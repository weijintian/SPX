import SwiftUI
import AVFoundation
import CallKit
import RTCRoomEngine
import TUICore

struct ActiveCall: Identifiable {
    let id: String
    let peer: String
    let name: String
    let avatar: String
    let outgoing: Bool
    let video: Bool
    let room: UInt32
    var connected = false
    var startedAt: Date?
    var sdkID: String?
    var systemID: UUID { UUID(uuidString: id)! }
}

/// The authenticated backend session also travels in Tencent userData.
@MainActor final class CallCoordinator: NSObject, ObservableObject {
    static let shared = CallCoordinator()
    @Published private(set) var call: ActiveCall?
    @Published var minimized = false
    @Published private(set) var muted = false
    @Published private(set) var speaker = false
    @Published private(set) var cameraEnabled = true
    @Published var notice: String?
    private var userID = 0
    private var ready = false
    private var initializing: Task<Void, Error>?
    private var sdkTeardown: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var epoch = UUID()
    private var tickerID = UUID()
    private var active = true
    private var preparing = false
    private var receiving = false
    private var finishing = false
    private var front = true
    private var systemCallID: UUID?
    private var connectingID: String?
    private var answering = false
    private let controller = CXCallController()
    private var observer = TencentCallObserver()
    private lazy var provider: CXProvider = {
        let config = CXProviderConfiguration(localizedName: "SPX Chat")
        config.supportsVideo = true; config.maximumCallsPerCallGroup = 1
        config.ringtoneSound = "call_ringing.wav"
        config.maximumCallGroups = 1; config.supportedHandleTypes = [.generic]
        let provider = CXProvider(configuration: config)
        provider.setDelegate(self, queue: .main); return provider
    }()
    private let terminal = Set(["ended", "canceled", "rejected", "busy", "timeout", "failed", "offline"])
    func start(userID: Int) { if self.userID != userID { stop(); self.userID = userID }; schedule() }
    func setActive(_ value: Bool) { active = value; if value || call != nil { schedule() } else { ticker?.cancel(); ticker = nil } }
    func stop(resetSounds: Bool = true) {
        if resetSounds { ChatSoundEffects.shared.reset() } else { ChatSoundEffects.shared.stopCall() }
        epoch = UUID(); tickerID = UUID(); ticker?.cancel(); ticker = nil
        let pendingInitialization = initializing; pendingInitialization?.cancel(); initializing = nil
        let pendingTeardown = sdkTeardown
        let oldObserver = observer; oldObserver.enabled = false; observer = TencentCallObserver()
        let hadSDK = ready || pendingInitialization != nil
        if hadSDK { TUICallEngine.createInstance().closeMicrophone(); if call?.video == true { TUICallEngine.createInstance().closeCamera() } }
        if let call, systemCallID == call.systemID { provider.reportCall(with: call.systemID, endedAt: Date(), reason: .remoteEnded) }
        systemCallID = nil; connectingID = nil; answering = false
        sdkTeardown = Task {
            await pendingTeardown?.value
            _ = try? await pendingInitialization?.value
            if hadSDK { _ = await Self.terminateSDK(reject: false); TUICallEngine.createInstance().removeObserver(oldObserver); TUICallEngine.destroyInstance() }
        }
        ready = false; userID = 0; call = nil; preparing = false; receiving = false; finishing = false; minimized = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    private func schedule() {
        guard ticker == nil, userID > 0 else { return }
        let generation = epoch, tickerGeneration = UUID(); tickerID = tickerGeneration
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, generation == self.epoch, tickerGeneration == self.tickerID else { return }
                do {
                    if self.finishing { try await Task.sleep(for: .milliseconds(200)); continue }
                    try await self.ensureReady()
                    guard generation == self.epoch, tickerGeneration == self.tickerID, !Task.isCancelled else { return }
                    try await self.presence()
                    guard generation == self.epoch, tickerGeneration == self.tickerID, !Task.isCancelled else { return }
                    if let call = self.call {
                        let result = try await self.update(call.id, event: "heartbeat")
                        guard generation == self.epoch, tickerGeneration == self.tickerID else { return }
                        if self.call?.id == call.id, self.terminal.contains(result["state"].string) { await self.finish(event: "heartbeat", message: self.label(result["state"].string)) }
                    }
                } catch is CancellationError { return }
                catch { if generation == self.epoch, tickerGeneration == self.tickerID, self.call != nil { self.toast("网络不稳定，正在恢复通话状态") } }
                do { try await Task.sleep(for: .seconds(10)) } catch { return }
                guard generation == self.epoch, tickerGeneration == self.tickerID else { return }
                if !self.active && self.call == nil { self.ticker = nil; self.tickerID = UUID(); return }
            }
        }
    }
    private func ensureReady() async throws {
        let waitingEpoch = epoch
        await sdkTeardown?.value
        try Task.checkCancellation()
        guard waitingEpoch == epoch, userID > 0 else { throw APIError.staleSession }
        if ready { return }; if let initializing { try await initializing.value; return }
        let generation = epoch
        let task = Task<Void, Error> {
            let credentials = try await APIClient.shared.get("calls.credentials")
            guard generation == epoch else { throw APIError.staleSession }
            let sdk = Int32(credentials["sdk_app_id"].int), identity = credentials["user_id"].string, signature = credentials["user_sig"].string
            guard sdk > 0, identity == "spx_\(userID)", !signature.isEmpty else { throw APIError.invalidResponse }
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                let completion = SDKOperationCompletion(c)
                TUILogin.login(sdk, userID: identity, userSig: signature) { Task { @MainActor in completion.succeed() } } fail: { code, message in Task { @MainActor in completion.resolve(.failure(APIError.server(Int(code), message ?? "通话登录失败"))) } }
            }
            try Task.checkCancellation(); guard generation == epoch else { throw APIError.staleSession }
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                let completion = SDKOperationCompletion(c)
                TUICallEngine.createInstance().`init`(sdk, userId: identity, userSig: signature) { Task { @MainActor in completion.succeed() } } fail: { code, message in Task { @MainActor in completion.resolve(.failure(APIError.server(Int(code), message ?? "通话服务连接失败"))) } }
            }
            guard generation == epoch else { throw APIError.staleSession }
            TUICallEngine.createInstance().addObserver(observer); ready = true
        }
        initializing = task; defer { if generation == epoch { initializing = nil } }; try await task.value
    }
    private func presence() async throws { try await APIClient.shared.post("calls.presence", ["device_id": .string(Keychain.deviceID), "ready": .bool(ready)]) }
    func dial(chat: Record, video: Bool) {
        guard call == nil, !preparing, !receiving, !finishing else { toast("当前已有通话，请先结束"); return }
        guard chat.id > 0 else { toast("会话不可用，请重新打开好友资料"); return }
        preparing = true; let generation = epoch, id = UUID().uuidString.lowercased()
        VoicePlayback.shared.stop()
        Task {
            defer { if generation == epoch { preparing = false } }
            do {
                try await permissions(video: video)
                guard generation == epoch else { return }
                try await ensureReady()
                guard generation == epoch else { return }
                try await presence()
                guard generation == epoch else { return }
                var data = try await APIClient.shared.post("calls.prepare", ["chat_id": chat["id"], "device_id": .string(Keychain.deviceID), "session_id": .string(id), "media": .string(video ? "video" : "audio")])
                guard generation == epoch else { throw APIError.staleSession }
                let room = UInt32.random(in: 1...UInt32(Int32.max))
                call = ActiveCall(id: id, peer: data["peer_user_id"].string, name: data["peer_name"].string, avatar: data["peer_avatar"].string, outgoing: true, video: video, room: room)
                resetControls(video: video)
                try await systemStart()
                guard call?.id == id, generation == epoch else { return }
                if call?.connected == false { ChatSoundEffects.shared.beginCall(id) }
                // Keep ringing while an offline recipient becomes ready, matching Android.
                let deadline = Date().addingTimeInterval(63)
                while !data["peer_ready"].flag {
                    guard generation == epoch, call?.id == id else { return }
                    guard Date() < deadline else { await finish(event: "timeout", message: "无人接听"); return }
                    try await Task.sleep(for: .seconds(2))
                    data = try await APIClient.shared.get("calls.session", ["session_id": id, "device_id": Keychain.deviceID])
                    guard generation == epoch, call?.id == id else { return }
                    if terminal.contains(data["state"].string) { await finish(event: "heartbeat", message: label(data["state"].string)); return }
                }
                guard call?.id == id, generation == epoch else { return }
                let params = TUICallParams(); params.timeout = Int32(max(1, min(60, Int(deadline.timeIntervalSinceNow)))); params.userData = id
                let roomID = TUIRoomId(); roomID.intRoomId = room; params.roomId = roomID
                try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                    let completion = SDKOperationCompletion(c)
                    TUICallEngine.createInstance().calls(userIdList: [data["peer_user_id"].string], callMediaType: video ? .video : .audio, params: params) { Task { @MainActor in completion.succeed() } } fail: { code, message in Task { @MainActor in completion.resolve(.failure(APIError.server(Int(code), message ?? "呼叫失败"))) } }
                }
            } catch { if generation == epoch { if call?.id == id { await finish(event: "failed", message: error.localizedDescription) } else if call == nil { toast(error.localizedDescription) } } }
        }
    }
    private func resetControls(video: Bool) { minimized = false; muted = false; speaker = video; cameraEnabled = video; front = true; systemCallID = nil; connectingID = nil; answering = false }
    private func systemStart() async throws {
        guard let call else { return }
        let action = CXStartCallAction(call: call.systemID, handle: CXHandle(type: .generic, value: call.name)); action.isVideo = call.video
        do {
            try await controller.request(CXTransaction(action: action))
            guard self.call?.id == call.id else { return }
            systemCallID = call.systemID
        } catch {
            // CallKit is unavailable on some devices/regions. Foreground RTC must still work.
            guard self.call?.id == call.id else { throw CancellationError() }
            guard active else { throw APIError.server(503, "系统通话服务暂不可用，请回到 App 重试") }
            systemCallID = nil
        }
    }
    func receive(id: String, sdkID: String, caller: String, video: Bool, room: UInt32) async {
        guard UUID(uuidString: id) != nil, userID > 0, call == nil, !preparing, !receiving, !finishing else { return }
        receiving = true; let generation = epoch
        defer { if generation == epoch { receiving = false } }
        VoicePlayback.shared.stop()
        do {
            try await presence()
            let data = try await APIClient.shared.get("calls.session", ["session_id": id, "device_id": Keychain.deviceID])
            guard generation == epoch, data["callee_id"].int == userID, data["peer_user_id"].string == caller, data["media"].string == (video ? "video" : "audio"), data["state"].string == "ringing" else { return }
            call = ActiveCall(id: id, peer: caller, name: data["peer_name"].string, avatar: data["peer_avatar"].string, outgoing: false, video: video, room: room, sdkID: sdkID)
            resetControls(video: video)
            let update = CXCallUpdate(); update.remoteHandle = CXHandle(type: .generic, value: data["peer_name"].string); update.hasVideo = video
            do {
                try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                    provider.reportNewIncomingCall(with: UUID(uuidString: id)!, update: update) { error in if let error { c.resume(throwing: error) } else { c.resume() } }
                }
                guard generation == epoch, call?.id == id else { return }
                systemCallID = UUID(uuidString: id)
            } catch {
                guard generation == epoch, call?.id == id, active else { throw error }
                systemCallID = nil
                ChatSoundEffects.shared.beginCall(id)
            }
            schedule()
        } catch { if call?.id == id { await finish(event: "failed", message: error.localizedDescription) } }
    }
    func answer() {
        guard let current = call, !current.outgoing, !current.connected, !answering else { return }
        Task {
            if systemCallID == current.systemID {
                do { try await controller.request(CXTransaction(action: CXAnswerCallAction(call: current.systemID))); return }
                catch { guard call?.id == current.id else { return }; systemCallID = nil }
            }
            do { try await acceptIncoming(current) }
            catch { if call?.id == current.id { await finish(event: "failed", message: error.localizedDescription) } }
        }
    }
    private func acceptIncoming(_ current: ActiveCall) async throws {
        guard !answering, call?.id == current.id else { throw CancellationError() }
        answering = true; defer { if call?.id == current.id { answering = false } }
        try await permissions(video: current.video)
        guard call?.id == current.id, !finishing else { throw CancellationError() }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            let completion = SDKOperationCompletion(c)
            TUICallEngine.createInstance().accept { Task { @MainActor in completion.succeed() } } fail: { code, message in
                Task { @MainActor in completion.resolve(.failure(APIError.server(Int(code), message ?? "接听失败，请重试"))) }
            }
        }
    }
    func end() { guard let call else { return }; Task { if systemCallID != call.systemID { await finish(event: call.connected ? "ended" : (call.outgoing ? "canceled" : "rejected"), message: nil); return }; do { try await controller.request(CXTransaction(action: CXEndCallAction(call: call.systemID))) } catch { guard self.call?.id == call.id else { return }; await finish(event: call.connected ? "ended" : (call.outgoing ? "canceled" : "rejected"), message: nil) } } }
    private func permissions(video: Bool) async throws {
        guard await AVAudioApplication.requestRecordPermission() else { throw APIError.server(0, "请在系统设置允许麦克风权限") }
        if video { guard await AVCaptureDevice.requestAccess(for: .video) else { throw APIError.server(0, "请在系统设置允许相机权限") } }
    }
    private func update(_ id: String, event: String) async throws -> JSON { try await APIClient.shared.post("calls.update", ["session_id": .string(id), "device_id": .string(Keychain.deviceID), "event": .string(event)]) }
    func connected(sdkID: String, sessionID: String, room: UInt32) async {
        guard matches(sdkID: sdkID, sessionID: sessionID, room: room), let current = call, !current.connected else { return }
        guard connectingID != current.id else { return }
        connectingID = current.id; call?.sdkID = sdkID
        defer { if connectingID == current.id { connectingID = nil } }
        do {
            if systemCallID == nil {
                let audio = AVAudioSession.sharedInstance()
                try audio.setCategory(.playAndRecord, mode: current.video ? .videoChat : .voiceChat, options: [.allowBluetooth])
                try audio.setActive(true)
                ChatSoundEffects.shared.setCallAudioActive(true)
            }
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                let completion = SDKOperationCompletion(c)
                TUICallEngine.createInstance().openMicrophone { Task { @MainActor in completion.succeed() } } fail: { code, message in
                    Task { @MainActor in completion.resolve(.failure(APIError.server(Int(code), message ?? "无法打开麦克风，请检查权限"))) }
                }
            }
            guard call?.id == current.id, !finishing else { return }
            TUICallEngine.createInstance().selectAudioPlaybackDevice(speaker ? .speakerphone : .earpiece)
            call?.connected = true; call?.startedAt = Date()
            ChatSoundEffects.shared.connected(current.id)
            if current.outgoing, systemCallID == current.systemID { provider.reportOutgoingCall(with: current.systemID, connectedAt: Date()) }
            reportStatus(current.id, event: "connected")
        } catch { if call?.id == current.id { await finish(event: "failed", message: error.localizedDescription) } }
    }
    private func reportStatus(_ id: String, event: String) {
        let generation = epoch
        Task {
            for attempt in 0..<3 {
                guard generation == epoch, event != "connected" || call?.id == id else { return }
                do { _ = try await update(id, event: event); return }
                catch { if attempt < 2 { try? await Task.sleep(for: .seconds(attempt + 1)) } }
            }
        }
    }
    private func matches(sdkID: String, sessionID: String, room: UInt32) -> Bool {
        guard let current = call else { return false }; if let existing = current.sdkID { return existing == sdkID }
        return !sdkID.isEmpty && (sessionID == current.id || (current.outgoing && room > 0 && room == current.room))
    }
    func sdkEnded(sdkID: String, sessionID: String, room: UInt32, reason: UInt) async {
        guard matches(sdkID: sdkID, sessionID: sessionID, room: room), let current = call else { return }
        // TUICallDefine.h in pinned RTCRoomEngine 3.2.1 defines values 0...9.
        let mapped: (String, String?)
        switch reason {
        case 1: mapped = ("ended", nil)
        case 2, 8: mapped = ("sdk_rejected", current.outgoing ? "对方已拒绝" : nil)
        case 3: mapped = ("sdk_timeout", "无人接听")
        case 5: mapped = ("sdk_busy", "对方忙线中")
        case 6: mapped = ("sdk_canceled", current.outgoing ? nil : "对方已取消")
        case 7: mapped = ("sdk_canceled", "已在其他设备接听")
        default: mapped = (current.connected ? "ended" : "failed", "通话连接已结束，请检查网络后重试")
        }
        await finish(event: mapped.0, message: mapped.1, sdkAlreadyEnded: true)
    }
    // Legacy peer-only callbacks have no call ID. Correlated onCallNotConnected is authoritative.
    func peerResult(_ peer: String, event: String) async {
        guard let current = call, current.peer == peer, !current.connected else { return }
        if let state = try? await APIClient.shared.get("calls.session", ["session_id": current.id, "device_id": Keychain.deviceID]), call?.id == current.id, terminal.contains(state["state"].string) {
            await finish(event: "heartbeat", message: label(state["state"].string))
        }
    }
    func expired() {
        if ready { TUICallEngine.createInstance().removeObserver(observer) }
        observer.enabled = false; observer = TencentCallObserver()
        ready = false
        if call != nil { Task { await finish(event: "failed", message: "通话登录已过期，请重新呼叫") } }
        schedule()
    }
    /// SDK kicks are not application logout. Re-authenticate through calls.credentials after teardown.
    func kickedOffline() {
        guard userID > 0, ready || initializing != nil else { return }
        let owner = userID, previous = call?.id
        stop(resetSounds: false); userID = owner
        let generation = epoch
        toast("通话连接已断开，正在重新连接")
        if let previous { Task { guard generation == epoch else { return }; _ = try? await update(previous, event: "failed") } }
        schedule()
    }
    private static func terminateSDK(reject: Bool) async -> Bool {
        await withCheckedContinuation { continuation in
            let completion = SDKTerminationCompletion(continuation)
            completion.timeout = Task {
                do { try await Task.sleep(for: .seconds(6)); completion.resolve(false) } catch { }
            }
            let success: () -> Void = { Task { @MainActor in completion.resolve(true) } }
            if reject {
                TUICallEngine.createInstance().reject(succ: success, fail: { _, _ in Task { @MainActor in completion.resolve(false) } })
            } else {
                TUICallEngine.createInstance().hangup(succ: success, fail: { _, _ in Task { @MainActor in completion.resolve(false) } })
            }
        }
    }
    private func finish(event: String, message: String?, sdkAlreadyEnded: Bool = false) async {
        guard !finishing, let current = call else { return }
        finishing = true
        ChatSoundEffects.shared.stopCall()
        TUICallEngine.createInstance().closeMicrophone()
        if current.video { TUICallEngine.createInstance().closeCamera() }
        let generation = epoch
        call = nil; minimized = false
        if systemCallID == current.systemID { provider.reportCall(with: current.systemID, endedAt: Date(), reason: event == "failed" ? .failed : .remoteEnded) }
        systemCallID = nil; connectingID = nil; answering = false
        reportStatus(current.id, event: event)
        let clean: Bool
        if sdkAlreadyEnded { clean = true }
        else { clean = await Self.terminateSDK(reject: !current.outgoing && !current.connected) }
        guard generation == epoch else { return }
        if !clean {
            // A timed-out teardown must not overlap a new SDK call.
            ready = false
            observer.enabled = false
            TUICallEngine.createInstance().removeObserver(observer)
            observer = TencentCallObserver()
            TUICallEngine.destroyInstance()
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        finishing = false
        schedule()
        if call == nil, let message { toast(message) }
    }
    private func label(_ state: String) -> String? { ["timeout": "无人接听", "rejected": "对方已拒绝", "busy": "对方忙线中", "failed": "通话连接中断"][state] }
    func toggleMute() {
        guard let current = call, current.connected, !finishing else { return }
        if muted { TUICallEngine.createInstance().openMicrophone { Task { @MainActor in if self.call?.id == current.id { self.muted = false } } } fail: { _, message in Task { @MainActor in if self.call?.id == current.id { self.toast(message ?? "麦克风打开失败") } } } }
        else { TUICallEngine.createInstance().closeMicrophone(); muted = true }
    }
    func toggleSpeaker() { guard call != nil else { return }; speaker.toggle(); TUICallEngine.createInstance().selectAudioPlaybackDevice(speaker ? .speakerphone : .earpiece) }
    func switchCamera() { guard call?.video == true else { return }; front.toggle(); TUICallEngine.createInstance().switchCamera(front ? .front : .back) }
    func toggleCamera() { guard call?.video == true else { return }; cameraEnabled.toggle(); if !cameraEnabled { TUICallEngine.createInstance().closeCamera() } }
    func attachVideo(_ view: UIView, local: Bool) {
        guard let call else { return }
        if local, cameraEnabled { TUICallEngine.createInstance().openCamera(front ? .front : .back, videoView: view) {} fail: { _, message in Task { @MainActor in self.toast(message ?? "相机打开失败") } } }
        else if !local { TUICallEngine.createInstance().startRemoteView(userId: call.peer, videoView: view) { _ in } onLoading: { _ in } onError: { _, _, message in Task { @MainActor in if self.call?.id == call.id { self.toast(message ?? "远端画面加载失败，请检查网络") } } } }
    }
    func toast(_ text: String) { notice = text; noticeTask?.cancel(); noticeTask = Task { do { try await Task.sleep(for: .seconds(2)); notice = nil } catch {} } }
}

