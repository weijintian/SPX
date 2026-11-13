import SwiftUI

struct WalletView: View {
    @EnvironmentObject private var session: SessionStore
    @Environment(\.scenePhase) private var phase
    @AppStorage("spx.wallet.balanceHidden") private var hidden = true
    @State private var loadID = UUID()
    @State private var assets: [Record] = []
    @State private var usd = ""
    @State private var loaded = false
    @State private var loading = false
    @State private var error: String?
    private var visibleAssets: [Record] { assets.filter { ["SPX001", "USDT", "XKRS"].contains($0["symbol"].string) } }
    private var total: Decimal? {
        guard loaded else { return nil }
        var result = Decimal.zero
        for asset in visibleAssets {
            guard let balance = Decimal(string: asset["balance"].string) else { return nil }
            if asset["symbol"].string == "USDT" { result += balance }
            else if balance != 0 { guard let price = Decimal(string: usd), price > 0 else { return nil }; result += balance * price }
        }
        return result
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Button { hidden.toggle() } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack { Text("总资产估值（USD）").spxFont(13); Spacer(); Image(systemName: hidden ? "eye.slash" : "eye").frame(width: 28, height: 28) }
                        Text(hidden ? "$ ••••" : total.map { "$ " + DisplayLabels.amount(NSDecimalNumber(decimal: $0).stringValue) } ?? "$ —")
                            .spxFont(34, weight: .bold).lineLimit(1).minimumScaleFactor(0.5)
                        Text("\(usd.isEmpty ? "行情暂未取得，估值可能暂不可用" : "估值随行情变化，以实际资产数量为准") · 轻触\(hidden ? "显示" : "隐藏")余额")
                            .spxFont(12).foregroundStyle(.white.opacity(0.75))
                    }.foregroundStyle(.white).padding(22).frame(maxWidth: .infinity, alignment: .leading)
                        .background(LinearGradient(colors: [SPXColor.hex(0x173A3F), SPXColor.hex(0x27645C)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 18))
                }.buttonStyle(.plain).accessibilityLabel(hidden ? "显示资产余额" : "隐藏资产余额")
                HStack(spacing: 12) {
                    walletLink("充值", "arrow.down.circle", color: SPXColor.primary) { WalletTransferView(withdrawal: false) }
                    walletLink("提现", "arrow.up.circle", color: SPXColor.success) { WalletTransferView(withdrawal: true) }
                }
                NavigationLink { WalletBindingView() } label: { SPXMenuRow(title: "BSC 钱包绑定", subtitle: "查看或绑定本人钱包地址", icon: "link") }
                SPXSectionTitle(title: "我的资产")
                if loading && !loaded { ProgressView("正在读取资产…").frame(maxWidth: .infinity).padding() }
                ForEach(visibleAssets, id: \.fields) { asset in
                    NavigationLink { WalletTransactionsView() } label: {
                        HStack(spacing: 14) {
                            Image(asset["symbol"].string == "XKRS" ? "wallet_fiss" : (asset["symbol"].string == "USDT" ? "wallet_usdt" : "wallet_spx001")).resizable().scaledToFit().frame(width: 44, height: 44).clipShape(Circle())
                            VStack(alignment: .leading, spacing: 5) { Text(asset["symbol"].string).spxFont(16, weight: .semibold); Text(asset["chain"].string.isEmpty ? "BSC" : asset["chain"].string).spxFont(12).foregroundStyle(SPXColor.muted) }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 6) {
                                Text(hidden ? "••••" : DisplayLabels.amount(asset["balance"].string)).spxFont(18, weight: .semibold).lineLimit(1).minimumScaleFactor(0.6)
                                if ["SPX001", "XKRS"].contains(asset["symbol"].string) { Text(hidden ? "可质押 ••••" : "可质押 " + DisplayLabels.amount(asset["stakeable_balance"].string)).spxFont(11).foregroundStyle(SPXColor.muted) }
                            }
                        }.foregroundStyle(SPXColor.ink).padding(16).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16))
                    }
                }
                if loaded && visibleAssets.isEmpty { EmptyState(title: "暂无资产", description: "充值后可在这里查看余额", icon: "wallet.pass") }
                NavigationLink { ExchangeView() } label: { SPXMenuRow(title: "兑换", subtitle: "USDT 兑换 SPX001", icon: "arrow.left.arrow.right") }
                NavigationLink { WalletTransactionsView() } label: { SPXMenuRow(title: "交易记录", subtitle: "充值、提现与资产变动", icon: "clock") }
                NavigationLink { StakingView() } label: { SPXMenuRow(title: "锁仓质押", subtitle: "质押计划、收益与赎回", icon: "lock.shield") }
                NavigationLink { RedPacketHistoryView() } label: { SPXMenuRow(title: "红包记录", subtitle: "查看发出和领取的红包", icon: "gift") }
                if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } } }
            }.buttonStyle(.plain).padding(18)
        }.navigationTitle("资产").spxPage().task(id: phase) { if phase == .active { await load() } }.spxRefreshable { await load() }
            .onReceive(NotificationCenter.default.publisher(for: .spxWalletChanged).debounce(for: .milliseconds(250), scheduler: RunLoop.main)) { event in
                Task { if await AccountMutation.isCurrent(event) { await load() } }
            }
    }
    private func walletLink<Destination: View>(_ title: String, _ icon: String, color: Color, @ViewBuilder destination: () -> Destination) -> some View {
        NavigationLink(destination: destination()) { Label(title, systemImage: icon).spxFont(16, weight: .semibold).foregroundStyle(color).frame(maxWidth: .infinity, minHeight: 54).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(SPXColor.line, lineWidth: 0.6)) }
    }
    private func load() async {
        guard let owner = session.user?.id, owner > 0 else { return }
        let requestID = UUID(); loadID = requestID; loading = true
        defer { if loadID == requestID { loading = false } }
        // APIReadCache restores the endpoint/account/language-scoped display snapshot.
        do {
            async let valuation = loadValuation()
            let rows = try await APIClient.shared.get("wallet").list
            let price = await valuation
            guard loadID == requestID, session.user?.id == owner, !Task.isCancelled else { return }
            assets = rows; usd = price; loaded = true; error = nil
        } catch is CancellationError { }
        catch { if loadID == requestID, session.user?.id == owner { self.error = error.localizedDescription } }
    }
    private func loadValuation() async -> String { (try? await APIClient.shared.get("mall.valuation"))?["spx_usdt"].string ?? "" }
}

struct WalletBindingView: View {
    @State private var binding: JSON = .null
    @State private var address = ""
    @State private var password = ""
    @State private var busy = false
    @State private var confirmation = false
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "钱包绑定", subtitle: "BSC 网络 · 管理资产出入金地址", icon: "wallet", color: SPXColor.success)
            if binding["bound"].flag { SPXPanel("已绑定钱包") { Text(binding["bound_address"].string).font(.system(.body, design: .monospaced)).textSelection(.enabled); Text("绑定地址固定。如需帮助，请提交客服工单。").font(.footnote).foregroundStyle(.secondary) } }
            else { SPXPanel("绑定 BSC 钱包") { SPXField("钱包地址") { TextField("0x 开头的 BSC 地址", text: $address).textInputAutocapitalization(.never) }; SPXField("验证账号密码") { SecureField("请输入账号密码", text: $password).textContentType(.password) }; Text("确认绑定后不可直接更改。提现将仅允许发送至该地址。").font(.footnote).foregroundStyle(.secondary); Button(busy ? "绑定中…" : "确认绑定") { confirmation = true }.buttonStyle(SPXPrimaryButtonStyle()).disabled(busy || address.count != 42 || password.isEmpty) } }
            if let error { ErrorBanner(message: error) }
        }.navigationTitle("钱包绑定").task { await load() }
            .confirmationDialog("请确认这是你本人控制的 BSC 钱包地址", isPresented: $confirmation, titleVisibility: .visible) { Button("确认并绑定") { bind() } }
    }
    private func load() async { do { binding = try await APIClient.shared.get("wallet.binding") } catch { self.error = error.localizedDescription } }
    private func bind() { busy = true; Task { defer { busy = false; password = "" }; do { binding = try await APIClient.shared.post("wallet.bind", ["address": .string(address.trimmingCharacters(in: .whitespacesAndNewlines)), "password": .string(password)]) } catch { self.error = error.localizedDescription; await load() } } }
}

