import Foundation

extension Notification.Name {
    static let spxWalletChanged = Notification.Name("spx.wallet.changed")
    static let spxOrdersChanged = Notification.Name("spx.orders.changed")
}

/// Events carry the authenticated request generation, never a token or payment amount.
struct AccountMutation: Sendable {
    let session: UUID
    static func affectsWallet(_ action: String) -> Bool {
        action.hasPrefix("wallet.") || action.hasPrefix("staking.") || action.hasPrefix("redpackets.") ||
        action.hasPrefix("merchant.deposit.") || action.hasPrefix("merchant.order.") ||
        ["orders.redeem", "orders.cancel", "orders.receive", "financial.resolve", "checkout.resolve", "merchant.close"].contains(action)
    }
    static func affectsOrders(_ action: String) -> Bool {
        action.hasPrefix("orders.") || action.hasPrefix("merchant.order.") || ["checkout.resolve", "product.review"].contains(action)
    }
    static func isCurrent(_ notification: Notification) async -> Bool {
        guard let event = notification.object as? AccountMutation else { return false }
        return await APIClient.shared.sessionIdentity() == event.session
    }
}
