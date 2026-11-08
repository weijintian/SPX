package com.spx.chat;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import org.json.*;
import java.util.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

/** Complete native shopping flow. Money and stock are always confirmed by the server. */
final class MallCommerce {
    interface Pick { void selected(Uri uri); }
    interface JsonResult { void done(JSONObject data); }
    interface Value<T> { void accept(T value); }
    interface PageNumber { void accept(int value); }
    private final MainActivity a;
    private Runnable screen;
    private long pageEpoch;
    private final ArrayList<ApiClient.RequestHandle> pageReads=new ArrayList<>();
    private void cancelPageReads(){for(ApiClient.RequestHandle request:pageReads)request.cancel();pageReads.clear();}
    private Runnable productCapture,productDiscard;
    private MallProductDraft productDraft;
    private String productBaseline="",productSaved="";
    private boolean leavingProduct;
    private final android.os.Handler formHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoSaveProduct=()->saveProductLocal();
    private final Runnable scanProduct=new Runnable(){public void run(){if(productCapture==null)return;saveProductLocal();formHandler.postDelayed(this,1000);}};
    void pause(){saveProductLocal();formHandler.removeCallbacks(scanProduct);formHandler.removeCallbacks(autoSaveProduct);}
    void foreground(){if(productCapture!=null){formHandler.removeCallbacks(scanProduct);formHandler.postDelayed(scanProduct,1000);}}
    void dispose(){saveProductLocal();productCapture=null;clearBatch();formHandler.removeCallbacksAndMessages(null);cancelPageReads();pageEpoch++;active=false;}
    private void changedProduct(){formHandler.removeCallbacks(autoSaveProduct);formHandler.postDelayed(autoSaveProduct,300);}
    private void saveProductLocal(){
        if(productCapture==null||productDraft==null)return;
        productCapture.run();
    }
    private void stopProductCapture(){productCapture=null;productDiscard=null;formHandler.removeCallbacks(scanProduct);formHandler.removeCallbacks(autoSaveProduct);}
    private void clearProductLocal(){if(productDraft!=null)productDraft.clear();stopProductCapture();}

    private boolean pageValid(MallSession session,long epoch){return active&&epoch==pageEpoch&&session.valid(a);}
    /** Commerce pages need their own stack so a detail/form returns to its commerce parent. */
    private final ArrayDeque<Runnable> history = new ArrayDeque<>();
    private boolean active;
    private boolean external;
    private LinearLayout pageRoot, pageBar;
    private ScrollView pageScroll;
    void externalPage(){saveProductLocal();stopProductCapture();clearBatch();cancelPageReads();pageEpoch++;if(active)external=true;}
    void resume(){boolean redraw=external;external=false;active=true;if(redraw&&screen!=null)screen.run();}
    private Pick pick;
    private Value<String> pickStatus;
    private Value<ArrayList<String>> multiDone;
    private Value<String> multiStatus;
    private String multiEndpoint;
    private int multiMax;
    private boolean imageUploading;
    private ArrayList<Uri> batchUris;
    private int batchIndex,batchSuccess,batchGeneration;
    private boolean batchFailed;
    private String batchFailure="";
    private MallSession uploadIdentity;
    private long uploadPage;
    private void clearBatch(){batchGeneration++;batchUris=null;batchFailed=false;batchFailure="";batchIndex=0;batchSuccess=0;imageUploading=false;multiDone=null;multiStatus=null;multiEndpoint=null;multiMax=0;pick=null;pickStatus=null;uploadIdentity=null;}
    private boolean uploadValid(int generation){return generation==batchGeneration&&uploadIdentity!=null&&pageValid(uploadIdentity,uploadPage);}
    private String batchSummary(){return batchUris==null?"":("已完成 "+batchSuccess+" / "+batchUris.size()+" 张"+(batchFailed?"；第 "+(batchIndex+1)+" 张失败："+batchFailure:""));}
    private void retryBatch(){if(batchFailed&&!imageUploading&&uploadIdentity!=null&&pageValid(uploadIdentity,uploadPage)){batchFailed=false;uploadBatch();}}
    private void skipBatch(){if(batchFailed&&!imageUploading&&batchUris!=null){batchIndex++;batchFailed=false;uploadBatch();}}
    private void cancelBatch(){if(imageUploading){toast("请等待当前图片完成后取消");return;}Value<String> state=multiStatus;String summary=batchSummary();clearBatch();if(state!=null)state.accept(summary+"；已取消剩余图片");saveProductLocal();}

