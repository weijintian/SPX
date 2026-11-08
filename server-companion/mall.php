<?php
declare(strict_types=1);
require_once __DIR__ . "/mall_commerce.php";
require_once __DIR__ . '/mall_merchant_media.php';
require_once __DIR__ . '/mall_valuation.php';
require_once __DIR__ . '/financial_requests.php';

const MALL_BASIC_DEPOSIT = '100000';
const MALL_GOLD_DEPOSIT = '1000000';
const MALL_AFTER_SALE_DAYS = 7;

function mallRequiredDeposit(string $level): string
{
    if (!in_array($level, ['basic', 'gold'], true)) throw new InvalidArgumentException('店铺等级无效');
    return $level === 'gold' ? MALL_GOLD_DEPOSIT : MALL_BASIC_DEPOSIT;
}

function mallText(array $body, string $key, int $max, bool $required = false): string
{
    if (isset($body[$key]) && !is_scalar($body[$key])) throw new InvalidArgumentException('字段格式无效: ' . $key);
    $value = trim((string) ($body[$key] ?? ''));
    if (($required && $value === '') || mb_strlen($value, 'UTF-8') > $max) throw new InvalidArgumentException('字段为空或过长: ' . $key);
    return $value;
}

function mallMediaUrl(string $url): string
{
    if ($url === '') return '';
    if (strlen($url) > 500 || !filter_var($url, FILTER_VALIDATE_URL) || !in_array(strtolower((string) parse_url($url, PHP_URL_SCHEME)), ['http', 'https'], true)) {
        throw new InvalidArgumentException('图片或视频地址必须是有效的 HTTP/HTTPS 地址');
    }
    return $url;
}

function mallAmount(mixed $value): string
{
    if (!is_scalar($value)) throw new InvalidArgumentException('商品价格无效');
    $value = trim((string) $value);
    if (!preg_match('/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,8})?$/', $value) || !preg_match('/[1-9]/', $value)) throw new InvalidArgumentException('商品价格必须大于零，最多八位小数');
    return $value;
}

function mallCompareAmounts(string $a, string $b): int
{
    $normalize = static function (string $value): string {
        [$whole, $fraction] = array_pad(explode('.', $value, 2), 2, '');
        return str_pad($whole, 16, '0', STR_PAD_LEFT) . str_pad($fraction, 8, '0');
    };
    return strcmp($normalize($a), $normalize($b));
}

function mallProfileInput(array $body): array
{
    $lat = $body['latitude'] ?? null;
    $lon = $body['longitude'] ?? null;
    if ($lat === '') $lat = null;
    if ($lon === '') $lon = null;
    if (($lat === null) !== ($lon === null)) throw new InvalidArgumentException('经纬度需要一起填写');
    if ($lat !== null && (!is_numeric($lat) || !is_numeric($lon) || !is_finite((float) $lat) || !is_finite((float) $lon) || (float) $lat < -90 || (float) $lat > 90 || (float) $lon < -180 || (float) $lon > 180)) throw new InvalidArgumentException('门店坐标无效');
    $coords = mallText($body, 'coordinate_system', 20) ?: 'gcj02';
    if (!in_array($coords, ['gcj02', 'bd09', 'wgs84'], true)) throw new InvalidArgumentException('坐标格式无效');
    $address = mallText($body, 'address', 255);
    if ($lat !== null && $address === '') throw new InvalidArgumentException('标记门店请填写详细地址');
    return [
        mallText($body, 'name', 120, true), mallMediaUrl(mallText($body, 'logo_url', 500)),
        mallText($body, 'description', 3000), mallText($body, 'category', 80, true),
        mallText($body, 'contact_name', 80, true), mallText($body, 'contact_phone', 40, true),
        mallText($body, 'qualification', 3000), $address, $lat, $lon, $coords,
        mallText($body, 'business_hours', 120),
    ];
}

function mallOwnMerchant(PDO $pdo, int $userId, bool $lock = false): array
{
    $q = $pdo->prepare('SELECT * FROM merchants WHERE owner_user_id=?' . ($lock ? ' FOR UPDATE' : ''));
    $q->execute([$userId]);
    $merchant = $q->fetch();
    if (!$merchant) throw new InvalidArgumentException('请先申请开店');
    return $merchant;
}

function mallPublicMerchant(array $merchant): array
{
    return array_intersect_key($merchant, array_flip(['id', 'owner_user_id', 'name', 'logo_url', 'description', 'category', 'level', 'address', 'latitude', 'longitude', 'coordinate_system', 'business_hours', 'contact_phone']));
}

