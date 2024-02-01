package com.android.server.display;

import android.animation.ValueAnimator;
import android.util.IntProperty;
import android.view.Choreographer;
import com.xiaopeng.server.display.DisplayBrightnessController;

/* loaded from: classes.dex */
final class RampAnimator<T> {
    private float mAnimatedValue;
    private boolean mAnimating;
    private int mCurrentValue;
    private long mLastFrameTimeNanos;
    private Listener mListener;
    private final T mObject;
    private final IntProperty<T> mProperty;
    private int mRate;
    private int mTargetValue;
    private boolean mFirstTime = true;
    private DisplayBrightnessController.RampAnimatorData mData = new DisplayBrightnessController.RampAnimatorData();
    private final Runnable mAnimationCallback = new Runnable() { // from class: com.android.server.display.RampAnimator.1
        /* JADX WARN: Multi-variable type inference failed */
        @Override // java.lang.Runnable
        public void run() {
            RampAnimator rampAnimator;
            RampAnimator rampAnimator2;
            RampAnimator rampAnimator3;
            long frameTimeNanos = RampAnimator.this.mChoreographer.getFrameTimeNanos();
            float timeDelta = ((float) (frameTimeNanos - RampAnimator.this.mLastFrameTimeNanos)) * 1.0E-9f;
            RampAnimator.this.mLastFrameTimeNanos = frameTimeNanos;
            float scale = ValueAnimator.getDurationScale();
            if (scale != 0.0f) {
                float amount = (RampAnimator.this.mRate * timeDelta) / scale;
                if (RampAnimator.this.mTargetValue > RampAnimator.this.mCurrentValue) {
                    RampAnimator rampAnimator4 = RampAnimator.this;
                    rampAnimator4.mAnimatedValue = Math.min(rampAnimator4.mAnimatedValue + amount, RampAnimator.this.mTargetValue);
                } else {
                    RampAnimator rampAnimator5 = RampAnimator.this;
                    rampAnimator5.mAnimatedValue = Math.max(rampAnimator5.mAnimatedValue - amount, RampAnimator.this.mTargetValue);
                }
                if (RampAnimator.this.mData.overrideAnimator) {
                    float percent = RampAnimator.this.mData.frames > 0 ? RampAnimator.this.mData.frame / RampAnimator.this.mData.frames : 1.0f;
                    RampAnimator.this.mAnimatedValue = DisplayBrightnessController.getInterpolation(rampAnimator.mData.from, RampAnimator.this.mData.to, percent);
                    RampAnimator.this.mData.frame++;
                    if (percent > 1.0d) {
                        RampAnimator.this.mAnimatedValue = rampAnimator2.mTargetValue;
                    }
                }
            } else {
                RampAnimator.this.mAnimatedValue = rampAnimator3.mTargetValue;
            }
            int oldCurrentValue = RampAnimator.this.mCurrentValue;
            RampAnimator rampAnimator6 = RampAnimator.this;
            rampAnimator6.mCurrentValue = Math.round(rampAnimator6.mAnimatedValue);
            if (oldCurrentValue != RampAnimator.this.mCurrentValue) {
                RampAnimator.this.mProperty.setValue(RampAnimator.this.mObject, RampAnimator.this.mCurrentValue);
            }
            if (RampAnimator.this.mTargetValue != RampAnimator.this.mCurrentValue) {
                RampAnimator.this.postAnimationCallback();
                return;
            }
            RampAnimator.this.mAnimating = false;
            if (RampAnimator.this.mListener != null) {
                RampAnimator.this.mListener.onAnimationEnd();
            }
            RampAnimator.this.mData.reset();
        }
    };
    private final Choreographer mChoreographer = Choreographer.getInstance();

    /* loaded from: classes.dex */
    public interface Listener {
        void onAnimationEnd();
    }

    public RampAnimator(T object, IntProperty<T> property) {
        this.mObject = object;
        this.mProperty = property;
    }

    /* JADX WARN: Code restructure failed: missing block: B:16:0x001f, code lost:
        if (r3 <= r5) goto L25;
     */
    /* JADX WARN: Removed duplicated region for block: B:20:0x0027  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean animateTo(int r5, int r6) {
        /*
            r4 = this;
            boolean r0 = r4.mFirstTime
            r1 = 1
            r2 = 0
            if (r0 != 0) goto L4b
            if (r6 > 0) goto L9
            goto L4b
        L9:
            boolean r0 = r4.mAnimating
            if (r0 == 0) goto L21
            int r0 = r4.mRate
            if (r6 > r0) goto L21
            int r0 = r4.mCurrentValue
            if (r5 > r0) goto L19
            int r3 = r4.mTargetValue
            if (r0 <= r3) goto L21
        L19:
            int r0 = r4.mTargetValue
            int r3 = r4.mCurrentValue
            if (r0 > r3) goto L23
            if (r3 > r5) goto L23
        L21:
            r4.mRate = r6
        L23:
            int r0 = r4.mTargetValue
            if (r0 == r5) goto L28
            r2 = r1
        L28:
            r0 = r2
            r4.mTargetValue = r5
            boolean r2 = r4.mAnimating
            if (r2 != 0) goto L4a
            int r2 = r4.mCurrentValue
            if (r5 == r2) goto L4a
            r4.mAnimating = r1
            float r1 = (float) r2
            r4.mAnimatedValue = r1
            long r1 = java.lang.System.nanoTime()
            r4.mLastFrameTimeNanos = r1
            com.xiaopeng.server.display.DisplayBrightnessController$RampAnimatorData r1 = r4.mData
            int r2 = r4.mCurrentValue
            int r3 = r4.mRate
            r1.set(r2, r5, r3)
            r4.postAnimationCallback()
        L4a:
            return r0
        L4b:
            boolean r0 = r4.mFirstTime
            if (r0 != 0) goto L55
            int r0 = r4.mCurrentValue
            if (r5 == r0) goto L54
            goto L55
        L54:
            return r2
        L55:
            r4.mFirstTime = r2
            r4.mRate = r2
            r4.mTargetValue = r5
            r4.mCurrentValue = r5
            android.util.IntProperty<T> r0 = r4.mProperty
            T r3 = r4.mObject
            r0.setValue(r3, r5)
            boolean r0 = r4.mAnimating
            if (r0 == 0) goto L6d
            r4.mAnimating = r2
            r4.cancelAnimationCallback()
        L6d:
            com.android.server.display.RampAnimator$Listener r0 = r4.mListener
            if (r0 == 0) goto L74
            r0.onAnimationEnd()
        L74:
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.display.RampAnimator.animateTo(int, int):boolean");
    }

    public boolean isAnimating() {
        return this.mAnimating;
    }

    public void setListener(Listener listener) {
        this.mListener = listener;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postAnimationCallback() {
        this.mChoreographer.postCallback(1, this.mAnimationCallback, null);
    }

    private void cancelAnimationCallback() {
        this.mChoreographer.removeCallbacks(1, this.mAnimationCallback, null);
    }

    public void setOverrideAnimator(boolean value) {
        this.mData.overrideAnimator = value;
    }
}
