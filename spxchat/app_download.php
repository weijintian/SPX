<?php
declare(strict_types=1);

/** Empty disables the iOS entry; only web links and Apple's manifest installer are accepted. */
function appIosDownloadUrl(string $value): string
{
    $url = trim($value);
    if ($url === '') return '';
    if (strlen($url) > 2048 || preg_match('/[\x00-\x20\x7f<>\\\\]/', $url)) {
        throw new InvalidArgumentException('iOS 下载地址格式无效，请填写完整的 HTTP/HTTPS 下载页面或安装链接。');
    }
    // PHP parse_url rejects Apple's empty-host installer URL; validate its query separately.
    $installerPrefix = 'itms-services://?';
    if (strncasecmp($url, $installerPrefix, strlen($installerPrefix)) === 0 && !str_contains($url, '#')) {
        parse_str(substr($url, strlen($installerPrefix)), $query);
        $manifest = $query['url'] ?? null;
        if (($query['action'] ?? '') === 'download-manifest' && is_string($manifest)
            && filter_var($manifest, FILTER_VALIDATE_URL) !== false
            && strtolower((string) parse_url($manifest, PHP_URL_SCHEME)) === 'https'
            && parse_url($manifest, PHP_URL_USER) === null && parse_url($manifest, PHP_URL_PASS) === null) return $url;
    }
    $parts = parse_url($url);
    if ($parts !== false) {
        if (isset($parts['user']) || isset($parts['pass'])) {
            throw new InvalidArgumentException('iOS 下载地址不能包含账号或密码。');
        }
        $scheme = strtolower((string) ($parts['scheme'] ?? ''));
        if (in_array($scheme, ['http', 'https'], true) && filter_var($url, FILTER_VALIDATE_URL) !== false && !empty($parts['host'])) return $url;
    }
    throw new InvalidArgumentException('iOS 下载地址须为 HTTP/HTTPS 链接，或使用 HTTPS 描述文件的 itms-services 安装链接。');
}

function appIosDownloadConfig(PDO $pdo): array
{
    $query = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
    $query->execute(['ios_download_url']);
    try { $url = appIosDownloadUrl((string) ($query->fetchColumn() ?: '')); }
    catch (InvalidArgumentException $exception) { $url = ''; }
    return ['ios_download_url' => $url, 'ios_available' => $url !== ''];
}

/** Backup mirrors are plain web links; iOS mirrors may also use Apple's manifest installer. */
function appBackupLineUrl(string $platform, string $value): string
{
    $url = trim($value);
    if ($platform === 'ios') return appIosDownloadUrl($url);
    $parts = parse_url($url);
    if (strlen($url) > 2048 || preg_match('/[\x00-\x20\x7f<>\\\\]/', $url) || $parts === false
        || isset($parts['user']) || isset($parts['pass']) || empty($parts['host'])
        || !in_array(strtolower((string) ($parts['scheme'] ?? '')), ['http', 'https'], true)
        || filter_var($url, FILTER_VALIDATE_URL) === false) {
        throw new InvalidArgumentException('Android 备用地址须为完整的 HTTP/HTTPS 链接，且不能包含账号或密码。');
    }
    return $url;
}

/** Normalizes submitted rows; blank rows are dropped so clearing a row deletes it. */
function appBackupLines(string $platform, mixed $rows): array
{
    if (!is_array($rows)) return [];
    $lines = [];
    foreach ($rows as $row) {
        if (!is_array($row)) continue;
        $name = trim((string) ($row['name'] ?? ''));
        $url = trim((string) ($row['url'] ?? ''));
        if ($name === '' && $url === '') continue;
        if ($url === '') throw new InvalidArgumentException('备用线路「' . $name . '」缺少下载地址。');
        if ($name === '') $name = '线路 ' . (count($lines) + 1);
        if (mb_strlen($name) > 40) throw new InvalidArgumentException('备用线路名称不能超过 40 个字。');
        $lines[] = ['name' => $name, 'url' => appBackupLineUrl($platform, $url), 'enabled' => !empty($row['enabled'])];
        if (count($lines) > 10) throw new InvalidArgumentException('每个平台最多配置 10 条备用线路。');
    }
    return $lines;
}

/** Defaults keep the previous hard-coded Android mirror until an admin saves the panel. */
function appBackupDownloadSettings(PDO $pdo): array
{
    $query = $pdo->query("SELECT `key`,`value` FROM app_settings WHERE `key` IN ('backup_download_enabled','android_backup_lines','ios_backup_lines')");
    $values = [];
    foreach ($query->fetchAll(PDO::FETCH_ASSOC) as $row) $values[$row['key']] = (string) $row['value'];
    $read = static function (string $platform, ?string $json, array $fallback): array {
        if ($json === null) return $fallback;
        $decoded = json_decode($json, true);
        try { return appBackupLines($platform, is_array($decoded) ? $decoded : []); }
        catch (InvalidArgumentException $exception) { return []; }
    };
    return [
        'enabled' => ($values['backup_download_enabled'] ?? '1') === '1',
        'android' => $read('android', $values['android_backup_lines'] ?? null, [['name' => '蓝奏云备用', 'url' => 'https://wwavm.lanzoue.com/spxapk', 'enabled' => true]]),
        'ios' => $read('ios', $values['ios_backup_lines'] ?? null, []),
    ];
}

