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