struct WalletTransferView: View {
    let withdrawal: Bool
    @EnvironmentObject private var session: SessionStore
    @State private var info: JSON = .null
    @State private var symbol = "SPX001"
    @State private var amount = ""
    @State private var hash = ""
    @State private var busy = false
    @State private var confirmation = false
    @State private var uncertain = false
    @State private var error: String?
    @State private var unlock = false
    @State private var pending: Record?
    @State private var withdrawConfig: JSON = .null
    /// 后台可配置的费率（%），默认 1
    private var feeRatePercent: Decimal {
        let raw = withdrawConfig["symbols"][symbol]["fee_rate"].string
        return Decimal(string: raw, locale: Locale(identifier: "en_US_POSIX")) ?? Decimal(string: "1")!
    }
    /// 后台可配置的最低提现量
    private var minimumWithdraw: Decimal {
        let raw = withdrawConfig["symbols"][symbol]["min_amount"].string
        return Decimal(string: raw, locale: Locale(identifier: "en_US_POSIX")) ?? 0
    }
    /// 后台的 XKRS 提现开关（充值不受影响）
    private var fissWithdrawEnabled: Bool {
        !withdrawal || withdrawConfig["fiss_enabled"].flag
    }
    private var feePercentText: String {
        var value = feeRatePercent, rounded = Decimal()
        NSDecimalRound(&rounded, &value, 4, .plain)
        var out = NSDecimalNumber(decimal: rounded).stringValue
        if out.hasSuffix(".0") { out = String(out.dropLast(2)) }
        return out
    }
    private var withdrawTotal: Decimal? {
        guard let total = Decimal(string: amount, locale: Locale(identifier: "en_US_POSIX")), total > 0 else { return nil }
        return total
    }
    private var withdrawFee: Decimal {
        guard let total = withdrawTotal else { return 0 }
        var raw = total * feeRatePercent / 100, out = Decimal()
        NSDecimalRound(&out, &raw, 18, .down)
        return out
    }
    private var withdrawAmountIssue: String? {
        guard withdrawal, let total = withdrawTotal else { return nil }
        if minimumWithdraw > 0, total < minimumWithdraw {
            return "最小提现数量为 \(DisplayLabels.amount(Money.format(minimumWithdraw, scale: 18))) \(symbol)"
        }
        if let net = withdrawTotal.map({ $0 - withdrawFee }), net <= 0 { return "提现数量必须大于手续费" }
        return nil
    }
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: withdrawal ? "提现" : "充值", subtitle: "BSC 网络 · 请核对币种、地址与数量", icon: withdrawal ? "arrow.up.right" : "arrow.down.left", color: SPXColor.success)
            SPXPanel("资产与地址") {
                Picker("币种", selection: $symbol) {
                    Text("SPX001").tag("SPX001")
                    Text("USDT").tag("USDT")
                    if fissWithdrawEnabled { Text("XKRS").tag("XKRS") }
                }.pickerStyle(.segmented)
                .onChange(of: symbol) { _ in if symbol == "XKRS" && !fissWithdrawEnabled { symbol = "SPX001" } }
                InfoRow(title: "网络", value: "BSC")
                InfoRow(title: withdrawal ? "提现至绑定地址" : "付款来源地址", value: info["bound_address"].string)
                if !withdrawal {
                    let addressKey = symbol == "USDT" ? "usdt_address" : (symbol == "XKRS" ? "fiss_address" : "spx001_address")
                let destination = info[addressKey].string.isEmpty ? info["spx001_address"].string : info[addressKey].string
                    Text("平台充值地址").foregroundStyle(.secondary)
                    Text(destination).font(.system(.subheadline, design: .monospaced)).textSelection(.enabled)
                    Button("复制充值地址", systemImage: "doc.on.doc") { UIPasteboard.general.string = destination }
                    SPXField("链上交易哈希") { TextField("请输入交易哈希", text: $hash).textInputAutocapitalization(.never) }
                }
                SPXField(withdrawal ? "提现数量" : "转账数量") { TextField("请输入数量", text: $amount).keyboardType(.decimalPad).spxFont(28, weight: .semibold, design: .rounded) }
                if withdrawal, let total = withdrawTotal {
                    InfoRow(title: "手续费（\(feePercentText)%）", value: DisplayLabels.amount(Money.format(withdrawFee, scale: 18)) + " " + symbol)
                    InfoRow(title: "预计到账", value: DisplayLabels.amount(Money.format(total - withdrawFee, scale: 18)) + " " + symbol)
                    if minimumWithdraw > 0 {
                        InfoRow(title: "最小提现", value: DisplayLabels.amount(Money.format(minimumWithdraw, scale: 18)) + " " + symbol)
                    }
                }
                if let withdrawAmountIssue { Text(withdrawAmountIssue).font(.footnote).foregroundStyle(.red) }
            }.disabled(busy || uncertain)
            SPXPanel("处理说明与结果核对") {
                Text(withdrawal ? "手续费按当前服务器规则收取，提交后可在交易记录查看审核状态。" : "请使用绑定钱包在 BSC 网络转入对应资产，填写真实交易哈希，到账以链上确认与服务器核验为准。").font(.footnote).foregroundStyle(.secondary)
                if uncertain {
                    Text("上次申请结果待核对。安全重试会使用原申请编号，不会重复创建提现。").font(.footnote).foregroundStyle(.secondary)
                    NavigationLink("查看交易记录并核对结果") { WalletTransactionsView() }
                    Button("重新读取最新状态") { Task { await load() } }
                    if let pending, !pending["client_request_id"].string.isEmpty {
                        Button("安全重试原申请") { recover(close: false) }.disabled(busy)
                        Button("核对并结束上次申请") { recover(close: true) }.disabled(busy)
                    } else { Button("我已核对旧版记录，解除保护") { unlock = true } }
                }

            }
            if let error { ErrorBanner(message: error) }
        }.safeAreaInset(edge: .bottom, spacing: 0) { if !uncertain { SPXBottomAction { Button(busy ? "正在提交…" : withdrawal ? "提交提现" : "提交充值核验") { confirmation = true }.disabled(busy || !Money.positive(amount, scale: 18) || withdrawAmountIssue != nil || info["bound_address"].string.isEmpty || (!withdrawal && hash.count != 66)) } } }.navigationTitle(withdrawal ? "提现" : "充值").task { await load() }
            .confirmationDialog(withdrawal ? "确认提现 \(DisplayLabels.amount(amount)) \(symbol)？" : "确认提交链上充值记录？", isPresented: $confirmation, titleVisibility: .visible) { Button("确认提交") { submit() } }
            .confirmationDialog("请确认已在交易记录核实结果；解除后再次提交会创建新申请", isPresented: $unlock, titleVisibility: .visible) { Button("已核实，解除保护") { Task { await LocalStore.shared.remove("withdraw-pending", userID: session.user?.id ?? 0); uncertain = false; amount = "" } } }
    }
    private func load() async {
        guard let owner = session.user?.id, owner > 0 else { return }
        if withdrawal {
            let saved: Record? = await LocalStore.shared.read("withdraw-pending", userID: owner)
            guard session.user?.id == owner else { return }
            pending = saved; uncertain = saved != nil
            if let saved { amount = saved["amount"].string; symbol = saved["symbol"].string }
        }
        do {
            let result = try await APIClient.shared.get(withdrawal ? "wallet.binding" : "wallet.deposit.info")
            guard session.user?.id == owner else { return }; info = result
            if withdrawal {
                withdrawConfig = (try? await APIClient.shared.get("wallet.withdraw.config")) ?? .null
                guard session.user?.id == owner else { return }
                if symbol == "XKRS" && !fissWithdrawEnabled { symbol = "SPX001" }
            }
            if withdrawal, let saved = pending, !saved["client_request_id"].string.isEmpty {
                let status = try await APIClient.shared.get("financial.request", ["operation": "wallet.withdraw", "client_request_id": saved["client_request_id"].string])
                if status["exists"].flag { await finish(status["result"], owner: owner) }
            }
        } catch { if session.user?.id == owner { self.error = error.localizedDescription } }
    }
    private func finish(_ result: JSON, owner: Int) async {
        guard result["cancelled"].flag || result["transaction_id"].int > 0 else { return }
        guard await LocalStore.shared.completeFinancial("withdraw-pending", userID: owner, requestID: result["client_request_id"].string) else { return }
        guard session.user?.id == owner else { return }
        pending = nil; uncertain = false; amount = ""
        error = result["cancelled"].flag ? "已确认上次申请未提交，可重新填写。" : "提现已提交，请在交易记录查看处理状态。"
    }
    private func recover(close: Bool) {
        guard !busy, let saved = pending, !saved["client_request_id"].string.isEmpty,
              let owner = session.user?.id, owner > 0 else { return }
        busy = true
        Task { defer { busy = false }
            do {
                let result: JSON
                if close {
                    var body = saved.fields; body["operation"] = "wallet.withdraw"
                    result = try await APIClient.shared.post("financial.resolve", body)
                } else { result = try await APIClient.shared.post("wallet.withdraw", saved.fields) }
                await finish(result, owner: owner)
            } catch { if session.user?.id == owner { self.error = "原申请仍受保护：" + error.localizedDescription } }
        }
    }
    private func submit() {
        guard !busy, !uncertain, let owner = session.user?.id, owner > 0 else { return }
        var body: [String: JSON] = ["symbol": .string(symbol), "chain": "BSC", "amount": .string(amount)]
        body[withdrawal ? "address" : "tx_hash"] = .string(withdrawal ? info["bound_address"].string : hash)
        busy = true
        Task {
            defer { busy = false }
            if withdrawal {
                do {
                    let saved = try await LocalStore.shared.reserveFinancial(Record(body), key: "withdraw-pending", userID: owner)
                    guard session.user?.id == owner else { return }
                    pending = saved; uncertain = true; body = saved.fields
                } catch { self.error = error.localizedDescription; await load(); return }
            }
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                let result = try await APIClient.shared.post(withdrawal ? "wallet.withdraw" : "wallet.deposit.submit", body)
                if withdrawal { await finish(result, owner: owner) }
                else if session.user?.id == owner { amount = ""; hash = ""; error = "已提交，请在交易记录查看处理状态" }
            } catch {
                if session.user?.id == owner { self.error = error.localizedDescription }
                // Keep the same request on every ambiguous or rejected response. Server-side
                // resolution serializes with a delayed request before allowing a new one.
            }
        }
    }
}

