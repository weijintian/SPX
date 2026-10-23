import SwiftUI
import CoreLocation
import PhotosUI

struct ContactsView: View {
    @State private var rows: [Record] = []
    @State private var search = ""
    @State private var error: String?
    var body: some View {
        List {
            NavigationLink { AddContactView() } label: { Label("添加朋友", systemImage: "person.badge.plus") }
            ForEach(rows.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }) { item in
                NavigationLink { UserProfileView(userID: item["contact_user_id"].int > 0 ? item["contact_user_id"].int : item.id) } label: {
                    HStack { Avatar(url: item.avatar, name: item.name); VStack(alignment: .leading) { Text(item.name); Text(item["spx_id"].string).font(.caption).foregroundStyle(.secondary) } }
                }
            }
        }.navigationTitle("通讯录").searchable(text: $search).task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    private func load() async { do { rows = try await APIClient.shared.get("contacts").list } catch { self.error = error.localizedDescription } }
}

struct AddContactView: View {
    @State private var lookup = ""
    @State private var rows: [Record] = []
    @State private var error: String?
    @State private var busy = false
    var body: some View {
        List {
            Section { TextField("SPX ID 或邮箱", text: $lookup).textInputAutocapitalization(.never).autocorrectionDisabled(); Button("搜索") { search() }.disabled(busy || lookup.isEmpty) }
            ForEach(rows) { item in NavigationLink { UserProfileView(userID: item.id) } label: { HStack { Avatar(url: item.avatar, name: item.name); Text(item.name); Spacer(); Text(item["spx_id"].string).foregroundStyle(.secondary) } } }
            Section { Button("添加该 SPX ID / 邮箱") { add() }.disabled(lookup.isEmpty || busy) }
        }.navigationTitle("添加朋友").errorAlert($error)
    }
    private func search() { busy = true; Task { defer { busy = false }; do { rows = try await APIClient.shared.get("contacts.search", ["q": lookup]).list } catch { self.error = error.localizedDescription } } }
    private func add() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("contacts.add", [lookup.contains("@") ? "email" : "spx_id": .string(lookup)]); error = "好友已添加" } catch { self.error = error.localizedDescription } } }
}

