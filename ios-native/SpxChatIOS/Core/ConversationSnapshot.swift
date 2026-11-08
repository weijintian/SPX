import Foundation

/// The REST cursor and its messages are committed together; realtime arrivals never advance it.
struct ConversationSnapshot: Codable, Equatable {
    var messages: [Record]
    let cursor: Int
    let clearedThrough: Int
}
