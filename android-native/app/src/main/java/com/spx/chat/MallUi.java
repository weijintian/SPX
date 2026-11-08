package com.spx.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Shared presentation only; prices, eligibility and orders remain server controlled. */
final class MallUi {
    static final int BACKGROUND = Color.rgb(246, 248, 252);
    static final int INK = Color.rgb(24, 38, 60);
    static final int SECONDARY = Color.rgb(111, 125, 147);
    static final int PRIMARY = Color.rgb(40, 105, 234);
    static final int TINT = Color.rgb(237, 243, 255);
    static final int GOLD = Color.rgb(145, 95, 20);
    static final int GOLD_TINT = Color.rgb(255, 247, 226);
    static final int LINE = Color.rgb(226, 231, 241);
    static final int SUCCESS = Color.rgb(35, 139, 105);
    static final int WARN = Color.rgb(196, 106, 20);

    private MallUi() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int color, int radius, boolean border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (border) shape.setStroke(dp(context, 1), LINE);
        return shape;
    }

    static GradientDrawable primaryButton(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(PRIMARY);
        shape.setCornerRadius(dp(context, 14));
        return shape;
    }

    static void touch(View view, int radius) {
        view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x18355AEA), null, surface(view.getContext(), Color.WHITE, radius, false)));
        view.setFocusable(true);
    }

    static TextView badge(Context context, String label, boolean gold) {
        TextView badge = new TextView(context);
        badge.setText(label);
        badge.setTextSize(10);
        badge.setTextColor(gold ? GOLD : PRIMARY);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setIncludeFontPadding(false);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setPadding(dp(context, 7), dp(context, 4), dp(context, 7), dp(context, 4));
        badge.setBackground(surface(context, gold ? GOLD_TINT : TINT, 5, false));
        return badge;
    }

    static View icon(Context context, String name, int color) {
        return new Icon(context, name, color);
    }

    static final class RatioFrame extends FrameLayout {
        private final float ratio;
        RatioFrame(Context context, float ratio) {
            super(context);
            this.ratio = ratio;
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (width > 0) heightSpec = MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static final class Icon extends View {
        private final String name;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context context, String name, int color) {
            super(context);
            this.name = name;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
            float size = Math.min(contentWidth, contentHeight);
            canvas.save();
            canvas.translate(getPaddingLeft() + (contentWidth - size) / 2f, getPaddingTop() + (contentHeight - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            if ("search".equals(name)) {
                canvas.drawCircle(10.5f, 10.5f, 6.2f, paint);
                canvas.drawLine(15, 15, 20, 20, paint);
            } else if ("store".equals(name)) {
                canvas.drawRoundRect(new RectF(4, 10, 20, 21), 1.5f, 1.5f, paint);
                Path roof = new Path(); roof.moveTo(3, 10); roof.lineTo(5, 4); roof.lineTo(19, 4); roof.lineTo(21, 10); roof.close();
                canvas.drawPath(roof, paint);
                canvas.drawLine(8, 5, 7, 10, paint); canvas.drawLine(16, 5, 17, 10, paint);
                canvas.drawRect(10, 14, 15, 21, paint);
            } else if ("pin".equals(name)) {
                Path pin = new Path(); pin.moveTo(12, 22); pin.cubicTo(8, 17, 4, 13, 4, 9); pin.cubicTo(4, 0, 20, 0, 20, 9); pin.cubicTo(20, 13, 16, 17, 12, 22);
                canvas.drawPath(pin, paint); canvas.drawCircle(12, 9, 2.7f, paint);
            } else if ("order".equals(name)) {
                canvas.drawRoundRect(new RectF(5, 3, 19, 21), 2, 2, paint);
                canvas.drawLine(9, 8, 15, 8, paint); canvas.drawLine(9, 12, 15, 12, paint); canvas.drawLine(9, 16, 13, 16, paint);
            } else if ("gift".equals(name)) {
                canvas.drawRect(4, 11, 20, 21, paint); canvas.drawRoundRect(new RectF(3, 7, 21, 11), 1, 1, paint);
                canvas.drawLine(12, 7, 12, 21, paint);
                canvas.drawOval(new RectF(6, 2, 12, 7), paint); canvas.drawOval(new RectF(12, 2, 18, 7), paint);
            } else if ("grid".equals(name)) {
                for (int y = 4; y <= 14; y += 10) for (int x = 4; x <= 14; x += 10) canvas.drawRoundRect(new RectF(x, y, x + 6, y + 6), 1, 1, paint);
            } else if ("back".equals(name) || "chevron".equals(name)) {
                Path arrow = new Path();
                if ("back".equals(name)) { arrow.moveTo(15, 5); arrow.lineTo(8, 12); arrow.lineTo(15, 19); }
                else { arrow.moveTo(9, 7); arrow.lineTo(14, 12); arrow.lineTo(9, 17); }
                canvas.drawPath(arrow, paint);
            } else if ("share".equals(name)) {
                Path share = new Path(); share.moveTo(4, 12); share.lineTo(4, 21); share.lineTo(20, 21); share.lineTo(20, 12);
                canvas.drawPath(share, paint); canvas.drawLine(12, 3, 12, 15, paint);
                canvas.drawLine(7, 8, 12, 3, paint); canvas.drawLine(17, 8, 12, 3, paint);
            } else if ("chat".equals(name)) {
                canvas.drawRoundRect(new RectF(3, 4, 21, 18), 4, 4, paint);
                canvas.drawLine(7, 18, 5, 22, paint); canvas.drawLine(5, 22, 13, 18, paint);
                for (int x = 7; x <= 17; x += 5) canvas.drawCircle(x, 11, .7f, paint);
            } else if ("people".equals(name)) {
                canvas.drawCircle(9, 7, 4, paint); canvas.drawArc(new RectF(2, 12, 16, 27), 180, 180, false, paint);
                canvas.drawArc(new RectF(14, 3, 22, 11), 270, 180, false, paint);
                canvas.drawArc(new RectF(14, 13, 24, 27), 270, 90, false, paint);
            } else if ("bot".equals(name)) {
                canvas.drawRoundRect(new RectF(4, 7, 20, 20), 4, 4, paint);
                canvas.drawLine(12, 3, 12, 7, paint); canvas.drawCircle(8, 13, 1, paint); canvas.drawCircle(16, 13, 1, paint);
                canvas.drawLine(1, 11, 1, 16, paint); canvas.drawLine(23, 11, 23, 16, paint);
            } else if ("notice".equals(name)) {
                Path notice = new Path(); notice.moveTo(3, 9); notice.lineTo(9, 9); notice.lineTo(20, 4); notice.lineTo(20, 20); notice.lineTo(9, 15); notice.lineTo(3, 15); notice.close();
                canvas.drawPath(notice, paint); canvas.drawLine(7, 15, 9, 21, paint);
            } else if ("image".equals(name)) {
                canvas.drawRoundRect(new RectF(3, 3, 21, 21), 3, 3, paint);
                canvas.drawCircle(8, 8, 1.5f, paint);
                Path hills = new Path(); hills.moveTo(4, 18); hills.lineTo(10, 12); hills.lineTo(14, 16); hills.lineTo(17, 12); hills.lineTo(21, 16);
                canvas.drawPath(hills, paint);
            } else if ("edit".equals(name)) {
                Path pen = new Path(); pen.moveTo(5, 15); pen.lineTo(16, 4); pen.lineTo(20, 8); pen.lineTo(9, 19); pen.lineTo(4, 20); pen.close();
                canvas.drawPath(pen, paint); canvas.drawLine(14, 6, 18, 10, paint);
            } else if ("bell".equals(name)) {
                Path bell = new Path(); bell.moveTo(4, 17); bell.lineTo(6, 14); bell.lineTo(6, 9); bell.cubicTo(6, 1, 18, 1, 18, 9); bell.lineTo(18, 14); bell.lineTo(20, 17); bell.close();
                canvas.drawPath(bell, paint); canvas.drawArc(new RectF(9, 17, 15, 22), 0, 180, false, paint);
            } else if ("shield".equals(name)) {
                Path shield = new Path(); shield.moveTo(12, 2); shield.lineTo(21, 6); shield.lineTo(20, 14); shield.quadTo(18, 19, 12, 22); shield.quadTo(6, 19, 4, 14); shield.lineTo(3, 6); shield.close();
                canvas.drawPath(shield, paint); canvas.drawLine(8, 12, 11, 15, paint); canvas.drawLine(11, 15, 16, 9, paint);
            } else if ("exit".equals(name)) {
                Path door = new Path(); door.moveTo(10, 3); door.lineTo(4, 3); door.lineTo(4, 21); door.lineTo(10, 21);
                canvas.drawPath(door, paint); canvas.drawLine(10, 12, 21, 12, paint); canvas.drawLine(17, 8, 21, 12, paint); canvas.drawLine(17, 16, 21, 12, paint);
            } else if ("arrow".equals(name)) {
                canvas.drawLine(5, 12, 19, 12, paint); canvas.drawLine(14, 7, 19, 12, paint); canvas.drawLine(14, 17, 19, 12, paint);
            } else {
                canvas.drawRoundRect(new RectF(4, 7, 20, 21), 2, 2, paint);
                canvas.drawArc(new RectF(8, 2, 16, 12), 180, 180, false, paint);
            }
            canvas.restore();
        }
    }
}
