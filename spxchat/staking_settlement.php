<?php
declare(strict_types=1);

/**
 * 结算时按质押记录自身的币种入账。
 * 历史上这里固定写死 SPX001，FISS 质押的收益和本金会被错误发成 SPX001。
 */
function creditStakingWallet(PDO $pdo, int $userId, string $symbol, string $amount, string $type): void
{
    $symbol = strtoupper($symbol);
    if (!in_array($symbol, ['SPX001', 'FISS'], true)) $symbol = 'SPX001';
    walletCredit($pdo, $userId, $symbol, 'BSC', $amount, false);
    $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,status) VALUES (?,?,"BSC",?,?,"completed")')->execute([$userId, $symbol, $type, $amount]);
}

function runStakingSettlement(): int
{
    $pdo = db();
    if ((int) $pdo->query("SELECT GET_LOCK('spx_staking_dividends',1)")->fetchColumn() !== 1) return 0;
    $processed = 0;
    try {
        $lastRun = (int) $pdo->query('SELECT `value` FROM app_settings WHERE `key`="staking_dividend_last_run" LIMIT 1')->fetchColumn();
        if ($lastRun > 0 && time() - $lastRun < 60) return 0;
        $pdo->beginTransaction();
        $records = $pdo->query('SELECT id,user_id,symbol,phase_id,plan_days,amount,apy,created_at FROM staking_records WHERE status="active" FOR UPDATE')->fetchAll();
        foreach ($records as $record) {
            $recordSymbol = strtoupper((string) ($record['symbol'] ?? 'SPX001'));
            $createdAt = strtotime((string) $record['created_at']);
            $elapsedDays = max(0, intdiv(max(0, time() - $createdAt), 86400));
            $planDays = max(0, (int) $record['plan_days']);
            $isExpired = $elapsedDays >= $planDays;
            $periods = $isExpired ? (int) ceil($planDays / 10) : intdiv($elapsedDays, 10);
            if ($periods <= 0 && !$isExpired) continue;

            $paid = $pdo->prepare('SELECT period_no FROM staking_dividends WHERE staking_id=? ORDER BY period_no');
            $paid->execute([(int) $record['id']]);
            $paidPeriods = array_fill_keys(array_map('intval', $paid->fetchAll(PDO::FETCH_COLUMN)), true);
            for ($period = 1; $period <= $periods; $period++) {
                if (isset($paidPeriods[$period])) continue;
                $periodDays = $period === $periods && $isExpired ? $planDays - (($period - 1) * 10) : 10;
                if ($periodDays <= 0) continue;
                $amountQuery = $pdo->prepare('SELECT CAST(ROUND(CAST(? AS DECIMAL(36,18))*CAST(? AS DECIMAL(36,18))*CAST(? AS DECIMAL(36,18))/36500,18) AS DECIMAL(36,18))');
                $amountQuery->execute([$record['amount'], $record['apy'], $periodDays]);
                $dividendAmount = (string) $amountQuery->fetchColumn();
                $pdo->prepare('INSERT INTO staking_dividends (staking_id,user_id,phase_id,period_no,amount) VALUES (?,?,?,?,?)')->execute([(int) $record['id'], (int) $record['user_id'], (int) $record['phase_id'], $period, $dividendAmount]);
                creditStakingWallet($pdo, (int) $record['user_id'], $recordSymbol, $dividendAmount, 'staking_dividend');
                $pdo->prepare('UPDATE staking_phases SET paid_quota=paid_quota+? WHERE id=?')->execute([$dividendAmount, (int) $record['phase_id']]);
                $processed++;
            }
            if ($isExpired) {
                $pdo->prepare('UPDATE staking_records SET status="redeemed" WHERE id=? AND status="active"')->execute([(int) $record['id']]);
                creditStakingWallet($pdo, (int) $record['user_id'], $recordSymbol, (string) $record['amount'], 'staking_redeem');
                $processed++;
            }
        }
        $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES ("staking_dividend_last_run",?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')->execute([(string) time()]);
        $pdo->commit();
        return $processed;
    } catch (Throwable $exception) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        throw $exception;
    } finally {
        $pdo->query("SELECT RELEASE_LOCK('spx_staking_dividends')");
    }
}
