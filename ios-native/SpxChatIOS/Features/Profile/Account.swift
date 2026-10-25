import SwiftUI
import PhotosUI
import UIKit

struct ProfileView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var notice: String?
    private var spxID: String { session.user?["spx_id"].string ?? "" }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 16) {
                    NavigationLink { ProfileEditView() } label: { Avatar(url: session.user?.avatar ?? "", name: session.user?.name ?? "我", size: 80) }
                    VStack(alignment: .leading, spacing: 8) {
                        Text(session.user?.name ?? "我的账号").font(.system(size: 23, weight: .semibold)).foregroundStyle(.white).lineLimit(2)
                        Button { guard !spxID.isEmpty else { return }; UIPasteboard.general.string = spxID; notice = "SPX ID 已复制" } label: {
                            Label(spxID.isEmpty ? "SPX ID 读取中…" : spxID, systemImage: "square.on.square")
                                .font(.system(size: 12, weight: .medium)).foregroundStyle(SPXColor.primary)
                                .padding(.horizontal, 10).padding(.vertical, 6).background(SPXColor.hex(0xEDF3FF), in: Capsule())
                        }.disabled(spxID.isEmpty)
                        NavigationLink { QRCodeView(value: "SPXUSER:\(spxID)", title: session.user?.name ?? "我的二维码") } label: {
                            Label("我的二维码", systemImage: "qrcode").font(.system(size: 12)).foregroundStyle(.white.opacity(0.8))
                        }.disabled(spxID.isEmpty)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    NavigationLink { ProfileEditView() } label: { Text("编辑").font(.system(size: 14, weight: .medium)).foregroundStyle(.white).frame(minWidth: 54, minHeight: 44).background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 12)) }
                }.padding(20).background(LinearGradient(colors: [SPXColor.hex(0x182647), SPXColor.hex(0x324D82)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24))
                SPXSectionTitle(title: "账号资产")
                NavigationLink { WalletView() } label: { SPXMenuRow(title: "我的资产", subtitle: "资产余额、充值与提现", icon: "wallet") }
                NavigationLink { OrdersView() } label: { SPXMenuRow(title: "我的订单", subtitle: "订单进度、物流与售后", icon: "order") }
                NavigationLink { MerchantOverviewView() } label: { SPXMenuRow(title: "商家中心", subtitle: "申请开店、保证金、商品与订单", icon: "store") }
                NavigationLink { AddressView() } label: { SPXMenuRow(title: "收货地址", subtitle: "管理收货人和配送地址", icon: "mappin.and.ellipse") }
                NavigationLink { StakingView() } label: { SPXMenuRow(title: "质押记录", subtitle: "查看质押本金与收益", icon: "chart.line.uptrend.xyaxis") }
                SPXSectionTitle(title: "SPX 服务")
                NavigationLink { ReferralsView() } label: { SPXMenuRow(title: "邀请朋友", subtitle: "邀请记录与奖励明细", icon: "person.badge.plus") }
                NavigationLink { QuestionsView() } label: { SPXMenuRow(title: "我的提问", subtitle: "提交问题，查看回复", icon: "questionmark.bubble") }
                NavigationLink { SupportView() } label: { SPXMenuRow(title: "帮助与反馈", subtitle: "常见问题、客服工单与建议", icon: "bubble.left.and.text.bubble.right") }
                SPXSectionTitle(title: "通用")
                NavigationLink { SettingsView() } label: { SPXMenuRow(title: "设置", subtitle: "账号安全、通知、显示与隐私", icon: "settings") }
                Text("版本 \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                    .font(.system(size: 12)).foregroundStyle(SPXColor.muted).frame(maxWidth: .infinity).padding(.vertical, 12)
            }.buttonStyle(.plain).padding(16)
        }.navigationTitle("我的").spxPage().successToast($notice)
            .refreshable { do { try await session.refreshProfile() } catch { notice = "资料暂时无法刷新，请稍后再试" } }
    }
}