function mallAudit(PDO $pdo, array $merchant, string $action, ?int $target = null, array $metadata = []): void
{
    $pdo->prepare('INSERT INTO merchant_audit_logs (merchant_id,actor_user_id,action,target_id,metadata) VALUES (?,?,?,?,?)')->execute([$merchant['id'], $merchant['owner_user_id'], $action, $target, json_encode($metadata, JSON_UNESCAPED_UNICODE)]);
}

function mallCanSell(PDO $pdo, array $merchant): bool
{
    $q = $pdo->prepare('SELECT CAST(? AS DECIMAL(36,18)) >= CAST(? AS DECIMAL(36,18))');
    $q->execute([$merchant['deposit_frozen'], mallRequiredDeposit((string) $merchant['level'])]);
    return $merchant['status'] === 'active' && (int) $q->fetchColumn() === 1;
}

function mallProductSelect(): string
{
    return 'SELECT p.*,m.name AS merchant_name,m.level AS merchant_level FROM products p LEFT JOIN merchants m ON m.id=p.merchant_id';
}

function mallVisibleWhere(): string
{
    return "p.status=1 AND p.review_status='approved' AND (p.merchant_id IS NULL OR (m.status='active' AND m.deposit_frozen>=IF(m.level='gold',1000000,100000)))";
}

function mallProducts(PDO $pdo, int $merchantId = 0, string $search = '', string $category = '', int $page = 1): array
{
    $where = mallVisibleWhere();
    $params = [];
    if ($merchantId > 0) { $where .= ' AND p.merchant_id=?'; $params[] = $merchantId; }
    if ($search !== '') { $where .= ' AND (p.name LIKE ? OR m.name LIKE ?)'; $params[] = '%' . $search . '%'; $params[] = '%' . $search . '%'; }
    if ($category !== '') { $where .= ' AND p.category=?'; $params[] = $category; }
    $type=(string)($_GET['type'] ?? 'all');
    if(in_array($type,['physical','digital','local'],true)) { $where.=' AND p.product_type=?';$params[]=$type; }
    // A limited boost gives gold shops priority while keeping the curated product order meaningful.
    $offset = (max(1, min(1000, $page)) - 1) * 60;
    $q = $pdo->prepare(mallProductSelect() . mallQualityJoin() . ' WHERE ' . $where . " ORDER BY (p.sort_order-" . mallQualityBoost() . ") ASC,p.id DESC LIMIT 60 OFFSET " . $offset);
    $q->execute($params);
    $products = $q->fetchAll();
    if (!$products) return [];
    $ids = array_column($products, 'id');
    $images = $pdo->prepare('SELECT product_id,image_url FROM product_images WHERE product_id IN (' . implode(',', array_fill(0, count($ids), '?')) . ') ORDER BY product_id,sort_order,id');
    $images->execute($ids);
    $byProduct = [];
    foreach ($images->fetchAll() as $image) $byProduct[$image['product_id']][] = $image['image_url'];
    return array_map(static fn(array $p): array => mallDecorateProduct($pdo, $p, false, $byProduct[$p['id']] ?? []), $products);
}

function mallDecorateProduct(PDO $pdo, array $product, bool $withShop = true, ?array $images = null): array
{
    if ($images === null) {
        $q = $pdo->prepare('SELECT image_url FROM product_images WHERE product_id=? ORDER BY sort_order,id');
        $q->execute([$product['id']]);
        $images = $q->fetchAll(PDO::FETCH_COLUMN);
    }
    $product['cny_price'] = (string) ($product['cny_price'] ?? '');
    $product['images'] = $images;
    if (!empty($product['image_url']) && !in_array($product['image_url'], $product['images'], true)) array_unshift($product['images'], $product['image_url']);
    $product['variants'] = array_values(array_filter(json_decode((string) ($product['variants'] ?? ''), true) ?: [], static fn($v) => !isset($v['enabled']) || $v['enabled']));
    $product['detail_images'] = json_decode((string) ($product['detail_images'] ?? ''), true) ?: [];
    $product['consultation_group'] = null;
    if ($withShop && !empty($product['consultation_group_id'])) {
        $q=$pdo->prepare("SELECT g.id,g.name,g.group_code FROM chat_groups g JOIN merchants m ON m.id=? JOIN chat_group_members gm ON gm.group_id=g.id AND gm.user_id=m.owner_user_id AND gm.role IN ('owner','admin') WHERE g.id=?");
        $q->execute([$product['merchant_id'],$product['consultation_group_id']]);
        $product['consultation_group']=$q->fetch() ?: null;
    }
    if ($withShop && !empty($product['merchant_id'])) {
        $q = $pdo->prepare('SELECT * FROM merchants WHERE id=?');
        $q->execute([$product['merchant_id']]);
        $m = $q->fetch();
        $product['shop'] = $m ? mallPublicMerchant($m) : null;
    }
    return $product;
}

