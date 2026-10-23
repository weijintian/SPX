import SwiftUI

struct WalletView: View {
    @State private var assets: [Record] = []
    @State private var error: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ForEach(Array(assets.enumerated()), id: \.offset) { _, asset in
                    SPXCard {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack { Text(asset["symbol"].string).font(.headline); Spacer(); Text(asset["chain"].string).font(.caption).foregroundStyle(.secondary) }
                            Text(asset["balance"].string).font(.system(size: 32, weight: .semibold, design: .rounded)).minimumScaleFactor(0.5).lineLimit(1)
                            if asset["symbol"].string == "SPX001" { InfoRow(title: "可质押余额", value: asset["stakeable_balance"].string) }
                        }
                    }
                }
                HStack(spacing: 10) {
                    walletLink("充值", "arrow.down.circle") { WalletTransferView(withdrawal: false) }
                    walletLink("提现", "arrow.up.circle") { WalletTransferView(withdrawal: true) }
                    walletLink("兑换", "arrow.left.arrow.right.circle") { ExchangeView() }
                    walletLink("记录", "clock") { WalletTransactionsView() }
                }
                SPXCard { VStack(spacing: 18) { NavigationLink { WalletBindingView() } label: { Label("BSC 钱包绑定", systemImage: "link") }; NavigationLink { StakingView() } label: { Label("锁仓质押", systemImage: "lock.shield") }; NavigationLink { RedPacketHistoryView() } label: { Label("红包记录", systemImage: "gift") } } }
                if let error { ErrorBanner(message: error) }
            }.padding(18)
        }.navigationTitle("资产").spxPage().task { await load() }.refreshable { await load() }
    }
    private func walletLink<Destination: View>(_ title: String, _ icon: String, @ViewBuilder destination: () -> Destination) -> some View { NavigationLink(destination: destination) { VStack(spacing: 9) { Image(systemName: icon).font(.system(size: 25)); Text(title).font(.caption) }.frame(maxWidth: .infinity).padding(.vertical, 18).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 18)) }.buttonStyle(.plain).foregroundStyle(SPXColor.primary) }
    private func load() async { do { assets = try await APIClient.shared.get("wallet").list; error = nil } catch { self.error = error.localizedDescription } }
}

