#!/usr/bin/env bash
# 验证 spx-api jail 真实链路：关闭 ignoreself → 制造 429 → 应自动封禁本机
set -uo pipefail
JAIL=/etc/fail2ban/jail.local
cp "$JAIL" /root/jail.local.bak2

echo "1) 临时关闭 ignoreself（仅本次测试）"
sed -i 's/^action   = iptables-multiport\[name=SPXAPI/ignoreself = false\naction   = iptables-multiport[name=SPXAPI/' "$JAIL"
grep -n 'ignoreself' "$JAIL" | sed 's/^/   /'
systemctl restart fail2ban
sleep 4

echo "2) 制造 429"
seq 1 500 | xargs -P 100 -I{} curl -s -o /dev/null -w "%{http_code}\n" -m 5 -H 'Host: spx001.top' "http://127.0.0.1/spxchat/api.php?action=health" 2>/dev/null | sort | uniq -c | awk '{printf "   HTTP %s: %s\n", $2, $1}'

echo "3) 等待检测（8 秒）"
sleep 8
fail2ban-client status spx-api | sed 's/^/   /'
iptables -S | grep -c 127.0.0.1 | awk '{print "   含 127.0.0.1 的封禁规则: "$1}'

echo "4) 恢复配置并清理"
cp /root/jail.local.bak2 "$JAIL"
systemctl restart fail2ban
sleep 3
fail2ban-client unban --all >/dev/null 2>&1 || true
iptables -S | grep -c 127.0.0.1 | awk '{print "   清理后规则数: "$1}'
fail2ban-client status spx-api | grep -E 'Total failed|Currently banned' | sed 's/^/   /'
