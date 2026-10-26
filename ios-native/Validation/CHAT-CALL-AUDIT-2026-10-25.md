# iOS 聊天与音视频通话复查 — 2026-10-25

## 结论

签名 IPA 已由用户提供的构建截图确认生成。聊天和一对一语音/视频通话均存在实际实现，不是只有按钮或占位提示。但本次静态审查仍发现消息排序缺陷、音频会话配置差异，以及后台来电唤醒缺口，不能判定全部功能已通过真机验收。

## 本次验证

- 执行 ios-native/scripts/validate_sources.py：67 个 Swift 文件、10,628 行；静态检查未报错；97 个字面 API action 均可在本地后端找到。该工具不等同于 Xcode 编译或设备测试。
- 执行 ios-native/scripts/test_signing_configuration.py：11 项 Python 签名脚本测试通过。
- 仓库中识别到 65 个 Swift XCTest 方法；本机为 Windows，本次没有执行 XCTest 或双设备音视频测试。GitHub CI 查询返回 HTTP 403，因此未将 CI 单测记为通过。
- 已核对 5 个声音资源：message_received.wav、red_packet.mp3、call_ringing.mp3、call_ringing.wav、call_connected.wav。

## 已有实现（源码检查，不等于真机通过）

- 聊天：本地缓存、消息历史补齐、已读、持久化待发队列、客户端消息 ID 去重、失败重试与删除；文字和附件分开处理。
- 媒体：PhotosPicker 相册多选、相机、文件选择、图片/视频预览、录音、播放、上传进度、分块上传、服务端断点检查、本地缓存、视频 Range 播放。
- 通话：腾讯 SDK 登录、业务会话与 SDK call ID 关联、权限检查、拨打/接听/拒绝/取消/超时、离线等待与限时重试、旧回调隔离、静音/扬声器/摄像头切换、悬浮窗、铃声与接通提示音。
- 聊天页加号和好友资料均已调用 CallCoordinator.dial。

## 确认的问题与缺口

### 1. 同时间消息排序不稳定 — 应修复

证据：SpxChatIOS/Core/ChatTimelineEntry.swift:21 使用 date 相同时比较字符串 id。对于相同时间的服务器消息，m-10 会排在 m-9 前；带 client_message_id 的消息改为比较 UUID 字符串，同样不代表发送顺序。

同时 SpxChatIOS/Core/MessageOutbox.swift:212 使用默认 ISO8601DateFormatter 写入 local_created_at，没有保留亚秒精度。发送回执与待发气泡使用不同时间精度，可能加剧发送确认后的位置变化。

建议：保留本地时间精度，给相同时间设置明确且稳定的顺序；已入库消息使用数值 ID，本地待发消息保留入队顺序，并增加同秒、多附件、回执替换回归测试。

### 2. 视频通话的音频模式不一致 — 应统一并实测

证据：CallCoordinator.swift:316 在无 CallKit 路径区分 videoChat/voiceChat；CallCoordinator.swift:471-473 的 CallKit didActivate 路径始终设置 voiceChat。

这证明两条音频配置路径不一致，不能据此判定它就是连接失败原因。建议按实际通话类型统一模式，再验证扬声器、听筒、蓝牙及音频打断恢复。

### 3. App 未运行时的来电唤醒尚不完整 — 已知能力缺口

已检查自有 Swift 源文件：没有 PushKit 导入、PKPushRegistry 注册或 VoIP push 接收回调。当前可见的是普通 APNs 消息通知接入及腾讯在线邀请回调；CallKit 页面接入不能单独提供进程唤醒。

普通消息通知配置与 VoIP 来电唤醒需要分别处理。此前推送服务端凭据由用户暂缓提供，本次没有复核线上密钥状态，也没有擅自部署。

### 4. 后台上传的消息投递仍需生命周期测试 — 待验证，未判定必然丢失

BackgroundMediaTransfer.swift:368-375 完成上传时移除本地上传记录，并向存在的进程内 continuation 返回结果；最终消息发送由 MessageOutbox.swift:196-218 完成。

系统重启进程后，旧 continuation 不存在；后台传输完成不自动证明聊天消息已经投递。后端 chat_upload.php:214-219 能保留并返回最终结果，重新进入待发队列有恢复路径，因此不能直接判为附件丢失。应测试进程重启、后台完成、重新前台以及发送确认的完整闭环。

## 真机验收清单

1. 两台不同账号：iOS ↔ iOS、iOS ↔ Android，分别双向发起语音与视频，确认双向声音和画面。
2. 连续拨打、拒绝、取消、忙线、超时、接通前后挂断、挂断后立即重拨；迟到回调不得结束新通话。
3. B 离线时 A 呼叫；B 在原 63 秒等待期限内上线可接听；过期后不再接通旧呼叫。
4. 通话接通后锁屏、切后台至少数分钟，验证服务端 45 秒心跳失效策略不会造成误挂断；后台收到来电需与上述推送缺口分别判断。
5. 扬声器/听筒/蓝牙切换，系统来电或录音权限拒绝后的恢复，视频悬浮窗缩小与还原。
6. 同秒连续文字、图片视频混发、多选 9 项、大视频、弱网重试、上传中断网及恢复、进程重启与账号切换；不得错序、重复或串号。
7. 再次进入聊天复用缓存；打开键盘、阅读旧消息、加载图片后保持滚动位置；群聊、私聊布局和底部导航需在设备逐页验收。

## 本轮范围

本轮为审查与本地验证；未将以上发现标记为已修复，未进行付款、红包资金操作、对真实用户发消息或采集麦克风/摄像头，也未重新发布客户端。

## Apple 官方参考

- https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit
- https://developer.apple.com/documentation/avfaudio/avaudiosession/mode-swift.struct/videochat
