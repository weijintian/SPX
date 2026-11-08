<?php
declare(strict_types=1);

/**
 * 接口流量防线（分级限流）。
 *
 * 设计要点：
 *  1. 默认「影子模式」：只统计、不拦截。上线后先跑 24-72 小时收集真实峰值，
 *     确认阈值不会误伤正常用户，再切到拦截模式。
 *  2. 分两类计数：IP 维度（覆盖未登录请求）+ 用户维度（已登录）。
 *  3. 与旧接口限流（api_rate_limits 按小时）共存：这里用独立前缀 traffic: 和「分钟」窗口。
 */

const TRAFFIC_CLASS_LIMITS = [
    // 每人不允许超过的「每分钟」请求数；影子模式下只用于标记超限
    'heartbeat' => 300,   // presence.update / calls.presence 等心跳
    'update'    => 120,   // app.update.check（实测单 IP 峰值 28/分；NAT 出口多人共用，给到 120）
    'auth'      => 20,    // 登录、注册、验证码
    'read'      => 180,   // 列表、详情、行情报价（3/秒，够正常滑动翻页）
    'write'     => 60,    // 发消息、加好友、建群、领红包（1/秒，够活跃用户）
    'finance'   => 12,    // 提现、充值、质押、下单等真正动钱的写操作
    'upload'    => 30,    // 附件上传
];

/** 动作 → 类别。未列出的按 read 处理（最宽松）。 */
function trafficClass(string $action): string
{
    static $map = null;
    if ($map === null) {
        $map = [
            'heartbeat' => ['presence.update', 'calls.presence', 'presence.read', 'health', 'app.download.config'],
            'update' => ['app.update.check', 'app.update.download'],
            'auth' => ['auth.login', 'auth.register', 'auth.code', 'auth.password.reset', 'auth.password.reset.confirm'],
            'finance' => [
                'wallet.withdraw', 'wallet.deposit', 'wallet.exchange',
                'staking.create', 'staking.redeem',
                'merchant.deposit', 'orders.create', 'payment.password.set',
            ],
            'write' => [
                'messages.send', 'messages.send.batch', 'messages.revoke', 'messages.forward',
                'groups.create', 'groups.join', 'groups.leave', 'groups.members.add',
                'contacts.request', 'contacts.accept', 'friends.request', 'user.update',
                'chats.update', 'groups.update', 'upload.message', 'upload.message.chunk',
                'redpackets.send', 'redpackets.claim', 'wallet.exchange.quote',
            ],
            'upload' => ['upload.message', 'upload.message.chunk', 'upload.message.status', 'mall.upload', 'mall.shop.upload'],
        ];
    }
    foreach ($map as $class => $actions) {
        if (in_array($action, $actions, true)) return $class;
    }
    return 'read';
}

/** 读取运行配置；默认影子模式。文件缓存 30 秒，避免每个请求都查一次 app_settings。 */
function trafficGuardConfig(): array
{
    static $config = null;
    if ($config !== null) return $config;
    $config = ['shadow' => true, 'limits' => [], 'enabled' => true];
    $cacheFile = rtrim(sys_get_temp_dir(), '/') . '/spxchat-traffic-guard.json';
    if (is_readable($cacheFile) && (time() - (int) filemtime($cacheFile)) < 30) {
        $cached = json_decode((string) file_get_contents($cacheFile), true);
        if (is_array($cached)) return $config = $cached;
    }
    try {
        $statement = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
        $statement->execute(['traffic_guard_config']);
        $decoded = json_decode((string) ($statement->fetchColumn() ?: ''), true);
        if (is_array($decoded)) {
            if (array_key_exists('enabled', $decoded)) $config['enabled'] = (bool) $decoded['enabled'];
            if (array_key_exists('shadow', $decoded)) $config['shadow'] = (bool) $decoded['shadow'];
            if (!empty($decoded['limits']) && is_array($decoded['limits'])) $config['limits'] = $decoded['limits'];
        }
    } catch (Throwable $ignored) {
    }
    @file_put_contents($cacheFile, json_encode($config), LOCK_EX);
    return $config;
}

function trafficWindow(): string
{
    return date('Y-m-d H:i:00');
}

/**
 * 记录一次请求；影子模式只记日志，非影子模式超限直接 429。
 * $userId 为 null 时按 IP 计数。
 */
function apiTrafficGuard(string $action, ?int $userId): void
{
    static $counted = [];
    $config = trafficGuardConfig();
    if (empty($config['enabled'])) return;

    $class = trafficClass($action);
    $bucket = $userId !== null ? 'u' . $userId : 'ip' . apiClientIp();
    $dedupeKey = $class . '|' . $bucket;
    if (isset($counted[$dedupeKey])) return;
    $counted[$dedupeKey] = true;

    $limit = (int) ($config['limits'][$class] ?? TRAFFIC_CLASS_LIMITS[$class] ?? 120);
    $scopeKey = 'traffic:' . $class . ':' . $bucket;
    $window = trafficWindow();

    try {
        // 计数放 Redis（INCR + EXPIRE），每次只花一次内存操作，不占数据库连接和 SQL
        $attempts = 0;
        $redis = function_exists('cacheClient') ? cacheClient() : null;
        if ($redis !== null) {
            try {
                // 键里不能带空格（时间戳用紧凑格式），否则运维工具按空格切分会出错
                $redisKey = $scopeKey . ':' . date('YmdHi');
                $attempts = (int) $redis->incr($redisKey);
                if ($attempts === 1) $redis->expire($redisKey, 180);
            } catch (Throwable $ignored) {
                $attempts = 0;
            }
        }
        if ($attempts <= 0) {
            // Redis 不可用时回落到 MySQL（保证限流在降级状态下仍然生效）
            $pdo = db();
            $pdo->prepare('INSERT INTO api_rate_limits (scope_key,window_start,attempts) VALUES (?,?,1) ON DUPLICATE KEY UPDATE attempts=attempts+1')
                ->execute([$scopeKey, $window]);
            $statement = $pdo->prepare('SELECT attempts FROM api_rate_limits WHERE scope_key=? AND window_start=? LIMIT 1');
            $statement->execute([$scopeKey, $window]);
            $attempts = (int) $statement->fetchColumn();
        }

        if ($attempts > $limit) {
            if (!empty($config['shadow'])) {
                // 影子模式：只记录，不拦截。日志前缀固定，便于 grep 统计。
                error_log(sprintf(
                    'traffic-shadow exceeded action=%s class=%s bucket=%s attempts=%d limit=%d',
                    $action, $class, $bucket, $attempts, $limit
                ));
                return;
            }
            jsonResponse(null, '操作过于频繁，请稍后再试', 429);
        }
    } catch (Throwable $ignored) {
        // 限流本身出错不能影响业务请求
    }
}
