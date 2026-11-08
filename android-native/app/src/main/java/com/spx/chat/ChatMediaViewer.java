package com.spx.chat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.function.Consumer;

/** One account-scoped viewer for chat photos and videos, with touch paging and media actions. */
final class ChatMediaViewer {
    private static final int SAVE_PERMISSION=7421;
    private static final long MAX_BYTES=1024L*1024*1024;
    private static WeakReference<ChatMediaViewer> awaitingPermission=new WeakReference<>(null);
    private final Activity host;
    private final String account,token,server;
    private final Dialog dialog;
    private final List<JSONObject> entries=new ArrayList<>();
    private final Consumer<JSONObject> forward;
    private final SwipeFrame root;
    private final FrameLayout media;
    private final ZoomImage photo;
    private final TextView counter,hint,play,elapsed;
    private final ProgressBar loading;
    private final LinearLayout videoControls;
    private final SeekBar seek;
    private VideoView video;
    private ChatMediaFiles.Lease videoLease;
    private final java.util.Set<HttpURLConnection> transfers=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private AlertDialog actionMenu;
    private Runnable onDismiss;
    private int index;
    private volatile int generation;
    private volatile boolean saving,closed;
    private boolean seeking,videoPrepared,resumeAfterSeek,resumeAfterMenu;
    private JSONObject pendingSave;
    private final Runnable videoTick=new Runnable(){public void run(){
        if(!dialog.isShowing()||video==null||!videoPrepared)return;
        int duration=Math.max(0,video.getDuration()),position=Math.max(0,video.getCurrentPosition());
        if(!seeking)seek.setProgress(duration==0?0:(int)(position*1000L/duration));
        elapsed.setText(time(position)+" / "+time(duration));play.setText(video.isPlaying()?"暂停":"播放");
        root.postDelayed(this,400);
    }};

