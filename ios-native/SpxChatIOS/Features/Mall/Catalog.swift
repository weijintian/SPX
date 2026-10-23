import SwiftUI
import AVKit

struct MallHomeView: View {
    var merchantID: Int = 0
    @State private var products: [Record] = []
    @State private var search = ""
    @State private var category = ""
    @State private var kind = "all"
    @State private var categories: [String] = []
    @State private var page = 1
    @State private var more = true
    @State private var busy = false
    @State private var error: String?
    @State private var loadID = UUID()
    @State private var usd = ""
    @State private var visible = false
    @Environment(\.scenePhase) private var phase
    private var catalogHeader: some View {
        VStack(alignment: .leading, spacing: 18) {
        HStack { Text("发现心仪好物").font(.title2.bold()); Spacer(); NavigationLink { OrdersView() } label: { Image(systemName: "shippingbox").font(.title3) } }
        Picker("商品类型", selection: $kind) { Text("全部").tag("all"); Text("实物").tag("physical"); Text("数字").tag("digital"); Text("本地").tag("local") }.pickerStyle(.segmented)
        ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 10) { categoryButton("全部", value: ""); ForEach(categories, id: \.self) { categoryButton($0, value: $0) } } }
        }
    }
    private var productGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
            ForEach(products) { product in NavigationLink { ProductDetailView(product: product) } label: { ProductCard(product: product, usd: usd) }.buttonStyle(.plain) }
        }
    }
    @ViewBuilder private var catalogStatus: some View {
        if more { Button(busy ? "正在加载…" : "加载更多") { Task { await load(reset: false) } }.frame(maxWidth: .infinity).disabled(busy) }
        if !busy && products.isEmpty { EmptyState(title: "暂无商品", description: "试试其他分类或关键词", icon: "bag") }
        if let error { ErrorBanner(message: error) }
    }
    private var catalogContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                catalogHeader
                productGrid
                catalogStatus
            }.padding(16)
        }
    }
    private var navigatedCatalog: some View {
        catalogContent.navigationTitle(merchantID > 0 ? "店铺商品" : "商城").searchable(text: $search, prompt: "搜索商品")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { NavigationLink { ShopsView() } label: { Image(systemName: "storefront") } } }
            .spxPage().refreshable { await load(reset: true) }

    }
    var body: some View {
        navigatedCatalog
            .task { if let config = try? await APIClient.shared.get("mall.config") { categories = config["categories"].array.map(\.string) }; await load(reset: true) }
            .task(id: search + "|" + category + "|" + kind) { do { try await Task.sleep(for: .milliseconds(350)); await load(reset: true) } catch { } }
            .onAppear { visible = true }.onDisappear { visible = false }
            .task(id: String(describing: phase) + String(visible)) {
                guard visible, phase == .active else { return }
                while !Task.isCancelled {
                    if let valuation = try? await APIClient.shared.get("mall.valuation"), !Task.isCancelled { usd = valuation["spx_usdt"].string }
                    do { try await Task.sleep(for: .seconds(30)) } catch { return }
                }
            }
    }
    private func categoryButton(_ title: String, value: String) -> some View { Button { category = value } label: { Text(title).font(.subheadline.weight(category == value ? .semibold : .regular)).padding(.horizontal, 14).padding(.vertical, 9).foregroundStyle(category == value ? .white : SPXColor.ink).background(category == value ? SPXColor.primary : SPXColor.card, in: Capsule()) } }
    private func load(reset: Bool) async {
        if !reset && busy { return }
        let requestID = UUID(); loadID = requestID; busy = true
        defer { if loadID == requestID { busy = false } }
        let next = reset ? 1 : page + 1
        do {
            let rows = try await APIClient.shared.get("products", ["page": String(next), "q": search, "category": category, "type": kind, "merchant_id": String(merchantID)]).list
            guard requestID == loadID, !Task.isCancelled else { return }
            if reset { products = rows } else { let ids = Set(products.map(\.id)); products += rows.filter { !ids.contains($0.id) } }
            page = next; more = rows.count == 60; error = nil
        } catch is CancellationError { } catch { if loadID == requestID { self.error = error.localizedDescription } }
    }
}

struct ProductCard: View {
    let product: Record
    var usd = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            CachedImage(source: product.image).frame(height: 154).frame(maxWidth: .infinity).background(.white).clipped()
            VStack(alignment: .leading, spacing: 6) {
                Text(product.name).font(.subheadline.weight(.medium)).lineLimit(2).frame(minHeight: 38, alignment: .topLeading)
                PriceLabel(cny: product["cny_price"].string, estimate: Money.estimate(cny: product["cny_price"].string, usd: usd), legacy: product["spx_price"].string)
            }.padding(.horizontal, 12).padding(.bottom, 14)
        }.background(SPXColor.card, in: RoundedRectangle(cornerRadius: 18)).clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

