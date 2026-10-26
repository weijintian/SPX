import SwiftUI

struct SupportView: View {
    @State private var faq: [Record] = []
    @State private var tickets: [Record] = []
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "帮助与反馈", subtitle: "查找常见问题，或提交工单联系平台", icon: "questionmark.bubble")
            SPXPanel("联系与反馈") {
                NavigationLink { SupportEditor() } label: { SPXSettingsRow(title: "提交客服工单", icon: "bubble.left.and.text.bubble.right") }
                NavigationLink { SupportEditor(feedback: true) } label: { SPXSettingsRow(title: "意见反馈", icon: "square.and.pencil") }
                NavigationLink { QuestionsView() } label: { SPXSettingsRow(title: "我的提问", icon: "questionmark.bubble") }
            }.buttonStyle(.plain)
            SPXPanel("常见问题") {
                ForEach(faq) { item in DisclosureGroup(item["question"].string) { Text(item["answer"].string).font(.subheadline).foregroundStyle(SPXColor.muted).textSelection(.enabled).padding(.top, 8) } }
                if faq.isEmpty { Text("暂无常见问题，可直接提交工单。").font(.subheadline).foregroundStyle(SPXColor.muted) }
            }
            SPXSectionTitle(title: "我的工单")
            ForEach(tickets) { ticket in SPXCard { DisclosureGroup {
                VStack(alignment: .leading, spacing: 12) { Text(ticket["content"].string); InfoRow(title: "状态", value: ticket["status"].string); if !ticket["admin_reply"].string.isEmpty { Text(ticket["admin_reply"].string).padding(12).background(SPXColor.tint, in: RoundedRectangle(cornerRadius: 12)) } }.padding(.top, 12)
            } label: { VStack(alignment: .leading, spacing: 6) { Text(ticket["subject"].string).font(.system(size: 15, weight: .semibold)); Text(ticket["created_at"].string).font(.caption).foregroundStyle(SPXColor.muted) } } } }
            if tickets.isEmpty { EmptyState(title: "暂无客服工单", description: "提交后可在这里查看回复", icon: "bubble.left") }
        }.navigationTitle("帮助与反馈").task { await load() }.spxRefreshable { await load() }.errorAlert($error)
    }
    private func load() async { do { faq = try await APIClient.shared.get("faq").list; tickets = try await APIClient.shared.get("support.tickets").list } catch { self.error = error.localizedDescription } }
}

struct SupportEditor: View {
    var feedback = false
    @Environment(\.dismiss) private var dismiss
    @State private var subject = ""
    @State private var content = ""
    @State private var contact = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: feedback ? "意见反馈" : "客服工单", subtitle: "请描述问题、发生时间与操作步骤，方便我们处理。", icon: "square.and.pencil")
            SPXPanel("问题内容") {
                if !feedback { SPXField("问题标题") { TextField("简要描述问题", text: $subject) } }
                SPXField("详细说明") { TextField("问题描述与出现步骤…", text: $content).lineLimit(16) }
                if feedback { SPXField("联系方式（选填）") { TextField("便于联系你的方式", text: $contact) } }
            }.disabled(busy)
            if let error { ErrorBanner(message: error) }
        }.safeAreaInset(edge: .bottom, spacing: 0) { SPXBottomAction { Button(busy ? "提交中…" : "提交") { submit() }.disabled(busy || content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (!feedback && subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)) } }
            .navigationTitle(feedback ? "意见反馈" : "客服工单")
    }
    private func submit() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post(feedback ? "feedback.create" : "support.tickets.create", feedback ? ["content": .string(content), "contact": .string(contact)] : ["subject": .string(subject), "content": .string(content)]); dismiss() } catch { self.error = error.localizedDescription } } }
}

struct QuestionsView: View {
    @State private var question = ""
    @State private var rows: [Record] = []
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        SPXDetailPage { SPXPanel("提交问题") { TextField("你想了解什么？", text: $question).lineLimit(8); Button(busy ? "提交中…" : "提交提问") { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("questions.create", ["question": .string(question)]); question = ""; await load() } catch { self.error = error.localizedDescription } } }.buttonStyle(SPXPrimaryButtonStyle()).disabled(busy || question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }; SPXPanel("历史提问") { ForEach(rows) { row in VStack(alignment: .leading, spacing: 8) { Text(row["question"].string); Text(row.text("answer", "admin_reply", "status")).font(.subheadline).foregroundStyle(.secondary) } } }; if let error { ErrorBanner(message: error) } }.navigationTitle("我的提问").task { await load() }
    }
    private func load() async { do { rows = try await APIClient.shared.get("questions").list } catch { self.error = error.localizedDescription } }
}

struct ReferralsView: View {
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View {
        SPXDetailPage {
            SPXPageHeading(title: "邀请朋友", subtitle: "分享邀请链接，查看邀请与奖励记录", icon: "person.2")
            SPXPanel("我的邀请码") {
                Text(data["invite_code"].string.isEmpty ? "—" : data["invite_code"].string).font(.system(size: 32, weight: .bold, design: .monospaced)).foregroundStyle(SPXColor.primary).textSelection(.enabled).frame(maxWidth: .infinity).padding(.vertical, 12)
                if let url = URL(string: data["invite_url"].string), !data["invite_url"].string.isEmpty { SPXShareLink(item: url) { Label("分享邀请链接", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent) }
            }
            SPXPanel("邀请数据") {
                InfoRow(title: "已邀请", value: data["invited_count"].string)
                InfoRow(title: "累计奖励 SPX001", value: DisplayLabels.amount(data["total_rewards"].string))
                InfoRow(title: "佣金比例", value: DisplayLabels.amount(data["commission_rate"].string))
            }
            SPXPanel("邀请记录") {
                ForEach(data["users"].list) { user in HStack { Avatar(url: user.avatar, name: user.name, size: 38); VStack(alignment: .leading, spacing: 6) { Text(user.name).font(.subheadline.weight(.medium)); Text("奖励 \(DisplayLabels.amount(user["reward_total"].string)) SPX001").font(.caption).foregroundStyle(SPXColor.muted) }; Spacer() } }
                if data["users"].array.isEmpty { Text("暂无邀请记录").font(.subheadline).foregroundStyle(SPXColor.muted) }
            }
            if let error { ErrorBanner(message: error) }
        }.navigationTitle("邀请朋友").task { do { data = try await APIClient.shared.get("referrals") } catch { self.error = error.localizedDescription } }
    }
}
