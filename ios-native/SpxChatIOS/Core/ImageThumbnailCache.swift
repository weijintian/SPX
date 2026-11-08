import Foundation
import UIKit
import ImageIO
import AVFoundation
import CryptoKit

/// Decode on this actor, never in a SwiftUI body or on the main actor.
actor ImageThumbnailCache {
    static let shared = ImageThumbnailCache()
    private let memory = NSCache<NSString, UIImage>()
    private var tasks: [String: (id: UUID, task: Task<UIImage, Error>)] = [:]
    private var generations: [Int: UUID] = [:]
    private var waiters: [String: Set<UUID>] = [:]
    init() { memory.totalCostLimit = 48 * 1024 * 1024; memory.countLimit = 240 }

    func image(_ source: String, userID: Int, maximumPixelSize: Int = 720) async throws -> UIImage {
        try await thumbnail(source, userID: userID, pixels: maximumPixelSize, video: false)
    }
    func video(_ source: String, userID: Int) async throws -> UIImage {
        try await thumbnail(source, userID: userID, pixels: 480, video: true)
    }
    func local(_ file: URL, userID: Int, video: Bool = false, maximumPixelSize: Int = 480) async throws -> UIImage {
        try await thumbnail(file.absoluteString, userID: userID, pixels: maximumPixelSize, video: video, localURL: file)
    }
    private func thumbnail(_ source: String, userID: Int, pixels: Int, video: Bool, localURL: URL? = nil) async throws -> UIImage {
        guard userID > 0, !source.isEmpty else { throw APIError.unauthorized }
        let pixels = min(4096, max(64, pixels))
        let key = "\(userID):\(video ? "v" : "i"):\(pixels):\(source)"
        if let cached = memory.object(forKey: key as NSString) { return cached }
        if let existing = tasks[key] { return try await wait(for: existing, key: key) }
        let generation = generations[userID] ?? UUID(); generations[userID] = generation
        let id = UUID()
        let task = Task<UIImage, Error> {
            let hash = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
            let file = try MediaFiles.directory(userID: userID, component: "media-thumbnails").appendingPathComponent(hash + (video ? ".jpg" : ".png"))
            let image: UIImage
            if FileManager.default.fileExists(atPath: file.path), let cached = try? Self.decode(file, pixels: pixels) {
                image = cached
            } else if video {
                let permit = UUID()
                try await VideoThumbnailLimiter.shared.acquire(permit)
                defer { Task { await VideoThumbnailLimiter.shared.release() } }
                try Task.checkCancellation()
                let resource: VideoAssetSource
                if let localURL { resource = VideoAssetSource(asset: AVURLAsset(url: localURL), loader: nil) }
                else { resource = try await VideoAssetSource.make(source: source, userID: userID) }
                defer { resource.loader?.cancelAllRequests() }
                let generator = AVAssetImageGenerator(asset: resource.asset)
                generator.appliesPreferredTrackTransform = true
                generator.maximumSize = CGSize(width: pixels, height: pixels)
                let frame = try await Self.generateFrame(generator, at: .zero)
                image = UIImage(cgImage: frame)
            } else {
                let original: URL
                if let localURL { original = localURL }
                else { original = try await MediaCache.shared.file(source, userID: userID) }
                try Task.checkCancellation()
                image = try Self.decode(original, pixels: pixels)
            }
            try Task.checkCancellation()
            guard generations[userID] == generation else { throw CancellationError() }
            if !FileManager.default.fileExists(atPath: file.path), let data = video ? image.jpegData(compressionQuality: 0.85) : image.pngData() {
                try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
            try? await MediaCache.shared.trim(userID: userID, excluding: [file])
            try Task.checkCancellation()
            guard generations[userID] == generation else { throw CancellationError() }
            let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? pixels * pixels * 4
            memory.setObject(image, forKey: key as NSString, cost: cost)
            return image
        }
        tasks[key] = (id, task)
        defer { if tasks[key]?.id == id { tasks[key] = nil; waiters[key] = nil } }
        return try await wait(for: (id, task), key: key)
    }
    private static func generateFrame(_ generator: AVAssetImageGenerator, at time: CMTime) async throws -> CGImage {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<CGImage, Error>) in
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, image, _, result, error in
                    if let image { continuation.resume(returning: image) }
                    else if let error { continuation.resume(throwing: error) }
                    else if result == .cancelled { continuation.resume(throwing: CancellationError()) }
                    else { continuation.resume(throwing: APIError.invalidResponse) }
                }
            }
        }, onCancel: { generator.cancelAllCGImageGeneration() })
    }
    private func wait(for entry: (id: UUID, task: Task<UIImage, Error>), key: String) async throws -> UIImage {
        let observer = UUID(); waiters[key, default: []].insert(observer)
        defer { releaseWaiter(observer, key: key, taskID: entry.id) }
        return try await withTaskCancellationHandler(operation: {
            let image = try await entry.task.value
            try Task.checkCancellation(); return image
        }, onCancel: { Task { await self.releaseWaiter(observer, key: key, taskID: entry.id) } })
    }
    private func releaseWaiter(_ observer: UUID, key: String, taskID: UUID) {
        guard tasks[key]?.id == taskID else { return }
        waiters[key]?.remove(observer)
        if waiters[key]?.isEmpty == true { waiters[key] = nil; tasks.removeValue(forKey: key)?.task.cancel() }
    }
    private static func decode(_ file: URL, pixels: Int) throws -> UIImage {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: pixels,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw APIError.invalidResponse }
        return UIImage(cgImage: image)
    }
    func clear(userID: Int) throws {
        generations[userID] = UUID()
        for key in tasks.keys.filter({ $0.hasPrefix("\(userID):") }) { tasks.removeValue(forKey: key)?.task.cancel(); waiters[key] = nil }
        // Memory entries are cheap and account-keyed; clearing all also releases decoded pixels immediately.
        memory.removeAllObjects()
        let directory = try MediaFiles.directory(userID: userID, component: "media-thumbnails")
        for file in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) { try FileManager.default.removeItem(at: file) }
    }
}

/// Limit concurrent poster decoders; cancellation removes queued work without leaking permits.
actor VideoThumbnailLimiter {
    static let shared = VideoThumbnailLimiter()
    private var active = 0
    private var order: [UUID] = []
    private var waiting: [UUID: CheckedContinuation<Void, Error>] = [:]
    func acquire(_ id: UUID) async throws {
        try Task.checkCancellation()
        if active < 2 { active += 1; return }
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { continuation.resume(throwing: CancellationError()) }
                else { order.append(id); waiting[id] = continuation }
            }
        }, onCancel: { Task { await self.cancel(id) } })
    }
    private func cancel(_ id: UUID) {
        order.removeAll { $0 == id }; waiting.removeValue(forKey: id)?.resume(throwing: CancellationError())
    }
    func release() {
        while !order.isEmpty {
            let id = order.removeFirst()
            if let continuation = waiting.removeValue(forKey: id) { continuation.resume(); return }
        }
        active = max(0, active - 1)
    }
}
