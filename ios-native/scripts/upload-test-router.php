<?php
declare(strict_types=1);

// Only for the loopback integration harness. Never load the production API or database.
$root = getenv('SPX_UPLOAD_TEST_ROOT');
if (PHP_SAPI !== 'cli-server' || !$root || !is_dir($root) || !is_file($root . '/test-marker')) {
    http_response_code(403);
    exit;
}
$source = dirname(__DIR__, 2) . '/spxchat/chat_upload.php';
if (!is_file($source)) $source = dirname(__DIR__, 2) . '/server-companion/chat_upload.php';
require $source;
header('Content-Type: application/json');
try {
    $uid = (int)($_GET['uid'] ?? 9);
    $operation = $_GET['operation'] ?? '';
    if ($operation === 'health') {
        $data = ['ready' => true];
    } elseif ($operation === 'status') {
        $data = chatUploadStatus($uid, $_GET, $root . '/state');
    } elseif ($operation === 'chunk' && $_SERVER['REQUEST_METHOD'] === 'POST') {
        $data = chatReceiveChunk($uid, $_POST, $_FILES['chunk'] ?? [], $root . '/messages', $root . '/state', 'https://example.invalid');
    } elseif ($operation === 'single' && $_SERVER['REQUEST_METHOD'] === 'POST') {
        $file = $_FILES['file'] ?? [];
        if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK || !is_uploaded_file($file['tmp_name'] ?? '')) {
            throw new DomainException('Missing upload fixture', 422);
        }
        $data = chatSaveUpload($file['tmp_name'], $uid, $root . '/messages', 'https://example.invalid');
    } else {
        throw new DomainException('Unknown test operation', 404);
    }
    echo json_encode(['code' => 0, 'data' => $data], JSON_THROW_ON_ERROR);
} catch (Throwable $error) {
    $status = $error instanceof DomainException ? $error->getCode() : 500;
    http_response_code($status >= 400 && $status <= 599 ? $status : 500);
    echo json_encode(['code' => $status, 'message' => $error->getMessage()], JSON_THROW_ON_ERROR);
}