struct ProfileEditView: View {
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var nickname = ""
    @State private var remark = ""
    @State private var avatar = ""
    @State private var selected: PhotosPickerItem?
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPanel("头像") { HStack { Avatar(url: avatar, name: nickname, size: 72); Spacer(); PhotosPicker(selection: $selected, matching: .images) { Label(busy ? "处理中…" : "更换头像", systemImage: "camera") }.buttonStyle(.bordered) } }
            SPXPanel("个人信息") {
                SPXField("昵称") { TextField("请输入昵称", text: $nickname) }
                InfoRow(title: "SPX ID", value: session.user?["spx_id"].string ?? "")
                SPXField("个人简介") { TextField("介绍一下自己", text: $remark, axis: .vertical).lineLimit(3...8) }
            }
            if let error { ErrorBanner(message: error) }
        }.safeAreaInset(edge: .bottom, spacing: 0) { SPXBottomAction { Button(busy ? "保存中…" : "保存资料") { save() }.disabled(busy || nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) } }.navigationTitle("个人资料").disabled(busy)
            .onAppear { nickname = session.user?["nickname"].string ?? ""; remark = session.user?["remark"].string ?? ""; avatar = session.user?.avatar ?? "" }
            .onChange(of: selected) { _, item in guard let item else { return }; busy = true; Task { defer { busy = false }; do { let file = try await MediaFiles.select(item, userID: session.user?.id ?? 0); defer { try? FileManager.default.removeItem(at: file) }; avatar = try await MediaUploader.shared.uploadForm(file: file, action: "upload.avatar"); try? FileManager.default.removeItem(at: file) } catch { self.error = error.localizedDescription } } }
    }
    private func save() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("profile.update", ["nickname": .string(nickname), "remark": .string(remark), "avatar_url": .string(avatar)]); try await session.refreshProfile(); dismiss() } catch { self.error = error.localizedDescription } } }
}

struct SecurityView: View {
    @EnvironmentObject private var session: SessionStore
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "账号与安全", subtitle: "管理登录凭据与钱包绑定信息", icon: "lock.shield")
            SPXPanel("账号信息") {
                InfoRow(title: "邮箱", value: session.user?["email"].string ?? "")
                InfoRow(title: "SPX ID", value: session.user?["spx_id"].string ?? "")
            }
            SPXPanel("安全管理") {
                NavigationLink { ChangePasswordView() } label: { SPXSettingsRow(title: "修改登录密码", icon: "key") }
                NavigationLink { ChangePasswordView(emailVerification: true) } label: { SPXSettingsRow(title: "邮箱验证修改密码", icon: "envelope") }
                NavigationLink { WalletBindingView() } label: { SPXSettingsRow(title: "钱包地址绑定", icon: "wallet") }
            }.buttonStyle(.plain)
        }.navigationTitle("账号与安全")
    }
}

struct ChangePasswordView: View {
    var emailVerification = false
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var code = ""
    @State private var password = ""
    @State private var confirmation = ""
    @State private var busy = false
    @State private var cooldown = 0
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "修改登录密码", subtitle: emailVerification ? "使用绑定邮箱验证身份" : "验证当前密码后设置新密码", icon: "key")
            SPXPanel("身份验证") {
                if emailVerification {
                    InfoRow(title: "验证邮箱", value: session.user?["email"].string ?? "")
                    SPXField("邮箱验证码") { HStack { TextField("六位验证码", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode); Button(cooldown > 0 ? "\(cooldown)s" : "发送验证码") { sendCode() }.disabled(busy || cooldown > 0).fixedSize() } }
                } else { SPXField("当前密码") { SecureField("请输入当前密码", text: $current).textContentType(.password) } }
            }.disabled(busy)
            SPXPanel("设置新密码", footer: "新密码至少六位，建议使用字母与数字组合。") {
                SPXField("新密码") { SecureField("至少六位", text: $password).textContentType(.newPassword) }
                SPXField("确认新密码") { SecureField("请再次输入新密码", text: $confirmation).textContentType(.newPassword) }
            }.disabled(busy)
            if let error { ErrorBanner(message: error) }
        }.safeAreaInset(edge: .bottom, spacing: 0) {
            SPXBottomAction { Button(busy ? "保存中…" : "保存新密码") { save() }.disabled(busy || password.count < 6 || password != confirmation || (emailVerification ? code.count != 6 : current.isEmpty)) }
        }.navigationTitle("修改密码").task(id: cooldown) { guard cooldown > 0 else { return }; do { try await Task.sleep(for: .seconds(1)); cooldown -= 1 } catch { } }
    }
    private func sendCode() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("password.email.code"); cooldown = 60 } catch { self.error = error.localizedDescription } } }
    private func save() { busy = true; Task { defer { busy = false; current = ""; password = ""; confirmation = ""; code = "" }; do { let response = try await APIClient.shared.post(emailVerification ? "password.update.email" : "password.update", [emailVerification ? "code" : "current_password": .string(emailVerification ? code : current), "new_password": .string(password)]); if emailVerification { try await session.replaceToken(response["token"].string) }; dismiss() } catch { self.error = error.localizedDescription } } }
}

