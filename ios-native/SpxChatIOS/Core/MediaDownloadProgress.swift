import Foundation

/// Bound UI work while preserving an indeterminate download's -1 sentinel.
final class DownloadProgressGate: @unchecked Sendable {
    private let lock = NSLock()
    private var lastValue: Double?
    private var lastUpdate: TimeInterval = -.infinity
    func accept(_ value: Double, now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard value.isFinite else { return false }
        let value = value < 0 ? -1 : min(1, value)
        if let lastValue {
            guard value > lastValue else { return false }
            guard lastValue < 0 || value == 1 || (value - lastValue >= 0.01 && now - lastUpdate >= 0.1) else { return false }
        }
        lastValue = value; lastUpdate = now
        return true
    }
}

final class MediaDownloadProgress: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let update: @Sendable (Double) -> Void
    private let gate = DownloadProgressGate()
    init(update: @escaping @Sendable (Double) -> Void) { self.update = update }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) { }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let fraction = totalBytesExpectedToWrite > 0 ? min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) : -1
        if gate.accept(fraction) { update(fraction) }
    }
}
