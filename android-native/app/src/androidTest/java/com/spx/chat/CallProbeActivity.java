package com.spx.chat;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import com.tencent.cloud.tuikit.engine.call.*;
import com.tencent.cloud.tuikit.engine.common.*;
import java.io.File;
import java.util.*;
import org.json.JSONObject;

/** Separate-package disposable SDK peers; credentials are provisioned privately by the test runner. */
public class CallProbeActivity extends Activity {
    TUICallEngine engine;Handler h=new Handler();JSONObject c;String role;TUIVideoView video;
    void event(String s){Log.i("SPXCallProbe",role+" "+s);}
    String correlation(String id,TUICallDefine.CallObserverExtraInfo extra){return " id_present="+(id!=null&&!id.isEmpty())+" role="+(extra==null?null:extra.role)+" room_match="+(extra!=null&&extra.roomId!=null&&extra.roomId.intRoomId==c.optInt("room"))+" userdata="+(extra!=null&&c.optString("session").equals(extra.userData));}
    TUICommonDefine.Callback cb(String op,Runnable next){return new TUICommonDefine.Callback(){public void onSuccess(){event(op+" OK");if(next!=null)h.post(next);}public void onError(int code,String message){event(op+" ERROR "+code+" "+(message==null?"":message.replaceAll("[A-Za-z0-9_*/+=-]{48,}","[redacted]")));}};}
    @Override public void onCreate(Bundle b){super.onCreate(b);try{
        String config=getIntent().getStringExtra("config");if(config==null)config="credentials.json";
        c=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(),config).toPath()),"UTF-8"));role=c.getString("role");video=new TUIVideoView(this);setContentView(video);
        engine=TUICallEngine.createInstance(this);engine.addObserver(new TUICallObserver(){
            @Override public void onCallReceived(String id,String caller,List<String> callees,TUICallDefine.MediaType type,TUICallDefine.CallObserverExtraInfo extra){event("RECEIVED"+correlation(id,extra));engine.accept(cb("ACCEPT",null));}
            @Override public void onCallBegin(String id,TUICallDefine.MediaType type,TUICallDefine.CallObserverExtraInfo extra){event("CONNECTED"+correlation(id,extra));engine.openMicrophone(cb("MIC",null));if(type==TUICallDefine.MediaType.Video)engine.openCamera(TUICommonDefine.Camera.Front,video,cb("CAMERA",null));h.postDelayed(()->{if(role.equals("caller"))engine.hangup(cb("HANGUP",null));},8000);}
            @Override public void onCallNotConnected(String id,TUICallDefine.MediaType type,TUICallDefine.CallEndReason reason,String user,TUICallDefine.CallObserverExtraInfo extra){event("NOT_CONNECTED "+reason);}
            @Override public void onCallEnd(String id,TUICallDefine.MediaType type,TUICallDefine.CallEndReason reason,String user,long total,TUICallDefine.CallObserverExtraInfo extra){event("ENDED "+reason+" duration="+total);}
            @Override public void onError(int code,String message){event("OBSERVER_ERROR "+code);}
        });engine.init(c.getInt("sdk_app_id"),c.getString("user_id"),c.getString("user_sig"),cb("INIT",()->{if(role.equals("caller"))h.postDelayed(()->{TUICallDefine.CallParams p=new TUICallDefine.CallParams();p.timeout=20;p.userData=c.optString("session");p.roomId=new TUICommonDefine.RoomId();p.roomId.intRoomId=c.optInt("room");engine.call(c.optString("peer"),c.optBoolean("video")?TUICallDefine.MediaType.Video:TUICallDefine.MediaType.Audio,p,cb("DIAL",null));},1500);}));
    }catch(Exception e){Log.e("SPXCallProbe","SETUP_FAILED "+e.getClass().getSimpleName());}}
}
