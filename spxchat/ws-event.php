<?php
declare(strict_types=1);
require_once __DIR__ . '/chat_names.php';

function personalizeWebSocketEvent(PDO $pdo, array $event): array
{
    if (empty($event['group_id']) || !isset($event['message']) || !is_array($event['message']) || (int)($event['user_id'] ?? 0) <= 0) return $event;
    // Both the sender and any quoted sender must use the recipient's remarks.
    if (function_exists('chatReplyDecorate')) {
        $event['message'] = chatReplyDecorate($pdo, [$event['message']], (int)$event['user_id'], 'group', (int)$event['group_id'])[0];
    } else {
        $event['message'] = chatViewerNames($pdo, [$event['message']], (int)$event['user_id'])[0];
        unset($event['message']['reply']);
    }
    return $event;
}

/** 一次连接把事件写进 WS 事件端口；失败不影响业务。 */
function wsPushSend(array $payload): void
{
    try {
        $config = require __DIR__ . '/config.php';
        $host = (string) ($config['app']['websocket_event_host'] ?? '127.0.0.1');
        $port = (int) ($config['app']['websocket_event_port'] ?? 8090);
        $socket = @fsockopen($host, $port, $errorCode, $errorMessage, 0.2);
        if (!$socket) return;
        stream_set_timeout($socket, 1);
        fwrite($socket, json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n");
        fclose($socket);
    } catch (Throwable $ignored) {
    }
}

function notifyWebSocket(array $event): void
{
    try {
        if (!empty($event['group_id']) && isset($event['message']) && is_array($event['message']) && (int)($event['user_id'] ?? 0) > 0) {
            $event = personalizeWebSocketEvent(db(), $event);
        }
        // Queueing is independent of WebSocket availability and cannot fail a chat send.
        try { if (function_exists('pushQueueEvent')) pushQueueEvent($event); } catch (Throwable $ignored) {}
        wsPushSend($event);
    } catch (Throwable $ignored) {
    }
}

/**
 * 批量投递：把同一条群消息按成员生成的多条事件合并成「一次连接、多份帧」。
 * - 备注名通过一条 SQL 预取，避免每个成员查一次库
 * - 推送到客户端的内容与单条投递完全一致（客户端无需改动）
 */
function notifyWebSocketBatch(array $events): void
{
    if (!$events) return;
    $groupItems = [];
    $singles = [];
    foreach ($events as $event) {
        if (!is_array($event)) continue;
        try { if (function_exists('pushQueueEvent')) pushQueueEvent($event); } catch (Throwable $ignored) {}
        $isGroupMessage = ($event['type'] ?? '') === 'message'
            && (int)($event['group_id'] ?? 0) > 0
            && isset($event['message']) && is_array($event['message'])
            && (int)($event['user_id'] ?? 0) > 0;
        if ($isGroupMessage) $groupItems[] = $event; else $singles[] = $event;
    }
    foreach ($singles as $event) {
        try { wsPushSend($event); } catch (Throwable $ignored) {}
    }
    if (!$groupItems) return;

    try {
        $pdo = db();
        $viewers = [];
        $contacts = [];
        foreach ($groupItems as $event) {
            $viewers[] = (int) $event['user_id'];
            $message = $event['message'];
            $contacts[] = (int) ($message['sender_id'] ?? 0);
            if (isset($message['reply']) && is_array($message['reply'])) $contacts[] = (int) ($message['reply']['sender_id'] ?? 0);
        }
        $remarks = wsRemarkMap($pdo, $viewers, $contacts);
        $items = [];
        foreach ($groupItems as $event) {
            $viewer = (int) $event['user_id'];
            $event['message'] = wsApplyViewerNames($event['message'], $viewer, $remarks);
            $items[] = ['u' => $viewer, 'e' => $event];
        }
        wsPushSend(['type' => 'batch', 'items' => $items]);
    } catch (Throwable $error) {
        // 批量失败时退化为逐条投递，保证消息一定送达。
        foreach ($groupItems as $event) {
            try {
                $event = personalizeWebSocketEvent(db(), $event);
                wsPushSend($event);
            } catch (Throwable $ignored) {}
        }
    }
}

/** 一次查询取出「观察者 -> 联系人 -> 备注」。 */
function wsRemarkMap(PDO $pdo, array $viewers, array $contacts): array
{
    $viewers = array_values(array_unique(array_filter(array_map('intval', $viewers))));
    $contacts = array_values(array_unique(array_filter(array_map('intval', $contacts))));
    if (!$viewers || !$contacts) return [];
    $sql = 'SELECT user_id,contact_user_id,remark FROM contacts WHERE user_id IN (' . implode(',', array_fill(0, count($viewers), '?'))
        . ') AND contact_user_id IN (' . implode(',', array_fill(0, count($contacts), '?')) . ')';
    $rows = $pdo->prepare($sql);
    $rows->execute(array_merge($viewers, $contacts));
    $map = [];
    foreach ($rows->fetchAll(PDO::FETCH_ASSOC) as $row) {
        $remark = chatContactName((string) ($row['remark'] ?? ''));
        if ($remark !== '') $map[(int) $row['user_id']][(int) $row['contact_user_id']] = $remark;
    }
    return $map;
}

/** 与 chatViewerNames 等价的纯内存装饰。 */
function wsApplyViewerNames(array $message, int $viewer, array $remarks): array
{
    $sender = (int) ($message['sender_id'] ?? 0);
    $nickname = (string) ($message['original_nickname'] ?? $message['nickname'] ?? '');
    $remark = (string) ($remarks[$viewer][$sender] ?? '');
    $message['original_nickname'] = $nickname;
    $message['contact_remark'] = $remark;
    $message['display_name'] = $remark !== '' ? $remark : $nickname;
    $message['nickname'] = $message['display_name'];
    if (isset($message['reply']) && is_array($message['reply'])) {
        $replySender = (int) ($message['reply']['sender_id'] ?? 0);
        $replyNick = (string) ($message['reply']['original_nickname'] ?? $message['reply']['nickname'] ?? '');
        $replyRemark = (string) ($remarks[$viewer][$replySender] ?? '');
        $message['reply']['original_nickname'] = $replyNick;
        $message['reply']['nickname'] = $replyRemark !== '' ? $replyRemark : $replyNick;
    }
    return $message;
}
