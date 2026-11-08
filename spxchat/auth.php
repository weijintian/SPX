<?php
declare(strict_types=1);
require_once __DIR__ . '/ws-event.php';

function bearerToken(): ?string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    if (preg_match('/Bearer\s+(.+)/i', $header, $matches)) {
        return trim($matches[1]);
    }
    return null;
}

function currentUser(): array
{
    // 同一请求内多处调用只查一次库
    static $cachedUser = null;
    if ($cachedUser !== null) return $cachedUser;
    $token = bearerToken();
    if (!$token) {
        jsonResponse(null, '请先登录', 401);
    }

    $statement = db()->prepare(
        'SELECT u.*, t.expires_at AS token_expires_at FROM user_tokens t JOIN users u ON u.id = t.user_id
         WHERE t.token = ? AND t.expires_at > NOW() LIMIT 1'
    );
    $statement->execute([$token]);
    $user = $statement->fetch();
    if (!$user) {
        jsonResponse(['reason' => 'SESSION_INVALID'], '登录已失效，可能已在其他设备登录，请重新登录', 401);
    }
    if ((int) ($user['status'] ?? 1) !== 1) {
        db()->prepare('DELETE FROM user_tokens WHERE token=?')->execute([$token]);
        jsonResponse(null, '账号已停用，请联系平台客服', 403);
    }
    // 资产行在注册/登录时已创建，这里不再每次请求写库（原先每个请求 1 次 INSERT）
    // 已登录请求按用户维度计入流量统计
    // 活跃续期：剩余不足 6 天时把有效期推回 7 天，活跃用户不会突然掉线。
    // 只有触发续期的那次请求会写库，正常情况下每个用户每天最多一次 UPDATE。
    $tokenExpiresAt = (int) strtotime((string) ($user['token_expires_at'] ?? ''));
    $tokenTtl = (int) ((require __DIR__ . '/config.php')['app']['token_ttl'] ?? 604800);
    if ($tokenExpiresAt > 0 && $tokenTtl > 0 && ($tokenExpiresAt - time()) < $tokenTtl - 86400) {
        try {
            db()->prepare('UPDATE user_tokens SET expires_at = DATE_ADD(NOW(), INTERVAL ? SECOND) WHERE token = ?')
                ->execute([$tokenTtl, $token]);
            $tokenExpiresAt = time() + $tokenTtl;
        } catch (Throwable $renewError) {
            error_log('token renewal failed: ' . $renewError->getMessage());
        }
    }
    unset($user['token_expires_at']);
    if (function_exists('apiTrafficGuard')) apiTrafficGuard((string) ($GLOBALS['api_action'] ?? ''), (int) $user['id']);
    unset($user['password_hash']);
    $cachedUser = $user;
    return $user;
}

/** Serialize logins per account: the last committed login owns the only valid token. */
function issueToken(int $userId): string
{
    $config = require __DIR__ . '/config.php';
    $token = bin2hex(random_bytes(32));
    $expiresAt = date('Y-m-d H:i:s', time() + (int) $config['app']['token_ttl']);
    $pdo = db();
    // Call presence is installed lazily; login must also work before the first call.
    $hasCallPresence = (bool) $pdo->query("SHOW TABLES LIKE 'chat_call_presence'")->fetchColumn();
    if ($hasCallPresence && !function_exists('callFinish')) require_once __DIR__ . '/chat_calls.php';
    $pdo->beginTransaction();
    // 登录时确保三种资产行齐全（新币种上线后老用户自动补行），每个登录只写一次
    $pdo->prepare('INSERT IGNORE INTO wallet_assets (user_id,symbol,chain,balance) VALUES (?,"SPX001","BSC",0),(?,"USDT","BSC",0),(?,"FISS","BSC",0)')
        ->execute([$userId, $userId, $userId]);
    $sessionEvents = [];
    try {
        $lock = $pdo->prepare('SELECT id FROM users WHERE id=? FOR UPDATE');
        $lock->execute([$userId]);
        if (!$lock->fetchColumn()) throw new RuntimeException('Login account does not exist');
        if ($hasCallPresence && function_exists('callFinish')) {
            $callsTable = (bool)$pdo->query("SHOW TABLES LIKE 'chat_calls'")->fetchColumn();
            if ($callsTable) {
                $activeCalls = $pdo->prepare('SELECT * FROM chat_calls WHERE (caller_id=? OR callee_id=?) AND state IN ("ringing","connected") FOR UPDATE');
                $activeCalls->execute([$userId,$userId]);
                foreach ($activeCalls->fetchAll(PDO::FETCH_ASSOC) as $call) {
                    // Only unclaimed incoming invitations survive login. Calls owned by
                    // the previous device still end when its credentials are revoked.
                    if (!callCanSurviveRecipientLogin($call, $userId)) callFinish($pdo,$call,'failed',$sessionEvents);
                }
            }
        }
        $pdo->prepare('DELETE FROM user_tokens WHERE user_id=?')->execute([$userId]);
        $pdo->prepare('INSERT INTO user_tokens (user_id, token, expires_at) VALUES (?, ?, ?)')->execute([$userId, $token, $expiresAt]);
        // A new login must not inherit the old device's ready-to-call status.
        if ($hasCallPresence) $pdo->prepare('DELETE FROM chat_call_presence WHERE user_id=?')->execute([$userId]);
        $pdo->prepare('UPDATE user_presence SET is_online=0 WHERE user_id=?')->execute([$userId]);
        $pdo->commit();
    } catch (Throwable $error) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        throw $error;
    }
    if (function_exists('notifyWebSocket')) {
        foreach ($sessionEvents as $event) notifyWebSocket($event);
        notifyWebSocket(['type' => 'auth.session_changed', 'user_id' => $userId]);
    }
    return $token;
}
