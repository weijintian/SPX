import SwiftUI
import PhotosUI

struct PendingCheckout: Codable, Equatable {
    let quoteID: String
    let requestID: String
}

struct CheckoutView: View {
    let product: Record
    @EnvironmentObject private var session: SessionStore
    @State private var variantID = ""
    @State private var quantity = 1
    @State private var addressID = 0
    @State private var addresses: [Record] = []
    @State private var note = ""
    @State private var quote: JSON = .null
    @State private var quoteExpiresAt: Date?
    @State private var pending: PendingCheckout?
    @State private var restored = false
    @State private var orderID = 0
    @State private var busy = false
    @State private var confirm = false
    @State private var error: String?
    private var physical: Bool { product["product_type"].string == "physical" }
    private var variants: [JSON] { product["variants"].array.filter { $0["enabled"] == .null || $0["enabled"].flag } }
    private var selectedVariant: JSON? { variants.first { $0["id"].string == variantID } }
    private var selectedPrice: String { selectedVariant?["cny_price"].string ?? product["cny_price"].string }
    private var availableStock: Int { selectedVariant?["stock"].int ?? (variants.isEmpty ? product["stock"].int : 0) }
    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) { CachedImage(source: product.image).frame(width: 76, height: 76); VStack(alignment: .leading, spacing: 8) { Text(product.name).font(.headline); PriceLabel(cny: selectedPrice, estimate: nil, legacy: selectedVariant?["spx_price"].string ?? product["spx_price"].string) } }
                if !variants.isEmpty { Picker("选择规格", selection: $variantID) { Text("请选择").tag(""); ForEach(variants, id: \.self) { value in Text("\(value["name"].string) · ¥\(DisplayLabels.amount(value["cny_price"].string))").tag(value["id"].string) } } }
                Stepper("数量：\(quantity)", value: $quantity, in: 1...max(1, min(99, availableStock))).disabled(availableStock < 1)
                Text("可购买库存：\(availableStock)").font(.caption).foregroundStyle(.secondary)
            }.disabled(busy || pending != nil)
            if physical {
                Section("收货地址") {
                    Picker("收件人", selection: $addressID) { Text("请选择地址").tag(0); ForEach(addresses) { address in Text("\(address["name"].string) \(address["phone"].string) · \(address["detail"].string)").tag(address.id) } }
                    NavigationLink("新增或编辑地址") { AddressView() }
                }.disabled(busy || pending != nil)
            }
            Section { TextField("买家备注（选填）", text: $note, axis: .vertical).lineLimit(2...4) }.disabled(busy || pending != nil)
            if !quote.object.isEmpty {
                Section("锁定报价") {
                    InfoRow(title: "商品单价", value: DisplayLabels.amount(quote["unit_price"].string) + " SPX001")
                    InfoRow(title: "运费", value: DisplayLabels.amount(quote["shipping_fee"].string) + " SPX001")
                    InfoRow(title: "合计", value: DisplayLabels.amount(quote["total_spx"].string) + " SPX001")
                    InfoRow(title: "可用余额", value: DisplayLabels.amount(quote["balance"].string) + " SPX001")
                    if let quoteExpiresAt {
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            let remaining = max(0, Int(quoteExpiresAt.timeIntervalSince(context.date)))
                            Text(remaining > 0 ? String(format: "报价剩余 %02d:%02d，最终金额以服务器核对为准。", remaining / 60, remaining % 60) : "报价已到期，请重新核对后支付。")
                                .font(.caption).foregroundStyle(remaining > 0 ? Color.secondary : Color.orange)
                        }
                    }
                }
            }
            if let error { Section { ErrorBanner(message: error) } }
            Section {
                if pending != nil { Button(busy ? "正在核对…" : "核对上次支付结果") { resolve() }.disabled(busy) }
                else { Button(busy ? "请稍候…" : "核对报价并继续") { prepare() }.disabled(!restored || busy || availableStock < 1 || (physical && addressID == 0) || (!variants.isEmpty && variantID.isEmpty)) }
            }
        }.navigationTitle("确认订单").navigationBarTitleDisplayMode(.inline)
            .onChange(of: variantID) { _, _ in quantity = min(quantity, max(1, min(99, availableStock))); quote = .null }
            .onChange(of: quantity) { _, _ in quote = .null }
            .onChange(of: addressID) { _, _ in quote = .null }
            .task {
                guard let owner = session.user?.id else { return }
                pending = await LocalStore.shared.read("checkout-pending", userID: owner)
                guard session.user?.id == owner else { return }
                restored = true
                await loadAddresses()
            }
            .onReceive(NotificationCenter.default.publisher(for: .spxAddressChanged)) { _ in Task { await loadAddresses() } }
            .confirmationDialog("确认支付 \(DisplayLabels.amount(quote["total_spx"].string)) SPX001？", isPresented: $confirm, titleVisibility: .visible) { Button("确认支付") { pay() }; Button("取消", role: .cancel) { } }
            .navigationDestination(isPresented: Binding(get: { orderID > 0 }, set: { if !$0 { orderID = 0 } })) { OrderDetailView(id: orderID) }
    }
    private func loadAddresses() async { guard physical else { return }; do { addresses = try await APIClient.shared.get("wallet.address").list; if addressID == 0 { addressID = addresses.first(where: { $0["is_default"].flag })?.id ?? addresses.first?.id ?? 0 } } catch { self.error = error.localizedDescription } }
    private func prepare() {
        guard restored, !busy, pending == nil else { return }
        busy = true; error = nil
        Task { defer { busy = false }; do { let quoteStarted = Date(); quote = try await APIClient.shared.post("checkout.quote", ["product_id": .number(Decimal(product.id)), "variant_id": .string(variantID), "quantity": .number(Decimal(quantity)), "address_id": .number(Decimal(addressID)), "buyer_note": .string(note)]); guard quote["quote_id"].string.count == 32 else { throw APIError.invalidResponse }; quoteExpiresAt = quoteStarted.addingTimeInterval(TimeInterval(quote["expires_in"].int)); confirm = true } catch { self.error = error.localizedDescription } }
    }
    private func pay() {
        guard restored, !busy, pending == nil, let owner = session.user?.id, owner > 0 else { return }
        guard let quoteExpiresAt, quoteExpiresAt > Date() else { quote = .null; error = "报价已到期，请重新核对后支付。"; return }
        let transaction = PendingCheckout(quoteID: quote["quote_id"].string, requestID: UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased())
        pending = transaction; busy = true
        Task {
            defer { busy = false }
            do { try await LocalStore.shared.reservePending(transaction, key: "checkout-pending", userID: owner) } catch { pending = await LocalStore.shared.read("checkout-pending", userID: owner); self.error = "无法创建新的支付，请先核对上次结果或检查设备存储。"; return }
            do { guard session.user?.id == owner else { throw APIError.staleSession }; let result = try await APIClient.shared.post("orders.redeem", ["quote_id": .string(transaction.quoteID), "client_request_id": .string(transaction.requestID)]); guard result["order_id"].int > 0 else { throw APIError.invalidResponse }; await complete(result["order_id"].int, owner: owner) }
            catch { self.error = "支付结果需要核对，请勿重复提交。\(error.localizedDescription)" }
        }
    }
    private func resolve() {
        guard !busy, let pending, let owner = session.user?.id else { return }; busy = true
        Task { defer { busy = false }; do { let result = try await APIClient.shared.post("checkout.resolve", ["quote_id": .string(pending.quoteID)]); if result["found"].flag { await complete(result["order_id"].int, owner: owner) } else if result["voided"].flag { await LocalStore.shared.completePending(pending, key: "checkout-pending", userID: owner); self.pending = nil; quote = .null; error = "订单未创建，原报价已作废，请重新核对报价" } else { throw APIError.invalidResponse } } catch { self.error = error.localizedDescription } }
    }
    private func complete(_ id: Int, owner: Int) async { await LocalStore.shared.remove("checkout-pending", userID: owner); guard session.user?.id == owner else { return }; pending = nil; orderID = id }
}

