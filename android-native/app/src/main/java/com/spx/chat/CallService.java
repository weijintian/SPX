package com.spx.chat;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Owns the lifetime of an already user-started call, never resurrects a dead call. */
public final class CallService extends Service {
    static final String OPEN="com.spx.chat.OPEN_CALL", END="com.spx.chat.END_CALL";
    private static final String CHANNEL="spx_active_call";
    private static final int NOTICE=4122;
    private static CallService running;
    private ChatCall call;
    private String sessionId="";
    private PowerManager.WakeLock wakeLock;

    static void start(Context context,ChatCall call,String id){
        Intent intent=new Intent(context,CallService.class).putExtra("session",id);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);
    }
    static boolean owns(ChatCall call){return running!=null&&running.call==call;}
    static void stop(ChatCall owner){if(running!=null&&running.call==owner){CallService service=running;service.call=null;service.release();service.stopSelf();}}
    private Notification notification(){
        Intent open=new Intent(this,MainActivity.class).setAction(OPEN).putExtra("call_session",sessionId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent end=new Intent(this,CallService.class).setAction(END).putExtra("session",sessionId);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content=PendingIntent.getActivity(this,sessionId.hashCode(),open,flags);
        PendingIntent hangup=PendingIntent.getService(this,sessionId.hashCode(),end,flags);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("SPX 通话进行中")
            .setContentText("点击返回通话 · 可在后台和锁屏时继续语音")
            .setContentIntent(content).setOngoing(true).setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"挂断",hangup).build());
        return b.build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String id=intent==null?"":intent.getStringExtra("session");
        if(intent!=null&&END.equals(intent.getAction())){
            if(call!=null&&sessionId.equals(id))call.hangupFromNotification();
            if(call==null)stopSelf();
            return START_NOT_STICKY;
        }
        ChatCall candidate=ChatCall.active();
        if(candidate==null||!candidate.matchesSession(id)){stopSelf();return START_NOT_STICKY;}
        call=candidate;sessionId=id;
        try{
            if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"正在进行的通话",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(channel);}
            if(Build.VERSION.SDK_INT>=30)startForeground(NOTICE,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);else startForeground(NOTICE,notification());
            running=this;
            if(wakeLock==null){wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SPX:activeCall");wakeLock.setReferenceCounted(false);wakeLock.acquire();}
            call.serviceReady();
        }catch(RuntimeException error){ChatCall failed=call;call=null;release();stopSelf();failed.serviceFailed();}
        return START_NOT_STICKY;
    }
    private void release(){if(wakeLock!=null){if(wakeLock.isHeld())wakeLock.release();wakeLock=null;}if(running==this)running=null;stopForeground(true);}
    @Override public void onDestroy(){ChatCall interrupted=call;call=null;release();if(interrupted!=null)interrupted.serviceFailed();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
