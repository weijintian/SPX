# SPX 商城 1.45 服务端更新记录

日期：2026-10-13（Asia/Shanghai）。用户授权更新线上商城文件，并明确要求 APK 暂不发布。

## 已完成

- 服务器：70.39.201.191；站点目录：/var/www/html/spxchat。
- 服务端覆盖更新包中的 10 个文件已安装，安装内容与原 SHA-256 清单一致。
- 商城数据库迁移版本 1、2 已安装；店铺、保证金记录、报价、订单事件和库存日志结构均已核验。
- 原有配置文件保持不变；uploads/mall 已建立并核验 PHP 服务用户具备写入权限；原上传目录脚本执行限制保留。
- 1.45 正式 APK 已加密上传至 root 私有目录，未登记到 app_releases、未设为活动版本、未向用户发布。
- 当前活动 APK 发布记录仍为 1.40（versionCode 41）。

## 备份及验证

- 发布文件目录：/root/spx-releases/mall-1.45-20261013T144936Z。
- 备份目录：/root/spx-backups/mall-1.45-20261013T144936Z。
- 原站点完整备份：site-before.tar.gz，103,812,902 字节；原数据库备份：database-before.sql.gz，36,835 字节。完整性摘要保存在服务器备份清单中。
- 使用独立临时数据库恢复备份演练迁移，原 41 张表的记录数量与原字段内容摘要全部一致；演练数据库、账号和临时站点已清理。
- 正式切换前另行保存数据库与覆盖文件快照。最终切换前后，48 张既有表的记录数量与原字段内容摘要全部一致，APK 发布记录保持不变。
- 生产 health、mall.config、products、shops、app.update.check 经 HTTP、HTTPS 共 10 次检查通过；后台未登录入口正常返回 303。
- Nginx 原配置恢复，PHP 服务已启动，维护限制已解除。
- 首次切换在服务恢复后的 HTTP 响应检查失败时已自动恢复原代码；新增结构保留。增加对重载后服务就绪的等待后，重新切换并完成全部检查。

## 保留事项

- APK 尚未发布；后续其他修改完成后再统一构建、验收和发布。
- 手机端新增 UI 和新版下单流程需要配套 1.45（46）或更高版本；旧版直接下单不兼容新版报价流程。
- 本次未进行真实账号的资金交易、商家缴保证金或真实下单验收，也未进行真机验收。
- 本地详细结果：.spx-release-build/spx-mall-upload-report.json、spx-mall-prepare.log、spx-mall-deploy.log、spx-mall-deployment-report.json。

正式 APK SHA-256：8b79a6be5b02255692c32d115cb4c24f6fd06424ea422ea008f7ce6b9c81a2ed。

服务端更新包 SHA-256：1eb94efcc876ce23a7ec81dc613bf1d5cc2852ddc545321aa0647345dce0235c。
