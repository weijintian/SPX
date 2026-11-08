package com.spx.chat;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Activity decor overlay: no system overlay permission, no focus theft from chat input. */
final class CallMiniWindow {
    private final Activity host;
    private final Runnable restore,end;
    private final ViewGroup decor;
    private FrameLayout box,preview;
    private View previewHandle;
    private TextView status,name;
    private int x=-1,y=-1;
    private boolean video;
    private final Rect bounds=new Rect();
    private final ViewTreeObserver.OnGlobalLayoutListener resized=()->position(false);
    CallMiniWindow(Activity host,Runnable restore,Runnable end){this.host=host;this.restore=restore;this.end=end;decor=(ViewGroup)host.getWindow().getDecorView();}
    private int dp(int n){return Math.round(n*host.getResources().getDisplayMetrics().density);}
    private TextView text(int size){TextView t=new TextView(host);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);return t;}
    void show(boolean isVideo,String title,String state,View media){
        if(box==null){
            video=isVideo;box=new FrameLayout(host);box.setElevation(dp(24));
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xff213b38);bg.setCornerRadius(dp(16));bg.setStroke(dp(1),0xff42615b);box.setBackground(bg);box.setClipToOutline(true);
            LinearLayout stack=new LinearLayout(host);stack.setOrientation(LinearLayout.VERTICAL);box.addView(stack,new FrameLayout.LayoutParams(-1,-1));
            preview=new FrameLayout(host);stack.addView(preview,new LinearLayout.LayoutParams(-1,dp(video?116:38)));
            if(!video){ImageView icon=new ImageView(host);icon.setImageDrawable(new ChatControlIcon("phone",0xff5ce2b0));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER);preview.addView(icon,p);}
            name=text(12);name.setPadding(dp(7),0,dp(7),0);stack.addView(name,new LinearLayout.LayoutParams(-1,dp(23)));
            LinearLayout actions=new LinearLayout(host);actions.setGravity(Gravity.CENTER_VERTICAL);
            status=text(12);status.setTextColor(0xff7ef0bd);actions.addView(status,new LinearLayout.LayoutParams(0,dp(38),1));
            ImageView hangup=new ImageView(host);hangup.setImageDrawable(new ChatControlIcon("hangup",0xffff7781));hangup.setPadding(dp(9),dp(9),dp(9),dp(9));hangup.setContentDescription("挂断通话");actions.addView(hangup,new LinearLayout.LayoutParams(dp(36),dp(38)));hangup.setOnClickListener(v->end.run());
            stack.addView(actions,new LinearLayout.LayoutParams(-1,dp(38)));
            View.OnTouchListener drag=new View.OnTouchListener(){float downX,downY;int startX,startY;boolean moved;
                public boolean onTouch(View v,MotionEvent event){
                    switch(event.getActionMasked()){
                        case MotionEvent.ACTION_DOWN:downX=event.getRawX();downY=event.getRawY();startX=x;startY=y;moved=false;return true;
                        case MotionEvent.ACTION_MOVE:float dx=event.getRawX()-downX,dy=event.getRawY()-downY;int slop=ViewConfiguration.get(host).getScaledTouchSlop();if(Math.abs(dx)>slop||Math.abs(dy)>slop)moved=true;if(moved){x=startX+Math.round(dx);y=startY+Math.round(dy);position(false);}return true;
                        case MotionEvent.ACTION_UP:if(moved)position(true);else v.performClick();return true;
                        case MotionEvent.ACTION_CANCEL:position(true);return true;
                    }return false;
                }};
            for(View v:new View[]{box,preview,name,status}){v.setOnTouchListener(drag);v.setOnClickListener(w->restore.run());v.setContentDescription("通话小窗，拖动移动，点击恢复");}
            previewHandle=new View(host);previewHandle.setOnTouchListener(drag);previewHandle.setOnClickListener(v->restore.run());previewHandle.setContentDescription("通话画面，拖动移动，点击恢复");preview.addView(previewHandle,new FrameLayout.LayoutParams(-1,-1));
            decor.addView(box,new FrameLayout.LayoutParams(dp(136),dp(video?177:99),Gravity.TOP|Gravity.LEFT));
            decor.getViewTreeObserver().addOnGlobalLayoutListener(resized);
        }
        if(media!=null&&media.getParent()!=preview){if(media.getParent() instanceof ViewGroup)((ViewGroup)media.getParent()).removeView(media);preview.addView(media,new FrameLayout.LayoutParams(-1,-1));previewHandle.bringToFront();}
        update(title,state);box.bringToFront();box.post(()->position(false));
    }
    void update(String title,String state){if(box!=null){name.setText(title);status.setText(state);}}
    private void position(boolean snap){
        if(box==null||decor.getWidth()==0)return;decor.getWindowVisibleDisplayFrame(bounds);int[] origin=new int[2];decor.getLocationOnScreen(origin);bounds.offset(-origin[0],-origin[1]);
        int left=Math.max(0,bounds.left)+dp(8),right=Math.min(decor.getWidth(),bounds.right)-dp(8);
        int top=Math.max(0,bounds.top)+dp(8),bottom=Math.min(decor.getHeight(),bounds.bottom)-dp(8);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)box.getLayoutParams();
        if(x<0)x=right-p.width;if(y<0)y=top+dp(70);
        x=CallFloatBounds.clamp(x,left,right-p.width);y=CallFloatBounds.clamp(y,top,bottom-p.height);
        if(snap)x=CallFloatBounds.edge(x,p.width,left,right);
        if(p.leftMargin!=x||p.topMargin!=y){p.leftMargin=x;p.topMargin=y;box.setLayoutParams(p);}
    }
    void dismiss(){
        if(box==null)return;
        if(decor.getViewTreeObserver().isAlive())decor.getViewTreeObserver().removeOnGlobalLayoutListener(resized);
        if(preview!=null)preview.removeAllViews();decor.removeView(box);box=null;preview=null;name=null;status=null;
    }
}
