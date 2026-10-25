import Foundation
import Combine
import Network

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
    var retryable: Bool?
    var retryAttempts: Int?
    let createdAt: Date
}

protocol OutboxTransport: Sendable {
    func upload(file: URL, userID: Int, uploadID: String, progress: @escaping @Sendable (Double) -> Void) async throws -> JSON
    func send(scope: String, body: [String: JSON]) async throws -> JSON
}
struct LiveOutboxTransport: OutboxTransport {
    func upload(file: URL, userID: Int, uploadID: String, progress: @escaping @Sendable (Double) -> Void) async throws -> JSON {
        try await MediaUploader.shared.upload(file: file, userID: userID, uploadID: uploadID, progress: progress)
    }
    func send(scope: String, body: [String: JSON]) async throws -> JSON {
        try await APIClient.shared.post(scope == "group" ? "groups.messages.send" : "messages.send", body)
    }
}

/// Filter byte callbacks before scheduling UI work. All mutable state is lock protected.
final class UploadProgressGate: @unchecked Sendable {
    private let lock = NSLock()
    private var active = true
    private var lastValue: Double = -1
    private var lastUpdate: TimeInterval = -.infinity

    func accept(_ value: Double, now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard active, value.isFinite else { return false }
        let value = min(1, max(0, value))
        guard value > lastValue else { return false }
        guard lastValue < 0 || value == 1 || (value - lastValue >= 0.01 && now - lastUpdate >= 0.1) else { return false }
        lastValue = value; lastUpdate = now
        return true
    }
    var isActive: Bool { lock.lock(); defer { lock.unlock() }; return active }
    func finish() { lock.lock(); defer { lock.unlock() }; active = false }
}

@MainActor final class Outbox: ObservableObject {
    static let shared = Outbox()
    @Published private(set) var items: [PendingMessage] = []
    @Published private(set) var acknowledgements: [String: Record] = [:]
    private var userID = 0
    private var epoch = UUID()
    private var textWorker: Task<Void, Never>?
    private var mediaWorker: Task<Void, Never>?
    private let network = NWPathMonitor()
    private let transport: any OutboxTransport
    private var durableIDs: Set<String> = []

