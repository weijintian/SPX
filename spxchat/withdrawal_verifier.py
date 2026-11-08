#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BSC USDT / SPX001 自动提现脚本。

流程：
1. 读取 wallet_transactions 中 status=processing 的提现记录。
2. 使用独立热钱包发送 ERC-20 transfer（gas 上限按估算值动态放大）。
3. 等待 BSC 确认数达到要求后标记 completed，并写入 tx_hash。
4. 回执 status=0（链上回滚，代币未转出）视为终态：标记 failed 并把 amount+fee 退回余额；
   其余状态不明的情况保持 sending，由 reconcile_sending 后续核对，避免重复付款。

依赖：
    pip install web3 pymysql
"""

import json
import logging
import os
import subprocess
import sys
import time
from decimal import Decimal, InvalidOperation
from pathlib import Path
from email_notifier import send_finance_template


import pymysql
from web3 import Web3
from web3.exceptions import TransactionNotFound

try:
    from web3.middleware import ExtraDataToPOAMiddleware
except ImportError:
    from web3.middleware import geth_poa_middleware as ExtraDataToPOAMiddleware

# 强制实时输出
sys.stdout.reconfigure(line_buffering=True)
sys.stderr.reconfigure(line_buffering=True)

# ==================== 路径 ====================
BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.php"
ENV_PATH = Path(os.getenv("WITHDRAWAL_ENV_FILE", "/etc/spxchat/withdrawal.env"))

# ERC-20 transfer ABI
TRANSFER_ABI = [{
    "constant": False,
    "inputs": [
        {"name": "to", "type": "address"},
        {"name": "value", "type": "uint256"},
    ],
    "name": "transfer",
    "outputs": [{"name": "", "type": "bool"}],
    "payable": False,
    "stateMutability": "nonpayable",
    "type": "function",
}]

# 日志
logging.basicConfig(
    level="INFO",
    format="%(asctime)s %(levelname)s %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger("withdrawal-verifier")


class TransactionReverted(RuntimeError):
    """链上回执 status=0：交易已执行失败，代币没有转出，属于终态。"""


# ==================== 读环境变量文件 ====================
def load_env_file(path: Path):
    if not path.is_file():
        raise RuntimeError(f"找不到提现环境变量文件: {path}")
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


load_env_file(ENV_PATH)


def required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"环境变量 {name} 未填写")
    return value


# ==================== 读 PHP 配置 ====================
def load_php_config():
    php_code = (
        "$c=require $argv[1];"
        "echo json_encode(['db'=>$c['db']??[]]);"
    )
    result = subprocess.run(
        ["php", "-r", php_code, str(CONFIG_PATH)],
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
    )
    config = json.loads(result.stdout)
    if not config.get("db"):
        raise RuntimeError("config.php 中缺少数据库配置")
    return config


CONFIG = load_php_config()
DB_CONFIG = CONFIG["db"]

# ==================== 链上配置 ====================
RPC_URL = required_env("BSC_WITHDRAW_RPC_URL")
PRIVATE_KEY = required_env("BSC_WITHDRAW_PRIVATE_KEY")
CHAIN_ID = int(os.getenv("BSC_WITHDRAW_CHAIN_ID", "56"))
# A plain BEP20 transfer is ~51k gas, but proxy/fee-on-transfer tokens need more:
# SPX001 needs ~114k, so a 100k limit reverted every payout with out-of-gas.
GAS_LIMIT = int(os.getenv("BSC_WITHDRAW_GAS_LIMIT", "200000"))
GAS_MARGIN = float(os.getenv("BSC_WITHDRAW_GAS_MARGIN", "1.3"))
GAS_CEILING = int(os.getenv("BSC_WITHDRAW_GAS_CEILING", "500000"))
CONFIRMATIONS_REQUIRED = max(1, int(os.getenv("BSC_WITHDRAW_CONFIRMATIONS", "12")))
POLL_SECONDS = max(5, int(os.getenv("BSC_WITHDRAW_POLL_SECONDS", "30")))
TOKEN_DECIMALS = int(os.getenv("BSC_TOKEN_DECIMALS", "18"))

TOKEN_CONFIG = {
    "USDT": os.getenv(
        "BSC_USDT_CONTRACT",
        "0x55d398326f99059ff775485246999027b3197955",
    ).strip(),
    "SPX001": os.getenv(
        "BSC_SPX001_CONTRACT",
        "0x0E920821464625cf661A4dceDEb6406E1B48FFFf",
    ).strip(),
    "FISS": os.getenv(
        "BSC_FISS_CONTRACT",
        "0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a",
    ).strip(),
}

w3 = Web3(Web3.HTTPProvider(RPC_URL, request_kwargs={"timeout": 30}))

# 注入 POA middleware（BSC 是 POA 链，必须）
try:
    w3.middleware_onion.inject(ExtraDataToPOAMiddleware, layer=0)
except Exception as e:
    logger.warning("注入 POA middleware 失败: %s", e)

account = w3.eth.account.from_key(PRIVATE_KEY)
HOT_WALLET = Web3.to_checksum_address(
    os.getenv("BSC_WITHDRAW_ADDRESS", "").strip() or account.address
)
if HOT_WALLET.lower() != account.address.lower():
    raise RuntimeError("BSC_WITHDRAW_ADDRESS 与私钥推导出的热钱包地址不一致")

for symbol, address in TOKEN_CONFIG.items():
    if not Web3.is_address(address):
        raise RuntimeError(f"{symbol} 合约地址无效")
    TOKEN_CONFIG[symbol] = Web3.to_checksum_address(address)


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


def claim_records(connection):
    """
    从 wallet_transactions 里「抢」一批 processing 的记录，
    抢到后立刻改成 sending，防止多进程重复处理。
    """
    connection.begin()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id,user_id,symbol,chain,amount,address,fee_amount,stakeable_amount,tx_hash "
                "FROM wallet_transactions "
                "WHERE type='withdraw' AND status='processing' "
                "ORDER BY id ASC LIMIT 20 FOR UPDATE"
            )
            records = cursor.fetchall()
            for row in records:
                cursor.execute(
                    "UPDATE wallet_transactions SET status='sending' "
                    "WHERE id=%s AND type='withdraw' AND status='processing'",
                    (row["id"],),
                )
        connection.commit()
        return records
    except Exception:
        connection.rollback()
        raise


def update_status(connection, transaction_id, status, tx_hash=None, error_message=None):
    with connection.cursor() as cursor:
        cursor.execute(
            "UPDATE wallet_transactions "
            "SET status=%s, tx_hash=COALESCE(%s, tx_hash), error_message=%s "
            "WHERE id=%s AND type='withdraw'",
            (status, tx_hash, error_message, transaction_id),
        )
    connection.commit()


def mark_failed(connection, transaction_id, message, user_id=None):
    with connection.cursor() as cursor:
        cursor.execute(
            "UPDATE wallet_transactions SET status='failed', error_message=%s "
            "WHERE id=%s AND type='withdraw'",
            (str(message)[:500], transaction_id),
        )
    connection.commit()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT t.user_id,t.symbol,t.amount,t.address,u.username,u.nickname "
                "FROM wallet_transactions t JOIN users u ON u.id=t.user_id "
                "WHERE t.id=%s LIMIT 1",
                (transaction_id,),
            )
            row = cursor.fetchone()
        if row:
            send_finance_template(connection, "withdraw_failed", {
                "username": row.get("username") or row["user_id"],
                "nickname": row.get("nickname") or "",
                "user_id": int(row["user_id"]),
                "symbol": str(row["symbol"]) + " / BSC",
                "amount": row["amount"],
                "address": row.get("address") or "",
                "reason": str(message)[:500],
                "status": "失败",
                "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            })
    except Exception:
        logger.exception("提现失败邮件发送失败 #%s", transaction_id)
    logger.error("提现 #%s 失败: %s", transaction_id, message)


# ==================== 链上转账 ====================
def amount_to_units(value):
    """把人类可读金额转成最小单位（wei 等）"""
    try:
        amount = Decimal(str(value))
        scaled = amount * (Decimal(10) ** TOKEN_DECIMALS)
        units = int(scaled)
        if amount <= 0 or scaled != units:
            raise InvalidOperation
        return units
    except (InvalidOperation, ValueError, TypeError):
        raise RuntimeError(f"提现金额无效: {value}")


def send_token(symbol, recipient, amount):
    """发送 ERC-20 转账，返回 tx_hash"""
    if symbol not in TOKEN_CONFIG:
        raise RuntimeError(f"不支持的提现资产: {symbol}")
    if not Web3.is_address(recipient):
        raise RuntimeError("提现地址无效")

    recipient = Web3.to_checksum_address(recipient)
    contract_address = TOKEN_CONFIG[symbol]
    contract = w3.eth.contract(address=contract_address, abi=TRANSFER_ABI)
    units = amount_to_units(amount)

    # 广播前先查余额：不足时立即失败并退款，避免广播出必然回滚的交易（白烧 gas、用户白等）
    try:
        # 直接用 eth_call 读 balanceOf，避免依赖 ABI 里是否声明了该方法
        call_data = "0x70a08231" + HOT_WALLET[2:].rjust(64, "0")
        raw_balance = w3.eth.call({"to": contract_address, "data": call_data})
        hot_balance = int.from_bytes(raw_balance, "big") if isinstance(raw_balance, (bytes, bytearray)) else int(str(raw_balance), 16)
    except Exception as balance_error:
        raise RuntimeError(f"无法读取热钱包 {symbol} 余额: {balance_error}")
    if hot_balance < units:
        available = Decimal(hot_balance) / (Decimal(10) ** TOKEN_DECIMALS)
        raise RuntimeError(
            f"热钱包 {symbol} 余额不足（可用 {available}，需要 {amount}），请先充值后再提现"
        )

    nonce = w3.eth.get_transaction_count(HOT_WALLET, "pending")
    gas_price = w3.eth.gas_price

    # Estimate first: a hardcoded limit that is too low burns the whole allowance and
    # reverts on chain (SPX001 needs ~114k, the old 100k default always failed).
    call = contract.functions.transfer(recipient, units)
    try:
        estimated = int(call.estimate_gas({"from": HOT_WALLET}))
        gas = min(GAS_CEILING, max(int(GAS_LIMIT), int(estimated * GAS_MARGIN) + 5000))
    except Exception as estimate_error:
        gas = min(GAS_CEILING, max(int(GAS_LIMIT), 200000))
        logger.warning("gas 估算失败(%s)，使用保守上限 %d", estimate_error, gas)
    logger.info("本次转账 gas 上限 %d（配置 %d，系数 %.2f）", gas, GAS_LIMIT, GAS_MARGIN)

    transaction = call.build_transaction({
        "from": HOT_WALLET,
        "nonce": nonce,
        "chainId": CHAIN_ID,
        "gas": gas,
        "gasPrice": gas_price,
    })

    # 再确认 gas 费够付，否则广播会失败
    if int(w3.eth.get_balance(HOT_WALLET)) < int(gas) * int(gas_price):
        raise RuntimeError("热钱包 BNB 不足，无法支付 gas，请先充值 BNB")

    signed = w3.eth.account.sign_transaction(transaction, PRIVATE_KEY)
    raw_transaction = getattr(
        signed, "raw_transaction",
        getattr(signed, "rawTransaction", None),
    )
    tx_hash = w3.eth.send_raw_transaction(raw_transaction)
    return tx_hash.hex()


def wait_for_confirmations(tx_hash):
    """
    等待确认数达标。
    web3 8.x 中 get_transaction_receipt 在交易未上链时会抛 TransactionNotFound，
    这里捕获后继续轮询，而不是当成错误。

    回执 status != 1 表示交易已在链上执行失败（最典型是 gas 不足被回滚）：
    这是终态，代币没有转出，必须抛 TransactionReverted 让调用方失败并退款，
    不能当成“还在确认”否则记录会永久卡在 sending。
    """
    while True:
        try:
            receipt = w3.eth.get_transaction_receipt(tx_hash)
        except TransactionNotFound:
            logger.info("交易 %s 尚未上链，等待中...", tx_hash)
            time.sleep(POLL_SECONDS)
            continue

        if receipt is not None:
            if int(receipt.get("status", 0)) != 1:
                raise TransactionReverted(
                    "链上交易执行失败(gas 回滚): block={} gasUsed={}/{}".format(
                        int(receipt["blockNumber"]), int(receipt.get("gasUsed", 0)), GAS_LIMIT
                    )
                )
            latest = w3.eth.block_number
            confirmations = latest - receipt["blockNumber"] + 1
            logger.info(
                "交易 %s 确认数 %d/%d",
                tx_hash, confirmations, CONFIRMATIONS_REQUIRED,
            )
            if confirmations >= CONFIRMATIONS_REQUIRED:
                return
        time.sleep(POLL_SECONDS)


def mark_confirm_pending(connection, transaction_id, message):
    """A broadcast transfer may already be on chain: keep the row resumable instead of failing it."""
    with connection.cursor() as cursor:
        cursor.execute(
            "UPDATE wallet_transactions SET status='sending', error_message=%s "
            "WHERE id=%s AND type='withdraw' AND status='sending'",
            ("确认中：" + str(message)[:480], transaction_id),
        )
    connection.commit()
    logger.error("提现 #%s 已广播但未确认完成，保持 sending 等待复核: %s", transaction_id, message)


def fail_and_refund(connection, row, reason):
    """把提现标记为失败，并把 amount+fee 退回用户余额，且只退一次。

    只允许在“交易确实没有转出”时调用（回执 status=0）。状态迁移本身就是锁：
    只有本次调用把行从 sending/processing 改成 failed，才会入账，重复调用不会重复退款。
    """
    transaction_id = int(row["id"])
    stakeable = Decimal(str(row.get("stakeable_amount") or "0"))
    total = Decimal(str(row["amount"])) + Decimal(str(row.get("fee_amount") or "0"))
    message = (str(reason) + "（已自动退款）")[:500]
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE wallet_transactions SET status='failed', error_message=%s "
                "WHERE id=%s AND type='withdraw' AND status IN ('sending','processing')",
                (message, transaction_id),
            )
            if cursor.rowcount != 1:
                connection.rollback()
                logger.warning("提现 #%s 状态已变化，跳过自动退款以免重复入账", transaction_id)
                return False
            cursor.execute(
                "UPDATE wallet_assets SET balance = balance + %s, stakeable_balance = stakeable_balance + %s "
                "WHERE user_id=%s AND symbol=%s AND chain=%s",
                (str(total), str(stakeable), row["user_id"], row["symbol"], row["chain"]),
            )
            if cursor.rowcount != 1:
                connection.rollback()
                logger.error("提现 #%s 退款中止：找不到 %s/%s 资产行，已回滚",
                             transaction_id, row["symbol"], row["chain"])
                return False
            cursor.execute(
                "INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,stakeable_amount,address,status) "
                "VALUES (%s,%s,%s,'withdraw_refund',%s,%s,'SYSTEM','completed')",
                (row["user_id"], row["symbol"], row["chain"], str(total), str(stakeable)),
            )
        connection.commit()
    except Exception:
        connection.rollback()
        logger.exception("提现 #%s 自动退款失败，需人工处理", transaction_id)
        return False
    logger.error("提现 #%s 已失败并退款 %s %s：%s", transaction_id, total, row["symbol"], reason)
    return True


def reconcile_sending(connection, older_than_minutes=10, limit=10):
    """兜底核对停留在 sending 的提现。

    process_record 遇到“状态不明”的错误会把它留在 sending，而领取队列只扫 processing，
    所以必须有这一轮：回执确认失败 → 失败并退款；确认成功但漏记 → 补成 completed；
    还没上链 → 记录告警继续等。
    """
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT id,user_id,symbol,chain,amount,address,fee_amount,stakeable_amount,tx_hash "
            "FROM wallet_transactions WHERE type='withdraw' AND status='sending' "
            "AND tx_hash IS NOT NULL AND tx_hash<>'' "
            "AND created_at < DATE_SUB(NOW(), INTERVAL %s MINUTE) "
            "ORDER BY id ASC LIMIT %s",
            (older_than_minutes, limit),
        )
        rows = cursor.fetchall()
    for row in rows:
        tx_hash = row["tx_hash"]
        try:
            receipt = w3.eth.get_transaction_receipt(tx_hash)
        except TransactionNotFound:
            logger.warning("提现 #%s 广播后仍未上链，继续等待: %s", row["id"], tx_hash)
            continue
        except Exception as error:
            logger.warning("查询提现 #%s 回执失败: %s", row["id"], error)
            continue
        confirmations = w3.eth.block_number - int(receipt["blockNumber"]) + 1
        if int(receipt.get("status", 0)) != 1:
            fail_and_refund(
                connection, row,
                "链上交易执行失败: block={} gasUsed={}".format(
                    int(receipt["blockNumber"]), int(receipt.get("gasUsed", 0))),
            )
            continue
        if confirmations >= CONFIRMATIONS_REQUIRED:
            update_status(connection, row["id"], "completed", tx_hash, None)
            logger.info("提现 #%s 补记为已完成: %s", row["id"], tx_hash)
        else:
            logger.info("提现 #%s 仍在确认中 %d/%d", row["id"], confirmations, CONFIRMATIONS_REQUIRED)


def reclaim_unbroadcast(connection, older_than_minutes=10):
    """A crash between claim and broadcast leaves rows in sending with no hash; nothing was
    signed, so they are safe to put back in the queue. Rows that already have a hash are
    deliberately left alone so an in-flight confirmation is never raced.

    wallet_transactions has no updated_at column — created_at is the only timestamp here.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            "UPDATE wallet_transactions SET status='processing', error_message='已重新排队（上次未广播）' "
            "WHERE type='withdraw' AND status='sending' AND (tx_hash IS NULL OR tx_hash='') "
            "AND created_at < DATE_SUB(NOW(), INTERVAL %s MINUTE)",
            (older_than_minutes,),
        )
        reclaimed = cursor.rowcount
    connection.commit()
    if reclaimed:
        logger.warning("回收 %d 条未广播的 sending 提现，已重新排队", reclaimed)
    return reclaimed


