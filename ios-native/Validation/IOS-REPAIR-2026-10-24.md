# iOS 修复记录

日期：2026-10-24。对应 [原始 12 项检查报告](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/Validation/IOS-AUDIT-2026-10-24.md)。

## 本轮范围与完成状态

原报告 12 项问题及挂断重拨并发风险的源代码修改已写入，18 个既有 Swift 文件修改、8 个 Swift 文件新增。仅修改 iOS；Android、PHP 后端、签名凭据、版本号均未改动。本记录初次生成时未部署、推送 Git、打包或发布；后续同步与云端验证结果见文末。

完成代码修改不等于通过 Xcode 编译或真机验收。当前 Windows 环境没有 Xcode；后续已通过 GitHub macOS runner 完成编译和 XCTest，见文末。

## 逐项处理

| 原报告问题 | 本轮实现 | 主要文件（均相对于 ios-native） |
| --- | --- | --- |
| 1. 实时消息推进补拉游标，导致中间消息遗漏 | REST 独立游标；逐页补拉；实时消息和发送确认不推进游标；消息与游标一起原子保存；兼容旧缓存并重建可信同步窗口；清空历史时使在途响应失效并继续同步 | Core/ConversationSnapshot.swift、Features/Chat/Conversations.swift、Core/LocalStore.swift |
| 2. 视频上传阻塞文字 | 分离文字提交与媒体上传队列；保留稳定消息和上传 ID；先持久化后发送；网络恢复或回前台仅重试暂时性错误，限制自动重试次数；账号切换使旧任务失效 | Core/MessageOutbox.swift、Core/SessionStore.swift |
| 3. 通话 SDK 异常掉线不能恢复 | 区分 SDK 被踢和应用退出；通过已有后端接口重新获取通话凭据；回前台恢复；挂断清理结束前阻止重拨；SDK 操作超时只恢复 continuation 一次；替换观察者并屏蔽旧回调 | Features/Calls/CallCoordinator.swift、Core/SessionStore.swift |
| 4. 重复刷新、排序、缓存写入 | 消息不变时不重新发布；按变化写缓存；合并列表刷新；限制可见且活跃时的轮询；实时事件先过滤再节流；缓存时间格式器、日期解析与时间分组 | Features/Chat/Conversations.swift、Features/Chat/MessageHistory.swift |
| 5. 主线程原尺寸图片解码 | 独立 actor 后台 ImageIO 缩略解码；有容量上限的内存缓存与按账号隔离的磁盘缩略图；气泡、头像、全屏采用不同尺寸；本地待发图片复用后台解码 | Core/ImageThumbnailCache.swift、Features/Chat/NativeMediaViews.swift、Design/Components.swift |
| 6. 视频分段无法复用、封面不足 | 对齐 1 MB 分段并持久化索引；校验 Content-Range、长度、资源版本；合并同段请求；完整后晋升文件缓存；远程生成封面且限制并发；缓存清理包含缩略图及分段 | Core/VideoRangeCache.swift、Core/ImageThumbnailCache.swift、Core/MediaStorage.swift、Features/Chat/NativeMediaViews.swift |
| 7. 商品视频必须先下载完整文件 | 商品页与聊天复用流式播放；缓冲与错误重试；快速切页防止旧播放器覆盖；离开时取消页面加载 | Features/Mall/Catalog.swift、Features/Chat/NativeMediaViews.swift |
| 8. 红包、兑换后资产显示未刷新 | 成功资金操作统一触发资产失效事件；带当前登录会话标识隔离旧请求；资产与流水合并刷新，回前台重载；覆盖幂等请求恢复和红包已领取状态 | Core/AccountMutation.swift、Core/APIClient.swift、Features/Wallet/Assets.swift |
| 9. 通讯录备注未优先显示/搜索 | 仅在联系人接口入口规范备注字段；清除备注恢复昵称；不把商品/订单 remark 当联系人名；备注事件更新显示、搜索及群消息缓存 | Core/JSON.swift、Core/APIClient.swift、Features/Chat/SocialViews.swift、Core/LocalStore.swift |
| 10. @ 姓名子串误匹配 | 使用 userID + UTF-16 范围 token；编辑、删除、emoji、草稿恢复均按范围更新；同名不确定时移除不可靠绑定；明确删除选中标签保留其他同名成员；兼容旧草稿 | Core/MentionToken.swift、Features/Chat/Conversations.swift |
| 11. 商家订单地址和状态陈旧 | 格式化结构化/旧 JSON 地址；只通过现有卖家授权订单列表读取新状态；进入及操作前复核；状态未验证禁用动作；订单变化通知刷新列表；数字交付参数修正为后端要求的 fulfillment_note；买家详情防止旧刷新覆盖 | Core/OrderPresentation.swift、Features/Merchant/MerchantOperations.swift、Features/Mall/Orders.swift |
| 12. 交易/售后显示内部英文 | 补齐兑换收入支出、仅退款与退货退款等中文标签 | Core/DisplayLabels.swift、Features/Merchant/MerchantOperations.swift |

