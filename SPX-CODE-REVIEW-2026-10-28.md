# SPX 聊天 代码评审报告（只读，未修改任何代码）

> 后续：本报告中 P0/P1 的清理、资金与安全项已于 2026-10-28 修复并上线，详见 [SPX-DEPLOY-2026-10-28.md](SPX-DEPLOY-2026-10-28.md)。性能架构类改动仍按本文建议暂缓。

- 评审日期：2026-10-28
- 范围：PHP 后端（`spxchat/`、根目录入口）、Android 客户端（`android-native/`）、iOS 客户端（`ios-native/`）、部署与仓库结构
- 方式：静态阅读 + 模式统计（未做运行时 profiling，未连数据库，未构建），所有结论都标注了文件:行号
- 结论：**没有发现 SQL 注入、没有发现未转义的 XSS、金额没有浮点运算**；问题集中在「资金状态机边界」「全局锁与 O(N) 扇出」「上帝类与重复代码」「工程与资源体积」四类

---

## 一、代码规模画像

| 部分 | 文件数 | 非空行数 | 最大单文件 |
|---|---|---|---|
| PHP 后端（不含 tests / py） | 56 | 8,006 | `admin.php` 1,909 行 |
| Python 运维脚本 | 3 | ~1,165 | `recharge_verifier.py` 456 行 |
| Android Java | 41 | 14,228 | `MainActivity.java` 9,639 行（物理 10,095） |
| iOS Swift | 65 | 11,249 | `Conversations.swift` 1,038 行 |
| 合计 | 165 | ≈ 34,600 | — |

集中度（这是后面所有结构问题的根源）：

- PHP：`index.php`(1,776) + `admin.php`(1,909) + `mall.php`(415) ≈ **全部 PHP 的 51%**
- Android：`MainActivity` 占 Java 代码的 **68%**，458 个方法、171 个字段、0 个 Fragment/ViewModel/RecyclerView
- iOS：8 个文件 > 400 行，其中 `Conversations.swift` 单结构 53 个 `@State`

工作目录体积：**12.5 GB**（`.spx-release-build` 6.6 GB、`android-native` 2.5 GB、`output` 2.4 GB、根目录 25 个 APK 955 MB）。

---

## 二、P0 建议优先处理（正确性 / 资金 / 安全）

### 1. 提现重试可能重复打币

- 位置：`spxchat/withdrawal_verifier.py:314`，配合 `spxchat/spxchat-systemadmin/admin.php:662`
- 证据：`tx_hash = send_token(symbol, row["address"], row["amount"])`
- 问题：`claim_records()`(:170) 明确 `SELECT ... tx_hash`，但 `process_record()` 从不检查它，无条件重新签名发送。而后台「重试」只把状态从 `failed` 改回 `processing` 且**保留 tx_hash**。若上一轮已广播、随后 `wait_for_confirmations`(:319) 因 RPC 抖动抛错 → `mark_failed` → 运营点重试 → 下一轮再发一笔。
- 建议：`process_record` 开头判断 `row["tx_hash"]`，非空则只做确认轮询、禁止重发；发送前把 nonce/tx 草稿落库作为幂等键。

### 2. 充值记录会被 5 分钟窗口永久丢弃

- 位置：`spxchat/recharge_verifier.py:392`（常量见 :35-36）
- 证据：`"AND r.created_at > DATE_SUB(NOW(), INTERVAL %s MINUTE) "`
- 问题：只捞 5 分钟内创建的 `pending`，但链上交易时效允许 600 秒。RPC/进程中断超过 5 分钟后，该记录既不会被处理也不会被置 `failed`：`recharge_records` 和 `wallet_transactions` 永远停在 `pending`，用户资金静默挂账。
- 建议：去掉时间窗或放大到 ≥ 交易时效；对超时 `pending` 统一写 `failed` + 审计 + 告警。

### 3. 已广播的提现被标记为 failed（可能"出金又退款"）

- 位置：`spxchat/withdrawal_verifier.py:322-323`
- 证据：`except Exception as exception: mark_failed(...)`
- 问题：`wait_for_confirmations` 抛错时币可能已在链上；记 `failed` 后运营按失败处理会退款，形成重复出金。
- 建议：区分「未广播 / 广播失败 / 已广播待确认」三态，第三种只能用 `pending_confirm + tx_hash` 对账收敛。

### 4. 生产密钥硬编码在源码里

| 位置 | 内容 |
|---|---|
| `spxchat/config.php:10` | MySQL 账号明文 `spxchat / spxchatweijintian123321` |
| `spxchat/smtp.php:16` | QQ 邮箱授权码 `faghfjvajglbdcce`（可作为 SMTP 默认密码） |
| `spxchat/spxchat-systemadmin/login.php:22-23` | 内置超管 `ahtim / ahtim123321`，`admin_users` 为空时自动创建 |

- 补充事实：Python 侧（APNs / BSC 私钥 / Telegram token）**全部走环境变量**，说明团队知道该怎么做，只是 PHP 侧没跟。
- 建议：三处全部改环境变量 / `config.local.php`（不入库），删除自动建号分支改为一次性安装脚本 + 强制首登改密。`.gitignore` 现在只忽略签名相关 4 个文件。

### 5. 商家编辑商品会绕过平台下架与审核

- 位置：`spxchat/mall.php:242`
- 证据：`... status=1,review_status='approved',review_reason=NULL,version=version+1 ...`
- 问题：平台在后台把商品 `status=0` 下架后，商家提交任意一次普通编辑就会重新上架并标记为已通过。代码只在 `review_status==='rejected'` 时拦截(:191)，没有比对 `$old['status']`。
- 建议：更新时保留旧 `status` / `review_status`；重新上架做成独立动作并写审计。

