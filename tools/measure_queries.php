<?php
declare(strict_types=1);
// 测量每个请求平均产生的 SQL 条数
$config = require '/var/www/html/spxchat/config.php';
$d = $config['db'];
$pdo = new PDO(sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $d['host'], $d['port'], $d['name']), $d['user'], $d['password'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);

$base = 'http://127.0.0.1/spxchat/api.php';
function call(string $url, ?array $body = null, ?string $token = null): int
{
    $ch = curl_init($url);
    $h = ['Content-Type: application/json'];
    if ($token) $h[] = 'Authorization: Bearer ' . $token;
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_HTTPHEADER => $h, CURLOPT_TIMEOUT => 20]);
    if ($body !== null) { curl_setopt($ch, CURLOPT_POST, true); curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body)); }
    $raw = (string) curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return $code;
}

$login = json_decode((string) @file_get_contents($base . '?action=auth.login', false, stream_context_create([
    'http' => ['method' => 'POST', 'header' => "Content-Type: application/json\r\n",
               'content' => json_encode(['username' => 'qa1101c', 'password' => 'Qa123456']), 'timeout' => 20],
])), true);
$token = $login['data']['token'] ?? '';
if ($token === '') { echo "登录失败\n"; exit(1); }

function queries(PDO $pdo): int
{
    $s = $pdo->query("SHOW GLOBAL STATUS LIKE 'Queries'")->fetch();
    return (int) $s['Value'];
}

$cases = [
    'health' => [$base . '?action=health', false],
    'unknown(404)' => [$base . '?action=no.such.action', false],
    'app.update.check' => [$base . '?action=app.update.check&version_code=1', false],
    'wallet' => [$base . '?action=wallet', true],
    'staking.plans' => [$base . '?action=staking.plans&symbol=SPX001', true],
    'wallet.transactions' => [$base . '?action=wallet.transactions', true],
];
$n = 30;
printf("%-24s %10s %12s\n", '接口', '总SQL', '每请求SQL');
echo "----------------------------------------------\n";
foreach ($cases as $name => [$url, $needsAuth]) {
    // 预热 1 次
    call($url, null, $needsAuth ? $token : null);
    $before = queries($pdo);
    for ($i = 0; $i < $n; $i++) call($url, null, $needsAuth ? $token : null);
    $after = queries($pdo);
    $delta = $after - $before;
    printf("%-24s %10d %12.1f\n", $name, $delta, $delta / $n);
}
