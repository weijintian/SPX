import Foundation
import SwiftUI
import CoreTransferable
import CryptoKit
import PhotosUI
import UniformTypeIdentifiers
import UIKit
import ImageIO

enum MediaFiles {
    static func directory(userID: Int, component: String) throws -> URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SPX/u\(userID)/\(component)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var url = root; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        return root
    }
    static let maximumBytes = 1_073_741_824
    static func preflight(_ source: URL, copies: Int = 1) throws {
        let size = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= maximumBytes else { throw APIError.server(413, "附件不能为空且不能超过 1 GB") }
        let volume = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        if let free = try? volume.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage,
           free < Int64(size) * Int64(copies) + 67_108_864 {
            throw APIError.server(507, "设备存储不足，请清理空间后再选择附件")
        }
    }
    static func restoredAttachment(_ source: URL, userID: Int) throws -> URL {
        let current = try directory(userID: userID, component: "attachments")
        // App updates can change the sandbox prefix; only rebase an account's own attachment.
        guard source.deletingLastPathComponent().lastPathComponent == "attachments",
              source.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent == "u\(userID)" else { return source }
        return current.appendingPathComponent(source.lastPathComponent)
    }
    static func keep(_ source: URL, userID: Int) throws -> URL {
        let directory = try directory(userID: userID, component: "attachments")
        let target = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension(source.pathExtension)
        let granted = source.startAccessingSecurityScopedResource()
        defer { if granted { source.stopAccessingSecurityScopedResource() } }
        try preflight(source)
        try FileManager.default.copyItem(at: source, to: target)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
        return target
    }
    static func keepForSending(_ source: URL, userID: Int) async throws -> URL {
        let file = try await Task.detached(priority: .userInitiated) { try keep(source, userID: userID) }.value
        if Task.isCancelled { try? FileManager.default.removeItem(at: file); throw CancellationError() }
        return file
    }
    static func preparePhoto(_ image: UIImage, userID: Int) async throws -> URL {
        let file = try await Task.detached(priority: .userInitiated) { try jpeg(image, userID: userID) }.value
        if Task.isCancelled { try? FileManager.default.removeItem(at: file); throw CancellationError() }
        return file
    }
    static func jpeg(_ image: UIImage, userID: Int) throws -> URL {
        let factor = min(1, 2048 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * factor, height: image.size.height * factor)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { context in UIColor.white.setFill(); context.fill(CGRect(origin: .zero, size: size)); image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = resized.jpegData(compressionQuality: 0.86) else { throw APIError.invalidResponse }
        let url = try directory(userID: userID, component: "attachments").appendingPathComponent(UUID().uuidString + ".jpg")
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return url
    }
    static func select(_ item: PhotosPickerItem, userID: Int, original: Bool = false) async throws -> URL {
        if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
            guard let picked = try await item.loadTransferable(type: PickedMovie.self) else { throw APIError.invalidResponse }
            defer { try? FileManager.default.removeItem(at: picked.url) }
            let target = try directory(userID: userID, component: "attachments").appendingPathComponent(picked.url.lastPathComponent)
            try FileManager.default.moveItem(at: picked.url, to: target)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
            return target
        }
        guard let data = try await item.loadTransferable(type: Data.self),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 2048] as CFDictionary) else { throw APIError.invalidResponse }
        // Preserve animation and alpha instead of flattening every image to JPEG.
        let imageType = CGImageSourceGetType(source) as String? ?? ""
        if imageType == UTType.gif.identifier || imageType == UTType.png.identifier || (original && imageType == UTType.jpeg.identifier) {
            guard data.count <= 52_428_800 else { throw APIError.server(413, "图片不能超过 50 MB") }
            let ext = imageType == UTType.gif.identifier ? "gif" : imageType == UTType.png.identifier ? "png" : "jpg"
            let target = try directory(userID: userID, component: "attachments").appendingPathComponent(UUID().uuidString + "." + ext)
            try data.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return target
        }
        let image = UIImage(cgImage: thumbnail)
        return try jpeg(image, userID: userID)
    }
}

struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            try MediaFiles.preflight(received.file)
            let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: target)
            return PickedMovie(url: target)
        }
    }
}

