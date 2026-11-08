package com.spx.chat;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Centralized app-owned UI strings. Server/user supplied text is never passed here. */
final class I18n {
    private static final Map<String, String> KEYS = new HashMap<>();

    static {
        map("app_loading", "正在验证登录状态...");
        map("default_user_name", "SPX 用户"); map("default_chat_name", "会话"); map("self_label", "我");
        map("demo_time", "今天 10:28"); map("demo_message_incoming", "锁仓质押的年化调整了，你看看");
        map("demo_message_reply", "好的，我马上确认一下。"); map("demo_message_latest", "目前年化是 8.2%，锁仓期限也更灵活了。");
        map("login", "登录"); map("register", "注册"); map("welcome_back", "欢迎回来");
        map("create_account", "创建账号"); map("username_or_email", "用户名或邮箱");
        map("password", "密码"); map("forgot_password", "忘记密码？");
        map("send_code", "发送验证码"); map("resend", "重新发送"); map("email_code", "6 位邮箱验证码");
        map("general_settings", "通用设置"); map("language", "语言"); map("chat_background", "聊天背景");
        map("change_password", "修改密码"); map("check_updates", "检查更新"); map("clear_cache", "清除缓存");
        map("about", "关于"); map("update_history", "更新历史"); map("agreement", "用户协议");
        map("sign_out", "退出登录"); map("cancel", "取消"); map("save", "保存"); map("ok", "确定");
        map("chats", "聊天"); map("contacts", "联系人"); map("explore", "生态"); map("me", "我的");
        map("search_chat", "搜索消息、联系人或 ID"); map("message_input", "输入消息"); map("send", "发送");
        map("choose_avatar", "更换头像"); map("gallery", "从相册选择"); map("take_photo", "拍照");
        map("profile_edit", "编辑资料"); map("change_avatar", "点击更换头像"); map("nickname_input", "输入昵称");
        map("question_input", "输入你的问题");
        map("save_profile", "保存资料"); map("my_qr", "我的二维码"); map("add_friend_by_qr", "扫一扫，添加我的 SPX 好友");
        map("clear_history", "清空聊天记录"); map("delete_friend", "删除好友"); map("edit_message", "编辑消息");
        map("delete", "删除"); map("copy", "复制"); map("reply", "引用");
        map("voice_hold", "按住说话"); map("recording", "正在录音"); map("release_cancel", "松开取消");
        map("chat_group", "群聊"); map("new_message", "新消息"); map("online", "在线"); map("offline", "离线");
        map("no_messages", "暂无消息"); map("loading", "正在加载..."); map("retry", "重试");
        map("chat_background_settings", "设置当前聊天背景"); map("choose_gallery", "从相册上传"); map("restore_default", "恢复默认");
        map("language_saved", "语言偏好已保存"); map("background_updated", "聊天背景已更新");
        map("password_changed", "密码已修改"); map("new_password", "新密码（至少 6 位）");
        map("send_and_install", "下载并安装"); map("download", "正在下载…"); map("version_update", "版本更新");
        map("current_latest", "当前已是最新版本"); map("confirm", "确认");
        map("eco_title", "生态"); map("eco_subtitle", "SPX 商城 & 锁仓质押");
        map("store", "SPX 商城"); map("store_desc", "商品、价格和库存来自平台"); map("open_store", "打开商城");
        map("staking", "锁仓质押"); map("staking_desc", "查看真实余额、质押记录和到期状态"); map("view_staking", "查看质押");
        map("video_reward", "刷视频赚积分"); map("video_reward_desc", "观看广告视频，获取 SPX001 积分"); map("developing", "正在开发");
        map("product_detail", "商品详情"); map("loading_product", "正在加载商品详情…"); map("standard_variant", "标准规格");
        map("select_variant", "选择规格"); map("variant_prefix", "规格："); map("stock_prefix", "库存 "); map("purchase_quantity", "购买数量");
        map("select_address", "选择收货地址"); map("product_info", "商品详情"); map("submit_order", "提交订单"); map("redeem_now", "立即兑换");
        map("select_variant_first", "请先选择商品规格"); map("insufficient_stock", "所选规格库存不足"); map("select_address_first", "请先选择收货地址");
        map("orders", "商城订单"); map("order_filters", "全部订单 · 待付款 · 待收货 · 售后"); map("no_orders", "暂无订单"); map("order_number", "订单号：");
        map("address", "地址"); map("my_address", "我的地址"); map("address_subtitle", "用于商城兑换后的收货信息"); map("recipient", "收货人姓名"); map("phone", "手机号码");
        map("detail_address", "详细地址（街道、楼栋、门牌号）"); map("save_address", "保存地址"); map("selected_region", "已选地区："); map("address_saved", "地址已保存");
        map("wallet", "我的钱包"); map("wallet_subtitle", "BSC 主网 · 美元估值"); map("total_assets", "总资产 · USD"); map("deposit", "充值"); map("withdraw", "提现");
        map("wallet_binding", "BSC 钱包绑定"); map("assets", "我的资产"); map("bind_wallet", "绑定 BSC 钱包"); map("wallet_address", "输入你的 BSC 钱包地址");
        map("wallet_balance_hidden", "\u70b9\u51fb\u663e\u793a\u4f59\u989d"); map("wallet_balance_visible", "\u70b9\u51fb\u9690\u85cf\u4f59\u989d");
        map("ui_draft_prefix", "草稿："); map("ui_send_failed_prefix", "发送失败："); map("ui_sending_prefix", "发送中：");
        map("wallet_password", "输入当前账户密码以确认绑定"); map("confirm_bind", "确认绑定"); map("permanent_bind", "永久绑定"); map("all", "全部");
        map("staking_title", "锁仓质押"); map("staking_subtitle", "质押 SPX001 积分，获得生态收益"); map("reading", "读取中"); map("staking_periods", "质押期数");
        map("quota", "分红 + 邀请额度"); map("remaining_quota", "剩余额度"); map("total_staking", "我的质押总额"); map("staking_rules", "质押规则");
        map("select_staking_plan", "选择质押方案"); map("my_staking_records", "我的质押记录"); map("go_stake", "去质押"); map("staking_detail", "质押详情");
        map("confirm_staking", "确认质押"); map("staking_amount", "输入质押数量（SPX001 积分）"); map("staking_submitted", "质押申请已提交");
        map("invite", "邀请好友"); map("invite_subtitle", "下级质押奖励与邀请记录"); map("invite_friends", "邀请好友加入 SPX IM"); map("invite_reward_desc", "好友完成质押后，你可获得奖励");
        map("copy_invite_link", "复制注册链接"); map("show_invite_qr", "显示我的邀请二维码"); map("invite_count", "下级人数"); map("total_reward", "累计奖励 · SPX001 积分");
        map("withdrawable", "可提现余额"); map("go_withdraw", "去提现"); map("my_referrals", "我的下级"); map("invite_loading", "正在读取邀请记录...");
        map("support", "提交意见或工单"); map("support_subtitle", "提交后可查看处理状态"); map("subject", "标题"); map("issue_description", "意见或问题描述"); map("submit_ticket", "提交工单"); map("my_submissions", "我的提交"); map("ticket_submitted", "工单已提交");
        map("general", "通用"); map("logout_confirm", "确定退出当前账号吗？"); map("clear_cache_confirm", "清除本地图片和临时缓存？"); map("clear", "清除"); map("cache_cleared", "缓存已清除");
        map("app_update", "版本更新"); map("update_notes", "更新说明"); map("wait_download", "等待开始下载"); map("downloading", "正在下载…"); map("redownload", "重新下载");
        map("group_create", "创建群聊"); map("group_create_subtitle", "选择联系人后创建群组"); map("group_name", "群名称"); map("choose_members", "选择好友（支持搜索）"); map("confirm_selection", "确认选择"); map("create_group", "创建群聊");
        map("scan", "扫一扫"); map("scan_subtitle", "扫描好友或群组二维码"); map("start_scan", "开始扫码"); map("scan_gallery", "从相册选择二维码"); map("join_by_group_id", "通过群组 ID 加入");
        map("points", "SPX001 积分"); map("select_new_address", "新增地址"); map("know", "知道了"); map("binding_wallet_title", "绑定 BSC 钱包");
        map("binding_confirm", "此地址绑定后不能更改。确认地址无误并永久绑定吗？"); map("return_check", "返回检查");
        map("pin", "置顶聊天"); map("unpin", "取消置顶"); map("mute_on", "开启消息免打扰"); map("mute_off", "关闭消息免打扰"); map("open_chat", "打开聊天");
        map("seconds_retry", "秒后重试"); map("send_failed", "发送失败"); map("email_invalid", "请输入有效邮箱地址"); map("code_sent_check", "验证码已发送，请检查邮箱"); map("code_invalid", "请输入 6 位验证码"); map("password_short", "新密码至少 6 位");
        map("password_reset_login", "密码已重置，请登录"); map("code_sent_view", "验证码已发送，请查看邮箱"); map("email_code_invalid", "请输入 6 位邮箱验证码"); map("username_required", "请输入用户名");
        map("server_format_invalid", "服务器返回格式错误"); map("login_token_missing", "登录凭证缺失"); map("register_success", "注册成功"); map("login_success", "登录成功"); map("update_required", "请先完成版本更新后继续使用");
        map("group_avatar_upload_failed", "群头像上传失败"); map("group_avatar_updated", "群头像已更新"); map("qr_unrecognized", "二维码内容无法识别"); map("qr_user_not_found", "未找到二维码对应的用户"); map("qr_image_empty", "图片中没有识别到二维码");
        map("microphone_required", "需要麦克风权限才能发送语音"); map("camera_required", "需要相机权限才能拍摄"); map("avatar_url_missing", "服务器未返回头像地址"); map("avatar_saved", "头像已保存"); map("camera_start_failed", "无法启动相机拍摄");
        map("record_permission_retry", "授权后请再次按住说话"); map("record_start_failed", "无法开始录音"); map("record_too_short", "说话时间太短"); map("install_permission_missing", "尚未允许安装此来源的应用，可在更新页面重新尝试");
        map("profile_saved", "资料已保存");
        map("loading_ad_points", "正在加载广告积分功能"); map("mentioned_you", "有人在群聊中 @ 了你"); map("realtime_unavailable", "无法连接实时聊天服务"); map("removed_from_group", "你已不在该群聊中"); map("mentioned_prefix", "已 @ ");
        map("loading_voice", "正在加载语音"); map("voice_invalid", "语音格式不支持或文件已损坏"); map("voice_play_failed", "语音播放失败"); map("deleted", "已删除"); map("message_edited", "消息已编辑"); map("message_deleted_everyone", "消息已为所有人删除");
        map("voice_download_failed", "语音下载失败"); map("ui_voice_duration", "[语音 %1$s秒]");
        map("call", "打电话"); map("video_call", "打视频"); map("voice_call_unavailable", "语音通话未开通"); map("video_call_unavailable", "视频通话未开通"); map("video_format_unsupported", "此视频格式暂不支持播放"); map("conversation_profile_missing", "当前会话未关联到用户资料");
        map("question_required", "请先输入问题"); map("question_submitted", "问题已提交，等待回复");
        map("select_variant_first", "请先选择商品规格"); map("insufficient_stock", "所选规格库存不足"); map("select_address_first", "请先选择收货地址"); map("product_detail_failed", "商品详情读取失败");
        map("withdraw_cancelled", "提现已撤销"); map("wallet_bound_immutable", "BSC 钱包已绑定且不可更换"); map("wallet_address_invalid", "BSC 地址格式不正确"); map("wallet_password_required", "请输入当前账户密码确认绑定"); map("wallet_bind_unconfirmed", "服务器未确认绑定，请刷新绑定状态");
        map("clear_history_warning", "将永久删除你和对方双方设备上的聊天记录，好友关系会保留，且无法恢复。"); map("clear_records", "清空记录"); map("delete_friend_warning", "删除后会解除双方好友关系，并删除双方私聊会话和聊天记录。此操作无法撤销。");
        map("ui_splash_desc", "SPX IM 开屏页，点击进入应用"); map("ui_media_uploading_video", "视频上传中…"); map("ui_media_uploading_image", "图片上传中…");
        map("ui_qr_title", "我的二维码"); map("ui_qr_hint", "扫一扫，添加我的 SPX 好友"); map("ui_profile_edit_subtitle", "修改头像和昵称");
        map("ui_profile_id_fixed", "SPX ID：%1$s（注册后不可修改）"); map("ui_copy_unavailable", "%1$s暂不可用"); map("ui_copied", "%1$s已复制");
        map("ui_chat_loading", "正在加载会话..."); map("ui_message_results", "消息结果"); map("ui_contact_results", "联系人结果");
        map("ui_chat_empty", "暂无私聊或群组会话"); map("ui_chat_unavailable", "会话暂不可用，请稍后重试"); map("ui_partial_chat_failed", "部分会话加载失败");
        map("ui_conversation", "会话"); map("ui_contact", "联系人"); map("ui_group_chat", "群聊"); map("ui_no_messages", "暂无消息");
        map("ui_chat_search", "搜索消息、联系人或 ID"); map("ui_contacts_search", "搜索姓名、备注、SPX ID 或用户 ID"); map("ui_contacts_loading", "正在加载联系人...");
        map("ui_my_contacts", "我的联系人"); map("ui_my_groups", "我的群组"); map("ui_no_contact_match", "本地联系人中没有匹配项"); map("ui_no_groups", "暂无群组"); map("ui_no_group_match", "没有匹配的群组");
        map("ui_contact_total", "好友总数  ·  %1$s"); map("ui_user_results", "用户搜索结果"); map("ui_user", "用户"); map("ui_online", "在线"); map("ui_offline", "离线");
        map("ui_member_count", "%1$s 位成员"); map("ui_remark", "备注：%1$s"); map("ui_details", "详情"); map("ui_profile", "个人资料"); map("ui_profile_card", "SPX IM 用户名片");
        map("ui_added_friend", "已添加的好友"); map("ui_spx_user", "SPX IM 用户"); map("ui_not_set", "未设置"); map("ui_set_remark", "点击设置备注");
        map("ui_voice_call", "语音通话"); map("ui_video_call", "视频通话"); map("ui_send_message", "发消息"); map("ui_add_contact", "添加到通讯录");
        map("ui_voice_call_unavailable", "语音通话功能暂未开放"); map("ui_video_call_unavailable", "视频通话功能暂未开放"); map("ui_added_to_contacts", "已添加到通讯录");
        map("ui_profile_unavailable", "用户资料不可用"); map("ui_profile_load_failed", "读取用户资料失败：%1$s"); map("ui_clear_friend_history", "清空双方聊天记录"); map("ui_delete_friend", "删除好友");
        map("ui_history_cleared", "你和对方的聊天记录已清空，好友关系保留"); map("ui_friend_deleted", "好友已删除，双方聊天记录已清除"); map("ui_group_members", "%1$s 位成员");
        map("ui_pinned", "置顶"); map("ui_muted", "免打扰"); map("ui_pin", "置顶聊天"); map("ui_unpin", "取消置顶"); map("ui_mute_on", "开启消息免打扰"); map("ui_mute_off", "关闭消息免打扰"); map("ui_open_chat", "打开聊天");
        map("ui_pinned_done", "已置顶"); map("ui_unpinned_done", "已取消置顶"); map("ui_mute_enabled", "已开启消息免打扰"); map("ui_mute_disabled", "已关闭消息免打扰");
        map("ui_chat_notification_desc", "收到私聊和群聊消息时通知"); map("ui_received_message", "收到一条新消息"); map("ui_profile_invalid", "用户资料格式无效"); map("ui_yesterday", "昨天 %1$s"); map("ui_remark_label", "备注");
        map("ui_image_placeholder", "[图片]"); map("ui_video_placeholder", "[视频]"); map("ui_voice_placeholder", "[语音]"); map("ui_new_avatar", "新"); map("ui_chat_avatar", "聊"); map("ui_group_avatar", "群"); map("ui_quote_invalid", "报价接口未返回有效价格");
        map("quote_prefix", "〔引用〕"); map("quote_separator", "\n——\n");
        map("ui_spx_status", "SPX ID %1$s · %2$s");
        map("ad_reward_subtitle", "观看广告视频获取 SPX001 积分"); map("ad_reward_title", "刷视频看广告赚 SPX001 积分"); map("ad_reward_developing", "刷视频看广告赚积分正在开发中\n敬请期待");
        map("spx_id_loading", "SPX ID 加载中"); map("edit", "编辑"); map("account_assets", "账户与资产"); map("wallet_menu_desc", "余额、充值、提现"); map("orders_menu_desc", "待付款、待收货、售后"); map("address_add_summary", "添加收货姓名、电话和地址"); map("staking_records", "质押记录"); map("staking_records_desc", "查看质押与收益明细"); map("spx_services", "SPX 服务"); map("invite_menu_desc", "查看下级用户及质押奖励"); map("ask_title", "你问我答"); map("ask_menu_desc", "常见问题与在线问答"); map("support_menu_desc", "提交问题并查看处理状态"); map("settings_menu_desc", "通知、隐私、清除缓存"); map("version_format", "版本 v%1$s · SPX");
        map("online_session", "在线会话"); map("new_messages_view", "有最新消息 · 点击查看"); map("switch_voice_input", "切换语音输入"); map("switch_text_input", "切换文字输入"); map("release_cancel_timer", "松开取消  %1$s"); map("recording_timer", "正在录音  %1$s"); map("continue_recording_timer", "继续录音  %1$s"); map("history_cleared_short", "双方聊天记录已清空"); map("friend_deleted_short", "该好友及聊天记录已删除"); map("chat_unread_tab", "◉\n聊天 · %1$s"); map("chat_tab", "◉\n聊天"); map("group_member_code", "%1$s 位成员 · 群号 %2$s"); map("view_join_group", "查看群组并选择加入"); map("delete_everyone", "为所有人删除"); map("message_label", "消息"); map("quote_template", "〔引用〕%1$s：%2$s\n——\n"); map("delete_message_confirm", "删除后，双方都将看不到这条消息。确定删除吗？");
        map("preparing_video", "正在准备视频…"); map("loading_image", "加载图片…"); map("download_percent", "下载中 %1$s%%"); map("video_load_failed_retry", "视频加载失败，点击重试"); map("image_load_failed", "图片加载失败"); map("reading_video_frame", "正在读取视频画面…"); map("tap_to_play", "点击播放");
        map("ask_intro", "关于 SPX 的问题，都可以在这里找到答案"); map("ask_no_answer", "没有找到答案？"); map("submit_question", "提交问题"); map("question_status", "状态：%1$s"); map("my_question", "我的问题"); map("store_subtitle", "精选商品 · SPX 兑换 · 订单管理"); map("layout", "排版"); map("one_column", "1列"); map("two_columns", "2列"); map("three_columns", "3列"); map("redeem_records", "兑换记录"); map("points_balance_loading", "正在读取 SPX001 积分余额..."); map("points_balance", "SPX001 积分余额 %1$s"); map("view_my_orders", "查看我的订单"); map("points_price", "%1$s SPX001 积分");
        map("invalid_product", "商品信息无效"); map("reading_product", "正在读取商品信息"); map("physical_product_subtitle", "SPX 商城 · 实物配送"); map("digital_product_subtitle", "SPX 商城 · 数字商品"); map("stock_product_type", "库存 %1$s · %2$s"); map("physical_delivery", "实物配送"); map("digital_product", "数字商品"); map("select_variant_prompt", "请选择商品规格"); map("variant_label", "规格：%1$s"); map("stock_amount", "库存 %1$s"); map("no_product_detail", "暂无商品详情"); map("order_created_physical", "下单成功，可在商城订单查看发货进度"); map("order_created_digital", "兑换订单已创建");
    }

