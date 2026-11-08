<?php
declare(strict_types=1);
require_once __DIR__ . '/chat_names.php';

function chatReplySource(PDO $pdo, int $uid, string $scope, int $target, int $id): ?array
{
    if ($id <= 0) return null;
    $sql = $scope === 'group' ? 'SELECT * FROM chat_group_messages WHERE group_id=? AND id=?' : 'SELECT * FROM messages WHERE user_id=? AND chat_id=? AND id=?';
    $params = $scope === 'group' ? [$target,$id] : [$uid,$target,$id];
    $source = chatQuery($pdo,$sql,$params)->fetch();
    if (!$source) throw new DomainException('引用的消息已删除或不属于当前会话',422);
    return $source;
}

/** Resolve each reference inside its owner's conversation; never expose another copy's ID. */
function chatReplyDecorate(PDO $pdo, array $rows, int $uid, string $scope, int $target): array
{
    if ($scope === 'group') $rows = chatViewerNames($pdo, $rows, $uid);
    foreach ($rows as &$row) unset($row['reply']);
    unset($row);
    $ids = array_values(array_unique(array_filter(array_map('intval',array_column($rows,'reply_to_message_id')))));
    if (!$ids) return $rows;
    $where = $scope === 'group' ? 'm.group_id=?' : 'm.user_id=? AND m.chat_id=?';
    $params = $scope === 'group' ? [$target] : [$uid,$target];
    $params = array_merge($params,$ids);
    $refs = chatQuery($pdo,'SELECT m.id,m.sender_id,m.content,m.message_type,u.nickname FROM ' . ($scope === 'group' ? 'chat_group_messages' : 'messages') . ' m JOIN users u ON u.id=m.sender_id WHERE ' . $where . ' AND m.id IN (' . implode(',',array_fill(0,count($ids),'?')) . ')',$params)->fetchAll();
    if ($scope === 'group') $refs = chatViewerNames($pdo, $refs, $uid);
    $byId = [];
    foreach ($refs as $ref) {
        $preview = $ref['message_type'] === 'text' ? mb_substr((string)$ref['content'],0,120,'UTF-8') : chatMessagePreview((string)$ref['message_type'],(string)$ref['content']);
        $byId[(int)$ref['id']] = ['id'=>(int)$ref['id'],'sender_id'=>(int)$ref['sender_id'],'nickname'=>$ref['nickname'],'original_nickname'=>$ref['original_nickname'] ?? $ref['nickname'],'preview'=>$preview,'available'=>true];
    }
    foreach ($rows as &$row) {
        $reply = (int)($row['reply_to_message_id'] ?? 0);
        if ($reply > 0) $row['reply'] = $byId[$reply] ?? ['id'=>$reply,'nickname'=>'','preview'=>'原消息已删除','available'=>false];
    }
    unset($row);
    return $rows;
}
