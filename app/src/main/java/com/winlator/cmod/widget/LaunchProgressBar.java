package com.winlator.cmod.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public class LaunchProgressBar extends View {
    private static final long CYCLE_MS = 1400;
    private static final float SEGMENT_RATIO = 0.35f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean running;
    private long startTime;

    public LaunchProgressBar(Context context) {
        this(context, null);
    }

    public LaunchProgressBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LaunchProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        segmentPaint.setColor(0xFF0288D1);
        trackPaint.setColor(0x330288D1);
    }

    public void setBarColor(int color) {
        segmentPaint.setColor(color);
        trackPaint.setColor((color & 0x00FFFFFF) | 0x33000000);
        invalidate();
    }

    private void startAnimation() {
        if (running) return;
        running = true;
        startTime = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    private void stopAnimation() {
        running = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) startAnimation();
        else stopAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        canvas.drawRect(0, 0, width, height, trackPaint);

        float progress = ((SystemClock.uptimeMillis() - startTime) % CYCLE_MS) / (float)CYCLE_MS;
        float eased = progress * progress * (3f - 2f * progress);
        float segmentWidth = width * SEGMENT_RATIO;
        float left = -segmentWidth + eased * (width + segmentWidth);

        canvas.save();
        canvas.clipRect(0, 0, width, height);
        rect.set(left, 0, left + segmentWidth, height);
        canvas.drawRect(rect, segmentPaint);
        canvas.restore();

        if (running) postInvalidateOnAnimation();
    }
}
