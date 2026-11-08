package com.spx.chat;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/** Minimal API client for the local PHP service. */
public final class ApiClient {
    /** Public web host for share / merchant-console links (deliberately not the API host). */
    static final String WEB_BASE = "https://spx001.top";
    /** Invite links are printed and QR-coded on plain http, so keep that scheme here. */
    static final String INVITE_BASE = "http://spx001.top";
    /** Fallback API host, also used when a configured base URL carries no host. */
    static final String DEFAULT_HOST = "70.39.201.191";
    private static final String DEFAULT_BASE_URL = "https://" + DEFAULT_HOST;
    // 容灾「地址广播」：香港连不上时从这里取最新 API 地址（杭州，纯静态 JSON）
    private static final String DISCOVERY_URL = "https://121.196.246.42:8443/config.json";
    private static volatile long discoveryCheckedAt = 0L;
    private static final int TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final java.util.concurrent.atomic.AtomicLong SESSION_GENERATION = new java.util.concurrent.atomic.AtomicLong();

    static final class SessionStamp {
        final String token, server;
        final long generation;
        SessionStamp(Context context) { token=token(context);server=baseUrl(context);generation=SESSION_GENERATION.get(); }
        boolean valid(Context context) { return generation==SESSION_GENERATION.get() && token.equals(token(context)) && server.equals(baseUrl(context)); }
    }
    private static final String PREFS = "spx_api";
    private static final String TOKEN = "token";
    private static final String BASE_URL = "base_url";
    private static final String NODE_JSON = "api_nodes";
    interface SessionListener { void onSessionExpired(String message); }
    private static SessionListener sessionListener;
    static void setSessionListener(SessionListener listener) { sessionListener=listener; }
    static void removeSessionListener(SessionListener listener) { if(sessionListener==listener)sessionListener=null; }
    static void expireSession(Context context,String rejectedToken,String message) {
        runOnMain(context,()->{
            if(rejectedToken==null||rejectedToken.isEmpty()||!rejectedToken.equals(token(context)))return;
            ChatCall call=ChatCall.active();if(call!=null)call.dispose();
            clearToken(context);
            if(sessionListener!=null)sessionListener.onSessionExpired(message);
        });
    }
    private static void checkSession(Context context,String requestToken,int status,String message) {
        if(status==401)expireSession(context,requestToken,message);
    }
    private static java.util.concurrent.ExecutorService workers(String name,int count,int capacity){
        java.util.concurrent.atomic.AtomicInteger sequence=new java.util.concurrent.atomic.AtomicInteger();
        return new java.util.concurrent.ThreadPoolExecutor(count,count,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(capacity),r->new Thread(r,name+sequence.incrementAndGet()),new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }
    private static final java.util.concurrent.ExecutorService REQUESTS=workers("spx-api-",6,128),AUTH_REQUESTS=workers("spx-auth-",2,8),UPLOADS=workers("spx-upload-",2,24),DOWNLOADS=workers("spx-download-",1,4);

    public static final class RequestHandle {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        private java.util.concurrent.Future<?> task;
        public void cancel(){cancelled=true;java.util.concurrent.Future<?> current=task;if(current!=null)current.cancel(true);HttpURLConnection c=connection;if(c!=null)c.disconnect();}
        public boolean isCancelled(){return cancelled;}
    }

    public interface Callback {
        void onSuccess(Object data);
        void onError(String message);
        default void onHttpError(int status, String message) { onError(message); }
        default void onUploadProgress(int percent, long sentBytes, long totalBytes) { }
    }

    public interface DownloadCallback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onSuccess(File file);
        void onError(String message);
    }

    private ApiClient() {
    }

    public static RequestHandle get(Context context, String action, Map<String, String> params, Callback callback) {
        return request(context, "GET", action, params, null, callback);
    }

    public static void post(Context context, String action, JSONObject body, Callback callback) {
        request(context, "POST", action, null, body, callback);
    }

