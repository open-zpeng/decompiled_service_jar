package com.android.server;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/* loaded from: classes.dex */
public class ThemeAlphaDrawable extends Drawable {
    private static final String TAG = "ThemeAlphaDrawable";
    private BitmapDrawable mDrawable;
    private Paint mPaint;
    private int mAlpha = 255;
    private final Rect mRect = new Rect();

    public ThemeAlphaDrawable(BitmapDrawable drawable) {
        init(drawable);
    }

    private void init(BitmapDrawable drawable) {
        this.mDrawable = drawable;
        this.mPaint = new Paint();
        this.mPaint.setAntiAlias(false);
        this.mPaint.setStyle(Paint.Style.FILL);
        BitmapDrawable bitmapDrawable = this.mDrawable;
        if (bitmapDrawable != null && bitmapDrawable.getBitmap() != null) {
            setBounds(0, 0, this.mDrawable.getBitmap().getWidth(), this.mDrawable.getBitmap().getHeight());
        }
    }

    public void startAnimation(long durationMillis, int alphaFrom, int alphaTo) {
        ValueAnimator valueAnimator = ValueAnimator.ofInt(alphaFrom, alphaTo);
        valueAnimator.setDuration(durationMillis);
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.android.server.-$$Lambda$ThemeAlphaDrawable$KGgp7x7I9xbtTSisD7pE08wDcxw
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator2) {
                ThemeAlphaDrawable.this.lambda$startAnimation$0$ThemeAlphaDrawable(valueAnimator2);
            }
        });
        valueAnimator.start();
    }

    public /* synthetic */ void lambda$startAnimation$0$ThemeAlphaDrawable(ValueAnimator animation) {
        int alpha = ((Integer) animation.getAnimatedValue()).intValue();
        this.mAlpha = alpha;
        invalidateSelf();
    }

    @Override // android.graphics.drawable.Drawable
    public void draw(Canvas canvas) {
        BitmapDrawable bitmapDrawable;
        if (this.mAlpha > 0 && (bitmapDrawable = this.mDrawable) != null && bitmapDrawable.getBitmap() != null) {
            this.mPaint.setAlpha(this.mAlpha);
            this.mRect.set(0, 0, getIntrinsicWidth(), getIntrinsicHeight());
            canvas.drawBitmap(this.mDrawable.getBitmap(), (Rect) null, this.mRect, this.mPaint);
        }
    }

    @Override // android.graphics.drawable.Drawable
    public void setAlpha(int i) {
        this.mPaint.setAlpha(i);
    }

    @Override // android.graphics.drawable.Drawable
    public void setColorFilter(ColorFilter colorFilter) {
        this.mPaint.setColorFilter(colorFilter);
    }

    @Override // android.graphics.drawable.Drawable
    public int getOpacity() {
        return -1;
    }

    @Override // android.graphics.drawable.Drawable
    public int getIntrinsicWidth() {
        return getBounds().right - getBounds().left;
    }

    @Override // android.graphics.drawable.Drawable
    public int getIntrinsicHeight() {
        return getBounds().bottom - getBounds().top;
    }
}
