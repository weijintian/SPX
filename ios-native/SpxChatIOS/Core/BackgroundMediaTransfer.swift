import Foundation

private struct BackgroundUploadRecord: Codable {
    let userID: Int
    let uploadID: String
    let filePath: String
    let totalBytes: Int
    let chunkSize: Int
    let totalChunks: Int
    var completedChunks: [Int]
    var attempts: [String: Int]
    var paused: Bool?
    var retryNotBefore: Date?
    var activeTaskID: Int?
}

private struct BackgroundUploadTag {
    let userID: Int
    let uploadID: String
    let index: Int
    var key: String { "\(userID):\(uploadID)" }

    init?(taskDescription: String?) {
        guard let taskDescription else { return nil }
        let parts = taskDescription.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count == 4, parts[0] == "spxupload",
              let userID = Int(parts[1]), userID > 0, UUID(uuidString: String(parts[2])) != nil,
              let index = Int(parts[3]), index >= 0, index < 1024 else { return nil }
        self.userID = userID
        uploadID = String(parts[2])
        self.index = index
    }

    init(userID: Int, uploadID: String, index: Int) {
        self.userID = userID
        self.uploadID = uploadID
        self.index = index
    }

    var description: String { "spxupload|\(userID)|\(uploadID)|\(index)" }
}

