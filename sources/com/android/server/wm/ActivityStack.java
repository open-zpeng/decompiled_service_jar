package com.android.server.wm;

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.RemoteAction;
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
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.EventLogTags;
import com.android.server.am.PendingIntentRecord;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.wm.RootActivityContainer;
import com.google.android.collect.Sets;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class ActivityStack extends ConfigurationContainer {
    static final int DESTROY_ACTIVITIES_MSG = 105;
    private static final int DESTROY_TIMEOUT = 10000;
    static final int DESTROY_TIMEOUT_MSG = 102;
    static final int FINISH_AFTER_PAUSE = 1;
    static final int FINISH_AFTER_VISIBLE = 2;
    static final int FINISH_IMMEDIATELY = 0;
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
    static final int STACK_VISIBILITY_INVISIBLE = 2;
    static final int STACK_VISIBILITY_VISIBLE = 0;
    static final int STACK_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT = 1;
    private static final int STOP_TIMEOUT = 11000;
    static final int STOP_TIMEOUT_MSG = 104;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_ADD_REMOVE = "ActivityTaskManager";
    private static final String TAG_APP = "ActivityTaskManager";
    private static final String TAG_CLEANUP = "ActivityTaskManager";
    private static final String TAG_CONTAINERS = "ActivityTaskManager";
    private static final String TAG_PAUSE = "ActivityTaskManager";
    private static final String TAG_RELEASE = "ActivityTaskManager";
    private static final String TAG_RESULTS = "ActivityTaskManager";
    private static final String TAG_SAVED_STATE = "ActivityTaskManager";
    private static final String TAG_STACK = "ActivityTaskManager";
    private static final String TAG_STATES = "ActivityTaskManager";
    private static final String TAG_SWITCH = "ActivityTaskManager";
    private static final String TAG_TASKS = "ActivityTaskManager";
    private static final String TAG_TRANSITION = "ActivityTaskManager";
    private static final String TAG_USER_LEAVING = "ActivityTaskManager";
    private static final String TAG_VISIBILITY = "ActivityTaskManager";
    private static final long TRANSLUCENT_CONVERSION_TIMEOUT = 2000;
    static final int TRANSLUCENT_TIMEOUT_MSG = 106;
    boolean mConfigWillChange;
    int mCurrentUser;
    int mDisplayId;
    final Handler mHandler;
    protected final RootActivityContainer mRootActivityContainer;
    final ActivityTaskManagerService mService;
    final int mStackId;
    protected final ActivityStackSupervisor mStackSupervisor;
    TaskStack mTaskStack;
    private boolean mTopActivityOccludesKeyguard;
    private ActivityRecord mTopDismissingKeyguardActivity;
    private boolean mUpdateBoundsDeferred;
    private boolean mUpdateBoundsDeferredCalled;
    private boolean mUpdateDisplayedBoundsDeferredCalled;
    final WindowManagerService mWindowManager;
    private final ArrayList<TaskRecord> mTaskHistory = new ArrayList<>();
    private final ArrayList<ActivityRecord> mLRUActivities = new ArrayList<>();
    ActivityRecord mPausingActivity = null;
    ActivityRecord mLastPausedActivity = null;
    ActivityRecord mLastNoHistoryActivity = null;
    ActivityRecord mResumedActivity = null;
    ActivityRecord mTranslucentActivityWaiting = null;
    ArrayList<ActivityRecord> mUndrawnActivitiesBelowTopTranslucent = new ArrayList<>();
    boolean mForceHidden = false;
    boolean mInResumeTopActivity = false;
    private final Rect mDeferredBounds = new Rect();
    private final Rect mDeferredDisplayedBounds = new Rect();
    private int mRestoreOverrideWindowingMode = 0;
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final ActivityOptions mTmpOptions = ActivityOptions.makeBasic();
    private final ArrayList<ActivityRecord> mTmpActivities = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public enum ActivityState {
        INITIALIZING,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED,
        RESTARTING_PROCESS
    }

    /* loaded from: classes2.dex */
    @interface StackVisibility {
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public int getChildCount() {
        return this.mTaskHistory.size();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public TaskRecord getChildAt(int index) {
        return this.mTaskHistory.get(index);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public ActivityDisplay getParent() {
        return getDisplay();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setParent(ActivityDisplay parent) {
        ActivityDisplay current = getParent();
        if (current != parent) {
            this.mDisplayId = parent.mDisplayId;
            onParentChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public void onParentChanged() {
        ActivityDisplay display = getParent();
        if (display != null) {
            getConfiguration().windowConfiguration.setRotation(display.getWindowConfiguration().getRotation());
        }
        super.onParentChanged();
        if (display != null && inSplitScreenPrimaryWindowingMode()) {
            getStackDockedModeBounds(null, null, this.mTmpRect, this.mTmpRect2);
            this.mStackSupervisor.resizeDockedStackLocked(getRequestedOverrideBounds(), this.mTmpRect, this.mTmpRect2, null, null, true);
        }
        this.mRootActivityContainer.updateUIDsPresentOnDisplay();
    }

    /* loaded from: classes2.dex */
    private static class ScheduleDestroyArgs {
        final WindowProcessController mOwner;
        final String mReason;

        ScheduleDestroyArgs(WindowProcessController owner, String reason) {
            this.mOwner = owner;
            this.mReason = reason;
        }
    }

    /* loaded from: classes2.dex */
    private class ActivityStackHandler extends Handler {
        ActivityStackHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 101:
                    ActivityRecord r = (ActivityRecord) msg.obj;
                    Slog.w("ActivityTaskManager", "Activity pause timeout for " + r);
                    synchronized (ActivityStack.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (r.hasProcess()) {
                                ActivityTaskManagerService activityTaskManagerService = ActivityStack.this.mService;
                                WindowProcessController windowProcessController = r.app;
                                long j = r.pauseTime;
                                activityTaskManagerService.logAppTooSlow(windowProcessController, j, "pausing " + r);
                            }
                            ActivityStack.this.activityPausedLocked(r.appToken, true);
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                case 102:
                    ActivityRecord r2 = (ActivityRecord) msg.obj;
                    Slog.w("ActivityTaskManager", "Activity destroy timeout for " + r2);
                    synchronized (ActivityStack.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            ActivityStack.this.activityDestroyedLocked((IBinder) (r2 != null ? r2.appToken : null), "destroyTimeout");
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                case 103:
                    ActivityRecord r3 = (ActivityRecord) msg.obj;
                    synchronized (ActivityStack.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (r3.continueLaunchTickingLocked()) {
                                ActivityTaskManagerService activityTaskManagerService2 = ActivityStack.this.mService;
                                WindowProcessController windowProcessController2 = r3.app;
                                long j2 = r3.launchTickTime;
                                activityTaskManagerService2.logAppTooSlow(windowProcessController2, j2, "launching " + r3);
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                case 104:
                    ActivityRecord r4 = (ActivityRecord) msg.obj;
                    Slog.w("ActivityTaskManager", "Activity stop timeout for " + r4);
                    synchronized (ActivityStack.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (r4.isInHistory()) {
                                r4.activityStoppedLocked(null, null, null);
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                case 105:
                    ScheduleDestroyArgs args = (ScheduleDestroyArgs) msg.obj;
                    synchronized (ActivityStack.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            ActivityStack.this.destroyActivitiesLocked(args.mOwner, args.mReason);
                        } finally {
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                case 106:
                    synchronized (ActivityStack.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            ActivityStack.this.notifyActivityDrawnLocked(null);
                        } finally {
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
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
        this.mRootActivityContainer = this.mService.mRootActivityContainer;
        this.mHandler = new ActivityStackHandler(supervisor.mLooper);
        this.mWindowManager = this.mService.mWindowManager;
        this.mStackId = stackId;
        this.mCurrentUser = this.mService.mAmInternal.getCurrentUserId();
        this.mDisplayId = display.mDisplayId;
        setActivityType(activityType);
        createTaskStack(display.mDisplayId, onTop, this.mTmpRect2);
        setWindowingMode(windowingMode, false, false, false, false, true);
        display.addChild(this, onTop ? Integer.MAX_VALUE : Integer.MIN_VALUE);
    }

    void createTaskStack(int displayId, boolean onTop, Rect outBounds) {
        DisplayContent dc = this.mWindowManager.mRoot.getDisplayContent(displayId);
        if (dc == null) {
            throw new IllegalArgumentException("Trying to add stackId=" + this.mStackId + " to unknown displayId=" + displayId);
        }
        this.mTaskStack = new TaskStack(this.mWindowManager, this.mStackId, this);
        dc.setStackOnDisplay(this.mStackId, onTop, this.mTaskStack);
        if (this.mTaskStack.matchParentBounds()) {
            outBounds.setEmpty();
        } else {
            this.mTaskStack.getRawBounds(outBounds);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getTaskStack() {
        return this.mTaskStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onActivityStateChanged(ActivityRecord record, ActivityState state, String reason) {
        if (record == this.mResumedActivity && state != ActivityState.RESUMED) {
            setResumedActivity(null, reason + " - onActivityStateChanged");
        }
        if (state == ActivityState.RESUMED) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.v("ActivityTaskManager", "set resumed activity to:" + record + " reason:" + reason);
            }
            setResumedActivity(record, reason + " - onActivityStateChanged");
            if (record == this.mRootActivityContainer.getTopResumedActivity()) {
                this.mService.setResumedActivityUncheckLocked(record, reason);
            }
            this.mStackSupervisor.mRecentTasks.add(record.getTaskRecord());
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        boolean hasNewOverrideBounds;
        ActivityDisplay display;
        TaskRecord topTask;
        int prevWindowingMode = getWindowingMode();
        boolean prevIsAlwaysOnTop = isAlwaysOnTop();
        int prevRotation = getWindowConfiguration().getRotation();
        int prevDensity = getConfiguration().densityDpi;
        int prevScreenW = getConfiguration().screenWidthDp;
        int prevScreenH = getConfiguration().screenHeightDp;
        Rect newBounds = this.mTmpRect;
        getBounds(newBounds);
        super.onConfigurationChanged(newParentConfig);
        ActivityDisplay display2 = getDisplay();
        if (display2 != null && getTaskStack() != null) {
            boolean windowingModeChanged = prevWindowingMode != getWindowingMode();
            int overrideWindowingMode = getRequestedOverrideWindowingMode();
            boolean hasNewOverrideBounds2 = false;
            if (overrideWindowingMode == 2) {
                hasNewOverrideBounds2 = getTaskStack().calculatePinnedBoundsForConfigChange(newBounds);
            } else if (!matchParentBounds()) {
                int newRotation = getWindowConfiguration().getRotation();
                boolean rotationChanged = prevRotation != newRotation;
                if (rotationChanged) {
                    display2.mDisplayContent.rotateBounds(newParentConfig.windowConfiguration.getBounds(), prevRotation, newRotation, newBounds);
                    hasNewOverrideBounds2 = true;
                }
                if ((overrideWindowingMode == 3 || overrideWindowingMode == 4) && (rotationChanged || windowingModeChanged || prevDensity != getConfiguration().densityDpi || prevScreenW != getConfiguration().screenWidthDp || prevScreenH != getConfiguration().screenHeightDp)) {
                    getTaskStack().calculateDockedBoundsForConfigChange(newParentConfig, newBounds);
                    hasNewOverrideBounds2 = true;
                }
            }
            if (!windowingModeChanged) {
                hasNewOverrideBounds = hasNewOverrideBounds2;
            } else {
                if (overrideWindowingMode == 3) {
                    getStackDockedModeBounds(null, null, newBounds, this.mTmpRect2);
                    setTaskDisplayedBounds(null);
                    setTaskBounds(newBounds);
                    setBounds(newBounds);
                    newBounds.set(newBounds);
                } else if (overrideWindowingMode == 4) {
                    Rect dockedBounds = display2.getSplitScreenPrimaryStack().getBounds();
                    boolean isMinimizedDock = display2.mDisplayContent.getDockedDividerController().isMinimizedDock();
                    if (isMinimizedDock && (topTask = display2.getSplitScreenPrimaryStack().topTask()) != null) {
                        dockedBounds = topTask.getBounds();
                    }
                    getStackDockedModeBounds(dockedBounds, null, newBounds, this.mTmpRect2);
                    hasNewOverrideBounds2 = true;
                }
                display2.onStackWindowingModeChanged(this);
                hasNewOverrideBounds = hasNewOverrideBounds2;
            }
            if (hasNewOverrideBounds) {
                display = display2;
                this.mRootActivityContainer.resizeStack(this, new Rect(newBounds), null, null, true, true, true);
            } else {
                display = display2;
            }
            if (prevIsAlwaysOnTop != isAlwaysOnTop()) {
                display.positionChildAtTop(this, false);
            }
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void setWindowingMode(int windowingMode) {
        setWindowingMode(windowingMode, false, false, false, false, false);
    }

    private static boolean isTransientWindowingMode(int windowingMode) {
        return windowingMode == 3 || windowingMode == 4;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowingMode(final int preferredWindowingMode, final boolean animate, final boolean showRecents, final boolean enteringSplitScreenMode, final boolean deferEnsuringVisibility, final boolean creating) {
        this.mWindowManager.inSurfaceTransaction(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityStack$7heVv97BezfdSlHS0oo3lugbypI
            @Override // java.lang.Runnable
            public final void run() {
                ActivityStack.this.lambda$setWindowingMode$0$ActivityStack(preferredWindowingMode, animate, showRecents, enteringSplitScreenMode, deferEnsuringVisibility, creating);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: setWindowingModeInSurfaceTransaction */
    public void lambda$setWindowingMode$0$ActivityStack(int preferredWindowingMode, boolean animate, boolean showRecents, boolean enteringSplitScreenMode, boolean deferEnsuringVisibility, boolean creating) {
        int likelyResolvedMode;
        int currentMode = getWindowingMode();
        int currentOverrideMode = getRequestedOverrideWindowingMode();
        ActivityDisplay display = getDisplay();
        TaskRecord topTask = topTask();
        ActivityStack splitScreenStack = display.getSplitScreenPrimaryStack();
        int windowingMode = preferredWindowingMode;
        if (preferredWindowingMode == 0 && isTransientWindowingMode(currentMode)) {
            windowingMode = this.mRestoreOverrideWindowingMode;
        }
        this.mTmpOptions.setLaunchWindowingMode(windowingMode);
        if (!creating) {
            windowingMode = display.validateWindowingMode(windowingMode, null, topTask, getActivityType());
        }
        int windowingMode2 = (splitScreenStack == this && windowingMode == 4) ? this.mRestoreOverrideWindowingMode : windowingMode;
        boolean alreadyInSplitScreenMode = display.hasSplitScreenPrimaryStack();
        boolean sendNonResizeableNotification = !enteringSplitScreenMode;
        if (alreadyInSplitScreenMode && windowingMode2 == 1 && sendNonResizeableNotification && isActivityTypeStandardOrUndefined()) {
            boolean preferredSplitScreen = preferredWindowingMode == 3 || preferredWindowingMode == 4;
            if (preferredSplitScreen || creating) {
                this.mService.getTaskChangeNotificationController().notifyActivityDismissingDockedStack();
                ActivityStack primarySplitStack = display.getSplitScreenPrimaryStack();
                primarySplitStack.lambda$setWindowingMode$0$ActivityStack(0, false, false, false, true, primarySplitStack == this ? creating : false);
            }
        }
        if (currentMode == windowingMode2) {
            getRequestedOverrideConfiguration().windowConfiguration.setWindowingMode(windowingMode2);
            return;
        }
        WindowManagerService wm = this.mService.mWindowManager;
        ActivityRecord topActivity = getTopActivity();
        int likelyResolvedMode2 = windowingMode2;
        if (windowingMode2 == 0) {
            ConfigurationContainer parent = getParent();
            int likelyResolvedMode3 = parent != null ? parent.getWindowingMode() : 1;
            likelyResolvedMode = likelyResolvedMode3;
        } else {
            likelyResolvedMode = likelyResolvedMode2;
        }
        if (sendNonResizeableNotification && likelyResolvedMode != 1 && topActivity != null && topActivity.isNonResizableOrForcedResizable() && !topActivity.noDisplay) {
            String packageName = topActivity.appInfo.packageName;
            this.mService.getTaskChangeNotificationController().notifyActivityForcedResizable(topTask.taskId, 1, packageName);
        }
        wm.deferSurfaceLayout();
        if (!animate && topActivity != null) {
            try {
                this.mStackSupervisor.mNoAnimActivities.add(topActivity);
            } catch (Throwable th) {
                th = th;
                if (showRecents) {
                    ActivityStack recentStack = display.getOrCreateStack(4, 3, true);
                    recentStack.moveToFront("setWindowingMode");
                    this.mService.mWindowManager.showRecentApps();
                }
                wm.continueSurfaceLayout();
                throw th;
            }
        }
        try {
            super.setWindowingMode(windowingMode2);
            windowingMode2 = getWindowingMode();
            if (creating) {
                if (showRecents && !alreadyInSplitScreenMode && this.mDisplayId == 0 && windowingMode2 == 3) {
                    ActivityStack recentStack2 = display.getOrCreateStack(4, 3, true);
                    recentStack2.moveToFront("setWindowingMode");
                    this.mService.mWindowManager.showRecentApps();
                }
                wm.continueSurfaceLayout();
                return;
            }
            try {
                if (windowingMode2 == 2 || currentMode == 2) {
                    throw new IllegalArgumentException("Changing pinned windowing mode not currently supported");
                }
                if (windowingMode2 == 3 && splitScreenStack != null) {
                    throw new IllegalArgumentException("Setting primary split-screen windowing mode while there is already one isn't currently supported");
                }
                if (isTransientWindowingMode(windowingMode2) && !isTransientWindowingMode(currentMode)) {
                    this.mRestoreOverrideWindowingMode = currentOverrideMode;
                }
                this.mTmpRect2.setEmpty();
                if (windowingMode2 != 1) {
                    if (this.mTaskStack.matchParentBounds()) {
                        this.mTmpRect2.setEmpty();
                    } else {
                        this.mTaskStack.getRawBounds(this.mTmpRect2);
                    }
                }
                if (!Objects.equals(getRequestedOverrideBounds(), this.mTmpRect2)) {
                    resize(this.mTmpRect2, null, null);
                }
                if (showRecents && !alreadyInSplitScreenMode && this.mDisplayId == 0 && windowingMode2 == 3) {
                    ActivityStack recentStack3 = display.getOrCreateStack(4, 3, true);
                    recentStack3.moveToFront("setWindowingMode");
                    this.mService.mWindowManager.showRecentApps();
                }
                wm.continueSurfaceLayout();
                if (deferEnsuringVisibility) {
                    return;
                }
                this.mRootActivityContainer.ensureActivitiesVisible(null, 0, true);
                this.mRootActivityContainer.resumeFocusedStacksTopActivities();
            } catch (Throwable th2) {
                th = th2;
                if (showRecents && !alreadyInSplitScreenMode && this.mDisplayId == 0 && windowingMode2 == 3) {
                    ActivityStack recentStack4 = display.getOrCreateStack(4, 3, true);
                    recentStack4.moveToFront("setWindowingMode");
                    this.mService.mWindowManager.showRecentApps();
                }
                wm.continueSurfaceLayout();
                throw th;
            }
        } catch (Throwable th3) {
            th = th3;
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public boolean isCompatible(int windowingMode, int activityType) {
        if (activityType == 0) {
            activityType = 1;
        }
        return super.isCompatible(windowingMode, activityType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reparent(ActivityDisplay activityDisplay, boolean onTop, boolean displayRemoved) {
        removeFromDisplay();
        this.mTmpRect2.setEmpty();
        TaskStack taskStack = this.mTaskStack;
        if (taskStack == null) {
            Log.w("ActivityTaskManager", "Task stack is not valid when reparenting.");
        } else {
            taskStack.reparent(activityDisplay.mDisplayId, this.mTmpRect2, onTop);
        }
        setBounds(this.mTmpRect2.isEmpty() ? null : this.mTmpRect2);
        activityDisplay.addChild(this, onTop ? Integer.MAX_VALUE : Integer.MIN_VALUE);
        if (!displayRemoved) {
            postReparent();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postReparent() {
        adjustFocusToNextFocusableStack("reparent", true);
        this.mRootActivityContainer.resumeFocusedStacksTopActivities();
        this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
    }

    private void removeFromDisplay() {
        ActivityDisplay display = getDisplay();
        if (display != null) {
            display.removeChild(this);
        }
        this.mDisplayId = -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void remove() {
        removeFromDisplay();
        TaskStack taskStack = this.mTaskStack;
        if (taskStack != null) {
            taskStack.removeIfPossible();
            this.mTaskStack = null;
        }
        onParentChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getDisplay() {
        return this.mRootActivityContainer.getActivityDisplay(this.mDisplayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStackDockedModeBounds(Rect dockedBounds, Rect currentTempTaskBounds, Rect outStackBounds, Rect outTempTaskBounds) {
        TaskStack taskStack = this.mTaskStack;
        if (taskStack != null) {
            taskStack.getStackDockedModeBoundsLocked(getParent().getConfiguration(), dockedBounds, currentTempTaskBounds, outStackBounds, outTempTaskBounds);
            return;
        }
        outStackBounds.setEmpty();
        outTempTaskBounds.setEmpty();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareFreezingTaskBounds() {
        TaskStack taskStack = this.mTaskStack;
        if (taskStack != null) {
            taskStack.prepareFreezingTaskBounds();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getWindowContainerBounds(Rect outBounds) {
        TaskStack taskStack = this.mTaskStack;
        if (taskStack != null) {
            taskStack.getBounds(outBounds);
        } else {
            outBounds.setEmpty();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildWindowContainerAtTop(TaskRecord child) {
        TaskStack taskStack = this.mTaskStack;
        if (taskStack != null) {
            taskStack.positionChildAtTop(child.getTask(), true);
        }
    }

    void positionChildWindowContainerAtBottom(TaskRecord child) {
        try {
            boolean z = true;
            ActivityStack nextFocusableStack = getDisplay().getNextFocusableStack(child.getStack(), true);
            if (this.mTaskStack != null) {
                TaskStack taskStack = this.mTaskStack;
                Task task = child.getTask();
                if (nextFocusableStack != null) {
                    z = false;
                }
                taskStack.positionChildAtBottom(task, z);
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean deferScheduleMultiWindowModeChanged() {
        if (!inPinnedWindowingMode() || getTaskStack() == null) {
            return false;
        }
        return getTaskStack().deferScheduleMultiWindowModeChanged();
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
        if (this.mUpdateBoundsDeferred) {
            this.mUpdateBoundsDeferred = false;
            if (this.mUpdateBoundsDeferredCalled) {
                setTaskBounds(this.mDeferredBounds);
                setBounds(this.mDeferredBounds);
            }
            if (this.mUpdateDisplayedBoundsDeferredCalled) {
                setTaskDisplayedBounds(this.mDeferredDisplayedBounds);
            }
        }
    }

    boolean updateBoundsAllowed(Rect bounds) {
        if (this.mUpdateBoundsDeferred) {
            if (bounds != null) {
                this.mDeferredBounds.set(bounds);
            } else {
                this.mDeferredBounds.setEmpty();
            }
            this.mUpdateBoundsDeferredCalled = true;
            return false;
        }
        return true;
    }

    boolean updateDisplayedBoundsAllowed(Rect bounds) {
        if (this.mUpdateBoundsDeferred) {
            if (bounds != null) {
                this.mDeferredDisplayedBounds.set(bounds);
            } else {
                this.mDeferredDisplayedBounds.setEmpty();
            }
            this.mUpdateDisplayedBoundsDeferredCalled = true;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivityLocked(boolean focusableOnly) {
        ActivityRecord r;
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord taskRecord = this.mTaskHistory.get(taskNdx);
            if (taskRecord != null && (r = taskRecord.topRunningActivityLocked()) != null && (!focusableOnly || r.isFocusable())) {
                return r;
            }
        }
        return null;
    }

    ActivityRecord topRunningNonOverlayTaskActivity() {
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
        TaskRecord task = r.getTaskRecord();
        ActivityStack stack = r.getActivityStack();
        if (stack == null || !task.mActivities.contains(r) || !this.mTaskHistory.contains(task)) {
            return null;
        }
        if (stack != this) {
            Slog.w("ActivityTaskManager", "Illegal state! task does not point to stack it is in.");
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSingleTaskInstance() {
        ActivityDisplay display = getDisplay();
        return display != null && display.isSingleTaskInstance();
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
            display.moveHomeStackToFront(reason + " returnToHome");
        }
        boolean movingTask = task != null;
        display.positionChildAtTop(this, movingTask ? false : true, reason);
        if (movingTask) {
            insertTaskAtTop(task, null);
        }
    }

    void moveToBack(String reason, TaskRecord task) {
        if (!isAttached()) {
            return;
        }
        if (getWindowingMode() == 3) {
            setWindowingMode(0);
        }
        try {
            getDisplay().positionChildAtBottom(this, reason);
            if (task != null) {
                insertTaskAtBottom(task);
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusable() {
        ActivityRecord r = topRunningActivityLocked();
        return this.mRootActivityContainer.isFocusable(this, r != null && r.isFocusable());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusableAndVisible() {
        return isFocusable() && shouldBeVisible(null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean isAttached() {
        ActivityDisplay display = getDisplay();
        return (display == null || display.isRemoved()) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void findTaskLocked(ActivityRecord target, RootActivityContainer.FindTaskResult result) {
        ActivityInfo info;
        int userId;
        boolean z;
        boolean taskIsDocument;
        Uri taskDocumentData;
        ActivityStack activityStack = this;
        Intent intent = target.intent;
        ActivityInfo info2 = target.info;
        ComponentName cls = intent.getComponent();
        if (info2.targetActivity != null) {
            cls = new ComponentName(info2.packageName, info2.targetActivity);
        }
        int userId2 = UserHandle.getUserId(info2.applicationInfo.uid);
        boolean z2 = true;
        boolean isDocument = intent.isDocument() & true;
        Uri documentData = isDocument ? intent.getData() : null;
        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
            Slog.d("ActivityTaskManager", "Looking for task of " + target + " in " + activityStack);
        }
        int taskNdx = activityStack.mTaskHistory.size() - 1;
        while (taskNdx >= 0) {
            TaskRecord task = activityStack.mTaskHistory.get(taskNdx);
            if (task.voiceSession != null) {
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityTaskManager", "Skipping " + task + ": voice session");
                }
                info = info2;
                userId = userId2;
                z = z2;
            } else if (task.userId != userId2) {
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityTaskManager", "Skipping " + task + ": different user");
                }
                info = info2;
                userId = userId2;
                z = z2;
            } else {
                ActivityRecord r = task.getTopActivity(false);
                if (r == null || r.finishing || r.mUserId != userId2) {
                    info = info2;
                    userId = userId2;
                    z = z2;
                } else if (r.launchMode == 3) {
                    info = info2;
                    userId = userId2;
                    z = true;
                } else if (!r.hasCompatibleActivityType(target)) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                        Slog.d("ActivityTaskManager", "Skipping " + task + ": mismatch activity type");
                    }
                    info = info2;
                    userId = userId2;
                    z = true;
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
                    if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                        StringBuilder sb = new StringBuilder();
                        userId = userId2;
                        sb.append("Comparing existing cls=");
                        sb.append(task.realActivity != null ? task.realActivity.flattenToShortString() : "");
                        sb.append("/aff=");
                        sb.append(r.getTaskRecord().rootAffinity);
                        sb.append(" to new cls=");
                        sb.append(intent.getComponent().flattenToShortString());
                        sb.append("/aff=");
                        sb.append(info2.taskAffinity);
                        Slog.d("ActivityTaskManager", sb.toString());
                    } else {
                        userId = userId2;
                    }
                    info = info2;
                    if (task.realActivity != null && task.realActivity.compareTo(cls) == 0 && Objects.equals(documentData, taskDocumentData)) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            Slog.d("ActivityTaskManager", "Found matching class!");
                        }
                        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            Slog.d("ActivityTaskManager", "For Intent " + intent + " bringing to top: " + r.intent);
                        }
                        result.mRecord = r;
                        result.mIdealMatch = true;
                        return;
                    }
                    if (affinityIntent == null || affinityIntent.getComponent() == null) {
                        z = true;
                    } else if (affinityIntent.getComponent().compareTo(cls) != 0) {
                        z = true;
                    } else if (!Objects.equals(documentData, taskDocumentData)) {
                        z = true;
                    } else {
                        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            Slog.d("ActivityTaskManager", "Found matching class!");
                        }
                        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            Slog.d("ActivityTaskManager", "For Intent " + intent + " bringing to top: " + r.intent);
                        }
                        result.mRecord = r;
                        result.mIdealMatch = true;
                        return;
                    }
                    if (!isDocument && !taskIsDocument && result.mRecord == null && task.rootAffinity != null) {
                        if (task.rootAffinity.equals(target.taskAffinity)) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                                Slog.d("ActivityTaskManager", "Found matching affinity candidate!");
                            }
                            result.mRecord = r;
                            result.mIdealMatch = false;
                        }
                    } else if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                        Slog.d("ActivityTaskManager", "Not a match: " + task);
                    }
                }
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityTaskManager", "Skipping " + task + ": mismatch root " + r);
                }
            }
            taskNdx--;
            z2 = z;
            userId2 = userId;
            info2 = info;
            activityStack = this;
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
                if (r.okToShowLocked() && !r.finishing && r.mUserId == userId) {
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
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityTaskManager", "switchUser: stack=" + getStackId() + " moving " + task + " to top");
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
        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityTaskManager", "Moving to RESUMED: " + r + " (starting new instance) callers=" + Debug.getCallers(5));
        }
        r.setState(ActivityState.RESUMED, "minimalResumeActivityLocked");
        r.completeResumeLocked();
        if (ActivityTaskManagerDebugConfig.DEBUG_SAVED_STATE) {
            Slog.i("ActivityTaskManager", "Launch completed; removing icicle of " + r.icicle);
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
            Slog.d("ActivityTaskManager", "awakeFromSleepingLocked: previously pausing activity didn't pause");
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
                if (userId == ar.mUserId && packageName.equals(ar.packageName)) {
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
            if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                Slog.v("ActivityTaskManager", "Sleep needs to pause " + this.mResumedActivity);
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING) {
                Slog.v("ActivityTaskManager", "Sleep => pause with userLeaving=false");
            }
            startPausingLocked(false, true, null, false);
            shouldSleep = false;
        } else if (this.mPausingActivity != null) {
            if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                Slog.v("ActivityTaskManager", "Sleep still waiting to pause " + this.mPausingActivity);
            }
            shouldSleep = false;
        }
        if (!shuttingDown) {
            if (containsActivityFromStack(this.mStackSupervisor.mStoppingActivities)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityTaskManager", "Sleep still need to stop " + this.mStackSupervisor.mStoppingActivities.size() + " activities");
                }
                this.mStackSupervisor.scheduleIdleLocked();
                shouldSleep = false;
            }
            if (containsActivityFromStack(this.mStackSupervisor.mGoingToSleepActivities)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityTaskManager", "Sleep still need to sleep " + this.mStackSupervisor.mGoingToSleepActivities.size() + " activities");
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
            if (r.getActivityStack() == this) {
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
        if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
            Slog.v("ActivityTaskManager", "Waiting for pause to complete...");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean startPausingLocked(boolean userLeaving, boolean uiSleeping, ActivityRecord resuming, boolean pauseImmediately) {
        if (this.mPausingActivity != null) {
            Slog.wtf("ActivityTaskManager", "Going to pause when pause is already pending for " + this.mPausingActivity + " state=" + this.mPausingActivity.getState());
            if (!shouldSleepActivities()) {
                completePauseLocked(false, resuming);
            }
        }
        ActivityRecord prev = this.mResumedActivity;
        if (SharedDisplayContainer.shouldPauseActivity(userLeaving, uiSleeping, resuming, prev, pauseImmediately)) {
            if (prev == null) {
                if (resuming == null) {
                    Slog.wtf("ActivityTaskManager", "Trying to pause when nothing is resumed");
                    this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
                return false;
            } else if (prev == resuming) {
                Slog.wtf("ActivityTaskManager", "Trying to pause activity that is in process of being resumed");
                return false;
            } else {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "Moving to PAUSING: " + prev);
                } else if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityTaskManager", "Start pausing: " + prev);
                }
                this.mPausingActivity = prev;
                this.mLastPausedActivity = prev;
                this.mLastNoHistoryActivity = ((prev.intent.getFlags() & 1073741824) == 0 && (prev.info.flags & 128) == 0) ? null : prev;
                prev.setState(ActivityState.PAUSING, "startPausingLocked");
                prev.getTaskRecord().touchActiveTime();
                clearLaunchTime(prev);
                this.mService.updateCpuStats();
                if (prev.attachedToProcess()) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                        Slog.v("ActivityTaskManager", "Enqueueing pending pause: " + prev);
                    }
                    try {
                        int i = prev.mUserId;
                        int identityHashCode = System.identityHashCode(prev);
                        String str = prev.shortComponentName;
                        EventLogTags.writeAmPauseActivity(i, identityHashCode, str, "userLeaving=" + userLeaving);
                        int flags = resuming != null ? ActivityInfoManager.getActivityFlags(resuming.intent) : 0;
                        this.mService.getLifecycleManager().scheduleTransaction(prev.app.getThread(), (IBinder) prev.appToken, PauseActivityItem.obtain(prev.finishing, userLeaving, prev.configChangeFlags, pauseImmediately, flags));
                    } catch (Exception e) {
                        Slog.w("ActivityTaskManager", "Exception thrown during pause", e);
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
                    } else if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                        Slog.v("ActivityTaskManager", "Key dispatch not paused for screen off");
                    }
                    if (pauseImmediately) {
                        completePauseLocked(false, resuming);
                        return false;
                    }
                    schedulePauseTimeout(prev);
                    return true;
                }
                if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityTaskManager", "Activity not running, resuming next.");
                }
                if (resuming == null) {
                    this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
                return false;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void activityPausedLocked(IBinder token, boolean timeout) {
        if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
            Slog.v("ActivityTaskManager", "Activity paused: token=" + token + ", timeout=" + timeout);
        }
        ActivityRecord r = isInStackLocked(token);
        if (r != null) {
            this.mHandler.removeMessages(101, r);
            if (this.mPausingActivity == r) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Moving to PAUSED: ");
                    sb.append(r);
                    sb.append(timeout ? " (due to timeout)" : " (pause complete)");
                    Slog.v("ActivityTaskManager", sb.toString());
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
            objArr[0] = Integer.valueOf(r.mUserId);
            objArr[1] = Integer.valueOf(System.identityHashCode(r));
            objArr[2] = r.shortComponentName;
            ActivityRecord activityRecord = this.mPausingActivity;
            objArr[3] = activityRecord != null ? activityRecord.shortComponentName : "(none)";
            EventLog.writeEvent((int) EventLogTags.AM_FAILED_TO_PAUSE, objArr);
            if (r.isState(ActivityState.PAUSING)) {
                r.setState(ActivityState.PAUSED, "activityPausedLocked");
                if (r.finishing) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                        Slog.v("ActivityTaskManager", "Executing finish of failed to pause activity: " + r);
                    }
                    finishCurrentActivityLocked(r, 2, false, "activityPausedLocked");
                }
            }
        }
        this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
    }

    private void completePauseLocked(boolean resumeNext, ActivityRecord resuming) {
        ActivityRecord prev = this.mPausingActivity;
        if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
            Slog.v("ActivityTaskManager", "Complete pause: " + prev);
        }
        if (prev != null) {
            prev.setWillCloseOrEnterPip(false);
            boolean wasStopping = prev.isState(ActivityState.STOPPING);
            prev.setState(ActivityState.PAUSED, "completePausedLocked");
            if (prev.finishing) {
                if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityTaskManager", "Executing finish of activity: " + prev);
                }
                prev = finishCurrentActivityLocked(prev, 2, false, "completePausedLocked");
            } else if (prev.hasProcess()) {
                if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityTaskManager", "Enqueue pending stop if needed: " + prev + " wasStopping=" + wasStopping + " visible=" + prev.visible);
                }
                if (prev.deferRelaunchUntilPaused) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                        Slog.v("ActivityTaskManager", "Re-launching after pause: " + prev);
                    }
                    prev.relaunchActivityLocked(false, prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    prev.setState(ActivityState.STOPPING, "completePausedLocked");
                } else if (!prev.visible || shouldSleepOrShutDownActivities()) {
                    prev.setDeferHidingClient(false);
                    addToStopping(prev, true, false, "completePauseLocked");
                }
            } else {
                if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                    Slog.v("ActivityTaskManager", "App died during pause, not stopping: " + prev);
                }
                prev = null;
            }
            if (prev != null) {
                prev.stopFreezingScreenLocked(true);
            }
            this.mPausingActivity = null;
        }
        if (resumeNext) {
            ActivityStack topStack = this.mRootActivityContainer.getTopDisplayFocusedStack();
            if (!topStack.shouldSleepOrShutDownActivities()) {
                this.mRootActivityContainer.resumeFocusedStacksTopActivities(topStack, prev, null);
            } else {
                checkReadyForSleep();
                ActivityRecord top = topStack.topRunningActivityLocked();
                if (top == null || (prev != null && top != prev)) {
                    this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
            }
        }
        if (prev != null) {
            prev.resumeKeyDispatchingLocked();
            if (prev.hasProcess() && prev.cpuTimeAtResume > 0) {
                long diff = prev.app.getCpuTime() - prev.cpuTimeAtResume;
                if (diff > 0) {
                    Runnable r = PooledLambda.obtainRunnable(new QuadConsumer() { // from class: com.android.server.wm.-$$Lambda$1636dquQO0UvkFayOGf_gceB4iw
                        public final void accept(Object obj, Object obj2, Object obj3, Object obj4) {
                            ((ActivityManagerInternal) obj).updateForegroundTimeIfOnBattery((String) obj2, ((Integer) obj3).intValue(), ((Long) obj4).longValue());
                        }
                    }, this.mService.mAmInternal, prev.info.packageName, Integer.valueOf(prev.info.applicationInfo.uid), Long.valueOf(diff));
                    this.mService.mH.post(r);
                }
            }
            prev.cpuTimeAtResume = 0L;
        }
        if (this.mStackSupervisor.mAppVisibilitiesChangedSinceLastPause || (getDisplay() != null && getDisplay().hasPinnedStack())) {
            this.mService.getTaskChangeNotificationController().notifyTaskStackChanged();
            this.mStackSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }
        this.mRootActivityContainer.ensureActivitiesVisible(resuming, 0, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addToStopping(ActivityRecord r, boolean scheduleIdle, boolean idleDelayed, String reason) {
        boolean z = false;
        if (!this.mStackSupervisor.mStoppingActivities.contains(r)) {
            EventLog.writeEvent((int) EventLogTags.AM_ADD_TO_STOPPING, Integer.valueOf(r.mUserId), Integer.valueOf(System.identityHashCode(r)), r.shortComponentName, reason);
            this.mStackSupervisor.mStoppingActivities.add(r);
        }
        if (this.mStackSupervisor.mStoppingActivities.size() > 3 || (r.frontOfTask && this.mTaskHistory.size() <= 1)) {
            z = true;
        }
        boolean forceIdle = z;
        if (scheduleIdle || forceIdle) {
            if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                StringBuilder sb = new StringBuilder();
                sb.append("Scheduling idle now: forceIdle=");
                sb.append(forceIdle);
                sb.append("immediate=");
                sb.append(!idleDelayed);
                Slog.v("ActivityTaskManager", sb.toString());
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
        return display != null && display.isTopStack(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusedStackOnDisplay() {
        ActivityDisplay display = getDisplay();
        return display != null && this == display.getFocusedStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTopActivityVisible() {
        ActivityRecord topActivity = getTopActivity();
        return topActivity != null && topActivity.visible;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldBeVisible(ActivityRecord starting) {
        return getVisibility(starting) != 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @StackVisibility
    public int getVisibility(ActivityRecord starting) {
        ActivityDisplay display;
        ActivityStack activityStack = this;
        if (!isAttached() || activityStack.mForceHidden) {
            return 2;
        }
        ActivityDisplay display2 = getDisplay();
        boolean gotSplitScreenStack = false;
        boolean gotOpaqueSplitScreenPrimary = false;
        boolean gotOpaqueSplitScreenSecondary = false;
        boolean gotTranslucentFullscreen = false;
        boolean gotTranslucentSplitScreenPrimary = false;
        boolean gotTranslucentSplitScreenSecondary = false;
        boolean shouldBeVisible = true;
        int windowingMode = getWindowingMode();
        boolean isAssistantType = isActivityTypeAssistant();
        boolean z = true;
        int i = display2.getChildCount() - 1;
        while (true) {
            if (i < 0) {
                break;
            }
            ActivityStack other = display2.getChildAt(i);
            boolean hasRunningActivities = other.topRunningActivityLocked() != null ? z : false;
            if (other == activityStack) {
                shouldBeVisible = (hasRunningActivities || isInStackLocked(starting) != null || isActivityTypeHome()) ? z : false;
            } else {
                if (!hasRunningActivities) {
                    display = display2;
                } else {
                    int otherWindowingMode = other.getWindowingMode();
                    if (otherWindowingMode == z) {
                        int activityType = other.getActivityType();
                        display = display2;
                        if (windowingMode == 3 && (activityType == 2 || (activityType == 4 && activityStack.mWindowManager.getRecentsAnimationController() != null))) {
                            break;
                        }
                        if (windowingMode == 5 && SharedDisplayManager.enable()) {
                            int targetScreenId = SharedDisplayManager.findScreenId(SharedDisplayContainer.getSharedId(topRunningActivityLocked()));
                            int othersScreenId = SharedDisplayManager.findScreenId(SharedDisplayContainer.getSharedId(other.topRunningActivityLocked()));
                            if (targetScreenId != othersScreenId) {
                                break;
                            }
                        }
                        if (other.isStackTranslucent(starting)) {
                            gotTranslucentFullscreen = true;
                        } else {
                            return 2;
                        }
                    } else {
                        display = display2;
                        if (otherWindowingMode == 3 && !gotOpaqueSplitScreenPrimary) {
                            gotSplitScreenStack = true;
                            gotTranslucentSplitScreenPrimary = other.isStackTranslucent(starting);
                            gotOpaqueSplitScreenPrimary = !gotTranslucentSplitScreenPrimary;
                            if (windowingMode == 3 && gotOpaqueSplitScreenPrimary) {
                                return 2;
                            }
                        } else if (otherWindowingMode == 4 && !gotOpaqueSplitScreenSecondary) {
                            gotSplitScreenStack = true;
                            gotTranslucentSplitScreenSecondary = other.isStackTranslucent(starting);
                            gotOpaqueSplitScreenSecondary = !gotTranslucentSplitScreenSecondary;
                            if (windowingMode == 4 && gotOpaqueSplitScreenSecondary) {
                                return 2;
                            }
                        }
                        if (gotOpaqueSplitScreenPrimary && gotOpaqueSplitScreenSecondary) {
                            return 2;
                        }
                        if (isAssistantType && gotSplitScreenStack) {
                            return 2;
                        }
                    }
                }
                i--;
                z = true;
                activityStack = this;
                display2 = display;
            }
        }
        if (!shouldBeVisible) {
            return 2;
        }
        if (windowingMode != 1) {
            if (windowingMode == 3) {
                if (gotTranslucentSplitScreenPrimary) {
                    return 1;
                }
            } else if (windowingMode == 4 && gotTranslucentSplitScreenSecondary) {
                return 1;
            }
        } else if (gotTranslucentSplitScreenPrimary || gotTranslucentSplitScreenSecondary) {
            return 1;
        }
        return gotTranslucentFullscreen ? 1 : 0;
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
        boolean z;
        boolean z2;
        ArrayList<ActivityRecord> activities;
        ActivityRecord top;
        boolean behindFullscreenActivity;
        TaskRecord task;
        int taskNdx;
        int taskNdx2;
        boolean z3;
        boolean z4 = false;
        this.mTopActivityOccludesKeyguard = false;
        this.mTopDismissingKeyguardActivity = null;
        this.mStackSupervisor.getKeyguardController().beginActivityVisibilityUpdate();
        try {
            ActivityRecord top2 = topRunningActivityLocked();
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityTaskManager", "ensureActivitiesVisible behind " + top2 + " configChanges=0x" + Integer.toHexString(configChanges));
            }
            if (top2 != null) {
                checkTranslucentActivityWaiting(top2);
            }
            boolean z5 = true;
            boolean aboveTop = top2 != null;
            boolean stackShouldBeVisible = shouldBeVisible(starting);
            boolean behindFullscreenActivity2 = !stackShouldBeVisible;
            boolean resumeNextActivity = isFocusable() && isInStackLocked(starting) == null;
            int activityNdx = this.mTaskHistory.size() - 1;
            int configChanges2 = configChanges;
            while (activityNdx >= 0) {
                try {
                    TaskRecord task2 = this.mTaskHistory.get(activityNdx);
                    ArrayList<ActivityRecord> activities2 = task2.mActivities;
                    boolean resumeNextActivity2 = resumeNextActivity;
                    int configChanges3 = configChanges2;
                    int configChanges4 = activities2.size() - 1;
                    while (configChanges4 >= 0) {
                        try {
                            ActivityRecord r = activities2.get(configChanges4);
                            if (!r.finishing) {
                                boolean isTop = r == top2;
                                if (!aboveTop || isTop) {
                                    boolean visibleIgnoringKeyguard = r.shouldBeVisibleIgnoringKeyguard(behindFullscreenActivity2);
                                    boolean reallyVisible = r.shouldBeVisible(behindFullscreenActivity2);
                                    if (visibleIgnoringKeyguard) {
                                        behindFullscreenActivity2 = updateBehindFullscreen(!stackShouldBeVisible, behindFullscreenActivity2, r);
                                    }
                                    boolean behindFullscreenActivity3 = behindFullscreenActivity2;
                                    if (reallyVisible) {
                                        try {
                                            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                                                Slog.v("ActivityTaskManager", "Make visible? " + r + " finishing=" + r.finishing + " state=" + r.getState());
                                            }
                                            if (r == starting || !notifyClients) {
                                                z = false;
                                            } else {
                                                z = false;
                                                r.ensureActivityConfiguration(0, preserveWindows, true);
                                            }
                                            if (r.attachedToProcess()) {
                                                z2 = z;
                                                activities = activities2;
                                                top = top2;
                                                behindFullscreenActivity = behindFullscreenActivity3;
                                                task = task2;
                                                taskNdx = activityNdx;
                                                taskNdx2 = configChanges4;
                                                z3 = true;
                                                if (r.visible) {
                                                    if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                                                        Slog.v("ActivityTaskManager", "Skipping: already visible at " + r);
                                                    }
                                                    if (r.mClientVisibilityDeferred && notifyClients) {
                                                        r.makeClientVisible();
                                                    }
                                                    if (r.handleAlreadyVisible()) {
                                                        resumeNextActivity2 = false;
                                                    }
                                                    if (notifyClients) {
                                                        r.makeActiveIfNeeded(starting);
                                                    }
                                                } else {
                                                    r.makeVisibleIfNeeded(starting, notifyClients);
                                                }
                                            } else {
                                                top = top2;
                                                behindFullscreenActivity = behindFullscreenActivity3;
                                                z2 = z;
                                                taskNdx = activityNdx;
                                                taskNdx2 = configChanges4;
                                                activities = activities2;
                                                task = task2;
                                                if (!makeVisibleAndRestartIfNeeded(starting, configChanges3, isTop, resumeNextActivity2, r)) {
                                                    z3 = true;
                                                } else if (taskNdx2 >= activities.size()) {
                                                    z3 = true;
                                                    taskNdx2 = activities.size() - 1;
                                                } else {
                                                    z3 = true;
                                                    resumeNextActivity2 = false;
                                                }
                                            }
                                            configChanges3 |= r.configChangeFlags;
                                            behindFullscreenActivity2 = behindFullscreenActivity;
                                            aboveTop = false;
                                        } catch (Throwable th) {
                                            th = th;
                                            this.mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
                                            throw th;
                                        }
                                    } else {
                                        activities = activities2;
                                        top = top2;
                                        z2 = false;
                                        task = task2;
                                        taskNdx = activityNdx;
                                        taskNdx2 = configChanges4;
                                        z3 = true;
                                        if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                                            Slog.v("ActivityTaskManager", "Make invisible? " + r + " finishing=" + r.finishing + " state=" + r.getState() + " stackShouldBeVisible=" + stackShouldBeVisible + " behindFullscreenActivity=" + behindFullscreenActivity3 + " mLaunchTaskBehind=" + r.mLaunchTaskBehind);
                                        }
                                        makeInvisible(r);
                                        behindFullscreenActivity2 = behindFullscreenActivity3;
                                        aboveTop = false;
                                    }
                                    int i = taskNdx2 - 1;
                                    activityNdx = taskNdx;
                                    task2 = task;
                                    top2 = top;
                                    activities2 = activities;
                                    z5 = z3;
                                    configChanges4 = i;
                                    z4 = z2;
                                }
                            }
                            activities = activities2;
                            task = task2;
                            top = top2;
                            taskNdx = activityNdx;
                            z2 = false;
                            taskNdx2 = configChanges4;
                            z3 = true;
                            int i2 = taskNdx2 - 1;
                            activityNdx = taskNdx;
                            task2 = task;
                            top2 = top;
                            activities2 = activities;
                            z5 = z3;
                            configChanges4 = i2;
                            z4 = z2;
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    }
                    boolean z6 = z4;
                    ActivityRecord top3 = top2;
                    int taskNdx3 = activityNdx;
                    boolean z7 = z5;
                    TaskRecord task3 = task2;
                    int windowingMode = getWindowingMode();
                    if (windowingMode == 5) {
                        behindFullscreenActivity2 = !stackShouldBeVisible ? z7 : z6;
                    } else if (isActivityTypeHome()) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                            Slog.v("ActivityTaskManager", "Home task: at " + task3 + " stackShouldBeVisible=" + stackShouldBeVisible + " behindFullscreenActivity=" + behindFullscreenActivity2);
                        }
                        behindFullscreenActivity2 = true;
                    }
                    activityNdx = taskNdx3 - 1;
                    z5 = z7;
                    configChanges2 = configChanges3;
                    resumeNextActivity = resumeNextActivity2;
                    top2 = top3;
                    z4 = z6;
                } catch (Throwable th3) {
                    th = th3;
                }
            }
            if (this.mTranslucentActivityWaiting != null && this.mUndrawnActivitiesBelowTopTranslucent.isEmpty()) {
                notifyActivityDrawnLocked(null);
            }
            this.mStackSupervisor.getKeyguardController().endActivityVisibilityUpdate();
        } catch (Throwable th4) {
            th = th4;
        }
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
        int displayId = this.mDisplayId;
        if (displayId == -1) {
            displayId = 0;
        }
        boolean keyguardOrAodShowing = this.mStackSupervisor.getKeyguardController().isKeyguardOrAodShowing(displayId);
        boolean keyguardLocked = this.mStackSupervisor.getKeyguardController().isKeyguardLocked();
        boolean showWhenLocked = r.canShowWhenLocked();
        boolean dismissKeyguard = r.mAppWindowToken != null && r.mAppWindowToken.containsDismissKeyguardWindow();
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowWithInsecureKeyguard() {
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
        boolean z = false;
        if (isTop || !r.visible) {
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityTaskManager", "Start and freeze screen for " + r);
            }
            if (r != starting) {
                r.startFreezingScreenLocked(r.app, configChanges);
            }
            if (!r.visible || r.mLaunchTaskBehind) {
                if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                    Slog.v("ActivityTaskManager", "Starting and making visible: " + r);
                }
                r.setVisible(true);
            }
            if (r != starting) {
                ActivityStackSupervisor activityStackSupervisor = this.mStackSupervisor;
                if (andResume && !r.mLaunchTaskBehind) {
                    z = true;
                }
                activityStackSupervisor.startSpecificActivityLocked(r, z, true);
                return true;
            }
        }
        return false;
    }

    private void makeInvisible(ActivityRecord r) {
        boolean deferHidingClient;
        if (!r.visible) {
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityTaskManager", "Already invisible: " + r);
                return;
            }
            return;
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v("ActivityTaskManager", "Making invisible: " + r + " " + r.getState());
        }
        try {
            boolean canEnterPictureInPicture = r.checkEnterPictureInPictureState("makeInvisible", true);
            if (canEnterPictureInPicture && !r.isState(ActivityState.STOPPING, ActivityState.STOPPED, ActivityState.PAUSED)) {
                deferHidingClient = true;
            } else {
                deferHidingClient = false;
            }
            r.setDeferHidingClient(deferHidingClient);
            r.setVisible(false);
            switch (r.getState()) {
                case STOPPING:
                case STOPPED:
                    if (r.attachedToProcess()) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                            Slog.v("ActivityTaskManager", "Scheduling invisibility: " + r);
                        }
                        this.mService.getLifecycleManager().scheduleTransaction(r.app.getThread(), (IBinder) r.appToken, (ClientTransactionItem) WindowVisibilityItem.obtain(false));
                    }
                    r.supportsEnterPipOnTaskSwitch = false;
                    return;
                case INITIALIZING:
                case RESUMED:
                case PAUSING:
                case PAUSED:
                    addToStopping(r, true, canEnterPictureInPicture, "makeInvisible");
                    return;
                default:
                    return;
            }
        } catch (Exception e) {
            Slog.w("ActivityTaskManager", "Exception thrown making hidden: " + r.intent.getComponent(), e);
        }
    }

    private boolean updateBehindFullscreen(boolean stackInvisible, boolean behindFullscreenActivity, ActivityRecord r) {
        if (r.fullscreen) {
            if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v("ActivityTaskManager", "Fullscreen: at " + r + " stackInvisible=" + stackInvisible + " behindFullscreenActivity=" + behindFullscreenActivity);
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
                if (waitingActivity.attachedToProcess()) {
                    try {
                        waitingActivity.app.getThread().scheduleTranslucentConversionComplete(waitingActivity.appToken, r != null);
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
    @GuardedBy({"mService"})
    public boolean resumeTopActivityUncheckedLocked(ActivityRecord prev, ActivityOptions options) {
        if (this.mInResumeTopActivity) {
            return false;
        }
        try {
            this.mInResumeTopActivity = true;
            boolean result = resumeTopActivityInnerLocked(prev, options);
            ActivityRecord next = topRunningActivityLocked(true);
            if (next == null || !next.canTurnScreenOn()) {
                checkReadyForSleep();
            }
            return result;
        } finally {
            this.mInResumeTopActivity = false;
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
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.d("ActivityTaskManager", "setResumedActivity stack:" + this + " + from: " + this.mResumedActivity + " to:" + r + " reason:" + reason);
        }
        this.mResumedActivity = r;
        this.mStackSupervisor.updateTopResumedActivityIfNeeded();
    }

    @GuardedBy({"mService"})
    private boolean resumeTopActivityInnerLocked(ActivityRecord prev, ActivityOptions options) {
        boolean userLeaving;
        boolean lastResumedCanPip;
        ActivityRecord lastResumed;
        boolean pausing;
        ActivityStack lastFocusedStack;
        ActivityRecord activityRecord;
        boolean anim;
        boolean notUpdated;
        ActivityState lastState;
        ActivityRecord lastResumedActivity;
        ActivityState lastState2;
        boolean z;
        boolean z2;
        ActivityRecord activityRecord2;
        boolean z3;
        ActivityRecord activityRecord3;
        if (!this.mService.isBooting() && !this.mService.isBooted()) {
            return false;
        }
        ActivityRecord next = topRunningActivityLocked(true);
        boolean hasRunningActivity = next != null;
        if (hasRunningActivity && !isAttached()) {
            return false;
        }
        if (next == null && prev == null) {
            try {
                if (isOnHomeDisplay()) {
                    return this.mRootActivityContainer.resumeHomeActivity(prev, "noNextActivities", this.mDisplayId);
                }
                return false;
            } catch (Exception e) {
                xpLogger.i("ActivityTaskManager", "resumeTopActivityInnerLocked resume home e=" + e);
            }
        }
        this.mRootActivityContainer.cancelInitializingActivities();
        boolean userLeaving2 = this.mStackSupervisor.mUserLeaving;
        this.mStackSupervisor.mUserLeaving = false;
        if (!hasRunningActivity) {
            return resumeNextFocusableActivityWhenStackIsEmpty(prev, options);
        }
        next.delayedResume = false;
        ActivityDisplay display = getDisplay();
        if (this.mResumedActivity == next && next.isState(ActivityState.RESUMED) && display.allResumedActivitiesComplete()) {
            executeAppTransition(options);
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.d("ActivityTaskManager", "resumeTopActivityLocked: Top activity resumed " + next);
            }
            return false;
        } else if (!next.canResumeByCompat()) {
            return false;
        } else {
            if (shouldSleepOrShutDownActivities() && this.mLastPausedActivity == next && this.mRootActivityContainer.allPausedActivitiesComplete()) {
                boolean nothingToResume = true;
                if (!this.mService.mShuttingDown) {
                    boolean canShowWhenLocked = !this.mTopActivityOccludesKeyguard && next.canShowWhenLocked();
                    boolean mayDismissKeyguard = (this.mTopDismissingKeyguardActivity == next || next.mAppWindowToken == null || !next.mAppWindowToken.containsDismissKeyguardWindow()) ? false : true;
                    if (canShowWhenLocked || mayDismissKeyguard) {
                        ensureActivitiesVisibleLocked(null, 0, false);
                        nothingToResume = shouldSleepActivities();
                    }
                }
                if (nothingToResume) {
                    executeAppTransition(options);
                    if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityTaskManager", "resumeTopActivityLocked: Going to sleep and all paused");
                    }
                    return false;
                }
            }
            if (!this.mService.mAmInternal.hasStartedUserState(next.mUserId)) {
                Slog.w("ActivityTaskManager", "Skipping resume of top activity " + next + ": user " + next.mUserId + " is stopped");
                return false;
            }
            this.mStackSupervisor.mStoppingActivities.remove(next);
            this.mStackSupervisor.mGoingToSleepActivities.remove(next);
            next.sleeping = false;
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                Slog.v("ActivityTaskManager", "Resuming " + next);
            }
            if (!this.mRootActivityContainer.allPausedActivitiesComplete()) {
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_PAUSE || ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "resumeTopActivityLocked: Skip resume: some activity pausing.");
                }
                return false;
            }
            this.mStackSupervisor.setLaunchSource(next.info.applicationInfo.uid);
            ActivityStack lastFocusedStack2 = display.getLastFocusedStack();
            if (lastFocusedStack2 != null && lastFocusedStack2 != this) {
                ActivityRecord lastResumed2 = lastFocusedStack2.mResumedActivity;
                if (userLeaving2 && inMultiWindowMode() && lastFocusedStack2.shouldBeVisible(next)) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING) {
                        Slog.i("ActivityTaskManager", "Overriding userLeaving to false next=" + next + " lastResumed=" + lastResumed2);
                    }
                    userLeaving2 = false;
                }
                boolean lastResumedCanPip2 = lastResumed2 != null && lastResumed2.checkEnterPictureInPictureState("resumeTopActivity", userLeaving2);
                userLeaving = userLeaving2;
                lastResumedCanPip = lastResumedCanPip2;
                lastResumed = lastResumed2;
            } else {
                userLeaving = userLeaving2;
                lastResumedCanPip = false;
                lastResumed = null;
            }
            boolean resumeWhilePausing = ((next.info.flags & 16384) == 0 || lastResumedCanPip) ? false : true;
            boolean pausing2 = getDisplay().pauseBackStacks(userLeaving, next, false);
            if (this.mResumedActivity == null) {
                pausing = pausing2;
            } else {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.d("ActivityTaskManager", "resumeTopActivityLocked: Pausing " + this.mResumedActivity);
                }
                pausing = pausing2 | startPausingLocked(userLeaving, false, next, false);
            }
            if (pausing && !resumeWhilePausing) {
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "resumeTopActivityLocked: Skip resume: need to start pausing");
                }
                if (next.attachedToProcess()) {
                    next.app.updateProcessInfo(false, true, false);
                }
                if (lastResumed != null) {
                    lastResumed.setWillCloseOrEnterPip(true);
                }
                return true;
            } else if (this.mResumedActivity == next && next.isState(ActivityState.RESUMED) && display.allResumedActivitiesComplete()) {
                executeAppTransition(options);
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.d("ActivityTaskManager", "resumeTopActivityLocked: Top activity resumed (dontWaitForPause) " + next);
                }
                return true;
            } else {
                if (!shouldSleepActivities() || (activityRecord3 = this.mLastNoHistoryActivity) == null || activityRecord3.finishing) {
                    lastFocusedStack = lastFocusedStack2;
                    activityRecord = null;
                } else {
                    if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityTaskManager", "no-history finish of " + this.mLastNoHistoryActivity + " on new resume");
                    }
                    lastFocusedStack = lastFocusedStack2;
                    activityRecord = null;
                    requestFinishActivityLocked(this.mLastNoHistoryActivity.appToken, 0, null, "resume-no-history", false);
                    this.mLastNoHistoryActivity = null;
                }
                if (prev != null && prev != next && next.nowVisible) {
                    if (prev.finishing) {
                        prev.setVisibility(false);
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                            Slog.v("ActivityTaskManager", "Not waiting for visible to hide: " + prev + ", nowVisible=" + next.nowVisible);
                        }
                    } else if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                        Slog.v("ActivityTaskManager", "Previous already visible but still waiting to hide: " + prev + ", nowVisible=" + next.nowVisible);
                    }
                }
                try {
                    AppGlobals.getPackageManager().setPackageStoppedState(next.packageName, false, next.mUserId);
                } catch (RemoteException e2) {
                } catch (IllegalArgumentException e3) {
                    Slog.w("ActivityTaskManager", "Failed trying to unstop package " + next.packageName + ": " + e3);
                }
                boolean anim2 = true;
                DisplayContent dc = getDisplay().mDisplayContent;
                int i = 6;
                if (prev != null) {
                    if (prev.finishing) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                            Slog.v("ActivityTaskManager", "Prepare close transition: prev=" + prev);
                        }
                        if (this.mStackSupervisor.mNoAnimActivities.contains(prev)) {
                            anim2 = false;
                            dc.prepareAppTransition(0, false);
                            z3 = false;
                        } else {
                            z3 = false;
                            dc.prepareAppTransition(prev.getTaskRecord() == next.getTaskRecord() ? 7 : 9, false);
                        }
                        prev.setVisibility(z3);
                        anim = anim2;
                    } else {
                        if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                            Slog.v("ActivityTaskManager", "Prepare open transition: prev=" + prev);
                        }
                        if (this.mStackSupervisor.mNoAnimActivities.contains(next)) {
                            dc.prepareAppTransition(0, false);
                            anim = false;
                        } else {
                            if (prev.getTaskRecord() != next.getTaskRecord()) {
                                i = next.mLaunchTaskBehind ? 16 : 8;
                            }
                            dc.prepareAppTransition(i, false);
                            anim = true;
                        }
                    }
                } else {
                    if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                        Slog.v("ActivityTaskManager", "Prepare open transition: no previous");
                    }
                    if (!this.mStackSupervisor.mNoAnimActivities.contains(next)) {
                        dc.prepareAppTransition(6, false);
                        anim = true;
                    } else {
                        dc.prepareAppTransition(0, false);
                        anim = false;
                    }
                }
                if (anim) {
                    next.applyOptionsLocked();
                } else {
                    next.clearOptionsLocked();
                }
                this.mStackSupervisor.mNoAnimActivities.clear();
                if (next.attachedToProcess()) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                        Slog.v("ActivityTaskManager", "Resume running: " + next + " stopped=" + next.stopped + " visible=" + next.visible);
                    }
                    boolean lastActivityTranslucent = lastFocusedStack != null && (lastFocusedStack.inMultiWindowMode() || !((activityRecord2 = lastFocusedStack.mLastPausedActivity) == null || activityRecord2.fullscreen));
                    if (!next.visible || next.stopped || lastActivityTranslucent) {
                        next.setVisibility(true);
                    }
                    next.startLaunchTickingLocked();
                    ActivityRecord lastResumedActivity2 = lastFocusedStack == null ? activityRecord : lastFocusedStack.mResumedActivity;
                    ActivityState lastState3 = next.getState();
                    this.mService.updateCpuStats();
                    if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                        Slog.v("ActivityTaskManager", "Moving to RESUMED: " + next + " (in existing)");
                    }
                    next.setState(ActivityState.RESUMED, "resumeTopActivityInnerLocked");
                    next.app.updateProcessInfo(false, true, true);
                    updateLRUListLocked(next);
                    if (shouldBeVisible(next)) {
                        notUpdated = !this.mRootActivityContainer.ensureVisibilityAndConfig(next, this.mDisplayId, true, false);
                    } else {
                        notUpdated = true;
                    }
                    if (notUpdated) {
                        ActivityRecord nextNext = topRunningActivityLocked();
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                            Slog.i("ActivityTaskManager", "Activity config changed during resume: " + next + ", new next: " + nextNext);
                        }
                        if (nextNext != next) {
                            this.mStackSupervisor.scheduleResumeTopActivities();
                        }
                        if (!next.visible || next.stopped) {
                            z2 = true;
                            next.setVisibility(true);
                        } else {
                            z2 = true;
                        }
                        next.completeResumeLocked();
                        return z2;
                    }
                    try {
                        ClientTransaction transaction = ClientTransaction.obtain(next.app.getThread(), next.appToken);
                        ArrayList<ResultInfo> a = next.results;
                        if (a != null) {
                            try {
                                int N = a.size();
                                if (!next.finishing && N > 0) {
                                    if (ActivityTaskManagerDebugConfig.DEBUG_RESULTS) {
                                        Slog.v("ActivityTaskManager", "Delivering results to " + next + ": " + a);
                                    }
                                    transaction.addCallback(ActivityResultItem.obtain(a));
                                }
                            } catch (Exception e4) {
                                lastState = lastState3;
                                lastResumedActivity = lastResumedActivity2;
                                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("Resume failed; resetting state to ");
                                    lastState2 = lastState;
                                    sb.append(lastState2);
                                    sb.append(": ");
                                    sb.append(next);
                                    Slog.v("ActivityTaskManager", sb.toString());
                                } else {
                                    lastState2 = lastState;
                                }
                                next.setState(lastState2, "resumeTopActivityInnerLocked");
                                if (lastResumedActivity != null) {
                                    lastResumedActivity.setState(ActivityState.RESUMED, "resumeTopActivityInnerLocked");
                                }
                                Slog.i("ActivityTaskManager", "Restarting because process died: " + next);
                                if (!next.hasBeenLaunched) {
                                    next.hasBeenLaunched = true;
                                    z = false;
                                } else if (lastFocusedStack == null) {
                                    z = false;
                                } else if (!lastFocusedStack.isTopStackOnDisplay()) {
                                    z = false;
                                } else {
                                    z = false;
                                    next.showStartingWindow(null, false, false);
                                }
                                this.mStackSupervisor.startSpecificActivityLocked(next, true, z);
                                return true;
                            }
                        }
                        if (next.newIntents != null) {
                            transaction.addCallback(NewIntentItem.obtain(next.newIntents, true));
                        }
                        next.notifyAppResumed(next.stopped);
                        EventLog.writeEvent((int) EventLogTags.AM_RESUME_ACTIVITY, Integer.valueOf(next.mUserId), Integer.valueOf(System.identityHashCode(next)), Integer.valueOf(next.getTaskRecord().taskId), next.shortComponentName);
                        next.sleeping = false;
                        this.mService.getAppWarningsLocked().onResumeActivity(next);
                        next.app.setPendingUiCleanAndForceProcessStateUpTo(this.mService.mTopProcessState);
                        next.clearOptionsLocked();
                        transaction.setLifecycleStateRequest(ResumeActivityItem.obtain(next.app.getReportedProcState(), getDisplay().mDisplayContent.isNextTransitionForward()));
                        this.mService.getLifecycleManager().scheduleTransaction(transaction);
                        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                            Slog.d("ActivityTaskManager", "resumeTopActivityLocked: Resumed " + next);
                        }
                        try {
                            next.completeResumeLocked();
                            return true;
                        } catch (Exception e5) {
                            Slog.w("ActivityTaskManager", "Exception thrown during resume of " + next, e5);
                            requestFinishActivityLocked(next.appToken, 0, null, "resume-exception", true);
                            return true;
                        }
                    } catch (Exception e6) {
                        lastState = lastState3;
                        lastResumedActivity = lastResumedActivity2;
                    }
                } else {
                    if (!next.hasBeenLaunched) {
                        next.hasBeenLaunched = true;
                    } else {
                        next.showStartingWindow(null, false, false);
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                            Slog.v("ActivityTaskManager", "Restarting: " + next);
                        }
                    }
                    if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityTaskManager", "resumeTopActivityLocked: Restarting " + next);
                    }
                    this.mStackSupervisor.startSpecificActivityLocked(next, true, true);
                    return true;
                }
            }
        }
    }

    private boolean resumeNextFocusableActivityWhenStackIsEmpty(ActivityRecord prev, ActivityOptions options) {
        ActivityStack nextFocusedStack;
        if (!isActivityTypeHome() && (nextFocusedStack = adjustFocusToNextFocusableStack("noMoreActivities")) != null) {
            return this.mRootActivityContainer.resumeFocusedStacksTopActivities(nextFocusedStack, prev, null);
        }
        ActivityOptions.abort(options);
        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            Slog.d("ActivityTaskManager", "resumeNextFocusableActivityWhenStackIsEmpty: noMoreActivities, go home");
        }
        return this.mRootActivityContainer.resumeHomeActivity(prev, "noMoreActivities", this.mDisplayId);
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
            TaskStack taskStack = this.mTaskStack;
            if (taskStack != null) {
                taskStack.positionChildAt(task.getTask(), position2);
            }
            updateTaskMovement(task, true);
        }
    }

    private void insertTaskAtTop(TaskRecord task, ActivityRecord starting) {
        this.mTaskHistory.remove(task);
        int position = getAdjustedPositionForTask(task, this.mTaskHistory.size(), starting);
        this.mTaskHistory.add(position, task);
        updateTaskMovement(task, true);
        positionChildWindowContainerAtTop(task);
    }

    private void insertTaskAtBottom(TaskRecord task) {
        this.mTaskHistory.remove(task);
        int position = getAdjustedPositionForTask(task, 0, null);
        this.mTaskHistory.add(position, task);
        updateTaskMovement(task, true);
        positionChildWindowContainerAtBottom(task);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startActivityLocked(ActivityRecord r, ActivityRecord focusedTopActivity, boolean newTask, boolean keepCurTransition, ActivityOptions options) {
        TaskRecord rTask = r.getTaskRecord();
        int taskId = rTask.taskId;
        boolean allowMoveToFront = options == null || !options.getAvoidMoveToFront();
        if (!r.mLaunchTaskBehind && allowMoveToFront && (taskForIdLocked(taskId) == null || newTask)) {
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
                            if (ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE) {
                                Slog.i("ActivityTaskManager", "Adding activity " + r + " to task " + task, new RuntimeException("here").fillInStackTrace());
                            }
                            r.createAppWindowToken();
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
        TaskRecord activityTask = r.getTaskRecord();
        if (task == activityTask && this.mTaskHistory.indexOf(task) != this.mTaskHistory.size() - 1) {
            this.mStackSupervisor.mUserLeaving = false;
            if (ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING) {
                Slog.v("ActivityTaskManager", "startActivity() behind front, mUserLeaving=false");
            }
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.i("ActivityTaskManager", "Adding activity " + r + " to stack to task " + activityTask, new RuntimeException("here").fillInStackTrace());
        }
        if (r.mAppWindowToken == null) {
            r.createAppWindowToken();
        }
        activityTask.setFrontOfTask();
        if ((!isHomeOrRecentsStack() || numActivities() > 0) && allowMoveToFront) {
            DisplayContent dc = getDisplay().mDisplayContent;
            if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                Slog.v("ActivityTaskManager", "Prepare open transition: starting " + r);
            }
            if ((r.intent.getFlags() & 65536) != 0) {
                dc.prepareAppTransition(0, keepCurTransition);
                this.mStackSupervisor.mNoAnimActivities.add(r);
            } else {
                int transit = 6;
                if (newTask) {
                    if (r.mLaunchTaskBehind) {
                        transit = 16;
                    } else if (getDisplay().isSingleTaskInstance()) {
                        transit = 28;
                    } else {
                        if (canEnterPipOnTaskSwitch(focusedTopActivity, null, r, options)) {
                            focusedTopActivity.supportsEnterPipOnTaskSwitch = true;
                        }
                        transit = 8;
                    }
                }
                dc.prepareAppTransition(transit, keepCurTransition);
                this.mStackSupervisor.mNoAnimActivities.remove(r);
            }
            boolean doShow = true;
            if (newTask) {
                if ((r.intent.getFlags() & DumpState.DUMP_COMPILER_STATS) != 0) {
                    resetTaskIfNeededLocked(r, r);
                    doShow = topRunningNonDelayedActivityLocked(null) == r;
                }
            } else if (options != null && options.getAnimationType() == 5) {
                doShow = false;
            }
            if (r.mLaunchTaskBehind) {
                r.setVisibility(true);
                ensureActivitiesVisibleLocked(null, 0, false);
                return;
            } else if (doShow) {
                TaskRecord prevTask = r.getTaskRecord();
                ActivityRecord prev = prevTask.topRunningActivityWithStartingWindowLocked();
                if (prev != null) {
                    if (prev.getTaskRecord() != prevTask) {
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
        ActivityStack targetStack = toFrontTask != null ? toFrontTask.getStack() : toFrontActivity.getActivityStack();
        if (targetStack != null && targetStack.isActivityTypeAssistant()) {
            return false;
        }
        return true;
    }

    private boolean isTaskSwitch(ActivityRecord r, ActivityRecord topFocusedActivity) {
        return (topFocusedActivity == null || r.getTaskRecord() == topFocusedActivity.getTaskRecord()) ? false : true;
    }

    private ActivityOptions resetTargetTaskIfNeededLocked(TaskRecord task, boolean forceReset) {
        int numActivities;
        boolean z;
        String str;
        int end;
        boolean noOptions;
        ActivityOptions topOptions;
        boolean z2;
        ActivityRecord target;
        TaskRecord targetTask;
        String str2;
        boolean canMoveOptions;
        boolean noOptions2;
        ArrayList<ActivityRecord> activities = task.mActivities;
        int numActivities2 = activities.size();
        int rootActivityNdx = task.findEffectiveRootIndex();
        ActivityOptions topOptions2 = null;
        int replyChainEnd = -1;
        boolean canMoveOptions2 = true;
        int i = numActivities2 - 1;
        while (i > rootActivityNdx) {
            ActivityRecord target2 = activities.get(i);
            if (target2.frontOfTask) {
                break;
            }
            int flags = target2.info.flags;
            boolean finishOnTaskLaunch = (flags & 2) != 0;
            boolean allowTaskReparenting = (flags & 64) != 0;
            boolean clearWhenTaskReset = (target2.intent.getFlags() & DumpState.DUMP_FROZEN) != 0;
            if (!finishOnTaskLaunch && !clearWhenTaskReset && target2.resultTo != null) {
                if (replyChainEnd >= 0) {
                    numActivities = numActivities2;
                } else {
                    replyChainEnd = i;
                    numActivities = numActivities2;
                }
            } else {
                if (finishOnTaskLaunch || clearWhenTaskReset || !allowTaskReparenting || target2.taskAffinity == null) {
                    numActivities = numActivities2;
                    z = false;
                    str = "ActivityTaskManager";
                } else if (target2.taskAffinity.equals(task.affinity)) {
                    numActivities = numActivities2;
                    z = false;
                    str = "ActivityTaskManager";
                } else {
                    ActivityRecord bottom = (this.mTaskHistory.isEmpty() || this.mTaskHistory.get(0).mActivities.isEmpty()) ? null : this.mTaskHistory.get(0).mActivities.get(0);
                    if (bottom != null && target2.taskAffinity != null && target2.taskAffinity.equals(bottom.getTaskRecord().affinity)) {
                        targetTask = bottom.getTaskRecord();
                        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            Slog.v("ActivityTaskManager", "Start pushing activity " + target2 + " out to bottom task " + targetTask);
                        }
                        numActivities = numActivities2;
                        target = target2;
                        str2 = "ActivityTaskManager";
                    } else {
                        numActivities = numActivities2;
                        target = target2;
                        targetTask = createTaskRecord(this.mStackSupervisor.getNextTaskIdForUserLocked(target2.mUserId), target2.info, null, null, null, false);
                        targetTask.affinityIntent = target.intent;
                        if (!ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            str2 = "ActivityTaskManager";
                        } else {
                            str2 = "ActivityTaskManager";
                            Slog.v(str2, "Start pushing activity " + target + " out to new task " + targetTask);
                        }
                    }
                    boolean noOptions3 = canMoveOptions2;
                    int start = replyChainEnd < 0 ? i : replyChainEnd;
                    int srcPos = start;
                    while (srcPos >= i) {
                        ActivityRecord p = activities.get(srcPos);
                        if (p.finishing) {
                            canMoveOptions = canMoveOptions2;
                        } else {
                            canMoveOptions = false;
                            if (noOptions3 && topOptions2 == null) {
                                topOptions2 = p.takeOptionsLocked(false);
                                if (topOptions2 != null) {
                                    noOptions3 = false;
                                }
                            }
                            if (ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE) {
                                StringBuilder sb = new StringBuilder();
                                noOptions2 = noOptions3;
                                sb.append("Removing activity ");
                                sb.append(p);
                                sb.append(" from task=");
                                sb.append(task);
                                sb.append(" adding to task=");
                                sb.append(targetTask);
                                sb.append(" Callers=");
                                sb.append(Debug.getCallers(4));
                                Slog.i(str2, sb.toString());
                            } else {
                                noOptions2 = noOptions3;
                            }
                            boolean noOptions4 = ActivityTaskManagerDebugConfig.DEBUG_TASKS;
                            if (noOptions4) {
                                Slog.v(str2, "Pushing next activity " + p + " out to target's task " + target);
                            }
                            p.reparent(targetTask, 0, "resetTargetTaskIfNeeded");
                            noOptions3 = noOptions2;
                        }
                        srcPos--;
                        canMoveOptions2 = canMoveOptions;
                    }
                    positionChildWindowContainerAtBottom(targetTask);
                    replyChainEnd = -1;
                }
                if (forceReset || finishOnTaskLaunch || clearWhenTaskReset) {
                    if (clearWhenTaskReset) {
                        end = activities.size() - 1;
                    } else if (replyChainEnd < 0) {
                        end = i;
                    } else {
                        end = replyChainEnd;
                    }
                    boolean noOptions5 = canMoveOptions2;
                    boolean z3 = canMoveOptions2;
                    int end2 = end;
                    boolean end3 = z3;
                    ActivityOptions activityOptions = topOptions2;
                    int srcPos2 = i;
                    ActivityOptions topOptions3 = activityOptions;
                    while (srcPos2 <= end2) {
                        ActivityRecord p2 = activities.get(srcPos2);
                        if (p2.finishing) {
                            z2 = z;
                        } else {
                            if (noOptions5 && topOptions3 == null) {
                                ActivityOptions topOptions4 = p2.takeOptionsLocked(z);
                                if (topOptions4 == null) {
                                    noOptions = noOptions5;
                                    topOptions = topOptions4;
                                } else {
                                    noOptions = false;
                                    topOptions = topOptions4;
                                }
                            } else {
                                noOptions = noOptions5;
                                topOptions = topOptions3;
                            }
                            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                                Slog.w(str, "resetTaskIntendedTask: calling finishActivity on " + p2);
                            }
                            z2 = z;
                            if (!finishActivityLocked(p2, 0, null, "reset-task", false)) {
                                end3 = false;
                                topOptions3 = topOptions;
                                noOptions5 = noOptions;
                            } else {
                                end2--;
                                srcPos2--;
                                end3 = false;
                                topOptions3 = topOptions;
                                noOptions5 = noOptions;
                            }
                        }
                        srcPos2++;
                        z = z2;
                    }
                    canMoveOptions2 = end3;
                    replyChainEnd = -1;
                    topOptions2 = topOptions3;
                } else {
                    replyChainEnd = -1;
                }
            }
            i--;
            numActivities2 = numActivities;
        }
        return topOptions2;
    }

    private int resetAffinityTaskIfNeededLocked(TaskRecord affinityTask, TaskRecord task, boolean topTaskIsHigher, boolean forceReset, int taskInsertionPoint) {
        int taskId;
        String taskAffinity;
        ArrayList<ActivityRecord> taskActivities;
        int targetNdx;
        int taskInsertionPoint2;
        TaskRecord taskRecord = affinityTask;
        int taskId2 = task.taskId;
        String taskAffinity2 = task.affinity;
        ArrayList<ActivityRecord> activities = taskRecord.mActivities;
        int numActivities = activities.size();
        int rootActivityNdx = affinityTask.findEffectiveRootIndex();
        int i = numActivities - 1;
        int replyChainEnd = -1;
        int replyChainEnd2 = taskInsertionPoint;
        while (i > rootActivityNdx) {
            ActivityRecord target = activities.get(i);
            if (target.frontOfTask) {
                break;
            }
            int flags = target.info.flags;
            boolean finishOnTaskLaunch = (flags & 2) != 0;
            boolean allowTaskReparenting = (flags & 64) != 0;
            if (target.resultTo != null) {
                if (replyChainEnd >= 0) {
                    taskId = taskId2;
                    taskAffinity = taskAffinity2;
                } else {
                    replyChainEnd = i;
                    taskId = taskId2;
                    taskAffinity = taskAffinity2;
                }
            } else if (!topTaskIsHigher || !allowTaskReparenting || taskAffinity2 == null) {
                taskId = taskId2;
                taskAffinity = taskAffinity2;
            } else if (!taskAffinity2.equals(target.taskAffinity)) {
                taskId = taskId2;
                taskAffinity = taskAffinity2;
            } else {
                if (forceReset) {
                    taskId = taskId2;
                    taskAffinity = taskAffinity2;
                } else if (finishOnTaskLaunch) {
                    taskId = taskId2;
                    taskAffinity = taskAffinity2;
                } else {
                    if (replyChainEnd2 < 0) {
                        replyChainEnd2 = task.mActivities.size();
                    }
                    int start = replyChainEnd >= 0 ? replyChainEnd : i;
                    if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                        taskId = taskId2;
                        StringBuilder sb = new StringBuilder();
                        taskAffinity = taskAffinity2;
                        sb.append("Reparenting from task=");
                        sb.append(taskRecord);
                        sb.append(":");
                        sb.append(start);
                        sb.append("-");
                        sb.append(i);
                        sb.append(" to task=");
                        sb.append(task);
                        sb.append(":");
                        sb.append(replyChainEnd2);
                        Slog.v("ActivityTaskManager", sb.toString());
                    } else {
                        taskId = taskId2;
                        taskAffinity = taskAffinity2;
                    }
                    int srcPos = start;
                    while (srcPos >= i) {
                        ActivityRecord p = activities.get(srcPos);
                        p.reparent(task, replyChainEnd2, "resetAffinityTaskIfNeededLocked");
                        if (ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE) {
                            StringBuilder sb2 = new StringBuilder();
                            taskInsertionPoint2 = replyChainEnd2;
                            sb2.append("Removing and adding activity ");
                            sb2.append(p);
                            sb2.append(" to stack at ");
                            sb2.append(task);
                            sb2.append(" callers=");
                            sb2.append(Debug.getCallers(3));
                            Slog.i("ActivityTaskManager", sb2.toString());
                        } else {
                            taskInsertionPoint2 = replyChainEnd2;
                        }
                        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            Slog.v("ActivityTaskManager", "Pulling activity " + p + " from " + srcPos + " in to resetting task " + task);
                        }
                        srcPos--;
                        replyChainEnd2 = taskInsertionPoint2;
                    }
                    int taskInsertionPoint3 = replyChainEnd2;
                    positionChildWindowContainerAtTop(task);
                    if (target.info.launchMode == 1 && (targetNdx = (taskActivities = task.mActivities).indexOf(target)) > 0) {
                        ActivityRecord p2 = taskActivities.get(targetNdx - 1);
                        if (p2.intent.getComponent().equals(target.intent.getComponent())) {
                            finishActivityLocked(p2, 0, null, "replace", false);
                        }
                    }
                    replyChainEnd2 = taskInsertionPoint3;
                    replyChainEnd = -1;
                }
                int start2 = replyChainEnd >= 0 ? replyChainEnd : i;
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.v("ActivityTaskManager", "Finishing task at index " + start2 + " to " + i);
                }
                for (int srcPos2 = start2; srcPos2 >= i; srcPos2--) {
                    ActivityRecord p3 = activities.get(srcPos2);
                    if (!p3.finishing) {
                        finishActivityLocked(p3, 0, null, "move-affinity", false);
                    }
                }
                replyChainEnd = -1;
            }
            i--;
            taskRecord = affinityTask;
            taskId2 = taskId;
            taskAffinity2 = taskAffinity;
        }
        return replyChainEnd2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityRecord resetTaskIfNeededLocked(ActivityRecord taskTop, ActivityRecord newActivity) {
        boolean forceReset = (newActivity.info.flags & 4) != 0;
        TaskRecord task = taskTop.getTaskRecord();
        boolean taskFound = false;
        ActivityOptions topOptions = null;
        int reparentInsertionPoint = -1;
        for (int i = this.mTaskHistory.size() - 1; i >= 0; i--) {
            TaskRecord targetTask = this.mTaskHistory.get(i);
            if (targetTask == task) {
                ActivityOptions topOptions2 = resetTargetTaskIfNeededLocked(task, forceReset);
                taskFound = true;
                topOptions = topOptions2;
            } else {
                reparentInsertionPoint = resetAffinityTaskIfNeededLocked(targetTask, task, taskFound, forceReset, reparentInsertionPoint);
            }
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
            this.mService.mUgmInternal.grantUriPermissionFromIntent(callingUid, r.packageName, data, r.getUriPermissionsLocked(), r.mUserId);
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_RESULTS) {
            Slog.v("ActivityTaskManager", "Send activity result to " + r + " : who=" + resultWho + " req=" + requestCode + " res=" + resultCode + " data=" + data);
        }
        if (this.mResumedActivity == r && r.attachedToProcess()) {
            try {
                ArrayList<ResultInfo> list = new ArrayList<>();
                list.add(new ResultInfo(resultWho, requestCode, resultCode, data));
                this.mService.getLifecycleManager().scheduleTransaction(r.app.getThread(), (IBinder) r.appToken, (ClientTransactionItem) ActivityResultItem.obtain(list));
                return;
            } catch (Exception e) {
                Slog.w("ActivityTaskManager", "Exception thrown sending result to " + r, e);
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
        if (this.mRootActivityContainer.isTopDisplayFocusedStack(this)) {
            ActivityRecord activityRecord = this.mResumedActivity;
            if (activityRecord != r && activityRecord != null) {
                return;
            }
            ActivityRecord next = topRunningActivityLocked();
            String myReason = reason + " adjustFocus";
            if (next == r) {
                ActivityRecord top = this.mRootActivityContainer.topRunningActivity();
                if (top != null) {
                    top.moveFocusableActivityToTop(myReason);
                }
            } else if (next != null && isFocusable()) {
            } else {
                TaskRecord task = r.getTaskRecord();
                if (task == null) {
                    throw new IllegalStateException("activity no longer associated with task:" + r);
                }
                ActivityStack nextFocusableStack = adjustFocusToNextFocusableStack(myReason);
                if (nextFocusableStack != null) {
                    ActivityRecord top2 = nextFocusableStack.topRunningActivityLocked();
                    if (top2 != null && top2 == this.mRootActivityContainer.getTopResumedActivity()) {
                        this.mService.setResumedActivityUncheckLocked(top2, reason);
                        return;
                    }
                    return;
                }
                getDisplay().moveHomeActivityToTop(myReason);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack adjustFocusToNextFocusableStack(String reason) {
        return adjustFocusToNextFocusableStack(reason, false);
    }

    private ActivityStack adjustFocusToNextFocusableStack(String reason, boolean allowFocusSelf) {
        ActivityStack stack = this.mRootActivityContainer.getNextFocusableStack(this, !allowFocusSelf);
        String myReason = reason + " adjustFocusToNextFocusableStack";
        if (stack == null) {
            return null;
        }
        ActivityRecord top = stack.topRunningActivityLocked();
        if (stack.isActivityTypeHome() && (top == null || !top.visible)) {
            stack.getDisplay().moveHomeActivityToTop(reason);
            return stack;
        }
        stack.moveToFront(myReason);
        return stack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void stopActivityLocked(ActivityRecord r) {
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityTaskManager", "Stopping: " + r);
        }
        if (((r.intent.getFlags() & 1073741824) != 0 || (r.info.flags & 128) != 0) && !r.finishing) {
            if (!shouldSleepActivities()) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.d("ActivityTaskManager", "no-history finish of " + r);
                }
                if (requestFinishActivityLocked(r.appToken, 0, null, "stop-no-history", false)) {
                    r.resumeKeyDispatchingLocked();
                    return;
                }
            } else if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.d("ActivityTaskManager", "Not finishing noHistory " + r + " on stop because we're just sleeping");
            }
        }
        if (r.attachedToProcess()) {
            adjustFocusedActivityStack(r, "stopActivity");
            r.resumeKeyDispatchingLocked();
            try {
                r.stopped = false;
                if (r.isActivityTypeHome() && xpWindowManager.isDesktopHome()) {
                    r.visible = true;
                }
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "Moving to STOPPING: " + r + " (stop requested)");
                }
                r.setState(ActivityState.STOPPING, "stopActivityLocked");
                if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
                    Slog.v("ActivityTaskManager", "Stopping visible=" + r.visible + " for " + r);
                }
                if (!r.visible) {
                    r.setVisible(false);
                }
                EventLogTags.writeAmStopActivity(r.mUserId, System.identityHashCode(r), r.shortComponentName);
                this.mService.getLifecycleManager().scheduleTransaction(r.app.getThread(), (IBinder) r.appToken, (ActivityLifecycleItem) StopActivityItem.obtain(r.visible, r.configChangeFlags));
                if (shouldSleepOrShutDownActivities()) {
                    r.setSleeping(true);
                }
                Message msg = this.mHandler.obtainMessage(104, r);
                this.mHandler.sendMessageDelayed(msg, 11000L);
            } catch (Exception e) {
                Slog.w("ActivityTaskManager", "Exception thrown during pause", e);
                r.stopped = true;
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "Stop failed; moving to STOPPED: " + r);
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
        if (ActivityTaskManagerDebugConfig.DEBUG_RESULTS || ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityTaskManager", "Finishing activity token=" + token + " r=, result=" + resultCode + ", data=" + resultData + ", reason=" + reason);
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
        this.mService.updateOomAdj();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final TaskRecord finishTopCrashedActivityLocked(WindowProcessController app, String reason) {
        ActivityRecord r = topRunningActivityLocked();
        if (r == null || r.app != app) {
            return null;
        }
        Slog.w("ActivityTaskManager", "  Force finishing activity " + r.intent.getComponent().flattenToShortString());
        TaskRecord finishedTask = r.getTaskRecord();
        int taskNdx = this.mTaskHistory.indexOf(finishedTask);
        int activityNdx = finishedTask.mActivities.indexOf(r);
        getDisplay().mDisplayContent.prepareAppTransition(26, false);
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
                Slog.w("ActivityTaskManager", "  Force finishing activity " + r2.intent.getComponent().flattenToShortString());
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
                                r2.app.getThread().scheduleLocalVoiceInteractionStarted(r2.appToken, (IVoiceInteractor) null);
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
            this.mService.updateOomAdj();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean finishActivityAffinityLocked(ActivityRecord r) {
        ArrayList<ActivityRecord> activities = r.getTaskRecord().mActivities;
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
            if (ActivityTaskManagerDebugConfig.DEBUG_RESULTS) {
                Slog.v("ActivityTaskManager", "Adding result to " + resultTo + " who=" + r.resultWho + " req=" + r.requestCode + " res=" + resultCode + " data=" + resultData);
            }
            if (resultTo.mUserId != r.mUserId && resultData != null) {
                resultData.prepareToLeaveUser(r.mUserId);
            }
            if (r.info.applicationInfo.uid > 0) {
                this.mService.mUgmInternal.grantUriPermissionFromIntent(r.info.applicationInfo.uid, resultTo.packageName, resultData, resultTo.getUriPermissionsLocked(), resultTo.mUserId);
            }
            resultTo.addResultLocked(r, r.resultWho, r.requestCode, resultCode, resultData);
            r.resultTo = null;
        } else if (ActivityTaskManagerDebugConfig.DEBUG_RESULTS) {
            Slog.v("ActivityTaskManager", "No result destination from " + r);
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
            Slog.w("ActivityTaskManager", "Duplicate finish request for " + r);
            return false;
        }
        if (r.intent != null && (r.intent.getPrivateFlags() & 536870912) == 536870912) {
            Slog.i("ActivityTaskManager", "Finish home activity reason " + reason, new Throwable());
        }
        this.mWindowManager.deferSurfaceLayout();
        try {
            r.makeFinishingLocked();
            TaskRecord task = r.getTaskRecord();
            int i = 2;
            EventLog.writeEvent((int) EventLogTags.AM_FINISH_ACTIVITY, Integer.valueOf(r.mUserId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(task.taskId), r.shortComponentName, reason);
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
                        if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY || ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                            Slog.v("ActivityTaskManager", "Prepare close transition: finishing " + r);
                        }
                        if (endTask) {
                            this.mService.getTaskChangeNotificationController().notifyTaskRemovalStarted(task.getTaskInfo());
                        }
                        getDisplay().mDisplayContent.prepareAppTransition(transit, false);
                        if (this.mWindowManager.mTaskSnapshotController != null) {
                            ArraySet<Task> tasks = Sets.newArraySet(new Task[]{task.mTask});
                            this.mWindowManager.mTaskSnapshotController.snapshotTasks(tasks);
                            this.mWindowManager.mTaskSnapshotController.addSkipClosingAppSnapshotTasks(tasks);
                        }
                        r.setVisibility(false);
                        if (this.mPausingActivity == null) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                                Slog.v("ActivityTaskManager", "Finish needs to pause: " + r);
                            }
                            if (ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING) {
                                Slog.v("ActivityTaskManager", "finish() => pause with userLeaving=false");
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
                            if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                                Slog.v("ActivityTaskManager", "Finish not pausing: " + r);
                            }
                            if (r.visible) {
                                prepareActivityHideTransitionAnimation(r, transit);
                            }
                            if (!r.visible && !r.nowVisible) {
                                i = 1;
                            }
                            int finishMode = i;
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
                        } else if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE) {
                            Slog.v("ActivityTaskManager", "Finish waiting for pause of: " + r);
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
        DisplayContent dc = getDisplay().mDisplayContent;
        dc.prepareAppTransition(transit, false);
        r.setVisibility(false);
        dc.executeAppTransition();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityRecord finishCurrentActivityLocked(ActivityRecord r, int mode, boolean oomAdj, String reason) {
        ActivityDisplay display = getDisplay();
        ActivityRecord next = display.topRunningActivity(true);
        boolean isFloating = r.getConfiguration().windowConfiguration.tasksAreFloating();
        if (mode == 2 && ((r.visible || r.nowVisible) && next != null && !next.nowVisible && !isFloating)) {
            if (!this.mStackSupervisor.mStoppingActivities.contains(r)) {
                addToStopping(r, false, false, "finishCurrentActivityLocked");
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "Moving to STOPPING: " + r + " (finish requested)");
            }
            r.setState(ActivityState.STOPPING, "finishCurrentActivityLocked");
            if (oomAdj) {
                this.mService.updateOomAdj();
            }
            return r;
        }
        this.mStackSupervisor.mStoppingActivities.remove(r);
        this.mStackSupervisor.mGoingToSleepActivities.remove(r);
        ActivityState prevState = r.getState();
        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityTaskManager", "Moving to FINISHING: " + r);
        }
        r.setState(ActivityState.FINISHING, "finishCurrentActivityLocked");
        boolean noRunningStack = next == null && display.topRunningActivity() == null && display.getHomeStack() == null;
        boolean noFocusedStack = r.getActivityStack() != display.getFocusedStack();
        boolean finishingInNonFocusedStackOrNoRunning = mode == 2 && prevState == ActivityState.PAUSED && (noFocusedStack || noRunningStack);
        if (mode == 0 || ((prevState == ActivityState.PAUSED && (mode == 1 || inPinnedWindowingMode())) || finishingInNonFocusedStackOrNoRunning || prevState == ActivityState.STOPPING || prevState == ActivityState.STOPPED || prevState == ActivityState.INITIALIZING)) {
            r.makeFinishingLocked();
            boolean activityRemoved = destroyActivityLocked(r, true, "finish-imm:" + reason);
            if (finishingInNonFocusedStackOrNoRunning) {
                this.mRootActivityContainer.ensureVisibilityAndConfig(next, this.mDisplayId, false, true);
            }
            if (activityRemoved) {
                this.mRootActivityContainer.resumeFocusedStacksTopActivities();
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_CONTAINERS) {
                Slog.d("ActivityTaskManager", "destroyActivityLocked: finishCurrentActivityLocked r=" + r + " destroy returned removed=" + activityRemoved);
            }
            if (activityRemoved) {
                return null;
            }
            return r;
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_ALL) {
            Slog.v("ActivityTaskManager", "Enqueueing pending finish: " + r);
        }
        this.mStackSupervisor.mFinishingActivities.add(r);
        r.resumeKeyDispatchingLocked();
        this.mRootActivityContainer.resumeFocusedStacksTopActivities();
        if (r.isState(ActivityState.RESUMED) && this.mPausingActivity != null) {
            startPausingLocked(false, false, next, false);
        }
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
                    Slog.d("ActivityTaskManager", "finishAllActivitiesLocked: finishing " + r + " immediately");
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
        if (srec == null || srec.getTaskRecord().affinity == null || !srec.getTaskRecord().affinity.equals(destAffinity)) {
            return true;
        }
        TaskRecord task = srec.getTaskRecord();
        if (srec.frontOfTask && task.getBaseIntent() != null && task.getBaseIntent().isDocument()) {
            if (!inFrontOfStandardStack()) {
                return true;
            }
            int taskIdx = this.mTaskHistory.indexOf(task);
            if (taskIdx <= 0) {
                Slog.w("ActivityTaskManager", "shouldUpRecreateTask: task not in history for " + srec);
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
        int finishTo;
        ActivityRecord parent;
        boolean foundParentInTask;
        int callingUid;
        ActivityRecord next;
        if (srec.attachedToProcess()) {
            TaskRecord task = srec.getTaskRecord();
            ArrayList<ActivityRecord> activities = task.mActivities;
            int start = activities.indexOf(srec);
            if (!this.mTaskHistory.contains(task) || start < 0) {
                return false;
            }
            int finishTo2 = start - 1;
            ActivityRecord parent2 = finishTo2 < 0 ? null : activities.get(finishTo2);
            ComponentName dest = destIntent.getComponent();
            if (start > 0 && dest != null) {
                for (int i = finishTo2; i >= 0; i--) {
                    ActivityRecord r = activities.get(i);
                    if (r.info.packageName.equals(dest.getPackageName()) && r.info.name.equals(dest.getClassName())) {
                        finishTo = i;
                        parent = r;
                        foundParentInTask = true;
                        break;
                    }
                }
            }
            finishTo = finishTo2;
            parent = parent2;
            foundParentInTask = false;
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
            int i2 = start;
            int resultCode2 = resultCode;
            Intent resultData2 = resultData;
            while (i2 > finishTo) {
                requestFinishActivityLocked(activities.get(i2).appToken, resultCode2, resultData2, "navigate-up", true);
                resultCode2 = 0;
                resultData2 = null;
                i2--;
                finishTo = finishTo;
                controller = controller;
            }
            if (parent != null && foundParentInTask) {
                int callingUid2 = srec.info.applicationInfo.uid;
                int parentLaunchMode = parent.info.launchMode;
                int destIntentFlags = destIntent.getFlags();
                if (parentLaunchMode != 3 && parentLaunchMode != 2) {
                    if (parentLaunchMode != 1) {
                        if ((destIntentFlags & 67108864) != 0) {
                            callingUid = callingUid2;
                            parent.deliverNewIntentLocked(callingUid, destIntent, srec.packageName);
                        } else {
                            try {
                                ActivityInfo aInfo = AppGlobals.getPackageManager().getActivityInfo(destIntent.getComponent(), 1024, srec.mUserId);
                                int res = this.mService.getActivityStartController().obtainStarter(destIntent, "navigateUpTo").setCaller(srec.app.getThread()).setActivityInfo(aInfo).setResultTo(parent.appToken).setCallingPid(-1).setCallingUid(callingUid2).setCallingPackage(srec.packageName).setRealCallingPid(-1).setRealCallingUid(callingUid2).setComponentSpecified(true).execute();
                                boolean foundParentInTask2 = res == 0;
                                foundParentInTask = foundParentInTask2;
                            } catch (RemoteException e2) {
                                foundParentInTask = false;
                            }
                            requestFinishActivityLocked(parent.appToken, resultCode2, resultData2, "navigate-top", true);
                        }
                    }
                }
                callingUid = callingUid2;
                parent.deliverNewIntentLocked(callingUid, destIntent, srec.packageName);
            }
            Binder.restoreCallingIdentity(origId);
            return foundParentInTask;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onActivityRemovedFromStack(ActivityRecord r) {
        removeTimeoutsForActivityLocked(r);
        ActivityRecord activityRecord = this.mResumedActivity;
        if (activityRecord != null && activityRecord == r) {
            setResumedActivity(null, "onActivityRemovedFromStack");
        }
        ActivityRecord activityRecord2 = this.mPausingActivity;
        if (activityRecord2 != null && activityRecord2 == r) {
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
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "Moving to DESTROYED: " + r + " (cleaning up)");
            }
            r.setState(ActivityState.DESTROYED, "cleanupActivityLocked");
            if (ActivityTaskManagerDebugConfig.DEBUG_APP) {
                Slog.v("ActivityTaskManager", "Clearing app during cleanUp for activity " + r);
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
                    this.mService.mPendingIntentController.cancelIntentSender(rec, false);
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

    private void removeTimeoutsForActivityLocked(ActivityRecord r) {
        this.mStackSupervisor.removeTimeoutsForActivityLocked(r);
        this.mHandler.removeMessages(101, r);
        this.mHandler.removeMessages(104, r);
        this.mHandler.removeMessages(102, r);
        r.finishLaunchTickingLocked();
    }

    private void removeActivityFromHistoryLocked(ActivityRecord r, String reason) {
        finishActivityResultsLocked(r, 0, null);
        r.makeFinishingLocked();
        if (ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.i("ActivityTaskManager", "Removing activity " + r + " from stack callers=" + Debug.getCallers(5));
        }
        r.takeFromHistory();
        removeTimeoutsForActivityLocked(r);
        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityTaskManager", "Moving to DESTROYED: " + r + " (removed from history)");
        }
        r.setState(ActivityState.DESTROYED, "removeActivityFromHistoryLocked");
        if (ActivityTaskManagerDebugConfig.DEBUG_APP) {
            Slog.v("ActivityTaskManager", "Clearing app during remove for activity " + r);
        }
        r.app = null;
        r.removeWindowContainer();
        TaskRecord task = r.getTaskRecord();
        boolean lastActivity = task != null ? task.removeActivity(r) : false;
        boolean onlyHasTaskOverlays = task != null ? task.onlyHasTaskOverlayActivities(false) : false;
        if (lastActivity || onlyHasTaskOverlays) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.i("ActivityTaskManager", "removeActivityFromHistoryLocked: last activity removed from " + this + " onlyHasTaskOverlays=" + onlyHasTaskOverlays);
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
        if (r.mServiceConnectionsHolder == null) {
            return;
        }
        r.mServiceConnectionsHolder.disconnectActivityFromServices();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleDestroyActivities(WindowProcessController owner, String reason) {
        Message msg = this.mHandler.obtainMessage(105);
        msg.obj = new ScheduleDestroyArgs(owner, reason);
        this.mHandler.sendMessage(msg);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroyActivitiesLocked(WindowProcessController owner, String reason) {
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
                        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                            Slog.v("ActivityTaskManager", "Destroying " + r + " in state " + r.getState() + " resumed=" + this.mResumedActivity + " pausing=" + this.mPausingActivity + " for reason " + reason);
                        }
                        if (destroyActivityLocked(r, true, reason)) {
                            activityRemoved = true;
                        }
                    }
                }
            }
        }
        if (activityRemoved) {
            this.mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean safelyDestroyActivityLocked(ActivityRecord r, String reason) {
        if (r.isDestroyable()) {
            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                Slog.v("ActivityTaskManager", "Destroying " + r + " in state " + r.getState() + " resumed=" + this.mResumedActivity + " pausing=" + this.mPausingActivity + " for reason " + reason);
            }
            return destroyActivityLocked(r, true, reason);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final int releaseSomeActivitiesLocked(WindowProcessController app, ArraySet<TaskRecord> tasks, String reason) {
        if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
            Slog.d("ActivityTaskManager", "Trying to release some activities in " + app);
        }
        int maxTasks = tasks.size() / 4;
        if (maxTasks < 1) {
            maxTasks = 1;
        }
        int numReleased = 0;
        int taskNdx = 0;
        while (taskNdx < this.mTaskHistory.size() && maxTasks > 0) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            if (tasks.contains(task)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
                    Slog.d("ActivityTaskManager", "Looking for activities to release in " + task);
                }
                int curNum = 0;
                ArrayList<ActivityRecord> activities = task.mActivities;
                int actNdx = 0;
                while (actNdx < activities.size()) {
                    ActivityRecord activity = activities.get(actNdx);
                    if (activity.app == app && activity.isDestroyable()) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
                            Slog.v("ActivityTaskManager", "Destroying " + activity + " in state " + activity.getState() + " resumed=" + this.mResumedActivity + " pausing=" + this.mPausingActivity + " for reason " + reason);
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
                    maxTasks--;
                    if (this.mTaskHistory.get(taskNdx) != task) {
                        taskNdx--;
                    }
                }
            }
            taskNdx++;
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
            Slog.d("ActivityTaskManager", "Done releasing: did " + numReleased + " activities");
        }
        return numReleased;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean destroyActivityLocked(ActivityRecord r, boolean removeFromApp, String reason) {
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
            StringBuilder sb = new StringBuilder();
            sb.append("Removing activity from ");
            sb.append(reason);
            sb.append(": token=");
            sb.append(r);
            sb.append(", app=");
            sb.append(r.hasProcess() ? r.app.mName : "(null)");
            Slog.v("ActivityTaskManager", sb.toString());
        }
        if (r.isState(ActivityState.DESTROYING, ActivityState.DESTROYED)) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "activity " + r + " already destroying.skipping request with reason:" + reason);
            }
            return false;
        }
        EventLog.writeEvent((int) EventLogTags.AM_DESTROY_ACTIVITY, Integer.valueOf(r.mUserId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(r.getTaskRecord().taskId), r.shortComponentName, reason);
        boolean removedFromHistory = false;
        cleanUpActivityLocked(r, false, false);
        boolean hadApp = r.hasProcess();
        if (hadApp) {
            if (removeFromApp) {
                r.app.removeActivity(r);
                if (!r.app.hasActivities()) {
                    this.mService.clearHeavyWeightProcessIfEquals(r.app);
                }
                if (!r.app.hasActivities()) {
                    r.app.updateProcessInfo(true, false, true);
                }
            }
            boolean skipDestroy = false;
            try {
                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
                    Slog.i("ActivityTaskManager", "Destroying: " + r);
                }
                this.mService.getLifecycleManager().scheduleTransaction(r.app.getThread(), (IBinder) r.appToken, (ActivityLifecycleItem) DestroyActivityItem.obtain(r.finishing, r.configChangeFlags));
            } catch (Exception e) {
                if (r.finishing) {
                    removeActivityFromHistoryLocked(r, reason + " exceptionInScheduleDestroy");
                    removedFromHistory = true;
                    skipDestroy = true;
                }
            }
            r.nowVisible = false;
            if (r.finishing && !skipDestroy) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "Moving to DESTROYING: " + r + " (destroy requested)");
                }
                r.setState(ActivityState.DESTROYING, "destroyActivityLocked. finishing and not skipping destroy");
                Message msg = this.mHandler.obtainMessage(102, r);
                this.mHandler.sendMessageDelayed(msg, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
            } else {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "Moving to DESTROYED: " + r + " (destroy skipped)");
                }
                r.setState(ActivityState.DESTROYED, "destroyActivityLocked. not finishing or skipping destroy");
                if (ActivityTaskManagerDebugConfig.DEBUG_APP) {
                    Slog.v("ActivityTaskManager", "Clearing app during destroy for activity " + r);
                }
                r.app = null;
            }
        } else if (r.finishing) {
            removeActivityFromHistoryLocked(r, reason + " hadNoApp");
            removedFromHistory = true;
        } else {
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "Moving to DESTROYED: " + r + " (no app)");
            }
            r.setState(ActivityState.DESTROYED, "destroyActivityLocked. not finishing and had no app");
            if (ActivityTaskManagerDebugConfig.DEBUG_APP) {
                Slog.v("ActivityTaskManager", "Clearing app during destroy for activity " + r);
            }
            r.app = null;
        }
        r.configChangeFlags = 0;
        if (!this.mLRUActivities.remove(r) && hadApp) {
            Slog.w("ActivityTaskManager", "Activity " + r + " being finished, but not in LRU list");
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
        if (ActivityTaskManagerDebugConfig.DEBUG_CONTAINERS) {
            Slog.d("ActivityTaskManager", "activityDestroyedLocked: r=" + record);
        }
        if (isInStackLocked(record) != null && record.isState(ActivityState.DESTROYING, ActivityState.DESTROYED)) {
            cleanUpActivityLocked(record, true, false);
            removeActivityFromHistoryLocked(record, reason);
        }
        this.mRootActivityContainer.resumeFocusedStacksTopActivities();
    }

    private void removeHistoryRecordsForAppLocked(ArrayList<ActivityRecord> list, WindowProcessController app, String listName) {
        int i = list.size();
        if (ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
            Slog.v("ActivityTaskManager", "Removing app " + app + " from list " + listName + " with " + i + " entries");
        }
        while (i > 0) {
            i--;
            ActivityRecord r = list.get(i);
            if (ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                Slog.v("ActivityTaskManager", "Record #" + i + " " + r);
            }
            if (r.app == app) {
                if (ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                    Slog.v("ActivityTaskManager", "---> REMOVING this entry!");
                }
                list.remove(i);
                removeTimeoutsForActivityLocked(r);
            }
        }
    }

    private boolean removeHistoryRecordsForAppLocked(WindowProcessController app) {
        boolean remove;
        removeHistoryRecordsForAppLocked(this.mLRUActivities, app, "mLRUActivities");
        removeHistoryRecordsForAppLocked(this.mStackSupervisor.mStoppingActivities, app, "mStoppingActivities");
        removeHistoryRecordsForAppLocked(this.mStackSupervisor.mGoingToSleepActivities, app, "mGoingToSleepActivities");
        removeHistoryRecordsForAppLocked(this.mStackSupervisor.mFinishingActivities, app, "mFinishingActivities");
        boolean isProcessRemoved = app.isRemoved();
        if (isProcessRemoved) {
            app.makeFinishingForProcessRemoved();
        }
        boolean hasVisibleActivities = false;
        int i = numActivities();
        if (ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
            Slog.v("ActivityTaskManager", "Removing app " + app + " from history with " + i + " entries");
        }
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            this.mTmpActivities.clear();
            this.mTmpActivities.addAll(activities);
            while (!this.mTmpActivities.isEmpty()) {
                int targetIndex = this.mTmpActivities.size() - 1;
                ActivityRecord r = this.mTmpActivities.remove(targetIndex);
                if (ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                    Slog.v("ActivityTaskManager", "Record #" + targetIndex + " " + r + ": app=" + r.app);
                }
                if (r.app == app) {
                    if (r.visible) {
                        hasVisibleActivities = true;
                    }
                    if ((r.mRelaunchReason == 1 || r.mRelaunchReason == 2) && r.launchCount < 3 && !r.finishing) {
                        remove = false;
                    } else {
                        boolean remove2 = r.haveState;
                        if ((!remove2 && !r.stateNotNeeded && !r.isState(ActivityState.RESTARTING_PROCESS)) || r.finishing) {
                            remove = true;
                        } else {
                            boolean remove3 = r.visible;
                            if (!remove3 && r.launchCount > 2 && r.lastLaunchTime > SystemClock.uptimeMillis() - 60000) {
                                remove = true;
                            } else {
                                remove = false;
                            }
                        }
                    }
                    if (remove) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_ADD_REMOVE || ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                            Slog.i("ActivityTaskManager", "Removing activity " + r + " from stack at " + i + ": haveState=" + r.haveState + " stateNotNeeded=" + r.stateNotNeeded + " finishing=" + r.finishing + " state=" + r.getState() + " callers=" + Debug.getCallers(5));
                        }
                        if (!r.finishing || isProcessRemoved) {
                            Slog.w("ActivityTaskManager", "Force removing " + r + ": app died, no saved state");
                            EventLog.writeEvent((int) EventLogTags.AM_FINISH_ACTIVITY, Integer.valueOf(r.mUserId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(r.getTaskRecord().taskId), r.shortComponentName, "proc died without state saved");
                        }
                    } else {
                        if (ActivityTaskManagerDebugConfig.DEBUG_ALL) {
                            Slog.v("ActivityTaskManager", "Keeping entry, setting app to null");
                        }
                        if (ActivityTaskManagerDebugConfig.DEBUG_APP) {
                            Slog.v("ActivityTaskManager", "Clearing app during removeHistory for activity " + r);
                        }
                        r.app = null;
                        r.nowVisible = r.visible;
                        if (!r.haveState) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_SAVED_STATE) {
                                Slog.i("ActivityTaskManager", "App died, clearing saved state of " + r);
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
        getDisplay().mDisplayContent.prepareAppTransition(transit, false);
    }

    private void updateTaskMovement(TaskRecord task, boolean toFront) {
        if (task.isPersistable) {
            task.mLastTimeMoved = System.currentTimeMillis();
            if (!toFront) {
                task.mLastTimeMoved *= -1;
            }
        }
        this.mRootActivityContainer.invalidateTaskLayers();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void moveTaskToFrontLocked(TaskRecord tr, boolean noAnimation, ActivityOptions options, AppTimeTracker timeTracker, String reason) {
        ActivityRecord top;
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.v("ActivityTaskManager", "moveTaskToFront: " + tr);
        }
        ActivityStack topStack = getDisplay().getTopStack();
        ActivityRecord topActivity = topStack != null ? topStack.getTopActivity() : null;
        int numTasks = this.mTaskHistory.size();
        int index = this.mTaskHistory.indexOf(tr);
        if (numTasks != 0 && index >= 0) {
            if (timeTracker != null) {
                for (int i = tr.mActivities.size() - 1; i >= 0; i--) {
                    tr.mActivities.get(i).appTimeTracker = timeTracker;
                }
            }
            try {
                getDisplay().deferUpdateImeTarget();
                insertTaskAtTop(tr, null);
                top = tr.getTopActivity();
            } catch (Throwable th) {
                th = th;
            }
            try {
                if (top != null && top.okToShowLocked()) {
                    ActivityRecord r = topRunningActivityLocked();
                    if (r != null) {
                        r.moveFocusableActivityToTop(reason);
                    }
                    if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                        Slog.v("ActivityTaskManager", "Prepare to front transition: task=" + tr);
                    }
                    if (noAnimation) {
                        getDisplay().mDisplayContent.prepareAppTransition(0, false);
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
                    this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                    EventLog.writeEvent((int) EventLogTags.AM_TASK_TO_FRONT, Integer.valueOf(tr.userId), Integer.valueOf(tr.taskId));
                    this.mService.getTaskChangeNotificationController().notifyTaskMovedToFront(tr.getTaskInfo());
                    getDisplay().continueUpdateImeTarget();
                    return;
                }
                if (top != null) {
                    this.mStackSupervisor.mRecentTasks.add(top.getTaskRecord());
                }
                ActivityOptions.abort(options);
                getDisplay().continueUpdateImeTarget();
                return;
            } catch (Throwable th2) {
                th = th2;
                getDisplay().continueUpdateImeTarget();
                throw th;
            }
        }
        if (noAnimation) {
            ActivityOptions.abort(options);
        } else {
            updateTransitLocked(10, options);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean moveTaskToBackLocked(int taskId) {
        TaskRecord tr = taskForIdLocked(taskId);
        if (tr == null) {
            Slog.i("ActivityTaskManager", "moveTaskToBack: bad taskId=" + taskId);
            return false;
        }
        Slog.i("ActivityTaskManager", "moveTaskToBack: " + tr);
        if (this.mService.getLockTaskController().canMoveTaskToBack(tr)) {
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
            if (ActivityTaskManagerDebugConfig.DEBUG_TRANSITION) {
                Slog.v("ActivityTaskManager", "Prepare to back transition: task=" + taskId);
            }
            this.mTaskHistory.remove(tr);
            this.mTaskHistory.add(0, tr);
            updateTaskMovement(tr, false);
            getDisplay().mDisplayContent.prepareAppTransition(11, false);
            moveToBack("moveTaskToBackLocked", tr);
            if (inPinnedWindowingMode()) {
                this.mStackSupervisor.removeStack(this);
                return true;
            }
            ActivityRecord topActivity = getDisplay().topRunningActivity();
            ActivityStack topStack = topActivity.getActivityStack();
            if (topStack != null && topStack != this && topActivity.isState(ActivityState.RESUMED)) {
                this.mRootActivityContainer.ensureVisibilityAndConfig(null, getDisplay().mDisplayId, false, false);
            }
            this.mRootActivityContainer.resumeFocusedStacksTopActivities();
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void logStartActivity(int tag, ActivityRecord r, TaskRecord task) {
        Uri data = r.intent.getData();
        String strData = data != null ? data.toSafeString() : null;
        EventLog.writeEvent(tag, Integer.valueOf(r.mUserId), Integer.valueOf(System.identityHashCode(r)), Integer.valueOf(task.taskId), r.shortComponentName, r.intent.getAction(), r.intent.getType(), strData, Integer.valueOf(r.intent.getFlags()));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void ensureVisibleActivitiesConfigurationLocked(ActivityRecord start, boolean preserveWindow) {
        if (start == null || !start.visible) {
            return;
        }
        TaskRecord startTask = start.getTaskRecord();
        boolean behindFullscreen = false;
        boolean updatedConfig = false;
        for (int taskIndex = this.mTaskHistory.indexOf(startTask); taskIndex >= 0; taskIndex--) {
            TaskRecord task = this.mTaskHistory.get(taskIndex);
            ArrayList<ActivityRecord> activities = task.mActivities;
            int activityIndex = start.getTaskRecord() == task ? activities.indexOf(start) : activities.size() - 1;
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
            this.mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestResize(Rect bounds) {
        this.mService.resizeStack(this.mStackId, bounds, true, false, false, -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resize(Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds) {
        if (!updateBoundsAllowed(bounds)) {
            return;
        }
        Rect taskBounds = tempTaskBounds != null ? tempTaskBounds : bounds;
        for (int i = this.mTaskHistory.size() - 1; i >= 0; i--) {
            TaskRecord task = this.mTaskHistory.get(i);
            if (task.isResizeable()) {
                task.updateOverrideConfiguration(taskBounds, tempTaskInsetBounds);
            }
        }
        setBounds(bounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPipAnimationEndResize() {
        TaskStack taskStack = this.mTaskStack;
        if (taskStack == null) {
            return;
        }
        taskStack.onPipAnimationEndResize();
    }

    void setTaskBounds(Rect bounds) {
        if (!updateBoundsAllowed(bounds)) {
            return;
        }
        for (int i = this.mTaskHistory.size() - 1; i >= 0; i--) {
            TaskRecord task = this.mTaskHistory.get(i);
            if (task.isResizeable()) {
                task.setBounds(bounds);
            } else {
                task.setBounds(null);
            }
        }
    }

    void setTaskDisplayedBounds(Rect bounds) {
        if (!updateDisplayedBoundsAllowed(bounds)) {
            return;
        }
        for (int i = this.mTaskHistory.size() - 1; i >= 0; i--) {
            TaskRecord task = this.mTaskHistory.get(i);
            if (bounds == null || bounds.isEmpty()) {
                task.setDisplayedBounds(null);
            } else if (task.isResizeable()) {
                task.setDisplayedBounds(bounds);
            }
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
            Slog.e("ActivityTaskManager", "willActivityBeVisibleLocked: Returning false, would have returned true for r=" + r2);
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
    /* JADX WARN: Code restructure failed: missing block: B:30:0x007b, code lost:
        if (r13.finishing == false) goto L47;
     */
    /* JADX WARN: Code restructure failed: missing block: B:32:0x007e, code lost:
        return true;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean finishDisabledPackageActivitiesLocked(java.lang.String r19, java.util.Set<java.lang.String> r20, boolean r21, boolean r22, int r23) {
        /*
            Method dump skipped, instructions count: 220
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityStack.finishDisabledPackageActivitiesLocked(java.lang.String, java.util.Set, boolean, boolean, int):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getRunningTasks(List<TaskRecord> tasksOut, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode, int callingUid, boolean allowed, boolean crossUser, ArraySet<Integer> profileIds) {
        boolean focusedStack = this.mRootActivityContainer.getTopDisplayFocusedStack() == this;
        boolean topTask = true;
        int userId = UserHandle.getUserId(callingUid);
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            if (task.getTopActivity() != null && ((task.effectiveUid == callingUid || ((task.userId == userId || crossUser || profileIds.contains(Integer.valueOf(task.userId))) && (allowed || task.isActivityTypeHome()))) && ((ignoreActivityType == 0 || task.getActivityType() != ignoreActivityType) && (ignoreWindowingMode == 0 || task.getWindowingMode() != ignoreWindowingMode)))) {
                if (focusedStack && topTask) {
                    task.lastActiveTime = SystemClock.elapsedRealtime();
                    topTask = false;
                }
                tasksOut.add(task);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unhandledBackLocked() {
        int top = this.mTaskHistory.size() - 1;
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityTaskManager", "Performing unhandledBack(): top activity at " + top);
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
    public boolean handleAppDiedLocked(WindowProcessController app) {
        ActivityRecord activityRecord = this.mPausingActivity;
        if (activityRecord != null && activityRecord.app == app) {
            if (ActivityTaskManagerDebugConfig.DEBUG_PAUSE || ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                Slog.v("ActivityTaskManager", "App died while pausing: " + this.mPausingActivity);
            }
            this.mPausingActivity = null;
        }
        ActivityRecord activityRecord2 = this.mLastPausedActivity;
        if (activityRecord2 != null && activityRecord2.app == app) {
            this.mLastPausedActivity = null;
            this.mLastNoHistoryActivity = null;
        }
        return removeHistoryRecordsForAppLocked(app);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleAppCrash(WindowProcessController app) {
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            ArrayList<ActivityRecord> activities = this.mTaskHistory.get(taskNdx).mActivities;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                ActivityRecord r = activities.get(activityNdx);
                if (r.app == app) {
                    Slog.w("ActivityTaskManager", "  Force finishing activity " + r.intent.getComponent().flattenToShortString());
                    r.app = null;
                    getDisplay().mDisplayContent.prepareAppTransition(26, false);
                    finishCurrentActivityLocked(r, 0, false, "handleAppCrashedLocked");
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dump(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient, String dumpPackage, boolean needSep) {
        pw.println("  Stack #" + this.mStackId + ": type=" + WindowConfiguration.activityTypeToString(getActivityType()) + " mode=" + WindowConfiguration.windowingModeToString(getWindowingMode()));
        StringBuilder sb = new StringBuilder();
        sb.append("  isSleeping=");
        sb.append(shouldSleepActivities());
        pw.println(sb.toString());
        pw.println("  mBounds=" + getRequestedOverrideBounds());
        boolean printed = ActivityStackSupervisor.dumpHistoryList(fd, pw, this.mLRUActivities, "    ", "Run", false, dumpAll ^ true, false, dumpPackage, true, "    Running activities (most recent first):", null) | dumpActivitiesLocked(fd, pw, dumpAll, dumpClient, dumpPackage, needSep);
        boolean needSep2 = printed;
        boolean pr = ActivityStackSupervisor.printThisActivity(pw, this.mPausingActivity, dumpPackage, needSep2, "    mPausingActivity: ");
        if (pr) {
            printed = true;
            needSep2 = false;
        }
        boolean pr2 = ActivityStackSupervisor.printThisActivity(pw, getResumedActivity(), dumpPackage, needSep2, "    mResumedActivity: ");
        if (pr2) {
            printed = true;
            needSep2 = false;
        }
        if (dumpAll) {
            boolean pr3 = ActivityStackSupervisor.printThisActivity(pw, this.mLastPausedActivity, dumpPackage, needSep2, "    mLastPausedActivity: ");
            if (pr3) {
                printed = true;
                needSep2 = true;
            }
            return printed | ActivityStackSupervisor.printThisActivity(pw, this.mLastNoHistoryActivity, dumpPackage, needSep2, "    mLastNoHistoryActivity: ");
        }
        return printed;
    }

    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient, String dumpPackage, boolean needSep) {
        if (this.mTaskHistory.isEmpty()) {
            return false;
        }
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx += -1) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            if (needSep) {
                pw.println("");
            }
            pw.println("    Task id #" + task.taskId);
            pw.println("    mBounds=" + task.getRequestedOverrideBounds());
            pw.println("    mMinWidth=" + task.mMinWidth);
            pw.println("    mMinHeight=" + task.mMinHeight);
            pw.println("    mLastNonFullscreenBounds=" + task.mLastNonFullscreenBounds);
            pw.println("    * " + task);
            task.dump(pw, "      ");
            ActivityStackSupervisor.dumpHistoryList(fd, pw, this.mTaskHistory.get(taskNdx).mActivities, "    ", "Hist", true, dumpAll ^ true, dumpClient, dumpPackage, false, null, task);
        }
        return true;
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
        boolean removed = this.mTaskHistory.remove(task);
        if (removed) {
            EventLog.writeEvent((int) EventLogTags.AM_REMOVE_TASK, Integer.valueOf(task.taskId), Integer.valueOf(getStackId()));
        }
        removeActivitiesFromLRUListLocked(task);
        updateTaskMovement(task, true);
        if (mode == 0) {
            task.cleanUpResourcesForDestroy();
        }
        ActivityDisplay display = getDisplay();
        if (this.mTaskHistory.isEmpty()) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.i("ActivityTaskManager", "removeTask: removing stack=" + this);
            }
            if (mode != 2 && this.mRootActivityContainer.isTopDisplayFocusedStack(this)) {
                String myReason = reason + " leftTaskHistoryEmpty";
                if (!inMultiWindowMode() || adjustFocusToNextFocusableStack(myReason) == null) {
                    display.moveHomeStackToFront(myReason);
                }
            }
            if (isAttached()) {
                display.positionChildAtBottom(this);
            }
            if (!isActivityTypeHome() || !isAttached()) {
                remove();
            }
        }
        task.setStack(null);
        if (inPinnedWindowingMode()) {
            this.mService.getTaskChangeNotificationController().notifyActivityUnpinned();
        }
        if (display.isSingleTaskInstance()) {
            this.mService.notifySingleTaskDisplayEmpty(display.mDisplayId);
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
        int displayId = this.mDisplayId;
        if (displayId == -1) {
            displayId = 0;
        }
        boolean isLockscreenShown = this.mService.mStackSupervisor.getKeyguardController().isKeyguardOrAodShowing(displayId);
        if (!this.mStackSupervisor.getLaunchParamsController().layoutTask(task, info.windowLayout, activity, source, options) && !matchParentBounds() && task.isResizeable() && !isLockscreenShown) {
            task.updateOverrideConfiguration(getRequestedOverrideBounds());
        }
        task.createTask(toTop, (info.flags & 1024) != 0);
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
            positionChildWindowContainerAtTop(task);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addTask(TaskRecord task, int position, boolean schedulePictureInPictureModeChange, String reason) {
        this.mTaskHistory.remove(task);
        if (isSingleTaskInstance() && !this.mTaskHistory.isEmpty()) {
            throw new IllegalStateException("Can only have one child on stack=" + this);
        }
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
                Log.wtf("ActivityTaskManager", "mResumedActivity was already set when moving mResumedActivity from other stack to this stack mResumedActivity=" + this.mResumedActivity + " other mResumedActivity=" + topRunningActivity);
            }
            topRunningActivity.setState(ActivityState.RESUMED, "positionChildAt");
        }
        ensureActivitiesVisibleLocked(null, 0, false);
        this.mRootActivityContainer.resumeFocusedStacksTopActivities();
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

    @Override // com.android.server.wm.ConfigurationContainer
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (isAlwaysOnTop() == alwaysOnTop) {
            return;
        }
        super.setAlwaysOnTop(alwaysOnTop);
        ActivityDisplay display = getDisplay();
        display.positionChildAtTop(this, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveToFrontAndResumeStateIfNeeded(ActivityRecord r, boolean moveToFront, boolean setResume, boolean setPause, String reason) {
        if (!moveToFront) {
            return;
        }
        ActivityState origState = r.getState();
        if (setResume) {
            r.setState(ActivityState.RESUMED, "moveToFrontAndResumeStateIfNeeded");
            updateLRUListLocked(r);
        }
        if (setPause) {
            this.mPausingActivity = r;
            schedulePauseTimeout(r);
        }
        moveToFront(reason);
        if (origState == ActivityState.RESUMED && r == this.mRootActivityContainer.getTopResumedActivity()) {
            this.mService.setResumedActivityUncheckLocked(r, reason);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getDefaultPictureInPictureBounds(float aspectRatio) {
        if (getTaskStack() == null) {
            return null;
        }
        return getTaskStack().getPictureInPictureBounds(aspectRatio, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void animateResizePinnedStack(Rect sourceHintBounds, Rect toBounds, int animationDuration, boolean fromFullscreen) {
        if (inPinnedWindowingMode()) {
            if (toBounds == null) {
                Configuration parentConfig = getParent().getConfiguration();
                ActivityRecord top = topRunningNonOverlayTaskActivity();
                if (top != null && !top.isConfigurationCompatible(parentConfig)) {
                    top.startFreezingScreenLocked(top.app, 256);
                    this.mService.moveTasksToFullscreenStack(this.mStackId, true);
                    return;
                }
            }
            if (getTaskStack() == null) {
                return;
            }
            getTaskStack().animateResizePinnedStack(toBounds, sourceHintBounds, animationDuration, fromFullscreen);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getAnimationOrCurrentBounds(Rect outBounds) {
        TaskStack stack = getTaskStack();
        if (stack == null) {
            outBounds.setEmpty();
        } else {
            stack.getAnimationOrCurrentBounds(outBounds);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPictureInPictureAspectRatio(float aspectRatio) {
        if (getTaskStack() == null) {
            return;
        }
        getTaskStack().setPictureInPictureAspectRatio(aspectRatio);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPictureInPictureActions(List<RemoteAction> actions) {
        if (getTaskStack() == null) {
            return;
        }
        getTaskStack().setPictureInPictureActions(actions);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimatingBoundsToFullscreen() {
        if (getTaskStack() == null) {
            return false;
        }
        return getTaskStack().isAnimatingBoundsToFullscreen();
    }

    public void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds, boolean forceUpdate) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (!isAttached()) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ArrayList<TaskRecord> tasks = getAllTasks();
                for (int i = 0; i < tasks.size(); i++) {
                    this.mStackSupervisor.updatePictureInPictureMode(tasks.get(i), targetStackBounds, forceUpdate);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
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
        getDisplay().mDisplayContent.executeAppTransition();
        ActivityOptions.abort(options);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldSleepActivities() {
        ActivityDisplay display = getDisplay();
        if (isFocusedStackOnDisplay() && this.mStackSupervisor.getKeyguardController().isKeyguardGoingAway()) {
            return false;
        }
        return display != null ? display.isSleeping() : this.mService.isSleepingLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || this.mService.mShuttingDown;
    }

    public ArrayList<ActivityRecord> getHistoryActivitiesLocked() {
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        try {
            if (this.mTaskHistory != null && !this.mTaskHistory.isEmpty()) {
                int taskNdx = this.mTaskHistory.size() - 1;
                while (taskNdx >= 0) {
                    int size = this.mTaskHistory.size();
                    TaskRecord record = taskNdx < size ? this.mTaskHistory.get(taskNdx) : null;
                    if (record != null) {
                        activities.addAll(record.mActivities);
                    }
                    taskNdx--;
                }
            }
        } catch (Exception e) {
        }
        return activities;
    }

    public boolean pauseActivityLocked(ActivityRecord pauseActivity) {
        if (pauseActivity != null) {
            pauseActivity.setState(ActivityState.PAUSING, "startPausingLocked");
            pauseActivity.getTaskRecord().touchActiveTime();
            clearLaunchTime(pauseActivity);
            this.mService.updateCpuStats();
            if (pauseActivity.attachedToProcess()) {
                try {
                    EventLogTags.writeAmPauseActivity(pauseActivity.mUserId, System.identityHashCode(pauseActivity), pauseActivity.shortComponentName, "userLeaving=true");
                    this.mService.getLifecycleManager().scheduleTransaction(pauseActivity.app.getThread(), (IBinder) pauseActivity.appToken, PauseActivityItem.obtain(pauseActivity.finishing, true, pauseActivity.configChangeFlags, true, 0));
                } catch (Exception e) {
                    xpLogger.i("ActivityTaskManager", "pauseActivityLocked e: " + e + " pause=" + pauseActivity);
                }
            }
            if (!this.mService.isSleepingOrShuttingDownLocked()) {
                this.mStackSupervisor.acquireLaunchWakelock();
            }
            pauseActivity.setState(ActivityState.PAUSED, "completePausedLocked");
            xpLogger.i("ActivityTaskManager", "pauseActivityLocked start paused: " + pauseActivity);
            return true;
        }
        return false;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        proto.write(1120986464258L, this.mStackId);
        for (int taskNdx = this.mTaskHistory.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = this.mTaskHistory.get(taskNdx);
            task.writeToProto(proto, 2246267895811L, logLevel);
        }
        ActivityRecord activityRecord = this.mResumedActivity;
        if (activityRecord != null) {
            activityRecord.writeIdentifierToProto(proto, 1146756268036L);
        }
        proto.write(1120986464261L, this.mDisplayId);
        if (!matchParentBounds()) {
            Rect bounds = getRequestedOverrideBounds();
            bounds.writeToProto(proto, 1146756268039L);
        }
        proto.write(1133871366150L, matchParentBounds());
        proto.end(token);
    }
}
