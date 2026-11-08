#!/usr/bin/env bash
# 抓取单个请求产生的全部 SQL
set -uo pipefail
LOG=/tmp/mysql-gen.log
mysql -e "SET GLOBAL general_log_file='$LOG'; SET GLOBAL general_log=ON;" 2>/dev/null
: > "$LOG" 2>/dev/null || true
curl -s -o /dev/null "http://127.0.0.1/spxchat/api.php?action=health"
sleep 1
mysql -e "SET GLOBAL general_log=OFF;" 2>/dev/null
echo "=== 本次请求的 SQL（去掉连接/系统语句）==="
grep -E 'Query|Execute' "$LOG" | grep -viE 'general_log|SET GLOBAL|Quit|Connect|select @@' | sed -E 's/^.*Query\s+//; s/^.*Execute\s+//' | head -40
echo
echo "=== 总条数 ==="
grep -cE 'Query|Execute' "$LOG" | awk '{print "  "$1}'
