import SwiftUI
import AVKit
import Photos
import QuickLook
import UIKit
import ImageIO
import UniformTypeIdentifiers

struct LocalAttachmentPreview: View {
    let url: URL
    let kind: String
    @State private var thumbnail: UIImage?
    var body: some View {
        Group {
            if let thumbnail {
                Image(uiImage: thumbnail).resizable().scaledToFit().frame(maxWidth: 200, maxHeight: 200)
                    .overlay { if kind == "video" { Image(systemName: "play.circle.fill").font(.largeTitle).foregroundStyle(.white).shadow(radius: 3) } }
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            } else if kind == "image" || kind == "video" {
                ProgressView().frame(width: 160, height: 120)
            }
        }.task(id: url) {
            if kind == "image", let source = CGImageSourceCreateWithURL(url as CFURL, nil),
               let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 480, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) { thumbnail = UIImage(cgImage: cg) }
            else if kind == "video" {
                let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url)); generator.appliesPreferredTrackTransform = true; generator.maximumSize = CGSize(width: 480, height: 480)
                if let result = try? await generator.image(at: .zero) { thumbnail = UIImage(cgImage: result.image) }
            }
        }
    }
}

struct CachedImage: View {
    let source: String
    var fit = true
    @EnvironmentObject private var session: SessionStore
    @State private var image: UIImage?
    @State private var failed = false
    @State private var retry = 0
    var body: some View {
        Group {
            if let image { if fit { Image(uiImage: image).resizable().scaledToFit() } else { Image(uiImage: image).resizable().scaledToFill() } }
            else if failed {
                VStack(spacing: 5) { Image(systemName: "photo").foregroundStyle(.secondary); Text("轻点重试").font(.caption2).foregroundStyle(.secondary) }
                    .contentShape(Rectangle()).onTapGesture { retry += 1 }.accessibilityAddTraits(.isButton).accessibilityLabel("图片加载失败，轻点重试")
            }
            else { ProgressView() }
        }.task(id: source + ":" + String(retry)) {
            let requestedSource = source
            image = nil; failed = false
            guard !source.isEmpty else { failed = true; return }
            do {
                let file = try await MediaCache.shared.file(requestedSource, userID: session.user?.id ?? 0)
                try Task.checkCancellation()
                guard source == requestedSource else { return }
                image = UIImage(contentsOfFile: file.path); failed = image == nil
            }
            catch is CancellationError { }
            catch { failed = true }
        }
    }
}

struct MediaPreview: View {
    let messages: [Record]
    let initialID: Int
    @EnvironmentObject private var session: SessionStore
    @Environment(\.dismiss) private var dismiss
    @State private var selected = 0
    @State private var forwarding = false
    @State private var notice: String?
    @State private var busy = false
    var body: some View {
        NavigationStack {
            TabView(selection: $selected) {
                ForEach(Array(messages.enumerated()), id: \.offset) { index, message in
                    MediaPage(message: message, active: selected == index).tag(index)
                }
            }.tabViewStyle(.page(indexDisplayMode: .always)).background(.black)
                .toolbarBackground(.black, for: .navigationBar).toolbarColorScheme(.dark, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button { dismiss() } label: { Image(systemName: "xmark") } }
                    ToolbarItem(placement: .principal) { Text("\(selected + 1) / \(messages.count)").foregroundStyle(.white) }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("保存到相册", systemImage: "square.and.arrow.down") { save() }.disabled(busy)
                            Button("转发", systemImage: "arrowshape.turn.up.right") { forwarding = true }
                        } label: { Image(systemName: "ellipsis") }
                    }
                }.onAppear { selected = messages.firstIndex(where: { $0.id == initialID }) ?? 0 }
                .sheet(isPresented: $forwarding) { if messages.indices.contains(selected) { ForwardMessageView(message: messages[selected]) } }
                .errorAlert($notice)
        }
    }
    private func save() {
        guard messages.indices.contains(selected) else { return }
        busy = true
        let message = messages[selected]
        Task {
            defer { busy = false }
            do {
                let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
                guard authorization == .authorized || authorization == .limited else { throw APIError.server(403, "请在系统设置允许保存到相册") }
                let file = try await MediaCache.shared.file(message["media_url"].string, userID: session.user?.id ?? 0)
                try await PHPhotoLibrary.shared().performChanges {
                    if message["message_type"].string == "video" { PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: file) }
                    else { PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: file) }
                }
                notice = "已保存到相册"
            } catch { notice = error.localizedDescription }
        }
    }
}