### 6. 后台「人民币参考汇率」是假配置，保存后完全不生效

- 位置：`spxchat/mall_valuation.php:7-12`
- 证据：`return MALL_USD_CNY;`（常量 `6.7`）
- 问题：`admin.php:1180` 有 `settings.mall_rate.save` 会写 `app_settings.mall_usdt_cny`，后台回显也用 `mallUsdtCny()`（读常量），于是管理员看到永远是 6.7、以为改成功了，而 `mallCnyToSpx()` 仍按 6.7 定价。
- 建议：二选一——删掉后台该项，或让 `mallUsdtCny()` 真正读 `app_settings` 并纳入定价快照校验。

### 7. 管理后台「节点分流」表单没有后端 handler

- 位置：`spxchat/spxchat-systemadmin/admin.php:1882`
- 证据：`<?= adminHidden($csrf, 'settings.api_nodes.save', 'settings') ?>`
- 问题：权限表(:296)登记了 `settings.api_nodes.save`，表单也在提交，但 35 个 `$action ===` 分支里**没有这个 handler**，提交必然落到 `:1237` 抛「不支持的管理操作」；且 `$data['api_nodes_text']` 从未赋值，textarea 永远空白。而 `index.php:98` 确实会读这个设置。
- 建议：补 handler（读旧值 → 校验 → 写 `app_settings` → `adminAudit`）+ 加载回显，或直接删表单。

### 8. 限流器在 CDN / NAT 下会误伤全体用户

- 位置：`spxchat/index.php:42-58`、`:260`
- 证据：`$ip = trim((string) ($_SERVER['REMOTE_ADDR'] ?? ''));`、`enforceApiRateLimit('login')`（默认 5 次/小时）
- 问题：只认 `REMOTE_ADDR`（`publicBaseUrl()` 里却已经处理了 `X-Forwarded-*`）。生产在 nginx/CDN 后时所有用户共享一个 IP：**第 6 次登录请求（含成功登录）全站 429**。同时 `api_rate_limits` 没有任何清理逻辑，按 IP×scope×小时无限增长。
- 建议：可信代理白名单下取 `X-Forwarded-For` 首跳；成功登录不计入配额；给 `api_rate_limits` 加定期清理（`email_verification_codes` 同样无清理）。

### 9. 密码校验型接口没有限流

- 位置：`spxchat/index.php:1263`（`wallet.bind` 校验密码）、`:326`（`password.update` 校验当前密码）
- 问题：持有一个泄漏 token 的攻击者可以无限次爆破账号密码（`password_verify` 无配额），进而绑定自己的钱包地址（不可再改）。
- 建议：这两个动作加 `enforceApiRateLimit`，按 user_id 而非仅 IP。

### 10. 用户可被邮箱片段枚举

- 位置：`spxchat/index.php:454-456`
- 证据：`WHERE ... (u.spx_id LIKE ? OR u.email LIKE ? OR u.nickname LIKE ? ...)`
- 问题：任何登录用户可用 `%a%@qq.com` 之类的片段检索，返回 `id / spx_id / nickname / avatar / 是否在线`，等于一个 PII 枚举接口。
- 建议：邮箱只做**精确匹配**，模糊搜索限定 spx_id 前缀 + 昵称。

---

## 三、P1 性能瓶颈（按影响排序）

### 11. 一把全局命名锁把所有消息相关操作串行化

- 位置：`spxchat/chat_delivery.php:30`
- 证据：`SELECT GET_LOCK('spx_chat_delivery_v1',5)`
- 影响：`chatAtomic()` 有 **12 个调用点**，覆盖私聊发送、群发送、**已读回执**（`chat_sync.php:68`）、红包（`chat_red_packets.php:38`）、定时消息、**通话信令**（`chat_calls.php:135/153/158`，客户端还在高频轮询 `calls.session`）。全平台这些操作的并发度 = **1**，抢不到锁 5 秒后直接给用户 503。
- 建议：去掉全局锁，幂等已有 `uq_message_user_sender_client` 兜底；只按 `chat_id` / `group_id` 做行级串行（或 `GET_LOCK(CONCAT('spx_chat_', chat_id))`）。

### 12. 群消息扇出是 O(N)：每个成员一次 SQL + 一次 TCP 连接

- 位置：`spxchat/chat_delivery.php:105` → `spxchat/ws-event.php:29`
- 证据：`foreach (... members ...) $events[] = [...]`，随后 `foreach ($events as $event) notifyWebSocket($event);`（`chat_delivery.php:40`），而 `notifyWebSocket` 每次 `fsockopen` 新建连接，并且每次 `require config.php`（`require` 不是 `require_once`）、每次 `json_encode`、对群事件还要**为每个收件人单独查一次库**（`ws-event.php:22` → `chat_names.php:23`）。
- 核算（N=500 成员，推送关闭）：

| 项 | 数量 |
|---|---|
| SQL（事务内 12 + 每人 1 次 `chatViewerNames`） | ≈ 512 条 |
| `fsockopen` 连接（串行，超时 0.2s/次） | 500 次，最坏 100 s > FPM 默认 30 s |
| `require` + `json_encode` | 各 500 次 |

  推送开启时额外每事件 2 条查询 + 每设备 1 条 INSERT，约 1,500 条 SQL，且 `push_worker` 限速 100 条/分钟 → **一次 500 人群发让推送队列积压 5 分钟以上**。
