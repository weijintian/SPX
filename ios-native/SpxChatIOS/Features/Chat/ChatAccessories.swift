import SwiftUI

struct EmojiPanel: View {
    let selected: (String) -> Void
    private let items = ["😀", "😄", "😊", "🥰", "😍", "🤔", "😎", "😭", "😂", "🥳", "😅", "😴", "👍", "👏", "🤝", "🙏", "❤️", "🎉", "🌹", "🎁", "☕️", "✨", "💪", "✅"]
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 8), spacing: 12) {
            ForEach(items, id: \.self) { item in Button { selected(item) } label: { Text(item).font(.system(size: 27)).frame(minWidth: 32, minHeight: 40) }.buttonStyle(.plain).accessibilityLabel(item) }
        }.padding(16).background(.bar)
    }
}

struct MemberMentionPicker: View {
    let members: [Record]
    let excluding: Int
    let groupID: Int
    let selected: (Record) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [Record] = []
    @State private var loading = true
    @State private var error: String?
    var body: some View {
        NavigationStack {
            List {
                Button { selected(Record(["id": -1, "nickname": "全体成员"])) } label: { Label("全体成员", systemImage: "person.3.fill").padding(.vertical, 10) }
                Section(query.isEmpty ? "群成员 · 默认显示前 5 位" : "搜索结果") {
                ForEach(results, id: \.fields) { member in
                    Button { selected(member) } label: { HStack(spacing: 12) { Avatar(url: member.avatar, name: member.name, size: 42); Text(member.name).foregroundStyle(.primary); Spacer(); Image(systemName: "at").foregroundStyle(SPXColor.primary) }.padding(.vertical, 4) }
                }
                }
                if loading { ProgressView().frame(maxWidth: .infinity) }
                if let error { Text(error).font(.footnote).foregroundStyle(.secondary) }
                if !loading && results.isEmpty { Text("没有找到匹配的群友").foregroundStyle(.secondary) }
            }.navigationTitle("选择提醒的成员").searchable(text: $query).toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
                .task(id: query) {
                    let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
                    loading = true; error = nil
                    do {
                        try await Task.sleep(for: .milliseconds(220))
                        let rows = try await APIClient.shared.get("groups.mentions", ["group_id": String(groupID), "q": term]).list
                        try Task.checkCancellation(); results = rows.filter { ($0["user_id"].int > 0 ? $0["user_id"].int : $0.id) != excluding }; loading = false
                    } catch is CancellationError { }
                    catch {
                        guard !Task.isCancelled else { return }
                        results = Array(members.filter { $0.id != excluding && (term.isEmpty || $0.name.localizedCaseInsensitiveContains(term)) }.prefix(term.isEmpty ? 5 : 20))
                        self.error = results.isEmpty ? error.localizedDescription : nil; loading = false
                    }
                }
        }
    }
}

struct MessageCardContent: View {
    let message: Record
    var body: some View {
        if let product = MessageContent.product(message) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    CachedImage(source: product["image_url"].string).frame(width: 72, height: 72).background(.white, in: RoundedRectangle(cornerRadius: 10)).clipShape(RoundedRectangle(cornerRadius: 10))
                    VStack(alignment: .leading, spacing: 8) { Text(product.text("name", "product_name")).font(.subheadline.weight(.semibold)).lineLimit(2); if !product["cny_price"].string.isEmpty { Text("¥\(DisplayLabels.amount(product["cny_price"].string))").font(.headline) } }
                }
                Divider(); Label("商城商品 · 查看详情", systemImage: "bag").font(.caption).opacity(0.8)
            }.frame(width: 228, alignment: .leading)
        } else {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) { Image(systemName: "person.2.fill").font(.title2).frame(width: 48, height: 48).background(.white.opacity(0.15), in: RoundedRectangle(cornerRadius: 13)); VStack(alignment: .leading, spacing: 5) { Text(MessageContent.groupTitle(message)).font(.headline).lineLimit(2); Text("群号 \(MessageContent.groupCode(message) ?? "")").font(.caption).opacity(0.8) } }
                Divider(); Text("群聊邀请 · 点击查看").font(.caption).opacity(0.8)
            }.frame(width: 228, alignment: .leading)
        }
    }
}
