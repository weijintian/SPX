<?php
declare(strict_types=1);
require_once dirname(__DIR__).'/push_notifications.php';
require_once dirname(__DIR__).'/push_worker.php';
$checks=[];
function check(bool $ok,string $name): void { global $checks; $checks[]=['name'=>$name,'passed'=>$ok]; if (!$ok) throw new RuntimeException($name); }
function rejects(array $body,string $name): void { try { pushRegistration($body); check(false,$name); } catch (InvalidArgumentException $e) { check(true,$name); } }
$valid=['provider'=>'apns','platform'=>'ios','device_id'=>'fixture-device-123','device_token'=>str_repeat('Ab',32),'bundle_id'=>'com.example.spx','environment'=>'production'];
$v=pushRegistration($valid);
check($v['device_token']===str_repeat('ab',32),'APNs token normalization');
check($v['enabled']===1 && $v['preview']===1 && $v['badge']===1,'Preferences default on');
$v=pushRegistration($valid+['enabled'=>false,'preview'=>false,'badge'=>false]);
check($v['enabled']===0 && $v['preview']===0 && $v['badge']===0,'Preferences disabled');
foreach (['provider'=>'fcm','platform'=>'android','device_id'=>'../bad','device_token'=>'not-a-token','bundle_id'=>"x\nheader",'environment'=>'arbitrary','enabled'=>'true'] as $key=>$value) rejects(array_replace($valid,[$key=>$value]),'Reject invalid '.$key);
rejects(array_replace($valid,['device_token'=>str_repeat('a',65)]),'Reject odd APNs token');
rejects(array_replace($valid,['device_id'=>['array']]),'Reject structured identity');
foreach (['enabled','preview','badge'] as $key) rejects(array_replace($valid,[$key=>null]),'Reject null '.$key);
check(pushNotificationAllowed(['mute'=>0,'blocked'=>0],false,false),'Normal direct message is allowed');
check(!pushNotificationAllowed(['mute'=>1,'blocked'=>0],false,false),'Muted direct message is suppressed');
check(!pushNotificationAllowed(['mute'=>1,'blocked'=>0],true,false),'Direct mention cannot bypass mute');
check(!pushNotificationAllowed(['mute'=>0,'blocked'=>1],true,false),'Blocked direct mention is suppressed');
check(!pushNotificationAllowed(['mute'=>1,'blocked'=>1],true,false),'Block cannot be bypassed by queued mention');
check(!pushNotificationAllowed(['mute'=>1],false,true),'Muted ordinary group message is suppressed');
check(pushNotificationAllowed(['mute'=>1],true,true),'Group mention can bypass group mute');
check(!pushNotificationAllowed(['mute'=>1,'blocked'=>1],true,true),'Block always takes precedence over mention');
$event=['type'=>'message','user_id'=>5,'chat_id'=>10,'message'=>['id'=>8,'sender_id'=>9]];
check(pushEventRoute($event)['chat']===10,'Direct route uses recipient chat');
check(pushEventRoute(array_replace($event,['group_id'=>3]))['chat']===0,'Group route excludes direct chat');
check(pushEventRoute(array_replace($event,['message'=>['id'=>8,'sender_id'=>5]]))===null,'Self events never notify');
check(pushEventRoute(array_replace($event,['user_id'=>0]))===null,'Recipient is required');
check(pushEventRoute(array_replace($event,['chat_id'=>0]))===null,'Conversation is required');
check(pushEventRoute(array_replace($event,['type'=>'call.invite']))===null,'Calls are not duplicated as chat pushes');
check(pushDERtoJOSE("\x30\x06\x02\x01\x01\x02\x01\x02")===str_repeat("\0",31)."\x01".str_repeat("\0",31)."\x02",'ES256 DER padding');
try { pushDERtoJOSE("\x30\x06\x02\x01"); check(false,'Reject truncated DER'); } catch (RuntimeException $e) { check(true,'Reject truncated DER'); }
putenv('SPX_APNS_ENABLED=0');
check(pushConfigured()===false,'No credentials means delivery disabled');
check(pushRunBatch()===['configured'=>false,'sent'=>0],'Unconfigured worker does not connect to database or network');
echo json_encode(['passed'=>count($checks),'checks'=>$checks,'database_integration'=>'not_run','live_delivery'=>'not_run'],JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES).PHP_EOL;
