import Foundation
import SwiftUI
import UIKit
import TUICore
import AtomicXCore
import TUICallKit_Swift

/// Compatibility model retained for the chat views. Tencent's TUICallKit owns
/// the actual call screen and complete invitation state.
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
}

/// Connects the authenticated SPX account to Tencent's official UI call kit.
/// The component owns invitations, incoming UI, accept/reject, ringing, media,
/// timeout and the floating call window, avoiding a second local call state machine.
@MainActor final class CallCoordinator: ObservableObject {
    static let shared = CallCoordinator()

    // Kept for source compatibility with existing chat views. TUICallKit shows
    // its own UI, so this remains nil.
    @Published private(set) var call: ActiveCall?
    @Published var minimized = false
    @Published private(set) var muted = false
    @Published private(set) var speaker = false
    @Published private(set) var cameraEnabled = true
    @Published private(set) var answering = false
    @Published var notice: String?

    private var userID = 0
    private var ready = false
    private var loginTask: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var active = true
    private var generation = UUID()

    func start(userID: Int) {
        guard userID > 0 else { return }
        if self.userID != userID {
            stop()
            self.userID = userID
        }
        connectIfNeeded()
    }

    func setActive(_ value: Bool) {
        active = value
        if value { connectIfNeeded() }
    }

    func stop(resetSounds: Bool = true) {
        generation = UUID()
        loginTask?.cancel()
        loginTask = nil
        if resetSounds { ChatSoundEffects.shared.reset() } else { ChatSoundEffects.shared.stopCall() }
        call = nil
        minimized = false
        muted = false
        speaker = false
        cameraEnabled = true
        answering = false
        ready = false
        userID = 0
    }

    private func connectIfNeeded() {
        guard active, userID > 0, !ready, loginTask == nil else { return }
        let expectedUser = userID
        let expectedGeneration = generation
        loginTask = Task { [weak self] in
            defer {
                if let self, self.generation == expectedGeneration {
                    self.loginTask = nil
                }
            }
            do {
                let credentials = try await APIClient.shared.get("calls.credentials")
                guard !Task.isCancelled, let self, self.generation == expectedGeneration, self.userID == expectedUser else { return }
                let sdkAppID = Int32(credentials["sdk_app_id"].int)
                let identity = credentials["user_id"].string
                let userSig = credentials["user_sig"].string
                guard sdkAppID > 0, identity == "spx_\(expectedUser)", !userSig.isEmpty else { throw APIError.invalidResponse }
                try await self.tencentLogin(sdkAppID: sdkAppID, userID: identity, userSig: userSig)
                guard !Task.isCancelled, self.generation == expectedGeneration, self.userID == expectedUser else { return }

                let kit = TUICallKit.createInstance()
                kit.enableFloatWindow(enable: true)
                kit.enableIncomingBanner(enable: false)
                kit.setSelfInfo(nickname: credentials["nickname"].string, avatar: credentials["avatar_url"].string, completion: nil)
                self.ready = true
            } catch is CancellationError {
            } catch {
                guard let self, self.generation == expectedGeneration, self.userID == expectedUser else { return }
                self.ready = false
                self.toast("通话服务连接失败：\(error.localizedDescription)")
            }
        }
    }

    private func tencentLogin(sdkAppID: Int32, userID: String, userSig: String) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            TUILogin.login(sdkAppID, userID: userID, userSig: userSig) {
                continuation.resume()
            } fail: { code, message in
                continuation.resume(throwing: APIError.server(Int(code), message ?? "腾讯云通话登录失败"))
            }
        }
    }

    /// This method is called only by explicit chat/profile button taps. Tencent's
    /// official component then owns caller/callee UI and the whole call lifecycle.
    func dial(chat: Record, video: Bool) {
        guard chat.id > 0 else { toast("会话不可用，请重新打开好友资料"); return }
        guard userID > 0 else { toast("请先登录后再发起通话"); return }
        let peer = "spx_\(chat["peer_user_id"].int)"
        guard peer != "spx_0", peer != "spx_\(userID)" else { toast("未找到有效的通话对象"); return }

        Task { [weak self] in
            guard let self else { return }
            if !self.ready {
                self.connectIfNeeded()
                for _ in 0..<40 where !self.ready {
                    try? await Task.sleep(for: .milliseconds(125))
                    if Task.isCancelled { return }
                }
            }
            guard self.ready else { self.toast("通话服务正在连接，请稍后重试"); return }
            TUICallKit.createInstance().calls(
                userIdList: [peer],
                mediaType: video ? .video : .audio,
                params: nil,
                completion: { [weak self] result in
                    if case .failure(let error) = result {
                        Task { @MainActor in self?.toast("发起通话失败：\(error.localizedDescription)") }
                    }
                }
            )
        }
    }

    // Compatibility APIs for existing chat and media views. Actual controls live
    // in the official TUICallKit call page.
    func answer() {}
    func end() {}
    func toggleMute() { muted.toggle() }
    func toggleSpeaker() { speaker.toggle() }
    func switchCamera() {}
    func toggleCamera() { cameraEnabled.toggle() }
    func attachVideo(_ view: UIView, local: Bool) {}

    func toast(_ text: String) {
        noticeTask?.cancel()
        notice = text
        noticeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.notice = nil
        }
    }
}
