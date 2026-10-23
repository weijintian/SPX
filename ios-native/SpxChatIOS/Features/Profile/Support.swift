import SwiftUI

struct SupportView: View {
    @State private var faq: [Record] = []
    @State private var tickets: [Record] = []
    @State private var error: String?
    var body: some View {
        List {
            Section { NavigationLink { SupportEditor() } label: { Label("提交客服工单", systemImage: "bubble.left.and.text.bubble.right") }; NavigationLink { SupportEditor(feedback: true) } label: { Label("意见反馈", systemImage: "square.and.pencil") }; NavigationLink { QuestionsView() } label: { Label("我的提问", systemImage: "questionmark.bubble") } }
            Section("常见问题") { ForEach(faq) { item in DisclosureGroup(item["question"].string) { Text(item["answer"].string).font(.subheadline).foregroundStyle(.secondary).textSelection(.enabled) } } }
            Section("我的工单") { ForEach(tickets) { ticket in DisclosureGroup(ticket["subject"].string) { Text(ticket["content"].string); InfoRow(title: "状态", value: ticket["status"].string); if !ticket["admin_reply"].string.isEmpty { Text(ticket["admin_reply"].string).padding(10).background(SPXColor.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 10)) } } } }
        }.navigationTitle("帮助与反馈").task { await load() }.refreshable { await load() }.errorAlert($error)
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
        Form {
            Section { if !feedback { TextField("问题标题", text: $subject) }; TextField("详细描述问题与出现步骤", text: $content, axis: .vertical).lineLimit(6...16); if feedback { TextField("联系方式（选填）", text: $contact) } }
            if let error { ErrorBanner(message: error) }
            Button(busy ? "提交中…" : "提交") { submit() }.disabled(busy || content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (!feedback && subject.isEmpty))
        }.navigationTitle(feedback ? "意见反馈" : "客服工单")
    }
    private func submit() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post(feedback ? "feedback.create" : "support.tickets.create", feedback ? ["content": .string(content), "contact": .string(contact)] : ["subject": .string(subject), "content": .string(content)]); dismiss() } catch { self.error = error.localizedDescription } } }
}

struct QuestionsView: View {
    @State private var question = ""
    @State private var rows: [Record] = []
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        Form { Section("提交问题") { TextField("你想了解什么？", text: $question, axis: .vertical).lineLimit(3...8); Button("提交提问") { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("questions.create", ["question": .string(question)]); question = ""; await load() } catch { self.error = error.localizedDescription } } }.disabled(busy || question.isEmpty) }; Section("历史提问") { ForEach(rows) { row in VStack(alignment: .leading, spacing: 8) { Text(row["question"].string); Text(row.text("answer", "admin_reply", "status")).font(.subheadline).foregroundStyle(.secondary) } } }; if let error { ErrorBanner(message: error) } }.navigationTitle("我的提问").task { await load() }
    }
    private func load() async { do { rows = try await APIClient.shared.get("questions").list } catch { self.error = error.localizedDescription } }
}

struct ReferralsView: View {
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View {
        List {
            Section("邀请朋友") { InfoRow(title: "邀请码", value: data["invite_code"].string); if let url = URL(string: data["invite_url"].string), !data["invite_url"].string.isEmpty { ShareLink(item: url) { Label("分享邀请链接", systemImage: "square.and.arrow.up") } }; InfoRow(title: "已邀请", value: data["invited_count"].string); InfoRow(title: "累计奖励 SPX001", value: data["total_rewards"].string); InfoRow(title: "佣金比例", value: data["commission_rate"].string) }
            Section("邀请记录") { ForEach(data["users"].list) { user in VStack(alignment: .leading, spacing: 8) { Text(user.name); Text("奖励 \(user["reward_total"].string) SPX001").font(.caption).foregroundStyle(.secondary) } } }
            if let error { ErrorBanner(message: error) }
        }.navigationTitle("邀请朋友").task { do { data = try await APIClient.shared.get("referrals") } catch { self.error = error.localizedDescription } }
    }
}
