import SwiftUI
import UIKit

enum SPXColor {
    static let ink = adaptive(0x181F33, dark: 0xF2F4F9)
    static let primary = adaptive(0x2869EA, dark: 0x8EA9FF)
    static let commerce = adaptive(0x2869EA, dark: 0x8EA9FF)
    static let surface = adaptive(0xF6F8FC, dark: 0x10151E)
    static let card = adaptive(0xFFFFFF, dark: 0x1C2432)
    static let line = adaptive(0xE2E7F1, dark: 0x344054)
    static let muted = adaptive(0x677187, dark: 0xA4AFC1)
    static let tint = adaptive(0xEDF3FF, dark: 0x26334C)
    static let success = adaptive(0x238B69, dark: 0x6ED8B0)
    static func hex(_ value: UInt32) -> Color { Color(red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255, blue: Double(value & 255) / 255) }
    private static func adaptive(_ light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255, green: CGFloat((value >> 8) & 255) / 255, blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

struct SPXCard<Content: View>: View {
    let content: Content
    init(@ViewBuilder _ content: () -> Content) { self.content = content() }
    var body: some View {
        content.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(SPXColor.line, lineWidth: 0.7))
    }
}

struct SPXButton: View {
    let title: String
    var icon: String? = nil
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) { if let icon { Image(systemName: icon) }; Text(title).fontWeight(.semibold) }
                .frame(maxWidth: .infinity).padding(.vertical, 14)
        }.buttonStyle(SPXPrimaryButtonStyle())
    }
}

struct Avatar: View {
    let url: String
    let name: String
    var size: CGFloat = 48
    @EnvironmentObject private var session: SessionStore
    @State private var image: UIImage?
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { fallback }
        }.frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
            .accessibilityLabel(name)
            .task(id: url) {
                image = nil; guard !url.isEmpty else { return }
                do {
                    let decoded = try await ImageThumbnailCache.shared.image(url, userID: session.user?.id ?? 0, maximumPixelSize: Int(size * 3))
                    try Task.checkCancellation(); image = decoded
                } catch { }
            }
    }
    private var fallback: some View {
        Image(systemName: "person.fill").font(.system(size: size * 0.45)).foregroundStyle(SPXColor.primary)
            .frame(maxWidth: .infinity, maxHeight: .infinity).background(SPXColor.primary.opacity(0.1))
    }
}

struct ErrorBanner: View {
    let message: String
    var body: some View { Label(message, systemImage: "exclamationmark.circle").font(.footnote).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true).accessibilityAddTraits(.isStaticText) }
}

struct EmptyState: View {
    let title: String
    var description: String = ""
    var icon: String = "tray"
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 32, weight: .light)).foregroundStyle(SPXColor.muted)
                .frame(width: 72, height: 72).background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 22))
            Text(title).font(.system(size: 17, weight: .semibold)).foregroundStyle(SPXColor.ink)
            if !description.isEmpty { Text(description).font(.system(size: 13)).foregroundStyle(SPXColor.muted).multilineTextAlignment(.center) }
        }.frame(maxWidth: .infinity).padding(.horizontal, 24).padding(.vertical, 36)
    }
}

struct InfoRow: View {
    let title: String
    let value: String
    var body: some View { HStack(alignment: .top) { Text(title).foregroundStyle(.secondary); Spacer(); Text(value.isEmpty ? "—" : value).multilineTextAlignment(.trailing).textSelection(.enabled) }.font(.subheadline) }
}

extension View {
    func successToast(_ message: Binding<String?>) -> some View { modifier(SPXToastModifier(message: message)) }
    /// Observes taps without taking touches away from inputs, buttons or scrolling.
    func spxDismissKeyboardOnTap(onDismiss: (() -> Void)? = nil) -> some View {
        modifier(SPXKeyboardDismissModifier(onDismiss: onDismiss))
    }
    func spxPage() -> some View {
        background(SPXColor.surface.ignoresSafeArea())
            .tint(SPXColor.primary).navigationBarTitleDisplayMode(.inline)
            
    }
    func errorAlert(_ error: Binding<String?>) -> some View {
        alert("提示", isPresented: Binding(get: { error.wrappedValue != nil }, set: { if !$0 { error.wrappedValue = nil } })) {
            Button("知道了", role: .cancel) { error.wrappedValue = nil }
        } message: { Text(error.wrappedValue ?? "") }
    }
}

private struct SPXToastModifier: ViewModifier {
    @Binding var message: String?
    func body(content: Content) -> some View {
        content.overlay {
            if let message {
                Text(message).font(.system(size: 14, weight: .medium)).multilineTextAlignment(.center)
                    .foregroundStyle(.white).padding(.horizontal, 22).padding(.vertical, 16)
                    .background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 14))
                    .padding(32).allowsHitTesting(false).transition(.opacity)
                    .accessibilityAddTraits(.isStaticText)
            }
        }.task(id: message) {
            guard let message else { return }
            UIAccessibility.post(notification: .announcement, argument: message)
            do { try await SPXSleep.seconds(2); self.message = nil } catch { }
        }.animation(.easeOut(duration: 0.15), value: message)
    }
}
