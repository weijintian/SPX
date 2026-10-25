import SwiftUI

struct EcosystemView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                NavigationLink { MallHomeView() } label: {
                    EcosystemCard(title: "商城", detail: "发现好物，安心购物", badge: "进入商城", image: "https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?w=900", icon: "store", color: SPXColor.primary)
                }
                NavigationLink { StakingView() } label: {
                    EcosystemCard(title: "质押", detail: "查看质押计划与收益记录", badge: "查看质押", image: "https://images.unsplash.com/photo-1639762681485-074b7f938ba0?w=900", icon: "chart.line.uptrend.xyaxis", color: .orange)
                }
                NavigationLink { VideoRewardIntroView() } label: {
                    EcosystemCard(title: "视频奖励", detail: "了解视频奖励与活动进展", badge: "筹备中", image: "", icon: "play.rectangle", color: SPXColor.success)
                }
            }.buttonStyle(.plain).padding(16)
        }.navigationTitle("生态").spxPage()
    }
}

private struct EcosystemCard: View {
    let title: String
    let detail: String
    let badge: String
    let image: String
    let icon: String
    let color: Color
    var body: some View {
        HStack(spacing: 16) {
            Group {
                if !image.isEmpty { CachedImage(source: image) }
                else { Image("reward_video").resizable().scaledToFill() }
            }.frame(width: 82, height: 82).clipShape(RoundedRectangle(cornerRadius: 16))
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.system(size: 19, weight: .semibold)).foregroundStyle(SPXColor.ink)
                Text(detail).font(.system(size: 13)).foregroundStyle(SPXColor.muted).fixedSize(horizontal: false, vertical: true)
                Text(badge).font(.system(size: 12, weight: .medium)).foregroundStyle(color)
                    .padding(.horizontal, 9).padding(.vertical, 4).background(color.opacity(0.09), in: Capsule())
            }.frame(maxWidth: .infinity, alignment: .leading)
            SPXIcon("chevron").frame(width: 18, height: 18).foregroundStyle(SPXColor.muted.opacity(0.5))
        }.padding(16).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(SPXColor.line, lineWidth: 0.7))
    }
}

private struct VideoRewardIntroView: View {
    var body: some View {
        EmptyState(title: "视频奖励筹备中", description: "服务开放后将在这里展示奖励活动。", icon: "play.rectangle")
            .frame(maxHeight: .infinity).navigationTitle("视频奖励").spxPage()
    }
}