- 建议：改用「一次连接批量写多行」+ ws-server 循环读（见下一条），或落本地队列由常驻进程分批投递；事件的个性化（备注）改为客户端本地覆盖，服务端不再逐人查库。

### 13. ws-server 是单进程 select 循环，且每 3 秒做一次全量重认证

- 位置：`spxchat/ws-server.php:135-138`、`:68-79`、`:94-106`
- 证据：`revalidateSockets()`（每 3 s）、`$line = fgets($eventSocket); fclose($eventSocket);`（一事件一连接）
- 影响：
  - 每 3 秒遍历**全部**在线连接各发一条 `authenticateSocket` SQL：5,000 连接 ≈ 1,667 QPS 纯用于重认证。
  - 事件口读完一行立刻 `fclose`，协议上无法批量承载事件 → 把上一条的"一事件一连接"钉死。
  - `accept` 后 `fgets` 受 `stream_set_timeout(1s)` 约束，写端慢就阻塞整个单进程循环 1 秒，期间所有客户端帧排队。
  - `$buffers[$id] .= $chunk` 无上限，且帧长 `$length` 无上限 → 单个恶意客户端可打爆内存。
- 建议：短期内改「轮转校验 + 事件批量写 + 缓冲上限」；中期换 Swoole/Workerman 或直接用第三方推送。

### 14. 每个 API 请求都有一笔固定开销

| 位置 | 开销 |
|---|---|
| `spxchat/db.php:54` | 每请求对 8 个 PHP 文件做 `hash_file('sha256')`（≈116 KB 读盘）再查 `app_settings`，即使指纹已命中 |
| `spxchat/staking_settlement.php:13-17` | 每请求先抢 `GET_LOCK('spx_staking_dividends')`（3 次往返），才判断"距上次 < 60s 就跳过" |
| `spxchat/auth.php:34` | 每次 `currentUser()` 都执行 `INSERT IGNORE INTO wallet_assets`（**每个鉴权请求一次写事务**） |

- 结果：一个最简单的 `GET ?action=contacts` 在业务 SQL 之前已经付了 **6 条语句 + 3 次锁操作 + 116 KB 读盘**；且 `runStakingSettlement()`（`index.php:85`）在 90% 的 action 上都会跑。
- 建议：指纹改为「部署时生成一个常量文件」或 `filemtime` 求和；把锁判断挪到 60 s 节流判断之后；`wallet_assets` 初始化挪到注册/登录时一次性做。

### 15. 打开钱包页会触发最多 200 个事务，每个都抢全局锁

- 位置：`spxchat/index.php:1251` → `spxchat/chat_red_packets.php:34-40`
- 证据：`redPacketSweep(db(),200,(int)$user['id']);`，内部 `foreach ($ids as $id) chatAtomic($pdo, ...)`
- 影响：一次 GET 最坏 = 200 ×（抢全局锁 + 事务 + `FOR UPDATE` + 入账 + 2 条日志）。且 `chat-worker.php` 每 10 秒也在做同样的事（`redPacketSweep(db(),200)`），请求路径上是重复劳动。
- 建议：请求路径上只做「读」，过期退款交给 worker；worker 端用批量 `UPDATE ... WHERE status='active' AND expires_at<=NOW()` 一次收敛。

### 16. 商城目录接口每次都跑 30 天全表订单聚合 + 逐行 JSON 解析

- 位置：`spxchat/mall_workspace.php:93-103`（被 `mall.php:120`、`mall.php:295`、`mall_workspace.php:170` 复用）
- 证据：`SUM(status='pending' AND ... JSON_UNQUOTE(JSON_EXTRACT(product_snapshot,'$.delivery_days')) ...) ... GROUP BY merchant_id`，同一 SQL 里还 `LEFT JOIN (SELECT product_id,AVG(rating)... FROM mall_reviews GROUP BY product_id)`
- 影响：商品列表、店铺列表、商家绩效页每次请求都物化「全部商家 30 天订单」派生表 + 「全部评价」派生表，`product_snapshot` 是 LONGTEXT 且每行解析两次 JSON；`merchant.performance` 只查一个商家也要先聚合全部。
- 建议：质量分物化成 `merchant_quality` 表（按 `merchant_id` 主键，定时/事件刷新），目录查询只 JOIN 单行；`delivery_days` 冗余成 `orders` 普通列，彻底去掉 `JSON_EXTRACT`。

### 17. 商品列表 `SELECT p.*` 把 LONGTEXT 全量读出来

- 位置：`spxchat/mall.php:101`
- 证据：`SELECT p.*,m.name AS merchant_name,m.level AS merchant_level FROM products p ...`
- 影响：`products` 单字段上限 `detail_text` 15,000、`parameters` / `service_terms` 各 5,000、`detail_images` 20×500、`variants` 最多 100 条 —— 一页 60 条理论上可读到 MB 级数据，而列表只需要 8 个字段；`p.*` 还让覆盖索引失效。
- 建议：列表查询显式列白名单，长字段只在详情接口取。

### 18. 缺失的索引（直接造成全表扫描 + `FOR UPDATE` 锁全表）

