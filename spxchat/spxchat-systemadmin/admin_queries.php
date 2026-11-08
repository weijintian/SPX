<?php
declare(strict_types=1);

function adminWalletTransactionPage(PDO $pdo, string $search, string $type, string $status, string $from, string $to): array
{
    $where = ['1=1'];
    $params = [];
    if ($type !== '') {
        $where[] = 't.type=?';
        $params[] = substr($type, 0, 64);
    }
    if (in_array($status, ['pending', 'processing', 'sending', 'completed', 'failed', 'rejected', 'cancelled'], true)) {
        $where[] = 't.status=?';
        $params[] = $status;
    }
    if ($search !== '') {
        $where[] = '(u.username LIKE ? OR u.nickname LIKE ? OR CAST(t.user_id AS CHAR) LIKE ? OR t.tx_hash LIKE ?)';
        $params = array_merge($params, array_fill(0, 4, '%' . $search . '%'));
    }
    if (preg_match('/^\d{4}-\d{2}-\d{2}$/', $from)) {
        $where[] = 't.created_at>=?';
        $params[] = $from . ' 00:00:00';
    }
    if (preg_match('/^\d{4}-\d{2}-\d{2}$/', $to)) {
        $where[] = 't.created_at<DATE_ADD(?,INTERVAL 1 DAY)';
        $params[] = $to;
    }
    $whereSql = implode(' AND ', $where);
    return adminPageQuery(
        $pdo,
        'SELECT COUNT(*) FROM wallet_transactions t JOIN users u ON u.id=t.user_id WHERE ' . $whereSql,
        'SELECT t.*,u.username,u.nickname FROM wallet_transactions t JOIN users u ON u.id=t.user_id WHERE ' . $whereSql . ' ORDER BY t.id DESC',
        $params
    );
}