struct ExchangeView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var quote: JSON = .null
    @State private var amount = ""
    @State private var requestID = UUID().uuidString.lowercased()
    @State private var busy = false
    @State private var frozen = false
    @State private var restored = false
    @State private var confirm = false
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "资产兑换", subtitle: "USDT 兑换 SPX001", icon: "arrow.left.arrow.right", color: SPXColor.success)
            SPXPanel("兑换数量与行情") {
                SPXField("支付 USDT") { TextField("请输入兑换数量", text: $amount).keyboardType(.decimalPad).spxFont(28, weight: .semibold, design: .rounded) }.disabled(frozen || busy || !restored)
                InfoRow(title: "每 USDT 预计获得", value: DisplayLabels.amount(quote["spx_per_usdt"].string) + " SPX001")
                InfoRow(title: "参考单价 USD", value: DisplayLabels.amount(quote["exchange_price_usd"].string))
                InfoRow(title: "行情时间", value: quote["updated_at"].string)
                Text("实际成交由服务器按提交时行情计算。").font(.footnote).foregroundStyle(.secondary)
            }
            if !restored { ProgressView("正在恢复上次兑换…") }
            if let error { ErrorBanner(message: error); Button("重新加载") { Task { await load() } }.disabled(busy) }
            SPXPanel("兑换操作") {
                Button(busy ? "处理中…" : frozen ? "核对 / 重试该次兑换" : "确认兑换") { confirm = true }.buttonStyle(SPXPrimaryButtonStyle())
                    .disabled(!restored || busy || (!frozen && quote.object.isEmpty) || !Money.positive(amount, scale: 18))
                NavigationLink("兑换与交易记录") { WalletTransactionsView() }
            }
        }.navigationTitle("兑换").task { await load() }
            .confirmationDialog("确认将 \(DisplayLabels.amount(amount)) USDT 兑换为 SPX001？", isPresented: $confirm, titleVisibility: .visible) { Button("确认兑换") { exchange() } }
    }
    private func load() async {
        guard let owner = session.user?.id else { return }
        // Recovery is independent of market availability and always runs first.
        if let saved: Record = await LocalStore.shared.read("exchange-pending", userID: owner) {
            guard session.user?.id == owner else { return }
            requestID = saved["request_id"].string; amount = saved["amount"].string; frozen = true
        }
        restored = true
        do { let result = try await APIClient.shared.get("wallet.exchange.quote"); guard session.user?.id == owner else { return }; quote = result; error = nil }
        catch { if session.user?.id == owner { self.error = error.localizedDescription } }
    }
    private func exchange() {
        guard restored, !busy, let owner = session.user?.id, owner > 0 else { return }
        let wasFrozen = frozen
        let pending = Record(["request_id": .string(requestID), "amount": .string(amount)])
        busy = true
        Task {
            defer { busy = false }
            do {
                if wasFrozen {
                    let saved: Record? = await LocalStore.shared.read("exchange-pending", userID: owner)
                    guard saved == pending else { throw APIError.server(409, "兑换记录已变化，请重新加载") }
                } else { try await LocalStore.shared.reservePending(pending, key: "exchange-pending", userID: owner) }
                frozen = true
            } catch { self.error = error.localizedDescription; await load(); return }
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                try await APIClient.shared.post("wallet.exchange", ["usdt_amount": pending["amount"], "request_id": pending["request_id"]])
                await LocalStore.shared.completePending(pending, key: "exchange-pending", userID: owner)
                guard session.user?.id == owner else { return }
                frozen = false; amount = ""; requestID = UUID().uuidString.lowercased(); error = "兑换已完成，请查看资产余额"
            } catch {
                guard session.user?.id == owner else { return }
                // An old delayed request may still complete. Never replace its ID after a retry fails.
                self.error = "兑换结果待核对，重试将沿用同一请求编号。\(error.localizedDescription)"
            }
        }
    }
}

struct WalletTransactionsView: View {
    @StateObject private var state = PageState()
    @State private var filter = "all"
    @State private var error: String?
    @State private var cancel: Record?
    var body: some View {
        SPXDetailPage {
            SPXFilterTabs(options: [("all", "全部"), ("SPX001", "SPX001"), ("USDT", "USDT"), ("XKRS", "XKRS")], selection: $filter)
            ForEach(state.rows) { row in
                SPXCard { DisclosureGroup {
                    ForEach(["id", "chain", "address", "tx_hash", "fee_amount", "created_at"], id: \.self) { key in if !row[key].string.isEmpty { InfoRow(title: ["id": "流水号", "chain": "网络", "address": "钱包地址", "tx_hash": "交易哈希", "fee_amount": "手续费", "created_at": "创建时间"][key] ?? key, value: key == "fee_amount" ? DisplayLabels.amount(row[key].string) : row[key].string) } }
                    if row["type"].string == "withdraw", row["status"].string == "pending" { Button("取消提现", role: .destructive) { cancel = row } }
                } label: { HStack { VStack(alignment: .leading, spacing: 5) { Text(DisplayLabels.transaction(row["type"].string)).font(.subheadline.weight(.medium)); Text(DisplayLabels.transactionStatus(row["status"].string)).font(.caption).foregroundStyle(.secondary) }; Spacer(); Text("\(DisplayLabels.amount(row["amount"].string)) \(row["symbol"].string)").font(.subheadline.weight(.semibold)) } }
                }
            }
            if state.busy { ProgressView().frame(maxWidth: .infinity) }
            if state.more { Button("加载更多") { Task { await load(reset: false) } }.disabled(state.busy) }
            if let error { ErrorBanner(message: error); Button("重试") { Task { await load(reset: state.page == 0) } } }
            if state.page > 0, state.rows.isEmpty { EmptyState(title: "暂无交易记录") }
        }.navigationTitle("交易记录").task(id: filter) { await load(reset: true) }.spxRefreshable { await load(reset: true) }.errorAlert($error)
            .onReceive(NotificationCenter.default.publisher(for: .spxWalletChanged).debounce(for: .milliseconds(250), scheduler: RunLoop.main)) { event in
                Task { if await AccountMutation.isCurrent(event) { await load(reset: true) } }
            }
            .confirmationDialog("确认取消这笔待处理提现？", isPresented: Binding(get: { cancel != nil }, set: { if !$0 { cancel = nil } }), titleVisibility: .visible) { Button("取消提现", role: .destructive) { if let cancel { Task { do { try await APIClient.shared.post("wallet.withdraw.cancel", ["transaction_id": cancel["id"]]); await load(reset: true) } catch { self.error = error.localizedDescription } }; self.cancel = nil } } }
    }
    private func load(reset: Bool) async {
        let selected = filter
        do { try await state.load(reset: reset) { next in try await APIClient.shared.get("wallet.transactions", ["symbol": selected, "page": String(next), "page_size": "30"]).list }; error = nil }
        catch { self.error = error.localizedDescription }
    }
}

