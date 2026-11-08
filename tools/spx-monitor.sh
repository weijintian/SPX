#!/usr/bin/env bash
# SPX IM 服务器巡检：服务 / 资源 / 备份 / 证书 / 限流命中，异常推送 Telegram
# 用法：spx-monitor.sh [--self-test]
set -uo pipefail

TG_ENV=/etc/spxchat/telegram.env
STATE_DIR=/var/lib/spx-monitor
STATE_FILE="$STATE_DIR/state.json"
mkdir -p "$STATE_DIR"

ALERTS=()
WARNINGS=()

notify() {
  local text="$1"
  [ -f "$TG_ENV" ] || return 0
  local token chat
  token=$(grep -m1 '^TELEGRAM_BOT_TOKEN=' "$TG_ENV" | cut -d= -f2-)
  chat=$(grep -m1 '^TELEGRAM_CHAT_IDS=' "$TG_ENV" | cut -d= -f2- | awk '{print $1}')
  [ -n "$token" ] && [ -n "$chat" ] || return 0
  curl -s -m 15 -X POST "https://api.telegram.org/bot${token}/sendMessage" \
    -d "chat_id=${chat}" --data-urlencode "text=${text}" >/dev/null 2>&1 || true
}

# ---------- 1. 关键服务 ----------
for unit in nginx php8.3-fpm mysql redis-server spxchat-ws recharge-verifier withdrawal-verifier fail2ban telegram-bot; do
  if ! systemctl is-active --quiet "$unit"; then
    ALERTS+=("服务未运行: $unit")
  fi
done

for timer in spx-chat-scheduler.timer spxchat-staking-settler.timer spx-db-backup.timer spx-db-restore-check.timer; do
  if ! systemctl is-active --quiet "$timer"; then
    ALERTS+=("定时器未启用: $timer")
  fi
done

# ---------- 2. 资源 ----------
DISK=$(df --output=pcent / | tail -1 | tr -dc '0-9')
[ "${DISK:-0}" -ge 85 ] && ALERTS+=("磁盘使用率 ${DISK}%（≥85%）") || true
[ "${DISK:-0}" -ge 75 ] && [ "${DISK:-0}" -lt 85 ] && WARNINGS+=("磁盘使用率 ${DISK}%") || true

MEM_AVAIL=$(free -m | awk '/Mem:/ {print $7}')
[ "${MEM_AVAIL:-9999}" -lt 500 ] && ALERTS+=("可用内存仅 ${MEM_AVAIL} MB") || true

LOAD1=$(awk '{print $1}' /proc/loadavg)
CORES=$(nproc)
HIGH_LOAD=$(awk -v l="$LOAD1" -v c="$CORES" 'BEGIN{printf "%d", (l > c*1.5)}')
[ "$HIGH_LOAD" = "1" ] && ALERTS+=("负载过高: load1=${LOAD1} / ${CORES} 核") || true

# ---------- 3. 备份新鲜度 ----------
LATEST_BAK=$(ls -t /var/backups/spx-db/spxchat-*.sql.gz 2>/dev/null | head -1)
if [ -z "$LATEST_BAK" ]; then
  ALERTS+=("找不到数据库备份文件")
else
  AGE_H=$(( ( $(date +%s) - $(stat -c %Y "$LATEST_BAK") ) / 3600 ))
  [ "$AGE_H" -gt 26 ] && ALERTS+=("数据库备份已 ${AGE_H} 小时未更新（应每日）") || true
  SIZE_KB=$(( $(stat -c %s "$LATEST_BAK") / 1024 ))
  [ "$SIZE_KB" -lt 50 ] && ALERTS+=("最新备份过小: ${SIZE_KB} KB") || true
fi

# ---------- 3.4 WebSocket 连接数（单进程上限约 1000~3000）----------
WS_CONN=$(ss -tn state established '( sport = :8081 )' 2>/dev/null | tail -n +2 | wc -l)
WS_CONN=$(echo "$WS_CONN" | tr -d ' ')
[ "${WS_CONN:-0}" -ge 900 ] && ALERTS+=("WebSocket 连接 ${WS_CONN} 个，接近单进程上限（约 1000），需要改多进程架构") || true
[ "${WS_CONN:-0}" -ge 700 ] && [ "${WS_CONN:-0}" -lt 900 ] && WARNINGS+=("WebSocket 连接 ${WS_CONN} 个，已到单进程上限的 70%") || true

# ---------- 3.5 Redis 健康 ----------
REDIS_PONG=$(timeout 2 redis-cli ping 2>/dev/null || echo "")
[ "$REDIS_PONG" != "PONG" ] && ALERTS+=("Redis 无响应（缓存会降级为直连数据库）") || true
REDIS_MEM=$(timeout 2 redis-cli info memory 2>/dev/null | awk -F: '/used_memory_human/ {print $2}' | tr -d '\r')
if [ -n "$REDIS_MEM" ]; then
  MEM_MB=$(timeout 2 redis-cli info memory 2>/dev/null | awk -F: '/used_memory[^_]/ {print int($2/1048576)}' | tr -d '\r')
  [ "${MEM_MB:-0}" -gt 230 ] && WARNINGS+=("Redis 内存接近上限: ${REDIS_MEM}") || true
fi

