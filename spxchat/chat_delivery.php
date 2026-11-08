<?php
declare(strict_types=1);
require_once __DIR__ . '/chat_content.php';
require_once __DIR__ . '/chat_replies.php';

function chatQuery(PDO $pdo, string $sql, array $params = []): PDOStatement
{ $q = $pdo->prepare($sql); $q->execute($params); return $q; }

function chatAccess(PDO $pdo, int $uid, string $scope, int $id): array
{
    if ($scope === 'group') {
        $m = chatQuery($pdo, 'SELECT role,mute FROM chat_group_members WHERE group_id=? AND user_id=?', [$id,$uid])->fetch();
        if (!$m) throw new DomainException('你已不在该群组中', 403);
        if ((int)$m['mute'] === 1 && !in_array($m['role'], ['owner','admin'], true)) throw new DomainException('你已被群管理员禁言',403);
        return $m;
    }
    if ($scope !== 'chat') throw new InvalidArgumentException('会话类型无效');
    $c = chatQuery($pdo, 'SELECT * FROM chats WHERE id=? AND user_id=?', [$id,$uid])->fetch();
    if (!$c) throw new DomainException('会话不存在',404);
    $peer = (int)$c['peer_user_id'];
    if (!chatQuery($pdo, 'SELECT 1 FROM contacts a JOIN contacts b ON b.user_id=a.contact_user_id AND b.contact_user_id=a.user_id WHERE a.user_id=? AND a.contact_user_id=? AND a.status="accepted" AND b.status="accepted"',[$uid,$peer])->fetch()) throw new DomainException('请先添加好友后再发送消息',403);
    if (chatQuery($pdo, 'SELECT g.id FROM chat_groups g JOIN chat_group_members a ON a.group_id=g.id AND a.user_id=? JOIN chat_group_members b ON b.group_id=g.id AND b.user_id=? WHERE g.allow_private_message=0 LIMIT 1',[$uid,$peer])->fetch()) throw new DomainException('共同群组已禁止成员互相私信',403);
    if (chatQuery($pdo, 'SELECT 1 FROM chat_settings s JOIN chats c ON c.id=s.chat_id AND c.user_id=s.user_id WHERE s.blocked=1 AND ((c.user_id=? AND c.peer_user_id=?) OR (c.user_id=? AND c.peer_user_id=?)) LIMIT 1',[$uid,$peer,$peer,$uid])->fetch()) throw new DomainException('该会话暂不允许发送消息',403);
    return $c;
}

/** Serialize delivery, schedule cancellation and bot cooldown updates together. */
function chatAtomic(PDO $pdo, callable $work, ?string $lockKey = null): mixed
{
    // 传入 lockKey 时只锁该会话，不同会话可并行投递；不传则沿用全局锁（兼容旧调用）。
    $lockName = 'spx_chat_delivery_v2:' . ($lockKey !== null && $lockKey !== '' ? $lockKey : 'global');
    if ((int)$pdo->query('SELECT GET_LOCK(' . $pdo->quote($lockName) . ',5)')->fetchColumn() !== 1) throw new DomainException('消息服务繁忙，请稍后重试',503);
    $events = [];
    try {
        $pdo->beginTransaction();
        $result = $work($events);
        $pdo->commit();
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        throw $e;
    } finally { $pdo->query('SELECT RELEASE_LOCK(' . $pdo->quote($lockName) . ')'); }
    if (function_exists('notifyWebSocketBatch')) notifyWebSocketBatch($events);
    else foreach ($events as $event) notifyWebSocket($event);
    return $result;
}

