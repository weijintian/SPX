package com.spx.chat;

import android.text.InputFilter;
import android.text.Spanned;

/** Count Unicode code points consistently with the API, including emoji. */
final class ChatText {
    static final int LIMIT = 10000;
    static int count(CharSequence s) { return Character.codePointCount(s, 0, s.length()); }
    static final InputFilter FILTER = (source, start, end, dest, dstart, dend) -> {
        int remaining = LIMIT - count(dest) + Character.codePointCount(dest, dstart, dend);
        int incoming = Character.codePointCount(source, start, end);
        if (incoming <= remaining) return null;
        if (remaining <= 0) return "";
        int cut = Character.offsetByCodePoints(source, start, remaining);
        return source.subSequence(start, Math.min(cut, end));
    };
    private ChatText() {}
}