enum UploadedMedia {
    static func url(in result: JSON) -> String {
        for value in [result["url"], result["media_url"], result["data"]["url"], result["data"]["media_url"]] {
            let source = value.string.trimmingCharacters(in: .whitespacesAndNewlines)
            if !source.isEmpty { return source }
        }
        return ""
    }
    static func type(in result: JSON) -> String {
        let type = result["message_type"].string.isEmpty ? result["data"]["message_type"].string : result["message_type"].string
        return ["image", "video", "voice", "file"].contains(type) ? type : ""
    }
    static func supportsStatusFallback(_ error: Error) -> Bool {
        guard let failure = error as? APIError, case .server(let code, _) = failure else { return false }
        // The checkpoint probe is optional. Older deployments may return 5xx when
        // that extension is missing, while the established chunk route still works.
        return [404, 405].contains(code) || (500...599).contains(code)
    }
}

actor MediaUploader {
    static let shared = MediaUploader()
    private let chunkSize = 1_048_576

    func upload(file: URL, userID: Int, uploadID: String, progress: @escaping @Sendable (Double) -> Void) async throws -> JSON {
        try Task.checkCancellation()
        let sessionID = await APIClient.shared.sessionIdentity()
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= 1_073_741_824 else { throw APIError.server(413, "附件不能为空且不能超过 1 GB") }
        let total = (size + chunkSize - 1) / chunkSize
        guard userID > 0 else { throw APIError.unauthorized }
        // Match Android: small attachments use the established single-file endpoint.
        // Do not make every photo depend on the resumable-upload status extension.
        if size <= chunkSize {
            let boundary = "SPX-" + UUID().uuidString
            let mime = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            let body = Multipart.body(boundary: boundary, fields: [:], data: try Data(contentsOf: file),
                                      name: "file", filename: file.lastPathComponent, mime: mime)
            progress(0)
            let request = try await APIClient.shared.backgroundUploadRequest("upload.message", boundary: boundary, userID: userID)
            let result = try await APIClient.shared.uploadPrepared(request, body: body, expectedSession: sessionID)
            guard !UploadedMedia.url(in: result).isEmpty else { throw APIError.server(502, "附件上传未返回地址，请重试") }
            progress(1)
            return result
        }
        var status: JSON = .null
        for attempt in 0..<4 {
            do {
                status = try await APIClient.shared.uploadStatus(userID: userID, uploadID: uploadID,
                                                                totalChunks: total, totalBytes: size)
                break
            } catch {
                if UploadedMedia.supportsStatusFallback(error) { break }
                guard (error as? APIError)?.retryable == true || error is URLError, attempt < 3 else { throw error }
                try await Task.sleep(for: .seconds(attempt + 1))
            }
        }
        let currentSessionID = await APIClient.shared.sessionIdentity()
        guard sessionID == currentSessionID else { throw APIError.staleSession }
        if !UploadedMedia.url(in: status["result"]).isEmpty { progress(1); return status["result"] }
        let received = Set(status["received_chunks"].array.map(\.int))
        try Task.checkCancellation()
        return try await BackgroundMediaTransfer.shared.upload(
            file: file, userID: userID, uploadID: uploadID, totalBytes: size,
            chunkSize: chunkSize, totalChunks: total, receivedChunks: received, progress: progress
        )
    }

    func uploadForm(file: URL, action: String, maximumBytes: Int = 5_242_880, responseKey: String = "url") async throws -> String {
        let sessionID = await APIClient.shared.sessionIdentity()
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0, size <= maximumBytes else { throw APIError.server(413, "文件超过本次上传允许的大小") }
        let boundary = "SPX-" + UUID().uuidString
        let mime = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        let body = Multipart.body(boundary: boundary, fields: [:], data: try Data(contentsOf: file), name: "file", filename: "media." + file.pathExtension, mime: mime)
        let response = try await APIClient.shared.upload(action, body: body, boundary: boundary, expectedSession: sessionID)
        guard !response[responseKey].string.isEmpty else { throw APIError.invalidResponse }
        return response[responseKey].string
    }
}

