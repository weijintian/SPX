<?php
declare(strict_types=1);

require_once __DIR__ . '/response.php';
require_once __DIR__ . '/db.php';
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/smtp.php';
require_once __DIR__ . '/ws-event.php';
require_once __DIR__ . '/AvePriceService.php';
require_once __DIR__ . '/mall.php';
require_once __DIR__ . '/mall_workspace.php';
require_once __DIR__ . '/chat_automation.php';
require_once __DIR__ . '/chat_groups.php';
require_once __DIR__ . '/chat_sync.php';
require_once __DIR__ . '/chat_red_packets.php';
require_once __DIR__ . '/payment_password.php';
require_once __DIR__ . '/chat_calls.php';
require_once __DIR__ . '/app_download.php';
require_once __DIR__ . '/chat_upload.php';
require_once __DIR__ . '/financial_requests.php';
require_once __DIR__ . '/withdrawal_policy.php';
require_once __DIR__ . '/cache.php';
require_once __DIR__ . '/traffic_guard.php';
require_once __DIR__ . '/burn_tiers.php';
require_once __DIR__ . '/chat_delete.php';

/**
 * 钱包估值：返回 [symbol => ['price_usd'=>..., 'updated_at'=>...]]。
 * AVE 不可用时返回空数组，钱包接口仍然正常返回余额（只是没有估值）。
 */
function walletPrices(): array
{
    static $prices = null;
    if ($prices !== null) return $prices;
    // 价格来自外部行情接口，原先每次请求都实时拉取（首屏 ~140ms）。
    // 缓存 60 秒：价格展示允许短暂延迟，避免每次钱包请求都打外网。
    $cached = cacheGet('prices:wallet:v2', null);
    if (is_array($cached) && $cached) { $prices = $cached; return $prices; }
    $prices = [];
    try {
        $service = new AvePriceService(require __DIR__ . '/config.ave.php');
        foreach (array_keys($service->tokens()) as $symbol) {
            try {
                $prices[$symbol] = $service->priceUsd($symbol);
            } catch (Throwable $ignored) {
            }
        }
    } catch (Throwable $ignored) {
    }
    // 只在拿到有效数据时写缓存，接口异常时不要把空值缓存住
    if ($prices) cacheSet('prices:wallet:v2', $prices, 60);
    return $prices;
}

function publicBaseUrl(): string
{    $config = require __DIR__ . '/config.php';
    $configured = trim((string) ($config['app']['public_base_url'] ?? ''));
    if ($configured !== '') return rtrim($configured, '/');
    $forwardedProto = strtolower(trim(explode(',', (string) ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? ''))[0] ?? ''));
    $scheme = $forwardedProto === 'https' || (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    $host = trim((string) ($_SERVER['HTTP_X_FORWARDED_HOST'] ?? $_SERVER['HTTP_HOST'] ?? '127.0.0.1'));
    $host = trim(explode(',', $host)[0]);
    return $scheme . '://' . $host . rtrim(dirname($_SERVER['SCRIPT_NAME'] ?? '/'), '/');
}

function inviteBaseUrl(): string
{
    if (function_exists('cacheRemember')) {
        $cached = cacheRemember('setting:invite_base_url', 300, static function (): string {
            $setting = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
            $setting->execute(['invite_base_url']);
            return rtrim(trim((string) ($setting->fetchColumn() ?: '')), '/');
        });
        return $cached !== '' ? (string) $cached : 'http://spx001.top';
    }
    $setting = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
    $setting->execute(['invite_base_url']);
    $value = rtrim(trim((string) ($setting->fetchColumn() ?: '')), '/');
    return $value !== '' ? $value : 'http://spx001.top';
}

function apiClientIp(): string
{
    $config = require __DIR__ . '/config.php';
    $ip = trim((string) ($_SERVER['REMOTE_ADDR'] ?? ''));
    // Only a configured reverse proxy may supply the client address (empty list = trust nobody).
    $trusted = (array) ($config['app']['trusted_proxies'] ?? []);
    if ($trusted && in_array($ip, $trusted, true)) {
        $forwarded = trim(explode(',', (string) ($_SERVER['HTTP_X_FORWARDED_FOR'] ?? ''))[0]);
        if (filter_var($forwarded, FILTER_VALIDATE_IP)) $ip = $forwarded;
    }
    return filter_var($ip, FILTER_VALIDATE_IP) ? $ip : 'unknown';
}

function rateLimitWindow(): string
{
    return date('Y-m-d H:00:00');
}

function rateLimitAttempts(string $scopeKey): int
{
    $statement = db()->prepare('SELECT attempts FROM api_rate_limits WHERE scope_key=? AND window_start=? LIMIT 1');
    $statement->execute([$scopeKey, rateLimitWindow()]);
    return (int) $statement->fetchColumn();
}

function rateLimitBump(string $scopeKey): void
{
    db()->prepare('INSERT INTO api_rate_limits (scope_key,window_start,attempts) VALUES (?,?,1) ON DUPLICATE KEY UPDATE attempts=attempts+1')
        ->execute([$scopeKey, rateLimitWindow()]);
}

function rateLimitClear(string $scopeKey): void
{
    db()->prepare('DELETE FROM api_rate_limits WHERE scope_key=?')->execute([$scopeKey]);
}

function enforceApiRateLimit(string $scope, int $limit = 5): void
{
    $scopeKey = $scope . ':' . apiClientIp();
    rateLimitBump($scopeKey);
    if (rateLimitAttempts($scopeKey) > $limit) jsonResponse(null, '操作频繁，请稍后再试', 429);
}

/** Login quota counts failures only; a successful sign-in must never consume the caller's budget. */
function loginRateKeys(string $username): array
{
    $ip = apiClientIp();
    $account = strtolower(substr(trim($username), 0, 64));
    return ['login:' . $ip . '|' . $account, 'login-ip:' . $ip];
}

function enforceLoginRateLimit(string $username): void
{
    [$accountKey, $ipKey] = loginRateKeys($username);
    if (rateLimitAttempts($accountKey) >= 10 || rateLimitAttempts($ipKey) >= 60) {
        jsonResponse(null, '登录尝试过于频繁，请稍后再试', 429);
    }
}

function recordLoginFailure(string $username): void
{
    foreach (loginRateKeys($username) as $key) rateLimitBump($key);
}

function clearLoginFailures(string $username): void
{
    rateLimitClear(loginRateKeys($username)[0]);
}

function isTokenContractAddress(string $address): bool
{
    $normalized = strtolower(trim($address));
    return in_array($normalized, [
        '0x55d398326f99059ff775485246999027b3197955',
        '0x0e920821464625cf661a4dcedeb6406e1b48ffff',
        '0x4e4372146c2a7d4d5c8580fd8e8f0f1cfdbafb5a',
    ], true);
}

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    jsonResponse(null);
}

$action = trim((string) ($_GET['action'] ?? 'health'));
$GLOBALS['api_action'] = $action;
// 影子模式：按 IP 统计每个接口的调用量，超出阈值只记日志不拦截
apiTrafficGuard($action, null);
$method = $_SERVER['REQUEST_METHOD'];
// 只接受常规方法：避免 PUT/DELETE 被当作普通请求处理
if (!in_array($method, ['GET', 'POST', 'HEAD', 'OPTIONS'], true)) {
    header('Allow: GET, POST, HEAD, OPTIONS');
    jsonResponse(null, '不支持的请求方法', 405);
}
$body = requestBody();

