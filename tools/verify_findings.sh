#!/usr/bin/env bash
# 核实自测里可疑的几项
set -uo pipefail
APP=/var/www/html/spxchat

echo "=== 1. /spxchat/backups/ 到底是什么 ==="
ls -la "$APP/backups" 2>/dev/null | head -8 || echo "  目录不存在"
echo -n "  HTTP 返回前 300 字节: "
curl -s -m 10 "https://spx001.top/spxchat/backups/" | head -c 300
echo

echo
echo "=== 2. PUT 请求实际被当成什么处理 ==="
curl -s -o /dev/null -w "  PUT health -> HTTP %{http_code}\n" -X PUT "https://spx001.top/spxchat/api.php?action=health"
curl -s -X PUT "https://spx001.top/spxchat/api.php?action=health" | head -c 120
echo

echo
echo "=== 3. app.update.history 返回什么（是否敏感）==="
curl -s -m 10 "https://spx001.top/spxchat/api.php?action=app.update.history" | head -c 260
echo

echo
echo "=== 4. CORS 头 ==="
curl -sI -m 10 "https://spx001.top/spxchat/api.php?action=health" | grep -i access-control | sed 's/^/  /' || echo "  无 CORS 头"

echo
echo "=== 5. 目录列表是否开启 ==="
for d in backups uploads tools; do
  printf "  /%s/ -> HTTP " "$d"
  curl -s -o /dev/null -w "%{http_code}\n" -m 10 "https://spx001.top/spxchat/$d/"
done

echo
echo "=== 6. 服务器版本头 ==="
curl -sI -m 10 "https://spx001.top/" | grep -i '^server' | sed 's/^/  /'