struct OrdersView: View {
    @State private var rows: [Record] = []
    @State private var status = "all"
    @State private var page = 1
    @State private var more = true
    @State private var busy = false
    @State private var loadID = UUID()
    @State private var error: String?
    var body: some View {
        List {
            Picker("订单状态", selection: $status) { Text("全部").tag("all"); Text("待履约").tag("pending"); Text("已发货").tag("shipped"); Text("已完成").tag("delivered"); Text("售后").tag("after_sale") }
            ForEach(rows) { order in NavigationLink { OrderDetailView(id: order.id) } label: { OrderSummary(order: order) } }
            if more { Button(busy ? "加载中…" : "加载更多") { Task { await load(reset: false) } }.disabled(busy) }
            if rows.isEmpty && !busy { EmptyState(title: "暂无订单", icon: "shippingbox") }
        }.navigationTitle("我的订单").task(id: status) { await load(reset: true) }.refreshable { await load(reset: true) }.errorAlert($error)
            .onReceive(NotificationCenter.default.publisher(for: .spxOrdersChanged).debounce(for: .milliseconds(250), scheduler: RunLoop.main)) { event in
                Task { if await AccountMutation.isCurrent(event) { await load(reset: true) } }
            }
    }
    private func load(reset: Bool) async {
        if !reset && busy { return }
        let requestID = UUID(); loadID = requestID; busy = true
        defer { if loadID == requestID { busy = false } }
        let next = reset ? 1 : page + 1
        do {
            let list = try await APIClient.shared.get("orders", ["status": status, "page": String(next), "page_size": "30"]).list
            guard loadID == requestID, !Task.isCancelled else { return }
            if reset { rows = list } else { let ids = Set(rows.map(\.id)); rows += list.filter { !ids.contains($0.id) } }
            page = next; more = list.count == 30; error = nil
        } catch is CancellationError { } catch { if loadID == requestID { self.error = error.localizedDescription } }
    }
}