final class BackgroundMediaTransfer: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    static let identifier = "com.spx.chat.ios.media-upload"
    static let shared = BackgroundMediaTransfer()

    private let stateQueue = DispatchQueue(label: "com.spx.chat.ios.media-upload.state")
    private var session: URLSession!
    private var records: [String: BackgroundUploadRecord] = [:]
    private var continuations: [String: CheckedContinuation<JSON, Error>] = [:]
    private var progressHandlers: [String: @Sendable (Double) -> Void] = [:]
    private var responseBodies: [Int: Data] = [:]
    private var scheduling = Set<String>()
    private var reservedUsers = Set<Int>()
    private var canceledJobs = Set<String>()
    private var backgroundEventsPending = false

    private override init() {
        super.init()
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.waitsForConnectivity = true
        configuration.httpMaximumConnectionsPerHost = 1
        configuration.timeoutIntervalForRequest = 180
        configuration.timeoutIntervalForResource = 12 * 60 * 60
        let delegateQueue = OperationQueue()
        delegateQueue.name = "com.spx.chat.ios.media-upload.delegate"
        delegateQueue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: delegateQueue)
        stateQueue.async { self.restorePendingUploads() }
    }

    func upload(file: URL, userID: Int, uploadID: String, totalBytes: Int, chunkSize: Int, totalChunks: Int,
                receivedChunks: Set<Int>, progress: @escaping @Sendable (Double) -> Void) async throws -> JSON {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                stateQueue.async {
                    do {
                        let key = "\(userID):\(uploadID)"
                        guard self.continuations[key] == nil else {
                            throw APIError.server(409, "附件正在上传，请稍候")
                        }
                        let recordURL = try self.recordURL(userID: userID, uploadID: uploadID)
                        var record = try self.loadRecord(at: recordURL, userID: userID, uploadID: uploadID)
                            ?? BackgroundUploadRecord(userID: userID, uploadID: uploadID, filePath: file.path,
                                                      totalBytes: totalBytes, chunkSize: chunkSize, totalChunks: totalChunks,
                                                      completedChunks: [], attempts: [:])
                        guard record.filePath == file.path, record.totalBytes == totalBytes,
                              record.chunkSize == chunkSize, record.totalChunks == totalChunks else {
                            throw APIError.server(409, "上传任务与本地文件不一致，请重新选择附件")
                        }
                        let valid = receivedChunks.filter { $0 >= 0 && $0 < totalChunks }
                        record.completedChunks = valid.sorted()
                        record.attempts = [:]
                        record.paused = false
                        record.retryNotBefore = nil
                        try self.save(record, to: recordURL)
                        self.records[key] = record
                        self.canceledJobs.remove(key)
                        self.continuations[key] = continuation
                        self.progressHandlers[key] = progress
                        self.publishProgress(for: key)
                        self.scheduleNext(for: key)
                    } catch {
                        continuation.resume(throwing: error)
                    }
                }
            }
        } onCancel: {
            self.cancel(userID: userID, uploadID: uploadID)
        }
    }

    func cancel(userID: Int, uploadID: String? = nil) {
        stateQueue.async {
            let keys = self.records.keys.filter { key in
                guard let record = self.records[key] else { return false }
                return record.userID == userID && (uploadID == nil || record.uploadID == uploadID)
            }
            for key in keys {
                self.canceledJobs.insert(key)
                if var record = self.records[key] {
                    record.paused = true
                    self.records[key] = record
                    try? self.persist(record)
                }
                self.continuations.removeValue(forKey: key)?.resume(throwing: CancellationError())
                self.progressHandlers[key] = nil
            }
            self.session.getAllTasks { tasks in
                for task in tasks {
                    guard let tag = BackgroundUploadTag(taskDescription: task.taskDescription), tag.userID == userID,
                          uploadID == nil || tag.uploadID == uploadID else { continue }
                    task.cancel()
                }
            }
        }
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        ServerTrustDelegate.shared.urlSession(session, didReceive: challenge, completionHandler: completionHandler)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        stateQueue.async { self.responseBodies[dataTask.taskIdentifier] = Data() }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        stateQueue.async { self.responseBodies[dataTask.taskIdentifier, default: Data()].append(data) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard let tag = BackgroundUploadTag(taskDescription: task.taskDescription) else { return }
        stateQueue.async {
            guard let record = self.record(for: tag),
                  record.activeTaskID == nil || record.activeTaskID == task.taskIdentifier else { return }
            let completed = Set(record.completedChunks)
            let completedBytes = completed.reduce(0) { sum, index in
                sum + min(record.chunkSize, record.totalBytes - index * record.chunkSize)
            }
            let currentBytes = min(record.chunkSize, record.totalBytes - tag.index * record.chunkSize)
            let sent = min(Int64(currentBytes), max(0, totalBytesSent))
            let fraction = Double(min(record.totalBytes, completedBytes + Int(sent))) / Double(record.totalBytes)
            self.progressHandlers[tag.key]?(fraction)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let tag = BackgroundUploadTag(taskDescription: task.taskDescription) else { return }
        stateQueue.async {
            let body = self.responseBodies.removeValue(forKey: task.taskIdentifier) ?? Data()
            guard var record = self.record(for: tag) else { return }
            guard record.activeTaskID == nil || record.activeTaskID == task.taskIdentifier else { return }
            guard tag.index < record.totalChunks else { return }
            let partURL = try? self.partURL(record: record, index: tag.index)
            if let partURL { try? FileManager.default.removeItem(at: partURL) }
            if self.canceledJobs.contains(tag.key) || record.paused == true { return }

            do {
                if let error { throw error }
                let result = try Self.decodeResponse(body, response: task.response)
                if !result["url"].string.isEmpty {
                    self.complete(tag.key, result: result)
                    return
                }
                var completed = Set(record.completedChunks)
                completed.insert(tag.index)
                record.completedChunks = completed.sorted()
                record.attempts[String(tag.index)] = nil
                record.retryNotBefore = nil
                record.activeTaskID = nil
                try self.persist(record)
                self.records[tag.key] = record
                self.publishProgress(for: tag.key)
                self.scheduleNext(for: tag.key)
            } catch {
                self.retryOrFail(error, tag: tag, record: record)
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        stateQueue.async {
            self.backgroundEventsPending = true
            self.finishBackgroundEventsIfReady()
        }
    }

    private func scheduleNext(for key: String) {
        guard let record = records[key], record.paused != true,
              !scheduling.contains(key), !canceledJobs.contains(key),
              !reservedUsers.contains(record.userID) else { return }
        let completed = Set(record.completedChunks)
        guard let index = (0..<record.totalChunks).first(where: { !completed.contains($0) }) else {
            fetchFinalResult(for: key)
            return
        }
        scheduling.insert(key)
        reservedUsers.insert(record.userID)
        session.getAllTasks { tasks in
            self.stateQueue.async {
                let hasUserTask = tasks.contains { task in
                    task.state != .completed && task.state != .canceling &&
                        BackgroundUploadTag(taskDescription: task.taskDescription)?.userID == record.userID
                }
                guard !hasUserTask, !self.canceledJobs.contains(key),
                      let current = self.records[key], current.paused != true else {
                    self.scheduling.remove(key)
                    self.reservedUsers.remove(record.userID)
                    self.finishBackgroundEventsIfReady()
                    return
                }
                self.createAndStartChunk(index, record: current, key: key)
            }
        }
    }

    private func createAndStartChunk(_ index: Int, record: BackgroundUploadRecord, key: String) {
        Task {
            do {
                let part = try partURL(record: record, index: index)
                let boundary = try Multipart.writeChunk(from: URL(fileURLWithPath: record.filePath), to: part,
                                                        index: index, chunkSize: record.chunkSize,
                                                        totalBytes: record.totalBytes, uploadID: record.uploadID,
                                                        totalChunks: record.totalChunks)
                let request = try await APIClient.shared.backgroundUploadRequest("upload.message.chunk", boundary: boundary, userID: record.userID)
                self.stateQueue.async {
                    guard !self.canceledJobs.contains(key), var current = self.records[key], current.paused != true else {
                        try? FileManager.default.removeItem(at: part)
                        self.scheduling.remove(key)
                        self.reservedUsers.remove(record.userID)
                        self.finishBackgroundEventsIfReady()
                        return
                    }
                    let task = self.session.uploadTask(with: request, fromFile: part)
                    task.taskDescription = BackgroundUploadTag(userID: record.userID, uploadID: record.uploadID, index: index).description
                    task.earliestBeginDate = record.retryNotBefore
                    current.activeTaskID = task.taskIdentifier
                    do {
                        try self.persist(current)
                        self.records[key] = current
                    } catch {
                        task.cancel()
                        self.scheduling.remove(key)
                        self.reservedUsers.remove(record.userID)
                        self.fail(key, error: error)
                        self.finishBackgroundEventsIfReady()
                        return
                    }
                    task.resume()
                    self.scheduling.remove(key)
                    self.reservedUsers.remove(record.userID)
                    self.finishBackgroundEventsIfReady()
                }
            } catch {
                self.stateQueue.async {
                    self.scheduling.remove(key)
                    self.reservedUsers.remove(record.userID)
                    self.retryOrFail(error, tag: BackgroundUploadTag(userID: record.userID, uploadID: record.uploadID, index: index), record: record)
                    self.finishBackgroundEventsIfReady()
                }
            }
        }
    }

    private func retryOrFail(_ error: Error, tag: BackgroundUploadTag, record: BackgroundUploadRecord) {
        let key = tag.key
        if canceledJobs.contains(key) { return }
        let retryable = (error as? URLError) != nil || (error as? APIError)?.retryable == true
        let count = record.attempts[String(tag.index), default: 0]
        guard retryable, count < 5 else {
            fail(key, error: error)
            return
        }
        var updated = record
        updated.attempts[String(tag.index)] = count + 1
        let delay = min(30, 1 << min(count, 4))
        updated.retryNotBefore = Date().addingTimeInterval(TimeInterval(delay))
        try? persist(updated)
        records[key] = updated
        scheduleNext(for: key)
    }

    private func fetchFinalResult(for key: String) {
        guard let record = records[key], !scheduling.contains(key) else { return }
        scheduling.insert(key)
        Task {
            do {
                let status = try await APIClient.shared.uploadStatus(userID: record.userID, uploadID: record.uploadID,
                                                                    totalChunks: record.totalChunks, totalBytes: record.totalBytes)
                self.stateQueue.async {
                    self.scheduling.remove(key)
                    defer { self.finishBackgroundEventsIfReady() }
                    guard !self.canceledJobs.contains(key), self.records[key]?.paused != true,
                          self.records[key] != nil else { return }
                    if !status["result"]["url"].string.isEmpty { self.complete(key, result: status["result"]) }
                    else {
                        var recovery = record
                        recovery.completedChunks = status["received_chunks"].array.map(\.int)
                            .filter { $0 >= 0 && $0 < record.totalChunks }
                        let lastChunk = record.totalChunks - 1
                        if Set(recovery.completedChunks).count == record.totalChunks {
                            recovery.completedChunks.removeAll { $0 == lastChunk }
                        }
                        do {
                            try self.persist(recovery)
                            self.records[key] = recovery
                            self.scheduleNext(for: key)
                        } catch {
                            self.fail(key, error: error)
                        }
                    }
                }
            } catch {
                self.stateQueue.async {
                    self.scheduling.remove(key)
                    self.fail(key, error: error)
                    self.finishBackgroundEventsIfReady()
                }
            }
        }
    }

    private func scheduleWaitingJobs(for userID: Int) {
        for (key, record) in records where record.userID == userID && !canceledJobs.contains(key) {
            scheduleNext(for: key)
        }
    }

    private func complete(_ key: String, result: JSON) {
        guard let record = records[key] else { return }
        progressHandlers[key]?(1)
        progressHandlers[key] = nil
        records[key] = nil
        try? FileManager.default.removeItem(at: recordURL(userID: record.userID, uploadID: record.uploadID))
        continuations.removeValue(forKey: key)?.resume(returning: result)
        scheduleWaitingJobs(for: record.userID)
    }

    private func fail(_ key: String, error: Error) {
        if var record = records[key] {
            record.paused = true
            records[key] = record
            try? persist(record)
        }
        let userID = records[key]?.userID ?? 0
        continuations.removeValue(forKey: key)?.resume(throwing: error)
        progressHandlers[key] = nil
        if userID > 0 { scheduleWaitingJobs(for: userID) }
    }

    private func publishProgress(for key: String) {
        guard let record = records[key] else { return }
        let bytes = Set(record.completedChunks).reduce(0) { sum, index in
            sum + min(record.chunkSize, record.totalBytes - index * record.chunkSize)
        }
        progressHandlers[key]?(Double(bytes) / Double(record.totalBytes))
    }

    private func record(for tag: BackgroundUploadTag) -> BackgroundUploadRecord? {
        if let record = records[tag.key] { return record }
        guard let url = try? recordURL(userID: tag.userID, uploadID: tag.uploadID),
              let record = try? loadRecord(at: url, userID: tag.userID, uploadID: tag.uploadID) else { return nil }
        records[tag.key] = record
        return record
    }

    private func recordURL(userID: Int, uploadID: String) throws -> URL {
        guard userID > 0, UUID(uuidString: uploadID) != nil else { throw APIError.invalidResponse }
        return try MediaFiles.directory(userID: userID, component: "upload-state").appendingPathComponent(uploadID + ".json")
    }

    private func partURL(record: BackgroundUploadRecord, index: Int) throws -> URL {
        try MediaFiles.directory(userID: record.userID, component: "upload-parts")
            .appendingPathComponent("\(record.uploadID)-\(index).multipart")
    }

    private func loadRecord(at url: URL, userID: Int, uploadID: String) throws -> BackgroundUploadRecord? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let record = try JSONDecoder().decode(BackgroundUploadRecord.self, from: Data(contentsOf: url))
        let attachmentRoot = try MediaFiles.directory(userID: userID, component: "attachments").standardizedFileURL
        let source = URL(fileURLWithPath: record.filePath).standardizedFileURL
        guard record.userID == userID, record.uploadID == uploadID,
              record.totalBytes > 0, record.totalBytes <= 1_073_741_824, record.chunkSize == 1_048_576,
              record.totalChunks == (record.totalBytes + record.chunkSize - 1) / record.chunkSize,
              record.completedChunks.allSatisfy({ $0 >= 0 && $0 < record.totalChunks }),
              source.deletingLastPathComponent() == attachmentRoot else {
            throw APIError.server(409, "本地上传任务无效，请重新选择附件")
        }
        return record
    }

    private func persist(_ record: BackgroundUploadRecord) throws {
        try save(record, to: recordURL(userID: record.userID, uploadID: record.uploadID))
    }

    private func restorePendingUploads() {
        guard let userID = Int(Keychain.read("lastUserID") ?? ""), userID > 0, Keychain.read("token") != nil,
              let directory = try? MediaFiles.directory(userID: userID, component: "upload-state"),
              let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return }
        for file in files where file.pathExtension == "json" {
            let uploadID = file.deletingPathExtension().lastPathComponent
            guard UUID(uuidString: uploadID) != nil,
                  let record = try? loadRecord(at: file, userID: userID, uploadID: uploadID),
                  record.paused != true else { continue }
            records["\(userID):\(uploadID)"] = record
        }
        scheduleWaitingJobs(for: userID)
    }

    private func finishBackgroundEventsIfReady() {
        guard backgroundEventsPending, scheduling.isEmpty else { return }
        backgroundEventsPending = false
        SPXAppDelegate.finishBackgroundEvents()
    }

    private func save(_ record: BackgroundUploadRecord, to url: URL) throws {
        let data = try JSONEncoder().encode(record)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func decodeResponse(_ data: Data, response: URLResponse?) throws -> JSON {
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        if http.statusCode == 401 { throw APIError.unauthorized }
        guard let envelope = try? JSONDecoder().decode(APIEnvelope.self, from: data) else {
            throw APIError.server(http.statusCode, "服务器暂时不可用（\(http.statusCode)）")
        }
        guard (200..<300).contains(http.statusCode), envelope.code.string == "0" else {
            throw APIError.server(http.statusCode, envelope.message ?? "附件上传失败")
        }
        return envelope.data ?? .null
    }
}
