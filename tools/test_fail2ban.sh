#!/usr/bin/env bash
# fail2ban 端到端验证：制造 429 → 确认自动封禁 → 解封并恢复配置
set -uo pipefail
JAIL=/etc/fail2ban/jail.local
cp "$JAIL" /root/jail.local.bak

echo "1) 临时把 127.0.0.1 从 ignoreip 移除（仅测试用）"
sed -i 's/^ignoreip = .*/ignoreip = ::1 10.0.0.0\/8/' "$JAIL"
systemctl restart fail2ban
sleep 3

echo "2) 制造 429（500 请求 / 100 并发打 health 接口）"
seq 1 500 | xargs -P 100 -I{} curl -s -o /dev/null -w "%{http_code}\n" -m 5 -H 'Host: spx001.top' "http://127.0.0.1/spxchat/api.php?action=health" 2>/dev/null | sort | uniq -c | awk '{printf "   HTTP %s: %s\n", $2, $1}'

echo "3) 等待 fail2ban 处理（8 秒）"
sleep 8
fail2ban-client status spx-api | sed 's/^/   /'

echo "4) 检查防火墙规则"
iptables -S | grep -c 127.0.0.1 | awk '{print "   含 127.0.0.1 的规则数: "$1}'

echo "5) 恢复配置并解封"
cp /root/jail.local.bak "$JAIL"
systemctl restart fail2ban
sleep 3
fail2ban-client status spx-api | grep -E 'Currently banned|Total banned' | sed 's/^/   /'
iptables -S | grep -c 127.0.0.1 | awk '{print "   恢复后含 127.0.0.1 的规则数: "$1}'
