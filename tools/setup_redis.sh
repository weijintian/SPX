#!/usr/bin/env bash
# Redis 作为纯缓存：只监听本机、限制内存、LRU 淘汰、关闭持久化
set -uo pipefail
CONF=/etc/redis/redis.conf
cp "$CONF" "/root/spx-backups/redis.conf.bak-$(date +%s)" 2>/dev/null || true

sed -i 's/^# *maxmemory .*/maxmemory 256mb/' "$CONF"
sed -i 's/^# *maxmemory-policy .*/maxmemory-policy allkeys-lru/' "$CONF"
grep -q '^maxmemory ' "$CONF" || echo 'maxmemory 256mb' >> "$CONF"
grep -q '^maxmemory-policy ' "$CONF" || echo 'maxmemory-policy allkeys-lru' >> "$CONF"
grep -q '^bind 127.0.0.1' "$CONF" || sed -i 's/^bind .*/bind 127.0.0.1 ::1/' "$CONF"

# 纯缓存：不需要落盘
sed -i 's/^save [0-9]/# save /' "$CONF"
grep -q '^appendonly yes' "$CONF" && sed -i 's/^appendonly yes/appendonly no/' "$CONF"

# 危险命令禁用
if ! grep -q 'SPX: 禁用危险命令' "$CONF"; then
  printf '\n# SPX: 禁用危险命令（纯缓存用途）\nrename-command FLUSHALL ""\nrename-command FLUSHDB ""\nrename-command CONFIG ""\nrename-command KEYS ""\n' >> "$CONF"
fi

systemctl enable --now redis-server >/dev/null 2>&1
systemctl restart redis-server
sleep 2
systemctl is-active redis-server
redis-cli ping
redis-cli info memory | grep -E '^used_memory_human|^maxmemory_human|^maxmemory_policy' | sed 's/^/  /'
redis-cli config get maxmemory 2>/dev/null | tail -1 | sed 's/^/  maxmemory: /'