| 表 | 现状 | 缺什么 | 受影响查询 |
|---|---|---|---|
| `wallet_transactions` | 仅 `INDEX(user_id,id)` + `tx_hash` 唯一键 | `(type,status,id)`、`(created_at)` | 后台仪表盘计数、提现任务抢占（`withdrawal_verifier.py:181` 会全表 `FOR UPDATE`）、流水列表 |
| `push_queue` | 仅 `INDEX(available_at)` | `(expires_at)`、`(attempts)` | `push_worker.php:60` 每分钟全表删 |
| `chat_group_messages.mention_user_ids` | 无索引 TEXT | 建议独立 `chat_group_mentions` 表 | `index.php:554` 每群一次 `JSON_CONTAINS` |
| `chat_call_presence` | `PRIMARY(user_id,device_id)` | 够用 | — |

### 19. 典型 N+1

| 位置 | 问题 |
|---|---|
| `spxchat/index.php:551-558` | 会话/群列表：每群 3 个相关子查询取最后一条消息 + 每群 1 次 `JSON_CONTAINS` 查询查未读 @ |
| `spxchat/chat_red_packets.php:142` | 红包列表：每行 3-4 条查询（`redPacketView`），一页 60 条 ≈ 240 条 SQL |
| `spxchat/mall.php:132-138` | 商家商品/收藏页：每行补查一次 `product_images`（`mallProducts()` 已有批量取图逻辑却没复用） |
| `spxchat/index.php:446` / `:510` / `:313` | 联系人/会话/资料：每行一个相关子查询判断在线状态（4 处重复同一段 SQL） |
| `spxchat/staking_settlement.php:19-40` | 结算：全部 active 记录 `FOR UPDATE` 后逐条按周期查询再逐期写入 |

### 20. 分块上传每收到一块都要 stat 全部已收块

- 位置：`spxchat/chat_upload.php:150-154`（同样的 O(total) 扫描还有 `:114-123`、`:226-233`）
- 证据：`for ($i = 0; $i < $total; $i++) { ... is_file($part) ... filesize($part) ... }`
- 影响：1 GB 文件 = 1,024 次请求 × ~1,024 次 `stat` ≈ 52 万次系统调用；账号配额扫描还要 `glob` 全部 `.part`。
- 建议：`meta.json` 增量记录 `received / bytes / quota`，每块 O(1) 更新，装配时再全量校验一次。

### 21. 大量列表接口没有分页

| 位置 | 说明 |
|---|---|
| `spxchat/index.php:1217-1221` | `orders` 无 LIMIT，一个用户的历史订单全量返回 |
| `spxchat/index.php:1379-1389` | `wallet.transactions` 默认 100 条但无游标，翻页用 `OFFSET` |
| `spxchat/index.php:1585`、`:1605`、`:1581` | `questions` / `support.tickets` / `faq` 无 LIMIT |
| `spxchat/spxchat-systemadmin/mall_admin.php:101-103` | 商家页硬编码 `LIMIT 100` 且无分页，第 101 条在后台永远看不到 |

### 22. 深分页 + 表达式排序

- 位置：`spxchat/mall.php:119-120`
- 证据：`ORDER BY (p.sort_order-<质量分表达式>) ASC,p.id DESC LIMIT 60 OFFSET <最多 59940>`
- 影响：排序键是表达式 → 必然 filesort 全量匹配行；且两处 `page` 上限不一致（`mall.php` 1,000 vs `mall_commerce.php:72` 100,000）。
- 建议：质量分物化成列后建索引 + 键集分页，统一 `mallPage()` 上限。

### 23. 后台首屏 13 次串行查询 + `LIKE '%kw%'` 搜索

- 位置：`spxchat/spxchat-systemadmin/admin.php:1286-1309`（仪表盘）、`admin_queries.php:17/31`
- 证据：`'待审核提现' => (int) $pdo->query("SELECT COUNT(*) FROM wallet_transactions WHERE type='withdraw' AND status='pending'")`
- 影响：10 个统计各发一条 SQL（其中 2 条对 `wallet_transactions` 全表聚合）；列表页固定 `COUNT(*) + 数据` 两条 SQL，18 个调用点；搜索用 `LIKE '%kw%'` 且 `CAST(t.user_id AS CHAR) LIKE`。
- 建议：仪表盘合并成 1-2 条条件聚合；`wallet_transactions` 加索引（见 18）；关键词改前缀匹配。

### 24. Android：i18n 在渲染热路径上 O(n) + 反射

- 位置：`android-native/app/src/main/java/com/spx/chat/I18n.java:152`、`MainActivity.java:10048`
- 证据：`for (Map.Entry<String, String> entry : KEYS.entrySet()) {`
- 影响：`I18n.t()` 每次线性扫 412 条映射（`MainActivity` 里有 **1,007** 处 `t()`）；`bodyText/centeredText`（303 处）对所有消息正文调 `tr()`，动态文本必然 miss 精确表 → 再扫 412 条 `contains`，每个命中还要 `new Configuration` + `createConfigurationContext` + `getIdentifier`（反射）。会话列表滚动时这是主线程最大开销。
- 建议：反向 `HashMap`（O(1)）+ 缓存「语言→Resources」+ `getIdentifier` 结果缓存。

### 25. Android：消息流用 ScrollView + LinearLayout 全量 View

- 位置：`MainActivity.java:4266`、`:4477`
- 证据：`for (int i = 0; i < targetList.getChildCount(); i++) {`（插入前扫一遍定位，`:4272` 再扫一遍找锚点）
- 影响：每条消息插入 2 次 O(n)，窗口上限 300 条 → 批量加载 50-200 条即 O(n²)；所有气泡常驻内存，时间标签刷新再全量遍历，滚动掉帧。
- 建议：换 `RecyclerView` + `DiffUtil`（已有 `renderedMessageIds` 做 key 去重，迁移成本可控）。

