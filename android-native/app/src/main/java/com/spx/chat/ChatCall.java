package com.spx.chat;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.tencent.cloud.tuikit.engine.room.TUIRoomEngine;
import com.tencent.cloud.tuikit.engine.room.TUIRoomDefine;
import com.tencent.cloud.tuikit.engine.call.TUICallEngine;
import com.tencent.cloud.tuikit.engine.call.TUICallObserver;
import com.tencent.cloud.tuikit.engine.call.TUICallDefine;
import com.tencent.cloud.tuikit.engine.common.TUICommonDefine;
import com.tencent.cloud.tuikit.engine.common.TUIVideoView;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Authenticated call state survives UI recreation; CallService owns active background calls. */
final class ChatCall {
    private interface Failure { void accept(String message); }
    static final int PERMISSION_REQUEST = 4120;
    private Activity host;
    private final android.content.Context app;
    private static ChatCall active;
    private static boolean engineRetiring;
    private boolean initInFlight,destroyPending;
    private boolean serviceRequested;
    private boolean disposing,heartbeatDelayed;
    private String networkHint="";
    private long networkHintAt,lastPulseAt;
    private Boolean routedSpeaker;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int owner;
    private final String token, base, device;
    private Runnable recordsChanged;
    private TUICallEngine engine;
    private boolean foreground, disposed, ready, initializing, pulsePending, serverSession;
    private int generation,cleanupEpoch,credentialRecoveries;
    private long credentialsExpire, retryAt, lastPresence, lastHeartbeat, startedAt, connectedAt;
    private JSONObject session;
    private String sdkId = "", peer = "", serviceError = "";
    private boolean outgoing, connected, accepting, muted, speaker, camera = true, front = true, swapped, micOpened, cameraOpened, micOpening, cameraOpening;
    private int mediaEpoch;
    private Runnable pendingPermission;
    private Runnable permissionReady;
    private Dialog dialog;
    private FrameLayout screen, videoLayer;
    private LinearLayout controls;
    private TextView status;
    private TUIVideoView localVideo, remoteVideo;
    private android.media.MediaPlayer waitingSound;
    private ToneGenerator connectedTone;
    private CallMiniWindow miniWindow;
    private boolean minimized,refreshingCredentials,closingSdk;
    private long credentialsDeadline;
    private Runnable afterReady;

