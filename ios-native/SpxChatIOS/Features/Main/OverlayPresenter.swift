import SwiftUI
import UIKit

@MainActor final class OverlayPresentation: ObservableObject {
    static let shared = OverlayPresentation()
    @Published var packetID: String?
    var miniFrame: CGRect = .zero
}

/// A separate scene window keeps calls above navigation sheets while allowing chat interaction.
struct OverlayPresenter: UIViewRepresentable {
    let session: SessionStore
    func makeUIView(context: Context) -> AnchorView { let view = AnchorView(); view.session = session; return view }
    func updateUIView(_ view: AnchorView, context: Context) { view.session = session; view.updateWindow() }
    static func dismantleUIView(_ view: AnchorView, coordinator: ()) { view.closeWindow() }
    final class AnchorView: UIView {
        var session: SessionStore?
        private var overlay: OverlayWindow?
        override func didMoveToWindow() { super.didMoveToWindow(); updateWindow() }
        func updateWindow() {
            guard let scene = window?.windowScene, let session else { return }
            guard overlay?.windowScene !== scene else { return }
            closeWindow()
            let window = OverlayWindow(windowScene: scene)
            window.backgroundColor = .clear
            window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.normal.rawValue + 1)
            let controller = UIHostingController(rootView: OverlayContents().environmentObject(session))
            controller.view.backgroundColor = .clear
            window.rootViewController = controller
            window.isHidden = false
            overlay = window
        }
        func closeWindow() { overlay?.isHidden = true; overlay?.rootViewController = nil; overlay = nil }
    }
}

private final class OverlayWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let calls = CallCoordinator.shared
        if calls.call != nil {
            if calls.minimized && !OverlayPresentation.shared.miniFrame.contains(point) {
                return OverlayPresentation.shared.packetID == nil ? nil : super.hitTest(point, with: event)
            }
            return super.hitTest(point, with: event)
        }
        guard OverlayPresentation.shared.packetID != nil else { return nil }
        return super.hitTest(point, with: event)
    }
}

private struct OverlayContents: View {
    @AppStorage("spx.appearance") private var appearance = "system"
    @EnvironmentObject private var session: SessionStore
    @ObservedObject private var calls = CallCoordinator.shared
    @ObservedObject private var presentation = OverlayPresentation.shared
    var body: some View {
        ZStack {
            if session.isAuthenticated, let packet = presentation.packetID {
                RedPacketPopup(packetID: packet) { presentation.packetID = nil }.id(packet)
            }
            if session.isAuthenticated { CallOverlay() }
            if let notice = calls.notice {
                Text(notice).font(.subheadline).multilineTextAlignment(.center).padding(.horizontal, 22).padding(.vertical, 16)
                    .foregroundStyle(.white).background(.black.opacity(0.82), in: RoundedRectangle(cornerRadius: 16)).padding(32).allowsHitTesting(false)
            }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(.clear)
            .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
    }
}
