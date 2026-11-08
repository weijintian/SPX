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
require_once dirname(__DIR__) . '/smtp.php';
require_once dirname(__DIR__) . '/mall.php';
require_once __DIR__ . '/mall_admin.php';
require_once dirname(__DIR__) . '/app_download.php';
require_once dirname(__DIR__) . '/burn_tiers.php';
require_once __DIR__ . '/admin_export.php';
require_once __DIR__ . '/admin_queries.php';
require_once __DIR__ . '/admin_layout.php';
$pdo = db();

function adminEscape(mixed $value): string
{
    return htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function adminIsTokenContractAddress(string $address): bool
{
    return in_array(strtolower(trim($address)), [
        '0x55d398326f99059ff775485246999027b3197955',
        '0x0e920821464625cf661a4dcedeb6406e1b48ffff',
    ], true);
}

function adminAmount4(mixed $value): string
{
    $raw = trim((string) $value);
    if (!preg_match('/^(-?)(\d+)(?:\.(\d+))?$/', $raw, $matches)) return $raw;
    $negative = $matches[1] === '-';
    $whole = ltrim($matches[2], '0') ?: '0';
    $fraction = str_pad($matches[3] ?? '', 5, '0');
    $scaled = (int) substr($fraction, 0, 4);
    if ((int) $fraction[4] >= 5) {
        $scaled++;
        if ($scaled === 10000) {
            $scaled = 0;
            for ($index = strlen($whole) - 1; $index >= 0; $index--) {
                if ($whole[$index] !== '9') {
                    $whole[$index] = (string) ((int) $whole[$index] + 1);
                    break;
                }
                $whole[$index] = '0';
                if ($index === 0) $whole = '1' . $whole;
            }
        }
    }
    $rounded = $whole . '.' . str_pad((string) $scaled, 4, '0', STR_PAD_LEFT);
    return $negative && $rounded !== '0.0000' ? '-' . $rounded : $rounded;
}

function adminRedirect(string $section = 'dashboard'): never
{
    header('Location: admin.php?section=' . rawurlencode($section), true, 303);
    exit;
}

function adminFlash(string $message, string $type = 'success'): void
{
    $_SESSION['admin_flash'] = ['message' => $message, 'type' => $type];
}

function adminCsrf(): string
{
    if (!isset($_SESSION['admin_csrf'])) {
        $_SESSION['admin_csrf'] = bin2hex(random_bytes(32));
    }
    return (string) $_SESSION['admin_csrf'];
}

function adminCheckCsrf(): void
{
    $submitted = (string) ($_POST['csrf'] ?? '');
    if ($submitted === '' || !hash_equals(adminCsrf(), $submitted)) {
        http_response_code(419);
        exit('页面已过期，请刷新后重试。');
    }
}

function adminRequireLogin(): array
{
    global $pdo;
    $adminId = (int) ($_SESSION['admin_id'] ?? 0);
    if ($adminId <= 0) {
        header('Location: login.php', true, 303);
        exit;
    }
    $statement = $pdo->prepare('SELECT id,username,role,last_login_at FROM admin_users WHERE id=? AND status=1 LIMIT 1');
    $statement->execute([$adminId]);
    $admin = $statement->fetch();
    if (!$admin) {
        unset($_SESSION['admin_id']);
        header('Location: login.php', true, 303);
        exit;
    }
    return $admin;
}

function adminPostString(string $key, int $maxLength = 5000): string
{
    $value = trim((string) ($_POST[$key] ?? ''));
    if (strlen($value) > $maxLength) throw new InvalidArgumentException('提交内容过长');
    return $value;
}

function adminDateIsValid(string $value): bool
{
    $date = DateTimeImmutable::createFromFormat('!Y-m-d', $value);
    return $date instanceof DateTimeImmutable && $date->format('Y-m-d') === $value;
}

function adminStatusLabel(string $status): string
{
    $labels = [
        'pending' => '待处理', 'completed' => '已完成', 'failed' => '失败', 'sending' => '发送中',
        'rejected' => '已拒绝', 'open' => '待处理', 'processing' => '处理中',
        'resolved' => '已解决', 'closed' => '已关闭', 'cancelled' => '已取消',
        'active' => '进行中', 'inactive' => '已停用', 'paid' => '已支付',
        'shipped' => '已发货', 'delivered' => '已完成', 'answered' => '已答复',
        'reviewed' => '已查看', 'success' => '成功', 'owner' => '群主', 'admin' => '管理员',
        'member' => '成员',
    ];
    return $labels[$status] ?? $status;
}

function adminRoleLabel(string $role): string
{
    return ['superadmin' => '超级管理员', 'finance' => '财务', 'support' => '客服', 'operator' => '运营'][$role] ?? $role;
}

function adminTargetLabel(string $target): string
{
    return [
        'user' => '用户', 'recharge' => '充值记录', 'wallet_transaction' => '钱包流水',
        'product' => '商品', 'order' => '订单', 'ticket' => '客服工单', 'question' => '用户问题',
        'feedback' => '用户反馈', 'faq' => 'FAQ', 'group' => '群组', 'group_member' => '群成员',
        'admin' => '管理员', 'setting' => '系统设置', 'app_release' => 'Android 版本',
    ][$target] ?? $target;
}

function adminActionLabel(string $action): string
{
    $labels = [
        'user.update' => '用户状态/备注调整', 'user.password.reset' => '重置用户密码', 'wallet.adjust' => '调整用户余额',
        'recharge.manual.complete' => '人工补记充值', 'burn.tiers.save' => '维护销毁档位', 'settings.fiss_wallet.save' => '修改 FISS 充值钱包与自动销毁', 'recharge.review.reject' => '拒绝待复核充值', 'wallet.status.update' => '仅修改充值/提现状态', 'withdraw.approve' => '通过提现', 'withdraw.batch_approve' => '批量通过提现', 'withdraw.reject' => '拒绝提现并退款', 'withdraw.retry' => '失败提现重试',
        'order.complete' => '完成订单', 'order.cancel' => '取消订单并退款', 'order.ship' => '商品订单发货', 'product.save' => '维护商品',
        'ticket.reply' => '回复客服工单', 'question.reply' => '答复用户问题', 'feedback.update' => '更新用户反馈',
        'faq.save' => '维护 FAQ', 'group.update' => '更新群组', 'admin.create' => '创建管理员',
        'admin.update' => '更新管理员', 'admin.toggle' => '启停管理员', 'admin.password' => '修改管理员密码', 'finance.email.test' => '发送财务邮件测试', 'finance.recipient.save' => '维护邮件收件人', 'finance.recipient.delete' => '删除邮件收件人', 'finance.template.save' => '维护邮件模板', 'finance.template.reset' => '恢复邮件模板',
        'settings.deposit' => '修改充值收款地址', 'settings.fiss_withdraw' => '切换 FISS 提现开关', 'settings.withdraw_config.save' => '修改提现手续费与最低额', 'settings.backup_download.save' => '修改官网备用下载线路', 'app.release.upload' => '发布 Android 更新', 'staking.phase.save' => '调整质押奖励期', 'staking.plans.save' => '调整质押方案', 'user.wallet.update' => '修改用户绑定钱包地址', 'group.member.mute' => '禁言群成员',
        'group.member.unmute' => '解除群成员禁言', 'group.member.remove' => '移除群成员',
    ];
    return $labels[$action] ?? $action;
}

function adminTransactionLabel(string $type): string
{
    static $labels;
    $labels ??= require __DIR__ . '/admin_wallet_labels.php';
    return $labels[$type] ?? ('其他流水（' . $type . '）');
}

function adminVariantLines(mixed $json): string
{
    $variants = json_decode((string) $json, true);
    if (!is_array($variants)) return '';
    return implode("\n", array_map(static fn(array $variant): string => implode('|', [
        (string) ($variant['name'] ?? ''), (string) ($variant['price'] ?? '0'), (string) ($variant['stock'] ?? '0'),
    ]), $variants));
}

function adminStoreProductImages(): array
{
    $files = $_FILES['images'] ?? null;
    if (!is_array($files) || !isset($files['name']) || !is_array($files['name'])) return [];
    $selected = [];
    foreach ($files['name'] as $index => $name) {
        if (($files['error'][$index] ?? UPLOAD_ERR_NO_FILE) === UPLOAD_ERR_NO_FILE) continue;
        $selected[] = $index;
    }
    if (count($selected) > 8) throw new InvalidArgumentException('每次最多上传 8 张商品图片。');
    $directory = dirname(__DIR__) . '/uploads/products';
    if (!is_dir($directory) && !mkdir($directory, 0755, true) && !is_dir($directory)) throw new RuntimeException('无法创建商品图片目录。');
    $mimeExtensions = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp', 'image/gif' => 'gif'];
    $stored = [];
    try {
        foreach ($selected as $index) {
            $error = (int) ($files['error'][$index] ?? UPLOAD_ERR_NO_FILE);
            $temporary = (string) ($files['tmp_name'][$index] ?? '');
            if ($error !== UPLOAD_ERR_OK || $temporary === '' || !is_uploaded_file($temporary)) throw new InvalidArgumentException('图片上传失败，请重新选择文件。');
            if ((int) ($files['size'][$index] ?? 0) > 5 * 1024 * 1024) throw new InvalidArgumentException('单张商品图片不能超过 5 MB。');
            $mime = (new finfo(FILEINFO_MIME_TYPE))->file($temporary);
            $imageInfo = @getimagesize($temporary);
            if (!isset($mimeExtensions[$mime]) || !$imageInfo || ($imageInfo['mime'] ?? '') !== $mime) throw new InvalidArgumentException('商品图片仅支持有效的 JPG、PNG、WebP 或 GIF 文件。');
            $filename = bin2hex(random_bytes(20)) . '.' . $mimeExtensions[$mime];
            if (!move_uploaded_file($temporary, $directory . '/' . $filename)) throw new RuntimeException('商品图片保存失败。');
            $stored[] = ['url' => '/uploads/products/' . $filename, 'path' => $directory . '/' . $filename];
        }
    } catch (Throwable $exception) {
        foreach ($stored as $image) @unlink($image['path']);
        throw $exception;
    }
    return $stored;
}

function adminTrendSvg(array $rows, string $field, string $color): string
{
    $values = array_reverse(array_map(static fn(array $row): float => (float) ($row[$field] ?? 0), $rows));
    if (!$values) $values = array_fill(0, 30, 0.0);
    $max = max($values) ?: 1.0;
    $last = max(0, count($values) - 1);
    $points = [];
    $markers = [];
    foreach ($values as $index => $value) {
        $x = $last > 0 ? $index * 580 / $last : 290;
        $y = 112 - ($value / $max) * 96;
        $points[] = number_format($x, 1, '.', '') . ',' . number_format($y, 1, '.', '');
        $source = $rows[count($rows) - 1 - $index] ?? [];
        $unit = $field === 'recharge_usdt' ? 'USDT' : 'SPX001';
        $label = (string) ($source['day'] ?? '') . ' · 金额 ' . adminAmount4($source[$field] ?? '0') . ' ' . $unit;
        $markers[] = '<circle class="trend-marker" cx="' . number_format($x, 1, '.', '') . '" cy="' . number_format($y, 1, '.', '') . '" r="5" fill="' . adminEscape($color) . '" tabindex="0" role="img" aria-label="' . adminEscape($label) . '" data-tooltip="' . adminEscape($label) . '"><title>' . adminEscape($label) . '</title></circle>';
    }
    $pointString = implode(' ', $points);
    $areaPoints = '0,112 ' . $pointString . ' 580,112';
    $gradientID = 'trend-' . substr(sha1($field . $color), 0, 10);
    return '<svg class="trend-svg" viewBox="0 0 580 120" role="img" aria-label="近 30 日趋势" preserveAspectRatio="none"><defs><linearGradient id="' . $gradientID . '" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="' . adminEscape($color) . '" stop-opacity=".24"/><stop offset="1" stop-color="' . adminEscape($color) . '" stop-opacity=".02"/></linearGradient></defs><path class="trend-grid" d="M0 16H580 M0 64H580 M0 112H580"/><polygon points="' . $areaPoints . '" fill="url(#' . $gradientID . ')"/><polyline points="' . $pointString . '" fill="none" stroke="' . adminEscape($color) . '" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>' . implode('', $markers) . '</svg>';
}

function adminCan(string $section): bool
{
    global $admin;
    $roles = [
        'dashboard' => ['superadmin', 'finance', 'support', 'operator'],
        'merchants' => ['superadmin', 'finance', 'operator'],
        'mall_finance' => ['superadmin', 'finance'],
        'users' => ['superadmin', 'finance', 'support', 'operator'],
         'user_control' => ['superadmin', 'operator'],
         'wallets' => ['superadmin', 'finance', 'operator'], 'recharges' => ['superadmin', 'finance', 'operator'],
        'recharge_review' => ['superadmin', 'finance', 'operator'],
         'withdrawals' => ['superadmin', 'finance', 'operator'], 'transactions' => ['superadmin', 'finance', 'operator'],
         'exchanges' => ['superadmin', 'finance', 'operator'], 'staking' => ['superadmin', 'finance', 'operator'], 'staking_plans' => ['superadmin', 'finance', 'operator'],
         'referrals' => ['superadmin', 'finance', 'operator'], 'products' => ['superadmin', 'operator'],
         'versions' => ['superadmin', 'operator', 'support'],
         'orders' => ['superadmin', 'finance', 'operator'], 'tickets' => ['superadmin', 'support', 'operator'],
         'questions' => ['superadmin', 'support', 'operator'], 'feedback' => ['superadmin', 'support', 'operator'],
         'faqs' => ['superadmin', 'operator', 'support'], 'groups' => ['superadmin', 'operator'],
        'logs' => ['superadmin'], 'admins' => ['superadmin'], 'settings' => ['superadmin'],
          'profile' => ['superadmin', 'finance', 'support'], 'wallet_address_control' => ['superadmin'], 'email_notifications' => ['superadmin', 'operator'], 'email_notification_control' => ['superadmin'],
         'reports' => ['superadmin', 'finance', 'operator'], 'export' => ['superadmin', 'finance', 'support', 'operator'],
         'export_finance' => ['superadmin', 'finance', 'operator'], 'export_orders' => ['superadmin', 'finance', 'operator'],
         'export_tickets' => ['superadmin', 'support', 'operator'],
    ];
    return isset($admin['role']) && in_array((string) $admin['role'], $roles[$section] ?? [], true);
}

function adminAudit(PDO $pdo, array $admin, string $action, string $targetType, ?int $targetId,
                    ?string $reason, ?array $before, ?array $after, ?string $requestId = null): void
{
    $statement = $pdo->prepare('INSERT INTO admin_audit_logs (admin_id,admin_username,`action`,target_type,target_id,request_id,reason,before_data,after_data,ip_address) VALUES (?,?,?,?,?,?,?,?,?,?)');
    $statement->execute([
        $admin['id'], $admin['username'], $action, $targetType, $targetId, $requestId,
        $reason, $before === null ? null : json_encode($before, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        $after === null ? null : json_encode($after, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        substr((string) ($_SERVER['REMOTE_ADDR'] ?? ''), 0, 45) ?: null,
    ]);
}

$action = (string) ($_POST['action'] ?? '');
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    adminCheckCsrf();
    $admin = adminRequireLogin();
    $section = (string) ($_POST['section'] ?? 'dashboard');
    $newProductImages = [];
    $removedProductImages = [];
    $newReleasePath = null;
    try {
        mallAdminPost($pdo, $admin, $action);
        $actionModules = [
            'user.save' => 'user_control', 'user.password.reset' => 'user_control', 'wallet.adjust' => 'wallets', 'recharge.manual.complete' => 'recharges', 'burn.tiers.save' => 'staking_plans', 'settings.fiss_wallet.save' => 'staking_plans', 'recharge.review.reject' => 'recharges', 'withdraw.process' => 'withdrawals', 'withdraw.batch_approve' => 'withdrawals', 'withdraw.retry' => 'withdrawals', 'wallet.status.update' => 'wallets',
            'product.save' => 'products', 'app.release.upload' => 'settings', 'staking.phase.save' => 'staking_plans', 'staking.plans.save' => 'staking_plans', 'order.process' => 'orders', 'order.ship' => 'orders', 'ticket.save' => 'tickets', 'question.save' => 'questions',
            'feedback.save' => 'feedback', 'faq.save' => 'faqs',
            'group.save' => 'groups', 'group.member' => 'groups', 'settings.deposit.save' => 'settings', 'settings.fiss_withdraw.save' => 'settings', 'settings.withdraw_config.save' => 'settings', 'settings.api_nodes.save' => 'settings', 'settings.invite_base.save' => 'settings', 'settings.mall_limits.save' => 'settings', 'settings.mall_settle.save' => 'settings', 'settings.ios_download.save' => 'settings', 'settings.backup_download.save' => 'settings', 'password.save' => 'profile',
            'admin.save' => 'admins', 'admin.toggle' => 'admins', 'user.wallet.update' => 'wallet_address_control', 'finance.email.test' => 'email_notifications', 'finance.recipient.save' => 'email_notification_control', 'finance.recipient.delete' => 'email_notification_control', 'finance.template.save' => 'email_notification_control', 'finance.template.reset' => 'email_notification_control',
        ];
        if (isset($actionModules[$action]) && !adminCan($actionModules[$action])) {
            throw new RuntimeException('当前管理员角色无权执行此操作。');
        }

        if ($action === 'finance.email.test') {
            $templateId = max(0, (int) ($_POST['template_id'] ?? 0));
            $reason = adminPostString('reason', 500);
            if ($reason === '') throw new InvalidArgumentException('请填写测试邮件原因。');
            if ($templateId <= 0) throw new InvalidArgumentException('请选择要测试的邮件模板。');
            $test = sendFinanceTemplate($pdo, '', ['username' => '测试用户', 'nickname' => '测试昵称', 'user_id' => 0, 'symbol' => 'SPX001 / BSC', 'amount' => '100', 'fee' => '1', 'net_amount' => '99', 'address' => '0x0000000000000000000000000000000000000000', 'reason' => '测试失败原因', 'status' => '测试状态', 'created_at' => date('Y-m-d H:i:s')], $templateId);
            if ((int) ($test['sent'] ?? 0) === 0) throw new RuntimeException((string) ($test['message'] ?? '测试邮件发送失败。'));
            adminAudit($pdo, $admin, 'finance.email.test', 'setting', null, $reason, null, ['recipient' => financeNotificationEmail()]);
            adminFlash('测试邮件已发送 ' . (int) $test['sent'] . ' 封，失败 ' . (int) ($test['failed'] ?? 0) . ' 封。');
            adminRedirect('email_notifications');
        }

        if ($action === 'finance.recipient.save') {
            $recipientId = max(0, (int) ($_POST['recipient_id'] ?? 0));
            $email = strtolower(trim(adminPostString('email', 190)));
            $status = (int) ($_POST['status'] ?? 0) === 1 ? 1 : 0;
            $templateIds = array_values(array_unique(array_filter(array_map('intval', (array) ($_POST['template_ids'] ?? [])), static fn(int $id): bool => $id > 0)));
            if (!filter_var($email, FILTER_VALIDATE_EMAIL)) throw new InvalidArgumentException('邮箱格式无效。');
            $pdo->beginTransaction();
            if ($recipientId > 0) {
                $beforeQuery = $pdo->prepare('SELECT id,email,status FROM email_notification_recipients WHERE id=? FOR UPDATE');
                $beforeQuery->execute([$recipientId]);
                $before = $beforeQuery->fetch();
                if (!$before) throw new InvalidArgumentException('收件人不存在。');
                $pdo->prepare('UPDATE email_notification_recipients SET email=?,status=? WHERE id=?')->execute([$email, $status, $recipientId]);
            } else {
                $pdo->prepare('INSERT INTO email_notification_recipients (email,status) VALUES (?,?)')->execute([$email, $status]);
                $recipientId = (int) $pdo->lastInsertId();
                $before = null;
            }
            $pdo->prepare('DELETE FROM email_notification_subscriptions WHERE recipient_id=?')->execute([$recipientId]);
            $subscribe = $pdo->prepare('INSERT IGNORE INTO email_notification_subscriptions (recipient_id,template_id) VALUES (?,?)');
            foreach ($templateIds as $templateId) $subscribe->execute([$recipientId, $templateId]);
            adminAudit($pdo, $admin, 'finance.recipient.save', 'setting', $recipientId, '维护邮件收件人', $before, ['email' => $email, 'status' => $status, 'template_ids' => $templateIds]);
            $pdo->commit();
            adminFlash('邮件收件人已保存。');
            adminRedirect('email_notifications');
        }

        if ($action === 'finance.recipient.delete') {
            $recipientId = max(0, (int) ($_POST['recipient_id'] ?? 0));
            if ($recipientId <= 0) throw new InvalidArgumentException('收件人无效。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,email,status FROM email_notification_recipients WHERE id=? FOR UPDATE');
            $lookup->execute([$recipientId]);
            $before = $lookup->fetch();
            if (!$before) throw new InvalidArgumentException('收件人不存在。');
            $pdo->prepare('DELETE FROM email_notification_recipients WHERE id=?')->execute([$recipientId]);
            adminAudit($pdo, $admin, 'finance.recipient.delete', 'setting', $recipientId, '删除邮件收件人', $before, null);
            $pdo->commit();
            adminFlash('邮件收件人已删除。');
            adminRedirect('email_notifications');
        }

        if ($action === 'finance.template.save' || $action === 'finance.template.reset') {
            $templateId = max(0, (int) ($_POST['template_id'] ?? 0));
            $templateQuery = $pdo->prepare('SELECT id,event_key,name,subject,body_html,status FROM email_notification_templates WHERE id=? LIMIT 1');
            $templateQuery->execute([$templateId]);
            $before = $templateQuery->fetch();
            if (!$before) throw new InvalidArgumentException('邮件模板不存在。');
            $defaults = [
                'recharge_completed' => ['充值到账', 'SPX Chat 充值到账通知', '<h2>充值已到账</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>到账金额：{{amount}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'],
                'withdraw_submitted' => ['提现申请', 'SPX Chat 提现申请通知', '<h2>提现申请</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>申请金额：{{amount}}</p><p>手续费：{{fee}}</p><p>实际到账：{{net_amount}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'],
                'withdraw_failed' => ['提现失败', 'SPX Chat 提现失败通知', '<h2>提现失败</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>提现金额：{{amount}}</p><p>失败原因：{{reason}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'],
            ];
            $default = $defaults[$before['event_key']] ?? [$before['name'], $before['subject'], $before['body_html']];
            $subject = $action === 'finance.template.reset' ? $default[1] : adminPostString('subject', 255);
            $body = $action === 'finance.template.reset' ? $default[2] : adminPostString('body_html', 50000);
            $pdo->prepare('UPDATE email_notification_templates SET subject=?,body_html=? WHERE id=?')->execute([$subject, $body, $templateId]);
            adminAudit($pdo, $admin, $action, 'setting', $templateId, $action === 'finance.template.reset' ? '恢复邮件模板默认内容' : '修改邮件模板', $before, ['subject' => $subject, 'body_html' => $body]);
            adminFlash($action === 'finance.template.reset' ? '邮件模板已恢复默认内容。' : '邮件模板已保存。');
            adminRedirect('email_notifications');
        }

        if ($action === 'user.save') {
            $userId = max(0, (int) ($_POST['user_id'] ?? 0));
            $status = (int) ($_POST['status'] ?? -1);
            $remark = adminPostString('remark', 100);
            $reason = adminPostString('reason', 500);
            if ($userId <= 0 || !in_array($status, [0, 1], true) || $reason === '') throw new InvalidArgumentException('用户、状态和操作原因不能为空。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,status,remark FROM users WHERE id=? FOR UPDATE');
            $lookup->execute([$userId]);
            $before = $lookup->fetch();
            if (!$before) { $pdo->rollBack(); throw new InvalidArgumentException('用户不存在。'); }
            $pdo->prepare('UPDATE users SET status=?,remark=? WHERE id=?')->execute([$status, $remark ?: null, $userId]);
            if ($status === 0) $pdo->prepare('DELETE FROM user_tokens WHERE user_id=?')->execute([$userId]);
            adminAudit($pdo, $admin, 'user.update', 'user', $userId, $reason, $before, ['status' => $status, 'remark' => $remark]);
            $pdo->commit();
            adminFlash($status === 1 ? '用户已启用。' : '用户已停用，现有登录令牌已撤销。');
            header('Location: admin.php?section=users&detail_id=' . $userId, true, 303);
            exit;
        }

        if ($action === 'user.password.reset') {
            $userId = max(0, (int) ($_POST['user_id'] ?? 0));
            $newPassword = (string) ($_POST['new_password'] ?? '');
            $reason = adminPostString('reason', 500);
            if ($userId <= 0 || strlen($newPassword) < 12 || strlen($newPassword) > 200 || $reason === '') {
                throw new InvalidArgumentException('用户、至少 12 位的新密码和重置原因均为必填项。');
            }
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,username,status FROM users WHERE id=? FOR UPDATE');
            $lookup->execute([$userId]);
            $targetUser = $lookup->fetch();
            if (!$targetUser) { $pdo->rollBack(); throw new InvalidArgumentException('用户不存在。'); }
            $pdo->prepare('UPDATE users SET password_hash=? WHERE id=?')->execute([password_hash($newPassword, PASSWORD_DEFAULT), $userId]);
            $pdo->prepare('DELETE FROM user_tokens WHERE user_id=?')->execute([$userId]);
            adminAudit($pdo, $admin, 'user.password.reset', 'user', $userId, $reason, ['status' => $targetUser['status']], ['password_reset' => true, 'tokens_revoked' => true]);
            $pdo->commit();
            adminFlash('用户密码已重置，原有登录令牌已撤销。');
            header('Location: admin.php?section=users&detail_id=' . $userId, true, 303);
            exit;
        }

        if ($action === 'user.wallet.update') {
            $userId = max(0, (int) ($_POST['user_id'] ?? 0));
            $walletAddress = strtolower(trim((string) ($_POST['wallet_address'] ?? '')));
            $reason = adminPostString('reason', 500);
            $requestId = (string) ($_POST['request_id'] ?? '');
            if ($userId <= 0 || !preg_match('/^0x[0-9a-f]{40}$/', $walletAddress) || adminIsTokenContractAddress($walletAddress) || $reason === '') {
                throw new InvalidArgumentException('钱包地址格式无效，不能填写代币合约地址，且必须填写操作原因。');
            }
            if (!preg_match('/^[a-f0-9]{32}$/', $requestId)) throw new InvalidArgumentException('钱包地址修改请求已失效，请刷新页面重试。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,username,bsc_wallet_address FROM users WHERE id=? FOR UPDATE');
            $lookup->execute([$userId]);
            $targetUser = $lookup->fetch();
            if (!$targetUser) { $pdo->rollBack(); throw new InvalidArgumentException('用户不存在。'); }
            if (strtolower((string) ($targetUser['bsc_wallet_address'] ?? '')) === $walletAddress) {
                $pdo->rollBack();
                throw new InvalidArgumentException('新钱包地址与当前绑定地址相同。');
            }
            $addressOwner = $pdo->prepare('SELECT id,username FROM users WHERE bsc_wallet_address=? AND id<>? LIMIT 1 FOR UPDATE');
            $addressOwner->execute([$walletAddress, $userId]);
            if ($addressOwner->fetch()) { $pdo->rollBack(); throw new InvalidArgumentException('该钱包地址已绑定其他用户，不能重复绑定。'); }
            $pdo->prepare('UPDATE users SET bsc_wallet_address=? WHERE id=?')->execute([$walletAddress, $userId]);
            adminAudit($pdo, $admin, 'user.wallet.update', 'user', $userId, $reason,
                ['username' => $targetUser['username'], 'bsc_wallet_address' => $targetUser['bsc_wallet_address']],
                ['username' => $targetUser['username'], 'bsc_wallet_address' => $walletAddress], $requestId);
            $pdo->commit();
            adminFlash('用户绑定钱包地址已更新，历史记录未修改。');
            adminRedirect('users');
        }

        if ($action === 'wallet.status.update') {
            $recordType = (string) ($_POST['record_type'] ?? '');
            $recordId = max(0, (int) ($_POST['record_id'] ?? 0));
            $status = trim((string) ($_POST['status'] ?? ''));
            $reason = adminPostString('reason', 500);
            $allowedStatuses = $recordType === 'withdraw'
                ? ['pending', 'completed', 'failed', 'rejected', 'cancelled']
                : ['pending', 'processing', 'completed', 'failed', 'rejected', 'cancelled'];
            if (!in_array($recordType, ['recharge', 'withdraw'], true) || $recordId <= 0 || !in_array($status, $allowedStatuses, true) || $reason === '') {
                throw new InvalidArgumentException('记录类型、状态和操作原因不能为空。');
            }
            if ($recordType === 'withdraw' && in_array($status, ['rejected', 'failed'], true)) {
                // This action never moves funds, so a "failed" withdrawal would stay debited.
                throw new InvalidArgumentException('提现改为“已拒绝/失败”涉及退款，请用列表里的「拒绝并退款」，或等待打币服务自动失败退款。');
            }
            $pdo = db();
            $pdo->beginTransaction();
            if ($recordType === 'recharge') {
                $lookup = $pdo->prepare('SELECT id,user_id,tx_hash,currency,amount,status FROM recharge_records WHERE id=? FOR UPDATE');
                $lookup->execute([$recordId]);
                $before = $lookup->fetch();
                if (!$before) { $pdo->rollBack(); throw new InvalidArgumentException('充值记录不存在。'); }
                $pdo->prepare('UPDATE recharge_records SET status=? WHERE id=?')->execute([$status, $recordId]);
                adminAudit($pdo, $admin, 'wallet.status.update', 'recharge', $recordId, $reason, $before, ['status' => $status, 'funds_changed' => false]);
                $redirect = 'recharges';
            } else {
                $lookup = $pdo->prepare('SELECT t.id,t.user_id,t.symbol,t.amount,t.fee_amount,t.address,t.status,t.tx_hash,u.username,u.nickname FROM wallet_transactions t JOIN users u ON u.id=t.user_id WHERE t.id=? AND t.type="withdraw" FOR UPDATE');
                $lookup->execute([$recordId]);
                $before = $lookup->fetch();
                if (!$before) { $pdo->rollBack(); throw new InvalidArgumentException('提现记录不存在。'); }
                if ($before['status'] === 'completed') { $pdo->rollBack(); throw new InvalidArgumentException('已完成提现禁止再次修改状态。'); }
                $pdo->prepare('UPDATE wallet_transactions SET status=? WHERE id=? AND type="withdraw"')->execute([$status, $recordId]);
                adminAudit($pdo, $admin, 'wallet.status.update', 'wallet_transaction', $recordId, $reason, $before, ['status' => $status, 'funds_changed' => false]);
                $redirect = 'withdrawals';
            }
            $pdo->commit();
            if ($recordType === 'withdraw' && $status === 'failed' && ($before['status'] ?? '') !== 'failed') {
                try {
                    sendFinanceTemplate($pdo, 'withdraw_failed', [
                        'username' => (string) ($before['username'] ?? $before['user_id']),
                        'nickname' => (string) ($before['nickname'] ?? ''),
                        'user_id' => (int) $before['user_id'],
                        'symbol' => (string) $before['symbol'] . ' / BSC',
                        'amount' => (string) $before['amount'],
                        'fee' => (string) ($before['fee_amount'] ?? '0'),
                        'net_amount' => (string) $before['amount'],
                        'address' => (string) ($before['address'] ?? ''),
                        'reason' => $reason,
                        'status' => '失败',
                        'created_at' => date('Y-m-d H:i:s'),
                    ]);
                } catch (Throwable $exception) {
                    error_log('Admin withdrawal failure email notification failed: ' . $exception->getMessage());
                }
            }
            adminFlash('状态已修改，未改变任何资金或余额。');
            adminRedirect($redirect);
        }

        if ($action === 'wallet.adjust') {
            $userId = max(0, (int) ($_POST['user_id'] ?? 0));
            $symbol = strtoupper(adminPostString('symbol', 20));
            $operation = (string) ($_POST['operation'] ?? '');
            $amount = adminPostString('amount', 40);
            $reason = adminPostString('reason', 500);
            $requestId = (string) ($_POST['request_id'] ?? '');
            if ($userId <= 0 || !in_array($symbol, ['USDT', 'SPX001', 'FISS'], true) || !in_array($operation, ['add', 'subtract', 'set'], true) || $reason === '' || !preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,6})?$/', $amount)) {
                throw new InvalidArgumentException('余额调整参数无效，且必须填写操作原因。');
            }
            if (!preg_match('/^[a-f0-9]{32}$/', $requestId)) throw new InvalidArgumentException('余额调整请求已失效，请刷新页面重试。');
            if ($operation !== 'set' && !preg_match('/[1-9]/', $amount)) throw new InvalidArgumentException('增加或扣减金额必须大于 0。');
            $pdo->beginTransaction();
            $pdo->prepare('INSERT IGNORE INTO wallet_assets (user_id,symbol,chain,balance) VALUES (?, ?, "BSC", 0)')->execute([$userId, $symbol]);
            $asset = $pdo->prepare('SELECT id,balance,stakeable_balance FROM wallet_assets WHERE user_id=? AND symbol=? AND chain="BSC" FOR UPDATE');
            $asset->execute([$userId, $symbol]);
            $wallet = $asset->fetch();
            if (!$wallet) { $pdo->rollBack(); throw new InvalidArgumentException('用户或钱包资产不存在。'); }
            $beforeBalance = (string) $wallet['balance'];
            $stakeableDelta = '0';
            if ($operation === 'set') {
                $deltaQuery = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))-CAST(? AS DECIMAL(36,18))');
                $deltaQuery->execute([$amount, $beforeBalance]);
                $delta = (string) $deltaQuery->fetchColumn();
            } else {
                $delta = $operation === 'add' ? $amount : '-' . $amount;
            }
            $zero = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))=0');
            $zero->execute([$delta]);
            if ((int) $zero->fetchColumn() === 1) { $pdo->rollBack(); throw new InvalidArgumentException('余额没有变化，无需提交。'); }
            if (!str_starts_with($delta, '-')) {
                // FISS 与 SPX001 一样可以质押：后台充值时也要同步"可质押余额"，
                // 否则加完 FISS 用户仍然质押不了。
                $stakeableSymbol = in_array($symbol, ['SPX001', 'FISS'], true);
                walletCredit($pdo, $userId, $symbol, 'BSC', $delta, $stakeableSymbol);
                $stakeableDelta = $stakeableSymbol ? $delta : '0';
            } else {
                $stakeableUsed = walletDebit($pdo, $userId, $symbol, 'BSC', ltrim($delta, '-'), false);
                $stakeableDelta = in_array($symbol, ['SPX001', 'FISS'], true) ? '-' . $stakeableUsed : '0';
            }
            $afterQuery = $pdo->prepare('SELECT balance FROM wallet_assets WHERE id=?');
            $afterQuery->execute([$wallet['id']]);
            $afterBalance = (string) $afterQuery->fetchColumn();
            $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,stakeable_amount,address,status) VALUES (?, ?, "BSC", "admin_adjustment", ?, ?, "ADMIN", "completed")')->execute([$userId, $symbol, $delta, $stakeableDelta]);
            adminAudit($pdo, $admin, 'wallet.adjust', 'user', $userId, $reason, ['symbol' => $symbol, 'balance' => $beforeBalance], ['symbol' => $symbol, 'balance' => $afterBalance, 'delta' => $delta], $requestId);
            $pdo->commit();
            adminFlash('余额已调整，钱包流水和审计日志已记录。');
            header('Location: admin.php?section=users&detail_id=' . $userId, true, 303);
            exit;
        }

        if ($action === 'recharge.manual.complete') {
            $recordId = max(0, (int) ($_POST['record_id'] ?? 0));
            $reason = adminPostString('reason', 500);
            $requestId = (string) ($_POST['request_id'] ?? '');
            if ($recordId <= 0 || $reason === '' || !preg_match('/^[a-f0-9]{32}$/', $requestId)) {
                throw new InvalidArgumentException('充值记录、处理原因或请求编号无效。');
            }
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT * FROM recharge_records WHERE id=? FOR UPDATE');
            $lookup->execute([$recordId]);
            $recharge = $lookup->fetch();
            $symbol = strtoupper((string) ($recharge['currency'] ?? ''));
            if (!$recharge
                || !in_array((string) $recharge['status'], ['failed', 'manual_review'], true)
                || !in_array($symbol, ['USDT', 'SPX001', 'FISS'], true)) {
                throw new InvalidArgumentException('仅可人工补记「失败」或「待复核」状态的 USDT / SPX001 / FISS 充值。');
            }
            $ledgerQuery = $pdo->prepare('SELECT id,amount,status FROM wallet_transactions WHERE tx_hash=? AND user_id=? AND type="deposit" AND symbol=? AND chain="BSC" FOR UPDATE');
            $ledgerQuery->execute([$recharge['tx_hash'], $recharge['user_id'], $symbol]);
            $ledger = $ledgerQuery->fetch();
            if (!$ledger || !in_array($ledger['status'], ['rejected', 'pending', 'manual_review'], true)) {
                throw new InvalidArgumentException('找不到可补记的原始充值流水，或该流水已处理。');
            }
            $sameAmount = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))=CAST(? AS DECIMAL(36,18))');
            $sameAmount->execute([$ledger['amount'], $recharge['amount']]);
            if ((int) $sameAmount->fetchColumn() !== 1) throw new RuntimeException('充值记录金额与钱包流水不一致，已停止补记。');
            $amount = (string) $recharge['amount'];
            // SPX001 / FISS 与 USDT 不同：入账时同步增加「可质押余额」，否则补记后用户仍然质押不了
            $stakeable = in_array($symbol, ['SPX001', 'FISS'], true) ? $amount : '0';
            $beforeQuery = $pdo->prepare('SELECT COALESCE(balance,"0") FROM wallet_assets WHERE user_id=? AND symbol=? AND chain="BSC" LIMIT 1');
            $beforeQuery->execute([$recharge['user_id'], $symbol]);
            $balanceBefore = (string) ($beforeQuery->fetchColumn() ?: '0');
            walletCreditSplit($pdo, (int) $recharge['user_id'], $symbol, 'BSC', $amount, $stakeable);
            $pdo->prepare('UPDATE wallet_transactions SET status="completed" WHERE id=?')->execute([$ledger['id']]);
            $pdo->prepare('UPDATE recharge_records SET status="completed",error_message=NULL WHERE id=? AND status IN ("failed","manual_review")')->execute([$recordId]);
            $pdo->prepare('INSERT INTO recharge_audit_logs (record_id,user_id,tx_hash,amount,token,status,from_address,to_address,error_message) VALUES (?,?,?,?,?,"manual_approved",?,?,?)')->execute([
                $recordId, $recharge['user_id'], $recharge['tx_hash'], $amount, $symbol,
                $recharge['wallet_address'], $recharge['deposit_address'], $reason,
            ]);
            $afterQuery = $pdo->prepare('SELECT COALESCE(balance,"0") FROM wallet_assets WHERE user_id=? AND symbol=? AND chain="BSC" LIMIT 1');
            $afterQuery->execute([$recharge['user_id'], $symbol]);
            $balanceAfter = (string) ($afterQuery->fetchColumn() ?: '0');
            adminAudit($pdo, $admin, 'recharge.manual.complete', 'recharge', $recordId, $reason,
                ['status' => (string) $recharge['status'], 'balance' => $balanceBefore],
                ['status' => 'completed', 'amount' => $amount, 'balance' => $balanceAfter, 'stakeable' => $stakeable], $requestId);
            $pdo->commit();
            adminFlash('充值已人工审核通过并到账，钱包流水与审计记录已更新。');
            adminRedirect('recharge_review');
        }

        if ($action === 'recharge.review.reject') {
            $recordId = max(0, (int) ($_POST['record_id'] ?? 0));
            $reason = adminPostString('reason', 500);
            $requestId = (string) ($_POST['request_id'] ?? '');
            if ($recordId <= 0 || $reason === '' || !preg_match('/^[a-f0-9]{32}$/', $requestId)) {
                throw new InvalidArgumentException('充值记录、拒绝原因或请求编号无效。');
            }
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT * FROM recharge_records WHERE id=? FOR UPDATE');
            $lookup->execute([$recordId]);
            $recharge = $lookup->fetch();
            if (!$recharge || (string) $recharge['status'] !== 'manual_review') {
                throw new InvalidArgumentException('仅可拒绝「待复核」状态的充值。');
            }
            $pdo->prepare('UPDATE recharge_records SET status="rejected",error_message=? WHERE id=? AND status="manual_review"')->execute([$reason, $recordId]);
            $pdo->prepare('UPDATE wallet_transactions SET status="rejected" WHERE tx_hash=? AND user_id=? AND type="deposit" AND status IN ("manual_review","pending")')->execute([$recharge['tx_hash'], $recharge['user_id']]);
            $pdo->prepare('INSERT INTO recharge_audit_logs (record_id,user_id,tx_hash,amount,token,status,from_address,to_address,error_message) VALUES (?,?,?,?,?,"manual_rejected",?,?,?)')->execute([
                $recordId, $recharge['user_id'], $recharge['tx_hash'], $recharge['amount'],
                strtoupper((string) $recharge['currency']), $recharge['wallet_address'], $recharge['deposit_address'], $reason,
            ]);
            adminAudit($pdo, $admin, 'recharge.review.reject', 'recharge', $recordId, $reason,
                ['status' => 'manual_review'], ['status' => 'rejected'], $requestId);
            $pdo->commit();
            adminFlash('该笔充值已拒绝。');
            adminRedirect('recharge_review');
        }

        if ($action === 'settings.fiss_wallet.save') {
            $deposit = trim((string) adminPostString('fiss_deposit_address', 42));
            $target = trim((string) adminPostString('burn_address', 42));
            $enabled = adminPostString('burn_sweep_enabled', 4) === '1' ? '1' : '0';
            $minAmount = trim((string) adminPostString('burn_min_amount', 40));
            if ($deposit !== '' && !preg_match('/^0x[0-9a-fA-F]{40}$/', $deposit)) {
                throw new InvalidArgumentException('FISS 充值钱包地址格式不正确（0x + 40 位十六进制）。');
            }
            if ($target !== '' && !preg_match('/^0x[0-9a-fA-F]{40}$/', $target)) {
                throw new InvalidArgumentException('销毁目标地址格式不正确（0x + 40 位十六进制）。');
            }
            if ($minAmount === '') $minAmount = '10000';
            if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $minAmount)) {
                throw new InvalidArgumentException('最小销毁数量必须是非负数字。');
            }
            $upsert = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
            $before = [];
            foreach (['fiss_deposit_address', 'burn_sweep_enabled', 'burn_address', 'burn_min_amount'] as $key) {
                $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
                $q->execute([$key]);
                $before[$key] = (string) ($q->fetchColumn() ?: '');
            }
            $upsert->execute(['fiss_deposit_address', $deposit]);
            $upsert->execute(['burn_sweep_enabled', $enabled]);
            $upsert->execute(['burn_address', $target !== '' ? $target : '0x000000000000000000000000000000000000dEaD']);
            $upsert->execute(['burn_min_amount', $minAmount]);
            adminAudit($pdo, $admin, 'settings.fiss_wallet.save', 'setting', null, '修改 FISS 充值钱包与自动销毁', $before,
                ['fiss_deposit_address' => $deposit, 'burn_sweep_enabled' => $enabled, 'burn_address' => $target, 'burn_min_amount' => $minAmount]);
            adminFlash('FISS 充值钱包与自动销毁设置已保存。');
            adminRedirect('staking');
        }

        if ($action === 'burn.tiers.save') {
            $mode = ($_POST['burn_mode'] ?? 'auto') === 'manual' ? 'manual' : 'auto';
            $manual = adminPostString('burn_manual_threshold', 40) ?: '0';
            $baseline = adminPostString('burn_baseline', 40) ?: '0';
            $thresholds = $_POST['tier_threshold'] ?? [];
            $mins = $_POST['tier_min'] ?? [];
            $statuses = $_POST['tier_status'] ?? [];
            foreach (['手动销毁量' => $manual, '基准线' => $baseline] as $label => $value) {
                if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $value)) {
                    throw new InvalidArgumentException($label . ' 必须是非负数字。');
                }
            }
            $rows = [];
            foreach ((array) $thresholds as $i => $threshold) {
                $threshold = trim((string) $threshold);
                $minStake = trim((string) ($mins[$i] ?? ''));
                if ($threshold === '' || $minStake === '') continue;
                if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $threshold)
                    || !preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $minStake)) {
                    throw new InvalidArgumentException('档位的销毁阈值与起押金额必须是非负数字。');
                }
                $rows[] = [$threshold, $minStake, (int) ($statuses[$i] ?? 0) === 1 ? 1 : 0];
            }
            if (!$rows) throw new InvalidArgumentException('至少保留一个销毁档位。');

            $pdo->beginTransaction();
            $upsert = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
            $upsert->execute(['burn_tier_mode', $mode]);
            $upsert->execute(['burn_tier_manual_threshold', $manual]);
            $upsert->execute(['burn_baseline', $baseline]);
            $beforeTiers = $pdo->query('SELECT threshold_amount,min_stake_amount,status FROM staking_burn_tiers WHERE symbol="FISS" ORDER BY threshold_amount')->fetchAll();
            $pdo->exec('DELETE FROM staking_burn_tiers WHERE symbol="FISS"');
            $insert = $pdo->prepare('INSERT INTO staking_burn_tiers (symbol,threshold_amount,min_stake_amount,status) VALUES ("FISS",?,?,?)');
            foreach ($rows as $row) $insert->execute($row);
            $afterTiers = $pdo->query('SELECT threshold_amount,min_stake_amount,status FROM staking_burn_tiers WHERE symbol="FISS" ORDER BY threshold_amount')->fetchAll();
            adminAudit($pdo, $admin, 'burn.tiers.save', 'staking_burn_tiers', null, '维护 FISS 销毁档位',
                ['mode' => 'before', 'tiers' => $beforeTiers],
                ['mode' => $mode, 'manual' => $manual, 'baseline' => $baseline, 'tiers' => $afterTiers]);
            $pdo->commit();
            if (function_exists('cacheForget')) cacheForget('staking:plans:SPX001', 'staking:plans:FISS');
            adminFlash('销毁档位已保存，新质押将按当前档位校验起押金额。');
            adminRedirect('staking');
        }

        if ($action === 'withdraw.process') {
            $transactionId = max(0, (int) ($_POST['transaction_id'] ?? 0));
            $decision = (string) ($_POST['decision'] ?? '');
            $reason = adminPostString('reason', 500);
            $txHash = strtolower(adminPostString('tx_hash', 120));
            if ($transactionId <= 0 || !in_array($decision, ['approve', 'reject'], true) || $reason === '') throw new InvalidArgumentException('提现操作参数无效，必须填写处理原因。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT * FROM wallet_transactions WHERE id=? AND type="withdraw" AND status="pending" FOR UPDATE');
            $lookup->execute([$transactionId]);
            $withdrawal = $lookup->fetch();
            if (!$withdrawal) { $pdo->rollBack(); throw new InvalidArgumentException('提现不存在或已处理。'); }
            if ($decision === 'approve') {
                $pdo->prepare('UPDATE wallet_transactions SET status="processing",tx_hash=NULL WHERE id=?')->execute([$transactionId]);
                $after = ['status' => 'processing', 'automatic_withdrawal' => true];
            } else {
                $pdo->prepare('UPDATE wallet_transactions SET status="rejected" WHERE id=?')->execute([$transactionId]);
                $refundQuery = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))+CAST(? AS DECIMAL(36,18))');
                $refundQuery->execute([$withdrawal['amount'], $withdrawal['fee_amount'] ?? '0']);
                $refundAmount = (string) $refundQuery->fetchColumn();
                walletCreditSplit($pdo, (int) $withdrawal['user_id'], (string) $withdrawal['symbol'], (string) $withdrawal['chain'], $refundAmount, (string) ($withdrawal['stakeable_amount'] ?? '0'));
                $pdo->prepare('INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,stakeable_amount,address,status) VALUES (?,?,?,"withdraw_refund",?,?,"ADMIN", "completed")')->execute([$withdrawal['user_id'], $withdrawal['symbol'], $withdrawal['chain'], $refundAmount, (string) ($withdrawal['stakeable_amount'] ?? '0')]);
                $after = ['status' => 'rejected', 'refunded' => $refundAmount];
            }
            adminAudit($pdo, $admin, 'withdraw.' . $decision, 'wallet_transaction', $transactionId, $reason, ['status' => 'pending', 'amount' => $withdrawal['amount']], $after);
            $pdo->commit();
            adminFlash($decision === 'approve' ? '提现已通过并登记交易哈希。' : '提现已拒绝，余额已退回。');
            adminRedirect('withdrawals');
        }

        if ($action === 'withdraw.batch_approve') {
            $rawIds = $_POST['transaction_ids'] ?? [];
            $reason = adminPostString('reason', 500);
            if (!is_array($rawIds) || $reason === '') throw new InvalidArgumentException('请选择待处理提现，并填写审核原因。');
            $transactionIds = array_values(array_unique(array_filter(array_map('intval', $rawIds), static fn(int $id): bool => $id > 0)));
            if (!$transactionIds || count($transactionIds) > 100) throw new InvalidArgumentException('请选择 1 至 100 笔待处理提现。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,user_id,symbol,amount,status FROM wallet_transactions WHERE id=? AND type="withdraw" FOR UPDATE');
            $approve = $pdo->prepare('UPDATE wallet_transactions SET status="processing",tx_hash=NULL,error_message=NULL WHERE id=? AND type="withdraw" AND status="pending"');
            $approved = 0;
            foreach ($transactionIds as $transactionId) {
                $lookup->execute([$transactionId]);
                $withdrawal = $lookup->fetch();
                if (!$withdrawal || $withdrawal['status'] !== 'pending') continue;
                $approve->execute([$transactionId]);
                if ($approve->rowCount() !== 1) continue;
                $approved++;
                adminAudit($pdo, $admin, 'withdraw.batch_approve', 'wallet_transaction', $transactionId, $reason,
                    ['status' => 'pending', 'amount' => $withdrawal['amount'], 'symbol' => $withdrawal['symbol']],
                    ['status' => 'processing', 'automatic_withdrawal' => true]);
            }
            $pdo->commit();
            if ($approved === 0) throw new InvalidArgumentException('所选提现已不处于待处理状态，请刷新后重试。');
            adminFlash('已批量通过 ' . $approved . ' 笔提现，自动打币服务将逐笔处理。');
            adminRedirect('withdrawals');
        }

        if ($action === 'withdraw.retry') {
            $transactionId = max(0, (int) ($_POST['transaction_id'] ?? 0));
            $reason = adminPostString('reason', 500);
            if ($transactionId <= 0 || $reason === '') throw new InvalidArgumentException('提现记录和重试原因不能为空。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,user_id,symbol,amount,status,tx_hash,error_message FROM wallet_transactions WHERE id=? AND type="withdraw" AND status="failed" FOR UPDATE');
            $lookup->execute([$transactionId]);
            $withdrawal = $lookup->fetch();
            if (!$withdrawal) { $pdo->rollBack(); throw new InvalidArgumentException('提现不存在或当前不是失败状态。'); }
            if (mb_strpos((string) ($withdrawal['error_message'] ?? ''), '已自动退款') !== false) {
                $pdo->rollBack();
                throw new InvalidArgumentException('该笔提现失败时已自动退款，重试会造成重复付款。');
            }
            $pdo->prepare('UPDATE wallet_transactions SET status="processing",error_message=NULL WHERE id=? AND type="withdraw" AND status="failed"')->execute([$transactionId]);
            adminAudit($pdo, $admin, 'withdraw.retry', 'wallet_transaction', $transactionId, $reason,
                ['status' => 'failed', 'error_message' => $withdrawal['error_message']],
                ['status' => 'processing', 'automatic_withdrawal' => true, 'retry' => true]);
            $pdo->commit();
            adminFlash('提现已重新加入自动打币队列。');
            adminRedirect('withdrawals');
        }

        if ($action === 'order.process') {
            $orderId = max(0, (int) ($_POST['order_id'] ?? 0));
            $decision = (string) ($_POST['decision'] ?? '');
            $reason = adminPostString('reason', 500);
            if ($orderId <= 0 || !in_array($decision, ['complete', 'cancel'], true) || $reason === '') throw new InvalidArgumentException('订单操作参数无效，必须填写处理原因。');
            $pdo->beginTransaction();
            $owner=$pdo->prepare("SELECT user_id FROM orders WHERE id=?");$owner->execute([$orderId]);$ownerId=(int)$owner->fetchColumn();
            if($ownerId){$buyerLock=$pdo->prepare("SELECT id FROM users WHERE id=? FOR UPDATE");$buyerLock->execute([$ownerId]);}
            $lookup = $pdo->prepare('SELECT * FROM orders WHERE id=? AND status="pending" FOR UPDATE');
            $lookup->execute([$orderId]);
            $order = $lookup->fetch();
            if (!$order) { $pdo->rollBack(); throw new InvalidArgumentException('订单不存在或已处理。'); }
            if (!empty($order['merchant_id'])) throw new InvalidArgumentException('商家订单请在商家商城模块处理，以保护结算与售后状态。');
            if ($decision === 'complete') {
                if (($order['order_type'] ?? 'digital') === 'physical') throw new InvalidArgumentException('实物订单请填写物流信息后执行发货。');
                $pdo->prepare('UPDATE orders SET status="delivered",received_at=NOW() WHERE id=?')->execute([$orderId]);
                mallEvent($pdo,$orderId,"delivered","平台已完成商品交付，进入售后期");
                $after = ['status' => 'delivered'];
            } else {
                mallRefundOrder($pdo,$order);
                $after = ['status' => 'refunded', 'refunded_spx' => $order['total_spx'], 'restocked_quantity' => $order['quantity']];
            }
            adminAudit($pdo, $admin, 'order.' . $decision, 'order', $orderId, $reason, ['status' => 'pending', 'total_spx' => $order['total_spx']], $after);
            $pdo->commit();
            adminFlash($decision === 'complete' ? '订单已标记完成。' : '订单已取消，余额已退回且库存已恢复。');
            adminRedirect('orders');
        }

        if ($action === 'order.ship') {
            $orderId = max(0, (int) ($_POST['order_id'] ?? 0));
            $carrier = adminPostString('carrier', 100);
            $trackingNumber = adminPostString('tracking_number', 120);
            $reason = adminPostString('reason', 500);
            if ($orderId <= 0 || $carrier === '' || $trackingNumber === '' || $reason === '') throw new InvalidArgumentException('订单、承运商、物流单号和操作原因均为必填。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,user_id,merchant_id,after_sale_status,order_type,status,carrier,tracking_number FROM orders WHERE id=? FOR UPDATE');
            $lookup->execute([$orderId]);
            $order = $lookup->fetch();
            if (!$order || $order['order_type'] !== 'physical' || $order['status'] !== 'pending' || in_array($order['after_sale_status'],['requested','return_approved','return_shipped'],true)) throw new InvalidArgumentException('仅待发货的实物订单可以登记发货。');
            $pdo->prepare('UPDATE orders SET status="shipped",carrier=?,tracking_number=?,shipped_at=NOW() WHERE id=?')->execute([$carrier, $trackingNumber, $orderId]);
            mallEvent($pdo,$orderId,'shipped','平台已登记发货物流');
            adminAudit($pdo, $admin, 'order.ship', 'order', $orderId, $reason, ['status' => $order['status']], ['status' => 'shipped', 'carrier' => $carrier, 'tracking_number' => $trackingNumber]);
            $pdo->commit();
            adminFlash('已登记物流信息，用户可在订单详情查看。');
            adminRedirect('orders');
        }

        if ($action === 'admin.save') {
            $adminId = max(0, (int) ($_POST['admin_id'] ?? 0));
            $username = adminPostString('username', 64);
            $role = (string) ($_POST['role'] ?? '');
            $password = (string) ($_POST['new_password'] ?? '');
            if (!preg_match('/^[A-Za-z0-9_.-]{3,64}$/', $username) || !in_array($role, ['superadmin', 'finance', 'support', 'operator'], true)) throw new InvalidArgumentException('管理员账号或角色无效。');
            if ($adminId === 0) {
                if (strlen($password) < 12) throw new InvalidArgumentException('新管理员密码至少 12 位。');
                $pdo->beginTransaction();
                $pdo->prepare('INSERT INTO admin_users (username,password_hash,role) VALUES (?,?,?)')->execute([$username, password_hash($password, PASSWORD_DEFAULT), $role]);
                $adminId = (int) $pdo->lastInsertId();
                adminAudit($pdo, $admin, 'admin.create', 'admin', $adminId, '创建管理员账号', null, ['username' => $username, 'role' => $role]);
                $pdo->commit();
            } else {
                if ($adminId === (int) $admin['id'] && $role !== 'superadmin') throw new InvalidArgumentException('不能降低当前管理员自己的最高权限。');
                if ($adminId === (int) $admin['id'] && $password !== '') throw new InvalidArgumentException('请在“账号安全”页面通过当前密码修改自己的密码。');
                $pdo->beginTransaction();
                $beforeQuery = $pdo->prepare('SELECT username,role,status FROM admin_users WHERE id=?');
                $beforeQuery->execute([$adminId]);
                $before = $beforeQuery->fetch();
                if (!$before) throw new InvalidArgumentException('管理员账号不存在。');
                if ((int) $before['status'] === 1 && $before['role'] === 'superadmin' && $role !== 'superadmin') {
                    $activeSuperadmins = (int) $pdo->query('SELECT COUNT(*) FROM admin_users WHERE role="superadmin" AND status=1')->fetchColumn();
                    if ($activeSuperadmins <= 1) throw new InvalidArgumentException('系统至少需要保留一名启用的超级管理员。');
                }
                $pdo->prepare('UPDATE admin_users SET username=?,role=? WHERE id=?')->execute([$username, $role, $adminId]);
                if ($password !== '') {
                    if (strlen($password) < 12) throw new InvalidArgumentException('新密码至少 12 位。');
                    $pdo->prepare('UPDATE admin_users SET password_hash=? WHERE id=?')->execute([password_hash($password, PASSWORD_DEFAULT), $adminId]);
                }
                adminAudit($pdo, $admin, 'admin.update', 'admin', $adminId, '管理员账号和角色更新', $before, ['username' => $username, 'role' => $role]);
                $pdo->commit();
            }
            adminFlash('管理员账号已保存。');
            adminRedirect('admins');
        }

        if ($action === 'admin.toggle') {
            $targetId = max(0, (int) ($_POST['admin_id'] ?? 0));
            if ($targetId <= 0 || $targetId === (int) $admin['id']) throw new InvalidArgumentException('不能停用当前登录账号。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT id,username,role,status FROM admin_users WHERE id=? FOR UPDATE');
            $lookup->execute([$targetId]);
            $target = $lookup->fetch();
            if (!$target) { $pdo->rollBack(); throw new InvalidArgumentException('管理员账号不存在。'); }
            $newStatus = (int) $target['status'] === 1 ? 0 : 1;
            if ($newStatus === 0 && $target['role'] === 'superadmin') {
                $activeSuperadmins = (int) $pdo->query('SELECT COUNT(*) FROM admin_users WHERE role="superadmin" AND status=1')->fetchColumn();
                if ($activeSuperadmins <= 1) { $pdo->rollBack(); throw new InvalidArgumentException('系统至少需要保留一名启用的超级管理员。'); }
            }
            $pdo->prepare('UPDATE admin_users SET status=? WHERE id=?')->execute([$newStatus, $targetId]);
            adminAudit($pdo, $admin, 'admin.toggle', 'admin', $targetId, '管理员启用状态变更', $target, ['status' => $newStatus]);
            $pdo->commit();
            adminFlash($newStatus === 1 ? '管理员账号已启用。' : '管理员账号已停用。');
            adminRedirect('admins');
        }

        if ($action === 'question.save') {
            $questionId = max(0, (int) ($_POST['question_id'] ?? 0));
            $answer = adminPostString('answer', 10000);
            $status = (string) ($_POST['status'] ?? 'answered');
            if ($questionId <= 0 || $answer === '' || !in_array($status, ['pending', 'answered', 'closed'], true)) throw new InvalidArgumentException('问题回复或状态无效。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT status,answer FROM user_questions WHERE id=? FOR UPDATE');
            $lookup->execute([$questionId]);
            $before = $lookup->fetch();
            if (!$before) throw new InvalidArgumentException('用户问题不存在。');
            $pdo->prepare('UPDATE user_questions SET answer=?,status=? WHERE id=?')->execute([$answer, $status, $questionId]);
            adminAudit($pdo, $admin, 'question.reply', 'question', $questionId, '回复用户问题', $before, ['answer' => $answer, 'status' => $status]);
            $pdo->commit();
            adminFlash('问题回复已保存。');
            adminRedirect('questions');
        }

        if ($action === 'feedback.save') {
            $feedbackId = max(0, (int) ($_POST['feedback_id'] ?? 0));
            $status = (string) ($_POST['status'] ?? 'pending');
            if ($feedbackId <= 0 || !in_array($status, ['pending', 'reviewed', 'resolved'], true)) throw new InvalidArgumentException('反馈状态无效。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT status FROM feedback WHERE id=? FOR UPDATE');
            $lookup->execute([$feedbackId]);
            $before = $lookup->fetch();
            if (!$before) throw new InvalidArgumentException('反馈记录不存在。');
            $pdo->prepare('UPDATE feedback SET status=? WHERE id=?')->execute([$status, $feedbackId]);
            adminAudit($pdo, $admin, 'feedback.update', 'feedback', $feedbackId, '更新反馈处理状态', $before, ['status' => $status]);
            $pdo->commit();
            adminFlash('反馈状态已更新。');
            adminRedirect('feedback');
        }

        if ($action === 'faq.save') {
            $faqId = max(0, (int) ($_POST['faq_id'] ?? 0));
            $category = adminPostString('category', 50);
            $question = adminPostString('question', 255);
            $answer = adminPostString('answer', 10000);
            $sortOrder = filter_var($_POST['sort_order'] ?? 0, FILTER_VALIDATE_INT);
            $status = (int) ($_POST['status'] ?? 0) === 1 ? 1 : 0;
            if ($category === '' || $question === '' || $answer === '' || $sortOrder === false) throw new InvalidArgumentException('FAQ 内容不能为空，排序值无效。');
            $pdo->beginTransaction();
            if ($faqId > 0) {
                $lookup = $pdo->prepare('SELECT category,question,answer,status,sort_order FROM faq_items WHERE id=? FOR UPDATE');
                $lookup->execute([$faqId]);
                $before = $lookup->fetch();
                if (!$before) { $pdo->rollBack(); throw new InvalidArgumentException('FAQ 不存在。'); }
                $pdo->prepare('UPDATE faq_items SET category=?,question=?,answer=?,status=?,sort_order=? WHERE id=?')->execute([$category, $question, $answer, $status, $sortOrder, $faqId]);
            } else {
                $pdo->prepare('INSERT INTO faq_items (category,question,answer,status,sort_order) VALUES (?,?,?,?,?)')->execute([$category, $question, $answer, $status, $sortOrder]);
                $faqId = (int) $pdo->lastInsertId();
                $before = null;
            }
            adminAudit($pdo, $admin, 'faq.save', 'faq', $faqId, '维护常见问题', $before, compact('category', 'question', 'answer', 'status', 'sortOrder'));
            $pdo->commit();
            adminFlash('FAQ 已保存。');
            adminRedirect('faqs');
        }

        if ($action === 'group.save') {
            $groupId = max(0, (int) ($_POST['group_id'] ?? 0));
            $name = adminPostString('name', 100);
            $announcement = adminPostString('announcement', 5000);
            if ($groupId <= 0 || $name === '') throw new InvalidArgumentException('群组名称不能为空。');
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT name,announcement FROM chat_groups WHERE id=? FOR UPDATE');
            $lookup->execute([$groupId]);
            $before = $lookup->fetch();
            if (!$before) throw new InvalidArgumentException('群组不存在。');
            $pdo->prepare('UPDATE chat_groups SET announcement_version=announcement_version+CASE WHEN COALESCE(announcement,"")<>COALESCE(?,"") THEN 1 ELSE 0 END,name=?,announcement=? WHERE id=?')->execute([$announcement, $name, $announcement ?: null, $groupId]);
            adminAudit($pdo, $admin, 'group.update', 'group', $groupId, '更新群组资料', $before, ['name' => $name, 'announcement' => $announcement]);
            $pdo->commit();
            adminFlash('群组信息已更新。');
            adminRedirect('groups');
        }

        if ($action === 'group.member') {
            $groupId = max(0, (int) ($_POST['group_id'] ?? 0));
            $memberUserId = max(0, (int) ($_POST['user_id'] ?? 0));
            $operation = (string) ($_POST['operation'] ?? '');
            $reason = adminPostString('reason', 500);
            if ($groupId <= 0 || $memberUserId <= 0 || !in_array($operation, ['mute', 'unmute', 'remove'], true) || $reason === '') {
                throw new InvalidArgumentException('群成员操作参数无效，必须填写原因。');
            }
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT gm.id,gm.role,gm.mute,u.username FROM chat_group_members gm JOIN users u ON u.id=gm.user_id WHERE gm.group_id=? AND gm.user_id=? FOR UPDATE');
            $lookup->execute([$groupId, $memberUserId]);
            $member = $lookup->fetch();
            if (!$member) { $pdo->rollBack(); throw new InvalidArgumentException('群成员不存在。'); }
            if ($member['role'] === 'owner') { $pdo->rollBack(); throw new InvalidArgumentException('不能通过此操作移除或禁言群主。'); }
            if ($operation === 'remove') {
                $pdo->prepare('DELETE FROM chat_group_members WHERE id=?')->execute([$member['id']]);
                $after = ['removed' => true];
            } else {
                $mute = $operation === 'mute' ? 1 : 0;
                $pdo->prepare('UPDATE chat_group_members SET mute=? WHERE id=?')->execute([$mute, $member['id']]);
                $after = ['mute' => $mute];
            }
            adminAudit($pdo, $admin, 'group.member.' . $operation, 'group_member', (int) $member['id'], $reason, ['group_id' => $groupId, 'user_id' => $memberUserId, 'role' => $member['role'], 'mute' => $member['mute']], $after);
            $pdo->commit();
            adminFlash($operation === 'remove' ? '成员已移出群组。' : ($operation === 'mute' ? '成员已禁言。' : '成员已解除禁言。'));
            header('Location: admin.php?section=groups&group_id=' . $groupId, true, 303);
            exit;
        }

        if ($action === 'staking.phase.save') {
            $phaseNo = filter_var($_POST['phase_no'] ?? null, FILTER_VALIDATE_INT);
            $phaseSymbol = strtoupper(adminPostString('symbol', 20));
            if (!in_array($phaseSymbol, ['SPX001', 'FISS'], true)) throw new InvalidArgumentException('质押期币种无效。');
            $name = adminPostString('name', 100);
            $quota = adminPostString('total_quota', 40);
            $active = (int) ($_POST['status'] ?? 0) === 1 ? 1 : 0;
            if ($phaseNo === false || $phaseNo < 1 || $phaseNo > 1000000 || $name === '' || !preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $quota) || !preg_match('/[1-9]/', $quota)) {
                throw new InvalidArgumentException('质押期数、名称或总额度无效。');
            }
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT * FROM staking_phases WHERE symbol=? AND phase_no=? FOR UPDATE');
            $lookup->execute([$phaseSymbol, $phaseNo]);
            $before = $lookup->fetch() ?: null;
            if ($before) {
                $quotaCheck = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))>=CAST(? AS DECIMAL(36,18))');
                $quotaCheck->execute([$quota, $before['reserved_quota']]);
                if ((int) $quotaCheck->fetchColumn() !== 1) { $pdo->rollBack(); throw new InvalidArgumentException('总额度不能低于已经预分配额度。'); }
            }
            if ($active === 1) $pdo->prepare('UPDATE staking_phases SET status=0,ended_at=IF(status=1,NOW(),ended_at) WHERE status=1 AND symbol=? AND phase_no<>?')->execute([$phaseSymbol, $phaseNo]);
            if ($before) {
                $pdo->prepare('UPDATE staking_phases SET name=?,total_quota=?,status=?,started_at=IF(?=1,COALESCE(started_at,NOW()),started_at),ended_at=IF(?=1,NULL,NOW()) WHERE id=?')->execute([$name, $quota, $active, $active, $active, $before['id']]);
                $phaseId = (int) $before['id'];
            } else {
                $pdo->prepare('INSERT INTO staking_phases (phase_no,symbol,name,total_quota,status,started_at) VALUES (?,?,?,?,?,IF(?=1,NOW(),NULL))')->execute([$phaseNo, $phaseSymbol, $name, $quota, $active, $active]);
                $phaseId = (int) $pdo->lastInsertId();
            }
            $afterQuery = $pdo->prepare('SELECT * FROM staking_phases WHERE id=?');
            $afterQuery->execute([$phaseId]);
            $after = $afterQuery->fetch() ?: [];
            adminAudit($pdo, $admin, 'staking.phase.save', 'staking_phase', $phaseId, '维护质押奖励额度和期数', $before, $after);
            $pdo->commit();
            if (function_exists('cacheForget')) cacheForget('staking:plans:SPX001', 'staking:plans:FISS');
            adminFlash($active === 1 ? '质押期已开启，新的质押将按该期额度预分配。' : '质押期配置已保存但未开启。');
            adminRedirect('staking');
        }

        if ($action === 'staking.plans.save') {
            $submitted = $_POST['plans'] ?? [];
            if (!is_array($submitted) || !$submitted) throw new InvalidArgumentException('至少保留一个质押方案。');
            $plans = [];
            $seenKeys = [];
            foreach (array_values($submitted) as $index => $input) {
                if (!is_array($input)) continue;
                if (trim((string) ($input['name'] ?? '')) === '' && trim((string) ($input['plan_days'] ?? '')) === '' && trim((string) ($input['apy'] ?? '')) === '' && trim((string) ($input['minimum_amount'] ?? '')) === '') continue;
                $id = max(0, (int) ($input['id'] ?? 0));
                $symbol = strtoupper(trim((string) ($input['symbol'] ?? 'SPX001')));
                $name = trim((string) ($input['name'] ?? ''));
                $days = filter_var($input['plan_days'] ?? null, FILTER_VALIDATE_INT);
                $apy = trim((string) ($input['apy'] ?? ''));
                $minimum = trim((string) ($input['minimum_amount'] ?? ''));
                $active = (int) ($input['status'] ?? 0) === 1 ? 1 : 0;
                // 未填步长/单户上限时按币种给默认值：SPX001 保持原 10 万整数倍 + 1000 万上限，FISS 不限步长
                $step = trim((string) ($input['step_amount'] ?? ''));
                $maxPerUser = trim((string) ($input['max_per_user'] ?? ''));
                // 表单没带这两项时（老页面或接口调用）不能把已有配置冲掉：先取原值，再退回币种默认
                if ($step === '' && $id > 0) {
                    $existingStep = $pdo->query('SELECT step_amount FROM staking_plans WHERE id=' . $id)->fetchColumn();
                    if ($existingStep !== false && $existingStep !== null) $step = (string) $existingStep;
                }
                if ($step === '') $step = $symbol === 'SPX001' ? '100000' : '0';
                if ($maxPerUser === '' && $id > 0) {
                    $existingMax = $pdo->query('SELECT max_per_user FROM staking_plans WHERE id=' . $id)->fetchColumn();
                    if ($existingMax !== false && $existingMax !== null) $maxPerUser = (string) $existingMax;
                }
                if ($maxPerUser === '' && $symbol === 'SPX001') $maxPerUser = '10000000';
                $uniqueKey = $symbol . '-' . (string) $days;
                if ($name === '' || strlen($name) > 100 || !in_array($symbol, ['SPX001', 'FISS'], true)
                    || $days === false || $days < 1 || $days > 3650 || isset($seenKeys[$uniqueKey])
                    || !preg_match('/^(?:0|[1-9][0-9]{0,2})(?:\.[0-9]{1,4})?$/', $apy)
                    || !preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $minimum) || !preg_match('/[1-9]/', $minimum)
                    || !preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $step)
                    || ($maxPerUser !== '' && !preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $maxPerUser))) {
                    throw new InvalidArgumentException('质押方案名称、币种、锁仓天数、年化利率或金额无效（同币种下锁仓天数不能重复）。');
                }
                $seenKeys[$uniqueKey] = true;
                $plans[] = compact('id', 'name', 'symbol', 'days', 'apy', 'minimum', 'step', 'maxPerUser', 'active', 'index');
            }
            if (!$plans || !array_filter($plans, static fn(array $plan): bool => $plan['active'] === 1)) throw new InvalidArgumentException('至少保留一个资料完整且启用的质押方案。');
            $pdo->beginTransaction();
            $before = $pdo->query('SELECT id,name,plan_days,apy,minimum_amount,status FROM staking_plans ORDER BY sort_order,id FOR UPDATE')->fetchAll();
            $pdo->exec('UPDATE staking_plans SET status=0');
            foreach ($plans as $plan) {
                $maxPerUser = $plan['maxPerUser'] === '' ? null : $plan['maxPerUser'];
                if ($plan['id'] > 0) {
                    $save = $pdo->prepare('UPDATE staking_plans SET name=?,symbol=?,plan_days=?,apy=?,minimum_amount=?,step_amount=?,max_per_user=?,status=?,sort_order=? WHERE id=?');
                    $save->execute([$plan['name'], $plan['symbol'], $plan['days'], $plan['apy'], $plan['minimum'], $plan['step'], $maxPerUser, $plan['active'], ($plan['index'] + 1) * 10, $plan['id']]);
                    if ($save->rowCount() === 0) {
                        $exists = $pdo->prepare('SELECT id FROM staking_plans WHERE id=?'); $exists->execute([$plan['id']]);
                        if (!$exists->fetchColumn()) throw new InvalidArgumentException('质押方案已不存在，请刷新页面后重试。');
                    }
                } else {
                    $pdo->prepare('INSERT INTO staking_plans (name,symbol,plan_days,apy,minimum_amount,step_amount,max_per_user,status,sort_order) VALUES (?,?,?,?,?,?,?,?,?)')->execute([$plan['name'], $plan['symbol'], $plan['days'], $plan['apy'], $plan['minimum'], $plan['step'], $maxPerUser, $plan['active'], ($plan['index'] + 1) * 10]);
                }
            }
            $after = $pdo->query('SELECT id,name,plan_days,apy,minimum_amount,status FROM staking_plans ORDER BY sort_order,id')->fetchAll();
            adminAudit($pdo, $admin, 'staking.plans.save', 'staking_plans', null, '维护动态质押方案', $before, $after);
            $pdo->commit();
            if (function_exists('cacheForget')) cacheForget('staking:plans:SPX001', 'staking:plans:FISS');
            adminFlash('质押方案已更新，App 展示和服务端最低金额校验已同步。');
            adminRedirect('staking');
        }

        if ($action === 'product.save') {
            $id = max(0, (int) ($_POST['id'] ?? 0));
            $name = adminPostString('name', 150);
            $description = adminPostString('description', 10000);
            $imageUrl = adminPostString('image_url', 500);
            $price = adminPostString('spx_price', 32);
            $stock = filter_var($_POST['stock'] ?? null, FILTER_VALIDATE_INT);
            $sortOrder = filter_var($_POST['sort_order'] ?? 0, FILTER_VALIDATE_INT);
            $status = (int) ($_POST['status'] ?? 0) === 1 ? 1 : 0;
            $productType = (string) ($_POST['product_type'] ?? 'digital');
            $variantText = str_replace("\r", '', adminPostString('variants', 5000));
            if (!in_array($productType, ['digital', 'physical'], true)) throw new InvalidArgumentException('商品类型无效。');
            $variants = [];
            $lineStock = 0;
            $seenVariantNames = [];
            foreach (array_filter(explode("\n", $variantText), static fn(string $line): bool => trim($line) !== '') as $lineIndex => $line) {
                $parts = array_map('trim', explode('|', $line));
                if (count($parts) !== 3 || $parts[0] === '' || strlen($parts[0]) > 150 || isset($seenVariantNames[$parts[0]]) || !preg_match('/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,8})?$/', $parts[1]) || !preg_match('/^(?:0|[1-9][0-9]{0,8})$/', $parts[2])) {
                    throw new InvalidArgumentException('规格请按“规格名称|SPX价格|库存”逐行填写，例如：红色 M|120|8。');
                }
                $seenVariantNames[$parts[0]] = true;
                $variants[] = ['id' => 'v' . bin2hex(random_bytes(8)), 'name' => $parts[0], 'price' => $parts[1], 'stock' => (int) $parts[2]];
                $lineStock += (int) $parts[2];
                if (count($variants) > 30) throw new InvalidArgumentException('每个商品最多设置 30 个规格。');
            }
            if ($variants) {
                $price = (string) $variants[0]['price'];
                $stock = $lineStock;
            }
            if ($name === '' || !preg_match('/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,8})?$/', $price) || $stock === false || $stock < 0 || $sortOrder === false) {
                throw new InvalidArgumentException('商品名称、价格、库存或排序值无效。');
            }
            if ($imageUrl !== '' && !filter_var($imageUrl, FILTER_VALIDATE_URL)) {
                throw new InvalidArgumentException('商品图片地址格式无效。');
            }
            $newProductImages = adminStoreProductImages();
            $removeIds = array_values(array_unique(array_filter(array_map('intval', (array) ($_POST['remove_image_ids'] ?? [])), static fn(int $value): bool => $value > 0)));
            if (count($newProductImages) > 8 || count($removeIds) > 8) throw new InvalidArgumentException('图片操作数量超出限制。');
            $pdo->beginTransaction();
            $before = null;
            $existingImages = [];
            if ($id > 0) {
                $lookup = $pdo->prepare('SELECT merchant_id,name,description,image_url,spx_price,stock,status,sort_order,product_type,variants FROM products WHERE id=?');
                $lookup->execute([$id]);
                $before = $lookup->fetch();
                if (!$before) throw new InvalidArgumentException('商品不存在。');
                if (!empty($before['merchant_id'])) throw new InvalidArgumentException('商家商品请通过商家端编辑、商家商城审核模块处理。');
                $previousVariants = json_decode((string) ($before['variants'] ?? ''), true) ?: [];
                $previousVariantIds = [];
                foreach ($previousVariants as $previousVariant) {
                    if (!empty($previousVariant['name']) && !empty($previousVariant['id'])) $previousVariantIds[(string) $previousVariant['name']] = (string) $previousVariant['id'];
                }
                foreach ($variants as &$variant) $variant['id'] = $previousVariantIds[$variant['name']] ?? ('v' . bin2hex(random_bytes(8)));
                unset($variant);
                $previousPendingVariantIds = $pdo->prepare('SELECT DISTINCT variant_id FROM orders WHERE product_id=? AND status="pending" AND variant_id IS NOT NULL');
                $previousPendingVariantIds->execute([$id]);
                $activeVariantIds = $variants ? array_column($variants, 'id') : ['default'];
                foreach ($previousPendingVariantIds->fetchAll(PDO::FETCH_COLUMN) as $pendingVariantId) {
                    if (!in_array((string) $pendingVariantId, $activeVariantIds, true)) throw new InvalidArgumentException('商品存在待处理订单，不能删除或重命名其规格；请先处理相关订单。');
                }
                $imageQuery = $pdo->prepare('SELECT id,image_url,sort_order FROM product_images WHERE product_id=? ORDER BY sort_order,id FOR UPDATE');
                $imageQuery->execute([$id]);
                $existingImages = $imageQuery->fetchAll();
                $save = $pdo->prepare('UPDATE products SET name=?,description=?,spx_price=?,stock=?,status=?,sort_order=?,product_type=?,variants=? WHERE id=?');
                $save->execute([$name, $description, $price, $stock, $status, $sortOrder, $productType, $variants ? json_encode($variants, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) : null, $id]);
            } else {
                $save = $pdo->prepare('INSERT INTO products (name,description,image_url,spx_price,stock,status,sort_order,product_type,variants) VALUES (?,?,NULL,?,?,?,?,?,?)');
                $save->execute([$name, $description, $price, $stock, $status, $sortOrder, $productType, $variants ? json_encode($variants, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) : null]);
                $id = (int) $pdo->lastInsertId();
            }
            $removedIds = array_values(array_intersect(array_map(static fn(array $image): int => (int) $image['id'], $existingImages), $removeIds));
            if ($removedIds) {
                $marks = implode(',', array_fill(0, count($removedIds), '?'));
                $removedQuery = $pdo->prepare('SELECT image_url FROM product_images WHERE product_id=? AND id IN (' . $marks . ')');
                $removedQuery->execute(array_merge([$id], $removedIds));
                $removedProductImages = $removedQuery->fetchAll(PDO::FETCH_COLUMN);
                $deleteImages = $pdo->prepare('DELETE FROM product_images WHERE product_id=? AND id IN (' . $marks . ')');
                $deleteImages->execute(array_merge([$id], $removedIds));
            }
            $remainingImages = array_values(array_filter($existingImages, static fn(array $image): bool => !in_array((int) $image['id'], $removedIds, true)));
            if (count($remainingImages) + count($newProductImages) > 8) throw new InvalidArgumentException('每个商品最多保留 8 张图片，请先移除旧图片。');
            $maxSort = 0;
            foreach ($remainingImages as $image) $maxSort = max($maxSort, (int) $image['sort_order']);
            $insertImage = $pdo->prepare('INSERT INTO product_images (product_id,image_url,sort_order) VALUES (?,?,?)');
            foreach ($newProductImages as $image) {
                $insertImage->execute([$id, $image['url'], ++$maxSort]);
                $remainingImages[] = ['image_url' => $image['url'], 'sort_order' => $maxSort];
            }
            usort($remainingImages, static fn(array $a, array $b): int => (int) $a['sort_order'] <=> (int) $b['sort_order']);
            $primaryImage = $imageUrl !== '' && !in_array($imageUrl, $removedProductImages, true) ? $imageUrl : ($remainingImages[0]['image_url'] ?? null);
            if (count(array_unique(array_merge($primaryImage ? [$primaryImage] : [], array_column($remainingImages, 'image_url')))) > 8) throw new InvalidArgumentException('商品主图与图库图片合计最多 8 张。');
            $pdo->prepare('UPDATE products SET image_url=? WHERE id=?')->execute([$primaryImage, $id]);
            adminAudit($pdo, $admin, 'product.save', 'product', $id, '维护商品资料', $before, ['name' => $name, 'description' => $description, 'image_url' => $primaryImage, 'gallery_count' => count($remainingImages), 'spx_price' => $price, 'stock' => $stock, 'product_type' => $productType, 'variants' => $variants, 'status' => $status, 'sort_order' => $sortOrder]);
            $pdo->commit();
            foreach ($removedProductImages as $url) {
                if (preg_match('#^/uploads/products/[a-f0-9]{40}\.(?:jpg|png|webp|gif)$#', (string) $url)) @unlink(dirname(__DIR__) . $url);
            }
            $newProductImages = [];
            adminFlash('商品信息已保存。');
            adminRedirect('products');
        }

        if ($action === 'ticket.save') {
            $ticketId = max(0, (int) ($_POST['ticket_id'] ?? 0));
            $reply = adminPostString('admin_reply', 10000);
            $status = (string) ($_POST['status'] ?? 'open');
            if ($ticketId <= 0 || !in_array($status, ['open', 'processing', 'resolved', 'closed'], true) || $reply === '') {
                throw new InvalidArgumentException('请填写回复内容并选择有效状态。');
            }
            $pdo->beginTransaction();
            $lookup = $pdo->prepare('SELECT admin_reply,status FROM support_tickets WHERE id=? FOR UPDATE');
            $lookup->execute([$ticketId]);
            $before = $lookup->fetch();
            if (!$before) throw new InvalidArgumentException('工单不存在。');
            $save = $pdo->prepare('UPDATE support_tickets SET admin_reply=?,status=? WHERE id=?');
            $save->execute([$reply, $status, $ticketId]);
            adminAudit($pdo, $admin, 'ticket.reply', 'ticket', $ticketId, '处理客服工单', $before, ['admin_reply' => $reply, 'status' => $status]);
            $pdo->commit();
            adminFlash('工单回复已保存。');
            adminRedirect('tickets');
        }

        if ($action === 'app.release.upload') {
            $versionName = adminPostString('version_name', 32);
            $versionCode = filter_var($_POST['version_code'] ?? null, FILTER_VALIDATE_INT);
            $releaseNotes = adminPostString('release_notes', 10000);
            $upload = $_FILES['apk'] ?? null;
            if (!preg_match('/^[A-Za-z0-9][A-Za-z0-9._+-]{0,31}$/', $versionName) || $versionCode === false || $versionCode <= 0 || $releaseNotes === '') {
                throw new InvalidArgumentException('请填写有效的版本名称、递增版本代码和更新说明。');
            }
            if (!is_array($upload) || (int) ($upload['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK || empty($upload['tmp_name']) || !is_uploaded_file((string) $upload['tmp_name'])) {
                throw new InvalidArgumentException('请选择 APK 文件；上传失败时请检查服务器上传大小限制。');
            }
            $apkSize = (int) ($upload['size'] ?? 0);
            if ($apkSize < 4 || $apkSize > 500 * 1024 * 1024) throw new InvalidArgumentException('APK 文件必须大于 0 且不超过 500 MB。');
            $signature = file_get_contents((string) $upload['tmp_name'], false, null, 0, 4);
            if ($signature !== "PK\x03\x04") throw new InvalidArgumentException('文件格式不是有效的 APK/ZIP 包。');
            if (class_exists('ZipArchive')) {
                $zip = new ZipArchive();
                if ($zip->open((string) $upload['tmp_name']) !== true || $zip->locateName('AndroidManifest.xml') === false) {
                    if ($zip->status === ZipArchive::ER_OK) $zip->close();
                    throw new InvalidArgumentException('APK 包内缺少 AndroidManifest.xml。');
                }
                $zip->close();
            }
            $apkSha256 = hash_file('sha256', (string) $upload['tmp_name']);
            if (!is_string($apkSha256)) throw new RuntimeException('无法计算 APK 校验值。');
            $uploadDirectory = dirname(__DIR__) . '/uploads/app-releases';
            if (!is_dir($uploadDirectory) && !mkdir($uploadDirectory, 0755, true) && !is_dir($uploadDirectory)) throw new RuntimeException('无法创建 APK 存储目录。');
            $filename = $apkSha256 . '-' . bin2hex(random_bytes(8)) . '.apk';
            $newReleasePath = $uploadDirectory . '/' . $filename;
            if (!move_uploaded_file((string) $upload['tmp_name'], $newReleasePath)) throw new RuntimeException('APK 保存失败。');
            $pdo->beginTransaction();
            $activeQuery = $pdo->query('SELECT id,version_code,version_name FROM app_releases WHERE is_active=1 ORDER BY version_code DESC LIMIT 1 FOR UPDATE');
            $activeRelease = $activeQuery->fetch();
            if ($activeRelease && (int) $versionCode <= (int) $activeRelease['version_code']) {
                throw new InvalidArgumentException('新版本代码必须大于当前已发布版本代码 ' . (int) $activeRelease['version_code'] . '。');
            }
            $pdo->exec('UPDATE app_releases SET is_active=0 WHERE is_active=1');
            $pdo->prepare('INSERT INTO app_releases (version_code,version_name,release_notes,file_path,apk_size,apk_sha256,is_active,admin_id) VALUES (?,?,?,?,?,?,1,?)')->execute([
                $versionCode, $versionName, $releaseNotes, '/uploads/app-releases/' . $filename, $apkSize, $apkSha256, $admin['id'],
            ]);
            $releaseId = (int) $pdo->lastInsertId();
            adminAudit($pdo, $admin, 'app.release.upload', 'app_release', $releaseId, '发布 Android APK 更新',
                $activeRelease ? ['version_name' => $activeRelease['version_name'], 'version_code' => (int) $activeRelease['version_code']] : null,
                ['version_name' => $versionName, 'version_code' => (int) $versionCode, 'apk_size' => $apkSize, 'apk_sha256' => $apkSha256]);
            $pdo->commit();
            // 发布成功后再推 OSS：失败只降级为服务器直发，绝不让发版失败。
            $ossNote = '';
            try {
                require_once __DIR__ . '/../oss_release.php';
                $ossPush = ossReleasePush($newReleasePath, $versionName, (int) $versionCode);
                if (!empty($ossPush['ok'])) {
                    $pdo->prepare('UPDATE app_releases SET apk_url=? WHERE id=?')->execute([$ossPush['url'], $releaseId]);
                    $ossNote = '，已同步到 OSS';
                } else {
                    error_log('app release OSS push failed #' . $releaseId . ': ' . (string) ($ossPush['log'] ?? ''));
                    $ossNote = '，但 OSS 同步失败，暂用服务器直发';
                }
            } catch (Throwable $ossError) {
                error_log('app release OSS push error #' . $releaseId . ': ' . $ossError->getMessage());
                $ossNote = '，但 OSS 同步异常，暂用服务器直发';
            }
            $newReleasePath = null;
            adminFlash('Android ' . $versionName . ' 已发布；版本代码 ' . (int) $versionCode . '，APK SHA-256 已记录' . $ossNote . '。');
            adminRedirect('settings');
        }

        if ($action === 'settings.ios_download.save') {
            $url = appIosDownloadUrl((string) ($_POST['ios_download_url'] ?? ''));
            $pdo->beginTransaction();
            $query = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? FOR UPDATE');
            $query->execute(['ios_download_url']);
            $before = (string) ($query->fetchColumn() ?: '');
            $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')->execute(['ios_download_url', $url]);
            adminAudit($pdo, $admin, $action, 'setting', null, '修改首页 iOS 下载地址', ['ios_download_url' => $before], ['ios_download_url' => $url]);
            $pdo->commit();
            adminFlash($url === '' ? 'iOS 下载入口已关闭。' : 'iOS 下载地址已保存，网站首页刷新后生效。');
            adminRedirect('settings');
        }

        if ($action === 'settings.backup_download.save') {
            $android = appBackupLines('android', $_POST['android_lines'] ?? []);
            $ios = appBackupLines('ios', $_POST['ios_lines'] ?? []);
            $enabled = !empty($_POST['backup_enabled']) ? '1' : '0';
            $pdo->beginTransaction();
            $before = appBackupDownloadSettings($pdo);
            $save = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
            $save->execute(['backup_download_enabled', $enabled]);
            $save->execute(['android_backup_lines', json_encode($android, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)]);
            $save->execute(['ios_backup_lines', json_encode($ios, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)]);
            adminAudit($pdo, $admin, $action, 'setting', null, '修改官网备用下载线路', $before, ['enabled' => $enabled === '1', 'android' => $android, 'ios' => $ios]);
            $pdo->commit();
            adminFlash('备用下载线路已保存，官网刷新后生效。');
            adminRedirect('settings');
        }

        if ($action === 'settings.mall_limits.save') {
            $limits = [];
            foreach (['basic', 'gold'] as $level) {
                $raw = trim(adminPostString('mall_'.$level.'_product_limit', 10));
                if (!preg_match('/^(0|[1-9][0-9]{0,6})$/D', $raw) || (int)$raw > 1000000) {
                    throw new InvalidArgumentException('上架上限须为 0 到 1000000 的整数；0 表示暂停新增上架。');
                }
                $limits[$level] = (int)$raw;
            }
            $pdo->beginTransaction();
            $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? FOR UPDATE');
            $q->execute(['mall_product_limits']);
            $before = json_decode((string)$q->fetchColumn(), true);
            $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')->execute(['mall_product_limits', json_encode($limits)]);
            adminAudit($pdo, $admin, $action, 'setting', null, '修改店铺商品上架名额', $before, $limits);
            $pdo->commit();
            adminFlash('上架名额已更新，Android、iOS 和网页端刷新后生效。');
            adminRedirect('settings');
        }

        if ($action === 'settings.mall_settle.save') {
            $rawRate = trim(adminPostString('mall_settlement_fee_rate', 10));
            if (!preg_match('/^(?:[0-9]|[1-9][0-9])(?:\.[0-9]{1,2})?$/D', $rawRate) || (float) $rawRate > 99) {
                throw new InvalidArgumentException('手续费率必须是 0 到 99 之间的百分数。');
            }
            $rawDays = trim(adminPostString('mall_auto_confirm_days', 3));
            if (!preg_match('/^(?:[1-9]|[1-9][0-9])$/D', $rawDays)) {
                throw new InvalidArgumentException('自动确认收货天数必须是 1 到 99 的整数。');
            }
            $rate = number_format((float) $rawRate / 100, 4, '.', '');
            $autoDays = (int) $rawDays;
            $pdo->beginTransaction();
            $q = $pdo->prepare('SELECT `key`,`value` FROM app_settings WHERE `key` IN (?,?) FOR UPDATE');
            $q->execute(['mall_settlement_fee_rate', 'mall_auto_confirm_days']);
            $before = $q->fetchAll(PDO::FETCH_KEY_PAIR);
            $save = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
            $save->execute(['mall_settlement_fee_rate', $rate]);
            $save->execute(['mall_auto_confirm_days', (string) $autoDays]);
            adminAudit($pdo, $admin, $action, 'setting', null, '修改商城结算手续费率与自动确认收货天数', $before, ['mall_settlement_fee_rate' => $rate, 'mall_auto_confirm_days' => $autoDays]);
            $pdo->commit();
            adminFlash('商城结算参数已更新，新订单按新费率计算。');
            adminRedirect('settings');
        }

        if ($action === 'settings.api_nodes.save') {
            // Accepts "url|weight|on" per line; index.php republishes this through app.config.
            $raw = adminPostString('api_nodes', 20000);
            $nodes = [];
            foreach (preg_split('/\R/', $raw) ?: [] as $line) {
                $line = trim($line);
                if ($line === '') continue;
                $parts = array_map('trim', explode('|', $line));
                $url = rtrim((string) ($parts[0] ?? ''), '/');
                if ($url === '') continue;
                if (!preg_match('#^https?://[^/\s]+(?:/[^\s]*)?$#i', $url)) throw new InvalidArgumentException('节点地址格式不正确：' . $url);
                // Accept a root address, /spxchat or /spxchat/api.php and store the root.
                $url = preg_replace('#/spxchat(?:/api\.php)?$#i', '', $url) ?: $url;
                $state = strtolower((string) ($parts[2] ?? 'on'));
                $nodes[] = [
                    'url' => $url,
                    'weight' => max(1, min(1000, (int) ($parts[1] ?? 1))),
                    'enabled' => in_array($state, ['on', '1', 'true', 'yes'], true) ? 1 : 0,
                ];
                if (count($nodes) > 20) throw new InvalidArgumentException('最多配置 20 个节点。');
            }
            $json = json_encode($nodes, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
            $pdo->beginTransaction();
            $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? FOR UPDATE');
            $q->execute(['api_nodes']);
            $before = (string) ($q->fetchColumn() ?: '');
            $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')->execute(['api_nodes', $json]);
            adminAudit($pdo, $admin, $action, 'setting', null, '修改 API 节点分流', ['nodes' => $before], ['nodes' => $json]);
            $pdo->commit();
            $beaconNote = '';
            [$beaconUrl, $beaconToken] = adminBeaconConfig($pdo);
            if ($beaconUrl !== '' && $beaconToken !== '') {
                try {
                    adminBeaconCall($beaconUrl, $beaconToken, '/beacon', ['nodes' => $nodes]);
                    $beaconNote = ' 已同步到杭州地址广播。';
                } catch (Throwable $beaconError) {
                    $beaconNote = ' 但同步到杭州失败：' . $beaconError->getMessage();
                }
            }
            adminFlash('API 节点分流已保存，App 下次拉取配置后生效。' . $beaconNote);
            adminRedirect('settings');
        }

        if ($action === 'settings.beacon.save' || $action === 'settings.beacon.sync') {
            $isSave = $action === 'settings.beacon.save';
            if ($isSave) {
                $url = rtrim(adminPostString('beacon_url', 255), '/');
                $token = trim(adminPostString('beacon_token', 200));
                if ($url !== '' && !preg_match('#^https?://[^/\s]+$#i', $url)) {
                    throw new InvalidArgumentException('广播地址只需根地址，例如 https://121.196.246.42:8443');
                }
                $upsert = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
                $upsert->execute(['beacon_url', $url]);
                $upsert->execute(['beacon_token', $token]);
                adminAudit($pdo, $admin, $action, 'setting', null, '修改地址广播配置', [], ['beacon_url' => $url]);
            } else {
                [$url, $token] = adminBeaconConfig($pdo);
            }
            if ($url === '' || $token === '') {
                adminFlash('请先保存广播地址和令牌。');
                adminRedirect('settings');
            }
            $message = $isSave ? '广播配置已保存。' : '';
            try {
                $result = adminBeaconCall($url, $token, '/beacon', ['nodes' => adminBeaconNodes($pdo)]);
                $count = count($result['config']['nodes'] ?? []);
                $message .= ' 已同步到杭州（当前 ' . $count . ' 个节点）。';
            } catch (Throwable $beaconError) {
                $message .= ' 同步失败：' . $beaconError->getMessage();
            }
            adminFlash($message);
            adminRedirect('settings');
        }

        if ($action === 'settings.deposit.save') {
            $address = trim(adminPostString('deposit_address', 42));
            if (!preg_match('/^0x[0-9a-fA-F]{40}$/', $address)) {
                throw new InvalidArgumentException('BSC 收款地址格式无效。');
            }
            $pdo->beginTransaction();
            $old = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? FOR UPDATE');
            $old->execute(['bsc_usdt_deposit_address']);
            $before = $old->fetchColumn();
            $save = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
            $save->execute(['bsc_usdt_deposit_address', strtolower($address)]);
            adminAudit($pdo, $admin, 'settings.deposit', 'setting', null, '修改 BSC USDT 收款地址', ['address' => $before ?: ''], ['address' => strtolower($address)]);
            $pdo->commit();
            adminFlash('充值收款地址已更新。');
            adminRedirect('settings');
        }

        if ($action === 'settings.withdraw_config.save') {
            $feeRates = [];
            $minAmounts = [];
            foreach (['USDT', 'SPX001', 'FISS'] as $sym) {
                $feeKey = 'fee_' . strtolower($sym);
                $minKey = 'min_' . strtolower($sym);
                $fee = trim((string) adminPostString($feeKey, 8));
                $min = trim((string) adminPostString($minKey, 40));
                if ($fee === '') $fee = '1';
                if ($min === '') $min = '0';
                if (!preg_match('/^(?:0|[1-9][0-9]?)(?:\.[0-9]{1,4})?$/', $fee) || (float) $fee > 50) {
                    throw new InvalidArgumentException($sym . ' 手续费率必须是 0–50 之间的数字（例如 1 表示 1%）。');
                }
                if (!preg_match('/^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,18})?$/', $min)) {
                    throw new InvalidArgumentException($sym . ' 最低提现额必须是非负数字。');
                }
                $feeRates[$sym] = $fee;
                $minAmounts[$sym] = $min;
            }
            $upsert = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
            $before = [];
            foreach (['withdraw_fee_rates', 'withdraw_min_amounts'] as $key) {
                $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
                $q->execute([$key]);
                $before[$key] = (string) ($q->fetchColumn() ?: '');
            }
            $upsert->execute(['withdraw_fee_rates', json_encode($feeRates, JSON_UNESCAPED_UNICODE)]);
            $upsert->execute(['withdraw_min_amounts', json_encode($minAmounts, JSON_UNESCAPED_UNICODE)]);
            adminAudit($pdo, $admin, 'settings.withdraw_config.save', 'setting', null, '修改提现手续费与最低额',
                $before, ['withdraw_fee_rates' => json_encode($feeRates), 'withdraw_min_amounts' => json_encode($minAmounts)]);
            adminFlash('提现手续费与最低额已保存，新提交的提现立即生效。');
            adminRedirect('settings');
        }

        if ($action === 'settings.fiss_withdraw.save') {
            // FISS 提现总开关：关闭后钱包提现接口会直接拒绝 FISS，已有提现单仍可正常审核
            $enabled = (int) ($_POST['enabled'] ?? 0) === 1 ? '1' : '0';
            $pdo->beginTransaction();
            $fissToggleLookup = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? FOR UPDATE');
            $fissToggleLookup->execute(['withdraw_fiss_enabled']);
            $fissToggleBefore = (string) ($fissToggleLookup->fetchColumn() ?: '0');
            $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')->execute(['withdraw_fiss_enabled', $enabled]);
            adminAudit($pdo, $admin, 'settings.fiss_withdraw', 'setting', null, '切换 FISS 提现开关', ['enabled' => $fissToggleBefore], ['enabled' => $enabled]);
            $pdo->commit();
            adminFlash('FISS 提现已' . ($enabled === '1' ? '开启' : '关闭') . '。');
            adminRedirect('settings');
        }

        if ($action === 'settings.invite_base.save') {
            $base = rtrim(trim((string) ($_POST['invite_base_url'] ?? '')), '/');
            if (!preg_match('#^https?://[^/\s]+$#i', $base)) throw new InvalidArgumentException('邀请链接域名格式无效。');
            $save = $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)');
            $save->execute(['invite_base_url', $base]);
            adminFlash('邀请链接域名已更新。');
            adminRedirect('settings');
        }

        if ($action === 'password.save') {
            $currentPassword = (string) ($_POST['current_password'] ?? '');
            $newPassword = (string) ($_POST['new_password'] ?? '');
            $verify = $pdo->prepare('SELECT password_hash FROM admin_users WHERE id=?');
            $verify->execute([$admin['id']]);
            $hash = (string) $verify->fetchColumn();
            if (!password_verify($currentPassword, $hash)) throw new InvalidArgumentException('当前密码不正确。');
            if (strlen($newPassword) < 12 || strlen($newPassword) > 200) throw new InvalidArgumentException('新密码长度须为 12 至 200 个字符。');
            $pdo->beginTransaction();
            $pdo->prepare('UPDATE admin_users SET password_hash=? WHERE id=?')->execute([password_hash($newPassword, PASSWORD_DEFAULT), $admin['id']]);
            adminAudit($pdo, $admin, 'admin.password', 'admin', (int) $admin['id'], '修改管理员自身密码', null, ['password_changed' => true]);
            $pdo->commit();
            session_regenerate_id(true);
            adminFlash('管理员密码已更新。');
            adminRedirect('profile');
        }

        throw new InvalidArgumentException('不支持的管理操作。');
    } catch (Throwable $exception) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        foreach ($newProductImages as $image) @unlink($image['path']);
        if ($newReleasePath !== null) @unlink($newReleasePath);
        error_log('System admin action failed: ' . $exception->getMessage());
        adminFlash($exception instanceof InvalidArgumentException ? $exception->getMessage() : '保存失败，请检查数据后重试。', 'error');
        adminRedirect($section);
    }
}

