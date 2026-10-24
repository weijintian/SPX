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
    @State private var busy = true
    @State private var error: String?
    @State private var loadID = UUID()
    @State private var usd = ""
    @State private var visible = false
    @Environment(\.scenePhase) private var phase
    private var catalogHeader: some View {
        VStack(alignment: .leading, spacing: 18) {
        HStack(spacing: 12) { SPXIcon("search").frame(width: 20, height: 20).foregroundStyle(SPXColor.muted); TextField("搜索商品", text: $search).font(.system(size: 14)); if !search.isEmpty { Button { search = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(SPXColor.muted) } } }.padding(13).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 14))
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
        if busy && products.isEmpty { ProgressView("正在加载商品…").frame(maxWidth: .infinity).padding(20) }
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
        catalogContent.navigationTitle(merchantID > 0 ? "店铺商品" : "商城")
            .toolbar { ToolbarItemGroup(placement: .topBarTrailing) { NavigationLink { FavoritesView() } label: { Image(systemName: "heart") }; NavigationLink { OrdersView() } label: { SPXIcon("order").frame(width: 22, height: 22) }; NavigationLink { ShopsView() } label: { SPXIcon("store").frame(width: 22, height: 22) } } }
            .spxPage().refreshable { await load(reset: true) }

    }
    var body: some View {
        navigatedCatalog
            .task { if let config = try? await APIClient.shared.get("mall.config") { categories = config["categories"].array.map(\.string) } }
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
            CachedImage(source: product.image).aspectRatio(1, contentMode: .fit).frame(maxWidth: .infinity).background(.white).clipped()
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
                Text("¥\(DisplayLabels.amount(cny))").font(.title3.bold()).foregroundStyle(SPXColor.primary)
                if let estimate { Text("≈ \(DisplayLabels.amount(estimate)) SPX001").font(.caption).foregroundStyle(.secondary) }
            } else {
                Text("\(DisplayLabels.amount(legacy)) SPX001").font(.headline).foregroundStyle(SPXColor.primary)
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
                    .tabViewStyle(.page).aspectRatio(1, contentMode: .fit).background(.white)
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
                }.padding().background(SPXColor.card)
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
    var body: some View {
        MediaPage(message: Record(["message_type": "video", "media_url": .string(source)]))
            .background(.black).clipShape(RoundedRectangle(cornerRadius: 14))
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
        SPXDetailPage {
            SPXPageHeading(title: "优选店铺", subtitle: "发现好店，浏览店铺商品与服务", icon: "store")
            ForEach(rows) { shop in
                NavigationLink { ShopDetailView(id: shop.id) } label: {
                    SPXCard { HStack(spacing: 14) {
                        Avatar(url: shop.avatar, name: shop.name, size: 56)
                        VStack(alignment: .leading, spacing: 7) { Text(shop.name).font(.system(size: 17, weight: .semibold)).foregroundStyle(SPXColor.ink); if !shop["category"].string.isEmpty { Text(shop["category"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted) } }
                        Spacer(minLength: 8); SPXIcon("chevron").frame(width: 14, height: 14).foregroundStyle(SPXColor.muted)
                    } }
                }.buttonStyle(.plain)
            }
            if busy && rows.isEmpty { ProgressView("正在加载店铺…").frame(maxWidth: .infinity).padding(24) }
            if !busy && rows.isEmpty && error == nil { EmptyState(title: query.isEmpty ? "暂无店铺" : "没有找到相关店铺", description: "试试其他店铺名称或关键词", icon: "storefront") }
            if more && !rows.isEmpty { Button(busy ? "正在加载…" : "加载更多") { Task { await load(reset: false) } }.frame(maxWidth: .infinity, minHeight: 44).disabled(busy) }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load(reset: true) } }.frame(maxWidth: .infinity, minHeight: 44).disabled(busy) }
        }.navigationTitle("优选店铺").searchable(text: $query, prompt: "搜索店铺")
            .task(id: query) { do { try await Task.sleep(for: .milliseconds(300)); await load(reset: true) } catch { } }
            .refreshable { await load(reset: true) }
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
    @State private var loading = true
    var body: some View {
        SPXDetailPage {
            if shop.id > 0 {
                SPXCard { VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 16) { Avatar(url: shop.avatar, name: shop.name, size: 72); VStack(alignment: .leading, spacing: 8) { Text(shop.name).font(.system(size: 23, weight: .bold)).foregroundStyle(SPXColor.ink); if !shop["category"].string.isEmpty { SPXStatusPill(title: shop["category"].string, color: SPXColor.commerce) } } }
                    if !shop["description"].string.isEmpty { Divider(); Text(shop["description"].string).font(.system(size: 14)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true) }
                } }
                SPXSectionTitle(title: "门店信息")
                SPXCard { VStack(alignment: .leading, spacing: 16) { InfoRow(title: "营业时间", value: shop["business_hours"].string); Divider(); InfoRow(title: "门店地址", value: shop["address"].string); ShopNavigationButton(shop: shop) } }
                if let url = URL(string: "tel:\(shop["contact_phone"].string)"), !shop["contact_phone"].string.isEmpty {
                    Link(destination: url) { SPXCard { SPXSettingsRow(title: "联系店铺", icon: "phone", detail: shop["contact_phone"].string) } }.buttonStyle(.plain)
                }
            } else if loading { ProgressView("正在读取店铺资料…").frame(maxWidth: .infinity).padding(32) }
            if let error { ErrorBanner(message: error); Button("重新读取店铺") { Task { await load() } }.frame(maxWidth: .infinity, minHeight: 44).disabled(loading) }
        }.navigationTitle("店铺").safeAreaInset(edge: .bottom) {
            if shop.id > 0 { SPXBottomAction { NavigationLink { MallHomeView(merchantID: id) } label: { Label("浏览店铺商品", systemImage: "bag") } } }
        }.task { await load() }.refreshable { await load() }
    }
    private func load() async {
        loading = true; defer { loading = false }
        do { shop = try await APIClient.shared.get("shop", ["id": String(id)])["shop"].record; error = nil }
        catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
}

