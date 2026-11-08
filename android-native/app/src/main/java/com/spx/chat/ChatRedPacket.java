package com.spx.chat;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;

/** Native packet presentation. Money, eligibility and idempotency remain server controlled. */
final class ChatRedPacket {
    private final MainActivity a;
    private static final int RED=0xffd95043, GOLD=0xffffdf9b, PAPER=0xfff7f5f1;
    private LinearLayout composer;
    private AlertDialog payment;
    private TextView sendButton;
    private LinearLayout sendingStatus;
    private TextView sendStatus;
    private ProgressBar sendProgress;
    private boolean sending,recovering,checkingPayment;
    private final android.os.Handler feedbackHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable slowSendFeedback=()->{
        if(sending&&sendStatus!=null)sendStatus.setText(recovering?"正在核对发送结果，请勿重复发送":"正在等待服务器确认，请勿重复发送");
    };
    private int generation;
    private PacketPopup popup;
    ChatRedPacket(MainActivity a){this.a=a;}
    private int dp(int n){return (int)(n*a.getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout col(){LinearLayout v=new LinearLayout(a);v.setOrientation(1);return v;}
    private TextView text(String s,int size,int color){TextView v=new TextView(a);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));return v;}
    private TextView center(String s,int size,int color){TextView v=text(s,size,color);v.setGravity(Gravity.CENTER);return v;}
    private void toast(String s){AppNotice.show(a, s);}
    private JSONObject obj(Object...p){JSONObject v=new JSONObject();try{for(int i=0;i<p.length;i+=2)v.put((String)p[i],p[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return v;}
    private String pretty(String raw){try{return new BigDecimal(raw).stripTrailingZeros().toPlainString();}catch(Exception e){return raw;}}
    private boolean session(String server,String token){return !a.isFinishing()&&!a.isDestroyed()&&server.equals(ApiClient.baseUrl(a))&&token.equals(ApiClient.token(a));}
    private String pendingKey(){return "red_packet_request:"+ApiClient.baseUrl(a)+":"+a.mallUserId();}
    private String pending(String key){return a.getSharedPreferences("spx_red_packets",0).getString(key,"");}
    private void clearPending(String key){a.getSharedPreferences("spx_red_packets",0).edit().remove(key).apply();}
    private boolean visible(LinearLayout form){return form!=null&&a.redPacketVisible(form);}
    private void sendState(boolean busy){
        sending=busy;feedbackHandler.removeCallbacks(slowSendFeedback);
        boolean pendingResult=!pending(pendingKey()).isEmpty();
        if(sendButton!=null){sendButton.setEnabled(!busy);sendButton.setAlpha(busy?.55f:1f);sendButton.setText(busy?(recovering?"正在查询发送结果…":"正在发送红包…"):pendingResult?"查询上次发送结果":"塞进红包");}
        if(sendingStatus!=null){sendingStatus.setVisibility(busy||pendingResult?View.VISIBLE:View.GONE);sendProgress.setVisibility(busy?View.VISIBLE:View.GONE);sendStatus.setText(busy?(recovering?"正在核对发送结果，请勿重复发送":"正在发送红包，可返回聊天等待"):"发送结果待确认，请查询后再试");}
        if(busy)feedbackHandler.postDelayed(slowSendFeedback,4000);
    }
    void dispose(){generation++;feedbackHandler.removeCallbacks(slowSendFeedback);if(popup!=null)popup.dialog.dismiss();if(payment!=null)payment.dismiss();popup=null;payment=null;composer=null;sendButton=null;sendingStatus=null;sendStatus=null;sendProgress=null;sending=false;recovering=false;checkingPayment=false;}
    private Dialog sheet(String title,LinearLayout body){
        Dialog d=new Dialog(a);LinearLayout root=col();root.setPadding(dp(20),dp(18),dp(20),dp(20));root.setBackground(MallUi.surface(a,Color.WHITE,22,false));
        root.addView(text(title,21,MallUi.INK));ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        d.setContentView(root);d.show();if(d.getWindow()!=null){d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);d.getWindow().setGravity(Gravity.BOTTOM);d.getWindow().setLayout(-1,Math.min(dp(650),(int)(a.getResources().getDisplayMetrics().heightPixels*.85f)));}return d;
    }
    private TextView button(LinearLayout p,String label,Runnable action){TextView b=center(label,16,Color.WHITE);b.setPadding(dp(12),dp(14),dp(12),dp(14));b.setBackground(MallUi.surface(a,RED,12,false));MallUi.touch(b,12);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(14),0,dp(8));p.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    private EditText field(LinearLayout p,String name,String hint,int type){p.addView(text(name,13,MallUi.SECONDARY));EditText e=new EditText(a);e.setTextSize(16);e.setTextColor(MallUi.INK);e.setHintTextColor(0xffa1a4ac);e.setSingleLine(true);e.setHint(hint);e.setInputType(type);e.setPadding(dp(14),dp(14),dp(14),dp(14));e.setBackground(MallUi.surface(a,Color.WHITE,12,false));e.setContentDescription(name);p.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    void compose(int chat,int group){
        if(sending||recovering)return;
        if(visible(composer))return;
        String key=pendingKey(),stored=pending(key);
        if(!stored.isEmpty()){try{recover(new JSONObject(stored),key,null);return;}catch(Exception ignored){clearPending(key);}}
        final LinearLayout page=col();composer=page;page.setTag("red-packet-compose");page.setBackgroundColor(PAPER);page.setFocusableInTouchMode(true);
        LinearLayout bar=new LinearLayout(a);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(4),dp(12),dp(4));
        View back=MallUi.icon(a,"back",MallUi.INK);back.setPadding(dp(12),dp(12),dp(12),dp(12));back.setContentDescription("返回聊天");MallUi.touch(back,12);bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->a.redPacketClose(page));
        TextView title=center(group>0?"发群红包":"发红包",18,MallUi.INK);title.setTypeface(null,1);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));View spacer=new View(a);bar.addView(spacer,new LinearLayout.LayoutParams(dp(48),1));page.addView(bar);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);LinearLayout body=col();body.setPadding(dp(24),dp(14),dp(24),dp(12));scroll.addView(body);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ImageView gift=new ImageView(a);gift.setImageDrawable(new ChatControlIcon("redpacket",RED));gift.setPadding(dp(12),dp(12),dp(12),dp(12));gift.setBackground(MallUi.surface(a,0xfffbe5dc,18,false));LinearLayout.LayoutParams giftLp=new LinearLayout.LayoutParams(dp(58),dp(58));giftLp.gravity=Gravity.CENTER;body.addView(gift,giftLp);
        TextView headline=center("把心意装进红包",23,MallUi.INK);headline.setTypeface(null,1);body.addView(headline);
        final String[] coin={"SPX001"};
        final TextView coinHint=center("SPX001 · 领取后自动存入钱包",12,MallUi.SECONDARY);body.addView(coinHint);
        LinearLayout coinRow=new LinearLayout(a);coinRow.setPadding(dp(4),dp(4),dp(4),dp(4));coinRow.setBackground(MallUi.surface(a,0xffebe7e1,12,false));
        final TextView spxChip=center("SPX001",14,MallUi.INK),fissChip=center("XKRS",14,MallUi.INK);
        coinRow.addView(spxChip,new LinearLayout.LayoutParams(0,dp(42),1));coinRow.addView(fissChip,new LinearLayout.LayoutParams(0,dp(42),1));
        LinearLayout.LayoutParams coinLp=new LinearLayout.LayoutParams(-1,-2);coinLp.setMargins(0,dp(14),0,0);body.addView(coinRow,coinLp);
        final int[] mode={group>0?1:0};LinearLayout segments=new LinearLayout(a);segments.setPadding(dp(4),dp(4),dp(4),dp(4));segments.setBackground(MallUi.surface(a,0xffebe7e1,12,false));TextView ordinary=center("普通红包",14,MallUi.INK),random=center("拼手气红包",14,MallUi.INK);segments.addView(ordinary,new LinearLayout.LayoutParams(0,dp(42),1));segments.addView(random,new LinearLayout.LayoutParams(0,dp(42),1));LinearLayout.LayoutParams segmentLp=new LinearLayout.LayoutParams(-1,-2);segmentLp.setMargins(0,dp(20),0,dp(16));body.addView(segments,segmentLp);
        Runnable updateMode=()->{ordinary.setBackground(MallUi.surface(a,mode[0]==0?Color.WHITE:Color.TRANSPARENT,9,false));random.setBackground(MallUi.surface(a,mode[0]==1?Color.WHITE:Color.TRANSPARENT,9,false));ordinary.setTextColor(mode[0]==0?RED:MallUi.SECONDARY);random.setTextColor(mode[0]==1?RED:MallUi.SECONDARY);ordinary.setSelected(mode[0]==0);random.setSelected(mode[0]==1);};updateMode.run();
        final TextView amountLabel=text("总金额 · SPX001",13,MallUi.SECONDARY);body.addView(amountLabel);
        EditText amount=new EditText(a);amount.setTextSize(34);amount.setTextColor(MallUi.INK);amount.setSingleLine(true);amount.setHint("0.00");amount.setHintTextColor(0xffc6bcb3);amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);amount.setPadding(dp(16),dp(14),dp(16),dp(14));amount.setBackground(MallUi.surface(a,Color.WHITE,14,false));amount.setContentDescription("红包总金额 SPX001");amount.setFilters(new InputFilter[]{new InputFilter.LengthFilter(17)});body.addView(amount,new LinearLayout.LayoutParams(-1,dp(78)));
        EditText count=field(body,group>0?"红包个数":"红包个数 · 个人红包固定 1 个","1–100 个",InputType.TYPE_CLASS_NUMBER);count.setText("1");count.setEnabled(group>0);count.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        TextView allocation=text("",12,MallUi.SECONDARY);body.addView(allocation);Runnable updateAllocation=()->{if(mode[0]==1){allocation.setText("每人随机金额，试试谁的手气最好");return;}try{BigDecimal total=new BigDecimal(amount.getText().toString());int n=Integer.parseInt(count.getText().toString());allocation.setText(n>0?"每人 "+pretty(total.divide(BigDecimal.valueOf(n),6,java.math.RoundingMode.DOWN).toPlainString())+" "+coin[0]:"每人金额相同");}catch(Exception e){allocation.setText("总金额按红包个数平均分配");}};
        ordinary.setOnClickListener(v->{mode[0]=0;updateMode.run();updateAllocation.run();});random.setOnClickListener(v->{mode[0]=1;updateMode.run();updateAllocation.run();});TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int c){updateAllocation.run();}public void afterTextChanged(Editable e){}};amount.addTextChangedListener(watcher);count.addTextChangedListener(watcher);updateAllocation.run();
        EditText greeting=field(body,"祝福语","恭喜发财，大吉大利",InputType.TYPE_CLASS_TEXT);greeting.setFilters(new InputFilter[]{new InputFilter.LengthFilter(50)});greeting.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        TextView balance=text("可用余额读取中…",12,MallUi.SECONDARY);body.addView(balance);final String server=ApiClient.baseUrl(a),token=ApiClient.token(a);
        Runnable loadBalance=new Runnable(){public void run(){balance.setText("可用余额读取中…");ApiClient.get(a,"wallet",null,new ApiClient.Callback(){public void onSuccess(Object data){if(!session(server,token)||!visible(page))return;String value="0";if(data instanceof JSONArray){JSONArray rows=(JSONArray)data;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&coin[0].equals(row.optString("symbol"))&&"BSC".equals(row.optString("chain","BSC")))value=row.optString("balance","0");}}balance.setText("可用余额 "+pretty(value)+" "+coin[0]);balance.setOnClickListener(null);}public void onError(String error){if(session(server,token)&&visible(page)){balance.setText("余额暂未获取 · 点击重试");balance.setOnClickListener(v->run());}}});}};loadBalance.run();
        Runnable updateCoin=()->{boolean spx="SPX001".equals(coin[0]);
            spxChip.setBackground(MallUi.surface(a,spx?Color.WHITE:Color.TRANSPARENT,9,false));
            fissChip.setBackground(MallUi.surface(a,spx?Color.TRANSPARENT:Color.WHITE,9,false));
            spxChip.setTextColor(spx?RED:MallUi.SECONDARY);fissChip.setTextColor(spx?MallUi.SECONDARY:RED);
            amountLabel.setText("总金额 · "+coin[0]);amount.setContentDescription("红包总金额 "+coin[0]);
            coinHint.setText(coin[0]+" · 领取后自动存入钱包");
            updateAllocation.run();loadBalance.run();};
        spxChip.setOnClickListener(v->{if("SPX001".equals(coin[0]))return;coin[0]="SPX001";updateCoin.run();});
        fissChip.setOnClickListener(v->{if("XKRS".equals(coin[0]))return;coin[0]="XKRS";updateCoin.run();});
        updateCoin.run();
        body.addView(text("24 小时未领完自动退回 · 每人限领一次"+(group>0?"\n仅发送时的群成员可以领取":""),12,MallUi.SECONDARY));
        LinearLayout footer=col();footer.setPadding(dp(24),dp(4),dp(24),dp(14));footer.setBackgroundColor(PAPER);
        sendingStatus=new LinearLayout(a);sendingStatus.setGravity(Gravity.CENTER_VERTICAL);sendingStatus.setVisibility(View.GONE);
        sendProgress=new ProgressBar(a);sendProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(RED));sendingStatus.addView(sendProgress,new LinearLayout.LayoutParams(dp(20),dp(20)));
        sendStatus=text("",12,MallUi.SECONDARY);sendStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(0,-2,1);statusParams.leftMargin=dp(10);sendingStatus.addView(sendStatus,statusParams);footer.addView(sendingStatus,new LinearLayout.LayoutParams(-1,-2));
        sendButton=button(footer,"塞进红包",()->{
            if(sending||recovering||(payment!=null&&payment.isShowing()))return;
            String pending=pending(key);if(!pending.isEmpty()){try{recover(new JSONObject(pending),key,page);}catch(Exception e){clearPending(key);sendState(false);}return;}
            String raw=amount.getText().toString().trim();int n;BigDecimal total;
            try{if(!raw.matches("(0|[1-9][0-9]{0,9})([.][0-9]{1,6})?"))throw new Exception();total=new BigDecimal(raw);if(total.signum()<=0||total.compareTo(new BigDecimal("1000000000"))>0)throw new Exception();}catch(Exception e){amount.setError("请输入有效金额，最多 6 位小数");amount.requestFocus();return;}
            try{n=Integer.parseInt(count.getText().toString());if(n<1||n>100||(group==0&&n!=1))throw new Exception();}catch(Exception e){count.setError("请输入 1–100 个红包");count.requestFocus();return;}
            if(total.movePointRight(6).compareTo(BigDecimal.valueOf(n))<0){amount.setError("每个红包至少 0.000001 "+coin[0]);amount.requestFocus();return;}
            if(mode[0]==0&&total.movePointRight(6).remainder(BigDecimal.valueOf(n)).signum()!=0){amount.setError("普通红包总额须能按 6 位小数均分");amount.requestFocus();return;}
            JSONObject request=obj("request_id",UUID.randomUUID().toString(),"scope",group>0?"group":"chat","target_id",group>0?group:chat,"mode",mode[0]==0?"ordinary":"random","count",n,"amount",raw,"greeting",greeting.getText().toString().trim(),"symbol",coin[0]);confirm(request,key,page);
        });page.addView(footer);a.redPacketShow(page);page.requestFocus();
    }
    private void confirm(JSONObject request,String key,LinearLayout form){
        if(checkingPayment)return;checkingPayment=true;final int epoch=generation;
        String server=ApiClient.baseUrl(a),token=ApiClient.token(a);
        ApiClient.get(a,"payment.password.status",null,new ApiClient.Callback(){
            public void onSuccess(Object result){if(epoch!=generation)return;checkingPayment=false;if(!session(server,token)||(form!=null&&!visible(form)))return;if(!(result instanceof JSONObject)){toast("支付密码状态异常，请重试");return;}if(!((JSONObject)result).optBoolean("configured")){new AlertDialog.Builder(a).setTitle("请先设置支付密码").setMessage("发红包需要独立的六位数字支付密码，首次可直接设置。").setNegativeButton("稍后",null).setPositiveButton("去设置",(d,w)->a.openPaymentPasswordSettings()).show();return;}confirmPayment(request,key,form);}
            public void onError(String error){if(epoch!=generation)return;checkingPayment=false;if(session(server,token))toast(error);}
        });
    }
    private void confirmPayment(JSONObject request,String key,LinearLayout form){
        if(payment!=null&&payment.isShowing())return;
        EditText password=new EditText(a);password.setSingleLine(true);password.setHint("输入六位数字支付密码");password.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);password.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});password.setSaveEnabled(false);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("发送 "+pretty(request.optString("amount"))+" "+request.optString("symbol","SPX001")).setMessage("将从钱包扣除总金额，发送后不可撤回。").setView(password).setNegativeButton("取消",null).setPositiveButton("确认发送",null).create();payment=dialog;
        dialog.setOnDismissListener(w->{password.setText("");if(payment==dialog)payment=null;});
        dialog.setOnShowListener(w->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(sending)return;if(!password.getText().toString().matches("[0-9]{6}")){password.setError("请输入六位数字支付密码");return;}
            final int epoch=generation;String server=ApiClient.baseUrl(a),token=ApiClient.token(a);JSONObject wire;try{wire=new JSONObject(request.toString()).put("payment_password",password.getText().toString());}catch(Exception e){return;}password.setText("");
            a.getSharedPreferences("spx_red_packets",0).edit().putString(key,request.toString()).apply();sendState(true);
            android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(password.getWindowToken(),0);
            dialog.dismiss();toast("正在发送红包，可返回聊天等待");
            ApiClient.post(a,"redpackets.create",wire,new ApiClient.Callback(){
                public void onSuccess(Object data){dialog.dismiss();if(epoch!=generation||!session(server,token))return;sendState(false);finishSend(data,request,key,form);}
                public void onError(String error){dialog.dismiss();if(epoch!=generation||!session(server,token))return;sendState(false);toast("发送结果待确认，正在查询");recover(request,key,form);}
                public void onHttpError(int status,String error){if(status==400||status==401||status==403||status==409||status==422||status==429){dialog.dismiss();if(epoch!=generation||!session(server,token))return;clearPending(key);sendState(false);toast(error);}else onError(error);}
            });
        }));dialog.show();PaymentPasswordSettings.focusNumericInput(dialog,password);
    }
    private void finishSend(Object data,JSONObject request,String key,LinearLayout form){
        if(!(data instanceof JSONObject)||((JSONObject)data).optJSONObject("packet")==null){toast("发送结果未确认，请查询后重试");sendState(false);return;}
        clearPending(key);sendState(false);if(form!=null)a.redPacketClose(form);JSONObject value=(JSONObject)data;JSONObject message=value.optJSONObject("message");if(message!=null)a.redPacketSent("chat".equals(request.optString("scope"))?request.optInt("target_id"):0,"group".equals(request.optString("scope"))?request.optInt("target_id"):0,message);toast("红包已发送");
    }
    private void recover(JSONObject request,String key,LinearLayout form){
        if(recovering)return;final int epoch=generation;recovering=true;sendState(true);String server=ApiClient.baseUrl(a),token=ApiClient.token(a);Map<String,String> p=new HashMap<>();p.put("request_id",request.optString("request_id"));
        ApiClient.get(a,"redpackets.request",p,new ApiClient.Callback(){
            public void onSuccess(Object data){if(epoch!=generation||!session(server,token))return;recovering=false;sendState(false);if(!(data instanceof JSONObject)){onError("结果暂未确认");return;}if(((JSONObject)data).optBoolean("exists")){finishSend(data,request,key,form);return;}
                new AlertDialog.Builder(a).setTitle("红包未发送").setMessage("未扣款，可继续发送这笔 "+pretty(request.optString("amount"))+" "+request.optString("symbol","SPX001")+" 红包。").setNegativeButton("放弃",(d,w)->{clearPending(key);sendState(false);}).setPositiveButton("继续发送",(d,w)->confirm(request,key,visible(form)?form:null)).show();}
            public void onError(String error){if(epoch!=generation||!session(server,token))return;recovering=false;sendState(false);new AlertDialog.Builder(a).setTitle("发送结果待确认").setMessage("请恢复网络后查询，避免重复创建红包。").setNegativeButton("稍后",null).setPositiveButton("查询结果",(d,w)->recover(request,key,visible(form)?form:null)).show();}
        });
    }
    View card(String raw,boolean mine){
        JSONObject data;try{data=new JSONObject(raw);}catch(Exception e){data=obj("greeting","红包信息不可用");}
        final String id=data.optString("packet_id");LinearLayout card=col();card.setPadding(dp(16),dp(12),dp(16),dp(10));card.setBackground(MallUi.surface(a,RED,12,false));MallUi.touch(card,12);
        LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(a);icon.setImageDrawable(new ChatControlIcon("redpacket",GOLD));heading.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView greeting=text(data.optString("greeting","恭喜发财，大吉大利"),16,Color.WHITE);greeting.setMaxLines(2);LinearLayout.LayoutParams gl=new LinearLayout.LayoutParams(0,-2,1);gl.leftMargin=dp(10);heading.addView(greeting,gl);card.addView(heading);
        card.addView(text("轻触打开红包",12,0xffffe1d6));View line=new View(a);line.setBackgroundColor(0x33ffffff);card.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));String cardSymbol=data.optString("symbol","SPX001");card.addView(text(("random".equals(data.optString("mode"))?"拼手气红包":"普通红包")+" · "+cardSymbol,11,0xffffe1d6));card.setContentDescription(cardSymbol+" 红包，点击领取或查看详情");card.setOnClickListener(v->detail(id));return card;
    }
    void detail(String id){
        if(id==null||!id.matches("[a-fA-F0-9]{32}")){toast("红包信息不可用");return;}
        if(popup!=null&&popup.dialog.isShowing()){if(popup.id.equals(id)||popup.claiming)return;popup.dialog.dismiss();}
        popup=new PacketPopup(id);popup.show();
    }
    static boolean positiveClaim(JSONObject packet){
        if(packet==null||packet.isNull("my_amount"))return false;
        try{return new BigDecimal(packet.getString("my_amount")).signum()>0;}
        catch(Exception invalid){return false;}
    }
    private final class PacketPopup {
        final String id,server=ApiClient.baseUrl(a),token=ApiClient.token(a);
        final Dialog dialog=new Dialog(a);
        final LinearLayout root=col(),cover=col(),details=col();
        final TextView sender=center("红包",17,GOLD),greeting=center("恭喜发财，大吉大利",23,GOLD),status=center("正在读取红包…",13,GOLD),link=center("",13,GOLD);
        final CoinView coin=new CoinView();
        final ProgressBar progress=new ProgressBar(a);
        final ScrollView records=new ScrollView(a);
        final FrameLayout coinHost=new FrameLayout(a);
        boolean loading,claiming,awaitingClaimReceipt;
        JSONObject packet;
        PacketPopup(String id){this.id=id;}
        boolean alive(){return session(server,token)&&dialog.isShowing()&&popup==this;}
        void show(){
            root.setBackground(MallUi.surface(a,RED,22,false));root.setClipToOutline(true);root.setTag("red-packet-popup");
            FrameLayout envelope=new FrameLayout(a);envelope.setBackground(new EnvelopeArt());cover.setPadding(dp(22),0,dp(22),dp(12));envelope.addView(cover,new FrameLayout.LayoutParams(-1,-2));
            TextView close=center("×",29,GOLD);close.setContentDescription("关闭红包");close.setOnClickListener(v->dialog.dismiss());FrameLayout.LayoutParams closeLp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);envelope.addView(close,closeLp);
            View top=new View(a);cover.addView(top,new LinearLayout.LayoutParams(1,dp(40)));ImageView emblem=new ImageView(a);emblem.setImageDrawable(new ChatControlIcon("redpacket",GOLD));LinearLayout.LayoutParams emblemLp=new LinearLayout.LayoutParams(dp(38),dp(38));emblemLp.gravity=Gravity.CENTER;cover.addView(emblem,emblemLp);
            sender.setMaxLines(1);sender.setEllipsize(TextUtils.TruncateAt.END);cover.addView(sender);greeting.setMaxLines(2);greeting.setEllipsize(TextUtils.TruncateAt.END);greeting.setTypeface(null,1);cover.addView(greeting);cover.addView(status);
            LinearLayout.LayoutParams hostLp=new LinearLayout.LayoutParams(-1,dp(122));cover.addView(coinHost,hostLp);FrameLayout.LayoutParams coinLp=new FrameLayout.LayoutParams(dp(100),dp(100),Gravity.CENTER);coinHost.addView(coin,coinLp);coin.setEnabled(false);coin.setAlpha(.65f);coin.setContentDescription("拆红包");coin.setOnClickListener(v->claim());coin.setCameraDistance(dp(1400));progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GOLD));coinHost.addView(progress,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
            cover.addView(link);root.addView(envelope,new LinearLayout.LayoutParams(-1,-2));
            details.setPadding(dp(20),dp(12),dp(20),dp(18));details.setBackgroundColor(PAPER);records.setFillViewport(false);records.addView(details);records.setVisibility(View.GONE);root.addView(records,new LinearLayout.LayoutParams(-1,0,1));
            dialog.setContentView(root);dialog.setOnDismissListener(w->{coin.animate().cancel();coin.setRotationY(0);if(popup==this)popup=null;});dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.55f);window.setGravity(Gravity.CENTER);window.setLayout(Math.min(dp(336),a.getResources().getDisplayMetrics().widthPixels-dp(48)),-2);}
            load(false);
        }
        void busy(boolean value,String label){coin.setEnabled(!value);coin.setAlpha(value?.65f:1f);progress.setVisibility(value?View.VISIBLE:View.GONE);coin.showLabel(!value);status.setText(label);}
        void load(boolean reconcile){
            if(loading||!alive())return;loading=true;busy(true,reconcile?"正在确认领取结果…":"正在读取红包…");link.setText("");link.setOnClickListener(null);Map<String,String> p=new HashMap<>();p.put("id",id);
            ApiClient.get(a,"redpackets.detail",p,new ApiClient.Callback(){public void onSuccess(Object value){if(!alive())return;loading=false;claiming=false;if(value instanceof JSONObject)render((JSONObject)value);else failure("红包信息返回异常，请重试");}public void onError(String error){if(!alive())return;loading=false;claiming=false;failure(reconcile?"领取结果待确认："+error:error);}public void onHttpError(int code,String error){onError(error);if(!alive())return;if(code==403||code==404||code==422){packet=null;records.setVisibility(View.GONE);coinHost.setVisibility(View.GONE);status.setText(error);link.setText(code==404?"红包不存在或已被移除":"当前账号无法打开此红包");link.setOnClickListener(null);}}});
        }
        void failure(String message){coin.animate().cancel();coin.setRotationY(0);coinHost.setVisibility(View.GONE);progress.setVisibility(View.GONE);coin.setEnabled(false);status.setText(message==null||message.trim().isEmpty()?"网络连接失败，请重新查询红包":message);link.setText("点击重新查询");link.setOnClickListener(v->load(awaitingClaimReceipt));}
        void claim(){
            if(!alive()||loading||claiming||packet==null||!packet.optBoolean("can_claim"))return;claiming=true;awaitingClaimReceipt=true;busy(true,"正在拆红包…");coin.animate().rotationYBy(360).setDuration(480).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            ApiClient.post(a,"redpackets.claim",obj("id",id),new ApiClient.Callback(){public void onSuccess(Object value){if(!session(server,token))return;if(value instanceof JSONObject&&id.equals(((JSONObject)value).optString("id")))a.redPacketReceipt((JSONObject)value);if(!alive())return;claiming=false;if(value instanceof JSONObject)render((JSONObject)value);else load(true);}public void onError(String error){if(!alive())return;coin.animate().cancel();coin.setRotationY(0);load(true);}public void onHttpError(int code,String error){if(code>=400&&code<500&&code!=408){if(!alive())return;claiming=false;awaitingClaimReceipt=false;failure(error);}else onError(error);}});
        }
        void render(JSONObject p){
            final String symbol=p.optString("symbol","SPX001");
            if(awaitingClaimReceipt){
                awaitingClaimReceipt=false;
                if(positiveClaim(p)&&id.equals(p.optString("id")))a.redPacketClaimed(id);
            }
            packet=p;coin.animate().cancel();coin.setRotationY(0);progress.setVisibility(View.GONE);coin.showLabel(true);sender.setText(p.optString("sender_name","好友")+"的红包");greeting.setText(p.optString("greeting","恭喜发财，大吉大利"));String state=p.optString("status");boolean received=p.has("my_amount")&&!p.isNull("my_amount"),can=p.optBoolean("can_claim")&&!received;boolean ended="expired".equals(state)||"completed".equals(state);
            details.removeAllViews();records.setVisibility(View.GONE);Window window=dialog.getWindow();if(window!=null)window.setLayout(Math.min(dp(336),a.getResources().getDisplayMetrics().widthPixels-dp(48)),-2);
            if(can){coinHost.setVisibility(View.VISIBLE);busy(false,"发来一份好心意");coin.setVisibility(View.VISIBLE);link.setText("轻触铜钱 · 拆开红包");link.setOnClickListener(null);if(p.has("claims")){link.setText("查看领取详情 ›");link.setOnClickListener(v->showRecords(p));}return;}
            coinHost.setVisibility(View.GONE);coin.setVisibility(View.GONE);status.setText(received?"已领取，心意已到账":"expired".equals(state)?"红包已过期":"completed".equals(state)?"手慢了，红包已领完":p.optInt("sender_id")==a.mallUserId()?"红包已发出，等待对方领取":p.optString("claim_disabled_reason","当前账号不在红包领取范围内"));
            link.setText(ended||received?symbol+" · 心意已送达":symbol+" · 红包");link.setOnClickListener(null);
            if(received){TextView amount=center(pretty(p.optString("my_amount")),34,RED);amount.setTypeface(null,1);details.addView(amount);details.addView(center(symbol,13,MallUi.SECONDARY));details.addView(center("已存入钱包可用余额",12,MallUi.SECONDARY));button(details,"查看钱包 / 提现",()->{dialog.dismiss();a.redPacketWallet();});}
            appendRecords(p);records.setVisibility(View.VISIBLE);resizeResult();
        }
        void showRecords(JSONObject p){details.removeAllViews();appendRecords(p);records.setVisibility(View.VISIBLE);resizeResult();link.setText("收起领取详情 ‹");link.setOnClickListener(v->{records.setVisibility(View.GONE);if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(336),a.getResources().getDisplayMetrics().widthPixels-dp(48)),-2);link.setText("查看领取详情 ›");link.setOnClickListener(w->showRecords(p));});}
        void resizeResult(){if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(dp(336),a.getResources().getDisplayMetrics().widthPixels-dp(48)),Math.min(dp(620),(int)(a.getResources().getDisplayMetrics().heightPixels*.84f)));}
        void appendRecords(JSONObject p){final String rsym=p.optString("symbol","SPX001");details.addView(text("已领取 "+p.optInt("claimed_count")+" / "+p.optInt("total_count")+" 个"+(p.has("total_amount")?" · 共 "+pretty(p.optString("total_amount"))+" "+rsym:""),12,MallUi.SECONDARY));if("expired".equals(p.optString("status"))&&p.has("refund_amount"))details.addView(text("未领取金额 "+pretty(p.optString("refund_amount"))+" "+rsym+" 已原路退回",12,MallUi.SECONDARY));JSONArray claims=p.optJSONArray("claims");if(claims!=null)for(int i=0;i<claims.length();i++){JSONObject c=claims.optJSONObject(i);if(c==null)continue;LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);TextView name=text(c.optString("nickname","好友"),14,MallUi.INK);name.setMaxLines(1);name.setEllipsize(TextUtils.TruncateAt.END);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.addView(text(pretty(c.optString("amount"))+" "+rsym,13,RED));details.addView(row);}}
    }
    /** A vector copper coin: no bitmap decode or allocation during the opening animation. */
    private final class CoinView extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF bounds=new RectF();boolean label=true;
        CoinView(){super(a);setFocusable(true);setLayerType(View.LAYER_TYPE_HARDWARE,null);}
        void showLabel(boolean show){label=show;invalidate();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xffffe4a6,0xffe8bb6b,0xfff5d58f},null,Shader.TileMode.CLAMP));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)-dp(4);paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);c.drawCircle(cx,cy,r,paint);Shader shader=paint.getShader();paint.setShader(null);paint.setColor(0xffbd8848);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));c.drawCircle(cx,cy,r-dp(5),paint);c.drawCircle(cx,cy,r-dp(8),paint);if(label){paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextSize(dp(32));paint.setTextAlign(Paint.Align.CENTER);c.drawText("拆",cx,cy-(paint.ascent()+paint.descent())/2,paint);}paint.setShader(shader);}
        @Override protected void onDetachedFromWindow(){animate().cancel();super.onDetachedFromWindow();}
    }
    private final class EnvelopeArt extends Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final Path flap=new Path();
        @Override protected void onBoundsChange(Rect bounds){float w=bounds.width(),h=bounds.height();flap.reset();flap.moveTo(0,0);flap.lineTo(w,0);flap.lineTo(w,h*.68f);flap.quadTo(w/2f,h*.94f,0,h*.68f);flap.close();paint.setShader(new LinearGradient(0,0,0,Math.max(1,h),0xffe56350,0xffd64d40,Shader.TileMode.CLAMP));}
        @Override public void draw(Canvas c){c.drawPath(flap,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    void history(){LinearLayout body=col();Dialog d=sheet("红包记录",body);body.addView(text("正在读取…",14,MallUi.SECONDARY));String server=ApiClient.baseUrl(a),token=ApiClient.token(a);ApiClient.get(a,"redpackets.mine",null,new ApiClient.Callback(){public void onSuccess(Object data){if(!session(server,token)||!d.isShowing())return;body.removeAllViews();if(!(data instanceof JSONArray)||((JSONArray)data).length()==0){body.addView(text("还没有红包记录",15,MallUi.SECONDARY));return;}JSONArray rows=(JSONArray)data;for(int i=0;i<rows.length();i++){JSONObject p=rows.optJSONObject(i);if(p==null)continue;TextView row=text((p.optInt("sender_id")==a.mallUserId()?"发出":"收到")+" · "+p.optString("greeting")+"\n"+p.optInt("claimed_count")+"/"+p.optInt("total_count")+" 个 · "+("expired".equals(p.optString("status"))?"已过期":"completed".equals(p.optString("status"))?"已领完":"进行中"),15,MallUi.INK);body.addView(row);row.setOnClickListener(v->{d.dismiss();detail(p.optString("id"));});}}public void onError(String error){if(d.isShowing()){body.removeAllViews();body.addView(text(error,15,RED));}}});}
}
