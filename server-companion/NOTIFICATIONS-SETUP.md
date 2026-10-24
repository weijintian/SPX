# 消息通知与角标接入

通知服务端代码和设备注册表已随 iOS 1.0.6 配套部署。当前尚未提供 APNs 密钥或 Android 推送服务配置，远程投递仍保持禁用。部署验证见 IOS-1.0.6-DEPLOYMENT.md。

## 当前可用代码

- iOS：在线事件生成系统通知；正在阅读的会话不重复提醒；去重、免打扰、@提醒、角标、已读清除、通知跳转和账号隔离。设置 → 消息通知可以管理提醒、预览、角标及系统权限。
- Android：沿用实时消息连接，新增标准系统通知和汇总角标、权限申请、已读清除、通知跳转、设置开关。数字或圆点的表现由桌面实现及系统通知设置决定。
- iOS 进入后台后当前会停止普通实时消息连接，未启用 APNs 前不能依靠该连接持续获得后台提醒。Android 也未接入厂商/FCM 推送，进程被结束后的提醒尚不可保证。
- 不通过循环播放音频、伪造前台服务或电池豁免实现消息保活。

## 服务端文件

`push_notifications.php`、`push_worker.php` 放入现有 `spxchat` 目录。同名源文件也已放在仓库 `spxchat/` 中。

`notifications-integration.patch` 记录 `db.php`、`index.php`、`ws-event.php` 的接入差异。部署前应对照服务器当前版本合并，避免覆盖此前已部署的登录、来电、商城修复。新增的 `push_devices`、`push_queue` 表纳入现有数据库 schema 版本机制。

设备注册仅使用当前有效登录会话；设备或 token 换绑会清除旧队列；worker 会再次确认会话有效、群成员/会话仍存在、消息未撤回、未被已读及免打扰设置。拉黑始终禁止投递，只有群 @ 可以越过群免打扰，私聊 @ 不能越过免打扰。

当前服务端实现 APNs；Android 服务商还未确定，不能将 APNs 注册接口当作 Android 远程推送已经接通。

## 后续提供 APNs 后的配置

在服务器的受限、非 Web 目录放置 `.p8` 文件，不上传到 Git，也不放到公开下载目录。向 PHP-FPM 和 worker 提供以下环境变量：

```text
SPX_APNS_ENABLED=1
SPX_APNS_P8_PATH=/restricted/path/AuthKey.p8
SPX_APNS_KEY_ID=<Apple Key ID>
SPX_APNS_TEAM_ID=<Apple Team ID>
SPX_APNS_TOPIC=<实际签名应用的 Bundle ID>
```

默认不配置或 `SPX_APNS_ENABLED=0` 时不入队、不连接 APNs。需要 PHP 的 OpenSSL、mbstring、PDO MySQL 和支持 HTTP/2 的 cURL 扩展。Key ID、Team ID 与 topic 必须属于实际签名应用。

可由进程管理器循环调用或 cron 定期运行 `php /var/www/html/spxchat/push_worker.php`。脚本每次最多处理 100 项，使用数据库互斥锁、有限重试、十分钟到期与失效 token 清理；不输出 token、私钥或消息内容。

iOS 签名脚本只在描述文件含 `aps-environment` 时生成对应签名 entitlement 和 `SPXPushEnvironment`，并核验导出的签名及 Info.plist。没有推送 entitlement 的描述文件仍可打包，应用不会反复发起无效 APNs 注册。纯系统 alert 推送不要求通过 silent push 常驻应用。

## 上线前需要验证

1. Mac 的 Xcode 构建和 XCTest；具备推送能力的真实签名 IPA。
2. 开启/拒绝通知权限；iPhone、Android 桌面未读数量，手机系统关闭角标的表现。
3. 当前会话、其他会话、群 @、免打扰、内容预览关闭、已读、退群、删除会话与切换账号。
4. 前台、后台、锁屏、断网恢复、进程被结束；确认不会重复横幅或提醒旧账号。
5. 配置真实 APNs 后测试 token 注册、JWT 与 HTTP/2 投递、失效 token、重试、队列数据库迁移；本轮离线策略检查不等于这些集成测试。

本轮不包含通话 VoIP/PushKit 来电推送；普通消息 APNs 也不能保证所有系统状态下的即时投递。