struct VideoMessagePreview: View {
    let source: String
    @EnvironmentObject private var session: SessionStore
    @State private var thumbnail: UIImage?

    var body: some View {
        ZStack {
            if let thumbnail {
                Image(uiImage: thumbnail).resizable().scaledToFill()
            } else {
                Rectangle().fill(Color.black.opacity(0.78))
                VStack(spacing: 6) {
                    Image(systemName: "video.fill").font(.title3)
                    Text("视频").font(.caption)
                }.foregroundStyle(.white.opacity(0.82))
            }
            LinearGradient(colors: [.clear, .black.opacity(0.42)], startPoint: .center, endPoint: .bottom)
            Image(systemName: "play.circle.fill")
                .font(.system(size: 42))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .black.opacity(0.35))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .accessibilityLabel("视频，轻点播放")
        .task(id: source) {
            guard !source.isEmpty, let userID = session.user?.id, userID > 0,
                  let file = try? await MediaCache.shared.cachedFile(source, userID: userID) else { return }
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: file))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 480, height: 320)
            if let frame = try? await generator.image(at: .zero) {
                thumbnail = UIImage(cgImage: frame.image)
            }
        }
    }
}

private final class RemoteVideoResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    private let sourceURL: URL
    private let delegateQueue = DispatchQueue(label: "com.spx.chat.ios.video-range-loader")
    private var requests: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(sourceURL: URL) { self.sourceURL = sourceURL }

    func makeAsset() throws -> AVURLAsset {
        guard var parts = URLComponents(url: sourceURL, resolvingAgainstBaseURL: false) else { throw APIError.invalidURL }
        parts.scheme = "spxvideo"
        guard let proxyURL = parts.url else { throw APIError.invalidURL }
        let asset = AVURLAsset(url: proxyURL)
        asset.resourceLoader.setDelegate(self, queue: delegateQueue)
        return asset
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        let key = ObjectIdentifier(loadingRequest)
        requests[key] = Task { [weak self] in
            guard let self else { return }
            do {
                try await self.load(loadingRequest)
                self.finish(loadingRequest, error: nil)
            } catch is CancellationError {
                self.finish(loadingRequest, error: CancellationError())
            } catch {
                self.finish(loadingRequest, error: error)
            }
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        requests.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    func cancelAllRequests() {
        delegateQueue.async {
            for task in self.requests.values { task.cancel() }
            self.requests.removeAll()
        }
    }

    private func load(_ loadingRequest: AVAssetResourceLoadingRequest) async throws {
        let dataRequest = loadingRequest.dataRequest
        let initialOffset = dataRequest.map { $0.currentOffset == 0 ? $0.requestedOffset : $0.currentOffset } ?? 0
        guard let initial = Int(exactly: initialOffset), initial >= 0 else { throw APIError.invalidResponse }
        var offset = initial
        var remaining = max(1, dataRequest?.requestedLength ?? 0)
        let toEnd = dataRequest?.requestsAllDataToEndOfResource == true
        var firstResponse = true

        while remaining > 0 {
            try Task.checkCancellation()
            let requestedLength = min(1_048_576, remaining)
            let (end, overflow) = offset.addingReportingOverflow(requestedLength - 1)
            guard !overflow else { throw APIError.invalidResponse }
            var request = URLRequest(url: sourceURL)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            request.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            let (bytes, response) = try await NetworkSession.shared.bytes(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 206,
                  let range = HTTPContentRange(http.value(forHTTPHeaderField: "Content-Range")),
                  range.matches(offset: offset, maximumCount: requestedLength) else {
                throw APIError.server((response as? HTTPURLResponse)?.statusCode ?? 0, "服务器未返回有效的视频分段，暂时无法在线播放")
            }
            let count = range.count
            guard count > 0 else { throw APIError.invalidResponse }
            var chunk = Data(); chunk.reserveCapacity(count)
            for try await byte in bytes {
                chunk.append(byte)
                if chunk.count == count { break }
            }
            guard chunk.count == count else { throw APIError.invalidResponse }
            try Task.checkCancellation()
            await deliver(chunk, response: http, totalLength: range.total, request: loadingRequest, includeContentInfo: firstResponse)
            firstResponse = false
            offset += count
            remaining = toEnd ? range.total - offset : remaining - count
            if offset >= range.total { break }
        }
    }

    private func deliver(_ data: Data, response: HTTPURLResponse, totalLength: Int, request: AVAssetResourceLoadingRequest,
                         includeContentInfo: Bool) async {
        await withCheckedContinuation { continuation in
            delegateQueue.async {
                guard !request.isCancelled, !request.isFinished else { continuation.resume(); return }
                if includeContentInfo, let information = request.contentInformationRequest {
                    information.contentLength = Int64(totalLength)
                    information.isByteRangeAccessSupported = true
                    let format = UTType(filenameExtension: self.sourceURL.pathExtension)
                        ?? response.mimeType.flatMap { UTType(mimeType: $0) }
                    information.contentType = format?.identifier ?? UTType.movie.identifier
                }
                request.dataRequest?.respond(with: data)
                continuation.resume()
            }
        }
    }

    private func finish(_ request: AVAssetResourceLoadingRequest, error: Error?) {
        let key = ObjectIdentifier(request)
        delegateQueue.async {
            self.requests[key] = nil
            guard !request.isCancelled, !request.isFinished else { return }
            if let error { request.finishLoading(with: error) }
            else { request.finishLoading() }
        }
    }

}

struct MediaPage: View {
    let message: Record
    var active = true
    @EnvironmentObject private var session: SessionStore
    @State private var player: AVPlayer?
    @State private var scale: CGFloat = 1
    @State private var imageOffset = CGSize.zero
    @State private var dragOrigin = CGSize.zero
    @State private var error: String?
    @State private var retry = 0
    @State private var videoLoader: RemoteVideoResourceLoader?
    @State private var videoObservation: NSKeyValueObservation?
    @State private var videoLoadID = UUID()
    var body: some View {
        Group {
            if message["message_type"].string == "video" {
                if let player { VideoPlayer(player: player) }
                else if let error { VStack(spacing: 14) { Text(error).font(.footnote).multilineTextAlignment(.center); Button("重新加载", systemImage: "arrow.clockwise") { retry += 1 } }.padding(24).foregroundStyle(.white) }
                else { ProgressView().tint(.white) }
            } else {
                CachedImage(source: message["media_url"].string)
                    .scaleEffect(scale)
                    .offset(imageOffset)
                    .gesture(MagnifyGesture()
                        .onChanged { scale = min(4, max(1, $0.magnification)) }
                        .onEnded { _ in if scale <= 1 { resetImageTransform() } })
                    .simultaneousGesture(DragGesture()
                        .onChanged { value in
                            guard scale > 1 else { return }
                            imageOffset = CGSize(width: dragOrigin.width + value.translation.width, height: dragOrigin.height + value.translation.height)
                        }
                        .onEnded { _ in dragOrigin = imageOffset })
                    .onTapGesture(count: 2) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            if scale > 1 { resetImageTransform() }
                            else { scale = 2.5 }
                        }
                    }
            }
        }.frame(maxWidth: .infinity, maxHeight: .infinity)
            .task(id: String(message.id) + ":" + String(retry) + ":" + String(active)) {
                guard message["message_type"].string == "video" else { return }
                guard active else { player?.pause(); return }
                let loadID = UUID()
                videoLoadID = loadID
                player?.pause(); player = nil
                videoObservation = nil
                videoLoader?.cancelAllRequests(); videoLoader = nil
                error = nil
                do {
                    let item: AVPlayerItem
                    if let local = try await MediaCache.shared.cachedFile(message["media_url"].string, userID: session.user?.id ?? 0) {
                        item = AVPlayerItem(url: local)
                    } else {
                        let endpoint = try await APIClient.shared.endpoint()
                        guard let remote = URL(string: message["media_url"].string, relativeTo: endpoint)?.absoluteURL,
                              remote.scheme == "https", remote.host == endpoint.host else { throw APIError.invalidURL }
                        let loader = RemoteVideoResourceLoader(sourceURL: remote)
                        item = AVPlayerItem(asset: try loader.makeAsset())
                        videoLoader = loader
                    }
                    try Task.checkCancellation()
                    guard videoLoadID == loadID else { return }
                    videoObservation = item.observe(\.status, options: [.initial, .new]) { observed, _ in
                        guard observed.status == .failed else { return }
                        let reason = observed.error?.localizedDescription ?? "视频加载失败，请检查网络后重试"
                        Task { @MainActor in
                            guard videoLoadID == loadID else { return }
                            error = reason
                            player?.pause(); player = nil
                            videoLoader?.cancelAllRequests(); videoLoader = nil
                            videoObservation = nil
                        }
                    }
                    player = AVPlayer(playerItem: item)
                }
                catch is CancellationError { }
                catch { self.error = error.localizedDescription }
            }.onChange(of: active) { _, value in if !value { player?.pause() } }
            .onChange(of: message.id) { _, _ in resetImageTransform() }
            .onDisappear {
                videoLoadID = UUID()
                player?.pause(); player = nil; videoObservation = nil
                videoLoader?.cancelAllRequests(); videoLoader = nil
            }
    }
    private func resetImageTransform() {
        scale = 1
        imageOffset = .zero
        dragOrigin = .zero
    }
}

