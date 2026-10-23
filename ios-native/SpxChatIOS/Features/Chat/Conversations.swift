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
    @State private var error: String?
    @State private var loadID = UUID()
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
        List {
            Section {
                NavigationLink { ContactsView() } label: { Label("通讯录", systemImage: "person.crop.rectangle.stack") }
            }
            ForEach(conversations, id: \.key) { item in row(item, group: item.isGroup) }
            if chats.isEmpty && groups.isEmpty { EmptyState(title: "还没有会话", description: "添加朋友，开始第一段聊天", icon: "bubble.left.and.bubble.right") }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } } }
        }.listStyle(.plain).navigationTitle("聊天").searchable(text: $search, prompt: "搜索会话")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        NavigationLink { AddContactView() } label: { Label("添加朋友", systemImage: "person.badge.plus") }
                        NavigationLink { ScanCodeView() } label: { Label("扫一扫", systemImage: "qrcode.viewfinder") }
                        NavigationLink { GroupJoinView() } label: { Label("加入群组", systemImage: "person.2.badge.plus") }
                        NavigationLink { GroupCreateView() } label: { Label("创建群聊", systemImage: "bubble.left.and.bubble.right") }
                    } label: { Image(systemName: "plus.circle").font(.title3) }
                }
            }.refreshable { await load() }.spxPage()
            .onReceive(NotificationCenter.default.publisher(for: .spxRealtime).debounce(for: .milliseconds(150), scheduler: RunLoop.main)) { _ in Task { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationUpdated)) { _ in Task { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationRemoved)) { _ in Task { await load() } }
            .task(id: phase) {
                guard phase == .active else { return }
                let id = session.user?.id ?? 0
                if let cache: [Record] = await LocalStore.shared.read("chats", userID: id) { chats = cache }
                if let cache: [Record] = await LocalStore.shared.read("groups", userID: id) { groups = cache }
                while !Task.isCancelled { await load(); do { try await Task.sleep(for: .seconds(5)) } catch { return } }
            }
    }
    private func filtered(_ items: [Record]) -> [Record] { items.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) } }
    private func row(_ item: Record, group: Bool) -> some View {
        NavigationLink { ChatDetailView(item: item, isGroup: group) } label: {
            HStack(spacing: 12) {
                Avatar(url: item.avatar, name: item.name)
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.name.isEmpty ? "会话" : item.name).font(.headline).lineLimit(1)
                    Text((group && item["unread_count"].int > 0 && (item["mention_unread"].int > 0 || sounds.pendingMentions[item.id] != nil) ? "[有人@我] " : "") + item.text("last_message", "last_message_type")).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                if item["mute"].flag { Image(systemName: "bell.slash").font(.caption).foregroundStyle(.secondary) }
                if item["unread_count"].int > 0 { Text(item["unread_count"].int > 99 ? "99+" : item["unread_count"].string).font(.caption2.bold()).padding(6).foregroundStyle(.white).background(.red, in: Capsule()) }
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
        let epoch = UUID(); loadID = epoch; let owner = session.user?.id ?? 0
        do {
            async let direct = APIClient.shared.get("chats")
            async let group = APIClient.shared.get("groups")
            let (a, b) = try await (direct, group)
            guard loadID == epoch, owner == session.user?.id else { return }
            chats = a.list; groups = b.list; error = nil
            sounds.updateMuted(chats: chats, groups: groups)
            let id = owner
            await LocalStore.shared.write(chats, key: "chats", userID: id)
            await LocalStore.shared.write(groups, key: "groups", userID: id)
        } catch is CancellationError { } catch { if loadID == epoch { self.error = error.localizedDescription } }
    }
}

