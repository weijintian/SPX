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
    $token = bearerToken();
    if (!$token) {
        jsonResponse(null, '请先登录', 401);
    }

    $statement = db()->prepare(
        'SELECT u.* FROM user_tokens t JOIN users u ON u.id = t.user_id
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
    db()->prepare('INSERT IGNORE INTO wallet_assets (user_id,symbol,chain,balance) VALUES (?,"SPX001","BSC",0),(?,"USDT","BSC",0)')->execute([(int) $user['id'], (int) $user['id']]);
    unset($user['password_hash']);
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