function chatNormalize(PDO $pdo, array $body, int $userId = 0): array
{
    $type = (string)($body['message_type'] ?? 'text');
    if (!in_array($type,['text','image','video','voice','file','product','red_packet'],true)) throw new InvalidArgumentException('消息类型不正确');
    $content = $type === 'product' ? chatProductCard($pdo,(int)($body['product_id'] ?? 0)) :
        ($type === 'text' && isset($body['share_group_id']) ? chatGroupCard($pdo, $userId, (int)$body['share_group_id']) : chatText($body['content'] ?? ''));
    if ($type === 'text' && str_starts_with($content, 'SPXGROUP:') && !isset($body['share_group_id'])) {
        // Older apps share this envelope; replace all supplied names/counts with authoritative data too.
        $code = trim(explode("\n", substr($content, 9), 2)[0]);
        $groupId = (int)chatQuery($pdo, 'SELECT id FROM chat_groups WHERE group_code=?', [$code])->fetchColumn();
        $content = chatGroupCard($pdo, $userId, $groupId);
    }
    $media = null;
    if (in_array($type,['image','video','voice','file'],true)) {
        $media = mallMediaUrl((string)($body['media_url'] ?? ''));
        if ($media === '') throw new InvalidArgumentException('媒体消息缺少地址');
    }
    $client = trim((string)($body['client_message_id'] ?? ''));
    if ($client === '') $client = bin2hex(random_bytes(16));
    if (!preg_match('/^[a-zA-Z0-9_-]{8,36}$/D',$client)) throw new InvalidArgumentException('消息标识无效');
    return [$type,$content,$media,$client];
}

