package com.spx.chat;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import org.json.JSONObject;
import java.util.LinkedHashSet;

/** Bundled effects; historical message rendering never triggers playback. */
final class ChatSoundEffects {
    private final Context app;
    private final LinkedHashSet<String> packets = new LinkedHashSet<>();
    private MediaPlayer packetPlayer;
    private final LinkedHashSet<String> claimedPackets = new LinkedHashSet<>();
    ChatSoundEffects(Context context) { app = context.getApplicationContext(); }
    void packet(JSONObject message) {
        if (message == null || !"red_packet".equals(message.optString("message_type"))) return;
        String key = message.optString("content");
        if (key.isEmpty() || !packets.add(key)) return;
        if (packets.size() > 256) packets.remove(packets.iterator().next());
        if (ChatCall.active() != null && ChatCall.active().hasSession()) return;
        AudioManager manager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null && manager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
        playEffect(R.raw.red_packet);
    }
    /** Only an explicitly requested, server-confirmed positive claim calls this. */
    void claimed(String receipt) {
        if (receipt == null || receipt.isEmpty() || !claimedPackets.add(receipt)) return;
        if (claimedPackets.size() > 256) claimedPackets.remove(claimedPackets.iterator().next());
        if (ChatCall.active() != null && ChatCall.active().hasSession()) return;
        AudioManager manager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null && manager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
        playEffect(R.raw.red_packet_claim);
    }
    private void playEffect(int resource) {
        stop();
        try {
            packetPlayer = player(app, resource, AudioAttributes.USAGE_NOTIFICATION, false);
            if (packetPlayer == null) return;
            packetPlayer.setOnCompletionListener(p -> { if (packetPlayer == p) stop(); });
            packetPlayer.setOnErrorListener((p, what, extra) -> { if (packetPlayer == p) stop(); return true; });
            packetPlayer.start();
        } catch (RuntimeException error) {
            stop();
            android.util.Log.w("ChatSoundEffects", "Bundled sound unavailable", error);
        }
    }
    void stop() {
        MediaPlayer previous = packetPlayer;
        packetPlayer = null;
        if (previous != null) previous.release();
    }
    static MediaPlayer player(Context context, int resource, int usage, boolean loop) {
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        MediaPlayer player = MediaPlayer.create(context, resource, attributes, 0);
        if (player == null) return null;
        player.setLooping(loop); player.setVolume(.65f, .65f);
        player.setOnErrorListener((p, what, extra) -> { p.reset(); return true; });
        return player;
    }
}
