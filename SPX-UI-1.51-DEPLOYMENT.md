# SPX 1.51 正式版发布记录

- 版本：1.51，版本代码 52，包名 com.spx.chat。
- 正式签名：原发布证书，未开启调试或 testOnly。
- 发布记录：14，发布时间（UTC）：2026-10-16T11:11:40.498794+00:00。
- 安装包：SPX-IM-1.51-release.apk，9656746 字节。
- SHA-256：7bfdfe4b72f502781bdd1014589875f9e47bbebb7330afaa9011604f94a26c28。
- 下载：https://spx001.top/spxchat/api.php?action=app.update.download&id=14

## 部署及检查

四个后端文件已部署：index.php、mall.php、mall_valuation.php、spxchat-systemadmin/admin.php。无数据库结构迁移。后端代码备份：/root/spx-backups/ui-1.51-20261016T105914Z；原 APK 和版本记录备份：/root/spx-backups/apk-1.51-20261016T110924Z。

正式编译、原证书签名、源文件与编译类完整性、对齐、应用链接配置检查通过。30 个 PHP 文件语法、商城客户端检查和 16 项汇率输入检查通过；A 方案此前已通过 49 项隔离模拟器界面断言。

线上商城接口、后台入口、真实 SPX001 行情函数、聊天服务及调度服务检查通过。旧版（版本代码 51）可检测到新版，当前版本（52）不重复提示更新。网站下载页使用最新版接口。

服务器经本地 HTTPS 和公网 DNS 域名两条路径下载正式安装包，证书和完整 SHA-256 校验均通过。本机外网 HTTPS 复核遇到 TLS 连接中断，故未把本机公网验证标记为通过；详见发布报告的 externalHttps 字段。

真实付款、退款、入群及管理员保存汇率未在生产环境执行测试操作。本轮没有自动安装到模拟器。
