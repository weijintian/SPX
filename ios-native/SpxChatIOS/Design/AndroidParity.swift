import SwiftUI

/// Shared Android presentation; the native list keeps swipe actions, deletion and focus behavior.
struct SPXList<Content: View>: View {
    private let content: Content
    private let plain: Bool
    init(plain: Bool = false, @ViewBuilder content: () -> Content) { self.content = content(); self.plain = plain }
    private var list: some View {
        List {
            content.listRowBackground(SPXColor.card)
                .listRowSeparatorTint(SPXColor.line)
                .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
        }.listSectionSpacing(14)
    }
    var body: some View {
        Group { if plain { list.listStyle(.plain) } else { list.listStyle(.insetGrouped) } }
            .environment(\.defaultMinListRowHeight, 52).font(.system(size: 15))
            .scrollDismissesKeyboard(.interactively).spxDismissKeyboardOnTap().spxPage()
    }
}

struct SPXForm<Content: View>: View {
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View { SPXList { content }.font(.system(size: 15)).foregroundStyle(SPXColor.ink).textFieldStyle(SPXInputStyle()) }
}

struct SPXInputStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration.padding(.horizontal, 12).padding(.vertical, 12)
            .background(SPXColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(SPXColor.line, lineWidth: 0.7))
    }
}

struct SPXPanel<Content: View>: View {
    let title: String
    let footer: String
    private let content: Content
    init(_ title: String, footer: String = "", @ViewBuilder content: () -> Content) { self.title = title; self.footer = footer; self.content = content() }
    var body: some View {
        SPXCard { VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(SPXColor.ink)
            content
            if !footer.isEmpty { Text(footer).font(.system(size: 12)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true) }
        } }.textFieldStyle(SPXInputStyle())
    }
}

struct SPXPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 15, weight: .semibold))
            .foregroundStyle(.white).frame(minHeight: 48)
            .background(SPXColor.hex(0x2869EA).opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.4), in: RoundedRectangle(cornerRadius: 14))
            .contentShape(RoundedRectangle(cornerRadius: 14))
    }
}

