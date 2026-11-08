#!/usr/bin/env bash
# 基于 nginx 日志统计真实使用强度：每 IP 每分钟请求数分布 + 接口构成
set -uo pipefail
LOG=/var/log/nginx/access.log

echo "=== 1. 最近 1 小时总请求数 ==="
HOUR=$(date '+%d/%b/%Y:%H')
TOTAL=$(grep -c "\[$HOUR:" "$LOG" 2>/dev/null || echo 0)
echo "  $TOTAL 次 / 小时 = $(awk -v t="$TOTAL" 'BEGIN{printf "%.2f", t/3600}') 次/秒"

echo
echo "=== 2. 有请求的独立 IP 数（最近 1 小时）==="
IPCOUNT=$(grep "\[$HOUR:" "$LOG" | awk '{print $1}' | sort -u | wc -l)
echo "  $IPCOUNT 个"

echo
echo "=== 3. 每个 IP 每小时请求数分布 ==="
grep "\[$HOUR:" "$LOG" | awk '{print $1}' | sort | uniq -c | sort -rn > /tmp/ipcount.txt
awk '{print $1}' /tmp/ipcount.txt | sort -n | awk '
{a[NR]=$1}
END{
  if (NR==0) {print "  无数据"; exit}
  printf "  中位数: %d 次/小时（%.1f 次/分钟）\n", a[int(NR/2)+1], a[int(NR/2)+1]/60
  printf "  平均:   %.0f 次/小时\n", s/NR
  printf "  最大:   %d 次/小时（%.1f 次/分钟）\n", a[NR], a[NR]/60
}'
echo "  Top 5 高频 IP:"
head -5 /tmp/ipcount.txt | awk '{printf "    %-18s %6d 次/小时\n", $2, $1}'

echo
echo "=== 4. 接口构成（最近 20000 条）==="
tail -n 20000 "$LOG" | grep -oE 'action=[a-zA-Z._]+' | sort | uniq -c | sort -rn | head -8 | awk '{printf "  %-30s %6d\n", $2, $1}'

echo
echo "=== 5. 应用内部指标（Redis 命中率 / MySQL 查询）==="
redis-cli info stats 2>/dev/null | grep -E 'keyspace_hits|keyspace_misses' | sed 's/^/  /'
mysql -N -e "SHOW GLOBAL STATUS LIKE 'Questions'" 2>/dev/null | sed 's/^/  /'
rm -f /tmp/ipcount.txt
