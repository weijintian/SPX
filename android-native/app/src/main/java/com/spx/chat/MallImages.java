package com.spx.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.widget.ImageView;
import android.util.LruCache;
import java.io.*;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Screen-sized decoding, bounded workers, lazy view loading and URL-level request sharing. */
final class MallImages {
    private static final ExecutorService WORKERS=Executors.newFixedThreadPool(3);
    private static final Object[] LOCKS=new Object[64];
    static {for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new Object();}
    private static final LruCache<String,Bitmap> MEMORY=new LruCache<String,Bitmap>(16*1024*1024) { protected int sizeOf(String k,Bitmap b){return b.getByteCount();} };
    static String url(Context c,String raw) {
        if(raw==null||raw.isEmpty()||raw.equals("null"))return "";
        if(raw.startsWith("http://")||raw.startsWith("https://"))return raw;
        return ApiClient.baseUrl(c)+"/"+raw.replaceAll("^/+","");
    }
    static void bind(ImageView v,String raw,boolean detail) {
        String u=url(v.getContext(),raw);v.setImageResource(android.R.drawable.ic_menu_gallery);v.setTag(u);if(u.isEmpty())return;
        int width=Math.min(1440,v.getResources().getDisplayMetrics().widthPixels);String key=u+":"+width+":"+detail;
        Bitmap cached=MEMORY.get(key);if(cached!=null){v.setImageBitmap(cached);return;}
        boolean[] started={false};
        Runnable check=()->{Rect r=new Rect();if(!started[0]&&v.isAttachedToWindow()&&v.getGlobalVisibleRect(r)){started[0]=true;load(v,u,key,width,detail);}};
        android.view.ViewTreeObserver.OnScrollChangedListener scroll=check::run;
        android.view.ViewTreeObserver.OnGlobalLayoutListener layout=check::run;
        v.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(android.view.View x){v.getViewTreeObserver().addOnScrollChangedListener(scroll);v.getViewTreeObserver().addOnGlobalLayoutListener(layout);v.post(check);}
            public void onViewDetachedFromWindow(android.view.View x){if(v.getViewTreeObserver().isAlive()){v.getViewTreeObserver().removeOnScrollChangedListener(scroll);v.getViewTreeObserver().removeOnGlobalLayoutListener(layout);}}
        });
        if(v.isAttachedToWindow()){v.getViewTreeObserver().addOnScrollChangedListener(scroll);v.getViewTreeObserver().addOnGlobalLayoutListener(layout);}
        v.post(check);
    }
    private static void load(ImageView view,String url,String key,int width,boolean detail){
        Context context=view.getContext().getApplicationContext();
        WORKERS.execute(()->{try{
            Object lock=LOCKS[(url.hashCode()&0x7fffffff)%LOCKS.length];Bitmap bitmap;
            synchronized(lock){
                bitmap=MEMORY.get(key);
                if(bitmap==null){
                    File dir=new File(context.getCacheDir(),"mall-images-v2");dir.mkdirs();
                    byte[] hash=MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format("%02x",b&255));
                    File file=new File(dir,name.toString());
                    if(!file.isFile()||file.length()==0){
                        File temp=File.createTempFile("image-",".part",dir);HttpURLConnection conn=null;
                        try{conn=ApiClient.openConnection(context,url);conn.setConnectTimeout(10000);conn.setReadTimeout(15000);if(conn.getResponseCode()!=200)throw new IOException();
                            try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(temp)){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>8*1024*1024)throw new IOException("图片过大");out.write(b,0,n);}}
                            if(!temp.renameTo(file))throw new IOException();
                        }finally{temp.delete();if(conn!=null)conn.disconnect();}
                    }
                    BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),opts);
                    if(opts.outWidth<1||opts.outHeight<1||((long)opts.outWidth*opts.outHeight)>40000000L){file.delete();throw new IOException();}
                    opts.inSampleSize=1;while(opts.outWidth/opts.inSampleSize>width*2||((long)opts.outWidth*opts.outHeight)/(opts.inSampleSize*opts.inSampleSize)>(detail?4000000:1600000))opts.inSampleSize*=2;
                    opts.inJustDecodeBounds=false;bitmap=BitmapFactory.decodeFile(file.getPath(),opts);if(bitmap==null){file.delete();throw new IOException();}MEMORY.put(key,bitmap);
                    File[] files=dir.listFiles();if(files!=null){long total=0;for(File f:files)total+=f.length();if(total>100L*1024*1024){java.util.Arrays.sort(files,(x,y)->Long.compare(x.lastModified(),y.lastModified()));for(File f:files){if(total<80L*1024*1024)break;if(!f.equals(file)){long len=f.length();if(f.delete())total-=len;}}}}
                }
            }
            Bitmap result=bitmap;view.post(()->{if(url.equals(view.getTag()))view.setImageBitmap(result);});
        }catch(Exception|OutOfMemoryError e){view.post(()->view.setContentDescription("图片暂时加载失败"));}});
    }
}
