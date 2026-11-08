package com.spx.chat;

import android.os.SystemClock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Memory-only form state. Never persists passwords or verification codes. */
final class AuthFormState {
    private final LongSupplier clock;
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, Long> deadlines = new HashMap<>();
    private final Set<String> sending = new HashSet<>();
    private int page;
    private boolean submitting;
    AuthFormState() { this(SystemClock::elapsedRealtime); }
    AuthFormState(LongSupplier clock) { this.clock = clock; }
    int nextPage() { return ++page; }
    int page() { return page; }
    boolean submitting() { return submitting; }
    boolean beginSubmit() { if (submitting || hasPendingCodeRequest()) return false; submitting = true; return true; }
    void finishSubmit() { submitting = false; }
    String draft(String key) { return drafts.getOrDefault(key, ""); }
    void draft(String key, String value) { drafts.put(key, value); }
    void clearDrafts() { drafts.clear(); }
    static String address(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    static boolean validEmail(String email) { return android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(); }
    private String key(String email, String purpose) { return purpose + ":" + address(email); }
    boolean sending(String email, String purpose) { return sending.contains(key(email, purpose)); }
    boolean hasPendingCodeRequest() { return !sending.isEmpty(); }
    int remaining(String email, String purpose) {
        long millis = deadlines.getOrDefault(key(email, purpose), 0L) - clock.getAsLong();
        return (int) Math.max(0, Math.min(60, (millis + 999) / 1000));
    }
    boolean beginCode(String email, String purpose) {
        if (sending(email, purpose) || remaining(email, purpose) > 0) return false;
        sending.add(key(email, purpose)); return true;
    }
    void finishCode(String email, String purpose, boolean delivered) {
        sending.remove(key(email, purpose));
        if (delivered) deadlines.put(key(email, purpose), clock.getAsLong() + 60_000);
    }
    static boolean validCode(String code) { return code.trim().matches("[0-9]{6}"); }
}
