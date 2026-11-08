#!/usr/bin/env bash
# 提升 WebSocket 与 MySQL 的连接上限
set -uo pipefail

echo "=== 1. WebSocket 服务：FD 上限 1024 -> 65535 ==="
UNIT=/etc/systemd/system/spxchat-ws.service
cp "$UNIT" "/root/spx-backups/spxchat-ws.service.bak-$(date +%s)"
if ! grep -q '^LimitNOFILE=' "$UNIT"; then
  sed -i '/^\[Service\]/a LimitNOFILE=65535' "$UNIT"
  echo "  已写入 LimitNOFILE=65535"
else
  echo "  已存在，跳过"
fi
systemctl daemon-reload
systemctl restart spxchat-ws
sleep 3
PID=$(ps -eo pid,cmd | grep 'ws-server.php' | grep -v grep | awk 'NR==1{print $1}')
if [ -n "${PID:-}" ]; then
  echo -n "  新进程 FD 上限: "
  grep 'Max open files' "/proc/$PID/limits" | awk '{print $4}'
else
  echo "  ⚠️ 未找到 ws 进程"
fi
systemctl is-active spxchat-ws | sed 's/^/  服务状态: /'

echo
echo "=== 2. MySQL 连接上限 151 -> 300 ==="
CONF=/etc/mysql/mysql.conf.d/mysqld.cnf
if ! grep -q '^max_connections' "$CONF"; then
  printf '\n# SPX: 提高并发连接上限（2026-11-02）\nmax_connections = 300\n' >> "$CONF"
  echo "  已写入配置文件（重启后生效）"
fi
mysql -e "SET GLOBAL max_connections=300;" 2>/dev/null
mysql -N -e "SHOW VARIABLES LIKE 'max_connections';" | sed 's/^/  /'

echo
echo "=== 3. 验证 WebSocket 可用 ==="
ss -tn state established '( sport = :8081 )' 2>/dev/null | tail -n +2 | wc -l | sed 's/^/  当前连接数: /'
curl -s -o /dev/null -w "  API HTTP %{http_code}\n" -m 10 "http://127.0.0.1/spxchat/api.php?action=health"
