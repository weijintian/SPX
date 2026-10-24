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
    var body: some View {
        Form {
            if isGroup {
                Section("群资料") {
                    InfoRow(title: "群号", value: info["group_code"].string)
                    NavigationLink("群二维码") { QRCodeView(value: "SPXGROUP:\(info["group_code"].string)", title: info.name) }
                    Button("分享群聊邀请", systemImage: "square.and.arrow.up") { sharing = true }.disabled(info.id == 0)
                    InfoRow(title: "成员", value: info["member_count"].string)
                    if info["permissions"]["update_group"].flag {
                        HStack { Avatar(url: info.avatar, name: info.name, size: 56); Spacer(); PhotosPicker(selection: $avatarPicker, matching: .images) { Label("更换群头像", systemImage: "camera") } }
                        TextField("群名称", text: $name)
                        TextField("群公告", text: $announcement, axis: .vertical).lineLimit(3...10)
                        Button("保存群资料") { updateInfo() }
                    } else { Text(info["announcement"].string.isEmpty ? "暂无群公告" : info["announcement"].string) }
                    if info["permissions"]["view_members"].flag { NavigationLink("群成员与管理") { GroupMembersView(group: info) } }
                }
            } else {
                Section { NavigationLink { UserProfileView(userID: item["peer_user_id"].int) } label: { HStack { Avatar(url: item.avatar, name: item.name); Text(item.name) } } }
            }
            Section("聊天设置") {
                setting("消息免打扰", key: "mute")
                setting("置顶聊天", key: "pinned")
                if !isGroup { setting("屏蔽该联系人", key: "blocked") }
                NavigationLink("查找聊天记录") { MessageSearchView(targetID: item.id, group: isGroup) }
                NavigationLink("定时消息") { ScheduledMessageView(scope: isGroup ? "group" : "chat", targetID: item.id) }
                NavigationLink("自动回复规则") { AutoReplyRulesView(scope: isGroup ? "group" : "chat", targetID: item.id) }
                PhotosPicker(selection: $backgroundPicker, matching: .images) { Label("设置聊天背景", systemImage: "photo") }
                if !backgroundURL.isEmpty { CachedImage(source: backgroundURL).frame(height: 130); Button("恢复默认背景") { saveBackground("") } }
            }
            if isGroup, info["permissions"]["manage_settings"].flag {
                Section("群权限") { setting("允许查看成员资料", key: "allow_member_details"); setting("允许成员私聊", key: "allow_private_message") }
            }
            Section {
                if isGroup {
                    Button("退出群聊", role: .destructive) { destructive = "groups.leave" }
                    if info["permissions"]["dissolve_group"].flag { Button("解散群聊", role: .destructive) { destructive = "groups.dissolve" } }
                } else {
                    Button("清空本机聊天记录", role: .destructive) { destructive = "messages.clear" }
                    Button("清空双方聊天记录", role: .destructive) { destructive = "messages.clear.both" }
                }
            }
        }.navigationTitle(isGroup ? "群聊详情" : "聊天详情").disabled(busy).task { await load() }.errorAlert($error)
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
        do {
            settings = try await APIClient.shared.get(isGroup ? "groups.settings" : "chats.settings.get", [isGroup ? "group_id" : "chat_id": String(item.id)]).record
            if isGroup { backgroundURL = await LocalStore.shared.read("chat-background-g-\(item.id)", userID: session.user?.id ?? 0) ?? "" } else { backgroundURL = settings["background_url"].string }
            if isGroup { info = try await APIClient.shared.get("groups.info", ["group_id": String(item.id)]).record; name = info.name; announcement = info["announcement"].string }
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
    @State private var rows: [Record] = []
    @State private var contacts: [Record] = []
    @State private var adding = false
    @State private var error: String?
    @State private var transfer: Record?
    var body: some View {
        List {
            if group["permissions"]["invite_members"].flag { Button("邀请好友加入", systemImage: "person.badge.plus") { adding = true } }
            ForEach(rows, id: \.fields) { member in
                HStack {
                    NavigationLink { UserProfileView(userID: member["user_id"].int > 0 ? member["user_id"].int : member.id) } label: {
                        HStack {
                            Avatar(url: member.avatar, name: member.name)
                            VStack(alignment: .leading) { Text(member.name); Text(member["role"].string == "owner" ? "群主" : member["role"].string == "admin" ? "管理员" : "成员").font(.caption).foregroundStyle(.secondary) }
                        }
                    }.buttonStyle(.plain)
                    Spacer()
                    if group["permissions"]["manage_settings"].flag, member["role"].string != "owner" {
                        Menu {
                            if group["role"].string == "owner" { Button(member["role"].string == "admin" ? "取消管理员" : "设为管理员") { act("groups.members.role", member: member, extra: ["role": .string(member["role"].string == "admin" ? "member" : "admin")]) }; Button("转让群主") { transfer = member } }
                            Button(member["mute"].flag ? "解除禁言" : "禁言") { act("groups.members.mute", member: member, extra: ["mute": .bool(!member["mute"].flag)]) }
                            Button("移出群组", role: .destructive) { act("groups.members.remove", member: member) }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            }
        }.navigationTitle("群成员").task { await load() }.errorAlert($error)
            .sheet(isPresented: $adding) {
                NavigationStack { List(contacts, id: \.fields) { contact in Button(contact.name) { let id = contact["contact_user_id"].int > 0 ? contact["contact_user_id"] : contact["id"]; Task { do { try await APIClient.shared.post("groups.members.add", ["group_id": group["id"], "user_ids": .array([id])]); adding = false; await load() } catch { self.error = error.localizedDescription } } } }.navigationTitle("选择好友").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { adding = false } } } }
            }
            .confirmationDialog("转让群主给 \(transfer?.name ?? "")？", isPresented: Binding(get: { transfer != nil }, set: { if !$0 { transfer = nil } }), titleVisibility: .visible) {
                Button("确认转让", role: .destructive) { if let member = transfer { Task { do { try await APIClient.shared.post("groups.transfer", ["group_id": group["id"], "owner_user_id": member["user_id"].int > 0 ? member["user_id"] : member["id"]]); await load() } catch { self.error = error.localizedDescription } }; transfer = nil } }
            }
    }
    private func load() async { do { rows = try await APIClient.shared.get("groups.members", ["group_id": group["id"].string]).list; if group["permissions"]["invite_members"].flag { contacts = try await APIClient.shared.get("contacts").list } } catch { self.error = error.localizedDescription } }
    private func act(_ action: String, member: Record, extra: [String: JSON] = [:]) {
        var body = extra; body["group_id"] = group["id"]; body["user_id"] = member["user_id"].int > 0 ? member["user_id"] : member["id"]
        Task { do { try await APIClient.shared.post(action, body); await load() } catch { self.error = error.localizedDescription } }
    }
}

struct MessageSearchView: View {
    let targetID: Int
    let group: Bool
    @State private var query = ""
    @State private var rows: [Record] = []
    @State private var error: String?
    var body: some View {
        List(rows) { row in NavigationLink { MessageContextView(targetID: targetID, group: group, messageID: row.id) } label: { VStack(alignment: .leading, spacing: 8) { Text(MessageContent.preview(row)).lineLimit(3); Text(MessageTimeline.label(row["created_at"].string)).font(.caption).foregroundStyle(.secondary) } } }
            .navigationTitle("查找聊天记录").searchable(text: $query)
            .task(id: query) { guard !query.isEmpty else { rows = []; return }; do { try await Task.sleep(for: .milliseconds(350)); rows = try await APIClient.shared.get("messages.search", [group ? "group_id" : "chat_id": String(targetID), "q": query]).list } catch is CancellationError { } catch { self.error = error.localizedDescription } }.errorAlert($error)
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
        Form {
            Section(editingID > 0 ? "编辑规则" : "新增规则") {
                if groupID > 0 {
                    TextField("匹配关键词（最多 100 字）", text: $keyword)
                    Picker("匹配方式", selection: $mode) { Text("包含关键词").tag("contains"); Text("完全一致").tag("exact") }
                } else { Text("朋友发来消息后，按设置的间隔自动回复。个人规则对所有私聊生效。").font(.footnote).foregroundStyle(.secondary) }
                TextField("回复内容", text: $reply, axis: .vertical).lineLimit(3...8)
                Picker("回复间隔", selection: $cooldown) { ForEach([30, 60, 300, 600, 1800, 3600, 86400], id: \.self) { value in Text(value < 60 ? "30 秒" : value < 3600 ? String(value / 60)+" 分钟" : String(value / 3600)+" 小时").tag(value) }; if ![30, 60, 300, 600, 1800, 3600, 86400].contains(cooldown) { Text(String(cooldown)+" 秒").tag(cooldown) } }
                Toggle("启用规则", isOn: $enabled)
                Button(busy ? "保存中…" : "保存规则") { save() }.disabled(busy || reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (groupID > 0 && keyword.isEmpty))
                if editingID > 0 { Button("取消编辑") { reset() } }
            }
            Section("已有规则") {
                ForEach(rows) { row in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack { Text(groupID > 0 ? row["keyword"].string : "私聊自动回复").font(.headline); Spacer(); Text(row["enabled"].flag ? "已开启" : "已关闭").font(.caption).foregroundStyle(row["enabled"].flag ? .green : .secondary) }
                        Text(row["reply_text"].string).font(.subheadline).lineLimit(3)
                        HStack { Button("编辑") { editingID = row.id; keyword = row["keyword"].string; reply = row["reply_text"].string; mode = row["match_mode"].string; cooldown = row["cooldown_seconds"].int; enabled = row["enabled"].flag }; Spacer(); Button("删除", role: .destructive) { remove(row) } }.font(.subheadline)
                    }.padding(.vertical, 5)
                }
                if rows.isEmpty { Text("还没有回复规则").foregroundStyle(.secondary) }
            }
        }.navigationTitle("自动回复").disabled(busy).task { await load() }.errorAlert($error)
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
