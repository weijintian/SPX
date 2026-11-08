#!/usr/bin/env python3
"""FISS 系统钱包自动销毁（余额模式，不扫区块）。

每 30 秒读一次系统钱包的 FISS 余额，达到最小销毁量就按「当前累计销毁档位」的
比例打进黑洞：

    < 1 亿   → 100%
    1–2 亿   → 50%
    2–3 亿   → 25%
    ≥ 3 亿   → 10%

比例可在后台 app_settings.burn_ratios 调整。
未销毁的部分留在钱包里，作为用户提现的备用金。

只处理 FISS：合约 symbol 必须为 FISS、精度必须 18，否则直接退出。
钱包里若混入 SPX001 / USDT，只会告警，绝不会被销毁。
"""
from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import time
import uuid
from decimal import Decimal, ROUND_DOWN
from pathlib import Path

import pymysql
from web3 import Web3
from web3.middleware import ExtraDataToPOAMiddleware

ENV_PATH = Path("/etc/spxchat/fiss-system.env")
TELEGRAM_ENV = Path("/etc/spxchat/telegram.env")
RPC_ENV_PATH = Path("/etc/spxchat/withdrawal.env")
CONFIG_PATH = Path("/var/www/html/spxchat/config.php")

BURN_ADDRESS_DEFAULT = "0x000000000000000000000000000000000000dEaD"
FISS_FALLBACK = "0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a"
# 只允许销毁 FISS：下面这些代币永远不动
OTHER_TOKENS = {
    "SPX001": "0x0E920821464625cf661A4dceDEb6406E1B48FFFf",
    "USDT": os.getenv("BSC_USDT_CONTRACT", "0x55d398326f99059ff775485246999027b3197955"),
}
DECIMALS = 18
MIN_BNB = Decimal("0.002")
MIN_BURN = Decimal(os.getenv("FISS_BURN_MIN_AMOUNT", "10000"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
log = logging.getLogger("fiss-burn")


def load_env(path: Path) -> None:
    if not path.is_file():
        return
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key and key not in os.environ:
            os.environ[key] = value.strip().strip('"').strip("'")


load_env(ENV_PATH)
load_env(RPC_ENV_PATH)
load_env(TELEGRAM_ENV)


def notify(text: str) -> None:
    """发 Telegram；没配置就静默跳过。"""
    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    chats = [c for c in os.getenv("TELEGRAM_CHAT_IDS", "").replace(",", " ").split() if c.lstrip("-").isdigit()]
    if not token or not chats:
        log.warning("未配置 Telegram，跳过通知")
        return
    import urllib.request
    for chat in chats:
        try:
            payload = json.dumps({"chat_id": chat, "text": text, "disable_web_page_preview": True}).encode()
            req = urllib.request.Request(
                "https://api.telegram.org/bot%s/sendMessage" % token,
                data=payload, headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status >= 400:
                    log.warning("Telegram 发送失败 status=%s", resp.status)
        except Exception as exc:  # noqa: BLE001
            log.warning("Telegram 发送异常: %s", exc)


def php_config() -> dict:
    code = "$c=require $argv[1];echo json_encode(['db'=>$c['db']??[]]);"
    out = subprocess.run(["php", "-r", code, str(CONFIG_PATH)], check=True, capture_output=True, text=True, timeout=20)
    cfg = json.loads(out.stdout)
    if not cfg.get("db"):
        raise RuntimeError("config.php 缺少数据库配置")
    return cfg["db"]


def connect(cfg: dict):
    return pymysql.connect(
        host=cfg["host"], port=int(cfg["port"]), user=cfg["user"], password=cfg["password"],
        database=cfg["name"], charset="utf8mb4", autocommit=False, cursorclass=pymysql.cursors.DictCursor,
    )


def read_setting(cur, key: str, default: str = "") -> str:
    cur.execute("SELECT `value` FROM app_settings WHERE `key`=%s LIMIT 1", (key,))
    row = cur.fetchone()
    return (row or {}).get("value") or default


def tier_ratio(burned: Decimal, tiers: list, ratios: dict) -> tuple:
    index = 0
    threshold = Decimal("0")
    for i, tier in enumerate(tiers):
        limit = Decimal(str(tier.get("threshold_amount") or "0"))
        if burned >= limit:
            index = i
            threshold = limit
    ratio = Decimal(str(ratios.get(str(index), ratios.get("0", "100"))))
    return ratio, threshold


def connect_web3() -> tuple:
    candidates = [
        os.getenv("FISS_BURN_RPC_URL", "").strip(),
        os.getenv("BSC_RPC_URL", "").strip(),
        "https://bsc-rpc.publicnode.com",
        "https://bsc-dataseed1.binance.org",
    ]
    for candidate in candidates:
        if not candidate:
            continue
        try:
            client = Web3(Web3.HTTPProvider(candidate, request_kwargs={"timeout": 25}))
            try:
                client.middleware_onion.inject(ExtraDataToPOAMiddleware, layer=0)
            except Exception:  # noqa: BLE001
                pass
            if client.is_connected():
                return client, candidate
        except Exception:  # noqa: BLE001
            continue
    return None, ""


def main() -> int:
    db = php_config()
    conn = connect(db)
    cur = conn.cursor()

    address = os.getenv("FISS_SYSTEM_ADDRESS", "").strip()
    private_key = os.getenv("FISS_SYSTEM_PRIVATE_KEY", "").strip()
    if not address or not private_key:
        log.error("缺少系统钱包地址或私钥")
        return 2
    dry_run = os.getenv("FISS_BURN_DRY_RUN", "1").strip() == "1"
    if read_setting(cur, "burn_sweep_enabled", "1") == "0":
        log.info("后台已关闭自动销毁，跳过")
        conn.close()
        return 0
    min_burn_setting = read_setting(cur, "burn_min_amount", "")
    min_burn = Decimal(min_burn_setting) if min_burn_setting else MIN_BURN

    w3, rpc = connect_web3()
    if w3 is None:
        log.error("RPC 连接失败，已尝试全部候选")
        return 3

    token = Web3.to_checksum_address(os.getenv("BSC_FISS_CONTRACT", FISS_FALLBACK))
    account = Web3.to_checksum_address(address)

    erc20 = w3.eth.contract(address=token, abi=[
        {"constant": True, "inputs": [{"name": "a", "type": "address"}],
         "name": "balanceOf", "outputs": [{"name": "", "type": "uint256"}], "type": "function"},
        {"constant": True, "inputs": [], "name": "symbol",
         "outputs": [{"name": "", "type": "string"}], "type": "function"},
        {"constant": True, "inputs": [], "name": "decimals",
         "outputs": [{"name": "", "type": "uint8"}], "type": "function"},
        {"constant": False, "inputs": [{"name": "to", "type": "address"}, {"name": "value", "type": "uint256"}],
         "name": "transfer", "outputs": [{"name": "", "type": "bool"}], "type": "function"},
    ])

    try:
        onchain_symbol = str(erc20.functions.symbol().call())
        onchain_decimals = int(erc20.functions.decimals().call())
    except Exception as exc:  # noqa: BLE001
        log.error("读取代币信息失败，拒绝执行：%s", exc)
        return 5
    if onchain_symbol.upper() != "FISS" or onchain_decimals != DECIMALS:
        log.error("合约校验失败：symbol=%s decimals=%s（只允许 FISS/18），已停止", onchain_symbol, onchain_decimals)
        return 5

    # 档位与 PHP 同源：staking_burn_tiers 表
    cur.execute("SELECT threshold_amount,min_stake_amount FROM staking_burn_tiers WHERE symbol='FISS' AND status=1 ORDER BY threshold_amount ASC")
    tiers = list(cur.fetchall())
    ratios = json.loads(read_setting(cur, "burn_ratios", '{"0":"100","1":"50","2":"25","3":"10"}') or "{}")
    baseline = Decimal(read_setting(cur, "burn_baseline", "0") or "0")
    mode = read_setting(cur, "burn_tier_mode", "auto")
    manual = Decimal(read_setting(cur, "burn_tier_manual_threshold", "0") or "0")
    if not tiers:
        log.warning("没有任何 FISS 销毁档位，比例将一直按 100%% 计算")

    burn_address = read_setting(cur, "burn_address", BURN_ADDRESS_DEFAULT).strip()
    if not burn_address.startswith("0x") or len(burn_address) != 42:
        burn_address = BURN_ADDRESS_DEFAULT
    burn_address = Web3.to_checksum_address(burn_address)
    dead_balance = Decimal(erc20.functions.balanceOf(burn_address).call()).scaleb(-DECIMALS)
    burned = manual if mode == "manual" else dead_balance - baseline
    ratio, threshold = tier_ratio(burned, tiers, ratios)

    balance = Decimal(erc20.functions.balanceOf(account).call()).scaleb(-DECIMALS)
    if balance < min_burn:
        log.info("钱包余额 %s FISS，未达最小销毁量 %s，跳过（当前比例 %s%%）", balance, min_burn, ratio)
        conn.close()
        return 0

    burn_amount = (balance * ratio / Decimal(100)).quantize(Decimal("0.000000000000000001"), rounding=ROUND_DOWN)
    if burn_amount <= 0:
        log.info("按 %s%% 计算销毁量不足最小单位，跳过", ratio)
        conn.close()
        return 0

    marker = "batch-%d-%s" % (int(time.time()), uuid.uuid4().hex[:8])
    if dry_run:
        log.info("[dry-run] 钱包余额 %s FISS，按 %s%% 应销毁 %s FISS，剩余 %s",
                 balance, ratio, burn_amount, balance - burn_amount)
        conn.close()
        return 0

    cur.execute(
        """INSERT INTO burn_sweeps
           (tx_in, from_address, amount_in, ratio_percent, burn_amount, tier_threshold,
            burned_total_before, status)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s)""",
        (marker, account, str(balance), str(ratio), str(burn_amount), str(threshold), str(burned), "pending"),
    )
    conn.commit()

    bnb = Decimal(w3.eth.get_balance(account)).scaleb(-18)
    if bnb < MIN_BNB:
        log.error("BNB 不足（%s），停止销毁", bnb)
        notify("⚠️ FISS 销毁暂停：系统钱包 BNB 不足（%s），请尽快充值 gas" % bnb)
        cur.execute("UPDATE burn_sweeps SET status='failed', error=%s WHERE tx_in=%s", ("BNB 不足", marker))
        conn.commit()
        conn.close()
        return 4

    try:
        tx = erc20.functions.transfer(
            burn_address, int(burn_amount * (Decimal(10) ** DECIMALS))
        ).build_transaction({
            "from": account,
            "nonce": w3.eth.get_transaction_count(account),
            "gas": 120000,
            "gasPrice": w3.eth.gas_price,
            "chainId": w3.eth.chain_id,
        })
        signed = w3.eth.account.sign_transaction(tx, private_key)
        raw = getattr(signed, "raw_transaction", None) or getattr(signed, "rawTransaction")
        sent = w3.eth.send_raw_transaction(raw)
        receipt = w3.eth.wait_for_transaction_receipt(sent, timeout=180)
        ok = int(receipt.get("status", 0)) == 1
        cur.execute("UPDATE burn_sweeps SET burn_tx=%s,status=%s WHERE tx_in=%s",
                    (sent.hex(), "burned" if ok else "failed", marker))
        conn.commit()
        if ok:
            log.info("已销毁 %s FISS（%s%%），剩余 %s，tx=%s", burn_amount, ratio, balance - burn_amount, sent.hex())
            notify(
                "🔥 FISS 已销毁\n"
                f"销毁数量：{burn_amount} FISS\n"
                f"当前比例：{ratio}%（累计销毁 {burned}）\n"
                f"钱包剩余：{balance - burn_amount} FISS\n"
                f"交易：{sent.hex()}"
            )
            try:
                fresh = Decimal(erc20.functions.balanceOf(burn_address).call()).scaleb(-DECIMALS)
                cur.execute(
                    "INSERT INTO app_settings (`key`,`value`) VALUES (%s,%s) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)",
                    ("burn_balance_FISS", json.dumps({"amount": format(fresh, "f"), "at": int(time.time())})),
                )
                conn.commit()
            except Exception:  # noqa: BLE001
                pass
        else:
            log.error("销毁交易回滚 tx=%s", sent.hex())
            return 6
    except Exception as exc:  # noqa: BLE001
        cur.execute("UPDATE burn_sweeps SET status='failed', error=%s WHERE tx_in=%s", (str(exc)[:250], marker))
        conn.commit()
        log.exception("销毁失败")
        notify("❌ FISS 销毁失败\n钱包余额：%s\n错误：%s" % (balance, str(exc)[:200]))
        return 7

    for name, addr in OTHER_TOKENS.items():
        try:
            other = w3.eth.contract(address=Web3.to_checksum_address(addr), abi=[{
                "constant": True, "inputs": [{"name": "a", "type": "address"}],
                "name": "balanceOf", "outputs": [{"name": "", "type": "uint256"}], "type": "function",
            }])
            other_balance = Decimal(other.functions.balanceOf(account).call()).scaleb(-DECIMALS)
            if other_balance > 0:
                log.warning("系统钱包里有 %s %s，本服务不会销毁它（只烧 FISS）", other_balance, name)
        except Exception:  # noqa: BLE001
            continue

    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
