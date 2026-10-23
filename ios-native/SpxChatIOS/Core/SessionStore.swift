import SwiftUI
import UIKit

@MainActor final class SessionStore: ObservableObject {
    @Published private(set) var token:String?
    @Published var user:Record?
    @Published var loading = false
    @Published var restoring = true
    @Published var error:String?
    var isAuthenticated:Bool {token != nil}
    private var presence:Task<Void,Never>?
    private var observer:NSObjectProtocol?
    private var active = true
    private var lifecycle = UUID()
    private var presenceEpoch = UUID()
    private var teardown: Task<Void, Never>?
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
    deinit {presence?.cancel();if let observer {NotificationCenter.default.removeObserver(observer)}}
    func restore() async {
        defer {restoring = false}
        guard let token else {return}
        let epoch = lifecycle
        await APIClient.shared.setToken(token)
        let lastID = Int(Keychain.read("lastUserID") ?? "0") ?? 0
        if lastID > 0, let cached:Record = await LocalStore.shared.read("profile",userID:lastID), epoch == lifecycle {user = cached}
        guard epoch == lifecycle else { return }
        do {let profile = try await APIClient.shared.get("me").record; guard epoch == lifecycle else { return }; user = profile; await persistUser();await startServices()}
        catch APIError.unauthorized { if epoch == lifecycle { expire() } }
        catch { if epoch == lifecycle { self.error = error.localizedDescription } }
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
            let value = result["token"].string;guard !value.isEmpty else {throw APIError.invalidResponse}
            try Keychain.write(value,account:"token");token = value;user = result["user"].record
            await APIClient.shared.setToken(value);await persistUser();await startServices()
        } catch {if epoch == lifecycle {self.error = error.localizedDescription}}
    }
    func refreshProfile() async throws {let epoch = lifecycle; let profile = try await APIClient.shared.get("me").record; guard epoch == lifecycle else { throw APIError.staleSession }; user = profile; await persistUser()}
    func replaceToken(_ value: String) async throws {
        guard !value.isEmpty else { throw APIError.invalidResponse }
        try Keychain.write(value, account: "token")
        token = value
        await APIClient.shared.setToken(value)
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
        if let token { RealtimeClient.shared.start(token: token) }
        if active {startPresence()}
    }
    func setActive(_ value:Bool) {
        active = value
        presenceEpoch = UUID()
        let presenceEpoch = self.presenceEpoch
        CallCoordinator.shared.setActive(value)
        if value, let token { RealtimeClient.shared.start(token: token) }
        else { RealtimeClient.shared.stop() }
        if value, token != nil {startPresence()}
        else {presence?.cancel();presence = nil; VoicePlayback.shared.stop(); if token != nil { Task { guard presenceEpoch == self.presenceEpoch, !self.active else { return }; try? await APIClient.shared.post("presence.update", ["device_id": .string(Keychain.deviceID), "online": false]) } }}
    }
    func logout() async {
        guard !loading else { return }; loading = true; defer { loading = false }
        do {try await APIClient.shared.post("auth.logout")} catch { }
        expire()
    }
    func expire() {
        lifecycle = UUID()
        let previous = user?.id ?? 0;presence?.cancel();presence = nil
        CallCoordinator.shared.stop()
        VoicePlayback.shared.stop()
        RealtimeClient.shared.stop()
        token = nil;user = nil;try? Keychain.write(nil,account:"token")
        teardown = Task {await APIClient.shared.setToken(nil);await Outbox.shared.stop(userID:previous)}
    }
    func startPresence() {
        presenceEpoch = UUID()
        let presenceEpoch = self.presenceEpoch
        presence?.cancel()
        presence = Task {
            while !Task.isCancelled, presenceEpoch == self.presenceEpoch, self.active, self.token != nil {
                do {try await APIClient.shared.post("presence.update",["device_id":.string(Keychain.deviceID), "online":true]);try await Task.sleep(for:.seconds(25))}
                catch is CancellationError {return}
                catch {try? await Task.sleep(for:.seconds(10))}
            }
        }
    }
}

