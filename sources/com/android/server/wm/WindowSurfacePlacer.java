package com.android.server.wm;

import android.os.Debug;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseIntArray;
import java.io.PrintWriter;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class WindowSurfacePlacer {
    static final int SET_ORIENTATION_CHANGE_COMPLETE = 4;
    static final int SET_UPDATE_ROTATION = 1;
    static final int SET_WALLPAPER_ACTION_PENDING = 8;
    private static final String TAG = "WindowManager";
    private int mLayoutRepeatCount;
    private final WindowManagerService mService;
    private boolean mTraversalScheduled;
    private boolean mInLayout = false;
    private int mDeferDepth = 0;
    private final SparseIntArray mTempTransitionReasons = new SparseIntArray();
    private final Runnable mPerformSurfacePlacement = new Runnable() { // from class: com.android.server.wm.-$$Lambda$WindowSurfacePlacer$4Hbamt-LFcbu8AoZBoOZN_LveKQ
        @Override // java.lang.Runnable
        public final void run() {
            WindowSurfacePlacer.this.lambda$new$0$WindowSurfacePlacer();
        }
    };

    public WindowSurfacePlacer(WindowManagerService service) {
        this.mService = service;
    }

    public /* synthetic */ void lambda$new$0$WindowSurfacePlacer() {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                performSurfacePlacement();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deferLayout() {
        this.mDeferDepth++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void continueLayout() {
        this.mDeferDepth--;
        if (this.mDeferDepth <= 0) {
            performSurfacePlacement();
        }
    }

    boolean isLayoutDeferred() {
        return this.mDeferDepth > 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void performSurfacePlacementIfScheduled() {
        if (this.mTraversalScheduled) {
            performSurfacePlacement();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void performSurfacePlacement() {
        performSurfacePlacement(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void performSurfacePlacement(boolean force) {
        if (this.mDeferDepth > 0 && !force) {
            return;
        }
        int loopCount = 6;
        do {
            this.mTraversalScheduled = false;
            performSurfacePlacementLoop();
            this.mService.mAnimationHandler.removeCallbacks(this.mPerformSurfacePlacement);
            loopCount--;
            if (!this.mTraversalScheduled) {
                break;
            }
        } while (loopCount > 0);
        this.mService.mRoot.mWallpaperActionPending = false;
    }

    private void performSurfacePlacementLoop() {
        if (this.mInLayout) {
            if (WindowManagerDebugConfig.DEBUG) {
                throw new RuntimeException("Recursive call!");
            }
            Slog.w(TAG, "performLayoutAndPlaceSurfacesLocked called while in layout. Callers=" + Debug.getCallers(3));
            return;
        }
        DisplayContent defaultDisplay = this.mService.getDefaultDisplayContentLocked();
        if (defaultDisplay.mWaitingForConfig || !this.mService.mDisplayReady) {
            return;
        }
        Trace.traceBegin(32L, "wmLayout");
        this.mInLayout = true;
        boolean recoveringMemory = false;
        if (!this.mService.mForceRemoves.isEmpty()) {
            while (!this.mService.mForceRemoves.isEmpty()) {
                WindowState ws = this.mService.mForceRemoves.remove(0);
                Slog.i(TAG, "Force removing: " + ws);
                ws.removeImmediately();
            }
            Slog.w(TAG, "Due to memory failure, waiting a bit for next layout");
            Object tmp = new Object();
            synchronized (tmp) {
                try {
                    tmp.wait(250L);
                } catch (InterruptedException e) {
                }
            }
            recoveringMemory = true;
        }
        try {
            this.mService.mRoot.performSurfacePlacement(recoveringMemory);
            this.mInLayout = false;
            if (this.mService.mRoot.isLayoutNeeded()) {
                int i = this.mLayoutRepeatCount + 1;
                this.mLayoutRepeatCount = i;
                if (i < 6) {
                    requestTraversal();
                } else {
                    Slog.e(TAG, "Performed 6 layouts in a row. Skipping");
                    this.mLayoutRepeatCount = 0;
                }
            } else {
                this.mLayoutRepeatCount = 0;
            }
            if (this.mService.mWindowsChanged && !this.mService.mWindowChangeListeners.isEmpty()) {
                this.mService.mH.removeMessages(19);
                this.mService.mH.sendEmptyMessage(19);
            }
        } catch (RuntimeException e2) {
            this.mInLayout = false;
            Slog.wtf(TAG, "Unhandled exception while laying out windows", e2);
        }
        Trace.traceEnd(32L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void debugLayoutRepeats(String msg, int pendingLayoutChanges) {
        if (this.mLayoutRepeatCount >= 4) {
            Slog.v(TAG, "Layouts looping: " + msg + ", mPendingLayoutChanges = 0x" + Integer.toHexString(pendingLayoutChanges));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInLayout() {
        return this.mInLayout;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestTraversal() {
        if (!this.mTraversalScheduled) {
            this.mTraversalScheduled = true;
            this.mService.mAnimationHandler.post(this.mPerformSurfacePlacement);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mTraversalScheduled=" + this.mTraversalScheduled);
        pw.println(prefix + "mHoldScreenWindow=" + this.mService.mRoot.mHoldScreenWindow);
        pw.println(prefix + "mObscuringWindow=" + this.mService.mRoot.mObscuringWindow);
    }
}
