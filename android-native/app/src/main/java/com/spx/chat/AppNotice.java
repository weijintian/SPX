package com.spx.chat;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** One non-blocking notice, centered for two seconds. New notices replace old ones. */
final class AppNotice {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> current = new WeakReference<>(null);
    private static PopupWindow popup;
    private static final Runnable HIDE = AppNotice::dismiss;
    static void resume(Activity activity) { current = new WeakReference<>(activity); }
    static void stop(Activity activity) { if(current.get()==activity){dismiss();current.clear();} }
    private static void dismiss() {
        MAIN.removeCallbacks(HIDE);
        if(popup!=null){popup.dismiss();popup=null;}
    }
    static void show(Context context, String message) {
        if(message==null||message.trim().isEmpty())return;
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->show(context,message));return;}
        Context candidate=context;
        while(candidate instanceof ContextWrapper && !(candidate instanceof Activity)) {
            Context next=((ContextWrapper)candidate).getBaseContext();if(next==candidate)break;candidate=next;
        }
        Activity activity=candidate instanceof Activity?(Activity)candidate:current.get();
        if(activity==null||current.get()!=activity||activity.isFinishing()||activity.isDestroyed()||!activity.getWindow().getDecorView().isAttachedToWindow())return;
        dismiss();
        float density=activity.getResources().getDisplayMetrics().density;
        TextView text=new TextView(activity);text.setText(message);text.setTextSize(14);text.setTextColor(Color.WHITE);text.setGravity(Gravity.CENTER);
        text.setPadding((int)(24*density),(int)(15*density),(int)(24*density),(int)(15*density));
        text.setMaxWidth(Math.min((int)(320*density),activity.getResources().getDisplayMetrics().widthPixels-(int)(48*density)));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xec242830);bg.setCornerRadius(12*density);text.setBackground(bg);
        popup=new PopupWindow(text,ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,false);
        popup.setTouchable(false);popup.setOutsideTouchable(false);popup.setElevation(6*density);
        try{popup.showAtLocation(activity.getWindow().getDecorView(),Gravity.CENTER,0,0);MAIN.postDelayed(HIDE,2000);}
        catch(android.view.WindowManager.BadTokenException e){dismiss();}
    }
}