struct ProductReviewsView: View {
    let productID: Int
    @State private var rows: [Record] = []
    @State private var error: String?
    @State private var loading = true
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "商品评价", subtitle: "查看买家的使用反馈", icon: "star")
            ForEach(rows.indices, id: \.self) { index in
                let review = rows[index]
                SPXCard { VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) { Avatar(url: review.avatar, name: review["nickname"].string, size: 40); VStack(alignment: .leading, spacing: 6) { Text(review["nickname"].string).font(.system(size: 14, weight: .semibold)); HStack(spacing: 3) { ForEach(0..<5, id: \.self) { star in Image(systemName: star < min(5, max(0, review["rating"].int)) ? "star.fill" : "star").font(.system(size: 11)).foregroundStyle(.orange) } }.accessibilityLabel("\(min(5, max(0, review["rating"].int))) 星评价") }; Spacer(minLength: 0) }
                    Text(review["content"].string).font(.system(size: 15)).foregroundStyle(SPXColor.ink).fixedSize(horizontal: false, vertical: true)
                    Text(review["created_at"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                } }
            }
            if loading { ProgressView("正在读取评价…").frame(maxWidth: .infinity).padding(24) }
            if !loading && rows.isEmpty && error == nil { EmptyState(title: "暂无评价", description: "买家完成订单后可留下评价", icon: "star.bubble") }
            if let error { ErrorBanner(message: error); Button("重新读取评价") { Task { await load() } }.frame(maxWidth: .infinity, minHeight: 44).disabled(loading) }
        }.navigationTitle("商品评价").task { await load() }.refreshable { await load() }
    }
    private func load() async {
        loading = true; defer { loading = false }
        do { rows = try await APIClient.shared.get("product.reviews", ["product_id": String(productID)]).list; error = nil }
        catch is CancellationError { }
        catch { self.error = error.localizedDescription }
    }
}
