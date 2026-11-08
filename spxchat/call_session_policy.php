<?php
declare(strict_types=1);

/** An offline recipient logging in must not cancel an invitation it has never received. */
function callCanSurviveRecipientLogin(array $call, int $userId): bool
{
    return ($call['state'] ?? '') === 'ringing'
        && (int)($call['callee_id'] ?? 0) === $userId
        && (int)($call['caller_id'] ?? 0) !== $userId
        && (string)($call['callee_device'] ?? '') === '';
}
