<?php
declare(strict_types=1);

/** Read authorization intentionally permits muted members to read history. */
function chatReadAccess(PDO $pdo, int $uid, string $scope, int $id): void
{
    $sql = $scope === 'group' ? 'SELECT 1 FROM chat_group_members WHERE group_id=? AND user_id=?' : 'SELECT 1 FROM chats WHERE id=? AND user_id=?';
    if ($id <= 0 || !chatQuery($pdo, $sql, [$id, $uid])->fetchColumn()) throw new DomainException('无权访问该会话', 403);
}

function chatReadRows(PDO $pdo, int $uid, string $scope, int $id, string $condition, array $values, string $order, int $limit): array
{
    if ($limit <= 0) return [];
    $group = $scope === 'group';
    $where = $group ? 'm.group_id=?' : 'm.chat_id=? AND m.user_id=?';
    $args = $group ? [$id] : [$id, $uid];
    if ($group) {
        // 群里「仅本地删除」的消息：只对当前用户隐藏，不影响其他成员
        $where .= ' AND m.id NOT IN (SELECT h.message_id FROM chat_group_message_hides h WHERE h.user_id=?)';
        $args[] = $uid;
    }
    $sql = 'SELECT m.*,u.nickname,u.avatar_url FROM ' . ($group ? 'chat_group_messages' : 'messages') . ' m JOIN users u ON u.id=m.sender_id WHERE ' . $where . $condition . ' ORDER BY m.id ' . $order . ' LIMIT ' . max(1, min(200, $limit));
    $rows = chatQuery($pdo, $sql, array_merge($args, $values))->fetchAll();
    if ($group) foreach ($rows as &$row) $row['mention_user_ids'] = json_decode((string)($row['mention_user_ids'] ?? '[]'), true) ?: [];
    unset($row);
    return chatReplyDecorate($pdo,$rows,$uid,$scope,$id);
}

/** Bounded authoritative reconciliation catches offline edits/deletions, including old IDs. */
function chatSyncApi(string $action, string $method): void
{
    if (!in_array($action, ['messages', 'groups.messages', 'messages.reconcile', 'groups.messages.reconcile', 'messages.read', 'groups.messages.read'], true)) return;
    if (str_ends_with($action,'.read')) { chatReadReceipt($action,$method); return; }
    if ($method !== 'GET') jsonResponse(null, '请求方式不正确', 405);
    $user = currentUser(); $pdo = db();
    $scope = str_starts_with($action, 'groups.') ? 'group' : 'chat';
    $id = (int)($_GET[$scope === 'group' ? 'group_id' : 'chat_id'] ?? 0);
    try { chatReadAccess($pdo, (int)$user['id'], $scope, $id); } catch (DomainException $e) { jsonResponse(null, $e->getMessage(), 403); }
    if (str_ends_with($action, '.reconcile')) {
        $raw = explode(',', (string)($_GET['ids'] ?? ''));
        if (count($raw) > 200) jsonResponse(null, '每次最多校验 200 条消息', 422);
        $ids = array_values(array_unique(array_filter(array_map('intval', $raw), static fn(int $v): bool => $v > 0)));
        if (!$ids) jsonResponse(['messages' => [], 'removed_ids' => []]);
        $rows = chatReadRows($pdo, (int)$user['id'], $scope, $id, ' AND m.id IN (' . implode(',', array_fill(0, count($ids), '?')) . ')', $ids, 'ASC', 200);
        $present = array_map('intval', array_column($rows, 'id'));
        jsonResponse(['messages' => $rows, 'removed_ids' => array_values(array_diff($ids, $present))]);
    }
    $after = max(0, (int)($_GET['after_id'] ?? 0));
    $before = max(0, (int)($_GET['before_id'] ?? 0));
    $around = max(0, (int)($_GET['around_id'] ?? 0));
    $limit = max(1, min(200, (int)($_GET['limit'] ?? 200)));
    if (($after > 0 ? 1 : 0) + ($before > 0 ? 1 : 0) + ($around > 0 ? 1 : 0) > 1) jsonResponse(null, '分页游标不能同时使用', 422);
    if ($around > 0) {
        if (!chatReadRows($pdo, (int)$user['id'], $scope, $id, ' AND m.id=?', [$around], 'ASC', 1)) jsonResponse(null, '消息已撤回或不可访问', 404);
        $half = max(1, (int)ceil($limit / 2));
        $older = array_reverse(chatReadRows($pdo, (int)$user['id'], $scope, $id, ' AND m.id<=?', [$around], 'DESC', $half));
        $newer = chatReadRows($pdo, (int)$user['id'], $scope, $id, ' AND m.id>?', [$around], 'ASC', $limit - $half);
        jsonResponse(array_merge($older, $newer));
    }
    if ($before > 0) jsonResponse(array_reverse(chatReadRows($pdo, (int)$user['id'], $scope, $id, ' AND m.id<?', [$before], 'DESC', $limit)));
    if ($after > 0) jsonResponse(chatReadRows($pdo, (int)$user['id'], $scope, $id, ' AND m.id>?', [$after], 'ASC', $limit));
    jsonResponse(array_reverse(chatReadRows($pdo, (int)$user['id'], $scope, $id, '', [], 'DESC', $limit)));
}

/** Lock the unread counter row while acknowledging only the rendered message boundary. */
function chatReadReceipt(string $action, string $method): void
{
    if ($method !== 'POST') jsonResponse(null,'请求方式不正确',405);
    $user=currentUser(); $uid=(int)$user['id']; $body=requestBody();
    $scope=str_starts_with($action,'groups.')?'group':'chat'; $id=(int)($body[$scope==='group'?'group_id':'chat_id'] ?? 0); $pdo=db();
    try {chatReadAccess($pdo,$uid,$scope,$id);} catch(DomainException $e){jsonResponse(null,$e->getMessage(),403);}
    $group=$scope==='group';$table=$group?'chat_group_members':'chats';$key=$group?'group_id':'id';
    $receipt=chatAtomic($pdo, function(array &$events) use($pdo,$table,$key,$id,$uid,$group,$body): array {
    $row=chatQuery($pdo,"SELECT last_read_message_id FROM $table WHERE $key=? AND user_id=? FOR UPDATE",[$id,$uid])->fetch();
    if (!$row) throw new DomainException('会话已失效，请返回会话列表',403);
    $where=$group?'group_id=?':'chat_id=? AND user_id=?';$args=$group?[$id]:[$id,$uid];$messages=$group?'chat_group_messages':'messages';
    $latest=(int)chatQuery($pdo,"SELECT COALESCE(MAX(id),0) FROM $messages WHERE $where",$args)->fetchColumn();
    $through=array_key_exists('through_id',$body)?min($latest,max(0,(int)$body['through_id'])):$latest;
    $through=max((int)$row['last_read_message_id'],$through);
    $unread=(int)chatQuery($pdo,"SELECT COUNT(*) FROM $messages WHERE $where AND id>? AND sender_id<>?",array_merge($args,[$through,$uid]))->fetchColumn();
    chatQuery($pdo,"UPDATE $table SET last_read_message_id=?,unread_count=? WHERE $key=? AND user_id=?",[$through,$unread,$id,$uid]);
    return ['last_read_message_id'=>$through,'unread_count'=>$unread];
    });
    jsonResponse($receipt,'消息已读');
}
