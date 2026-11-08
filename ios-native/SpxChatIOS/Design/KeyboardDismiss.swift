import SwiftUI
import UIKit

/// Screen-scoped observation: never place an intercepting overlay over editable content.
struct SPXKeyboardDismissModifier: ViewModifier {
    var onDismiss: (() -> Void)?
    @State private var active = false

    func body(content: Content) -> some View {
        content.background {
            SPXKeyboardDismissBridge(active: active, onDismiss: onDismiss)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .onAppear { active = true }
        .onDisappear { active = false }
    }
}

enum SPXKeyboardTouchPolicy {
    static func isInputOrControl(_ touchedView: UIView?) -> Bool {
        var ancestor = touchedView
        while let view = ancestor {
            if view is UIControl || view is UITextView || view is UIPickerView { return true }
            ancestor = view.superview
        }
        return false
    }
}

private struct SPXKeyboardDismissBridge: UIViewRepresentable {
    let active: Bool
    let onDismiss: (() -> Void)?

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.windowChanged = { [weak coordinator = context.coordinator] surface in
            coordinator?.connect(surface)
        }
        return view
    }

    func updateUIView(_ view: Surface, context: Context) {
        context.coordinator.active = active
        context.coordinator.onDismiss = onDismiss
        context.coordinator.connect(view)
    }

    static func dismantleUIView(_ view: Surface, coordinator: Coordinator) {
        view.windowChanged = nil
        coordinator.disconnect()
    }

    final class Surface: UIView {
        var windowChanged: ((Surface) -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            windowChanged?(self)
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var active = false
        var onDismiss: (() -> Void)?
        private weak var surface: Surface?
        private weak var observedWindow: UIWindow?
        private lazy var tap: UITapGestureRecognizer = {
            let gesture = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard))
            gesture.cancelsTouchesInView = false
            gesture.delaysTouchesBegan = false
            gesture.delaysTouchesEnded = false
            gesture.delegate = self
            return gesture
        }()

        func connect(_ view: Surface) {
            surface = view
            guard observedWindow !== view.window else { return }
            observedWindow?.removeGestureRecognizer(tap)
            observedWindow = view.window
            observedWindow?.addGestureRecognizer(tap)
        }

        func disconnect() {
            observedWindow?.removeGestureRecognizer(tap)
            observedWindow = nil
            surface = nil
            active = false
            onDismiss = nil
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard active, let surface, let window = surface.window,
                  touch.view?.window === window,
                  surface.bounds.contains(touch.location(in: surface)),
                  !SPXKeyboardTouchPolicy.isInputOrControl(touch.view) else { return false }
            var ancestor: UIView? = surface
            while let view = ancestor {
                if view.isHidden || view.alpha < 0.01 { return false }
                ancestor = view.superview
            }
            return true
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            true
        }

        @objc private func dismissKeyboard() {
            guard active, let window = observedWindow else { return }
            onDismiss?()
            window.endEditing(true)
        }
    }
}
