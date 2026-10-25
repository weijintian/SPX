import SwiftUI
import AVFoundation
import UIKit

struct ActiveCall: Identifiable {
    let id: String
    let peer: String
    let name: String
    let avatar: String
    let outgoing: Bool
    let video: Bool
    var room: UInt32 = 0
    var connected = false
    var startedAt: Date?
    var sdkID: String?

    func matchesSDK(_ sdkID: String, sessionID: String, room: UInt32, retired: Set<String>) -> Bool {
        guard !sdkID.isEmpty, !retired.contains(sdkID) else { return false }
        if let bound = self.sdkID { return bound == sdkID }
        guard outgoing, sessionID.isEmpty || sessionID == id else { return false }
        // Each retry has a new room, even though the business UUID is unchanged.
        return room > 0 && room == self.room
    }
    func canRetryOffline(reason: UInt, deadline: Date?, now: Date = Date()) -> Bool {
        outgoing && !connected && reason == 4 && (deadline.map { now < $0 } ?? false)
    }
}

/// Implements Android's existing engine-v1 contract and application-owned UI.
/// The authenticated business UUID travels in Tencent userData in both directions.
@MainActor final class CallCoordinator: ObservableObject {
    static let shared = CallCoordinator()
    @Published private(set) var call: ActiveCall?
    @Published var minimized = false
    @Published private(set) var muted = false
    @Published private(set) var speaker = false
    @Published private(set) var cameraEnabled = true
    @Published private(set) var answering = false
    @Published var notice: String?
    private var userID = 0
    private var epoch = UUID()
    private let device = Keychain.deviceID
    private var active = true, ready = false, preparing = false, finishing = false
    private var engine: SPXCallEngineBridge?
    private var initialization: Task<Void, Error>?
    private var teardown: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var maintenance: Task<Void, Never>?
    private var dialing: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var incomingSDK: String?
    private var incomingEnded: Set<String> = []
    private var attempt = UUID(), mediaEpoch = UUID()
    private var invited = false, mediaReady = false, peerReady = false
    private var retired: Set<String> = []
    private var deadline: Date?
    private var nextInvite = Date.distantPast, nextLogin = Date.distantPast
    private var credentialExpiry = Date.distantPast
    private var lastPresence = Date.distantPast, lastHeartbeat = Date.distantPast
    private var front = true, micOpening = false, micOpened = false, cameraOpening = false
    private var micRevision = UUID(), cameraRevision = UUID()
    private let preview = UIView()
    private weak var localView: UIView?
    private weak var remoteView: UIView?
    private weak var cameraView: UIView?
    private weak var renderedRemoteView: UIView?
    private let terminal = Set(["ended", "canceled", "rejected", "busy", "timeout", "failed", "offline"])