struct StakingView: View {
    @State private var pending: Record?
    @EnvironmentObject private var session: SessionStore
    @State private var plans: [Record] = []
    @State private var records: [Record] = []
    @State private var assets: [Record] = []
    @State private var selected = 0
    @State private var symbol = "SPX001"
    @State private var amount = ""
    @State private var phase: JSON = .null
    @State private var burnInfo: JSON = .null
    @State private var stakingOpen = true
    @State private var confirm: String?
    @State private var stakeSheet = false
    private var stakingSymbol: String {
        guard let plan = selectedPlan else { return "SPX001" }
        let value = plan["symbol"].string
        return ["SPX001", "XKRS"].contains(value) ? value : "SPX001"
    }
    @State private var redeemID = 0
    @State private var busy = false
    @State private var error: String?
    @State private var uncertain = false
    @State private var unlock = false
    @State private var loading = false
    @State private var loaded = false
    @State private var snapshotFresh = false
    @State private var dataOwner = 0
    @State private var loadID = UUID()
    @State private var success: String?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private var selectedPlan: Record? { plans.first { $0.id == selected } }
    private var stakeable: Decimal? { assets.first { $0["symbol"].string == symbol && $0["chain"].string == "BSC" }?["stakeable_balance"].decimal }
    private var activeTotal: Decimal? { StakingPresentation.activeTotal(records) }
    private var amountIssue: String? {
        StakingPresentation.validationMessage(amount: amount, plan: selectedPlan, balance: stakeable, activeTotal: activeTotal)
    }
    private var phaseOpen: Bool { phase["status"].int == 1 && (phase["remaining_quota"].decimal.map { $0 > 0 } ?? false) }
    private var canCreate: Bool {
        snapshotFresh && dataOwner == session.user?.id && phaseOpen && !busy && !loading && !uncertain && amountIssue == nil
    }
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "锁仓质押", subtitle: "质押 \(symbol) 积分，查看锁仓方案与生态分红", icon: "lock.shield", color: SPXColor.primary)
            HStack(spacing: 8) {
                ForEach(["SPX001", "XKRS"], id: \.self) { option in
                    Button {
                        guard symbol != option else { return }
                        symbol = option
                        selected = 0
                        amount = ""
                        Task { await load() }
                    } label: {
                        Text(option)
                            .spxFont(14, weight: .semibold)
                            .foregroundStyle(symbol == option ? Color.white : SPXColor.muted)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 9)
                            .background(symbol == option ? SPXColor.primary : SPXColor.card, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(busy)
                }
            }
            if loading { ProgressView(loaded ? "正在更新质押数据…" : "正在读取质押数据…").frame(maxWidth: .infinity).padding(.vertical, 8) }
            if let error {
                SPXCard {
                    VStack(alignment: .leading, spacing: 12) {
                        ErrorBanner(message: error)
                        Button("重新加载") { Task { await load() } }.disabled(loading || busy)
                    }
                }
            }
            if loaded {
                balanceHero
                personalOverview
                quotaCard
                rulesOverview
            }
            if uncertain {
                SPXPanel("提交结果待核对") {
                    Text("上次质押申请结果待核对。安全重试沿用原申请编号，避免重复质押。")
                    NavigationLink("交易记录") { WalletTransactionsView() }
                    Button("重新核对质押记录") { Task { await load() } }.disabled(loading || busy)
                    if let pending, !pending["client_request_id"].string.isEmpty {
                        Button("安全重试原申请") { recover(close: false) }.disabled(loading || busy)
                        Button("核对并结束上次申请") { recover(close: true) }.disabled(loading || busy)
                    } else { Button("已核实旧版记录，解除保护") { unlock = true }.disabled(loading || busy) }
                }
            }
            if loaded {
                if !stakingOpen {
                    SPXCard { VStack(alignment: .leading, spacing: 8) {
                        Text("质押已满额").font(.headline).foregroundStyle(SPXColor.hex(0xBC7A16))
                        Text("本期额度已用完，暂不开放新的质押；已质押订单照常计息，到期自动结算。")
                            .font(.footnote).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
                    } }
                }
                SPXSectionTitle(title: "选择质押方案")
                if plans.isEmpty {
                    EmptyState(title: stakingOpen ? "暂无可用方案" : "本期额度已用完",
                               description: stakingOpen ? "方案由平台配置，可稍后下拉刷新。" : "开放时间请留意公告，已质押订单不受影响。",
                               icon: "lock.shield")
                }
                ForEach(plans) { plan in planCard(plan) }
                if !plans.isEmpty { amountForm }
                HStack(alignment: .firstTextBaseline) {
                    SPXSectionTitle(title: "我的质押记录")
                    Spacer()
                    NavigationLink {
                        StakingRecordsView(pageSymbol: symbol)
                    } label: {
                        Text("全部记录 ›").font(.subheadline.weight(.medium)).foregroundStyle(SPXColor.primary)
                    }
                    NavigationLink {
                        StakingEarningsView(pageSymbol: symbol)
                    } label: {
                        Text("收益明细 ›").font(.subheadline.weight(.medium)).foregroundStyle(SPXColor.primary)
                    }
                }
                if records.isEmpty {
                    EmptyState(title: "暂无质押记录", description: "参与质押后，可在这里查看本金、分红与赎回状态。", icon: "clock")
                }
                ForEach(records) { record in recordCard(record) }
            }
        }.navigationTitle("锁仓质押").task(id: session.user?.id) { await load() }.spxRefreshable { if !busy { await load() } }.successToast($success)
            .onReceive(NotificationCenter.default.publisher(for: .spxWalletChanged).debounce(for: .milliseconds(250), scheduler: RunLoop.main)) { event in
                Task { if await AccountMutation.isCurrent(event), !busy { await load() } }
            }
            .sheet(isPresented: $stakeSheet) { stakeQuickForm }
            .confirmationDialog(confirm == "create" ? "确认质押 \(StakingPresentation.amount(amount)) \(stakingSymbol)？" : "确认赎回到期本金？", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible) { Button("确认") { let action = confirm; confirm = nil; submit(create: action == "create") } } message: {
                Text(confirm == "create" ? "\(selectedPlan?["plan_days"].string ?? "—") 天锁定 · 年化 \(StakingPresentation.rate(selectedPlan?["apy"]))。锁定期间不可提前赎回，最终以服务器核验为准。" : "仅可赎回已到期的本金，结果以服务器核验为准。")
            }
            .confirmationDialog("解除后可创建新的质押，请确认已经核实上次申请", isPresented: $unlock, titleVisibility: .visible) { Button("确认已核实") {
                guard !busy, !loading, let owner = session.user?.id, owner == dataOwner else { return }
                busy = true
                Task {
                    defer { busy = false }
                    await LocalStore.shared.remove("staking-pending", userID: owner)
                    guard session.user?.id == owner else { return }
                    pending = nil; uncertain = false; amount = ""
                    await load()
                }
            } }
    }
    private var pendingDividend: Decimal {
        records.filter { $0["status"].string == "active" }.reduce(Decimal(0)) { total, record in
            let expected = record["expected_reward"].decimal ?? 0
            let paid = record["paid_dividend"].decimal ?? 0
            return total + (expected > paid ? expected - paid : 0)
        }
    }
    private var usedFraction: Double {
        let total = phase["total_quota"].decimal ?? 0
        let reserved = phase["reserved_quota"].decimal ?? 0
        guard total > 0 else { return 0 }
        let value = Double(truncating: NSDecimalNumber(decimal: reserved / total))
        return min(1, max(0, value))
    }
    private func compactStaking(_ raw: String) -> String {
        guard let value = Double(raw) else { return raw }
        if value >= 99_999_999.5 { return String(format: "%.0f 亿", value / 100_000_000) }
        if value >= 9_999.5 { return String(format: "%.0f 万", value / 10_000) }
        return StakingPresentation.amount(raw)
    }
    private var balanceHero: some View {
        SPXCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("可质押余额").font(.caption).foregroundStyle(SPXColor.muted)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(StakingPresentation.amount(stakeable.map { NSDecimalNumber(decimal: $0).stringValue } ?? "0"))
                        .spxFont(32, weight: .bold).monospacedDigit().foregroundStyle(SPXColor.ink)
                        .minimumScaleFactor(0.6).lineLimit(1)
                    Text(symbol).font(.footnote).foregroundStyle(SPXColor.muted)
                }
                Divider()
                HStack(spacing: 24) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("已质押").font(.caption2).foregroundStyle(SPXColor.muted)
                        Text(StakingPresentation.amount(activeTotal.map { NSDecimalNumber(decimal: $0).stringValue } ?? "0"))
                            .font(.subheadline.weight(.semibold)).monospacedDigit()
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("待发收益").font(.caption2).foregroundStyle(SPXColor.muted)
                        Text(StakingPresentation.amount(NSDecimalNumber(decimal: pendingDividend).stringValue))
                            .font(.subheadline.weight(.semibold)).monospacedDigit().foregroundStyle(SPXColor.success)
                    }
                    Spacer()
                }
            }
        }
    }
    private var quotaCard: some View {
        SPXCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("总奖励额度").font(.caption).foregroundStyle(SPXColor.muted)
                        Text(compactStaking(phase["total_quota"].string))
                            .spxFont(26, weight: .bold).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    VStack(alignment: .trailing, spacing: 6) {
                        Text("剩余可用").font(.caption).foregroundStyle(SPXColor.muted)
                        Text(compactStaking(phase["remaining_quota"].string))
                            .spxFont(18, weight: .bold).monospacedDigit().foregroundStyle(SPXColor.success)
                            .minimumScaleFactor(0.6).lineLimit(1)
                    }
                }
                ProgressView(value: usedFraction).tint(SPXColor.primary)
                HStack {
                    Text("已发放 \(compactStaking(phase["paid_quota"].string))")
                    Spacer()
                    Text("已预扣 \(compactStaking(phase["reserved_quota"].string))")
                }.font(.caption2).foregroundStyle(SPXColor.muted)
                if !phase["name"].string.isEmpty {
                    Text("\(phase["name"].string) · 剩余 = 总额度 − 已预扣")
                        .font(.caption2).foregroundStyle(SPXColor.muted.opacity(0.9))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
    private var personalOverview: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("我的质押总额", systemImage: "lock.shield.fill").font(.subheadline.weight(.medium))
            Text(StakingPresentation.amount(activeTotal.map { NSDecimalNumber(decimal: $0).stringValue } ?? "")).font(.largeTitle.weight(.bold)).monospacedDigit().fixedSize(horizontal: false, vertical: true)
            Text("\(symbol) · 当前生效中的本金").font(.caption).foregroundStyle(.white.opacity(0.85))
            Divider().overlay(.white.opacity(0.25))
            Text("共 \(records.count) 条质押记录 · \(records.filter { $0["status"].string == "active" }.count) 条生效中").font(.footnote).fixedSize(horizontal: false, vertical: true)
        }.foregroundStyle(.white).padding(22).frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [SPXColor.hex(0x405BFA), SPXColor.hex(0x7342ED)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 22))
    }
    private var rulesOverview: some View {
        SPXPanel("质押规则") {
            if let plan = selectedPlan {
                detail("锁仓期限", value: plan["plan_days"].string + " 天")
                detail("参考年化", value: StakingPresentation.rate(plan["apy"]))
                detail("最低质押", value: StakingPresentation.minimum(plan).map { StakingPresentation.amount(NSDecimalNumber(decimal: $0).stringValue) } ?? "—", unit: symbol)
                if let step = plan["step_amount"].decimal, step > 1 {
                    detail("数量递增", value: StakingPresentation.amount(NSDecimalNumber(decimal: step).stringValue) + " \(symbol) 的整数倍")
                }
                if let maxValue = plan["max_per_user"].decimal, maxValue > 0 {
                    detail("单人生效本金上限", value: StakingPresentation.amount(NSDecimalNumber(decimal: maxValue).stringValue) + " \(symbol)")
                }
            } else { Text("请选择下方方案，查看对应期限、年化和最低数量。").font(.subheadline).foregroundStyle(SPXColor.muted) }
            Divider()
            Text("锁定期间不可提前赎回。服务端每 10 天结算分红，不足 10 天的尾期在到期时结算，本金由服务端到期结算返还。实际到账与处理时间请以交易记录为准；记录仍可赎回时，也可通过下方按钮核验处理。").font(.footnote).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
        }
    }
    private func planCard(_ plan: Record) -> some View {
        let days = plan["plan_days"].int
        let periods = max(1, Int(ceil(Double(days) / 10.0)))
        let minText = StakingPresentation.minimum(plan).map { compactStaking(NSDecimalNumber(decimal: $0).stringValue) } ?? "—"
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(days) 天").spxFont(20, weight: .bold).foregroundStyle(SPXColor.ink)
                Spacer()
                Text(StakingPresentation.rate(plan["apy"]))
                    .spxFont(22, weight: .bold).foregroundStyle(SPXColor.primary).monospacedDigit()
            }
            HStack(alignment: .top) {
                Text("每 10 天发息 · 共 \(periods) 期 · 到期还本").font(.caption).foregroundStyle(SPXColor.muted)
                Spacer()
                Text("年化").font(.caption2).foregroundStyle(SPXColor.muted)
            }
            HStack {
                Text("起押 \(minText) \(symbol) · \(compactStaking(plan["step_amount"].string)) 整数倍").font(.caption).foregroundStyle(SPXColor.muted)
                Spacer()
                Button { selected = plan.id; amount = ""; stakeSheet = true } label: {
                    Text("立即质押")
                        .spxFont(14, weight: .semibold).foregroundStyle(.white)
                        .padding(.horizontal, 18).padding(.vertical, 9)
                        .background(SPXColor.primary, in: RoundedRectangle(cornerRadius: 12))
                }.buttonStyle(.plain).disabled(busy || uncertain || loading)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SPXColor.card, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(selected == plan.id ? SPXColor.primary : SPXColor.line,
                                                           lineWidth: selected == plan.id ? 2 : 1))
        .accessibilityAddTraits(selected == plan.id ? .isSelected : [])
    }

    private var amountForm: some View {
        SPXPanel("填写质押数量") {
            SPXField("质押数量 · \(symbol)") {
                TextField("请输入质押数量", text: $amount).keyboardType(.numberPad).font(.title2.weight(.semibold)).accessibilityLabel("质押数量，单位 \(symbol)")
            }
            if let plan = selectedPlan { stakingAmountExtras(plan) }
            if !amount.isEmpty, let amountIssue { Text(amountIssue).font(.footnote).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true) }
            if let plan = selectedPlan, amountIssue == nil, let reward = StakingPresentation.expectedReward(amount: amount, plan: plan) {
                detail("预计全期分红", value: StakingPresentation.amount(NSDecimalNumber(decimal: reward).stringValue) + " \(symbol)")
                Text("参考测算 = 本金 × 年化 × 天数 ÷ 365；以服务端实际结算为准。").font(.caption).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
            }
            Button(busy ? "正在处理…" : "确认质押") { confirm = "create" }.buttonStyle(SPXPrimaryButtonStyle()).disabled(!canCreate)
            if !snapshotFresh, !loading { Text("数据尚未更新成功，请重新加载后再提交。").font(.footnote).foregroundStyle(SPXColor.muted) }
        }.disabled(uncertain || busy || loading)
    }
    private func recordCard(_ record: Record) -> some View {
        NavigationLink {
            StakingRecordDetailView(recordID: record.id)
        } label: {
            SPXPanel(record.text("plan_name", "name").isEmpty ? record["plan_days"].string + " 天锁仓" : record.text("plan_name", "name")) {
                SPXStatusPill(title: record["can_redeem"].flag ? "已到期 · 待结算" : DisplayLabels.transactionStatus(record["status"].string), color: record["status"].string == "active" ? SPXColor.success : SPXColor.muted)
                detail("质押本金", value: StakingPresentation.amount(record["amount"].string) + " \(symbol)")
                detail("年化收益率", value: StakingPresentation.rate(record["apy"]))
                detail("预计全期分红", value: StakingPresentation.amount(record["expected_reward"].string) + " \(symbol)")
                detail("已发分红", value: StakingPresentation.amount(record["paid_dividend"].string) + " \(symbol)")
                if !record["created_at"].string.isEmpty { detail("创建时间", value: record["created_at"].string) }
            }
        }
        .buttonStyle(.plain)
    }

    private func detail(_ title: String, value: String, unit: String = "") -> some View {
        let displayedValue = unit.isEmpty ? value : value + " " + unit
        return SPXViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 12) { Text(title).foregroundStyle(SPXColor.muted).fixedSize(); Spacer(minLength: 0); Text(displayedValue).fixedSize().multilineTextAlignment(.trailing) }
            VStack(alignment: .leading, spacing: 5) { Text(title).foregroundStyle(SPXColor.muted); Text(displayedValue).fixedSize(horizontal: false, vertical: true) }.frame(maxWidth: .infinity, alignment: .leading)
        }.font(.subheadline).accessibilityElement(children: .combine)
    }
    private func load() async {
        let requestID = UUID(); loadID = requestID
        guard let owner = session.user?.id, owner > 0 else {
            plans = []; records = []; assets = []; phase = .null; amount = ""; selected = 0
            pending = nil; uncertain = false; loaded = false; loading = false; snapshotFresh = false; dataOwner = 0; confirm = nil; unlock = false; redeemID = 0; success = nil; error = nil
            return
        }
        if dataOwner != owner {
            plans = []; records = []; assets = []; phase = .null; amount = ""; selected = 0
            pending = nil; uncertain = false; loaded = false; confirm = nil; unlock = false; redeemID = 0; success = nil
        }
        dataOwner = owner; loading = true; snapshotFresh = false; error = nil
        defer { if loadID == requestID { loading = false } }
        let saved: Record? = await LocalStore.shared.read("staking-pending", userID: owner)
        guard loadID == requestID, session.user?.id == owner, !Task.isCancelled else { return }
        pending = saved; uncertain = saved != nil
        if let saved { selected = saved["plan_id"].int; amount = saved["amount"].string }
        if let saved, !saved["client_request_id"].string.isEmpty {
            do {
                let status = try await APIClient.shared.get("financial.request", ["operation": "staking.create", "client_request_id": saved["client_request_id"].string])
                guard loadID == requestID, session.user?.id == owner, !Task.isCancelled else { return }
                if status["exists"].flag { await finish(status["result"], owner: owner) }
            } catch is CancellationError { return }
            catch {
                guard loadID == requestID, session.user?.id == owner, !Task.isCancelled else { return }
                self.error = "上次申请仍待核对：" + error.localizedDescription
            }
        }
        do {
            async let planData = APIClient.shared.get("staking.plans", ["symbol": symbol])
            async let recordData = APIClient.shared.get("staking", ["symbol": symbol])
            async let walletData = APIClient.shared.get("wallet")
            let (data, updatedRecords, updatedAssets) = try await (planData, recordData, walletData)
            guard loadID == requestID, session.user?.id == owner, !Task.isCancelled else { return }
            plans = data["plans"].list; phase = data["phase"]; burnInfo = data["burn"]; records = updatedRecords.list; assets = updatedAssets.list
            stakingOpen = data["open"].int == 1 || data["open"].string.lowercased() == "true"
            if !uncertain, !plans.contains(where: { $0.id == selected }) { selected = plans.first?.id ?? 0 }
            loaded = true; snapshotFresh = true
        } catch is CancellationError { }
        catch { if loadID == requestID, session.user?.id == owner, !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func finish(_ result: JSON, owner: Int) async {
        guard result["cancelled"].flag || result["id"].int > 0 else { return }
        guard await LocalStore.shared.completeFinancial("staking-pending", userID: owner, requestID: result["client_request_id"].string) else { return }
        guard session.user?.id == owner else { return }
        pending = nil; uncertain = false
        amount = ""
        success = result["cancelled"].flag ? "已确认上次申请未提交，可重新选择方案。" : "质押申请已完成。"
    }
    private func recover(close: Bool) {
        guard !busy, !loading, let saved = pending, !saved["client_request_id"].string.isEmpty,
              let owner = session.user?.id, owner > 0, owner == dataOwner else { return }
        busy = true
        Task { defer { busy = false }
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                let result: JSON
                if close {
                    var body = saved.fields; body["operation"] = "staking.create"
                    result = try await APIClient.shared.post("financial.resolve", body)
                } else { result = try await APIClient.shared.post("staking.create", saved.fields) }
                await finish(result, owner: owner)
                if session.user?.id == owner { await load() }
            } catch { if session.user?.id == owner { self.error = "原申请仍受保护：" + error.localizedDescription } }
        }
    }
    private func submit(create: Bool) {
        guard !busy, !loading, let owner = session.user?.id, owner > 0, owner == dataOwner else { return }
        if create { guard canCreate else { return } }
        else { guard snapshotFresh, records.contains(where: { $0.id == redeemID && $0["can_redeem"].flag }) else { return } }
        var body: [String: JSON] = create ? ["plan_id": .number(Decimal(selected)), "amount": .string(amount)] : ["id": .number(Decimal(redeemID))]
        busy = true; error = nil; success = nil
        Task {
            defer { busy = false }
            if create {
                do {
                    let saved = try await LocalStore.shared.reserveFinancial(Record(body), key: "staking-pending", userID: owner)
                    guard session.user?.id == owner else { return }
                    pending = saved; uncertain = true; body = saved.fields
                } catch {
                    let message = error.localizedDescription
                    guard session.user?.id == owner else { return }
                    await load()
                    if session.user?.id == owner { self.error = message }
                    return
                }
            }
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                let result = try await APIClient.shared.post(create ? "staking.create" : "staking.redeem", body)
                if create { await finish(result, owner: owner) }
                else if session.user?.id == owner { success = "到期本金已赎回，请在资产与交易记录中核对。" }
                if session.user?.id == owner { await load() }
            } catch { if session.user?.id == owner { self.error = error.localizedDescription } }
        }
    }
}

