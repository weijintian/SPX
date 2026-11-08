# iOS 全应用对照 Android UI 审查清单

审查日期：2026-10-24。范围：当前工作区 iOS 与 Android 界面源码；本轮只审查和整理，不修改业务代码、不部署、不打包。

## 结论与边界

用户要求是整个 iOS 应用的页面、图标、入口布局和常用交互对齐当前 Android，而非只调整聊天页。当前 iOS 仍混用了系统 List/Form/TabView、SF Symbols 和自行设计的卡片布局，尚未完成全应用统一。已有功能代码不等同于已完成实机验收。Android 作为应用内部界面基准；系统相册、相机、权限与系统安全区域保留 iOS 原生适配。维持既有 API；不修改 Android；不新增 iOS 自动更新；APNs/锁屏推送仍按用户此前要求暂缓。

## 完整改造范围

| 模块 | 需要对齐的页面和细节 |
| --- | --- |
| 1. 全局设计 | 按 Android 各模块实际配色统一字体层级、背景、卡片、间距、圆角、分隔线、按钮、输入框、头像占位、图标笔画和触控区域。Android 主界面与商城本身有模块色差，不能只改一个全局蓝色。 |
| 2. 主导航与返回 | 四个主入口、悬浮底栏、图标文字、选中态、标题栏、返回按钮、二层页面底栏显隐、重复点击主入口返回根页。逐条核查从资料、订单、扫码、通知及深链进入后的返回路径。 |
| 3. 登录注册 | 登录、注册、验证码、忘记密码、重置密码、协议勾选、输入校验、提交状态；对齐品牌区、表单和按钮位置。 |
| 4. 聊天首页 | 顶栏、搜索、右上角加号、加入群组/创建群聊操作、会话头像、摘要、时间、未读、免打扰、置顶和空态；首页不显示群人数。 |
| 5. 私聊与群聊 | 聊天标题、群人数仅在群聊内显示、头像/群备注、气泡宽度与颜色、时间条、消息状态、底部输入栏、语音切换、键盘、表情、四列更多菜单、@选择、长按菜单。保留之前已修的首屏定位及不主动拉动历史位置逻辑。 |
| 6. 通讯录与资料 | 通讯录、搜索、添加好友、好友资料、备注、发消息、语音/视频按钮、删除好友、二维码和扫码。图标不重复，备注显示一致。 |
| 7. 群与会话管理 | 建群、入群、群资料、群成员网格、邀请/移除、公告、权限、二维码、查找记录、定时消息、自动回复、背景、退群/解散；按 Android 入口与分组排列。 |
| 8. 图片视频文件语音 | 相册选择后的应用内预览、待发送媒体、上传进度、失败重试、气泡尺寸、视频封面/时长、语音条、文件卡片、全屏预览；左右手势浏览、右上三点保存/转发。 |
| 9. 语音视频通话 | 呼出、来电、接通中、通话中、结束、断线提示，头像/昵称、接听/拒绝/挂断、静音/扬声器/摄像头、视频大小画面和可拖动最小化浮窗。沿用已修的信令和会话隔离逻辑，不因换 UI 另写呼叫协议。 |
| 10. 红包 | 独立发送页、普通/拼手气选择、金额/个数/祝福、支付确认、消息红包卡片、铜钱拆红包弹层、领取详情、已领/领完/过期/失败状态与记录。当前 iOS 发送页为系统 Form，密码也直接位于表单，与 Android 提交后确认密码流程不同。 |
| 11. 生态首页 | 改回 Android 的商城/质押/视频奖励纵向入口卡片与配图、说明、标签；当前 iOS 的商城大渐变卡与常用服务网格不是同一布局。 |
| 12. 商城及店铺 | 商城搜索、分类、类型筛选、商品网格、店铺列表、店铺主页、收藏、评价、加载更多、空态和错误态；卡片、主图比例、标题与价格排版统一。 |
| 13. 商品详情与结算 | 主图/视频、商品说明、规格、库存、店铺、咨询、收藏/分享、底部操作栏；确认订单、地址、数量、运费、锁价、支付确认与结果。保持 CNY 固定价、SPX001 动态估算及既有报价契约。 |
| 14. 买家订单与售后 | 订单状态筛选、列表卡片、详情、物流、数字交付、核销码、取消/收货、退款/退货、凭证、售后进度、评价、收货地址新增/编辑/默认地址。 |
| 15. 经营概览 | 对齐 Android 的店铺状态卡、经营数据、快捷入口、待办、保证金和店铺管理动作；目前 iOS 是独立四宫格指标和大型发布渐变卡，数据项/顺序也不同。 |
| 16. 商家经营与发布 | 商品管理、批量操作、草稿、发布/编辑商品、批量主图、横向缩略图、首图/删除、规格库存、详情图/视频、服务条款、保存/发布；商家订单、发货/售后、入驻、店铺资料、资质、门店地图、保证金/升级/关店入口逐页对齐。 |
| 17. 资产与金融 | Android 资产总览头卡、余额显示/隐藏、币种行和操作区；充值、提现、兑换、钱包绑定、交易/红包记录、质押和赎回。当前 iOS 资产首页已过滤 SPX，但总览布局及余额隐藏交互未对齐。金额展示统一最多两位，提交和计算继续保留服务端需要的精度。 |
| 18. 我的与个人设置 | Android 渐变个人卡、头像、昵称、SPX ID/复制、二维码、编辑资料、账号资产/SPX 服务/通用入口分组；安全、密码、权益、收藏、邀请、帮助/反馈/提问、设置、协议与版本记录全部对照。iOS 不接自动更新这一例外保留。 |
| 19. 地图与分享附属页 | 发送位置、门店定位、地图导航、联系人/商品/群卡片、转发对象选择、二维码结果处理；对齐应用内标题栏、选择态、确认按钮与退出路径。 |
| 20. 通用状态与适配 | 中间短提示、成功/错误/危险确认、加载占位、空态、断网重试、权限拒绝、上传失败；不同屏宽、长昵称/商品名、键盘、安全区、屏幕旋转及文字放大适配均需验收。 |

