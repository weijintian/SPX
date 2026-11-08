package com.spx.chat;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.json.JSONObject;
import com.tencent.cloud.tuikit.engine.call.TUICallDefine;
import com.tencent.cloud.tuikit.engine.common.TUICommonDefine;

/** UI-only sessions: no engine init, no server session, no messages or real calls. */
public final class CallUiInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    private Object get(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void set(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private Object invoke(Object object,String name)throws Exception {Method m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);}
    private View find(View view,String description){if(view.getContentDescription()!=null&&description.contentEquals(view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View v=find(g.getChildAt(i),description);if(v!=null)return v;}}return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Intent intent=new Intent().setClassName("com.spx.chat","com.spx.chat.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Activity host=startActivitySync(intent);waitForIdleSync();
            final Throwable[] error={null};
            runOnMainSync(()->{try{runChecks(host);}catch(Throwable t){error[0]=t;}});
            if(error[0]!=null)throw new RuntimeException(error[0]);
            result.putString("stream","SUCCESS "+checks+" isolated native call checks; no SDK dialing or server writes\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable t){result.putString("stream","FAIL: "+t.toString()+"\n"+(t.getCause()==null?"":t.getCause().toString()));finish(Activity.RESULT_CANCELED,result);}
    }
    private void runChecks(Activity host)throws Exception{
        check(ChatCallRecord.media("call","text","[语音通话] 连接中断",0)==1,"audio record redial");
        check(ChatCallRecord.media("call","text","[视频通话] 已取消",0)==2,"video record redial");
        check(ChatCallRecord.media("user","text","[语音通话] 已取消",0)==0,"forged text must not redial");
        check(ChatCallRecord.media("call","text","[语音通话] 已取消",3)==0,"group must not redial");
        check(CallFailure.expired(-10001,""),"expired SDK login recognized");
        check(!CallFailure.expired(-1,"unknown failure"),"generic failure must not masquerade as expired credentials");
        check(CallFailure.expired(-1,"[calls] API call failed: key is expired."),"wrapped expiry recognized");
        check(CallFailure.category(-1,"package expired").equals("service_package"),"package expiry is distinct");
        check(CallFailure.category(101011,"single group is not supported, please upgrade your package").equals("service_package"),"SDK package mismatch classified");
        check(!CallFailure.expired(101011,"please upgrade your package"),"package mismatch must not refresh credentials");
        matchingChecks(host);
        check(CallFloatBounds.clamp(-300,8,300)==8,"left boundary");
        check(CallFloatBounds.clamp(900,8,300)==300,"right boundary");
        check(CallFloatBounds.edge(40,136,8,540)==8,"snap left");
        check(CallFloatBounds.edge(400,136,8,540)==404,"snap right");
        check(CallFloatBounds.clamp(600,20,260)==260,"keyboard resize boundary");
        for(boolean video:new boolean[]{false,true}){
            ChatCall call=new ChatCall(host,0,()->{});
            set(call,"foreground",true);
            try{
                JSONObject session=new JSONObject().put("session_id",UUID.randomUUID().toString()).put("media",video?"video":"audio").put("peer_name","隔离界面测试");
                set(call,"session",session);set(call,"outgoing",true);set(call,"startedAt",SystemClock.elapsedRealtime());
                invoke(call,"show");Dialog full=(Dialog)get(call,"dialog");
                check(full.isShowing(),"full screen ringing visible");
                View shrink=find(full.getWindow().getDecorView(),"缩小通话，继续聊天");check(shrink!=null,"minimize button available");shrink.performClick();
                CallMiniWindow mini=(CallMiniWindow)get(call,"miniWindow");View box=(View)get(mini,"box");
                check((boolean)get(call,"minimized")&&!full.isShowing()&&box.getParent()!=null,"ringing minimizes without ending");
                check(get(call,"session")==session,"same session after minimize");
                check(box.isAttachedToWindow(),"mini attached to activity decor");
                View handle=(View)get(mini,"previewHandle");long now=SystemClock.uptimeMillis();
                for(MotionEvent e:new MotionEvent[]{MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,30,30,0),MotionEvent.obtain(now,now+30,MotionEvent.ACTION_MOVE,2000,2000,0),MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,2000,2000,0)}){handle.dispatchTouchEvent(e);e.recycle();}
                android.graphics.Rect b=(android.graphics.Rect)get(mini,"bounds");android.widget.FrameLayout.LayoutParams p=(android.widget.FrameLayout.LayoutParams)box.getLayoutParams();
                check(p.leftMargin>=0&&p.topMargin>=0&&p.leftMargin+p.width<=host.getWindow().getDecorView().getWidth(),"drag constrained to display");
                check((boolean)get(call,"minimized"),"drag does not accidentally restore");
                handle.performClick();check(!(boolean)get(call,"minimized")&&full.isShowing(),"mini tap restores full screen");
                check(get(call,"session")==session,"restoring retains session");
                set(call,"connected",true);long connectedAt=SystemClock.elapsedRealtime()-17000;set(call,"connectedAt",connectedAt);invoke(call,"show");invoke(call,"minimize");
                set(call,"heartbeatDelayed",true);check(((String)invoke(call,"callStatus")).contains("连接恢复"),"lost heartbeat shows recovery without terminating media");
                set(call,"heartbeatDelayed",false);set(call,"networkHint","网络不稳定，请稍候…");set(call,"networkHintAt",SystemClock.elapsedRealtime());check(((String)invoke(call,"callStatus")).contains("网络不稳定"),"weak network feedback visible");
                set(call,"networkHintAt",SystemClock.elapsedRealtime()-13000);check(((String)invoke(call,"callStatus")).matches("[0-9]+:[0-9]+"),"stale network warning clears");
                check((long)get(call,"connectedAt")==connectedAt,"connected timer unchanged after minimize");
                if(video){check(((View)get(call,"remoteVideo")).getParent()==get(mini,"preview"),"connected remote video transferred into mini");}
                call.start(1,!video);check(get(call,"session")==session&&!(boolean)get(call,"minimized"),"duplicate call restores active call without redial");
                if(video){check(((View)get(call,"remoteVideo")).getParent()==get(call,"videoLayer"),"video view restored without recreation");}
                invoke(call,"minimize");View hangup=find((View)get(mini,"box"),"挂断通话");check(hangup!=null,"mini hangup available");hangup.performClick();
                check(get(call,"session")==null&&get(call,"miniWindow")==null&&get(call,"dialog")==null,"hangup cleans full screen and floating view");
                check(get(call,"engine")==null&&!(boolean)get(call,"serverSession"),"test never initialized SDK or business call");
            }finally{if(get(call,"session")!=null){Method finish=ChatCall.class.getDeclaredMethod("finishLocal",String.class,boolean.class);finish.setAccessible(true);finish.invoke(call,"测试结束",false);}set(call,"disposed",true);}
        }
    }
    private void matchingChecks(Activity host)throws Exception{
        Method match=ChatCall.class.getDeclaredMethod("matches",String.class,TUICallDefine.MediaType.class,TUICallDefine.CallObserverExtraInfo.class);match.setAccessible(true);
        Method end=ChatCall.class.getDeclaredMethod("sdkEnded",String.class,TUICallDefine.MediaType.class,TUICallDefine.CallEndReason.class,TUICallDefine.CallObserverExtraInfo.class);end.setAccessible(true);
        for(boolean video:new boolean[]{false,true}){
            ChatCall call=new ChatCall(host,0,()->{});
            TUICallDefine.MediaType media=video?TUICallDefine.MediaType.Video:TUICallDefine.MediaType.Audio,other=video?TUICallDefine.MediaType.Audio:TUICallDefine.MediaType.Video;
            TUICallDefine.CallObserverExtraInfo extra=new TUICallDefine.CallObserverExtraInfo();extra.role=TUICallDefine.Role.Caller;extra.roomId=new TUICommonDefine.RoomId();extra.roomId.intRoomId=123456;
            try{
                check(!(boolean)match.invoke(call,"current",media,extra),"callback without business session ignored");
                JSONObject session=new JSONObject().put("session_id",UUID.randomUUID().toString()).put("media",video?"video":"audio");
                set(call,"session",session);set(call,"outgoing",true);set(call,"dialing",true);set(call,"sdkRoomId",123456);
                check(!(boolean)match.invoke(call,"",media,extra)&&!(boolean)match.invoke(call,null,media,extra),"empty SDK IDs rejected");
                check(!(boolean)match.invoke(call,"stale",media,null),"unbound callback without correlation rejected");
                extra.userData=session.optString("session_id");extra.roomId.intRoomId=123455;
                check(!(boolean)match.invoke(call,"stale",media,extra),"previous attempt rejected even with same business UUID");
                extra.userData=null;extra.roomId.intRoomId=123456;extra.role=TUICallDefine.Role.Called;
                check(!(boolean)match.invoke(call,"wrong-role",media,extra),"callee role cannot bind outgoing call");extra.role=TUICallDefine.Role.Caller;
                check(!(boolean)match.invoke(call,"wrong-media",other,extra),"media mismatch rejected");
                java.util.Set<String> retired=(java.util.Set<String>)get(call,"retiredCalls");retired.add("retired");
                check(!(boolean)match.invoke(call,"retired",media,extra),"retired SDK callback rejected");
                set(call,"closingSdk",true);check(!(boolean)match.invoke(call,"current",media,extra),"cleanup callback ignored");set(call,"closingSdk",false);
                set(call,"refreshingCredentials",true);check(!(boolean)match.invoke(call,"current",media,extra),"credential recovery callback ignored");set(call,"refreshingCredentials",false);
                set(call,"dialing",false);check(!(boolean)match.invoke(call,"current",media,extra),"callback before attempt rejected");set(call,"dialing",true);
                check((boolean)match.invoke(call,"current",media,extra),"one-to-one begin binds room without userData");
                check(get(call,"sdkId").equals("current"),"SDK identity retained");
                check((boolean)match.invoke(call,"current",media,null),"bound SDK callback works without extra info");
                check(!(boolean)match.invoke(call,"old",media,extra),"old SDK ID cannot replace active identity");
                end.invoke(call,"old",media,TUICallDefine.CallEndReason.UNKNOWN,extra);check(get(call,"session")==session,"stale end cannot terminate new call");
                end.invoke(call,"current",other,TUICallDefine.CallEndReason.UNKNOWN,extra);check(get(call,"session")==session,"wrong media end ignored");
                set(call,"outgoing",false);check((boolean)match.invoke(call,"current",media,null),"incoming call uses received identity");
                set(call,"connected",true);end.invoke(call,"current",media,TUICallDefine.CallEndReason.UNKNOWN,null);
                check(get(call,"session")==null&&(int)get(call,"sdkRoomId")==0,"connected end clears identity and room");
                check(get(call,"engine")==null&&!(boolean)get(call,"serverSession"),"matching tests never dial or write business session");
            }finally{set(call,"session",null);set(call,"disposed",true);}
        }
    }
}
