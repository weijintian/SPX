package com.spx.chat;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated loopback fixtures only. No real account, wallet mutation, or server traffic. */
public final class RedPacketReceiptInstrumentation extends Instrumentation {
    private static final String ID="abcdef0123456789abcdef0123456789";
    private MainActivity host;private Context context;private ChatRedPacket packets;
    private ServerSocket server;private volatile boolean serving=true;
    private volatile int createMode;private volatile JSONObject wire;
    private volatile CountDownLatch sendGate=new CountDownLatch(0);
    private final AtomicInteger creates=new AtomicInteger(),recoveries=new AtomicInteger();
    private volatile int detailMode;
    private volatile boolean paymentConfigured;
    private final AtomicInteger passwordSaves=new AtomicInteger(),emailSends=new AtomicInteger();
    private int checks;private LinearLayout messages;private ScrollView scroll;
    private interface Work{void run()throws Exception;}private interface Condition{boolean ok()throws Exception;}
    private void ui(Work work)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new RuntimeException(error[0]);}
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private void await(String label,Condition condition)throws Exception{long end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){boolean[] ok={false};ui(()->ok[0]=condition.ok());if(ok[0])return;Thread.sleep(40);}throw new AssertionError("Timeout "+label);}
    private Object get(Object target,String key)throws Exception{Field f=target.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(target);}
    private void set(Object target,String key,Object value)throws Exception{Field f=target.getClass().getDeclaredField(key);f.setAccessible(true);f.set(target,value);}
    private Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    private EditText edit(View v,String description){if(v instanceof EditText&&(description==null||description.contentEquals(v.getContentDescription())))return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=edit(((ViewGroup)v).getChildAt(i),description);if(e!=null)return e;}return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        context=getTargetContext();if(!"com.spx.chat.updatepreview".equals(context.getPackageName()))throw new IllegalStateException("Isolated app required");
        server=new ServerSocket(38177,20,InetAddress.getByName("127.0.0.1"));
        new Thread(()->{while(serving)try{Socket s=server.accept();new Thread(()->respond(s),"packet-fixture-response").start();}catch(Exception e){if(serving)android.util.Log.e("PacketFixture","accept",e);}},"packet-fixture").start();
        context.getSharedPreferences("spx_api",0).edit().clear().putString("base_url","http://127.0.0.1:38177").commit();
        context.getSharedPreferences("spx_red_packets",0).edit().clear().commit();
        host=(MainActivity)startActivitySync(new Intent().setClassName(context.getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        ui(()->{call(host,"continueAfterSplash",new Class<?>[]{});call(host,"buildApp",new Class<?>[]{});set(host,"currentUserId",700001);set(host,"appLanguage","zh-CN");packets=new ChatRedPacket(host);});
        classification();paymentSettings();sendFeedback();packetStates();keyboardInsets();receiptRendering();
        result.putString("stream","SUCCESS "+checks+" red packet feedback and receipt checks; isolated loopback fixtures\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}finally{sendGate.countDown();serving=false;try{if(server!=null)server.close();}catch(Exception ignored){}}}
    private JSONObject receipt(int row,int sender,int receiver,int group)throws Exception{
        JSONObject metadata=new JSONObject().put("packet_id",ID).put("sender_id",sender).put("receiver_id",receiver).put("sender_name","小林").put("receiver_name","小王");
        return new JSONObject().put("id",row).put("sender_id",receiver).put("nickname","小王").put("group_id",group).put("chat_id",group>0?0:990001).put("message_type","text").put("message_origin","system").put("content","小王领取了小林的红包").put("media_url","spxredpacket:claim:"+metadata);
    }
    private void classification()throws Exception{
        JSONObject m=receipt(1,700001,700002,99);ChatRedPacketNotice n=ChatRedPacketNotice.parse(m);
        check(n!=null&&"小王领取了你的红包".equals(n.text(700001,false)),"sender perspective");
        check("你领取了小林的红包".equals(n.text(700002,false)),"claimant perspective");check("小王领取了小林的红包".equals(n.text(700003,false)),"group observer perspective");
        check(n.text(700001,true).equals("小王 claimed your red packet"),"English receipt");
        n=ChatRedPacketNotice.parse(receipt(2,700001,700001,99));check(n.text(700001,false).equals("你领取了自己的红包"),"self claim perspective");check(n.text(700003,false).equals("小王领取了自己的红包"),"observer self claim");
        check(ChatRedPacketNotice.parse(new JSONObject(m.toString()).put("message_origin","human"))==null,"human text cannot impersonate receipt");
        check(ChatRedPacketNotice.parse(new JSONObject(m.toString()).put("message_type","image"))==null,"media cannot impersonate receipt");
        check(ChatRedPacketNotice.parse(new JSONObject(m.toString()).put("sender_id",700004))==null,"claimant identity must match sender");
        check(ChatRedPacketNotice.parse(new JSONObject(m.toString()).put("media_url","spxredpacket:claim:{}"))==null,"invalid IDs stay ordinary text");
        check(ChatRedPacketNotice.parse(new JSONObject(m.toString()).put("media_url","spxredpacket:claim:{"))==null,"malformed metadata safe");
    }
    private void submit()throws Exception{
        ui(()->{
        packets.compose(990001,0);LinearLayout form=(LinearLayout)get(packets,"composer");edit(form,"红包总金额 SPX001").setText("1");((View)get(packets,"sendButton")).performClick();
        });await("payment status",()->get(packets,"payment")!=null);
        final Dialog[] paymentDialog={null};final EditText[] paymentInput={null};
        ui(()->{paymentDialog[0]=(Dialog)get(packets,"payment");paymentInput[0]=edit(paymentDialog[0].getWindow().getDecorView(),null);});
        paymentKeyboard(paymentDialog[0],paymentInput[0],"send confirmation");ui(()->{
        AlertDialog dialog=(AlertDialog)get(packets,"payment");check(dialog!=null&&dialog.isShowing(),"password confirmation present");
        edit(dialog.getWindow().getDecorView(),null).setText("258369");dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        check(!dialog.isShowing(),"password dialog closes immediately");check(edit(dialog.getWindow().getDecorView(),null).length()==0,"password edit cleared immediately");
        });
    }
    private void sendFeedback()throws Exception{
        sendGate=new CountDownLatch(1);createMode=0;submit();await("first create",()->creates.get()==1);
        ui(()->{check((Boolean)get(packets,"sending"),"pending state until server confirms");check(((ProgressBar)get(packets,"sendProgress")).getVisibility()==View.VISIBLE,"indeterminate progress visible");check(!((View)get(packets,"sendButton")).isEnabled(),"duplicate submit disabled");
            ((View)get(packets,"sendButton")).performClick();packets.compose(990001,0);check(creates.get()==1,"double taps cannot create second request");
            String saved=context.getSharedPreferences("spx_red_packets",0).getAll().toString();check(saved.contains(wire.optString("request_id"))&&!saved.contains("password")&&!saved.contains("258369"),"only password-free stable request persisted");check(wire.optString("payment_password").equals("258369")&&!wire.has("password"),"financial request uses explicit payment password field");});
        Thread.sleep(4200);ui(()->{check(((TextView)get(packets,"sendStatus")).getText().toString().contains("服务器确认"),"slow response explanation visible");check((Boolean)get(packets,"sending"),"no fake success during delay");
            host.redPacketClose((View)get(packets,"composer"));check(!host.redPacketVisible((View)get(packets,"composer")),"user can return while sending");});
        sendGate.countDown();await("confirmed send",()->!(Boolean)get(packets,"sending"));
        ui(()->check(context.getSharedPreferences("spx_red_packets",0).getAll().isEmpty()&&creates.get()==1,"confirmed response clears pending request once"));
        createMode=1;submit();await("rejected password",()->creates.get()==2&&!(Boolean)get(packets,"sending"));
        ui(()->{check(context.getSharedPreferences("spx_red_packets",0).getAll().isEmpty(),"wrong password clears pending without fake sent");check(((View)get(packets,"sendButton")).isEnabled(),"wrong password allows correction");host.redPacketClose((View)get(packets,"composer"));});
        createMode=2;submit();await("lost response reconciliation",()->creates.get()==3&&recoveries.get()>0&&!(Boolean)get(packets,"sending")&&!(Boolean)get(packets,"recovering"));
        ui(()->{check(creates.get()==3&&recoveries.get()==1,"lost response queries result without second financial POST");check(context.getSharedPreferences("spx_red_packets",0).getAll().isEmpty(),"reconciled send clears pending");packets.dispose();});
    }
    private int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private Object settings() throws Exception {
        Constructor<PaymentPasswordSettings> c=PaymentPasswordSettings.class.getDeclaredConstructor(MainActivity.class);c.setAccessible(true);Object s=c.newInstance(host);call(s,"show",new Class<?>[]{});return s;
    }
    private boolean imeVisible(Dialog dialog){androidx.core.view.WindowInsetsCompat i=androidx.core.view.ViewCompat.getRootWindowInsets(dialog.getWindow().getDecorView());return i!=null&&i.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());}
    private void paymentKeyboard(Dialog dialog,EditText input,String label)throws Exception{
        ui(()->{
            check((dialog.getWindow().getAttributes().flags&(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))==0,label+" window accepts input method");
            check((input.getInputType()&android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_NUMBER&&input.getShowSoftInputOnFocus(),label+" requests numeric keyboard");
            input.requestFocus();input.requestRectangleOnScreen(new Rect(0,0,input.getWidth(),input.getHeight()),true);
        });waitForIdleSync();
        final int[] xy=new int[2];ui(()->{input.getLocationOnScreen(xy);xy[0]+=input.getWidth()/2;xy[1]+=input.getHeight()/2;});
        long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,xy[0],xy[1],0),up=MotionEvent.obtain(time,time+60,MotionEvent.ACTION_UP,xy[0],xy[1],0);
        try{sendPointerSync(down);sendPointerSync(up);}finally{down.recycle();up.recycle();}
        await(label+" numeric IME visible after touch",()->input.hasFocus()&&input.hasWindowFocus()&&imeVisible(dialog));
        ui(()->check(imeVisible(dialog),label+" keyboard actually visible"));
        sendStringSync("258369");ui(()->check("258369".equals(input.getText().toString()),label+" accepts six digits with keyboard visible"));
        ui(()->{input.setText("");((android.view.inputmethod.InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);});
        await(label+" keyboard dismissed",()->!imeVisible(dialog));
    }
    private void paymentSettings() throws Exception {
        Object[] settings={null};ui(()->settings[0]=settings());await("first setup status",()->(Boolean)get(settings[0],"loaded"));
        paymentKeyboard((Dialog)get(settings[0],"dialog"),(EditText)get(settings[0],"password"),"first setup password");
        paymentKeyboard((Dialog)get(settings[0],"dialog"),(EditText)get(settings[0],"confirmation"),"first setup confirmation");
        ui(()->{Object s=settings[0];check(!(Boolean)get(s,"configured"),"first payment setup has no code requirement");EditText pin=(EditText)get(s,"password"),again=(EditText)get(s,"confirmation");pin.setText("1234567");check(pin.length()==6,"payment input limited to six digits");check(pin.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod,"payment input masks digits");pin.setText("258369");again.setText("258368");((Button)get(s,"save")).performClick();check(passwordSaves.get()==0,"mismatching confirmation never submitted");again.setText("258369");((Button)get(s,"save")).performClick();});
        await("payment saved",()->passwordSaves.get()==1&&!((Dialog)get(settings[0],"dialog")).isShowing());
        ui(()->check(((EditText)get(settings[0],"password")).length()==0&&((EditText)get(settings[0],"confirmation")).length()==0,"saved payment credentials cleared from view"));
        ui(()->settings[0]=settings());await("configured status",()->(Boolean)get(settings[0],"loaded"));
        paymentKeyboard((Dialog)get(settings[0],"dialog"),(EditText)get(settings[0],"code"),"email code");
        paymentKeyboard((Dialog)get(settings[0],"dialog"),(EditText)get(settings[0],"password"),"changed password");
        paymentKeyboard((Dialog)get(settings[0],"dialog"),(EditText)get(settings[0],"confirmation"),"changed confirmation");
        ui(()->{Object s=settings[0];check((Boolean)get(s,"configured")&&get(s,"code")!=null,"changing payment password requires email verification");((EditText)get(s,"password")).setText("456789");((EditText)get(s,"confirmation")).setText("456789");((Button)get(s,"save")).performClick();check(passwordSaves.get()==1,"missing email code is not submitted");((Button)get(s,"sendCode")).performClick();});
        await("verification code sent",()->emailSends.get()==1&&!(Boolean)get(settings[0],"busy"));
        ui(()->{check(!((Button)get(settings[0],"sendCode")).isEnabled(),"verification resend cooldown active");((Dialog)get(settings[0],"dialog")).dismiss();});
    }
    private void packetStates() throws Exception {
        String[] expected={"红包已领完","红包已过期","你不在这个红包的领取范围内","红包不存在","测试网络故障"};
        for(int mode=0;mode<expected.length;mode++){
            detailMode=mode;final Object[] p={null};ui(()->{packets.detail(ID);p[0]=get(packets,"popup");});await("packet state "+mode,()->!(Boolean)get(p[0],"loading"));final int selected=mode;
            ui(()->{check(((TextView)get(p[0],"status")).getText().toString().contains(expected[selected]),"specific packet state "+selected);check(((FrameLayout)get(p[0],"coinHost")).getVisibility()==View.GONE,"no misleading claim button for state "+selected);});
            if(mode==4){detailMode=0;ui(()->((TextView)get(p[0],"link")).performClick());await("packet retry",()->!(Boolean)get(p[0],"loading"));ui(()->check(((TextView)get(p[0],"status")).getText().toString().contains("已领完"),"retry recovers terminal packet state"));}
            ui(()->((Dialog)get(p[0],"dialog")).dismiss());
        }
        detailMode=0;
    }
    private void keyboardInsets() throws Exception {
        ui(()->{call(host,"renderAuthenticationPage",new Class<?>[]{});});waitForIdleSync();
        final View[] root={null};final EditText[] input={null};
        ui(()->{root[0]=((ViewGroup)host.findViewById(android.R.id.content)).getChildAt(0);input[0]=edit(root[0],null);check(input[0]!=null,"login input present");input[0].requestFocus();((android.view.inputmethod.InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input[0],android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        await("native keyboard",()->{androidx.core.view.WindowInsetsCompat i=androidx.core.view.ViewCompat.getRootWindowInsets(root[0]);return i!=null&&i.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());});
        ui(()->{androidx.core.view.WindowInsetsCompat i=androidx.core.view.ViewCompat.getRootWindowInsets(root[0]);int bottom=i.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;check(root[0].getPaddingBottom()>=bottom,"root reserves keyboard height");int[] xy=new int[2];input[0].getLocationOnScreen(xy);check(xy[1]+input[0].getHeight()<=host.getResources().getDisplayMetrics().heightPixels-bottom,"focused login input remains above keyboard");((android.view.inputmethod.InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input[0].getWindowToken(),0);});
        await("keyboard closed",()->!androidx.core.view.ViewCompat.getRootWindowInsets(root[0]).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
        ui(()->{androidx.core.view.WindowInsetsCompat i=androidx.core.view.ViewCompat.getRootWindowInsets(root[0]);check(root[0].getPaddingBottom()==i.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout()).bottom,"keyboard space removed after dismissal");});
        ui(()->{call(host,"buildApp",new Class<?>[]{});set(host,"currentUserId",700001);call(host,"openChatWindow",new Class<?>[]{int.class,int.class,String.class,String.class,String.class,String.class,String.class},0,99,"键盘测试群","群","group","#A8C0FF","#3F5EFB");});waitForIdleSync();
        ui(()->{root[0]=((ViewGroup)host.findViewById(android.R.id.content)).getChildAt(0);input[0]=(EditText)get(host,"activeMessageInput");input[0].requestFocus();((android.view.inputmethod.InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input[0],android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        await("chat keyboard",()->androidx.core.view.ViewCompat.getRootWindowInsets(root[0]).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
        ui(()->{androidx.core.view.WindowInsetsCompat i=androidx.core.view.ViewCompat.getRootWindowInsets(root[0]);int bottom=i.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;int[] xy=new int[2];input[0].getLocationOnScreen(xy);check(xy[1]+input[0].getHeight()<=host.getResources().getDisplayMetrics().heightPixels-bottom,"chat composer remains above keyboard");input[0].setText("键盘测试\n多行输入");check(input[0].getText().toString().contains("多行"),"chat remains editable with keyboard visible");((android.view.inputmethod.InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input[0].getWindowToken(),0);});
        await("chat keyboard closed",()->!androidx.core.view.ViewCompat.getRootWindowInsets(root[0]).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
        ui(()->{input[0].setText("");call(host,"closeChatWindow",new Class<?>[]{});});
    }
    private void render(JSONObject m)throws Exception{call(host,"renderChatMessage",new Class<?>[]{JSONObject.class,LinearLayout.class,ScrollView.class,String.class,String.class,String.class,String.class,boolean.class},m,messages,scroll,"小林","林","#A8C0FF","#3F5EFB",false);}
    private void receiptRendering()throws Exception{
        ui(()->{
            set(host,"activeChatId",0);set(host,"activeGroupId",99);set(host,"chatWindowOpen",true);set(host,"currentUserId",700001);
            ((Set<?>)get(host,"renderedMessageIds")).clear();((Map<?,?>)get(host,"renderedMessageViews")).clear();((Map<?,?>)get(host,"activeMessageModels")).clear();
            LinearLayout screen=new LinearLayout(host);screen.setOrientation(1);screen.setBackgroundColor(0xfff6f8fc);
            TextView title=new TextView(host);title.setText("‹    红包测试群（3人）              ···");title.setTextSize(20);title.setTextColor(0xff20283f);title.setPadding(dp(18),dp(18),dp(18),dp(18));title.setBackgroundColor(Color.WHITE);screen.addView(title);
            scroll=new ScrollView(host);messages=new LinearLayout(host);messages.setOrientation(1);messages.setPadding(dp(14),dp(20),dp(14),dp(20));scroll.addView(messages);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            TextView input=new TextView(host);input.setText("＋      输入消息…                         ☺");input.setTextSize(16);input.setPadding(dp(18),dp(18),dp(18),dp(18));input.setBackgroundColor(Color.WHITE);screen.addView(input);host.setContentView(screen);
            set(host,"activeMessageList",messages);set(host,"activeMessageScroll",scroll);
            JSONObject packetCard=new JSONObject().put("packet_id",ID).put("greeting","恭喜发财，大吉大利").put("mode","random");
            render(new JSONObject().put("id",901).put("sender_id",700001).put("nickname","我").put("message_type","red_packet").put("message_origin","red_packet").put("content",packetCard.toString()));
            JSONObject m=receipt(902,700001,700002,99);render(m);render(m);check(messages.getChildCount()==2,"duplicate receipt renders once");
            TextView notice=(TextView)((Map<?,?>)get(host,"renderedMessageViews")).get("g902");check(notice.getText().toString().equals("小王领取了你的红包"),"centered personalized receipt rendered");check((notice.getGravity()&Gravity.CENTER_HORIZONTAL)==Gravity.CENTER_HORIZONTAL,"receipt centered");
            ClickableSpan[] spans=((Spanned)notice.getText()).getSpans(0,notice.length(),ClickableSpan.class);check(spans.length==1,"single packet detail link");Spanned text=(Spanned)notice.getText();check(text.subSequence(text.getSpanStart(spans[0]),text.getSpanEnd(spans[0])).toString().equals("红包"),"only packet word is clickable");
            spans[0].onClick(notice);ChatRedPacket owner=(ChatRedPacket)get(host,"chatRedPacket");Object popup=get(owner,"popup");check(ID.equals(get(popup,"id")),"link opens correct packet ID");((Dialog)get(popup,"dialog")).dismiss();
            set(host,"currentUserId",700004);spans[0].onClick(notice);Object stale=get(owner,"popup");check(stale==null||(stale==popup&&!((Dialog)get(stale,"dialog")).isShowing()),"stale account link ignored");set(host,"currentUserId",700001);
            render(receipt(903,700001,700001,99));
            JSONObject packet=new JSONObject().put("id",ID).put("scope","group").put("my_amount","1").put("claim_notice",receipt(904,700002,700001,99));host.redPacketReceipt(packet);host.redPacketReceipt(packet);
            check(messages.getChildCount()==4,"HTTP and live/history delivery share message ID dedupe");
            host.redPacketReceipt(new JSONObject(packet.toString()).put("id","wrong"));check(messages.getChildCount()==4,"wrong packet receipt rejected");
            host.redPacketReceipt(new JSONObject(packet.toString()).put("my_amount",JSONObject.NULL));check(messages.getChildCount()==4,"unconfirmed claim never renders a receipt");
        });waitForIdleSync();
        await("receipt persisted in account cache",()->{JSONArray rows=ChatCache.get(context).list("group",99);for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optInt("id")==904)return ChatRedPacketNotice.parse(row)!=null;}return false;});
        ui(()->check(ChatCache.get(context).list("group",99).length()>0,"receipt survives cached history read"));
        Thread.sleep(3000);
        Bitmap shot=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"red-packet-receipts.png"))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}
        ui(()->{ChatRedPacket owner=(ChatRedPacket)get(host,"chatRedPacket");if(owner!=null)owner.dispose();});
    }
    private JSONObject sendResult()throws Exception{return new JSONObject().put("packet",new JSONObject().put("id",ID)).put("message",new JSONObject().put("id",801+creates.get()).put("sender_id",700001).put("message_type","red_packet").put("message_origin","red_packet").put("content",new JSONObject().put("packet_id",ID).put("greeting","测试红包").toString()));}
    private void respond(Socket socket){try(Socket c=socket){c.setSoTimeout(10000);InputStream in=c.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int ch,state=0;while((ch=in.read())!=-1){headers.write(ch);state=(state==0&&ch==13)?1:(state==1&&ch==10)?2:(state==2&&ch==13)?3:(state==3&&ch==10)?4:0;if(state==4)break;if(headers.size()>16384)return;}
        String head=headers.toString("UTF-8"),line=head.split("\r\n")[0];int len=0;for(String h:head.split("\r\n"))if(h.toLowerCase(Locale.ROOT).startsWith("content-length:"))len=Integer.parseInt(h.substring(15).trim());
        byte[] bytes=new byte[len];for(int i=0;i<len;){int n=in.read(bytes,i,len-i);if(n<0)throw new EOFException();i+=n;}
        Object data=new JSONArray();int status=200;
        if(line.contains("action=redpackets.create")){wire=new JSONObject(new String(bytes,"UTF-8"));creates.incrementAndGet();sendGate.await(12,TimeUnit.SECONDS);if(createMode==2)return;if(createMode==1)status=403;else data=sendResult();}
        else if(line.contains("action=redpackets.request")){recoveries.incrementAndGet();data=sendResult().put("exists",true);}
        else if(line.contains("action=payment.password.status"))data=new JSONObject().put("configured",paymentConfigured);
        else if(line.contains("action=payment.password.set")||line.contains("action=payment.password.change.email")){passwordSaves.incrementAndGet();paymentConfigured=true;data=new JSONObject().put("configured",true);}
        else if(line.contains("action=payment.password.email.code")){emailSends.incrementAndGet();data=new JSONObject().put("email","fixture@example.test");}
        else if(line.contains("action=redpackets.detail")){if(detailMode>=2)status=detailMode==2?403:detailMode==3?404:503;else data=new JSONObject().put("id",ID).put("my_amount",JSONObject.NULL).put("sender_name","小林").put("sender_id",700002).put("can_claim",false).put("status",detailMode==1?"expired":"completed").put("total_count",1).put("claimed_count",1);}
        else if(line.contains("action=wallet"))data=new JSONArray().put(new JSONObject().put("symbol","SPX001").put("chain","BSC").put("balance","100"));
        else if(line.contains("action=app.update.check"))data=new JSONObject().put("update_available",false);
        else if(line.contains("action=api.nodes"))data=new JSONObject().put("nodes",new JSONArray());
        String message=line.contains("action=redpackets.detail")?(status==403?"你不在这个红包的领取范围内":status==404?"红包不存在":"测试网络故障，请重试"):"密码错误，未扣款";
        byte[] body=(status>=400?new JSONObject().put("code",status).put("message",message):new JSONObject().put("code",0).put("data",data)).toString().getBytes("UTF-8");
        OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 "+status+" OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
    }catch(Exception e){if(serving)android.util.Log.e("PacketFixture","response",e);}}
}
