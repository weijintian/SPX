#!/usr/bin/env bash
# 严格 A/B：同时关闭 nginx 限流与应用层限流，复测 50 并发
set -uo pipefail
SITE=/etc/nginx/sites-enabled/default
APP=/var/www/html/spxchat

cp "$SITE" /root/site-with-limits.bak
sed -i 's/^\([[:space:]]*\)limit_req zone=spxapi/\1# limit_req zone=spxapi/; s/^\([[:space:]]*\)limit_req zone=spxglobal/\1# limit_req zone=spxglobal/; s/^\([[:space:]]*\)limit_conn spxconn/\1# limit_conn spxconn/' "$SITE"
nginx -t >/dev/null 2>&1 && nginx -s reload && echo "nginx 限流已关闭"
php "$APP/tools/switch_shadow.php" off
rm -f /tmp/spxchat-traffic-guard.json
sleep 1

redis-cli --scan --pattern 'spx:traffic*' | while IFS= read -r k; do redis-cli del "$k" >/dev/null; done

echo
echo "=== 50 并发 / 15 秒（缓存优化后）==="
python3 /root/loadtest.py --path '/spxchat/api.php?action=app.update.check&version_code=1' --concurrency 50 --seconds 15 --label "after-cache"

echo
echo "=== 恢复 ==="
cp /root/site-with-limits.bak "$SITE"
nginx -t >/dev/null 2>&1 && nginx -s reload && echo "nginx 限流已恢复"
php -r '$c=require "/var/www/html/spxchat/config.php";$d=$c["db"];$p=new PDO(sprintf("mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4",$d["host"],$d["port"],$d["name"]),$d["user"],$d["password"]);
$p->prepare("INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)")->execute(["traffic_guard_config", json_encode(["shadow"=>false,"enabled"=>true])]);'
rm -f /tmp/spxchat-traffic-guard.json
redis-cli --scan --pattern 'spx:traffic*' | while IFS= read -r k; do redis-cli del "$k" >/dev/null; done
sleep 2
echo "应用层已恢复拦截模式"
curl -s -o /dev/null -w "恢复后 HTTP %{http_code} %{time_total}s\n" "http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1"
