import SwiftUI
import UIKit

extension Notification.Name { static let spxOpenPacket = Notification.Name("spx.openPacket") }

struct RedPacketSendView: View {
    let scope: String
    let targetID: Int
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var mode = "ordinary"
    @State private var symbol = "SPX001"
    @State private var countText = "1"
    @FocusState private var inputFocus: PacketInput?
    @State private var amount = ""
    @State private var greeting = "恭喜发财，大吉大利"
    @State private var password = ""
    @State private var requestID = UUID().uuidString.lowercased()
    @State private var busy = false
    @State private var uncertain = false
    @State private var error: String?
    @State private var confirm = false
    @State private var paymentSettings = false
    @State private var needsPaymentPassword = false
    @State private var restored = false
    @State private var balanceText = "可用余额读取中…"
    @State private var balanceLoading = false
    private enum PacketInput: Hashable { case count, amount, greeting }
    private var count: Int { scope == "group" ? Int(countText) ?? 0 : 1 }
    private var validCount: Bool { (1...100).contains(count) }
    private var allocationText: String {
        if mode == "random" { return "每人随机金额，试试谁的手气最好" }
        guard let total = Decimal(string: amount), count > 0 else { return "总金额按红包个数平均分配" }
        let each = total / Decimal(count)
        return "每人 \(NSDecimalNumber(decimal: each).stringValue) \(symbol)"
    }
    var body: some View {
        SPXNavigationContainer {
            ScrollView {
                VStack(spacing: 16) {
                    VStack(spacing: 10) {
                        Image(systemName: "gift.fill")
                            .spxFont(25, weight: .semibold)
                            .foregroundStyle(SPXColor.hex(0xD34B3F))
                            .frame(width: 58, height: 58)
                            .background(SPXColor.hex(0xFBE5DC), in: RoundedRectangle(cornerRadius: 18))
                        Text("把心意装进红包").spxFont(23, weight: .bold)
                        Text("\(symbol) · 领取后自动存入钱包").spxFont(12).foregroundStyle(SPXColor.muted)
                    }
                    HStack(spacing: 4) {
                        Button { mode = "ordinary" } label: { Text("普通红包").frame(maxWidth: .infinity).frame(height: 42).foregroundStyle(mode == "ordinary" ? SPXColor.hex(0xC34438) : SPXColor.muted).background(mode == "ordinary" ? Color.white : Color.clear, in: RoundedRectangle(cornerRadius: 9)) }
                        Button { if scope == "group" { mode = "random" } } label: { Text("拼手气红包").frame(maxWidth: .infinity).frame(height: 42).foregroundStyle(mode == "random" ? SPXColor.hex(0xC34438) : SPXColor.muted).background(mode == "random" ? Color.white : Color.clear, in: RoundedRectangle(cornerRadius: 9)) }.disabled(scope != "group")
                    }
                    .padding(4)
                    .background(SPXColor.hex(0xEBE7E1), in: RoundedRectangle(cornerRadius: 12))
                    Picker("红包币种", selection: $symbol) {
                        Text("SPX001").tag("SPX001")
                        Text("XKRS").tag("XKRS")
                    }.pickerStyle(.segmented).onChange(of: symbol) { _ in loadBalance() }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("总金额 · \(symbol)").spxFont(13).foregroundStyle(SPXColor.muted)
                        TextField("0.00", text: $amount)
                            .spxFont(34, weight: .semibold)
                            .foregroundStyle(Color.black)
                            .tint(SPXColor.hex(0xC34438))
                            .keyboardType(.decimalPad)
                            .focused($inputFocus, equals: .amount)
                            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                            .padding(.horizontal, 16)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
                        if scope == "group" {
                            HStack {
                                Text("红包个数").spxFont(15)
                                Spacer()
                                TextField("1–100 个", text: $countText)
                                    .foregroundStyle(SPXColor.ink)
                                    .tint(SPXColor.primary)
                                    .keyboardType(.numberPad)
                                    .multilineTextAlignment(.trailing)
                                    .frame(width: 110)
                                    .focused($inputFocus, equals: .count)
                                    .accessibilityLabel("红包个数，1 至 100 个")
                                    .onChange(of: countText, perform: sanitizeCountText)
                                Text("个").spxFont(13).foregroundStyle(SPXColor.muted)
                            }.frame(minHeight: 44)
                            Text(validCount ? "每次可发送 1–100 个红包" : "请输入 1–100 之间的整数").spxFont(12).foregroundStyle(validCount ? SPXColor.muted : Color.red)
                        }
                        Text(allocationText).spxFont(12).foregroundStyle(SPXColor.muted)
                    }
                    .padding(16)
                    .background(SPXColor.card, in: RoundedRectangle(cornerRadius: 14))
                    VStack(alignment: .leading, spacing: 8) {
                        Text("祝福语").spxFont(13).foregroundStyle(SPXColor.muted)
                        TextField("恭喜发财，大吉大利", text: $greeting)
                            .foregroundStyle(Color.black)
                            .tint(SPXColor.hex(0xC34438))
                            .focused($inputFocus, equals: .greeting)
                            .onChange(of: greeting) { value in if value.count > 50 { greeting = String(value.prefix(50)) } }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 48)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .padding(16)
                    .background(SPXColor.card, in: RoundedRectangle(cornerRadius: 14))
                    Button { if !balanceLoading { loadBalance() } } label: {
                        HStack(spacing: 6) {
                            Image(systemName: balanceLoading ? "arrow.triangle.2.circlepath" : "wallet.pass")
                            Text(balanceText)
                            Spacer()
                        }
                        .spxFont(12, weight: .medium)
                        .foregroundStyle(SPXColor.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }.buttonStyle(.plain).disabled(balanceLoading)
                    Text("24 小时未领完自动退回。\(scope == "group" ? "仅发送时的群成员可以领取。" : "")")
                        .spxFont(12).foregroundStyle(SPXColor.muted).frame(maxWidth: .infinity, alignment: .leading)
                    if uncertain { ErrorBanner(message: "上次发送结果待确认，请先核对，避免重复扣款。") }
                }.padding(24).disabled(!restored || uncertain || busy)
        }.spxDismissKeyboardOnTap()
                .safeAreaInset(edge: .bottom) {
                    Button { inputFocus = nil; if uncertain { resolve() } else { checkPaymentPassword() } } label: {
                        HStack { Spacer(); if busy { ProgressView().tint(.white) }; Text(uncertain ? "核对上次发送结果" : "塞钱进红包"); Spacer() }
                            .spxFont(16, weight: .semibold).foregroundStyle(.white).frame(minHeight: 50).background(SPXColor.hex(0xD34B3F).opacity(busy ? 0.5 : 1),in: RoundedRectangle(cornerRadius: 14))
                    }.disabled(!restored || busy || (!uncertain && (!validCount || !Money.positive(amount,scale: 6)))).padding(16).background(SPXColor.card)
                }
                .navigationTitle("发红包").spxPage()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) }
                    ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("完成") { inputFocus = nil } }
                }
                .tint(SPXColor.hex(0xC34438)).errorAlert($error)
                .sheet(isPresented: $confirm) { paymentConfirmation }
                .alert("请先设置支付密码", isPresented: $needsPaymentPassword) {
                    Button("去设置") { paymentSettings = true }
                    Button("稍后", role: .cancel) { }
                } message: { Text("发红包需要独立的六位数字支付密码，首次可直接设置。") }
                .sheet(isPresented: $paymentSettings) { SPXNavigationContainer { PaymentPasswordView().toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { paymentSettings = false } } } } }
                .task { if let saved: String = await LocalStore.shared.read("redpacket-pending-\(scope)-\(targetID)",userID: session.user?.id ?? 0) { requestID = saved; uncertain = true }; restored = true; loadBalance() }
        }
    }
    private var paymentConfirmation: some View {
        SPXNavigationContainer {
            SPXDetailPage {
                SPXPageHeading(title: "确认发送红包", subtitle: "总额 \(DisplayLabels.amount(amount)) \(symbol)，共 \(count) 个", icon: "gift")
                SPXPanel("支付验证", footer: "确认后从资产扣除，请输入独立支付密码。") {
                    SPXField("支付密码") { SecureField("六位数字支付密码", text: $password).foregroundStyle(SPXColor.ink).tint(SPXColor.primary).keyboardType(.numberPad).textContentType(.password) }
                }
                if let error { ErrorBanner(message: error) }
            }.safeAreaInset(edge: .bottom, spacing: 0) {
                SPXBottomAction { Button("确认发送") { send() }.disabled(busy || password.count != 6) }
            }.navigationTitle("确认支付").toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { password = ""; confirm = false } }
            }.onDisappear { password = "" }
        }
    }
    private func checkPaymentPassword() {
        guard !busy else { return }; busy = true
        let owner = session.user?.id
        Task { defer { busy = false }; do { let result = try await APIClient.shared.get("payment.password.status"); guard session.user?.id == owner else { return }; password = ""; if result["configured"].flag { confirm = true } else { needsPaymentPassword = true } } catch { self.error = error.localizedDescription } }
    }

    private func sanitizeCountText(_ value: String) {
        let sanitized = value.filter { $0.isASCII && $0.isNumber }
        guard sanitized != value else { return }
        countText = sanitized
    }
    private func loadBalance() {
        guard !balanceLoading else { return }
        balanceLoading = true
        let owner = session.user?.id
        let selectedSymbol = symbol
        Task {
            defer { balanceLoading = false }
            do {
                let assets = try await APIClient.shared.get("wallet").list
                guard session.user?.id == owner else { return }
                if let asset = assets.first(where: { $0["symbol"].string == selectedSymbol && ($0["chain"].string.isEmpty || $0["chain"].string == "BSC") }) {
                    balanceText = "可用余额 \(DisplayLabels.amount(asset["balance"].string)) \(selectedSymbol)"
                } else {
                    balanceText = "可用余额暂未读取 · 点击重试"
                }
            } catch {
                if session.user?.id == owner { balanceText = "余额暂未获取 · 点击重试" }
            }
        }
    }
    private func send() {
        guard restored, !busy, !uncertain, let owner = session.user?.id, owner > 0 else { return }
        guard validCount else { error = "红包个数请输入 1–100 之间的整数"; return }
        guard password.count == 6, password.allSatisfy({ $0.isASCII && $0.isNumber }) else { error = "请输入六位数字支付密码"; return }
        guard greeting.count <= 50 else { error = "祝福语最多 50 字"; return }
        guard let total = Decimal(string: amount), total >= Decimal(count) * Decimal(string: "0.000001")! else { error = "每个红包至少 0.000001 \(symbol)"; return }
        if mode == "ordinary" { let each = total / Decimal(count); var raw = each; var rounded = Decimal(); NSDecimalRound(&rounded, &raw, 6, .down); guard rounded * Decimal(count) == total else { error = "普通红包需可按六位小数平均分配，请调整总额或个数"; return } }
        busy = true
        let payload: [String: JSON] = ["request_id": .string(requestID), "scope": .string(scope), "target_id": .number(Decimal(targetID)), "mode": .string(mode), "count": .number(Decimal(count)), "amount": .string(amount), "symbol": .string(symbol), "chain": .string("BSC"), "greeting": .string(greeting), "payment_password": .string(password)]
        confirm = false
        Task {
            defer { busy = false; password = "" }
            do { try await LocalStore.shared.reservePending(requestID, key: "redpacket-pending-\(scope)-\(targetID)", userID: owner) }
            catch { if let saved: String = await LocalStore.shared.read("redpacket-pending-\(scope)-\(targetID)", userID: owner) { requestID = saved; uncertain = true }; self.error = "已有待核对红包或存储不可用，请先核对结果"; return }
            do { guard session.user?.id == owner else { throw APIError.staleSession }; let result = try await APIClient.shared.post("redpackets.create", payload); guard session.user?.id == owner else { throw APIError.staleSession }; ChatSoundEffects.shared.packet(result["message"].record); await LocalStore.shared.remove("redpacket-pending-\(scope)-\(targetID)", userID: owner); dismiss() }
            catch {
                if (error as? APIError)?.definitiveRejection == true { await LocalStore.shared.remove("redpacket-pending-\(scope)-\(targetID)", userID: owner); requestID = UUID().uuidString.lowercased(); self.error = error.localizedDescription }
                else { uncertain = true; self.error = "发送结果需要核对：\(error.localizedDescription)" }
            }
        }
    }
    private func resolve() {
        guard !busy, let owner = session.user?.id else { return }
        busy = true
        Task { defer { busy = false }; do { let result = try await APIClient.shared.get("redpackets.request", ["request_id": requestID]); guard session.user?.id == owner else { throw APIError.staleSession }; await LocalStore.shared.remove("redpacket-pending-\(scope)-\(targetID)", userID: owner); if result["exists"].flag { ChatSoundEffects.shared.packet(result["message"].record); dismiss() } else { requestID = UUID().uuidString.lowercased(); uncertain = false; error = "服务器已核对，红包未创建，可以重新填写发送" } } catch { self.error = error.localizedDescription } }
    }
}

