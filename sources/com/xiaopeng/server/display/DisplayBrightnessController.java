package com.xiaopeng.server.display;

import android.animation.ValueAnimator;
import android.app.UiModeManager;
import android.content.Context;
import android.os.SystemProperties;
import android.view.animation.Interpolator;

/* loaded from: classes2.dex */
public class DisplayBrightnessController {
    public static final double FRAME_MILLIS = 0.016d;
    public static final int RATE = 100;
    private static final String TAG = "DisplayBrightnessController";
    private static final String PROP_BRIGHTNESS_ANIMATOR_FACTOR = "persist.sys.xp.brightness.animator.factor";
    public static final int FACTOR = SystemProperties.getInt(PROP_BRIGHTNESS_ANIMATOR_FACTOR, 2);

    public static final boolean useOverrideAnimator(Context context) {
        try {
            UiModeManager um = (UiModeManager) context.getSystemService("uimode");
            boolean isThemeWorking = um.isThemeWorking();
            if (isThemeWorking) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static int getSmoothBrightness(int from, int to, float percent) {
        return getInterpolation(from, to, percent);
    }

    public static int getInterpolation(int from, int to, float percent) {
        Interpolator interpolator;
        int direction;
        float delta = Math.abs(to - from);
        int factor = FACTOR;
        float percent2 = Math.max(Math.min(percent, 1.0f), 0.0f);
        if (from < to) {
            interpolator = new AccelerateInterpolator(factor);
            direction = 1;
        } else {
            interpolator = new DecelerateInterpolator(factor);
            direction = -1;
        }
        float value = interpolator.getInterpolation(percent2);
        int range = (int) (delta * value);
        int target = (direction * range) + from;
        return target;
    }

    /* loaded from: classes2.dex */
    public static class AccelerateInterpolator implements Interpolator {
        private final double mDoubleFactor;
        private final float mFactor;

        public AccelerateInterpolator() {
            this.mFactor = 1.0f;
            this.mDoubleFactor = 2.0d;
        }

        public AccelerateInterpolator(float factor) {
            this.mFactor = factor;
            this.mDoubleFactor = this.mFactor * 2.0f;
        }

        @Override // android.animation.TimeInterpolator
        public float getInterpolation(float input) {
            if (this.mFactor == 1.0f) {
                return input * input;
            }
            return (float) Math.pow(input, this.mDoubleFactor);
        }
    }

    /* loaded from: classes2.dex */
    public static class DecelerateInterpolator implements Interpolator {
        private double mDoubleFactor;
        private float mFactor;

        public DecelerateInterpolator() {
            this.mFactor = 1.0f;
        }

        public DecelerateInterpolator(float factor) {
            this.mFactor = 1.0f;
            this.mFactor = factor;
            this.mDoubleFactor = this.mFactor * 2.0f;
        }

        @Override // android.animation.TimeInterpolator
        public float getInterpolation(float input) {
            if (this.mFactor == 1.0f) {
                float result = 1.0f - ((1.0f - input) * (1.0f - input));
                return result;
            }
            float result2 = (float) (1.0d - Math.pow(1.0f - input, this.mDoubleFactor));
            return result2;
        }
    }

    /* loaded from: classes2.dex */
    public static class RampAnimatorData {
        public int current;
        public int frame;
        public int frames;
        public int from;
        public boolean overrideAnimator = false;
        public int rate;
        public int to;

        public void set(int from, int to, int rate) {
            this.from = from;
            this.to = to;
            this.rate = 0;
            float delta = Math.abs(to - from);
            float scale = ValueAnimator.getDurationScale();
            double amount = (rate * 0.016d) / scale;
            this.frames = (int) Math.ceil(delta / amount);
        }

        public void reset() {
            this.from = 0;
            this.to = 0;
            this.current = 0;
            this.rate = 0;
            this.frame = 0;
            this.frames = 0;
            this.overrideAnimator = false;
        }
    }
}
