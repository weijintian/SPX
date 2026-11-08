import SwiftUI
import UIKit

@MainActor final class SPXAppDelegate: NSObject, UIApplicationDelegate {
    static var backgroundEventHandler: (() -> Void)?
    private static var backgroundEventsReady = false

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let navigation = UINavigationBarAppearance()
        navigation.configureWithOpaqueBackground()
        navigation.backgroundColor = UIColor(SPXColor.card)
        navigation.shadowColor = UIColor(SPXColor.line)
        navigation.titleTextAttributes = [.font: UIFont.systemFont(ofSize: 18, weight: .semibold), .foregroundColor: UIColor(SPXColor.ink)]
        UINavigationBar.appearance().standardAppearance = navigation
        UINavigationBar.appearance().scrollEdgeAppearance = navigation
        UINavigationBar.appearance().compactAppearance = navigation
        UINavigationBar.appearance().tintColor = UIColor(SPXColor.primary)
        MessageNotifications.shared.configure()
        _ = BackgroundMediaTransfer.shared
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        MessageNotifications.shared.receivedDeviceToken(deviceToken)
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        MessageNotifications.shared.registrationFailed(error)
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == BackgroundMediaTransfer.identifier else { completionHandler(); return }
        Self.backgroundEventHandler = completionHandler
        _ = BackgroundMediaTransfer.shared
        Self.completeBackgroundEventsIfReady()
    }

    nonisolated static func finishBackgroundEvents() {
        DispatchQueue.main.async {
            backgroundEventsReady = true
            completeBackgroundEventsIfReady()
        }
    }

    // MARK: - 隐私遮罩
    // 切到后台或多任务界面时，整个窗口（含弹出的 sheet）都会被系统截图，
    // 这里盖一层毛玻璃，避免聊天内容出现在应用切换器缩略图里。
    private static var privacyShield: UIView?

    func applicationWillResignActive(_ application: UIApplication) { Self.showPrivacyShield() }
    func applicationDidBecomeActive(_ application: UIApplication) { Self.hidePrivacyShield() }

    private static func showPrivacyShield() {
        guard privacyShield == nil else { return }
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let window = scene?.windows.first(where: { $0.isKeyWindow }) ?? scene?.windows.first else { return }
        let shield = UIView(frame: window.bounds)
        shield.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        shield.backgroundColor = .clear
        let blur = UIVisualEffectView(effect: UIBlurEffect(style: .systemThickMaterial))
        blur.frame = shield.bounds
        blur.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        shield.addSubview(blur)
        let label = UILabel()
        label.text = "SPX CHAT"
        label.font = .systemFont(ofSize: 18, weight: .semibold)
        label.textColor = UIColor(SPXColor.muted)
        label.translatesAutoresizingMaskIntoConstraints = false
        shield.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: shield.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: shield.centerYAnchor),
        ])
        shield.accessibilityLabel = "隐私保护已开启"
        window.addSubview(shield)
        privacyShield = shield
    }

    private static func hidePrivacyShield() {
        privacyShield?.removeFromSuperview()
        privacyShield = nil
    }

    private static func completeBackgroundEventsIfReady() {
        guard backgroundEventsReady, let handler = backgroundEventHandler else { return }
        backgroundEventsReady = false
        backgroundEventHandler = nil
        handler()
    }
}

@main struct SpxChatIOSApp: App {
    @UIApplicationDelegateAdaptor(SPXAppDelegate.self) private var appDelegate
    @StateObject private var session = SessionStore()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(session)
                .onChange(of: phase) { value in session.setActive(value == .active) }
        }
    }
}

