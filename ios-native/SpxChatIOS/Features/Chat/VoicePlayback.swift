import SwiftUI
import AVFoundation
import UIKit
import CryptoKit

enum VoiceMessageMetadata {
    static func seconds(in content: String) -> Int? {
        guard let range = content.range(of: #"[0-9]+(?:\.[0-9]+)?(?=秒)"#, options: .regularExpression),
              let value = Double(content[range]), value.isFinite, value > 0, value <= 86_400 else { return nil }
        return Int(ceil(value))
    }
}

@MainActor final class VoicePlayback: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = VoicePlayback()
    @Published private(set) var source: String?
    @Published private(set) var loading = false
    @Published private(set) var playing = false
    @Published private(set) var speaker = UserDefaults.standard.object(forKey: "spx.voice.speaker") as? Bool ?? true
    @Published private(set) var durations: [String: Int] = [:]
    @Published private(set) var heard: [Int: Set<String>] = [:]
    private var player: AVAudioPlayer?
    private var download: Task<URL, Error>?
    private var generation = UUID()
    private var observers: [NSObjectProtocol] = []
    override init() {
        super.init()
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIDevice.proximityStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in guard let self, self.playing else { return }; try? self.applyRoute() }
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] event in
            let raw = event.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw == AVAudioSession.InterruptionType.began.rawValue { Task { @MainActor in self?.stop() } }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] event in
            let raw = event.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            if raw == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { Task { @MainActor in self?.stop() } }
        })
    }
    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) }; download?.cancel() }
    private func heardKey(_ source: String) -> String { SHA256.hash(data: Data(source.utf8)).map { String(format: "%02x", $0) }.joined() }
    func prepareHistory(userID: Int) {
        guard userID > 0, heard[userID] == nil else { return }
        heard[userID] = Set(UserDefaults.standard.stringArray(forKey: "spx.voice.heard.\(userID)") ?? [])
    }
    func hasListened(_ source: String, userID: Int) -> Bool { heard[userID]?.contains(heardKey(source)) == true }
    private func markListened(_ source: String, userID: Int) {
        prepareHistory(userID: userID)
        let key = heardKey(source), storage = "spx.voice.heard.\(userID)"
        var recent = UserDefaults.standard.stringArray(forKey: storage) ?? []
        recent.removeAll { $0 == key }; recent.append(key)
        recent = Array(recent.suffix(2_000)); UserDefaults.standard.set(recent, forKey: storage); heard[userID] = Set(recent)
    }
    func setSpeaker(_ value: Bool) throws {
        let previous = speaker; speaker = value
        do { if playing { try applyRoute() }; UserDefaults.standard.set(value, forKey: "spx.voice.speaker") }
        catch { speaker = previous; throw error }
    }
    private func applyRoute() throws {
        let audio = AVAudioSession.sharedInstance()
        if audio.currentRoute.outputs.contains(where: { [.bluetoothA2DP, .bluetoothHFP, .headphones].contains($0.portType) }) { return }
        try audio.overrideOutputAudioPort(speaker && !UIDevice.current.proximityState ? .speaker : .none)
    }
    func toggle(_ source: String, userID: Int) async throws {
        guard CallCoordinator.shared.call == nil else { throw APIError.server(409, "通话中暂不能播放语音消息") }
        if self.source == source, playing || loading { stop(); return }
        ChatSoundEffects.shared.stopEffect()
        stop(); let epoch = generation; self.source = source; loading = true
        do {
            let task = Task { try await MediaCache.shared.file(source, userID: userID) }; download = task
            let file = try await task.value
            guard epoch == generation else { return }; download = nil
            guard CallCoordinator.shared.call == nil else { stop(); return }
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .allowBluetoothA2DP])
            try audio.setActive(true); UIDevice.current.isProximityMonitoringEnabled = true; try applyRoute()
            let next = try AVAudioPlayer(contentsOf: file); next.delegate = self; next.prepareToPlay(); player = next
            guard next.play() else { throw APIError.invalidResponse }
            if next.duration.isFinite { durations[source] = max(1, Int(ceil(next.duration))) }
            markListened(source, userID: userID); loading = false; playing = true
        } catch { if epoch == generation { stop() }; if !(error is CancellationError) { throw error } }
    }
    func stop() {
        generation = UUID(); let owned = playing || player != nil
        download?.cancel(); download = nil
        player?.delegate = nil; player?.stop(); player = nil; source = nil; loading = false; playing = false
        if owned, CallCoordinator.shared.call == nil {
            UIDevice.current.isProximityMonitoringEnabled = false
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in if self.player === player { stop() } } }
    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) { Task { @MainActor in if self.player === player { stop() } } }
}

struct VoiceMessageLabel: View {
    let url: String
    var content = ""
    var incoming = false
    var userID = 0
    @ObservedObject private var audio = VoicePlayback.shared
    @State private var routeError: String?
    private var duration: Int? { VoiceMessageMetadata.seconds(in: content) ?? audio.durations[url] }
    var body: some View {
        HStack(spacing: 10) {
            if audio.source == url, audio.loading { ProgressView().tint(.primary) }
            else { Image(systemName: audio.source == url && audio.playing ? "stop.circle.fill" : "waveform").font(.title3).symbolEffect(.variableColor, isActive: audio.source == url && audio.playing) }
            if let duration { Text("\(duration)秒").font(.subheadline.monospacedDigit()) }
            else { Text(audio.source == url && audio.playing ? "播放中" : "点击播放").font(.subheadline) }
            if incoming, !audio.hasListened(url, userID: userID) { Circle().fill(.red).frame(width: 7, height: 7).accessibilityLabel("未听语音") }
        }.frame(minWidth: 104, minHeight: 30)
            .onAppear { audio.prepareHistory(userID: userID) }
            .contextMenu {
                Button(audio.speaker ? "切换听筒播放" : "切换扬声器播放", systemImage: audio.speaker ? "ear" : "speaker.wave.2") {
                    do { try audio.setSpeaker(!audio.speaker) } catch { routeError = error.localizedDescription }
                }
            }.errorAlert($routeError).accessibilityLabel("语音消息，\(duration.map { "\($0)秒" } ?? "点击播放")")
    }
}
