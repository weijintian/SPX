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
    @State private var selectedImage = 0
    @State private var previewImages = false
    @State private var selectedSection = "details"
    @State private var loading = false
    @State private var contacting = false
    @State private var merchantChat: Record?
    @Environment(\.scenePhase) private var phase
    private var current: Record { detail.id > 0 ? detail : product }
    private var images: [String] { let urls = current["images"].array.map(\.string).filter { !$0.isEmpty }; return urls.isEmpty ? [current.image] : urls }
    private var shop: JSON { current["shop"] }
    private var group: JSON { current["consultation_group"] }
    private var physical: Bool { current["product_type"].string == "physical" }
    private var local: Bool { current["product_type"].string == "local" }
    private var fulfillment: String {
        if local { return "\(current["service_valid_days"] == .null ? 30 : current["service_valid_days"].int) 天内有效" }
        let days = current["delivery_days"] == .null ? 3 : current["delivery_days"].int
        return (days == 0 ? "当日" : "\(days) 天内") + (physical ? "发货" : "交付")
    }
    private var deliveryFee: String {
        if physical { return Money.positive(current["shipping_fee"].string) ? "运费 \(DisplayLabels.amount(current["shipping_fee"].string)) SPX001" : "包邮" }
        return local ? (current["appointment_required"].flag ? "需预约" : "到店使用") : "线上交付"
    }
    private var carousel: some View {
        GeometryReader { geometry in
            TabView(selection: $selectedImage) {
                ForEach(Array(images.enumerated()), id: \.offset) { index, url in
                    CachedImage(source: url, fit: true, maximumPixelSize: 1200)
                        .frame(width: geometry.size.width, height: geometry.size.height, alignment: .center)
                        .background(.white).contentShape(Rectangle()).onTapGesture { previewImages = true }.tag(index)
                }
            }.tabViewStyle(.page(indexDisplayMode: .never))
        }.aspectRatio(1, contentMode: .fit).background(.white)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .overlay(alignment: .bottomTrailing) {
                Text("\(min(selectedImage + 1, images.count)) / \(images.count)").font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white).padding(.horizontal, 10).padding(.vertical, 5)
                    .background(.black.opacity(0.45), in: Capsule()).padding(12).allowsHitTesting(false)
            }
    }
    private var summaryCard: some View {
        SPXCard { VStack(alignment: .leading, spacing: 14) {
            PriceLabel(cny: current["cny_price"].string, estimate: Money.estimate(cny: current["cny_price"].string, usd: usd), legacy: current["spx_price"].string)
            Text(current.name).font(.system(size: 21, weight: .bold)).foregroundStyle(SPXColor.ink)
            Text(current["description"].string).font(.system(size: 14)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
            Divider()
            HStack(spacing: 8) {
                Text(fulfillment).frame(maxWidth: .infinity)
                Text(deliveryFee).frame(maxWidth: .infinity)
                Text("库存 \(current["stock"].int) 件").frame(maxWidth: .infinity)
            }.font(.system(size: 11)).foregroundStyle(SPXColor.muted)
        } }
    }
    private var consultationCard: some View {
        Group {
            if group["id"].int > 0, !group["group_code"].string.isEmpty {
                NavigationLink { GroupJoinView(initialCode: group["group_code"].string) } label: {
                    HStack(spacing: 12) {
                        SPXIcon("chat").frame(width: 28, height: 28).foregroundStyle(SPXColor.commerce)
                        VStack(alignment: .leading, spacing: 6) {
                            Text("加入商品群").font(.system(size: 15, weight: .semibold)).foregroundStyle(SPXColor.ink)
                            Text(group["name"].string + " · 购买前问一问").font(.system(size: 12)).foregroundStyle(SPXColor.muted).lineLimit(2)
                            Text("群号 " + group["group_code"].string).font(.system(size: 11)).foregroundStyle(SPXColor.muted)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        Text("加入").font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                            .padding(.horizontal, 16).frame(minHeight: 36).background(SPXColor.commerce, in: RoundedRectangle(cornerRadius: 10))
                    }.padding(16).background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 16))
                }.buttonStyle(.plain).accessibilityLabel("商品咨询群，\(group["name"].string)，点击加入")
            } else {
                HStack(spacing: 12) {
                    SPXIcon("chat").frame(width: 24, height: 24).foregroundStyle(SPXColor.muted)
                    VStack(alignment: .leading, spacing: 5) {
                        Text("商品咨询群").font(.system(size: 14, weight: .medium)).foregroundStyle(SPXColor.ink)
                        Text(loading ? "正在读取咨询群…" : "商家暂未设置可用咨询群，可联系商家咨询").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                    }
                    Spacer(minLength: 0)
                }.padding(16).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }
    private var shopCard: some View {
        NavigationLink { ShopDetailView(id: shop["id"].int) } label: {
            SPXCard { VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    Avatar(url: shop["logo_url"].string, name: shop["name"].string, size: 44)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(shop["name"].string).font(.system(size: 16, weight: .semibold)).foregroundStyle(SPXColor.ink)
                        if !shop["business_hours"].string.isEmpty { Text("服务时间 " + shop["business_hours"].string).font(.system(size: 11)).foregroundStyle(SPXColor.muted) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Text(shop["level"].string == "gold" ? "黄金店铺" : "初级店铺").font(.system(size: 10, weight: .medium)).foregroundStyle(SPXColor.commerce)
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(SPXColor.muted)
                }
                if !shop["address"].string.isEmpty { Text(shop["address"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted) }
            } }
        }.buttonStyle(.plain)
    }
    @ViewBuilder private var sectionContent: some View {
        if selectedSection == "details" {
            SPXCard { Text(current.text("detail_text", "description")).font(.system(size: 14)).foregroundStyle(SPXColor.ink).textSelection(.enabled).fixedSize(horizontal: false, vertical: true) }
            ForEach(Array(current["detail_images"].array.enumerated()), id: \.offset) { _, image in
                CachedImage(source: image.string, fit: true, maximumPixelSize: 1200).frame(maxWidth: .infinity).background(.white).clipShape(RoundedRectangle(cornerRadius: 12))
            }
            if !current["video_url"].string.isEmpty { ProductVideo(source: current["video_url"].string).frame(height: 220) }
            NavigationLink { ProductReviewsView(productID: current.id) } label: { SPXCard { SPXSettingsRow(title: "查看买家评价", icon: "star", detail: "了解购买体验") } }.buttonStyle(.plain)
        } else if selectedSection == "parameters" {
            SPXCard { VStack(alignment: .leading, spacing: 12) {
                SPXSectionTitle(title: "规格参数")
                Text(current["parameters"].string.isEmpty ? "商家暂未填写更多参数，可查看商品规格或加入商品群咨询。" : current["parameters"].string).font(.system(size: 14)).foregroundStyle(SPXColor.muted).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            } }
        } else {
            SPXCard { VStack(alignment: .leading, spacing: 14) {
                SPXSectionTitle(title: "配送与使用")
                Text(fulfillment + " · " + deliveryFee).font(.system(size: 14)).foregroundStyle(SPXColor.ink)
                if local { Text("到店接受服务时出示订单核销码").font(.system(size: 13)).foregroundStyle(SPXColor.muted) }
                if !current["delivery_note"].string.isEmpty { Text(current["delivery_note"].string).font(.system(size: 13)).foregroundStyle(SPXColor.muted) }
                Divider()
                SPXSectionTitle(title: "售后与服务")
                Text(current["service_terms"].string.isEmpty ? "购买前请核对规格和服务说明，售后可在订单详情中申请。" : current["service_terms"].string).font(.system(size: 14)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
                Text("SPX001 为实时估算，1 USD 按 6.7 CNY 换算，最终支付以确认订单的锁定报价为准。").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
            } }
        }
    }
    private var purchaseBar: some View {
        HStack(spacing: 12) {
            if shop["id"].int > 0 {
                NavigationLink { ShopDetailView(id: shop["id"].int) } label: { footerLabel("店铺", icon: "store") }
                Button { contactMerchant() } label: { footerLabel(contacting ? "连接中" : "联系商家", icon: "chat") }.disabled(contacting || shop["owner_user_id"].int < 1)
            }
            Button { toggleFavorite() } label: { footerLabel("收藏", icon: favorite ? "heart.fill" : "heart") }.disabled(favoriteBusy)
            NavigationLink { CheckoutView(product: current) } label: {
                Text(current["stock"].int > 0 ? "选择规格 · 立即购买" : "暂时缺货").font(.system(size: 15, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 48).foregroundStyle(.white)
                    .background(SPXColor.commerce.opacity(current["stock"].int > 0 ? 1 : 0.5), in: RoundedRectangle(cornerRadius: 14))
            }.disabled(loading || detail.id == 0 || current["stock"].int < 1)
        }.buttonStyle(.plain).padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 8).background(SPXColor.card)
    }
    private func footerLabel(_ title: String, icon: String) -> some View {
        VStack(spacing: 4) { SPXIcon(icon).frame(width: 21, height: 21); Text(title).font(.system(size: 10)) }
            .foregroundStyle(SPXColor.muted).frame(minWidth: 32, minHeight: 48)
    }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                carousel
                summaryCard
                NavigationLink { CheckoutView(product: current) } label: { SPXCard { SPXSettingsRow(title: "选择规格与数量", icon: "square.grid.2x2", detail: current["variants"].array.isEmpty ? "默认规格" : "\(current["variants"].array.count) 种规格可选") } }.buttonStyle(.plain).disabled(loading || detail.id == 0 || current["stock"].int < 1)
                consultationCard
                if shop["id"].int > 0 { shopCard }
                SPXFilterTabs(options: [("details", "商品详情"), ("parameters", "规格参数"), ("service", "购买须知")], selection: $selectedSection)
                sectionContent
                if let error { ErrorBanner(message: error); Button("重新读取商品") { Task { await loadProduct() } }.disabled(loading).frame(maxWidth: .infinity, minHeight: 44) }
            }.padding(16)
        }.spxPage().navigationTitle("商品详情").navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom, spacing: 0) { purchaseBar }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Menu { Button("分享给朋友", systemImage: "bubble.left") { shareInChat = true }; if let url = URL(string: "https://spx001.top/spxchat/product-share.php?id=\(current.id)") { ShareLink(item: url) }; Button("举报商品") { report = true } } label: { Image(systemName: "ellipsis") } } }
            .sheet(isPresented: $shareInChat) { ForwardMessageView(message: Record(["message_type": "product", "content": .string(String(data: (try? JSONEncoder().encode(Record(["product_id": current["id"]]))) ?? Data(), encoding: .utf8) ?? "")])) }
            .alert("举报商品", isPresented: $report) { TextField("原因", text: $reason); Button("提交") { Task { do { try await APIClient.shared.post("product.report", ["product_id": .number(Decimal(current.id)), "reason": .string(reason)]) } catch { self.error = error.localizedDescription } } }; Button("取消", role: .cancel) { } }
            .task(id: product.id) { await loadProduct(); await loadFavorite() }
            .refreshable { await loadProduct() }
            .navigationDestination(item: $merchantChat) { ChatDetailView(item: $0, isGroup: false) }
            .fullScreenCover(isPresented: $previewImages) { MediaPreview(messages: images.enumerated().map { index, url in Record(["id": .number(Decimal(index + 1)), "message_type": "image", "media_url": .string(url)]) }, initialID: selectedImage + 1) }
            .onAppear { visible = true }.onDisappear { visible = false }
            .task(id: String(describing: phase) + String(visible)) { guard visible, phase == .active else { return }; while !Task.isCancelled { do { usd = try await APIClient.shared.get("mall.valuation")["spx_usdt"].string; try await Task.sleep(for: .seconds(30)) } catch is CancellationError { return } catch { self.error = error.localizedDescription; do { try await Task.sleep(for: .seconds(30)) } catch { return } } } }
    }
    private func loadProduct() async {
        guard !loading else { return }
        loading = true; defer { loading = false }
        do {
            let value = try await APIClient.shared.get("product", ["id": String(product.id)]).record
            guard value.id > 0 else { throw APIError.invalidResponse }
            detail = value; selectedImage = min(selectedImage, max(0, images.count - 1)); error = nil
        } catch is CancellationError { return } catch { self.error = error.localizedDescription; return }
    }
    private func loadFavorite() async {
        do { favorite = try await APIClient.shared.get("product.favorite.status", ["product_id": String(product.id)])["enabled"].flag }
        catch is CancellationError { } catch { self.error = error.localizedDescription }
    }
    private func contactMerchant() {
        guard !contacting, shop["owner_user_id"].int > 0 else { return }
        contacting = true
        Task {
            defer { contacting = false }
            do {
                let result = try await APIClient.shared.post("chats.create", ["peer_user_id": shop["owner_user_id"]])
                guard result["chat_id"].int > 0 else { throw APIError.invalidResponse }
                merchantChat = Record(["id": result["chat_id"], "peer_user_id": shop["owner_user_id"], "nickname": shop["name"], "avatar_url": shop["logo_url"]])
            } catch { self.error = error.localizedDescription }
        }
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
