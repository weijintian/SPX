import Foundation
import CryptoKit
import SwiftUI
import UserNotifications
import UIKit

struct ChatNotificationRoute: Identifiable, Equatable {
    let userID: Int
    let targetID: Int
    let group: Bool
    var id: String { "\(userID)-\(group ? "g" : "c")-\(targetID)" }
    init?(payload: [AnyHashable: Any]) {
        let integer: (String) -> Int = { key in (payload[key] as? NSNumber)?.intValue ?? Int(payload[key] as? String ?? "") ?? 0 }
        userID = integer("recipient_user_id")
        let groupID = integer("group_id")
        group = groupID > 0; targetID = group ? groupID : integer("chat_id")
        guard userID > 0, targetID > 0 else { return nil }
    }
}

/// Shared by the badge and notification decisions. Counts are reconciled with REST.
struct NotificationUnreadLedger {
    private(set) var counts: [String: Int] = [:]
    private(set) var muted: Set<String> = []
    private var seen: Set<String> = []
    private var recent: [String] = []
    var total: Int { Int(min(Int64(Int32.max), counts.values.reduce(Int64(0)) { $0 + Int64(max(0, $1)) })) }
    static func key(_ id: Int, group: Bool) -> String { "\(group ? "g" : "c")-\(id)" }
    mutating func replace(chats: [Record], groups: [Record]) {
        var next: [String: Int] = [:], muted: Set<String> = []
        for (rows, group) in [(chats, false), (groups, true)] {
            for row in rows where row.id > 0 {
                let key = Self.key(row.id, group: group)
                next[key] = max(0, min(Int(Int32.max), row["unread_count"].int))
                if row["mute"].flag { muted.insert(key) }
            }
        }
        counts = next; self.muted = muted
    }
    mutating func receive(target: Int, group: Bool, messageID: Int, visible: Bool) -> Bool {
        guard target > 0, messageID > 0 else { return false }
        let key = Self.key(target, group: group), event = "\(key)-\(messageID)"
        guard seen.insert(event).inserted else { return false }
        recent.append(event)
        if recent.count > 1024 { seen.remove(recent.removeFirst()) }
        if !visible { counts[key] = min(Int(Int32.max), max(0, counts[key] ?? 0) + 1) }
        return true
    }
    mutating func read(target: Int, group: Bool, remaining: Int) { counts[Self.key(target, group: group)] = max(0, min(Int(Int32.max), remaining)) }
}

