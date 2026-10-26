import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import AVKit

struct ChatListView: View {
    @ObservedObject private var sounds = ChatSoundEffects.shared
    @EnvironmentObject private var session: SessionStore
    @Environment(\.scenePhase) private var phase
    @State private var chats: [Record] = []
    @State private var groups: [Record] = []
    @State private var search = ""
    @State private var filter = 0
    @State private var error: String?
    @State private var loadID = UUID()
    @State private var listVisible = false
    @State private var loadingList = false
    @State private var reloadRequested = false
    @State private var forceReloadRequested = false
    private var conversations: [Record] {
        let direct = chats.map { item -> Record in var value = item; value["kind"] = "chat"; return value }
        let joined = groups.map { item -> Record in var value = item; value["kind"] = "group"; return value }
        return filtered(direct + joined).sorted { a, b in
            if a["pinned"].flag != b["pinned"].flag { return a["pinned"].flag }
            let first = a.text("last_message_at", "updated_at", "created_at"), second = b.text("last_message_at", "updated_at", "created_at")
            return first == second ? a.key < b.key : first > second
        }
    }
    var body: some View {
        SPXList(plain: true) {
            ForEach(conversations, id: \.key) { item in row(item, group: item.isGroup) }
            if !loadingList && chats.isEmpty && groups.isEmpty { EmptyState(title: "还没有会话", description: "添加朋友，开始第一段聊天", icon: "bubble.left.and.bubble.right") }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } } }
        }.navigationTitle("聊天").safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 12) {
                    HStack(spacing: 10) { SPXIcon("search").frame(width: 18, height: 18).foregroundStyle(SPXColor.muted); TextField("搜索会话", text: $search).font(.system(size: 14)); if !search.isEmpty { Button { search = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(SPXColor.muted) } } }.padding(11).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 12))
                    HStack(spacing: 8) { ForEach(Array(["全部", "未读", "私聊", "群聊"].enumerated()), id: \.offset) { index, title in Button { filter = index } label: { Text(title).font(.system(size: 13, weight: filter == index ? .semibold : .regular)).foregroundStyle(filter == index ? SPXColor.primary : SPXColor.muted).frame(maxWidth: .infinity).padding(.vertical, 8).background(filter == index ? SPXColor.tint : Color.clear, in: Capsule()) }.buttonStyle(.plain) } }
                }.padding(.horizontal, 18).padding(.vertical, 10).background(SPXColor.surface)
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        NavigationLink { AddContactView() } label: { Label("添加朋友", systemImage: "person.badge.plus") }
                        NavigationLink { ScanCodeView() } label: { Label("扫一扫", systemImage: "qrcode.viewfinder") }
                        NavigationLink { GroupJoinView() } label: { Label("加入群组", systemImage: "person.2.badge.plus") }
                        NavigationLink { GroupCreateView() } label: { Label("创建群聊", systemImage: "bubble.left.and.bubble.right") }
                    } label: { SPXIcon("plus").frame(width: 23, height: 23).frame(width: 44, height: 44) }
                }
            }.spxRefreshable { await load() }.spxPage()
            .onReceive(NotificationCenter.default.publisher(for: .spxRealtime)
                .filter { notification in
                    guard let event = notification.object as? JSON else { return false }
                    let type = event["type"].string
                    return type == "ready" || type.hasPrefix("message") || type.hasPrefix("group.") || type.hasPrefix("chat.") || type.hasPrefix("contact.")
                }.throttle(for: .milliseconds(600), scheduler: RunLoop.main, latest: true)) { _ in
                guard listVisible, phase == .active else { return }
                Task { await load() }
            }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationUpdated)) { _ in Task { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationRemoved)) { _ in Task { await load() } }
            .onAppear { listVisible = true }.onDisappear { listVisible = false }
            .task(id: String(describing: phase) + String(listVisible)) {
                guard phase == .active, listVisible else { return }
                let id = session.user?.id ?? 0
                if let cache: [Record] = await LocalStore.shared.read("chats", userID: id) { chats = cache }
                if let cache: [Record] = await LocalStore.shared.read("groups", userID: id) { groups = cache }
                while !Task.isCancelled { await load(); do { try await SPXSleep.seconds(20) } catch { return } }
            }
    }
    private func filtered(_ items: [Record]) -> [Record] { items.filter { (search.isEmpty || $0.name.localizedCaseInsensitiveContains(search)) && (filter != 1 || $0["unread_count"].int > 0) && (filter != 2 || !$0.isGroup) && (filter != 3 || $0.isGroup) } }
    private func row(_ item: Record, group: Bool) -> some View {
        NavigationLink { ChatDetailView(item: item, isGroup: group) } label: {
            HStack(spacing: 12) {
                Avatar(url: item.avatar, name: item.name)
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.name.isEmpty ? "会话" : item.name).font(.headline).lineLimit(1)
                    Text((group && item["unread_count"].int > 0 && (item["mention_unread"].int > 0 || sounds.pendingMentions[item.id] != nil) ? "[有人@我] " : "") + MessageContent.preview(item)).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                VStack(alignment: .trailing, spacing: 7) {
                Text(MessageTimeline.conversationLabel(item.text("last_message_at", "updated_at"))).font(.system(size: 11)).foregroundStyle(SPXColor.muted)
                if item["mute"].flag { Image(systemName: "bell.slash").font(.caption).foregroundStyle(.secondary) }
                if item["unread_count"].int > 0 { Text(item["unread_count"].int > 99 ? "99+" : item["unread_count"].string).font(.caption2.bold()).padding(6).foregroundStyle(.white).background(.red, in: Capsule()) }
                }
            }.padding(.vertical, 5)
        }
        .listRowBackground(item["pinned"].flag ? SPXColor.primary.opacity(0.035) : Color.clear)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(item["mute"].flag ? "取消免打扰" : "免打扰", systemImage: item["mute"].flag ? "bell" : "bell.slash") { update(item, key: "mute", group: group) }.tint(.gray)
            Button(item["pinned"].flag ? "取消置顶" : "置顶", systemImage: item["pinned"].flag ? "pin.slash" : "pin") { update(item, key: "pinned", group: group) }.tint(SPXColor.primary)
        }
    }
    private func update(_ item: Record, key: String, group: Bool) { Task { do { try await APIClient.shared.post(group ? "groups.settings" : "chats.settings", [group ? "group_id" : "chat_id": item["id"], key: .bool(!item[key].flag)]); await load() } catch { self.error = error.localizedDescription } } }
    private func load() async {
        guard !loadingList else {
            reloadRequested = true
            forceReloadRequested = forceReloadRequested || APIReadContext.forceRefresh
            return
        }
        loadingList = true
        defer {
            loadingList = false
            if reloadRequested {
                let force = forceReloadRequested
                reloadRequested = false; forceReloadRequested = false
                if listVisible && phase == .active {
                    Task { await APIReadContext.$forceRefresh.withValue(force) { await load() } }
                }
            }
        }
        let epoch = UUID(); loadID = epoch; let owner = session.user?.id ?? 0
        do {
            async let direct = APIClient.shared.get("chats")
            async let group = APIClient.shared.get("groups")
            let (a, b) = try await (direct, group)
            guard loadID == epoch, owner == session.user?.id else { return }
            let directChanged = chats != a.list, groupsChanged = groups != b.list
            if directChanged { chats = a.list }
            if groupsChanged { groups = b.list }
            error = nil
            sounds.updateMuted(chats: chats, groups: groups)
            if directChanged { await LocalStore.shared.write(a.list, key: "chats", userID: owner) }
            if groupsChanged { await LocalStore.shared.write(b.list, key: "groups", userID: owner) }
        } catch is CancellationError { } catch { if loadID == epoch { self.error = error.localizedDescription } }
    }
}