extension CallCoordinator: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) { Task { @MainActor in await finish(event: "failed", message: nil) } }
    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) { Task { @MainActor in guard let call, call.systemID == action.callUUID else { action.fail(); return }; provider.reportOutgoingCall(with: call.systemID, startedConnectingAt: Date()); action.fulfill() } }
    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor in
            guard let current = call, current.systemID == action.callUUID else { action.fail(); return }
            do { try await acceptIncoming(current); action.fulfill() }
            catch { action.fail(); if call?.id == current.id { await finish(event: "rejected", message: error.localizedDescription) } }
        }
    }
    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) { Task { @MainActor in guard let current = call, current.systemID == action.callUUID else { action.fulfill(); return }; action.fulfill(); await finish(event: current.connected ? "ended" : (current.outgoing ? "canceled" : "rejected"), message: nil) } }
    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        try? audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
        Task { @MainActor in ChatSoundEffects.shared.setCallAudioActive(true) }
    }
    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) { Task { @MainActor in ChatSoundEffects.shared.setCallAudioActive(false) } }
}

private final class TencentCallObserver: NSObject, TUICallObserver {
    @MainActor var enabled = true
    func onCallReceived(_ callId: String, callerId: String, calleeIdList: [String], mediaType: TUICallMediaType, info: TUICallObserverExtraInfo) { let session = info.userData, room = info.roomId.intRoomId; Task { @MainActor in guard self.enabled else { return }; await CallCoordinator.shared.receive(id: session, sdkID: callId, caller: callerId, video: mediaType == .video, room: room) } }
    func onCallBegin(callId: String, mediaType: TUICallMediaType, info: TUICallObserverExtraInfo) { let session = info.userData, room = info.roomId.intRoomId; Task { @MainActor in guard self.enabled else { return }; await CallCoordinator.shared.connected(sdkID: callId, sessionID: session, room: room) } }
    func onCallEnd(callId: String, mediaType: TUICallMediaType, reason: TUICallEndReason, userId: String, totalTime: Float, info: TUICallObserverExtraInfo) { ended(callId, reason: reason, info: info) }
    func onCallNotConnected(callId: String, mediaType: TUICallMediaType, reason: TUICallEndReason, userId: String, info: TUICallObserverExtraInfo) { ended(callId, reason: reason, info: info) }
    private func ended(_ id: String, reason: TUICallEndReason, info: TUICallObserverExtraInfo) { let session = info.userData, room = info.roomId.intRoomId, rawReason = reason.rawValue; Task { @MainActor in guard self.enabled else { return }; await CallCoordinator.shared.sdkEnded(sdkID: id, sessionID: session, room: room, reason: rawReason) } }
    func onUserReject(userId: String) { Task { @MainActor in guard self.enabled else { return }; await CallCoordinator.shared.peerResult(userId, event: "sdk_rejected") } }
    func onUserLineBusy(userId: String) { Task { @MainActor in guard self.enabled else { return }; await CallCoordinator.shared.peerResult(userId, event: "sdk_busy") } }
    func onUserNoResponse(userId: String) { Task { @MainActor in guard self.enabled else { return }; await CallCoordinator.shared.peerResult(userId, event: "sdk_timeout") } }
    func onError(code: Int32, message: String?) { Task { @MainActor in guard self.enabled else { return }; CallCoordinator.shared.toast(message ?? "通话服务暂不可用") } }
    func onUserSigExpired() { Task { @MainActor in guard self.enabled else { return }; CallCoordinator.shared.expired() } }
    func onKickedOffline() { Task { @MainActor in guard self.enabled else { return }; CallCoordinator.shared.kickedOffline() } }
}

/// Resume at most once even if the SDK callback arrives after its timeout.
@MainActor private final class SDKTerminationCompletion {
    private var continuation: CheckedContinuation<Bool, Never>?
    var timeout: Task<Void, Never>?
    init(_ continuation: CheckedContinuation<Bool, Never>) { self.continuation = continuation }
    func resolve(_ success: Bool) {
        guard let continuation else { return }
        self.continuation = nil; timeout?.cancel(); timeout = nil
        continuation.resume(returning: success)
    }
}

@MainActor private final class SDKOperationCompletion {
    private var continuation: CheckedContinuation<Void, Error>?
    private var timeout: Task<Void, Never>?
    init(_ continuation: CheckedContinuation<Void, Error>) {
        self.continuation = continuation
        timeout = Task {
            do { try await Task.sleep(for: .seconds(20)); resolve(.failure(APIError.server(408, "通话服务响应超时，请重试"))) } catch { }
        }
    }
    func succeed() { resolve(.success(Void())) }
    func resolve(_ result: Result<Void, Error>) {
        guard let continuation else { return }
        self.continuation = nil; timeout?.cancel(); timeout = nil; continuation.resume(with: result)
    }
}