### 26. Android：主线程反复全量解析会话快照

- 位置：`MainActivity.java:3894`
- 证据：`try { return new JSONArray(preferences.getString(chatSnapshotKey(), "[]")); }`
- 影响：`readChatSnapshot()` 有 **35 个调用点**（通知、角标、切 tab、WS 事件），每次都从 SharedPreferences 读整串并重新解析；写入还要 `toString()` 后整串比较。
- 建议：内存持有解析结果，写盘才序列化；读取改懒加载 + revision 校验。

### 27. Android：WebSocket 已就绪仍每 15 秒轮询，断线 3 秒一次

- 位置：`MainActivity.java:3576`
- 证据：`chatRefreshHandler.postDelayed(chatHistoryRefresh, realtimeReady ? 15000L : 3000L);`
- 影响：WS 已负责推送，这里仍每 15 s 调 `loadChatHistory` + 逐条 `reconcileVisibleMessages`；断线时 3 s 一次；每次 `onResume` 还整页重载。
- 建议：`realtimeReady` 时拉到 2-5 分钟或改为心跳超时才触发；断线轮询用指数退避。

### 28. Android：通话页每秒主线程 JNI

- 位置：`android-native/app/src/main/java/com/spx/chat/ChatCall.java:227`
- 证据：`handler.postDelayed(this, 1000);`
- 影响：`pulse` 每秒执行，内含 `sdkAuthenticated()` → `TUIRoomEngine.getSelfInfo()`（JNI）、`status.setText(...)`、可能触发 `initialize()`。
- 建议：pulse 只做本地计时；SDK 鉴权降到 10-15 s；`setText` 只在文本变化时调。

### 29. iOS：会话页 3 秒一次 REST 同步，与 WebSocket 双通道

- 位置：`ios-native/SpxChatIOS/Features/Chat/Conversations.swift:254`
- 证据：`do { try await SPXSleep.seconds(3) } catch { return }`
- 影响：打开一个会话每 3 秒请求 messages（每 15 秒再 `reconcile` 最多 200 个 id），而 `.spxRealtime` 已在合并推送；叠加列表 20 s、通知 20 s、presence 25 s，前台单会话约 20 请求/分钟。
- 建议：改事件驱动，轮询退化为 15-30 秒兜底或仅在 WS 断开时启用。

### 30. iOS：内存消息数组无上限

- 位置：`Conversations.swift:279`（磁盘裁剪到 1,000 条，内存不裁剪）；`ConversationStore.cached` 常驻最多 12 个 store
- 建议：内存也设上限（如 2,000 条 + `hasOlder` 标志），按条数/内存压力淘汰。

---

## 四、P2 结构与重复（拆分收益最大）

### 31. `index.php`：1,776 行单 switch + 7 个分发器，其中有 4 个 case 是死代码

- 结构：`pushApi` → `tencentCallApi` → `mallHandleApi` → `mallWorkspaceApi` → `chatApi` → `chatSyncApi` → `redPacketApi` 依次调用（`index.php:78-90`），最后落到 92 个 `case` 的 switch。
- **死代码**（已核实不可达）：`chatSyncApi` 在 `index.php:89` 无条件先处理 `messages` / `groups.messages` / `*.read` 并 `jsonResponse` 退出，所以 `index.php:833`（`groups.messages`）、`:855`（`groups.messages.read`）、`:1074`（`messages.read`）、`:1193`（`messages`）这 4 个分支永远不会执行 —— 约 90 行与 `chat_sync.php` 逻辑重复且已经漂移（例如旧分支不更新 `last_read_message_id`）。
- 建议：阶段一删死代码 + 建立一张「action → handler」映射表（可枚举 API 面）；阶段二按域拆文件（chat / mall / wallet / social），`index.php` 只留 bootstrap + dispatch。

### 32. `admin.php`：1,909 行 / 209 KB 单文件

- 内容：35 个 POST handler（`:283-1246`，71 KB）+ 25 个 section 路由（`:1542-1891`）+ 8.4 KB 内联 CSS + 4 段内联 JS + 44 个表格面板；150 处 SQL、501 处 `<?=`。
- 重复：内联 CSS 的 115 个选择器里有 **58 个**在 `admin.css` / `ui-premium.css` 里重复定义；会话引导 + 4 个安全头 14 行 × 3 个文件逐字复制（`logout.php` 已少 3 个头）；分页 HTML 2 份；质押列表 SQL（1.6 KB，含 2 个相关子查询）在 `admin.php:1342` 与 `admin_export.php:19` 各一份。
- 建议：拆 `handlers/` + `views/`；先抽 CSRF / 登录态 / 审计 / 分页 4 个横切层（能立刻消掉 3 份复制）。

### 33. 商城模块内部有两套实现，售后分支已是死代码

- 位置：`spxchat/mall.php:361-373`
- 问题：`mallHandleApi()` 第一行就调 `mallCommerceApi()`（`mall.php:274`），而 `orders.after_sale` 在 `mall_commerce.php:141` 的写白名单里并已 `jsonResponse` 退出 → `mall.php` 里那份售后分支不可达。两份写入字段不同（commerce 版写 `after_sale_type/images/requested_at`，旧版只写 status/reason），而质量分依赖 `after_sale_requested_at`。
- 建议：删旧分支，并把写白名单里多余的 `orders.after_sale` 去掉。

### 34. Schema 有三个来源，其中一个已经过期

