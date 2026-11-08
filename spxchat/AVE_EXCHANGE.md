# SPX001 闪兑部署

闪兑 API 使用登录用户的 `wallet_assets` 账户，不接受客户端提交的 `user_id`。价格通过 Ave 查询，服务端共享 5 秒文件缓存；兑换在数据库事务中扣除 USDT、增加 SPX001，并写入 `exchange_records` 和两条 `wallet_transactions`。

## PHP 要求

- PHP cURL 扩展
- BCMath 扩展
- PHP-FPM 运行用户可写系统临时目录
- PHP-FPM 进程可访问 `https://prod.ave-api.com`

## 环境变量

在 PHP-FPM pool 环境中配置，不要将 API Key 写入 PHP 源码、Android APK 或仓库文件：

```ini
env[AVE_API_KEY] = 已轮换的 Ave API Key
env[SPX001_TOKEN_ID] = 0x0e920821464625cf661a4dcedeb6406e1b48ffff-bsc
env[EXCHANGE_FEE_RATE] = 0.01
```

修改后重启 PHP-FPM。API Key 已经出现在公开对话内容中，必须先到 Ave 控制台撤销并重置，再把新 Key 配到服务器环境变量。Ave 数据 API 使用 `X-API-KEY` 和 `Content-Type: application/json` 请求头；交易 API 的 `AVE-ACCESS-*` 认证头不用于此行情接口。

## 接口

- `GET /spxchat/api.php?action=wallet.exchange.quote`：需要 Bearer 登录令牌，返回 Ave 市价、含手续费兑换价和 1 USDT 可兑换数量。
- `POST /spxchat/api.php?action=wallet.exchange`：需要 Bearer 登录令牌，JSON 参数为 `usdt_amount` 和唯一 `request_id`。

兑换公式：`SPX001 数量 = USDT 数量 / (Ave 市价 * (1 + 手续费率))`。默认手续费率为 1%；展示牌价使用 30 秒共享缓存，确认闪兑时强制请求 Ave 最新价格，并再次校验余额。遇到 Ave 429 限流时不使用旧价格成交，提示用户稍后重试。

首次调用任一 API 时，`db.php` 会按现有项目的自动建表方式创建 `exchange_records` 表。