    static {
        map("call_record_canceled", "已取消");
        map("call_record_rejected", "已拒绝");
        map("call_record_busy", "对方忙线中");
        map("call_record_unanswered", "无人接听");
        map("call_record_offline", "对方暂时不在线");
        map("call_record_interrupted", "连接中断");
        map("call_record_ended", "通话已结束");
        map("call_record_duration", "通话时长 %1$s");
        map("call_record_redial", "点击回拨");
        map("call_record_call_again", "再次呼叫");
        map("call_record_subtitle", "%1$s · %2$s");
        map("call_record_confirm_voice", "给%1$s拨打语音？");
        map("call_record_confirm_video", "给%1$s拨打视频？");
        map("call_record_confirm_hint", "确认后发起通话。");
        map("call_record_voice_action", "语音呼叫");
        map("call_record_video_action", "视频呼叫");
    }

    private static void map(String key, String source) { KEYS.put(source, key); }
    private static void mapKey(String key, String source, String resourceKey) { KEYS.put(source, resourceKey); }

    static String translate(Context context, String language, String source) {
        if (source == null || language == null || "zh-CN".equals(language)) return source;
        String key = KEYS.get(source);
        if (key == null) return source;
        Locale locale = localeFor(language);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(locale);
        Resources resources = context.createConfigurationContext(configuration).getResources();
        int id = resources.getIdentifier(key, "string", context.getPackageName());
        return id == 0 ? source : resources.getString(id);
    }

