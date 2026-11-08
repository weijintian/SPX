<?php
declare(strict_types=1);
require_once __DIR__ . '/chat_delivery.php';

function chatManageGroup(PDO $pdo, int $uid, int $group): void
{
    $role=chatQuery($pdo,'SELECT role FROM chat_group_members WHERE group_id=? AND user_id=?',[$group,$uid])->fetchColumn();
    if (!in_array($role,['owner','admin'],true)) throw new DomainException('只有群主或管理员可以设置群机器人',403);
}

function chatAutoReply(PDO $pdo, int $sender, string $scope, int $target, string $text, array &$events): void
{
    if ($scope==='group') {
        $rules=chatQuery($pdo,'SELECT r.* FROM chat_reply_rules r JOIN chat_group_members m ON m.group_id=r.group_id AND m.user_id=r.owner_user_id WHERE r.group_id=? AND r.enabled=1 AND m.role IN ("owner","admin") ORDER BY r.id LIMIT 50',[$target])->fetchAll();
        $key='group:'.$target;
    } else {
        $peer=(int)chatQuery($pdo,'SELECT peer_user_id FROM chats WHERE id=? AND user_id=?',[$target,$sender])->fetchColumn();
        $rules=chatQuery($pdo,'SELECT * FROM chat_reply_rules WHERE owner_user_id=? AND group_id=0 AND enabled=1 ORDER BY id LIMIT 1',[$peer])->fetchAll();
        $key='peer:'.$sender;
    }
    foreach ($rules as $r) {
        // Group administrators can test their own rules. chatDeliver only invokes
        // this function for human messages, so bot replies cannot trigger a loop.
        if ($scope!=='group' && (int)$r['owner_user_id']===$sender) continue;
        if ($scope==='group') {
            $matched=$r['match_mode']==='exact' ? mb_strtolower($text,'UTF-8')===mb_strtolower($r['keyword'],'UTF-8') : mb_stripos($text,$r['keyword'],0,'UTF-8')!==false;
            if (!$matched) continue;
        }
        $last=chatQuery($pdo,'SELECT replied_at FROM chat_reply_cooldowns WHERE rule_id=? AND conversation_key=?',[$r['id'],$key])->fetchColumn();
        if ($last && strtotime($last)+(int)$r['cooldown_seconds']>time()) continue;
        $destination=$scope==='group' ? $target : (int)chatQuery($pdo,'SELECT id FROM chats WHERE user_id=? AND peer_user_id=? LIMIT 1',[$r['owner_user_id'],$sender])->fetchColumn();
        try { chatAccess($pdo,(int)$r['owner_user_id'],$scope,$destination); } catch (DomainException $e) { continue; }
        chatDeliver($pdo,(int)$r['owner_user_id'],$scope,$destination,['content'=>$r['reply_text'],'client_message_id'=>bin2hex(random_bytes(16))],$events,'auto_reply');
        chatQuery($pdo,'INSERT INTO chat_reply_cooldowns(rule_id,conversation_key,replied_at) VALUES (?,?,NOW()) ON DUPLICATE KEY UPDATE replied_at=NOW()',[$r['id'],$key]);
        break; // At most one bot response to a human message; bots never trigger bots.
    }
}

