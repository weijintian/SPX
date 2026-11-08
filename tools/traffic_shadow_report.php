<?php
declare(strict_types=1);
/**
 * 影子模式报告：看每个接口分类的真实峰值，用于决定是否切换拦截模式。
 *
 * 用法：php tools/traffic_shadow_report.php [小时数，默认 24]
 */
$hours = max(1, (int) ($argv[1] ?? 24));
$config = require '/var/www/html/spxchat/config.php';
$d = $config['db'];
$pdo = new PDO(
    sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $d['host'], $d['port'], $d['name']),
    $d['user'], $d['password'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
);

$limits = [
    'heartbeat' => 300, 'update' => 120, 'auth' => 20,
    'read' => 120, 'write' => 30, 'finance' => 10, 'upload' => 30,
];

echo "统计区间：最近 {$hours} 小时（每分钟一个窗口）\n\n";
$rows = $pdo->prepare('SELECT scope_key, attempts, window_start FROM api_rate_limits
    WHERE scope_key LIKE "traffic:%" AND window_start >= DATE_SUB(NOW(), INTERVAL ? HOUR)');
$rows->execute([$hours]);

$byClass = [];
foreach ($rows as $row) {
    // scope_key = traffic:<class>:<uN|ipX>
    $parts = explode(':', $row['scope_key'], 3);
    if (count($parts) < 3) continue;
    $class = $parts[1];
    $bucket = $parts[2];
    $kind = $bucket[0] === 'u' ? 'user' : 'ip';
    $key = $class . '|' . $kind;
    if (!isset($byClass[$key])) {
        $byClass[$key] = ['class' => $class, 'kind' => $kind, 'peak' => 0, 'buckets' => 0, 'over' => 0, 'peak_bucket' => ''];
    }
    $byClass[$key]['buckets']++;
    if ((int) $row['attempts'] > $byClass[$key]['peak']) {
        $byClass[$key]['peak'] = (int) $row['attempts'];
        $byClass[$key]['peak_bucket'] = $bucket;
    }
    $limit = $limits[$class] ?? 120;
    if ((int) $row['attempts'] > $limit) $byClass[$key]['over']++;
}

printf("%-11s %-6s %-8s %-8s %-10s %s\n", '分类', '维度', '分母', '峰值/分', '超限窗口', '峰值来源');
foreach ($byClass as $info) {
    $limit = $limits[$info['class']] ?? 120;
    printf(
        "%-11s %-6s %-8d %-8d %-10d 阈值 %d%s\n",
        $info['class'],
        $info['kind'] === 'user' ? '用户' : 'IP',
        $info['buckets'],
        $info['peak'],
        $info['over'],
        $limit,
        $info['over'] > 0 ? '  ⚠️ 有超限窗口' : '  ✅ 未超限'
    );
}
if (!$byClass) {
    echo "区间内还没有统计数据。\n";
}

echo "\n判定建议：\n";
$risky = array_filter($byClass, static fn(array $i): bool => $i['over'] > 0);
if (!$risky) {
    echo "  所有分类都没有超限窗口，可以把 shadow 改为 false 开启拦截。\n";
} else {
    echo "  以下分类存在超限窗口，建议先核实是攻击流量还是正常峰值，再决定是否拦截：\n";
    foreach ($risky as $info) {
        printf("    - %s（%s）：峰值 %d/分，超限 %d 个窗口\n", $info['class'], $info['kind'], $info['peak'], $info['over']);
    }
}
