<?php
declare(strict_types=1);

final class Translator
{
    private const DEFAULT_LANGUAGE = 'zh-CN';
    private const LANGUAGES = ['zh-CN', 'zh-TW', 'en', 'ja', 'ko'];
    private static ?string $requestedLanguage = null;
    private static array $resources = [];

    public static function setRequestedLanguage(mixed $language): void
    {
        if (is_string($language) && trim($language) !== '') {
            self::$requestedLanguage = trim($language);
        }
    }

    public static function language(): string
    {
        $candidate = self::$requestedLanguage;
        if ($candidate === null && is_scalar($_GET['language'] ?? null)) $candidate = (string) $_GET['language'];
        if ($candidate === null && is_scalar($_POST['language'] ?? null)) $candidate = (string) $_POST['language'];
        $candidate = str_replace('_', '-', trim((string) ($candidate ?? self::acceptLanguage())));
        foreach (self::LANGUAGES as $language) {
            if (strcasecmp($candidate, $language) === 0) return $language;
        }
        if (preg_match('/^zh(?:-|$)/i', $candidate)) return self::DEFAULT_LANGUAGE;
        foreach (['en', 'ja', 'ko'] as $language) {
            if (preg_match('/^' . $language . '(?:-|$)/i', $candidate)) return $language;
        }
        return self::DEFAULT_LANGUAGE;
    }

    public static function message(string $source, int $status): array
    {
        $language = self::language();
        $chinese = self::resource('zh-CN');
        $key = $chinese['source_keys'][$source] ?? self::fallbackKey($source, $status);
        $messages = self::resource($language)['messages'] ?? [];
        $message = $messages[$key] ?? null;
        if (!is_string($message) || $message === '') {
            $message = $language === self::DEFAULT_LANGUAGE ? $source : ($messages[self::statusKey($status)] ?? $messages['api.internal_error']);
        }
        return ['language' => $language, 'key' => $key, 'message' => $message];
    }

    private static function acceptLanguage(): string
    {
        $header = (string) ($_SERVER['HTTP_ACCEPT_LANGUAGE'] ?? '');
        foreach (explode(',', $header) as $part) {
            $language = trim(explode(';', $part, 2)[0]);
            if ($language !== '') return $language;
        }
        return self::DEFAULT_LANGUAGE;
    }

    private static function fallbackKey(string $source, int $status): string
    {
        if ($source === 'ok') return 'api.ok';
        if ($status >= 400) return self::statusKey($status) . '.' . substr(hash('sha256', $source), 0, 12);
        return 'api.success.' . substr(hash('sha256', $source), 0, 12);
    }

    private static function statusKey(int $status): string
    {
        return match (true) {
            $status === 401 => 'api.unauthorized',
            $status === 403 => 'api.forbidden',
            $status === 404 => 'api.not_found',
            $status === 409 => 'api.conflict',
            $status === 422 => 'api.validation_error',
            $status === 429 => 'api.rate_limited',
            $status >= 500 => 'api.internal_error',
            $status >= 400 => 'api.bad_request',
            default => 'api.success',
        };
    }

    private static function resource(string $language): array
    {
        if (!isset(self::$resources[$language])) {
            self::$resources[$language] = require __DIR__ . '/messages/' . $language . '.php';
        }
        return self::$resources[$language];
    }
}
