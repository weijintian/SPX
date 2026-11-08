import XCTest
@testable import SpxChatIOS

final class MessageNotificationTests: XCTestCase {
    func testRouteRequiresRecipientAndConversation() {
        XCTAssertNil(ChatNotificationRoute(payload: ["chat_id": 12]))
        XCTAssertNil(ChatNotificationRoute(payload: ["recipient_user_id": 4, "chat_id": -1]))
        let direct = ChatNotificationRoute(payload: ["recipient_user_id": "4", "chat_id": "12"])
        XCTAssertEqual(direct?.userID, 4); XCTAssertEqual(direct?.targetID, 12); XCTAssertEqual(direct?.group, false)
        let group = ChatNotificationRoute(payload: ["recipient_user_id": 4, "chat_id": 12, "group_id": 8])
        XCTAssertEqual(group?.targetID, 8); XCTAssertEqual(group?.group, true)
    }
    func testUnreadSnapshotAndReadPreserveOtherConversations() {
        var ledger = NotificationUnreadLedger()
        ledger.replace(chats: [Record(["id": 1, "unread_count": 3]), Record(["id": 2, "unread_count": -5])], groups: [Record(["id": 1, "unread_count": 7, "mute": true])])
        XCTAssertEqual(ledger.total, 10)
        XCTAssertTrue(ledger.muted.contains("g-1"))
        ledger.read(target: 1, group: false, remaining: 0)
        XCTAssertEqual(ledger.total, 7)
        ledger.read(target: 1, group: true, remaining: 2)
        XCTAssertEqual(ledger.total, 2)
    }
    func testRepeatedRealtimeEventDoesNotIncreaseBadgeTwice() {
        var ledger = NotificationUnreadLedger()
        XCTAssertTrue(ledger.receive(target: 3, group: false, messageID: 8, visible: false))
        XCTAssertFalse(ledger.receive(target: 3, group: false, messageID: 8, visible: false))
        XCTAssertEqual(ledger.total, 1)
        XCTAssertTrue(ledger.receive(target: 3, group: true, messageID: 8, visible: false))
        XCTAssertEqual(ledger.total, 2)
    }
    func testVisibleAndInvalidEventsDoNotAddUnread() {
        var ledger = NotificationUnreadLedger()
        XCTAssertTrue(ledger.receive(target: 2, group: false, messageID: 5, visible: true))
        XCTAssertFalse(ledger.receive(target: 0, group: false, messageID: 6, visible: false))
        XCTAssertFalse(ledger.receive(target: 2, group: false, messageID: 0, visible: false))
        XCTAssertEqual(ledger.total, 0)
    }
    func testBadgeClampsAndSnapshotsRemoveLeftGroups() {
        var ledger = NotificationUnreadLedger()
        ledger.replace(chats: [Record(["id": 1,"unread_count": .number(Decimal(Int32.max))])], groups: [Record(["id": 2,"unread_count": 10])])
        XCTAssertEqual(ledger.total, Int(Int32.max))
        ledger.replace(chats: [], groups: [])
        XCTAssertEqual(ledger.total, 0)
    }
}
