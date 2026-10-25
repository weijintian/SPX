import SwiftUI

extension Notification.Name {
    static let spxMerchantUpdated = Notification.Name("spx.merchant.updated")
    static let spxMerchantProductsUpdated = Notification.Name("spx.merchant.products.updated")
}

struct MerchantOverviewView: View {
    @State private var merchant = Record()
    @State private var stats = Record()
    @State private var capacity = Record()
    @State private var required = ""
    @State private var loaded = false
    @State private var loading = false
    @State private var submitting = false
    @State private var loadID = UUID()
    @State private var error: String?
    @State private var deposit = false
    @State private var closing = false
    @State private var depositLevel = "basic"
    private var active: Bool { merchant["status"].string == "active" }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !loaded { ProgressView("正在读取店铺信息…").frame(maxWidth: .infinity).padding(32) }
                else if merchant.id == 0 {
                    SPXCard { VStack(alignment: .leading, spacing: 12) { SPXIcon("store").frame(width: 40, height: 40).foregroundStyle(SPXColor.primary); Text("开始经营你的店铺").font(.title3.bold()); Text("提交资料并通过审核后，就可以发布商品、接收订单和管理咨询群。").font(.subheadline).foregroundStyle(SPXColor.muted); NavigationLink("申请开店") { MerchantProfileEditor(merchant: merchant, application: true) }.buttonStyle(SPXPrimaryButtonStyle()) } }
                } else {
                    SPXCard {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack { Text(merchant.name).font(.system(size: 20, weight: .semibold)); Spacer(); Text(DisplayLabels.merchant(merchant["status"].string)).font(.system(size: 12, weight: .medium)).foregroundStyle(SPXColor.primary).padding(7).background(SPXColor.tint, in: Capsule()) }
                            HStack { Text(merchant["level"].string == "gold" ? "黄金店铺" : "初级店铺"); Spacer(); Text("冻结保证金 " + DisplayLabels.amount(merchant["deposit_frozen"].string) + " SPX001").foregroundStyle(SPXColor.primary) }.font(.system(size: 12))
                            MerchantProductCapacity(capacity: capacity)
                            if !merchant["review_reason"].string.isEmpty { Text("审核说明 " + merchant["review_reason"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted) }
                        }
                    }
                    SPXCard {
                        VStack(spacing: 16) {
                            SPXSectionTitle(title: "经营数据", detail: "下拉刷新")
                            HStack(spacing: 12) { NavigationLink { RecordsView(title: "经营财务", action: "merchant.finance") } label: { metric("待结算净额", DisplayLabels.amount(stats["held_amount"].string)) }; NavigationLink { MerchantOrdersView() } label: { metric("订单总数", stats["order_count"].string) } }
                            HStack(spacing: 12) { NavigationLink { RecordsView(title: "经营财务", action: "merchant.finance") } label: { metric("已到账净额", DisplayLabels.amount(stats["settled_amount"].string)) }; NavigationLink { MerchantProductsView() } label: { metric("商品管理", "进入", actionable: true) } }
                        }
                    }
                    SPXCard {
                        VStack(spacing: 16) {
                            SPXSectionTitle(title: "快捷入口", detail: "常用操作")
                            HStack(spacing: 10) {
                                if active { NavigationLink { PublishProductView() } label: { shortcut("发布商品", "gift") } }
                                NavigationLink { MerchantProductsView() } label: { shortcut("商品管理", "eco") }
                                NavigationLink { MerchantOrdersView() } label: { shortcut("订单与售后", "order") }
                            }
                        }
                    }
                    SPXCard {
                        VStack(alignment: .leading, spacing: 4) {
                            SPXSectionTitle(title: "订单与售后", detail: "及时处理")
                            NavigationLink { MerchantOrdersView() } label: {
                                HStack { Label("查看订单与发货", systemImage: "shippingbox"); Spacer(); Image(systemName: "chevron.right").font(.caption) }.frame(minHeight: 48)
                            }
                            Divider()
                            NavigationLink { MerchantOrdersView(onlyAfterSale: true) } label: {
                                HStack { Label("处理售后申请", systemImage: "arrow.triangle.2.circlepath"); Spacer(); Image(systemName: "chevron.right").font(.caption) }.frame(minHeight: 48)
                            }
                        }.font(.system(size: 14)).foregroundStyle(SPXColor.ink)
                    }
                    NavigationLink { MerchantDraftsView() } label: { SPXMenuRow(title: "商品草稿", subtitle: "继续编辑尚未提交的商品", icon: "doc.text") }
                    if ["rejected", "closed"].contains(merchant["status"].string) { NavigationLink { MerchantProfileEditor(merchant: merchant, application: true) } label: { SPXMenuRow(title: "修改资料并重新申请", icon: "square.and.pencil") } }
                    if active || merchant["status"].string == "awaiting_deposit" {
                        NavigationLink { MerchantProfileEditor(merchant: merchant, application: false) } label: { SPXMenuRow(title: "编辑店铺与门店位置", icon: "store") }
                        if merchant["status"].string == "awaiting_deposit" { Button { depositLevel = merchant["level"].string; deposit = true } label: { SPXMenuRow(title: "缴纳保证金并开店", subtitle: required + " SPX001", icon: "lock.shield") }.disabled(submitting) }
                        if active && merchant["level"].string != "gold" { Button { depositLevel = "gold"; deposit = true } label: { SPXMenuRow(title: "升级黄金店铺", subtitle: "保证金差额以服务器核对为准", icon: "star") }.disabled(submitting) }
                        NavigationLink { ShopDetailView(id: merchant.id) } label: { SPXMenuRow(title: "查看我的店铺", icon: "store") }
                        Button { closing = true } label: { SPXMenuRow(title: "申请关店", subtitle: "订单及售后结束后按规则结算保证金", icon: "xmark.circle", color: .red) }.disabled(submitting)
                    }
                    NavigationLink { RecordsView(title: "操作记录", action: "merchant.audit") } label: { SPXMenuRow(title: "操作记录", icon: "clock.arrow.circlepath") }
                }
                if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } } }
                if submitting { ProgressView("正在提交…").frame(maxWidth: .infinity) }
            }.buttonStyle(.plain).padding(16)
        }.navigationTitle("经营概览").spxPage().task { await load() }.refreshable { await load() }
            .onReceive(NotificationCenter.default.publisher(for: .spxMerchantUpdated)) { _ in Task { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: .spxMerchantProductsUpdated)) { _ in Task { await load() } }
            .confirmationDialog("确认冻结保证金？", isPresented: $deposit, titleVisibility: .visible) { Button("确认缴纳") { mutate("merchant.deposit.pay", ["level": .string(depositLevel)]) }; Button("取消", role: .cancel) { } } message: { Text(depositLevel == "gold" ? "黄金店铺需冻结合计 1,000,000 SPX001，系统只扣除尚未交齐的差额。" : "初级店铺需冻结 100,000 SPX001。") }
            .confirmationDialog("申请关店后商品将下架并停止接单，订单、售后和结算完成后按规则退还保证金。", isPresented: $closing, titleVisibility: .visible) { Button("申请关店", role: .destructive) { mutate("merchant.close") }; Button("取消", role: .cancel) { } }
    }
    private func metric(_ title: String, _ value: String, actionable: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 12)).foregroundStyle(SPXColor.muted)
            HStack(spacing: 6) {
                Text(value.isEmpty ? "—" : value).font(.system(size: 18, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.75)
                if actionable { Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)) }
            }.foregroundStyle(SPXColor.primary)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .padding(.horizontal, 10).padding(.vertical, 9)
        .background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 12))
        .contentShape(RoundedRectangle(cornerRadius: 12))
    }
    private func shortcut(_ title: String, _ icon: String) -> some View {
        VStack(spacing: 6) {
            SPXIcon(icon).frame(width: 22, height: 22).foregroundStyle(SPXColor.primary)
            Text(title).font(.system(size: 12)).foregroundStyle(SPXColor.ink).lineLimit(1).minimumScaleFactor(0.85)
        }
        .frame(maxWidth: .infinity).frame(height: 76)
        .background(SPXColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(SPXColor.line, lineWidth: 0.7))
        .contentShape(RoundedRectangle(cornerRadius: 12))
    }
    private func mutate(_ action: String, _ payload: [String: JSON] = [:]) {
        guard !submitting else { return }; submitting = true
        Task { defer { submitting = false }; do { try await APIClient.shared.post(action, payload); NotificationCenter.default.post(name: .spxMerchantUpdated, object: nil) } catch { self.error = error.localizedDescription } }
    }
    private func load() async {
        let identity = UUID(); loadID = identity; loading = true
        defer { if loadID == identity { loading = false } }
        do { let data = try await APIClient.shared.get("merchant.me"); guard loadID == identity, !Task.isCancelled else { return }; merchant = data["merchant"].record; stats = data["statistics"].record; capacity = data["product_capacity"].record; required = DisplayLabels.amount(data["required_deposit"].string); loaded = true; error = nil }
        catch is CancellationError { }
        catch { if loadID == identity { self.error = error.localizedDescription } }
    }
}

