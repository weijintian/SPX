<?php
declare(strict_types=1);

/**
 * 购物助手机器人：新订单播报到总群（默认 9OQF7L = 共创共建）。
 * 只做播报，不参与订单状态，任何异常都只写日志，绝不影响下单。
 */

require_once __DIR__ . '/chat_delivery.php';

const MALL_BOT_DEFAULT_GROUP_CODE = '9OQF7L';

/** 卡片消息需要的结构化字段，缺则自动补上（只建一次）。 */
function mallBotEnsureSchema(PDO $pdo): void
{
    static $done = false;
    if ($done) return;
    $done = true;
    try {
        $column = $pdo->query("SHOW COLUMNS FROM chat_group_messages LIKE 'payload_json'")->fetch();
        if (!$column) $pdo->exec('ALTER TABLE chat_group_messages ADD COLUMN payload_json TEXT NULL');
    } catch (Throwable $ignored) {
    }
}

/** 读取后台配置（带请求内缓存）。 */
function mallBotSetting(PDO $pdo, string $key, string $default = ''): string
{
    static $cache = [];
    if (!array_key_exists($key, $cache)) {
        $value = '';
        try {
            $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=?');
            $q->execute([$key]);
            $fetched = $q->fetchColumn();
            if ($fetched !== false && $fetched !== null) $value = trim((string) $fetched);
        } catch (Throwable $ignored) {
        }
        $cache[$key] = $value;
    }
    return $cache[$key] === '' ? $default : $cache[$key];
}

/** 机器人账号：优先用后台配置的 user id，没有就返回空 id（不播报）。 */
function mallBotUser(PDO $pdo): array
{
    static $bot = null;
    if ($bot !== null) return $bot;
    $bot = ['id' => 0, 'nickname' => 'SPX 购物助手', 'avatar_url' => null];
    $id = (int) mallBotSetting($pdo, 'mall_bot_user_id', '0');
    if ($id <= 0) return $bot;
    try {
        $q = $pdo->prepare('SELECT id,nickname,avatar_url FROM users WHERE id=? AND status=1');
        $q->execute([$id]);
        $row = $q->fetch();
        if ($row) $bot = ['id' => (int) $row['id'], 'nickname' => (string) $row['nickname'], 'avatar_url' => $row['avatar_url']];
    } catch (Throwable $ignored) {
    }
    return $bot;
}

/** 买家昵称脱敏：保留首字，其余打码。 */
function mallBotMaskName(string $name): string
{
    $name = trim($name);
    if ($name === '') return '匿名用户';
    $chars = preg_split('//u', $name, -1, PREG_SPLIT_NO_EMPTY) ?: [];
    $count = count($chars);
    if ($count === 0) return '匿名用户';
    if ($count === 1) return $chars[0] . '**';
    $keep = $count > 4 ? 2 : 1;
    return implode('', array_slice($chars, 0, $keep)) . str_repeat('*', min(4, max(2, $count - $keep)));
}

/** 金额去掉多余 0，保留最多 2 位小数。 */
function mallBotAmount(string $value): string
{
    $raw = trim($value);
    if ($raw === '' || !is_numeric($raw)) return '0';
    $formatted = number_format((float) $raw, 2, '.', '');
    return rtrim(rtrim($formatted, '0'), '.') ?: '0';
}

/**
 * 播报一笔新订单到总群。返回是否真的发出去了。
 * 调用方必须先提交订单事务，播报失败不回滚订单。
 */
