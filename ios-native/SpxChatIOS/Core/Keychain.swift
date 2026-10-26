import Foundation
import Security

enum Keychain {
    static func read(_ account:String) -> String? {
        let q:[String:Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"com.spx.chat.ios",kSecAttrAccount as String:account,kSecReturnData as String:true,kSecMatchLimit as String:kSecMatchLimitOne]
        var item: CFTypeRef?;guard SecItemCopyMatching(q as CFDictionary,&item) == errSecSuccess,let data = item as? Data else {return nil};return String(data:data,encoding:.utf8)
    }
    static func write(_ value:String?,account:String) throws {
        let q:[String:Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"com.spx.chat.ios",kSecAttrAccount as String:account]
        guard let value else {
            let status = SecItemDelete(q as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw APIError.server(Int(status), "无法清除登录信息") }
            return
        }
        let attributes: [String: Any] = [kSecValueData as String: Data(value.utf8),
                                        kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let update = SecItemUpdate(q as CFDictionary, attributes as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw APIError.server(Int(update), "无法安全保存登录信息") }
        let status = SecItemAdd(q.merging(attributes) { _, value in value } as CFDictionary, nil)
        guard status == errSecSuccess else { throw APIError.server(Int(status), "无法安全保存登录信息") }
    }
    static func saveSession(token: String, userID: Int) throws {
        let previousID = read("lastUserID")
        try write(String(userID), account: "lastUserID")
        do { try write(token, account: "token") }
        catch { try? write(previousID, account: "lastUserID"); throw error }
    }
    static var deviceID:String {
        if let saved = read("deviceID") {return saved}
        let id = "ios-" + UUID().uuidString.lowercased();try? write(id,account:"deviceID");return id
    }
}

