<?php
declare(strict_types=1);

/**
 * Automatic withdrawal approval policy.
 *
 * A withdrawal is auto-approved only when every condition holds:
 *   - the policy switch is on,
 *   - the requested amount (before the 1% fee) is BELOW the ceiling,
 *   - the asset is on the auto-approval whitelist (SPX001 by default),
 *   - the account has fewer than `daily_limit` withdrawals in the last 24 hours
 *     (cancelled/rejected rows are not counted, they never left the platform).
 *
 * Everything else stays `pending` for a human decision in the admin console.
 * Tuning without a deploy: write one JSON row into app_settings, e.g.
 *   INSERT INTO app_settings (`key`,`value`) VALUES ('withdraw_auto_policy',
 *     '{"enabled":"1","max_amount":"10000","daily_limit":"2","symbols":"SPX001"}')
 *   ON DUPLICATE KEY UPDATE `value`=VALUES(`value`);
 */

const WITHDRAW_AUTO_POLICY_DEFAULTS = [
    'enabled' => '1',
    'max_amount' => '10000',
    'daily_limit' => '2',
    'symbols' => 'SPX001',
    // 按币种覆盖上限（代币单价差异极大，统一上限没有意义）
    'max_amount_by_symbol' => '{}',
    // 24 小时内累计可自动通过的金额上限，防止拆单绕过单笔门槛
    'daily_amount_limit' => '20000',
];

function withdrawalAutoPolicy(PDO $pdo): array
{
    $statement = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
    $statement->execute(['withdraw_auto_policy']);
    $decoded = json_decode((string) ($statement->fetchColumn() ?: ''), true);
    $stored = is_array($decoded) ? $decoded : [];
    $config = array_merge(WITHDRAW_AUTO_POLICY_DEFAULTS, array_intersect_key($stored, WITHDRAW_AUTO_POLICY_DEFAULTS));
    $symbols = [];
    foreach (explode(',', (string) $config['symbols']) as $symbol) {
        $symbol = strtoupper(trim($symbol));
        if ($symbol !== '') $symbols[] = $symbol;
    }
    $enabled = $config['enabled'];
    // The ceiling comes from app_settings, so a malformed value must degrade to the default
    // instead of making bccomp() throw a ValueError and 500 the withdrawal endpoint.
    $maxAmount = trim((string) $config['max_amount']);
    if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $maxAmount)) {
        $maxAmount = WITHDRAW_AUTO_POLICY_DEFAULTS['max_amount'];
    }
    $dailyAmountLimit = trim((string) $config['daily_amount_limit']);
    if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $dailyAmountLimit)) {
        $dailyAmountLimit = WITHDRAW_AUTO_POLICY_DEFAULTS['daily_amount_limit'];
    }
    $bySymbol = [];
    $decodedBySymbol = is_string($config['max_amount_by_symbol'])
        ? json_decode($config['max_amount_by_symbol'], true)
        : $config['max_amount_by_symbol'];
    if (is_array($decodedBySymbol)) {
        foreach ($decodedBySymbol as $symbolKey => $limit) {
            $symbolKey = strtoupper(trim((string) $symbolKey));
            $limit = trim((string) $limit);
            if ($symbolKey !== '' && preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $limit)) {
                $bySymbol[$symbolKey] = $limit;
            }
        }
    }
    return [
        'enabled' => $enabled === '1' || $enabled === 1 || $enabled === true,
        'max_amount' => $maxAmount,
        'daily_limit' => max(1, (int) $config['daily_limit']),
        'symbols' => $symbols ?: ['SPX001'],
        'max_amount_by_symbol' => $bySymbol,
        'daily_amount_limit' => $dailyAmountLimit,
        'window_hours' => 24,
    ];
}

/** 24 小时内已提交（含处理中）的提现累计金额，用于「拆单」防护。 */
function withdrawalRecentAmount(PDO $pdo, int $userId, int $windowHours = 24): string
{
    $statement = $pdo->prepare(
        'SELECT COALESCE(SUM(amount),0) FROM wallet_transactions
         WHERE user_id=? AND type="withdraw" AND created_at > DATE_SUB(NOW(), INTERVAL ? HOUR)
         AND status NOT IN ("cancelled","rejected","failed")'
    );
    $statement->execute([$userId, $windowHours]);
    return (string) $statement->fetchColumn();
}