def count_processing(connection):
    """Rows waiting to be paid. If this is > 0 while a round claimed nothing, the payout loop
    is not draining and somebody must look at it."""
    with connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) AS n FROM wallet_transactions WHERE type='withdraw' AND status='processing'")
        row = cursor.fetchone()
    return int(row["n"]) if row else 0


# ==================== 处理单条提现 ====================
def process_record(connection, row):
    transaction_id = row["id"]
    symbol = str(row["symbol"]).upper()
    existing_hash = (row.get("tx_hash") or "").strip()
    tx_hash = existing_hash
    try:
        logger.info(
            "处理提现 #%s: %s %s -> %s",
            transaction_id, row["amount"], symbol, row["address"],
        )
        if existing_hash:
            # Already broadcast by an earlier attempt. Re-signing here would pay the same
            # withdrawal twice, so only confirmation work is allowed for this hash.
            logger.warning("提现 #%s 已广播过 %s，本次只等待确认，不重复发送", transaction_id, existing_hash)
        else:
            tx_hash = send_token(symbol, row["address"], row["amount"])
            # 广播成功，立刻写 tx_hash 和 sending 状态，防止重复发送
            update_status(connection, transaction_id, "sending", tx_hash)
            logger.info("提现 #%s 已广播: %s", transaction_id, tx_hash)

        try:
            wait_for_confirmations(tx_hash)
        except TransactionReverted as reverted:
            # status=0 是终态：代币没有转出，必须失败并退款，否则用户被扣款却没收到币。
            fail_and_refund(connection, row, str(reverted))
            return
        except Exception as confirm_error:
            # 状态不明（RPC 异常、迟迟未上链）：转账仍可能成功，保持 sending，
            # 由 reconcile_sending 后续核对，绝不在这里退款。
            mark_confirm_pending(connection, transaction_id, confirm_error)
            return

        update_status(connection, transaction_id, "completed", tx_hash)
        logger.info("提现 #%s 已完成: %s", transaction_id, tx_hash)
    except Exception as exception:
        if tx_hash:
            mark_confirm_pending(connection, transaction_id, exception)
            return
        # 还没广播：链上没有任何动作，必须原路退回（金额+手续费），
        # 否则用户被扣款却拿不到币（例如合约未配置、热钱包余额不足、地址无效）。
        logger.error("提现 #%s 准备阶段失败，执行退款: %s", transaction_id, exception)
        fail_and_refund(connection, row, str(exception))


