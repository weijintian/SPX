import SwiftUI
import PhotosUI

struct ConversationSettingsView: View {
    let item: Record
    let isGroup: Bool
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var settings = Record()
    @State private var info = Record()
    @State private var name = ""
    @State private var announcement = ""
    @State private var error: String?
    @State private var busy = false
    @State private var destructive: String?
    @State private var sharing = false
    @State private var avatarPicker: PhotosPickerItem?
    @State private var backgroundPicker: PhotosPickerItem?
    @State private var backgroundURL = ""
    @State private var loading = true
    var body: some View {
        SPXDetailPage {
            if loading { ProgressView("正在读取聊天设置…").frame(maxWidth: .infinity) }
            if isGroup {
                SPXCard { VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 14) {
                        Avatar(url: info.avatar, name: info.name, size: 64)
                        VStack(alignment: .leading, spacing: 7) {
                            Text(info.name.isEmpty ? item.name : info.name).font(.system(size: 21, weight: .bold)).foregroundStyle(SPXColor.ink)
                            Text("群聊资料与管理").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                        }
                    }
                    Divider()
                    InfoRow(title: "群号", value: info["group_code"].string)
                    InfoRow(title: "成员", value: info["member_count"].string)
                    NavigationLink { QRCodeView(value: "SPXGROUP:\(info["group_code"].string)", title: info.name) } label: { SPXSettingsRow(title: "群二维码", icon: "qrcode") }
                    Divider()
                    Button { sharing = true } label: { SPXSettingsRow(title: "分享群聊邀请", icon: "square.and.arrow.up") }.disabled(info.id == 0)
                    if info["permissions"]["view_members"].flag {
                        Divider()
                        NavigationLink { GroupMembersView(group: info) } label: { SPXSettingsRow(title: "群成员与管理", icon: "person.3") }
                    }
                } }
                SPXCard { VStack(alignment: .leading, spacing: 18) {
                    SPXSectionTitle(title: "群资料")
                    if info["permissions"]["update_group"].flag {
                        PhotosPicker(selection: $avatarPicker, matching: .images) { SPXSettingsRow(title: "更换群头像", icon: "camera") }
                        SPXField("群名称") { TextField("请输入群名称", text: $name) }
                        SPXField("群公告") { TextField("填写群公告", text: $announcement, axis: .vertical).lineLimit(3...10) }
                        SPXButton(title: busy ? "保存中…" : "保存群资料") { updateInfo() }
                    } else {
                        Text(info["announcement"].string.isEmpty ? "暂无群公告" : info["announcement"].string).font(.system(size: 14)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
                    }
                } }
            } else {
                NavigationLink { UserProfileView(userID: item["peer_user_id"].int) } label: {
                    SPXCard { HStack(spacing: 14) {
                        Avatar(url: item.avatar, name: item.name, size: 60)
                        VStack(alignment: .leading, spacing: 7) { Text(item.name).font(.system(size: 20, weight: .semibold)).foregroundStyle(SPXColor.ink); Text("查看朋友资料").font(.system(size: 12)).foregroundStyle(SPXColor.muted) }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(SPXColor.muted)
                    } }
                }
            }
            SPXCard { VStack(alignment: .leading, spacing: 16) {
                SPXSectionTitle(title: "消息设置")
                setting("消息免打扰", key: "mute")
                Divider()
                setting("置顶聊天", key: "pinned")
                if !isGroup { Divider(); setting("屏蔽该联系人", key: "blocked") }
            } }
            SPXCard { VStack(spacing: 12) {
                NavigationLink { MessageSearchView(targetID: item.id, group: isGroup) } label: { SPXSettingsRow(title: "查找聊天记录", icon: "magnifyingglass") }
                Divider()
                NavigationLink { ScheduledMessageView(scope: isGroup ? "group" : "chat", targetID: item.id) } label: { SPXSettingsRow(title: "定时消息", icon: "clock") }
                Divider()
                NavigationLink { AutoReplyRulesView(scope: isGroup ? "group" : "chat", targetID: item.id) } label: { SPXSettingsRow(title: "自动回复规则", icon: "bubble.left.and.text.bubble.right") }
                Divider()
                PhotosPicker(selection: $backgroundPicker, matching: .images) { SPXSettingsRow(title: "设置聊天背景", icon: "photo") }
                if !backgroundURL.isEmpty {
                    CachedImage(source: backgroundURL).frame(height: 140).clipShape(RoundedRectangle(cornerRadius: 12))
                    Button("恢复默认背景") { saveBackground("") }.frame(minHeight: 44)
                }
            } }
            if isGroup, info["permissions"]["manage_settings"].flag {
                SPXCard { VStack(alignment: .leading, spacing: 16) { SPXSectionTitle(title: "群权限"); setting("允许查看成员资料", key: "allow_member_details"); Divider(); setting("允许成员私聊", key: "allow_private_message") } }
            }
            SPXCard { VStack(spacing: 12) {
                if isGroup {
                    Button("退出群聊", role: .destructive) { destructive = "groups.leave" }.frame(maxWidth: .infinity, minHeight: 44)
                    if info["permissions"]["dissolve_group"].flag { Divider(); Button("解散群聊", role: .destructive) { destructive = "groups.dissolve" }.frame(maxWidth: .infinity, minHeight: 44) }
                } else {
                    Button("清空本机聊天记录", role: .destructive) { destructive = "messages.clear" }.frame(maxWidth: .infinity, minHeight: 44)
                    Divider()
                    Button("清空双方聊天记录", role: .destructive) { destructive = "messages.clear.both" }.frame(maxWidth: .infinity, minHeight: 44)
                }
            } }
        }.buttonStyle(.plain).navigationTitle(isGroup ? "群聊详情" : "聊天详情").disabled(busy || loading).task { await load() }.errorAlert($error)
            .sheet(isPresented: $sharing) { ForwardMessageView(message: Record(["message_type": "text", "share_group_id": info["id"], "content": .string("SPXGROUP:\(info["group_code"].string)")])) }
            .onChange(of: avatarPicker) { _, item in if let item { upload(item, avatar: true) } }
            .onChange(of: backgroundPicker) { _, item in if let item { upload(item, avatar: false) } }
            .confirmationDialog("确认执行？此操作可能无法撤销", isPresented: Binding(get: { destructive != nil }, set: { if !$0 { destructive = nil } }), titleVisibility: .visible) {
                Button("确认", role: .destructive) { if let action = destructive { mutate(action); destructive = nil } }
            }
    }
    private func setting(_ title: String, key: String) -> some View {
        Toggle(title, isOn: Binding(get: { settings[key].flag }, set: { value in
            let old = settings[key]; settings[key] = .bool(value); busy = true
            Task { defer { busy = false }; do { try await APIClient.shared.post(isGroup ? "groups.settings" : "chats.settings", [isGroup ? "group_id" : "chat_id": .number(Decimal(item.id)), key: .bool(value)]); NotificationCenter.default.post(name: .spxConversationUpdated, object: nil) } catch { settings[key] = old; self.error = error.localizedDescription } }
        }))
    }
    private func load() async {
        loading = true; defer { loading = false }
        do {
            if isGroup {
                async let preferences = APIClient.shared.get("groups.settings", ["group_id": String(item.id)])
                async let details = APIClient.shared.get("groups.info", ["group_id": String(item.id)])
                let (saved, group) = try await (preferences, details)
                settings = saved.record; info = group.record; name = info.name; announcement = info["announcement"].string
                backgroundURL = await LocalStore.shared.read("chat-background-g-\(item.id)", userID: session.user?.id ?? 0) ?? ""
            } else {
                settings = try await APIClient.shared.get("chats.settings.get", ["chat_id": String(item.id)]).record
                backgroundURL = settings["background_url"].string
            }
            error = nil
        } catch { self.error = error.localizedDescription }
    }
    private func updateInfo() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("groups.update", ["group_id": .number(Decimal(item.id)), "name": .string(name), "announcement": .string(announcement)]); await load(); NotificationCenter.default.post(name: .spxConversationUpdated, object: nil) } catch { self.error = error.localizedDescription } } }
    private func upload(_ selected: PhotosPickerItem, avatar: Bool) {
        guard !busy else { return }; busy = true; let owner = session.user?.id ?? 0
        Task {
            defer { busy = false; avatarPicker = nil; backgroundPicker = nil }
            do {
                let file = try await MediaFiles.select(selected, userID: owner); defer { try? FileManager.default.removeItem(at: file) }
                let url = try await MediaUploader.shared.uploadForm(file: file, action: "upload.message")
                if avatar { try await APIClient.shared.post("groups.update", ["group_id": .number(Decimal(item.id)), "avatar_url": .string(url)]); await load() }
                else { try await persistBackground(url, owner: owner) }
                NotificationCenter.default.post(name: .spxConversationUpdated, object: nil)
            } catch { self.error = error.localizedDescription }
        }
    }
    private func saveBackground(_ url: String) {
        guard !busy else { return }; busy = true; let owner = session.user?.id ?? 0
        Task { defer { busy = false }; do { try await persistBackground(url, owner: owner); NotificationCenter.default.post(name: .spxConversationUpdated, object: nil) } catch { self.error = error.localizedDescription } }
    }
    private func persistBackground(_ url: String, owner: Int) async throws {
        if !isGroup { try await APIClient.shared.post("chats.settings", ["chat_id": .number(Decimal(item.id)), "background_url": .string(url)]) }
        try await LocalStore.shared.save(url, key: "chat-background-\(isGroup ? "g" : "c")-\(item.id)", userID: owner); backgroundURL = url
    }
    private func mutate(_ action: String) {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await APIClient.shared.post(action, [isGroup ? "group_id" : "chat_id": .number(Decimal(item.id))])
                await LocalStore.shared.remove("messages-\(isGroup ? "g" : "c")-\(item.id)", userID: session.user?.id ?? 0)
                await LocalStore.shared.remove("messages-\(isGroup ? "g" : "c")-\(item.id)-sync-v2", userID: session.user?.id ?? 0)
                let context = Record(["target": .number(Decimal(item.id)), "group": .bool(isGroup)])
                NotificationCenter.default.post(name: isGroup ? .spxConversationRemoved : .spxConversationCleared, object: context)
                dismiss()
            } catch { self.error = error.localizedDescription }
        }
    }
}