@MainActor final class MessageNotifications: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = MessageNotifications()
    @Published private(set) var unread = 0
    @Published private(set) var authorization: UNAuthorizationStatus = .notDetermined
    @Published private(set) var registrationError: String?
    @Published var pendingRoute: ChatNotificationRoute?
    private var owner = 0
    private var ownerSession = Data()
    private var ledger = NotificationUnreadLedger()
    private var names: [String: String] = [:]
    private var visibleConversation: String?
    private var runner: Task<Void, Never>?
    private var epoch = UUID()
    private var revision = 0
    private var active = true
    private var deviceToken: String?
    private var registeredToken: String?
    private var refreshing = false
    private var refreshAgain = false
    private var badgeRevision = 0
    private var desiredBadge = 0
    private var badgeTask: Task<Void, Never>?
    private let center = UNUserNotificationCenter.current()
    private override init() { super.init() }

    func configure() { center.delegate = self }
    func start(userID: Int, sessionToken: String) {
        guard userID > 0, !sessionToken.isEmpty else { return }
        let sessionHash = Data(SHA256.hash(data: Data(sessionToken.utf8)))
        if owner != userID || ownerSession != sessionHash { let pending = pendingRoute; stop(); owner = userID; ownerSession = sessionHash; if pending?.userID == userID { pendingRoute = pending } }
        guard runner == nil else { return }
        let generation = epoch
        runner = Task { [weak self] in
            await self?.requestPermissionIfNeeded()
            guard let self, self.epoch == generation else { return }
            await self.registerTokenIfNeeded()
            while !Task.isCancelled, self.epoch == generation {
                if self.active { await self.refresh() }
                do { try await Task.sleep(for: .seconds(20)) } catch { return }
            }
        }
    }
    func setActive(_ value: Bool) {
        active = value
        if value, owner > 0 { Task { await updatePermission(); await registerTokenIfNeeded(); await refresh() } }
    }
    func requestPermissionIfNeeded() async {
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined { _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge]) }
        await updatePermission()
        if [.authorized, .provisional, .ephemeral].contains(authorization), Bundle.main.object(forInfoDictionaryKey: "SPXPushEnvironment") as? String != nil { UIApplication.shared.registerForRemoteNotifications() }
    }
    func updatePermission() async { authorization = await center.notificationSettings().authorizationStatus }
    func receivedDeviceToken(_ data: Data) {
        deviceToken = data.map { String(format: "%02x", $0) }.joined()
        Task { await registerTokenIfNeeded() }
    }
    func registrationFailed(_ error: Error) { registrationError = "系统推送注册尚未就绪：\(error.localizedDescription)" }
    private func registerTokenIfNeeded() async {
        guard owner > 0, let token = deviceToken, token != registeredToken else { return }
        let generation = epoch
        do {
            try await APIClient.shared.post("push.device.register", ["provider": "apns", "platform": "ios", "device_id": .string(Keychain.deviceID), "device_token": .string(token), "bundle_id": .string(Bundle.main.bundleIdentifier ?? ""), "environment": .string(Bundle.main.object(forInfoDictionaryKey: "SPXPushEnvironment") as? String ?? "production"), "enabled": .bool(UserDefaults.standard.object(forKey: "spx.notifications.enabled") as? Bool != false), "preview": .bool(UserDefaults.standard.object(forKey: "spx.notifications.preview") as? Bool != false), "badge": .bool(UserDefaults.standard.object(forKey: "spx.notifications.badge") as? Bool != false)])
            guard generation == epoch else { return }; registeredToken = token; registrationError = nil
        } catch { if generation == epoch { registrationError = "远程推送等待服务器配置，当前实时消息和未读统计仍可使用。" } }
    }
    func refresh() async {
        guard owner > 0 else { return }
        guard !refreshing else { refreshAgain = true; return }
        refreshing = true
        let generation = epoch, version = revision, account = owner
        defer {
            refreshing = false
            if refreshAgain { refreshAgain = false; if generation == epoch { Task { await refresh() } } }
        }
        do {
            async let direct = APIClient.shared.get("chats")
            async let joined = APIClient.shared.get("groups")
            let (chats, groups) = try await (direct, joined)
            guard generation == epoch, account == owner, !Task.isCancelled else { return }
            guard version == revision else { refreshAgain = true; return }
            applySnapshot(chats: chats.list, groups: groups.list)
        } catch { /* Keep the last confirmed badge while offline. */ }
    }
    func applySnapshot(chats: [Record], groups: [Record]) {
        guard owner > 0 else { return }
        ledger.replace(chats: chats, groups: groups)
        names = Dictionary((chats.map { (NotificationUnreadLedger.key($0.id, group: false), $0.name) } + groups.map { (NotificationUnreadLedger.key($0.id, group: true), $0.name) }), uniquingKeysWith: { _, last in last })
        synchronizeBadge(); pruneDelivered()
    }
    func setVisible(target: Int, group: Bool, readingLatest: Bool, userID: Int) {
        guard owner == userID else { return }
        let key = NotificationUnreadLedger.key(target, group: group)
        if readingLatest { visibleConversation = key }
        else if visibleConversation == key { visibleConversation = nil }
    }
    func acknowledged(target: Int, group: Bool, remaining: Int, userID: Int) {
        guard owner == userID else { return }
        revision += 1; ledger.read(target: target, group: group, remaining: remaining); synchronizeBadge()
        center.removeDeliveredNotifications(withIdentifiers: [identifier(target, group: group)])
        center.removePendingNotificationRequests(withIdentifiers: [identifier(target, group: group)])
        pruneDelivered()
    }
    func incoming(_ event: JSON, userID: Int) {
        guard owner == userID, userID > 0, ["message", "group.message"].contains(event["type"].string), event["user_id"].int == 0 || event["user_id"].int == userID else { return }
        let message = event["message"].record
        guard message.id > 0, message["sender_id"].int != userID else { return }
        let group = event["group_id"].int > 0, target = group ? event["group_id"].int : event["chat_id"].int
        let key = NotificationUnreadLedger.key(target, group: group)
        let visible = active && visibleConversation == key
        guard ledger.receive(target: target, group: group, messageID: message.id, visible: visible) else { return }
        revision += 1; synchronizeBadge()
        let mentioned = ChatSoundEffects.mentioned(message, userID: userID)
        guard !visible, mentioned || !ledger.muted.contains(key), UserDefaults.standard.object(forKey: "spx.notifications.enabled") as? Bool != false else { return }
        let content = UNMutableNotificationContent()
        let sender = message.name.isEmpty ? "新消息" : message.name
        let groupName = names[key] ?? message["group_name"].string
        content.title = group ? (groupName.isEmpty ? "群聊新消息" : groupName) : (names[key] ?? sender)
        let preview = UserDefaults.standard.object(forKey: "spx.notifications.preview") as? Bool != false
        content.body = preview ? String(((group ? sender + "：" : "") + MessageContent.preview(message)).prefix(140)) : "你收到了一条新消息"
        if mentioned { content.subtitle = "有人@你" }
        content.badge = NSNumber(value: desiredBadge); content.threadIdentifier = identifier(target, group: group)
        content.userInfo = ["recipient_user_id": owner, "chat_id": group ? 0 : target, "group_id": group ? target : 0, "message_id": message.id]
        // Foreground sounds are owned by ChatSoundEffects, avoiding double playback.
        if !active { content.sound = .default }
        let generation = epoch
        let requestID = identifier(target, group: group)
        Task {
            guard generation == epoch else { return }
            try? await center.add(UNNotificationRequest(identifier: requestID, content: content, trigger: nil))
            if generation != epoch { center.removeDeliveredNotifications(withIdentifiers: [requestID]); center.removePendingNotificationRequests(withIdentifiers: [requestID]) }
        }
    }
    private func pruneDelivered() {
        let generation = epoch
        Task {
            let delivered = await center.deliveredNotifications()
            guard generation == epoch else { return }
            let ids = delivered.compactMap { notification -> String? in
                guard let route = ChatNotificationRoute(payload: notification.request.content.userInfo), route.userID == owner else { return nil }
                return (ledger.counts[NotificationUnreadLedger.key(route.targetID, group: route.group)] ?? 0) == 0 ? notification.request.identifier : nil
            }
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }
    private func identifier(_ target: Int, group: Bool) -> String { "spx-\(owner)-\(NotificationUnreadLedger.key(target, group: group))" }
    private func synchronizeBadge() {
        unread = ledger.total
        desiredBadge = UserDefaults.standard.object(forKey: "spx.notifications.badge") as? Bool == false ? 0 : unread
        badgeRevision += 1
        guard badgeTask == nil else { return }
        badgeTask = Task {
            repeat {
                let version = badgeRevision, value = desiredBadge
                try? await center.setBadgeCount(value)
                if version == badgeRevision { break }
            } while !Task.isCancelled
            badgeTask = nil
        }
    }
    func preferencesChanged() {
        synchronizeBadge(); registeredToken = nil
        if UserDefaults.standard.object(forKey: "spx.notifications.enabled") as? Bool == false { center.removeAllDeliveredNotifications(); center.removeAllPendingNotificationRequests() }
        Task { await registerTokenIfNeeded() }
    }
    func stop() {
        epoch = UUID(); runner?.cancel(); runner = nil; owner = 0; ownerSession = Data(); revision += 1
        ledger = NotificationUnreadLedger(); names = [:]; visibleConversation = nil; registeredToken = nil
        pendingRoute = nil; center.removeAllDeliveredNotifications(); center.removeAllPendingNotificationRequests(); synchronizeBadge()
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let payload = notification.request.content.userInfo
        Task { @MainActor in
            guard let route = ChatNotificationRoute(payload: payload), route.userID == self.owner else { completionHandler([]); return }
            let key = NotificationUnreadLedger.key(route.targetID, group: route.group)
            if self.active && self.visibleConversation == key { completionHandler([]) }
            else if UserDefaults.standard.object(forKey: "spx.notifications.enabled") as? Bool == false { completionHandler([]) }
            else { completionHandler([.banner, .list]) }
        }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let payload = response.notification.request.content.userInfo
        Task { @MainActor in
            if response.actionIdentifier != UNNotificationDismissActionIdentifier, let route = ChatNotificationRoute(payload: payload) { self.pendingRoute = route }
            completionHandler()
        }
    }
}
