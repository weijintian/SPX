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
        // TextEditor is backed by UITextView on iOS 15. Clear its opaque
        // default background so the compact composer keeps its rounded card.
        UITextView.appearance().backgroundColor = .clear
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

