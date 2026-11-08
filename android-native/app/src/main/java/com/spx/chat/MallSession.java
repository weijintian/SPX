package com.spx.chat;

import android.app.Activity;
import android.content.SharedPreferences;

/** Captured identity: a response must never resolve against the next login. */
final class MallSession {
    final String server, token;
    final int userId;
    final SharedPreferences payments;
    MallSession(MainActivity activity) {
        server = ApiClient.baseUrl(activity);
        token = ApiClient.token(activity);
        userId = activity.mallUserId();
        // Keep the existing store so already pending payments remain recoverable.
        payments = activity.getSharedPreferences("mall-checkout-" + userId, 0);
    }
    boolean valid(MainActivity activity) {
        return !activity.isFinishing() && !activity.isDestroyed()
                && userId == activity.mallUserId()
                && server.equals(ApiClient.baseUrl(activity))
                && token.equals(ApiClient.token(activity));
    }
    void clearPayment(String expected) {
        if (expected.equals(payments.getString("pending", "")))
            payments.edit().remove("pending").commit();
    }
}
