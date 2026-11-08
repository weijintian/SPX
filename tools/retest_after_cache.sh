#!/usr/bin/env bash
# 缓存优化后的单点复测：与优化前 50 并发基线对比
set -uo pipefail
SITE=/etc/nginx/sites-enabled/default
BAK=/root/loadtest-site.bak
[ -f "$BAK" ] || cp "$SITE" "$BAK"

cp "$SITE" /root/site-with-limits.bak
sed -i 's/^\([[:space:]]*\)limit_req zone=spxapi/\1# limit_req zone=spxapi/; s/^\([[:space:]]*\)limit_req zone=spxglobal/\1# limit_req zone=spxglobal/; s/^\([[:space:]]*\)limit_conn spxconn/\1# limit_conn spxconn/' "$SITE"
nginx -t >/dev/null 2>&1 && nginx -s reload

echo "=== 50 并发 / 15 秒（优化后）==="
python3 /root/loadtest.py --path '/spxchat/api.php?action=app.update.check&version_code=1' --concurrency 50 --seconds 15 --label "after-cache"

cp /root/site-with-limits.bak "$SITE"
nginx -t >/dev/null 2>&1 && nginx -s reload
rm -f /root/site-with-limits.bak
echo "限流已恢复"
curl -s -o /dev/null -w "恢复后 HTTP %{http_code}\n" "http://127.0.0.1/spxchat/api.php?action=app.update.check&version_code=1"
