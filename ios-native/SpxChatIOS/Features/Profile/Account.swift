import SwiftUI
import PhotosUI

struct ProfileView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var logout = false
    var body: some View {
        List {
            Section {
                NavigationLink { QRCodeView(value: "SPXUSER:\(session.user?["spx_id"].string ?? "")", title: session.user?.name ?? "我的二维码") } label: { Label("我的二维码", systemImage: "qrcode") }
                NavigationLink { ProfileEditView() } label: {
                    HStack(spacing: 16) { Avatar(url: session.user?.avatar ?? "", name: session.user?.name ?? "我", size: 64); VStack(alignment: .leading, spacing: 7) { Text(session.user?.name ?? "我的账号").font(.title2.bold()); Text("SPX ID：\(session.user?["spx_id"].string ?? "")").font(.caption).foregroundStyle(.secondary) } }.padding(.vertical, 12)
                }
            }
            Section {
                NavigationLink { WalletView() } label: { Label("我的资产", systemImage: "creditcard") }
                NavigationLink { MerchantOverviewView() } label: { Label("商家中心", systemImage: "storefront") }
                NavigationLink { OrdersView() } label: { Label("我的订单", systemImage: "shippingbox") }
                NavigationLink { FavoritesView() } label: { Label("收藏商品", systemImage: "heart") }
                NavigationLink { AddressView() } label: { Label("收货地址", systemImage: "mappin.and.ellipse") }
                NavigationLink { ReferralsView() } label: { Label("邀请朋友", systemImage: "person.badge.plus") }
                NavigationLink { BenefitsView() } label: { Label("我的权益", systemImage: "seal") }
            }
            Section {
                NavigationLink { SecurityView() } label: { Label("账号与安全", systemImage: "lock.shield") }
                NavigationLink { SupportView() } label: { Label("帮助与反馈", systemImage: "questionmark.circle") }
                NavigationLink { UpdateHistoryView() } label: { Label("更新历史", systemImage: "clock.arrow.circlepath") }
                NavigationLink { SettingsView() } label: { Label("设置", systemImage: "gearshape") }
            }
            Section { Button("退出登录", role: .destructive) { logout = true } }
        }.navigationTitle("我的").spxPage().refreshable { try? await session.refreshProfile() }
            .confirmationDialog("确认退出当前账号？", isPresented: $logout, titleVisibility: .visible) { Button("退出登录", role: .destructive) { Task { await session.logout() } } }
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
        Form {
            Section {
                HStack { Avatar(url: avatar, name: nickname, size: 72); Spacer(); PhotosPicker(selection: $selected, matching: .images) { Label("更换头像", systemImage: "camera") } }
                TextField("昵称", text: $nickname)
                InfoRow(title: "SPX ID", value: session.user?["spx_id"].string ?? "")
                TextField("个人简介", text: $remark, axis: .vertical).lineLimit(3...8)
            }
            if let error { ErrorBanner(message: error) }
            Section { Button(busy ? "保存中…" : "保存资料") { save() }.disabled(busy || nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
        }.navigationTitle("个人资料").disabled(busy)
            .onAppear { nickname = session.user?["nickname"].string ?? ""; remark = session.user?["remark"].string ?? ""; avatar = session.user?.avatar ?? "" }
            .onChange(of: selected) { _, item in guard let item else { return }; busy = true; Task { defer { busy = false }; do { let file = try await MediaFiles.select(item, userID: session.user?.id ?? 0); defer { try? FileManager.default.removeItem(at: file) }; avatar = try await MediaUploader.shared.uploadForm(file: file, action: "upload.avatar"); try? FileManager.default.removeItem(at: file) } catch { self.error = error.localizedDescription } } }
    }
    private func save() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("profile.update", ["nickname": .string(nickname), "remark": .string(remark), "avatar_url": .string(avatar)]); try await session.refreshProfile(); dismiss() } catch { self.error = error.localizedDescription } } }
}

