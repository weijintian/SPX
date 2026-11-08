#!/usr/bin/env bash
# 压测期间的资源采样：每秒记录一次 CPU / 内存 / php-fpm / MySQL 连接 / nginx 连接
set -uo pipefail
OUT="${1:-/root/loadtest-monitor.log}"
DURATION="${2:-120}"
: > "$OUT"
END=$(( $(date +%s) + DURATION ))
while [ "$(date +%s)" -lt "$END" ]; do
  ts=$(date +%H:%M:%S)
  load=$(awk '{print $1}' /proc/loadavg)
  cpu_idle=$(LC_ALL=C top -bn1 | awk '/Cpu\(s\)/ {print $8; exit}')
  cpu_use=$(awk -v idle="${cpu_idle:-100}" 'BEGIN{printf "%.1f", 100-idle}')
  mem_used=$(free -m | awk '/Mem:/ {print $3}')
  php=$(pgrep -c -f 'php-fpm: pool' 2>/dev/null || echo 0)
  mysql_conn=$(mysql -N -e "SHOW GLOBAL STATUS LIKE 'Threads_connected'" 2>/dev/null | awk '{print $2}')
  mysql_qps=$(mysql -N -e "SHOW GLOBAL STATUS LIKE 'Queries'" 2>/dev/null | awk '{print $2}')
  nginx_est=$(ss -tan state established '( sport = :443 or sport = :80 )' 2>/dev/null | tail -n +2 | wc -l)
  echo "$ts load=$load cpu=${cpu_use}% mem=${mem_used}MB php=$php mysql_conn=${mysql_conn:-?} mysql_queries=${mysql_qps:-?} nginx_conn=$nginx_est" >> "$OUT"
  sleep 1
done