struct PriceLabel: View {
    let cny: String
    let estimate: String?
    var legacy: String = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            if !cny.isEmpty, Money.positive(cny) {
                Text("¥\(cny)").font(.title3.bold()).foregroundStyle(SPXColor.primary)
                if let estimate { Text("≈ \(estimate) SPX001").font(.caption).foregroundStyle(.secondary) }
            } else {
                Text("\(legacy) SPX001").font(.headline).foregroundStyle(SPXColor.primary)
                Text("历史商品 · 尚未设置 CNY 定价").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

struct ProductDetailView: View {
    let product: Record
    @State private var detail = Record()
    @State private var usd = ""
    @State private var error: String?
    @State private var favorite = false
    @State private var favoriteBusy = false
    @State private var shareInChat = false
    @State private var report = false
    @State private var reason = ""
    @State private var visible = false
    @Environment(\.scenePhase) private var phase
    private var current: Record { detail.id > 0 ? detail : product }
    private var images: [String] { let urls = current["images"].array.map(\.string).filter { !$0.isEmpty }; return urls.isEmpty ? [current.image] : urls }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                TabView { ForEach(images, id: \.self) { CachedImage(source: $0).frame(maxWidth: .infinity, maxHeight: .infinity).background(.white) } }
                    .tabViewStyle(.page).frame(height: 340).background(.white)
                VStack(alignment: .leading, spacing: 18) {
                    PriceLabel(cny: current["cny_price"].string, estimate: Money.estimate(cny: current["cny_price"].string, usd: usd), legacy: current["spx_price"].string)
                    Text(current.name).font(.title2.bold())
                    Text(current["description"].string).foregroundStyle(.secondary)
                    HStack { Label(current["product_type"].string == "physical" ? "实物配送" : current["product_type"].string == "local" ? "到店服务" : "数字交付", systemImage: "shippingbox"); Spacer(); Text("库存 \(current["stock"].string)") }.font(.caption).foregroundStyle(.secondary)
                    if current["shop"]["id"].int > 0 { NavigationLink { ShopDetailView(id: current["shop"]["id"].int) } label: { SPXCard { HStack { Avatar(url: current["shop"]["logo_url"].string, name: current["shop"]["name"].string); Text(current["shop"]["name"].string).font(.headline); Spacer(); Image(systemName: "chevron.right") } } } }
                    if current["consultation_group"]["id"].int > 0 { NavigationLink("商品咨询群") { GroupJoinView(initialCode: current["consultation_group"]["group_code"].string) } }
                    NavigationLink("商品评价") { ProductReviewsView(productID: current.id) }
                    Text("商品详情").font(.title3.bold())
                    Text(current.text("detail_text", "description")).textSelection(.enabled)
                    ForEach(current["detail_images"].array.map(\.string), id: \.self) { CachedImage(source: $0).frame(maxWidth: .infinity) }
                    if !current["video_url"].string.isEmpty { ProductVideo(source: current["video_url"].string).frame(height: 220) }
                    ForEach(["parameters", "service_terms", "delivery_note"], id: \.self) { key in if !current[key].string.isEmpty { Text(current[key].string).font(.subheadline).foregroundStyle(.secondary) } }
                    Text("SPX001 为实时估算，1 USD 固定按 6.7 CNY 换算，最终支付以确认订单的锁定报价为准。").font(.caption).foregroundStyle(.secondary)
                    if let error { ErrorBanner(message: error) }
                }.padding(.horizontal, 18).padding(.bottom, 24)
            }
        }.spxPage().navigationTitle("商品详情").navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                HStack(spacing: 18) {
                    Button { toggleFavorite() } label: { Image(systemName: favorite ? "heart.fill" : "heart").font(.title2) }.accessibilityLabel("收藏").disabled(favoriteBusy)
                    NavigationLink { CheckoutView(product: current) } label: { Text("立即购买").fontWeight(.semibold).frame(maxWidth: .infinity).padding(14).background(SPXColor.primary, in: RoundedRectangle(cornerRadius: 15)).foregroundStyle(.white) }.disabled(current["stock"].int < 1)
                }.padding().background(.bar)
            }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Menu { Button("分享给朋友", systemImage: "bubble.left") { shareInChat = true }; if let url = URL(string: "https://spx001.top/spxchat/product-share.php?id=\(current.id)") { ShareLink(item: url) }; Button("举报商品") { report = true } } label: { Image(systemName: "ellipsis") } } }
            .sheet(isPresented: $shareInChat) { ForwardMessageView(message: Record(["message_type": "product", "content": .string(String(data: (try? JSONEncoder().encode(Record(["product_id": current["id"]]))) ?? Data(), encoding: .utf8) ?? "")])) }
            .alert("举报商品", isPresented: $report) { TextField("原因", text: $reason); Button("提交") { Task { do { try await APIClient.shared.post("product.report", ["product_id": .number(Decimal(current.id)), "reason": .string(reason)]) } catch { self.error = error.localizedDescription } } }; Button("取消", role: .cancel) { } }
            .task { do { detail = try await APIClient.shared.get("product", ["id": String(product.id)]).record; favorite = try await APIClient.shared.get("product.favorite.status", ["product_id": String(product.id)])["enabled"].flag } catch { self.error = error.localizedDescription } }
            .onAppear { visible = true }.onDisappear { visible = false }
            .task(id: String(describing: phase) + String(visible)) { guard visible, phase == .active else { return }; while !Task.isCancelled { do { usd = try await APIClient.shared.get("mall.valuation")["spx_usdt"].string; try await Task.sleep(for: .seconds(30)) } catch is CancellationError { return } catch { self.error = error.localizedDescription; do { try await Task.sleep(for: .seconds(30)) } catch { return } } } }
    }
    private func toggleFavorite() { guard !favoriteBusy else { return }; favoriteBusy = true; Task { defer { favoriteBusy = false }; do { try await APIClient.shared.post("product.favorite", ["product_id": .number(Decimal(current.id)), "enabled": .bool(!favorite)]); favorite.toggle() } catch { self.error = error.localizedDescription } } }
}

