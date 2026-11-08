<?php
declare(strict_types=1);

// In-memory fixtures only; this test never loads production config or opens a network connection.
require_once __DIR__ . '/../chat_delivery.php';
require_once __DIR__ . '/../chat_sync.php';
require_once __DIR__ . '/../ws-event.php';

$pdo = new PDO('sqlite::memory:', null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
$pdo->exec('CREATE TABLE users(id INTEGER PRIMARY KEY,nickname TEXT,avatar_url TEXT);
    CREATE TABLE contacts(user_id INTEGER,contact_user_id INTEGER,remark TEXT,UNIQUE(user_id,contact_user_id));
    CREATE TABLE chat_group_messages(id INTEGER PRIMARY KEY,group_id INTEGER,sender_id INTEGER,content TEXT,message_type TEXT,mention_user_ids TEXT,reply_to_message_id INTEGER);
    CREATE TABLE chat_group_members(group_id INTEGER,user_id INTEGER);
    INSERT INTO users VALUES(1,"Viewer A",""),(2,"Original Bob",""),(3,"Viewer C",""),(4,"Stranger",""),(5,"Original Eve","");
    INSERT INTO contacts VALUES(1,2,"A remark"),(3,2,"C remark"),(1,5,"Eve private A");
    INSERT INTO chat_group_members VALUES(10,1),(10,2),(10,3),(10,5);
    INSERT INTO chat_group_messages VALUES(100,10,2,"hello","text","[]",NULL),(101,10,5,"quote","text","[]",100),(102,20,2,"private other group","text","[]",NULL),(103,10,5,"foreign quote","text","[]",102);');

$checks = 0;
function check(bool $passed, string $label): void {
    global $checks;
    if (!$passed) throw new RuntimeException($label);
    $checks++;
    echo "PASS $label\n";
}
function row(PDO $pdo, int $viewer, int $id): array {
    return chatReadRows($pdo, $viewer, 'group', 10, ' AND m.id=?', [$id], 'ASC', 1)[0];
}

$a = row($pdo, 1, 100);
$c = row($pdo, 3, 100);
check($a['nickname'] === 'A remark' && $a['display_name'] === 'A remark', 'history uses viewer A remark');
check($c['nickname'] === 'C remark', 'same message uses viewer C own remark');
check($a['original_nickname'] === 'Original Bob', 'original nickname retained for clearing');
check((int)$a['id'] === 100 && (int)$a['sender_id'] === 2, 'message and sender identity unchanged');
check(row($pdo, 4, 100)['nickname'] === 'Original Bob', 'no remark falls back to nickname');
$quote = row($pdo, 1, 101);
check($quote['nickname'] === 'Eve private A', 'quoted message sender uses own remark');
check($quote['reply']['nickname'] === 'A remark' && (int)$quote['reply']['sender_id'] === 2, 'quoted sender also uses viewer remark');
check(row($pdo, 3, 101)['reply']['nickname'] === 'C remark', 'reply names isolated per viewer');
check(row($pdo, 1, 103)['reply']['available'] === false, 'foreign group reference inaccessible');

$shared = ['type'=>'message','group_id'=>10,'user_id'=>1,'message'=>$quote];
$forA = personalizeWebSocketEvent($pdo, $shared);
$forC = personalizeWebSocketEvent($pdo, array_replace($forA, ['user_id'=>3]));
check($forC['message']['nickname'] === 'Original Eve', 'real-time fanout does not copy another viewer sender remark');
check($forC['message']['reply']['nickname'] === 'C remark', 'real-time fanout redecorates quoted sender');
check(!str_contains(json_encode($forC), 'Eve private A') && !str_contains(json_encode($forC), 'A remark'), 'other recipient packet contains no viewer A private names');
check($forA['message']['nickname'] === 'Eve private A', 'recipient decoration leaves prior packet unchanged');
check($shared['message'] === $quote, 'shared event remains unchanged');
$edited = personalizeWebSocketEvent($pdo, array_replace($shared, ['type'=>'message.updated','user_id'=>3]));
check($edited['message']['reply']['nickname'] === 'C remark', 'edited message push uses recipient reply name');

$pdo->exec('UPDATE contacts SET remark="New remark" WHERE user_id=1 AND contact_user_id=2');
check(row($pdo, 1, 100)['nickname'] === 'New remark', 'existing history updates after remark change');
check(row($pdo, 1, 101)['reply']['nickname'] === 'New remark', 'existing quoted message updates after remark change');
$pdo->exec('UPDATE contacts SET remark="" WHERE user_id=1 AND contact_user_id=2');
$cleared = personalizeWebSocketEvent($pdo, array_replace($forC, ['user_id'=>1]));
check($cleared['message']['reply']['nickname'] === 'Original Bob', 'clearing remark restores original quoted name');
check(row($pdo, 1, 100)['contact_remark'] === '' && row($pdo, 1, 100)['nickname'] === 'Original Bob', 'clearing remark restores original history name');
foreach ([' ', 'null', ' Undefined '] as $placeholder) {
    $stmt = $pdo->prepare('UPDATE contacts SET remark=? WHERE user_id=1 AND contact_user_id=2'); $stmt->execute([$placeholder]);
    check(row($pdo, 1, 100)['nickname'] === 'Original Bob', 'empty legacy placeholder normalizes: ' . trim($placeholder));
}
$pdo->exec('DELETE FROM contacts WHERE user_id=1 AND contact_user_id=2');
check(row($pdo, 1, 100)['nickname'] === 'Original Bob', 'deleting contact removes private remark');
$members = chatViewerNames($pdo, [['id'=>2,'nickname'=>'Original Bob'],['id'=>5,'nickname'=>'Original Eve']], 1);
check($members[0]['nickname'] === 'Original Bob' && $members[1]['nickname'] === 'Eve private A', 'member and mention response shapes supported');
$missing = chatViewerNames($pdo, [['sender_id'=>99,'nickname'=>'borrowed private name','original_nickname'=>'Original missing','contact_remark'=>'borrowed private name','display_name'=>'borrowed private name']], 1)[0];
check($missing['nickname'] === 'Original missing' && !isset($missing['contact_remark']) && !isset($missing['display_name']), 'missing sender never inherits previous viewer decoration');
$badReply = chatReplyDecorate($pdo, [['sender_id'=>2,'reply'=>['nickname'=>'borrowed private reply']]], 1, 'group', 10)[0];
check(!isset($badReply['reply']), 'stale reply metadata removed without valid reference');
$direct = ['type'=>'message','user_id'=>1,'chat_id'=>8,'message'=>['id'=>3,'nickname'=>'Direct']];
check(personalizeWebSocketEvent($pdo, $direct) === $direct, 'direct-message events remain compatible');
try { chatReadAccess($pdo,4,'group',10); check(false,'outsider rejected'); }
catch (DomainException $expected) { check($expected->getCode() === 403,'group history authorization preserved'); }
echo "SUCCESS $checks group remark regression checks (SQLite; no production data)\n";
