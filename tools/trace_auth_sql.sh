#!/usr/bin/env bash
# 抓取「已登录请求」的完整 SQL 列表
set -uo pipefail
ACTION="${1:-wallet}"
LOG=/var/log/mysql/gen-trace.log

mysql -e "SET GLOBAL general_log_file='$LOG'; SET GLOBAL general_log=ON;" 2>/dev/null
sleep 0.3
: > "$LOG" 2>/dev/null || true

TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"username":"qa1101c","password":"Qa123456"}' \
  "http://127.0.0.1/spxchat/api.php?action=auth.login" | python3 -c 'import json,sys;print((json.load(sys.stdin).get("data") or {}).get("token",""))')

if [ -z "$TOKEN" ]; then echo "登录失败"; mysql -e "SET GLOBAL general_log=OFF;"; exit 1; fi

curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" "http://127.0.0.1/spxchat/api.php?action=$ACTION"
sleep 0.5
mysql -e "SET GLOBAL general_log=OFF;" 2>/dev/null

echo "=== action=$ACTION 的 SQL ==="
awk '/Query|Execute/ {print}' "$LOG" | sed -E 's/^.*(Query|Execute)[[:space:]]+//' | grep -viE 'general_log|SET GLOBAL|^Quit$|^Connect$' | sed -E 's/[[:space:]]+/ /g' | cut -c1-150 | nl -ba | head -40
echo "=== 总条数 ==="
awk '/Query|Execute/' "$LOG" | wc -l
