import SwiftUI

struct RedPacketHistoryView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var rows: [Record] = []
    @State private var filter = "all"
    @State private var loaded = false
    @State private var loadID = UUID()
    @State private var error: String?
    private var filtered: [Record] {
        rows.filter { filter == "all" || (filter == "sent" ? $0["sender_id"].int == session.user?.id : !$0["my_amount"].string.isEmpty) }
    }
    var body: some View {
        List {
            Section { Picker("红包记录", selection: $filter) { Text("全部").tag("all"); Text("收到").tag("received"); Text("发出").tag("sent") }.pickerStyle(.segmented) }
            ForEach(filtered, id: \.fields) { packet in
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
            if !loaded { ProgressView().frame(maxWidth: .infinity) }
            else if filtered.isEmpty, error == nil { EmptyState(title: "暂无红包记录", icon: "gift") }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } } }
            Section { Text("显示最近 100 条红包记录。点击可查看领取情况，金额与退款以服务器记录为准。").font(.footnote).foregroundStyle(.secondary) }
        }.navigationTitle("红包记录").spxPage().task { await load() }.refreshable { await load() }
    }
    private func detail(_ packet: Record) -> String {
        let direction = packet["sender_id"].int == session.user?.id ? "我发出的" : "来自 " + packet["sender_name"].string
        return direction + (packet["mode"].string == "random" ? " · 拼手气红包" : " · 普通红包")
    }
    private func status(_ packet: Record) -> String {
        if packet["status"].string == "active" { return "已领 \(packet["claimed_count"].int)/\(packet["total_count"].int)" }
        return ["expired": "已过期", "completed": "已领完", "exhausted": "已领完", "refunded": "剩余已退回"][packet["status"].string] ?? "已结束"
    }
    private func load() async {
        let id = UUID(); loadID = id
        do {
            let list = try await APIClient.shared.get("redpackets.mine").list
            guard loadID == id, !Task.isCancelled else { return }
            rows = list; loaded = true; error = nil
        } catch is CancellationError { }
        catch { if loadID == id { self.error = error.localizedDescription; loaded = true } }
    }
}
