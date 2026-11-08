<?php
declare(strict_types=1);
function installChatSchema(PDO $pdo): void
{
    $pdo->exec("CREATE TABLE IF NOT EXISTS chat_red_packets (
        id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, sender_id BIGINT UNSIGNED NOT NULL,
        request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, request_hash CHAR(64) NOT NULL,
        scope VARCHAR(10) NOT NULL, target_id BIGINT UNSIGNED NOT NULL, mode VARCHAR(10) NOT NULL, greeting VARCHAR(200) NOT NULL,
        symbol VARCHAR(16) NOT NULL DEFAULT 'SPX001', chain VARCHAR(16) NOT NULL DEFAULT 'BSC',
        total_units BIGINT UNSIGNED NOT NULL, remaining_units BIGINT UNSIGNED NOT NULL, refunded_units BIGINT UNSIGNED NOT NULL DEFAULT 0,
        total_count INT NOT NULL, claimed_count INT NOT NULL DEFAULT 0, remaining_stakeable DECIMAL(36,18) NOT NULL DEFAULT 0,
        status VARCHAR(16) NOT NULL DEFAULT 'active', message_json TEXT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, expires_at DATETIME NOT NULL,
        UNIQUE KEY uq_red_packet_request(sender_id,request_id), INDEX(status,expires_at), INDEX(sender_id,created_at),
        FOREIGN KEY(sender_id) REFERENCES users(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    foreach (['symbol' => "VARCHAR(16) NOT NULL DEFAULT 'SPX001'", 'chain' => "VARCHAR(16) NOT NULL DEFAULT 'BSC'"] as $column => $definition) {
        if (!$pdo->query("SHOW COLUMNS FROM chat_red_packets LIKE " . $pdo->quote($column))->fetch()) {
            $pdo->exec("ALTER TABLE chat_red_packets ADD COLUMN $column $definition");
        }
    }
    $pdo->exec("CREATE TABLE IF NOT EXISTS chat_red_packet_eligible (
        packet_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,user_id BIGINT UNSIGNED NOT NULL, PRIMARY KEY(packet_id,user_id),
        FOREIGN KEY(packet_id) REFERENCES chat_red_packets(id),FOREIGN KEY(user_id) REFERENCES users(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS chat_red_packet_claims (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,packet_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,user_id BIGINT UNSIGNED NOT NULL,
        amount DECIMAL(36,18) NOT NULL,stakeable_amount DECIMAL(36,18) NOT NULL DEFAULT 0,claimed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uq_red_packet_claim(packet_id,user_id),INDEX(user_id,id),
        FOREIGN KEY(packet_id) REFERENCES chat_red_packets(id),FOREIGN KEY(user_id) REFERENCES users(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    foreach (['messages','chat_group_messages'] as $table) {
        $reply=$pdo->query("SHOW COLUMNS FROM $table LIKE 'reply_to_message_id'");
        if (!$reply->fetch()) $pdo->exec("ALTER TABLE $table ADD COLUMN reply_to_message_id BIGINT UNSIGNED NULL");
        $q=$pdo->query("SHOW COLUMNS FROM $table LIKE 'message_origin'");
        if (!$q->fetch()) $pdo->exec("ALTER TABLE $table ADD COLUMN message_origin VARCHAR(20) NOT NULL DEFAULT 'human'");
    }
    foreach (['chats','chat_group_members'] as $table) {
        if (!$pdo->query("SHOW COLUMNS FROM $table LIKE 'last_read_message_id'")->fetch()) $pdo->exec("ALTER TABLE $table ADD COLUMN last_read_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0");
    }
    $pdo->exec("CREATE TABLE IF NOT EXISTS chat_scheduled_messages (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL,
        scope VARCHAR(10) NOT NULL, target_id BIGINT UNSIGNED NOT NULL, request_id VARCHAR(36) NOT NULL,
        message_type VARCHAR(20) NOT NULL, content TEXT NOT NULL, media_url VARCHAR(500) NULL,
        send_at DATETIME NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'pending', error_message VARCHAR(500) NULL,
        delivered_message_id BIGINT UNSIGNED NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, sent_at DATETIME NULL,
        UNIQUE KEY uq_schedule_request (user_id,request_id), INDEX idx_schedule_due(status,send_at), INDEX(user_id,id),
        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS chat_reply_rules (
        id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, owner_user_id BIGINT UNSIGNED NOT NULL,
        group_id BIGINT UNSIGNED NOT NULL DEFAULT 0, keyword VARCHAR(100) NOT NULL DEFAULT '',
        match_mode VARCHAR(12) NOT NULL DEFAULT 'contains', reply_text TEXT NOT NULL,
        enabled TINYINT NOT NULL DEFAULT 1, cooldown_seconds INT NOT NULL DEFAULT 60,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        INDEX(owner_user_id,group_id), INDEX(group_id,enabled),
        FOREIGN KEY(owner_user_id) REFERENCES users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS chat_reply_cooldowns (
        rule_id BIGINT UNSIGNED NOT NULL, conversation_key VARCHAR(64) NOT NULL, replied_at DATETIME NOT NULL,
        PRIMARY KEY(rule_id,conversation_key), FOREIGN KEY(rule_id) REFERENCES chat_reply_rules(id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
}
