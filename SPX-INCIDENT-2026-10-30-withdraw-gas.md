# 事故记录：提现 gas 上限过低导致交易回滚、资金被扣未退

- 日期：2026-10-30
- 影响记录：`wallet_transactions` #440（用户 41，SPX001，99.14395194 + 手续费 1.00145406）
- 状态：已处置，资金已到账，代码已修复并上线

## 一、现象

用户在 App 里提交提现后，记录长时间停在「转账中」，失败原因显示「确认中：链上交易执行失败」。管理员点了「仅修改状态」把他改成「已拒绝」，但**用户余额没有退回**，链上也没有转出。

## 二、时间线

| 时间（+08） | 事件 |
|---|---|
| 01:31:51 | 提交提现，`walletDebit` 立即扣除 100.145406，记录置 `pending` |
| 01:32:03 | 广播 `acdb6fca…`，nonce 47 |
| 01:32:34 | 回执 `status=0x0`、`gasUsed=100000`（顶满上限）、`logs=0` → 抛"链上交易执行失败" |
| 01:32:34 | 被当成"待确认"，写回 `sending`，错误信息「确认中：链上交易执行失败」 |
| — | 领取队列只扫 `processing`，该记录**再也不会被处理** |
| 06:39:41 | 记录被重新通过后再次广播 `f78647260d…`，nonce 48，同样 `status=0`、`gasUsed=100000` |
| 06:40:12 | 再次卡在 `sending` |
| 09:10 | 处置：gas 上限提到 200000，重排后广播 `4fde70b8…` 成功 |
| 09:10:43 | 确认数达标，`completed` |

## 三、根因

**gas 上限设小了。** 实测该代币转账需要 **114,243** gas，而配置是 **100,000**：

- `eth_call` 模拟转账成功（代币允许转、热钱包余额充足、收款地址正常）
- `eth_estimateGas` = 114,243 > 100,000
- 两次失败交易的 `gasUsed` 都等于 100,000（顶满），`logs=0`（没有 Transfer 事件）

`gasUsed == gas` 且无日志，是 out-of-gas 回滚的确定特征：代币一个都没转出。

## 四、放大问题的三个代码缺陷

1. **把"回滚"当成"待确认"**：`wait_for_confirmations` 抛异常后，`process_record` 统一走 `mark_confirm_pending`，把终态错误写回 `sending`
2. **`sending` 记录无人接管**：`claim_records` 只扫 `processing`，带 tx_hash 的 `sending` 永不重试
3. **失败不退款**：全链路只有管理端「拒绝并退款」（且只对 `pending` 可见）会退款；`mark_failed` 与「仅修改状态」都只改标签不动钱

## 五、处置

### 立即
```
BSC_WITHDRAW_GAS_LIMIT: 100000 → 200000      （/etc/spxchat/withdrawal.env）
systemctl restart withdrawal-verifier
#440: status='processing', tx_hash=NULL       （重新排队）
```

链上核验：
- `4fde70b8…` status=0x1，block 130574703，gasUsed **113211**
- Transfer：热钱包 → `0x825e2cd7…`，金额 **99.14395194**
- 收款地址余额 4217561.5531 → **4217660.6971**（+99.14395194）

### 代码修复（已上线）

| 文件 | 修改 |
|---|---|
| `withdrawal_verifier.py` | ① `send_token` 用 `estimate_gas` 动态估算，上限 `max(配置, 估算×1.3+5000)`，天花板 500000；配置默认值改 200000<br>② 新增 `TransactionReverted`，回执 `status=0` 时抛它（区分"回滚"与"未确认"）<br>③ 新增 `fail_and_refund`：回滚 → 标记 `failed` 并退回 `amount+fee`，用状态迁移做幂等锁<br>④ 新增 `reconcile_sending`：每轮核对 `sending` 记录，回滚→退款、漏记→补完成、未上链→告警 |
| `spxchat-systemadmin/admin.php` | ① 提现禁止用「仅修改状态」改成 `rejected/failed`（提示走「拒绝并退款」）<br>② `withdraw.retry` 拒绝重试"已自动退款"的记录，避免重复付款 |
| `index.php` | 全局 500 兜底补 `error_log`（action、IP、异常类、消息、文件:行），此前 500 没有任何日志 |

## 六、验证

- 离线单测 `fail_and_refund`：11 项全过（正常退款、重复调用不重复退款、资产行缺失回滚）
- 链上：交易成功、Transfer 金额与收款余额一致
- 服务：withdrawal-verifier / recharge-verifier / spxchat-ws / telegram-bot / php8.3-fpm / nginx 全部 active
- 部署文件 sha256 与构建产物逐一比对一致
- `php -l`、`python3 -m py_compile` 均通过

## 七、回滚

| 内容 | 路径 |
|---|---|
| verifier + admin | `/root/spx-backups/withdraw-gasfix-20261030T011302Z/` |
| index.php | `/root/spx-backups/api500-logging-20261030T011445Z/` |
| gas 配置 | `/root/withdrawal.env.bak-20261030T010938Z` |

回滚后需 `systemctl restart withdrawal-verifier` 与 `systemctl reload php8.3-fpm`。

## 八、遗留

- 今天另有 5 起一次性 500（`chats`、`wallet`、`groups`、`presence.update`、`groups.messages.reconcile` 各 1 次），当时无日志。现已补日志，下次出现可在 `/var/log/nginx/error.log` 搜 `api 500 action=`。
- 建议给"提现失败已退款"补一条站内/邮件通知（当前仅写日志与管理端可查）。
- 建议把 `BSC_WITHDRAW_GAS_MARGIN`、`BSC_WITHDRAW_GAS_CEILING` 一并写入 `withdrawal.env` 显式声明。
