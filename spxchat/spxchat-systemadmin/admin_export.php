<?php
declare(strict_types=1);

function adminHandleExport(PDO $pdo): never
{
    if (!adminCan('export')) {
        http_response_code(403);
        exit('无权导出数据');
    }

    $exports = [
        'users' => ['users', 'SELECT id,username,email,nickname,spx_id,status,remark,bsc_wallet_address,created_at FROM users ORDER BY id DESC'],
        'wallets' => ['wallets', 'SELECT w.user_id,u.username,w.symbol,w.chain,w.balance,w.stakeable_balance,w.updated_at FROM wallet_assets w JOIN users u ON u.id=w.user_id ORDER BY w.user_id,w.symbol'],
        'recharges' => ['recharges', 'SELECT id,user_id,tx_hash,currency,amount,status,wallet_address,deposit_address,error_message,created_at FROM recharge_records ORDER BY id DESC'],
        'withdrawals' => ['withdrawals', 'SELECT id,user_id,symbol,chain,amount,address,tx_hash,status,created_at FROM wallet_transactions WHERE type="withdraw" ORDER BY id DESC'],
        'transactions' => ['transactions', 'SELECT id,user_id,symbol,chain,type,amount,fee_amount,address,tx_hash,status,created_at FROM wallet_transactions ORDER BY id DESC'],
        'orders' => ['orders', 'SELECT id,user_id,product_id,quantity,total_spx,status,created_at FROM orders ORDER BY id DESC'],
        'tickets' => ['tickets', 'SELECT id,user_id,subject,content,status,admin_reply,created_at,updated_at FROM support_tickets ORDER BY id DESC'],
        'staking' => ['staking-orders', 'SELECT s.id,s.user_id,u.username,u.nickname,u.spx_id,s.phase_id,ph.name AS phase_name,p.name AS plan_name,s.plan_days,s.amount,s.apy,s.status,s.created_at,CAST(ROUND(s.amount*s.apy*s.plan_days/36500,18) AS DECIMAL(36,18)) AS expected_reward,COALESCE((SELECT SUM(d.amount) FROM staking_dividends d WHERE d.staking_id=s.id),0) AS paid_dividend,COALESCE((SELECT reward_amount FROM referral_rewards r WHERE r.staking_id=s.id LIMIT 1),0) AS referral_reward FROM staking_records s JOIN users u ON u.id=s.user_id LEFT JOIN staking_phases ph ON ph.id=s.phase_id LEFT JOIN staking_plans p ON p.plan_days=s.plan_days AND p.symbol=s.symbol ORDER BY s.id DESC'],
    ];
    $exportKey = (string) ($_GET['export'] ?? '');
    if (!isset($exports[$exportKey])) {
        http_response_code(404);
        exit('导出类型不存在');
    }
    $exportSections = [
        'users' => 'export_finance', 'wallets' => 'export_finance', 'recharges' => 'export_finance',
        'withdrawals' => 'export_finance', 'transactions' => 'export_finance', 'orders' => 'export_orders',
        'staking' => 'export_finance', 'tickets' => 'export_tickets',
    ];
    if (!adminCan($exportSections[$exportKey])) {
        http_response_code(403);
        exit('无权导出此数据');
    }

    [$filename, $sql] = $exports[$exportKey];
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="spxchat-' . $filename . '-' . date('Ymd-His') . '.csv"');
    $output = fopen('php://output', 'wb');
    fwrite($output, "\xEF\xBB\xBF");
    $statement = $pdo->query($sql);
    $first = $statement->fetch(PDO::FETCH_ASSOC);
    if ($first) {
        fputcsv($output, array_keys($first));
        do {
            $safeRow = array_map(static function (mixed $value): string {
                $cell = (string) ($value ?? '');
                return preg_match('/^\s*[=+@-]/', $cell) ? "'" . $cell : $cell;
            }, array_values($first));
            fputcsv($output, $safeRow);
        } while ($first = $statement->fetch(PDO::FETCH_ASSOC));
    }
    fclose($output);
    exit;
}
