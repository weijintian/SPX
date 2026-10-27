import SwiftUI
import CoreLocation
import PhotosUI

struct ContactsView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var loadID = UUID()
    @State private var rows: [Record] = []
    @State private var search = ""
    @State private var loading = true
    @State private var error: String?
    private var filtered: [Record] { rows.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || $0["spx_id"].string.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        SPXList(plain: true) {
            NavigationLink { AddContactView() } label: { HStack(spacing: 14) { SPXIcon("plus").frame(width: 24,height: 24).foregroundStyle(SPXColor.primary).frame(width: 48,height: 48).background(SPXColor.tint,in: RoundedRectangle(cornerRadius: 14)); Text("添加朋友").font(.system(size: 16,weight: .medium)) } }
            ForEach(filtered) { item in
                NavigationLink { UserProfileView(userID: item["contact_user_id"].int > 0 ? item["contact_user_id"].int : item.id) } label: {
                    HStack(spacing: 14) { Avatar(url: item.avatar,name: item.name,size: 48); VStack(alignment: .leading,spacing: 6) { Text(item.name).font(.system(size: 16,weight: .medium)); Text(item["spx_id"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted) } }.padding(.vertical,4)
                }
            }
            if loading && rows.isEmpty { ProgressView("正在读取通讯录…").frame(maxWidth: .infinity) }
            if !loading && filtered.isEmpty { EmptyState(title: search.isEmpty ? "还没有好友" : "未找到匹配的好友",description: search.isEmpty ? "通过 SPX ID、邮箱或二维码添加朋友" : "试试搜索备注、昵称或 SPX ID") }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } } }
        }.navigationTitle("通讯录").safeAreaInset(edge: .top) {
            HStack(spacing: 10) { SPXIcon("search").frame(width: 18,height: 18).foregroundStyle(SPXColor.muted); TextField("搜索好友、备注或 SPX ID",text: $search).font(.system(size: 14)); if !search.isEmpty { Button { search = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(SPXColor.muted) } } }.padding(12).background(SPXColor.card,in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal,18).padding(.vertical,10).background(SPXColor.surface)
        }.task { if let cached: [Record] = await LocalStore.shared.read("contacts",userID: session.user?.id ?? 0) { rows = cached }; await load() }.spxRefreshable { await load() }
            .onReceive(NotificationCenter.default.publisher(for: .spxConversationUpdated)) { _ in Task { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: .spxRealtime).receive(on: RunLoop.main)) { notification in
                guard let event = notification.object as? JSON else { return }
                let type = event["type"].string
                guard type == "ready" || type.hasPrefix("contact.") else { return }
                guard type == "ready" || event["user_id"].int == 0 || event["user_id"].int == session.user?.id else { return }
                guard type == "contact.remark.updated" else { Task { await load() }; return }
                loadID = UUID()
                loading = false
                rows = rows.map { row in
                    let peer = row["contact_user_id"].int > 0 ? row["contact_user_id"].int : row.id
                    guard peer == event["contact_user_id"].int else { return row }
                    var updated = row; updated["remark"] = event["remark"]; return updated.normalizedContact
                }
                let cache = rows, owner = session.user?.id ?? 0
                Task { await LocalStore.shared.write(cache,key: "contacts",userID: owner) }
            }
    }
    private func load() async {
        let requestID = UUID(), owner = session.user?.id ?? 0; loadID = requestID; loading = true
        defer { if loadID == requestID { loading = false } }
        do { let values = try await APIClient.shared.get("contacts").list; guard loadID == requestID,session.user?.id == owner,!Task.isCancelled else { return }; rows = values; error = nil; await LocalStore.shared.write(values,key: "contacts",userID: owner) }
        catch is CancellationError { }
        catch { if loadID == requestID { self.error = error.localizedDescription } }
    }
}

