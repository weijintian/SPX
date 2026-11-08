<?php
declare(strict_types=1);

const CHAT_TEXT_LIMIT = 10000;

function chatText(mixed $value, bool $required = true): string
{
    if (!is_string($value) || !mb_check_encoding($value, 'UTF-8')) throw new InvalidArgumentException('消息格式不正确');
    $text = trim($value);
    if (($required && $text === '') || mb_strlen($text, 'UTF-8') > CHAT_TEXT_LIMIT) throw new InvalidArgumentException('消息不能为空且最多 10000 个字');
    return $text;
}

/** Product fields come from the catalog, never from an untrusted share payload. */
function chatProductCard(PDO $pdo, int $id): string
{
    $q = $pdo->prepare(mallProductSelect() . ' WHERE p.id=? AND ' . mallVisibleWhere());
    $q->execute([$id]);
    $p = $q->fetch();
    if (!$p) throw new InvalidArgumentException('商品已下架或暂不可分享');
    return json_encode(['version'=>1,'product_id'=>(int)$p['id'],'name'=>$p['name'],
        'image_url'=>$p['image_url'] ?? '', 'price'=>(string)$p['spx_price'],
        'cny_price'=>(string)($p['cny_price'] ?? ''),
        'shop_name'=>$p['merchant_name'] ?: 'SPX 好物商城'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
}

function chatMessagePreview(string $type, string $content): string
{
    if ($type === 'file') { $meta = str_starts_with($content, 'SPXFILE:') ? json_decode(substr($content, 8), true) : null; return mb_substr('[文件] ' . (is_array($meta) ? ($meta['name'] ?? '文件') : $content), 0, 160, 'UTF-8'); }
    if ($type === 'red_packet') {
        $packet = json_decode($content, true);
        $symbol = is_array($packet) && in_array(($packet['symbol'] ?? 'SPX001'), ['SPX001', 'FISS'], true)
            ? $packet['symbol'] : 'SPX001';
        return '[' . $symbol . ' 红包]';
    }
    if ($type === 'product') { $p = json_decode($content, true); return mb_substr('[商品] ' . ($p['name'] ?? '商品卡片'), 0, 160, 'UTF-8'); }
    if (str_starts_with($content, 'SPXGROUP:')) return '[群组邀请]';
    return ['image'=>'[图片]','video'=>'[视频]','voice'=>'[语音]'][$type] ?? mb_substr($content, 0, 160, 'UTF-8');
}
