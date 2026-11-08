import SwiftUI
import Foundation

struct EmojiPanel: View {
    let selected: (String) -> Void
    private let items = ["😀", "😄", "😊", "🥰", "😍", "🤔", "😎", "😭", "😂", "🥳", "😅", "😴", "👍", "👏", "🤝", "🙏", "❤️", "🎉", "🌹", "🎁", "☕️", "✨", "💪", "✅"]
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 8), spacing: 12) {
            ForEach(items, id: \.self) { item in Button { selected(item) } label: { Text(item).spxFont(27).frame(minWidth: 32, minHeight: 40) }.buttonStyle(.plain).accessibilityLabel(item) }
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
        SPXNavigationContainer {
            SPXDetailPage {
                SPXCard { Button { selected(Record(["id": -1, "nickname": "全体成员"])) } label: {
                    SPXSettingsRow(title: "全体成员", icon: "person.3.fill", detail: "提醒群内成员")
                }.buttonStyle(.plain) }
                SPXSectionTitle(title: query.isEmpty ? "群成员" : "搜索结果", detail: query.isEmpty ? "默认显示前 5 位" : "按名字查找")
                ForEach(results, id: \.fields) { member in
                    SPXCard { Button { selected(member) } label: {
                        HStack(spacing: 12) {
                            Avatar(url: member.avatar, name: member.name, size: 44)
                            Text(member.name).spxFont(15).foregroundStyle(SPXColor.ink).frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "at").spxFont(18, weight: .medium).foregroundStyle(SPXColor.primary)
                        }.frame(minHeight: 44).contentShape(Rectangle())
                    }.buttonStyle(.plain) }
                }
                if loading { ProgressView("正在查找群友…").frame(maxWidth: .infinity).padding() }
                if let error { ErrorBanner(message: error) }
                if !loading && results.isEmpty { EmptyState(title: "没有找到匹配的群友", description: "试试其他名字或备注", icon: "person.crop.circle.badge.questionmark") }
            }.navigationTitle("选择提醒的成员").searchable(text: $query).toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
                .task(id: query) {
                    let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
                    loading = true; error = nil
                    do {
                        try await SPXSleep.milliseconds(220)
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

struct MallOrderCardView: View {
    let message: Record
    private var payload: Record {
        guard let data = message["payload_json"].string.data(using: .utf8),
              let json = try? JSONDecoder().decode(JSON.self, from: data) else { return Record([:]) }
        return Record(json.object)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text("\u{1F6D2}").font(.system(size: 14))
                Text("\u{65B0}\u{8BA2}\u{5355}").font(.system(size: 13, weight: .semibold)).foregroundStyle(SPXColor.primary)
            }
            Text(payload["product"].string.isEmpty ? "\u{65B0}\u{8BA2}\u{5355}\u{64AD}\u{62A5}" : payload["product"].string)
                .font(.system(size: 15, weight: .semibold)).lineLimit(2)
            if !payload["variant"].string.isEmpty {
                Text(payload["variant"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted)
            }
            if !payload["amount"].string.isEmpty {
                Text(DisplayLabels.amount(payload["amount"].string) + " SPX001")
                    .font(.system(size: 16, weight: .bold)).foregroundStyle(SPXColor.primary)
            }
            VStack(alignment: .leading, spacing: 2) {
                if !payload["merchant"].string.isEmpty { Text("\u{5546}\u{5BB6} " + payload["merchant"].string).font(.system(size: 11)).foregroundStyle(SPXColor.muted) }
                if !payload["buyer"].string.isEmpty { Text("\u{4E70}\u{5BB6} " + payload["buyer"].string).font(.system(size: 11)).foregroundStyle(SPXColor.muted) }
            }
            Divider()
            Text("SPX \u{8D2D}\u{7269}\u{52A9}\u{624B} \u{00B7} \u{5B9E}\u{65F6}\u{64AD}\u{62A5}").font(.system(size: 10)).foregroundStyle(SPXColor.muted)
        }
        .padding(12)
        .frame(width: 236, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(.secondarySystemBackground)))
    }
}

struct MessageCardContent: View {
    let message: Record
    var body: some View {
        if message["message_type"].string == "mall_card" {
            MallOrderCardView(message: message)
        } else if let product = MessageContent.product(message) {
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
