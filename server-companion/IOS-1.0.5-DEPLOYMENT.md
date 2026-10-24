# iOS 1.0.5 离线来电登录修复配套部署

本目录 auth.php、chat_calls.php、call_session_policy.php 与工作区 spxchat 中同名文件一致，不含证书、密码或服务配置。

修复的问题：接收方登录时，旧逻辑把尚未绑定接收设备的待接来电也标记为 failed。现在仅保留该类新来电，其余换设备登录清理规则不变；接口参数、Android 请求契约和原通话超时均不改变。

部署时：

1. 备份并比较线上 auth.php、chat_calls.php；检查原有 chat_delivery.php 等依赖仍在。
2. 先安装新增 call_session_policy.php，再将 auth.php 与 chat_calls.php 作为同一批更新；不要用本目录覆盖整个后端。
3. 对三个文件执行 php -l，并按现有运维方式刷新 PHP-FPM / OPcache。
4. 用隔离测试账号验证：A 呼叫离线 B；B 在超时前登录后 A 会话仍为 ringing，B 可收到邀请；取消和已超时邀请不恢复；旧设备已认领通话在换设备登录时结束。

本地规则测试：php ios-native/scripts/test-call-login.php。规则测试不连接数据库，不证明生产 MySQL 锁或双设备音视频验收。

本修复已随 iOS 1.0.6 配套部署，以唯一锚点合并到线上版本。部署验证见 IOS-1.0.6-DEPLOYMENT.md；规则测试不代替真实双机通话验收。