struct AddContactView: View {
    @State private var lookup = ""
    @State private var rows: [Record] = []
    @State private var error: String?
    @State private var notice: String?
    @State private var busy = false
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "添加朋友", subtitle: "输入朋友的 SPX ID 或邮箱，查看资料后添加", icon: "person.badge.plus")
            SPXCard { VStack(alignment: .leading, spacing: 14) {
                SPXField("SPX ID / 邮箱") { TextField("输入 SPX ID 或邮箱", text: $lookup).textInputAutocapitalization(.never).submitLabel(.search).onSubmit { if !busy && !lookup.isEmpty { search() } } }
                Button { search() } label: { Label(busy ? "正在查找…" : "查找朋友", systemImage: "magnifyingglass").frame(maxWidth: .infinity, minHeight: 44) }.disabled(busy || lookup.isEmpty)
            } }
            if !rows.isEmpty { SPXSectionTitle(title: "查找结果", detail: "\(rows.count) 位") }
            ForEach(rows) { item in
                NavigationLink { UserProfileView(userID: item.id) } label: { SPXCard { HStack(spacing: 12) {
                    Avatar(url: item.avatar, name: item.name, size: 48)
                    VStack(alignment: .leading, spacing: 6) { Text(item.name).font(.system(size: 16, weight: .semibold)).foregroundStyle(SPXColor.ink); Text(item["spx_id"].string).font(.caption).foregroundStyle(SPXColor.muted) }
                    Spacer(minLength: 8); SPXIcon("chevron").frame(width: 14, height: 14).foregroundStyle(SPXColor.muted)
                } } }.buttonStyle(.plain)
            }
        }.navigationTitle("添加朋友").safeAreaInset(edge: .bottom) {
            SPXBottomAction { Button(busy ? "处理中…" : "添加该 SPX ID / 邮箱") { add() }.disabled(lookup.isEmpty || busy) }
        }.errorAlert($error).successToast($notice)
    }
    private func search() { busy = true; Task { defer { busy = false }; do { rows = try await APIClient.shared.get("contacts.search", ["q": lookup]).list } catch { self.error = error.localizedDescription } } }
    private func add() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("contacts.add", [lookup.contains("@") ? "email" : "spx_id": .string(lookup)]); notice = "好友已添加"; NotificationCenter.default.post(name: .spxConversationUpdated, object: nil) } catch { self.error = error.localizedDescription } } }
}

