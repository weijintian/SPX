<?php
declare(strict_types=1);

function appGetEnv(string $key, string $default = ''): string
{
    $value = getenv($key);
    if ($value === false || $value === '') $value = $_ENV[$key] ?? $_SERVER[$key] ?? $default;
    return (string) $value;
}

/** SMTP credentials come from /etc/spxchat/secrets.php; env vars still win per call site. */
function smtpSecretValue(string $key, string $default = ''): string
{
    static $cache = null;
    if (!is_array($cache)) {
        $path = getenv('SPX_SECRETS_FILE') ?: '/etc/spxchat/secrets.php';
        $loaded = is_readable($path) ? require $path : [];
        $cache = is_array($loaded) ? (array) ($loaded['smtp'] ?? []) : [];
    }
    return (string) ($cache[$key] ?? $default);
}

function smtpSend(string $to, string $subject, string $html): void
{
    $host = appGetEnv('FISS_SMTP_HOST', 'smtp.qq.com');
    $user = appGetEnv('FISS_SMTP_USER', smtpSecretValue('user', '2873728660@qq.com'));
    // Environment variables take precedence over the deployment secrets file.
    $pass = appGetEnv('FISS_SMTP_PASS', smtpSecretValue('pass'));
    $port = (int) appGetEnv('FISS_SMTP_PORT', '465');
    if ($pass === '') throw new RuntimeException('未配置 SMTP 口令（/etc/spxchat/secrets.php 或 FISS_SMTP_PASS）');

    $socket = @fsockopen('ssl://' . $host, $port, $errorNo, $errorMessage, 15);
    if (!$socket) throw new RuntimeException('SMTP 连接失败: ' . $errorMessage . ' (' . $errorNo . ')');
    stream_set_timeout($socket, 15);
    smtpExpect($socket, 220);
    smtpCommand($socket, 'EHLO localhost', 250);
    smtpCommand($socket, 'AUTH LOGIN', 334);
    smtpCommand($socket, base64_encode($user), 334);
    smtpCommand($socket, base64_encode($pass), 235);
    smtpCommand($socket, 'MAIL FROM:<' . $user . '>', 250);
    smtpCommand($socket, 'RCPT TO:<' . $to . '>', 250);
    smtpCommand($socket, 'DATA', 354);

    $headers = [
        'From: SPX Chat <' . $user . '>',
        'To: <' . $to . '>',
        'Subject: =?UTF-8?B?' . base64_encode($subject) . '?=',
        'MIME-Version: 1.0',
        'Content-Type: text/html; charset=UTF-8',
        'Content-Transfer-Encoding: 8bit',
    ];
    $message = implode("\r\n", $headers) . "\r\n\r\n" . $html;
    $message = preg_replace('/^\./m', '..', $message) ?? $message;
    fwrite($socket, $message . "\r\n.\r\n");
    smtpExpect($socket, 250);
    smtpCommand($socket, 'QUIT', 221);
    fclose($socket);
}

function financeNotificationEmail(): string
{
    return 'yishengzaidu@qq.com';
}

function tryFinanceNotification(string $subject, string $html): void
{
    try {
        smtpSend(financeNotificationEmail(), $subject, $html);
    } catch (Throwable $exception) {
        error_log('Finance email notification failed: ' . $exception->getMessage());
    }
}

function sendFinanceTemplate(PDO $pdo, string $eventKey, array $variables = [], ?int $templateId = null, ?array $recipientIds = null): array
{
    $templateQuery = $templateId !== null
        ? $pdo->prepare('SELECT id,event_key,subject,body_html FROM email_notification_templates WHERE id=? AND status=1 LIMIT 1')
        : $pdo->prepare('SELECT id,event_key,subject,body_html FROM email_notification_templates WHERE event_key=? AND status=1 LIMIT 1');
    $templateQuery->execute($templateId !== null ? [$templateId] : [$eventKey]);
    $template = $templateQuery->fetch();
    if (!$template) return ['sent' => 0, 'failed' => 0, 'message' => '模板不存在或已停用'];
    $sql = 'SELECT DISTINCT r.id,r.email FROM email_notification_recipients r JOIN email_notification_subscriptions s ON s.recipient_id=r.id WHERE s.template_id=? AND r.status=1';
    $params = [(int) $template['id']];
    if ($recipientIds !== null) {
        $ids = array_values(array_filter(array_map('intval', $recipientIds), static fn(int $id): bool => $id > 0));
        if (!$ids) return ['sent' => 0, 'failed' => 0, 'message' => '没有选择收件人'];
        $sql .= ' AND r.id IN (' . implode(',', array_fill(0, count($ids), '?')) . ')';
        $params = array_merge($params, $ids);
    }
    $recipients = $pdo->prepare($sql);
    $recipients->execute($params);
    $subject = (string) $template['subject'];
    $body = (string) $template['body_html'];
    foreach ($variables as $key => $value) {
        $subject = str_replace('{{' . $key . '}}', (string) $value, $subject);
        $body = str_replace('{{' . $key . '}}', htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'), $body);
    }
    $sent = 0;
    $failed = 0;
    $log = $pdo->prepare('INSERT INTO email_notification_logs (event_key,recipient_email,subject,status,error_message) VALUES (?,?,?,?,?)');
    foreach ($recipients->fetchAll() as $recipient) {
        try {
            smtpSend((string) $recipient['email'], $subject, $body);
            $log->execute([(string) $template['event_key'], $recipient['email'], $subject, 'sent', null]);
            $sent++;
        } catch (Throwable $exception) {
            $log->execute([(string) $template['event_key'], $recipient['email'], $subject, 'failed', substr($exception->getMessage(), 0, 500)]);
            error_log('Finance email notification failed: ' . $exception->getMessage());
            $failed++;
        }
    }
    return ['sent' => $sent, 'failed' => $failed, 'message' => $sent > 0 ? '发送完成' : '没有邮件发送成功'];
}

function smtpCommand($socket, string $command, int $expected): void
{
    fwrite($socket, $command . "\r\n");
    smtpExpect($socket, $expected);
}

function smtpExpect($socket, int $expected): void
{
    $response = '';
    while (($line = fgets($socket, 515)) !== false) {
        $response .= $line;
        if (isset($line[3]) && $line[3] === ' ') break;
    }
    $actual = (int) substr($response, 0, 3);
    if ($actual !== $expected) throw new RuntimeException('SMTP 响应异常: ' . trim($response));
}
