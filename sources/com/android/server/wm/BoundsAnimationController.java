package com.android.server.wm;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Choreographer;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.server.wm.BoundsAnimationController;
import com.android.server.wm.WindowManagerInternal;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
/* loaded from: classes.dex */
public class BoundsAnimationController {
    private static final boolean DEBUG = WindowManagerDebugConfig.DEBUG_ANIM;
    private static final int DEBUG_ANIMATION_SLOW_DOWN_FACTOR = 1;
    private static final boolean DEBUG_LOCAL = false;
    private static final int DEFAULT_TRANSITION_DURATION = 425;
    public static final int NO_PIP_MODE_CHANGED_CALLBACKS = 0;
    public static final int SCHEDULE_PIP_MODE_CHANGED_ON_END = 2;
    public static final int SCHEDULE_PIP_MODE_CHANGED_ON_START = 1;
    private static final String TAG = "WindowManager";
    private static final int WAIT_FOR_DRAW_TIMEOUT_MS = 3000;
    private final AnimationHandler mAnimationHandler;
    private final AppTransition mAppTransition;
    private Choreographer mChoreographer;
    private final Interpolator mFastOutSlowInInterpolator;
    private final Handler mHandler;
    private ArrayMap<BoundsAnimationTarget, BoundsAnimator> mRunningAnimations = new ArrayMap<>();
    private final AppTransitionNotifier mAppTransitionNotifier = new AppTransitionNotifier();
    private boolean mFinishAnimationAfterTransition = false;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface SchedulePipModeChangedState {
    }

    /* loaded from: classes.dex */
    private final class AppTransitionNotifier extends WindowManagerInternal.AppTransitionListener implements Runnable {
        private AppTransitionNotifier() {
        }

