<?php
declare(strict_types=1);

$secureCookie = (!empty($_SERVER['HTTPS']) && strtolower((string) $_SERVER['HTTPS']) !== 'off') || (int) ($_SERVER['SERVER_PORT'] ?? 0) === 443;
$cookiePath = rtrim(str_replace('\\', '/', dirname((string) ($_SERVER['SCRIPT_NAME'] ?? '/'))), '/') . '/';
session_set_cookie_params([
    'lifetime' => 0,
    'path' => $cookiePath,
    'secure' => $secureCookie,
    'httponly' => true,
    'samesite' => 'Strict',
]);
session_start();
header('Cache-Control: no-store, private');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: same-origin');

require_once dirname(__DIR__) . '/db.php';
$pdo = db();

// Administrators are provisioned out of band (CLI or SQL); never auto-create an account here,
// because an empty admin_users table must not grant a known password to whoever loads this page.

function loginEscape(mixed $value): string
{
    return htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function loginCsrf(): string
{
    if (!isset($_SESSION['admin_csrf'])) $_SESSION['admin_csrf'] = bin2hex(random_bytes(32));
    return (string) $_SESSION['admin_csrf'];
}

if (isset($_SESSION['admin_id'])) {
    $current = $pdo->prepare('SELECT id FROM admin_users WHERE id=? AND status=1 LIMIT 1');
    $current->execute([(int) $_SESSION['admin_id']]);
    if ($current->fetch()) {
        header('Location: admin.php', true, 303);
        exit;
    }
    unset($_SESSION['admin_id']);
}

$flash = $_SESSION['admin_flash'] ?? null;
unset($_SESSION['admin_flash']);
$signedOut = isset($_GET['signed_out']);
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!hash_equals(loginCsrf(), (string) ($_POST['csrf'] ?? ''))) {
        http_response_code(419);
        exit('页面已过期，请刷新后重试。');
    }
    $username = trim((string) ($_POST['username'] ?? ''));
    $password = (string) ($_POST['password'] ?? '');
    $remoteIp = substr((string) ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 0, 45);
    $loginKey = strtolower(substr($username, 0, 64));
    $attempt = $pdo->prepare('SELECT failed_attempts,locked_until FROM admin_login_attempts WHERE ip_address=? AND username=? LIMIT 1');
    $attempt->execute([$remoteIp, $loginKey]);
    $attemptRow = $attempt->fetch();
    if ($attemptRow && $attemptRow['locked_until'] && strtotime((string) $attemptRow['locked_until']) > time()) {
        $_SESSION['admin_flash'] = ['message' => '账号或密码错误。', 'type' => 'error'];
        header('Location: login.php', true, 303);
        exit;
    }
    $login = $pdo->prepare('SELECT id,password_hash FROM admin_users WHERE username=? AND status=1 LIMIT 1');
    $login->execute([$username]);
    $account = $login->fetch();
    if ($account && password_verify($password, (string) $account['password_hash'])) {
        session_regenerate_id(true);
        $_SESSION['admin_csrf'] = bin2hex(random_bytes(32));
        $_SESSION['admin_id'] = (int) $account['id'];
        $pdo->prepare('DELETE FROM admin_login_attempts WHERE ip_address=? AND username=?')->execute([$remoteIp, $loginKey]);
        $pdo->prepare('UPDATE admin_users SET last_login_at=NOW() WHERE id=?')->execute([$account['id']]);
        header('Location: admin.php', true, 303);
        exit;
    }
    $pdo->beginTransaction();
    try {
        $pdo->prepare('INSERT IGNORE INTO admin_login_attempts (ip_address,username) VALUES (?,?)')->execute([$remoteIp, $loginKey]);
        $lockedAttempt = $pdo->prepare('SELECT failed_attempts FROM admin_login_attempts WHERE ip_address=? AND username=? FOR UPDATE');
        $lockedAttempt->execute([$remoteIp, $loginKey]);
        $failures = (int) $lockedAttempt->fetchColumn() + 1;
        $cooldownSeconds = $failures >= 10 ? 3600 : ($failures >= 5 ? 600 : 0);
        $lockedUntil = $cooldownSeconds > 0 ? date('Y-m-d H:i:s', time() + $cooldownSeconds) : null;
        $pdo->prepare('UPDATE admin_login_attempts SET failed_attempts=?,locked_until=? WHERE ip_address=? AND username=?')->execute([$failures, $lockedUntil, $remoteIp, $loginKey]);
        $pdo->commit();
        if ($cooldownSeconds > 0) {
            error_log('SPX admin login throttled for ' . $loginKey . ' from ' . $remoteIp . ' (' . $failures . ' failures)');
        }
    } catch (Throwable $exception) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        throw $exception;
    }
    $_SESSION['admin_flash'] = ['message' => '账号或密码错误。', 'type' => 'error'];
    header('Location: login.php', true, 303);
    exit;
}

$csrf = loginCsrf();
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>管理员登录 · SPX Chat</title>
    <style>
        :root{font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif;color:#17211e;background:#f2f5f3;font-synthesis:none}
        *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px}
        .login{width:min(100%,420px);background:#fff;border:1px solid #dce5e0;border-radius:8px;padding:32px;box-shadow:0 12px 36px #173d2b12}
        .brand{font-size:13px;font-weight:700;color:#17804f;letter-spacing:.08em}.login h1{font-size:24px;margin:10px 0 6px}.muted{color:#718078;font-size:14px;margin:0 0 24px}
        label{display:block;font-size:13px;font-weight:600;margin:16px 0 7px}input{width:100%;height:44px;border:1px solid #cbd7d0;border-radius:5px;padding:0 12px;font:inherit}input:focus{outline:2px solid #86c9a5;border-color:#17804f}
        button{width:100%;height:44px;margin-top:22px;border:0;border-radius:5px;background:#177b4d;color:white;font:inherit;font-weight:700;cursor:pointer}.note{padding:12px;border-radius:5px;background:#f2f5f3;color:#59665f;font-size:13px;line-height:1.6;margin-top:18px}.flash{padding:11px 12px;border-radius:5px;background:#fff0ee;color:#9b352a;font-size:13px;margin:0 0 16px}.flash.success{background:#e8f5ed;color:#17663f}
    </style>
    <link rel="stylesheet" href="ui-premium.css?v=147">
</head>
<body>
<main class="login">
    <div class="brand">SPX CHAT · SYSTEM ADMIN</div>
    <h1>管理员登录</h1>
    <p class="muted">使用独立管理员账号登录</p>
    <?php if ($signedOut): ?><div class="flash success">已安全退出。</div><?php endif; ?>
    <?php if (is_array($flash)): ?><div class="flash <?= ($flash['type'] ?? '') === 'error' ? '' : 'success' ?>"><?= loginEscape($flash['message'] ?? '') ?></div><?php endif; ?>
    <form method="post" autocomplete="on">
        <input type="hidden" name="csrf" value="<?= loginEscape($csrf) ?>">
        <label for="username">管理员账号</label>
        <input id="username" name="username" autocomplete="username" required maxlength="64" autofocus>
        <label for="password">密码</label>
        <input id="password" name="password" type="password" autocomplete="current-password" required>
        <button type="submit">登录后台</button>
    </form>
</main>
</body>
</html>
