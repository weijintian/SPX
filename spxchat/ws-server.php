<?php
declare(strict_types=1);

require_once __DIR__ . '/db.php';

$config = require __DIR__ . '/config.php';
$publicPort = (int) ($config['app']['websocket_public_port'] ?? 8081);
$eventPort = (int) ($config['app']['websocket_event_port'] ?? 8090);
$publicErrorCode = 0;
$publicErrorMessage = '';
$eventErrorCode = 0;
$eventErrorMessage = '';
// 客户端统一走 nginx 的 /ws 反代（proxy_pass 127.0.0.1:8081），
// 这里只监听本机，避免攻击者直连 8081 建立大量未鉴权连接。
$publicHost = (string) ($config['app']['websocket_bind'] ?? '127.0.0.1');
$public = stream_socket_server("tcp://{$publicHost}:{$publicPort}", $publicErrorCode, $publicErrorMessage);
if (!$public) {
    fwrite(STDERR, "WebSocket public bind failed on {$publicPort}: {$publicErrorMessage} ({$publicErrorCode})\n");
    exit(1);
}
$events = stream_socket_server("tcp://127.0.0.1:{$eventPort}", $eventErrorCode, $eventErrorMessage);
if (!$events) {
    fwrite(STDERR, "WebSocket event bind failed on {$eventPort}: {$eventErrorMessage} ({$eventErrorCode})\n");
    fclose($public);
    exit(1);
}
stream_set_blocking($public, false);
stream_set_blocking($events, false);
fwrite(STDOUT, "SPX WebSocket server started\n");
fwrite(STDOUT, "Public WebSocket: {$publicHost}:{$publicPort}\n");
fwrite(STDOUT, "Internal events: 127.0.0.1:{$eventPort}\n");
fwrite(STDOUT, "Press Ctrl+C to stop.\n");
$clients = [];
$buffers = [];
$users = [];
$handshaken = [];
$tokens = [];
/** 每个连接待发送的字节缓冲：写不进去时排队，避免静默丢消息。 */
$pending = [];
/** 上次鉴权时间，用于把「每 3 秒全量查库」改成有上限的抽样。 */
$lastAuthAt = [];
$authCursor = 0;
const WS_PENDING_LIMIT = 2097152;   // 单连接最多排队 2MB，超过视为慢客户端
const WS_AUTH_SWEEP_SECONDS = 60;   // 鉴权抽样周期
const WS_AUTH_SWEEP_BATCH = 200;    // 每轮最多校验 200 个连接

function wsAcceptKey(string $key): string { return base64_encode(sha1($key . '258EAFA5-E914-47DA-95CA-C5AB0DC85B11', true)); }
function wsFrame(string $payload): string {
    $length = strlen($payload);
    if ($length < 126) return chr(0x81) . chr($length) . $payload;
    if ($length < 65536) return chr(0x81) . chr(126) . pack('n', $length) . $payload;
    return chr(0x81) . chr(127) . pack('J', $length) . $payload;
}
function wsControlFrame(int $opcode, string $payload = ''): string { return chr(0x80 | $opcode) . chr(strlen($payload)) . $payload; }
function wsReadFrames(string &$buffer): array {
    $result = [];
    while (strlen($buffer) >= 2) {
        $first = ord($buffer[0]); $second = ord($buffer[1]);
        $opcode = $first & 15; $masked = ($second & 128) !== 0; $length = $second & 127; $offset = 2;
        if ($length === 126) { if (strlen($buffer) < 4) break; $length = unpack('n', substr($buffer, 2, 2))[1]; $offset = 4; }
        elseif ($length === 127) { if (strlen($buffer) < 10) break; $parts = unpack('N2', substr($buffer, 2, 8)); $length = ($parts[1] * 4294967296) + $parts[2]; $offset = 10; }
        if ($masked) { if (strlen($buffer) < $offset + 4 + $length) break; $mask = substr($buffer, $offset, 4); $offset += 4; }
        else { if (strlen($buffer) < $offset + $length) break; $mask = ''; }
        $payload = substr($buffer, $offset, $length); $buffer = substr($buffer, $offset + $length);
        if ($masked) for ($i = 0; $i < $length; $i++) $payload[$i] = $payload[$i] ^ $mask[$i % 4];
        $result[] = [$opcode, $payload];
    }
    return $result;
}

