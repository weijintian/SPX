# iOS 第二轮代码审查与完善清单

日期：2026-10-24。

## 范围与结论

本轮仅审查 iOS 客户端及判断接口契约所需的本地 PHP 实现。没有修改业务源码、Android、线上服务器、Git 或签名配置。以下区分源代码可确定的路径、体验增强和尚待真机验收的事项；没有对线上用户执行聊天、付款、红包、下单或通话操作。不能将静态审查当作已完成真机复现，也不能保证尚未覆盖的代码不存在其他问题。

上一轮的 12 项修复及云端编译/XCTest/未签名设备归档通过记录见 [修复记录](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/Validation/IOS-REPAIR-2026-10-24.md)。本报告针对新增边界，不将上一轮已经修复的问题重新列为缺失。

优先级：P1 优先修补（涉及资金恢复或核心通信）；P2 随后的稳定性/体验修补；增强项可在修补后实现。

## 一、需要优先修补的边界

### 1. P1 — 兑换和结算页面允许在恢复旧请求完成前创建新请求

- 代码： [Assets.swift:198](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Wallet/Assets.swift:198)、同文件 200、209 行；[Orders.swift:62](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Mall/Orders.swift:62)、68—85 行。
- 兑换页先等待行情请求，再读取 exchange-pending。行情失败会直接跳过恢复，页面仍未冻结；下一次提交用 save 覆盖原待核对记录。结算页先等待地址请求再读 checkout-pending，加载期间也未禁止创建新的支付流程。
- 影响：上一次结果不确定时，用户可能进入另一笔新申请，旧请求恢复标识可能丢失。代码确认存在恢复窗口；本轮没有执行交易，也没有证据认定已经重复扣款。
- 完善方向：先按账号恢复本地待核对记录；完成恢复前禁用提交；使用原子保留/校验避免覆盖旧请求；行情和地址加载不阻断旧结果核对。
- 验收：保存待核对申请后重启，令行情失败或地址请求延迟；提交入口保持冻结，恢复原 request ID，核对完成后才能创建新请求。

### 2. P1 — 通话已经显示接通，但开麦仍等待业务接口

- 代码： [CallCoordinator.swift:198](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Calls/CallCoordinator.swift:198)，198—206 行；[APIClient.swift:30](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/APIClient.swift:30)。
- connected 回调先显示接通、开始计时，随后 await calls.update 完成，才调用 openMicrophone。业务接口弱网/超时时，已经建立的媒体通道也被延迟开麦。
- 影响：存在显示接通但暂时无声的确定执行路径。openMicrophone 的失败回调和远端视频 onError 也为空，缺少可见错误与状态恢复；接听失败仅 action.fail，缺少应用内明确反馈。
- 完善方向：媒体激活不等待业务状态上报；上报单独重试；处理开麦、接听、远端视频失败并使界面状态与媒体状态一致。
- 验收：在两台受控真机间让 calls.update 变慢，媒体仍应及时开通；权限、SDK 开麦失败时有明确反馈。当前未做双机测试。

### 3. P2 — 清缓存会保留已取消的下载任务，后续重试可能持续失败

- 代码： [MediaStorage.swift:164](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/MediaStorage.swift:164)，164—179、191—197 行。
- clear 更换账号 generation 并 cancel 任务，却没有从 tasks 移除。原任务结束时 defer 又因 generation 不同而跳过移除。之后同一 URL 继续复用已取消的 task。
- 影响：文件、语音以及依赖完整文件缓存的媒体在清理缓存后可能反复加载失败，直到进程重启。
- 完善方向：清理时移除对应任务；以独立任务身份保护清理，防止旧任务误删新任务。
- 验收：在下载中清缓存，立即重新打开同一文件，能创建新下载；旧回调不能覆盖新缓存。

### 4. P2 — 商品视频失败后，续传所需的本地源文件会被删除

- 代码： [ProductEditor.swift:288](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Merchant/ProductEditor.swift:288)，288—297 行；[BackgroundMediaTransfer.swift:365](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/BackgroundMediaTransfer.swift:365)。
- 商品视频采用新的 UUID 发起上传；不论成功或失败，defer 都删除源文件。后台上传失败会保留暂停记录，但恢复时源文件可能已不存在；页面没有保留同一上传 ID 的重试入口。
- 影响：聊天附件已有的续传能力不能等同于商品视频也具备完整续传，失败后商家通常要重新选择、重新上传。
- 完善方向：保存商品编辑上传任务、原文件及 ID；成功或用户放弃后再清理；恢复草稿时还原任务。
- 验收：商品视频分块上传中断、重启，继续原任务和已接收分块；放弃时清理对应状态。