function mallFreezeDeposit(PDO $pdo, array $merchant, string $level): void
{
    if (!in_array($merchant['status'], ['awaiting_deposit', 'active'], true)) throw new InvalidArgumentException('店铺未通过审核或已暂停');
    if ($merchant['level'] === 'gold' && $level === 'basic') throw new InvalidArgumentException('降级需要平台审核');
    $required = mallRequiredDeposit($level);
    $q = $pdo->prepare('SELECT GREATEST(0,CAST(? AS DECIMAL(36,18))-CAST(? AS DECIMAL(36,18)))');
    $q->execute([$required, $merchant['deposit_frozen']]);
    $delta = (string) $q->fetchColumn();
    if (!preg_match('/[1-9]/', $delta)) return; // Retried requests never debit twice.
    $used = walletDebit($pdo, (int) $merchant['owner_user_id'], 'SPX001', 'BSC', $delta);
    $pdo->prepare("UPDATE merchants SET deposit_frozen=deposit_frozen+?,deposit_stakeable=deposit_stakeable+?,level=?,status='active' WHERE id=?")->execute([$delta, $used, $level, $merchant['id']]);
    $pdo->prepare("INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,stakeable_amount,status,mall_merchant_id) VALUES (?,'SPX001','BSC','merchant_deposit',?,?,'completed',?)")->execute([$merchant['owner_user_id'], $delta, $used, $merchant['id']]);
    $pdo->prepare("INSERT INTO merchant_deposit_ledger (merchant_id,type,amount,stakeable_amount,balance_after,reason) VALUES (?,'freeze',?,?,?,'开店或升级保证金')")->execute([$merchant['id'], $delta, $used, $required]);
    mallAudit($pdo, $merchant, 'deposit.freeze', null, ['amount' => $delta, 'level' => $level]);
}