struct SecurityView: View {
    @EnvironmentObject private var session: SessionStore
    var body: some View {
        Form {
            Section("账号信息") { InfoRow(title: "邮箱", value: session.user?["email"].string ?? ""); InfoRow(title: "SPX ID", value: session.user?["spx_id"].string ?? "") }
            Section { NavigationLink("修改登录密码") { ChangePasswordView() }; NavigationLink("邮箱验证修改密码") { ChangePasswordView(emailVerification: true) }; NavigationLink("钱包地址绑定") { WalletBindingView() } }
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
        Form {
            Section {
                if emailVerification { InfoRow(title: "验证邮箱", value: session.user?["email"].string ?? ""); HStack { TextField("六位验证码", text: $code).keyboardType(.numberPad); Button(cooldown > 0 ? "\(cooldown)s" : "发送验证码") { sendCode() }.disabled(busy || cooldown > 0) } }
                else { SecureField("当前密码", text: $current).textContentType(.password) }
                SecureField("新密码（至少六位）", text: $password).textContentType(.newPassword)
                SecureField("确认新密码", text: $confirmation).textContentType(.newPassword)
                Button("保存新密码") { save() }.disabled(busy || password.count < 6 || password != confirmation || (emailVerification ? code.count != 6 : current.isEmpty))
            }
            if let error { ErrorBanner(message: error) }
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
    @State private var error: String?
    var body: some View {
        Form {
            Section("显示") { Picker("外观", selection: $appearance) { Text("跟随系统").tag("system"); Text("浅色").tag("light"); Text("深色").tag("dark") } }
            Section("服务消息语言") { Picker("服务器提示语言", selection: $language) { Text("简体中文").tag("zh-CN"); Text("English").tag("en") }; Text("当前原生界面以简体中文呈现。").font(.caption).foregroundStyle(.secondary) }
            Section { Button("清理媒体缓存") { clear = true }; Link("打开系统权限设置", destination: URL(string: UIApplication.openSettingsURLString)!); NavigationLink("服务协议") { PolicyView(kind: "agreement") }; NavigationLink("隐私政策") { PolicyView(kind: "privacy") } }
            Section("关于") { InfoRow(title: "应用", value: "SPX Chat for iOS"); InfoRow(title: "版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""); InfoRow(title: "构建号", value: Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""); InfoRow(title: "连接安全", value: "系统公信 HTTPS") }
        }.navigationTitle("设置").errorAlert($error)
            .confirmationDialog("清除已下载的聊天媒体？后续可重新下载", isPresented: $clear, titleVisibility: .visible) { Button("清理缓存", role: .destructive) { Task { do { try await MediaCache.shared.clear(userID: session.user?.id ?? 0); error = "缓存已清理" } catch { self.error = error.localizedDescription } } } }
    }
}

struct PolicyView: View {
    let kind: String
    @State private var text = ""
    @State private var error: String?
    var body: some View { ScrollView { VStack(alignment: .leading, spacing: 16) { if text.isEmpty, error == nil { ProgressView() } else { Text(text).textSelection(.enabled) }; if let error { ErrorBanner(message: error) } }.padding(20).frame(maxWidth: .infinity, alignment: .leading) }.navigationTitle(kind == "privacy" ? "隐私政策" : "服务协议").task { do { let url = Bundle.main.url(forResource: kind, withExtension: "txt"); if let url { text = try String(contentsOf: url, encoding: .utf8) } else { error = "协议暂时无法加载，请稍后重试。" } } catch { self.error = error.localizedDescription } } }
}

struct BenefitsView: View {
    private let benefits = [("生态服务", "浏览平台商城与生态入口"), ("质押权益", "查看真实质押计划和账户记录"), ("邀请奖励", "查看邀请记录与已发奖励")]
    var body: some View {
        List { Section { ForEach(benefits, id: \.0) { item in Label { VStack(alignment: .leading, spacing: 5) { Text(item.0).font(.headline); Text(item.1).font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: "seal").foregroundStyle(SPXColor.primary) } } } footer: { Text("权益以账户记录与平台规则为准。") }; Section { NavigationLink("我的质押") { StakingView() }; NavigationLink("邀请记录") { ReferralsView() }; NavigationLink("我的订单") { OrdersView() } } }.spxPage().navigationTitle("我的权益")
    }
}

struct UpdateHistoryView: View {
    private var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.2" }
    private var build: String { Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "" }
    var body: some View {
        List {
            Section("当前安装版本") {
                InfoRow(title: "iOS", value: "\(version) (\(build))")
                Text("新版本请通过官方发布渠道重新下载安装。").font(.footnote).foregroundStyle(.secondary)
            }
            Section("1.0.2 · 2026-10-24") {
                Label("修复登录连接的系统 HTTPS 信任校验", systemImage: "lock.shield")
                Label("完善资金请求恢复、分页和历史记录展示", systemImage: "arrow.clockwise")
                Label("优化商品价格、经营记录与报价到期提示", systemImage: "bag")
            }
            Section("1.0.1 · 2026-10-24") {
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
        List {
            ForEach(state.rows) { product in
                NavigationLink { ProductDetailView(product: product) } label: {
                    HStack { CachedImage(source: product.image).frame(width: 64, height: 64)
                        VStack(alignment: .leading, spacing: 6) { Text(product.name); PriceLabel(cny: product["cny_price"].string, estimate: nil, legacy: product["spx_price"].string) }
                    }
                }
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
        List {
            ForEach(state.rows) { row in
                DisclosureGroup {
                    InfoRow(title: "记录编号", value: row["id"].string)
                    InfoRow(title: "时间", value: row["created_at"].string)
                    if finance {
                        InfoRow(title: "处理状态", value: DisplayLabels.transactionStatus(row["status"].string))
                        if row["mall_order_id"].int > 0 { InfoRow(title: "关联订单", value: "#" + row["mall_order_id"].string) }
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
