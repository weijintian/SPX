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
        SPXDetailPage {
            SPXPanel("商品信息") {
                HStack(alignment: .top, spacing: 14) { CachedImage(source: product.image).frame(width: 76, height: 76).clipShape(RoundedRectangle(cornerRadius: 12)); VStack(alignment: .leading, spacing: 8) { Text(product.name).font(.headline); PriceLabel(cny: selectedPrice, estimate: nil, legacy: selectedVariant?["spx_price"].string ?? product["spx_price"].string) } }
                if !variants.isEmpty { Picker("选择规格", selection: $variantID) { Text("请选择").tag(""); ForEach(variants, id: \.self) { value in Text("\(value["name"].string) · ¥\(DisplayLabels.amount(value["cny_price"].string))").tag(value["id"].string) } } }
                Stepper("数量：\(quantity)", value: $quantity, in: 1...max(1, min(99, availableStock))).disabled(availableStock < 1)
                Text("可购买库存：\(availableStock)").font(.caption).foregroundStyle(SPXColor.muted)
            }.disabled(busy || pending != nil)
            if physical {
                SPXPanel("收货地址") {
                    Picker("收件人", selection: $addressID) { Text("请选择地址").tag(0); ForEach(addresses) { address in Text("\(address["name"].string) \(address["phone"].string) · \(address["detail"].string)").tag(address.id) } }
                    NavigationLink { AddressView() } label: { SPXSettingsRow(title: "新增或编辑地址", icon: "mappin.and.ellipse") }.buttonStyle(.plain)
                }.disabled(busy || pending != nil)
            }
            SPXPanel("买家留言") { TextField("备注需求（选填）", text: $note, axis: .vertical).lineLimit(2...4) }.disabled(busy || pending != nil)
            if !quote.object.isEmpty {
                SPXPanel("锁定报价", footer: "最终支付金额以服务器核对为准。") {
                    InfoRow(title: "商品单价", value: DisplayLabels.amount(quote["unit_price"].string) + " SPX001")
                    InfoRow(title: "运费", value: DisplayLabels.amount(quote["shipping_fee"].string) + " SPX001")
                    Divider()
                    InfoRow(title: "合计", value: DisplayLabels.amount(quote["total_spx"].string) + " SPX001")
                    InfoRow(title: "可用余额", value: DisplayLabels.amount(quote["balance"].string) + " SPX001")
                    if let quoteExpiresAt { TimelineView(.periodic(from: .now, by: 1)) { context in let remaining = max(0, Int(quoteExpiresAt.timeIntervalSince(context.date))); Text(remaining > 0 ? String(format: "报价剩余 %02d:%02d", remaining / 60, remaining % 60) : "报价已到期，请重新核对后支付。").font(.caption).foregroundStyle(remaining > 0 ? SPXColor.muted : .orange) } }
                }
            }
            if pending != nil { Label("上次支付结果待核对，请先确认结果，避免重复下单。", systemImage: "clock.arrow.circlepath").font(.footnote).foregroundStyle(.orange) }
            if let error { ErrorBanner(message: error) }
        }.safeAreaInset(edge: .bottom, spacing: 0) {
            SPXBottomAction {
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
        SPXDetailPage {
            SPXFilterTabs(options: [("all", "全部"), ("pending", "待发货 / 交付"), ("shipped", "待收货"), ("local", "待使用"), ("delivered", "已完成"), ("after_sale", "售后")], selection: $status)
            ForEach(rows) { order in
                NavigationLink { OrderDetailView(id: order.id) } label: {
                    SPXCard { OrderSummary(order: order) }
                }.buttonStyle(.plain)
            }
            if busy && rows.isEmpty { ProgressView("正在加载订单…").frame(maxWidth: .infinity).padding(30) }
            if rows.isEmpty && !busy { EmptyState(title: "暂无此类订单", description: "订单状态更新后会显示在这里", icon: "shippingbox") }
            if more && !rows.isEmpty { Button(busy ? "加载中…" : "加载更多") { Task { await load(reset: false) } }.disabled(busy).frame(maxWidth: .infinity).padding(12) }
        }.navigationTitle("我的订单").task(id: status) { await load(reset: true) }.spxRefreshable { await load(reset: true) }.errorAlert($error)
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
    var showDetail = true
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack { Text("订单 #\(order.id)").font(.system(size: 12)).foregroundStyle(SPXColor.muted); Spacer(); SPXStatusPill(title: DisplayLabels.order(order["status"].string)) }
            HStack(alignment: .top, spacing: 12) {
                CachedImage(source: order.image).frame(width: 76, height: 76).clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 8) {
                    Text(order.text("product_name", "name")).font(.system(size: 15, weight: .semibold)).foregroundStyle(SPXColor.ink).lineLimit(2)
                    Text("\(order["variant_name"].string) × \(order["quantity"].string)").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                    Text(DisplayLabels.amount(order["total_spx"].string) + " SPX001").font(.system(size: 17, weight: .semibold)).foregroundStyle(SPXColor.commerce)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            Divider().overlay(SPXColor.line)
            HStack { Text(order["created_at"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted); Spacer(); if showDetail { Label("查看详情", systemImage: "chevron.right").font(.system(size: 12)).foregroundStyle(SPXColor.commerce) } }
        }
    }
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
        SPXDetailPage {
            if order.id == 0 {
                if let error { EmptyState(title: "订单暂时无法加载", description: error, icon: "wifi.exclamationmark"); Button("重新加载") { Task { await load() } }.buttonStyle(SPXPrimaryButtonStyle()) }
                else { ProgressView("正在加载订单…").frame(maxWidth: .infinity).padding(40) }
            } else {
                SPXPageHeading(title: DisplayLabels.order(order["status"].string), subtitle: "订单 #\(id) · \(order["created_at"].string)", icon: "order", color: SPXColor.commerce)
                SPXPanel("商品信息") {
                    OrderSummary(order: order, showDetail: false)
                    InfoRow(title: "规格", value: order["variant_name"].string)
                    InfoRow(title: "商品单价", value: DisplayLabels.amount(order["unit_price"].string) + " SPX001")
                    InfoRow(title: "运费", value: DisplayLabels.amount(order["shipping_fee"].string) + " SPX001")
                    InfoRow(title: "实付金额", value: DisplayLabels.amount(order["total_spx"].string) + " SPX001")
                    if !order["buyer_note"].string.isEmpty { InfoRow(title: "买家留言", value: order["buyer_note"].string) }
                }
                if !order["address"].object.isEmpty {
                    SPXPanel("收货信息") {
                        let address = order["address"]
                        Label("\(address["name"].string)  \(address["phone"].string)", systemImage: "mappin.and.ellipse").font(.system(size: 15, weight: .medium))
                        Text(["province", "city", "district", "detail"].map { address[$0].string }.joined()).font(.system(size: 14)).foregroundStyle(SPXColor.muted)
                    }
                }
                if !order["carrier"].string.isEmpty {
                    SPXPanel("物流信息") {
                        InfoRow(title: "承运商", value: order["carrier"].string)
                        InfoRow(title: "运单号", value: order["tracking_number"].string)
                        ShareLink(item: order["tracking_number"].string) { Label("分享物流单号", systemImage: "square.and.arrow.up") }.font(.subheadline)
                    }
                }
                if !order["fulfillment_note"].string.isEmpty { SPXPanel("交付内容") { Text(order["fulfillment_note"].string).font(.system(size: 15)).textSelection(.enabled) } }
                if !order["redeem_code"].string.isEmpty {
                    SPXPanel("到店核销码", footer: "接受服务时向商家出示，请勿提前转发。") {
                        Text(order["redeem_code"].string).font(.system(size: 30, weight: .bold, design: .monospaced)).foregroundStyle(SPXColor.commerce).textSelection(.enabled).frame(maxWidth: .infinity).padding(.vertical, 12)
                        if !order["service_expires_at"].string.isEmpty { InfoRow(title: "有效期至", value: order["service_expires_at"].string) }
                    }
                }
                if order["after_sale_status"].string != "none", !order["after_sale_status"].string.isEmpty {
                    SPXPanel("售后进度") {
                        SPXStatusPill(title: DisplayLabels.afterSale(order["after_sale_status"].string))
                        Text(order["after_sale_reason"].string).font(.subheadline)
                        if !order["after_sale_reply"].string.isEmpty { InfoRow(title: "处理回复", value: order["after_sale_reply"].string) }
                        if !order["merchant_service_reply"].string.isEmpty { InfoRow(title: "商家回复", value: order["merchant_service_reply"].string) }
                        if !order["after_sale_images"].array.isEmpty { ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 10) { ForEach(Array(order["after_sale_images"].array.enumerated()), id: \.offset) { _, image in CachedImage(source: image.string).frame(width: 88, height: 88).clipShape(RoundedRectangle(cornerRadius: 12)) } } } }
                        if !order["return_address"].string.isEmpty { InfoRow(title: "退货地址", value: order["return_address"].string) }
                        if !order["return_tracking"].string.isEmpty { InfoRow(title: "退货物流", value: order["return_carrier"].string + " " + order["return_tracking"].string) }
                        if order["after_sale_status"].string == "return_approved" { Button("填写退货物流") { returns = true }.buttonStyle(.bordered) }
                    }
                }
                SPXPanel("订单进度") {
                    ForEach(Array(order["events"].array.enumerated()), id: \.offset) { _, event in SPXTimelineRow(title: event["message"].string, date: event["created_at"].string) }
                    if order["events"].array.isEmpty { Text("暂无进度记录").font(.subheadline).foregroundStyle(SPXColor.muted) }
                }
                if let error { ErrorBanner(message: error) }
            }
        }.safeAreaInset(edge: .bottom, spacing: 0) {
            if order.id > 0 {
                let held = ["requested", "return_approved", "return_shipped"].contains(order["after_sale_status"].string)
                if order["status"].string == "pending" || (order["status"].string == "shipped" && !held) || (order["can_after_sale"].flag && !held) || ["delivered", "completed"].contains(order["status"].string) {
                    SPXBottomAction {
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 12) { orderActions }
                            VStack(spacing: 10) { orderActions }
                        }.disabled(busy)
                    }
                }
            }
        }.navigationTitle("订单详情").task { await load() }.spxRefreshable { await load() }
            .onReceive(NotificationCenter.default.publisher(for: .spxOrdersChanged).debounce(for: .milliseconds(250), scheduler: RunLoop.main)) { event in
                Task { if await AccountMutation.isCurrent(event) { await load() } }
            }
            .confirmationDialog("确认执行此订单操作？", isPresented: Binding(get: { action != nil }, set: { if !$0 { action = nil } }), titleVisibility: .visible) { Button("确认") { if let action { mutate(action); self.action = nil } } }
            .sheet(isPresented: $afterSale, onDismiss: { Task { await load() } }) { AfterSaleView(orderID: id, canReturn: order["order_type"].string == "physical" && order["status"].string != "pending") }
            .sheet(isPresented: $returns, onDismiss: { Task { await load() } }) { ReturnShipmentView(orderID: id) }
            .sheet(isPresented: $review) { ReviewEditor(order: order) }
    }
    @ViewBuilder private var orderActions: some View {
        let held = ["requested", "return_approved", "return_shipped"].contains(order["after_sale_status"].string)
        if order["status"].string == "pending" { Button("取消订单") { action = "orders.cancel" } }
        if order["can_after_sale"].flag, !held { Button("申请售后") { afterSale = true } }
        if order["status"].string == "shipped", !held { Button("确认收货") { action = "orders.receive" } }
        if ["delivered", "completed"].contains(order["status"].string) { Button("评价商品") { review = true } }
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
        NavigationStack {
            SPXDetailPage {
                SPXPageHeading(title: "申请售后", subtitle: "请说明遇到的问题，商家将根据订单信息处理。", icon: "arrow.uturn.backward", color: SPXColor.commerce)
                SPXPanel("售后方式") {
                    Picker("售后方式", selection: $type) { Text("仅退款").tag("refund_only"); if canReturn { Text("退货退款").tag("return_refund") } }.pickerStyle(.segmented)
                }
                SPXPanel("问题说明") { TextField("描述商品问题与退款原因", text: $reason, axis: .vertical).lineLimit(5...12) }
                SPXPanel("上传凭证", footer: "最多选择 6 张图片，提交时自动上传。") {
                    PhotosPicker(selection: $photos, maxSelectionCount: 6, matching: .images) { Label("选择图片 · \(photos.count) / 6", systemImage: "photo.badge.plus").frame(maxWidth: .infinity).padding(16).background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 12)) }
                    if !photos.isEmpty { SPXPhotoSelectionStrip(items: photos) }
                    if busy { ProgressView("正在上传凭证并提交申请…") }
                }
                if let error { ErrorBanner(message: error) }
            }.disabled(busy).safeAreaInset(edge: .bottom, spacing: 0) {
                SPXBottomAction { Button(busy ? "正在提交…" : "提交售后申请") { submit() }.disabled(busy || reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }.onChange(of: photos) { _, _ in urls = [] }
                .navigationTitle("申请售后").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(busy) } }
                .interactiveDismissDisabled(busy)
        }
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
    var body: some View {
        NavigationStack {
            SPXDetailPage {
                SPXPageHeading(title: "填写退货物流", subtitle: "请寄往商家确认的退货地址，并填写真实物流信息。", icon: "shippingbox", color: SPXColor.commerce)
                SPXPanel("物流信息") {
                    SPXField("承运商") { TextField("例如：顺丰速运", text: $carrier) }
                    SPXField("退货运单号") { TextField("请输入物流单号", text: $tracking).textInputAutocapitalization(.never).autocorrectionDisabled() }
                }.disabled(busy)
                if let error { ErrorBanner(message: error) }
            }.safeAreaInset(edge: .bottom, spacing: 0) {
                SPXBottomAction { Button(busy ? "提交中…" : "提交物流") { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("orders.return", ["order_id": .number(Decimal(orderID)), "carrier": .string(carrier), "tracking_number": .string(tracking)]); dismiss() } catch { self.error = error.localizedDescription } } }.disabled(busy || carrier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || tracking.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }.navigationTitle("退货物流").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) } }.interactiveDismissDisabled(busy)
        }
    }
}

