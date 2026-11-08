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
if ($_SERVER['REQUEST_METHOD'] !== 'POST' || !hash_equals((string) ($_SESSION['admin_csrf'] ?? ''), (string) ($_POST['csrf'] ?? ''))) {
    http_response_code(419);
    exit('页面已过期，请刷新后重试。');
}

$_SESSION = [];
if (ini_get('session.use_cookies')) {
    setcookie(session_name(), '', [
        'expires' => time() - 42000,
        'path' => $cookiePath,
        'secure' => $secureCookie,
        'httponly' => true,
        'samesite' => 'Strict',
    ]);
}
session_destroy();
header('Location: login.php?signed_out=1', true, 303);
exit;
