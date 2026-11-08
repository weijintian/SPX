<?php
declare(strict_types=1);

/** A contact remark belongs only to the viewing account, never to the group. */
function chatContactName(string $value): string
{
    $value = trim($value);
    return in_array(strtolower($value), ['', 'null', 'undefined'], true) ? '' : $value;
}

function chatViewerNames(PDO $pdo, array $rows, int $viewer): array
{
    $ids = [];
    foreach ($rows as &$row) {
        // Events may already have been decorated for another recipient.
        $row['nickname'] = (string)($row['original_nickname'] ?? $row['nickname'] ?? '');
        unset($row['display_name'], $row['contact_remark']);
        $id = (int)($row['sender_id'] ?? $row['user_id'] ?? $row['id'] ?? 0);
        if ($id > 0) $ids[$id] = $id;
    }
    unset($row);
    if (!$ids || $viewer <= 0) return $rows;
    $stmt = $pdo->prepare('SELECT u.id,u.nickname,c.remark FROM users u LEFT JOIN contacts c ON c.contact_user_id=u.id AND c.user_id=? WHERE u.id IN (' . implode(',', array_fill(0, count($ids), '?')) . ')');
    $stmt->execute(array_merge([$viewer], array_values($ids)));
    $names = [];
    foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $user) $names[(int)$user['id']] = $user;
    foreach ($rows as &$row) {
        $id = (int)($row['sender_id'] ?? $row['user_id'] ?? $row['id'] ?? 0);
        if (!isset($names[$id])) continue;
        $user = $names[$id];
        $remark = chatContactName((string)($user['remark'] ?? ''));
        $row['original_nickname'] = (string)$user['nickname'];
        $row['contact_remark'] = $remark;
        $row['display_name'] = $remark !== '' ? $remark : (string)$user['nickname'];
        // Keep existing Android/iOS consumers compatible with the display contract.
        $row['nickname'] = $row['display_name'];
    }
    unset($row);
    return $rows;
}
