package com.spx.chat;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Exercises synthetic media only; never sends messages, starts calls, or writes server data. */
public final class MediaUiInstrumentation extends Instrumentation {
    private int checks;
    private Activity host;
    private ChatMediaViewer viewer;
    private String account;
    private final List<File> fixtures=new ArrayList<>();
    private final List<Uri> saved=new ArrayList<>();
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private Object invoke(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private interface Work{void run()throws Exception;}
    private void main(Work work)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new RuntimeException(error[0]);}
    private View find(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View r=find(g.getChildAt(i),label);if(r!=null)return r;}}return null;}
    private List<String> texts(View v){List<String> r=new ArrayList<>();if(v instanceof TextView)r.add(((TextView)v).getText().toString());if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)r.addAll(texts(g.getChildAt(i)));}return r;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        host=startActivitySync(new Intent().setClassName("com.spx.chat","com.spx.chat.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));waitForIdleSync();
        account="media-review:"+UUID.randomUUID();
        layoutChecks();mediaChecks();uncachedVideoEntryChecks();
        result.putString("stream","SUCCESS "+checks+" isolated media and layout checks; no live messages or server writes\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable t){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);
    }finally{try{main(()->{if(viewer!=null)viewer.dismiss();});}catch(Exception ignored){}for(Uri uri:saved)try{host.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}for(File f:fixtures)f.delete();}}
    private void layoutChecks()throws Exception{main(()->{
        check(I18n.t(host,"zh-CN","ui_voice_call").equals("语音通话"),"voice label has no legacy emoji");
        check(I18n.t(host,"zh-CN","ui_video_call").equals("视频通话"),"video label has no legacy emoji");
        LinearLayout row=new LinearLayout(host);
        View card=(View)invoke(host,"profileCallAction",new Class<?>[]{String.class,String.class,int.class,boolean.class,LinearLayout.class},"phone","语音通话",1,false,row);
        check(((ViewGroup)card).getChildCount()==2&&texts(card).equals(Arrays.asList("语音通话")),"profile has exactly one icon and one text label");
        int chat=(int)field(host,"activeChatId"),group=(int)field(host,"activeGroupId");
        try{for(boolean privateChat:new boolean[]{false,true}){
            set(host,"activeChatId",privateChat?1:0);set(host,"activeGroupId",privateChat?0:1);
            LinearLayout panel=new LinearLayout(host);panel.setOrientation(LinearLayout.VERTICAL);invoke(host,"showChatMorePanel",new Class<?>[]{LinearLayout.class},panel);
            check(panel.getChildCount()==1&&panel.getChildAt(0) instanceof GridLayout,"menu uses one packed grid");
            GridLayout grid=(GridLayout)panel.getChildAt(0);check(grid.getColumnCount()==4&&grid.getChildCount()==(privateChat?8:6),"correct number of visible actions");
            int width=host.getResources().getDisplayMetrics().widthPixels;grid.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));grid.layout(0,0,width,grid.getMeasuredHeight());
            for(int i=0;i<grid.getChildCount();i++){View item=grid.getChildAt(i);check(item.getWidth()>0,"grid action visible");if(i>0&&i%4!=0)check(item.getTop()==grid.getChildAt(i-1).getTop()&&item.getLeft()>=grid.getChildAt(i-1).getRight(),"row aligned without gaps or overlap");}
            View file=find(grid,"文件");check(file!=null&&file==grid.getChildAt(privateChat?5:3),"file packs into the next available cell");
        }}finally{set(host,"activeChatId",chat);set(host,"activeGroupId",group);}
    });}
    private JSONObject fixture(String type)throws Exception{
        String url="https://fixture.invalid/"+UUID.randomUUID()+(type.equals("video")?".mp4":".png");
        File file=ChatOutbox.mediaCacheFile(host,account,url,type);file.getParentFile().mkdirs();fixtures.add(file);
        if(type.equals("image")){Bitmap bitmap=Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff267865);try(OutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
        else try(ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand("cat /data/local/tmp/spx-media-fixture.mp4");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);OutputStream out=new FileOutputStream(file)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);check(file.length()>1000,"synthetic video available");}
        return new JSONObject().put("message_type",type).put("media_url",url).put("content",type.equals("video")?"[视频]":"[图片]");
    }
    private void ready(boolean video)throws Exception{
        for(int n=0;n<80;n++){boolean[] done={false};main(()->{done[0]=video?(boolean)field(viewer,"videoPrepared"):((ImageView)field(viewer,"photo")).getDrawable()!=null;});if(done[0])return;Thread.sleep(100);}throw new AssertionError("media did not finish loading");
    }
    private void swipe(boolean left)throws Exception{main(()->{
        View root=(View)field(viewer,"root");long now=SystemClock.uptimeMillis();float y=root.getHeight()*.45f,w=root.getWidth(),start=w*(left?.8f:.2f),end=w*(left?.2f:.8f);
        for(MotionEvent e:new MotionEvent[]{MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,start,y,0),MotionEvent.obtain(now,now+200,MotionEvent.ACTION_MOVE,(start+end)/2,y,0),MotionEvent.obtain(now,now+500,MotionEvent.ACTION_UP,end,y,0)}){root.dispatchTouchEvent(e);e.recycle();}
    });}
    private void mediaChecks()throws Exception{
        JSONObject first=fixture("image"),second=fixture("image"),video=fixture("video");List<JSONObject> entries=Arrays.asList(first,second,video);
        List<JSONObject> forwarded=new ArrayList<>();main(()->{viewer=ChatMediaViewer.show(host,entries,0,account,forwarded::add);});ready(false);waitForIdleSync();
        main(()->{View root=(View)field(viewer,"root");check(find(root,"上一张")==null&&find(root,"下一张")==null,"side arrows removed");check(find(root,"更多操作")!=null&&find(root,"关闭预览")!=null,"top controls available");});
        saveCheck(first,false);
        swipe(true);ready(false);main(()->check((int)field(viewer,"index")==1,"slow left swipe changes photo"));
        swipe(true);ready(true);main(()->check((int)field(viewer,"index")==2,"swipe reaches video in mixed list"));
        saveCheck(video,true);
        VideoView[] old={null};main(()->{old[0]=(VideoView)field(viewer,"video");check(old[0].isPlaying(),"video auto plays");viewer.pause();check(!old[0].isPlaying(),"background pauses video");invoke(viewer,"toggleVideo",new Class<?>[]{});check(old[0].isPlaying(),"video resumes from play control");});
        swipe(true);main(()->check((int)field(viewer,"index")==2,"last item does not wrap"));
        swipe(false);ready(false);main(()->{check((int)field(viewer,"index")==1,"right swipe returns to photo");check(field(viewer,"video")==null&&old[0].getParent()==null,"swipe releases video player");});
        main(()->{Object photo=field(viewer,"photo");set(photo,"zoom",2f);});swipe(true);main(()->check((int)field(viewer,"index")==1,"zoomed photo pans without paging"));
        main(()->{invoke(field(viewer,"photo"),"reset",new Class<?>[]{});invoke(viewer,"showActions",new Class<?>[]{});});
        main(()->{Dialog menu=latestMenu();check(menu!=null&&menu.isShowing(),"more opens actions dialog");ListView list=((AlertDialog)menu).getListView();check(list.getAdapter().getItem(0).equals("保存到相册")&&list.getAdapter().getItem(1).equals("转发"),"save and forward actions present");list.performItemClick(list.getChildAt(1),1,list.getAdapter().getItemId(1));check(forwarded.size()==1&&forwarded.get(0).optString("media_url").equals(second.optString("media_url")),"forward passes currently viewed photo");});
        main(()->{viewer=ChatMediaViewer.show(host,entries,2,account,forwarded::add);});ready(true);
        main(()->{invoke(viewer,"showActions",new Class<?>[]{});});
        main(()->{AlertDialog menu=(AlertDialog)latestMenu();ListView list=menu.getListView();list.performItemClick(list.getChildAt(1),1,list.getAdapter().getItemId(1));check(forwarded.size()==2&&forwarded.get(1).optString("message_type").equals("video"),"forward passes currently viewed video");check(field(viewer,"video")==null,"forward closes and releases player");});
    }
    private void uncachedVideoEntryChecks()throws Exception{
        byte[] bytes;
        try(ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand("cat /data/local/tmp/spx-media-fixture.mp4");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);bytes=out.toByteArray();
        }
        final byte[] videoBytes=bytes;
        java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger(),failures=new java.util.concurrent.atomic.AtomicInteger();
        Thread local=new Thread(()->{while(!server.isClosed())try(java.net.Socket socket=server.accept()){
            socket.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));String request=reader.readLine(),line;
            while((line=reader.readLine())!=null&&!line.isEmpty()){}requests.incrementAndGet();
            boolean fail=request!=null&&request.contains("retry.mp4")&&failures.getAndIncrement()==0;
            byte[] body=fail?new byte[0]:videoBytes;
            OutputStream out=socket.getOutputStream();out.write(((fail?"HTTP/1.1 503 Unavailable":"HTTP/1.1 200 OK")+"\r\nContent-Type: video/mp4\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
        }catch(IOException error){if(!server.isClosed())throw new RuntimeException(error);}},"spx-video-entry-fixture");local.start();
        int user=(int)field(host,"currentUserId"),chat=(int)field(host,"activeChatId"),group=(int)field(host,"activeGroupId");
        Object oldViewer=field(host,"chatMediaViewer");Map models=(Map)field(host,"activeMessageModels");Map original=new LinkedHashMap(models);
        final Dialog[] entryWindow={null};
        try{
            for(boolean isGroup:new boolean[]{false,true}){
                final String url="http://127.0.0.1:"+server.getLocalPort()+"/"+UUID.randomUUID()+".mp4";
                final JSONObject message=new JSONObject().put("id",2147481101).put("message_type","video").put("media_url",url);
                final String owner=ApiClient.baseUrl(host)+":"+user;File cache=ChatOutbox.mediaCacheFile(host,owner,url,"video");fixtures.add(cache);fixtures.add(new File(cache.getPath()+".thumb.jpg"));fixtures.add(new File(cache.getPath()+".duration"));
                final View[] coldPreview={null};
                int before=requests.get();
                main(()->{
                    set(host,"activeChatId",isGroup?0:1);set(host,"activeGroupId",isGroup?1:0);models.clear();models.put(2147481101,message);
                    check(!cache.exists(),"entry begins without video cache");
                    LinearLayout container=new LinearLayout(host);
                    View preview=(View)invoke(host,"addMediaMessage",new Class<?>[]{LinearLayout.class,String.class,String.class,boolean.class,String.class,String.class,String.class,String.class,String.class},container,url,"video",true,"fixture","F","#112233","#223344","");
                    coldPreview[0]=preview;entryWindow[0]=new Dialog(host);entryWindow[0].setContentView(container);entryWindow[0].show();
                    check(preview.performClick(),"uncached video card handles a click");viewer=(ChatMediaViewer)field(host,"chatMediaViewer");
                    check(viewer!=null&&((Dialog)field(viewer,"dialog")).isShowing(),"card opens viewer before poster exists");
                });ready(true);
                check(cache.isFile()&&cache.length()==videoBytes.length,"first click downloads complete video");check(requests.get()==before+1,"cold card downloads exactly once");
                main(()->viewer.dismiss());
                boolean durationShown=false;for(int n=0;n<80;n++){boolean[] shown={false};main(()->shown[0]=texts(coldPreview[0]).stream().anyMatch(s->s.matches("[0-9]+:[0-5][0-9]")));if(shown[0]){durationShown=true;break;}Thread.sleep(100);}
                check(durationShown,"closing first playback refreshes card poster and duration");
                main(()->{LinearLayout container=new LinearLayout(host);View preview=(View)invoke(host,"addMediaMessage",new Class<?>[]{LinearLayout.class,String.class,String.class,boolean.class,String.class,String.class,String.class,String.class,String.class},container,url,"video",true,"fixture","F","#112233","#223344","");check(preview.performClick(),"cached card remains clickable");viewer=(ChatMediaViewer)field(host,"chatMediaViewer");});ready(true);
                check(requests.get()==before+1,"repeat video uses downloaded cache; before="+before+", now="+requests.get()+", file="+cache.exists()+", owner="+field(host,"currentUserId"));main(()->{viewer.dismiss();entryWindow[0].dismiss();entryWindow[0]=null;});
            }
            final String url="http://127.0.0.1:"+server.getLocalPort()+"/retry.mp4";final JSONObject message=new JSONObject().put("id",2147481102).put("message_type","video").put("media_url",url);
            fixtures.add(ChatOutbox.mediaCacheFile(host,ApiClient.baseUrl(host)+":"+user,url,"video"));
            main(()->{models.clear();models.put(2147481102,message);invoke(host,"openChatMediaViewer",new Class<?>[]{String.class,String.class},url,"video");viewer=(ChatMediaViewer)field(host,"chatMediaViewer");});
            boolean failed=false;for(int n=0;n<80;n++){String[] value={""};main(()->value[0]=((TextView)field(viewer,"hint")).getText().toString());if(value[0].contains("加载失败")){failed=true;break;}Thread.sleep(100);}
            check(failed,"failed video shows an actionable retry");main(()->check(((TextView)field(viewer,"hint")).performClick(),"error label retries video"));ready(true);check(failures.get()==2,"retry recovers from initial 503");
            main(()->{LinearLayout list=new LinearLayout(host);invoke(host,"addStakePlan",new Class<?>[]{LinearLayout.class,JSONObject.class},list,new JSONObject().put("id",1).put("plan_days",90).put("apy","60").put("minimum_amount","100000"));check(texts(list).stream().anyMatch(s->s.contains("60.00%")&&(s.contains("年化")||s.contains("APY"))),"staking card identifies a percentage APY");});
        }finally{
            main(()->{if(viewer!=null)viewer.dismiss();if(entryWindow[0]!=null)entryWindow[0].dismiss();set(host,"currentUserId",user);set(host,"activeChatId",chat);set(host,"activeGroupId",group);set(host,"chatMediaViewer",oldViewer);models.clear();models.putAll(original);});
            server.close();local.join(2000);
        }
    }
    private Dialog latestMenu()throws Exception{
        return (Dialog)field(viewer,"actionMenu");
    }
    private byte[] digest(InputStream in)throws Exception{try(InputStream source=in){MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[16384];int n;while((n=source.read(b))!=-1)d.update(b,0,n);return d.digest();}}
    private void saveCheck(JSONObject entry,boolean video)throws Exception{
        long before=System.currentTimeMillis();main(()->invoke(viewer,"requestSave",new Class<?>[]{JSONObject.class},entry));
        for(int n=0;n<100&&(boolean)field(viewer,"saving");n++)Thread.sleep(100);
        check(!(boolean)field(viewer,"saving"),"save callback completes");
        Uri collection=video?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri found=null;String prefix="SPX_";
        try(Cursor cursor=host.getContentResolver().query(collection,new String[]{"_id","_display_name","mime_type","is_pending"},"_display_name LIKE ?",new String[]{prefix+"%"},"_id DESC")){
            if(cursor!=null&&cursor.moveToFirst()){String name=cursor.getString(1);long time=Long.parseLong(name.split("_")[1]);if(time>=before){found=ContentUris.withAppendedId(collection,cursor.getLong(0));saved.add(found);check(cursor.getString(2).equals(video?"video/mp4":"image/png"),"saved MIME matches actual media");check(cursor.getInt(3)==0,"saved media becomes visible");}}
        }
        check(found!=null,"saved item appears in correct album");File original=ChatOutbox.mediaCacheFile(host,account,entry.optString("media_url"),entry.optString("message_type"));
        check(Arrays.equals(digest(new FileInputStream(original)),digest(host.getContentResolver().openInputStream(found))),"saved original bytes match exactly");
    }
}
