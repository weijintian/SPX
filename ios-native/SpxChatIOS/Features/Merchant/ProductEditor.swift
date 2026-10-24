import SwiftUI
import PhotosUI

struct ProductVideoPending: Codable, Equatable {
    let userID: Int
    let uploadID: String
    let source: URL
    var file: URL { (try? MediaFiles.restoredAttachment(source, userID: userID)) ?? source }
}

struct ProductVariant: Identifiable {
    var id = UUID().uuidString.lowercased()
    var name = ""
    var cny = ""
    var stock = "0"
    var enabled = true
    var attributes: [String: JSON] = [:]
    var imageURL = ""
    var json: JSON { .object(["id": .string(id), "name": .string(name), "cny_price": .string(cny), "stock": .string(stock), "enabled": .bool(enabled), "attributes": .object(attributes), "image_url": .string(imageURL)]) }
}

struct PublishProductView: View {
    var product = Record()
    var draftID = 0
    var draftVersion = 0
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var values: [String: String] = [:]
    @State private var type = "physical"
    @State private var images: [String] = []
    @State private var detailImages: [String] = []
    @State private var mainPicker: [PhotosPickerItem] = []
    @State private var detailPicker: [PhotosPickerItem] = []
    @State private var videoPicker: PhotosPickerItem?
    @State private var pendingVideo: ProductVideoPending?
    private var videoRecoveryKey: String { localKey + "-video-upload" }
    @State private var variants: [ProductVariant] = []
    @State private var appointment = false
    @State private var draft = 0
    @State private var version = 0
    @State private var categories: [String] = ["服饰", "数码", "家居", "食品", "本地服务", "数字商品", "其他"]
    @State private var busy = false
    @State private var progress = ""
    @State private var error: String?
    @State private var publish = false
    @State private var initialized = false
    @State private var restored = false
    @State private var recovery: Record?
    @State private var leaving = false
    @State private var committed = false
    @State private var savedFingerprint = Record()
    @State private var usd = ""
    private var localKey: String { "product-editor-\(product.id)-\(draftID)" }
    private var snapshot: Record {
        var payload = values.mapValues(JSON.string)
        payload["product_type"] = .string(type); payload["images"] = .array(images.map(JSON.string)); payload["detail_images"] = .array(detailImages.map(JSON.string)); payload["variants"] = .array(variants.map(\.json)); payload["appointment_required"] = .bool(appointment)
        return Record(payload)
    }
    private var editing: Bool { product.id > 0 }
    var body: some View {
        editorWithDialogs
            .onChange(of: mainPicker) { _, selected in
                Task { await upload(selected, detail: false) }
            }
            .onChange(of: detailPicker) { _, selected in
                Task { await upload(selected, detail: true) }
            }
            .onChange(of: videoPicker) { _, item in
                guard let item else { return }
                Task { await uploadVideo(item) }
            }
    }

