#!/usr/bin/env bash
# 验证 Redis 故障时业务自动降级（不应报错）
set -uo pipefail
URL="http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1"

echo "1) 正常状态"
curl -s -o /dev/null -w "   HTTP %{http_code}  耗时 %{time_total}s\n" -m 10 "$URL"

echo "2) 停止 Redis"
systemctl stop redis-server
sleep 1
systemctl is-active redis-server | sed 's/^/   redis: /'

echo "3) Redis 不可用时请求（应仍为 200，只慢一点）"
for i in 1 2 3; do
  curl -s -o /dev/null -w "   HTTP %{http_code}  耗时 %{time_total}s\n" -m 10 "$URL"
done

echo "4) 恢复 Redis"
systemctl start redis-server
sleep 2
systemctl is-active redis-server | sed 's/^/   redis: /'
curl -s -o /dev/null -w "   HTTP %{http_code}  耗时 %{time_total}s\n" -m 10 "$URL"

echo "5) 登录 + 钱包（降级恢复后功能正常）"
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"username":"qa1101c","password":"Qa123456"}' "http://127.0.0.1/spxchat/api.php?action=auth.login" | python3 -c 'import json,sys;print((json.load(sys.stdin).get("data") or {}).get("token",""))')
if [ -n "$TOKEN" ]; then
  curl -s -o /dev/null -w "   wallet HTTP %{http_code}\n" -H "Authorization: Bearer $TOKEN" "http://127.0.0.1/spxchat/api.php?action=wallet"
else
  echo "   登录失败"
fi
