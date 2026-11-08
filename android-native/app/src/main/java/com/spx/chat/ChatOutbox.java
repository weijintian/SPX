package com.spx.chat;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable, account-scoped sends. All queue I/O and state transitions share one worker. */
final class ChatOutbox extends SQLiteOpenHelper {
    interface Listener {void changed();void sent(JSONObject task,JSONObject message);}
    private static final ExecutorService STORE=Executors.newSingleThreadExecutor(r->new Thread(r,"spx-outbox-store"));
    private static final ExecutorService STAGING=Executors.newSingleThreadExecutor(r->new Thread(r,"spx-outbox-copy"));
    private final Context context; private final String account,token,server; private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,JSONObject> unsaved=new LinkedHashMap<>();
    private volatile String snapshot="[]"; private volatile boolean stopped;
    private boolean busy,uploadBusy; private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable wake=()->pump();
    ChatOutbox(Context context,int userId,Listener listener){
        super(context.getApplicationContext(),"spx_chat_outbox.db",null,3);
        this.context=context.getApplicationContext();server=ApiClient.baseUrl(context);account=server+":"+userId;token=ApiClient.token(context);this.listener=listener;
        store(()->{
            SQLiteDatabase db=getWritableDatabase();
            db.execSQL("UPDATE outbox SET state='queued',error='',retry_at=0,attempts=attempts+1 WHERE account=? AND state IN ('sending','uploading') AND attempts<3",new Object[]{account});
            db.execSQL("UPDATE outbox SET state='failed',error='发送中断，点击重试' WHERE account=? AND state IN ('sending','uploading','staging')",new Object[]{account});
            changed();pumpStored();
        });
        try{
            connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network network){pump();}};
            if(connectivity!=null){if(android.os.Build.VERSION.SDK_INT>=24)connectivity.registerDefaultNetworkCallback(networkCallback);
                else connectivity.registerNetworkCallback(new android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback);}
        }catch(Exception ignored){networkCallback=null;}
    }
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE outbox (sequence INTEGER PRIMARY KEY AUTOINCREMENT,account TEXT NOT NULL,client_id TEXT NOT NULL,chat_id INTEGER NOT NULL,group_id INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,error TEXT,progress INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,UNIQUE(account,client_id))");}
    public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<2)db.execSQL("ALTER TABLE outbox ADD COLUMN progress INTEGER NOT NULL DEFAULT 0");
        if(oldVersion<3){db.execSQL("ALTER TABLE outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE outbox ADD COLUMN retry_at INTEGER NOT NULL DEFAULT 0");}
    }
    boolean belongsTo(int uid){return !stopped&&account.equals(ApiClient.baseUrl(context)+":"+uid)&&sessionValid();}
    private boolean sessionValid(){return token.equals(ApiClient.token(context))&&server.equals(ApiClient.baseUrl(context));}
    private void store(Runnable action){STORE.execute(()->{if(stopped)return;try{action.run();}catch(Exception e){android.util.Log.e("SPXOutbox","Queue operation failed",e);}});}
    private void ui(Runnable action){main.post(()->{if(!stopped&&sessionValid())action.run();});}
    private JSONArray query(String where,String[] args){
        JSONArray result=new JSONArray();try(Cursor c=getReadableDatabase().query("outbox",null,where,args,null,null,"sequence ASC")){
            while(c.moveToNext()){
                JSONObject task=new JSONObject(c.getString(c.getColumnIndexOrThrow("payload")));
                task.put("chat_id",c.getInt(c.getColumnIndexOrThrow("chat_id"))).put("group_id",c.getInt(c.getColumnIndexOrThrow("group_id")))
                    .put("local_state",c.getString(c.getColumnIndexOrThrow("state"))).put("local_error",c.getString(c.getColumnIndexOrThrow("error")))
                    .put("local_progress",c.getInt(c.getColumnIndexOrThrow("progress"))).put("local_attempts",c.getInt(c.getColumnIndexOrThrow("attempts")))
                    .put("local_retry_at",c.getLong(c.getColumnIndexOrThrow("retry_at")));result.put(task);
            }
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}return result;
    }
    private void changed(){
        JSONArray all;
        try{all=query("account=?",new String[]{account});}
        catch(Exception e){android.util.Log.e("SPXOutbox","Snapshot unavailable",e);try{all=new JSONArray(snapshot);}catch(Exception ignored){all=new JSONArray();}}
        JSONArray merged=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject task=all.optJSONObject(i);if(task!=null&&!unsaved.containsKey(task.optString("client_message_id")))merged.put(task);}
        for(JSONObject task:unsaved.values())merged.put(task);snapshot=merged.toString();
        ui(()->listener.changed());
    }
    JSONArray list(int chat,int group){JSONArray result=new JSONArray();try{JSONArray all=new JSONArray(snapshot);for(int i=0;i<all.length();i++){JSONObject task=all.optJSONObject(i);if(task!=null&&task.optInt("chat_id")==chat&&task.optInt("group_id")==group)result.put(task);}}catch(Exception ignored){}return result;}
    private void validate(int chat,int group){if(stopped||!sessionValid()||(chat<=0&&group<=0))throw new IllegalStateException("会话或账号已变化");}
    String enqueue(int chat,int group,JSONObject body){
        validate(chat,group);final JSONObject task;final String client;
        try{task=new JSONObject(body.toString());String id=task.optString("client_message_id");client=id.isEmpty()?UUID.randomUUID().toString():id;task.put("client_message_id",client).put("chat_id",chat).put("group_id",group);}
        catch(Exception e){throw new IllegalArgumentException(e);}
        STORE.execute(()->{insert(task,"queued");changed();pumpStored();});return client;
    }
    private boolean insert(JSONObject task,String state){
        String client=task.optString("client_message_id");ContentValues v=new ContentValues();v.put("account",account);v.put("client_id",client);v.put("chat_id",task.optInt("chat_id"));v.put("group_id",task.optInt("group_id"));v.put("payload",task.toString());v.put("state",state);
        try{getWritableDatabase().insertWithOnConflict("outbox",null,v,SQLiteDatabase.CONFLICT_IGNORE);unsaved.remove(client);return true;}
        catch(Exception e){try{task.put("local_state","failed").put("local_error","本地存储失败，请释放空间后重试；请勿退出应用");}catch(Exception ignored){}unsaved.put(client,task);return false;}
    }
    void stageMedia(int chat,int group,Uri uri,String type,String content){
        validate(chat,group);final JSONObject body;final String client=UUID.randomUUID().toString();
        try{body=new JSONObject().put("client_message_id",client).put("chat_id",chat).put("group_id",group).put("content",content).put("message_type",type).put("local_uri",uri.toString());}
        catch(Exception e){throw new IllegalStateException(e);}
        STORE.execute(()->{boolean saved=insert(body,"staging");changed();if(saved&&!stopped&&sessionValid())copyMedia(client,chat,group,uri,body);});
    }
    private void copyMedia(String client,int chat,int group,Uri uri,JSONObject body){
        final String type=body.optString("message_type","image");
        STAGING.execute(()->{File file=null;try{String mime=context.getContentResolver().getType(uri);String displayName=uri.getLastPathSegment();long total=0;
            try(Cursor cursor=context.getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){displayName=cursor.getString(0);if(!cursor.isNull(1))total=cursor.getLong(1);}}catch(Exception ignored){}
            if(displayName==null||displayName.trim().isEmpty())displayName="附件";
            String ext=android.webkit.MimeTypeMap.getFileExtensionFromUrl(displayName.replace(" ","_"));
            if(ext.isEmpty()&&mime!=null)ext=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if(ext==null||!ext.matches("[A-Za-z0-9]{1,10}"))ext="image".equals(type)?"jpg":"video".equals(type)?"mp4":"voice".equals(type)?"3gp":"bin";
            ext=ext.toLowerCase(java.util.Locale.ROOT);
            if(mime==null||mime.equals("application/octet-stream")){String inferred=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);mime=inferred==null?"application/octet-stream":inferred;}
            body.put("local_name",displayName).put("local_mime",mime);
            if(total>1024L*1024*1024)throw new IOException("附件超过 1 GB，暂不支持发送");
            File folder=new File(context.getFilesDir(),"chat-outbox/"+Integer.toHexString(account.hashCode()));if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法保存附件");file=new File(folder,client+"."+ext);
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(file)){if(in==null)throw new IOException("无法读取附件");byte[] buffer=new byte[65536];int n;long size=0;while((n=in.read(buffer))!=-1){if(stopped||!sessionValid())throw new IOException("账号已变化，请重新选择附件");size+=n;if(size>1024L*1024*1024)throw new IOException("附件超过 1 GB，暂不支持发送");output.write(buffer,0,n);}if(size==0)throw new IOException("附件为空");body.put("local_size",size);}
            if("voice".equals(type)&&"file".equals(uri.getScheme())){File source=new File(uri.getPath());if(source.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator+"voice_"))source.delete();}
            final File staged=file;try{body.put("local_file",staged.getAbsolutePath()).remove("local_uri");}catch(Exception ignored){}
            store(()->{if(stopped||!sessionValid()){staged.delete();return;}ContentValues update=new ContentValues();update.put("payload",body.toString());update.put("state","queued");getWritableDatabase().update("outbox",update,"account=? AND client_id=?",new String[]{account,client});changed();pump();});
        }catch(Exception e){if(file!=null)file.delete();String error=e.getMessage();store(()->{state(client,"failed",error==null?"附件读取失败":error);if(!stopped)changed();});}});
    }
    static File mediaCacheFile(Context context,String account,String url,String type){
        String segment=Uri.parse(url).getLastPathSegment();
        String ext=segment!=null&&segment.matches(".*\\.[A-Za-z0-9]{1,10}")?segment.substring(segment.lastIndexOf('.')):"video".equals(type)?".mp4":"image".equals(type)?".jpg":".bin";
        File folder=new File(context.getFilesDir(),"media-cache/chat");
        return new File(folder,Integer.toHexString((account+":"+url).hashCode())+ext);
    }
    private void cleanup(JSONObject task){
        if(task==null)return;String p=task.optString("local_file","");File folder=new File(context.getFilesDir(),"chat-outbox");
        try{
            if(p.isEmpty()||!new File(p).getCanonicalPath().startsWith(folder.getCanonicalPath()+File.separator))return;
            File source=new File(p);String type=task.optString("message_type");String url=task.optString("media_url","");
            if(!url.isEmpty()&&source.isFile()){
                File cache=mediaCacheFile(context,account,url,type);try(ChatMediaFiles.Lease lease=ChatMediaFiles.protect(cache)){File dir=cache.getParentFile();
                if(!dir.isDirectory()&&!dir.mkdirs())return;
                if(cache.isFile()&&cache.length()==source.length()){source.delete();return;}
                if(!source.renameTo(cache))ChatMediaWork.executeCache(()->{try(ChatMediaFiles.Lease seedLease=ChatMediaFiles.protect(cache)){
                    File partial=new File(cache.getAbsolutePath()+".seed");
                    try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(partial)){byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))!=-1)out.write(bytes,0,count);}
                    if(partial.renameTo(cache))source.delete();else partial.delete();
                }catch(IOException ignored){}});}
            }else source.delete();
        }catch(IOException ignored){}
    }

    void retry(String client){store(()->{
        if(!sessionValid())return;
        JSONObject task=unsaved.get(client);
        if(task!=null){if(!insert(task,"failed")){changed();return;}}
        JSONArray rows=query("account=? AND client_id=? AND state='failed'",new String[]{account,client});if(rows.length()==0)return;task=rows.optJSONObject(0);
        ContentValues v=new ContentValues();v.put("state","queued");v.put("error","");v.put("attempts",0);v.put("retry_at",0);v.put("progress",0);
        if(!task.optString("local_uri").isEmpty()&&task.optString("local_file").isEmpty()){v.put("state","staging");getWritableDatabase().update("outbox",v,"account=? AND client_id=?",new String[]{account,client});changed();copyMedia(client,task.optInt("chat_id"),task.optInt("group_id"),Uri.parse(task.optString("local_uri")),task);}
        else{getWritableDatabase().update("outbox",v,"account=? AND client_id=?",new String[]{account,client});changed();pumpStored();}
    });}
    void discard(String client){store(()->{unsaved.remove(client);JSONArray rows=query("account=? AND client_id=? AND state='failed'",new String[]{account,client});if(rows.length()>0){cleanup(rows.optJSONObject(0));getWritableDatabase().delete("outbox","account=? AND client_id=? AND state='failed'",new String[]{account,client});}changed();});}
    void acknowledge(String client){if(client!=null&&!client.isEmpty())store(()->{ackStored(client);changed();});}
    private boolean ackStored(String client){JSONArray rows=query("account=? AND client_id=?",new String[]{account,client});if(rows.length()==0)return false;cleanup(rows.optJSONObject(0));getWritableDatabase().delete("outbox","account=? AND client_id=?",new String[]{account,client});return true;}
    void stop(){stopped=true;main.removeCallbacks(wake);if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}STORE.execute(()->close());}
    private void state(String client,String state,String error){ContentValues v=new ContentValues();v.put("state",state);v.put("error",error);getWritableDatabase().update("outbox",v,"account=? AND client_id=?",new String[]{account,client});}
    void pump(){store(this::pumpStored);}
    private void pumpStored(){
        if(stopped||token.isEmpty()||!sessionValid())return;
        JSONArray queued=query("account=? AND state='queued'",new String[]{account});JSONObject send=null,upload=null;long now=System.currentTimeMillis(),next=Long.MAX_VALUE;
        for(int i=0;i<queued.length();i++){JSONObject task=queued.optJSONObject(i);if(task==null)continue;long at=task.optLong("local_retry_at");if(at>now){next=Math.min(next,at);continue;}
            if(!task.optString("local_file").isEmpty()&&task.optString("media_url").isEmpty()){if(upload==null)upload=task;}else if(send==null)send=task;}
        main.removeCallbacks(wake);if(next!=Long.MAX_VALUE)main.postDelayed(wake,Math.max(1,next-now));
        if(!uploadBusy&&upload!=null)upload(upload);if(!busy&&send!=null)send(send);
    }
    private void progress(String client,int percent){
        ContentValues v=new ContentValues();v.put("progress",Math.max(0,Math.min(100,percent)));
        if(getWritableDatabase().update("outbox",v,"account=? AND client_id=? AND state='uploading' AND progress<?",new String[]{account,client,String.valueOf(percent)})>0)changed();
    }
    static boolean transientFailure(int status,String error){
        if(status>0)return status==408||status==429||status>=500;
        String text=error==null?"":error.toLowerCase(Locale.ROOT);
        return text.contains("timeout")||text.contains("timed out")||text.contains("connection")||text.contains("network")||text.contains("reset")||text.contains("unable to resolve")||text.contains("超时")||text.contains("网络");
    }
    private void failed(String client,String error,int status){
        if(!sessionValid())return;
        JSONArray rows=query("account=? AND client_id=?",new String[]{account,client});if(rows.length()==0)return;
        int attempts=rows.optJSONObject(0).optInt("local_attempts");boolean retry=attempts<3&&transientFailure(status,error);
        ContentValues v=new ContentValues();v.put("state",retry?"queued":"failed");v.put("error",error==null?"发送失败":error);
        if(retry){v.put("attempts",attempts+1);v.put("retry_at",System.currentTimeMillis()+(2000L<<attempts));}
        getWritableDatabase().update("outbox",v,"account=? AND client_id=?",new String[]{account,client});changed();pumpStored();
    }
    private ApiClient.Callback onStore(ApiClient.Callback callback){return new ApiClient.Callback(){
        public void onSuccess(Object value){store(()->callback.onSuccess(value));}
        public void onError(String error){store(()->callback.onError(error));}
        public void onHttpError(int status,String error){store(()->callback.onHttpError(status,error));}
        public void onUploadProgress(int percent,long sent,long total){store(()->callback.onUploadProgress(percent,sent,total));}
    };}
    private void upload(JSONObject task){
        String client=task.optString("client_message_id");uploadBusy=true;state(client,"uploading","");changed();
        ApiClient.Callback callback=onStore(new ApiClient.Callback(){
            public void onUploadProgress(int percent,long sent,long total){if(sessionValid()&&percent>=0)progress(client,percent);}
            public void onSuccess(Object value){
                uploadBusy=false;if(!sessionValid())return;
                if(!(value instanceof JSONObject)||((JSONObject)value).optString("url").isEmpty()){failed(client,"上传未返回附件地址",0);return;}
                try{JSONObject uploaded=(JSONObject)value;task.put("media_url",uploaded.optString("url"));
                    if(!"file".equals(task.optString("message_type")))task.put("message_type",uploaded.optString("message_type",task.optString("message_type")));
                    else task.put("content","SPXFILE:"+new JSONObject().put("name",task.optString("local_name","文件")).put("size",task.optLong("local_size")).put("mime",uploaded.optString("mime",task.optString("local_mime"))).toString());
                    ContentValues v=new ContentValues();v.put("payload",task.toString());v.put("state","queued");v.put("attempts",0);v.put("retry_at",0);getWritableDatabase().update("outbox",v,"account=? AND client_id=?",new String[]{account,client});changed();pumpStored();
                }catch(Exception e){failed(client,"附件状态保存失败",0);}
            }
            public void onError(String error){uploadBusy=false;failed(client,error,0);}
            public void onHttpError(int status,String error){uploadBusy=false;failed(client,error,status);}
        });
        File local=new File(task.optString("local_file"));
        if(!local.isFile()||local.length()==0){uploadBusy=false;failed(client,"本地附件不存在，请重新选择",0);return;}
        if(local.length()>1024L*1024)ApiClient.uploadLarge(context,local,client,task.optString("local_mime","application/octet-stream"),callback);
        else ApiClient.upload(context,"upload.message",Uri.fromFile(local),callback);
    }
    private void send(JSONObject task){
        String client=task.optString("client_message_id");busy=true;state(client,"sending","");changed();
        JSONObject wire;try{wire=new JSONObject(task.toString());List<String> remove=new ArrayList<>();Iterator<String> keys=wire.keys();while(keys.hasNext()){String key=keys.next();if(key.startsWith("local_")||key.equals("reply"))remove.add(key);}for(String key:remove)wire.remove(key);}
        catch(Exception e){busy=false;failed(client,"消息内容无法读取",0);return;}
        ApiClient.post(context,task.optInt("group_id")>0?"groups.messages.send":"messages.send",wire,onStore(new ApiClient.Callback(){
            public void onSuccess(Object value){busy=false;if(!sessionValid())return;
                if(!(value instanceof JSONObject)||((JSONObject)value).optInt("id")<=0){failed(client,"未收到发送确认，点击重试",0);return;}
                boolean removed=ackStored(client);if(removed)ui(()->listener.sent(task,(JSONObject)value));changed();pumpStored();
            }
            public void onError(String error){busy=false;failed(client,error,0);}
            public void onHttpError(int status,String error){busy=false;failed(client,error,status);}
        }));
    }
}
