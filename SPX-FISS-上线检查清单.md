# FISS 代币上线检查清单

> 生成时间：2026-11-01 · 对应版本：Android 1.81（versionCode 82）

## 一、上线前必须确认（人工决策）

| # | 事项 | 当前状态 | 谁来确认 |
|---|---|---|---|
| 1 | FISS 年化利率（现 60%） | 后台「质押方案」可改 | 运营 |
| 2 | 起押金额（现 100,000 FISS） | 后台可改 | 运营 |
| 3 | 总额度（现 100,000,000 FISS） | 后台「质押奖励额度」可改 | 运营 |
| 4 | 是否开放提现（现**关闭**） | 后台「设置 → FISS 提现开关」 | 财务 |
| 5 | FISS 转账是否有税 | **未验证**（热钱包无 FISS，需真实转账一次） | 技术 |
| 6 | 质押利息的资金来源 | 需明确（平台补贴 or 增发） | 运营/财务 |

## 二、部署清单（已完成的改动）

### 服务端文件

| 文件 | 改动 |
|---|---|
| `spxchat/db.php` | 多币种迁移（symbol 列）、FISS 方案与额度种子 |
| `spxchat/index.php` | 按币种质押、`staking.earnings`、钱包估值、FISS 提现开关 |
| `spxchat/AvePriceService.php` | 多币种行情（每币种独立缓存） |
| `spxchat/config.ave.php` | `tokens` 映射（SPX001 / FISS） |
| `spxchat/staking_settlement.php` | 结算按记录币种入账（修复曾发成 SPX001） |
| `spxchat/recharge_verifier.py` | FISS 合约配置 + 币种白名单（修复静默忽略） |
| `spxchat/withdrawal_verifier.py` | FISS 合约配置 |
| `spxchat/spxchat-systemadmin/admin.php` | 方案按币种、额度分币种、FISS 加减、提现开关 |
| `spxchat/oss_release.php` | 发布包自动同步 OSS（上一阶段） |

### 服务重启

```bash
systemctl reload php8.3-fpm
systemctl restart recharge-verifier
systemctl restart withdrawal-verifier
```

⚠️ **Python 服务改了代码必须重启**，systemd 不会自动重载。上线时踩过：文件已更新但进程还是旧的，导致 FISS 提现被判为「不支持的资产」。

部署后核对（确认进程内是最新代码）：

```bash
bash tools/check_service_freshness.sh
# 期望：全部 ✅ 已加载最新代码；出现「代码比进程新」就 restart 对应服务
```

### 数据库迁移

首次访问任意 API 时自动执行（`db.php` 幂等迁移），无需手工 SQL。

## 三、上线验证清单

逐项执行，全部通过才算上线完成。

### 1. 后端

```bash
# 方案与额度（需登录 token）
curl -s "https://<host>/spxchat/api.php?action=staking.plans&symbol=FISS" -H "Authorization: Bearer <token>"
# 期望：plans 含 FISS 90 天、phase.remaining_quota = 100000000

# 行情
curl -s "https://<host>/spxchat/api.php?action=wallet" -H "Authorization: Bearer <token>"
# 期望：FISS 资产带 price_usd 与 usd_value
```

### 2. 充值

| 步骤 | 验证点 |
|---|---|
| 用户向平台地址转入 ≥ 起押量的 FISS | — |
| 提交充值单 | `recharge_records.currency = FISS` |
| 等待验证器 1–2 轮（30s/轮） | 日志出现 `检查 #<id>: ... FISS` |
| 12 个确认后 | 状态 `completed`，FISS 余额增加 |
| 查 `recharge_audit_logs` | 有对应记录 |

⚠️ **重点**：确认到账金额是否等于转账金额。若 FISS 有转账税，到账会小于转入，需要业务确认按哪个口径入账。

### 3. 质押

| 步骤 | 验证点 |
|---|---|
| 切换 FISS → 选方案 → 输入 → 确认 | 弹窗显示周期/年化/到期日/预计收益 |
| 提交 | `staking_records.symbol = FISS`，状态 `active` |
| 钱包 | 可用余额减少、可质押余额减少 |
| 期数 | `reserved_quota` 增加（预分配收益） |
| 有推荐人时 | 推荐人收到 **FISS** 5% |

### 4. 结算

