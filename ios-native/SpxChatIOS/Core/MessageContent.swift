import Foundation

enum MessageContent {
    static func product(_ message: Record) -> Record? {
        guard message["message_type"].string == "product",
              let data = message["content"].string.data(using: .utf8),
              let value = try? JSONDecoder().decode(Record.self, from: data),
              value["product_id"].int > 0 else { return nil }
        return value
    }
    static func groupCode(_ message: Record) -> String? {
        let text = message["content"].string
        guard text.hasPrefix("SPXGROUP:") else { return nil }
        let code = String(text.dropFirst(9).prefix { !$0.isWhitespace }).uppercased()
        guard code.count == 6, code.allSatisfy({ $0.isASCII && $0.isLetter || $0.isNumber && $0.isASCII }) else { return nil }
        return code
    }
    static func groupTitle(_ message: Record) -> String {
        let lines = message["content"].string.components(separatedBy: .newlines)
        guard lines.count > 1, let data = lines[1].data(using: .utf8), let metadata = try? JSONDecoder().decode(Record.self, from: data) else { return "群聊邀请" }
        return metadata.name.isEmpty ? "群聊邀请" : metadata.name
    }
    static func canForward(_ message: Record) -> Bool {
        message["message_origin"].string != "call" && !["red_packet", "call"].contains(message["message_type"].string)
    }
    static func forwardBody(_ message: Record) -> [String: JSON] {
        var body: [String: JSON] = ["message_type": message["message_type"], "content": message["content"], "media_url": message["media_url"]]
        if let card = product(message) { body["product_id"] = card["product_id"] }
        if message["share_group_id"].int > 0 { body["share_group_id"] = message["share_group_id"] }
        return body
    }
    static func preview(_ message: Record) -> String {
        if let card = product(message) { return "[商品] " + card.text("name", "product_name") }
        if groupCode(message) != nil { return "[群聊邀请] " + groupTitle(message) }
        return ["image": "[图片]", "video": "[视频]", "voice": "[语音]", "file": "[文件]", "red_packet": "[红包]"][message["message_type"].string] ?? message["content"].string
    }
}

extension Notification.Name {
    static let spxConversationRemoved = Notification.Name("spx.conversation.removed")
    static let spxConversationCleared = Notification.Name("spx.conversation.cleared")
    static let spxConversationUpdated = Notification.Name("spx.conversation.updated")
}
