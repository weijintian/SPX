import XCTest
import Combine
@testable import SpxChatIOS

final class ReliabilityTests: XCTestCase {
    func testContactsUseRemarksAndAllowClearingThem() {
        let contact = Record(["id": 8, "nickname": "原昵称", "remark": "  客户小张  "])
        XCTAssertEqual(contact.normalizedContact.name, "客户小张")
        var cleared = contact.normalizedContact
        cleared["remark"] = ""
        XCTAssertEqual(cleared.normalizedContact.name, "原昵称")
        for placeholder in ["null", "undefined", " "] {
            XCTAssertEqual(Record(["nickname": "原昵称", "remark": .string(placeholder)]).normalizedContact.name, "原昵称")
        }
        XCTAssertEqual(Record(["nickname": "商品名", "remark": "售后说明"]).name, "商品名", "Generic records must not reinterpret business remarks")
    }
    func testDeletingPrefixMentionKeepsOnlyLongerSelectedRecipient() {
        let old = "@张三 @张三丰 "
        let tokens = [MentionToken(userID: 2, name: "张三", location: 0), MentionToken(userID: 3, name: "张三丰", location: 4)]
        let new = "@张三丰 "
        let updated = MentionRanges.updating(tokens, from: old, to: new)
        XCTAssertEqual(MentionRanges.userIDs(updated, in: new), [3])
    }
    func testMentionEditsBeforeEmojiAndInsideNameAreSafe() {
        let old = "😀 @Alice "
        let tokens = [MentionToken(userID: 5, name: "Alice", location: 3)]
        let prefixed = "你好 " + old
        let updated = MentionRanges.updating(tokens, from: old, to: prefixed)
        XCTAssertEqual(updated.first?.location, 6)
        XCTAssertEqual(MentionRanges.userIDs(updated, in: prefixed), [5])
        let edited = prefixed.replacingOccurrences(of: "Alice", with: "Alicia")
        XCTAssertTrue(MentionRanges.updating(updated, from: prefixed, to: edited).isEmpty)
    }
    func testAmbiguousSameNameDeletionDoesNotNotifyWrongPerson() {
        let old = "@同名 @同名 "
        let tokens = [MentionToken(userID: 2, name: "同名", location: 0), MentionToken(userID: 3, name: "同名", location: 4)]
        XCTAssertTrue(MentionRanges.updating(tokens, from: old, to: "@同名 ").isEmpty)
        let removed = MentionRanges.removing(userID: 2, tokens: tokens, from: old)
        XCTAssertEqual(removed.text, "@同名 ")
        // Explicit chip removal knows the selected user; preserve the other token.
        XCTAssertEqual(MentionRanges.userIDs(removed.tokens, in: removed.text), [3])
    }
    func testMentionDraftRoundTripEveryoneAndMalformedRanges() throws {
        let tokens = [MentionToken(userID: -1, name: "全体成员", location: 0)]
        let data = try JSONEncoder().encode(JSON.array(tokens.map(\.json)))
        let restored = try JSONDecoder().decode([MentionToken].self, from: data)
        XCTAssertEqual(MentionRanges.userIDs(restored, in: "@全体成员 请查看公告"), [-1])
        let malformed = MentionToken(userID: 2, name: "A", location: Int.max)
        XCTAssertTrue(MentionRanges.validated([malformed], in: "@A").isEmpty)
        XCTAssertTrue(MentionRanges.restoringLegacy([2: "同名", 3: "同名"], in: "@同名 ").isEmpty)
    }
    func testOrderAddressesDecodeBothCurrentAndLegacyShapes() {
        let encoded = Record(["shipping_address": .string(#"{"name":"张三","phone":"13800000000","province":"广东","city":"深圳","detail":"测试路1号"}"#)])
        let address = OrderPresentation.address(encoded)
        XCTAssertEqual(address["name"].string, "张三")
        XCTAssertEqual(OrderPresentation.addressLine(address), "广东 深圳 测试路1号")
        XCTAssertEqual(OrderPresentation.address(Record(["address": .object(["name": "李四"]) ]))["name"].string, "李四")
        XCTAssertTrue(OrderPresentation.address(Record()).fields.isEmpty)
    }
    func testMerchantOrderReaderFindsShiftedPageWithoutBuyerEndpoint() async throws {
        var pages: [Int] = []
        let found = try await MerchantOrderReader.find(id: 8, startingPage: 1, pageSize: 3) { page in
            pages.append(page)
            return page == 1 ? [12, 11, 10].map { Record(["id": .number(Decimal($0))]) } : [9, 8, 7].map { Record(["id": .number(Decimal($0)), "status": "shipped"]) }
        }
        XCTAssertEqual(pages, [1, 2])
        XCTAssertEqual(found.order["status"].string, "shipped")
        XCTAssertEqual(found.page, 2)
    }
    func testMerchantOrderReaderStopsForRemovedOrder() async {
        do {
            _ = try await MerchantOrderReader.find(id: 8, startingPage: 1, pageSize: 3) { _ in [9, 7, 6].map { Record(["id": .number(Decimal($0))]) } }
            XCTFail("An interior missing ID must not generate unbounded paging")
        } catch APIError.server(let code, _) { XCTAssertEqual(code, 404) }
        catch { XCTFail(error.localizedDescription) }
    }
    func testFinancialMutationCoverageAndChineseLabels() {
        for action in ["redpackets.claim", "redpackets.create", "wallet.exchange", "wallet.withdraw.cancel", "staking.redeem", "orders.redeem", "merchant.deposit.pay", "financial.resolve"] { XCTAssertTrue(AccountMutation.affectsWallet(action), action) }
        for action in ["messages.send", "groups.messages.send", "calls.update", "presence.update"] { XCTAssertFalse(AccountMutation.affectsWallet(action), action) }
        XCTAssertTrue(AccountMutation.affectsOrders("merchant.order.deliver"))
        XCTAssertFalse(AccountMutation.affectsOrders("messages.send"))
        XCTAssertEqual(DisplayLabels.transaction("exchange_out"), "兑换支出")
        XCTAssertEqual(DisplayLabels.transaction("exchange_in"), "兑换到账")
        XCTAssertEqual(DisplayLabels.afterSaleType("return_refund"), "退货退款")
    }
    func testVideoChunkAlignmentAtSeekAndTailBoundaries() {
        for (offset, expected) in [(0, 0), (17, 0), (1_048_575, 0), (1_048_576, 1_048_576), (2_097_151, 1_048_576)] { XCTAssertEqual(VideoRangeCache.alignedOffset(offset), expected) }
    }
    @MainActor func testUnchangedMessageMergeDoesNotPublishAgain() {
        let model = ConversationStore(target: 1, group: true, userID: 1)
        let message = Record(["id": 7, "content": "hello", "created_at": "2026-10-24 10:00:00"])
        model.merge([message])
        var publishes = 0
        let subscription = model.$messages.dropFirst().sink { _ in publishes += 1 }
        model.merge([]); model.merge([message])
        XCTAssertEqual(publishes, 0)
        var changed = message; changed["content"] = "edited"; model.merge([changed])
        XCTAssertEqual(publishes, 1)
        XCTAssertTrue(model.timeBoundaryIDs.contains(7))
        withExtendedLifetime(subscription) { }
    }
    @MainActor func testRealtimeArrivalDoesNotSkipRESTCatchupPages() async throws {
        let owner = 2_147_479_981, target = 77881
        let key = "messages-g-\(target)-sync-v2"
        let snapshot = ConversationSnapshot(messages: [Record(["id": 200])], cursor: 200, clearedThrough: 0)
        try await LocalStore.shared.save(snapshot, key: key, userID: owner)
        var cursors: [String] = []
        let caughtUp = expectation(description: "Read receipt only after full catchup")
        var acknowledgedCatchup = false
        let model = ConversationStore(target: target, group: true, userID: owner, fetch: { action, query in
            if action.hasSuffix(".reconcile") { return .object(["messages": .array([]), "removed_ids": .array([])]) }
            let cursor = query["after_id"] ?? "initial"; cursors.append(cursor)
            let ids = cursor == "200" ? Array(201...300) : cursor == "300" ? Array(301...350) : []
            return .array(ids.map { .object(["id": .number(Decimal($0))]) })
        }, post: { _, body in
            let boundary = body["through_id"]?.int ?? 0
            XCTAssertTrue([200, 300, 350].contains(boundary), "Receipts must follow a contiguous REST boundary")
            if boundary == 350, !acknowledgedCatchup {
                acknowledgedCatchup = true
                XCTAssertEqual(Array(cursors.prefix(2)), ["200", "300"])
                caughtUp.fulfill()
            }
            return .null
        })
        model.receive(.object(["type": "group.message", "group_id": .number(Decimal(target)), "message": .object(["id": 350])]))
        model.setReadVisibility(true)
        let task = Task { await model.run() }
        await fulfillment(of: [caughtUp], timeout: 5)
        task.cancel(); await task.value
        XCTAssertEqual(Array(cursors.prefix(2)), ["200", "300"])
        XCTAssertEqual(model.messages.map(\.id), Array(200...350))
        let persisted: ConversationSnapshot? = await LocalStore.shared.read(key, userID: owner)
        XCTAssertEqual(persisted?.cursor, 350)
        await LocalStore.shared.remove(key, userID: owner)
    }
    @MainActor func testClearingDuringCatchupDoesNotStopSubsequentSync() async throws {
        let owner = 2_147_479_985, target = 77885
        let key = "messages-g-\(target)-sync-v2"
        try await LocalStore.shared.save(ConversationSnapshot(messages: [Record(["id": 10])], cursor: 10, clearedThrough: 0), key: key, userID: owner)
        let fetching = expectation(description: "First REST request is in flight")
        let recovered = expectation(description: "Sync continues after local history clear")
        var requests = 0
        var release: CheckedContinuation<JSON, Never>?
        let model = ConversationStore(target: target, group: true, userID: owner, fetch: { action, _ in
            if action.hasSuffix(".reconcile") { return .object(["messages": .array([]), "removed_ids": .array([])]) }
            requests += 1
            if requests == 1 { return await withCheckedContinuation { continuation in release = continuation; fetching.fulfill() } }
            return .array([.object(["id": 11])])
        }, post: { _, body in
            let boundary = body["through_id"]?.int ?? 0
            XCTAssertTrue([10, 11].contains(boundary))
            if boundary == 11 { recovered.fulfill() }
            return .null
        })
        model.setReadVisibility(true)
        let task = Task { await model.run() }
        await fulfillment(of: [fetching], timeout: 5)
        model.clear()
        release?.resume(returning: .array([.object(["id": 11])]))
        await fulfillment(of: [recovered], timeout: 5)
        task.cancel(); await task.value
        XCTAssertGreaterThanOrEqual(requests, 2)
        XCTAssertEqual(model.messages.map(\.id), [11])
        await LocalStore.shared.remove(key, userID: owner)
    }
    @MainActor func testSyncedMessagesStayUnreadUntilLatestMessagesAreVisible() async throws {
        let owner = 2_147_479_987, target = 77887
        let key = "messages-g-\(target)-sync-v2"
        try await LocalStore.shared.save(ConversationSnapshot(messages: [Record(["id": 10])], cursor: 10, clearedThrough: 0), key: key, userID: owner)
        let synced = expectation(description: "Messages merged while reading history")
        let read = expectation(description: "Read only after latest messages become visible")
        var receipts = 0
        var reconciled = false
        let model = ConversationStore(target: target, group: true, userID: owner, fetch: { action, _ in
            if action.hasSuffix(".reconcile") {
                if !reconciled { reconciled = true; synced.fulfill() }
                return .object(["messages": .array([]), "removed_ids": .array([])])
            }
            return .array([.object(["id": 11])])
        }, post: { _, body in
            receipts += 1
            XCTAssertEqual(body["through_id"]?.int, 11)
            read.fulfill()
            return .null
        })
        let task = Task { await model.run() }
        await fulfillment(of: [synced], timeout: 5)
        XCTAssertEqual(model.messages.map(\.id), [10, 11])
        XCTAssertEqual(receipts, 0, "Background sync must not acknowledge unseen messages")
        model.setReadVisibility(true)
        await fulfillment(of: [read], timeout: 5)
        model.setReadVisibility(false)
        task.cancel(); await task.value
        XCTAssertEqual(receipts, 1)
        await LocalStore.shared.remove(key, userID: owner)
    }
    @MainActor func testQueuedLargeUploadDoesNotBlockTextDelivery() async throws {
        let owner = 2_147_479_982
        let started = expectation(description: "Upload waits for controlled completion")
        let textSent = expectation(description: "Text reaches independent delivery lane")
        let transport = ControlledOutboxTransport(started: started, textSent: textSent)
        let outbox = Outbox(transport: transport, observeNetwork: false)
        await outbox.start(userID: owner)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        try Data([1, 2, 3]).write(to: file)
        try await outbox.enqueue(scope: "chat", id: 1, body: ["content": "视频", "message_type": "video"], localURL: file, expectedUserID: owner)
        await fulfillment(of: [started], timeout: 5)
        try await outbox.enqueue(scope: "chat", id: 2, body: ["content": "先发文字", "message_type": "text"], expectedUserID: owner)
        await fulfillment(of: [textSent], timeout: 5)
        XCTAssertTrue(outbox.items.contains { $0.localURL != nil && $0.state == .uploading })
        await transport.releaseUpload()
        await outbox.stop(userID: owner)
        await LocalStore.shared.remove("outbox", userID: owner)
        try? FileManager.default.removeItem(at: file)
    }
}

private actor ControlledOutboxTransport: OutboxTransport {
    let started: XCTestExpectation
    let textSent: XCTestExpectation
    private var uploadContinuation: CheckedContinuation<JSON, Error>?
    init(started: XCTestExpectation, textSent: XCTestExpectation) { self.started = started; self.textSent = textSent }
    func upload(file: URL, userID: Int, uploadID: String, progress: @escaping @Sendable (Double) -> Void) async throws -> JSON {
        try await withCheckedThrowingContinuation { continuation in uploadContinuation = continuation; started.fulfill() }
    }
    func send(scope: String, body: [String: JSON]) async throws -> JSON {
        if body["message_type"]?.string == "text" { textSent.fulfill() }
        return .object(["message": .object(["id": 100, "chat_id": body["chat_id"] ?? .null])])
    }
    func releaseUpload() { uploadContinuation?.resume(returning: .object(["url": "https://example.com/video.mp4", "message_type": "video"])); uploadContinuation = nil }
}
