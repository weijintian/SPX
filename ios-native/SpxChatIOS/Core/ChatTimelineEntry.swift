import Foundation

/// One ordered list for persisted messages and optimistic attachments.
struct ChatTimelineEntry: Identifiable {
    let id: String
    let date: Date
    let message: Record?
    let pending: PendingMessage?
    static func merge(messages: [Record], pending: [PendingMessage], userID: Int) -> [ChatTimelineEntry] {
        let local = Dictionary(pending.map { ($0.id, $0.createdAt) }, uniquingKeysWith: { first, _ in first })
        let acknowledged = Set(messages.filter { $0["sender_id"].int == userID }.map { $0["client_message_id"].string })
        var result = messages.map { message -> ChatTimelineEntry in
            let client = message["client_message_id"].string, sender = message["sender_id"].int
            let date = (sender == userID ? local[client] : nil) ?? MessageTimeline.date(message.text("local_created_at", "created_at")) ?? .distantPast
            let id = client.isEmpty ? "m-\(message.id)" : "c-\(sender)-\(client)"
            return ChatTimelineEntry(id: id, date: date, message: message, pending: nil)
        }
        result += pending.filter { !acknowledged.contains($0.id) }.map {
            ChatTimelineEntry(id: "c-\(userID)-\($0.id)", date: $0.createdAt, message: nil, pending: $0)
        }
        return result.sorted { $0.date == $1.date ? $0.id < $1.id : $0.date < $1.date }
    }
}