struct OrderSummary: View {
    let order: Record
    var body: some View { VStack(alignment: .leading, spacing: 9) { HStack { Text(order.text("product_name", "name")).font(.headline); Spacer(); Text(DisplayLabels.order(order["status"].string)).font(.caption).foregroundStyle(SPXColor.primary) }; Text("\(order["quantity"].string) 件 · \(DisplayLabels.amount(order["total_spx"].string)) SPX001").font(.subheadline); Text(order["created_at"].string).font(.caption).foregroundStyle(.secondary) }.padding(.vertical, 6) }
}

struct OrderDetailView: View {
    let id: Int
    @State private var detailLoadID = UUID()
    @State private var order = Record()
    @State private var error: String?
    @State private var busy = false
    @State private var action: String?
    @State private var afterSale = false
    @State private var returns = false
    @State private var review = false
    var body: some View {
        List {
            Section { OrderSummary(order: order); InfoRow(title: "订单编号", value: String(id)); InfoRow(title: "规格", value: order["variant_name"].string); InfoRow(title: "支付金额", value: DisplayLabels.amount(order["total_spx"].string) + " SPX001") }
            if !order["carrier"].string.isEmpty { Section("物流") { InfoRow(title: "承运商", value: order["carrier"].string); InfoRow(title: "运单号", value: order["tracking_number"].string) } }
            if !order["fulfillment_note"].string.isEmpty { Section("交付内容") { Text(order["fulfillment_note"].string).textSelection(.enabled) } }
            if !order["redeem_code"].string.isEmpty { Section("到店核销码") { Text(order["redeem_code"].string).font(.title2.monospaced().bold()).textSelection(.enabled); Text("到店时向商家出示，切勿提前转发。").font(.caption).foregroundStyle(.secondary) } }
            if !order["address"].object.isEmpty { Section("收货信息") { let address = order["address"]; Text("\(address["name"].string) \(address["phone"].string)"); Text(["province", "city", "district", "detail"].map { address[$0].string }.joined()) } }
            Section("订单进度") { ForEach(Array(order["events"].array.enumerated()), id: \.offset) { _, event in VStack(alignment: .leading) { Text(event["message"].string); Text(event["created_at"].string).font(.caption).foregroundStyle(.secondary) } } }
            if order["after_sale_status"].string != "none", !order["after_sale_status"].string.isEmpty {
                Section("售后进度") { InfoRow(title: "当前状态", value: DisplayLabels.afterSale(order["after_sale_status"].string)); Text(order.text("merchant_service_reply", "after_sale_reason")); if order["after_sale_status"].string == "return_approved" { Text(order["return_address"].string); Button("填写退货物流") { returns = true } } }
            }
            Section {
                if order["status"].string == "shipped" { Button("确认收货") { action = "orders.receive" } }
                if order["status"].string == "pending" { Button("取消订单", role: .destructive) { action = "orders.cancel" } }
                if order["can_after_sale"].flag, ["none", "rejected", ""].contains(order["after_sale_status"].string) { Button("申请售后") { afterSale = true } }
                if ["delivered", "completed"].contains(order["status"].string) { Button("评价商品") { review = true } }
            }.disabled(busy)
            if let error { ErrorBanner(message: error) }
        }.navigationTitle("订单详情").task { await load() }.refreshable { await load() }
            .onReceive(NotificationCenter.default.publisher(for: .spxOrdersChanged).debounce(for: .milliseconds(250), scheduler: RunLoop.main)) { event in
                Task { if await AccountMutation.isCurrent(event) { await load() } }
            }
            .confirmationDialog("确认执行此订单操作？", isPresented: Binding(get: { action != nil }, set: { if !$0 { action = nil } }), titleVisibility: .visible) { Button("确认") { if let action { mutate(action); self.action = nil } } }
            .sheet(isPresented: $afterSale, onDismiss: { Task { await load() } }) { AfterSaleView(orderID: id, canReturn: order["order_type"].string == "physical" && order["status"].string != "pending") }
            .sheet(isPresented: $returns, onDismiss: { Task { await load() } }) { ReturnShipmentView(orderID: id) }
            .sheet(isPresented: $review) { ReviewEditor(order: order) }
    }
    private func load() async {
        let requestID = UUID(); detailLoadID = requestID
        do {
            let value = try await APIClient.shared.get("order", ["id": String(id)]).record
            guard detailLoadID == requestID, !Task.isCancelled else { return }
            order = value; error = nil
        } catch is CancellationError { }
        catch { if detailLoadID == requestID { self.error = error.localizedDescription } }
    }
    private func mutate(_ action: String) { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post(action, ["order_id": .number(Decimal(id))]); await load() } catch { self.error = error.localizedDescription } } }
}

