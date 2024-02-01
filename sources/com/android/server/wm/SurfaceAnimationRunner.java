package com.android.server.wm;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.PowerManagerInternal;
import android.util.ArrayMap;
import android.view.Choreographer;
import android.view.SurfaceControl;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.server.AnimationThread;
import com.android.server.wm.LocalAnimationAdapter;
/* loaded from: classes.dex */
public class SurfaceAnimationRunner {
    private final AnimationHandler mAnimationHandler;
    @GuardedBy("mLock")
    private boolean mAnimationStartDeferred;
    private final AnimatorFactory mAnimatorFactory;
    private boolean mApplyScheduled;
    private final Runnable mApplyTransactionRunnable;
    private final Object mCancelLock;
    @VisibleForTesting
    Choreographer mChoreographer;
    private final SurfaceControl.Transaction mFrameTransaction;
    private final Object mLock;
    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mPendingAnimations;
    private final PowerManagerInternal mPowerManagerInternal;
    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mRunningAnimations;

    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface AnimatorFactory {
        ValueAnimator makeAnimator();
    }

    public SurfaceAnimationRunner(PowerManagerInternal powerManagerInternal) {
        this(null, null, new SurfaceControl.Transaction(), powerManagerInternal);
    }

    @VisibleForTesting
    SurfaceAnimationRunner(AnimationHandler.AnimationFrameCallbackProvider callbackProvider, AnimatorFactory animatorFactory, SurfaceControl.Transaction frameTransaction, PowerManagerInternal powerManagerInternal) {
        AnimationHandler.AnimationFrameCallbackProvider sfVsyncFrameCallbackProvider;
        this.mLock = new Object();
        this.mCancelLock = new Object();
        this.mApplyTransactionRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SurfaceAnimationRunner$lSzwjoKEGADoEFOzdEnwriAk0T4
            @Override // java.lang.Runnable
            public final void run() {
                SurfaceAnimationRunner.this.applyTransaction();
            }
        };
        this.mPendingAnimations = new ArrayMap<>();
        this.mRunningAnimations = new ArrayMap<>();
        SurfaceAnimationThread.getHandler().runWithScissors(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SurfaceAnimationRunner$xDyZdsMrcbp64p4BQmOGPvVnSWA
            @Override // java.lang.Runnable
            public final void run() {
                SurfaceAnimationRunner.this.mChoreographer = Choreographer.getSfInstance();
            }
        }, 0L);
        this.mFrameTransaction = frameTransaction;
        this.mAnimationHandler = new AnimationHandler();
        AnimationHandler animationHandler = this.mAnimationHandler;
        if (callbackProvider != null) {
            sfVsyncFrameCallbackProvider = callbackProvider;
        } else {
            sfVsyncFrameCallbackProvider = new SfVsyncFrameCallbackProvider(this.mChoreographer);
        }
        animationHandler.setProvider(sfVsyncFrameCallbackProvider);
        this.mAnimatorFactory = animatorFactory != null ? animatorFactory : new AnimatorFactory() { // from class: com.android.server.wm.-$$Lambda$SurfaceAnimationRunner$we7K92eAl3biB_bzyqbv5xCmasE
            @Override // com.android.server.wm.SurfaceAnimationRunner.AnimatorFactory
            public final ValueAnimator makeAnimator() {
                return SurfaceAnimationRunner.lambda$new$1(SurfaceAnimationRunner.this);
            }
        };
        this.mPowerManagerInternal = powerManagerInternal;
    }

    public static /* synthetic */ ValueAnimator lambda$new$1(SurfaceAnimationRunner surfaceAnimationRunner) {
        return new SfValueAnimator();
    }

    public void deferStartingAnimations() {
        synchronized (this.mLock) {
            this.mAnimationStartDeferred = true;
        }
    }

    public void continueStartingAnimations() {
        synchronized (this.mLock) {
            this.mAnimationStartDeferred = false;
            if (!this.mPendingAnimations.isEmpty()) {
                this.mChoreographer.postFrameCallback(new $$Lambda$SurfaceAnimationRunner$9Wa9MhcrSX12liOouHtYXEkDU60(this));
            }
        }
    }

    public void startAnimation(LocalAnimationAdapter.AnimationSpec a, SurfaceControl animationLeash, SurfaceControl.Transaction t, Runnable finishCallback) {
        synchronized (this.mLock) {
            RunningAnimation runningAnim = new RunningAnimation(a, animationLeash, finishCallback);
            this.mPendingAnimations.put(animationLeash, runningAnim);
            if (!this.mAnimationStartDeferred) {
                this.mChoreographer.postFrameCallback(new $$Lambda$SurfaceAnimationRunner$9Wa9MhcrSX12liOouHtYXEkDU60(this));
            }
            applyTransformation(runningAnim, t, 0L);
        }
    }

    public void onAnimationCancelled(SurfaceControl leash) {
        synchronized (this.mLock) {
            if (this.mPendingAnimations.containsKey(leash)) {
                this.mPendingAnimations.remove(leash);
                return;
            }
            final RunningAnimation anim = this.mRunningAnimations.get(leash);
            if (anim != null) {
                this.mRunningAnimations.remove(leash);
                synchronized (this.mCancelLock) {
                    anim.mCancelled = true;
                }
                SurfaceAnimationThread.getHandler().post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SurfaceAnimationRunner$SGOilG6qRe0XTsTJRQqQKhta0pA
                    @Override // java.lang.Runnable
                    public final void run() {
                        SurfaceAnimationRunner.lambda$onAnimationCancelled$2(SurfaceAnimationRunner.this, anim);
                    }
                });
            }
        }
    }

    public static /* synthetic */ void lambda$onAnimationCancelled$2(SurfaceAnimationRunner surfaceAnimationRunner, RunningAnimation anim) {
        anim.mAnim.cancel();
        surfaceAnimationRunner.applyTransaction();
    }

    @GuardedBy("mLock")
    private void startPendingAnimationsLocked() {
        for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
            startAnimationLocked(this.mPendingAnimations.valueAt(i));
        }
        this.mPendingAnimations.clear();
    }

    @GuardedBy("mLock")
    private void startAnimationLocked(final RunningAnimation a) {
        final ValueAnimator anim = this.mAnimatorFactory.makeAnimator();
        anim.overrideDurationScale(1.0f);
        anim.setDuration(a.mAnimSpec.getDuration());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.android.server.wm.-$$Lambda$SurfaceAnimationRunner$puhYAP5tF0mSSJva-eUz59HnrkA
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                SurfaceAnimationRunner.lambda$startAnimationLocked$3(SurfaceAnimationRunner.this, a, anim, valueAnimator);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() { // from class: com.android.server.wm.SurfaceAnimationRunner.1
            {
                SurfaceAnimationRunner.this = this;
            }

            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationStart(Animator animation) {
                synchronized (SurfaceAnimationRunner.this.mCancelLock) {
                    if (!a.mCancelled) {
                        SurfaceAnimationRunner.this.mFrameTransaction.show(a.mLeash);
                    }
                }
            }

            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationEnd(Animator animation) {
                synchronized (SurfaceAnimationRunner.this.mLock) {
                    SurfaceAnimationRunner.this.mRunningAnimations.remove(a.mLeash);
                    synchronized (SurfaceAnimationRunner.this.mCancelLock) {
                        if (!a.mCancelled) {
                            AnimationThread.getHandler().post(a.mFinishCallback);
                        }
                    }
                }
            }
        });
        a.mAnim = anim;
        this.mRunningAnimations.put(a.mLeash, a);
        anim.start();
        if (a.mAnimSpec.canSkipFirstFrame()) {
            anim.setCurrentPlayTime(this.mChoreographer.getFrameIntervalNanos() / 1000000);
        }
        anim.doAnimationFrame(this.mChoreographer.getFrameTime());
    }

    public static /* synthetic */ void lambda$startAnimationLocked$3(SurfaceAnimationRunner surfaceAnimationRunner, RunningAnimation a, ValueAnimator anim, ValueAnimator animation) {
        synchronized (surfaceAnimationRunner.mCancelLock) {
            if (!a.mCancelled) {
                long duration = anim.getDuration();
                long currentPlayTime = anim.getCurrentPlayTime();
                if (currentPlayTime > duration) {
                    currentPlayTime = duration;
                }
                surfaceAnimationRunner.applyTransformation(a, surfaceAnimationRunner.mFrameTransaction, currentPlayTime);
            }
        }
        surfaceAnimationRunner.scheduleApplyTransaction();
    }

    private void applyTransformation(RunningAnimation a, SurfaceControl.Transaction t, long currentPlayTime) {
        if (a.mAnimSpec.needsEarlyWakeup()) {
            t.setEarlyWakeup();
        }
        a.mAnimSpec.apply(t, a.mLeash, currentPlayTime);
    }

    public void startAnimations(long frameTimeNanos) {
        synchronized (this.mLock) {
            startPendingAnimationsLocked();
        }
        this.mPowerManagerInternal.powerHint(2, 0);
    }

    private void scheduleApplyTransaction() {
        if (!this.mApplyScheduled) {
            this.mChoreographer.postCallback(2, this.mApplyTransactionRunnable, null);
            this.mApplyScheduled = true;
        }
    }

    public void applyTransaction() {
        this.mFrameTransaction.setAnimationTransaction();
        this.mFrameTransaction.apply();
        this.mApplyScheduled = false;
    }

    /* loaded from: classes.dex */
    public static final class RunningAnimation {
        ValueAnimator mAnim;
        final LocalAnimationAdapter.AnimationSpec mAnimSpec;
        @GuardedBy("mCancelLock")
        private boolean mCancelled;
        final Runnable mFinishCallback;
        final SurfaceControl mLeash;

        RunningAnimation(LocalAnimationAdapter.AnimationSpec animSpec, SurfaceControl leash, Runnable finishCallback) {
            this.mAnimSpec = animSpec;
            this.mLeash = leash;
            this.mFinishCallback = finishCallback;
        }
    }

    /* loaded from: classes.dex */
    public class SfValueAnimator extends ValueAnimator {
        SfValueAnimator() {
            SurfaceAnimationRunner.this = r1;
            setFloatValues(0.0f, 1.0f);
        }

        public AnimationHandler getAnimationHandler() {
            return SurfaceAnimationRunner.this.mAnimationHandler;
        }
    }
}