struct GroupMembersView: View {
    let group: Record
    @State private var info = Record()
    @State private var rows: [Record] = []
    @State private var contacts: [Record] = []
    @State private var adding = false
    @State private var loading = true
    @State private var busy = false
    @State private var error: String?
    @State private var transfer: Record?
    @State private var remove: Record?
    private var current: Record { info.id > 0 ? info : group }
    private var canManage: Bool { current["permissions"]["manage_settings"].flag }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                SPXSectionTitle(title: "群成员", detail: "\(rows.count) 人")
                if loading && rows.isEmpty { ProgressView().frame(maxWidth: .infinity) }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 60), spacing: 10)], alignment: .leading, spacing: 20) {
                    ForEach(rows, id: \.fields) { member in
                        VStack(spacing: 7) {
                            NavigationLink { UserProfileView(userID: userID(member)) } label: {
                                VStack(spacing: 7) { Avatar(url: member.avatar, name: member.name, size: 48); Text(member.name).font(.system(size: 12)).foregroundStyle(SPXColor.ink).lineLimit(1) }
                            }.buttonStyle(.plain)
                            if canManage && member["role"].string != "owner" {
                                Menu {
                                    if current["role"].string == "owner" {
                                        Button(member["role"].string == "admin" ? "取消管理员" : "设为管理员") { act("groups.members.role", member: member, extra: ["role": .string(member["role"].string == "admin" ? "member" : "admin")]) }
                                        Button("转让群主") { transfer = member }
                                    }
                                    Button(member["mute"].flag ? "解除禁言" : "禁言") { act("groups.members.mute", member: member, extra: ["mute": .bool(!member["mute"].flag)]) }
                                    Button("移出群聊", role: .destructive) { remove = member }
                                } label: { Label(member["role"].string == "admin" ? "管理员" : "管理", systemImage: "ellipsis").font(.system(size: 10)).foregroundStyle(SPXColor.muted) }
                            } else { Text(member["role"].string == "owner" ? "群主" : member["role"].string == "admin" ? "管理员" : "成员").font(.system(size: 10)).foregroundStyle(SPXColor.muted) }
                        }.frame(maxWidth: .infinity).accessibilityElement(children: .contain)
                    }
                    if current["permissions"]["invite_members"].flag {
                        Button { adding = true } label: { VStack(spacing: 7) { SPXIcon("plus").frame(width: 23,height: 23).frame(width: 48,height: 48).background(SPXColor.surface,in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(SPXColor.line)); Text("邀请好友").font(.system(size: 12)); Text(" ").font(.system(size: 10)) }.foregroundStyle(SPXColor.primary).frame(maxWidth: .infinity) }
                    }
                }.padding(16).background(SPXColor.card,in: RoundedRectangle(cornerRadius: 20))
                if !loading && rows.isEmpty { EmptyState(title: "暂未读取到成员", description: "请下拉刷新或稍后重试") }
            }.padding(16)
        }.navigationTitle("群成员").spxPage().task { await load() }.refreshable { await load() }.disabled(busy).errorAlert($error)
            .sheet(isPresented: $adding) {
                NavigationStack { SPXDetailPage {
                    SPXPageHeading(title: "邀请好友", subtitle: "选择朋友加入当前群聊", icon: "person.badge.plus")
                    if contacts.isEmpty { EmptyState(title: "暂无可邀请的好友") }
                    ForEach(contacts, id: \.fields) { contact in
                        SPXCard { Button { act("groups.members.add", member: contact, extra: ["user_ids": .array([contact["contact_user_id"].int > 0 ? contact["contact_user_id"] : contact["id"]])]) } label: { HStack(spacing: 12) { Avatar(url: contact.avatar,name: contact.name); Text(contact.name).foregroundStyle(SPXColor.ink); Spacer(); SPXIcon("plus").frame(width: 20,height: 20) }.frame(minHeight: 48).contentShape(Rectangle()) }.buttonStyle(.plain).disabled(busy) }
                    }
                }.navigationTitle("邀请好友").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { adding = false }.disabled(busy) } } }.interactiveDismissDisabled(busy)
            }
            .confirmationDialog("转让群主给 \(transfer?.name ?? "")？", isPresented: Binding(get: { transfer != nil },set: { if !$0 { transfer = nil } }), titleVisibility: .visible) {
                Button("确认转让",role: .destructive) { if let member = transfer { act("groups.transfer",member: member,extra: ["owner_user_id": .number(Decimal(userID(member)))]) }; transfer = nil }
            }
            .confirmationDialog("将 \(remove?.name ?? "") 移出群聊？", isPresented: Binding(get: { remove != nil },set: { if !$0 { remove = nil } }), titleVisibility: .visible) {
                Button("移出群聊",role: .destructive) { if let member = remove { act("groups.members.remove",member: member) }; remove = nil }
            }
    }
    private func userID(_ member: Record) -> Int { member["user_id"].int > 0 ? member["user_id"].int : member.id }
    private func load() async {
        loading = true; defer { loading = false }
        do {
            async let members = APIClient.shared.get("groups.members", ["group_id": group["id"].string])
            async let details = APIClient.shared.get("groups.info", ["group_id": group["id"].string])
            let (list, data) = try await (members, details); rows = list.list; info = data.record
            if current["permissions"]["invite_members"].flag { contacts = try await APIClient.shared.get("contacts").list }
            error = nil
        } catch { self.error = error.localizedDescription }
    }
    private func act(_ action: String, member: Record, extra: [String: JSON] = [:]) {
        guard !busy else { return }; busy = true
        var body = extra; body["group_id"] = group["id"]; body["user_id"] = .number(Decimal(userID(member)))
        Task { defer { busy = false }; do { try await APIClient.shared.post(action,body); adding = false; await load(); NotificationCenter.default.post(name: .spxConversationUpdated, object: nil) } catch { self.error = error.localizedDescription } }
    }
}

