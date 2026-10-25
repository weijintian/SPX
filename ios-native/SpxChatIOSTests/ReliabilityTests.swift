import XCTest
import Combine
@testable import SpxChatIOS

final class ReliabilityTests: XCTestCase {
    func testStakingPhaseAndRemainingQuotaUseServerValues() {
        let phase: JSON = .object(["phase_no": 7, "total_quota": "20000000", "reserved_quota": "19500000", "remaining_quota": "500000"])
        XCTAssertEqual(StakingPresentation.phaseTitle(phase), "第 7 期")
        XCTAssertEqual(StakingPresentation.phaseTitle(.null), "暂未开放")
        XCTAssertEqual(StakingPresentation.remainingFraction(phase) ?? -1, 0.025, accuracy: 0.000001)
        XCTAssertEqual(StakingPresentation.remainingFraction(.object(["total_quota": "100", "remaining_quota": "0"])), 0)
        XCTAssertEqual(StakingPresentation.remainingFraction(.object(["total_quota": "100", "remaining_quota": "150"])), 1)
        let invalidPhases: [JSON] = [.null, .object(["total_quota": "0", "remaining_quota": "50"]), .object(["total_quota": "100"]), .object(["total_quota": "100", "remaining_quota": "-1"])]
        for phase in invalidPhases {
            XCTAssertNil(StakingPresentation.remainingFraction(phase))
        }
    }
    func testStakingAmountPresentationKeepsMissingAndDustDistinctFromZero() {
        XCTAssertEqual(StakingPresentation.amount("19541369.869"), "19,541,369.86")
        XCTAssertEqual(StakingPresentation.amount("0"), "0.00")
        XCTAssertEqual(StakingPresentation.amount("0.000000000000000001"), "<0.01")
        for raw in ["", "invalid", "-1"] { XCTAssertEqual(StakingPresentation.amount(raw), "—") }
        XCTAssertEqual(StakingPresentation.rate(nil), "—")
    }
    func testStakingActivePrincipalExcludesRedeemedRecordsAndRejectsMissingAmounts() {
        let active = Record(["id": 1, "status": "active", "amount": "100000"])
        let redeemed = Record(["id": 2, "status": "redeemed", "amount": "900000"])
        XCTAssertEqual(StakingPresentation.activeTotal([active, redeemed]), Decimal(100000))
        XCTAssertEqual(StakingPresentation.activeTotal([]), Decimal.zero)
        XCTAssertNil(StakingPresentation.activeTotal([active, Record(["status": "active"])]))
        XCTAssertNil(StakingPresentation.activeTotal([Record(["status": "active", "amount": "-1"])]))
    }
    func testStakingSubmissionRequiresWholeIncrementAndCompleteSnapshot() {
        let plan = Record(["id": 1, "minimum_amount": "100000", "apy": "60", "plan_days": 90])
        XCTAssertNil(StakingPresentation.validationMessage(amount: "100000", plan: plan, balance: Decimal(100000), activeTotal: 0))
        for amount in ["", "0", "99999", "100001", "100000.0", "0100000", " 100000", "100000 ", "+100000", "1e5", "１０００００", "10100000", String(repeating: "9", count: 40)] {
            XCTAssertNotNil(StakingPresentation.validationMessage(amount: amount, plan: plan, balance: Decimal(10000000), activeTotal: 0), amount)
        }
        XCTAssertNotNil(StakingPresentation.validationMessage(amount: "100000", plan: plan, balance: nil, activeTotal: 0))
        XCTAssertNotNil(StakingPresentation.validationMessage(amount: "100000", plan: plan, balance: Decimal(100000), activeTotal: nil))
        XCTAssertNotNil(StakingPresentation.validationMessage(amount: "100000", plan: nil, balance: Decimal(100000), activeTotal: 0))
    }
    func testStakingSubmissionChecksPlanMinimumBalanceAndCumulativeCap() {
        let plan = Record(["id": 2, "minimum_amount": "200000", "apy": "60", "plan_days": 90])
        XCTAssertNotNil(StakingPresentation.validationMessage(amount: "100000", plan: plan, balance: Decimal(10000000), activeTotal: 0))
        XCTAssertNotNil(StakingPresentation.validationMessage(amount: "200000", plan: plan, balance: Decimal(199999), activeTotal: 0))
        XCTAssertNil(StakingPresentation.validationMessage(amount: "200000", plan: plan, balance: Decimal(200000), activeTotal: Decimal(9800000)))
        XCTAssertNotNil(StakingPresentation.validationMessage(amount: "200000", plan: plan, balance: Decimal(200000), activeTotal: Decimal(9900000)))
    }
    func testStakingIncompletePlansDoNotInventMinimumOrEnableSubmission() {
        let plan = Record(["id": 1, "minimum_amount": "100000", "apy": "60", "plan_days": 90])
        XCTAssertEqual(StakingPresentation.minimum(Record(["minimum_amount": "50000"])), Decimal(100000))
        let invalidMinimums: [JSON] = [.null, "invalid", "-1"]
        for raw in invalidMinimums {
            var incomplete = plan; incomplete["minimum_amount"] = raw
            XCTAssertNil(StakingPresentation.minimum(incomplete))
            XCTAssertNotNil(StakingPresentation.validationMessage(amount: "100000", plan: incomplete, balance: Decimal(100000), activeTotal: 0))
        }
        for key in ["apy", "plan_days"] {
            var incomplete = plan; incomplete[key] = .null
            XCTAssertNotNil(StakingPresentation.validationMessage(amount: "100000", plan: incomplete, balance: Decimal(100000), activeTotal: 0))
            XCTAssertNil(StakingPresentation.expectedReward(amount: "100000", plan: incomplete))
        }
    }
    func testStakingEstimatedDividendUsesAnnualRateAndPlanDays() {
        var plan = Record(["apy": "60", "plan_days": 73])
        XCTAssertEqual(StakingPresentation.expectedReward(amount: "100000", plan: plan), Decimal(12000))
        plan["plan_days"] = 365
        XCTAssertEqual(StakingPresentation.expectedReward(amount: "100000", plan: plan), Decimal(60000))
        plan["apy"] = "36.5"
        XCTAssertEqual(StakingPresentation.expectedReward(amount: "100000", plan: plan, days: 1), Decimal(100))
        XCTAssertEqual(StakingPresentation.expectedReward(amount: "100000", plan: plan, days: 10), Decimal(1000))
        plan["plan_days"] = 7
        XCTAssertEqual(StakingPresentation.expectedReward(amount: "100000", plan: plan, days: 10), Decimal(700))
        XCTAssertNil(StakingPresentation.expectedReward(amount: "100000", plan: plan, days: 0))
        plan["apy"] = 0
        XCTAssertEqual(StakingPresentation.expectedReward(amount: "100000", plan: plan), Decimal.zero)
        XCTAssertNil(StakingPresentation.expectedReward(amount: "invalid", plan: plan))
    }
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
    @MainActor func testCanceledHistoryLoadDoesNotShowAnError() async {
        let model = ConversationStore(target: 77890, group: true, userID: 2_147_479_990, fetch: { _, _ in
            throw CancellationError()
        })
        model.merge([Record(["id": 7])])
        await model.older()
        XCTAssertNil(model.error)
        XCTAssertFalse(model.loadingOlder)
        XCTAssertEqual(model.messages.map(\.id), [7])
    }
    @MainActor func testLateHistoryFailureAfterClearDoesNotRestoreAnError() async {
        let started = expectation(description: "History request started")
        var pending: CheckedContinuation<JSON, Error>?
        let model = ConversationStore(target: 77891, group: true, userID: 2_147_479_991, fetch: { _, _ in
            try await withCheckedThrowingContinuation { continuation in pending = continuation; started.fulfill() }
        })
        model.merge([Record(["id": 7])])
        let operation = Task { await model.older() }
        await fulfillment(of: [started], timeout: 3)
        model.clear()
        pending?.resume(throwing: URLError(.networkConnectionLost)); pending = nil
        await operation.value
        XCTAssertNil(model.error)
        XCTAssertFalse(model.loadingOlder)
        XCTAssertTrue(model.messages.isEmpty)
        await LocalStore.shared.remove(model.cacheKey + "-sync-v2", userID: model.userID)
    }
    @MainActor func testCurrentHistoryFailureStillShowsAnError() async {
        let model = ConversationStore(target: 77892, group: true, userID: 2_147_479_992, fetch: { _, _ in
            throw URLError(.networkConnectionLost)
        })
        model.merge([Record(["id": 7])])
        await model.older()
        XCTAssertNotNil(model.error)
        XCTAssertFalse(model.loadingOlder)
        XCTAssertEqual(model.messages.map(\.id), [7])
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
    @MainActor func testReopenedConversationUsesRetainedMessagesAndIncrementalCursor() async throws {
        let owner = 2_147_479_975, target = 77875
        let key = "messages-g-\(target)-sync-v2"
        try await LocalStore.shared.save(ConversationSnapshot(messages: [Record(["id": 10])], cursor: 10, clearedThrough: 0), key: key, userID: owner)
        let firstSynced = expectation(description: "First view synced")
        let reopened = expectation(description: "Reopened view requests only later messages")
        var cursors: [String] = []
        let model = ConversationStore(target: target, group: true, userID: owner, fetch: { action, query in
            if action.hasSuffix(".reconcile") { firstSynced.fulfill(); return .object(["messages": .array([]), "removed_ids": .array([])]) }
            cursors.append(query["after_id"] ?? "initial")
            if cursors.count == 2 { reopened.fulfill() }
            return .array([.object(["id": 11])])
        }, post: { _, _ in .null })
        let first = Task { await model.run() }
        await fulfillment(of: [firstSynced], timeout: 3)
        first.cancel(); await first.value
        XCTAssertFalse(model.initialLoading)
        XCTAssertEqual(model.messages.map(\.id), [10, 11])
        let second = Task { await model.run() }
        await fulfillment(of: [reopened], timeout: 3)
        second.cancel(); await second.value
        XCTAssertEqual(Array(cursors.prefix(2)), ["10", "11"])
        XCTAssertFalse(model.initialLoading)
        XCTAssertEqual(model.messages.map(\.id), [10, 11])
        await LocalStore.shared.remove(key, userID: owner)
    }
    @MainActor func testReopenedConversationTakesOwnershipOfCanceledFetch() async throws {
        let owner = 2_147_479_976, target = 77876
        let key = "messages-g-\(target)-sync-v2"
        try await LocalStore.shared.save(ConversationSnapshot(messages: [Record(["id": 10])], cursor: 10, clearedThrough: 0), key: key, userID: owner)
        let oldStarted = expectation(description: "Old view request started")
        let newSynced = expectation(description: "New view synced before old request finishes")
        var pending: CheckedContinuation<JSON, Never>?
        var requests = 0
        let model = ConversationStore(target: target, group: true, userID: owner, fetch: { action, query in
            if action.hasSuffix(".reconcile") { newSynced.fulfill(); return .object(["messages": .array([]), "removed_ids": .array([])]) }
            requests += 1
            XCTAssertEqual(query["after_id"], "10")
            if requests == 1 {
                return await withCheckedContinuation { continuation in pending = continuation; oldStarted.fulfill() }
            }
            return .array([.object(["id": 11])])
        }, post: { _, _ in .null })
        let old = Task { await model.run() }
        await fulfillment(of: [oldStarted], timeout: 3)
        old.cancel()
        let current = Task { await model.run() }
        await fulfillment(of: [newSynced], timeout: 3)
        pending?.resume(returning: .array([.object(["id": 99])]))
        pending = nil
        await old.value
        XCTAssertEqual(model.messages.map(\.id), [10, 11], "Canceled predecessor must not merge late history")
        XCTAssertNil(model.error)
        XCTAssertFalse(model.initialLoading)
        current.cancel(); await current.value
        await LocalStore.shared.remove(key, userID: owner)
    }
    @MainActor func testConversationRegistryRetainsAndReleasesStores() {
        ConversationStore.releaseCached()
        let first = ConversationStore.cached(target: 77877, group: true, userID: 2_147_479_977)
        let again = ConversationStore.cached(target: 77877, group: true, userID: 2_147_479_977)
        let direct = ConversationStore.cached(target: 77877, group: false, userID: 2_147_479_977)
        XCTAssertTrue(first === again)
        XCTAssertFalse(first === direct)
        first.merge([Record(["id": 10])])
        XCTAssertEqual(again.messages.map(\.id), [10])
        ConversationStore.releaseCached()
        let afterLogout = ConversationStore.cached(target: 77877, group: true, userID: 2_147_479_977)
        XCTAssertFalse(first === afterLogout)
        XCTAssertTrue(afterLogout.messages.isEmpty)
        ConversationStore.releaseCached()
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
