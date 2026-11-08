import Foundation

struct HTTPContentRange: Equatable {
    let start: Int
    let end: Int
    let total: Int
    var count: Int { end - start + 1 }

    init?(_ value: String?) {
        guard let value else { return nil }
        let parts = value.split(whereSeparator: { $0.isWhitespace })
        guard parts.count == 2, parts[0].lowercased() == "bytes" else { return nil }
        let rangeAndTotal = parts[1].split(separator: "/", omittingEmptySubsequences: false)
        guard rangeAndTotal.count == 2 else { return nil }
        let bounds = rangeAndTotal[0].split(separator: "-", omittingEmptySubsequences: false)
        guard bounds.count == 2,
              let start = Int(bounds[0]), let end = Int(bounds[1]), let total = Int(rangeAndTotal[1]),
              start >= 0, end >= start, total > end else { return nil }
        self.start = start
        self.end = end
        self.total = total
    }

    func matches(offset: Int, maximumCount: Int) -> Bool {
        start == offset && count <= maximumCount
    }
}