    private var editorWithNavigation: some View {
        editorForm
            .navigationTitle(editing ? "编辑商品" : "发布商品")
            .navigationBarTitleDisplayMode(.inline)
            .disabled(busy)
            .interactiveDismissDisabled(busy)
            .navigationBarBackButtonHidden()
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: requestLeave) {
                        Label("返回", systemImage: "chevron.left")
                    }
                    .disabled(busy)
                }
            }
    }

    private var editorWithTasks: some View {
        editorWithNavigation
            .task { await loadEditor() }
            .task(id: snapshot) { await persistLocalDraft() }
    }

    private var recoveryPresented: Binding<Bool> {
        Binding(
            get: { recovery != nil },
            set: { if !$0 { recovery = nil } }
        )
    }

    private var editorWithDialogs: some View {
        editorWithTasks
            .alert("发现未完成的编辑", isPresented: recoveryPresented) {
                Button("恢复编辑", action: restoreLocalDraft)
                Button("放弃本地草稿", role: .destructive, action: discardLocalDraft)
            } message: {
                Text("可恢复上次离开时的商品内容、图片和规格。")
            }
            .confirmationDialog("是否保留这次编辑？", isPresented: $leaving, titleVisibility: .visible) {
                Button("保留到本机并返回", action: keepDraftAndLeave)
                Button("放弃修改", role: .destructive, action: discardChangesAndLeave)
                Button("继续编辑", role: .cancel) {}
            }
            .confirmationDialog("确认提交商品资料？", isPresented: $publish, titleVisibility: .visible) {
                Button("确认提交") { save(publishing: true) }
            }
    }

    private func requestLeave() {
        if snapshot != savedFingerprint { leaving = true }
        else { dismiss() }
    }

    @MainActor private func loadEditor() async {
        guard !initialized else { return }
        initialize()
        savedFingerprint = snapshot
        recovery = await LocalStore.shared.read(localKey, userID: session.user?.id ?? 0)
        restored = true
        pendingVideo = await LocalStore.shared.read(videoRecoveryKey, userID: session.user?.id ?? 0)
        if let config = try? await APIClient.shared.get("mall.config") {
            let configured = config["categories"].array.map(\.string)
            if !configured.isEmpty { categories = configured }
            if let selected = values["category"], !categories.contains(selected) {
                categories.append(selected)
            }
        }
        if let valuation = try? await APIClient.shared.get("mall.valuation") {
            usd = valuation["spx_usdt"].string
        }
    }

    @MainActor private func persistLocalDraft() async {
        guard restored, recovery == nil, !committed else { return }
        let current = snapshot
        let owner = session.user?.id ?? 0
        do {
            try await Task.sleep(for: .milliseconds(500))
            if current != savedFingerprint {
                try await LocalStore.shared.save(current, key: localKey, userID: owner)
            }
        } catch is CancellationError {
        } catch {
            self.error = "本地草稿保存失败，请手动保存草稿"
        }
    }

    private func restoreLocalDraft() {
        if let recovery { restore(recovery) }
        recovery = nil
    }

    private func discardLocalDraft() {
        recovery = nil
        let owner = session.user?.id ?? 0
        Task { await LocalStore.shared.remove(localKey, userID: owner) }
    }

    private func keepDraftAndLeave() {
        let data = snapshot
        let owner = session.user?.id ?? 0
        Task {
            do {
                try await LocalStore.shared.save(data, key: localKey, userID: owner)
                dismiss()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func discardChangesAndLeave() {
        committed = true
        let owner = session.user?.id ?? 0
        Task {
            await LocalStore.shared.remove(localKey, userID: owner)
            if let pendingVideo { await discardVideo(pendingVideo) }
            dismiss()
        }
    }

    private var editorForm: some View {
        Form {
            basicSection
            mainImageSection
            variantSection
            detailSection
            statusSections
            saveSection
        }
    }
    private var basicSection: some View {
        Section {
            Picker("商品类型", selection: $type) { Text("实物商品").tag("physical"); Text("数字商品").tag("digital"); Text("本地服务").tag("local") }.disabled(editing)
            field("name", "商品名称（最多 150 字）")
            field("description", "商品简介", multiline: true)
            Picker("分类", selection: bind("category")) { ForEach(categories, id: \.self) { Text($0).tag($0) } }
            field("cny_price", "人民币价格 CNY", keyboard: .decimalPad)
            if let estimate = Money.estimate(cny: values["cny_price"] ?? "", usd: usd) { Text("参考约 \(DisplayLabels.amount(estimate)) SPX001").font(.caption).foregroundStyle(.secondary) }
            field("stock", "总库存", keyboard: .numberPad).disabled(editing)
            field("consultation_group_code", "咨询群号（六位）")
        } header: { Text("基本信息") } footer: { Text("人民币售价固定，SPX001 数量按实时行情换算。已发布商品库存请通过库存管理调整。") }
    }
    private var mainImageSection: some View {
        Section {
            PhotosPicker(selection: $mainPicker, maxSelectionCount: max(1, 8 - images.count), matching: .images) { Label("批量选择主图", systemImage: "photo.on.rectangle.angled") }.disabled(images.count >= 8)
            imageStrip($images)
        } header: { Text("商品主图 \(images.count)/8") } footer: { Text("第一张为主图。点击缩略图菜单可设为主图或移除，图片保持完整居中。") }
    }
    private var variantSection: some View {
        Section("规格与库存") {
            ForEach($variants) { $variant in
                VStack(alignment: .leading, spacing: 10) {
                    TextField("规格名称，例如 黑色 / L", text: $variant.name)
                    TextField("CNY 售价", text: $variant.cny).keyboardType(.decimalPad)
                    TextField("库存", text: $variant.stock).keyboardType(.numberPad).disabled(editing)
                    Toggle("启用该规格", isOn: $variant.enabled)
                }.padding(.vertical, 6)
            }.onDelete { index in if !editing { variants.remove(atOffsets: index) } }
            if variants.count < 50, !editing || !product["variants"].array.isEmpty { Button("添加规格", systemImage: "plus.circle") { variants.append(ProductVariant()) } }
        }
    }
    private var detailSection: some View {
        Section("详情与服务") {
            field("detail_text", "详细介绍", multiline: true)
            PhotosPicker(selection: $detailPicker, maxSelectionCount: max(1, 20 - detailImages.count), matching: .images) { Label("批量添加详情图", systemImage: "photo.stack") }.disabled(detailImages.count >= 20)
            imageStrip($detailImages)
            field("video_url", "商品视频 URL（选填）")
            PhotosPicker(selection: $videoPicker, matching: .videos) { Label("从相册选择商品视频", systemImage: "video.badge.plus") }.disabled(pendingVideo != nil)
            if let pendingVideo {
                Label("有未完成的商品视频上传", systemImage: "arrow.triangle.2.circlepath").font(.subheadline)
                HStack {
                    Button("继续上传") { Task { await resumeVideo(pendingVideo) } }
                    Spacer()
                    Button("放弃视频", role: .destructive) { Task { await discardVideo(pendingVideo) } }
                }
            }
            if !(values["video_url"] ?? "").isEmpty { Button("移除商品视频", role: .destructive) { values["video_url"] = "" } }
            field("parameters", "商品参数", multiline: true)
            field("service_terms", "售后与服务条款", multiline: true)
            field("delivery_note", "交付说明", multiline: true)
            if type == "physical" { field("shipping_fee", "运费（SPX001）", keyboard: .decimalPad); field("delivery_days", "预计发货天数", keyboard: .numberPad) }
            if type == "local" { field("service_valid_days", "服务有效期（天）", keyboard: .numberPad); Toggle("需预约", isOn: $appointment) }
        }
    }
    @ViewBuilder private var statusSections: some View {
        if !progress.isEmpty { Section { HStack { ProgressView(); Text(progress).font(.subheadline) } } }
        if let error { Section { ErrorBanner(message: error) } }
    }
    private var saveSection: some View {
        Section { Button("保存草稿") { save(publishing: false) }; Button(editing ? "保存商品修改" : "提交发布") { publish = true }.fontWeight(.semibold) }.disabled(busy)
    }

    private func bind(_ key: String) -> Binding<String> { Binding(get: { values[key] ?? "" }, set: { values[key] = $0 }) }
    private func field(_ key: String, _ title: String, keyboard: UIKeyboardType = .default, multiline: Bool = false) -> some View { TextField(title, text: bind(key), axis: multiline ? .vertical : .horizontal).lineLimit(multiline ? 3...10 : 1...1).keyboardType(keyboard) }
    private func imageStrip(_ urls: Binding<[String]>) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(Array(urls.wrappedValue.enumerated()), id: \.offset) { index, url in
                    Menu {
                        if index > 0 { Button("设为主图") { var items = urls.wrappedValue; items.insert(items.remove(at: index), at: 0); urls.wrappedValue = items } }
                        if index > 0 { Button("向前移动", systemImage: "arrow.left") { var items = urls.wrappedValue; items.swapAt(index, index - 1); urls.wrappedValue = items } }
                        if index + 1 < urls.wrappedValue.count { Button("向后移动", systemImage: "arrow.right") { var items = urls.wrappedValue; items.swapAt(index, index + 1); urls.wrappedValue = items } }
                        Button("移除", role: .destructive) { urls.wrappedValue.remove(at: index) }
                    } label: {
                        CachedImage(source: url).frame(width: 78, height: 78).background(.white, in: RoundedRectangle(cornerRadius: 12)).overlay(alignment: .bottomLeading) { if index == 0 { Text("首图").font(.caption2).padding(4).foregroundStyle(.white).background(SPXColor.primary, in: RoundedRectangle(cornerRadius: 6)) } }.clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
            }.padding(.vertical, 6)
        }
    }
    private func initialize() {
        initialized = true; draft = draftID; version = draftVersion
        for key in ["name", "description", "cny_price", "category", "stock", "consultation_group_code", "detail_text", "video_url", "parameters", "service_terms", "delivery_note", "shipping_fee", "delivery_days", "service_valid_days"] { values[key] = product[key].string }
        if values["category"]?.isEmpty != false { values["category"] = "其他" }
        for (key, value) in ["stock": "0", "shipping_fee": "0", "delivery_days": "3", "service_valid_days": "30"] where values[key]?.isEmpty != false { values[key] = value }
        type = product["product_type"].string.isEmpty ? "physical" : product["product_type"].string
        images = product["images"].array.map(\.string); detailImages = product["detail_images"].array.map(\.string); appointment = product["appointment_required"].flag
        variants = product["variants"].array.map { v in ProductVariant(id: v["id"].string, name: v["name"].string, cny: v["cny_price"].string, stock: v["stock"].string, enabled: v["enabled"] == .null || v["enabled"].flag, attributes: v["attributes"].object, imageURL: v["image_url"].string) }
    }
    private func restore(_ saved: Record) {
        for key in values.keys { values[key] = saved[key].string }
        type = saved["product_type"].string; images = saved["images"].array.map(\.string); detailImages = saved["detail_images"].array.map(\.string); appointment = saved["appointment_required"].flag
        variants = saved["variants"].array.map { v in ProductVariant(id: v["id"].string, name: v["name"].string, cny: v["cny_price"].string, stock: v["stock"].string, enabled: v["enabled"].flag, attributes: v["attributes"].object, imageURL: v["image_url"].string) }
    }
    private func upload(_ selected: [PhotosPickerItem], detail: Bool) async {
        guard !busy else { return }; busy = true; defer { busy = false; progress = ""; if detail { detailPicker = [] } else { mainPicker = [] } }
        for (index, item) in selected.enumerated() {
            if detail ? detailImages.count >= 20 : images.count >= 8 { break }
            do { progress = "正在上传 \(index + 1)/\(selected.count)"; let file = try await MediaFiles.select(item, userID: session.user?.id ?? 0); defer { try? FileManager.default.removeItem(at: file) }; let remote = try await MediaUploader.shared.uploadForm(file: file, action: "merchant.media.upload"); if detail { detailImages.append(remote) } else { images.append(remote) }; try? FileManager.default.removeItem(at: file) }
            catch { self.error = error.localizedDescription; break }
        }
    }
    @MainActor private func uploadVideo(_ item: PhotosPickerItem) async {
        guard !busy, pendingVideo == nil, let owner = session.user?.id, owner > 0 else { return }
        busy = true; progress = "正在准备商品视频…"
        defer { busy = false; progress = ""; videoPicker = nil }
        do {
            let file = try await MediaFiles.select(item, userID: owner)
            let pending = ProductVideoPending(userID: owner, uploadID: UUID().uuidString.lowercased(), source: file)
            do { try await LocalStore.shared.save(pending, key: videoRecoveryKey, userID: owner) }
            catch { try? FileManager.default.removeItem(at: file); throw error }
            pendingVideo = pending
            try await transferVideo(pending)
        } catch { self.error = error.localizedDescription }
    }
    @MainActor private func resumeVideo(_ pending: ProductVideoPending) async {
        guard !busy, session.user?.id == pending.userID else { return }
        busy = true; progress = "正在恢复商品视频上传…"; error = nil
        defer { busy = false; progress = "" }
        do { try await transferVideo(pending) } catch { self.error = error.localizedDescription }
    }
    @MainActor private func transferVideo(_ pending: ProductVideoPending) async throws {
        let result = try await MediaUploader.shared.upload(file: pending.file, userID: pending.userID, uploadID: pending.uploadID) { value in
            Task { @MainActor in if pendingVideo?.uploadID == pending.uploadID { progress = "商品视频上传 \(Int(value * 100))%" } }
        }
        guard session.user?.id == pending.userID else { throw APIError.staleSession }
        guard result["message_type"].string == "video", !result["url"].string.isEmpty else { throw APIError.server(422, "请选择支持的视频格式") }
        values["video_url"] = result["url"].string
        // Persist the remote URL before removing the recoverable source and upload ID.
        try await LocalStore.shared.save(snapshot, key: localKey, userID: pending.userID)
        await discardVideo(pending)
    }
    @MainActor private func discardVideo(_ pending: ProductVideoPending) async {
        BackgroundMediaTransfer.shared.discard(userID: pending.userID, uploadID: pending.uploadID)
        await LocalStore.shared.remove(videoRecoveryKey, userID: pending.userID)
        try? FileManager.default.removeItem(at: pending.file)
        if pendingVideo?.uploadID == pending.uploadID { pendingVideo = nil }
    }
    private func save(publishing: Bool) {
        guard !busy else { return }
        guard pendingVideo == nil else { error = "请先继续上传或放弃未完成的商品视频"; return }
        if publishing {
            guard (values["name"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).count <= 150 else { error = "商品名称最多 150 字"; return }
            guard !(values["name"] ?? "").isEmpty, !(values["description"] ?? "").isEmpty, Money.positive(values["cny_price"] ?? ""), let stock = Int(values["stock"] ?? ""), (0...1_000_000).contains(stock), values["consultation_group_code"]?.count == 6, !images.isEmpty else { error = "请完善名称、描述、人民币售价、库存、咨询群号及至少一张主图"; return }
            guard variants.allSatisfy({ !$0.name.isEmpty && Money.positive($0.cny) && Int($0.stock).map { (0...1_000_000).contains($0) } == true }), variants.isEmpty || variants.contains(where: \.enabled) else { error = "请完善每个规格的名称、售价和库存，至少启用一个规格"; return }
        }
        var payload = snapshot.fields
        if editing { payload["id"] = product["id"]; payload["version"] = product["version"] }
        busy = true
        let owner = session.user?.id ?? 0
        Task {
            defer { busy = false }
            do {
                if publishing { try await APIClient.shared.post("merchant.product.save", payload); committed = true; await LocalStore.shared.remove(localKey, userID: owner); NotificationCenter.default.post(name: .spxMerchantProductsUpdated, object: nil); dismiss() }
                else { let result = try await APIClient.shared.post("merchant.draft.save", ["id": .number(Decimal(draft)), "version": .number(Decimal(version)), "payload": .object(payload)]); draft = result["id"].int; version = result["version"].int; savedFingerprint = snapshot; await LocalStore.shared.remove(localKey, userID: owner); error = "草稿已保存" }
            } catch { self.error = error.localizedDescription }
        }
    }
}
