<?php
declare(strict_types=1);
require_once __DIR__.'/db.php';
require_once __DIR__.'/auth.php';

function merchantSession(): void
{
    $config=require __DIR__.'/config.php';
    $https=(!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS']!=='off');
    $trusted=$config['app']['trusted_proxies'] ?? ['127.0.0.1','::1'];
    if (in_array($_SERVER['REMOTE_ADDR']??'', $trusted,true) && ($_SERVER['HTTP_X_FORWARDED_PROTO']??'')==='https') $https=true;
    $local=in_array($_SERVER['REMOTE_ADDR']??'', ['127.0.0.1','::1'],true) && preg_match('/^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/',$_SERVER['HTTP_HOST']??'');
    if (!$https && !$local) {http_response_code(426);exit('请使用 HTTPS 打开商户工作台，账号密码必须通过加密连接提交。');}
    header('Cache-Control: no-store');header('X-Content-Type-Options: nosniff');header('Referrer-Policy: same-origin');
    ini_set('session.use_strict_mode','1');ini_set('session.use_only_cookies','1');
    session_name('SPX_MERCHANT');
    $cookiePath=preg_replace('#/[^/]*$#','/',str_replace('\\','/',$_SERVER['SCRIPT_NAME']??'/merchant.php'));
    session_set_cookie_params(['lifetime'=>0,'path'=>$cookiePath ?: '/','secure'=>$https,'httponly'=>true,'samesite'=>'Strict']);
    session_start();
    if (isset($_SESSION['uid']) && (time()-($_SESSION['seen']??0)>1800 || time()-($_SESSION['login_at']??0)>28800)) merchantLogout();
    if (!isset($_SESSION['csrf'])) $_SESSION['csrf']=bin2hex(random_bytes(32));
}

function merchantLogout(): void
{
    if (!empty($_SESSION['token'])) db()->prepare('DELETE FROM user_tokens WHERE token=?')->execute([$_SESSION['token']]);
    $_SESSION=[];session_regenerate_id(true);$_SESSION['csrf']=bin2hex(random_bytes(32));
}

function merchantSessionUser(): ?array
{
    if (empty($_SESSION['uid'])) return null;
    $q=db()->prepare('SELECT id,username,nickname,status,password_hash FROM users WHERE id=?');$q->execute([$_SESSION['uid']]);$u=$q->fetch();
    if (!$u || (int)$u['status']!==1 || !hash_equals($_SESSION['auth_hash']??'',hash('sha256',$u['password_hash']))) {merchantLogout();return null;}
    $valid=db()->prepare('SELECT 1 FROM user_tokens WHERE user_id=? AND token=? AND expires_at>NOW() LIMIT 1');
    $valid->execute([$_SESSION['uid'],$_SESSION['token']??'']);
    if (!$valid->fetchColumn()) {merchantLogout();return null;}
    $_SESSION['seen']=time();return $u;
}

function merchantCsrf(string $csrf): bool {return $csrf!=='' && hash_equals($_SESSION['csrf']??'', $csrf);}

function merchantLogin(string $username,string $password): bool
{
    $username=trim($username);
    $pdo=db();$ip=$_SERVER['REMOTE_ADDR']??'unknown';$window=date('Y-m-d H:i:00', intdiv(time(),900)*900);
    // Both a shared IP limit and an account limit prevent switching usernames or addresses to bypass throttling.
    foreach (['merchant_ip:'.hash('sha256',$ip)=>50,'merchant_account:'.hash('sha256',strtolower($username))=>15] as $scope=>$limit) {
        $pdo->prepare('INSERT INTO api_rate_limits(scope_key,window_start,attempts) VALUES(?,?,1) ON DUPLICATE KEY UPDATE attempts=attempts+1')->execute([$scope,$window]);
        $q=$pdo->prepare('SELECT attempts FROM api_rate_limits WHERE scope_key=? AND window_start=?');$q->execute([$scope,$window]);
        if ((int)$q->fetchColumn()>$limit) throw new InvalidArgumentException('登录尝试过多，请十五分钟后再试');
    }
    if ($username==='' || strlen($username)>254 || strlen($password)>256) return false;
    // Match the App login: an account may sign in with its username or bound email.
    $q=$pdo->prepare('SELECT id,password_hash,status FROM users WHERE username=? OR email=? LIMIT 1');$q->execute([$username,strtolower($username)]);$u=$q->fetch();
    $hash=$u['password_hash']??'$2y$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi';
    if (!password_verify($password,$hash) || !$u || (int)$u['status']!==1) return false;
    merchantLogout();
    $_SESSION['uid']=(int)$u['id'];$_SESSION['auth_hash']=hash('sha256',$hash);$_SESSION['seen']=$_SESSION['login_at']=time();
    // Kept on the server only; browsers never receive an API bearer token.
    $_SESSION['token']=issueToken((int)$u['id']);
    $pdo->prepare('UPDATE user_tokens SET expires_at=DATE_ADD(NOW(),INTERVAL 8 HOUR) WHERE token=?')->execute([$_SESSION['token']]);
    return true;
}
