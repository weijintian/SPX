<?php
declare(strict_types=1);
// 拦截模式下验证真实用户链路不会被误伤
$base = 'http://127.0.0.1/spxchat/api.php';
function call(string $url, ?array $body = null, ?string $token = null): array
{
    $ch = curl_init($url);
    $headers = ['Content-Type: application/json'];
    if ($token !== null) $headers[] = 'Authorization: Bearer ' . $token;
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_TIMEOUT => 15,
    ]);
    if ($body !== null) {
        curl_setopt($ch, CURLOPT_POST, true);
        curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
    }
    $raw = (string) curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return ['code' => $code, 'body' => json_decode($raw, true)];
}

$login = call($base . '?action=auth.login', ['username' => 'qa1101c', 'password' => 'Qa123456']);
$token = $login['body']['data']['token'] ?? '';
if ($token === '') {
    echo "登录失败: HTTP {$login['code']} " . json_encode($login['body'], JSON_UNESCAPED_UNICODE) . "\n";
    exit(1);
}
echo "登录成功\n";
foreach (['wallet', 'staking.plans&symbol=SPX001', 'staking.earnings&symbol=FISS', 'wallet.transactions', 'chats', 'presence.update'] as $action) {
    $r = call($base . '?action=' . $action, null, $token);
    printf("  %-34s HTTP %s\n", $action, $r['code']);
}
// 连续 15 次读接口，检查会不会被误伤（阈值 180/分）
$codes = [];
for ($i = 0; $i < 15; $i++) {
    $r = call($base . '?action=wallet', null, $token);
    $codes[$r['code']] = ($codes[$r['code']] ?? 0) + 1;
}
echo "连续 15 次 wallet 请求结果: " . json_encode($codes) . "\n";