private struct ProductVideo: View {
    let source: String
    @EnvironmentObject private var session: SessionStore
    @State private var player: AVPlayer?
    @State private var error: String?
    var body: some View {
        VideoPlayer(player: player).overlay { if let error { Text(error).font(.caption).padding() } else if player == nil { ProgressView("正在加载视频…") } }
            .task(id: source) { do { player = AVPlayer(url: try await MediaCache.shared.file(source, userID: session.user?.id ?? 0)) } catch { self.error = error.localizedDescription } }
            .onDisappear { player?.pause(); player = nil }
    }
}

struct ShopsView: View {
    @State private var rows: [Record] = []
    @State private var query = ""
    @State private var page = 1
    @State private var more = true
    @State private var loadID = UUID()
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        List { ForEach(rows) { shop in NavigationLink { ShopDetailView(id: shop.id) } label: { HStack { Avatar(url: shop.avatar, name: shop.name); VStack(alignment: .leading) { Text(shop.name).font(.headline); Text(shop["category"].string).font(.caption).foregroundStyle(.secondary) } } } }; if more { Button(busy ? "正在加载…" : "加载更多") { Task { await load(reset: false) } }.disabled(busy) } }
            .navigationTitle("优选店铺").searchable(text: $query).task(id: query) { do { try await Task.sleep(for: .milliseconds(300)); await load(reset: true) } catch { } }.errorAlert($error)
    }
    private func load(reset: Bool) async {
        if !reset && busy { return }
        let requestID = UUID(); loadID = requestID; busy = true
        defer { if loadID == requestID { busy = false } }
        let next = reset ? 1 : page + 1
        do {
            let list = try await APIClient.shared.get("shops", ["page": String(next), "page_size": "30", "q": query]).list
            guard loadID == requestID, !Task.isCancelled else { return }
            if reset { rows = list } else { let ids = Set(rows.map(\.id)); rows += list.filter { !ids.contains($0.id) } }
            page = next; more = list.count == 30; error = nil
        } catch is CancellationError { } catch { if loadID == requestID { self.error = error.localizedDescription } }
    }
}

struct ShopDetailView: View {
    let id: Int
    @State private var shop = Record()
    @State private var error: String?
    var body: some View {
        List {
            Section { HStack { Avatar(url: shop.avatar, name: shop.name, size: 64); Text(shop.name).font(.title2.bold()) }; Text(shop["description"].string); InfoRow(title: "营业时间", value: shop["business_hours"].string); InfoRow(title: "地址", value: shop["address"].string) }
            Section { NavigationLink("浏览店铺商品") { MallHomeView(merchantID: id) }; ShopNavigationButton(shop: shop); if let url = URL(string: "tel:\(shop["contact_phone"].string)"), !shop["contact_phone"].string.isEmpty { Link("联系店铺", destination: url) } }
        }.navigationTitle("店铺").task { do { shop = try await APIClient.shared.get("shop", ["id": String(id)])["shop"].record } catch { self.error = error.localizedDescription } }.errorAlert($error)
    }
}

struct ProductReviewsView: View {
    let productID: Int
    @State private var rows: [Record] = []
    @State private var error: String?
    var body: some View { List { ForEach(rows.indices, id: \.self) { index in let review = rows[index]; VStack(alignment: .leading, spacing: 8) { HStack { Text(review["nickname"].string); Spacer(); Text(String(repeating: "★", count: min(5, max(0, review["rating"].int)))).foregroundStyle(.orange) }; Text(review["content"].string); Text(review["created_at"].string).font(.caption).foregroundStyle(.secondary) } }; if rows.isEmpty { EmptyState(title: "暂无评价") } }.navigationTitle("商品评价").task { do { rows = try await APIClient.shared.get("product.reviews", ["product_id": String(productID)]).list } catch { self.error = error.localizedDescription } }.errorAlert($error) }
}
