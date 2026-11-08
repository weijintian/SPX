#!/usr/bin/env bash
# 部署后验证：Redis 限流计数 + 单请求 SQL 条数
set -uo pipefail

echo "=== 1. Redis 限流计数 ==="
redis-cli --scan --pattern 'spx:traffic*' | while IFS= read -r k; do redis-cli del "$k" >/dev/null; done
for i in 1 2 3; do curl -s -o /dev/null "http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1"; done
redis-cli --scan --pattern 'spx:traffic*' | while IFS= read -r k; do
  printf '  %-48s 计数=%s TTL=%ss\n' "$k" "$(redis-cli get "$k")" "$(redis-cli ttl "$k")"
done

echo
echo "=== 2. 单请求 SQL 条数（每次用新日志文件，避免截断偏移）==="
LOG=/var/log/mysql/gen-$(date +%s).log
mysql -e "SET GLOBAL general_log_file='$LOG'; SET GLOBAL general_log=ON;" 2>/dev/null
sleep 0.3
curl -s -o /dev/null "http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1"
sleep 0.3
mysql -e "SET GLOBAL general_log=OFF;" 2>/dev/null
awk '/Query|Execute/' "$LOG" 2>/dev/null | sed -E 's/^.*(Query|Execute)[[:space:]]+//' | grep -viE 'general_log|SET GLOBAL|^Quit$|^Connect$' | sed -E 's/[[:space:]]+/ /g' | cut -c1-105 | nl -ba
echo "  总条数: $(awk '/Query|Execute/' "$LOG" 2>/dev/null | wc -l)"
rm -f "$LOG"

echo
echo "=== 3. 接口可用性 ==="
for i in 1 2 3; do curl -s -o /dev/null -w "  HTTP %{http_code} %{time_total}s\n" -m 10 "https://spx001.top/spxchat/api.php?action=app.update.check&version_code=1"; done