@MainActor final class ConversationStore: ObservableObject {
    private struct Retained {
        let store: ConversationStore
        var lastAccess: Date
    }
    private static var retained: [String: Retained] = [:]
    static func cached(target: Int, group: Bool, userID: Int) -> ConversationStore {
        let endpoint = UserDefaults.standard.string(forKey: "spx.apiURL") ?? "default"
        let credential = Keychain.read("token") ?? ""
        let key = APIReadCache.digest(endpoint + ":" + credential + ":" + String(userID) + ":" + String(group) + ":" + String(target))
        if let existing = retained[key] {
            retained[key]?.lastAccess = Date()
            return existing.store
        }
        let store = ConversationStore(target: target, group: group, userID: userID)
        retained[key] = Retained(store: store, lastAccess: Date())
        if retained.count > 12, let oldest = retained.filter({ $0.key != key && !$0.value.store.running }).min(by: { $0.value.lastAccess < $1.value.lastAccess })?.key { retained[oldest] = nil }
        return store
    }
    static func releaseCached() { retained.removeAll() }
    private var runID: UUID?
    private var running: Bool { runID != nil }
    private var lastReconciled = Date.distantPast
    @Published var messages: [Record] = [] { didSet { if oldValue != messages { timeBoundaryIDs = MessageTimeline.boundaries(in: messages) } } }
    @Published private(set) var timeBoundaryIDs: Set<Int> = []
    private var syncCursor: Int?
    private var restored = false
    private var legacyIDs: Set<Int> = []
    private var persisted: ConversationSnapshot?
    private var lastReadID = 0
    private var readingLatest = false
    private var reportingRead = false
    @Published private(set) var initialLoading = true
    @Published var error: String?
    @Published var loadingOlder = false
    @Published var hasOlder = true
    private var revision = UUID()
    private var clearedThrough = 0
    let target: Int
    let group: Bool
    let userID: Int
    var action: String { group ? "groups.messages" : "messages" }
    var key: String { group ? "group_id" : "chat_id" }
    var cacheKey: String { "messages-\(group ? "g" : "c")-\(target)" }
    private let fetch: (String, [String: String]) async throws -> JSON
    private let post: (String, [String: JSON]) async throws -> JSON
    init(target: Int, group: Bool, userID: Int,
         fetch: @escaping (String, [String: String]) async throws -> JSON = { try await APIClient.shared.get($0, $1) },
         post: @escaping (String, [String: JSON]) async throws -> JSON = { try await APIClient.shared.post($0, $1) }) {
        self.target = target; self.group = group; self.userID = userID; self.fetch = fetch; self.post = post
    }
    func run() async {
        // A reopened view takes ownership even while the canceled view's fetch is finishing.
        let identity = UUID(); runID = identity
        defer { if runID == identity { runID = nil } }
        if !restored {
            let restoreRevision = revision
            let snapshot: ConversationSnapshot? = await LocalStore.shared.read(cacheKey + "-sync-v2", userID: userID)
            guard runID == identity, restoreRevision == revision, !Task.isCancelled else { return }
            if let snapshot {
                syncCursor = snapshot.cursor; clearedThrough = snapshot.clearedThrough
                merge(snapshot.messages); persisted = snapshot
            } else if let saved: [Record] = await LocalStore.shared.read(cacheKey, userID: userID), runID == identity, restoreRevision == revision, !Task.isCancelled {
                // Legacy caches may contain gaps. Show them immediately, then establish a fresh REST window.
                legacyIDs = Set(saved.map(\.id)); merge(saved)
            }
            guard runID == identity, restoreRevision == revision, !Task.isCancelled else { return }
            restored = true
            if syncCursor != nil || !messages.isEmpty { initialLoading = false }
        }
        while runID == identity, !Task.isCancelled {
            let currentRevision = revision
            do {
                var more = true
                while more {
                    guard runID == identity, currentRevision == revision, !Task.isCancelled else { throw CancellationError() }
                    let cursor = syncCursor
                    var query = [key: String(target), "limit": "100"]
                    if let cursor, cursor > 0 { query["after_id"] = String(cursor) }
                    let fresh = try await fetch(action, query).list
                    guard runID == identity, currentRevision == revision, !Task.isCancelled else { throw CancellationError() }
                    if cursor == nil {
                        let upper = fresh.map(\.id).max() ?? 0
                        // Preserve newer realtime inserts while discarding untrusted legacy cache gaps.
                        messages = messages.filter { $0.id > upper && $0.id > clearedThrough && !legacyIDs.contains($0.id) }
                        legacyIDs = []
                        syncCursor = max(clearedThrough, upper)
                    } else if let last = fresh.map(\.id).max() {
                        syncCursor = max(cursor ?? 0, last)
                    }
                    merge(fresh)
                    initialLoading = false
                    more = (cursor ?? 0) > 0 && fresh.count == 100 && (syncCursor ?? 0) > (cursor ?? 0)
                    await persistIfNeeded()
                }
                guard runID == identity, currentRevision == revision, !Task.isCancelled else { throw CancellationError() }
                if Date().timeIntervalSince(lastReconciled) >= 15, !messages.isEmpty {
                    let ids = messages.suffix(200).map { String($0.id) }.joined(separator: ",")
                    let sync = try await fetch(action + ".reconcile", [key: String(target), "ids": ids])
                    guard runID == identity, currentRevision == revision, !Task.isCancelled else { throw CancellationError() }
                    let removed = Set(sync["removed_ids"].array.map(\.int))
                    if !removed.isEmpty { messages.removeAll { removed.contains($0.id) } }
                    merge(sync["messages"].list)
                    lastReconciled = Date()
                }
                guard runID == identity, currentRevision == revision, !Task.isCancelled else { throw CancellationError() }
                await reportReadIfNeeded()
                guard runID == identity, currentRevision == revision, !Task.isCancelled else { throw CancellationError() }
                await persistIfNeeded()
                guard runID == identity, currentRevision == revision, !Task.isCancelled else { throw CancellationError() }
                error = nil
            } catch is CancellationError { if Task.isCancelled { return }; if currentRevision != revision { continue }; return }
            catch { if runID == identity, currentRevision == revision, !Task.isCancelled { initialLoading = false; self.error = error.localizedDescription } }
            do { try await SPXSleep.seconds(3) } catch { return }
        }
    }
    func setReadVisibility(_ visible: Bool) {
        readingLatest = visible
        MessageNotifications.shared.setVisible(target: target, group: group, readingLatest: visible, userID: userID)
        if visible { Task { await reportReadIfNeeded() } }
    }
    private func reportReadIfNeeded() async {
        guard readingLatest, !reportingRead else { return }
        // An out-of-order realtime event cannot acknowledge a missing REST interval.
        let boundary = min(messages.last?.id ?? 0, syncCursor ?? 0)
        guard boundary > lastReadID else { return }
        let currentRevision = revision
        reportingRead = true; defer { reportingRead = false }
        do {
            let receipt = try await post(action + ".read", [key: .number(Decimal(target)), "through_id": .number(Decimal(boundary))])
            if currentRevision == revision {
                lastReadID = max(lastReadID, boundary)
                MessageNotifications.shared.acknowledged(target: target, group: group, remaining: receipt["unread_count"].int, userID: userID)
            }
        } catch { /* Receipt failures retry on the next visible sync. */ }
    }
    private func persistIfNeeded() async {
        guard let cursor = syncCursor else { return }
        let snapshot = ConversationSnapshot(messages: Array(messages.suffix(1000)), cursor: cursor, clearedThrough: clearedThrough)
        guard snapshot != persisted else { return }
        let currentRevision = revision
        do {
            try await LocalStore.shared.save(snapshot, key: cacheKey + "-sync-v2", userID: userID)
            if currentRevision == revision { persisted = snapshot }
        } catch { /* A failed checkpoint is retried; its cursor must never be saved separately. */ }
    }
    func older() async {
        guard !loadingOlder, hasOlder, let first = messages.first else { return }
        loadingOlder = true; defer { loadingOlder = false }
        let currentRevision = revision
        do { let rows = try await fetch(action, [key: String(target), "before_id": String(first.id), "limit": "100"]).list; guard currentRevision == revision, !Task.isCancelled else { return }; hasOlder = rows.count == 100; merge(rows); await persistIfNeeded() }
        catch { if currentRevision == revision, !Task.isCancelled, !(error is CancellationError) { self.error = error.localizedDescription } }
    }
    func clear() {
        revision = UUID(); clearedThrough = max(clearedThrough, messages.last?.id ?? 0)
        syncCursor = max(syncCursor ?? 0, clearedThrough); messages = []; hasOlder = false; error = nil; restored = true; initialLoading = false
        Task { await persistIfNeeded() }
    }
    func merge(_ rows: [Record]) {
        guard !rows.isEmpty else { return }
        var map = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
        var changed = false
        for row in rows where row.id > clearedThrough && row.id > 0 {
            var value = row
            if value["local_created_at"].string.isEmpty,
               !value["client_message_id"].string.isEmpty,
               let local = map[row.id], !local["local_created_at"].string.isEmpty,
               local["client_message_id"] == value["client_message_id"] {
                value["local_created_at"] = local["local_created_at"]
            }
            if map[row.id] != value { map[row.id] = value; changed = true }
        }
        if changed { messages = map.values.sorted { $0.id < $1.id } }
    }
    func receive(_ event: JSON) {
        if event["type"].string == "contact.remark.updated", event["user_id"].int == userID, group {
            messages = messages.map { $0.applyingGroupRemark(peerID: event["contact_user_id"].int, remark: event["remark"].string) }
            Task { await refreshNames(for: event["contact_user_id"].int) }
            return
        }
        guard event[group ? "group_id" : "chat_id"].int == target else { return }
        if ["message", "message.updated", "group.message", "group.message.updated"].contains(event["type"].string), event["message"]["id"].int > 0 { merge([event["message"].record]) }
        if event["type"].string.hasSuffix("deleted") { messages.removeAll { $0.id == event["message_id"].int } }
        Task { await persistIfNeeded() }
    }
    func refreshNames(for peerID: Int) async {
        guard group, peerID > 0 else { return }
        let ids = messages.filter { $0["sender_id"].int == peerID || ($0["reply"]["id"].int > 0 && ($0["reply"]["sender_id"].int == peerID || $0["reply"]["sender_id"].int == 0)) }.map { String($0.id) }
        guard !ids.isEmpty else { return }
        let currentRevision = revision
        do {
            for offset in stride(from: 0, to: ids.count, by: 200) {
                let batch = ids[offset..<min(offset + 200, ids.count)].joined(separator: ",")
                let sync = try await fetch(action + ".reconcile", [key: String(target), "ids": batch])
                guard currentRevision == revision, !Task.isCancelled else { return }
                merge(sync["messages"].list)
            }
            await persistIfNeeded()
        } catch { /* Existing names remain visible; the next normal sync retries. */ }
    }
    func edit(_ message: Record, content: String) async throws {
        let currentRevision = revision
        let value = try await post(action + ".edit", [key: .number(Decimal(target)), "message_id": message["id"], "content": .string(content)])
        guard currentRevision == revision else { return }
        merge([value.record])
            await persistIfNeeded()
    }
    func delete(_ message: Record) async throws {
        try await post(action + ".delete", [key: .number(Decimal(target)), "message_id": message["id"]])
        messages.removeAll { $0.id == message.id }
        await persistIfNeeded()
    }
}

