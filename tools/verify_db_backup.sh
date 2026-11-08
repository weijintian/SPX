#!/usr/bin/env bash
# 恢复演练：把最新备份导入临时库，比对关键表行数，验证备份可用
set -uo pipefail
CONF=/etc/spxchat/db-backup.cnf
. /etc/spxchat/backup.env
LATEST=$(ls -t /var/backups/spx-db/spxchat-*.sql.gz 2>/dev/null | head -1)
[ -n "$LATEST" ] || { echo "没有找到备份文件"; exit 1; }
TEST_DB="spxchat_restore_probe"

echo "备份文件: $LATEST ($(numfmt --to=iec "$(stat -c %s "$LATEST")"))"

mysql_admin() { mysql "$@"; }   # 用 root socket 连接：应用账号没有建库权限

echo "--- 建临时库 $TEST_DB ---"
mysql_admin -e "DROP DATABASE IF EXISTS $TEST_DB; CREATE DATABASE $TEST_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

echo "--- 导入备份 ---"
START=$(date +%s)
if ! gzip -dc "$LATEST" | mysql_admin "$TEST_DB" 2>/tmp/restore-err.log; then
  echo "❌ 导入失败"; head -5 /tmp/restore-err.log; mysql_admin -e "DROP DATABASE IF EXISTS $TEST_DB"; exit 1
fi
echo "导入耗时 $(( $(date +%s) - START )) 秒"

echo
printf "%-26s %12s %12s\n" "表" "生产库" "恢复库"
echo "------------------------------------------------------"
ok=0; bad=0
for t in users wallet_assets wallet_transactions staking_records staking_plans recharge_records messages chat_group_messages app_releases; do
  a=$(mysql_admin -N -e "SELECT COUNT(*) FROM $DB_NAME.$t" 2>/dev/null)
  b=$(mysql_admin -N -e "SELECT COUNT(*) FROM $TEST_DB.$t" 2>/dev/null)
  if [ -n "$a" ] && [ "$a" = "$b" ]; then mark="✅"; ok=$((ok+1)); else mark="❌"; bad=$((bad+1)); fi
  printf "%-26s %12s %12s  %s\n" "$t" "${a:-?}" "${b:-?}" "$mark"
done

echo
echo "表数量: 生产 $(mysql_admin -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'") / 恢复 $(mysql_admin -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TEST_DB'")"
echo "校验通过 $ok 张，不一致 $bad 张"

mysql_admin -e "DROP DATABASE IF EXISTS $TEST_DB"
echo "临时库已删除"
notify() {
  local text="$1"
  local env=/etc/spxchat/telegram.env
  [ -f "$env" ] || return 0
  local token chat
  token=$(grep -m1 '^TELEGRAM_BOT_TOKEN=' "$env" | cut -d= -f2-)
  chat=$(grep -m1 '^TELEGRAM_CHAT_IDS=' "$env" | cut -d= -f2- | awk '{print $1}')
  [ -n "$token" ] && [ -n "$chat" ] || return 0
  curl -s -m 15 -X POST "https://api.telegram.org/bot${token}/sendMessage" \
    -d "chat_id=${chat}" --data-urlencode "text=${text}" >/dev/null 2>&1 || true
}

if [ "$bad" -eq 0 ]; then
  echo "✅ 恢复演练通过（$ok 张关键表行数一致）"
else
  echo "❌ 恢复演练发现问题（$bad 张表不一致）"
  notify "❌ SPX 数据库恢复演练失败\n备份文件: $(basename "$LATEST")\n不一致表: $bad 张\n请立即检查备份链路。"
fi
