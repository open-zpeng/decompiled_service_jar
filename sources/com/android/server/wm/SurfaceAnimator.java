package com.android.server.wm;

import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import com.android.internal.annotations.VisibleForTesting;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class SurfaceAnimator {
    private static final String TAG = "WindowManager";
    private final Animatable mAnimatable;
    private AnimationAdapter mAnimation;
    @VisibleForTesting
    final Runnable mAnimationFinishedCallback;
    private boolean mAnimationStartDelayed;
    private final OnAnimationFinishedCallback mInnerAnimationFinishedCallback;
    @VisibleForTesting
    SurfaceControl mLeash;
    private final WindowManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface OnAnimationFinishedCallback {
        void onAnimationFinished(AnimationAdapter animationAdapter);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceAnimator(Animatable animatable, Runnable animationFinishedCallback, WindowManagerService service) {
        this.mAnimatable = animatable;
        this.mService = service;
        this.mAnimationFinishedCallback = animationFinishedCallback;
        this.mInnerAnimationFinishedCallback = getFinishedCallback(animationFinishedCallback);
    }

    private OnAnimationFinishedCallback getFinishedCallback(final Runnable animationFinishedCallback) {
        return new OnAnimationFinishedCallback() { // from class: com.android.server.wm.-$$Lambda$SurfaceAnimator$vdRZk66hQVbQCvVXEaQCT1kVmFc
            @Override // com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback
            public final void onAnimationFinished(AnimationAdapter animationAdapter) {
                SurfaceAnimator.lambda$getFinishedCallback$1(SurfaceAnimator.this, animationFinishedCallback, animationAdapter);
            }
        };
    }

    public static /* synthetic */ void lambda$getFinishedCallback$1(final SurfaceAnimator surfaceAnimator, final Runnable animationFinishedCallback, AnimationAdapter anim) {
        synchronized (surfaceAnimator.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                SurfaceAnimator target = surfaceAnimator.mService.mAnimationTransferMap.remove(anim);
                if (target != null) {
                    target.mInnerAnimationFinishedCallback.onAnimationFinished(anim);
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (anim != surfaceAnimator.mAnimation) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else {
                    Runnable resetAndInvokeFinish = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SurfaceAnimator$SIBia0mND666K8lMCPsoid8pUTI
                        @Override // java.lang.Runnable
                        public final void run() {
                            SurfaceAnimator.lambda$getFinishedCallback$0(SurfaceAnimator.this, animationFinishedCallback);
                        }
                    };
                    if (!surfaceAnimator.mAnimatable.shouldDeferAnimationFinish(resetAndInvokeFinish)) {
                        resetAndInvokeFinish.run();
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public static /* synthetic */ void lambda$getFinishedCallback$0(SurfaceAnimator surfaceAnimator, Runnable animationFinishedCallback) {
        surfaceAnimator.reset(surfaceAnimator.mAnimatable.getPendingTransaction(), true);
        if (animationFinishedCallback != null) {
            animationFinishedCallback.run();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startAnimation(SurfaceControl.Transaction t, AnimationAdapter anim, boolean hidden) {
        cancelAnimation(t, true, true);
        this.mAnimation = anim;
        SurfaceControl surface = this.mAnimatable.getSurfaceControl();
        if (surface == null) {
            Slog.w(TAG, "Unable to start animation, surface is null or no children.");
            cancelAnimation();
            return;
        }
        this.mLeash = createAnimationLeash(surface, t, this.mAnimatable.getSurfaceWidth(), this.mAnimatable.getSurfaceHeight(), hidden);
        this.mAnimatable.onAnimationLeashCreated(t, this.mLeash);
        if (this.mAnimationStartDelayed) {
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.i(TAG, "Animation start delayed");
                return;
            }
            return;
        }
        this.mAnimation.startAnimation(this.mLeash, t, this.mInnerAnimationFinishedCallback);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startDelayingAnimationStart() {
        if (!isAnimating()) {
            this.mAnimationStartDelayed = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void endDelayingAnimationStart() {
        boolean delayed = this.mAnimationStartDelayed;
        this.mAnimationStartDelayed = false;
        if (delayed && this.mAnimation != null) {
            this.mAnimation.startAnimation(this.mLeash, this.mAnimatable.getPendingTransaction(), this.mInnerAnimationFinishedCallback);
            this.mAnimatable.commitPendingTransaction();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimating() {
        return this.mAnimation != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AnimationAdapter getAnimation() {
        return this.mAnimation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelAnimation() {
        cancelAnimation(this.mAnimatable.getPendingTransaction(), false, true);
        this.mAnimatable.commitPendingTransaction();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLayer(SurfaceControl.Transaction t, int layer) {
        t.setLayer(this.mLeash != null ? this.mLeash : this.mAnimatable.getSurfaceControl(), layer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRelativeLayer(SurfaceControl.Transaction t, SurfaceControl relativeTo, int layer) {
        t.setRelativeLayer(this.mLeash != null ? this.mLeash : this.mAnimatable.getSurfaceControl(), relativeTo, layer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(SurfaceControl.Transaction t, SurfaceControl newParent) {
        t.reparent(this.mLeash != null ? this.mLeash : this.mAnimatable.getSurfaceControl(), newParent.getHandle());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasLeash() {
        return this.mLeash != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void transferAnimation(SurfaceAnimator from) {
        if (from.mLeash == null) {
            return;
        }
        SurfaceControl surface = this.mAnimatable.getSurfaceControl();
        SurfaceControl parent = this.mAnimatable.getAnimationLeashParent();
        if (surface == null || parent == null) {
            Slog.w(TAG, "Unable to transfer animation, surface or parent is null");
            cancelAnimation();
            return;
        }
        endDelayingAnimationStart();
        SurfaceControl.Transaction t = this.mAnimatable.getPendingTransaction();
        cancelAnimation(t, true, true);
        this.mLeash = from.mLeash;
        this.mAnimation = from.mAnimation;
        from.cancelAnimation(t, false, false);
        t.reparent(surface, this.mLeash.getHandle());
        t.reparent(this.mLeash, parent.getHandle());
        this.mAnimatable.onAnimationLeashCreated(t, this.mLeash);
        this.mService.mAnimationTransferMap.put(this.mAnimation, this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimationStartDelayed() {
        return this.mAnimationStartDelayed;
    }

    private void cancelAnimation(SurfaceControl.Transaction t, boolean restarting, boolean forwardCancel) {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.i(TAG, "Cancelling animation restarting=" + restarting);
        }
        SurfaceControl leash = this.mLeash;
        AnimationAdapter animation = this.mAnimation;
        reset(t, forwardCancel);
        if (animation != null) {
            if (!this.mAnimationStartDelayed && forwardCancel) {
                animation.onAnimationCancelled(leash);
            }
            if (!restarting) {
                this.mAnimationFinishedCallback.run();
            }
        }
        if (!restarting) {
            this.mAnimationStartDelayed = false;
        }
    }

    private void reset(SurfaceControl.Transaction t, boolean destroyLeash) {
        SurfaceControl surface = this.mAnimatable.getSurfaceControl();
        SurfaceControl parent = this.mAnimatable.getParentSurfaceControl();
        boolean scheduleAnim = false;
        boolean destroy = (this.mLeash == null || surface == null || parent == null) ? false : true;
        if (destroy) {
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.i(TAG, "Reparenting to original parent");
            }
            t.reparent(surface, parent.getHandle());
            scheduleAnim = true;
        }
        this.mService.mAnimationTransferMap.remove(this.mAnimation);
        if (this.mLeash != null && destroyLeash) {
            t.destroy(this.mLeash);
            scheduleAnim = true;
        }
        this.mLeash = null;
        this.mAnimation = null;
        if (destroy) {
            this.mAnimatable.onAnimationLeashDestroyed(t);
            scheduleAnim = true;
        }
        if (scheduleAnim) {
            this.mService.scheduleAnimationLocked();
        }
    }

    private SurfaceControl createAnimationLeash(SurfaceControl surface, SurfaceControl.Transaction t, int width, int height, boolean hidden) {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.i(TAG, "Reparenting to leash");
        }
        SurfaceControl.Builder parent = this.mAnimatable.makeAnimationLeash().setParent(this.mAnimatable.getAnimationLeashParent());
        SurfaceControl.Builder builder = parent.setName(surface + " - animation-leash").setSize(width, height);
        SurfaceControl leash = builder.build();
        if (!hidden) {
            t.show(leash);
        }
        t.reparent(surface, leash.getHandle());
        return leash;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        if (this.mAnimation != null) {
            this.mAnimation.writeToProto(proto, 1146756268035L);
        }
        if (this.mLeash != null) {
            this.mLeash.writeToProto(proto, 1146756268033L);
        }
        proto.write(1133871366146L, this.mAnimationStartDelayed);
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("mLeash=");
        pw.print(this.mLeash);
        if (this.mAnimationStartDelayed) {
            pw.print(" mAnimationStartDelayed=");
            pw.println(this.mAnimationStartDelayed);
        } else {
            pw.println();
        }
        pw.print(prefix);
        pw.println("Animation:");
        if (this.mAnimation != null) {
            AnimationAdapter animationAdapter = this.mAnimation;
            animationAdapter.dump(pw, prefix + "  ");
            return;
        }
        pw.print(prefix);
        pw.println("null");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface Animatable {
        void commitPendingTransaction();

        SurfaceControl getAnimationLeashParent();

        SurfaceControl getParentSurfaceControl();

        SurfaceControl.Transaction getPendingTransaction();

        SurfaceControl getSurfaceControl();

        int getSurfaceHeight();

        int getSurfaceWidth();

        SurfaceControl.Builder makeAnimationLeash();

        void onAnimationLeashCreated(SurfaceControl.Transaction transaction, SurfaceControl surfaceControl);

        void onAnimationLeashDestroyed(SurfaceControl.Transaction transaction);

        default boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            return false;
        }
    }
}
