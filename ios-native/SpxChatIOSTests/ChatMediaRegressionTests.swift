import XCTest
import Combine
import UIKit
@testable import SpxChatIOS

final class ChatMediaRegressionTests: XCTestCase {
    func testSystemNoticesUseMetadataAndCannotBeForwarded() {
        let text = "欢迎新朋友加入群聊"
        let ordinary = SpxChatIOS.Record(["id": 1, "message_type": "text", "content": .string(text)])
        XCTAssertFalse(MessageContent.isSystem(ordinary))
        XCTAssertTrue(MessageContent.canForward(ordinary))
        for metadata in [["message_origin": "system"], ["message_type": "system"], ["message_origin": "SYSTEM"]] {
            var notice = ordinary
            for (key, value) in metadata { notice[key] = .string(value) }
            XCTAssertTrue(MessageContent.isSystem(notice))
            XCTAssertFalse(MessageContent.canForward(notice))
            XCTAssertEqual(MessageContent.preview(notice), text)
        }
    }

    func testUploadProgressDropsBurstsRegressionsAndStaleCallbacks() {
        let gate = UploadProgressGate()
        XCTAssertFalse(gate.accept(.nan, now: 0))
        XCTAssertFalse(gate.accept(.infinity, now: 0))
        XCTAssertTrue(gate.accept(0, now: 0))
        XCTAssertFalse(gate.accept(0.001, now: 0.02))
        XCTAssertFalse(gate.accept(0.5, now: 0.05))
        XCTAssertTrue(gate.accept(0.5, now: 0.11))
        XCTAssertFalse(gate.accept(0.4, now: 0.3))
        XCTAssertFalse(gate.accept(0.505, now: 0.3))
        XCTAssertTrue(gate.accept(1, now: 0.12))
        XCTAssertFalse(gate.accept(1.1, now: 0.4))
        gate.finish()
        XCTAssertFalse(gate.isActive)
        XCTAssertFalse(gate.accept(1, now: 1))
        XCTAssertTrue(UploadProgressGate().accept(0, now: 0))
    }

    func testDownloadProgressRetainsUnknownSizeAndDropsBurstUpdates() {
        let gate = DownloadProgressGate()
        XCTAssertFalse(gate.accept(.nan, now: 0))
        XCTAssertFalse(gate.accept(.infinity, now: 0))
        XCTAssertTrue(gate.accept(-1, now: 0))
        XCTAssertFalse(gate.accept(-1, now: 0.01))
        XCTAssertTrue(gate.accept(0, now: 0.02))
        XCTAssertFalse(gate.accept(-1, now: 0.03))
        XCTAssertFalse(gate.accept(0.5, now: 0.04))
        XCTAssertTrue(gate.accept(0.5, now: 0.13))
        XCTAssertFalse(gate.accept(0.4, now: 0.3))
        XCTAssertFalse(gate.accept(0.505, now: 0.3))
        XCTAssertTrue(gate.accept(1, now: 0.14))
        XCTAssertFalse(gate.accept(1, now: 0.4))
    }

    func testCanceledUploadDoesNotRegisterOrReportProgress() async {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        let operation = Task<JSON, Error> {
            // Cancel deterministically before entering registration; no file or network is needed.
            withUnsafeCurrentTask { $0?.cancel() }
            return try await BackgroundMediaTransfer.shared.upload(
                file: file, userID: 0, uploadID: UUID().uuidString.lowercased(),
                totalBytes: 1, chunkSize: 1, totalChunks: 1, receivedChunks: []
            ) { _ in XCTFail("A canceled upload must not publish progress") }
        }
        do {
            _ = try await operation.value
            XCTFail("An upload canceled before registration must not start")
        } catch is CancellationError {
        } catch {
            XCTFail("Expected cancellation, received \(error)")
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testInvalidPhotoDataFailsBeforeCreatingAnAttachment() {
        XCTAssertThrowsError(try MediaFiles.preparePhotoData(Data("invalid-image".utf8), userID: 2_147_479_950)) { error in
            if case APIError.invalidResponse = error { } else { XCTFail("Invalid photo bytes must be rejected") }
        }
    }

    @MainActor func testLocalThumbnailReusesCacheAndRemainsAccountScoped() async throws {
        let owner = 2_147_479_950
        let cache = ImageThumbnailCache()
        let image = UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16)).image { context in
            UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 16, height: 16))
        }
        let data = try XCTUnwrap(image.pngData())
        let file = try MediaFiles.preparePhotoData(data, userID: owner)
        addTeardownBlock {
            try? await cache.clear(userID: owner)
            try? await cache.clear(userID: owner + 1)
            try? FileManager.default.removeItem(at: file)
        }
        XCTAssertEqual(file.pathExtension, "png")
        XCTAssertEqual(try Data(contentsOf: file), data)
        let first = try await cache.local(file, userID: owner)
        try FileManager.default.removeItem(at: file)
        let reused = try await cache.local(file, userID: owner)
        XCTAssertEqual(first.size, reused.size)
        do {
            _ = try await cache.local(file, userID: owner + 1)
            XCTFail("Another account must not receive a cached private thumbnail")
        } catch { }
        try await cache.clear(userID: owner)
        do {
            _ = try await cache.local(file, userID: owner)
            XCTFail("Cleared thumbnails must not survive a missing source")
        } catch { }
        try await cache.clear(userID: owner + 1)
    }

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
