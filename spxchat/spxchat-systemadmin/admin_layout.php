<?php
declare(strict_types=1);

function adminRenderNavigation(array $navGroups, array $sections, string $activeSection): void
{
    foreach ($navGroups as $groupLabel => $groupItems) {
        $visibleItems = array_filter($groupItems, static fn(string $key): bool => isset($sections[$key]), ARRAY_FILTER_USE_KEY);
        if (!$visibleItems) continue;
        echo '<div class="nav-group">';
        echo '<div class="nav-group-title">' . adminEscape($groupLabel) . '</div>';
        foreach ($visibleItems as $key => $label) {
            $active = $activeSection === $key ? ' active' : '';
            echo '<a class="' . $active . '" href="admin.php?section=' . adminEscape($key) . '">' . adminEscape($label) . '</a>';
        }
        echo '</div>';
    }
}
