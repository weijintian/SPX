import SwiftUI

struct RedPacketHistoryView: View {
    @EnvironmentObject private var session: SessionStore
    @StateObject private var state = PageState()
    @State private var filter = "all"
    @State private var error: String?
    var body: some View {
        List {
            Section { Picker("红包记录", selection: $filter) { Text("全部").tag("all"); Text("收到").tag("received"); Text("发出").tag("sent") }.pickerStyle(.segmented) }
            ForEach(state.rows, id: \.fields) { packet in
                Button {
                    NotificationCenter.default.post(name: .spxOpenPacket, object: packet["id"].string)
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "gift.fill").font(.title2).foregroundStyle(.red).frame(width: 48, height: 48)
                            .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                        VStack(alignment: .leading, spacing: 6) {
                            Text(packet["greeting"].string).font(.subheadline.weight(.medium)).lineLimit(1).foregroundStyle(.primary)
                            Text(detail(packet)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 4)
                        VStack(alignment: .trailing, spacing: 6) {
                            Text(packet[filter == "received" ? "my_amount" : "total_amount"].string + " SPX001").font(.caption.weight(.semibold)).foregroundStyle(.primary)
                            Text(status(packet)).font(.caption2).foregroundStyle(.secondary)
                        }
                    }.padding(.vertical, 5)
                }.buttonStyle(.plain)
            }
            if state.busy { ProgressView().frame(maxWidth: .infinity) }
            else if state.page > 0, state.rows.isEmpty, error == nil { EmptyState(title: "暂无红包记录", icon: "gift") }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load(reset: state.page == 0) } } }
            if state.more { Button("加载更多") { Task { await load(reset: false) } }.disabled(state.busy) }
            Section { Text("向下加载可查看更早的红包记录。点击可查看领取情况，金额与退款以服务器记录为准。").font(.footnote).foregroundStyle(.secondary) }
        }.navigationTitle("红包记录").spxPage().task(id: filter) { await load(reset: true) }.refreshable { await load(reset: true) }
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