    static final int PICK_IMAGE=4250;
    MallCommerce(MainActivity activity){a=activity;}
    boolean back(){
        if(!active)return false;
        if(!leavingProduct&&productCapture!=null){
            saveProductLocal();
            if(!productSaved.equals(productBaseline)){
                new AlertDialog.Builder(a).setTitle("保存商品修改？").setMessage("已在本机暂存，可下次恢复；尚未发布到商城。")
                    .setNegativeButton("继续编辑",null)
                    .setNeutralButton("放弃本次修改",(d,n)->{if(productDiscard!=null)productDiscard.run();clearProductLocal();leavingProduct=true;back();leavingProduct=false;})
                    .setPositiveButton("保留草稿并返回",(d,n)->{saveProductLocal();productCapture=null;leavingProduct=true;back();leavingProduct=false;}).show();
                return true;
            }
        }
        saveProductLocal();productCapture=null;cancelPageReads();pageEpoch++;clearBatch();
        if(!history.isEmpty()){
            Runnable previous=history.pop();
            screen=previous;
            external=false;
            previous.run();
            return true;
        }
        active=false;screen=null;external=false;a.mallExit();return true;
    }
    void reset(){saveProductLocal();productCapture=null;formHandler.removeCallbacksAndMessages(null);cancelPageReads();pageEpoch++;clearBatch();history.clear();screen=null;active=false;external=false;}
    private void show(Runnable render){
        if(!active||external){
            // An external app page is the new parent; do not reuse stale commerce frames.
            history.clear();
            a.mallBeforeNavigation();
        }else if(screen!=null){
            // From here on, keep navigation inside commerce until its stack is empty.
            history.push(screen);
        }
        active=true;screen=render;render.run();
    }
    private void replace(Runnable render){active=true;screen=render;render.run();}
    private int dp(int x){return (int)(x*a.getResources().getDisplayMetrics().density+.5f);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(a);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(a);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;}
    private TextView title(String s){TextView t=text(s,18,MallUi.INK);t.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD));t.setPadding(0,dp(12),0,dp(9));return t;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=col();c.setPadding(dp(14),dp(13),dp(14),dp(13));c.setBackground(MallUi.surface(a,Color.WHITE,16,true));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(10));parent.addView(c,lp);return c;}
    private TextView action(LinearLayout parent,String label,Runnable run,boolean primary){TextView t=text(label,15,primary?Color.WHITE:MallUi.PRIMARY);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(13),dp(12),dp(13));t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));t.setBackground(primary?MallUi.primaryButton(a):MallUi.surface(a,MallUi.TINT,14,false));MallUi.touch(t,14);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(8),0,dp(4));parent.addView(t,lp);t.setOnClickListener(v->run.run());return t;}
    private EditText field(LinearLayout p,String label,String value,boolean multi){p.addView(text(label,12,MallUi.SECONDARY));EditText e=new EditText(a);e.setText(value);e.setTextSize(15);e.setTextColor(MallUi.INK);e.setSingleLine(!multi);if(multi){e.setMinLines(3);e.setGravity(Gravity.TOP);}e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackground(MallUi.surface(a,MallUi.BACKGROUND,12,true));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(14));p.addView(e,lp);return e;}
    private LinearLayout page(String label){saveProductLocal();productCapture=null;clearBatch();cancelPageReads();pageEpoch++;external=false;LinearLayout root=col();root.setBackgroundColor(MallUi.BACKGROUND);LinearLayout bar=row();bar.setPadding(dp(8),dp(6),dp(14),dp(6));bar.setBackgroundColor(Color.WHITE);bar.setElevation(dp(1));View backIcon=MallUi.icon(a,"back",MallUi.PRIMARY);backIcon.setContentDescription("返回");bar.addView(backIcon,new LinearLayout.LayoutParams(dp(44),dp(44)));backIcon.setOnClickListener(v->back());TextView name=title(label);name.setPadding(0,0,0,0);bar.addView(name,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);LinearLayout body=col();body.setPadding(dp(14),dp(14),dp(14),dp(28));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));pageScroll=scroll;pageRoot=root;a.mallShow(root);return body;}
    private void toast(String s){AppNotice.show(a, s);}
    private JSONObject obj(Object... pairs){JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);}catch(Exception ignored){}return o;}
    private String str(JSONObject o,String key){return o==null||o.isNull(key)?"":o.optString(key,"");}
    private void get(String endpoint,Map<String,String> args,Value<Object> done){
        get(endpoint,args,done,this::toast);
    }
    private void get(String endpoint,Map<String,String> args,Value<Object> done,Value<String> failed){
        final MallSession identity=new MallSession(a);final long epoch=pageEpoch;
        pageReads.add(ApiClient.get(a,endpoint,args,new ApiClient.Callback(){
            public void onSuccess(Object d){if(pageValid(identity,epoch))done.accept(d);}
            public void onError(String s){if(pageValid(identity,epoch))failed.accept(s);}
        }));
    }
    private void post(String endpoint,JSONObject req,TextView button,Value<Object> done){
        final MallSession identity=new MallSession(a);final long epoch=pageEpoch;
        if(button!=null)button.setEnabled(false);
        ApiClient.post(a,endpoint,req,new ApiClient.Callback(){
            public void onSuccess(Object d){if(!pageValid(identity,epoch))return;if(button!=null)button.setEnabled(true);done.accept(d);}
            public void onError(String s){if(!pageValid(identity,epoch))return;if(button!=null)button.setEnabled(true);toast(s);}
        });
    }
    private Map<String,String> args(String... pairs){Map<String,String> m=new HashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i],pairs[i+1]);return m;}
    private ImageView image(LinearLayout p,String url,int height,boolean detail){ImageView v=new ImageView(a);v.setBackground(MallUi.surface(a,MallUi.TINT,12,false));v.setClipToOutline(true);v.setScaleType(detail?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);if(detail)v.setAdjustViewBounds(true);p.addView(v,new LinearLayout.LayoutParams(-1,height==0?-2:dp(height)));MallImages.bind(v,url,detail);return v;}
    private void preview(String url){Dialog d=new Dialog(a,android.R.style.Theme_Material_Light_NoActionBar);LinearLayout r=col();r.setBackgroundColor(Color.BLACK);ScrollView scroll=new ScrollView(a);LinearLayout b=col();image(b,url,0,true);scroll.addView(b);r.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));action(r,"关闭",d::dismiss,false);d.setContentView(r);d.show();}
    private void pickImage(String endpoint,Value<String> done){pickImage(endpoint,done,s->{});}
    private void pickImage(String endpoint,Value<String> done,Value<String> state){
        if(imageUploading||pick!=null){toast("请等待当前图片上传完成");return;}
        uploadIdentity=new MallSession(a);uploadPage=pageEpoch;final int operation=++batchGeneration;pickStatus=state;
        state.accept("已打开相册，请选择一张图片");
        pick=uri->{
            if(!uploadValid(operation))return;
            imageUploading=true;state.accept("正在处理并上传图片…");toast("正在上传图片…");
            MallUpload.upload(a,endpoint,uri,new ApiClient.Callback(){
                public void onSuccess(Object d){
                    if(!uploadValid(operation))return;
                    String url=d instanceof JSONObject?((JSONObject)d).optString("url","").trim():"";
                    if(url.isEmpty()||url.equals("null")){onError("服务器未返回图片地址，请重试");return;}
                    imageUploading=false;done.accept(url);saveProductLocal();state.accept("图片已上传");
                }
                public void onError(String s){if(!uploadValid(operation))return;imageUploading=false;state.accept("上传失败："+s+"，请重新选择图片重试");toast(s);}
            });
        };
        if(!ChatGallery.open(a,"image/*",PICK_IMAGE)){pick=null;pickStatus=null;state.accept("无法打开相册，请启用系统相册后重试");}
    }
    private void pickImages(String endpoint,int max,Value<ArrayList<String>> done,Value<String> state){
        if(imageUploading||pick!=null||multiDone!=null){toast("请等待当前图片上传完成");return;}
        uploadIdentity=new MallSession(a);uploadPage=pageEpoch;batchGeneration++;multiEndpoint=endpoint;multiDone=done;multiStatus=state;multiMax=Math.max(1,max);state.accept("已打开相册，请选择多张图片");
        if(!ChatGallery.openMultiple(a,"image/*",PICK_IMAGE)){multiDone=null;multiStatus=null;multiEndpoint=null;multiMax=0;state.accept("无法打开相册，请启用系统相册后重试");}
    }
    private void uploadBatch(){
        final int operation=batchGeneration;
        if(!uploadValid(operation)||batchUris==null)return;
        if(batchIndex>=batchUris.size()){
            imageUploading=false;batchFailed=false;Value<String> state=multiStatus;String result=batchSummary();
            clearBatch();if(state!=null)state.accept(result+"；上传结束");saveProductLocal();return;
        }
        imageUploading=true;batchFailed=false;
        if(multiStatus!=null)multiStatus.accept("正在上传第 "+(batchIndex+1)+" / "+batchUris.size()+" 张；已完成 "+batchSuccess+" 张");
        MallUpload.upload(a,multiEndpoint,batchUris.get(batchIndex),new ApiClient.Callback(){
            public void onSuccess(Object data){
                if(!uploadValid(operation))return;
                String url=data instanceof JSONObject?((JSONObject)data).optString("url","").trim():"";
                if(url.isEmpty()||url.equals("null")){onError("服务器未返回图片地址");return;}
                batchSuccess++;batchIndex++;
                if(multiDone!=null)multiDone.accept(new ArrayList<>(java.util.Collections.singletonList(url)));
                saveProductLocal();uploadBatch();
            }
            public void onError(String error){
                if(!uploadValid(operation))return;
                imageUploading=false;batchFailed=true;batchFailure=error;
                if(multiStatus!=null)multiStatus.accept(batchSummary()+"。可重试本张、跳过或取消剩余。");
                saveProductLocal();
            }
        });
    }
    boolean activityResult(int request,int result,Intent data){
        if(request==MALL_SCAN){
            if(result==Activity.RESULT_OK&&data!=null){
                String code=data.getStringExtra(QrScanActivity.EXTRA_SCAN_RESULT);
                if(code!=null)confirmRedeem(code.trim());
            }
            return true;
        }
        if(request!=PICK_IMAGE)return false;
        if(uploadIdentity==null||!pageValid(uploadIdentity,uploadPage)){clearBatch();return true;}
        if(multiDone!=null){
            Value<String> state=multiStatus;ArrayList<Uri> selected=new ArrayList<>(ChatGallery.selectedUris(data));
            if(selected.isEmpty()){multiDone=null;multiStatus=null;multiEndpoint=null;multiMax=0;if(state!=null)state.accept("未选择图片，点击批量选择按钮可重试");return true;}
            batchUris=new ArrayList<>(selected.subList(0,Math.min(selected.size(),multiMax)));batchIndex=0;batchSuccess=0;batchFailed=false;uploadBatch();return true;
        }
        Pick callback=pick;Value<String> state=pickStatus;pick=null;pickStatus=null;
        Uri uri=ChatGallery.selectedUri(data);
        android.util.Log.i("SPXGallery","product result="+result+" selected="+(uri!=null)+" pending="+(callback!=null));
        if(callback==null){toast("本次选图已失效，请重新点击选择图片");return true;}
        // OEM gallery result codes differ; a returned local image URI is authoritative.
        // Real cancellation has no selection and must never reuse the previous image.
        if(uri==null&&result!=Activity.RESULT_OK){if(state!=null)state.accept("未选择图片，点击选图按钮可重试");return true;}
        if(uri==null){String message="未读取到相册图片，请重新选择";if(state!=null)state.accept(message);toast(message);return true;}
        callback.selected(uri);return true;
    }
    private String addressText(JSONObject x){return str(x,"name")+"  "+str(x,"phone")+"\n"+str(x,"province")+str(x,"city")+str(x,"district")+str(x,"detail");}
    private String status(JSONObject o){String s=str(o,"status");if(s.equals("pending"))return str(o,"order_type").equals("local")?"待使用":str(o,"order_type").equals("digital")?"待交付":"待发货";if(s.equals("shipped"))return str(o,"order_type").equals("digital")?"待确认交付":"待收货";if(s.equals("delivered")||s.equals("completed"))return "已完成";if(s.equals("refunded"))return "已退款";if(s.equals("cancelled"))return "已取消";return s;}

    private static final class Buy {JSONObject product,sku,address,quote;int quantity=1;String note="";Buy(JSONObject p){product=p;JSONArray list=p.optJSONArray("variants");if(list==null||list.length()==0){sku=new JSONObject();try{sku.put("id","default");sku.put("name","标准规格");sku.put("price",p.optString("spx_price"));sku.put("cny_price",p.optString("cny_price"));sku.put("stock",p.optInt("stock"));}catch(Exception ignored){}}else if(list.length()==1)sku=list.optJSONObject(0);}}
    void product(JSONObject p){Buy buy=new Buy(p);show(()->detail(buy));}
    private void detail(Buy b){
        JSONObject p=b.product;LinearLayout body=productPage(p);JSONArray gallery=p.optJSONArray("images");
        if(gallery==null)gallery=new JSONArray();
        // 选中的规格图优先放在第一张
        String pickedImage=b.sku==null?"":str(b.sku,"image_url");
        JSONArray merged=new JSONArray();
        if(!pickedImage.isEmpty())merged.put(pickedImage);
        for(int i=0;i<gallery.length();i++){
            String u=gallery.optString(i);
            if(u!=null&&!u.isEmpty()&&!u.equals(pickedImage))merged.put(u);
        }
        if(merged.length()==0)merged.put(str(p,"image_url"));
        final JSONArray photos=merged;
        HorizontalScrollView carousel=new HorizontalScrollView(a);carousel.setHorizontalScrollBarEnabled(false);carousel.setBackground(MallUi.surface(a,MallUi.TINT,20,false));carousel.setClipToOutline(true);
        LinearLayout strip=row();strip.setGravity(Gravity.CENTER);ArrayList<View> slides=new ArrayList<>();
        for(int i=0;i<photos.length();i++){
            String url=photos.optString(i);
            FrameLayout slide=new FrameLayout(a);
            slide.setForegroundGravity(Gravity.CENTER);
            ImageView iv=new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(false);
            iv.setContentDescription(str(p,"name")+"，图片 "+(i+1));
            FrameLayout.LayoutParams imageParams=new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER);
            slide.addView(iv,imageParams);
            strip.addView(slide,new LinearLayout.LayoutParams(dp(280),-1));
            MallImages.bind(iv,url,true);
            iv.setOnClickListener(v->preview(url));
            slides.add(slide);
        }
        carousel.addView(strip,new HorizontalScrollView.LayoutParams(-2,-1));body.addView(carousel,new LinearLayout.LayoutParams(-1,dp(235)));
        final LinearLayout productRoot=pageRoot;
         carousel.addOnLayoutChangeListener((v,l,t,r,bot,ol,ot,or,ob)->{int w=r-l;if(w>0&&(r-l!=or-ol)){for(View slide:slides){LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)slide.getLayoutParams();lp.width=w;slide.setLayoutParams(lp);}int screenLimit=Math.max(dp(180),Math.round(productRoot.getHeight()*.30f));carousel.getLayoutParams().height=Math.min(screenLimit,Math.min(dp(300),Math.round(w*.78f)));carousel.requestLayout();}});
        TextView photoHint=text("1 / "+photos.length()+"  ·  左右滑动，点击放大",10,MallUi.SECONDARY);photoHint.setGravity(Gravity.END);photoHint.setPadding(0,dp(6),dp(3),dp(8));body.addView(photoHint);
        carousel.setOnScrollChangeListener((v,x,y,oldx,oldy)->{int width=carousel.getWidth();if(width>0)photoHint.setText(Math.min(photos.length(),1+(x+width/2)/width)+" / "+photos.length()+"  ·  左右滑动，点击放大");});
        TextView name=title(str(p,"name"));name.setTextSize(22);name.setPadding(dp(4),dp(3),dp(4),dp(4));body.addView(name);
        TextView subtitle=text(str(p,"description"),12,MallUi.SECONDARY);subtitle.setMaxLines(2);subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);subtitle.setPadding(dp(4),0,dp(4),dp(10));body.addView(subtitle);
        TextView price=text("",28,MallUi.PRIMARY);price.setTypeface(null,1);price.setPadding(dp(4),0,0,dp(2));body.addView(price);
        TextView reference=text("SPX001 实时换算中…",13,MallUi.SECONDARY);reference.setPadding(dp(4),0,dp(4),dp(10));body.addView(reference);
        JSONObject[] quote={p.optJSONObject("valuation")};
        Runnable updatePrice=()->{String cny=b.sku==null?str(p,"cny_price"):str(b.sku,"cny_price");boolean legacy=cny.isEmpty();price.setText(legacy?MallMoney.show(b.sku==null?str(p,"spx_price"):str(b.sku,"price"))+" SPX001":"¥"+MallMoney.show(cny)+" CNY"+(b.sku==null?" 起":""));String spx=legacy?"":quote[0]==null?"":MallMoney.spxFromCny(cny,str(quote[0],"spx_usdt"),str(quote[0],"usdt_cny"));reference.setText(legacy?"此商品按历史 SPX001 价格结算":spx.isEmpty()?"SPX001 行情加载中…":"约需 "+MallMoney.price(spx)+" SPX001");reference.setOnClickListener(v->{if(!legacy&&quote[0]!=null)new AlertDialog.Builder(a).setTitle("实时换算").setMessage("固定商品价：¥"+MallMoney.show(cny)+" CNY\n\n1 USD = "+MallMoney.show(str(quote[0],"usdt_cny"))+" CNY\nSPX001 行情：$"+str(quote[0],"spx_usdt")+"\n行情更新时间："+str(quote[0],"updated_at")+"\n\nSPX001 价格上涨时，所需数量会减少。").setPositiveButton("知道了",null).show();});};
        updatePrice.run();
        Runnable refreshMarket=new Runnable(){
            public void run(){
                if(!reference.isAttachedToWindow()||a.isFinishing())return;
                if(reference.getWindowVisibility()!=View.VISIBLE){reference.postDelayed(this,30000);return;}
                final Runnable refresh=this;
                ApiClient.get(a,"mall.valuation",null,new ApiClient.Callback(){
                    public void onSuccess(Object data){
                        if(!reference.isAttachedToWindow())return;
                        if(data instanceof JSONObject){quote[0]=(JSONObject)data;updatePrice.run();}
                        reference.postDelayed(refresh,30000);
                    }
                    public void onError(String message){
                        if(!reference.isAttachedToWindow())return;
                        quote[0]=null;updatePrice.run();
                        if(!str(b.sku==null?p:b.sku,"cny_price").isEmpty())reference.setText("SPX001 行情暂不可用，正在重试…");
                        reference.postDelayed(refresh,30000);
                    }
                });
            }
        };
        reference.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){reference.removeCallbacks(refreshMarket);reference.post(refreshMarket);}
            public void onViewDetachedFromWindow(View v){reference.removeCallbacks(refreshMarket);}
        });
        if(reference.isAttachedToWindow())reference.post(refreshMarket);
        String type=str(p,"product_type");LinearLayout delivery=row();delivery.setPadding(dp(4),dp(2),dp(4),dp(12));
        String days=p.optInt("delivery_days",3)==0?"当日":p.optInt("delivery_days",3)+" 天内";
        String timing=type.equals("local")?p.optInt("service_valid_days",30)+" 天内有效":days+(type.equals("physical")?"发货":"交付");
        String fee=type.equals("physical")?("0.00".equals(MallMoney.show(p.optString("shipping_fee","0")))?"包邮":"运费 "+MallMoney.show(str(p,"shipping_fee"))+" SPX001"):type.equals("local")?(p.optInt("appointment_required")==1?"需预约":"到店使用"):"线上交付";
        for(String value:new String[]{timing,fee,"库存 "+p.optInt("stock")+" 件"}){TextView item=text(value,11,MallUi.SECONDARY);item.setGravity(Gravity.CENTER);delivery.addView(item,new LinearLayout.LayoutParams(0,-2,1));}body.addView(delivery);
        LinearLayout spec=card(body);TextView specHint=text("选择规格与数量",12,MallUi.SECONDARY);spec.addView(specHint);TextView chosen=text("",14,MallUi.INK);chosen.setTypeface(null,1);chosen.setPadding(0,dp(6),0,0);spec.addView(chosen);Runnable updateChoice=()->chosen.setText((b.sku==null?"请选择商品规格":str(b.sku,"name")+" / "+b.quantity+" 件")+"  ›");updateChoice.run();MallUi.touch(spec,18);spec.setOnClickListener(v->skuSheet(b,()->{updateChoice.run();updatePrice.run();}));
        JSONObject consultation=p.optJSONObject("consultation_group");if(consultation!=null){LinearLayout group=row();group.setPadding(dp(14),dp(12),dp(12),dp(12));group.setBackground(MallUi.surface(a,MallUi.TINT,16,false));LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.setMargins(0,0,0,dp(12));body.addView(group,gp);group.addView(MallUi.icon(a,"chat",MallUi.PRIMARY),new LinearLayout.LayoutParams(dp(28),dp(28)));LinearLayout labels=col();labels.setPadding(dp(10),0,dp(8),0);TextView heading=text("加入商品群",14,MallUi.INK);heading.setTypeface(null,1);labels.addView(heading);TextView groupName=text(str(consultation,"name")+" · 购买前问一问",11,MallUi.SECONDARY);groupName.setMaxLines(2);labels.addView(groupName);group.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView join=text("加入",12,Color.WHITE);join.setGravity(Gravity.CENTER);join.setBackground(MallUi.primaryButton(a));group.addView(join,new LinearLayout.LayoutParams(dp(56),dp(36)));join.setOnClickListener(v->a.mallJoinGroup(consultation));group.setOnClickListener(v->a.mallJoinGroup(consultation));}
        JSONObject shop=p.optJSONObject("shop");if(shop!=null){LinearLayout c=card(body);LinearLayout header=row();header.addView(MallUi.icon(a,"store",MallUi.PRIMARY),new LinearLayout.LayoutParams(dp(30),dp(30)));LinearLayout labels=col();labels.setPadding(dp(10),0,dp(8),0);TextView shopName=text(str(shop,"name"),14,MallUi.INK);shopName.setTypeface(null,1);labels.addView(shopName);if(!str(shop,"business_hours").isEmpty())labels.addView(text("服务时间 "+str(shop,"business_hours"),11,MallUi.SECONDARY));header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));header.addView(MallUi.badge(a,str(shop,"level").equals("gold")?"黄金店铺":"初级店铺",false));c.addView(header);MallUi.touch(c,18);c.setOnClickListener(v->shop(shop.optInt("id")));if(!str(shop,"address").isEmpty()){TextView address=text(str(shop,"address")+"  ·  导航到店 ›",12,MallUi.SECONDARY);address.setPadding(0,dp(12),0,0);c.addView(address);address.setOnClickListener(v->MapNavigation.open(a,shop));}}
        LinearLayout tabs=row();body.addView(tabs);LinearLayout detailContent=col();body.addView(detailContent);TextView[] tabButtons=new TextView[3];String[] names={"商品详情","规格参数","购买须知"};final int[] selectedTab={0};
        Runnable renderSection=()->{detailContent.removeAllViews();int selected=selectedTab[0];for(int i=0;i<3;i++){tabButtons[i].setTextColor(i==selected?MallUi.PRIMARY:MallUi.SECONDARY);tabButtons[i].setTypeface(null,i==selected?1:0);}LinearLayout c=card(detailContent);if(selected==0){String content=str(p,"detail_text");c.addView(text(content.isEmpty()?str(p,"description"):content,14,MallUi.INK));JSONArray images=p.optJSONArray("detail_images");if(images!=null)for(int i=0;i<images.length();i++){String url=images.optString(i);ImageView iv=image(detailContent,url,0,true);iv.setMinimumHeight(dp(160));iv.setOnClickListener(v->preview(url));}if(!str(p,"video_url").isEmpty())action(detailContent,"观看商品视频",()->{try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(str(p,"video_url"))));}catch(Exception e){toast("视频暂不可用");}},false);action(detailContent,"查看买家评价",()->reviews(p.optInt("id")),false);}else if(selected==1){String parameters=str(p,"parameters");if(parameters.isEmpty())c.addView(text("商家暂未填写更多参数，请查看规格或加入商品群咨询。",13,MallUi.SECONDARY));else for(String entry:parameters.split("\n")){LinearLayout r=row();String[] bits=entry.split("[:：]",2);r.addView(text(bits[0],13,MallUi.SECONDARY),new LinearLayout.LayoutParams(0,-2,1));r.addView(text(bits.length>1?bits[1]:"",13,MallUi.INK),new LinearLayout.LayoutParams(0,-2,2));r.setPadding(0,dp(7),0,dp(7));c.addView(r);}}else{c.addView(title("配送与使用"));c.addView(text(timing+" · "+fee,14,MallUi.INK));if(type.equals("local"))c.addView(text("到店接受服务时出示订单核销码",13,MallUi.SECONDARY));if(!str(p,"delivery_note").isEmpty())c.addView(text(str(p,"delivery_note"),13,MallUi.SECONDARY));c.addView(title("售后与服务"));c.addView(text(str(p,"service_terms").isEmpty()?"购买前请核对规格和服务说明，售后可在订单详情中申请。":str(p,"service_terms"),14,MallUi.INK));}};
        for(int i=0;i<3;i++){final int index=i;TextView tab=text(names[i],14,MallUi.SECONDARY);tab.setGravity(Gravity.CENTER);tab.setPadding(0,dp(12),0,dp(12));tabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));tabButtons[i]=tab;tab.setOnClickListener(v->{selectedTab[0]=index;renderSection.run();});}renderSection.run();
        LinearLayout footer=row();footer.setPadding(dp(12),dp(8),dp(16),dp(12));footer.setBackgroundColor(Color.WHITE);
        if(shop!=null){footerShortcut(footer,"store","店铺",()->shop(shop.optInt("id")));footerShortcut(footer,"chat","联系商家",()->a.mallContact(shop));}
        TextView buy=text("选择规格 · 立即购买",15,Color.WHITE);buy.setGravity(Gravity.CENTER);buy.setTypeface(null,1);buy.setBackground(MallUi.primaryButton(a));MallUi.touch(buy,14);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(48),1);bp.setMargins(dp(8),0,0,0);footer.addView(buy,bp);buy.setOnClickListener(v->{if(b.sku==null)skuSheet(b,()->{updateChoice.run();updatePrice.run();checkout(b);});else if(b.sku.optInt("stock")<b.quantity)toast("库存不足，请调整规格与数量");else checkout(b);});pageRoot.addView(footer,new LinearLayout.LayoutParams(-1,-2));
    }
    private LinearLayout productPage(JSONObject product){
        saveProductLocal();productCapture=null;clearBatch();cancelPageReads();pageEpoch++;external=false;pageRoot=col();pageRoot.setBackgroundColor(MallUi.BACKGROUND);pageRoot.setTag("product:"+product.optInt("id"));pageBar=row();pageBar.setPadding(dp(8),dp(4),dp(8),dp(4));
        View backIcon=MallUi.icon(a,"back",MallUi.PRIMARY);backIcon.setPadding(dp(12),dp(12),dp(12),dp(12));backIcon.setContentDescription("返回上一页");pageBar.addView(backIcon,new LinearLayout.LayoutParams(dp(48),dp(48)));backIcon.setOnClickListener(v->back());TextView heading=text("商品详情",17,MallUi.INK);heading.setGravity(Gravity.CENTER);heading.setTypeface(null,1);pageBar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));View share=MallUi.icon(a,"share",MallUi.PRIMARY);share.setPadding(dp(13),dp(13),dp(13),dp(13));share.setContentDescription("分享商品");pageBar.addView(share,new LinearLayout.LayoutParams(dp(48),dp(48)));share.setOnClickListener(v->shareProduct(product));pageRoot.addView(pageBar);
        pageScroll=new ScrollView(a);pageScroll.setVerticalScrollBarEnabled(false);pageScroll.setFillViewport(true);LinearLayout body=col();body.setPadding(dp(16),dp(8),dp(16),dp(16));pageScroll.addView(body);pageRoot.addView(pageScroll,new LinearLayout.LayoutParams(-1,0,1));a.mallShow(pageRoot);return body;
    }
    private void footerShortcut(LinearLayout footer,String icon,String label,Runnable action){LinearLayout item=col();item.setGravity(Gravity.CENTER);item.addView(MallUi.icon(a,icon,MallUi.SECONDARY),new LinearLayout.LayoutParams(dp(22),dp(22)));TextView title=text(label,10,MallUi.SECONDARY);title.setGravity(Gravity.CENTER);item.addView(title);footer.addView(item,new LinearLayout.LayoutParams(dp(52),dp(48)));item.setContentDescription(label);item.setOnClickListener(v->action.run());MallUi.touch(item,10);}
    private void shop(int id){a.mallShop(id);}
    private void shareProduct(JSONObject p){
        String link=ApiClient.WEB_BASE+"/spxchat/product-share.php?id="+p.optInt("id");
        String shop=p.optJSONObject("shop")==null?"SPX 好物商城":str(p.optJSONObject("shop"),"name");
        String shareCny=str(p,"cny_price");String content=str(p,"name")+"\n"+(shareCny.isEmpty()?"历史 SPX001 标价 "+MallMoney.show(str(p,"spx_price"))+" SPX001":"¥"+MallMoney.show(shareCny))+" · "+shop+"\n"+link;
        new AlertDialog.Builder(a).setTitle("分享商品").setItems(new String[]{"发送给 SPX 好友 / 群聊","复制商品链接","分享到其他应用"},(d,n)->{
            if(n==0){a.mallShareProduct(p);return;}if(n==1){copy(link);return;}
            Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain");send.putExtra(Intent.EXTRA_SUBJECT,str(p,"name"));send.putExtra(Intent.EXTRA_TEXT,content);
            try{a.startActivity(Intent.createChooser(send,"分享商品"));}catch(ActivityNotFoundException e){toast("当前设备暂不支持分享");}
        }).show();
    }
    /** 淘宝式规格弹层：缩略图 + 价格 + 规格网格 + 数量步进。 */
    private void skuSheet(final Buy b,final Runnable done){
        final Dialog dialog=new Dialog(a,android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root=col();root.setPadding(dp(16),dp(10),dp(16),dp(20));
        android.graphics.drawable.GradientDrawable sheetBg=new android.graphics.drawable.GradientDrawable();
        sheetBg.setColor(Color.WHITE);
        float radius=dp(22);
        sheetBg.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});
        root.setBackground(sheetBg);
        LinearLayout handle=row();handle.setGravity(Gravity.CENTER);
        TextView bar=text("━",18,MallUi.LINE);handle.addView(bar);root.addView(handle);

        JSONArray all=b.product.optJSONArray("variants");
        if(all==null||all.length()==0){all=new JSONArray();if(b.sku!=null)all.put(b.sku);}
        final JSONArray options=all;
        final JSONObject[] selected={b.sku!=null&&b.sku.optInt("stock")>0?b.sku:null};
        final Map<String,String> filters=new LinkedHashMap<>();
        final LinkedHashMap<String,LinkedHashSet<String>> dimensions=new LinkedHashMap<>();
        for(int i=0;i<options.length();i++){
            JSONObject sku=options.optJSONObject(i);if(sku==null)continue;
            JSONObject attrs=sku.optJSONObject("attributes");
            if(attrs==null)continue;
            Iterator<String> it=attrs.keys();
            while(it.hasNext()){
                String k=it.next();
                if(!dimensions.containsKey(k))dimensions.put(k,new LinkedHashSet<>());
                dimensions.get(k).add(attrs.optString(k));
            }
        }

        LinearLayout head=row();head.setGravity(Gravity.TOP);
        ImageView thumb=new ImageView(a);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(MallUi.surface(a,Color.rgb(238,243,255),14,false));thumb.setClipToOutline(true);
        MallImages.bind(thumb,str(b.product,"image_url"),false);
        head.addView(thumb,new LinearLayout.LayoutParams(dp(94),dp(94)));
        LinearLayout headInfo=col();headInfo.setPadding(dp(12),0,0,0);
        final TextView priceView=text("",22,MallUi.PRIMARY);priceView.setTypeface(null,1);headInfo.addView(priceView);
        final TextView stockView=text("",12,MallUi.SECONDARY);stockView.setPadding(0,dp(6),0,0);headInfo.addView(stockView);
        final TextView pickedView=text("请选择商品规格",12,MallUi.SECONDARY);pickedView.setPadding(0,dp(6),0,0);headInfo.addView(pickedView);
        final String[] shownImage={""};
        head.addView(headInfo,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(head);

        final int[] count={Math.max(1,b.quantity)};
        final LinearLayout specArea=col();specArea.setPadding(0,dp(14),0,0);
        ScrollView specScroll=new ScrollView(a);specScroll.addView(specArea);
        root.addView(specScroll,new LinearLayout.LayoutParams(-1,Math.min(dp(240),a.getResources().getDisplayMetrics().heightPixels/4)));

        final Runnable[] refresh={null};
        for(final Map.Entry<String,LinkedHashSet<String>> dim:dimensions.entrySet()){
            specArea.addView(text(dim.getKey(),13,MallUi.SECONDARY));
            GridLayout grid=new GridLayout(a);grid.setColumnCount(3);
            for(final String value:dim.getValue()){
                final TextView chip=text(value,13,MallUi.INK);
                chip.setGravity(Gravity.CENTER);chip.setPadding(dp(6),dp(10),dp(6),dp(10));
                chip.setSingleLine(true);chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                chip.setBackground(MallUi.surface(a,Color.rgb(246,248,252),12,true));
                final String dimName=dim.getKey();
                chip.setOnClickListener(v->{filters.put(dimName,value);selected[0]=null;refresh[0].run();});
                GridLayout.LayoutParams lp=new GridLayout.LayoutParams();
                lp.width=0;lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
                lp.setMargins(dp(3),dp(6),dp(3),dp(6));
                grid.addView(chip,lp);
                chip.setTag("chip:"+dimName+"="+value);
            }
            specArea.addView(grid);
        }

        if(dimensions.isEmpty()){
            specArea.addView(text("规格",13,MallUi.SECONDARY));
            int maxLen=0;
            for(int i=0;i<options.length();i++){JSONObject sku=options.optJSONObject(i);if(sku!=null)maxLen=Math.max(maxLen,str(sku,"name").length());}
            GridLayout grid=new GridLayout(a);grid.setColumnCount(maxLen>8?2:3);
            for(int i=0;i<options.length();i++){
                final JSONObject sku=options.optJSONObject(i);if(sku==null)continue;
                String label=str(sku,"name");if(label.isEmpty())label="标准规格";
                final TextView chip=text(label,13,MallUi.INK);
                chip.setGravity(Gravity.CENTER);chip.setPadding(dp(6),dp(10),dp(6),dp(10));
                chip.setSingleLine(true);chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                chip.setBackground(MallUi.surface(a,Color.rgb(246,248,252),12,true));
                chip.setTag("chip:default="+str(sku,"id"));
                chip.setOnClickListener(v->{selected[0]=sku;refresh[0].run();});
                GridLayout.LayoutParams lp=new GridLayout.LayoutParams();
                lp.width=0;lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
                lp.setMargins(dp(3),dp(6),dp(3),dp(6));
                grid.addView(chip,lp);
            }
            specArea.addView(grid);
        }

        final LinearLayout qtyRow=row();qtyRow.setGravity(Gravity.CENTER_VERTICAL);qtyRow.setPadding(0,dp(12),0,0);
        qtyRow.addView(text("数量",13,MallUi.SECONDARY),new LinearLayout.LayoutParams(0,-2,1));
        TextView minus=text("−",20,MallUi.INK),qty=text(""+count[0],16,MallUi.INK),plus=text("＋",18,MallUi.INK);
        for(TextView v:new TextView[]{minus,qty,plus}){
            v.setGravity(Gravity.CENTER);v.setBackground(MallUi.surface(a,Color.rgb(246,248,252),10,true));
            qtyRow.addView(v,new LinearLayout.LayoutParams(dp(44),dp(38)));
        }
        root.addView(qtyRow);

        final TextView confirm=new TextView(a);
        confirm.setText("确定");confirm.setGravity(Gravity.CENTER);
        confirm.setPadding(dp(12),dp(14),dp(12),dp(14));
        confirm.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        confirm.setTextColor(Color.WHITE);
        confirm.setBackground(MallUi.primaryButton(a));
        MallUi.touch(confirm,14);
        LinearLayout.LayoutParams confirmLp=new LinearLayout.LayoutParams(-1,-2);
        confirmLp.setMargins(0,dp(14),0,dp(2));
        root.addView(confirm,confirmLp);
        final Runnable sync=()->{
            JSONObject sku=selected[0];
            String image=sku==null?"":str(sku,"image_url");
            if(image.isEmpty())image=str(b.product,"image_url");
            if(!image.equals(shownImage[0])){shownImage[0]=image;MallImages.bind(thumb,image,false);}
            String cny=sku==null?"":str(sku,"cny_price");
            priceView.setText(sku==null?"请选择规格":(cny.isEmpty()?MallMoney.total(str(sku,"price"),count[0])+" SPX001":"¥"+MallMoney.total(cny,count[0])));
            stockView.setText(sku==null?"":("库存 "+sku.optInt("stock")+" 件"));
            StringBuilder picked=new StringBuilder();
            for(Map.Entry<String,String> e:filters.entrySet()){if(picked.length()>0)picked.append(" · ");picked.append(e.getValue());}
            if(picked.length()==0&&selected[0]!=null&&!str(selected[0],"name").isEmpty())picked.append(str(selected[0],"name"));
            pickedView.setText(picked.length()==0?"请选择商品规格":picked.toString());
            for(int i=0;i<specArea.getChildCount();i++){
                View child=specArea.getChildAt(i);
                if(!(child instanceof GridLayout))continue;
                GridLayout grid=(GridLayout)child;
                for(int j=0;j<grid.getChildCount();j++){
                    TextView chip=(TextView)grid.getChildAt(j);
                    String tag=String.valueOf(chip.getTag());
                    boolean on=false;
                    for(Map.Entry<String,String> e:filters.entrySet()){
                        if(tag.equals("chip:"+e.getKey()+"="+e.getValue()))on=true;
                    }
                    if(!on&&tag.startsWith("chip:default=")&&selected[0]!=null&&tag.equals("chip:default="+str(selected[0],"id")))on=true;
                    if(on){chip.setBackground(MallUi.surface(a,MallUi.PRIMARY,12,false));chip.setTextColor(Color.WHITE);}
                    else{chip.setBackground(MallUi.surface(a,Color.rgb(246,248,252),12,true));chip.setTextColor(MallUi.INK);}
                }
            }
            qty.setText(""+count[0]);
            confirm.setText(sku==null?"请选择规格":"确定");
        };
        refresh[0]=()->{
            // 没有属性的商品：只在尚未选择时默认第一个，选过就不能被重置
            if(dimensions.isEmpty()){
                if(selected[0]==null){
                    for(int i=0;i<options.length();i++){
                        JSONObject sku=options.optJSONObject(i);
                        if(sku!=null&&sku.optInt("stock")>0){selected[0]=sku;break;}
                    }
                }
                sync.run();
                return;
            }
            for(int i=0;i<options.length();i++){
                JSONObject sku=options.optJSONObject(i);if(sku==null)continue;
                JSONObject attrs=sku.optJSONObject("attributes");
                boolean match=true;
                for(Map.Entry<String,String> e:filters.entrySet()){
                    if(attrs==null||!e.getValue().equals(attrs.optString(e.getKey()))){match=false;break;}
                }
                if(match&&sku.optInt("stock")>0){selected[0]=sku;break;}
            }
            sync.run();
        };
        minus.setOnClickListener(v->{if(count[0]>1){count[0]--;refresh[0].run();}});
        plus.setOnClickListener(v->{int max=selected[0]==null?99:Math.max(1,selected[0].optInt("stock"));if(count[0]<Math.min(99,max)){count[0]++;refresh[0].run();}else toast("库存不足");});
        confirm.setOnClickListener(v->{
            android.util.Log.d("SPXMall","sku confirm clicked selected="+(selected[0]==null?"null":str(selected[0],"id")));
            if(selected[0]==null){toast("请选择商品规格");return;}
            b.sku=selected[0];b.quantity=count[0];b.quote=null;
            dialog.dismiss();
            try{done.run();}catch(Throwable error){toast("打开结算失败："+error.getMessage());}
        });
        refresh[0].run();
        dialog.setContentView(root,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window=dialog.getWindow();
        if(window!=null){
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            if(android.os.Build.VERSION.SDK_INT>=21)window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.getAttributes().windowAnimations=android.R.style.Animation_Dialog;
        }
    }
    private void checkout(Buy b){b.quote=null;show(()->checkoutPage(b));}
    private void checkoutPage(Buy b){
        LinearLayout body=page("确认订单");LinearLayout c=card(body);c.addView(title(str(b.product,"name")));c.addView(text(str(b.sku,"name")+" × "+b.quantity,15,MallUi.INK));
        final MallSession checkoutIdentity=new MallSession(a);final long checkoutEpoch=pageEpoch;
        action(c,"修改规格与数量",()->skuSheet(b,()->{b.quote=null;replace(()->checkoutPage(b));}),false);
        if(str(b.product,"product_type").equals("physical")){action(body,b.address==null?"选择收货地址  ›":addressText(b.address)+"  ›",()->addresses(x->{b.address=x;b.quote=null;replace(()->checkoutPage(b));}),false);}else{JSONObject shop=b.product.optJSONObject("shop");if(shop!=null&&str(b.product,"product_type").equals("local"))body.addView(text("使用门店："+str(shop,"name")+"\n"+str(shop,"address"),14,MallUi.INK));}
        EditText note=field(body,"买家留言（选填）",b.note,true);note.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){b.note=s.toString();}public void afterTextChanged(android.text.Editable e){}});TextView amounts=text("确认前会重新核对价格、运费及库存",14,MallUi.SECONDARY);body.addView(amounts);
        LinearLayout terms=card(body);terms.addView(text("请核对商品规格和交付说明。支付后 SPX 先由平台保留，订单完成且售后期结束后结算给商家。",13,MallUi.SECONDARY));
        TextView button=action(body,"核对订单金额",()->{},true);button.setOnClickListener(v->{b.note=note.getText().toString().trim();if(str(b.product,"product_type").equals("physical")&&b.address==null){toast("请选择收货地址");return;}JSONObject req=obj("product_id",b.product.optInt("id"),"variant_id",str(b.sku,"id"),"quantity",b.quantity,"address_id",b.address==null?0:b.address.optInt("id"),"buyer_note",b.note);post("checkout.quote",req,button,data->{if(!(data instanceof JSONObject))return;b.quote=(JSONObject)data;String cnyTotal=b.quote.optString("subtotal_cny","");JSONObject fx=b.quote.optJSONObject("pricing");String fxLine=fx==null?"":("\n\n换算行情：1 USD = "+str(fx,"usd_cny")+" CNY；1 SPX001 = $"+str(fx,"spx_usd")+"\n报价有效 10 分钟，已锁定此行情");amounts.setText((cnyTotal.isEmpty()?"商品金额 "+MallMoney.show(b.quote.optString("subtotal"))+" SPX001":"商品金额 ¥"+MallMoney.show(cnyTotal)+"（约需 "+MallMoney.show(b.quote.optString("subtotal"))+" SPX001）")+"\n运费 "+MallMoney.show(b.quote.optString("shipping_fee"))+" SPX001\n应付 "+MallMoney.show(b.quote.optString("total_spx"))+" SPX001\n余额 "+MallMoney.show(b.quote.optString("balance"))+" SPX001"+fxLine);new AlertDialog.Builder(a).setTitle("确认支付 "+MallMoney.show(b.quote.optString("total_spx"))+" SPX001").setMessage(str(b.quote.optJSONObject("snapshot"),"name")+"\n"+str(b.quote,"variant_name")+" × "+b.quote.optInt("quantity")+"\n"+(b.quote.optJSONObject("address")==null?"":addressText(b.quote.optJSONObject("address")))+"\n请确认金额、规格及地址无误。").setNegativeButton("返回检查",null).setPositiveButton("确认支付",(dialog,n)->{if(pageValid(checkoutIdentity,checkoutEpoch))pay(b,button);}).show();});});
        String pending=prefs().getString("pending","");if(!pending.isEmpty())action(body,"查询上一笔支付结果",this::recoverPayment,false);
    }
    private android.content.SharedPreferences prefs(){return new MallSession(a).payments;}
    private interface PasswordDone{void done(String password);}
    private void pay(final Buy b,final TextView button){
        final MallSession identity=new MallSession(a);
        if(!identity.payments.getString("pending","").isEmpty()){recoverPayment();return;}
        if(b.quote==null){toast("请重新核对订单金额");return;}
        promptPayPassword(MallMoney.show(b.quote.optString("total_spx")),password->submitPay(b,button,password));
    }
    /** 下单必须输入 6 位支付密码，与红包、提现一致。 */
    private void promptPayPassword(String amount,PasswordDone done){
        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(8),dp(20),0);
        box.addView(text("支付金额  "+amount+" SPX001",13,MallUi.SECONDARY));
        final EditText input=new EditText(a);input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("输入 6 位数字支付密码");box.addView(input);
        final AlertDialog dialog=new AlertDialog.Builder(a).setTitle("支付验证").setView(box)
            .setNegativeButton("取消",null).setPositiveButton("确认支付",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String pwd=input.getText().toString().trim();
            if(!pwd.matches("[0-9]{6}")){input.setError("请输入 6 位数字支付密码");return;}
            dialog.dismiss();done.done(pwd);
        }));
        dialog.show();
    }
    private void submitPay(Buy b,TextView button,String password){
        final MallSession identity=new MallSession(a);final long epoch=pageEpoch;
        JSONObject base=obj("quote_id",str(b.quote,"quote_id"),"client_request_id",UUID.randomUUID().toString().replace("-",""));
        // 只把可重放的字段落盘，支付密码仅保存在内存里
        final String stored=base.toString();
        JSONObject payload;
        try{payload=new JSONObject(stored);payload.put("payment_password",password);}catch(Exception e){toast("支付参数异常，请重试");return;}
        if(!identity.payments.edit().putString("pending",stored).commit()){toast("无法保存支付请求，请重试");return;}
        button.setEnabled(false);
        ApiClient.post(a,"orders.redeem",payload,new ApiClient.Callback(){
            public void onSuccess(Object data){
                if(!(data instanceof JSONObject)||((JSONObject)data).optInt("order_id")<=0){onError("支付结果待确认");return;}
                identity.clearPayment(stored);
                if(!pageValid(identity,epoch))return;button.setEnabled(true);replace(()->orderPage(((JSONObject)data).optInt("order_id")));
            }
            public void onError(String message){
                if(!pageValid(identity,epoch))return;
                button.setEnabled(true);
                identity.clearPayment(stored);   // 密码错误等服务端拒绝：不保留待确认状态
                b.quote=null;                    // 清掉旧报价，避免下次显示过期余额
                toast(message);
            }
        });
    }
    private void recoverPayment(){
        final MallSession identity=new MallSession(a);final long epoch=pageEpoch;
        final String stored=identity.payments.getString("pending","");
        try{JSONObject payload=new JSONObject(stored.isEmpty()?"{}":stored);if(str(payload,"quote_id").isEmpty())return;
            ApiClient.post(a,"checkout.resolve",payload,new ApiClient.Callback(){
                public void onSuccess(Object data){
                    if(!(data instanceof JSONObject))return;JSONObject r=(JSONObject)data;
                    if(!r.optBoolean("found")&&!r.optBoolean("voided"))return;
                    identity.clearPayment(stored);if(!pageValid(identity,epoch))return;
                    if(r.optBoolean("found"))replace(()->orderPage(r.optInt("order_id")));
                    else toast("上一笔未生成订单，也未扣款；请重新核对金额后支付");
                }
                public void onError(String error){if(pageValid(identity,epoch))toast(error);}
            });
        }catch(Exception e){if(pageValid(identity,epoch))toast("支付记录无法读取，请先到订单中心核对");}
    }

    void addresses(JsonResult selected){show(()->addressPage(selected));}
    private void addressPage(JsonResult selected){LinearLayout body=page(selected==null?"收货地址":"选择收货地址");action(body,"＋ 新增收货地址",()->addressForm(null,selected),true);get("wallet.address",null,data->{if(!(data instanceof JSONArray))return;JSONArray all=(JSONArray)data;if(all.length()==0)body.addView(text("还没有地址，添加后即可下单",14,MallUi.SECONDARY));for(int i=0;i<all.length();i++){JSONObject x=all.optJSONObject(i);LinearLayout c=card(body);c.addView(text(addressText(x)+(x.optInt("is_default")==1?"\n默认地址":""),15,MallUi.INK));if(selected!=null)action(c,"使用此地址",()->{back();selected.done(x);},true);action(c,"编辑地址",()->addressForm(x,selected),false);if(x.optInt("is_default")!=1)action(c,"设为默认",()->post("wallet.address.default",obj("id",x.optInt("id")),null,d->replace(()->addressPage(selected))),false);action(c,"删除地址",()->new AlertDialog.Builder(a).setMessage("删除这条收货地址？").setNegativeButton("保留",null).setPositiveButton("删除",(d,n)->post("wallet.address.delete",obj("id",x.optInt("id")),null,r->replace(()->addressPage(selected)))).show(),false);}});}
    private void addressForm(JSONObject old,JsonResult selected){show(()->{LinearLayout body=page(old==null?"新增地址":"编辑地址");String[] keys={"name","phone","province","city","district","detail"};String[] labels={"收货人","联系电话","省 / 自治区 / 直辖市","城市","区 / 县","街道、楼栋、门牌号"};Map<String,EditText> fields=new HashMap<>();for(int i=0;i<keys.length;i++){EditText e=field(body,labels[i],str(old,keys[i]),i==5);if(i==1)e.setInputType(InputType.TYPE_CLASS_PHONE);fields.put(keys[i],e);}Switch defaultSwitch=new Switch(a);defaultSwitch.setText("设为默认地址");defaultSwitch.setChecked(old==null||old.optInt("is_default")==1);body.addView(defaultSwitch);TextView save=action(body,"保存并使用",()->{},true);save.setOnClickListener(v->{JSONObject req=obj("id",old==null?0:old.optInt("id"),"is_default",defaultSwitch.isChecked());for(String k:keys){String val=fields.get(k).getText().toString().trim();if(val.isEmpty()){toast("请完整填写收货信息");return;}try{req.put(k,val);}catch(Exception ignored){}}post(old==null?"wallet.address.save":"wallet.address.update",req,save,data->{if(old==null&&data instanceof JSONObject)try{req.put("id",((JSONObject)data).optInt("id"));}catch(Exception ignored){}back();if(selected!=null){back();selected.done(req);}else replace(()->addressPage(null));});});});}
    void orders(){show(()->orderList("all",1));}
    private void orderList(String filter,int pageNo){LinearLayout body=page("我的订单");HorizontalScrollView tabs=new HorizontalScrollView(a);LinearLayout row=row();String[] labels={"全部","待发货 / 交付","待收货","待使用","已完成","售后"},values={"all","pending","shipped","local","delivered","after_sale"};for(int i=0;i<labels.length;i++){String value=values[i];LinearLayout wrap=col();row.addView(wrap,new LinearLayout.LayoutParams(dp(120),-2));action(wrap,labels[i],()->replace(()->orderList(value,1)),filter.equals(value));}tabs.addView(row);body.addView(tabs);if(!prefs().getString("pending","").isEmpty())action(body,"查询待确认支付结果",this::recoverPayment,false);get("orders",args("status",filter,"page",""+pageNo,"page_size","20"),data->{if(!(data instanceof JSONArray))return;JSONArray list=(JSONArray)data;if(list.length()==0)body.addView(text("暂无此类订单",14,MallUi.SECONDARY));for(int i=0;i<list.length();i++){JSONObject o=list.optJSONObject(i);LinearLayout c=card(body);c.addView(text(status(o)+" · #"+o.optInt("id"),13,MallUi.PRIMARY));LinearLayout r=row();ImageView iv=new ImageView(a);r.addView(iv,new LinearLayout.LayoutParams(dp(76),dp(76)));MallImages.bind(iv,str(o,"image_url"),false);LinearLayout info=col();info.setPadding(dp(12),0,0,0);info.addView(text(str(o,"name"),16,MallUi.INK));info.addView(text(str(o,"variant_name")+" × "+o.optInt("quantity"),12,MallUi.SECONDARY));info.addView(text(MallMoney.show(str(o,"total_spx"))+" SPX001",17,MallUi.PRIMARY));r.addView(info,new LinearLayout.LayoutParams(0,-2,1));c.addView(r);action(c,"查看订单详情",()->order(o.optInt("id")),false);}pagination(body,pageNo,list.length(),20,n->replace(()->orderList(filter,n)));});}
    private void pagination(LinearLayout body,int current,int size,int limit,PageNumber go){body.addView(text("第 "+current+" 页",12,MallUi.SECONDARY));if(current>1)action(body,"上一页",()->go.accept(current-1),false);if(size==limit)action(body,"下一页",()->go.accept(current+1),false);}
    void order(int id){show(()->orderPage(id));}
    private void orderPage(int id){LinearLayout body=page("订单详情");get("order",args("id",""+id),data->{if(!(data instanceof JSONObject))return;JSONObject o=(JSONObject)data;JSONObject snapshot=o.optJSONObject("snapshot");if(snapshot==null)snapshot=new JSONObject();final JSONObject receipt=snapshot;LinearLayout head=card(body);head.addView(title(status(o)));head.addView(text("订单 #"+id+" · "+str(o,"created_at"),12,MallUi.SECONDARY));if(!str(o,"image_url").isEmpty())image(head,str(o,"image_url"),180,false);head.addView(title(str(o,"name")));head.addView(text(str(o,"variant_name")+" × "+o.optInt("quantity"),15,MallUi.INK));JSONObject bought=receipt.optJSONObject("variant");String paidCny=bought==null?"":str(bought,"cny_price");JSONObject fx=receipt.optJSONObject("pricing");String cnyLine=paidCny.isEmpty()?"":"商品标价 ¥"+MallMoney.show(paidCny)+" CNY\n";String fxLine=fx==null?"":"\n下单行情：1 SPX001 = $"+str(fx,"spx_usd")+"；1 USD = "+str(fx,"usd_cny")+" CNY";head.addView(text(cnyLine+"单价 "+MallMoney.show(o.optString("unit_price","0"))+" SPX001\n运费 "+MallMoney.show(o.optString("shipping_fee","0"))+" SPX001\n实付 "+MallMoney.show(str(o,"total_spx"))+" SPX001"+fxLine,15,MallUi.PRIMARY));if(!str(o,"buyer_note").isEmpty())head.addView(text("留言："+str(o,"buyer_note"),13,MallUi.SECONDARY));JSONObject address=o.optJSONObject("address");if(address!=null)card(body).addView(text(addressText(address),14,MallUi.INK));
        if(!str(o,"tracking_number").isEmpty()){LinearLayout logistics=card(body);logistics.addView(title("物流信息"));logistics.addView(text(str(o,"carrier")+" · "+str(o,"tracking_number"),14,MallUi.INK));action(logistics,"复制物流单号",()->copy(str(o,"tracking_number")),false);}if(!str(o,"fulfillment_note").isEmpty()){LinearLayout f=card(body);f.addView(title("交付内容"));TextView content=text(str(o,"fulfillment_note"),15,MallUi.INK);content.setTextIsSelectable(true);f.addView(content);action(f,"复制交付内容",()->copy(str(o,"fulfillment_note")),false);}
        if(str(o,"order_type").equals("local")&&str(o,"status").equals("pending")){LinearLayout use=card(body);use.addView(title("到店核销码"));
            String redeemCode=str(o,"redeem_code");
            if(redeemCode.matches("[0-9]{16}")){
                Bitmap qr=qrBitmap(redeemCode,dp(200));
                if(qr!=null){ImageView qrView=new ImageView(a);qrView.setImageBitmap(qr);qrView.setAdjustViewBounds(true);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(200),dp(200));lp.gravity=Gravity.CENTER_HORIZONTAL;lp.topMargin=dp(10);
                    use.addView(qrView,lp);}
            }
            TextView digits=text(groupDigits(redeemCode),24,MallUi.PRIMARY);digits.setGravity(Gravity.CENTER);digits.setPadding(0,dp(10),0,dp(4));use.addView(digits);
            use.addView(text("商家扫码，或手动输入这 16 位数字核销\n有效期至 "+str(o,"service_expires_at")+"\n核销后次日自动结算给商家",13,MallUi.SECONDARY));
            action(use,"复制核销码",()->copy(redeemCode),false);}
        JSONObject shop=receipt.optJSONObject("shop");if(shop!=null){LinearLayout store=card(body);store.addView(title(str(shop,"name")));action(store,"联系商家",()->a.mallContact(shop),false);if(!str(shop,"address").isEmpty())action(store,"导航到使用门店",()->MapNavigation.open(a,shop),false);}
        if(!str(receipt,"service_terms").isEmpty()){LinearLayout terms=card(body);terms.addView(title("购买时的服务条款"));terms.addView(text(str(receipt,"service_terms"),13,MallUi.SECONDARY));}
        String af=str(o,"after_sale_status");if(!af.isEmpty()&&!af.equals("none")){LinearLayout sale=card(body);sale.addView(title("售后进度"));Map<String,String> names=new HashMap<>();names.put("requested","申请已提交");names.put("return_approved","已同意退货");names.put("return_shipped","已寄回，等待核实");names.put("rejected","申请未通过");names.put("refunded","退款已到账");sale.addView(text((names.containsKey(af)?names.get(af):af)+"\n"+str(o,"after_sale_reason")+"\n"+str(o,"after_sale_reply"),14,MallUi.INK));if(!str(o,"merchant_service_reply").isEmpty())sale.addView(text("商家回复："+str(o,"merchant_service_reply"),14,MallUi.PRIMARY));JSONArray evidence=o.optJSONArray("after_sale_images");if(evidence!=null)for(int i=0;i<evidence.length();i++){String url=evidence.optString(i);ImageView iv=image(sale,url,120,false);iv.setOnClickListener(v->preview(url));}if(!str(o,"return_address").isEmpty())sale.addView(text("退货地址："+str(o,"return_address"),14,MallUi.INK));if(af.equals("return_approved"))action(sale,"填写退货物流",()->returnForm(id),true);if(!str(o,"return_tracking").isEmpty())sale.addView(text("退货物流："+str(o,"return_carrier")+" "+str(o,"return_tracking"),14,MallUi.SECONDARY));}
        if(str(o,"status").equals("pending"))action(body,"取消订单并退款",()->confirm("取消后将退回原余额，确定取消？",()->post("orders.cancel",obj("order_id",id),null,r->replace(()->orderPage(id)))),false);
        boolean held=af.equals("requested")||af.equals("return_approved")||af.equals("return_shipped");
        if(str(o,"status").equals("shipped")&&!held)action(body,"确认收货 / 交付完成",()->confirm("请确认已经收到商品或交付内容，确认后开始七天售后期。",()->post("orders.receive",obj("order_id",id),null,r->replace(()->orderPage(id)))),true);
        if(o.optBoolean("can_after_sale")&&!held)action(body,"申请售后",()->afterSale(o),false);
        if(str(o,"status").equals("delivered")||str(o,"status").equals("completed"))action(body,"评价已购商品",()->reviewOrder(o),false);
        LinearLayout events=card(body);events.addView(title("订单进度"));JSONArray timeline=o.optJSONArray("events");if(timeline!=null)for(int i=0;i<timeline.length();i++){JSONObject e=timeline.optJSONObject(i);events.addView(text(str(e,"created_at")+"\n"+str(e,"message"),13,MallUi.SECONDARY));}action(body,"刷新订单",()->replace(()->orderPage(id)),false);});}
    private void copy(String value){((ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("订单信息",value));toast("已复制");}
    private void reviews(int productId){show(()->{LinearLayout body=page("买家评价");get("product.reviews",args("product_id",""+productId),data->{if(!(data instanceof JSONArray))return;JSONArray rows=(JSONArray)data;for(int i=0;i<rows.length();i++){JSONObject review=rows.optJSONObject(i);LinearLayout c=card(body);c.addView(title(str(review,"nickname")+" · "+review.optInt("rating")+" / 5 分"));c.addView(text(str(review,"content"),15,MallUi.INK));c.addView(text(str(review,"created_at"),12,MallUi.SECONDARY));}if(rows.length()==0)body.addView(text("暂无评价，完成订单后可分享真实购买体验。",14,MallUi.SECONDARY));});});}
    private void reviewOrder(JSONObject order){
        LinearLayout form=col();form.setPadding(dp(16),dp(12),dp(16),dp(12));form.addView(text("请根据实际商品质量和服务评分，每个已完成订单可评价一次。",13,MallUi.SECONDARY));RatingBar rating=new RatingBar(a);rating.setNumStars(5);rating.setStepSize(1);rating.setRating(5);form.addView(rating,new LinearLayout.LayoutParams(-2,-2));EditText message=field(form,"购买体验（不超过 1000 字）","",true);message.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("评价商品").setView(form).setNegativeButton("返回",null).setPositiveButton("提交评价",null).create();dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{String content=message.getText().toString().trim();if(content.isEmpty()){message.setError("请填写真实购买体验");return;}post("product.review",obj("order_id",order.optInt("id"),"product_id",order.optInt("product_id"),"rating",(int)rating.getRating(),"content",content),dialog.getButton(-1),r->{dialog.dismiss();toast("评价已提交");reviews(order.optInt("product_id"));});}));dialog.show();
    }
    private void confirm(String message,Runnable run){new AlertDialog.Builder(a).setMessage(message).setNegativeButton("返回",null).setPositiveButton("确认",(d,n)->run.run()).show();}
    private void afterSale(JSONObject order){show(()->{LinearLayout body=page("申请售后");String[] type={"refund_only"};TextView choose=action(body,"售后类型：仅退款",()->{},false);if(str(order,"order_type").equals("physical")&&!str(order,"status").equals("pending"))choose.setOnClickListener(v->new AlertDialog.Builder(a).setItems(new String[]{"仅退款","退货退款"},(d,n)->{type[0]=n==0?"refund_only":"return_refund";choose.setText(n==0?"仅退款":"退货退款");}).show());EditText reason=field(body,"问题说明与退款原因","",true);ArrayList<String> evidence=new ArrayList<>();mediaEditor(body,"上传凭证（最多六张）",evidence,6,"orders.media.upload");TextView submit=action(body,"提交售后申请",()->{},true);submit.setOnClickListener(v->{String why=reason.getText().toString().trim();if(why.isEmpty()){toast("请填写原因");return;}post("orders.after_sale",obj("order_id",order.optInt("id"),"type",type[0],"reason",why,"images",new JSONArray(evidence)),submit,r->{back();replace(()->orderPage(order.optInt("id")));});});});}
    private void returnForm(int id){LinearLayout form=col();form.setPadding(dp(16),dp(16),dp(16),dp(16));EditText carrier=field(form,"承运商","",false),number=field(form,"退货单号","",false);AlertDialog d=new AlertDialog.Builder(a).setTitle("填写退货物流").setView(form).setNegativeButton("返回",null).setPositiveButton("提交",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->post("orders.return",obj("order_id",id,"carrier",carrier.getText().toString().trim(),"tracking_number",number.getText().toString().trim()),d.getButton(-1),r->{d.dismiss();replace(()->orderPage(id));})));d.show();}
    private ArrayList<String> strings(JSONArray a){ArrayList<String> r=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++)r.add(a.optString(i));return r;}
    private void mediaEditor(LinearLayout body,String heading,ArrayList<String> urls,int limit,String endpoint){
        LinearLayout headingRow=row();TextView label=text(heading,15,MallUi.INK);label.setTypeface(null,android.graphics.Typeface.BOLD);headingRow.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView count=text("0 / "+limit,11,MallUi.SECONDARY);headingRow.addView(count);body.addView(headingRow);
        final boolean mainImages=heading.startsWith("商品主图");
        TextView hint=text(mainImages?"支持批量选择，第一张为封面；点图片预览，点更多调整。":"支持批量选择，点图片预览，点更多调整顺序。",12,MallUi.SECONDARY);hint.setPadding(0,dp(5),0,dp(10));body.addView(hint);
        HorizontalScrollView scroll=new HorizontalScrollView(a);scroll.setHorizontalScrollBarEnabled(false);LinearLayout gallery=row();scroll.addView(gallery,new HorizontalScrollView.LayoutParams(-2,-2));body.addView(scroll);
        Runnable[] draw={null};draw[0]=()->{
            gallery.removeAllViews();count.setText(urls.size()+" / "+limit);
            for(int i=0;i<urls.size();i++){
                int n=i;String url=urls.get(i);LinearLayout tile=col();tile.setPadding(0,0,dp(10),0);
                ImageView iv=new ImageView(a);iv.setScaleType(ImageView.ScaleType.FIT_CENTER);iv.setClipToOutline(true);iv.setBackground(MallUi.surface(a,MallUi.BACKGROUND,12,true));
                MallImages.bind(iv,url,false);tile.addView(iv,new LinearLayout.LayoutParams(dp(88),dp(88)));iv.setContentDescription("预览第"+(n+1)+"张图片");iv.setOnClickListener(v->preview(url));
                LinearLayout controls=row();TextView order=text(mainImages&&n==0?"封面":"图片 "+(n+1),12,n==0?MallUi.PRIMARY:MallUi.SECONDARY);controls.addView(order,new LinearLayout.LayoutParams(0,-2,1));
                TextView more=smallAction("•••",()->{});more.setBackgroundColor(Color.TRANSPARENT);more.setContentDescription("第"+(n+1)+"张图片的更多操作");controls.addView(more,new LinearLayout.LayoutParams(dp(44),dp(44)));
                more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(a,more);if(n>0){menu.getMenu().add(0,1,0,mainImages?"设为封面":"移到第一张");menu.getMenu().add(0,2,1,"向前移动");}if(n<urls.size()-1)menu.getMenu().add(0,3,2,"向后移动");menu.getMenu().add(0,4,3,"移除图片");
                    menu.setOnMenuItemClickListener(item->{int id=item.getItemId();if(id==1){urls.remove(n);urls.add(0,url);}else if(id==2)Collections.swap(urls,n,n-1);else if(id==3)Collections.swap(urls,n,n+1);else urls.remove(n);draw[0].run();changedProduct();return true;});menu.show();});
                tile.addView(controls,new LinearLayout.LayoutParams(dp(88),-2));gallery.addView(tile,new LinearLayout.LayoutParams(dp(98),-2));
            }
        };draw[0].run();
        TextView status=text("",11,MallUi.SECONDARY);status.setVisibility(View.GONE);body.addView(status);
        LinearLayout recovery=row();recovery.setVisibility(View.GONE);body.addView(recovery);
        TextView retry=smallAction("重试本张",this::retryBatch),skip=smallAction("跳过本张",this::skipBatch),cancel=smallAction("取消剩余",this::cancelBatch);
        for(TextView control:new TextView[]{retry,skip,cancel})recovery.addView(control,new LinearLayout.LayoutParams(0,dp(42),1));
        TextView add=action(body,"＋ 批量选择手机图片",()->{},false);
        add.setOnClickListener(v->{if(urls.size()>=limit){toast("最多 "+limit+" 张图片");return;}pickImages(endpoint,limit-urls.size(),values->{urls.addAll(values);draw[0].run();changedProduct();},message->{status.setText(message);status.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);recovery.setVisibility(batchFailed?View.VISIBLE:View.GONE);add.setEnabled(!imageUploading&&!batchFailed);add.setText(imageUploading?"图片上传中…":batchFailed?"请先处理失败图片":"＋ 批量选择手机图片");});});
    }
    private TextView smallAction(String label,Runnable run){TextView t=text(label,16,MallUi.PRIMARY);t.setGravity(Gravity.CENTER);t.setPadding(0,0,0,0);t.setBackground(MallUi.surface(a,Color.WHITE,8,true));MallUi.touch(t,8);t.setOnClickListener(v->run.run());return t;}
    private void merchantCapacity(LinearLayout body){
        TextView slots=text("正在读取上架名额…",13,MallUi.SECONDARY);body.addView(slots);
        Runnable refresh=()->{slots.setText("正在读取上架名额…");get("merchant.me",null,data->{
            if(!(data instanceof JSONObject)){slots.setText("名额读取失败，点击重试");return;}
            JSONObject cap=((JSONObject)data).optJSONObject("product_capacity");
            if(cap==null||!cap.has("limit")||cap.optInt("limit")<0){slots.setText("上架名额暂不可用，点击重试");return;}
            slots.setText("已上架 "+cap.optInt("listed")+"/"+cap.optInt("limit")+" · 还可上架 "+cap.optInt("remaining")+" 款\n下架或删除商品后释放上架名额");
        },error->slots.setText("名额读取失败，点击重试"));};
        slots.setOnClickListener(v->refresh.run());refresh.run();
    }
    void merchantProducts(){show(()->merchantProductList(1));}
    private void merchantProductList(int pageNo){
        LinearLayout body=page("商品管理");
        merchantCapacity(card(body));
        LinearLayout tools=row();
        inlineAction(tools,"＋ 发布商品",()->editProduct(null),true);
        inlineAction(tools,"草稿箱",this::drafts,false);
        body.addView(tools,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView desktop=action(body,"电脑商户后台 ↗",()->copy(ApiClient.WEB_BASE+"/spxchat/merchant.php"),false);
        desktop.setTextSize(12);desktop.setBackgroundColor(Color.TRANSPARENT);
        TextView loading=text("正在读取商品…",13,MallUi.SECONDARY);loading.setPadding(0,dp(16),0,dp(16));body.addView(loading);
        get("merchant.products",args("page",""+pageNo,"page_size","20"),data->{
            body.removeView(loading);
            if(!(data instanceof JSONArray)){action(body,"商品读取失败，点击重试",()->replace(()->merchantProductList(pageNo)),false);return;}
            JSONArray products=(JSONArray)data;
            for(int i=0;i<products.length();i++){
                JSONObject p=products.optJSONObject(i);if(p==null)continue;
                LinearLayout c=card(body),heading=row(),info=col();heading.setGravity(Gravity.TOP);
                String cover=str(p,"image_url");JSONArray photos=p.optJSONArray("images");
                if(cover.isEmpty()&&photos!=null&&photos.length()>0)cover=photos.optString(0);
                ImageView photo=new ImageView(a);photo.setScaleType(ImageView.ScaleType.FIT_CENTER);photo.setClipToOutline(true);
                photo.setBackground(MallUi.surface(a,MallUi.BACKGROUND,12,true));MallImages.bind(photo,cover,false);
                heading.addView(photo,new LinearLayout.LayoutParams(dp(82),dp(82)));
                LinearLayout.LayoutParams infoSize=new LinearLayout.LayoutParams(0,-2,1);infoSize.setMargins(dp(12),0,0,0);heading.addView(info,infoSize);
                TextView name=text(str(p,"name"),16,MallUi.INK);name.setTypeface(null,android.graphics.Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);info.addView(name);
                String cny=str(p,"cny_price");
                if(cny.isEmpty()){JSONArray variants=p.optJSONArray("variants");if(variants!=null&&variants.length()>0)cny=str(variants.optJSONObject(0),"cny_price");}
                String priceLabel=cny.isEmpty()?"历史 SPX001 "+MallMoney.show(str(p,"spx_price")):"¥"+MallMoney.show(cny)+" CNY";
                TextView price=text(priceLabel,15,MallUi.PRIMARY);price.setPadding(0,dp(6),0,dp(4));info.addView(price);
                info.addView(text("库存 "+p.optInt("stock"),12,MallUi.SECONDARY));c.addView(heading);
                String review=str(p,"review_status");
                String state=p.optInt("status")!=1?"已下架":"rejected".equals(review)?"审核未通过":"pending".equals(review)?"审核中":"已上架";
                TextView badge=text(state,12,MallUi.SECONDARY);badge.setPadding(0,dp(10),0,dp(6));c.addView(badge);
                if(!str(p,"review_reason").isEmpty())c.addView(text(str(p,"review_reason"),13,Color.rgb(190,55,55)));
                LinearLayout controls=row();inlineAction(controls,"编辑商品",()->editProduct(p),false);
                TextView more=smallAction("•••",()->{});more.setContentDescription("更多商品操作");
                LinearLayout.LayoutParams moreSize=new LinearLayout.LayoutParams(dp(48),dp(44));moreSize.setMargins(dp(8),0,0,0);controls.addView(more,moreSize);
                more.setOnClickListener(v->{
                    PopupMenu menu=new PopupMenu(a,more);menu.getMenu().add(0,1,0,"调整库存");
                    boolean listed=p.optInt("status")==1;if(!listed&&"approved".equals(review))menu.getMenu().add(0,3,0,"上架商品");menu.getMenu().add(0,2,1,listed?"下架商品":"删除商品");
                    menu.setOnMenuItemClickListener(item->{
                        if(item.getItemId()==3)post("merchant.product.online",obj("id",p.optInt("id")),null,r->replace(()->merchantProductList(pageNo)));
                        else if(item.getItemId()==1)inventory(p);
                        else confirm(listed?"下架后买家将无法继续下单，已有订单仍需履约。":"确认删除已下架商品？历史订单和售后记录仍保留。",()->post(listed?"merchant.product.offline":"merchant.product.delete",obj("id",p.optInt("id")),null,r->replace(()->merchantProductList(pageNo))));
                        return true;
                    });menu.show();
                });
                c.addView(controls,new LinearLayout.LayoutParams(-1,dp(52)));
                if(p.optInt("status")!=1&&"approved".equals(review)){LinearLayout publishRow=row();inlineAction(publishRow,"上架商品",()->post("merchant.product.online",obj("id",p.optInt("id")),null,r->replace(()->merchantProductList(pageNo))),true);c.addView(publishRow,new LinearLayout.LayoutParams(-1,dp(52)));}
            }
            if(products.length()==0){TextView empty=text("暂无商品，点击发布商品开始添加",14,MallUi.SECONDARY);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(12),dp(36),dp(12),dp(36));body.addView(empty);}
            pagination(body,pageNo,products.length(),20,n->replace(()->merchantProductList(n)));
        },error->{body.removeView(loading);body.addView(text(error,13,MallUi.SECONDARY));action(body,"重新加载商品",()->replace(()->merchantProductList(pageNo)),false);});
    }
    private TextView inlineAction(LinearLayout parent,String label,Runnable run,boolean primary){
        TextView button=action(parent,label,run,primary);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,dp(44),1);size.setMargins(0,0,parent.getChildCount()==1?dp(8):0,0);
        button.setLayoutParams(size);button.setPadding(dp(8),0,dp(8),0);button.setGravity(Gravity.CENTER);return button;
    }
    private LinearLayout disclosure(LinearLayout parent,String heading,String summary,LinearLayout content,boolean expanded){
        if(content.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)content.getParent()).removeView(content);
        LinearLayout wrapper=card(parent),toggle=row(),labels=col();
        TextView title=text(heading,16,MallUi.INK);title.setTypeface(null,android.graphics.Typeface.BOLD);labels.addView(title);
        TextView note=text(summary,12,MallUi.SECONDARY);note.setPadding(0,dp(5),0,0);labels.addView(note);
        toggle.addView(labels,new LinearLayout.LayoutParams(0,-2,1));View arrow=MallUi.icon(a,"chevron",MallUi.SECONDARY);toggle.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
        toggle.setMinimumHeight(dp(52));toggle.setContentDescription(heading);wrapper.addView(toggle);
        content.setBackgroundColor(Color.TRANSPARENT);content.setPadding(0,dp(12),0,0);content.setVisibility(expanded?View.VISIBLE:View.GONE);arrow.setRotation(expanded?90:0);wrapper.addView(content);
        toggle.setOnClickListener(v->{boolean open=content.getVisibility()!=View.VISIBLE;content.setVisibility(open?View.VISIBLE:View.GONE);arrow.setRotation(open?90:0);});MallUi.touch(toggle,12);return wrapper;
    }
    void editProduct(JSONObject original){
        final JSONObject p;try{p=original==null?new JSONObject():new JSONObject(original.toString());}catch(Exception e){toast("商品资料无法读取");return;}
        final MallSession identity=new MallSession(a);
        final MallProductDraft local=new MallProductDraft(a,identity.server,identity.userId,p);
        JSONObject recovered=local.load();
        if(recovered!=null){
            new AlertDialog.Builder(a).setTitle("恢复未发布的商品草稿？").setMessage("发现上次保存在本机的内容，包含已上传图片和规格。")
                .setNegativeButton("使用当前资料",(d,n)->{if(!identity.valid(a))return;local.clear();show(()->productForm(p,p.optInt("id")>0));})
                .setPositiveButton("恢复草稿",(d,n)->{if(!identity.valid(a))return;show(()->productForm(recovered,recovered.optInt("id")>0));}).show();
        }else show(()->productForm(p,p.optInt("id")>0));
    }
    private void drafts(){show(()->{LinearLayout body=page("商品草稿");get("merchant.drafts",args("page_size","60"),data->{if(!(data instanceof JSONArray))return;JSONArray rows=(JSONArray)data;for(int i=0;i<rows.length();i++){JSONObject draft=rows.optJSONObject(i);LinearLayout c=card(body);c.addView(title(str(draft,"title")));c.addView(text(str(draft,"updated_at"),12,MallUi.SECONDARY));action(c,"继续编辑",()->{JSONObject payload=draft.optJSONObject("payload");if(payload!=null){try{payload.put("draft_id",draft.optInt("id"));payload.put("draft_version",draft.optInt("version"));}catch(Exception ignored){}editProduct(payload);}},false);action(c,"删除草稿",()->confirm("删除此草稿？",()->post("merchant.draft.delete",obj("id",draft.optInt("id")),null,r->{back();drafts();})),false);}if(rows.length()==0)body.addView(text("暂无草稿",14,MallUi.SECONDARY));});});}
    private void productForm(JSONObject p,boolean existing){
        LinearLayout body=page(existing?"编辑商品":"发布商品");
        final MallSession formIdentity=new MallSession(a);
        productDraft=new MallProductDraft(a,formIdentity.server,formIdentity.userId,p);
        final MallProductDraft localDraft=productDraft;
        final long formEpoch=pageEpoch;
        LinearLayout progress=row();progress.setPadding(0,dp(2),0,dp(10));
        TextView[] stepButtons=new TextView[3];String[] steps={"商品资料","价格库存","补充资料"};
        for(int i=0;i<steps.length;i++){TextView step=text((i+1)+"  "+steps[i],12,MallUi.SECONDARY);step.setGravity(Gravity.CENTER);step.setBackground(MallUi.surface(a,Color.WHITE,10,true));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(44),1);sp.setMargins(i==0?0:dp(4),0,0,0);progress.addView(step,sp);stepButtons[i]=step;}body.addView(progress);
        TextView intro=text("资料、图片、价格和规格通过系统校验后自动上架。已发布规格的库存请在库存管理单独调整。",12,MallUi.SECONDARY);intro.setPadding(0,0,0,dp(8));body.addView(intro);Map<String,EditText> fields=new LinkedHashMap<>();
         String[] keys={"name","description","cny_price","stock","parameters","detail_text","delivery_note","service_terms","video_url","shipping_fee","delivery_days","service_valid_days"};String[] labels={"商品名称 *","商品简介 *","商品价格（CNY） *","首次发布库存 *","商品参数（每行 参数：值）","详细介绍","交付 / 使用说明","售后与服务条款","商品视频地址（选填）","实物运费（SPX001 / 单，0 为包邮）","实物发货 / 数字交付天数（0—90）","到店服务有效天数（1—365）"};
        String[] type={p.optString("product_type","physical")};TextView kind=action(body,"商品类型："+typeName(type[0]),()->{},false);if(!existing)kind.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("商品类型").setItems(new String[]{"实物快递","数字商品","到店服务"},(d,n)->{type[0]=new String[]{"physical","digital","local"}[n];kind.setText("商品类型："+typeName(type[0]));}).show());
        String[] category={p.optString("category","其他")};TextView cat=action(body,"类目："+category[0],()->{},false);cat.setOnClickListener(v->new AlertDialog.Builder(a).setItems(new String[]{"服饰","数码","家居","食品","本地服务","数字商品","其他"},(d,n)->{category[0]=new String[]{"服饰","数码","家居","食品","本地服务","数字商品","其他"}[n];cat.setText("类目："+category[0]);}).show());
        LinearLayout basics=card(body),pricing=card(body),detailsBox=card(body),fulfillment=card(body);basics.addView(title("商品基本资料"));pricing.addView(title("价格与库存"));pricing.addView(text("添加多种规格后，将使用各规格的价格和库存，无需重复填写标准价格。",12,MallUi.SECONDARY));detailsBox.addView(title("图文与服务说明"));fulfillment.addView(title("发货与使用时间"));for(int i=0;i<keys.length;i++){String value=str(p,keys[i]);if(value.isEmpty()){if(keys[i].equals("shipping_fee")||keys[i].equals("stock"))value="0";if(keys[i].equals("delivery_days"))value="3";if(keys[i].equals("service_valid_days"))value="30";}EditText e=field(i<2?basics:i<4?pricing:i<9?detailsBox:fulfillment,labels[i],value,i==1||i>=4&&i<=7);if(i==2||i==9)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);if(i==3||i==10||i==11)e.setInputType(InputType.TYPE_CLASS_NUMBER);if(i==3&&existing)e.setEnabled(false);fields.put(keys[i],e);}
        LinearLayout groupBox=card(body);groupBox.addView(title("商品咨询群 *"));groupBox.addView(text("先在聊天中创建商品群，选择你管理的群。买家可在商品详情加入咨询。",13,MallUi.SECONDARY));EditText groupCode=field(groupBox,"六位群号（可在群详情复制）",str(p,"consultation_group_code"),false);groupCode.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.AllCaps(),new android.text.InputFilter.LengthFilter(6)});action(groupBox,"从我管理的群选择",()->get("merchant.groups",null,data->{if(!(data instanceof JSONArray)){toast("咨询群读取失败，请重试");return;}JSONArray groups=(JSONArray)data;if(groups.length()==0){toast("暂无可管理的群，请检查群主或管理员身份，也可填写已有群号");return;}String[] labelsGroup=new String[groups.length()];for(int i=0;i<groups.length();i++)labelsGroup[i]=str(groups.optJSONObject(i),"name")+" · "+str(groups.optJSONObject(i),"group_code");new AlertDialog.Builder(a).setTitle("选择商品咨询群").setItems(labelsGroup,(d,n)->groupCode.setText(str(groups.optJSONObject(n),"group_code"))).show();}),false);
        Switch appointment=new Switch(a);appointment.setText("到店服务需提前预约");appointment.setChecked(p.optInt("appointment_required")==1);fulfillment.addView(appointment);
        Runnable visibility=()->{boolean local=type[0].equals("local");for(String key:new String[]{"shipping_fee","delivery_days","service_valid_days"}){EditText input=fields.get(key);LinearLayout parent=(LinearLayout)input.getParent();int position=parent.indexOfChild(input);boolean visible=key.equals("shipping_fee")?type[0].equals("physical"):key.equals("delivery_days")?!local:local;input.setVisibility(visible?View.VISIBLE:View.GONE);parent.getChildAt(position-1).setVisibility(visible?View.VISIBLE:View.GONE);}appointment.setVisibility(local?View.VISIBLE:View.GONE);};
        visibility.run();if(!existing)kind.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("商品类型").setItems(new String[]{"实物快递","数字商品","到店服务"},(d,n)->{type[0]=new String[]{"physical","digital","local"}[n];kind.setText("商品类型："+typeName(type[0]));visibility.run();}).show());
        ArrayList<String> main=strings(p.optJSONArray("images")),details=strings(p.optJSONArray("detail_images"));LinearLayout mainBox=card(body);mediaEditor(mainBox,"商品主图 *（最多八张）",main,8,"merchant.media.upload");mediaEditor(detailsBox,"图文详情（最多二十张）",details,20,"merchant.media.upload");
        ArrayList<JSONObject> skus=new ArrayList<>();JSONArray old=p.optJSONArray("variants");if(old!=null)for(int i=0;i<old.length();i++){JSONObject saved=old.optJSONObject(i);if(saved!=null&&(!saved.has("enabled")||saved.optInt("enabled",1)!=0))skus.add(saved);}LinearLayout variantContent=col();variantContent.addView(text("可添加颜色、尺码、套餐等属性组合。修改名称保留原规格编号；删除的历史规格会停用保存。",12,MallUi.SECONDARY));LinearLayout skuList=col();variantContent.addView(skuList);Runnable[] render={null};render[0]=()->{pricing.setVisibility(skus.isEmpty()?View.VISIBLE:View.GONE);skuList.removeAllViews();for(int i=0;i<skus.size();i++){int n=i;JSONObject sku=skus.get(i);LinearLayout c=card(skuList);c.addView(text(str(sku,"name")+" · ¥"+MallMoney.show(str(sku,"cny_price"))+" · 库存 "+sku.optInt("stock"),14,MallUi.INK));action(c,"编辑规格",()->skuForm(sku,existing&&containsSku(old,str(sku,"id")),x->{skus.set(n,x);render[0].run();}),false);action(c,"移除此规格",()->{skus.remove(n);render[0].run();},false);}};render[0].run();if(!existing||old!=null&&old.length()>0)action(variantContent,"＋ 添加规格组合",()->{if(skus.size()>=50){toast("最多 50 种规格");return;}skuForm(null,false,x->{skus.add(x);render[0].run();});},false);
        // Arrange the same inputs and handlers into required and optional sections.
        body.removeView(kind);body.removeView(cat);basics.addView(kind,1);basics.addView(cat,2);
        body.removeView(mainBox);body.addView(mainBox,2);
        body.removeView(detailsBox);body.removeView(fulfillment);
        LinearLayout variantBox=disclosure(body,"多规格商品","颜色、尺码或套餐 · 按需设置",variantContent,!skus.isEmpty());
        body.removeView(variantBox);body.addView(variantBox,body.indexOfChild(pricing)+1);
        LinearLayout supplemental=col();supplemental.addView(detailsBox);supplemental.addView(fulfillment);
        LinearLayout detailsSection=disclosure(body,"详情与交付","图文说明、运费、交付时间及售后条款",supplemental,existing);
        TextView localStatus=text("修改会自动保存在本机；发布前仍需确认。",12,MallUi.SECONDARY);localStatus.setPadding(0,dp(8),0,dp(12));body.addView(localStatus);
        productCapture=()->{
            // Save to the captured owner's store even while login is being cleared.
            try{
                JSONObject saved=new JSONObject(p.toString());
                for(String key:keys)saved.put(key,fields.get(key).getText().toString().trim());
                saved.put("product_type",type[0]).put("category",category[0]).put("consultation_group_code",groupCode.getText().toString().trim())
                    .put("appointment_required",appointment.isChecked()?1:0).put("images",new JSONArray(main)).put("detail_images",new JSONArray(details)).put("variants",new JSONArray(skus));
                String raw=saved.toString();
                // The navigation stack keeps p; keep it synchronized before rebuilding this form.
                for(Iterator<String> it=saved.keys();it.hasNext();){String key=it.next();p.put(key,saved.get(key));}
                if(productSaved.isEmpty()){productBaseline=raw;productSaved=raw;return;}
                if(!raw.equals(productSaved)){
                    localDraft.save(saved);productSaved=raw;localStatus.setText("已自动保存在本机 · 尚未发布");
                }
            }catch(Exception e){localStatus.setText("本机草稿未能保存，请手动保存后离开");}
        };
        productSaved="";productCapture.run();
        productDiscard=()->{try{JSONObject original=new JSONObject(productBaseline);ArrayList<String> remove=new ArrayList<>();for(Iterator<String> it=p.keys();it.hasNext();)remove.add(it.next());for(String key:remove)p.remove(key);for(Iterator<String> it=original.keys();it.hasNext();){String key=it.next();p.put(key,original.get(key));}}catch(Exception ignored){}};
        android.text.TextWatcher watcher=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence x,int st,int c,int aft){}public void onTextChanged(CharSequence x,int st,int before,int count){changedProduct();}public void afterTextChanged(android.text.Editable e){}};
        for(EditText input:fields.values())input.addTextChangedListener(watcher);groupCode.addTextChangedListener(watcher);
        appointment.setOnCheckedChangeListener((v,checked)->changedProduct());
        formHandler.removeCallbacks(scanProduct);formHandler.postDelayed(scanProduct,1000);
        body.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){if(pageEpoch==formEpoch){saveProductLocal();formHandler.removeCallbacks(scanProduct);}}});
        TextView saveDraft=action(body,"保存草稿",()->{if(!pageValid(formIdentity,formEpoch))return;if(imageUploading||multiDone!=null){toast("还有图片未完成，请重试或取消剩余图片后保存");return;}try{JSONObject req=obj("id",p.optInt("id"),"version",p.optInt("version"),"product_type",type[0],"category",category[0],"consultation_group_code",groupCode.getText().toString().trim(),"appointment_required",appointment.isChecked()?1:0,"images",new JSONArray(main),"detail_images",new JSONArray(details),"variants",new JSONArray(skus));for(String key:keys){String value=fields.get(key).getText().toString().trim();req.put(key,value);p.put(key,value);}for(String key:new String[]{"product_type","category","consultation_group_code","appointment_required","images","detail_images","variants"})p.put(key,req.get(key));post("merchant.draft.save",obj("id",p.optInt("draft_id"),"version",p.optInt("draft_version"),"payload",req),null,data->{if(data instanceof JSONObject){try{p.put("draft_id",((JSONObject)data).optInt("id"));p.put("draft_version",((JSONObject)data).optInt("version"));}catch(Exception ignored){}saveProductLocal();productBaseline=productSaved;toast("草稿已保存，可在草稿箱继续编辑");}});}catch(Exception e){toast("草稿保存失败，请重试");}},false);
        TextView publish=action(body,"预览并发布",()->{},true);publish.setOnClickListener(v->{if(!pageValid(formIdentity,formEpoch))return;if(imageUploading||multiDone!=null){toast("还有图片未完成，请重试或取消剩余图片后发布");return;}try{JSONObject req=obj("id",p.optInt("id"),"version",p.optInt("version"),"product_type",type[0],"category",category[0],"appointment_required",appointment.isChecked(),"images",new JSONArray(main),"detail_images",new JSONArray(details),"variants",new JSONArray(skus));for(String key:keys)req.put(key,fields.get(key).getText().toString().trim());req.put("consultation_group_code",groupCode.getText().toString().trim());if(!str(req,"consultation_group_code").matches("[A-Z0-9]{6}")){groupCode.setError("请选择商品咨询群或填写六位群号");groupCode.requestFocus();return;}if(main.isEmpty()||str(req,"name").isEmpty()||str(req,"description").isEmpty()){toast("请填写名称、简介并上传至少一张主图");return;}if(!skus.isEmpty()){req.put("cny_price",skus.get(0).optString("cny_price"));req.put("stock",0);}LinearLayout preview=col();preview.setPadding(dp(16),dp(12),dp(16),dp(12));image(preview,main.get(0),160,false);preview.addView(title(str(req,"name")));preview.addView(text(typeName(type[0])+" · "+category[0]+"\n"+str(req,"description")+"\n规格数量："+(skus.isEmpty()?1:skus.size())+"\n运费："+MallMoney.show(type[0].equals("physical")?str(req,"shipping_fee"):"0")+" SPX001\n咨询群："+str(req,"consultation_group_code")+"\n"+(type[0].equals("local")?"到店有效期："+str(req,"service_valid_days")+" 天":"承诺交付："+str(req,"delivery_days")+" 天"),14,MallUi.INK));AlertDialog dialog=new AlertDialog.Builder(a).setTitle("确认商品资料").setView(preview).setNegativeButton("继续编辑",null).setPositiveButton("确认发布",null).create();dialog.setOnShowListener(z->dialog.getButton(-1).setOnClickListener(x->{if(!pageValid(formIdentity,formEpoch)){dialog.dismiss();return;}post("merchant.product.save",req,dialog.getButton(-1),d->{dialog.dismiss();if(p.optInt("draft_id")>0)post("merchant.draft.delete",obj("id",p.optInt("draft_id")),null,r->{});clearProductLocal();toast("系统审核通过，商品已上架");back();replace(()->merchantProductList(1));});}));dialog.show();}catch(Exception e){toast("请检查商品资料");}});body.removeView(saveDraft);body.removeView(publish);
        LinearLayout bottom=row();bottom.setPadding(dp(16),dp(10),dp(16),dp(14));bottom.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams draftSize=new LinearLayout.LayoutParams(0,dp(48),1);draftSize.setMargins(0,0,dp(10),0);bottom.addView(saveDraft,draftSize);
        bottom.addView(publish,new LinearLayout.LayoutParams(0,dp(48),2));pageRoot.addView(bottom,new LinearLayout.LayoutParams(-1,-2));
        final ScrollView formScroll=pageScroll;View[] anchors={mainBox,pricing,detailsSection};
        Runnable highlight=()->{if(!pageValid(formIdentity,formEpoch))return;int selected=0;for(int n=0;n<anchors.length;n++){View v=n==1&&pricing.getVisibility()!=View.VISIBLE?variantBox:anchors[n];if(v.getVisibility()==View.VISIBLE&&formScroll.getScrollY()+dp(70)>=v.getTop())selected=n;}for(int n=0;n<stepButtons.length;n++){stepButtons[n].setTextColor(n==selected?MallUi.PRIMARY:MallUi.SECONDARY);stepButtons[n].setBackground(MallUi.surface(a,n==selected?MallUi.TINT:Color.WHITE,10,true));}};
        for(int n=0;n<stepButtons.length;n++){final int index=n;stepButtons[n].setOnClickListener(v->{View target=index==1&&pricing.getVisibility()!=View.VISIBLE?variantBox:anchors[index];formScroll.smoothScrollTo(0,Math.max(0,target.getTop()-dp(12)));});}
        formScroll.setOnScrollChangeListener((v,x,y,ox,oy)->highlight.run());formScroll.post(highlight);
    }
    private boolean containsSku(JSONArray all,String id){if(all!=null)for(int i=0;i<all.length();i++)if(id.equals(str(all.optJSONObject(i),"id")))return true;return false;}
    private String typeName(String type){return type.equals("physical")?"实物快递":type.equals("local")?"到店服务":"数字商品";}
    private void skuForm(JSONObject old,boolean fixedStock,JsonResult done){Dialog dialog=new Dialog(a);ScrollView scroll=new ScrollView(a);LinearLayout body=col();body.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(body);body.addView(title("规格组合"));String oldCny=str(old,"cny_price");EditText name=field(body,"规格名称 *",str(old,"name"),false),price=field(body,"价格（CNY） *",oldCny,false),stock=field(body,"库存 *",old==null?"0":str(old,"stock"),false);price.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);stock.setInputType(InputType.TYPE_CLASS_NUMBER);stock.setEnabled(!fixedStock);ArrayList<EditText> names=new ArrayList<>(),values=new ArrayList<>();JSONObject attrs=old==null?null:old.optJSONObject("attributes");ArrayList<String> keys=new ArrayList<>();if(attrs!=null){Iterator<String> iterator=attrs.keys();while(iterator.hasNext())keys.add(iterator.next());}for(int i=0;i<3;i++){names.add(field(body,"属性 "+(i+1)+" 名称（如颜色，选填）",i<keys.size()?keys.get(i):"",false));values.add(field(body,"属性 "+(i+1)+" 值（如黑色）",i<keys.size()?attrs.optString(keys.get(i)):"",false));}String[] photo={str(old,"image_url")};LinearLayout imageBox=col();body.addView(imageBox);if(!photo[0].isEmpty())image(imageBox,photo[0],120,false);action(body,"上传规格图片",()->pickImage("merchant.media.upload",url->{photo[0]=url;imageBox.removeAllViews();image(imageBox,url,120,false);}),false);action(body,"保存规格",()->{try{String n=name.getText().toString().trim(),cost=price.getText().toString().trim();if(n.isEmpty()||!cost.matches("(?:0|[1-9][0-9]{0,15})(?:[.][0-9]{1,8})?")||new java.math.BigDecimal(cost).signum()<=0)throw new Exception();int number=Integer.parseInt(stock.getText().toString());if(number<0||number>1000000)throw new Exception();JSONObject map=new JSONObject();for(int i=0;i<3;i++){String k=names.get(i).getText().toString().trim(),val=values.get(i).getText().toString().trim();if(k.isEmpty()&&val.isEmpty())continue;if(k.isEmpty()||val.isEmpty()||map.has(k))throw new Exception();map.put(k,val);}JSONObject sku=obj("id",old==null?"v"+UUID.randomUUID().toString().replace("-",""):str(old,"id"),"name",n,"price",cost,"cny_price",cost,"stock",number,"attributes",map,"image_url",photo[0],"enabled",true);done.done(sku);dialog.dismiss();}catch(Exception e){toast("请检查规格名称、正数价格、整数库存及属性配对");}},true);dialog.setContentView(scroll);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*.9f));}
    private void inventory(JSONObject p){JSONArray variants=p.optJSONArray("variants");if(variants==null||variants.length()==0)variants=new JSONArray().put(obj("id","default","name","标准规格","stock",p.optInt("stock")));final JSONArray options=variants;String[] labels=new String[options.length()];for(int i=0;i<labels.length;i++){JSONObject s=options.optJSONObject(i);labels[i]=str(s,"name")+" · 当前库存 "+s.optInt("stock");}new AlertDialog.Builder(a).setTitle("选择要调整的规格").setItems(labels,(d,n)->{JSONObject sku=options.optJSONObject(n);LinearLayout form=col();form.setPadding(dp(16),dp(12),dp(16),dp(12));EditText delta=field(form,"增加填正数，减少填负数","",false),reason=field(form,"调整原因（补货 / 盘点 / 退货验收等）","",false);delta.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED);AlertDialog edit=new AlertDialog.Builder(a).setTitle(str(sku,"name")).setView(form).setNegativeButton("返回",null).setPositiveButton("确认调整",null).create();edit.setOnShowListener(x->edit.getButton(-1).setOnClickListener(v->{try{post("merchant.inventory.adjust",obj("product_id",p.optInt("id"),"variant_id",str(sku,"id"),"inventory_version",p.optInt("inventory_version"),"delta",Integer.parseInt(delta.getText().toString()),"reason",reason.getText().toString().trim()),edit.getButton(-1),r->{edit.dismiss();replace(()->merchantProductList(1));});}catch(Exception e){toast("请输入整数增减数量");}}));edit.show();}).show();}
    private static final int MALL_SCAN=0x5A20;
    private String groupDigits(String code){
        if(code==null)return "";
        if(!code.matches("[0-9]{16}"))return code;
        return code.substring(0,4)+" "+code.substring(4,8)+" "+code.substring(8,12)+" "+code.substring(12);
    }
    private Bitmap qrBitmap(String content,int size){
        try{
            BitMatrix matrix=new MultiFormatWriter().encode(content,BarcodeFormat.QR_CODE,size,size);
            int[] pixels=new int[size*size];
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)pixels[y*size+x]=matrix.get(x,y)?0xFF101A2B:0xFFFFFFFF;
            return Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888);
        }catch(Exception e){return null;}
    }
    void merchantDashboard(){
        show(()->{
            final LinearLayout body=page("经营数据");
            get("merchant.dashboard",null,data->{
                if(!(data instanceof JSONObject)){action(body,"读取失败，点击重试",()->replace(this::merchantDashboard),false);return;}
                JSONObject d=(JSONObject)data;
                LinearLayout k1=card(body);k1.addView(title("今日成交"));
                k1.addView(text(d.optInt("today_orders")+" 单 · "+MallMoney.show(d.optString("today_amount"))+" SPX001",20,MallUi.PRIMARY));
                LinearLayout k2=card(body);k2.addView(title("待结算 / 已到账"));
                k2.addView(text("待结算 "+MallMoney.show(d.optString("pending_net"))+" SPX001（"+d.optInt("pending_count")+" 单）\n已到账 "+MallMoney.show(d.optString("settled_net"))+" SPX001（"+d.optInt("settled_count")+" 单）",15,MallUi.INK));
                LinearLayout k3=card(body);k3.addView(title("待处理"));
                k3.addView(text("待发货 "+d.optInt("pending_ship")+" · 待核销 "+d.optInt("pending_redeem")+" · 售后 "+d.optInt("after_sale"),15,MallUi.INK));
                LinearLayout quick=card(body);quick.addView(title("快捷入口"));
                action(quick,"扫码核销",this::scanRedeem,true);
                action(quick,"结算记录",this::merchantSettlements,false);
                action(quick,"核销记录",this::merchantRedemptions,false);
                action(quick,"商家订单",this::merchantOrders,false);
                JSONArray daily=d.optJSONArray("daily_7d");
                LinearLayout trend=card(body);trend.addView(title("近 7 天成交"));
                if(daily==null||daily.length()==0)trend.addView(text("暂无数据",13,MallUi.SECONDARY));
                else for(int i=0;i<daily.length();i++){
                    JSONObject row=daily.optJSONObject(i);if(row==null)continue;
                    trend.addView(text(str(row,"d")+"   "+row.optInt("orders")+" 单   "+MallMoney.show(row.optString("amount"))+" SPX001",14,MallUi.INK));
                }
            },error->toast(error));
        });
    }
    void merchantSettlements(){
        show(()->{
            final LinearLayout body=page("结算记录");
            get("merchant.settlements",null,data->{
                if(!(data instanceof JSONArray)){action(body,"读取失败，点击重试",()->replace(this::merchantSettlements),false);return;}
                JSONArray list=(JSONArray)data;
                if(list.length()==0)body.addView(text("还没有结算记录",14,MallUi.SECONDARY));
                for(int i=0;i<list.length();i++){
                    JSONObject r=list.optJSONObject(i);if(r==null)continue;
                    LinearLayout c=card(body);
                    String state=str(r,"state");
                    c.addView(text(("settled".equals(state)?"已到账":"待结算")+" · 订单 #"+r.optInt("order_id"),13,"settled".equals(state)?MallUi.SUCCESS:MallUi.WARN));
                    c.addView(text(str(r,"product_name"),16,MallUi.INK));
                    c.addView(text("商品金额 "+MallMoney.show(r.optString("gross"))+"\n手续费 "+MallMoney.show(r.optString("fee"))+"（"+new java.math.BigDecimal(r.optString("fee_rate","0")).multiply(new java.math.BigDecimal("100")).stripTrailingZeros().toPlainString()+"%）\n净额 "+MallMoney.show(r.optString("net"))+" SPX001",14,MallUi.INK));
                    c.addView(text(("settled".equals(state)?"到账时间 ":"预计结算 ")+str(r,"time"),12,MallUi.SECONDARY));
                }
            },error->toast(error));
        });
    }
    void merchantRedemptions(){
        show(()->{
            final LinearLayout body=page("核销记录");
            get("merchant.redemptions",null,data->{
                if(!(data instanceof JSONArray)){action(body,"读取失败，点击重试",()->replace(this::merchantRedemptions),false);return;}
                JSONArray list=(JSONArray)data;
                if(list.length()==0)body.addView(text("还没有核销记录",14,MallUi.SECONDARY));
                for(int i=0;i<list.length();i++){
                    JSONObject r=list.optJSONObject(i);if(r==null)continue;
                    LinearLayout c=card(body);
                    c.addView(text(str(r,"received_at")+" · 订单 #"+r.optInt("order_id"),12,MallUi.SECONDARY));
                    c.addView(title(str(r,"product_name")));
                    c.addView(text("核销码 "+groupDigits(str(r,"redeem_code"))+"\n买家 "+str(r,"buyer")+"\n金额 "+MallMoney.show(r.optString("total_spx"))+" SPX001",14,MallUi.INK));
                    c.addView(text("settled".equals(str(r,"settlement_status"))?"已结算":"待结算",12,"settled".equals(str(r,"settlement_status"))?MallUi.SUCCESS:MallUi.WARN));
                }
            },error->toast(error));
        });
    }
    private void scanRedeem(){
        try{a.startActivityForResult(new Intent(a,QrScanActivity.class),MALL_SCAN);}
        catch(Exception e){toast("无法打开扫码，请手动输入");}
    }
    private void confirmRedeem(String code){
        if(code==null||!code.matches("[0-9]{16}")){toast("这不是有效的 16 位核销码");return;}
        new AlertDialog.Builder(a).setTitle("确认核销").setMessage("核销码 "+groupDigits(code)+"\n核销后订单完成，次日自动结算。")
            .setNegativeButton("取消",null)
            .setPositiveButton("确认核销",(d,w)->post("merchant.order.redeem",obj("order_id",0,"redeem_code",code),null,
                r->{toast("核销成功，次日结算");replace(this::merchantRedemptions);}))
            .show();
    }
    void merchantOrders(){show(()->merchantOrderList(1));}
    private void merchantOrderList(int pageNo){LinearLayout body=page("商家订单与售后");get("merchant.orders",args("page",""+pageNo,"page_size","20"),data->{if(!(data instanceof JSONArray))return;JSONArray list=(JSONArray)data;for(int i=0;i<list.length();i++){JSONObject o=list.optJSONObject(i);LinearLayout c=card(body);c.addView(title("#"+o.optInt("id")+" · "+str(o,"product_name")));String buyer=str(o,"buyer_nickname");if(buyer.isEmpty())buyer=str(o,"buyer_username");if(!buyer.isEmpty())c.addView(text("买家："+buyer+(str(o,"buyer_username").isEmpty()||buyer.equals(str(o,"buyer_username"))?"":"（"+str(o,"buyer_username")+"）"),14,MallUi.INK));c.addView(text(status(o)+" · "+str(o,"variant_name")+" × "+o.optInt("quantity")+"\n"+MallMoney.show(str(o,"total_spx"))+" SPX001 · "+(str(o,"settlement_status").equals("settled")?"已结算":str(o,"settlement_status").equals("refunded")?"已退款":"待结算"),14,MallUi.INK));c.addView(text("结算手续费："+MallMoney.show(str(o,"settlement_fee"))+" SPX001\n"+(str(o,"settlement_status").equals("settled")?"已到账：":"预计净额（退款后不结算）：")+MallMoney.show(str(o,"merchant_net_amount").isEmpty()?str(o,"total_spx"):str(o,"merchant_net_amount"))+" SPX001",13,MallUi.SECONDARY));JSONObject address=o.optJSONObject("address");if(address!=null)c.addView(text(addressText(address),13,MallUi.SECONDARY));if(!str(o,"buyer_note").isEmpty())c.addView(text("买家留言："+str(o,"buyer_note"),14,MallUi.INK));if(!str(o,"tracking_number").isEmpty())c.addView(text("发货物流："+str(o,"carrier")+" "+str(o,"tracking_number"),13,MallUi.SECONDARY));if(!str(o,"fulfillment_note").isEmpty())c.addView(text("交付内容："+str(o,"fulfillment_note"),13,MallUi.SECONDARY));String af=str(o,"after_sale_status");boolean pending=af.equals("requested")||af.equals("return_approved")||af.equals("return_shipped");if(!af.equals("none")){c.addView(text("售后："+str(o,"after_sale_reason")+"\n"+str(o,"after_sale_reply"),14,MallUi.PRIMARY));if(!str(o,"return_tracking").isEmpty())c.addView(text("退货单号："+str(o,"return_carrier")+" "+str(o,"return_tracking"),13,MallUi.INK));}if(!str(o,"merchant_service_reply").isEmpty())c.addView(text("商家回复："+str(o,"merchant_service_reply"),14,MallUi.INK));if(pending){c.addView(text("售后处理中，暂停履约与结算。请及时回复买家，退款由平台审核。",13,MallUi.SECONDARY));action(c,"回复售后问题",()->replyAfterSale(o,pageNo),false);}else if(str(o,"status").equals("pending"))action(c,"处理订单",()->fulfill(o,pageNo),true);action(c,"查看订单详情",()->merchantOrderDetail(o.optInt("id")),false);}if(list.length()==0)body.addView(text("暂无订单",14,MallUi.SECONDARY));pagination(body,pageNo,list.length(),20,n->replace(()->merchantOrderList(n)));});}
    private void merchantOrderDetail(int id){
        LinearLayout body=page("订单详情");
        TextView loading=text("正在读取订单…",13,MallUi.SECONDARY);body.addView(loading);
        get("merchant.order.detail",args("id",""+id),data->{
            body.removeView(loading);
            if(!(data instanceof JSONObject)){action(body,"订单读取失败，点击重试",()->replace(()->merchantOrderDetail(id)),false);return;}
            JSONObject o=(JSONObject)data;
            LinearLayout c=card(body);
            c.addView(title("#"+o.optInt("id")+" · "+str(o,"product_name")));
            String buyer=str(o,"buyer_nickname");if(buyer.isEmpty())buyer=str(o,"buyer_username");
            if(!buyer.isEmpty())c.addView(text("买家："+buyer+(str(o,"buyer_username").isEmpty()||buyer.equals(str(o,"buyer_username"))?"":"（"+str(o,"buyer_username")+"）"),15,MallUi.INK));
            c.addView(text(status(o)+" · "+str(o,"variant_name")+" × "+o.optInt("quantity"),14,MallUi.INK));
            c.addView(text("商品金额 "+MallMoney.show(str(o,"total_spx"))+" SPX001",13,MallUi.SECONDARY));
            c.addView(text("结算手续费："+MallMoney.show(str(o,"settlement_fee"))+" SPX001\n"+(str(o,"settlement_status").equals("settled")?"已到账：":"预计净额（退款后不结算）：")+MallMoney.show(str(o,"merchant_net_amount").isEmpty()?str(o,"total_spx"):str(o,"merchant_net_amount"))+" SPX001",13,MallUi.SECONDARY));
            JSONObject address=o.optJSONObject("address");
            if(address!=null)c.addView(text("收货信息\n"+addressText(address),13,MallUi.SECONDARY));
            if(!str(o,"buyer_note").isEmpty())c.addView(text("买家留言："+str(o,"buyer_note"),13,MallUi.INK));
            if(!str(o,"carrier").isEmpty()||!str(o,"tracking_number").isEmpty())c.addView(text("物流："+str(o,"carrier")+" "+str(o,"tracking_number"),13,MallUi.INK));
            if(!str(o,"fulfillment_note").isEmpty())c.addView(text("交付内容："+str(o,"fulfillment_note"),13,MallUi.INK));
            if(!str(o,"after_sale_status").equals("none"))c.addView(text("售后："+str(o,"after_sale_reason")+"\n"+str(o,"after_sale_reply"),13,MallUi.PRIMARY));
            if(!str(o,"return_tracking").isEmpty())c.addView(text("退货单号："+str(o,"return_carrier")+" "+str(o,"return_tracking"),13,MallUi.INK));
            if(!str(o,"merchant_service_reply").isEmpty())c.addView(text("商家回复："+str(o,"merchant_service_reply"),13,MallUi.INK));
            JSONArray events=o.optJSONArray("events");
            if(events!=null&&events.length()>0){
                StringBuilder sb=new StringBuilder("订单进度");
                for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e==null)continue;sb.append("\n· ").append(str(e,"created_at")).append("  ").append(str(e,"message"));}
                c.addView(text(sb.toString(),12,MallUi.SECONDARY));
            }
            if(str(o,"status").equals("pending"))action(c,"去处理这个订单",()->fulfill(o,1),true);
        },error->{body.removeView(loading);body.addView(text(error,13,MallUi.SECONDARY));});
    }
    private void replyAfterSale(JSONObject order,int pageNo){
        LinearLayout form=col();form.setPadding(dp(16),dp(14),dp(16),dp(14));EditText reply=field(form,"给买家的处理说明（不超过 1000 字）",str(order,"merchant_service_reply"),true);reply.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)});
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("回复售后 #"+order.optInt("id")).setView(form).setNegativeButton("返回",null).setPositiveButton("发送回复",null).create();dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{String message=reply.getText().toString().trim();if(message.isEmpty()){reply.setError("请填写回复内容");return;}post("merchant.order.reply",obj("order_id",order.optInt("id"),"reply",message),dialog.getButton(-1),r->{dialog.dismiss();replace(()->merchantOrderList(pageNo));});}));dialog.show();
    }
    private void fulfill(JSONObject o,int pageNo){String type=str(o,"order_type");LinearLayout form=col();form.setPadding(dp(16),dp(12),dp(16),dp(12));EditText first=field(form,type.equals("physical")?"承运商":type.equals("local")?"买家出示的核销码":"交付内容 / 领取说明","",type.equals("digital"));EditText second=type.equals("physical")?field(form,"物流单号","",false):null;AlertDialog dialog=new AlertDialog.Builder(a).setTitle("处理订单 #"+o.optInt("id")).setView(form).setNegativeButton("返回",null).setPositiveButton("确认处理",null).create();dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{JSONObject req=obj("order_id",o.optInt("id"),type.equals("physical")?"carrier":type.equals("local")?"redeem_code":"fulfillment_note",first.getText().toString().trim());if(second!=null)try{req.put("tracking_number",second.getText().toString().trim());}catch(Exception ignored){}post(type.equals("physical")?"merchant.order.ship":type.equals("local")?"merchant.order.redeem":"merchant.order.deliver",req,dialog.getButton(-1),r->{dialog.dismiss();replace(()->merchantOrderList(pageNo));});}));dialog.show();}
}
