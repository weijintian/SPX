#!/usr/bin/env bash
# WebSocket 服务承压能力摸底
set -uo pipefail
echo "=== systemd 单元 ==="
systemctl cat spxchat-ws 2>/dev/null | tail -18 | sed 's/^/  /'

echo
echo "=== 进程 ==="
ps -eo pid,nlwp,rss,etimes,cmd 2>/dev/null | grep 'ws-server' | grep -v grep | sed 's/^/  /'

PID=$(ps -eo pid,cmd | grep 'ws-server.php' | grep -v grep | awk 'NR==1{print $1}')
if [ -n "${PID:-}" ]; then
  echo
  echo "=== 进程 FD 上限（影响最大连接数）==="
  grep -E 'Max open files|Max processes' "/proc/$PID/limits" | sed 's/^/  /'
  echo "=== 当前已用 FD ==="
  ls "/proc/$PID/fd" 2>/dev/null | wc -l | sed 's/^/  /'
fi

echo
echo "=== 连接处理方式 ==="
grep -nE 'stream_select|socket_accept|pcntl_fork|EventLoop|Workerman|Swoole|mysql' /var/www/html/spxchat/ws-server.php 2>/dev/null | head -10 | sed 's/^/  /'

echo
echo "=== MySQL 连接上限 ==="
mysql -N -e "SHOW VARIABLES LIKE 'max_connections'; SHOW STATUS LIKE 'Max_used_connections';" 2>/dev/null | sed 's/^/  /'

echo
echo "=== 当前 WS 连接数 ==="
ss -tn state established '( sport = :8081 )' 2>/dev/null | tail -n +2 | wc -l | sed 's/^/  /'
