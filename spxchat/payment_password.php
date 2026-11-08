<?php
declare(strict_types=1);

function paymentPasswordConfigured(PDO $pdo, int $uid): bool
{
    return (bool) chatQuery($pdo, 'SELECT 1 FROM user_payment_passwords WHERE user_id=?', [$uid])->fetchColumn();
}

function paymentPasswordApi(string $action, string $method, array $body): void
{
    $actions = ['payment.password.status', 'payment.password.set', 'payment.password.email.code', 'payment.password.change.email'];
    if (!in_array($action, $actions, true)) return;

    $user = currentUser();
    $uid = (int) $user['id'];
    $pdo = db();

    try {
        if ($action === 'payment.password.status') {
            if ($method !== 'GET') throw new DomainException('请求方式不正确', 405);
            jsonResponse(['configured' => paymentPasswordConfigured($pdo, $uid)]);
        }

        if ($method !== 'POST') throw new DomainException('请求方式不正确', 405);

        if ($action === 'payment.password.email.code') {
            if (!paymentPasswordConfigured($pdo, $uid)) throw new DomainException('请先设置支付密码', 409);
            $email = strtolower(trim((string) ($user['email'] ?? '')));
            if (!filter_var($email, FILTER_VALIDATE_EMAIL) || empty($user['email_verified_at'])) {
                throw new DomainException('当前账号未绑定并验证有效邮箱', 409);
            }
            enforceApiRateLimit('payment_password_code_' . $uid, 5);
            $config = require __DIR__ . '/config.php';
            $recent = $pdo->prepare('SELECT id FROM email_verification_codes WHERE email=? AND purpose="payment_password_change" AND created_at>? ORDER BY id DESC LIMIT 1');
            $recent->execute([$email, date('Y-m-d H:i:s', time() - (int) $config['app']['verification_resend_seconds'])]);
            if ($recent->fetch()) throw new DomainException('验证码发送过于频繁，请稍后再试', 429);

            $code = (string) random_int(100000, 999999);
            $statement = $pdo->prepare('INSERT INTO email_verification_codes (email,purpose,code_hash,expires_at,request_ip) VALUES (?,?,?,?,?)');
            $statement->execute([$email, 'payment_password_change', hash('sha256', $code), date('Y-m-d H:i:s', time() + (int) $config['app']['verification_code_ttl']), apiClientIp()]);
            $codeId = (int) $pdo->lastInsertId();
            try {
                smtpSend($email, 'SPX Chat 支付密码修改验证码', '<div style="font-family:Arial,sans-serif"><h2>SPX Chat 支付密码修改</h2><p>你的验证码是：</p><p style="font-size:30px;letter-spacing:8px;font-weight:bold">' . htmlspecialchars($code, ENT_QUOTES, 'UTF-8') . '</p><p>验证码 5 分钟内有效。如非本人操作，请及时检查账号安全。</p></div>');
            } catch (Throwable $exception) {
                $pdo->prepare('DELETE FROM email_verification_codes WHERE id=?')->execute([$codeId]);
                throw $exception;
            }
            jsonResponse(['email' => $email, 'expires_in' => (int) $config['app']['verification_code_ttl']], '验证码已发送');
        }

        requireFields($body, ['password', 'confirmation']);
        $password = (string) $body['password'];
        $confirmation = (string) $body['confirmation'];
        if (!preg_match('/^\d{6}$/D', $password)) throw new InvalidArgumentException('支付密码必须为 6 位数字');
        if (!hash_equals($password, $confirmation)) throw new InvalidArgumentException('两次输入的支付密码不一致');
        $loginHash = chatQuery($pdo, 'SELECT password_hash FROM users WHERE id=?', [$uid])->fetchColumn();
        if (password_verify($password, (string) $loginHash)) throw new InvalidArgumentException('支付密码不能与登录密码相同');

        if ($action === 'payment.password.set') {
            $pdo->beginTransaction();
            $lockUser = $pdo->prepare('SELECT id FROM users WHERE id=? FOR UPDATE');
            $lockUser->execute([$uid]);
            if (!$lockUser->fetchColumn()) { $pdo->rollBack(); throw new DomainException('账号不存在', 404); }
            if (paymentPasswordConfigured($pdo, $uid)) { $pdo->rollBack(); throw new DomainException('支付密码已设置，请通过邮箱验证后修改', 409); }
            $pdo->prepare('INSERT INTO user_payment_passwords (user_id,password_hash) VALUES (?,?)')->execute([$uid, password_hash($password, PASSWORD_DEFAULT)]);
            $pdo->commit();
            jsonResponse(['configured' => true], '支付密码设置成功');
        }

        requireFields($body, ['code']);
        enforceApiRateLimit('payment_password_change_' . $uid, 30);
        $email = strtolower(trim((string) ($user['email'] ?? '')));
        $code = trim((string) $body['code']);
        if (!filter_var($email, FILTER_VALIDATE_EMAIL) || empty($user['email_verified_at'])) throw new DomainException('当前账号未绑定并验证有效邮箱', 409);
        if (!preg_match('/^\d{6}$/D', $code)) throw new InvalidArgumentException('验证码格式不正确');

        $pdo->beginTransaction();
        $lockUser = $pdo->prepare('SELECT id FROM users WHERE id=? FOR UPDATE');
        $lockUser->execute([$uid]);
        if (!$lockUser->fetchColumn()) { $pdo->rollBack(); throw new DomainException('账号不存在', 404); }
        $verificationQuery = $pdo->prepare('SELECT * FROM email_verification_codes WHERE email=? AND purpose="payment_password_change" AND used_at IS NULL AND expires_at>NOW() ORDER BY id DESC LIMIT 1 FOR UPDATE');
        $verificationQuery->execute([$email]);
        $verification = $verificationQuery->fetch();
        if (!$verification) { $pdo->rollBack(); throw new DomainException('验证码错误或已过期', 422); }
        if ((int) $verification['attempts'] >= 5) { $pdo->commit(); throw new DomainException('验证码尝试次数过多，请重新获取', 429); }
        if (!hash_equals((string) $verification['code_hash'], hash('sha256', $code))) {
            $pdo->prepare('UPDATE email_verification_codes SET attempts=attempts+1 WHERE id=?')->execute([$verification['id']]);
            $pdo->commit();
            throw new DomainException('验证码错误或已过期', 422);
        }
        $changed = $pdo->prepare('UPDATE user_payment_passwords SET password_hash=?,failed_attempts=0,locked_until=NULL WHERE user_id=?');
        $changed->execute([password_hash($password, PASSWORD_DEFAULT), $uid]);
        if ($changed->rowCount() !== 1) { $pdo->rollBack(); throw new DomainException('请先设置支付密码', 409); }
        $pdo->prepare('UPDATE email_verification_codes SET used_at=NOW() WHERE id=?')->execute([$verification['id']]);
        $pdo->commit();
        jsonResponse(['configured' => true], '支付密码已修改');
    } catch (InvalidArgumentException $error) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        jsonResponse(null, $error->getMessage(), 422);
    } catch (DomainException $error) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        $status = in_array($error->getCode(), [403, 404, 405, 409, 429], true) ? $error->getCode() : 422;
        jsonResponse(null, $error->getMessage(), $status);
    }
}