struct UserProfileView: View {
    let userID: Int
    @EnvironmentObject private var session: SessionStore
    @State private var profile = Record()
    @State private var chat: Record?
    @State private var remark = ""
    @State private var error: String?
    @State private var delete = false
    @State private var busy = false
    var body: some View {
        List {
            Section { HStack(spacing: 16) { Avatar(url: profile.avatar, name: profile.name, size: 64); VStack(alignment: .leading, spacing: 6) { Text(profile.name).font(.title2.bold()); Text(profile["spx_id"].string).foregroundStyle(.secondary) } }.padding(.vertical, 12) }
            if profile["is_contact"].flag { Section {
                Button { openChat() } label: { Label("发消息", systemImage: "bubble.left.and.bubble.right") }
                HStack(spacing: 12) {
                    callButton("语音通话", "phone", video: false)
                    callButton("视频通话", "video", video: true)
                }.padding(.vertical, 8)
            } } else { Section { Button("添加朋友", systemImage: "person.badge.plus") { addContact() }.disabled(busy || profile.id == 0) } }
            if profile["is_contact"].flag {
                Section("备注") { TextField("联系人备注", text: $remark); Button("保存备注") { updateRemark() } }
                Section { Button("删除好友及双方聊天记录", role: .destructive) { delete = true } }
            }
        }.navigationTitle("个人资料").navigationBarTitleDisplayMode(.inline).disabled(busy)
            .navigationDestination(item: $chat) { ChatDetailView(item: $0, isGroup: false) }
            .task { do { profile = try await APIClient.shared.get("user.profile", ["id": String(userID)]).record; remark = profile["contact_remark"].string } catch { self.error = error.localizedDescription } }
            .confirmationDialog("删除好友将清除双方聊天记录", isPresented: $delete, titleVisibility: .visible) { Button("确认删除", role: .destructive) { Task { do { try await APIClient.shared.post("contacts.delete", ["contact_user_id": .number(Decimal(userID))]); error = "好友已删除" } catch { self.error = error.localizedDescription } } } }
            .errorAlert($error)
    }
    private func callButton(_ title: String, _ icon: String, video: Bool) -> some View {
        Button { Task { do { let item = try await directChat(); CallCoordinator.shared.dial(chat: item, video: video) } catch { self.error = error.localizedDescription } } } label: {
            VStack(spacing: 10) { Image(systemName: icon).font(.system(size: 25, weight: .medium)); Text(title).font(.subheadline.weight(.medium)) }.frame(maxWidth: .infinity).padding(18).foregroundStyle(SPXColor.primary).background(SPXColor.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
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
                error = "备注已保存"
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
    var body: some View {
        List {
            Section { TextField("群名称", text: $name) }
            Section("选择朋友") {
                ForEach(contacts) { item in
                    let id = item["contact_user_id"].int > 0 ? item["contact_user_id"].int : item.id
                    Button { if selected.contains(id) { selected.remove(id) } else { selected.insert(id) } } label: { HStack { Avatar(url: item.avatar, name: item.name); Text(item.name).foregroundStyle(.primary); Spacer(); Image(systemName: selected.contains(id) ? "checkmark.circle.fill" : "circle").foregroundStyle(SPXColor.primary) } }
                }
            }
            Section { Button("创建群聊") { create() }.disabled(busy || selected.isEmpty || name.trimmingCharacters(in: .whitespaces).isEmpty) }
        }.navigationTitle("创建群聊").task { do { contacts = try await APIClient.shared.get("contacts").list } catch { self.error = error.localizedDescription } }
            .navigationDestination(item: $chat) { ChatDetailView(item: $0, isGroup: true) }.errorAlert($error)
    }
    private func create() { busy = true; Task { defer { busy = false }; do { let result = try await APIClient.shared.post("groups.create", ["name": .string(name), "member_ids": .array(selected.sorted().map { .number(Decimal($0)) })]); chat = Record(["id": result["group_id"], "name": .string(name), "member_count": result["member_count"]]) } catch { self.error = error.localizedDescription } } }
}

struct GroupJoinView: View {
    var initialCode = ""
    @State private var code = ""
    @State private var result: Record?
    @State private var chat: Record?
    @State private var error: String?
    var body: some View {
        Form {
            Section { TextField("六位群号", text: $code).textInputAutocapitalization(.characters); Button("查找群组") { Task { do { result = try await APIClient.shared.get("groups.search", ["group_code": code.uppercased()]).record } catch { self.error = error.localizedDescription } } }.disabled(code.count != 6) }
            if let result { Section { HStack { Avatar(url: result.avatar, name: result.name); Text(result.name) }; Text(result["announcement"].string); Button("加入群组") { Task { do { let response = try await APIClient.shared.post("groups.join", ["group_code": .string(code.uppercased())]); var item = result; item["id"] = response["group_id"].int > 0 ? response["group_id"] : result["id"]; chat = item } catch { self.error = error.localizedDescription } } } } }
        }.navigationTitle("加入群组").navigationDestination(item: $chat) { ChatDetailView(item: $0, isGroup: true) }.onAppear { if code.isEmpty { code = initialCode } }.errorAlert($error)
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
    private var available: [Record] { messages.filter(MessageContent.canForward) }
    var body: some View {
        NavigationStack {
            List {
                Section("最近会话") { ForEach(chats.filter(matches)) { target($0, scope: "chat") } }
                Section("通讯录") { ForEach(contacts.filter(matches)) { target($0, scope: "contact") } }
                Section("群组") { ForEach(groups.filter(matches)) { target($0, scope: "group") } }
            }.navigationTitle("转发 "+String(available.count)+" 条消息").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $query, prompt: "搜索朋友或群聊").disabled(busy)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) }
                    ToolbarItem(placement: .confirmationAction) { Button(busy ? "发送中…" : "发送 ("+String(selection.count)+")") { confirm = true }.disabled(busy || selection.isEmpty || available.isEmpty) }
                }
                .confirmationDialog("按原顺序逐条转发给所选会话？", isPresented: $confirm, titleVisibility: .visible) { Button("确认发送") { send() } }
                .task { await load() }.errorAlert($error)
        }.interactiveDismissDisabled(busy)
    }
    private func matches(_ item: Record) -> Bool { query.isEmpty || item.name.localizedCaseInsensitiveContains(query) }
    private func target(_ item: Record, scope: String) -> some View {
        let key = scope+":"+String(item.id)
        return Button { if selection.contains(key) { selection.remove(key) } else { selection.insert(key) } } label: {
            HStack(spacing: 12) { Avatar(url: item.avatar, name: item.name, size: 42); Text(item.name).foregroundStyle(.primary); Spacer(); Image(systemName: selection.contains(key) ? "checkmark.circle.fill" : "circle").foregroundStyle(SPXColor.primary) }.padding(.vertical, 3)
        }.buttonStyle(.plain)
    }
    private func load() async {
        do {
            async let c = APIClient.shared.get("chats"); async let g = APIClient.shared.get("groups"); async let f = APIClient.shared.get("contacts")
            let (a,b,d) = try await (c,g,f); chats = a.list; groups = b.list
            let existing = Set(chats.map { $0["peer_user_id"].int })
            contacts = d.list.compactMap { contact in
                var value = contact; let id = contact["contact_user_id"].int > 0 ? contact["contact_user_id"].int : contact.id
                guard !existing.contains(id) else { return nil }; value["id"] = .number(Decimal(id)); return value
            }
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
    @State private var photo: PhotosPickerItem?
    @State private var mediaURL = ""
    @State private var pending: Record?
    private var recoveryKey: String { "scheduled-pending-\(scope)-\(targetID)" }
    var body: some View {
        NavigationStack {
            Form {
                Section("定时消息") {
                    TextField("消息内容", text: $content, axis: .vertical).lineLimit(3...8)
                    PhotosPicker(selection: $photo, matching: .images) { Label(mediaURL.isEmpty ? "添加图片" : "更换图片", systemImage: "photo") }
                    if !mediaURL.isEmpty { CachedImage(source: mediaURL).frame(height: 150); Button("移除图片", role: .destructive) { mediaURL = "" } }
                    DatePicker("发送时间", selection: $date, in: Date().addingTimeInterval(30)...Date().addingTimeInterval(90 * 86400))
                }.disabled(busy || pending != nil)
                Section {
                    if pending != nil { Text("上次提交结果待确认。重试会使用相同内容与标识，防止重复创建。").font(.footnote).foregroundStyle(.secondary) }
                    Button(pending == nil ? "创建定时消息" : "确认上次提交结果") { save() }.disabled(busy || (pending == nil && content.isEmpty && mediaURL.isEmpty))
                }
                Section("已创建") {
                    ForEach(rows) { row in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(row["content"].string)
                            Text(row.text("send_at", "status")).font(.caption).foregroundStyle(.secondary)
                            if row["status"].string == "pending" {
                                Button("取消发送", role: .destructive) { cancel(row) }.disabled(busy)
                            }
                        }
                    }
                }
            }.navigationTitle("定时发送")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
                .task { pending = await LocalStore.shared.read(recoveryKey, userID: session.user?.id ?? 0); await load() }.errorAlert($error)
                .onChange(of: photo) { _, item in guard let item else { return }; upload(item) }
        }
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
    private func upload(_ item: PhotosPickerItem) {
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
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways { manager.requestLocation() } else if manager.authorizationStatus == .denied { error = "请在系统设置允许位置权限" } }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) { coordinate = locations.last?.coordinate }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { self.error = error.localizedDescription }
}

struct SendLocationView: View {
    @EnvironmentObject private var session: SessionStore
    let scope: String
    let targetID: Int
    @Environment(\.dismiss) private var dismiss
    @StateObject private var location = LocationProvider()
    @State private var label = "我的位置"
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                TextField("位置名称", text: $label)
                if let coordinate = location.coordinate { InfoRow(title: "纬度", value: String(coordinate.latitude)); InfoRow(title: "经度", value: String(coordinate.longitude)); Button("发送位置") { let owner = session.user?.id ?? 0; Task { do { let link = "https://maps.apple.com/?ll=\(coordinate.latitude),\(coordinate.longitude)&q=\(label.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")"; try await Outbox.shared.enqueue(scope: scope, id: targetID, body: ["message_type": "text", "content": .string("\(label)\n\(link)")], expectedUserID: owner); dismiss() } catch { self.error = error.localizedDescription } } } }
                else { ProgressView("正在获取当前位置…") }
                if let error = location.error { ErrorBanner(message: error); Button("重试") { location.request() } }
            }.navigationTitle("发送位置").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }.onAppear { location.request() }.errorAlert($error)
        }
    }
}
