import XCTest
@testable import SpxChatIOS

private actor ReadCacheCounter {
    private(set) var calls = 0
    func next() -> JSON { calls += 1; return .number(Decimal(calls)) }
}

private actor ReadCacheGate {
    private let started: XCTestExpectation
    private var continuation: CheckedContinuation<JSON, Never>?
    init(_ started: XCTestExpectation) { self.started = started }
    func load() async -> JSON {
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            started.fulfill()
        }
    }
    func resolve(_ value: JSON) { continuation?.resume(returning: value); continuation = nil }
}

final class APIReadCacheTests: XCTestCase {
    private func directory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("APIReadCacheTests-" + UUID().uuidString, isDirectory: true)
    }
    func testFreshHitExpiryAndExplicitRefresh() async throws {
        let directory = directory(); defer { try? FileManager.default.removeItem(at: directory) }
        let cache = APIReadCache(directory: directory), counter = ReadCacheCounter()
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        let first = try await cache.value(scope: "s", key: "k", action: "products", lifetime: 120, now: date) { await counter.next() }
        let hit = try await cache.value(scope: "s", key: "k", action: "products", lifetime: 120, now: date.addingTimeInterval(119)) { await counter.next() }
        let expired = try await cache.value(scope: "s", key: "k", action: "products", lifetime: 120, now: date.addingTimeInterval(120)) { await counter.next() }
        let forced = try await cache.value(scope: "s", key: "k", action: "products", lifetime: 120, force: true, now: date.addingTimeInterval(121)) { await counter.next() }
        XCTAssertEqual(first, 1); XCTAssertEqual(hit, 1); XCTAssertEqual(expired, 2); XCTAssertEqual(forced, 3)
        let calls = await counter.calls; XCTAssertEqual(calls, 3)
    }
    func testAccountEndpointLanguageAndQueryIsolation() async throws {
        let directory = directory(); defer { try? FileManager.default.removeItem(at: directory) }
        let cache = APIReadCache(directory: directory), counter = ReadCacheCounter()
        let scopes = [
            APIReadCache.scope(endpoint: "https://a.example/api.php", credential: "a", language: "zh-CN"),
            APIReadCache.scope(endpoint: "https://a.example/api.php", credential: "b", language: "zh-CN"),
            APIReadCache.scope(endpoint: "https://b.example/api.php", credential: "a", language: "zh-CN"),
            APIReadCache.scope(endpoint: "https://a.example/api.php", credential: "a", language: "en-US")
        ]
        XCTAssertEqual(Set(scopes).count, 4)
        for (index, scope) in scopes.enumerated() {
            let value = try await cache.value(scope: scope, key: "page1", action: "products", lifetime: 120) { await counter.next() }
            XCTAssertEqual(value.int, index + 1)
        }
        let secondPage = try await cache.value(scope: scopes[0], key: "page2", action: "products", lifetime: 120) { await counter.next() }
        let firstPage = try await cache.value(scope: scopes[0], key: "page1", action: "products", lifetime: 120) { await counter.next() }
        XCTAssertEqual(secondPage, 5); XCTAssertEqual(firstPage, 1)
    }
    func testDiskRestoreAndSelectiveInvalidationAreDurable() async throws {
        let directory = directory(); defer { try? FileManager.default.removeItem(at: directory) }
        let cache = APIReadCache(directory: directory), counter = ReadCacheCounter()
        _ = try await cache.value(scope: "s", key: "wallet", action: "wallet", lifetime: 120) { await counter.next() }
        _ = try await cache.value(scope: "s", key: "products", action: "products", lifetime: 120) { await counter.next() }
        let reopened = APIReadCache(directory: directory)
        let restored = try await reopened.value(scope: "s", key: "wallet", action: "wallet", lifetime: 120) { await counter.next() }
        XCTAssertEqual(restored, 1)
        await reopened.invalidate(scope: "s", actions: ["wallet"])
        let again = APIReadCache(directory: directory)
        let products = try await again.value(scope: "s", key: "products", action: "products", lifetime: 120) { await counter.next() }
        let wallet = try await again.value(scope: "s", key: "wallet", action: "wallet", lifetime: 120) { await counter.next() }
        XCTAssertEqual(products, 2); XCTAssertEqual(wallet, 3)
    }
    func testConcurrentDisplayReadsShareOneRequest() async throws {
        let directory = directory(); defer { try? FileManager.default.removeItem(at: directory) }
        let cache = APIReadCache(directory: directory), counter = ReadCacheCounter()
        let values = try await withThrowingTaskGroup(of: JSON.self) { group in
            for _ in 0..<24 {
                group.addTask {
                    try await cache.value(scope: "s", key: "k", action: "contacts", lifetime: 300) {
                        let value = await counter.next()
                        try await Task.sleep(for: .milliseconds(50))
                        return value
                    }
                }
            }
            var values: [JSON] = []
            for try await value in group { values.append(value) }
            return values
        }
        XCTAssertEqual(values.count, 24); XCTAssertEqual(Set(values), [JSON(integerLiteral: 1)])
        let calls = await counter.calls; XCTAssertEqual(calls, 1)
    }
    func testInvalidatedInFlightResponseCannotRepopulateCache() async throws {
        let directory = directory(); defer { try? FileManager.default.removeItem(at: directory) }
        let cache = APIReadCache(directory: directory)
        let started = expectation(description: "Old request started"), gate = ReadCacheGate(started)
        let old = Task { try await cache.value(scope: "s", key: "k", action: "wallet", lifetime: 30) { await gate.load() } }
        await fulfillment(of: [started], timeout: 3)
        await cache.invalidate(scope: "s", actions: ["wallet"])
        let fresh = try await cache.value(scope: "s", key: "k", action: "wallet", lifetime: 30) { 2 }
        await gate.resolve(1)
        do { _ = try await old.value; XCTFail("Invalidated request returned stale data") } catch is CancellationError { } catch { XCTFail("Unexpected error: \(error)") }
        let hit = try await cache.value(scope: "s", key: "k", action: "wallet", lifetime: 30) { 3 }
        XCTAssertEqual(fresh, 2); XCTAssertEqual(hit, 2)
    }
    func testForcedRefreshSupersedesOrdinaryPendingRead() async throws {
        let directory = directory(); defer { try? FileManager.default.removeItem(at: directory) }
        let cache = APIReadCache(directory: directory)
        let started = expectation(description: "Ordinary request started"), gate = ReadCacheGate(started)
        let old = Task { try await cache.value(scope: "s", key: "k", action: "products", lifetime: 120) { await gate.load() } }
        await fulfillment(of: [started], timeout: 3)
        let fresh = try await cache.value(scope: "s", key: "k", action: "products", lifetime: 120, force: true) { 2 }
        await gate.resolve(1)
        do { _ = try await old.value; XCTFail("Ordinary request overwrote forced refresh") } catch is CancellationError { } catch { XCTFail("Unexpected error: \(error)") }
        let hit = try await cache.value(scope: "s", key: "k", action: "products", lifetime: 120) { 3 }
        XCTAssertEqual(fresh, 2); XCTAssertEqual(hit, 2)
    }
    func testFailedRefreshDoesNotReturnExpiredFinancialData() async throws {
        let directory = directory(); defer { try? FileManager.default.removeItem(at: directory) }
        let cache = APIReadCache(directory: directory), date = Date(timeIntervalSince1970: 1_700_000_000)
        _ = try await cache.value(scope: "s", key: "k", action: "wallet", lifetime: 30, now: date) { 1 }
        do {
            _ = try await cache.value(scope: "s", key: "k", action: "wallet", lifetime: 30, now: date.addingTimeInterval(31)) { throw URLError(.notConnectedToInternet) }
            XCTFail("Expired balance was returned after a failed refresh")
        } catch let error as URLError { XCTAssertEqual(error.code, .notConnectedToInternet) }
        let retried = try await cache.value(scope: "s", key: "k", action: "wallet", lifetime: 30, now: date.addingTimeInterval(32)) { 2 }
        XCTAssertEqual(retried, 2)
    }
    func testLiveReadsAndMutationInvalidationRules() {
        for action in ["me", "wallet.exchange.quote", "financial.request", "orders.request", "redpackets.request", "redpackets.detail", "messages", "groups.messages", "messages.reconcile", "upload.status", "unknown.action"] {
            XCTAssertNil(APIReadPolicy.lifetime(action), action)
        }
        for action in ["products", "wallet", "staking", "staking.plans", "contacts", "chats", "groups", "orders", "faq", "questions", "wallet.transactions"] {
            XCTAssertNotNil(APIReadPolicy.lifetime(action), action)
        }
        XCTAssertTrue(APIReadPolicy.invalidatedActions(by: "staking.create").contains("wallet"))
        XCTAssertTrue(APIReadPolicy.invalidatedActions(by: "orders.cancel").contains("products"))
        XCTAssertTrue(APIReadPolicy.invalidatedActions(by: "contacts.remark").contains("contacts"))
        XCTAssertTrue(APIReadPolicy.invalidatedActions(by: "questions.create").contains("questions"))
        XCTAssertTrue(APIReadPolicy.invalidatedActions(by: "presence.update").isEmpty)
        XCTAssertEqual(APIReadPolicy.invalidatedActions(by: "groups.messages.read"), ["chats", "groups"])
        XCTAssertEqual(APIReadPolicy.realtimeActions("group.message"), ["chats", "groups"])
        XCTAssertFalse(APIReadPolicy.realtimeActions("group.message")?.contains("groups.members") ?? true)
        XCTAssertTrue(APIReadPolicy.realtimeActions("wallet.updated")?.contains("wallet") ?? false)
        XCTAssertEqual(APIReadPolicy.realtimeActions("presence.updated"), [])
        XCTAssertNil(APIReadPolicy.realtimeActions("ready"))
    }
}