struct UserProfileView: View {
    let userID: Int
    @EnvironmentObject private var session: SessionStore
    @State private var profile = Record()
    @State private var chat: Record?
    @State private var remark = ""
    @State private var error: String?
    @State private var notice: String?
    @State private var delete = false
    @State private var busy = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if profile.id == 0 { ProgressView("正在读取资料…").frame(maxWidth: .infinity).padding(24) }
                else {
                    SPXCard { HStack(spacing: 18) { Avatar(url: profile.avatar, name: profile.name, size: 72); VStack(alignment: .leading, spacing: 8) { Text(profile.name).font(.system(size: 23, weight: .semibold)).lineLimit(2); Text("SPX ID：" + profile["spx_id"].string).font(.system(size: 13)).foregroundStyle(SPXColor.muted); if !profile["remark"].string.isEmpty { Text(profile["remark"].string).font(.system(size: 13)).foregroundStyle(SPXColor.muted) } }; Spacer(minLength: 0) } }
                    if profile["is_contact"].flag {
                        NavigationLink { QRCodeView(value: "SPXUSER:\(profile["spx_id"].string)", title: profile.name) } label: { SPXMenuRow(title: "个人二维码", icon: "qrcode") }
                        Button { openChat() } label: { Label("发消息", systemImage: "bubble.left.and.bubble.right").frame(maxWidth: .infinity) }.buttonStyle(SPXPrimaryButtonStyle())
                        HStack(spacing: 12) { callButton("语音通话", "phone", video: false); callButton("视频通话", "video", video: true) }
                        SPXPanel("备注") { TextField("设置联系人备注", text: $remark); Button("保存备注") { updateRemark() }.font(.system(size: 14, weight: .semibold)) }
                        Button("删除好友及双方聊天记录", role: .destructive) { delete = true }.font(.system(size: 14)).frame(maxWidth: .infinity).padding(16).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16))
                    } else { Button("添加朋友") { addContact() }.frame(maxWidth: .infinity).buttonStyle(SPXPrimaryButtonStyle()).disabled(profile.id == 0) }
                }
                if let error { ErrorBanner(message: error); if profile.id == 0 { Button("重新加载") { Task { await loadProfile() } } } }
            }.padding(18)
        }.navigationTitle("个人资料").spxPage().disabled(busy)
            .spxNavigationDestination(item: $chat) { ChatDetailView(item: $0, isGroup: false) }
            .task { await loadProfile() }.successToast($notice)
            .confirmationDialog("删除好友将清除双方聊天记录", isPresented: $delete, titleVisibility: .visible) {
                Button("确认删除", role: .destructive) { guard !busy else { return }; busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("contacts.delete", ["contact_user_id": .number(Decimal(userID))]); profile["is_contact"] = false; notice = "好友已删除"; NotificationCenter.default.post(name: .spxConversationUpdated, object: nil) } catch { self.error = error.localizedDescription } } }
                Button("取消", role: .cancel) { }
            }
    }
    private func loadProfile() async {
        do { profile = try await APIClient.shared.get("user.profile", ["id": String(userID)]).record; remark = profile["contact_remark"].string; error = nil }
        catch { self.error = error.localizedDescription }
    }
    private func callButton(_ title: String, _ icon: String, video: Bool) -> some View {
        Button { guard !busy else { return }; busy = true; Task { defer { busy = false }; do { let item = try await directChat(); CallCoordinator.shared.dial(chat: item, video: video) } catch { self.error = error.localizedDescription } } } label: {
            VStack(spacing: 10) { SPXIcon(video ? "video" : "phone").frame(width: 26, height: 26); Text(title).font(.subheadline.weight(.medium)) }.frame(maxWidth: .infinity).padding(18).foregroundStyle(SPXColor.primary).background(SPXColor.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
        }.buttonStyle(.plain)
    }
    private func directChat() async throws -> Record {
        let data = try await APIClient.shared.post("chats.create", ["peer_user_id": .number(Decimal(userID))])
        return Record(["id": data["chat_id"], "peer_user_id": .number(Decimal(userID)), "nickname": .string(profile.name), "avatar_url": .string(profile.avatar)])
    }
    private func openChat() { busy = true; Task { defer { busy = false }; do { chat = try await directChat() } catch { self.error = error.localizedDescription } } }
    private func addContact() {
        guard !busy else { return }; busy = true
        Task { defer { busy = false }; do { try await APIClient.shared.post("contacts.add", ["spx_id": profile["spx_id"]]); profile = try await APIClient.shared.get("user.profile", ["id": String(userID)]).record } catch { self.error = error.localizedDescription } }
    }
    private func updateRemark() {
        guard !busy, let owner = session.user?.id else { return }
        let raw = remark.trimmingCharacters(in: .whitespacesAndNewlines)
        let saved = ["null", "undefined"].contains(raw.lowercased()) ? "" : raw
        busy = true
        Task {
            defer { busy = false }
            do {
                try await APIClient.shared.post("contacts.remark", ["contact_user_id": .number(Decimal(userID)), "remark": .string(saved)])
                guard owner == session.user?.id else { return }
                await LocalStore.shared.updateGroupContactRemark(peerID: userID, remark: saved, userID: owner)
                guard owner == session.user?.id else { return }
                profile["contact_remark"] = .string(saved)
                profile["display_name"] = .string(saved.isEmpty ? profile["nickname"].string : saved)
                NotificationCenter.default.post(name: .spxRealtime, object: JSON.object(["type": .string("contact.remark.updated"), "user_id": .number(Decimal(owner)), "contact_user_id": .number(Decimal(userID)), "remark": .string(saved)]))
                notice = "备注已保存"
            } catch { self.error = error.localizedDescription }
        }
    }
}

