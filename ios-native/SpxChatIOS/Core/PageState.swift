import SwiftUI

@MainActor final class PageState: ObservableObject {
    @Published private(set) var rows: [Record] = []
    @Published private(set) var page = 0
    @Published private(set) var more = false
    @Published private(set) var busy = false
    private var requestID = UUID()

    func load(reset: Bool, pageSize: Int = 30, paginated: Bool = true, request: (Int) async throws -> [Record]) async throws {
        if busy && !reset { return }
        let id = UUID(); requestID = id; busy = true
        defer { if requestID == id { busy = false } }
        let next = reset ? 1 : page + 1
        do {
            let incoming = try await request(next)
            guard requestID == id, !Task.isCancelled else { return }
            if reset { rows = incoming }
            else {
                // Audit rows have no numeric id; packet ids are hexadecimal strings.
                var ids = Set(rows.map { $0["id"].string }.filter { !$0.isEmpty })
                for row in incoming {
                    let key = row["id"].string
                    if key.isEmpty {
                        // Identical audit events can be distinct rows. Preserve all of them.
                        rows.append(row)
                    } else if ids.insert(key).inserted { rows.append(row) }
                }
            }
            page = next; more = paginated && incoming.count == pageSize
        } catch is CancellationError { }
        catch { if requestID == id { throw error } }
    }
}