## 本次源码中额外确认的问题

1. 全局组件不统一：Components.swift 使用系统 grouped 背景、统一圆角20与系统按钮；Android 自绘主底栏、图标和商城组件，不能只在现有系统 Form 上换颜色。
2. 主导航仍是系统 TabView；只有聊天相关代码显式隐藏 tabBar，其他商城/订单/商家等二层路线的显隐还需按 Android 规则统一。是否在每条路线实际可见需设备验证。
3. 主入口切换通过重置 UUID 重建 NavigationStack，实现返回根页；同时也重建页面局部状态并触发 task。后续需将回根页与缓存/刷新分开，避免因导航重建反复空白与请求。此处是源码风险，不是已完成性能测量。
4. 商城首页既有首次 task 加载，又有搜索/分类 task 的初始加载，存在重复 products 请求路径。已有请求标识避免旧结果覆盖，仍应合并首次请求来减少浪费。
5. 群设置先请求 settings 再请求群信息，部分资料页/列表页没有一致的首屏加载和失败状态。需要统一占位与重试，不能将等待时的空内容当最终空态。
6. 添加好友成功、保存备注成功、清理缓存成功等通过 error 状态触发通用 alert；成功反馈应使用统一成功提示，不与错误提示共用命名和呈现。
7. 商家经营概览只要 merchant 存在就显示发布新商品入口，未像 Android 一样仅在 active 状态显示；需按审核/营业状态控制入口。服务器是否拒绝仍依接口规则，不能据此认定后端存在权限漏洞。
8. iOS 资产首页无 Android 的余额隐藏/显示控制；Android 的总资产头卡与 iOS 的逐币种大卡也不同。
9. 商家等级升级、查看我的店铺、关店等入口位置需要统一。关店功能已有，在 iOS 店铺资料内，不能误报为功能完全缺失。

## 待设备核验，不能凭源码保证通过

- 进入私聊/群聊首屏耗时、历史定位、聚焦键盘前后位置、长列表掉帧和重复进出。
- 图片/视频/文件/语音：选择、预览、发送进度、成功、失败重试、缓存再次打开；真实大视频及录制样本。
- iOS 与 Android 两台实际设备：双方在线、被叫离线再上线、超时、取消、拒绝、接听、最小化、网络切换、蓝牙与前后台。现有 Windows 环境无法执行 Xcode 或 iPhone 实测。
- 红包发送/领取/到账、订单/发货/售后属于真实业务操作，本次未执行；需要隔离测试账号和测试数据再验收。
- 所有底栏、二层返回、系统返回手势、弹层取消和未保存草稿路径。

## 建议实施顺序与完成标准

先统一设计组件和导航，再做聊天/媒体/通话/红包，随后做生态/商城/商家/资产，最后补齐个人/设置/支持与所有状态。每个模块用相同测试数据截取 Android 与 iOS 同页面对照，检查入口、图标、排版、点击区域、加载/失败状态和返回路径。编译通过、接口脚本通过、界面对齐和真实双机功能验收分别记录，不能相互替代。

## 关键源码依据

- ios-native/SpxChatIOS/Design/Components.swift:3-29、77-83
- ios-native/SpxChatIOS/Features/Main/AppNavigation.swift:85-102
- ios-native/SpxChatIOS/Features/Main/EcosystemView.swift
- ios-native/SpxChatIOS/Features/Profile/Account.swift:4-34、99-112
- ios-native/SpxChatIOS/Features/Chat/SocialViews.swift:43-50、63-84、96-112
- ios-native/SpxChatIOS/Features/Chat/ConversationSettings.swift:20-81、120-156
- ios-native/SpxChatIOS/Features/Chat/RedPackets.swift:21-40、77-103
- ios-native/SpxChatIOS/Features/Calls/CallOverlay.swift:9-72
- ios-native/SpxChatIOS/Features/Mall/Catalog.swift:19-75
- ios-native/SpxChatIOS/Features/Merchant/MerchantDashboard.swift:18-69
- ios-native/SpxChatIOS/Features/Merchant/MerchantOperations.swift:25-97
- ios-native/SpxChatIOS/Features/Merchant/ProductEditor.swift:207-269
- ios-native/SpxChatIOS/Features/Wallet/Assets.swift:9-36
- android-native/app/src/main/java/com/spx/chat/MainActivity.java:1286-1317、3024-3182、5514-5588、6678-6733
- android-native/app/src/main/java/com/spx/chat/MallUi.java:19-66
- android-native/app/src/main/java/com/spx/chat/MallMerchantForm.java:36-58
- android-native/app/src/main/java/com/spx/chat/ChatRedPacket.java:79-112