struct GroupCreateView: View {
    @State private var contacts: [Record] = []
    @State private var selected = Set<Int>()
    @State private var name = ""
    @State private var chat: Record?
    @State private var error: String?
    @State private var busy = false
    @State private var loading = true
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "一起聊聊", subtitle: "设置群名称，再选择要邀请的朋友", icon: "person.3")
            SPXCard { SPXField("群名称") { TextField("给群聊起个名字", text: $name) } }
            SPXSectionTitle(title: "选择朋友", detail: "已选 \(selected.count) 位")
            if loading { ProgressView("正在读取朋友列表…").frame(maxWidth: .infinity).padding(20) }
            if !loading && contacts.isEmpty && error == nil { EmptyState(title: "暂无可邀请的朋友", description: "先添加朋友，再邀请他们加入群聊", icon: "person.2") }
            if let error { ErrorBanner(message: error); Button("重新读取朋友列表") { Task { await loadContacts() } }.frame(maxWidth: .infinity, minHeight: 44).disabled(loading || busy) }
            ForEach(contacts) { item in
                let id = item["contact_user_id"].int > 0 ? item["contact_user_id"].int : item.id
                Button { if selected.contains(id) { selected.remove(id) } else { selected.insert(id) } } label: {
                    SPXCard { HStack(spacing: 12) { Avatar(url: item.avatar, name: item.name, size: 44); Text(item.name).font(.system(size: 15)).foregroundStyle(SPXColor.ink); Spacer(); Image(systemName: selected.contains(id) ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(selected.contains(id) ? SPXColor.primary : SPXColor.muted) }.frame(minHeight: 44) }
                }.buttonStyle(.plain).disabled(busy).accessibilityAddTraits(selected.contains(id) ? .isSelected : [])
            }
        }.navigationTitle("创建群聊").safeAreaInset(edge: .bottom) {
            SPXBottomAction { Button(busy ? "正在创建…" : "创建群聊 · \(selected.count) 位朋友") { create() }.disabled(busy || loading || selected.isEmpty || name.trimmingCharacters(in: .whitespaces).isEmpty) }
        }.task { await loadContacts() }
            .spxNavigationDestination(item: $chat) { ChatDetailView(item: $0, isGroup: true) }
    }
    private func loadContacts() async {
        guard !busy else { return }; loading = true
        defer { loading = false }
        do { contacts = try await APIClient.shared.get("contacts").list; error = nil }
        catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
    private func create() { busy = true; Task { defer { busy = false }; do { let result = try await APIClient.shared.post("groups.create", ["name": .string(name), "member_ids": .array(selected.sorted().map { .number(Decimal($0)) })]); chat = Record(["id": result["group_id"], "name": .string(name), "member_count": result["member_count"]]) } catch { self.error = error.localizedDescription } } }
}

