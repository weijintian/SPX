<?php
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/staking_settlement.php';

try {
    $processed = runStakingSettlement();
    fwrite(STDOUT, date(DATE_ATOM) . " settled=" . $processed . PHP_EOL);
} catch (Throwable $exception) {
    fwrite(STDERR, date(DATE_ATOM) . ' staking settlement failed: ' . $exception->getMessage() . PHP_EOL);
    exit(1);
}