struct RedPacketPopup: View {
    let packetID: String
    let close: () -> Void
    @EnvironmentObject private var session: SessionStore
    @State private var packet: JSON = .null
    @State private var busy = false
    @State private var loading = false
    @State private var claimCompleted = false
    @State private var claimRequestConfirmed = false
    @State private var awaitingClaimSound = false
    @State private var claimOwnerID: Int?
    @State private var showingClaims = false
    @State private var coinRotation = 0.0
    @State private var error: String?
    private var packetSymbol: String { ["SPX001", "XKRS"].contains(packet["symbol"].string) ? packet["symbol"].string : "SPX001" }
    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea().onTapGesture { if !busy { close() } }
            GeometryReader { geometry in
                ScrollView(showsIndicators: false) {
                    packetCard
                        .frame(maxWidth: 310)
                        .padding(.horizontal, 24)
                        .frame(maxWidth: .infinity)
                        // Fill the viewport before centering; expanded claim
                        // records can still grow beyond it and scroll normally.
                        .frame(minHeight: max(0, geometry.size.height - 32), alignment: .center)
                        .padding(.vertical, 16)
                }
            }
        }
        .task { load() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            playClaimSoundIfConfirmed(packet)
        }
        .onReceive(VoicePlayback.shared.$playing) { playing in
            if !playing, !VoicePlayback.shared.loading { playClaimSoundIfConfirmed(packet) }
        }
        .onReceive(VoicePlayback.shared.$loading) { loading in
            if !loading, !VoicePlayback.shared.playing { playClaimSoundIfConfirmed(packet) }
        }
        .onReceive(CallCoordinator.shared.$call) { call in
            if case nil = call { playClaimSoundIfConfirmed(packet) }
        }
    }
    private var packetCard: some View {
        VStack(spacing: 0) {
            envelopeHeader
            VStack(spacing: 14) {
                claimAction
                if let error { Text(error).font(.footnote).foregroundStyle(Color(red: 0.72, green: 0.22, blue: 0.17)).multilineTextAlignment(.center) }
                if !packet["id"].string.isEmpty { claimsSection }
                Button("关闭") { close() }.font(.subheadline).foregroundStyle(SPXColor.muted).disabled(busy).padding(.top, 2)
            }.padding(.horizontal, 22).padding(.vertical, 20).frame(maxWidth: .infinity).background(Color.white)
        }
        .clipShape(RoundedRectangle(cornerRadius: 17))
        .shadow(color: .black.opacity(0.20), radius: 24, y: 10)
    }
    private var envelopeHeader: some View {
        VStack(spacing: 10) {
            Text(packet["sender_name"].string.isEmpty ? "SPX 红包" : packet["sender_name"].string + "的红包")
                .spxFont(16, weight: .semibold).lineLimit(1).minimumScaleFactor(0.8)
            Text(packet["greeting"].string.isEmpty ? "恭喜发财，大吉大利" : packet["greeting"].string)
                .spxFont(14).multilineTextAlignment(.center).lineLimit(2)
            Text("\(packetSymbol) · 领取后自动存入钱包")
                .spxFont(11).foregroundStyle(.white.opacity(0.76))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 22).padding(.top, 26).padding(.bottom, 24)
        .frame(maxWidth: .infinity)
        .background(LinearGradient(colors: [Color(red: 0.86, green: 0.25, blue: 0.19), Color(red: 0.70, green: 0.12, blue: 0.12)], startPoint: .topLeading, endPoint: .bottomTrailing))
    }
    @ViewBuilder private var claimAction: some View {
        if let amount = confirmedAmount {
            VStack(spacing: 5) {
                Text(DisplayLabels.amount(amount)).spxFont(30, weight: .bold, design: .rounded).monospacedDigit().foregroundStyle(Color(red: 0.78, green: 0.19, blue: 0.13))
                Text("\(packetSymbol) · 已存入钱包可用余额").font(.footnote).foregroundStyle(SPXColor.muted)
            }.padding(.vertical, 5)
        } else if claimCompleted {
            Label("领取成功，正在更新状态", systemImage: "checkmark.circle.fill").font(.subheadline.weight(.semibold)).foregroundStyle(Color(red: 0.72, green: 0.22, blue: 0.17))
            Button(loading ? "正在刷新…" : "刷新领取状态") { load() }.font(.footnote.weight(.semibold)).disabled(loading || busy)
        } else if packet["can_claim"].flag {
            Button { claim() } label: {
                ZStack {
                    Circle().fill(LinearGradient(colors: [Color(red: 1, green: 0.89, blue: 0.60), Color(red: 0.88, green: 0.62, blue: 0.27)], startPoint: .topLeading, endPoint: .bottomTrailing))
                    Circle().stroke(Color(red: 0.66, green: 0.40, blue: 0.17).opacity(0.75), lineWidth: 1).padding(5)
                    Circle().stroke(Color.white.opacity(0.65), lineWidth: 1).padding(9)
                    if busy { ProgressView().tint(Color(red: 0.48, green: 0.29, blue: 0.12)) }
                    else { Text("拆").spxFont(34, weight: .medium, design: .serif).foregroundStyle(Color(red: 0.48, green: 0.29, blue: 0.12)) }
                }
                .frame(width: 100, height: 100)
                .rotation3DEffect(.degrees(coinRotation), axis: (x: 0, y: 1, z: 0), perspective: 0.45)
                .accessibilityLabel("拆红包")
            }
            .buttonStyle(.plain).disabled(busy)
            Text(busy ? "正在拆红包…" : "轻触铜钱 · 拆开红包").font(.footnote).foregroundStyle(SPXColor.muted)
        } else if packet["id"].string.isEmpty {
            if loading { ProgressView().tint(Color(red: 0.72, green: 0.22, blue: 0.17)) }
            else { Button("重新加载红包") { load() }.font(.footnote.weight(.semibold)).disabled(loading || busy) }
        } else {
            Text(ineligibleMessage).font(.subheadline).foregroundStyle(SPXColor.ink).multilineTextAlignment(.center)
            Button(loading ? "正在刷新…" : "刷新领取状态") { load() }.font(.footnote.weight(.semibold)).disabled(loading || busy)
        }
    }
    @ViewBuilder private var claimsSection: some View {
        VStack(spacing: 10) {
            HStack {
                Text("已领取 \(packet["claimed_count"].int) / \(packet["total_count"].int) 个")
                Spacer(minLength: 8)
                if !packet["total_amount"].string.isEmpty { Text("共 \(DisplayLabels.amount(packet["total_amount"].string)) \(packetSymbol)") }
            }.font(.caption).foregroundStyle(SPXColor.muted)
            if packet["status"].string == "expired", !packet["refund_amount"].string.isEmpty {
                Text("未领取金额 \(DisplayLabels.amount(packet["refund_amount"].string)) \(packetSymbol) 已退回")
                    .font(.caption).foregroundStyle(Color(red: 0.72, green: 0.22, blue: 0.17)).frame(maxWidth: .infinity, alignment: .leading)
            }
            if !packet["claims"].array.isEmpty {
                Button { withAnimation(.easeInOut(duration: 0.2)) { showingClaims.toggle() } } label: {
                    HStack { Text(showingClaims ? "收起领取详情" : "查看领取详情"); Spacer(); Image(systemName: showingClaims ? "chevron.up" : "chevron.down") }
                        .font(.caption.weight(.semibold)).foregroundStyle(Color(red: 0.72, green: 0.22, blue: 0.17))
                }.buttonStyle(.plain)
                if showingClaims {
                    VStack(spacing: 9) {
                        ForEach(packet["claims"].array.indices, id: \.self) { index in
                            let claim = packet["claims"].array[index]
                            HStack { Text(claim["nickname"].string.isEmpty ? "好友" : claim["nickname"].string).lineLimit(1); Spacer(minLength: 8); Text(DisplayLabels.amount(claim["amount"].string) + " \(packetSymbol)").monospacedDigit() }
                                .font(.caption).foregroundStyle(SPXColor.ink)
                        }
                    }.padding(.top, 2)
                }
            }
        }
        .padding(13)
        .background(Color(red: 0.98, green: 0.97, blue: 0.95), in: RoundedRectangle(cornerRadius: 8))
    }
    private var confirmedAmount: String? {
        guard !packet["my_amount"].string.isEmpty, (packet["my_amount"].decimal ?? 0) > 0 else { return nil }
        return packet["my_amount"].string
    }
    private var ineligibleMessage: String {
        if packet["status"].string == "completed" { return "手慢了，红包已领完" }
        if packet["status"].string == "expired" { return "红包已过期" }
        if packet["sender_id"].int == session.user?.id { return "这是你发出的红包，查看领取进度" }
        return packet["claim_disabled_reason"].string.isEmpty ? "当前账号不在红包领取范围内" : packet["claim_disabled_reason"].string
    }
    private func load() {
        guard !loading else { return }
        loading = true
        let owner = session.user?.id
        Task {
            defer { loading = false }
            do {
                let result = try await APIClient.shared.get("redpackets.detail", ["id": packetID])
                guard session.user?.id == owner, result["id"].string == packetID else { throw APIError.staleSession }
                packet = result
                playClaimSoundIfConfirmed(result)
                if confirmedAmount != nil { claimRequestConfirmed = true }
                claimCompleted = claimRequestConfirmed && confirmedAmount == nil
                error = nil
            } catch { discardInaccessiblePacket(error); self.error = error.localizedDescription }
        }
    }
    private func discardInaccessiblePacket(_ error: Error) {
        if let apiError = error as? APIError, case .server(let code, _) = apiError, [403,404,422].contains(code) { packet = .null }
    }
    private func claim() {
        guard !busy, !loading, packet["can_claim"].flag, let owner = session.user?.id, owner > 0 else { return }
        busy = true
        awaitingClaimSound = true
        claimOwnerID = owner
        withAnimation(.easeOut(duration: 0.48)) { coinRotation += 360 }
        Task {
            defer { busy = false }
            do {
                let receipt = try await APIClient.shared.post("redpackets.claim", ["id": .string(packetID)])
                guard session.user?.id == owner, receipt["id"].string == packetID else { throw APIError.staleSession }
                packet = receipt
                claimRequestConfirmed = true
                claimCompleted = true
                error = nil
                playClaimSoundIfConfirmed(receipt)
                do {
                    let latest = try await APIClient.shared.get("redpackets.detail", ["id": packetID])
                    guard session.user?.id == owner, latest["id"].string == packetID else { throw APIError.staleSession }
                    packet = latest
                    playClaimSoundIfConfirmed(latest)
                    claimCompleted = confirmedAmount == nil
                } catch {
                    self.error = "领取已提交，状态暂时无法刷新，请稍后重试。"
                }
            } catch {
                guard session.user?.id == owner else { return }
                discardInaccessiblePacket(error)
                self.error = error.localizedDescription
                if let result = try? await APIClient.shared.get("redpackets.detail", ["id": packetID]), session.user?.id == owner, result["id"].string == packetID {
                    packet = result
                    playClaimSoundIfConfirmed(result)
                    if confirmedAmount != nil { claimRequestConfirmed = true }
                    claimCompleted = claimRequestConfirmed && confirmedAmount == nil
                } else {
                    claimCompleted = true
                    self.error = "领取结果待确认，请刷新领取状态后再操作。"
                }
            }
        }
    }
    private func playClaimSoundIfConfirmed(_ receipt: JSON) {
        guard awaitingClaimSound, let owner = session.user?.id, owner == claimOwnerID, owner > 0, receipt["id"].string == packetID,
              let amount = receipt["my_amount"].decimal, amount > 0 else { return }
        if ChatSoundEffects.shared.claimed(receipt: "\(owner):\(packetID)") { awaitingClaimSound = false }
    }
}