struct GroupJoinView: View {
    var initialCode = ""
    @State private var code = ""
    @State private var result: Record?
    @State private var resultCode = ""
    @State private var requestID = UUID()
    @State private var searching = false
    @State private var joining = false
    @State private var chat: Record?
    @State private var error: String?
    private var normalizedCode: String { code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "加入群组", subtitle: "输入六位群号，确认群资料后加入", icon: "person.3")
            SPXCard { VStack(alignment: .leading, spacing: 14) {
                SPXField("六位群号") { TextField("输入六位群号", text: $code).textInputAutocapitalization(.characters).disabled(joining).submitLabel(.search).onSubmit { if !searching && !joining && normalizedCode.count == 6 { search() } } }
                Button(searching ? "查找中…" : "查找群组") { search() }.frame(maxWidth: .infinity, minHeight: 44).disabled(searching || joining || normalizedCode.count != 6)
            } }
            if let result, resultCode == normalizedCode {
                SPXSectionTitle(title: "群组资料")
                SPXCard { VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 14) { Avatar(url: result.avatar, name: result.name, size: 60); VStack(alignment: .leading, spacing: 7) { Text(result.name).font(.system(size: 20, weight: .semibold)); Text("群号 " + resultCode).font(.caption).foregroundStyle(SPXColor.muted) } }
                    if !result["announcement"].string.isEmpty { Divider(); Text(result["announcement"].string).font(.system(size: 14)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true) }
                } }
            }
        }.navigationTitle("加入群组").spxNavigationDestination(item: $chat) { ChatDetailView(item: $0, isGroup: true) }
            .safeAreaInset(edge: .bottom) { if let result, resultCode == normalizedCode { SPXBottomAction { Button(joining ? "正在加入…" : "加入群组") { join(result, code: resultCode) }.disabled(joining) } } }
            .onAppear { if code.isEmpty { code = initialCode } }
            .onChange(of: code) { _ in
                result = nil; resultCode = ""; requestID = UUID(); searching = false
                if normalizedCode.count == 6, normalizedCode == initialCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() { search() }
            }
            .errorAlert($error)
    }
    private func search() {
        let query = normalizedCode, identity = UUID(); requestID = identity; searching = true
        Task {
            defer { if requestID == identity { searching = false } }
            do {
                let value = try await APIClient.shared.get("groups.search", ["group_code": query]).record
                guard requestID == identity, query == normalizedCode else { return }
                guard value.id > 0 else { throw APIError.server(404, "未找到该群组") }
                result = value; resultCode = query
            } catch { if requestID == identity { self.error = error.localizedDescription } }
        }
    }
    private func join(_ result: Record, code query: String) {
        guard !joining, query == normalizedCode, query == resultCode else { return }
        // Invalidate a duplicate search that may still be completing. Its late
        // error must not appear after the join request already succeeded.
        requestID = UUID()
        searching = false; joining = true; error = nil
        Task {
            defer { joining = false }
            do {
                let response = try await APIClient.shared.post("groups.join", ["group_code": .string(query)])
                guard response["group_id"].int > 0 || result.id > 0 else { throw APIError.invalidResponse }
                var item = result
                item["id"] = response["group_id"].int > 0 ? response["group_id"] : result["id"]
                item["member_count"] = response["member_count"].int > 0 ? response["member_count"] : result["member_count"]
                error = nil
                chat = item
            } catch { self.error = error.localizedDescription }
        }
    }
}