    private ChatMediaViewer(Activity host,List<JSONObject> sources,int selected,String account,Consumer<JSONObject> forward){
        this.host=host;this.account=account;this.forward=forward;
        token=ApiClient.token(host);server=ApiClient.baseUrl(host);
        for(JSONObject source:sources)try{entries.add(new JSONObject(source.toString()));}catch(Exception ignored){entries.add(source);}
        index=Math.max(0,Math.min(selected,entries.size()-1));
        dialog=new Dialog(host,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        root=new SwipeFrame(host);root.setBackgroundColor(Color.BLACK);
        media=new FrameLayout(host);root.addView(media,new FrameLayout.LayoutParams(-1,-1));
        photo=new ZoomImage(host);media.addView(photo,new FrameLayout.LayoutParams(-1,-1));photo.tap=this::toggleChrome;
        loading=new ProgressBar(host);root.addView(loading,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));
        hint=text("",13);hint.setPadding(dp(20),dp(14),dp(20),dp(14));
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);hp.topMargin=dp(92);root.addView(hint,hp);
        counter=text("",14);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP);
        cp.setMargins(dp(72),dp(24),dp(72),0);root.addView(counter,cp);
        control("back","关闭预览",Gravity.START,this::dismiss);
        control("detail","更多操作",Gravity.END,this::showActions);
        videoControls=new LinearLayout(host);videoControls.setGravity(Gravity.CENTER_VERTICAL);
        videoControls.setPadding(dp(12),dp(12),dp(12),dp(20));videoControls.setBackgroundColor(0xBF101010);
        play=text("播放",14);play.setContentDescription("播放或暂停视频");videoControls.addView(play,new LinearLayout.LayoutParams(dp(54),dp(48)));
        seek=new SeekBar(host);seek.setMax(1000);seek.setContentDescription("视频播放进度");videoControls.addView(seek,new LinearLayout.LayoutParams(0,dp(48),1));
        elapsed=text("0:00 / 0:00",11);videoControls.addView(elapsed,new LinearLayout.LayoutParams(dp(88),dp(48)));
        root.addView(videoControls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));videoControls.setVisibility(View.GONE);
        play.setOnClickListener(v->toggleVideo());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){seeking=true;resumeAfterSeek=video!=null&&video.isPlaying();if(video!=null)video.pause();}
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(user&&video!=null&&videoPrepared)elapsed.setText(time((int)(video.getDuration()*value/1000L))+" / "+time(video.getDuration()));}
            public void onStopTrackingTouch(SeekBar bar){seeking=false;if(video!=null&&videoPrepared){video.seekTo((int)(video.getDuration()*bar.getProgress()/1000L));if(resumeAfterSeek)video.start();}}
        });
        dialog.setContentView(root);
        dialog.setOnDismissListener(d->{
            cleanup();Runnable callback=onDismiss;onDismiss=null;
            if(callback!=null)callback.run();
        });
        root.setOnApplyWindowInsetsListener((view,insets)->{root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
    }
    static ChatMediaViewer show(Activity host,List<JSONObject> entries,int selected,String account,Consumer<JSONObject> forward){
        if(entries.isEmpty()||host.isFinishing()||host.isDestroyed())return null;
        ChatMediaViewer viewer=new ChatMediaViewer(host,entries,selected,account,forward);viewer.dialog.show();
        if(viewer.dialog.getWindow()!=null)viewer.dialog.getWindow().setLayout(-1,-1);viewer.load();return viewer;
    }
    static boolean permissionResult(Activity host,int request,int[] results){
        if(request!=SAVE_PERMISSION)return false;
        ChatMediaViewer viewer=awaitingPermission.get();awaitingPermission.clear();
        if(viewer!=null&&viewer.host==host&&viewer.alive()){
            JSONObject source=viewer.pendingSave;viewer.pendingSave=null;
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&source!=null)viewer.save(source);
            else AppNotice.show(host,"未获得存储权限，无法保存到相册");
        }return true;
    }
    private int dp(int value){return MallUi.dp(host,value);}
    private TextView text(String value,int size){TextView v=new TextView(host);v.setText(value);v.setTextSize(size);v.setTextColor(Color.WHITE);v.setGravity(Gravity.CENTER);return v;}
    private void control(String kind,String label,int gravity,Runnable action){
        ImageView v=new ImageView(host);v.setImageDrawable(new ChatControlIcon(kind,Color.WHITE));v.setPadding(dp(12),dp(12),dp(12),dp(12));v.setContentDescription(label);
        v.setBackground(MallUi.surface(host,0x66333333,24,false));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|gravity);
        p.setMargins(dp(12),dp(24),dp(12),0);root.addView(v,p);v.setOnClickListener(w->action.run());
    }
    private boolean alive(){return !closed&&dialog.isShowing()&&!host.isFinishing()&&!host.isDestroyed()&&token.equals(ApiClient.token(host))&&server.equals(ApiClient.baseUrl(host));}
    private boolean current(int ticket){return ticket==generation&&alive();}
    void whenDismissed(Runnable callback){onDismiss=callback;}
    void dismiss(){cleanup();dialog.dismiss();}
    private void cleanup(){
        if(closed)return;closed=true;generation++;for(HttpURLConnection c:transfers)cancelTransfer(c);transfers.clear();releaseVideo();pendingSave=null;
        if(actionMenu!=null)actionMenu.dismiss();
        if(awaitingPermission.get()==this)awaitingPermission.clear();photo.setImageDrawable(null);
    }
    void pause(){if(video!=null&&videoPrepared){video.pause();play.setText("播放");}}
    private void cancelTransfer(HttpURLConnection c){new Thread(c::disconnect,"spx-media-cancel").start();}
    private void move(int direction){int next=index+direction;if(next<0||next>=entries.size())return;index=next;load();}
    private void load(){
        if(!saving)for(HttpURLConnection c:transfers)cancelTransfer(c);
        final int ticket=++generation;final JSONObject entry=entries.get(index);
        releaseVideo();photo.setImageDrawable(null);photo.reset();photo.setVisibility(View.VISIBLE);
        media.animate().cancel();media.setTranslationX(0);counter.setText((index+1)+" / "+entries.size());
        loading.setVisibility(View.VISIBLE);hint.setVisibility(View.VISIBLE);hint.setText("正在加载…");hint.setOnClickListener(null);setChromeVisible(true);
        String url=entry.optString("media_url");ChatMediaWork.execute(url,()->{try{
            if(!current(ticket))return;File file=obtain(entry,ticket);if(!current(ticket))return;
            if("video".equals(entry.optString("message_type"))){
                ChatMediaFiles.Lease lease=ChatMediaFiles.protect(file);
                root.post(()->{if(current(ticket))showVideo(file,ticket,lease);else lease.close();});
            }
            else{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("图片格式无法读取");
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2560)options.inSampleSize*=2;
                Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);if(bitmap==null)throw new IOException("图片格式无法读取");
                root.post(()->{if(!current(ticket)){bitmap.recycle();return;}photo.setImageBitmap(bitmap);photo.reset();loading.setVisibility(View.GONE);hint.setVisibility(View.GONE);});
            }
        }catch(Exception error){root.post(()->{if(current(ticket)){loading.setVisibility(View.GONE);hint.setText("加载失败 · 点击重试");hint.setOnClickListener(v->load());}});}});
    }
    private File obtain(JSONObject entry,int ticket)throws Exception{
        String url=entry.optString("media_url"),type=entry.optString("message_type");File target=ChatOutbox.mediaCacheFile(host,account,url,type);
        if(target.isFile()&&target.length()>0)return target;
        if(!alive())throw new IOException("预览已关闭");File directory=target.getParentFile();if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("存储不可用");
        File part=new File(target.getPath()+".viewer-part");HttpURLConnection c=null;
        try{
            c=ApiClient.openConnection(host,url);transfers.add(c);c.setConnectTimeout(15000);c.setReadTimeout(15000);int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("下载失败");
            long expected=c.getContentLengthLong(),done=0,last=0;if(expected>0&&directory.getUsableSpace()<expected+8L*1024*1024)throw new IOException("存储空间不足");if(expected>MAX_BYTES)throw new IOException("附件超过 1 GB");
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(part)){
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(!alive())throw new IOException("预览已关闭");if(ticket!=generation&&!saving)throw new IOException("已切换媒体");done+=n;if(done>MAX_BYTES)throw new IOException("附件超过 1 GB");out.write(b,0,n);
                    long now=System.currentTimeMillis();if(now-last>200){final String label=expected>0?"加载中 "+Math.min(99,done*100/expected)+"%":"正在加载…";root.post(()->{if(current(ticket))hint.setText(label);});last=now;}}
            }
            if(done==0||(expected>=0&&done!=expected))throw new IOException("下载不完整");if(!part.renameTo(target))throw new IOException("无法保存缓存");return target;
        }finally{if(c!=null){transfers.remove(c);c.disconnect();}if(part.exists())part.delete();}
    }
    private void showVideo(File file,int ticket,ChatMediaFiles.Lease lease){
        videoLease=lease;
        photo.setVisibility(View.GONE);videoPrepared=false;final VideoView player=new VideoView(host);video=player;
        media.addView(player,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));player.setContentDescription("视频，左右滑动切换，点击显示播放控制");
        player.setOnClickListener(v->toggleChrome());player.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;});
        player.setOnPreparedListener(mp->{if(!current(ticket)||video!=player)return;videoPrepared=true;mp.setLooping(false);loading.setVisibility(View.GONE);hint.setVisibility(View.GONE);videoControls.setVisibility(View.VISIBLE);player.start();root.removeCallbacks(videoTick);root.post(videoTick);});
        player.setOnCompletionListener(mp->{if(current(ticket)){play.setText("重播");setChromeVisible(true);}});
        player.setOnErrorListener((mp,what,extra)->{if(current(ticket)){videoPrepared=false;loading.setVisibility(View.GONE);hint.setText("视频播放失败 · 点击重试");hint.setVisibility(View.VISIBLE);hint.setOnClickListener(v->load());}return true;});
        player.setVideoPath(file.getAbsolutePath());
    }
    private void toggleVideo(){if(video==null||!videoPrepared)return;if(video.isPlaying())video.pause();else{if(video.getCurrentPosition()>=video.getDuration())video.seekTo(0);video.start();}play.setText(video.isPlaying()?"暂停":"播放");}
    private void releaseVideo(){root.removeCallbacks(videoTick);videoPrepared=false;seeking=false;resumeAfterMenu=false;videoControls.setVisibility(View.GONE);if(video!=null){video.stopPlayback();media.removeView(video);video=null;}if(videoLease!=null){videoLease.close();videoLease=null;}}
    private void toggleChrome(){setChromeVisible(counter.getVisibility()!=View.VISIBLE);}
    private void setChromeVisible(boolean visible){counter.setVisibility(visible?View.VISIBLE:View.GONE);videoControls.setVisibility(visible&&videoPrepared?View.VISIBLE:View.GONE);}
    private void showActions(){
        if(!alive())return;final JSONObject selected=entries.get(index);resumeAfterMenu=video!=null&&video.isPlaying();if(video!=null)video.pause();
        AlertDialog menu=new AlertDialog.Builder(host).setItems(new String[]{"保存到相册","转发"},(d,action)->{
            if(action==0)requestSave(selected);else{resumeAfterMenu=false;dismiss();forward.accept(selected);}
        }).create();actionMenu=menu;menu.setOnDismissListener(d->{if(resumeAfterMenu&&alive()&&video!=null&&videoPrepared)video.start();resumeAfterMenu=false;actionMenu=null;});menu.show();
    }
    private void requestSave(JSONObject entry){
        if(saving){AppNotice.show(host,"正在保存，请稍候");return;}
        if(Build.VERSION.SDK_INT<=28&&host.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){pendingSave=entry;awaitingPermission=new WeakReference<>(this);host.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},SAVE_PERMISSION);return;}
        save(entry);
    }
    private void save(JSONObject entry){
        if(!alive()||saving)return;saving=true;final int ticket=generation;String url=entry.optString("media_url");AppNotice.show(host,"正在保存到相册…");
        ChatMediaWork.execute(url,()->{Uri target=null;File legacy=null;try{
            File file=obtain(entry,ticket);boolean isVideo="video".equals(entry.optString("message_type"));String mime=mime(file,isVideo);
            String ext=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);if(ext==null)ext=isVideo?"mp4":"jpg";
            String name="SPX_"+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+"."+ext;if(!alive())throw new IOException("预览已关闭");
            if(Build.VERSION.SDK_INT>=29){
                ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,(isVideo?"Movies":"Pictures")+"/SPX IM");values.put(MediaStore.MediaColumns.IS_PENDING,1);
                target=host.getContentResolver().insert(isVideo?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(target==null)throw new IOException("无法创建相册文件");
                try(InputStream in=new FileInputStream(file);OutputStream out=host.getContentResolver().openOutputStream(target)){if(out==null)throw new IOException("无法写入相册");copy(in,out);}
                values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);if(host.getContentResolver().update(target,values,null,null)!=1)throw new IOException("保存未完成");
            }else{
                File folder=new File(Environment.getExternalStoragePublicDirectory(isVideo?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_PICTURES),"SPX IM");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("相册目录不可用");legacy=new File(folder,name);
                try(InputStream in=new FileInputStream(file);OutputStream out=new FileOutputStream(legacy)){copy(in,out);}MediaScannerConnection.scanFile(host,new String[]{legacy.getPath()},new String[]{mime},null);
            }
            root.post(()->{saving=false;if(alive())AppNotice.show(host,"已保存到相册");});
        }catch(Exception error){if(target!=null)try{host.getContentResolver().delete(target,null,null);}catch(Exception ignored){}if(legacy!=null)legacy.delete();root.post(()->{saving=false;if(alive())AppNotice.show(host,"保存失败，请检查存储空间后重试");});}});
    }
    private void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(!token.equals(ApiClient.token(host))||!server.equals(ApiClient.baseUrl(host)))throw new IOException("账号已变化");out.write(b,0,n);}}
    private static String mime(File file,boolean video){
        if(!video){BitmapFactory.Options info=new BitmapFactory.Options();info.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),info);if(info.outMimeType!=null&&info.outMimeType.startsWith("image/"))return info.outMimeType;}
        String ext=android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getName()).toLowerCase(Locale.ROOT);String value=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return value!=null&&value.startsWith(video?"video/":"image/")?value:video?"video/mp4":"image/jpeg";
    }
    private static String time(int millis){int seconds=Math.max(0,millis/1000);return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}

    private final class SwipeFrame extends FrameLayout{
        private float downX,downY;private boolean dragging,blocked;private final int slop;
        SwipeFrame(Context c){super(c);slop=ViewConfiguration.get(c).getScaledTouchSlop();}
        @Override public boolean onInterceptTouchEvent(MotionEvent e){int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();dragging=false;blocked=downY<dp(90)+getPaddingTop()||(videoControls.getVisibility()==View.VISIBLE&&downY>getHeight()-dp(90)-getPaddingBottom());}
            if(e.getPointerCount()>1||photo.isZoomed())blocked=true;
            if(action==MotionEvent.ACTION_MOVE&&!blocked&&entries.size()>1){float dx=e.getX()-downX,dy=e.getY()-downY;if(Math.abs(dx)>slop*2&&Math.abs(dx)>Math.abs(dy)*1.25f){dragging=true;return true;}}return false;
        }
        @Override public boolean onTouchEvent(MotionEvent e){if(!dragging)return true;float dx=e.getX()-downX;boolean edge=dx>0?index==0:index==entries.size()-1;
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){media.setTranslationX(dx*(edge?.2f:1));return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;if(e.getActionMasked()==MotionEvent.ACTION_UP&&!edge&&Math.abs(dx)>=dp(48)){media.setTranslationX(0);move(dx<0?1:-1);}else media.animate().translationX(0).setDuration(120).start();}return true;
        }
    }
    private static final class ZoomImage extends ImageView{
        private final Matrix transform=new Matrix();private final ScaleGestureDetector pinch;private final GestureDetector gestures;private float zoom=1,x,y;Runnable tap;
        ZoomImage(Context c){super(c);setScaleType(ScaleType.MATRIX);setContentDescription("图片，可双指缩放、双击放大、左右滑动切换");
            pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){float old=zoom;zoom=Math.max(1,Math.min(5,zoom*d.getScaleFactor()));float ratio=zoom/old;x=d.getFocusX()-(d.getFocusX()-x)*ratio;y=d.getFocusY()-(d.getFocusY()-y)*ratio;apply();return true;}});
            gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){public boolean onDown(MotionEvent e){return true;}public boolean onSingleTapConfirmed(MotionEvent e){performClick();if(tap!=null)tap.run();return true;}public boolean onDoubleTap(MotionEvent e){zoom=zoom>1?1:2.5f;x=getWidth()/2f-(e.getX()-getWidth()/2f)*zoom;y=getHeight()/2f-(e.getY()-getHeight()/2f)*zoom;apply();return true;}public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){if(zoom>1&&!pinch.isInProgress()){x-=dx;y-=dy;apply();}return true;}});
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);gestures.onTouchEvent(e);return true;}
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);reset();}
        boolean isZoomed(){return zoom>1.01f;}
        void reset(){zoom=1;x=getWidth()/2f;y=getHeight()/2f;apply();}
        private void apply(){if(getDrawable()==null||getWidth()==0||getHeight()==0)return;float iw=getDrawable().getIntrinsicWidth(),ih=getDrawable().getIntrinsicHeight();if(iw<=0||ih<=0)return;float fit=Math.min(getWidth()/iw,getHeight()/ih)*zoom,w=iw*fit,h=ih*fit;x=w<=getWidth()?getWidth()/2f:Math.max(getWidth()-w/2f,Math.min(w/2f,x));y=h<=getHeight()?getHeight()/2f:Math.max(getHeight()-h/2f,Math.min(h/2f,y));transform.setScale(fit,fit);transform.postTranslate(x-w/2f,y-h/2f);setImageMatrix(transform);}
    }
}
