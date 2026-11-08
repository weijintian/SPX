#!/bin/bash
# 部署核对：Python 服务进程是否加载了磁盘上的最新代码。
# 用法：bash tools/check_service_freshness.sh
# 退出码：0 全部最新；1 有服务比其代码文件旧（需要 restart）。

set -uo pipefail
APP=${APP:-/var/www/html/spxchat}

declare -A SERVICES=(
  ["recharge-verifier"]="$APP/recharge_verifier.py"
  ["withdrawal-verifier"]="$APP/withdrawal_verifier.py"
)

stale=0
printf "%-24s %-20s %-20s %s\n" "服务" "启动时间" "文件修改时间" "状态"

for name in "${!SERVICES[@]}"; do
  file="${SERVICES[$name]}"
  if [ ! -f "$file" ]; then
    printf "%-24s %-20s %-20s %s\n" "$name" "-" "-" "缺少文件"
    stale=1
    continue
  fi
  started=$(systemctl show -p ActiveEnterTimestamp --value "$name" 2>/dev/null)
  started_ts=$(date -d "$started" +%s 2>/dev/null || echo 0)
  file_ts=$(stat -c %Y "$file")
  active=$(systemctl is-active "$name" 2>/dev/null)
  if [ "$active" != "active" ]; then
    state="未运行"
    stale=1
  elif [ "$started_ts" -lt "$file_ts" ]; then
    state="⚠️ 代码比进程新，需 restart"
    stale=1
  else
    state="✅ 已加载最新代码"
  fi
  printf "%-24s %-20s %-20s %s\n" "$name" "${started:-未知}" "$(date -d @"$file_ts" '+%Y-%m-%d %H:%M:%S')" "$state"
done

echo
if [ "$stale" -eq 0 ]; then
  echo "全部服务已加载最新代码。"
else
  echo "存在需要重启的服务，执行："
  for name in "${!SERVICES[@]}"; do echo "  systemctl restart $name"; done
fi
exit "$stale"