```bash
# 手动触发一次（注意会按当前时间补发）
php -r 'require "/var/www/html/spxchat/db.php"; require "/var/www/html/spxchat/staking_settlement.php"; runStakingSettlement();'
```

| 验证点 |
|---|
| `staking_dividends` 新增记录 |
| `wallet_transactions` 中 `symbol = FISS`（**不能是 SPX001**） |
| 到期后 `status = redeemed`，本金以 FISS 退回 |

### 5. 提现

| 开关状态 | 期望 |
|---|---|
| 关闭 | 接口返回 403「FISS 提现暂未开放」 |
| 开启 | 正常进入余额/地址校验 |

⚠️ 开启前必须确认**热钱包有足够的 FISS + BNB**，否则提现会失败并自动退款。

### 6. 客户端

| 端 | 验证点 |
|---|---|
| Android | 质押页 FISS 切换、余额卡、方案、确认弹窗、记录、收益明细、钱包卡片、充值/提现选币 |
| iOS | 同上（**尚未在 Mac 上编译验证**） |

## 四、已修复的严重问题（上线前记录）

开发 FISS 过程中发现并修复，均为会导致资金/账目错误的缺陷：

| # | 问题 | 后果 | 验证方式 |
|---|---|---|---|
| 1 | 结算脚本写死 `SPX001` | FISS 质押的收益和本金发成 SPX001，账目错乱 | 实测：修复前 SPX001 余额 +1643.83，修复后 FISS +1643.83 |
| 2 | 充值验证器币种白名单不含 FISS | FISS 充值记录永不处理，链上到账不入账 | 修复前 80 秒仍 pending；修复后日志出现「检查 #48 … FISS」 |
| 3 | 提现「准备阶段失败」只标记 failed 不退款 | 未广播就失败的提现，用户被扣款且拿不到币 | 用不支持币种触发：修复后余额退回 1010（本金 1000 + 手续费 10） |
| 4 | 提现验证器未重启 | 进程内无 FISS 配置，提现被判「不支持的资产」 | `check_service_freshness.sh` 核对启动时间 |
| 5 | 安卓/iOS 质押校验写死 100000 整数倍 | FISS 步长为 1，任意金额都被拒 | 端到端质押 100000 FISS 成功 |

## 五、已知风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| FISS 转账税未验证 | 若代币收税，到账金额会小于提交金额 | 首次真实充值后核对 |
| AVE 行情依赖 | FISS 未上大所，AVe 价格波动/缺失时钱包估值显示 `--` | 估值失败不影响余额与质押 |
| iOS 未编译验证 | Swift 改动未在真机/Mac 编译 | 上线前必须 Mac 构建一次 |
| 结算周期为 10 天 | 收益按每 10 天发放一次 | 与用户预期一致即可 |
| 热钱包 FISS 余额为 0 | 提现开启前必须充值 | 开启提现前检查 |

## 六、回滚方案

| 场景 | 操作 |
|---|---|
| 质押利率配置错 | 后台改方案（立即生效，不影响已有记录） |
| 提现异常 | 后台关闭「FISS 提现开关」 |
| 结算逻辑问题 | 停 `spxchat-staking-settler` timer，修好后补跑 |
| 充值不入账 | 停 `recharge-verifier`，人工核对链上后补记 |
| 客户端问题 | Android 后台下架 FISS 方案（`status=0`），iOS 同上 |

## 七、上线后监控

```bash
# 充值验证器
tail -f /var/log/recharge_verifier.log | grep FISS

# 结算
journalctl -u spxchat-staking-settler -f

# 提现
tail -f /var/log/withdrawal_verifier.log

# 接口 500
grep "api 500" /var/log/nginx/error.log
```

## 八、测试数据清理

测试账号 `qa1101c`（id=49）上有一笔 100,000 FISS 测试质押。上市前清理：

```sql
DELETE FROM staking_dividends WHERE user_id = 49 AND staking_id IN (SELECT id FROM staking_records WHERE user_id = 49);
DELETE FROM staking_records WHERE user_id = 49;
UPDATE wallet_assets SET balance = 0, stakeable_balance = 0 WHERE user_id = 49 AND symbol = 'FISS';
DELETE FROM wallet_transactions WHERE user_id = 49 AND symbol = 'FISS';
UPDATE staking_phases SET reserved_quota = 0, paid_quota = 0 WHERE symbol = 'FISS';
```
