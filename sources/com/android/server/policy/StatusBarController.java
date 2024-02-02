package com.android.server.policy;

import android.os.IBinder;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.WindowManagerInternal;
/* loaded from: classes.dex */
public class StatusBarController extends BarController {
    private final WindowManagerInternal.AppTransitionListener mAppTransitionListener;

    public StatusBarController() {
        super("StatusBar", 67108864, 268435456, 1073741824, 1, 67108864, 8);
        this.mAppTransitionListener = new WindowManagerInternal.AppTransitionListener() { // from class: com.android.server.policy.StatusBarController.1
            @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
            public void onAppTransitionPendingLocked() {
                StatusBarController.this.mHandler.post(new Runnable() { // from class: com.android.server.policy.StatusBarController.1.1
                    @Override // java.lang.Runnable
                    public void run() {
                        StatusBarManagerInternal statusbar = StatusBarController.this.getStatusBarInternal();
                        if (statusbar != null) {
                            statusbar.appTransitionPending();
                        }
                    }
                });
            }

            @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
            public int onAppTransitionStartingLocked(int transit, IBinder openToken, IBinder closeToken, long duration, final long statusBarAnimationStartTime, final long statusBarAnimationDuration) {
                StatusBarController.this.mHandler.post(new Runnable() { // from class: com.android.server.policy.StatusBarController.1.2
                    @Override // java.lang.Runnable
                    public void run() {
                        StatusBarManagerInternal statusbar = StatusBarController.this.getStatusBarInternal();
                        if (statusbar != null) {
                            statusbar.appTransitionStarting(statusBarAnimationStartTime, statusBarAnimationDuration);
                        }
                    }
                });
                return 0;
            }

            @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
            public void onAppTransitionCancelledLocked(int transit) {
                StatusBarController.this.mHandler.post(new Runnable() { // from class: com.android.server.policy.StatusBarController.1.3
                    @Override // java.lang.Runnable
                    public void run() {
                        StatusBarManagerInternal statusbar = StatusBarController.this.getStatusBarInternal();
                        if (statusbar != null) {
                            statusbar.appTransitionCancelled();
                        }
                    }
                });
            }

            @Override // com.android.server.wm.WindowManagerInternal.AppTransitionListener
            public void onAppTransitionFinishedLocked(IBinder token) {
                StatusBarController.this.mHandler.post(new Runnable() { // from class: com.android.server.policy.StatusBarController.1.4
                    @Override // java.lang.Runnable
                    public void run() {
                        StatusBarManagerInternal statusbar = (StatusBarManagerInternal) LocalServices.getService(StatusBarManagerInternal.class);
                        if (statusbar != null) {
                            statusbar.appTransitionFinished();
                        }
                    }
                });
            }
        };
    }

    public void setTopAppHidesStatusBar(boolean hidesStatusBar) {
        StatusBarManagerInternal statusbar = getStatusBarInternal();
        if (statusbar != null) {
            statusbar.setTopAppHidesStatusBar(hidesStatusBar);
        }
    }

    @Override // com.android.server.policy.BarController
    protected boolean skipAnimation() {
        return this.mWin.getAttrs().height == -1;
    }

    public WindowManagerInternal.AppTransitionListener getAppTransitionListener() {
        return this.mAppTransitionListener;
    }
}