/// Mirrors staking.create's fixed integer limits; plans and phase quotas remain server-provided.
enum StakingPresentation {
    static let increment = 100_000
    static let maximum = 10_000_000
    static func phaseTitle(_ phase: JSON) -> String {
        phase["phase_no"].int > 0 ? "第 \(phase["phase_no"].int) 期" : "暂未开放"
    }
    static func amount(_ raw: String) -> String {
        guard let value = JSON.string(raw).decimal, !value.isNaN, value >= 0 else { return "—" }
        if value > 0, value < Decimal(string: "0.01")! { return "<0.01" }
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US"); formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 2; formatter.maximumFractionDigits = 2
        formatter.usesGroupingSeparator = true; formatter.roundingMode = .down
        return formatter.string(from: NSDecimalNumber(decimal: value)) ?? "—"
    }
    static func rate(_ raw: JSON?) -> String {
        guard let value = raw?.decimal, !value.isNaN, value >= 0 else { return "—" }
        if value > 0, value < Decimal(string: "0.01")! { return "<0.01%" }
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 2; formatter.maximumFractionDigits = 2
        formatter.usesGroupingSeparator = false; formatter.roundingMode = .halfUp
        guard let formatted = formatter.string(from: NSDecimalNumber(decimal: value)) else { return "—" }
        return formatted + "%"
    }
    static func remainingFraction(_ phase: JSON) -> Double? {
        guard let total = phase["total_quota"].decimal, !total.isNaN, total > 0,
              let remaining = phase["remaining_quota"].decimal, !remaining.isNaN, remaining >= 0 else { return nil }
        return min(1, max(0, NSDecimalNumber(decimal: remaining / total).doubleValue))
    }
    static func activeTotal(_ records: [Record]) -> Decimal? {
        var total = Decimal.zero
        for record in records where record["status"].string == "active" {
            guard let value = record["amount"].decimal, !value.isNaN, value >= 0 else { return nil }
            total += value
        }
        return total
    }
    static func minimum(_ plan: Record) -> Decimal? {
        guard let value = plan["minimum_amount"].decimal, !value.isNaN, value >= 0 else { return nil }
        return value
    }
    static func validationMessage(amount: String, plan: Record?, balance: Decimal?, activeTotal: Decimal?) -> String? {
        guard let plan, plan.id > 0 else { return "请先选择质押方案" }
        guard let minimumAmount = plan["minimum_amount"].decimal, !minimumAmount.isNaN, minimumAmount >= 0,
              let apy = plan["apy"].decimal, !apy.isNaN, apy >= 0, plan["plan_days"].int > 0 else { return "方案数据不完整，请重新加载" }
        guard !amount.isEmpty, amount.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }), amount.first != "0", let integer = Int(amount), integer > 0 else { return "请输入有效的质押数量" }
        let stepValue = Int(truncating: (plan["step_amount"].decimal ?? Decimal(increment)) as NSDecimalNumber)
        if stepValue > 1, integer % stepValue != 0 { return "质押数量必须是 \(StakingPresentation.amount(String(stepValue))) 的整数倍" }
        let value = Decimal(integer)
        guard value >= minimumAmount else { return "低于该方案的最低质押数量" }
        guard let balance, !balance.isNaN, balance >= 0, let activeTotal, !activeTotal.isNaN, activeTotal >= 0 else { return "余额或质押记录尚未完整读取，请刷新" }
        if let maxValue = plan["max_per_user"].decimal, !maxValue.isNaN, maxValue > 0 {
            guard value + activeTotal <= maxValue else { return "本次质押将超过单人累计上限" }
        }
        guard value <= balance else { return "可质押余额不足" }
        return nil
    }
    static func expectedReward(amount: String, plan: Record, days: Int? = nil) -> Decimal? {
        guard let principal = JSON.string(amount).decimal, !principal.isNaN, principal > 0,
              let apy = plan["apy"].decimal, !apy.isNaN, apy >= 0, plan["plan_days"].int > 0 else { return nil }
        let periodDays = min(days ?? plan["plan_days"].int, plan["plan_days"].int)
        guard periodDays > 0 else { return nil }
        return principal * apy * Decimal(periodDays) / 36500
    }
}


