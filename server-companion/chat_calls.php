<?php
declare(strict_types=1);
require_once __DIR__ . '/chat_delivery.php';
require_once __DIR__ . '/call_session_policy.php';

/** Key is supplied by the PHP service environment, never by an HTTP request. */
function tencentCallConfig(): array
{
    $path = getenv('SPX_TENCENT_CALL_ENV_FILE') ?: '/etc/spxchat/tencent-call.env';
    $values = [];
    if (is_readable($path)) {
        foreach (file($path, FILE_IGNORE_NEW_LINES) ?: [] as $line) {
            if (str_starts_with(trim($line), '#') || !str_contains($line, '=')) continue;
            [$k, $v] = explode('=', $line, 2);
            $values[trim($k)] = trim(trim($v), "\"'");
        }
    }
    $id = getenv('TENCENT_CALL_SDK_APP_ID') ?: ($values['TENCENT_CALL_SDK_APP_ID'] ?? '');
    $key = getenv('TENCENT_CALL_SDK_SECRET_KEY') ?: ($values['TENCENT_CALL_SDK_SECRET_KEY'] ?? '');
    if (!preg_match('/^[1-9][0-9]{8,10}$/D', (string)$id) || strlen($key) < 32)
        throw new DomainException('音视频通话服务尚未配置，请联系管理员', 503);
    return ['id' => (int)$id, 'key' => $key];
}

/** Tencent TLS signature v2, HMAC-SHA256 + zlib + Tencent URL-safe base64. */
function tencentCallUserSig(int $appId, string $key, string $userId, int $ttl = 3600, ?int $now = null): string
{
    $now ??= time();
    $text = "TLS.identifier:{$userId}\nTLS.sdkappid:{$appId}\nTLS.time:{$now}\nTLS.expire:{$ttl}\n";
    $data = ['TLS.ver'=>'2.0','TLS.identifier'=>$userId,'TLS.sdkappid'=>$appId,'TLS.expire'=>$ttl,'TLS.time'=>$now,
        'TLS.sig'=>base64_encode(hash_hmac('sha256', $text, $key, true))];
    return strtr(base64_encode(gzcompress(json_encode($data, JSON_UNESCAPED_SLASHES), 9)), ['+'=>'*','/'=>'-','='=>'_']);
}

