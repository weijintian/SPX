import SwiftUI
import PhotosUI
import UIKit

struct MerchantProfileEditor: View {
    let merchant: Record
    let application: Bool
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var values: [String: String] = [:]
    @State private var logo: PhotosPickerItem?
    @State private var qualifications: [PhotosPickerItem] = []
    @State private var qualificationURLs: [String] = []
    @State private var previewQualification: String?
    @State private var level = "basic"
    @State private var busy = false
    @State private var error: String?
    @State private var closing = false
    @State private var locationPicker = false
    private var initialCoordinate: MapCoordinate? {
        guard let lat = Double(values["latitude"] ?? ""), let lon = Double(values["longitude"] ?? ""), lat.isFinite, lon.isFinite, (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
        return MapCoordinates.toWGS(MapCoordinate(latitude: lat, longitude: lon), system: values["coordinate_system"] ?? "gcj02")
    }
    private let fields = [("name", "店铺名称"), ("description", "店铺介绍"), ("category", "经营分类"), ("contact_name", "联系人"), ("contact_phone", "联系电话"), ("address", "门店地址"), ("business_hours", "营业时间"), ("qualification", "经营资质说明"), ("latitude", "纬度"), ("longitude", "经度")]
    var body: some View {
        Form {
            Section("店铺资料") {
                if !(values["logo_url"] ?? "").isEmpty { Avatar(url: values["logo_url"] ?? "", name: values["name"] ?? "", size: 72) }
                PhotosPicker(selection: $logo, matching: .images) { Label("设置店铺头像", systemImage: "camera") }
                ForEach(fields, id: \.0) { key, title in
                    TextField(title, text: binding(key), axis: ["description", "qualification"].contains(key) ? .vertical : .horizontal)
                        .keyboardType(["latitude", "longitude"].contains(key) ? .numbersAndPunctuation : key == "contact_phone" ? .phonePad : .default)
                        .disabled(!application && ["category", "qualification"].contains(key))
                }
                Picker("坐标系统", selection: binding("coordinate_system")) { Text("WGS84 / Apple 地图").tag("wgs84"); Text("GCJ02").tag("gcj02"); Text("BD09").tag("bd09") }
                Button("在地图选择门店位置", systemImage: "map") { locationPicker = true }
                if application { Picker("商家等级", selection: $level) { Text("普通商家").tag("basic"); Text("金牌商家").tag("gold") } }
            }
            Section("经营资质") {
                if application { PhotosPicker(selection: $qualifications, maxSelectionCount: max(1, 6 - qualificationURLs.count), matching: .images) { Label("添加资质图片", systemImage: "doc.viewfinder") }.disabled(qualificationURLs.count >= 6) }
                if !qualificationURLs.isEmpty {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                        ForEach(qualificationURLs, id: \.self) { imageID in
                            ZStack(alignment: .topTrailing) {
                                Button { previewQualification = imageID } label: {
                                    QualificationImage(id: imageID)
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 96)
                                        .clipShape(RoundedRectangle(cornerRadius: 8))
                                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(SPXColor.line))
                                }
                                .buttonStyle(.plain)
                                if application {
                                    Button(role: .destructive) { qualificationURLs.removeAll { $0 == imageID } } label: {
                                        Image(systemName: "xmark.circle.fill")
                                            .font(.title3)
                                            .symbolRenderingMode(.palette)
                                            .foregroundStyle(.white, .black.opacity(0.65))
                                    }
                                    .buttonStyle(.plain)
                                    .padding(4)
                                    .accessibilityLabel("移除经营资质图片")
                                }
                            }
                        }
                    }
                }
                Text("已上传 \(qualificationURLs.count) 张 · 最多 6 张").foregroundStyle(.secondary)
                if !application { Text("经营分类和已审核资质变更请通过客服工单申请。").font(.footnote).foregroundStyle(.secondary) }
            }
            if let error { Section { ErrorBanner(message: error) } }
            Section { Button(busy ? "正在保存…" : application ? "提交入驻申请" : "保存店铺资料") { save() }.disabled(busy) }
            if !application { Section { Button("申请关闭店铺", role: .destructive) { closing = true } } }
        }.navigationTitle(application ? "申请开店" : "店铺资料").disabled(busy)
            .onAppear {
                if values.isEmpty { for (key, _) in fields { values[key] = merchant[key].string }; values["logo_url"] = merchant["logo_url"].string; values["coordinate_system"] = merchant["coordinate_system"].string.isEmpty ? "wgs84" : merchant["coordinate_system"].string; qualificationURLs = merchant["qualification_images"].array.map(\.string); level = merchant["level"].string.isEmpty ? "basic" : merchant["level"].string }
            }
            .onChange(of: logo) { _, item in guard let item else { return }; Task { await uploadLogo(item) } }
            .onChange(of: qualifications) { _, items in Task { await uploadQualifications(items) } }
            .sheet(isPresented: $locationPicker) { ShopLocationPicker(initialAddress: values["address"] ?? "", initialCoordinate: initialCoordinate) { address, coordinate in values["address"] = address; values["latitude"] = String(coordinate.latitude); values["longitude"] = String(coordinate.longitude); values["coordinate_system"] = "wgs84" } }
            .sheet(isPresented: Binding(get: { previewQualification != nil }, set: { if !$0 { previewQualification = nil } })) {
                if let previewQualification {
                    NavigationStack {
                        QualificationImage(id: previewQualification)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(.black)
                            .toolbarBackground(.black, for: .navigationBar)
                            .toolbarColorScheme(.dark, for: .navigationBar)
                            .toolbar {
                                ToolbarItem(placement: .topBarTrailing) {
                                    Button("关闭") { self.previewQualification = nil }.foregroundStyle(.white)
                                }
                            }
                    }
                }
            }
            .confirmationDialog("关闭店铺后将停止接单，保证金按服务器规则结算退回", isPresented: $closing, titleVisibility: .visible) { Button("确认申请关闭", role: .destructive) { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("merchant.close"); NotificationCenter.default.post(name: .spxMerchantUpdated, object: nil); dismiss() } catch { self.error = error.localizedDescription } } } }
    }
    private func binding(_ key: String) -> Binding<String> { Binding(get: { values[key] ?? "" }, set: { values[key] = $0 }) }
    private func uploadLogo(_ item: PhotosPickerItem) async {
        busy = true; defer { busy = false }
        do { let file = try await MediaFiles.select(item, userID: session.user?.id ?? 0); values["logo_url"] = try await MediaUploader.shared.uploadForm(file: file, action: "merchant.logo.upload"); try? FileManager.default.removeItem(at: file) } catch { self.error = error.localizedDescription }
    }
    private func uploadQualifications(_ items: [PhotosPickerItem]) async {
        guard application else { return }; busy = true; defer { busy = false; qualifications = [] }
        do { for item in items where qualificationURLs.count < 6 { let file = try await MediaFiles.select(item, userID: session.user?.id ?? 0); qualificationURLs.append(try await MediaUploader.shared.uploadForm(file: file, action: "merchant.qualification.upload", responseKey: "id")); try? FileManager.default.removeItem(at: file) } } catch { self.error = error.localizedDescription }
    }
    private func save() {
        guard ["name", "category", "contact_name", "contact_phone"].allSatisfy({ values[$0]?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false }) else { error = "请完整填写店铺名称、分类及联系人信息"; return }
        busy = true
        Task { defer { busy = false }; do { var body = values.mapValues(JSON.string); body["level"] = .string(level); body["qualification_images"] = .array(qualificationURLs.map(JSON.string)); try await APIClient.shared.post(application ? "merchant.apply" : "merchant.profile.save", body); NotificationCenter.default.post(name: .spxMerchantUpdated, object: nil); dismiss() } catch { self.error = error.localizedDescription } }
    }
}