    public static String actionUrl(Context context, String action, Map<String, String> params) throws Exception {
        StringBuilder url = new StringBuilder(endpoint(context)).append("?action=")
                .append(URLEncoder.encode(action, "UTF-8"));
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                url.append('&').append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                        .append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
        }
        return url.toString();
    }

    public static void download(Context context, String action, Map<String, String> params, File destination, DownloadCallback callback) {
        final String requestUrl;
        try{requestUrl=actionUrl(context,action,params);}catch(Exception e){runOnMain(context,()->callback.onError("更新下载地址无效"));return;}
        try{DOWNLOADS.execute(() -> {
            HttpURLConnection connection = null;
            File partial = new File(destination.getParentFile(), destination.getName() + ".part");
            File metadata=new File(partial.getPath()+".json");
            try {
                String etag="",lastModified="";long offset=0;
                if(partial.isFile()&&metadata.isFile()){
                    try{JSONObject saved=new JSONObject(read(new java.io.FileInputStream(metadata)));
                        if(requestUrl.equals(saved.optString("url"))){etag=saved.optString("etag");lastModified=saved.optString("last_modified");if(!etag.isEmpty()&&!etag.startsWith("W/")||!lastModified.isEmpty())offset=partial.length();}
                    }catch(Exception ignored){}
                }
                connection = openConnection(context, requestUrl);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(120000);
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                connection.setRequestProperty("Accept-Language", language(context));
                connection.setRequestProperty("Accept-Encoding","identity");
                if(offset>0){connection.setRequestProperty("Range","bytes="+offset+"-");connection.setRequestProperty("If-Range",!etag.isEmpty()&&!etag.startsWith("W/")?etag:lastModified);}
                int status = connection.getResponseCode();
                if(status==416){partial.delete();metadata.delete();throw new IllegalStateException("更新文件已变化，请重新下载");}
                if (status < 200 || status >= 300) {
                    String response = read(connection.getErrorStream());
                    try {
                        String message = new JSONObject(response).optString("message", "APK 下载失败（HTTP " + status + "）");
                        throw new IllegalStateException(message);
                    } catch (org.json.JSONException ignored) {
                        throw new IllegalStateException("APK 下载失败（HTTP " + status + "）");
                    }
                }
                boolean append=status==206&&offset>0;
                String responseEtag=connection.getHeaderField("ETag"),responseModified=connection.getHeaderField("Last-Modified");
                long total=connection.getContentLengthLong();
                if(status==206){
                    java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                    if(!append||!range.matches()||Long.parseLong(range.group(1))!=offset||Long.parseLong(range.group(2))<offset||Long.parseLong(range.group(3))<=Long.parseLong(range.group(2))
                            ||(!etag.isEmpty()&&!etag.startsWith("W/")&&!etag.equals(responseEtag))
                            ||(etag.isEmpty()&&!lastModified.isEmpty()&&!lastModified.equals(responseModified))){partial.delete();metadata.delete();throw new IllegalStateException("下载范围或版本不一致，请重新下载");}
                    total=Long.parseLong(range.group(3));
                }else offset=0;
                JSONObject saved=new JSONObject().put("url",requestUrl).put("etag",responseEtag==null?"":responseEtag).put("last_modified",responseModified==null?"":responseModified);
                try(FileOutputStream meta=new FileOutputStream(metadata)){meta.write(saved.toString().getBytes(StandardCharsets.UTF_8));}
                final long expectedTotal=total;
                long downloaded = offset;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial,append)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    int lastPercent = -1;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        downloaded += count;
                        int percent = total > 0 ? (int) Math.min(100, downloaded * 100 / total) : -1;
                        if (percent != lastPercent) {
                            int progress = percent;
                            long received = downloaded;
                            runOnMain(context, () -> callback.onProgress(progress, received, expectedTotal));
                            lastPercent = percent;
                        }
                    }
                    output.getFD().sync();
                }
                if (downloaded <= 0 || (total > 0 && downloaded != total)) throw new IllegalStateException("APK 下载不完整，请重试");
                if (destination.exists() && !destination.delete()) throw new IllegalStateException("无法替换本地 APK 文件");
                if (!partial.renameTo(destination)) throw new IllegalStateException("无法保存已下载 APK");
                metadata.delete();
                runOnMain(context, () -> callback.onSuccess(destination));
            } catch (Exception exception) {
                String message = exception.getMessage() == null ? "APK 下载失败，请检查网络后重试" : exception.getMessage();
                runOnMain(context, () -> callback.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });}catch(java.util.concurrent.RejectedExecutionException busy){runOnMain(context,()->callback.onError("下载任务繁忙，请稍后重试"));}
    }

    private static Callback sessionCallback(Context context,String requestToken,Callback target) {
        final SessionStamp session=new SessionStamp(context);
        return new Callback(){
            private boolean valid(){return requestToken.equals(session.token)&&session.valid(context);}
            public void onSuccess(Object data){if(valid())target.onSuccess(data);}
            public void onError(String error){if(valid())target.onError(error);}
            public void onHttpError(int status,String error){if(valid())target.onHttpError(status,error);}
            public void onUploadProgress(int percent,long sent,long total){if(valid())target.onUploadProgress(percent,sent,total);}
        };
    }

    public static void upload(Context context, String action, Uri uri, Callback callback) {
        uploadCaptured(context,action,uri,endpoint(context),token(context),callback);
    }

    private static final class UploadHttpException extends java.io.IOException {
        final int status;UploadHttpException(int status,String message){super(message);this.status=status;}
    }
    /** Resume from the authenticated server checkpoint, retaining the same client upload ID. */
    public static void uploadLarge(Context context, File file, String uploadId, String mime, Callback receiver) {
        final Callback callback=sessionCallback(context,token(context),receiver);
        final String requestEndpoint=endpoint(context),requestToken=token(context),requestBase=baseUrl(context);
        try{UPLOADS.execute(()->{
            final int chunkSize=1024*1024;final long totalBytes=file.length();
            if(totalBytes<=0||totalBytes>1024L*1024*1024){runOnMain(context,()->callback.onError("附件为空或超过 1 GB"));return;}
            final int chunks=(int)((totalBytes+chunkSize-1)/chunkSize);
            final java.util.concurrent.atomic.AtomicInteger lastPercent=new java.util.concurrent.atomic.AtomicInteger(-1);
            try(java.io.RandomAccessFile input=new java.io.RandomAccessFile(file,"r")){
                if(!requestToken.equals(token(context))||!requestBase.equals(baseUrl(context)))throw new IllegalStateException("账号已变化，上传已停止");
                JSONObject checkpoint=uploadCheckpoint(context,requestEndpoint,requestToken,uploadId,chunks,totalBytes);
                JSONObject result=checkpoint.optJSONObject("result");
                java.util.Set<Integer> received=new java.util.HashSet<>();org.json.JSONArray saved=checkpoint.optJSONArray("received_chunks");
                if(saved!=null)for(int i=0;i<saved.length();i++){int index=saved.optInt(i,-1);if(index>=0&&index<chunks)received.add(index);}
                if(result==null&&received.size()==chunks)received.remove(chunks-1);
                long confirmed=0;for(int index:received)confirmed+=Math.min(chunkSize,totalBytes-(long)index*chunkSize);
                for(int index=0;result==null&&index<chunks;index++){
                    if(received.contains(index))continue;
                    long offset=(long)index*chunkSize,pieceSize=Math.min(chunkSize,totalBytes-offset);
                    for(int attempt=0;attempt<3;attempt++){
                        if(!requestToken.equals(token(context))||!requestBase.equals(baseUrl(context)))throw new IllegalStateException("账号已变化，上传已停止");
                        String boundary="SPX-"+java.util.UUID.randomUUID();
                        HttpURLConnection connection=openConnection(context,requestEndpoint+"?action=upload.message.chunk");
                        try{
                            java.io.ByteArrayOutputStream fields=new java.io.ByteArrayOutputStream();
                            writeField(fields,boundary,"upload_id",uploadId);writeField(fields,boundary,"chunk_index",String.valueOf(index));writeField(fields,boundary,"total_chunks",String.valueOf(chunks));writeField(fields,boundary,"total_bytes",String.valueOf(totalBytes));
                            fields.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"chunk\"; filename=\"part.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            byte[] prefix=fields.toByteArray(),suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
                            connection.setRequestMethod("POST");connection.setConnectTimeout(20000);connection.setReadTimeout(120000);connection.setDoOutput(true);connection.setFixedLengthStreamingMode(prefix.length+pieceSize+suffix.length);
                            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
                            if(!requestToken.isEmpty())connection.setRequestProperty("Authorization","Bearer "+requestToken);
                            try(OutputStream out=connection.getOutputStream()){
                                out.write(prefix);input.seek(offset);long remaining=pieceSize;byte[] buffer=new byte[65536];
                                while(remaining>0){
                                    if(!requestToken.equals(token(context))||!requestBase.equals(baseUrl(context)))throw new IllegalStateException("账号已变化，上传已停止");
                                    int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new java.io.EOFException("附件读取不完整");out.write(buffer,0,n);remaining-=n;
                                    final long sent=confirmed+pieceSize-remaining;int percent=(int)Math.min(99,sent*100/totalBytes);
                                    int previous=lastPercent.get();if(percent>previous&&lastPercent.compareAndSet(previous,percent))runOnMain(context,()->callback.onUploadProgress(percent,sent,totalBytes));
                                }out.write(suffix);
                            }
                            JSONObject envelope=uploadEnvelope(context,connection,requestToken);Object data=envelope.opt("data");
                            if(data instanceof JSONObject&& !((JSONObject)data).optString("url").isEmpty())result=(JSONObject)data;
                            confirmed+=pieceSize;break;
                        }catch(java.io.IOException error){
                            if(attempt==2||(error instanceof UploadHttpException&&!ChatOutbox.transientFailure(((UploadHttpException)error).status,error.getMessage())))throw error;
                            Thread.sleep(500L*(attempt+1));
                        }finally{connection.disconnect();}
                    }
                }
                if(result==null||result.optString("url").isEmpty())throw new IllegalStateException("上传服务未返回附件地址");
                if(!requestToken.equals(token(context))||!requestBase.equals(baseUrl(context)))throw new IllegalStateException("账号已变化，上传已停止");
                final JSONObject uploaded=result;runOnMain(context,()->{callback.onUploadProgress(100,totalBytes,totalBytes);callback.onSuccess(uploaded);});
            }catch(UploadHttpException e){runOnMain(context,()->callback.onHttpError(e.status,e.getMessage()));}
            catch(Exception e){String message=e.getMessage()==null?"大文件上传失败，请重试":e.getMessage();runOnMain(context,()->callback.onError(message));}
        });}catch(java.util.concurrent.RejectedExecutionException busy){runOnMain(context,()->callback.onError("上传任务繁忙，请稍后重试"));}
    }
    private static JSONObject uploadCheckpoint(Context context,String endpoint,String token,String id,int chunks,long bytes)throws Exception{
        HttpURLConnection c=openConnection(context,endpoint+"?action=upload.message.status&upload_id="+URLEncoder.encode(id,"UTF-8")+"&total_chunks="+chunks+"&total_bytes="+bytes);
        try{c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setRequestProperty("Accept","application/json");if(!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
            JSONObject envelope=uploadEnvelope(context,c,token);JSONObject data=envelope.optJSONObject("data");if(data==null)throw new java.io.IOException("续传服务响应异常");return data;
        }finally{c.disconnect();}
    }
    private static JSONObject uploadEnvelope(Context context,HttpURLConnection c,String token)throws Exception{
        int status=c.getResponseCode();String text=read(status>=400?c.getErrorStream():c.getInputStream());JSONObject envelope;
        try{envelope=new JSONObject(text);}catch(Exception e){throw new UploadHttpException(status,"上传服务响应异常（HTTP "+status+"）");}
        String error=envelope.optString("message","文件上传失败（HTTP "+status+"）");checkSession(context,token,status,error);
        if(status>=400||envelope.optInt("code",0)!=0)throw new UploadHttpException(status>=400?status:422,error);
        return envelope;
    }
    private static void writeField(OutputStream out,String boundary,String key,String value)throws java.io.IOException{
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+key+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));
    }

    static void uploadCaptured(Context context,String action,Uri uri,String requestEndpoint,String requestToken,Callback receiver) {
        final Callback callback=sessionCallback(context,requestToken,receiver);
        try{UPLOADS.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String boundary = "SPX-" + System.currentTimeMillis();
                connection = openConnection(context, requestEndpoint + "?action=" + URLEncoder.encode(action, "UTF-8"));
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);
                connection.setChunkedStreamingMode(16 * 1024);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Accept-Language", language(context));
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                String auth = requestToken;
                if (!auth.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + auth);
                String mime = context.getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                String name = "upload";
                if ("content".equals(uri.getScheme())) {
                    try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                            if (index >= 0) name = cursor.getString(index);
                        }
                    }
                } else if (uri.getLastPathSegment() != null) name = uri.getLastPathSegment();
                if ("application/octet-stream".equals(mime) && "file".equals(uri.getScheme())) {
                    int dot = name.lastIndexOf('.');
                    String guessed = dot < 0 ? null : android.webkit.MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
                    if (guessed != null) mime = guessed;
                }
                try (OutputStream out = connection.getOutputStream(); InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("无法读取所选文件");
                    out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "").replace("\r", "").replace("\n", "") + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    long totalBytes = -1;
                    if ("file".equals(uri.getScheme())) totalBytes = new File(uri.getPath()).length();
                    else try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) totalBytes = cursor.getLong(0);
                    } catch (Exception ignored) { }
                    long sentBytes = 0, lastProgress = 0;
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count); sentBytes += count;
                        long now = System.currentTimeMillis();
                        if (now - lastProgress >= 150) {
                            int percent = totalBytes > 0 ? (int)Math.min(99, sentBytes * 100 / totalBytes) : -1;
                            long current = sentBytes, total = totalBytes;
                            runOnMain(context, () -> callback.onUploadProgress(percent, current, total));
                            lastProgress = now;
                        }
                    }
                    long current = sentBytes, total = totalBytes;
                    runOnMain(context, () -> callback.onUploadProgress(100, current, total));
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                String responseText = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                JSONObject response;
                try { response = new JSONObject(responseText); }
                    catch (org.json.JSONException e) {
                    throw new java.io.IOException(status == 413 ? "文件超过服务器上传限制，请选择较小的文件"
                            : "上传服务返回异常（HTTP " + status + "），请稍后重试");
                }
                if (status >= 400 || response.optInt("code", 0) != 0) {
                    String message = response.optString("message", "上传失败");
                    checkSession(context,requestToken,status,message);
                    runOnMain(context, () -> callback.onError(message));
                } else {
                    Object data = response.opt("data");
                    runOnMain(context, () -> callback.onSuccess(data == JSONObject.NULL ? null : data));
                }
            } catch (Exception exception) {
                String message = exception.getMessage() == null ? "文件上传失败" : exception.getMessage();
                runOnMain(context, () -> callback.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });}catch(java.util.concurrent.RejectedExecutionException busy){runOnMain(context,()->callback.onError("上传任务繁忙，请稍后重试"));}
    }

    public static void saveToken(Context context, String token) {
        if (!token.equals(token(context))) { SESSION_GENERATION.incrementAndGet(); MallCatalogCache.clear(); }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply();
    }

    public static void clearToken(Context context) {
        SESSION_GENERATION.incrementAndGet();
        MallCatalogCache.clear();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN).apply();
    }

    public static String token(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, "");
    }

    public static String baseUrl(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BASE_URL, DEFAULT_BASE_URL);
        if (value == null || value.trim().isEmpty()) return DEFAULT_BASE_URL;
        return value.trim().replaceAll("/+$", "");
    }

    public static String connectionBaseUrl(Context context) {
        return chooseBaseUrl(context);
    }

    public static void saveBaseUrl(Context context, String value) {
        if (!value.trim().equals(baseUrl(context))) SESSION_GENERATION.incrementAndGet();
        MallCatalogCache.clear();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(BASE_URL, value.trim()).remove(NODE_JSON).apply();
    }

    public static void refreshConfig(Context context, Runnable completed) {
        // Bootstrap from the known-good endpoint so stale node caches cannot block login.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(NODE_JSON).remove(BASE_URL).apply();
        get(context, "app.config", null, new Callback() {
            @Override public void onSuccess(Object data) {
                applyConfig(context, data);
                if (completed != null) completed.run();
            }
            @Override public void onError(String message) { if (completed != null) completed.run(); }
        });
    }

    private static void applyConfig(Context context, Object data) {
        if (!(data instanceof JSONObject)) return;
        JSONArray nodes = ((JSONObject) data).optJSONArray("nodes");
        if (nodes == null || nodes.length() == 0) return;
        JSONArray valid = new JSONArray();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || !node.optString("url", "").matches("(?i)^https?://[^/\\s]+(?:/[^\\s]*)?$")) continue;
            valid.put(node);
        }
        if (valid.length() > 0) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(NODE_JSON, valid.toString()).apply();
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(NODE_JSON).apply();
        }
    }

    public static String secureUrl(String value) {
        return value == null ? "" : value;
    }

    static String endpoint(Context context) {
        String value = chooseBaseUrl(context).trim().replaceAll("/+$", "");
        if (value.endsWith(".php")) return value;
        if (value.endsWith("/spxchat")) return value + "/api.php";
        return value + "/spxchat/api.php";
    }

    private static String chooseBaseUrl(Context context) {
        String nodesText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NODE_JSON, "");
        if (!nodesText.isEmpty()) {
            try {
                JSONArray nodes = new JSONArray(nodesText);
                int total = 0;
                for (int i = 0; i < nodes.length(); i++) if (nodes.optJSONObject(i) != null && nodes.optJSONObject(i).optInt("enabled", 1) == 1) total += Math.max(1, nodes.optJSONObject(i).optInt("weight", 1));
                if (total > 0) {
                    int selected = new java.util.Random().nextInt(total);
                    for (int i = 0; i < nodes.length(); i++) {
                        JSONObject node = nodes.optJSONObject(i);
                        if (node == null || node.optInt("enabled", 1) != 1) continue;
                        selected -= Math.max(1, node.optInt("weight", 1));
                        if (selected < 0) return node.optString("url", DEFAULT_BASE_URL);
                    }
                }
            } catch (Exception ignored) { }
        }
        return baseUrl(context);
    }

    static HttpURLConnection openConnection(Context context, String url) throws Exception {
        URLConnection raw = new URL(secureUrl(url)).openConnection();
        if (!(raw instanceof HttpURLConnection)) throw new IllegalStateException("不支持的网络协议");
        HttpURLConnection connection = (HttpURLConnection) raw;
        if (connection instanceof HttpsURLConnection) {
            SSLHelper.init(context.getApplicationContext());
            ((HttpsURLConnection) connection).setSSLSocketFactory(SSLHelper.getSSLContext().getSocketFactory());
        }
        return connection;
    }

    /**
     * 向杭州地址广播要一次最新节点。成功返回 true，并已把节点写入本地。
     * 最多每分钟请求一次。
     */
    static boolean refreshFromDiscovery(Context context) {
        long now = System.currentTimeMillis();
        if (now - discoveryCheckedAt < 60000L) return false;
        discoveryCheckedAt = now;
        HttpURLConnection connection = null;
        try {
            connection = openConnection(context, DISCOVERY_URL);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) return false;
            JSONObject payload = new JSONObject(read(connection.getInputStream()));
            JSONArray source = payload.optJSONArray("nodes");
            if (source == null) return false;
            JSONArray valid = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                JSONObject node = source.optJSONObject(i);
                if (node == null) continue;
                String url = node.optString("url", "").trim().replaceAll("/+$", "");
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                JSONObject clean = new JSONObject();
                clean.put("url", url);
                clean.put("weight", Math.max(1, Math.min(1000, node.optInt("weight", 1))));
                clean.put("enabled", node.optInt("enabled", 1) == 1 ? 1 : 0);
                valid.put(clean);
            }
            if (valid.length() == 0) return false;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(NODE_JSON, valid.toString())
                    .remove(BASE_URL)
                    .apply();
            android.util.Log.i("SpxNetwork", "discovery switched to " + valid.optJSONObject(0).optString("url"));
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean mayRetryRead(String action) {
        return java.util.Arrays.asList("me", "chats", "groups", "contacts", "contacts.search", "user.profile", "wallet", "wallet.binding", "wallet.transactions", "staking", "staking.plans").contains(action);
    }

    static String networkError(Context context, Exception error) {
        String key = error instanceof java.net.SocketTimeoutException ? "network_timeout_retry"
                : error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException || error instanceof java.net.NoRouteToHostException ? "network_unreachable"
                : error instanceof javax.net.ssl.SSLException ? "network_secure_failed"
                : error instanceof org.json.JSONException ? "network_response_invalid" : "network_interrupted_retry";
        return I18n.t(context, language(context), key);
    }

    private static String httpError(Context context, int status) {
        return I18n.t(context, language(context), status == 429 ? "network_rate_limited" : status >= 500 ? "network_service_busy" : "network_http_failed", status);
    }

    private static RequestHandle request(Context context, String method, String action, Map<String, String> params, JSONObject body, Callback callback) {
        final SessionStamp session = new SessionStamp(context);
        final String requestEndpoint=endpoint(context),requestToken=session.token;
        final String requestBody=body==null?null:body.toString();
        final Map<String,String> requestParams=params==null?null:new java.util.HashMap<>(params);
        final RequestHandle handle=new RequestHandle();
        final Callback guarded=new Callback(){
            private boolean valid(){return !handle.cancelled && session.valid(context);}
            public void onSuccess(Object data){if(valid())callback.onSuccess(data);}
            public void onError(String message){if(valid())callback.onError(message);}
            public void onHttpError(int status,String message){if(valid())callback.onHttpError(status,message);}
        };
        // User-initiated authentication must not queue behind page refreshes.
        java.util.concurrent.ExecutorService executor=action.startsWith("auth.")?AUTH_REQUESTS:REQUESTS;
        try{handle.task=executor.submit(() -> {
            int attempts = "GET".equals(method) && mayRetryRead(action) ? 2 : 1;
            for(int attempt=0;attempt<attempts;attempt++) {
                HttpURLConnection connection = null;
                try {
                    if(handle.cancelled || !session.valid(context))return;
                    StringBuilder urlBuilder = new StringBuilder(attempt == 0 ? requestEndpoint : endpoint(context)).append("?action=")
                            .append(URLEncoder.encode(action, "UTF-8"));
                    if (requestParams != null) for (Map.Entry<String, String> entry : requestParams.entrySet())
                        urlBuilder.append('&').append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                    connection = openConnection(context, urlBuilder.toString());
                    handle.connection=connection;if(handle.cancelled)return;
                    connection.setRequestMethod(method);
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Accept-Language", language(context));
                    if (!requestToken.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + requestToken);
                    if (requestBody != null) {
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
                        connection.setFixedLengthStreamingMode(payload.length);
                        try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
                    }
                    int status = connection.getResponseCode();
                    if(attempt+1<attempts && (status==502 || status==503 || status==504)) { connection.disconnect();handle.connection=null;Thread.sleep(350);continue; }
                    String responseText = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                    JSONObject response;
                    try { response = new JSONObject(responseText); }
                    catch(org.json.JSONException invalid) {
                        if(status>=400){String message=httpError(context,status);checkSession(context,requestToken,status,message);runOnMain(context,()->guarded.onHttpError(status,message));return;}
                        throw invalid;
                    }
                    if(!response.has("code"))throw new org.json.JSONException("Missing response code");
                    if (status >= 400 || response.optInt("code", -1) != 0) {
                        String message = response.optString("message", httpError(context,status));
                        if(!action.startsWith("auth."))checkSession(context,requestToken,status,message);
                        runOnMain(context, () -> guarded.onHttpError(status, message));return;
                    }
                    Object data = response.opt("data");
                    if ("POST".equals(method)) {
                        if(action.startsWith("merchant.") || action.startsWith("orders."))MallCatalogCache.clear();
                        AccountPageCache.invalidateForMutation(context, action);
                    }
                    runOnMain(context, () -> guarded.onSuccess(data == JSONObject.NULL ? null : data));return;
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();return;
                } catch (Exception exception) {
                    if(handle.cancelled || !session.valid(context))return;
                    if(attempt+1<attempts && exception instanceof java.io.IOException && !(exception instanceof javax.net.ssl.SSLException)) {
                        try { Thread.sleep(350); } catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
                        continue;
                    }
                    // 网络层失败：先向杭州地址广播要一次最新地址，拿到就立刻重试
                    boolean networkLevel = exception instanceof java.io.IOException && !(exception instanceof javax.net.ssl.SSLException);
                    if ("GET".equals(method) && networkLevel && refreshFromDiscovery(context)) {
                        attempts = attempt + 2;
                        continue;
                    }
                    android.util.Log.w("SpxNetwork", action+": "+exception.getClass().getSimpleName());
                    runOnMain(context, () -> guarded.onError(networkError(context,exception)));return;
                } finally { handle.connection=null;if(connection!=null)connection.disconnect(); }
            }
        });}catch(java.util.concurrent.RejectedExecutionException busy){runOnMain(context,()->guarded.onError(I18n.t(context,language(context),"network_tasks_busy")));}
        return handle;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "{}";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static void runOnMain(Context context, Runnable runnable) {
        android.os.Handler handler = new android.os.Handler(context.getMainLooper());
        handler.post(runnable);
    }

    static String language(Context context) {
        String value = context.getSharedPreferences("spx_profile", Context.MODE_PRIVATE).getString("language", "zh-CN");
        if ("en".equals(value)) return value;
        return "zh-CN";
    }
}