struct ForwardMessageView: View {
    @EnvironmentObject private var session: SessionStore
    let messages: [Record]
    init(message: Record) { messages = [message] }
    init(messages: [Record]) { self.messages = messages }
    @Environment(\.dismiss) private var dismiss
    @State private var chats: [Record] = []
    @State private var groups: [Record] = []
    @State private var contacts: [Record] = []
    @State private var selection = Set<String>()
    @State private var accepted = Set<String>()
    @State private var query = ""
    @State private var error: String?
    @State private var busy = false
    @State private var confirm = false
    @State private var loading = true
    private var available: [Record] { messages.filter(MessageContent.canForward) }
    var body: some View {
        SPXNavigationContainer {
            SPXDetailPage {
                SPXPageHeading(title: "选择接收人", subtitle: "已选 \(selection.count) 个会话 · 转发 \(available.count) 条消息", icon: "arrowshape.turn.up.right")
                if !chats.filter(matches).isEmpty { SPXSectionTitle(title: "最近会话"); ForEach(chats.filter(matches)) { target($0, scope: "chat") } }
                if !contacts.filter(matches).isEmpty { SPXSectionTitle(title: "通讯录"); ForEach(contacts.filter(matches)) { target($0, scope: "contact") } }
                if !groups.filter(matches).isEmpty { SPXSectionTitle(title: "群组"); ForEach(groups.filter(matches)) { target($0, scope: "group") } }
                if loading { ProgressView("正在读取会话…").frame(maxWidth: .infinity).padding(20) }
                if !loading && error == nil && chats.filter(matches).isEmpty && contacts.filter(matches).isEmpty && groups.filter(matches).isEmpty { EmptyState(title: query.isEmpty ? "暂无可选会话" : "未找到匹配结果", description: "可搜索朋友备注、昵称或群名称") }
                if let error { ErrorBanner(message: error); Button("重新加载会话") { Task { await load() } }.frame(maxWidth: .infinity, minHeight: 44).disabled(loading || busy) }
            }.navigationTitle("转发 "+String(available.count)+" 条消息").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $query, prompt: "搜索朋友或群聊").disabled(busy)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) }
                }
                .safeAreaInset(edge: .bottom) { SPXBottomAction { Button(busy ? "正在转发…" : "发送给 \(selection.count) 个会话") { confirm = true }.disabled(busy || selection.isEmpty || available.isEmpty) } }
                .confirmationDialog("按原顺序逐条转发给所选会话？", isPresented: $confirm, titleVisibility: .visible) { Button("确认发送") { send() } }
                .task { await load() }
        }.interactiveDismissDisabled(busy)
    }
    private func matches(_ item: Record) -> Bool { query.isEmpty || item.name.localizedCaseInsensitiveContains(query) }
    private func target(_ item: Record, scope: String) -> some View {
        let key = scope+":"+String(item.id)
        return Button { if selection.contains(key) { selection.remove(key) } else { selection.insert(key) } } label: {
            SPXCard { HStack(spacing: 12) { Avatar(url: item.avatar, name: item.name, size: 44); Text(item.name).font(.system(size: 15)).foregroundStyle(SPXColor.ink); Spacer(); Image(systemName: selection.contains(key) ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(selection.contains(key) ? SPXColor.primary : SPXColor.muted) }.frame(minHeight: 44) }
        }.buttonStyle(.plain).accessibilityAddTraits(selection.contains(key) ? .isSelected : [])
    }
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            async let c = APIClient.shared.get("chats"); async let g = APIClient.shared.get("groups"); async let f = APIClient.shared.get("contacts")
            let (a,b,d) = try await (c,g,f); chats = a.list; groups = b.list
            let existing = Set(chats.map { $0["peer_user_id"].int })
            contacts = d.list.compactMap { contact in
                var value = contact; let id = contact["contact_user_id"].int > 0 ? contact["contact_user_id"].int : contact.id
                guard !existing.contains(id) else { return nil }; value["id"] = .number(Decimal(id)); return value
            }
            error = nil
        } catch { self.error = error.localizedDescription }
    }
    private func send() {
        guard !busy else { return }; busy = true
        let destinations = selection.sorted()
        let owner = session.user?.id ?? 0
        Task {
            defer { busy = false }
            do {
                for destination in destinations {
                    guard owner > 0, session.user?.id == owner else { throw APIError.staleSession }
                    let pieces = destination.split(separator: ":"); guard pieces.count == 2, let id = Int(pieces[1]) else { continue }
                    var scope = String(pieces[0]); var targetID = id
                    if scope == "contact" { targetID = try await APIClient.shared.post("chats.create", ["peer_user_id": .number(Decimal(id))])["chat_id"].int; scope = "chat" }
                    guard targetID > 0 else { throw APIError.invalidResponse }
                    for (index, message) in available.enumerated() {
                        let receipt = destination+":"+String(index)
                        guard !accepted.contains(receipt) else { continue }
                        try await Outbox.shared.enqueue(scope: scope, id: targetID, body: MessageContent.forwardBody(message), expectedUserID: owner)
                        accepted.insert(receipt)
                    }
                }
                dismiss()
            } catch { self.error = "未完成的转发可再次发送；已加入发送队列的消息不会重复。"+error.localizedDescription }
        }
    }
}