function mallSaveProduct(PDO $pdo, array $merchant, array $body): int
{
    $requestId=financialRequestID($body);
    if($requestId!=='') {
        $previous=financialRequestBegin($pdo,(int)$merchant['owner_user_id'],'merchant.product.save',$requestId,financialPayload('merchant.product.save',$body));
        if($previous!==null) {
            if(!empty($previous['cancelled'])) throw new InvalidArgumentException('这次发布请求已撤销，请重新提交');
            return (int)$previous['id'];
        }
    }
    if (!mallCanSell($pdo,$merchant)) throw new InvalidArgumentException("请先完成开店审核与保证金缴纳");
    $id=max(0,(int)($body["id"] ?? 0));$old=null;
    if ($id) {
        $q=$pdo->prepare("SELECT * FROM products WHERE id=? AND merchant_id=? FOR UPDATE");$q->execute([$id,$merchant["id"]]);$old=$q->fetch();
        if (!$old) throw new InvalidArgumentException("商品不存在或无权编辑");
        if ((int)($body["version"] ?? 0)!==(int)$old["version"]) throw new InvalidArgumentException("商品资料已更新，请刷新后编辑");
        if ($old["review_status"]==="rejected") throw new InvalidArgumentException("商品已被平台限制，请联系平台处理");
    }
    $type=mallText($body,"product_type",20,true);
    if (!in_array($type,["physical","digital","local"],true)) throw new InvalidArgumentException("商品类型无效");
    if ($old && $type!==$old["product_type"]) throw new InvalidArgumentException("已发布商品不能变更交付类型，请发布新商品");
    if ($type==="local" && ($merchant["latitude"]===null || $merchant["longitude"]===null)) throw new InvalidArgumentException("请先设置门店位置");
    $name=mallText($body,"name",150,true);$description=mallText($body,"description",5000,true);
    $rawCny = trim((string) ($body['cny_price'] ?? ''));
    if ($rawCny === '') {
        throw new InvalidArgumentException('请填写 CNY 商品价格；旧 SPX 商品不能直接按原数字换算，请先确认人民币售价');
    }
    $cnyPrice=mallAmount($rawCny);
    $pricing=mallPricingSnapshot($pdo);
    $price=mallCnyToSpx($cnyPrice,$pricing);
    $stock=filter_var($body["stock"] ?? null,FILTER_VALIDATE_INT);
    if ($stock===false || $stock<0 || $stock>1000000) throw new InvalidArgumentException("库存须为 0 到 1000000 的整数");
    $previous=json_decode((string)($old["variants"] ?? ""),true) ?: [];$byId=[];foreach ($previous as $v)$byId[$v["id"]]=$v;
    $raw=$body["variants"] ?? [];if (!is_array($raw) || count($raw)>50) throw new InvalidArgumentException("最多支持 50 种规格");
    if ($old && (bool)$previous!==(bool)$raw) throw new InvalidArgumentException("已发布商品不能切换单规格与多规格模式");
    $variants=[];$ids=[];$combos=[];$active=[];
    foreach ($raw as $v) {
        if (!is_array($v)) throw new InvalidArgumentException("规格格式无效");
        $vid=mallText($v,"id",64,true);if (!preg_match("/^[A-Za-z0-9_-]+$/",$vid) || isset($ids[$vid]))throw new InvalidArgumentException("规格编号无效或重复");$ids[$vid]=true;
        $vs=filter_var($v["stock"] ?? null,FILTER_VALIDATE_INT);if($vs===false || $vs<0 || $vs>1000000)throw new InvalidArgumentException("规格库存无效");
        if(isset($byId[$vid]))$vs=(int)$byId[$vid]["stock"];
        $attrs=$v["attributes"] ?? [];if(!is_array($attrs)||count($attrs)>3)throw new InvalidArgumentException("最多三个规格维度");$clean=[];
        foreach($attrs as $key=>$value){if(!is_string($key)||!is_scalar($value)||trim($key)===""||mb_strlen($key)>20||trim((string)$value)===""||mb_strlen((string)$value)>40)throw new InvalidArgumentException("规格属性无效");$clean[trim($key)]=trim((string)$value);}
        ksort($clean);$vn=mallText($v,"name",150,true);$enabled=(!array_key_exists("enabled",$v)||$v["enabled"])?1:0;
        $combo=$clean ? json_encode($clean,JSON_UNESCAPED_UNICODE):$vn;if($enabled&&isset($combos[$combo]))throw new InvalidArgumentException("存在重复的规格组合");if($enabled)$combos[$combo]=true;
        $rawVariantCny = trim((string) ($v['cny_price'] ?? ''));
        if ($rawVariantCny === '') throw new InvalidArgumentException('每个商品规格都必须填写 CNY 价格');
        $variantCny=mallAmount($rawVariantCny);
        $sku=["id"=>$vid,"name"=>$vn,"price"=>mallCnyToSpx($variantCny,$pricing),"cny_price"=>$variantCny,"stock"=>$vs,"enabled"=>$enabled,"attributes"=>(object)$clean,"image_url"=>mallMediaUrl(mallText($v,"image_url",500))];
        $variants[]=$sku;if($enabled)$active[]=$sku;
    }
    foreach($previous as $v)if(!isset($ids[$v["id"]])){ $v["enabled"]=0;$variants[]=$v; }
    if(count($variants)>100)throw new InvalidArgumentException("历史规格过多，请发布新商品");
    if($raw && !$active)throw new InvalidArgumentException("至少保留一种启用的规格");
    if($variants){$stock=mallActiveStock($variants);usort($active,static fn($a,$b)=>mallCompareAmounts($a["cny_price"],$b["cny_price"]));$price=$active[0]["price"];$cnyPrice=$active[0]["cny_price"];}elseif($old)$stock=(int)$old["stock"];
    if($stock>1000000)throw new InvalidArgumentException("商品总库存超过上限");
    $gallery=$body["images"]??[];$details=$body["detail_images"]??[];
    if(!is_array($gallery)||count($gallery)<1||count($gallery)>8||!is_array($details)||count($details)>20)throw new InvalidArgumentException("请上传 1 到 8 张主图，详情图最多 20 张");
    $gallery=array_map(static fn($url)=>mallMediaUrl((string)$url),$gallery);$details=array_map(static fn($url)=>mallMediaUrl((string)$url),$details);
    if(in_array("",$gallery,true)||in_array("",$details,true))throw new InvalidArgumentException("图片地址不能为空");
    $fee=trim((string)($body["shipping_fee"]??"0"));if(!preg_match("/^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,8})?$/",$fee))throw new InvalidArgumentException("运费格式无效");if($type!=="physical")$fee="0";
    $days=filter_var($body["delivery_days"]??3,FILTER_VALIDATE_INT);$valid=filter_var($body["service_valid_days"]??30,FILTER_VALIDATE_INT);
    if($days===false||$days<0||$days>90||$valid===false||$valid<1||$valid>365)throw new InvalidArgumentException("发货天数须为 0—90，有效期须为 1—365 天");
    $groupCode=mallText($body,"consultation_group_code",20,true);$groupId=mallConsultationGroup($pdo,(int)$merchant['owner_user_id'],strtoupper($groupCode));
    $values=[$name,$description,$gallery[0],$price,$cnyPrice,$stock,$type,mallText($body,"category",80,true),mallText($body,"detail_text",15000),json_encode($details),mallMediaUrl(mallText($body,"video_url",500)),mallText($body,"parameters",5000),mallText($body,"service_terms",5000),mallText($body,"delivery_note",500),json_encode($variants,JSON_UNESCAPED_UNICODE),$fee,$days,$valid,!empty($body["appointment_required"])?1:0,$groupId];
    if($old){
        $pdo->prepare("UPDATE products SET name=?,description=?,image_url=?,spx_price=?,cny_price=?,stock=?,product_type=?,category=?,detail_text=?,detail_images=?,video_url=?,parameters=?,service_terms=?,delivery_note=?,variants=?,shipping_fee=?,delivery_days=?,service_valid_days=?,appointment_required=?,consultation_group_id=?,status=1,review_status='approved',review_reason=NULL,version=version+1,inventory_version=inventory_version+1 WHERE id=? AND merchant_id=?")->execute(array_merge($values,[$id,$merchant["id"]]));
    }else{
        $pdo->prepare("INSERT INTO products(name,description,image_url,spx_price,cny_price,stock,product_type,category,detail_text,detail_images,video_url,parameters,service_terms,delivery_note,variants,shipping_fee,delivery_days,service_valid_days,appointment_required,consultation_group_id,merchant_id,status,review_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?, ?,1,'approved')")->execute(array_merge($values,[$merchant["id"]]));$id=(int)$pdo->lastInsertId();
    }
    $pdo->prepare("DELETE FROM product_images WHERE product_id=?")->execute([$id]);foreach($gallery as $i=>$url)$pdo->prepare("INSERT INTO product_images(product_id,image_url,sort_order) VALUES(?,?,?)")->execute([$id,$url,$i]);
    mallAudit($pdo,$merchant,"product.auto_approved",$id,["rules"=>"commerce_v2","version"=>($old?(int)$old["version"]+1:1)]);
    financialRequestFinish($pdo,(int)$merchant['owner_user_id'],'merchant.product.save',$requestId,['id'=>$id]);
    return $id;
}

