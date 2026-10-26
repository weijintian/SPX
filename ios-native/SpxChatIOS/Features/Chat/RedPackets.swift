import SwiftUI

extension Notification.Name { static let spxOpenPacket = Notification.Name("spx.openPacket") }

struct RedPacketSendView: View {
    let scope: String
    let targetID: Int
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var mode = "ordinary"
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
    @State private var restored = false
    private enum PacketInput: Hashable { case count, amount, greeting }
    private var count: Int { scope == "group" ? Int(countText) ?? 0 : 1 }
    private var validCount: Bool { (1...100).contains(count) }
    var body: some View {
        SPXNavigationContainer {
            ScrollView {
                VStack(spacing: 18) {
                    HStack { SPXIcon("gift").frame(width: 22,height: 22); Text("SPX001 红包").font(.system(size: 15,weight: .semibold)); Spacer() }.foregroundStyle(SPXColor.hex(0xC34438))
                    SPXPanel("红包信息") {
                        Picker("红包类型",selection: $mode) { Text("普通红包").tag("ordinary"); if scope == "group" { Text("拼手气红包").tag("random") } }.pickerStyle(.segmented)
                        if scope == "group" {
                            HStack {
                                Text("红包个数").font(.system(size: 15))
                                Spacer()
                                TextField("填写个数", text: $countText)
                                    .keyboardType(.numberPad).multilineTextAlignment(.trailing)
                                    .frame(minWidth: 70, maxWidth: 130).focused($inputFocus, equals: .count)
                                    .accessibilityLabel("红包个数，1 至 100 个")
                                    .onChange(of: countText) { value in
                                        let digits = value.filter { $0.isASCII && $0.isNumber }
                                        if digits != value { countText = digits }
                                    }
                                Text("个").font(.system(size: 13)).foregroundStyle(SPXColor.muted)
                            }.frame(minHeight: 44)
                            Text(validCount ? "每次可发送 1–100 个红包" : "请输入 1–100 之间的整数")
                                .font(.system(size: 12)).foregroundStyle(validCount ? SPXColor.muted : Color.red)
                        }
                        HStack { Text("总金额").font(.system(size: 15)); Spacer(); TextField("0.00",text: $amount).keyboardType(.decimalPad).multilineTextAlignment(.trailing).focused($inputFocus, equals: .amount); Text("SPX001").font(.system(size: 12)).foregroundStyle(SPXColor.muted) }.frame(minHeight: 44)
                        Text(mode == "random" ? "每个人领取的金额随机分配" : "每个人领取相同金额").font(.system(size: 12)).foregroundStyle(SPXColor.muted)
                    }
                    SPXPanel("祝福语") { TextField("恭喜发财，大吉大利",text: $greeting).focused($inputFocus, equals: .greeting).onChange(of: greeting) { value in if value.count > 50 { greeting = String(value.prefix(50)) } } }
                    Text("\(DisplayLabels.amount(amount.isEmpty ? "0" : amount)) SPX001").font(.system(size: 30,weight: .semibold)).monospacedDigit().minimumScaleFactor(0.5).lineLimit(1).padding(.vertical,16)
                    if uncertain { ErrorBanner(message: "上次发送结果待确认，请先核对，避免重复扣款。") }
                    Text("红包到账至 SPX001 资产，未领取部分由服务器按到期规则退回。普通红包按六位小数均分，实际金额以确认结果为准。")
                        .font(.system(size: 12)).foregroundStyle(SPXColor.muted).frame(maxWidth: .infinity,alignment: .leading)
                }.padding(20).disabled(!restored || uncertain || busy)
        }.spxDismissKeyboardOnTap()

                .safeAreaInset(edge: .bottom) {
                    Button { inputFocus = nil; if uncertain { resolve() } else { password = ""; confirm = true } } label: {
                        HStack { Spacer(); if busy { ProgressView().tint(.white) }; Text(uncertain ? "核对上次发送结果" : "塞钱进红包"); Spacer() }
                            .font(.system(size: 16,weight: .semibold)).foregroundStyle(.white).frame(minHeight: 50).background(SPXColor.hex(0xD34B3F).opacity(busy ? 0.5 : 1),in: RoundedRectangle(cornerRadius: 14))
                    }.disabled(!restored || busy || (!uncertain && (!validCount || !Money.positive(amount,scale: 6)))).padding(16).background(SPXColor.card)
                }
                .navigationTitle("发红包").spxPage()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy) }
                    ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("完成") { inputFocus = nil } }
                }
                .tint(SPXColor.hex(0xC34438)).errorAlert($error)
                .alert("确认发送红包",isPresented: $confirm) {
                    SecureField("请输入账号密码",text: $password).textContentType(.password)
                    Button("取消",role: .cancel) { password = "" }
                    Button("确认发送") { send() }.disabled(password.isEmpty)
                } message: { Text("总额 \(DisplayLabels.amount(amount)) SPX001，共 \(count) 个。确认后从资产扣除。") }
                .task { if let saved: String = await LocalStore.shared.read("redpacket-pending-\(scope)-\(targetID)",userID: session.user?.id ?? 0) { requestID = saved; uncertain = true }; restored = true }
        }
    }
    private func send() {
        guard restored, !busy, !uncertain, let owner = session.user?.id, owner > 0 else { return }
        guard validCount else { error = "红包个数请输入 1–100 之间的整数"; return }
        guard greeting.count <= 50 else { error = "祝福语最多 50 字"; return }
        guard let total = Decimal(string: amount), total >= Decimal(count) * Decimal(string: "0.000001")! else { error = "每个红包至少 0.000001 SPX001"; return }
        if mode == "ordinary" { let each = total / Decimal(count); var raw = each; var rounded = Decimal(); NSDecimalRound(&rounded, &raw, 6, .down); guard rounded * Decimal(count) == total else { error = "普通红包需可按六位小数平均分配，请调整总额或个数"; return } }
        busy = true
        let payload: [String: JSON] = ["request_id": .string(requestID), "scope": .string(scope), "target_id": .number(Decimal(targetID)), "mode": .string(mode), "count": .number(Decimal(count)), "amount": .string(amount), "greeting": .string(greeting), "password": .string(password)]
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
    @State private var error: String?
    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea().onTapGesture { if !busy { close() } }
            VStack(spacing: 22) {
                Image(systemName: "gift.fill").font(.system(size: 38)).foregroundStyle(.yellow)
                Text(packet["sender_name"].string.isEmpty ? "红包" : packet["sender_name"].string).font(.headline)
                Text(packet["greeting"].string.isEmpty ? "恭喜发财，大吉大利" : packet["greeting"].string).font(.title3.weight(.medium)).multilineTextAlignment(.center)
                if !packet["my_amount"].string.isEmpty {
                    Text("\(DisplayLabels.amount(packet["my_amount"].string)) SPX001").font(.title2.bold()).foregroundStyle(.yellow); Text("已存入资产").font(.footnote)
                } else if claimCompleted {
                    Label("领取成功，正在更新状态", systemImage: "checkmark.circle.fill").foregroundStyle(.yellow)
                    Button(loading ? "正在刷新…" : "刷新领取状态") { load() }.font(.footnote.weight(.semibold)).disabled(loading || busy)
                } else if packet["can_claim"].flag {
                    Button { claim() } label: { ZStack { Circle().fill(LinearGradient(colors: [.yellow, .orange], startPoint: .topLeading, endPoint: .bottomTrailing)).frame(width: 94, height: 94); Circle().stroke(.brown.opacity(0.4), lineWidth: 2).frame(width: 80, height: 80); if busy { ProgressView().tint(.brown) } else { Text("拆").font(.system(size: 38, weight: .medium, design: .serif)).foregroundStyle(.brown) } } }.disabled(busy)
                } else if !packet["my_amount"].string.isEmpty { Text("\(DisplayLabels.amount(packet["my_amount"].string)) SPX001").font(.title2.bold()).foregroundStyle(.yellow); Text("已存入资产").font(.footnote) }
                else if packet["id"].string.isEmpty {
                    if loading { ProgressView().tint(.white) }
                    else { Button("重新加载红包") { load() }.font(.footnote.weight(.semibold)).disabled(loading || busy) }
                }
                else { Text(ineligibleMessage).foregroundStyle(.white.opacity(0.86)).multilineTextAlignment(.center); Button(loading ? "正在刷新…" : "刷新领取状态") { load() }.font(.footnote.weight(.semibold)).disabled(loading || busy) }
                if let error { Text(error).font(.footnote).foregroundStyle(.yellow) }
                if !packet["claims"].array.isEmpty {
                    ScrollView { LazyVStack(spacing: 12) { ForEach(Array(packet["claims"].array.enumerated()), id: \.offset) { claim in HStack { Text(claim["nickname"].string); Spacer(); Text(DisplayLabels.amount(claim["amount"].string) + " SPX001") }.font(.caption) } } }.frame(maxHeight: 200)
                }
                Button("关闭") { close() }.foregroundStyle(.white.opacity(0.8)).disabled(busy)
            }.padding(30).frame(maxWidth: 320).foregroundStyle(.white).background(LinearGradient(colors: [Color(red: 0.86, green: 0.24, blue: 0.18), Color(red: 0.67, green: 0.1, blue: 0.1)], startPoint: .top, endPoint: .bottom), in: RoundedRectangle(cornerRadius: 24))
        }.task { load() }
    }
    private var ineligibleMessage: String {
        if packet["status"].string != "active" { return "红包已领完或已过期" }
        if packet["sender_id"].int == session.user?.id { return "这是你发出的红包，查看领取进度" }
        return "当前账号暂不可领取，请确认红包接收范围和群成员身份。"
    }
    private func load() {
        guard !loading else { return }
        loading = true
        Task { defer { loading = false }; do { packet = try await APIClient.shared.get("redpackets.detail", ["id": packetID]); claimCompleted = false; error = nil } catch { self.error = error.localizedDescription } }
    }
    private func claim() {
        guard !busy, packet["can_claim"].flag else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                packet = try await APIClient.shared.post("redpackets.claim", ["id": .string(packetID)])
                claimCompleted = true
                error = nil
                do {
                    packet = try await APIClient.shared.get("redpackets.detail", ["id": packetID])
                    claimCompleted = false
                } catch {
                    self.error = "领取已提交，状态暂时无法刷新，请稍后重试。"
                }
            } catch {
                self.error = error.localizedDescription
                if let result = try? await APIClient.shared.get("redpackets.detail", ["id": packetID]) {
                    packet = result
                    claimCompleted = false
                }
            }
        }
    }
}