@MainActor final class ConversationStore: ObservableObject {
    @Published var messages: [Record] = []
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
    init(target: Int, group: Bool, userID: Int) { self.target = target; self.group = group; self.userID = userID }
    func run() async {
        let restoreRevision = revision
        if let saved: [Record] = await LocalStore.shared.read(cacheKey, userID: userID), restoreRevision == revision, !Task.isCancelled { merge(saved) }
        var round = 0
        while !Task.isCancelled {
            let currentRevision = revision
            do {
                var query = [key: String(target), "limit": "100"]
                if let last = messages.last, last.id > 0 { query["after_id"] = String(last.id) }
                let fresh = try await APIClient.shared.get(action, query).list
                guard currentRevision == revision, !Task.isCancelled else { continue }
                merge(fresh)
                if round % 5 == 0, !messages.isEmpty {
                    let ids = messages.suffix(200).map { String($0.id) }.joined(separator: ",")
                    let sync = try await APIClient.shared.get(action + ".reconcile", [key: String(target), "ids": ids])
                    guard currentRevision == revision, !Task.isCancelled else { continue }
                    let removed = Set(sync["removed_ids"].array.map(\.int))
                    messages.removeAll { removed.contains($0.id) }; merge(sync["messages"].list)
                }
                if let last = messages.last { try await APIClient.shared.post(action + ".read", [key: .number(Decimal(target)), "through_id": .number(Decimal(last.id))]) }
                guard currentRevision == revision, !Task.isCancelled else { continue }
                await LocalStore.shared.write(Array(messages.suffix(1000)), key: cacheKey, userID: userID)
                guard currentRevision == revision, !Task.isCancelled else { continue }
                error = nil; round += 1
            } catch is CancellationError { return } catch { if currentRevision == revision { self.error = error.localizedDescription } }
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
        }
    }
    func older() async {
        guard !loadingOlder, hasOlder, let first = messages.first else { return }
        loadingOlder = true; defer { loadingOlder = false }
        let currentRevision = revision
        do { let rows = try await APIClient.shared.get(action, [key: String(target), "before_id": String(first.id), "limit": "100"]).list; guard currentRevision == revision, !Task.isCancelled else { return }; hasOlder = rows.count == 100; merge(rows) }
        catch { self.error = error.localizedDescription }
    }
    func clear() { revision = UUID(); clearedThrough = max(clearedThrough, messages.last?.id ?? 0); messages = []; hasOlder = false; error = nil }
    func merge(_ rows: [Record]) {
        var map = Dictionary(uniqueKeysWithValues: messages.map { ($0.id, $0) })
        for row in rows where row.id > clearedThrough { map[row.id] = row }
        messages = map.values.sorted { $0.id < $1.id }
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
    }
    func refreshNames(for peerID: Int) async {
        guard group, peerID > 0 else { return }
        let ids = messages.filter { $0["sender_id"].int == peerID || ($0["reply"]["id"].int > 0 && ($0["reply"]["sender_id"].int == peerID || $0["reply"]["sender_id"].int == 0)) }.map { String($0.id) }
        guard !ids.isEmpty else { return }
        let currentRevision = revision
        do {
            for offset in stride(from: 0, to: ids.count, by: 200) {
                let batch = ids[offset..<min(offset + 200, ids.count)].joined(separator: ",")
                let sync = try await APIClient.shared.get(action + ".reconcile", [key: String(target), "ids": batch])
                guard currentRevision == revision, !Task.isCancelled else { return }
                merge(sync["messages"].list)
            }
            await LocalStore.shared.write(Array(messages.suffix(1000)), key: cacheKey, userID: userID)
        } catch { /* Existing names remain visible; the next normal sync retries. */ }
    }
    func edit(_ message: Record, content: String) async throws {
        let currentRevision = revision
        let value = try await APIClient.shared.post(action + ".edit", [key: .number(Decimal(target)), "message_id": message["id"], "content": .string(content)])
        guard currentRevision == revision else { return }
        merge([value.record])
    }
    func delete(_ message: Record) async throws {
        try await APIClient.shared.post(action + ".delete", [key: .number(Decimal(target)), "message_id": message["id"]])
        messages.removeAll { $0.id == message.id }
        await LocalStore.shared.write(messages, key: cacheKey, userID: userID)
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
    @State private var photos: [PhotosPickerItem] = []
    @State private var documents = false
    @State private var camera = false
    @State private var packet = false
    @State private var schedule = false
    @State private var location = false
    @State private var selectedMedia: Record?
    @State private var forward: Record?
    @State private var filePreview: URL?
    @State private var error: String?
    @State private var scrollTarget: String?
    @State private var atBottom = true
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
    @State private var mentions: [Int: String] = [:]
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
        _model = StateObject(wrappedValue: ConversationStore(target: item.id, group: isGroup, userID: userID))
    }
    private var pending: [PendingMessage] { outbox.items.filter { $0.targetID == item.id && $0.scope == (isGroup ? "group" : "chat") } }
    private var media: [Record] { model.messages.filter { ["image", "video"].contains($0["message_type"].string) } }
    private var newMessageCount: Int { model.messages.filter { $0.id > latestSeenID && $0["sender_id"].int != userID }.count }
    private var conversationContent: some View {
        VStack(spacing: 0) {
            if let value = model.error { ErrorBanner(message: value).padding(.horizontal).padding(.vertical, 5) }
            if isGroup, !groupInfo["announcement"].string.isEmpty { Button { announcement = true } label: { HStack(spacing: 8) { Image(systemName: "megaphone"); Text(groupInfo["announcement"].string).lineLimit(1); Spacer(); Image(systemName: "chevron.right").font(.caption2) }.font(.caption).foregroundStyle(SPXColor.primary).padding(.horizontal, 16).padding(.vertical, 10).background(SPXColor.card) } }
            ScrollView {
                LazyVStack(spacing: 12) {
                    if model.hasOlder, !model.messages.isEmpty {
                        Button(model.loadingOlder ? "加载中…" : "查看更早消息") { Task { await model.older() } }.font(.caption).disabled(model.loadingOlder)
                    }
                    ForEach(model.messages) { message in
                        if MessageTimeline.showsTime(before: message, in: model.messages) {
                            Text(MessageTimeline.label(message["created_at"].string)).font(.caption2).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.vertical, 4)
                        }
                        HStack(spacing: 10) {
                            if selecting { Button { toggleSelection(message.id) } label: { Image(systemName: selectedMessages.contains(message.id) ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(SPXColor.primary) }.disabled(!MessageContent.canForward(message)) }
                            MessageBubble(message: message, currentUserID: userID, open: { if selecting { toggleSelection(message.id) } else { open(message) } }, reply: { contextMessage = Record(["id": message["reply"]["id"]]) }, mention: isGroup ? { insertMention(id: message["sender_id"].int, name: message.name) } : nil)
                        }
                            .id("m-\(message.id)")
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
                    ForEach(pending) { entry in
                        HStack {
                            Spacer(minLength: 50)
                            VStack(alignment: .trailing, spacing: 5) {
                                if let local = entry.localURL { LocalAttachmentPreview(url: local, kind: entry.body["message_type"]?.string ?? "file") }
                                Text(entry.body["content"]?.string.isEmpty == false ? entry.body["content"]!.string : "正在发送附件")
                                if entry.state == .uploading { ProgressView(value: entry.progress).tint(.white); Text("\(Int(entry.progress * 100))%").font(.caption2) }
                                if entry.state == .failed {
                                    Text(entry.error ?? "发送失败").font(.caption)
                                    HStack { Button("重试") { outbox.retry(entry.id) }; Button("删除") { Task { await outbox.discard(entry.id) } } }.font(.caption).tint(.white)
                                } else { Text(entry.state == .queued ? "等待发送" : "发送中…").font(.caption2) }
                            }.padding(12).foregroundStyle(.white).background(SPXColor.primary.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
                        }.id(entry.id)
                    }
                    Color.clear.frame(height: 1).id("bottom").onAppear { atBottom = true; latestSeenID = model.messages.last?.id ?? latestSeenID }.onDisappear { atBottom = false }
                }.padding(16).scrollTargetLayout()
            }.defaultScrollAnchor(.bottom).scrollPosition(id: $scrollTarget, anchor: .bottom)
                .background { if !backgroundURL.isEmpty { CachedImage(source: backgroundURL, fit: false).opacity(0.22).clipped().allowsHitTesting(false) } }
                .scrollDismissesKeyboard(.interactively)
                .overlay(alignment: .bottomTrailing) {
                    if !atBottom {
                        Button { scrollTarget = "bottom"; latestSeenID = model.messages.last?.id ?? latestSeenID } label: {
                            Label(newMessageCount > 0 ? "\(newMessageCount) 条新消息" : "回到最新", systemImage: "arrow.down")
                                .font(.caption.weight(.semibold)).padding(.horizontal, 13).padding(.vertical, 10)
                                .background(.regularMaterial, in: Capsule()).overlay(Capsule().stroke(SPXColor.line))
                        }.padding(12)
                    }
                }
                .onChange(of: model.messages.last?.id) { old, last in
                    if old == nil || atBottom { scrollTarget = "bottom"; latestSeenID = last ?? latestSeenID }
                }
                .onChange(of: outbox.acknowledgements) { _, map in model.merge(Array(map.values.filter { $0[isGroup ? "group_id" : "chat_id"].int == item.id })) }
            if let replied { HStack { Text("回复：\(replied["content"].string)").lineLimit(1); Spacer(); Button { self.replied = nil } label: { Image(systemName: "xmark.circle.fill") } }.font(.caption).padding(10).background(.thinMaterial) }
            if !mentions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) { HStack { ForEach(mentions.keys.sorted(), id: \.self) { id in Button { let name = mentions.removeValue(forKey: id) ?? ""; text = text.replacingOccurrences(of: "@\(name) ", with: "") } label: { Label("@\(mentions[id] ?? "")", systemImage: "xmark.circle.fill").font(.caption).padding(7).background(SPXColor.primary.opacity(0.08), in: Capsule()) } } } }.padding(.horizontal, 12)
            }
            Divider()
            if selecting {
                HStack { Button("取消多选") { selecting = false; selectedMessages = [] }; Spacer(); Button("逐条转发 (\(selectedMessages.count))", systemImage: "arrowshape.turn.up.right") { forwardingSelected = true }.disabled(selectedMessages.isEmpty) }.font(.subheadline).padding(16).background(.bar)
            } else {
            HStack(alignment: .bottom, spacing: 10) {
                Button { voiceMode.toggle(); if !voiceMode { stopRecording(cancel: true) } } label: { Image(systemName: voiceMode ? "keyboard" : "waveform.circle").font(.title2) }.accessibilityLabel(voiceMode ? "切换文字输入" : "切换语音输入")
                if voiceMode {
                    Text(recordingGesture ? (cancelRecording ? "松开取消" : "松开发送，上滑取消") : "按住说话")
                        .font(.subheadline.weight(.medium)).frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(cancelRecording ? Color.red.opacity(0.12) : SPXColor.card, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(cancelRecording ? .red : SPXColor.ink)
                        .gesture(DragGesture(minimumDistance: 0).onChanged { value in beginRecording(); cancelRecording = value.translation.height < -70 }.onEnded { _ in stopRecording(cancel: cancelRecording) })
                        .accessibilityAction(named: "开始或结束录音") { if recorder.recording { stopRecording(cancel: false) } else { beginRecording() } }
                } else {
                    TextField("输入消息", text: $text, axis: .vertical).focused($composerFocused).lineLimit(1...5).padding(10).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16))
                }
                Button { emoji.toggle(); plus = false } label: { Image(systemName: "face.smiling").font(.title2) }.accessibilityLabel("表情")
                if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Button { withAnimation(.easeInOut(duration: 0.18)) { plus.toggle() } } label: { Image(systemName: "plus.circle").font(.title2) }.accessibilityLabel("更多功能")
                } else {
                    Button { sendText() } label: { Image(systemName: "arrow.up.circle.fill").font(.title2) }.accessibilityLabel("发送")
                }
            }.padding(12).background(.bar)
            if plus { plusGrid.padding(.horizontal, 16).padding(.vertical, 18).background(.bar) }
            if emoji { EmojiPanel { text += $0 } }
            }
        }
    }
    private var navigationContent: some View {
        conversationContent.navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { NavigationLink { ConversationSettingsView(item: item, isGroup: isGroup) } label: { Image(systemName: "ellipsis") } } }
            .spxPage().errorAlert($error)
            .overlay(alignment: .top) { if let mentionNotice { Text(mentionNotice).font(.subheadline.weight(.medium)).padding(.horizontal, 18).padding(.vertical, 11).background(.regularMaterial, in: Capsule()).padding(.top, 10).allowsHitTesting(false) } }
            .task(id: mentionNotice) { guard mentionNotice != nil else { return }; do { try await Task.sleep(for: .seconds(2)); mentionNotice = nil } catch { } }
    }
    private var realtimeContent: some View {
        navigationContent
            .onChange(of: text) { old, value in
                guard isGroup, !insertingMention, !mentioning, value.count == old.count + 1 else { return }
                let prefix = zip(old, value).prefix { $0.0 == $0.1 }.count
                let index = value.index(value.startIndex, offsetBy: prefix)
                if value[index] == "@" { mentionTrigger = prefix; mentioning = true }
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
                        text = saved?["text"].string ?? ""
                        for (key, name) in saved?["mention_names"].object ?? [:] { if let id = Int(key), id > 0 || id == -1 { mentions[id] = name.string } }
                    }
                    restoredDraft = true
                }
                await refreshGroup()
                if let saved: String = await LocalStore.shared.read("chat-background-\(isGroup ? "g" : "c")-\(item.id)", userID: userID) { backgroundURL = saved }
                if !isGroup, let settings = try? await APIClient.shared.get("chats.settings.get", ["chat_id": String(item.id)]) { backgroundURL = settings["background_url"].string }
            }
            .task(id: text) {
                guard restoredDraft else { return }
                do { try await Task.sleep(for: .milliseconds(350)); await LocalStore.shared.write(draftSnapshot, key: draftKey, userID: userID) } catch { }
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
            .task(id: String(describing: phase) + String(visible)) { if phase == .active && visible { await model.run() } else { stopRecording(cancel: true) } }
            .onAppear { visible = true; if isGroup { ChatSoundEffects.shared.readGroup(item.id) } }
            .onDisappear { visible = false; stopRecording(cancel: true); let snapshot = draftSnapshot; Task { await LocalStore.shared.write(snapshot, key: draftKey, userID: userID) } }
            .onReceive(CallCoordinator.shared.$call) { call in if call != nil { stopRecording(cancel: true) } }
            .onChange(of: recorder.completedURL) { _, url in if let url { ChatSoundEffects.recording = false; recordingGesture = false; recordingRequest = UUID(); recorder.completedURL = nil; Task { do { try await queue(file: url, kind: "voice", name: "语音") } catch { self.error = error.localizedDescription } } } }
            .onChange(of: recorder.errorMessage) { _, message in if let message { ChatSoundEffects.recording = false; error = message; recorder.errorMessage = nil } }
            .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
                guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                      AVAudioSession.InterruptionType(rawValue: raw) == .began, recorder.recording || recordingGesture else { return }
                stopRecording(cancel: true); error = "系统音频被打断，录音已取消"
            }
    }
    private var attachmentContent: some View {
        recordingContent
            .onChange(of: photos) { _, items in Task { await sendPhotos(items) } }
            .fileImporter(isPresented: $documents, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                Task { do { for source in try result.get() { try await queue(file: MediaFiles.keep(source, userID: userID), kind: "file", name: source.lastPathComponent) } } catch { self.error = error.localizedDescription } }
            }
            .fullScreenCover(isPresented: $camera) { NativeCamera { image, video in
                camera = false
                Task { do { if let video { try await queue(file: MediaFiles.keep(video, userID: userID), kind: "video", name: "视频") } else if let image { try await queue(file: MediaFiles.jpeg(image, userID: userID), kind: "image", name: "图片") } } catch { self.error = error.localizedDescription } }
            }.ignoresSafeArea() }
            .fullScreenCover(item: $selectedMedia) { selected in MediaPreview(messages: media, initialID: selected.id) }
    }
    private var messageSheetsContent: some View {
        attachmentContent
            .sheet(item: $forward) { ForwardMessageView(message: $0) }
            .sheet(item: $contextMessage) { message in NavigationStack { MessageContextView(targetID: item.id, group: isGroup, messageID: message.id) } }
            .sheet(isPresented: $forwardingSelected, onDismiss: { selecting = false; selectedMessages = [] }) { ForwardMessageView(messages: model.messages.filter { selectedMessages.contains($0.id) }) }
            .sheet(isPresented: $announcement, onDismiss: { let version = announcementVersion; Task { await LocalStore.shared.write(version, key: "group-announcement-\(item.id)", userID: userID) } }) { NavigationStack { ScrollView { Text(groupInfo["announcement"].string).frame(maxWidth: .infinity, alignment: .leading).padding(24).textSelection(.enabled) }.navigationTitle("群公告").toolbar { ToolbarItem(placement: .confirmationAction) { Button("我知道了") { announcement = false } } } } }
            .navigationDestination(item: $sharedProduct) { ProductDetailView(product: $0) }
            .sheet(isPresented: $sharedGroup) { NavigationStack { GroupJoinView(initialCode: sharedGroupCode).toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { sharedGroup = false } } } } }
    }
    var body: some View {
        messageSheetsContent
            .sheet(isPresented: $mentioning, onDismiss: { mentionTrigger = nil; composerFocused = true }) { MemberMentionPicker(members: members, excluding: userID, groupID: item.id) { member in insertMention(id: member["user_id"].int > 0 ? member["user_id"].int : member.id, name: member.name); mentioning = false } }
            .sheet(isPresented: $packet) { RedPacketSendView(scope: isGroup ? "group" : "chat", targetID: item.id) }
            .sheet(isPresented: $schedule) { ScheduledMessageView(scope: isGroup ? "group" : "chat", targetID: item.id) }
            .sheet(isPresented: $location) { SendLocationView(scope: isGroup ? "group" : "chat", targetID: item.id) }
            .sheet(isPresented: Binding(get: { filePreview != nil }, set: { if !$0 { filePreview = nil } })) { if let filePreview { DocumentPreview(url: filePreview) } }
    }
    private var plusGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 20) {
            PhotosPicker(selection: $photos, maxSelectionCount: 9, matching: .any(of: [.images, .videos])) { PlusAction(title: "相册", icon: "photo.on.rectangle") }
            Button { if UIImagePickerController.isSourceTypeAvailable(.camera) { camera = true } else { error = "当前设备没有可用相机" } } label: { PlusAction(title: "拍摄", icon: "camera") }
            if !isGroup {
                Button { CallCoordinator.shared.dial(chat: item, video: false) } label: { PlusAction(title: "语音通话", icon: "phone") }
                Button { CallCoordinator.shared.dial(chat: item, video: true) } label: { PlusAction(title: "视频通话", icon: "video") }
            }
            Button { packet = true } label: { PlusAction(title: "红包", icon: "gift") }
            Button { documents = true } label: { PlusAction(title: "文件", icon: "doc") }
            Button { schedule = true } label: { PlusAction(title: "定时发送", icon: "clock") }
            Button { location = true } label: { PlusAction(title: "位置", icon: "location") }
            if isGroup { Button { mentioning = true } label: { PlusAction(title: "提醒成员", icon: "at") } }
        }.buttonStyle(.plain)
    }
    private func sendText() {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        guard value.unicodeScalars.count <= 10_000 else { error = "消息最多 10000 字，请分段发送"; return }
        var body: [String: JSON] = ["content": .string(value), "message_type": "text"]
        if let replied { body["reply_to_message_id"] = .number(Decimal(replied.id)) }
        if isGroup { body["mention_user_ids"] = .array(mentions.keys.sorted().filter { value.contains("@\(mentions[$0] ?? "")") }.map { .number(Decimal($0)) }) }
        let savedMentions = mentions
        text = ""; replied = nil; mentions = [:]
        Task { do { try await outbox.enqueue(scope: isGroup ? "group" : "chat", id: item.id, body: body); scrollTarget = "bottom" } catch { if text.isEmpty { text = value; mentions = savedMentions }; self.error = error.localizedDescription } }
    }
    private func insertMention(id: Int, name: String) {
        guard id != userID, id > 0 || id == -1, !name.isEmpty else { return }
        mentions[id] = name; insertingMention = true
        if let offset = mentionTrigger, offset < text.count {
            let index = text.index(text.startIndex, offsetBy: offset)
            if text[index] == "@" { text.replaceSubrange(index...index, with: "@\(name) ") } else { text += "@\(name) " }
        } else { text += "@\(name) " }
        mentionTrigger = nil; composerFocused = true
        Task { @MainActor in await Task.yield(); insertingMention = false }
    }
    private var draftSnapshot: Record { Record(["text": .string(text), "mention_names": .object(Dictionary(uniqueKeysWithValues: mentions.map { (String($0.key), JSON.string($0.value)) }))]) }
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
        try await outbox.enqueue(scope: isGroup ? "group" : "chat", id: item.id, body: ["content": .string(name), "message_type": .string(kind)], localURL: file)
        scrollTarget = "bottom"
    }
    private func sendPhotos(_ items: [PhotosPickerItem]) async {
        for selected in items {
            do { let file = try await MediaFiles.select(selected, userID: userID); let video = selected.supportedContentTypes.contains { $0.conforms(to: .movie) }; try await queue(file: file, kind: video ? "video" : "image", name: video ? "视频" : "图片") }
            catch { self.error = error.localizedDescription }
        }
        photos = []; plus = false
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
        switch message["message_type"].string {
        case "image", "video": selectedMedia = message
        case "voice": Task { do { try await VoicePlayback.shared.toggle(message["media_url"].string, userID: userID) } catch { self.error = error.localizedDescription } }
        case "file": Task { do { filePreview = try await MediaCache.shared.file(message["media_url"].string, userID: userID) } catch { self.error = error.localizedDescription } }
        case "red_packet": NotificationCenter.default.post(name: .spxOpenPacket, object: message)
        case "call": CallCoordinator.shared.dial(chat: item, video: message["content"].string.contains("视频"))
        default: break
        }
    }
}