enum Multipart {
    static func body(boundary: String, fields: [String: String], data: Data, name: String, filename: String, mime: String) -> Data {
        var output = Data()
        for (key, value) in fields.sorted(by: { $0.key < $1.key }) {
            output.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(key)\"\r\n\r\n\(value)\r\n".utf8))
        }
        output.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\nContent-Type: \(mime)\r\n\r\n".utf8))
        output.append(data); output.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return output
    }

    static func writeChunk(from source: URL, to target: URL, index: Int, chunkSize: Int, totalBytes: Int, uploadID: String, totalChunks: Int) throws -> String {
        let offset = index * chunkSize
        let length = min(chunkSize, totalBytes - offset)
        guard index >= 0, length > 0 else { throw APIError.invalidResponse }
        let boundary = "SPX-" + UUID().uuidString
        let fields = ["upload_id": uploadID, "chunk_index": String(index), "total_chunks": String(totalChunks), "total_bytes": String(totalBytes)]
        let header = fields.sorted(by: { $0.key < $1.key }).map { key, value in
            "--\(boundary)\r\nContent-Disposition: form-data; name=\"\(key)\"\r\n\r\n\(value)\r\n"
        }.joined()
        let fileHeader = "--\(boundary)\r\nContent-Disposition: form-data; name=\"chunk\"; filename=\"part.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        let trailer = "\r\n--\(boundary)--\r\n"
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: target)
        guard FileManager.default.createFile(atPath: target.path, contents: nil) else { throw APIError.invalidResponse }
        let input = try FileHandle(forReadingFrom: source)
        let output = try FileHandle(forWritingTo: target)
        defer { try? input.close(); try? output.close() }
        try input.seek(toOffset: UInt64(offset))
        try output.write(contentsOf: Data((header + fileHeader).utf8))
        var remaining = length
        while remaining > 0 {
            guard let data = try input.read(upToCount: min(65_536, remaining)), !data.isEmpty else { throw APIError.invalidResponse }
            try output.write(contentsOf: data)
            remaining -= data.count
        }
        try output.write(contentsOf: Data(trailer.utf8))
        try output.close()
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
        return boundary
    }
}