struct SettingsView: View {
    @EnvironmentObject private var session: SessionStore
    @AppStorage("spx.appearance") private var appearance = "system"
    @AppStorage("spx.language") private var language = "zh-CN"
    @State private var clear = false
    @State private var logout = false
    @State private var notice: String?
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPanel("账号与通知") {
                NavigationLink { SecurityView() } label: { SPXSettingsRow(title: "账号与安全", icon: "lock.shield") }
                NavigationLink { BenefitsView() } label: { SPXSettingsRow(title: "我的权益", icon: "seal") }
                NavigationLink { MessageNotificationSettingsView() } label: { SPXSettingsRow(title: "消息通知与角标", icon: "bell.badge") }
            }
            SPXPanel("通用") {
                SPXField("外观") { Picker("外观", selection: $appearance) { Text("跟随系统").tag("system"); Text("浅色").tag("light"); Text("深色").tag("dark") }.pickerStyle(.segmented) }
                HStack { Text("服务消息语言"); Spacer(); Picker("服务器提示语言", selection: $language) { Text("简体中文").tag("zh-CN"); Text("English").tag("en") }.labelsHidden() }
                Text("当前原生界面以简体中文呈现。").font(.caption).foregroundStyle(SPXColor.muted)
                Button { clear = true } label: { SPXSettingsRow(title: "清理媒体缓存", icon: "internaldrive") }
                Link(destination: URL(string: UIApplication.openSettingsURLString)!) { SPXSettingsRow(title: "系统权限设置", icon: "settings") }
            }
            SPXPanel("关于与协议") {
                InfoRow(title: "应用", value: "SPX Chat for iOS")
                InfoRow(title: "当前版本", value: (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") + " (" + (Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") + ")")
                NavigationLink { UpdateHistoryView() } label: { SPXSettingsRow(title: "版本记录", icon: "clock.arrow.circlepath") }
                NavigationLink { PolicyView(kind: "agreement") } label: { SPXSettingsRow(title: "服务协议", icon: "doc.text") }
                NavigationLink { PolicyView(kind: "privacy") } label: { SPXSettingsRow(title: "隐私政策", icon: "hand.raised") }
                InfoRow(title: "连接安全", value: "系统公信 HTTPS")
            }
            Button { logout = true } label: { Text("退出登录").font(.system(size: 15, weight: .semibold)).foregroundStyle(.red).frame(maxWidth: .infinity).padding(17).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16)) }.buttonStyle(.plain)
        }.buttonStyle(.plain).navigationTitle("设置").spxPage().errorAlert($error).successToast($notice)
            .confirmationDialog("退出当前账号？", isPresented: $logout, titleVisibility: .visible) { Button("退出登录", role: .destructive) { Task { await session.logout() } } }
            .confirmationDialog("清除已下载的聊天媒体？后续可重新下载", isPresented: $clear, titleVisibility: .visible) { Button("清理缓存", role: .destructive) { Task { do { try await MediaCache.shared.clear(userID: session.user?.id ?? 0); notice = "缓存已清理" } catch { self.error = error.localizedDescription } } } }
    }
}

