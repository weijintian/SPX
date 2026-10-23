import SwiftUI

extension Notification.Name {
    static let spxMerchantUpdated = Notification.Name("spx.merchant.updated")
    static let spxMerchantProductsUpdated = Notification.Name("spx.merchant.products.updated")
}

struct MerchantOverviewView: View {
    @State private var merchant = Record()
    @State private var performance = Record()
    @State private var stats = Record()
    @State private var required = ""
    @State private var loaded = false
    @State private var error: String?
    @State private var deposit = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if !loaded { ProgressView("加载经营数据…").frame(maxWidth: .infinity) }
                else if merchant.id == 0 {
                    SPXCard { VStack(alignment: .leading, spacing: 16) { Image(systemName: "storefront").font(.system(size: 42)).foregroundStyle(SPXColor.primary); Text("把好商品带给更多人").font(.title2.bold()); Text("提交店铺资料，审核通过后开启经营。").foregroundStyle(.secondary); NavigationLink("申请开店") { MerchantProfileEditor(merchant: merchant, application: true) }.buttonStyle(.borderedProminent) } }
                } else {
                    HStack(spacing: 14) { Avatar(url: merchant.avatar, name: merchant.name, size: 58); VStack(alignment: .leading, spacing: 6) { Text(merchant.name).font(.title2.bold()); Text("店铺状态：\(DisplayLabels.merchant(merchant["status"].string))").font(.caption).foregroundStyle(.secondary) }; Spacer() }
                    if !merchant["review_reason"].string.isEmpty { ErrorBanner(message: merchant["review_reason"].string) }
                    if ["rejected", "closed"].contains(merchant["status"].string) {
                        NavigationLink { MerchantProfileEditor(merchant: merchant, application: true) } label: { Label("完善资料并重新申请", systemImage: "square.and.pencil") }.buttonStyle(.borderedProminent)
                    }
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        NavigationLink { MerchantOrdersView() } label: { Metric(title: "近 30 天订单", value: performance["orders_30_days"].string, icon: "shippingbox") }
                        NavigationLink { MerchantProductsView() } label: { Metric(title: "商品管理", value: "查看商品", icon: "square.grid.2x2") }
                        NavigationLink { MerchantOrdersView(onlyAfterSale: true) } label: { Metric(title: "售后请求", value: performance["after_sale_count"].string, icon: "arrow.triangle.2.circlepath") }
                        NavigationLink { RecordsView(title: "经营财务", action: "merchant.finance") } label: { Metric(title: "已结算 SPX001", value: stats["settled_amount"].string, icon: "chart.line.uptrend.xyaxis") }
                    }.buttonStyle(.plain)
                    NavigationLink { PublishProductView() } label: { HStack { Image(systemName: "plus.app").font(.title2); VStack(alignment: .leading) { Text("发布新商品").font(.headline); Text("批量主图 · CNY 定价 · 多规格").font(.caption).opacity(0.8) }; Spacer(); Image(systemName: "arrow.right") }.padding(20).foregroundStyle(.white).background(LinearGradient(colors: [SPXColor.primary, Color(red: 0.16, green: 0.48, blue: 0.98)], startPoint: .leading, endPoint: .trailing), in: RoundedRectangle(cornerRadius: 22)) }
                    SPXCard {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("经营工具").font(.headline)
                            NavigationLink("商品草稿", systemImage: "doc.text") { MerchantDraftsView() }
                            NavigationLink("店铺资料", systemImage: "storefront") { MerchantProfileEditor(merchant: merchant, application: false) }
                            NavigationLink("操作记录", systemImage: "clock.arrow.circlepath") { RecordsView(title: "操作记录", action: "merchant.audit") }
                            InfoRow(title: "待结算 SPX001", value: stats["held_amount"].string)
                            InfoRow(title: "已冻结保证金", value: merchant["deposit_frozen"].string)
                            if merchant["status"].string == "awaiting_deposit" { Button("缴纳保证金 \(required) SPX001") { deposit = true } }
                        }
                    }
                }
                if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } } }
            }.padding(18)
        }.navigationTitle("经营概览").spxPage().task { await load() }.refreshable { await load() }
            .onReceive(NotificationCenter.default.publisher(for: .spxMerchantUpdated)) { _ in Task { await load() } }
            .confirmationDialog("确认缴纳开店保证金？", isPresented: $deposit, titleVisibility: .visible) { Button("确认缴纳") { Task { do { try await APIClient.shared.post("merchant.deposit.pay", ["level": merchant["level"]]); await load() } catch { self.error = error.localizedDescription } } } }
    }
    private func load() async {
        do { let data = try await APIClient.shared.get("merchant.me"); merchant = data["merchant"].record; stats = data["statistics"].record; required = data["required_deposit"].string; loaded = true; if merchant.id > 0 { performance = try await APIClient.shared.get("merchant.performance").record }; error = nil }
        catch { loaded = true; self.error = error.localizedDescription }
    }
}

struct Metric: View {
    let title: String
    let value: String
    var icon: String = "chart.bar"
    var body: some View { SPXCard { VStack(alignment: .leading, spacing: 12) { HStack { Image(systemName: icon).foregroundStyle(SPXColor.primary); Spacer(); Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary) }; Text(value.isEmpty ? "—" : value).font(.title3.bold()).foregroundStyle(.primary); Text(title).font(.caption).foregroundStyle(.secondary) } } }
}

