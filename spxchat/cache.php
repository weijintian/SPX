<?php
declare(strict_types=1);

/**
 * 极简 Redis 缓存层。
 *
 * 设计原则：缓存是「加速器」，不是「依赖」。
 *   - 连接失败（超时 200ms）自动降级为直连数据库，业务不受影响
 *   - 只缓存可重建的读数据（配置、方案、版本信息），不缓存资金类数据
 */

function cacheClient(): ?Redis
{
    static $client = null;
    static $failed = false;
    if ($failed) return null;
    if ($client instanceof Redis) return $client;
    if (!class_exists('Redis')) { $failed = true; return null; }
    try {
        $redis = new Redis();
        // 200ms 连接超时：Redis 挂了也只会多等 0.2 秒，不会拖垮请求
        if (!$redis->connect('127.0.0.1', 6379, 0.2)) { $failed = true; return null; }
        $redis->setOption(Redis::OPT_PREFIX, 'spx:');
        $redis->setOption(Redis::OPT_SERIALIZER, Redis::SERIALIZER_PHP);
        $redis->setOption(Redis::OPT_READ_TIMEOUT, 0.5);
        $client = $redis;
    } catch (Throwable $ignored) {
        $failed = true;
        return null;
    }
    return $client;
}

function cacheGet(string $key, mixed $default = null): mixed
{
    $redis = cacheClient();
    if ($redis === null) return $default;
    try {
        $value = $redis->get($key);
        return $value === false ? $default : $value;
    } catch (Throwable $ignored) {
        return $default;
    }
}

function cacheSet(string $key, mixed $value, int $ttl = 60): void
{
    $redis = cacheClient();
    if ($redis === null) return;
    try {
        $redis->setex($key, max(1, $ttl), $value);
    } catch (Throwable $ignored) {
    }
}

function cacheForget(string ...$keys): void
{
    $redis = cacheClient();
    if ($redis === null) return;
    try {
        foreach ($keys as $key) $redis->del($key);
    } catch (Throwable $ignored) {
    }
}

/** 读缓存，未命中时执行 $loader 并写回。$loader 抛异常则直接透传（不吞错）。 */
function cacheRemember(string $key, int $ttl, callable $loader): mixed
{
    $cached = cacheGet($key, null);
    if ($cached !== null) return $cached;
    $value = $loader();
    if ($value !== null) cacheSet($key, $value, $ttl);
    return $value;
}