| 来源 | 覆盖 | 状态 |
|---|---|---|
| `spxchat/db.php:73-113` 内联 DDL | 60 张表 | 运行时真实来源 |
| `spxchat/mall_schema.sql` | 3 张商户表 | 被 `mall_schema.php:12` 读取 |
| `spxchat/schema.sql` | 43 张表 | **代码里 0 引用，但 `README.md` 仍写着「也可手动执行 schema.sql」** |

- 具体危害：`schema.sql:228` 的 `products.name ... UNIQUE` 正是 `mall_schema.php:52-56` 在运行时**主动删除**的旧约束；它缺 20 张表（call / push / mall 系列）。任何照 README 操作的人都会得到一个语义不同的库。
- 附加问题：`mall_schema.php:13` 用 `explode(';', $sql)` 逐条执行，任何 DDL 默认值里出现 `;` 就会截断语句；同一列在多个版本迁移里重复定义（`orders.after_sale_requested_at`、`products.cny_price`）。
- 建议：删除 `schema.sql` 或改成自动生成的快照；README 改为指向唯一入口；`mall_schema.sql` 后续并入带版本号的 migration。

### 35. 金额与业务常量分散硬编码

| 常量 | 位置 |
|---|---|
| 保证金 100,000 / 1,000,000 | `mall.php:8-9`（定义）+ `mall.php:106` + `mall.php:290` + `merchant.js:15`（各写一遍字面量） |
| 结算费率 0.1000 | `mall_commerce.php:204`（同一句里两次）+ `mall.php:287` |
| 人民币汇率 6.7 | `mall_valuation.php:4`，被 6 处引用 |
| 后台 4 位小数舍入 | `admin.php:42-66` 手写 25 行字符串进位（项目已有 bcmath，`db.php` 到处用 `bccomp/bcdiv`） |

- 建议：`mallRequiredDeposit()` 结果用参数绑定进 WHERE（不要写字面量）；费率/汇率的唯一真源放 `app_settings` 或常量并单点引用；`adminAmount4()` 直接用 `bcadd`/`round` 语义化实现。

### 36. `server-companion/mall.php` 是一份落后 5 个修复的旧快照

- 与 `spxchat/mall.php` 逐行比对（415 vs 414 行）后确认缺：软删除过滤、上架名额校验（`mallRequireProductSlot`）、`merchant.product.delete`、`p.deleted_at` 过滤、`settlement_fee_rate` 返回。其所在目录还缺它 `require` 的 `mall_commerce.php` / `mall_schema.php`，**无法独立运行**。
- 建议：删除或明确标注为「历史补丁包，勿部署」，避免下次有人把它当线上版本改。

### 37. Android：两套 i18n 并存

- `res/values(-en)/strings.xml` 各 **821 条**（双语完备、无缺失 key），但 `I18n.java` 里另有 **412 条**中文映射；新功能（商城/通话/红包/媒体）几乎全用中文字面量：`MallCommerce.java` 397 个、`ChatRedPacket` 93、`ChatExperience` 85、`ChatCall` 54。
- 具体缺陷：`ui_draft_prefix` / `ui_send_failed_prefix` / `ui_sending_prefix` / `wallet_balance_hidden` / `wallet_balance_visible` 5 个 key 在 `strings.xml` 里不存在 → 非中文界面回退中文；`select_variant_first` / `insufficient_stock` / `select_address_first` 3 个 key 重复 map 两次；`strings.xml` 里有 31 组同值不同 key。
- 建议：确立单一 i18n 入口；加一个「I18n key ⊆ strings.xml key」的单测；分模块把中文字面量抽 key（可脚本抽取）。

### 38. Android：`dp()` 等 UI 工具在 10 个类各写一遍

- 位置：`MainActivity.java:10087` 等（`CallMiniWindow`、`ChatCall`、`ChatExperience`、`ChatMediaViewer`、`ChatRedPacket`、`GroupUi`、`MallCommerce`、`MallMerchantForm`、`MallUi`）；`dp()` 全包 1,812 次调用。已有 `MallUi` 却只有一半商城代码在用。
- 建议：收进 `MallUi`/`Ui`，同步统一间距常量。

### 39. iOS：`Conversations.swift` 1,038 行 / 视图链 9 层

- 位置：`ios-native/SpxChatIOS/Features/Chat/Conversations.swift:738`
- 内容：12 个类型、`ConversationView` 单结构 53 个 `@State`、`body` 经 8 个 `...Content` 包装（761→738→721→706→695→668→644→634→482），同文件直接调 `APIClient` 8 次、`LocalStore` 14 次。
- 建议：拆 `TimelineView` / `ComposerBar` / `MediaMessages` / `SheetHost`，网络与持久化收进已有的 `ConversationStore`。

### 40. iOS：分页脚手架复制 11 份，现成的 `PageState` 只用了 5 处

- 位置：`Orders.swift:126-137` 与 `MerchantOperations.swift:183-194` 除 endpoint 外逐行相同；`private var page = 1 / more = true` 出现 11 次。
- 建议：全部改用 `Core/PageState.swift`。

### 41. iOS：金额格式化三套实现，舍入规则已分叉

- `StakingPresentation.amount`（`.down` + 固定 2 位）、`DisplayLabels.amount`（`.halfUp` + 可变位）、`Money.format`（`NSDecimalRound(.plain)`）→ 同一余额在不同页可能显示不同值。
- 建议：只保留一套。资金幂等状态机（`Assets.swift:184`、`:593`、`ProductEditor.swift:493`）同样复制了三份，建议抽 `FinancialRecovery`。

