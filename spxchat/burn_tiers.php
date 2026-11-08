<?php
declare(strict_types=1);

/**
 * FISS 销毁阶梯：起押门槛随「转到黑洞地址的累计销毁量」自动下调。
 *
 * 所有参数都放数据库，后台可改：
 *   - staking_burn_tiers 表：每一档的「销毁阈值 → 起押金额」
 *   - app_settings:
 *       burn_address            黑洞地址（默认 0x…dEaD）
 *       burn_tier_mode          auto（自动按链上销毁）/ manual（后台指定档位）
 *       burn_tier_manual_threshold  手动模式下使用的销毁量
 *       bsc_rpc_url             BSC RPC 节点
 *       burn_balance_FISS       销毁量缓存 {amount, at}
 *       burn_baseline           销毁基准线（从该值起算「新增销毁」，用于阶梯判档）
 */

const BURN_TOKEN_CONTRACTS = [
    'FISS' => '0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a',
    'SPX001' => '0x0e920821464625cf661a4dcedeb6406e1b48ffff',
    'USDT' => '0x55d398326f99059ff775485246999027b3197955',
];

const BURN_DEFAULT_ADDRESS = '0x000000000000000000000000000000000000dEaD';
const BURN_CACHE_SECONDS = 600;

/** 读取一条 app_settings（带进程内缓存）。 */
function burnSetting(PDO $pdo, string $key, string $default = ''): string
{
    static $cache = [];
    if (array_key_exists($key, $cache)) return $cache[$key];
    $stmt = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
    $stmt->execute([$key]);
    $value = $stmt->fetchColumn();
    $cache[$key] = ($value === false || $value === null) ? $default : (string) $value;
    return $cache[$key];
}

function burnAddress(PDO $pdo): string
{
    $addr = strtolower(trim(burnSetting($pdo, 'burn_address', BURN_DEFAULT_ADDRESS)));
    return preg_match('/^0x[0-9a-f]{40}$/', $addr) ? $addr : BURN_DEFAULT_ADDRESS;
}

function burnRpcUrl(PDO $pdo): string
{
    $url = trim(burnSetting($pdo, 'bsc_rpc_url', 'https://bsc-dataseed1.binance.org'));
    return str_starts_with($url, 'http') ? $url : 'https://bsc-dataseed1.binance.org';
}

/** 链上读取某个代币在黑洞地址的余额（keccak 后的 balanceOf）。 */
function burnFetchOnChain(PDO $pdo, string $symbol): ?string
{
    $symbol = strtoupper($symbol);
    $token = BURN_TOKEN_CONTRACTS[$symbol] ?? null;
    if ($token === null) return null;

    $addr = burnAddress($pdo);
    $data = '0x70a08231' . str_pad(substr($addr, 2), 64, '0', STR_PAD_LEFT);
    $payload = json_encode([
        'jsonrpc' => '2.0', 'id' => 1, 'method' => 'eth_call',
        'params' => [['to' => $token, 'data' => $data], 'latest'],
    ], JSON_THROW_ON_ERROR);

    $ch = curl_init(burnRpcUrl($pdo));
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => $payload,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => ['Content-Type: application/json'],
        CURLOPT_TIMEOUT => 8,
        CURLOPT_CONNECTTIMEOUT => 4,
    ]);
    $raw = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($raw === false || $code !== 200) return null;

    $json = json_decode((string) $raw, true);
    $hex = $json['result'] ?? null;
    if (!is_string($hex) || $hex === '0x' || strlen($hex) < 3) return null;

    // 16 进制 → 十进制字符串（不依赖 bcmath 的 hex 支持）
    $hex = ltrim(substr($hex, 2), '0');
    if ($hex === '') return '0';
    $decimal = '0';
    foreach (str_split($hex) as $digit) {
        $decimal = burnDecMulAdd($decimal, 16, hexdec($digit));
    }
    return $decimal;
}

/** 大整数字符串运算：value*16 + digit */
function burnDecMulAdd(string $value, int $mul, int $add): string
{
    $carry = $add;
    $out = '';
    for ($i = strlen($value) - 1; $i >= 0; $i--) {
        $n = (int) $value[$i] * $mul + $carry;
        $out = (string) ($n % 10) . $out;
        $carry = intdiv($n, 10);
    }
    while ($carry > 0) {
        $out = (string) ($carry % 10) . $out;
        $carry = intdiv($carry, 10);
    }
    return ltrim($out, '0') ?: '0';
}

/** 把 18 位小数的整数换算成可展示金额字符串。 */
function burnFormatUnits(string $rawUnits, int $decimals = 18): string
{
    if (!function_exists('bcadd')) return '0';
    return bcdiv($rawUnits, bcpow('10', (string) $decimals, 0), $decimals);
}