try {
    pushApi($action, $method, $body);
    tencentCallApi($action, $method, $body);
    if ($action === 'app.download.config' && $method === 'GET') {
        header('Cache-Control: no-store');
        jsonResponse(appDownloadConfig(db()), '下载配置已获取');
    }
    // Wallet settlement runs from spxchat-staking-settler.timer every 60s; keeping it out of the
    // request path avoids a named lock plus two queries on every API call.
    mallHandleApi($action, $method, $body);
    mallWorkspaceApi($action, $method, $body);
    chatApi($action, $method, $body);
    chatDeleteApi($action, $method, $body);
    chatSyncApi($action, $method);
    paymentPasswordApi($action, $method, $body);
    redPacketApi($action, $method, $body);
    switch ($action) {
        case 'health':
            db()->query('SELECT 1');
            jsonResponse(['service' => 'spxchat', 'time' => date(DATE_ATOM)]);

        case 'app.config':
            $nodesSetting = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
            $nodesSetting->execute(['api_nodes']);
            $configuredNodes = json_decode((string) ($nodesSetting->fetchColumn() ?: '[]'), true);
            $nodes = [];
            if (is_array($configuredNodes)) foreach ($configuredNodes as $node) {
                if (!is_array($node) || !is_string($node['url'] ?? null) || !preg_match('#^https?://[^/\s]+(?:/[^\s]*)?$#i', $node['url'])) continue;
                $nodes[] = ['url' => rtrim((string) $node['url'], '/'), 'weight' => max(1, min(1000, (int) ($node['weight'] ?? 1))), 'enabled' => (int) ($node['enabled'] ?? 1) === 1 ? 1 : 0];
            }
            jsonResponse(['api_path' => '/spxchat/api.php', 'nodes' => $nodes], '节点配置已获取');

        case 'app.update.check':
            $release = cacheRemember('release:active', 60, static fn(): ?array => db()->query('SELECT id,version_code,version_name,release_notes,apk_size,apk_sha256,apk_url,file_path,download_count,created_at FROM app_releases WHERE is_active=1 ORDER BY version_code DESC LIMIT 1')->fetch() ?: null);
            if (!$release) jsonResponse(['update_available' => false, 'current_version_code' => max(0, (int) ($_GET['version_code'] ?? 0))]);
            $currentCode = max(0, (int) ($_GET['version_code'] ?? 0));

            // 客户端版本统计：任何异常都不能影响更新接口
            try {
                $statToken = bearerToken();
                $statUserId = 0;
                if ($statToken !== null && $statToken !== '') {
                    $statQuery = db()->prepare('SELECT user_id FROM user_tokens WHERE token=? AND expires_at>NOW() LIMIT 1');
                    $statQuery->execute([$statToken]);
                    $statUserId = (int) ($statQuery->fetchColumn() ?: 0);
                }
                if ($statUserId > 0 && $currentCode > 0) {
                    $statPlatform = strtolower(trim((string) ($_GET['platform'] ?? 'android')));
                    if (!in_array($statPlatform, ['android', 'ios'], true)) $statPlatform = 'android';
                    $statName = (int) ($release['version_code'] ?? 0) === $currentCode ? (string) $release['version_name'] : '';
                    db()->prepare('INSERT INTO app_version_stats (user_id,platform,version_code,version_name,last_ip) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE platform=VALUES(platform),version_code=VALUES(version_code),version_name=VALUES(version_name),last_ip=VALUES(last_ip),last_seen_at=NOW()')
                        ->execute([$statUserId, $statPlatform, $currentCode, $statName, $userIp = (string) ($_SERVER['REMOTE_ADDR'] ?? '')]);
                    db()->prepare('INSERT INTO app_version_daily (stat_date,user_id,version_code) VALUES (CURDATE(),?,?) ON DUPLICATE KEY UPDATE version_code=VALUES(version_code)')
                        ->execute([$statUserId, $currentCode]);
                }
            } catch (Throwable $statError) {}
            $release['id'] = (int) $release['id'];
            $release['version_code'] = (int) $release['version_code'];
            $release['apk_size'] = (int) $release['apk_size'];
            $release['download_count'] = (int) $release['download_count'];
            $release['apk_url'] = appReleaseMirror((string) ($release['apk_url'] ?? ''));
            $release['current_version_code'] = $currentCode;
            $release['update_available'] = $release['version_code'] > $currentCode;
            // 下载候选地址：OSS(CDN) 优先，客户端失败后自动降级到本域名
            $mirrorBase = rtrim((string) (db()->query("SELECT `value` FROM app_settings WHERE `key`='apk_mirror_base'")->fetchColumn() ?: ''), '/');
            if ($mirrorBase === '') $mirrorBase = 'https://oss.spx001.top/releases';
            $apkCandidates = [];
            // 国内分发节点（杭州，纯 IP + 非标端口 + Let's Encrypt IP 证书）优先
    $cnBase = rtrim((string) (db()->query("SELECT `value` FROM app_settings WHERE `key`='download_base_cn'")->fetchColumn() ?: ''), '/');
    if ($cnBase !== '' && (str_starts_with($cnBase, 'http://') || str_starts_with($cnBase, 'https://'))) {
        $apkCandidates[] = $cnBase . '/spxchat-' . $release['version_name'] . '-' . $release['version_code'] . '.apk';
    }
    $apkCandidates[] = $mirrorBase . '/spxchat-' . $release['version_name'] . '-' . $release['version_code'] . '.apk';
            // 直连 IP 的 https 地址：大陆网络对 spx001.top 域名会被重置，IP 不受影响；
    // 证书里带 IP SAN，App 内置的证书库已经信任它（和接口同一个地址）
    $downloadBase = rtrim((string) (db()->query("SELECT `value` FROM app_settings WHERE `key`='download_base_url'")->fetchColumn() ?: ''), '/');
    if ($downloadBase === '') $downloadBase = 'https://70.39.201.191';
    if (!empty($release['file_path']) && str_starts_with((string) $release['file_path'], '/uploads/app-releases/')) {
        $apkCandidates[] = $downloadBase . '/spxchat' . $release['file_path'];
    }
    if (!empty($release['apk_url'])) $apkCandidates[] = (string) $release['apk_url'];
            // 本域名下的实体文件永远作为最后兜底，避免 OSS 故障时无路可退
            if (!empty($release['file_path']) && str_starts_with((string) $release['file_path'], '/uploads/app-releases/')) {
                $localBase = rtrim((string) (db()->query("SELECT `value` FROM app_settings WHERE `key`='public_base_url'")->fetchColumn() ?: ''), '/');
        if ($localBase === '') $localBase = 'https://spx001.top';
        $apkCandidates[] = $localBase . '/spxchat' . $release['file_path'];
            }
            $release['apk_urls'] = array_values(array_unique(array_filter($apkCandidates)));
            jsonResponse($release, '版本信息已获取');

        case 'app.update.history':
            $releases = db()->query('SELECT version_code,version_name,release_notes,created_at FROM app_releases ORDER BY version_code DESC')->fetchAll();
            foreach ($releases as &$release) $release['version_code'] = (int) $release['version_code'];
            unset($release);
            jsonResponse($releases, '版本更新记录');

        case 'app.update.download':
            $releaseId = max(0, (int) ($_GET['id'] ?? 0));
            $releaseQuery = db()->prepare('SELECT id,version_name,file_path,apk_size,apk_sha256,apk_url FROM app_releases WHERE id=? AND is_active=1 LIMIT 1');
            $releaseQuery->execute([$releaseId]);
            $release = $releaseQuery->fetch();
            // A configured https mirror wins: object storage serves the APK far faster than
            // streaming 100+ MB through PHP, and older clients follow the redirect unchanged.
            $mirror = $release ? appReleaseMirror((string) ($release['apk_url'] ?? '')) : '';
            if ($mirror !== '') {
                db()->prepare('UPDATE app_releases SET download_count=download_count+1 WHERE id=? AND is_active=1')->execute([$releaseId]);
                header('Cache-Control: no-store');
                header('Location: ' . $mirror, true, 302);
                exit;
            }
            if (!$release || !preg_match('#^/uploads/app-releases/[a-f0-9]{64}-[a-f0-9]{16}\\.apk$#', (string) $release['file_path'])) {
                jsonResponse(null, '更新文件不存在', 404);
            }
            $directory = realpath(__DIR__ . '/uploads/app-releases');
            $apkPath = realpath(__DIR__ . $release['file_path']);
            if (!$directory || !$apkPath || strpos($apkPath, $directory . DIRECTORY_SEPARATOR) !== 0 || !is_file($apkPath)) {
                jsonResponse(null, '更新文件不可用', 404);
            }
            appDownloadFile($apkPath, (string) $release['version_name'], (string) $release['apk_sha256'], static function () use ($releaseId): void {
                db()->prepare('UPDATE app_releases SET download_count=download_count+1 WHERE id=? AND is_active=1')->execute([$releaseId]);
            });

        case 'auth.email.code':
            requireFields($body, ['email']);
            $email = strtolower(trim((string) $body['email']));
            if (!filter_var($email, FILTER_VALIDATE_EMAIL)) jsonResponse(null, '邮箱格式不正确', 422);
            $purpose = (string) ($body['purpose'] ?? 'register');
            if (!in_array($purpose, ['register', 'password_reset'], true)) jsonResponse(null, '验证码用途无效', 422);
            enforceApiRateLimit('email_code');
            if ($purpose === 'password_reset') {
                $account = db()->prepare('SELECT id FROM users WHERE email=? LIMIT 1');
                $account->execute([$email]);
                if (!$account->fetch()) jsonResponse(['email' => $email, 'expires_in' => 300], '验证码已发送，请查看邮箱');
            }
            $config = require __DIR__ . '/config.php';
            $recent = db()->prepare('SELECT id FROM email_verification_codes WHERE email=? AND purpose=? AND created_at > ? ORDER BY id DESC LIMIT 1');
            $recent->execute([$email, $purpose, date('Y-m-d H:i:s', time() - (int) $config['app']['verification_resend_seconds'])]);
            if ($recent->fetch()) jsonResponse(null, '验证码发送过于频繁，请稍后再试', 429);
            $code = (string) random_int(100000, 999999);
            $codeStatement = db()->prepare('INSERT INTO email_verification_codes (email,purpose,code_hash,expires_at,request_ip) VALUES (?,?,?,?,?)');
            $codeStatement->execute([
                $email,
                $purpose,
                hash('sha256', $code),
                date('Y-m-d H:i:s', time() + (int) $config['app']['verification_code_ttl']),
                $_SERVER['REMOTE_ADDR'] ?? null,
            ]);
            try {
                $label = $purpose === 'password_reset' ? '密码重置' : '注册验证';
                smtpSend($email, 'SPX Chat ' . $label . '验证码', '<div style="font-family:Arial,sans-serif"><h2>SPX Chat ' . $label . '</h2><p>你的验证码是：</p><p style="font-size:30px;letter-spacing:8px;font-weight:bold">' . htmlspecialchars($code, ENT_QUOTES, 'UTF-8') . '</p><p>验证码 5 分钟内有效。如非本人操作，请忽略此邮件。</p></div>');
            } catch (Throwable $exception) {
                db()->prepare('DELETE FROM email_verification_codes WHERE id=?')->execute([db()->lastInsertId()]);
                throw $exception;
            }
            jsonResponse(['email' => $email, 'expires_in' => (int) $config['app']['verification_code_ttl']], '验证码已发送');

        case 'auth.password.reset':
            requireFields($body, ['email', 'code', 'new_password']);
            $email = strtolower(trim((string) $body['email']));
            $code = trim((string) $body['code']);
            $newPassword = (string) $body['new_password'];
            if (!filter_var($email, FILTER_VALIDATE_EMAIL)) jsonResponse(null, '邮箱格式不正确', 422);
            if (!preg_match('/^\d{6}$/', $code)) jsonResponse(null, '验证码格式不正确', 422);
            if (strlen($newPassword) < 6) jsonResponse(null, '新密码至少 6 位', 422);
            $pdo = db();
            $pdo->beginTransaction();
            $statement = $pdo->prepare('SELECT * FROM email_verification_codes WHERE email=? AND purpose="password_reset" AND used_at IS NULL AND expires_at>NOW() ORDER BY id DESC LIMIT 1 FOR UPDATE');
            $statement->execute([$email]);
            $verification = $statement->fetch();
            if (!$verification) { $pdo->rollBack(); jsonResponse(null, '验证码错误或已过期', 422); }
            if ((int) $verification['attempts'] >= 5) { $pdo->commit(); jsonResponse(null, '验证码尝试次数过多，请重新获取', 429); }
            if (!hash_equals($verification['code_hash'], hash('sha256', $code))) {
                $pdo->prepare('UPDATE email_verification_codes SET attempts=attempts+1 WHERE id=?')->execute([$verification['id']]);
                $pdo->commit();
                jsonResponse(null, '验证码错误或已过期', 422);
            }
            $user = $pdo->prepare('SELECT id FROM users WHERE email=? LIMIT 1 FOR UPDATE');
            $user->execute([$email]);
            $userId = (int) $user->fetchColumn();
            if ($userId <= 0) { $pdo->rollBack(); jsonResponse(null, '账号不存在', 404); }
            $pdo->prepare('UPDATE users SET password_hash=? WHERE id=?')->execute([password_hash($newPassword, PASSWORD_DEFAULT), $userId]);
            $pdo->prepare('UPDATE email_verification_codes SET used_at=NOW() WHERE id=?')->execute([$verification['id']]);
            $pdo->prepare('DELETE FROM user_tokens WHERE user_id=?')->execute([$userId]);
            $pdo->commit();
            jsonResponse(null, '密码重置成功，请使用新密码登录');

        case 'auth.register':
            requireFields($body, ['email', 'code', 'username', 'password']);
            $email = strtolower(trim((string) $body['email']));
            $code = trim((string) $body['code']);
            if (!filter_var($email, FILTER_VALIDATE_EMAIL)) jsonResponse(null, '邮箱格式不正确', 422);
            if (!preg_match('/^\d{6}$/', $code)) jsonResponse(null, '验证码格式不正确', 422);
            $username = trim((string) $body['username']);
            $password = (string) $body['password'];
            if (strlen($password) < 6) jsonResponse(null, '密码至少 6 位', 422);
            $pdo = db();
            $pdo->beginTransaction();
            $codeStatement = $pdo->prepare('SELECT * FROM email_verification_codes WHERE email=? AND purpose="register" AND used_at IS NULL AND expires_at > NOW() ORDER BY id DESC LIMIT 1 FOR UPDATE');
            $codeStatement->execute([$email]);
            $verification = $codeStatement->fetch();
            if (!$verification) { $pdo->rollBack(); jsonResponse(null, '验证码错误或已过期', 422); }
            if ((int) $verification['attempts'] >= 5) { $pdo->commit(); jsonResponse(null, '验证码尝试次数过多，请重新获取', 429); }
            if (!hash_equals($verification['code_hash'], hash('sha256', $code))) {
                $pdo->prepare('UPDATE email_verification_codes SET attempts=attempts+1 WHERE id=?')->execute([$verification['id']]);
                $pdo->commit();
                jsonResponse(null, '验证码错误或已过期', 422);
            }
            $check = db()->prepare('SELECT id FROM users WHERE username = ? LIMIT 1');
            $check->execute([$username]);
            if ($check->fetch()) { $pdo->rollBack(); jsonResponse(null, '用户名已存在', 409); }
            $emailCheck = db()->prepare('SELECT id FROM users WHERE email = ? LIMIT 1');
            $emailCheck->execute([$email]);
            if ($emailCheck->fetch()) { $pdo->rollBack(); jsonResponse(null, '邮箱已注册', 409); }
            $inviterId = null;
            $inviteCode = trim((string) ($body['invite_code'] ?? ''));
            if ($inviteCode !== '') {
                $inviter = $pdo->prepare('SELECT id FROM users WHERE spx_id=? LIMIT 1');
                $inviter->execute([$inviteCode]);
                $inviterRow = $inviter->fetch();
                if (!$inviterRow) { $pdo->rollBack(); jsonResponse(null, '邀请码无效，请检查后重试', 422); }
                $inviterId = (int) $inviterRow['id'];
            }
            do {
                $spxId = 'spx' . random_int(100000, 999999);
                $idCheck = $pdo->prepare('SELECT id FROM users WHERE spx_id=? LIMIT 1');
                $idCheck->execute([$spxId]);
            } while ($idCheck->fetch());
            $statement = $pdo->prepare('INSERT INTO users (username,email,email_verified_at,invited_by_user_id,nickname,password_hash,spx_id,registration_ip,device_id,device_model,android_version,app_version) VALUES (?, ?, NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?)');
            $statement->execute([$username, $email, $inviterId, $body['nickname'] ?? $username, password_hash($password, PASSWORD_DEFAULT), $spxId, $_SERVER['REMOTE_ADDR'] ?? null, $body['device_id'] ?? null, $body['device_model'] ?? null, $body['android_version'] ?? null, $body['app_version'] ?? null]);
            $userId = (int) $pdo->lastInsertId();
            $pdo->prepare('INSERT INTO wallet_assets (user_id,symbol,chain,balance) VALUES (?,"SPX001","BSC",0),(?,"USDT","BSC",0),(?,"FISS","BSC",0)')->execute([$userId, $userId, $userId]);
            $pdo->prepare('UPDATE email_verification_codes SET used_at=NOW() WHERE id=?')->execute([$verification['id']]);
            $registrationEvents = [];
            chatGroupJoinOnRegistration($pdo, $userId, $registrationEvents);
            $pdo->commit();
            $token = issueToken($userId);
            chatPublishGroupEvents($registrationEvents);
            $userStatement = db()->prepare('SELECT id, username, email, nickname, spx_id, avatar_url, remark, created_at FROM users WHERE id = ? LIMIT 1');
            $userStatement->execute([$userId]);
            jsonResponse(['token' => $token, 'user' => $userStatement->fetch()], '注册成功', 201);

        case 'auth.login':
            requireFields($body, ['username', 'password']);
            $login = trim((string) $body['username']);
            enforceLoginRateLimit($login);
            $statement = db()->prepare('SELECT * FROM users WHERE username = ? OR email = ? LIMIT 1');
            $statement->execute([$login, strtolower($login)]);
            $user = $statement->fetch();
            if (!$user || !password_verify((string) $body['password'], $user['password_hash'])) {
                recordLoginFailure($login);
                jsonResponse(null, '用户名或密码错误', 401);
            }
            clearLoginFailures($login);
            if ((int) ($user['status'] ?? 1) !== 1) jsonResponse(null, '账号已停用，请联系平台客服', 403);
            $deviceFields = [];
            $deviceValues = [];
            foreach (['device_id', 'device_model', 'android_version', 'app_version'] as $field) {
                if (array_key_exists($field, $body) && trim((string) $body[$field]) !== '') {
                    $deviceFields[] = $field . '=?';
                    $deviceValues[] = trim((string) $body[$field]);
                    $user[$field] = trim((string) $body[$field]);
                }
            }
            if ($deviceFields) {
                $deviceValues[] = $user['id'];
                db()->prepare('UPDATE users SET ' . implode(',', $deviceFields) . ' WHERE id=?')->execute($deviceValues);
            }
            $loginGroupEvents = [];
            $pdo = db();
            $pdo->beginTransaction();
            chatGroupCompleteRegistrationJoin($pdo, (int) $user['id'], $loginGroupEvents);
            $pdo->commit();
            $token = issueToken((int) $user['id']);
            chatPublishGroupEvents($loginGroupEvents);
            unset($user['password_hash']);
            jsonResponse(['token' => $token, 'user' => $user]);

        case 'me':
            $meUser = currentUser();
            // 客户端版本上报（iOS 不走 app.update.check，从 me 采集；失败不影响主流程）
            try {
                $mePlatform = strtolower(trim((string) ($_GET['platform'] ?? '')));
                $meVersion = max(0, (int) ($_GET['version_code'] ?? 0));
                $meVersionName = trim((string) ($_GET['version_name'] ?? ''));
                if (!preg_match('/^[0-9A-Za-z._-]{1,32}$/', $meVersionName)) $meVersionName = '';
                if (!in_array($mePlatform, ['android', 'ios'], true)) {
                    $meAgent = strtolower((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''));
                    $mePlatform = (str_contains($meAgent, 'ios') || str_contains($meAgent, 'darwin')) ? 'ios' : 'android';
                }
                if ($meVersion > 0) {
                    db()->prepare('INSERT INTO app_version_stats (user_id,platform,version_code,version_name,last_ip) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE platform=VALUES(platform),version_code=VALUES(version_code),version_name=VALUES(version_name),last_ip=VALUES(last_ip),last_seen_at=NOW()')
                        ->execute([(int) $meUser['id'], $mePlatform, $meVersion, $meVersionName, (string) ($_SERVER['REMOTE_ADDR'] ?? '')]);
                    db()->prepare('INSERT INTO app_version_daily (stat_date,user_id,version_code) VALUES (CURDATE(),?,?) ON DUPLICATE KEY UPDATE version_code=VALUES(version_code)')
                        ->execute([(int) $meUser['id'], $meVersion]);
                }
            } catch (Throwable $meStatError) {}
            jsonResponse($meUser);

        case 'presence.update':
            $user = currentUser();
            requireFields($body, ['device_id', 'online']);
            $deviceId = trim((string) $body['device_id']);
            if (!preg_match('/^[A-Za-z0-9._:-]{8,128}$/', $deviceId)) jsonResponse(null, '设备编号无效', 422);
            $online = filter_var($body['online'], FILTER_VALIDATE_BOOLEAN, FILTER_NULL_ON_FAILURE);
            if ($online === null) jsonResponse(null, '在线状态无效', 422);
            $pdo = db();
            $countOnline = $pdo->prepare('SELECT COUNT(*) FROM user_presence WHERE user_id=? AND is_online=1 AND last_seen_at>=DATE_SUB(NOW(),INTERVAL 90 SECOND)');
            $countOnline->execute([$user['id']]);
            $wasOnline = (int) $countOnline->fetchColumn() > 0;
            $pdo->prepare('INSERT INTO user_presence (user_id,device_id,is_online,last_seen_at) VALUES (?,?,?,NOW()) ON DUPLICATE KEY UPDATE is_online=VALUES(is_online),last_seen_at=NOW()')->execute([$user['id'], $deviceId, $online ? 1 : 0]);
            $countOnline->execute([$user['id']]);
            $isOnline = (int) $countOnline->fetchColumn() > 0;
            if ($wasOnline !== $isOnline) {
                $friends = $pdo->prepare('SELECT contact_user_id FROM contacts WHERE user_id=? AND status="accepted"');
                $friends->execute([$user['id']]);
                foreach ($friends->fetchAll(PDO::FETCH_COLUMN) as $friendId) notifyWebSocket(['type' => 'presence.updated', 'user_id' => (int) $friendId, 'peer_user_id' => (int) $user['id'], 'is_online' => $isOnline]);
            }
            jsonResponse(['is_online' => $isOnline]);

        case 'user.profile':
            $user = currentUser();
            if (trim((string) ($_GET['id'] ?? '')) === '') jsonResponse(null, '缺少参数: id', 422);
            $statement = db()->prepare('SELECT u.id,u.nickname,u.spx_id,u.avatar_url,u.created_at,CASE WHEN c.remark IS NULL OR TRIM(c.remark)="" OR LOWER(TRIM(c.remark)) IN ("null","undefined") THEN "" ELSE TRIM(c.remark) END AS contact_remark,IF(c.id IS NULL,0,1) AS is_contact,IF(EXISTS(SELECT 1 FROM user_presence p WHERE p.user_id=u.id AND p.is_online=1 AND p.last_seen_at>=DATE_SUB(NOW(),INTERVAL 90 SECOND)),1,0) AS is_online FROM users u LEFT JOIN contacts c ON c.user_id=? AND c.contact_user_id=u.id WHERE (u.id=? OR u.spx_id=?) AND u.id<>? LIMIT 1');
            $lookup = (string) ($_GET['id'] ?? '');
            $statement->execute([$user['id'], ctype_digit($lookup) ? (int) $lookup : 0, $lookup, $user['id']]);
            $profile = $statement->fetch();
            if (!$profile) jsonResponse(null, '用户不存在', 404);
            jsonResponse($profile);

        case 'auth.logout':
            currentUser();
            $token = bearerToken();
            db()->prepare('DELETE FROM user_tokens WHERE token = ?')->execute([$token]);
            jsonResponse(null, '已退出登录');

        case 'password.update':
            $user = currentUser();
            requireFields($body, ['current_password', 'new_password']);
            // A leaked session token must not become an unlimited password-guessing oracle.
            enforceApiRateLimit('password_update-' . (int) $user['id'], 10);
            if (strlen((string) $body['new_password']) < 6) jsonResponse(null, '新密码至少 6 位', 422);
            $statement = db()->prepare('SELECT password_hash FROM users WHERE id=? LIMIT 1');
            $statement->execute([$user['id']]);
            $row = $statement->fetch();
            if (!$row || !password_verify((string) $body['current_password'], $row['password_hash'])) jsonResponse(null, '当前密码不正确', 422);
            db()->prepare('UPDATE users SET password_hash=? WHERE id=?')->execute([password_hash((string) $body['new_password'], PASSWORD_DEFAULT), $user['id']]);
            jsonResponse(null, '密码已修改');

        case 'password.email.code':
            $user = currentUser();
            $email = strtolower(trim((string) ($user['email'] ?? '')));
            if (!filter_var($email, FILTER_VALIDATE_EMAIL)) jsonResponse(null, '当前账号未绑定有效邮箱', 409);
            enforceApiRateLimit('password_change_code');
            $config = require __DIR__ . '/config.php';
            $recent = db()->prepare('SELECT id FROM email_verification_codes WHERE email=? AND purpose="password_change" AND created_at>? ORDER BY id DESC LIMIT 1');
            $recent->execute([$email, date('Y-m-d H:i:s', time() - (int) $config['app']['verification_resend_seconds'])]);
            if ($recent->fetch()) jsonResponse(null, '验证码发送过于频繁，请稍后再试', 429);
            $code = (string) random_int(100000, 999999);
            $statement = db()->prepare('INSERT INTO email_verification_codes (email,purpose,code_hash,expires_at,request_ip) VALUES (?,?,?,?,?)');
            $statement->execute([$email, 'password_change', hash('sha256', $code), date('Y-m-d H:i:s', time() + (int) $config['app']['verification_code_ttl']), $_SERVER['REMOTE_ADDR'] ?? null]);
            try {
                smtpSend($email, 'SPX Chat 修改密码验证码', '<div style="font-family:Arial,sans-serif"><h2>SPX Chat 修改密码</h2><p>你的验证码是：</p><p style="font-size:30px;letter-spacing:8px;font-weight:bold">' . htmlspecialchars($code, ENT_QUOTES, 'UTF-8') . '</p><p>验证码 5 分钟内有效。如非本人操作，请立即修改密码并联系平台客服。</p></div>');
            } catch (Throwable $exception) {
                db()->prepare('DELETE FROM email_verification_codes WHERE id=?')->execute([db()->lastInsertId()]);
                throw $exception;
            }
            jsonResponse(['email' => $email, 'expires_in' => (int) $config['app']['verification_code_ttl']], '验证码已发送');

        case 'password.update.email':
            $user = currentUser();
            requireFields($body, ['code', 'new_password']);
            $email = strtolower(trim((string) ($user['email'] ?? '')));
            $code = trim((string) $body['code']);
            $newPassword = (string) $body['new_password'];
            if (!filter_var($email, FILTER_VALIDATE_EMAIL)) jsonResponse(null, '当前账号未绑定有效邮箱', 409);
            if (!preg_match('/^\d{6}$/', $code)) jsonResponse(null, '验证码格式不正确', 422);
            if (strlen($newPassword) < 6) jsonResponse(null, '新密码至少 6 位', 422);
            $pdo = db();
            $pdo->beginTransaction();
            $verificationQuery = $pdo->prepare('SELECT * FROM email_verification_codes WHERE email=? AND purpose="password_change" AND used_at IS NULL AND expires_at>NOW() ORDER BY id DESC LIMIT 1 FOR UPDATE');
            $verificationQuery->execute([$email]);
            $verification = $verificationQuery->fetch();
            if (!$verification) { $pdo->rollBack(); jsonResponse(null, '验证码错误或已过期', 422); }
            if ((int) $verification['attempts'] >= 5) { $pdo->commit(); jsonResponse(null, '验证码尝试次数过多，请重新获取', 429); }
            if (!hash_equals((string) $verification['code_hash'], hash('sha256', $code))) {
                $pdo->prepare('UPDATE email_verification_codes SET attempts=attempts+1 WHERE id=?')->execute([$verification['id']]);
                $pdo->commit();
                jsonResponse(null, '验证码错误或已过期', 422);
            }
            $pdo->prepare('UPDATE users SET password_hash=? WHERE id=?')->execute([password_hash($newPassword, PASSWORD_DEFAULT), $user['id']]);
            $pdo->prepare('UPDATE email_verification_codes SET used_at=NOW() WHERE id=?')->execute([$verification['id']]);
            $pdo->prepare('DELETE FROM user_tokens WHERE user_id=?')->execute([$user['id']]);
            $pdo->commit();
            $token = issueToken((int) $user['id']);
            jsonResponse(['token' => $token], '密码已修改');

        case 'profile.update':
            $user = currentUser();
            $fields = [];
            $values = [];
            foreach (['nickname', 'avatar_url', 'remark', 'device_id', 'device_model', 'android_version', 'app_version'] as $field) {
                if (array_key_exists($field, $body)) {
                    $fields[] = "{$field} = ?";
                    $values[] = $body[$field];
                }
            }
            if (array_key_exists('spx_id', $body)) {
                jsonResponse(null, 'SPX ID 注册后不可修改', 403);
            }
            if ($fields) {
                $values[] = $user['id'];
                db()->prepare('UPDATE users SET ' . implode(', ', $fields) . ' WHERE id = ?')->execute($values);
            }
            jsonResponse(currentUser());

        case 'upload.avatar':
            $user = currentUser();
            if (!isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK) {
                jsonResponse(null, '请上传头像文件', 422);
            }
            $file = $_FILES['file'];
            if ($file['size'] > 5 * 1024 * 1024) jsonResponse(null, '头像不能超过 5MB', 422);
            $mime = (new finfo(FILEINFO_MIME_TYPE))->file($file['tmp_name']);
            $allowed = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp', 'image/gif' => 'gif'];
            if (!isset($allowed[$mime])) jsonResponse(null, '只支持 JPG、PNG、WEBP、GIF', 422);
            $uploadDir = __DIR__ . '/uploads';
            if (!is_dir($uploadDir)) mkdir($uploadDir, 0755, true);
            $filename = 'avatar_' . $user['id'] . '_' . bin2hex(random_bytes(8)) . '.' . $allowed[$mime];
            $target = $uploadDir . '/' . $filename;
            if (!move_uploaded_file($file['tmp_name'], $target)) jsonResponse(null, '头像保存失败', 500);
            $url = publicBaseUrl() . '/uploads/' . $filename;
            db()->prepare('UPDATE users SET avatar_url=? WHERE id=?')->execute([$url, $user['id']]);
            $recipients = db()->prepare('SELECT user_id FROM contacts WHERE contact_user_id=? UNION SELECT user_id FROM chats WHERE peer_user_id=?');
            $recipients->execute([$user['id'], $user['id']]);
            foreach ($recipients->fetchAll(PDO::FETCH_COLUMN) as $recipientId) {
                notifyWebSocket(['type' => 'profile.updated', 'user_id' => (int) $recipientId, 'peer_user_id' => (int) $user['id'], 'avatar_url' => $url]);
            }
            jsonResponse(['url' => $url], '头像上传成功');

        case 'upload.message':
            $user = currentUser();
            if (!isset($_FILES['file']) || $_FILES['file']['error'] !== UPLOAD_ERR_OK || !is_uploaded_file($_FILES['file']['tmp_name'])) jsonResponse(null, '请选择图片、视频、音频或文件', 422);
            if ($_FILES['file']['size'] > 50 * 1024 * 1024) jsonResponse(null, '超过 50 MB 的附件请使用新版 App 分块上传', 413);
            // Older installed iOS builds require HTTP 200 for a successful JSON acknowledgement.
            jsonResponse(chatSaveUpload($_FILES['file']['tmp_name'], (int)$user['id'], __DIR__ . '/uploads/messages', publicBaseUrl()), '附件上传成功');

        case 'upload.message.chunk':
            $user = currentUser();
            $result = chatReceiveChunk((int)$user['id'], $_POST, $_FILES['chunk'] ?? [], __DIR__ . '/uploads/messages', sys_get_temp_dir() . '/spxchat-upload', publicBaseUrl());
            jsonResponse($result, '媒体上传成功');

        case 'upload.message.status':
            $user = currentUser();
            jsonResponse(chatUploadStatus((int)$user['id'], $_GET, sys_get_temp_dir() . '/spxchat-upload'));

        case 'contacts':
            $user = currentUser();
            $statement = db()->prepare('SELECT u.id,u.nickname,u.spx_id,u.avatar_url,CASE WHEN c.remark IS NULL OR TRIM(c.remark)="" OR LOWER(TRIM(c.remark)) IN ("null","undefined") THEN "" ELSE TRIM(c.remark) END AS remark,IF(EXISTS(SELECT 1 FROM user_presence p WHERE p.user_id=u.id AND p.is_online=1 AND p.last_seen_at>=DATE_SUB(NOW(),INTERVAL 90 SECOND)),1,0) AS is_online,c.created_at FROM contacts c JOIN users u ON u.id=c.contact_user_id WHERE c.user_id=? AND c.status="accepted" ORDER BY c.id DESC');
            $statement->execute([$user['id']]);
            jsonResponse($statement->fetchAll());

        case 'contacts.search':
            $user = currentUser();
            $keyword = trim((string) ($_GET['q'] ?? ''));
            if ($keyword === '') jsonResponse([]);
            // Email only matches exactly: a partial match would let any account enumerate addresses.
            $statement = db()->prepare('SELECT u.id,u.nickname,u.spx_id,u.avatar_url,CASE WHEN c.remark IS NULL OR TRIM(c.remark)="" OR LOWER(TRIM(c.remark)) IN ("null","undefined") THEN "" ELSE TRIM(c.remark) END AS remark,IF(EXISTS(SELECT 1 FROM user_presence p WHERE p.user_id=u.id AND p.is_online=1 AND p.last_seen_at>=DATE_SUB(NOW(),INTERVAL 90 SECOND)),1,0) AS is_online FROM users u LEFT JOIN contacts c ON c.user_id=? AND c.contact_user_id=u.id WHERE u.id<>? AND (u.spx_id LIKE ? OR u.email = ? OR u.nickname LIKE ? OR CAST(u.id AS CHAR)=?) ORDER BY (u.spx_id=? OR u.email=?) DESC LIMIT 20');
            $like = '%' . $keyword . '%';
            $statement->execute([$user['id'], $user['id'], $like, strtolower($keyword), $like, $keyword, $keyword, strtolower($keyword)]);
            jsonResponse($statement->fetchAll());

        case 'contacts.add':
            $user = currentUser();
            if (empty($body['spx_id']) && empty($body['email'])) jsonResponse(null, '请提供 SPX ID 或邮箱', 422);
            $lookup = trim((string) ($body['spx_id'] ?? $body['email']));
            $peerStatement = db()->prepare('SELECT id FROM users WHERE spx_id=? OR email=? LIMIT 1');
            $peerStatement->execute([$lookup, strtolower($lookup)]);
            $peer = $peerStatement->fetch();
            if (!$peer) jsonResponse(null, '未找到该 SPX 用户', 404);
            if ((int) $peer['id'] === (int) $user['id']) jsonResponse(null, '不能添加自己', 422);
            db()->prepare('INSERT IGNORE INTO contacts (user_id,contact_user_id,remark,status) VALUES (?,?,?,"accepted"),(?,?,?,"accepted")')->execute([
                $user['id'], $peer['id'], $body['remark'] ?? null,
                $peer['id'], $user['id'], null,
            ]);
            jsonResponse(['user_id' => (int) $peer['id']], '好友已添加', 201);

        case 'contacts.remark':
            $user = currentUser();
            if (!array_key_exists('contact_user_id', $body) || trim((string) $body['contact_user_id']) === '') jsonResponse(null, '缺少参数: contact_user_id', 422);
            if (!array_key_exists('remark', $body)) jsonResponse(null, '缺少参数: remark', 422);
            $peerId = (int) $body['contact_user_id'];
            if ($peerId <= 0 || $peerId === (int) $user['id']) jsonResponse(null, '联系人参数无效', 422);
            $remark = is_scalar($body['remark']) ? trim((string) $body['remark']) : '';
            if (in_array(strtolower($remark), ['null', 'undefined'], true)) $remark = '';
            $statement = db()->prepare('INSERT INTO contacts (user_id,contact_user_id,remark,status) VALUES (?,?,?,"accepted") ON DUPLICATE KEY UPDATE remark=VALUES(remark),status="accepted"');
            $statement->execute([$user['id'], $peerId, $remark]);
            notifyWebSocket(['type'=>'contact.remark.updated','user_id'=>(int)$user['id'],'contact_user_id'=>$peerId,'remark'=>$remark]);
            jsonResponse(null, '备注已保存');

        case 'contacts.delete':
            $user = currentUser();
            requireFields($body, ['contact_user_id']);
            $peerId = (int) $body['contact_user_id'];
            if ($peerId <= 0 || $peerId === (int) $user['id']) jsonResponse(null, '联系人参数无效', 422);
            $pdo = db();
            $sessions = $pdo->prepare('SELECT id,user_id FROM chats WHERE (user_id=? AND peer_user_id=?) OR (user_id=? AND peer_user_id=?)');
            $sessions->execute([$user['id'], $peerId, $peerId, $user['id']]);
            $sessionRows = $sessions->fetchAll();
            $pdo->beginTransaction();
            $pdo->prepare('DELETE FROM contacts WHERE (user_id=? AND contact_user_id=?) OR (user_id=? AND contact_user_id=?)')->execute([$user['id'], $peerId, $peerId, $user['id']]);
            $pdo->prepare('DELETE FROM chats WHERE (user_id=? AND peer_user_id=?) OR (user_id=? AND peer_user_id=?)')->execute([$user['id'], $peerId, $peerId, $user['id']]);
            $pdo->commit();
            foreach ($sessionRows as $session) {
                notifyWebSocket(['type' => 'conversation.deleted', 'user_id' => (int) $session['user_id'], 'chat_id' => (int) $session['id'], 'peer_user_id' => $peerId]);
            }
            $deletedChatIds = [];
            foreach ($sessionRows as $session) if ((int) $session['user_id'] === (int) $user['id']) $deletedChatIds[] = (int) $session['id'];
            jsonResponse(['deleted_chat_ids' => $deletedChatIds], '好友及双方聊天记录已删除');

        case 'chats':
            $user = currentUser();
            $statement = db()->prepare(
                'SELECT c.*, u.nickname, u.avatar_url, u.spx_id,IF(EXISTS(SELECT 1 FROM user_presence p WHERE p.user_id=u.id AND p.is_online=1 AND p.last_seen_at>=DATE_SUB(NOW(),INTERVAL 90 SECOND)),1,0) AS is_online,
                        CASE WHEN contacts.remark IS NULL OR TRIM(contacts.remark)="" OR LOWER(TRIM(contacts.remark)) IN ("null","undefined") THEN "" ELSE TRIM(contacts.remark) END AS contact_remark,
                        CASE WHEN contacts.remark IS NULL OR TRIM(contacts.remark)="" OR LOWER(TRIM(contacts.remark)) IN ("null","undefined") THEN u.nickname ELSE TRIM(contacts.remark) END AS display_name,
                        COALESCE(cs.pinned,0) AS pinned, COALESCE(cs.mute,0) AS mute, COALESCE(cs.blocked,0) AS blocked
                 FROM chats c JOIN users u ON u.id = c.peer_user_id
                 LEFT JOIN contacts ON contacts.user_id=c.user_id AND contacts.contact_user_id=c.peer_user_id
                 LEFT JOIN chat_settings cs ON cs.user_id=c.user_id AND cs.chat_id=c.id
                 WHERE c.user_id = ? ORDER BY COALESCE(cs.pinned,0) DESC,c.updated_at DESC'
            );
            $statement->execute([$user['id']]);
            jsonResponse($statement->fetchAll());

        case 'chats.create':
            $user = currentUser();
            requireFields($body, ['peer_user_id']);
            $peerId = (int) $body['peer_user_id'];
            if ($peerId === (int) $user['id']) jsonResponse(null, '不能创建与自己的会话', 422);
            $contact = db()->prepare('SELECT 1 FROM contacts mine JOIN contacts peer ON peer.user_id=mine.contact_user_id AND peer.contact_user_id=mine.user_id WHERE mine.user_id=? AND mine.contact_user_id=? AND mine.status="accepted" AND peer.status="accepted" LIMIT 1');
            $contact->execute([$user['id'], $peerId]);
            if (!$contact->fetch()) jsonResponse(null, '请先添加好友后再发起私聊', 403);
            $privacy = db()->prepare('SELECT g.id FROM chat_groups g JOIN chat_group_members a ON a.group_id=g.id AND a.user_id=? JOIN chat_group_members b ON b.group_id=g.id AND b.user_id=? WHERE g.allow_private_message=0 LIMIT 1');
            $privacy->execute([$user['id'], $peerId]);
            if ($privacy->fetch()) jsonResponse(null, '共同群组已禁止成员互相私信', 403);
            $peer = db()->prepare('SELECT id,nickname FROM users WHERE id = ? LIMIT 1');
            $peer->execute([$peerId]);
            $peerUser = $peer->fetch();
            if (!$peerUser) jsonResponse(null, '用户不存在', 404);
            $statement = db()->prepare('SELECT id FROM chats WHERE user_id = ? AND peer_user_id = ? LIMIT 1');
            $statement->execute([$user['id'], $peerId]);
            $chat = $statement->fetch();
            if (!$chat) {
                $create = db()->prepare('INSERT INTO chats (user_id,peer_user_id,title) VALUES (?,?,?)');
                $create->execute([$user['id'], $peerId, $peerUser['nickname']]);
                $chatId = (int) db()->lastInsertId();
            } else {
                $chatId = (int) $chat['id'];
            }
            jsonResponse(['chat_id' => $chatId], '会话已创建', 201);

        case 'groups':
            $user = currentUser();
            $statement = db()->prepare('SELECT g.*, gm.role, gm.unread_count, gm.mute, gm.pinned, gm.last_read_message_id, (SELECT COUNT(*) FROM chat_group_members count_members WHERE count_members.group_id=g.id) AS member_count, (SELECT gm2.content FROM chat_group_messages gm2 WHERE gm2.group_id=g.id ORDER BY gm2.id DESC LIMIT 1) AS last_message, (SELECT gm2.message_type FROM chat_group_messages gm2 WHERE gm2.group_id=g.id ORDER BY gm2.id DESC LIMIT 1) AS last_message_type, (SELECT gm2.created_at FROM chat_group_messages gm2 WHERE gm2.group_id=g.id ORDER BY gm2.id DESC LIMIT 1) AS last_message_at FROM chat_groups g JOIN chat_group_members gm ON gm.group_id=g.id WHERE gm.user_id=? ORDER BY gm.pinned DESC,g.id DESC');
            $statement->execute([$user['id']]);
            $groups = $statement->fetchAll();
            $mentionQuery = db()->prepare('SELECT m.id FROM chat_group_messages m JOIN chat_group_members gm ON gm.group_id=m.group_id AND gm.user_id=? WHERE m.group_id=? AND m.id>gm.last_read_message_id AND m.sender_id<>? AND (JSON_CONTAINS(COALESCE(m.mention_user_ids,"[]"),?) OR JSON_CONTAINS(COALESCE(m.mention_user_ids,"[]"),"-1")) ORDER BY m.id DESC LIMIT 1');
            foreach ($groups as &$groupRow) {
                $mentionQuery->execute([$user['id'],$groupRow['id'],$user['id'],json_encode((int)$user['id'])]);
                $groupRow['mention_unread'] = (int) ($mentionQuery->fetchColumn() ?: 0);
            }
            unset($groupRow);
            jsonResponse($groups);

        case 'groups.info':
            $user = currentUser();
            $groupId = (int) ($_GET['group_id'] ?? 0);
            if ($groupId <= 0) jsonResponse(null, '缺少参数: group_id', 422);
            $statement = db()->prepare('SELECT g.id,g.group_code,g.owner_user_id,g.name,g.avatar_url,g.announcement,g.allow_member_details,g.allow_private_message,g.announcement_version,gm.role,COUNT(all_members.id) AS member_count FROM chat_groups g JOIN chat_group_members gm ON gm.group_id=g.id AND gm.user_id=? JOIN chat_group_members all_members ON all_members.group_id=g.id WHERE g.id=? GROUP BY g.id,g.group_code,g.owner_user_id,g.name,g.avatar_url,g.announcement,g.allow_member_details,g.allow_private_message,g.announcement_version,gm.role LIMIT 1');
            $statement->execute([$user['id'], $groupId]);
            $group = $statement->fetch();
            if (!$group) jsonResponse(null, '群组不存在或无权访问', 404);
            $role = (string) $group['role'];
            $group['member_count'] = (int) $group['member_count'];
            $group['permissions'] = [
                'view_info' => true,
                'view_members' => (int) $group['allow_member_details'] === 1 || in_array($role, ['owner', 'admin'], true),
                'update_group' => in_array($role, ['owner', 'admin'], true),
                'manage_settings' => in_array($role, ['owner', 'admin'], true),
                'invite_members' => in_array($role, ['owner', 'admin'], true),
                'transfer_owner' => $role === 'owner',
                'dissolve_group' => $role === 'owner',
            ];
            jsonResponse($group);

        case 'groups.search':
            currentUser();
            $groupCode = strtoupper(trim((string) ($_GET['group_code'] ?? '')));
            if (!preg_match('/^[A-Z0-9]{6}$/', $groupCode)) jsonResponse(null, '群号格式不正确', 422);
            $statement = db()->prepare('SELECT g.id,g.group_code,g.name,g.avatar_url,g.announcement,g.announcement_version,(SELECT COUNT(*) FROM chat_group_members gm WHERE gm.group_id=g.id) AS member_count FROM chat_groups g WHERE g.group_code=? LIMIT 1');
            $statement->execute([$groupCode]);
            $group = $statement->fetch();
            if (!$group) jsonResponse(null, '群组不存在', 404);
            $group['member_count'] = (int) $group['member_count'];
            jsonResponse($group);

        case 'groups.join':
            $user = currentUser();
            requireFields($body, ['group_code']);
            $groupCode = strtoupper(trim((string) $body['group_code']));
            if (!preg_match('/^[A-Z0-9]{6}$/', $groupCode)) jsonResponse(null, '群号格式不正确', 422);
            $pdo = db();
            $pdo->beginTransaction();
            $group = $pdo->prepare('SELECT id,group_code,owner_user_id FROM chat_groups WHERE group_code=? FOR UPDATE');
            $group->execute([$groupCode]);
            $groupRow = $group->fetch();
            if (!$groupRow) { $pdo->rollBack(); jsonResponse(null, '群组不存在', 404); }
            $groupId = (int) $groupRow['id'];
            chatGroupAssertNotBanned($pdo, $groupId, (int) $user['id']);
            $member = $pdo->prepare('SELECT role FROM chat_group_members WHERE group_id=? AND user_id=? FOR UPDATE');
            $member->execute([$groupId, $user['id']]);
            $existingRole = $member->fetchColumn();
            $joinRole=(int)$groupRow['owner_user_id']===(int)$user['id']?'owner':'member';
            if ($existingRole !== false) {
                if($joinRole==='owner' && $existingRole!=='owner'){$pdo->prepare("UPDATE chat_group_members SET role='owner' WHERE group_id=? AND user_id=?")->execute([$groupId,$user['id']]);$existingRole='owner';}
                $pdo->commit();
                jsonResponse(['group_id' => $groupId, 'group_code' => $groupRow['group_code'], 'role' => $existingRole, 'joined' => false], '你已在该群聊中');
            }
            $pdo->prepare('INSERT INTO chat_group_members (group_id,user_id,role) VALUES (?,?,?)')->execute([$groupId, $user['id'], $joinRole]);
            $events = [];
            chatGroupWelcome($pdo, $groupId, (int)$user['id'], $events);
            $memberCount = chatGroupMembershipEvents($pdo, $groupId, $events);
            $pdo->commit();
            chatPublishGroupEvents($events);
            jsonResponse(['group_id' => $groupId, 'group_code' => $groupRow['group_code'], 'member_count' => $memberCount, 'role' => $joinRole, 'joined' => true], '已加入群聊', 201);

        case 'groups.create':
            $user = currentUser();
            requireFields($body, ['name']);
            $memberIds = is_array($body['member_ids'] ?? null) ? array_map('intval', $body['member_ids']) : [];
            $memberIds[] = (int) $user['id'];
            $memberIds = array_values(array_unique(array_filter($memberIds)));
            if (count($memberIds) < 2) jsonResponse(null, '至少选择一位好友加入群聊', 422);
            $pdo = db();
            $pdo->beginTransaction();
            if ($memberIds) {
                $placeholders = implode(',', array_fill(0, count($memberIds), '?'));
                $existing = $pdo->prepare("SELECT id FROM users WHERE id IN ({$placeholders})");
                $existing->execute($memberIds);
                if (count($existing->fetchAll()) !== count($memberIds)) {
                    $pdo->rollBack();
                    jsonResponse(null, '群成员中包含不存在的用户', 422);
                }
            }
            $friendIds = array_values(array_diff($memberIds, [(int) $user['id']]));
            $friendPlaceholders = implode(',', array_fill(0, count($friendIds), '?'));
            $friends = $pdo->prepare("SELECT COUNT(*) FROM users target JOIN contacts mine ON mine.user_id=? AND mine.contact_user_id=target.id AND mine.status='accepted' JOIN contacts peer ON peer.user_id=target.id AND peer.contact_user_id=? AND peer.status='accepted' WHERE target.id IN ({$friendPlaceholders})");
            $friends->execute(array_merge([(int) $user['id'], (int) $user['id']], $friendIds));
            if ((int) $friends->fetchColumn() !== count($friendIds)) {
                $pdo->rollBack();
                jsonResponse(null, '只能邀请已接受的好友加入群聊', 403);
            }
            $announcement = array_key_exists('announcement', $body) ? trim((string) $body['announcement']) : null;
            $create = null;
            $groupCode = '';
            for ($attempt = 0; $attempt < 10; $attempt++) {
                $groupCode = generateGroupCode();
                try {
                    $create = $pdo->prepare('INSERT INTO chat_groups (group_code,owner_user_id,name,avatar_url,announcement,announcement_version) VALUES (?,?,?,?,?,?)');
                    $create->execute([$groupCode, $user['id'], trim((string) $body['name']), $body['avatar_url'] ?? null, $announcement, $announcement === null || $announcement === '' ? 0 : 1]);
                    break;
                } catch (PDOException $exception) {
                    if ((string) ($exception->errorInfo[0] ?? '') !== '23000' || (int) ($exception->errorInfo[1] ?? 0) !== 1062 || $attempt === 9) throw $exception;
                }
            }
            $groupId = (int) $pdo->lastInsertId();
            $member = $pdo->prepare('INSERT IGNORE INTO chat_group_members (group_id,user_id,role) VALUES (?,?,?)');
            foreach ($memberIds as $memberId) $member->execute([$groupId, $memberId, $memberId === (int) $user['id'] ? 'owner' : 'member']);
            $events = [];
            foreach ($friendIds as $memberId) chatGroupWelcome($pdo, $groupId, $memberId, $events);
            chatGroupMembershipEvents($pdo, $groupId, $events);
            $pdo->commit();
            chatPublishGroupEvents($events);
            jsonResponse(['group_id' => $groupId, 'group_code' => $groupCode, 'member_count' => count($memberIds)], '群聊已创建', 201);

        case 'groups.update':
            $user = currentUser();
            requireFields($body, ['group_id']);
            $groupId = (int) $body['group_id'];
            $pdo = db();
            $groupStatement = $pdo->prepare('SELECT * FROM chat_groups WHERE id=? FOR UPDATE');
            $pdo->beginTransaction();
            $groupStatement->execute([$groupId]);
            $group = $groupStatement->fetch();
            if (!$group) { $pdo->rollBack(); jsonResponse(null, '群组不存在', 404); }
            $roleStatement = $pdo->prepare('SELECT role FROM chat_group_members WHERE group_id=? AND user_id=? LIMIT 1');
            $roleStatement->execute([$groupId, $user['id']]);
            $role = $roleStatement->fetchColumn();
            if (!in_array($role, ['owner', 'admin'], true)) { $pdo->rollBack(); jsonResponse(null, '群组不存在或没有管理权限', 403); }
            $fields = [];
            $values = [];
            foreach (['name', 'avatar_url', 'announcement'] as $field) {
                if (array_key_exists($field, $body)) {
                    $value = $field === 'announcement' ? trim((string) $body[$field]) : $body[$field];
                    $fields[] = "{$field}=?";
                    $values[] = $value;
                    if ($field === 'announcement' && (string) $group['announcement'] !== (string) $value) {
                        $fields[] = 'announcement_version=announcement_version+1';
                    }
                }
            }
            if (!$fields) { $pdo->rollBack(); jsonResponse(null, '没有需要更新的群资料', 422); }
            $values[] = $groupId;
            $statement = $pdo->prepare('UPDATE chat_groups SET ' . implode(',', $fields) . ' WHERE id=?');
            $statement->execute($values);
            $pdo->commit();
            jsonResponse(null, '群资料已更新');

        case 'groups.members.add':
            $user = currentUser();
            requireFields($body, ['group_id', 'user_ids']);
            if (!is_array($body['user_ids'])) jsonResponse(null, 'user_ids 必须是数组', 422);
            $pdo = db();
            $pdo->beginTransaction();
            // Serialize joins/invites for the same group before checking membership and creating welcomes.
            $lock = $pdo->prepare('SELECT id FROM chat_groups WHERE id=? FOR UPDATE');
            $lock->execute([(int)$body['group_id']]);
            if (!$lock->fetch()) { $pdo->rollBack(); jsonResponse(null, '群组不存在', 404); }
            $owner = $pdo->prepare('SELECT gm.role FROM chat_groups g JOIN chat_group_members gm ON gm.group_id=g.id AND gm.user_id=? WHERE g.id=? LIMIT 1');
            $owner->execute([$user['id'], (int) $body['group_id']]);
            $inviterRole = $owner->fetchColumn();
            if (!in_array($inviterRole, ['owner', 'admin'], true)) { $pdo->rollBack(); jsonResponse(null, '只有群主或管理员可以邀请成员', 403); }
            $memberIds = array_values(array_unique(array_filter(array_map('intval', $body['user_ids']))));
            if (!$memberIds) { $pdo->rollBack(); jsonResponse(null, '至少选择一名成员', 422); }
            $placeholders = implode(',', array_fill(0, count($memberIds), '?'));
            $existing = $pdo->prepare("SELECT id FROM users WHERE id IN ({$placeholders})");
            $existing->execute($memberIds);
            if (count($existing->fetchAll()) !== count($memberIds)) { $pdo->rollBack(); jsonResponse(null, '成员中包含不存在的用户', 422); }
            $friends = $pdo->prepare("SELECT COUNT(*) FROM users target JOIN contacts mine ON mine.user_id=? AND mine.contact_user_id=target.id AND mine.status='accepted' JOIN contacts peer ON peer.user_id=target.id AND peer.contact_user_id=? AND peer.status='accepted' WHERE target.id IN ({$placeholders})");
            $friends->execute(array_merge([$user['id'], $user['id']], $memberIds));
            if ((int) $friends->fetchColumn() !== count($memberIds)) { $pdo->rollBack(); jsonResponse(null, '只能邀请已接受的好友加入群聊', 403); }
            $insert = $pdo->prepare('INSERT IGNORE INTO chat_group_members (group_id,user_id,role) VALUES (?,? ,"member")');
            $added = 0;
            $events = [];
            foreach ($memberIds as $memberId) {
                chatGroupAssertNotBanned($pdo, (int) $body['group_id'], $memberId);
                $insert->execute([(int) $body['group_id'], $memberId]);
                if ($insert->rowCount() > 0) {
                    $added++;
                    chatGroupWelcome($pdo, (int)$body['group_id'], $memberId, $events);
                }
            }
            $memberCount = chatGroupMembershipEvents($pdo, (int)$body['group_id'], $events);
            $pdo->commit();
            chatPublishGroupEvents($events);
            jsonResponse(['added' => $added, 'member_count' => $memberCount], '群成员已更新');

        case 'groups.members.role':
        case 'groups.members.remove':
        case 'groups.members.mute':
            $user = currentUser();
            requireFields($body, ['group_id', 'user_id']);
            if ($action === 'groups.members.role') requireFields($body, ['role']);
            if ($action === 'groups.members.mute') requireFields($body, ['mute']);
            $groupId = (int) $body['group_id'];
            $targetId = (int) $body['user_id'];
            $pdo = db();
            $pdo->beginTransaction();
            $owner = $pdo->prepare('SELECT owner_user_id FROM chat_groups WHERE id=? FOR UPDATE');
            $owner->execute([$groupId]);
            $group = $owner->fetch();
            if (!$group) { $pdo->rollBack(); jsonResponse(null, '群组不存在', 404); }
            $actor = db()->prepare('SELECT role FROM chat_group_members WHERE group_id=? AND user_id=? LIMIT 1');
            $actor->execute([$groupId, $user['id']]);
            $actorRole = (string) $actor->fetchColumn();
            if (!in_array($actorRole, ['owner', 'admin'], true)) { $pdo->rollBack(); jsonResponse(null, '只有群主或管理员可以管理成员', 403); }
            $member = db()->prepare('SELECT role FROM chat_group_members WHERE group_id=? AND user_id=? LIMIT 1');
            $member->execute([$groupId, $targetId]);
            $target = $member->fetch();
            if (!$target) { $pdo->rollBack(); jsonResponse(null, '群成员不存在', 404); }
            if ($target['role'] === 'owner') { $pdo->rollBack(); jsonResponse(null, '不能修改群主权限', 409); }
            if ($actorRole === 'admin' && $target['role'] === 'admin') { $pdo->rollBack(); jsonResponse(null, '管理员不能管理其他管理员', 403); }
            if ($action === 'groups.members.role') {
                $role = (string) $body['role'];
                if (!in_array($role, ['admin', 'member'], true)) { $pdo->rollBack(); jsonResponse(null, '角色仅支持 admin 或 member', 422); }
                if ($actorRole !== 'owner' && $role === 'admin') { $pdo->rollBack(); jsonResponse(null, '只有群主可以设置管理员', 403); }
                $pdo->prepare('UPDATE chat_group_members SET role=? WHERE group_id=? AND user_id=?')->execute([$role, $groupId, $targetId]);
                $pdo->commit();
                jsonResponse(['role' => $role], '成员角色已更新');
            }
            if ($action === 'groups.members.remove') {
                $ban = filter_var($body['ban'] ?? false, FILTER_VALIDATE_BOOLEAN, FILTER_NULL_ON_FAILURE);
                if ($ban === null) { $pdo->rollBack(); jsonResponse(null, '拉黑选项无效', 422); }
                if ($ban) {
                    $pdo->prepare('INSERT INTO chat_group_bans (group_id,user_id,banned_by) VALUES (?,?,?) ON DUPLICATE KEY UPDATE banned_by=VALUES(banned_by),created_at=CURRENT_TIMESTAMP')->execute([$groupId, $targetId, $user['id']]);
                }
                $pdo->prepare('DELETE FROM chat_group_members WHERE group_id=? AND user_id=?')->execute([$groupId, $targetId]);
                $events = [];
                chatGroupMembershipEvents($pdo, $groupId, $events);
                $pdo->commit();
                notifyWebSocket(['type' => 'group.member_removed', 'user_id' => $targetId, 'group_id' => $groupId]);
                chatPublishGroupEvents($events);
                jsonResponse(['banned' => $ban], $ban ? '成员已拉黑并移出，无法再次入群' : '成员已移出群聊');
            }
            $pdo->prepare('UPDATE chat_group_members SET mute=? WHERE group_id=? AND user_id=?')->execute([(int) ((bool) $body['mute']), $groupId, $targetId]);
            $pdo->commit();
            jsonResponse(['mute' => (int) ((bool) $body['mute'])], '成员禁言状态已更新');

        case 'groups.mentions':
            $user = currentUser();
            $groupId = (int) ($_GET['group_id'] ?? 0);
            $membership = db()->prepare('SELECT gm.role,g.allow_member_details FROM chat_group_members gm JOIN chat_groups g ON g.id=gm.group_id WHERE gm.group_id=? AND gm.user_id=?');
            $membership->execute([$groupId,$user['id']]);
            $mentionAccess = $membership->fetch();
            if (!$mentionAccess) jsonResponse(null, '无权访问该群组', 403);
            if ((int)$mentionAccess['allow_member_details'] === 0 && !in_array($mentionAccess['role'], ['owner','admin'], true)) jsonResponse(null, '群主已关闭成员详情查看', 403);
            $keyword = mb_substr(trim((string) ($_GET['q'] ?? '')),0,80);
            // Resolve private display names only for the authenticated viewer.
            $sql = 'SELECT u.id,u.nickname,u.avatar_url FROM chat_group_members gm JOIN users u ON u.id=gm.user_id LEFT JOIN contacts contact ON contact.user_id=? AND contact.contact_user_id=u.id WHERE gm.group_id=? AND u.id<>?';
            $parameters = [$user['id'],$groupId,$user['id']];
            if ($keyword !== '') { $sql .= ' AND (u.nickname LIKE ? OR contact.remark LIKE ?)'; $parameters[] = '%' . $keyword . '%'; $parameters[] = '%' . $keyword . '%'; }
            $limit = $keyword === '' ? 5 : 20;
            $sql .= ' ORDER BY FIELD(gm.role,"owner","admin","member"),gm.id ASC LIMIT ' . $limit;
            $statement = db()->prepare($sql); $statement->execute($parameters);
            jsonResponse(chatViewerNames(db(), $statement->fetchAll(), (int)$user['id']));

        case 'groups.members':
            $user = currentUser();
            $groupId = (int) ($_GET['group_id'] ?? 0);
            $access = db()->prepare('SELECT gm.role,g.allow_member_details FROM chat_group_members gm JOIN chat_groups g ON g.id=gm.group_id WHERE gm.group_id=? AND gm.user_id=? LIMIT 1');
            $access->execute([$groupId, $user['id']]);
            $membership = $access->fetch();
            if (!$membership) jsonResponse(null, '无权访问该群组', 403);
            if ((int) $membership['allow_member_details'] === 0 && !in_array($membership['role'], ['owner', 'admin'], true)) jsonResponse(null, '群主已关闭成员详情查看', 403);
            $keyword = trim((string) ($_GET['q'] ?? ''));
            $sql = 'SELECT u.id,u.nickname,u.spx_id,u.avatar_url,gm.role,gm.mute,COALESCE(contact.remark,"") AS contact_remark,IF(contact.id IS NULL,0,1) AS is_contact FROM chat_group_members gm JOIN users u ON u.id=gm.user_id LEFT JOIN contacts contact ON contact.user_id=? AND contact.contact_user_id=u.id WHERE gm.group_id=?';
            $parameters = [$user['id'], $groupId];
            if ($keyword !== '') {
                $sql .= ' AND (u.nickname LIKE ? OR u.spx_id LIKE ? OR CAST(u.id AS CHAR) LIKE ? OR contact.remark LIKE ?)';
                $like = '%' . $keyword . '%';
                $parameters[] = $like;
                $parameters[] = $like;
                $parameters[] = $like;
                $parameters[] = $like;
            }
            $sql .= ' ORDER BY FIELD(gm.role,"owner","admin","member"),gm.id ASC';
            // Optional preview bound; full member search/management keeps its existing response.
            if (isset($_GET['limit'])) $sql .= ' LIMIT ' . max(1, min(20, (int) $_GET['limit']));
            $statement = db()->prepare($sql);
            $statement->execute($parameters);
            jsonResponse(chatViewerNames(db(), $statement->fetchAll(), (int)$user['id']));

        // Messages read/write endpoints live in chat_sync.php (chatSyncApi), which handles
        // messages, groups.messages, *.reconcile and *.read before this switch is reached.
        case 'groups.settings':
            $user = currentUser();
            $groupId = (int) ($_GET['group_id'] ?? $body['group_id'] ?? 0);
            if ($groupId <= 0) jsonResponse(null, '缺少参数: group_id', 422);
            $access = db()->prepare('SELECT gm.role,gm.mute,gm.pinned,g.allow_member_details,g.allow_private_message FROM chat_group_members gm JOIN chat_groups g ON g.id=gm.group_id WHERE gm.group_id=? AND gm.user_id=? LIMIT 1');
            $access->execute([$groupId, $user['id']]);
            $settings = $access->fetch();
            if (!$settings) jsonResponse(null, '无权访问该群组', 403);
            if ($method === 'GET') {
                jsonResponse([
                    'group_id' => $groupId,
                    'mute' => (int) $settings['mute'],
                    'pinned' => (int) $settings['pinned'],
                    'allow_member_details' => (int) $settings['allow_member_details'],
                    'allow_private_message' => (int) $settings['allow_private_message'],
                ]);
            }
            $memberFields = [];
            $memberValues = [];
            foreach (['mute', 'pinned'] as $field) {
                if (array_key_exists($field, $body)) {
                    $memberFields[] = "{$field}=?";
                    $memberValues[] = !empty($body[$field]) ? 1 : 0;
                }
            }
            $groupFields = [];
            $groupValues = [];
            foreach (['allow_member_details', 'allow_private_message'] as $field) {
                if (array_key_exists($field, $body)) {
                    $groupFields[] = "{$field}=?";
                    $groupValues[] = !empty($body[$field]) ? 1 : 0;
                }
            }
            if ($groupFields && !in_array($settings['role'], ['owner', 'admin'], true)) jsonResponse(null, '只有群主或管理员可以修改群敏感设置', 403);
            if (!$memberFields && !$groupFields) jsonResponse(null, '没有可更新的设置', 422);
            $pdo = db();
            $pdo->beginTransaction();
            if ($memberFields) {
                $memberValues[] = $groupId;
                $memberValues[] = $user['id'];
                $pdo->prepare('UPDATE chat_group_members SET ' . implode(',', $memberFields) . ' WHERE group_id=? AND user_id=?')->execute($memberValues);
            }
            if ($groupFields) {
                $groupValues[] = $groupId;
                $pdo->prepare('UPDATE chat_groups SET ' . implode(',', $groupFields) . ' WHERE id=?')->execute($groupValues);
            }
            $pdo->commit();
            jsonResponse(['group_id' => $groupId], '群设置已保存');

        case 'groups.leave':
            $user = currentUser();
            requireFields($body, ['group_id']);
            $groupId = (int) $body['group_id'];
            $pdo = db();
            $pdo->beginTransaction();
            $group = $pdo->prepare('SELECT owner_user_id FROM chat_groups WHERE id=? FOR UPDATE');
            $group->execute([$groupId]);
            $ownerId = (int) $group->fetchColumn();
            if ($ownerId <= 0) jsonResponse(null, '群聊不存在', 404);
            $member = $pdo->prepare('SELECT 1 FROM chat_group_members WHERE group_id=? AND user_id=?');
            $member->execute([$groupId, $user['id']]);
            if (!$member->fetch()) jsonResponse(null, '你已不在该群聊中', 404);
            $count = $pdo->prepare('SELECT COUNT(*) FROM chat_group_members WHERE group_id=?');
            $count->execute([$groupId]);
            if ($ownerId === (int) $user['id'] && (int) $count->fetchColumn() > 1) jsonResponse(null, '群主请先转让群主身份后再退出', 409);
            $pdo->prepare('DELETE FROM chat_group_members WHERE group_id=? AND user_id=?')->execute([$groupId, $user['id']]);
            $remaining = $pdo->prepare('SELECT COUNT(*) FROM chat_group_members WHERE group_id=?');
            $remaining->execute([$groupId]);
            if ((int) $remaining->fetchColumn() === 0) $pdo->prepare('DELETE FROM chat_groups WHERE id=?')->execute([$groupId]);
            $events = [];
            chatGroupMembershipEvents($pdo, $groupId, $events);
            $pdo->commit();
            notifyWebSocket(['type'=>'group.member_removed', 'user_id'=>(int)$user['id'], 'group_id'=>$groupId]);
            chatPublishGroupEvents($events);
            jsonResponse(null, '已退出群聊');

        case 'groups.transfer':
            $user = currentUser();
            requireFields($body, ['group_id', 'owner_user_id']);
            $groupId = (int) $body['group_id'];
            $newOwnerId = (int) $body['owner_user_id'];
            if ($newOwnerId <= 0 || $newOwnerId === (int) $user['id']) jsonResponse(null, '新群主必须是其他成员', 422);
            $pdo = db();
            $pdo->beginTransaction();
            $group = $pdo->prepare('SELECT owner_user_id FROM chat_groups WHERE id=? FOR UPDATE');
            $group->execute([$groupId]);
            $groupRow = $group->fetch();
            if (!$groupRow) { $pdo->rollBack(); jsonResponse(null, '群组不存在', 404); }
            if ((int) $groupRow['owner_user_id'] !== (int) $user['id']) { $pdo->rollBack(); jsonResponse(null, '只有群主可以转让群主身份', 403); }
            $target = $pdo->prepare('SELECT role FROM chat_group_members WHERE group_id=? AND user_id=? FOR UPDATE');
            $target->execute([$groupId, $newOwnerId]);
            if (!$target->fetch()) { $pdo->rollBack(); jsonResponse(null, '新群主必须是群成员', 422); }
            $pdo->prepare('UPDATE chat_groups SET owner_user_id=? WHERE id=?')->execute([$newOwnerId, $groupId]);
            $pdo->prepare('UPDATE chat_group_members SET role="member" WHERE group_id=? AND role="owner" AND user_id<>?')->execute([$groupId, $newOwnerId]);
            $pdo->prepare('UPDATE chat_group_members SET role="owner" WHERE group_id=? AND user_id=?')->execute([$groupId, $newOwnerId]);
            $pdo->commit();
            jsonResponse(['group_id' => $groupId, 'owner_user_id' => $newOwnerId], '群主已转让');

        case 'groups.dissolve':
            $user = currentUser();
            requireFields($body, ['group_id']);
            $groupId = (int) $body['group_id'];
            $pdo = db();
            $pdo->beginTransaction();
            $group = $pdo->prepare('SELECT owner_user_id FROM chat_groups WHERE id=? FOR UPDATE');
            $group->execute([$groupId]);
            $groupRow = $group->fetch();
            if (!$groupRow) { $pdo->rollBack(); jsonResponse(null, '群组不存在', 404); }
            if ((int) $groupRow['owner_user_id'] !== (int) $user['id']) { $pdo->rollBack(); jsonResponse(null, '只有群主可以解散群聊', 403); }
            $members = $pdo->prepare('SELECT user_id FROM chat_group_members WHERE group_id=?');
            $members->execute([$groupId]);
            $memberIds = array_map('intval', $members->fetchAll(PDO::FETCH_COLUMN));
            $pdo->prepare('DELETE FROM chat_groups WHERE id=?')->execute([$groupId]);
            $pdo->commit();
            foreach ($memberIds as $memberId) notifyWebSocket(['type' => 'group.deleted', 'user_id' => $memberId, 'group_id' => $groupId]);
            jsonResponse(['group_id' => $groupId], '群聊已解散');

        case 'groups.messages.edit':
            $user = currentUser();
            requireFields($body, ['group_id', 'message_id', 'content']);
            try { $content = chatText($body['content']); } catch (InvalidArgumentException $e) { jsonResponse(null,$e->getMessage(),422); }
            if ($content === '') jsonResponse(null, '消息内容不能为空', 422);
            $pdo = db();
            try { chatAccess($pdo,(int)$user['id'],'group',(int)$body['group_id']); } catch (DomainException $e) { jsonResponse(null,$e->getMessage(),403); }
            if (str_starts_with($content, 'SPXGROUP:')) jsonResponse(null, '群组卡片请使用分享功能发送', 422);
            $message = $pdo->prepare('SELECT id,content FROM chat_group_messages WHERE id=? AND group_id=? AND sender_id=? AND message_type="text" AND message_origin="human" LIMIT 1');
            $message->execute([(int) $body['message_id'], (int) $body['group_id'], $user['id']]);
            $editable = $message->fetch();
            if (!$editable) jsonResponse(null, '只能编辑自己发送的文本消息', 403);
            if (str_starts_with((string)$editable['content'], 'SPXGROUP:')) jsonResponse(null, '群组卡片不支持编辑', 422);
            $pdo->prepare('UPDATE chat_group_messages SET content=? WHERE id=?')->execute([$content, (int) $body['message_id']]);
            $statement = $pdo->prepare('SELECT gm.*,u.nickname,u.avatar_url FROM chat_group_messages gm JOIN users u ON u.id=gm.sender_id WHERE gm.id=?');
            $statement->execute([(int) $body['message_id']]);
            $messageData = $statement->fetch();
            $members = $pdo->prepare('SELECT user_id FROM chat_group_members WHERE group_id=?');
            $members->execute([(int) $body['group_id']]);
            foreach ($members->fetchAll() as $member) notifyWebSocket(['type' => 'message.updated', 'user_id' => (int) $member['user_id'], 'group_id' => (int) $body['group_id'], 'message' => $messageData]);
            jsonResponse(chatReplyDecorate($pdo, [$messageData], (int)$user['id'], 'group', (int)$body['group_id'])[0], '消息已编辑');

        case 'groups.messages.delete':
            $user = currentUser();
            requireFields($body, ['group_id', 'message_id']);
            $pdo = db();
            try { chatAccess($pdo,(int)$user['id'],'group',(int)$body['group_id']); } catch (DomainException $e) { jsonResponse(null,$e->getMessage(),403); }
            $message = $pdo->prepare('SELECT id FROM chat_group_messages WHERE id=? AND group_id=? AND sender_id=? AND message_origin="human" LIMIT 1');
            $message->execute([(int) $body['message_id'], (int) $body['group_id'], $user['id']]);
            if (!$message->fetch()) jsonResponse(null, '只能删除自己发送的消息', 403);
            $pdo->prepare('DELETE FROM chat_group_messages WHERE id=?')->execute([(int) $body['message_id']]);
            $members = $pdo->prepare('SELECT user_id FROM chat_group_members WHERE group_id=?');
            $members->execute([(int) $body['group_id']]);
            foreach ($members->fetchAll() as $member) notifyWebSocket(['type' => 'message.deleted', 'user_id' => (int) $member['user_id'], 'group_id' => (int) $body['group_id'], 'message_id' => (int) $body['message_id']]);
            jsonResponse(null, '消息已为所有人删除');

        case 'chats.settings':
            $user = currentUser();
            requireFields($body, ['chat_id']);
            $chatId = (int) $body['chat_id'];
            $owned = db()->prepare('SELECT id FROM chats WHERE id = ? AND user_id = ? LIMIT 1');
            $owned->execute([$chatId, $user['id']]);
            if (!$owned->fetch()) jsonResponse(null, '会话不存在', 404);
            $allowed = ['mute', 'pinned', 'blocked', 'background_url'];
            $fields = [];
            $setValues = [];
            foreach ($allowed as $field) {
                if (array_key_exists($field, $body)) {
                    $fields[] = "{$field} = ?";
                    $setValues[] = $body[$field];
                }
            }
            if (!$fields) jsonResponse(null, '没有可更新的设置', 422);
            $pdo=db();$pdo->beginTransaction();
            $pdo->prepare('INSERT IGNORE INTO chat_settings (user_id,chat_id) VALUES (?,?)')->execute([$user['id'],$chatId]);
            $pdo->prepare('UPDATE chat_settings SET '.implode(',',$fields).' WHERE user_id=? AND chat_id=?')->execute(array_merge($setValues,[$user['id'],$chatId]));
            $pdo->commit();
            jsonResponse(['chat_id' => $chatId], '会话设置已保存');

        case 'chats.settings.get':
            $user = currentUser();
            $chatId = (int) ($_GET['chat_id'] ?? 0);
            if ($chatId <= 0) jsonResponse(null, '缺少参数: chat_id', 422);
            $owned = db()->prepare('SELECT id FROM chats WHERE id=? AND user_id=? LIMIT 1');
            $owned->execute([$chatId, $user['id']]);
            if (!$owned->fetch()) jsonResponse(null, '会话不存在', 404);
            $statement = db()->prepare('SELECT * FROM chat_settings WHERE user_id=? AND chat_id=? LIMIT 1');
            $statement->execute([$user['id'], $chatId]);
            jsonResponse($statement->fetch() ?: ['user_id' => $user['id'], 'chat_id' => $chatId, 'mute' => 0, 'pinned' => 0, 'blocked' => 0, 'background_url' => null]);

        case 'messages.clear':
            $user = currentUser();
            requireFields($body, ['chat_id']);
            db()->prepare('DELETE FROM messages WHERE chat_id = ? AND user_id = ?')->execute([(int) $body['chat_id'], $user['id']]);
            jsonResponse(null, '聊天记录已清空');

        case 'messages.clear.both':
            $user = currentUser();
            requireFields($body, ['chat_id']);
            $pdo = db();
            $chat = $pdo->prepare('SELECT id,peer_user_id FROM chats WHERE id=? AND user_id=? LIMIT 1');
            $chat->execute([(int) $body['chat_id'], $user['id']]);
            $chatRow = $chat->fetch();
            if (!$chatRow) jsonResponse(null, '会话不存在', 404);
            $peerChat = $pdo->prepare('SELECT id FROM chats WHERE user_id=? AND peer_user_id=? LIMIT 1');
            $peerChat->execute([(int) $chatRow['peer_user_id'], $user['id']]);
            $peerChatId = (int) $peerChat->fetchColumn();
            $pdo->beginTransaction();
            $pdo->prepare('DELETE FROM messages WHERE (chat_id=? AND user_id=?) OR (chat_id=? AND user_id=?)')->execute([(int) $body['chat_id'], $user['id'], $peerChatId, (int) $chatRow['peer_user_id']]);
            $pdo->prepare('UPDATE chats SET last_message=NULL,updated_at=NOW() WHERE id IN (?,?)')->execute([(int) $body['chat_id'], $peerChatId]);
            $pdo->commit();
            jsonResponse(['deleted_chat_ids' => array_values(array_filter([(int) $body['chat_id'], $peerChatId]))], '双方聊天记录已清空');

        case 'messages.edit':
            $user = currentUser();
            requireFields($body, ['chat_id', 'message_id', 'content']);
            try { $content = chatText($body['content']); } catch (InvalidArgumentException $e) { jsonResponse(null,$e->getMessage(),422); }
            if ($content === '') jsonResponse(null, '消息内容不能为空', 422);
            $pdo = db();
            try { chatAccess($pdo,(int)$user['id'],'chat',(int)$body['chat_id']); } catch (DomainException $e) { jsonResponse(null,$e->getMessage(),403); }
            $chat = $pdo->prepare('SELECT id,peer_user_id FROM chats WHERE id=? AND user_id=? LIMIT 1');
            $chat->execute([(int) $body['chat_id'], $user['id']]);
            $chatRow = $chat->fetch();
            if (!$chatRow) jsonResponse(null, '会话不存在', 404);
            if (str_starts_with($content, 'SPXGROUP:')) jsonResponse(null, '群组卡片请使用分享功能发送', 422);
            $find = $pdo->prepare('SELECT id,client_message_id,content FROM messages WHERE id=? AND chat_id=? AND user_id=? AND sender_id=? AND message_type="text" AND message_origin="human" LIMIT 1');
            $find->execute([(int) $body['message_id'], (int) $body['chat_id'], $user['id'], $user['id']]);
            $messageRow = $find->fetch();
            if (!$messageRow) jsonResponse(null, '只能编辑自己发送的文本消息', 403);
            if (str_starts_with((string)$messageRow['content'], 'SPXGROUP:')) jsonResponse(null, '群组卡片不支持编辑', 422);
            if (!$messageRow['client_message_id']) jsonResponse(null, '该旧消息不支持编辑', 409);
            $peerChat = $pdo->prepare('SELECT id FROM chats WHERE user_id=? AND peer_user_id=? LIMIT 1');
            $peerChat->execute([(int) $chatRow['peer_user_id'], $user['id']]);
            $peerChatId = (int) $peerChat->fetchColumn();
            $pdo->prepare('UPDATE messages SET content=? WHERE sender_id=? AND client_message_id=?')->execute([$content, $user['id'], $messageRow['client_message_id']]);
            foreach ([[ (int) $user['id'], (int) $body['chat_id'] ], [ (int) $chatRow['peer_user_id'], $peerChatId ]] as [$ownerId, $ownerChatId]) {
                if ($ownerChatId <= 0) continue;
                $last = $pdo->prepare('SELECT content,message_type FROM messages WHERE user_id=? AND chat_id=? ORDER BY id DESC LIMIT 1');
                $last->execute([$ownerId, $ownerChatId]);
                $latest = $last->fetch();
                $preview = !$latest ? null : chatMessagePreview($latest['message_type'],$latest['content']);
                $pdo->prepare('UPDATE chats SET last_message=?,updated_at=NOW() WHERE id=? AND user_id=?')->execute([$preview, $ownerChatId, $ownerId]);
            }
            $statement = $pdo->prepare('SELECT m.*,u.nickname,u.avatar_url FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.id=?');
            $statement->execute([(int) $body['message_id']]);
            $messageData = $statement->fetch();
            notifyWebSocket(['type' => 'message.updated', 'user_id' => (int) $user['id'], 'chat_id' => (int) $body['chat_id'], 'message' => $messageData]);
            if ($peerChatId > 0) {
                $peerMessage = $pdo->prepare('SELECT m.*,u.nickname,u.avatar_url FROM messages m JOIN users u ON u.id=m.sender_id WHERE m.user_id=? AND m.sender_id=? AND m.client_message_id=? LIMIT 1');
                $peerMessage->execute([(int) $chatRow['peer_user_id'], $user['id'], $messageRow['client_message_id']]);
                notifyWebSocket(['type' => 'message.updated', 'user_id' => (int) $chatRow['peer_user_id'], 'chat_id' => $peerChatId, 'message' => $peerMessage->fetch() ?: $messageData]);
            }
            jsonResponse($messageData, '消息已编辑');

        case 'messages.delete':
            $user = currentUser();
            requireFields($body, ['chat_id', 'message_id']);
            $pdo = db();
            $chat = $pdo->prepare('SELECT id,peer_user_id FROM chats WHERE id=? AND user_id=? LIMIT 1');
            $chat->execute([(int) $body['chat_id'], $user['id']]);
            $chatRow = $chat->fetch();
            if (!$chatRow) jsonResponse(null, '会话不存在', 404);
            $find = $pdo->prepare('SELECT id,client_message_id FROM messages WHERE id=? AND chat_id=? AND user_id=? AND sender_id=? AND message_origin="human" LIMIT 1');
            $find->execute([(int) $body['message_id'], (int) $body['chat_id'], $user['id'], $user['id']]);
            $messageRow = $find->fetch();
            if (!$messageRow) jsonResponse(null, '只能为所有人删除自己发送的消息', 403);
            if (!$messageRow['client_message_id']) jsonResponse(null, '该旧消息不支持为所有人删除', 409);
            $peerChat = $pdo->prepare('SELECT id FROM chats WHERE user_id=? AND peer_user_id=? LIMIT 1');
            $peerChat->execute([(int) $chatRow['peer_user_id'], $user['id']]);
            $peerChatId = (int) $peerChat->fetchColumn();
            $peerMessageId = 0;
            if ($peerChatId > 0) {
                $peerMessage = $pdo->prepare('SELECT id FROM messages WHERE user_id=? AND sender_id=? AND client_message_id=? LIMIT 1');
                $peerMessage->execute([(int) $chatRow['peer_user_id'], $user['id'], $messageRow['client_message_id']]);
                $peerMessageId = (int) $peerMessage->fetchColumn();
            }
            $pdo->beginTransaction();
            $pdo->prepare('DELETE FROM messages WHERE sender_id=? AND client_message_id=?')->execute([$user['id'], $messageRow['client_message_id']]);
            foreach ([[ (int) $user['id'], (int) $body['chat_id'] ], [ (int) $chatRow['peer_user_id'], $peerChatId ]] as [$ownerId, $ownerChatId]) {
                if ($ownerChatId <= 0) continue;
                $last = $pdo->prepare('SELECT content,message_type FROM messages WHERE user_id=? AND chat_id=? ORDER BY id DESC LIMIT 1');
                $last->execute([$ownerId, $ownerChatId]);
                $latest = $last->fetch();
                $preview = !$latest ? null : chatMessagePreview($latest['message_type'],$latest['content']);
                $pdo->prepare('UPDATE chats SET last_message=?,updated_at=NOW() WHERE id=? AND user_id=?')->execute([$preview, $ownerChatId, $ownerId]);
            }
            $pdo->commit();
            notifyWebSocket(['type' => 'message.deleted', 'user_id' => (int) $user['id'], 'chat_id' => (int) $body['chat_id'], 'message_id' => (int) $body['message_id']]);
            if ($peerMessageId > 0) notifyWebSocket(['type' => 'message.deleted', 'user_id' => (int) $chatRow['peer_user_id'], 'chat_id' => $peerChatId, 'message_id' => $peerMessageId]);
            jsonResponse(null, '消息已为所有人删除');

        case 'chats.delete':
            $user = currentUser();
            requireFields($body, ['chat_id']);
            db()->prepare('DELETE FROM chats WHERE id=? AND user_id=?')->execute([(int) $body['chat_id'], $user['id']]);
            jsonResponse(null, '会话已删除');

        case 'messages.search':
            $user = currentUser();
            $keyword = trim((string) ($_GET['q'] ?? ''));
            if ($keyword === '') jsonResponse([]);
            $like = '%' . $keyword . '%';
            $chatId = (int) ($_GET['chat_id'] ?? 0);
            $groupId = (int) ($_GET['group_id'] ?? 0);
            if ($chatId > 0) {
                $owned = db()->prepare('SELECT c.id,c.peer_user_id,COALESCE(NULLIF(ct.remark,""),u.nickname) AS conversation_name FROM chats c JOIN users u ON u.id=c.peer_user_id LEFT JOIN contacts ct ON ct.user_id=c.user_id AND ct.contact_user_id=c.peer_user_id WHERE c.id=? AND c.user_id=? LIMIT 1');
                $owned->execute([$chatId, $user['id']]);
                $chat = $owned->fetch();
                if (!$chat) jsonResponse(null, '会话不存在', 404);
                $statement = db()->prepare('SELECT "chat" AS kind,m.id,m.chat_id,0 AS group_id,c.peer_user_id AS conversation_user_id,? AS conversation_name,m.content,m.message_type,m.created_at FROM messages m JOIN chats c ON c.id=m.chat_id AND c.user_id=m.user_id WHERE m.user_id=? AND m.chat_id=? AND m.content LIKE ? ORDER BY m.id DESC LIMIT 100');
                $statement->execute([$chat['conversation_name'], $user['id'], $chatId, $like]);
                jsonResponse($statement->fetchAll());
            }
            if ($groupId > 0) {
                $access = db()->prepare('SELECT g.name FROM chat_groups g JOIN chat_group_members gm ON gm.group_id=g.id WHERE g.id=? AND gm.user_id=? LIMIT 1');
                $access->execute([$groupId, $user['id']]);
                $groupName = $access->fetchColumn();
                if (!$groupName) jsonResponse(null, '无权访问该群组', 403);
                $statement = db()->prepare('SELECT "group" AS kind,gm.id,0 AS chat_id,gm.group_id,0 AS conversation_user_id,? AS conversation_name,gm.content,gm.message_type,gm.created_at FROM chat_group_messages gm WHERE gm.group_id=? AND gm.content LIKE ? ORDER BY gm.id DESC LIMIT 100');
                $statement->execute([$groupName, $groupId, $like]);
                jsonResponse($statement->fetchAll());
            }
            $statement = db()->prepare('SELECT "chat" AS kind,m.id,m.chat_id,0 AS group_id,c.peer_user_id AS conversation_user_id,COALESCE(NULLIF(ct.remark,""),u.nickname) AS conversation_name,m.content,m.message_type,m.created_at FROM messages m JOIN chats c ON c.id=m.chat_id AND c.user_id=m.user_id JOIN users u ON u.id=c.peer_user_id LEFT JOIN contacts ct ON ct.user_id=m.user_id AND ct.contact_user_id=c.peer_user_id WHERE m.user_id=? AND m.content LIKE ? UNION ALL SELECT "group" AS kind,gm.id,0 AS chat_id,gm.group_id,0 AS conversation_user_id,g.name AS conversation_name,gm.content,gm.message_type,gm.created_at FROM chat_group_messages gm JOIN chat_group_members member ON member.group_id=gm.group_id AND member.user_id=? JOIN chat_groups g ON g.id=gm.group_id WHERE gm.content LIKE ? ORDER BY created_at DESC LIMIT 100');
            $statement->execute([$user['id'], $like, $user['id'], $like]);
            jsonResponse($statement->fetchAll());

        case 'products':
            jsonResponse(mallProducts(db(), max(0, (int) ($_GET['merchant_id'] ?? 0)), mb_substr(trim((string) ($_GET['q'] ?? '')), 0, 100), mb_substr(trim((string) ($_GET['category'] ?? '')), 0, 80), max(1, (int) ($_GET['page'] ?? 1))));

        case 'product':
            $pdo = db();
            $statement = $pdo->prepare(mallProductSelect() . ' WHERE p.id=? AND ' . mallVisibleWhere() . ' LIMIT 1');
            $statement->execute([(int) ($_GET['id'] ?? 0)]);
            $product = $statement->fetch();
            if (!$product) jsonResponse(null, '商品不存在', 404);
            jsonResponse(mallDecorateProduct($pdo, $product));

        case 'orders':
            $user = currentUser();
            $statement = db()->prepare('SELECT o.*,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.product_snapshot,"$.name")),p.name) AS name,p.image_url,p.product_type FROM orders o JOIN products p ON p.id=o.product_id WHERE o.user_id = ? ORDER BY o.id DESC');
            $statement->execute([$user['id']]);
            jsonResponse($statement->fetchAll());

        case 'order':
            $user = currentUser();
            $statement = db()->prepare('SELECT o.*,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.product_snapshot,"$.name")),p.name) AS name,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.product_snapshot,"$.description")),p.description) AS description,p.image_url,p.product_type FROM orders o JOIN products p ON p.id=o.product_id WHERE o.id=? AND o.user_id=? LIMIT 1');
            $statement->execute([(int) ($_GET['id'] ?? 0), $user['id']]);
            $order = $statement->fetch();
            if (!$order) jsonResponse(null, '订单不存在', 404);
            jsonResponse($order);

        case 'orders.cancel':
            $user = currentUser();
            requireFields($body, ['order_id']);
            $pdo = db();
            $pdo->beginTransaction();
            $buyerLock=$pdo->prepare("SELECT id FROM users WHERE id=? FOR UPDATE");$buyerLock->execute([$user["id"]]);
            $find = $pdo->prepare('SELECT * FROM orders WHERE id=? AND user_id=? AND status="pending" FOR UPDATE');
            $find->execute([(int) $body['order_id'], $user['id']]);
            $order = $find->fetch();
            if (!$order) { $pdo->rollBack(); jsonResponse(null, '订单不存在或已不能取消', 409); }
            mallRefundOrder($pdo,$order);
            $pdo->commit();
            if (!empty($order['merchant_id'])) {
                $owner=$pdo->prepare('SELECT owner_user_id FROM merchants WHERE id=?');$owner->execute([$order['merchant_id']]);
                if ($ownerId=$owner->fetchColumn()) mallTryFinishClosure($pdo,(int)$ownerId);
            }
            jsonResponse(null, '订单已取消，SPX 已退回余额');

        case 'wallet':
            $user = currentUser();
            redPacketSweep(db(),200,(int)$user['id']);
            $statement = db()->prepare('SELECT * FROM wallet_assets WHERE user_id = ? ORDER BY symbol ASC');
            $statement->execute([$user['id']]);
            $walletAssets = $statement->fetchAll();
            $walletPrices = walletPrices();
            foreach ($walletAssets as &$walletAsset) {
                $assetSymbol = strtoupper((string) $walletAsset['symbol']);
                $price = $walletPrices[$assetSymbol]['price_usd'] ?? null;
                $walletAsset['price_usd'] = $price !== null ? (string) $price : null;
                $walletAsset['price_updated_at'] = $walletPrices[$assetSymbol]['updated_at'] ?? null;
                if ($price !== null && function_exists('bcmul')) {
                    $walletAsset['usd_value'] = bcmul((string) $walletAsset['balance'], (string) $price, 8);
                    if (array_key_exists('stakeable_balance', $walletAsset)) {
                        $walletAsset['stakeable_usd_value'] = bcmul((string) $walletAsset['stakeable_balance'], (string) $price, 8);
                    }
                } else {
                    $walletAsset['usd_value'] = $assetSymbol === 'USDT' ? (string) $walletAsset['balance'] : null;
                }
            }
            unset($walletAsset);
            jsonResponse($walletAssets);

        case 'wallet.binding':
            $user = currentUser();
            $statement = db()->prepare('SELECT bsc_wallet_address FROM users WHERE id=? LIMIT 1');
            $statement->execute([$user['id']]);
            $address = $statement->fetchColumn();
            jsonResponse(['bound' => is_string($address) && $address !== '', 'bound_address' => $address ?: null]);

        case 'wallet.bind':
            $user = currentUser();
            requireFields($body, ['address', 'password']);
            // Wallet binding is permanent, so the password check must be rate limited like a login.
            enforceApiRateLimit('wallet_bind-' . (int) $user['id'], 10);
            $address = strtolower(trim((string) $body['address']));
            if (!preg_match('/^0x[a-f0-9]{40}$/', $address)) jsonResponse(null, 'BSC 钱包地址格式无效', 422);
            if (isTokenContractAddress($address)) jsonResponse(null, '不能绑定 USDT 或 SPX001 合约地址', 422);
            $pdo = db();
            $pdo->beginTransaction();
            $existing = $pdo->prepare('SELECT bsc_wallet_address,password_hash FROM users WHERE id=? FOR UPDATE');
            $existing->execute([$user['id']]);
            $account = $existing->fetch();
            if (!$account || !password_verify((string) $body['password'], (string) $account['password_hash'])) {
                $pdo->rollBack();
                jsonResponse(null, '密码验证失败，无法绑定钱包', 403);
            }
            $boundAddress = $account['bsc_wallet_address'];
            if (is_string($boundAddress) && $boundAddress !== '') {
                $pdo->rollBack();
                if (strtolower($boundAddress) === $address) jsonResponse(['bound' => true, 'bound_address' => $boundAddress], '该地址已绑定');
                jsonResponse(null, '钱包地址已绑定且不可修改', 409);
            }
            $addressOwner = $pdo->prepare('SELECT id FROM users WHERE bsc_wallet_address=? AND id<>? LIMIT 1');
            $addressOwner->execute([$address, $user['id']]);
            if ($addressOwner->fetch()) { $pdo->rollBack(); jsonResponse(null, '该钱包地址已绑定其他账户', 409); }
            $pdo->prepare('UPDATE users SET bsc_wallet_address=? WHERE id=? AND bsc_wallet_address IS NULL')->execute([$address, $user['id']]);
            $pdo->commit();
            jsonResponse(['bound' => true, 'bound_address' => $address], '钱包地址绑定成功', 201);

        case 'wallet.exchange.quote':
            currentUser();
            $aveConfig = require __DIR__ . '/config.ave.php';
            $service = new AvePriceService($aveConfig);
            $snapshot = $service->getSnapshot();
            $quote = $service->calcSpxAmount('1', $snapshot);
            jsonResponse([
                'symbol' => $quote['symbol'],
                'name' => $quote['name'],
                'token_id' => $quote['token_id'],
                'api_price_usd' => $quote['api_price'],
                'exchange_price_usd' => $quote['exchange_price'],
                'fee_rate' => $quote['fee_rate'],
                'spx_per_usdt' => $quote['spx_amount'],
                'updated_at' => $quote['updated_at'],
            ], '实时兑换价');

        case 'wallet.exchange':
            $user = currentUser();
            requireFields($body, ['usdt_amount', 'request_id']);
            $usdtAmount = trim((string) $body['usdt_amount']);
            $requestId = trim((string) $body['request_id']);
            if (!function_exists('bccomp')) jsonResponse(null, '服务器未安装 BCMath 扩展', 503);
            if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $usdtAmount) || bccomp($usdtAmount, '0', 18) <= 0) jsonResponse(null, 'USDT 金额格式无效', 422);
            if (!preg_match('/^[A-Za-z0-9._:-]{16,64}$/', $requestId)) jsonResponse(null, '兑换请求编号无效', 422);
            $pdo = db();
            $existing = $pdo->prepare('SELECT * FROM exchange_records WHERE request_id=? LIMIT 1');
            $existing->execute([$requestId]);
            $previous = $existing->fetch();
            if ($previous) {
                if ((int) $previous['user_id'] !== (int) $user['id']) jsonResponse(null, '兑换请求编号已使用', 409);
                if (bccomp((string) $previous['usdt_amount'], $usdtAmount, 18) !== 0) jsonResponse(null, '兑换请求编号与金额不匹配', 409);
                jsonResponse([
                    'request_id' => $requestId,
                    'usdt_amount' => (string) $previous['usdt_amount'],
                    'spx_amount' => (string) $previous['spx_amount'],
                    'api_price' => (string) $previous['api_price'],
                    'exchange_price' => (string) $previous['exchange_price'],
                    'fee_rate' => (string) $previous['fee_rate'],
                    'replayed' => true,
                ], '该兑换请求已处理');
            }
            $aveConfig = require __DIR__ . '/config.ave.php';
            $service = new AvePriceService($aveConfig);
            $snapshot = $service->getSnapshot(true);
            $exchange = $service->calcSpxAmount($usdtAmount, $snapshot);
            $pdo->beginTransaction();
            $pdo->prepare('INSERT IGNORE INTO wallet_assets (user_id,symbol,chain,balance) VALUES (?,"USDT","BSC",0),(?,"SPX001","BSC",0),(?,"FISS","BSC",0)')->execute([$user['id'], $user['id'], $user['id']]);
            $usdtAsset = $pdo->prepare('SELECT id,balance FROM wallet_assets WHERE user_id=? AND symbol="USDT" AND chain="BSC" FOR UPDATE');
            $usdtAsset->execute([$user['id']]);
            $usdt = $usdtAsset->fetch();
            if (!$usdt || bccomp((string) $usdt['balance'], $usdtAmount, 18) < 0) {
                $pdo->rollBack();
                jsonResponse(null, 'USDT 余额不足', 409);
            }
            $spxAsset = $pdo->prepare('SELECT id FROM wallet_assets WHERE user_id=? AND symbol="SPX001" AND chain="BSC" FOR UPDATE');
            $spxAsset->execute([$user['id']]);
            $spx = $spxAsset->fetch();
            if (!$spx) {
                $pdo->rollBack();
                jsonResponse(null, 'SPX001 资产账户不可用', 409);
            }
            $pdo->prepare('UPDATE wallet_assets SET balance=balance-? WHERE id=?')->execute([$usdtAmount, $usdt['id']]);
            $pdo->prepare('UPDATE wallet_assets SET balance=balance+? WHERE id=?')->execute([$exchange['spx_amount'], $spx['id']]);
            $record = $pdo->prepare('INSERT INTO exchange_records (user_id,usdt_amount,spx_amount,api_price,exchange_price,fee_rate,request_id,token_id,status) VALUES (?,?,?,?,?,?,?,?,"success")');
            $record->execute([$user['id'], $usdtAmount, $exchange['spx_amount'], $exchange['api_price'], $exchange['exchange_price'], $exchange['fee_rate'], $requestId, $exchange['token_id']]);
            $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,status) VALUES (?,"USDT","BSC","exchange_out",?,"completed"),(?,"SPX001","BSC","exchange_in",?,"completed")')->execute([$user['id'], $usdtAmount, $user['id'], $exchange['spx_amount']]);
            $pdo->commit();
            jsonResponse([
                'request_id' => $requestId,
                'usdt_amount' => $usdtAmount,
                'spx_amount' => $exchange['spx_amount'],
                'api_price' => $exchange['api_price'],
                'exchange_price' => $exchange['exchange_price'],
                'fee_rate' => $exchange['fee_rate'],
                'replayed' => false,
            ], '闪兑成功');

        case 'financial.request':
            if ($method !== 'GET') jsonResponse(null,'请求方式无效',405);
            $user=currentUser();
            jsonResponse(financialRequestResult(db(),(int)$user['id'],(string)($_GET['operation']??''),(string)($_GET['client_request_id']??'')));

        case 'financial.resolve':
            if ($method !== 'POST') jsonResponse(null,'请求方式无效',405);
            $user=currentUser();
            jsonResponse(financialRequestResolve(db(),(int)$user['id'],(string)($body['operation']??''),$body));

        case 'wallet.transactions':
            $user = currentUser();
            $paged=isset($_GET['page']) || isset($_GET['page_size']);
            [$size,$offset]=$paged?mallPage():[100,0];
            $filter=strtoupper(trim((string)($_GET['symbol']??'ALL')));
            if(!in_array($filter,['ALL','SPX001','USDT','FISS'],true)) jsonResponse(null,'资产筛选无效',422);
            $sql='SELECT * FROM wallet_transactions WHERE user_id=?';$params=[$user['id']];
            if($filter!=='ALL'){$sql.=' AND symbol=?';$params[]=$filter;}
            $statement=db()->prepare($sql." ORDER BY id DESC LIMIT $size OFFSET $offset");
            $statement->execute($params);
            jsonResponse($statement->fetchAll());

        case 'wallet.deposit.info':
            $config = require __DIR__ . '/config.php';
            $user = currentUser();
            $binding = db()->prepare('SELECT bsc_wallet_address FROM users WHERE id=? LIMIT 1');
            $binding->execute([$user['id']]);
            $boundAddress = $binding->fetchColumn();
            if (!is_string($boundAddress) || $boundAddress === '') jsonResponse(null, '请先绑定 BSC 钱包地址', 403);
            if (isTokenContractAddress($boundAddress)) jsonResponse(null, '已绑定地址为代币合约地址，请联系平台处理', 403);
            $setting = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
            $setting->execute(['bsc_usdt_deposit_address']);
            $depositAddress = trim((string) ($setting->fetchColumn() ?: (getenv('FISS_BSC_USDT_DEPOSIT_ADDRESS') ?: ($config['wallet']['bsc_usdt_deposit_address'] ?? ''))));
            if (!preg_match('/^0x[0-9a-fA-F]{40}$/', $depositAddress)) {
                jsonResponse(null, '链上充值服务尚未配置，请联系平台管理员', 503);
            }
            $fissSetting = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
            $fissSetting->execute(['fiss_deposit_address']);
            $fissAddress = trim((string) ($fissSetting->fetchColumn() ?: '') ?: (getenv('FISS_DEPOSIT_ADDRESS') ?: ''));
            if (!preg_match('/^0x[0-9a-fA-F]{40}$/', $fissAddress)) $fissAddress = $depositAddress;
            jsonResponse([
                'chain' => 'BSC',
                'usdt_address' => $depositAddress,
                'spx001_address' => $depositAddress,
                'fiss_address' => $fissAddress,
                'spx001_contract' => '0x0E920821464625cf661A4dceDEb6406E1B48FFFf',
                'symbol' => 'USDT',
                'bound_address' => $boundAddress,
                'verification' => 'automatic_after_confirmations',
                'confirmations' => max(1, (int) (getenv('FISS_BSC_CONFIRMATIONS') ?: 12)),
            ]);

        case 'wallet.deposit':
        case 'wallet.deposit.submit':
        case 'wallet.withdraw':
            $user = currentUser();
            requireFields($body, ['symbol', 'amount']);
            $amount = trim((string) $body['amount']);
            if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $amount) || !preg_match('/[1-9]/', $amount)) jsonResponse(null, '金额格式无效或必须大于 0', 422);
            if ($action === 'wallet.withdraw' && !function_exists('bccomp')) jsonResponse(null, '服务器未安装 BCMath 扩展', 503);
            $symbol = strtoupper(trim((string) $body['symbol']));
            $chain = strtoupper(trim((string) ($body['chain'] ?? 'BSC')));
            if (!in_array($symbol, ['SPX001', 'USDT', 'FISS'], true) || $chain !== 'BSC') jsonResponse(null, '仅支持 BSC 链上的 SPX001、FISS 或 USDT', 422);
            // 这个开关只管「提现」，不能拦住充值——充值与提现共用同一段代码。
            if ($symbol === 'FISS' && $action === 'wallet.withdraw') {
                $fissWithdrawToggle = db()->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
                $fissWithdrawToggle->execute(['withdraw_fiss_enabled']);
                if ((string) ($fissWithdrawToggle->fetchColumn() ?: '0') !== '1') jsonResponse(null, 'FISS 提现暂未开放', 403);
            }
            $type = $action === 'wallet.withdraw' ? 'withdraw' : 'deposit';
            // 提现参数：手续费率与最低额都来自后台设置（按币种，JSON）
            $withdrawFeeRates = json_decode((string) (db()->query("SELECT `value` FROM app_settings WHERE `key`='withdraw_fee_rates'")->fetchColumn() ?: ''), true);
            $withdrawMinAmounts = json_decode((string) (db()->query("SELECT `value` FROM app_settings WHERE `key`='withdraw_min_amounts'")->fetchColumn() ?: ''), true);
            $withdrawFeeRates = is_array($withdrawFeeRates) ? $withdrawFeeRates : [];
            $withdrawMinAmounts = is_array($withdrawMinAmounts) ? $withdrawMinAmounts : [];
            $feeRatePercent = (string) ($withdrawFeeRates[$symbol] ?? '1');
            if (!preg_match('/^(?:0|[1-9][0-9]?)(?:\.[0-9]{1,4})?$/', $feeRatePercent) || bccomp($feeRatePercent, '50', 6) > 0) $feeRatePercent = '1';
            $config = $type === 'deposit' ? require __DIR__ . '/config.php' : null;
            if ($type === 'withdraw') requireFields($body, ['address']);
            if ($type === 'deposit') requireFields($body, ['tx_hash']);
            $pdo = db();
            $bound = $pdo->prepare('SELECT bsc_wallet_address FROM users WHERE id=? LIMIT 1');
            $bound->execute([$user['id']]);
            $boundAddress = $bound->fetchColumn();
            if (!is_string($boundAddress) || $boundAddress === '') jsonResponse(null, '请先绑定 BSC 钱包地址', 403);
            if (isTokenContractAddress($boundAddress)) jsonResponse(null, '已绑定地址为代币合约地址，请联系平台处理', 403);
            if ($type === 'withdraw') {
                $requestedAddress = trim((string) $body['address']);
                if (!preg_match('/^0x[0-9a-fA-F]{40}$/', $requestedAddress) || strtolower($requestedAddress) !== strtolower($boundAddress)) jsonResponse(null, '提现地址必须与已绑定钱包地址完全一致', 422);
                if (isTokenContractAddress($requestedAddress)) jsonResponse(null, '不能向 USDT、SPX001 或 FISS 合约地址提现', 422);
                $requestId=financialRequestID($body);
                $pdo->beginTransaction();
                $replay=financialRequestBegin($pdo,(int)$user['id'],$action,$requestId,financialPayload($action,$body));
                if($replay!==null){$pdo->commit();jsonResponse($replay,'申请已提交',200);}
                $asset = $pdo->prepare('SELECT id,balance,stakeable_balance FROM wallet_assets WHERE user_id=? AND symbol=? AND chain=? FOR UPDATE');
                $asset->execute([$user['id'], $symbol, $chain]);
                $wallet = $asset->fetch();
                if (!$wallet || bccomp((string) $wallet['balance'], $amount, 18) < 0) { $pdo->rollBack(); jsonResponse(null, '可用余额不足', 409); }
                $activeWithdrawal = $pdo->prepare('SELECT id FROM wallet_transactions WHERE user_id=? AND symbol=? AND chain=? AND type="withdraw" AND status IN ("pending","processing","sending") LIMIT 1 FOR UPDATE');
                $activeWithdrawal->execute([$user['id'], $symbol, $chain]);
                if ($activeWithdrawal->fetch()) {
                    $pdo->rollBack();
                    jsonResponse(null, '该资产已有一笔提现正在处理中，请等待完成后再提交', 409);
                }
                // 最低提现额：避免小额提现白耗热钱包 gas
                $minDefaults = ['USDT' => '10', 'SPX001' => '1000', 'FISS' => '10000'];
                $minAmount = (string) ($withdrawMinAmounts[$symbol] ?? ($minDefaults[$symbol] ?? '0'));
                if (bccomp($minAmount, '0', 18) > 0 && bccomp($amount, $minAmount, 18) < 0) {
                    $pdo->rollBack();
                    jsonResponse(null, '最小提现数量为 ' . $minAmount . ' ' . $symbol, 422);
                }
                $fee = bcdiv(bcmul($amount, $feeRatePercent, 18), '100', 18);
                $netAmount = bcsub($amount, $fee, 18);
                if (bccomp($netAmount, '0', 18) <= 0) { $pdo->rollBack(); jsonResponse(null, '提现金额必须大于手续费', 422); }
                $stakeableAmount = walletDebit($pdo, (int) $user['id'], $symbol, $chain, $amount, false);
            } else {
                
                $txHash = strtolower(trim((string) $body['tx_hash']));
                if (!preg_match('/^0x[a-f0-9]{64}$/', $txHash)) jsonResponse(null, 'BSC 交易哈希格式无效', 422);
                $setting = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
                $setting->execute(['bsc_usdt_deposit_address']);
                $depositAddress = trim((string) ($setting->fetchColumn() ?: (getenv('FISS_BSC_USDT_DEPOSIT_ADDRESS') ?: ($config['wallet']['bsc_usdt_deposit_address'] ?? ''))));
                if ($symbol === 'FISS') {
                    // FISS 走独立系统钱包（该钱包会按档位比例自动销毁）
                    $fissDepositQuery = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
                    $fissDepositQuery->execute(['fiss_deposit_address']);
                    $fissDeposit = trim((string) ($fissDepositQuery->fetchColumn() ?: ''));
                    if (preg_match('/^0x[0-9a-fA-F]{40}$/', $fissDeposit)) $depositAddress = $fissDeposit;
                }
                if (!preg_match('/^0x[0-9a-fA-F]{40}$/', $depositAddress)) {
                    jsonResponse(null, '链上充值服务尚未配置，请联系平台管理员', 503);
                }
                $pdo->beginTransaction();
                try {
                    $pdo->prepare('INSERT INTO recharge_records (user_id,tx_hash,amount,currency,wallet_address,deposit_address,status) VALUES (?,?,?,?,?,?,"pending")')->execute([$user['id'], $txHash, $amount, $symbol, strtolower($boundAddress), strtolower($depositAddress)]);
                    $statement = $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,address,tx_hash,status) VALUES (?, ?,"BSC","deposit",?,?,?,"pending")');
                    $statement->execute([$user['id'], $symbol, $amount, $boundAddress, $txHash]);
                     $transactionId = (int) $pdo->lastInsertId();
                     $pdo->commit();
                } catch (PDOException $exception) {
                    if ($pdo->inTransaction()) $pdo->rollBack();
                    if ((string) ($exception->errorInfo[0] ?? '') === '23000') jsonResponse(null, '该交易哈希已提交', 409);
                     throw $exception;
                 }
                 jsonResponse(['transaction_id' => $transactionId, 'status' => 'pending', 'verification' => 'automatic_after_confirmations'], '交易已提交，达到 BSC 确认数后自动到账', 201);
            }
            $txHash = strtolower(trim((string) ($body['tx_hash'] ?? '')));
            if ($txHash !== '') {
                $duplicate = $pdo->prepare('SELECT id FROM wallet_transactions WHERE tx_hash=? LIMIT 1');
                $duplicate->execute([$txHash]);
                if ($duplicate->fetch()) {
                    if ($type === 'withdraw') $pdo->rollBack();
                    jsonResponse(null, '该交易哈希已提交', 409);
                }
            }
            $fee = $type === 'withdraw' ? bcdiv(bcmul($amount, $feeRatePercent, 18), '100', 18) : '0';
            $netAmount = $type === 'withdraw' ? bcsub($amount, $fee, 18) : $amount;
            $statement = $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,fee_amount,stakeable_amount,address,tx_hash,status) VALUES (?,?,?,?,?,?,?,?,?,"pending")');
             $statement->execute([$user['id'], $symbol, $chain, $type, $netAmount, $fee, $type === 'withdraw' ? $stakeableAmount : '0', $boundAddress, $txHash ?: null]);
             $transactionId = (int) $pdo->lastInsertId();
             $autoApproved = false;
             $autoReason = '';
             if ($type === 'withdraw') {
                 $policy = withdrawalTryAutoApprove($pdo, $transactionId, (int) $user['id'], $symbol, $amount, $netAmount);
                 $autoApproved = $policy['approved'];
                 $autoReason = $policy['reason'];
             }
              if ($type === 'withdraw') {
                  $withdrawResult=financialRequestFinish($pdo,(int)$user['id'],$action,$requestId,['transaction_id'=>$transactionId,'status'=>$autoApproved?'processing':'pending','auto_approved'=>$autoApproved,'amount'=>$amount,'fee'=>$fee,'net_amount'=>$netAmount,'verification'=>$autoApproved?'automatic_after_policy':'manual_withdraw_review']);
                  $pdo->commit();
                  // The withdrawal is already committed; a mail failure must not turn it into an
                  // API error, because a retry would look like a second request to the user.
                  try {
                      sendFinanceTemplate($pdo, 'withdraw_submitted', [
                          'username' => (string) ($user['username'] ?? $user['id']),
                          'nickname' => (string) ($user['nickname'] ?? ''),
                          'user_id' => (int) $user['id'],
                          'symbol' => $symbol . ' / ' . $chain,
                          'amount' => $amount,
                          'fee' => $fee,
                          'net_amount' => $netAmount,
                          'address' => $boundAddress,
                          'status' => $autoApproved ? '已自动通过，正在打币' : '待审核',
                          'created_at' => date('Y-m-d H:i:s'),
                      ]);
                  } catch (Throwable $notifyError) {
                      error_log('withdraw notify failed #' . $transactionId . ': ' . $notifyError->getMessage());
                  }
              }
             jsonResponse($withdrawResult, $type === 'deposit' ? '交易已提交，达到 BSC 确认数后自动到账' : ($autoApproved ? '申请已自动通过，正在打币；手续费 1%，实际到账 ' . $netAmount : '申请已提交，手续费 1%，实际到账 ' . $netAmount), 201);

        case 'wallet.withdraw.cancel':
            $user = currentUser();
            requireFields($body, ['transaction_id']);
            $pdo = db();
            $pdo->beginTransaction();
            $transaction = $pdo->prepare('SELECT * FROM wallet_transactions WHERE id=? AND user_id=? AND type="withdraw" AND status="pending" FOR UPDATE');
            $transaction->execute([(int) $body['transaction_id'], $user['id']]);
            $row = $transaction->fetch();
            if (!$row) { $pdo->rollBack(); jsonResponse(null, '提现申请不存在或已处理', 409); }
            $pdo->prepare('UPDATE wallet_transactions SET status="cancelled" WHERE id=?')->execute([$row['id']]);
             $refundQuery = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))+CAST(? AS DECIMAL(36,18))');
             $refundQuery->execute([$row['amount'], $row['fee_amount'] ?? '0']);
             $refundAmount = (string) $refundQuery->fetchColumn();
             walletCreditSplit($pdo, (int) $user['id'], (string) $row['symbol'], (string) $row['chain'], $refundAmount, (string) ($row['stakeable_amount'] ?? '0'));
            $pdo->commit();
            jsonResponse(null, '提现申请已撤销，余额已退回');

        case 'wallet.address':
            $user = currentUser();
            $statement = db()->prepare('SELECT * FROM user_addresses WHERE user_id = ? ORDER BY is_default DESC, id DESC');
            $statement->execute([$user['id']]);
            jsonResponse($statement->fetchAll());

        case 'wallet.address.save':
            $user = currentUser();
            requireFields($body, ['name', 'phone', 'province', 'city', 'district', 'detail']);
            db()->prepare('UPDATE user_addresses SET is_default=0 WHERE user_id=?')->execute([$user['id']]);
            $statement = db()->prepare(
                'INSERT INTO user_addresses (user_id, name, phone, province, city, district, detail, is_default)
                 VALUES (?, ?, ?, ?, ?, ?, ?, 1)'
            );
            $statement->execute([$user['id'], $body['name'], $body['phone'], $body['province'], $body['city'], $body['district'], $body['detail']]);
            jsonResponse(['id' => (int) db()->lastInsertId()], '地址已保存', 201);

        case 'wallet.address.update':
            $user = currentUser();
            requireFields($body, ['id', 'name', 'phone', 'province', 'city', 'district', 'detail']);
            $addressId = (int) $body['id'];
            $existing = db()->prepare('SELECT is_default FROM user_addresses WHERE id=? AND user_id=? LIMIT 1');
            $existing->execute([$addressId, $user['id']]);
            $oldAddress = $existing->fetch();
            if (!$oldAddress) jsonResponse(null, '地址不存在', 404);
            $isDefault = array_key_exists('is_default', $body) ? (int) ((bool) $body['is_default']) : (int) $oldAddress['is_default'];
            if ($isDefault === 1) db()->prepare('UPDATE user_addresses SET is_default=0 WHERE user_id=?')->execute([$user['id']]);
            $statement = db()->prepare('UPDATE user_addresses SET name=?,phone=?,province=?,city=?,district=?,detail=?,is_default=? WHERE id=? AND user_id=?');
            $statement->execute([$body['name'], $body['phone'], $body['province'], $body['city'], $body['district'], $body['detail'], $isDefault, $addressId, $user['id']]);
            jsonResponse(null, '地址已更新');

        case 'wallet.address.delete':
            $user = currentUser();
            requireFields($body, ['id']);
            db()->prepare('DELETE FROM user_addresses WHERE id = ? AND user_id = ?')->execute([(int) $body['id'], $user['id']]);
            jsonResponse(null, '地址已删除');

        case 'wallet.address.default':
            $user = currentUser();
            requireFields($body, ['id']);
            $pdo = db();
            $pdo->beginTransaction();
            $pdo->prepare('UPDATE user_addresses SET is_default=0 WHERE user_id=?')->execute([$user['id']]);
            $statement = $pdo->prepare('UPDATE user_addresses SET is_default=1 WHERE id=? AND user_id=?');
            $statement->execute([(int) $body['id'], $user['id']]);
            if ($statement->rowCount() === 0) { $pdo->rollBack(); jsonResponse(null, '地址不存在', 404); }
            $pdo->commit();
            jsonResponse(null, '默认地址已更新');

        case 'faq':
            $statement = db()->query('SELECT id,category,question,answer FROM faq_items WHERE status=1 ORDER BY sort_order,id');
            jsonResponse($statement->fetchAll());

        case 'questions':
            $user = currentUser();
            $statement = db()->prepare('SELECT * FROM user_questions WHERE user_id=? ORDER BY id DESC');
            $statement->execute([$user['id']]);
            jsonResponse($statement->fetchAll());

        case 'questions.create':
            $user = currentUser();
            requireFields($body, ['question']);
            $statement = db()->prepare('INSERT INTO user_questions (user_id,question) VALUES (?,?)');
            $statement->execute([$user['id'], trim((string) $body['question'])]);
            jsonResponse(['id' => (int) db()->lastInsertId(), 'status' => 'pending'], '问题已提交', 201);

        case 'feedback.create':
            $user = currentUser();
            requireFields($body, ['content']);
            $statement = db()->prepare('INSERT INTO feedback (user_id,content,contact) VALUES (?,?,?)');
            $statement->execute([$user['id'], trim((string) $body['content']), $body['contact'] ?? null]);
            jsonResponse(['id' => (int) db()->lastInsertId()], '反馈已提交', 201);

        case 'support.tickets':
            $user = currentUser();
            $statement = db()->prepare('SELECT id,subject,content,status,admin_reply,created_at,updated_at FROM support_tickets WHERE user_id=? ORDER BY id DESC');
            $statement->execute([$user['id']]);
            jsonResponse($statement->fetchAll());

        case 'support.tickets.create':
            $user = currentUser();
            requireFields($body, ['subject', 'content']);
            $subject = trim((string) $body['subject']);
            $content = trim((string) $body['content']);
            $subjectLength = function_exists('mb_strlen') ? mb_strlen($subject) : strlen($subject);
            $contentLength = function_exists('mb_strlen') ? mb_strlen($content) : strlen($content);
            if ($subjectLength > 150 || $contentLength > 10000) jsonResponse(null, '标题或内容超过长度限制', 422);
            $statement = db()->prepare('INSERT INTO support_tickets (user_id,subject,content) VALUES (?,?,?)');
            $statement->execute([$user['id'], $subject, $content]);
            jsonResponse(['id' => (int) db()->lastInsertId(), 'status' => 'open'], '工单已提交', 201);

        case 'referrals':
            $user = currentUser();
            $members = db()->prepare('SELECT u.id,u.nickname,u.spx_id,u.created_at, COALESCE((SELECT SUM(s.amount) FROM staking_records s WHERE s.user_id=u.id AND s.symbol="SPX001"),0) AS stake_total, COALESCE((SELECT SUM(s.amount) FROM staking_records s WHERE s.user_id=u.id AND s.symbol="FISS"),0) AS stake_total_fiss, COALESCE((SELECT SUM(r.reward_amount) FROM referral_rewards r WHERE r.staker_user_id=u.id AND r.referrer_user_id=?),0) AS reward_total FROM users u WHERE u.invited_by_user_id=? ORDER BY u.id DESC');
            $members->execute([$user['id'], $user['id']]);
            $memberRows = $members->fetchAll();
            $totals = db()->prepare('SELECT COALESCE(SUM(reward_amount),0) AS total_rewards FROM referral_rewards WHERE referrer_user_id=?');
            $totals->execute([$user['id']]);
            $rewardSummary = $totals->fetch();
            $balance = db()->prepare('SELECT balance FROM wallet_assets WHERE user_id=? AND symbol="SPX001" AND chain="BSC" LIMIT 1');
            $balance->execute([$user['id']]);
            $withdrawableSpx = $balance->fetchColumn() ?: '0';
            $fissBalance = db()->prepare('SELECT balance FROM wallet_assets WHERE user_id=? AND symbol="FISS" AND chain="BSC" LIMIT 1');
            $fissBalance->execute([$user['id']]);
            $inviteCode = (string) $user['spx_id'];
            jsonResponse(['invite_code' => $inviteCode, 'invite_url' => inviteBaseUrl() . '/login.php?invite=' . rawurlencode($inviteCode), 'commission_rate' => '5%', 'invited_count' => count($memberRows), 'total_rewards' => $rewardSummary['total_rewards'] ?? '0', 'withdrawable_spx' => $withdrawableSpx, 'withdrawable_fiss' => $fissBalance->fetchColumn() ?: '0', 'users' => $memberRows]);

        case 'staking.plans':
            currentUser();
            $planSymbol = strtoupper(trim((string) ($body['symbol'] ?? $_GET['symbol'] ?? 'SPX001')));
            if (!in_array($planSymbol, ['SPX001', 'FISS'], true)) jsonResponse(null, '质押资产无效', 422);
            $plans = cacheRemember('staking:plans:' . $planSymbol, 60, static function () use ($planSymbol): array {
                $plansQuery = db()->prepare('SELECT id,name,symbol,plan_days,apy,minimum_amount,step_amount,max_per_user FROM staking_plans WHERE status=1 AND symbol=? ORDER BY sort_order,plan_days');
                $plansQuery->execute([$planSymbol]);
                return $plansQuery->fetchAll();
            });
            $phaseQuery = db()->prepare('SELECT symbol,phase_no,name,total_quota,reserved_quota,paid_quota,status FROM staking_phases WHERE status=1 AND symbol=? ORDER BY phase_no DESC LIMIT 1');
            $phaseQuery->execute([$planSymbol]);
            $phase = $phaseQuery->fetch() ?: null;
            foreach ($plans as &$plan) {
                $plan['id'] = (int) $plan['id'];
                $plan['plan_days'] = (int) $plan['plan_days'];
                $plan['apy'] = (string) $plan['apy'];
                $plan['minimum_amount'] = (string) $plan['minimum_amount'];
                $plan['step_amount'] = (string) $plan['step_amount'];
                $plan['max_per_user'] = $plan['max_per_user'] === null ? null : (string) $plan['max_per_user'];
            }
            unset($plan);
            if ($phase) {
                $phase['phase_no'] = (int) $phase['phase_no'];
                $phase['status'] = (int) $phase['status'];
                $phase['total_quota'] = (string) $phase['total_quota'];
                $phase['reserved_quota'] = (string) $phase['reserved_quota'];
                $phase['paid_quota'] = (string) $phase['paid_quota'];
                $remainingQuery = db()->prepare('SELECT CAST(? AS DECIMAL(36,18))-CAST(? AS DECIMAL(36,18))');
                $remainingQuery->execute([$phase['total_quota'], $phase['reserved_quota']]);
                $phase['remaining_quota'] = (string) $remainingQuery->fetchColumn();
            }
            // 销毁阶梯：FISS 的起押门槛随「转到黑洞地址的累计销毁量」自动下调。
            // 覆盖 minimum_amount 后，老版本 App 也能直接显示当前正确的门槛。
            [$effectiveMin, $tierInfo] = burnEffectiveMinimum(db(), $planSymbol, (string) ($plans[0]['minimum_amount'] ?? '0'));
            foreach ($plans as &$tierPlan) { $tierPlan['minimum_amount'] = $effectiveMin; }
            unset($tierPlan);
            $burnInfo = [
                'amount' => (string) $tierInfo['burn'],
                'mode' => (string) $tierInfo['mode'],
                'tier_threshold' => $tierInfo['tier'] ? (string) $tierInfo['tier']['threshold_amount'] : null,
                'tier_min' => $tierInfo['tier'] ? (string) $tierInfo['tier']['min_stake_amount'] : null,
                'next_threshold' => $tierInfo['next'] ? (string) $tierInfo['next']['threshold_amount'] : null,
                'next_min' => $tierInfo['next'] ? (string) $tierInfo['next']['min_stake_amount'] : null,
                'remaining_to_next' => $tierInfo['remaining'],
                'tiers' => array_map(static fn(array $t): array => [
                    'threshold' => (string) $t['threshold_amount'],
                    'min_stake' => (string) $t['min_stake_amount'],
                ], $tierInfo['tiers']),
                'stale' => (bool) $tierInfo['stale'],
                'updated_at' => (int) $tierInfo['cached_at'],
            ];
            jsonResponse(['symbol' => $planSymbol, 'plans' => $plans, 'phase' => $phase, 'burn' => $burnInfo]);

        case 'staking':
            $user = currentUser();
            $listSymbol = strtoupper(trim((string) ($body['symbol'] ?? $_GET['symbol'] ?? '')));
            if ($listSymbol !== '' && !in_array($listSymbol, ['SPX001', 'FISS'], true)) jsonResponse(null, '质押资产无效', 422);
            $statement = db()->prepare('SELECT s.*,DATE_ADD(s.created_at,INTERVAL s.plan_days DAY) AS matures_at,IF(s.status="active" AND DATE_ADD(s.created_at,INTERVAL s.plan_days DAY)<=NOW(),1,0) AS can_redeem,CAST(ROUND(s.amount*s.apy*s.plan_days/36500,18) AS DECIMAL(36,18)) AS expected_reward,COALESCE((SELECT SUM(d.amount) FROM staking_dividends d WHERE d.staking_id=s.id),0) AS paid_dividend FROM staking_records s WHERE s.user_id=?' . ($listSymbol !== '' ? ' AND s.symbol=?' : '') . ' ORDER BY s.id DESC');
            $statement->execute($listSymbol !== '' ? [$user['id'], $listSymbol] : [$user['id']]);
            jsonResponse($statement->fetchAll());

        case 'staking.earnings':
            // 收益明细：按质押记录汇总，逐条列出到期应付与实际已发
            $user = currentUser();
            $earningSymbol = strtoupper(trim((string) ($body['symbol'] ?? $_GET['symbol'] ?? '')));
            if ($earningSymbol !== '' && !in_array($earningSymbol, ['SPX001', 'FISS'], true)) jsonResponse(null, '资产无效', 422);
            $earningQuery = db()->prepare('SELECT s.id,s.symbol,s.plan_days,s.amount,s.apy,s.status,s.created_at,DATE_ADD(s.created_at,INTERVAL s.plan_days DAY) AS matures_at,CAST(ROUND(s.amount*s.apy*s.plan_days/36500,18) AS DECIMAL(36,18)) AS expected_reward,COALESCE((SELECT SUM(d.amount) FROM staking_dividends d WHERE d.staking_id=s.id),0) AS paid_dividend FROM staking_records s WHERE s.user_id=?' . ($earningSymbol !== '' ? ' AND s.symbol=?' : '') . ' ORDER BY s.id DESC');
            $earningQuery->execute($earningSymbol !== '' ? [$user['id'], $earningSymbol] : [$user['id']]);
            $earningRows = $earningQuery->fetchAll();
            $totals = [];
            foreach ($earningRows as $row) {
                $symbol = (string) $row['symbol'];
                if (!isset($totals[$symbol])) {
                    $totals[$symbol] = ['expected' => '0', 'paid' => '0', 'active_amount' => '0'];
                }
                $totals[$symbol]['expected'] = bcadd($totals[$symbol]['expected'], (string) $row['expected_reward'], 18);
                $totals[$symbol]['paid'] = bcadd($totals[$symbol]['paid'], (string) $row['paid_dividend'], 18);
                if ((string) $row['status'] === 'active') {
                    $totals[$symbol]['active_amount'] = bcadd($totals[$symbol]['active_amount'], (string) $row['amount'], 18);
                }
            }
            jsonResponse(['records' => $earningRows, 'totals' => $totals]);

        case 'wallet.withdraw.config':
        // 提现费率与最低额（客户端展示预计到账用）
        $user = currentUser();
        $pdo = db();
        $readConfig = static function (string $key) use ($pdo): array {
            $stmt = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
            $stmt->execute([$key]);
            $decoded = json_decode((string) ($stmt->fetchColumn() ?: ''), true);
            return is_array($decoded) ? $decoded : [];
        };
        $feeRatesCfg = $readConfig('withdraw_fee_rates');
        $minAmountsCfg = $readConfig('withdraw_min_amounts');
        $fissEnabled = (string) ($pdo->query("SELECT `value` FROM app_settings WHERE `key`='withdraw_fiss_enabled'")->fetchColumn() ?: '0') === '1';
        $symbolConfig = [];
        foreach (['USDT' => ['1', '10'], 'SPX001' => ['1', '1000'], 'FISS' => ['1', '10000']] as $symbolKey => $defaults) {
            $symbolConfig[$symbolKey] = [
                'fee_rate' => (string) ($feeRatesCfg[$symbolKey] ?? $defaults[0]),
                'min_amount' => (string) ($minAmountsCfg[$symbolKey] ?? $defaults[1]),
            ];
        }
        jsonResponse(['symbols' => $symbolConfig, 'fiss_enabled' => $fissEnabled]);

    case 'staking.detail':
        // 单笔质押详情：本金、进度、每期分红明细、下一期发放时间
        $user = currentUser();
        $detailId = (int) ($body['id'] ?? $_GET['id'] ?? 0);
        if ($detailId <= 0) jsonResponse(null, '缺少质押记录编号', 422);
        $pdo = db();
        $detailQuery = $pdo->prepare(
            'SELECT s.*,DATE_ADD(s.created_at,INTERVAL s.plan_days DAY) AS matures_at,
                IF(s.status="active" AND DATE_ADD(s.created_at,INTERVAL s.plan_days DAY)<=NOW(),1,0) AS can_redeem,
                CAST(ROUND(s.amount*s.apy*s.plan_days/36500,18) AS DECIMAL(36,18)) AS expected_reward,
                COALESCE((SELECT SUM(d.amount) FROM staking_dividends d WHERE d.staking_id=s.id),0) AS paid_dividend,
                COALESCE((SELECT COUNT(*) FROM staking_dividends d WHERE d.staking_id=s.id),0) AS periods_paid,
                (SELECT MAX(d.created_at) FROM staking_dividends d WHERE d.staking_id=s.id) AS last_paid_at
             FROM staking_records s WHERE s.id=? AND s.user_id=? LIMIT 1');
        $detailQuery->execute([$detailId, $user['id']]);
        $record = $detailQuery->fetch();
        if (!$record) jsonResponse(null, '质押记录不存在', 404);

        $symbol = strtoupper((string) $record['symbol']);
        $planDays = max(1, (int) $record['plan_days']);
        $periodsTotal = (int) ceil($planDays / 10);
        $periodsPaid = (int) $record['periods_paid'];
        $nextPayoutAt = null;
        if ((string) $record['status'] === 'active' && $periodsPaid < $periodsTotal) {
            $offsetDays = ($periodsPaid + 1) * 10;
            $startTs = strtotime((string) $record['created_at']);
            $nextPayoutAt = $startTs ? date('Y-m-d H:i', $startTs + $offsetDays * 86400) : null;
        }
        $expectedReward = (string) $record['expected_reward'];
        $paidDividend = (string) $record['paid_dividend'];
        $remainingReward = bccomp($expectedReward, $paidDividend, 18) > 0 ? bcsub($expectedReward, $paidDividend, 18) : '0';

        $dividends = $pdo->prepare(
            'SELECT period_no,amount,created_at AS paid_at FROM staking_dividends WHERE staking_id=? ORDER BY period_no ASC LIMIT 60');
        $dividends->execute([$detailId]);

        $phase = null;
        if (!empty($record['phase_id'])) {
            $phaseQuery = $pdo->prepare('SELECT id,phase_no,name,total_quota,reserved_quota,paid_quota FROM staking_phases WHERE id=? LIMIT 1');
            $phaseQuery->execute([(int) $record['phase_id']]);
            $phase = $phaseQuery->fetch() ?: null;
        }

        jsonResponse([
            'id' => (int) $record['id'],
            'symbol' => $symbol,
            'plan_days' => $planDays,
            'apy' => (string) $record['apy'],
            'amount' => (string) $record['amount'],
            'status' => (string) $record['status'],
            'created_at' => (string) $record['created_at'],
            'matures_at' => (string) $record['matures_at'],
            'can_redeem' => (int) $record['can_redeem'] === 1,
            'expected_reward' => $expectedReward,
            'paid_dividend' => $paidDividend,
            'remaining_reward' => $remainingReward,
            'periods_total' => $periodsTotal,
            'periods_paid' => $periodsPaid,
            'next_payout_at' => $nextPayoutAt,
            'last_paid_at' => $record['last_paid_at'] ? (string) $record['last_paid_at'] : null,
            'dividends' => $dividends->fetchAll(),
            'phase' => $phase,
        ]);

    case 'staking.create':
            $user = currentUser();
            requireFields($body, ['plan_id', 'amount']);
            $amount = trim((string) $body['amount']);
            if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $amount) || bccomp($amount, '0', 18) <= 0) {
                jsonResponse(null, '质押数量格式无效', 422);
            }
            $pdo = db();
            $requestId=financialRequestID($body);
            $pdo->beginTransaction();
            $replay=financialRequestBegin($pdo,(int)$user['id'],$action,$requestId,financialPayload($action,$body));
            if($replay!==null){$pdo->commit();jsonResponse($replay,'质押记录已创建');}
            $planQuery = $pdo->prepare('SELECT id,name,symbol,plan_days,apy,minimum_amount,step_amount,max_per_user FROM staking_plans WHERE id=? AND status=1');
            $planQuery->execute([(int) $body['plan_id']]);
            $plan = $planQuery->fetch();
            if (!$plan) { $pdo->rollBack(); jsonResponse(null, '质押方案已下架，请刷新后重试', 409); }
            $stakeSymbol = strtoupper((string) $plan['symbol']);
            if (!in_array($stakeSymbol, ['SPX001', 'FISS'], true)) { $pdo->rollBack(); jsonResponse(null, '质押资产无效', 422); }

            $phaseQuery = $pdo->prepare('SELECT id,symbol,phase_no,name,total_quota,reserved_quota,paid_quota FROM staking_phases WHERE status=1 AND symbol=? ORDER BY phase_no DESC LIMIT 1 FOR UPDATE');
            $phaseQuery->execute([$stakeSymbol]);
            $phase = $phaseQuery->fetch();
            if (!$phase) { $pdo->rollBack(); jsonResponse(null, '当前没有开启的 ' . $stakeSymbol . ' 质押期，请等待后台配置', 409); }

            // 起押门槛按当前销毁档位计算（没有配置档位时回退到方案自身的最低金额）
            [$effectiveMin, $tierInfo] = burnEffectiveMinimum($pdo, $stakeSymbol, (string) $plan['minimum_amount']);
            $minimumCheck = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))>=CAST(? AS DECIMAL(36,18))');
            $minimumCheck->execute([$amount, $effectiveMin]);
            if ((int) $minimumCheck->fetchColumn() !== 1) {
                $pdo->rollBack();
                $hint = '';
                if ($tierInfo['next'] !== null && $tierInfo['remaining'] !== null) {
                    $hint = '；累计销毁再增加 ' . $tierInfo['remaining'] . ' ' . $stakeSymbol
                        . ' 可降至 ' . (string) $tierInfo['next']['min_stake_amount'] . ' ' . $stakeSymbol;
                }
                jsonResponse(null, '当前销毁档位要求最低质押 ' . $effectiveMin . ' ' . $stakeSymbol . $hint, 422);
            }

            $stepAmount = (string) ($plan['step_amount'] ?? '0');
            if (bccomp($stepAmount, '0', 18) > 0) {
                $stepCheck = $pdo->prepare('SELECT MOD(CAST(? AS DECIMAL(36,18)), CAST(? AS DECIMAL(36,18)))');
                $stepCheck->execute([$amount, $stepAmount]);
                if (bccomp((string) $stepCheck->fetchColumn(), '0', 18) !== 0) {
                    $pdo->rollBack();
                    $stepLabel = $stepAmount;
                $stepValue = (float) $stepAmount;
                if ($stepValue >= 100000000) {
                    $stepLabel = rtrim(rtrim(number_format($stepValue / 100000000, 2, '.', ''), '0'), '.') . ' 亿';
                } elseif ($stepValue >= 10000) {
                    $stepLabel = rtrim(rtrim(number_format($stepValue / 10000, 2, '.', ''), '0'), '.') . ' 万';
                }
                jsonResponse(null, '质押数量必须是 ' . $stepLabel . ' 的整数倍（如 110 万、150 万、230 万）', 422);
                }
            }

            $activeStakeTotal = $pdo->prepare('SELECT COALESCE(SUM(amount),0) FROM staking_records WHERE user_id=? AND symbol=? AND status="active" FOR UPDATE');
            $activeStakeTotal->execute([$user['id'], $stakeSymbol]);
            $alreadyStaked = (string) $activeStakeTotal->fetchColumn();
            if ($plan['max_per_user'] !== null) {
                $maxCheck = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))+CAST(? AS DECIMAL(36,18))<=CAST(? AS DECIMAL(36,18))');
                $maxCheck->execute([$alreadyStaked, $amount, $plan['max_per_user']]);
                if ((int) $maxCheck->fetchColumn() !== 1) { $pdo->rollBack(); jsonResponse(null, '单人累计质押上限 ' . (string) $plan['max_per_user'] . ' ' . $stakeSymbol, 409); }
            }
            $planDays = (int) $plan['plan_days'];
            $expectedQuery = $pdo->prepare('SELECT CAST(ROUND(CAST(? AS DECIMAL(36,18))*CAST(? AS DECIMAL(36,18))*CAST(? AS DECIMAL(36,18))/36500,18) AS DECIMAL(36,18))');
            $expectedQuery->execute([$amount, $plan['apy'], $planDays]);
            $expectedDividend = (string) $expectedQuery->fetchColumn();
            $referrerId = (int) ($user['invited_by_user_id'] ?? 0);
            $commission = '0';
            if ($referrerId > 0) {
                $commissionQuery = $pdo->prepare('SELECT CAST(CAST(? AS DECIMAL(36,18))*0.05 AS DECIMAL(36,18))');
                $commissionQuery->execute([$amount]);
                $commission = (string) $commissionQuery->fetchColumn();
            }
            $reserveQuery = $pdo->prepare('SELECT CAST(CAST(? AS DECIMAL(36,18))+CAST(? AS DECIMAL(36,18)) AS DECIMAL(36,18))');
            $reserveQuery->execute([$expectedDividend, $commission]);
            $reserveAmount = (string) $reserveQuery->fetchColumn();
            $quotaCheck = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))-CAST(? AS DECIMAL(36,18))>=CAST(? AS DECIMAL(36,18))');
            $quotaCheck->execute([$phase['total_quota'], $phase['reserved_quota'], $reserveAmount]);
            if ((int) $quotaCheck->fetchColumn() !== 1) { $pdo->rollBack(); jsonResponse(null, '当前质押期剩余额度不足，无法预分配本笔分红和邀请奖励', 409); }
            $asset = $pdo->prepare('SELECT id,balance,stakeable_balance FROM wallet_assets WHERE user_id=? AND symbol=? AND chain="BSC" FOR UPDATE');
            $asset->execute([$user['id'], $stakeSymbol]);
            $wallet = $asset->fetch();
            $enough = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))>=CAST(? AS DECIMAL(36,18))');
            $enough->execute([$wallet['stakeable_balance'] ?? '0', $amount]);
            if (!$wallet || (int) $enough->fetchColumn() !== 1) { $pdo->rollBack(); jsonResponse(null, '可质押 ' . $stakeSymbol . ' 余额不足，邀请奖励和分红不能用于质押', 409); }
            walletDebit($pdo, (int) $user['id'], $stakeSymbol, 'BSC', $amount, true);
            $apy = (string) $plan['apy'];
            $statement = $pdo->prepare('INSERT INTO staking_records (user_id,symbol,phase_id,plan_days,amount,apy,status) VALUES (?,?,?,?,?,?,?)');
            $statement->execute([$user['id'], $stakeSymbol, $phase['id'], $planDays, $amount, $apy, 'active']);
            $stakingId = (int) $pdo->lastInsertId();
            $pdo->prepare('UPDATE staking_phases SET reserved_quota=reserved_quota+?,paid_quota=paid_quota+? WHERE id=?')->execute([$reserveAmount, $commission, $phase['id']]);
            $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,stakeable_amount,status) VALUES (?,?,"BSC","staking",?,?,"completed")')->execute([$user['id'], $stakeSymbol, $amount, $amount]);
            if ($referrerId > 0) {
                walletCredit($pdo, $referrerId, $stakeSymbol, 'BSC', $commission, false);
                $pdo->prepare('INSERT INTO referral_rewards (staking_id,phase_id,referrer_user_id,staker_user_id,staked_amount,reward_amount) VALUES (?,?,?,?,?,?)')->execute([$stakingId, $phase['id'], $referrerId, $user['id'], $amount, $commission]);
                $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,status) VALUES (?,?,"BSC","referral_reward",?,"completed")')->execute([$referrerId, $stakeSymbol, $commission]);
            }
             $stakingResult=financialRequestFinish($pdo,(int)$user['id'],$action,$requestId,['id'=>$stakingId,'referral_commission'=>$commission]);
             $pdo->commit();
             jsonResponse($stakingResult, '质押记录已创建', 201);

        case 'staking.redeem':
            $user = currentUser();
            requireFields($body, ['id']);
            $pdo = db();
            $pdo->beginTransaction();
            $find = $pdo->prepare('SELECT *,CAST(ROUND(amount*apy*plan_days/36500,18) AS DECIMAL(36,18)) AS reward_amount FROM staking_records WHERE id=? AND user_id=? AND status="active" AND DATE_ADD(created_at, INTERVAL plan_days DAY) <= NOW() FOR UPDATE');
            $find->execute([(int) $body['id'], $user['id']]);
            $staking = $find->fetch();
            if (!$staking) { $pdo->rollBack(); jsonResponse(null, '质押记录不存在、尚未到期或已结束', 409); }
            $paidDividend = (string) $pdo->query('SELECT COALESCE(SUM(amount),0) FROM staking_dividends WHERE staking_id=' . (int) $staking['id'])->fetchColumn();
            $totalAmount = (string) $staking['amount'];
            $redeemSymbol = strtoupper((string) ($staking['symbol'] ?? 'SPX001'));
            $pdo->prepare('UPDATE staking_records SET status="redeemed" WHERE id=?')->execute([$staking['id']]);
            walletCredit($pdo, (int) $user['id'], $redeemSymbol, 'BSC', $totalAmount, false);
             $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,status) VALUES (?,?,"BSC","staking_redeem",?,"completed")')->execute([$user['id'], $redeemSymbol, $staking['amount']]);
             $pdo->commit();
             jsonResponse(['principal' => $staking['amount'], 'paid_dividend' => $paidDividend, 'total_amount' => $totalAmount], '质押本金已赎回，分红已按周期到账');

        default:
            jsonResponse(null, '未知接口: ' . $action, 404);
    }
} catch (Throwable $exception) {
    if (in_array($action, ['upload.message', 'upload.message.chunk', 'upload.message.status'], true)) {
        $status = $exception instanceof DomainException && in_array((int)$exception->getCode(), [403, 409, 413, 422, 429], true) ? (int)$exception->getCode() : 500;
        if ($status === 500) error_log('Chat upload error: ' . $exception->getMessage());
        jsonResponse(null, $status === 500 ? '附件上传失败，请稍后重试' : $exception->getMessage(), $status);
    }
    if (str_starts_with($action, 'calls.')) {
        $code = (int) $exception->getCode();
        $status = $exception instanceof InvalidArgumentException ? 422 : (in_array($code, [403, 404, 409, 429, 503], true) ? $code : 500);
        $message = $status === 500 ? '通话服务暂时不可用，请稍后重试' : $exception->getMessage();
        jsonResponse(null, $message, $status);
    }
    try {
        if (db()->inTransaction()) db()->rollBack();
    } catch (Throwable $ignored) {
    }
    if (($action === 'auth.register' || str_starts_with($action, 'groups.') || str_starts_with($action, 'payment.password.')) && $exception instanceof DomainException && in_array($exception->getCode(), [403,404,409,422,429,503], true)) jsonResponse(null, $exception->getMessage(), $exception->getCode());
    if (in_array($action,['financial.request','financial.resolve','wallet.withdraw','staking.create','merchant.product.save'],true) && $exception instanceof DomainException && in_array($exception->getCode(),[409,422],true)) jsonResponse(null,$exception->getMessage(),$exception->getCode());
    $config = require __DIR__ . '/config.php';
    if ($action === 'wallet.exchange.quote') {
        $status = (int) $exception->getCode() === 429 ? 429 : 502;
        jsonResponse($config['app']['debug'] ? ['error' => $exception->getMessage()] : null, 'Ave 行情请求失败', $status);
    }
    if ($action === 'wallet.exchange' && (int) $exception->getCode() === 429) {
        jsonResponse(null, 'Ave API 限流，请稍后重试', 429);
    }
    // Without this line a 500 leaves no trace anywhere: the body is a generic message and
    // the exception never reaches the PHP error log.
    error_log(sprintf(
        'api 500 action=%s ip=%s %s: %s (%s:%d)',
        $action,
        apiClientIp(),
        get_class($exception),
        $exception->getMessage(),
        basename($exception->getFile()),
        $exception->getLine()
    ));
    jsonResponse($config['app']['debug'] ? ['error' => $exception->getMessage()] : null, '服务器错误', 500);
}
