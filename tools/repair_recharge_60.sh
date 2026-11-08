#!/usr/bin/env bash
# 修复记录 #60：走真实验证路径补入账（含审计日志），随后恢复时效限制
set -uo pipefail
APP=/var/www/html/spxchat
VERIFIER=$APP/recharge_verifier.py
BAK=/root/spx-backups/recharge-fix-20261102/recharge_verifier.py.repair
HASH=0x0df1b28944ae867162a6b3f67e4ed33982c42f1c57fc40b3da2f92c195ddb21c

echo "===== 1. 备份并暂停验证服务 ====="
cp -a "$VERIFIER" "$BAK" 2>/dev/null || true
systemctl stop recharge-verifier
sleep 1
systemctl is-active recharge-verifier || true

echo
echo "===== 2. 临时放宽链上交易时效（600s → 86400s）====="
python3 - "$VERIFIER" <<'PY'
import io, sys
p = sys.argv[1]
s = io.open(p, encoding="utf-8").read()
s = s.replace("MAX_TX_AGE_SECONDS = 600", "MAX_TX_AGE_SECONDS = 86400", 1)
io.open(p, "w", encoding="utf-8").write(s)
print("  已放宽")
PY

echo
echo "===== 3. 重置记录为 pending ====="
php -r '
$c = require "/var/www/html/spxchat/config.php"; $d = $c["db"];
$p = new PDO(sprintf("mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4", $d["host"], $d["port"], $d["name"]), $d["user"], $d["password"]);
$p->beginTransaction();
$p->prepare("UPDATE recharge_records SET status=\"pending\", error_message=NULL, created_at=NOW() WHERE id=60 AND tx_hash=?")->execute(["'"$HASH"'"]);
$p->prepare("UPDATE wallet_transactions SET status=\"pending\" WHERE tx_hash=? AND type=\"deposit\"")->execute(["'"$HASH"'"]);
$p->commit();
$r = $p->query("SELECT id,status,error_message FROM recharge_records WHERE id=60")->fetch(PDO::FETCH_ASSOC);
echo "  记录 #60 状态: " . $r["status"] . "\n";
$w = $p->query("SELECT id,status FROM wallet_transactions WHERE tx_hash=\"'"$HASH"'\" AND type=\"deposit\"")->fetch(PDO::FETCH_ASSOC);
echo "  流水 #" . $w["id"] . " 状态: " . $w["status"] . "\n";
'

echo
echo "===== 4. 执行一次真实验证 ====="
cd "$APP"
sudo -u www-data python3 "$VERIFIER" --once 2>&1 | tail -20

echo
echo "===== 5. 核对结果 ====="
php -r '
$c = require "/var/www/html/spxchat/config.php"; $d = $c["db"];
$p = new PDO(sprintf("mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4", $d["host"], $d["port"], $d["name"]), $d["user"], $d["password"], [PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
$r = $p->query("SELECT id,status,amount,error_message FROM recharge_records WHERE id=60")->fetch();
printf("  充值记录 #60: status=%s amount=%s err=%s\n", $r["status"], $r["amount"], $r["error_message"] ?? "-");
$b = $p->query("SELECT balance,stakeable_balance FROM wallet_assets WHERE user_id=59 AND symbol=\"SPX001\"")->fetch();
printf("  用户 #59 SPX001: 余额=%s 可质押=%s\n", $b["balance"], $b["stakeable_balance"]);
$t = $p->query("SELECT id,type,amount,status FROM wallet_transactions WHERE tx_hash='"$HASH"' AND type=\"deposit\"")->fetch();
printf("  钱包流水 #%s: %s %s status=%s\n", $t["id"], $t["type"], $t["amount"], $t["status"]);
$log = $p->query("SELECT status,from_address,to_address,confirmations FROM recharge_audit_logs WHERE tx_hash='"$HASH"' ORDER BY id DESC LIMIT 1")->fetch();
printf("  审计日志: status=%s from=%s to=%s 确认数=%s\n", $log["status"], $log["from_address"], $log["to_address"], $log["confirmations"]);
'

echo
echo "===== 6. 恢复时效限制并重启服务 ====="
cp -a "$BAK" "$VERIFIER"
python3 - "$VERIFIER" <<'PY'
import io, sys
s = io.open(sys.argv[1], encoding="utf-8").read()
print("  当前时效设置:", [l for l in s.splitlines() if l.startswith("MAX_TX_AGE_SECONDS")][0])
PY
systemctl start recharge-verifier
sleep 3
systemctl is-active recharge-verifier | sed 's/^/  服务状态: /'
tail -n 3 /var/log/spx-recharge-verifier.log 2>/dev/null || true
