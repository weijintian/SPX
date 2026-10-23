import Foundation

enum DisplayLabels {
    static func order(_ value: String) -> String {
        ["pending": "待履约", "shipped": "待收货", "delivered": "已完成", "completed": "已完成", "cancelled": "已取消", "refunded": "已退款"][value] ?? value
    }
    static func afterSale(_ value: String) -> String {
        ["none": "无售后", "requested": "等待审核", "return_approved": "等待寄回", "return_shipped": "退货已寄出", "rejected": "申请未通过", "refunded": "已退款", "completed": "已完成"][value] ?? value
    }
    static func review(_ value: String) -> String { ["pending": "审核中", "approved": "审核通过", "rejected": "审核未通过"][value] ?? value }
    static func merchant(_ value: String) -> String { ["pending": "审核中", "active": "正常经营", "awaiting_deposit": "待缴保证金", "rejected": "审核未通过", "closing": "关店结算中", "closed": "店铺已关闭", "frozen": "店铺已冻结"][value] ?? value }
    static func transaction(_ value: String) -> String { ["withdraw": "提现", "deposit": "充值", "exchange": "资产兑换", "redeem": "商品支付", "refund": "订单退款", "red_packet_send": "发出红包", "red_packet_receive": "收到红包", "red_packet_refund": "红包退回", "staking": "质押", "stake": "质押", "dividend": "质押分红", "stake_redeem": "质押赎回", "merchant_deposit": "商家保证金", "merchant_settlement": "商家结算"][value] ?? value }
    static func transactionStatus(_ value: String) -> String { ["pending": "待处理", "processing": "处理中", "sending": "转账中", "completed": "已完成", "confirmed": "已确认", "failed": "未成功", "cancelled": "已取消", "rejected": "未通过", "active": "生效中", "redeemed": "已赎回"][value] ?? value }
}