struct SPXMenuRow: View {
    let title: String
    var subtitle = ""
    let icon: String
    var color = SPXColor.primary
    var body: some View {
        HStack(spacing: 14) {
            SPXIcon(icon).foregroundStyle(color).frame(width: 25, height: 25)
                .frame(width: 40, height: 40).background(color.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(SPXColor.ink)
                if !subtitle.isEmpty { Text(subtitle).font(.system(size: 12)).foregroundStyle(SPXColor.muted).lineLimit(2) }
            }
            Spacer(minLength: 8)
            SPXIcon("chevron").foregroundStyle(SPXColor.muted.opacity(0.6)).frame(width: 17, height: 17)
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(SPXColor.line, lineWidth: 0.7))
            .contentShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct SPXSectionTitle: View {
    let title: String
    var detail = ""
    var body: some View {
        HStack { Text(title).font(.system(size: 16, weight: .semibold)); Spacer(); if !detail.isEmpty { Text(detail).font(.system(size: 12)).foregroundStyle(SPXColor.muted) } }
            .foregroundStyle(SPXColor.ink).padding(.top, 8).padding(.bottom, 3)
    }
}

/// Stroke paths mirror the Android navigation and common action icon family.
struct SPXIcon: View {
    let name: String
    init(_ name: String) { self.name = name }
    var body: some View {
        Group {
            if SPXGlyph.supports(name) { SPXGlyph(name: name).stroke(style: StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round)) }
            else { Image(systemName: name).resizable().scaledToFit().symbolRenderingMode(.monochrome) }
        }.accessibilityHidden(true)
    }
}

private struct SPXGlyph: Shape {
    let name: String
    static func supports(_ name: String) -> Bool { ["chat", "contacts", "eco", "profile", "wallet", "order", "store", "gift", "chevron", "phone", "video", "search", "plus", "settings"].contains(name) }
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let line: ([CGPoint]) -> Void = { points in guard let first = points.first else { return }; p.move(to: first); for point in points.dropFirst() { p.addLine(to: point) } }
        switch name {
        case "chat":
            p.addRoundedRect(in: CGRect(x: 3, y: 4, width: 18, height: 14), cornerSize: CGSize(width: 4, height: 4)); line([CGPoint(x: 7,y: 18),CGPoint(x: 5,y: 22),CGPoint(x: 13,y: 18)])
            for x in [7.0, 12.0, 17.0] { p.addEllipse(in: CGRect(x: x-0.45,y: 10.55,width: 0.9,height: 0.9)) }
        case "contacts":
            p.addRoundedRect(in: CGRect(x: 4,y: 2,width: 17,height: 20), cornerSize: CGSize(width: 2,height: 2)); p.addEllipse(in: CGRect(x: 9,y: 6,width: 6,height: 6)); p.addArc(center: CGPoint(x: 12,y: 19), radius: 5,startAngle: .degrees(180),endAngle: .degrees(0),clockwise: false); line([CGPoint(x: 2,y: 7),CGPoint(x: 5,y: 7)]); line([CGPoint(x: 2,y: 16),CGPoint(x: 5,y: 16)])
        case "eco":
            for y in [CGFloat(3),14] { for x in [CGFloat(3),14] { p.addRoundedRect(in: CGRect(x: x,y: y,width: 7,height: 7),cornerSize: CGSize(width: 1.5,height: 1.5)) } }
        case "profile":
            p.addEllipse(in: CGRect(x: 8,y: 3,width: 8,height: 8)); p.addArc(center: CGPoint(x: 12,y: 22),radius: 8,startAngle: .degrees(180),endAngle: .degrees(0),clockwise: false)
        case "wallet":
            p.addRoundedRect(in: CGRect(x: 3,y: 5,width: 18,height: 16),cornerSize: CGSize(width: 3,height: 3)); p.addRoundedRect(in: CGRect(x: 15,y: 10,width: 7,height: 6),cornerSize: CGSize(width: 1.5,height: 1.5)); line([CGPoint(x: 5,y: 5),CGPoint(x: 17,y: 2),CGPoint(x: 19,y: 5)])
        case "order":
            p.addRoundedRect(in: CGRect(x: 5,y: 3,width: 14,height: 18),cornerSize: CGSize(width: 2,height: 2)); for y in [CGFloat(8),12,16] { line([CGPoint(x: 9,y: y),CGPoint(x: y == 16 ? 13:15,y: y)]) }
        case "store":
            p.addRoundedRect(in: CGRect(x: 4,y: 10,width: 16,height: 11),cornerSize: CGSize(width: 1.5,height: 1.5)); line([CGPoint(x: 3,y: 10),CGPoint(x: 5,y: 4),CGPoint(x: 19,y: 4),CGPoint(x: 21,y: 10),CGPoint(x: 3,y: 10)]); p.addRect(CGRect(x: 10,y: 14,width: 5,height: 7))
        case "gift":
            p.addRect(CGRect(x: 4,y: 10,width: 16,height: 11)); p.addRoundedRect(in: CGRect(x: 3,y: 7,width: 18,height: 4),cornerSize: CGSize(width: 1,height: 1)); line([CGPoint(x: 12,y: 7),CGPoint(x: 12,y: 21)]); p.addEllipse(in: CGRect(x: 6,y: 2,width: 6,height: 5)); p.addEllipse(in: CGRect(x: 12,y: 2,width: 6,height: 5))
        case "phone":
            p.move(to: CGPoint(x: 5,y: 3)); p.addQuadCurve(to: CGPoint(x: 3,y: 7),control: CGPoint(x: 2,y: 3)); p.addQuadCurve(to: CGPoint(x: 17,y: 21),control: CGPoint(x: 5,y: 19)); p.addQuadCurve(to: CGPoint(x: 21,y: 17),control: CGPoint(x: 22,y: 21)); line([CGPoint(x: 21,y: 17),CGPoint(x: 16,y: 14),CGPoint(x: 13,y: 16),CGPoint(x: 8,y: 11),CGPoint(x: 10,y: 8),CGPoint(x: 7,y: 3),CGPoint(x: 5,y: 3)])
        case "video":
            p.addRoundedRect(in: CGRect(x: 2,y: 5,width: 13,height: 14),cornerSize: CGSize(width: 3,height: 3)); line([CGPoint(x: 15,y: 9),CGPoint(x: 22,y: 5),CGPoint(x: 22,y: 19),CGPoint(x: 15,y: 15)])
        case "search": p.addEllipse(in: CGRect(x: 4,y: 3,width: 13,height: 13)); line([CGPoint(x: 15,y: 15),CGPoint(x: 21,y: 21)])
        case "plus": line([CGPoint(x: 12,y: 4),CGPoint(x: 12,y: 20)]); line([CGPoint(x: 4,y: 12),CGPoint(x: 20,y: 12)])
        case "chevron": line([CGPoint(x: 9,y: 6),CGPoint(x: 15,y: 12),CGPoint(x: 9,y: 18)])
        case "settings":
            p.addEllipse(in: CGRect(x: 6,y: 6,width: 12,height: 12)); p.addEllipse(in: CGRect(x: 10,y: 10,width: 4,height: 4))
            for index in 0..<8 {
                let angle = Double(index) * .pi / 4
                line([CGPoint(x: 12 + cos(angle) * 8,y: 12 + sin(angle) * 8),CGPoint(x: 12 + cos(angle) * 10,y: 12 + sin(angle) * 10)])
            }
        default: p.addEllipse(in: CGRect(x: 3,y: 3,width: 18,height: 18)); p.addEllipse(in: CGRect(x: 9,y: 9,width: 6,height: 6))
        }
        return p.applying(CGAffineTransform(scaleX: rect.width / 24, y: rect.height / 24).concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY)))
    }
}
