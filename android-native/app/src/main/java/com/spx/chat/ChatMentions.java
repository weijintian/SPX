package com.spx.chat;

import android.app.Dialog;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;

/** A bounded, searchable chooser. The server verifies membership for every request. */
final class ChatMentions {
    interface Selected { void choose(int id,String name); }
    interface Loader { void load(String query); }
    static Dialog show(Activity activity,int group,int owner,Selected selected){
        Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int unit=Math.max(1,Math.round(activity.getResources().getDisplayMetrics().density));
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(20*unit,18*unit,20*unit,16*unit);body.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(activity);title.setText("选择提醒的成员");title.setTextSize(19);title.setTextColor(0xff172334);body.addView(title);
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint("搜索群友名字");body.addView(search,new LinearLayout.LayoutParams(-1,52*unit));
        TextView everyone=new TextView(activity);everyone.setText("@ 全体成员");everyone.setTextColor(0xff2869ea);everyone.setTextSize(16);everyone.setPadding(0,16*unit,0,16*unit);body.addView(everyone);
        everyone.setOnClickListener(v->{selected.choose(-1,"全体成员");dialog.dismiss();});
        ScrollView scroll=new ScrollView(activity);LinearLayout rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView close=new TextView(activity);close.setText("取消");close.setTextSize(16);close.setGravity(android.view.Gravity.CENTER);close.setPadding(0,14*unit,0,0);body.addView(close);close.setOnClickListener(v->dialog.dismiss());
        dialog.setContentView(body);dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels-32*unit,420*unit),440*unit);dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        Handler handler=new Handler(Looper.getMainLooper());final int[] revision={0};final Runnable[] pending={null};
        Loader load=query->{
            final int request=++revision[0];rows.removeAllViews();TextView loading=new TextView(activity);loading.setText("正在加载…");rows.addView(loading);
            HashMap<String,String> params=new HashMap<>();params.put("group_id",String.valueOf(group));params.put("q",query);
            ApiClient.get(activity,"groups.mentions",params,new ApiClient.Callback(){
                public void onSuccess(Object value){
                    if(!dialog.isShowing()||request!=revision[0])return;rows.removeAllViews();JSONArray members=value instanceof JSONArray?(JSONArray)value:new JSONArray();
                    for(int i=0;i<members.length();i++){JSONObject member=members.optJSONObject(i);if(member==null)continue;int id=member.optInt("user_id",member.optInt("id"));if(id<=0||id==owner)continue;String rawName=member.optString("display_name",member.optString("contact_remark",member.optString("nickname","群友")));final String name=rawName==null||rawName.trim().isEmpty()||"null".equalsIgnoreCase(rawName)||"undefined".equalsIgnoreCase(rawName)?"群友":rawName;TextView row=new TextView(activity);row.setText(name);row.setTextSize(16);row.setTextColor(0xff172334);row.setPadding(0,14*unit,0,14*unit);rows.addView(row,new LinearLayout.LayoutParams(-1,-2));row.setOnClickListener(v->{selected.choose(id,name);dialog.dismiss();});}
                    if(rows.getChildCount()==0){TextView empty=new TextView(activity);empty.setText("没有找到匹配的群友");rows.addView(empty);}
                }
                public void onError(String message){if(!dialog.isShowing()||request!=revision[0])return;rows.removeAllViews();TextView retry=new TextView(activity);retry.setText(message+" · 点击重试");rows.addView(retry);retry.setOnClickListener(v->{search.setText(search.getText());});}
            });
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){revision[0]++;if(pending[0]!=null)handler.removeCallbacks(pending[0]);pending[0]=()->load.load(s.toString().trim());handler.postDelayed(pending[0],220);}public void afterTextChanged(Editable e){}});
        dialog.setOnDismissListener(d->{revision[0]++;handler.removeCallbacksAndMessages(null);});load.load("");return dialog;
    }
}