    ChatCall(Activity host, int owner, Runnable recordsChanged) {
        this.host = host; this.app=host.getApplicationContext(); this.owner = owner; this.recordsChanged = recordsChanged;
        token = ApiClient.token(host); base = ApiClient.baseUrl(host);
        android.content.SharedPreferences p = host.getSharedPreferences("spx_call", 0);
        String value = p.getString("device_id", "");
        if (value.isEmpty()) { value = UUID.randomUUID().toString(); p.edit().putString("device_id", value).apply(); }
        device = value;
    }
    static ChatCall active(){return active;}
    boolean hasSession(){return session != null;}
    static ChatCall acquire(Activity host,int owner,Runnable changed){
        if(active!=null&&!active.belongsTo(owner)){active.dispose();active=null;}
        if(active==null)active=new ChatCall(host,owner,changed);
        if(active.host!=null&&active.host!=host)active.releaseViews();
        active.host=host;active.recordsChanged=changed;return active;
    }
    boolean matchesSession(String id){return id!=null&&same(id);}
    void serviceReady(){if(session==null){CallService.stop(this);return;}handler.removeCallbacks(pulse);handler.post(pulse);}
    void serviceFailed(){serviceRequested=false;if(session!=null)finish("failed","系统停止了后台通话，请重新拨打");}
    void hangupFromNotification(){if(session!=null)finish(connected?"ended":outgoing?"canceled":"rejected","通话已结束");}
    void openFromNotification(String id){if(matchesSession(id)){minimized=false;restore();}}
    private void changed(){if(host!=null&&foreground&&recordsChanged!=null)recordsChanged.run();}
    private boolean ensureService(){
        if(serviceRequested)return true;
        if(!foreground||host==null)return false;
        try{serviceRequested=true;CallService.start(app,this,session.optString("session_id"));
            handler.postDelayed(()->{if(serviceRequested&&session!=null&&!CallService.owns(this))serviceFailed();},4500);return true;
        }catch(RuntimeException error){serviceRequested=false;finish("failed","无法启动后台通话服务，请检查麦克风权限");return false;}
    }
    private void releaseViews(){
        if(miniWindow!=null){miniWindow.dismiss();miniWindow=null;}
        if(dialog!=null){dialog.dismiss();dialog=null;}
        if(host!=null)host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(engine!=null&&!peer.isEmpty())engine.stopRemoteView(peer);
        localVideo=null;remoteVideo=null;screen=null;controls=null;status=null;videoLayer=null;
    }
    void detach(Activity activity){
        if(host!=activity)return;
        if(session!=null&&serviceRequested){foreground(false);releaseViews();host=null;recordsChanged=null;}
        else dispose();
    }
    boolean belongsTo(int uid) { return !disposed && uid == owner && authenticated(); }
    boolean hasPendingPermission() { return pendingPermission != null; }
    private boolean authenticated() { return token.equals(ApiClient.token(app)) && base.equals(ApiClient.baseUrl(app)); }
    private boolean live() { return !disposed && authenticated(); }
    private boolean same(String id) { return live() && session != null && id.equals(session.optString("session_id")); }
    private void main(Runnable work) { handler.post(() -> { if (live()) work.run(); }); }
    private void toast(String text) { if (live()&&foreground&&host!=null&&!host.isFinishing()) AppNotice.show(host, text); }
    private boolean dialing, sdkMediaReady;
    private long nextDialAt;
    private int dialAttempt;
    private int sdkRoomId;
    private final java.util.Set<String> retiredCalls = new java.util.HashSet<>();
    private static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) object.put((String)pairs[i], pairs[i + 1]); } catch (Exception ignored) { }
        return object;
    }
    private TUICommonDefine.Callback sdkCallback(Runnable success, Failure failure) {
        int epoch = generation;
        return new TUICommonDefine.Callback() {
            public void onSuccess() { main(() -> { if (epoch == generation) success.run(); }); }
            public void onError(int code, String message) { main(() -> { if (epoch == generation) {diagnostic("operation:"+CallFailure.category(code,message),code);failure.accept(CallFailure.label(code,message));} }); }
        };
    }
    void foreground(boolean value) {
        if (!live()) return;
        foreground = value;
        handler.removeCallbacks(pulse);
        if (value) { if(session==null&&credentialsDeadline<SystemClock.elapsedRealtime())ready=false; initialize(); if(ready)presence(true); handler.post(pulse);if(session!=null){show();media();}Runnable granted=permissionReady;permissionReady=null;if(granted!=null)granted.run(); }
        else {afterReady=null;presence(false);if(engine!=null&&video()){engine.closeCamera();cameraOpened=false;cameraOpening=false;}if(session!=null&&serviceRequested)handler.post(pulse);}
    }
    void stopped() {
        afterReady=null;
        foreground(false);
        if (session != null&&!serviceRequested) finish(connected ? "ended" : outgoing ? "canceled" : "rejected", "未接听的通话已结束");
    }
    private boolean sdkAuthenticated() {
        try {
            // TUICallEngine uses the native RoomEngine account, not Java V2TIMManager.
            TUIRoomDefine.LoginUserInfo user=TUIRoomEngine.getSelfInfo();
            return engine!=null && user!=null && ("spx_"+owner).equals(user.userId);
        } catch (RuntimeException | LinkageError error) { return false; }
    }
    private void initialize() {
        if(ready&&!sdkAuthenticated()){ready=false;presence(false);diagnostic("auth:lost",0);}
        if (!live() || ready || initializing || closingSdk || engineRetiring || !foreground || SystemClock.elapsedRealtime() < retryAt) return;
        initializing = true;
        final int epoch = ++generation;
        ApiClient.get(app, "calls.credentials", null, new ApiClient.Callback() {
            public void onSuccess(Object data) {
                if (!live() || generation != epoch) return;
                if (!(data instanceof JSONObject)) { failedInit(); return; }
                JSONObject value = (JSONObject)data;
                if (!("spx_" + owner).equals(value.optString("user_id")) || value.optInt("sdk_app_id") <= 0 || value.optString("user_sig").isEmpty()) { failedInit(); return; }
                try {
                    if (engine == null) { engine = TUICallEngine.createInstance(app); engine.addObserver(observer); }
                    if(engineRetiring){initializing=false;return;}
                    initInFlight=true;
                    engine.init(value.optInt("sdk_app_id"), value.optString("user_id"), value.optString("user_sig"), initCallback(epoch,() -> {
                        diagnostic("init:success",0);initializing = false; ready = sdkAuthenticated(); if(!ready){serviceError="通话登录未完成，请稍后重试";failedInit();return;} serviceError=""; credentialsExpire = value.optLong("expires_at");
                        credentialsDeadline=SystemClock.elapsedRealtime()+45*60*1000L;
                        engine.setSelfInfo(value.optString("nickname"), value.optString("avatar_url"), sdkCallback(() -> {}, e -> {}));
                        presence(foreground);
                        if(refreshingCredentials){refreshingCredentials=false;dialing=false;nextDialAt=0;lastHeartbeat=0;}
                    }, error -> { serviceError=error;failedInit(); }));
                } catch (RuntimeException | LinkageError error) { initInFlight=false;failedInit(); }
            }
            public void onError(String message) { if (live() && generation == epoch) { serviceError=message;failedInit(); } }
        });
    }
    private TUICommonDefine.Callback initCallback(int epoch,Runnable success,Failure failure){
        return new TUICommonDefine.Callback(){
            public void onSuccess(){handler.post(()->{initInFlight=false;if(destroyPending){retireEngine();return;}if(live()&&epoch==generation)success.run();});}
            public void onError(int code,String message){handler.post(()->{initInFlight=false;if(destroyPending){retireEngine();return;}if(live()&&epoch==generation)failure.accept(CallFailure.label(code,message));});}
        };
    }
    private void retireEngine(){
        if(!destroyPending||initInFlight||closingSdk||engine==null)return;
        // The SDK queues SQLite work during init/hangup. Do not destroy it mid-operation.
        engine.removeObserver(observer);TUICallEngine.destroyInstance();engine=null;destroyPending=false;engineRetiring=false;
    }
    private void failedInit() { boolean requested=afterReady!=null;initializing = false; ready = false; afterReady=null;if(requested)toast(serviceError.isEmpty()?"通话服务连接失败，请稍后重试":serviceError); retryAt = SystemClock.elapsedRealtime() + 30000;if(refreshingCredentials){refreshingCredentials=false;if(session!=null)finish("failed","通话服务重新连接失败，请稍后重试");} }
    private void diagnostic(String operation,int code){android.util.Log.w("SPXCall",operation+" code="+code+" outgoing="+outgoing+" connected="+connected+" dialing="+dialing+" minimized="+minimized);}
    private void presence(boolean value) {
        if (!authenticated() || (value && (!ready || !sdkAuthenticated()))) return;
        lastPresence = SystemClock.elapsedRealtime();
        ApiClient.post(app, "calls.presence", json("device_id", device, "ready", value), new ApiClient.Callback() {
            public void onSuccess(Object data) {if(value&&live()&&foreground){Runnable pending=afterReady;afterReady=null;if(pending!=null)pending.run();}}
            public void onError(String message) { if (live()) lastPresence = 0; }
        });
    }
    private final Runnable pulse = new Runnable() {
        public void run() {
            if (!live()){if(!disposed)dispose();return;}
            if (!foreground && (session==null||!serviceRequested)) return;
            long now = SystemClock.elapsedRealtime();
            if (ready && session == null && (credentialsDeadline < now || !sdkAuthenticated())) {ready=false;presence(false);diagnostic("auth:recheck",0);}
            if (!ready) initialize();
            if (ready && foreground && now-lastPresence > 15000) presence(true);
            if (session != null) {
                lastPulseAt=now;
                if (status != null && connected) status.setText(callStatus());
                if(miniWindow!=null)miniWindow.update(session.optString("peer_name","好友"),callStatus());
                if (!connected && now-startedAt > 65000) finish("timeout", "无人接听");
                else if (serverSession && !pulsePending && now-lastHeartbeat > (outgoing && !connected && !dialing ? 2000 : 8000)) heartbeat();
            }
            handler.postDelayed(this, 1000);
        }
    };
    private String duration() {
        long seconds = Math.max(0, (SystemClock.elapsedRealtime()-connectedAt)/1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds/60, seconds%60);
    }
    private boolean permissions(boolean video, Runnable after) {
        boolean mic = host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean cam = !video || host.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (mic && cam) { after.run(); return true; }
        pendingPermission = after;
        host.requestPermissions(video ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA} : new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
        return false;
    }
    boolean permissionResult(int request, int[] results) {
        if (request != PERMISSION_REQUEST) return false;
        Runnable action = pendingPermission; pendingPermission = null;
        boolean allowed = results.length > 0;
        for (int result : results) allowed &= result == PackageManager.PERMISSION_GRANTED;
        if (live() && allowed && action != null) {if(foreground)action.run();else permissionReady=action;}
        else if (live() && !allowed) {
            if (session != null) finish(outgoing ? "canceled" : "rejected", "需要麦克风和相机权限");
            new AlertDialog.Builder(host).setTitle("通话权限未开启").setMessage("语音通话需要麦克风权限，视频通话还需要相机权限。可在系统应用设置中开启后重试。")
                .setNegativeButton("稍后", null).setPositiveButton("去设置", (d,w) -> host.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:"+host.getPackageName())))).show();
        }
        return true;
    }
    void start(int chatId, boolean video) {
        if (!live()) return;
        if (session != null) {restore();toast("当前正在通话");return;}
        if (pendingPermission != null || afterReady!=null||closingSdk) { toast("正在处理通话请求，请稍后重试"); return; }
        if (chatId <= 0) { toast("请选择好友后发起通话"); return; }
        if(credentialsDeadline<SystemClock.elapsedRealtime()||!sdkAuthenticated())ready=false;
        if (!ready) {retryAt=0;afterReady=()->permissions(video,()->prepare(chatId,video));initialize();toast("正在连接通话服务…");return;}
        permissions(video, () -> prepare(chatId, video));
    }
    private void prepare(int chatId, boolean video) {
        if (!live() || !foreground || session != null) return;
        String id = UUID.randomUUID().toString();
        session = json("session_id", id, "media", video ? "video" : "audio", "peer_name", "正在连接好友…");
        if(!ensureService())return;
        outgoing = true; connected = false; accepting = false; credentialRecoveries=0;minimized=false;dialing=false;sdkMediaReady=false; nextDialAt=0; retiredCalls.clear(); speaker=video; startedAt = SystemClock.elapsedRealtime(); lastHeartbeat = startedAt;
        show();
        ApiClient.post(app, "calls.prepare", json("chat_id",chatId,"device_id",device,"session_id",id,"media",video?"video":"audio"), new ApiClient.Callback() {
            public void onSuccess(Object data) {
                if (!same(id)) { update(id,"canceled",0); return; }
                if (!(data instanceof JSONObject)) { finish("failed","通话创建失败"); return; }
                session=(JSONObject)data; serverSession=true; peer=session.optString("peer_user_id");
                if (!"ringing".equals(session.optString("state"))) { finish("failed","通话已结束"); return; }
                show();
                startRing(false); dialWhenReady();
            }
            public void onError(String message) { if(same(id)) finish("failed",message); }
        });
    }
    private void dialWhenReady() {
        if(session==null||!outgoing||connected||dialing||closingSdk||refreshingCredentials||!ready||!serverSession||!session.optBoolean("peer_ready")||SystemClock.elapsedRealtime()<nextDialAt)return;
        if(!sdkAuthenticated()){diagnostic("dial:auth_lost",-1);refreshCredentials();return;}
        int remaining=(int)((65000-(SystemClock.elapsedRealtime()-startedAt))/1000);
        if(remaining<=0){finish("timeout","无人接听");return;}
        String id=session.optString("session_id"); int attempt=++dialAttempt; dialing=true;
        TUICallDefine.CallParams params=new TUICallDefine.CallParams();params.timeout=Math.min(60,remaining);params.userData=id;
        sdkRoomId=java.util.concurrent.ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE);
        params.roomId=new TUICommonDefine.RoomId();params.roomId.intRoomId=sdkRoomId;
        final int epoch=generation;
        // Direct conversations use the 1:1 API; calls() requires Tencent's single-group package.
        engine.call(peer,video()?TUICallDefine.MediaType.Video:TUICallDefine.MediaType.Audio,params,new TUICommonDefine.Callback(){
            public void onSuccess(){main(()->{if(epoch==generation&&same(id)&&dialAttempt==attempt){sdkMediaReady=true;media();}});}
            public void onError(int code,String message){main(()->{if(epoch!=generation||!same(id)||dialAttempt!=attempt)return;diagnostic("dial:"+CallFailure.category(code,message),code);if(isExpired(code,message)||CallFailure.uninitialized(code,message)){refreshCredentials();return;}finish("failed",CallFailure.label(code,message));});}
        });
    }
    private boolean isExpired(int code,String message){return CallFailure.expired(code,message);}
    private void refreshCredentials(){
        if(refreshingCredentials)return;
        if(session!=null&&credentialRecoveries++>=1){finish("failed","通话登录失效，请稍后重新拨打");ready=false;retryAt=SystemClock.elapsedRealtime()+30000;return;}
        ready=false;retryAt=0;presence(false);
        if(session!=null&&connected){finish("failed","通话凭证已失效，请重新拨打");initialize();return;}
        if(session!=null){refreshingCredentials=true;dialing=false;sdkMediaReady=false;dialAttempt++;mediaEpoch++;micOpened=false;micOpening=false;cameraOpened=false;cameraOpening=false;engine.closeMicrophone();engine.closeCamera();if(!sdkId.isEmpty())retiredCalls.add(sdkId);sdkId="";sdkRoomId=0;closeSdk("hangup");}
        initialize();
    }
    private void sdkEnded(String id,TUICallDefine.MediaType type,TUICallDefine.CallEndReason reason,TUICallDefine.CallObserverExtraInfo extra) {
        if(!matches(id,type,extra))return;
        if(outgoing&&!connected&&reason==TUICallDefine.CallEndReason.OFFLINE){
            retiredCalls.add(id);sdkId="";sdkRoomId=0;dialing=false;sdkMediaReady=false;dialAttempt++;nextDialAt=SystemClock.elapsedRealtime()+8000;
            mediaEpoch++;micOpened=false;micOpening=false;cameraOpened=false;cameraOpening=false;
            engine.closeMicrophone();engine.closeCamera();
            return; // Keep the business call ringing; a fresh presence heartbeat may retry the SDK invitation.
        }
        finish(connected?"ended":reportedReason(reason),label(reason));
    }
    private boolean matches(String callId,TUICallDefine.MediaType type,TUICallDefine.CallObserverExtraInfo extra) {
        if(session==null||closingSdk||refreshingCredentials||callId==null||callId.isEmpty()||retiredCalls.contains(callId)) return false;
        if(type!=(video()?TUICallDefine.MediaType.Video:TUICallDefine.MediaType.Audio))return false;
        if(!sdkId.isEmpty()) return sdkId.equals(callId);
        // call() omits userData on begin/end. Bind only the current outgoing attempt's room and role.
        if(outgoing&&dialing&&sdkRoomId>0&&extra!=null&&extra.role==TUICallDefine.Role.Caller&&extra.roomId!=null&&extra.roomId.intRoomId==sdkRoomId) { sdkId=callId; return true; }
        return false;
    }
    private final TUICallObserver observer = new TUICallObserver() {
        @Override public void onCallReceived(String id,String caller,List<String> callees,TUICallDefine.MediaType type,TUICallDefine.CallObserverExtraInfo extra) {
            main(() -> incoming(id,caller,type,extra));
        }
        @Override public void onCallBegin(String id,TUICallDefine.MediaType type,TUICallDefine.CallObserverExtraInfo extra) {
            main(() -> { if(!matches(id,type,extra)||connected)return; connected=true; accepting=false; connectedAt=SystemClock.elapsedRealtime(); stopRing(); playConnectedTone(); update(session.optString("session_id"),"connected",0); show(); media(); });
        }
        @Override public void onCallEnd(String id,TUICallDefine.MediaType type,TUICallDefine.CallEndReason reason,String user,long total,TUICallDefine.CallObserverExtraInfo extra) {
            main(() -> sdkEnded(id,type,reason,extra));
        }
        @Override public void onCallNotConnected(String id,TUICallDefine.MediaType type,TUICallDefine.CallEndReason reason,String user,TUICallDefine.CallObserverExtraInfo extra) {
            main(() -> sdkEnded(id,type,reason,extra));
        }
        @Override public void onUserVideoAvailable(String user,boolean available) {
            main(() -> { if(session!=null && user.equals(peer) && remoteVideo!=null){remoteVideo.setVisibility(available?View.VISIBLE:View.INVISIBLE); if(available) engine.startRemoteView(user,remoteVideo,null);} });
        }
        @Override public void onError(int code,String message) { main(() -> { diagnostic("observer:"+CallFailure.category(code,message),code);if(closingSdk||refreshingCredentials)return;if(isExpired(code,message)||CallFailure.uninitialized(code,message)){refreshCredentials();return;}if(session!=null)finish("failed",CallFailure.label(code,message)); }); }
        @Override public void onKickedOffline() { main(() -> { if(session!=null)finish("failed","该账号已在其他设备登录");ready=false;retryAt=Long.MAX_VALUE;presence(false); }); }
        @Override public void onUserSigExpired() { main(ChatCall.this::refreshCredentials); }
        @Override public void onUserNetworkQualityChanged(List<TUICommonDefine.NetworkQualityInfo> quality){
            main(()->{if(session==null||!connected||quality==null)return;String hint="";
                for(TUICommonDefine.NetworkQualityInfo info:quality){if(info==null||info.quality==null||(!("spx_"+owner).equals(info.userId)&&!peer.equals(info.userId)))continue;
                    if(info.quality==TUICommonDefine.NetworkQuality.DOWN){hint="网络中断，正在尝试恢复…";break;}
                    if(info.quality==TUICommonDefine.NetworkQuality.BAD||info.quality==TUICommonDefine.NetworkQuality.VERY_BAD)hint="网络不稳定，请稍候…";
                }
                networkHint=hint;networkHintAt=SystemClock.elapsedRealtime();if(status!=null)status.setText(callStatus());
            });
        }
    };
    private static String reason(TUICallDefine.CallEndReason reason) {
        switch(reason) {
            case REJECT: case OTHER_DEVICE_REJECT: return "rejected";
            case LINE_BUSY: return "busy";
            case NO_RESPONSE: return "timeout";
            case CANCELED: case OTHER_DEVICE_ACCEPTED: return "canceled";
            case OFFLINE: return "failed";
            case HANGUP: return "ended";
            default: return "failed";
        }
    }
    private String reportedReason(TUICallDefine.CallEndReason reason) {
        if(!outgoing&&reason==TUICallDefine.CallEndReason.CANCELED)return "sdk_canceled";
        if(outgoing&&reason==TUICallDefine.CallEndReason.REJECT)return "sdk_rejected";
        if(outgoing&&reason==TUICallDefine.CallEndReason.LINE_BUSY)return "sdk_busy";
        if(outgoing&&reason==TUICallDefine.CallEndReason.NO_RESPONSE)return "sdk_timeout";
        return reason(reason);
    }
    private static String label(TUICallDefine.CallEndReason reason) {
        switch(reason) {
            case REJECT: return "对方已拒绝";
            case LINE_BUSY: return "对方忙线中";
            case NO_RESPONSE: return "无人接听";
            case CANCELED: return "通话已取消";
            case OTHER_DEVICE_ACCEPTED: case OTHER_DEVICE_REJECT: return "已在其他设备处理";
            case OFFLINE: return "对方暂时不在线";
            case HANGUP: return "通话已结束";
            default: return "通话连接已中断";
        }
    }
    private void incoming(String id,String caller,TUICallDefine.MediaType type,TUICallDefine.CallObserverExtraInfo extra) {
        if(session!=null) return; // The engine sends busy for an existing call; never reject the active call here.
        if(!foreground || !ready || extra==null || extra.userData==null || !extra.userData.matches("[a-f0-9-]{36}")) { engine.reject(sdkCallback(()->{},e->{})); return; }
        String sid=extra.userData;
        retiredCalls.clear();credentialRecoveries=0;minimized=false;sdkId=id;sdkRoomId=0; peer=caller; outgoing=false;connected=false;accepting=false;speaker=type==TUICallDefine.MediaType.Video;startedAt=SystemClock.elapsedRealtime();lastHeartbeat=startedAt;
        session=json("session_id",sid,"media",type==TUICallDefine.MediaType.Video?"video":"audio");
        HashMap<String,String> params=new HashMap<>();params.put("session_id",sid);params.put("device_id",device);
        ApiClient.get(app,"calls.session",params,new ApiClient.Callback(){
            public void onSuccess(Object data){
                if(!same(sid))return;
                JSONObject value=data instanceof JSONObject?(JSONObject)data:null;
                boolean valid=value!=null && value.optInt("callee_id")==owner && caller.equals(value.optString("peer_user_id")) && session.optString("media").equals(value.optString("media")) && "ringing".equals(value.optString("state"));
                if(!valid){rejectUntrusted(sid);return;}
                session=value;serverSession=true;show();startRing(true);
            }
            public void onError(String message){if(same(sid))rejectUntrusted(sid);}
        });
    }
    private void rejectUntrusted(String id) {
        if(!same(id))return;
        if(engine!=null)closeSdk("reject");
        session=null;serverSession=false;sdkId="";sdkRoomId=0;peer="";stopRing();if(dialog!=null){dialog.dismiss();dialog=null;}screen=null;controls=null;status=null;videoLayer=null;
    }
    private void accept() {
        if(session==null || engine==null || outgoing || connected || accepting) return;
        String id=session.optString("session_id");
        permissions(video(),()->{if(!same(id)||!ensureService())return;accepting=true;stopRing();if(status!=null)status.setText("正在接通…");
            engine.accept(sdkCallback(()->{if(same(id))media();},e->{if(same(id))finish("failed",e);}));});
    }
    private void heartbeat() {
        if(session==null)return;
        String id=session.optString("session_id");pulsePending=true;lastHeartbeat=SystemClock.elapsedRealtime();
        ApiClient.post(app,"calls.update",json("session_id",id,"device_id",device,"event","heartbeat"),new ApiClient.Callback(){
            public void onSuccess(Object data){if(!same(id))return;pulsePending=false;heartbeatDelayed=false;if(data instanceof JSONObject){session=(JSONObject)data;String state=session.optString("state");if(!"ringing".equals(state)&&!"connected".equals(state))finishLocal("timeout".equals(state)?"无人接听":"通话已结束");else dialWhenReady();}}
            public void onError(String message){if(!same(id))return;pulsePending=false;heartbeatDelayed=true;}
            @Override public void onHttpError(int status,String message){if(same(id)&& (status==401||status==403||status==404||status==409))finishLocal(message);else onError(message);}
        });
    }
    void event(JSONObject event) { if(session!=null&&session.optString("session_id").equals(event.optString("session_id"))){String state=event.optString("state");if(!"connected".equals(state)&&!"ringing".equals(state))finishLocal("通话已结束");} }
    private void update(String id,String state,int attempt) {
        if(!authenticated())return;
        ApiClient.post(app,"calls.update",json("session_id",id,"device_id",device,"event",state),new ApiClient.Callback(){
            public void onSuccess(Object data){if(live())changed();}
            public void onError(String message){if(attempt<2&&live())handler.postDelayed(()->update(id,state,attempt+1),2000L*(attempt+1));}
        });
    }
    private void finish(String state,String text) {
        if(session==null)return;
        String id=session.optString("session_id");
        if(serverSession&&authenticated())update(id,state,0);
        if(engine!=null){
            closeSdk(!outgoing&&!connected&&"rejected".equals(state)?"reject":"hangup");
        }
        finishLocal(text,false);
    }
    private void finishLocal(String text) {
        finishLocal(text,true);
    }
    private void closeSdk(String action){
        if(engine==null||closingSdk)return;closingSdk=true;final int cleanup=++cleanupEpoch;
        Runnable closed=()->{if(cleanup==cleanupEpoch){closingSdk=false;retireEngine();}};
        handler.postDelayed(closed,3000);
        TUICommonDefine.Callback callback=new TUICommonDefine.Callback(){
            public void onSuccess(){handler.post(()->{handler.removeCallbacks(closed);if(cleanup==cleanupEpoch){closingSdk=false;retireEngine();}});}
            public void onError(int code,String message){handler.post(()->{handler.removeCallbacks(closed);diagnostic("cleanup",code);if(cleanup==cleanupEpoch){closingSdk=false;retireEngine();}});}
        };
        if("reject".equals(action))engine.reject(callback);else engine.hangup(callback);
    }
    private void finishLocal(String text,boolean closeSdk) {
        if(session==null)return;
        if(closeSdk&&engine!=null)closeSdk("hangup");
        minimized=false;refreshingCredentials=false;if(miniWindow!=null){miniWindow.dismiss();miniWindow=null;}
        if(host!=null)host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session=null;pendingPermission=null;permissionReady=null;pulsePending=false;serverSession=false;sdkId="";sdkRoomId=0;connected=false;accepting=false;dialing=false;sdkMediaReady=false;dialAttempt++;micOpened=false;cameraOpened=false;micOpening=false;cameraOpening=false;mediaEpoch++;
        stopRing();
        if(engine!=null){engine.closeMicrophone();engine.closeCamera();if(!peer.isEmpty())engine.stopRemoteView(peer);engine.selectAudioPlaybackDevice(TUICommonDefine.AudioPlaybackDevice.Earpiece);}
        peer=""; localVideo=null;remoteVideo=null;muted=false;camera=true;front=true;swapped=false;networkHint="";heartbeatDelayed=false;routedSpeaker=null;
        if(dialog!=null){dialog.dismiss();dialog=null;}screen=null;controls=null;status=null;videoLayer=null;
        serviceRequested=false;CallService.stop(this);toast(text);changed();
        if(host==null)dispose();
    }
    void dispose() {
        if(disposed||disposing)return;disposing=true;
        if(session!=null)finish(connected?"ended":outgoing?"canceled":"rejected","通话已结束");
        afterReady=null;permissionReady=null;presence(false);disposed=true;generation++;handler.removeCallbacksAndMessages(null);stopRing();if(miniWindow!=null){miniWindow.dismiss();miniWindow=null;}
        serviceRequested=false;CallService.stop(this);releaseViews();if(active==this)active=null;
        if(engine!=null){engine.removeObserver(observer);destroyPending=true;engineRetiring=true;retireEngine();if(closingSdk)handler.postDelayed(()->{closingSdk=false;retireEngine();},5000);}
    }
    private boolean video(){return session!=null&&"video".equals(session.optString("media"));}
    private void media() {
        if(session==null||engine==null||!serverSession||refreshingCredentials||(outgoing&&!sdkMediaReady&&!connected))return;
        String id=session.optString("session_id");
        final int epoch=mediaEpoch;
        if((outgoing||accepting||connected)&&!muted&&!micOpened&&!micOpening){
            micOpening=true;
            engine.openMicrophone(sdkCallback(()->{
                if(epoch!=mediaEpoch||!same(id)){if(session==null||muted)engine.closeMicrophone();return;}
                micOpening=false;micOpened=true;if(muted){engine.closeMicrophone();micOpened=false;}
            },e->{if(epoch==mediaEpoch&&same(id)){micOpening=false;finish("failed","麦克风开启失败，请检查权限或占用");}}));
        }
        routeAudio();
        if(foreground&&host!=null&&video()&&(outgoing||accepting||connected)&&camera&&localVideo!=null&&!cameraOpened&&!cameraOpening){
            cameraOpening=true;
            engine.openCamera(front?TUICommonDefine.Camera.Front:TUICommonDefine.Camera.Back,localVideo,sdkCallback(()->{
                if(epoch!=mediaEpoch||!same(id)){if(session==null||!video()||!camera)engine.closeCamera();return;}
                cameraOpening=false;cameraOpened=true;if(!camera||!foreground){engine.closeCamera();cameraOpened=false;}
            },e->{if(epoch==mediaEpoch&&same(id)){cameraOpening=false;finish("failed","摄像头开启失败，请检查权限或占用");}}));
        }
        if(video()&&connected&&remoteVideo!=null)engine.startRemoteView(peer,remoteVideo,null);
    }
    private void startRing(boolean incoming){
        stopRing();
        AudioManager manager=(AudioManager)app.getSystemService(android.content.Context.AUDIO_SERVICE);
        if(incoming&&manager!=null&&manager.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL)return;
        waitingSound=ChatSoundEffects.player(app,R.raw.call_ringing,incoming?android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE:android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,true);
        if(waitingSound!=null)try{waitingSound.start();}catch(RuntimeException ignored){stopRing();}
    }
    private void stopRing(){if(waitingSound!=null){waitingSound.release();waitingSound=null;}if(connectedTone!=null){connectedTone.release();connectedTone=null;}}
    private void playConnectedTone(){
        try{final ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_VOICE_CALL,45);connectedTone=tone;tone.startTone(ToneGenerator.TONE_PROP_BEEP,160);handler.postDelayed(()->{if(connectedTone==tone){connectedTone=null;tone.release();}},240);}catch(RuntimeException ignored){}
    }
    private int dp(float value){return Math.round(host.getResources().getDisplayMetrics().density*value);}
    private GradientDrawable background(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(host);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    private void routeAudio(){if(engine!=null&&(routedSpeaker==null||routedSpeaker!=speaker)){engine.selectAudioPlaybackDevice(speaker?TUICommonDefine.AudioPlaybackDevice.Speakerphone:TUICommonDefine.AudioPlaybackDevice.Earpiece);routedSpeaker=speaker;}}
    private String callStatus(){return connected?(heartbeatDelayed?"连接恢复中…":(!networkHint.isEmpty()&&SystemClock.elapsedRealtime()-networkHintAt<12000?networkHint:duration())):accepting?"正在接通…":outgoing?"等待接听…":"收到来电";}
    private void minimize(){
        if(!live()||host==null||!foreground||session==null||minimized)return;
        minimized=true;
        if(miniWindow==null)miniWindow=new CallMiniWindow(host,this::restore,()->finish(connected?"ended":outgoing?"canceled":"rejected","通话已结束"));
        showMini();
        if(dialog!=null)dialog.hide();
        host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void showMini(){
        if(!minimized||session==null||miniWindow==null)return;
        if(video()&&localVideo==null){localVideo=new TUIVideoView(host);remoteVideo=new TUIVideoView(host);}
        miniWindow.show(video(),session.optString("peer_name","好友"),callStatus(),video()?(connected?remoteVideo:localVideo):null);
        media();
    }
    private void restore(){
        if(!live()||host==null||!foreground||session==null)return;
        minimized=false;if(miniWindow!=null)miniWindow.dismiss();
        if(dialog!=null)dialog.show();
        host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show();layoutVideo();media();
    }
    private void show(){
        if(!live()||host==null||host.isFinishing()||host.isDestroyed()||!foreground||session==null)return;
        if(minimized){if(miniWindow==null)miniWindow=new CallMiniWindow(host,this::restore,this::hangupFromNotification);showMini();return;}
        if(dialog==null){
            dialog=new Dialog(host,android.R.style.Theme_Material_NoActionBar);dialog.setCancelable(false);
            dialog.setOnKeyListener((d,key,event)->{if(key==android.view.KeyEvent.KEYCODE_BACK&&event.getAction()==android.view.KeyEvent.ACTION_UP){minimize();return true;}return key==android.view.KeyEvent.KEYCODE_BACK;});
            screen=new FrameLayout(host);screen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xff283c43,0xff111c22}));
            dialog.setContentView(screen);dialog.show();Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_SECURE);window.setStatusBarColor(0xff283c43);window.setNavigationBarColor(0xff111c22);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
        }
        if(videoLayer==null){videoLayer=new FrameLayout(host);screen.addView(videoLayer,new FrameLayout.LayoutParams(-1,-1));}
        if(video()&&(outgoing||connected)&&localVideo==null){localVideo=new TUIVideoView(host);remoteVideo=new TUIVideoView(host);layoutVideo();}
        // Keep video surfaces attached while updating call controls.
        if(controls!=null)screen.removeView(controls);
        controls=new LinearLayout(host);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(dp(24),dp(40),dp(24),dp(24));
        screen.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        ImageView minimizeButton=new ImageView(host);minimizeButton.setImageDrawable(new ChatControlIcon("minimize",Color.WHITE));minimizeButton.setPadding(dp(10),dp(10),dp(10),dp(10));minimizeButton.setContentDescription("缩小通话，继续聊天");LinearLayout.LayoutParams minimizeParams=new LinearLayout.LayoutParams(dp(44),dp(40));minimizeParams.gravity=Gravity.LEFT;controls.addView(minimizeButton,minimizeParams);minimizeButton.setOnClickListener(v->minimize());
        ImageView avatarIcon=new ImageView(host);avatarIcon.setImageDrawable(new ChatControlIcon("person",Color.WHITE));avatarIcon.setPadding(dp(19),dp(19),dp(19),dp(19));avatarIcon.setBackground(background(0x6656787c,22));
        LinearLayout.LayoutParams av=new LinearLayout.LayoutParams(dp(80),dp(80));av.gravity=Gravity.CENTER;av.topMargin=dp(video()&&connected?4:24);controls.addView(avatarIcon,av);
        String avatarUrl=session.optString("peer_avatar");
        if(!avatarUrl.isEmpty()&&!"null".equals(avatarUrl)){avatarIcon.setPadding(0,0,0,0);avatarIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);avatarIcon.setClipToOutline(true);MallImages.bind(avatarIcon,avatarUrl,false);}
        if(video()&&connected)avatarIcon.setVisibility(View.GONE);
        TextView name=text(session.optString("peer_name","好友"),26,Color.WHITE);name.setTypeface(null,Typeface.BOLD);name.setPadding(0,dp(20),0,dp(8));controls.addView(name,new LinearLayout.LayoutParams(-1,-2));
        status=text(connected?callStatus():accepting?"正在接通…":outgoing?"等待对方接听…":video()?"邀请你视频通话":"邀请你语音通话",15,0xffcfdbde);controls.addView(status,new LinearLayout.LayoutParams(-1,dp(34)));
        View space=new View(host);controls.addView(space,new LinearLayout.LayoutParams(1,0,1));
        TextView mode=text(video()?"视频通话":"语音通话",13,0xffb4c6ca);controls.addView(mode,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout row=new LinearLayout(host);row.setGravity(Gravity.CENTER);controls.addView(row,new LinearLayout.LayoutParams(-1,dp(100)));
        if(!outgoing&&!connected&&!accepting){control(row,"挂断","hangup",0xffdf525c,()->finish("rejected","已拒绝来电"));control(row,"接听",video()?"video":"phone",0xff24b875,this::accept);}
        else{
            control(row,muted?"取消静音":"静音",muted?"mute":"voice",muted?0xff4c7f75:0x443e5059,()->{muted=!muted;if(muted){if(engine!=null)engine.closeMicrophone();micOpened=false;}else media();show();});
            control(row,"挂断","hangup",0xffdf525c,()->finish(connected?"ended":outgoing?"canceled":"rejected","通话已结束"));
            control(row,speaker?"关闭免提":"免提","speaker",speaker?0xff4c7f75:0x443e5059,()->{speaker=!speaker;routeAudio();show();});
        }
        if(video()&&(outgoing||connected)){
            LinearLayout vrow=new LinearLayout(host);vrow.setGravity(Gravity.CENTER);controls.addView(vrow,new LinearLayout.LayoutParams(-1,dp(92)));
            control(vrow,camera?"关闭摄像头":"开启摄像头",camera?"video":"cameraoff",0x443e5059,()->{camera=!camera;if(camera)media();else{if(engine!=null)engine.closeCamera();cameraOpened=false;}if(localVideo!=null)localVideo.setVisibility(camera?View.VISIBLE:View.INVISIBLE);show();});
            control(vrow,"翻转镜头","switchcamera",0x443e5059,()->{front=!front;if(engine!=null)engine.switchCamera(front?TUICommonDefine.Camera.Front:TUICommonDefine.Camera.Back);});
        }
    }
    private void layoutVideo(){
        if(localVideo==null||remoteVideo==null||videoLayer==null)return;
        for(View v:new View[]{localVideo,remoteVideo})if(v.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)v.getParent()).removeView(v);
        videoLayer.removeAllViews();
        View big=swapped?localVideo:remoteVideo,small=swapped?remoteVideo:localVideo;
        videoLayer.addView(big,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(104),dp(152),Gravity.TOP|Gravity.RIGHT);lp.setMargins(0,dp(24),dp(16),0);videoLayer.addView(small,lp);small.setOnClickListener(v->{swapped=!swapped;layoutVideo();});
        small.setContentDescription("切换大小画面");small.setElevation(dp(10));big.setElevation(0);
    }
    private void control(LinearLayout row,String label,String icon,int color,Runnable action){
        LinearLayout box=new LinearLayout(host);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setContentDescription(label);
        row.addView(box,new LinearLayout.LayoutParams(0,-1,1));
        ImageView button=new ImageView(host);button.setImageDrawable(new ChatControlIcon(icon,Color.WHITE));button.setPadding(dp(17),dp(17),dp(17),dp(17));button.setBackground(background(color,32));box.addView(button,new LinearLayout.LayoutParams(dp(60),dp(60)));
        TextView caption=text(label,12,0xffe2ebed);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(30));lp.topMargin=dp(5);box.addView(caption,lp);box.setOnClickListener(v->{if(session!=null)action.run();});button.setOnClickListener(v->box.performClick());
    }
}
