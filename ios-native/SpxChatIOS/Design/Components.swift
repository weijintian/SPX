import SwiftUI

enum SPXColor {
    static let ink = Color.primary
    static let primary = Color(red: 0.12, green: 0.36, blue: 0.96)
    static let surface = Color(uiColor: .systemGroupedBackground)
    static let card = Color(uiColor: .secondarySystemGroupedBackground)
    static let line = Color.primary.opacity(0.07)
    static let muted = Color.secondary
}

struct SPXCard<Content: View>: View {
    let content: Content
    init(@ViewBuilder _ content: () -> Content) { self.content = content() }
    var body: some View {
        content.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(SPXColor.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
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
        }.buttonStyle(.borderedProminent).tint(SPXColor.primary).buttonBorderShape(.roundedRectangle(radius: 14))
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
                if let file = try? await MediaCache.shared.file(url, userID: session.user?.id ?? 0) { image = UIImage(contentsOfFile: file.path) }
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
    var body: some View { ContentUnavailableView(title, systemImage: icon, description: Text(description)) }
}

struct InfoRow: View {
    let title: String
    let value: String
    var body: some View { HStack(alignment: .top) { Text(title).foregroundStyle(.secondary); Spacer(); Text(value.isEmpty ? "—" : value).multilineTextAlignment(.trailing).textSelection(.enabled) }.font(.subheadline) }
}

extension View {
    func spxPage() -> some View { scrollContentBackground(.hidden).background(SPXColor.surface.ignoresSafeArea()) }
    func errorAlert(_ error: Binding<String?>) -> some View {
        alert("提示", isPresented: Binding(get: { error.wrappedValue != nil }, set: { if !$0 { error.wrappedValue = nil } })) {
            Button("知道了", role: .cancel) { error.wrappedValue = nil }
        } message: { Text(error.wrappedValue ?? "") }
    }
}