/** Withdrawals that actually left (or are leaving) the platform; cancelled/rejected ones do not count. */
function withdrawalRecentCount(PDO $pdo, int $userId, int $windowHours = 24): int
{
    $statement = $pdo->prepare(
        'SELECT COUNT(*) FROM wallet_transactions
         WHERE user_id=? AND type="withdraw" AND created_at > DATE_SUB(NOW(), INTERVAL ? HOUR)
           AND status NOT IN ("cancelled","rejected","failed")'
    );
    $statement->execute([$userId, $windowHours]);
    return (int) $statement->fetchColumn();
}

/**
 * @return array{approved:bool,reason:string}
 */
function withdrawalAutoDecision(PDO $pdo, int $userId, string $symbol, string $grossAmount): array
{
    $policy = withdrawalAutoPolicy($pdo);
    if (!$policy['enabled']) return ['approved' => false, 'reason' => '自动通过已关闭'];
    $symbol = strtoupper($symbol);
    if (!in_array($symbol, $policy['symbols'], true)) {
        return ['approved' => false, 'reason' => $symbol . ' 不在自动通过名单'];
    }
    $ceiling = (string) ($policy['max_amount_by_symbol'][$symbol] ?? $policy['max_amount']);
    if (bccomp($grossAmount, $ceiling, 18) >= 0) {
        return ['approved' => false, 'reason' => '金额 ' . $grossAmount . ' 不低于门槛 ' . $ceiling];
    }
    // 笔数门槛：当前这笔也计入，所以 daily_limit=2 表示「每天最多自动通过 1 笔，第 2 笔起人工」
    $recent = withdrawalRecentCount($pdo, $userId, (int) $policy['window_hours']);
    if ($recent >= $policy['daily_limit']) {
        return ['approved' => false, 'reason' => '24 小时内第 ' . $recent . ' 笔提现，达到笔数上限 ' . $policy['daily_limit'] . '，转人工审核'];
    }
    // 金额门槛：防止把大额拆成多笔小额绕过单笔上限。
    // 注意：调用前本笔提现已经插入 wallet_transactions，所以累计值里已经包含本笔，不能再加一次。
    $recentAmount = withdrawalRecentAmount($pdo, $userId, (int) $policy['window_hours']);
    if (bccomp($recentAmount, (string) $policy['daily_amount_limit'], 18) > 0) {
        return ['approved' => false, 'reason' => '24 小时累计 ' . $recentAmount
            . ' 超过累计上限 ' . $policy['daily_amount_limit'] . '，转人工审核'];
    }
    return ['approved' => true, 'reason' => '金额 ' . $grossAmount . ' 低于 ' . $ceiling
        . '，24 小时内第 ' . $recent . ' 笔，累计 ' . $recentAmount];
}

/** System-side audit trail; admin_id stays NULL because no human made this decision. */
function withdrawalAuditAutoApprove(PDO $pdo, int $transactionId, string $reason, array $before, array $after): void
{
    $pdo->prepare(
        'INSERT INTO admin_audit_logs (admin_id,admin_username,`action`,target_type,target_id,reason,before_data,after_data,ip_address)
         VALUES (NULL,"system:auto-approve","withdraw.auto_approve","wallet_transaction",?,?,?,?,NULL)'
    )->execute([
        $transactionId,
        mb_substr($reason, 0, 500),
        json_encode($before, JSON_UNESCAPED_UNICODE),
        json_encode($after, JSON_UNESCAPED_UNICODE),
    ]);
}

/**
 * Flips a freshly inserted pending withdrawal to processing when the policy allows it.
 *
 * @return array{approved:bool,reason:string}
 */
function withdrawalTryAutoApprove(
    PDO $pdo,
    int $transactionId,
    int $userId,
    string $symbol,
    string $grossAmount,
    string $netAmount
): array {
    $decision = withdrawalAutoDecision($pdo, $userId, $symbol, $grossAmount);
    if (!$decision['approved']) return $decision;
    $update = $pdo->prepare('UPDATE wallet_transactions SET status="processing",error_message=NULL WHERE id=? AND type="withdraw" AND status="pending"');
    $update->execute([$transactionId]);
    if ($update->rowCount() !== 1) return ['approved' => false, 'reason' => '记录状态已变化，转人工'];
    withdrawalAuditAutoApprove($pdo, $transactionId, $decision['reason'], [
        'status' => 'pending',
        'symbol' => strtoupper($symbol),
        'amount' => $netAmount,
        'gross_amount' => $grossAmount,
    ], [
        'status' => 'processing',
        'automatic_withdrawal' => true,
        'policy' => $decision['reason'],
    ]);
    return $decision;
}