struct PolicyView: View {
    let kind: String
    @State private var text = ""
    @State private var error: String?
    var body: some View { SPXDetailPage { SPXCard { VStack(alignment: .leading, spacing: 16) { if text.isEmpty, error == nil { ProgressView() } else { Text(text).textSelection(.enabled) }; if let error { ErrorBanner(message: error) } }.font(.system(size: 15)).lineSpacing(7).frame(maxWidth: .infinity, alignment: .leading) } }.navigationTitle(kind == "privacy" ? "隐私政策" : "服务协议").task { do { let url = Bundle.main.url(forResource: kind, withExtension: "txt"); if let url { text = try String(contentsOf: url, encoding: .utf8) } else { error = "协议暂时无法加载，请稍后重试。" } } catch { self.error = error.localizedDescription } } }
}

struct BenefitsView: View {
    private let benefits = [("生态服务", "浏览平台商城与生态入口"), ("质押权益", "查看真实质押计划和账户记录"), ("邀请奖励", "查看邀请记录与已发奖励")]
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "我的权益", subtitle: "权益以账户记录与平台规则为准", icon: "seal")
            ForEach(benefits, id: \.0) { item in SPXCard { HStack(alignment: .top, spacing: 14) { Image(systemName: "checkmark.seal").font(.title2).foregroundStyle(SPXColor.primary); VStack(alignment: .leading, spacing: 8) { Text(item.0).font(.headline); Text(item.1).font(.subheadline).foregroundStyle(SPXColor.muted) } } } }
            NavigationLink { StakingView() } label: { SPXMenuRow(title: "我的质押", icon: "lock.shield") }
            NavigationLink { ReferralsView() } label: { SPXMenuRow(title: "邀请记录", icon: "person.2") }
            NavigationLink { OrdersView() } label: { SPXMenuRow(title: "我的订单", icon: "order") }
        }.buttonStyle(.plain).navigationTitle("我的权益")
    }
}

struct UpdateHistoryView: View {
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.2" }
    private var build: String { Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "" }
    var body: some View {
        SPXDetailPage {
            SPXPanel("当前安装版本") {
                InfoRow(title: "iOS", value: "\(version) (\(build))")
                Text("新版本请通过官方发布渠道重新下载安装。").font(.footnote).foregroundStyle(.secondary)
            }
            SPXPanel("1.0.6 · 2026-10-24") {
                Label("统一导航、列表、经营概览、商品发布与资产页面", systemImage: "square.grid.2x2")
                Label("加入消息系统通知、未读角标和通知跳转", systemImage: "bell.badge")
                Label("完善群成员网格、红包确认和通知设置", systemImage: "person.3")
            }
            SPXPanel("1.0.2 · 2026-10-24") {
                Label("修复登录连接的系统 HTTPS 信任校验", systemImage: "lock.shield")
                Label("完善资金请求恢复、分页和历史记录展示", systemImage: "arrow.clockwise")
                Label("优化商品价格、经营记录与报价到期提示", systemImage: "bag")
            }
            SPXPanel("1.0.1 · 2026-10-24") {
                Label("使用系统公信 HTTPS 校验登录连接", systemImage: "lock.shield")
                Label("提现与质押支持原申请结果恢复和安全重试", systemImage: "arrow.clockwise")
                Label("交易、红包、收藏和商家草稿支持加载更多", systemImage: "list.bullet")
                Label("优化历史定价、经营记录与报价到期提示", systemImage: "bag")
            }
        }.navigationTitle("iOS 版本记录")
    }
}

struct FavoritesView: View {
    @StateObject private var state = PageState()
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            ForEach(state.rows) { product in
                NavigationLink { ProductDetailView(product: product) } label: {
                    SPXCard { HStack(spacing: 14) { CachedImage(source: product.image).frame(width: 76, height: 76).clipShape(RoundedRectangle(cornerRadius: 12))
                        VStack(alignment: .leading, spacing: 6) { Text(product.name); PriceLabel(cny: product["cny_price"].string, estimate: nil, legacy: product["spx_price"].string) }
                    } }
                }.buttonStyle(.plain)
            }
            if state.busy { ProgressView().frame(maxWidth: .infinity) }
            if state.more { Button("加载更多") { Task { await load(reset: false) } }.disabled(state.busy) }
            if state.page > 0, state.rows.isEmpty { EmptyState(title: "还没有收藏商品", icon: "heart") }
            if let error { ErrorBanner(message: error); Button("重试") { Task { await load(reset: state.page == 0) } } }
        }.navigationTitle("收藏商品").task { await load(reset: true) }.refreshable { await load(reset: true) }
    }
    private func load(reset: Bool) async {
        do { try await state.load(reset: reset) { next in try await APIClient.shared.get("favorites", ["page": String(next), "page_size": "30"]).list }; error = nil }
        catch { self.error = error.localizedDescription }
    }
}

