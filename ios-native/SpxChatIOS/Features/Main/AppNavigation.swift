import SwiftUI

struct RootView: View {
    @EnvironmentObject private var session: SessionStore
    @ObservedObject private var calls = CallCoordinator.shared
    @AppStorage("spx.appearance") private var appearance = "system"
    @ObservedObject private var presentation = OverlayPresentation.shared
    @State private var link: AppLink?
    @State private var pendingLink: AppLink?
    var body: some View {
        ZStack {
            Group {
                if session.restoring { ProgressView("正在恢复会话…") }
                else if session.isAuthenticated {
                    MainTabView().id(session.user?.id)
                        .safeAreaInset(edge: .top, spacing: 0) {
                            if let message = session.recoveryError {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("正在恢复连接，暂时显示本机缓存").font(.subheadline.bold())
                                    Text(message).font(.caption).foregroundStyle(.secondary)
                                    Button(session.recoveringSession ? "重试中…" : "重新连接") { Task { await session.restore() } }
                                        .disabled(session.recoveringSession)
                                }.frame(maxWidth: .infinity, alignment: .leading).padding(12).background(.regularMaterial)
                            }
                        }
                }
                else if session.token != nil { SessionRecoveryView() }
                else { AuthView() }
            }
        }
        .background(OverlayPresenter(session: session).frame(width: 0, height: 0))
        .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
        .onReceive(NotificationCenter.default.publisher(for: .spxOpenPacket)) { event in
            let packetID: String
            if let id = event.object as? String {
                packetID = id
            } else if let message = event.object as? Record,
                      let content = message["content"].string.data(using: .utf8),
                      let json = try? JSONDecoder().decode(JSON.self, from: content) {
                packetID = json["packet_id"].string
            } else { return }
            guard packetID.range(of: "^[a-fA-F0-9]{32}$", options: .regularExpression) != nil else { return }
            presentation.packetID = packetID.lowercased()
        }
        .onChange(of: session.isAuthenticated) { _, authenticated in
            if authenticated { link = pendingLink; pendingLink = nil; openPendingNotification() }
            else { presentation.packetID = nil; link = nil }
        }
        .onReceive(MessageNotifications.shared.$pendingRoute) { route in if route != nil { openPendingNotification() } }
        .onChange(of: session.user?.id) { _, _ in openPendingNotification() }
        .onOpenURL { url in if let value = AppLink.parse(url) { if session.isAuthenticated { link = value } else { pendingLink = value } } }
        .sheet(item: $link) { value in
            NavigationStack {
                Group {
                    switch value {
                    case .user(let id): LinkedUserView(lookup: id)
                    case .group(let code): GroupJoinView(initialCode: code)
                    case .product(let id): ProductDetailView(product: Record(["id": .number(Decimal(id))]))
                    case .conversation(let route): LinkedConversationView(route: route)
                    }
                }.toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { link = nil } } }
            }
        }
    }
    private func openPendingNotification() {
        guard session.isAuthenticated, let route = MessageNotifications.shared.pendingRoute else { return }
        MessageNotifications.shared.pendingRoute = nil
        guard route.userID == session.user?.id else { return }
        link = .conversation(route)
    }
}

private struct LinkedConversationView: View {
    let route: ChatNotificationRoute
    @EnvironmentObject private var session: SessionStore
    @State private var item: Record?
    @State private var error: String?
    @State private var retry = 0
    var body: some View {
        Group {
            if let item { ChatDetailView(item: item, isGroup: route.group) }
            else if let error { VStack { EmptyState(title: "暂时无法打开会话", description: error); Button("重新加载") { retry += 1 } } }
            else { ProgressView("正在打开会话…") }
        }.spxPage().task(id: retry) {
            guard session.user?.id == route.userID else { error = "此通知不属于当前账号"; return }
            do {
                error = nil
                let rows = try await APIClient.shared.get(route.group ? "groups" : "chats").list
                guard session.user?.id == route.userID else { return }
                guard var value = rows.first(where: { $0.id == route.targetID }) else { error = "该会话已删除或你已退出群聊"; return }
                value["kind"] = .string(route.group ? "group" : "chat"); item = value
            } catch { self.error = error.localizedDescription }
        }
    }
}

