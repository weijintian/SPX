package com.spx.chat;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Resolution independent navigation symbols, with the same stroke on every screen. */
final class AppNavIcon extends Drawable {
    private final int kind;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    AppNavIcon(int kind, int size, int color) {
        this.kind = kind; this.size = size;
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    void color(int color) { paint.setColor(color); invalidateSelf(); }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void draw(Canvas c) {
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.scale(getBounds().width()/24f, getBounds().height()/24f);
        if (kind == 0) {
            Path p = new Path();
            p.moveTo(5,4);p.lineTo(19,4);p.quadTo(21,4,21,6);p.lineTo(21,16);
            p.quadTo(21,18,19,18);p.lineTo(10,18);p.lineTo(4,21);p.lineTo(4,18);
            p.quadTo(3,18,3,16);p.lineTo(3,6);p.quadTo(3,4,5,4);p.close();c.drawPath(p,paint);
            c.drawLine(7,9,17,9,paint);c.drawLine(7,13,14,13,paint);
        } else if (kind == 1) {
            c.drawCircle(9,7,3,paint);c.drawArc(new RectF(3,12,15,23),180,180,false,paint);
            c.drawArc(new RectF(15,4,21,10),270,180,false,paint);
            c.drawArc(new RectF(13,12,23,22),270,90,false,paint);
        } else if (kind == 2) {
            c.drawCircle(12,12,9,paint);
            Path p=new Path();p.moveTo(15,8);p.lineTo(13,14);p.lineTo(8,16);p.lineTo(10,10);p.close();c.drawPath(p,paint);
        } else {
            c.drawCircle(12,7,3.5f,paint);
            c.drawRoundRect(new RectF(4,14,20,22),4,4,paint);
        }
        c.restore();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
