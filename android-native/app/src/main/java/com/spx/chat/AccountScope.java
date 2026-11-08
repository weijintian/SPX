package com.spx.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Persistent account identity. Tokens deliberately do not participate: pending requests survive login. */
final class AccountScope {
    static String id(String server, int userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(server.trim().replaceAll("/+$", "").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("account_");
            for (byte value : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.append('_').append(userId).toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private AccountScope() { }
}
