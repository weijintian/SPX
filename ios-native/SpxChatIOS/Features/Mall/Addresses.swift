import SwiftUI

extension Notification.Name { static let spxAddressChanged = Notification.Name("spx.address.changed") }

struct AddressView: View {
    var selection: ((Record) -> Void)? = nil
    @State private var rows: [Record] = []
    @State private var editing: Record?
    @State private var error: String?
    @State private var deleting: Record?
    var body: some View {
        SPXDetailPage {
            ForEach(rows) { address in
                SPXCard { VStack(alignment: .leading, spacing: 10) {
                    HStack { Text(address["name"].string).font(.headline); Text(address["phone"].string).foregroundStyle(.secondary); if address["is_default"].flag { Text("默认").font(.caption2).padding(4).background(SPXColor.primary.opacity(0.1), in: Capsule()).foregroundStyle(SPXColor.primary) } }
                    Text(["province", "city", "district", "detail"].map { address[$0].string }.joined()).font(.subheadline)
                    HStack { if let selection { Button("使用该地址") { selection(address) } }; Button("编辑") { editing = address }; Spacer(); if !address["is_default"].flag { Button("设为默认") { mutate("wallet.address.default", id: address.id) } }; Button("删除", role: .destructive) { deleting = address } }.font(.caption)
                }.padding(.vertical, 8)
                }
            }
            if rows.isEmpty { EmptyState(title: "还没有收货地址", icon: "mappin.and.ellipse") }
        }.safeAreaInset(edge: .bottom, spacing: 0) { SPXBottomAction { Button { editing = Record() } label: { Label("新增收货地址", systemImage: "plus") } } }.navigationTitle("收货地址")
            .task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $editing, onDismiss: { Task { await load() } }) { AddressEditor(address: $0) }
            .confirmationDialog("删除这个收货地址？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) { Button("删除", role: .destructive) { if let deleting { mutate("wallet.address.delete", id: deleting.id) }; deleting = nil } }
    }
    private func load() async { do { rows = try await APIClient.shared.get("wallet.address").list } catch { self.error = error.localizedDescription } }
    private func mutate(_ action: String, id: Int) { Task { do { try await APIClient.shared.post(action, ["id": .number(Decimal(id))]); NotificationCenter.default.post(name: .spxAddressChanged, object: nil); await load() } catch { self.error = error.localizedDescription } } }
}

struct AddressEditor: View {
    let address: Record
    @Environment(\.dismiss) private var dismiss
    @State private var values: [String: String] = [:]
    @State private var isDefault = false
    @State private var busy = false
    @State private var error: String?
    private let fields = [("name", "收件人"), ("phone", "电话"), ("province", "省 / 州"), ("city", "城市"), ("district", "区 / 县"), ("detail", "详细地址")]
    var body: some View {
        NavigationStack {
            SPXDetailPage {
                SPXPanel("收货人") { ForEach(fields.prefix(2), id: \.0) { key, title in SPXField(title) { TextField(title, text: Binding(get: { values[key] ?? "" }, set: { values[key] = $0 })).keyboardType(key == "phone" ? .phonePad : .default) } } }
                SPXPanel("收货地址") { ForEach(fields.suffix(4), id: \.0) { key, title in SPXField(title) { TextField(key == "detail" ? "街道、楼栋、门牌号" : title, text: Binding(get: { values[key] ?? "" }, set: { values[key] = $0 }), axis: key == "detail" ? .vertical : .horizontal) } }; Toggle("设为默认地址", isOn: $isDefault).tint(SPXColor.commerce) }
                if let error { ErrorBanner(message: error) }
            }.disabled(busy).safeAreaInset(edge: .bottom, spacing: 0) { SPXBottomAction { Button(busy ? "保存中…" : "保存地址") { save() }.disabled(busy || fields.contains { values[$0.0]?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false }) } }
                .navigationTitle(address.id > 0 ? "编辑地址" : "新增地址").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) } }
                .interactiveDismissDisabled(busy)
                .onAppear { values = Dictionary(uniqueKeysWithValues: fields.map { ($0.0, address[$0.0].string) }); isDefault = address["is_default"].flag }
        }
    }
    private func save() { busy = true; Task { defer { busy = false }; do { var body = values.mapValues(JSON.string); body["is_default"] = .bool(isDefault); if address.id > 0 { body["id"] = address["id"] }; try await APIClient.shared.post(address.id > 0 ? "wallet.address.update" : "wallet.address.save", body); NotificationCenter.default.post(name: .spxAddressChanged, object: nil); dismiss() } catch { self.error = error.localizedDescription } } }
}