/** Called within chatAtomic; origin is server-owned and cannot be set by clients. */
function chatDeliver(PDO $pdo, int $uid, string $scope, int $target, array $body, array &$events, string $origin='human'): array
{
    if (($body['message_type']??'text')==='red_packet' && $origin!=='red_packet') throw new InvalidArgumentException('红包必须从发红包入口创建');
    $access = chatAccess($pdo,$uid,$scope,$target);
    [$type,$content,$media,$client] = chatNormalize($pdo,$body,$uid);
    if ($origin !== 'call' && str_starts_with($client, 'call-')) throw new InvalidArgumentException('消息标识使用了保留前缀');
    if ($origin==='human' && (str_starts_with($client,'schedule-') || str_starts_with($client,'welcome-'))) throw new InvalidArgumentException('消息标识使用了保留前缀');
    $group = $scope === 'group';
    $table = $group ? 'chat_group_messages' : 'messages';
    $existing = chatQuery($pdo, 'SELECT * FROM ' . $table . ' WHERE ' . ($group ? 'group_id' : 'user_id') . '=? AND sender_id=? AND client_message_id=?',[$group?$target:$uid,$uid,$client])->fetch();
    if ($existing) {
        if (!$group && (int)$existing['chat_id'] !== $target) throw new DomainException('消息标识已用于其他会话',409);
        if($existing['message_origin']!==$origin) throw new DomainException('消息标识已用于其他发送任务',409);
        if ($group) $existing['mention_user_ids'] = json_decode((string)($existing['mention_user_ids'] ?? '[]'), true) ?: [];
        $canonical=chatReplyDecorate($pdo,[$existing],$uid,$scope,$target)[0];
        return array_merge($canonical,['id'=>(int)$existing['id'],'client_message_id'=>$client,'duplicate'=>true]);
    }
    $replySource = chatReplySource($pdo,$uid,$scope,$target,max(0,(int)($body['reply_to_message_id'] ?? 0)));
    $mentions = [];
    if ($group) {
        $ids = array_slice(array_unique(array_map('intval',is_array($body['mention_user_ids'] ?? null)?$body['mention_user_ids']:[])),0,100);
        // -1 is a server-validated all-members marker; it never becomes a fake user ID.
        // Expand against current membership so existing Android clients also receive the reminder.
        if ($type === 'text' && in_array(-1, $ids, true) && str_contains($content, '@全体成员')) {
            $mentions = array_map('intval', chatQuery($pdo,'SELECT user_id FROM chat_group_members WHERE group_id=? AND user_id<>?',[$target,$uid])->fetchAll(PDO::FETCH_COLUMN));
            $mentions[] = -1;
        } elseif ($type === 'text') {
            $ids = array_values(array_filter($ids, static fn(int $id): bool => $id > 0 && $id !== $uid));
            if ($ids) $mentions = array_map('intval',chatQuery($pdo,'SELECT user_id FROM chat_group_members WHERE group_id=? AND user_id IN ('.implode(',',array_fill(0,count($ids),'?')).')',array_merge([$target],$ids))->fetchAll(PDO::FETCH_COLUMN));
        }
        chatQuery($pdo,'INSERT INTO chat_group_messages (group_id,sender_id,client_message_id,content,message_type,media_url,mention_user_ids,message_origin) VALUES (?,?,?,?,?,?,?,?)',[$target,$uid,$client,$content,$type,$media,json_encode($mentions),$origin]);
        $id = (int)$pdo->lastInsertId();
        if ($replySource) chatQuery($pdo,'UPDATE chat_group_messages SET reply_to_message_id=? WHERE id=?',[(int)$replySource['id'],$id]);
        $m = chatQuery($pdo,'SELECT m.*,u.nickname,u.avatar_url FROM chat_group_messages m JOIN users u ON u.id=m.sender_id WHERE m.id=?',[$id])->fetch();
        $m['mention_user_ids'] = $mentions;
        $m = chatReplyDecorate($pdo,[$m],$uid,$scope,$target)[0];
        chatQuery($pdo,'UPDATE chat_group_members SET unread_count=unread_count+1 WHERE group_id=? AND user_id<>?',[$target,$uid]);
        foreach (chatQuery($pdo,'SELECT user_id FROM chat_group_members WHERE group_id=?',[$target])->fetchAll(PDO::FETCH_COLUMN) as $member) $events[] = ['type'=>'message','user_id'=>(int)$member,'group_id'=>$target,'message'=>$m];
    } else {
        $peer = (int)$access['peer_user_id'];
        $peerChat = (int)chatQuery($pdo,'SELECT id FROM chats WHERE user_id=? AND peer_user_id=? LIMIT 1',[$peer,$uid])->fetchColumn();
        if (!$peerChat) {
            $name = chatQuery($pdo,'SELECT nickname FROM users WHERE id=?',[$uid])->fetchColumn();
            chatQuery($pdo,'INSERT INTO chats (user_id,peer_user_id,title) VALUES (?,?,?)',[$peer,$uid,$name]);
            $peerChat = (int)$pdo->lastInsertId();
        }
        $id = 0;
        foreach ([[$uid,$target],[$peer,$peerChat]] as [$owner,$chat]) {
            chatQuery($pdo,'INSERT INTO messages (chat_id,user_id,sender_id,client_message_id,content,message_type,media_url,message_origin) VALUES (?,?,?,?,?,?,?,?)',[$chat,$owner,$uid,$client,$content,$type,$media,$origin]);
            $rowId = (int)$pdo->lastInsertId();
            if ($replySource) {
                $replyId = $owner === $uid ? (int)$replySource['id'] : 0;
                if ($owner !== $uid && !empty($replySource['client_message_id'])) $replyId = (int)chatQuery($pdo,'SELECT id FROM messages WHERE user_id=? AND chat_id=? AND sender_id=? AND client_message_id=? LIMIT 1',[$owner,$chat,(int)$replySource['sender_id'],$replySource['client_message_id']])->fetchColumn();
                if ($replyId > 0) chatQuery($pdo,'UPDATE messages SET reply_to_message_id=? WHERE id=?',[$replyId,$rowId]);
            }
            if ($owner===$uid) $id=$rowId;
            chatQuery($pdo,'UPDATE chats SET last_message=?,unread_count=unread_count+?,updated_at=NOW() WHERE id=?',[chatMessagePreview($type,$content),$owner===$uid?0:1,$chat]);
            $m = chatQuery($pdo,'SELECT m.*,u.nickname,u.avatar_url FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.id=?',[$rowId])->fetch();
            $m = chatReplyDecorate($pdo,[$m],$owner,$scope,$chat)[0];
            $events[]=['type'=>'message','user_id'=>$owner,'chat_id'=>$chat,'message'=>$m];
        }
    }
    if ($type==='text' && $origin==='human' && !str_starts_with($content,'SPXGROUP:')) chatAutoReply($pdo,$uid,$scope,$target,$content,$events);
    $response=['id'=>$id,'client_message_id'=>$client,'message_type'=>$type,'media_url'=>$media,'content'=>$content,'message_origin'=>$origin,'created_at'=>$m['created_at'],'reply_to_message_id'=>(int)($replySource['id'] ?? 0)];
    if ($group) $response['mention_user_ids'] = $mentions;
    if($replySource)$response['reply']=chatReplyDecorate($pdo,[['reply_to_message_id'=>(int)$replySource['id']]],$uid,$scope,$target)[0]['reply'];
    return $response;
}
