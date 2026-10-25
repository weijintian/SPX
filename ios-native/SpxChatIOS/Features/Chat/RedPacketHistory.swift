import SwiftUI

struct RedPacketHistoryView: View {
    @EnvironmentObject private var session: SessionStore
    @StateObject private var state = PageState()
    @State private var filter = "all"
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "红包记录", subtitle: "查看收发明细与领取状态", icon: "gift", color: .red)
            SPXFilterTabs(options: [("all", "全部"), ("received", "收到"), ("sent", "发出")], selection: $filter)
            ForEach(state.rows, id: \.fields) { packet in
                SPXCard { Button {
                    NotificationCenter.default.post(name: .spxOpenPacket, object: packet["id"].string)
                } label: {
                    VStack(alignment: .leading, spacing: 14) {
                      HStack(spacing: 14) {
                        Image(systemName: "gift.fill").font(.title2).foregroundStyle(.red).frame(width: 48, height: 48)
                            .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                        VStack(alignment: .leading, spacing: 6) {
                            Text(packet["greeting"].string.isEmpty ? "恭喜发财，大吉大利" : packet["greeting"].string).font(.subheadline.weight(.medium)).lineLimit(2).foregroundStyle(SPXColor.ink)
                            Text(detail(packet)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(SPXColor.muted)
                      }
                      Divider()
                      HStack(alignment: .firstTextBaseline) {
                          Text(DisplayLabels.amount(packet[filter == "received" ? "my_amount" : "total_amount"].string)).font(.system(size: 21, weight: .semibold)).foregroundStyle(SPXColor.ink).monospacedDigit()
                          Text("SPX001").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                          Spacer()
                          SPXStatusPill(title: status(packet), color: packet["status"].string == "active" ? .red : SPXColor.muted)
                      }
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain) }
            }
            if state.busy { ProgressView().frame(maxWidth: .infinity) }
            else if state.page > 0, state.rows.isEmpty, error == nil { EmptyState(title: "暂无红包记录", icon: "gift") }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load(reset: state.page == 0) } } }
            if state.more && !state.rows.isEmpty { Button("加载更多") { Task { await load(reset: false) } }.disabled(state.busy).frame(maxWidth: .infinity, minHeight: 44) }
            Text("点击红包查看领取情况，金额与退款以服务器记录为准。").font(.footnote).foregroundStyle(SPXColor.muted)
        }.navigationTitle("红包记录").spxPage().task(id: filter) { await load(reset: true) }.spxRefreshable { await load(reset: true) }
    }
    private func detail(_ packet: Record) -> String {
        let direction = packet["sender_id"].int == session.user?.id ? "我发出的" : "来自 " + packet["sender_name"].string
        return direction + (packet["mode"].string == "random" ? " · 拼手气红包" : " · 普通红包")
    }
    private func status(_ packet: Record) -> String {
        if packet["status"].string == "active" { return "已领 \(packet["claimed_count"].int)/\(packet["total_count"].int)" }
        return ["expired": "已过期", "completed": "已领完", "exhausted": "已领完", "refunded": "剩余已退回"][packet["status"].string] ?? "已结束"
    }
    private func load(reset: Bool) async {
        let selected = filter
        do { try await state.load(reset: reset) { next in try await APIClient.shared.get("redpackets.mine", ["filter": selected, "page": String(next), "page_size": "30"]).list }; error = nil }
        catch { self.error = error.localizedDescription }
    }
}
