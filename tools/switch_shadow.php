<?php
declare(strict_types=1);
/**
 * 临时切换 traffic_guard 配置，用于验证「拦截模式」确实会返回 429。
 * 用法：php switch_shadow.php enforce   → shadow=false，update 限额 5/分
 *       php switch_shadow.php restore   → 删除配置，回到默认影子模式 + 清空计数
 */
$mode = $argv[1] ?? '';
$config = require '/var/www/html/spxchat/config.php';
$d = $config['db'];
$pdo = new PDO(
    sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $d['host'], $d['port'], $d['name']),
    $d['user'], $d['password'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
);

if ($mode === 'enforce') {
    $json = json_encode(['shadow' => false, 'enabled' => true, 'limits' => ['update' => 5]]);
    $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')
        ->execute(['traffic_guard_config', $json]);
    $pdo->exec("DELETE FROM api_rate_limits WHERE scope_key LIKE 'traffic:%'");
    echo "已切到拦截模式：update 限额 5 次/分，计数已清零\n";
} elseif ($mode === 'off') {
    $json = json_encode(['shadow' => false, 'enabled' => false]);
    $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')
        ->execute(['traffic_guard_config', $json]);
    $pdo->exec("DELETE FROM api_rate_limits WHERE scope_key LIKE 'traffic:%'");
    echo "应用层限流已完全关闭（压测用）\n";
} elseif ($mode === 'restore') {
    $pdo->exec("DELETE FROM app_settings WHERE `key`='traffic_guard_config'");
    $pdo->exec("DELETE FROM api_rate_limits WHERE scope_key LIKE 'traffic:%'");
    echo "已恢复影子模式（默认），测试计数已清空\n";
} else {
    fwrite(STDERR, "用法: php switch_shadow.php enforce|restore\n");
    exit(1);
}
