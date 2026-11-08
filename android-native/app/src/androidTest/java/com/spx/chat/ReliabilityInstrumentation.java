package com.spx.chat;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import org.json.*;

/** Isolated local-state checks. Never submits products, payments, messages or calls. */
public final class ReliabilityInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    private EditText firstInput(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){EditText found=firstInput(g.getChildAt(i));if(found!=null)return found;}}return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        MainActivity host=(MainActivity)startActivitySync(new Intent().setClassName("com.spx.chat","com.spx.chat.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));waitForIdleSync();
        Throwable[] failure={null};runOnMainSync(()->{try{runChecks(host);}catch(Throwable t){failure[0]=t;}});
        if(failure[0]!=null)throw new RuntimeException(failure[0]);
        downloadChecks(host);
        result.putString("stream","SUCCESS "+checks+" isolated reliability checks; no business writes\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable t){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}}
    private void runChecks(MainActivity host)throws Exception{
        Context isolated=new ContextWrapper(host){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("reliability-test-"+name,mode);}};
        JSONObject product=new JSONObject().put("id",2147482001);
        MallProductDraft a=new MallProductDraft(isolated,"https://test-a",1,product),b=new MallProductDraft(isolated,"https://test-a",2,product),node=new MallProductDraft(isolated,"https://test-b",1,product),other=new MallProductDraft(isolated,"https://test-a",1,new JSONObject().put("id",2147482002));
        try{
            a.save(new JSONObject().put("name","草稿").put("images",new JSONArray().put("https://test/image.jpg")).put("variants",new JSONArray().put(new JSONObject().put("cny_price","168"))));
            check(a.load()!=null&&a.load().optString("name").equals("草稿"),"draft recovers after new instance");
            check(new MallProductDraft(isolated,"https://test-a",1,product).load().optJSONArray("images").length()==1,"uploaded image persists");
            check(a.load().optJSONArray("variants").getJSONObject(0).optString("cny_price").equals("168"),"variant persists");
            check(b.load()==null,"account isolation");check(node.load()==null,"server isolation");check(other.load()==null,"product isolation");
            a.clear();check(a.load()==null,"discard removes only target draft");
        }finally{isolated.getSharedPreferences("mall-product-local-v1",0).edit().clear().commit();}
        int uid=host.mallUserId();MallSession owner=null,next=null;
        try{
            set(host,"currentUserId",-987651);owner=new MallSession(host);owner.payments.edit().putString("pending","first").commit();
            check(owner.valid(host),"captured current identity valid");
            set(host,"currentUserId",-987652);next=new MallSession(host);next.payments.edit().putString("pending","second").commit();
            check(!owner.valid(host),"old identity rejected after account change");
            owner.clearPayment("unrelated");check(owner.payments.getString("pending","").equals("first"),"stale payment cannot remove replacement");
            owner.clearPayment("first");check(owner.payments.getString("pending","").isEmpty(),"original pending resolved");
            check(next.payments.getString("pending","").equals("second"),"other account pending untouched");
        }finally{if(owner!=null)owner.payments.edit().clear().commit();if(next!=null)next.payments.edit().clear().commit();set(host,"currentUserId",uid);}
        ApiClient.RequestHandle request=new ApiClient.RequestHandle();request.cancel();request.cancel();check(request.isCancelled(),"cancel idempotent");
        MallCommerce commerce=new MallCommerce(host);JSONObject form=new JSONObject().put("id",2147482003).put("name","原名称").put("description","测试草稿");
        MallProductDraft local=new MallProductDraft(host,ApiClient.baseUrl(host),uid,form);local.clear();
        Object previousContent=get(host,"content"),previousNav=get(host,"bottomNav");FrameLayout isolatedContent=new FrameLayout(host);set(host,"content",isolatedContent);set(host,"bottomNav",new LinearLayout(host));
        try{
            set(commerce,"active",true);call(commerce,"productForm",new Class<?>[]{JSONObject.class,boolean.class},form,true);
            EditText name=firstInput((View)get(commerce,"pageRoot"));check(name!=null,"product form renders");name.setText("修改名称");commerce.pause();
            check(local.load()!=null&&local.load().optString("name").equals("修改名称"),"pause flushes edits");
            check(form.optString("name").equals("修改名称"),"navigation snapshot synchronized");
            ((Runnable)get(commerce,"productDiscard")).run();check(form.optString("name").equals("原名称"),"discard restores baseline");
            name.setText("恢复名称");commerce.pause();call(commerce,"productForm",new Class<?>[]{JSONObject.class,boolean.class},form,true);
            check(firstInput((View)get(commerce,"pageRoot")).getText().toString().equals("恢复名称"),"rebuilt form retains edits");
            MallSession identity=new MallSession(host);long epoch=(long)get(commerce,"pageEpoch");
            check((boolean)call(commerce,"pageValid",new Class<?>[]{MallSession.class,long.class},identity,epoch),"current page callback allowed");
            commerce.externalPage();check(!(boolean)call(commerce,"pageValid",new Class<?>[]{MallSession.class,long.class},identity,epoch),"detached page callback rejected");
            check(get(commerce,"productCapture")==null,"external navigation releases capture");
            set(commerce,"batchUris",new java.util.ArrayList<android.net.Uri>());set(commerce,"batchFailed",true);set(commerce,"batchIndex",2);set(commerce,"imageUploading",false);
            call(commerce,"skipBatch",new Class<?>[]{});check((int)get(commerce,"batchIndex")==3&&!(boolean)get(commerce,"batchFailed"),"skip advances only failed image");
            call(commerce,"clearBatch",new Class<?>[]{});check(get(commerce,"batchUris")==null&&get(commerce,"multiDone")==null,"cancel clears queue and callbacks");
        }finally{commerce.dispose();local.clear();set(host,"content",previousContent);set(host,"bottomNav",previousNav);}
    }
    private void downloadChecks(MainActivity host)throws Exception{
        Context local=new ContextWrapper(host){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("reliability-download-"+name,mode);}};
        java.io.File destination=new java.io.File(host.getCacheDir(),"reliability-download.apk"),partial=new java.io.File(destination+".part"),meta=new java.io.File(partial+".json");
        byte[] complete="0123456789abcdef".getBytes("UTF-8");
        try{
            for(int scenario=0;scenario<4;scenario++){
                final int test=scenario;
                java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));server.setSoTimeout(10000);
                local.getSharedPreferences("spx_api",0).edit().clear().putString("base_url","http://127.0.0.1:"+server.getLocalPort()).commit();
                String url=ApiClient.actionUrl(local,"app.update.download",null);
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(partial)){out.write(complete,0,5);}
                JSONObject metadata=new JSONObject().put("url",url).put("etag","\"fixture\"");
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(meta)){out.write(metadata.toString().getBytes("UTF-8"));}
                String[] headers={""};Throwable[] serverError={null};
                Thread response=new Thread(()->{try(java.net.Socket socket=server.accept()){
                    java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),"UTF-8"));String line;StringBuilder request=new StringBuilder();while((line=reader.readLine())!=null&&!line.isEmpty())request.append(line).append('\n');headers[0]=request.toString().toLowerCase(java.util.Locale.ROOT);
                    String status=test==1||test==3?"200 OK":"206 Partial Content";
                    String extra=test==1||test==3?"":"Content-Range: bytes 5-15/16\r\n";
                    String etag=test==2?"\"different\"":"\"fixture\"";
                    int length=test==1||test==3?16:11;
                    java.io.OutputStream output=socket.getOutputStream();output.write(("HTTP/1.1 "+status+"\r\nContent-Length: "+length+"\r\nETag: "+etag+"\r\n"+extra+"Connection: close\r\n\r\n").getBytes("UTF-8"));
                    output.write(complete,test==1||test==3?0:5,test==3?5:length);output.flush();
                }catch(Throwable t){serverError[0]=t;}finally{try{server.close();}catch(Exception ignored){}}},"download-fixture");response.start();
                java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);String[] error={null};boolean[] success={false};
                ApiClient.download(local,"app.update.download",null,destination,new ApiClient.DownloadCallback(){public void onProgress(int p,long received,long total){}public void onSuccess(java.io.File file){success[0]=true;done.countDown();}public void onError(String message){error[0]=message;done.countDown();}});
                check(done.await(15,java.util.concurrent.TimeUnit.SECONDS),"download callback completes");response.join(1000);if(serverError[0]!=null)throw new RuntimeException(serverError[0]);
                check(headers[0].contains("range: bytes=5-")&&headers[0].contains("if-range: \"fixture\""),"resume uses byte offset and validator");
                if(test<2){byte[] data=new byte[16];try(java.io.FileInputStream in=new java.io.FileInputStream(destination)){check(in.read(data)==16&&java.util.Arrays.equals(data,complete),test==0?"206 resumes exact bytes":"200 safely restarts");}check(success[0]&&!partial.exists(),"download finalizes complete file");}
                if(test==2)check(error[0]!=null&&!partial.exists(),"changed ETag discards incompatible partial");
                if(test==3)check(error[0]!=null&&partial.length()==5&&meta.isFile(),"interrupted transfer retains resumable bytes");
                destination.delete();partial.delete();meta.delete();
            }
        }finally{destination.delete();partial.delete();meta.delete();local.getSharedPreferences("spx_api",0).edit().clear().commit();}
    }
}
