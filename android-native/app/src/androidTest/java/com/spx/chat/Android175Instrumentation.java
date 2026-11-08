package com.spx.chat;

import android.app.*;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synthetic local queues, uploads and gestures only. No live business writes. */
public final class Android175Instrumentation extends Instrumentation {
    private int checks;
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private interface Work{void run()throws Exception;}
    private void main(Work work)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new RuntimeException(error[0]);}
    private void barrier()throws Exception{java.lang.reflect.Field f=ChatOutbox.class.getDeclaredField("STORE");f.setAccessible(true);((ExecutorService)f.get(null)).submit(()->{}).get(15,TimeUnit.SECONDS);waitForIdleSync();}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=new Isolated(getTargetContext());
        queueChecks(context);cacheChecks(context);gestureChecks(context);uploadChecks(context);tlsChecks(context);
        result.putString("stream","SUCCESS "+checks+" Android 1.75 queue, upload, cache, gesture and TLS checks; synthetic local data only\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}}
    private static final class Isolated extends ContextWrapper {
        Isolated(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("android175-"+name,mode);}
        @Override public File getFilesDir(){File f=new File(super.getFilesDir(),"android175-tests");f.mkdirs();return f;}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return super.openOrCreateDatabase("android175-"+name,mode,factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return super.openOrCreateDatabase("android175-"+name,mode,factory,handler);}
        @Override public File getDatabasePath(String name){return super.getDatabasePath("android175-"+name);}
    }
    private void queueChecks(Context context)throws Exception{
        context.getSharedPreferences("spx_api",0).edit().clear().commit();context.deleteDatabase("android175-spx_chat_outbox.db");
        ApiClient.baseUrl(context);ApiClient.token(context);AtomicBoolean wrongThread=new AtomicBoolean();AtomicInteger changed=new AtomicInteger();
        ChatOutbox.Listener listener=new ChatOutbox.Listener(){public void changed(){if(Looper.myLooper()!=Looper.getMainLooper())wrongThread.set(true);changed.incrementAndGet();}public void sent(JSONObject a,JSONObject b){throw new AssertionError("Empty-token test must not send");}};
        ChatOutbox[] holder={null};String[] id={null};
        main(()->{StrictMode.ThreadPolicy previous=StrictMode.getThreadPolicy();try{
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build());
            holder[0]=new ChatOutbox(context,175001,listener);id[0]=holder[0].enqueue(12,0,new JSONObject().put("message_type","text").put("content","synthetic draft"));holder[0].list(12,0);
        }finally{StrictMode.setThreadPolicy(previous);}});
        barrier();ChatOutbox queue=holder[0];
        check(queue.list(12,0).length()==1,"queue persists off main");check(!wrongThread.get()&&changed.get()>0,"listeners stay on main");
        JSONObject copy=queue.list(12,0).getJSONObject(0);copy.put("content","mutated");check(queue.list(12,0).getJSONObject(0).optString("content").equals("synthetic draft"),"UI snapshots detached");
        check(queue.list(13,0).length()==0,"chat isolation");main(queue::stop);barrier();
        main(()->holder[0]=new ChatOutbox(context,175001,listener));barrier();queue=holder[0];
        check(queue.list(12,0).length()==1,"queue survives reopen");check(queue.list(12,0).getJSONObject(0).optString("client_message_id").equals(id[0]),"stable client ID after reopen");
        main(()->holder[0].acknowledge(id[0]));barrier();check(queue.list(12,0).length()==0,"ack removes durable row");main(()->holder[0].acknowledge(id[0]));barrier();check(queue.list(12,0).length()==0,"duplicate ack harmless");
        main(()->holder[0].stop());barrier();
        main(()->{holder[0]=new ChatOutbox(context,175001,listener);holder[0].enqueue(12,0,new JSONObject().put("content","immediate stop"));holder[0].stop();});barrier();
        main(()->holder[0]=new ChatOutbox(context,175001,listener));barrier();check(holder[0].list(12,0).length()==1,"accepted enqueue survives immediate stop");
        main(()->holder[0].stop());barrier();
        main(()->holder[0]=new ChatOutbox(context,175002,listener));barrier();check(holder[0].list(12,0).length()==0,"account queue isolation");
        main(()->holder[0].stop());barrier();
        check(ChatOutbox.transientFailure(503,"service"),"server errors retry");check(ChatOutbox.transientFailure(429,"limit"),"rate limit retries");check(!ChatOutbox.transientFailure(401,"network"),"auth failure never retries");check(!ChatOutbox.transientFailure(422,"timeout"),"validation never retries");check(!ChatOutbox.transientFailure(0,"本地附件不存在"),"missing attachment never retries");
        context.deleteDatabase("android175-spx_chat_outbox.db");
        try(SQLiteDatabase legacy=context.openOrCreateDatabase("spx_chat_outbox.db",0,null)){
            legacy.execSQL("CREATE TABLE outbox (sequence INTEGER PRIMARY KEY AUTOINCREMENT,account TEXT NOT NULL,client_id TEXT NOT NULL,chat_id INTEGER NOT NULL,group_id INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,error TEXT,progress INTEGER NOT NULL DEFAULT 0,UNIQUE(account,client_id))");legacy.setVersion(2);
            JSONObject payload=new JSONObject().put("content","legacy interrupted").put("client_message_id","legacy-id");
            legacy.execSQL("INSERT INTO outbox(account,client_id,chat_id,group_id,payload,state) VALUES(?,?,?,?,?,?)",new Object[]{ApiClient.baseUrl(context)+":175001","legacy-id",12,0,payload.toString(),"sending"});
        }
        main(()->holder[0]=new ChatOutbox(context,175001,listener));barrier();JSONObject migrated=holder[0].list(12,0).getJSONObject(0);
        check(migrated.optString("local_state").equals("queued")&&migrated.optInt("local_attempts")==1,"legacy queue migrates and recovers interrupted send");
        check(migrated.optString("client_message_id").equals("legacy-id"),"migration retains idempotency ID");
        main(()->holder[0].stop());barrier();context.deleteDatabase("android175-spx_chat_outbox.db");
    }
    private void cacheChecks(Context context)throws Exception{
        File dir=new File(context.getFilesDir(),"cache-test");dir.mkdirs();File active=new File(dir,"active.mp4"),thumb=new File(dir,"active.mp4.thumb.jpg"),unused=new File(dir,"old.jpg");
        for(File file:new File[]{active,thumb,unused})try(FileOutputStream out=new FileOutputStream(file)){out.write(1);}
        try(ChatMediaFiles.Lease lease=ChatMediaFiles.protect(active)){ChatMediaFiles.clearUnused(dir);check(active.exists()&&thumb.exists(),"active media and sidecar protected");check(!unused.exists(),"unused cache cleared");}
        ChatMediaFiles.clearUnused(dir);check(!active.exists()&&!thumb.exists(),"closed media can be cleared");dir.delete();
    }
    private void gestureChecks(Context context)throws Exception{main(()->{
        ChatMessageScroll scroll=new ChatMessageScroll(context);LinearLayout list=new LinearLayout(context);list.setOrientation(1);TextView bubble=new TextView(context);bubble.setText("clickable message");bubble.setOnClickListener(v->{});list.addView(bubble,new LinearLayout.LayoutParams(100,60));scroll.addView(list,new ScrollView.LayoutParams(400,600));scroll.completeInitialLoad(false);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));scroll.layout(0,0,400,600);
        AtomicInteger taps=new AtomicInteger();scroll.backgroundTap=taps::incrementAndGet;
        gesture(scroll,200,150,200,150,50);check(taps.get()==1,"blank tap dismiss hook");gesture(scroll,20,20,20,20,50);check(taps.get()==1,"message click preserved");gesture(scroll,200,150,200,250,100);check(taps.get()==1,"scroll never dismisses keyboard");gesture(scroll,200,150,200,150,1000);check(taps.get()==1,"long press preserved");
    });}
    private void gesture(View target,float x,float y,float endX,float endY,long duration){long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,0,x,y,0),move=MotionEvent.obtain(now,now+duration/2,2,endX,endY,0),up=MotionEvent.obtain(now,now+duration,1,endX,endY,0);target.dispatchTouchEvent(down);target.dispatchTouchEvent(move);target.dispatchTouchEvent(up);down.recycle();move.recycle();up.recycle();}
    private void uploadChecks(Context context)throws Exception{
        try(Fixture server=new Fixture()){
            ApiClient.saveBaseUrl(context,"http://127.0.0.1:"+server.server.getLocalPort());ApiClient.saveToken(context,"synthetic-local-token");
            File file=new File(context.getFilesDir(),"upload.bin");try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(1024*1024+13);}
            try{
                JSONObject uploaded=upload(context,file,server);check(uploaded.optString("url").equals("https://fixture/video.mp4"),"resumed upload returns URL");check(server.indices.equals(Arrays.asList(1)),"confirmed chunk skipped");check(server.authorized.get(),"checkpoint authenticated");check(server.progressMonotonic.get(),"upload progress monotonic");
                server.completed=true;server.indices.clear();upload(context,file,server);check(server.indices.isEmpty(),"completed upload avoids retransmission");
                server.completed=false;server.reject=true;server.requests.set(0);CountDownLatch done=new CountDownLatch(1);AtomicInteger status=new AtomicInteger();
                ApiClient.uploadLarge(context,file,UUID.randomUUID().toString(),"video/mp4",new ApiClient.Callback(){public void onSuccess(Object data){done.countDown();}public void onError(String error){done.countDown();}public void onHttpError(int code,String error){status.set(code);done.countDown();}});
                check(done.await(15,TimeUnit.SECONDS)&&status.get()==422,"upload preserves HTTP validation status");check(server.requests.get()==1,"permanent checkpoint error not retried");
            }finally{file.delete();ApiClient.saveToken(context,"");ApiClient.saveBaseUrl(context,"https://70.39.201.191");}
        }
    }
    private JSONObject upload(Context context,File file,Fixture fixture)throws Exception{
        CountDownLatch done=new CountDownLatch(1);Object[] result={null};String[] error={null};AtomicInteger previous=new AtomicInteger(-1);
        ApiClient.uploadLarge(context,file,UUID.randomUUID().toString(),"video/mp4",new ApiClient.Callback(){
            public void onSuccess(Object data){result[0]=data;done.countDown();}public void onError(String text){error[0]=text;done.countDown();}
            public void onUploadProgress(int percent,long sent,long total){if(percent<previous.getAndSet(percent))fixture.progressMonotonic.set(false);}
        });
        if(!done.await(20,TimeUnit.SECONDS)||error[0]!=null||!(result[0] instanceof JSONObject))throw new AssertionError("Upload failed: "+error[0]);return (JSONObject)result[0];
    }
    private static final class Fixture implements AutoCloseable{
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));final List<Integer> indices=Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean authorized=new AtomicBoolean(),progressMonotonic=new AtomicBoolean(true);final AtomicInteger requests=new AtomicInteger();volatile boolean completed,reject,closed;
        Fixture()throws Exception{new Thread(()->{while(!closed)try(Socket socket=server.accept()){socket.setSoTimeout(10000);handle(socket);}catch(Exception ignored){}},"android175-fixture").start();}
        private void handle(Socket socket)throws Exception{
            InputStream in=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int b;while((b=in.read())!=-1){header.write(b);byte[] value=header.toByteArray();int n=value.length;if(n>=4&&value[n-4]==13&&value[n-3]==10&&value[n-2]==13&&value[n-1]==10)break;if(n>32768)throw new IOException("header too big");}
            String h=header.toString("UTF-8");int size=0;for(String line:h.split("\\r\\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());
            byte[] body=new byte[size];int offset=0;while(offset<size){int n=in.read(body,offset,size-offset);if(n<0)throw new EOFException();offset+=n;}requests.incrementAndGet();
            String result;if(h.contains("upload.message.status")){authorized.set(h.contains("Bearer synthetic-local-token"));result=completed?"{\"result\":{\"url\":\"https://fixture/video.mp4\"},\"received_chunks\":[]}":"{\"result\":null,\"received_chunks\":[0]}";}
            else{String text=new String(body,StandardCharsets.UTF_8);java.util.regex.Matcher match=java.util.regex.Pattern.compile("name=\\\"chunk_index\\\"\\r\\n\\r\\n([0-9]+)").matcher(text);if(match.find())indices.add(Integer.parseInt(match.group(1)));result="{\"url\":\"https://fixture/video.mp4\",\"message_type\":\"video\"}";}
            byte[] response=(reject?"{\"code\":422,\"message\":\"invalid fixture\"}":"{\"code\":0,\"data\":"+result+"}").getBytes(StandardCharsets.UTF_8);
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+(reject?"422 Unprocessable Entity":"200 OK")+"\r\nContent-Type: application/json\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(response);out.flush();
        }
        public void close()throws IOException{closed=true;server.close();}
    }
    private void tlsChecks(Context context)throws Exception{
        SSLHelper.init(context);boolean rejected=false;
        try(javax.net.ssl.SSLSocket socket=tls("70.39.201.191")){socket.startHandshake();ChatWebSocket.verifyPeer("70.39.201.191",socket.getSession());check(socket.getSession().isValid(),"production TLS endpoint identity valid");}
        try(javax.net.ssl.SSLSocket socket=tls("wrong-host.spx.invalid")){socket.startHandshake();ChatWebSocket.verifyPeer("wrong-host.spx.invalid",socket.getSession());}catch(javax.net.ssl.SSLException expected){rejected=true;}
        check(rejected,"TLS rejects incorrect peer name");
    }
    private javax.net.ssl.SSLSocket tls(String peer)throws Exception{
        Socket raw=new Socket();raw.connect(new InetSocketAddress("70.39.201.191",443),10000);raw.setSoTimeout(10000);
        javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)SSLHelper.getSSLContext().getSocketFactory().createSocket(raw,peer,443,true);
        javax.net.ssl.SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);return tls;
    }
}
