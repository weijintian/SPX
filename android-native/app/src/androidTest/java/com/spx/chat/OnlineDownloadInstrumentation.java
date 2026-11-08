package com.spx.chat;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Downloads an existing public release through production TLS. Never installs or publishes it. */
public final class OnlineDownloadInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private String read(InputStream stream)throws Exception{try(InputStream input=stream){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Context ctx=getTargetContext();File target=new File(ctx.getCacheDir(),"online-download-test.apk"),part=new File(target+".part"),meta=new File(part+".json");
        try{
            Context local=new ContextWrapper(ctx){@Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("online-download-test-"+name,mode);}};
            local.getSharedPreferences("spx_api",0).edit().putString("base_url",ApiClient.baseUrl(ctx)).commit();
            HttpURLConnection config=ApiClient.openConnection(local,ApiClient.actionUrl(local,"app.update.check",Collections.singletonMap("version_code","0")));config.setConnectTimeout(20000);config.setReadTimeout(20000);
            JSONObject release;try{check(config.getResponseCode()==200,"public metadata available");release=new JSONObject(read(config.getInputStream())).getJSONObject("data");}finally{config.disconnect();}
            Map<String,String> params=Collections.singletonMap("id",String.valueOf(release.getInt("id")));String url=ApiClient.actionUrl(local,"app.update.download",params);
            HttpURLConnection first=ApiClient.openConnection(local,url);first.setRequestProperty("Accept-Encoding","identity");first.setConnectTimeout(20000);first.setReadTimeout(30000);
            String etag,modified;long offset=524288;
            try{
                check(first.getResponseCode()==200,"normal full download remains compatible");etag=first.getHeaderField("ETag");modified=first.getHeaderField("Last-Modified");check(etag!=null&&!etag.startsWith("W/"),"server supplies strong validator");
                try(InputStream input=first.getInputStream();FileOutputStream output=new FileOutputStream(part)){byte[] b=new byte[16384];long remaining=offset;while(remaining>0){int n=input.read(b,0,(int)Math.min(b.length,remaining));if(n<0)throw new EOFException();output.write(b,0,n);remaining-=n;}}
            }finally{first.disconnect();}
            check(part.length()==offset,"interrupted download retains initial bytes");
            try(FileOutputStream output=new FileOutputStream(meta)){output.write(new JSONObject().put("url",url).put("etag",etag).put("last_modified",modified).toString().getBytes("UTF-8"));}
            CountDownLatch finished=new CountDownLatch(1);String[] error={null};long[] firstProgress={-1};
            ApiClient.download(local,"app.update.download",params,target,new ApiClient.DownloadCallback(){public void onProgress(int percent,long bytes,long total){if(firstProgress[0]<0)firstProgress[0]=bytes;}public void onSuccess(File file){finished.countDown();}public void onError(String message){error[0]=message;finished.countDown();}});
            check(finished.await(150,TimeUnit.SECONDS)&&error[0]==null,"real client resume succeeds: "+error[0]);check(firstProgress[0]>=offset,"progress continues beyond retained offset");
            check(target.length()==release.getLong("apk_size"),"final size equals active release");MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(target)){byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)sha.update(b,0,n);}StringBuilder hash=new StringBuilder();for(byte b:sha.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            check(hash.toString().equals(release.getString("apk_sha256")),"resumed APK SHA256 equals production release");check(!part.exists()&&!meta.exists(),"success removes partial metadata");
            result.putString("stream","SUCCESS "+checks+" emulator to production HTTPS resume checks\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable t){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}
        finally{target.delete();part.delete();meta.delete();ctx.getSharedPreferences("online-download-test-spx_api",0).edit().clear().commit();}
    }
}