struct MerchantProductsView: View {
    @State private var products: [Record] = []
    @State private var page = 1
    @State private var more = true
    @State private var loadID = UUID()
    @State private var busy = false
    @State private var error: String?
    @State private var offline: Record?
    @State private var selecting = false
    @State private var selected = Set<Int>()
    @State private var batchConfirm = false
    @State private var submitting = false
    var body: some View {
        List {
            ForEach(products) { product in
                VStack(alignment: .leading, spacing: 10) {
                    if selecting { Button { toggle(product.id) } label: { Label(selected.contains(product.id) ? "已选择" : "选择商品", systemImage: selected.contains(product.id) ? "checkmark.circle.fill" : "circle") }.disabled(submitting) }
                    HStack { CachedImage(source: product.image).frame(width: 66, height: 66); VStack(alignment: .leading) { Text(product.name).font(.headline); Text("¥\(product["cny_price"].string) · 库存 \(product["stock"].string)").font(.caption); Text(DisplayLabels.review(product["review_status"].string)).font(.caption).foregroundStyle(.secondary) } }
                    if !selecting { HStack { NavigationLink("编辑商品") { PublishProductView(product: product) }; NavigationLink("调整库存") { InventoryEditor(product: product) }; Spacer(); Button("下架", role: .destructive) { offline = product } }.font(.caption) }
                }.padding(.vertical, 8)
            }
            if more { Button(busy ? "正在加载…" : "加载更多") { Task { await load(reset: false) } }.disabled(busy) }
            if products.isEmpty { EmptyState(title: "暂无商品", icon: "square.grid.2x2") }
        }.navigationTitle("商品管理").toolbar {
            ToolbarItem(placement: .topBarTrailing) { Button(selecting ? "完成" : "批量管理") { selecting.toggle(); selected = [] }.disabled(submitting) }
            ToolbarItem(placement: .topBarTrailing) { NavigationLink { PublishProductView() } label: { Image(systemName: "plus") } }
        }.safeAreaInset(edge: .bottom) {
            if selecting { HStack { Text("已选 \(selected.count)/50").font(.subheadline); Spacer(); Button(submitting ? "下架中…" : "批量下架", role: .destructive) { batchConfirm = true }.disabled(selected.isEmpty || submitting) }.padding(16).background(.bar) }
        }.task { await load(reset: true) }.refreshable { await load(reset: true) }.errorAlert($error)
            .onReceive(NotificationCenter.default.publisher(for: .spxMerchantProductsUpdated)) { _ in Task { await load(reset: true) } }
            .confirmationDialog("确认下架已选的 \(selected.count) 件商品？", isPresented: $batchConfirm, titleVisibility: .visible) { Button("确认下架", role: .destructive) { batchOffline() } }
            .confirmationDialog("确认下架商品？", isPresented: Binding(get: { offline != nil }, set: { if !$0 { offline = nil } }), titleVisibility: .visible) { Button("下架", role: .destructive) { if let offline { Task { do { try await APIClient.shared.post("merchant.product.offline", ["id": offline["id"]]); await load(reset: true) } catch { self.error = error.localizedDescription } }; self.offline = nil } } }
    }
    private func toggle(_ id: Int) {
        if selected.contains(id) { selected.remove(id) }
        else if selected.count < 50 { selected.insert(id) }
        else { error = "一次最多选择 50 件商品" }
    }
    private func batchOffline() {
        guard !submitting, !selected.isEmpty else { return }
        submitting = true; let ids = selected.sorted()
        Task {
            defer { submitting = false }
            do {
                try await APIClient.shared.post("merchant.products.offline", ["ids": .array(ids.map { .number(Decimal($0)) })])
                selected = []; selecting = false; await load(reset: true)
            } catch { self.error = error.localizedDescription }
        }
    }
    private func load(reset: Bool) async {
        if !reset && busy { return }
        let requestID = UUID(); loadID = requestID; busy = true
        defer { if loadID == requestID { busy = false } }
        let next = reset ? 1 : page + 1
        do {
            let list = try await APIClient.shared.get("merchant.products", ["page": String(next), "page_size": "30"]).list
            guard loadID == requestID, !Task.isCancelled else { return }
            if reset { products = list } else { let ids = Set(products.map(\.id)); products += list.filter { !ids.contains($0.id) } }
            page = next; more = list.count == 30; error = nil
        } catch is CancellationError { } catch { if loadID == requestID { self.error = error.localizedDescription } }
    }
}

struct MerchantDraftsView: View {
    @State private var rows: [Record] = []
    @State private var error: String?
    var body: some View { List { ForEach(rows) { draft in NavigationLink(draft.name) { DraftLoader(id: draft.id) }.swipeActions { Button("删除", role: .destructive) { Task { do { try await APIClient.shared.post("merchant.draft.delete", ["id": draft["id"]]); await load() } catch { self.error = error.localizedDescription } } } } } }.navigationTitle("商品草稿").task { await load() }.refreshable { await load() }.errorAlert($error).onReceive(NotificationCenter.default.publisher(for: .spxMerchantProductsUpdated)) { _ in Task { await load() } } }
    private func load() async { do { rows = try await APIClient.shared.get("merchant.drafts").list } catch { self.error = error.localizedDescription } }
}
struct DraftLoader: View {
    let id: Int
    @State private var draft: JSON = .null
    @State private var error: String?
    var body: some View { Group { if !draft.object.isEmpty { PublishProductView(product: draft["payload"].record, draftID: id, draftVersion: draft["version"].int) } else if let error { EmptyState(title: "草稿加载失败", description: error) } else { ProgressView() } }.task { do { draft = try await APIClient.shared.get("merchant.draft", ["id": String(id)]) } catch { self.error = error.localizedDescription } } }
}
