<?php
declare(strict_types=1);

date_default_timezone_set('Asia/Shanghai');
require_once __DIR__ . '/mall_schema.php';
require_once __DIR__ . '/push_notifications.php';
require_once __DIR__ . '/chat_schema.php';
require_once __DIR__ . '/mall_commerce.php';
require_once __DIR__ . '/mall_merchant_media.php';
require_once __DIR__ . '/mall_workspace.php';
require_once __DIR__ . '/mall_revision.php';

function db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $config = require __DIR__ . '/config.php';
    $db = $config['db'];
    $options = [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ];
    $dsn = sprintf('mysql:host=%s;port=%d;dbname=%s;charset=%s', $db['host'], $db['port'], $db['name'], $db['charset']);
    try {
        $pdo = new PDO($dsn, $db['user'], $db['password'], $options);
    } catch (PDOException $exception) {
        if ((int) ($exception->errorInfo[1] ?? 0) !== 1049) throw $exception;
        $serverDsn = sprintf('mysql:host=%s;port=%d;charset=%s', $db['host'], $db['port'], $db['charset']);
        $server = new PDO($serverDsn, $db['user'], $db['password'], $options);
        $server->exec(sprintf(
            'CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci',
            str_replace('`', '``', $db['name'])
        ));
        $pdo = new PDO($dsn, $db['user'], $db['password'], $options);
    }

    $pdo->exec("SET time_zone = '+08:00'");

    installSchema($pdo);

    return $pdo;
}

