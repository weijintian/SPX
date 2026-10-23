import AVFoundation
import UIKit
import AudioToolbox
import Combine

@MainActor final class ChatSoundEffects: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = ChatSoundEffects()
    private var effect: AVAudioPlayer?
    private var effectOwnsSession = false
    private var waiting: AVAudioPlayer?
    private var packetKeys: [String] = []
    private var eventKeys: [String] = []
    private var callID: String?
    private var audioActive = false
    private var connectedID: String?
    private var connectionTonePending = false
    private var mutedConversations: Set<String> = []
    @Published private(set) var pendingMentions: [Int: Int] = [:]
    func updateMuted(chats: [Record], groups: [Record]) {
        mutedConversations = Set(chats.filter { $0["mute"].flag }.map { "c-\($0.id)" } + groups.filter { $0["mute"].flag }.map { "g-\($0.id)" })
    }
    func readGroup(_ id: Int) { pendingMentions.removeValue(forKey: id) }

    static func resource(_ name: String, extension ext: String) -> URL? {
        Bundle.main.url(forResource: name, withExtension: ext) ?? Bundle.main.url(forResource: name, withExtension: ext, subdirectory: "Sounds")
    }
    func packet(_ message: Record) {
        guard message["message_type"].string == "red_packet" else { return }
        let key = message["content"].string
        guard !key.isEmpty, !packetKeys.contains(key) else { return }
        packetKeys.append(key); if packetKeys.count > 256 { packetKeys.removeFirst() }
        playEffect("red_packet", ext: "mp3")
    }
    func incoming(_ event: JSON, userID: Int) {
        guard userID > 0, ["message", "group.message"].contains(event["type"].string) else { return }
        let message = event["message"].record
        guard message.id > 0, message["sender_id"].int != userID else { return }
        let key = "\(event["group_id"].int > 0 ? "g" : "c")-\(event["group_id"].int > 0 ? event["group_id"].int : event["chat_id"].int)-\(message.id)"
        guard !eventKeys.contains(key) else { return }
        eventKeys.append(key); if eventKeys.count > 512 { eventKeys.removeFirst() }
        let mentioned = Self.mentioned(message, userID: userID)
        let scope = event["group_id"].int > 0 ? "g-\(event["group_id"].int)" : "c-\(event["chat_id"].int)"
        if mentioned { pendingMentions[event["group_id"].int] = message.id }
        guard mentioned || !mutedConversations.contains(scope) else { return }
        if message["message_type"].string == "red_packet" { packet(message) }
        else { playEffect("message_received", ext: "wav") }
        if mentioned { NotificationCenter.default.post(name: .spxMentionReceived, object: Record(["group_id": event["group_id"], "message_id": message["id"]])) }
    }
    static func mentioned(_ message: Record, userID: Int) -> Bool {
        message["mention_user_ids"].array.contains { $0.int == userID || $0.int == -1 }
    }
    func beginCall(_ id: String) {
        stopEffect(); stopCall(); callID = id; connectedID = nil
        if !audioActive {
            do { try AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default, options: [.mixWithOthers]); try AVAudioSession.sharedInstance().setActive(true); audioActive = true }
            catch { audioActive = false }
        }
        resumeCallAudio()
    }
    func setCallAudioActive(_ value: Bool) { audioActive = value; if value { if connectionTonePending { playConnectionTone() } else { resumeCallAudio() } } else { waiting?.pause() } }
    private func resumeCallAudio() {
        guard audioActive, callID != nil, connectedID == nil else { return }
        if waiting == nil, let file = Self.resource("call_ringing", extension: "mp3") {
            waiting = try? AVAudioPlayer(contentsOf: file); waiting?.numberOfLoops = -1; waiting?.volume = 0.55; waiting?.prepareToPlay()
        }
        waiting?.play()
    }
    func connected(_ id: String) {
        guard connectedID != id else { return }; connectedID = id
        waiting?.stop(); waiting = nil; callID = nil
        connectionTonePending = true; if audioActive { playConnectionTone() }
    }
    private func playConnectionTone() {
        guard connectionTonePending else { return }; connectionTonePending = false
        guard let file = Self.resource("call_connected", extension: "wav") else { return }
        stopEffect(); effect = try? AVAudioPlayer(contentsOf: file); effect?.delegate = self; effect?.volume = 0.5; effect?.play()
    }
    func stopCall() { waiting?.stop(); waiting = nil; callID = nil; connectedID = nil; connectionTonePending = false; audioActive = false; stopEffect() }
    func reset() { stopCall(); audioActive = false; packetKeys = []; eventKeys = []; pendingMentions = [:]; mutedConversations = [] }
    func stopEffect() {
        effect?.delegate = nil; effect?.stop(); effect = nil
        if effectOwnsSession { effectOwnsSession = false; try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }
    private func playEffect(_ name: String, ext: String) {
        guard UIApplication.shared.applicationState == .active, CallCoordinator.shared.call == nil, !VoicePlayback.shared.playing, !VoicePlayback.shared.loading, !Self.recording, let file = Self.resource(name, extension: ext) else { return }
        stopEffect()
        do {
            // Ambient respects the silent switch and never interrupts the RTC audio session.
            try AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default, options: [.mixWithOthers]); try AVAudioSession.sharedInstance().setActive(true)
            effectOwnsSession = true
            effect = try AVAudioPlayer(contentsOf: file); effect?.delegate = self; effect?.volume = 0.65; effect?.play()
        } catch { stopEffect() }
    }
    static var recording = false
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in if self.effect === player { self.stopEffect() } } }
}

extension Notification.Name { static let spxMentionReceived = Notification.Name("spx.mentionReceived") }
