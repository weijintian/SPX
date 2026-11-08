import Foundation

struct MentionToken: Codable, Equatable, Identifiable {
    let id: UUID
    let userID: Int
    let name: String
    var location: Int
    let length: Int
    init(userID: Int, name: String, location: Int) {
        id = UUID(); self.userID = userID; self.name = name; self.location = location; length = ("@" + name).utf16.count
    }
    var json: JSON { .object(["id": .string(id.uuidString), "userID": .number(Decimal(userID)), "name": .string(name), "location": .number(Decimal(location)), "length": .number(Decimal(length))]) }
}

enum MentionRanges {
    static func validated(_ tokens: [MentionToken], in text: String) -> [MentionToken] {
        let source = text as NSString
        return tokens.filter { token in
            guard token.userID > 0 || token.userID == -1, !token.name.isEmpty,
                  token.location >= 0, token.length > 0, token.location <= source.length,
                  token.length <= source.length - token.location,
                  source.substring(with: NSRange(location: token.location, length: token.length)) == "@" + token.name else { return false }
            let end = token.location + token.length
            if end == source.length { return true }
            let tail = source.substring(from: end)
            guard let scalar = tail.unicodeScalars.first else { return true }
            return CharacterSet.whitespacesAndNewlines.contains(scalar) || CharacterSet.punctuationCharacters.contains(scalar)
        }
    }
    /// Track the actual edited UTF-16 range, including emoji and edits before a mention.
    static func updating(_ tokens: [MentionToken], from old: String, to new: String) -> [MentionToken] {
        guard old != new else { return validated(tokens, in: new) }
        let before = Array(old.utf16), after = Array(new.utf16)
        var start = 0
        while start < min(before.count, after.count), before[start] == after[start] { start += 1 }
        var suffix = 0
        while suffix < before.count - start, suffix < after.count - start,
              before[before.count - suffix - 1] == after[after.count - suffix - 1] { suffix += 1 }
        var oldEnd = before.count - suffix, newEnd = after.count - suffix
        let delta = newEnd - oldEnd
        let valid = validated(tokens, in: old)
        let boundaries = Set(valid.flatMap { [$0.location, $0.location + $0.length] })
        var best: [MentionToken] = [], score = -1
        // Repeated/prefix names can make a textual edit ambiguous. Consider equivalent token-boundary edits.
        while true {
            if score < 0 || boundaries.contains(start) {
                let mapping = applying(valid, to: new, start: start, oldEnd: oldEnd, delta: delta)
                let candidateScore = mapping.reduce(0) { $0 + $1.length }
                if candidateScore > score { best = mapping; score = candidateScore }
                else if candidateScore == score { best = best.filter { mapping.contains($0) } }
            }
            guard start > 0, oldEnd > 0, newEnd > 0, before[oldEnd - 1] == after[newEnd - 1] else { break }
            start -= 1; oldEnd -= 1; newEnd -= 1
        }
        return best
    }
    private static func applying(_ tokens: [MentionToken], to new: String, start: Int, oldEnd: Int, delta: Int) -> [MentionToken] {
        let updated = tokens.compactMap { token -> MentionToken? in
            var token = token
            if token.location + token.length <= start { return token }
            if token.location >= oldEnd { token.location += delta; return token }
            return nil
        }
        return validated(updated, in: new)
    }
    static func userIDs(_ tokens: [MentionToken], in text: String) -> [Int] { Array(Set(validated(tokens, in: text).map(\.userID))).sorted() }
    static func restoringLegacy(_ names: [Int: String], in text: String) -> [MentionToken] {
        var tokens: [MentionToken] = []
        let source = text as NSString
        for (id, name) in names.sorted(by: { $0.value.utf16.count > $1.value.utf16.count }) where names.values.filter({ $0 == name }).count == 1 {
            let needle = "@" + name
            guard let regex = try? NSRegularExpression(pattern: NSRegularExpression.escapedPattern(for: needle)) else { continue }
            for match in regex.matches(in: text, range: NSRange(location: 0, length: source.length)) {
                let candidate = MentionToken(userID: id, name: name, location: match.range.location)
                guard !validated([candidate], in: text).isEmpty,
                      !tokens.contains(where: { NSIntersectionRange(NSRange(location: $0.location, length: $0.length), match.range).length > 0 }) else { continue }
                tokens.append(candidate)
            }
        }
        return tokens.sorted { $0.location < $1.location }
    }
    static func removing(userID: Int, tokens: [MentionToken], from text: String) -> (text: String, tokens: [MentionToken]) {
        var value = text
        var remaining = validated(tokens, in: text)
        for token in remaining.filter({ $0.userID == userID }).sorted(by: { $0.location > $1.location }) {
            let source = value as NSString
            var length = token.length
            if token.location + length < source.length, source.substring(with: NSRange(location: token.location + length, length: 1)) == " " { length += 1 }
            let next = source.replacingCharacters(in: NSRange(location: token.location, length: length), with: "")
            let end = token.location + length
            remaining = remaining.compactMap { other in
                guard other.id != token.id else { return nil }
                var updated = other
                if updated.location >= end { updated.location -= length }
                else if updated.location + updated.length > token.location { return nil }
                return updated
            }
            remaining = validated(remaining, in: next); value = next
        }
        return (value, remaining)
    }
}
