<?php
declare(strict_types=1);

const MALL_USD_CNY = '6.7';

/** The CNY list price is fixed; SPX001 quantity is calculated from this snapshot. */
function mallUsdtCny(PDO $pdo): string
{
    // Keep the merchant price conversion deterministic. The old setting is ignored
    // so a stale admin value cannot silently change product checkout amounts.
    return MALL_USD_CNY;
}

function mallValidUsdtCny(string $raw): bool
{
    return (bool) preg_match('/^(?:0|[1-9][0-9]{0,2})(?:\.[0-9]{1,6})?$/D', $raw)
        && (float) $raw > 0 && (float) $raw <= 100;
}

function mallValuation(PDO $pdo): array
{
    $snapshot = mallPricingSnapshot($pdo);
    return [
        'symbol' => 'SPX001',
        'spx_usdt' => $snapshot['spx_usd'],
        'usdt_cny' => MALL_USD_CNY,
        // Keep both names while older Android builds still read usdt_cny.
        'usd_cny' => MALL_USD_CNY,
        'updated_at' => (string) ($snapshot['updated_at'] ?? ''),
        'reference_only' => true,
        'checkout_locks_price' => true,
    ];
}

function mallPricingSnapshot(PDO $pdo, ?array $snapshot = null): array
{
    if (!function_exists('bccomp')) throw new RuntimeException('服务器未安装 BCMath 扩展');
    require_once __DIR__ . '/AvePriceService.php';
    if ($snapshot === null) {
        $config = require __DIR__ . '/config.ave.php';
        $snapshot = (new AvePriceService($config))->getSnapshot();
    }
    $spxUsd = trim((string) ($snapshot['current_price_usd'] ?? $snapshot['spx_usd'] ?? ''));
    if (!preg_match('/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,18})?$/', $spxUsd) || bccomp($spxUsd, '0', 18) <= 0) {
        throw new InvalidArgumentException('SPX001 实时行情无效，请稍后重新核对价格');
    }
    return [
        'symbol' => 'SPX001',
        'spx_usd' => $spxUsd,
        'usd_cny' => MALL_USD_CNY,
        'updated_at' => (string) ($snapshot['updated_at'] ?? ''),
    ];
}

function mallCnyToSpx(string $cny, array $pricing): string
{
    if (!function_exists('bcdiv') || !function_exists('bcmul') || !function_exists('bccomp')) throw new RuntimeException('服务器未安装 BCMath 扩展，无法计算 SPX001 价格');
    $spxUsd = trim((string) ($pricing['spx_usd'] ?? ''));
    if (!preg_match('/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,18})?$/', $spxUsd) || bccomp($spxUsd, '0', 18) <= 0) {
        throw new InvalidArgumentException('SPX001 实时行情无效');
    }
    if ((string) ($pricing['usd_cny'] ?? MALL_USD_CNY) !== MALL_USD_CNY) {
        throw new InvalidArgumentException('商城换算汇率无效');
    }
    if (!preg_match('/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,8})?$/', $cny) || bccomp($cny, '0', 8) <= 0) {
        throw new InvalidArgumentException('CNY 商品价格必须大于零，最多八位小数');
    }
    // Multiply first so very small token prices do not amplify an intermediate
    // rounding error. Keep the 8-decimal token precision used by checkout.
    $tokenCny = bcmul(MALL_USD_CNY, $spxUsd, 19);
    $amount = bcdiv($cny, $tokenCny, 8);
    if (bccomp($amount, '0', 8) <= 0 || bccomp($amount, '9999999999999999.99999999', 8) > 0) {
        throw new InvalidArgumentException('换算后的 SPX001 金额超出支持范围，请调整 CNY 价格');
    }
    return $amount;
}