private struct SessionRecoveryView: View {
    @EnvironmentObject private var session: SessionStore
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "wifi.exclamationmark").font(.system(size: 42)).foregroundStyle(.secondary)
            Text("暂时无法恢复登录").font(.title3.bold())
            Text(session.recoveryError ?? "请检查网络后重试").foregroundStyle(.secondary).multilineTextAlignment(.center)
            if session.recoveringSession { ProgressView("正在重新连接…") }
            Button("重新连接") { Task { await session.restore() } }.buttonStyle(.borderedProminent).disabled(session.recoveringSession || session.loading)
            Button(session.loading ? "退出中…" : "返回登录") { Task { await session.logout() } }.disabled(session.loading)
        }.padding(28).frame(maxWidth: 440)
    }
}

private struct LinkedUserView: View {
    let lookup: String
    @State private var userID = 0
    @State private var error: String?
    var body: some View { Group { if userID > 0 { UserProfileView(userID: userID) } else if let error { EmptyState(title: "无法打开用户资料", description: error) } else { ProgressView() } }.task { do { userID = try await APIClient.shared.get("user.profile", ["id": lookup])["id"].int; guard userID > 0 else { throw APIError.invalidResponse } } catch { self.error = error.localizedDescription } } }
}

struct MainTabView: View {
    @State private var selected = 0
    @State private var path = NavigationPath()
    var body: some View {
        NavigationStack(path: $path) {
            Group {
                switch selected {
                case 1: ContactsView()
                case 2: EcosystemView()
                case 3: ProfileView()
                default: ChatListView()
                }
            }
            // The bar belongs to the root content, so pushed pages never inherit it.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                SPXBottomBar(selected: selected) { value in
                    path = NavigationPath()
                    if selected != value { selected = value }
                }
            }
        }.tint(SPXColor.primary)
    }
}

private struct SPXBottomBar: View {
    @ObservedObject private var notifications = MessageNotifications.shared
    let selected: Int
    let select: (Int) -> Void
    private let titles = ["聊天", "通讯录", "生态", "我的"]
    private let icons = ["chat", "contacts", "eco", "profile"]
    var body: some View {
        HStack(spacing: 0) {
            ForEach(titles.indices, id: \.self) { index in
                Button { select(index) } label: {
                    VStack(spacing: 5) {
                        SPXIcon(icons[index]).frame(width: 23, height: 23).overlay(alignment: .topTrailing) {
                            if index == 0, notifications.unread > 0 {
                                Text(notifications.unread > 99 ? "99+" : String(notifications.unread)).font(.system(size: 9, weight: .bold))
                                    .foregroundStyle(.white).padding(.horizontal, 4).frame(minWidth: 16, minHeight: 16)
                                    .background(.red, in: Capsule()).offset(x: 12, y: -8)
                            }
                        }
                        Text(titles[index]).font(.system(size: 11, weight: selected == index ? .semibold : .regular))
                    }.foregroundStyle(selected == index ? SPXColor.primary : SPXColor.muted)
                        .frame(maxWidth: .infinity).frame(minHeight: 68).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel(titles[index])
                    .accessibilityAddTraits(selected == index ? .isSelected : [])
            }
        }.padding(.horizontal, 8).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 24))
            .overlay(RoundedRectangle(cornerRadius: 24).stroke(SPXColor.line, lineWidth: 0.7))
            .shadow(color: .black.opacity(0.07), radius: 10, y: 3)
            .padding(.horizontal, 18).padding(.top, 8).padding(.bottom, 10)
            .background(SPXColor.surface)
    }
}
