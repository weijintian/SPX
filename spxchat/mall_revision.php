<?php
declare(strict_types=1);

/** Versioned, resumable DDL. Existing receipts retain the terms at purchase. */
function installMallRevisionSchema(PDO $pdo): void
{
    if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=7')->fetchColumn()) return;
    foreach ([
        'products' => ['deleted_at' => 'DATETIME NULL'],
        'orders' => ['settlement_fee_rate' => 'DECIMAL(5,4) NOT NULL DEFAULT 0', 'settlement_fee' => 'DECIMAL(24,8) NOT NULL DEFAULT 0', 'merchant_net_amount' => 'DECIMAL(24,8) NULL'],
    ] as $table => $columns) {
        $existing = $pdo->query("SHOW COLUMNS FROM `$table`")->fetchAll(PDO::FETCH_COLUMN);
        foreach ($columns as $name => $type) if (!in_array($name, $existing, true)) $pdo->exec("ALTER TABLE `$table` ADD COLUMN `$name` $type");
    }
    $pdo->exec('UPDATE orders SET merchant_net_amount=total_spx WHERE merchant_net_amount IS NULL');
    $pdo->exec("CREATE TABLE IF NOT EXISTS mall_settlement_ledger (order_id BIGINT UNSIGNED PRIMARY KEY, merchant_id BIGINT UNSIGNED NOT NULL, gross_amount DECIMAL(24,8) NOT NULL, fee_rate DECIMAL(5,4) NOT NULL, fee_amount DECIMAL(24,8) NOT NULL, net_amount DECIMAL(24,8) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, INDEX(merchant_id,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec('INSERT IGNORE INTO mall_schema_versions(version) VALUES(7)');
}

/** Repair only identities proved by owner_user_id; never guess from join order. */
function repairLegacyGroupOwners(PDO $pdo): void
{
    $ids = $pdo->query("SELECT DISTINCT g.id FROM chat_groups g LEFT JOIN chat_group_members gm ON gm.group_id=g.id WHERE (gm.user_id=g.owner_user_id AND gm.role<>'owner') OR (gm.role='owner' AND gm.user_id<>g.owner_user_id) OR NOT EXISTS (SELECT 1 FROM chat_group_members own WHERE own.group_id=g.id AND own.user_id=g.owner_user_id)")->fetchAll(PDO::FETCH_COLUMN);
    foreach ($ids as $id) {
        $pdo->beginTransaction();
        try {
            $q = $pdo->prepare('SELECT owner_user_id FROM chat_groups WHERE id=? FOR UPDATE'); $q->execute([$id]);
            $owner = (int)$q->fetchColumn();
            if ($owner > 0) {
                $pdo->prepare("UPDATE chat_group_members SET role='member' WHERE group_id=? AND role='owner' AND user_id<>?")->execute([$id, $owner]);
                $pdo->prepare("INSERT INTO chat_group_members(group_id,user_id,role) VALUES(?,?,'owner') ON DUPLICATE KEY UPDATE role='owner'")->execute([$id, $owner]);
            }
            $pdo->commit();
        } catch (Throwable $e) { if ($pdo->inTransaction()) $pdo->rollBack(); throw $e; }
    }
}

/** Both clients and publication checks use the same persisted admin settings. */
function mallProductLimits(PDO $pdo): array
{
    // A locking current read prevents a transaction from using a stale setting
    // after waiting for the shop lock. Shared locks allow different shops to publish.
    $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=?'.($pdo->inTransaction() ? ' LOCK IN SHARE MODE' : ''));
    $q->execute(['mall_product_limits']);
    $limits = json_decode((string)$q->fetchColumn(), true);
    foreach (['basic', 'gold'] as $level) {
        if (!isset($limits[$level]) || !is_int($limits[$level]) || $limits[$level] < 0 || $limits[$level] > 1000000) {
            throw new RuntimeException('店铺上架名额配置异常，请联系平台管理员');
        }
    }
    return ['basic' => $limits['basic'], 'gold' => $limits['gold']];
}

function installMallProductLimitSettings(PDO $pdo): void
{
    if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=8')->fetchColumn()) return;
    // Only initialize a missing setting; upgrades must not reset admin edits.
    $pdo->prepare('INSERT IGNORE INTO app_settings (`key`,`value`) VALUES (?,?)')->execute(['mall_product_limits', json_encode(['basic'=>6, 'gold'=>20])]);
    $pdo->exec('INSERT IGNORE INTO mall_schema_versions(version) VALUES(8)');
}

function mallProductCapacity(PDO $pdo, array $merchant): array
{
    $limits = mallProductLimits($pdo);
    $limit = $limits[$merchant['level'] === 'gold' ? 'gold' : 'basic'];
    // Mutations must use a current read even if an earlier read established a
    // REPEATABLE READ snapshot before waiting for the merchant lock.
    $locked = $pdo->inTransaction();
    $q = $pdo->prepare('SELECT '.($locked ? 'id' : 'COUNT(*)').' FROM products WHERE merchant_id=? AND status=1 AND deleted_at IS NULL'.($locked ? ' FOR UPDATE' : ''));
    $q->execute([$merchant['id']]);
    $used = $locked ? count($q->fetchAll(PDO::FETCH_COLUMN)) : (int)$q->fetchColumn();
    return ['listed' => $used, 'limit' => $limit, 'remaining' => max(0, $limit-$used)];
}

/** Caller holds the merchant row lock so concurrent publishes cannot exceed the limit. */
function mallRequireProductSlot(PDO $pdo, array $merchant, ?array $existing = null): void
{
    if (!$pdo->inTransaction()) throw new LogicException('Publishing requires a transaction');
    if ($existing && (int)$existing['status'] === 1 && empty($existing['deleted_at'])) return;
    $capacity = mallProductCapacity($pdo, $merchant);
    if ($capacity['remaining'] === 0) throw new InvalidArgumentException('已达到上架上限（'.$capacity['listed'].'/'.$capacity['limit'].'），请先下架或删除商品后再上架');
}

function mallRemoveProduct(PDO $pdo, array $merchant, int $id, bool $delete): void
{
    if (!$pdo->inTransaction()) throw new LogicException('Product mutation requires a transaction');
    $q = $pdo->prepare('SELECT * FROM products WHERE id=? AND merchant_id=? FOR UPDATE'); $q->execute([$id, $merchant['id']]);
    $p = $q->fetch();
    if (!$p) throw new InvalidArgumentException('商品不存在或无权操作');
    if (!empty($p['deleted_at'])) {
        if ($delete) return; // A lost response can be retried safely.
        throw new InvalidArgumentException('商品已删除，请刷新列表');
    }
    if ($delete && (int)$p['status'] !== 0) throw new InvalidArgumentException('请先下架商品，再删除');
    if ($delete) {
        $pdo->prepare('UPDATE products SET deleted_at=NOW(),version=version+1,inventory_version=inventory_version+1 WHERE id=?')->execute([$id]);
        $pdo->prepare('DELETE FROM mall_product_drafts WHERE merchant_id=? AND product_id=?')->execute([$merchant['id'], $id]);
        $pdo->prepare('DELETE FROM mall_favorites WHERE product_id=?')->execute([$id]);
    } elseif ((int)$p['status'] === 0) return;
    else $pdo->prepare('UPDATE products SET status=0,version=version+1 WHERE id=?')->execute([$id]);
    // Preserve the product row, SKU snapshots and media for existing orders/refunds.
    mallAudit($pdo, $merchant, $delete ? 'product.delete' : 'product.offline', $id);
}

/** Called with user, merchant and order locks already held by the finance action. */
function mallSettleMerchantOrder(PDO $pdo, array $order, array $merchant): array
{
    if (!$pdo->inTransaction() || $order['settlement_status'] !== 'held') throw new LogicException('Order is not held for settlement');
    $gross = (string)$order['total_spx'];
    $fee = (string)$order['settlement_fee'];
    $net = (string)($order['merchant_net_amount'] ?? $gross);
    // Validate the immutable snapshot with decimal SQL, never binary floating point.
    $q = $pdo->prepare('SELECT CAST(? AS DECIMAL(24,8))=CAST(? AS DECIMAL(24,8))+CAST(? AS DECIMAL(24,8)) AND CAST(? AS DECIMAL(24,8))>=0 AND CAST(? AS DECIMAL(24,8))>=0 AND CAST(? AS DECIMAL(5,4)) BETWEEN 0 AND 1 AND CAST(? AS DECIMAL(24,8))=TRUNCATE(CAST(? AS DECIMAL(24,8))*CAST(? AS DECIMAL(5,4)),8)');
    $q->execute([$gross, $fee, $net, $fee, $net, $order['settlement_fee_rate'], $fee, $gross, $order['settlement_fee_rate']]);
    if (!(int)$q->fetchColumn()) throw new RuntimeException('订单结算金额异常，请核对');
    // Unique order_id also prevents a second credit if settlement status is damaged.
    $pdo->prepare('INSERT INTO mall_settlement_ledger(order_id,merchant_id,gross_amount,fee_rate,fee_amount,net_amount) VALUES(?,?,?,?,?,?)')->execute([$order['id'], $order['merchant_id'], $gross, $order['settlement_fee_rate'], $fee, $net]);
    walletCredit($pdo, (int)$merchant['owner_user_id'], 'SPX001', 'BSC', $net);
    $pdo->prepare("INSERT INTO wallet_transactions(user_id,symbol,chain,type,amount,status,mall_order_id,mall_merchant_id) VALUES(?,'SPX001','BSC','merchant_settlement',?,'completed',?,?)")->execute([$merchant['owner_user_id'], $net, $order['id'], $order['merchant_id']]);
    $pdo->prepare("UPDATE orders SET settlement_status='settled',settled_at=NOW() WHERE id=?")->execute([$order['id']]);
    return ['gross_amount'=>$gross, 'fee_amount'=>$fee, 'net_amount'=>$net, 'fee_rate'=>(string)$order['settlement_fee_rate']];
}