/**
 * 取当前销毁量（带 10 分钟缓存；$force 强制刷新）。
 * 返回 ['amount' => '1234.000...', 'cached_at' => ts, 'stale' => bool]
 */
function burnAmount(PDO $pdo, string $symbol = 'FISS', bool $force = false): array
{
    $key = 'burn_balance_' . strtoupper($symbol);
    $cached = json_decode(burnSetting($pdo, $key, ''), true);
    $now = time();
    if (!$force && is_array($cached) && isset($cached['amount'], $cached['at']) && $now - (int) $cached['at'] < BURN_CACHE_SECONDS) {
        return ['amount' => (string) $cached['amount'], 'cached_at' => (int) $cached['at'], 'stale' => false];
    }

    $raw = burnFetchOnChain($pdo, $symbol);
    if ($raw === null) {
        // 链上读取失败：用旧缓存兜底，避免因 RPC 抖动导致起押门槛异常
        if (is_array($cached) && isset($cached['amount'])) {
            return ['amount' => (string) $cached['amount'], 'cached_at' => (int) $cached['at'], 'stale' => true];
        }
        return ['amount' => '0', 'cached_at' => 0, 'stale' => true];
    }

    $amount = burnFormatUnits($raw, 18);
    $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')
        ->execute([$key, json_encode(['amount' => $amount, 'at' => $now])]);
    return ['amount' => $amount, 'cached_at' => $now, 'stale' => false];
}

/** 读取某币种的全部档位（按阈值升序）。 */
function burnTierRows(PDO $pdo, string $symbol = 'FISS'): array
{
    $stmt = $pdo->prepare('SELECT id,symbol,threshold_amount,min_stake_amount,status FROM staking_burn_tiers WHERE symbol=? AND status=1 ORDER BY threshold_amount ASC');
    $stmt->execute([strtoupper($symbol)]);
    return $stmt->fetchAll();
}

/**
 * 计算当前生效档位。
 * 返回：burn（用于选档的销毁量）, tier（当前档）, next（下一档）, remaining（距下一档还差多少）
 */
function burnCurrentTier(PDO $pdo, string $symbol = 'FISS', bool $force = false): array
{
    $symbol = strtoupper($symbol);
    $mode = strtolower(burnSetting($pdo, 'burn_tier_mode', 'auto')) === 'manual' ? 'manual' : 'auto';

    $baseline = burnSetting($pdo, 'burn_baseline', '0');
    // 链上真实余额始终读取（用于展示），手动模式也一样
    $chain = burnAmount($pdo, $symbol, $force);
    $burnRaw = (string) $chain['amount'];

    if ($mode === 'manual') {
        // 手动模式：后台填的就是「新增销毁量」，直接用于判档，不再扣基准线
        $burn = burnSetting($pdo, 'burn_tier_manual_threshold', '0');
        $meta = ['cached_at' => (int) $chain['cached_at'], 'stale' => (bool) $chain['stale'], 'source' => 'manual'];
    } else {
        // 自动模式：链上真实余额 - 基准线 = 上线后新增销毁
        $burn = bccomp($burnRaw, $baseline, 18) > 0 ? bcsub($burnRaw, $baseline, 18) : '0';
        $meta = ['cached_at' => (int) $chain['cached_at'], 'stale' => (bool) $chain['stale'], 'source' => 'chain'];
    }

    $tiers = burnTierRows($pdo, $symbol);
    $current = null;
    $next = null;
    foreach ($tiers as $tier) {
        if (bccomp((string) $tier['threshold_amount'], $burn, 18) <= 0) {
            $current = $tier;
            continue;
        }
        $next = $tier;
        break;
    }
    if ($current === null && $tiers) $current = $tiers[0];

    $remaining = null;
    if ($next !== null && $current !== null) {
        $remaining = bcsub((string) $next['threshold_amount'], $burn, 18);
    }

    return [
        'symbol' => $symbol,
        'mode' => $mode,
        'burn' => (string) $burn,
        'burn_raw' => (string) ($burnRaw ?? $burn),
        'baseline' => (string) $baseline,
        'tier' => $current,
        'next' => $next,
        'remaining' => $remaining,
        'tiers' => $tiers,
        'cached_at' => $meta['cached_at'],
        'stale' => $meta['stale'],
        'source' => $meta['source'],
    ];
}

/**
 * 计算实际生效的起押金额：有档位用档位，没有则回退到方案自身的最低金额。
 * 返回 [min, tierInfo]
 */
function burnEffectiveMinimum(PDO $pdo, string $symbol, string $planMinimum): array
{
    $tierInfo = burnCurrentTier($pdo, $symbol);
    $tier = $tierInfo['tier'];
    if ($tier !== null && bccomp((string) $tier['min_stake_amount'], '0', 18) > 0) {
        return [(string) $tier['min_stake_amount'], $tierInfo];
    }
    return [$planMinimum, $tierInfo];
}