struct StakingRecordDetailView: View {
    let recordID: Int
    @State private var detail: Record?
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        SPXDetailPage {
            if loading {
                ProgressView("正在读取质押详情…").frame(maxWidth: .infinity).padding(.vertical, 24)
            }
            if let error {
                SPXCard {
                    VStack(alignment: .leading, spacing: 12) {
                        ErrorBanner(message: error)
                        Button("重新加载") { Task { await load() } }.disabled(loading)
                    }
                }
            }
            if let detail { content(detail) }
        }
        .navigationTitle("质押详情")
        .task { await load() }
    }

    @ViewBuilder
    private func content(_ d: Record) -> some View {
        let symbol = d["symbol"].string
        let active = d["status"].string == "active"
        SPXCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(symbol + " 锁仓").font(.caption).foregroundStyle(SPXColor.muted)
                    Spacer()
                    SPXStatusPill(title: active ? "进行中" : "已完成", color: active ? SPXColor.success : SPXColor.muted)
                }
                Text(StakingPresentation.amount(d["amount"].string) + " " + symbol)
                    .spxFont(30, weight: .bold).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
                Text("年化 \(StakingPresentation.rate(d["apy"])) · 锁定 \(d["plan_days"].string) 天")
                    .font(.caption).foregroundStyle(SPXColor.muted)
            }
        }
        SPXCard {
            VStack(alignment: .leading, spacing: 10) {
                row("利息发放进度", "\(d["periods_paid"].string) / \(d["periods_total"].string) 期")
                let paid = Double(d["periods_paid"].string) ?? 0
                let total = max(1, Double(d["periods_total"].string) ?? 1)
                ProgressView(value: min(1, max(0, paid / total))).tint(SPXColor.primary)
                row("已发利息", StakingPresentation.amount(d["paid_dividend"].string) + " " + symbol)
                row("待发利息", StakingPresentation.amount(d["remaining_reward"].string) + " " + symbol)
                row("应发合计", StakingPresentation.amount(d["expected_reward"].string) + " " + symbol)
            }
        }
        SPXCard {
            VStack(alignment: .leading, spacing: 10) {
                row("起息时间", d["created_at"].string)
                row("到期时间", d["matures_at"].string)
                let next = d["next_payout_at"].string
                if !next.isEmpty && next != "null" { row("下期发放", next) }
                let last = d["last_paid_at"].string
                if !last.isEmpty && last != "null" { row("最近发放", last) }
                let pool = d["phase"]["name"].string
                if !pool.isEmpty { row("分红奖池", pool) }
            }
        }
        let dividends = d["dividends"].list
        SPXSectionTitle(title: "分红明细")
        if dividends.isEmpty {
            SPXCard { Text("还没有利息发放记录，每 10 天发放一次").font(.footnote).foregroundStyle(SPXColor.muted) }
        } else {
            SPXCard {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(dividends) { item in
                        row("第 \(item["period_no"].string) 期",
                            StakingPresentation.amount(item["amount"].string) + "  ·  " + item["paid_at"].string)
                    }
                }
            }
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title).font(.subheadline).foregroundStyle(SPXColor.muted).fixedSize()
            Spacer(minLength: 8)
            Text(value).font(.subheadline.weight(.medium)).multilineTextAlignment(.trailing)
        }
    }

    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            let data = try await APIClient.shared.get("staking.detail", ["id": String(recordID)])
            guard data["id"].int > 0 else { throw APIError.server(404, "质押记录不存在") }
            detail = data.record
        } catch is CancellationError {
            return
        } catch {
            self.error = error.localizedDescription
        }
    }
}