$admin = adminRequireLogin();
$flash = $_SESSION['admin_flash'] ?? null;
unset($_SESSION['admin_flash']);
$csrf = adminCsrf();

if (isset($_GET['qualification_image'])) {
    if (!adminCan('merchants')) { http_response_code(403); exit; }
    $id = (string) $_GET['qualification_image'];
    $q = $pdo->prepare('SELECT owner_user_id,qualification_images FROM merchants WHERE id=?');
    $q->execute([(int) ($_GET['merchant_id'] ?? 0)]);
    $shop = $q->fetch();
    if (!$shop || !in_array($id, json_decode((string) ($shop['qualification_images'] ?? ''), true) ?: [], true)) { http_response_code(404); exit; }
    mallServeQualificationImage($id, (int) $shop['owner_user_id']);
}

if (isset($_GET['export'])) {
    adminHandleExport($pdo);
}

$navGroups = require __DIR__ . '/admin_navigation.php';
$sections = [];
foreach ($navGroups as $groupItems) $sections += $groupItems;
$section = (string) ($_GET['section'] ?? 'dashboard');
if (!isset($sections[$section])) $section = 'dashboard';
if (!adminCan($section)) {
    adminFlash('当前管理员角色无权访问该模块。', 'error');
    adminRedirect('dashboard');
}
$sections = array_filter($sections, static fn(string $key): bool => adminCan($key), ARRAY_FILTER_USE_KEY);
$data = [];
$search = trim((string) ($_GET['q'] ?? ''));
$walletType = trim((string) ($_GET['wallet_type'] ?? ''));
$walletStatus = trim((string) ($_GET['wallet_status'] ?? ''));
$walletFrom = trim((string) ($_GET['from'] ?? ''));
$walletTo = trim((string) ($_GET['to'] ?? ''));

