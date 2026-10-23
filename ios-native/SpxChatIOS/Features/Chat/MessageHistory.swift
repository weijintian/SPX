import SwiftUI

enum MessageTimeline {
    static func date(_ value: String) -> Date? {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai"); formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
    static func showsTime(before message: Record, in messages: [Record]) -> Bool {
        guard let index = messages.firstIndex(where: { $0.id == message.id }), index > 0 else { return true }
        guard let current = date(message["created_at"].string), let previous = date(messages[index - 1]["created_at"].string) else { return false }
        return current.timeIntervalSince(previous) >= 300
    }
    static func label(_ value: String) -> String {
        guard let date = date(value) else { return value }
        let formatter = DateFormatter(); formatter.locale = .autoupdatingCurrent; formatter.timeZone = .autoupdatingCurrent
        formatter.dateFormat = Calendar.current.isDateInToday(date) ? "HH:mm" : "MM月dd日 HH:mm"
        return formatter.string(from: date)
    }
}

struct MessageText: View {
    let content: String
    var mine = false
    private var attributed: AttributedString {
        var text = AttributedString(content)
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return text }
        let source = content as NSString
        for match in detector.matches(in: content, range: NSRange(location: 0, length: source.length)) {
            guard let url = match.url, ["https", "http", "mailto"].contains(url.scheme?.lowercased() ?? ""), let stringRange = Range(match.range, in: content), let range = Range(stringRange, in: text) else { continue }
            text[range].link = url; text[range].underlineStyle = .single
            text[range].foregroundColor = mine ? .white : SPXColor.primary
        }
        return text
    }
    var body: some View { Text(attributed).textSelection(.enabled).multilineTextAlignment(.leading) }
}

/// Opens a bounded context through the same cursor endpoint used by Android.
struct MessageContextView: View {
    let targetID: Int
    let group: Bool
    let messageID: Int
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [Record] = []
    @State private var error: String?
    @State private var loading = false
    @State private var media: Record?
    @State private var forward: Record?
    @State private var document: URL?
    @State private var product: Record?
    @State private var groupCode: String?
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    ForEach(rows) { row in
                        VStack(spacing: 8) {
                            Text(MessageTimeline.label(row["created_at"].string)).font(.caption2).foregroundStyle(.secondary)
                            MessageBubble(message: row, currentUserID: session.user?.id ?? 0, open: { open(row) })
                        }.padding(8).background(row.id == messageID ? SPXColor.primary.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 16)).id(row.id)
                            .contextMenu { if MessageContent.canForward(row) { Button("转发", systemImage: "arrowshape.turn.up.right") { forward = row } } }
                    }
                    if loading { ProgressView() }
                    if let error { ErrorBanner(message: error); Button("重试") { Task { await load() } } }
                }.padding(12)
            }.onChange(of: rows.map(\.id)) { _, _ in proxy.scrollTo(messageID, anchor: .center) }
        }.navigationTitle("消息上下文").navigationBarTitleDisplayMode(.inline).spxPage()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .task { await load() }
            .fullScreenCover(item: $media) { selected in MediaPreview(messages: rows.filter { ["image", "video"].contains($0["message_type"].string) }, initialID: selected.id) }
            .sheet(item: $forward) { ForwardMessageView(message: $0) }
            .navigationDestination(item: $product) { ProductDetailView(product: $0) }
            .sheet(isPresented: Binding(get: { groupCode != nil }, set: { if !$0 { groupCode = nil } })) { NavigationStack { GroupJoinView(initialCode: groupCode ?? "").toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { groupCode = nil } } } } }
            .sheet(isPresented: Binding(get: { document != nil }, set: { if !$0 { document = nil } })) { if let document { DocumentPreview(url: document) } }
    }
    private func load() async {
        guard !loading else { return }; loading = true; defer { loading = false }
        do { rows = try await APIClient.shared.get(group ? "groups.messages" : "messages", [group ? "group_id" : "chat_id": String(targetID), "around_id": String(messageID), "limit": "100"]).list; error = nil }
        catch { self.error = error.localizedDescription }
    }
    private func open(_ row: Record) {
        if let card = MessageContent.product(row) { product = Record(["id": card["product_id"], "name": card["name"], "image_url": card["image_url"], "cny_price": card["cny_price"]]); return }
        if let code = MessageContent.groupCode(row) { groupCode = code; return }
        switch row["message_type"].string {
        case "image", "video": media = row
        case "voice": Task { do { try await VoicePlayback.shared.toggle(row["media_url"].string, userID: session.user?.id ?? 0) } catch { self.error = error.localizedDescription } }
        case "file": Task { do { document = try await MediaCache.shared.file(row["media_url"].string, userID: session.user?.id ?? 0) } catch { self.error = error.localizedDescription } }
        case "red_packet": NotificationCenter.default.post(name: .spxOpenPacket, object: row)
        default: break
        }
    }
}
