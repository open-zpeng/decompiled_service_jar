package com.android.server.wm;

import android.content.Context;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.Choreographer;
import android.view.SurfaceControl;
import com.android.server.AnimationThread;
import com.android.server.policy.WindowManagerPolicy;
import java.io.PrintWriter;
import java.util.ArrayList;

/* loaded from: classes2.dex */
public class WindowAnimator {
    private static final String TAG = "WindowManager";
    private boolean mAnimating;
    final Choreographer.FrameCallback mAnimationFrameCallback;
    private boolean mAnimationFrameCallbackScheduled;
    private Choreographer mChoreographer;
    final Context mContext;
    long mCurrentTime;
    private boolean mInExecuteAfterPrepareSurfacesRunnables;
    private boolean mLastRootAnimating;
    Object mLastWindowFreezeSource;
    final WindowManagerPolicy mPolicy;
    final WindowManagerService mService;
    int mBulkUpdateParams = 0;
    SparseArray<DisplayContentsAnimator> mDisplayContentsAnimators = new SparseArray<>(2);
    private boolean mInitialized = false;
    private boolean mRemoveReplacedWindows = false;
    private final ArrayList<Runnable> mAfterPrepareSurfacesRunnables = new ArrayList<>();
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowAnimator(WindowManagerService service) {
        this.mService = service;
        this.mContext = service.mContext;
        this.mPolicy = service.mPolicy;
        AnimationThread.getHandler().runWithScissors(new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowAnimator$U3Fu5_RzEyNo8Jt6zTb2ozdXiqM
            @Override // java.lang.Runnable
            public final void run() {
                WindowAnimator.this.lambda$new$0$WindowAnimator();
            }
        }, 0L);
        this.mAnimationFrameCallback = new Choreographer.FrameCallback() { // from class: com.android.server.wm.-$$Lambda$WindowAnimator$ddXU8gK8rmDqri0OZVMNa3Y4GHk
            @Override // android.view.Choreographer.FrameCallback
            public final void doFrame(long j) {
                WindowAnimator.this.lambda$new$1$WindowAnimator(j);
            }
        };
    }

    public /* synthetic */ void lambda$new$0$WindowAnimator() {
        this.mChoreographer = Choreographer.getSfInstance();
    }

    public /* synthetic */ void lambda$new$1$WindowAnimator(long frameTimeNs) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mAnimationFrameCallbackScheduled = false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        animate(frameTimeNs);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addDisplayLocked(int displayId) {
        getDisplayContentsAnimatorLocked(displayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeDisplayLocked(int displayId) {
        DisplayContentsAnimator displayAnimator = this.mDisplayContentsAnimators.get(displayId);
        if (displayAnimator != null && displayAnimator.mScreenRotationAnimation != null) {
            displayAnimator.mScreenRotationAnimation.kill();
            displayAnimator.mScreenRotationAnimation = null;
        }
        this.mDisplayContentsAnimators.delete(displayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void ready() {
        this.mInitialized = true;
    }

    private void animate(long frameTimeNs) {
        String str;
        String str2;
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mInitialized) {
                    scheduleAnimation();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    synchronized (this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            this.mCurrentTime = frameTimeNs / 1000000;
                            this.mBulkUpdateParams = 4;
                            this.mAnimating = false;
                            if (WindowManagerDebugConfig.DEBUG_WINDOW_TRACE) {
                                Slog.i(TAG, "!!! animate: entry time=" + this.mCurrentTime);
                            }
                            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                                Slog.i(TAG, ">>> OPEN TRANSACTION animate");
                            }
                            this.mService.openSurfaceTransaction();
                            try {
                                AccessibilityController accessibilityController = this.mService.mAccessibilityController;
                                int numDisplays = this.mDisplayContentsAnimators.size();
                                for (int i = 0; i < numDisplays; i++) {
                                    DisplayContent dc = this.mService.mRoot.getDisplayContent(this.mDisplayContentsAnimators.keyAt(i));
                                    DisplayContentsAnimator displayAnimator = this.mDisplayContentsAnimators.valueAt(i);
                                    ScreenRotationAnimation screenRotationAnimation = displayAnimator.mScreenRotationAnimation;
                                    if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
                                        if (screenRotationAnimation.stepAnimationLocked(this.mCurrentTime)) {
                                            setAnimating(true);
                                        } else {
                                            this.mBulkUpdateParams |= 1;
                                            screenRotationAnimation.kill();
                                            displayAnimator.mScreenRotationAnimation = null;
                                            if (accessibilityController != null) {
                                                accessibilityController.onRotationChangedLocked(dc);
                                            }
                                        }
                                    }
                                    dc.updateWindowsForAnimator();
                                    dc.updateBackgroundForAnimator();
                                    dc.prepareSurfaces();
                                }
                                for (int i2 = 0; i2 < numDisplays; i2++) {
                                    int displayId = this.mDisplayContentsAnimators.keyAt(i2);
                                    DisplayContent dc2 = this.mService.mRoot.getDisplayContent(displayId);
                                    dc2.checkAppWindowsReadyToShow();
                                    ScreenRotationAnimation screenRotationAnimation2 = this.mDisplayContentsAnimators.valueAt(i2).mScreenRotationAnimation;
                                    if (screenRotationAnimation2 != null) {
                                        screenRotationAnimation2.updateSurfaces(this.mTransaction);
                                    }
                                    orAnimating(dc2.getDockedDividerController().animate(this.mCurrentTime));
                                    if (accessibilityController != null) {
                                        accessibilityController.drawMagnifiedRegionBorderIfNeededLocked(displayId);
                                    }
                                }
                                if (!this.mAnimating) {
                                    cancelAnimation();
                                }
                                if (this.mService.mWatermark != null) {
                                    this.mService.mWatermark.drawIfNeeded();
                                }
                                SurfaceControl.mergeToGlobalTransaction(this.mTransaction);
                                this.mService.closeSurfaceTransaction("WindowAnimator");
                            } catch (RuntimeException e) {
                                Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
                                this.mService.closeSurfaceTransaction("WindowAnimator");
                                if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                                    str = TAG;
                                    str2 = "<<< CLOSE TRANSACTION animate";
                                }
                            }
                            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                                str = TAG;
                                str2 = "<<< CLOSE TRANSACTION animate";
                                Slog.i(str, str2);
                            }
                            boolean hasPendingLayoutChanges = this.mService.mRoot.hasPendingLayoutChanges(this);
                            boolean doRequest = false;
                            if (this.mBulkUpdateParams != 0) {
                                doRequest = this.mService.mRoot.copyAnimToLayoutParams();
                            }
                            if (hasPendingLayoutChanges || doRequest) {
                                this.mService.mWindowPlacerLocked.requestTraversal();
                            }
                            boolean rootAnimating = this.mService.mRoot.isSelfOrChildAnimating();
                            if (rootAnimating && !this.mLastRootAnimating) {
                                this.mService.mTaskSnapshotController.setPersisterPaused(true);
                                Trace.asyncTraceBegin(32L, "animating", 0);
                            }
                            if (!rootAnimating && this.mLastRootAnimating) {
                                this.mService.mWindowPlacerLocked.requestTraversal();
                                this.mService.mTaskSnapshotController.setPersisterPaused(false);
                                Trace.asyncTraceEnd(32L, "animating", 0);
                            }
                            this.mLastRootAnimating = rootAnimating;
                            if (this.mRemoveReplacedWindows) {
                                this.mService.mRoot.removeReplacedWindows();
                                this.mRemoveReplacedWindows = false;
                            }
                            this.mService.destroyPreservedSurfaceLocked();
                            executeAfterPrepareSurfacesRunnables();
                            if (WindowManagerDebugConfig.DEBUG_WINDOW_TRACE) {
                                Slog.i(TAG, "!!! animate: exit mAnimating=" + this.mAnimating + " mBulkUpdateParams=" + Integer.toHexString(this.mBulkUpdateParams) + " hasPendingLayoutChanges=" + hasPendingLayoutChanges);
                            }
                        } finally {
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } finally {
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        }
    }

    private static String bulkUpdateParamsToString(int bulkUpdateParams) {
        StringBuilder builder = new StringBuilder(128);
        if ((bulkUpdateParams & 1) != 0) {
            builder.append(" UPDATE_ROTATION");
        }
        if ((bulkUpdateParams & 4) != 0) {
            builder.append(" ORIENTATION_CHANGE_COMPLETE");
        }
        return builder.toString();
    }

    public void dumpLocked(PrintWriter pw, String prefix, boolean dumpAll) {
        String subPrefix = "  " + prefix;
        String subSubPrefix = "  " + subPrefix;
        for (int i = 0; i < this.mDisplayContentsAnimators.size(); i++) {
            pw.print(prefix);
            pw.print("DisplayContentsAnimator #");
            pw.print(this.mDisplayContentsAnimators.keyAt(i));
            pw.println(":");
            DisplayContentsAnimator displayAnimator = this.mDisplayContentsAnimators.valueAt(i);
            DisplayContent dc = this.mService.mRoot.getDisplayContent(this.mDisplayContentsAnimators.keyAt(i));
            dc.dumpWindowAnimators(pw, subPrefix);
            if (displayAnimator.mScreenRotationAnimation != null) {
                pw.print(subPrefix);
                pw.println("mScreenRotationAnimation:");
                displayAnimator.mScreenRotationAnimation.printTo(subSubPrefix, pw);
            } else if (dumpAll) {
                pw.print(subPrefix);
                pw.println("no ScreenRotationAnimation ");
            }
            pw.println();
        }
        pw.println();
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mCurrentTime=");
            pw.println(TimeUtils.formatUptime(this.mCurrentTime));
        }
        if (this.mBulkUpdateParams != 0) {
            pw.print(prefix);
            pw.print("mBulkUpdateParams=0x");
            pw.print(Integer.toHexString(this.mBulkUpdateParams));
            pw.println(bulkUpdateParamsToString(this.mBulkUpdateParams));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getPendingLayoutChanges(int displayId) {
        DisplayContent displayContent;
        if (displayId >= 0 && (displayContent = this.mService.mRoot.getDisplayContent(displayId)) != null) {
            return displayContent.pendingLayoutChanges;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPendingLayoutChanges(int displayId, int changes) {
        DisplayContent displayContent;
        if (displayId >= 0 && (displayContent = this.mService.mRoot.getDisplayContent(displayId)) != null) {
            displayContent.pendingLayoutChanges |= changes;
        }
    }

    private DisplayContentsAnimator getDisplayContentsAnimatorLocked(int displayId) {
        if (displayId < 0) {
            return null;
        }
        DisplayContentsAnimator displayAnimator = this.mDisplayContentsAnimators.get(displayId);
        if (displayAnimator == null && this.mService.mRoot.getDisplayContent(displayId) != null) {
            DisplayContentsAnimator displayAnimator2 = new DisplayContentsAnimator();
            this.mDisplayContentsAnimators.put(displayId, displayAnimator2);
            return displayAnimator2;
        }
        return displayAnimator;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setScreenRotationAnimationLocked(int displayId, ScreenRotationAnimation animation) {
        DisplayContentsAnimator animator = getDisplayContentsAnimatorLocked(displayId);
        if (animator != null) {
            animator.mScreenRotationAnimation = animation;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ScreenRotationAnimation getScreenRotationAnimationLocked(int displayId) {
        DisplayContentsAnimator animator;
        if (displayId >= 0 && (animator = getDisplayContentsAnimatorLocked(displayId)) != null) {
            return animator.mScreenRotationAnimation;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestRemovalOfReplacedWindows(WindowState win) {
        this.mRemoveReplacedWindows = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleAnimation() {
        if (!this.mAnimationFrameCallbackScheduled) {
            this.mAnimationFrameCallbackScheduled = true;
            this.mChoreographer.postFrameCallback(this.mAnimationFrameCallback);
        }
    }

    private void cancelAnimation() {
        if (this.mAnimationFrameCallbackScheduled) {
            this.mAnimationFrameCallbackScheduled = false;
            this.mChoreographer.removeFrameCallback(this.mAnimationFrameCallback);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class DisplayContentsAnimator {
        ScreenRotationAnimation mScreenRotationAnimation;

        private DisplayContentsAnimator() {
            this.mScreenRotationAnimation = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimating() {
        return this.mAnimating;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimationScheduled() {
        return this.mAnimationFrameCallbackScheduled;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Choreographer getChoreographer() {
        return this.mChoreographer;
    }

    void setAnimating(boolean animating) {
        this.mAnimating = animating;
    }

    void orAnimating(boolean animating) {
        this.mAnimating |= animating;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addAfterPrepareSurfacesRunnable(Runnable r) {
        if (this.mInExecuteAfterPrepareSurfacesRunnables) {
            r.run();
            return;
        }
        this.mAfterPrepareSurfacesRunnables.add(r);
        scheduleAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void executeAfterPrepareSurfacesRunnables() {
        if (this.mInExecuteAfterPrepareSurfacesRunnables) {
            return;
        }
        this.mInExecuteAfterPrepareSurfacesRunnables = true;
        int size = this.mAfterPrepareSurfacesRunnables.size();
        for (int i = 0; i < size; i++) {
            this.mAfterPrepareSurfacesRunnables.get(i).run();
        }
        this.mAfterPrepareSurfacesRunnables.clear();
        this.mInExecuteAfterPrepareSurfacesRunnables = false;
    }
}
