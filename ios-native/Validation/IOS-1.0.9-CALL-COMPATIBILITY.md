# iOS 1.0.9（10）：兼容现有 Android 一对一通话

本次只修改 iOS。Android 源码、版本号和现有服务端通话协议保持原样。

## 通话流程

1. 从 calls.credentials 获取当前 SPX 账号的 SDKAppID、spx_用户ID 和 UserSig。密钥仍只在服务端。
2. 原生 TUICallEngine 初始化成功并确认当前 RoomEngine 用户后，通过 calls.presence 登记本机就绪状态；前台每 15 秒刷新。
3. 发起方先调用 calls.prepare，使用小写 UUID 建立业务会话，再检查 peer_ready。
4. 使用与现有 Android 相同的 TUICallEngine 单人 call 接口，把业务 UUID 写入 TUICallParams.userData，并指定整数房间号及剩余邀请超时。
5. 接收方从原生 SDK 回调取得会话 UUID，通过 calls.session 验证并认领本机邀请。验证接收账号、呼叫者和媒体类型后展示应用内来电界面。
6. 用户点击接听才请求所需权限并调用 accept。onCallBegin 确认接通后报告 connected，开启音视频及计时；保留静音、听筒/扬声器、前后镜头、关闭摄像头、拖动悬浮窗及恢复通话界面。
7. 通话中每 8 秒刷新业务心跳，离线等待阶段每 2 秒检查对方就绪。拒绝、取消、超时和结束都回传现有 calls.update。

## 边界处理

- 未接通呼叫采用固定原始等待期限，离线重试不会续期。每次重试使用新整数房间；退役 SDK ID、旧房间和其他业务 UUID 的回调不能结束当前呼叫。
- 原生 SDK 观察者先于初始化注册。验证来电期间如收到取消事件，不展示已经过期的来电。
- SDK 成功创建邀请/接受邀请后才启动媒体；来电振铃阶段不自动开启麦克风或摄像头。
- 退出账号时等待旧原生初始化和清理，避免销毁与新账号初始化并发。原生初始化未完成时不重复初始化或销毁单例。
- 视频视图恢复时重新绑定。缩小通话仍保留本机视频采集；进入后台暂停相机，语音沿用既有 audio 后台能力。
- 网络请求使用 8 秒超时；独立的等待超时不会被 HTTP 请求阻塞。短暂 HTTP 故障不直接中断已建立的媒体通话。
- 锁屏/应用被终止后的新来电通知仍依赖尚未配置的 APNs/VoIP 推送。本次来电交付范围为双方 App 在前台。

## SDK

- RTCRoomEngine 3.6.4（腾讯官方归档为 3.6.4.104，与现有 Android 同系列）。
- TXLiteAVSDK_TRTC 12.8.19666。
- TXIMSDK_Plus_iOS_XCFramework 8.8.7373。
- 移除 TUICallKit_Swift / AtomicXCore 调用，使用应用现有通话页面。
- 新增 Objective-C 桥接，直接实现 SDK 真实 selector。官方 3.6.4 的 onCallReceived 头文件带有参数数量不匹配的 swift_name 注解；桥接避免依赖该可选回调在 Swift 中的导入名称。
- 本次仍采用 Android 已有的 TUICallEngine 呼叫协议；不是两端纯 TRTC、自建呼叫信令迁移，也不改变腾讯项目的资源包或授权。

## 验证及限制

- Swift Tree-sitter 语法、资源和本地 API action 检查通过。
- 签名配置脚本 11 项回归通过。
- 登录保留未认领来电的 PHP 策略 9 项通过。
- Objective-C 桥接通过 Clang 类型检查：使用下载的腾讯 3.6.4 真实通话头文件，Foundation/UIKit 使用声明替身；该检查不代表 Apple SDK 编译、链接或 Swift 桥接导入已经验收。
- 原有 HTTP/PDO 通话隔离集成测试未能启动：本机临时 MariaDB 运行时缺失；没有对生产数据库运行测试。
- 当前 Windows 没有 Xcode，尚未执行 XCTest、生成 IPA 或真实双设备通话。本次没有推送 Git、部署服务器或公开发布安装包。

打包前在 macOS 运行 scripts/verify-macos.sh，执行完整 Xcode build/test，再签名。安装后用两个不同账号分别验证 iOS→Android、Android→iOS 的语音与视频，以及拒接、取消、超时、关闭摄像头和悬浮窗恢复。上述实测通过前不应宣称已完成跨端通话验收。