// MARK: - 立即质押（方案卡内直达）

extension StakingView {
    /// 快捷金额 + 余额 + 整数倍 + 到期提示（主表单与弹层共用，对齐安卓）
    @ViewBuilder
    func stakingAmountExtras(_ plan: Record) -> some View {
        let minimum = StakingPresentation.minimum(plan) ?? 0
        let step = plan["step_amount"].decimal ?? 1
        let stepInt = max(1, Int(truncating: NSDecimalNumber(decimal: step)))
        let balanceInt = Int(truncating: NSDecimalNumber(decimal: stakeable ?? 0))
        let maxAmount = Decimal(max(0, (balanceInt / stepInt) * stepInt))
        let options: [(String, Decimal)] = [
            ("起押", minimum),
            ("2 倍", minimum * 2),
            ("5 倍", minimum * 5),
            ("10 倍", minimum * 10),
            ("全部", maxAmount)
        ]
        HStack(spacing: 6) {
            ForEach(Array(options.enumerated()), id: \.offset) { pair in
                let label = pair.element.0
                let value = pair.element.1
                Button {
                    let rounded = Int(truncating: NSDecimalNumber(decimal: value))
                    guard rounded > 0 else { error = "可质押余额不足"; return }
                    amount = String(rounded)
                } label: {
                    Text(label)
                        .spxFont(12, weight: .medium)
                        .foregroundStyle(SPXColor.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(SPXColor.hex(0xEEF2FF), in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .disabled(busy)
            }
        }
        Text("可质押余额 \(StakingPresentation.amount(NSDecimalNumber(decimal: stakeable ?? 0).stringValue)) \(symbol)")
            .font(.caption).foregroundStyle(SPXColor.success)
        Text("必须是 \(compactStaking(NSDecimalNumber(decimal: step).stringValue)) 的整数倍")
            .font(.caption).foregroundStyle(SPXColor.muted)
        if plan["plan_days"].int > 0 {
            Text("预计到期：\(Date().addingTimeInterval(Double(plan["plan_days"].int) * 86400).formatted(.dateTime.year().month(.twoDigits).day(.twoDigits)))")
                .font(.caption).foregroundStyle(SPXColor.muted)
        }
    }

    /// 方案卡点「立即质押」后弹出的简化表单
    var stakeQuickForm: some View {
        SPXNavigationContainer {
            SPXDetailPage {
                if let plan = selectedPlan {
                    SPXPanel("质押方案") {
                        InfoRow(title: "锁仓期限", value: plan["plan_days"].string + " 天")
                        InfoRow(title: "参考年化", value: StakingPresentation.rate(plan["apy"]))
                        InfoRow(title: "当前起押", value: (StakingPresentation.minimum(plan).map { StakingPresentation.amount(NSDecimalNumber(decimal: $0).stringValue) } ?? "-") + " \(symbol)")
                        if let step = plan["step_amount"].decimal, step > 1 {
                            InfoRow(title: "数量递增", value: compactStaking(NSDecimalNumber(decimal: step).stringValue) + " \(symbol) 的整数倍")
                        }
                    }
                }
                SPXPanel("填写数量") {
                    SPXField("质押数量 · \(symbol)") {
                        TextField("请输入质押数量", text: $amount).keyboardType(.numberPad).font(.title2.weight(.semibold))
                    }
                    if let plan = selectedPlan { stakingAmountExtras(plan) }
                    if !amount.isEmpty, let amountIssue {
                        Text(amountIssue).font(.footnote).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                    }
                    if amountIssue == nil, let plan = selectedPlan,
                       let reward = StakingPresentation.expectedReward(amount: amount, plan: plan) {
                        InfoRow(title: "预计全期分红", value: StakingPresentation.amount(NSDecimalNumber(decimal: reward).stringValue) + " \(symbol)")
                    }
                    Button("确认质押") { stakeSheet = false; submit(create: true) }
                        .buttonStyle(SPXPrimaryButtonStyle())
                        .disabled(!canCreate)
                }
            }
            .navigationTitle("立即质押")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { stakeSheet = false } } }
        }
    }
}

// MARK: - 收益明细

struct StakingEarningsView: View {
    let pageSymbol: String
    @State private var data: JSON = .null
    @State private var loading = true
    @State private var error: String?