struct MessageBubble: View {
    let message: Record
    let currentUserID: Int
    let open: () -> Void
    var reply: (() -> Void)? = nil
    var mention: (() -> Void)? = nil
    private var mine: Bool { message["sender_id"].int == currentUserID }
    private var greeting: String { guard let data = message["content"].string.data(using: .utf8), let json = try? JSONDecoder().decode(JSON.self, from: data) else { return "恭喜发财，大吉大利" }; return json["greeting"].string }
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if mine { Spacer(minLength: 48) } else { Avatar(url: message.avatar, name: message.name, size: 34).onLongPressGesture { mention?() }.accessibilityAction(named: "提醒这位成员") { mention?() } }
            Button(action: open) {
                VStack(alignment: .leading, spacing: 6) {
                    if !mine { Text(message.name).font(.caption2).foregroundStyle(.secondary) }
                    if message["reply"]["id"].int > 0 { Text("\(message["reply"]["nickname"].string)：\(message["reply"]["preview"].string)").font(.caption).lineLimit(2).padding(8).background(.black.opacity(0.07), in: RoundedRectangle(cornerRadius: 6)).onTapGesture { reply?() }.accessibilityAddTraits(.isButton).accessibilityLabel("查看引用消息") }
                    if MessageContent.product(message) != nil || MessageContent.groupCode(message) != nil { MessageCardContent(message: message) } else { switch message["message_type"].string {
                    case "image": CachedImage(source: message["media_url"].string).frame(maxWidth: 210, maxHeight: 240).clipShape(RoundedRectangle(cornerRadius: 10))
                    case "video": VideoMessagePreview(source: message["media_url"].string).frame(width: 190, height: 112)
                    case "voice": VoiceMessageLabel(url: message["media_url"].string)
                    case "file": Label(message["content"].string, systemImage: "doc.fill")
                    case "red_packet": Label(greeting, systemImage: "gift.fill").padding(.vertical, 20)
                    case "call": Label(message["content"].string, systemImage: "phone.fill")
                    default: MessageText(content: message["content"].string, mine: mine)
                    } }
                }.padding(12).foregroundStyle(mine ? .white : SPXColor.ink)
                    .background(message["message_type"].string == "red_packet" ? Color.orange : (mine ? SPXColor.primary : SPXColor.card), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }.buttonStyle(.plain)
            if !mine { Spacer(minLength: 48) }
        }
    }
}

struct PlusAction: View {
    let title: String
    let icon: String
    var body: some View { VStack(spacing: 8) { Image(systemName: icon).font(.system(size: 23, weight: .regular)).frame(width: 54, height: 52).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16)); Text(title).font(.caption2) }.foregroundStyle(SPXColor.ink).frame(maxWidth: .infinity) }
}