## iOS View 源码目录盘点

以下为 View 声明，包含独立页面与子组件，不用作独立页面数量统计。

- ios-native/SpxChatIOS/Design/Components.swift：SPXButton、Avatar、ErrorBanner、EmptyState、InfoRow
- ios-native/SpxChatIOS/Features/Auth/AuthenticationView.swift：AuthView、PasswordResetView
- ios-native/SpxChatIOS/Features/Calls/CallOverlay.swift：CallOverlay
- ios-native/SpxChatIOS/Features/Chat/ChatAccessories.swift：EmojiPanel、MemberMentionPicker、MessageCardContent
- ios-native/SpxChatIOS/Features/Chat/Conversations.swift：ChatListView、ChatDetailView、ConversationView、MessageBubble、PendingMessageBubble、PlusAction
- ios-native/SpxChatIOS/Features/Chat/ConversationSettings.swift：ConversationSettingsView、GroupMembersView、MessageSearchView、AutoReplyRulesView
- ios-native/SpxChatIOS/Features/Chat/FileAttachmentView.swift：FileAttachmentView
- ios-native/SpxChatIOS/Features/Chat/MessageHistory.swift：MessageText、MessageContextView
- ios-native/SpxChatIOS/Features/Chat/NativeMediaViews.swift：LocalAttachmentPreview、CachedImage、MediaPreview、VideoMessagePreview、MediaPage
- ios-native/SpxChatIOS/Features/Chat/QRCodeViews.swift：QRCodeView、ScanCodeView
- ios-native/SpxChatIOS/Features/Chat/RedPacketHistory.swift：RedPacketHistoryView
- ios-native/SpxChatIOS/Features/Chat/RedPackets.swift：RedPacketSendView、RedPacketPopup
- ios-native/SpxChatIOS/Features/Chat/SocialViews.swift：ContactsView、AddContactView、UserProfileView、GroupCreateView、GroupJoinView、ForwardMessageView、ScheduledMessageView、SendLocationView
- ios-native/SpxChatIOS/Features/Chat/VoicePlayback.swift：VoiceMessageLabel
- ios-native/SpxChatIOS/Features/Main/AppNavigation.swift：RootView、SessionRecoveryView、LinkedUserView、MainTabView
- ios-native/SpxChatIOS/Features/Main/EcosystemView.swift：EcosystemView
- ios-native/SpxChatIOS/Features/Main/OverlayPresenter.swift：OverlayContents
- ios-native/SpxChatIOS/Features/Mall/Addresses.swift：AddressView、AddressEditor
- ios-native/SpxChatIOS/Features/Mall/Catalog.swift：MallHomeView、ProductCard、PriceLabel、ProductDetailView、ProductVideo、ShopsView、ShopDetailView、ProductReviewsView
- ios-native/SpxChatIOS/Features/Mall/Orders.swift：CheckoutView、OrdersView、OrderSummary、OrderDetailView、AfterSaleView、ReturnShipmentView、ReviewEditor
- ios-native/SpxChatIOS/Features/Mall/ShopNavigation.swift：ShopNavigationButton
- ios-native/SpxChatIOS/Features/Merchant/MerchantDashboard.swift：MerchantOverviewView、Metric、MerchantProductsView、MerchantDraftsView、DraftLoader
- ios-native/SpxChatIOS/Features/Merchant/MerchantOperations.swift：MerchantProfileEditor、QualificationImage、InventoryEditor、MerchantOrdersView、MerchantFulfillmentView
- ios-native/SpxChatIOS/Features/Merchant/ProductEditor.swift：PublishProductView
- ios-native/SpxChatIOS/Features/Merchant/ShopLocationPicker.swift：ShopLocationPicker
- ios-native/SpxChatIOS/Features/Profile/Account.swift：ProfileView、ProfileEditView、SecurityView、ChangePasswordView、SettingsView、PolicyView、BenefitsView、UpdateHistoryView、FavoritesView、RecordsView
- ios-native/SpxChatIOS/Features/Profile/Support.swift：SupportView、SupportEditor、QuestionsView、ReferralsView
- ios-native/SpxChatIOS/Features/Wallet/Assets.swift：WalletView、WalletBindingView、WalletTransferView、ExchangeView、WalletTransactionsView、StakingView
