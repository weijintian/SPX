import Foundation
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
    static func keep(_ source: URL, userID: Int) throws -> URL {
        let directory = try directory(userID: userID, component: "attachments")
        let target = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension(source.pathExtension)
        let granted = source.startAccessingSecurityScopedResource()
        defer { if granted { source.stopAccessingSecurityScopedResource() } }
        try FileManager.default.copyItem(at: source, to: target)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
        return target
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
    static func select(_ item: PhotosPickerItem, userID: Int) async throws -> URL {
        if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
            guard let picked = try await item.loadTransferable(type: PickedMovie.self) else { throw APIError.invalidResponse }
            defer { try? FileManager.default.removeItem(at: picked.url) }
            return try keep(picked.url, userID: userID)
        }
        guard let data = try await item.loadTransferable(type: Data.self),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 2048] as CFDictionary) else { throw APIError.invalidResponse }
        let image = UIImage(cgImage: thumbnail)
        return try jpeg(image, userID: userID)
    }
}

struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension(received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: target)
            return PickedMovie(url: target)
        }
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
        var status: JSON = .null
        for attempt in 0..<4 {
            do {
                status = try await APIClient.shared.uploadStatus(userID: userID, uploadID: uploadID,
                                                                totalChunks: total, totalBytes: size)
                break
            } catch {
                guard (error as? APIError)?.retryable == true || error is URLError, attempt < 3 else { throw error }
                try await Task.sleep(for: .seconds(attempt + 1))
            }
        }
        let currentSessionID = await APIClient.shared.sessionIdentity()
        guard sessionID == currentSessionID else { throw APIError.staleSession }
        if !status["result"]["url"].string.isEmpty { return status["result"] }
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
    private var tasks: [String: Task<URL, Error>] = [:]
    private var generations: [Int: UUID] = [:]
    func file(_ source: String, userID: Int) async throws -> URL {
        let endpoint = try await APIClient.shared.endpoint()
        guard let remote = URL(string: source, relativeTo: endpoint)?.absoluteURL, ["https", "http"].contains(remote.scheme ?? ""), !source.isEmpty else { throw APIError.invalidURL }
        let generation = generations[userID] ?? UUID(); generations[userID] = generation
        let key = "\(userID):\(source)"
        let hash = SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined()
        let target = try MediaFiles.directory(userID: userID, component: "media").appendingPathComponent(hash).appendingPathExtension(remote.pathExtension)
        if FileManager.default.fileExists(atPath: target.path) { try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path); return target }
        if let task = tasks[key] { return try await task.value }
        let task = Task<URL, Error> {
            let (temporary, response) = try await NetworkSession.shared.download(from: remote)
            defer { try? FileManager.default.removeItem(at: temporary) }
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw APIError.invalidResponse }
            let bytes = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard bytes > 0 else { throw APIError.invalidResponse }
            try Task.checkCancellation()
            guard generations[userID] == generation else { throw CancellationError() }
            if !FileManager.default.fileExists(atPath: target.path) { try FileManager.default.moveItem(at: temporary, to: target) }
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
            return target
        }
        tasks[key] = task
        defer { tasks[key] = nil }
        return try await task.value
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
    func clear(userID: Int) throws {
        generations[userID] = UUID()
        for (key, task) in tasks where key.hasPrefix("\(userID):") { task.cancel() }
        let directory = try MediaFiles.directory(userID: userID, component: "media")
        for url in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) { try FileManager.default.removeItem(at: url) }
    }
    func seed(_ local: URL, source: String, userID: Int) throws {
        guard let remote = URL(string: source), remote.scheme == "https" || remote.scheme == "http" else { return }
        let hash = SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined()
        let target = try MediaFiles.directory(userID: userID, component: "media").appendingPathComponent(hash).appendingPathExtension(remote.pathExtension)
        guard !FileManager.default.fileExists(atPath: target.path) else { return }
        try FileManager.default.copyItem(at: local, to: target)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path)
    }
    func trim(userID: Int, maximumBytes: Int = 536_870_912) throws {
        let directory = try MediaFiles.directory(userID: userID, component: "media")
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]).compactMap { url -> (URL, Int, Date)? in guard let info = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]) else { return nil }; return (url, info.fileSize ?? 0, info.contentModificationDate ?? .distantPast) }.sorted { $0.2 < $1.2 }
        var size = files.reduce(0) { $0 + $1.1 }
        for (url, bytes, modified) in files where size > maximumBytes && modified < Date().addingTimeInterval(-86400) { try FileManager.default.removeItem(at: url); size -= bytes }
    }
}
