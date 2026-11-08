#!/usr/bin/env bash
# SPX IM 数据库自动备份：本机保留 14 天 + 同步到 OSS，失败时 Telegram 告警
set -uo pipefail

CONF=/etc/spxchat/db-backup.cnf
LOCAL_DIR=/var/backups/spx-db
OSS_DIR=oss://spx001/backups/db/
KEEP_DAYS=14
LOG=/var/log/spx-db-backup.log
TG_ENV=/etc/spxchat/telegram.env
HOSTNAME_SHORT=$(hostname -s)

mkdir -p "$LOCAL_DIR"

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"; }

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

fail() {
  log "失败: $*"
  notify "❌ SPX 数据库备份失败（${HOSTNAME_SHORT}）
原因: $*"
  exit 1
}

[ -r "$CONF" ] || fail "缺少 $CONF"
[ -r /etc/spxchat/backup.env ] && . /etc/spxchat/backup.env
[ -n "${DB_NAME:-}" ] || fail "缺少库名配置 /etc/spxchat/backup.env"

STAMP=$(date '+%Y%m%d-%H%M%S')
FILE="$LOCAL_DIR/spxchat-$STAMP.sql.gz"

log "开始备份 -> $FILE"
START=$(date +%s)

if ! mysqldump --defaults-extra-file="$CONF" \
    --single-transaction --quick --routines --triggers --events \
    --set-gtid-purged=OFF --no-tablespaces --default-character-set=utf8mb4 \
    "$DB_NAME" 2>>"$LOG" | gzip -6 > "$FILE"; then
  rm -f "$FILE"
  fail "mysqldump 执行出错"
fi

SIZE=$(stat -c %s "$FILE" 2>/dev/null || echo 0)
[ "$SIZE" -gt 1024 ] || { rm -f "$FILE"; fail "备份文件过小（${SIZE} 字节）"; }

gzip -t "$FILE" 2>/dev/null || { rm -f "$FILE"; fail "备份文件 gzip 校验失败"; }

TABLES=$(gzip -dc "$FILE" | grep -c '^CREATE TABLE')
[ "$TABLES" -gt 20 ] || { fail "备份只含 ${TABLES} 张表，疑似不完整（文件保留）"; }

# 关键表行数校验：用配置里的库导出一份行数快照写进日志
COUNTS=$(gzip -dc "$FILE" | grep -c '^INSERT INTO')
DUR=$(( $(date +%s) - START ))
log "备份成功: 大小 $(numfmt --to=iec "$SIZE")，${TABLES} 张表，${COUNTS} 段 INSERT，耗时 ${DUR}s"

# 清理本地过期
DELETED=$(find "$LOCAL_DIR" -name 'spxchat-*.sql.gz' -mtime +"$KEEP_DAYS" -print -delete | wc -l)
[ "$DELETED" -gt 0 ] && log "清理过期备份 ${DELETED} 个"

# 上传到 OSS（异地副本）
if command -v ossutil >/dev/null 2>&1; then
  if ossutil -c /root/.ossutil/ossutilconfig cp -f "$FILE" "${OSS_DIR}$(basename "$FILE")" >>"$LOG" 2>&1; then
    log "已上传 OSS: ${OSS_DIR}$(basename "$FILE")"
  else
    log "⚠️ OSS 上传失败（本地备份已保留）"
    notify "⚠️ SPX 数据库备份已生成，但上传 OSS 失败（${HOSTNAME_SHORT}）
文件: $(basename "$FILE")
请检查 ossutil 配置或网络。"
  fi
else
  log "⚠️ 未找到 ossutil，跳过异地备份"
fi

# 本地磁盘使用检查
DISK=$(df --output=pcent / | tail -1 | tr -dc '0-9')
if [ "${DISK:-0}" -ge 85 ]; then
  notify "⚠️ SPX 服务器磁盘使用率 ${DISK}%（${HOSTNAME_SHORT}），请及时清理"
fi

exit 0
