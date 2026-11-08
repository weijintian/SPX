<?php
declare(strict_types=1);
require_once __DIR__ . '/payment_password.php';
require_once __DIR__.'/chat_delivery.php';

// Six decimal places at the product boundary; all wallet balances remain DECIMAL(36,18).
function redPacketUnits(mixed $raw): int
{
    if (!is_string($raw) || !preg_match('/^(0|[1-9][0-9]{0,9})(?:\.([0-9]{1,6}))?$/D',$raw,$m)) throw new InvalidArgumentException('金额最多保留 6 位小数');
    $units=(int)$m[1]*1000000+(int)str_pad($m[2]??'',6,'0');
    if ($units<1 || $units>1000000000000000) throw new InvalidArgumentException('红包金额须大于 0 且不超过 10 亿');
    return $units;
}
function redPacketAmount(int $units): string { return intdiv($units,1000000).'.'.str_pad((string)($units%1000000),6,'0',STR_PAD_LEFT); }

function redPacketAccess(PDO $pdo,array $packet,int $uid): void
{
    if ((int)$packet['sender_id']===$uid) return;
    if (!chatQuery($pdo,'SELECT 1 FROM chat_red_packet_eligible WHERE packet_id=? AND user_id=?',[$packet['id'],$uid])->fetchColumn()) throw new DomainException('你不能查看或领取这个红包',403);
    if ($packet['scope']==='group' && !chatQuery($pdo,'SELECT 1 FROM chat_group_members WHERE group_id=? AND user_id=?',[$packet['target_id'],$uid])->fetchColumn()) throw new DomainException('你已不在该群中',403);
}
function redPacketRefundLocked(PDO $pdo,array $p): array
{
    if ($p['status']!=='active' || strtotime($p['expires_at'])>time()) return $p;
    $units=(int)$p['remaining_units'];
    if ($units>0) {
        $amount=redPacketAmount($units);
        $symbol = in_array((string)($p['symbol'] ?? ''), ['SPX001', 'FISS'], true) ? (string)$p['symbol'] : 'SPX001';
        walletCreditSplit($pdo,(int)$p['sender_id'],$symbol,'BSC',$amount,(string)$p['remaining_stakeable']);
        chatQuery($pdo,'INSERT INTO wallet_transactions(user_id,symbol,chain,type,amount,stakeable_amount,status) VALUES (?,?,?,?,?,?,"completed")',[$p['sender_id'],$symbol,'BSC','red_packet_refund',$amount,$p['remaining_stakeable']]);
    }
    chatQuery($pdo,'UPDATE chat_red_packets SET status="expired",refunded_units=remaining_units,remaining_units=0,remaining_stakeable=0 WHERE id=?',[$p['id']]);
    $p['status']='expired';$p['refunded_units']=$units;$p['remaining_units']=0;$p['remaining_stakeable']='0';
    return $p;
}
function redPacketSweep(PDO $pdo,int $limit=50,?int $sender=null): int
{
    $args=[];$where='status="active" AND expires_at<=NOW()';if($sender!==null){$where.=' AND sender_id=?';$args[]=$sender;}
    $ids=chatQuery($pdo,'SELECT id FROM chat_red_packets WHERE '.$where.' ORDER BY expires_at LIMIT '.max(1,min(200,$limit)),$args)->fetchAll(PDO::FETCH_COLUMN);
    foreach($ids as $id) chatAtomic($pdo,static function(array &$events)use($pdo,$id){$p=chatQuery($pdo,'SELECT * FROM chat_red_packets WHERE id=? FOR UPDATE',[$id])->fetch();if($p)redPacketRefundLocked($pdo,$p);return null;});
    return count($ids);
}
function redPacketView(PDO $pdo,array $p,int $uid): array
{
    $own=chatQuery($pdo,'SELECT amount FROM chat_red_packet_claims WHERE packet_id=? AND user_id=?',[$p['id'],$uid])->fetchColumn();
    $sender=chatQuery($pdo,'SELECT nickname FROM users WHERE id=?',[$p['sender_id']])->fetchColumn();
    $eligible=(bool)chatQuery($pdo,'SELECT 1 FROM chat_red_packet_eligible WHERE packet_id=? AND user_id=?',[$p['id'],$uid])->fetchColumn();
    $member=$p['scope']!=='group'||(bool)chatQuery($pdo,'SELECT 1 FROM chat_group_members WHERE group_id=? AND user_id=?',[$p['target_id'],$uid])->fetchColumn();
    $canClaim=$p['status']==='active' && $own===false && $eligible && $member;
    $symbol = in_array((string)($p['symbol'] ?? ''), ['SPX001', 'FISS'], true) ? (string)$p['symbol'] : 'SPX001';
    $result=['id'=>$p['id'],'sender_id'=>(int)$p['sender_id'],'sender_name'=>$sender,'scope'=>$p['scope'],'mode'=>$p['mode'],'greeting'=>$p['greeting'],'symbol'=>$symbol,'chain'=>'BSC','status'=>$p['status'],'total_count'=>(int)$p['total_count'],'claimed_count'=>(int)$p['claimed_count'],'expires_at'=>$p['expires_at'],'my_amount'=>$own===false?null:(string)$own,'can_claim'=>$canClaim];
    $result['claim_disabled_reason']=$own!==false?'已领取，心意已到账':($p['status']==='completed'?'手慢了，红包已领完':($p['status']==='expired'?'红包已过期':(!$member?'你已不在该群中':(!$eligible?'你不在这个红包的领取范围内':''))));
    // Do not reveal the random allocations before the viewer has opened the packet.
    if($own!==false || (int)$p['sender_id']===$uid || $p['status']!=='active'){
        $result['total_amount']=redPacketAmount((int)$p['total_units']);$result['refund_amount']=redPacketAmount((int)$p['refunded_units']);
        $result['claims']=chatQuery($pdo,'SELECT c.user_id,u.nickname,c.amount,c.claimed_at FROM chat_red_packet_claims c JOIN users u ON u.id=c.user_id WHERE c.packet_id=? ORDER BY c.id',[$p['id']])->fetchAll();
    }
    return $result;
}
function redPacketCreate(PDO $pdo,int $uid,array $body): array
{
    $request=(string)($body['request_id']??'');if(!preg_match('/^[a-zA-Z0-9_-]{8,36}$/D',$request))throw new InvalidArgumentException('请求标识无效');
    $scope=(string)($body['scope']??'');$target=filter_var($body['target_id']??null,FILTER_VALIDATE_INT);
    $mode=(string)($body['mode']??'ordinary');$count=filter_var($body['count']??1,FILTER_VALIDATE_INT);
    if(!in_array($scope,['chat','group'],true)||!$target||!in_array($mode,['ordinary','random'],true)||!$count||$count<1||$count>100||($scope==='chat'&&$count!==1))throw new InvalidArgumentException('红包类型、会话或个数不正确（每次最多 100 个）');
    $symbol=(string)($body['symbol']??'SPX001'); $chain=(string)($body['chain']??'BSC');
    if(!in_array($symbol,['SPX001','FISS'],true)||$chain!=='BSC')throw new InvalidArgumentException('红包币种不受支持');
    $units=redPacketUnits($body['amount']??null);
    if($units<$count)throw new InvalidArgumentException('每个红包至少 0.000001 '.$symbol);
    if($mode==='ordinary'&&$units%$count!==0)throw new InvalidArgumentException('普通红包总额须能按 6 位小数均分');
    $greeting=trim((string)($body['greeting']??''));if($greeting==='')$greeting='恭喜发财，大吉大利';
    if(mb_strlen($greeting,'UTF-8')>50)throw new InvalidArgumentException('祝福语最多 50 个字');
    $hash=hash('sha256',json_encode($symbol==='SPX001'?[$scope,$target,$mode,$count,$units,$greeting]:[$scope,$target,$mode,$count,$units,$greeting,$symbol,$chain],JSON_UNESCAPED_UNICODE));
    if (!array_key_exists('payment_password', $body)) throw new InvalidArgumentException('请升级到支持支付密码的版本，并在设置中设置支付密码后发红包');
    $password=(string)$body['payment_password'];
    verifyPaymentPassword($pdo,$uid,$password);
    return chatAtomic($pdo,static function(array &$events)use($pdo,$uid,$request,$scope,$target,$mode,$count,$units,$greeting,$hash,$symbol,$chain){
        $previous=chatQuery($pdo,'SELECT * FROM chat_red_packets WHERE sender_id=? AND request_id=?',[$uid,$request])->fetch();
        if($previous){if(!hash_equals($previous['request_hash'],$hash))throw new DomainException('同一请求不能更改金额或会话',409);return ['packet'=>redPacketView($pdo,redPacketRefundLocked($pdo,$previous),$uid),'message'=>json_decode($previous['message_json'],true),'duplicate'=>true];}
        $access=chatAccess($pdo,$uid,$scope,$target);
        $eligible=$scope==='group'?array_map('intval',chatQuery($pdo,'SELECT user_id FROM chat_group_members WHERE group_id=?',[$target])->fetchAll(PDO::FETCH_COLUMN)):[(int)$access['peer_user_id']];
        if($count>count($eligible))throw new InvalidArgumentException('红包个数不能超过可领取人数');
        if(!function_exists('bccomp'))throw new DomainException('服务器未安装 BCMath 扩展',503);
        $amount=redPacketAmount($units);
        $asset=chatQuery($pdo,'SELECT id,balance,stakeable_balance FROM wallet_assets WHERE user_id=? AND symbol=? AND chain=? FOR UPDATE',[$uid,$symbol,$chain])->fetch();
        if(!$asset||bccomp((string)$asset['balance'],$amount,18)<0)throw new DomainException($symbol.' 可用余额不足',409);
        $nonStakeable=bcsub((string)$asset['balance'],(string)$asset['stakeable_balance'],18);
        $stake=bccomp($amount,$nonStakeable,18)>0?bcsub($amount,$nonStakeable,18):'0';
        $id=bin2hex(random_bytes(16));
        chatQuery($pdo,'INSERT INTO chat_red_packets(id,sender_id,request_id,request_hash,scope,target_id,mode,greeting,symbol,chain,total_units,remaining_units,total_count,remaining_stakeable,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(NOW(),INTERVAL 24 HOUR))',[$id,$uid,$request,$hash,$scope,$target,$mode,$greeting,$symbol,$chain,$units,$units,$count,$stake]);
        foreach($eligible as $member)chatQuery($pdo,'INSERT INTO chat_red_packet_eligible(packet_id,user_id) VALUES (?,?)',[$id,$member]);
        chatQuery($pdo,'INSERT INTO wallet_transactions(user_id,symbol,chain,type,amount,stakeable_amount,status) VALUES (?,?,?,?,?,?,"completed")',[$uid,$symbol,$chain,'red_packet_send',$amount,$stake]);
        chatQuery($pdo,'UPDATE wallet_assets SET balance=balance-CAST(? AS DECIMAL(36,18)),stakeable_balance=stakeable_balance-CAST(? AS DECIMAL(36,18)) WHERE id=?',[$amount,$stake,$asset['id']]);
        $content=json_encode(['packet_id'=>$id,'greeting'=>$greeting,'mode'=>$mode,'symbol'=>$symbol,'chain'=>$chain],JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR);
        $message=chatDeliver($pdo,$uid,$scope,$target,['message_type'=>'red_packet','content'=>$content,'client_message_id'=>'rp-'.$id],$events,'red_packet');
        chatQuery($pdo,'UPDATE chat_red_packets SET message_json=? WHERE id=?',[json_encode($message,JSON_THROW_ON_ERROR),$id]);
        $packet=chatQuery($pdo,'SELECT * FROM chat_red_packets WHERE id=?',[$id])->fetch();
        return ['packet'=>redPacketView($pdo,$packet,$uid),'message'=>$message,'duplicate'=>false];
    });
}
/** The claim and its receipt commit together. Do not route through chatDeliver: muted members may claim. */
function redPacketClaimNotice(PDO $pdo,array $packet,int $receiver,array &$events): ?array
{
    $sender=(int)$packet['sender_id'];
    $names=[];
    foreach(chatQuery($pdo,'SELECT id,nickname FROM users WHERE id IN (?,?)',[$sender,$receiver])->fetchAll() as $user){
        // Keep a one-line receipt and bound JSON escaping within the existing VARCHAR(500).
        $name=trim(preg_replace('/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u','',(string)$user['nickname'])??'');
        $names[(int)$user['id']]=$name===''?'好友':mb_substr($name,0,64,'UTF-8');
    }
    $receiverName=$names[$receiver]??'好友';$senderName=$names[$sender]??'好友';
    $content=$receiverName.'领取了'.($receiver===$sender?'自己':$senderName).'的红包';
    // Plain text remains readable on older apps. Server-owned metadata uses the existing text-media field.
    $metadata='spxredpacket:claim:'.json_encode(['packet_id'=>$packet['id'],'receiver_id'=>$receiver,'sender_id'=>$sender,'receiver_name'=>$receiverName,'sender_name'=>$senderName],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR);
    $client='rpc-'.bin2hex(random_bytes(16));
    if($packet['scope']==='group'){
        $group=(int)$packet['target_id'];
        chatQuery($pdo,'INSERT INTO chat_group_messages(group_id,sender_id,client_message_id,content,message_type,media_url,mention_user_ids,message_origin) VALUES (?,?,?,?,"text",?,"[]","system")',[$group,$receiver,$client,$content,$metadata]);
        $row=(int)$pdo->lastInsertId();
        $message=chatQuery($pdo,'SELECT m.*,u.nickname,u.avatar_url,g.name AS group_name FROM chat_group_messages m JOIN users u ON u.id=m.sender_id JOIN chat_groups g ON g.id=m.group_id WHERE m.id=?',[$row])->fetch();
        $message['mention_user_ids']=[];
        foreach(chatQuery($pdo,'SELECT user_id FROM chat_group_members WHERE group_id=?',[$group])->fetchAll(PDO::FETCH_COLUMN) as $member)
            $events[]=['type'=>'message','user_id'=>(int)$member,'group_id'=>$group,'message'=>$message];
        return $message;
    }
    $ownMessage=null;
    // Keep deleted conversations deleted; only append to the existing original sender/recipient chats.
    $chats=chatQuery($pdo,'SELECT id,user_id FROM chats WHERE (id=? AND user_id=?) OR id=(SELECT m.chat_id FROM messages m JOIN chats c ON c.id=m.chat_id AND c.user_id=m.user_id WHERE m.user_id=? AND m.sender_id=? AND m.client_message_id=? AND m.message_type="red_packet" ORDER BY m.id LIMIT 1)',[$packet['target_id'],$sender,$receiver,$sender,'rp-'.$packet['id']])->fetchAll();
    foreach($chats as $chat){
        $chatId=(int)$chat['id'];$owner=(int)$chat['user_id'];
        chatQuery($pdo,'INSERT INTO messages(chat_id,user_id,sender_id,client_message_id,content,message_type,media_url,message_origin) VALUES (?,?,?,?,?,"text",?,"system")',[$chatId,$owner,$receiver,$client,$content,$metadata]);
        $row=(int)$pdo->lastInsertId();
        $message=chatQuery($pdo,'SELECT m.*,u.nickname,u.avatar_url FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.id=?',[$row])->fetch();
        $events[]=['type'=>'message','user_id'=>$owner,'chat_id'=>$chatId,'message'=>$message];
        if($owner===$receiver)$ownMessage=$message;
    }
    return $ownMessage;
}
function redPacketOpen(PDO $pdo,int $uid,string $id,bool $claim): array
{
    if(!preg_match('/^[a-f0-9]{32}$/D',$id))throw new InvalidArgumentException('红包标识不正确');
    return chatAtomic($pdo,static function(array &$events)use($pdo,$uid,$id,$claim){
        $p=chatQuery($pdo,'SELECT * FROM chat_red_packets WHERE id=? FOR UPDATE',[$id])->fetch();if(!$p)throw new DomainException('红包不存在',404);
        redPacketAccess($pdo,$p,$uid);$p=redPacketRefundLocked($pdo,$p);
        $existing=chatQuery($pdo,'SELECT amount FROM chat_red_packet_claims WHERE packet_id=? AND user_id=?',[$id,$uid])->fetchColumn();
        $notice=null;
        if($claim&&$existing===false&&$p['status']==='active'){
            if($p['scope']==='chat'&&(int)$p['sender_id']===$uid)throw new DomainException('不能领取自己发出的个人红包',403);
            if(!chatQuery($pdo,'SELECT 1 FROM chat_red_packet_eligible WHERE packet_id=? AND user_id=?',[$id,$uid])->fetchColumn()||($p['scope']==='group'&&!chatQuery($pdo,'SELECT 1 FROM chat_group_members WHERE group_id=? AND user_id=?',[$p['target_id'],$uid])->fetchColumn()))throw new DomainException('你不能领取这个红包',403);
            $left=(int)$p['total_count']-(int)$p['claimed_count'];$remaining=(int)$p['remaining_units'];
            $units=$left===1?$remaining:($p['mode']==='ordinary'?intdiv((int)$p['total_units'],(int)$p['total_count']):random_int(1,min($remaining-($left-1),max(1,intdiv(2*$remaining,$left)))));
            $amount=redPacketAmount($units);
            $stake=(string)chatQuery($pdo,'SELECT LEAST(CAST(? AS DECIMAL(36,18)),CAST(? AS DECIMAL(36,18)))',[$p['remaining_stakeable'],$amount])->fetchColumn();
            $symbol = in_array((string)($p['symbol'] ?? ''), ['SPX001', 'FISS'], true) ? (string)$p['symbol'] : 'SPX001';
            walletCreditSplit($pdo,$uid,$symbol,'BSC',$amount,$stake);
            chatQuery($pdo,'INSERT INTO chat_red_packet_claims(packet_id,user_id,amount,stakeable_amount) VALUES (?,?,?,?)',[$id,$uid,$amount,$stake]);
            chatQuery($pdo,'INSERT INTO wallet_transactions(user_id,symbol,chain,type,amount,stakeable_amount,status) VALUES (?,?,?,?,?,?,"completed")',[$uid,$symbol,'BSC','red_packet_receive',$amount,$stake]);
            chatQuery($pdo,'UPDATE chat_red_packets SET remaining_units=remaining_units-?,remaining_stakeable=remaining_stakeable-CAST(? AS DECIMAL(36,18)),claimed_count=claimed_count+1,status=? WHERE id=?',[$units,$stake,$left===1?'completed':'active',$id]);
            $p=chatQuery($pdo,'SELECT * FROM chat_red_packets WHERE id=?',[$id])->fetch();
            $notice=redPacketClaimNotice($pdo,$p,$uid,$events);
        }
        $view=redPacketView($pdo,$p,$uid);
        if($notice!==null)$view['claim_notice']=$notice;
        return $view;
    });
}
function redPacketApi(string $action,string $method,array $body): void
{
    if(!in_array($action,['redpackets.create','redpackets.detail','redpackets.claim','redpackets.mine','redpackets.request'],true))return;
    $uid=(int)currentUser()['id'];$pdo=db();
    try{
        $read=in_array($action,['redpackets.detail','redpackets.mine','redpackets.request'],true);if($method!==($read?'GET':'POST'))throw new DomainException('请求方式不正确',405);
        if($action==='redpackets.request'){
            $request=(string)($_GET['request_id']??'');if(!preg_match('/^[a-zA-Z0-9_-]{8,36}$/D',$request))throw new InvalidArgumentException('请求标识无效');
            // Same lock as creation: a missing result cannot race a still-committing send.
            $result=chatAtomic($pdo,static function(array &$events)use($pdo,$uid,$request){$p=chatQuery($pdo,'SELECT * FROM chat_red_packets WHERE sender_id=? AND request_id=? FOR UPDATE',[$uid,$request])->fetch();return $p?['exists'=>true,'packet'=>redPacketView($pdo,redPacketRefundLocked($pdo,$p),$uid),'message'=>json_decode($p['message_json'],true)]:['exists'=>false];});
            jsonResponse($result);
        }
        if($action==='redpackets.create'){
            // Password-backed sends are rate-limited per account and source IP.
            enforceApiRateLimit('red_packet_send_user_'.$uid,30);
            $r=redPacketCreate($pdo,$uid,$body);jsonResponse($r,'红包已发送',$r['duplicate']?200:201);
        }
        if($action==='redpackets.mine'){
            redPacketSweep($pdo,200,$uid);
            [$size,$offset]=(isset($_GET['page'])||isset($_GET['page_size']))?mallPage():[100,0];
            $filter=(string)($_GET['filter']??'all');
            if(!in_array($filter,['all','sent','received'],true))throw new InvalidArgumentException('红包筛选无效');
            $where=$filter==='sent'?'p.sender_id=?':($filter==='received'?'c.user_id=?':'(p.sender_id=? OR c.user_id=?)');
            $params=$filter==='all'?[$uid,$uid,$uid]:[$uid,$uid];
            $rows=chatQuery($pdo,"SELECT p.*,c.amount AS received_amount FROM chat_red_packets p LEFT JOIN chat_red_packet_claims c ON c.packet_id=p.id AND c.user_id=? WHERE $where ORDER BY p.created_at DESC,p.id DESC LIMIT $size OFFSET $offset",$params)->fetchAll();
            $list=[];foreach($rows as $p){$v=redPacketView($pdo,$p,$uid);$v['total_amount']=redPacketAmount((int)$p['total_units']);$list[]=$v;}jsonResponse($list);
        }
        jsonResponse(redPacketOpen($pdo,$uid,(string)($read?($_GET['id']??''):($body['id']??'')),!$read),$read?'红包详情':'红包状态已更新');
    }catch(InvalidArgumentException $e){jsonResponse(null,$e->getMessage(),422);}catch(DomainException $e){jsonResponse(null,$e->getMessage(),in_array($e->getCode(),[403,404,405,409,429,503],true)?$e->getCode():422);}
}
