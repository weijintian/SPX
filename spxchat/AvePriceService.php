<?php
declare(strict_types=1);

final class AvePriceService
{
    private array $config;

    public function __construct(array $config)
    {
        $this->config = $config;
    }

    /** 平台资产符号 => AVE token_id。兼容只配了 token_id 的旧配置。 */
    public function tokens(): array
    {
        $configured = $this->config['tokens'] ?? null;
        if (is_array($configured) && $configured !== []) {
            $tokens = [];
            foreach ($configured as $symbol => $tokenId) {
                $tokens[strtoupper((string) $symbol)] = (string) $tokenId;
            }
            return $tokens;
        }
        return ['SPX001' => (string) ($this->config['token_id'] ?? '')];
    }

    public function supports(string $symbol): bool
    {
        return (string) ($this->tokens()[strtoupper($symbol)] ?? '') !== '';
    }

    /** 旧接口：固定 SPX001。 */
    public function getSnapshot(bool $forceRefresh = false): array
    {
        return $this->getSnapshotFor('SPX001', $forceRefresh);
    }

    public function getSnapshotFor(string $symbol, bool $forceRefresh = false): array
    {
        if ($this->config['api_key'] === '') {
            throw new RuntimeException('Ave API 尚未配置，请在 PHP-FPM 环境设置 AVE_API_KEY');
        }
        if (!function_exists('curl_init')) {
            throw new RuntimeException('服务器未安装 PHP cURL 扩展');
        }
        $symbol = strtoupper($symbol);
        $tokenId = (string) ($this->tokens()[$symbol] ?? '');
        if ($tokenId === '') {
            throw new RuntimeException('未配置 ' . $symbol . ' 的 Ave 行情');
        }

        // 每个币种独立缓存文件，避免互相覆盖。
        $cacheFile = (string) $this->config['cache_file'] . '-' . strtolower($symbol);
        $lock = @fopen($cacheFile . '.lock', 'c');
        if (!$lock) throw new RuntimeException('无法创建 Ave 行情缓存锁');
        if (!flock($lock, LOCK_EX)) {
            fclose($lock);
            throw new RuntimeException('无法锁定 Ave 行情缓存');
        }

        try {
            if (!$forceRefresh) {
                $cached = $this->readCache($cacheFile);
                if ($cached !== null && time() - $cached['cached_at'] < (int) $this->config['cache_ttl']) {
                    return $cached['snapshot'];
                }
            }

            $snapshot = $this->requestSnapshot($symbol, $tokenId);
            $temporary = $cacheFile . '.' . bin2hex(random_bytes(6)) . '.tmp';
            $payload = json_encode(['cached_at' => time(), 'snapshot' => $snapshot], JSON_UNESCAPED_SLASHES);
            if ($payload === false || file_put_contents($temporary, $payload, LOCK_EX) === false || !@rename($temporary, $cacheFile)) {
                @unlink($temporary);
                throw new RuntimeException('无法保存 Ave 行情缓存');
            }
            @chmod($cacheFile, 0600);
            return $snapshot;
        } finally {
            flock($lock, LOCK_UN);
            fclose($lock);
        }
    }

    /** 旧接口：USDT → SPX001。 */
    public function calcSpxAmount(string $usdtAmount, ?array $snapshot = null): array
    {
        return $this->calcAmountFor('SPX001', $usdtAmount, $snapshot);
    }

