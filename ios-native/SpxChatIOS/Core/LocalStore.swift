import Foundation

actor LocalStore {
    static let shared = LocalStore()
    private let root:URL
    init() {
        root = FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("SPX",isDirectory:true)
        try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        var directory = root;var values = URLResourceValues();values.isExcludedFromBackup = true;try? directory.setResourceValues(values)
    }
    private func url(_ key:String,userID:Int) -> URL {root.appendingPathComponent("u\(userID)",isDirectory:true).appendingPathComponent(Data(key.utf8).base64EncodedString().replacingOccurrences(of:"/",with:"_") + ".json")}
    func read<T:Decodable>(_ key:String,userID:Int) -> T? {try? JSONDecoder().decode(T.self,from:Data(contentsOf:url(key,userID:userID)))}
    func write<T:Encodable>(_ value:T,key:String,userID:Int) {
        do { try save(value, key: key, userID: userID) }
        catch { }
    }
    /// Durable queue/financial recovery records must succeed before any request is issued.
    func save<T: Encodable>(_ value: T, key: String, userID: Int) throws {
        guard userID > 0 else { throw APIError.unauthorized }
        let target = url(key, userID: userID)
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(value).write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    /// Atomically prevent two open pages from overwriting the same pending operation.
    func reserveFinancial(_ body: Record, key: String, userID: Int) throws -> Record {
        let existing: Record? = read(key, userID: userID)
        guard existing == nil else { throw APIError.server(409, "还有一笔结果待核对的申请，请重新读取状态") }
        var pending = body
        pending["client_request_id"] = .string(UUID().uuidString.lowercased())
        try save(pending, key: key, userID: userID)
        return pending
    }
    /// An old page may finish after a new request was reserved; only clear its own ID.
    func completeFinancial(_ key: String, userID: Int, requestID: String) -> Bool {
        let saved: Record? = read(key, userID: userID)
        guard !requestID.isEmpty, saved?["client_request_id"].string == requestID else { return false }
        remove(key, userID: userID)
        return true
    }
    func remove(_ key:String,userID:Int) {try? FileManager.default.removeItem(at:url(key,userID:userID))}
    func updateGroupContactRemark(peerID: Int, remark: String, userID: Int) {
        guard userID > 0, peerID > 0 else { return }
        let directory = root.appendingPathComponent("u\(userID)", isDirectory: true)
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "json" {
            let encoded = file.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "_", with: "/")
            guard let keyData = Data(base64Encoded: encoded), let key = String(data: keyData, encoding: .utf8), key.hasPrefix("messages-g-"),
                  let data = try? Data(contentsOf: file) else { continue }
            if key.hasSuffix("-sync-v2"), var snapshot = try? JSONDecoder().decode(ConversationSnapshot.self, from: data) {
                let updated = snapshot.messages.map { $0.applyingGroupRemark(peerID: peerID, remark: remark) }
                if updated != snapshot.messages { snapshot.messages = updated; try? save(snapshot, key: key, userID: userID) }
            } else if let rows = try? JSONDecoder().decode([Record].self, from: data) {
                let updated = rows.map { $0.applyingGroupRemark(peerID: peerID, remark: remark) }
                if updated != rows { try? save(updated, key: key, userID: userID) }
            }
        }
    }
}

enum Money {
    static let usdCNY:Decimal = Decimal(string:"6.7")!
    static func withdrawalFee(_ amount: Decimal) -> Decimal {
        var fee = amount / 100, result = Decimal()
        NSDecimalRound(&result, &fee, 18, .down)
        return result
    }
    static func estimate(cny:String,usd:String) -> String? {
        guard let c = Decimal(string:cny,locale:Locale(identifier:"en_US_POSIX")),let u = Decimal(string:usd,locale:Locale(identifier:"en_US_POSIX")),c >= 0,u > 0 else {return nil}
        return format(c / (u * usdCNY),scale:6)
    }
    static func format(_ value:Decimal,scale:Int = 2) -> String {
        var input = value;var rounded = Decimal();NSDecimalRound(&rounded,&input,scale,.plain)
        return NSDecimalNumber(decimal:rounded).stringValue
    }
    static func positive(_ text:String,scale:Int = 8) -> Bool {
        let parts = text.split(separator:".",omittingEmptySubsequences:false)
        guard !parts.isEmpty,parts.count <= 2,!parts[0].isEmpty,parts.allSatisfy({$0.allSatisfy({ "0123456789".contains($0) })}),parts.count < 2 || (!parts[1].isEmpty && parts[1].count <= scale),let d = Decimal(string:text),d > 0 else {return false};return true
    }
}

