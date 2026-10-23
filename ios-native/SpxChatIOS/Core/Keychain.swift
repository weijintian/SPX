import Foundation
import Security

enum Keychain {
    static func read(_ account:String) -> String? {
        let q:[String:Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"com.spx.chat.ios",kSecAttrAccount as String:account,kSecReturnData as String:true,kSecMatchLimit as String:kSecMatchLimitOne]
        var item: CFTypeRef?;guard SecItemCopyMatching(q as CFDictionary,&item) == errSecSuccess,let data = item as? Data else {return nil};return String(data:data,encoding:.utf8)
    }
    static func write(_ value:String?,account:String) throws {
        let q:[String:Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"com.spx.chat.ios",kSecAttrAccount as String:account]
        SecItemDelete(q as CFDictionary)
        guard let value else {return}
        var add = q;add[kSecValueData as String] = Data(value.utf8);add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary,nil)
        guard status == errSecSuccess else {throw APIError.server(Int(status),"无法安全保存登录信息")}
    }
    static var deviceID:String {
        if let saved = read("deviceID") {return saved}
        let id = "ios-" + UUID().uuidString.lowercased();try? write(id,account:"deviceID");return id
    }
}

