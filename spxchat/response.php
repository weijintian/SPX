<?php
declare(strict_types=1);

require_once __DIR__ . '/i18n/Translator.php';

// API warnings belong in server logs, never in a JSON upload acknowledgement.
ini_set('display_errors', '0');
ini_set('log_errors', '1');

function jsonResponse(mixed $data = null, string $message = 'ok', int $status = 200): never
{
    $translated = Translator::message($message, $status);
    try {
        $body = json_encode([
            'code' => $status >= 400 ? $status : 0,
            'message' => $translated['message'],
            'data' => $data,
            'language' => $translated['language'],
            'message_key' => $translated['key'],
            'error_code' => strtoupper(str_replace('.', '_', $translated['key'])),
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE | JSON_THROW_ON_ERROR);
    } catch (JsonException $e) {
        error_log('API response JSON encoding failed: '.$e->getCode());
        $status = 500;
        $body = '{"code":500,"message":"Response encoding failed; please retry","data":null}';
    }
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Vary: Accept-Language');
    // CORS 只放行本站域名（App 不发 Origin，无需 CORS）
    $origin = (string) ($_SERVER['HTTP_ORIGIN'] ?? '');
    $allowedOrigins = ['https://spx001.top', 'https://www.spx001.top'];
    if ($origin !== '' && in_array($origin, $allowedOrigins, true)) {
        header('Access-Control-Allow-Origin: ' . $origin);
        header('Vary: Origin');
        header('Access-Control-Allow-Headers: Content-Type, Authorization, Accept-Language');
        header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    }
    echo $body;
    exit;
}

function requestBody(): array
{
    $raw = file_get_contents('php://input');
    if (!$raw) {
        return $_POST ?: [];
    }
    $data = json_decode($raw, true);
    if (is_array($data)) Translator::setRequestedLanguage($data['language'] ?? null);
    return is_array($data) ? $data : [];
}

function requireFields(array $data, array $fields): void
{
    foreach ($fields as $field) {
        $value = $data[$field] ?? null;
        $missing = !array_key_exists($field, $data) || $value === null;
        $blankString = is_string($value) && trim($value) === '';
        $emptyArray = is_array($value) && count($value) === 0;
        if ($missing || $blankString || $emptyArray) {
            jsonResponse(null, "缺少参数: {$field}", 422);
        }
    }
}