struct Metric: View {
    let title: String
    let value: String
    var icon: String = "chart.bar"
    var body: some View { SPXCard { VStack(alignment: .leading, spacing: 12) { HStack { Image(systemName: icon).foregroundStyle(SPXColor.primary); Spacer(); Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary) }; Text(value.isEmpty ? "—" : value).font(.title3.bold()).foregroundStyle(.primary); Text(title).font(.caption).foregroundStyle(.secondary) } } }
}

struct MerchantProductsView: View {
    @State private var capacity = Record()
    @State private var products: [Record] = []
    @State private var page = 1
    @State private var more = true
    @State private var loadID = UUID()
    @State private var busy = false
    @State private var error: String?
    @State private var offline: Record?
    @State private var deleting: Record?
    @State private var selecting = false
    @State private var selected = Set<Int>()
    @State private var batchConfirm = false
    @State private var submitting = false
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "商品管理", subtitle: selecting ? "选择商品后批量下架，每次最多 50 件" : "管理商品资料、库存与上架状态", icon: "square.grid.2x2", color: SPXColor.commerce)
            SPXCard { MerchantProductCapacity(capacity: capacity) }
            ForEach(products) { product in
                SPXCard { VStack(alignment: .leading, spacing: 14) {
                    if selecting { Button { toggle(product.id) } label: { HStack { Label(selected.contains(product.id) ? "已选择" : "选择商品", systemImage: selected.contains(product.id) ? "checkmark.circle.fill" : "circle"); Spacer() }.frame(minHeight: 44).contentShape(Rectangle()) }.buttonStyle(.plain).foregroundStyle(SPXColor.primary).disabled(submitting) }
                    HStack(alignment: .top, spacing: 14) {
                        CachedImage(source: product.image).frame(width: 82, height: 82).background(.white, in: RoundedRectangle(cornerRadius: 12)).clipShape(RoundedRectangle(cornerRadius: 12))
                        VStack(alignment: .leading, spacing: 9) { Text(product.name).font(.system(size: 16, weight: .semibold)).foregroundStyle(SPXColor.ink).lineLimit(2); PriceLabel(cny: product["cny_price"].string, estimate: nil, legacy: product["spx_price"].string); Text("库存 \(product["stock"].string)").font(.system(size: 12)).foregroundStyle(SPXColor.muted) }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                    SPXStatusPill(title: product["status"].int == 1 ? DisplayLabels.review(product["review_status"].string) : "已下架", color: SPXColor.commerce)
                    if !product["review_reason"].string.isEmpty { Text(product["review_reason"].string).font(.system(size: 12)).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
                    if !selecting {
                        Divider()
                        HStack(spacing: 12) {
                            NavigationLink { PublishProductView(product: product) } label: {
                                Label("编辑商品", systemImage: "square.and.pencil").frame(maxWidth: .infinity, minHeight: 44).background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 12))
                            }
                            Menu {
                                NavigationLink("调整库存") { InventoryEditor(product: product) }
                                productAction(product)
                            } label: {
                                Image(systemName: "ellipsis").frame(width: 48, height: 44).background(SPXColor.surface, in: RoundedRectangle(cornerRadius: 12))
                            }.accessibilityLabel("更多商品操作")
                        }.font(.system(size: 14, weight: .medium)).foregroundStyle(SPXColor.primary).buttonStyle(.plain).disabled(submitting)
                    }
                } }
            }
            if busy && products.isEmpty { ProgressView("正在读取商品…").frame(maxWidth: .infinity).padding(24) }
            if more && !products.isEmpty { Button(busy ? "正在加载…" : "加载更多") { Task { await load(reset: false) } }.frame(maxWidth: .infinity, minHeight: 44).disabled(busy) }
            if !busy && products.isEmpty && error == nil { EmptyState(title: "暂无商品", description: "点击右上角加号发布第一件商品", icon: "square.grid.2x2") }
            if let error { ErrorBanner(message: error); Button("重新加载商品") { Task { await load(reset: true) } }.frame(maxWidth: .infinity, minHeight: 44).disabled(busy) }
        }.navigationTitle("商品管理").toolbar {
            ToolbarItem(placement: .topBarTrailing) { Button(selecting ? "完成" : "批量管理") { selecting.toggle(); selected = [] }.disabled(submitting) }
            ToolbarItem(placement: .topBarTrailing) { NavigationLink { PublishProductView() } label: { Image(systemName: "plus") }.accessibilityLabel("发布商品") }
        }.safeAreaInset(edge: .bottom) {
            if selecting { SPXBottomAction { VStack(spacing: 10) { Text("已选 \(selected.count)/50 件商品").font(.system(size: 13)).foregroundStyle(SPXColor.muted); Button(submitting ? "正在下架…" : "批量下架", role: .destructive) { batchConfirm = true }.disabled(selected.isEmpty || submitting) } } }
        }.task { await load(reset: true); await loadCapacity() }.refreshable { await load(reset: true); await loadCapacity() }
            .onReceive(NotificationCenter.default.publisher(for: .spxMerchantProductsUpdated)) { _ in Task { await load(reset: true); await loadCapacity() } }
            .confirmationDialog("确认下架已选的 \(selected.count) 件商品？", isPresented: $batchConfirm, titleVisibility: .visible) { Button("确认下架", role: .destructive) { batchOffline() } }
            .confirmationDialog("确认下架商品？", isPresented: Binding(get: { offline != nil }, set: { if !$0 { offline = nil } }), titleVisibility: .visible) {
                Button("下架", role: .destructive) { if let product = offline { mutate(product, deleting: false) }; offline = nil }
            }
            .confirmationDialog("删除后商品将从列表移除，历史订单和售后记录仍保留。", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                Button("删除商品", role: .destructive) { if let product = deleting { mutate(product, deleting: true) }; deleting = nil }
            }
    }
    @ViewBuilder private func productAction(_ product: Record) -> some View {
        if product["status"].int == 1 { Button("下架", role: .destructive) { offline = product } }
        else { Button("删除", role: .destructive) { deleting = product } }
    }
    private func mutate(_ product: Record, deleting: Bool) {
        guard !submitting else { return }
        submitting = true
        Task {
            defer { submitting = false }
            do {
                try await APIClient.shared.post(deleting ? "merchant.product.delete" : "merchant.product.offline", ["id": product["id"]])
                selected.remove(product.id)
                NotificationCenter.default.post(name: .spxMerchantProductsUpdated, object: nil)
            } catch { self.error = error.localizedDescription }
        }
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
                selected = []; selecting = false
                NotificationCenter.default.post(name: .spxMerchantProductsUpdated, object: nil)
            } catch { self.error = error.localizedDescription }
        }
    }
    private func loadCapacity() async {
        let identity = await APIClient.shared.sessionIdentity()
        do {
            let data = try await APIClient.shared.get("merchant.me")
            guard identity == (await APIClient.shared.sessionIdentity()), !Task.isCancelled else { return }
            capacity = data["product_capacity"].record
        } catch is CancellationError { } catch { self.error = error.localizedDescription }
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

struct MerchantProductCapacity: View {
    let capacity: Record
    var body: some View {
        if capacity.fields.keys.contains("limit") && capacity["limit"].int >= 0 {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("已上架 \(capacity["listed"].int)/\(capacity["limit"].int)").font(.system(size: 14, weight: .semibold))
                    Spacer()
                    Text("还可上架 \(capacity["remaining"].int) 款").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                }
                Text(capacity["remaining"].int == 0 ? "已达到上架上限，下架或删除后可再次上架" : "下架或删除商品后释放上架名额").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
            }
        }
    }
}

