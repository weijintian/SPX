<?php
declare(strict_types=1);

return [
    'source_keys' => [
        'ok' => 'api.ok', '请先登录' => 'auth.login_required', '登录已过期' => 'auth.session_expired',
        '用户名或密码错误' => 'auth.invalid_credentials', '账号已停用，请联系平台客服' => 'auth.account_disabled',
        '注册成功' => 'auth.registered', '验证码已发送' => 'auth.code_sent', '验证码已发送，请查看邮箱' => 'auth.code_sent_check_email',
        '验证码错误或已过期' => 'auth.code_invalid_or_expired', '验证码尝试次数过多，请重新获取' => 'auth.code_attempts_exceeded',
        '密码已修改' => 'auth.password_updated', '密码重置成功，请使用新密码登录' => 'auth.password_reset',
        '操作频繁，请稍后再试' => 'api.rate_limited', '服务器错误' => 'api.internal_error',
        '发送成功' => 'chat.message_sent', '消息已发送' => 'chat.message_sent', '消息已读' => 'chat.message_read',
        '消息已编辑' => 'chat.message_edited', '消息已为所有人删除' => 'chat.message_deleted',
        '会话已创建' => 'chat.created', '会话已删除' => 'chat.deleted', '聊天记录已清空' => 'chat.history_cleared',
        '双方聊天记录已清空' => 'chat.history_cleared_both', '群聊已创建' => 'group.created', '已加入群聊' => 'group.joined',
        '已退出群聊' => 'group.left', '群聊已解散' => 'group.dissolved', '群资料已更新' => 'group.updated',
        '群设置已保存' => 'group.settings_saved', '成员角色已更新' => 'group.member_role_updated',
        '成员已移出群聊' => 'group.member_removed', '成员禁言状态已更新' => 'group.member_mute_updated',
        '头像上传成功' => 'upload.avatar_uploaded', '媒体上传成功' => 'upload.media_uploaded',
        '订单已取消，SPX 已退回余额' => 'shop.order_cancelled', '兑换订单已创建' => 'shop.order_created',
        '订单已创建，请等待商家发货' => 'shop.physical_order_created', '质押记录已创建' => 'staking.created',
        '质押本金已赎回，分红已按周期到账' => 'staking.redeemed', '提现申请已提交' => 'wallet.withdrawal_submitted',
    ],
    'messages' => [
        'api.ok' => 'ok', 'api.success' => '操作成功', 'api.bad_request' => '请求参数错误', 'api.unauthorized' => '请先登录',
        'api.forbidden' => '无权执行此操作', 'api.not_found' => '请求的资源不存在', 'api.conflict' => '当前操作无法完成',
        'api.validation_error' => '请求参数校验失败', 'api.rate_limited' => '操作频繁，请稍后再试', 'api.internal_error' => '服务器错误',
        'auth.login_required' => '请先登录', 'auth.session_expired' => '登录已过期', 'auth.invalid_credentials' => '用户名或密码错误', 'auth.account_disabled' => '账号已停用，请联系平台客服',
        'auth.registered' => '注册成功', 'auth.code_sent' => '验证码已发送', 'auth.code_sent_check_email' => '验证码已发送，请查看邮箱', 'auth.code_invalid_or_expired' => '验证码错误或已过期', 'auth.code_attempts_exceeded' => '验证码尝试次数过多，请重新获取', 'auth.password_updated' => '密码已修改', 'auth.password_reset' => '密码重置成功，请使用新密码登录',
        'chat.message_sent' => '消息已发送', 'chat.message_read' => '消息已读', 'chat.message_edited' => '消息已编辑', 'chat.message_deleted' => '消息已为所有人删除', 'chat.created' => '会话已创建', 'chat.deleted' => '会话已删除', 'chat.history_cleared' => '聊天记录已清空', 'chat.history_cleared_both' => '双方聊天记录已清空',
        'group.created' => '群聊已创建', 'group.joined' => '已加入群聊', 'group.left' => '已退出群聊', 'group.dissolved' => '群聊已解散', 'group.updated' => '群资料已更新', 'group.settings_saved' => '群设置已保存', 'group.member_role_updated' => '成员角色已更新', 'group.member_removed' => '成员已移出群聊', 'group.member_mute_updated' => '成员禁言状态已更新',
        'upload.avatar_uploaded' => '头像上传成功', 'upload.media_uploaded' => '媒体上传成功', 'shop.order_cancelled' => '订单已取消，SPX 已退回余额', 'shop.order_created' => '兑换订单已创建', 'shop.physical_order_created' => '订单已创建，请等待商家发货', 'staking.created' => '质押记录已创建', 'staking.redeemed' => '质押本金已赎回，分红已按周期到账', 'wallet.withdrawal_submitted' => '提现申请已提交',
    ],
];