### 5. P2 — 新商品发布超时后再次提交，可能生成重复商品

- 代码： [ProductEditor.swift:299](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Merchant/ProductEditor.swift:299)，299—315 行；本地接口 [mall.php:174](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/spxchat/mall.php:174)、233—234 行。
- 客户端新发布没有稳定 request ID 或待核对结果；仅编辑旧商品时提交 id/version。接口在没有 id 时直接 INSERT。
- 影响：服务器已创建商品但响应丢失，用户再按提交可能再次创建。这是代码路径风险，并非本轮已复现的线上重复记录。
- 完善方向：稳定发布请求标识和结果查询；共享接口需配合幂等处理，单纯禁用按钮不能解决响应丢失后的重复发布。此项不能宣称只改 iOS 界面即可完全闭环。
- 验收：模拟提交成功后响应断开，再核对/重试仍返回同一商品。

### 6. P2 — 用户正在看历史消息，新消息仍会自动被标成已读

- 代码： [Conversations.swift:183](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Chat/Conversations.swift:183)，183—188、387—402 行。
- REST 连续游标已修正，但 read 上报只参考同步边界，并不参考用户是否停留在底部。UI 明明显示新消息提示，服务层仍可能清掉这些消息的未读状态。
- 完善方向：同步游标与可见已读位置分离；按前台、页面可见和已看到的消息边界上报。
- 验收：向上滚动看历史，新消息到来仍保持未读；回到底部看到后才推进已读。

### 7. P2 — 待发附件和服务器消息没有统一时间轴，发送期间位置可能跳动

- 代码： [Conversations.swift:353](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Chat/Conversations.swift:353)，353—385、404 行。
- 所有服务器消息先渲染，所有 pending 消息单独追加在最后。上传视频时随后到来的文字会出现在 pending 视频前面；确认落库后气泡还可能换位置。
- 完善方向：用稳定 client ID 和本地创建时间合并时间轴；确认回执替换原气泡，保留滚动位置。
- 验收：视频上传中双方交错发送文字，待发气泡不跨消息跳动；确认后不出现重复气泡。

### 8. P2 — 媒体缓存容量只有软约束，失败附件也不在统一回收范围

- 代码： [MediaStorage.swift:212](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/MediaStorage.swift:212)，212—226 行；[SessionStore.swift:132](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/SessionStore.swift:132)；[ProductEditor.swift:280](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Merchant/ProductEditor.swift:280)、[Account.swift:57](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Profile/Account.swift:57)。
- 默认 512 MB 回收只在启动服务调用，并只删除超过 24 小时的文件，不能约束当天大量媒体。attachments/upload-state/upload-parts 没有纳入同一预算；部分表单上传仅成功才删除源文件，失败会留下文件。
- 完善方向：运行时容量/磁盘阈值，按最近使用回收；保护发送中的文件；统一清理无引用附件及失效状态；设置页展示真实占用。
- 验收：当天持续看视频超过阈值也会回收；失败/取消上传不永久堆积；进行中的上传不被清走。

### 9. P2 — 大视频先复制后校验，缺少导入前的空间与大小提示

- 代码： [MediaStorage.swift:38](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/MediaStorage.swift:38)，38—58、67—71 行。
- PickedMovie 先将视频复制到临时目录，再复制到 attachments；1 GB 检查到 MediaUploader.upload 才执行。导入阶段没有预检磁盘空间或可取消的准备进度。
- 影响：大文件会先等待、占用额外磁盘，再提示超限或存储失败，给人卡住的感觉。并不表示现有分块发送完全不可用。
- 完善方向：能取得大小时提前判断，导入时检查可用容量、限制峰值占用并及时反馈；避免不必要的第二份副本。
- 验收：超限、低空间、iCloud 相册下载场景都能尽早说明原因且不留临时垃圾。

### 10. P2 — 加群页搜索结果与当前输入没有绑定

- 代码： [SocialViews.swift:140](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Chat/SocialViews.swift:140)，140—150 行。
- 搜索到群 A 后修改输入为群 B，result 仍保留群 A；加入按钮读取当前输入 code，而不是搜索结果的不可变群号。异步搜索也无请求身份保护和加入中状态。
- 影响：显示的群与实际请求加入的群可能不一致，跳转记录还可能混用旧名称。
- 完善方向：输入变化清结果；查询响应与原查询绑定；加入只使用已确认结果的群号；提交中防重复点击。
- 验收：A 搜索完成后改 B，A 的加入入口立即失效；旧响应不能覆盖新结果。