    static String t(Context context, String language, String key, Object... args) {
        String source = sourceForKey(key);
        String value;
        if (source.equals(key)) {
            Resources resources = context.getResources();
            if (!"zh-CN".equals(language)) {
                Configuration configuration = new Configuration(resources.getConfiguration());
                configuration.setLocale(localeFor(language));
                resources = context.createConfigurationContext(configuration).getResources();
            }
            int id = resources.getIdentifier(key, "string", context.getPackageName());
            value = id == 0 ? key : resources.getString(id);
        } else value = translate(context, language, source);
        if (args == null || args.length == 0) return value;
        return String.format(Locale.ROOT, value, args);
    }

    private static String sourceForKey(String key) {
        for (Map.Entry<String, String> entry : KEYS.entrySet()) {
            if (entry.getValue().equals(key)) return entry.getKey();
        }
        return key;
    }

    static String translateComposite(Context context, String language, String source) {
        if (source == null || source.isEmpty() || "zh-CN".equals(language)) return source;
        String result = source;
        for (Map.Entry<String, String> entry : KEYS.entrySet()) {
            String phrase = entry.getKey();
            if (phrase.length() < 2 || !result.contains(phrase)) continue;
            String translated = translate(context, language, phrase);
            if (!phrase.equals(translated)) result = result.replace(phrase, translated);
        }
        return result;
    }

    private static Locale localeFor(String language) {
        if ("zh-TW".equals(language)) return Locale.TRADITIONAL_CHINESE;
        if ("ja".equals(language)) return Locale.JAPANESE;
        if ("ko".equals(language)) return Locale.KOREAN;
        return Locale.ENGLISH;
    }
}
