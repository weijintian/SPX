import SwiftUI

extension View {
    /// Pull-to-refresh always asks the server, including child async reads.
    func spxRefreshable(action: @escaping @Sendable () async -> Void) -> some View {
        refreshable {
            await APIReadContext.$forceRefresh.withValue(true) { await action() }
        }
    }
}