function mallBotBroadcastOrder(PDO $pdo, int $orderId): bool
{
    try {
        if ($orderId <= 0) return false;
        if (mallBotSetting($pdo, 'mall_broadcast_enabled', '1') !== '1') return false;
        $bot = mallBotUser($pdo);
        if ((int) $bot['id'] <= 0) return false;

        $groupCode = strtoupper(mallBotSetting($pdo, 'mall_broadcast_group_code', MALL_BOT_DEFAULT_GROUP_CODE));
        $q = $pdo->prepare('SELECT id FROM chat_groups WHERE group_code=?');
        $q->execute([$groupCode]);
        $groupId = (int) $q->fetchColumn();
        if ($groupId <= 0) return false;

        $q = $pdo->prepare('SELECT o.id,o.total_spx,o.quantity,o.variant_name,o.merchant_id,o.created_at,p.name AS product_name,u.nickname AS buyer
                            FROM orders o
                            LEFT JOIN products p ON p.id=o.product_id
                            LEFT JOIN users u ON u.id=o.user_id
                            WHERE o.id=?');
        $q->execute([$orderId]);
        $order = $q->fetch();
        if (!$order) return false;

        $merchantName = '';
        if ((int) $order['merchant_id'] > 0) {
            $mq = $pdo->prepare('SELECT name FROM merchants WHERE id=?');
            $mq->execute([(int) $order['merchant_id']]);
            $merchantName = trim((string) ($mq->fetchColumn() ?: ''));
        }

        $lines = ['🛒 新订单播报'];
        $product = trim((string) ($order['product_name'] ?? ''));
        $lines[] = '商品：' . ($product !== '' ? $product : '商品 #' . $orderId);
        $variant = trim((string) ($order['variant_name'] ?? ''));
        if ($variant !== '') $lines[] = '规格：' . $variant;
        $lines[] = '数量：' . max(1, (int) $order['quantity']);
        $lines[] = '金额：' . mallBotAmount((string) $order['total_spx']) . ' SPX001';
        if ($merchantName !== '') $lines[] = '商家：' . $merchantName;
        $lines[] = '买家：' . mallBotMaskName((string) ($order['buyer'] ?? ''));
        $content = implode("\n", $lines);

        mallBotEnsureSchema($pdo);
        $payload = json_encode([
            'order_id' => (int) $orderId,
            'product' => $product !== '' ? $product : '',
            'variant' => $variant,
            'quantity' => max(1, (int) $order['quantity']),
            'amount' => mallBotAmount((string) $order['total_spx']),
            'symbol' => 'SPX001',
            'merchant' => $merchantName,
            'buyer' => mallBotMaskName((string) ($order['buyer'] ?? '')),
            'image' => '',
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        $client = 'mallbot-' . bin2hex(random_bytes(12));
        chatQuery($pdo, 'INSERT INTO chat_group_messages (group_id,sender_id,client_message_id,content,message_type,message_origin,payload_json) VALUES (?,?,?,?,"mall_card","human",?)',
            [$groupId, (int) $bot['id'], $client, $content, $payload]);
        $messageId = (int) $pdo->lastInsertId();
        $message = chatQuery($pdo, 'SELECT m.*,u.nickname,u.avatar_url,g.name AS group_name FROM chat_group_messages m JOIN users u ON u.id=m.sender_id JOIN chat_groups g ON g.id=m.group_id WHERE m.id=?', [$messageId])->fetch();
        if (!$message) return false;
        $message['mention_user_ids'] = [];
        $message['payload'] = json_decode($payload, true);
        chatQuery($pdo, 'UPDATE chat_group_members SET unread_count=unread_count+1 WHERE group_id=? AND user_id<>?', [$groupId, (int) $bot['id']]);

        $events = [];
        foreach (chatQuery($pdo, 'SELECT user_id FROM chat_group_members WHERE group_id=?', [$groupId])->fetchAll(PDO::FETCH_COLUMN) as $recipient) {
            $events[] = ['type' => 'message', 'user_id' => (int) $recipient, 'group_id' => $groupId, 'message' => $message];
        }
        if ($events && function_exists('chatPublishGroupEvents')) chatPublishGroupEvents($events);
        return true;
    } catch (Throwable $exception) {
        error_log('mall bot broadcast failed: ' . $exception->getMessage());
        return false;
    }
}