# ==================== 单轮 ====================
def run_once():
    if not w3.is_connected():
        raise RuntimeError("无法连接 BSC RPC")
    if int(w3.eth.chain_id) != CHAIN_ID:
        raise RuntimeError("RPC 链 ID 与配置不一致")

    connection = connect_db()
    try:
        # Housekeeping must never block payouts: a failure here is logged and the round continues.
        try:
            reclaim_unbroadcast(connection)
        except Exception:
            logger.exception("回收未广播提现失败，本轮继续处理待付款记录")
        try:
            reconcile_sending(connection)
        except Exception:
            logger.exception("核对 sending 提现失败，本轮继续处理待付款记录")
        records = claim_records(connection)
        if records:
            logger.info("发现 %d 条待处理提现", len(records))
        else:
            logger.info("本轮无待处理提现")
            # Canary: a waiting row that nothing claims means the payout loop is broken.
            waiting = count_processing(connection)
            if waiting:
                logger.warning("有 %d 条 processing 提现本轮未被领取，请检查打币服务", waiting)
        for row in records:
            process_record(connection, row)
    finally:
        connection.close()


# ==================== 主循环 ====================
def main():
    once = "--once" in sys.argv[1:]

    logger.info("=" * 60)
    logger.info("BSC 自动提现服务启动")
    logger.info("=" * 60)
    logger.info("热钱包地址: %s", HOT_WALLET)
    logger.info("RPC 节点: %s", RPC_URL)
    logger.info("链 ID: %d", CHAIN_ID)
    logger.info("支持资产: %s", ", ".join(TOKEN_CONFIG.keys()))
    logger.info("确认数: %d", CONFIRMATIONS_REQUIRED)
    logger.info("轮询间隔: %d 秒", POLL_SECONDS)
    logger.info("=" * 60)
    while True:
        try:
            run_once()
        except Exception:
            logger.exception("提现验证轮次失败")
            if once:
                return 1
        if once:
            return 0
        logger.info("等待 %d 秒后进入下一轮...", POLL_SECONDS)
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    sys.exit(main())
