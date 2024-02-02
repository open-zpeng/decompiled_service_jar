package com.android.server.am;

import android.content.ComponentName;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.view.IRecentsAnimationRunner;
import com.android.server.am.ActivityDisplay;
import com.android.server.wm.RecentsAnimationController;
import com.android.server.wm.WindowManagerService;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RecentsAnimation implements RecentsAnimationController.RecentsAnimationCallbacks, ActivityDisplay.OnStackOrderChangedListener {
    private static final boolean DEBUG = false;
    private static final String TAG = RecentsAnimation.class.getSimpleName();
    private final ActivityStartController mActivityStartController;
    private AssistDataRequester mAssistDataRequester;
    private final int mCallingPid;
    private final ActivityDisplay mDefaultDisplay;
    private ActivityStack mRestoreTargetBehindStack;
    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private int mTargetActivityType;
    private final UserController mUserController;
    private final WindowManagerService mWindowManager;

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecentsAnimation(ActivityManagerService am, ActivityStackSupervisor stackSupervisor, ActivityStartController activityStartController, WindowManagerService wm, UserController userController, int callingPid) {
        this.mService = am;
        this.mStackSupervisor = stackSupervisor;
        this.mDefaultDisplay = stackSupervisor.getDefaultDisplay();
        this.mActivityStartController = activityStartController;
        this.mWindowManager = wm;
        this.mUserController = userController;
        this.mCallingPid = callingPid;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:15:0x004a  */
    /* JADX WARN: Removed duplicated region for block: B:16:0x004c  */
    /* JADX WARN: Removed duplicated region for block: B:19:0x0051  */
    /* JADX WARN: Removed duplicated region for block: B:36:0x00e9  */
    /* JADX WARN: Removed duplicated region for block: B:38:0x00ed A[Catch: all -> 0x010a, Exception -> 0x010f, TryCatch #7 {Exception -> 0x010f, all -> 0x010a, blocks: (B:31:0x00ba, B:38:0x00ed, B:40:0x00fc, B:47:0x0115), top: B:65:0x00ba }] */
    /* JADX WARN: Removed duplicated region for block: B:47:0x0115 A[Catch: all -> 0x010a, Exception -> 0x010f, TRY_LEAVE, TryCatch #7 {Exception -> 0x010f, all -> 0x010a, blocks: (B:31:0x00ba, B:38:0x00ed, B:40:0x00fc, B:47:0x0115), top: B:65:0x00ba }] */
    /* JADX WARN: Removed duplicated region for block: B:66:0x0085 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void startRecentsActivity(android.content.Intent r29, android.view.IRecentsAnimationRunner r30, android.content.ComponentName r31, int r32, android.app.IAssistDataReceiver r33) {
        /*
            Method dump skipped, instructions count: 457
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.RecentsAnimation.startRecentsActivity(android.content.Intent, android.view.IRecentsAnimationRunner, android.content.ComponentName, int, android.app.IAssistDataReceiver):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void finishAnimation(@RecentsAnimationController.ReorderMode final int reorderMode) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (this.mAssistDataRequester != null) {
                    this.mAssistDataRequester.cancel();
                    this.mAssistDataRequester = null;
                }
                this.mDefaultDisplay.unregisterStackOrderChangedListener(this);
                if (this.mWindowManager.getRecentsAnimationController() == null) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (reorderMode != 0) {
                    this.mStackSupervisor.sendPowerHintForLaunchEndIfNeeded();
                }
                this.mService.setRunningRemoteAnimation(this.mCallingPid, false);
                this.mWindowManager.inSurfaceTransaction(new Runnable() { // from class: com.android.server.am.-$$Lambda$RecentsAnimation$Zj0-OCbCxGCeVS-UKZSU82iNyXc
                    @Override // java.lang.Runnable
                    public final void run() {
                        RecentsAnimation.lambda$finishAnimation$0(RecentsAnimation.this, reorderMode);
                    }
                });
                ActivityManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public static /* synthetic */ void lambda$finishAnimation$0(RecentsAnimation recentsAnimation, int reorderMode) {
        Trace.traceBegin(64L, "RecentsAnimation#onAnimationFinished_inSurfaceTransaction");
        recentsAnimation.mWindowManager.deferSurfaceLayout();
        try {
            try {
                recentsAnimation.mWindowManager.cleanupRecentsAnimation(reorderMode);
                ActivityStack targetStack = recentsAnimation.mDefaultDisplay.getStack(0, recentsAnimation.mTargetActivityType);
                ActivityRecord targetActivity = targetStack != null ? targetStack.getTopActivity() : null;
                if (targetActivity == null) {
                    return;
                }
                targetActivity.mLaunchTaskBehind = false;
                if (reorderMode == 1) {
                    recentsAnimation.mStackSupervisor.mNoAnimActivities.add(targetActivity);
                    targetStack.moveToFront("RecentsAnimation.onAnimationFinished()");
                } else if (reorderMode != 2) {
                    return;
                } else {
                    ActivityDisplay display = targetActivity.getDisplay();
                    display.moveStackBehindStack(targetStack, recentsAnimation.mRestoreTargetBehindStack);
                }
                recentsAnimation.mWindowManager.prepareAppTransition(0, false);
                recentsAnimation.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
                recentsAnimation.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                recentsAnimation.mWindowManager.executeAppTransition();
                recentsAnimation.mWindowManager.checkSplitScreenMinimizedChanged(true);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to clean up recents activity", e);
                throw e;
            }
        } finally {
            recentsAnimation.mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(64L);
        }
    }

    @Override // com.android.server.wm.RecentsAnimationController.RecentsAnimationCallbacks
    public void onAnimationFinished(@RecentsAnimationController.ReorderMode final int reorderMode, boolean runSychronously) {
        if (runSychronously) {
            finishAnimation(reorderMode);
        } else {
            this.mService.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$RecentsAnimation$1UHkVDWv9CBej8qt8TWQICpmP60
                @Override // java.lang.Runnable
                public final void run() {
                    RecentsAnimation.this.finishAnimation(reorderMode);
                }
            });
        }
    }

    @Override // com.android.server.am.ActivityDisplay.OnStackOrderChangedListener
    public void onStackOrderChanged() {
        this.mWindowManager.cancelRecentsAnimationSynchronously(0, "stackOrderChanged");
    }

    private void notifyAnimationCancelBeforeStart(IRecentsAnimationRunner recentsAnimationRunner) {
        try {
            recentsAnimationRunner.onAnimationCanceled();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to cancel recents animation before start", e);
        }
    }

    private ActivityStack getTopNonAlwaysOnTopStack() {
        for (int i = this.mDefaultDisplay.getChildCount() - 1; i >= 0; i--) {
            ActivityStack s = this.mDefaultDisplay.getChildAt(i);
            if (!s.getWindowConfiguration().isAlwaysOnTop()) {
                return s;
            }
        }
        return null;
    }

    private ActivityRecord getTargetActivity(ActivityStack targetStack, ComponentName component) {
        if (targetStack == null) {
            return null;
        }
        for (int i = targetStack.getChildCount() - 1; i >= 0; i--) {
            TaskRecord task = (TaskRecord) targetStack.getChildAt(i);
            if (task.getBaseIntent().getComponent().equals(component)) {
                return task.getTopActivity();
            }
        }
        return targetStack.getTopActivity();
    }
}
