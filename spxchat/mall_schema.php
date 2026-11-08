<?php
declare(strict_types=1);

/** Additive migration; errors other than a concurrent duplicate are surfaced. */
function installMallSchema(PDO $pdo): void
{
    $pdo->exec('CREATE TABLE IF NOT EXISTS mall_schema_versions (version INT PRIMARY KEY, installed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB');
    if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=1')->fetchColumn()) return;
    if ((int) $pdo->query("SELECT GET_LOCK('spx_mall_schema_v1',30)")->fetchColumn() !== 1) throw new RuntimeException('商城数据库正在升级，请稍后重试');
    try {
    if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=1')->fetchColumn()) return;
    $sql = file_get_contents(__DIR__ . '/mall_schema.sql');
    foreach (explode(';', (string) $sql) as $statement) {
        if (trim($statement) !== '') $pdo->exec($statement);
    }
    $columns = [
        'products' => [
            'merchant_id' => 'BIGINT UNSIGNED NULL',
            'review_status' => "VARCHAR(20) NOT NULL DEFAULT 'approved'",
            'review_reason' => 'VARCHAR(500) NULL',
            'category' => "VARCHAR(80) NOT NULL DEFAULT '其他'",
            'detail_text' => 'TEXT NULL',
            'detail_images' => 'LONGTEXT NULL',
            'video_url' => 'VARCHAR(500) NULL',
            'parameters' => 'TEXT NULL',
            'service_terms' => 'TEXT NULL',
            'delivery_note' => 'VARCHAR(500) NULL',
        ],
        'orders' => [
            'merchant_id' => 'BIGINT UNSIGNED NULL',
            'product_snapshot' => 'LONGTEXT NULL',
            'client_request_id' => 'CHAR(32) NULL',
            'settlement_status' => "VARCHAR(20) NOT NULL DEFAULT 'held'",
            'received_at' => 'DATETIME NULL',
            'settled_at' => 'DATETIME NULL',
            'after_sale_status' => "VARCHAR(20) NOT NULL DEFAULT 'none'",
            'after_sale_reason' => 'VARCHAR(500) NULL',
            'after_sale_reply' => 'VARCHAR(500) NULL',
            'fulfillment_note' => 'VARCHAR(500) NULL',
            'redeem_code' => 'CHAR(16) NULL',
        ],
    ];
    foreach ($columns as $table => $definitions) {
        $existing = $pdo->query('SHOW COLUMNS FROM `' . $table . '`')->fetchAll(PDO::FETCH_COLUMN);
        foreach ($definitions as $column => $definition) {
            if (in_array($column, $existing, true)) continue;
            try { $pdo->exec("ALTER TABLE `$table` ADD COLUMN `$column` $definition"); }
            catch (PDOException $e) { if ((int) ($e->errorInfo[1] ?? 0) !== 1060) throw $e; }
        }
    }
    // The legacy runtime schema made names globally unique; different shops may sell the same item.
    foreach ($pdo->query('SHOW INDEX FROM products')->fetchAll() as $index) {
        if ($index['Column_name'] === 'name' && (int) $index['Non_unique'] === 0 && $index['Key_name'] !== 'PRIMARY') {
            $pdo->exec('ALTER TABLE products DROP INDEX `' . str_replace('`', '``', $index['Key_name']) . '`');
        }
    }
    foreach ([
        'ALTER TABLE products ADD INDEX idx_mall_product_owner (merchant_id,review_status,status)',
        'ALTER TABLE orders ADD INDEX idx_mall_order_owner (merchant_id,settlement_status,status)',
        'ALTER TABLE orders ADD UNIQUE INDEX uq_mall_order_request (user_id,client_request_id)',
        'ALTER TABLE products ADD CONSTRAINT fk_mall_product_owner FOREIGN KEY (merchant_id) REFERENCES merchants(id)',
        'ALTER TABLE orders ADD CONSTRAINT fk_mall_order_owner FOREIGN KEY (merchant_id) REFERENCES merchants(id)',
    ] as $migration) {
        try { $pdo->exec($migration); }
        catch (PDOException $e) { if (!in_array((int) ($e->errorInfo[1] ?? 0), [1061, 1826], true)) throw $e; }
    }
    $pdo->exec('INSERT IGNORE INTO mall_schema_versions (version) VALUES (1)');
    } finally {
        $pdo->query("SELECT RELEASE_LOCK('spx_mall_schema_v1')");
    }
}
