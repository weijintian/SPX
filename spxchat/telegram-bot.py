#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SPX Chat 独立 Telegram 通知机器人。

该脚本只读取数据库并发送通知，不参与充值、提现或质押业务事务。
部署位置：/etc/spxchat/telegram-bot.py
配置文件：/etc/spxchat/telegram.env
"""

import json
import logging
import os
import subprocess
import tempfile
import time
from pathlib import Path

import pymysql
import requests


ENV_PATH = Path("/etc/spxchat/telegram.env")
STATE_PATH = Path("/etc/spxchat/telegram-bot.state.json")
CONFIG_PATH = Path(os.getenv("SPXCHAT_CONFIG_PATH", "/var/www/html/spxchat/config.php"))
POLL_SECONDS = max(5, int(os.getenv("TELEGRAM_POLL_SECONDS", "10")))

logging.basicConfig(
    level="INFO",
    format="%(asctime)s %(levelname)s %(message)s",
)
logger = logging.getLogger("spxchat-telegram-bot")


def load_env_file():
    if not ENV_PATH.is_file():
        return
    for raw_line in ENV_PATH.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


load_env_file()


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


def connect_db():
    return pymysql.connect(
        host=DB_CONFIG["host"],
        port=int(DB_CONFIG.get("port", 3306)),
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        database=DB_CONFIG["name"],
        charset=DB_CONFIG.get("charset", "utf8mb4"),
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


def recipients():
    result = set()
    for chat_id in os.getenv("TELEGRAM_CHAT_IDS", "").replace(",", " ").split():
        if chat_id.lstrip("-").isdigit():
            result.add(chat_id)
    return sorted(result)


def send_message(message):
    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    if not token or not message:
        logger.warning("未配置 TELEGRAM_BOT_TOKEN，跳过通知")
        return
    chat_ids = recipients()
    if not chat_ids:
        logger.warning("未配置 TELEGRAM_CHAT_IDS，跳过通知")
        return
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    for chat_id in chat_ids:
        try:
            response = requests.post(
                url,
                json={"chat_id": chat_id, "text": message, "disable_web_page_preview": True},
                timeout=8,
            )
            payload = response.json()
            if response.status_code >= 400 or not payload.get("ok"):
                logger.warning("Telegram 发送失败 chat_id=%s status=%s", chat_id, response.status_code)
        except Exception as exc:
            logger.warning("Telegram 发送异常 chat_id=%s: %s", chat_id, exc)


def load_state():
    if not STATE_PATH.is_file():
        return None
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("读取通知状态失败，将重新初始化: %s", exc)
        return None


def save_state(state):
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix="telegram-bot-", suffix=".json", dir=STATE_PATH.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as output:
            json.dump(state, output, ensure_ascii=False, separators=(",", ":"))
        os.replace(temporary, STATE_PATH)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def scalar(cursor, sql, params=(), default=0):
    cursor.execute(sql, params)
    row = cursor.fetchone()
    if not row:
        return default
    return next(iter(row.values()))


def initialize_state(connection):
    state = {
        "users": 0,
        "staking": 0,
        "dividends": 0,
        "recharges": 0,
        "recharge_audits": 0,
        "withdrawals": 0,
        "withdrawal_statuses": {},
        "admin_audits": 0,
        "admin_logins": {},
        "admin_failures": {},
        "heartbeats": {},
    }
    with connection.cursor() as cursor:
        state["users"] = int(scalar(cursor, "SELECT COALESCE(MAX(id),0) AS value FROM users"))
        state["staking"] = int(scalar(cursor, "SELECT COALESCE(MAX(id),0) AS value FROM staking_records"))
        state["dividends"] = int(scalar(cursor, "SELECT COALESCE(MAX(id),0) AS value FROM staking_dividends"))
        state["recharges"] = int(scalar(cursor, "SELECT COALESCE(MAX(id),0) AS value FROM recharge_records"))
        state["recharge_audits"] = int(scalar(cursor, "SELECT COALESCE(MAX(id),0) AS value FROM recharge_audit_logs"))
        state["withdrawals"] = int(scalar(cursor, "SELECT COALESCE(MAX(id),0) AS value FROM wallet_transactions WHERE type='withdraw'"))
        state["admin_audits"] = int(scalar(cursor, "SELECT COALESCE(MAX(id),0) AS value FROM admin_audit_logs"))
        cursor.execute("SELECT id,status FROM wallet_transactions WHERE type='withdraw' ORDER BY id DESC LIMIT 1000")
        state["withdrawal_statuses"] = {str(row["id"]): row["status"] for row in cursor.fetchall()}
        cursor.execute("SELECT id,last_login_at FROM admin_users")
        state["admin_logins"] = {str(row["id"]): str(row["last_login_at"] or "") for row in cursor.fetchall()}
        cursor.execute("SELECT ip_address,username,failed_attempts FROM admin_login_attempts")
        state["admin_failures"] = {f"{row['ip_address']}:{row['username']}": int(row["failed_attempts"]) for row in cursor.fetchall()}
        cursor.execute("SELECT service_name,status FROM service_heartbeats")
        state["heartbeats"] = {row["service_name"]: row["status"] for row in cursor.fetchall()}
    return state


def poll_users(connection, state):
    with connection.cursor() as cursor:
        cursor.execute("SELECT id,username,email FROM users WHERE id>%s ORDER BY id LIMIT 100", (state["users"],))
        rows = cursor.fetchall()
    for row in rows:
        send_message(f"👤 新用户注册成功\n用户：{row['username']}\n用户 ID：#{row['id']}\n邮箱：{row.get('email') or '无'}")
        state["users"] = max(state["users"], int(row["id"]))


def poll_staking(connection, state):
    with connection.cursor() as cursor:
        cursor.execute("SELECT s.id,s.user_id,s.symbol,s.amount,s.plan_days,s.apy,u.username FROM staking_records s JOIN users u ON u.id=s.user_id WHERE s.id>%s ORDER BY s.id LIMIT 100", (state["staking"],))
        created = cursor.fetchall()
        cursor.execute("SELECT d.id,d.staking_id,d.user_id,d.period_no,d.amount,s.symbol FROM staking_dividends d JOIN staking_records s ON s.id=d.staking_id WHERE d.id>%s ORDER BY d.id LIMIT 100", (state["dividends"],))
        dividends = cursor.fetchall()
        cursor.execute("SELECT s.id,s.user_id,s.symbol,s.amount,u.username FROM staking_records s JOIN users u ON u.id=s.user_id WHERE s.status='redeemed' AND s.id<=%s", (state["staking"],))
        redeemed = cursor.fetchall()
    for row in created:
        send_message(f"📌 用户创建质押\n用户：{row['username']} (#{row['user_id']})\n质押 ID：#{row['id']}\n金额：{row['amount']} {row['symbol']}\n期限：{row['plan_days']} 天\n年化：{row['apy']}%")
        state["staking"] = max(state["staking"], int(row["id"]))
    for row in dividends:
        send_message(f"📈 质押分红到账\n用户 ID：#{row['user_id']}\n质押 ID：#{row['staking_id']}\n周期：第 {row['period_no']} 期\n分红：{row['amount']} {row['symbol']}")
        state["dividends"] = max(state["dividends"], int(row["id"]))
    redeemed_ids = state.setdefault("redeemed", [])
    for row in redeemed:
        if int(row["id"]) in redeemed_ids:
            continue
        send_message(f"🔓 用户赎回质押\n用户：{row['username']} (#{row['user_id']})\n质押 ID：#{row['id']}\n本金：{row['amount']} {row['symbol']}")
        redeemed_ids.append(int(row["id"]))
    state["redeemed"] = redeemed_ids[-1000:]


def poll_recharges(connection, state):
    with connection.cursor() as cursor:
        cursor.execute("SELECT id,user_id,currency,amount,tx_hash FROM recharge_records WHERE id>%s ORDER BY id LIMIT 100", (state["recharges"],))
        submitted = cursor.fetchall()
        cursor.execute("SELECT id,record_id,user_id,amount,token,status,tx_hash,error_message FROM recharge_audit_logs WHERE id>%s ORDER BY id LIMIT 100", (state["recharge_audits"],))
        results = cursor.fetchall()
    for row in submitted:
        send_message(f"💰 用户提交充值\n用户 ID：#{row['user_id']}\n资产：{row['currency']}\n金额：{row['amount']}\n交易哈希：{row['tx_hash']}\n充值记录：#{row['id']}")
        state["recharges"] = max(state["recharges"], int(row["id"]))
    for row in results:
        icon = "✅" if row["status"] == "completed" else "❌"
        label = "充值到账" if row["status"] == "completed" else "充值验证失败"
        suffix = "" if row["status"] == "completed" else f"\n原因：{row.get('error_message') or '未知原因'}"
        send_message(f"{icon} {label}\n用户 ID：#{row['user_id']}\n资产：{row['token']}\n金额：{row['amount']}\n交易哈希：{row['tx_hash']}{suffix}")
        state["recharge_audits"] = max(state["recharge_audits"], int(row["id"]))


def poll_withdrawals(connection, state):
    with connection.cursor() as cursor:
        cursor.execute("SELECT t.id,t.user_id,t.symbol,t.amount,t.fee_amount,t.address,t.status,u.username FROM wallet_transactions t JOIN users u ON u.id=t.user_id WHERE t.type='withdraw' AND t.id>%s ORDER BY t.id LIMIT 100", (state["withdrawals"],))
        created = cursor.fetchall()
        cursor.execute("SELECT id,user_id,symbol,amount,fee_amount,address,status,tx_hash,error_message FROM wallet_transactions WHERE type='withdraw' AND id>=GREATEST(1,%s-1000)", (state["withdrawals"],))
        current = cursor.fetchall()
    for row in created:
        send_message(f"📤 用户提交提现\n用户：{row['username']} (#{row['user_id']})\n资产：{row['symbol']}\n金额：{row['amount']}\n手续费：{row['fee_amount']}\n地址：{row['address']}\n提现流水：#{row['id']}")
        state["withdrawals"] = max(state["withdrawals"], int(row["id"]))
    statuses = state.setdefault("withdrawal_statuses", {})
    for row in current:
        key = str(row["id"])
        previous = statuses.get(key)
        current_status = row["status"]
        if previous is not None and previous != current_status and current_status != "processing":
            icon = "✅" if current_status == "completed" else ("❌" if current_status == "failed" else "🧾")
            send_message(f"{icon} 提现状态变更\n提现流水：#{row['id']}\n用户 ID：#{row['user_id']}\n资产：{row['symbol']}\n金额：{row['amount']}\n状态：{previous} → {current_status}\n交易哈希：{row.get('tx_hash') or '无'}\n原因：{row.get('error_message') or '无'}")
        statuses[key] = current_status
    state["withdrawal_statuses"] = dict(list(statuses.items())[-1000:])


def poll_admin_security(connection, state):
    with connection.cursor() as cursor:
        cursor.execute("SELECT id,username,last_login_at FROM admin_users")
        admins = cursor.fetchall()
        cursor.execute("SELECT ip_address,username,failed_attempts,locked_until FROM admin_login_attempts")
        failures = cursor.fetchall()
    logins = state.setdefault("admin_logins", {})
    for row in admins:
        key = str(row["id"])
        current = str(row["last_login_at"] or "")
        if current and logins.get(key) and logins[key] != current:
            send_message(f"🔐 后台管理员登录成功\n账号：{row['username']}\n时间：{current}")
        logins[key] = current
    previous_failures = state.setdefault("admin_failures", {})
    for row in failures:
        key = f"{row['ip_address']}:{row['username']}"
        count = int(row["failed_attempts"])
        previous = int(previous_failures.get(key, 0))
        if count > previous and count in (5, 10):
            send_message(f"⚠️ 后台登录安全告警\n账号：{row['username']}\nIP：{row['ip_address']}\n连续失败：{count} 次\n锁定时间：{row['locked_until'] or '无'}")
        previous_failures[key] = count


def poll_admin_audits(connection, state):
    with connection.cursor() as cursor:
        cursor.execute("SELECT id,admin_username,action,target_type,target_id,reason,before_data,after_data FROM admin_audit_logs WHERE id>%s ORDER BY id LIMIT 100", (state["admin_audits"],))
        rows = cursor.fetchall()
    actions = {"wallet.status.update", "recharge.manual.complete", "withdraw.approve", "withdraw.reject", "withdraw.batch_approve", "withdraw.retry"}
    for row in rows:
        if row["action"] in actions:
            send_message(f"🧾 后台资金操作\n操作：{row['action']}\n目标：{row['target_type']} #{row['target_id'] or '-'}\n管理员：{row['admin_username']}\n原因：{row['reason'] or '无'}")
        state["admin_audits"] = max(state["admin_audits"], int(row["id"]))


def run_once(connection, state):
    poll_users(connection, state)
    poll_staking(connection, state)
    poll_recharges(connection, state)
    poll_withdrawals(connection, state)
    poll_admin_security(connection, state)
    poll_admin_audits(connection, state)


def main():
    connection = connect_db()
    try:
        state = load_state()
        if state is None or not state.get("initialized"):
            state = initialize_state(connection)
            state["initialized"] = True
            save_state(state)
        send_message("🟢 Telegram 通知机器人已开启\n监控：注册、充值、提现、质押、后台安全事件\n启动时间：" + time.strftime("%Y-%m-%d %H:%M:%S"))
        while True:
            try:
                run_once(connection, state)
                save_state(state)
            except Exception:
                logger.exception("通知轮询失败")
                try:
                    connection.close()
                except Exception:
                    pass
                time.sleep(2)
                connection = connect_db()
            time.sleep(POLL_SECONDS)
    finally:
        connection.close()


if __name__ == "__main__":
    main()
