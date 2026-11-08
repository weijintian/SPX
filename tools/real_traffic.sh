#!/usr/bin/env bash
# 真实用户请求强度（排除本机压测流量），统计最近 24 小时
set -uo pipefail
LOG=/var/log/nginx/access.log
OUT=/tmp/real_traffic.txt
: > "$OUT"

# 最近 24 小时、排除本机与服务器自身 IP
awk -v cutoff="$(date -d '24 hours ago' '+%d/%b/%Y:%H:%M:%S')" '
{
  # 时间戳形如 [02/Nov/2026:17:45:00
  ts = substr($4, 2)
  gsub(/\[/, "", ts)
  if (ts >= cutoff && $1 != "127.0.0.1" && $1 != "70.39.201.191") print $1
}' "$LOG" > "$OUT"

TOTAL=$(wc -l < "$OUT")
IPS=$(sort -u "$OUT" | wc -l)
echo "=== 最近 24 小时（排除本机）==="
echo "  总请求: $TOTAL 次"
echo "  独立 IP: $IPS 个"
[ "$IPS" -gt 0 ] && awk -v t="$TOTAL" -v i="$IPS" 'BEGIN{printf "  平均每 IP: %.0f 次/24h = %.2f 次/分钟\n", t/i, t/i/1440}'

echo
echo "=== 每 IP 请求数分布（24h）==="
sort "$OUT" | uniq -c | sort -n > /tmp/ip24.txt
head -3 /tmp/ip24.txt | awk '{printf "  低: %5d 次  %s\n", $1, $2}'
tail -5 /tmp/ip24.txt | awk '{printf "  高: %5d 次  %s\n", $1, $2}'
awk '{a[NR]=$1} END{printf "  中位数: %d 次/24h = %.2f 次/分钟\n", a[int(NR/2)+1], a[int(NR/2)+1]/1440}' /tmp/ip24.txt

echo
echo "=== 接口构成（最近 24h，排除本机）==="
awk -v cutoff="$(date -d '24 hours ago' '+%d/%b/%Y:%H:%M:%S')" '
{
  ts = substr($4, 2); gsub(/\[/, "", ts)
  if (ts >= cutoff && $1 != "127.0.0.1" && $1 != "70.39.201.191") print $0
}' "$LOG" | grep -oE 'action=[a-zA-Z._]+' | sort | uniq -c | sort -rn | head -10 | awk '{printf "  %-28s %6d\n", $2, $1}'

rm -f "$OUT" /tmp/ip24.txt