struct ChatDetailView: View {
    let item: Record
    let isGroup: Bool
    @EnvironmentObject private var session: SessionStore
    var body: some View {
        ConversationView(item: item, isGroup: isGroup, userID: session.user?.id ?? 0)
            
    }
}

private struct ConversationScrollMetrics: Equatable {
    var bottomY: CGFloat?
    var viewportHeight: CGFloat?
}

private struct ConversationScrollMetricsKey: PreferenceKey {
    static var defaultValue = ConversationScrollMetrics()
    static func reduce(value: inout ConversationScrollMetrics, nextValue: () -> ConversationScrollMetrics) {
        let next = nextValue()
        if let bottom = next.bottomY { value.bottomY = bottom }
        if let height = next.viewportHeight { value.viewportHeight = height }
    }
}

struct ConversationView: View {
    let item: Record
    let isGroup: Bool
    let userID: Int
    @StateObject private var model: ConversationStore
    @ObservedObject private var outbox = Outbox.shared
    @StateObject private var recorder = VoiceRecorder()
    @Environment(\.scenePhase) private var phase
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var plus = false
    @State private var photos: [SPXPhotosPickerItem] = []
    @State private var processingPhotos = false
    @State private var sendOriginal = false
    @State private var documents = false
    @State private var camera = false
    @State private var packet = false
    @State private var schedule = false
    @State private var location = false
    @State private var selectedMedia: Record?
    @State private var forward: Record?
    @State private var filePreview: Record?
    @State private var error: String?
    @State private var pendingScrollToBottom = false
    @State private var didInitialScroll = false
    @State private var oldestDisplayedID: Int?
    @State private var atBottom = true
    @State private var lastViewportHeight: CGFloat = 0
    @State private var timelineCache = ChatTimelineCache()
    @State private var latestSeenID = 0
    @State private var replied: Record?
    @State private var editing: Record?
    @State private var editText = ""
    @State private var deleting: Record?
    @State private var sharedProduct: Record?
    @State private var sharedGroup = false
    @State private var sharedGroupCode = ""
    @State private var restoredDraft = false
    @State private var groupInfo = Record()
    @State private var announcement = false
    @State private var announcementVersion = ""
    @State private var selectedMessages = Set<Int>()
    @State private var selecting = false
    @State private var forwardingSelected = false
    @State private var backgroundURL = ""
    @State private var members: [Record] = []
    @State private var mentioning = false
    @State private var mentionTokens: [MentionToken] = []
    @State private var mentionSourceText = ""
    private var mentions: [Int: String] { Dictionary(mentionTokens.map { ($0.userID, $0.name) }, uniquingKeysWith: { first, _ in first }) }
    @State private var mentionTrigger: Int?
    @State private var insertingMention = false
    @State private var mentionNotice: String?
    @FocusState private var composerFocused: Bool
    @State private var emoji = false
    @State private var voiceMode = false
    @State private var recordingGesture = false
    @State private var cancelRecording = false
    @State private var recordingRequest = UUID()
    @State private var contextMessage: Record?
    @State private var visible = false
    private var draftKey: String { "chat-draft-\(isGroup ? "g" : "c")-\(item.id)" }
    private var title: String {
        let current = groupInfo.id > 0 ? groupInfo : item
        return isGroup ? "\(current.name) (\(current["member_count"].int))" : current.name
    }
    init(item: Record, isGroup: Bool, userID: Int) {
        self.item = item; self.isGroup = isGroup; self.userID = userID
        _model = StateObject(wrappedValue: ConversationStore.cached(target: item.id, group: isGroup, userID: userID))
    }
    private var pending: [PendingMessage] { outbox.items.filter { $0.targetID == item.id && $0.scope == (isGroup ? "group" : "chat") } }
    // Keep the initial layout bounded even when a long offline history is cached.
    private var displayedMessages: [Record] {
        guard let oldestDisplayedID else { return Array(model.messages.suffix(100)) }
        return model.messages.filter { $0.id >= oldestDisplayedID }
    }
    private var timeline: [ChatTimelineEntry] { timelineCache.entries(messages: displayedMessages, pending: pending, userID: userID) }
    private var firstDisplayedID: Int? {
        guard let oldestDisplayedID else { return model.messages.dropFirst(max(0, model.messages.count - 100)).first?.id }
        return model.messages.first { $0.id >= oldestDisplayedID }?.id
    }
    private var media: [Record] { model.messages.filter { ["image", "video"].contains($0["message_type"].string) } }
    private var newMessageCount: Int { model.messages.filter { $0.id > latestSeenID && $0["sender_id"].int != userID }.count }
    private var conversationContent: some View {
        VStack(spacing: 0) {
            if let value = model.error { ErrorBanner(message: value).padding(.horizontal).padding(.vertical, 5) }
            if isGroup, !groupInfo["announcement"].string.isEmpty { Button { announcement = true } label: { HStack(spacing: 8) { Image(systemName: "megaphone"); Text(groupInfo["announcement"].string).lineLimit(1); Spacer(); Image(systemName: "chevron.right").font(.caption2) }.font(.caption).foregroundStyle(SPXColor.primary).padding(.horizontal, 16).padding(.vertical, 10).background(SPXColor.card) } }
            if model.initialLoading, model.messages.isEmpty { HStack(spacing: 8) { ProgressView(); Text("正在同步消息…").font(.caption).foregroundStyle(.secondary) }.padding(10) }
            if processingPhotos { HStack(spacing: 8) { ProgressView(); Text("正在从相册准备附件…").font(.caption); Spacer() }.padding(.horizontal, 16).padding(.vertical, 8) }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                    if (model.hasOlder || firstDisplayedID != model.messages.first?.id), !model.messages.isEmpty {
                        Button(model.loadingOlder ? "加载中…" : "查看更早消息") { Task { await loadEarlier(proxy) } }.font(.caption).disabled(model.loadingOlder)
                    }
                    ForEach(timeline) { entry in
                        if let message = entry.message {
                        if model.timeBoundaryIDs.contains(message.id) || message.id == firstDisplayedID {
                            Text(MessageTimeline.label(message["created_at"].string)).font(.caption2).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.vertical, 4)
                        }
                        if MessageContent.isSystem(message) {
                            SystemMessageNotice(message: message).id(entry.id)
                        } else {
                        HStack(spacing: 10) {
                            if selecting { Button { toggleSelection(message.id) } label: { Image(systemName: selectedMessages.contains(message.id) ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(SPXColor.primary) }.disabled(!MessageContent.canForward(message)) }
                            MessageBubble(message: message, currentUserID: userID, isGroup: isGroup, open: { if selecting { toggleSelection(message.id) } else { open(message) } }, reply: { contextMessage = Record(["id": message["reply"]["id"]]) }, mention: isGroup ? { insertMention(id: message["sender_id"].int, name: message.name) } : nil)
                        }
                            .id(entry.id)
                            .contextMenu {
                                Button("复制", systemImage: "doc.on.doc") { UIPasteboard.general.string = message["content"].string }
                                Button("引用", systemImage: "arrowshape.turn.up.left") { replied = message }
                                if MessageContent.canForward(message) { Button("转发", systemImage: "arrowshape.turn.up.right") { forward = message } }
                                if MessageContent.canForward(message) { Button("多选", systemImage: "checkmark.circle") { selectedMessages = [message.id]; selecting = true } }
                                if message["sender_id"].int == userID, message["message_origin"].string != "call", message["message_origin"].string != "system" {
                                    if message["message_type"].string == "text", MessageContent.groupCode(message) == nil { Button("编辑", systemImage: "pencil") { editing = message; editText = message["content"].string } }
                                    if message["message_type"].string != "red_packet" { Button("为所有人删除", systemImage: "trash", role: .destructive) { deleting = message } }
                                }
                            }
                        }
                        } else if let entry = entry.pending {
                        PendingMessageBubble(entry: entry, retry: { outbox.retry(entry.id) }, discard: { Task { await outbox.discard(entry.id) } }).id(entry.id)
                    }
                    }
                        Color.clear.frame(height: 1).id("bottom")
                            .background { GeometryReader { geometry in Color.clear.preference(key: ConversationScrollMetricsKey.self, value: ConversationScrollMetrics(bottomY: geometry.frame(in: .named("conversation-viewport")).maxY)) } }
                    }.padding(.horizontal, 12).padding(.vertical, 14)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .coordinateSpace(name: "conversation-viewport")
                .clipped()
                .background {
                    GeometryReader { geometry in Color.clear.preference(key: ConversationScrollMetricsKey.self, value: ConversationScrollMetrics(viewportHeight: geometry.size.height)) }
                    if !backgroundURL.isEmpty { CachedImage(source: backgroundURL, fit: false).opacity(0.22).clipped().allowsHitTesting(false) }
                }
                .onPreferenceChange(ConversationScrollMetricsKey.self) { metrics in
                    guard let height = metrics.viewportHeight, height > 0 else { return }
                    let resized = lastViewportHeight > 0 && abs(height - lastViewportHeight) > 1
                    lastViewportHeight = height
                    // Follow the latest message across keyboard/panel size changes,
                    // while leaving someone reading older messages in place.
                    if resized, didInitialScroll, atBottom {
                        scrollToLatest(proxy, animated: false)
                        return
                    }
                    let visible = metrics.bottomY.map { $0 >= 0 && $0 <= height + 24 } ?? false
                    if atBottom != visible { atBottom = visible }
                    if visible { latestSeenID = model.messages.last?.id ?? latestSeenID }
                }
                
                .spxDismissKeyboardOnTap { dismissInputPanels() }
                .accessibilityAction(named: "收起键盘和输入面板") { dismissInputPanels() }
                

                .overlay(alignment: .bottomTrailing) {
                    if !atBottom {
                        Button { scrollToLatest(proxy, animated: true) } label: {
                            Label(newMessageCount > 0 ? "\(newMessageCount) 条新消息" : "回到最新", systemImage: "arrow.down")
                                .font(.caption.weight(.semibold)).padding(.horizontal, 13).padding(.vertical, 10)
                                .background(.regularMaterial, in: Capsule()).overlay(Capsule().stroke(SPXColor.line))
                        }.padding(12)
                    }
                }
                .onChange(of: model.messages.last?.id) { last in
                    guard old != last else { return }
                    if !didInitialScroll || atBottom { scrollToLatest(proxy, animated: didInitialScroll); latestSeenID = last ?? latestSeenID }
                }
                .onChange(of: model.messages.count) { _ in
                    if !didInitialScroll, !model.messages.isEmpty { scrollToLatest(proxy, animated: false) }
                }
                .onChange(of: pendingScrollToBottom) { requested in
                    guard requested else { return }
                    scrollToLatest(proxy, animated: true)
                    pendingScrollToBottom = false
                }
                .onAppear {
                    guard !didInitialScroll, !model.messages.isEmpty else { return }
                    scrollToLatest(proxy, animated: false)
                }
                .onChange(of: outbox.acknowledgements) { map in model.merge(Array(map.values.filter { $0[isGroup ? "group_id" : "chat_id"].int == item.id })) }
                .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { _ in
                    if composerFocused, atBottom { scrollToLatest(proxy, animated: false) }
                }
            }
            if let replied { HStack { Text("回复：\(replied["content"].string)").lineLimit(1); Spacer(); Button { self.replied = nil } label: { Image(systemName: "xmark.circle.fill") } }.font(.caption).padding(10).background(.thinMaterial) }
            if !mentions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) { HStack { ForEach(mentions.keys.sorted(), id: \.self) { id in Button { removeMention(id) } label: { Label("@\(mentions[id] ?? "")", systemImage: "xmark.circle.fill").font(.caption).padding(7).background(SPXColor.primary.opacity(0.08), in: Capsule()) } } } }.padding(.horizontal, 12)
            }
            Divider()
            if selecting {
                HStack { Button("取消多选") { selecting = false; selectedMessages = [] }; Spacer(); Button("逐条转发 (\(selectedMessages.count))", systemImage: "arrowshape.turn.up.right") { forwardingSelected = true }.disabled(selectedMessages.isEmpty) }.font(.subheadline).padding(16).background(.bar)
            } else {
            HStack(alignment: .bottom, spacing: 10) {
                Button { voiceMode.toggle(); plus = false; emoji = false; composerFocused = !voiceMode; if !voiceMode { stopRecording(cancel: true) } } label: { Image(systemName: voiceMode ? "keyboard" : "waveform.circle").font(.system(size: 25, weight: .regular)).frame(width: 32, height: 40) }.accessibilityLabel(voiceMode ? "切换文字输入" : "切换语音输入")
                if voiceMode {
                    Text(recordingGesture ? (cancelRecording ? "松开取消" : "松开发送，上滑取消") : "按住说话")
                        .font(.subheadline.weight(.medium)).frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(cancelRecording ? Color.red.opacity(0.12) : SPXColor.card, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(cancelRecording ? .red : SPXColor.ink)
                        .gesture(DragGesture(minimumDistance: 0).onChanged { value in beginRecording(); cancelRecording = value.translation.height < -70 }.onEnded { _ in stopRecording(cancel: cancelRecording) })
                        .accessibilityAction(named: "开始或结束录音") { if recorder.recording { stopRecording(cancel: false) } else { beginRecording() } }
                } else {
                    TextEditor(text: $text).font(.system(size: 16)).focused($composerFocused).frame(minHeight: 40, maxHeight: 120).padding(.horizontal, 8).padding(.vertical, 4).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 10))
                }
                Button { emoji.toggle(); plus = false; composerFocused = false } label: { Image(systemName: "face.smiling").font(.system(size: 25, weight: .regular)).frame(width: 32, height: 40) }.accessibilityLabel("表情")
                if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Button { composerFocused = false; emoji = false; withAnimation(.easeInOut(duration: 0.18)) { plus.toggle() } } label: { Image(systemName: "plus.circle").font(.system(size: 25, weight: .regular)).frame(width: 32, height: 40) }.accessibilityLabel("更多功能")
                } else {
                    Button { sendText() } label: { Text("发送").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white).padding(.horizontal, 12).frame(height: 36).background(SPXColor.primary, in: RoundedRectangle(cornerRadius: 8)) }.accessibilityLabel("发送")
                }
            }.padding(.horizontal, 10).padding(.vertical, 8).background(SPXColor.surface)
            if plus { plusGrid.padding(.horizontal, 16).padding(.vertical, 18).background(.bar) }
            if emoji { EmojiPanel { text += $0 } }
            }
        }
    }
    private func dismissInputPanels() {
        guard composerFocused || plus || emoji else { return }
        if atBottom { pendingScrollToBottom = true }
        composerFocused = false
        if plus || emoji {
            withAnimation(.easeInOut(duration: 0.18)) { plus = false; emoji = false }
        }
    }
    private var navigationContent: some View {
        conversationContent.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            
            
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { NavigationLink { ConversationSettingsView(item: item, isGroup: isGroup) } label: { Image(systemName: "ellipsis") } } }
            
            .spxPage().errorAlert($error)
            .overlay(alignment: .top) { if let mentionNotice { Text(mentionNotice).font(.subheadline.weight(.medium)).padding(.horizontal, 18).padding(.vertical, 11).background(.regularMaterial, in: Capsule()).padding(.top, 10).allowsHitTesting(false) } }
            .task(id: mentionNotice) { guard mentionNotice != nil else { return }; do { try await SPXSleep.seconds(2); mentionNotice = nil } catch { } }
    }
    private var realtimeContent: some View {
        navigationContent
            .onChange(of: text) { value in
                let userEdit = mentionSourceText != value
                synchronizeMentions(to: value)
                guard userEdit, isGroup, !insertingMention, !mentioning, value.count == old.count + 1 else { return }
                let prefix = zip(old, value).prefix { $0.0 == $0.1 }.count
                let index = value.index(value.startIndex, offsetBy: prefix)
                if value[index] == "@" { mentionTrigger = prefix; mentioning = true }
            }
            .onChange(of: composerFocused) { focused in
                guard focused else { return }
                plus = false
                emoji = false
                if atBottom { pendingScrollToBottom = true }
            }
            .onReceive(NotificationCenter.default.publisher(for: .spxMentionReceived)) { event in if let value = event.object as? Record, isGroup, value["group_id"].int == item.id { mentionNotice = "有人 @ 了你"; ChatSoundEffects.shared.readGroup(item.id) } }
            .onReceive(NotificationCenter.default.publisher(for: .spxRealtime)) { notification in if let event = notification.object as? JSON { model.receive(event) } }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationRemoved)) { event in if let value = event.object as? Record, value["target"].int == item.id, value["group"].flag == isGroup { dismiss() } }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationCleared)) { event in if let value = event.object as? Record, value["target"].int == item.id, value["group"].flag == isGroup { model.clear() } }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationUpdated)) { _ in Task { let saved: String? = await LocalStore.shared.read("chat-background-\(isGroup ? "g" : "c")-\(item.id)", userID: userID); backgroundURL = saved ?? ""; await refreshGroup() } }
    }
    private var draftContent: some View {
        realtimeContent
            .task {
                if !restoredDraft {
                    let saved: Record? = await LocalStore.shared.read(draftKey, userID: userID)
                    if text.isEmpty {
                        let value = saved?["text"].string ?? ""
                        if let raw = saved?["mention_tokens"], let data = try? JSONEncoder().encode(raw), let tokens = try? JSONDecoder().decode([MentionToken].self, from: data) {
                            mentionTokens = MentionRanges.validated(tokens, in: value)
                        } else {
                            var names: [Int: String] = [:]
                            for (key, name) in saved?["mention_names"].object ?? [:] { if let id = Int(key), id > 0 || id == -1 { names[id] = name.string } }
                            mentionTokens = MentionRanges.restoringLegacy(names, in: value)
                        }
                        mentionSourceText = value; text = value
                    }
                    restoredDraft = true
                }
                await refreshGroup()
                if let saved: String = await LocalStore.shared.read("chat-background-\(isGroup ? "g" : "c")-\(item.id)", userID: userID) { backgroundURL = saved }
                if !isGroup, let settings = try? await APIClient.shared.get("chats.settings.get", ["chat_id": String(item.id)]) { backgroundURL = settings["background_url"].string }
            }
            .task(id: draftSnapshot) {
                guard restoredDraft else { return }
                do { try await SPXSleep.milliseconds(350); await LocalStore.shared.write(draftSnapshot, key: draftKey, userID: userID) } catch { }
            }
    }
    private var messageEditorContent: some View {
        draftContent
            .alert("编辑消息", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
                TextField("消息内容", text: $editText)
                Button("保存") { if let editing { Task { do { try await model.edit(editing, content: editText) } catch { self.error = error.localizedDescription } } }; editing = nil }
                Button("取消", role: .cancel) { editing = nil }
            }
            .confirmationDialog("为所有人删除这条消息？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                Button("确认删除", role: .destructive) { if let deleting { Task { do { try await model.delete(deleting) } catch { self.error = error.localizedDescription } } }; deleting = nil }
            }
    }
    private var recordingContent: some View {
        messageEditorContent
            .task(id: String(describing: phase) + String(visible)) { model.setReadVisibility(phase == .active && visible && atBottom); if phase == .active && visible { await model.run() } else { stopRecording(cancel: true) } }
            .onChange(of: atBottom) { value in model.setReadVisibility(value && visible && phase == .active) }
            .onAppear { visible = true; if isGroup { ChatSoundEffects.shared.readGroup(item.id) } }
            .onDisappear { visible = false; model.setReadVisibility(false); stopRecording(cancel: true); let snapshot = draftSnapshot; Task { await LocalStore.shared.write(snapshot, key: draftKey, userID: userID) } }
            .onReceive(CallCoordinator.shared.$call) { call in if call != nil { stopRecording(cancel: true) } }
            .onChange(of: recorder.completedURL) { url in if let url { ChatSoundEffects.recording = false; recordingGesture = false; recordingRequest = UUID(); recorder.completedURL = nil; Task { do { try await queue(file: url, kind: "voice", name: "语音") } catch { self.error = error.localizedDescription } } } }
            .onChange(of: recorder.errorMessage) { message in if let message { ChatSoundEffects.recording = false; error = message; recorder.errorMessage = nil } }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
                guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      AVAudioSession.InterruptionType(rawValue: raw) == .began, recorder.recording || recordingGesture else { return }
                stopRecording(cancel: true); error = "系统音频被打断，录音已取消"
            }
    }
    private var attachmentContent: some View {
        recordingContent
            .onChange(of: photos) { items in
                guard !items.isEmpty, !processingPhotos else { return }
                photos = []
                processingPhotos = true
                Task { await sendPhotos(items) }
            }
            .fileImporter(isPresented: $documents, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                Task { do { for source in try result.get() { let file = try await MediaFiles.keepForSending(source, userID: userID); try await queue(file: file, kind: "file", name: source.lastPathComponent) } } catch { self.error = error.localizedDescription } }
            }
            .fullScreenCover(isPresented: $camera) { NativeCamera { image, video in
                camera = false
                Task { do { if let video { let file = try await MediaFiles.keepForSending(video, userID: userID); try await queue(file: file, kind: "video", name: "视频") } else if let image { let file = try await MediaFiles.preparePhoto(image, userID: userID); try await queue(file: file, kind: "image", name: "图片") } } catch { self.error = error.localizedDescription } }
            }.ignoresSafeArea() }
            .fullScreenCover(item: $selectedMedia) { selected in MediaPreview(messages: media, initialID: selected.id) }
    }
    private var messageSheetsContent: some View {
        attachmentContent
            .sheet(item: $forward) { ForwardMessageView(message: $0) }
            .sheet(item: $contextMessage) { message in SPXNavigationContainer { MessageContextView(targetID: item.id, group: isGroup, messageID: message.id) } }
            .sheet(isPresented: $forwardingSelected, onDismiss: { selecting = false; selectedMessages = [] }) { ForwardMessageView(messages: model.messages.filter { selectedMessages.contains($0.id) }) }
            .sheet(isPresented: $announcement, onDismiss: { let version = announcementVersion; Task { await LocalStore.shared.write(version, key: "group-announcement-\(item.id)", userID: userID) } }) { SPXNavigationContainer { ScrollView { Text(groupInfo["announcement"].string).frame(maxWidth: .infinity, alignment: .leading).padding(24).textSelection(.enabled) }.navigationTitle("群公告").toolbar { ToolbarItem(placement: .confirmationAction) { Button("我知道了") { announcement = false } } } } }
            .spxNavigationDestination(item: $sharedProduct) { ProductDetailView(product: $0) }
            .sheet(isPresented: $sharedGroup) { SPXNavigationContainer { GroupJoinView(initialCode: sharedGroupCode).toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { sharedGroup = false } } } } }
    }
    var body: some View {
        messageSheetsContent
            .sheet(isPresented: $mentioning, onDismiss: { mentionTrigger = nil; composerFocused = true }) { MemberMentionPicker(members: members, excluding: userID, groupID: item.id) { member in insertMention(id: member["user_id"].int > 0 ? member["user_id"].int : member.id, name: member.name); mentioning = false } }
            .sheet(isPresented: $packet) { RedPacketSendView(scope: isGroup ? "group" : "chat", targetID: item.id) }
            .sheet(isPresented: $schedule) { ScheduledMessageView(scope: isGroup ? "group" : "chat", targetID: item.id) }
            .sheet(isPresented: $location) { SendLocationView(scope: isGroup ? "group" : "chat", targetID: item.id) }
            .sheet(item: $filePreview) { message in FileAttachmentView(message: message, userID: userID) }
    }
    private var plusGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 4), spacing: 16) {
            SPXPhotosPicker(selection: $photos, maxSelectionCount: 9, matching: .any(of: [.images, .videos])) { PlusAction(title: sendOriginal ? "相册 · 原图" : "相册", icon: "photo.on.rectangle") }
                .contextMenu { Toggle("发送原图", isOn: $sendOriginal) }
                .disabled(processingPhotos)
            Button { if UIImagePickerController.isSourceTypeAvailable(.camera) { camera = true } else { error = "当前设备没有可用相机" } } label: { PlusAction(title: "拍摄", icon: "camera") }
            if !isGroup {
                Button { CallCoordinator.shared.dial(chat: item, video: false) } label: { PlusAction(title: "语音通话", icon: "phone") }
                Button { CallCoordinator.shared.dial(chat: item, video: true) } label: { PlusAction(title: "视频通话", icon: "video") }
            }
            Button { packet = true } label: { PlusAction(title: "红包", icon: "gift") }
            Button { documents = true } label: { PlusAction(title: "文件", icon: "doc") }
            Button { location = true } label: { PlusAction(title: "位置", icon: "location") }
            Menu {
                Button { schedule = true } label: { Label("定时发送", systemImage: "clock") }
            } label: { PlusAction(title: "更多", icon: "ellipsis") }
            if isGroup { Button { mentioning = true } label: { PlusAction(title: "提醒成员", icon: "at") } }
        }.buttonStyle(.plain)
    }
    private func synchronizeMentions(to value: String) {
        guard mentionSourceText != value else { return }
        mentionTokens = MentionRanges.updating(mentionTokens, from: mentionSourceText, to: value)
        mentionSourceText = value
    }
    private func removeMention(_ id: Int) {
        synchronizeMentions(to: text)
        let updated = MentionRanges.removing(userID: id, tokens: mentionTokens, from: text)
        mentionTokens = updated.tokens; mentionSourceText = updated.text; text = updated.text
    }
    private func sendText() {
        let source = text, value = source.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        guard value.unicodeScalars.count <= 10_000 else { error = "消息最多 10000 字，请分段发送"; return }
        synchronizeMentions(to: source)
        var body: [String: JSON] = ["content": .string(value), "message_type": "text"]
        if let replied { body["reply_to_message_id"] = .number(Decimal(replied.id)) }
        if isGroup { body["mention_user_ids"] = .array(MentionRanges.userIDs(mentionTokens, in: source).map { .number(Decimal($0)) }) }
        let savedTokens = mentionTokens, savedReply = replied
        mentionTokens = []; mentionSourceText = ""; text = ""; replied = nil
        Task {
            do { try await outbox.enqueue(scope: isGroup ? "group" : "chat", id: item.id, body: body, expectedUserID: userID); pendingScrollToBottom = true }
            catch { if text.isEmpty { mentionTokens = savedTokens; mentionSourceText = source; text = source; replied = savedReply }; self.error = error.localizedDescription }
        }
    }
    private func insertMention(id: Int, name: String) {
        guard id != userID, id > 0 || id == -1, !name.isEmpty else { return }
        synchronizeMentions(to: text)
        insertingMention = true
        var value = text
        let insertion: Int
        if let offset = mentionTrigger, offset < text.count {
            let index = text.index(text.startIndex, offsetBy: offset)
            if text[index] == "@" {
                insertion = text[..<index].utf16.count; value.replaceSubrange(index...index, with: "@\(name) ")
            } else { insertion = value.utf16.count; value += "@\(name) " }
        } else { insertion = value.utf16.count; value += "@\(name) " }
        mentionTokens = MentionRanges.updating(mentionTokens, from: text, to: value)
        mentionTokens.append(MentionToken(userID: id, name: name, location: insertion))
        mentionTokens = MentionRanges.validated(mentionTokens, in: value)
        mentionSourceText = value; text = value
        mentionTrigger = nil; composerFocused = true
        Task { @MainActor in await Task.yield(); insertingMention = false }
    }
    private var draftSnapshot: Record {
        let tokens = MentionRanges.updating(mentionTokens, from: mentionSourceText, to: text)
        return Record(["text": .string(text), "mention_tokens": .array(tokens.map(\.json))])
    }
    private func refreshGroup() async {
        guard isGroup else { return }
        do {
            groupInfo = try await APIClient.shared.get("groups.info", ["group_id": String(item.id)]).record
            if !groupInfo["announcement"].string.isEmpty {
                announcementVersion = groupInfo["announcement_version"].string.isEmpty ? groupInfo["announcement"].string : groupInfo["announcement_version"].string
                let seen: String? = await LocalStore.shared.read("group-announcement-\(item.id)", userID: userID)
                if seen != announcementVersion { announcement = true }
            }
            if groupInfo["permissions"]["view_members"].flag { members = try await APIClient.shared.get("groups.members", ["group_id": String(item.id)]).list }
        } catch { self.error = error.localizedDescription }
    }
    private func toggleSelection(_ id: Int) {
        guard let message = model.messages.first(where: { $0.id == id }), MessageContent.canForward(message) else { return }
        if selectedMessages.contains(id) { selectedMessages.remove(id) } else if selectedMessages.count < 100 { selectedMessages.insert(id) } else { error = "一次最多选择 100 条消息" }
    }
    private func queue(file: URL, kind: String, name: String) async throws {
        var content = name
        if kind == "voice", let duration = try? await AVURLAsset(url: file).load(.duration), duration.seconds.isFinite {
            let seconds = max(1, Int(ceil(duration.seconds)))
            content = "语音 · \(seconds)秒"
        }
        try await outbox.enqueue(scope: isGroup ? "group" : "chat", id: item.id, body: ["content": .string(content), "message_type": .string(kind)], localURL: file, expectedUserID: userID)
        pendingScrollToBottom = true
    }
    private func sendPhotos(_ items: [SPXPhotosPickerItem]) async {
        defer { processingPhotos = false; plus = false }
        for selected in items {
            do { let file = try await MediaFiles.select(selected, userID: userID, original: sendOriginal); let video = selected.supportedContentTypes.contains { $0.conforms(to: .movie) }; try await queue(file: file, kind: video ? "video" : "image", name: video ? "视频" : "图片") }
            catch { self.error = error.localizedDescription }
        }
    }
    private func scrollToLatest(_ proxy: ScrollViewProxy, animated: Bool) {
        let action = {
            proxy.scrollTo("bottom", anchor: .bottom)
            if oldestDisplayedID == nil { oldestDisplayedID = firstDisplayedID }
            atBottom = true
            didInitialScroll = true
            latestSeenID = model.messages.last?.id ?? latestSeenID
        }
        if animated { withAnimation(.easeOut(duration: 0.2), action) } else { action() }
    }
    private func loadEarlier(_ proxy: ScrollViewProxy) async {
        let anchor = timeline.first?.id
        let firstIndex = model.messages.firstIndex { $0.id == firstDisplayedID } ?? 0
        if firstIndex > 0 { oldestDisplayedID = model.messages[max(0, firstIndex - 100)].id }
        else {
            await model.older()
            oldestDisplayedID = model.messages.first?.id
        }
        // Prepending history keeps the original first message in the same reading position.
        if let anchor {
            var transaction = Transaction(); transaction.disablesAnimations = true
            withTransaction(transaction) { proxy.scrollTo(anchor, anchor: .top) }
        }
    }
    private func beginRecording() {
        guard !recordingGesture else { return }
        guard CallCoordinator.shared.call == nil else { error = "通话中暂不能录制语音消息"; return }
        recordingGesture = true; cancelRecording = false; let request = UUID(); recordingRequest = request
        VoicePlayback.shared.stop()
        ChatSoundEffects.shared.stopEffect(); ChatSoundEffects.recording = true
        Task {
            do { try await recorder.start(userID: userID); if recordingRequest != request || !recordingGesture { recorder.cancel() } }
            catch { if recordingRequest == request { recordingGesture = false; ChatSoundEffects.recording = false; self.error = error.localizedDescription } }
        }
    }
    private func stopRecording(cancel: Bool) {
        ChatSoundEffects.recording = false
        recordingRequest = UUID(); recordingGesture = false; cancelRecording = false
        if cancel { recorder.cancel() } else { recorder.finish() }
    }
    private func open(_ message: Record) {
        if let card = MessageContent.product(message) { sharedProduct = Record(["id": card["product_id"], "name": card["name"], "image_url": card["image_url"], "cny_price": card["cny_price"]]); return }
        if let code = MessageContent.groupCode(message) { sharedGroupCode = code; sharedGroup = true; return }
        if message["message_origin"].string == "call", !isGroup { CallCoordinator.shared.dial(chat: item, video: message["content"].string.hasPrefix("[视频通话]")); return }
        switch MessageContent.messageType(message) {
        case "image", "video": selectedMedia = message
        case "voice": Task { do { try await VoicePlayback.shared.toggle(message["media_url"].string, userID: userID) } catch { self.error = error.localizedDescription } }
        case "file": filePreview = message
        case "red_packet": NotificationCenter.default.post(name: .spxOpenPacket, object: message)
        case "call": CallCoordinator.shared.dial(chat: item, video: message["content"].string.contains("视频"))
        default: break
        }
    }
}

