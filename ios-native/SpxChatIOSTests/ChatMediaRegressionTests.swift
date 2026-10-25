import XCTest
import Combine
@testable import SpxChatIOS

final class ChatMediaRegressionTests: XCTestCase {
    func testUploadEnvelopeAccepts201AndUTF8BOM() throws {
        let url = URL(string: "https://example.test/upload")!
        let response = HTTPURLResponse(url: url, statusCode: 201, httpVersion: nil, headerFields: nil)!
        let body = Data(#"{"code":0,"data":{"url":"https://example.test/video.mp4"}}"#.utf8)
        for data in [body, Data([0xEF, 0xBB, 0xBF]) + body] {
            let result = try APIClient.decodeEnvelope(data, response: response)
            XCTAssertEqual(result["url"].string, "https://example.test/video.mp4")
        }
    }

    func testMalformed201IsRecoverableAndBusinessRejectionIsPreserved() {
        let url = URL(string: "https://example.test/upload")!
        let response = HTTPURLResponse(url: url, statusCode: 201, httpVersion: nil, headerFields: nil)!
        XCTAssertThrowsError(try APIClient.decodeEnvelope(Data("truncated".utf8), response: response)) { error in
            XCTAssertTrue((error as? APIError)?.retryable == true)
        }
        let rejected = Data(#"{"code":422,"message":"文件超过限制","data":null}"#.utf8)
        XCTAssertThrowsError(try APIClient.decodeEnvelope(rejected, response: response)) { error in
            XCTAssertEqual(error.localizedDescription, "文件超过限制")
            XCTAssertFalse((error as? APIError)?.retryable == true)
        }
        let unauthorized = HTTPURLResponse(url: url, statusCode: 401, httpVersion: nil, headerFields: nil)!
        XCTAssertThrowsError(try APIClient.decodeEnvelope(Data(), response: unauthorized)) { error in
            if case APIError.unauthorized = error { } else { XCTFail("401 must require login") }
        }
    }
    func testImageBubbleUsesAndroidAspectBoundsAndFitsNarrowProposals() {
        let cases: [(CGSize, CGFloat, CGSize)] = [
            (CGSize(width: 480, height: 480), 220, CGSize(width: 220, height: 220)),
            (CGSize(width: 400, height: 800), 220, CGSize(width: 150, height: 300)),
            (CGSize(width: 800, height: 400), 220, CGSize(width: 220, height: 110)),
            (CGSize(width: 480, height: 480), 180, CGSize(width: 180, height: 180)),
            (.zero, 220, CGSize(width: 210, height: 160))
        ]
        for (source, maximumWidth, expected) in cases {
            let actual = ChatMediaLayout.imageSize(source, maximumWidth: maximumWidth)
            XCTAssertEqual(actual.width, expected.width, accuracy: 0.000_001)
            XCTAssertEqual(actual.height, expected.height, accuracy: 0.000_001)
        }
    }
    func testUploadResultAcceptsCurrentAndLegacyAddressShapes() {
        let url = "https://media.example.test/image.png"
        let results: [JSON] = [.object(["url": .string(url)]), .object(["media_url": .string(url)]),
                               .object(["data": .object(["url": .string(url)])]),
                               .object(["data": .object(["media_url": .string(url)])])]
        for result in results {
            XCTAssertEqual(UploadedMedia.url(in: result), url)
        }
        XCTAssertEqual(UploadedMedia.url(in: .object(["url": "  "])), "")
        XCTAssertEqual(UploadedMedia.type(in: .object(["message_type": "unknown"])), "")
    }

    func testOptionalStatusFallbackDoesNotBypassAuthOrUploadRestrictions() {
        for status in [404, 405, 500, 501, 502, 503] { XCTAssertTrue(UploadedMedia.supportsStatusFallback(APIError.server(status, "unsupported checkpoint"))) }
        for status in [400, 401, 403, 409, 413, 422, 429] { XCTAssertFalse(UploadedMedia.supportsStatusFallback(APIError.server(status, "failure"))) }
        XCTAssertFalse(UploadedMedia.supportsStatusFallback(APIError.unauthorized))
    }

    @MainActor func testImageAndVideoReceiptsKeepMediaForBothConversationTypes() async throws {
        let url = "https://media.example.test/attachment.mp4"
        for (offset, sample) in [("chat", "image"), ("group", "image"), ("chat", "video"), ("group", "video"), ("chat", "file")].enumerated() {
            let owner = 2_147_479_930 + offset
            let transport = MediaReceiptTransport(result: .object(["media_url": .string(url)]))
            let outbox = Outbox(transport: transport, observeNetwork: false)
            await LocalStore.shared.remove("outbox", userID: owner); await outbox.start(userID: owner)
            let complete = expectation(description: "\(sample.0) \(sample.1) acknowledged")
            var received: SpxChatIOS.Record?
            let subscription = outbox.$acknowledgements.sink { map in
                guard received == nil, let message = map.values.first else { return }
                received = message; complete.fulfill()
            }
            let file = try MediaFiles.directory(userID: owner, component: "attachments").appendingPathComponent(UUID().uuidString + ".bin")
            try Data([1, 2, 3]).write(to: file)
            try await outbox.enqueue(scope: sample.0, id: 9, body: ["content": "附件", "message_type": .string(sample.1)], localURL: file, expectedUserID: owner)
            await fulfillment(of: [complete], timeout: 5)
            XCTAssertEqual(received?["media_url"].string, url)
            XCTAssertEqual(received?["message_type"].string, sample.1)
            let sent = await transport.sent
            XCTAssertEqual(sent.first?["media_url"]?.string, url)
            XCTAssertEqual(sent.first?["message_type"]?.string, sample.1)
            let cached = try await MediaCache.shared.cachedFile(url, userID: owner)
            XCTAssertNotNil(cached)
            if let cached { XCTAssertEqual(try Data(contentsOf: cached), Data([1, 2, 3])) }
            subscription.cancel(); await outbox.stop(userID: owner); await LocalStore.shared.remove("outbox", userID: owner)
            try? await MediaCache.shared.clear(userID: owner); try? FileManager.default.removeItem(at: file)
        }
    }

    @MainActor func testMissingUploadAddressFailsWithoutSendingAndRetainsLocalFile() async throws {
        let owner = 2_147_479_940
        let transport = MediaReceiptTransport(result: .object(["size": 3]))
        let outbox = Outbox(transport: transport, observeNetwork: false)
        await LocalStore.shared.remove("outbox", userID: owner); await outbox.start(userID: owner)
        let failed = expectation(description: "Attachment remains available for retry")
        var failedID: String?
        let subscription = outbox.$items.sink { entries in
            guard failedID == nil, let entry = entries.first(where: { $0.state == .failed }) else { return }
            failedID = entry.id; failed.fulfill()
        }
        let file = try MediaFiles.directory(userID: owner, component: "attachments").appendingPathComponent(UUID().uuidString + ".jpg")
        try Data([1, 2, 3]).write(to: file)
        try await outbox.enqueue(scope: "group", id: 9, body: ["content": "图片", "message_type": "image"], localURL: file, expectedUserID: owner)
        await fulfillment(of: [failed], timeout: 5)
        let sent = await transport.sent
        XCTAssertTrue(sent.isEmpty); XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        XCTAssertTrue(outbox.acknowledgements.isEmpty)
        subscription.cancel()
        if let failedID { await outbox.discard(failedID) }
        await outbox.stop(userID: owner); await LocalStore.shared.remove("outbox", userID: owner)
    }
}

private actor MediaReceiptTransport: OutboxTransport {
    let result: JSON
    private(set) var sent: [[String: JSON]] = []
    init(result: JSON) { self.result = result }
    func upload(file: URL, userID: Int, uploadID: String, progress: @escaping @Sendable (Double) -> Void) async throws -> JSON {
        progress(0.5); progress(1); return result
    }
    func send(scope: String, body: [String: JSON]) async throws -> JSON {
        sent.append(body)
        return .object(["message": .object(["id": 75, "media_url": "", "message_type": ""])])
    }
}
