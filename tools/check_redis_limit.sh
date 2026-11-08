#!/usr/bin/env bash
# 验证 Redis 限流计数 + 统计单请求 SQL 数
set -uo pipefail
echo "=== 1. 连续 5 次请求后 Redis 计数 ==="
for i in 1 2 3 4 5; do curl -s -o /dev/null "http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1"; done
for k in $(redis-cli --scan --pattern 'spx:traffic*' | head -5); do
  echo "  $k = $(redis-cli get "$k")  (TTL $(redis-cli ttl "$k")s)"
done

echo
echo "=== 2. 单请求 SQL 条数（general log）==="
LOG=/var/log/mysql/gen-trace.log
mysql -e "SET GLOBAL general_log_file='$LOG'; SET GLOBAL general_log=ON;" 2>/dev/null
: > "$LOG"
curl -s -o /dev/null "http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1"
sleep 0.3
mysql -e "SET GLOBAL general_log=OFF;" 2>/dev/null
awk '/Query|Execute/' "$LOG" | sed -E 's/^.*(Query|Execute)[[:space:]]+//' | grep -viE 'general_log|SET GLOBAL|^Quit$|^Connect$' | sed -E 's/[[:space:]]+/ /g' | cut -c1-110 | nl -ba
echo "  总条数: $(awk '/Query|Execute/' "$LOG" | wc -l)"