    func start(userID: Int) {
        guard userID > 0 else { return }
        if self.userID != userID { stop(); self.userID = userID }
        schedule()
    }
    func setActive(_ value: Bool) {
        active = value
        if value { lastPresence = .distantPast; schedule(); media() }
        else {
            cameraRevision = UUID(); engine?.closeCamera(); cameraView = nil
            let generation = epoch
            Task { if generation == epoch { _ = try? await presence(false) } }
        }
    }
    func stop(resetSounds: Bool = true) {
        let oldEngine = engine, pending = initialization, previous = teardown
        epoch = UUID()
        ticker?.cancel(); ticker = nil; maintenance?.cancel(); maintenance = nil
        dialing?.cancel(); dialing = nil; initialization = nil
        oldEngine?.eventHandler = nil
        oldEngine?.closeMicrophone(); oldEngine?.closeCamera()
        if let call { oldEngine?.stopRemote(userID: call.peer) }
        // A different account must wait for the old singleton to finish retiring.
        teardown = Task {
            await previous?.value
            _ = try? await pending?.value
            if let oldEngine {
                _ = try? await Self.operation(seconds: 6) { ok, fail in oldEngine.terminate(reject: false, success: ok, failure: fail) }
                oldEngine.invalidate()
            }
        }
        engine = nil; ready = false; userID = 0; preparing = false; finishing = false
        incomingSDK = nil; incomingEnded = []; attempt = UUID(); mediaEpoch = UUID()
        call = nil; minimized = false; answering = false; deadline = nil; mediaReady = false
        localView = nil; remoteView = nil; cameraView = nil; renderedRemoteView = nil
        micOpening = false; micOpened = false; nextLogin = .distantPast
        UIApplication.shared.isIdleTimerDisabled = false
        if resetSounds { ChatSoundEffects.shared.reset() } else { ChatSoundEffects.shared.stopCall() }
    }
    private func schedule() {
        guard ticker == nil, userID > 0 else { return }
        let generation = epoch
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.epoch == generation else { return }
                if !self.finishing, let call = self.call, !call.connected, let deadline = self.deadline, Date() >= deadline {
                    await self.finish(event: "timeout", message: "无人接听")
                }
                // Network requests run separately so their timeout cannot block the ring deadline.
                if self.maintenance == nil, !self.finishing, self.active || self.call != nil {
                    self.maintenance = Task {
                        defer { if generation == self.epoch { self.maintenance = nil } }
                        await self.maintain(generation)
                    }
                }
                do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
            }
        }
    }
    private func maintain(_ generation: UUID) async {
        do {
            if ready, call == nil, engine?.isLoggedIn(as: "spx_\(userID)") != true {
                ready = false; lastPresence = .distantPast
            }
            if !ready || (call == nil && credentialExpiry < Date()) {
                guard Date() >= nextLogin else { return }
                try await ensureReady()
            }
            guard generation == epoch, !Task.isCancelled, !finishing else { return }
            if active, Date().timeIntervalSince(lastPresence) >= 15 { try await presence(true) }
            guard generation == epoch, !finishing, let current = call else { return }
            let interval: TimeInterval = current.outgoing && !current.connected && !invited ? 2 : 8
            if Date().timeIntervalSince(lastHeartbeat) >= interval {
                lastHeartbeat = Date()
                let data = try await update(current.id, event: "heartbeat")
                guard generation == epoch, call?.id == current.id, !finishing else { return }
                if terminal.contains(data["state"].string) {
                    await finish(event: nil, message: label(data["state"].string)); return
                }
                peerReady = data["peer_ready"].flag
            }
            inviteWhenReady()
        } catch is CancellationError {
        } catch {
            guard generation == epoch else { return }
            if let rejection = error as? APIError, rejection.definitiveRejection, call != nil {
                await finish(event: "failed", message: rejection.localizedDescription)
            }
            // Transient HTTP errors do not cancel an invitation or active audio.
        }
    }
    private func ensureReady() async throws {
        let generation = epoch, owner = userID
        await teardown?.value
        try Task.checkCancellation()
        guard epoch == generation, owner > 0 else { throw APIError.staleSession }
        if ready, credentialExpiry > Date(), engine?.isLoggedIn(as: "spx_\(owner)") == true { return }
        if let initialization { try await initialization.value; return }
        let task = Task<Void, Error> {
            let data = try await APIClient.shared.request("calls.credentials", timeout: 8)
            guard epoch == generation else { throw APIError.staleSession }
            let sdk = Int32(data["sdk_app_id"].int), identity = data["user_id"].string, signature = data["user_sig"].string
            guard sdk > 0, identity == "spx_\(owner)", !signature.isEmpty else { throw APIError.invalidResponse }
            let bridge = engine ?? SPXCallEngineBridge()
            engine = bridge
            bridge.eventHandler = { [weak self] data in
                Task { @MainActor in
                    guard let self, self.epoch == generation else { return }
                    await self.sdkEvent(data)
                }
            }
            // Engine.init authenticates the native RoomEngine account, just as Android does.
            try await Self.operation { ok, fail in bridge.initialize(appID: sdk, userID: identity, userSig: signature, success: ok, failure: fail) }
            guard epoch == generation else { throw APIError.staleSession }
            guard bridge.isLoggedIn(as: identity) else { throw APIError.server(409, "通话账号登录尚未完成，请重试") }
            ready = true; credentialExpiry = Date().addingTimeInterval(45 * 60)
            lastPresence = .distantPast; nextLogin = .distantPast
        }
        initialization = task
        defer { if epoch == generation { initialization = nil } }
        do { try await task.value }
        catch { if epoch == generation { ready = false; nextLogin = Date().addingTimeInterval(15) }; throw error }
    }
    @discardableResult private func presence(_ value: Bool) async throws -> JSON {
        let generation = epoch
        let data = try await APIClient.shared.request("calls.presence", method: "POST",
            body: ["device_id": .string(device), "ready": .bool(value && ready)], timeout: 8)
        guard epoch == generation else { throw APIError.staleSession }
        if value { lastPresence = Date() }
        return data
    }
    private func update(_ id: String, event: String) async throws -> JSON {
        try await APIClient.shared.request("calls.update", method: "POST",
            body: ["session_id": .string(id), "device_id": .string(device), "event": .string(event)], timeout: 8)
    }
    func dial(chat: Record, video: Bool) {
        guard userID > 0 else { toast("请先登录"); return }
        guard call == nil, !preparing, incomingSDK == nil, !finishing else { minimized = false; toast("当前正在处理通话"); return }
        guard chat.id > 0 else { toast("会话不可用，请重新打开好友资料"); return }
        preparing = true
        let generation = epoch, id = UUID().uuidString.lowercased()
        VoicePlayback.shared.stop()
        Task {
            defer { if epoch == generation { preparing = false } }
            do {
                try await permissions(video: video)
                guard epoch == generation, active else { return }
                try await ensureReady()
                guard epoch == generation else { return }
                try await presence(true)
                guard epoch == generation else { return }
                let data = try await APIClient.shared.request("calls.prepare", method: "POST", body: [
                    "chat_id": chat["id"], "device_id": .string(device),
                    "session_id": .string(id), "media": .string(video ? "video" : "audio")], timeout: 8)
                guard epoch == generation else { return }
                guard data["session_id"].string == id, data["state"].string == "ringing", data["caller_id"].int == userID,
                      data["peer_user_id"].string == "spx_\(data["callee_id"].int)", data["callee_id"].int > 0 else { throw APIError.invalidResponse }
                if !active { _ = try? await update(id, event: "canceled"); return }
                call = ActiveCall(id: id, peer: data["peer_user_id"].string, name: data["peer_name"].string,
                    avatar: data["peer_avatar"].string, outgoing: true, video: video)
                resetControls(video)
                deadline = Date().addingTimeInterval(63); lastHeartbeat = .distantPast; peerReady = data["peer_ready"].flag
                ChatSoundEffects.shared.beginCall(id); inviteWhenReady()
            } catch {
                guard epoch == generation else { return }
                if call?.id == id { await finish(event: "failed", message: error.localizedDescription) }
                else {
                    // Also cleans a prepare response lost after the server committed it.
                    Task { await reportEnd(id, event: "failed", generation: generation) }
                    toast(error.localizedDescription)
                }
            }
        }
    }
    private func inviteWhenReady() {
        guard let current = call, current.outgoing, !current.connected, !invited, dialing == nil, !finishing,
              ready, peerReady, Date() >= nextInvite, let deadline, deadline > Date(), let engine else { return }
        let generation = epoch, token = UUID()
        attempt = token; invited = true; mediaReady = false
        let room = UInt32.random(in: 1...UInt32(Int32.max))
        call?.room = room; call?.sdkID = nil
        dialing = Task {
            defer { if epoch == generation, attempt == token { dialing = nil } }
            do {
                try await Self.operation { ok, fail in
                    engine.call(userID: current.peer, video: current.video, sessionID: current.id, room: room,
                        timeout: Int32(max(1, min(60, Int(deadline.timeIntervalSinceNow)))), success: ok, failure: fail)
                }
                guard epoch == generation, attempt == token, call?.id == current.id, !finishing else { return }
                mediaReady = true; media()
            } catch {
                guard epoch == generation, attempt == token, call?.id == current.id, !finishing else { return }
                await finish(event: "failed", message: error.localizedDescription)
            }
        }
    }
    private func resetControls(_ video: Bool) {
        minimized = false; answering = false; muted = false; speaker = video; cameraEnabled = video; front = true
        mediaEpoch = UUID(); mediaReady = false; micOpened = false; micOpening = false; cameraOpening = false
        retired = []; invited = false; nextInvite = .distantPast
        localView = nil; remoteView = nil; cameraView = nil; renderedRemoteView = nil
        UIApplication.shared.isIdleTimerDisabled = true
    }
    private func receive(_ event: [String: Any]) async {
        let id = event["session_id"] as? String ?? "", sdkID = event["sdk_id"] as? String ?? ""
        let caller = event["peer"] as? String ?? "", video = event["video"] as? Bool ?? false
        let callees = event["callees"] as? [String] ?? []
        guard call == nil, incomingSDK == nil, userID > 0 else { return }
        if preparing || finishing { await rejectInvitation(); return }
        guard UUID(uuidString: id)?.uuidString.lowercased() == id, !sdkID.isEmpty,
              caller != "spx_\(userID)", callees.contains("spx_\(userID)"), active else {
            await rejectInvitation(); return
        }
        incomingSDK = sdkID; incomingEnded = []
        let generation = epoch
        defer { if epoch == generation, incomingSDK == sdkID { incomingSDK = nil; incomingEnded = [] } }
        do {
            try await ensureReady()
            guard epoch == generation, !incomingEnded.contains(sdkID) else { return }
            try await presence(true)
            let data = try await APIClient.shared.request("calls.session", query: ["session_id": id, "device_id": device], timeout: 8)
            guard epoch == generation, !incomingEnded.contains(sdkID), call == nil else { return }
            guard active, data["session_id"].string == id, data["callee_id"].int == userID,
                  data["peer_user_id"].string == caller, data["media"].string == (video ? "video" : "audio"),
                  data["state"].string == "ringing" else { throw APIError.invalidResponse }
            VoicePlayback.shared.stop()
            call = ActiveCall(id: id, peer: caller, name: data["peer_name"].string, avatar: data["peer_avatar"].string,
                outgoing: false, video: video, room: (event["room"] as? NSNumber)?.uint32Value ?? 0, sdkID: sdkID)
            resetControls(video)
            deadline = Date().addingTimeInterval(63); lastHeartbeat = .distantPast
            ChatSoundEffects.shared.beginCall(id); schedule()
        } catch {
            guard epoch == generation, !incomingEnded.contains(sdkID) else { return }
            await rejectInvitation(); toast(error.localizedDescription)
        }
    }
    private func rejectInvitation() async {
        guard let engine else { return }
        _ = try? await Self.operation(seconds: 6) { ok, fail in engine.terminate(reject: true, success: ok, failure: fail) }
    }
    func answer() {
        guard let current = call, !current.outgoing, !current.connected, !answering, !finishing, let engine else { return }
        answering = true
        let generation = epoch
        Task {
            do {
                try await permissions(video: current.video)
                guard epoch == generation, call?.id == current.id, !finishing else { return }
                ChatSoundEffects.shared.stopCall()
                try await Self.operation { ok, fail in engine.accept(success: ok, failure: fail) }
                guard epoch == generation, call?.id == current.id, !finishing else { return }
                mediaReady = true; media()
            } catch {
                if epoch == generation, call?.id == current.id { await finish(event: "failed", message: error.localizedDescription) }
            }
        }
    }
    func end() {
        guard let current = call, !finishing else { return }
        Task { if call?.id == current.id { await finish(event: current.connected ? "ended" : current.outgoing ? "canceled" : "rejected", message: nil) } }
    }
    private func permissions(video: Bool) async throws {
        guard await AVAudioApplication.requestRecordPermission() else { throw APIError.server(403, "请在系统设置允许麦克风权限") }
        if video, !(await AVCaptureDevice.requestAccess(for: .video)) { throw APIError.server(403, "请在系统设置允许相机权限") }
    }
    private func sdkEvent(_ event: [String: Any]) async {
        let kind = event["kind"] as? String ?? ""
        if kind == "received" { await receive(event); return }
        if kind == "error" {
            let code = (event["code"] as? NSNumber)?.intValue ?? 0
            if call != nil, !finishing { await finish(event: "failed", message: "通话错误（\(code)）：\(event["message"] as? String ?? "请重试")") }
            return
        }
        if kind == "expired" || kind == "kicked" {
            if call != nil { await finish(event: "failed", message: kind == "kicked" ? "通话账号已在其他设备登录" : "通话凭证已过期，请重新拨打") }
            ready = false; credentialExpiry = .distantPast; lastPresence = .distantPast
            nextLogin = Date().addingTimeInterval(kind == "kicked" ? 30 : 0)
            return
        }
        if kind == "video" {
            if event["peer"] as? String == call?.peer, event["available"] as? Bool == true { renderedRemoteView = nil; media() }
            return
        }
        let sdkID = event["sdk_id"] as? String ?? ""
        if incomingSDK == sdkID, kind == "end" { incomingEnded.insert(sdkID) }
        guard !finishing, let current = call, current.video == (event["video"] as? Bool ?? false),
              current.matchesSDK(sdkID, sessionID: event["session_id"] as? String ?? "",
                  room: (event["room"] as? NSNumber)?.uint32Value ?? 0, retired: retired) else { return }
        if kind == "begin", !current.connected {
            call?.sdkID = sdkID; call?.connected = true; call?.startedAt = Date()
            answering = false; deadline = nil; mediaReady = true
            ChatSoundEffects.shared.connected(current.id); ChatSoundEffects.shared.setCallAudioActive(true); media()
            let generation = epoch
            _ = try? await update(current.id, event: "connected")
            if generation == epoch, call?.id == current.id { lastHeartbeat = .distantPast }
        } else if kind == "end" {
            let reason = (event["reason"] as? NSNumber)?.uintValue ?? 0
            if current.canRetryOffline(reason: reason, deadline: deadline) {
                retired.insert(sdkID); call?.sdkID = nil; call?.room = 0
                attempt = UUID(); dialing?.cancel(); dialing = nil; invited = false; mediaReady = false; mediaEpoch = UUID()
                micOpened = false; micOpening = false; cameraOpening = false
                engine?.closeMicrophone(); engine?.closeCamera(); cameraView = nil
                nextInvite = Date().addingTimeInterval(8); lastHeartbeat = .distantPast
                return
            }
            let state: String
            switch reason {
            case 1: state = "ended"
            case 2, 8: state = current.outgoing ? "sdk_rejected" : "rejected"
            case 3: state = current.outgoing ? "sdk_timeout" : "timeout"
            case 5: state = current.outgoing ? "sdk_busy" : "busy"
            case 6, 7: state = current.outgoing ? "canceled" : "sdk_canceled"
            default: state = "failed"
            }
            await finish(event: state, message: label(state), sdkEnded: true)
        }
    }
    private func finish(event: String?, message: String?, sdkEnded: Bool = false) async {
        guard !finishing, let current = call else { return }
        finishing = true
        let generation = epoch, oldEngine = engine
        attempt = UUID(); mediaEpoch = UUID(); dialing?.cancel(); dialing = nil
        oldEngine?.closeMicrophone(); oldEngine?.closeCamera(); oldEngine?.stopRemote(userID: current.peer)
        ChatSoundEffects.shared.stopCall()
        call = nil; answering = false; minimized = false; deadline = nil; invited = false; mediaReady = false
        localView = nil; remoteView = nil; cameraView = nil; renderedRemoteView = nil
        UIApplication.shared.isIdleTimerDisabled = false
        Task { await reportEnd(current.id, event: event, generation: generation) }
        if !sdkEnded, let oldEngine {
            do { try await Self.operation(seconds: 6) { ok, fail in oldEngine.terminate(reject: !current.outgoing && !current.connected, success: ok, failure: fail) } }
            catch { if epoch == generation { oldEngine.invalidate(); engine = nil; ready = false; credentialExpiry = .distantPast } }
        }
        guard epoch == generation else { return }
        try? await Task.sleep(for: .milliseconds(250))
        guard epoch == generation else { return }
        finishing = false; micOpened = false; micOpening = false; cameraOpening = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if let message { toast(message) }
    }
    private func reportEnd(_ id: String, event: String?, generation: UUID) async {
        guard let event else { return }
        for retry in 0..<3 {
            guard epoch == generation else { return }
            do { _ = try await update(id, event: event); return }
            catch {
                if (error as? APIError)?.definitiveRejection == true { return }
                if retry < 2 { try? await Task.sleep(for: .seconds(1)) }
            }
        }
    }
    private func label(_ state: String) -> String? {
        ["timeout": "无人接听", "sdk_timeout": "无人接听", "rejected": "对方已拒绝", "sdk_rejected": "对方已拒绝",
         "busy": "对方忙线中", "sdk_busy": "对方忙线中", "failed": "通话连接中断",
         "canceled": "通话已取消", "sdk_canceled": "通话已取消"][state]
    }
    func toggleMute() {
        guard call != nil, !finishing else { return }
        muted.toggle(); micRevision = UUID()
        if muted { engine?.closeMicrophone(); micOpened = false } else { media() }
    }
    func toggleSpeaker() { guard call != nil else { return }; speaker.toggle(); engine?.setSpeaker(speaker) }
    func switchCamera() { guard call?.video == true else { return }; front.toggle(); engine?.switchCamera(front: front) }
    func toggleCamera() {
        guard call?.video == true else { return }
        cameraEnabled.toggle(); cameraRevision = UUID()
        if cameraEnabled { media() } else { engine?.closeCamera(); cameraView = nil }
    }
    func attachVideo(_ view: UIView, local: Bool) {
        if local { localView = view } else { remoteView = view }
        media()
    }
    func detachVideo(_ view: UIView, local: Bool) {
        if local, localView === view { localView = nil; cameraView = nil; media() }
        if !local, remoteView === view {
            if let call { engine?.stopRemote(userID: call.peer) }
            remoteView = nil; renderedRemoteView = nil
        }
    }
    private func media() {
        guard let current = call, let engine, !finishing, mediaReady else { return }
        let generation = epoch, id = current.id, mediaGeneration = mediaEpoch
        engine.setSpeaker(speaker)
        if !muted, !micOpened, !micOpening {
            micOpening = true
            let revision = micRevision
            engine.openMicrophone(success: { [weak self] in
                Task { @MainActor in
                    guard let self, self.epoch == generation, self.mediaEpoch == mediaGeneration, self.call?.id == id else { return }
                    self.micOpening = false
                    if revision != self.micRevision {
                        engine.closeMicrophone(); self.micOpened = false; self.media(); return
                    }
                    self.micOpened = !self.muted
                    if self.muted { engine.closeMicrophone() }
                }
            }, failure: { [weak self] code, message in
                Task { @MainActor in
                    guard let self, self.epoch == generation, self.mediaEpoch == mediaGeneration, self.call?.id == id else { return }
                    self.micOpening = false
                    if revision != self.micRevision { self.media(); return }
                    await self.finish(event: "failed", message: "麦克风错误（\(code)）：\(message)")
                }
            })
        }
        let target = localView ?? preview
        if current.video, cameraEnabled, active, cameraView !== target, !cameraOpening {
            cameraOpening = true
            let revision = cameraRevision
            engine.openCamera(in: target, front: front, success: { [weak self, weak target] in
                Task { @MainActor in
                    guard let self, self.epoch == generation, self.mediaEpoch == mediaGeneration, self.call?.id == id else { return }
                    self.cameraOpening = false
                    if revision != self.cameraRevision {
                        engine.closeCamera(); self.cameraView = nil; self.media(); return
                    }
                    if self.cameraEnabled, self.active { self.cameraView = target; self.media() }
                    else { engine.closeCamera(); self.cameraView = nil }
                }
            }, failure: { [weak self] code, message in
                Task { @MainActor in
                    guard let self, self.epoch == generation, self.mediaEpoch == mediaGeneration, self.call?.id == id else { return }
                    self.cameraOpening = false
                    if revision != self.cameraRevision { self.media(); return }
                    self.cameraEnabled = false
                    self.toast("摄像头错误（\(code)）：\(message)")
                }
            })
        }
        if current.video, current.connected, let view = remoteView, renderedRemoteView !== view {
            engine.showRemote(userID: current.peer, in: view); renderedRemoteView = view
        }
    }
    func toast(_ text: String) {
        noticeTask?.cancel(); notice = text
        noticeTask = Task { [weak self] in do { try await Task.sleep(for: .seconds(2)); self?.notice = nil } catch {} }
    }
    private static func operation(seconds: Double = 20,
        _ start: (@escaping () -> Void, @escaping (Int32, String) -> Void) -> Void) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let completion = CallOperationCompletion(continuation, seconds: seconds)
            start({ Task { @MainActor in completion.resolve(.success(Void())) } },
                  { code, message in Task { @MainActor in completion.resolve(.failure(APIError.server(Int(code), "通话错误（\(code)）：\(message)"))) } })
        }
    }
}

/// Callback and timeout may race. Resume each SDK continuation exactly once.
@MainActor private final class CallOperationCompletion {
    private var continuation: CheckedContinuation<Void, Error>?
    private var timeout: Task<Void, Never>?
    init(_ continuation: CheckedContinuation<Void, Error>, seconds: Double) {
        self.continuation = continuation
        timeout = Task {
            do { try await Task.sleep(for: .seconds(seconds)); resolve(.failure(APIError.server(408, "通话服务响应超时，请重试"))) } catch {}
        }
    }
    func resolve(_ result: Result<Void, Error>) {
        guard let continuation else { return }
        self.continuation = nil; timeout?.cancel(); timeout = nil
        continuation.resume(with: result)
    }
}
