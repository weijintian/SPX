import SwiftUI
import UIKit
import PhotosUI
import UniformTypeIdentifiers

// Compatibility primitives used by the app when the deployment target is iOS 15.
enum SPXSleep {
    static func seconds(_ value: Double) async throws {
        try await Task.sleep(nanoseconds: UInt64(max(0, value) * 1_000_000_000))
    }

    static func milliseconds(_ value: Double) async throws {
        try await Task.sleep(nanoseconds: UInt64(max(0, value) * 1_000_000))
    }
}

struct SPXNavigationPath: Equatable {
    init() {}
}

struct SPXNavigationContainer<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) { self.content = content() }

    init(path: Binding<SPXNavigationPath>, @ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View { NavigationView { content } }
}

struct SPXViewThatFits<Content: View>: View {
    private let content: Content
    init(in _: Axis.Set, @ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View { content }
}

// These overloads backport the binding-driven destinations used throughout the app.
extension View {
    func spxNavigationDestination<Item: Identifiable, Destination: View>(
        item: Binding<Item?>,
        @ViewBuilder destination: @escaping (Item) -> Destination
    ) -> some View {
        background(
            NavigationLink(
                isActive: Binding(
                    get: { item.wrappedValue != nil },
                    set: { if !$0 { item.wrappedValue = nil } }
                ),
                destination: {
                    if let value = item.wrappedValue { destination(value) }
                    else { EmptyView() }
                },
                label: { EmptyView() }
            ).hidden()
        )
    }

    func spxNavigationDestination<Destination: View>(
        isPresented: Binding<Bool>,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        background(
            NavigationLink(isActive: isPresented, destination: destination, label: { EmptyView() }).hidden()
        )
    }
}

struct SPXPhotosPickerItem: Identifiable, Hashable {
    let id: UUID
    fileprivate let provider: NSItemProvider
    let supportedContentTypes: [UTType]

    init(provider: NSItemProvider, contentTypes: [UTType]) {
        id = UUID()
        self.provider = provider
        supportedContentTypes = contentTypes
    }

    static func == (lhs: SPXPhotosPickerItem, rhs: SPXPhotosPickerItem) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }

    func loadTransferable<T>(type: T.Type) async throws -> T? {
        if type == Data.self {
            return try await withCheckedThrowingContinuation { continuation in
                provider.loadDataRepresentation(forTypeIdentifier: supportedContentTypes.first?.identifier ?? UTType.data.identifier) { data, error in
                    if let error { continuation.resume(throwing: error) }
                    else { continuation.resume(returning: data as? T) }
                }
            }
        }
        if type == PickedMovie.self {
            return try await withCheckedThrowingContinuation { continuation in
                provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, error in
                    if let error { continuation.resume(throwing: error); return }
                    guard let url else { continuation.resume(returning: nil); return }
                    do {
                        let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(url.pathExtension)
                        try FileManager.default.copyItem(at: url, to: target)
                        continuation.resume(returning: PickedMovie(url: target) as? T)
                    } catch { continuation.resume(throwing: error) }
                }
            }
        }
        return nil
    }
}

struct SPXPhotosPicker<Label: View>: View {
    @Binding private var selection: [SPXPhotosPickerItem]
    private let maxSelectionCount: Int
    private let filter: PHPickerFilter?
    private let label: Label
    @State private var presenting = false

    init(selection: Binding<[SPXPhotosPickerItem]>, maxSelectionCount: Int = 0, matching: PHPickerFilter? = nil, @ViewBuilder label: () -> Label) {
        _selection = selection
        self.maxSelectionCount = maxSelectionCount
        filter = matching
        self.label = label()
    }

    init(selection: Binding<SPXPhotosPickerItem?>, matching: PHPickerFilter? = nil, @ViewBuilder label: () -> Label) {
        self.init(selection: Binding(get: { selection.wrappedValue.map { [$0] } ?? [] }, set: { selection.wrappedValue = $0.first }), matching: matching, label: label)
    }

    var body: some View {
        Button { presenting = true } label: { label }
            .sheet(isPresented: $presenting) {
                SPXPHPicker(selection: $selection, maxSelectionCount: maxSelectionCount, filter: filter)
            }
    }
}

private struct SPXPHPicker: UIViewControllerRepresentable {
    @Binding var selection: [SPXPhotosPickerItem]
    let maxSelectionCount: Int
    let filter: PHPickerFilter?

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = filter
        configuration.selectionLimit = maxSelectionCount
        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let owner: SPXPHPicker
        init(_ owner: SPXPHPicker) { self.owner = owner }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            let values = results.map { result in
                let types = result.itemProvider.registeredTypeIdentifiers.compactMap { UTType($0) }
                return SPXPhotosPickerItem(provider: result.itemProvider, contentTypes: types)
            }
            DispatchQueue.main.async { self.owner.selection = values }
        }
    }
}

struct SPXSharePreview {
    let title: String
    let image: Image?
    init(_ title: String, image: Image? = nil) { self.title = title; self.image = image }
}

struct SPXShareLink: View {
    private let item: Any
    private let title: String
    private let customLabel: AnyView?
    @State private var presenting = false

    init<Item>(item: Item) {
        self.item = item
        title = "分享"
        customLabel = nil
    }

    init<Item, Label: View>(item: Item, preview: SPXSharePreview? = nil, @ViewBuilder label: () -> Label) {
        self.item = item
        title = preview?.title ?? "分享"
        customLabel = AnyView(label())
    }

    var body: some View {
        Group {
            Button { presenting = true } label: {
                if let customLabel { customLabel }
                else { Label(title, systemImage: "square.and.arrow.up") }
            }
        }
        .sheet(isPresented: $presenting) { SPXActivityView(items: [shareValue]) }
    }

    private var shareValue: Any {
        if let value = item as? URL { return value }
        if let value = item as? String { return value }
        if let value = item as? UIImage { return value }
        return String(describing: item)
    }
}

private struct SPXActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct SPXImageShareLink: View {
    let image: UIImage
    let title: String
    @State private var presenting = false
    var body: some View {
        Button { presenting = true } label: { Label("分享二维码", systemImage: "square.and.arrow.up") }
            .sheet(isPresented: $presenting) { SPXActivityView(items: [image]) }
    }
}