struct RecordsView: View {
    let title: String
    let action: String
    var query: [String: String] = [:]
    @StateObject private var state = PageState()
    @State private var error: String?
    private var finance: Bool { action == "merchant.finance" }
    var body: some View {
        SPXDetailPage {
            ForEach(state.rows) { row in
                SPXCard { DisclosureGroup {
                    InfoRow(title: "记录编号", value: row["id"].string)
                    InfoRow(title: "时间", value: row["created_at"].string)
                    if finance {
                        InfoRow(title: "处理状态", value: DisplayLabels.transactionStatus(row["status"].string))
                        if row["mall_order_id"].int > 0 { InfoRow(title: "关联订单", value: "#" + row["mall_order_id"].string) }
                        if row["type"].string == "merchant_settlement" {
                            InfoRow(title: "订单总额", value: DisplayLabels.amount(row["gross_amount"].string) + " SPX001")
                            InfoRow(title: "平台手续费", value: DisplayLabels.amount(row["fee_amount"].string) + " SPX001")
                            InfoRow(title: "实际到账", value: DisplayLabels.amount(row["net_amount"].string) + " SPX001")
                        }
                        if let stakeable = row["stakeable_amount"].decimal, stakeable > 0 { InfoRow(title: "其中可质押数量", value: DisplayLabels.amount(Money.format(stakeable, scale: 18)) + " SPX001") }
                    } else {
                        if row["target_id"].int > 0 { InfoRow(title: "关联业务编号", value: "#" + row["target_id"].string) }
                        let metadata = metadata(row)
                        if !metadata["amount"].string.isEmpty { InfoRow(title: "数量", value: DisplayLabels.amount(metadata["amount"].string) + " SPX001") }
                        if !metadata["version"].string.isEmpty { InfoRow(title: "商品版本", value: metadata["version"].string) }
                        if !metadata["ids"].array.isEmpty { InfoRow(title: "涉及商品", value: metadata["ids"].array.map(\.string).joined(separator: "、")) }
                    }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: finance ? "arrow.left.arrow.right.circle" : "clock.arrow.circlepath").foregroundStyle(SPXColor.primary)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(finance ? DisplayLabels.transaction(row["type"].string) : DisplayLabels.merchantAction(row["action"].string)).font(.subheadline.weight(.medium))
                            Text(row["created_at"].string).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if finance { Text(DisplayLabels.amount(row["amount"].string) + " " + row["symbol"].string).font(.caption.weight(.semibold)).multilineTextAlignment(.trailing) }
                    }
                }
                }
            }
            if state.busy { ProgressView().frame(maxWidth: .infinity) }
            if state.more { Button("加载更多") { Task { await load(reset: false) } }.disabled(state.busy) }
            if state.page > 0, state.rows.isEmpty { EmptyState(title: "暂无记录") }
            if let error { ErrorBanner(message: error); Button("重试") { Task { await load(reset: state.page == 0) } } }
        }.navigationTitle(title).task { await load(reset: true) }.refreshable { await load(reset: true) }
    }
    private func metadata(_ row: Record) -> JSON {
        guard let data = row["metadata"].string.data(using: .utf8) else { return .null }
        return (try? JSONDecoder().decode(JSON.self, from: data)) ?? .null
    }
    private func load(reset: Bool) async {
        do {
            try await state.load(reset: reset) { next in
                var params = query; params["page"] = String(next); params["page_size"] = "30"
                return try await APIClient.shared.get(action, params).list
            }; error = nil
        } catch { self.error = error.localizedDescription }
    }
}
