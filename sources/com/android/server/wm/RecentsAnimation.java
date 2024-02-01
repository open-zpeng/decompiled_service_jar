package com.android.server.wm;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.view.IRecentsAnimationRunner;
import com.android.server.wm.ActivityDisplay;
import com.android.server.wm.ActivityStack;
import com.android.server.wm.RecentsAnimationController;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class RecentsAnimation implements RecentsAnimationController.RecentsAnimationCallbacks, ActivityDisplay.OnStackOrderChangedListener {
    private final ActivityStartController mActivityStartController;
    private final WindowProcessController mCaller;
    private final ActivityDisplay mDefaultDisplay;
    private ActivityRecord mLaunchedTargetActivity;
    private final ComponentName mRecentsComponent;
    private final int mRecentsUid;
    private ActivityStack mRestoreTargetBehindStack;
    private final ActivityTaskManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private final int mTargetActivityType;
    private final Intent mTargetIntent;
    private final int mUserId;
    private final WindowManagerService mWindowManager;
    private static final String TAG = RecentsAnimation.class.getSimpleName();
    private static final boolean DEBUG = WindowManagerDebugConfig.DEBUG_RECENTS_ANIMATIONS;

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecentsAnimation(ActivityTaskManagerService atm, ActivityStackSupervisor stackSupervisor, ActivityStartController activityStartController, WindowManagerService wm, Intent targetIntent, ComponentName recentsComponent, int recentsUid, WindowProcessController caller) {
        int i;
        this.mService = atm;
        this.mStackSupervisor = stackSupervisor;
        this.mDefaultDisplay = this.mService.mRootActivityContainer.getDefaultDisplay();
        this.mActivityStartController = activityStartController;
        this.mWindowManager = wm;
        this.mTargetIntent = targetIntent;
        this.mRecentsComponent = recentsComponent;
        this.mRecentsUid = recentsUid;
        this.mCaller = caller;
        this.mUserId = atm.getCurrentUserId();
        if (targetIntent.getComponent() != null && recentsComponent.equals(targetIntent.getComponent())) {
            i = 3;
        } else {
            i = 2;
        }
        this.mTargetActivityType = i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void preloadRecentsActivity() {
        if (DEBUG) {
            String str = TAG;
            Slog.d(str, "Preload recents with " + this.mTargetIntent);
        }
        ActivityStack targetStack = this.mDefaultDisplay.getStack(0, this.mTargetActivityType);
        ActivityRecord targetActivity = getTargetActivity(targetStack);
        if (targetActivity != null) {
            if (targetActivity.visible || targetActivity.isTopRunningActivity()) {
                return;
            }
            if (targetActivity.attachedToProcess()) {
                targetActivity.ensureActivityConfiguration(0, false, true);
                if (DEBUG) {
                    String str2 = TAG;
                    Slog.d(str2, "Updated config=" + targetActivity.getConfiguration());
                }
            }
        } else {
            startRecentsActivityInBackground("preloadRecents");
            targetStack = this.mDefaultDisplay.getStack(0, this.mTargetActivityType);
            targetActivity = getTargetActivity(targetStack);
            if (targetActivity == null) {
                String str3 = TAG;
                Slog.w(str3, "Cannot start " + this.mTargetIntent);
                return;
            }
        }
        if (!targetActivity.attachedToProcess()) {
            if (DEBUG) {
                Slog.d(TAG, "Real start recents");
            }
            this.mStackSupervisor.startSpecificActivityLocked(targetActivity, false, false);
            if (targetActivity.mAppWindowToken != null) {
                targetActivity.mAppWindowToken.getDisplayContent().mUnknownAppVisibilityController.appRemovedOrHidden(targetActivity.mAppWindowToken);
            }
        }
        if (!targetActivity.isState(ActivityStack.ActivityState.STOPPING, ActivityStack.ActivityState.STOPPED)) {
            targetStack.addToStopping(targetActivity, true, true, "preloadRecents");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startRecentsActivity(IRecentsAnimationRunner recentsAnimationRunner) {
        if (DEBUG) {
            String str = TAG;
            Slog.d(str, "startRecentsActivity(): intent=" + this.mTargetIntent);
        }
        Trace.traceBegin(64L, "RecentsAnimation#startRecentsActivity");
        DisplayContent dc = this.mService.mRootActivityContainer.getDefaultDisplay().mDisplayContent;
        if (!this.mWindowManager.canStartRecentsAnimation()) {
            notifyAnimationCancelBeforeStart(recentsAnimationRunner);
            if (DEBUG) {
                String str2 = TAG;
                Slog.d(str2, "Can't start recents animation, nextAppTransition=" + dc.mAppTransition.getAppTransition());
                return;
            }
            return;
        }
        ActivityStack targetStack = this.mDefaultDisplay.getStack(0, this.mTargetActivityType);
        ActivityRecord targetActivity = getTargetActivity(targetStack);
        boolean hasExistingActivity = targetActivity != null;
        if (hasExistingActivity) {
            ActivityDisplay display = targetActivity.getDisplay();
            this.mRestoreTargetBehindStack = display.getStackAbove(targetStack);
            if (this.mRestoreTargetBehindStack == null) {
                notifyAnimationCancelBeforeStart(recentsAnimationRunner);
                if (DEBUG) {
                    String str3 = TAG;
                    Slog.d(str3, "No stack above target stack=" + targetStack);
                    return;
                }
                return;
            }
        }
        if (targetActivity == null || !targetActivity.visible) {
            this.mService.mRootActivityContainer.sendPowerHintForLaunchStartIfNeeded(true, targetActivity);
        }
        this.mStackSupervisor.getActivityMetricsLogger().notifyActivityLaunching(this.mTargetIntent);
        WindowProcessController windowProcessController = this.mCaller;
        if (windowProcessController != null) {
            windowProcessController.setRunningRecentsAnimation(true);
        }
        this.mWindowManager.deferSurfaceLayout();
        try {
            try {
                if (hasExistingActivity) {
                    this.mDefaultDisplay.moveStackBehindBottomMostVisibleStack(targetStack);
                    if (DEBUG) {
                        String str4 = TAG;
                        Slog.d(str4, "Moved stack=" + targetStack + " behind stack=" + this.mDefaultDisplay.getStackAbove(targetStack));
                    }
                    if (targetStack.topTask() != targetActivity.getTaskRecord()) {
                        targetStack.addTask(targetActivity.getTaskRecord(), true, "startRecentsActivity");
                    }
                } else {
                    startRecentsActivityInBackground("startRecentsActivity_noTargetActivity");
                    targetStack = this.mDefaultDisplay.getStack(0, this.mTargetActivityType);
                    targetActivity = getTargetActivity(targetStack);
                    this.mDefaultDisplay.moveStackBehindBottomMostVisibleStack(targetStack);
                    if (DEBUG) {
                        String str5 = TAG;
                        Slog.d(str5, "Moved stack=" + targetStack + " behind stack=" + this.mDefaultDisplay.getStackAbove(targetStack));
                    }
                    this.mWindowManager.prepareAppTransition(0, false);
                    this.mWindowManager.executeAppTransition();
                    if (DEBUG) {
                        String str6 = TAG;
                        Slog.d(str6, "Started intent=" + this.mTargetIntent);
                    }
                }
                ActivityRecord targetActivity2 = targetActivity;
                try {
                    targetActivity2.mLaunchTaskBehind = true;
                    this.mLaunchedTargetActivity = targetActivity2;
                    this.mWindowManager.cancelRecentsAnimationSynchronously(2, "startRecentsActivity");
                    this.mWindowManager.initializeRecentsAnimation(this.mTargetActivityType, recentsAnimationRunner, this, this.mDefaultDisplay.mDisplayId, this.mStackSupervisor.mRecentTasks.getRecentTaskIds());
                    this.mService.mRootActivityContainer.ensureActivitiesVisible(null, 0, true);
                    this.mStackSupervisor.getActivityMetricsLogger().notifyActivityLaunched(2, targetActivity2);
                    this.mDefaultDisplay.registerStackOrderChangedListener(this);
                    this.mWindowManager.continueSurfaceLayout();
                    Trace.traceEnd(64L);
                } catch (Exception e) {
                    e = e;
                    Slog.e(TAG, "Failed to start recents activity", e);
                    throw e;
                } catch (Throwable th) {
                    e = th;
                    this.mWindowManager.continueSurfaceLayout();
                    Trace.traceEnd(64L);
                    throw e;
                }
            } catch (Exception e2) {
                e = e2;
            }
        } catch (Throwable th2) {
            e = th2;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: finishAnimation */
    public void lambda$onAnimationFinished$1$RecentsAnimation(@RecentsAnimationController.ReorderMode final int reorderMode, final boolean sendUserLeaveHint) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (DEBUG) {
                    String str = TAG;
                    Slog.d(str, "onAnimationFinished(): controller=" + this.mWindowManager.getRecentsAnimationController() + " reorderMode=" + reorderMode);
                }
                this.mDefaultDisplay.unregisterStackOrderChangedListener(this);
                final RecentsAnimationController controller = this.mWindowManager.getRecentsAnimationController();
                if (controller == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (reorderMode != 0) {
                    this.mService.mRootActivityContainer.sendPowerHintForLaunchEndIfNeeded();
                }
                if (reorderMode == 1) {
                    this.mService.stopAppSwitches();
                }
                if (this.mCaller != null) {
                    this.mCaller.setRunningRecentsAnimation(false);
                }
                this.mWindowManager.inSurfaceTransaction(new Runnable() { // from class: com.android.server.wm.-$$Lambda$RecentsAnimation$reh_wG2afVmsOkGOZzt-QbWe4gE
                    @Override // java.lang.Runnable
                    public final void run() {
                        RecentsAnimation.this.lambda$finishAnimation$0$RecentsAnimation(reorderMode, sendUserLeaveHint, controller);
                    }
                });
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public /* synthetic */ void lambda$finishAnimation$0$RecentsAnimation(int reorderMode, boolean sendUserLeaveHint, RecentsAnimationController controller) {
        Trace.traceBegin(64L, "RecentsAnimation#onAnimationFinished_inSurfaceTransaction");
        this.mWindowManager.deferSurfaceLayout();
        try {
            try {
                this.mWindowManager.cleanupRecentsAnimation(reorderMode);
                ActivityStack targetStack = this.mDefaultDisplay.getStack(0, this.mTargetActivityType);
                ActivityRecord targetActivity = targetStack != null ? targetStack.isInStackLocked(this.mLaunchedTargetActivity) : null;
                if (DEBUG) {
                    String str = TAG;
                    Slog.d(str, "onAnimationFinished(): targetStack=" + targetStack + " targetActivity=" + targetActivity + " mRestoreTargetBehindStack=" + this.mRestoreTargetBehindStack);
                }
                if (targetActivity == null) {
                    return;
                }
                targetActivity.mLaunchTaskBehind = false;
                if (reorderMode == 1) {
                    this.mStackSupervisor.mNoAnimActivities.add(targetActivity);
                    if (sendUserLeaveHint) {
                        this.mStackSupervisor.mUserLeaving = true;
                        targetStack.moveTaskToFrontLocked(targetActivity.getTaskRecord(), true, null, targetActivity.appTimeTracker, "RecentsAnimation.onAnimationFinished()");
                    } else {
                        targetStack.moveToFront("RecentsAnimation.onAnimationFinished()");
                    }
                    if (DEBUG) {
                        ActivityStack topStack = getTopNonAlwaysOnTopStack();
                        if (topStack != targetStack) {
                            String str2 = TAG;
                            Slog.w(str2, "Expected target stack=" + targetStack + " to be top most but found stack=" + topStack);
                        }
                    }
                } else if (reorderMode != 2) {
                    if (!controller.shouldDeferCancelWithScreenshot() && !targetStack.isFocusedStackOnDisplay()) {
                        targetStack.ensureActivitiesVisibleLocked(null, 0, false);
                    }
                    return;
                } else {
                    ActivityDisplay display = targetActivity.getDisplay();
                    display.moveStackBehindStack(targetStack, this.mRestoreTargetBehindStack);
                    if (DEBUG) {
                        ActivityStack aboveTargetStack = this.mDefaultDisplay.getStackAbove(targetStack);
                        if (this.mRestoreTargetBehindStack != null && aboveTargetStack != this.mRestoreTargetBehindStack) {
                            String str3 = TAG;
                            Slog.w(str3, "Expected target stack=" + targetStack + " to restored behind stack=" + this.mRestoreTargetBehindStack + " but it is behind stack=" + aboveTargetStack);
                        }
                    }
                }
                this.mWindowManager.prepareAppTransition(0, false);
                this.mService.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
                this.mService.mRootActivityContainer.resumeFocusedStacksTopActivities();
                this.mWindowManager.executeAppTransition();
                this.mWindowManager.checkSplitScreenMinimizedChanged(true);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to clean up recents activity", e);
                throw e;
            }
        } finally {
            this.mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(64L);
        }
    }

    @Override // com.android.server.wm.RecentsAnimationController.RecentsAnimationCallbacks
    public void onAnimationFinished(@RecentsAnimationController.ReorderMode final int reorderMode, boolean runSychronously, final boolean sendUserLeaveHint) {
        if (runSychronously) {
            lambda$onAnimationFinished$1$RecentsAnimation(reorderMode, sendUserLeaveHint);
        } else {
            this.mService.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$RecentsAnimation$fjw2Vw5snMqEP-wvbelHZx-rg1Q
                @Override // java.lang.Runnable
                public final void run() {
                    RecentsAnimation.this.lambda$onAnimationFinished$1$RecentsAnimation(reorderMode, sendUserLeaveHint);
                }
            });
        }
    }

    @Override // com.android.server.wm.ActivityDisplay.OnStackOrderChangedListener
    public void onStackOrderChanged(ActivityStack stack) {
        RecentsAnimationController controller;
        if (DEBUG) {
            String str = TAG;
            Slog.d(str, "onStackOrderChanged(): stack=" + stack);
        }
        if (this.mDefaultDisplay.getIndexOf(stack) == -1 || !stack.shouldBeVisible(null) || (controller = this.mWindowManager.getRecentsAnimationController()) == null) {
            return;
        }
        DisplayContent dc = this.mService.mRootActivityContainer.getDefaultDisplay().mDisplayContent;
        dc.mBoundsAnimationController.setAnimationType(controller.shouldDeferCancelUntilNextTransition() ? 1 : 0);
        if ((!controller.isAnimatingTask(stack.getTaskStack().getTopChild()) || controller.isTargetApp(stack.getTopActivity().mAppWindowToken)) && controller.shouldDeferCancelUntilNextTransition()) {
            this.mWindowManager.prepareAppTransition(0, false);
            controller.setCancelOnNextTransitionStart();
            return;
        }
        this.mWindowManager.cancelRecentsAnimationSynchronously(0, "stackOrderChanged");
    }

    private void startRecentsActivityInBackground(String reason) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchActivityType(this.mTargetActivityType);
        options.setAvoidMoveToFront();
        this.mTargetIntent.addFlags(268500992);
        this.mActivityStartController.obtainStarter(this.mTargetIntent, reason).setCallingUid(this.mRecentsUid).setCallingPackage(this.mRecentsComponent.getPackageName()).setActivityOptions(new SafeActivityOptions(options)).setMayWait(this.mUserId).execute();
    }

    static void notifyAnimationCancelBeforeStart(IRecentsAnimationRunner recentsAnimationRunner) {
        try {
            recentsAnimationRunner.onAnimationCanceled(false);
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

    private ActivityRecord getTargetActivity(ActivityStack targetStack) {
        if (targetStack == null) {
            return null;
        }
        for (int i = targetStack.getChildCount() - 1; i >= 0; i--) {
            TaskRecord task = targetStack.getChildAt(i);
            if (task.userId == this.mUserId && task.getBaseIntent().getComponent().equals(this.mTargetIntent.getComponent())) {
                return task.getTopActivity();
            }
        }
        return null;
    }
}
