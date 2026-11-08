#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BSC USDT / SPX001 充值验证脚本
- 每 30 秒轮询 recharge_records 表里的 pending 记录
- 对每条记录，验证链上交易
- 通过 → 加余额 + 标记 completed
- 失败 → 标记 failed + 写错误原因
"""

import sys
import json
import time
import logging
import subprocess
import requests
import pymysql
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from email_notifier import send_finance_template


# 强制实时输出
sys.stdout.reconfigure(line_buffering=True)
sys.stderr.reconfigure(line_buffering=True)

# ==================== 配置 ====================
BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.php"
USDT_CONTRACT_DEFAULT = "0x55d398326f99059ff775485246999027b3197955"
SPX001_CONTRACT_DEFAULT = "0x0E920821464625cf661A4dceDEb6406E1B48FFFf"
FISS_CONTRACT_DEFAULT = "0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a"
TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
POLL_SECONDS = 30
MAX_TX_AGE_SECONDS = 86400    # 链上交易 24 小时内（原来 10 分钟：用户先转账、隔一会儿再提交就会失败；
                              # 交易哈希唯一 + Transfer 事件强校验，放宽时效不会带来重复入账风险）
PENDING_EXPIRE_HOURS = 24     # 安全网：超过 24 小时仍 pending 的置 failed，绝不让记录静默挂账
CONFIRMATIONS_REQUIRED = 12

# 日志
logging.basicConfig(
    level="INFO",
    format="%(asctime)s %(levelname)s %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger("recharge-verifier")


# ==================== 读 PHP 配置 ====================
def load_php_config():
    php_code = (
        "$c=require $argv[1];"
        "echo json_encode(['db'=>$c['db']??[],"
        "'deposit_address'=>$c['wallet']['bsc_usdt_deposit_address']??'']);"
    )
    result = subprocess.run(
        ["php", "-r", php_code, str(CONFIG_PATH)],
        check=True, capture_output=True, text=True, timeout=15,
    )
    cfg = json.loads(result.stdout)
    if not cfg.get("db"):
        raise RuntimeError("config.php 中缺少数据库配置")
    return cfg


CONFIG = load_php_config()
DB_CONFIG = CONFIG["db"]
RPC_URL = (
    __import__("os").getenv("BSC_RPC")
    or "https://bsc-dataseed1.binance.org"
)
TOKEN_CONFIG = {
    "USDT": {"contract": USDT_CONTRACT_DEFAULT.lower(), "decimals": 18},
    "SPX001": {"contract": SPX001_CONTRACT_DEFAULT.lower(), "decimals": 18},
    "FISS": {"contract": FISS_CONTRACT_DEFAULT.lower(), "decimals": 18},
}

http = requests.Session()
http.headers.update({"Content-Type": "application/json"})


# ==================== 数据库 ====================
def connect_db():
    return pymysql.connect(
        host=DB_CONFIG["host"],
        port=int(DB_CONFIG.get("port", 3306)),
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        database=DB_CONFIG["name"],
        charset=DB_CONFIG.get("charset", "utf8mb4"),
        autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )


def get_deposit_address(connection):
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT `value` FROM app_settings WHERE `key`=%s LIMIT 1",
            ("bsc_usdt_deposit_address",),
        )
        row = cursor.fetchone()
    value = (row or {}).get("value") or CONFIG.get("deposit_address") or ""
    return value.lower()


def update_heartbeat(connection, status, error_message=None):
    with connection.cursor() as cursor:
        cursor.execute(
            "INSERT INTO service_heartbeats (service_name,status,checked_at,error_message) "
            "VALUES ('bsc_recharge_verifier',%s,NOW(),%s) "
            "ON DUPLICATE KEY UPDATE status=VALUES(status),checked_at=NOW(),error_message=VALUES(error_message)",
            (status, (str(error_message)[:500] if error_message else None)),
        )
    connection.commit()


# ==================== RPC ====================
def rpc(method, params):
    r = http.post(
        RPC_URL,
        json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
        timeout=20,
    )
    r.raise_for_status()
    payload = r.json()
    if payload.get("error"):
        raise RuntimeError(f"BSC RPC {method} failed: {payload['error']}")
    return payload.get("result")


def topic_address(topic):
    return "0x" + topic.removeprefix("0x")[-40:].lower()


def decode_amount(data, decimals):
    return Decimal(int(data, 16)).scaleb(-decimals)


# ==================== 记录结果 ====================
def record_result(connection, row, status, message,
                  from_address=None, to_address=None,
                  block_number=None, confirmations=None):
    connection.begin()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id,status FROM recharge_records WHERE id=%s FOR UPDATE",
                (row["id"],),
            )
            current = cursor.fetchone()
            if not current or current["status"] != "pending":
                connection.commit()
                return
            cursor.execute(
                "UPDATE recharge_records SET status=%s,error_message=%s "
                "WHERE id=%s AND status='pending'",
                (status, message[:500], row["id"]),
            )
            cursor.execute(
                "UPDATE wallet_transactions SET status=%s "
                "WHERE tx_hash=%s AND user_id=%s AND type='deposit' AND status='pending'",
                # 转人工审核时，钱包流水保持 pending，App 端显示「处理中」而不是英文状态
                ("completed" if status == "completed" else ("pending" if status == "manual_review" else "rejected"),
                 row["tx_hash"], row["user_id"]),
            )
            cursor.execute(
                "INSERT INTO recharge_audit_logs "
                "(record_id,user_id,tx_hash,amount,token,status,"
                "from_address,to_address,block_number,confirmations,error_message) "
                "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (row["id"], row["user_id"], row["tx_hash"], row["amount"],
                 str(row.get("currency") or "USDT"), status,
                 from_address, to_address, block_number, confirmations,
                 message[:500] or None),
            )
        connection.commit()
    except Exception:
        connection.rollback()
        raise


def reject(connection, row, reason, **details):
    # 自动验证失败不再直接判死，转人工审核：后台核实链上到账后可一键补记
    logger.warning("recharge %s 转人工审核: %s", row["tx_hash"], reason)
    record_result(connection, row, "manual_review", reason, **details)


def expire_stale_pending(connection):
    """A pending recharge must never be abandoned silently: either it is verified, rejected by
    the 10-minute rule below, or explicitly expired here after PENDING_EXPIRE_HOURS."""
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT r.id,r.user_id,r.tx_hash,r.amount,r.currency,r.wallet_address,r.deposit_address,"
            "UNIX_TIMESTAMP(r.created_at) AS submitted_at "
            "FROM recharge_records r "
            "WHERE r.status='pending' AND r.currency IN ('USDT','SPX001','FISS') "
            "AND r.created_at < DATE_SUB(NOW(), INTERVAL %s HOUR) "
            "ORDER BY r.id ASC LIMIT 100",
            (PENDING_EXPIRE_HOURS,),
        )
        stale = cursor.fetchall()
    connection.commit()
    for row in stale:
        reject(connection, row, f"提交后 {PENDING_EXPIRE_HOURS} 小时仍未确认到账，请人工核对链上交易")
    if stale:
        logger.warning("已将 %d 条超时未到账的充值置为 failed", len(stale))
    return len(stale)


# ==================== 处理单条 ====================
def process_record(connection, row, latest_block, fallback_deposit_address):
    tx_hash = str(row["tx_hash"]).lower()
    deposit_address = str(row.get("deposit_address") or fallback_deposit_address).lower()
    symbol = str(row.get("currency") or "USDT").upper()
    token = TOKEN_CONFIG.get(symbol)
    if token is None:
        reject(connection, row, f"不支持的充值资产: {symbol}")
        return
    token_address = token["contract"]
    token_decimals = int(token["decimals"])

    logger.info(
        "🔍 检查 #%s: 用户 %s, %s %s, %s...",
        row["id"], row["user_id"], row["amount"], symbol, tx_hash[:20],
    )

    # 用户必须绑钱包
    user_wallet = str(row.get("wallet_address") or "").lower()
    if not user_wallet:
        reject(connection, row, "用户未绑定钱包地址")
        return
    logger.info("🔐 用户 %s 绑定钱包: %s...%s",
                row["user_id"], user_wallet[:10], user_wallet[-6:])

    # 查链上
    transaction = rpc("eth_getTransactionByHash", [tx_hash])
    receipt = rpc("eth_getTransactionReceipt", [tx_hash])
    if transaction is None or receipt is None:
        if time.time() - float(row["submitted_at"]) > MAX_TX_AGE_SECONDS:
            reject(connection, row, "交易哈希提交后 10 分钟仍未上链")
        else:
            logger.info("⏳ #%s 交易尚未上链，等待下一轮", row["id"])
        return

    sender = str(transaction.get("from") or "").lower()
    contract = str(transaction.get("to") or "").lower()
    # tx.from 只是"付 gas 的地址"，tx.to 只是"被调用的合约"。
    # 通过 DEX 路由、聚合器、合约钱包或代付发起的转账，这两个都可能是中间合约，
    # 而代币的真实转出方要看 Transfer 事件（from=绑定钱包 → to=充值地址）。
    # 所以这里不再用 tx.from / tx.to 直接拒绝，最终判据统一交给下面的 Transfer 事件匹配。
    if not (sender == user_wallet and contract == token_address):
        logger.info(
            "ℹ️ #%s 非直连代币转账（tx.from=%s tx.to=%s），改按 Transfer 事件校验",
            row["id"], sender[:10], contract[:10],
        )
    if str(receipt.get("status", "")).lower() != "0x1":
        reject(connection, row, "链上交易执行失败",
               from_address=sender, to_address=deposit_address)
        return

    block_number = int(receipt.get("blockNumber", "0x0"), 16)
    if block_number <= 0:
        logger.info("⏳ #%s 交易未打包，等待下一轮", row["id"])
        return
    confirmations = latest_block - block_number + 1

    block = rpc("eth_getBlockByNumber", [hex(block_number), False])
    if not block or not block.get("timestamp"):
        return
    block_timestamp = int(block["timestamp"], 16)
    age = time.time() - block_timestamp
    if age > MAX_TX_AGE_SECONDS:
        reject(connection, row, "链上交易时间已超过 10 分钟",
               from_address=sender, to_address=deposit_address,
               block_number=block_number, confirmations=confirmations)
        return
    logger.info("✅ #%s 交易时间验证通过: %d秒前", row["id"], int(age))

    if confirmations < CONFIRMATIONS_REQUIRED:
        logger.info("⏳ #%s 确认数不足: %d/%d",
                    row["id"], confirmations, CONFIRMATIONS_REQUIRED)
        return

    # 解析 Transfer 事件
    expected_amount = Decimal(str(row["amount"]))
    transfer_amount = Decimal(0)
    for log in receipt.get("logs", []):
        topics = log.get("topics") or []
        if (str(log.get("address", "")).lower() != token_address
                or len(topics) < 3
                or str(topics[0]).lower() != TRANSFER_TOPIC):
            continue
        if (topic_address(str(topics[1])) != user_wallet
                or topic_address(str(topics[2])) != deposit_address):
            continue
        try:
            transfer_amount += decode_amount(str(log.get("data", "0x0")), token_decimals)
        except (ValueError, ArithmeticError):
            continue

    if transfer_amount <= 0:
        reject(connection, row,
               f"{symbol} 未检测到有效到账金额（提交 {expected_amount}）",
               from_address=sender, to_address=deposit_address,
               block_number=block_number, confirmations=confirmations)
        return
    if transfer_amount != expected_amount:
        # 代币可能带转账税（收到 < 提交），也可能是用户填错金额。
        # 平台只能按链上实际到账入账，否则用户的钱会卡在平台地址。
        logger.warning(
            "充值 #%s 金额与提交不一致: 提交 %s，实际到账 %s（按实际入账）",
            row["id"], expected_amount, transfer_amount,
        )

    # 入账
    connection.begin()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id,status FROM recharge_records WHERE id=%s FOR UPDATE",
                (row["id"],),
            )
            current = cursor.fetchone()
            if not current or current["status"] != "pending":
                connection.commit()
                return

            cursor.execute(
                "SELECT id,status FROM wallet_transactions "
                "WHERE tx_hash=%s AND user_id=%s AND type='deposit' FOR UPDATE",
                (tx_hash, row["user_id"]),
            )
            ledger = cursor.fetchone()
            if not ledger or ledger["status"] != "pending":
                connection.rollback()
                reject(connection, row, "找不到对应的待处理钱包流水")
                return

            cursor.execute(
                "INSERT INTO wallet_assets (user_id,symbol,contract_address,chain,balance,stakeable_balance) "
                "VALUES (%s,%s,%s,'BSC',%s,IF(%s IN ('SPX001','FISS'),%s,0)) "
                "ON DUPLICATE KEY UPDATE balance=balance+VALUES(balance), "
                "stakeable_balance=stakeable_balance+VALUES(stakeable_balance)",
                (row["user_id"], symbol, token_address, str(transfer_amount), symbol, str(transfer_amount)),
            )
            cursor.execute(
                "UPDATE recharge_records SET status='completed',error_message=NULL,amount=%s "
                "WHERE id=%s AND status='pending'",
                (str(transfer_amount), row["id"]),
            )
            cursor.execute(
                "UPDATE wallet_transactions SET status='completed', amount=%s, "
                "stakeable_amount=IF(symbol IN ('SPX001','FISS'),%s,0) "
                "WHERE tx_hash=%s AND user_id=%s AND type='deposit' AND status='pending'",
                (str(transfer_amount), str(transfer_amount), tx_hash, row["user_id"]),
            )
            cursor.execute(
                "INSERT INTO recharge_audit_logs "
                "(record_id,user_id,tx_hash,amount,token,status,"
                "from_address,to_address,block_number,confirmations,error_message) "
                "VALUES (%s,%s,%s,%s,%s,'completed',%s,%s,%s,%s,NULL)",
                (row["id"], row["user_id"], tx_hash, str(transfer_amount), symbol,
                 sender, deposit_address, block_number, confirmations),
            )
        connection.commit()
        try:
            send_finance_template(connection, 'recharge_completed', {
                'username': row.get('username') or row['user_id'],
                'nickname': row.get('nickname') or '',
                'user_id': int(row['user_id']),
                'symbol': f'{symbol} / BSC',
                'amount': transfer_amount,
                'status': '已到账',
                'created_at': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
            })
        except Exception:
            logger.exception("充值到账邮件发送失败 #%s", row["id"])
        logger.info("✅ #%s 充值成功！+%s %s (用户 %s, 来源: %s...%s)",
                    row["id"], transfer_amount, symbol, row["user_id"],
                    sender[:10], sender[-6:])
    except Exception:
        connection.rollback()
        raise


# ==================== 单轮 ====================
def run_once():
    logger.info("=" * 60)
    logger.info("🔄 开始新一轮验证")
    logger.info("=" * 60)

    connection = connect_db()
    try:
        # 读充值地址
        deposit_address = get_deposit_address(connection)
        connection.commit()
        logger.info("📌 平台充值地址: %s", deposit_address)
        if not deposit_address or not deposit_address.startswith("0x"):
            logger.error("❌ 充值地址无效")
            update_heartbeat(connection, "error", "平台充值地址无效")
            return

        # 检查 RPC
        chain_id = rpc("eth_chainId", [])
        if not chain_id or int(chain_id, 16) != 56:
            logger.error("❌ RPC 不是 BSC 主网")
            update_heartbeat(connection, "error", "RPC 不是 BSC 主网")
            return
        logger.info("🌐 RPC 连接正常 (BSC 主网)")

        latest_hex = rpc("eth_blockNumber", [])
        if not latest_hex:
            logger.error("❌ 无法获取区块高度")
            update_heartbeat(connection, "error", "无法获取 BSC 区块高度")
            return
        latest_block = int(latest_hex, 16)
        logger.info("📊 当前区块高度: %d", latest_block)

        # 先把超时未到账的清掉，再取所有真正 pending 的记录（不再按 5 分钟窗口丢弃）
        expire_stale_pending(connection)
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT r.id,r.user_id,r.tx_hash,r.amount,r.currency,r.wallet_address,r.deposit_address,u.username,u.nickname,"
                "UNIX_TIMESTAMP(r.created_at) AS submitted_at "
                "FROM recharge_records r JOIN users u ON u.id=r.user_id "
                "WHERE r.status='pending' AND r.currency IN ('USDT','SPX001','FISS') "
                "ORDER BY r.id ASC LIMIT 100"
            )
            records = cursor.fetchall()
        connection.commit()

        if not records:
            logger.info("⏳ 本轮无待处理记录")
            update_heartbeat(connection, "ok")
            return

        logger.info("📥 发现 %d 条待处理充值（USDT/SPX001/FISS）", len(records))
        errors = []
        for row in records:
            try:
                process_record(connection, row, latest_block, deposit_address)
            except (requests.RequestException, RuntimeError) as exc:
                logger.error("临时验证错误 %s: %s", row["tx_hash"], exc)
                errors.append(str(exc))
            except Exception:
                logger.exception("未预期错误 %s", row["tx_hash"])
                errors.append("记录处理发生未预期错误")
        update_heartbeat(connection, "warning" if errors else "ok", "; ".join(errors[-3:]) if errors else None)

    finally:
        connection.close()


# ==================== 主循环 ====================
def main():
    once = "--once" in sys.argv[1:]

    logger.info("=" * 60)
    logger.info("🚀 BSC USDT/SPX001 充值验证服务启动")
    logger.info("=" * 60)
    logger.info("📋 代币: USDT + SPX001")
    logger.info("⏱️  轮询间隔: %d 秒", POLL_SECONDS)
    logger.info("⏰ 链上交易时效: %d 分钟", MAX_TX_AGE_SECONDS // 60)
    logger.info("⏰ pending 失效: %d 小时", PENDING_EXPIRE_HOURS)
    logger.info("🔐 安全验证: 钱包绑定 + 来源地址 + 时间 + 确认数")
    logger.info("=" * 60)
    while True:
        try:
            run_once()
        except Exception as exc:
            logger.exception("verification cycle failed")
            try:
                connection = connect_db()
                try:
                    update_heartbeat(connection, "error", exc)
                finally:
                    connection.close()
            except Exception:
                logger.exception("unable to save verifier heartbeat")
            if once:
                return 1
        if once:
            return 0
        logger.info("💤 等待 %d 秒后进入下一轮...", POLL_SECONDS)
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    sys.exit(main())
