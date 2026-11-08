package com.spx.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Two-line call receipt. The caller supplies an action only for trusted call records. */
final class ChatCallBubble extends LinearLayout {
    ChatCallBubble(Context context, boolean mine, int media, boolean completed,
                   String title, String subtitle, int blue) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(15), dp(14), dp(15), dp(14));
        setMinimumHeight(dp(74));
        setFocusable(true);
        setClickable(true);
        setContentDescription(title + "，" + subtitle);
        int ink = mine ? Color.WHITE : 0xff20283f;
        GradientDrawable surface = shape(mine ? blue : Color.WHITE, 19);
        float large = dp(19), small = dp(6);
        surface.setCornerRadii(new float[]{large, large, large, large,
                mine ? small : large, mine ? small : large,
                mine ? large : small, mine ? large : small});
        if (!mine) surface.setStroke(Math.max(1, dp(.75f)), 0xffe9edf5);
        GradientDrawable mask = (GradientDrawable) surface.getConstantState().newDrawable().mutate();
        mask.setColor(Color.WHITE);
        setBackground(new RippleDrawable(ColorStateList.valueOf(mine ? 0x25ffffff : 0x123458ed), surface, mask));

        FrameLayout iconBox = new FrameLayout(context);
        iconBox.setBackground(shape(mine ? 0x20ffffff : 0xffeef2ff, 14));
        ImageView icon = new ImageView(context);
        String symbol = media == 2 ? (completed ? "video" : "cameraoff") : (completed ? "phone" : "phoneoff");
        icon.setImageDrawable(new ChatControlIcon(symbol, mine ? Color.WHITE : 0xff6174c9));
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        addView(iconBox, new LayoutParams(dp(41), dp(41)));

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(VERTICAL);
        TextView heading = label(title, 16, ink);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(heading, new LayoutParams(-1, -2));
        TextView hint = label(subtitle, 11, mine ? 0xe6ffffff : 0xff7c879e);
        hint.setMaxLines(2);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams hintParams = new LayoutParams(-1, -2);
        hintParams.topMargin = dp(5);
        copy.addView(hint, hintParams);
        LayoutParams copyParams = new LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(12);
        addView(copy, copyParams);

        ImageView arrow = new ImageView(context);
        arrow.setImageDrawable(new ChatControlIcon("chevronright", mine ? 0x99ffffff : 0xffb0b9cb));
        LayoutParams arrowParams = new LayoutParams(dp(12), dp(14));
        arrowParams.leftMargin = dp(5);
        addView(arrow, arrowParams);
        for (View child : new View[]{iconBox, icon, copy, heading, hint, arrow})
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = dp(248);
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED)
            width = Math.min(width, MeasureSpec.getSize(widthSpec));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
