import Foundation

enum AppLink: Equatable, Identifiable {
    case user(String), group(String), product(Int), conversation(ChatNotificationRoute)
    var id: String { switch self { case .user(let id): return "user:" + id; case .group(let code): return "group:" + code; case .product(let id): return "product:" + String(id); case .conversation(let route): return "conversation:" + route.id } }
    static func parse(_ url: URL) -> AppLink? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let values = Dictionary(components.queryItems?.map { ($0.name, $0.value ?? "") } ?? [], uniquingKeysWith: { first, _ in first })
        if url.scheme?.lowercased() == "spxchat" {
            let value = values["id"] ?? values["code"] ?? url.lastPathComponent
            switch url.host?.lowercased() {
            case "user": if validID(value) { return .user(value) }
            case "group": if validGroup(value) { return .group(value.uppercased()) }
            case "product": if let id = Int(value), id > 0 { return .product(id) }
            default: break
            }
        }
        if url.scheme?.lowercased() == "https", ["spx001.top", "www.spx001.top", "70.39.201.191"].contains(url.host?.lowercased() ?? ""), url.path == "/spxchat/product-share.php", let id = Int(values["id"] ?? ""), id > 0 { return .product(id) }
        return nil
    }
    private static func validID(_ value: String) -> Bool { !value.isEmpty && value.count <= 64 && value.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) } }
    private static func validGroup(_ value: String) -> Bool { value.count == 6 && validID(value) }
}