### 11. P2 — 经营概览首次请求失败，会同时显示申请开店入口

- 代码： [MerchantDashboard.swift:19](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Merchant/MerchantDashboard.swift:19)，19—21、53—55 行。
- 初始 merchant 为空；catch 把 loaded 设为 true，所以请求失败也满足 merchant.id == 0，显示申请开店，尽管页面底部有错误提示。多个 load 也缺少旧响应保护。
- 完善方向：区分加载中、确认未开店、成功商家数据和加载失败；失败时保留旧数据/重试，不能把未知状态当未开店。
- 验收：已开店账号首次加载断网，不出现未注册商家的引导；快速重刷只采纳最新请求。

### 12. P2 — 退出登录先等待网络，弱网下本机不会立即退出

- 代码： [SessionStore.swift:162](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/SessionStore.swift:162)，162—176 行。
- logout await auth.logout 后才 expire；在请求等待期间 token、用户视图和本地服务仍保留。
- 完善方向：先完成本地退出，使用原凭据进行受控的服务器撤销；处理撤销失败，不让用户等待网络才能回登录页。
- 验收：断网退出仍立即回到登录页并停止本机通话、消息服务；旧请求不会影响下一账号。

## 二、常用交互增强（当前设计限制，不能误报为功能完全未实现）

### 13. 图片发送增加原图/格式选择

- 证据：[MediaStorage.swift:44](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/MediaStorage.swift:44)，44—48 行统一首帧缩略到 2048 像素并转换 JPEG。
- 现状：可发送压缩图片；相册 GIF 会成为静态首帧，透明 PNG 透明度丢失；没有原图开关。
- 建议：默认压缩保留，增加原图发送、支持动图及透明图的可接受格式；明确大小限制。不是重新实现已接通的照片选择/发送。

### 14. 语音消息补足听取体验

- 证据：[VoicePlayback.swift:20](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Chat/VoicePlayback.swift:20)、36—44 行。
- 现状：已有下载指示、播放/停止与动画，但气泡不显示秒数/未听状态，播放使用固定 playback 路径，未见听筒切换或近耳处理。
- 建议：显示时长、未听标记和播放完成状态，支持听筒/扬声器选择；音频中断、蓝牙切换需要受控真机验收，不能仅凭代码认定 SDK 已处理完整。

### 15. 大文件下载增加可见进度与取消入口

- 证据：[Conversations.swift:661](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Features/Chat/Conversations.swift:661)、[MediaStorage.swift:165](C:/Users/雷神/Documents/HBuilderProjects/SPX聊天/ios-native/SpxChatIOS/Core/MediaStorage.swift:165)。
- 现状：文件点击后等待下载完成才打开预览；与语音气泡不同，没有文件下载进度、取消或下载中状态。
- 建议：显示大小、下载进度、取消/失败重试；避免同一文件反复点击造成用户误判。现有同 URL 下载合并可以保留。

## 三、建议的实施顺序

1. 资金恢复入口与通话媒体激活（1—2）。
2. 媒体清理/商品视频恢复、发布幂等、真实已读、统一时间轴（3—7）。
3. 存储预算、导入预检、加群/经营状态、退出登录（8—12）。
4. 原图/动图、语音听取、大文件下载反馈，再统一页面层级、按钮反馈和空/错/加载态（13—15）。

页面设计建议随对应流程修补：发布页提供稳定缩略图位置、单图失败重试与视频准备/上传状态；经营概览区分错误与未开店；聊天保持待发气泡位置、未读提示和媒体加载反馈。先解决这些真实等待和状态问题，再调整间距/图标，避免只有视觉变化。

## 四、验证状态及范围外事项

- 本轮为人工代码与本地接口契约审查；没有新增业务代码或重新构建 IPA，没有对线上真实余额、消息、红包和订单操作。
- 尝试运行既有 validate_sources.py，但在解析器导入阶段报 ModuleNotFoundError: tree_sitter._binding，因此本轮没有获得新的本地语法扫描结果。该工具依赖问题不能等同于 App 编译失败；上一轮云端 Xcode/XCTest 成功记录仍保留。
- 尚需两台受控真机进行通话、蓝牙/音频中断、弱网上传/续传、红包领取与资产变化、下单/发货/收货/售后闭环验收。已有红包领取界面及后端交易锁，不应再笼统说红包没有实现；本轮没有证据认定现版本必然无法抢红包。
- 用户此前暂缓的 APNs/锁屏推送不计入本轮缺陷清单；iOS 自动更新按要求不接入。Android 不在本轮范围。