    private var symbol: String { pageSymbol }
    private var records: [Record] { data["records"].list }
    private var total: JSON { data["totals"][symbol] }

    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "收益明细", subtitle: "\(symbol) · 预计收益与已发放分红", icon: "chart.line.uptrend.xyaxis", color: SPXColor.success)
            if loading {
                ProgressView("正在读取收益明细…").frame(maxWidth: .infinity).padding(.vertical, 12)
            }
            if let error {
                SPXCard {
                    VStack(alignment: .leading, spacing: 12) {
                        ErrorBanner(message: error)
                        Button("重新加载") { Task { await load() } }
                    }
                }
            }
            if !loading, error == nil {
                SPXPanel("汇总") {
                    InfoRow(title: "预计总收益", value: StakingPresentation.amount(total["expected"].string) + " \(symbol)")
                    InfoRow(title: "已发放分红", value: StakingPresentation.amount(total["paid"].string) + " \(symbol)")
                    InfoRow(title: "生效中本金", value: StakingPresentation.amount(total["active_amount"].string) + " \(symbol)")
                }
                if records.isEmpty {
                    EmptyState(title: "暂无收益记录", description: "参与质押后，可在这里查看每笔质押的预计收益与到期时间。", icon: "clock")
                } else {
                    SPXSectionTitle(title: "按质押记录")
                    ForEach(records) { record in
                        SPXPanel(record["plan_days"].string + " 天 · " + StakingPresentation.amount(record["amount"].string) + " \(symbol)") {
                            InfoRow(title: "预计全期分红", value: StakingPresentation.amount(record["expected_reward"].string) + " \(symbol)")
                            InfoRow(title: "已发分红", value: StakingPresentation.amount(record["paid_dividend"].string) + " \(symbol)")
                            InfoRow(title: "年化", value: StakingPresentation.rate(record["apy"]))
                            if !record["matures_at"].string.isEmpty {
                                InfoRow(title: "到期时间", value: record["matures_at"].string)
                            }
                            InfoRow(title: "状态", value: DisplayLabels.transactionStatus(record["status"].string))
                        }
                    }
                }
            }
        }
        .navigationTitle("收益明细")
        .task(id: symbol) { await load() }
        .spxRefreshable { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            data = try await APIClient.shared.get("staking.earnings", ["symbol": symbol])
            error = nil
        } catch is CancellationError {
            return
        } catch {
            self.error = error.localizedDescription
        }
    }
}


// MARK: - 质押记录（全部 / 生效中 / 已结束）

struct StakingRecordsView: View {
    let pageSymbol: String
    @State private var records: [Record] = []
    @State private var filter = 0
    @State private var loading = true
    @State private var error: String?

    private var symbol: String { pageSymbol }
    private var filtered: [Record] {
        switch filter {
        case 1: return records.filter { $0["status"].string == "active" }
        case 2: return records.filter { $0["status"].string != "active" }
        default: return records
        }
    }

    var body: some View {
        SPXDetailPage {
            Picker("筛选", selection: $filter) {
                Text("全部").tag(0)
                Text("生效中").tag(1)
                Text("已结束").tag(2)
            }
            .pickerStyle(.segmented)
            if loading {
                ProgressView("正在读取质押记录…").frame(maxWidth: .infinity).padding(.vertical, 24)
            }
            if let error {
                SPXCard {
                    VStack(alignment: .leading, spacing: 12) {
                        ErrorBanner(message: error)
                        Button("重新加载") { Task { await load() } }
                    }
                }
            }
            if !loading, error == nil {
                if filtered.isEmpty {
                    EmptyState(title: "暂无质押记录", description: "参与质押后，可在这里查看本金、分红与到期时间。", icon: "clock")
                }
                ForEach(filtered) { record in
                    NavigationLink {
                        StakingRecordDetailView(recordID: record.id)
                    } label: {
                        SPXPanel(record["plan_days"].string + " 天 · " + symbol) {
                            HStack {
                                Text(record["status"].string == "active" ? "生效中" : "已结束")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(record["status"].string == "active" ? SPXColor.success : SPXColor.muted)
                                Spacer()
                                Text(record["matures_at"].string).font(.caption).foregroundStyle(SPXColor.muted)
                            }
                            InfoRow(title: "质押本金", value: StakingPresentation.amount(record["amount"].string) + " \(symbol)")
                            InfoRow(title: "预计分红", value: StakingPresentation.amount(record["expected_reward"].string) + " \(symbol)")
                            InfoRow(title: "已发分红", value: StakingPresentation.amount(record["paid_dividend"].string) + " \(symbol)")
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .navigationTitle("质押记录")
        .task(id: symbol) { await load() }
        .spxRefreshable { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            let data = try await APIClient.shared.get("staking", ["symbol": symbol])
            records = data.list
            error = nil
        } catch is CancellationError {
            return
        } catch {
            self.error = error.localizedDescription
        }
    }
}
