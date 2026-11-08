(function () {
    document.querySelectorAll('.copy-value').forEach(function (button) {
        button.addEventListener('click', function () {
            var value = button.getAttribute('data-copy') || '';
            if (!value || !navigator.clipboard) return;
            navigator.clipboard.writeText(value).then(function () {
                var original = button.textContent;
                button.textContent = '已复制';
                window.setTimeout(function () { button.textContent = original; }, 1200);
            });
        });
    });
})();
