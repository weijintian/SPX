import SwiftUI
import UserNotifications
import UIKit

struct MessageNotificationSettingsView: View {
    @ObservedObject private var notifications = MessageNotifications.shared
    @Environment(\.scenePhase) private var phase
    @AppStorage("spx.notifications.enabled") private var enabled = true
    @AppStorage("spx.notifications.preview") private var preview = true
    @AppStorage("spx.notifications.badge") private var badge = true
    private var allowed: Bool { [.authorized, .provisional, .ephemeral].contains(notifications.authorization) }
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "消息通知", subtitle: "管理新消息提醒与桌面未读数量", icon: "bell.badge")
            SPXPanel("系统通知权限") {
                HStack { Text("通知权限").font(.subheadline); Spacer(); SPXStatusPill(title: allowed ? "已允许" : notifications.authorization == .notDetermined ? "尚未申请" : "未允许", color: allowed ? SPXColor.success : SPXColor.muted) }
                if notifications.authorization == .notDetermined { Button("允许消息通知") { Task { await notifications.requestPermissionIfNeeded() } }.buttonStyle(.bordered) }
                else { Link(destination: URL(string: UIApplication.openSettingsURLString)!) { SPXSettingsRow(title: "前往系统通知设置", icon: "settings") }.buttonStyle(.plain) }
            }
            SPXPanel("消息提醒", footer: "正在阅读的聊天不重复弹出通知；免打扰遵循聊天设置。锁屏预览和声音也受系统设置控制。") {
                Toggle("新消息通知", isOn: $enabled)
                Divider()
                Toggle("通知显示消息内容", isOn: $preview).disabled(!enabled)
                Divider()
                Toggle("桌面图标未读数量", isOn: $badge)
            }.tint(SPXColor.primary)
            SPXPanel("提醒说明") {
                Text("打开聊天并成功标记已读后，未读数量同步更新。当前支持在线消息提醒；App 退出或长时间在后台后的提醒暂不可用。").font(.footnote).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
            }
        }.navigationTitle("消息通知").spxPage()
            .task { await notifications.updatePermission() }
            .onChange(of: phase) { _, value in if value == .active { Task { await notifications.updatePermission() } } }
            .onChange(of: enabled) { _, _ in notifications.preferencesChanged() }
            .onChange(of: preview) { _, _ in notifications.preferencesChanged() }
            .onChange(of: badge) { _, _ in notifications.preferencesChanged() }
    }
}
