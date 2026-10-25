import Foundation
import CryptoKit

enum APIReadContext {
    @TaskLocal static var forceRefresh = false
}

/// Only display reads are cached. Authentication, quotes, recovery and message cursors stay live.
enum APIReadPolicy {
    static func lifetime(_ action: String) -> TimeInterval? {
        switch action {
        case "chats", "groups": return 20
        case "wallet", "staking", "staking.plans", "mall.valuation", "orders", "order", "merchant.orders", "merchant.me": return 30
        case "products", "product", "product.reviews", "product.favorite.status", "shops", "shop", "favorites",
             "merchant.products", "merchant.groups", "merchant.drafts", "merchant.draft",
             "wallet.transactions", "wallet.address", "wallet.binding", "wallet.deposit.info",
             "redpackets.mine", "support.tickets", "referrals": return 120
        case "contacts", "contacts.search", "user.profile", "groups.info", "groups.members", "groups.settings",
             "groups.search", "groups.mentions", "chats.settings.get", "chat.rules.list", "chat.schedule.list": return 300
        case "mall.config", "faq", "questions": return 1800
        default: return nil
        }
    }
    static let financial: Set<String> = ["wallet", "wallet.transactions", "wallet.binding", "wallet.address", "wallet.deposit.info", "staking", "staking.plans", "redpackets.mine", "merchant.me", "orders", "order", "merchant.orders", "products", "product", "mall.valuation"]
    static let conversations: Set<String> = ["chats", "groups", "groups.info", "groups.members", "groups.settings", "groups.search", "groups.mentions", "chats.settings.get", "chat.rules.list", "chat.schedule.list"]
    static let contacts: Set<String> = ["contacts", "contacts.search", "user.profile", "chats", "groups", "groups.members", "groups.mentions"]
    static let commerce: Set<String> = ["products", "product", "product.reviews", "product.favorite.status", "shops", "shop", "favorites", "merchant.products", "merchant.groups", "merchant.drafts", "merchant.draft", "merchant.me", "mall.config", "orders", "order", "merchant.orders"]
    static func invalidatedActions(by action: String) -> Set<String> {
        var result: Set<String> = []
        if AccountMutation.affectsWallet(action) || AccountMutation.affectsOrders(action) { result.formUnion(financial) }
        if action.hasPrefix("contacts.") || action.hasPrefix("profile.") || action.hasPrefix("account.") { result.formUnion(contacts) }
        if action.hasPrefix("messages.") || action.hasPrefix("groups.messages.") {
            // Read receipts change list badges, but never evict unrelated pages.
            result.formUnion(["chats", "groups"])
        } else if action.hasPrefix("groups.") || action.hasPrefix("chats.") || action.hasPrefix("chat.") { result.formUnion(conversations) }
        if action.hasPrefix("merchant.") || action.hasPrefix("product.") || action.hasPrefix("orders.") || action.hasPrefix("checkout.") || action.hasPrefix("favorites.") { result.formUnion(commerce) }
        if action.hasPrefix("support.") { result.insert("support.tickets") }
        if action.hasPrefix("questions.") { result.insert("questions") }
        return result
    }
    static func realtimeActions(_ type: String) -> Set<String>? {
        if type == "ready" { return nil } // Reconnect may have missed any domain's event.
        if type.hasPrefix("message") || type.hasPrefix("group.message") || type.hasPrefix("chat.") { return ["chats", "groups"] }
        if type.hasPrefix("contact.") { return contacts }
        if type.hasPrefix("wallet.") || type.hasPrefix("staking.") || type.hasPrefix("order.") || type.hasPrefix("orders.") || type.hasPrefix("redpacket.") { return financial.union(commerce) }
        if type.hasPrefix("product.") || type.hasPrefix("merchant.") || type.hasPrefix("shop.") { return commerce }
        if type.hasPrefix("group.") || type.hasPrefix("groups.") { return conversations }
        return []
    }
}

