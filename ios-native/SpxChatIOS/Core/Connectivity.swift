import Foundation
import Network
import SwiftUI

/// Reports whether the device currently has a usable network path so screens can
/// explain why a send or refresh is stuck instead of silently failing.
@MainActor final class Connectivity: ObservableObject {
    static let shared = Connectivity()
    @Published private(set) var online = true
    private let monitor = NWPathMonitor()
    private var started = false

    private init() {}

    func start() {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { [weak self] path in
            let value = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                if self.online != value { self.online = value }
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.spx.chat.connectivity"))
    }
}

/// Thin bar shown above content while the device is offline.
struct OfflineBanner: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi.slash").spxFont(12, weight: .semibold)
            Text("当前无网络连接，消息将在恢复后自动同步").spxFont(12, weight: .medium).lineLimit(1).minimumScaleFactor(0.85)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Color.orange)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Color.orange.opacity(0.12))
        .overlay(alignment: .bottom) { Rectangle().fill(Color.orange.opacity(0.25)).frame(height: 0.5) }
        .accessibilityLabel("当前无网络连接")
    }
}