function chatApi(string $action,string $method,array $body): void
{
    $supported=['messages.send','groups.messages.send','chat.schedule.list','chat.schedule.create','chat.schedule.cancel','chat.rules.list','chat.rules.save','chat.rules.delete'];
    if (!in_array($action,$supported,true)) return;
    $user=currentUser(); $uid=(int)$user['id']; $pdo=db();
    try {
        $read=in_array($action,['chat.schedule.list','chat.rules.list'],true);
        if ($method!==($read?'GET':'POST')) throw new DomainException('请求方式不正确',405);
        if ($action==='messages.send' || $action==='groups.messages.send') {
            $scope=$action==='messages.send'?'chat':'group';
            $target=(int)($body[$scope==='chat'?'chat_id':'group_id']??0);
            $result=chatAtomic($pdo,static function(array &$events) use($pdo,$uid,$scope,$target,$body) { return chatDeliver($pdo,$uid,$scope,$target,$body,$events); }, $scope . ':' . $target);
            // Use the same success contract for first delivery and idempotent retry.
            jsonResponse($result,'发送成功');
        }
        if ($action==='chat.schedule.list') {
            $where='user_id=?';$args=[$uid];
            if (isset($_GET['target_id'])) { $where.=' AND scope=? AND target_id=?';$args[]=(string)($_GET['scope']??'chat');$args[]=(int)$_GET['target_id']; }
            $rows=chatQuery($pdo,'SELECT * FROM chat_scheduled_messages WHERE '.$where.' ORDER BY id DESC LIMIT 100',$args)->fetchAll();
            foreach($rows as &$row) $row['send_at_epoch']=strtotime($row['send_at']); unset($row);
            jsonResponse($rows);
        }
        if ($action==='chat.schedule.create') {
            $scope=(string)($body['scope']??'');$target=(int)($body['target_id']??0);
            $epoch=filter_var($body['send_at_epoch']??null,FILTER_VALIDATE_INT);
            if (!$epoch || $epoch<time()+30 || $epoch>time()+90*86400) throw new InvalidArgumentException('请选择至少 30 秒后、90 天内的发送时间');
            if (!in_array($body['message_type']??'text',['text','image'],true)) throw new InvalidArgumentException('定时消息支持文字或图片');
            $result=chatAtomic($pdo,static function(array &$events) use($pdo,$uid,$scope,$target,$body,$epoch) {
                chatAccess($pdo,$uid,$scope,$target);
                [$type,$content,$media,$request]=chatNormalize($pdo,$body,$uid);
                $existing=chatQuery($pdo,'SELECT * FROM chat_scheduled_messages WHERE user_id=? AND request_id=?',[$uid,$request])->fetch();
                if($existing) { if($existing['scope']!==$scope || (int)$existing['target_id']!==$target) throw new DomainException('请求标识已被使用',409); return $existing; }
                if ((int)chatQuery($pdo,'SELECT COUNT(*) FROM chat_scheduled_messages WHERE user_id=? AND status="pending"',[$uid])->fetchColumn()>=50) throw new InvalidArgumentException('最多保留 50 条待发送消息');
                chatQuery($pdo,'INSERT INTO chat_scheduled_messages(user_id,scope,target_id,request_id,message_type,content,media_url,send_at) VALUES (?,?,?,?,?,?,?,?)',[$uid,$scope,$target,$request,$type,$content,$media,date('Y-m-d H:i:s',$epoch)]);
                return ['id'=>(int)$pdo->lastInsertId(),'status'=>'pending','send_at_epoch'=>$epoch];
            });
            jsonResponse($result,'已添加到定时发送',201);
        }
        if ($action==='chat.schedule.cancel') {
            $result=chatAtomic($pdo,static function(array &$events) use($pdo,$uid,$body) {
                $s=chatQuery($pdo,'SELECT * FROM chat_scheduled_messages WHERE id=? AND user_id=? FOR UPDATE',[(int)($body['id']??0),$uid])->fetch();
                if(!$s) throw new DomainException('任务不存在',404);
                if($s['status']==='cancelled') return ['status'=>'cancelled'];
                if($s['status']!=='pending') throw new DomainException('该任务已经结束，无法取消',409);
                chatQuery($pdo,'UPDATE chat_scheduled_messages SET status="cancelled" WHERE id=?',[$s['id']]);
                return ['status'=>'cancelled'];
            });
            jsonResponse($result,'定时消息已取消');
        }
        $group=(int)($_GET['group_id']??$body['group_id']??0);
        if($group<0) throw new InvalidArgumentException('群组参数无效');
        if($group>0) chatManageGroup($pdo,$uid,$group);
        if($action==='chat.rules.list') {
            jsonResponse(chatQuery($pdo,'SELECT * FROM chat_reply_rules WHERE '.($group>0?'group_id=?':'group_id=0 AND owner_user_id=?').' ORDER BY id',[$group>0?$group:$uid])->fetchAll());
        }
        $result=chatAtomic($pdo,static function(array &$events) use($pdo,$uid,$group,$body,$action) {
            if($group>0) chatManageGroup($pdo,$uid,$group);
            $id=(int)($body['id']??0);
            if($id>0) {
                $r=chatQuery($pdo,'SELECT * FROM chat_reply_rules WHERE id=? FOR UPDATE',[$id])->fetch();
                if(!$r || (int)$r['group_id']!==$group || ($group===0 && (int)$r['owner_user_id']!==$uid)) throw new DomainException('回复规则不存在',404);
            }
            if($action==='chat.rules.delete') {
                if($id<=0) throw new InvalidArgumentException('请选择回复规则');
                chatQuery($pdo,'DELETE FROM chat_reply_rules WHERE id=?',[$id]);return ['deleted'=>true];
            }
            $reply=chatText($body['reply_text']??'');
            $keyword=$group>0?mallText($body,'keyword',100,true):'';
            $mode=(string)($body['match_mode']??'contains');
            if(!in_array($mode,['exact','contains'],true)) throw new InvalidArgumentException('触发方式无效');
            $cooldown=(int)($body['cooldown_seconds']??60);
            if($cooldown<30 || $cooldown>86400) throw new InvalidArgumentException('回复间隔须为 30–86400 秒');
            $enabled=!empty($body['enabled'])?1:0;
            if(!$id && $group===0) $id=(int)chatQuery($pdo,'SELECT id FROM chat_reply_rules WHERE owner_user_id=? AND group_id=0 LIMIT 1',[$uid])->fetchColumn();
            if($id) chatQuery($pdo,'UPDATE chat_reply_rules SET owner_user_id=?,keyword=?,match_mode=?,reply_text=?,enabled=?,cooldown_seconds=? WHERE id=?',[$uid,$keyword,$mode,$reply,$enabled,$cooldown,$id]);
            else {
                if($group>0 && (int)chatQuery($pdo,'SELECT COUNT(*) FROM chat_reply_rules WHERE group_id=?',[$group])->fetchColumn()>=50) throw new InvalidArgumentException('每个群最多 50 条回复规则');
                chatQuery($pdo,'INSERT INTO chat_reply_rules(owner_user_id,group_id,keyword,match_mode,reply_text,enabled,cooldown_seconds) VALUES (?,?,?,?,?,?,?)',[$uid,$group,$keyword,$mode,$reply,$enabled,$cooldown]);$id=(int)$pdo->lastInsertId();
            }
            return ['id'=>$id,'enabled'=>$enabled];
        });
        jsonResponse($result,'设置已保存');
    } catch(InvalidArgumentException $e) { jsonResponse(null,$e->getMessage(),422); }
    catch(DomainException $e) { jsonResponse(null,$e->getMessage(),in_array($e->getCode(),[403,404,405,409,503],true)?$e->getCode():422); }
}

