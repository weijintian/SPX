package com.spx.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ChatCache extends SQLiteOpenHelper {
    private static final String NAME = "spx_chat_cache.db";
    private static final int VERSION = 3;
    private static ChatCache instance;
    private final Context context;
    private final ThreadLocal<String> scheduledIdentity = new ThreadLocal<>();
    void execute(Runnable work){
        final String identity=ApiClient.baseUrl(context)+"\n"+ApiClient.token(context);
        ChatMediaWork.executeCache(()->{scheduledIdentity.set(identity);try{work.run();}finally{scheduledIdentity.remove();}});
    }

    public static synchronized ChatCache get(Context context) {
        if (instance == null) instance = new ChatCache(context.getApplicationContext());
        return instance;
    }

    private ChatCache(Context context) {
        super(context, NAME, null, VERSION);
        this.context = context;
    }

    private String sessionScope(String scope) {
        // A different server or login must never reuse another session's messages.
        try {
            String identity = scheduledIdentity.get();if(identity==null)identity=ApiClient.baseUrl(context) + "\n" + ApiClient.token(context);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(scope).append(":");
            for (byte value : hash) key.append(String.format(java.util.Locale.US, "%02x", value & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (scope TEXT NOT NULL, conversation_id INTEGER NOT NULL, message_id INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope, conversation_id, message_id))");
        db.execSQL("CREATE TABLE hidden_messages (scope TEXT NOT NULL, conversation_id INTEGER NOT NULL, message_id INTEGER NOT NULL, PRIMARY KEY(scope, conversation_id, message_id))");
        createHistoryState(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS hidden_messages (scope TEXT NOT NULL, conversation_id INTEGER NOT NULL, message_id INTEGER NOT NULL, PRIMARY KEY(scope, conversation_id, message_id))");
        if (oldVersion < 3) createHistoryState(db);
    }

    private void createHistoryState(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS history_state (scope TEXT NOT NULL, conversation_id INTEGER NOT NULL, after_id INTEGER NOT NULL, PRIMARY KEY(scope, conversation_id))");
    }

    public synchronized int syncedThrough(String scope, int conversationId) {
        try (Cursor cursor = getReadableDatabase().query("history_state", new String[]{"after_id"}, "scope=? AND conversation_id=?", new String[]{sessionScope(scope), String.valueOf(conversationId)}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized void put(String scope, int conversationId, JSONObject message) {
        put(getWritableDatabase(), sessionScope(scope), conversationId, message);
    }

    /** One transaction for a history page instead of a disk commit per message. */
    public synchronized void putAll(String scope, int conversationId, JSONArray messages) {
        putHistoryPage(scope, conversationId, messages, -1);
    }

    /** Save the REST checkpoint with its page. A live message never advances it. */
    public synchronized void putHistoryPage(String scope, int conversationId, JSONArray messages, int afterId) {
        if (messages.length() == 0) return;
        SQLiteDatabase db = getWritableDatabase();
        String scoped = sessionScope(scope);
        db.beginTransaction();
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message != null) put(db, scoped, conversationId, message);
            }
            if (afterId >= 0) {
                ContentValues checkpoint = new ContentValues();
                checkpoint.put("scope", scoped);
                checkpoint.put("conversation_id", conversationId);
                checkpoint.put("after_id", afterId);
                db.insertWithOnConflict("history_state", null, checkpoint, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void put(SQLiteDatabase db, String scoped, int conversationId, JSONObject message) {
        int id = message.optInt("id");
        if (id <= 0) return;
        ContentValues values = new ContentValues();
        values.put("scope", scoped);
        values.put("conversation_id", conversationId);
        values.put("message_id", id);
        values.put("payload", message.toString());
        db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized JSONArray list(String scope, int conversationId) {
        JSONArray result = new JSONArray();
        ArrayList<JSONObject> newest = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"payload"}, "scope=? AND conversation_id=?", new String[]{sessionScope(scope), String.valueOf(conversationId)}, null, null, "message_id DESC", "50")) {
            while (cursor.moveToNext()) newest.add(new JSONObject(cursor.getString(0)));
        } catch (Exception ignored) { }
        for (int i = newest.size() - 1; i >= 0; i--) result.put(newest.get(i));
        return result;
    }

    public synchronized void clear(String scope, int conversationId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {sessionScope(scope), String.valueOf(conversationId)};
        db.beginTransaction();
        try {
            db.delete("messages", "scope=? AND conversation_id=?", args);
            db.delete("history_state", "scope=? AND conversation_id=?", args);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void remove(String scope, int conversationId, int messageId) {
        getWritableDatabase().delete("messages", "scope=? AND conversation_id=? AND message_id=?", new String[]{sessionScope(scope), String.valueOf(conversationId), String.valueOf(messageId)});
    }

    /** Update this login's group caches so returning to a chat never restores an old remark. */
    public synchronized void updateContactRemark(int peerId, String remark) {
        if (peerId <= 0) return;
        SQLiteDatabase db = getWritableDatabase();
        String scope = sessionScope("group");
        db.beginTransaction();
        try (Cursor cursor = db.query("messages", new String[]{"conversation_id", "message_id", "payload"}, "scope=?", new String[]{scope}, null, null, null)) {
            while (cursor.moveToNext()) {
                try {
                    JSONObject message = new JSONObject(cursor.getString(2));
                    if (!applyContactRemark(message, peerId, remark)) continue;
                    ContentValues values = new ContentValues(); values.put("payload", message.toString());
                    db.update("messages", values, "scope=? AND conversation_id=? AND message_id=?", new String[]{scope, cursor.getString(0), cursor.getString(1)});
                } catch (org.json.JSONException ignored) { }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    static boolean applyContactRemark(JSONObject message, int peerId, String remark) throws org.json.JSONException {
        boolean changed = false;
        if (message.optInt("sender_id") == peerId) {
            String original = message.optString("original_nickname", message.optString("nickname", ""));
            String display = remark.isEmpty() ? original : remark;
            message.put("original_nickname", original).put("nickname", display).put("display_name", display).put("contact_remark", remark);
            changed = true;
        }
        JSONObject reply = message.optJSONObject("reply");
        if (reply != null && reply.optInt("sender_id") == peerId) {
            String original = reply.optString("original_nickname", reply.optString("nickname", ""));
            reply.put("original_nickname", original).put("nickname", remark.isEmpty() ? original : remark);
            changed = true;
        }
        return changed;
    }

    public synchronized void hide(String scope, int conversationId, int messageId) {
        ContentValues values = new ContentValues();
        values.put("scope", sessionScope(scope));
        values.put("conversation_id", conversationId);
        values.put("message_id", messageId);
        getWritableDatabase().insertWithOnConflict("hidden_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        remove(scope, conversationId, messageId);
    }

    public synchronized boolean isHidden(String scope, int conversationId, int messageId) {
        try (Cursor cursor = getReadableDatabase().query("hidden_messages", new String[]{"message_id"}, "scope=? AND conversation_id=? AND message_id=?", new String[]{sessionScope(scope), String.valueOf(conversationId), String.valueOf(messageId)}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    public synchronized java.util.Set<Integer> hiddenIds(String scope, int conversationId) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        try (Cursor cursor = getReadableDatabase().query("hidden_messages", new String[]{"message_id"}, "scope=? AND conversation_id=?", new String[]{sessionScope(scope), String.valueOf(conversationId)}, null, null, null)) {
            while (cursor.moveToNext()) ids.add(cursor.getInt(0));
        }
        return ids;
    }
}
