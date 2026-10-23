import XCTest
@testable import SpxChatIOS

final class CoreTests: XCTestCase {
    /// Integration probe: no credentials or user data; exercises iOS TLS against the actual login host.
    func testBundledServerCertificateReachesAPIWithoutCredentials() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20
        let session = URLSession(configuration: configuration, delegate: ServerTrustDelegate.shared, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let url = URL(string: "https://70.39.201.191/spxchat/api.php?action=me")!
        let (data, response) = try await session.data(from: url)
        let http = try XCTUnwrap(response as? HTTPURLResponse)
        XCTAssertEqual(http.statusCode, 401, "The protected endpoint should reject an anonymous request after TLS succeeds")
        XCTAssertNoThrow(try JSONDecoder().decode(APIEnvelope.self, from: data))
    }

    func testAuthenticationRequiresBothCredentialAndValidAccount() throws {
        let account = try AuthenticatedAccount(.object(["token": "test-session", "user": .object(["id": 42, "nickname": "Tester"])]))
        XCTAssertEqual(account.token, "test-session")
        XCTAssertEqual(account.user.id, 42)
        for result in [JSON.null, .object(["token": "test-session"]),
                             .object(["token": " ", "user": .object(["id": 42])]),
                             .object(["token": "test-session", "user": .object(["id": 0])])] {
            XCTAssertThrowsError(try AuthenticatedAccount(result))
        }
    }
    func testReapplyingSameCredentialDoesNotInvalidateCurrentRequests() async {
        let api = APIClient()
        await api.setToken("first")
        let original = await api.sessionIdentity()
        await api.setToken("first")
        let unchanged = await api.sessionIdentity()
        XCTAssertEqual(original, unchanged)
        await api.setToken("second")
        let changed = await api.sessionIdentity()
        XCTAssertNotEqual(original, changed)
        await api.setToken(nil)
        let loggedOut = await api.sessionIdentity()
        XCTAssertNotEqual(changed, loggedOut)
    }
    func testKeychainCanReplaceAndRemoveOnlyItsOwnTestCredential() throws {
        let account = "test-session-" + UUID().uuidString
        defer { try? Keychain.write(nil, account: account) }
        try Keychain.write("old", account: account)
        XCTAssertEqual(Keychain.read(account), "old")
        try Keychain.write("new", account: account)
        XCTAssertEqual(Keychain.read(account), "new")
        try Keychain.write(nil, account: account)
        XCTAssertNil(Keychain.read(account))
        XCTAssertNoThrow(try Keychain.write(nil, account: account))
    }
    @MainActor func testOutboxRejectsAttachmentsFromPreviousAccountBeforeEnqueue() async {
        let outbox = Outbox()
        let owner = 2_147_480_000
        await outbox.start(userID: owner)
        do {
            try await outbox.enqueue(scope: "chat", id: 1, body: ["message_type": "image"],
                                     localURL: URL(fileURLWithPath: "/tmp/previous-account.jpg"), expectedUserID: owner - 1)
            XCTFail("A stale media selection must not enter the new account's queue")
        } catch APIError.staleSession { }
        catch { XCTFail("Unexpected error: " + error.localizedDescription) }
        XCTAssertTrue(outbox.items.isEmpty)
        await outbox.stop(userID: owner)
        await LocalStore.shared.remove("outbox", userID: owner)
    }

    func testAuthenticationTransportErrorsRemainDistinctFromAccountErrors() {
        let cancelled = AuthenticationErrorMessage.describe(URLError(.cancelled))
        let untrusted = AuthenticationErrorMessage.describe(URLError(.serverCertificateUntrusted))
        let offline = AuthenticationErrorMessage.describe(URLError(.notConnectedToInternet))
        XCTAssertTrue(cancelled.contains("-999"))
        XCTAssertTrue(untrusted.contains("-1202"))
        XCTAssertTrue(untrusted.contains("证书"))
        XCTAssertTrue(offline.contains("网络不可用"))
        XCTAssertNotEqual(cancelled, untrusted)
        XCTAssertEqual(AuthenticationErrorMessage.describe(APIError.server(422, "用户名或密码错误")), "用户名或密码错误")
        XCTAssertTrue(AuthenticationErrorMessage.describe(URLError(.serverCertificateNotYetValid)).contains("手机日期"))
        XCTAssertEqual(AuthenticationErrorMessage.describe(CancellationError()), "操作已取消，请重试")
    }