function chatRunDue(PDO $pdo,int $limit=50): array
{
    $counts=['sent'=>0,'failed'=>0,'skipped'=>0];
    $ids=chatQuery($pdo,'SELECT id FROM chat_scheduled_messages WHERE status="pending" AND send_at<=NOW() ORDER BY send_at,id LIMIT '.max(1,min(100,$limit)))->fetchAll(PDO::FETCH_COLUMN);
    foreach($ids as $id) {
        $state=chatAtomic($pdo,static function(array &$events) use($pdo,$id) {
            $s=chatQuery($pdo,'SELECT * FROM chat_scheduled_messages WHERE id=? FOR UPDATE',[$id])->fetch();
            if(!$s || $s['status']!=='pending' || strtotime($s['send_at'])>time()) return 'skipped';
            try {
                $body=['content'=>$s['content'],'message_type'=>$s['message_type'],'media_url'=>$s['media_url'],'client_message_id'=>'schedule-'.$id];
                $m=chatDeliver($pdo,(int)$s['user_id'],$s['scope'],(int)$s['target_id'],$body,$events,'scheduled');
                chatQuery($pdo,'UPDATE chat_scheduled_messages SET status="sent",sent_at=NOW(),delivered_message_id=? WHERE id=?',[$m['id'],$id]);
                return 'sent';
            } catch(DomainException|InvalidArgumentException $e) {
                chatQuery($pdo,'UPDATE chat_scheduled_messages SET status="failed",error_message=? WHERE id=?',[mb_substr($e->getMessage(),0,400),$id]);return 'failed';
            }
        });
        $counts[$state]++;
    }
    return $counts;
}