struct WalletBindingView: View {
    @State private var binding: JSON = .null
    @State private var address = ""
    @State private var password = ""
    @State private var busy = false
    @State private var confirmation = false
    @State private var error: String?
    var body: some View {
        Form {
            if binding["bound"].flag { Section("已绑定钱包") { Text(binding["bound_address"].string).font(.system(.body, design: .monospaced)).textSelection(.enabled); Text("绑定地址固定。如需帮助，请提交客服工单。").font(.footnote).foregroundStyle(.secondary) } }
            else { Section("绑定 BSC 钱包") { TextField("0x 开头的 BSC 地址", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled(); SecureField("账号密码", text: $password); Text("确认绑定后不可直接更改。提现将仅允许发送至该地址。").font(.footnote).foregroundStyle(.secondary); Button("确认绑定") { confirmation = true }.disabled(busy || address.count != 42 || password.isEmpty) } }
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
    var body: some View {
        Form {
            Section("资产") {
                Picker("币种", selection: $symbol) { Text("SPX001").tag("SPX001"); Text("USDT").tag("USDT") }
                InfoRow(title: "网络", value: "BSC")
                InfoRow(title: withdrawal ? "提现至绑定地址" : "付款来源地址", value: info["bound_address"].string)
                if !withdrawal {
                    let destination = info[symbol == "USDT" ? "usdt_address" : "spx001_address"].string
                    Text("平台充值地址").foregroundStyle(.secondary)
                    Text(destination).font(.system(.subheadline, design: .monospaced)).textSelection(.enabled)
                    Button("复制充值地址", systemImage: "doc.on.doc") { UIPasteboard.general.string = destination }
                    TextField("链上交易哈希", text: $hash).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                TextField(withdrawal ? "提现数量" : "转账数量", text: $amount).keyboardType(.decimalPad)
                if withdrawal, let total = Decimal(string: amount), Money.positive(amount, scale: 18) {
                    InfoRow(title: "手续费（1%）", value: Money.format(Money.withdrawalFee(total), scale: 18) + " " + symbol)
                    InfoRow(title: "预计到账", value: Money.format(total - Money.withdrawalFee(total), scale: 18) + " " + symbol)
                }
            }.disabled(busy || uncertain)
            Section {
                Text(withdrawal ? "手续费按当前服务器规则收取，提交后可在交易记录查看审核状态。" : "请使用绑定钱包在 BSC 网络转入对应资产，填写真实交易哈希，到账以链上确认与服务器核验为准。").font(.footnote).foregroundStyle(.secondary)
                if uncertain {
                    Text("上次提交结果尚未确认，重新进入页面仍会保留保护。请核对交易记录，勿重复提现。").font(.footnote).foregroundStyle(.secondary)
                    NavigationLink("查看交易记录并核对结果") { WalletTransactionsView() }
                    Button("重新读取最新状态") { Task { await load() } }
                    Button("我已核对记录，解除提交保护") { unlock = true }
                }
                else { Button(busy ? "正在提交…" : withdrawal ? "提交提现" : "提交充值核验") { confirmation = true }.disabled(busy || !Money.positive(amount, scale: 18) || info["bound_address"].string.isEmpty || (!withdrawal && hash.count != 66)) }
            }
            if let error { ErrorBanner(message: error) }
        }.navigationTitle(withdrawal ? "提现" : "充值").task { await load() }
            .confirmationDialog(withdrawal ? "确认提现 \(amount) \(symbol)？" : "确认提交链上充值记录？", isPresented: $confirmation, titleVisibility: .visible) { Button("确认提交") { submit() } }
            .confirmationDialog("请确认已在交易记录核实结果；解除后再次提交会创建新申请", isPresented: $unlock, titleVisibility: .visible) { Button("已核实，解除保护") { Task { await LocalStore.shared.remove("withdraw-pending", userID: session.user?.id ?? 0); uncertain = false; amount = "" } } }
    }
    private func load() async {
        let owner = session.user?.id ?? 0
        if withdrawal, let saved: Record = await LocalStore.shared.read("withdraw-pending", userID: owner) { uncertain = true; amount = saved["amount"].string; symbol = saved["symbol"].string }
        do { info = try await APIClient.shared.get(withdrawal ? "wallet.binding" : "wallet.deposit.info") } catch { self.error = error.localizedDescription }
    }
    private func submit() {
        guard !busy, !uncertain, let owner = session.user?.id, owner > 0 else { return }
        var body: [String: JSON] = ["symbol": .string(symbol), "chain": "BSC", "amount": .string(amount)]
        body[withdrawal ? "address" : "tx_hash"] = .string(withdrawal ? info["bound_address"].string : hash)
        busy = true
        Task {
            defer { busy = false }
            if withdrawal {
                do { try await LocalStore.shared.save(Record(body), key: "withdraw-pending", userID: owner) }
                catch { self.error = "无法保存提现恢复记录，请检查设备存储后重试"; return }
            }
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                try await APIClient.shared.post(withdrawal ? "wallet.withdraw" : "wallet.deposit.submit", body)
                if withdrawal { await LocalStore.shared.remove("withdraw-pending", userID: owner) }
                amount = ""; hash = ""; error = "已提交，请在交易记录查看处理状态"
            } catch {
                if withdrawal, (error as? APIError)?.definitiveRejection == true { await LocalStore.shared.remove("withdraw-pending", userID: owner) }
                else if withdrawal { uncertain = true }
                self.error = uncertain ? "提交结果未确认，请先在交易记录核对，避免重复申请。" : error.localizedDescription
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
    @State private var confirm = false
    @State private var error: String?
    var body: some View {
        Form {
            Section("USDT → SPX001") { TextField("USDT 数量", text: $amount).keyboardType(.decimalPad).disabled(frozen || busy); InfoRow(title: "每 USDT 预计获得", value: quote["spx_per_usdt"].string + " SPX001"); InfoRow(title: "参考单价 USD", value: quote["exchange_price_usd"].string); InfoRow(title: "行情时间", value: quote["updated_at"].string); Text("实际成交由服务器按提交时行情计算。").font(.footnote).foregroundStyle(.secondary) }
            if let error { ErrorBanner(message: error) }
            Section { Button(frozen ? "核对 / 重试该次兑换" : "确认兑换") { confirm = true }.disabled(busy || !Money.positive(amount, scale: 18)); NavigationLink("兑换与交易记录") { WalletTransactionsView() } }
        }.navigationTitle("兑换")
            .task { do { quote = try await APIClient.shared.get("wallet.exchange.quote"); if let saved: Record = await LocalStore.shared.read("exchange-pending", userID: session.user?.id ?? 0) { requestID = saved["request_id"].string; amount = saved["amount"].string; frozen = true } } catch { self.error = error.localizedDescription } }
            .confirmationDialog("确认将 \(amount) USDT 兑换为 SPX001？", isPresented: $confirm, titleVisibility: .visible) { Button("确认兑换") { exchange() } }
    }
    private func exchange() {
        guard !busy, let owner = session.user?.id, owner > 0 else { return }
        busy = true; frozen = true
        let pending = Record(["request_id": .string(requestID), "amount": .string(amount)])
        Task { defer { busy = false }
            do {
                try await LocalStore.shared.save(pending, key: "exchange-pending", userID: owner)
            } catch { frozen = false; self.error = "无法保存兑换恢复记录，请检查设备存储"; return }
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                try await APIClient.shared.post("wallet.exchange", ["usdt_amount": pending["amount"], "request_id": pending["request_id"]])
                await LocalStore.shared.remove("exchange-pending", userID: owner)
                frozen = false; amount = ""; requestID = UUID().uuidString.lowercased(); error = "兑换已完成，请查看资产余额"
            }
            catch {
                if (error as? APIError)?.definitiveRejection == true { await LocalStore.shared.remove("exchange-pending", userID: owner); frozen = false; requestID = UUID().uuidString.lowercased(); self.error = error.localizedDescription }
                else { self.error = "兑换结果待核对，重试将沿用同一请求编号。\(error.localizedDescription)" }
            }
        }
    }
}

struct WalletTransactionsView: View {
    @State private var rows: [Record] = []
    @State private var filter = "all"
    @State private var error: String?
    @State private var cancel: Record?
    var body: some View {
        List {
            Picker("资产", selection: $filter) { Text("全部").tag("all"); Text("SPX001").tag("SPX001"); Text("USDT").tag("USDT") }
            ForEach(rows.filter { filter == "all" || $0["symbol"].string == filter }) { row in
                DisclosureGroup {
                    ForEach(["id", "chain", "address", "tx_hash", "fee_amount", "created_at"], id: \.self) { key in if !row[key].string.isEmpty { InfoRow(title: ["id": "流水号", "chain": "网络", "address": "钱包地址", "tx_hash": "交易哈希", "fee_amount": "手续费", "created_at": "创建时间"][key] ?? key, value: row[key].string) } }
                    if row["type"].string == "withdraw", row["status"].string == "pending" { Button("取消提现", role: .destructive) { cancel = row } }
                } label: { HStack { VStack(alignment: .leading, spacing: 5) { Text(DisplayLabels.transaction(row["type"].string)).font(.subheadline.weight(.medium)); Text(DisplayLabels.transactionStatus(row["status"].string)).font(.caption).foregroundStyle(.secondary) }; Spacer(); Text("\(row["amount"].string) \(row["symbol"].string)").font(.subheadline.weight(.semibold)) } }
            }
            if rows.isEmpty { EmptyState(title: "暂无交易记录") }
        }.navigationTitle("交易记录").task { await load() }.refreshable { await load() }.errorAlert($error)
            .confirmationDialog("确认取消这笔待处理提现？", isPresented: Binding(get: { cancel != nil }, set: { if !$0 { cancel = nil } }), titleVisibility: .visible) { Button("取消提现", role: .destructive) { if let cancel { Task { do { try await APIClient.shared.post("wallet.withdraw.cancel", ["transaction_id": cancel["id"]]); await load() } catch { self.error = error.localizedDescription } }; self.cancel = nil } } }
    }
    private func load() async { do { rows = try await APIClient.shared.get("wallet.transactions").list } catch { self.error = error.localizedDescription } }
}

struct StakingView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var plans: [Record] = []
    @State private var records: [Record] = []
    @State private var assets: [Record] = []
    @State private var selected = 0
    @State private var amount = "100000"
    @State private var phase: JSON = .null
    @State private var confirm: String?
    @State private var redeemID = 0
    @State private var busy = false
    @State private var error: String?
    @State private var uncertain = false
    @State private var unlock = false
    var body: some View {
        Form {
            if uncertain {
                Section("提交结果待核对") {
                    Text("上次质押申请结果未确认，请先核对我的质押与交易记录，再决定是否发起新申请。")
                    NavigationLink("交易记录") { WalletTransactionsView() }
                    Button("重新核对质押记录") { Task { await load() } }
                    Button("已核实记录，解除保护") { unlock = true }
                }
            }
            Section("当前方案") {
                Text(phase["name"].string).font(.headline)
                InfoRow(title: "可质押 SPX001", value: assets.first(where: { $0["symbol"].string == "SPX001" })?["stakeable_balance"].string ?? "—")
                Picker("选择期限", selection: $selected) { Text("请选择").tag(0); ForEach(plans) { plan in Text("\(plan.name) · \(plan["plan_days"].string) 天 · \(plan["apy"].string)%").tag(plan.id) } }
                TextField("质押数量", text: $amount).keyboardType(.numberPad)
                Text("须为 100000 的整数倍，单人累计上限与可质押余额由服务器核对。").font(.caption).foregroundStyle(.secondary)
                Button("确认质押") { confirm = "create" }.disabled(busy || selected == 0 || Int(amount).map { $0 < 100000 || $0 > 10000000 || $0 % 100000 != 0 } != false)
            }.disabled(uncertain)
            Section("我的质押") { ForEach(records) { record in VStack(alignment: .leading, spacing: 8) { Text(record.text("plan_name", "name", "amount")).font(.headline); InfoRow(title: "本金", value: record["amount"].string); InfoRow(title: "已发分红", value: record["paid_dividend"].string); InfoRow(title: "状态", value: DisplayLabels.transactionStatus(record["status"].string)); if record["can_redeem"].flag { Button("赎回到期本金") { redeemID = record.id; confirm = "redeem" } } } } }
            if let error { ErrorBanner(message: error) }
        }.navigationTitle("锁仓质押").task { await load() }.refreshable { await load() }.disabled(busy)
            .confirmationDialog(confirm == "create" ? "确认质押 \(amount) SPX001？" : "确认赎回到期本金？", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible) { Button("确认") { let action = confirm; confirm = nil; submit(create: action == "create") } }
            .confirmationDialog("解除后可创建新的质押，请确认已经核实上次申请", isPresented: $unlock, titleVisibility: .visible) { Button("确认已核实") { Task { await LocalStore.shared.remove("staking-pending", userID: session.user?.id ?? 0); uncertain = false } } }
    }
    private func load() async {
        let pending: Record? = await LocalStore.shared.read("staking-pending", userID: session.user?.id ?? 0)
        uncertain = pending != nil
        do { let data = try await APIClient.shared.get("staking.plans"); plans = data["plans"].list; phase = data["phase"]; records = try await APIClient.shared.get("staking").list; assets = try await APIClient.shared.get("wallet").list } catch { self.error = error.localizedDescription }
    }
    private func submit(create: Bool) {
        guard !busy, !create || !uncertain, let owner = session.user?.id, owner > 0 else { return }
        let body: [String: JSON] = create ? ["plan_id": .number(Decimal(selected)), "amount": .string(amount)] : ["id": .number(Decimal(redeemID))]
        busy = true
        Task {
            defer { busy = false }
            if create {
                do { try await LocalStore.shared.save(Record(body), key: "staking-pending", userID: owner) }
                catch { self.error = "无法保存质押恢复记录，请检查设备存储"; return }
            }
            do {
                guard session.user?.id == owner else { throw APIError.staleSession }
                try await APIClient.shared.post(create ? "staking.create" : "staking.redeem", body)
                if create { await LocalStore.shared.remove("staking-pending", userID: owner) }
                await load()
            } catch {
                if create, (error as? APIError)?.definitiveRejection == true { await LocalStore.shared.remove("staking-pending", userID: owner) }
                self.error = error.localizedDescription; await load()
            }
        }
    }
}
