import XCTest
@testable import SpxChatIOS

final class VideoCacheTests: XCTestCase {
    func testNewCacheInstanceReusesVerifiedDiskChunkAndClearInvalidatesIt() async throws {
        let owner = 2_147_479_983
        let source = "https://video-cache.test/" + UUID().uuidString + ".mp4"
        let remote = try XCTUnwrap(URL(string: source))
        let bytes = Data(repeating: 137, count: VideoRangeCache.chunkSize)
        VideoRangeURLProtocol.configure(bytes: bytes, total: bytes.count * 2)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [VideoRangeURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel(); VideoRangeURLProtocol.reset() }
        let first = VideoRangeCache(session: session), reopened = VideoRangeCache(session: session)
        do {
            let a = try await first.chunk(source: source, remote: remote, userID: owner, offset: 17)
            XCTAssertEqual(a.offset, 0); XCTAssertEqual(a.data, bytes)
            let b = try await reopened.chunk(source: source, remote: remote, userID: owner, offset: 1024)
            XCTAssertEqual(b.data, bytes)
            XCTAssertEqual(VideoRangeURLProtocol.requestCount, 1, "Reopening must not redownload the same range")
            try await reopened.clear(userID: owner)
            _ = try await reopened.chunk(source: source, remote: remote, userID: owner, offset: 0)
            XCTAssertEqual(VideoRangeURLProtocol.requestCount, 2)
            try await reopened.clear(userID: owner)
        } catch { try? await reopened.clear(userID: owner); throw error }
    }
    func testMisalignedResponseIsRejectedAndNotPersisted() async throws {
        let owner = 2_147_479_984
        let source = "https://video-cache.test/" + UUID().uuidString + ".mp4"
        let remote = try XCTUnwrap(URL(string: source))
        VideoRangeURLProtocol.configure(bytes: Data(repeating: 1, count: 32), total: VideoRangeCache.chunkSize * 2, responseStart: 1)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [VideoRangeURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel(); VideoRangeURLProtocol.reset() }
        let cache = VideoRangeCache(session: session)
        do { _ = try await cache.chunk(source: source, remote: remote, userID: owner, offset: 0); XCTFail("A misaligned range must never play or enter the disk cache") }
        catch APIError.server(_, _) { }
        catch { XCTFail(error.localizedDescription) }
        try await cache.clear(userID: owner)
    }
}

private final class VideoRangeURLProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var bytes = Data()
    private static var total = 0
    private static var responseStart = 0
    private static var count = 0
    static var requestCount: Int { lock.lock(); defer { lock.unlock() }; return count }
    static func configure(bytes: Data, total: Int, responseStart: Int = 0) {
        lock.lock(); defer { lock.unlock() }
        self.bytes = bytes; self.total = total; self.responseStart = responseStart; count = 0
    }
    static func reset() { configure(bytes: Data(), total: 0) }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "video-cache.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let bytes = Self.bytes, total = Self.total, start = Self.responseStart
        Self.count += 1; Self.lock.unlock()
        guard let url = request.url, !bytes.isEmpty, let response = HTTPURLResponse(url: url, statusCode: 206, httpVersion: "HTTP/1.1", headerFields: [
            "Content-Range": "bytes \(start)-\(start + bytes.count - 1)/\(total)",
            "Content-Length": String(bytes.count), "Content-Type": "video/mp4", "ETag": "test-immutable-v1"
        ]) else { client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse)); return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: bytes)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
