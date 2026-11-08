package com.spx.chat;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.io.*;
import java.net.HttpURLConnection;

/** Application photos stay private; only the shop logo is public. */
final class MallMerchantForm {
    static final int PICK_IMAGE=4260;
    private final MainActivity a;
    private final boolean application;
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private final ArrayList<String> qualifications=new ArrayList<>();
    private String logo,level;
    private final EditText latitude,longitude;
    private final String[] system;
    private LinearLayout photos;
    private ImageView avatar;
    private TextView submit,uploadStatus,logoPick,qualificationPick;
    private boolean uploading,sending;
    private String pickKind;
    MallMerchantForm(MainActivity activity,JSONObject shop,boolean apply){
        a=activity;application=apply;logo=value(shop,"logo_url");level=shop==null?"basic":shop.optString("level","basic");
        latitude=new EditText(a);latitude.setText(value(shop,"latitude"));
        longitude=new EditText(a);longitude.setText(value(shop,"longitude"));
        system=new String[]{shop==null?"wgs84":shop.optString("coordinate_system","gcj02")};
        JSONArray images=shop==null?null:shop.optJSONArray("qualification_images");
        if(images!=null)for(int i=0;i<images.length();i++)qualifications.add(images.optString(i));
        LinearLayout body=a.mallMerchantPage(application?"申请开店":"店铺资料");
        LinearLayout intro=card(body,"开店资料");intro.addView(text("提交真实资料，平台审核通过后缴纳保证金即可营业。",13,MallUi.SECONDARY));
        LinearLayout basic=card(body,"基本信息");
        field(basic,"name","店铺名称 *",value(shop,"name"),false);
        field(basic,"category","经营类目 *",value(shop,"category"),false).setEnabled(application);
        field(basic,"contact_name","联系人 *",value(shop,"contact_name"),false);
        field(basic,"contact_phone","联系电话 *",value(shop,"contact_phone"),false).setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        LinearLayout brand=card(body,"店铺头像");avatar=new ImageView(a);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);avatar.setBackground(MallUi.surface(a,MallUi.TINT,16,false));avatar.setClipToOutline(true);avatar.setContentDescription("店铺头像");brand.addView(avatar,new LinearLayout.LayoutParams(dp(96),dp(96)));MallImages.bind(avatar,logo,false);
        logoPick=action(brand,logo.isEmpty()?"从手机上传头像":"更换头像",()->pick("logo"),false);
        action(brand,"移除头像",()->{if(uploading||sending)return;logo="";MallImages.bind(avatar,"",false);logoPick.setText("从手机上传头像");},false);
        field(brand,"description","店铺简介（选填）",value(shop,"description"),true);
        LinearLayout docs=card(body,"经营资质");docs.addView(text(application?"上传营业执照或经营许可证，最多六张。图片仅本人和平台审核人员可查看。":"已审核资质由平台保存，需要变更请联系平台。",13,MallUi.SECONDARY));
        photos=col();docs.addView(photos);renderPhotos();
        if(application)qualificationPick=action(docs,"从手机上传资质图片",()->pick("qualification"),false);
        field(docs,"qualification","资质说明（选填）",value(shop,"qualification"),true).setEnabled(application);
        LinearLayout location=card(body,"门店位置");
        field(location,"business_hours","营业时间（选填）",value(shop,"business_hours"),false);
        field(location,"address","详细地址（含楼层、门牌）",value(shop,"address"),true);
        TextView map=action(location,hasPoint()?"已选择店铺位置 · 重新选择":"从地图选择店铺位置",()->{},false);
        map.setOnClickListener(v->a.mallMerchantMap(latitude,longitude,system,()->map.setText("已选择店铺位置 · 重新选择")));
        if(application){LinearLayout tiers=card(body,"店铺等级");TextView choose=action(tiers,tierLabel(),()->{},false);choose.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("选择店铺等级").setItems(new String[]{"初级 · 保证金 100,000 SPX001","黄金 · 保证金 1,000,000 SPX001 · 优先曝光"},(d,n)->{level=n==0?"basic":"gold";choose.setText(tierLabel());}).show());tiers.addView(text("申请时不扣款，审核通过后再缴纳保证金。",12,MallUi.SECONDARY));}
        uploadStatus=text("",13,MallUi.SECONDARY);body.addView(uploadStatus);
        submit=action(body,application?"提交开店申请":"保存店铺资料",this::send,true);a.mallPin(body,submit);
    }
    private boolean hasPoint(){return !latitude.getText().toString().isEmpty()&&!longitude.getText().toString().isEmpty();}
    private String tierLabel(){return "gold".equals(level)?"黄金店铺 · 1,000,000 SPX001 · 优先曝光":"初级店铺 · 100,000 SPX001";}
    private void pick(String kind){if(uploading||sending)return;if("qualification".equals(kind)&&qualifications.size()>=6){toast("最多上传六张资质图片");return;}pickKind=kind;if(!ChatGallery.open(a,"image/*",PICK_IMAGE))pickKind=null;}
    boolean activityResult(int request,int result,Intent data){
        if(request!=PICK_IMAGE)return false;String kind=pickKind;pickKind=null;
        if(kind==null){toast("本次选图已失效，请重新选择");return true;}
        Uri selected=ChatGallery.selectedUri(data);
        if(selected==null&&result!=Activity.RESULT_OK){uploadStatus.setText("未选择图片，点击上传按钮可重试");return true;}
        if(selected==null){uploadStatus.setText("未读取到相册图片，请重新选择");toast("未读取到相册图片，请重新选择");return true;}
        uploading=true;busy();uploadStatus.setText("正在处理并上传图片…");
        MallUpload.upload(a,"logo".equals(kind)?"merchant.logo.upload":"merchant.qualification.upload",selected,new ApiClient.Callback(){
            public void onSuccess(Object d){uploading=false;busy();if(!(d instanceof JSONObject)){onError("上传未完成，请重试");return;}JSONObject x=(JSONObject)d;String saved=x.optString("logo".equals(kind)?"url":"id");if(saved.isEmpty()){onError("上传未完成，请重试");return;}if("logo".equals(kind)){logo=saved;MallImages.bind(avatar,logo,false);logoPick.setText("更换头像");}else{qualifications.add(saved);renderPhotos();}uploadStatus.setText("图片已上传");}
            public void onError(String s){uploading=false;busy();uploadStatus.setText(s+"，点击上传按钮可重试");toast(s);}
        });return true;
    }
    private void busy(){submit.setEnabled(!uploading&&!sending);submit.setAlpha(uploading||sending?.5f:1f);logoPick.setEnabled(!uploading&&!sending);if(qualificationPick!=null)qualificationPick.setEnabled(!uploading&&!sending);}
    private void renderPhotos(){photos.removeAllViews();for(String id:new ArrayList<>(qualifications)){LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(MallUi.BACKGROUND);image.setContentDescription("经营资质图片");row.addView(image,new LinearLayout.LayoutParams(dp(104),dp(104)));privateImage(image,id);TextView label=text("资质图片 "+(qualifications.indexOf(id)+1),13,MallUi.INK);label.setPadding(dp(12),0,0,0);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));if(application)action(row,"删除",()->{if(!uploading&&!sending){qualifications.remove(id);renderPhotos();}},false);photos.addView(row);image.setOnClickListener(v->{ImageView full=new ImageView(a);full.setScaleType(ImageView.ScaleType.FIT_CENTER);privateImage(full,id);new AlertDialog.Builder(a).setTitle("经营资质").setView(full).setPositiveButton("关闭",null).show();});}}
    private void privateImage(ImageView view,String id){
        final String auth=ApiClient.token(a);view.setImageResource(android.R.drawable.ic_menu_gallery);
        new Thread(()->{HttpURLConnection c=null;try{Map<String,String> q=new HashMap<>();q.put("id",id);c=ApiClient.openConnection(a,ApiClient.actionUrl(a,"merchant.qualification.image",q));c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Authorization","Bearer "+auth);byte[] bytes;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>5*1024*1024)throw new IOException();out.write(b,0,n);}bytes=out.toByteArray();}BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);o.inSampleSize=1;while((long)o.outWidth*o.outHeight/(o.inSampleSize*o.inSampleSize)>2000000)o.inSampleSize*=2;o.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);a.runOnUiThread(()->{if(auth.equals(ApiClient.token(a))&&bitmap!=null)view.setImageBitmap(bitmap);});}catch(Exception|OutOfMemoryError e){a.runOnUiThread(()->view.setContentDescription("资质图片加载失败，可重新打开查看"));}finally{if(c!=null)c.disconnect();}},"shop-private-image").start();
    }
    private void send(){if(uploading||sending)return;JSONObject req=new JSONObject();try{for(String key:fields.keySet())req.put(key,fields.get(key).getText().toString().trim());for(String key:new String[]{"name","category","contact_name","contact_phone"})if(req.optString(key).isEmpty()){fields.get(key).setError("请填写此项");fields.get(key).requestFocus();return;}if(hasPoint()&&req.optString("address").isEmpty()){fields.get("address").setError("请补充门店详细地址");fields.get("address").requestFocus();return;}req.put("logo_url",logo);req.put("qualification_images",new JSONArray(qualifications));req.put("latitude",latitude.getText().toString());req.put("longitude",longitude.getText().toString());req.put("coordinate_system",system[0]);req.put("level",level);}catch(JSONException e){toast("请检查资料后重试");return;}sending=true;busy();submit.setText("正在提交…");ApiClient.post(a,application?"merchant.apply":"merchant.profile.save",req,new ApiClient.Callback(){public void onSuccess(Object d){sending=false;toast(application?"申请已提交，等待平台审核":"店铺资料已保存");a.mallMerchantDone();}public void onError(String s){sending=false;busy();submit.setText(application?"提交开店申请":"保存店铺资料");toast(s);}});}
    private String value(JSONObject x,String key){return x==null||x.isNull(key)?"":x.optString(key);}
    private int dp(int x){return (int)(x*a.getResources().getDisplayMetrics().density+.5f);}
    private void toast(String s){AppNotice.show(a, s);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(1);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    private LinearLayout card(LinearLayout parent,String title){LinearLayout c=col();c.setPadding(dp(16),dp(16),dp(16),dp(16));c.setBackground(MallUi.surface(a,Color.WHITE,18,true));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));parent.addView(c,lp);TextView h=text(title,18,MallUi.INK);h.setTypeface(null,1);h.setPadding(0,0,0,dp(14));c.addView(h);return c;}
    private EditText field(LinearLayout p,String key,String label,String value,boolean multi){TextView caption=text(label,12,MallUi.SECONDARY);caption.setPadding(0,dp(12),0,dp(6));p.addView(caption);EditText e=new EditText(a);e.setText(value);e.setTextSize(15);e.setTextColor(MallUi.INK);e.setSingleLine(!multi);if(multi){e.setMinLines(2);e.setGravity(Gravity.TOP);}e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(MallUi.surface(a,MallUi.BACKGROUND,12,false));p.addView(e,new LinearLayout.LayoutParams(-1,-2));fields.put(key,e);return e;}
    private TextView action(LinearLayout p,String label,Runnable run,boolean primary){TextView t=text(label,14,primary?Color.WHITE:MallUi.PRIMARY);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(13),dp(12),dp(13));t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));t.setBackground(primary?MallUi.primaryButton(a):MallUi.surface(a,MallUi.TINT,14,false));MallUi.touch(t,14);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(p.getOrientation()==1?-1:-2,-2);lp.setMargins(0,dp(8),0,dp(4));p.addView(t,lp);t.setOnClickListener(v->run.run());return t;}
}