private struct QualificationImage: View {
    let id: String
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else if failed {
                Image(systemName: "doc.text.image").font(.title2).foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .task(id: id) {
            do {
                let data = try await APIClient.shared.data("merchant.qualification.image", query: ["id": id])
                try Task.checkCancellation()
                image = UIImage(data: data)
                failed = image == nil
            } catch is CancellationError {
            } catch {
                failed = true
            }
        }
    }
}

struct InventoryEditor: View {
    let product: Record
    @Environment(\.dismiss) private var dismiss
    @State private var variant = ""
    @State private var delta = ""
    @State private var reason = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        Form {
            Section { Text(product.name).font(.headline); InfoRow(title: "当前库存", value: product["stock"].string)
                if !product["variants"].array.isEmpty { Picker("规格", selection: $variant) { Text("请选择规格").tag(""); ForEach(product["variants"].array, id: \.self) { v in Text("\(v["name"].string) · \(v["stock"].string)").tag(v["id"].string) } } }
                TextField("增减数量，例如 10 或 -5", text: $delta).keyboardType(.numbersAndPunctuation)
                TextField("调整原因", text: $reason)
            }
            if let error { ErrorBanner(message: error) }
            Button("确认调整库存") { save() }.disabled(busy || Int(delta) == nil || Int(delta) == 0 || reason.isEmpty || (!product["variants"].array.isEmpty && variant.isEmpty))
        }.navigationTitle("调整库存")
    }
    private func save() { busy = true; Task { defer { busy = false }; do { try await APIClient.shared.post("merchant.inventory.adjust", ["product_id": product["id"], "inventory_version": product["inventory_version"], "variant_id": .string(variant), "delta": .number(Decimal(Int(delta) ?? 0)), "reason": .string(reason)]); dismiss() } catch { self.error = error.localizedDescription } } }
}

