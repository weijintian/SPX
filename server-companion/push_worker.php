<?php
declare(strict_types=1);
// CLI only. Run once per minute with cron or continuously with a process manager.
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
require_once __DIR__.'/push_notifications.php';

function pushBase64(string $value): string { return rtrim(strtr(base64_encode($value),'+/','-_'),'='); }
function pushDERtoJOSE(string $der): string
{
    $offset=0;
    $length=static function () use ($der,&$offset): int {
        if ($offset>=strlen($der)) throw new RuntimeException('Invalid ECDSA signature');
        $n=ord($der[$offset++]); if ($n<128) return $n;
        $bytes=$n&127; if ($bytes<1 || $bytes>2) throw new RuntimeException('Invalid ECDSA length');
        $n=0; for ($i=0;$i<$bytes;$i++) { if ($offset>=strlen($der)) throw new RuntimeException('Truncated signature'); $n=($n<<8)|ord($der[$offset++]); } return $n;
    };
    if (ord($der[$offset++] ?? "\0")!==48) throw new RuntimeException('Invalid ECDSA sequence');
    $sequence=$length(); if ($sequence!==strlen($der)-$offset) throw new RuntimeException('Invalid ECDSA sequence length');
    $out='';
    for ($i=0;$i<2;$i++) {
        if (ord($der[$offset++] ?? "\0")!==2) throw new RuntimeException('Invalid ECDSA integer');
        $n=$length(); if ($n<1 || $offset+$n>strlen($der)) throw new RuntimeException('Truncated ECDSA integer');
        $integer=ltrim(substr($der,$offset,$n),"\0"); $offset+=$n;
        if (strlen($integer)>32) throw new RuntimeException('Invalid P256 integer');
        $out.=str_pad($integer,32,"\0",STR_PAD_LEFT);
    }
    if ($offset!==strlen($der)) throw new RuntimeException('Unexpected signature suffix'); return $out;
}
function pushAPNsJWT(): string
{
    static $jwt='', $issued=0;
    if ($jwt!=='' && time()-$issued<2400) return $jwt;
    $key=openssl_pkey_get_private((string)file_get_contents((string)getenv('SPX_APNS_P8_PATH')));
    $details=$key ? openssl_pkey_get_details($key) : false;
    if (!$details || ($details['ec']['curve_name'] ?? '')!=='prime256v1') throw new RuntimeException('APNs requires an ES256 private key');
    $issued=time();
    $input=pushBase64(json_encode(['alg'=>'ES256','kid'=>getenv('SPX_APNS_KEY_ID')],JSON_THROW_ON_ERROR)).'.'.pushBase64(json_encode(['iss'=>getenv('SPX_APNS_TEAM_ID'),'iat'=>$issued],JSON_THROW_ON_ERROR));
    if (!openssl_sign($input,$signature,$key,OPENSSL_ALGO_SHA256)) throw new RuntimeException('APNs signing failed');
    return $jwt=$input.'.'.pushBase64(pushDERtoJOSE($signature));
}
function pushDeliver(array $device, array $payload, string $collapse): array
{
    $host=$device['environment']==='development' ? 'https://api.sandbox.push.apple.com' : 'https://api.push.apple.com';
    $ch=curl_init($host.'/3/device/'.$device['device_token']);
    curl_setopt_array($ch,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>json_encode($payload,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),CURLOPT_RETURNTRANSFER=>true,
        CURLOPT_HTTP_VERSION=>CURL_HTTP_VERSION_2_0,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>12,
        CURLOPT_HTTPHEADER=>['authorization: bearer '.pushAPNsJWT(),'apns-topic: '.$device['bundle_id'],'apns-push-type: alert','apns-priority: 10','apns-expiration: '.(time()+600),'apns-collapse-id: '.$collapse,'content-type: application/json']]);
    $response=curl_exec($ch); $code=(int)curl_getinfo($ch,CURLINFO_HTTP_CODE); curl_close($ch);
    $reason=$response===false ? 'transport' : (json_decode($response,true)['reason'] ?? '');
    return [$code,$reason];
}
function pushRunBatch(): array
{
    if (!pushConfigured()) return ['configured'=>false,'sent'=>0];
    require_once __DIR__.'/db.php';
    if (!extension_loaded('curl') || !(curl_version()['features'] & CURL_VERSION_HTTP2)) throw new RuntimeException('APNs requires cURL HTTP/2');
    $pdo=db(); $stats=['configured'=>true,'sent'=>0,'skipped'=>0,'retried'=>0];
    if ((int)$pdo->query("SELECT GET_LOCK('spx_push_worker',0)")->fetchColumn()!==1) return $stats;
    try {
        $pdo->exec('DELETE FROM push_queue WHERE expires_at<NOW() OR attempts>=6');
        $jobs=$pdo->query('SELECT * FROM push_queue WHERE available_at<=NOW() ORDER BY id LIMIT 100')->fetchAll();
        foreach ($jobs as $job) {
            $q=$pdo->prepare('SELECT * FROM push_devices d WHERE d.id=? AND d.enabled=1 AND d.session_hash=? AND EXISTS (SELECT 1 FROM user_tokens t WHERE t.user_id=d.user_id AND SHA2(t.token,256)=d.session_hash AND t.expires_at>NOW())');
            $q->execute([$job['device_id'],$job['session_hash']]); $device=$q->fetch(); $skip=!$device;
            if ($device) {
                $group=(int)$job['group_id']>0;
                $table=$group?'chat_group_members':'chats'; $key=$group?'group_id':'id';
                $q=$group ? $pdo->prepare("SELECT last_read_message_id,mute FROM $table WHERE $key=? AND user_id=?") : $pdo->prepare("SELECT c.last_read_message_id,COALESCE(s.mute,0) AS mute,COALESCE(s.blocked,0) AS blocked FROM chats c LEFT JOIN chat_settings s ON s.chat_id=c.id AND s.user_id=c.user_id WHERE c.id=? AND c.user_id=?");
                $q->execute([$group?$job['group_id']:$job['chat_id'],$device['user_id']]); $chat=$q->fetch();
                $messageQuery=$group ? $pdo->prepare('SELECT 1 FROM chat_group_messages WHERE id=? AND group_id=?') : $pdo->prepare('SELECT 1 FROM messages WHERE id=? AND chat_id=?');
                $messageQuery->execute([$job['message_id'],$group?$job['group_id']:$job['chat_id']]); $messageExists=(bool)$messageQuery->fetchColumn();
                $payload=json_decode($job['payload'],true,512,JSON_THROW_ON_ERROR);
                $skip=!$chat || !$messageExists || (int)$chat['last_read_message_id']>=(int)$job['message_id'] || !pushNotificationAllowed($chat,!empty($payload['aps']['alert']['subtitle']),$group)
                    || !hash_equals((string)getenv('SPX_APNS_TOPIC'),$device['bundle_id']);
                if (!$skip) {
                    if (!$device['preview']) $payload['aps']['alert']['body']='你收到了一条新消息';
                    if ($device['badge']) {
                        $q=$pdo->prepare('SELECT (SELECT COALESCE(SUM(unread_count),0) FROM chats WHERE user_id=?)+(SELECT COALESCE(SUM(unread_count),0) FROM chat_group_members WHERE user_id=?)');
                        $q->execute([$device['user_id'],$device['user_id']]); $payload['aps']['badge']=min(2147483647,max(0,(int)$q->fetchColumn()));
                    } else { $payload['aps']['badge']=0; }
                    [$code,$reason]=pushDeliver($device,$payload,hash('sha256',$device['id'].':'.$job['chat_id'].':'.$job['group_id']));
                    if ($code===200) $stats['sent']++;
                    elseif (in_array($code,[0,429,500,503],true) || in_array($reason,['ExpiredProviderToken','TooManyProviderTokenUpdates'],true)) {
                        $delay=min(300,5*(2**min(5,(int)$job['attempts'])));
                        $pdo->prepare('UPDATE push_queue SET attempts=attempts+1,available_at=DATE_ADD(NOW(),INTERVAL ? SECOND) WHERE id=?')->execute([$delay,$job['id']]);
                        $stats['retried']++; continue;
                    } else {
                        if ($code===410 || in_array($reason,['BadDeviceToken','DeviceTokenNotForTopic'],true)) $pdo->prepare('DELETE FROM push_devices WHERE id=?')->execute([$device['id']]);
                        $stats['skipped']++;
                    }
                } else $stats['skipped']++;
            } else $stats['skipped']++;
            $pdo->prepare('DELETE FROM push_queue WHERE id=?')->execute([$job['id']]);
        }
    } finally { $pdo->query("SELECT RELEASE_LOCK('spx_push_worker')"); }
    return $stats;
}
if (realpath($_SERVER['SCRIPT_FILENAME'] ?? '')===__FILE__) {
    try { echo json_encode(pushRunBatch(),JSON_THROW_ON_ERROR).PHP_EOL; }
    catch (Throwable $e) { fwrite(STDERR,"Push worker failed; inspect server configuration.\n"); exit(1); }
}