/** Public website payload: only enabled backup lines are exposed. */
function appDownloadConfig(PDO $pdo): array
{
    $backup = appBackupDownloadSettings($pdo);
    $public = static fn (array $lines): array => array_values(array_map(
        static fn (array $line): array => ['name' => $line['name'], 'url' => $line['url']],
        array_filter($lines, static fn (array $line): bool => $line['enabled'])
    ));
    $iosVersion = (string) ($pdo->query("SELECT `value` FROM app_settings WHERE `key`='ios_version'")->fetchColumn() ?: '');
    return ['ios_version' => $iosVersion] + appIosDownloadConfig($pdo) + [
        'backup_enabled' => $backup['enabled'],
        'android_backup_lines' => $backup['enabled'] ? $public($backup['android']) : [],
        'ios_backup_lines' => $backup['enabled'] ? $public($backup['ios']) : [],
    ];
}

/**
 * Optional external mirror for a release APK (object storage / CDN). Only https URLs are
 * followed, so a bad row can never downgrade the download to plaintext or another scheme.
 */
function appReleaseMirror(string $value): string
{
    $url = trim($value);
    if ($url === '' || strlen($url) > 1024) return '';
    $parts = parse_url($url);
    if ($parts === false || empty($parts['host']) || isset($parts['user']) || isset($parts['pass'])) return '';
    $scheme = strtolower((string) ($parts['scheme'] ?? ''));
    $host = strtolower((string) $parts['host']);
    // 默认只跟随 https；本平台域名的 http 下载地址放行（部分老安卓握手不了 ECDSA 证书，
    // APK 本身是公开文件，客户端下载后还会校验 SHA256，明文不影响完整性）
    $ownHost = in_array($host, ['spx001.top', 'www.spx001.top'], true);
    if ($scheme !== 'https' && !($scheme === 'http' && $ownHost)) return '';
    return filter_var($url, FILTER_VALIDATE_URL) ? $url : '';
}

/** A single RFC byte range. Null means full content, false means unsatisfiable. */function appDownloadRange(?string $range, int $size): array|false|null
{
    if ($range === null || trim($range) === '') return null;
    if (!preg_match('/^bytes=(\d*)-(\d*)$/D', trim($range), $m) || ($m[1] === '' && $m[2] === '')) return false;
    if ($size <= 0) return false;
    if ($m[1] === '') {
        $suffix = (int) $m[2];
        return $suffix > 0 ? [max(0, $size - $suffix), $size - 1] : false;
    }
    $start = (int) $m[1];
    $end = $m[2] === '' ? $size - 1 : min($size - 1, (int) $m[2]);
    return $start < $size && $end >= $start ? [$start, $end] : false;
}

function appDownloadFile(string $path, string $version, string $sha256, ?callable $count = null): never
{
    $method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
    if (!in_array($method, ['GET', 'HEAD'], true)) { http_response_code(405); header('Allow: GET, HEAD'); exit; }
    $file = fopen($path, 'rb');
    if (!$file) { http_response_code(404); exit; }
    $stat = fstat($file);
    $size = (int) $stat['size']; $modified = (int) $stat['mtime'];
    // Release APK files are immutable; this digest is stored when the file is published.
    $hash = preg_match('/^[a-f0-9]{64}$/D', $sha256) ? $sha256 : hash_file('sha256', $path);
    $etag = '"' . $hash . '"';
    $rangeHeader = $_SERVER['HTTP_RANGE'] ?? null;
    $ifRange = trim((string) ($_SERVER['HTTP_IF_RANGE'] ?? ''));
    if ($ifRange !== '') {
        $date = strtotime($ifRange);
        if ($ifRange !== $etag && ($date === false || $date < $modified)) $rangeHeader = null;
    }
    // HEAD describes the entire representation; Range only applies to GET.
    $range = $method === 'HEAD' ? null : appDownloadRange($rangeHeader, $size);
    @ini_set('zlib.output_compression', '0');
    while (ob_get_level() > 0) { if (!@ob_end_clean()) break; }
    header_remove('Content-Type');
    header('Content-Type: application/vnd.android.package-archive');
    header('Accept-Ranges: bytes');
    header('ETag: ' . $etag);
    header('Last-Modified: ' . gmdate('D, d M Y H:i:s', $modified) . ' GMT');
    header('Cache-Control: private, no-cache, no-transform');
    header('X-Content-Type-Options: nosniff');
    header('X-Accel-Buffering: no');
    header('Content-Disposition: attachment; filename="spxchat-' . rawurlencode($version) . '.apk"');
    if ($range === false) {
        http_response_code(416); header('Content-Range: bytes */' . $size); header('Content-Length: 0'); fclose($file); exit;
    }
    [$start, $end] = $range ?? [0, $size - 1];
    $length = max(0, $end - $start + 1);
    if ($range !== null) { http_response_code(206); header("Content-Range: bytes $start-$end/$size"); }
    header('Content-Length: ' . $length);
    if ($method === 'HEAD') { fclose($file); exit; }
    // A resumed fragment must not count as another complete download attempt.
    if ($start === 0 && $count !== null) $count();
    set_time_limit(0);
    if ($start > 0 && fseek($file, $start) !== 0) { fclose($file); exit; }
    while ($length > 0 && !connection_aborted()) {
        $chunk = fread($file, min(262144, $length));
        if ($chunk === false || $chunk === '') break;
        echo $chunk; $length -= strlen($chunk); flush();
    }
    fclose($file); exit;
}
