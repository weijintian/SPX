<?php
declare(strict_types=1);

/** Commerce v2: exact quotes, immutable receipts, versioned inventory. */
function installMallCommerceSchema(PDO $pdo): void
{
    if ($pdo->query("SELECT version FROM mall_schema_versions WHERE version=2")->fetchColumn()) return;
    if ((int) $pdo->query("SELECT GET_LOCK('spx_mall_commerce_v2',30)")->fetchColumn() !== 1) throw new RuntimeException("商城升级中，请稍后重试");
    try {
        if ($pdo->query("SELECT version FROM mall_schema_versions WHERE version=2")->fetchColumn()) return;
        $definitions = [
            "products" => ["version"=>"INT UNSIGNED NOT NULL DEFAULT 1", "inventory_version"=>"INT UNSIGNED NOT NULL DEFAULT 1", "cny_price"=>"DECIMAL(24,8) NULL", "shipping_fee"=>"DECIMAL(24,8) NOT NULL DEFAULT 0", "delivery_days"=>"INT NOT NULL DEFAULT 3", "service_valid_days"=>"INT NOT NULL DEFAULT 30", "appointment_required"=>"TINYINT NOT NULL DEFAULT 0"],
            "orders" => ["checkout_quote_id"=>"CHAR(32) NULL", "unit_price"=>"DECIMAL(24,8) NULL", "shipping_fee"=>"DECIMAL(24,8) NOT NULL DEFAULT 0", "buyer_note"=>"VARCHAR(500) NULL", "after_sale_type"=>"VARCHAR(30) NOT NULL DEFAULT 'refund_only'", "after_sale_images"=>"TEXT NULL", "return_address"=>"VARCHAR(500) NULL", "return_carrier"=>"VARCHAR(100) NULL", "return_tracking"=>"VARCHAR(120) NULL", "service_expires_at"=>"DATETIME NULL", "refunded_at"=>"DATETIME NULL"],
        ];
        foreach ($definitions as $table=>$columns) {
            $existing = $pdo->query("SHOW COLUMNS FROM `$table`")->fetchAll(PDO::FETCH_COLUMN);
            foreach ($columns as $name=>$type) if (!in_array($name,$existing,true)) $pdo->exec("ALTER TABLE `$table` ADD COLUMN `$name` $type");
        }
        $pdo->exec("CREATE TABLE IF NOT EXISTS mall_checkout_quotes (id CHAR(32) PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,product_id BIGINT UNSIGNED NOT NULL,product_version INT NOT NULL,payload LONGTEXT NOT NULL,expires_at DATETIME NOT NULL,order_id BIGINT UNSIGNED NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,INDEX(user_id,created_at),INDEX(expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $pdo->exec("CREATE TABLE IF NOT EXISTS mall_order_events (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,order_id BIGINT UNSIGNED NOT NULL,status VARCHAR(40) NOT NULL,message VARCHAR(500) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,INDEX(order_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $pdo->exec("CREATE TABLE IF NOT EXISTS mall_inventory_logs (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,product_id BIGINT UNSIGNED NOT NULL,variant_id VARCHAR(64) NOT NULL,delta INT NOT NULL,reason VARCHAR(255) NOT NULL,actor_user_id BIGINT UNSIGNED NULL,order_id BIGINT UNSIGNED NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,INDEX(product_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $pdo->exec("INSERT IGNORE INTO mall_schema_versions(version) VALUES(2)");
    } finally { $pdo->query("SELECT RELEASE_LOCK('spx_mall_commerce_v2')"); }
}

/** Add CNY pricing to already-installed commerce v2 databases. */
function installMallCnySchema(PDO $pdo): void
{
    if ($pdo->query("SELECT version FROM mall_schema_versions WHERE version=6")->fetchColumn()) return;
    if ((int) $pdo->query("SELECT GET_LOCK('spx_mall_cny_v1',30)")->fetchColumn() !== 1) {
        throw new RuntimeException('商城价格升级中，请稍后重试');
    }
    try {
        if ($pdo->query("SELECT version FROM mall_schema_versions WHERE version=6")->fetchColumn()) return;
        $columns = $pdo->query("SHOW COLUMNS FROM products")->fetchAll(PDO::FETCH_COLUMN);
        if (!in_array('cny_price', $columns, true)) {
            $pdo->exec("ALTER TABLE products ADD COLUMN cny_price DECIMAL(24,8) NULL AFTER spx_price");
        }
        $pdo->exec("INSERT IGNORE INTO mall_schema_versions(version) VALUES(6)");
    } finally {
        $pdo->query("SELECT RELEASE_LOCK('spx_mall_cny_v1')");
    }
}

/** 平台结算手续费率：默认 5%，后台 app_settings.mall_settlement_fee_rate 可改。 */
function mallSettlementFeeRate(PDO $pdo): string
{
    static $rate = null;
    if ($rate !== null) return $rate;
    $value = '';
    try {
        $q = $pdo->prepare('SELECT `value` FROM app_settings WHERE `key`=?');
        $q->execute(['mall_settlement_fee_rate']);
        $fetched = $q->fetchColumn();
        if ($fetched !== false && $fetched !== null) $value = trim((string) $fetched);
    } catch (Throwable $ignored) {
    }
    if (!preg_match('/^(?:0(?:\.\d{1,4})?|1(?:\.0{1,4})?)$/', $value) || (float) $value > 1) $value = '0.0500';
    return $rate = $value;
}

/** 核销码：16 位纯数字，全表唯一，不可预测。 */
function mallRedeemCode(PDO $pdo): string
{
    $check = $pdo->prepare('SELECT 1 FROM orders WHERE redeem_code=?');
    for ($attempt = 0; $attempt < 20; $attempt++) {
        $code = '';
        for ($i = 0; $i < 16; $i++) $code .= (string) random_int(0, 9);
        $check->execute([$code]);
        if (!$check->fetchColumn()) return $code;
    }
    throw new RuntimeException('核销码生成失败，请重试');
}

function mallEvent(PDO $pdo, int $orderId, string $status, string $message): void
{
    $pdo->prepare("INSERT INTO mall_order_events(order_id,status,message) VALUES(?,?,?)")->execute([$orderId,$status,$message]);
}

function mallActiveStock(array $variants): int
{
    return array_sum(array_map(static fn(array $v): int => !array_key_exists("enabled",$v) || $v["enabled"] ? (int)$v["stock"] : 0,$variants));
}

function mallSku(array $product, string $id, ?array $pricing = null): array
{
    $variants = json_decode((string)($product["variants"] ?? ""),true) ?: [];
    if (!$variants && ($id === "default" || $id === "")) {
        $cny = (string) ($product['cny_price'] ?? '');
        $price = $cny !== '' && $pricing ? mallCnyToSpx($cny, $pricing) : (string) $product['spx_price'];
        return ["id"=>"default","name"=>"标准规格","price"=>$price,"cny_price"=>$cny,"stock"=>(int)$product["stock"],"attributes"=>[],"image_url"=>$product["image_url"]];
    }
    foreach ($variants as $v) if ((string)$v["id"] === $id && (!isset($v["enabled"]) || $v["enabled"])) {
        if (!empty($v['cny_price']) && $pricing) $v['price'] = mallCnyToSpx((string) $v['cny_price'], $pricing);
        return $v;
    }
    throw new InvalidArgumentException("规格已停用，请重新选择");
}

function mallPage(): array
{
    $size = max(1,min(60,(int)($_GET["page_size"] ?? 20)));
    $page = max(1,min(100000,(int)($_GET["page"] ?? 1)));
    return [$size,($page-1)*$size];
}

function mallOrderView(PDO $pdo, array $o, bool $events=false): array
{
    if (!isset($o["unit_price"]) && (int)$o["quantity"]>0) {
        $amount=$pdo->prepare("SELECT (CAST(? AS DECIMAL(36,8))-CAST(? AS DECIMAL(36,8)))/? AS unit_price");
        $amount->execute([$o["total_spx"],$o["shipping_fee"]??"0",$o["quantity"]]);$o["unit_price"]=$amount->fetchColumn();
    }
    $snapshot = json_decode((string)($o["product_snapshot"] ?? ""),true) ?: [];
    foreach (["name","description","image_url"] as $key) if (isset($snapshot[$key])) $o[$key]=$snapshot[$key];
    $o["snapshot"]=$snapshot;
    $o["address"]=json_decode((string)($o["shipping_address"] ?? ""),true);
    $o["after_sale_images"]=json_decode((string)($o["after_sale_images"] ?? ""),true) ?: [];
    $o["can_after_sale"]=$o["settlement_status"]==="held" && !in_array($o["status"],["cancelled","refunded"],true) && (!$o["received_at"] || strtotime($o["received_at"])+MALL_AFTER_SALE_DAYS*86400>time());
    if ($events) {
        $q=$pdo->prepare("SELECT status,message,created_at FROM mall_order_events WHERE order_id=? ORDER BY id"); $q->execute([$o["id"]]); $o["events"]=$q->fetchAll();
        if (!$o["events"]) $o["events"]=[["status"=>"created","message"=>"订单创建","created_at"=>$o["created_at"]]];
    }
    return $o;
}

function mallCheckoutData(PDO $pdo, int $uid, array $body, bool $lock=false, ?array $pricing=null): array
{
    $quantity=filter_var($body["quantity"] ?? null,FILTER_VALIDATE_INT);
    if ($quantity===false || $quantity<1 || $quantity>99) throw new InvalidArgumentException("购买数量须为 1 到 99 的整数");
    // Use the same shop -> product lock order as merchant editing.
    $productId=(int)($body["product_id"] ?? 0);
    $q=$pdo->prepare("SELECT merchant_id FROM products WHERE id=?");$q->execute([$productId]);$owner=$q->fetch();
    if ($lock && $owner && $owner["merchant_id"]) { $q=$pdo->prepare("SELECT id FROM merchants WHERE id=? FOR UPDATE");$q->execute([$owner["merchant_id"]]); }
    $q=$pdo->prepare("SELECT * FROM products WHERE id=?" . ($lock ? " FOR UPDATE":"")); $q->execute([$productId]); $p=$q->fetch();
    if (!$p || !empty($p["deleted_at"]) || (int)$p["status"]!==1 || $p["review_status"]!=="approved") throw new InvalidArgumentException("商品已下架或暂不可购买");
    $shop=null;
    if ($p["merchant_id"]) {
        $q=$pdo->prepare("SELECT * FROM merchants WHERE id=?" . ($lock ? " FOR UPDATE":"")); $q->execute([$p["merchant_id"]]); $shop=$q->fetch();
        if (!$shop || !mallCanSell($pdo,$shop)) throw new InvalidArgumentException("店铺暂停接单");
        if ((int)$shop["owner_user_id"]===$uid) throw new InvalidArgumentException("不能购买自己的商品");
    }
    $hasCny = !empty($p['cny_price']);
    if ($pricing === null && $hasCny) $pricing = mallPricingSnapshot($pdo);
    $sku=mallSku($p,mallText($body,"variant_id",64),$pricing);
    if ($sku["stock"]<$quantity || $p["stock"]<$quantity) throw new InvalidArgumentException("所选规格库存不足，请调整数量");
    $address=null; $addressId=0;
    if ($p["product_type"]==="physical") {
        $addressId=(int)($body["address_id"] ?? 0);
        $q=$pdo->prepare("SELECT id,name,phone,province,city,district,detail FROM user_addresses WHERE id=? AND user_id=?" . ($lock ? " FOR UPDATE":"")); $q->execute([$addressId,$uid]); $address=$q->fetch();
        if (!$address) throw new InvalidArgumentException("请选择有效的收货地址");
    }
    $price=mallAmount($sku["price"]);
    $shipping=$p["product_type"]==="physical" ? (string)$p["shipping_fee"]:"0";
    $q=$pdo->prepare("SELECT CAST(? AS DECIMAL(36,8))*? AS subtotal,CAST(? AS DECIMAL(36,8))*?+CAST(? AS DECIMAL(36,8)) AS total");
    $q->execute([$price,$quantity,$price,$quantity,$shipping]); $amount=$q->fetch();
    if (strlen(explode(".",(string)$amount["total"])[0])>16 || mallCompareAmounts((string)$amount["total"],"9999999999999999.99999999")>0) throw new InvalidArgumentException("订单金额超过上限，请减少数量");
    $snapshot=array_intersect_key($p,array_flip(["name","description","image_url","detail_text","detail_images","parameters","service_terms","delivery_note","video_url","product_type","delivery_days","service_valid_days","appointment_required"]));
    $snapshot["variant"]=$sku; $snapshot["shop"]=$shop ? mallPublicMerchant($shop):null;
    $snapshot["merchant_name"]=$shop["name"] ?? "平台自营";
    $snapshot["price_cny"]=(string)($sku['cny_price'] ?? '');
    if ($pricing) $snapshot['pricing'] = $pricing;
    $priceCny=(string)($sku['cny_price'] ?? $p['cny_price'] ?? '');
    $subtotalCny=$priceCny!=='' ? bcmul($priceCny,(string)$quantity,8) : '';
    return ["product_id"=>(int)$p["id"],"product_version"=>(int)$p["version"],"merchant_id"=>$p["merchant_id"],"variant_id"=>$sku["id"],"variant_name"=>$sku["name"],"quantity"=>$quantity,"price_cny"=>$priceCny,"subtotal_cny"=>$subtotalCny,"unit_price"=>$price,"subtotal"=>(string)$amount["subtotal"],"shipping_fee"=>$shipping,"total_spx"=>(string)$amount["total"],"address_id"=>$addressId,"address"=>$address,"buyer_note"=>mallText($body,"buyer_note",500),"order_type"=>$p["product_type"],"pricing"=>$pricing,"snapshot"=>$snapshot];
}

function mallCommerceApi(string $action,string $method,array $body): void
{
    mallAddressApi($action,$method,$body);
    $reads=["orders","order","orders.request"];
    $writes=["checkout.quote","checkout.resolve","orders.redeem","merchant.inventory.adjust","orders.after_sale","orders.return","orders.media.upload"];
    if (!in_array($action,array_merge($reads,$writes),true)) return;
    if (($method==="GET" && !in_array($action,$reads,true)) || ($method==="POST" && !in_array($action,$writes,true)) || !in_array($method,["GET","POST"],true)) jsonResponse(null,"请求方式无效",405);
    $user=currentUser(); $uid=(int)$user["id"]; $pdo=db();
    try {
        if ($action==="orders" || $action==="order" || $action==="orders.request") {
            $where="o.user_id=?"; $params=[$uid];
            if ($action==="order") {$where.=" AND o.id=?";$params[]=(int)($_GET["id"] ?? 0);}
            if ($action==="orders.request") {$where.=" AND o.client_request_id=?";$params[]=(string)($_GET["client_request_id"] ?? "");}
            $status=(string)($_GET["status"] ?? "all");
            if ($action==="orders") {
                if ($status==="after_sale") $where.=" AND o.after_sale_status<>'none'";
                elseif ($status==="local") $where.=" AND o.order_type='local' AND o.status='pending'";
                elseif (in_array($status,["pending","shipped","delivered","refunded"],true)) {$where.=" AND o.status=?";$params[]=$status;}
            }
            [$limit,$offset]=mallPage();
            $q=$pdo->prepare("SELECT o.*,p.name,p.image_url,p.description FROM orders o JOIN products p ON p.id=o.product_id WHERE $where ORDER BY o.id DESC LIMIT " . ($action==="orders" ? "$limit OFFSET $offset":"1"));
            $q->execute($params); $rows=$q->fetchAll();
            if ($action==="orders") jsonResponse(array_map(static fn($o)=>mallOrderView($pdo,$o),$rows));
            if (!$rows) { if ($action==="orders.request") jsonResponse(["found"=>false]); jsonResponse(null,"订单不存在",404); }
            $result=mallOrderView($pdo,$rows[0],true); $result["found"]=true; jsonResponse($result);
        }
        if ($action==="orders.media.upload") {
            enforceApiRateLimit("mall_evidence:".$uid,30); jsonResponse(["url"=>mallStoreImage($uid)]);
        }
        if ($action==="orders.redeem") {
            // 支付密码：与红包、提现一致，在事务外校验，错误次数独立生效
            if (!paymentPasswordConfigured($pdo,$uid)) throw new DomainException('请先设置 6 位支付密码后再下单',409);
            verifyPaymentPassword($pdo,$uid,(string)($body['payment_password'] ?? ''));
        }
        $pdo->beginTransaction();
        // Serialize the buyer before any inventory changes; repeated payment requests share one result.
        if (in_array($action,["checkout.quote","checkout.resolve","orders.redeem"],true)) { $q=$pdo->prepare("SELECT id FROM users WHERE id=? FOR UPDATE");$q->execute([$uid]); }
        if ($action==="checkout.resolve") {
            $qid=mallText($body,"quote_id",32,true);
            $q=$pdo->prepare("SELECT order_id FROM mall_checkout_quotes WHERE id=? AND user_id=? FOR UPDATE");$q->execute([$qid,$uid]);$quote=$q->fetch();
            if (!$quote) throw new InvalidArgumentException("报价不存在");
            if ($quote["order_id"]) {$pdo->commit();jsonResponse(["found"=>true,"order_id"=>(int)$quote["order_id"]]);}
            $pdo->prepare("UPDATE mall_checkout_quotes SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?")->execute([$qid]);
            $pdo->commit();jsonResponse(["found"=>false,"voided"=>true]);
        }
        if ($action==="checkout.quote") {
            $data=mallCheckoutData($pdo,$uid,$body,true); $id=bin2hex(random_bytes(16));
            $q=$pdo->prepare("INSERT INTO mall_checkout_quotes(id,user_id,product_id,product_version,payload,expires_at) VALUES(?,?,?,?,?,DATE_ADD(NOW(),INTERVAL 10 MINUTE))");
            $q->execute([$id,$uid,$data["product_id"],$data["product_version"],json_encode($data,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES)]);
            $q=$pdo->prepare("SELECT balance FROM wallet_assets WHERE user_id=? AND symbol='SPX001' AND chain='BSC'");$q->execute([$uid]);
            $data["balance"]=(string)($q->fetchColumn() ?: "0");$data["quote_id"]=$id;$data["expires_in"]=600;
            $pdo->commit();jsonResponse($data);
        }
        if ($action==="orders.redeem") {
            $rid=mallText($body,"client_request_id",32,true);$qid=mallText($body,"quote_id",32,true);
            if (!preg_match("/^[a-f0-9]{32}$/",$rid) || !preg_match("/^[a-f0-9]{32}$/",$qid)) throw new InvalidArgumentException("请更新应用并重新确认订单");
            $q=$pdo->prepare("SELECT * FROM orders WHERE user_id=? AND client_request_id=?");$q->execute([$uid,$rid]);$old=$q->fetch();
            if ($old) {if ($old["checkout_quote_id"]!==$qid) throw new InvalidArgumentException("订单请求已用于另一笔支付");$pdo->commit();jsonResponse(["order_id"=>(int)$old["id"],"total_spx"=>$old["total_spx"]],"订单已创建",201);}
            $q=$pdo->prepare("SELECT *,expires_at>NOW() AS valid FROM mall_checkout_quotes WHERE id=? AND user_id=? FOR UPDATE");$q->execute([$qid,$uid]);$quote=$q->fetch();
            if (!$quote) throw new InvalidArgumentException("报价不存在，请重新确认订单");
            if ($quote["order_id"]) { $pdo->commit();jsonResponse(["order_id"=>(int)$quote["order_id"]],"订单已创建",201); }
            if (!(int)$quote["valid"]) throw new InvalidArgumentException("报价已过期，请重新确认订单");
            $data=json_decode($quote["payload"],true);$fresh=mallCheckoutData($pdo,$uid,$data,true,$data['pricing'] ?? null);
            foreach (["product_version","price_cny","unit_price","shipping_fee","total_spx","address","pricing"] as $key) if (($data[$key] ?? null)!==($fresh[$key] ?? null)) throw new InvalidArgumentException("价格、商品或地址已变化，请重新确认订单");
            $used=walletDebit($pdo,$uid,"SPX001","BSC",$data["total_spx"]);
            $q=$pdo->prepare("SELECT variants FROM products WHERE id=? FOR UPDATE");$q->execute([$data["product_id"]]);$variants=json_decode((string)$q->fetchColumn(),true) ?: [];
            foreach ($variants as &$v) if ($v["id"]===$data["variant_id"]) $v["stock"]-=$data["quantity"];unset($v);
            $pdo->prepare("UPDATE products SET stock=stock-?,variants=?,inventory_version=inventory_version+1 WHERE id=?")->execute([$data["quantity"],json_encode($variants,JSON_UNESCAPED_UNICODE),$data["product_id"]]);
            $expires=$data["order_type"]==="local" ? date("Y-m-d H:i:s",time()+max(1,(int)$data["snapshot"]["service_valid_days"])*86400):null;
            $pdo->prepare("INSERT INTO orders(user_id,product_id,merchant_id,quantity,total_spx,stakeable_amount,status,order_type,variant_id,variant_name,shipping_address,product_snapshot,redeem_code,client_request_id,checkout_quote_id,unit_price,shipping_fee,buyer_note,service_expires_at) VALUES(?,?,?,?,?,?,'pending',?,?,?,?,?,?,?,?,?,?,?,?)")->execute([$uid,$data["product_id"],$data["merchant_id"],$data["quantity"],$data["total_spx"],$used,$data["order_type"],$data["variant_id"],$data["variant_name"],$data["address"] ? json_encode($data["address"],JSON_UNESCAPED_UNICODE):null,json_encode($data["snapshot"],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),$data["order_type"]==="local" ? mallRedeemCode($pdo):null,$rid,$qid,$data["unit_price"],$data["shipping_fee"],$data["buyer_note"],$expires]);
            $oid=(int)$pdo->lastInsertId();
            // Freeze fee terms at purchase; DECIMAL arithmetic preserves all eight places.
            $mallFeeRate=mallSettlementFeeRate($pdo);
            $pdo->prepare("UPDATE orders SET settlement_fee_rate=IF(merchant_id IS NULL,0,?),settlement_fee=IF(merchant_id IS NULL,0,TRUNCATE(total_spx*?,8)),merchant_net_amount=total_spx-IF(merchant_id IS NULL,0,TRUNCATE(total_spx*?,8)) WHERE id=?")->execute([$mallFeeRate,$mallFeeRate,$mallFeeRate,$oid]);
            $pdo->prepare("UPDATE mall_checkout_quotes SET order_id=? WHERE id=?")->execute([$oid,$qid]);
            $pdo->prepare("INSERT INTO wallet_transactions(user_id,symbol,chain,type,amount,stakeable_amount,status,mall_order_id,mall_merchant_id) VALUES(?,'SPX001','BSC','redeem',?,?,'completed',?,?)")->execute([$uid,$data["total_spx"],$used,$oid,$data['merchant_id']]);
            $pdo->prepare("INSERT INTO mall_inventory_logs(product_id,variant_id,delta,reason,actor_user_id,order_id) VALUES(?,?,?,'订单购买',?,?)")->execute([$data["product_id"],$data["variant_id"],-$data["quantity"],$uid,$oid]);
            mallEvent($pdo,$oid,"paid","支付成功，等待商家履约");$pdo->commit();
            // 购物助手播报：失败只写日志，不影响下单
            if (function_exists("mallBotBroadcastOrder")) { try { mallBotBroadcastOrder($pdo,$oid); } catch (Throwable $ignored) {} }
            jsonResponse(["order_id"=>$oid,"total_spx"=>$data["total_spx"]],"订单已创建",201);
        }
        if ($action==="merchant.inventory.adjust") {
            $m=mallOwnMerchant($pdo,$uid,true);if (!mallCanSell($pdo,$m)) throw new InvalidArgumentException("店铺暂停营业");
            $q=$pdo->prepare("SELECT * FROM products WHERE id=? AND merchant_id=? FOR UPDATE");$q->execute([(int)($body["product_id"] ?? 0),$m["id"]]);$p=$q->fetch();
            if (!$p || !empty($p["deleted_at"]) || (int)($body["inventory_version"] ?? 0)!==(int)$p["inventory_version"]) throw new InvalidArgumentException("库存已变化，请刷新后调整");
            $delta=filter_var($body["delta"] ?? null,FILTER_VALIDATE_INT);if ($delta===false || $delta===0 || abs($delta)>1000000) throw new InvalidArgumentException("请输入有效的库存增减数量");
            $sku=mallSku($p,mallText($body,"variant_id",64));$stock=(int)$sku["stock"]+$delta;
            if ($stock<0 || $stock>1000000 || (int)$p["stock"]+$delta>1000000) throw new InvalidArgumentException("调整后库存超出范围");
            $variants=json_decode((string)$p["variants"],true) ?: [];foreach ($variants as &$v) if ($v["id"]===$sku["id"]) $v["stock"]=$stock;unset($v);
            $reason=mallText($body,"reason",255,true);
            $pdo->prepare("UPDATE products SET stock=stock+?,variants=?,inventory_version=inventory_version+1 WHERE id=?")->execute([$delta,json_encode($variants,JSON_UNESCAPED_UNICODE),$p["id"]]);
            $pdo->prepare("INSERT INTO mall_inventory_logs(product_id,variant_id,delta,reason,actor_user_id) VALUES(?,?,?,?,?)")->execute([$p["id"],$sku["id"],$delta,$reason,$uid]);
            mallAudit($pdo,$m,"inventory.adjust",(int)$p["id"],["delta"=>$delta,"reason"=>$reason]);
        } else {
            $q=$pdo->prepare("SELECT * FROM orders WHERE id=? AND user_id=? FOR UPDATE");$q->execute([(int)($body["order_id"] ?? 0),$uid]);$o=$q->fetch();
            if (!$o) throw new InvalidArgumentException("订单不存在");
            if ($action==="orders.after_sale") {
                if (!mallOrderView($pdo,$o)["can_after_sale"] || in_array($o["after_sale_status"],["requested","return_approved","return_shipped"],true)) throw new InvalidArgumentException("订单当前不能重复申请售后");
                $type=mallText($body,"type",30) ?: "refund_only";if (!in_array($type,["refund_only","return_refund"],true) || ($type==="return_refund" && ($o["order_type"]!=="physical" || $o["status"]==="pending"))) throw new InvalidArgumentException("该订单不支持退货退款");
                $images=$body["images"] ?? [];if (!is_array($images) || count($images)>6) throw new InvalidArgumentException("凭证最多六张");$images=array_map(static fn($x)=>mallMediaUrl((string)$x),$images);
                $pdo->prepare("UPDATE orders SET after_sale_type=?,after_sale_status='requested',after_sale_reason=?,after_sale_images=?,after_sale_reply=NULL,after_sale_requested_at=NOW(),merchant_service_reply=NULL,service_replied_at=NULL WHERE id=?")->execute([$type,mallText($body,"reason",500,true),json_encode($images),$o["id"]]);
                mallEvent($pdo,(int)$o["id"],"after_sale","售后申请已提交，等待平台处理");
            } elseif ($action==="orders.return") {
                if ($o["after_sale_status"]!=="return_approved") throw new InvalidArgumentException("请等待平台提供退货地址");
                $pdo->prepare("UPDATE orders SET after_sale_status='return_shipped',return_carrier=?,return_tracking=? WHERE id=?")->execute([mallText($body,"carrier",100,true),mallText($body,"tracking_number",120,true),$o["id"]]);
                mallEvent($pdo,(int)$o["id"],"return_shipped","买家已寄回，等待平台核实退款");
            }
        }
        $pdo->commit();jsonResponse(null,"操作成功");
    } catch (DomainException $e) {if ($pdo->inTransaction())$pdo->rollBack();jsonResponse(null,$e->getMessage(),$e->getCode()>=400&&$e->getCode()<600?$e->getCode():422);} catch (InvalidArgumentException $e) {if ($pdo->inTransaction())$pdo->rollBack();jsonResponse(null,$e->getMessage(),422);}
}

function mallAddressApi(string $action,string $method,array $body): void
{
    if (!in_array($action,["wallet.address.save","wallet.address.update","wallet.address.delete","wallet.address.default"],true)) return;
    if ($method!=="POST") jsonResponse(null,"请求方式无效",405);
    $user=currentUser();$uid=(int)$user["id"];$pdo=db();
    try {
        $pdo->beginTransaction();$q=$pdo->prepare("SELECT id FROM users WHERE id=? FOR UPDATE");$q->execute([$uid]);
        $id=(int)($body["id"]??0);$old=null;
        if ($action!=="wallet.address.save") { $q=$pdo->prepare("SELECT * FROM user_addresses WHERE id=? AND user_id=? FOR UPDATE");$q->execute([$id,$uid]);$old=$q->fetch();if(!$old)throw new InvalidArgumentException("地址不存在"); }
        if ($action==="wallet.address.delete") {
            $pdo->prepare("DELETE FROM user_addresses WHERE id=? AND user_id=?")->execute([$id,$uid]);
            if($old["is_default"])$pdo->prepare("UPDATE user_addresses SET is_default=1 WHERE user_id=? ORDER BY id DESC LIMIT 1")->execute([$uid]);
        } elseif ($action==="wallet.address.default") {
            $pdo->prepare("UPDATE user_addresses SET is_default=(id=?) WHERE user_id=?")->execute([$id,$uid]);
        } else {
            $data=[];foreach(["name"=>64,"phone"=>30,"province"=>80,"city"=>80,"district"=>80,"detail"=>255] as $k=>$max)$data[]=mallText($body,$k,$max,true);
            if(!preg_match("/^[+0-9 ()-]{5,30}$/",$data[1]))throw new InvalidArgumentException("联系电话格式无效");
            $q=$pdo->prepare("SELECT COUNT(*) FROM user_addresses WHERE user_id=?");$q->execute([$uid]);$count=(int)$q->fetchColumn();
            if(!$old&&$count>=30)throw new InvalidArgumentException("最多保存 30 条地址");
            $default=!empty($body["is_default"])||$count===0||($old&&$old["is_default"])?1:0;
            if($default)$pdo->prepare("UPDATE user_addresses SET is_default=0 WHERE user_id=?")->execute([$uid]);
            if($old)$pdo->prepare("UPDATE user_addresses SET name=?,phone=?,province=?,city=?,district=?,detail=?,is_default=? WHERE id=? AND user_id=?")->execute(array_merge($data,[$default,$id,$uid]));
            else{$pdo->prepare("INSERT INTO user_addresses(name,phone,province,city,district,detail,is_default,user_id) VALUES(?,?,?,?,?,?,?,?)")->execute(array_merge($data,[$default,$uid]));$id=(int)$pdo->lastInsertId();}
        }
        $pdo->commit();jsonResponse(["id"=>$id],"地址已保存",$action==="wallet.address.save"?201:200);
    } catch(InvalidArgumentException $e){if($pdo->inTransaction())$pdo->rollBack();jsonResponse(null,$e->getMessage(),422);}
}

function mallStoreImage(int $uid): string
{
    $f=$_FILES["file"] ?? null;if (!$f || $f["error"]!==UPLOAD_ERR_OK || $f["size"]<1 || $f["size"]>5*1024*1024) throw new InvalidArgumentException("请上传 5MB 以内的图片");
    $mime=(new finfo(FILEINFO_MIME_TYPE))->file($f["tmp_name"]);$types=["image/jpeg"=>"jpg","image/png"=>"png","image/webp"=>"webp"]; $size=getimagesize($f["tmp_name"]);
    if (!isset($types[$mime]) || !$size || $size[0]*$size[1]>24000000 || max($size[0],$size[1])>16000) throw new InvalidArgumentException("图片格式或尺寸不支持，请压缩后上传");
    $dir=__DIR__."/uploads/mall";if (!is_dir($dir) && !mkdir($dir,0755,true) && !is_dir($dir)) throw new RuntimeException("图片目录不可用");
    $name=$uid."_".bin2hex(random_bytes(16)).".".$types[$mime];if (!move_uploaded_file($f["tmp_name"],$dir."/".$name)) throw new RuntimeException("图片保存失败");return publicBaseUrl()."/uploads/mall/".$name;
}
