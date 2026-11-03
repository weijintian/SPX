import SwiftUI

enum AuthenticationInput {
    static func trimmed(_ value: String) -> String { value.trimmingCharacters(in: .whitespacesAndNewlines) }
    static func validEmail(_ value: String) -> Bool {
        trimmed(value).range(of: #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#, options: .regularExpression) != nil
    }
    static func validCode(_ value: String) -> Bool {
        let bytes = Array(trimmed(value).utf8)
        return bytes.count == 6 && bytes.allSatisfy { (48...57).contains($0) }
    }
}

/// The deadline survives form switches and background time without storing credentials.
struct EmailCodeCooldown {
    private var deadlines: [String: Date] = [:]
    private func key(_ email: String, _ purpose: String) -> String { purpose + ":" + AuthenticationInput.trimmed(email).lowercased() }
    mutating func sent(to email: String, purpose: String, now: Date = Date()) {
        deadlines[key(email, purpose)] = now.addingTimeInterval(60)
    }
    func remaining(for email: String, purpose: String, now: Date = Date()) -> Int {
        guard let deadline = deadlines[key(email, purpose)] else { return 0 }
        return max(0, min(60, Int(ceil(deadline.timeIntervalSince(now)))))
    }
}

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
    @State private var successToast: String?
    @State private var codeBusy = false
    @State private var cooldown = EmailCodeCooldown()
    @State private var now = Date()
    @State private var acceptedTerms = false
    @FocusState private var focused: Field?
    private enum Field: Hashable { case username, email, code, password, invite }
    private var remaining: Int { cooldown.remaining(for: email, purpose: "register", now: now) }
    private var canSubmit: Bool {
        (!register || acceptedTerms) && !session.loading && !codeBusy && !AuthenticationInput.trimmed(username).isEmpty &&
        (register ? password.count >= 6 && AuthenticationInput.validEmail(email) && AuthenticationInput.validCode(code) : !password.isEmpty)
    }

    var body: some View {
        SPXNavigationContainer {
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
                                .textContentType(.username).textInputAutocapitalization(.never).disableAutocorrection(true)
                                .focused($focused, equals: .username).submitLabel(.next)
                                .onSubmit { focused = register ? .email : .password }
                            Divider()
                            if register {
                                TextField("邮箱", text: $email).keyboardType(.emailAddress)
                                    .textContentType(.emailAddress).textInputAutocapitalization(.never).disableAutocorrection(true)
                                    .focused($focused, equals: .email).submitLabel(.next).onSubmit { focused = .code }
                                    .disabled(codeBusy)
                                Divider()
                                HStack {
                                    TextField("六位邮箱验证码", text: $code).keyboardType(.numberPad)
                                        .textContentType(.oneTimeCode).focused($focused, equals: .code)
                                        .onChange(of: code) { value in code = String(value.filter(\.isNumber).prefix(6)) }
                                    Button(codeBusy ? "发送中…" : remaining > 0 ? "\(remaining)s后重发" : "获取验证码") { requestCode() }
                                        .disabled(!acceptedTerms || codeBusy || remaining > 0 || !AuthenticationInput.validEmail(email))
                                }
                                Divider()
                            }
                            SecureField("密码（至少六位）", text: $password)
                                .textContentType(register ? .newPassword : .password)
                                .focused($focused, equals: .password).submitLabel(register ? .next : .go)
                                .onSubmit { if register { focused = .invite } else { submit() } }
                            if register {
                                Divider()
                                TextField("邀请码（选填）", text: $invite).textInputAutocapitalization(.never).disableAutocorrection(true)
                                    .focused($focused, equals: .invite).submitLabel(.go).onSubmit { submit() }
                            }
                        }.disabled(session.loading)
                    }
                    if let error = session.error { ErrorBanner(message: error) }
                    if let notice { Text(notice).font(.footnote).foregroundStyle(.green) }
                    if register {
                        HStack(alignment: .top, spacing: 10) {
                            Button { acceptedTerms.toggle() } label: {
                                Image(systemName: acceptedTerms ? "checkmark.square.fill" : "square")
                                    .font(.system(size: 21)).foregroundStyle(acceptedTerms ? SPXColor.primary : .secondary)
                                    .frame(width: 44, height: 44)
                            }.buttonStyle(.plain).accessibilityLabel("同意服务协议与隐私政策")
                                .accessibilityValue(acceptedTerms ? "已勾选" : "未勾选").disabled(session.loading || codeBusy)
                            VStack(alignment: .leading, spacing: 6) {
                                Text("我已阅读并同意").foregroundStyle(.secondary)
                                HStack {
                                    NavigationLink("服务协议") { PolicyView(kind: "agreement") }
                                    NavigationLink("隐私政策") { PolicyView(kind: "privacy") }
                                }.disabled(session.loading || codeBusy)
                            }.font(.caption).padding(.top, 5)
                        }
                    }
                    SPXButton(title: session.loading ? (register ? "注册中…" : "登录中…") : (register ? "创建账号" : "登录"), icon: "arrow.right") {
                        submit()
                    }.disabled(!canSubmit)
                    HStack {
                        Button(register ? "返回登录" : "邮箱注册") {
                            focused = nil; register.toggle(); session.error = nil; notice = nil
                        }
                        Spacer()
                        if !register { Button("忘记密码") { focused = nil; session.error = nil; notice = nil; reset = true } }
                    }.font(.subheadline).disabled(session.loading || codeBusy)
                }.padding(24).frame(maxWidth: 520)
            }.spxPage().sheet(isPresented: $reset) { PasswordResetView() }
                .onChange(of: email) { _ in code = ""; notice = nil; successToast = nil; session.error = nil; now = Date() }
                .task {
                    while !Task.isCancelled {
                        now = Date()
                        do { try await SPXSleep.seconds(1) } catch { return }
                    }
                }
        }.successToast($successToast)
    }

    private func submit() {
        guard canSubmit else { return }
        focused = nil; notice = nil
        let registration = register, account = AuthenticationInput.trimmed(username), secret = password
        let address = AuthenticationInput.trimmed(email), verification = AuthenticationInput.trimmed(code), invitation = AuthenticationInput.trimmed(invite)
        Task { await session.authenticate(register: registration, username: account, password: secret, email: address, code: verification, invite: invitation) }
    }

    private func requestCode() {
        guard acceptedTerms, !session.loading, !codeBusy, cooldown.remaining(for: email, purpose: "register") == 0,
              AuthenticationInput.validEmail(email) else { return }
        let address = AuthenticationInput.trimmed(email)
        codeBusy = true; session.error = nil; notice = nil
        Task {
            defer { codeBusy = false }
            do {
                try await APIClient.shared.post("auth.email.code", ["email": .string(address), "purpose": "register"])
                cooldown.sent(to: address, purpose: "register"); now = Date()
                notice = "验证码已发送，请查收邮箱；未收到时可检查垃圾邮件"
                successToast = "验证码已发送，请检查邮箱"
                focused = .code
            } catch is CancellationError { }
            catch { session.error = AuthenticationErrorMessage.describe(error) }
        }
    }
}

