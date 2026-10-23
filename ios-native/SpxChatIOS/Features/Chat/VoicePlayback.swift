import SwiftUI
import AVFoundation

@MainActor final class VoicePlayback: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = VoicePlayback()
    @Published private(set) var source: String?
    @Published private(set) var loading = false
    @Published private(set) var playing = false
    private var player: AVAudioPlayer?
    private var generation = UUID()
    func toggle(_ source: String, userID: Int) async throws {
        guard CallCoordinator.shared.call == nil else { throw APIError.server(409, "通话中暂不能播放语音消息") }
        if self.source == source, playing { stop(); return }
        ChatSoundEffects.shared.stopEffect()
        stop(); let epoch = generation; self.source = source; loading = true
        do {
            let file = try await MediaCache.shared.file(source, userID: userID)
            guard epoch == generation else { return }
            guard CallCoordinator.shared.call == nil else { stop(); return }
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio); try session.setActive(true)
            player = try AVAudioPlayer(contentsOf: file); player?.delegate = self; player?.prepareToPlay()
            guard player?.play() == true else { throw APIError.invalidResponse }
            loading = false; playing = true
        } catch { if epoch == generation { stop() }; throw error }
    }
    func stop() {
        generation = UUID(); let owned = playing || player != nil
        player?.delegate = nil; player?.stop(); player = nil; source = nil; loading = false; playing = false
        if owned, CallCoordinator.shared.call == nil { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in if self.player === player { stop() } } }
    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) { Task { @MainActor in if self.player === player { stop() } } }
}

struct VoiceMessageLabel: View {
    let url: String
    @ObservedObject private var audio = VoicePlayback.shared
    var body: some View {
        HStack(spacing: 10) {
            if audio.source == url, audio.loading { ProgressView().tint(.primary) }
            else { Image(systemName: audio.source == url && audio.playing ? "stop.circle.fill" : "waveform").font(.title3).symbolEffect(.variableColor, isActive: audio.source == url && audio.playing) }
            Text(audio.source == url && audio.playing ? "正在播放 · 点击停止" : "语音 · 点击播放").font(.subheadline)
        }.frame(minWidth: 140, minHeight: 30).accessibilityLabel("语音消息")
    }
}
