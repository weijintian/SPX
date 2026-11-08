#!/usr/bin/env bash
# 用「伪造来源 IP + polling 后端」验证 fail2ban 检测与封禁链路
set -uo pipefail
FAKE=/tmp/f2b-fake.log
: > "$FAKE"
chmod 644 "$FAKE"

cat > /etc/fail2ban/jail.d/zz-test.conf <<EOF
[spx-test]
enabled  = true
filter   = spx-api
logpath  = $FAKE
backend  = polling
maxretry = 10
findtime = 300
bantime  = 120
action   = iptables-multiport[name=SPXTEST, port="http,https", protocol=tcp]
EOF

systemctl restart fail2ban
sleep 4

NOW=$(date '+%d/%b/%Y:%H:%M:%S %z')
for i in $(seq 1 20); do
  echo "198.51.100.9 - - [$NOW] \"GET /spxchat/api.php?action=health HTTP/1.1\" 429 162 \"-\" \"test\"" >> "$FAKE"
done
sleep 8

echo "--- 测试 jail 状态 ---"
fail2ban-client status spx-test 2>&1 | sed 's/^/  /'
echo "--- 防火墙规则 ---"
iptables -S | grep 198.51.100.9 | sed 's/^/  /' || echo "  （无）"

rm -f /etc/fail2ban/jail.d/zz-test.conf "$FAKE"
systemctl restart fail2ban
sleep 2
echo "--- 清理完成 ---"
fail2ban-client status | tail -2 | sed 's/^/  /'