function verifyPaymentPassword(PDO $pdo, int $uid, string $password): void
{
    if (!preg_match('/^\d{6}$/D', $password)) throw new DomainException('支付密码必须为 6 位数字', 422);

    $pdo->beginTransaction();
    $query = $pdo->prepare('SELECT password_hash,failed_attempts,locked_until,locked_until>NOW() AS is_locked FROM user_payment_passwords WHERE user_id=? FOR UPDATE');
    $query->execute([$uid]);
    $record = $query->fetch();
    if (!$record) { $pdo->rollBack(); throw new DomainException('请先到设置中设置支付密码', 409); }
    if ((int) $record['is_locked'] === 1) { $pdo->commit(); throw new DomainException('支付密码错误次数过多，请 10 分钟后重试', 429); }

    if (!password_verify($password, (string) $record['password_hash'])) {
        $failed = ($record['locked_until'] !== null ? 0 : (int) $record['failed_attempts']) + 1;
        if ($failed >= 5) {
            $pdo->prepare('UPDATE user_payment_passwords SET failed_attempts=5,locked_until=DATE_ADD(NOW(),INTERVAL 10 MINUTE) WHERE user_id=?')->execute([$uid]);
            $pdo->commit();
            throw new DomainException('支付密码错误次数过多，请 10 分钟后重试', 429);
        }
        $pdo->prepare('UPDATE user_payment_passwords SET failed_attempts=?,locked_until=NULL WHERE user_id=?')->execute([$failed, $uid]);
        $pdo->commit();
        throw new DomainException('支付密码不正确，未扣款', 403);
    }

    $pdo->prepare('UPDATE user_payment_passwords SET failed_attempts=0,locked_until=NULL WHERE user_id=?')->execute([$uid]);
    $pdo->commit();
}