    init(transport: any OutboxTransport = LiveOutboxTransport(), observeNetwork: Bool = true) {
        self.transport = transport
        guard observeNetwork else { return }
        network.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in self?.resume() }
        }
        network.start(queue: DispatchQueue(label: "spx.outbox.network"))
    }
    deinit { network.cancel(); textWorker?.cancel(); mediaWorker?.cancel() }
    /// Requeue only transient failures; validation and permission errors remain manual.
    func resume() {
        guard userID > 0 else { return }
        var changed = false
        for index in items.indices where items[index].state == .failed && items[index].retryable == true && (items[index].retryAttempts ?? 0) < 3 {
            items[index].state = .queued; items[index].error = nil
            items[index].retryAttempts = (items[index].retryAttempts ?? 0) + 1
            changed = true
        }
        if changed { Task { await persist(); drain() } } else { drain() }
    }
    private func cancelWorkers() {
        textWorker?.cancel(); mediaWorker?.cancel(); textWorker = nil; mediaWorker = nil
    }

    func start(userID: Int) async {
        guard userID > 0 else { return }
        if self.userID != userID {
            cancelWorkers(); epoch = UUID(); self.userID = userID
            let activeEpoch = epoch
            let restored: [PendingMessage] = await LocalStore.shared.read("outbox", userID: userID) ?? []
            guard activeEpoch == epoch else { return }
            items = restored.map { entry in
                var value = entry
                if let source = value.localURL { value.localURL = (try? MediaFiles.restoredAttachment(source, userID: userID)) ?? source }
                return value
            }
            durableIDs = Set(restored.map(\.id))
            for index in items.indices where items[index].state != .failed {
                items[index].state = .queued; items[index].progress = 0
            }
            acknowledgements = [:]
        }
        drain()
    }

    func stop(userID: Int) async {
        guard self.userID == userID else { return }
        cancelWorkers(); epoch = UUID()
        BackgroundMediaTransfer.shared.cancel(userID: userID)
        let snapshot = items
        self.userID = 0; items = []; acknowledgements = [:]; durableIDs = []
        await LocalStore.shared.write(snapshot, key: "outbox", userID: userID)
    }

    func enqueue(scope: String, id: Int, body: [String: JSON], localURL: URL? = nil, expectedUserID: Int) async throws {
        guard userID > 0, id > 0 else { throw APIError.unauthorized }
        guard expectedUserID == userID else { throw APIError.staleSession }
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
        items[index].state = .queued; items[index].error = nil; items[index].retryAttempts = 0
        Task { await persist(); drain() }
    }

    func discard(_ id: String) async {
        guard let item = items.first(where: { $0.id == id }), item.state == .failed else { return }
        items.removeAll { $0.id == id }
        durableIDs.remove(id)
        BackgroundMediaTransfer.shared.discard(userID: item.userID, uploadID: item.uploadID)
        if let url = item.localURL { try? FileManager.default.removeItem(at: url) }
        await persist()
    }

    private func persist() async { guard userID > 0 else { return }; await LocalStore.shared.write(items, key: "outbox", userID: userID) }

    private func drain() {
        guard userID > 0 else { return }
        if textWorker == nil { textWorker = makeWorker(media: false) }
        if mediaWorker == nil { mediaWorker = makeWorker(media: true) }
    }
    /// One media transfer and one independent text delivery lane keep upload memory bounded.
    private func makeWorker(media: Bool) -> Task<Void, Never>? {
        guard items.contains(where: { $0.state == .queued && ($0.localURL != nil) == media && durableIDs.contains($0.id) }) else { return nil }
        let activeEpoch = epoch
        return Task {
            defer {
                if activeEpoch == epoch {
                    if media { mediaWorker = nil } else { textWorker = nil }
                    drain()
                }
            }
            while !Task.isCancelled, activeEpoch == epoch,
                  let item = items.first(where: { $0.state == .queued && ($0.localURL != nil) == media && durableIDs.contains($0.id) }) {
                await deliver(item, activeEpoch: activeEpoch)
            }
        }
    }
    private func deliver(_ item: PendingMessage, activeEpoch: UUID) async {
        do {
            var payload = item.body
            if let url = item.localURL, payload["media_url"]?.string.isEmpty != false {
                update(item.id, state: .uploading)
                let progressGate = UploadProgressGate()
                defer { progressGate.finish() }
                let result = try await transport.upload(file: url, userID: item.userID, uploadID: item.uploadID) { progress in
                    guard progressGate.accept(progress) else { return }
                    Task { @MainActor [weak self] in
                        guard let self, progressGate.isActive, activeEpoch == self.epoch,
                              let index = self.items.firstIndex(where: { $0.id == item.id }),
                              self.items[index].state == .uploading else { return }
                        self.items[index].progress = max(self.items[index].progress, min(1, max(0, progress)))
                    }
                }
                progressGate.finish()
                guard activeEpoch == epoch else { return }
                // Upload responses have used both `url` and `media_url` over the
                // lifetime of the API. Keep the locally selected type when the
                // server omits it so an image/video cannot turn into a blank file.
                let remoteURL = UploadedMedia.url(in: result)
                guard !remoteURL.isEmpty else { throw APIError.server(502, "附件上传未返回地址，请重试") }
                payload["media_url"] = .string(remoteURL)
                let uploadedType = UploadedMedia.type(in: result)
                if !uploadedType.isEmpty, payload["message_type"]?.string != "file" { payload["message_type"] = .string(uploadedType) }
                if let index = items.firstIndex(where: { $0.id == item.id }) { items[index].body = payload }
                await persist()
                guard activeEpoch == epoch else { return }
                try Task.checkCancellation()
                // Seed before publishing/sending: a realtime echo can arrive before the
                // send receipt, and its preview should reuse the file already on this device.
                try? await MediaCache.shared.seed(url, source: remoteURL, userID: item.userID)
                guard activeEpoch == epoch else { return }
                try Task.checkCancellation()
            }
            update(item.id, state: .sending)
            let result = try await transport.send(scope: item.scope, body: payload)
            guard activeEpoch == epoch else { return }
            var message = result["message"].object.isEmpty ? result.record : result["message"].record
            // Send endpoints may return only an ID. Preserve the local content and media URL.
            for (key, value) in payload where message[key] == .null { message[key] = value }
            if message["media_url"].string.isEmpty, let mediaURL = payload["media_url"], !mediaURL.string.isEmpty {
                message["media_url"] = mediaURL
            }
            if message["message_type"].string.isEmpty, let messageType = payload["message_type"], !messageType.string.isEmpty {
                message["message_type"] = messageType
            }
            // Complete compact receipts for immediate local rendering.
            message[item.scope == "group" ? "group_id" : "chat_id"] = .number(Decimal(item.targetID))
            message["sender_id"] = .number(Decimal(item.userID))
            message["client_message_id"] = .string(item.id)
            message["local_created_at"] = .string(ISO8601DateFormatter().string(from: item.createdAt))
            guard message.id > 0 else { throw APIError.invalidResponse }
            acknowledgements[item.id] = message
            if acknowledgements.count > 200 { acknowledgements = [item.id: message] }
            if let url = item.localURL, !message["media_url"].string.isEmpty { try? await MediaCache.shared.seed(url, source: message["media_url"].string, userID: item.userID) }
            guard activeEpoch == epoch else { return }
            items.removeAll { $0.id == item.id }; durableIDs.remove(item.id); await persist()
            guard activeEpoch == epoch else { return }
            if let url = item.localURL { try? FileManager.default.removeItem(at: url) }
        } catch is CancellationError {
            if activeEpoch == epoch { update(item.id, state: .queued); await persist() }
        } catch {
            guard activeEpoch == epoch else { return }
            update(item.id, state: .failed, error: error.localizedDescription)
            if let index = items.firstIndex(where: { $0.id == item.id }) { items[index].retryable = (error as? APIError)?.retryable == true || error is URLError }
            await persist()
        }
    }

    private func update(_ id: String, state: PendingMessage.State, error: String? = nil) {
        if let index = items.firstIndex(where: { $0.id == id }) {
            items[index].state = state; items[index].error = error
            if state == .uploading { items[index].progress = 0 }
            if state == .sending, items[index].localURL != nil { items[index].progress = 1 }
        }
    }
}
