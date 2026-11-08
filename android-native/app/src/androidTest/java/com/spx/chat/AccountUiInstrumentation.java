package com.spx.chat;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Synthetic UI and preferences only. Financial submit buttons are never given valid amounts. */
public final class AccountUiInstrumentation extends Instrumentation {
    private int checks;
    private Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private EditText input(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=input(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        MainActivity host=(MainActivity)startActivitySync(new Intent().setClassName("com.spx.chat","com.spx.chat.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));waitForIdleSync();
        Throwable[] error={null};runOnMainSync(()->{try{runChecks(host);}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new RuntimeException(error[0]);
        result.putString("stream","SUCCESS "+checks+" isolated profile/contact/navigation/form UI checks; no financial submissions\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}}
    private void runChecks(MainActivity host)throws Exception{
        String[] names={"preferences","currentUserId","userName","userEmail","currentUserSpxId","remoteAvatarUrl","userAvatarUri","chatBackgroundUri","chatBackgroundPreset","content","bottomNav","activeTab","subPageOpen","chatWindowOpen","askWindowOpen","currentCommercePage","previousTab","walletAssetDetailOpen","updateRequired","contactsRender","contactsRefresh","contactsPage","authChildOpen"};
        Map<String,Object> old=new HashMap<>();for(String name:names)old.put(name,get(host,name));
        SharedPreferences prefs=host.getSharedPreferences("account-ui-review",0);prefs.edit().clear().commit();
        int a=2147481001,b=2147481002;String server=ApiClient.baseUrl(host);
        SharedPreferences ownA=host.getSharedPreferences("spx_profile_"+AccountScope.id(server,a),0),ownB=host.getSharedPreferences("spx_profile_"+AccountScope.id(server,b),0);
        ArrayDeque history=(ArrayDeque)get(host,"pageHistory");ArrayList savedHistory=new ArrayList(history);history.clear();
        Set<Dialog> dialogs=(Set<Dialog>)get(host,"accountDialogs");
        try{
            set(host,"preferences",prefs);set(host,"content",new FrameLayout(host));set(host,"bottomNav",new LinearLayout(host));
            set(host,"subPageOpen",false);set(host,"chatWindowOpen",false);set(host,"askWindowOpen",false);set(host,"currentCommercePage",false);set(host,"updateRequired",false);
            ownA.edit().clear().commit();ownB.edit().clear().commit();
            call(host,"acceptAuthenticatedUser",new Class<?>[]{JSONObject.class},new JSONObject().put("id",a).put("nickname","用户甲").put("avatar_url","https://example.invalid/a.jpg"));
            check(get(host,"remoteAvatarUrl").equals("https://example.invalid/a.jpg"),"first account avatar saved");
            call(host,"acceptAuthenticatedUser",new Class<?>[]{JSONObject.class},new JSONObject().put("id",b).put("nickname","用户乙").put("avatar_url",JSONObject.NULL));
            check(get(host,"remoteAvatarUrl").equals("")&&get(host,"userAvatarUri")==null,"null avatar cannot reuse previous account");
            check(get(host,"userName").equals("用户乙"),"new profile name belongs to new account");
            call(host,"loadOwnProfile",new Class<?>[]{int.class},a);check(get(host,"remoteAvatarUrl").equals("https://example.invalid/a.jpg"),"first account keeps its own avatar cache");
            call(host,"loadOwnProfile",new Class<?>[]{int.class},b);check(get(host,"remoteAvatarUrl").equals(""),"second account retains empty avatar after reload");
            String key=(String)call(host,"accountCacheKey",new Class<?>[]{String.class},"contacts_cache");
            JSONObject peer=new JSONObject().put("id",77).put("nickname","新朋友").put("is_online",0);
            call(host,"contactAdded",new Class<?>[]{JSONObject.class},peer);call(host,"contactAdded",new Class<?>[]{JSONObject.class},peer);
            check(new JSONArray(prefs.getString(key,"[]")).length()==1,"friend add immediately updates cache without duplicates");
            call(host,"removeCachedContact",new Class<?>[]{int.class},77);check(new JSONArray(prefs.getString(key,"[]")).length()==0,"friend removal immediately updates cache");
            JSONObject online=new JSONObject().put("is_online",1);check(!(boolean)call(host,"presenceOnline",new Class<?>[]{JSONObject.class},online),"unverified cached online is not trusted");
            call(host,"markPresenceObserved",new Class<?>[]{JSONObject.class},online);check((boolean)call(host,"presenceOnline",new Class<?>[]{JSONObject.class},online),"fresh observed presence accepted");
            online.put("_presence_at",System.currentTimeMillis()-91000);check(!(boolean)call(host,"presenceOnline",new Class<?>[]{JSONObject.class},online),"old online expires");
            TextView status=new TextView(host);call(host,"showAuthSuccess",new Class<?>[]{TextView.class,String.class},status,"验证码已发送");check(status.getCurrentTextColor()==Color.rgb(37,145,103),"success has success color");
            call(host,"showAuthError",new Class<?>[]{TextView.class,String.class},status,"输入有误");check(status.getCurrentTextColor()==Color.rgb(207,67,67),"error has error color");
            FrameLayout frame=(FrameLayout)get(host,"content");LinearLayout home=new LinearLayout(host);frame.addView(home);LinearLayout child=new LinearLayout(host);LinearLayout nested=new LinearLayout(host);
            call(host,"openSubPage",new Class<?>[]{LinearLayout.class,int.class},child,3);call(host,"openSubPage",new Class<?>[]{LinearLayout.class,int.class},nested,3);
            host.onBackPressed();check(frame.getChildAt(0)==child&&(boolean)get(host,"subPageOpen"),"system back restores previous child");
            host.onBackPressed();check(frame.getChildAt(0)==home&&!(boolean)get(host,"subPageOpen"),"system back restores root without finishing");check(!host.isFinishing(),"secondary navigation does not finish activity");
            JSONObject plan=new JSONObject().put("id",1).put("plan_days",30).put("apy","8.2").put("minimum_amount","100000");
            call(host,"showStakeDialog",new Class<?>[]{JSONObject.class,String.class},plan,"100000");
            AlertDialog stake=null;for(Dialog dialog:dialogs)if(dialog instanceof AlertDialog&&dialog.isShowing())stake=(AlertDialog)dialog;
            check(stake!=null,"staking form opens");stake.getButton(AlertDialog.BUTTON_POSITIVE).performClick();check(stake.isShowing(),"invalid amount keeps form open");
            check(stake.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"invalid input does not lock submit");
            call(host,"resetAccountSurface",new Class<?>[]{});check(!stake.isShowing()&&dialogs.isEmpty(),"account reset dismisses old financial dialog");
            check(get(host,"remoteAvatarUrl").equals("")&&(int)get(host,"currentUserId")==0,"logout clears in-memory identity");
        }finally{
            for(Dialog dialog:new ArrayList<>(dialogs))dialog.dismiss();ownA.edit().clear().commit();ownB.edit().clear().commit();prefs.edit().clear().commit();
            for(String name:names)set(host,name,old.get(name));history.clear();history.addAll(savedHistory);
        }
    }
}
