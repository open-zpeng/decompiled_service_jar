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
            long frameTimeNanos = RampAnimator.this.mChoreographer.getFrameTimeNanos();
            float timeDelta = ((float) (frameTimeNanos - RampAnimator.this.mLastFrameTimeNanos)) * 1.0E-9f;
            RampAnimator.this.mLastFrameTimeNanos = frameTimeNanos;
            float scale = ValueAnimator.getDurationScale();
            if (scale == 0.0f) {
                RampAnimator.this.mAnimatedValue = RampAnimator.this.mTargetValue;
            } else {
                float amount = (RampAnimator.this.mRate * timeDelta) / scale;
                if (RampAnimator.this.mTargetValue > RampAnimator.this.mCurrentValue) {
                    RampAnimator.this.mAnimatedValue = Math.min(RampAnimator.this.mAnimatedValue + amount, RampAnimator.this.mTargetValue);
                } else {
                    RampAnimator.this.mAnimatedValue = Math.max(RampAnimator.this.mAnimatedValue - amount, RampAnimator.this.mTargetValue);
                }
                if (RampAnimator.this.mData.overrideAnimator) {
                    float percent = RampAnimator.this.mData.frames > 0 ? RampAnimator.this.mData.frame / RampAnimator.this.mData.frames : 1.0f;
                    RampAnimator.this.mAnimatedValue = DisplayBrightnessController.getInterpolation(RampAnimator.this.mData.from, RampAnimator.this.mData.to, percent);
                    RampAnimator.this.mData.frame++;
                    if (percent > 1.0d) {
                        RampAnimator.this.mAnimatedValue = RampAnimator.this.mTargetValue;
                    }
                }
            }
            int oldCurrentValue = RampAnimator.this.mCurrentValue;
            RampAnimator.this.mCurrentValue = Math.round(RampAnimator.this.mAnimatedValue);
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

    public boolean animateTo(int target, int rate) {
        if (this.mFirstTime || rate <= 0) {
            boolean changed = this.mFirstTime;
            if (changed || target != this.mCurrentValue) {
                this.mFirstTime = false;
                this.mRate = 0;
                this.mTargetValue = target;
                this.mCurrentValue = target;
                this.mProperty.setValue(this.mObject, target);
                if (this.mAnimating) {
                    this.mAnimating = false;
                    cancelAnimationCallback();
                }
                if (this.mListener != null) {
                    this.mListener.onAnimationEnd();
                }
                return true;
            }
            return false;
        }
        if (!this.mAnimating || rate > this.mRate || ((target <= this.mCurrentValue && this.mCurrentValue <= this.mTargetValue) || (this.mTargetValue <= this.mCurrentValue && this.mCurrentValue <= target))) {
            this.mRate = rate;
        }
        boolean changed2 = this.mTargetValue != target;
        this.mTargetValue = target;
        if (!this.mAnimating && target != this.mCurrentValue) {
            this.mAnimating = true;
            this.mAnimatedValue = this.mCurrentValue;
            this.mLastFrameTimeNanos = System.nanoTime();
            this.mData.set(this.mCurrentValue, target, this.mRate);
            postAnimationCallback();
        }
        return changed2;
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