struct MessageSearchView: View {
    let targetID: Int
    let group: Bool
    @State private var query = ""
    @State private var rows: [Record] = []
    @State private var error: String?
    @State private var loading = false
    @State private var completedQuery: String?
    var body: some View {
        SPXDetailPage {
            if query.isEmpty { EmptyState(title: "查找聊天记录", description: "输入关键词，点击结果查看消息上下文", icon: "magnifyingglass") }
            if loading { ProgressView("正在查找消息…").frame(maxWidth: .infinity).padding(20) }
            if !loading && completedQuery == query && !query.isEmpty && rows.isEmpty && error == nil { EmptyState(title: "没有找到相关消息", description: "试试其他关键词", icon: "magnifyingglass") }
            ForEach(rows) { row in
                NavigationLink { MessageContextView(targetID: targetID, group: group, messageID: row.id) } label: {
                    SPXCard { HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "text.bubble").font(.system(size: 20)).foregroundStyle(SPXColor.primary).frame(width: 40, height: 40).background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 12))
                        VStack(alignment: .leading, spacing: 9) {
                            Text(MessageContent.preview(row)).font(.system(size: 15)).foregroundStyle(SPXColor.ink).lineLimit(3)
                            Text(MessageTimeline.label(row["created_at"].string)).font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(SPXColor.muted).padding(.top, 12)
                    } }
                }.buttonStyle(.plain)
            }
        }
            .navigationTitle("查找聊天记录").searchable(text: $query)
            .task(id: query) {
                let requestedQuery = query
                rows = []; completedQuery = nil; error = nil
                guard !requestedQuery.isEmpty else { loading = false; return }
                loading = true
                defer { if query == requestedQuery { loading = false } }
                do {
                    try await Task.sleep(for: .milliseconds(350))
                    let results = try await APIClient.shared.get("messages.search", [group ? "group_id" : "chat_id": String(targetID), "q": requestedQuery]).list
                    try Task.checkCancellation()
                    guard query == requestedQuery else { return }
                    rows = results; completedQuery = requestedQuery
                } catch is CancellationError { }
                catch { if query == requestedQuery && !Task.isCancelled { self.error = error.localizedDescription } }
            }.errorAlert($error)
    }
}