### 42. iOS：131 处把底层错误直接抛给用户

- 位置示例：`Orders.swift:136` —— `self.error = error.localizedDescription`（全仓 157 处 `localizedDescription`）
- 影响：用户会看到英文 `The request timed out.`；`NetworkSession.swift` 里写好的分类映射只被认证流程调用。另有 **14 处空 `catch {}`**（如 `NativeMediaViews.swift:53`），本地附件预览失败后永久停在转圈。
- 建议：`APIClient` 层统一转 `UserFacingError`，视图只显示 `userMessage`；空 catch 区分 `CancellationError` 与真实失败。

### 42b. Android 其他可优化点（均为中/低，性价比排序）

| 位置 | 问题 | 建议 |
|---|---|---|
| `MainActivity.java:2145` 等 57 处 `ApiClient.get` | `RequestHandle` 在 MainActivity 里出现 **0 次**，切 tab / 换搜索词 / 退出聊天后旧请求仍跑完并回调，只能靠 `epoch/generation` 掩码兜底 | 参考 `MallCommerce.pageReads`，每页持有句柄集合，切换前 `cancel()` |
| `MainActivity.java:554` `onDestroy` | 只清了 splash/chatRefresh/draft/outbox 4 个 Handler，`presenceHandler`(30 s)、`updateCheckHandler`(60 s)、`incomingBannerHandler`、`readReceiptHandler` 的排队消息仍持有 Activity | 统一 `removeCallbacksAndMessages(null)`；循环 Runnable 判断改 `isFinishing()||isDestroyed()` |
| `ChatMediaViewer.java:121` | `cancelTransfer` 为每个连接 `new Thread(...)`；快速滑动会连续建线程 | 共享单线程 executor，或直接 `connection.disconnect()` |
| `ChatCache.java:138-154` | 改联系人备注时在事务里读全表群消息并逐条 `db.update`，改一次备注 O(全部群消息) | 按 `sender_id` 建索引后定向 update，或只更新内存缓存 |
| `MainActivity.java:10069` | `LocalizedTextView` 只调 `super.setText`，注释却声称会本地化 —— 误导性死代码，异步文案（状态、倒计时）实际不翻译 | 要么在 `setText` 里翻译（注意二次翻译），要么删掉改用普通 `TextView` |
| 全包 150 处 `catch (... ignored)` / 87 处完全空 catch | 头像、图片、缓存写入失败全静默（`loadAvatarInto` 整段吞），线上只能看到"图片不显示"，无法区分网络/证书/磁盘 | 统一 `Logs.w(tag, e)`，仅在确属取消时保留空 catch 并注明原因 |
| `com/spx/chat` 下 41 个类、**0 个子包** | 全部平铺，`MainActivity` 与 40 个类同级 | 拆分 `ui/`、`chat/`、`mall/`、`net/`、`data/` 子包，随第 2 批重构一起做 |

---

## 五、P3 工程 / 资源 / 仓库

### 43. 目录里堆了 12.5 GB 构建产物

| 目录 | 体积 |
|---|---|
| `.spx-release-build` | 6.6 GB |
| `android-native`（含 `.gradle`、`build`） | 2.5 GB |
| `output` | 2.4 GB |
| 根目录 25 个 `SPX-IM-*.apk` | 955 MB（其中 1.69–1.75 六个各 130 MB） |

- 建议：APK 与构建输出移出工作目录（或 `.gitignore` + 归档到对象存储），保留 `RELEASE-NOTES` / `SHA256` 即可。

### 44. 残留与临时文件

- 空目录：`js/`、`css/`、`img/`（0 文件）
- HBuilder 模板残留：根 `index.html`（`plusready` 空回调，实际入口是 `index.php`）、`manifest.json`（HBuilder 5+ 清单，原生 App 不用）
- 临时产物：`$null`（61 B，明显是 `> $null` 打错的产物）、`debug.log`、`1.py`、`inspect_device.py`、`method_dump*.txt`、`methods_lines.txt`、`.tmp-swift-parser/`
- 建议：一次性清理（`js/css/img` 与 `index.html` 确认无引用后删除）。

### 45. Android release 构建没开压缩

- 位置：`android-native/app/build.gradle:45`
- 证据：`minifyEnabled false`（且无 `shrinkResources`、无 `proguardFiles`、`lint { checkReleaseBuilds false }`）
- 影响：821×2 条字符串、7.6 MB 图片、未使用的腾讯 UIKit 类全部进包；当前 APK 130 MB。
- 建议：`minifyEnabled true` + `shrinkResources true` + keep 规则（zxing / 腾讯 SDK）+ `resConfigs "zh","en"`。

### 46. 同一张 2048² PNG 在两端各占 5 MB，却当卡片小图标用

| 位置 | 尺寸 | 体积 | 用途 |
|---|---|---|---|
| `android-native/.../drawable-nodpi/reward_video.png` | 2048×2048 | 5,245,484 B | `MainActivity.java:3108` 一张生态卡片图标 |
| `ios-native/.../Resources/reward_video.png` | 2048×2048 | 5,245,484 B | `EcosystemView.swift:32` 同一图标 |
| `drawable-nodpi/spx_im_splash.jpg` | 1520×2720 | 2,576,392 B | 启动图 |

- 影响：解码后 ARGB 约 16 MB 位图；两张图占 Android 图片资源的 95%。
- 建议：图标改 96×96 WebP（几 KB）；启动图压到 1080×1920 WebP（<200 KB）；iOS 出 1x/2x/3x 或 HEIC。

