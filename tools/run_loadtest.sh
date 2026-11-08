#!/usr/bin/env bash
# 容量压测：临时解除限流 → 阶梯加压 → 恢复限流
set -uo pipefail
SITE=/etc/nginx/sites-enabled/default
BAK=/root/loadtest-site.bak
APP=/var/www/html/spxchat
API_PATH='/spxchat/api.php?action=app.update.check&version_code=1'
HEALTH_PATH='/spxchat/api.php?action=health'

echo "===== [1] 基线 ====="
uptime
echo "php-fpm 进程数: $(pgrep -c -f 'php-fpm: pool' || echo 0)"
mysql -N -e "SHOW GLOBAL STATUS LIKE 'Threads_connected'"

echo
echo "===== [2] 临时解除限流 ====="
cp "$SITE" "$BAK"
sed -i 's/^\([[:space:]]*\)limit_req zone=spxapi/\1# limit_req zone=spxapi/; s/^\([[:space:]]*\)limit_conn spxconn/\1# limit_conn spxconn/' "$SITE"
nginx -t 2>&1 | tail -1
nginx -s reload
php "$APP/tools/switch_shadow.php" off

echo
echo "===== [3] 阶梯压测（每次 15 秒）====="
/root/loadtest_monitor.sh /root/loadtest-monitor.log 140 &
MON=$!
sleep 2

for c in 50 100 200 400; do
  echo "--- 并发 $c ---"
  python3 /root/loadtest.py --path "$API_PATH" --concurrency "$c" --seconds 15 --label "update-check@${c}"
  sleep 2
done

echo "--- 并发 200（health，对比用）---"
python3 /root/loadtest.py --path "$HEALTH_PATH" --concurrency 200 --seconds 10 --label "health@200"

echo
echo "===== [4] 资源峰值 ====="
awk '{for(i=1;i<=NF;i++){if($i ~ /^cpu=/){gsub(/cpu=|%/,"",$i); if($i+0>mc)mc=$i+0} if($i ~ /^load=/){gsub(/load=/,"",$i); if($i+0>ml)ml=$i+0} if($i ~ /^php=/){gsub(/php=/,"",$i); if($i+0>mp)mp=$i+0} if($i ~ /^mysql_conn=/){gsub(/mysql_conn=/,"",$i); if($i+0>mq)mq=$i+0} if($i ~ /^mem=/){gsub(/mem=|MB/,"",$i); if($i+0>mm)mm=$i+0}}} END{printf "  CPU 峰值: %s%%\n  load1 峰值: %s\n  php-fpm 峰值: %s\n  MySQL 连接峰值: %s\n  内存峰值: %s MB\n", mc, ml, mp, mq, mm}' /root/loadtest-monitor.log

echo
echo "===== [5] 恢复限流 ====="
cp "$BAK" "$SITE"
nginx -t 2>&1 | tail -1
nginx -s reload
php -r '$c=require "/var/www/html/spxchat/config.php";$d=$c["db"];$p=new PDO(sprintf("mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4",$d["host"],$d["port"],$d["name"]),$d["user"],$d["password"]);
$p->prepare("INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)")->execute(["traffic_guard_config", json_encode(["shadow"=>false,"enabled"=>true])]);'
echo "已恢复拦截模式"

echo
echo "===== [6] 恢复后可用性 ====="
for i in 1 2 3; do curl -s -o /dev/null -w "  第 $i 次 HTTP %{http_code} %{time_total}s\n" -m 10 "https://spx001.top/spxchat/api.php?action=app.update.check&version_code=1"; done
kill $MON 2>/dev/null || true
wait $MON 2>/dev/null || true
