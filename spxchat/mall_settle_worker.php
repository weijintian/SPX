<?php
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

require_once __DIR__ . '/mall_settlement.php';

$dryRun = in_array('--dry-run', $argv ?? [], true);
try {
    $pdo = db();
    // 先把发货超时的订单自动确认收货，再结算
    $auto = mallAutoConfirmShippedOrders($pdo, $dryRun);
    $stats = mallSettleDueOrders($pdo, $dryRun);
    $stats['auto_confirm'] = $auto;
    fwrite(STDOUT, date(DATE_ATOM) . ' ' . ($dryRun ? 'dry-run ' : '') . json_encode($stats, JSON_UNESCAPED_UNICODE) . PHP_EOL);
} catch (Throwable $exception) {
    fwrite(STDERR, date(DATE_ATOM) . ' mall settlement failed: ' . $exception->getMessage() . PHP_EOL);
    exit(1);
}
