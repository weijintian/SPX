# iOS 1.0.4 商品发布恢复配套补丁

仅商品发布新增可选 client_request_id；旧 Android 不传此字段时保留现有接口契约。
financial_requests.php 和 mall.php 与工作区 spxchat 同名文件一致；不包含配置、账号、密钥或数据库内容。

部署前备份线上同名文件、检查差异及 financial_requests 表已存在。将两文件作为同一批更新，PHP 语法检查后再切换，按现有运维流程刷新 PHP-FPM/OPcache。不要用本目录覆盖整个后端。
index-product-recovery.patch 仅增加 merchant.product.save 的 409/422 异常映射；以差异方式合并，不覆盖 index.php。
mall.config 的 product_request_recovery=true 表示已支持发布结果核对；客户端在不支持时保留本地草稿并暂缓提交。

本地无真实数据库状态转换测试：php tests/product_request_recovery.php。
该测试不验证 MySQL 并发锁；上线前仍需在隔离数据库验证原提交与取消核对的并发顺序、响应丢失后返回同一商品 ID、事务回滚。
不得使用真实订单或真实余额做上述测试。

当前服务器部署尚未确认，不能把 GitHub 推送视为线上部署成功。
