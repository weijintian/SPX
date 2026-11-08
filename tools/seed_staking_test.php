<?php
declare(strict_types=1);
/**
 * 测试数据种子：给指定账号发 FISS 并创建一笔质押。
 * 用途：上线前演示 / 验收复测。清理见 cleanup_staking_test.php。
 *
 * 用法：php seed_staking_test.php <user_id> [fiss_amount] [stake_amount]
 */

$userId = (int) ($argv[1] ?? 49);
$fissAmount = (string) ($argv[2] ?? '200000');
$stakeAmount = (string) ($argv[3] ?? '100000');

$config = require '/var/www/html/spxchat/config.php';
$d = $config['db'];
$pdo = new PDO(
    sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $d['host'], $d['port'], $d['name']),
    $d['user'], $d['password'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
);

$user = $pdo->prepare('SELECT id,username,spx_id FROM users WHERE id=? LIMIT 1');
$user->execute([$userId]);
$account = $user->fetch();
if (!$account) { fwrite(STDERR, "用户不存在: $userId\n"); exit(1); }

$pdo->prepare("INSERT IGNORE INTO wallet_assets (user_id,symbol,chain,balance,stakeable_balance) VALUES (?,'FISS','BSC',0,0)")->execute([$userId]);
$pdo->prepare("UPDATE wallet_assets SET balance=?, stakeable_balance=? WHERE user_id=? AND symbol='FISS'")
    ->execute([$fissAmount, $fissAmount, $userId]);

$plan = $pdo->query("SELECT id,plan_days,apy,minimum_amount FROM staking_plans WHERE symbol='FISS' AND status=1 ORDER BY plan_days LIMIT 1")->fetch();
if (!$plan) { fwrite(STDERR, "没有启用的 FISS 方案\n"); exit(1); }
if ((float) $stakeAmount < (float) $plan['minimum_amount']) {
    fwrite(STDERR, "质押金额低于方案最低 {$plan['minimum_amount']}\n"); exit(1);
}

$phaseId = (int) $pdo->query("SELECT id FROM staking_phases WHERE symbol='FISS' AND status=1 ORDER BY phase_no DESC LIMIT 1")->fetchColumn();
$pdo->prepare('INSERT INTO staking_records (user_id,symbol,phase_id,plan_days,amount,apy,status) VALUES (?,?,?,?,?,?,?)')
    ->execute([$userId, 'FISS', $phaseId ?: null, (int) $plan['plan_days'], $stakeAmount, (string) $plan['apy'], 'active']);
$recordId = (int) $pdo->lastInsertId();

$pdo->prepare("UPDATE wallet_assets SET balance=balance-?, stakeable_balance=stakeable_balance-? WHERE user_id=? AND symbol='FISS'")
    ->execute([$stakeAmount, $stakeAmount, $userId]);
$pdo->prepare("INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,stakeable_amount,status) VALUES (?,'FISS','BSC','staking',?,?,'completed')")
    ->execute([$userId, $stakeAmount, $stakeAmount]);
if ($phaseId > 0) {
    $expected = $pdo->prepare('SELECT CAST(ROUND(CAST(? AS DECIMAL(36,18))*CAST(? AS DECIMAL(36,18))*CAST(? AS DECIMAL(36,18))/36500,18) AS DECIMAL(36,18))');
    $expected->execute([$stakeAmount, (string) $plan['apy'], (int) $plan['plan_days']]);
    $pdo->prepare('UPDATE staking_phases SET reserved_quota=reserved_quota+? WHERE id=?')->execute([(string) $expected->fetchColumn(), $phaseId]);
}

printf("已为用户 %s（%s）创建 FISS 质押：%s 质押 %s\n", $account['username'], $account['spx_id'], $recordId, $stakeAmount);
printf("FISS 总发放 %s，当前余额 %s\n", $fissAmount,
    $pdo->query("SELECT balance FROM wallet_assets WHERE user_id=$userId AND symbol='FISS'")->fetchColumn());