struct AfterSaleView: View {
    let orderID: Int
    var canReturn = false
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var type = "refund_only"
    @State private var reason = ""
    @State private var photos: [PhotosPickerItem] = []
    @State private var urls: [String] = []
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack { Form { Picker("售后方式", selection: $type) { Text("仅退款").tag("refund_only"); if canReturn { Text("退货退款").tag("return_refund") } }; TextField("申请原因", text: $reason, axis: .vertical).lineLimit(4...10); PhotosPicker(selection: $photos, maxSelectionCount: 6, matching: .images) { Label("选择凭证（最多六张）", systemImage: "photo.on.rectangle") }; Text("已选择 \(photos.count) 张").foregroundStyle(.secondary); if let error { ErrorBanner(message: error) }; Button(busy ? "正在提交…" : "提交售后申请") { submit() }.disabled(busy || reason.isEmpty) }.disabled(busy).onChange(of: photos) { _, _ in urls = [] }.navigationTitle("申请售后").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(busy) } } }
    }
    private func submit() { busy = true; Task { defer { busy = false }; do { while urls.count < photos.count { let file = try await MediaFiles.select(photos[urls.count], userID: session.user?.id ?? 0); let url = try await MediaUploader.shared.uploadForm(file: file, action: "orders.media.upload"); urls.append(url); try? FileManager.default.removeItem(at: file) }; try await APIClient.shared.post("orders.after_sale", ["order_id": .number(Decimal(orderID)), "type": .string(type), "reason": .string(reason), "images": .array(urls.map(JSON.string))]); dismiss() } catch { self.error = error.localizedDescription } } }
}

struct ReturnShipmentView: View {
    let orderID: Int
    @Environment(\.dismiss) private var dismiss
    @State private var carrier = ""
    @State private var tracking = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View { NavigationStack { Form { TextField("承运商", text: $carrier); TextField("退货运单号", text: $tracking); if let error { ErrorBanner(message: error) }; Button("提交物流") { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("orders.return", ["order_id": .number(Decimal(orderID)), "carrier": .string(carrier), "tracking_number": .string(tracking)]); dismiss() } catch { self.error = error.localizedDescription } } }.disabled(busy || carrier.isEmpty || tracking.isEmpty) }.navigationTitle("退货物流").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } } } }
}

struct ReviewEditor: View {
    let order: Record
    @Environment(\.dismiss) private var dismiss
    @State private var rating = 5
    @State private var content = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View { NavigationStack { Form { Stepper("评分：\(rating) 星", value: $rating, in: 1...5); TextField("分享使用感受", text: $content, axis: .vertical).lineLimit(4...12); if let error { ErrorBanner(message: error) }; Button("提交评价") { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("product.review", ["order_id": order["id"], "product_id": order["product_id"], "rating": .number(Decimal(rating)), "content": .string(content)]); dismiss() } catch { self.error = error.localizedDescription } } }.disabled(busy || content.isEmpty) }.navigationTitle("评价商品").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } } } }
}