struct MerchantDraftsView: View {
    @StateObject private var state = PageState()
    @State private var error: String?
    @State private var deleting = false
    @State private var deletingDraft: Record?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "商品草稿", subtitle: "未完成的商品可继续编辑和发布", icon: "doc", color: SPXColor.commerce)
            ForEach(state.rows) { draft in
                SPXCard { VStack(alignment: .leading, spacing: 14) {
                    NavigationLink { DraftLoader(id: draft.id) } label: {
                        HStack(spacing: 12) { SPXIcon("doc").frame(width: 24, height: 24).foregroundStyle(SPXColor.commerce).frame(width: 48, height: 48).background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 12)); VStack(alignment: .leading, spacing: 7) { Text(draft["title"].string.isEmpty ? "未命名商品" : draft["title"].string).font(.system(size: 16, weight: .semibold)).foregroundStyle(SPXColor.ink); Text("更新于 " + draft["updated_at"].string).font(.system(size: 12)).foregroundStyle(SPXColor.muted) }; Spacer(minLength: 4); SPXIcon("chevron").frame(width: 14, height: 14).foregroundStyle(SPXColor.muted) }
                    }.buttonStyle(.plain).disabled(deleting)
                    Divider()
                    HStack { NavigationLink("继续编辑") { DraftLoader(id: draft.id) }.disabled(deleting); Spacer(); Button("删除草稿", role: .destructive) { deletingDraft = draft }.disabled(deleting) }.font(.system(size: 14, weight: .medium)).frame(minHeight: 44).buttonStyle(.plain)
                } }
            }
            if state.busy || deleting { ProgressView(deleting ? "正在删除…" : "正在读取草稿…").frame(maxWidth: .infinity).padding(20) }
            if state.more && !state.rows.isEmpty { Button("加载更多") { Task { await load(reset: false) } }.frame(maxWidth: .infinity, minHeight: 44).disabled(state.busy || deleting) }
            if state.page > 0, state.rows.isEmpty, !state.busy, error == nil { EmptyState(title: "暂无草稿", description: "发布商品时可保存草稿，下次继续编辑", icon: "doc") }
            if let error { ErrorBanner(message: error); Button("重试") { Task { await load(reset: state.page == 0) } }.frame(maxWidth: .infinity, minHeight: 44).disabled(state.busy || deleting) }
        }.navigationTitle("商品草稿").task { await load(reset: true) }.refreshable { await load(reset: true) }
            .onReceive(NotificationCenter.default.publisher(for: .spxMerchantProductsUpdated)) { _ in Task { await load(reset: true) } }
            .confirmationDialog("删除后无法恢复此草稿", isPresented: Binding(get: { deletingDraft != nil }, set: { if !$0 { deletingDraft = nil } }), titleVisibility: .visible) { Button("删除草稿", role: .destructive) { if let draft = deletingDraft { remove(draft) }; deletingDraft = nil }; Button("取消", role: .cancel) { deletingDraft = nil } }
    }
    private func remove(_ draft: Record) {
        guard !deleting else { return }; deleting = true
        Task { defer { deleting = false }; do { try await APIClient.shared.post("merchant.draft.delete", ["id": draft["id"]]); await load(reset: true) } catch { self.error = error.localizedDescription } }
    }
    private func load(reset: Bool) async {
        do { try await state.load(reset: reset) { next in try await APIClient.shared.get("merchant.drafts", ["page": String(next), "page_size": "30"]).list }; error = nil }
        catch { self.error = error.localizedDescription }
    }
}
struct DraftLoader: View {
    let id: Int
    @State private var draft: JSON = .null
    @State private var error: String?
    var body: some View { Group { if !draft.object.isEmpty { PublishProductView(product: draft["payload"].record, draftID: id, draftVersion: draft["version"].int) } else if let error { EmptyState(title: "草稿加载失败", description: error) } else { ProgressView() } }.task { do { draft = try await APIClient.shared.get("merchant.draft", ["id": String(id)]) } catch { self.error = error.localizedDescription } } }
}