    /** USDT 按 AVE 价格折算成指定资产数量，含手续费。 */
    public function calcAmountFor(string $symbol, string $usdtAmount, ?array $snapshot = null): array
    {
        if (!function_exists('bccomp') || !function_exists('bcdiv') || !function_exists('bcmul') || !function_exists('bcadd')) {
            throw new RuntimeException('服务器未安装 BCMath 扩展');
        }
        if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $usdtAmount) || bccomp($usdtAmount, '0', 18) <= 0) {
            throw new InvalidArgumentException('USDT 金额格式无效');
        }

        $symbol = strtoupper($symbol);
        $snapshot = $snapshot ?? $this->getSnapshotFor($symbol);
        $apiPrice = (string) $snapshot['current_price_usd'];
        $feeRate = (string) $this->config['fee_rate'];
        if (!preg_match('/^(?:0(?:\.[0-9]{1,6})?|1(?:\.0{1,6})?)$/', $feeRate)) {
            throw new RuntimeException('EXCHANGE_FEE_RATE 配置无效');
        }
        $exchangePrice = bcmul($apiPrice, bcadd('1', $feeRate, 18), 18);
        if (bccomp($exchangePrice, '0', 18) <= 0) throw new RuntimeException('Ave 返回的兑换价格无效');

        // FISS 单价约 0.00006 美元，8 位小数会丢掉精度，统一保留 18 位后再截断展示。
        $amount = bcdiv($usdtAmount, $exchangePrice, 18);

        return [
            'symbol' => $snapshot['symbol'],
            'name' => $snapshot['name'],
            'token_id' => $snapshot['token_id'],
            'api_price' => $apiPrice,
            'exchange_price' => $exchangePrice,
            'amount' => $amount,
            'spx_amount' => $amount,
            'fee_rate' => $feeRate,
            'updated_at' => $snapshot['updated_at'],
        ];
    }

    /** 钱包估值用：返回单价，失败时抛异常由调用方降级。 */
    public function priceUsd(string $symbol): array
    {
        $snapshot = $this->getSnapshotFor($symbol);
        return [
            'symbol' => $snapshot['symbol'],
            'price_usd' => (string) $snapshot['current_price_usd'],
            'price_change_24h' => (string) ($snapshot['price_change_24h'] ?? '0'),
            'updated_at' => (int) ($snapshot['updated_at'] ?? time()),
        ];
    }

    private function readCache(string $cacheFile): ?array
    {
        if (!is_file($cacheFile)) return null;
        $contents = @file_get_contents($cacheFile);
        if ($contents === false) return null;
        $cache = json_decode($contents, true);
        if (!is_array($cache) || !isset($cache['cached_at'], $cache['snapshot']) || !is_array($cache['snapshot'])) return null;
        return ['cached_at' => (int) $cache['cached_at'], 'snapshot' => $cache['snapshot']];
    }

    private function requestSnapshot(string $symbol, string $tokenId): array
    {
        $url = rtrim((string) $this->config['api_base'], '/') . '/v2/tokens/' . rawurlencode($tokenId);
        $curl = curl_init($url);
        if ($curl === false) throw new RuntimeException('无法初始化 Ave API 请求');
        curl_setopt_array($curl, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_TIMEOUT => (int) $this->config['timeout_seconds'],
            CURLOPT_HTTPHEADER => [
                'X-API-KEY: ' . $this->config['api_key'],
                'Content-Type: application/json',
            ],
        ]);
        $body = curl_exec($curl);
        $httpCode = (int) curl_getinfo($curl, CURLINFO_HTTP_CODE);
        $error = curl_error($curl);
        curl_close($curl);
        if ($body === false) throw new RuntimeException('Ave API 请求失败: ' . $error);
        if ($httpCode === 429) throw new RuntimeException('Ave API 限流，请稍后重试', 429);
        if ($httpCode < 200 || $httpCode >= 300) throw new RuntimeException('Ave API HTTP 状态异常: ' . $httpCode);

        $json = json_decode($body, true);
        if (!is_array($json) || (int) ($json['status'] ?? 0) !== 1) {
            throw new RuntimeException('Ave API 返回异常: ' . (string) ($json['msg'] ?? 'invalid response'));
        }
        $token = $json['data']['token'] ?? null;
        if (!is_array($token)) throw new RuntimeException('Ave API 响应缺少 token 数据');
        $returnedId = (string) ($token['token_id'] ?? '');
        $returnedSymbol = strtoupper((string) ($token['symbol'] ?? ''));
        $price = (string) ($token['current_price_usd'] ?? '');
        if (strtolower($returnedId) !== strtolower($tokenId) || $returnedSymbol !== $symbol
            || !preg_match('/^[0-9]+(?:\.[0-9]+)?$/', $price) || (float) $price <= 0) {
            throw new RuntimeException('Ave API 返回的代币或价格与 ' . $symbol . ' 配置不匹配');
        }

        return [
            'symbol' => $returnedSymbol,
            'name' => (string) ($token['name'] ?? $symbol),
            'token_id' => $returnedId,
            'current_price_usd' => $price,
            'tvl' => (string) ($token['tvl'] ?? '0'),
            'price_change_24h' => (string) ($token['price_change_24h'] ?? '0'),
            'updated_at' => (int) ($token['updated_at'] ?? time()),
        ];
    }
}
