<?php
declare(strict_types=1);
$server = __DIR__ . '/../../spxchat';
if (!is_file($server . '/call_session_policy.php')) $server = __DIR__ . '/../../server-companion';
require_once $server . '/call_session_policy.php';

$incoming = ['state' => 'ringing', 'caller_id' => 10, 'callee_id' => 20, 'callee_device' => ''];
$checks = [
    'offline recipient login preserves unclaimed incoming call' => [true, $incoming, 20],
    'caller login ends its previous outgoing call' => [false, $incoming, 10],
    'unrelated login cannot inherit a call' => [false, $incoming, 30],
    'recipient device replacement ends a claimed call' => [false, array_replace($incoming, ['callee_device' => 'previous-device']), 20],
    'connected call does not migrate across logins' => [false, array_replace($incoming, ['state' => 'connected']), 20],
    'canceled call is never revived' => [false, array_replace($incoming, ['state' => 'canceled']), 20],
    'timed out call is never revived' => [false, array_replace($incoming, ['state' => 'timeout']), 20],
    'rejected call is never revived' => [false, array_replace($incoming, ['state' => 'rejected']), 20],
];
foreach ($checks as $name => [$expected, $call, $owner]) {
    if (callCanSurviveRecipientLogin($call, $owner) !== $expected) throw new RuntimeException($name);
    echo "PASS: {$name}\n";
}
$auth = file_get_contents($server . '/auth.php');
if (!str_contains($auth, 'if (!callCanSurviveRecipientLogin($call, $userId)) callFinish(')) {
    throw new RuntimeException('Login must apply the invitation-preservation guard');
}
echo "PASS: login applies the preservation rule before ending old calls\n";
