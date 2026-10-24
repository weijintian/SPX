import Foundation
import Combine

extension Notification.Name { static let spxRealtime = Notification.Name("spx.realtime") }

/// REST remains authoritative. Pushes accelerate updates; a reconnect triggers reconciliation.
@MainActor final class RealtimeClient: ObservableObject {
    static let shared = RealtimeClient()
    @Published private(set) var connected = false
    private var runner: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var socket: URLSessionWebSocketTask?
    private var epoch = UUID()
    private var currentToken: String?
    func start(token: String) {
        if currentToken == token, runner != nil { return }
        stop(); currentToken = token
        let generation = epoch
        runner = Task {
            var attempt = 0
            while !Task.isCancelled, generation == epoch {
                do {
                    let endpoint = try await APIClient.shared.endpoint()
                    guard generation == epoch, !Task.isCancelled else { return }
                    guard var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false) else { throw APIError.invalidURL }
                    components.scheme = "wss"; components.path = "/ws"; components.query = nil
                    guard let url = components.url else { throw APIError.invalidURL }
                    let connection = NetworkSession.shared.webSocketTask(with: url); socket = connection; connection.resume()
                    let auth = try JSONEncoder().encode(["type": "auth", "token": token])
                    try await connection.send(.string(String(decoding: auth, as: UTF8.self)))
                    guard generation == epoch, !Task.isCancelled else { connection.cancel(with: .goingAway, reason: nil); return }
                    heartbeat = Task {
                        while !Task.isCancelled {
                            do { try await Task.sleep(for: .seconds(20)); try await ping(connection) } catch { connection.cancel(with: .goingAway, reason: nil); return }
                        }
                    }
                    while !Task.isCancelled, generation == epoch {
                        let event = try await connection.receive()
                        let bytes: Data
                        switch event { case .data(let value): bytes = value; case .string(let value): bytes = Data(value.utf8); @unknown default: continue }
                        let payload = try JSONDecoder().decode(JSON.self, from: bytes)
                        guard generation == epoch else { return }
                        if payload["type"].string == "auth.revoked" {
                            NotificationCenter.default.post(name: .spxUnauthorized, object: token, userInfo: ["message": payload["message"].string])
                            stop()
                            return
                        }
                        if payload["type"].string == "ready" { connected = true; attempt = 0 }
                        if payload["type"].string == "contact.remark.updated" {
                            let owner = Int(Keychain.read("lastUserID") ?? "0") ?? 0
                            if owner > 0, payload["user_id"].int == owner {
                                await LocalStore.shared.updateGroupContactRemark(peerID: payload["contact_user_id"].int, remark: payload["remark"].string, userID: owner)
                                guard generation == epoch, !Task.isCancelled else { return }
                            }
                        }
                        let recipientID = Int(Keychain.read("lastUserID") ?? "0") ?? 0
                        MessageNotifications.shared.incoming(payload, userID: recipientID)
                        ChatSoundEffects.shared.incoming(payload, userID: recipientID)
                        NotificationCenter.default.post(name: .spxRealtime, object: payload)
                    }
                } catch is CancellationError { return }
                catch { guard generation == epoch else { return }; connected = false }
                guard generation == epoch else { return }
                heartbeat?.cancel(); heartbeat = nil; socket?.cancel(with: .goingAway, reason: nil)
                attempt += 1
                do { try await Task.sleep(for: .seconds(min(30, pow(2, Double(min(attempt, 5)))) + Double.random(in: 0...1))) } catch { return }
            }
        }
    }
    private func ping(_ connection: URLSessionWebSocketTask) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.sendPing { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
    }
    func stop() { epoch = UUID(); runner?.cancel(); runner = nil; heartbeat?.cancel(); heartbeat = nil; socket?.cancel(with: .goingAway, reason: nil); socket = nil; connected = false; currentToken = nil }
}
