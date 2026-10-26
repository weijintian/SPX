import SwiftUI

struct FileAttachmentView: View {
    let message: Record
    let userID: Int
    @Environment(\.dismiss) private var dismiss
    @State private var file: URL?
    @State private var progress: Double = -1
    @State private var attempt = UUID()
    @State private var loading = true
    @State private var failure: String?
    private var source: String { message["media_url"].string }
    var body: some View {
        SPXNavigationContainer {
            Group {
                if let file { DocumentPreview(url: file) }
                else {
                    VStack(spacing: 20) {
                        Image(systemName: "doc.richtext").font(.system(size: 54, weight: .light)).foregroundStyle(SPXColor.primary)
                        Text(message["content"].string.isEmpty ? "文件附件" : message["content"].string).font(.headline).multilineTextAlignment(.center)
                        if loading {
                            if progress >= 0 { ProgressView(value: progress); Text("正在下载 \(Int(progress * 100))%").font(.subheadline.monospacedDigit()) }
                            else { ProgressView("正在下载文件…") }
                            Button("取消下载") { loading = false; attempt = UUID() }
                        } else {
                            Text(failure ?? "下载已取消").font(.subheadline).foregroundStyle(.secondary)
                            Button("重新下载") { failure = nil; loading = true; progress = -1; attempt = UUID() }.buttonStyle(.borderedProminent)
                        }
                    }.padding(32).frame(maxWidth: 420)
                }
            }.navigationTitle("文件预览").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }.task(id: attempt) { await download() }
    }
    @MainActor private func download() async {
        guard loading else { return }
        let current = attempt
        do {
            let result = try await MediaCache.shared.file(source, userID: userID) { value in
                Task { @MainActor in if current == attempt { progress = value } }
            }
            guard !Task.isCancelled, current == attempt else { return }
            file = result; loading = false
        } catch is CancellationError { }
        catch {
            guard !Task.isCancelled, current == attempt else { return }
            failure = error.localizedDescription; loading = false
        }
    }
}