/** 排队发送：能写就直接写，写不进去就缓存，由 flushPending 重试。 */
function wsSend(int $id, string $frame): void {
    global $clients, $pending;
    if (!isset($clients[$id])) return;
    if (!empty($pending[$id])) { $pending[$id] .= $frame; return; }
    $written = @fwrite($clients[$id], $frame);
    if ($written === false || $written < strlen($frame)) {
        $pending[$id] = substr($frame, $written === false ? 0 : $written);
    }
}

function flushPending(): void {
    global $clients, $pending, $buffers, $users, $handshaken, $tokens, $lastAuthAt;
    foreach ($pending as $id => $chunk) {
        if (!isset($clients[$id])) { unset($pending[$id]); continue; }
        if ($chunk === '') { unset($pending[$id]); continue; }
        $written = @fwrite($clients[$id], $chunk);
        if ($written === false || $written === 0) {
            if (strlen($chunk) > WS_PENDING_LIMIT) {
                @fclose($clients[$id]);
                unset($clients[$id], $buffers[$id], $users[$id], $handshaken[$id], $tokens[$id], $pending[$id], $lastAuthAt[$id]);
            }
            continue;
        }
        $rest = substr($chunk, $written);
        if ($rest === '') unset($pending[$id]); else $pending[$id] = $rest;
    }
}

function dropClient(int $id): void {
    global $clients, $buffers, $users, $handshaken, $tokens, $pending, $lastAuthAt;
    if (isset($clients[$id])) @fclose($clients[$id]);
    unset($clients[$id], $buffers[$id], $users[$id], $handshaken[$id], $tokens[$id], $pending[$id], $lastAuthAt[$id]);
}

/** 把一条事件投递给指定账号的所有连接。 */
function deliverToUser(int $userId, string $frame): void {
    global $users;
    foreach ($users as $clientId => $owner) {
        if ((int) $owner === $userId) wsSend((int) $clientId, $frame);
    }
}

function authenticateSocket(string $token): ?int {
    $statement = db()->prepare('SELECT t.user_id FROM user_tokens t JOIN users u ON u.id=t.user_id WHERE t.token=? AND t.expires_at>NOW() LIMIT 1');
    $statement->execute([$token]);
    $row = $statement->fetch();
    return $row ? (int) $row['user_id'] : null;
}

/** Validate the socket's own token so a delayed login event cannot kick the newest login. */
function revalidateSockets(?int $account = null, int $limit = 0): void {
    global $clients, $buffers, $users, $handshaken, $tokens, $lastAuthAt;
    $checked = 0;
    foreach ($users as $clientId => $userId) {
        if (!$userId || ($account !== null && (int)$userId !== $account)) continue;
        if ($limit > 0 && $checked >= $limit) break;
        $checked++;
        try { $validUser = authenticateSocket($tokens[$clientId] ?? ''); }
        catch (Throwable $error) { continue; } // Database failure is not session revocation.
        if ($validUser === (int)$userId) { $lastAuthAt[$clientId] = time(); continue; }
        wsSend((int) $clientId, wsFrame(json_encode(['type'=>'auth.revoked','status'=>401,'message'=>'登录已失效，可能已在其他设备登录，请重新登录'], JSON_UNESCAPED_UNICODE)));
        dropClient((int) $clientId);
    }
}

/** 定期抽样：每轮最多校验一批连接，避免连接数增长后把主循环拖死。 */
function sweepAuth(): void {
    global $users, $tokens, $lastAuthAt, $authCursor;
    $ids = array_keys($users);
    $total = count($ids);
    if ($total === 0) return;
    $start = $authCursor % $total;
    $checked = 0;
    for ($i = 0; $i < $total && $checked < WS_AUTH_SWEEP_BATCH; $i++) {
        $id = (int) $ids[($start + $i) % $total];
        $userId = (int) ($users[$id] ?? 0);
        if (!$userId) continue;
        // 刚登录/刚鉴权过的连接给 120 秒宽限，避免抽样误判影响新会话
        if (time() - (int) ($lastAuthAt[$id] ?? 0) < 120) { $checked++; continue; }
        $checked++;
        try { $valid = authenticateSocket($tokens[$id] ?? ''); } catch (Throwable $error) { continue; }
        if ($valid === $userId) { $lastAuthAt[$id] = time(); continue; }
        wsSend($id, wsFrame(json_encode(['type'=>'auth.revoked','status'=>401,'message'=>'登录已失效，可能已在其他设备登录，请重新登录'], JSON_UNESCAPED_UNICODE)));
        dropClient($id);
    }
    $authCursor = $start + max(1, $checked);
}