struct SystemMessageNotice: View {
    let message: Record
    var body: some View {
        Text(message["content"].string)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 20)
            .padding(.vertical, 6)
            .accessibilityLabel("系统通知：\(message["content"].string)")
    }
}

struct MessageBubble: View {
    let message: Record
    let currentUserID: Int
    var isGroup = false
    let open: () -> Void
    var reply: (() -> Void)? = nil
    var mention: (() -> Void)? = nil
    private var mine: Bool { message["sender_id"].int == currentUserID }
    private var isMedia: Bool { ["image", "video"].contains(message["message_type"].string) }
    private var type: String { MessageContent.messageType(message) }
    private var greeting: String { MessageContent.redPacketGreeting(message) }
    var body: some View {
        if MessageContent.isSystem(message) {
            SystemMessageNotice(message: message)
        } else {
        HStack(alignment: .top, spacing: 10) {
            if mine { Spacer(minLength: 52) } else { Avatar(url: message.avatar, name: message.name, size: 36).onLongPressGesture { mention?() }.accessibilityAction(named: "提醒这位成员") { mention?() } }
            VStack(alignment: mine ? .trailing : .leading, spacing: 5) {
            if !mine, isGroup { Text(message.name).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
            Button(action: open) {
                VStack(alignment: .leading, spacing: 6) {
                    if message["reply"]["id"].int > 0 { Text("\(message["reply"]["nickname"].string)：\(message["reply"]["preview"].string)").font(.caption).lineLimit(2).padding(8).background(.black.opacity(0.07), in: RoundedRectangle(cornerRadius: 6)).onTapGesture { reply?() }.accessibilityAddTraits(.isButton).accessibilityLabel("查看引用消息") }
                    if MessageContent.product(message) != nil || MessageContent.groupCode(message) != nil { MessageCardContent(message: message) } else { switch type {
                    case "image": CachedImage(source: message["media_url"].string, messagePreview: true).background(SPXColor.card).clipShape(RoundedRectangle(cornerRadius: 12))
                    case "video": VideoMessagePreview(source: message["media_url"].string).frame(width: ChatMediaLayout.width, height: ChatMediaLayout.height)
                    case "voice": VoiceMessageLabel(url: message["media_url"].string, content: message["content"].string, incoming: !mine, userID: currentUserID)
                    case "file": Label(message["content"].string, systemImage: "doc.fill")
                    case "red_packet":
                        VStack(alignment: .leading, spacing: 5) {
                            Label("红包", systemImage: "gift.fill").font(.system(size: 16, weight: .semibold))
                            Text(greeting).font(.caption).lineLimit(1)
                        }.padding(.vertical, 10)
                    case "call": Label(message["content"].string, systemImage: "phone.fill")
                    default: MessageText(content: message["content"].string, mine: mine)
                    } }
                }.font(.system(size: 16)).padding(isMedia ? 0 : 12).foregroundStyle(mine ? .white : SPXColor.ink)
                    .background(isMedia ? Color.clear : (type == "red_packet" ? Color.orange : (mine ? SPXColor.primary : SPXColor.card)), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay { if !mine, !isMedia { RoundedRectangle(cornerRadius: 12).stroke(SPXColor.line, lineWidth: 0.5) } }
            }.buttonStyle(.plain)
            }
            if !mine { Spacer(minLength: 44) }
        }
        }
    }
}

struct PendingMessageBubble: View {
    let entry: PendingMessage
    let retry: () -> Void
    let discard: () -> Void
    private var kind: String { entry.body["message_type"]?.string ?? "text" }
    private var isMedia: Bool { ["image", "video"].contains(kind) }
    private var status: String {
        switch entry.state {
        case .queued: return "等待发送"
        case .uploading: return "上传中 \(Int(entry.progress * 100))%"
        case .sending: return "发送中…"
        case .failed: return entry.error ?? "发送失败"
        }
    }
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Spacer(minLength: 52)
            VStack(alignment: .trailing, spacing: 6) {
                Group {
                    if isMedia, let local = entry.localURL { LocalAttachmentPreview(url: local, kind: kind) }
                    else { Label(entry.body["content"]?.string ?? "附件", systemImage: kind == "file" ? "doc.fill" : (kind == "voice" ? "waveform" : "bubble.right")).font(.system(size: 16)).padding(12).foregroundStyle(.white).background(SPXColor.primary, in: RoundedRectangle(cornerRadius: 12)) }
                }
                if entry.state == .uploading { ProgressView(value: entry.progress).frame(width: 180).tint(SPXColor.primary) }
                Text(status).font(.system(size: 11)).foregroundStyle(entry.state == .failed ? Color.red : .secondary).lineLimit(3).frame(maxWidth: ChatMediaLayout.width, alignment: .trailing)
                if entry.state == .failed {
                    HStack(spacing: 16) {
                        Button("重试", systemImage: "arrow.clockwise", action: retry)
                        Button("删除", role: .destructive, action: discard)
                    }.font(.caption).buttonStyle(.plain)
                }
            }
        }
    }
}

struct PlusAction: View {
    let title: String
    let icon: String
    var body: some View {
        VStack(spacing: 8) {
            SPXIcon(icon).frame(width: 25, height: 25).frame(width: 48, height: 48)
                .background(SPXColor.card, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(SPXColor.line, lineWidth: 0.7))
            Text(title).font(.system(size: 12)).lineLimit(1).minimumScaleFactor(0.85)
        }.foregroundStyle(SPXColor.ink).frame(maxWidth: .infinity, minHeight: 82).contentShape(Rectangle())
    }
}
