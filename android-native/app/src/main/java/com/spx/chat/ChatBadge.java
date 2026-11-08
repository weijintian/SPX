package com.spx.chat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;

/** Launcher counts use authoritative unread totals, never the number of websocket deliveries. */
final class ChatBadge {
    static final String GROUP = "spx.messages";
    private static final String CHANNEL = "spx_unread_badge_v1";
    private ChatBadge() {}

    static int count(JSONArray rows) {
        long total = 0;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) total += Math.max(0, row.optInt("unread_count"));
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    static int conversationCount(JSONArray rows, int chat, int group) {
        if (rows == null) return 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            boolean isGroup = "group".equals(row.optString("kind"));
            if (isGroup == (group > 0) && row.optInt("id") == (group > 0 ? group : chat)) return Math.max(0, row.optInt("unread_count"));
        }
        return 0;
    }

    static boolean permitted(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled());
    }

    static boolean badgeEnabled(Context context) { return context.getSharedPreferences("spx_profile",Context.MODE_PRIVATE).getBoolean("notifications_badge",true); }

    static void update(Context context, int user, JSONArray rows) {
        if (user <= 0) return;
        int total = count(rows);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        String prefix = "chat:" + user + ":";
        // Reading one conversation only clears that conversation's notifications.
        if (Build.VERSION.SDK_INT >= 23) for (StatusBarNotification posted : manager.getActiveNotifications()) {
            String tag = posted.getTag();
            if (tag == null || !tag.startsWith(prefix)) continue;
            String[] key = tag.substring(prefix.length()).split(":");
            if (key.length != 2) continue;
            try {
                int id = Integer.parseInt(key[1]);
                if (conversationCount(rows, "c".equals(key[0]) ? id : 0, "g".equals(key[0]) ? id : 0) == 0) manager.cancel(tag, posted.getId());
            } catch (NumberFormatException ignored) { }
        }
        if (total == 0 || !badgeEnabled(context) || !context.getSharedPreferences("spx_profile",Context.MODE_PRIVATE).getBoolean("notifications_enabled",true)) { manager.cancel("unread:" + user, 0); return; }
        if (!permitted(context)) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "未读消息角标", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示未读消息总数，不播放声音"); channel.setSound(null, null); channel.enableVibration(false); channel.setShowBadge(true);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.setData(android.net.Uri.parse("spxchat://unread/" + user));
        open.putExtra("notification_user_id",user).putExtra("notification_open_unread",true);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("SPX IM")
            .setContentText("你有 " + total + " 条未读消息").setNumber(total).setShowWhen(false)
            .setOnlyAlertOnce(true).setAutoCancel(false).setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setGroup(GROUP).setGroupSummary(true);
        if (Build.VERSION.SDK_INT >= 26) builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).setBadgeIconType(Notification.BADGE_ICON_SMALL);
        else builder.setPriority(Notification.PRIORITY_LOW).setDefaults(0);
        try { manager.notify("unread:" + user, 0, builder.build()); } catch (SecurityException ignored) { }
    }

    static void clear(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT < 23) return;
        for (StatusBarNotification posted : manager.getActiveNotifications()) {
            String tag = posted.getTag();
            if (tag != null && (tag.startsWith("chat:") || tag.startsWith("unread:"))) manager.cancel(tag, posted.getId());
        }
    }
}
