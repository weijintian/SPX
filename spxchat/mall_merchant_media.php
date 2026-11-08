<?php
declare(strict_types=1);

function installMallMerchantMediaSchema(PDO $pdo): void
{
    if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=3')->fetchColumn()) return;
    if ((int) $pdo->query("SELECT GET_LOCK('spx_mall_merchant_media_v3',30)")->fetchColumn() !== 1) throw new RuntimeException('店铺资料正在升级，请稍后重试');
    try {
        if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=3')->fetchColumn()) return;
        $columns = $pdo->query('SHOW COLUMNS FROM merchants')->fetchAll(PDO::FETCH_COLUMN);
        if (!in_array('qualification_images', $columns, true)) $pdo->exec('ALTER TABLE merchants ADD COLUMN qualification_images TEXT NULL');
        $pdo->exec('INSERT IGNORE INTO mall_schema_versions(version) VALUES(3)');
    } finally { $pdo->query("SELECT RELEASE_LOCK('spx_mall_merchant_media_v3')"); }
}

function mallQualificationDirectory(): string
{
    $config = require __DIR__ . '/config.php';
    return rtrim((string) ($config['mall']['private_media_dir'] ?? (dirname(__DIR__, 2) . '/.spx-mall-private')), '/\\') . '/qualifications';
}

function mallQualificationPath(string $id, ?int $owner = null): string
{
    if (!preg_match('/^([1-9][0-9]*)_[a-f0-9]{32}\.(?:jpg|png|webp)$/D', $id, $match) || ($owner !== null && (int) $match[1] !== $owner)) throw new InvalidArgumentException('经营资质图片无效');
    return mallQualificationDirectory() . '/' . $id;
}

function mallQualificationInput(array $body, int $userId, array $fallback = []): array
{
    $images = $body['qualification_images'] ?? $fallback;
    if (!is_array($images) || !array_is_list($images) || count($images) > 6) throw new InvalidArgumentException('经营资质最多上传六张图片');
    foreach ($images as $id) {
        if (!is_string($id) || !is_file(mallQualificationPath($id, $userId))) throw new InvalidArgumentException('请重新上传有效的经营资质图片');
    }
    return array_values(array_unique($images));
}

function mallStoreQualificationImage(int $uid): string
{
    $f = $_FILES['file'] ?? null;
    if (!$f || $f['error'] !== UPLOAD_ERR_OK || $f['size'] < 1 || $f['size'] > 5 * 1024 * 1024) throw new InvalidArgumentException('请上传 5MB 以内的图片');
    $mime = (new finfo(FILEINFO_MIME_TYPE))->file($f['tmp_name']);
    $types = ['image/jpeg' => 'jpg', 'image/png' => 'png', 'image/webp' => 'webp'];
    $size = getimagesize($f['tmp_name']);
    if (!isset($types[$mime]) || !$size || $size[0] * $size[1] > 24000000 || max($size[0], $size[1]) > 16000) throw new InvalidArgumentException('图片格式或尺寸不支持，请压缩后上传');
    $dir = mallQualificationDirectory();
    if (!is_dir($dir) && !mkdir($dir, 0700, true) && !is_dir($dir)) throw new RuntimeException('经营资质图片保存目录不可用');
    $id = $uid . '_' . bin2hex(random_bytes(16)) . '.' . $types[$mime];
    $path = mallQualificationPath($id, $uid);
    if (!move_uploaded_file($f['tmp_name'], $path)) throw new RuntimeException('经营资质图片保存失败');
    chmod($path, 0600);
    return $id;
}

function mallServeQualificationImage(string $id, ?int $owner = null): never
{
    try { $path = mallQualificationPath($id, $owner); }
    catch (InvalidArgumentException $e) { http_response_code(404); exit; }
    if (!is_file($path)) { http_response_code(404); exit; }
    header('Content-Type: ' . (new finfo(FILEINFO_MIME_TYPE))->file($path));
    header('Content-Length: ' . filesize($path));
    header('Cache-Control: no-store, private');
    header('X-Content-Type-Options: nosniff');
    header('Content-Disposition: inline; filename="qualification.' . pathinfo($path, PATHINFO_EXTENSION) . '"');
    readfile($path);
    exit;
}
