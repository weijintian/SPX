package com.spx.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-owned APK download. Closing an Activity or page must not lose the job. */
final class AppUpdateDownload {
    enum Stage { IDLE, DOWNLOADING, VERIFYING, READY, ERROR }
    interface Listener { void changed(); }
    private static AppUpdateDownload instance;
    static synchronized AppUpdateDownload get(Context context) {
        if (instance == null) instance = new AppUpdateDownload(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    Stage stage = Stage.IDLE;
    JSONObject release;
    File file;
    int percent;
    long received, total;
    String error = "";
    String errorKey = "";
    private String server = "";
    private AppUpdateDownload(Context context) { this.context = context; }
    void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); listener.changed(); }
    void removeListener(Listener listener) { listeners.remove(listener); }
    boolean busy() { return stage == Stage.DOWNLOADING || stage == Stage.VERIFYING; }
    boolean matches(JSONObject candidate) {
        return release != null && candidate != null && server.equals(ApiClient.baseUrl(context))
                && release.optInt("id") == candidate.optInt("id")
                && release.optInt("version_code") == candidate.optInt("version_code")
                && release.optString("apk_sha256").equalsIgnoreCase(candidate.optString("apk_sha256"));
    }
    void start(JSONObject candidate) {
        if (busy()) { changed(); return; }
        String hash = candidate.optString("apk_sha256", "").toLowerCase(Locale.ROOT);
        if (candidate.optInt("id") <= 0 || candidate.optInt("version_code") <= 0 || !hash.matches("[a-f0-9]{64}")) {
            errorKey = "update_info_invalid"; error = ""; stage = Stage.ERROR; changed(); return;
        }
        try { release = new JSONObject(candidate.toString()); }
        catch (Exception invalid) { fail("update_info_invalid", ""); return; }
        server = ApiClient.baseUrl(context);
        error = ""; errorKey = ""; percent = 0; received = 0; total = candidate.optLong("apk_size", 0);
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) { fail("update_directory_failed", ""); return; }
        // Identity includes both the release digest and server. Never append into a different APK.
        String identity = Integer.toHexString(server.hashCode()) + "-" + release.optInt("version_code") + "-" + hash;
        file = new File(directory, "spxchat-" + identity + ".apk");
        if (file.isFile()) verify(file, hash, true);
        else download(hash);
    }
    private void download(String hash) {
        stage = Stage.DOWNLOADING; changed();
        // 优先用服务端下发的候选地址（OSS/CDN），失败时自动降级到下一个，最后走接口代理
        JSONArray urls = release == null ? null : release.optJSONArray("apk_urls");
        if (urls != null && urls.length() > 0) { downloadFrom(urls, 0, hash); return; }
        downloadViaApi(hash);
    }

    private void downloadFrom(final JSONArray urls, final int index, final String hash) {
        if (index >= urls.length()) { downloadViaApi(hash); return; }
        final String url = urls.optString(index, "");
        if (url.isEmpty()) { downloadFrom(urls, index + 1, hash); return; }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = ApiClient.openConnection(context, url);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(180000);
                connection.setInstanceFollowRedirects(true);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
                long length = connection.getContentLengthLong();
                File partial = new File(file.getAbsolutePath() + ".part");
                try (java.io.InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[65536];
                    long downloaded = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        downloaded += count;
                        final int value = length > 0 ? (int) Math.min(100, downloaded * 100 / length) : 0;
                        final long receivedNow = downloaded, totalNow = length;
                        main.post(() -> { percent = value; received = receivedNow; total = totalNow; changed(); });
                    }
                    output.getFD().sync();
                }
                if (file.exists() && !file.delete()) throw new IllegalStateException("cannot replace cached apk");
                if (!partial.renameTo(file)) throw new IllegalStateException("cannot save apk");
                main.post(() -> verify(file, hash, false));
            } catch (Exception error) {
                if (file != null) new File(file.getAbsolutePath() + ".part").delete();
                main.post(() -> downloadFrom(urls, index + 1, hash));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "apk-download").start();
    }

    private void downloadViaApi(String hash) {
        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(release.optInt("id")));
        ApiClient.download(context, "app.update.download", params, file, new ApiClient.DownloadCallback() {
            @Override public void onProgress(int value, long downloaded, long length) {
                percent = value; received = downloaded; total = length; changed();
            }
            @Override public void onSuccess(File downloaded) { verify(downloaded, hash, false); }
            @Override public void onError(String message) { fail("", message); }
        });
    }
    private void verify(File downloaded, String expected, boolean recoverCachedFile) {
        stage = Stage.VERIFYING; changed();
        verifier.execute(() -> {
            boolean valid = expected.equals(sha256(downloaded));
            main.post(() -> {
                if (valid) { file = downloaded; stage = Stage.READY; percent = 100; received = downloaded.length(); total = received; changed(); }
                else if (downloaded.exists() && !downloaded.delete()) fail("apk_verification_failed", "");
                else if (recoverCachedFile) download(expected);
                else fail("apk_verification_failed", "");
            });
        });
    }
    private void fail(String key, String message) { errorKey = key; error = message == null ? "" : message; stage = Stage.ERROR; changed(); }
    private void changed() { for (Listener listener : new ArrayList<>(listeners)) listener.changed(); }
    private static String sha256(File downloaded) {
        try (InputStream input = new FileInputStream(downloaded)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception invalid) { return ""; }
    }
}