## 新增 16 个回归测试（已纳入后续云端验证）

ReliabilityTests.swift 共 14 个：联系人备注及清空；姓名前缀 @；emoji/改名；同名歧义与精确删除；草稿/全体成员/非法范围；新旧订单地址；卖家订单跨页；订单移除停止查找；资金事件及中文标签；视频分段对齐；无变化消息不发布；实时 350 先到仍补拉 200→300→350；清空历史不停止后续同步；大附件等待期间其他会话文字独立发送。

VideoCacheTests.swift 共 2 个：重建缓存实例后复用磁盘分段、清理后重新下载；拒绝错位分段响应。新增测试使用受控发送传输和本地 URLProtocol，不需要真实聊天、支付或线上订单。

## 已执行检查

运行 ios-native/scripts/validate_sources.py，扫描应用与测试源码：56 个 Swift 文件、8018 行；未发现语法扫描错误；99 个字面量接口 action 均可在本地后端找到。结果见 [source-validation.json](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/Validation/source-validation.json)。

这项检查不包含 Swift 类型检查、SDK 链接、XCTest 执行、服务器部署验证或业务交易结果。首次本地静态检查中的 xcode_compilation、unit_tests、device_testing 均为 not_run；后续云端编译与 XCTest 已成功，真机验收仍未进行。

## 仍需完成的验收

1. 已完成：GitHub macOS runner 生成 Xcode 项目、解析依赖，编译应用及测试目标、运行 XCTest，并生成未签名设备归档。
2. 两台真实 iPhone：语音/视频发起接听、掉线恢复、快速挂断重拨、前后台、相机/麦克风权限和蓝牙切换。
3. 图片、大视频、文件上传：弱网/断网/重启/取消、上传时发文字、重进会话复用缓存、磁盘空间不足。
4. 群 @ 前缀/同名/emoji/草稿、备注即时更新、长聊天记录滚动与消息补拉。
5. 红包领取及资产刷新、兑换流水、订单发货及数字交付、退款售后状态闭环。需要测试账号、测试余额及受控订单，当前未对线上真实资金做操作。

APNs/锁屏推送依此前要求暂缓；iOS 不接入自动更新。本轮未将这两项加入修复范围。

## 可追溯文件

原文件备份、改动 SHA256 清单及补丁保存在 output/ios-reliability-fix-20261024。清单仅统计本轮 26 个 Swift 源码/测试文件；本报告与检查结果另列为文档产物。

## 后续 Git 推送与云端验证

- 仓库：weijintian/SPX，分支 main。源码修复提交 69c3c527a1db8302e2a69014f9695476543bb8b9；腾讯 SDK 回调参数兼容修正提交 4c28c3f55096be1330674baf02b5e0eeb70aa819。
- 首轮云端编译发现 reject / hangup 的 succ 参数标签不匹配，已补推修正。
- [GitHub Actions 40064414305](https://github.com/weijintian/SPX/actions/runs/40064414305) 已成功：源码检查、依赖解析、iPhone 模拟器编译与 XCTest、未签名设备归档、日志及产物上传均通过。
- 本轮 26 个修复源码/测试文件与此前未同步的 iOS 可用性改动一并推送，总计 37 个文件，远程 blob 哈希核验一致；Android 与签名凭据未上传或修改。
- 本轮没有新增后端代码需要部署。已使用系统信任的 HTTPS 访问线上 me、calls.credentials 接口，均按未登录状态返回 HTTP 401 和“请先登录”；此项仅证明连通及鉴权入口正常，不代表真实账号登录或业务全链路验收。
- 产物：SpxChatIOS-unsigned-archive-28（未签名设备归档）与 ios-validation-28（验证记录）。本轮没有生成签名 IPA；新功能需要重新签名打包并安装后生效。
- 真机双设备通话、弱网媒体、红包及订单资金闭环仍须受控验收。
