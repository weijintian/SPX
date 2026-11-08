package com.spx.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 录音浮层里的实时音量波形：每来一个采样就从右往左推进一根竖条。
 * 采样值来自 MediaRecorder.getMaxAmplitude()（0~32767）。
 */
public class VoiceWaveView extends View {
    private static final int BARS = 26;
    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean cancelled = false;

    public VoiceWaveView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.FILL);
    }

    /** 推入一次采样（0~32767）。 */
    public void push(int amplitude) {
        float level = Math.max(0f, Math.min(1f, amplitude / 26000f));
        // 轻微平滑，避免条子跳动过猛
        float smoothed = (float) Math.sqrt(level);
        System.arraycopy(levels, 1, levels, 0, BARS - 1);
        levels[BARS - 1] = smoothed;
        invalidate();
    }

    public void clearLevels() {
        for (int i = 0; i < BARS; i++) levels[i] = 0f;
        invalidate();
    }

    public void setCancelled(boolean value) {
        if (cancelled == value) return;
        cancelled = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        float gap = Math.max(2f, width * 0.012f);
        float barWidth = (width - gap * (BARS - 1)) / BARS;
        float radius = barWidth / 2f;
        int color = cancelled ? Color.rgb(255, 92, 92) : Color.WHITE;
        for (int i = 0; i < BARS; i++) {
            float level = Math.max(0.06f, levels[i]);
            float barHeight = Math.max(barWidth, height * level);
            float left = i * (barWidth + gap);
            float top = (height - barHeight) / 2f;
            rect.set(left, top, left + barWidth, top + barHeight);
            int alpha = 110 + (int) (145 * Math.min(1f, level * 1.6f));
            paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