# ---------- 3.6 提现热钱包余额（不足会导致提现失败退款）----------
WALLET_OUT=$(timeout 25 python3 - <<'PY' 2>/dev/null
import json, urllib.request
RPC = "https://bsc-dataseed1.binance.org"
HOT = "0xFec25630f06d967C525FD02759a05f1c55f3E1Fb"
TOKENS = {
    "USDT": "0x55d398326f99059ff775485246999027b3197955",
    "SPX001": "0x0E920821464625cf661A4dceDEb6406E1B48FFFf",
    "FISS": "0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a",
}
def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(RPC, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())["result"]
try:
    print("BNB=" + str(int(rpc("eth_getBalance", [HOT, "latest"]), 16) / 1e18))
    for sym, addr in TOKENS.items():
        data = "0x70a08231" + HOT[2:].rjust(64, "0")
        print(sym + "=" + str(int(rpc("eth_call", [{"to": addr, "data": data}, "latest"]), 16) / 1e18))
except Exception:
    pass
PY
)
if [ -n "$WALLET_OUT" ]; then
  while IFS='=' read -r key val; do
    [ -z "$key" ] && continue
    case "$key" in
      BNB)
        awk -v v="$val" 'BEGIN{exit !(v < 0.005)}' && ALERTS+=("热钱包 BNB 仅 ${val}，gas 可能不足") || true ;;
      FISS)
        awk -v v="$val" 'BEGIN{exit !(v < 10000)}' && ALERTS+=("热钱包 FISS 仅 ${val}，FISS 提现会失败退款，请充值") || true ;;
      SPX001)
        awk -v v="$val" 'BEGIN{exit !(v < 10000)}' && ALERTS+=("热钱包 SPX001 仅 ${val}，提现可能失败") || true ;;
    esac
  done <<< "$WALLET_OUT"
else
  WARNINGS+=("无法读取热钱包余额（RPC 异常）")
fi

# ---------- 4. SSL 证书 ----------
CERT=/etc/letsencrypt/live/spx001.top/fullchain.pem
if [ -r "$CERT" ]; then
  DAYS=$(( ( $(date -d "$(openssl x509 -enddate -noout -in "$CERT" | cut -d= -f2)" +%s) - $(date +%s) ) / 86400 ))
  [ "$DAYS" -lt 14 ] && ALERTS+=("SSL 证书 ${DAYS} 天后过期") || true
else
  WARNINGS+=("找不到证书文件 $CERT")
fi

# ---------- 5. 限流与错误率（最近 5 分钟）----------
if [ -r /var/log/nginx/access.log ]; then
  RECENT=$(tail -n 5000 /var/log/nginx/access.log)
  E429=$(printf '%s\n' "$RECENT" | grep -c '" 429 ')
  # 只统计真正的服务端错误；503 是我们自己 limit_conn 的预期结果，429 是限流
  E5XX=$(printf '%s\n' "$RECENT" | grep -cE '" 50[012] ')
  [ "${E429:-0}" -gt 200 ] && WARNINGS+=("近期 429 命中 ${E429} 次（可能有人在刷接口）") || true
  [ "${E5XX:-0}" -gt 100 ] && ALERTS+=("近期 5xx 错误 ${E5XX} 次（最近 5000 行窗口内）") || true
fi

# ---------- 6. 接口可用性 ----------
HTTP=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1" 2>/dev/null)
[ "$HTTP" != "200" ] && ALERTS+=("API 健康检查返回 HTTP ${HTTP}") || true

# ---------- 输出与告警（同类告警 30 分钟内不重复）----------
if [ "${1:-}" = "--self-test" ]; then
  notify "✅ SPX 监控告警通道测试
主机: $(hostname -s)
时间: $(date '+%Y-%m-%d %H:%M:%S')
如果你收到这条消息，说明告警链路正常。"
  echo "已发送测试消息"
  exit 0
fi

STAMP=$(date '+%Y-%m-%d %H:%M:%S')
if [ ${#ALERTS[@]} -gt 0 ]; then
  KEY=$(printf '%s' "${ALERTS[*]}" | md5sum | cut -c1-12)
  LAST=""
  [ -f "$STATE_FILE" ] && LAST=$(cat "$STATE_FILE")
  if [ "$LAST" != "$KEY" ]; then
    BODY="🚨 SPX 服务器告警（$(hostname -s)）
时间: $STAMP

$(printf '• %s\n' "${ALERTS[@]}")"
    [ ${#WARNINGS[@]} -gt 0 ] && BODY+="
提醒:
$(printf '• %s\n' "${WARNINGS[@]}")"
    notify "$BODY"
    printf '%s' "$KEY" > "$STATE_FILE"
    echo "[$STAMP] 告警已发送: ${ALERTS[*]}"
  else
    echo "[$STAMP] 同类告警已发送过，跳过"
  fi
  exit 1
fi

printf '' > "$STATE_FILE"
echo "[$STAMP] 全部正常（磁盘 ${DISK}%，可用内存 ${MEM_AVAIL}MB，load1 ${LOAD1}，WS ${WS_CONN}，API ${HTTP}）"
[ ${#WARNINGS[@]} -gt 0 ] && printf '  提醒: %s\n' "${WARNINGS[@]}"
exit 0
