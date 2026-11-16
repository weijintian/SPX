import Foundation

/// 视频整段预缓存：第一次播放时把完整文件落到本地，
/// 第二次打开同一视频直接读文件，不再走网络分段请求。
enum VideoCacheWarmer {
    private static var warming: Set<String> = []
    private static let lock = NSLock()

    static func warm(source: String, userID: Int) {
        guard userID > 0, !source.isEmpty else { return }
        let key = "\(userID)|\(source)"
        lock.lock()
        if warming.contains(key) {
            lock.unlock()
            return
        }
        warming.insert(key)
        lock.unlock()

        Task(priority: .utility) {
            defer {
                lock.lock()
                warming.remove(key)
                lock.unlock()
            }
            if let existing = (try? await MediaCache.shared.cachedFile(source, userID: userID)) ?? nil,
               FileManager.default.fileExists(atPath: existing.path) {
                return
            }
            _ = try? await MediaCache.shared.file(source, userID: userID)
        }
    }
}