struct ReviewEditor: View {
    let order: Record
    @Environment(\.dismiss) private var dismiss
    @State private var rating = 5
    @State private var content = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            SPXDetailPage {
                SPXPageHeading(title: "评价商品", subtitle: "分享真实的商品质量与服务体验。", icon: "star", color: SPXColor.commerce)
                SPXPanel("商品评分") {
                    Text(order.text("product_name", "name")).font(.headline)
                    HStack(spacing: 14) { ForEach(1...5, id: \.self) { value in Button { rating = value } label: { Image(systemName: value <= rating ? "star.fill" : "star").font(.system(size: 30)).foregroundStyle(.orange).frame(minWidth: 40, minHeight: 44) }.buttonStyle(.plain).accessibilityLabel("\(value) 星") } }.frame(maxWidth: .infinity)
                    Text("\(rating) / 5 分").font(.caption).foregroundStyle(SPXColor.muted).frame(maxWidth: .infinity)
                }
                SPXPanel("购买体验") { TextField("商品质量、使用感受或商家服务…", text: $content, axis: .vertical).lineLimit(5...12) }
                if let error { ErrorBanner(message: error) }
            }.disabled(busy).safeAreaInset(edge: .bottom, spacing: 0) {
                SPXBottomAction { Button(busy ? "提交中…" : "提交评价") { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("product.review", ["order_id": order["id"], "product_id": order["product_id"], "rating": .number(Decimal(rating)), "content": .string(content)]); dismiss() } catch { self.error = error.localizedDescription } } }.disabled(busy || content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }.navigationTitle("评价商品").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) } }.interactiveDismissDisabled(busy)
        }
    }
}
