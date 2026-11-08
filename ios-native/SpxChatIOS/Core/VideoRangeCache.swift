import Foundation
import CryptoKit
import AVFoundation

struct VideoChunk: Sendable {
    let data: Data
    let offset: Int
    let total: Int
    let mimeType: String?
}

/// Stable aligned chunks can be reused by playback, seeking and poster generation.
actor VideoRangeCache {
    static let shared = VideoRangeCache()
    static let chunkSize = 1_048_576
    private struct Manifest: Codable {
        let total: Int
        let mimeType: String?
        let validator: String
        var completed: Set<Int>
    }
    private struct PendingChunk {
        let id: UUID
        let task: Task<VideoChunk, Error>
        var waiters: [UUID: CheckedContinuation<VideoChunk, Error>] = [:]
    }
    private var tasks: [String: PendingChunk] = [:]
    private var promotions: [String: (id: UUID, task: Task<Void, Never>)] = [:]
    private var generations: [Int: UUID] = [:]
    private var versions: [String: UUID] = [:]
    private let session: URLSession
    init(session: URLSession = NetworkSession.shared) { self.session = session }

    static func alignedOffset(_ offset: Int) -> Int { offset - offset % chunkSize }
    func chunk(source: String, remote: URL, userID: Int, offset: Int) async throws -> VideoChunk {
        guard userID > 0, offset >= 0, offset < 1_073_741_824 else { throw APIError.invalidResponse }
        try Task.checkCancellation()
        let start = Self.alignedOffset(offset)
        if let file = try await MediaCache.shared.cachedFile(source, userID: userID) {
            try Task.checkCancellation()
            let total = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard start < total else { throw APIError.invalidResponse }
            let input = try FileHandle(forReadingFrom: file); defer { try? input.close() }
            try input.seek(toOffset: UInt64(start))
            let expected = min(Self.chunkSize, total - start)
            guard let data = try input.read(upToCount: expected), data.count == expected else { throw APIError.invalidResponse }
            return VideoChunk(data: data, offset: start, total: total, mimeType: nil)
        }
        let hash = SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined()
        let ownerKey = "\(userID):\(hash)", key = "\(userID):\(hash):\(start)"
        try Task.checkCancellation()
        if let existing = tasks[key] { return try await waitForChunk(key: key, taskID: existing.id) }
        let generation = generations[userID] ?? UUID(); generations[userID] = generation
        let version = versions[ownerKey] ?? UUID(); versions[ownerKey] = version
        let id = UUID()
        let task = Task<VideoChunk, Error> {
            try Task.checkCancellation()
            let directory = try MediaFiles.directory(userID: userID, component: "media-ranges").appendingPathComponent(hash, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let index = start / Self.chunkSize
            let manifestURL = directory.appendingPathComponent("manifest.json")
            let part = directory.appendingPathComponent("\(index).bin")
            if let saved = Self.manifest(manifestURL), saved.completed.contains(index), start < saved.total,
               let bytes = try? Data(contentsOf: part), bytes.count == min(Self.chunkSize, saved.total - start) {
                try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: directory.path)
                return VideoChunk(data: bytes, offset: start, total: saved.total, mimeType: saved.mimeType)
            }
            var request = URLRequest(url: remote)
            request.setValue("bytes=\(start)-\(start + Self.chunkSize - 1)", forHTTPHeaderField: "Range")
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.cachePolicy = .reloadIgnoringLocalCacheData
            let (stream, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 206,
                  let range = HTTPContentRange(http.value(forHTTPHeaderField: "Content-Range")),
                  range.matches(offset: start, maximumCount: Self.chunkSize), range.total <= 1_073_741_824,
                  range.count == min(Self.chunkSize, range.total - start) else {
                throw APIError.server((response as? HTTPURLResponse)?.statusCode ?? 0, "视频分段暂不可用，请稍后重试")
            }
            var bytes = Data(); bytes.reserveCapacity(range.count)
            for try await byte in stream { bytes.append(byte); if bytes.count == range.count { break } }
            guard bytes.count == range.count else { throw APIError.invalidResponse }
            try Task.checkCancellation()
            guard generations[userID] == generation, versions[ownerKey] == version else { throw CancellationError() }
            let validator = http.value(forHTTPHeaderField: "ETag") ?? http.value(forHTTPHeaderField: "Last-Modified") ?? ""
            var saved = Self.manifest(manifestURL)
            if let saved, saved.total != range.total || (!saved.validator.isEmpty && !validator.isEmpty && saved.validator != validator) {
                versions[ownerKey] = UUID()
                try? FileManager.default.removeItem(at: directory)
                throw APIError.server(409, "视频资源已更新，请重新打开")
            }
            if saved == nil { saved = Manifest(total: range.total, mimeType: http.mimeType, validator: validator, completed: []) }
            if var manifest = saved {
                do {
                    try bytes.write(to: part, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                    manifest.completed.insert(index)
                    try JSONEncoder().encode(manifest).write(to: manifestURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                    let count = (manifest.total + Self.chunkSize - 1) / Self.chunkSize
                    if manifest.completed.count == count {
                        promote(directory: directory, manifest: manifest, source: source, userID: userID, key: ownerKey, generation: generation, version: version)
                    }
                } catch { /* Disk pressure must not prevent a validated network chunk from playing. */ }
            }
            try? await MediaCache.shared.trim(userID: userID, excluding: [directory])
            try Task.checkCancellation()
            guard generations[userID] == generation, versions[ownerKey] == version else { throw CancellationError() }
            return VideoChunk(data: bytes, offset: start, total: range.total, mimeType: http.mimeType)
        }
        tasks[key] = PendingChunk(id: id, task: task)
        return try await waitForChunk(key: key, taskID: id)
    }
    private func waitForChunk(key: String, taskID: UUID) async throws -> VideoChunk {
        let waiter = UUID()
        let chunk = try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<VideoChunk, Error>) in
                guard !Task.isCancelled, let entry = tasks[key], entry.id == taskID else {
                    cancelWaiter(waiter, key: key, taskID: taskID)
                    continuation.resume(throwing: CancellationError()); return
                }
                tasks[key]?.waiters[waiter] = continuation
                Task {
                    let result = await entry.task.result
                    finishWaiter(waiter, key: key, taskID: taskID, result: result)
                }
            }
        }, onCancel: { Task { await self.cancelWaiter(waiter, key: key, taskID: taskID) } })
        try Task.checkCancellation()
        return chunk
    }
    private func cancelWaiter(_ waiter: UUID, key: String, taskID: UUID) {
        finishWaiter(waiter, key: key, taskID: taskID, result: .failure(CancellationError()))
    }
    private func finishWaiter(_ waiter: UUID, key: String, taskID: UUID, result: Result<VideoChunk, Error>) {
        guard tasks[key]?.id == taskID else { return }
        tasks[key]?.waiters.removeValue(forKey: waiter)?.resume(with: result)
        if tasks[key]?.waiters.isEmpty == true {
            tasks.removeValue(forKey: key)?.task.cancel()
        }
    }
    private static func manifest(_ url: URL) -> Manifest? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        guard let saved = try? JSONDecoder().decode(Manifest.self, from: data), saved.total > 0, saved.total <= 1_073_741_824 else { return nil }
        let count = (saved.total + chunkSize - 1) / chunkSize
        guard saved.completed.allSatisfy({ $0 >= 0 && $0 < count }) else { return nil }
        return saved
    }
    private func promote(directory: URL, manifest: Manifest, source: String, userID: Int, key: String, generation: UUID, version: UUID) {
        guard promotions[key] == nil else { return }
        let promotionID = UUID()
        let task = Task {
            defer { if promotions[key]?.id == promotionID { promotions[key] = nil } }
            let assembled = directory.appendingPathComponent(UUID().uuidString + ".assembled")
            defer { try? FileManager.default.removeItem(at: assembled) }
            do {
                let assembly = Task.detached(priority: .utility) {
                    try Task.checkCancellation()
                    guard FileManager.default.createFile(atPath: assembled.path, contents: nil) else { throw APIError.invalidResponse }
                    let output = try FileHandle(forWritingTo: assembled); defer { try? output.close() }
                    let count = (manifest.total + Self.chunkSize - 1) / Self.chunkSize
                    for index in 0..<count {
                        try Task.checkCancellation()
                        let file = directory.appendingPathComponent("\(index).bin")
                        let bytes = try Data(contentsOf: file)
                        guard bytes.count == min(Self.chunkSize, manifest.total - index * Self.chunkSize) else { throw APIError.invalidResponse }
                        try output.write(contentsOf: bytes)
                    }
                    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: assembled.path)
                }
                try await withTaskCancellationHandler(operation: { try await assembly.value }, onCancel: { assembly.cancel() })
                try Task.checkCancellation()
                guard generations[userID] == generation, versions[key] == version else { return }
                try await MediaCache.shared.seed(assembled, source: source, userID: userID)
                guard generations[userID] == generation, versions[key] == version else { return }
                // The next range request can read the full file; release duplicate parts.
                try? FileManager.default.removeItem(at: directory)
            } catch { /* Incomplete assemblies keep their individually verified chunks. */ }
        }
        promotions[key] = (promotionID, task)
    }
    func clear(userID: Int) throws {
        generations[userID] = UUID()
        for key in tasks.keys.filter({ $0.hasPrefix("\(userID):") }) {
            guard let entry = tasks.removeValue(forKey: key) else { continue }
            entry.task.cancel()
            for continuation in entry.waiters.values { continuation.resume(throwing: CancellationError()) }
        }
        for key in promotions.keys.filter({ $0.hasPrefix("\(userID):") }) { promotions.removeValue(forKey: key)?.task.cancel() }
        let directory = try MediaFiles.directory(userID: userID, component: "media-ranges")
        for file in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) { try FileManager.default.removeItem(at: file) }
    }
}

/// Shared by chat playback, product video and remote poster generation.
struct VideoAssetSource {
    let asset: AVURLAsset
    let loader: RemoteVideoResourceLoader?
    static func make(source: String, userID: Int) async throws -> VideoAssetSource {
        guard userID > 0, !source.isEmpty else { throw APIError.unauthorized }
        if let file = try await MediaCache.shared.cachedFile(source, userID: userID) { return VideoAssetSource(asset: AVURLAsset(url: file), loader: nil) }
        let endpoint = try await APIClient.shared.endpoint()
        guard let remote = URL(string: source, relativeTo: endpoint)?.absoluteURL,
              remote.scheme == "https", remote.host != nil, remote.user == nil, remote.password == nil else { throw APIError.invalidURL }
        let loader = RemoteVideoResourceLoader(sourceURL: remote, source: source, userID: userID)
        return VideoAssetSource(asset: try loader.makeAsset(), loader: loader)
    }
}
