<?php
declare(strict_types=1);

function mallAdminMoney(mixed $value): string
{
    $raw=trim((string)$value);
    return adminEscape(str_contains($raw,'.') ? rtrim(rtrim($raw,'0'),'.') : $raw);
}

function mallAdminPost(PDO $pdo, array $admin, string $action): void
{
    if (!str_starts_with($action, 'mall.')) return;
    $module = $action === 'mall.order.settle' || $action === 'mall.deposit.release' ? 'mall_finance' : 'merchants';
    if (!adminCan($module)) throw new RuntimeException('无权执行商城操作');
    $reason = adminPostString('reason', 500);
    if ($reason === '') throw new InvalidArgumentException('请填写操作原因');
    $pdo->beginTransaction();
    // Acquire the credited user before shop/order/product locks, as checkout does.
    $walletUserId=0;
    if ($action==='mall.order.refund') {
        $q=$pdo->prepare("SELECT user_id FROM orders WHERE id=?");$q->execute([(int)($_POST['order_id']??0)]);$walletUserId=(int)$q->fetchColumn();
    } elseif ($action==='mall.order.settle') {
        $q=$pdo->prepare("SELECT m.owner_user_id FROM orders o JOIN merchants m ON m.id=o.merchant_id WHERE o.id=?");$q->execute([(int)($_POST['order_id']??0)]);$walletUserId=(int)$q->fetchColumn();
    } elseif ($action==='mall.deposit.release') {
        $q=$pdo->prepare("SELECT owner_user_id FROM merchants WHERE id=?");$q->execute([(int)($_POST['merchant_id']??0)]);$walletUserId=(int)$q->fetchColumn();
    }
    if($walletUserId){$q=$pdo->prepare("SELECT id FROM users WHERE id=? FOR UPDATE");$q->execute([$walletUserId]);}
    if ($action === 'mall.merchant.review') {
        $q = $pdo->prepare('SELECT * FROM merchants WHERE id=? FOR UPDATE'); $q->execute([(int) ($_POST['merchant_id'] ?? 0)]); $m = $q->fetch();
        if (!$m) throw new InvalidArgumentException('店铺不存在');
        $decision = adminPostString('decision', 20);
        if ($decision === 'approve' && $m['status'] === 'pending') $status = 'awaiting_deposit';
        elseif ($decision === 'reject' && $m['status'] === 'pending') $status = 'rejected';
        elseif ($decision === 'suspend' && $m['status'] === 'active') $status = 'suspended';
        elseif ($decision === 'resume' && $m['status'] === 'suspended') $status = 'active';
        else throw new InvalidArgumentException('店铺状态已变化，请刷新');
        $pdo->prepare('UPDATE merchants SET status=?,review_reason=? WHERE id=?')->execute([$status, $reason, $m['id']]);
        adminAudit($pdo, $admin, $action, 'merchant', (int) $m['id'], $reason, ['status' => $m['status']], ['status' => $status]);
    } elseif ($action === 'mall.product.review') {
        $q = $pdo->prepare('SELECT merchant_id FROM products WHERE id=? AND deleted_at IS NULL'); $q->execute([(int) ($_POST['product_id'] ?? 0)]); $merchantId = (int)$q->fetchColumn();
        $q = $pdo->prepare('SELECT * FROM merchants WHERE id=? FOR UPDATE'); $q->execute([$merchantId]); $m = $q->fetch();
        $q = $pdo->prepare('SELECT * FROM products WHERE id=? AND merchant_id=? AND deleted_at IS NULL FOR UPDATE'); $q->execute([(int) ($_POST['product_id'] ?? 0), $merchantId]); $p = $q->fetch();
        if (!$p) throw new InvalidArgumentException('商家商品不存在');
        $decision = adminPostString('decision', 20);
        if (!in_array($decision, ['approve', 'reject', 'offline', 'online'], true)) throw new InvalidArgumentException('审核操作无效');
        $pendingReview = in_array($p['review_status'], ['pending', 'rejected'], true);
        if ($decision === 'approve' && !$pendingReview) throw new InvalidArgumentException('仅待审核商品可以通过');
        if ($decision === 'online') {
            // 经营中的商品可能被商家或平台下架，审核结论仍是 approved；这里允许重新上架。
            if ($p['review_status'] !== 'approved') throw new InvalidArgumentException('只有审核通过的商品可以重新上架');
            if ((int) $p['status'] === 1) throw new InvalidArgumentException('商品已在架，无需重复上架');
        }
        if ($decision === 'offline' && (int) $p['status'] !== 1) throw new InvalidArgumentException('商品当前不在架');
        if ($decision === 'approve' || $decision === 'online') {
            $status = 1;
            $review = 'approved';
        } elseif ($decision === 'offline') {
            // 下架只是停止售卖，不应把「已通过审核」改成「被驳回」。
            $status = 0;
            $review = 'approved';
        } else {
            $status = 0;
            $review = 'rejected';
        }
        if ($status === 1) mallRequireProductSlot($pdo, $m, $p);
        $pdo->prepare('UPDATE products SET status=?,review_status=?,review_reason=?,version=version+1 WHERE id=?')->execute([$status, $review, $reason, $p['id']]);
        adminAudit($pdo, $admin, $action, 'product', (int) $p['id'], $reason, ['status' => $p['status'], 'review_status' => $p['review_status']], ['status' => $status, 'review_status' => $review]);
    } elseif ($action === 'mall.order.refund' || $action === 'mall.order.after_sale.reject' || $action === 'mall.order.return.approve' || $action === 'mall.order.settle') {
        $owner=$pdo->prepare("SELECT merchant_id FROM orders WHERE id=?");$owner->execute([(int)($_POST["order_id"]??0)]);$merchantId=$owner->fetchColumn();
        if($merchantId){$shopLock=$pdo->prepare("SELECT id FROM merchants WHERE id=? FOR UPDATE");$shopLock->execute([$merchantId]);}
        $q = $pdo->prepare('SELECT * FROM orders WHERE id=? FOR UPDATE'); $q->execute([(int) ($_POST['order_id'] ?? 0)]); $o = $q->fetch();
        if (!$o) throw new InvalidArgumentException('商家订单不存在');
        if ($action === 'mall.order.refund') {
            if (!in_array($o['status'], ['pending', 'shipped', 'delivered'], true)) throw new InvalidArgumentException('订单不可退款');
            mallRefundOrder($pdo, $o);
            $after = ['status' => 'refunded'];
        } elseif ($action === 'mall.order.return.approve') {
            if ($o['after_sale_status'] !== 'requested' || $o['after_sale_type'] !== 'return_refund' || $o['order_type'] !== 'physical') throw new InvalidArgumentException('该订单不支持批准退货');
            $address=adminPostString('return_address',500);if ($address==='') throw new InvalidArgumentException('请填写退货地址及联系人');
            $pdo->prepare("UPDATE orders SET after_sale_status='return_approved',return_address=?,after_sale_reply=? WHERE id=?")->execute([$address,$reason,$o['id']]);
            mallEvent($pdo,(int)$o['id'],'return_approved','退货申请已同意，请按订单地址寄回');
            $after=['after_sale_status'=>'return_approved'];
        } elseif ($action === 'mall.order.after_sale.reject') {
            if (!in_array($o['after_sale_status'],['requested','return_approved','return_shipped'],true)) throw new InvalidArgumentException('订单没有待处理售后');
            $pdo->prepare("UPDATE orders SET after_sale_status='rejected',after_sale_reply=? WHERE id=?")->execute([$reason, $o['id']]);
            mallEvent($pdo,(int)$o['id'],'after_sale_rejected','平台已回复售后申请');
            $after = ['after_sale_status' => 'rejected'];
        } else {
            if ($o['settlement_status'] !== 'held' || $o['status'] !== 'delivered' || in_array($o['after_sale_status'],['requested','return_approved','return_shipped'],true) || !$o['received_at'] || strtotime($o['received_at']) + MALL_AFTER_SALE_DAYS * 86400 > time()) throw new InvalidArgumentException('需完成订单、售后期满且无未处理售后才能结算');
            if($o['merchant_id']){
            $q = $pdo->prepare('SELECT owner_user_id,status FROM merchants WHERE id=?'); $q->execute([$o['merchant_id']]); $m = $q->fetch();
            if (!$m || !in_array($m['status'], ['active', 'closing'], true)) throw new InvalidArgumentException('店铺已冻结，请先处理异常');
            $settlement=mallSettleMerchantOrder($pdo,$o,$m);
            }else{$pdo->prepare("UPDATE orders SET settlement_status='settled',settled_at=NOW() WHERE id=?")->execute([$o['id']]);}
            mallEvent($pdo,(int)$o['id'],'settled',$o['merchant_id']?'售后期结束，平台已结算给商家':'平台订单售后期结束，财务已结单');
            $after = ['settlement_status' => 'settled', 'amount' => $o['total_spx']] + ($settlement ?? []);
        }
        adminAudit($pdo, $admin, $action, 'order', (int) $o['id'], $reason, ['status' => $o['status'], 'settlement_status' => $o['settlement_status'], 'after_sale_status' => $o['after_sale_status']], $after);
    } elseif ($action === 'mall.deposit.release') {
        $q = $pdo->prepare('SELECT * FROM merchants WHERE id=? FOR UPDATE'); $q->execute([(int) ($_POST['merchant_id'] ?? 0)]); $m = $q->fetch();
        if (!$m || !in_array($m['status'],['closing','closed'],true)) throw new InvalidArgumentException('请先申请关店');
        if (!mallReleaseClosedDeposit($pdo,$m)) throw new InvalidArgumentException('仍有未完成、未结算或待处理售后订单，保证金暂缓退还');
        adminAudit($pdo, $admin, $action, 'merchant', (int) $m['id'], $reason, ['deposit_frozen' => $m['deposit_frozen']], ['status' => 'closed', 'deposit_frozen' => '0']);
    } else throw new InvalidArgumentException('商城操作不存在');
    $pdo->commit();
    if (!empty($merchantId)) {
        $q=$pdo->prepare('SELECT owner_user_id FROM merchants WHERE id=?');$q->execute([$merchantId]);
        if ($ownerId=$q->fetchColumn()) mallTryFinishClosure($pdo,(int)$ownerId);
    }
    adminFlash('商城操作已完成，已记录审计流水。');
    adminRedirect('merchants');
}

