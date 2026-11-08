# SPX IM Android 1.78 正式版

- versionName：1.78；versionCode：79。
- 优化红包发送反馈：输入支付密码后立即显示发送进度，支持返回聊天等待服务器确认。
- 新增红包领取系统提示，显示领取人和发红包人，点击“红包”查看详情；服务端已部署。
- 优化语音、视频通话记录为双行气泡，支持点击再次通话。
- 完善应用内更新：暂不更新、跳过本版本、收起下载进度及安装包校验。

## 本次发布验证

- Release 构建通过，非调试包，包含 arm64-v8a、armeabi-v7a、x86、x86_64。
- 正式签名与原版本一致；模拟器覆盖安装、启动通过，原安装身份保留。
- 公网完整 APK 下载 132199274 字节，SHA-256 与本地正式包一致。
- 官网 Android 下载脚本、更新记录、旧版本更新提示、当前版本无重复更新提示均通过。
- HTTP 206 断点/范围下载字节校验通过。
- 未在测试中使用真实资金发放或领取红包。

官网：https://spx001.top/
直链：https://spx001.top/spxchat/api.php?action=app.update.download&id=22
SHA-256：`7bf4b4e15534f1d5b4caca46223662e23afee112eb8af6a7a1151db6d82247b4`
发布时间（UTC）：2026-10-31T06:35:35.118447+00:00
发布元数据备份：`/root/spx-releases/android-1.78-20261031T063310Z-fef5775c/previous-releases.json`
