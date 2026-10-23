import SwiftUI
import UIKit

struct CallOverlay: View {
    @ObservedObject private var model = CallCoordinator.shared
    @State private var position = CGPoint(x: 0, y: 0)
    @GestureState private var drag = CGSize.zero
    var body: some View {
        GeometryReader { geometry in
            if let call = model.call {
                if model.minimized {
                    Button { model.minimized = false } label: {
                        VStack(spacing: 8) {
                            Image(systemName: call.video ? "video.fill" : "waveform").font(.title2).foregroundStyle(.green)
                            Text(call.name).font(.caption.bold()).lineLimit(1)
                            duration(call).font(.caption2.monospacedDigit())
                        }.padding(12).frame(width: 100, height: 116)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
                            .overlay(RoundedRectangle(cornerRadius: 20).stroke(.white.opacity(0.4)))
                            .shadow(color: .black.opacity(0.18), radius: 14, y: 5)
                    }.buttonStyle(.plain)
                        .position(x: clamp(position.x + drag.width, 62, geometry.size.width - 62), y: clamp(position.y + drag.height, 72, geometry.size.height - 140))
                        .simultaneousGesture(DragGesture().updating($drag) { value, state, _ in state = value.translation }.onEnded { value in
                            position.x = clamp(position.x + value.translation.width, 62, geometry.size.width - 62)
                            position.y = clamp(position.y + value.translation.height, 72, geometry.size.height - 140)
                        })
                        .onAppear { if position == .zero { position = CGPoint(x: geometry.size.width - 70, y: 110) } }
                        .onChange(of: position, initial: true) { _, _ in updateFrame(geometry) }
                        .onChange(of: drag) { _, _ in updateFrame(geometry) }
                        .onChange(of: geometry.size) { _, _ in updateFrame(geometry) }
                } else {
                    ZStack {
                        LinearGradient(colors: [Color(red: 0.08, green: 0.14, blue: 0.24), Color(red: 0.04, green: 0.07, blue: 0.13)], startPoint: .topLeading, endPoint: .bottomTrailing).ignoresSafeArea()
                        if call.video, call.connected {
                            CallVideoView(local: false).ignoresSafeArea()
                            VStack { HStack { Spacer(); if model.cameraEnabled { CallVideoView(local: true).frame(width: 112, height: 164).clipShape(RoundedRectangle(cornerRadius: 18)).padding(.top, 60).id(model.cameraEnabled) } }; Spacer() }.padding(20)
                        }
                        VStack(spacing: 22) {
                            HStack { Button { model.minimized = true } label: { Image(systemName: "arrow.down.right.and.arrow.up.left").font(.title3).frame(width: 48, height: 48).background(.white.opacity(0.12), in: Circle()) }; Spacer(); Text(call.video ? "视频通话" : "语音通话").font(.subheadline); Spacer(); Color.clear.frame(width: 48, height: 48) }
                            Spacer()
                            if !call.video || !call.connected { Avatar(url: call.avatar, name: call.name, size: 96); Text(call.name).font(.title.bold()) }
                            duration(call).foregroundStyle(.white.opacity(0.75))
                            Spacer()
                            if call.outgoing || call.connected {
                                HStack(spacing: 30) {
                                    control(model.muted ? "取消静音" : "静音", icon: model.muted ? "mic.slash.fill" : "mic.fill", active: model.muted) { model.toggleMute() }
                                    control(model.speaker ? "听筒" : "扬声器", icon: model.speaker ? "speaker.wave.3.fill" : "speaker.fill", active: model.speaker) { model.toggleSpeaker() }
                                    if call.video { control("翻转", icon: "arrow.triangle.2.circlepath.camera") { model.switchCamera() } }
                                }
                                if call.video { Button(model.cameraEnabled ? "关闭摄像头" : "开启摄像头") { model.toggleCamera() }.font(.footnote).padding(.top, 4) }
                            }
                            HStack(spacing: 90) {
                                control(call.connected ? "挂断" : call.outgoing ? "取消" : "拒绝", icon: "phone.down.fill", color: .red) { model.end() }
                                if !call.outgoing && !call.connected { control("接听", icon: call.video ? "video.fill" : "phone.fill", color: .green) { model.answer() } }
                            }.padding(.top, 15).padding(.bottom, 45)
                        }.padding(.horizontal, 24).foregroundStyle(.white)
                    }
                }
            }
        }.allowsHitTesting(model.call != nil)
    }
    private func clamp(_ v: CGFloat, _ minValue: CGFloat, _ maxValue: CGFloat) -> CGFloat { min(max(v, minValue), max(minValue, maxValue)) }
    private func updateFrame(_ geometry: GeometryProxy) {
        let origin = geometry.frame(in: .global).origin
        OverlayPresentation.shared.miniFrame = CGRect(x: origin.x + clamp(position.x + drag.width, 62, geometry.size.width - 62) - 62, y: origin.y + clamp(position.y + drag.height, 72, geometry.size.height - 140) - 70, width: 124, height: 140)
    }
    @ViewBuilder private func duration(_ call: ActiveCall) -> some View {
        if let start = call.startedAt { Text(start, style: .timer) }
        else { Text(call.outgoing ? "正在等待对方接听…" : "邀请你进行\(call.video ? "视频" : "语音")通话") }
    }
    private func control(_ title: String, icon: String, active: Bool = false, color: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) { VStack(spacing: 10) { Image(systemName: icon).font(.system(size: 25)).frame(width: 64, height: 64).background(color ?? (active ? Color.white.opacity(0.35) : Color.white.opacity(0.14)), in: Circle()); Text(title).font(.caption) } }.buttonStyle(.plain).accessibilityLabel(title)
    }
}

private struct CallVideoView: UIViewRepresentable {
    let local: Bool
    func makeUIView(context: Context) -> UIView { let view = UIView(); view.backgroundColor = .black; CallCoordinator.shared.attachVideo(view, local: local); return view }
    func updateUIView(_ view: UIView, context: Context) { }
}