try {
    if ($section === 'dashboard') {
        $data['stats'] = [
            '用户总数' => (int) $pdo->query('SELECT COUNT(*) FROM users')->fetchColumn(),
            '今日新增用户' => (int) $pdo->query('SELECT COUNT(*) FROM users WHERE created_at >= CURDATE()')->fetchColumn(),
            '停用用户' => (int) $pdo->query('SELECT COUNT(*) FROM users WHERE status=0')->fetchColumn(),
            'USDT 总余额' => (string) $pdo->query('SELECT COALESCE(SUM(balance),0) FROM wallet_assets WHERE symbol="USDT" AND chain="BSC"')->fetchColumn(),
            'SPX001 总余额' => (string) $pdo->query('SELECT COALESCE(SUM(balance),0) FROM wallet_assets WHERE symbol="SPX001" AND chain="BSC"')->fetchColumn(),
            '当前质押期' => (string) ($pdo->query("SELECT COALESCE(MAX(phase_no),0) FROM staking_phases WHERE status=1 AND symbol='SPX001'")->fetchColumn() ?: '未开启'),
            '质押剩余额度' => adminAmount4($pdo->query("SELECT COALESCE((SELECT total_quota-reserved_quota FROM staking_phases WHERE status=1 AND symbol='SPX001' ORDER BY phase_no DESC LIMIT 1),0)")->fetchColumn() ?: '0'),
            '待验证充值' => (int) $pdo->query("SELECT COUNT(*) FROM recharge_records WHERE status='pending'")->fetchColumn(),
            '待审核提现' => (int) $pdo->query("SELECT COUNT(*) FROM wallet_transactions WHERE type='withdraw' AND status='pending'")->fetchColumn(),
            '待处理工单' => (int) $pdo->query("SELECT COUNT(*) FROM support_tickets WHERE status IN ('open','processing')")->fetchColumn(),
        ];
        $data['recent'] = $pdo->query('SELECT id,username,nickname,spx_id,status,created_at FROM users ORDER BY id DESC LIMIT 8')->fetchAll();
        $heartbeat = $pdo->prepare('SELECT status,checked_at,error_message FROM service_heartbeats WHERE service_name=? LIMIT 1');
        $heartbeat->execute(['bsc_recharge_verifier']);
        $data['heartbeat'] = $heartbeat->fetch() ?: null;
        $trends = $pdo->query('SELECT DATE(created_at) AS day,SUM(CASE WHEN type="deposit" AND symbol="USDT" AND status="completed" THEN amount ELSE 0 END) AS recharge_usdt,SUM(CASE WHEN type="staking" AND symbol="SPX001" AND status="completed" THEN amount ELSE 0 END) AS staking_spx FROM wallet_transactions WHERE created_at>=DATE_SUB(CURDATE(),INTERVAL 29 DAY) AND created_at<DATE_ADD(CURDATE(),INTERVAL 1 DAY) GROUP BY DATE(created_at) ORDER BY day ASC')->fetchAll();
        $trendByDay = [];
        foreach ($trends as $trend) $trendByDay[$trend['day']] = $trend;
        $data['trends'] = [];
        for ($day = 29; $day >= 0; $day--) {
            $date = date('Y-m-d', strtotime('-' . $day . ' days'));
            $data['trends'][] = $trendByDay[$date] ?? ['day' => $date, 'recharge_usdt' => '0', 'staking_spx' => '0'];
        }
    } elseif ($section === 'users') {
        $where = $search !== '' ? ' WHERE username LIKE ? OR nickname LIKE ? OR spx_id LIKE ? OR email LIKE ?' : '';
        $params = $search !== '' ? array_fill(0, 4, '%' . $search . '%') : [];
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM users' . $where, 'SELECT id,username,email,nickname,spx_id,bsc_wallet_address,status,remark,created_at FROM users' . $where . ' ORDER BY id DESC', $params);
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
        $detailId = max(0, (int) ($_GET['detail_id'] ?? 0));
        if ($detailId > 0) {
            $detail = $pdo->prepare('SELECT id,username,email,email_verified_at,nickname,spx_id,avatar_url,remark,bsc_wallet_address,status,registration_ip,device_id,device_model,android_version,app_version,created_at FROM users WHERE id=? LIMIT 1');
            $detail->execute([$detailId]);
            $data['user_detail'] = $detail->fetch() ?: null;
            if ($data['user_detail'] && adminCan('wallets')) {
                // 老用户可能没有新增币种（如 FISS）的资产行，这里补齐，否则后台没有该币种的加减入口。
                $pdo->prepare("INSERT IGNORE INTO wallet_assets (user_id,symbol,chain,balance,stakeable_balance) VALUES (?,'SPX001','BSC',0,0),(?,'USDT','BSC',0,0),(?,'FISS','BSC',0,0)")
                    ->execute([$detailId, $detailId, $detailId]);
                $assets = $pdo->prepare('SELECT id,symbol,chain,balance,stakeable_balance,updated_at FROM wallet_assets WHERE user_id=? ORDER BY FIELD(symbol,"SPX001","FISS","USDT"),symbol,chain');
                $assets->execute([$detailId]);
                $data['user_assets'] = $assets->fetchAll();
                $activity = $pdo->prepare('SELECT symbol,chain,type,amount,status,tx_hash,created_at FROM wallet_transactions WHERE user_id=? ORDER BY id DESC LIMIT 20');
                $activity->execute([$detailId]);
                $data['user_activity'] = $activity->fetchAll();
            }
        }
    } elseif ($section === 'recharges') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM recharge_records', 'SELECT r.*,u.username,u.nickname FROM recharge_records r JOIN users u ON u.id=r.user_id ORDER BY r.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'recharge_review') {
        $pageData = adminPageQuery($pdo,
            'SELECT COUNT(*) FROM recharge_records WHERE status="manual_review"',
            'SELECT r.*,u.username,u.nickname,u.bsc_wallet_address,
                (SELECT a.from_address FROM recharge_audit_logs a WHERE a.record_id=r.id ORDER BY a.id DESC LIMIT 1) AS audit_from,
                (SELECT a.confirmations FROM recharge_audit_logs a WHERE a.record_id=r.id ORDER BY a.id DESC LIMIT 1) AS audit_confirmations
             FROM recharge_records r JOIN users u ON u.id=r.user_id
             WHERE r.status="manual_review" ORDER BY r.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'withdrawals') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM wallet_transactions WHERE type="withdraw"', 'SELECT t.*,u.username,u.nickname FROM wallet_transactions t JOIN users u ON u.id=t.user_id WHERE t.type="withdraw" ORDER BY FIELD(t.status,"pending","completed","rejected","cancelled"),t.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'transactions') {
        $pageData = adminWalletTransactionPage($pdo, $search, $walletType, $walletStatus, $walletFrom, $walletTo);
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'exchanges') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM exchange_records', 'SELECT e.*,u.username,u.nickname FROM exchange_records e JOIN users u ON u.id=e.user_id ORDER BY e.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'staking') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM staking_records', 'SELECT s.*,u.username,u.nickname,u.spx_id,ph.name AS phase_name,p.name AS plan_name,COALESCE((SELECT SUM(d.amount) FROM staking_dividends d WHERE d.staking_id=s.id),0) AS paid_dividend,COALESCE((SELECT reward_amount FROM referral_rewards r WHERE r.staking_id=s.id LIMIT 1),0) AS referral_reward FROM staking_records s JOIN users u ON u.id=s.user_id LEFT JOIN staking_phases ph ON ph.id=s.phase_id LEFT JOIN staking_plans p ON p.plan_days=s.plan_days AND p.symbol=s.symbol ORDER BY s.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
        $data['staking_stats'] = [
            'active_people' => (int) $pdo->query('SELECT COUNT(DISTINCT user_id) FROM staking_records WHERE status="active"')->fetchColumn(),
            'active_amount' => (string) $pdo->query('SELECT COALESCE(SUM(amount),0) FROM staking_records WHERE status="active" AND symbol="SPX001"')->fetchColumn(),
            'active_amount_fiss' => (string) $pdo->query('SELECT COALESCE(SUM(amount),0) FROM staking_records WHERE status="active" AND symbol="FISS"')->fetchColumn(),
            'all_people' => (int) $pdo->query('SELECT COUNT(DISTINCT user_id) FROM staking_records')->fetchColumn(),
            'all_amount' => (string) $pdo->query('SELECT COALESCE(SUM(amount),0) FROM staking_records WHERE symbol="SPX001"')->fetchColumn(),
            'all_amount_fiss' => (string) $pdo->query('SELECT COALESCE(SUM(amount),0) FROM staking_records WHERE symbol="FISS"')->fetchColumn(),
        ];
        $data['plans'] = $pdo->query('SELECT id,name,symbol,plan_days,apy,minimum_amount,step_amount,max_per_user,status FROM staking_plans ORDER BY sort_order,id')->fetchAll();
        $data['phases'] = [];
        foreach ($pdo->query('SELECT * FROM staking_phases ORDER BY status DESC,phase_no DESC') as $phaseRow) {
            $phaseKey = (string) ($phaseRow['symbol'] ?? 'SPX001');
            if (!isset($data['phases'][$phaseKey])) $data['phases'][$phaseKey] = $phaseRow;
        }
        $data['phase'] = $data['phases']['SPX001'] ?? null;
        // 销毁档位（FISS 起押阶梯）
        $data['burn'] = burnCurrentTier($pdo, 'FISS');
        $data['burn_tiers'] = burnTierRows($pdo, 'FISS');
        $data['fiss_wallet'] = [
            'deposit' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="fiss_deposit_address"')->fetchColumn() ?: ''),
            'enabled' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_sweep_enabled"')->fetchColumn() ?: '1'),
            'target' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_address"')->fetchColumn() ?: '0x000000000000000000000000000000000000dEaD'),
            'min' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_min_amount"')->fetchColumn() ?: '10000'),
            'system' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_system_address"')->fetchColumn() ?: ''),
        ];
        $data['burn_settings'] = [
            'mode' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_tier_mode"')->fetchColumn() ?: 'auto'),
            'manual' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_tier_manual_threshold"')->fetchColumn() ?: '0'),
            'baseline' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_baseline"')->fetchColumn() ?: '0'),
            'address' => (string) ($pdo->query('SELECT `value` FROM app_settings WHERE `key`="burn_address"')->fetchColumn() ?: ''),
        ];
        foreach ($data['phases'] as $phaseKey => $phaseRow) {
            $remaining = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))-CAST(? AS DECIMAL(36,18))');
            $remaining->execute([$phaseRow['total_quota'], $phaseRow['reserved_quota']]);
            $data['phases'][$phaseKey]['remaining_quota'] = (string) $remaining->fetchColumn();
        }
        if ($data['phase']) $data['phase']['remaining_quota'] = $data['phases']['SPX001']['remaining_quota'];
        $detailId = max(0, (int) ($_GET['detail_id'] ?? 0));
        if ($detailId > 0) {
            $detail = $pdo->prepare('SELECT s.*,u.username,u.nickname,u.email,u.spx_id,ph.name AS phase_name,p.name AS plan_name,CAST(ROUND(s.amount*s.apy*s.plan_days/36500,18) AS DECIMAL(36,18)) AS expected_reward,COALESCE((SELECT SUM(d.amount) FROM staking_dividends d WHERE d.staking_id=s.id),0) AS paid_dividend,COALESCE((SELECT reward_amount FROM referral_rewards r WHERE r.staking_id=s.id LIMIT 1),0) AS referral_reward FROM staking_records s JOIN users u ON u.id=s.user_id LEFT JOIN staking_phases ph ON ph.id=s.phase_id LEFT JOIN staking_plans p ON p.plan_days=s.plan_days AND p.symbol=s.symbol WHERE s.id=? LIMIT 1');
            $detail->execute([$detailId]);
            $data['staking_detail'] = $detail->fetch() ?: null;
            if ($data['staking_detail']) {
                $dividends = $pdo->prepare('SELECT period_no,amount,created_at FROM staking_dividends WHERE staking_id=? ORDER BY period_no');
                $dividends->execute([$detailId]);
                $data['staking_detail_dividends'] = $dividends->fetchAll();
            }
        }
    } elseif ($section === 'versions') {
        $data['version_totals'] = $pdo->query('SELECT COUNT(*) AS users, SUM(last_seen_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)) AS active7, SUM(last_seen_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)) AS active1 FROM app_version_stats')->fetch() ?: [];
        $data['version_platform'] = $pdo->query('SELECT platform, COUNT(*) AS users FROM app_version_stats GROUP BY platform ORDER BY users DESC')->fetchAll();
        $data['version_dist'] = $pdo->query('SELECT version_code, MAX(version_name) AS version_name, COUNT(*) AS users, MAX(last_seen_at) AS last_seen FROM app_version_stats GROUP BY version_code ORDER BY version_code DESC')->fetchAll();
        $data['version_daily'] = $pdo->query('SELECT stat_date, version_code, COUNT(*) AS users FROM app_version_daily WHERE stat_date >= DATE_SUB(CURDATE(), INTERVAL 13 DAY) GROUP BY stat_date, version_code ORDER BY stat_date ASC')->fetchAll();
        $data['version_users'] = $pdo->query('SELECT s.user_id, s.platform, s.version_code, s.version_name, s.first_seen_at, s.last_seen_at, s.last_ip, u.username, u.nickname FROM app_version_stats s LEFT JOIN users u ON u.id=s.user_id ORDER BY s.last_seen_at DESC LIMIT 200')->fetchAll();
    } elseif ($section === 'referrals') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM referral_rewards', 'SELECT r.*,owner.username AS referrer_username,owner.nickname AS referrer_nickname,staker.username AS staker_username,staker.nickname AS staker_nickname FROM referral_rewards r JOIN users owner ON owner.id=r.referrer_user_id JOIN users staker ON staker.id=r.staker_user_id ORDER BY r.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'reports') {
        $reportFrom = (string) ($_GET['from'] ?? date('Y-m-d', strtotime('-29 days')));
        $reportTo = (string) ($_GET['to'] ?? date('Y-m-d'));
        if (!adminDateIsValid($reportFrom) || !adminDateIsValid($reportTo) || $reportFrom > $reportTo) {
            $reportFrom = date('Y-m-d', strtotime('-29 days'));
            $reportTo = date('Y-m-d');
        }
        $data['report_from'] = $reportFrom;
        $data['report_to'] = $reportTo;
        $dateParams = [$reportFrom . ' 00:00:00', $reportTo . ' 00:00:00'];
        $dailySelect = 'SELECT DATE(created_at) AS day,COUNT(*) AS transaction_count,SUM(CASE WHEN type="deposit" AND symbol="USDT" AND status="completed" THEN amount ELSE 0 END) AS recharge_usdt,SUM(CASE WHEN type="withdraw" AND symbol="USDT" AND status="completed" THEN amount ELSE 0 END) AS withdraw_usdt,SUM(CASE WHEN type="admin_adjustment" AND symbol="USDT" AND amount>0 THEN amount ELSE 0 END) AS admin_credit_usdt,-SUM(CASE WHEN type="admin_adjustment" AND symbol="USDT" AND amount<0 THEN amount ELSE 0 END) AS admin_debit_usdt FROM wallet_transactions WHERE created_at>=? AND created_at<DATE_ADD(?,INTERVAL 1 DAY) GROUP BY DATE(created_at) ORDER BY day DESC';
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(DISTINCT DATE(created_at)) FROM wallet_transactions WHERE created_at>=? AND created_at<DATE_ADD(?,INTERVAL 1 DAY)', $dailySelect, $dateParams);
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
        $totals = $pdo->prepare('SELECT COALESCE(SUM(CASE WHEN type="deposit" AND symbol="USDT" AND status="completed" THEN amount ELSE 0 END),0) AS recharge_usdt,COALESCE(SUM(CASE WHEN type="withdraw" AND symbol="USDT" AND status="completed" THEN amount ELSE 0 END),0) AS withdraw_usdt,COUNT(*) AS transaction_count FROM wallet_transactions WHERE created_at>=? AND created_at<DATE_ADD(?,INTERVAL 1 DAY)');
        $totals->execute([$reportFrom . ' 00:00:00', $reportTo . ' 00:00:00']);
        $data['totals'] = $totals->fetch() ?: [];

        // ---- SPX001 财务汇总（原报表只有 USDT）----
        $spxPeriod = $pdo->prepare('SELECT
            COALESCE(SUM(CASE WHEN type="deposit" AND status="completed" THEN amount ELSE 0 END),0) AS recharge_spx,
            COALESCE(SUM(CASE WHEN type="withdraw" AND status="completed" THEN amount ELSE 0 END),0) AS withdraw_spx,
            COALESCE(SUM(CASE WHEN type="staking" AND status="completed" THEN amount ELSE 0 END),0) AS staking_spx,
            COALESCE(SUM(CASE WHEN type="staking_dividend" THEN amount ELSE 0 END),0) AS dividend_spx,
            COALESCE(SUM(CASE WHEN type="exchange_in" THEN amount ELSE 0 END),0) AS exchange_in_spx,
            COALESCE(SUM(CASE WHEN type="referral_reward" THEN amount ELSE 0 END),0) AS referral_spx,
            COALESCE(SUM(CASE WHEN type="admin_adjustment" AND amount>0 THEN amount ELSE 0 END),0) AS admin_credit_spx,
            COALESCE(SUM(CASE WHEN type="admin_adjustment" AND amount<0 THEN -amount ELSE 0 END),0) AS admin_debit_spx
            FROM wallet_transactions WHERE symbol="SPX001" AND created_at>=? AND created_at<DATE_ADD(?,INTERVAL 1 DAY)');
        $spxPeriod->execute([$reportFrom . ' 00:00:00', $reportTo . ' 00:00:00']);
        $data['spx_totals'] = $spxPeriod->fetch() ?: [];

        $spxAssets = $pdo->query('SELECT COALESCE(SUM(balance),0) AS balance,COALESCE(SUM(stakeable_balance),0) AS stakeable FROM wallet_assets WHERE symbol="SPX001" AND chain="BSC"')->fetch() ?: [];
        $data['spx_live'] = [
            'balance' => (string) ($spxAssets['balance'] ?? '0'),
            'stakeable' => (string) ($spxAssets['stakeable'] ?? '0'),
            'active_staking' => (string) $pdo->query('SELECT COALESCE(SUM(amount),0) FROM staking_records WHERE symbol="SPX001" AND status="active"')->fetchColumn(),
            'all_staking' => (string) $pdo->query('SELECT COALESCE(SUM(amount),0) FROM staking_records WHERE symbol="SPX001"')->fetchColumn(),
        ];

        $spxDaily = $pdo->prepare('SELECT DATE(created_at) AS day,
            COALESCE(SUM(CASE WHEN type="deposit" AND status="completed" THEN amount ELSE 0 END),0) AS recharge_spx,
            COALESCE(SUM(CASE WHEN type="withdraw" AND status="completed" THEN amount ELSE 0 END),0) AS withdraw_spx,
            COALESCE(SUM(CASE WHEN type="staking" AND status="completed" THEN amount ELSE 0 END),0) AS staking_spx,
            COALESCE(SUM(CASE WHEN type="staking_dividend" THEN amount ELSE 0 END),0) AS dividend_spx,
            COALESCE(SUM(CASE WHEN type="admin_adjustment" THEN amount ELSE 0 END),0) AS admin_net_spx
            FROM wallet_transactions WHERE symbol="SPX001" AND created_at>=? AND created_at<DATE_ADD(?,INTERVAL 1 DAY)
            GROUP BY DATE(created_at) ORDER BY day DESC LIMIT 90');
        $spxDaily->execute([$reportFrom . ' 00:00:00', $reportTo . ' 00:00:00']);
        $data['spx_daily'] = $spxDaily->fetchAll();
    } elseif ($section === 'products') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM products', 'SELECT * FROM products ORDER BY sort_order ASC,id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
        $editId = max(0, (int) ($_GET['edit'] ?? 0));
        if ($editId > 0) {
            $edit = $pdo->prepare('SELECT * FROM products WHERE id=?');
            $edit->execute([$editId]);
            $data['edit'] = $edit->fetch() ?: null;
            if ($data['edit']) {
                $images = $pdo->prepare('SELECT id,image_url,sort_order FROM product_images WHERE product_id=? ORDER BY sort_order,id');
                $images->execute([$editId]);
                $data['edit_images'] = $images->fetchAll();
            }
        }
    } elseif ($section === 'orders') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM orders', 'SELECT o.*,u.username,u.nickname,p.name AS product_name,p.image_url FROM orders o JOIN users u ON u.id=o.user_id JOIN products p ON p.id=o.product_id ORDER BY o.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'tickets') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM support_tickets', 'SELECT t.*,u.username,u.nickname FROM support_tickets t JOIN users u ON u.id=t.user_id ORDER BY FIELD(t.status,"open","processing","resolved","closed"),t.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'questions') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM user_questions', 'SELECT q.*,u.username,u.nickname FROM user_questions q JOIN users u ON u.id=q.user_id ORDER BY FIELD(q.status,"pending","answered","closed"),q.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'feedback') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM feedback', 'SELECT f.*,u.username,u.nickname FROM feedback f JOIN users u ON u.id=f.user_id ORDER BY FIELD(f.status,"pending","reviewed","resolved"),f.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'faqs') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM faq_items', 'SELECT * FROM faq_items ORDER BY sort_order,id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
        $faqId = max(0, (int) ($_GET['faq_id'] ?? 0));
        if ($faqId > 0) {
            $editFaq = $pdo->prepare('SELECT * FROM faq_items WHERE id=?');
            $editFaq->execute([$faqId]);
            $data['edit_faq'] = $editFaq->fetch() ?: null;
        }
    } elseif ($section === 'groups') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM chat_groups', 'SELECT g.id,g.group_code,g.name,g.announcement,g.created_at,COUNT(gm.id) AS member_count FROM chat_groups g LEFT JOIN chat_group_members gm ON gm.group_id=g.id GROUP BY g.id,g.group_code,g.name,g.announcement,g.created_at ORDER BY g.id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
        $groupId = max(0, (int) ($_GET['group_id'] ?? 0));
        if ($groupId > 0) {
            $groupQuery = $pdo->prepare('SELECT id,group_code,name FROM chat_groups WHERE id=?');
            $groupQuery->execute([$groupId]);
            $data['selected_group'] = $groupQuery->fetch() ?: null;
            if ($data['selected_group']) {
                $memberPage = adminPageQuery($pdo, 'SELECT COUNT(*) FROM chat_group_members WHERE group_id=?', 'SELECT gm.user_id,gm.role,gm.mute,gm.joined_at,u.username,u.nickname,u.spx_id FROM chat_group_members gm JOIN users u ON u.id=gm.user_id WHERE gm.group_id=? ORDER BY FIELD(gm.role,"owner","admin","member"),gm.joined_at ASC', [$groupId], 'member_page');
                $data['members'] = $memberPage['rows'];
                $data['member_pagination'] = $memberPage['pagination'];
            }
        }
    } elseif ($section === 'logs') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM admin_audit_logs', 'SELECT * FROM admin_audit_logs ORDER BY id DESC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'admins') {
        $pageData = adminPageQuery($pdo, 'SELECT COUNT(*) FROM admin_users', 'SELECT id,username,role,status,last_login_at,created_at FROM admin_users ORDER BY id ASC');
        $data['rows'] = $pageData['rows']; $data['pagination'] = $pageData['pagination'];
    } elseif ($section === 'email_notifications') {
        $data['email_templates'] = $pdo->query('SELECT id,event_key,name,subject,body_html,status,updated_at FROM email_notification_templates ORDER BY id')->fetchAll();
        $data['email_recipients'] = $pdo->query('SELECT id,email,status,created_at FROM email_notification_recipients ORDER BY id')->fetchAll();
        $data['email_subscriptions'] = [];
        foreach ($pdo->query('SELECT recipient_id,template_id FROM email_notification_subscriptions')->fetchAll() as $subscription) {
            $data['email_subscriptions'][(int) $subscription['recipient_id']][] = (int) $subscription['template_id'];
        }
        $data['email_logs'] = $pdo->query('SELECT event_key,recipient_email,subject,status,error_message,created_at FROM email_notification_logs ORDER BY id DESC LIMIT 50')->fetchAll();
    } elseif ($section === 'settings') {
        $data['mall_product_limits'] = mallProductLimits($pdo);
        $settleRate = trim((string) ($pdo->query("SELECT `value` FROM app_settings WHERE `key`='mall_settlement_fee_rate'")->fetchColumn() ?: '0.05'));
        $data['mall_settle_fee_rate'] = rtrim(rtrim(number_format(((float) $settleRate) * 100, 2, '.', ''), '0'), '.');
        $data['mall_auto_confirm_days'] = (int) ($pdo->query("SELECT `value` FROM app_settings WHERE `key`='mall_auto_confirm_days'")->fetchColumn() ?: 3);
        $data['ios_download_url'] = appIosDownloadConfig($pdo)['ios_download_url'];
        $data['backup_download'] = appBackupDownloadSettings($pdo);
        $setting = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
        $setting->execute(['api_nodes']);
        $apiNodes = json_decode((string) ($setting->fetchColumn() ?: '[]'), true);
        $apiNodeLines = [];
        foreach (is_array($apiNodes) ? $apiNodes : [] as $node) {
            if (!is_array($node) || !is_string($node['url'] ?? null)) continue;
            $apiNodeLines[] = $node['url'] . '|' . (int) ($node['weight'] ?? 1) . '|' . ((int) ($node['enabled'] ?? 1) === 1 ? 'on' : 'off');
        }
        $data['api_nodes_text'] = implode("\n", $apiNodeLines);
        [$data['beacon_url'], $data['beacon_token']] = adminBeaconConfig($pdo);
        $data['beacon_live'] = null;
        $data['beacon_error'] = '';
        if (($data['beacon_url'] ?? '') !== '' && ($data['beacon_token'] ?? '') !== '') {
            try {
                $data['beacon_live'] = adminBeaconCall($data['beacon_url'], $data['beacon_token'], '/beacon', null, 3);
            } catch (Throwable $beaconError) {
                $data['beacon_error'] = $beaconError->getMessage();
            }
        }
        $setting->execute(['bsc_usdt_deposit_address']);
        $data['deposit_address'] = (string) ($setting->fetchColumn() ?: '');
        $setting->execute(['withdraw_fee_rates']);
        $feeRatesData = json_decode((string) ($setting->fetchColumn() ?: ''), true);
        $setting->execute(['withdraw_min_amounts']);
        $minAmountsData = json_decode((string) ($setting->fetchColumn() ?: ''), true);
        $data['withdraw_fee_rates'] = is_array($feeRatesData) ? $feeRatesData : [];
        $data['withdraw_min_amounts'] = is_array($minAmountsData) ? $minAmountsData : [];
        $setting->execute(['invite_base_url']);
        $data['invite_base_url'] = (string) ($setting->fetchColumn() ?: 'http://spx001.top');
        $data['releases'] = $pdo->query('SELECT id,version_code,version_name,release_notes,apk_size,apk_sha256,download_count,is_active,created_at FROM app_releases ORDER BY version_code DESC LIMIT 20')->fetchAll();
    }
} catch (Throwable $exception) {
    error_log('System admin page query failed: ' . $exception->getMessage());
    $data['load_error'] = '数据读取失败，请检查数据库结构及账号权限。';
}

