#!/usr/bin/env bash
# 用伪造日志验证 fail2ban 的「检测 → 封禁」链路（不依赖真实来源 IP）
set -uo pipefail
FAKE=/tmp/f2b-fake.log
: > "$FAKE"
chmod 644 "$FAKE"

cat > /etc/fail2ban/jail.d/zz-test.conf <<EOF
[spx-test]
enabled = true
filter = spx-api
logpath = $FAKE
maxretry = 30
findtime = 300
bantime = 120
action = iptables-multiport[name=SPXTEST, port="http,https", protocol=tcp]
EOF

systemctl restart fail2ban
sleep 4
# fail2ban 启动时从文件末尾开始读，所以要在启动后再写入日志
NOW=$(date '+%d/%b/%Y:%H:%M:%S %z')
for i in $(seq 1 40); do
  echo "198.51.100.9 - - [$NOW] \"GET /spxchat/api.php?action=health HTTP/1.1\" 429 162 \"-\" \"test\"" >> "$FAKE"
done
sleep 6
echo "--- 测试 jail 状态 ---"
fail2ban-client status spx-test 2>&1 | sed 's/^/  /'
echo "--- 防火墙里是否有 198.51.100.9 ---"
iptables -S | grep -c 198.51.100.9 | awk '{print "  规则数: "$1}'

echo "--- 清理测试 jail ---"
rm -f /etc/fail2ban/jail.d/zz-test.conf "$FAKE"
systemctl restart fail2ban
sleep 2
iptables -S | grep -c 198.51.100.9 | awk '{print "  清理后规则数: "$1}'
fail2ban-client status | tail -2 | sed 's/^/  /'
