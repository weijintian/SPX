import SwiftUI
import UIKit

@MainActor final class SessionStore: ObservableObject {
    @Published private(set) var token:String?
    @Published var user:Record?
    @Published var loading = false
    @Published var restoring = true
    @Published var error:String?
    @Published private(set) var recoveryError:String?
    @Published private(set) var recoveringSession = false
    var isAuthenticated:Bool {token != nil && (user?.id ?? 0) > 0}
    private var presence:Task<Void,Never>?
    private var observer:NSObjectProtocol?
    private var active = true
    private var lifecycle = UUID()
    private var presenceEpoch = UUID()
    private var teardown: Task<Void, Never>?
    private var recovery: Task<Void, Never>?
    private var recoveryID = UUID()
    private var needsRecovery = false
    init() {
        token = Keychain.read("token")
        observer = NotificationCenter.default.addObserver(forName:.spxUnauthorized,object:nil,queue:.main) { [weak self] notification in
            let rejectedToken = notification.object as? String
            let message = notification.userInfo?["message"] as? String
            Task { @MainActor in
                if let self, let rejectedToken, self.token == rejectedToken {
                    self.expire()
                    self.error = message ?? "登录已失效，请重新登录"
                }
            }
        }
        Task {await restore()}
    }
    deinit {recovery?.cancel();presence?.cancel();if let observer {NotificationCenter.default.removeObserver(observer)}}
    func restore() async {
        guard !recoveringSession else { return }
        guard let savedToken = token else { restoring = false; return }
        recoveringSession = true
        defer { restoring = false; recoveringSession = false }
        let epoch = lifecycle
        await APIClient.shared.setToken(savedToken)
        guard epoch == lifecycle else { return }
        let lastID = Int(Keychain.read("lastUserID") ?? "0") ?? 0
        if user == nil, lastID > 0,
           let cached: Record = await LocalStore.shared.read("profile", userID: lastID),
           epoch == lifecycle, cached.id == lastID { user = cached }
        guard epoch == lifecycle else { return }
        do {
            let profile = try await APIClient.shared.get("me").record
            guard epoch == lifecycle else { return }
            guard profile.id > 0, lastID == 0 || profile.id == lastID else { throw APIError.invalidResponse }
            try Keychain.write(String(profile.id), account: "lastUserID")
            await Outbox.shared.start(userID: profile.id)
            guard epoch == lifecycle else { return }
            user = profile
            await persistUser()
            guard epoch == lifecycle else { return }
            await startServices()
            guard epoch == lifecycle else { return }
            needsRecovery = false; recoveryError = nil
        } catch APIError.unauthorized {
            if epoch == lifecycle { expire(); error = "登录已失效，请重新登录" }
        } catch {
            guard epoch == lifecycle else { return }
            needsRecovery = true
            recoveryError = AuthenticationErrorMessage.describe(error)
            // Cached pages remain usable. A missing profile never opens pages with user ID 0.
            if let user, user.id > 0 { await startServices() }
            guard epoch == lifecycle else { return }
            scheduleRecovery()
        }
    }
    private func scheduleRecovery(immediate: Bool = false) {
        guard active, token != nil, needsRecovery, recovery == nil else { return }
        let id = UUID(); recoveryID = id
        let epoch = lifecycle
        recovery = Task { [weak self] in
            defer { if let self, self.recoveryID == id { self.recovery = nil } }
            var delay = !immediate
            while !Task.isCancelled {
                if delay { do { try await SPXSleep.seconds(15) } catch { return } }
                guard let self, self.lifecycle == epoch, self.active, self.needsRecovery else { return }
                await self.restore()
                delay = true
            }
        }
    }
    func authenticate(register:Bool,username:String,password:String,email:String = "",code:String = "",invite:String = "") async {
        guard !loading else {return};loading = true;error = nil;defer {loading = false}
        await teardown?.value
        lifecycle = UUID()
        let epoch = lifecycle
        var body:[String:JSON] = ["username":.string(username),"password":.string(password),"device_id":.string(Keychain.deviceID),"device_model":.string(UIDevice.current.model),"app_version":.string(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")]
        if register {body["email"] = .string(email);body["code"] = .string(code);body["invite_code"] = .string(invite)}
        do {
            let result = try await APIClient.shared.post(register ? "auth.register":"auth.login",body)
            guard epoch == lifecycle else { return }
            let account = try AuthenticatedAccount(result)
            try Keychain.saveSession(token: account.token, userID: account.user.id)
            await APIClient.shared.setToken(account.token)
            guard epoch == lifecycle else { return }
            await Outbox.shared.start(userID: account.user.id)
            guard epoch == lifecycle else { return }
            user = account.user; token = account.token
            recoveryError = nil; needsRecovery = false
            await persistUser(); await startServices()
        } catch {if epoch == lifecycle {self.error = AuthenticationErrorMessage.describe(error)}}
    }
    func refreshProfile() async throws {
        let epoch = lifecycle
        let owner = user?.id
        let profile = try await APIClient.shared.get("me").record
        guard epoch == lifecycle else { throw APIError.staleSession }
        guard profile.id > 0, owner == nil || owner == profile.id else { throw APIError.invalidResponse }
        user = profile
        await persistUser()
    }
    func replaceToken(_ value: String) async throws {
        guard !value.isEmpty else { throw APIError.invalidResponse }
        let epoch = lifecycle
        try Keychain.write(value, account: "token")
        await APIClient.shared.setToken(value)
        guard epoch == lifecycle else { throw APIError.staleSession }
        token = value
        await startServices()
    }
    private func persistUser() async {
        if let user, user.id > 0 {try? Keychain.write(String(user.id),account:"lastUserID");await LocalStore.shared.write(user,key:"profile",userID:user.id)}
    }
    private func startServices() async {
        guard let user, user.id > 0 else {return}
        let epoch = lifecycle
        await Outbox.shared.start(userID:user.id)
        guard epoch == lifecycle else { return }
        try? await MediaCache.shared.trim(userID: user.id)
        guard epoch == lifecycle else { return }
        CallCoordinator.shared.start(userID: user.id)
        CallCoordinator.shared.setActive(active)
        MessageNotifications.shared.start(userID: user.id, sessionToken: token ?? "")
        MessageNotifications.shared.setActive(active)
        if active {
            if let token { RealtimeClient.shared.start(token: token) }
            startPresence()
        }
    }
    func setActive(_ value:Bool) {
        active = value
        MessageNotifications.shared.setActive(value)
        presenceEpoch = UUID()
        let presenceEpoch = self.presenceEpoch
        if value, token != nil, let user, user.id > 0, !restoring {
            CallCoordinator.shared.start(userID: user.id)
            Outbox.shared.resume()
        }
        CallCoordinator.shared.setActive(value)
        if value, let token, let user, user.id > 0, !restoring { RealtimeClient.shared.start(token: token) }
        else { RealtimeClient.shared.stop() }
        if value, token != nil, let user, user.id > 0, !restoring {startPresence()}
        else {presence?.cancel();presence = nil; VoicePlayback.shared.stop(); if token != nil { Task { guard presenceEpoch == self.presenceEpoch, !self.active else { return }; try? await APIClient.shared.post("presence.update", ["device_id": .string(Keychain.deviceID), "online": false]) } }}
        if value { scheduleRecovery(immediate: true) }
        else { recoveryID = UUID(); recovery?.cancel(); recovery = nil }
    }
    func logout() async {
        guard !loading else { return }; loading = true; defer { loading = false }
        let credential = token
        expire()
        // Local logout is immediate, even when the server cannot be reached.
        if let credential { await APIClient.shared.revokeSession(credential) }
    }
    func expire() {
        lifecycle = UUID()
        recoveryID = UUID(); recovery?.cancel(); recovery = nil
        needsRecovery = false; recoveryError = nil
        let previous = user?.id ?? 0;presence?.cancel();presence = nil
        CallCoordinator.shared.stop()
        VoicePlayback.shared.stop()
        RealtimeClient.shared.stop()
        MessageNotifications.shared.stop()
        ConversationStore.releaseCached()
        token = nil;user = nil;try? Keychain.write(nil,account:"token")
        teardown = Task {await APIClient.shared.setToken(nil);await Outbox.shared.stop(userID:previous)}
    }
    func startPresence() {
        presenceEpoch = UUID()
        let presenceEpoch = self.presenceEpoch
        presence?.cancel()
        presence = Task {
            while !Task.isCancelled, presenceEpoch == self.presenceEpoch, self.active, self.token != nil {
                do {try await APIClient.shared.post("presence.update",["device_id":.string(Keychain.deviceID), "online":true]);try await SPXSleep.seconds(25)}
                catch is CancellationError {return}
                catch {try? await SPXSleep.seconds(10)}
            }
        }
    }
}


/// A successful response must carry both the credential and its account identity.
struct AuthenticatedAccount {
    let token: String
    let user: Record
    init(_ result: JSON) throws {
        let token = result["token"].string
        let user = result["user"].record
        guard !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, user.id > 0 else { throw APIError.invalidResponse }
        self.token = token; self.user = user
    }
}
