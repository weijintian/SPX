package com.spx.chat;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.UUID;
import org.json.JSONObject;

/** Exercises the real foreground service without dialing SDK peers or changing business data. */
public final class BackgroundCallInstrumentation extends Instrumentation {
    private int checks;
    private ChatCall call;
    private Activity host;
    private Context isolated;
    private String sid;
    private ChatCall previous;
    private int owner;
    private android.media.AudioRecord recorder;
    private Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private Object invoke(String name)throws Exception{Method m=ChatCall.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(call);}
    private void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;android.util.Log.i("SPXBackgroundTest",checks+" "+name);}
    private void ui(Runnable work){Throwable[] failure={null};runOnMainSync(()->{try{work.run();}catch(Throwable t){failure[0]=t;}});if(failure[0]!=null)throw new RuntimeException(failure[0]);}
    private void sleep(long millis){SystemClock.sleep(millis);}
    private void shell(String command)throws Exception{try(ParcelFileDescriptor file=getUiAutomation().executeShellCommand(command)){java.io.FileInputStream stream=new java.io.FileInputStream(file.getFileDescriptor());byte[] b=new byte[4096];while(stream.read(b)!=-1){} }}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        host=startActivitySync(new Intent().setClassName("com.spx.chat","com.spx.chat.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));waitForIdleSync();sleep(2400);
        ui(()->{try{
            previous=ChatCall.active();if(previous!=null){check(get(previous,"session")==null,"never replace a user's real call");previous.foreground(false);}
            owner=((MainActivity)host).mallUserId();
            isolated=new ContextWrapper(host.getApplicationContext()){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("background-call-test-"+name,mode);}};
            isolated.getSharedPreferences("spx_api",0).edit().clear().putString("base_url","http://127.0.0.1:9").commit();
            call=new ChatCall(host,owner,()->{});set(call,"app",isolated);set(call,"token","");set(call,"base",ApiClient.baseUrl(isolated));set(call,"active",call);
            set(host,"chatCall",call);set(call,"foreground",true);set(call,"ready",true);set(call,"credentialsDeadline",SystemClock.elapsedRealtime()+3600000);
            sid=UUID.randomUUID().toString();set(call,"session",new JSONObject().put("session_id",sid).put("media","video").put("peer_name","隔离后台测试"));
            set(call,"outgoing",true);set(call,"connected",true);set(call,"connectedAt",SystemClock.elapsedRealtime()-12000);set(call,"startedAt",SystemClock.elapsedRealtime());
            check((boolean)invoke("ensureService"),"foreground starts microphone service");
        }catch(Exception e){throw new RuntimeException(e);}});
        for(int i=0;i<60&&!CallService.owns(call);i++)sleep(50);
        ui(()->{try{
            check(CallService.owns(call),"real service owns active session");
            Object service=CallService.class.getDeclaredField("running");Field f=(Field)service;f.setAccessible(true);Object running=f.get(null);
            check(((PowerManager.WakeLock)get(running,"wakeLock")).isHeld(),"CPU wake lock acquired");
            check(host.getSystemService(NotificationManager.class).getActiveNotifications().length>0,"ongoing notification posted");
        }catch(Exception e){throw new RuntimeException(e);}});
        int min=android.media.AudioRecord.getMinBufferSize(16000,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT);
        recorder=new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT,Math.max(min,8192));
        check(recorder.getState()==android.media.AudioRecord.STATE_INITIALIZED,"real emulator microphone initializes");recorder.startRecording();
        check(recorder.read(new short[320],0,320)>0,"foreground captures audio buffers");
        shell("input keyevent KEYCODE_HOME");sleep(1800);
        ui(()->{try{check(get(call,"session")!=null&&CallService.owns(call),"Home keeps session and service");check(!(boolean)get(call,"foreground"),"activity really backgrounded");check(!(boolean)get(call,"cameraOpened"),"background camera paused");}catch(Exception e){throw new RuntimeException(e);}});
        check(recorder.read(new short[320],0,320)>0,"background captures audio buffers");
        shell("input keyevent KEYCODE_SLEEP");sleep(3000);
        long pulseBefore=(long)get(call,"lastPulseAt");sleep(48000);
        check(recorder.getRecordingState()==android.media.AudioRecord.RECORDSTATE_RECORDING&&recorder.read(new short[320],0,320)>0,"locked screen still captures audio after 50 seconds");
        check((long)get(call,"lastPulseAt")>pulseBefore+40000,"session timer continues across server heartbeat expiry window");
        recorder.stop();recorder.release();recorder=null;
        ui(()->{try{check(get(call,"session")!=null&&CallService.owns(call),"screen off retains active call");check((boolean)get(call,"connected"),"connection state retained");}catch(Exception e){throw new RuntimeException(e);}});
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");
        ui(()->host.startActivity(new Intent(host,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)));sleep(800);
        ui(()->{try{
            call.foreground(true);check(get(call,"dialog")!=null,"return restores full call UI");
            long connectedAt=(long)get(call,"connectedAt");call.detach(host);
            check(get(call,"host")==null&&get(call,"dialog")==null,"destroyed activity views released");
            check(get(call,"session")!=null&&CallService.owns(call),"detaching activity keeps service call");
            check(ChatCall.acquire(host,owner,()->{})==call,"new UI attaches same controller");call.foreground(true);
            check((long)get(call,"connectedAt")==connectedAt,"recreation preserves call timer");
            call.openFromNotification("different-session");check(call.matchesSession(sid),"stale notification cannot replace call");
            host.startService(new Intent(host,CallService.class).setAction(CallService.END).putExtra("session","stale"));
        }catch(Exception e){throw new RuntimeException(e);}});
        sleep(300);ui(()->check(call.matchesSession(sid),"stale hangup action ignored"));
        ui(()->host.startService(new Intent(host,CallService.class).setAction(CallService.END).putExtra("session",sid)));sleep(500);
        ui(()->{try{check(get(call,"session")==null&&!CallService.owns(call),"notification hangup releases service");check(get(call,"dialog")==null&&get(call,"miniWindow")==null,"hangup clears both UI modes");check(get(call,"engine")==null&&!(boolean)get(call,"serverSession"),"test made no SDK or business call");call.dispose();set(host,"chatCall",null);}catch(Exception e){throw new RuntimeException(e);}});
        result.putString("stream","SUCCESS "+checks+" real service lifecycle checks; media not simulated as verified\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable t){try{ui(()->{if(call!=null)call.dispose();});}catch(Throwable ignored){}result.putString("stream","FAIL "+android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}
    finally{if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}try{ui(()->{try{if(call!=null)call.dispose();set(call==null?new ChatCall(host,owner,()->{}):call,"active",previous);if(host!=null)set(host,"chatCall",previous);if(previous!=null)previous.foreground(true);}catch(Exception e){throw new RuntimeException(e);}});}catch(Throwable ignored){}if(isolated!=null)isolated.getSharedPreferences("spx_api",0).edit().clear().commit();}}
}
