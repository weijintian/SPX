<?php
declare(strict_types=1);

/**
 * 商家自动结算：
 *   - 到店核销（order_type=local）：核销满 24 小时后结算
 *   - 快递/数字商品：确认收货满 7 天售后期后结算
 * 手续费使用订单创建时的快照（settlement_fee_rate），这里只负责放款 + 记账。
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/mall_revision.php';
require_once __DIR__ . '/mall_commerce.php';

/** 发货后多少天自动确认收货（后台可改，默认 3 天）。 */
function mallAutoConfirmDays(PDO $pdo): int
{
    static $days = null;
    if ($days !== null) return $days;
    $value = 0;
    try {
        $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=?');
        $q->execute(['mall_auto_confirm_days']);
        $value = (int) ($q->fetchColumn() ?: 0);
    } catch (Throwable $ignored) {
    }
    return $days = ($value >= 1 && $value <= 99) ? $value : 3;
}

/** 发货超过自动确认天数的订单，视为买家已收货。 */
function mallAutoConfirmShippedOrders(PDO $pdo, bool $dryRun = false): array
{
    $days = mallAutoConfirmDays($pdo);
    $stats = ['days' => $days, 'scanned' => 0, 'confirmed' => 0, 'failed' => 0];
    $q = $pdo->prepare("SELECT id FROM orders
                        WHERE status='shipped' AND shipped_at IS NOT NULL
                          AND shipped_at <= DATE_SUB(NOW(), INTERVAL ? DAY)
                          AND (after_sale_status IS NULL OR after_sale_status NOT IN ('requested','return_approved','return_shipped'))
                        ORDER BY id ASC LIMIT 200");
    $q->execute([$days]);
    $ids = $q->fetchAll(PDO::FETCH_COLUMN);
    $stats['scanned'] = count($ids);
    foreach ($ids as $rawId) {
        $orderId = (int) $rawId;
        if ($dryRun) { $stats['confirmed']++; continue; }
        try {
            $pdo->beginTransaction();
            $lock = $pdo->prepare('SELECT status,shipped_at FROM orders WHERE id=? FOR UPDATE');
            $lock->execute([$orderId]);
            $row = $lock->fetch();
            if (!$row || $row['status'] !== 'shipped' || !$row['shipped_at'] || strtotime((string) $row['shipped_at']) > time() - $days * 86400) {
                $pdo->rollBack();
                continue;
            }
            $pdo->prepare("UPDATE orders SET status='delivered',received_at=NOW() WHERE id=?")->execute([$orderId]);
            mallEvent($pdo, $orderId, 'auto_received', '发货满 ' . $days . ' 天自动确认收货');
            $pdo->commit();
            $stats['confirmed']++;
        } catch (Throwable $exception) {
            if ($pdo->inTransaction()) $pdo->rollBack();
            $stats['failed']++;
            error_log('mall auto confirm ' . $orderId . ' failed: ' . $exception->getMessage());
        }
    }
    return $stats;
}

/** 到店核销后多少小时可结算。 */
const MALL_SETTLE_LOCAL_HOURS = 24;
/** 实物/数字商品确认收货后多少天可结算（与售后保护期一致）。 */
const MALL_SETTLE_REMOTE_DAYS = 7;

function mallSettleDueOrders(PDO $pdo, bool $dryRun = false): array
{
    $stats = ['scanned' => 0, 'settled' => 0, 'skipped' => 0, 'failed' => 0, 'net' => '0', 'fee' => '0', 'orders' => []];

    $sql = "SELECT id FROM orders
            WHERE settlement_status='held'
              AND status='delivered'
              AND received_at IS NOT NULL
              AND (after_sale_status IS NULL OR after_sale_status NOT IN ('requested','return_approved','return_shipped'))
              AND (
                    (order_type='local'       AND received_at <= DATE_SUB(NOW(), INTERVAL " . MALL_SETTLE_LOCAL_HOURS . " HOUR))
                 OR (order_type<>'local'      AND received_at <= DATE_SUB(NOW(), INTERVAL " . MALL_SETTLE_REMOTE_DAYS . " DAY))
              )
            ORDER BY id ASC
            LIMIT 200";

    $ids = $pdo->query($sql)->fetchAll(PDO::FETCH_COLUMN);
    $stats['scanned'] = count($ids);
    if (!$ids) return $stats;

    $lock = $dryRun ? true : (int) $pdo->query("SELECT GET_LOCK('spx_mall_settle', 20)")->fetchColumn() === 1;
    if (!$lock) {
        $stats['skipped'] = count($ids);
        return $stats;
    }

    try {
        foreach ($ids as $rawId) {
            $orderId = (int) $rawId;
            if ($dryRun) {
                $q = $pdo->prepare('SELECT id,order_type,merchant_id,total_spx,settlement_fee,merchant_net_amount,settlement_fee_rate,received_at FROM orders WHERE id=?');
                $q->execute([$orderId]);
                $row = $q->fetch();
                if ($row) {
                    $stats['net'] = bcadd($stats['net'], (string) ($row['merchant_net_amount'] ?? '0'), 8);
                    $stats['fee'] = bcadd($stats['fee'], (string) ($row['settlement_fee'] ?? '0'), 8);
                    $stats['orders'][] = $row;
                }
                $stats['settled']++;
                continue;
            }
            try {
                $pdo->beginTransaction();
                $q = $pdo->prepare('SELECT * FROM orders WHERE id=? FOR UPDATE');
                $q->execute([$orderId]);
                $order = $q->fetch();
                if (!$order || $order['settlement_status'] !== 'held' || $order['status'] !== 'delivered' || !$order['received_at']) {
                    $pdo->rollBack();
                    $stats['skipped']++;
                    continue;
                }
                $received = strtotime((string) $order['received_at']);
                $dueAt = $order['order_type'] === 'local'
                    ? $received + MALL_SETTLE_LOCAL_HOURS * 3600
                    : $received + MALL_SETTLE_REMOTE_DAYS * 86400;
                if ($dueAt > time()) {
                    $pdo->rollBack();
                    $stats['skipped']++;
                    continue;
                }
                $note = $order['order_type'] === 'local'
                    ? '到店核销满 ' . MALL_SETTLE_LOCAL_HOURS . ' 小时，平台自动结算'
                    : '确认收货满 ' . MALL_SETTLE_REMOTE_DAYS . ' 天售后期，平台自动结算';

                if ((int) $order['merchant_id'] > 0) {
                    $mq = $pdo->prepare('SELECT owner_user_id,status FROM merchants WHERE id=?');
                    $mq->execute([(int) $order['merchant_id']]);
                    $merchant = $mq->fetch();
                    if (!$merchant || !in_array($merchant['status'], ['active', 'closing'], true)) {
                        $pdo->rollBack();
                        $stats['failed']++;
                        continue;
                    }
                    mallSettleMerchantOrder($pdo, $order, $merchant);
                } else {
                    $pdo->prepare("UPDATE orders SET settlement_status='settled',settled_at=NOW() WHERE id=?")->execute([$orderId]);
                }
                mallEvent($pdo, $orderId, 'settled', $note);
                $pdo->commit();
                $stats['settled']++;
                $stats['net'] = bcadd($stats['net'], (string) ($order['merchant_net_amount'] ?? '0'), 8);
                $stats['fee'] = bcadd($stats['fee'], (string) ($order['settlement_fee'] ?? '0'), 8);
                $stats['orders'][] = ['id' => $orderId, 'order_type' => $order['order_type'], 'merchant_id' => (int) $order['merchant_id'], 'net' => (string) ($order['merchant_net_amount'] ?? '0'), 'fee' => (string) ($order['settlement_fee'] ?? '0')];
            } catch (Throwable $exception) {
                if ($pdo->inTransaction()) $pdo->rollBack();
                $stats['failed']++;
                error_log('mall settle order ' . $orderId . ' failed: ' . $exception->getMessage());
            }
        }
    } finally {
        if (!$dryRun) {
            try { $pdo->query("SELECT RELEASE_LOCK('spx_mall_settle')"); } catch (Throwable $ignored) {}
        }
    }
    return $stats;
}
