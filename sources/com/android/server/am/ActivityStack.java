package com.android.server.am;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.ResultInfo;
import android.app.WindowConfiguration;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.DestroyActivityItem;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.app.servertransaction.WindowVisibilityItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.BatteryStatsImpl;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.ActivityStackSupervisor;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.wm.ConfigurationContainer;
import com.android.server.wm.StackWindowController;
import com.android.server.wm.StackWindowListener;
import com.android.server.wm.WindowManagerService;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.xpWindowManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
/* JADX INFO: Access modifiers changed from: package-private */
@SuppressLint({"all"})
/* loaded from: classes.dex */
public class ActivityStack<T extends StackWindowController> extends ConfigurationContainer implements StackWindowListener {
    static final int DESTROY_ACTIVITIES_MSG = 105;
    private static final int DESTROY_TIMEOUT = 10000;
    static final int DESTROY_TIMEOUT_MSG = 102;
    static final int FINISH_AFTER_PAUSE = 1;
    static final int FINISH_AFTER_VISIBLE = 2;
    static final int FINISH_IMMEDIATELY = 0;
    private static final int FIT_WITHIN_BOUNDS_DIVIDER = 3;
    static final int LAUNCH_TICK = 500;
    static final int LAUNCH_TICK_MSG = 103;
    private static final int MAX_STOPPING_TO_FORCE = 3;
    private static final int PAUSE_TIMEOUT = 500;
    static final int PAUSE_TIMEOUT_MSG = 101;
    @VisibleForTesting
    protected static final int REMOVE_TASK_MODE_DESTROYING = 0;
    static final int REMOVE_TASK_MODE_MOVING = 1;
    static final int REMOVE_TASK_MODE_MOVING_TO_TOP = 2;
    private static final boolean SHOW_APP_STARTING_PREVIEW = true;
    private static final int STOP_TIMEOUT = 11000;
    static final int STOP_TIMEOUT_MSG = 104;
    private static final String TAG = "ActivityManager";
    private static final String TAG_ADD_REMOVE = "ActivityManager";
    private static final String TAG_APP = "ActivityManager";
    private static final String TAG_CLEANUP = "ActivityManager";
    private static final String TAG_CONTAINERS = "ActivityManager";
    private static final String TAG_PAUSE = "ActivityManager";
    private static final String TAG_RELEASE = "ActivityManager";
    private static final String TAG_RESULTS = "ActivityManager";
    private static final String TAG_SAVED_STATE = "ActivityManager";
    private static final String TAG_STACK = "ActivityManager";
    private static final String TAG_STATES = "ActivityManager";
    private static final String TAG_SWITCH = "ActivityManager";
    private static final String TAG_TASKS = "ActivityManager";
    private static final String TAG_TRANSITION = "ActivityManager";
    private static final String TAG_USER_LEAVING = "ActivityManager";
    private static final String TAG_VISIBILITY = "ActivityManager";
    private static final long TRANSLUCENT_CONVERSION_TIMEOUT = 2000;
    static final int TRANSLUCENT_TIMEOUT_MSG = 106;
    boolean mConfigWillChange;
    int mCurrentUser;
    int mDisplayId;
    final Handler mHandler;
    final ActivityManagerService mService;
    final int mStackId;
    protected final ActivityStackSupervisor mStackSupervisor;
    private boolean mTopActivityOccludesKeyguard;
    private ActivityRecord mTopDismissingKeyguardActivity;
    private boolean mUpdateBoundsDeferred;
    private boolean mUpdateBoundsDeferredCalled;
    T mWindowContainerController;
    private final WindowManagerService mWindowManager;
    private final ArrayList<TaskRecord> mTaskHistory = new ArrayList<>();
    final ArrayList<ActivityRecord> mLRUActivities = new ArrayList<>();
    ActivityRecord mPausingActivity = null;
    ActivityRecord mLastPausedActivity = null;
    ActivityRecord mLastNoHistoryActivity = null;
    ActivityRecord mResumedActivity = null;
    ActivityRecord mTranslucentActivityWaiting = null;
    ArrayList<ActivityRecord> mUndrawnActivitiesBelowTopTranslucent = new ArrayList<>();
    boolean mForceHidden = false;
    private final Rect mDeferredBounds = new Rect();
    private final Rect mDeferredTaskBounds = new Rect();
    private final Rect mDeferredTaskInsetBounds = new Rect();
    private final SparseArray<Rect> mTmpBounds = new SparseArray<>();
    private final SparseArray<Rect> mTmpInsetBounds = new SparseArray<>();
    private final Rect mTmpRect2 = new Rect();
    private final ActivityOptions mTmpOptions = ActivityOptions.makeBasic();
    private final ArrayList<ActivityRecord> mTmpActivities = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public enum ActivityState {
        INITIALIZING,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public int getChildCount() {
        return this.mTaskHistory.size();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public ConfigurationContainer getChildAt(int index) {
        return this.mTaskHistory.get(index);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public ConfigurationContainer getParent() {
        return getDisplay();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public void onParentChanged() {
        super.onParentChanged();
        this.mStackSupervisor.updateUIDsPresentOnDisplay();
    }

    /* loaded from: classes.dex */
    private static class ScheduleDestroyArgs {
        final ProcessRecord mOwner;
        final String mReason;

        ScheduleDestroyArgs(ProcessRecord owner, String reason) {
            this.mOwner = owner;
            this.mReason = reason;
        }
    }

    /* loaded from: classes.dex */
    private class ActivityStackHandler extends Handler {
        ActivityStackHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 101:
                    ActivityRecord r = (ActivityRecord) msg.obj;
                    Slog.w("ActivityManager", "Activity pause timeout for " + r);
                    synchronized (ActivityStack.this.mService) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            if (r.app != null) {
                                ActivityManagerService activityManagerService = ActivityStack.this.mService;
                                ProcessRecord processRecord = r.app;
                                long j = r.pauseTime;
                                activityManagerService.logAppTooSlow(processRecord, j, "pausing " + r);
                            }
                            ActivityStack.this.activityPausedLocked(r.appToken, true);
                        } finally {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                case 102:
                    ActivityRecord r2 = (ActivityRecord) msg.obj;
                    Slog.w("ActivityManager", "Activity destroy timeout for " + r2);
                    synchronized (ActivityStack.this.mService) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            ActivityStack.this.activityDestroyedLocked((IBinder) (r2 != null ? r2.appToken : null), "destroyTimeout");
                        } finally {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                case 103:
                    ActivityRecord r3 = (ActivityRecord) msg.obj;
                    synchronized (ActivityStack.this.mService) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            if (r3.continueLaunchTickingLocked()) {
                                ActivityManagerService activityManagerService2 = ActivityStack.this.mService;
                                ProcessRecord processRecord2 = r3.app;
                                long j2 = r3.launchTickTime;
                                activityManagerService2.logAppTooSlow(processRecord2, j2, "launching " + r3);
                            }
                        } finally {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                case 104:
                    ActivityRecord r4 = (ActivityRecord) msg.obj;
                    Slog.w("ActivityManager", "Activity stop timeout for " + r4);
                    synchronized (ActivityStack.this.mService) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            if (r4.isInHistory()) {
                                r4.activityStoppedLocked(null, null, null);
                            }
                        } finally {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                case 105:
                    ScheduleDestroyArgs args = (ScheduleDestroyArgs) msg.obj;
                    synchronized (ActivityStack.this.mService) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            ActivityStack.this.destroyActivitiesLocked(args.mOwner, args.mReason);
                        } finally {
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                case 106:
                    synchronized (ActivityStack.this.mService) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            ActivityStack.this.notifyActivityDrawnLocked(null);
                        } finally {
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int numActivities() {
        int count = 0;
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            count += this.mTaskHistory.get(taskNdx).mActivities.size();
        }
        return count;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack(ActivityDisplay display, int stackId, ActivityStackSupervisor supervisor, int windowingMode, int activityType, boolean onTop) {
        this.mStackSupervisor = supervisor;
        this.mService = supervisor.mService;
        this.mHandler = new ActivityStackHandler(this.mService.mHandler.getLooper());
        this.mWindowManager = this.mService.mWindowManager;
        this.mStackId = stackId;
        this.mCurrentUser = this.mService.mUserController.getCurrentUserId();
        this.mTmpRect2.setEmpty();
        this.mDisplayId = display.mDisplayId;
        setActivityType(activityType);
        setWindowingMode(windowingMode);
        this.mWindowContainerController = createStackWindowController(display.mDisplayId, onTop, this.mTmpRect2);
        postAddToDisplay(display, this.mTmpRect2.isEmpty() ? null : this.mTmpRect2, onTop);
    }

    T createStackWindowController(int displayId, boolean onTop, Rect outBounds) {
        return (T) new StackWindowController(this.mStackId, this, displayId, onTop, outBounds, this.mStackSupervisor.mWindowManager);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public T getWindowContainerController() {
        return this.mWindowContainerController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onActivityStateChanged(ActivityRecord record, ActivityState state, String reason) {
        if (record == this.mResumedActivity && state != ActivityState.RESUMED) {
            setResumedActivity(null, reason + " - onActivityStateChanged");
        }
        if (state == ActivityState.RESUMED) {
            if (ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.v("ActivityManager", "set resumed activity to:" + record + " reason:" + reason);
            }
            setResumedActivity(record, reason + " - onActivityStateChanged");
            this.mService.setResumedActivityUncheckLocked(record, reason);
            this.mStackSupervisor.mRecentTasks.add(record.getTask());
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        int prevWindowingMode = getWindowingMode();
        super.onConfigurationChanged(newParentConfig);
        ActivityDisplay display = getDisplay();
        if (display != null && prevWindowingMode != getWindowingMode()) {
            display.onStackWindowingModeChanged(this);
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void setWindowingMode(int windowingMode) {
        setWindowingMode(windowingMode, false, false, false, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowingMode(int preferredWindowingMode, boolean animate, boolean showRecents, boolean enteringSplitScreenMode, boolean deferEnsuringVisibility) {
        Rect bounds;
        boolean creating = this.mWindowContainerController == null;
        int currentMode = getWindowingMode();
        ActivityDisplay display = getDisplay();
        TaskRecord topTask = topTask();
        ActivityStack splitScreenStack = display.getSplitScreenPrimaryStack();
        this.mTmpOptions.setLaunchWindowingMode(preferredWindowingMode);
        int windowingMode = creating ? preferredWindowingMode : display.resolveWindowingMode(null, this.mTmpOptions, topTask, getActivityType());
        if (splitScreenStack == this && windowingMode == 4) {
            windowingMode = 1;
        }
        boolean alreadyInSplitScreenMode = display.hasSplitScreenPrimaryStack();
        boolean sendNonResizeableNotification = !enteringSplitScreenMode;
        if (alreadyInSplitScreenMode && windowingMode == 1 && sendNonResizeableNotification && isActivityTypeStandardOrUndefined()) {
            boolean preferredSplitScreen = preferredWindowingMode == 3 || preferredWindowingMode == 4;
            if (preferredSplitScreen || creating) {
                this.mService.mTaskChangeNotificationController.notifyActivityDismissingDockedStack();
                display.getSplitScreenPrimaryStack().setWindowingMode(1, false, false, false, true);
            }
        }
        if (currentMode == windowingMode) {
            return;
        }
        WindowManagerService wm = this.mService.mWindowManager;
        ActivityRecord topActivity = getTopActivity();
        if (sendNonResizeableNotification && windowingMode != 1 && topActivity != null && topActivity.isNonResizableOrForcedResizable() && !topActivity.noDisplay) {
            String packageName = topActivity.appInfo.packageName;
            this.mService.mTaskChangeNotificationController.notifyActivityForcedResizable(topTask.taskId, 1, packageName);
        }
        wm.deferSurfaceLayout();
        if (!animate && topActivity != null) {
            try {
                this.mStackSupervisor.mNoAnimActivities.add(topActivity);
            } catch (Throwable th) {
                if (showRecents && !alreadyInSplitScreenMode && this.mDisplayId == 0 && windowingMode == 3) {
                    ActivityStack recentStack = display.getOrCreateStack(4, 3, true);
                    recentStack.moveToFront("setWindowingMode");
                    this.mService.mWindowManager.showRecentApps();
                }
                wm.continueSurfaceLayout();
                throw th;
            }
        }
        super.setWindowingMode(windowingMode);
        if (!creating) {
            if (windowingMode == 2 || currentMode == 2) {
                throw new IllegalArgumentException("Changing pinned windowing mode not currently supported");
            }
            if (windowingMode == 3 && splitScreenStack != null) {
                throw new IllegalArgumentException("Setting primary split-screen windowing mode while there is already one isn't currently supported");
            }
            this.mTmpRect2.setEmpty();
            if (windowingMode != 1) {
                this.mWindowContainerController.getRawBounds(this.mTmpRect2);
                if (windowingMode == 5 && topTask != null && (bounds = topTask().getLaunchBounds()) != null) {
                    this.mTmpRect2.set(bounds);
                }
            }
            if (!Objects.equals(getOverrideBounds(), this.mTmpRect2)) {
                resize(this.mTmpRect2, null, null);
            }
            if (showRecents && !alreadyInSplitScreenMode && this.mDisplayId == 0 && windowingMode == 3) {
                ActivityStack recentStack2 = display.getOrCreateStack(4, 3, true);
                recentStack2.moveToFront("setWindowingMode");
                this.mService.mWindowManager.showRecentApps();
            }
            wm.continueSurfaceLayout();
            if (!deferEnsuringVisibility) {
                this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, true);
                this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                return;
            }
            return;
        }
        if (showRecents && !alreadyInSplitScreenMode && this.mDisplayId == 0 && windowingMode == 3) {
            ActivityStack recentStack3 = display.getOrCreateStack(4, 3, true);
            recentStack3.moveToFront("setWindowingMode");
            this.mService.mWindowManager.showRecentApps();
        }
        wm.continueSurfaceLayout();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public boolean isCompatible(int windowingMode, int activityType) {
        if (activityType == 0) {
            activityType = 1;
        }
        ActivityDisplay display = getDisplay();
        if (display != null && activityType == 1 && windowingMode == 0) {
            windowingMode = display.getWindowingMode();
        }
        return super.isCompatible(windowingMode, activityType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(ActivityDisplay activityDisplay, boolean onTop) {
        removeFromDisplay();
        this.mTmpRect2.setEmpty();
        this.mWindowContainerController.reparent(activityDisplay.mDisplayId, this.mTmpRect2, onTop);
        postAddToDisplay(activityDisplay, this.mTmpRect2.isEmpty() ? null : this.mTmpRect2, onTop);
        adjustFocusToNextFocusableStack("reparent", true);
        this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
        this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
    }

    private void postAddToDisplay(ActivityDisplay activityDisplay, Rect bounds, boolean onTop) {
        this.mDisplayId = activityDisplay.mDisplayId;
        setBounds(bounds);
        onParentChanged();
        activityDisplay.addChild(this, onTop ? Integer.MAX_VALUE : Integer.MIN_VALUE);
        if (inSplitScreenPrimaryWindowingMode()) {
            this.mStackSupervisor.resizeDockedStackLocked(getOverrideBounds(), null, null, null, null, true);
        }
    }

    private void removeFromDisplay() {
        ActivityDisplay display = getDisplay();
        if (display != null) {
            display.removeChild(this);
        }
        this.mDisplayId = -1;
    }

    void remove() {
        if (this.mStackSupervisor.isPhoneStack(this)) {
            return;
        }
        removeFromDisplay();
        this.mWindowContainerController.removeContainer();
        this.mWindowContainerController = null;
        onParentChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getDisplay() {
        return this.mStackSupervisor.getActivityDisplay(this.mDisplayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStackDockedModeBounds(Rect currentTempTaskBounds, Rect outStackBounds, Rect outTempTaskBounds, boolean ignoreVisibility) {
        this.mWindowContainerController.getStackDockedModeBounds(currentTempTaskBounds, outStackBounds, outTempTaskBounds, ignoreVisibility);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareFreezingTaskBounds() {
        this.mWindowContainerController.prepareFreezingTaskBounds();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getWindowContainerBounds(Rect outBounds) {
        if (this.mWindowContainerController != null) {
            this.mWindowContainerController.getBounds(outBounds);
        } else {
            outBounds.setEmpty();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getBoundsForNewConfiguration(Rect outBounds) {
        this.mWindowContainerController.getBoundsForNewConfiguration(outBounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildWindowContainerAtTop(TaskRecord child) {
        this.mWindowContainerController.positionChildAtTop(child.getWindowContainerController(), true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean deferScheduleMultiWindowModeChanged() {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deferUpdateBounds() {
        if (!this.mUpdateBoundsDeferred) {
            this.mUpdateBoundsDeferred = true;
            this.mUpdateBoundsDeferredCalled = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void continueUpdateBounds() {
        Rect rect;
        Rect rect2;
        boolean wasDeferred = this.mUpdateBoundsDeferred;
        this.mUpdateBoundsDeferred = false;
        if (wasDeferred && this.mUpdateBoundsDeferredCalled) {
            if (!this.mDeferredBounds.isEmpty()) {
                rect = this.mDeferredBounds;
            } else {
                rect = null;
            }
            if (!this.mDeferredTaskBounds.isEmpty()) {
                rect2 = this.mDeferredTaskBounds;
            } else {
                rect2 = null;
            }
            resize(rect, rect2, this.mDeferredTaskInsetBounds.isEmpty() ? null : this.mDeferredTaskInsetBounds);
        }
    }

    boolean updateBoundsAllowed(Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds) {
        if (this.mUpdateBoundsDeferred) {
            if (bounds != null) {
                this.mDeferredBounds.set(bounds);
            } else {
                this.mDeferredBounds.setEmpty();
            }
            if (tempTaskBounds != null) {
                this.mDeferredTaskBounds.set(tempTaskBounds);
            } else {
                this.mDeferredTaskBounds.setEmpty();
            }
            if (tempTaskInsetBounds != null) {
                this.mDeferredTaskInsetBounds.set(tempTaskInsetBounds);
            } else {
                this.mDeferredTaskInsetBounds.setEmpty();
            }
            this.mUpdateBoundsDeferredCalled = true;
            return false;
        }
        return true;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public int setBounds(Rect bounds) {
        return super.setBounds(!inMultiWindowMode() ? null : bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivityLocked() {
        return topRunningActivityLocked(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getAllRunningVisibleActivitiesLocked(ArrayList<ActivityRecord> outActivities) {
        outActivities.clear();
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            this.mTaskHistory.get(taskNdx).getAllRunningVisibleActivitiesLocked(outActivities);
        }
    }

    private ActivityRecord topRunningActivityLocked(boolean focusableOnly) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ActivityRecord r = this.mTaskHistory.get(taskNdx).topRunningActivityLocked();
            if (r != null && (!focusableOnly || r.isFocusable())) {
                return r;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningNonOverlayTaskActivity() {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && !r.mTaskOverlay) {
                    return r;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningNonDelayedActivityLocked(ActivityRecord notTop) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && !r.delayedResume && r != notTop && r.okToShowLocked()) {
                    return r;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityRecord topRunningActivityLocked(IBinder token, int taskId) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            if (task.taskId != taskId) {
                ArrayList<ActivityRecord> activities = task.mActivities;
                for (int i = activities.size() - 1; i >= 0; i--) {
                    ActivityRecord r = activities.get(i);
                    if (!r.finishing && token != r.appToken && r.okToShowLocked()) {
                        return r;
                    }
                }
                continue;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getTopActivity() {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ActivityRecord r = this.mTaskHistory.get(taskNdx).getTopActivity();
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final TaskRecord topTask() {
        int size = this.mTaskHistory.size();
        if (size > 0) {
            return this.mTaskHistory.get(size - 1);
        }
        return null;
    }

    private TaskRecord bottomTask() {
        if (this.mTaskHistory.isEmpty()) {
            return null;
        }
        return this.mTaskHistory.get(0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord taskForIdLocked(int id) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            if (task.taskId == id) {
                return task;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord isInStackLocked(IBinder token) {
        ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return isInStackLocked(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord isInStackLocked(ActivityRecord r) {
        if (r == null) {
            return null;
        }
        TaskRecord task = r.getTask();
        ActivityStack stack = r.getStack();
        if (stack == null || !task.mActivities.contains(r) || !this.mTaskHistory.contains(task)) {
            return null;
        }
        if (stack != this) {
            Slog.w("ActivityManager", "Illegal state! task does not point to stack it is in.");
        }
        return r;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInStackLocked(TaskRecord task) {
        return this.mTaskHistory.contains(task);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUidPresent(int uid) {
        Iterator<TaskRecord> it = this.mTaskHistory.iterator();
        while (it.hasNext()) {
            TaskRecord task = it.next();
            Iterator<ActivityRecord> it2 = task.mActivities.iterator();
            while (it2.hasNext()) {
                ActivityRecord r = it2.next();
                if (r.getUid() == uid) {
                    return true;
                }
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getPresentUIDs(IntArray presentUIDs) {
        Iterator<TaskRecord> it = this.mTaskHistory.iterator();
        while (it.hasNext()) {
            TaskRecord task = it.next();
            Iterator<ActivityRecord> it2 = task.mActivities.iterator();
            while (it2.hasNext()) {
                ActivityRecord r = it2.next();
                presentUIDs.add(r.getUid());
            }
        }
    }

    final void removeActivitiesFromLRUListLocked(TaskRecord task) {
        Iterator<ActivityRecord> it = task.mActivities.iterator();
        while (it.hasNext()) {
            ActivityRecord r = it.next();
            this.mLRUActivities.remove(r);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean updateLRUListLocked(ActivityRecord r) {
        boolean hadit = this.mLRUActivities.remove(r);
        this.mLRUActivities.add(r);
        return hadit;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean isHomeOrRecentsStack() {
        return isActivityTypeHome() || isActivityTypeRecents();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean isOnHomeDisplay() {
        return this.mDisplayId == 0;
    }

    private boolean returnsToHomeStack() {
        return (inMultiWindowMode() || this.mTaskHistory.isEmpty() || !this.mTaskHistory.get(0).returnsToHomeStack()) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveToFront(String reason) {
        moveToFront(reason, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveToFront(String reason, TaskRecord task) {
        ActivityStack topFullScreenStack;
        if (!isAttached()) {
            return;
        }
        ActivityDisplay display = getDisplay();
        if (inSplitScreenSecondaryWindowingMode() && (topFullScreenStack = display.getTopStackInWindowingMode(1)) != null) {
            ActivityStack primarySplitScreenStack = display.getSplitScreenPrimaryStack();
            if (display.getIndexOf(topFullScreenStack) > display.getIndexOf(primarySplitScreenStack)) {
                primarySplitScreenStack.moveToFront(reason + " splitScreenToTop");
            }
        }
        if (!isActivityTypeHome() && returnsToHomeStack()) {
            ActivityStackSupervisor activityStackSupervisor = this.mStackSupervisor;
            activityStackSupervisor.moveHomeStackToFront(reason + " returnToHome");
        }
        display.positionChildAtTop(this);
        this.mStackSupervisor.setFocusStackUnchecked(reason, this);
        if (task != null) {
            insertTaskAtTop(task, null);
        }
    }

    void moveToBack(String reason, TaskRecord task) {
        if (!isAttached()) {
            return;
        }
        if (getWindowingMode() == 3) {
            setWindowingMode(1);
        }
        getDisplay().positionChildAtBottom(this);
        this.mStackSupervisor.setFocusStackUnchecked(reason, getDisplay().getTopStack());
        if (task != null) {
            insertTaskAtBottom(task);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusable() {
        ActivityRecord r = topRunningActivityLocked();
        return this.mStackSupervisor.isFocusable(this, r != null && r.isFocusable());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean isAttached() {
        return getParent() != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void findTaskLocked(ActivityRecord target, ActivityStackSupervisor.FindTaskResult result) {
        ActivityInfo info;
        int userId;
        boolean taskIsDocument;
        Uri taskDocumentData;
        Uri taskDocumentData2;
        ActivityStack<T> activityStack = this;
        Intent intent = target.intent;
        ActivityInfo info2 = target.info;
        ComponentName cls = intent.getComponent();
        if (info2.targetActivity != null) {
            cls = new ComponentName(info2.packageName, info2.targetActivity);
        }
        int userId2 = UserHandle.getUserId(info2.applicationInfo.uid);
        boolean z = false;
        boolean isDocument = (intent != null) & intent.isDocument();
        Uri documentData = isDocument ? intent.getData() : null;
        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
            Slog.d("ActivityManager", "Looking for task of " + target + " in " + activityStack);
        }
        int taskNdx = activityStack.mTaskHistory.size() - 1;
        while (taskNdx >= 0) {
            TaskRecord task = activityStack.mTaskHistory.get(taskNdx);
            if (task.voiceSession != null) {
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityManager", "Skipping " + task + ": voice session");
                }
            } else if (task.userId != userId2) {
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityManager", "Skipping " + task + ": different user");
                }
            } else {
                ActivityRecord r = task.getTopActivity(z);
                if (r == null || r.finishing || r.userId != userId2) {
                    info = info2;
                    userId = userId2;
                } else if (r.launchMode == 3) {
                    info = info2;
                    userId = userId2;
                } else if (!r.hasCompatibleActivityType(target)) {
                    if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                        Slog.d("ActivityManager", "Skipping " + task + ": mismatch activity type");
                    }
                } else {
                    Intent taskIntent = task.intent;
                    Intent affinityIntent = task.affinityIntent;
                    if (taskIntent != null && taskIntent.isDocument()) {
                        taskIsDocument = true;
                        taskDocumentData = taskIntent.getData();
                    } else if (affinityIntent != null && affinityIntent.isDocument()) {
                        taskIsDocument = true;
                        taskDocumentData = affinityIntent.getData();
                    } else {
                        taskIsDocument = false;
                        taskDocumentData = null;
                    }
                    Uri taskDocumentData3 = taskDocumentData;
                    if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                        StringBuilder sb = new StringBuilder();
                        userId = userId2;
                        sb.append("Comparing existing cls=");
                        sb.append(taskIntent.getComponent().flattenToShortString());
                        sb.append("/aff=");
                        sb.append(r.getTask().rootAffinity);
                        sb.append(" to new cls=");
                        sb.append(intent.getComponent().flattenToShortString());
                        sb.append("/aff=");
                        sb.append(info2.taskAffinity);
                        Slog.d("ActivityManager", sb.toString());
                    } else {
                        userId = userId2;
                    }
                    if (taskIntent == null || taskIntent.getComponent() == null || taskIntent.getComponent().compareTo(cls) != 0) {
                        info = info2;
                        taskDocumentData2 = taskDocumentData3;
                    } else {
                        taskDocumentData2 = taskDocumentData3;
                        if (Objects.equals(documentData, taskDocumentData2)) {
                            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                                Slog.d("ActivityManager", "Found matching class!");
                            }
                            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                                Slog.d("ActivityManager", "For Intent " + intent + " bringing to top: " + r.intent);
                            }
                            result.r = r;
                            result.matchedByRootAffinity = false;
                            return;
                        }
                        info = info2;
                    }
                    if (affinityIntent == null || affinityIntent.getComponent() == null || affinityIntent.getComponent().compareTo(cls) != 0 || !Objects.equals(documentData, taskDocumentData2)) {
                        if (isDocument || taskIsDocument || result.r != null || task.rootAffinity == null) {
                            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                                Slog.d("ActivityManager", "Not a match: " + task);
                            }
                        } else if (task.rootAffinity.equals(target.taskAffinity)) {
                            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                                Slog.d("ActivityManager", "Found matching affinity candidate!");
                            }
                            result.r = r;
                            result.matchedByRootAffinity = true;
                        }
                        taskNdx--;
                        userId2 = userId;
                        info2 = info;
                        activityStack = this;
                        z = false;
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                            Slog.d("ActivityManager", "Found matching class!");
                        }
                        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                            Slog.d("ActivityManager", "For Intent " + intent + " bringing to top: " + r.intent);
                        }
                        result.r = r;
                        result.matchedByRootAffinity = false;
                        return;
                    }
                }
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityManager", "Skipping " + task + ": mismatch root " + r);
                }
                taskNdx--;
                userId2 = userId;
                info2 = info;
                activityStack = this;
                z = false;
            }
            info = info2;
            userId = userId2;
            taskNdx--;
            userId2 = userId;
            info2 = info;
            activityStack = this;
            z = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord findActivityLocked(Intent intent, ActivityInfo info, boolean compareIntentFilters) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }
        int userId = UserHandle.getUserId(info.applicationInfo.uid);
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.okToShowLocked() && !r.finishing && r.userId == userId) {
                    if (compareIntentFilters) {
                        if (r.intent.filterEquals(intent)) {
                            return r;
                        }
                    } else if (r.intent.getComponent().equals(cls)) {
                        return r;
                    }
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void switchUserLocked(int userId) {
        if (this.mCurrentUser == userId) {
            return;
        }
        this.mCurrentUser = userId;
        int index = this.mTaskHistory.size();
        int i = 0;
        while (i < index) {
            TaskRecord task = this.mTaskHistory.get(i);
            if (task.okToShowLocked()) {
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityManager", "switchUserLocked: stack=" + getStackId() + " moving " + task + " to top");
                }
                this.mTaskHistory.remove(i);
                this.mTaskHistory.add(task);
                index--;
            } else {
                i++;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void minimalResumeActivityLocked(ActivityRecord r) {
        if (ActivityManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityManager", "Moving to RESUMED: " + r + " (starting new instance) callers=" + Debug.getCallers(5));
        }
        r.setState(ActivityState.RESUMED, "minimalResumeActivityLocked");
        r.completeResumeLocked();
        if (ActivityManagerDebugConfig.DEBUG_SAVED_STATE) {
            Slog.i("ActivityManager", "Launch completed; removing icicle of " + r.icicle);
        }
    }

    private void clearLaunchTime(ActivityRecord r) {
        if (!this.mStackSupervisor.mWaitingActivityLaunched.isEmpty()) {
            this.mStackSupervisor.removeTimeoutsForActivityLocked(r);
            this.mStackSupervisor.scheduleIdleTimeoutLocked(r);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void awakeFromSleepingLocked() {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                activities.get(activityNdx).setSleeping(false);
            }
        }
        if (this.mPausingActivity != null) {
            Slog.d("ActivityManager", "awakeFromSleepingLocked: previously pausing activity didn't pause");
            activityPausedLocked(this.mPausingActivity.appToken, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateActivityApplicationInfoLocked(ApplicationInfo aInfo) {
        String packageName = aInfo.packageName;
        int userId = UserHandle.getUserId(aInfo.uid);
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            List<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord ar = activities.get(activityNdx);
                if (userId == ar.userId && packageName.equals(ar.packageName)) {
                    ar.updateApplicationInfo(aInfo);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkReadyForSleep() {
        if (shouldSleepActivities() && goToSleepIfPossible(false)) {
            this.mStackSupervisor.checkReadyForSleepLocked(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean goToSleepIfPossible(boolean shuttingDown) {
        boolean shouldSleep = true;
        if (this.mResumedActivity != null) {
            if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                Slog.v("ActivityManager", "Sleep needs to pause " + this.mResumedActivity);
            }
            if (ActivityManagerDebugConfig.DEBUG_USER_LEAVING) {
                Slog.v("ActivityManager", "Sleep => pause with userLeaving=false");
            }
            startPausingLocked(false, true, null, false);
            shouldSleep = false;
        } else if (this.mPausingActivity != null) {
            if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                Slog.v("ActivityManager", "Sleep still waiting to pause " + this.mPausingActivity);
            }
            shouldSleep = false;
        }
        if (!shuttingDown) {
            if (containsActivityFromStack(this.mStackSupervisor.mStoppingActivities)) {
                if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityManager", "Sleep still need to stop " + this.mStackSupervisor.mStoppingActivities.size() + " activities");
                }
                this.mStackSupervisor.scheduleIdleLocked();
                shouldSleep = false;
            }
            if (containsActivityFromStack(this.mStackSupervisor.mGoingToSleepActivities)) {
                if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityManager", "Sleep still need to sleep " + this.mStackSupervisor.mGoingToSleepActivities.size() + " activities");
                }
                shouldSleep = false;
            }
        }
        if (shouldSleep) {
            goToSleep();
        }
        return shouldSleep;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void goToSleep() {
        ensureActivitiesVisibleLocked(null, 0, false);
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.isState(ActivityState.STOPPING, ActivityState.STOPPED, ActivityState.PAUSED, ActivityState.PAUSING)) {
                    r.setSleeping(true);
                }
            }
        }
    }

    private boolean containsActivityFromStack(List<ActivityRecord> rs) {
        for (ActivityRecord r : rs) {
            if (r.getStack() == this) {
                return true;
            }
        }
        return false;
    }

    private void schedulePauseTimeout(ActivityRecord r) {
        Message msg = this.mHandler.obtainMessage(101);
        msg.obj = r;
        r.pauseTime = SystemClock.uptimeMillis();
        this.mHandler.sendMessageDelayed(msg, 500L);
        if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
            Slog.v("ActivityManager", "Waiting for pause to complete...");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean startPausingLocked(boolean userLeaving, boolean uiSleeping, ActivityRecord resuming, boolean pauseImmediately) {
        if (shouldKeepResumedActivities(this.mResumedActivity, resuming)) {
            return false;
        }
        if (this.mPausingActivity != null) {
            Slog.wtf("ActivityManager", "Going to pause when pause is already pending for " + this.mPausingActivity + " state=" + this.mPausingActivity.getState());
            if (!shouldSleepActivities()) {
                completePauseLocked(false, resuming);
            }
        }
        ActivityRecord prev = this.mResumedActivity;
        if (prev == null) {
            if (resuming == null) {
                Slog.wtf("ActivityManager", "Trying to pause when nothing is resumed");
                this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
            }
            return false;
        } else if (prev == resuming) {
            Slog.wtf("ActivityManager", "Trying to pause activity that is in process of being resumed");
            return false;
        } else {
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "Moving to PAUSING: " + prev);
            } else if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                Slog.v("ActivityManager", "Start pausing: " + prev);
            }
            this.mPausingActivity = prev;
            this.mLastPausedActivity = prev;
            this.mLastNoHistoryActivity = ((prev.intent.getFlags() & 1073741824) == 0 && (prev.info.flags & 128) == 0) ? null : prev;
            prev.setState(ActivityState.PAUSING, "startPausingLocked");
            prev.getTask().touchActiveTime();
            clearLaunchTime(prev);
            this.mStackSupervisor.getActivityMetricsLogger().stopFullyDrawnTraceIfNeeded();
            this.mService.updateCpuStats();
            if (prev.app != null && prev.app.thread != null) {
                if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityManager", "Enqueueing pending pause: " + prev);
                }
                try {
                    int i = prev.userId;
                    int identityHashCode = System.identityHashCode(prev);
                    String str = prev.shortComponentName;
                    EventLogTags.writeAmPauseActivity(i, identityHashCode, str, "userLeaving=" + userLeaving);
                    this.mService.updateUsageStats(prev, false);
                    if (resuming != null && resuming.isActivityTypeHome()) {
                        this.mService.setHomeState(resuming.realActivity, 3);
                    }
                    int flags = resuming != null ? ActivityInfoManager.getActivityFlags(resuming.intent) : 0;
                    this.mService.getLifecycleManager().scheduleTransaction(prev.app.thread, (IBinder) prev.appToken, PauseActivityItem.obtain(prev.finishing, userLeaving, prev.configChangeFlags, pauseImmediately, flags));
                } catch (Exception e) {
                    Slog.w("ActivityManager", "Exception thrown during pause", e);
                    this.mPausingActivity = null;
                    this.mLastPausedActivity = null;
                    this.mLastNoHistoryActivity = null;
                }
            } else {
                this.mPausingActivity = null;
                this.mLastPausedActivity = null;
                this.mLastNoHistoryActivity = null;
            }
            if (!uiSleeping && !this.mService.isSleepingOrShuttingDownLocked()) {
                this.mStackSupervisor.acquireLaunchWakelock();
            }
            if (this.mPausingActivity != null) {
                if (!uiSleeping) {
                    prev.pauseKeyDispatchingLocked();
                } else if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityManager", "Key dispatch not paused for screen off");
                }
                if (pauseImmediately) {
                    completePauseLocked(false, resuming);
                    return false;
                }
                schedulePauseTimeout(prev);
                return true;
            }
            if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                Slog.v("ActivityManager", "Activity not running, resuming next.");
            }
            if (resuming == null) {
                this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void activityPausedLocked(IBinder token, boolean timeout) {
        if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
            Slog.v("ActivityManager", "Activity paused: token=" + token + ", timeout=" + timeout);
        }
        ActivityRecord r = isInStackLocked(token);
        if (r != null) {
            this.mHandler.removeMessages(101, r);
            if (this.mPausingActivity == r) {
                if (ActivityManagerDebugConfig.DEBUG_STATES) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Moving to PAUSED: ");
                    sb.append(r);
                    sb.append(timeout ? " (due to timeout)" : " (pause complete)");
                    Slog.v("ActivityManager", sb.toString());
                }
                this.mService.mWindowManager.deferSurfaceLayout();
                try {
                    completePauseLocked(true, null);
                    return;
                } finally {
                    this.mService.mWindowManager.continueSurfaceLayout();
                }
            }
            Object[] objArr = new Object[4];
            objArr[0] = Integer.valueOf(r.userId);
            objArr[1] = Integer.valueOf(System.identityHashCode(r));
            objArr[2] = r.shortComponentName;
            objArr[3] = this.mPausingActivity != null ? this.mPausingActivity.shortComponentName : "(none)";
            EventLog.writeEvent((int) EventLogTags.AM_FAILED_TO_PAUSE, objArr);
            if (r.isState(ActivityState.PAUSING)) {
                r.setState(ActivityState.PAUSED, "activityPausedLocked");
                if (r.finishing) {
                    if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                        Slog.v("ActivityManager", "Executing finish of failed to pause activity: " + r);
                    }
                    finishCurrentActivityLocked(r, 2, false, "activityPausedLocked");
                }
            }
        }
        this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
    }

    private void completePauseLocked(boolean resumeNext, ActivityRecord resuming) {
        ActivityRecord prev = this.mPausingActivity;
        if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
            Slog.v("ActivityManager", "Complete pause: " + prev);
        }
        if (prev != null) {
            prev.setWillCloseOrEnterPip(false);
            boolean wasStopping = prev.isState(ActivityState.STOPPING);
            prev.setState(ActivityState.PAUSED, "completePausedLocked");
            if (prev.finishing) {
                if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityManager", "Executing finish of activity: " + prev);
                }
                prev = finishCurrentActivityLocked(prev, 2, false, "completedPausedLocked");
            } else if (prev.app != null) {
                if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityManager", "Enqueue pending stop if needed: " + prev + " wasStopping=" + wasStopping + " visible=" + prev.visible);
                }
                if (this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.remove(prev) && (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_PAUSE)) {
                    Slog.v("ActivityManager", "Complete pause, no longer waiting: " + prev);
                }
                if (prev.deferRelaunchUntilPaused) {
                    if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                        Slog.v("ActivityManager", "Re-launching after pause: " + prev);
                    }
                    prev.relaunchActivityLocked(false, prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    prev.setState(ActivityState.STOPPING, "completePausedLocked");
                } else if (!prev.visible || shouldSleepOrShutDownActivities()) {
                    prev.setDeferHidingClient(false);
                    addToStopping(prev, true, false);
                }
            } else {
                if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityManager", "App died during pause, not stopping: " + prev);
                }
                prev = null;
            }
            if (prev != null) {
                prev.stopFreezingScreenLocked(true);
            }
            this.mPausingActivity = null;
        }
        if (resumeNext) {
            ActivityStack topStack = this.mStackSupervisor.getFocusedStack();
            if (!topStack.shouldSleepOrShutDownActivities()) {
                this.mStackSupervisor.resumeFocusedStackTopActivityLocked(topStack, prev, null);
            } else {
                checkReadyForSleep();
                ActivityRecord top = topStack.topRunningActivityLocked();
                if (top == null || (prev != null && top != prev)) {
                    this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                }
            }
        }
        if (prev != null) {
            prev.resumeKeyDispatchingLocked();
            if (prev.app != null && prev.cpuTimeAtResume > 0 && this.mService.mBatteryStatsService.isOnBattery()) {
                long diff = this.mService.mProcessCpuTracker.getCpuTimeForPid(prev.app.pid) - prev.cpuTimeAtResume;
                if (diff > 0) {
                    BatteryStatsImpl bsi = this.mService.mBatteryStatsService.getActiveStatistics();
                    synchronized (bsi) {
                        BatteryStatsImpl.Uid.Proc ps = bsi.getProcessStatsLocked(prev.info.applicationInfo.uid, prev.info.packageName);
                        if (ps != null) {
                            ps.addForegroundTimeLocked(diff);
                        }
                    }
                }
            }
            prev.cpuTimeAtResume = 0L;
        }
        if (this.mStackSupervisor.mAppVisibilitiesChangedSinceLastPause || (isAttached() && getDisplay() != null && getDisplay().hasPinnedStack())) {
            this.mService.mTaskChangeNotificationController.notifyTaskStackChanged();
            this.mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }
        this.mStackSupervisor.ensureActivitiesVisibleLocked(resuming, 0, false);
    }

    void addToStopping(ActivityRecord r, boolean scheduleIdle, boolean idleDelayed) {
        if (!this.mStackSupervisor.mStoppingActivities.contains(r)) {
            this.mStackSupervisor.mStoppingActivities.add(r);
            if (!this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.isEmpty() && !this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(r)) {
                if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                    Slog.i("ActivityManager", "adding to waiting visible activity=" + r + " existing=" + this.mStackSupervisor.mActivitiesWaitingForVisibleActivity);
                }
                this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.add(r);
            }
        }
        boolean z = true;
        if (this.mStackSupervisor.mStoppingActivities.size() <= 3 && (!r.frontOfTask || this.mTaskHistory.size() > 1)) {
            z = false;
        }
        boolean forceIdle = z;
        if (scheduleIdle || forceIdle) {
            if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                StringBuilder sb = new StringBuilder();
                sb.append("Scheduling idle now: forceIdle=");
                sb.append(forceIdle);
                sb.append("immediate=");
                sb.append(!idleDelayed);
                Slog.v("ActivityManager", sb.toString());
            }
            if (!idleDelayed) {
                this.mStackSupervisor.scheduleIdleLocked();
                return;
            } else {
                this.mStackSupervisor.scheduleIdleTimeoutLocked(r);
                return;
            }
        }
        checkReadyForSleep();
    }

    @VisibleForTesting
    boolean isStackTranslucent(ActivityRecord starting) {
        if (!isAttached() || this.mForceHidden) {
            return true;
        }
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing && ((r.visibleIgnoringKeyguard || r == starting) && (r.fullscreen || r.hasWallpaper))) {
                    return false;
                }
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopStackOnDisplay() {
        ActivityDisplay display = getDisplay();
        if (display != null) {
            return display.isTopStack(this);
        }
        xpLogger.i("ActivityManager", "isTopStackOnDisplay display is null");
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopActivityVisible() {
        ActivityRecord topActivity = getTopActivity();
        return topActivity != null && topActivity.visible;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldBeVisible(ActivityRecord starting) {
        if (!isAttached() || this.mForceHidden) {
            return false;
        }
        if (this.mStackSupervisor.isFocusedStack(this) || this.mStackSupervisor.isPhoneStack(this)) {
            return true;
        }
        ActivityRecord top = topRunningActivityLocked();
        if (top == null && isInStackLocked(starting) == null && !isTopStackOnDisplay()) {
            return false;
        }
        ActivityDisplay display = getDisplay();
        boolean gotSplitScreenStack = false;
        boolean gotOpaqueSplitScreenPrimary = false;
        boolean gotOpaqueSplitScreenSecondary = false;
        int windowingMode = getWindowingMode();
        boolean isAssistantType = isActivityTypeAssistant();
        for (int i = display.getChildCount() - 1; i >= 0; i--) {
            ActivityStack other = display.getChildAt(i);
            if (other == this) {
                return true;
            }
            int otherWindowingMode = other.getWindowingMode();
            if (otherWindowingMode == 1) {
                int activityType = other.getActivityType();
                if (windowingMode == 3 && (activityType == 2 || (activityType == 4 && this.mWindowManager.getRecentsAnimationController() != null))) {
                    return true;
                }
                if (!other.isStackTranslucent(starting)) {
                    return false;
                }
            } else {
                if (otherWindowingMode == 3 && !gotOpaqueSplitScreenPrimary) {
                    gotSplitScreenStack = true;
                    gotOpaqueSplitScreenPrimary = !other.isStackTranslucent(starting);
                    if (windowingMode == 3 && gotOpaqueSplitScreenPrimary) {
                        return false;
                    }
                } else if (otherWindowingMode == 4 && !gotOpaqueSplitScreenSecondary) {
                    gotSplitScreenStack = true;
                    gotOpaqueSplitScreenSecondary = !other.isStackTranslucent(starting);
                    if (windowingMode == 4 && gotOpaqueSplitScreenSecondary) {
                        return false;
                    }
                }
                if (gotOpaqueSplitScreenPrimary && gotOpaqueSplitScreenSecondary) {
                    return false;
                }
                if (isAssistantType && gotSplitScreenStack) {
                    return false;
                }
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int rankTaskLayers(int baseLayer) {
        int layer = 0;
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            ActivityRecord r = task.topRunningActivityLocked();
            if (r == null || r.finishing || !r.visible) {
                task.mLayerRank = -1;
            } else {
                task.mLayerRank = layer + baseLayer;
                layer++;
            }
        }
        return layer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges, boolean preserveWindows) {
        ensureActivitiesVisibleLocked(starting, configChanges, preserveWindows, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges, boolean preserveWindows, boolean notifyClients) {
        ActivityRecord top;
        boolean z;
        boolean aboveTop;
        boolean stackShouldBeVisible;
        boolean behindFullscreenActivity;
        boolean resumeNextActivity;
        boolean isTopNotPinnedStack;
        int taskNdx;
        int configChanges2;
        boolean behindFullscreenActivity2;
        ArrayList<ActivityRecord> activities;
        TaskRecord task;
        boolean z2;
        boolean z3;
        boolean visibleIgnoringKeyguard;
        ActivityRecord r;
        int activityNdx;
        ActivityRecord activityRecord = starting;
        boolean z4 = notifyClients;
        boolean z5 = false;
        this.mTopActivityOccludesKeyguard = false;
        this.mTopDismissingKeyguardActivity = null;
        this.mStackSupervisor.getKeyguardController().beginActivityVisibilityUpdate();
        try {
            top = topRunningActivityLocked();
            if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityManager", "ensureActivitiesVisible behind " + top + " configChanges=0x" + Integer.toHexString(configChanges));
            }
            if (top != null) {
                checkTranslucentActivityWaiting(top);
            }
            z = true;
            aboveTop = top != null;
            stackShouldBeVisible = shouldBeVisible(starting);
            behindFullscreenActivity = !stackShouldBeVisible;
            resumeNextActivity = this.mStackSupervisor.isFocusedStack(this) && isInStackLocked(starting) == null;
            isTopNotPinnedStack = isAttached() && getDisplay().isTopNotPinnedStack(this);
            taskNdx = this.mTaskHistory.size() - 1;
            configChanges2 = configChanges;
        } catch (Throwable th) {
            th = th;
        }
        while (true) {
            int taskNdx2 = taskNdx;
            if (taskNdx2 < 0) {
                break;
            }
            try {
                TaskRecord task2 = this.mTaskHistory.get(taskNdx2);
                ArrayList<ActivityRecord> activities2 = task2.mActivities;
                int activityNdx2 = activities2.size() - 1;
                boolean resumeNextActivity2 = resumeNextActivity;
                int configChanges3 = configChanges2;
                while (true) {
                    int activityNdx3 = activityNdx2;
                    if (activityNdx3 < 0) {
                        break;
                    }
                    try {
                        ActivityRecord r2 = activities2.get(activityNdx3);
                        if (!r2.finishing) {
                            boolean isTop = r2 == top ? z : z5;
                            if (!aboveTop || isTop) {
                                boolean visibleIgnoringKeyguard2 = r2.shouldBeVisibleIgnoringKeyguard(behindFullscreenActivity);
                                r2.visibleIgnoringKeyguard = visibleIgnoringKeyguard2;
                                if (isTop && isTopNotPinnedStack) {
                                    z5 = z;
                                }
                                boolean reallyVisible = checkKeyguardVisibility(r2, visibleIgnoringKeyguard2, z5);
                                if (visibleIgnoringKeyguard2) {
                                    if (stackShouldBeVisible) {
                                        z = false;
                                    }
                                    behindFullscreenActivity2 = updateBehindFullscreen(z, behindFullscreenActivity, r2);
                                } else {
                                    behindFullscreenActivity2 = behindFullscreenActivity;
                                }
                                if (reallyVisible) {
                                    if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                                        StringBuilder sb = new StringBuilder();
                                        visibleIgnoringKeyguard = visibleIgnoringKeyguard2;
                                        sb.append("Make visible? ");
                                        sb.append(r2);
                                        sb.append(" finishing=");
                                        sb.append(r2.finishing);
                                        sb.append(" state=");
                                        sb.append(r2.getState());
                                        Slog.v("ActivityManager", sb.toString());
                                    } else {
                                        visibleIgnoringKeyguard = visibleIgnoringKeyguard2;
                                    }
                                    if (r2 != activityRecord && z4) {
                                        r2.ensureActivityConfiguration(0, preserveWindows, true);
                                    }
                                    if (r2.app != null && r2.app.thread != null) {
                                        if (r2.visible) {
                                            if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                                                Slog.v("ActivityManager", "Skipping: already visible at " + r2);
                                            }
                                            if (r2.mClientVisibilityDeferred && z4) {
                                                r2.makeClientVisible();
                                            }
                                            if (r2.handleAlreadyVisible()) {
                                                resumeNextActivity2 = false;
                                                r = r2;
                                                activities = activities2;
                                                task = task2;
                                                z2 = true;
                                                z3 = false;
                                                configChanges3 |= r.configChangeFlags;
                                            }
                                        } else {
                                            r2.makeVisibleIfNeeded(activityRecord, z4);
                                        }
                                        r = r2;
                                        activities = activities2;
                                        activityNdx = activityNdx3;
                                        task = task2;
                                        z2 = true;
                                        z3 = false;
                                        activityNdx3 = activityNdx;
                                        configChanges3 |= r.configChangeFlags;
                                    }
                                    z3 = false;
                                    r = r2;
                                    activities = activities2;
                                    activityNdx = activityNdx3;
                                    task = task2;
                                    if (!makeVisibleAndRestartIfNeeded(activityRecord, configChanges3, isTop, resumeNextActivity2, r)) {
                                        z2 = true;
                                    } else if (activityNdx >= activities.size()) {
                                        z2 = true;
                                        activityNdx3 = activities.size() - 1;
                                        configChanges3 |= r.configChangeFlags;
                                    } else {
                                        z2 = true;
                                        resumeNextActivity2 = false;
                                    }
                                    activityNdx3 = activityNdx;
                                    configChanges3 |= r.configChangeFlags;
                                } else {
                                    activities = activities2;
                                    task = task2;
                                    z2 = true;
                                    z3 = false;
                                    if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                                        Slog.v("ActivityManager", "Make invisible? " + r2 + " finishing=" + r2.finishing + " state=" + r2.getState() + " stackShouldBeVisible=" + stackShouldBeVisible + " behindFullscreenActivity=" + behindFullscreenActivity2 + " mLaunchTaskBehind=" + r2.mLaunchTaskBehind);
                                    }
                                    if (!r2.isActivityTypeHome() || !xpWindowManager.isDesktopHome()) {
                                        makeInvisible(r2);
                                    }
                                    activityNdx3 = activityNdx3;
                                }
                                behindFullscreenActivity = behindFullscreenActivity2;
                                aboveTop = false;
                                activityNdx2 = activityNdx3 - 1;
                                activities2 = activities;
                                z = z2;
                                task2 = task;
                                z5 = z3;
                                activityRecord = starting;
                                z4 = notifyClients;
                            }
                        }
                        z3 = z5;
                        activities = activities2;
                        task = task2;
                        z2 = z;
                        activityNdx2 = activityNdx3 - 1;
                        activities2 = activities;
                        z = z2;
                        task2 = task;
                        z5 = z3;
                        activityRecord = starting;
                        z4 = notifyClients;
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
                boolean z6 = z5;
                TaskRecord task3 = task2;
                boolean z7 = z;
                int windowingMode = getWindowingMode();
                if (windowingMode == 5) {
                    behindFullscreenActivity = true;
                } else if (isActivityTypeHome()) {
                    if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                        Slog.v("ActivityManager", "Home task: at " + task3 + " stackShouldBeVisible=" + stackShouldBeVisible + " behindFullscreenActivity=" + behindFullscreenActivity);
                    }
                    behindFullscreenActivity = true;
                }
                taskNdx = taskNdx2 - 1;
                z = z7;
                resumeNextActivity = resumeNextActivity2;
                configChanges2 = configChanges3;
                z5 = z6;
                activityRecord = starting;
                z4 = notifyClients;
            } catch (Throwable th3) {
                th = th3;
            }
            th = th3;
            this.mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
            throw th;
        }
        if (this.mTranslucentActivityWaiting != null && this.mUndrawnActivitiesBelowTopTranslucent.isEmpty()) {
            notifyActivityDrawnLocked(null);
        }
        this.mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            this.mTaskHistory.get(taskNdx).addStartingWindowsForVisibleActivities(taskSwitch);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean topActivityOccludesKeyguard() {
        return this.mTopActivityOccludesKeyguard;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resizeStackWithLaunchBounds() {
        return inPinnedWindowingMode();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public boolean supportsSplitScreenWindowingMode() {
        TaskRecord topTask = topTask();
        return super.supportsSplitScreenWindowingMode() && (topTask == null || topTask.supportsSplitScreenWindowingMode());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean affectedBySplitScreenResize() {
        int windowingMode;
        return (!supportsSplitScreenWindowingMode() || (windowingMode = getWindowingMode()) == 5 || windowingMode == 2) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getTopDismissingKeyguardActivity() {
        return this.mTopDismissingKeyguardActivity;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkKeyguardVisibility(ActivityRecord r, boolean shouldBeVisible, boolean isTop) {
        int displayId = this.mDisplayId != -1 ? this.mDisplayId : 0;
        boolean keyguardOrAodShowing = this.mStackSupervisor.getKeyguardController().isKeyguardOrAodShowing(displayId);
        boolean keyguardLocked = this.mStackSupervisor.getKeyguardController().isKeyguardLocked();
        boolean showWhenLocked = r.canShowWhenLocked();
        boolean dismissKeyguard = r.hasDismissKeyguardWindows();
        if (shouldBeVisible) {
            if (dismissKeyguard && this.mTopDismissingKeyguardActivity == null) {
                this.mTopDismissingKeyguardActivity = r;
            }
            if (isTop) {
                this.mTopActivityOccludesKeyguard |= showWhenLocked;
            }
            boolean canShowWithKeyguard = canShowWithInsecureKeyguard() && this.mStackSupervisor.getKeyguardController().canDismissKeyguard();
            if (canShowWithKeyguard) {
                return true;
            }
        }
        if (keyguardOrAodShowing) {
            return shouldBeVisible && this.mStackSupervisor.getKeyguardController().canShowActivityWhileKeyguardShowing(r, dismissKeyguard);
        } else if (keyguardLocked) {
            return shouldBeVisible && this.mStackSupervisor.getKeyguardController().canShowWhileOccluded(dismissKeyguard, showWhenLocked);
        } else {
            return shouldBeVisible;
        }
    }

    private boolean canShowWithInsecureKeyguard() {
        ActivityDisplay activityDisplay = getDisplay();
        if (activityDisplay == null) {
            throw new IllegalStateException("Stack is not attached to any display, stackId=" + this.mStackId);
        }
        int flags = activityDisplay.mDisplay.getFlags();
        return (flags & 32) != 0;
    }

    private void checkTranslucentActivityWaiting(ActivityRecord top) {
        if (this.mTranslucentActivityWaiting != top) {
            this.mUndrawnActivitiesBelowTopTranslucent.clear();
            if (this.mTranslucentActivityWaiting != null) {
                notifyActivityDrawnLocked(null);
                this.mTranslucentActivityWaiting = null;
            }
            this.mHandler.removeMessages(106);
        }
    }

    private boolean makeVisibleAndRestartIfNeeded(ActivityRecord starting, int configChanges, boolean isTop, boolean andResume, ActivityRecord r) {
        if (isTop || !r.visible) {
            if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityManager", "Start and freeze screen for " + r);
            }
            if (r != starting) {
                r.startFreezingScreenLocked(r.app, configChanges);
            }
            if (!r.visible || r.mLaunchTaskBehind) {
                if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                    Slog.v("ActivityManager", "Starting and making visible: " + r);
                }
                r.setVisible(true);
            }
            if (r != starting) {
                this.mStackSupervisor.startSpecificActivityLocked(r, andResume, false);
                return true;
            }
        }
        return false;
    }

    private void makeInvisible(ActivityRecord r) {
        if (!r.visible) {
            if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityManager", "Already invisible: " + r);
                return;
            }
            return;
        }
        if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v("ActivityManager", "Making invisible: " + r + " " + r.getState());
        }
        try {
            boolean canEnterPictureInPicture = r.checkEnterPictureInPictureState("makeInvisible", true);
            boolean deferHidingClient = canEnterPictureInPicture && !r.isState(ActivityState.STOPPING, ActivityState.STOPPED, ActivityState.PAUSED);
            r.setDeferHidingClient(deferHidingClient);
            r.setVisible(false);
            switch (r.getState()) {
                case STOPPING:
                case STOPPED:
                    if (r.app != null && r.app.thread != null) {
                        if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                            Slog.v("ActivityManager", "Scheduling invisibility: " + r);
                        }
                        this.mService.getLifecycleManager().scheduleTransaction(r.app.thread, (IBinder) r.appToken, (ClientTransactionItem) WindowVisibilityItem.obtain(false));
                    }
                    r.supportsEnterPipOnTaskSwitch = false;
                    return;
                case INITIALIZING:
                case RESUMED:
                case PAUSING:
                case PAUSED:
                    addToStopping(r, true, canEnterPictureInPicture);
                    return;
                default:
                    return;
            }
        } catch (Exception e) {
            Slog.w("ActivityManager", "Exception thrown making hidden: " + r.intent.getComponent(), e);
        }
    }

    private boolean updateBehindFullscreen(boolean stackInvisible, boolean behindFullscreenActivity, ActivityRecord r) {
        if (r.fullscreen) {
            if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityManager", "Fullscreen: at " + r + " stackInvisible=" + stackInvisible + " behindFullscreenActivity=" + behindFullscreenActivity);
            }
            return true;
        }
        return behindFullscreenActivity;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void convertActivityToTranslucent(ActivityRecord r) {
        this.mTranslucentActivityWaiting = r;
        this.mUndrawnActivitiesBelowTopTranslucent.clear();
        this.mHandler.sendEmptyMessageDelayed(106, TRANSLUCENT_CONVERSION_TIMEOUT);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOtherAppTimeTrackers(AppTimeTracker except) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.appTimeTracker != except) {
                    r.appTimeTracker = null;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityDrawnLocked(ActivityRecord r) {
        if (r == null || (this.mUndrawnActivitiesBelowTopTranslucent.remove(r) && this.mUndrawnActivitiesBelowTopTranslucent.isEmpty())) {
            ActivityRecord waitingActivity = this.mTranslucentActivityWaiting;
            this.mTranslucentActivityWaiting = null;
            this.mUndrawnActivitiesBelowTopTranslucent.clear();
            this.mHandler.removeMessages(106);
            if (waitingActivity != null) {
                this.mWindowManager.setWindowOpaque(waitingActivity.appToken, false);
                if (waitingActivity.app != null && waitingActivity.app.thread != null) {
                    try {
                        waitingActivity.app.thread.scheduleTranslucentConversionComplete(waitingActivity.appToken, r != null);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelInitializingActivities() {
        boolean z;
        ActivityRecord topActivity = topRunningActivityLocked();
        boolean aboveTop = true;
        boolean behindFullscreenActivity = false;
        if (!shouldBeVisible(null)) {
            aboveTop = false;
            behindFullscreenActivity = true;
        }
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (aboveTop) {
                    if (r == topActivity) {
                        aboveTop = false;
                    }
                    z = r.fullscreen;
                } else {
                    r.removeOrphanedStartingWindow(behindFullscreenActivity);
                    z = r.fullscreen;
                }
                behindFullscreenActivity |= z;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mService")
    public boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        if (this.mStackSupervisor.inResumeTopActivity) {
            return false;
        }
        try {
            this.mStackSupervisor.inResumeTopActivity = true;
            boolean result = resumeTopActivityInnerLocked(prev, options);
            ActivityRecord next = topRunningActivityLocked(true);
            if (next == null || !next.canTurnScreenOn()) {
                checkReadyForSleep();
            }
            return result;
        } finally {
            this.mStackSupervisor.inResumeTopActivity = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mService")
    public boolean resumeTopPhoneActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        if (this.mStackSupervisor.inResumeTopPhoneActivity) {
            return false;
        }
        try {
            this.mStackSupervisor.inResumeTopPhoneActivity = true;
            boolean result = resumeTopPhoneActivityInnerLocked(prev, options);
            ActivityRecord next = topRunningActivityLocked(true);
            if (next == null || !next.canTurnScreenOn()) {
                checkReadyForSleep();
            }
            return result;
        } finally {
            this.mStackSupervisor.inResumeTopPhoneActivity = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public ActivityRecord getResumedActivity() {
        return this.mResumedActivity;
    }

    private void setResumedActivity(ActivityRecord r, String reason) {
        if (this.mResumedActivity == r) {
            return;
        }
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.d("ActivityManager", "setResumedActivity stack:" + this + " + from: " + this.mResumedActivity + " to:" + r + " reason:" + reason);
        }
        this.mResumedActivity = r;
    }

    /* JADX WARN: Code restructure failed: missing block: B:254:0x050c, code lost:
        if (r22 == false) goto L219;
     */
    /* JADX WARN: Multi-variable type inference failed */
    @com.android.internal.annotations.GuardedBy("mService")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private boolean resumeTopActivityInnerLocked(com.android.server.am.ActivityRecord r26, android.app.ActivityOptions r27) {
        /*
            Method dump skipped, instructions count: 1960
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStack.resumeTopActivityInnerLocked(com.android.server.am.ActivityRecord, android.app.ActivityOptions):boolean");
    }

    @GuardedBy("mService")
    private boolean resumeTopPhoneActivityInnerLocked(ActivityRecord prev, ActivityOptions options) {
        if (this.mService.mBooting || this.mService.mBooted) {
            ActivityRecord next = topRunningActivityLocked(true);
            boolean hasRunningActivity = next != null;
            if (!hasRunningActivity || isAttached()) {
                if (!hasRunningActivity) {
                    Slog.d("ActivityManager", "resumeTopPhoneActivityInnerLocked No Activtiys");
                    return false;
                }
                next.delayedResume = false;
                if (this.mResumedActivity == next && next.isState(ActivityState.RESUMED)) {
                    executeAppTransition(options);
                    if (ActivityManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityManager", "resumeTopActivityLocked: Top activity resumed " + next);
                    }
                    if (ActivityManagerDebugConfig.DEBUG_STACK) {
                        this.mStackSupervisor.validateTopActivitiesLocked();
                    }
                    return false;
                }
                if (prev != null && prev != next && prev.finishing) {
                    prev.setVisibility(false);
                }
                if (next.app != null && next.app.thread != null) {
                    if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                        Slog.v("ActivityManager", "Resume running: " + next + " stopped=" + next.stopped + " visible=" + next.visible);
                    }
                    synchronized (this.mWindowManager.getWindowManagerLock()) {
                        if (!next.visible || next.stopped) {
                            next.setVisibility(true);
                        }
                        next.startLaunchTickingLocked();
                        ActivityRecord lastResumedActivity = this.mResumedActivity;
                        ActivityState lastState = next.getState();
                        this.mService.updateCpuStats();
                        if (ActivityManagerDebugConfig.DEBUG_STATES) {
                            Slog.v("ActivityManager", "Moving to RESUMED: " + next + " (in existing)");
                        }
                        next.setState(ActivityState.RESUMED, "resumeTopActivityInnerLocked");
                        this.mService.updateLruProcessLocked(next.app, true, null);
                        updateLRUListLocked(next);
                        this.mService.updateOomAdjLocked();
                        try {
                            ClientTransaction transaction = ClientTransaction.obtain(next.app.thread, next.appToken);
                            ArrayList<ResultInfo> a = next.results;
                            if (a != null) {
                                int N = a.size();
                                if (!next.finishing && N > 0) {
                                    if (ActivityManagerDebugConfig.DEBUG_RESULTS) {
                                        Slog.v("ActivityManager", "Delivering results to " + next + ": " + a);
                                    }
                                    transaction.addCallback(ActivityResultItem.obtain(a));
                                }
                            }
                            if (next.newIntents != null) {
                                transaction.addCallback(NewIntentItem.obtain(next.newIntents, false));
                            }
                            next.notifyAppResumed(next.stopped);
                            EventLog.writeEvent((int) EventLogTags.AM_RESUME_ACTIVITY, Integer.valueOf(next.userId), Integer.valueOf(System.identityHashCode(next)), Integer.valueOf(next.getTask().taskId), next.shortComponentName);
                            next.sleeping = false;
                            this.mService.getAppWarningsLocked().onResumeActivity(next);
                            this.mService.showAskCompatModeDialogLocked(next);
                            next.app.pendingUiClean = true;
                            next.app.forceProcessStateUpTo(this.mService.mTopProcessState);
                            next.clearOptionsLocked();
                            transaction.setLifecycleStateRequest(ResumeActivityItem.obtain(next.app.repProcState, this.mService.isNextTransitionForward()));
                            this.mService.getLifecycleManager().scheduleTransaction(transaction);
                            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                                Slog.d("ActivityManager", "resumeTopActivityLocked: Resumed " + next);
                            }
                        } catch (Exception e) {
                            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                                Slog.v("ActivityManager", "Resume failed; resetting state to " + lastState + ": " + next);
                            }
                            next.setState(lastState, "resumeTopActivityInnerLocked");
                            if (lastResumedActivity != null) {
                                lastResumedActivity.setState(ActivityState.RESUMED, "resumeTopActivityInnerLocked");
                            }
                            Slog.i("ActivityManager", "Restarting because process died: " + next);
                            if (!next.hasBeenLaunched) {
                                next.hasBeenLaunched = true;
                            } else {
                                next.showStartingWindow(null, false, false);
                            }
                            this.mStackSupervisor.startSpecificActivityLocked(next, true, false);
                            if (ActivityManagerDebugConfig.DEBUG_STACK) {
                                this.mStackSupervisor.validateTopActivitiesLocked();
                            }
                            return true;
                        }
                    }
                } else {
                    if (!next.hasBeenLaunched) {
                        next.hasBeenLaunched = true;
                    } else {
                        next.showStartingWindow(null, false, false);
                        if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                            Slog.v("ActivityManager", "Restarting: " + next);
                        }
                    }
                    if (ActivityManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityManager", "resumeTopPhoneActivityLocked: Restarting " + next);
                    }
                    this.mStackSupervisor.startSpecificActivityLocked(next, true, true);
                }
                return true;
            }
            return false;
        }
        return false;
    }

    private boolean resumeTopActivityInNextFocusableStack(ActivityRecord prev, ActivityOptions options, String reason) {
        if (adjustFocusToNextFocusableStack(reason)) {
            return this.mStackSupervisor.resumeFocusedStackTopActivityLocked(this.mStackSupervisor.getFocusedStack(), prev, null);
        }
        ActivityOptions.abort(options);
        if (ActivityManagerDebugConfig.DEBUG_STATES) {
            Slog.d("ActivityManager", "resumeTopActivityInNextFocusableStack: " + reason + ", go home");
        }
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            this.mStackSupervisor.validateTopActivitiesLocked();
        }
        return isOnHomeDisplay() && this.mStackSupervisor.resumeHomeStackTask(prev, reason);
    }

    private TaskRecord getNextTask(TaskRecord targetTask) {
        int index = this.mTaskHistory.indexOf(targetTask);
        if (index >= 0) {
            int numTasks = this.mTaskHistory.size();
            for (int i = index + 1; i < numTasks; i++) {
                TaskRecord task = this.mTaskHistory.get(i);
                if (task.userId == targetTask.userId) {
                    return task;
                }
            }
            return null;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getAdjustedPositionForTask(TaskRecord task, int suggestedPosition, ActivityRecord starting) {
        int maxPosition = this.mTaskHistory.size();
        if ((starting != null && starting.okToShowLocked()) || (starting == null && task.okToShowLocked())) {
            return Math.min(suggestedPosition, maxPosition);
        }
        while (maxPosition > 0) {
            TaskRecord tmpTask = this.mTaskHistory.get(maxPosition - 1);
            if (!this.mStackSupervisor.isCurrentProfileLocked(tmpTask.userId) || tmpTask.topRunningActivityLocked() == null) {
                break;
            }
            maxPosition--;
        }
        return Math.min(suggestedPosition, maxPosition);
    }

    private void insertTaskAtPosition(TaskRecord task, int position) {
        if (position >= this.mTaskHistory.size()) {
            insertTaskAtTop(task, null);
        } else if (position <= 0) {
            insertTaskAtBottom(task);
        } else {
            int position2 = getAdjustedPositionForTask(task, position, null);
            this.mTaskHistory.remove(task);
            this.mTaskHistory.add(position2, task);
            this.mWindowContainerController.positionChildAt(task.getWindowContainerController(), position2);
            updateTaskMovement(task, true);
        }
    }

    private void insertTaskAtTop(TaskRecord task, ActivityRecord starting) {
        this.mTaskHistory.remove(task);
        int position = getAdjustedPositionForTask(task, this.mTaskHistory.size(), starting);
        this.mTaskHistory.add(position, task);
        updateTaskMovement(task, true);
        this.mWindowContainerController.positionChildAtTop(task.getWindowContainerController(), true);
    }

    private void insertTaskAtBottom(TaskRecord task) {
        this.mTaskHistory.remove(task);
        int position = getAdjustedPositionForTask(task, 0, null);
        this.mTaskHistory.add(position, task);
        updateTaskMovement(task, true);
        this.mWindowContainerController.positionChildAtBottom(task.getWindowContainerController(), true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity, boolean newTask, boolean keepCurTransition, ActivityOptions options) {
        boolean z;
        TaskRecord rTask = r.getTask();
        int taskId = rTask.taskId;
        if (!r.mLaunchTaskBehind && (taskForIdLocked(taskId) == null || newTask)) {
            insertTaskAtTop(rTask, r);
        }
        TaskRecord task = null;
        if (!newTask) {
            boolean startIt = true;
            int taskNdx = this.mTaskHistory.size() - 1;
            while (true) {
                if (taskNdx < 0) {
                    break;
                }
                task = this.mTaskHistory.get(taskNdx);
                if (task.getTopActivity() != null) {
                    if (task == rTask) {
                        if (!startIt) {
                            if (ActivityManagerDebugConfig.DEBUG_ADD_REMOVE) {
                                Slog.i("ActivityManager", "Adding activity " + r + " to task " + task, new RuntimeException("here").fillInStackTrace());
                            }
                            r.createWindowContainer();
                            ActivityOptions.abort(options);
                            return;
                        }
                    } else if (task.numFullscreen > 0) {
                        startIt = false;
                    }
                }
                taskNdx--;
            }
        }
        TaskRecord activityTask = r.getTask();
        if (task == activityTask && this.mTaskHistory.indexOf(task) != this.mTaskHistory.size() - 1) {
            this.mStackSupervisor.mUserLeaving = false;
            if (ActivityManagerDebugConfig.DEBUG_USER_LEAVING) {
                Slog.v("ActivityManager", "startActivity() behind front, mUserLeaving=false");
            }
        }
        if (ActivityManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.i("ActivityManager", "Adding activity " + r + " to stack to task " + activityTask, new RuntimeException("here").fillInStackTrace());
        }
        if (r.getWindowContainerController() == null) {
            r.createWindowContainer();
        }
        activityTask.setFrontOfTask();
        if (!isHomeOrRecentsStack() || numActivities() > 0) {
            if (ActivityManagerDebugConfig.DEBUG_TRANSITION) {
                Slog.v("ActivityManager", "Prepare open transition: starting " + r);
            }
            if ((r.intent.getFlags() & 65536) != 0) {
                this.mWindowManager.prepareAppTransition(0, keepCurTransition);
                this.mStackSupervisor.mNoAnimActivities.add(r);
            } else {
                int transit = 6;
                if (newTask) {
                    if (r.mLaunchTaskBehind) {
                        transit = 16;
                    } else {
                        if (canEnterPipOnTaskSwitch(focusedTopActivity, null, r, options)) {
                            focusedTopActivity.supportsEnterPipOnTaskSwitch = true;
                        }
                        transit = 8;
                    }
                }
                this.mWindowManager.prepareAppTransition(transit, keepCurTransition);
                this.mStackSupervisor.mNoAnimActivities.remove(r);
            }
            boolean doShow = true;
            if (newTask) {
                if ((r.intent.getFlags() & DumpState.DUMP_COMPILER_STATS) != 0) {
                    resetTaskIfNeededLocked(r, r);
                    if (topRunningNonDelayedActivityLocked(null) == r) {
                        z = true;
                    } else {
                        z = false;
                    }
                    doShow = z;
                }
            } else if (options != null && options.getAnimationType() == 5) {
                doShow = false;
            }
            if (r.mLaunchTaskBehind) {
                r.setVisibility(true);
                ensureActivitiesVisibleLocked(null, 0, false);
                return;
            } else if (doShow) {
                TaskRecord prevTask = r.getTask();
                ActivityRecord prev = prevTask.topRunningActivityWithStartingWindowLocked();
                if (prev != null) {
                    if (prev.getTask() != prevTask) {
                        prev = null;
                    } else if (prev.nowVisible) {
                        prev = null;
                    }
                }
                r.showStartingWindow(prev, newTask, isTaskSwitch(r, focusedTopActivity));
                return;
            } else {
                return;
            }
        }
        ActivityOptions.abort(options);
    }

    private boolean canEnterPipOnTaskSwitch(ActivityRecord pipCandidate, TaskRecord toFrontTask, ActivityRecord toFrontActivity, ActivityOptions opts) {
        if ((opts != null && opts.disallowEnterPictureInPictureWhileLaunching()) || pipCandidate == null || pipCandidate.inPinnedWindowingMode()) {
            return false;
        }
        ActivityStack targetStack = toFrontTask != null ? toFrontTask.getStack() : toFrontActivity.getStack();
        if (targetStack != null && targetStack.isActivityTypeAssistant()) {
            return false;
        }
        return true;
    }

    private boolean isTaskSwitch(ActivityRecord r, ActivityRecord topFocusedActivity) {
        return (topFocusedActivity == null || r.getTask() == topFocusedActivity.getTask()) ? false : true;
    }

    private ActivityOptions resetTargetTaskIfNeededLocked(TaskRecord task, boolean forceReset) {
        int numActivities;
        int i;
        ActivityRecord target;
        TaskRecord targetTask;
        int start;
        ArrayList<ActivityRecord> activities = task.mActivities;
        int numActivities2 = activities.size();
        int rootActivityNdx = task.findEffectiveRootIndex();
        int i2 = numActivities2 - 1;
        ActivityOptions topOptions = null;
        int replyChainEnd = -1;
        boolean canMoveOptions = true;
        while (true) {
            int i3 = i2;
            if (i3 <= rootActivityNdx) {
                break;
            }
            ActivityRecord target2 = activities.get(i3);
            if (target2.frontOfTask) {
                break;
            }
            int flags = target2.info.flags;
            boolean finishOnTaskLaunch = (flags & 2) != 0;
            boolean allowTaskReparenting = (flags & 64) != 0;
            boolean clearWhenTaskReset = (target2.intent.getFlags() & DumpState.DUMP_FROZEN) != 0;
            if (finishOnTaskLaunch || clearWhenTaskReset || target2.resultTo == null) {
                if (finishOnTaskLaunch || clearWhenTaskReset || !allowTaskReparenting || target2.taskAffinity == null || target2.taskAffinity.equals(task.affinity)) {
                    numActivities = numActivities2;
                    if (forceReset || finishOnTaskLaunch || clearWhenTaskReset) {
                        int end = clearWhenTaskReset ? activities.size() - 1 : replyChainEnd < 0 ? i3 : replyChainEnd;
                        boolean noOptions = canMoveOptions;
                        ActivityOptions topOptions2 = topOptions;
                        int end2 = end;
                        int end3 = i3;
                        while (true) {
                            int srcPos = end3;
                            if (srcPos > end2) {
                                break;
                            }
                            ActivityRecord p = activities.get(srcPos);
                            if (!p.finishing) {
                                canMoveOptions = false;
                                if (noOptions && topOptions2 == null && (topOptions2 = p.takeOptionsLocked()) != null) {
                                    noOptions = false;
                                }
                                boolean noOptions2 = noOptions;
                                ActivityOptions topOptions3 = topOptions2;
                                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                                    Slog.w("ActivityManager", "resetTaskIntendedTask: calling finishActivity on " + p);
                                }
                                if (finishActivityLocked(p, 0, null, "reset-task", false)) {
                                    end2--;
                                    srcPos--;
                                    topOptions2 = topOptions3;
                                    noOptions = noOptions2;
                                } else {
                                    topOptions2 = topOptions3;
                                    noOptions = noOptions2;
                                    srcPos = srcPos;
                                }
                            }
                            end3 = srcPos + 1;
                        }
                        replyChainEnd = -1;
                        topOptions = topOptions2;
                    } else {
                        i = -1;
                    }
                } else {
                    ActivityRecord bottom = (this.mTaskHistory.isEmpty() || this.mTaskHistory.get(0).mActivities.isEmpty()) ? null : this.mTaskHistory.get(0).mActivities.get(0);
                    if (bottom == null || target2.taskAffinity == null || !target2.taskAffinity.equals(bottom.getTask().affinity)) {
                        numActivities = numActivities2;
                        target = target2;
                        targetTask = createTaskRecord(this.mStackSupervisor.getNextTaskIdForUserLocked(target2.userId), target2.info, null, null, null, false);
                        targetTask.affinityIntent = target.intent;
                        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                            Slog.v("ActivityManager", "Start pushing activity " + target + " out to new task " + targetTask);
                        }
                    } else {
                        targetTask = bottom.getTask();
                        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                            Slog.v("ActivityManager", "Start pushing activity " + target2 + " out to bottom task " + targetTask);
                        }
                        numActivities = numActivities2;
                        target = target2;
                    }
                    boolean noOptions3 = canMoveOptions;
                    int start2 = replyChainEnd < 0 ? i3 : replyChainEnd;
                    boolean noOptions4 = noOptions3;
                    int srcPos2 = start2;
                    while (srcPos2 >= i3) {
                        ActivityRecord p2 = activities.get(srcPos2);
                        if (p2.finishing) {
                            start = start2;
                        } else {
                            if (noOptions4 && topOptions == null && (topOptions = p2.takeOptionsLocked()) != null) {
                                noOptions4 = false;
                            }
                            if (ActivityManagerDebugConfig.DEBUG_ADD_REMOVE) {
                                StringBuilder sb = new StringBuilder();
                                start = start2;
                                sb.append("Removing activity ");
                                sb.append(p2);
                                sb.append(" from task=");
                                sb.append(task);
                                sb.append(" adding to task=");
                                sb.append(targetTask);
                                sb.append(" Callers=");
                                sb.append(Debug.getCallers(4));
                                Slog.i("ActivityManager", sb.toString());
                            } else {
                                start = start2;
                            }
                            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                                Slog.v("ActivityManager", "Pushing next activity " + p2 + " out to target's task " + target);
                            }
                            p2.reparent(targetTask, 0, "resetTargetTaskIfNeeded");
                            canMoveOptions = false;
                        }
                        srcPos2--;
                        start2 = start;
                    }
                    this.mWindowContainerController.positionChildAtBottom(targetTask.getWindowContainerController(), false);
                    i = -1;
                }
                replyChainEnd = i;
            } else {
                if (replyChainEnd < 0) {
                    replyChainEnd = i3;
                }
                numActivities = numActivities2;
            }
            i2 = i3 - 1;
            numActivities2 = numActivities;
        }
        return topOptions;
    }

    private int resetAffinityTaskIfNeededLocked(TaskRecord affinityTask, TaskRecord task, boolean topTaskIsHigher, boolean forceReset, int taskInsertionPoint) {
        int taskId;
        String taskAffinity;
        int numActivities;
        int rootActivityNdx;
        ActivityStack<T> activityStack;
        ArrayList<ActivityRecord> taskActivities;
        int targetNdx;
        int taskInsertionPoint2;
        TaskRecord taskRecord = affinityTask;
        TaskRecord taskRecord2 = task;
        int taskId2 = taskRecord2.taskId;
        String taskAffinity2 = taskRecord2.affinity;
        ArrayList<ActivityRecord> activities = taskRecord.mActivities;
        int numActivities2 = activities.size();
        int rootActivityNdx2 = affinityTask.findEffectiveRootIndex();
        int i = numActivities2 - 1;
        int replyChainEnd = -1;
        int replyChainEnd2 = taskInsertionPoint;
        while (i > rootActivityNdx2) {
            ActivityRecord target = activities.get(i);
            if (target.frontOfTask) {
                break;
            }
            int flags = target.info.flags;
            boolean finishOnTaskLaunch = (flags & 2) != 0;
            boolean allowTaskReparenting = (flags & 64) != 0;
            if (target.resultTo != null) {
                if (replyChainEnd < 0) {
                    replyChainEnd = i;
                }
                taskId = taskId2;
                taskAffinity = taskAffinity2;
                numActivities = numActivities2;
                rootActivityNdx = rootActivityNdx2;
            } else if (topTaskIsHigher && allowTaskReparenting && taskAffinity2 != null && taskAffinity2.equals(target.taskAffinity)) {
                if (forceReset) {
                    activityStack = this;
                    taskId = taskId2;
                    taskAffinity = taskAffinity2;
                    numActivities = numActivities2;
                    rootActivityNdx = rootActivityNdx2;
                } else if (finishOnTaskLaunch) {
                    activityStack = this;
                    taskId = taskId2;
                    taskAffinity = taskAffinity2;
                    numActivities = numActivities2;
                    rootActivityNdx = rootActivityNdx2;
                } else {
                    if (replyChainEnd2 < 0) {
                        taskId = taskId2;
                        replyChainEnd2 = taskRecord2.mActivities.size();
                    } else {
                        taskId = taskId2;
                    }
                    int start = replyChainEnd >= 0 ? replyChainEnd : i;
                    if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                        taskAffinity = taskAffinity2;
                        numActivities = numActivities2;
                        StringBuilder sb = new StringBuilder();
                        rootActivityNdx = rootActivityNdx2;
                        sb.append("Reparenting from task=");
                        sb.append(taskRecord);
                        sb.append(":");
                        sb.append(start);
                        sb.append("-");
                        sb.append(i);
                        sb.append(" to task=");
                        sb.append(taskRecord2);
                        sb.append(":");
                        sb.append(replyChainEnd2);
                        Slog.v("ActivityManager", sb.toString());
                    } else {
                        taskAffinity = taskAffinity2;
                        numActivities = numActivities2;
                        rootActivityNdx = rootActivityNdx2;
                    }
                    int srcPos = start;
                    while (srcPos >= i) {
                        ActivityRecord p = activities.get(srcPos);
                        p.reparent(taskRecord2, replyChainEnd2, "resetAffinityTaskIfNeededLocked");
                        if (ActivityManagerDebugConfig.DEBUG_ADD_REMOVE) {
                            StringBuilder sb2 = new StringBuilder();
                            taskInsertionPoint2 = replyChainEnd2;
                            sb2.append("Removing and adding activity ");
                            sb2.append(p);
                            sb2.append(" to stack at ");
                            sb2.append(taskRecord2);
                            sb2.append(" callers=");
                            sb2.append(Debug.getCallers(3));
                            Slog.i("ActivityManager", sb2.toString());
                        } else {
                            taskInsertionPoint2 = replyChainEnd2;
                        }
                        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                            Slog.v("ActivityManager", "Pulling activity " + p + " from " + srcPos + " in to resetting task " + taskRecord2);
                        }
                        srcPos--;
                        replyChainEnd2 = taskInsertionPoint2;
                    }
                    int taskInsertionPoint3 = replyChainEnd2;
                    this.mWindowContainerController.positionChildAtTop(task.getWindowContainerController(), true);
                    if (target.info.launchMode == 1 && (targetNdx = (taskActivities = taskRecord2.mActivities).indexOf(target)) > 0) {
                        ActivityRecord p2 = taskActivities.get(targetNdx - 1);
                        if (p2.intent.getComponent().equals(target.intent.getComponent())) {
                            finishActivityLocked(p2, 0, null, "replace", false);
                        }
                    }
                    replyChainEnd2 = taskInsertionPoint3;
                    replyChainEnd = -1;
                }
                int start2 = replyChainEnd >= 0 ? replyChainEnd : i;
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.v("ActivityManager", "Finishing task at index " + start2 + " to " + i);
                }
                for (int srcPos2 = start2; srcPos2 >= i; srcPos2--) {
                    ActivityRecord p3 = activities.get(srcPos2);
                    if (!p3.finishing) {
                        activityStack.finishActivityLocked(p3, 0, null, "move-affinity", false);
                    }
                }
                replyChainEnd = -1;
            } else {
                taskId = taskId2;
                taskAffinity = taskAffinity2;
                numActivities = numActivities2;
                rootActivityNdx = rootActivityNdx2;
            }
            i--;
            taskId2 = taskId;
            taskAffinity2 = taskAffinity;
            numActivities2 = numActivities;
            rootActivityNdx2 = rootActivityNdx;
            taskRecord = affinityTask;
            taskRecord2 = task;
        }
        return replyChainEnd2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityRecord resetTaskIfNeededLocked(ActivityRecord taskTop, ActivityRecord newActivity) {
        boolean forceReset = (newActivity.info.flags & 4) != 0;
        TaskRecord task = taskTop.getTask();
        int i = this.mTaskHistory.size() - 1;
        boolean taskFound = false;
        ActivityOptions topOptions = null;
        int reparentInsertionPoint = -1;
        while (true) {
            int i2 = i;
            if (i2 < 0) {
                break;
            }
            TaskRecord targetTask = this.mTaskHistory.get(i2);
            if (targetTask == task) {
                topOptions = resetTargetTaskIfNeededLocked(task, forceReset);
                taskFound = true;
            } else {
                reparentInsertionPoint = resetAffinityTaskIfNeededLocked(targetTask, task, taskFound, forceReset, reparentInsertionPoint);
            }
            i = i2 - 1;
        }
        int taskNdx = this.mTaskHistory.indexOf(task);
        if (taskNdx >= 0) {
            while (true) {
                int taskNdx2 = taskNdx - 1;
                taskTop = this.mTaskHistory.get(taskNdx).getTopActivity();
                if (taskTop != null || taskNdx2 < 0) {
                    break;
                }
                taskNdx = taskNdx2;
            }
        }
        if (topOptions != null) {
            if (taskTop != null) {
                taskTop.updateOptionsLocked(topOptions);
            } else {
                topOptions.abort();
            }
        }
        return taskTop;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendActivityResultLocked(int callingUid, ActivityRecord r, String resultWho, int requestCode, int resultCode, Intent data) {
        if (callingUid > 0) {
            this.mService.grantUriPermissionFromIntentLocked(callingUid, r.packageName, data, r.getUriPermissionsLocked(), r.userId);
        }
        if (ActivityManagerDebugConfig.DEBUG_RESULTS) {
            Slog.v("ActivityManager", "Send activity result to " + r + " : who=" + resultWho + " req=" + requestCode + " res=" + resultCode + " data=" + data);
        }
        if (this.mResumedActivity == r && r.app != null && r.app.thread != null) {
            try {
                ArrayList<ResultInfo> list = new ArrayList<>();
                list.add(new ResultInfo(resultWho, requestCode, resultCode, data));
                this.mService.getLifecycleManager().scheduleTransaction(r.app.thread, (IBinder) r.appToken, (ClientTransactionItem) ActivityResultItem.obtain(list));
                return;
            } catch (Exception e) {
                Slog.w("ActivityManager", "Exception thrown sending result to " + r, e);
            }
        }
        r.addResultLocked(null, resultWho, requestCode, resultCode, data);
    }

    private boolean isATopFinishingTask(TaskRecord task) {
        for (int i = this.mTaskHistory.size() - 1; i >= 0; i--) {
            TaskRecord current = this.mTaskHistory.get(i);
            ActivityRecord r = current.topRunningActivityLocked();
            if (r != null) {
                return false;
            }
            if (current == task) {
                return true;
            }
        }
        return false;
    }

    private void adjustFocusedActivityStack(ActivityRecord r, String reason) {
        if (this.mStackSupervisor.isFocusedStack(this)) {
            if (this.mResumedActivity != r && this.mResumedActivity != null) {
                return;
            }
            ActivityRecord next = topRunningActivityLocked();
            String myReason = reason + " adjustFocus";
            if (next == r) {
                this.mStackSupervisor.moveFocusableActivityStackToFrontLocked(this.mStackSupervisor.topRunningActivityLocked(), myReason);
            } else if (next != null && isFocusable()) {
            } else {
                TaskRecord task = r.getTask();
                if (task == null) {
                    throw new IllegalStateException("activity no longer associated with task:" + r);
                } else if (adjustFocusToNextFocusableStack(myReason)) {
                } else {
                    this.mStackSupervisor.moveHomeStackTaskToTop(myReason);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean adjustFocusToNextFocusableStack(String reason) {
        return adjustFocusToNextFocusableStack(reason, false);
    }

    private boolean adjustFocusToNextFocusableStack(String reason, boolean allowFocusSelf) {
        ActivityStack stack = this.mStackSupervisor.getNextFocusableStackLocked(this, !allowFocusSelf);
        String myReason = reason + " adjustFocusToNextFocusableStack";
        if (stack == null) {
            return false;
        }
        ActivityRecord top = stack.topRunningActivityLocked();
        if (stack.isActivityTypeHome() && (top == null || !top.visible)) {
            return this.mStackSupervisor.moveHomeStackTaskToTop(reason);
        }
        stack.moveToFront(myReason);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void stopActivityLocked(ActivityRecord r) {
        if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityManager", "Stopping: " + r);
        }
        if (((r.intent.getFlags() & 1073741824) != 0 || (r.info.flags & 128) != 0) && !r.finishing) {
            if (!shouldSleepActivities()) {
                if (ActivityManagerDebugConfig.DEBUG_STATES) {
                    Slog.d("ActivityManager", "no-history finish of " + r);
                }
                if (requestFinishActivityLocked(r.appToken, 0, null, "stop-no-history", false)) {
                    r.resumeKeyDispatchingLocked();
                    return;
                }
            } else if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.d("ActivityManager", "Not finishing noHistory " + r + " on stop because we're just sleeping");
            }
        }
        if (r.app != null && r.app.thread != null) {
            adjustFocusedActivityStack(r, "stopActivity");
            r.resumeKeyDispatchingLocked();
            try {
                r.stopped = false;
                if (r.isActivityTypeHome() && xpWindowManager.isDesktopHome()) {
                    r.visible = true;
                }
                if (ActivityManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityManager", "Moving to STOPPING: " + r + " (stop requested)");
                }
                r.setState(ActivityState.STOPPING, "stopActivityLocked");
                if (ActivityManagerDebugConfig.DEBUG_VISIBILITY) {
                    Slog.v("ActivityManager", "Stopping visible=" + r.visible + " for " + r);
                }
                if (!r.visible) {
                    r.setVisible(false);
                }
                EventLogTags.writeAmStopActivity(r.userId, System.identityHashCode(r), r.shortComponentName);
                this.mService.getLifecycleManager().scheduleTransaction(r.app.thread, (IBinder) r.appToken, (ActivityLifecycleItem) StopActivityItem.obtain(r.visible, r.configChangeFlags));
                if (shouldSleepOrShutDownActivities()) {
                    r.setSleeping(true);
                }
                Message msg = this.mHandler.obtainMessage(104, r);
                this.mHandler.sendMessageDelayed(msg, 11000L);
            } catch (Exception e) {
                Slog.w("ActivityManager", "Exception thrown during pause", e);
                r.stopped = true;
                if (ActivityManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityManager", "Stop failed; moving to STOPPED: " + r);
                }
                r.setState(ActivityState.STOPPED, "stopActivityLocked");
                if (r.deferRelaunchUntilPaused) {
                    destroyActivityLocked(r, true, "stop-except");
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean requestFinishActivityLocked(IBinder token, int resultCode, Intent resultData, String reason, boolean oomAdj) {
        ActivityRecord r = isInStackLocked(token);
        if (ActivityManagerDebugConfig.DEBUG_RESULTS || ActivityManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityManager", "Finishing activity token=" + token + " r=, result=" + resultCode + ", data=" + resultData + ", reason=" + reason);
        }
        if (r == null) {
            return false;
        }
        finishActivityLocked(r, resultCode, resultData, reason, oomAdj);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void finishSubActivityLocked(ActivityRecord self, String resultWho, int requestCode) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.resultTo == self && r.requestCode == requestCode && ((r.resultWho == null && resultWho == null) || (r.resultWho != null && r.resultWho.equals(resultWho)))) {
                    finishActivityLocked(r, 0, null, "request-sub", false);
                }
            }
        }
        this.mService.updateOomAdjLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final TaskRecord finishTopCrashedActivityLocked(ProcessRecord app, String reason) {
        ActivityRecord r = topRunningActivityLocked();
        if (r == null || r.app != app) {
            return null;
        }
        Slog.w("ActivityManager", "  Force finishing activity " + r.intent.getComponent().flattenToShortString());
        TaskRecord finishedTask = r.getTask();
        int taskNdx = this.mTaskHistory.indexOf(finishedTask);
        int activityNdx = finishedTask.mActivities.indexOf(r);
        this.mWindowManager.prepareAppTransition(26, false);
        finishActivityLocked(r, 0, null, reason, false);
        int activityNdx2 = activityNdx - 1;
        if (activityNdx2 < 0) {
            do {
                taskNdx--;
                if (taskNdx < 0) {
                    break;
                }
                activityNdx2 = this.mTaskHistory.get(taskNdx).mActivities.size() - 1;
            } while (activityNdx2 < 0);
        }
        if (activityNdx2 >= 0) {
            ActivityRecord r2 = this.mTaskHistory.get(taskNdx).mActivities.get(activityNdx2);
            if (r2.isState(ActivityState.RESUMED, ActivityState.PAUSING, ActivityState.PAUSED) && (!r2.isActivityTypeHome() || this.mService.mHomeProcess != r2.app)) {
                Slog.w("ActivityManager", "  Force finishing activity " + r2.intent.getComponent().flattenToShortString());
                finishActivityLocked(r2, 0, null, reason, false);
            }
        }
        return finishedTask;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void finishVoiceTask(IVoiceInteractionSession session) {
        IBinder sessionBinder = session.asBinder();
        boolean didOne = false;
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord tr = this.mTaskHistory.get(taskNdx);
            if (tr.voiceSession != null && tr.voiceSession.asBinder() == sessionBinder) {
                for (int activityNdx = tr.mActivities.size() - 1; activityNdx >= 0; activityNdx--) {
                    ActivityRecord r = tr.mActivities.get(activityNdx);
                    if (!r.finishing) {
                        finishActivityLocked(r, 0, null, "finish-voice", false);
                        didOne = true;
                    }
                }
            } else {
                int activityNdx2 = tr.mActivities.size() - 1;
                while (true) {
                    if (activityNdx2 >= 0) {
                        ActivityRecord r2 = tr.mActivities.get(activityNdx2);
                        if (r2.voiceSession != null && r2.voiceSession.asBinder() == sessionBinder) {
                            r2.clearVoiceSessionLocked();
                            try {
                                r2.app.thread.scheduleLocalVoiceInteractionStarted(r2.appToken, (IVoiceInteractor) null);
                            } catch (RemoteException e) {
                            }
                            this.mService.finishRunningVoiceLocked();
                            break;
                        }
                        activityNdx2--;
                    }
                }
            }
        }
        if (didOne) {
            this.mService.updateOomAdjLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean finishActivityAffinityLocked(ActivityRecord r) {
        ArrayList<ActivityRecord> activities = r.getTask().mActivities;
        for (int index = activities.indexOf(r); index >= 0; index--) {
            ActivityRecord cur = activities.get(index);
            if (Objects.equals(cur.taskAffinity, r.taskAffinity)) {
                finishActivityLocked(cur, 0, null, "request-affinity", true);
            } else {
                return true;
            }
        }
        return true;
    }

    private void finishActivityResultsLocked(ActivityRecord r, int resultCode, Intent resultData) {
        ActivityRecord resultTo = r.resultTo;
        if (resultTo != null) {
            if (ActivityManagerDebugConfig.DEBUG_RESULTS) {
                Slog.v("ActivityManager", "Adding result to " + resultTo + " who=" + r.resultWho + " req=" + r.requestCode + " res=" + resultCode + " data=" + resultData);
            }
            if (resultTo.userId != r.userId && resultData != null) {
                resultData.prepareToLeaveUser(r.userId);
            }
            if (r.info.applicationInfo.uid > 0) {
                this.mService.grantUriPermissionFromIntentLocked(r.info.applicationInfo.uid, resultTo.packageName, resultData, resultTo.getUriPermissionsLocked(), resultTo.userId);
            }
            resultTo.addResultLocked(r, r.resultWho, r.requestCode, resultCode, resultData);
            r.resultTo = null;
        } else if (ActivityManagerDebugConfig.DEBUG_RESULTS) {
            Slog.v("ActivityManager", "No result destination from " + r);
        }
        r.results = null;
        r.pendingResults = null;
        r.newIntents = null;
        r.icicle = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean finishActivityLocked(ActivityRecord r, int resultCode, Intent resultData, String reason, boolean oomAdj) {
        return finishActivityLocked(r, resultCode, resultData, reason, oomAdj, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean finishActivityLocked(ActivityRecord r, int resultCode, Intent resultData, String reason, boolean oomAdj, boolean pauseImmediately) {
        if (r.finishing) {
            Slog.w("ActivityManager", "Duplicate finish request for " + r);
            return false;
        }
        this.mWindowManager.deferSurfaceLayout();
        try {
            r.makeFinishingLocked();
            TaskRecord task = r.getTask();
            int finishMode = 2;
            EventLog.writeEvent((int) EventLogTags.AM_FINISH_ACTIVITY, Integer.valueOf(r.userId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(task.taskId), r.shortComponentName, reason);
            ArrayList<ActivityRecord> activities = task.mActivities;
            int index = activities.indexOf(r);
            if (index < activities.size() - 1) {
                task.setFrontOfTask();
                if ((r.intent.getFlags() & DumpState.DUMP_FROZEN) != 0) {
                    ActivityRecord next = activities.get(index + 1);
                    next.intent.addFlags(DumpState.DUMP_FROZEN);
                }
            }
            r.pauseKeyDispatchingLocked();
            adjustFocusedActivityStack(r, "finishActivity");
            finishActivityResultsLocked(r, resultCode, resultData);
            boolean endTask = index <= 0 && !task.isClearingToReuseTask();
            int transit = endTask ? 9 : 7;
            try {
                if (this.mResumedActivity == r) {
                    try {
                        if (ActivityManagerDebugConfig.DEBUG_VISIBILITY || ActivityManagerDebugConfig.DEBUG_TRANSITION) {
                            Slog.v("ActivityManager", "Prepare close transition: finishing " + r);
                        }
                        if (endTask) {
                            this.mService.mTaskChangeNotificationController.notifyTaskRemovalStarted(task.taskId);
                        }
                        this.mWindowManager.prepareAppTransition(transit, false);
                        r.setVisibility(false);
                        if (this.mPausingActivity == null) {
                            if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                                Slog.v("ActivityManager", "Finish needs to pause: " + r);
                            }
                            if (ActivityManagerDebugConfig.DEBUG_USER_LEAVING) {
                                Slog.v("ActivityManager", "finish() => pause with userLeaving=false");
                            }
                            startPausingLocked(false, false, null, pauseImmediately);
                        }
                        if (endTask) {
                            this.mService.getLockTaskController().clearLockedTask(task);
                        }
                    } catch (Throwable th) {
                        th = th;
                        this.mWindowManager.continueSurfaceLayout();
                        throw th;
                    }
                } else {
                    try {
                        if (!r.isState(ActivityState.PAUSING)) {
                            if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                                Slog.v("ActivityManager", "Finish not pausing: " + r);
                            }
                            if (r.visible) {
                                prepareActivityHideTransitionAnimation(r, transit);
                            }
                            if (!r.visible && !r.nowVisible) {
                                finishMode = 1;
                            }
                            boolean removedActivity = finishCurrentActivityLocked(r, finishMode, oomAdj, "finishActivityLocked") == null;
                            if (task.onlyHasTaskOverlayActivities(true)) {
                                Iterator<ActivityRecord> it = task.mActivities.iterator();
                                while (it.hasNext()) {
                                    ActivityRecord taskOverlay = it.next();
                                    if (taskOverlay.mTaskOverlay) {
                                        prepareActivityHideTransitionAnimation(taskOverlay, transit);
                                    }
                                }
                            }
                            this.mWindowManager.continueSurfaceLayout();
                            return removedActivity;
                        } else if (ActivityManagerDebugConfig.DEBUG_PAUSE) {
                            Slog.v("ActivityManager", "Finish waiting for pause of: " + r);
                        }
                    } catch (Throwable th2) {
                        th = th2;
                        this.mWindowManager.continueSurfaceLayout();
                        throw th;
                    }
                }
                this.mWindowManager.continueSurfaceLayout();
                return false;
            } catch (Throwable th3) {
                th = th3;
            }
        } catch (Throwable th4) {
            th = th4;
        }
    }

    private void prepareActivityHideTransitionAnimation(ActivityRecord r, int transit) {
        this.mWindowManager.prepareAppTransition(transit, false);
        r.setVisibility(false);
        this.mWindowManager.executeAppTransition();
        if (!this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.contains(r)) {
            this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.add(r);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityRecord finishCurrentActivityLocked(ActivityRecord r, int mode, boolean oomAdj, String reason) {
        ActivityRecord next = this.mStackSupervisor.topRunningActivityLocked(true);
        if (mode == 2 && ((r.visible || r.nowVisible) && next != null && !next.nowVisible)) {
            if (!this.mStackSupervisor.mStoppingActivities.contains(r)) {
                addToStopping(r, false, false);
            }
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "Moving to STOPPING: " + r + " (finish requested)");
            }
            r.setState(ActivityState.STOPPING, "finishCurrentActivityLocked");
            if (oomAdj) {
                this.mService.updateOomAdjLocked();
            }
            return r;
        }
        this.mStackSupervisor.mStoppingActivities.remove(r);
        this.mStackSupervisor.mGoingToSleepActivities.remove(r);
        this.mStackSupervisor.mActivitiesWaitingForVisibleActivity.remove(r);
        ActivityState prevState = r.getState();
        if (ActivityManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityManager", "Moving to FINISHING: " + r);
        }
        r.setState(ActivityState.FINISHING, "finishCurrentActivityLocked");
        boolean finishingActivityInNonFocusedStack = r.getStack() != this.mStackSupervisor.getFocusedStack() && prevState == ActivityState.PAUSED && mode == 2;
        if (mode == 0 || ((prevState == ActivityState.PAUSED && (mode == 1 || inPinnedWindowingMode())) || finishingActivityInNonFocusedStack || prevState == ActivityState.STOPPING || prevState == ActivityState.STOPPED || prevState == ActivityState.INITIALIZING)) {
            r.makeFinishingLocked();
            boolean activityRemoved = destroyActivityLocked(r, true, "finish-imm:" + reason);
            if (finishingActivityInNonFocusedStack) {
                this.mStackSupervisor.ensureVisibilityAndConfig(next, this.mDisplayId, false, true);
            }
            if (activityRemoved) {
                this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
            }
            if (ActivityManagerDebugConfig.DEBUG_CONTAINERS) {
                Slog.d("ActivityManager", "destroyActivityLocked: finishCurrentActivityLocked r=" + r + " destroy returned removed=" + activityRemoved);
            }
            if (activityRemoved) {
                return null;
            }
            return r;
        }
        if (ActivityManagerDebugConfig.DEBUG_ALL) {
            Slog.v("ActivityManager", "Enqueueing pending finish: " + r);
        }
        this.mStackSupervisor.mFinishingActivities.add(r);
        r.resumeKeyDispatchingLocked();
        this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
        return r;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishAllActivitiesLocked(boolean immediately) {
        boolean noActivitiesInStack = true;
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                noActivitiesInStack = false;
                if (!r.finishing || immediately) {
                    Slog.d("ActivityManager", "finishAllActivitiesLocked: finishing " + r + " immediately");
                    finishCurrentActivityLocked(r, 0, false, "finishAllActivitiesLocked");
                }
            }
        }
        if (noActivitiesInStack) {
            remove();
        }
    }

    boolean inFrontOfStandardStack() {
        int index;
        ActivityDisplay display = getDisplay();
        if (display == null || (index = display.getIndexOf(this)) == 0) {
            return false;
        }
        ActivityStack stackBehind = display.getChildAt(index - 1);
        return stackBehind.isActivityTypeStandard();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldUpRecreateTaskLocked(ActivityRecord srec, String destAffinity) {
        if (srec == null || srec.getTask().affinity == null || !srec.getTask().affinity.equals(destAffinity)) {
            return true;
        }
        TaskRecord task = srec.getTask();
        if (srec.frontOfTask && task.getBaseIntent() != null && task.getBaseIntent().isDocument()) {
            if (!inFrontOfStandardStack()) {
                return true;
            }
            int taskIdx = this.mTaskHistory.indexOf(task);
            if (taskIdx <= 0) {
                Slog.w("ActivityManager", "shouldUpRecreateTask: task not in history for " + srec);
                return false;
            }
            TaskRecord prevTask = this.mTaskHistory.get(taskIdx);
            if (!task.affinity.equals(prevTask.affinity)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean navigateUpToLocked(ActivityRecord srec, Intent destIntent, int resultCode, Intent resultData) {
        boolean z;
        ActivityRecord next;
        TaskRecord task = srec.getTask();
        ArrayList<ActivityRecord> activities = task.mActivities;
        int start = activities.indexOf(srec);
        if (!this.mTaskHistory.contains(task) || start < 0) {
            return false;
        }
        int finishTo = start - 1;
        ActivityRecord parent = finishTo < 0 ? null : activities.get(finishTo);
        boolean foundParentInTask = false;
        ComponentName dest = destIntent.getComponent();
        if (start > 0 && dest != null) {
            int i = finishTo;
            while (true) {
                if (i < 0) {
                    break;
                }
                ActivityRecord r = activities.get(i);
                if (r.info.packageName.equals(dest.getPackageName()) && r.info.name.equals(dest.getClassName())) {
                    finishTo = i;
                    parent = r;
                    foundParentInTask = true;
                    break;
                }
                i--;
            }
        }
        int finishTo2 = finishTo;
        ActivityRecord parent2 = parent;
        boolean foundParentInTask2 = foundParentInTask;
        IActivityController controller = this.mService.mController;
        if (controller != null && (next = topRunningActivityLocked(srec.appToken, 0)) != null) {
            boolean resumeOK = true;
            try {
                resumeOK = controller.activityResuming(next.packageName);
            } catch (RemoteException e) {
                this.mService.mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
            if (!resumeOK) {
                return false;
            }
        }
        long origId = Binder.clearCallingIdentity();
        int resultCode2 = resultCode;
        Intent resultData2 = resultData;
        int i2 = start;
        while (i2 > finishTo2) {
            requestFinishActivityLocked(activities.get(i2).appToken, resultCode2, resultData2, "navigate-up", true);
            resultCode2 = 0;
            resultData2 = null;
            i2--;
            origId = origId;
            controller = controller;
            finishTo2 = finishTo2;
            dest = dest;
        }
        long origId2 = origId;
        if (parent2 != null && foundParentInTask2) {
            int parentLaunchMode = parent2.info.launchMode;
            int destIntentFlags = destIntent.getFlags();
            if (parentLaunchMode != 3 && parentLaunchMode != 2) {
                if (parentLaunchMode != 1) {
                    if ((destIntentFlags & 67108864) != 0) {
                        parent2.deliverNewIntentLocked(srec.info.applicationInfo.uid, destIntent, srec.packageName);
                    } else {
                        try {
                            ActivityInfo aInfo = AppGlobals.getPackageManager().getActivityInfo(destIntent.getComponent(), 1024, srec.userId);
                            int res = this.mService.getActivityStartController().obtainStarter(destIntent, "navigateUpTo").setCaller(srec.app.thread).setActivityInfo(aInfo).setResultTo(parent2.appToken).setCallingPid(-1).setCallingUid(parent2.launchedFromUid).setCallingPackage(parent2.launchedFromPackage).setRealCallingPid(-1).setRealCallingUid(parent2.launchedFromUid).setComponentSpecified(true).execute();
                            z = res == 0;
                        } catch (RemoteException e2) {
                            z = false;
                        }
                        foundParentInTask2 = z;
                        requestFinishActivityLocked(parent2.appToken, resultCode2, resultData2, "navigate-top", true);
                    }
                }
            }
            parent2.deliverNewIntentLocked(srec.info.applicationInfo.uid, destIntent, srec.packageName);
        }
        Binder.restoreCallingIdentity(origId2);
        return foundParentInTask2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onActivityRemovedFromStack(ActivityRecord r) {
        removeTimeoutsForActivityLocked(r);
        if (this.mResumedActivity != null && this.mResumedActivity == r) {
            setResumedActivity(null, "onActivityRemovedFromStack");
        }
        if (this.mPausingActivity != null && this.mPausingActivity == r) {
            this.mPausingActivity = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onActivityAddedToStack(ActivityRecord r) {
        if (r.getState() == ActivityState.RESUMED) {
            setResumedActivity(r, "onActivityAddedToStack");
        }
    }

    private void cleanUpActivityLocked(ActivityRecord r, boolean cleanServices, boolean setState) {
        onActivityRemovedFromStack(r);
        r.deferRelaunchUntilPaused = false;
        r.frozenBeforeDestroy = false;
        if (setState) {
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "Moving to DESTROYED: " + r + " (cleaning up)");
            }
            r.setState(ActivityState.DESTROYED, "cleanupActivityLocked");
            if (ActivityManagerDebugConfig.DEBUG_APP) {
                Slog.v("ActivityManager", "Clearing app during cleanUp for activity " + r);
            }
            r.app = null;
        }
        this.mStackSupervisor.cleanupActivity(r);
        if (r.finishing && r.pendingResults != null) {
            Iterator<WeakReference<PendingIntentRecord>> it = r.pendingResults.iterator();
            while (it.hasNext()) {
                WeakReference<PendingIntentRecord> apr = it.next();
                PendingIntentRecord rec = apr.get();
                if (rec != null) {
                    this.mService.cancelIntentSenderLocked(rec, false);
                }
            }
            r.pendingResults = null;
        }
        if (cleanServices) {
            cleanUpActivityServicesLocked(r);
        }
        removeTimeoutsForActivityLocked(r);
        this.mWindowManager.notifyAppRelaunchesCleared(r.appToken);
    }

    void removeTimeoutsForActivityLocked(ActivityRecord r) {
        this.mStackSupervisor.removeTimeoutsForActivityLocked(r);
        this.mHandler.removeMessages(101, r);
        this.mHandler.removeMessages(104, r);
        this.mHandler.removeMessages(102, r);
        r.finishLaunchTickingLocked();
    }

    private void removeActivityFromHistoryLocked(ActivityRecord r, String reason) {
        finishActivityResultsLocked(r, 0, null);
        r.makeFinishingLocked();
        if (ActivityManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.i("ActivityManager", "Removing activity " + r + " from stack callers=" + Debug.getCallers(5));
        }
        r.takeFromHistory();
        removeTimeoutsForActivityLocked(r);
        if (ActivityManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityManager", "Moving to DESTROYED: " + r + " (removed from history)");
        }
        r.setState(ActivityState.DESTROYED, "removeActivityFromHistoryLocked");
        if (ActivityManagerDebugConfig.DEBUG_APP) {
            Slog.v("ActivityManager", "Clearing app during remove for activity " + r);
        }
        r.app = null;
        r.removeWindowContainer();
        TaskRecord task = r.getTask();
        boolean lastActivity = task != null ? task.removeActivity(r) : false;
        boolean onlyHasTaskOverlays = task != null ? task.onlyHasTaskOverlayActivities(false) : false;
        if (lastActivity || onlyHasTaskOverlays) {
            if (ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.i("ActivityManager", "removeActivityFromHistoryLocked: last activity removed from " + this + " onlyHasTaskOverlays=" + onlyHasTaskOverlays);
            }
            if (onlyHasTaskOverlays) {
                this.mStackSupervisor.removeTaskByIdLocked(task.taskId, false, false, true, reason);
            }
            if (lastActivity) {
                removeTask(task, reason, 0);
            }
        }
        cleanUpActivityServicesLocked(r);
        r.removeUriPermissionsLocked();
    }

    private void cleanUpActivityServicesLocked(ActivityRecord r) {
        if (r.connections != null) {
            Iterator<ConnectionRecord> it = r.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                this.mService.mServices.removeConnectionLocked(c, null, r);
            }
            r.connections = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleDestroyActivities(ProcessRecord owner, String reason) {
        Message msg = this.mHandler.obtainMessage(105);
        msg.obj = new ScheduleDestroyArgs(owner, reason);
        this.mHandler.sendMessage(msg);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroyActivitiesLocked(ProcessRecord owner, String reason) {
        boolean lastIsOpaque = false;
        boolean activityRemoved = false;
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (!r.finishing) {
                    if (r.fullscreen) {
                        lastIsOpaque = true;
                    }
                    if ((owner == null || r.app == owner) && lastIsOpaque && r.isDestroyable()) {
                        if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                            Slog.v("ActivityManager", "Destroying " + r + " in state " + r.getState() + " resumed=" + this.mResumedActivity + " pausing=" + this.mPausingActivity + " for reason " + reason);
                        }
                        if (destroyActivityLocked(r, true, reason)) {
                            activityRemoved = true;
                        }
                    }
                }
            }
        }
        if (activityRemoved) {
            this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean safelyDestroyActivityLocked(ActivityRecord r, String reason) {
        if (r.isDestroyable()) {
            if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                Slog.v("ActivityManager", "Destroying " + r + " in state " + r.getState() + " resumed=" + this.mResumedActivity + " pausing=" + this.mPausingActivity + " for reason " + reason);
            }
            return destroyActivityLocked(r, true, reason);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int releaseSomeActivitiesLocked(ProcessRecord app, ArraySet<TaskRecord> tasks, String reason) {
        if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
            Slog.d("ActivityManager", "Trying to release some activities in " + app);
        }
        int maxTasks = tasks.size() / 4;
        if (maxTasks < 1) {
            maxTasks = 1;
        }
        int numReleased = 0;
        int maxTasks2 = maxTasks;
        int taskNdx = 0;
        while (taskNdx < this.mTaskHistory.size() && maxTasks2 > 0) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            if (tasks.contains(task)) {
                if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
                    Slog.d("ActivityManager", "Looking for activities to release in " + task);
                }
                ArrayList<ActivityRecord> activities = task.mActivities;
                int curNum = 0;
                int actNdx = 0;
                while (actNdx < activities.size()) {
                    ActivityRecord activity = activities.get(actNdx);
                    if (activity.app == app && activity.isDestroyable()) {
                        if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
                            Slog.v("ActivityManager", "Destroying " + activity + " in state " + activity.getState() + " resumed=" + this.mResumedActivity + " pausing=" + this.mPausingActivity + " for reason " + reason);
                        }
                        destroyActivityLocked(activity, true, reason);
                        if (activities.get(actNdx) != activity) {
                            actNdx--;
                        }
                        curNum++;
                    }
                    actNdx++;
                }
                if (curNum > 0) {
                    numReleased += curNum;
                    maxTasks2--;
                    if (this.mTaskHistory.get(taskNdx) != task) {
                        taskNdx--;
                    }
                }
            }
            taskNdx++;
        }
        if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
            Slog.d("ActivityManager", "Done releasing: did " + numReleased + " activities");
        }
        return numReleased;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean destroyActivityLocked(ActivityRecord r, boolean removeFromApp, String reason) {
        if (ActivityManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_CLEANUP) {
            StringBuilder sb = new StringBuilder();
            sb.append("Removing activity from ");
            sb.append(reason);
            sb.append(": token=");
            sb.append(r);
            sb.append(", app=");
            sb.append(r.app != null ? r.app.processName : "(null)");
            Slog.v("ActivityManager", sb.toString());
        }
        if (r.isState(ActivityState.DESTROYING, ActivityState.DESTROYED)) {
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "activity " + r + " already destroying.skipping request with reason:" + reason);
            }
            return false;
        }
        EventLog.writeEvent((int) EventLogTags.AM_DESTROY_ACTIVITY, Integer.valueOf(r.userId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(r.getTask().taskId), r.shortComponentName, reason);
        boolean removedFromHistory = false;
        cleanUpActivityLocked(r, false, false);
        boolean hadApp = r.app != null;
        if (hadApp) {
            if (removeFromApp) {
                r.app.activities.remove(r);
                if (this.mService.mHeavyWeightProcess == r.app && r.app.activities.size() <= 0) {
                    this.mService.mHeavyWeightProcess = null;
                    this.mService.mHandler.sendEmptyMessage(25);
                }
                if (r.app.activities.isEmpty()) {
                    this.mService.mServices.updateServiceConnectionActivitiesLocked(r.app);
                    this.mService.updateLruProcessLocked(r.app, false, null);
                    this.mService.updateOomAdjLocked();
                }
            }
            try {
                if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
                    Slog.i("ActivityManager", "Destroying: " + r);
                }
                this.mService.getLifecycleManager().scheduleTransaction(r.app.thread, (IBinder) r.appToken, (ActivityLifecycleItem) DestroyActivityItem.obtain(r.finishing, r.configChangeFlags));
            } catch (Exception e) {
                Slog.w("ActivityManager", "Exception thrown during finish", e);
            }
            r.nowVisible = false;
            if (r.finishing && 0 == 0) {
                if (ActivityManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityManager", "Moving to DESTROYING: " + r + " (destroy requested)");
                }
                r.setState(ActivityState.DESTROYING, "destroyActivityLocked. finishing and not skipping destroy");
                Message msg = this.mHandler.obtainMessage(102, r);
                this.mHandler.sendMessageDelayed(msg, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
            } else {
                if (ActivityManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityManager", "Moving to DESTROYED: " + r + " (destroy skipped)");
                }
                r.setState(ActivityState.DESTROYED, "destroyActivityLocked. not finishing or skipping destroy");
                if (ActivityManagerDebugConfig.DEBUG_APP) {
                    Slog.v("ActivityManager", "Clearing app during destroy for activity " + r);
                }
                r.app = null;
            }
        } else if (r.finishing) {
            removeActivityFromHistoryLocked(r, reason + " hadNoApp");
            removedFromHistory = true;
        } else {
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "Moving to DESTROYED: " + r + " (no app)");
            }
            r.setState(ActivityState.DESTROYED, "destroyActivityLocked. not finishing and had no app");
            if (ActivityManagerDebugConfig.DEBUG_APP) {
                Slog.v("ActivityManager", "Clearing app during destroy for activity " + r);
            }
            r.app = null;
        }
        r.configChangeFlags = 0;
        if (!this.mLRUActivities.remove(r) && hadApp) {
            Slog.w("ActivityManager", "Activity " + r + " being finished, but not in LRU list");
        }
        return removedFromHistory;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void activityDestroyedLocked(IBinder token, String reason) {
        long origId = Binder.clearCallingIdentity();
        try {
            activityDestroyedLocked(ActivityRecord.forTokenLocked(token), reason);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    final void activityDestroyedLocked(ActivityRecord record, String reason) {
        if (record != null) {
            this.mHandler.removeMessages(102, record);
        }
        if (ActivityManagerDebugConfig.DEBUG_CONTAINERS) {
            Slog.d("ActivityManager", "activityDestroyedLocked: r=" + record);
        }
        if (isInStackLocked(record) != null && record.isState(ActivityState.DESTROYING, ActivityState.DESTROYED)) {
            cleanUpActivityLocked(record, true, false);
            removeActivityFromHistoryLocked(record, reason);
        }
        this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
    }

    private void removeHistoryRecordsForAppLocked(ArrayList<ActivityRecord> list, ProcessRecord app, String listName) {
        int i = list.size();
        if (ActivityManagerDebugConfig.DEBUG_CLEANUP) {
            Slog.v("ActivityManager", "Removing app " + app + " from list " + listName + " with " + i + " entries");
        }
        while (i > 0) {
            i--;
            ActivityRecord r = list.get(i);
            if (ActivityManagerDebugConfig.DEBUG_CLEANUP) {
                Slog.v("ActivityManager", "Record #" + i + " " + r);
            }
            if (r.app == app) {
                if (ActivityManagerDebugConfig.DEBUG_CLEANUP) {
                    Slog.v("ActivityManager", "---> REMOVING this entry!");
                }
                list.remove(i);
                removeTimeoutsForActivityLocked(r);
            }
        }
    }

    private boolean removeHistoryRecordsForAppLocked(ProcessRecord app) {
        boolean remove;
        removeHistoryRecordsForAppLocked(this.mLRUActivities, app, "mLRUActivities");
        removeHistoryRecordsForAppLocked(this.mStackSupervisor.mStoppingActivities, app, "mStoppingActivities");
        removeHistoryRecordsForAppLocked(this.mStackSupervisor.mGoingToSleepActivities, app, "mGoingToSleepActivities");
        removeHistoryRecordsForAppLocked(this.mStackSupervisor.mActivitiesWaitingForVisibleActivity, app, "mActivitiesWaitingForVisibleActivity");
        removeHistoryRecordsForAppLocked(this.mStackSupervisor.mFinishingActivities, app, "mFinishingActivities");
        boolean hasVisibleActivities = false;
        int i = numActivities();
        if (ActivityManagerDebugConfig.DEBUG_CLEANUP) {
            Slog.v("ActivityManager", "Removing app " + app + " from history with " + i + " entries");
        }
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            this.mTmpActivities.clear();
            this.mTmpActivities.addAll(activities);
            while (!this.mTmpActivities.isEmpty()) {
                int targetIndex = this.mTmpActivities.size() - 1;
                ActivityRecord r = this.mTmpActivities.remove(targetIndex);
                if (ActivityManagerDebugConfig.DEBUG_CLEANUP) {
                    Slog.v("ActivityManager", "Record #" + targetIndex + " " + r + ": app=" + r.app);
                }
                if (r.app == app) {
                    if (r.visible) {
                        hasVisibleActivities = true;
                    }
                    if ((!r.haveState && !r.stateNotNeeded) || r.finishing) {
                        remove = true;
                    } else {
                        boolean remove2 = r.visible;
                        if (!remove2 && r.launchCount > 2 && r.lastLaunchTime > SystemClock.uptimeMillis() - 60000) {
                            remove = true;
                        } else {
                            remove = false;
                        }
                    }
                    if (remove) {
                        if (ActivityManagerDebugConfig.DEBUG_ADD_REMOVE || ActivityManagerDebugConfig.DEBUG_CLEANUP) {
                            Slog.i("ActivityManager", "Removing activity " + r + " from stack at " + i + ": haveState=" + r.haveState + " stateNotNeeded=" + r.stateNotNeeded + " finishing=" + r.finishing + " state=" + r.getState() + " callers=" + Debug.getCallers(5));
                        }
                        if (!r.finishing) {
                            Slog.w("ActivityManager", "Force removing " + r + ": app died, no saved state");
                            EventLog.writeEvent((int) EventLogTags.AM_FINISH_ACTIVITY, Integer.valueOf(r.userId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(r.getTask().taskId), r.shortComponentName, "proc died without state saved");
                            if (r.getState() == ActivityState.RESUMED) {
                                this.mService.updateUsageStats(r, false);
                            }
                        }
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_ALL) {
                            Slog.v("ActivityManager", "Keeping entry, setting app to null");
                        }
                        if (ActivityManagerDebugConfig.DEBUG_APP) {
                            Slog.v("ActivityManager", "Clearing app during removeHistory for activity " + r);
                        }
                        r.app = null;
                        r.nowVisible = r.visible;
                        if (!r.haveState) {
                            if (ActivityManagerDebugConfig.DEBUG_SAVED_STATE) {
                                Slog.i("ActivityManager", "App died, clearing saved state of " + r);
                            }
                            r.icicle = null;
                        }
                    }
                    cleanUpActivityLocked(r, true, true);
                    if (remove) {
                        removeActivityFromHistoryLocked(r, "appDied");
                    }
                }
            }
        }
        return hasVisibleActivities;
    }

    private void updateTransitLocked(int transit, ActivityOptions options) {
        if (options != null) {
            ActivityRecord r = topRunningActivityLocked();
            if (r != null && !r.isState(ActivityState.RESUMED)) {
                r.updateOptionsLocked(options);
            } else {
                ActivityOptions.abort(options);
            }
        }
        this.mWindowManager.prepareAppTransition(transit, false);
    }

    private void updateTaskMovement(TaskRecord task, boolean toFront) {
        if (task.isPersistable) {
            task.mLastTimeMoved = System.currentTimeMillis();
            if (!toFront) {
                task.mLastTimeMoved *= -1;
            }
        }
        this.mStackSupervisor.invalidateTaskLayers();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveHomeStackTaskToTop() {
        if (!isActivityTypeHome()) {
            throw new IllegalStateException("Calling moveHomeStackTaskToTop() on non-home stack: " + this);
        }
        int top = this.mTaskHistory.size() - 1;
        if (top >= 0) {
            TaskRecord task = this.mTaskHistory.get(top);
            if (ActivityManagerDebugConfig.DEBUG_TASKS || ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityManager", "moveHomeStackTaskToTop: moving " + task);
            }
            this.mTaskHistory.remove(top);
            this.mTaskHistory.add(top, task);
            updateTaskMovement(task, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void moveTaskToFrontLocked(TaskRecord tr, boolean noAnimation, ActivityOptions options, AppTimeTracker timeTracker, String reason) {
        if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
            Slog.v("ActivityManager", "moveTaskToFront: " + tr);
        }
        ActivityStack topStack = getDisplay().getTopStack();
        ActivityRecord topActivity = topStack != null ? topStack.getTopActivity() : null;
        int numTasks = this.mTaskHistory.size();
        int index = this.mTaskHistory.indexOf(tr);
        if (numTasks == 0 || index < 0) {
            if (noAnimation) {
                ActivityOptions.abort(options);
                return;
            } else {
                updateTransitLocked(10, options);
                return;
            }
        }
        if (timeTracker != null) {
            for (int i = tr.mActivities.size() - 1; i >= 0; i--) {
                tr.mActivities.get(i).appTimeTracker = timeTracker;
            }
        }
        try {
            getDisplay().deferUpdateImeTarget();
            insertTaskAtTop(tr, null);
            ActivityRecord top = tr.getTopActivity();
            try {
                if (top != null && top.okToShowLocked()) {
                    ActivityRecord r = topRunningActivityLocked();
                    this.mStackSupervisor.moveFocusableActivityStackToFrontLocked(r, reason);
                    if (ActivityManagerDebugConfig.DEBUG_TRANSITION) {
                        Slog.v("ActivityManager", "Prepare to front transition: task=" + tr);
                    }
                    if (noAnimation) {
                        this.mWindowManager.prepareAppTransition(0, false);
                        if (r != null) {
                            this.mStackSupervisor.mNoAnimActivities.add(r);
                        }
                        ActivityOptions.abort(options);
                    } else {
                        updateTransitLocked(10, options);
                    }
                    if (canEnterPipOnTaskSwitch(topActivity, tr, null, options)) {
                        topActivity.supportsEnterPipOnTaskSwitch = true;
                    }
                    this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                    EventLog.writeEvent((int) EventLogTags.AM_TASK_TO_FRONT, Integer.valueOf(tr.userId), Integer.valueOf(tr.taskId));
                    this.mService.mTaskChangeNotificationController.notifyTaskMovedToFront(tr.taskId);
                    getDisplay().continueUpdateImeTarget();
                    return;
                }
                if (top != null) {
                    this.mStackSupervisor.mRecentTasks.add(top.getTask());
                }
                ActivityOptions.abort(options);
                getDisplay().continueUpdateImeTarget();
            } catch (Throwable th) {
                th = th;
                getDisplay().continueUpdateImeTarget();
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean moveTaskToBackLocked(int taskId) {
        TaskRecord tr = taskForIdLocked(taskId);
        if (tr == null) {
            Slog.i("ActivityManager", "moveTaskToBack: bad taskId=" + taskId);
            return false;
        }
        Slog.i("ActivityManager", "moveTaskToBack: " + tr);
        if (!this.mService.getLockTaskController().canMoveTaskToBack(tr)) {
            return false;
        }
        if (isTopStackOnDisplay() && this.mService.mController != null) {
            ActivityRecord next = topRunningActivityLocked(null, taskId);
            if (next == null) {
                next = topRunningActivityLocked(null, 0);
            }
            if (next != null) {
                boolean moveOK = true;
                try {
                    moveOK = this.mService.mController.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    this.mService.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }
                if (!moveOK) {
                    return false;
                }
            }
        }
        if (ActivityManagerDebugConfig.DEBUG_TRANSITION) {
            Slog.v("ActivityManager", "Prepare to back transition: task=" + taskId);
        }
        this.mTaskHistory.remove(tr);
        this.mTaskHistory.add(0, tr);
        updateTaskMovement(tr, false);
        this.mWindowManager.prepareAppTransition(11, false);
        moveToBack("moveTaskToBackLocked", tr);
        if (inPinnedWindowingMode()) {
            this.mStackSupervisor.removeStack(this);
            return true;
        }
        this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
        ActivityRecord ar = tr.topRunningActivityLocked();
        return ar == null || ActivityState.RESUMED != ar.getState();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void logStartActivity(int tag, ActivityRecord r, TaskRecord task) {
        Uri data = r.intent.getData();
        String strData = data != null ? data.toSafeString() : null;
        EventLog.writeEvent(tag, Integer.valueOf(r.userId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(task.taskId), r.shortComponentName, r.intent.getAction(), r.intent.getType(), strData, Integer.valueOf(r.intent.getFlags()));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void ensureVisibleActivitiesConfigurationLocked(ActivityRecord start, boolean preserveWindow) {
        if (start == null || !start.visible) {
            return;
        }
        TaskRecord startTask = start.getTask();
        boolean behindFullscreen = false;
        boolean updatedConfig = false;
        for (int taskIndex = this.mTaskHistory.indexOf(startTask); taskIndex >= 0; taskIndex--) {
            TaskRecord task = this.mTaskHistory.get(taskIndex);
            ArrayList<ActivityRecord> activities = task.mActivities;
            int activityIndex = start.getTask() == task ? activities.indexOf(start) : activities.size() - 1;
            while (true) {
                if (activityIndex < 0) {
                    break;
                }
                ActivityRecord r = activities.get(activityIndex);
                updatedConfig |= r.ensureActivityConfiguration(0, preserveWindow);
                if (r.fullscreen) {
                    behindFullscreen = true;
                    break;
                }
                activityIndex--;
            }
            if (behindFullscreen) {
                break;
            }
        }
        if (updatedConfig) {
            this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
        }
    }

    @Override // com.android.server.wm.StackWindowListener
    public void requestResize(Rect bounds) {
        this.mService.resizeStack(this.mStackId, bounds, true, false, false, -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resize(Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds) {
        if (!updateBoundsAllowed(bounds, tempTaskBounds, tempTaskInsetBounds)) {
            return;
        }
        Rect taskBounds = tempTaskBounds != null ? tempTaskBounds : bounds;
        Rect insetBounds = tempTaskInsetBounds != null ? tempTaskInsetBounds : taskBounds;
        this.mTmpBounds.clear();
        this.mTmpInsetBounds.clear();
        synchronized (this.mWindowManager.getWindowManagerLock()) {
            for (int i = this.mTaskHistory.size() - 1; i >= 0; i--) {
                TaskRecord task = this.mTaskHistory.get(i);
                if (task.isResizeable()) {
                    if (inFreeformWindowingMode()) {
                        this.mTmpRect2.set(task.getOverrideBounds());
                        fitWithinBounds(this.mTmpRect2, bounds);
                        task.updateOverrideConfiguration(this.mTmpRect2);
                    } else {
                        task.updateOverrideConfiguration(taskBounds, insetBounds);
                    }
                }
                this.mTmpBounds.put(task.taskId, task.getOverrideBounds());
                if (tempTaskInsetBounds != null) {
                    this.mTmpInsetBounds.put(task.taskId, tempTaskInsetBounds);
                }
            }
            this.mWindowContainerController.resize(bounds, this.mTmpBounds, this.mTmpInsetBounds);
            setBounds(bounds);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPipAnimationEndResize() {
        this.mWindowContainerController.onPipAnimationEndResize();
    }

    private static void fitWithinBounds(Rect bounds, Rect stackBounds) {
        if (stackBounds == null || stackBounds.isEmpty() || stackBounds.contains(bounds)) {
            return;
        }
        if (bounds.left < stackBounds.left || bounds.right > stackBounds.right) {
            int maxRight = stackBounds.right - (stackBounds.width() / 3);
            int horizontalDiff = stackBounds.left - bounds.left;
            if ((horizontalDiff < 0 && bounds.left >= maxRight) || bounds.left + horizontalDiff >= maxRight) {
                horizontalDiff = maxRight - bounds.left;
            }
            bounds.left += horizontalDiff;
            bounds.right += horizontalDiff;
        }
        if (bounds.top < stackBounds.top || bounds.bottom > stackBounds.bottom) {
            int maxBottom = stackBounds.bottom - (stackBounds.height() / 3);
            int verticalDiff = stackBounds.top - bounds.top;
            if ((verticalDiff < 0 && bounds.top >= maxBottom) || bounds.top + verticalDiff >= maxBottom) {
                verticalDiff = maxBottom - bounds.top;
            }
            bounds.top += verticalDiff;
            bounds.bottom += verticalDiff;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean willActivityBeVisibleLocked(IBinder token) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.appToken == token || r.getWindowingMode() == 5) {
                    return true;
                }
                if (r.fullscreen && !r.finishing) {
                    return false;
                }
            }
        }
        ActivityRecord r2 = ActivityRecord.forTokenLocked(token);
        if (r2 == null) {
            return false;
        }
        if (r2.getWindowingMode() == 5) {
            return true;
        }
        if (r2.finishing) {
            Slog.e("ActivityManager", "willActivityBeVisibleLocked: Returning false, would have returned true for r=" + r2);
        }
        return true ^ r2.finishing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void closeSystemDialogsLocked() {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if ((r.info.flags & 256) != 0) {
                    finishActivityLocked(r, 0, null, "close-sys", true);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x007a, code lost:
        if (r13.finishing == false) goto L54;
     */
    /* JADX WARN: Code restructure failed: missing block: B:33:0x007d, code lost:
        return true;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean finishDisabledPackageActivitiesLocked(java.lang.String r19, java.util.Set<java.lang.String> r20, boolean r21, boolean r22, int r23) {
        /*
            Method dump skipped, instructions count: 231
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStack.finishDisabledPackageActivitiesLocked(java.lang.String, java.util.Set, boolean, boolean, int):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getRunningTasks(List<TaskRecord> tasksOut, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode, int callingUid, boolean allowed) {
        boolean focusedStack = this.mStackSupervisor.getFocusedStack() == this;
        boolean topTask = true;
        int taskNdx = this.mTaskHistory.size() - 1;
        while (true) {
            int taskNdx2 = taskNdx;
            if (taskNdx2 >= 0) {
                TaskRecord task = this.mTaskHistory.get(taskNdx2);
                if (task.getTopActivity() != null && ((allowed || task.isActivityTypeHome() || task.effectiveUid == callingUid) && ((ignoreActivityType == 0 || task.getActivityType() != ignoreActivityType) && (ignoreWindowingMode == 0 || task.getWindowingMode() != ignoreWindowingMode)))) {
                    if (focusedStack && topTask) {
                        task.lastActiveTime = SystemClock.elapsedRealtime();
                        topTask = false;
                    }
                    tasksOut.add(task);
                }
                taskNdx = taskNdx2 - 1;
            } else {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unhandledBackLocked() {
        int top = this.mTaskHistory.size() - 1;
        if (ActivityManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityManager", "Performing unhandledBack(): top activity at " + top);
        }
        if (top >= 0) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(top).mActivities;
            int activityTop = activities.size() - 1;
            if (activityTop >= 0) {
                finishActivityLocked(activities.get(activityTop), 0, null, "unhandled-back", true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handleAppDiedLocked(ProcessRecord app) {
        if (this.mPausingActivity != null && this.mPausingActivity.app == app) {
            if (ActivityManagerDebugConfig.DEBUG_PAUSE || ActivityManagerDebugConfig.DEBUG_CLEANUP) {
                Slog.v("ActivityManager", "App died while pausing: " + this.mPausingActivity);
            }
            this.mPausingActivity = null;
        }
        if (this.mLastPausedActivity != null && this.mLastPausedActivity.app == app) {
            this.mLastPausedActivity = null;
            this.mLastNoHistoryActivity = null;
        }
        return removeHistoryRecordsForAppLocked(app);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleAppCrashLocked(ProcessRecord app) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.app == app) {
                    Slog.w("ActivityManager", "  Force finishing activity " + r.intent.getComponent().flattenToShortString());
                    r.app = null;
                    this.mWindowManager.prepareAppTransition(26, false);
                    finishCurrentActivityLocked(r, 0, false, "handleAppCrashedLocked");
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient, String dumpPackage, boolean needSep) {
        if (this.mTaskHistory.isEmpty()) {
            return false;
        }
        int taskNdx = this.mTaskHistory.size() - 1;
        while (true) {
            int taskNdx2 = taskNdx;
            if (taskNdx2 < 0) {
                return true;
            }
            TaskRecord task = this.mTaskHistory.get(taskNdx2);
            if (needSep) {
                pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            }
            pw.println("    Task id #" + task.taskId);
            pw.println("    mBounds=" + task.getOverrideBounds());
            pw.println("    mMinWidth=" + task.mMinWidth);
            pw.println("    mMinHeight=" + task.mMinHeight);
            pw.println("    mLastNonFullscreenBounds=" + task.mLastNonFullscreenBounds);
            pw.println("    * " + task);
            task.dump(pw, "      ");
            ActivityStackSupervisor.dumpHistoryList(fd, pw, this.mTaskHistory.get(taskNdx2).mActivities, "    ", "Hist", true, dumpAll ^ true, dumpClient, dumpPackage, false, null, task);
            taskNdx = taskNdx2 + (-1);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<ActivityRecord> getDumpActivitiesLocked(String name) {
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        if ("all".equals(name)) {
            for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
                activities.addAll(this.mTaskHistory.get(taskNdx).mActivities);
            }
        } else if ("top".equals(name)) {
            int top = this.mTaskHistory.size() - 1;
            if (top >= 0) {
                ArrayList<ActivityRecord> list = this.mTaskHistory.get(top).mActivities;
                int listTop = list.size() - 1;
                if (listTop >= 0) {
                    activities.add(list.get(listTop));
                }
            }
        } else {
            ActivityManagerService.ItemMatcher matcher = new ActivityManagerService.ItemMatcher();
            matcher.build(name);
            for (int taskNdx2 = this.mTaskHistory.size() - 1; taskNdx2 >= 0; taskNdx2--) {
                Iterator<ActivityRecord> it = this.mTaskHistory.get(taskNdx2).mActivities.iterator();
                while (it.hasNext()) {
                    ActivityRecord r1 = it.next();
                    if (matcher.match(r1, r1.intent.getComponent())) {
                        activities.add(r1);
                    }
                }
            }
        }
        return activities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord restartPackage(String packageName) {
        ActivityRecord starting = topRunningActivityLocked();
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord a = activities.get(activityNdx);
                if (a.info.packageName.equals(packageName)) {
                    a.forceNewConfig = true;
                    if (starting != null && a == starting && a.visible) {
                        a.startFreezingScreenLocked(starting.app, 256);
                    }
                }
            }
        }
        return starting;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeTask(TaskRecord task, String reason, int mode) {
        Iterator<ActivityRecord> it = task.mActivities.iterator();
        while (it.hasNext()) {
            ActivityRecord record = it.next();
            onActivityRemovedFromStack(record);
        }
        boolean removed = this.mTaskHistory.remove(task);
        if (removed) {
            EventLog.writeEvent((int) EventLogTags.AM_REMOVE_TASK, Integer.valueOf(task.taskId), Integer.valueOf(getStackId()));
        }
        removeActivitiesFromLRUListLocked(task);
        updateTaskMovement(task, true);
        if (mode == 0 && task.mActivities.isEmpty()) {
            boolean isVoiceSession = task.voiceSession != null;
            if (isVoiceSession) {
                try {
                    task.voiceSession.taskFinished(task.intent, task.taskId);
                } catch (RemoteException e) {
                }
            }
            if (task.autoRemoveFromRecents() || isVoiceSession) {
                this.mStackSupervisor.mRecentTasks.remove(task);
            }
            task.removeWindowContainer();
        }
        if (this.mTaskHistory.isEmpty()) {
            if (ActivityManagerDebugConfig.DEBUG_STACK) {
                Slog.i("ActivityManager", "removeTask: removing stack=" + this);
            }
            if (isOnHomeDisplay() && mode != 2 && this.mStackSupervisor.isFocusedStack(this)) {
                String myReason = reason + " leftTaskHistoryEmpty";
                if (!inMultiWindowMode() || !adjustFocusToNextFocusableStack(myReason)) {
                    this.mStackSupervisor.moveHomeStackToFront(myReason);
                }
            }
            if (isAttached()) {
                getDisplay().positionChildAtBottom(this);
            }
            if (!isActivityTypeHome()) {
                remove();
            }
        }
        task.setStack(null);
        if (inPinnedWindowingMode()) {
            this.mService.mTaskChangeNotificationController.notifyActivityUnpinned();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord createTaskRecord(int taskId, ActivityInfo info, Intent intent, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, boolean toTop) {
        return createTaskRecord(taskId, info, intent, voiceSession, voiceInteractor, toTop, null, null, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord createTaskRecord(int taskId, ActivityInfo info, Intent intent, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, boolean toTop, ActivityRecord activity, ActivityRecord source, ActivityOptions options) {
        TaskRecord task = TaskRecord.create(this.mService, taskId, info, intent, voiceSession, voiceInteractor);
        addTask(task, toTop, "createTaskRecord");
        int displayId = this.mDisplayId != -1 ? this.mDisplayId : 0;
        boolean isLockscreenShown = this.mService.mStackSupervisor.getKeyguardController().isKeyguardOrAodShowing(displayId);
        if (!this.mStackSupervisor.getLaunchParamsController().layoutTask(task, info.windowLayout, activity, source, options) && !matchParentBounds() && task.isResizeable() && !isLockscreenShown) {
            task.updateOverrideConfiguration(getOverrideBounds());
        }
        task.createWindowContainer(toTop, (info.flags & 1024) != 0);
        return task;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<TaskRecord> getAllTasks() {
        return new ArrayList<>(this.mTaskHistory);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addTask(TaskRecord task, boolean toTop, String reason) {
        addTask(task, toTop ? Integer.MAX_VALUE : 0, true, reason);
        if (toTop) {
            this.mWindowContainerController.positionChildAtTop(task.getWindowContainerController(), true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addTask(TaskRecord task, int position, boolean schedulePictureInPictureModeChange, String reason) {
        this.mTaskHistory.remove(task);
        int position2 = getAdjustedPositionForTask(task, position, null);
        boolean toTop = position2 >= this.mTaskHistory.size();
        ActivityStack prevStack = preAddTask(task, reason, toTop);
        this.mTaskHistory.add(position2, task);
        task.setStack(this);
        updateTaskMovement(task, toTop);
        postAddTask(task, prevStack, schedulePictureInPictureModeChange);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAt(TaskRecord task, int index) {
        if (task.getStack() != this) {
            throw new IllegalArgumentException("AS.positionChildAt: task=" + task + " is not a child of stack=" + this + " current parent=" + task.getStack());
        }
        task.updateOverrideConfigurationForStack(this);
        ActivityRecord topRunningActivity = task.topRunningActivityLocked();
        boolean wasResumed = topRunningActivity == task.getStack().mResumedActivity;
        insertTaskAtPosition(task, index);
        task.setStack(this);
        postAddTask(task, null, true);
        if (wasResumed) {
            if (this.mResumedActivity != null) {
                Log.wtf("ActivityManager", "mResumedActivity was already set when moving mResumedActivity from other stack to this stack mResumedActivity=" + this.mResumedActivity + " other mResumedActivity=" + topRunningActivity);
            }
            topRunningActivity.setState(ActivityState.RESUMED, "positionChildAt");
        }
        ensureActivitiesVisibleLocked(null, 0, false);
        this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
    }

    private ActivityStack preAddTask(TaskRecord task, String reason, boolean toTop) {
        ActivityStack prevStack = task.getStack();
        if (prevStack != null && prevStack != this) {
            prevStack.removeTask(task, reason, toTop ? 2 : 1);
        }
        return prevStack;
    }

    private void postAddTask(TaskRecord task, ActivityStack prevStack, boolean schedulePictureInPictureModeChange) {
        if (schedulePictureInPictureModeChange && prevStack != null) {
            this.mStackSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(task, prevStack);
        } else if (task.voiceSession != null) {
            try {
                task.voiceSession.taskStarted(task.intent, task.taskId);
            } catch (RemoteException e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveToFrontAndResumeStateIfNeeded(ActivityRecord r, boolean moveToFront, boolean setResume, boolean setPause, String reason) {
        if (!moveToFront) {
            return;
        }
        if (setResume) {
            r.setState(ActivityState.RESUMED, "moveToFrontAndResumeStateIfNeeded");
            updateLRUListLocked(r);
        }
        if (setPause) {
            this.mPausingActivity = r;
            schedulePauseTimeout(r);
        }
        moveToFront(reason);
    }

    public int getStackId() {
        return this.mStackId;
    }

    public String toString() {
        return "ActivityStack{" + Integer.toHexString(System.identityHashCode(this)) + " stackId=" + this.mStackId + " type=" + WindowConfiguration.activityTypeToString(getActivityType()) + " mode=" + WindowConfiguration.windowingModeToString(getWindowingMode()) + " visible=" + shouldBeVisible(null) + " translucent=" + isStackTranslucent(null) + ", " + this.mTaskHistory.size() + " tasks}";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onLockTaskPackagesUpdated() {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            this.mTaskHistory.get(taskNdx).setLockTaskAuth();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void executeAppTransition(ActivityOptions options) {
        this.mWindowManager.executeAppTransition();
        ActivityOptions.abort(options);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldSleepActivities() {
        ActivityDisplay display = getDisplay();
        if (this.mStackSupervisor.getFocusedStack() == this && this.mStackSupervisor.getKeyguardController().isKeyguardGoingAway()) {
            return false;
        }
        return display != null ? display.isSleeping() : this.mService.isSleepingLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || this.mService.isShuttingDownLocked();
    }

    private boolean shouldKeepResumedActivities(ActivityRecord resumedActivity, ActivityRecord resumingActivity) {
        if (resumedActivity == null || resumingActivity == null || resumedActivity.getWindowingMode() == 5 || resumingActivity.getWindowingMode() != 5) {
            return false;
        }
        return true;
    }

    public ArrayList<ActivityRecord> getHistoryActivitiesLocked() {
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        if (this.mTaskHistory != null && !this.mTaskHistory.isEmpty()) {
            for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
                activities.addAll(this.mTaskHistory.get(taskNdx).mActivities);
            }
        }
        return activities;
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, false);
        proto.write(1120986464258L, this.mStackId);
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            task.writeToProto(proto, 2246267895811L);
        }
        if (this.mResumedActivity != null) {
            this.mResumedActivity.writeIdentifierToProto(proto, 1146756268036L);
        }
        proto.write(1120986464261L, this.mDisplayId);
        if (!matchParentBounds()) {
            Rect bounds = getOverrideBounds();
            bounds.writeToProto(proto, 1146756268039L);
        }
        proto.write(1133871366150L, matchParentBounds());
        proto.end(token);
    }
}
