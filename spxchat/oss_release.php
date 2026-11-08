<?php
declare(strict_types=1);

/**
 * 发布包同步到 OSS。
 *
 * 目标：以后每次发版，APK 自动进对象存储，`app_releases.apk_url` 自动填好，
 * 客户端（含未更新的老版本）通过 302 直接从 OSS 下载，不再走 PHP 流式输出。
 *
 * 设计原则：任何失败都不能影响本地发布。失败时返回 ok=false，apk_url 留空，
 * 下载接口会自动回退到服务器本地文件，只是速度慢一些。
 */

const OSS_RELEASE_ENV_FILE = '/etc/spxchat/oss.env';
const OSS_RELEASE_CONFIG_FILE = '/etc/spxchat/ossutilconfig';
const OSS_RELEASE_BINARY = '/usr/local/bin/ossutil';
const OSS_RELEASE_META = 'Content-Type:application/vnd.android.package-archive#Cache-Control:public, max-age=31536000, immutable';
// ossutil 默认在当前工作目录建 .ossutil_checkpoint 做断点续传，php-fpm 下没有写权限，
// 所以显式指定一个 www-data 可写的目录。
const OSS_RELEASE_CHECKPOINT_DIR = '/var/cache/spxchat-oss';

function ossReleaseEnv(): array
{
    static $env = null;
    if ($env !== null) return $env;
    $env = [];
    $lines = is_readable(OSS_RELEASE_ENV_FILE) ? file(OSS_RELEASE_ENV_FILE, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) : [];
    foreach ($lines ?: [] as $line) {
        $line = trim($line);
        if ($line === '' || $line[0] === '#' || !str_contains($line, '=')) continue;
        [$key, $value] = explode('=', $line, 2);
        $env[trim($key)] = trim($value);
    }
    return $env;
}

function ossReleaseAvailable(): bool
{
    $env = ossReleaseEnv();
    return is_file(OSS_RELEASE_BINARY)
        && is_readable(OSS_RELEASE_CONFIG_FILE)
        && trim((string) ($env['SPX_OSS_BUCKET'] ?? '')) !== '';
}

/** 对象名固定为 releases/spxchat-<版本名>-<版本代码>.apk，便于人工核对和按版本清理。 */
function ossReleaseObjectKey(string $versionName, int $versionCode): string
{
    $safe = preg_replace('/[^0-9A-Za-z._-]/', '', $versionName);
    if ($safe === '' || $safe === null) $safe = 'unknown';
    return 'releases/spxchat-' . $safe . '-' . $versionCode . '.apk';
}

function ossReleasePublicUrl(string $versionName, int $versionCode): string
{
    $env = ossReleaseEnv();
    $base = trim((string) ($env['SPX_OSS_PUBLIC_BASE'] ?? ''));
    if ($base === '') {
        $host = trim((string) ($env['SPX_OSS_PUBLIC_HOST'] ?? ''));
        if ($host === '') $host = trim((string) ($env['SPX_OSS_BUCKET'] ?? '')) . '.' . trim((string) ($env['SPX_OSS_ENDPOINT'] ?? ''));
        $base = 'https://' . $host;
    }
    return rtrim($base, '/') . '/' . ossReleaseObjectKey($versionName, $versionCode);
}

/**
 * 上传并返回 ['ok'=>bool,'url'=>string,'log'=>string]。
 * 用 coreutils timeout 兜底，避免 OSS 卡住时占死一个 php-fpm 进程。
 */
function ossReleasePush(string $localPath, string $versionName, int $versionCode, int $timeoutSeconds = 90): array
{
    $env = ossReleaseEnv();
    $bucket = trim((string) ($env['SPX_OSS_BUCKET'] ?? ''));
    if (!ossReleaseAvailable() || !is_file($localPath)) {
        return ['ok' => false, 'url' => '', 'log' => 'OSS 未配置或文件不存在'];
    }
    $key = ossReleaseObjectKey($versionName, $versionCode);
    $command = sprintf(
        'timeout %d %s -c %s cp -f %s %s --checkpoint-dir %s --meta %s 2>&1',
        max(30, $timeoutSeconds),
        escapeshellarg(OSS_RELEASE_BINARY),
        escapeshellarg(OSS_RELEASE_CONFIG_FILE),
        escapeshellarg($localPath),
        escapeshellarg('oss://' . $bucket . '/' . $key),
        escapeshellarg(OSS_RELEASE_CHECKPOINT_DIR),
        escapeshellarg(OSS_RELEASE_META)
    );
    $output = [];
    $status = 0;
    exec($command, $output, $status);
    $log = trim(implode(' ', array_slice($output, -3)));
    if ($status !== 0) {
        return ['ok' => false, 'url' => '', 'log' => 'ossutil 退出码 ' . $status . '：' . mb_substr($log, 0, 300)];
    }
    return ['ok' => true, 'url' => ossReleasePublicUrl($versionName, $versionCode), 'log' => $log];
}

/** CLI：给已有版本补 apk_url（发布时 OSS 失败、或历史版本漏传时用）。 */
function ossReleaseSyncMissing(bool $dryRun = false): int
{
    $config = require __DIR__ . '/config.php';
    $db = $config['db'];
    $pdo = new PDO(
        sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $db['host'], $db['port'], $db['name']),
        $db['user'], $db['password'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]
    );
    $rows = $pdo->query('SELECT id,version_name,version_code,file_path,apk_url FROM app_releases ORDER BY version_code DESC')->fetchAll();
    $changed = 0;
    foreach ($rows as $row) {
        if (trim((string) $row['apk_url']) !== '') {
            echo "  skip  #{$row['id']} v{$row['version_name']}（已有 apk_url）\n";
            continue;
        }
        $path = __DIR__ . $row['file_path'];
        if (!is_file($path)) {
            echo "  miss  #{$row['id']} v{$row['version_name']}（本地文件不存在：{$row['file_path']}）\n";
            continue;
        }
        if ($dryRun) {
            echo "  plan  #{$row['id']} v{$row['version_name']} -> " . ossReleasePublicUrl($row['version_name'], (int) $row['version_code']) . "\n";
            continue;
        }
        $result = ossReleasePush($path, (string) $row['version_name'], (int) $row['version_code']);
        if ($result['ok']) {
            $pdo->prepare('UPDATE app_releases SET apk_url=? WHERE id=?')->execute([$result['url'], $row['id']]);
            echo "  ok    #{$row['id']} v{$row['version_name']} -> {$result['url']}\n";
            $changed++;
        } else {
            echo "  fail  #{$row['id']} v{$row['version_name']}：{$result['log']}\n";
        }
    }
    echo $dryRun ? "\n（dry run，未改动）\n" : "\n已补齐 {$changed} 条记录\n";
    return 0;
}

if (PHP_SAPI === 'cli' && isset($argv[0]) && realpath($argv[0]) === __FILE__) {
    $mode = $argv[1] ?? '';
    if ($mode === '--sync-missing') {
        exit(ossReleaseSyncMissing(in_array('--dry-run', $argv, true)));
    }
    echo "用法：php oss_release.php --sync-missing [--dry-run]\n";
    echo "  给 app_releases 里 apk_url 为空的版本补传 OSS 并回填地址\n";
    exit(1);
}
