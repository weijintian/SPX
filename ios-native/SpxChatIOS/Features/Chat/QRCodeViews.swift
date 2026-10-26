import SwiftUI
import VisionKit
import Vision
import PhotosUI
import AVFoundation
import Photos
import CoreImage.CIFilterBuiltins

struct QRCodeView: View {
    let value: String
    let title: String
    @State private var saving = false
    @State private var notice: String?
    private var image: UIImage? {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 9, y: 9)), let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
    var body: some View {
        VStack(spacing: 26) {
            Image(systemName: "qrcode").font(.system(size: 35)).foregroundStyle(SPXColor.primary)
            Text(title).font(.title2.bold())
            if let image { Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(width: 250, height: 250).padding(22).background(.white, in: RoundedRectangle(cornerRadius: 22)); SPXImageShareLink(image: image, title: title) }
            Text("使用 SPX Chat 扫一�?).font(.subheadline).foregroundStyle(.secondary)
            Button("保存到相�?, systemImage: "square.and.arrow.down") { save() }.disabled(saving || image == nil)
        }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity).background(SPXColor.surface).navigationTitle("二维�?).errorAlert($notice)
    }
    private func save() {
        guard !saving, let image else { return }; saving = true
        Task { defer { saving = false }; do { let allowed = await PHPhotoLibrary.requestAuthorization(for: .addOnly); guard allowed == .authorized || allowed == .limited else { throw APIError.server(403, "请在系统设置允许保存到相�?) }; try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.creationRequestForAsset(from: image) }; notice = "二维码已保存" } catch { notice = error.localizedDescription } }
    }
}

struct ScanCodeView: View {
    @State private var value = ""
    @State private var userID = 0
    @State private var groupCode = ""
    @State private var error: String?
    @State private var selectedPhoto: SPXPhotosPickerItem?
    @State private var cameraAllowed = false
    @State private var busy = false
    var body: some View {
        VStack(spacing: 18) {
            if #available(iOS 16.0, *), cameraAllowed, DataScannerViewController.isSupported, DataScannerViewController.isAvailable {
                QRScanner { value in process(value) }.clipShape(RoundedRectangle(cornerRadius: 24)).padding(.horizontal, 20)
            } else { EmptyState(title: "当前设备无法扫码", description: "可输�?SPX ID 或六位群�?, icon: "qrcode.viewfinder") }
            HStack { TextField("SPXUSER:用户ID �?SPXGROUP:群号", text: $value).textInputAutocapitalization(.never).textFieldStyle(.roundedBorder); Button("识别") { process(value) } }.padding()
            SPXPhotosPicker(selection: $selectedPhoto, matching: .images) { Label("从相册识别二维码", systemImage: "photo") }.disabled(busy).padding(.bottom)
        }.navigationTitle("扫一�?).errorAlert($error)
            .task { cameraAllowed = await AVCaptureDevice.requestAccess(for: .video) }
            .onChange(of: selectedPhoto) { item in
                guard let item else { return }
                Task {
                    defer { selectedPhoto = nil }
                    do {
                        guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data), let cg = image.cgImage else { throw APIError.invalidResponse }
                        let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
                        try VNImageRequestHandler(cgImage: cg).perform([request])
                        guard let result = request.results?.compactMap(\.payloadStringValue).first else { throw APIError.server(400, "图片中未找到二维�?) }
                        process(result)
                    } catch { self.error = error.localizedDescription }
                }
            }
            .spxNavigationDestination(isPresented: Binding(get: { userID > 0 }, set: { if !$0 { userID = 0 } })) { UserProfileView(userID: userID) }
            .spxNavigationDestination(isPresented: Binding(get: { !groupCode.isEmpty }, set: { if !$0 { groupCode = "" } })) { GroupJoinView(initialCode: groupCode) }
    }
    private func process(_ value: String) {
        guard !busy, userID == 0, groupCode.isEmpty else { return }
        let value = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("SPXGROUP:") { let code = String(value.dropFirst(9).prefix { !$0.isWhitespace }).uppercased(); guard code.count == 6, code.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { error = "群号格式不正�?; return }; groupCode = code }
        else if value.hasPrefix("SPXUSER:") {
            let id = String(value.dropFirst(8))
            busy = true
            Task { defer { busy = false }; do { let result = try await APIClient.shared.get("user.profile", ["id": id]); guard result["id"].int > 0 else { throw APIError.invalidResponse }; userID = result["id"].int } catch { self.error = error.localizedDescription } }
        } else if !value.isEmpty, value.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) {
            busy = true
            Task { defer { busy = false }; do { let result = try await APIClient.shared.get("user.profile", ["id": value]); guard result["id"].int > 0 else { throw APIError.invalidResponse }; userID = result["id"].int } catch {
                if value.count == 6 { do { let found = try await APIClient.shared.get("groups.search", ["group_code": value.uppercased()]); guard found["id"].int > 0 else { throw APIError.invalidResponse }; groupCode = value.uppercased() } catch { self.error = error.localizedDescription } } else { self.error = error.localizedDescription }
            } }
        } else { error = "请扫�?SPX 用户或群组二维码" }
    }
}

@available(iOS 16.0, *)
private struct QRScanner: UIViewControllerRepresentable {
    let found: (String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(found) }
    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced, recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false, isPinchToZoomEnabled: true, isGuidanceEnabled: true, isHighlightingEnabled: true)
        controller.delegate = context.coordinator; try? controller.startScanning(); return controller
    }
    func updateUIViewController(_ controller: DataScannerViewController, context: Context) { }
    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) { controller.stopScanning() }
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let found: (String) -> Void
        private var last = ""
        init(_ found: @escaping (String) -> Void) { self.found = found }
        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems { if case .barcode(let barcode) = item, let value = barcode.payloadStringValue, value != last { last = value; found(value); break } }
        }
    }
}
