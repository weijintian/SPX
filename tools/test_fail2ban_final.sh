#!/usr/bin/env bash
# 最终验证：从 [DEFAULT] 移除本机 IP → 制造 429 → 应自动封禁
set -uo pipefail
JAIL=/etc/fail2ban/jail.local
cp "$JAIL" /root/jail.local.bak3

echo "1) 修改 ignoreip（移除 127.0.0.1/8）"
sed -i 's|^ignoreip = .*|ignoreip = ::1 10.0.0.0/8|' "$JAIL"
grep '^ignoreip' "$JAIL" | sed 's/^/   /'
systemctl restart fail2ban
sleep 4

echo "2) 制造 429（500 请求 / 100 并发）"
seq 1 500 | xargs -P 100 -I{} curl -s -o /dev/null -w "%{http_code}\n" -m 5 -H 'Host: spx001.top' "http://127.0.0.1/spxchat/api.php?action=health" 2>/dev/null | sort | uniq -c | awk '{printf "   HTTP %s: %s\n", $2, $1}'

echo "3) 等待 fail2ban 检测（10 秒）"
sleep 10
fail2ban-client status spx-api | sed 's/^/   /'
iptables -S | grep 127.0.0.1 | sed 's/^/   规则: /'

echo "4) 恢复"
cp /root/jail.local.bak3 "$JAIL"
systemctl restart fail2ban
sleep 3
fail2ban-client unban --all >/dev/null 2>&1 || true
iptables -S | grep -c 127.0.0.1 | awk '{print "   清理后规则数: "$1}'
