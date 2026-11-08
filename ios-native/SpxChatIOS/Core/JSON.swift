import Foundation

enum JSON: Codable, Hashable, Sendable {
    case null, string(String), number(Decimal), bool(Bool), array([JSON]), object([String: JSON])
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Decimal.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSON].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSON].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }
    var string: String {
        switch self {
        case .string(let s): return s
        case .number(let n): return NSDecimalNumber(decimal:n).stringValue
        case .bool(let b): return b ? "1" : "0"
        default: return ""
        }
    }
    var int: Int { Int(string) ?? 0 }
    var decimal: Decimal? { Decimal(string:string,locale:Locale(identifier:"en_US_POSIX")) }
    var flag: Bool { self == .bool(true) || string == "1" || string == "true" }
    var array: [JSON] { if case .array(let a) = self { return a }; return [] }
    var object: [String: JSON] { if case .object(let o) = self { return o }; return [:] }
    var record: Record { Record(object) }
    var records: [Record] { array.map(\.record) }
    var list: [Record] {
        if case .array = self { return records }
        for key in ["items", "list", "rows", "products", "orders", "messages", "records", "assets"] {
            if case .array = self[key] { return self[key].records }
        }
        return []
    }
    func text(_ keys: String...) -> String {
        keys.map { self[$0].string }.first(where: { !$0.isEmpty }) ?? ""
    }
    subscript(_ key: String) -> JSON { object[key] ?? .null }
}
extension JSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral {
    init(stringLiteral value:String) { self = .string(value) }
    init(integerLiteral value:Int) { self = .number(Decimal(value)) }
    init(booleanLiteral value:Bool) { self = .bool(value) }
}
struct Record: Codable, Identifiable, Hashable, Sendable {
    var fields: [String: JSON]
    init(_ fields:[String:JSON] = [:]) { self.fields = fields }
    init(from decoder: Decoder) throws { fields = try [String:JSON](from:decoder) }
    func encode(to encoder: Encoder) throws { try fields.encode(to:encoder) }
    var id: Int { self["id"].int }
    subscript(_ key:String) -> JSON { get {fields[key] ?? .null} set {fields[key] = newValue} }
    func text(_ keys:String...) -> String { keys.map {self[$0].string}.first(where:{!$0.isEmpty}) ?? "" }
    var name: String { text("display_name","contact_remark","nickname","name","title","username","product_name") }
    var avatar: String { text("avatar_url","logo_url") }
    var image: String { text("image_url", "cover_url", "image", "logo_url") }
    var isGroup: Bool { self["kind"].string == "group" }
    var key: String { "\(isGroup ? "group" : "chat")-\(id)" }
    func applyingGroupRemark(peerID: Int, remark: String) -> Record {
        guard peerID > 0 else { return self }
        var value = self
        if self["sender_id"].int == peerID {
            let original = text("original_nickname", "nickname")
            let display = remark.isEmpty ? original : remark
            value["original_nickname"] = .string(original)
            value["contact_remark"] = .string(remark)
            value["display_name"] = .string(display)
            value["nickname"] = .string(display)
        }
        if self["reply"]["sender_id"].int == peerID {
            var reply = self["reply"].record
            let original = reply.text("original_nickname", "nickname")
            reply["original_nickname"] = .string(original)
            reply["nickname"] = .string(remark.isEmpty ? original : remark)
            value["reply"] = .object(reply.fields)
        }
        return value
    }
}
extension Dictionary where Key == String, Value == JSON {
    mutating func set(_ key:String, _ value:String) { self[key] = .string(value) }
    mutating func set(_ key:String, _ value:Int) { self[key] = .number(Decimal(value)) }
}


extension Record {
    /// The contacts endpoint calls this field remark; other record types give it different meanings.
    var normalizedContact: Record {
        var value = self
        let raw = (fields["remark"] != nil ? self["remark"].string : self["contact_remark"].string).trimmingCharacters(in: .whitespacesAndNewlines)
        let remark = ["null", "undefined"].contains(raw.lowercased()) ? "" : raw
        value["contact_remark"] = .string(remark)
        value["display_name"] = .string(remark.isEmpty ? text("nickname", "name", "username") : remark)
        return value
    }
}