    func testGroupRemarksRenameExistingMessageAndQuoteWithoutChangingIdentity() {
        let source = Record(["id": 100, "sender_id": 2, "nickname": "Bob", "content": "hello", "reply": .object(["id": 90, "sender_id": 2, "nickname": "Bob", "preview": "earlier"])])
        let renamed = source.applyingGroupRemark(peerID: 2, remark: "Friend B")
        XCTAssertEqual(renamed.id, 100)
        XCTAssertEqual(renamed["sender_id"].int, 2)
        XCTAssertEqual(renamed.name, "Friend B")
        XCTAssertEqual(renamed["reply"]["nickname"].string, "Friend B")
        XCTAssertEqual(renamed["content"], source["content"])
        XCTAssertEqual(source["nickname"].string, "Bob")
        let cleared = renamed.applyingGroupRemark(peerID: 2, remark: "")
        XCTAssertEqual(cleared.name, "Bob")
        XCTAssertEqual(cleared["reply"]["nickname"].string, "Bob")
        XCTAssertEqual(source.applyingGroupRemark(peerID: 3, remark: "Other"), source)
    }
    @MainActor func testMemberAndEveryoneMentionDetection() {
        XCTAssertTrue(ChatSoundEffects.mentioned(Record(["mention_user_ids": .array([2])]), userID: 2))
        XCTAssertTrue(ChatSoundEffects.mentioned(Record(["mention_user_ids": .array([-1])]), userID: 3))
        XCTAssertFalse(ChatSoundEffects.mentioned(Record(["mention_user_ids": .array([2])]), userID: 3))
        XCTAssertFalse(ChatSoundEffects.mentioned(Record(), userID: 3))
    }
    func testWithdrawalFeeTruncatesLikeServer() {
        let amount = Decimal(string: "1.000000000000000099")!
        XCTAssertEqual(Money.withdrawalFee(amount), Decimal(string: "0.01")!)
        XCTAssertEqual(amount - Money.withdrawalFee(amount), Decimal(string: "0.990000000000000099")!)
        XCTAssertEqual(Money.withdrawalFee(Decimal(string: "0.000000000000000001")!), 0)
    }
    func testTrustedDeepLinksRejectUnsupportedTargets() {
        XCTAssertEqual(AppLink.parse(URL(string: "spxchat://group?code=ab12cd")!), .group("AB12CD"))
        XCTAssertEqual(AppLink.parse(URL(string: "https://spx001.top/spxchat/product-share.php?id=42")!), .product(42))
        for value in ["https://example.com/spxchat/product-share.php?id=42", "spxchat://product?id=-1", "spxchat://group?code=中文1234", "spxchat://group?code=TOOLONG", "spxchat://user?id=bad%20id"] {
            XCTAssertNil(AppLink.parse(URL(string: value)!), value)
        }
    }
    func testMapConversionRoundTripAndOutsideChina() {
        let wgs = MapCoordinate(latitude: 39.908823, longitude: 116.397470)
        let gcj = MapCoordinates.toGCJ(wgs, system: "wgs84")
        XCTAssertGreaterThan(abs(gcj.longitude - wgs.longitude), 0.001)
        let recovered = MapCoordinates.toWGS(gcj, system: "gcj02")
        XCTAssertEqual(recovered.latitude, wgs.latitude, accuracy: 0.000001)
        XCTAssertEqual(recovered.longitude, wgs.longitude, accuracy: 0.000001)
        let london = MapCoordinate(latitude: 51.5074, longitude: -0.1278)
        XCTAssertEqual(MapCoordinates.toGCJ(london, system: "wgs84"), london)
    }
    func testSharedCardsAndForwardingPreserveContracts() {
        let card = Record(["message_type": "product", "content": .string(#"{"product_id":42,"name":"商品","cny_price":"168"}"#)])
        XCTAssertEqual(MessageContent.product(card)?["product_id"].int, 42)
        XCTAssertEqual(MessageContent.forwardBody(card)["product_id"]?.int, 42)
        XCTAssertEqual(MessageContent.groupCode(Record(["content": "SPXGROUP:ab12cd"])), "AB12CD")
        XCTAssertNil(MessageContent.groupCode(Record(["content": "SPXGROUP:中文1234"])))
        XCTAssertFalse(MessageContent.canForward(Record(["message_type": "red_packet"])))
        XCTAssertFalse(MessageContent.canForward(Record(["message_type": "text", "message_origin": "call"])))
    }
    func testTimelineUsesFiveMinuteBoundaries() {
        let a = Record(["id": 1, "created_at": "2026-10-22 12:00:00"])
        let b = Record(["id": 2, "created_at": "2026-10-22 12:04:59"])
        let c = Record(["id": 3, "created_at": "2026-10-22 12:09:59"])
        XCTAssertTrue(MessageTimeline.showsTime(before: a, in: [a,b,c]))
        XCTAssertFalse(MessageTimeline.showsTime(before: b, in: [a,b,c]))
        XCTAssertTrue(MessageTimeline.showsTime(before: c, in: [a,b,c]))
    }
    func testDecimalJSONRoundTripKeepsWalletPrecision() throws {
        let raw = Data(#"{"amount":"123456789.123456789123456789","id":42,"enabled":1}"#.utf8)
        let record = try JSONDecoder().decode(Record.self, from: raw)
        XCTAssertEqual(record.id, 42)
        XCTAssertEqual(record["amount"].string, "123456789.123456789123456789")
        XCTAssertTrue(record["enabled"].flag)
        XCTAssertEqual(try JSONDecoder().decode(Record.self, from: JSONEncoder().encode(record)), record)
    }
    func testFixedCNYAndInverseSPXValuation() {
        XCTAssertEqual(Money.estimate(cny: "168", usd: "0.1"), "250.746269")
        XCTAssertEqual(Money.estimate(cny: "168", usd: "0.2"), "125.373134")
        XCTAssertNil(Money.estimate(cny: "168", usd: "0"))
        XCTAssertNil(Money.estimate(cny: "-1", usd: "1"))
    }
    func testAmountInputRejectsScientificAndAmbiguousFormats() {
        for value in ["-1", "1e6", "1,000", ".5", "1.", " 1", "NaN", "0", "1.0000001"] { XCTAssertFalse(Money.positive(value, scale: 6), value) }
        XCTAssertTrue(Money.positive("0.000001", scale: 6))
    }
    func testEnvelopeAcceptsCreatedAndRejectsBusinessFailure() async throws {
        let api = APIClient()
        let url = URL(string: "https://example.com/api.php")!
        let created = HTTPURLResponse(url: url, statusCode: 201, httpVersion: nil, headerFields: nil)!
        let value = try await api.decode(Data(#"{"code":0,"data":{"id":7}}"#.utf8), response: created)
        XCTAssertEqual(value["id"].int, 7)
        do { _ = try await api.decode(Data(#"{"code":422,"message":"invalid"}"#.utf8), response: created); XCTFail("Must reject nonzero envelope code") } catch { }
    }
    func testMultipartPreservesBinaryPayloadAndBoundaries() {
        let payload = Data([0, 255, 13, 10, 128])
        let body = Multipart.body(boundary: "unit-boundary", fields: ["chunk_index": "2"], data: payload, name: "chunk", filename: "part.bin", mime: "application/octet-stream")
        XCTAssertNotNil(body.range(of: payload))
        let ending = Data([45, 45] + Array("unit-boundary--".utf8) + [13, 10])
        XCTAssertTrue(body.suffix(ending.count).elementsEqual(ending))
    }
    func testPendingMessageKeepsStableRetryIdentifiers() throws {
        let message = PendingMessage(id: "client-12345678", userID: 9, scope: "group", targetID: 3, body: ["content": "hello"], localURL: URL(fileURLWithPath: "/tmp/video.mp4"), uploadID: "upload-12345678", createdAt: Date())
        let restored = try JSONDecoder().decode(PendingMessage.self, from: JSONEncoder().encode(message))
        XCTAssertEqual(restored.id, message.id)
        XCTAssertEqual(restored.uploadID, message.uploadID)
        XCTAssertEqual(restored.localURL, message.localURL)
    }
    func testVideoContentRangeValidatesBoundsAndRequestedSlice() {
        let range = HTTPContentRange("bytes 1048576-2097151/8388608")
        XCTAssertEqual(range?.count, 1_048_576)
        XCTAssertTrue(range?.matches(offset: 1_048_576, maximumCount: 1_048_576) == true)
        XCTAssertFalse(range?.matches(offset: 0, maximumCount: 1_048_576) == true)
        XCTAssertFalse(range?.matches(offset: 1_048_576, maximumCount: 512) == true)
        XCTAssertEqual(HTTPContentRange("bytes 9-9/10")?.count, 1)
        for malformed in ["items 0-1/2", "bytes */123", "bytes 4-3/9", "bytes 0-9/9", "bytes -1-1/10", "bytes 0-1/*", "bytes 0-1/0", "bytes 0-999999999999999999999/10"] {
            XCTAssertNil(HTTPContentRange(malformed), malformed)
        }
    }
    func testFileBackedMultipartReadsOnlyRequestedChunk() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("source.bin")
        let target = directory.appendingPathComponent("chunk.multipart")
        var data = Data(repeating: 42, count: 1_048_576)
        let tail = Data([0, 255, 128, 13, 10])
        data.append(tail)
        try data.write(to: source)
        let uploadID = UUID().uuidString.lowercased()
        let boundary = try Multipart.writeChunk(from: source, to: target, index: 1, chunkSize: 1_048_576,
                                                 totalBytes: data.count, uploadID: uploadID, totalChunks: 2)
        let body = try Data(contentsOf: target)
        XCTAssertLessThan(body.count, 2_048)
        XCTAssertNotNil(body.range(of: tail))
        XCTAssertNotNil(body.range(of: Data(uploadID.utf8)))
        XCTAssertTrue(body.suffix(Data("\r\n--\(boundary)--\r\n".utf8).count)
            .elementsEqual(Data("\r\n--\(boundary)--\r\n".utf8)))
    }
    @MainActor func testPagedRecordsDeduplicateStringIDsAndKeepIdlessAuditEvents() async throws {
        let state = PageState()
        let packet = Record(["id": .string("0123456789abcdef0123456789abcdef"), "status": .string("active")])
        let audit = Record(["action": .string("apply"), "created_at": .string("2026-10-22 12:00:00")])
        try await state.load(reset: true) { _ in [packet, audit, audit] }
        try await state.load(reset: false, pageSize: 3) { _ in [packet, Record(["id": .string("abcdef0123456789abcdef0123456789")]), audit] }
        XCTAssertEqual(state.rows.count, 5)
        XCTAssertEqual(state.rows.filter { $0["action"].string == "apply" }.count, 3)
        XCTAssertEqual(state.rows.filter { $0["id"].string.count == 32 }.count, 2)
    }
}
