<?php
declare(strict_types=1);
/**
 * 清理种子脚本产生的测试数据（按用户清理 FISS 质押、分红、流水、余额与期数额度）。
 *
 * 用法：php cleanup_staking_test.php <user_id>
 */

$userId = (int) ($argv[1] ?? 0);
if ($userId <= 0) { fwrite(STDERR, "用法: php cleanup_staking_test.php <user_id>\n"); exit(1); }

$config = require '/var/www/html/spxchat/config.php';
$d = $config['db'];
$pdo = new PDO(
    sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $d['host'], $d['port'], $d['name']),
    $d['user'], $d['password'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
);

$pdo->beginTransaction();
$ids = $pdo->query("SELECT id FROM staking_records WHERE user_id=$userId")->fetchAll(PDO::FETCH_COLUMN);
foreach ($ids as $id) {
    $pdo->prepare('DELETE FROM staking_dividends WHERE staking_id=?')->execute([$id]);
    $pdo->prepare('DELETE FROM referral_rewards WHERE staking_id=?')->execute([$id]);
    $pdo->prepare('DELETE FROM staking_records WHERE id=?')->execute([$id]);
}
$pdo->prepare("DELETE FROM wallet_transactions WHERE user_id=$userId")->execute();
$pdo->prepare("UPDATE wallet_assets SET balance=0, stakeable_balance=0 WHERE user_id=?")->execute([$userId]);
$pdo->exec("UPDATE staking_phases SET reserved_quota=0, paid_quota=0 WHERE symbol='FISS'");
$pdo->commit();

printf("已清理用户 %d：质押记录 %d 条，钱包已归零，FISS 期数额度已重置\n", $userId, count($ids));
