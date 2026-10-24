import SwiftUI

struct AuthView: View {
    @EnvironmentObject private var session: SessionStore
    @State private var register = false
    @State private var username = ""
    @State private var password = ""
    @State private var email = ""
    @State private var code = ""
    @State private var invite = ""
    @State private var reset = false
    @State private var notice: String?
    @State private var codeBusy = false
    @State private var cooldown = 0
    @State private var acceptedTerms = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                        .font(.system(size: 52)).foregroundStyle(SPXColor.primary).padding(.top, 44)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(register ? "创建你的账号" : "欢迎回来").font(.largeTitle.bold())
                        Text("让沟通与生活轻松连接").foregroundStyle(.secondary)
                    }
                    SPXCard {
                        VStack(spacing: 16) {
                            TextField(register ? "用户名" : "用户名或邮箱", text: $username)
                                .textContentType(.username).textInputAutocapitalization(.never)
                            Divider()
                            if register {
                                TextField("邮箱", text: $email).keyboardType(.emailAddress)
                                    .textContentType(.emailAddress).textInputAutocapitalization(.never)
                                Divider()
                                HStack {
                                    TextField("六位邮箱验证码", text: $code).keyboardType(.numberPad)
                                        .textContentType(.oneTimeCode)
                                    Button(cooldown > 0 ? "\(cooldown)s" : "获取验证码") { requestCode() }
                                        .disabled(!acceptedTerms || codeBusy || cooldown > 0 || !email.contains("@"))
                                }
                                Divider()
                            }
                            SecureField("密码（至少六位）", text: $password)
                                .textContentType(register ? .newPassword : .password)
                            if register {
                                Divider()
                                TextField("邀请码（选填）", text: $invite).textInputAutocapitalization(.never)
                            }
                        }.autocorrectionDisabled()
                    }
                    if let error = session.error { ErrorBanner(message: error) }
                    if let notice { Text(notice).font(.footnote).foregroundStyle(.secondary) }
                    HStack(alignment: .top, spacing: 10) {
                        Button { acceptedTerms.toggle() } label: {
                            Image(systemName: acceptedTerms ? "checkmark.square.fill" : "square")
                                .font(.system(size: 21)).foregroundStyle(acceptedTerms ? SPXColor.primary : .secondary)
                                .frame(width: 44, height: 44)
                        }.buttonStyle(.plain).accessibilityLabel("同意服务协议与隐私政策")
                            .accessibilityValue(acceptedTerms ? "已勾选" : "未勾选")
                        VStack(alignment: .leading, spacing: 6) {
                            Text("我已阅读并同意").foregroundStyle(.secondary)
                            HStack {
                                NavigationLink("服务协议") { PolicyView(kind: "agreement") }
                                NavigationLink("隐私政策") { PolicyView(kind: "privacy") }
                            }
                        }.font(.caption).padding(.top, 5)
                    }
                    SPXButton(title: session.loading ? "请稍候…" : (register ? "创建账号" : "登录"), icon: "arrow.right") {
                        Task { await session.authenticate(register: register, username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password, email: email, code: code, invite: invite) }
                    }.disabled(!acceptedTerms || session.loading || username.isEmpty || password.count < 6 || (register && (code.count != 6 || !email.contains("@"))))
                    HStack {
                        Button(register ? "返回登录" : "注册账号") { register.toggle(); session.error = nil }
                        Spacer()
                        if !register { Button("忘记密码") { reset = true } }
                    }.font(.subheadline)
                }.padding(24).frame(maxWidth: 520)
            }.spxPage().sheet(isPresented: $reset) { PasswordResetView() }
                .task(id: cooldown) {
                    guard cooldown > 0 else { return }
                    do { try await Task.sleep(for: .seconds(1)); cooldown -= 1 } catch { }
                }
        }
    }

    private func requestCode() {
        guard acceptedTerms, !codeBusy, cooldown == 0 else { return }
        codeBusy = true
        Task {
            defer { codeBusy = false }
            do {
                try await APIClient.shared.post("auth.email.code", ["email": .string(email), "purpose": "register"])
                cooldown = 60; notice = "验证码已发送，请查收邮箱"
            } catch { session.error = AuthenticationErrorMessage.describe(error) }
        }
    }
}

struct PasswordResetView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var code = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var cooldown = 0
    var body: some View {
        NavigationStack {
            SPXDetailPage {
                SPXPageHeading(title: "找回密码", subtitle: "通过注册邮箱验证身份，设置新的登录密码", icon: "lock.shield")
                SPXCard { VStack(alignment: .leading, spacing: 18) {
                    SPXField("注册邮箱") { TextField("请输入邮箱", text: $email).keyboardType(.emailAddress).textContentType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled() }
                    SPXField("邮箱验证码") { HStack(spacing: 12) {
                        TextField("六位验证码", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode)
                        Button(cooldown > 0 ? "\(cooldown)s" : "获取验证码") { send() }.font(.system(size: 13, weight: .semibold)).frame(minHeight: 44).disabled(busy || cooldown > 0 || !email.contains("@"))
                    } }
                    SPXField("新密码") { SecureField("至少六位", text: $password).textContentType(.newPassword) }
                }.disabled(busy) }
                if let error { ErrorBanner(message: error) }
            }.navigationTitle("重置密码").navigationBarTitleDisplayMode(.inline)
                .safeAreaInset(edge: .bottom) { SPXBottomAction { Button(busy ? "提交中…" : "重置密码") { save() }.disabled(busy || !email.contains("@") || code.count != 6 || password.count < 6) } }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
                .task(id: cooldown) { guard cooldown > 0 else { return }; do { try await Task.sleep(for: .seconds(1)); cooldown -= 1 } catch { } }
        }
    }
    private func send() {
        busy = true
        Task { defer { busy = false }; do { try await APIClient.shared.post("auth.email.code", ["email": .string(email), "purpose": "password_reset"]); cooldown = 60 } catch { self.error = AuthenticationErrorMessage.describe(error) } }
    }
    private func save() {
        busy = true
        Task { defer { busy = false }; do { try await APIClient.shared.post("auth.password.reset", ["email": .string(email), "code": .string(code), "new_password": .string(password)]); dismiss() } catch { self.error = AuthenticationErrorMessage.describe(error) } }
    }
}
