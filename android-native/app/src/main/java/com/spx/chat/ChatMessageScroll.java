package com.spx.chat;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/** Resolve the latest-message position during layout, before the first visible frame. */
final class ChatMessageScroll extends ScrollView {
    private boolean contentReady, followBottom = true, bottomRequested, layingOut, touching;
    Runnable backgroundTap;
    private float tapX,tapY;
    private long tapTime;
    private boolean blankTap;

    private boolean interactiveAt(View view,float x,float y) {
        if(view.getVisibility()!=VISIBLE)return false;
        if(view!=this&&view.isEnabled()&&(view.isClickable()||view.isLongClickable()||view instanceof android.widget.EditText))return true;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;
            for(int i=group.getChildCount()-1;i>=0;i--){View child=group.getChildAt(i);float cx=x+group.getScrollX()-child.getLeft(),cy=y+group.getScrollY()-child.getTop();
                if(cx>=0&&cy>=0&&cx<child.getWidth()&&cy<child.getHeight()&&interactiveAt(child,cx,cy))return true;
            }
        }
        return false;
    }

    ChatMessageScroll(Context context) {
        super(context);
        setVisibility(INVISIBLE);
        setSmoothScrollingEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(false);
        setHorizontalFadingEdgeEnabled(false);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(true);
        setClipChildren(true);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    void completeInitialLoad(boolean latest) {
        contentReady = true;
        followBottom = latest;
        bottomRequested = latest;
        requestLayout();
    }

    void requestBottom() {
        followBottom = true;
        bottomRequested = true;
        requestLayout();
    }

    void stopFollowing() {
        followBottom = false;
        bottomRequested = false;
    }

    int bottomDistance() {
        if (getChildCount() == 0) return 0;
        return Math.max(0, getChildAt(0).getHeight() -
                (getHeight() - getPaddingTop() - getPaddingBottom())) - getScrollY();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layingOut = true;
        try {
            super.onLayout(changed, l, t, r, b);
            if (contentReady && !touching && (followBottom || bottomRequested)) {
                int range = getChildCount() == 0 ? 0 : Math.max(0, getChildAt(0).getHeight() -
                        (getHeight() - getPaddingTop() - getPaddingBottom()));
                scrollTo(0, range);
                bottomRequested = false;
            }
            if (contentReady && getVisibility() != VISIBLE) setVisibility(VISIBLE);
        } finally {
            layingOut = false;
        }
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        if (!layingOut && !bottomRequested)
            followBottom = bottomDistance() <= Math.round(80 * getResources().getDisplayMetrics().density);
        super.onScrollChanged(x, y, oldX, oldY);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tapX=event.getX();tapY=event.getY();tapTime=event.getEventTime();blankTap=!interactiveAt(this,tapX,tapY);
            touching = true;
            bottomRequested = false;
        }
        int slop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if(event.getPointerCount()>1||Math.abs(event.getX()-tapX)>slop||Math.abs(event.getY()-tapY)>slop||action==MotionEvent.ACTION_CANCEL)blankTap=false;
        boolean handled = super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP&&blankTap&&event.getEventTime()-tapTime<android.view.ViewConfiguration.getLongPressTimeout()&&backgroundTap!=null)backgroundTap.run();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            followBottom = bottomDistance() <= Math.round(80 * getResources().getDisplayMetrics().density);
        }
        return handled;
    }
}