function adminHidden(string $csrf, string $action, string $section): string
{
    return '<input type="hidden" name="csrf" value="' . adminEscape($csrf) . '"><input type="hidden" name="action" value="' . adminEscape($action) . '"><input type="hidden" name="section" value="' . adminEscape($section) . '">';
}

/**
 * 调用杭州「地址广播」服务。
 * $path 为 /beacon（写入节点）或 /beacon/sync（让杭州自己从本机拉一次）。
 */
function adminBeaconCall(string $baseUrl, string $token, string $path, ?array $body = null, int $timeout = 10): array
{
    $url = rtrim(trim($baseUrl), '/') . $path;
    $ch = curl_init($url);
    if ($ch === false) {
        throw new RuntimeException('无法初始化请求。');
    }
    $options = [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_CONNECTTIMEOUT => 5,
        CURLOPT_HTTPHEADER => ['X-Beacon-Token: ' . $token, 'Content-Type: application/json'],
    ];
    if ($body !== null) {
        $options[CURLOPT_POST] = true;
        $options[CURLOPT_POSTFIELDS] = json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }
    curl_setopt_array($ch, $options);
    $raw = (string) curl_exec($ch);
    $error = curl_error($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($raw === '' && $error !== '') {
        throw new RuntimeException('连接失败：' . $error);
    }
    $json = json_decode($raw, true);
    if ($code >= 400) {
        $detail = is_array($json) ? (string) ($json['error'] ?? $raw) : $raw;
        throw new RuntimeException('HTTP ' . $code . '：' . $detail);
    }
    return is_array($json) ? $json : ['raw' => $raw];
}

function adminBeaconConfig(PDO $pdo): array
{
    $q = $pdo->prepare('SELECT `key`,`value` FROM app_settings WHERE `key` IN (?,?)');
    $q->execute(['beacon_url', 'beacon_token']);
    $values = [];
    foreach ($q->fetchAll(PDO::FETCH_ASSOC) as $row) {
        $values[$row['key']] = (string) $row['value'];
    }
    return [$values['beacon_url'] ?? '', $values['beacon_token'] ?? ''];
}

function adminBeaconNodes(PDO $pdo): array
{
    $value = (string) ($pdo->query("SELECT `value` FROM app_settings WHERE `key`='api_nodes'")->fetchColumn() ?: '[]');
    $nodes = json_decode($value, true);
    return is_array($nodes) ? $nodes : [];
}

function adminTableEmpty(int $columns): string
{
    return '<tr><td colspan="' . $columns . '" class="empty">暂无记录</td></tr>';
}

function adminPageQuery(PDO $pdo, string $countSql, string $rowsSql, array $params = [], string $pageParam = 'page'): array
{
    $count = $pdo->prepare($countSql);
    $count->execute($params);
    $total = (int) $count->fetchColumn();
    $perPage = 30;
    $pages = max(1, (int) ceil($total / $perPage));
    $page = min($pages, max(1, (int) ($_GET[$pageParam] ?? 1)));
    $offset = ($page - 1) * $perPage;
    $rows = $pdo->prepare($rowsSql . ' LIMIT ' . $offset . ',' . $perPage);
    $rows->execute($params);
    return ['rows' => $rows->fetchAll(), 'pagination' => ['page' => $page, 'pages' => $pages, 'total' => $total]];
}

?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= adminEscape($sections[$section]) ?> · SPX Chat 管理后台</title>
    <style>
        :root{font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif;color:#17211e;background:#f3f6f4;font-synthesis:none;text-rendering:optimizeLegibility}
        *{box-sizing:border-box}body{margin:0;font-size:14px}a{color:inherit;text-decoration:none}button,input,textarea,select{font:inherit}
        .shell{min-height:100vh;display:grid;grid-template-columns:224px minmax(0,1fr)}.sidebar{background:#14251e;color:#e6f0ea;padding:22px 14px;display:flex;flex-direction:column;gap:24px}
        .brand{padding:0 12px;font-size:13px;font-weight:750;letter-spacing:.04em}.brand small{display:block;color:#91ab9d;font-weight:500;margin-top:6px;letter-spacing:0}
        .nav{display:grid;gap:4px}.nav a{padding:10px 12px;border-radius:5px;color:#b8c9bf}.nav a:hover,.nav a.active{background:#244334;color:#fff}.side-bottom{margin-top:auto;border-top:1px solid #2a4035;padding:16px 12px 0;color:#9cb1a5;font-size:12px;line-height:1.7}
        .main{min-width:0}.topbar{height:64px;background:#fff;border-bottom:1px solid #dce5e0;display:flex;align-items:center;justify-content:space-between;padding:0 28px;position:sticky;top:0;z-index:2}.topbar h1{font-size:18px;margin:0}.admin-controls{display:flex;align-items:center;gap:14px;color:#54645b}.admin-controls form{margin:0}
        .content{max-width:1440px;margin:0 auto;padding:26px 28px 48px}.flash{padding:12px 14px;border-radius:5px;margin-bottom:18px;background:#e8f5ed;color:#17663f}.flash.error{background:#fff0ee;color:#9b352a}.load-error{padding:12px 14px;background:#fff5db;color:#765713;border-radius:5px;margin-bottom:18px}
        .stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:28px}.stat{background:#fff;border:1px solid #dce5e0;border-radius:6px;padding:17px 18px}.stat-label{color:#708078;font-size:12px}.stat-value{font-size:25px;font-weight:700;margin-top:9px;color:#17211e}
        .section-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 14px}.section-head h2{margin:0;font-size:16px}.panel{background:#fff;border:1px solid #dce5e0;border-radius:6px;margin-bottom:22px;overflow:hidden}.panel-head{padding:15px 17px;border-bottom:1px solid #e5ebe7;display:flex;align-items:center;justify-content:space-between;gap:12px}.panel-head h2{font-size:15px;margin:0}.panel-body{padding:18px}
        .table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{text-align:left;padding:11px 13px;border-bottom:1px solid #edf1ee;vertical-align:top}th{font-size:11px;text-transform:uppercase;color:#738078;background:#f8faf9;font-weight:700}td{font-size:13px}tr:last-child td{border-bottom:0}.empty{text-align:center;color:#85928b;padding:30px}.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}.muted{color:#738078}.sub{display:block;color:#87948d;font-size:11px;margin-top:3px}.status{display:inline-block;padding:4px 8px;border-radius:4px;background:#edf2ef;color:#52635a;font-size:11px}.status.pending,.status.open,.status.processing,.status.warning{background:#fff3d9;color:#875e0b}.status.completed,.status.resolved,.status.active,.status.ok{background:#e7f5ec;color:#17663f}.status.failed,.status.rejected,.status.closed,.status.inactive,.status.error,.status.stale{background:#fcebea;color:#9b352a}
        .toolbar{display:flex;gap:8px;align-items:center}.toolbar input,.field input,.field textarea,.field select{border:1px solid #ccd8d1;border-radius:5px;padding:9px 11px;background:#fff;color:#17211e;min-width:0}.toolbar input{width:min(280px,55vw)}.field{display:grid;gap:7px;margin-bottom:15px}.field label{font-size:12px;font-weight:650;color:#4d5c54}.field input,.field select{height:40px;width:100%}.field textarea{width:100%;min-height:105px;resize:vertical;line-height:1.5}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 14px}.span-2{grid-column:1/-1}
        .detail-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px 24px}.detail-grid>div{display:grid;gap:5px;min-width:0}.detail-grid strong{font-weight:600;overflow-wrap:anywhere}.detail-heading{margin-top:22px}.trend-grid-layout{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:24px}.trend-panel{background:#fff;border:1px solid #dce5e0;border-radius:6px;padding:16px 18px;min-width:0}.trend-title{display:flex;justify-content:space-between;gap:12px;align-items:baseline;font-weight:650}.trend-title strong{font-size:14px}.trend-svg{display:block;width:100%;height:auto;margin-top:14px;overflow:visible}.trend-grid{fill:none;stroke:#e9efeb;stroke-width:1}.trend-foot{display:flex;justify-content:space-between;color:#748179;font-size:11px}.image-list{display:flex;flex-wrap:wrap;gap:10px}.image-item{display:grid;gap:5px;width:100px}.image-item img,.image-preview img{width:100%;aspect-ratio:1;object-fit:cover;border:1px solid #dce5e0;border-radius:4px}.image-preview{display:flex;gap:8px;flex-wrap:wrap}.image-preview img{width:76px}
        .pagination{display:flex;align-items:center;justify-content:flex-end;gap:6px;margin:12px 0 24px;color:#68776f;font-size:12px}.pagination a,.pagination span{min-width:34px;height:32px;padding:0 9px;border:1px solid #d5dfd9;border-radius:4px;display:inline-flex;align-items:center;justify-content:center;background:#fff}.pagination .current{background:#177b4d;border-color:#177b4d;color:#fff}.pagination .page-info{border:0;background:transparent}
        .modal-overlay{position:fixed;inset:0;z-index:20;background:rgba(18,31,25,.52);display:flex;align-items:center;justify-content:center;padding:24px}.modal-dialog{width:min(1120px,100%);max-height:calc(100vh - 48px);overflow:auto;background:#f3f6f4;border-radius:8px;box-shadow:0 20px 70px rgba(0,0,0,.28)}.modal-dialog .panel{margin:0;border:0;border-radius:8px}.modal-dialog .panel-head{position:sticky;top:0;background:#fff;z-index:1}.modal-close{font-size:24px;line-height:1;width:36px;height:36px;display:grid;place-items:center;border:1px solid #dce5e0;border-radius:4px;background:#fff}.trend-marker{stroke:#fff;stroke-width:2;cursor:crosshair;transition:r .12s}.trend-marker:hover{r:8}
        .trend-panel{position:relative}.trend-tooltip{display:none;position:absolute;z-index:4;max-width:calc(100% - 16px);padding:8px 10px;border-radius:4px;background:#17211e;color:#fff;font-size:12px;font-weight:600;line-height:1.4;box-shadow:0 4px 14px rgba(0,0,0,.2);pointer-events:none;white-space:nowrap}.trend-tooltip.visible{display:block}.trend-marker:focus{outline:none;stroke:#17211e;stroke-width:3;r:8}
        .btn{display:inline-flex;align-items:center;justify-content:center;min-height:35px;border:1px solid #ccd8d1;border-radius:5px;padding:0 12px;background:#fff;color:#27362e;font-weight:600;font-size:12px;cursor:pointer}.btn:hover{background:#f3f7f4}.btn.primary{border-color:#177b4d;background:#177b4d;color:#fff}.btn.primary:hover{background:#11663e}.btn.danger{color:#9b352a;border-color:#e8c7c3}.inline{display:inline}.inline-form{display:flex;gap:8px;align-items:center}.inline-form textarea{width:250px;min-height:56px}.ticket-content{white-space:normal;min-width:220px;max-width:380px;line-height:1.55}.address-box{overflow-wrap:anywhere}.actions{display:flex;gap:7px;align-items:center}.search-form{display:flex;gap:8px}
        @media(max-width:900px){.shell{grid-template-columns:184px minmax(0,1fr)}.content{padding:20px 18px 36px}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}}
        @media(max-width:640px){.shell{display:block}.sidebar{padding:12px;gap:10px}.brand{padding:4px 8px}.brand small{display:inline;margin-left:7px}.nav{display:flex;overflow:auto;gap:4px}.nav a{white-space:nowrap;padding:8px 10px}.side-bottom{display:none}.topbar{height:54px;padding:0 14px}.topbar h1{font-size:16px}.admin-controls{gap:8px;font-size:12px}.content{padding:16px 12px 32px}.stats{gap:8px}.stat{padding:13px}.stat-value{font-size:21px}.panel-body{padding:13px}.form-grid{grid-template-columns:1fr}.span-2{grid-column:auto}.section-head{align-items:flex-start;flex-direction:column}.search-form{width:100%}.search-form input{flex:1;min-width:0}.detail-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.trend-grid-layout{grid-template-columns:1fr}.inline-form{flex-wrap:wrap}.modal-overlay{padding:8px;align-items:flex-end}.modal-dialog{max-height:calc(100vh - 16px);border-radius:8px 8px 0 0}}
    </style>
    <link rel="stylesheet" href="ui-premium.css?v=147">
    <link rel="stylesheet" href="admin.css?v=20261027-2">
</head>
<body>
<div class="shell">
    <aside class="sidebar">
        <div class="brand">SPX CHAT <small>系统管理</small></div>
            <nav class="nav" aria-label="后台导航">
                <?php adminRenderNavigation($navGroups, $sections, $section); ?>
        </nav>
        <div class="side-bottom">数据库直连管理<br>操作账号：<?= adminEscape($admin['username']) ?></div>
    </aside>
    <main class="main">
        <header class="topbar">
            <h1><?= adminEscape($sections[$section]) ?></h1>
            <div class="admin-controls">
                <span><?= adminEscape($admin['username']) ?></span>
                <form method="post" action="logout.php"><input type="hidden" name="csrf" value="<?= adminEscape($csrf) ?>"><button class="btn" type="submit">退出</button></form>

                    <form method="post" class="form-grid">
                        <?= adminHidden($csrf, 'settings.mall_settle.save', 'settings') ?>
                        <div class="field"><label for="mall_settlement_fee_rate">结算手续费率（%）</label><input id="mall_settlement_fee_rate" name="mall_settlement_fee_rate" type="number" min="0" max="99" step="0.01" value="<?= adminEscape($data['mall_settle_fee_rate']) ?>" required></div>
                        <div class="field"><label for="mall_auto_confirm_days">发货后自动确认收货（天）</label><input id="mall_auto_confirm_days" name="mall_auto_confirm_days" type="number" min="1" max="99" step="1" value="<?= (int)$data['mall_auto_confirm_days'] ?>" required></div>
                        <p class="muted span-2">到店核销订单：核销满 24 小时自动结算。快递 / 数字订单：确认收货满 7 天自动结算。手续费率只影响之后创建的新订单，已下单订单沿用下单时的快照。</p>
                        <div><button class="btn primary" type="submit">保存结算参数</button></div>
                    </form>
            </div>
        </header>
        <div class="content">
            <?php if (is_array($flash)): ?><div class="flash <?= ($flash['type'] ?? '') === 'error' ? 'error' : '' ?>"><?= adminEscape($flash['message'] ?? '') ?></div><?php endif; ?>
            <?php if (!empty($data['load_error'])): ?><div class="load-error"><?= adminEscape($data['load_error']) ?></div><?php endif; ?>

            <?php if ($section === 'dashboard'): ?>
                <div class="stats">
                    <?php foreach (($data['stats'] ?? []) as $label => $value): ?>
                        <div class="stat"><div class="stat-label"><?= adminEscape($label) ?></div><div class="stat-value"><?= adminEscape(str_ends_with((string) $label, '总余额') || $label === '质押剩余额度' ? adminAmount4($value) : $value) ?></div></div>
                    <?php endforeach; ?>
                </div>
                <div class="trend-grid-layout">
                    <section class="trend-panel"><div class="trend-title"><strong>USDT 充值到账</strong><span class="muted">近 30 日每日到账</span></div><?= adminTrendSvg($data['trends'] ?? [], 'recharge_usdt', '#177b4d') ?><div class="trend-tooltip" role="status" aria-live="polite"></div><div class="trend-foot"><span><?= adminEscape($data['trends'][0]['day'] ?? '') ?></span><span><?= adminEscape($data['trends'][29]['day'] ?? '') ?></span></div></section>
                    <section class="trend-panel"><div class="trend-title"><strong>SPX001 质押</strong><span class="muted">近 30 日每日质押</span></div><?= adminTrendSvg($data['trends'] ?? [], 'staking_spx', '#c06a28') ?><div class="trend-tooltip" role="status" aria-live="polite"></div><div class="trend-foot"><span><?= adminEscape($data['trends'][0]['day'] ?? '') ?></span><span><?= adminEscape($data['trends'][29]['day'] ?? '') ?></span></div></section>
                </div>
                <?php $heartbeat = $data['heartbeat'] ?? null; $heartbeatFresh = $heartbeat && strtotime((string) $heartbeat['checked_at']) >= time() - 120; $heartbeatStatus = !$heartbeatFresh ? 'stale' : (string) $heartbeat['status']; ?>
                <section class="panel"><div class="panel-head"><h2>BSC 充值验证器</h2><span class="status <?= adminEscape($heartbeatStatus) ?>"><?= !$heartbeat ? '尚无运行记录' : (!$heartbeatFresh ? '心跳超时' : (($heartbeatStatus === 'ok') ? '运行正常' : (($heartbeatStatus === 'warning') ? '有待检查错误' : '异常'))) ?></span></div><div class="panel-body"><span class="muted">最近心跳：<?= adminEscape($heartbeat['checked_at'] ?? '—') ?></span><?php if (!empty($heartbeat['error_message'])): ?><div class="load-error" style="margin:12px 0 0"><?= adminEscape($heartbeat['error_message']) ?></div><?php endif; ?></div></section>
                <section class="panel">
                    <div class="panel-head"><h2>最近注册用户</h2><a class="btn" href="admin.php?section=users">查看用户</a></div>
                    <div class="table-wrap"><table><thead><tr><th>ID</th><th>账号</th><th>昵称</th><th>SPX ID</th><th>状态</th><th>注册时间</th></tr></thead><tbody>
                        <?php foreach (($data['recent'] ?? []) as $row): ?><tr><td><?= (int) $row['id'] ?></td><td><?= adminEscape($row['username']) ?></td><td><?= adminEscape($row['nickname']) ?></td><td><?= adminEscape($row['spx_id']) ?></td><td><span class="status <?= (int) $row['status'] === 1 ? 'active' : 'inactive' ?>"><?= (int) $row['status'] === 1 ? '正常' : '停用' ?></span></td><td><?= adminEscape($row['created_at']) ?></td></tr><?php endforeach; ?>
                        <?= empty($data['recent']) ? adminTableEmpty(6) : '' ?>
                    </tbody></table></div>
                </section>
            <?php elseif ($section === 'users'): ?>
                <div class="section-head"><h2>用户列表 <span class="muted">每页 30 条</span></h2><form class="search-form" method="get"><input type="hidden" name="section" value="users"><input name="q" value="<?= adminEscape($search) ?>" placeholder="账号、昵称、SPX ID 或邮箱"><button class="btn primary">搜索</button></form></div>
                <?php if (!empty($data['user_detail'])): $detail = $data['user_detail']; ?>
                    <div class="modal-overlay" id="user-detail-modal" role="dialog" aria-modal="true" aria-labelledby="user-detail-title"><div class="modal-dialog"><section class="panel"><div class="panel-head"><h2 id="user-detail-title">用户详情 · #<?= (int) $detail['id'] ?> <?= adminEscape($detail['username']) ?></h2><a class="modal-close" href="admin.php?section=users" aria-label="关闭用户详情">×</a></div><div class="panel-body">
                        <div class="detail-grid">
                            <div><span class="muted">昵称</span><strong><?= adminEscape($detail['nickname']) ?></strong></div><div><span class="muted">SPX ID</span><strong><?= adminEscape($detail['spx_id']) ?></strong></div>
                            <div><span class="muted">邮箱</span><strong><?= adminEscape($detail['email'] ?: '未绑定') ?></strong></div><div><span class="muted">邮箱验证</span><strong><?= adminEscape($detail['email_verified_at'] ?: '未验证') ?></strong></div>
                            <div><span class="muted">绑定 BSC 钱包</span><strong class="mono address-box"><?= adminEscape($detail['bsc_wallet_address'] ?: '未绑定') ?></strong></div><div><span class="muted">账号状态</span><strong><?= (int) $detail['status'] === 1 ? '正常' : '已停用' ?></strong></div>
                            <div><span class="muted">注册时间</span><strong><?= adminEscape($detail['created_at']) ?></strong></div><div><span class="muted">注册 IP</span><strong><?= adminEscape($detail['registration_ip'] ?: '—') ?></strong></div>
                            <div><span class="muted">设备</span><strong><?= adminEscape($detail['device_model'] ?: '—') ?></strong></div><div><span class="muted">Android 版本</span><strong><?= adminEscape($detail['android_version'] ?: '—') ?></strong></div>
                            <div><span class="muted">设备编号</span><strong class="mono"><?= adminEscape($detail['device_id'] ?: '—') ?></strong></div><div><span class="muted">应用版本</span><strong><?= adminEscape($detail['app_version'] ?: '—') ?></strong></div>
                        </div>
                        <?php if (adminCan('user_control')): ?><div class="actions detail-heading"><form method="post" class="inline-form"><?= adminHidden($csrf, 'user.save', 'users') ?><input type="hidden" name="user_id" value="<?= (int) $detail['id'] ?>"><select name="status"><option value="1" <?= (int) $detail['status'] === 1 ? 'selected' : '' ?>>启用</option><option value="0" <?= (int) $detail['status'] === 0 ? 'selected' : '' ?>>停用</option></select><input name="remark" maxlength="100" placeholder="用户备注" value="<?= adminEscape($detail['remark'] ?? '') ?>"><input name="reason" maxlength="500" placeholder="状态/备注操作原因" required><button class="btn primary">保存用户</button></form><form method="post" class="inline-form"><?= adminHidden($csrf, 'user.password.reset', 'users') ?><input type="hidden" name="user_id" value="<?= (int) $detail['id'] ?>"><input name="new_password" type="password" minlength="12" maxlength="200" placeholder="新密码至少 12 位" required><input name="reason" maxlength="500" placeholder="密码重置原因" required><button class="btn">重置密码</button></form></div><?php endif; ?>
                        <?php if (adminCan('wallet_address_control')): ?><div class="actions detail-heading"><form method="post" class="inline-form"><?= adminHidden($csrf, 'user.wallet.update', 'users') ?><input type="hidden" name="request_id" value="<?= bin2hex(random_bytes(16)) ?>"><input type="hidden" name="user_id" value="<?= (int) $detail['id'] ?>"><input name="wallet_address" maxlength="42" pattern="0x[0-9a-fA-F]{40}" placeholder="新的 BSC 钱包地址" value="<?= adminEscape($detail['bsc_wallet_address'] ?? '') ?>" required><input name="reason" maxlength="500" placeholder="修改钱包地址原因" required><button class="btn primary">修改绑定钱包</button></form></div><?php endif; ?>
                        <?php if (adminCan('wallets')): ?>
                            <div class="section-head detail-heading"><h2>钱包资产</h2></div>
                            <div class="table-wrap"><table><thead><tr><th>资产</th><th>当前余额</th><th>可质押余额</th><th>更新时间</th><th>余额操作</th></tr></thead><tbody>
                                <?php foreach (($data['user_assets'] ?? []) as $asset): ?><tr><td><?= adminEscape($asset['symbol']) ?> / <?= adminEscape($asset['chain']) ?></td><td class="mono"><?= ($asset['symbol']==='SPX001'?mallAdminMoney($asset['balance']):adminAmount4($asset['balance'])) ?></td><td class="mono"><?= in_array(adminEscape($asset['symbol']), ['SPX001','FISS'], true) ? adminAmount4($asset['stakeable_balance'] ?? '0') : '—' ?></td><td><?= adminEscape($asset['updated_at']) ?></td><td><form method="post" class="inline-form"><?= adminHidden($csrf, 'wallet.adjust', 'users') ?><input type="hidden" name="request_id" value="<?= bin2hex(random_bytes(16)) ?>"><input type="hidden" name="user_id" value="<?= (int) $detail['id'] ?>"><input type="hidden" name="symbol" value="<?= adminEscape($asset['symbol']) ?>"><select name="operation"><option value="add">增加</option><option value="subtract">扣减</option><option value="set">设置为</option></select><input name="amount" inputmode="decimal" placeholder="金额 / 目标余额" required><input name="reason" maxlength="500" placeholder="必填：调整原因" required><button class="btn primary">提交</button></form></td></tr><?php endforeach; ?>
                                <?= empty($data['user_assets']) ? adminTableEmpty(5) : '' ?>
                            </tbody></table></div>
                            <div class="section-head detail-heading"><h2>最近钱包流水</h2></div>
                            <div class="table-wrap"><table><thead><tr><th>类型</th><th>资产</th><th>金额</th><th>状态</th><th>哈希</th><th>时间</th></tr></thead><tbody>
                                 <?php foreach (($data['user_activity'] ?? []) as $activity): ?><tr><td><?= adminEscape(adminTransactionLabel((string) $activity['type'])) ?></td><td><?= adminEscape($activity['symbol']) ?> / <?= adminEscape($activity['chain']) ?></td><td><?= ($activity['symbol']==='SPX001'?mallAdminMoney($activity['amount']):adminAmount4($activity['amount'])) ?></td><td><?= adminEscape(adminStatusLabel((string) $activity['status'])) ?></td><td class="mono"><?= adminEscape($activity['tx_hash'] ?: '无') ?></td><td><?= adminEscape($activity['created_at']) ?></td></tr><?php endforeach; ?>
                                <?= empty($data['user_activity']) ? adminTableEmpty(6) : '' ?>
                            </tbody></table></div>
                        <?php endif; ?>
                    </div></section></div></div>
                <?php elseif (isset($_GET['detail_id'])): ?>
                    <div class="load-error">用户不存在。</div>
                <?php endif; ?>
                <section class="panel"><div class="table-wrap"><table><thead><tr><th>ID / 账号</th><th>SPX ID / 邮箱</th><th>绑定 BSC 钱包</th><th>状态</th><th>注册时间</th><th>管理</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?>
                        <tr>
                            <td><a href="admin.php?section=users&amp;detail_id=<?= (int) $row['id'] ?>">#<?= (int) $row['id'] ?> · <?= adminEscape($row['username']) ?></a><span class="sub"><?= adminEscape($row['nickname']) ?><?= $row['remark'] ? ' · ' . adminEscape($row['remark']) : '' ?></span></td>
                            <td><?= adminEscape($row['spx_id']) ?><span class="sub"><?= adminEscape($row['email'] ?: '无邮箱') ?></span></td>
                            <td class="mono"><?= adminEscape($row['bsc_wallet_address'] ?: '未绑定') ?></td>
                            <td><span class="status <?= (int) $row['status'] === 1 ? 'active' : 'inactive' ?>"><?= (int) $row['status'] === 1 ? '正常' : '停用' ?></span></td>
                            <td><?= adminEscape($row['created_at']) ?></td>
                            <td><a class="btn" href="admin.php?section=users&amp;detail_id=<?= (int) $row['id'] ?>">查看详情</a></td>
                        </tr>
                    <?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(6) : '' ?>
                </tbody></table></div></section>

            <?php elseif ($section === 'recharges'): ?>
                <div class="section-head"><h2>充值记录 <span class="muted">自动验证失败的充值会转入「充值复核」，核实链上到账后可一键入账</span></h2></div>
                <section class="panel"><div class="table-wrap"><table><thead><tr><th>用户</th><th>币种 / 金额</th><th>状态</th><th>交易哈希</th><th>用户钱包</th><th>收款地址</th><th>提交时间</th><th>失败原因 / 处理</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?></span></td><td><?= adminEscape($row['currency']) ?><span class="sub"><?= adminAmount4($row['amount']) ?></span></td><td><span class="status <?= adminEscape($row['status']) ?>"><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></span></td><td class="mono"><?= adminEscape($row['tx_hash'] ?: '无') ?></td><td class="mono"><?= adminEscape($row['wallet_address'] ?: '无') ?></td><td class="mono"><?= adminEscape($row['deposit_address'] ?: '无') ?></td><td><?= adminEscape($row['created_at']) ?></td><td><?= adminEscape($row['error_message'] ?: '无') ?><?php if ($row['status'] === 'failed' && strtoupper((string) $row['currency']) === 'USDT' && adminCan('recharges')): ?><form method="post" class="inline-form" onsubmit="return confirm('请先确认区块链浏览器显示该交易已成功并到达平台收款地址。确定后将立即给用户 USDT 余额入账，且不可重复操作。')"><?= adminHidden($csrf, 'recharge.manual.complete', 'recharges') ?><input type="hidden" name="record_id" value="<?= (int) $row['id'] ?>"><input type="hidden" name="request_id" value="<?= bin2hex(random_bytes(16)) ?>"><input name="reason" maxlength="500" placeholder="链上核验依据 / 处理原因" required><button class="btn primary">核验后补记到账</button></form><?php endif; ?><?php if (adminCan('wallets')): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'wallet.status.update', 'recharges') ?><input type="hidden" name="record_type" value="recharge"><input type="hidden" name="record_id" value="<?= (int) $row['id'] ?>"><select name="status"><option value="pending" <?= $row['status'] === 'pending' ? 'selected' : '' ?>>待处理</option><option value="completed" <?= $row['status'] === 'completed' ? 'selected' : '' ?>>已完成</option><option value="failed" <?= $row['status'] === 'failed' ? 'selected' : '' ?>>失败</option><option value="rejected" <?= $row['status'] === 'rejected' ? 'selected' : '' ?>>已拒绝</option><option value="cancelled" <?= $row['status'] === 'cancelled' ? 'selected' : '' ?>>已取消</option></select><input name="reason" maxlength="500" placeholder="仅改状态原因" required><button class="btn">仅改状态</button></form><?php endif; ?></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(8) : '' ?>
                </tbody></table></div></section>
                <?php elseif ($section === 'recharge_review'): ?>
                <div class="section-head"><h2>充值复核 <span class="muted">自动验证失败的充值集中在这里；核实链上确实到账后点「通过并到账」，走与自动入账相同的逻辑</span></h2></div>
                <section class="panel"><div class="table-wrap"><table><thead><tr><th>用户</th><th>币种 / 金额</th><th>失败原因</th><th>链上发起方</th><th>确认数</th><th>交易哈希</th><th>提交时间</th><th>操作</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?>
                    <tr>
                        <td><?= adminEscape($row['username']) ?><span class="sub">#<?= (int) $row['user_id'] ?></span></td>
                        <td><?= adminEscape($row['currency']) ?><span class="sub"><?= adminAmount4($row['amount']) ?></span></td>
                        <td class="ticket-content"><?= adminEscape($row['error_message'] ?: '—') ?></td>
                        <td class="mono"><?= adminEscape($row['audit_from'] ?: '—') ?><span class="sub">绑定：<?= adminEscape($row['bsc_wallet_address'] ?: '—') ?></span></td>
                        <td><?= adminEscape((string) ($row['audit_confirmations'] ?? '—')) ?></td>
                        <td class="mono"><a href="https://bscscan.com/tx/<?= adminEscape($row['tx_hash']) ?>" target="_blank" rel="noopener"><?= adminEscape(substr($row['tx_hash'], 0, 16)) ?>…</a></td>
                        <td><?= adminEscape($row['created_at']) ?></td>
                        <td>
                            <form method="post" class="inline-form" onsubmit="return confirm('确认已核实链上到账，按该金额入账？');">
                                <?= adminHidden($csrf, 'recharge.manual.complete', 'recharge_review') ?>
                                <input type="hidden" name="record_id" value="<?= (int) $row['id'] ?>">
                                <input type="hidden" name="request_id" value="<?= bin2hex(random_bytes(16)) ?>">
                                <input name="reason" maxlength="200" placeholder="审核原因（必填）" required>
                                <button class="btn primary">通过并到账</button>
                            </form>
                            <form method="post" class="inline-form" onsubmit="return confirm('确认拒绝该笔充值？');">
                                <?= adminHidden($csrf, 'recharge.review.reject', 'recharge_review') ?>
                                <input type="hidden" name="record_id" value="<?= (int) $row['id'] ?>">
                                <input type="hidden" name="request_id" value="<?= bin2hex(random_bytes(16)) ?>">
                                <input name="reason" maxlength="200" placeholder="拒绝原因（必填）" required>
                                <button class="btn">拒绝</button>
                            </form>
                        </td>
                    </tr>
                    <?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(8) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'withdrawals'): ?>
                <div class="section-head"><h2>提现审核 <span class="muted">通过后由 Python 提现服务自动打币；拒绝时退回全部冻结金额</span></h2></div>
                <?php if (adminCan('withdrawals')): ?><section class="panel"><div class="panel-body"><form id="batch-withdraw-approve" method="post" class="inline-form" onsubmit="return confirm('确认批量通过已勾选的提现提现吗？自动打币服务会逐笔发送链上转账。')"><?= adminHidden($csrf, 'withdraw.batch_approve', 'withdrawals') ?><input name="reason" maxlength="500" placeholder="批量审核原因" required><button class="btn primary">批量通过并自动打币</button></form></div></section><?php endif; ?>
                <section class="panel"><div class="table-wrap"><table><thead><tr><th><?php if (adminCan('withdrawals')): ?><input id="withdraw-select-all" type="checkbox" title="全选待处理提现"><?php endif; ?></th><th>用户</th><th>资产 / 金额</th><th>提现地址</th><th>状态</th><th>申请时间</th><th>审核操作</th></tr></thead><tbody>
                     <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?php if ($row['status'] === 'pending' && adminCan('withdrawals')): ?><input class="withdraw-select" form="batch-withdraw-approve" type="checkbox" name="transaction_ids[]" value="<?= (int) $row['id'] ?>"><?php endif; ?></td><td>#<?= (int) $row['user_id'] ?> · <?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?></span></td><td><?= adminEscape($row['symbol']) ?> / <?= adminEscape($row['chain']) ?><span class="sub">实际到账 <?= adminAmount4($row['amount']) ?><?php if (isset($row['fee_amount'])): ?> · 手续费 <?= adminAmount4($row['fee_amount']) ?><?php endif; ?></span></td><td class="mono"><?= adminEscape($row['address'] ?: '无') ?></td><td><span class="status <?= adminEscape($row['status']) ?>"><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></span><span class="sub"><?= adminEscape($row['tx_hash'] ?: '无') ?></span><?php if (!empty($row['error_message'])): ?><span class="sub">失败原因：<?= adminEscape($row['error_message']) ?></span><?php endif; ?></td><td><?= adminEscape($row['created_at']) ?></td><td><?php if ($row['status'] === 'pending'): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'withdraw.process', 'withdrawals') ?><input type="hidden" name="transaction_id" value="<?= (int) $row['id'] ?>"><input name="reason" maxlength="500" placeholder="审核原因" required><button class="btn primary" name="decision" value="approve">通过并自动打币</button><button class="btn danger" name="decision" value="reject">拒绝并退款</button></form><?php endif; ?><?php if (adminCan('wallets')): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'wallet.status.update', 'withdrawals') ?><input type="hidden" name="record_type" value="withdraw"><input type="hidden" name="record_id" value="<?= (int) $row['id'] ?>"><select name="status"><option value="pending" <?= $row['status'] === 'pending' ? 'selected' : '' ?>>待处理</option><option value="processing" <?= $row['status'] === 'processing' ? 'selected' : '' ?>>处理中</option><option value="completed" <?= $row['status'] === 'completed' ? 'selected' : '' ?>>已完成</option><option value="failed" <?= $row['status'] === 'failed' ? 'selected' : '' ?>>失败</option><option value="rejected" <?= $row['status'] === 'rejected' ? 'selected' : '' ?>>已拒绝</option><option value="cancelled" <?= $row['status'] === 'cancelled' ? 'selected' : '' ?>>已取消</option></select><input name="reason" maxlength="500" placeholder="仅改状态原因" required><button class="btn">仅改状态</button></form><?php endif; ?></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(8) : '' ?>
                </tbody></table></div></section>
                <script>
                    (function () {
                        var rows = <?= json_encode(array_values(array_map(static fn(array $row): array => ['id' => (int) $row['id']], $data['rows'] ?? [])), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) ?>;
                        var tables = document.querySelectorAll('table');
                        var table = null;
                        for (var index = 0; index < tables.length; index++) {
                            if ((tables[index].textContent || '').indexOf('审核操作') !== -1) { table = tables[index]; break; }
                        }
                        if (!table) return;
                        var header = table.querySelector('thead tr');
                        if (header && header.children.length > 1) {
                            var heading = document.createElement('th');
                            heading.textContent = '提现 ID';
                            header.insertBefore(heading, header.children[1]);
                        }
                        table.querySelectorAll('tbody tr').forEach(function (row, rowIndex) {
                            if (!rows[rowIndex] || row.children.length < 2) return;
                            var cell = document.createElement('td');
                            cell.className = 'mono';
                            cell.textContent = '#' + rows[rowIndex].id;
                            row.insertBefore(cell, row.children[1]);
                        });
                    }());
                </script>
                 <script>document.addEventListener('DOMContentLoaded',function(){var all=document.getElementById('withdraw-select-all');if(all)all.addEventListener('change',function(){document.querySelectorAll('.withdraw-select').forEach(function(box){box.checked=all.checked;});});var rows=<?= json_encode(array_values(array_map(static fn(array $row): array => ['id'=>(int) $row['id'],'status'=>(string) $row['status']], $data['rows'] ?? [])), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) ?>;var csrf=<?= json_encode($csrf, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) ?>;document.querySelectorAll('.table-wrap tbody tr').forEach(function(row,index){if(!rows[index]||rows[index].status!=='failed')return;var cell=row.lastElementChild;if(!cell)return;var form=document.createElement('form');form.method='post';form.className='inline-form';form.onsubmit=function(){return confirm('请确认该提现原交易未到账，再重新发送。重复发送可能造成重复付款。');};[['csrf',csrf],['action','withdraw.retry'],['section','withdrawals'],['transaction_id',String(rows[index].id)]].forEach(function(item){var input=document.createElement('input');input.type='hidden';input.name=item[0];input.value=item[1];form.appendChild(input);});var reason=document.createElement('input');reason.name='reason';reason.maxLength=500;reason.placeholder='重试原因';reason.required=true;form.appendChild(reason);var button=document.createElement('button');button.type='submit';button.className='btn primary';button.textContent='重试发送';form.appendChild(button);cell.appendChild(form);});});</script>
            <?php elseif ($section === 'transactions'): ?>
                <div class="section-head"><h2>钱包流水 <span class="muted">每页 30 条 · 支持筛选和导出</span></h2><a class="btn" href="admin.php?export=transactions">导出全部流水</a></div>
                <form class="filter-grid" method="get">
                    <input type="hidden" name="section" value="transactions">
                    <div class="field"><label for="wallet-search">用户、ID 或交易哈希</label><input id="wallet-search" name="q" value="<?= adminEscape($search) ?>" placeholder="输入关键词"></div>
                    <div class="field"><label for="wallet-type">流水类型</label><select id="wallet-type" name="wallet_type"><option value="">全部类型</option><?php foreach (require __DIR__ . '/admin_wallet_labels.php' as $value => $label): ?><option value="<?= adminEscape($value) ?>" <?= $walletType === $value ? 'selected' : '' ?>><?= adminEscape($label) ?></option><?php endforeach; ?></select></div>
                    <div class="field"><label for="wallet-status">状态</label><select id="wallet-status" name="wallet_status"><option value="">全部状态</option><?php foreach (['pending' => '待处理', 'processing' => '处理中', 'sending' => '发送中', 'completed' => '已完成', 'failed' => '失败', 'rejected' => '已拒绝', 'cancelled' => '已取消'] as $value => $label): ?><option value="<?= $value ?>" <?= $walletStatus === $value ? 'selected' : '' ?>><?= $label ?></option><?php endforeach; ?></select></div>
                    <div class="field"><label for="wallet-from">开始日期</label><input id="wallet-from" name="from" type="date" value="<?= adminEscape($walletFrom) ?>"></div>
                    <div class="field"><label for="wallet-to">结束日期</label><input id="wallet-to" name="to" type="date" value="<?= adminEscape($walletTo) ?>"></div>
                    <div class="filter-actions"><button class="btn primary" type="submit">筛选</button><a class="btn" href="admin.php?section=transactions">重置</a></div>
                </form>
                <section class="panel"><div class="table-wrap"><table class="wallet-table"><thead><tr><th>ID</th><th>用户</th><th>币种 / 链</th><th>类型</th><th>金额</th><th>手续费</th><th>状态</th><th>地址</th><th>交易哈希</th><th>时间</th></tr></thead><tbody>
                     <?php foreach (($data['rows'] ?? []) as $row): ?>
                     <?php $address = (string) ($row['address'] ?? ''); $txHash = (string) ($row['tx_hash'] ?? ''); ?>
                     <tr><td class="mono">#<?= (int) $row['id'] ?></td><td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?> · #<?= (int) $row['user_id'] ?></span></td><td><?= adminEscape($row['symbol']) ?> / <?= adminEscape($row['chain']) ?></td><td><?= adminEscape(adminTransactionLabel((string) $row['type'])) ?><span class="sub"><?= adminEscape((string) $row['type']) ?></span></td><td class="amount"><?= adminAmount4($row['amount']) ?></td><td class="amount"><?= adminAmount4($row['fee_amount'] ?? '0') ?></td><td><span class="status <?= adminEscape($row['status']) ?>"><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></span></td><td class="mono clip" title="<?= adminEscape($address ?: '无') ?>"><?= adminEscape($address ?: '无') ?><?php if ($address !== ''): ?><button class="copy-value" type="button" data-copy="<?= adminEscape($address) ?>">复制</button><?php endif; ?></td><td class="mono clip" title="<?= adminEscape($txHash ?: '无') ?>"><?= adminEscape($txHash ?: '无') ?><?php if ($txHash !== ''): ?><button class="copy-value" type="button" data-copy="<?= adminEscape($txHash) ?>">复制</button><?php endif; ?></td><td><?= adminEscape($row['created_at']) ?></td></tr>
                     <?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(10) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'exchanges'): ?>
                <section class="panel"><div class="panel-head"><h2>兑换记录 <span class="muted">每页 30 条 · 只读</span></h2></div><div class="table-wrap"><table><thead><tr><th>用户</th><th>USDT</th><th>SPX001</th><th>成交价</th><th>费率</th><th>状态</th><th>时间</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?></span></td><td><?= adminAmount4($row['usdt_amount']) ?></td><td><?= adminAmount4($row['spx_amount']) ?></td><td><?= adminAmount4($row['exchange_price']) ?></td><td><?= adminAmount4($row['fee_rate']) ?></td><td><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></td><td><?= adminEscape($row['created_at']) ?></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(7) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'staking'): ?>
                <?php foreach (($data['phases'] ?? []) as $phaseSymbol => $phase): $phaseRemaining = $phase['remaining_quota'] ?? '0'; ?>
                <?php $stakingStats = $data['staking_stats'] ?? ['active_people' => 0, 'active_amount' => '0', 'all_people' => 0, 'all_amount' => '0']; ?>
                <div class="stats">
                    <div class="stat"><div class="stat-label">当前质押人数</div><div class="stat-value"><?= number_format((int) $stakingStats['active_people']) ?></div><div class="sub">仍在质押中的用户</div></div>
                    <div class="stat"><div class="stat-label">当前质押总量</div><div class="stat-value"><?= adminAmount4($stakingStats['active_amount']) ?></div><div class="sub">SPX001</div></div>
                    <div class="stat"><div class="stat-label">历史参与人数</div><div class="stat-value"><?= number_format((int) $stakingStats['all_people']) ?></div><div class="sub">曾创建质押订单</div></div>
                    <div class="stat"><div class="stat-label">历史质押总量</div><div class="stat-value"><?= adminAmount4($stakingStats['all_amount']) ?></div><div class="sub">SPX001</div></div>
                    <div class="stat"><div class="stat-label">当前质押总量</div><div class="stat-value"><?= adminAmount4($stakingStats['active_amount_fiss'] ?? '0') ?></div><div class="sub">FISS</div></div>
                    <div class="stat"><div class="stat-label">历史质押总量</div><div class="stat-value"><?= adminAmount4($stakingStats['all_amount_fiss'] ?? '0') ?></div><div class="sub">FISS</div></div>
                </div>
                <?php if (adminCan('staking_plans')): ?><section class="panel"><div class="panel-head"><h2>质押奖励额度 · <?= adminEscape($phaseSymbol) ?></h2><span class="muted">新质押创建时预分配分红和邀请奖励</span></div><div class="panel-body"><div class="detail-grid"><div><span class="muted">当前期数</span><strong><?= adminEscape($phase['phase_no'] ?? '未开启') ?></strong></div><div><span class="muted">总额度</span><strong><?= adminAmount4($phase['total_quota'] ?? '0') ?> SPX001 积分</strong></div><div><span class="muted">已预分配</span><strong><?= adminAmount4($phase['reserved_quota'] ?? '0') ?> SPX001 积分</strong></div><div><span class="muted">已发放</span><strong><?= adminAmount4($phase['paid_quota'] ?? '0') ?> SPX001 积分</strong></div><div><span class="muted">剩余额度</span><strong><?= adminAmount4($phaseRemaining) ?> SPX001 积分</strong></div><div><span class="muted">状态</span><strong><?= !empty($phase) && (int) ($phase['status'] ?? 0) === 1 ? '已开启' : '未开启' ?></strong></div></div><form method="post" class="form-grid" style="margin-top:18px"><?= adminHidden($csrf, 'staking.phase.save', 'staking') ?><div class="field"><label>期数</label><input type="hidden" name="symbol" value="<?= adminEscape($phaseSymbol) ?>"><input name="phase_no" type="number" min="1" max="1000000" required value="<?= (int) ($phase['phase_no'] ?? 1) ?>"></div><div class="field"><label>期名称</label><input name="name" maxlength="100" required value="<?= adminEscape($phase['name'] ?? 'SPX001 第一期') ?>"></div><div class="field"><label>分红+邀请奖励总额度</label><input name="total_quota" inputmode="decimal" required value="<?= adminEscape($phase['total_quota'] ?? '20000000') ?>"></div><div class="field"><label>状态</label><select name="status"><option value="1" <?= !empty($phase) && (int) ($phase['status'] ?? 0) === 1 ? 'selected' : '' ?>>开启</option><option value="0" <?= empty($phase) || (int) ($phase['status'] ?? 0) !== 1 ? 'selected' : '' ?>>未开启</option></select></div><div><button class="btn primary">保存质押期配置</button></div></form></div></section><?php endif; ?><?php endforeach; ?>
                <?php if (adminCan('staking_plans')): ?>
                    <section class="panel"><div class="panel-head"><h2>质押方案设置 <span class="muted">App 展示与服务端最低金额校验使用同一配置</span></h2></div><div class="panel-body"><form method="post">
                        <?= adminHidden($csrf, 'staking.plans.save', 'staking') ?>
                        <div class="table-wrap"><table><thead><tr><th>方案名称</th><th>币种</th><th>锁仓天数</th><th>年化利率 %</th><th>最低数量</th><th>步长（整数倍）</th><th>单户上限（空=不限）</th><th>状态</th></tr></thead><tbody>
                            <?php foreach (($data['plans'] ?? []) as $planIndex => $plan): ?><tr><td><input name="plans[<?= (int) $planIndex ?>][name]" maxlength="100" required value="<?= adminEscape($plan['name']) ?>"><input type="hidden" name="plans[<?= (int) $planIndex ?>][id]" value="<?= (int) $plan['id'] ?>"></td><td><select name="plans[<?= (int) $planIndex ?>][symbol]"><option value="SPX001" <?= (string) ($plan['symbol'] ?? 'SPX001') === 'SPX001' ? 'selected' : '' ?>>SPX001</option><option value="FISS" <?= (string) ($plan['symbol'] ?? '') === 'FISS' ? 'selected' : '' ?>>FISS</option></select></td><td><input name="plans[<?= (int) $planIndex ?>][plan_days]" type="number" min="1" max="3650" required value="<?= (int) $plan['plan_days'] ?>"></td><td><input name="plans[<?= (int) $planIndex ?>][apy]" inputmode="decimal" required value="<?= adminEscape($plan['apy']) ?>"></td><td><input name="plans[<?= (int) $planIndex ?>][minimum_amount]" inputmode="decimal" required value="<?= adminEscape($plan['minimum_amount']) ?>"></td><td><input name="plans[<?= (int) $planIndex ?>][step_amount]" inputmode="decimal" value="<?= adminEscape((string) ($plan['step_amount'] ?? '0')) ?>" style="max-width:150px"></td><td><input name="plans[<?= (int) $planIndex ?>][max_per_user]" inputmode="decimal" placeholder="不限" value="<?= adminEscape((string) ($plan['max_per_user'] ?? '')) ?>" style="max-width:150px"></td><td><select name="plans[<?= (int) $planIndex ?>][status]"><option value="1" <?= (int) $plan['status'] === 1 ? 'selected' : '' ?>>启用</option><option value="0" <?= (int) $plan['status'] === 0 ? 'selected' : '' ?>>下架</option></select></td></tr><?php endforeach; ?>
                            <?php $newPlanIndex = count($data['plans'] ?? []); ?><tr><td><input name="plans[<?= $newPlanIndex ?>][name]" maxlength="100" placeholder="新增方案名称"><input type="hidden" name="plans[<?= $newPlanIndex ?>][id]" value="0"></td><td><select name="plans[<?= $newPlanIndex ?>][symbol]"><option value="SPX001">SPX001</option><option value="FISS">FISS</option></select></td><td><input name="plans[<?= $newPlanIndex ?>][plan_days]" type="number" min="1" max="3650" placeholder="天数"></td><td><input name="plans[<?= $newPlanIndex ?>][apy]" inputmode="decimal" placeholder="例如 8.2"></td><td><input name="plans[<?= $newPlanIndex ?>][minimum_amount]" inputmode="decimal" placeholder="最低金额"></td><td><input name="plans[<?= $newPlanIndex ?>][step_amount]" inputmode="decimal" placeholder="如 100000"></td><td><input name="plans[<?= $newPlanIndex ?>][max_per_user]" inputmode="decimal" placeholder="不限"></td><td><select name="plans[<?= $newPlanIndex ?>][status]"><option value="1">启用</option><option value="0">下架</option></select></td></tr>
                        </tbody></table></div><button class="btn primary">保存质押方案</button>
                    </form></div></section>
                <?php endif; ?>
                <?php $fissWallet = $data['fiss_wallet'] ?? []; ?>
                <section class="panel"><div class="panel-head"><h2>FISS 充值钱包与自动销毁 <span class="muted">用户充值 FISS 打到这个钱包，可按比例自动销毁</span></h2></div><div class="panel-body">
                    <form method="post">
                        <?= adminHidden($csrf, 'settings.fiss_wallet.save', 'staking') ?>
                        <div class="form-grid">
                            <div class="field"><label>FISS 充值钱包地址</label><input name="fiss_deposit_address" maxlength="42" spellcheck="false" value="<?= adminEscape($fissWallet['deposit'] ?? '') ?>" placeholder="0x..."><span class="sub">App「充值 FISS」展示的收款地址</span></div>
                            <div class="field"><label>自动销毁</label><select name="burn_sweep_enabled"><option value="1" <?= ($fissWallet['enabled'] ?? '1') === '1' ? 'selected' : '' ?>>开启</option><option value="0" <?= ($fissWallet['enabled'] ?? '') === '0' ? 'selected' : '' ?>>关闭</option></select><span class="sub">关闭后只收不烧，服务每 30 秒检查一次</span></div>
                            <div class="field"><label>销毁目标地址</label><input name="burn_address" maxlength="42" spellcheck="false" value="<?= adminEscape($fissWallet['target'] ?? '') ?>" placeholder="0x000000000000000000000000000000000000dEaD"><span class="sub">默认黑洞，可改成别的钱包</span></div>
                            <div class="field"><label>最小销毁数量</label><input name="burn_min_amount" value="<?= adminEscape($fissWallet['min'] ?? '10000') ?>"><span class="sub">余额低于该值不销毁，避免白耗 gas</span></div>
                            <div class="field"><label>自动销毁钱包（只读）</label><input value="<?= adminEscape($fissWallet['system'] ?? '') ?>" readonly><span class="sub">服务器持有该钱包私钥；把 FISS 转到这个地址，服务会自动按比例销毁</span></div>
                        </div>
                        <div><button class="btn primary">保存 FISS 钱包设置</button></div>
                    </form>
                </div></section>
                <?php if (adminCan('staking_plans')): $burnInfo = $data['burn'] ?? []; $burnTiers = $data['burn_tiers'] ?? []; $burnSet = $data['burn_settings'] ?? []; ?>
<section class="panel"><div class="panel-head"><h2>FISS 销毁档位 <span class="muted">起押门槛随链上销毁量自动下调 · 全部参数可改</span></h2></div><div class="panel-body">
    <div class="stats" style="margin-bottom:16px">
        <div class="stat"><div class="stat-label">黑洞地址余额</div><div class="stat-value"><?= adminAmount4($burnInfo['burn_raw'] ?? '0') ?></div><div class="sub">FISS · 真实链上余额</div></div>
        <div class="stat"><div class="stat-label">新增销毁</div><div class="stat-value"><?= adminAmount4($burnInfo['burn'] ?? '0') ?></div><div class="sub">已扣除基准线，用于判档</div></div>
        <div class="stat"><div class="stat-label">当前起押门槛</div><div class="stat-value"><?= adminAmount4($burnInfo['tier']['min_stake_amount'] ?? '0') ?></div><div class="sub">FISS</div></div>
        <div class="stat"><div class="stat-label">距下一档还差</div><div class="stat-value"><?= $burnInfo['remaining'] !== null ? adminAmount4($burnInfo['remaining']) : '已是最高档' ?></div><div class="sub">销毁量</div></div>
    </div>
    <form method="post">
        <?= adminHidden($csrf, 'burn.tiers.save', 'staking') ?>
        <div class="form-grid">
            <div class="field"><label>判档模式</label><select name="burn_mode">
                <option value="auto" <?= ($burnSet['mode'] ?? 'auto') === 'auto' ? 'selected' : '' ?>>自动（读链上销毁量）</option>
                <option value="manual" <?= ($burnSet['mode'] ?? '') === 'manual' ? 'selected' : '' ?>>手动指定</option>
            </select></div>
            <div class="field"><label>手动销毁量</label><input name="burn_manual_threshold" value="<?= adminEscape($burnSet['manual'] ?? '0') ?>"><span class="sub">手动模式下用这个值判档</span></div>
            <div class="field"><label>销毁基准线</label><input name="burn_baseline" value="<?= adminEscape($burnSet['baseline'] ?? '0') ?>"><span class="sub">阶梯从该值起算，黑洞地址已有余额填这里</span></div>
        </div>
        <div class="table-wrap"><table><thead><tr><th>累计新增销毁 ≥</th><th>起押金额</th><th>状态</th></tr></thead><tbody>
            <?php foreach ($burnTiers as $tier): ?>
            <tr>
                <td><input name="tier_threshold[]" value="<?= adminEscape((string) $tier['threshold_amount']) ?>"></td>
                <td><input name="tier_min[]" value="<?= adminEscape((string) $tier['min_stake_amount']) ?>"></td>
                <td><select name="tier_status[]"><option value="1" <?= (int) $tier['status'] === 1 ? 'selected' : '' ?>>启用</option><option value="0" <?= (int) $tier['status'] === 0 ? 'selected' : '' ?>>停用</option></select></td>
            </tr>
            <?php endforeach; ?>
            <tr>
                <td><input name="tier_threshold[]" placeholder="新增档位，如 400000000"></td>
                <td><input name="tier_min[]" placeholder="如 50000"></td>
                <td><select name="tier_status[]"><option value="1">启用</option><option value="0">停用</option></select></td>
            </tr>
        </tbody></table></div>
        <button class="btn primary">保存销毁档位</button>
    </form>
</div></section>
<?php endif; ?>
<section class="panel"><div class="panel-head"><h2>质押订单 <span class="muted">每页 30 条 · 点击订单查看详情</span></h2><a class="btn" href="admin.php?export=staking">导出质押订单 CSV</a></div><div class="table-wrap"><table><thead><tr><th>订单</th><th>用户</th><th>方案 / 周期</th><th>金额</th><th>APY</th><th>状态</th><th>时间</th><th>操作</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td>#<?= (int) $row['id'] ?><span class="sub">期数 <?= (int) ($row['phase_id'] ?? 0) ?></span></td><td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?> · <?= adminEscape($row['spx_id'] ?? '') ?></span></td><td><?= adminEscape($row['plan_name'] ?: '质押方案') ?><span class="sub"><?= (int) $row['plan_days'] ?> 天 · <?= adminEscape($row['phase_name'] ?? '未分期') ?></span></td><td><?= adminAmount4($row['amount']) ?> <span class="sub"><?= adminEscape($row['symbol'] ?? 'SPX001') ?></span></td><td><?= adminAmount4($row['apy']) ?>%</td><td><span class="status <?= adminEscape($row['status']) ?>"><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></span></td><td><?= adminEscape($row['created_at']) ?></td><td><a class="btn" href="admin.php?section=staking&amp;detail_id=<?= (int) $row['id'] ?>">查看详情</a></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(8) : '' ?>
                </tbody></table></div></section>
                <?php if (!empty($data['staking_detail'])): $detail = $data['staking_detail']; ?>
                    <div class="modal-overlay" id="staking-detail-modal" role="dialog" aria-modal="true" aria-labelledby="staking-detail-title"><div class="modal-dialog"><section class="panel"><div class="panel-head"><h2 id="staking-detail-title">质押订单详情 · #<?= (int) $detail['id'] ?></h2><a class="modal-close" href="admin.php?section=staking" aria-label="关闭质押详情">×</a></div><div class="panel-body"><div class="detail-grid">
                        <div><span class="muted">用户</span><strong><?= adminEscape($detail['username']) ?> · <?= adminEscape($detail['nickname']) ?></strong></div><div><span class="muted">邮箱</span><strong><?= adminEscape($detail['email'] ?? '') ?></strong></div><div><span class="muted">SPX ID</span><strong><?= adminEscape($detail['spx_id'] ?? '') ?></strong></div><div><span class="muted">状态</span><strong><?= adminEscape(adminStatusLabel((string) $detail['status'])) ?></strong></div>
                        <div><span class="muted">质押方案</span><strong><?= adminEscape($detail['plan_name'] ?? '质押方案') ?></strong></div><div><span class="muted">质押期数</span><strong><?= adminEscape($detail['phase_name'] ?? '未分期') ?></strong></div><div><span class="muted">锁仓期限</span><strong><?= (int) $detail['plan_days'] ?> 天</strong></div><div><span class="muted">年化收益率</span><strong><?= adminAmount4($detail['apy']) ?>%</strong></div>
                        <div><span class="muted">质押数量</span><strong><?= adminAmount4($detail['amount']) ?> <?= adminEscape($detail['symbol'] ?? 'SPX001') ?></strong></div><div><span class="muted">预计分红</span><strong><?= adminAmount4($detail['expected_reward']) ?> <?= adminEscape($detail['symbol'] ?? 'SPX001') ?></strong></div><div><span class="muted">已支付分红</span><strong><?= adminAmount4($detail['paid_dividend']) ?> <?= adminEscape($detail['symbol'] ?? 'SPX001') ?></strong></div><div><span class="muted">邀请奖励</span><strong><?= adminAmount4($detail['referral_reward']) ?> <?= adminEscape($detail['symbol'] ?? 'SPX001') ?></strong></div>
                        <div><span class="muted">创建时间</span><strong><?= adminEscape($detail['created_at']) ?></strong></div>
                    </div><div class="section-head detail-heading"><h2>分红明细</h2></div><div class="table-wrap"><table><thead><tr><th>期数</th><th>分红数量</th><th>发放时间</th></tr></thead><tbody><?php foreach (($data['staking_detail_dividends'] ?? []) as $dividend): ?><tr><td>第 <?= (int) $dividend['period_no'] ?> 期</td><td><?= adminAmount4($dividend['amount']) ?> <?= adminEscape($detail['symbol'] ?? 'SPX001') ?></td><td><?= adminEscape($dividend['created_at']) ?></td></tr><?php endforeach; ?><?= empty($data['staking_detail_dividends']) ? adminTableEmpty(3) : '' ?></tbody></table></div></div></section></div></div>
                <?php endif; ?>
            <?php elseif ($section === 'versions'): ?>
                <?php
                $vTotals = $data['version_totals'] ?? [];
                $vDist = $data['version_dist'] ?? [];
                $vPlat = $data['version_platform'] ?? [];
                $vDaily = $data['version_daily'] ?? [];
                $vUsers = $data['version_users'] ?? [];
                $vTotal = max(1, (int) ($vTotals['users'] ?? 0));
                $vCurrentCode = 0;
                foreach ($vDist as $row) { if ($vCurrentCode === 0) $vCurrentCode = (int) $row['version_code']; }
                ?>
                <div class="stats" style="margin-bottom:16px">
                    <div class="stat"><div class="stat-label">统计用户</div><div class="stat-value"><?= number_format((int) ($vTotals['users'] ?? 0)) ?></div><div class="sub">有上报过版本的用户</div></div>
                    <div class="stat"><div class="stat-label">近 7 天活跃</div><div class="stat-value"><?= number_format((int) ($vTotals['active7'] ?? 0)) ?></div><div class="sub">7 天内用过 App</div></div>
                    <div class="stat"><div class="stat-label">近 24 小时</div><div class="stat-value"><?= number_format((int) ($vTotals['active1'] ?? 0)) ?></div><div class="sub">今天上报过</div></div>
                    <div class="stat"><div class="stat-label">最新版本用户</div><div class="stat-value"><?= number_format((int) (($vDist[0]['users'] ?? 0))) ?></div><div class="sub"><?= adminEscape(($vDist[0]['version_name'] ?? '') !== '' ? $vDist[0]['version_name'] : 'v' . ($vDist[0]['version_code'] ?? '')) ?></div></div>
                </div>

                <section class="panel"><div class="panel-head"><h2>版本分布 <span class="muted">按用户最近一次上报的版本统计</span></h2></div><div class="panel-body">
                    <?php if (!$vDist): ?><p class="muted">还没有数据，等用户打开 App 后会自动采集。</p><?php else: ?>
                    <table><thead><tr><th style="width:120px">版本</th><th>占比</th><th style="width:110px">用户数</th><th style="width:170px">最近活跃</th></tr></thead><tbody>
                        <?php foreach ($vDist as $row): $name = ($row['version_name'] ?? '') !== '' ? $row['version_name'] : 'v' . $row['version_code']; $pct = round(100 * (int) $row['users'] / $vTotal, 1); ?>
                        <tr>
                            <td><strong><?= adminEscape($name) ?></strong><span class="sub">代码 <?= (int) $row['version_code'] ?></span></td>
                            <td><div style="background:#e8efe9;border-radius:6px;height:18px;position:relative"><div style="background:#2f6f4f;height:18px;border-radius:6px;width:<?= max(1.5, $pct) ?>%"></div><span style="position:absolute;left:8px;top:0;line-height:18px;font-size:12px;color:#123"><?= $pct ?>%</span></div></td>
                            <td><?= number_format((int) $row['users']) ?></td>
                            <td><?= adminEscape((string) $row['last_seen']) ?></td>
                        </tr>
                        <?php endforeach; ?>
                    </tbody></table>
                    <?php endif; ?>
                    <div class="muted" style="margin-top:12px">平台：<?php foreach ($vPlat as $p): ?><?= adminEscape($p['platform']) ?> <?= number_format((int) $p['users']) ?> · <?php endforeach; ?>（iOS 需客户端上报后才会出现）</div>
                </div></section>

                <section class="panel"><div class="panel-head"><h2>近 14 天趋势 <span class="muted">每天每个版本的使用人数</span></h2></div><div class="panel-body">
                    <?php if (!$vDaily): ?><p class="muted">暂无趋势数据。</p><?php else:
                        $byDate = [];
                        foreach ($vDaily as $row) { $byDate[$row['stat_date']][(int) $row['version_code']] = (int) $row['users']; }
                        $vCodes = [];
                        foreach ($vDaily as $row) { $vCodes[(int) $row['version_code']] = true; }
                        $vCodes = array_keys($vCodes); rsort($vCodes);
                    ?>
                    <div class="table-wrap"><table><thead><tr><th>日期</th><?php foreach ($vCodes as $code): ?><th>v<?= $code ?></th><?php endforeach; ?><th>合计</th></tr></thead><tbody>
                        <?php foreach ($byDate as $day => $codes): $sum = array_sum($codes); ?>
                        <tr><td><?= adminEscape($day) ?></td><?php foreach ($vCodes as $code): ?><td><?= (int) ($codes[$code] ?? 0) ?></td><?php endforeach; ?><td><strong><?= $sum ?></strong></td></tr>
                        <?php endforeach; ?>
                    </tbody></table></div>
                    <?php endif; ?>
                </div></section>

                <section class="panel"><div class="panel-head"><h2>用户版本明细 <span class="muted">最近 200 条</span></h2></div><div class="table-wrap"><table><thead><tr><th>用户</th><th>版本</th><th>平台</th><th>首次上报</th><th>最近上报</th><th>IP</th></tr></thead><tbody>
                    <?php foreach ($vUsers as $row): $name = ($row['version_name'] ?? '') !== '' ? $row['version_name'] : 'v' . $row['version_code']; ?>
                    <tr>
                        <td><?= adminEscape((string) ($row['username'] ?? ('#' . $row['user_id']))) ?><span class="sub"><?= adminEscape((string) ($row['nickname'] ?? '')) ?> · #<?= (int) $row['user_id'] ?></span></td>
                        <td><?= adminEscape($name) ?></td>
                        <td><?= adminEscape((string) $row['platform']) ?></td>
                        <td><?= adminEscape((string) $row['first_seen_at']) ?></td>
                        <td><?= adminEscape((string) $row['last_seen_at']) ?></td>
                        <td class="mono"><?= adminEscape((string) ($row['last_ip'] ?? '')) ?></td>
                    </tr>
                    <?php endforeach; ?>
                    <?= empty($vUsers) ? adminTableEmpty(6) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'referrals'): ?>
                <section class="panel"><div class="panel-head"><h2>推荐奖励 <span class="muted">每页 30 条 · 只读</span></h2></div><div class="table-wrap"><table><thead><tr><th>推荐人</th><th>被推荐人</th><th>质押金额</th><th>奖励金额</th><th>时间</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['referrer_username']) ?><span class="sub"><?= adminEscape($row['referrer_nickname']) ?></span></td><td><?= adminEscape($row['staker_username']) ?><span class="sub"><?= adminEscape($row['staker_nickname']) ?></span></td><td><?= adminAmount4($row['staked_amount']) ?></td><td><?= adminAmount4($row['reward_amount']) ?></td><td><?= adminEscape($row['created_at']) ?></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(5) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'merchants'): ?>
                <?php mallAdminRender($pdo, $csrf); ?>
            <?php elseif ($section === 'products'): ?>
                <?php $edit = $data['edit'] ?? null; ?>
                <section class="panel"><div class="panel-head"><h2><?= $edit ? '编辑商品' : '新增商品' ?></h2><?php if ($edit): ?><a class="btn" href="admin.php?section=products">新建商品</a><?php endif; ?></div><div class="panel-body">
                    <form method="post" class="form-grid" enctype="multipart/form-data">
                        <?= adminHidden($csrf, 'product.save', 'products') ?>
                        <input type="hidden" name="id" value="<?= (int) ($edit['id'] ?? 0) ?>">
                        <div class="field"><label for="name">商品名称</label><input id="name" name="name" maxlength="150" required value="<?= adminEscape($edit['name'] ?? '') ?>"></div>
                        <div class="field"><label for="product_type">商品类型</label><select id="product_type" name="product_type"><option value="digital" <?= ($edit['product_type'] ?? 'digital') === 'digital' ? 'selected' : '' ?>>数字商品</option><option value="physical" <?= ($edit['product_type'] ?? '') === 'physical' ? 'selected' : '' ?>>实物商品（需收货地址和物流）</option></select></div>
                        <div class="field"><label for="price">SPX 价格</label><input id="price" name="spx_price" inputmode="decimal" required value="<?= adminEscape($edit['spx_price'] ?? '0') ?>"></div>
                        <div class="field"><label for="stock">总库存（有规格时自动汇总）</label><input id="stock" name="stock" type="number" min="0" step="1" required value="<?= (int) ($edit['stock'] ?? 0) ?>"></div>
                        <div class="field"><label for="sort_order">排序值</label><input id="sort_order" name="sort_order" type="number" step="1" value="<?= (int) ($edit['sort_order'] ?? 0) ?>"></div>
                        <div class="field"><label for="image_url">主图 URL（可选）</label><input id="image_url" name="image_url" type="url" maxlength="500" value="<?= adminEscape($edit['image_url'] ?? '') ?>"></div>
                        <div class="field"><label for="images">上传商品图片（每张不超过 5 MB，最多保留 8 张）</label><input id="images" name="images[]" type="file" accept="image/jpeg,image/png,image/webp,image/gif" multiple><div id="image-preview" class="image-preview"></div></div>
                        <div class="field span-2"><label for="variants">商品规格（每行：规格名称|SPX价格|库存；没有规格可留空）</label><textarea id="variants" name="variants" maxlength="5000" placeholder="红色 M|120|8&#10;蓝色 L|125|5"><?= adminEscape(adminVariantLines($edit['variants'] ?? '')) ?></textarea></div>
                        <?php if (!empty($data['edit_images'])): ?><div class="field span-2"><label>现有图库（勾选移除）</label><div class="image-list"><?php foreach ($data['edit_images'] as $image): ?><label class="image-item"><img src="<?= adminEscape($image['image_url']) ?>" alt="商品图片"><span><input type="checkbox" name="remove_image_ids[]" value="<?= (int) $image['id'] ?>"> 移除此图</span></label><?php endforeach; ?></div></div><?php endif; ?>
                        <div class="field"><label for="status">状态</label><select id="status" name="status"><option value="1" <?= (int) ($edit['status'] ?? 1) === 1 ? 'selected' : '' ?>>上架</option><option value="0" <?= (int) ($edit['status'] ?? 1) === 0 ? 'selected' : '' ?>>下架</option></select></div>
                        <div class="field span-2"><label for="description">商品描述</label><textarea id="description" name="description" maxlength="10000"><?= adminEscape($edit['description'] ?? '') ?></textarea></div>
                        <div><button class="btn primary" type="submit">保存商品</button></div>
                    </form>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>商品列表</h2></div><div class="table-wrap"><table><thead><tr><th>ID</th><th>名称</th><th>价格</th><th>库存</th><th>状态</th><th>排序</th><th>操作</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= (int) $row['id'] ?></td><td><?php if (!empty($row['image_url'])): ?><img src="<?= adminEscape($row['image_url']) ?>" alt="" style="width:42px;height:42px;object-fit:cover;vertical-align:middle;margin-right:8px;border-radius:3px"><?php endif; ?><?= adminEscape($row['name']) ?></td><td><?= mallAdminMoney($row['spx_price']) ?></td><td><?= (int) $row['stock'] ?></td><td><span class="status <?= (int) $row['status'] === 1 ? 'active' : 'inactive' ?>"><?= (int) $row['status'] === 1 ? '上架' : '下架' ?></span></td><td><?= (int) $row['sort_order'] ?></td><td><a class="btn" href="admin.php?section=products&amp;edit=<?= (int) $row['id'] ?>">编辑</a></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(7) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'orders'): ?>
                <div class="section-head"><h2>商城订单 <span class="muted">实物订单可登记承运商和物流单号，用户订单页同步可见</span></h2></div>
                <section class="panel"><div class="table-wrap"><table><thead><tr><th>订单</th><th>用户</th><th>商品 / 规格</th><th>数量</th><th>SPX 金额</th><th>收货地址 / 物流</th><th>状态</th><th>处理</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): $shipping = json_decode((string) ($row['shipping_address'] ?? ''), true) ?: []; ?><tr>
                        <td>#<?= (int) $row['id'] ?><span class="sub"><?= adminEscape($row['created_at']) ?></span></td>
                        <td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?> · <?= $row['order_type'] === 'physical' ? '实物' : '数字商品' ?></span></td>
                        <td><?= adminEscape($row['product_name']) ?><span class="sub"><?= adminEscape($row['variant_name'] ?: '标准规格') ?></span></td>
                        <td><?= (int) $row['quantity'] ?></td><td><?= mallAdminMoney($row['total_spx']) ?></td>
                        <td class="ticket-content"><?php if ($row['order_type'] === 'physical'): ?><?= adminEscape(($shipping['name'] ?? '') . ' · ' . ($shipping['phone'] ?? '')) ?><span class="sub"><?= adminEscape(($shipping['province'] ?? '') . ($shipping['city'] ?? '') . ($shipping['district'] ?? '') . ($shipping['detail'] ?? '')) ?></span><?php if (!empty($row['tracking_number'])): ?><span class="sub"><?= adminEscape($row['carrier']) ?> · <?= adminEscape($row['tracking_number']) ?></span><?php endif; ?><?php else: ?>无需配送<?php endif; ?></td>
                        <td><span class="status <?= adminEscape($row['status']) ?>"><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></span><span class="sub"><?= adminEscape($row['shipped_at'] ?: '') ?></span></td>
                        <td><?php if ($row['status'] === 'pending' && $row['order_type'] === 'physical'): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'order.ship', 'orders') ?><input type="hidden" name="order_id" value="<?= (int) $row['id'] ?>"><input name="carrier" maxlength="100" placeholder="承运商" required><input name="tracking_number" maxlength="120" placeholder="物流单号" required><input name="reason" maxlength="500" placeholder="发货备注/原因" required><button class="btn primary">确认发货</button></form><form method="post" class="inline-form"><?= adminHidden($csrf, 'order.process', 'orders') ?><input type="hidden" name="order_id" value="<?= (int) $row['id'] ?>"><input name="reason" maxlength="500" placeholder="取消原因" required><button class="btn danger" name="decision" value="cancel">取消退款</button></form><?php elseif ($row['status'] === 'pending'): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'order.process', 'orders') ?><input type="hidden" name="order_id" value="<?= (int) $row['id'] ?>"><input name="reason" maxlength="500" placeholder="处理原因" required><button class="btn primary" name="decision" value="complete">完成</button><button class="btn danger" name="decision" value="cancel">取消退款</button></form><?php else: ?>已处理<?php endif; ?></td>
                    </tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(8) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'tickets'): ?>
                <div class="section-head"><h2>客服工单 <span class="muted">每页 30 条</span></h2><a class="btn" href="admin.php?export=tickets">导出工单 CSV</a></div>
                <section class="panel"><div class="table-wrap"><table><thead><tr><th>用户</th><th>主题 / 内容</th><th>状态</th><th>回复与处理</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['created_at']) ?></span></td><td class="ticket-content"><strong><?= adminEscape($row['subject']) ?></strong><div><?= nl2br(adminEscape($row['content'])) ?></div></td><td><span class="status <?= adminEscape($row['status']) ?>"><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></span></td><td><form method="post" class="inline-form"><?= adminHidden($csrf, 'ticket.save', 'tickets') ?><input type="hidden" name="ticket_id" value="<?= (int) $row['id'] ?>"><textarea name="admin_reply" maxlength="10000" required placeholder="填写回复"><?= adminEscape($row['admin_reply'] ?? '') ?></textarea><select name="status"><option value="open" <?= $row['status'] === 'open' ? 'selected' : '' ?>>待处理</option><option value="processing" <?= $row['status'] === 'processing' ? 'selected' : '' ?>>处理中</option><option value="resolved" <?= $row['status'] === 'resolved' ? 'selected' : '' ?>>已解决</option><option value="closed" <?= $row['status'] === 'closed' ? 'selected' : '' ?>>已关闭</option></select><button class="btn primary">保存</button></form></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(4) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'questions'): ?>
                <section class="panel"><div class="panel-head"><h2>用户问题 <span class="muted">每页 30 条</span></h2></div><div class="table-wrap"><table><thead><tr><th>用户 / 问题</th><th>时间</th><th>状态</th><th>答复</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?></span><div class="ticket-content"><?= nl2br(adminEscape($row['question'])) ?></div></td><td><?= adminEscape($row['created_at']) ?></td><td><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></td><td><form method="post" class="inline-form"><?= adminHidden($csrf, 'question.save', 'questions') ?><input type="hidden" name="question_id" value="<?= (int) $row['id'] ?>"><textarea name="answer" maxlength="10000" required placeholder="答复内容"><?= adminEscape($row['answer'] ?? '') ?></textarea><select name="status"><option value="pending" <?= $row['status'] === 'pending' ? 'selected' : '' ?>>待处理</option><option value="answered" <?= $row['status'] === 'answered' ? 'selected' : '' ?>>已答复</option><option value="closed" <?= $row['status'] === 'closed' ? 'selected' : '' ?>>关闭</option></select><button class="btn primary">保存</button></form></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(4) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'feedback'): ?>
                <section class="panel"><div class="panel-head"><h2>用户反馈 <span class="muted">每页 30 条</span></h2></div><div class="table-wrap"><table><thead><tr><th>用户</th><th>反馈内容</th><th>联系方式</th><th>状态</th><th>处理</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['username']) ?><span class="sub"><?= adminEscape($row['nickname']) ?> · <?= adminEscape($row['created_at']) ?></span></td><td class="ticket-content"><?= nl2br(adminEscape($row['content'])) ?></td><td><?= adminEscape($row['contact'] ?: '—') ?></td><td><?= adminEscape(adminStatusLabel((string) $row['status'])) ?></td><td><form method="post" class="inline-form"><?= adminHidden($csrf, 'feedback.save', 'feedback') ?><input type="hidden" name="feedback_id" value="<?= (int) $row['id'] ?>"><select name="status"><option value="pending" <?= $row['status'] === 'pending' ? 'selected' : '' ?>>待处理</option><option value="reviewed" <?= $row['status'] === 'reviewed' ? 'selected' : '' ?>>已查看</option><option value="resolved" <?= $row['status'] === 'resolved' ? 'selected' : '' ?>>已解决</option></select><button class="btn primary">更新</button></form></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(5) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'faqs'): ?>
                <?php $editFaq = $data['edit_faq'] ?? null; ?>
                <section class="panel"><div class="panel-head"><h2><?= $editFaq ? '编辑 FAQ' : '新增 FAQ' ?></h2><?php if ($editFaq): ?><a class="btn" href="admin.php?section=faqs">新增</a><?php endif; ?></div><div class="panel-body"><form method="post" class="form-grid">
                    <?= adminHidden($csrf, 'faq.save', 'faqs') ?><input type="hidden" name="faq_id" value="<?= (int) ($editFaq['id'] ?? 0) ?>">
                    <div class="field"><label>分类</label><input name="category" maxlength="50" required value="<?= adminEscape($editFaq['category'] ?? '') ?>"></div>
                    <div class="field"><label>排序</label><input name="sort_order" type="number" step="1" value="<?= (int) ($editFaq['sort_order'] ?? 0) ?>"></div>
                    <div class="field"><label>问题</label><input name="question" maxlength="255" required value="<?= adminEscape($editFaq['question'] ?? '') ?>"></div>
                    <div class="field"><label>状态</label><select name="status"><option value="1" <?= (int) ($editFaq['status'] ?? 1) === 1 ? 'selected' : '' ?>>启用</option><option value="0" <?= (int) ($editFaq['status'] ?? 1) === 0 ? 'selected' : '' ?>>隐藏</option></select></div>
                    <div class="field span-2"><label>答案</label><textarea name="answer" maxlength="10000" required><?= adminEscape($editFaq['answer'] ?? '') ?></textarea></div><div><button class="btn primary">保存 FAQ</button></div>
                </form></div></section>
                <section class="panel"><div class="table-wrap"><table><thead><tr><th>分类</th><th>问题</th><th>状态</th><th>排序</th><th>操作</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['category']) ?></td><td><?= adminEscape($row['question']) ?></td><td><?= (int) $row['status'] === 1 ? '启用' : '隐藏' ?></td><td><?= (int) $row['sort_order'] ?></td><td><a class="btn" href="admin.php?section=faqs&amp;faq_id=<?= (int) $row['id'] ?>">编辑</a></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(5) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'groups'): ?>
                <section class="panel"><div class="panel-head"><h2>群组管理 <span class="muted">修改群名、公告和成员</span></h2></div><div class="table-wrap"><table><thead><tr><th>ID / 群号</th><th>群组</th><th>人数</th><th>公告</th><th>保存修改</th><th>成员</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td>#<?= (int) $row['id'] ?><span class="sub"><?= adminEscape($row['group_code'] ?: '—') ?></span></td><td><?= adminEscape($row['name']) ?><span class="sub"><?= adminEscape($row['created_at']) ?></span></td><td><?= (int) $row['member_count'] ?></td><td class="ticket-content"><?= nl2br(adminEscape($row['announcement'] ?? '')) ?></td><td><form method="post" class="inline-form"><?= adminHidden($csrf, 'group.save', 'groups') ?><input type="hidden" name="group_id" value="<?= (int) $row['id'] ?>"><input name="name" maxlength="100" required value="<?= adminEscape($row['name']) ?>"><textarea name="announcement" maxlength="5000" placeholder="群公告"><?= adminEscape($row['announcement'] ?? '') ?></textarea><button class="btn primary">保存</button></form></td><td><a class="btn" href="admin.php?section=groups&amp;group_id=<?= (int) $row['id'] ?>">查看成员</a></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(6) : '' ?>
                </tbody></table></div></section>
                <?php if (!empty($data['selected_group'])): ?>
                    <section class="panel"><div class="panel-head"><h2>群成员 · <?= adminEscape($data['selected_group']['name']) ?> <span class="muted"><?= adminEscape($data['selected_group']['group_code']) ?></span></h2><a class="btn" href="admin.php?section=groups">返回群组</a></div><div class="table-wrap"><table><thead><tr><th>用户</th><th>角色</th><th>状态</th><th>加入时间</th><th>管理操作</th></tr></thead><tbody>
                        <?php foreach (($data['members'] ?? []) as $member): ?><tr><td>#<?= (int) $member['user_id'] ?> · <?= adminEscape($member['username']) ?><span class="sub"><?= adminEscape($member['nickname']) ?> · <?= adminEscape($member['spx_id']) ?></span></td><td><?= adminEscape(adminStatusLabel((string) $member['role'])) ?></td><td><?= (int) $member['mute'] === 1 ? '已禁言' : '正常' ?></td><td><?= adminEscape($member['joined_at']) ?></td><td><?php if ($member['role'] !== 'owner'): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'group.member', 'groups') ?><input type="hidden" name="group_id" value="<?= (int) $data['selected_group']['id'] ?>"><input type="hidden" name="user_id" value="<?= (int) $member['user_id'] ?>"><input name="reason" maxlength="500" placeholder="操作原因" required><?php if ((int) $member['mute'] === 1): ?><button class="btn" name="operation" value="unmute">解除禁言</button><?php else: ?><button class="btn" name="operation" value="mute">禁言</button><?php endif; ?><button class="btn danger" name="operation" value="remove">移出群组</button></form><?php else: ?>群主不可操作<?php endif; ?></td></tr><?php endforeach; ?>
                        <?= empty($data['members']) ? adminTableEmpty(5) : '' ?>
                    </tbody></table></div></section>
                    <?php if (!empty($data['member_pagination']) && $data['member_pagination']['pages'] > 1): $memberPagination = $data['member_pagination']; $memberQuery = $_GET; ?>
                        <nav class="pagination" aria-label="群成员分页"><span class="page-info">共 <?= number_format($memberPagination['total']) ?> 位成员</span><?php if ($memberPagination['page'] > 1): $memberQuery['member_page'] = $memberPagination['page'] - 1; ?><a href="admin.php?<?= adminEscape(http_build_query($memberQuery)) ?>">上一页</a><?php endif; ?><span class="current"><?= (int) $memberPagination['page'] ?> / <?= (int) $memberPagination['pages'] ?></span><?php if ($memberPagination['page'] < $memberPagination['pages']): $memberQuery['member_page'] = $memberPagination['page'] + 1; ?><a href="admin.php?<?= adminEscape(http_build_query($memberQuery)) ?>">下一页</a><?php endif; ?></nav>
                    <?php endif; ?>
                <?php endif; ?>
            <?php elseif ($section === 'reports'): ?>
                <section class="panel"><div class="panel-head"><h2>财务汇总 <span class="muted">按流水日期统计</span></h2></div><div class="panel-body">
                    <form method="get" class="search-form"><input type="hidden" name="section" value="reports"><label>开始日期 <input type="date" name="from" value="<?= adminEscape($data['report_from']) ?>"></label><label>结束日期 <input type="date" name="to" value="<?= adminEscape($data['report_to']) ?>"></label><button class="btn primary">查询</button></form>
                    <div class="stats" style="margin-top:18px"><div class="stat"><div class="stat-label">完成充值 USDT</div><div class="stat-value"><?= adminAmount4($data['totals']['recharge_usdt'] ?? '0') ?></div></div><div class="stat"><div class="stat-label">完成提现 USDT</div><div class="stat-value"><?= adminAmount4($data['totals']['withdraw_usdt'] ?? '0') ?></div></div><div class="stat"><div class="stat-label">钱包流水条数</div><div class="stat-value"><?= number_format((int) ($data['totals']['transaction_count'] ?? 0)) ?></div></div></div>
                    <div class="actions">
                        <a class="btn" href="admin.php?export=users">导出用户</a><a class="btn" href="admin.php?export=wallets">导出余额</a><a class="btn" href="admin.php?export=recharges">导出充值</a><a class="btn" href="admin.php?export=withdrawals">导出提现</a><a class="btn" href="admin.php?export=transactions">导出流水</a><a class="btn" href="admin.php?export=orders">导出订单</a>
                    </div>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>每日 USDT 流水</h2></div><div class="table-wrap"><table><thead><tr><th>日期</th><th>流水数</th><th>完成充值</th><th>完成提现</th><th>管理员增加</th><th>管理员扣减</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['day']) ?></td><td><?= (int) $row['transaction_count'] ?></td><td><?= adminAmount4($row['recharge_usdt']) ?></td><td><?= adminAmount4($row['withdraw_usdt']) ?></td><td><?= adminAmount4($row['admin_credit_usdt']) ?></td><td><?= adminAmount4($row['admin_debit_usdt']) ?></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(6) : '' ?>
                </tbody></table></div></section>
                <?php $spxTotals = $data['spx_totals'] ?? []; $spxLive = $data['spx_live'] ?? []; ?>
                <section class="panel"><div class="panel-head"><h2>SPX001 财务汇总 <span class="muted">与 USDT 分开统计 · 顶部数字为所选区间，底部为实时持仓</span></h2></div><div class="panel-body">
                    <div class="stats">
                        <div class="stat"><div class="stat-label">完成充值 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['recharge_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                        <div class="stat"><div class="stat-label">完成提现 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['withdraw_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                        <div class="stat"><div class="stat-label">新增质押 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['staking_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                        <div class="stat"><div class="stat-label">发放分红 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['dividend_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                        <div class="stat"><div class="stat-label">邀请奖励 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['referral_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                        <div class="stat"><div class="stat-label">闪兑入账 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['exchange_in_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                        <div class="stat"><div class="stat-label">后台增加 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['admin_credit_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                        <div class="stat"><div class="stat-label">后台扣减 SPX001</div><div class="stat-value"><?= adminAmount4($spxTotals['admin_debit_spx'] ?? '0') ?></div><div class="sub">区间内</div></div>
                    </div>
                    <div class="stats" style="margin-top:14px">
                        <div class="stat"><div class="stat-label">当前质押中</div><div class="stat-value"><?= adminAmount4($spxLive['active_staking'] ?? '0') ?></div><div class="sub">实时 · 生效中的质押本金</div></div>
                        <div class="stat"><div class="stat-label">历史质押总量</div><div class="stat-value"><?= adminAmount4($spxLive['all_staking'] ?? '0') ?></div><div class="sub">实时 · 含已到期</div></div>
                        <div class="stat"><div class="stat-label">平台 SPX001 总余额</div><div class="stat-value"><?= adminAmount4($spxLive['balance'] ?? '0') ?></div><div class="sub">实时 · 所有用户余额之和</div></div>
                        <div class="stat"><div class="stat-label">其中可质押余额</div><div class="stat-value"><?= adminAmount4($spxLive['stakeable'] ?? '0') ?></div><div class="sub">实时</div></div>
                    </div>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>每日 SPX001 流水</h2></div><div class="table-wrap"><table><thead><tr><th>日期</th><th>完成充值</th><th>完成提现</th><th>新增质押</th><th>发放分红</th><th>后台净调整</th></tr></thead><tbody>
                    <?php foreach (($data['spx_daily'] ?? []) as $row): ?><tr><td><?= adminEscape($row['day']) ?></td><td><?= adminAmount4($row['recharge_spx']) ?></td><td><?= adminAmount4($row['withdraw_spx']) ?></td><td><?= adminAmount4($row['staking_spx']) ?></td><td><?= adminAmount4($row['dividend_spx']) ?></td><td><?= adminAmount4($row['admin_net_spx']) ?></td></tr><?php endforeach; ?>
                    <?= empty($data['spx_daily']) ? adminTableEmpty(6) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'logs'): ?>
                <section class="panel"><div class="panel-head"><h2>管理员操作审计 <span class="muted">每页 30 条，不提供删除</span></h2></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>管理员</th><th>操作</th><th>对象</th><th>原因</th><th>变更前</th><th>变更后</th><th>来源 IP</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td><?= adminEscape($row['created_at']) ?></td><td><?= adminEscape($row['admin_username']) ?></td><td><?= adminEscape(adminActionLabel((string) $row['action'])) ?></td><td><?= adminEscape(adminTargetLabel((string) $row['target_type'])) ?> #<?= adminEscape($row['target_id'] ?? '') ?></td><td class="ticket-content"><?= adminEscape($row['reason'] ?: '—') ?></td><td class="ticket-content mono"><?= adminEscape($row['before_data'] ?: '—') ?></td><td class="ticket-content mono"><?= adminEscape($row['after_data'] ?: '—') ?></td><td><?= adminEscape($row['ip_address'] ?: '—') ?></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(8) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'admins'): ?>
                <section class="panel"><div class="panel-head"><h2>新增管理员</h2></div><div class="panel-body"><form method="post" class="form-grid">
                    <?= adminHidden($csrf, 'admin.save', 'admins') ?><input type="hidden" name="admin_id" value="0">
                    <div class="field"><label>登录账号</label><input name="username" maxlength="64" required></div>
                    <div class="field"><label>初始密码（至少 12 位）</label><input name="new_password" type="password" minlength="12" maxlength="200" required></div>
                    <div class="field"><label>角色</label><select name="role"><option value="operator">运营</option><option value="support">客服</option><option value="finance">财务</option><option value="superadmin">超级管理员</option></select></div>
                    <div><button class="btn primary">创建管理员</button></div>
                </form></div></section>
                <section class="panel"><div class="panel-head"><h2>管理员账号与权限</h2></div><div class="table-wrap"><table><thead><tr><th>ID / 最近登录</th><th>账号</th><th>角色</th><th>状态</th><th>修改</th><th>启停</th></tr></thead><tbody>
                    <?php foreach (($data['rows'] ?? []) as $row): ?><tr><td>#<?= (int) $row['id'] ?><span class="sub"><?= adminEscape($row['last_login_at'] ?: '尚未登录') ?></span></td><td><?= adminEscape($row['username']) ?></td><td><?= adminEscape(adminRoleLabel((string) $row['role'])) ?></td><td><?= (int) $row['status'] === 1 ? '启用' : '停用' ?></td><td><form method="post" class="inline-form"><?= adminHidden($csrf, 'admin.save', 'admins') ?><input type="hidden" name="admin_id" value="<?= (int) $row['id'] ?>"><input name="username" maxlength="64" required value="<?= adminEscape($row['username']) ?>"><select name="role"><option value="operator" <?= $row['role'] === 'operator' ? 'selected' : '' ?>>运营</option><option value="support" <?= $row['role'] === 'support' ? 'selected' : '' ?>>客服</option><option value="finance" <?= $row['role'] === 'finance' ? 'selected' : '' ?>>财务</option><option value="superadmin" <?= $row['role'] === 'superadmin' ? 'selected' : '' ?>>超级管理员</option></select><input name="new_password" type="password" minlength="12" maxlength="200" placeholder="留空不改密码"><button class="btn">保存</button></form></td><td><?php if ((int) $row['id'] !== (int) $admin['id']): ?><form method="post"><?= adminHidden($csrf, 'admin.toggle', 'admins') ?><input type="hidden" name="admin_id" value="<?= (int) $row['id'] ?>"><button class="btn <?= (int) $row['status'] === 1 ? 'danger' : '' ?>"><?= (int) $row['status'] === 1 ? '停用' : '启用' ?></button></form><?php else: ?>当前账号<?php endif; ?></td></tr><?php endforeach; ?>
                    <?= empty($data['rows']) ? adminTableEmpty(6) : '' ?>
                </tbody></table></div></section>
            <?php elseif ($section === 'email_notifications'): ?>
                <section class="panel"><div class="panel-head"><h2>邮件通知</h2><span class="muted">充值到账、提现申请、提现失败</span></div><div class="panel-body"><div class="detail-grid"><div><span class="muted">SMTP 配置</span><strong>使用服务器环境变量</strong></div><div><span class="muted">默认收件人</span><strong>yishengzaidu@qq.com</strong></div><div><span class="muted">超级管理员</span><strong>管理收件人和模板</strong></div><div><span class="muted">运营账号</span><strong>仅发送测试</strong></div></div></div></section>
                <?php if (adminCan('email_notification_control')): ?><section class="panel"><div class="panel-head"><h2>新增收件人</h2><span class="muted">可按模板订阅</span></div><div class="panel-body"><form method="post" class="form-grid"><?= adminHidden($csrf, 'finance.recipient.save', 'email_notifications') ?><input type="hidden" name="recipient_id" value="0"><div class="field"><label>邮箱</label><input name="email" type="email" maxlength="190" required></div><div class="field"><label>状态</label><select name="status"><option value="1">启用</option><option value="0">停用</option></select></div><div class="field span-2"><label>订阅模板</label><div class="actions"><?php foreach (($data['email_templates'] ?? []) as $template): ?><label><input type="checkbox" name="template_ids[]" value="<?= (int) $template['id'] ?>" checked> <?= adminEscape($template['name']) ?></label><?php endforeach; ?></div></div><div><button class="btn primary">添加收件人</button></div></form></div></section><?php endif; ?>
                <section class="panel"><div class="panel-head"><h2>收件人列表</h2></div><div class="table-wrap"><table><thead><tr><th>邮箱</th><th>状态</th><th>订阅模板</th><th>操作</th></tr></thead><tbody><?php foreach (($data['email_recipients'] ?? []) as $recipient): ?><tr><td><?= adminEscape($recipient['email']) ?></td><td><?= (int) $recipient['status'] === 1 ? '启用' : '停用' ?></td><td><?php $subscriptions = $data['email_subscriptions'][(int) $recipient['id']] ?? []; ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'finance.recipient.save', 'email_notifications') ?><input type="hidden" name="recipient_id" value="<?= (int) $recipient['id'] ?>"><input name="email" type="email" value="<?= adminEscape($recipient['email']) ?>" maxlength="190" required><select name="status"><option value="1" <?= (int) $recipient['status'] === 1 ? 'selected' : '' ?>>启用</option><option value="0" <?= (int) $recipient['status'] === 0 ? 'selected' : '' ?>>停用</option></select><?php foreach (($data['email_templates'] ?? []) as $template): ?><label><input type="checkbox" name="template_ids[]" value="<?= (int) $template['id'] ?>" <?= in_array((int) $template['id'], $subscriptions, true) ? 'checked' : '' ?>><?= adminEscape($template['name']) ?></label><?php endforeach; ?><button class="btn">保存</button></form></td><td><form method="post" onsubmit="return confirm('确认删除该收件人吗？');"><?= adminHidden($csrf, 'finance.recipient.delete', 'email_notifications') ?><input type="hidden" name="recipient_id" value="<?= (int) $recipient['id'] ?>"><button class="btn danger">删除</button></form></td></tr><?php endforeach; ?><?= empty($data['email_recipients']) ? adminTableEmpty(4) : '' ?></tbody></table></div></section>
                <section class="panel"><div class="panel-head"><h2>通知模板</h2><span class="muted">支持变量：{{username}} {{nickname}} {{user_id}} {{symbol}} {{amount}} {{fee}} {{net_amount}} {{reason}} {{status}} {{created_at}}</span></div><?php foreach (($data['email_templates'] ?? []) as $template): ?><div class="panel-body" style="border-bottom:1px solid #e5ebe7"><div class="section-head"><h3><?= adminEscape($template['name']) ?></h3><span class="muted"><?= adminEscape($template['event_key']) ?></span></div><form method="post"><input type="hidden" name="csrf" value="<?= adminEscape($csrf) ?>"><input type="hidden" name="action" value="finance.template.save"><input type="hidden" name="section" value="email_notifications"><input type="hidden" name="template_id" value="<?= (int) $template['id'] ?>"><div class="field"><label>邮件标题</label><input name="subject" maxlength="255" value="<?= adminEscape($template['subject']) ?>" required></div><div class="field"><label>HTML 正文</label><textarea name="body_html" maxlength="50000" style="min-height:150px" required><?= adminEscape($template['body_html']) ?></textarea></div><?php if (adminCan('email_notification_control')): ?><div class="actions"><button class="btn primary">保存模板</button></form><form method="post"><?= adminHidden($csrf, 'finance.template.reset', 'email_notifications') ?><input type="hidden" name="template_id" value="<?= (int) $template['id'] ?>"><button class="btn">恢复默认</button></form><?php else: ?></form><?php endif; ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'finance.email.test', 'email_notifications') ?><input type="hidden" name="template_id" value="<?= (int) $template['id'] ?>"><input name="reason" maxlength="500" placeholder="测试原因" required><button class="btn">测试发送</button></form><?php if (adminCan('email_notification_control')): ?></div><?php endif; ?></div><?php endforeach; ?></section>
                <section class="panel"><div class="panel-head"><h2>发送记录 <span class="muted">最近 50 条</span></h2></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>模板</th><th>收件人</th><th>状态</th><th>错误原因</th></tr></thead><tbody><?php foreach (($data['email_logs'] ?? []) as $log): ?><tr><td><?= adminEscape($log['created_at']) ?></td><td><?= adminEscape($log['event_key']) ?></td><td><?= adminEscape($log['recipient_email']) ?></td><td><span class="status <?= adminEscape($log['status']) ?>"><?= $log['status'] === 'sent' ? '成功' : '失败' ?></span></td><td><?= adminEscape($log['error_message'] ?: '—') ?></td></tr><?php endforeach; ?><?= empty($data['email_logs']) ? adminTableEmpty(5) : '' ?></tbody></table></div></section>
            <?php elseif ($section === 'settings'): ?>
                <section class="panel" id="ios-download"><div class="panel-head"><h2>iOS 下载地址</h2><span class="muted">网站首页 iOS 下载按钮</span></div><div class="panel-body">
                    <form method="post" class="settings-form">
                        <?= adminHidden($csrf, 'settings.ios_download.save', 'settings') ?>
                        <div class="field"><label for="ios_download_url">下载页面或安装链接</label><input id="ios_download_url" name="ios_download_url" type="text" inputmode="url" autocomplete="off" spellcheck="false" maxlength="2048" value="<?= adminEscape($data['ios_download_url'] ?? '') ?>" placeholder="https://你的域名/ios"><span class="muted">支持 HTTP/HTTPS 下载页面、App Store、TestFlight 或 itms-services 安装链接。保存后用户点击首页按钮直接跳转；留空保存可关闭入口。IPA 能否安装仍取决于有效签名及分发方式。</span></div>
                        <button class="btn primary" type="submit">保存 iOS 下载地址</button>
                    </form>
                </div></section>
                <section class="panel" id="backup-download"><div class="panel-head"><h2>官网备用下载线路</h2><span class="muted">官网点击「备用下载」时，按访问设备显示对应平台的已启用线路</span></div><div class="panel-body">
                    <?php $backupDownload = $data['backup_download'] ?? ['enabled' => true, 'android' => [], 'ios' => []]; ?>
                    <form method="post" class="settings-form">
                        <?= adminHidden($csrf, 'settings.backup_download.save', 'settings') ?>
                        <label><input type="checkbox" name="backup_enabled" value="1" <?= !empty($backupDownload['enabled']) ? 'checked' : '' ?>> 官网显示「备用下载」按钮</label>
                        <?php foreach (['android' => 'Android 备用线路（HTTP/HTTPS 链接）', 'ios' => 'iOS 备用线路（HTTP/HTTPS 或 itms-services 链接）'] as $platform => $title): ?>
                            <?php $lines = $backupDownload[$platform] ?? []; $rows = max(count($lines) + 2, 3); ?>
                            <h3 style="margin:18px 0 8px"><?= adminEscape($title) ?></h3>
                            <div class="table-wrap"><table><thead><tr><th style="width:24%">线路名称</th><th>下载地址</th><th style="width:70px">启用</th></tr></thead><tbody>
                            <?php for ($i = 0; $i < $rows; $i++): $line = $lines[$i] ?? ['name' => '', 'url' => '', 'enabled' => true]; ?>
                                <tr><td><input name="<?= $platform ?>_lines[<?= $i ?>][name]" maxlength="40" value="<?= adminEscape($line['name']) ?>" placeholder="如：线路 <?= $i + 1 ?>（香港）"></td><td><input name="<?= $platform ?>_lines[<?= $i ?>][url]" type="text" inputmode="url" maxlength="2048" spellcheck="false" value="<?= adminEscape($line['url']) ?>" placeholder="https://"></td><td><input type="checkbox" name="<?= $platform ?>_lines[<?= $i ?>][enabled]" value="1" <?= !empty($line['enabled']) ? 'checked' : '' ?> aria-label="启用该线路"></td></tr>
                            <?php endfor; ?>
                            </tbody></table></div>
                        <?php endforeach; ?>
                        <span class="muted">清空某行的名称和地址后保存即可删除该线路；每次保存后会多留两行空位用于新增。每个平台最多 10 条。</span>
                        <button class="btn primary" type="submit">保存备用下载线路</button>
                    </form>
                </div></section>
                <section class="panel" id="mall-product-limits"><div class="panel-head"><h2>店铺商品上架名额</h2><span class="muted">Android、iOS 和网页端共用此配置</span></div><div class="panel-body">
                    <form method="post" class="form-grid">
                        <?= adminHidden($csrf, 'settings.mall_limits.save', 'settings') ?>
                        <div class="field"><label for="mall_basic_product_limit">初级店铺上架上限（款）</label><input id="mall_basic_product_limit" name="mall_basic_product_limit" type="number" min="0" max="1000000" step="1" value="<?= (int)$data['mall_product_limits']['basic'] ?>" required></div>
                        <div class="field"><label for="mall_gold_product_limit">黄金店铺上架上限（款）</label><input id="mall_gold_product_limit" name="mall_gold_product_limit" type="number" min="0" max="1000000" step="1" value="<?= (int)$data['mall_product_limits']['gold'] ?>" required></div>
                        <p class="muted span-2">按当前已上架商品数量计算，下架或删除后释放名额。0 表示暂停新增上架。调低上限不会自动下架已有商品，已上架商品仍可编辑；新增或重新上架须低于新上限。</p>
                        <div><button class="btn primary" type="submit">保存上架名额</button></div>
                    </form>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>Android 热更新</h2><span class="muted">上传并发布后，原生 Android App 会自动检查新版本</span></div><div class="panel-body">
                    <?php $activeRelease = null; foreach (($data['releases'] ?? []) as $release): if ((int) $release['is_active'] === 1) { $activeRelease = $release; break; } endforeach; ?>
                    <p class="muted">当前发布：<?= $activeRelease ? 'v' . adminEscape($activeRelease['version_name']) . '（版本代码 ' . (int) $activeRelease['version_code'] . '）· 已下载 ' . number_format((int) $activeRelease['download_count']) . ' 次' : '尚未发布 APK' ?></p>
                    <form method="post" class="form-grid" enctype="multipart/form-data">
                        <?= adminHidden($csrf, 'app.release.upload', 'settings') ?>
                        <div class="field"><label for="release_version_name">版本名称</label><input id="release_version_name" name="version_name" maxlength="32" placeholder="例如 1.1" pattern="[A-Za-z0-9][A-Za-z0-9._+-]{0,31}" required></div>
                        <div class="field"><label for="release_version_code">Android 版本代码</label><input id="release_version_code" name="version_code" type="number" min="1" step="1" placeholder="必须大于当前代码" required></div>
                        <div class="field span-2"><label for="release_notes">更新说明</label><textarea id="release_notes" name="release_notes" maxlength="10000" placeholder="填写本次版本更新内容" required></textarea></div>
                        <div class="field span-2"><label for="release_apk">APK 文件（最大 500 MB）</label><input id="release_apk" name="apk" type="file" accept=".apk,application/vnd.android.package-archive" required><span class="muted">必须使用与已安装版本相同的 Android 包名和签名证书；版本代码必须递增。</span></div>
                        <div><button class="btn primary" type="submit">上传并发布</button></div>
                    </form>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>商城人民币参考汇率</h2><span class="muted">只读 · 代码固定值</span></div><div class="panel-body">
                    <p class="muted">当前固定为 1 USDT = <?= adminEscape(MALL_USD_CNY) ?> CNY（定义在 mall_valuation.php）。该值只用于商品参考价，不参与 SPX001 订单结算；调整需要改代码发版，后台不提供在线修改，避免出现"改了不生效"的假配置。</p>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>邀请链接域名</h2><span class="muted">用于生成注册邀请链接</span></div><div class="panel-body">
                    <form method="post" class="settings-form"><?= adminHidden($csrf, 'settings.invite_base.save', 'settings') ?><div class="field"><label for="invite_base_url">公网根地址</label><input id="invite_base_url" name="invite_base_url" value="<?= adminEscape($data['invite_base_url'] ?? 'http://spx001.top') ?>" placeholder="http://spx001.top" required></div><button class="btn primary">保存邀请域名</button></form>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>已发布版本 <span class="muted">最近 20 个版本 · 下载次数按版本累计</span></h2></div><div class="table-wrap"><table><thead><tr><th>版本</th><th>状态</th><th>文件大小</th><th>下载次数</th><th>SHA-256</th><th>发布时间</th></tr></thead><tbody>
                    <?php foreach (($data['releases'] ?? []) as $release): ?><tr><td>v<?= adminEscape($release['version_name']) ?><span class="sub">版本代码 <?= (int) $release['version_code'] ?> · <?= nl2br(adminEscape($release['release_notes'])) ?></span></td><td><span class="status <?= (int) $release['is_active'] === 1 ? 'active' : 'inactive' ?>"><?= (int) $release['is_active'] === 1 ? '当前发布' : '历史版本' ?></span></td><td><?= number_format((int) $release['apk_size'] / 1048576, 2) ?> MB</td><td><?= number_format((int) $release['download_count']) ?></td><td class="mono"><?= adminEscape(substr((string) $release['apk_sha256'], 0, 16)) ?>…</td><td><?= adminEscape($release['created_at']) ?></td></tr><?php endforeach; ?>
                    <?= empty($data['releases']) ? adminTableEmpty(6) : '' ?>
                </tbody></table></div></section>
                <section class="panel"><div class="panel-head"><h2>BSC USDT 收款设置</h2></div><div class="panel-body">
                    <form method="post" class="settings-form"><?= adminHidden($csrf, 'settings.deposit.save', 'settings') ?><div class="field"><label for="deposit_address">平台收款钱包地址</label><input id="deposit_address" name="deposit_address" class="address-box" maxlength="42" required pattern="0x[0-9a-fA-F]{40}" value="<?= adminEscape($data['deposit_address'] ?? '') ?>"></div><button class="btn primary">保存收款地址</button></form>
                </div></section>
                <?php $fissWithdrawEnabled = (string) ($pdo->query("SELECT `value` FROM app_settings WHERE `key`='withdraw_fiss_enabled'")->fetchColumn() ?: '0'); ?>
                <section class="panel"><div class="panel-head"><h2>FISS 提现开关</h2><span class="muted">关闭后 App 端 FISS 提现会被拒绝，已有提现单不受影响</span></div><div class="panel-body">
                    <form method="post" class="settings-form"><?= adminHidden($csrf, 'settings.fiss_withdraw.save', 'settings') ?><div class="field"><label for="fiss_withdraw_enabled">FISS 提现</label><select id="fiss_withdraw_enabled" name="enabled"><option value="1" <?= ($fissWithdrawEnabled ?? '0') === '1' ? 'selected' : '' ?>>开启</option><option value="0" <?= ($fissWithdrawEnabled ?? '0') !== '1' ? 'selected' : '' ?>>关闭</option></select></div><div><button class="btn primary">保存</button></div></form>
                </div></section>
                <?php $feeCfg = $data['withdraw_fee_rates'] ?? []; $minCfg = $data['withdraw_min_amounts'] ?? []; $symDefaults = ['USDT' => ['1', '10'], 'SPX001' => ['1', '1000'], 'FISS' => ['1', '10000']]; ?>
                <section class="panel"><div class="panel-head"><h2>提现手续费与最低额</h2><span class="muted">按币种分别设置，手续费率填百分比（1 = 1%），保存后立即生效</span></div><div class="panel-body">
                    <form method="post" class="settings-form">
                        <?= adminHidden($csrf, 'settings.withdraw_config.save', 'settings') ?>
                        <div class="table-wrap"><table><thead><tr><th>币种</th><th>手续费率 %</th><th>最低提现数量</th></tr></thead><tbody>
                        <?php foreach (['USDT', 'SPX001', 'FISS'] as $sym): $cfgFee = $feeCfg[$sym] ?? $symDefaults[$sym][0]; $cfgMin = $minCfg[$sym] ?? $symDefaults[$sym][1]; ?>
                            <tr>
                                <td><strong><?= adminEscape($sym) ?></strong></td>
                                <td><input name="fee_<?= strtolower($sym) ?>" value="<?= adminEscape((string) $cfgFee) ?>" inputmode="decimal" style="max-width:120px"></td>
                                <td><input name="min_<?= strtolower($sym) ?>" value="<?= adminEscape((string) $cfgMin) ?>" inputmode="decimal" style="max-width:180px"></td>
                            </tr>
                        <?php endforeach; ?>
                        </tbody></table></div>
                        <p class="muted">例：SPX001 填 5 表示提现收 5%；最低额用于拦掉小额提现，避免白耗热钱包 gas。</p>
                        <div><button class="btn primary">保存提现配置</button></div>
                    </form>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>API 节点分流</h2><span class="muted">固定路径 /spxchat/api.php，按权重选择启用节点</span></div><div class="panel-body">
                    <form method="post" class="settings-form">
                        <?= adminHidden($csrf, 'settings.api_nodes.save', 'settings') ?>
                        <div class="field"><label for="api_nodes">节点列表</label><textarea id="api_nodes" name="api_nodes" rows="7" spellcheck="false" placeholder="https://70.39.201.191|10|on&#10;http://备用IP|1|on&#10;https://故障节点|1|off"><?= adminEscape($data['api_nodes_text'] ?? '') ?></textarea><span class="muted">每行格式：地址 | 权重 | on/off。地址可填写根地址、/spxchat 或 /spxchat/api.php，保存时会统一为根地址。HTTPS 自签证书必须随 App 内置并匹配对应节点。</span></div>
                        <div><button class="btn primary">保存节点分流</button></div>
                    </form>
                </div></section>
                <section class="panel"><div class="panel-head"><h2>杭州地址广播（容灾）</h2><span class="muted">香港连不上时，新版本客户端从这里读取最新 API 地址</span></div><div class="panel-body">
                    <?php $beaconNodes = $data['beacon_live']['nodes'] ?? []; ?>
                    <form method="post" class="settings-form">
                        <?= adminHidden($csrf, 'settings.beacon.save', 'settings') ?>
                        <div class="field"><label for="beacon_url">广播地址（根地址）</label><input id="beacon_url" name="beacon_url" type="text" inputmode="url" autocomplete="off" spellcheck="false" maxlength="255" value="<?= adminEscape($data['beacon_url'] ?? '') ?>" placeholder="https://121.196.246.42:8443"><span class="muted">杭州服务器的根地址，不要带 /beacon 后缀。</span></div>
                        <div class="field"><label for="beacon_token">访问令牌</label><input id="beacon_token" name="beacon_token" type="text" autocomplete="off" spellcheck="false" maxlength="200" value="<?= adminEscape($data['beacon_token'] ?? '') ?>" placeholder="部署杭州时生成的令牌"><span class="muted">只用于后台写入，客户端不需要。</span></div>
                        <div><button class="btn primary">保存并同步</button></div>
                    </form>
                    <form method="post" onsubmit="return confirm('把当前节点列表推送到杭州？');">
                        <?= adminHidden($csrf, 'settings.beacon.sync', 'settings') ?>
                        <div style="margin-top:12px"><button class="btn">立即同步节点到杭州</button> <span class="muted">香港正常时杭州每 60 秒自动同步一次，这个按钮用于改完节点马上生效。</span></div>
                    </form>
                    <?php if (!empty($data['beacon_live'])): ?>
                        <p class="muted" style="margin-top:12px">杭州当前广播：<?= $beaconNodes ? adminEscape(implode('、', array_map(static fn ($node) => (string) ($node['url'] ?? ''), $beaconNodes))) : '（空，客户端会无法切换）' ?></p>
                    <?php elseif (!empty($data['beacon_error'])): ?>
                        <p class="muted" style="margin-top:12px">杭州连接失败：<?= adminEscape((string) $data['beacon_error']) ?></p>
                    <?php endif; ?>
                </div></section>
            <?php elseif ($section === 'profile'): ?>
                <section class="panel"><div class="panel-head"><h2>修改管理员密码</h2></div><div class="panel-body">
                    <form method="post" class="form-grid"><?= adminHidden($csrf, 'password.save', 'profile') ?><div class="field"><label for="current_password">当前密码</label><input id="current_password" name="current_password" type="password" autocomplete="current-password" required></div><div class="field"><label for="new_password">新密码（至少 12 位）</label><input id="new_password" name="new_password" type="password" minlength="12" maxlength="200" autocomplete="new-password" required></div><div><button class="btn primary">更新密码</button></div></form>
                </div></section>
            <?php endif; ?>
            <?php if (!empty($data['pagination']) && $data['pagination']['pages'] > 1): $pagination = $data['pagination']; $query = $_GET; unset($query['page']); $startPage = max(1, $pagination['page'] - 2); $endPage = min($pagination['pages'], $pagination['page'] + 2); ?>
                <nav class="pagination" aria-label="列表分页"><span class="page-info">共 <?= number_format($pagination['total']) ?> 条，第 <?= (int) $pagination['page'] ?> / <?= (int) $pagination['pages'] ?> 页</span><?php if ($pagination['page'] > 1): $query['page'] = $pagination['page'] - 1; ?><a href="admin.php?<?= adminEscape(http_build_query($query)) ?>" aria-label="上一页">上一页</a><?php endif; ?><?php for ($pageNumber = $startPage; $pageNumber <= $endPage; $pageNumber++): ?><?php if ($pageNumber === $pagination['page']): ?><span class="current" aria-current="page"><?= $pageNumber ?></span><?php else: $query['page'] = $pageNumber; ?><a href="admin.php?<?= adminEscape(http_build_query($query)) ?>"><?= $pageNumber ?></a><?php endif; ?><?php endfor; ?><?php if ($pagination['page'] < $pagination['pages']): $query['page'] = $pagination['page'] + 1; ?><a href="admin.php?<?= adminEscape(http_build_query($query)) ?>" aria-label="下一页">下一页</a><?php endif; ?></nav>
            <?php endif; ?>
        </div>
    </main>
</div>
<script>
const productImageInput=document.getElementById('images');
const productImagePreview=document.getElementById('image-preview');
if(productImageInput&&productImagePreview){productImageInput.addEventListener('change',()=>{productImagePreview.replaceChildren();for(const file of Array.from(productImageInput.files||[])){if(!file.type.startsWith('image/'))continue;const image=document.createElement('img');image.alt=file.name;image.src=URL.createObjectURL(file);image.onload=()=>URL.revokeObjectURL(image.src);productImagePreview.append(image);}});}
const userDetailModal=document.getElementById('user-detail-modal');
if(userDetailModal){userDetailModal.addEventListener('click',event=>{if(event.target===userDetailModal)window.location.href='admin.php?section=users';});document.addEventListener('keydown',event=>{if(event.key==='Escape')window.location.href='admin.php?section=users';});}
document.querySelectorAll('.trend-panel').forEach(panel=>{const tooltip=panel.querySelector('.trend-tooltip');const hide=()=>tooltip.classList.remove('visible');const show=(marker,x,y)=>{tooltip.textContent=marker.dataset.tooltip||'';tooltip.classList.add('visible');const bounds=panel.getBoundingClientRect();const left=Math.max(8,Math.min(x-bounds.left+10,panel.clientWidth-tooltip.offsetWidth-8));let top=y-bounds.top-tooltip.offsetHeight-10;if(top<8)top=Math.min(panel.clientHeight-tooltip.offsetHeight-8,y-bounds.top+14);tooltip.style.left=left+'px';tooltip.style.top=Math.max(8,top)+'px';};panel.addEventListener('pointermove',event=>{const marker=event.target.closest?.('.trend-marker');if(marker)show(marker,event.clientX,event.clientY);else hide();});panel.addEventListener('pointerdown',event=>{const marker=event.target.closest?.('.trend-marker');if(marker)show(marker,event.clientX,event.clientY);});panel.addEventListener('pointerleave',hide);panel.addEventListener('focusin',event=>{const marker=event.target.closest?.('.trend-marker');if(marker){const bounds=marker.getBoundingClientRect();show(marker,bounds.left+bounds.width/2,bounds.top);}});panel.addEventListener('focusout',hide);});
</script>
<script src="admin.js?v=20261027-1" defer></script>
</body>
</html>