actor MediaCache {
    static let shared = MediaCache()
    private var tasks: [String: (id: UUID, task: Task<URL, Error>)] = [:]
    private var generations: [Int: UUID] = [:]
    private var observers: [String: [UUID: @Sendable (Double) -> Void]] = [:]
    private let session: URLSession
    init(session: URLSession = NetworkSession.shared) { self.session = session }
    func file(_ source: String, userID: Int, progress: (@Sendable (Double) -> Void)? = nil) async throws -> URL {
        let endpoint = try await APIClient.shared.endpoint()
        guard let remote = URL(string: source, relativeTo: endpoint)?.absoluteURL, ["https", "http"].contains(remote.scheme ?? ""), !source.isEmpty else { throw APIError.invalidURL }
        let generation = generations[userID] ?? UUID(); generations[userID] = generation
        let key = "\(userID):\(source)"
        let observer = UUID()
        observers[key, default: [:]][observer] = progress ?? { _ in }
        defer { observers[key]?[observer] = nil; if observers[key]?.isEmpty == true { observers[key] = nil } }
        let hash = SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined()
        let target = try MediaFiles.directory(userID: userID, component: "media").appendingPathComponent(hash).appendingPathExtension(remote.pathExtension)
        if FileManager.default.fileExists(atPath: target.path) { try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path); return target }
        if let entry = tasks[key] { return try await waitForDownload(entry, key: key, observer: observer) }
        let taskID = UUID()
        let task = Task<URL, Error> {
            let delegate = MediaDownloadProgress { fraction in
                Task { await self.publishDownload(fraction, key: key, taskID: taskID) }
            }
            let (temporary, response) = try await session.download(from: remote, delegate: delegate)
            defer { try? FileManager.default.removeItem(at: temporary) }
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw APIError.invalidResponse }
            let bytes = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard bytes > 0 else { throw APIError.invalidResponse }
            try Task.checkCancellation()
            guard generations[userID] == generation else { throw CancellationError() }
            if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.moveItem(at: temporary, to: target) }
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
            publishDownload(1, key: key, taskID: taskID)
            try? trim(userID: userID, excluding: [target])
            return target
        }
        tasks[key] = (taskID, task)
        defer { if tasks[key]?.id == taskID { tasks[key] = nil } }
        return try await waitForDownload((taskID, task), key: key, observer: observer)
    }
    private func waitForDownload(_ entry: (id: UUID, task: Task<URL, Error>), key: String, observer: UUID) async throws -> URL {
        try await withTaskCancellationHandler(operation: {
            let file = try await entry.task.value
            try Task.checkCancellation(); return file
        }, onCancel: { Task { await self.cancelWaiter(observer, key: key, taskID: entry.id) } })
    }
    private func cancelWaiter(_ observer: UUID, key: String, taskID: UUID) {
        observers[key]?[observer] = nil
        if tasks[key]?.id == taskID, observers[key]?.isEmpty != false { tasks.removeValue(forKey: key)?.task.cancel() }
    }
    private func publishDownload(_ fraction: Double, key: String, taskID: UUID) {
        guard tasks[key]?.id == taskID, let callbacks = observers[key] else { return }
        for callback in callbacks.values { callback(fraction) }
    }
    func cancelDownload(_ source: String, userID: Int) {
        tasks.removeValue(forKey: "\(userID):\(source)")?.task.cancel()
    }
    /// Inspect the disk cache without starting a network transfer.
    func cachedFile(_ source: String, userID: Int) async throws -> URL? {
        let endpoint = try await APIClient.shared.endpoint()
        guard let remote = URL(string: source, relativeTo: endpoint)?.absoluteURL, ["https", "http"].contains(remote.scheme ?? ""), !source.isEmpty else { throw APIError.invalidURL }
        let hash = SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined()
        let target = try MediaFiles.directory(userID: userID, component: "media").appendingPathComponent(hash).appendingPathExtension(remote.pathExtension)
        guard FileManager.default.fileExists(atPath: target.path) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
        return target
    }
    func clear(userID: Int) async throws {
        generations[userID] = UUID()
        let keys = tasks.keys.filter { $0.hasPrefix("\(userID):") }
        for key in keys { tasks.removeValue(forKey: key)?.task.cancel() }
        try await VideoRangeCache.shared.clear(userID: userID)
        try await ImageThumbnailCache.shared.clear(userID: userID)
        let directory = try MediaFiles.directory(userID: userID, component: "media")
        for url in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) { try FileManager.default.removeItem(at: url) }
    }
    func seed(_ local: URL, source: String, userID: Int) async throws {
        try Task.checkCancellation()
        let generation = generations[userID] ?? UUID(); generations[userID] = generation
        let endpoint = try await APIClient.shared.endpoint()
        try Task.checkCancellation()
        guard generation == generations[userID] else { throw CancellationError() }
        guard let remote = URL(string: source, relativeTo: endpoint)?.absoluteURL, ["https", "http"].contains(remote.scheme ?? "") else { throw APIError.invalidURL }
        let hash = SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined()
        let target = try MediaFiles.directory(userID: userID, component: "media").appendingPathComponent(hash).appendingPathExtension(remote.pathExtension)
        guard !FileManager.default.fileExists(atPath: target.path) else { return }
        try FileManager.default.copyItem(at: local, to: target)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
        try? trim(userID: userID, excluding: [target])
    }
    func trim(userID: Int, maximumBytes: Int = 536_870_912, excluding: Set<URL> = []) throws {
        var files: [(URL, Int, Date)] = []
        for component in ["media", "media-ranges", "media-thumbnails"] {
            let directory = try MediaFiles.directory(userID: userID, component: component)
            let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
            while let url = enumerator?.nextObject() as? URL {
                guard let info = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]), info.isRegularFile == true else { continue }
                files.append((url, info.fileSize ?? 0, info.contentModificationDate ?? .distantPast))
            }
        }
        files.sort { $0.2 < $1.2 }
        var size = files.reduce(0) { $0 + $1.1 }
        for (url, bytes, _) in files where size > maximumBytes && !excluding.contains(where: { url == $0 || url.path.hasPrefix($0.path + "/") }) {
            do { try FileManager.default.removeItem(at: url); size -= bytes } catch { }
        }
    }
}
