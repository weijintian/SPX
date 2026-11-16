import SwiftUI

/// 红包气泡配色：可抢保持鲜红，已领完/已领过压暗为灰红。
enum RedPacketPalette {
    static let brightFill = Color(red: 0.894, green: 0.333, blue: 0.247)
    static let dimFill = Color(red: 0.690, green: 0.541, blue: 0.518)
    static let goldBright = Color(red: 1.000, green: 0.875, blue: 0.608)
    static let goldDim = Color(red: 0.914, green: 0.839, blue: 0.769)
    static let subBright = Color(red: 1.000, green: 0.882, blue: 0.839)
    static let subDim = Color(red: 0.878, green: 0.796, blue: 0.741)

    static func fill(dim: Bool) -> Color { dim ? dimFill : brightFill }
    static func gold(dim: Bool) -> Color { dim ? goldDim : goldBright }
    static func sub(dim: Bool) -> Color { dim ? subDim : subBright }
}

/// 红包状态缓存：一次批量拉取多个红包状态，供聊天气泡判断是否压暗。
final class RedPacketStatus: ObservableObject {
    static let shared = RedPacketStatus()

    private struct Entry {
        var status: String
        var myAmount: String
    }

    @Published private(set) var revision = 0
    private var entries: [String: Entry] = [:]
    private var pending: Set<String> = []
    private var scheduled = false

    private init() {}

    func request(_ id: String) {
        guard id.count == 32 else { return }
        if entries[id] != nil { return }
        if pending.contains(id) { return }
        pending.insert(id)
        if scheduled { return }
        scheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { [weak self] in
            self?.flush()
        }
    }

    private func flush() {
        scheduled = false
        let ids = Array(pending.prefix(50))
        pending.removeAll()
        guard !ids.isEmpty else { return }
        let joined = ids.joined(separator: ",")
        Task { [weak self] in
            guard let self else { return }
            let data = try? await APIClient.shared.get("redpackets.states", ["ids": joined])
            guard let result = data else { return }
            DispatchQueue.main.async {
                for id in ids {
                    let row = result[id]
                    let status = row["status"].string
                    if status.isEmpty { continue }
                    self.entries[id] = Entry(status: status, myAmount: row["my_amount"].string)
                }
                self.revision &+= 1
            }
        }
    }

    func isDim(_ id: String, mine: Bool) -> Bool {
        if mine { return false }
        guard let entry = entries[id] else { return false }
        if !entry.myAmount.isEmpty { return true }
        return entry.status != "active"
    }

    func reason(_ id: String, mine: Bool) -> String {
        if mine { return "已存入钱包" }
        guard let entry = entries[id] else { return "轻触打开红包" }
        if !entry.myAmount.isEmpty { return "已领取 " + RedPacketStatus.shortAmount(entry.myAmount) }
        if entry.status == "completed" { return "红包已领完" }
        if entry.status == "expired" { return "红包已过期" }
        return "轻触打开红包"
    }

    /// 领到红包后立刻刷新气泡，不用等下一次列表刷新。
    func remember(id: String, status: String, myAmount: String) {
        guard id.count == 32 else { return }
        entries[id] = Entry(status: status, myAmount: myAmount)
        revision &+= 1
    }

    private static func shortAmount(_ raw: String) -> String {
        guard let value = Decimal(string: raw) else { return raw }
        return NSDecimalNumber(decimal: value).stringValue
    }
}
