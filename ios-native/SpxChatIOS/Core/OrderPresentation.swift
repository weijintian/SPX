import Foundation

enum OrderPresentation {
    static func address(_ order: Record) -> Record {
        if !order["address"].object.isEmpty { return order["address"].record }
        let raw = order["shipping_address"].string.trimmingCharacters(in: .whitespacesAndNewlines)
        if let data = raw.data(using: .utf8), let value = try? JSONDecoder().decode(JSON.self, from: data), !value.object.isEmpty { return value.record }
        return raw.isEmpty ? Record() : Record(["detail": .string(raw.hasPrefix("{") ? "收货地址暂时无法读取，请刷新后重试" : raw)])
    }
    static func addressLine(_ address: Record) -> String {
        ["province", "city", "district", "detail"].map { address[$0].string }.filter { !$0.isEmpty }.joined(separator: " ")
    }
}

/// Reuse the existing seller-authorized endpoint; never request a buyer-only order or redemption code.
enum MerchantOrderReader {
    static func find(id: Int, startingPage: Int, pageSize: Int = 30, fetch: (Int) async throws -> [Record]) async throws -> (order: Record, page: Int) {
        var page = max(1, startingPage)
        var visited = Set<Int>()
        while !visited.contains(page) {
            try Task.checkCancellation(); visited.insert(page)
            let rows = try await fetch(page)
            if let order = rows.first(where: { $0.id == id }) { return (order, page) }
            guard let newest = rows.map(\.id).max(), let oldest = rows.map(\.id).min() else {
                if page != 1 { page = 1; continue }
                break
            }
            if id > newest { if page == 1 { break }; page = 1 }
            else if id < oldest, rows.count == pageSize { page += 1 }
            else { break }
        }
        throw APIError.server(404, "订单不存在或已不可访问，请返回列表刷新")
    }
}
