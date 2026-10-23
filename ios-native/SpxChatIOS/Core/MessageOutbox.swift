import Foundation
import Combine

struct PendingMessage: Codable, Identifiable {
    enum State: String, Codable { case queued, uploading, sending, failed }
    let id: String
    let userID: Int
    let scope: String
    let targetID: Int
    var body: [String: JSON]
    var localURL: URL?
    var uploadID: String
    var state: State = .queued
    var progress: Double = 0
    var error: String?
    let createdAt: Date
}

@MainActor final class Outbox: ObservableObject {
    static let shared = Outbox()
    @Published private(set) var items: [PendingMessage] = []
    @Published private(set) var acknowledgements: [String: Record] = [:]
    private var userID = 0
    private var epoch = UUID()
    private var worker: Task<Void, Never>?
    private var durableIDs: Set<String> = []

    func start(userID: Int) async {
        guard userID > 0 else { return }
        if self.userID != userID {
            worker?.cancel(); worker = nil; epoch = UUID(); self.userID = userID
            let activeEpoch = epoch
            let restored: [PendingMessage] = await LocalStore.shared.read("outbox", userID: userID) ?? []
            guard activeEpoch == epoch else { return }
            items = restored
            durableIDs = Set(restored.map(\.id))
            for index in items.indices where items[index].state != .failed { items[index].state = .queued }
            acknowledgements = [:]
        }
        drain()
    }

    func stop(userID: Int) async {
        guard self.userID == userID else { return }
        worker?.cancel(); worker = nil; epoch = UUID()
        BackgroundMediaTransfer.shared.cancel(userID: userID)
        let snapshot = items
        self.userID = 0; items = []; acknowledgements = [:]; durableIDs = []
        await LocalStore.shared.write(snapshot, key: "outbox", userID: userID)
    }

    func enqueue(scope: String, id: Int, body: [String: JSON], localURL: URL? = nil) async throws {
        guard userID > 0, id > 0 else { throw APIError.unauthorized }
        let clientID = UUID().uuidString.lowercased()
        var payload = body
        payload[scope == "group" ? "group_id" : "chat_id"] = .number(Decimal(id))
        payload["client_message_id"] = .string(clientID)
        let entry = PendingMessage(id: clientID, userID: userID, scope: scope, targetID: id,
                                    body: payload, localURL: localURL, uploadID: UUID().uuidString.lowercased(), createdAt: Date())
        let activeEpoch = epoch
        let owner = userID
        items.append(entry)
        do {
            try await LocalStore.shared.save(items, key: "outbox", userID: owner)
            guard activeEpoch == epoch else { throw APIError.staleSession }
            durableIDs.insert(entry.id)
            drain()
        } catch {
            if activeEpoch == epoch { items.removeAll { $0.id == entry.id } }
            throw error
        }
    }

    func retry(_ id: String) {
        guard let index = items.firstIndex(where: { $0.id == id }), items[index].state == .failed else { return }
        items[index].state = .queued; items[index].error = nil
        Task { await persist(); drain() }
    }

    func discard(_ id: String) async {
        guard let item = items.first(where: { $0.id == id }), item.state == .failed else { return }
        items.removeAll { $0.id == id }
        durableIDs.remove(id)
        if let url = item.localURL { try? FileManager.default.removeItem(at: url) }
        await persist()
    }

    private func persist() async { guard userID > 0 else { return }; await LocalStore.shared.write(items, key: "outbox", userID: userID) }

    private func drain() {
        guard worker == nil, userID > 0 else { return }
        let activeEpoch = epoch
        worker = Task {
            defer { if activeEpoch == epoch { worker = nil } }
            while !Task.isCancelled, activeEpoch == epoch,
                  let item = items.first(where: { $0.state == .queued && durableIDs.contains($0.id) }) {
                do {
                    var payload = item.body
                    if let url = item.localURL, payload["media_url"]?.string.isEmpty != false {
                        update(item.id, state: .uploading)
                        let result = try await MediaUploader.shared.upload(file: url, userID: item.userID, uploadID: item.uploadID) { progress in
                            Task { @MainActor in
                                guard activeEpoch == self.epoch, let i = self.items.firstIndex(where: { $0.id == item.id }) else { return }
                                self.items[i].progress = progress
                            }
                        }
                        guard activeEpoch == epoch else { return }
                        payload["media_url"] = result["url"]
                        payload["message_type"] = result["message_type"]
                        if let index = items.firstIndex(where: { $0.id == item.id }) { items[index].body = payload }
                        await persist()
                        guard activeEpoch == epoch, !Task.isCancelled else { return }
                    }
                    update(item.id, state: .sending)
                    let result = try await APIClient.shared.post(item.scope == "group" ? "groups.messages.send" : "messages.send", payload)
                    guard activeEpoch == epoch else { return }
                    let message = result["message"].object.isEmpty ? result.record : result["message"].record
                    acknowledgements[item.id] = message
                    if acknowledgements.count > 200 { acknowledgements = [item.id: message] }
                    if let url = item.localURL, !message["media_url"].string.isEmpty { try? await MediaCache.shared.seed(url, source: message["media_url"].string, userID: item.userID) }
                    guard activeEpoch == epoch else { return }
                    items.removeAll { $0.id == item.id }; durableIDs.remove(item.id); await persist()
                    if let url = item.localURL { try? FileManager.default.removeItem(at: url) }
                } catch is CancellationError { return }
                catch {
                    guard activeEpoch == epoch else { return }
                    update(item.id, state: .failed, error: error.localizedDescription); await persist()
                }
            }
        }
    }

    private func update(_ id: String, state: PendingMessage.State, error: String? = nil) {
        if let index = items.firstIndex(where: { $0.id == id }) { items[index].state = state; items[index].error = error }
    }
}