/** Call only with the order row locked. */
function mallRefundOrder(PDO $pdo, array $order): void
{
    if ($order['settlement_status'] === 'settled' || in_array($order['status'], ['cancelled', 'refunded'], true)) throw new InvalidArgumentException('订单已结算或已退款');
    $q = $pdo->prepare('SELECT stock,variants FROM products WHERE id=? FOR UPDATE');
    $q->execute([$order['product_id']]);
    $currentProduct=$q->fetch();
    walletCreditSplit($pdo, (int) $order['user_id'], 'SPX001', 'BSC', (string) $order['total_spx'], (string) $order['stakeable_amount']);
    $variants = json_decode((string)($currentProduct['variants'] ?? ''), true) ?: [];
    $restock = $order['status'] === 'pending';
    $found = !$variants && ($order['variant_id'] ?? 'default') === 'default';
    foreach ($variants as &$variant) if ((string) ($variant['id'] ?? '') === (string) $order['variant_id']) { $found = true; if ($restock) $variant['stock'] = (int) ($variant['stock'] ?? 0) + (int) $order['quantity']; }
    unset($variant);
    if ($restock && $found) $pdo->prepare('UPDATE products SET stock=?,variants=?,inventory_version=inventory_version+1 WHERE id=?')->execute([$variants ? mallActiveStock($variants) : (int)$currentProduct['stock']+(int)$order['quantity'], json_encode($variants, JSON_UNESCAPED_UNICODE), $order['product_id']]);
    if($restock&&$found)$pdo->prepare("INSERT INTO mall_inventory_logs(product_id,variant_id,delta,reason,actor_user_id,order_id) VALUES(?,?,?,'取消订单回库',?,?)")->execute([$order['product_id'],$order['variant_id']??'default',$order['quantity'],$order['user_id'],$order['id']]);
    $pdo->prepare("UPDATE orders SET status='refunded',settlement_status='refunded',after_sale_status='refunded',refunded_at=NOW() WHERE id=?")->execute([$order['id']]);
    mallEvent($pdo,(int)$order['id'],'refunded','退款已退回原余额及可质押额度');
    $pdo->prepare("INSERT INTO wallet_transactions (user_id,symbol,chain,type,amount,stakeable_amount,status,mall_order_id,mall_merchant_id) VALUES (?,'SPX001','BSC','refund',?,?,'completed',?,?)")->execute([$order['user_id'], $order['total_spx'], $order['stakeable_amount'], $order['id'], $order['merchant_id']]);
}

