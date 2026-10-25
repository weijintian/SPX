import SwiftUI

struct MerchantConsultationGroupPicker: View {
    @Binding var code: String
    @Environment(\.dismiss) private var dismiss
    @State private var groups: [Record] = []
    @State private var search = ""
    @State private var loading = false
    @State private var error: String?
    @State private var page = 0
    @State private var more = true

    private var filtered: [Record] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return groups.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) || $0["group_code"].string.localizedCaseInsensitiveContains(query) }
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(filtered) { group in
                        Button {
                            code = group["group_code"].string; dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "person.2.fill").foregroundStyle(SPXColor.primary)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(group.name).foregroundStyle(SPXColor.ink)
                                    Text("群号 \(group["group_code"].string)").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if code.uppercased() == group["group_code"].string { Image(systemName: "checkmark").foregroundStyle(SPXColor.primary) }
                            }.frame(minHeight: 46).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                } footer: { Text("可选择你担任群主或管理员的群，已有群号会保留。") }
                if loading { ProgressView("正在读取咨询群…") }
                if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load(reset: page == 0) } } }
                if !loading && error == nil && filtered.isEmpty { Text(groups.isEmpty ? "暂无可管理的群，请检查群身份或先创建商品咨询群。" : "没有匹配的群，可继续加载或修改搜索词。").foregroundStyle(.secondary) }
                if more && !loading && error == nil { Button("加载更多群") { Task { await load(reset: false) } } }
            }
            .searchable(text: $search, prompt: "搜索群名称或群号")
            .navigationTitle("选择商品咨询群").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .task { await load(reset: true) }
            .refreshable { await load(reset: true) }
        }
    }
    private func load(reset: Bool) async {
        guard !loading else { return }
        loading = true; error = nil
        defer { loading = false }
        let next = reset ? 1 : page + 1
        do {
            let rows = try await APIClient.shared.get("merchant.groups", ["page": String(next), "page_size": "60"]).list
            try Task.checkCancellation()
            if reset { groups = rows }
            else { let ids = Set(groups.map(\.id)); groups += rows.filter { !ids.contains($0.id) } }
            page = next; more = rows.count == 60
        } catch is CancellationError { } catch { self.error = error.localizedDescription }
    }
}
