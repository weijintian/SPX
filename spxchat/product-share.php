<?php
declare(strict_types=1);
require_once __DIR__ . '/db.php';
require_once __DIR__ . '/mall.php';
header('Content-Type: text/html; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: strict-origin-when-cross-origin');
header("Content-Security-Policy: default-src 'none'; img-src https: http:; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
header('Cache-Control: no-store');
function shareEscape(mixed $value): string {
    return htmlspecialchars((string) $value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}
function shareMoney(mixed $value): string { $s=(string)$value;return str_contains($s,'.') ? rtrim(rtrim($s,'0'),'.') : $s; }
$id = filter_var($_GET['id'] ?? null, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
$product = null;
try {
    if ($id) {
        $pdo = db();
        $q = $pdo->prepare(mallProductSelect() . ' WHERE p.id=? AND ' . mallVisibleWhere() . ' LIMIT 1');
        $q->execute([$id]);
        $row = $q->fetch();
        if ($row) $product = mallDecorateProduct($pdo, $row);
    }
} catch (Throwable $error) {
    error_log('SPX product share unavailable: ' . get_class($error));
    http_response_code(503);
}
if (!$product && http_response_code() !== 503) http_response_code(404);
$name = $product ? (string) $product['name'] : '商品暂不可用';
$description = $product ? mb_substr((string) $product['description'], 0, 240) : '商品可能已下架，或链接已失效。';
$image = $product ? (string) $product['image_url'] : '';
$imageScheme = strtolower((string) parse_url($image, PHP_URL_SCHEME));
if (!in_array($imageScheme, ['http', 'https'], true)) $image = '';
$cnyPrice = $product ? (string) ($product['cny_price'] ?? '') : '';
$price = $cnyPrice !== '' ? shareMoney($cnyPrice) : ($product ? shareMoney((string) $product['spx_price']) : '');
$shop = $product['shop'] ?? null;
$deepLink = 'spx://product?id=' . (int) $id;
$canonical = 'https://spx001.top/spxchat/product-share.php?id=' . (int) $id;
$type = ['physical' => '实物快递', 'local' => '到店服务', 'digital' => '数字商品'][$product['product_type'] ?? ''] ?? '商品';
$release=null;
try { $release=db()->query('SELECT id,version_name FROM app_releases WHERE is_active=1 ORDER BY version_code DESC LIMIT 1')->fetch() ?: null; } catch(Throwable $e) {}
$download=$release ? 'api.php?action=app.update.download&id='.(int)$release['id'] : 'https://spx001.top/';
?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title><?= shareEscape($name) ?> · SPX 好物商城</title>
    <meta name="description" content="<?= shareEscape($description) ?>">
    <meta property="og:title" content="<?= shareEscape($name) ?>">
    <meta property="og:description" content="<?= shareEscape($description) ?>">
    <meta property="og:type" content="product">
    <meta property="og:url" content="<?= shareEscape($canonical) ?>">
    <?php if ($image): ?><meta property="og:image" content="<?= shareEscape($image) ?>"><?php endif; ?>
    <style>
        *{box-sizing:border-box}body{margin:0;background:#f6f8fc;color:#181f33;font:15px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}
        main{max-width:540px;margin:0 auto;padding:24px 18px 100px}.brand{font-size:12px;letter-spacing:.14em;color:#677187;margin-bottom:18px;font-weight:700}
        .cover{width:100%;aspect-ratio:1;object-fit:cover;border-radius:24px;background:#ebf0ff}.card{padding:24px;background:white;border:1px solid #e2e7f1;border-radius:24px;margin-top:16px}
        h1{font-size:24px;line-height:1.4;margin:12px 0}p{color:#677187;white-space:pre-line;overflow-wrap:anywhere}.price{font-size:30px;font-weight:750;color:#2d4ecf;overflow-wrap:anywhere}.price small{font-size:12px}.tag{display:inline-block;padding:4px 10px;border-radius:8px;background:#ebf0ff;color:#2d4ecf;font-size:12px;margin-right:8px}
        .shop{font-size:16px;font-weight:650}.open{display:block;text-align:center;background:linear-gradient(135deg,#426ae8,#2846c2);color:white;text-decoration:none;border-radius:14px;padding:14px;font-weight:700}.hint{font-size:12px;text-align:center;margin-bottom:0}.empty{text-align:center;padding:48px 24px}.footer{color:#677187;text-align:center;font-size:12px;margin-top:24px}
    </style>
</head>
<body>
<main>
    <div class="brand">SPX · 好物商城</div>
    <?php if ($product): ?>
        <?php if ($image): ?><img class="cover" src="<?= shareEscape($image) ?>" alt="<?= shareEscape($name) ?>" fetchpriority="high"><?php endif; ?>
        <section class="card">
            <span class="tag"><?= shareEscape($type) ?></span>
            <?php if (($shop['level'] ?? '') === 'gold'): ?><span class="tag">黄金店铺</span><?php endif; ?>
            <h1><?= shareEscape($name) ?></h1>
            <div class="price"><?php if ($cnyPrice !== ''): ?>¥<?= shareEscape($price) ?> <small>CNY<?php if (count($product['variants']) > 1): ?> 起<?php endif; ?></small><?php else: ?><?= shareEscape($price) ?> <small>SPX001（历史标价）</small><?php endif; ?></div>
            <p><?= shareEscape($description) ?></p>
            <div class="shop"><?= shareEscape($shop['name'] ?? '平台自营') ?></div>
            <?php if (!empty($shop['address'])): ?><p><?= shareEscape($shop['address']) ?></p><?php endif; ?>
        </section>
        <?php if (!empty($product['detail_text'])): ?><section class="card"><h2>商品介绍</h2><p><?= shareEscape($product['detail_text']) ?></p></section><?php endif; ?>
        <section class="card"><h2>规格与交付</h2>
        <?php foreach ($product['variants'] as $variant): ?><p><?= shareEscape($variant['name']) ?> · <?php if (!empty($variant['cny_price'])): ?>¥<?= shareEscape(shareMoney($variant['cny_price'])) ?> CNY<?php else: ?><?= shareEscape(shareMoney($variant['price'])) ?> SPX001<?php endif; ?> · 库存 <?= (int)$variant['stock'] ?></p><?php endforeach; ?>
        <p><?= shareEscape($product['product_type']==='physical' ? '每单运费 '.$product['shipping_fee'].' SPX001 · '.$product['delivery_days'].' 天内发货' : ($product['product_type']==='digital' ? $product['delivery_days'].' 天内交付' : '付款后 '.$product['service_valid_days'].' 天内有效')) ?></p>
        <?php if(!empty($shop['business_hours'])): ?><p>服务时间：<?= shareEscape($shop['business_hours']) ?></p><?php endif; ?>
        <?php foreach(['parameters'=>'商品参数','delivery_note'=>'使用说明','service_terms'=>'售后与服务'] as $key=>$label): if(!empty($product[$key])): ?><h3><?= $label ?></h3><p><?= shareEscape($product[$key]) ?></p><?php endif; endforeach; ?>
        <?php if(!empty($product['consultation_group'])): ?><p>商品咨询群：<?= shareEscape($product['consultation_group']['name']) ?> · 在 App 商品详情加入群聊</p><?php endif; ?></section>
        <?php foreach ($product['detail_images'] as $url): ?><img class="cover" loading="lazy" src="<?= shareEscape($url) ?>" alt="商品详情图" style="aspect-ratio:auto;margin-top:16px"><?php endforeach; ?>
        <section class="card">
            <a class="open" href="<?= shareEscape($deepLink) ?>">在 SPX App 中查看</a>
            <p class="hint">已安装 App 可直接打开商品；部分浏览器需允许跳转。规格、库存和价格以确认订单页面为准。</p>
            <a class="open" style="margin-top:12px;background:#edf2ff;color:#2d4ecf" href="<?= shareEscape($download) ?>"><?= $release ? '下载 SPX App · '.shareEscape($release['version_name']) : '前往官网下载 App' ?></a>
        </section>
    <?php else: ?>
        <section class="card empty"><h1><?= shareEscape($name) ?></h1><p><?= shareEscape($description) ?></p><a class="open" href="https://spx001.top/">返回 SPX</a></section>
    <?php endif; ?>
    <div class="footer">分享好物 · 发现好店</div>
</main>
</body>
</html>