        public void onAppTransitionCancelledLocked() {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "onAppTransitionCancelledLocked: mFinishAnimationAfterTransition=" + BoundsAnimationController.this.mFinishAnimationAfterTransition);
            }
            animationFinished();
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionFinishedLocked(IBinder token) {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "onAppTransitionFinishedLocked: mFinishAnimationAfterTransition=" + BoundsAnimationController.this.mFinishAnimationAfterTransition);
            }
            animationFinished();
        }

        private void animationFinished() {
            if (BoundsAnimationController.this.mFinishAnimationAfterTransition) {
                BoundsAnimationController.this.mHandler.removeCallbacks(this);
                BoundsAnimationController.this.mHandler.post(this);
            }
        }

        @Override // java.lang.Runnable
        public void run() {
            for (int i = 0; i < BoundsAnimationController.this.mRunningAnimations.size(); i++) {
                BoundsAnimator b = (BoundsAnimator) BoundsAnimationController.this.mRunningAnimations.valueAt(i);
                b.onAnimationEnd(null);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BoundsAnimationController(Context context, AppTransition transition, Handler handler, AnimationHandler animationHandler) {
        this.mHandler = handler;
        this.mAppTransition = transition;
        this.mAppTransition.registerListenerLocked(this.mAppTransitionNotifier);
        this.mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context, 17563661);
        this.mAnimationHandler = animationHandler;
        if (animationHandler != null) {
            handler.runWithScissors(new Runnable() { // from class: com.android.server.wm.-$$Lambda$BoundsAnimationController$dXxeGqbKxeDo9iKFwG9az5rUo3U
                @Override // java.lang.Runnable
                public final void run() {
                    BoundsAnimationController.this.mChoreographer = Choreographer.getSfInstance();
                }
            }, 0L);
            animationHandler.setProvider(new SfVsyncFrameCallbackProvider(this.mChoreographer));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public final class BoundsAnimator extends ValueAnimator implements ValueAnimator.AnimatorUpdateListener, Animator.AnimatorListener {
        private final int mFrozenTaskHeight;
        private final int mFrozenTaskWidth;
        private boolean mMoveFromFullscreen;
        private boolean mMoveToFullscreen;
        private int mPrevSchedulePipModeChangedState;
        private int mSchedulePipModeChangedState;
        private boolean mSkipAnimationEnd;
        private boolean mSkipFinalResize;
        private final BoundsAnimationTarget mTarget;
        private final Rect mFrom = new Rect();
        private final Rect mTo = new Rect();
        private final Rect mTmpRect = new Rect();
        private final Rect mTmpTaskBounds = new Rect();
        private final Runnable mResumeRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$BoundsAnimationController$BoundsAnimator$eIPNx9WcD7moTPCByy2XhPMSdCs
            @Override // java.lang.Runnable
            public final void run() {
                BoundsAnimationController.BoundsAnimator.lambda$new$0(BoundsAnimationController.BoundsAnimator.this);
            }
        };

        public static /* synthetic */ void lambda$new$0(BoundsAnimator boundsAnimator) {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "pause: timed out waiting for windows drawn");
            }
            boundsAnimator.resume();
        }

        BoundsAnimator(BoundsAnimationTarget target, Rect from, Rect to, int schedulePipModeChangedState, int prevShedulePipModeChangedState, boolean moveFromFullscreen, boolean moveToFullscreen) {
            this.mTarget = target;
            this.mFrom.set(from);
            this.mTo.set(to);
            this.mSchedulePipModeChangedState = schedulePipModeChangedState;
            this.mPrevSchedulePipModeChangedState = prevShedulePipModeChangedState;
            this.mMoveFromFullscreen = moveFromFullscreen;
            this.mMoveToFullscreen = moveToFullscreen;
            addUpdateListener(this);
            addListener(this);
            if (animatingToLargerSize()) {
                this.mFrozenTaskWidth = this.mTo.width();
                this.mFrozenTaskHeight = this.mTo.height();
                return;
            }
            this.mFrozenTaskWidth = this.mFrom.width();
            this.mFrozenTaskHeight = this.mFrom.height();
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationStart(Animator animation) {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "onAnimationStart: mTarget=" + this.mTarget + " mPrevSchedulePipModeChangedState=" + this.mPrevSchedulePipModeChangedState + " mSchedulePipModeChangedState=" + this.mSchedulePipModeChangedState);
            }
            BoundsAnimationController.this.mFinishAnimationAfterTransition = false;
            this.mTmpRect.set(this.mFrom.left, this.mFrom.top, this.mFrom.left + this.mFrozenTaskWidth, this.mFrom.top + this.mFrozenTaskHeight);
            BoundsAnimationController.this.updateBooster();
            if (this.mPrevSchedulePipModeChangedState == 0) {
                this.mTarget.onAnimationStart(this.mSchedulePipModeChangedState == 1, false);
                if (this.mMoveFromFullscreen && this.mTarget.shouldDeferStartOnMoveToFullscreen()) {
                    pause();
                }
            } else if (this.mPrevSchedulePipModeChangedState == 2 && this.mSchedulePipModeChangedState == 1) {
                this.mTarget.onAnimationStart(true, true);
            }
            if (animatingToLargerSize()) {
                this.mTarget.setPinnedStackSize(this.mFrom, this.mTmpRect);
                if (this.mMoveToFullscreen) {
                    pause();
                }
            }
        }

        @Override // android.animation.ValueAnimator, android.animation.Animator
        public void pause() {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "pause: waiting for windows drawn");
            }
            super.pause();
            BoundsAnimationController.this.mHandler.postDelayed(this.mResumeRunnable, 3000L);
        }

        @Override // android.animation.ValueAnimator, android.animation.Animator
        public void resume() {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "resume:");
            }
            BoundsAnimationController.this.mHandler.removeCallbacks(this.mResumeRunnable);
            super.resume();
        }

        @Override // android.animation.ValueAnimator.AnimatorUpdateListener
        public void onAnimationUpdate(ValueAnimator animation) {
            float value = ((Float) animation.getAnimatedValue()).floatValue();
            float remains = 1.0f - value;
            this.mTmpRect.left = (int) ((this.mFrom.left * remains) + (this.mTo.left * value) + 0.5f);
            this.mTmpRect.top = (int) ((this.mFrom.top * remains) + (this.mTo.top * value) + 0.5f);
            this.mTmpRect.right = (int) ((this.mFrom.right * remains) + (this.mTo.right * value) + 0.5f);
            this.mTmpRect.bottom = (int) ((this.mFrom.bottom * remains) + (this.mTo.bottom * value) + 0.5f);
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "animateUpdate: mTarget=" + this.mTarget + " mBounds=" + this.mTmpRect + " from=" + this.mFrom + " mTo=" + this.mTo + " value=" + value + " remains=" + remains);
            }
            this.mTmpTaskBounds.set(this.mTmpRect.left, this.mTmpRect.top, this.mTmpRect.left + this.mFrozenTaskWidth, this.mTmpRect.top + this.mFrozenTaskHeight);
            if (!this.mTarget.setPinnedStackSize(this.mTmpRect, this.mTmpTaskBounds)) {
                if (BoundsAnimationController.DEBUG) {
                    Slog.d(BoundsAnimationController.TAG, "animateUpdate: cancelled");
                }
                if (this.mSchedulePipModeChangedState == 1) {
                    this.mSchedulePipModeChangedState = 2;
                }
                cancelAndCallAnimationEnd();
            }
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationEnd(Animator animation) {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "onAnimationEnd: mTarget=" + this.mTarget + " mSkipFinalResize=" + this.mSkipFinalResize + " mFinishAnimationAfterTransition=" + BoundsAnimationController.this.mFinishAnimationAfterTransition + " mAppTransitionIsRunning=" + BoundsAnimationController.this.mAppTransition.isRunning() + " callers=" + Debug.getCallers(2));
            }
            if (BoundsAnimationController.this.mAppTransition.isRunning() && !BoundsAnimationController.this.mFinishAnimationAfterTransition) {
                BoundsAnimationController.this.mFinishAnimationAfterTransition = true;
                return;
            }
            if (!this.mSkipAnimationEnd) {
                if (BoundsAnimationController.DEBUG) {
                    Slog.d(BoundsAnimationController.TAG, "onAnimationEnd: mTarget=" + this.mTarget + " moveToFullscreen=" + this.mMoveToFullscreen);
                }
                this.mTarget.onAnimationEnd(this.mSchedulePipModeChangedState == 2, !this.mSkipFinalResize ? this.mTo : null, this.mMoveToFullscreen);
            }
            removeListener(this);
            removeUpdateListener(this);
            BoundsAnimationController.this.mRunningAnimations.remove(this.mTarget);
            BoundsAnimationController.this.updateBooster();
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationCancel(Animator animation) {
            this.mSkipFinalResize = true;
            this.mMoveToFullscreen = false;
        }

        private void cancelAndCallAnimationEnd() {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "cancelAndCallAnimationEnd: mTarget=" + this.mTarget);
            }
            this.mSkipAnimationEnd = false;
            super.cancel();
        }

        @Override // android.animation.ValueAnimator, android.animation.Animator
        public void cancel() {
            if (BoundsAnimationController.DEBUG) {
                Slog.d(BoundsAnimationController.TAG, "cancel: mTarget=" + this.mTarget);
            }
            this.mSkipAnimationEnd = true;
            super.cancel();
        }

        boolean isAnimatingTo(Rect bounds) {
            return this.mTo.equals(bounds);
        }

        @VisibleForTesting
        boolean animatingToLargerSize() {
            return this.mFrom.width() * this.mFrom.height() <= this.mTo.width() * this.mTo.height();
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationRepeat(Animator animation) {
        }

        public AnimationHandler getAnimationHandler() {
            if (BoundsAnimationController.this.mAnimationHandler != null) {
                return BoundsAnimationController.this.mAnimationHandler;
            }
            return super.getAnimationHandler();
        }
    }

    public void animateBounds(BoundsAnimationTarget target, Rect from, Rect to, int animationDuration, int schedulePipModeChangedState, boolean moveFromFullscreen, boolean moveToFullscreen) {
        animateBoundsImpl(target, from, to, animationDuration, schedulePipModeChangedState, moveFromFullscreen, moveToFullscreen);
    }

    @VisibleForTesting
    BoundsAnimator animateBoundsImpl(BoundsAnimationTarget target, Rect from, Rect to, int animationDuration, int schedulePipModeChangedState, boolean moveFromFullscreen, boolean moveToFullscreen) {
        Rect rect;
        boolean moveFromFullscreen2;
        boolean moveToFullscreen2;
        int schedulePipModeChangedState2;
        boolean moveFromFullscreen3;
        boolean moveToFullscreen3;
        int schedulePipModeChangedState3 = schedulePipModeChangedState;
        BoundsAnimator existing = this.mRunningAnimations.get(target);
        boolean replacing = existing != null;
        int prevSchedulePipModeChangedState = 0;
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("animateBounds: target=");
            sb.append(target);
            sb.append(" from=");
            rect = from;
            sb.append(rect);
            sb.append(" to=");
            sb.append(to);
            sb.append(" schedulePipModeChangedState=");
            sb.append(schedulePipModeChangedState3);
            sb.append(" replacing=");
            sb.append(replacing);
            Slog.d(TAG, sb.toString());
        } else {
            rect = from;
        }
        if (!replacing) {
            moveFromFullscreen2 = moveFromFullscreen;
            moveToFullscreen2 = moveToFullscreen;
            schedulePipModeChangedState2 = schedulePipModeChangedState3;
        } else if (!existing.isAnimatingTo(to) || ((moveToFullscreen && !existing.mMoveToFullscreen) || (moveFromFullscreen && !existing.mMoveFromFullscreen))) {
            prevSchedulePipModeChangedState = existing.mSchedulePipModeChangedState;
            if (existing.mSchedulePipModeChangedState != 1) {
                if (existing.mSchedulePipModeChangedState == 2) {
                    if (schedulePipModeChangedState3 == 1) {
                        if (DEBUG) {
                            Slog.d(TAG, "animateBounds: non-fullscreen animation canceled, callback on start will be processed");
                        }
                    } else {
                        if (DEBUG) {
                            Slog.d(TAG, "animateBounds: still animating from fullscreen, keep existing deferred state");
                        }
                        schedulePipModeChangedState3 = 2;
                    }
                }
            } else if (schedulePipModeChangedState3 == 1) {
                if (DEBUG) {
                    Slog.d(TAG, "animateBounds: still animating to fullscreen, keep existing deferred state");
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "animateBounds: fullscreen animation canceled, callback on start already processed, schedule deferred update on end");
                }
                schedulePipModeChangedState3 = 2;
            }
            if (!moveFromFullscreen && !moveToFullscreen) {
                moveToFullscreen3 = existing.mMoveToFullscreen;
                moveFromFullscreen3 = existing.mMoveFromFullscreen;
            } else {
                moveFromFullscreen3 = moveFromFullscreen;
                moveToFullscreen3 = moveToFullscreen;
            }
            existing.cancel();
            schedulePipModeChangedState2 = schedulePipModeChangedState3;
            moveFromFullscreen2 = moveFromFullscreen3;
            moveToFullscreen2 = moveToFullscreen3;
        } else {
            if (DEBUG) {
                Slog.d(TAG, "animateBounds: same destination and moveTo/From flags as existing=" + existing + ", ignoring...");
            }
            return existing;
        }
        BoundsAnimator animator = new BoundsAnimator(target, rect, to, schedulePipModeChangedState2, prevSchedulePipModeChangedState, moveFromFullscreen2, moveToFullscreen2);
        this.mRunningAnimations.put(target, animator);
        animator.setFloatValues(0.0f, 1.0f);
        animator.setDuration((animationDuration != -1 ? animationDuration : DEFAULT_TRANSITION_DURATION) * 1);
        animator.setInterpolator(this.mFastOutSlowInInterpolator);
        animator.start();
        return animator;
    }

    public Handler getHandler() {
        return this.mHandler;
    }

    public void onAllWindowsDrawn() {
        if (DEBUG) {
            Slog.d(TAG, "onAllWindowsDrawn:");
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$BoundsAnimationController$MoVv_WhxoMrTVo-xz1qu2FMcYrM
            @Override // java.lang.Runnable
            public final void run() {
                BoundsAnimationController.this.resume();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resume() {
        for (int i = 0; i < this.mRunningAnimations.size(); i++) {
            BoundsAnimator b = this.mRunningAnimations.valueAt(i);
            b.resume();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBooster() {
        WindowManagerService.sThreadPriorityBooster.setBoundsAnimationRunning(!this.mRunningAnimations.isEmpty());
    }
}
