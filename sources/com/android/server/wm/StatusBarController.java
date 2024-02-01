package com.android.server.wm;

import android.os.IBinder;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.StatusBarController;
import com.android.server.wm.WindowManagerInternal;

/* loaded from: classes2.dex */
public class StatusBarController extends BarController {
    private final WindowManagerInternal.AppTransitionListener mAppTransitionListener;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.wm.StatusBarController$1  reason: invalid class name */
    /* loaded from: classes2.dex */
    public class AnonymousClass1 extends WindowManagerInternal.AppTransitionListener {
        private Runnable mAppTransitionPending = new Runnable() { // from class: com.android.server.wm.-$$Lambda$StatusBarController$1$x4q7e0Eysf0ynMSdT1A-JN_ucuI
            @Override // java.lang.Runnable
            public final void run() {
                StatusBarController.AnonymousClass1.this.lambda$$0$StatusBarController$1();
            }
        };
        private Runnable mAppTransitionCancelled = new Runnable() { // from class: com.android.server.wm.-$$Lambda$StatusBarController$1$CizMeoiz6ZVrkt6kAKpSV5htmyc
            @Override // java.lang.Runnable
            public final void run() {
                StatusBarController.AnonymousClass1.this.lambda$$1$StatusBarController$1();
            }
        };
        private Runnable mAppTransitionFinished = new Runnable() { // from class: com.android.server.wm.-$$Lambda$StatusBarController$1$3FiQ0kybPCSlgcNJkCsNm5M12iA
            @Override // java.lang.Runnable
            public final void run() {
                StatusBarController.AnonymousClass1.this.lambda$$2$StatusBarController$1();
            }
        };

        AnonymousClass1() {
        }

        public /* synthetic */ void lambda$$0$StatusBarController$1() {
            StatusBarManagerInternal statusBar = StatusBarController.this.getStatusBarInternal();
            if (statusBar != null) {
                statusBar.appTransitionPending(StatusBarController.this.mDisplayId);
            }
        }

        public /* synthetic */ void lambda$$1$StatusBarController$1() {
            StatusBarManagerInternal statusBar = StatusBarController.this.getStatusBarInternal();
            if (statusBar != null) {
                statusBar.appTransitionCancelled(StatusBarController.this.mDisplayId);
            }
        }

        public /* synthetic */ void lambda$$2$StatusBarController$1() {
            StatusBarManagerInternal statusBar = StatusBarController.this.getStatusBarInternal();
            if (statusBar != null) {
                statusBar.appTransitionFinished(StatusBarController.this.mDisplayId);
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionPendingLocked() {
            StatusBarController.this.mHandler.post(this.mAppTransitionPending);
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public int onAppTransitionStartingLocked(int transit, long duration, final long statusBarAnimationStartTime, final long statusBarAnimationDuration) {
            StatusBarController.this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$StatusBarController$1$t71qcQIBSxRShk0Xohf1lk53bOw
                @Override // java.lang.Runnable
                public final void run() {
                    StatusBarController.AnonymousClass1.this.lambda$onAppTransitionStartingLocked$3$StatusBarController$1(statusBarAnimationStartTime, statusBarAnimationDuration);
                }
            });
            return 0;
        }

        public /* synthetic */ void lambda$onAppTransitionStartingLocked$3$StatusBarController$1(long statusBarAnimationStartTime, long statusBarAnimationDuration) {
            StatusBarManagerInternal statusBar = StatusBarController.this.getStatusBarInternal();
            if (statusBar != null) {
                statusBar.appTransitionStarting(StatusBarController.this.mDisplayId, statusBarAnimationStartTime, statusBarAnimationDuration);
            }
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionCancelledLocked(int transit) {
            StatusBarController.this.mHandler.post(this.mAppTransitionCancelled);
        }

        @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
        public void onAppTransitionFinishedLocked(IBinder token) {
            StatusBarController.this.mHandler.post(this.mAppTransitionFinished);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public StatusBarController(int displayId) {
        super("StatusBar", displayId, 67108864, 268435456, 1073741824, 1, 67108864, 8);
        this.mAppTransitionListener = new AnonymousClass1();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTopAppHidesStatusBar(boolean hidesStatusBar) {
        StatusBarManagerInternal statusBar = getStatusBarInternal();
        if (statusBar != null) {
            statusBar.setTopAppHidesStatusBar(hidesStatusBar);
        }
    }

    @Override // com.android.server.wm.BarController
    protected boolean skipAnimation() {
        return this.mWin.getAttrs().height == -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowManagerInternal.AppTransitionListener getAppTransitionListener() {
        return this.mAppTransitionListener;
    }
}