function mallAdminRender(PDO $pdo, string $csrf): void
{
    $limits = mallProductLimits($pdo);
    $shops = $pdo->query('SELECT m.*,u.username FROM merchants m JOIN users u ON u.id=m.owner_user_id ORDER BY m.id DESC LIMIT 100')->fetchAll();
    $products = $pdo->query('SELECT p.*,m.name AS shop_name FROM products p JOIN merchants m ON m.id=p.merchant_id WHERE p.deleted_at IS NULL ORDER BY (p.review_status="pending") DESC,p.id DESC LIMIT 100')->fetchAll();
    $orders = $pdo->query('SELECT o.*,p.name AS product_name,COALESCE(m.name,"平台自营") AS shop_name FROM orders o JOIN products p ON p.id=o.product_id LEFT JOIN merchants m ON m.id=o.merchant_id ORDER BY (o.after_sale_status="requested") DESC,o.id DESC LIMIT 100')->fetchAll();
    ?>
    <div class="section-head"><h2>商家商城</h2><span class="muted">初级 10 万 SPX001 · 黄金 100 万 SPX001 · 售后期 7 天 · 最近 100 条</span></div>
    <p class="muted">当前上架上限：初级 <?= $limits['basic'] ?> 款 · 黄金 <?= $limits['gold'] ?> 款。<?php if (adminCan('settings')): ?><a href="admin.php?section=settings#mall-product-limits">修改上架名额</a><?php endif; ?></p>
    <section class="panel"><div class="panel-head"><h2>开店审核与保证金</h2></div><div class="table-wrap"><table><thead><tr><th>店铺</th><th>资料与位置</th><th>等级 / 状态</th><th>保证金</th><th>操作</th></tr></thead><tbody>
    <?php foreach ($shops as $m): ?><tr>
        <td><?php if ($m['logo_url']): ?><img src="<?= adminEscape($m['logo_url']) ?>" loading="lazy" alt="店铺头像" style="width:52px;height:52px;object-fit:cover;border-radius:12px"><?php endif; ?> #<?= (int) $m['id'] ?> <?= adminEscape($m['name']) ?><span class="sub"><?= adminEscape($m['username']) ?> · <?= adminEscape($m['category']) ?></span></td>
        <td><?= adminEscape($m['contact_name'] . ' · ' . $m['contact_phone']) ?><span class="sub"><?= adminEscape($m['qualification']) ?></span>
        <?php foreach (json_decode((string) ($m['qualification_images'] ?? ''), true) ?: [] as $imageId): $imageUrl = 'admin.php?merchant_id=' . (int) $m['id'] . '&qualification_image=' . rawurlencode($imageId); ?><a href="<?= adminEscape($imageUrl) ?>" target="_blank" rel="noopener noreferrer"><img src="<?= adminEscape($imageUrl) ?>" loading="lazy" alt="经营资质（点击查看）" style="width:90px;height:90px;object-fit:contain;border:1px solid #e1e7f0;border-radius:8px;margin:4px"></a><?php endforeach; ?>
        <span class="sub"><?= adminEscape($m['address']) ?><?php if ($m['latitude'] !== null && $m['longitude'] !== null): ?> · 已选择地图位置<?php endif; ?></span></td>
        <td><?= $m['level'] === 'gold' ? '黄金店铺' : '初级店铺' ?><span class="sub"><?= adminEscape($m['status']) ?></span><span class="sub"><?= adminEscape($m['review_reason']) ?></span></td>
        <td><?= mallAdminMoney($m['deposit_frozen']) ?></td>
        <td><form method="post" class="inline-form"><?= adminHidden($csrf, 'mall.merchant.review', 'merchants') ?><input type="hidden" name="merchant_id" value="<?= (int) $m['id'] ?>"><input name="reason" maxlength="500" required placeholder="审核或处理原因">
        <?php if ($m['status'] === 'pending'): ?><button class="btn primary" name="decision" value="approve">审核通过</button><button class="btn danger" name="decision" value="reject">驳回</button><?php elseif ($m['status'] === 'active'): ?><button class="btn danger" name="decision" value="suspend">暂停营业</button><?php elseif ($m['status'] === 'suspended'): ?><button class="btn" name="decision" value="resume">恢复营业</button><?php endif; ?></form>
        <?php if ($m['status'] === 'closing' && adminCan('mall_finance')): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'mall.deposit.release', 'merchants') ?><input type="hidden" name="merchant_id" value="<?= (int) $m['id'] ?>"><input name="reason" maxlength="500" required placeholder="关店退还原因"><button class="btn">审核退还保证金</button></form><?php endif; ?></td>
    </tr><?php endforeach; ?><?= !$shops ? adminTableEmpty(5) : '' ?></tbody></table></div></section>
    <section class="panel"><div class="panel-head"><h2>商品自动审核与平台管理</h2></div><div class="table-wrap"><table><thead><tr><th>商品</th><th>详情</th><th>价格 / 库存</th><th>状态</th><th>审核</th></tr></thead><tbody>
    <?php foreach ($products as $p): ?><tr><td>#<?= (int) $p['id'] ?> <?= adminEscape($p['name']) ?><span class="sub"><?= adminEscape($p['shop_name']) ?></span><?php if ($p['image_url']): ?><img src="<?= adminEscape($p['image_url']) ?>" alt="商品" style="width:80px;height:80px;object-fit:cover"><?php endif; ?></td>
    <td class="ticket-content"><details><summary>查看图文与服务条款</summary><p><?= nl2br(adminEscape($p['description'])) ?></p><p><?= nl2br(adminEscape($p['detail_text'])) ?></p><p><?= nl2br(adminEscape($p['parameters'])) ?></p><p><?= nl2br(adminEscape($p['service_terms'])) ?></p><p><?= adminEscape($p['delivery_note']) ?></p><?php foreach (json_decode((string) $p['detail_images'], true) ?: [] as $url): ?><img src="<?= adminEscape($url) ?>" style="max-width:240px" alt="详情图"><?php endforeach; ?><?php if ($p['video_url']): ?><a href="<?= adminEscape($p['video_url']) ?>" target="_blank" rel="noopener noreferrer">查看视频</a><?php endif; ?></details></td>
    <td><?= mallAdminMoney($p['spx_price']) ?><span class="sub"><?= (int) $p['stock'] ?> 件 · <?= adminEscape($p['product_type']) ?></span></td><td><?= adminEscape($p['review_status']) ?><span class="sub"><?= (int) $p['status'] === 1 ? '已上架' : '未上架' ?></span></td>
    <td><form method="post" class="inline-form"><?= adminHidden($csrf, 'mall.product.review', 'merchants') ?><input type="hidden" name="product_id" value="<?= (int) $p['id'] ?>"><input name="reason" maxlength="500" required placeholder="审核原因"><?php if (in_array($p['review_status'],['pending','rejected'],true)): ?><button class="btn primary" name="decision" value="approve">通过上架</button><button class="btn danger" name="decision" value="reject">驳回</button><?php elseif ((int) $p['status'] === 1): ?><button class="btn danger" name="decision" value="offline">下架</button><?php else: ?><button class="btn primary" name="decision" value="online">重新上架</button><?php endif; ?></form></td></tr><?php endforeach; ?><?= !$products ? adminTableEmpty(5) : '' ?></tbody></table></div></section>
    <section class="panel"><div class="panel-head"><h2>商家订单、售后和结算</h2></div><div class="table-wrap"><table><thead><tr><th>订单</th><th>状态</th><th>售后</th><th>处理</th></tr></thead><tbody>
    <?php foreach ($orders as $o): ?><tr><td>#<?= (int) $o['id'] ?> <?= adminEscape($o['product_name']) ?><span class="sub"><?= adminEscape($o['shop_name']) ?> · <?= mallAdminMoney($o['total_spx']) ?> SPX001</span></td><td><?= adminEscape($o['status']) ?><span class="sub"><?= adminEscape($o['settlement_status']) ?> · 完成时间 <?= adminEscape($o['received_at']) ?></span></td><td><?= adminEscape($o['after_sale_status']) ?><span class="sub"><?= adminEscape($o['after_sale_reason']) ?></span><span class="sub">商家回复：<?= adminEscape($o['merchant_service_reply'] ?? '') ?></span>
    <?php foreach(json_decode((string)$o['after_sale_images'],true)?:[] as $url): ?><a href="<?= adminEscape($url) ?>" target="_blank" rel="noopener noreferrer"><img src="<?= adminEscape($url) ?>" alt="售后凭证" style="max-width:100px"></a><?php endforeach; ?>
    <?php if($o['return_tracking']): ?><span class="sub">退货物流 <?= adminEscape($o['return_carrier'].' '.$o['return_tracking']) ?></span><?php endif; ?></td><td>
    <?php if ($o['settlement_status'] === 'held' && !in_array($o['status'], ['cancelled','refunded'], true)): ?>
    <form method="post" class="inline-form"><?= adminHidden($csrf, 'mall.order.refund', 'merchants') ?><input type="hidden" name="order_id" value="<?= (int) $o['id'] ?>"><input name="reason" maxlength="500" required placeholder="退款处理原因"><button class="btn danger">退款给买家</button></form>
    <?php if ($o['after_sale_status'] === 'requested' && $o['after_sale_type'] === 'return_refund'): ?>
    <form method="post" class="inline-form"><?= adminHidden($csrf, 'mall.order.return.approve', 'merchants') ?><input type="hidden" name="order_id" value="<?= (int)$o['id'] ?>"><input name="return_address" maxlength="500" required placeholder="退货地址、联系人、电话"><input name="reason" maxlength="500" required placeholder="退货处理说明"><button class="btn primary">同意退货并提供地址</button></form>
    <?php endif; ?>
    <?php if ($o['after_sale_status'] === 'requested'): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'mall.order.after_sale.reject', 'merchants') ?><input type="hidden" name="order_id" value="<?= (int) $o['id'] ?>"><input name="reason" maxlength="500" required placeholder="售后驳回原因"><button class="btn">驳回售后</button></form><?php endif; ?>
    <?php if (adminCan('mall_finance') && $o['status'] === 'delivered'): ?><form method="post" class="inline-form"><?= adminHidden($csrf, 'mall.order.settle', 'merchants') ?><input type="hidden" name="order_id" value="<?= (int) $o['id'] ?>"><input name="reason" maxlength="500" required placeholder="财务结算原因"><button class="btn primary">审核结算到商家钱包</button></form><?php endif; ?>
    <?php endif; ?></td></tr><?php endforeach; ?><?= !$orders ? adminTableEmpty(4) : '' ?></tbody></table></div></section>
    <?php
}