function callSchema(PDO $pdo): void
{
    $pdo->exec('CREATE TABLE IF NOT EXISTS chat_call_presence (user_id BIGINT UNSIGNED PRIMARY KEY, device_id VARCHAR(64) NOT NULL, last_seen DATETIME NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
    $pdo->exec('CREATE TABLE IF NOT EXISTS chat_calls (
        id CHAR(36) PRIMARY KEY, caller_id BIGINT UNSIGNED NOT NULL, callee_id BIGINT UNSIGNED NOT NULL,
        caller_chat_id BIGINT UNSIGNED NOT NULL, media VARCHAR(8) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT "ringing",
        created_at DATETIME NOT NULL, connected_at DATETIME NULL, ended_at DATETIME NULL, duration_seconds INT NOT NULL DEFAULT 0,
        caller_seen DATETIME NOT NULL, callee_seen DATETIME NULL, caller_device VARCHAR(64) NOT NULL, callee_device VARCHAR(64) NOT NULL,
        sdk_call_id VARCHAR(128) NULL, record_written TINYINT NOT NULL DEFAULT 0,
        INDEX caller_active(caller_id,state), INDEX callee_active(callee_id,state)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
}

function callIsFinal(string $state): bool { return !in_array($state, ['ringing','connected'], true); }

function callDevice(array $body): string
{
    $id = (string)($body['device_id'] ?? '');
    if (!preg_match('/^[a-zA-Z0-9-]{16,64}$/D', $id)) throw new InvalidArgumentException('设备标识无效');
    return $id;
}
function callResult(string $state, int $duration): string
{
    return match ($state) {
        'ended' => sprintf('通话时长 %02d:%02d', intdiv($duration,60), $duration%60),
        'rejected' => '已拒绝', 'busy' => '对方忙线中', 'timeout' => '无人接听',
        'offline' => '对方暂时不在线', 'failed' => '连接中断', default => '已取消',
    };
}
function callRecord(PDO $pdo, array $row, array &$events): void
{
    if ((int)$row['record_written'] === 1) return;
    // A call record is server-owned; it is written once into each participant's own chat.
    $label = '[' . ($row['media']==='video'?'视频通话':'语音通话') . '] ' . callResult($row['state'],(int)$row['duration_seconds']);
    foreach ([[(int)$row['caller_id'],(int)$row['callee_id']],[(int)$row['callee_id'],(int)$row['caller_id']]] as [$uid,$peer]) {
        $chat = (int)chatQuery($pdo,'SELECT id FROM chats WHERE user_id=? AND peer_user_id=? LIMIT 1',[$uid,$peer])->fetchColumn();
        if (!$chat) {
            $name=chatQuery($pdo,'SELECT nickname FROM users WHERE id=?',[$peer])->fetchColumn();
            chatQuery($pdo,'INSERT INTO chats(user_id,peer_user_id,title) VALUES(?,?,?)',[$uid,$peer,$name?:'好友']);
            $chat=(int)$pdo->lastInsertId();
        }
        chatQuery($pdo,'INSERT INTO messages(chat_id,user_id,sender_id,client_message_id,content,message_type,message_origin) VALUES(?,?,?,?,?,"text","call")',
            [$chat,$uid,$row['caller_id'],'call-'.substr(hash('sha256',$row['id']),0,30),$label]);
        $id=(int)$pdo->lastInsertId();
        chatQuery($pdo,'UPDATE chats SET last_message=?,updated_at=NOW(),unread_count=unread_count+? WHERE id=?',[$label,$uid===(int)$row['caller_id']?0:1,$chat]);
        $message=chatQuery($pdo,'SELECT m.*,u.nickname,u.avatar_url FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.id=?',[$id])->fetch();
        $events[]=['type'=>'message','user_id'=>$uid,'chat_id'=>$chat,'message'=>$message];
    }
    chatQuery($pdo,'UPDATE chat_calls SET record_written=1 WHERE id=?',[$row['id']]);
}
function callFinish(PDO $pdo, array $row, string $state, array &$events): array
{
    if (callIsFinal($row['state'])) return $row;
    $duration=$row['connected_at'] ? max(0,time()-strtotime($row['connected_at'])) : 0;
    if ($row['connected_at'] && in_array($state,['canceled','rejected','timeout','busy'],true)) $state='ended';
    chatQuery($pdo,'UPDATE chat_calls SET state=?,ended_at=NOW(),duration_seconds=? WHERE id=?',[$state,$duration,$row['id']]);
    $row['state']=$state;$row['duration_seconds']=$duration;
    callRecord($pdo,$row,$events);
    foreach ([$row['caller_id'],$row['callee_id']] as $uid) $events[]=['type'=>'call.updated','user_id'=>(int)$uid,'session_id'=>$row['id'],'state'=>$state];
    return $row;
}
function callExpire(PDO $pdo, int $uid, array &$events): void
{
    $rows=chatQuery($pdo,'SELECT * FROM chat_calls WHERE (caller_id=? OR callee_id=?) AND state IN ("ringing","connected") FOR UPDATE',[$uid,$uid])->fetchAll();
    foreach($rows as $row) {
        if ($row['state']==='ringing' && strtotime($row['created_at'])<time()-65) callFinish($pdo,$row,'timeout',$events);
        elseif ($row['state']==='connected' && min(strtotime($row['caller_seen']),strtotime($row['callee_seen']?:$row['connected_at']))<time()-45) callFinish($pdo,$row,'failed',$events);
    }
}
function callView(PDO $pdo, array $row, int $uid): array
{
    if (!in_array($uid,[(int)$row['caller_id'],(int)$row['callee_id']],true)) throw new DomainException('无权查看该通话',403);
    $peer=$uid===(int)$row['caller_id']?(int)$row['callee_id']:(int)$row['caller_id'];
    $profile=chatQuery($pdo,'SELECT nickname,avatar_url FROM users WHERE id=?',[$peer])->fetch()?:[];
    $peerReady=(bool)chatQuery($pdo,'SELECT 1 FROM chat_call_presence WHERE user_id=? AND last_seen>DATE_SUB(NOW(),INTERVAL 35 SECOND)',[$peer])->fetchColumn();
    return ['session_id'=>$row['id'],'caller_id'=>(int)$row['caller_id'],'callee_id'=>(int)$row['callee_id'],
        'peer_id'=>$peer,'peer_user_id'=>'spx_'.$peer,'peer_name'=>$profile['nickname']??'好友','peer_avatar'=>$profile['avatar_url']??'',
        'media'=>$row['media'],'state'=>$row['state'],'duration_seconds'=>(int)$row['duration_seconds'],'peer_ready'=>$peerReady];
}

function tencentCallApi(string $action, string $method, array $body): void
{
    if (!str_starts_with($action,'calls.')) return;
    $user=currentUser();$uid=(int)$user['id'];$pdo=db();callSchema($pdo);
    header('Cache-Control: no-store');
    if($action==='calls.credentials' && $method==='GET') {
        enforceApiRateLimit('call-credentials-'.$uid,120);
        $config=tencentCallConfig();$rtc='spx_'.$uid;
        jsonResponse(['sdk_app_id'=>$config['id'],'user_id'=>$rtc,'user_sig'=>tencentCallUserSig($config['id'],$config['key'],$rtc),
            'expires_at'=>time()+3600,'nickname'=>$user['nickname'],'avatar_url'=>$user['avatar_url']??'']);
    }
    if($action==='calls.presence' && $method==='POST') {
        $device=callDevice($body);
        if(!empty($body['ready'])) chatQuery($pdo,'INSERT INTO chat_call_presence(user_id,device_id,last_seen) VALUES(?,?,NOW()) ON DUPLICATE KEY UPDATE device_id=VALUES(device_id),last_seen=NOW()',[$uid,$device]);
        else chatQuery($pdo,'DELETE FROM chat_call_presence WHERE user_id=? AND device_id=?',[$uid,$device]);
        jsonResponse(['ready'=>!empty($body['ready'])]);
    }
    if($action==='calls.prepare' && $method==='POST') {
        enforceApiRateLimit('call-prepare-'.$uid,120);
        tencentCallConfig();$device=callDevice($body);$chat=(int)($body['chat_id']??0);$media=(string)($body['media']??'');$id=(string)($body['session_id']??'');
        if(!preg_match('/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/D',$id)||!in_array($media,['audio','video'],true))throw new InvalidArgumentException('通话参数无效');
        $result=chatAtomic($pdo,function(array &$events)use($pdo,$uid,$chat,$device,$media,$id){
            $access=chatAccess($pdo,$uid,'chat',$chat);$peer=(int)$access['peer_user_id'];
            if($peer===$uid)throw new InvalidArgumentException('不能呼叫自己');
            $existing=chatQuery($pdo,'SELECT * FROM chat_calls WHERE id=? FOR UPDATE',[$id])->fetch();
            if($existing){if((int)$existing['caller_id']!==$uid||(int)$existing['caller_chat_id']!==$chat||$existing['media']!==$media||$existing['caller_device']!==$device)throw new DomainException('通话标识已被使用',409);return callView($pdo,$existing,$uid);}
            callExpire($pdo,$uid,$events);callExpire($pdo,$peer,$events);
            $own=chatQuery($pdo,'SELECT device_id FROM chat_call_presence WHERE user_id=? AND last_seen>DATE_SUB(NOW(),INTERVAL 35 SECOND)',[$uid])->fetchColumn();
            if($own!==$device)throw new DomainException('通话服务正在连接，请稍后重试',409);
            if(chatQuery($pdo,'SELECT id FROM chat_calls WHERE (caller_id IN (?,?) OR callee_id IN (?,?)) AND state IN ("ringing","connected") LIMIT 1',[$uid,$peer,$uid,$peer])->fetch())throw new DomainException('你或对方正在通话中',409);
            // Bind the recipient only when its authenticated, ready device receives the invitation.
            chatQuery($pdo,'INSERT INTO chat_calls(id,caller_id,callee_id,caller_chat_id,media,state,created_at,caller_seen,caller_device,callee_device) VALUES(?,?,?,?,?,"ringing",NOW(),NOW(),?,?)',[$id,$uid,$peer,$chat,$media,$device,'']);
            return callView($pdo,chatQuery($pdo,'SELECT * FROM chat_calls WHERE id=?',[$id])->fetch(),$uid);
        });jsonResponse($result);
    }
    if($action==='calls.session' && $method==='GET') {
        $id=(string)($_GET['session_id']??'');
        if(!preg_match('/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/D',$id))throw new InvalidArgumentException('通话标识无效');
        $device = callDevice($_GET);
        $result=chatAtomic($pdo,function(array &$events)use($pdo,$uid,$id,$device){callExpire($pdo,$uid,$events);$row=chatQuery($pdo,'SELECT * FROM chat_calls WHERE id=? FOR UPDATE',[$id])->fetch();if(!$row)throw new DomainException('通话已失效',404);callView($pdo,$row,$uid);$column=$uid===(int)$row['caller_id']?'caller_device':'callee_device';if($row[$column]===''&&$uid===(int)$row['callee_id']){$ready=chatQuery($pdo,'SELECT device_id FROM chat_call_presence WHERE user_id=? AND last_seen>DATE_SUB(NOW(),INTERVAL 35 SECOND)',[$uid])->fetchColumn();if($ready!==$device)throw new DomainException('通话设备尚未就绪',409);chatQuery($pdo,'UPDATE chat_calls SET callee_device=? WHERE id=? AND callee_device=""',[$device,$id]);$row['callee_device']=$device;}if($row[$column]!==$device)throw new DomainException('通话已在其他设备处理',409);return callView($pdo,$row,$uid);});jsonResponse($result);
    }
    if($action==='calls.update' && $method==='POST') {
        $device=callDevice($body);$id=(string)($body['session_id']??'');$event=(string)($body['event']??'');
        if(!in_array($event,['heartbeat','connected','ended','canceled','rejected','busy','sdk_canceled','sdk_rejected','sdk_busy','sdk_timeout','timeout','failed'],true))throw new InvalidArgumentException('通话状态无效');
        $result=chatAtomic($pdo,function(array &$events)use($pdo,$uid,$device,$id,$event,$body){
            $row=chatQuery($pdo,'SELECT * FROM chat_calls WHERE id=? FOR UPDATE',[$id])->fetch();if(!$row)throw new DomainException('通话不存在',404);
            callView($pdo,$row,$uid);$caller=$uid===(int)$row['caller_id'];
            if($row[$caller?'caller_device':'callee_device']!==$device)throw new DomainException('通话已在其他设备处理',409);
            callExpire($pdo,$uid,$events);$row=chatQuery($pdo,'SELECT * FROM chat_calls WHERE id=?',[$id])->fetch();
            if(callIsFinal($row['state']))return callView($pdo,$row,$uid);
            // Both SDK observers may report the same terminal reason; first terminal update wins.
            if($event==='canceled'&&!$caller)throw new DomainException('只有发起人可以取消呼叫',403);
            if(in_array($event,['rejected','busy'],true)&&$caller)throw new DomainException('只有接收方可以拒绝来电',403);
            chatQuery($pdo,'UPDATE chat_calls SET '.($caller?'caller_seen':'callee_seen').'=NOW() WHERE id=?',[$id]);
            if($event==='connected'&&$row['state']==='ringing') {
                chatQuery($pdo,'UPDATE chat_calls SET state="connected",connected_at=NOW(),callee_seen=NOW(),caller_seen=NOW() WHERE id=?',[$id]);
                $row['state']='connected';
            } elseif(!in_array($event,['heartbeat','connected'],true)) {
                $terminal = ['sdk_canceled'=>'canceled','sdk_rejected'=>'rejected','sdk_busy'=>'busy','sdk_timeout'=>'timeout'][$event] ?? $event;
                $row=callFinish($pdo,$row,$terminal,$events);
            }
            return callView($pdo,$row,$uid);
        });jsonResponse($result);
    }
    jsonResponse(null,'通话接口不存在或请求方式不正确',405);
}
