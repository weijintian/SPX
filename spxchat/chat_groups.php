<?php
declare(strict_types=1);

/** Call while holding the chat_groups row lock, shared by every admission path. */
function chatGroupAssertNotBanned(PDO $pdo, int $groupId, int $userId): void
{
    if (chatQuery($pdo, 'SELECT 1 FROM chat_group_bans WHERE group_id=? AND user_id=?', [$groupId, $userId])->fetchColumn()) {
        throw new DomainException('该用户已被拉黑，无法加入此群聊', 403);
    }
}

/** Record the one-time admission even if the configured group is temporarily unavailable. */
function chatGroupJoinOnRegistration(PDO $pdo, int $userId, array &$events): void
{
    chatQuery($pdo, 'INSERT INTO registration_group_admissions (user_id,group_code) VALUES (?,?)', [$userId, '9OQF7L']);
    chatGroupCompleteRegistrationJoin($pdo, $userId, $events);
}

/** Only pending admissions retry on login. Leaving or being kicked never re-enrols a user. */
function chatGroupCompleteRegistrationJoin(PDO $pdo, int $userId, array &$events): void
{
    $admission = chatQuery($pdo, 'SELECT group_code FROM registration_group_admissions WHERE user_id=? AND state="pending" FOR UPDATE', [$userId])->fetch();
    if (!$admission) return;
    $group = chatQuery($pdo, 'SELECT id FROM chat_groups WHERE group_code=? FOR UPDATE', [$admission['group_code']])->fetch();
    if (!$group) return;
    $groupId = (int) $group['id'];
    if (chatQuery($pdo, 'SELECT 1 FROM chat_group_bans WHERE group_id=? AND user_id=?', [$groupId, $userId])->fetchColumn()) {
        chatQuery($pdo, 'UPDATE registration_group_admissions SET state="blocked" WHERE user_id=?', [$userId]);
        return;
    }
    chatGroupAssertNotBanned($pdo, $groupId, $userId);
    $insert = chatQuery($pdo, 'INSERT IGNORE INTO chat_group_members (group_id,user_id,role) VALUES (?,?,"member")', [$groupId, $userId]);
    if ($insert->rowCount() > 0) {
        chatGroupWelcome($pdo, $groupId, $userId, $events);
        chatGroupMembershipEvents($pdo, $groupId, $events);
    }
    chatQuery($pdo, 'UPDATE registration_group_admissions SET state="joined" WHERE user_id=?', [$userId]);
}

require_once __DIR__ . '/chat_delivery.php';

/** Called only for a membership inserted in the current transaction. Retries that find a member never call this. */
function chatGroupWelcome(PDO $pdo, int $groupId, int $memberId, array &$events): void
{
    $member = chatQuery($pdo, 'SELECT u.nickname FROM users u JOIN chat_group_members gm ON gm.user_id=u.id WHERE gm.group_id=? AND u.id=?', [$groupId, $memberId])->fetch();
    if (!$member) throw new RuntimeException('新增群成员不存在');
    $content = '欢迎 ' . $member['nickname'] . ' 加入群聊';
    $client = 'welcome-' . bin2hex(random_bytes(12));
    chatQuery($pdo, 'INSERT INTO chat_group_messages (group_id,sender_id,client_message_id,content,message_type,message_origin) VALUES (?,?,?,?,"text","system")', [$groupId, $memberId, $client, $content]);
    $id = (int)$pdo->lastInsertId();
    $message = chatQuery($pdo, 'SELECT m.*,u.nickname,u.avatar_url,g.name AS group_name FROM chat_group_messages m JOIN users u ON u.id=m.sender_id JOIN chat_groups g ON g.id=m.group_id WHERE m.id=?', [$id])->fetch();
    $message['mention_user_ids'] = [];
    chatQuery($pdo, 'UPDATE chat_group_members SET unread_count=unread_count+1 WHERE group_id=? AND user_id<>?', [$groupId, $memberId]);
    foreach (chatQuery($pdo, 'SELECT user_id FROM chat_group_members WHERE group_id=?', [$groupId])->fetchAll(PDO::FETCH_COLUMN) as $recipient) {
        $events[] = ['type'=>'message', 'user_id'=>(int)$recipient, 'group_id'=>$groupId, 'message'=>$message];
    }
    $events[] = ['type'=>'group.joined', 'user_id'=>$memberId, 'group_id'=>$groupId];
}

/** Broadcast absolute counts, so duplicate delivery cannot increase/decrease the displayed value twice. */
function chatGroupMembershipEvents(PDO $pdo, int $groupId, array &$events): int
{
    $members = chatQuery($pdo, 'SELECT user_id FROM chat_group_members WHERE group_id=?', [$groupId])->fetchAll(PDO::FETCH_COLUMN);
    $count = count($members);
    foreach ($members as $recipient) {
        $events[] = ['type'=>'group.members.updated', 'user_id'=>(int)$recipient, 'group_id'=>$groupId, 'member_count'=>$count];
    }
    return $count;
}

function chatPublishGroupEvents(array $events): void
{
    foreach ($events as $event) notifyWebSocket($event);
}

/** Keep the existing text envelope so older installed clients can still render the invitation card. */
function chatGroupCard(PDO $pdo, int $userId, int $groupId): string
{
    $group = chatQuery($pdo, 'SELECT g.id,g.group_code,g.name,g.avatar_url,(SELECT COUNT(*) FROM chat_group_members members WHERE members.group_id=g.id) AS member_count FROM chat_groups g JOIN chat_group_members mine ON mine.group_id=g.id AND mine.user_id=? WHERE g.id=?', [$userId, $groupId])->fetch();
    if (!$group) throw new DomainException('群组不存在或你已不在该群聊中', 403);
    $group['member_count'] = (int)$group['member_count'];
    return 'SPXGROUP:' . $group['group_code'] . "\n" . json_encode($group, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
}