while (true) {
    $read = [$public, $events];
    foreach ($clients as $client) $read[] = $client;
    $write = $except = [];
    @stream_select($read, $write, $except, 1);
    flushPending();
    foreach ($read as $socket) {
        if ($socket === $public) {
            $client = @stream_socket_accept($public, 0);
            if (!$client) continue;
            stream_set_blocking($client, false);
            $id = (int) $client; $clients[$id] = $client; $buffers[$id] = ''; $users[$id] = null; $handshaken[$id] = false;
            continue;
        }
        if ($socket === $events) {
            $eventSocket = @stream_socket_accept($events, 0);
            if (!$eventSocket) continue;
            stream_set_timeout($eventSocket, 1);
            $line = fgets($eventSocket);
            fclose($eventSocket);
            $event = json_decode(trim((string) $line), true);
            if (!is_array($event)) continue;
            // 批量事件：一次连接携带多条「账号 -> 事件」，避免群消息按成员逐个开连接。
            if (($event['type'] ?? '') === 'batch' && isset($event['items']) && is_array($event['items'])) {
                foreach ($event['items'] as $item) {
                    if (!is_array($item)) continue;
                    $targetUser = (int) ($item['u'] ?? 0);
                    $payload = $item['e'] ?? null;
                    if ($targetUser <= 0 || !is_array($payload)) continue;
                    deliverToUser($targetUser, wsFrame(json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)));
                }
                continue;
            }
            if (!isset($event['user_id'])) continue;
            revalidateSockets((int)$event['user_id']);
            if (($event['type'] ?? '') === 'auth.session_changed') continue;
            deliverToUser((int) $event['user_id'], wsFrame(json_encode($event, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)));
            continue;
        }
        $id = (int) $socket;
        if (!isset($clients[$id])) continue;
        $chunk = @fread($socket, 8192);
        if ($chunk === '' || $chunk === false) { dropClient($id); continue; }
        $buffers[$id] .= $chunk;
        if (!$handshaken[$id] && strpos($buffers[$id], "\r\n\r\n") === false) continue;
        if (!$handshaken[$id]) {
            [$headers, $rest] = explode("\r\n\r\n", $buffers[$id], 2); $buffers[$id] = $rest;
            preg_match('/Sec-WebSocket-Key:\s*(.+)/i', $headers, $match);
            if (!$match) { dropClient($id); continue; }
            fwrite($socket, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " . wsAcceptKey(trim($match[1])) . "\r\n\r\n");
            $handshaken[$id] = true;
        }
        foreach (wsReadFrames($buffers[$id]) as [$opcode, $payload]) {
            if ($opcode === 8) { dropClient($id); continue 2; }
            if ($opcode === 9) { wsSend($id, wsControlFrame(10, substr($payload, 0, 125))); continue; }
            if ($opcode === 10) continue;
            if ($opcode !== 1) continue;
            $message = json_decode($payload, true);
            if ($users[$id] === null && is_array($message) && ($message['type'] ?? '') === 'auth') {
                $tokens[$id] = (string) ($message['token'] ?? '');
                $users[$id] = authenticateSocket($tokens[$id]);
                if (!$users[$id]) { wsSend($id, wsFrame(json_encode(['type' => 'auth.revoked', 'status' => 401, 'message' => '登录已失效，请重新登录'], JSON_UNESCAPED_UNICODE))); }
                else { $lastAuthAt[$id] = time(); wsSend($id, wsFrame(json_encode(['type' => 'ready'], JSON_UNESCAPED_UNICODE))); }
            }
        }
    }
    if (time() - ($lastAuthCheck ?? 0) >= WS_AUTH_SWEEP_SECONDS) {
        sweepAuth();
        $lastAuthCheck = time();
    }
    if (time() - ($lastPing ?? 0) >= 25) {
        foreach (array_keys($clients) as $clientId) wsSend((int) $clientId, wsControlFrame(9));
        $lastPing = time();
    }
}