### 47. 部署依赖文档化在生产环境上不成立的两处

- `spxchat/.htaccess`（`Options -Indexes` + 拒绝 `config/db/auth/response/smtp/schema/ws-server/ws-event`）在 **nginx 下完全不生效** —— 而 `server-companion/deploy/UPLOAD-LIMIT-500MB.md` 明确记录生产是 nginx + php-fpm 8.3。
- 管理后台的 HTTPS 判定只看 `$_SERVER['HTTPS']` / 端口（`admin.php:4`、`login.php:4`、`logout.php:4`），全仓 0 处读取 `X-Forwarded-Proto` → 反向代理终止 TLS 后会话 Cookie 会丢掉 `Secure`。
- 建议：把 `.htaccess` 的规则等价写进 nginx `location` 并在部署文档里固化；PHP 侧加可信代理判断。

### 48. PHP 侧没有自动化测试与 CI

- `spxchat/tests/` 有 11 个真实场景脚本（群聊、红包、通话、商城、会话吊销、iOS 分页…，合计约 1,600 行），但都是「连真实 MySQL + 手装 PHP」的集成脚本，`codemagic.yaml` 只跑 iOS。
- 建议：至少把「schema 迁移 + 关键资金路径」放进一个可在 CI 跑的容器化集成测试；PHP 侧加 `composer.json` + `php -l` 全量 lint（成本极低）。

---

## 六、已做得好、不要动的地方（避免重构时破坏）

- **SQL 注入：无。** 逐条核对后台 6 处拼接 SQL（`admin_queries.php:31/32`、`admin.php:1032/1035/1313/1486`）与请求路径 10 处插值，全部是固定片段、生成的 `?` 列表或 `(int)` 强转的 LIMIT。
- **XSS：后台 0 处。** 504 个 `<?=` 全部经 `adminEscape`、`(int)` 强转或字面量。
- **金额计算：无浮点。** 全程 `DECIMAL(36,18)` + `bccomp/bcdiv/bcsub`；商城 6 处 `(float)` 只用于经纬度。
- **幂等：设计正确。** `financial_requests` 的 `(user_id,action,request_id)` 主键 + `payload_hash` 比对 + `FOR UPDATE`，回放返回同一结果；消息用 `uq_message_user_sender_client` 去重。
- **CSRF / 会话：** 后台含 logout 在内全覆盖，登录 `session_regenerate_id(true)`。
- **上传：** MIME 白名单 + libmagic + 随机文件名 + UUID 正则 + 分块大小/哈希校验，未发现路径穿越。
- **Android：** `ApiClient` 请求/上传/下载分离线程池；`ChatOutbox` 离线队列；WebSocket 1s→30s 指数退避 + 心跳；图片解码有 `inJustDecodeBounds` + `inSampleSize`；无 static Context、无 WakeLock；SQLite 不在主线程。
- **iOS：** `APIClient` 是 actor（解码不在主线程）；`ImageThumbnailCache` 用 ImageIO 降采样 + 磁盘缓存；无 `try!` / `as!`。
- **`app_download.php`：** 分块下载 / Range / ETag / HEAD 处理写得很完整，是仓库里质量最高的文件之一。

---

## 七、建议的动手顺序

**第 1 批（1-2 天，低风险、立刻见效，不改业务逻辑）**

1. 删除 `index.php` 4 个死分支、`mall.php` 售后死分支、`login.php` 空 `if`、`server-companion/` 旧快照
2. 补 4 个索引：`wallet_transactions(type,status,id)`、`wallet_transactions(created_at)`、`push_queue(expires_at)`、`push_queue(attempts)`
3. 密钥迁移出仓库（DB / SMTP / 超管）+ 关掉自动建号
4. 请求路径上做「减法」：`stmt` 前置节流、`wallet_assets` 初始化挪走、`redPacketSweep` 从 `wallet` 接口移除
5. `fetch` 列表加显式列白名单 + 分页（orders / transactions / tickets）

**第 2 批（1-2 周，结构性，收益最大）**

6. 去掉全局命名锁 → 按 `chat_id` 行级串行
7. 投递层批量化：`notifyWebSocket` 合并成一次连接多行 + ws 端循环读；事件去掉逐人查库
8. ws-server 的 3 秒全量重认证改轮转；缓冲/帧长加上限
9. 商城质量分物化成 `merchant_quality` 表；`delivery_days` 冗余成列
10. 拆分 `index.php`（action 映射表 → 按域拆文件）与 `admin.php`（handlers / views + 4 个横切层）

**第 3 批（按迭代推进）**

11. 私聊「双写两行 messages」收敛为单行 + 视图（大改，需双端配合）
12. `MainActivity` 按 `MallCommerce` 模式拆 4 个 controller + `RecyclerView` 消息列表
13. Android i18n 单入口化；iOS 拆 `Conversations.swift` + 统一分页/金额/错误层
14. 资源与构建：图片压缩、`minifyEnabled`、`resConfigs`、仓库瘦身
15. schema 单一来源 + PHP 侧 CI（lint + 资金路径集成测试）

---

## 八、说明

- 本报告为**静态评审**：未运行压测、未连数据库、未构建 App，因此「影响」一栏是基于代码路径的推算（规模假设 N=500 人的群、5,000 个 WS 连接），已在正文标明假设。
- 全程**未修改任何代码文件**，只新增了本报告。
- 每个条目的行号可在当前工作区直接跳转核对；如某处代码已更新导致行号偏移，以搜索关键词定位。
