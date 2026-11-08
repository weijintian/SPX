<?php
declare(strict_types=1);
/**
 * SPX 数据清理（CLI，由 systemd timer 每天执行）
 * - 过期登录 token（保留 1 天缓冲，便于排查）
 * - 旧的接口限流窗口
 * - 过期的邮箱验证码（保留 7 天）
 */
if (PHP_SAPI !== 'cli') {
    http_response_code(403);
    exit('cli only');
}
require_once __DIR__ . '/db.php';

$pdo = db();
$result = ['tokens' => 0, 'rate_limits' => 0, 'codes' => 0, 'finished_at' => date('c')];

// 1) 过期 token：分批删除，避免一次锁表
for ($i = 0; $i < 100; $i++) {
    $st = $pdo->prepare('DELETE FROM user_tokens WHERE expires_at < NOW() - INTERVAL 1 DAY LIMIT 1000');
    $st->execute();
    $n = $st->rowCount();
    $result['tokens'] += $n;
    if ($n < 1000) break;
}

// 2) 限流窗口：2 天前的记录已无用
for ($i = 0; $i < 100; $i++) {
    $st = $pdo->prepare('DELETE FROM api_rate_limits WHERE window_start < NOW() - INTERVAL 2 DAY LIMIT 1000');
    $st->execute();
    $n = $st->rowCount();
    $result['rate_limits'] += $n;
    if ($n < 1000) break;
}

// 3) 过期验证码：保留 7 天
for ($i = 0; $i < 100; $i++) {
    $st = $pdo->prepare('DELETE FROM email_verification_codes WHERE expires_at < NOW() - INTERVAL 7 DAY LIMIT 1000');
    $st->execute();
    $n = $st->rowCount();
    $result['codes'] += $n;
    if ($n < 1000) break;
}

echo json_encode($result, JSON_UNESCAPED_UNICODE) . PHP_EOL;