struct PasswordResetView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var email = ""
    @State private var code = ""
    @State private var password = ""
    @State private var busy = false
    @State private var codeBusy = false
    @State private var error: String?
    @State private var notice: String?
    @State private var successToast: String?
    @State private var cooldown = EmailCodeCooldown()
    @State private var now = Date()
    @FocusState private var focused: Field?
    private enum Field: Hashable { case email, code, password }
    private var remaining: Int { cooldown.remaining(for: email, purpose: "password_reset", now: now) }

    var body: some View {
        SPXNavigationContainer {
            SPXDetailPage {
                SPXPageHeading(title: "找回密码", subtitle: "通过注册邮箱验证身份，设置新的登录密码", icon: "lock.shield")
                SPXCard { VStack(alignment: .leading, spacing: 18) {
                    SPXField("注册邮箱") {
                        TextField("请输入邮箱", text: $email).keyboardType(.emailAddress).textContentType(.emailAddress)
                            .textInputAutocapitalization(.never).disableAutocorrection(true).disabled(codeBusy)
                            .focused($focused, equals: .email).submitLabel(.next).onSubmit { focused = .code }
                    }
                    SPXField("邮箱验证码") { HStack(spacing: 12) {
                        TextField("六位验证码", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode).focused($focused, equals: .code)
                            .onChange(of: code) { value in code = String(value.filter(\.isNumber).prefix(6)) }
                        Button(codeBusy ? "发送中…" : remaining > 0 ? "\(remaining)s后重发" : "获取验证码") { send() }
                            .font(.system(size: 13, weight: .semibold)).frame(minHeight: 44)
                            .disabled(busy || codeBusy || remaining > 0 || !AuthenticationInput.validEmail(email))
                    } }
                    SPXField("新密码") {
                        SecureField("至少六位", text: $password).textContentType(.newPassword)
                            .focused($focused, equals: .password).submitLabel(.go).onSubmit { save() }
                    }
                }.disabled(busy) }
                if let error { ErrorBanner(message: error) }
                if let notice { Text(notice).font(.footnote).foregroundStyle(.green) }
            }.navigationTitle("重置密码").navigationBarTitleDisplayMode(.inline)
                .safeAreaInset(edge: .bottom) {
                    SPXBottomAction { Button(busy ? "重置中…" : "重置密码") { save() }
                        .disabled(busy || codeBusy || !AuthenticationInput.validEmail(email) || !AuthenticationInput.validCode(code) || password.count < 6) }
                }
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() }.disabled(busy || codeBusy) } }
                .interactiveDismissDisabled(busy || codeBusy)
                .onChange(of: email) { _ in code = ""; error = nil; notice = nil; successToast = nil; now = Date() }
                .task {
                    while !Task.isCancelled {
                        now = Date()
                        do { try await SPXSleep.seconds(1) } catch { return }
                    }
                }
        }.successToast($successToast)
    }

    private func send() {
        guard !busy, !codeBusy, AuthenticationInput.validEmail(email), cooldown.remaining(for: email, purpose: "password_reset") == 0 else { return }
        let address = AuthenticationInput.trimmed(email)
        codeBusy = true; error = nil; notice = nil
        Task {
            defer { codeBusy = false }
            do {
                try await APIClient.shared.post("auth.email.code", ["email": .string(address), "purpose": "password_reset"])
                cooldown.sent(to: address, purpose: "password_reset"); now = Date()
                notice = "如果该邮箱已注册，验证码将发送到该邮箱，请检查收件箱和垃圾邮件"
                successToast = notice
                focused = .code
            } catch is CancellationError { }
            catch { self.error = AuthenticationErrorMessage.describe(error) }
        }
    }

    private func save() {
        guard !busy, !codeBusy, AuthenticationInput.validEmail(email), AuthenticationInput.validCode(code), password.count >= 6 else { return }
        let address = AuthenticationInput.trimmed(email), verification = AuthenticationInput.trimmed(code), secret = password
        busy = true; error = nil; notice = nil; focused = nil
        Task {
            defer { busy = false }
            do {
                try await APIClient.shared.post("auth.password.reset", ["email": .string(address), "code": .string(verification), "new_password": .string(secret)])
                dismiss()
            } catch is CancellationError { }
            catch { self.error = AuthenticationErrorMessage.describe(error) }
        }
    }
}
