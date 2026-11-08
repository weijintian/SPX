CREATE DATABASE IF NOT EXISTS `spxchat` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `spxchat`;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  email VARCHAR(190) NULL UNIQUE,
  email_verified_at DATETIME NULL,
  invited_by_user_id BIGINT UNSIGNED NULL,
  nickname VARCHAR(64) NOT NULL,
  spx_id VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  avatar_url VARCHAR(500) NULL,
  remark VARCHAR(100) NULL,
  bsc_wallet_address CHAR(42) NULL UNIQUE,
  status TINYINT NOT NULL DEFAULT 1,
  registration_ip VARCHAR(64) NULL,
  device_id VARCHAR(128) NULL,
  device_model VARCHAR(128) NULL,
  android_version VARCHAR(32) NULL,
  app_version VARCHAR(32) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user_inviter (invited_by_user_id),
  CONSTRAINT fk_user_inviter FOREIGN KEY (invited_by_user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'superadmin',
  status TINYINT NOT NULL DEFAULT 1,
  last_login_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_audit_logs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT UNSIGNED NULL,
  admin_username VARCHAR(64) NOT NULL,
  `action` VARCHAR(64) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_id BIGINT NULL,
  request_id CHAR(32) NULL,
  reason VARCHAR(500) NULL,
  before_data LONGTEXT NULL,
  after_data LONGTEXT NULL,
  ip_address VARCHAR(45) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_admin_audit_request_id (request_id),
  KEY idx_admin_audit_created (created_at),
  KEY idx_admin_audit_target (target_type, target_id),
  KEY idx_admin_audit_actor (admin_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS service_heartbeats (
  service_name VARCHAR(64) PRIMARY KEY,
  status VARCHAR(20) NOT NULL,
  checked_at DATETIME NOT NULL,
  error_message VARCHAR(500) NULL,
  metadata TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_login_attempts (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ip_address VARCHAR(45) NOT NULL,
  username VARCHAR(64) NOT NULL,
  failed_attempts INT UNSIGNED NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_admin_login_ip_user (ip_address, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS email_notification_recipients (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL UNIQUE,
  status TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS email_notification_templates (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(50) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  body_html TEXT NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS email_notification_subscriptions (
  recipient_id BIGINT UNSIGNED NOT NULL,
  template_id BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (recipient_id, template_id),
  CONSTRAINT fk_email_sub_recipient FOREIGN KEY (recipient_id) REFERENCES email_notification_recipients(id) ON DELETE CASCADE,
  CONSTRAINT fk_email_sub_template FOREIGN KEY (template_id) REFERENCES email_notification_templates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS email_notification_logs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  event_key VARCHAR(50) NOT NULL,
  recipient_email VARCHAR(190) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,
  error_message VARCHAR(500) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY idx_email_log_created (created_at),
  KEY idx_email_log_event (event_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS email_verification_codes (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL,
  purpose VARCHAR(30) NOT NULL DEFAULT 'register',
  code_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  used_at DATETIME NULL,
  attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
  request_ip VARCHAR(64) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_email_code (email, purpose, created_at),
  INDEX idx_code_expire (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_payment_passwords (
  user_id BIGINT UNSIGNED PRIMARY KEY,
  password_hash VARCHAR(255) NOT NULL,
  failed_attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_payment_password_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS api_rate_limits (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  scope_key VARCHAR(180) NOT NULL,
  window_start DATETIME NOT NULL,
  attempts INT UNSIGNED NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_api_rate_window (scope_key, window_start),
  KEY idx_api_rate_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE IF NOT EXISTS user_tokens (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  token CHAR(64) NOT NULL UNIQUE,
  expires_at DATETIME NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_token_user (user_id),
  CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_presence (
  user_id BIGINT UNSIGNED NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  is_online TINYINT NOT NULL DEFAULT 0,
  last_seen_at DATETIME NOT NULL,
  PRIMARY KEY (user_id, device_id),
  KEY idx_presence_online (is_online, last_seen_at),
  CONSTRAINT fk_presence_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chats (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  peer_user_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(100) NOT NULL,
  last_message VARCHAR(500) NULL,
  unread_count INT UNSIGNED NOT NULL DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_chat_user_updated (user_id, updated_at),
  CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_peer FOREIGN KEY (peer_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_groups (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(100) NOT NULL,
  avatar_url VARCHAR(500) NULL,
  announcement TEXT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_group_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_group_members (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  group_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'member',
  mute TINYINT NOT NULL DEFAULT 0,
  pinned TINYINT NOT NULL DEFAULT 0,
  unread_count INT UNSIGNED NOT NULL DEFAULT 0,
  joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_group_member (group_id, user_id),
  CONSTRAINT fk_group_member_group FOREIGN KEY (group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
  CONSTRAINT fk_group_member_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS registration_group_admissions (
  user_id BIGINT UNSIGNED PRIMARY KEY,
  group_code CHAR(6) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'pending',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_registration_group_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_group_bans (
  group_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  banned_by BIGINT UNSIGNED NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (group_id,user_id),
  CONSTRAINT fk_group_ban_group FOREIGN KEY (group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
  CONSTRAINT fk_group_ban_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_group_ban_actor FOREIGN KEY (banned_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_group_messages (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  group_id BIGINT UNSIGNED NOT NULL,
  sender_id BIGINT UNSIGNED NOT NULL,
  client_message_id CHAR(36) NULL,
  content TEXT NOT NULL,
  message_type VARCHAR(20) NOT NULL DEFAULT 'text',
  media_url VARCHAR(500) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_group_message (group_id, id),
  UNIQUE KEY uq_group_sender_client (group_id, sender_id, client_message_id),
  CONSTRAINT fk_group_message_group FOREIGN KEY (group_id) REFERENCES chat_groups(id) ON DELETE CASCADE,
  CONSTRAINT fk_group_message_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS messages (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  chat_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  sender_id BIGINT UNSIGNED NOT NULL,
  client_message_id CHAR(36) NULL,
  content TEXT NOT NULL,
  message_type VARCHAR(20) NOT NULL DEFAULT 'text',
  media_url VARCHAR(500) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_message_chat (chat_id, id),
  UNIQUE KEY uq_message_user_sender_client (user_id, sender_id, client_message_id),
  CONSTRAINT fk_message_chat FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE,
  CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS products (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT UNSIGNED NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'approved',
  review_reason VARCHAR(500) NULL,
  category VARCHAR(80) NOT NULL DEFAULT '其他',
  detail_text TEXT NULL,
  detail_images LONGTEXT NULL,
  video_url VARCHAR(500) NULL,
  parameters TEXT NULL,
  service_terms TEXT NULL,
  delivery_note VARCHAR(500) NULL,
  name VARCHAR(150) NOT NULL,
  description TEXT NOT NULL,
  image_url VARCHAR(500) NULL,
  spx_price DECIMAL(24, 8) NOT NULL DEFAULT 0,
  cny_price DECIMAL(24, 8) NULL,
  stock INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  product_type VARCHAR(20) NOT NULL DEFAULT 'digital',
  variants LONGTEXT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_images (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT UNSIGNED NOT NULL,
  image_url VARCHAR(500) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_product_images (product_id, sort_order, id),
  CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  merchant_id BIGINT UNSIGNED NULL,
  product_snapshot LONGTEXT NULL,
  client_request_id CHAR(32) NULL,
  settlement_status VARCHAR(20) NOT NULL DEFAULT 'held',
  received_at DATETIME NULL,
  settled_at DATETIME NULL,
  after_sale_status VARCHAR(20) NOT NULL DEFAULT 'none',
  after_sale_reason VARCHAR(500) NULL,
  after_sale_reply VARCHAR(500) NULL,
  fulfillment_note VARCHAR(500) NULL,
  redeem_code CHAR(16) NULL,
  UNIQUE KEY uq_mall_order_request (user_id,client_request_id),
  quantity INT UNSIGNED NOT NULL,
  total_spx DECIMAL(24, 8) NOT NULL,
  stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL DEFAULT 'pending',
  order_type VARCHAR(20) NOT NULL DEFAULT 'digital',
  variant_id VARCHAR(64) NULL,
  variant_name VARCHAR(150) NULL,
  shipping_address TEXT NULL,
  carrier VARCHAR(100) NULL,
  tracking_number VARCHAR(120) NULL,
  shipped_at DATETIME NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_order_user (user_id, id),
  CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_order_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wallet_assets (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  contract_address VARCHAR(100) NULL,
  chain VARCHAR(30) NOT NULL DEFAULT 'BSC',
  balance DECIMAL(36, 18) NOT NULL DEFAULT 0,
  stakeable_balance DECIMAL(36, 18) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wallet_asset (user_id, symbol, chain),
  CONSTRAINT fk_wallet_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_addresses (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(64) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  province VARCHAR(80) NOT NULL,
  city VARCHAR(80) NOT NULL,
  district VARCHAR(80) NOT NULL,
  detail VARCHAR(255) NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_address_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS staking_phases (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  phase_no INT UNSIGNED NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  total_quota DECIMAL(36,18) NOT NULL,
  reserved_quota DECIMAL(36,18) NOT NULL DEFAULT 0,
  paid_quota DECIMAL(36,18) NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 0,
  started_at DATETIME NULL,
  ended_at DATETIME NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_staking_phase_status (status,phase_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO staking_phases (phase_no,name,total_quota,status,started_at) VALUES (1,'SPX001 第一期',20000000,1,NOW());

CREATE TABLE IF NOT EXISTS staking_records (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  phase_id BIGINT UNSIGNED NULL,
  plan_days INT NOT NULL,
  amount DECIMAL(36, 18) NOT NULL,
  apy DECIMAL(8, 4) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'active',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY idx_staking_phase (phase_id),
  CONSTRAINT fk_staking_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS staking_dividends (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  staking_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  phase_id BIGINT UNSIGNED NULL,
  period_no INT UNSIGNED NOT NULL,
  amount DECIMAL(36, 18) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_staking_dividend_period (staking_id, period_no),
  INDEX idx_staking_dividend_user (user_id, created_at),
  INDEX idx_staking_dividend_phase (phase_id),
  CONSTRAINT fk_dividend_staking FOREIGN KEY (staking_id) REFERENCES staking_records(id) ON DELETE CASCADE,
  CONSTRAINT fk_dividend_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS staking_plans (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  plan_days INT UNSIGNED NOT NULL,
  apy DECIMAL(8,4) NOT NULL,
  minimum_amount DECIMAL(36,18) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_staking_plan_days (plan_days)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO staking_plans (name,plan_days,apy,minimum_amount,sort_order)
VALUES ('90 天锁仓',90,8.2,500,10),('180 天锁仓',180,12.5,1000,20);

CREATE TABLE IF NOT EXISTS referral_rewards (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  staking_id BIGINT UNSIGNED NOT NULL UNIQUE,
  phase_id BIGINT UNSIGNED NULL,
  referrer_user_id BIGINT UNSIGNED NOT NULL,
  staker_user_id BIGINT UNSIGNED NOT NULL,
  staked_amount DECIMAL(36,18) NOT NULL,
  reward_amount DECIMAL(36,18) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_referral_owner (referrer_user_id, id),
  INDEX idx_referral_staker (staker_user_id),
  INDEX idx_referral_phase (phase_id),
  CONSTRAINT fk_referral_stake FOREIGN KEY (staking_id) REFERENCES staking_records(id) ON DELETE CASCADE,
  CONSTRAINT fk_referral_owner FOREIGN KEY (referrer_user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_referral_staker FOREIGN KEY (staker_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_settings (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  chat_id BIGINT UNSIGNED NOT NULL,
  mute TINYINT NOT NULL DEFAULT 0,
  pinned TINYINT NOT NULL DEFAULT 0,
  blocked TINYINT NOT NULL DEFAULT 0,
  background_url VARCHAR(500) NULL,
  UNIQUE KEY uk_chat_setting (user_id, chat_id),
  CONSTRAINT fk_setting_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_setting_chat FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS contacts (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  contact_user_id BIGINT UNSIGNED NOT NULL,
  remark VARCHAR(100) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'accepted',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_contact (user_id, contact_user_id),
  CONSTRAINT fk_contact_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_contact_peer FOREIGN KEY (contact_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wallet_transactions (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  symbol VARCHAR(20) NOT NULL,
  chain VARCHAR(30) NOT NULL DEFAULT 'BSC',
  type VARCHAR(30) NOT NULL,
  amount DECIMAL(36,18) NOT NULL,
  fee_amount DECIMAL(36,18) NOT NULL DEFAULT 0,
  stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0,
  address VARCHAR(120) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'pending',
  tx_hash VARCHAR(120) NULL,
  error_message VARCHAR(500) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_transaction_user (user_id, id),
  CONSTRAINT fk_transaction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recharge_records (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  tx_hash VARCHAR(66) NOT NULL,
  amount DECIMAL(36,18) NOT NULL,
  currency VARCHAR(10) NOT NULL DEFAULT 'USDT',
  wallet_address CHAR(42) NOT NULL,
  deposit_address CHAR(42) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  error_message VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_recharge_tx_hash (tx_hash),
  KEY idx_recharge_user (user_id),
  KEY idx_recharge_status (status),
  KEY idx_recharge_created (created_at),
  CONSTRAINT fk_recharge_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recharge_audit_logs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  record_id BIGINT UNSIGNED NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  tx_hash VARCHAR(66) NOT NULL,
  amount DECIMAL(36,18) NOT NULL,
  token VARCHAR(10) NOT NULL,
  status VARCHAR(20) NOT NULL,
  from_address VARCHAR(42) NULL,
  to_address VARCHAR(42) NULL,
  block_number BIGINT UNSIGNED NULL,
  confirmations INT NULL,
  error_message VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_recharge_audit_user (user_id),
  KEY idx_recharge_audit_tx (tx_hash),
  CONSTRAINT fk_recharge_audit_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_settings (
  `key` VARCHAR(64) PRIMARY KEY,
  `value` TEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS app_releases (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  version_code INT UNSIGNED NOT NULL,
  version_name VARCHAR(32) NOT NULL,
  release_notes TEXT NOT NULL,
  file_path VARCHAR(500) NOT NULL,
  apk_size BIGINT UNSIGNED NOT NULL,
  apk_sha256 CHAR(64) NOT NULL,
  download_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  is_active TINYINT NOT NULL DEFAULT 1,
  admin_id BIGINT UNSIGNED NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_app_release_version_code (version_code),
  KEY idx_app_release_active (is_active, version_code),
  CONSTRAINT fk_app_release_admin FOREIGN KEY (admin_id) REFERENCES admin_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exchange_records (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  usdt_amount DECIMAL(36,18) NOT NULL,
  spx_amount DECIMAL(36,18) NOT NULL,
  api_price DECIMAL(36,18) NOT NULL,
  exchange_price DECIMAL(36,18) NOT NULL,
  fee_rate DECIMAL(10,8) NOT NULL,
  request_id VARCHAR(64) NULL UNIQUE,
  token_id VARCHAR(120) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'success',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_exchange_user_created (user_id, created_at),
  CONSTRAINT fk_exchange_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS faq_items (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  category VARCHAR(50) NOT NULL,
  question VARCHAR(255) NOT NULL UNIQUE,
  answer TEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_questions (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  question TEXT NOT NULL,
  answer TEXT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'pending',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_question_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS feedback (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  content TEXT NOT NULL,
  contact VARCHAR(100) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'pending',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS support_tickets (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  subject VARCHAR(150) NOT NULL,
  content TEXT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'open',
  admin_reply TEXT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_ticket_user (user_id, id),
  CONSTRAINT fk_ticket_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO products (name, description, image_url, spx_price, stock, sort_order)
VALUES
('SPX 生态会员', '商城专属折扣、质押权益和客服优先', 'https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?w=900', 1280, 9999, 1),
('数字权益礼包', '兑换生态服务和限量数字权益', 'https://images.unsplash.com/photo-1517245386807-bb43f82c33c4?w=900', 680, 9999, 2),
('SPX 周边礼盒', '限量周边，支持 SPX 兑换', 'https://images.unsplash.com/photo-1600185365483-26d7a4cc7519?w=900', 320, 9999, 3);

INSERT IGNORE INTO faq_items (category, question, answer, sort_order, status)
VALUES
('商城', 'SPX 商城如何兑换商品？', '选择商品后确认兑换数量，系统会从 SPX 余额扣除兑换额度并生成订单。', 1, 1),
('质押', '质押收益如何计算？', '收益按照质押方案年化率和有效质押天数计算，实际规则以平台公布为准。', 2, 1),
('账户', '如何修改头像和昵称？', '进入我的页面，点击编辑资料即可修改头像和昵称。', 3, 1);

INSERT IGNORE INTO email_notification_templates (event_key, name, subject, body_html)
VALUES
('recharge_completed', '充值到账', 'SPX Chat 充值到账通知', '<h2>充值已到账</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>到账金额：{{amount}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'),
('withdraw_submitted', '提现申请', 'SPX Chat 提现申请通知', '<h2>提现申请</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>申请金额：{{amount}}</p><p>手续费：{{fee}}</p><p>实际到账：{{net_amount}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>'),
('withdraw_failed', '提现失败', 'SPX Chat 提现失败通知', '<h2>提现失败</h2><p>用户：{{username}}（{{nickname}}）</p><p>用户 ID：{{user_id}}</p><p>资产：{{symbol}}</p><p>提现金额：{{amount}}</p><p>失败原因：{{reason}}</p><p>状态：{{status}}</p><p>时间：{{created_at}}</p>');

INSERT IGNORE INTO email_notification_recipients (email, status) VALUES ('yishengzaidu@qq.com', 1);
INSERT IGNORE INTO email_notification_subscriptions (recipient_id, template_id)
SELECT r.id, t.id
FROM email_notification_recipients r
JOIN email_notification_templates t ON t.event_key IN ('recharge_completed', 'withdraw_submitted', 'withdraw_failed')
WHERE r.email = 'yishengzaidu@qq.com';

-- Multi-merchant marketplace tables (runtime migration also upgrades legacy columns).
CREATE TABLE IF NOT EXISTS merchants (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  owner_user_id BIGINT UNSIGNED NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  logo_url VARCHAR(500) NULL,
  description TEXT NULL,
  category VARCHAR(80) NOT NULL,
  contact_name VARCHAR(80) NOT NULL,
  contact_phone VARCHAR(40) NOT NULL,
  qualification TEXT NULL,
  level VARCHAR(20) NOT NULL DEFAULT 'basic',
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  review_reason VARCHAR(500) NULL,
  deposit_frozen DECIMAL(36,18) NOT NULL DEFAULT 0,
  deposit_stakeable DECIMAL(36,18) NOT NULL DEFAULT 0,
  address VARCHAR(255) NULL,
  latitude DECIMAL(10,7) NULL,
  longitude DECIMAL(10,7) NULL,
  coordinate_system VARCHAR(20) NOT NULL DEFAULT 'gcj02',
  business_hours VARCHAR(120) NULL,
  close_requested_at DATETIME NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_merchants_public (status,level,id),
  CONSTRAINT fk_mall_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_deposit_ledger (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT UNSIGNED NOT NULL,
  type VARCHAR(30) NOT NULL,
  amount DECIMAL(36,18) NOT NULL,
  stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0,
  balance_after DECIMAL(36,18) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY idx_deposit_merchant (merchant_id,id),
  CONSTRAINT fk_mall_deposit FOREIGN KEY (merchant_id) REFERENCES merchants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_audit_logs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT UNSIGNED NOT NULL,
  actor_user_id BIGINT UNSIGNED NULL,
  action VARCHAR(64) NOT NULL,
  target_id BIGINT UNSIGNED NULL,
  metadata TEXT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY idx_mall_audit (merchant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