function installSchema(PDO $pdo): void
{
    static $installed = false;
    if ($installed) return;
    $installed = true;
    // Run schema repair once per code revision, instead of issuing DDL on every API request.
    $fingerprint = hash('sha256', hash_file('sha256', __DIR__ . '/push_notifications.php') . hash_file('sha256', __FILE__) . hash_file('sha256', __DIR__ . '/chat_schema.php') . hash_file('sha256', __DIR__ . '/mall_schema.php') . hash_file('sha256', __DIR__ . '/mall_commerce.php') . hash_file('sha256', __DIR__ . '/mall_merchant_media.php') . hash_file('sha256', __DIR__ . '/mall_workspace.php') . hash_file('sha256', __DIR__ . '/mall_revision.php'));
    $schemaCurrent = static function () use ($pdo, $fingerprint): bool {
        try {
            // 每个请求一次查库 → 命中缓存后直接跳过（key 带代码指纹，改动自动失效）
            if (function_exists('cacheGet') && cacheGet('schema:' . $fingerprint) === true) return true;
            $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=? LIMIT 1');
            $q->execute(['runtime_schema_fingerprint']);
            $current = $q->fetchColumn() === $fingerprint;
            if ($current && function_exists('cacheSet')) cacheSet('schema:' . $fingerprint, true, 3600);
            return $current;
        } catch (PDOException $e) {
            if ((int) ($e->errorInfo[1] ?? 0) !== 1146) throw $e;
            return false;
        }
    };
    if ($schemaCurrent()) return;
    if ((int) $pdo->query("SELECT GET_LOCK('spx_runtime_schema_v2',30)")->fetchColumn() !== 1) {
        $installed = false;
        throw new RuntimeException('数据库正在升级，请稍后重试');
    }
    try {
    if ($schemaCurrent()) return;
    $tables = [
        'CREATE TABLE IF NOT EXISTS users (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, email VARCHAR(190) NULL UNIQUE, email_verified_at DATETIME NULL, invited_by_user_id BIGINT UNSIGNED NULL, nickname VARCHAR(64) NOT NULL, spx_id VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, avatar_url VARCHAR(500) NULL, remark VARCHAR(100) NULL, bsc_wallet_address CHAR(42) NULL UNIQUE, status TINYINT NOT NULL DEFAULT 1, registration_ip VARCHAR(64) NULL, device_id VARCHAR(128) NULL, device_model VARCHAR(128) NULL, android_version VARCHAR(32) NULL, app_version VARCHAR(32) NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX(invited_by_user_id), CONSTRAINT fk_user_inviter FOREIGN KEY(invited_by_user_id) REFERENCES users(id) ON DELETE SET NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS financial_requests (user_id BIGINT UNSIGNED NOT NULL, action VARCHAR(32) NOT NULL, request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, payload_hash CHAR(64) NOT NULL, result_json LONGTEXT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id,action,request_id), CONSTRAINT fk_financial_request_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS admin_users (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, role VARCHAR(20) NOT NULL DEFAULT "superadmin", status TINYINT NOT NULL DEFAULT 1, last_login_at DATETIME NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS admin_login_attempts (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, ip_address VARCHAR(45) NOT NULL, username VARCHAR(64) NOT NULL, failed_attempts INT UNSIGNED NOT NULL DEFAULT 0, locked_until DATETIME NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, UNIQUE KEY uq_admin_login_ip_user (ip_address,username)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS admin_audit_logs (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, admin_id BIGINT UNSIGNED NULL, admin_username VARCHAR(64) NOT NULL, `action` VARCHAR(64) NOT NULL, target_type VARCHAR(40) NOT NULL, target_id BIGINT NULL, request_id CHAR(32) NULL, reason VARCHAR(500) NULL, before_data LONGTEXT NULL, after_data LONGTEXT NULL, ip_address VARCHAR(45) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uq_admin_audit_request_id (request_id), KEY idx_admin_audit_created (created_at), KEY idx_admin_audit_target (target_type,target_id), KEY idx_admin_audit_actor (admin_id,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS service_heartbeats (service_name VARCHAR(64) PRIMARY KEY, status VARCHAR(20) NOT NULL, checked_at DATETIME NOT NULL, error_message VARCHAR(500) NULL, metadata TEXT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS email_verification_codes (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, email VARCHAR(190) NOT NULL, purpose VARCHAR(30) NOT NULL DEFAULT "register", code_hash CHAR(64) NOT NULL, expires_at DATETIME NOT NULL, used_at DATETIME NULL, attempts TINYINT UNSIGNED NOT NULL DEFAULT 0, request_ip VARCHAR(64) NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX(email,purpose,created_at), INDEX(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS user_payment_passwords (user_id BIGINT UNSIGNED PRIMARY KEY, password_hash VARCHAR(255) NOT NULL, failed_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0, locked_until DATETIME NULL, changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, CONSTRAINT fk_payment_password_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS registration_group_admissions (user_id BIGINT UNSIGNED PRIMARY KEY, group_code CHAR(6) NOT NULL, state VARCHAR(16) NOT NULL DEFAULT "pending", created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT fk_registration_group_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS api_rate_limits (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, scope_key VARCHAR(180) NOT NULL, window_start DATETIME NOT NULL, attempts INT UNSIGNED NOT NULL DEFAULT 0, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, UNIQUE KEY uq_api_rate_window (scope_key,window_start), INDEX idx_api_rate_updated (updated_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS user_tokens (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, token CHAR(64) NOT NULL UNIQUE, expires_at DATETIME NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX(user_id), CONSTRAINT fk_token_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS user_presence (user_id BIGINT UNSIGNED NOT NULL, device_id VARCHAR(128) NOT NULL, is_online TINYINT NOT NULL DEFAULT 0, last_seen_at DATETIME NOT NULL, PRIMARY KEY(user_id,device_id), KEY idx_presence_online (is_online,last_seen_at), CONSTRAINT fk_presence_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS chats (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, peer_user_id BIGINT UNSIGNED NOT NULL, title VARCHAR(100) NOT NULL, last_message VARCHAR(500) NULL, unread_count INT UNSIGNED NOT NULL DEFAULT 0, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX(user_id, updated_at), CONSTRAINT fk_chat_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, CONSTRAINT fk_chat_peer FOREIGN KEY(peer_user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS chat_groups (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, owner_user_id BIGINT UNSIGNED NOT NULL, name VARCHAR(100) NOT NULL, avatar_url VARCHAR(500) NULL, announcement TEXT NULL, allow_member_details TINYINT NOT NULL DEFAULT 1, allow_private_message TINYINT NOT NULL DEFAULT 1, announcement_version INT UNSIGNED NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CONSTRAINT fk_group_owner FOREIGN KEY(owner_user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS chat_group_members (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, group_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, role VARCHAR(20) NOT NULL DEFAULT "member", mute TINYINT NOT NULL DEFAULT 0, pinned TINYINT NOT NULL DEFAULT 0, unread_count INT UNSIGNED NOT NULL DEFAULT 0, joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY(group_id,user_id), CONSTRAINT fk_group_member_group FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE, CONSTRAINT fk_group_member_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS chat_group_bans (group_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, banned_by BIGINT UNSIGNED NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(group_id,user_id), CONSTRAINT fk_group_ban_group FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE, CONSTRAINT fk_group_ban_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, CONSTRAINT fk_group_ban_actor FOREIGN KEY(banned_by) REFERENCES users(id) ON DELETE SET NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS chat_group_messages (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, group_id BIGINT UNSIGNED NOT NULL, sender_id BIGINT UNSIGNED NOT NULL, client_message_id CHAR(36) NULL, content TEXT NOT NULL, message_type VARCHAR(20) NOT NULL DEFAULT "text", media_url VARCHAR(500) NULL, mention_user_ids TEXT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uq_group_sender_client (group_id,sender_id,client_message_id), INDEX(group_id,id), CONSTRAINT fk_group_message_group FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE, CONSTRAINT fk_group_message_sender FOREIGN KEY(sender_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS messages (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, chat_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, sender_id BIGINT UNSIGNED NOT NULL, client_message_id CHAR(36) NULL, content TEXT NOT NULL, message_type VARCHAR(20) NOT NULL DEFAULT "text", media_url VARCHAR(500) NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uq_message_user_sender_client (user_id,sender_id,client_message_id), INDEX(chat_id, id), CONSTRAINT fk_message_chat FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE, CONSTRAINT fk_message_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, CONSTRAINT fk_message_sender FOREIGN KEY(sender_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS chat_group_message_hides (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, message_id BIGINT UNSIGNED NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uq_group_hide (user_id,message_id), INDEX idx_group_hide_message (message_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    'CREATE TABLE IF NOT EXISTS chat_settings (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, chat_id BIGINT UNSIGNED NOT NULL, mute TINYINT NOT NULL DEFAULT 0, pinned TINYINT NOT NULL DEFAULT 0, blocked TINYINT NOT NULL DEFAULT 0, background_url VARCHAR(500) NULL, UNIQUE KEY(user_id, chat_id), CONSTRAINT fk_setting_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, CONSTRAINT fk_setting_chat FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS contacts (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, contact_user_id BIGINT UNSIGNED NOT NULL, remark VARCHAR(100) NULL, status VARCHAR(30) NOT NULL DEFAULT "accepted", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY(user_id, contact_user_id), CONSTRAINT fk_contact_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, CONSTRAINT fk_contact_peer FOREIGN KEY(contact_user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS products (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, name VARCHAR(150) NOT NULL UNIQUE, description TEXT NOT NULL, image_url VARCHAR(500) NULL, spx_price DECIMAL(24,8) NOT NULL DEFAULT 0, stock INT NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 1, sort_order INT NOT NULL DEFAULT 0, product_type VARCHAR(20) NOT NULL DEFAULT "digital", variants LONGTEXT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS product_images (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, product_id BIGINT UNSIGNED NOT NULL, image_url VARCHAR(500) NOT NULL, sort_order INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_product_images (product_id,sort_order,id), CONSTRAINT fk_product_image_product FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS orders (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, product_id BIGINT UNSIGNED NOT NULL, quantity INT UNSIGNED NOT NULL, total_spx DECIMAL(24,8) NOT NULL, stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0, status VARCHAR(30) NOT NULL DEFAULT "pending", order_type VARCHAR(20) NOT NULL DEFAULT "digital", variant_id VARCHAR(64) NULL, variant_name VARCHAR(150) NULL, shipping_address TEXT NULL, carrier VARCHAR(100) NULL, tracking_number VARCHAR(120) NULL, shipped_at DATETIME NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX(user_id,id), CONSTRAINT fk_order_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE, CONSTRAINT fk_order_product FOREIGN KEY(product_id) REFERENCES products(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS wallet_assets (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, symbol VARCHAR(20) NOT NULL, contract_address VARCHAR(100) NULL, chain VARCHAR(30) NOT NULL DEFAULT "BSC", balance DECIMAL(36,18) NOT NULL DEFAULT 0, stakeable_balance DECIMAL(36,18) NOT NULL DEFAULT 0, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, UNIQUE KEY(user_id,symbol,chain), CONSTRAINT fk_wallet_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS wallet_transactions (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, symbol VARCHAR(20) NOT NULL, chain VARCHAR(30) NOT NULL DEFAULT "BSC", type VARCHAR(30) NOT NULL, amount DECIMAL(36,18) NOT NULL, fee_amount DECIMAL(36,18) NOT NULL DEFAULT 0, stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0, address VARCHAR(120) NULL, status VARCHAR(30) NOT NULL DEFAULT "pending", tx_hash VARCHAR(120) NULL, error_message VARCHAR(500) NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX(user_id,id), CONSTRAINT fk_transaction_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS recharge_records (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, tx_hash VARCHAR(66) NOT NULL, amount DECIMAL(36,18) NOT NULL, currency VARCHAR(10) NOT NULL DEFAULT "USDT", wallet_address CHAR(42) NOT NULL, deposit_address CHAR(42) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT "pending", error_message VARCHAR(500) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, UNIQUE KEY uk_recharge_tx_hash (tx_hash), KEY idx_recharge_user (user_id), KEY idx_recharge_status (status), KEY idx_recharge_created (created_at), CONSTRAINT fk_recharge_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS recharge_audit_logs (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, record_id BIGINT UNSIGNED NULL, user_id BIGINT UNSIGNED NOT NULL, tx_hash VARCHAR(66) NOT NULL, amount DECIMAL(36,18) NOT NULL, token VARCHAR(10) NOT NULL, status VARCHAR(20) NOT NULL, from_address VARCHAR(42) NULL, to_address VARCHAR(42) NULL, block_number BIGINT UNSIGNED NULL, confirmations INT NULL, error_message VARCHAR(500) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_recharge_audit_user (user_id), KEY idx_recharge_audit_tx (tx_hash), CONSTRAINT fk_recharge_audit_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS app_settings (`key` VARCHAR(64) PRIMARY KEY, `value` TEXT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS app_releases (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, version_code INT UNSIGNED NOT NULL, version_name VARCHAR(32) NOT NULL, release_notes TEXT NOT NULL, file_path VARCHAR(500) NOT NULL, apk_size BIGINT UNSIGNED NOT NULL, apk_sha256 CHAR(64) NOT NULL, download_count BIGINT UNSIGNED NOT NULL DEFAULT 0, is_active TINYINT NOT NULL DEFAULT 1, admin_id BIGINT UNSIGNED NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uq_app_release_version_code (version_code), KEY idx_app_release_active (is_active,version_code), CONSTRAINT fk_app_release_admin FOREIGN KEY(admin_id) REFERENCES admin_users(id) ON DELETE SET NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS exchange_records (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, usdt_amount DECIMAL(36,18) NOT NULL, spx_amount DECIMAL(36,18) NOT NULL, api_price DECIMAL(36,18) NOT NULL, exchange_price DECIMAL(36,18) NOT NULL, fee_rate DECIMAL(10,8) NOT NULL, request_id VARCHAR(64) NULL UNIQUE, token_id VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT "success", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX idx_exchange_user_created (user_id,created_at), CONSTRAINT fk_exchange_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS user_addresses (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, name VARCHAR(64) NOT NULL, phone VARCHAR(30) NOT NULL, province VARCHAR(80) NOT NULL, city VARCHAR(80) NOT NULL, district VARCHAR(80) NOT NULL, detail VARCHAR(255) NOT NULL, is_default TINYINT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CONSTRAINT fk_address_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS staking_phases (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, phase_no INT UNSIGNED NOT NULL UNIQUE, name VARCHAR(100) NOT NULL, total_quota DECIMAL(36,18) NOT NULL, reserved_quota DECIMAL(36,18) NOT NULL DEFAULT 0, paid_quota DECIMAL(36,18) NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 0, started_at DATETIME NULL, ended_at DATETIME NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX idx_staking_phase_status (status,phase_no)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS staking_records (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, phase_id BIGINT UNSIGNED NULL, plan_days INT NOT NULL, amount DECIMAL(36,18) NOT NULL, apy DECIMAL(8,4) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT "active", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX idx_staking_phase (phase_id), CONSTRAINT fk_staking_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS staking_dividends (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, staking_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, phase_id BIGINT UNSIGNED NULL, period_no INT UNSIGNED NOT NULL, amount DECIMAL(36,18) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uq_staking_dividend_period (staking_id,period_no), INDEX idx_staking_dividend_user (user_id,created_at), INDEX idx_staking_dividend_phase (phase_id), CONSTRAINT fk_dividend_staking FOREIGN KEY(staking_id) REFERENCES staking_records(id) ON DELETE CASCADE, CONSTRAINT fk_dividend_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS staking_plans (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, plan_days INT UNSIGNED NOT NULL, apy DECIMAL(8,4) NOT NULL, minimum_amount DECIMAL(36,18) NOT NULL, status TINYINT NOT NULL DEFAULT 1, sort_order INT NOT NULL DEFAULT 0, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, UNIQUE KEY uq_staking_plan_days (plan_days)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS referral_rewards (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, staking_id BIGINT UNSIGNED NOT NULL UNIQUE, phase_id BIGINT UNSIGNED NULL, referrer_user_id BIGINT UNSIGNED NOT NULL, staker_user_id BIGINT UNSIGNED NOT NULL, staked_amount DECIMAL(36,18) NOT NULL, reward_amount DECIMAL(36,18) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX(referrer_user_id,id), INDEX(staker_user_id), INDEX idx_referral_phase (phase_id), CONSTRAINT fk_referral_stake FOREIGN KEY(staking_id) REFERENCES staking_records(id) ON DELETE CASCADE, CONSTRAINT fk_referral_owner FOREIGN KEY(referrer_user_id) REFERENCES users(id) ON DELETE CASCADE, CONSTRAINT fk_referral_staker FOREIGN KEY(staker_user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS faq_items (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, category VARCHAR(50) NOT NULL, question VARCHAR(255) NOT NULL UNIQUE, answer TEXT NOT NULL, sort_order INT NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 1) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS user_questions (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, question TEXT NOT NULL, answer TEXT NULL, status VARCHAR(30) NOT NULL DEFAULT "pending", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CONSTRAINT fk_question_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS feedback (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, content TEXT NOT NULL, contact VARCHAR(100) NULL, status VARCHAR(30) NOT NULL DEFAULT "pending", created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CONSTRAINT fk_feedback_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS support_tickets (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL, subject VARCHAR(150) NOT NULL, content TEXT NOT NULL, status VARCHAR(30) NOT NULL DEFAULT "open", admin_reply TEXT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX(user_id,id), CONSTRAINT fk_ticket_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS email_notification_recipients (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, email VARCHAR(190) NOT NULL UNIQUE, status TINYINT NOT NULL DEFAULT 1, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS email_notification_templates (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, event_key VARCHAR(50) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL, subject VARCHAR(255) NOT NULL, body_html TEXT NOT NULL, status TINYINT NOT NULL DEFAULT 1, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS email_notification_subscriptions (recipient_id BIGINT UNSIGNED NOT NULL, template_id BIGINT UNSIGNED NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(recipient_id,template_id), CONSTRAINT fk_email_sub_recipient FOREIGN KEY(recipient_id) REFERENCES email_notification_recipients(id) ON DELETE CASCADE, CONSTRAINT fk_email_sub_template FOREIGN KEY(template_id) REFERENCES email_notification_templates(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS email_notification_logs (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, event_key VARCHAR(50) NOT NULL, recipient_email VARCHAR(190) NOT NULL, subject VARCHAR(255) NOT NULL, status VARCHAR(20) NOT NULL, error_message VARCHAR(500) NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX idx_email_log_created (created_at), INDEX idx_email_log_event (event_key,status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
        'CREATE TABLE IF NOT EXISTS burn_sweeps (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, tx_in VARCHAR(66) NOT NULL UNIQUE, from_address VARCHAR(42) NOT NULL, amount_in DECIMAL(36,18) NOT NULL, ratio_percent DECIMAL(8,2) NOT NULL, burn_amount DECIMAL(36,18) NOT NULL, burn_tx VARCHAR(66) NULL, tier_threshold DECIMAL(36,18) NOT NULL DEFAULT 0, burned_total_before DECIMAL(36,18) NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT "pending", error VARCHAR(255) NULL, block_number BIGINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, INDEX idx_status (status), INDEX idx_created (created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',

        'CREATE TABLE IF NOT EXISTS app_version_stats (user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, platform VARCHAR(16) NOT NULL DEFAULT "android", version_code INT UNSIGNED NOT NULL DEFAULT 0, version_name VARCHAR(32) NOT NULL DEFAULT "", first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_ip VARCHAR(45) NULL, INDEX idx_version (version_code), INDEX idx_last_seen (last_seen_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
        'CREATE TABLE IF NOT EXISTS app_version_daily (stat_date DATE NOT NULL, user_id BIGINT UNSIGNED NOT NULL, version_code INT UNSIGNED NOT NULL DEFAULT 0, PRIMARY KEY (stat_date, user_id), INDEX idx_version (stat_date, version_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    ];
    foreach ($tables as $sql) $pdo->exec($sql);
    $emailTemplates = [
        ['recharge_completed', '充值到账', 'SPX Chat 充值到账通知', '<h2>充值已到账</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>到账金额：{{amount}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'],
        ['withdraw_submitted', '提现申请', 'SPX Chat 提现申请通知', '<h2>提现申请</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>申请金额：{{amount}}</p><p>手续费：{{fee}}</p><p>实际到账：{{net_amount}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'],
        ['withdraw_failed', '提现失败', 'SPX Chat 提现失败通知', '<h2>提现失败</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>提现金额：{{amount}}</p><p>失败原因：{{reason}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'],
    ];
    $templateInsert = $pdo->prepare('INSERT IGNORE INTO email_notification_templates (event_key,name,subject,body_html) VALUES (?,?,?,?)');
    foreach ($emailTemplates as $template) $templateInsert->execute($template);
    $pdo->exec('INSERT IGNORE INTO email_notification_recipients (email,status) VALUES ("yishengzaidu@qq.com",1)');
    $recipientId = (int) $pdo->query('SELECT id FROM email_notification_recipients WHERE email="yishengzaidu@qq.com" LIMIT 1')->fetchColumn();
    if ($recipientId > 0) {
        $subscriptionInsert = $pdo->prepare('INSERT IGNORE INTO email_notification_subscriptions (recipient_id,template_id) SELECT ?,id FROM email_notification_templates WHERE event_key IN ("recharge_completed","withdraw_submitted","withdraw_failed")');
        $subscriptionInsert->execute([$recipientId]);
    }
    foreach ([
        'ALTER TABLE products ADD COLUMN product_type VARCHAR(20) NOT NULL DEFAULT "digital" AFTER sort_order',
        'ALTER TABLE products ADD COLUMN variants LONGTEXT NULL AFTER product_type',
        'ALTER TABLE orders ADD COLUMN order_type VARCHAR(20) NOT NULL DEFAULT "digital" AFTER status',
        'ALTER TABLE orders ADD COLUMN variant_id VARCHAR(64) NULL AFTER order_type',
        'ALTER TABLE orders ADD COLUMN variant_name VARCHAR(150) NULL AFTER variant_id',
        'ALTER TABLE orders ADD COLUMN shipping_address TEXT NULL AFTER variant_name',
        'ALTER TABLE orders ADD COLUMN carrier VARCHAR(100) NULL AFTER shipping_address',
        'ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(120) NULL AFTER carrier',
        'ALTER TABLE orders ADD COLUMN shipped_at DATETIME NULL AFTER tracking_number',
        'ALTER TABLE orders ADD COLUMN stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0 AFTER total_spx',
        'ALTER TABLE staking_records ADD COLUMN phase_id BIGINT UNSIGNED NULL AFTER user_id',
        'ALTER TABLE staking_records ADD INDEX idx_staking_phase (phase_id)',
        'ALTER TABLE staking_dividends ADD COLUMN phase_id BIGINT UNSIGNED NULL AFTER user_id',
        'ALTER TABLE staking_dividends ADD INDEX idx_staking_dividend_phase (phase_id)',
        'ALTER TABLE referral_rewards ADD COLUMN phase_id BIGINT UNSIGNED NULL AFTER staking_id',
        'ALTER TABLE referral_rewards ADD INDEX idx_referral_phase (phase_id)',
        'ALTER TABLE wallet_assets ADD COLUMN stakeable_balance DECIMAL(36,18) NOT NULL DEFAULT 0 AFTER balance',
        // 多币种质押：方案、记录、期数都要区分资产
        'ALTER TABLE staking_plans ADD COLUMN symbol VARCHAR(20) NOT NULL DEFAULT "SPX001" AFTER name',
        'ALTER TABLE staking_plans ADD COLUMN step_amount DECIMAL(36,18) NOT NULL DEFAULT 100000 AFTER minimum_amount',
        'ALTER TABLE staking_plans ADD COLUMN max_per_user DECIMAL(36,18) NULL AFTER step_amount',
        'ALTER TABLE staking_plans DROP INDEX uq_staking_plan_days',
        'ALTER TABLE staking_plans ADD UNIQUE KEY uq_staking_plan_symbol_days (symbol, plan_days)',
        'ALTER TABLE staking_records ADD COLUMN symbol VARCHAR(20) NOT NULL DEFAULT "SPX001" AFTER user_id',
        'ALTER TABLE staking_records ADD INDEX idx_staking_symbol (symbol, status)',
        'ALTER TABLE staking_phases ADD COLUMN symbol VARCHAR(20) NOT NULL DEFAULT "SPX001" AFTER phase_no',
        'ALTER TABLE staking_phases DROP INDEX phase_no',
        'ALTER TABLE staking_phases ADD UNIQUE KEY uq_staking_phase_symbol_no (symbol, phase_no)',
    ] as $migration) { try { $pdo->exec($migration); } catch (Throwable $ignored) {} }
    $pdo->exec('INSERT IGNORE INTO staking_phases (phase_no,name,total_quota,status,started_at) VALUES (1,"SPX001 第一期",20000000,1,NOW())');
    $phaseId = (int) $pdo->query('SELECT id FROM staking_phases WHERE phase_no=1 LIMIT 1')->fetchColumn();
    if ($phaseId > 0) {
        $pdo->prepare('UPDATE staking_records SET phase_id=? WHERE phase_id IS NULL')->execute([$phaseId]);
        $pdo->prepare('UPDATE staking_dividends d JOIN staking_records s ON s.id=d.staking_id SET d.phase_id=s.phase_id WHERE d.phase_id IS NULL')->execute();
        $pdo->prepare('UPDATE referral_rewards r JOIN staking_records s ON s.id=r.staking_id SET r.phase_id=s.phase_id WHERE r.phase_id IS NULL')->execute();
        $reserved = $pdo->prepare('SELECT COALESCE(SUM(ROUND(amount*apy*plan_days/36500,18)),0) FROM staking_records WHERE phase_id=?');
        $reserved->execute([$phaseId]);
        $paid = $pdo->prepare('SELECT COALESCE((SELECT SUM(amount) FROM staking_dividends WHERE phase_id=?),0)+COALESCE((SELECT SUM(reward_amount) FROM referral_rewards WHERE phase_id=?),0)');
        $paid->execute([$phaseId, $phaseId]);
        $pdo->prepare('UPDATE staking_phases SET reserved_quota=?,paid_quota=? WHERE id=?')->execute([(string) $reserved->fetchColumn(), (string) $paid->fetchColumn(), $phaseId]);
    }
    $pdo->exec('INSERT IGNORE INTO staking_plans (name,plan_days,apy,minimum_amount,sort_order) VALUES ("90 天锁仓",90,8.2,500,10),("180 天锁仓",180,12.5,1000,20)');
    // FISS 质押：60% 年化、起押 10 万、额度 1 亿；档位/利率/额度都可在后台调整
    $pdo->exec('INSERT IGNORE INTO staking_plans (name,symbol,plan_days,apy,minimum_amount,step_amount,sort_order) VALUES ("FISS 90 天","FISS",90,60.00,100000,1,10)');
    $pdo->exec('INSERT IGNORE INTO staking_phases (phase_no,symbol,name,total_quota,status,started_at) VALUES (1,"FISS","FISS 第一期",100000000,1,NOW())');
    try { $pdo->exec('ALTER TABLE admin_users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT "superadmin" AFTER password_hash'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE admin_audit_logs ADD COLUMN request_id CHAR(32) NULL AFTER target_id'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE admin_audit_logs ADD UNIQUE KEY uq_admin_audit_request_id (request_id)'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE recharge_records MODIFY amount DECIMAL(36,18) NOT NULL'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE recharge_records ADD COLUMN wallet_address CHAR(42) NULL AFTER currency'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE recharge_records ADD COLUMN deposit_address CHAR(42) NULL AFTER wallet_address'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE recharge_audit_logs MODIFY amount DECIMAL(36,18) NOT NULL'); } catch (Throwable $ignored) {}
    $config = require __DIR__ . '/config.php';
    $depositAddress = trim((string) (getenv('FISS_BSC_USDT_DEPOSIT_ADDRESS') ?: ($config['wallet']['bsc_usdt_deposit_address'] ?? '')));
    if ($depositAddress !== '') {
        $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=IF(TRIM(`value`)="",VALUES(`value`),`value`)')->execute(['bsc_usdt_deposit_address', $depositAddress]);
    }
    try { $pdo->exec('ALTER TABLE email_verification_codes ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT "register" AFTER email'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE email_verification_codes ADD COLUMN attempts TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER used_at'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE messages ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT "text" AFTER content'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE messages ADD COLUMN media_url VARCHAR(500) NULL AFTER message_type'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_group_messages ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT "text" AFTER content'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_group_messages ADD COLUMN media_url VARCHAR(500) NULL AFTER message_type'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_group_messages ADD COLUMN mention_user_ids TEXT NULL AFTER media_url'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE messages ADD COLUMN client_message_id CHAR(36) NULL AFTER sender_id'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE messages ADD UNIQUE KEY uq_message_user_sender_client (user_id,sender_id,client_message_id)'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_group_messages ADD COLUMN client_message_id CHAR(36) NULL AFTER sender_id'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_group_messages ADD UNIQUE KEY uq_group_sender_client (group_id,sender_id,client_message_id)'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_group_members ADD COLUMN unread_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER mute'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_group_members ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0 AFTER mute'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE users ADD COLUMN email VARCHAR(190) NULL UNIQUE AFTER username'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE users ADD COLUMN email_verified_at DATETIME NULL AFTER email'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE users ADD COLUMN invited_by_user_id BIGINT UNSIGNED NULL AFTER email_verified_at'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE users ADD COLUMN bsc_wallet_address CHAR(42) NULL AFTER remark'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE users ADD UNIQUE KEY uq_users_bsc_wallet_address (bsc_wallet_address)'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE users ADD COLUMN status TINYINT NOT NULL DEFAULT 1 AFTER bsc_wallet_address'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE wallet_transactions ADD UNIQUE KEY uq_wallet_transaction_hash (tx_hash)'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE wallet_transactions ADD COLUMN fee_amount DECIMAL(36,18) NOT NULL DEFAULT 0 AFTER amount'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE wallet_transactions ADD COLUMN stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0 AFTER fee_amount'); } catch (Throwable $ignored) {}
    try {
        $migrated = $pdo->query('SELECT `value` FROM app_settings WHERE `key`="wallet_stakeable_migration_v1" LIMIT 1')->fetchColumn();
        if ($migrated !== 'completed') {
            $pdo->exec('UPDATE wallet_assets w SET stakeable_balance=GREATEST(0,LEAST(w.balance,COALESCE((SELECT SUM(CASE WHEN t.type="deposit" AND t.status="completed" AND t.symbol="SPX001" THEN t.amount WHEN t.type="admin_adjustment" AND t.symbol="SPX001" AND t.amount>0 THEN t.amount WHEN t.type="staking" AND t.symbol="SPX001" THEN -t.amount WHEN t.type="redeem" AND t.symbol="SPX001" THEN -t.amount WHEN t.type="withdraw" AND t.symbol="SPX001" THEN -(t.amount+t.fee_amount) WHEN t.type="admin_adjustment" AND t.symbol="SPX001" AND t.amount<0 THEN t.amount ELSE 0 END) FROM wallet_transactions t WHERE t.user_id=w.user_id AND t.symbol="SPX001" AND t.chain="BSC"),0))) WHERE w.symbol="SPX001" AND w.chain="BSC"');
            $pdo->exec('INSERT INTO app_settings (`key`,`value`) VALUES ("wallet_stakeable_migration_v1","completed") ON DUPLICATE KEY UPDATE `value`="completed"');
        }
    } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE wallet_transactions ADD COLUMN error_message VARCHAR(500) NULL AFTER tx_hash'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_groups ADD COLUMN allow_member_details TINYINT NOT NULL DEFAULT 1 AFTER announcement'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_groups ADD COLUMN allow_private_message TINYINT NOT NULL DEFAULT 1 AFTER allow_member_details'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_groups ADD COLUMN announcement_version INT UNSIGNED NOT NULL DEFAULT 0 AFTER allow_private_message'); } catch (Throwable $ignored) {}
    try { $pdo->exec('UPDATE chat_groups SET announcement_version=1 WHERE announcement_version=0 AND announcement IS NOT NULL AND TRIM(announcement)<>""'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_groups ADD COLUMN group_code CHAR(6) NULL AFTER id'); } catch (Throwable $ignored) {}
    $groupCodeRowsStatement = $pdo->prepare('SELECT id FROM chat_groups WHERE group_code IS NULL OR group_code NOT REGEXP ?');
    $groupCodeRowsStatement->execute(['^[A-Z0-9]{6}$']);
    $groupCodeRows = $groupCodeRowsStatement->fetchAll(PDO::FETCH_COLUMN);
    foreach ($groupCodeRows as $groupId) {
        for ($attempt = 0; $attempt < 10; $attempt++) {
            $groupCode = generateGroupCode();
            $codeCheck = $pdo->prepare('SELECT id FROM chat_groups WHERE group_code=? LIMIT 1');
            $codeCheck->execute([$groupCode]);
            if ($codeCheck->fetch()) continue;
            try {
                $pdo->prepare('UPDATE chat_groups SET group_code=? WHERE id=?')->execute([$groupCode, $groupId]);
                break;
            } catch (PDOException $exception) {
                if ((string) ($exception->errorInfo[0] ?? '') !== '23000' || (int) ($exception->errorInfo[1] ?? 0) !== 1062 || $attempt === 9) throw $exception;
            }
        }
    }
    try { $pdo->exec('ALTER TABLE chat_groups ADD UNIQUE KEY uq_chat_groups_group_code (group_code)'); } catch (Throwable $ignored) {}
    try { $pdo->exec('ALTER TABLE chat_groups MODIFY group_code CHAR(6) NOT NULL'); } catch (Throwable $ignored) {}
    foreach (['registration_ip VARCHAR(64) NULL', 'device_id VARCHAR(128) NULL', 'device_model VARCHAR(128) NULL', 'android_version VARCHAR(32) NULL', 'app_version VARCHAR(32) NULL'] as $column) {
        try { $pdo->exec('ALTER TABLE users ADD COLUMN ' . $column); } catch (Throwable $ignored) {}
    }
    try { $pdo->exec('ALTER TABLE users ADD INDEX idx_user_inviter (invited_by_user_id), ADD CONSTRAINT fk_user_inviter FOREIGN KEY (invited_by_user_id) REFERENCES users(id) ON DELETE SET NULL'); } catch (Throwable $ignored) {}
    // Hot-path indexes: withdrawal task pick-up, admin dashboards and push-queue pruning all
    // filter on columns the original schema never indexed.
    foreach ([
        'ALTER TABLE wallet_transactions ADD INDEX idx_wallet_tx_type_status (type,status,id)',
        'ALTER TABLE wallet_transactions ADD INDEX idx_wallet_tx_created (created_at)',
        'ALTER TABLE push_queue ADD INDEX idx_push_expires (expires_at)',
        'ALTER TABLE push_queue ADD INDEX idx_push_attempts (attempts)',
    ] as $indexMigration) { try { $pdo->exec($indexMigration); } catch (Throwable $ignored) {} }
    // Optional external mirror for a release APK (CDN / object storage). When set, the
    // download endpoint redirects there instead of streaming the file through PHP.
    try { $pdo->exec('ALTER TABLE app_releases ADD COLUMN apk_url VARCHAR(1024) NULL AFTER apk_sha256'); } catch (Throwable $ignored) {}
    $legacyIds = $pdo->query("SELECT id FROM users WHERE spx_id NOT REGEXP '^spx[0-9]{6}$'")->fetchAll(PDO::FETCH_COLUMN);
    foreach ($legacyIds as $legacyId) {
        do {
            $newSpxId = 'spx' . random_int(100000, 999999);
            $checkId = $pdo->prepare('SELECT id FROM users WHERE spx_id=? LIMIT 1');
            $checkId->execute([$newSpxId]);
        } while ($checkId->fetch());
        $pdo->prepare('UPDATE users SET spx_id=? WHERE id=?')->execute([$newSpxId, $legacyId]);
    }
    installPushSchema($pdo);
    installChatSchema($pdo);
    installMallSchema($pdo);
    installMallCommerceSchema($pdo);
    installMallCnySchema($pdo);
    installMallMerchantMediaSchema($pdo);
    installMallWorkspaceSchema($pdo);
    installMallWorkspaceServiceSchema($pdo);
    installMallRevisionSchema($pdo);
    installMallProductLimitSettings($pdo);
    repairLegacyGroupOwners($pdo);
    $pdo->prepare('INSERT INTO app_settings (`key`,`value`) VALUES (?,?) ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)')->execute(['runtime_schema_fingerprint', $fingerprint]);
    } catch (Throwable $e) { $installed = false; throw $e; }
    finally { $pdo->query("SELECT RELEASE_LOCK('spx_runtime_schema_v2')"); }
}

function walletCreditSplit(PDO $pdo, int $userId, string $symbol, string $chain, string $amount, string $stakeableAmount = '0'): void
{
    if ($userId <= 0 || $amount === '0') return;
    // SPX001 与 FISS 都可以质押：入账时都要同步增加可质押余额，否则用户充值后无法质押。
    $stakeable = in_array($symbol, ['SPX001', 'FISS'], true) ? $stakeableAmount : '0';
    $pdo->prepare('INSERT INTO wallet_assets (user_id,symbol,chain,balance,stakeable_balance) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE balance=balance+VALUES(balance),stakeable_balance=stakeable_balance+VALUES(stakeable_balance)')->execute([$userId, $symbol, $chain, $amount, $stakeable]);
}

function walletCredit(PDO $pdo, int $userId, string $symbol, string $chain, string $amount, bool $stakeable = false): void
{
    walletCreditSplit($pdo, $userId, $symbol, $chain, $amount, $stakeable ? $amount : '0');
}

function walletDebit(PDO $pdo, int $userId, string $symbol, string $chain, string $amount, bool $stakeableOnly = false): string
{
    if ($userId <= 0 || $amount === '0') return '0';
    $assetQuery = $pdo->prepare('SELECT id,balance,stakeable_balance FROM wallet_assets WHERE user_id=? AND symbol=? AND chain=? FOR UPDATE');
    $assetQuery->execute([$userId, $symbol, $chain]);
    $asset = $assetQuery->fetch();
    if (!$asset) throw new InvalidArgumentException('资产账户不存在。');
    $enoughQuery = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18))>=CAST(? AS DECIMAL(36,18))');
    $enoughQuery->execute([$stakeableOnly ? $asset['stakeable_balance'] : $asset['balance'], $amount]);
    if ((int) $enoughQuery->fetchColumn() !== 1) throw new InvalidArgumentException($stakeableOnly ? '可质押资产余额不足。' : '资产余额不足。');
    if ($stakeableOnly) {
        $stakeableUsed = $amount;
    } else {
        $usedQuery = $pdo->prepare('SELECT GREATEST(0,CAST(? AS DECIMAL(36,18))-(CAST(? AS DECIMAL(36,18))-CAST(? AS DECIMAL(36,18))))');
        $usedQuery->execute([$amount, $asset['balance'], $asset['stakeable_balance']]);
        $stakeableUsed = (string) $usedQuery->fetchColumn();
    }
    $pdo->prepare('UPDATE wallet_assets SET balance=balance-CAST(? AS DECIMAL(36,18)),stakeable_balance=stakeable_balance-CAST(? AS DECIMAL(36,18)) WHERE id=?')->execute([$amount, $stakeableUsed, $asset['id']]);
    return $stakeableUsed;
}

function generateGroupCode(): string
{
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    $code = '';
    for ($index = 0; $index < 6; $index++) {
        $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
    }
    return $code;
}