struct ScheduledMessageView: View {
    let scope: String
    let targetID: Int
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: SessionStore
    @State private var content = ""
    @State private var date = Date().addingTimeInterval(300)
    @State private var rows: [Record] = []
    @State private var requestID = UUID().uuidString.lowercased()
    @State private var error: String?
    @State private var busy = false
    @State private var photo: SPXPhotosPickerItem?
    @State private var mediaURL = ""
    @State private var pending: Record?
    private var recoveryKey: String { "scheduled-pending-\(scope)-\(targetID)" }
    var body: some View {
        SPXNavigationContainer {
            SPXDetailPage {
                SPXPageHeading(title: "定时发送", subtitle: "提前安排消息，按指定时间发送", icon: "clock")
                SPXCard { VStack(alignment: .leading, spacing: 18) {
                    SPXField("消息内容") { TextField("输入要发送的内容", text: $content).lineLimit(8) }
                    SPXPhotosPicker(selection: $photo, matching: .images) { SPXSettingsRow(title: mediaURL.isEmpty ? "添加图片" : "更换图片", icon: "photo") }
                    if !mediaURL.isEmpty { CachedImage(source: mediaURL).frame(maxWidth: .infinity).frame(height: 160).clipShape(RoundedRectangle(cornerRadius: 12)); Button("移除图片", role: .destructive) { mediaURL = "" }.frame(minHeight: 44) }
                    Divider()
                    DatePicker("发送时间", selection: $date, in: Date().addingTimeInterval(30)...Date().addingTimeInterval(90 * 86400)).font(.system(size: 14))
                    Text("可安排未来 30 秒至 90 天内发送").font(.caption).foregroundStyle(SPXColor.muted)
                } }.disabled(busy || pending != nil)
                if pending != nil { ErrorBanner(message: "上次提交结果待确认。重试会使用相同内容与标识，防止重复创建。") }
                if busy { ProgressView("正在处理…").frame(maxWidth: .infinity) }
                SPXSectionTitle(title: "已创建", detail: "\(rows.count) 条")
                ForEach(rows) { row in
                    SPXCard { VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 10) { Image(systemName: "clock").foregroundStyle(SPXColor.primary); Text(row.text("send_at", "status")).font(.system(size: 13)).foregroundStyle(SPXColor.muted); Spacer(minLength: 0) }
                        if !row["content"].string.isEmpty { Text(row["content"].string).font(.system(size: 15)).foregroundStyle(SPXColor.ink) }
                        if !row["media_url"].string.isEmpty { CachedImage(source: row["media_url"].string).frame(height: 100).clipShape(RoundedRectangle(cornerRadius: 10)) }
                        if row["status"].string == "pending" { Divider(); Button("取消发送", role: .destructive) { cancel(row) }.frame(maxWidth: .infinity, minHeight: 44).disabled(busy) }
                    } }
                }
            }.navigationTitle("定时发送")
                .safeAreaInset(edge: .bottom) { SPXBottomAction { Button(busy ? "正在处理…" : pending == nil ? "创建定时消息" : "确认上次提交结果") { save() }.disabled(busy || (pending == nil && content.isEmpty && mediaURL.isEmpty)) } }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) } }
                .task { pending = await LocalStore.shared.read(recoveryKey, userID: session.user?.id ?? 0); await load() }.errorAlert($error)
                .onChange(of: photo) { item in guard let item else { return }; upload(item) }
        }.interactiveDismissDisabled(busy)
    }
    private func load() async {
        do { rows = try await APIClient.shared.get("chat.schedule.list", ["scope": scope, "target_id": String(targetID)]).list }
        catch { self.error = error.localizedDescription }
    }
    private func cancel(_ row: Record) {
        guard !busy else { return }; busy = true
        Task { defer { busy = false }; do { try await APIClient.shared.post("chat.schedule.cancel", ["id": row["id"]]); await load() } catch { self.error = error.localizedDescription } }
    }
    private func save() {
        guard !busy else { return }; busy = true
        let owner = session.user?.id ?? 0
        Task {
            defer { busy = false }
            let body = pending ?? Record(["scope": .string(scope), "target_id": .number(Decimal(targetID)), "content": .string(content), "message_type": .string(mediaURL.isEmpty ? "text" : "image"), "media_url": .string(mediaURL), "send_at_epoch": .number(Decimal(Int(date.timeIntervalSince1970))), "client_message_id": .string(requestID)])
            do {
                try await LocalStore.shared.save(body, key: recoveryKey, userID: owner)
            } catch { self.error = error.localizedDescription; return }
            pending = body
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                let existing = try await APIClient.shared.get("chat.schedule.list", ["scope": scope, "target_id": String(targetID)]).list
                guard session.user?.id == owner else { throw APIError.staleSession }
                if existing.contains(where: { $0["request_id"].string == body["client_message_id"].string }) {
                    await LocalStore.shared.remove(recoveryKey, userID: owner); pending = nil; rows = existing
                    requestID = UUID().uuidString.lowercased(); content = ""; mediaURL = ""; return
                }
                guard body["send_at_epoch"].int >= Int(Date().timeIntervalSince1970) + 30 else {
                    // The API lists only the newest 100 tasks. Absence cannot prove a past task was never created.
                    self.error = "原发送时间已到，列表中未找到该任务。为避免重复发送，已保留恢复记录；请先核对聊天记录和定时任务。"; return
                }
                try await APIClient.shared.post("chat.schedule.create", body.fields)
                await LocalStore.shared.remove(recoveryKey, userID: owner); pending = nil
                requestID = UUID().uuidString.lowercased(); content = ""; mediaURL = ""; photo = nil; await load()
            } catch {
                if (error as? APIError)?.definitiveRejection == true { await LocalStore.shared.remove(recoveryKey, userID: owner); pending = nil; requestID = UUID().uuidString.lowercased() }
                self.error = error.localizedDescription
            }
        }
    }
    private func upload(_ item: SPXPhotosPickerItem) {
        guard !busy, pending == nil else { return }; busy = true
        Task { defer { busy = false; photo = nil }; do { let file = try await MediaFiles.select(item, userID: session.user?.id ?? 0); defer { try? FileManager.default.removeItem(at: file) }; mediaURL = try await MediaUploader.shared.uploadForm(file: file, action: "upload.message") } catch { self.error = error.localizedDescription } }
    }
}

