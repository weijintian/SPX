<?php
declare(strict_types=1);
/**
 * 缓存预热（CLI，由 systemd timer 每 45 秒执行，以 www-data 身份）
 * 目的：让用户请求永远命中缓存，不再承担外部行情 / 链上 RPC 的冷启动延迟。
 *  - 行情价格：AvePriceService → spx:prices:wallet:v2（TTL 120s）
 *  - 销毁量：链上 RPC → app_settings.burn_balance_FISS（原 TTL 600s，这里 300s 刷新一次）
 */
if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit('cli only');
}
require_once __DIR__ . '/cache.php';
require_once __DIR__ . '/AvePriceService.php';
require_once __DIR__ . '/db.php';
require_once __DIR__ . '/burn_tiers.php';

$start = microtime(true);
$result = ['prices' => 0, 'burn' => 'skip', 'ok' => false];

// 1) 行情价格
try {
    $service = new AvePriceService(require __DIR__ . '/config.ave.php');
    $prices = [];
    foreach (array_keys($service->tokens()) as $symbol) {
        try {
            $prices[$symbol] = $service->priceUsd($symbol);
        } catch (Throwable $ignored) {
        }
    }
    if ($prices) {
        cacheSet('prices:wallet:v2', $prices, 120);
        $result['prices'] = count($prices);
        $result['ok'] = true;
    }
} catch (Throwable $error) {
    $result['price_error'] = $error->getMessage();
}

// 2) 销毁量：只在缓存超过 300 秒时刷新，保证用户请求永远命中（原 TTL 600 秒）
try {
    $pdo = db();
    $stale = true;
    $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
    $q->execute(['burn_balance_FISS']);
    $cached = json_decode((string) ($q->fetchColumn() ?: ''), true);
    if (is_array($cached) && isset($cached['at']) && time() - (int) $cached['at'] < 300) {
        $stale = false;
        $result['burn'] = 'fresh';
    }
    if ($stale) {
        $refreshed = burnAmount($pdo, 'FISS', true);
        $result['burn'] = (string) ($refreshed['amount'] ?? '');
    }
} catch (Throwable $error) {
    $result['burn_error'] = $error->getMessage();
}

$result['ms'] = (int) round((microtime(true) - $start) * 1000);
echo json_encode($result, JSON_UNESCAPED_UNICODE) . PHP_EOL;
