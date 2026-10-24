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
            if authenticated { link = pendingLink; pendingLink = nil }
            else { presentation.packetID = nil; link = nil }
        }
        .onOpenURL { url in if let value = AppLink.parse(url) { if session.isAuthenticated { link = value } else { pendingLink = value } } }
        .sheet(item: $link) { value in
            NavigationStack {
                Group {
                    switch value {
                    case .user(let id): LinkedUserView(lookup: id)
                    case .group(let code): GroupJoinView(initialCode: code)
                    case .product(let id): ProductDetailView(product: Record(["id": .number(Decimal(id))]))
                    }
                }.toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { link = nil } } }
            }
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
    @State private var roots = [UUID(), UUID(), UUID(), UUID()]
    private var selection: Binding<Int> {
        Binding(get: { selected }, set: { value in
            // A tab tap always has a predictable destination: its root page.
            roots[value] = UUID()
            selected = value
        })
    }
    var body: some View {
        TabView(selection: selection) {
            NavigationStack { ChatListView().toolbar(.visible, for: .tabBar) }.id(roots[0]).tabItem { Label("聊天", systemImage: "bubble.left.and.bubble.right") }.tag(0)
            NavigationStack { ContactsView().toolbar(.visible, for: .tabBar) }.id(roots[1]).tabItem { Label("通讯录", systemImage: "person.crop.rectangle.stack") }.tag(1)
            NavigationStack { EcosystemView().toolbar(.visible, for: .tabBar) }.id(roots[2]).tabItem { Label("生态", systemImage: "square.grid.2x2") }.tag(2)
            NavigationStack { ProfileView().toolbar(.visible, for: .tabBar) }.id(roots[3]).tabItem { Label("我的", systemImage: "person.crop.circle") }.tag(3)
        }.tint(SPXColor.primary)
    }
}