struct AutoReplyRulesView: View {
    let scope: String
    let targetID: Int
    @State private var rows: [Record] = []
    @State private var editingID = 0
    @State private var keyword = ""
    @State private var reply = ""
    @State private var mode = "contains"
    @State private var cooldown = 60
    @State private var enabled = true
    @State private var error: String?
    @State private var busy = false
    private var groupID: Int { scope == "group" ? targetID : 0 }
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "自动回复", subtitle: groupID > 0 ? "设置关键词、回复内容与触发间隔" : "为朋友的私聊设置自动回复", icon: "bubble.left.and.text.bubble.right")
            SPXCard { VStack(alignment: .leading, spacing: 18) {
                SPXSectionTitle(title: editingID > 0 ? "编辑规则" : "新增规则")
                if groupID > 0 {
                    SPXField("匹配关键词") { TextField("最多 100 字", text: $keyword) }
                    SPXField("匹配方式") { Picker("匹配方式", selection: $mode) { Text("包含关键词").tag("contains"); Text("完全一致").tag("exact") }.pickerStyle(.segmented) }
                } else { Text("朋友发来消息后，按设置的间隔自动回复。个人规则对所有私聊生效。").font(.footnote).foregroundStyle(.secondary) }
                SPXField("回复内容") { TextField("填写自动回复消息", text: $reply, axis: .vertical).lineLimit(3...8) }
                SPXField("回复间隔") { Picker("回复间隔", selection: $cooldown) { ForEach([30, 60, 300, 600, 1800, 3600, 86400], id: \.self) { value in Text(value < 60 ? "30 秒" : value < 3600 ? String(value / 60)+" 分钟" : String(value / 3600)+" 小时").tag(value) }; if ![30, 60, 300, 600, 1800, 3600, 86400].contains(cooldown) { Text(String(cooldown)+" 秒").tag(cooldown) } }.frame(maxWidth: .infinity, alignment: .leading) }
                Toggle("启用规则", isOn: $enabled)
                if editingID > 0 { Button("取消编辑") { reset() }.frame(minHeight: 44) }
            } }
            SPXSectionTitle(title: "已有规则", detail: "\(rows.count) 条")
                ForEach(rows) { row in
                    SPXCard { VStack(alignment: .leading, spacing: 14) {
                        HStack { Text(groupID > 0 ? row["keyword"].string : "私聊自动回复").font(.headline); Spacer(); SPXStatusPill(title: row["enabled"].flag ? "已开启" : "已关闭", color: row["enabled"].flag ? SPXColor.success : SPXColor.muted) }
                        Text(row["reply_text"].string).font(.subheadline).lineLimit(3)
                        Divider()
                        HStack { Button("编辑") { editingID = row.id; keyword = row["keyword"].string; reply = row["reply_text"].string; mode = row["match_mode"].string; cooldown = row["cooldown_seconds"].int; enabled = row["enabled"].flag }; Spacer(); Button("删除", role: .destructive) { remove(row) } }.font(.subheadline).frame(minHeight: 44)
                    } }
                }
                if rows.isEmpty { EmptyState(title: "还没有回复规则", description: "填写上方内容后保存", icon: "text.bubble") }
        }.navigationTitle("自动回复")
            .safeAreaInset(edge: .bottom) { SPXBottomAction { Button(busy ? "保存中…" : "保存规则") { save() }.disabled(busy || reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (groupID > 0 && keyword.isEmpty)) } }
            .disabled(busy).task { await load() }.errorAlert($error)
    }
    private func reset() { editingID = 0; keyword = ""; reply = ""; mode = "contains"; cooldown = 60; enabled = true }
    private func load() async { do { rows = try await APIClient.shared.get("chat.rules.list", ["group_id": String(groupID)]).list } catch { self.error = error.localizedDescription } }
    private func save() {
        guard !busy else { return }; busy = true
        Task { defer { busy = false }; do { try await APIClient.shared.post("chat.rules.save", ["id": .number(Decimal(editingID)), "group_id": .number(Decimal(groupID)), "keyword": .string(keyword), "reply_text": .string(reply), "match_mode": .string(mode), "cooldown_seconds": .number(Decimal(cooldown)), "enabled": .bool(enabled)]); reset(); await load() } catch { self.error = error.localizedDescription } }
    }
    private func remove(_ row: Record) {
        guard !busy else { return }; busy = true
        Task { defer { busy = false }; do { try await APIClient.shared.post("chat.rules.delete", ["id": row["id"], "group_id": .number(Decimal(groupID))]); if row.id == editingID { reset() }; await load() } catch { self.error = error.localizedDescription } }
    }
}