struct MerchantOrdersView: View {
    var onlyAfterSale = false
    @State private var rows: [Record] = []
    @State private var page = 1
    @State private var more = true
    @State private var loadID = UUID()
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        List { ForEach(rows.filter { !onlyAfterSale || !["none", ""].contains($0["after_sale_status"].string) }) { order in NavigationLink { MerchantFulfillmentView(order: order) } label: { OrderSummary(order: order) } }; if more { Button(busy ? "正在加载…" : "加载更多") { Task { await load(reset: false) } }.disabled(busy) } }
            .navigationTitle(onlyAfterSale ? "售后管理" : "订单管理").task { await load(reset: true) }.refreshable { await load(reset: true) }.errorAlert($error)
    }
    private func load(reset: Bool) async {
        if !reset && busy { return }
        let requestID = UUID(); loadID = requestID; busy = true
        defer { if loadID == requestID { busy = false } }
        let next = reset ? 1 : page + 1
        do {
            let list = try await APIClient.shared.get("merchant.orders", ["page": String(next), "page_size": "30"]).list
            guard loadID == requestID, !Task.isCancelled else { return }
            if reset { rows = list } else { let ids = Set(rows.map(\.id)); rows += list.filter { !ids.contains($0.id) } }
            page = next; more = list.count == 30; error = nil
        } catch is CancellationError { } catch { if loadID == requestID { self.error = error.localizedDescription } }
    }
}

struct MerchantFulfillmentView: View {
    let order: Record
    @Environment(\.dismiss) private var dismiss
    @State private var carrier = ""
    @State private var tracking = ""
    @State private var delivery = ""
    @State private var code = ""
    @State private var reply = ""
    @State private var error: String?
    @State private var busy = false
    @State private var confirmation: String?
    private var activeAfterSale: Bool { ["requested", "return_approved", "return_shipped"].contains(order["after_sale_status"].string) }
    var body: some View {
        Form {
            Section { OrderSummary(order: order); InfoRow(title: "订单编号", value: order["id"].string); InfoRow(title: "买家备注", value: order["buyer_note"].string) }
            if !order["shipping_address"].string.isEmpty { Section("收货信息") { Text(order["shipping_address"].string).textSelection(.enabled) } }
            if order["status"].string == "pending", !activeAfterSale {
                if order["order_type"].string == "physical" { Section("发货") { TextField("承运商", text: $carrier); TextField("物流单号", text: $tracking); Button("确认发货") { confirmation = "merchant.order.ship" }.disabled(carrier.isEmpty || tracking.isEmpty) } }
                else if order["order_type"].string == "digital" { Section("数字交付") { TextField("填写交付内容 / 使用说明", text: $delivery, axis: .vertical).lineLimit(4...12); Button("完成数字交付") { confirmation = "merchant.order.deliver" }.disabled(delivery.isEmpty) } }
                else { Section("到店核销") { TextField("输入买家出示的核销码", text: $code).textInputAutocapitalization(.characters); Button("核销服务") { confirmation = "merchant.order.redeem" }.disabled(code.isEmpty); Text("核销码由买家主动出示，商家端无法查询该码。").font(.caption).foregroundStyle(.secondary) } }
            }
            if activeAfterSale { Section("售后协商") { InfoRow(title: "申请类型", value: order["after_sale_type"].string); Text(order["after_sale_reason"].string); TextField("给买家的回复", text: $reply, axis: .vertical).lineLimit(3...10); Button("回复售后") { confirmation = "merchant.order.reply" }.disabled(reply.isEmpty); Text("退款审核按平台流程处理。").font(.caption).foregroundStyle(.secondary) } }
            if let error { ErrorBanner(message: error) }
        }.navigationTitle("订单处理").disabled(busy)
            .confirmationDialog("确认提交此订单操作？", isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil } }), titleVisibility: .visible) { Button("确认提交") { if let action = confirmation { submit(action); confirmation = nil } } }
    }
    private func submit(_ action: String) { busy = true; Task { defer { busy = false }; do { var body: [String: JSON] = ["order_id": order["id"]]; switch action { case "merchant.order.ship": body["carrier"] = .string(carrier); body["tracking_number"] = .string(tracking); case "merchant.order.deliver": body["fulfillment_note"] = .string(delivery); case "merchant.order.redeem": body["redeem_code"] = .string(code.uppercased()); default: body["reply"] = .string(reply) }; try await APIClient.shared.post(action, body); dismiss() } catch { self.error = error.localizedDescription } } }
}
