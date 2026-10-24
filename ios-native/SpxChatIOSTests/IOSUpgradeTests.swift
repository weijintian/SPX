import XCTest
import Combine
@testable import SpxChatIOS

final class IOSUpgradeTests: XCTestCase {
    func testPendingMediaAndIncomingTextKeepChronologicalOrder() {
        let pending = PendingMessage(id: "local", userID: 1, scope: "chat", targetID: 9, body: [:], uploadID: "upload", createdAt: Date(timeIntervalSince1970: 100))
        let message = Record(["id": 10, "sender_id": 2, "created_at": "1970-01-01T00:03:20Z"])
        let rows = ChatTimelineEntry.merge(messages: [message], pending: [pending], userID: 1)
        XCTAssertEqual(rows.map(\.id), ["c-1-local", "m-10"])
    }
    func testMediaAcknowledgementKeepsStableIdentityAndNoDuplicateBubble() {
        let pending = PendingMessage(id: "local", userID: 1, scope: "group", targetID: 9, body: [:], uploadID: "upload", createdAt: Date(timeIntervalSince1970: 100))
        let message = Record(["id": 10, "sender_id": 1, "client_message_id": "local", "created_at": "1970-01-01T00:03:20Z"])
        let rows = ChatTimelineEntry.merge(messages: [message], pending: [pending], userID: 1)
        XCTAssertEqual(rows.count, 1); XCTAssertEqual(rows[0].id, "c-1-local")
        XCTAssertEqual(rows[0].date, pending.createdAt)
    }
    func testSameClientIDFromOtherSenderDoesNotHidePendingMessage() {
        let pending = PendingMessage(id: "local", userID: 1, scope: "group", targetID: 9, body: [:], uploadID: "upload", createdAt: Date())
        let other = Record(["id": 10, "sender_id": 2, "client_message_id": "local"])
        XCTAssertEqual(ChatTimelineEntry.merge(messages: [other], pending: [pending], userID: 1).count, 2)
    }
    func testVoiceDurationLabelsHandleOldMessagesAndMalformedValues() {
        XCTAssertEqual(VoiceMessageMetadata.seconds(in: "语音 · 12秒"), 12)
        XCTAssertEqual(VoiceMessageMetadata.seconds(in: "语音 1.2秒"), 2)
        XCTAssertNil(VoiceMessageMetadata.seconds(in: "语音"))
        XCTAssertNil(VoiceMessageMetadata.seconds(in: "999999999999999999999秒"))
    }
    func testFinancialReservationDoesNotOverwriteExistingOrCorruptedRecovery() async throws {
        let owner = 2_147_479_981, key = "ios-upgrade-pending-" + UUID().uuidString
        let original = try await LocalStore.shared.reserveFinancial(Record(["amount": "1"]), key: key, userID: owner)
        do { _ = try await LocalStore.shared.reserveFinancial(Record(["amount": "2"]), key: key, userID: owner); XCTFail("Must preserve the original request") }
        catch APIError.server(let status, _) { XCTAssertEqual(status, 409) }
        let clearedWrong = await LocalStore.shared.completeFinancial(key, userID: owner, requestID: "wrong")
        XCTAssertFalse(clearedWrong)
        let recovered: SpxChatIOS.Record? = await LocalStore.shared.read(key, userID: owner)
        XCTAssertEqual(recovered, original)
        let directory = try MediaFiles.directory(userID: owner, component: "")
        let encoded = Data(key.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_") + ".json"
        let location = directory.appendingPathComponent(encoded)
        try Data("{broken".utf8).write(to: location)
        do { _ = try await LocalStore.shared.reserveFinancial(Record(["amount": "3"]), key: key, userID: owner); XCTFail("Unreadable recovery must never be replaced") }
        catch APIError.server(let status, _) { XCTAssertEqual(status, 409) }
        XCTAssertEqual(try Data(contentsOf: location), Data("{broken".utf8))
        await LocalStore.shared.remove(key, userID: owner)
    }
    func testCacheBudgetEvictsTodaysOldFilesAndProtectsCurrentDownload() async throws {
        let owner = 2_147_479_982
        let cache = MediaCache()
        try await cache.clear(userID: owner)
        let directory = try MediaFiles.directory(userID: owner, component: "media")
        let old = directory.appendingPathComponent("old.bin"), current = directory.appendingPathComponent("current.bin")
        try Data(repeating: 1, count: 100).write(to: old); try Data(repeating: 2, count: 100).write(to: current)
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-60)], ofItemAtPath: old.path)
        try await cache.trim(userID: owner, maximumBytes: 100, excluding: [current])
        XCTAssertFalse(FileManager.default.fileExists(atPath: old.path)); XCTAssertTrue(FileManager.default.fileExists(atPath: current.path))
        try await cache.clear(userID: owner)
    }

    func testCacheTrimProtectsAnActiveRangeDirectoryWithoutKeepingUnrelatedFiles() async throws {
        let owner = 2_147_479_979
        let cache = MediaCache()
        try await cache.clear(userID: owner)
        let directory = try MediaFiles.directory(userID: owner, component: "media-ranges")
        let active = directory.appendingPathComponent("active", isDirectory: true)
        try FileManager.default.createDirectory(at: active, withIntermediateDirectories: true)
        let chunk = active.appendingPathComponent("0.bin"), manifest = active.appendingPathComponent("manifest.json")
        let expired = directory.appendingPathComponent("unused.bin")
        try Data(repeating: 1, count: 40).write(to: chunk)
        try Data(repeating: 2, count: 20).write(to: manifest)
        try Data(repeating: 3, count: 60).write(to: expired)
        try await cache.trim(userID: owner, maximumBytes: 60, excluding: [active])
        XCTAssertTrue(FileManager.default.fileExists(atPath: chunk.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: manifest.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: expired.path))
        try await cache.clear(userID: owner)
    }

    @MainActor func testCompactMediaReceiptRetainsContentAndURL() async throws {
        let owner = 2_147_479_980
        let outbox = Outbox(transport: CompactReceiptTransport(), observeNetwork: false)
        await LocalStore.shared.remove("outbox", userID: owner); await outbox.start(userID: owner)
        let complete = expectation(description: "Receipt contains the original attachment")
        var received: SpxChatIOS.Record?
        let subscription = outbox.$acknowledgements.sink { map in
            guard received == nil, let message = map.values.first else { return }; received = message; complete.fulfill()
        }
        let file = try MediaFiles.directory(userID: owner, component: "attachments").appendingPathComponent(UUID().uuidString + ".m4a")
        try Data([1, 2, 3]).write(to: file)
        try await outbox.enqueue(scope: "chat", id: 9, body: ["content": "语音 · 3秒", "message_type": "voice"], localURL: file, expectedUserID: owner)
        await fulfillment(of: [complete], timeout: 5)
        XCTAssertEqual(received?["content"].string, "语音 · 3秒")
        XCTAssertEqual(received?["media_url"].string, "https://receipt.test/voice.m4a")
        XCTAssertEqual(received?["message_type"].string, "voice")
        subscription.cancel(); await outbox.stop(userID: owner); await LocalStore.shared.remove("outbox", userID: owner)
        try? await MediaCache.shared.clear(userID: owner); try? FileManager.default.removeItem(at: file)
    }
}

private struct CompactReceiptTransport: OutboxTransport {
    func upload(file: URL, userID: Int, uploadID: String, progress: @escaping @Sendable (Double) -> Void) async throws -> JSON {
        progress(1); return .object(["url": "https://receipt.test/voice.m4a", "message_type": "voice"])
    }
    func send(scope: String, body: [String: JSON]) async throws -> JSON { .object(["message": .object(["id": 75])]) }
}
