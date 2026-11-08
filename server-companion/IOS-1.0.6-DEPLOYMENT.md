# iOS 1.0.6 (7) 配套部署

工作日期：2026-10-25。iOS 客户端需要重新构建和安装 IPA 才能生效。

## 服务端已完成

- 安装 push_notifications.php、push_worker.php 和 call_session_policy.php。
- 按唯一锚点合并 db.php、index.php、ws-event.php、auth.php、chat_calls.php，保留线上其他实现和配置。
- 新增 push_devices、push_queue 表。未配置 APNs，远程投递保持禁用。
- 尚未认领的来电在接收方登录后保留；已经被旧设备认领的通话仍按单设备登录规则清理。
- PHP 语法检查、33 项通知策略测试、9 项通话登录策略测试通过。未使用真实账号或产生交易。
- 部署后文件哈希一致，重复预检无待修改文件。Nginx、PHP-FPM、WebSocket 服务均为 active。
- 正式 HTTPS 的 TLS 校验通过；me、calls.credentials、calls.session、push.device.register、upload.message.status 在无登录凭据时均返回预期 401。

回滚备份：/root/spx-releases/push-1.0.6-20261024T201128Z。
备份目录时间来自服务器 UTC 时钟；工作日期与服务器时间标记分别保留。

## 验证边界

未进行真实账号登录、图片视频发送、双机音视频通话或红包资金闭环测试。
上述服务核验不等于这些实际业务已经通过验收。
APNs 密钥和 Android 厂商推送尚未配置；应用退出后的远程通知仍不可用。
本次没有生成签名 IPA、安装 iPhone 或更新公开 APK 下载记录。