actor APIReadCache {
    static let shared = APIReadCache()
    private struct Entry: Codable {
        let requestID: UUID
        let action: String
        let value: JSON
        let savedAt: Date
    }
    private struct Pending {
        let id: UUID
        let action: String
        let forced: Bool
        let task: Task<JSON, Error>
    }
    private let directory: URL
    private var entries: [String: [String: Entry]] = [:]
    private var pending: [String: Pending] = [:]
    private let maximumEntries = 256
    private let maximumBytes = 8 * 1024 * 1024

    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SPX/APIReads-v1", isDirectory: true)
    }
    nonisolated static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    nonisolated static func scope(endpoint: String, credential: String?, language: String) -> String {
        let data = (try? JSONEncoder().encode([endpoint, credential ?? "", language])) ?? Data()
        return digest(String(decoding: data, as: UTF8.self))
    }
    func value(scope: String, key: String, action: String, lifetime: TimeInterval,
               force: Bool = false, now: Date = Date(),
               loader: @escaping @Sendable () async throws -> JSON) async throws -> JSON {
        try Task.checkCancellation()
        load(scope)
        if !force, let entry = entries[scope]?[key], entry.action == action {
            let age = now.timeIntervalSince(entry.savedAt)
            if age >= 0, age < lifetime { return entry.value }
        }
        let pendingKey = scope + ":" + key
        if force, let existing = pending[pendingKey], !existing.forced {
            existing.task.cancel(); pending[pendingKey] = nil
        }
        let work: Pending
        if let existing = pending[pendingKey] { work = existing }
        else {
            // An expired entry is not silently used to authorize a financial operation.
            entries[scope]?[key] = nil
            work = Pending(id: UUID(), action: action, forced: force, task: Task { try await loader() })
            pending[pendingKey] = work
        }
        do {
            let result = try await work.task.value
            guard pending[pendingKey]?.id == work.id else {
                // A different waiter may already have saved this exact result.
                if entries[scope]?[key]?.requestID == work.id {
                    try Task.checkCancellation(); return result
                }
                throw CancellationError()
            }
            pending[pendingKey] = nil
            entries[scope, default: [:]][key] = Entry(requestID: work.id, action: action, value: result, savedAt: now)
            persist(scope)
            try Task.checkCancellation()
            return result
        } catch {
            if pending[pendingKey]?.id == work.id { pending[pendingKey] = nil }
            throw error
        }
    }
    func invalidate(scope: String, actions: Set<String>? = nil) {
        if actions?.isEmpty == true { return }
        load(scope)
        let previousCount = entries[scope]?.count ?? 0
        if let actions { entries[scope] = entries[scope]?.filter { !actions.contains($0.value.action) } }
        else { entries[scope] = [:] }
        let prefix = scope + ":"
        for (key, work) in pending where key.hasPrefix(prefix) && (actions == nil || actions!.contains(work.action)) {
            work.task.cancel(); pending[key] = nil
        }
        if previousCount != (entries[scope]?.count ?? 0) { persist(scope) }
    }
    func cancelPending() {
        for work in pending.values { work.task.cancel() }
        pending.removeAll()
    }
    private func load(_ scope: String) {
        guard entries[scope] == nil else { return }
        let url = directory.appendingPathComponent(scope + ".json")
        if let data = try? Data(contentsOf: url), data.count <= maximumBytes,
           let saved = try? JSONDecoder().decode([String: Entry].self, from: data) { entries[scope] = saved }
        else { entries[scope] = [:] }
        if entries.count > 8, let other = entries.keys.first(where: { $0 != scope }) { entries[other] = nil }
    }
    private func persist(_ scope: String) {
        guard let saved = entries[scope] else { return }
        let sorted = saved.sorted { $0.value.savedAt > $1.value.savedAt }
        var limited: [String: Entry] = [:]
        var size = 0
        for (key, entry) in sorted.prefix(maximumEntries) {
            guard let encoded = try? JSONEncoder().encode(entry), encoded.count <= maximumBytes else { continue }
            guard size + encoded.count < maximumBytes - 32768 else { continue }
            limited[key] = entry; size += encoded.count
        }
        entries[scope] = limited
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appendingPathComponent(scope + ".json")
            try JSONEncoder().encode(limited).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])
                .filter { $0.pathExtension == "json" }
                .sorted { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) > ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
            for old in files.dropFirst(8) where old != url { try? FileManager.default.removeItem(at: old) }
        } catch { /* A disk cache failure must never turn a successful read into an error. */ }
    }
}
