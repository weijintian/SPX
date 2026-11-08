package com.spx.chat;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/** Run only in an isolated application ID with a loopback-only ApiClient. Never places a call. */
public final class CallRecordInstrumentation extends Instrumentation {
    private MainActivity host;
    private Context context;
    private LinearLayout messages;
    private ScrollView scroll;
    private int checks;
    private ServerSocket server;
    private volatile boolean serving = true;
    private interface Work { void run() throws Exception; }
    private void ui(Work work) throws Exception {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }
    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(host);
    }
    private void field(String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(host,value);
    }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(host,args);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext();
            if (!"com.spx.chat.updatepreview".equals(context.getPackageName())) throw new IllegalStateException("Isolated preview package required");
            server = new ServerSocket(38177, 20, InetAddress.getByName("127.0.0.1"));
            new Thread(() -> {
                while (serving) try { respond(server.accept()); } catch (IOException error) { if (serving) android.util.Log.e("CallFixture","loopback",error); }
            }, "call-fixture").start();
            context.getSharedPreferences("spx_api",0).edit().clear().putString("base_url","http://127.0.0.1:38177").commit();
            host = (MainActivity) startActivitySync(new Intent().setClassName(context.getPackageName(), MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            classificationChecks();
            ui(() -> { invoke("continueAfterSplash",new Class<?>[]{});field("currentUserId",700001);field("activeChatId",990001);field("activeGroupId",0);field("activeChatName","小林");field("appLanguage","zh-CN");gallery(); });
            waitForIdleSync();
            ui(this::renderingChecks);
            ui(this::confirmationChecks);
            waitForIdleSync();
            staleConfirmationChecks();
            ui(this::layoutChecks);
            ui(() -> { field("appLanguage","zh-CN");gallery(); });
            waitForIdleSync();
            Thread.sleep(300);
            Bitmap shot = getUiAutomation().takeScreenshot();
            File screenshot = new File(context.getExternalFilesDir(null),"call-bubbles-android.png");
            try (OutputStream out = new FileOutputStream(screenshot)) { shot.compress(Bitmap.CompressFormat.PNG,100,out); } finally { shot.recycle(); }
            result.putString("stream","SUCCESS "+checks+" call record checks; isolated loopback fixtures; no real calls\nScreenshot: "+screenshot.getAbsolutePath()+"\n");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable error) {
            result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);
        } finally { serving=false;try { if(server!=null)server.close(); } catch(IOException ignored) {} }
    }
    private void classificationChecks() {
        check(ChatCallRecord.media("call","text","[语音通话] 已取消",0)==1,"voice record recognized");
        check(ChatCallRecord.media("call","text","[视频通话] 通话时长 00:13",0)==2,"video record recognized");
        check(ChatCallRecord.media("user","text","[语音通话] 已取消",0)==0,"ordinary text cannot impersonate a call");
        check(ChatCallRecord.media("call","text","[视频通话] 已取消",9)==0,"group record never dials");
        check(ChatCallRecord.media("call","image","[语音通话] 已取消",0)==0,"non-text record never dials");
        check(ChatCallRecord.media("call","text",null,0)==0,"null content safe");
        check(ChatCallRecord.media("call","text","other text",0)==0,"unknown record safe");
        String[] statuses={"已取消","已拒绝","对方忙线中","无人接听","对方暂时不在线","连接中断"};
        String[] keys={"call_record_canceled","call_record_rejected","call_record_busy","call_record_unanswered","call_record_offline","call_record_interrupted"};
        for(int i=0;i<statuses.length;i++) check(keys[i].equals(ChatCallRecord.statusKey("[语音通话] "+statuses[i],1)),"status "+statuses[i]);
        check("00:13".equals(ChatCallRecord.duration("[视频通话] 通话时长 00:13",2)),"short duration preserved");
        check("125:59".equals(ChatCallRecord.duration("[语音通话] 通话时长 125:59",1)),"long duration preserved");
        check(ChatCallRecord.duration("[语音通话] 通话时长 00:99",1).isEmpty(),"invalid duration rejected");
        check("自定义结果".equals(ChatCallRecord.result("[视频通话] 自定义结果",2)),"unknown server status retained");
    }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView label=new TextView(host);label.setText(text);label.setTextSize(size);label.setTextColor(color);return label;
    }
    private JSONObject record(int id, int media, String status, boolean mine, String origin) throws Exception {
        return new JSONObject().put("id",id).put("sender_id",mine?700001:700002).put("nickname",mine?"我":"小林")
                .put("message_origin",origin).put("message_type","text").put("content",(media==2?"[视频通话] ":"[语音通话] ")+status);
    }
    private View render(JSONObject message) throws Exception {
        invoke("renderChatMessage",new Class<?>[]{JSONObject.class,LinearLayout.class,ScrollView.class,String.class,String.class,String.class,String.class,boolean.class},message,messages,scroll,"小林","林","#6D8CE8","#536CC5",false);
        return (View)((Map<?,?>)field("renderedMessageViews")).get((Integer.valueOf(0).equals(field("activeGroupId"))?"c":"g")+message.optInt("id"));
    }
    private void gallery() throws Exception {
        ((Set<?>)field("renderedMessageIds")).clear();((Map<?,?>)field("renderedMessageViews")).clear();((Map<?,?>)field("activeMessageModels")).clear();
        LinearLayout screen=new LinearLayout(host);screen.setOrientation(LinearLayout.VERTICAL);screen.setBackgroundColor(0xfff3f5f9);
        LinearLayout header=new LinearLayout(host);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(20),dp(18),dp(20),dp(18));header.setBackgroundColor(Color.WHITE);
        header.addView(label("‹",28,0xff26324a),new LinearLayout.LayoutParams(dp(30),-2));
        TextView name=label("小林",19,0xff20283f);name.setTypeface(null,1);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));header.addView(label("···",24,0xff26324a));screen.addView(header);
        scroll=new ScrollView(host);messages=new LinearLayout(host);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(dp(14),dp(22),dp(14),dp(20));scroll.addView(messages);
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView composer=label("＋     输入消息…                           ☺",16,0xff8b95a8);composer.setPadding(dp(18),dp(18),dp(18),dp(18));composer.setBackgroundColor(Color.WHITE);screen.addView(composer);
        host.setContentView(screen);field("activeMessageList",messages);field("activeMessageScroll",scroll);
        render(record(101,1,"已取消",false,"call"));render(record(102,1,"通话时长 00:13",true,"call"));
        render(record(103,2,"通话时长 02:36",false,"call"));render(record(104,2,"无人接听",true,"call"));
        render(record(105,1,"连接中断",false,"call"));
    }
    private ChatCallBubble bubble(int id) throws Exception { return (ChatCallBubble)((Map<?,?>)field("renderedMessageViews")).get("c"+id); }
    private void renderingChecks() throws Exception {
        for(int id=101;id<=105;id++) { ChatCallBubble bubble=bubble(id);check(bubble!=null&&bubble.getWidth()>0,"trusted record rendered "+id);check(bubble.isLongClickable(),"long-press retained "+id); }
        check(bubble(101).getContentDescription().toString().contains("已取消")&&bubble(101).getContentDescription().toString().contains("语音通话"),"voice two-line labels");
        check(bubble(102).getContentDescription().toString().contains("通话时长 00:13"),"historic duration rendered");
        check(bubble(103).getContentDescription().toString().contains("视频通话"),"video subtitle rendered");
        check(render(record(106,1,"已取消",false,"user")) instanceof TextView,"forged text uses ordinary message renderer");
        field("activeGroupId",9);check(render(record(107,2,"已取消",false,"call")) instanceof TextView,"group messages never use actionable call bubbles");field("activeGroupId",0);
        int before=messages.getChildCount();invoke("removeMessageBlock",new Class<?>[]{View.class},bubble(101));check(messages.getChildCount()==before-1,"message block removal retains its linkage");
        field("appLanguage","en");View english=render(record(108,2,"已拒绝",false,"call"));check(english.getContentDescription().toString().contains("Declined")&&english.getContentDescription().toString().contains("Video call"),"English call labels localized");field("appLanguage","zh-CN");
        AccessibilityNodeInfo info=AccessibilityNodeInfo.obtain();bubble(102).onInitializeAccessibilityNodeInfo(info);check(Button.class.getName().contentEquals(info.getClassName()),"TalkBack exposes call bubble as button");info.recycle();
    }
    private AlertDialog prompt() throws Exception { return (AlertDialog)field("callRecordRedialPrompt"); }
    private void confirmationChecks() throws Exception {
        bubble(102).performClick();AlertDialog voice=prompt();check(voice!=null&&voice.isShowing(),"voice confirmation opens");
        check(voice.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString().contains("语音"),"voice action selected");
        bubble(103).performClick();check(prompt()==voice,"rapid taps do not stack dialogs");voice.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
    }
    private void layoutChecks() throws Exception {
        // Let the dialog dismiss queued by its button complete before re-opening it.
        if(prompt()!=null)prompt().dismiss();
        bubble(103).performClick();check(prompt().isShowing()&&prompt().getButton(DialogInterface.BUTTON_POSITIVE).getText().toString().contains("视频"),"video confirmation selected");prompt().dismiss();
        field("activeChatId",990002);bubble(102).performClick();check(!prompt().isShowing(),"stale bubble cannot call another chat");field("activeChatId",990001);
        field("currentUserId",700003);bubble(102).performClick();check(!prompt().isShowing(),"stale bubble cannot cross accounts");field("currentUserId",700001);
        field("activeGroupId",9);bubble(102).performClick();check(!prompt().isShowing(),"call confirmation blocked inside groups");field("activeGroupId",0);
        for(boolean mine:new boolean[]{false,true}) for(float scale:new float[]{1f,1.5f}) for(int width:new int[]{224,248}) {
            Configuration configuration=new Configuration(host.getResources().getConfiguration());configuration.fontScale=scale;configuration.setLocale(Locale.ENGLISH);
            Context local=host.createConfigurationContext(configuration);
            ChatCallBubble sample=new ChatCallBubble(local,mine,2,true,"Call duration 125:59","Video call · Tap to call back",0xff3458ed);
            sample.measure(View.MeasureSpec.makeMeasureSpec(dp(width),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));sample.layout(0,0,sample.getMeasuredWidth(),sample.getMeasuredHeight());
            check(sample.getMeasuredWidth()<=dp(width),"narrow layout stays within available width "+width+"/"+scale+"/"+mine);
            LinearLayout copy=(LinearLayout)sample.getChildAt(1);TextView heading=(TextView)copy.getChildAt(0),hint=(TextView)copy.getChildAt(1);
            check(hint.getBottom()<=copy.getHeight()&&copy.getBottom()<=sample.getHeight()-sample.getPaddingBottom()&&heading.getLineCount()>0&&hint.getLineCount()>0,"both text rows have measured height "+width+"/"+scale+"/"+mine);
        }
    }
    private void staleConfirmationChecks() throws Exception {
        // Keep the invalidated target until AlertDialog's asynchronous listener has executed.
        ui(() -> { if(prompt()!=null)prompt().dismiss();field("activeVoiceLabel","guard-fixture");bubble(102).performClick();field("activeChatId",990002);prompt().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); });
        waitForIdleSync();
        ui(() -> { check(!prompt().isShowing()&&field("chatCall")==null&&"guard-fixture".equals(field("activeVoiceLabel")),"stale confirmation returns before touching playback or call SDK");field("activeChatId",990001);bubble(103).performClick();field("currentUserId",700003);prompt().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); });
        waitForIdleSync();
        ui(() -> { check(!prompt().isShowing()&&field("chatCall")==null&&"guard-fixture".equals(field("activeVoiceLabel")),"account change returns before touching playback or call SDK");field("activeVoiceLabel","");field("currentUserId",700001); });
    }
    private void respond(Socket socket) throws IOException {
        try(Socket connection=socket) {
            connection.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(connection.getInputStream(),"UTF-8"));String request=in.readLine(),line;
            while((line=in.readLine())!=null&&!line.isEmpty()) {}
            String data=request!=null&&request.contains("app.update.check")?"{\"update_available\":false,\"version_code\":77}":"[]";
            byte[] body=("{\"code\":0,\"data\":"+data+"}").getBytes("UTF-8");
            OutputStream out=connection.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
        }
    }
}