@MainActor final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var coordinate: CLLocationCoordinate2D?
    @Published var error: String?
    private let manager = CLLocationManager()
    override init() { super.init(); manager.delegate = self; manager.desiredAccuracy = kCLLocationAccuracyHundredMeters }
    func request() { manager.requestWhenInUseAuthorization(); if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways { manager.requestLocation() } }
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor [weak self] in
            guard let self else { return }
            if status == .authorizedWhenInUse || status == .authorizedAlways { self.manager.requestLocation() }
            else if status == .denied { self.error = "请在系统设置允许位置权限" }
        }
    }
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let coordinate = locations.last?.coordinate
        Task { @MainActor [weak self] in self?.coordinate = coordinate }
    }
    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let message = error.localizedDescription
        Task { @MainActor [weak self] in self?.error = message }
    }
}

struct SendLocationView: View {
    @EnvironmentObject private var session: SessionStore
    let scope: String
    let targetID: Int
    @Environment(\.dismiss) private var dismiss
    @StateObject private var location = LocationProvider()
    @State private var label = "我的位置"
    @State private var error: String?
    @State private var sending = false
    var body: some View {
        SPXNavigationContainer {
            SPXDetailPage {
                SPXPageHeading(title: "分享当前位置", subtitle: "确认位置名称后发送给当前会话", icon: "location")
                SPXCard { VStack(alignment: .leading, spacing: 16) {
                    SPXField("位置名称") { TextField("输入位置名称", text: $label) }
                    if let coordinate = location.coordinate { Divider(); InfoRow(title: "纬度", value: String(format: "%.6f", coordinate.latitude)); InfoRow(title: "经度", value: String(format: "%.6f", coordinate.longitude)) }
                    else if location.error == nil { ProgressView("正在获取当前位置…").frame(maxWidth: .infinity).padding(.vertical, 20) }
                } }
                if let error = location.error { ErrorBanner(message: error); Button("重新定位") { location.request() }.frame(maxWidth: .infinity, minHeight: 44) }
            }.navigationTitle("发送位置").safeAreaInset(edge: .bottom) {
                SPXBottomAction { Button(sending ? "正在发送…" : "发送位置") {
                    guard !sending, let coordinate = location.coordinate else { return }; sending = true
                    let owner = session.user?.id ?? 0
                    Task { defer { sending = false }; do { let link = "https://maps.apple.com/?ll=\(coordinate.latitude),\(coordinate.longitude)&q=\(label.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")"; try await Outbox.shared.enqueue(scope: scope, id: targetID, body: ["message_type": "text", "content": .string("\(label)\n\(link)")], expectedUserID: owner); dismiss() } catch { self.error = error.localizedDescription } }
                }.disabled(sending || location.coordinate == nil) }
            }.toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(sending) } }.onAppear { location.request() }.errorAlert($error)
        }.interactiveDismissDisabled(sending)
    }
}
