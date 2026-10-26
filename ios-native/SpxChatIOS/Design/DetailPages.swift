import SwiftUI
import PhotosUI
import ImageIO
import UIKit

/// Detail pages follow the Android card layout while keeping native navigation.
struct SPXDetailPage<Content: View>: View {
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) { content }
                .padding(16).frame(maxWidth: 680).frame(maxWidth: .infinity)
        }.spxDismissKeyboardOnTap().spxPage()

    }
}

struct SPXPageHeading: View {
    let title: String
    var subtitle = ""
    var icon = "square.grid.2x2"
    var color = SPXColor.primary
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            SPXIcon(icon).frame(width: 25, height: 25).foregroundStyle(color)
                .frame(width: 48, height: 48).background(color.opacity(0.09), in: RoundedRectangle(cornerRadius: 15))
            VStack(alignment: .leading, spacing: 7) {
                Text(title).font(.system(size: 23, weight: .bold)).foregroundStyle(SPXColor.ink)
                if !subtitle.isEmpty { Text(subtitle).font(.system(size: 13)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.padding(.vertical, 8)
    }
}

struct SPXField<Content: View>: View {
    let title: String
    private let content: Content
    init(_ title: String, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(SPXColor.muted)
            content.font(.system(size: 15)).textFieldStyle(SPXInputStyle())
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct SPXStatusPill: View {
    let title: String
    var color = SPXColor.primary
    var body: some View {
        Text(title).font(.system(size: 12, weight: .semibold))
            .foregroundStyle(color).padding(.horizontal, 10).padding(.vertical, 6)
            .background(color.opacity(0.09), in: Capsule())
    }
}

struct SPXBottomAction<Content: View>: View {
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(SPXColor.line).frame(height: 0.7)
            content.padding(.horizontal, 16).padding(.vertical, 12)
                .frame(maxWidth: 680).frame(maxWidth: .infinity)
        }.background(SPXColor.card).buttonStyle(SPXDetailButtonStyle())
    }
}

private struct SPXDetailButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 15, weight: .semibold))
            .multilineTextAlignment(.center).foregroundStyle(.white)
            .padding(.horizontal, 16).padding(.vertical, 13).frame(maxWidth: .infinity, minHeight: 48)
            .background(SPXColor.hex(0x2D4ECF).opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.4), in: RoundedRectangle(cornerRadius: 14))
    }
}

struct SPXSettingsRow: View {
    let title: String
    let icon: String
    var detail = ""
    var color = SPXColor.primary
    var body: some View {
        HStack(spacing: 12) {
            SPXIcon(icon).frame(width: 22, height: 22).foregroundStyle(color)
                .frame(width: 36, height: 36).background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            Text(title).font(.system(size: 15)).foregroundStyle(SPXColor.ink)
            Spacer(minLength: 8)
            if !detail.isEmpty { Text(detail).font(.system(size: 12)).foregroundStyle(SPXColor.muted).multilineTextAlignment(.trailing) }
            SPXIcon("chevron").frame(width: 14, height: 14).foregroundStyle(SPXColor.muted)
        }.frame(minHeight: 44).contentShape(Rectangle())
    }
}

struct SPXFilterTabs: View {
    let options: [(String, String)]
    @Binding var selection: String
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options, id: \.0) { option in
                    Button { selection = option.0 } label: {
                        Text(option.1).font(.system(size: 13, weight: selection == option.0 ? .semibold : .regular))
                            .foregroundStyle(selection == option.0 ? Color.white : SPXColor.muted)
                            .padding(.horizontal, 15).padding(.vertical, 11)
                            .background(selection == option.0 ? SPXColor.hex(0x2D4ECF) : SPXColor.card, in: Capsule())
                    }.buttonStyle(.plain).accessibilityAddTraits(selection == option.0 ? .isSelected : [])
                }
            }.padding(.vertical, 2)
        }
    }
}

struct SPXTimelineRow: View {
    let title: String
    let date: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Circle().fill(SPXColor.primary).frame(width: 7, height: 7).padding(.top, 6)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.system(size: 14)).foregroundStyle(SPXColor.ink)
                Text(date).font(.system(size: 12)).foregroundStyle(SPXColor.muted)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct SPXPhotoSelectionStrip: View {
    let items: [SPXPhotosPickerItem]
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 10) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                    SPXSelectedPhoto(item: item).overlay(alignment: .bottomTrailing) {
                        Text("\(index + 1)").font(.caption2.bold()).padding(5).foregroundStyle(.white).background(.black.opacity(0.55), in: Circle()).padding(5)
                    }
                }
            }
        }.frame(height: 88)
    }
}

private struct SPXSelectedPhoto: View {
    let item: SPXPhotosPickerItem
    @State private var image: UIImage?
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Image(systemName: "photo").foregroundStyle(SPXColor.muted).frame(maxWidth: .infinity, maxHeight: .infinity).background(SPXColor.tint) }
        }.frame(width: 88, height: 88).clipped().clipShape(RoundedRectangle(cornerRadius: 12))
            .task(id: item) {
                image = nil
                guard let data = try? await item.loadTransferable(type: Data.self), !Task.isCancelled else { return }
                let thumbnail = await Task.detached(priority: .utility) {
                    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                          let result = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 264] as CFDictionary) else { return Optional<UIImage>.none }
                    return UIImage(cgImage: result)
                }.value
                if !Task.isCancelled { image = thumbnail }
            }
    }
}