function mallHandleApi(string $action, string $method, array $body): void
{
    mallCommerceApi($action, $method, $body);
    $read = ['mall.config', 'mall.valuation', 'shops', 'shop', 'merchant.me', 'merchant.products', 'merchant.orders', 'merchant.qualification.image'];
    $write = ['merchant.apply', 'merchant.profile.save', 'merchant.deposit.pay', 'merchant.product.save', 'merchant.product.offline', 'merchant.close', 'merchant.media.upload', 'merchant.logo.upload', 'merchant.qualification.upload', 'merchant.order.ship', 'merchant.order.deliver', 'merchant.order.redeem', 'orders.receive', 'orders.after_sale'];
    if (!in_array($action, array_merge($read, $write), true)) return;
    if (($method === 'GET' && !in_array($action, $read, true)) || ($method !== 'GET' && $method !== 'POST') || ($method === 'POST' && !in_array($action, $write, true))) jsonResponse(null, '请求方式无效', 405);
    $pdo = db();
    try {
        if ($action === 'mall.valuation') {
            currentUser();
            try { $valuation = mallValuation($pdo); }
            catch (Throwable $e) { jsonResponse(null, '人民币参考行情暂不可用', 503); }
            jsonResponse($valuation);
        }
        if ($action === 'mall.config') jsonResponse(['product_request_recovery' => true, 'symbol' => 'SPX001', 'basic_deposit' => MALL_BASIC_DEPOSIT, 'gold_deposit' => MALL_GOLD_DEPOSIT, 'after_sale_days' => MALL_AFTER_SALE_DAYS, 'categories' => ['服饰', '数码', '家居', '食品', '本地服务', '数字商品', '其他']]);
        if ($action === 'shops') {
            [$shopLimit,$shopOffset]=mallPage();
            $where = "m.status='active' AND m.deposit_frozen>=IF(m.level='gold',1000000,100000)";
            $params = [];
            if (($_GET['level'] ?? '') === 'gold') $where .= " AND m.level='gold'";
            $search = mb_substr(trim((string) ($_GET['q'] ?? '')), 0, 100);
            if ($search !== '') { $where .= ' AND (m.name LIKE ? OR m.category LIKE ?)'; $params = ['%' . $search . '%', '%' . $search . '%']; }
            $q = $pdo->prepare('SELECT m.* FROM merchants m '.mallQualityJoin(false).' WHERE ' . $where . " ORDER BY ".mallQualityBoost(false)." DESC,CRC32(CONCAT(m.id,DATE_FORMAT(NOW(),'%Y%m%d%H'))) ASC LIMIT ".$shopLimit." OFFSET ".$shopOffset);
            $q->execute($params);
            jsonResponse(array_map('mallPublicMerchant', $q->fetchAll()));
        }
        if ($action === 'shop') {
            $q = $pdo->prepare('SELECT * FROM merchants WHERE id=?'); $q->execute([(int) ($_GET['id'] ?? 0)]); $shop = $q->fetch();
            if (!$shop || !mallCanSell($pdo, $shop)) jsonResponse(null, '店铺不存在或暂停营业', 404);
            jsonResponse(['shop' => mallPublicMerchant($shop), 'products' => mallProducts($pdo, (int) $shop['id'], '', '', max(1,(int)($_GET['page']??1)))]);
        }
        $user = currentUser(); $uid = (int) $user['id'];
        if ($action === 'merchant.qualification.image') {
            mallServeQualificationImage((string) ($_GET['id'] ?? ''), $uid);
        }
        if ($action === 'merchant.logo.upload' || $action === 'merchant.qualification.upload') {
            enforceApiRateLimit('mall_shop_upload:' . $uid, 60);
            if ($action === 'merchant.logo.upload') jsonResponse(['url' => mallStoreImage($uid)]);
            jsonResponse(['id' => mallStoreQualificationImage($uid)]);
        }
        if ($action === 'merchant.media.upload') {
            enforceApiRateLimit('mall_upload:' . $uid, 60);
            $m = mallOwnMerchant($pdo, $uid);
            if (!in_array($m['status'], ['active', 'awaiting_deposit'], true)) throw new InvalidArgumentException('店铺审核通过后可上传商品图片并保存草稿，上架前须缴足保证金');
            jsonResponse(['url'=>mallStoreImage($uid)]);
        }
        if ($action === 'merchant.me') {
            mallTryFinishClosure($pdo,$uid);
            $q = $pdo->prepare('SELECT * FROM merchants WHERE owner_user_id=?'); $q->execute([$uid]); $m = $q->fetch();
            if (!$m) jsonResponse(['merchant' => null]);
            $m['qualification_images'] = json_decode((string) ($m['qualification_images'] ?? ''), true) ?: [];
            $q = $pdo->prepare('SELECT * FROM merchant_deposit_ledger WHERE merchant_id=? ORDER BY id DESC LIMIT 30'); $q->execute([$m['id']]);
            $ledger = $q->fetchAll();
            $q = $pdo->prepare("SELECT COALESCE(SUM(IF(settlement_status='held' AND status NOT IN ('cancelled','refunded'),total_spx,0)),0) AS held_amount,COALESCE(SUM(IF(settlement_status='settled',total_spx,0)),0) AS settled_amount,COUNT(*) AS order_count FROM orders WHERE merchant_id=?"); $q->execute([$m['id']]);
            jsonResponse(['merchant' => $m, 'required_deposit' => mallRequiredDeposit($m['level']), 'deposit_ledger' => $ledger, 'statistics' => $q->fetch()]);
        }
        if ($action === 'merchant.products' || $action === 'merchant.orders') {
            [$pageSize,$pageOffset]=mallPage();
            $m = mallOwnMerchant($pdo, $uid);
            $q = $pdo->prepare($action === 'merchant.products' ? 'SELECT p.*,g.group_code AS consultation_group_code FROM products p LEFT JOIN chat_groups g ON g.id=p.consultation_group_id WHERE p.merchant_id=? ORDER BY p.id DESC LIMIT '.$pageSize.' OFFSET '.$pageOffset : 'SELECT o.*,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(o.product_snapshot,"$.name")),p.name) AS product_name FROM orders o JOIN products p ON p.id=o.product_id WHERE o.merchant_id=? ORDER BY o.id DESC LIMIT '.$pageSize.' OFFSET '.$pageOffset);
            $q->execute([$m['id']]); $rows = $q->fetchAll();
            if ($action === 'merchant.products') $rows = array_map(static fn(array $p): array => mallDecorateProduct($pdo, $p, false), $rows);
            // The buyer must present the code. Never let a shop retrieve it and self-redeem.
            else foreach ($rows as &$row) { $row=mallOrderView($pdo,$row); unset($row['redeem_code']); }
            unset($row);
            jsonResponse($rows);
        }
        // Count failed guesses outside the transaction so rollback cannot erase them.
        if ($action === 'merchant.order.redeem') enforceApiRateLimit('mall_redeem:' . $uid, 100);
        $pdo->beginTransaction();
        if ($action === 'merchant.deposit.pay' || $action === 'merchant.close') {
            $buyerLock=$pdo->prepare("SELECT id FROM users WHERE id=? FOR UPDATE");$buyerLock->execute([$uid]);
        }
        if ($action === 'merchant.apply') {
            // Lock the user to serialize the first application (one shop per account).
            $q = $pdo->prepare('SELECT id FROM users WHERE id=? FOR UPDATE'); $q->execute([$uid]);
            $profile = mallProfileInput($body); $level = mallText($body, 'level', 20, true); mallRequiredDeposit($level);
            $q = $pdo->prepare('SELECT * FROM merchants WHERE owner_user_id=? FOR UPDATE'); $q->execute([$uid]); $m = $q->fetch();
            if ($m && !in_array($m['status'], ['rejected', 'closed'], true)) throw new InvalidArgumentException('已提交申请，请查看商家中心');
            $qualificationImages = mallQualificationInput($body, $uid, $m ? (json_decode((string) ($m['qualification_images'] ?? ''), true) ?: []) : []);
            if ($m) {
                $pdo->prepare("UPDATE merchants SET name=?,logo_url=?,description=?,category=?,contact_name=?,contact_phone=?,qualification=?,address=?,latitude=?,longitude=?,coordinate_system=?,business_hours=?,level=?,status='pending',review_reason=NULL,close_requested_at=NULL WHERE id=?")->execute(array_merge($profile, [$level, $m['id']]));
            } else {
                $pdo->prepare("INSERT INTO merchants (name,logo_url,description,category,contact_name,contact_phone,qualification,address,latitude,longitude,coordinate_system,business_hours,level,owner_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")->execute(array_merge($profile, [$level, $uid]));
                $m = ['id' => (int) $pdo->lastInsertId(), 'owner_user_id' => $uid];
            }
            $pdo->prepare('UPDATE merchants SET qualification_images=? WHERE id=?')->execute([json_encode($qualificationImages), $m['id']]);
            mallAudit($pdo, $m, 'apply');
        } elseif ($action === 'orders.receive' || $action === 'orders.after_sale') {
            $q = $pdo->prepare('SELECT * FROM orders WHERE id=? AND user_id=? FOR UPDATE'); $q->execute([(int) ($body['order_id'] ?? 0), $uid]); $o = $q->fetch();
            if (!$o) throw new InvalidArgumentException('订单不存在');
            if ($action === 'orders.receive') {
                if ($o['status'] !== 'shipped' || in_array($o['after_sale_status'], ['requested','return_approved','return_shipped'], true)) throw new InvalidArgumentException('订单状态不支持确认完成');
                $pdo->prepare("UPDATE orders SET status='delivered',received_at=NOW() WHERE id=?")->execute([$o['id']]);
                mallEvent($pdo,(int)$o['id'],'delivered','买家确认完成，进入七天售后期');
            } else {
                if ($o['settlement_status'] !== 'held' || in_array($o['status'], ['cancelled', 'refunded'], true)) throw new InvalidArgumentException('该订单不能申请售后，请联系平台客服');
                if ($o['received_at'] && strtotime($o['received_at']) + MALL_AFTER_SALE_DAYS * 86400 <= time()) throw new InvalidArgumentException('售后期已结束，请联系平台客服');
                $reason = mallText($body, 'reason', 500, true);
                $pdo->prepare("UPDATE orders SET after_sale_status='requested',after_sale_reason=?,after_sale_reply=NULL WHERE id=?")->execute([$reason, $o['id']]);
            }
        } else {
            $m = mallOwnMerchant($pdo, $uid, true);
            if ($action === 'merchant.profile.save') {
                if (!in_array($m['status'], ['active', 'awaiting_deposit'], true)) throw new InvalidArgumentException('当前状态不能编辑店铺');
                $profile = mallProfileInput($body);
                if ($profile[3] !== $m['category'] || $profile[6] !== (string) ($m['qualification'] ?? '')) throw new InvalidArgumentException('经营类目和资质变更请联系平台重新审核');
                $approvedImages = json_decode((string) ($m['qualification_images'] ?? ''), true) ?: [];
                if (mallQualificationInput($body, $uid, $approvedImages) !== $approvedImages) throw new InvalidArgumentException('经营资质图片变更请联系平台重新审核');
                $pdo->prepare('UPDATE merchants SET name=?,logo_url=?,description=?,category=?,contact_name=?,contact_phone=?,qualification=?,address=?,latitude=?,longitude=?,coordinate_system=?,business_hours=? WHERE id=?')->execute(array_merge($profile, [$m['id']]));
                mallAudit($pdo, $m, 'profile.save');
            } elseif ($action === 'merchant.deposit.pay') {
                mallFreezeDeposit($pdo, $m, mallText($body, 'level', 20, true));
            } elseif ($action === 'merchant.product.save') {
                $id = mallSaveProduct($pdo, $m, $body);
            } elseif ($action === 'merchant.product.offline') {
                $pdo->prepare('UPDATE products SET status=0 WHERE id=? AND merchant_id=?')->execute([(int) ($body['id'] ?? 0), $m['id']]); mallAudit($pdo, $m, 'product.offline', (int) ($body['id'] ?? 0));
            } elseif ($action === 'merchant.close') {
                mallRequestClosure($pdo,$m);
            } else {
                $q = $pdo->prepare('SELECT * FROM orders WHERE id=? AND merchant_id=? FOR UPDATE'); $q->execute([(int) ($body['order_id'] ?? 0), $m['id']]); $o = $q->fetch();
                if (!$o || $o['status'] !== 'pending' || in_array($o['after_sale_status'], ['requested','return_approved','return_shipped'], true)) throw new InvalidArgumentException('订单不存在或当前不可履约');
                if ($action === 'merchant.order.ship') {
                    if ($o['order_type'] !== 'physical') throw new InvalidArgumentException('仅实物订单可发货');
                    $pdo->prepare("UPDATE orders SET status='shipped',carrier=?,tracking_number=?,shipped_at=NOW() WHERE id=?")->execute([mallText($body, 'carrier', 100, true), mallText($body, 'tracking_number', 120, true), $o['id']]);
                } elseif ($action === 'merchant.order.deliver') {
                    if ($o['order_type'] !== 'digital') throw new InvalidArgumentException('仅数字商品可登记交付');
                    $pdo->prepare("UPDATE orders SET status='shipped',fulfillment_note=?,shipped_at=NOW() WHERE id=?")->execute([mallText($body, 'fulfillment_note', 500, true), $o['id']]);
                } elseif ($action === 'merchant.order.redeem') {
                    if (($o['service_expires_at'] && strtotime($o['service_expires_at']) < time()) || $o['order_type'] !== 'local' || !$o['redeem_code'] || !hash_equals($o['redeem_code'], mallText($body, 'redeem_code', 16, true))) throw new InvalidArgumentException('核销码无效');
                    $pdo->prepare("UPDATE orders SET status='delivered',received_at=NOW() WHERE id=?")->execute([$o['id']]);
                }
                mallEvent($pdo,(int)$o['id'],$action,$action==='merchant.order.ship'?'商家已发货':($action==='merchant.order.deliver'?'数字商品已交付':'到店服务已核销'));
                mallAudit($pdo, $m, $action, (int) $o['id']);
            }
        }
        $pdo->commit();
        jsonResponse(isset($id) ? ['id' => $id] : null, '操作成功');
    } catch (InvalidArgumentException $e) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        jsonResponse(null, $e->getMessage(), 422);
    }
}
