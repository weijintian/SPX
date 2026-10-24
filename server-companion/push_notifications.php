<?php
declare(strict_types=1);

// Registration is usable before APNs credentials exist. Delivery remains disabled
// unless the operator explicitly enables it in the server environment.
function installPushSchema(PDO $pdo): void
{
    $pdo->exec('CREATE TABLE IF NOT EXISTS push_devices (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL,
        device_id VARCHAR(128) NOT NULL, provider VARCHAR(20) NOT NULL, platform VARCHAR(16) NOT NULL,
        device_token VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        token_hash CHAR(64) CHARACTER SET ascii NOT NULL, session_hash CHAR(64) CHARACTER SET ascii NOT NULL,
        bundle_id VARCHAR(190) NOT NULL, environment VARCHAR(16) NOT NULL,
        enabled TINYINT NOT NULL DEFAULT 1, preview TINYINT NOT NULL DEFAULT 1, badge TINYINT NOT NULL DEFAULT 1,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        UNIQUE KEY uq_push_device (device_id,provider), UNIQUE KEY uq_push_token (provider,token_hash),
        INDEX idx_push_user (user_id), FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
    $pdo->exec('CREATE TABLE IF NOT EXISTS push_queue (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, device_id BIGINT UNSIGNED NOT NULL,
        message_id BIGINT UNSIGNED NOT NULL, chat_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
        group_id BIGINT UNSIGNED NOT NULL DEFAULT 0, session_hash CHAR(64) CHARACTER SET ascii NOT NULL,
        payload TEXT NOT NULL, attempts INT NOT NULL DEFAULT 0, available_at DATETIME NOT NULL,
        expires_at DATETIME NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_push_message (device_id,message_id,chat_id,group_id),
        INDEX idx_push_pending (available_at), FOREIGN KEY(device_id) REFERENCES push_devices(id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
}

function pushRegistration(array $body): array
{
    foreach (['provider','platform','device_id','device_token','bundle_id','environment'] as $key) {
        if (!isset($body[$key]) || !is_string($body[$key])) throw new InvalidArgumentException('推送设备参数格式不正确');
    }
    if ($body['provider'] !== 'apns' || $body['platform'] !== 'ios') throw new InvalidArgumentException('该推送服务尚未配置');
    if (!preg_match('/^[A-Za-z0-9._-]{8,128}$/D', $body['device_id'])) throw new InvalidArgumentException('设备标识不正确');
    $token = strtolower($body['device_token']);
    if (!preg_match('/^[a-f0-9]{32,512}$/D', $token) || strlen($token) % 2 !== 0) throw new InvalidArgumentException('设备推送标识不正确');
    if (!preg_match('/^[A-Za-z0-9][A-Za-z0-9.-]{1,189}$/D', $body['bundle_id'])) throw new InvalidArgumentException('应用标识不正确');
    if (!in_array($body['environment'], ['development','production'], true)) throw new InvalidArgumentException('推送环境不正确');
    $result = $body; $result['device_token'] = $token;
    foreach (['enabled','preview','badge'] as $key) {
        if (array_key_exists($key,$body) && !is_bool($body[$key])) throw new InvalidArgumentException('通知偏好格式不正确');
        $result[$key] = ($body[$key] ?? true) ? 1 : 0;
    }
    return $result;
}

function pushApi(string $action, string $method, array $body): void
{
    if (!in_array($action, ['push.device.register','push.device.unregister'], true)) return;
    if ($method !== 'POST') jsonResponse(null, '请使用 POST 请求', 405);
    $user = currentUser(); $userId = (int)$user['id']; $sessionToken = (string)bearerToken(); $session = hash('sha256', $sessionToken);
    if ($action === 'push.device.unregister') {
        $device = $body['device_id'] ?? '';
        if (!is_string($device) || !preg_match('/^[A-Za-z0-9._-]{8,128}$/D', $device)) throw new InvalidArgumentException('设备标识不正确');
        db()->prepare('DELETE FROM push_devices WHERE user_id=? AND device_id=? AND session_hash=?')->execute([$userId,$device,$session]);
        jsonResponse(['registered'=>false]);
    }
    $v = pushRegistration($body); $pdo = db();
    // Device/token reassignment is atomic. A new account must never receive the
    // previous account's queued notification on this physical device.
    $pdo->beginTransaction();
    try {
        $pdo->prepare('SELECT id FROM users WHERE id=? FOR UPDATE')->execute([$userId]);
        $active=$pdo->prepare('SELECT id FROM user_tokens WHERE user_id=? AND token=? AND expires_at>NOW() FOR UPDATE');
        $active->execute([$userId,$sessionToken]);
        if (!$active->fetchColumn()) { $pdo->rollBack(); jsonResponse(null,'登录已失效，请重新登录',401); }
        $pdo->prepare('DELETE FROM push_devices WHERE provider=? AND (device_id=? OR token_hash=?)')
            ->execute([$v['provider'],$v['device_id'],hash('sha256',$v['device_token'])]);
        $pdo->prepare('INSERT INTO push_devices (user_id,device_id,provider,platform,device_token,token_hash,session_hash,bundle_id,environment,enabled,preview,badge) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)')
            ->execute([$userId,$v['device_id'],$v['provider'],$v['platform'],$v['device_token'],hash('sha256',$v['device_token']),$session,$v['bundle_id'],$v['environment'],$v['enabled'],$v['preview'],$v['badge']]);
        $pdo->commit();
    } catch (Throwable $e) { if ($pdo->inTransaction()) $pdo->rollBack(); throw $e; }
    jsonResponse(['registered'=>true,'delivery_configured'=>pushConfigured()]);
}

function pushConfigured(): bool
{
    return getenv('SPX_APNS_ENABLED') === '1' && preg_match('/^[A-Z0-9]{10}$/D',(string)getenv('SPX_APNS_KEY_ID'))
        && preg_match('/^[A-Z0-9]{10}$/D',(string)getenv('SPX_APNS_TEAM_ID'))
        && is_readable((string)getenv('SPX_APNS_P8_PATH')) && trim((string)getenv('SPX_APNS_TOPIC')) !== '';
}

function pushEventRoute(array $event): ?array
{
    $m = $event['message'] ?? null; $user = (int)($event['user_id'] ?? 0);
    if (!in_array($event['type'] ?? '', ['message','group.message'], true) || !is_array($m)
        || $user <= 0 || (int)($m['id'] ?? 0) <= 0 || (int)($m['sender_id'] ?? 0) === $user) return null;
    $group = max(0,(int)($event['group_id'] ?? 0)); $chat = $group > 0 ? 0 : max(0,(int)($event['chat_id'] ?? 0));
    return $group + $chat > 0 ? ['user'=>$user,'group'=>$group,'chat'=>$chat,'message'=>$m] : null;
}

function pushNotificationAllowed(array $conversation, bool $mentioned, bool $group): bool
{
    // A group mention may bypass mute, but never a block or a direct-chat mute.
    return empty($conversation['blocked']) && (empty($conversation['mute']) || ($group && $mentioned));
}

function pushQueueEvent(array $event): void
{
    if (!pushConfigured() || !($route = pushEventRoute($event))) return;
    $pdo = db(); $m = $route['message']; $uid = $route['user'];
    if ($route['group'] > 0) {
        $q=$pdo->prepare('SELECT gm.mute,g.name FROM chat_group_members gm JOIN chat_groups g ON g.id=gm.group_id WHERE gm.group_id=? AND gm.user_id=?'); $q->execute([$route['group'],$uid]);
    } else {
        $q=$pdo->prepare('SELECT COALESCE(s.mute,0) AS mute,COALESCE(s.blocked,0) AS blocked FROM chats c LEFT JOIN chat_settings s ON s.chat_id=c.id AND s.user_id=c.user_id WHERE c.id=? AND c.user_id=?'); $q->execute([$route['chat'],$uid]);
    }
    $conversation=$q->fetch(); if (!$conversation) return;
    $mentions = $m['mention_user_ids'] ?? []; if (!is_array($mentions)) $mentions=[];
    $mentioned = $route['group'] > 0 && (in_array($uid,array_map('intval',$mentions),true) || in_array(-1,array_map('intval',$mentions),true));
    if (!pushNotificationAllowed($conversation,$mentioned,$route['group'] > 0)) return;
    $q=$pdo->prepare('SELECT * FROM push_devices d WHERE d.user_id=? AND d.provider="apns" AND d.enabled=1 AND EXISTS (SELECT 1 FROM user_tokens t WHERE t.user_id=d.user_id AND SHA2(t.token,256)=d.session_hash AND t.expires_at>NOW())');
    $q->execute([$uid]);
    $insert=$pdo->prepare('INSERT IGNORE INTO push_queue (device_id,message_id,chat_id,group_id,session_hash,payload,available_at,expires_at) VALUES (?,?,?,?,?,?,DATE_ADD(NOW(),INTERVAL 4 SECOND),DATE_ADD(NOW(),INTERVAL 10 MINUTE))');
    foreach ($q->fetchAll() as $device) {
        if (!hash_equals((string)getenv('SPX_APNS_TOPIC'),$device['bundle_id'])) continue;
        $type=(string)($m['message_type'] ?? 'text');
        $preview = ['image'=>'[图片]','video'=>'[视频]','audio'=>'[语音]','file'=>'[文件]','red_packet'=>'[红包]','redpacket'=>'[红包]','location'=>'[位置]'][$type] ?? (string)($m['content'] ?? '[新消息]');
        $name=trim((string)($m['display_name'] ?? $m['nickname'] ?? '新消息')); if ($name==='') $name='新消息';
        $title=$route['group'] > 0 ? (string)($conversation['name'] ?? '群聊') : $name;
        if ($route['group'] > 0) $preview=$name.'：'.$preview;
        $payload=['aps'=>['alert'=>['title'=>mb_substr($title,0,60),'body'=>$device['preview'] ? mb_substr($preview,0,140) : '你收到了一条新消息'],'sound'=>'default','thread-id'=>($route['group'] ? 'g-'.$route['group'] : 'c-'.$route['chat'])],
            'recipient_user_id'=>$uid,'chat_id'=>$route['chat'],'group_id'=>$route['group'],'message_id'=>(int)$m['id']];
        if ($mentioned) $payload['aps']['alert']['subtitle']='有人@你';
        $insert->execute([$device['id'],$m['id'],$route['chat'],$route['group'],$device['session_hash'],json_encode($payload,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)]);
    }
}
