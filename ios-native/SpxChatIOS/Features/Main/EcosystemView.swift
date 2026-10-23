import SwiftUI

struct EcosystemView: View {
    private let columns = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("连接生活与价值").font(.largeTitle.bold())
                    Text("购物、经营与资产，一处轻松管理").font(.subheadline).foregroundStyle(.secondary)
                }.padding(.top, 16)
                NavigationLink { MallHomeView() } label: {
                    HStack(spacing: 18) {
                        Image(systemName: "bag").font(.system(size: 38, weight: .light)).frame(width: 70, height: 76).background(.white.opacity(0.15), in: RoundedRectangle(cornerRadius: 22))
                        VStack(alignment: .leading, spacing: 8) { Text("SPX 商城").font(.title2.bold()); Text("发现好物，安心购物").font(.subheadline).opacity(0.8) }
                        Spacer(); Image(systemName: "arrow.up.right")
                    }.padding(22).foregroundStyle(.white).background(LinearGradient(colors: [SPXColor.primary, Color(red: 0.17, green: 0.47, blue: 0.94)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 26))
                }.buttonStyle(.plain)
                Text("常用服务").font(.title3.bold())
                LazyVGrid(columns: columns, spacing: 14) {
                    destination("商家中心", subtitle: "经营概览与商品管理", icon: "storefront") { MerchantOverviewView() }
                    destination("我的资产", subtitle: "余额、充值与提现", icon: "creditcard") { WalletView() }
                    destination("质押中心", subtitle: "查看计划与收益", icon: "chart.line.uptrend.xyaxis") { StakingView() }
                    destination("我的订单", subtitle: "物流与售后服务", icon: "shippingbox") { OrdersView() }
                }
                HStack(spacing: 12) { Image(systemName: "play.rectangle").font(.title2); VStack(alignment: .leading, spacing: 4) { Text("视频奖励").font(.subheadline.weight(.medium)); Text("与安卓端一致，当前服务尚未开放").font(.caption).foregroundStyle(.secondary) }; Spacer(); Text("筹备中").font(.caption).foregroundStyle(.secondary) }.padding(18).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 20))
            }.padding(18)
        }.navigationTitle("生态").spxPage()
    }
    private func destination<Content: View>(_ title: String, subtitle: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
        NavigationLink(destination: content()) {
            VStack(alignment: .leading, spacing: 16) {
                Image(systemName: icon).font(.system(size: 25, weight: .regular)).foregroundStyle(SPXColor.primary).frame(width: 48, height: 48).background(SPXColor.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 5) { Text(title).font(.headline); Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(18).background(SPXColor.card, in: RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(.plain)
    }
}
