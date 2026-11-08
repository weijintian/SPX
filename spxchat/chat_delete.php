<?php
declare(strict_types=1);
/**
 * 聊天记录「仅本地删除」（批量）
 *
 * 语义：只影响当前用户，对方/群成员不受影响。
 *   - 私聊：每条消息双方各存一份，删自己的行即可。
 *   - 群聊：所有人共享一行，用 chat_group_message_hides 记录「我隐藏了哪条」。
 */

function chatDeleteUnits(array $ids): array
{
    $clean = [];
    foreach ($ids as $value) {
        $id = (int) $value;
        if ($id > 0) $clean[$id] = true;
    }
    $list = array_keys($clean);
    if (!$list) throw new InvalidArgumentException('请选择要删除的消息');
    if (count($list) > 200) throw new InvalidArgumentException('每次最多删除 200 条消息');
    return $list;
}

function chatDeleteApi(string $action, string $method, array $body): void
{
    if (!in_array($action, ['messages.delete.local', 'groups.messages.delete.local', 'groups.messages.clear'], true)) return;
    if ($method !== 'POST') jsonResponse(null, '请求方式不正确', 405);

    $user = currentUser();
    $uid = (int) $user['id'];
    $pdo = db();
    $isGroup = str_starts_with($action, 'groups.');

    if ($isGroup) {
        $groupId = (int) ($body['group_id'] ?? 0);
        if ($groupId <= 0) jsonResponse(null, '请选择群聊', 422);
        try { chatReadAccess($pdo, $uid, 'group', $groupId); } catch (DomainException $e) { jsonResponse(null, $e->getMessage(), 403); }

        if ($action === 'groups.messages.clear') {
            // 清空：把当前群内所有可见消息标记为「我已隐藏」，不动其他人的视图
            $limit = (int) ($body['through_id'] ?? 0);
            $sql = 'INSERT IGNORE INTO chat_group_message_hides (user_id,message_id)
                    SELECT ?, id FROM chat_group_messages WHERE group_id=?';
            $args = [$uid, $groupId];
            if ($limit > 0) { $sql .= ' AND id<=?'; $args[] = $limit; }
            $stmt = $pdo->prepare($sql);
            $stmt->execute($args);
            $last = $pdo->prepare('SELECT COALESCE(MAX(m.id),0) FROM chat_group_messages m WHERE m.group_id=?');
            $last->execute([$groupId]);
            $pdo->prepare('UPDATE chat_group_members SET unread_count=0 WHERE group_id=? AND user_id=?')->execute([$groupId, $uid]);
            jsonResponse(['hidden' => $stmt->rowCount()], '聊天记录已清除（仅自己）');
        }

        $ids = chatDeleteUnits((array) ($body['message_ids'] ?? []));
        $exists = $pdo->prepare('SELECT COUNT(*) FROM chat_group_messages WHERE group_id=? AND id IN (' . implode(',', array_fill(0, count($ids), '?')) . ')');
        $exists->execute(array_merge([$groupId], $ids));
        if ((int) $exists->fetchColumn() === 0) jsonResponse(null, '所选消息不存在', 404);

        $insert = $pdo->prepare('INSERT IGNORE INTO chat_group_message_hides (user_id,message_id) VALUES (?,?)');
        $hidden = 0;
        foreach ($ids as $id) { $insert->execute([$uid, $id]); $hidden += $insert->rowCount(); }
        jsonResponse(['hidden' => $hidden, 'message_ids' => $ids], '已删除所选记录（仅自己）');
    }

    // 私聊
    $chatId = (int) ($body['chat_id'] ?? 0);
    if ($chatId <= 0) jsonResponse(null, '请选择会话', 422);
    $chat = $pdo->prepare('SELECT id,peer_user_id FROM chats WHERE id=? AND user_id=? LIMIT 1');
    $chat->execute([$chatId, $uid]);
    $chatRow = $chat->fetch();
    if (!$chatRow) jsonResponse(null, '会话不存在', 404);

    if ($action === 'messages.delete.local' && isset($body['all']) && (int) $body['all'] === 1) {
        $stmt = $pdo->prepare('DELETE FROM messages WHERE chat_id=? AND user_id=?');
        $stmt->execute([$chatId, $uid]);
        $pdo->prepare('UPDATE chats SET last_message=NULL WHERE id=? AND user_id=?')->execute([$chatId, $uid]);
        jsonResponse(['deleted' => $stmt->rowCount()], '聊天记录已清空（仅自己）');
    }

    $ids = chatDeleteUnits((array) ($body['message_ids'] ?? []));
    $stmt = $pdo->prepare('DELETE FROM messages WHERE chat_id=? AND user_id=? AND id IN (' . implode(',', array_fill(0, count($ids), '?')) . ')');
    $stmt->execute(array_merge([$chatId, $uid], $ids));
    $deleted = $stmt->rowCount();

    // 重新计算会话列表的最后一条预览（只影响自己）
    $last = $pdo->prepare('SELECT content,message_type FROM messages WHERE chat_id=? AND user_id=? ORDER BY id DESC LIMIT 1');
    $last->execute([$chatId, $uid]);
    $latestRow = $last->fetch();
    $preview = $latestRow ? chatMessagePreview((string) $latestRow['message_type'], (string) $latestRow['content']) : null;
    $pdo->prepare('UPDATE chats SET last_message=? WHERE id=? AND user_id=?')->execute([$preview, $chatId, $uid]);

    jsonResponse(['deleted' => $deleted, 'message_ids' => $ids], '已删除所选记录（仅自己）');
}