struct NativeCamera: UIViewControllerRepresentable {
    let completion: (UIImage?, URL?) -> Void
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController(); picker.delegate = context.coordinator
        picker.sourceType = .camera; picker.mediaTypes = ["public.image", "public.movie"]; picker.videoQuality = .typeHigh
        return picker
    }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) { }
    func makeCoordinator() -> Coordinator { Coordinator(completion) }
    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let completion: (UIImage?, URL?) -> Void
        init(_ completion: @escaping (UIImage?, URL?) -> Void) { self.completion = completion }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { completion(nil, nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { completion(info[.originalImage] as? UIImage, info[.mediaURL] as? URL) }
    }
}

struct DocumentPreview: UIViewControllerRepresentable {
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator(url) }
    func makeUIViewController(context: Context) -> QLPreviewController { let controller = QLPreviewController(); controller.dataSource = context.coordinator; return controller }
    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) { }
    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(_ url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

@MainActor final class VoiceRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    @Published var recording = false
    @Published var completedURL: URL?
    @Published var errorMessage: String?
    private var recorder: AVAudioRecorder?
    private var request = UUID()
    private var starting = false
    func start(userID: Int) async throws {
        guard !starting, !recording else { throw APIError.server(409, "正在准备录音，请稍候") }
        starting = true; let requestID = UUID(); request = requestID
        defer { if request == requestID { starting = false } }
        let allowed = await withCheckedContinuation { continuation in AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) } }
        guard request == requestID else { throw CancellationError() }
        guard allowed else { throw APIError.server(403, "请在系统设置允许麦克风权限") }
        guard CallCoordinator.shared.call == nil, !recording else { throw APIError.server(409, "当前正在通话或录音，请稍后再试") }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)
        let url = try MediaFiles.directory(userID: userID, component: "attachments").appendingPathComponent(UUID().uuidString + ".m4a")
        do {
            recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue])
            recorder?.delegate = self
            guard recorder?.record(forDuration: 60) == true else { throw APIError.invalidResponse }
            errorMessage = nil; recording = true
        } catch {
            recorder?.delegate = nil; recorder?.stop(); recorder = nil
            try? FileManager.default.removeItem(at: url)
            if CallCoordinator.shared.call == nil { try? session.setActive(false, options: .notifyOthersOnDeactivation) }
            throw error
        }
    }
    func finish() {
        request = UUID(); starting = false
        guard recording else { return }
        let url = recorder?.url
        let duration = recorder?.currentTime ?? 0
        recording = false; recorder?.delegate = nil; recorder?.stop(); recorder = nil
        if duration >= 0.5 { completedURL = url }
        else {
            if let url { try? FileManager.default.removeItem(at: url) }
            completedURL = nil; errorMessage = "录音时间太短，请长按至少半秒后发送"
        }
        if CallCoordinator.shared.call == nil { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }
    func cancel() {
        request = UUID(); starting = false
        let ownedAudioSession = recorder != nil || recording
        recording = false; recorder?.delegate = nil; recorder?.stop(); if let url = recorder?.url { try? FileManager.default.removeItem(at: url) }; recorder = nil; completedURL = nil
        if ownedAudioSession, CallCoordinator.shared.call == nil { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        let url = recorder.url
        Task { @MainActor in
            guard self.recorder === recorder, recording else { return }
            let duration = recorder.currentTime
            recording = false; self.recorder = nil
            if flag && duration >= 0.5 { completedURL = url }
            else { try? FileManager.default.removeItem(at: url); errorMessage = flag ? "录音时间太短，请长按至少半秒后发送" : "录音未完成，请重试" }
            if CallCoordinator.shared.call == nil { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        }
    }
    nonisolated func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        Task { @MainActor in
            guard self.recorder === recorder else { return }
            cancel(); errorMessage = "录音保存失败，请检查设备存储后重试"
        }
    }
}
