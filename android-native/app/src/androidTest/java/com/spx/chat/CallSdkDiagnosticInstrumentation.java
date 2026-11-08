package com.spx.chat;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import com.tencent.cloud.tuikit.engine.call.TUICallEngine;
import com.tencent.cloud.tuikit.engine.call.TUICallDefine;
import com.tencent.cloud.tuikit.engine.common.TUICommonDefine;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import com.tencent.imsdk.v2.V2TIMManager;
import com.tencent.imsdk.v2.V2TIMValueCallback;
import com.tencent.imsdk.v2.V2TIMUserFullInfo;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Submits to random non-user identifiers; never calls a real contact. */
public final class CallSdkDiagnosticInstrumentation extends Instrumentation {
    private Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Activity host=startActivitySync(new Intent().setClassName("com.spx.chat","com.spx.chat.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
            waitForIdleSync();
            ChatCall[] call={null};boolean[] ready={false};
            for(int i=0;i<40;i++){runOnMainSync(()->{try{call[0]=ChatCall.active();ready[0]=call[0]!=null&&(boolean)get(call[0],"ready");}catch(Exception e){throw new RuntimeException(e);}});if(ready[0])break;SystemClock.sleep(500);}
            if(!ready[0]){result.putString("stream","SDK_NOT_READY "+(call[0]==null?"controller absent":get(call[0],"serviceError")));finish(Activity.RESULT_CANCELED,result);return;}
            TUICallEngine engine=(TUICallEngine)get(call[0],"engine");
            CountDownLatch profileDone=new CountDownLatch(1);String[] profiles={"timeout"};
            runOnMainSync(()->V2TIMManager.getInstance().getUsersInfo(Arrays.asList("spx_1","spx_5"),new V2TIMValueCallback<List<V2TIMUserFullInfo>>(){public void onSuccess(List<V2TIMUserFullInfo> users){profiles[0]="registered="+users.size();profileDone.countDown();}public void onError(int c,String m){profiles[0]="error="+c;profileDone.countDown();}}));
            profileDone.await(15,TimeUnit.SECONDS);result.putString("profiles",profiles[0]);
            for(TUICallDefine.MediaType media:new TUICallDefine.MediaType[]{TUICallDefine.MediaType.Audio,TUICallDefine.MediaType.Video}){
                CountDownLatch done=new CountDownLatch(1);String[] response={"CALLBACK_TIMEOUT"};
                runOnMainSync(()->{TUICallDefine.CallParams p=new TUICallDefine.CallParams();p.timeout=10;p.userData=UUID.randomUUID().toString();engine.call("spx_diag_"+UUID.randomUUID().toString().replace("-",""),media,p,new TUICommonDefine.Callback(){
                    public void onSuccess(){response[0]="SUBMITTED";done.countDown();}
                    public void onError(int code,String message){String safe=message==null?"":message.replaceAll("[A-Za-z0-9_*/+=-]{48,}","[redacted]");response[0]="code="+code+" message="+safe;done.countDown();}
                });});
                done.await(20,TimeUnit.SECONDS);result.putString(media.name(),response[0]);
                CountDownLatch closed=new CountDownLatch(1);runOnMainSync(()->engine.hangup(new TUICommonDefine.Callback(){public void onSuccess(){closed.countDown();}public void onError(int c,String m){closed.countDown();}}));closed.await(4,TimeUnit.SECONDS);SystemClock.sleep(1000);
            }
            result.putString("stream","Profiles: "+profiles[0]+"\nAudio: "+result.getString("Audio")+"\nVideo: "+result.getString("Video")+"\nSDK diagnostic complete; random non-user destinations; no business sessions or contact calls");finish(Activity.RESULT_OK,result);
        }catch(Throwable t){result.putString("stream","FAIL "+t.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);}
    }
}
