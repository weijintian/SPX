package com.spx.chat;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Native controls for durable schedules and server-managed reply rules. */
final class ChatExperience {
    private final MainActivity a;
    private static final int PICK_SCHEDULE_IMAGE=4261;
    private interface Value<T>{void accept(T x);}
    private Value<Uri> pendingImage;
    ChatExperience(MainActivity a){this.a=a;}
    private int dp(int v){return (int)(v*a.getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(1);return l;}
    private TextView label(String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    private void toast(String s){AppNotice.show(a, s);}
    private JSONObject obj(Object... pairs){JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);}catch(JSONException e){throw new IllegalStateException(e);}return o;}
    private TextView button(LinearLayout p,String name,Runnable action,boolean primary){
        TextView t=label(name,15,primary?Color.WHITE:MallUi.PRIMARY);t.setGravity(Gravity.CENTER);t.setTypeface(null,1);t.setPadding(dp(12),dp(13),dp(12),dp(13));t.setBackground(MallUi.surface(a,primary?MallUi.PRIMARY:MallUi.TINT,14,false));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(4));p.addView(t,lp);t.setOnClickListener(v->action.run());return t;
    }
    private Dialog sheet(String title,Value<LinearLayout> build){
        Dialog d=new Dialog(a);LinearLayout root=col();root.setPadding(dp(20),dp(12),dp(20),dp(22));root.setBackground(MallUi.surface(a,Color.WHITE,24,false));
        TextView head=label(title,21,MallUi.INK);head.setTypeface(null,1);head.setPadding(0,dp(8),0,dp(16));root.addView(head);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);LinearLayout body=col();scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));build.accept(body);
        d.setContentView(root);d.show();Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);w.setLayout(-1,Math.min(dp(660),(int)(a.getResources().getDisplayMetrics().heightPixels*.85f)));w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}return d;
    }
    private EditText field(LinearLayout p,String hint,String value,boolean multi){
        TextView h=label(hint,13,MallUi.SECONDARY);h.setPadding(0,dp(12),0,dp(6));p.addView(h);EditText e=new EditText(a);e.setText(value);e.setTextColor(MallUi.INK);e.setTextSize(15);e.setSingleLine(!multi);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(MallUi.surface(a,MallUi.BACKGROUND,12,true));if(multi){e.setMinLines(3);e.setMaxLines(6);e.setGravity(Gravity.TOP);e.setFilters(new InputFilter[]{ChatText.FILTER});}p.addView(e,new LinearLayout.LayoutParams(-1,-2));if(multi)counter(p,e);return e;
    }
    static void counter(LinearLayout p,EditText e){
        TextView count=new TextView(e.getContext());count.setTextSize(11);count.setTextColor(MallUi.SECONDARY);count.setGravity(Gravity.RIGHT);count.setPadding(8,4,12,6);p.addView(count);
        Runnable update=()->count.setText(ChatText.count(e.getText())+" / 10000");update.run();e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int af){}public void onTextChanged(CharSequence s,int st,int b,int c){update.run();}public void afterTextChanged(Editable v){}});
    }
    private void post(String endpoint,JSONObject data,TextView button,Value<Object> success){
        if(button!=null)button.setEnabled(false);ApiClient.post(a,endpoint,data,new ApiClient.Callback(){public void onSuccess(Object o){if(button!=null)button.setEnabled(true);success.accept(o);}public void onError(String m){if(button!=null)button.setEnabled(true);toast(m);}});
    }
    boolean activityResult(int request,int result,Intent data){
        if(request!=PICK_SCHEDULE_IMAGE)return false;Value<Uri> cb=pendingImage;pendingImage=null;Uri selected=ChatGallery.selectedUri(data);if(selected!=null&&cb!=null)cb.accept(selected);else if(result==Activity.RESULT_OK)toast("未读取到相册图片，请重新选择");return true;
    }
    void schedule(int chat,int group,String draft){
        if(chat<=0&&group<=0){toast("请先打开一个会话");return;}
        String scope=group>0?"group":"chat";int target=group>0?group:chat;Calendar when=Calendar.getInstance();when.add(Calendar.MINUTE,5);when.set(Calendar.SECOND,0);when.set(Calendar.MILLISECOND,0);
        String[] photo={""};boolean[] uploading={false};String requestId=UUID.randomUUID().toString();Dialog[] d={null};
        d[0]=sheet("定时发送",body->{
            body.addView(label("设置后由服务器发送，关闭 App 也可执行。发送时仍会检查好友、群成员和禁言权限。",13,MallUi.SECONDARY));
            EditText text=field(body,"文字内容（图片可附说明）",draft,true);
            ImageView preview=new ImageView(a);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setVisibility(View.GONE);body.addView(preview,new LinearLayout.LayoutParams(-1,dp(120)));
            TextView photoState=label("尚未选择图片",12,MallUi.SECONDARY);body.addView(photoState);
            button(body,"从相册选择图片",()->{pendingImage=uri->{if(!d[0].isShowing())return;uploading[0]=true;photoState.setText("图片上传中…");ApiClient.upload(a,"upload.message",uri,new ApiClient.Callback(){public void onSuccess(Object value){uploading[0]=false;if(value instanceof JSONObject){JSONObject upload=(JSONObject)value;if(!"image".equals(upload.optString("message_type"))){toast("请选择图片");return;}photo[0]=upload.optString("url");photoState.setText("图片已上传");preview.setVisibility(View.VISIBLE);MallImages.bind(preview,photo[0],false);}}public void onError(String m){uploading[0]=false;photoState.setText("上传失败，请重试");toast(m);}});};ChatGallery.open(a,"image/*",PICK_SCHEDULE_IMAGE);},false);
            button(body,"移除图片",()->{photo[0]="";preview.setVisibility(View.GONE);photoState.setText("尚未选择图片");},false);
            TextView date=button(body,"",()->{},false);Runnable dateText=()->date.setText("发送时间："+new java.text.SimpleDateFormat("MM月dd日 HH:mm z",Locale.CHINA).format(when.getTime()));dateText.run();
            date.setOnClickListener(v->{DatePickerDialog picker=new DatePickerDialog(a,(view,y,m,day)->{Calendar chosen=(Calendar)when.clone();chosen.set(y,m,day);new TimePickerDialog(a,(time,h,min)->{chosen.set(Calendar.HOUR_OF_DAY,h);chosen.set(Calendar.MINUTE,min);when.setTimeInMillis(chosen.getTimeInMillis());dateText.run();},when.get(Calendar.HOUR_OF_DAY),when.get(Calendar.MINUTE),true).show();},when.get(Calendar.YEAR),when.get(Calendar.MONTH),when.get(Calendar.DAY_OF_MONTH));picker.getDatePicker().setMinDate(System.currentTimeMillis());picker.getDatePicker().setMaxDate(System.currentTimeMillis()+90L*86400000);picker.show();});
            TextView save=button(body,"确认定时发送",()->{},true);save.setOnClickListener(v->{if(uploading[0]){toast("请等待图片上传完成");return;}String value=text.getText().toString().trim();if(value.isEmpty()&&photo[0].isEmpty()){toast("请输入文字或选择图片");return;}long seconds=when.getTimeInMillis()/1000;if(seconds<System.currentTimeMillis()/1000+30){toast("请选择至少 30 秒后的时间");return;}post("chat.schedule.create",obj("scope",scope,"target_id",target,"content",value.isEmpty()?"[图片]":value,"message_type",photo[0].isEmpty()?"text":"image","media_url",photo[0],"client_message_id",requestId,"send_at_epoch",seconds),save,o->{d[0].dismiss();toast("已设置定时发送，可在定时消息中查看或取消");});});
            button(body,"查看当前会话的定时消息",()->schedules(chat,group),false);
        });
    }
    void schedules(int chat,int group){
        Map<String,String> args=new HashMap<>();if(chat>0||group>0){args.put("scope",group>0?"group":"chat");args.put("target_id",String.valueOf(group>0?group:chat));}
        ApiClient.get(a,"chat.schedule.list",args,new ApiClient.Callback(){public void onSuccess(Object data){JSONArray rows=data instanceof JSONArray?(JSONArray)data:new JSONArray();Dialog[] d={null};d[0]=sheet("定时消息",body->{if(rows.length()==0)body.addView(label("暂无定时消息，在聊天页点击「＋」→「定时发送」创建。",14,MallUi.SECONDARY));for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);if(r==null)continue;String state=r.optString("status");String status="pending".equals(state)?"待发送":"sent".equals(state)?"已发送":"failed".equals(state)?"发送失败":"已取消";TextView title=label(r.optString("send_at")+"（北京时间） · "+status,14,MallUi.INK);title.setPadding(0,dp(14),0,dp(4));body.addView(title);TextView preview=label(("image".equals(r.optString("message_type"))?"[图片] ":"")+r.optString("content"),13,MallUi.SECONDARY);preview.setMaxLines(3);preview.setEllipsize(TextUtils.TruncateAt.END);body.addView(preview);if("failed".equals(state))body.addView(label(r.optString("error_message","发送失败"),12,Color.rgb(190,55,55)));if("pending".equals(state)){TextView cancel=button(body,"取消这条定时消息",()->{},false);cancel.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("取消定时发送？").setNegativeButton("保留",null).setPositiveButton("取消发送",(x,y)->post("chat.schedule.cancel",obj("id",r.optInt("id")),cancel,o->{d[0].dismiss();schedules(chat,group);})).show());}}});}public void onError(String m){toast(m);}});
    }
    void rules(int group){
        Map<String,String> args=new HashMap<>();args.put("group_id",String.valueOf(group));ApiClient.get(a,"chat.rules.list",args,new ApiClient.Callback(){public void onSuccess(Object value){JSONArray rules=value instanceof JSONArray?(JSONArray)value:new JSONArray();if(group==0){editRule(0,rules.optJSONObject(0));return;}Dialog[] d={null};d[0]=sheet("群机器人",body->{body.addView(label("仅群主、管理员可设置。每条消息最多触发一次；自动回复不会再次触发机器人。",13,MallUi.SECONDARY));button(body,"＋ 添加触发词规则",()->{d[0].dismiss();editRule(group,null);},true);for(int i=0;i<rules.length();i++){JSONObject r=rules.optJSONObject(i);if(r==null)continue;button(body,(r.optInt("enabled")==1?"已开启 · ":"已关闭 · ")+r.optString("keyword"),()->{d[0].dismiss();editRule(group,r);},false);}});}public void onError(String m){toast(m);}});
    }
    private void editRule(int group,JSONObject existing){
        JSONObject r=existing==null?new JSONObject():existing;Dialog[] d={null};d[0]=sheet(group>0?"编辑群回复规则":"我的自动回复",body->{
            body.addView(label(group>0?"由当前管理员身份回复，并标记为自动回复。失去管理员权限后规则不再触发。":"开启后，好友发来消息时自动回复。对每位好友按间隔限频，关闭 App 也能回复。",13,MallUi.SECONDARY));
            Switch enabled=new Switch(a);enabled.setText("启用自动回复");enabled.setChecked(r.optInt("enabled",1)==1);body.addView(enabled);
            EditText keyword=group>0?field(body,"触发词（最多 100 字）",r.optString("keyword"),false):null;
            Switch exact=new Switch(a);exact.setText("完全匹配（关闭时包含触发词即可）");exact.setChecked("exact".equals(r.optString("match_mode")));if(group>0)body.addView(exact);
            EditText reply=field(body,"回复内容",r.optString("reply_text"),true);EditText cooldown=field(body,"最短回复间隔 / 秒（30–86400）",String.valueOf(r.optInt("cooldown_seconds",60)),false);cooldown.setInputType(InputType.TYPE_CLASS_NUMBER);
            TextView save=button(body,"保存设置",()->{},true);save.setOnClickListener(v->{int seconds;try{seconds=Integer.parseInt(cooldown.getText().toString());}catch(Exception e){toast("请填写回复间隔");return;}if(reply.getText().toString().trim().isEmpty()||seconds<30||seconds>86400){toast("请填写回复内容，间隔为 30–86400 秒");return;}if(keyword!=null&&(keyword.getText().toString().trim().isEmpty()||ChatText.count(keyword.getText())>100)){toast("触发词为 1–100 字");return;}post("chat.rules.save",obj("id",r.optInt("id"),"group_id",group,"enabled",enabled.isChecked(),"keyword",keyword==null?"":keyword.getText().toString().trim(),"match_mode",exact.isChecked()?"exact":"contains","reply_text",reply.getText().toString().trim(),"cooldown_seconds",seconds),save,o->{d[0].dismiss();toast("自动回复设置已保存");});});
            if(r.optInt("id")>0)button(body,"删除这条规则",()->new AlertDialog.Builder(a).setTitle("删除回复规则？").setNegativeButton("取消",null).setPositiveButton("删除",(x,y)->post("chat.rules.delete",obj("group_id",group,"id",r.optInt("id")),null,o->{d[0].dismiss();toast("规则已删除");})).show(),false);
        });
    }
    void forwardMessage(JSONObject source){
        forwardMessages(new JSONArray().put(source));
    }
    void forwardMessages(JSONArray sources){
        for(int i=0;i<sources.length();i++){JSONObject item=sources.optJSONObject(i);if(item!=null&&"red_packet".equals(item.optString("message_type"))){toast("红包不可转发，请在目标会话重新发红包");return;}}
        JSONArray[] data={null,null};boolean[] loaded={false,false};
        Runnable ready=()->{if(!loaded[0]||!loaded[1])return;ArrayList<JSONObject> targets=new ArrayList<>();ArrayList<String> names=new ArrayList<>();
            for(int kind=0;kind<2;kind++){JSONArray rows=data[kind];if(rows==null)continue;for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);if(r==null)continue;targets.add(obj("group",kind==1,"id",r.optInt("id")));String name=kind==1?r.optString("name"):r.optString("remark");if(name.isEmpty())name=r.optString("nickname","好友");names.add(name+(kind==1?" · 群聊":""));}}
            if(targets.isEmpty()){toast("暂无可转发的好友或群聊");return;}Dialog[] dialog={null};boolean[] choosing={false};
            dialog[0]=sheet("转发消息",body->{EditText search=field(body,"搜索好友 / 群聊","",false);LinearLayout list=col();body.addView(list);Runnable render=()->{list.removeAllViews();String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);
                for(int i=0;i<targets.size();i++){final int n=i;if(!names.get(i).toLowerCase(Locale.ROOT).contains(q))continue;button(list,names.get(i),()->{if(choosing[0])return;new AlertDialog.Builder(a).setTitle("转发给 "+names.get(n)).setMessage("确认逐条转发这 "+sources.length()+" 条消息？").setNegativeButton("取消",null).setPositiveButton("转发",(d,w)->{if(choosing[0])return;choosing[0]=true;JSONObject target=targets.get(n);if(target.optBoolean("group")){a.queueForwardMessages(0,target.optInt("id"),sources);dialog[0].dismiss();}else ApiClient.post(a,"chats.create",obj("peer_user_id",target.optInt("id")),new ApiClient.Callback(){public void onSuccess(Object v){int id=v instanceof JSONObject?((JSONObject)v).optInt("chat_id"):0;if(id<=0){choosing[0]=false;toast("无法创建会话");return;}a.queueForwardMessages(id,0,sources);dialog[0].dismiss();}public void onError(String error){choosing[0]=false;toast(error);}});}).show();},false);}
            };render.run();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){}public void onTextChanged(CharSequence s,int a,int b,int c){render.run();}public void afterTextChanged(Editable e){}});});
        };
        for(int i=0;i<2;i++){final int kind=i;ApiClient.get(a,i==0?"contacts":"groups",null,new ApiClient.Callback(){public void onSuccess(Object value){data[kind]=value instanceof JSONArray?(JSONArray)value:new JSONArray();loaded[kind]=true;ready.run();}public void onError(String error){loaded[kind]=true;toast(error);ready.run();}});}
    }
    void shareProduct(JSONObject product){
        JSONArray[] datasets={null,null};boolean[] finished={false,false};
        Runnable done=()->{if(!finished[0]||!finished[1])return;ArrayList<JSONObject> targets=new ArrayList<>();ArrayList<String> names=new ArrayList<>();for(int kind=0;kind<2;kind++){JSONArray data=datasets[kind];if(data==null)continue;for(int i=0;i<data.length();i++){JSONObject item=data.optJSONObject(i);if(item==null)continue;targets.add(obj("group",kind==1,"id",item.optInt("id")));String name=kind==1?item.optString("name"):item.optString("remark");if(name.isEmpty())name=item.optString("nickname","好友");names.add(name+(kind==1?" · 群聊":""));}}if(targets.isEmpty()){toast("暂没有可分享的好友或群聊");return;}Dialog[] d={null};d[0]=sheet("发送商品卡",body->{body.addView(label(product.optString("name"),17,MallUi.INK));body.addView(label("选择好友或群聊，对方点击卡片即可查看商品",12,MallUi.SECONDARY));EditText search=field(body,"搜索好友 / 群聊","",false);LinearLayout list=col();body.addView(list);Runnable refresh=()->{list.removeAllViews();String key=search.getText().toString().toLowerCase(Locale.ROOT);for(int i=0;i<targets.size();i++){final int n=i;if(!names.get(i).toLowerCase(Locale.ROOT).contains(key))continue;button(list,names.get(i),()->new AlertDialog.Builder(a).setTitle("发送给 "+names.get(n)).setMessage(product.optString("name")+" · "+((product.isNull("cny_price")||product.optString("cny_price","").isEmpty())?MallMoney.show(product.optString("spx_price"))+" SPX001":"¥"+MallMoney.show(product.optString("cny_price"))+" CNY")).setNegativeButton("取消",null).setPositiveButton("发送商品卡",(x,y)->{JSONObject t=targets.get(n);boolean group=t.optBoolean("group");if(group)sendProduct(product,t.optInt("id"),true,d[0]);else post("chats.create",obj("peer_user_id",t.optInt("id")),null,o->{if(o instanceof JSONObject)sendProduct(product,((JSONObject)o).optInt("chat_id"),false,d[0]);});}).show(),false);}};refresh.run();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int af){}public void onTextChanged(CharSequence s,int st,int b,int c){refresh.run();}public void afterTextChanged(Editable e){}});});};
        for(int i=0;i<2;i++){final int n=i;ApiClient.get(a,i==0?"contacts":"groups",null,new ApiClient.Callback(){public void onSuccess(Object o){datasets[n]=o instanceof JSONArray?(JSONArray)o:new JSONArray();finished[n]=true;done.run();}public void onError(String m){finished[n]=true;toast(m);done.run();}});}
    }
    void shareGroup(JSONObject source) {
        JSONArray[] data = {null, null};
        boolean[] loaded = {false, false};
        Runnable ready = () -> {
            if (!loaded[0] || !loaded[1]) return;
            ArrayList<JSONObject> targets = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            for (int kind = 0; kind < 2; kind++) {
                JSONArray items = data[kind];
                if (items == null) continue;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || (kind == 1 && item.optInt("id") == source.optInt("id"))) continue;
                    JSONObject target = obj("group", kind == 1, "id", item.optInt("id"), "name", item.optString("name"),
                            "nickname", item.optString("nickname"), "remark", item.optString("remark"), "avatar_url", item.optString("avatar_url"));
                    String name = kind == 1 ? item.optString("name") : item.optString("remark");
                    if (name.isEmpty()) name = item.optString("nickname", "好友");
                    targets.add(target); names.add(name + (kind == 1 ? " · 群聊" : " · 好友"));
                }
            }
            if (targets.isEmpty()) { toast("暂无可分享的好友或其他群聊"); return; }
            Dialog[] dialog = {null};
            boolean[] sending = {false};
            dialog[0] = sheet("分享群组", body -> {
                body.addView(label(source.optString("name"), 17, MallUi.INK));
                body.addView(label("选择好友或其他群聊，发送可点击加入的群组卡片", 12, MallUi.SECONDARY));
                EditText query = field(body, "搜索好友 / 群聊", "", false);
                LinearLayout list = col(); body.addView(list);
                Runnable render = () -> {
                    list.removeAllViews();
                    String key = query.getText().toString().trim().toLowerCase(Locale.ROOT);
                    int matches = 0;
                    for (int i = 0; i < targets.size(); i++) {
                        if (!names.get(i).toLowerCase(Locale.ROOT).contains(key)) continue;
                        final int n = i;
                        button(list, names.get(i), () -> {
                            if (sending[0]) { toast("群组卡片正在发送"); return; }
                            new AlertDialog.Builder(a).setTitle("发送给 " + names.get(n)).setMessage(source.optString("name") + " · 群组邀请")
                                .setNegativeButton("取消", null).setPositiveButton("发送群组卡片", (d, w) -> {
                                    if (sending[0]) return;
                                    sending[0] = true;
                                    JSONObject target = targets.get(n);
                                    String clientId = UUID.randomUUID().toString();
                                    if (target.optBoolean("group")) sendGroupCard(source, target, 0, clientId, dialog[0], sending);
                                    else ApiClient.post(a, "chats.create", obj("peer_user_id", target.optInt("id")), new ApiClient.Callback() {
                                        public void onSuccess(Object value) {
                                            int id = value instanceof JSONObject ? ((JSONObject)value).optInt("chat_id") : 0;
                                            if (id <= 0) { sending[0] = false; toast("无法创建会话，请重试"); return; }
                                            sendGroupCard(source, target, id, clientId, dialog[0], sending);
                                        }
                                        public void onError(String error) { sending[0] = false; toast(error); }
                                    });
                                }).show();
                        }, false);
                        matches++;
                    }
                    if (matches == 0) list.addView(label("没有匹配的好友或群聊", 13, MallUi.SECONDARY));
                };
                render.run();
                query.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                    public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
                    public void afterTextChanged(Editable e) { }
                });
            });
        };
        for (int i = 0; i < 2; i++) {
            final int kind = i;
            ApiClient.get(a, i == 0 ? "contacts" : "groups", null, new ApiClient.Callback() {
                public void onSuccess(Object value) { data[kind] = value instanceof JSONArray ? (JSONArray)value : new JSONArray(); loaded[kind] = true; ready.run(); }
                public void onError(String error) { loaded[kind] = true; toast(error); ready.run(); }
            });
        }
    }

    private void sendGroupCard(JSONObject source, JSONObject target, int chatId, String clientId, Dialog dialog, boolean[] sending) {
        boolean group = target.optBoolean("group");
        int groupId = group ? target.optInt("id") : 0;
        JSONObject body = obj(group ? "group_id" : "chat_id", group ? groupId : chatId,
                "share_group_id", source.optInt("id"), "message_type", "text", "client_message_id", clientId);
        ApiClient.post(a, group ? "groups.messages.send" : "messages.send", body, new ApiClient.Callback() {
            public void onSuccess(Object value) {
                sending[0] = false;
                if (value instanceof JSONObject) a.sharedGroupCardSent(chatId, groupId, target, (JSONObject)value);
                dialog.dismiss(); toast("群组卡片已发送");
            }
            public void onError(String error) {
                sending[0] = false;
                new AlertDialog.Builder(a).setTitle("群组卡片发送失败").setMessage(error)
                    .setNegativeButton("取消", null).setPositiveButton("重试", (d, w) -> {
                        if (!sending[0]) { sending[0] = true; sendGroupCard(source, target, chatId, clientId, dialog, sending); }
                    }).show();
            }
        });
    }

    private void sendProduct(JSONObject p,int target,boolean group,Dialog dialog){post(group?"groups.messages.send":"messages.send",obj(group?"group_id":"chat_id",target,"product_id",p.optInt("id"),"message_type","product","client_message_id",UUID.randomUUID().toString()),null,o->{dialog.dismiss();toast("商品卡已发送，点击即可查看商品");});}
}
