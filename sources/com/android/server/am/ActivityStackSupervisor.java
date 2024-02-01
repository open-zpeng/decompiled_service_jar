package com.android.server.am;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserManager;
import android.os.WorkSource;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.IApplicationToken;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.UiModeManagerService;
import com.android.server.am.ActivityStack;
import com.android.server.am.RecentTasks;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.wm.ConfigurationContainer;
import com.android.server.wm.PinnedStackWindowController;
import com.android.server.wm.WindowManagerService;
import com.xiaopeng.util.xpLogger;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
@SuppressLint({"all"})
/* loaded from: classes.dex */
public class ActivityStackSupervisor extends ConfigurationContainer implements DisplayManager.DisplayListener, RecentTasks.Callbacks {
    private static final ArrayMap<String, String> ACTION_TO_RUNTIME_PERMISSION = new ArrayMap<>();
    private static final int ACTIVITY_RESTRICTION_APPOP = 2;
    private static final int ACTIVITY_RESTRICTION_NONE = 0;
    private static final int ACTIVITY_RESTRICTION_PERMISSION = 1;
    static final boolean CREATE_IF_NEEDED = true;
    static final boolean DEFER_RESUME = true;
    static final int HANDLE_DISPLAY_ADDED = 105;
    static final int HANDLE_DISPLAY_CHANGED = 106;
    static final int HANDLE_DISPLAY_REMOVED = 107;
    static final int IDLE_NOW_MSG = 101;
    static final int IDLE_TIMEOUT = 10000;
    static final int IDLE_TIMEOUT_MSG = 100;
    static final int LAUNCH_TASK_BEHIND_COMPLETE = 112;
    static final int LAUNCH_TIMEOUT = 10000;
    static final int LAUNCH_TIMEOUT_MSG = 104;
    static final int MATCH_TASK_IN_STACKS_ONLY = 0;
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS = 1;
    static final int MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE = 2;
    private static final int MAX_TASK_IDS_PER_USER = 100000;
    static final boolean ON_TOP = true;
    static final boolean PAUSE_IMMEDIATELY = true;
    static final boolean PRESERVE_WINDOWS = true;
    static final boolean REMOVE_FROM_RECENTS = true;
    static final int REPORT_MULTI_WINDOW_MODE_CHANGED_MSG = 114;
    static final int REPORT_PIP_MODE_CHANGED_MSG = 115;
    static final int RESUME_TOP_ACTIVITY_MSG = 102;
    static final int SLEEP_TIMEOUT = 5000;
    static final int SLEEP_TIMEOUT_MSG = 103;
    private static final String TAG = "ActivityManager";
    private static final String TAG_FOCUS = "ActivityManager";
    private static final String TAG_IDLE = "ActivityManager";
    private static final String TAG_PAUSE = "ActivityManager";
    private static final String TAG_RECENTS = "ActivityManager";
    private static final String TAG_RELEASE = "ActivityManager";
    private static final String TAG_STACK = "ActivityManager";
    private static final String TAG_STATES = "ActivityManager";
    private static final String TAG_SWITCH = "ActivityManager";
    static final String TAG_TASKS = "ActivityManager";
    static final boolean VALIDATE_WAKE_LOCK_CALLER = false;
    private static final String VIRTUAL_DISPLAY_BASE_NAME = "ActivityViewVirtualDisplay";
    boolean inResumeTopActivity;
    boolean inResumeTopPhoneActivity;
    private ActivityMetricsLogger mActivityMetricsLogger;
    boolean mAppVisibilitiesChangedSinceLastPause;
    int mCurrentUser;
    private int mDeferResumeCount;
    DisplayManager mDisplayManager;
    private DisplayManagerInternal mDisplayManagerInternal;
    private boolean mDockedStackResizing;
    ActivityStack mFocusedStack;
    PowerManager.WakeLock mGoingToSleep;
    final ActivityStackSupervisorHandler mHandler;
    private boolean mHasPendingDockedBounds;
    ActivityStack mHomeStack;
    private boolean mInitialized;
    boolean mIsDockMinimized;
    private KeyguardController mKeyguardController;
    private ActivityStack mLastFocusedStack;
    private LaunchParamsController mLaunchParamsController;
    PowerManager.WakeLock mLaunchingActivity;
    final Looper mLooper;
    private Rect mPendingDockedBounds;
    private Rect mPendingTempDockedTaskBounds;
    private Rect mPendingTempDockedTaskInsetBounds;
    private Rect mPendingTempOtherTaskBounds;
    private Rect mPendingTempOtherTaskInsetBounds;
    ActivityStack mPhoneStack;
    Rect mPipModeChangedTargetStackBounds;
    private boolean mPowerHintSent;
    private PowerManager mPowerManager;
    RecentTasks mRecentTasks;
    private RunningTasks mRunningTasks;
    final ActivityManagerService mService;
    WindowManagerService mWindowManager;
    private final SparseIntArray mCurTaskIdForUser = new SparseIntArray(20);
    final ArrayList<ActivityRecord> mActivitiesWaitingForVisibleActivity = new ArrayList<>();
    private final ArrayList<WaitInfo> mWaitingForActivityVisible = new ArrayList<>();
    final ArrayList<WaitResult> mWaitingActivityLaunched = new ArrayList<>();
    final ArrayList<ActivityRecord> mStoppingActivities = new ArrayList<>();
    final ArrayList<ActivityRecord> mFinishingActivities = new ArrayList<>();
    final ArrayList<ActivityRecord> mGoingToSleepActivities = new ArrayList<>();
    final ArrayList<ActivityRecord> mMultiWindowModeChangedActivities = new ArrayList<>();
    final ArrayList<ActivityRecord> mPipModeChangedActivities = new ArrayList<>();
    final ArrayList<ActivityRecord> mNoAnimActivities = new ArrayList<>();
    final ArrayList<UserState> mStartingUsers = new ArrayList<>();
    boolean mUserLeaving = false;
    final ArrayList<ActivityManagerInternal.SleepToken> mSleepTokens = new ArrayList<>();
    SparseIntArray mUserStackInFront = new SparseIntArray(2);
    private final SparseArray<ActivityDisplay> mActivityDisplays = new SparseArray<>();
    private final SparseArray<IntArray> mDisplayAccessUIDs = new SparseArray<>();
    private final Rect tempRect = new Rect();
    private final ActivityOptions mTmpOptions = ActivityOptions.makeBasic();
    int mDefaultMinSizeOfResizeableTask = -1;
    private boolean mTaskLayersChanged = true;
    private final ArrayList<ActivityRecord> mTmpActivityList = new ArrayList<>();
    private final FindTaskResult mTmpFindTaskResult = new FindTaskResult();
    private SparseIntArray mTmpOrderedDisplayIds = new SparseIntArray();
    private final ArraySet<Integer> mResizingTasksDuringAnimation = new ArraySet<>();
    private boolean mAllowDockedStackResize = true;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface AnyTaskForIdMatchTaskMode {
    }

    static {
        ACTION_TO_RUNTIME_PERMISSION.put("android.media.action.IMAGE_CAPTURE", "android.permission.CAMERA");
        ACTION_TO_RUNTIME_PERMISSION.put("android.media.action.VIDEO_CAPTURE", "android.permission.CAMERA");
        ACTION_TO_RUNTIME_PERMISSION.put("android.intent.action.CALL", "android.permission.CALL_PHONE");
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public int getChildCount() {
        return this.mActivityDisplays.size();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public ActivityDisplay getChildAt(int index) {
        return this.mActivityDisplays.valueAt(index);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getParent() {
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Configuration getDisplayOverrideConfiguration(int displayId) {
        ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }
        return activityDisplay.getOverrideConfiguration();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisplayOverrideConfiguration(Configuration overrideConfiguration, int displayId) {
        ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }
        activityDisplay.onOverrideConfigurationChanged(overrideConfiguration);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canPlaceEntityOnDisplay(int displayId, boolean resizeable, int callingPid, int callingUid, ActivityInfo activityInfo) {
        if (displayId == 0) {
            return true;
        }
        if (this.mService.mSupportsMultiDisplay) {
            return (resizeable || displayConfigMatchesGlobal(displayId)) && isCallerAllowedToLaunchOnDisplay(callingPid, callingUid, displayId, activityInfo);
        }
        return false;
    }

    private boolean displayConfigMatchesGlobal(int displayId) {
        if (displayId == 0) {
            return true;
        }
        if (displayId == -1) {
            return false;
        }
        ActivityDisplay targetDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (targetDisplay == null) {
            throw new IllegalArgumentException("No display found with id: " + displayId);
        }
        return getConfiguration().equals(targetDisplay.getConfiguration());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class FindTaskResult {
        boolean matchedByRootAffinity;
        ActivityRecord r;

        FindTaskResult() {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class PendingActivityLaunch {
        final ProcessRecord callerApp;
        final ActivityRecord r;
        final ActivityRecord sourceRecord;
        final ActivityStack stack;
        final int startFlags;

        /* JADX INFO: Access modifiers changed from: package-private */
        public PendingActivityLaunch(ActivityRecord _r, ActivityRecord _sourceRecord, int _startFlags, ActivityStack _stack, ProcessRecord _callerApp) {
            this.r = _r;
            this.sourceRecord = _sourceRecord;
            this.startFlags = _startFlags;
            this.stack = _stack;
            this.callerApp = _callerApp;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void sendErrorResult(String message) {
            try {
                if (this.callerApp.thread != null) {
                    this.callerApp.thread.scheduleCrash(message);
                }
            } catch (RemoteException e) {
                Slog.e("ActivityManager", "Exception scheduling crash of failed activity launcher sourceRecord=" + this.sourceRecord, e);
            }
        }
    }

    public ActivityStackSupervisor(ActivityManagerService service, Looper looper) {
        this.mService = service;
        this.mLooper = looper;
        this.mHandler = new ActivityStackSupervisorHandler(looper);
    }

    public void initialize() {
        if (this.mInitialized) {
            return;
        }
        this.mInitialized = true;
        this.mRunningTasks = createRunningTasks();
        this.mActivityMetricsLogger = new ActivityMetricsLogger(this, this.mService.mContext, this.mHandler.getLooper());
        this.mKeyguardController = new KeyguardController(this.mService, this);
        this.mLaunchParamsController = new LaunchParamsController(this.mService);
        this.mLaunchParamsController.registerDefaultModifiers(this);
    }

    public ActivityMetricsLogger getActivityMetricsLogger() {
        return this.mActivityMetricsLogger;
    }

    public KeyguardController getKeyguardController() {
        return this.mKeyguardController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRecentTasks(RecentTasks recentTasks) {
        this.mRecentTasks = recentTasks;
        this.mRecentTasks.registerCallback(this);
    }

    @VisibleForTesting
    RunningTasks createRunningTasks() {
        return new RunningTasks();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initPowerManagement() {
        this.mPowerManager = (PowerManager) this.mService.mContext.getSystemService("power");
        this.mGoingToSleep = this.mPowerManager.newWakeLock(1, "ActivityManager-Sleep");
        this.mLaunchingActivity = this.mPowerManager.newWakeLock(1, "*launch*");
        this.mLaunchingActivity.setReferenceCounted(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowManager(WindowManagerService wm) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                this.mWindowManager = wm;
                getKeyguardController().setWindowManager(wm);
                this.mDisplayManager = (DisplayManager) this.mService.mContext.getSystemService("display");
                this.mDisplayManager.registerDisplayListener(this, null);
                this.mDisplayManagerInternal = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
                Display[] displays = this.mDisplayManager.getDisplays();
                for (int displayNdx = displays.length - 1; displayNdx >= 0; displayNdx--) {
                    Display display = displays[displayNdx];
                    ActivityDisplay activityDisplay = new ActivityDisplay(this, display);
                    this.mActivityDisplays.put(display.getDisplayId(), activityDisplay);
                    calculateDefaultMinimalSizeOfResizeableTasks(activityDisplay);
                }
                ActivityStack orCreateStack = getDefaultDisplay().getOrCreateStack(1, 2, true);
                this.mLastFocusedStack = orCreateStack;
                this.mFocusedStack = orCreateStack;
                this.mHomeStack = orCreateStack;
                this.mPhoneStack = getDefaultDisplay().getOrCreateStack(1, 1, true);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getFocusedStack() {
        return this.mFocusedStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusable(ConfigurationContainer container, boolean alwaysFocusable) {
        if (container.inSplitScreenPrimaryWindowingMode() && this.mIsDockMinimized) {
            return false;
        }
        return container.getWindowConfiguration().canReceiveKeys() || alwaysFocusable;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getLastStack() {
        return this.mLastFocusedStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocusedStack(ActivityStack stack) {
        return stack != null && stack == this.mFocusedStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPhoneStack(ActivityStack stack) {
        return stack != null && stack == this.mPhoneStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getPhoneStack() {
        return this.mPhoneStack;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFocusStackUnchecked(String reason, ActivityStack focusCandidate) {
        int stackId;
        if (!focusCandidate.isFocusable()) {
            focusCandidate = getNextFocusableStackLocked(focusCandidate, false);
        }
        if (focusCandidate != this.mFocusedStack) {
            this.mLastFocusedStack = this.mFocusedStack;
            this.mFocusedStack = focusCandidate;
            int i = this.mCurrentUser;
            if (this.mFocusedStack != null) {
                stackId = this.mFocusedStack.getStackId();
            } else {
                stackId = -1;
            }
            EventLogTags.writeAmFocusedStack(i, stackId, this.mLastFocusedStack != null ? this.mLastFocusedStack.getStackId() : -1, reason);
        }
        ActivityRecord r = topRunningActivityLocked();
        if ((this.mService.mBooting || !this.mService.mBooted) && r != null && r.idle) {
            checkFinishBootingLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveHomeStackToFront(String reason) {
        this.mHomeStack.moveToFront(reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveRecentsStackToFront(String reason) {
        ActivityStack recentsStack = getDefaultDisplay().getStack(0, 3);
        if (recentsStack != null) {
            recentsStack.moveToFront(reason);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean moveHomeStackTaskToTop(String reason) {
        this.mHomeStack.moveHomeStackTaskToTop();
        ActivityRecord top = getHomeActivity();
        if (top == null) {
            return false;
        }
        moveFocusableActivityStackToFrontLocked(top, reason);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumeHomeStackTask(ActivityRecord prev, String reason) {
        if (!this.mService.mBooting && !this.mService.mBooted) {
            return false;
        }
        this.mHomeStack.moveHomeStackTaskToTop();
        ActivityRecord r = getHomeActivity();
        String myReason = reason + " resumeHomeStackTask";
        if (r != null && !r.finishing) {
            moveFocusableActivityStackToFrontLocked(r, myReason);
            return resumeFocusedStackTopActivityLocked(this.mHomeStack, prev, null);
        }
        return this.mService.startHomeActivityLocked(this.mCurrentUser, myReason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord anyTaskForIdLocked(int id) {
        return anyTaskForIdLocked(id, 2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord anyTaskForIdLocked(int id, int matchMode) {
        return anyTaskForIdLocked(id, matchMode, null, false);
    }

    TaskRecord anyTaskForIdLocked(int id, int matchMode, ActivityOptions aOptions, boolean onTop) {
        ActivityStack launchStack;
        if (matchMode != 2 && aOptions != null) {
            throw new IllegalArgumentException("Should not specify activity options for non-restore lookup");
        }
        int numDisplays = this.mActivityDisplays.size();
        int displayNdx = 0;
        while (true) {
            int displayNdx2 = displayNdx;
            if (displayNdx2 < numDisplays) {
                ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx2);
                int stackNdx = display.getChildCount() - 1;
                while (true) {
                    int stackNdx2 = stackNdx;
                    if (stackNdx2 >= 0) {
                        ActivityStack stack = display.getChildAt(stackNdx2);
                        TaskRecord task = stack.taskForIdLocked(id);
                        if (task == null) {
                            stackNdx = stackNdx2 - 1;
                        } else if (aOptions != null && (launchStack = getLaunchStack(null, aOptions, task, onTop)) != null && stack != launchStack) {
                            int reparentMode = onTop ? 0 : 2;
                            task.reparent(launchStack, onTop, reparentMode, true, true, "anyTaskForIdLocked");
                            return task;
                        } else {
                            return task;
                        }
                    }
                }
            } else if (matchMode == 0) {
                return null;
            } else {
                if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                    Slog.v("ActivityManager", "Looking for task id=" + id + " in recents");
                }
                TaskRecord task2 = this.mRecentTasks.getTask(id);
                if (task2 == null) {
                    if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                        Slog.d("ActivityManager", "\tDidn't find task id=" + id + " in recents");
                    }
                    return null;
                } else if (matchMode == 1) {
                    return task2;
                } else {
                    if (!restoreRecentTaskLocked(task2, aOptions, onTop)) {
                        if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                            Slog.w("ActivityManager", "Couldn't restore task id=" + id + " found in recents");
                        }
                        return null;
                    }
                    if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                        Slog.w("ActivityManager", "Restored task id=" + id + " from in recents");
                    }
                    return task2;
                }
            }
            displayNdx = displayNdx2 + 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord isInAnyStackLocked(IBinder token) {
        int numDisplays = this.mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord r = stack.isInStackLocked(token);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private boolean taskTopActivityIsUser(TaskRecord task, int userId) {
        ActivityRecord activityRecord = task.getTopActivity();
        ActivityRecord resultTo = activityRecord != null ? activityRecord.resultTo : null;
        return (activityRecord != null && activityRecord.userId == userId) || (resultTo != null && resultTo.userId == userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void lockAllProfileTasks(int userId) {
        this.mWindowManager.deferSurfaceLayout();
        try {
            for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
                ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
                for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                    ActivityStack stack = display.getChildAt(stackNdx);
                    List<TaskRecord> tasks = stack.getAllTasks();
                    for (int taskNdx = tasks.size() - 1; taskNdx >= 0; taskNdx--) {
                        TaskRecord task = tasks.get(taskNdx);
                        if (taskTopActivityIsUser(task, userId)) {
                            this.mService.mTaskChangeNotificationController.notifyTaskProfileLocked(task.taskId, userId);
                        }
                    }
                }
            }
        } finally {
            this.mWindowManager.continueSurfaceLayout();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setNextTaskIdForUserLocked(int taskId, int userId) {
        int currentTaskId = this.mCurTaskIdForUser.get(userId, -1);
        if (taskId > currentTaskId) {
            this.mCurTaskIdForUser.put(userId, taskId);
        }
    }

    static int nextTaskIdForUser(int taskId, int userId) {
        int nextTaskId = taskId + 1;
        if (nextTaskId == (userId + 1) * 100000) {
            return nextTaskId - 100000;
        }
        return nextTaskId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getNextTaskIdForUserLocked(int userId) {
        int currentTaskId = this.mCurTaskIdForUser.get(userId, 100000 * userId);
        int candidateTaskId = nextTaskIdForUser(currentTaskId, userId);
        do {
            if (this.mRecentTasks.containsTaskId(candidateTaskId, userId) || anyTaskForIdLocked(candidateTaskId, 1) != null) {
                candidateTaskId = nextTaskIdForUser(candidateTaskId, userId);
            } else {
                this.mCurTaskIdForUser.put(userId, candidateTaskId);
                return candidateTaskId;
            }
        } while (candidateTaskId != currentTaskId);
        throw new IllegalStateException("Cannot get an available task id. Reached limit of 100000 running tasks per user.");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getResumedActivityLocked() {
        ActivityStack stack = this.mFocusedStack;
        if (stack == null) {
            return null;
        }
        ActivityRecord resumedActivity = stack.getResumedActivity();
        if (resumedActivity == null || resumedActivity.app == null) {
            ActivityRecord resumedActivity2 = stack.mPausingActivity;
            if (resumedActivity2 == null || resumedActivity2.app == null) {
                return stack.topRunningActivityLocked();
            }
            return resumedActivity2;
        }
        return resumedActivity;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean attachApplicationLocked(ProcessRecord app) throws RemoteException {
        String processName = app.processName;
        boolean didSomething = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (isFocusedStack(stack) || isPhoneStack(stack)) {
                    stack.getAllRunningVisibleActivitiesLocked(this.mTmpActivityList);
                    ActivityRecord top = stack.topRunningActivityLocked();
                    int size = this.mTmpActivityList.size();
                    boolean didSomething2 = didSomething;
                    for (int i = 0; i < size; i++) {
                        ActivityRecord activity = this.mTmpActivityList.get(i);
                        if (activity.app == null && app.uid == activity.info.applicationInfo.uid && processName.equals(activity.processName)) {
                            try {
                                if (realStartActivityLocked(activity, app, top == activity, true)) {
                                    didSomething2 = true;
                                }
                            } catch (RemoteException e) {
                                Slog.w("ActivityManager", "Exception in new application when starting activity " + top.intent.getComponent().flattenToShortString(), e);
                                throw e;
                            }
                        }
                    }
                    didSomething = didSomething2;
                }
            }
        }
        if (!didSomething) {
            ensureActivitiesVisibleLocked(null, 0, false);
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allResumedActivitiesIdle() {
        ActivityRecord resumedActivity;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (isFocusedStack(stack) && stack.numActivities() != 0 && ((resumedActivity = stack.getResumedActivity()) == null || !resumedActivity.idle)) {
                    if (ActivityManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityManager", "allResumedActivitiesIdle: stack=" + stack.mStackId + " " + resumedActivity + " not idle");
                        return false;
                    } else {
                        return false;
                    }
                }
            }
        }
        sendPowerHintForLaunchEndIfNeeded();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allResumedActivitiesComplete() {
        ActivityRecord r;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (isFocusedStack(stack) && (r = stack.getResumedActivity()) != null && !r.isState(ActivityStack.ActivityState.RESUMED)) {
                    return false;
                }
            }
        }
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.d("ActivityManager", "allResumedActivitiesComplete: mLastFocusedStack changing from=" + this.mLastFocusedStack + " to=" + this.mFocusedStack);
        }
        this.mLastFocusedStack = this.mFocusedStack;
        return true;
    }

    private boolean allResumedActivitiesVisible() {
        boolean foundResumed = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord r = stack.getResumedActivity();
                if (r != null) {
                    if (!r.nowVisible || this.mActivitiesWaitingForVisibleActivity.contains(r)) {
                        return false;
                    }
                    foundResumed = true;
                }
            }
        }
        return foundResumed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean pauseBackStacks(boolean userLeaving, ActivityRecord resuming, boolean dontWait) {
        boolean someActivityPaused = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (!isFocusedStack(stack) && stack.getResumedActivity() != null) {
                    if (ActivityManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityManager", "pauseBackStacks: stack=" + stack + " mResumedActivity=" + stack.getResumedActivity());
                    }
                    someActivityPaused |= stack.startPausingLocked(userLeaving, false, resuming, dontWait);
                }
            }
        }
        return someActivityPaused;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allPausedActivitiesComplete() {
        boolean pausing = true;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord r = stack.mPausingActivity;
                if (r != null && !r.isState(ActivityStack.ActivityState.PAUSED, ActivityStack.ActivityState.STOPPED, ActivityStack.ActivityState.STOPPING)) {
                    if (ActivityManagerDebugConfig.DEBUG_STATES) {
                        Slog.d("ActivityManager", "allPausedActivitiesComplete: r=" + r + " state=" + r.getState());
                        pausing = false;
                    } else {
                        return false;
                    }
                }
            }
        }
        return pausing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelInitializingActivities() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.cancelInitializingActivities();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void waitActivityVisible(ComponentName name, WaitResult result, long startTimeMs) {
        WaitInfo waitInfo = new WaitInfo(name, result, startTimeMs);
        this.mWaitingForActivityVisible.add(waitInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cleanupActivity(ActivityRecord r) {
        this.mFinishingActivities.remove(r);
        this.mActivitiesWaitingForVisibleActivity.remove(r);
        for (int i = this.mWaitingForActivityVisible.size() - 1; i >= 0; i--) {
            if (this.mWaitingForActivityVisible.get(i).matches(r.realActivity)) {
                this.mWaitingForActivityVisible.remove(i);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportActivityVisibleLocked(ActivityRecord r) {
        sendWaitingVisibleReportLocked(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendWaitingVisibleReportLocked(ActivityRecord r) {
        boolean changed = false;
        for (int i = this.mWaitingForActivityVisible.size() - 1; i >= 0; i--) {
            WaitInfo w = this.mWaitingForActivityVisible.get(i);
            if (w.matches(r.realActivity)) {
                WaitResult result = w.getResult();
                changed = true;
                result.timeout = false;
                result.who = w.getComponent();
                result.totalTime = SystemClock.uptimeMillis() - w.getStartTime();
                this.mWaitingForActivityVisible.remove(w);
            }
        }
        if (changed) {
            this.mService.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportWaitingActivityLaunchedIfNeeded(ActivityRecord r, int result) {
        if (this.mWaitingActivityLaunched.isEmpty()) {
            return;
        }
        if (result != 3 && result != 2) {
            return;
        }
        boolean changed = false;
        for (int i = this.mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            WaitResult w = this.mWaitingActivityLaunched.remove(i);
            if (w.who == null) {
                changed = true;
                w.result = result;
                if (result == 3) {
                    w.who = r.realActivity;
                }
            }
        }
        if (changed) {
            this.mService.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportActivityLaunchedLocked(boolean timeout, ActivityRecord r, long totalTime) {
        boolean changed = false;
        for (int i = this.mWaitingActivityLaunched.size() - 1; i >= 0; i--) {
            WaitResult w = this.mWaitingActivityLaunched.remove(i);
            if (w.who == null) {
                changed = true;
                w.timeout = timeout;
                if (r != null) {
                    w.who = new ComponentName(r.info.packageName, r.info.name);
                }
                w.totalTime = totalTime;
            }
        }
        if (changed) {
            this.mService.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivityLocked() {
        return topRunningActivityLocked(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord topRunningActivityLocked(boolean considerKeyguardState) {
        ActivityStack topStack;
        ActivityRecord topActivity;
        ActivityStack focusedStack = this.mFocusedStack;
        ActivityRecord r = focusedStack.topRunningActivityLocked();
        if (r != null && isValidTopRunningActivity(r, considerKeyguardState)) {
            return r;
        }
        this.mWindowManager.getDisplaysInFocusOrder(this.mTmpOrderedDisplayIds);
        for (int i = this.mTmpOrderedDisplayIds.size() - 1; i >= 0; i--) {
            int displayId = this.mTmpOrderedDisplayIds.get(i);
            ActivityDisplay display = this.mActivityDisplays.get(displayId);
            if (display != null && (topStack = display.getTopStack()) != null && topStack.isFocusable() && topStack != focusedStack && (topActivity = topStack.topRunningActivityLocked()) != null && isValidTopRunningActivity(topActivity, considerKeyguardState)) {
                return topActivity;
            }
        }
        return null;
    }

    private boolean isValidTopRunningActivity(ActivityRecord record, boolean considerKeyguardState) {
        if (!considerKeyguardState) {
            return true;
        }
        boolean keyguardLocked = getKeyguardController().isKeyguardLocked();
        if (!keyguardLocked) {
            return true;
        }
        return record.canShowWhenLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public void getRunningTasks(int maxNum, List<ActivityManager.RunningTaskInfo> list, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode, int callingUid, boolean allowed) {
        this.mRunningTasks.getTasks(maxNum, list, ignoreActivityType, ignoreWindowingMode, this.mActivityDisplays, callingUid, allowed);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityInfo resolveActivity(Intent intent, ResolveInfo rInfo, int startFlags, ProfilerInfo profilerInfo) {
        ActivityInfo aInfo = rInfo != null ? rInfo.activityInfo : null;
        if (aInfo != null) {
            intent.setComponent(new ComponentName(aInfo.applicationInfo.packageName, aInfo.name));
            if (!aInfo.processName.equals("system")) {
                if ((startFlags & 2) != 0) {
                    this.mService.setDebugApp(aInfo.processName, true, false);
                }
                if ((startFlags & 8) != 0) {
                    this.mService.setNativeDebuggingAppLocked(aInfo.applicationInfo, aInfo.processName);
                }
                if ((startFlags & 4) != 0) {
                    this.mService.setTrackAllocationApp(aInfo.applicationInfo, aInfo.processName);
                }
                if (profilerInfo != null) {
                    this.mService.setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo);
                }
            }
            String intentLaunchToken = intent.getLaunchToken();
            if (aInfo.launchToken == null && intentLaunchToken != null) {
                aInfo.launchToken = intentLaunchToken;
            }
        }
        return aInfo;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int userId, int flags, int filterCallingUid) {
        ResolveInfo resolveIntent;
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                Trace.traceBegin(64L, "resolveIntent");
                int modifiedFlags = flags | 65536 | 1024;
                if (intent.isWebIntent() || (intent.getFlags() & 2048) != 0) {
                    modifiedFlags |= DumpState.DUMP_VOLUMES;
                }
                int modifiedFlags2 = modifiedFlags;
                long token = Binder.clearCallingIdentity();
                resolveIntent = this.mService.getPackageManagerInternalLocked().resolveIntent(intent, resolvedType, modifiedFlags2, userId, true, filterCallingUid);
                Binder.restoreCallingIdentity(token);
                Trace.traceEnd(64L);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        return resolveIntent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags, ProfilerInfo profilerInfo, int userId, int filterCallingUid) {
        ResolveInfo rInfo = resolveIntent(intent, resolvedType, userId, 0, filterCallingUid);
        return resolveActivity(intent, rInfo, startFlags, profilerInfo);
    }

    /* JADX WARN: Removed duplicated region for block: B:107:0x02cb A[Catch: all -> 0x03cc, RemoteException -> 0x03d2, TRY_ENTER, TryCatch #18 {RemoteException -> 0x03d2, all -> 0x03cc, blocks: (B:99:0x028d, B:108:0x02cf, B:107:0x02cb), top: B:215:0x028d }] */
    /* JADX WARN: Removed duplicated region for block: B:137:0x0360  */
    /* JADX WARN: Removed duplicated region for block: B:139:0x037d  */
    /* JADX WARN: Removed duplicated region for block: B:144:0x038b  */
    /* JADX WARN: Removed duplicated region for block: B:148:0x03b4  */
    /* JADX WARN: Removed duplicated region for block: B:151:0x03c1  */
    /* JADX WARN: Removed duplicated region for block: B:181:0x0417 A[Catch: all -> 0x045f, TRY_LEAVE, TryCatch #20 {all -> 0x045f, blocks: (B:179:0x0413, B:181:0x0417, B:185:0x0457, B:186:0x045e, B:173:0x0402, B:174:0x0407), top: B:200:0x0402 }] */
    /* JADX WARN: Removed duplicated region for block: B:184:0x0454  */
    /* JADX WARN: Removed duplicated region for block: B:209:0x02e3 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:217:0x02b2 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:219:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    final boolean realStartActivityLocked(com.android.server.am.ActivityRecord r39, com.android.server.am.ProcessRecord r40, boolean r41, boolean r42) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 1130
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStackSupervisor.realStartActivityLocked(com.android.server.am.ActivityRecord, com.android.server.am.ProcessRecord, boolean, boolean):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean ensureVisibilityAndConfig(ActivityRecord starting, int mDisplayId, boolean markFrozenIfConfigChanged, boolean deferResume) {
        IApplicationToken.Stub stub = null;
        ensureActivitiesVisibleLocked(null, 0, false, false);
        int displayId = mDisplayId != -1 ? mDisplayId : 0;
        WindowManagerService windowManagerService = this.mWindowManager;
        Configuration displayOverrideConfiguration = getDisplayOverrideConfiguration(displayId);
        if (starting != null && starting.mayFreezeScreenLocked(starting.app)) {
            stub = starting.appToken;
        }
        Configuration config = windowManagerService.updateOrientationFromAppTokens(displayOverrideConfiguration, stub, displayId, true);
        if (starting != null && markFrozenIfConfigChanged && config != null) {
            starting.frozenBeforeDestroy = true;
        }
        return this.mService.updateDisplayOverrideConfigurationLocked(config, starting, deferResume, displayId);
    }

    private void logIfTransactionTooLarge(Intent intent, Bundle icicle) {
        Bundle extras;
        int extrasSize = 0;
        if (intent != null && (extras = intent.getExtras()) != null) {
            extrasSize = extras.getSize();
        }
        int icicleSize = icicle == null ? 0 : icicle.getSize();
        if (extrasSize + icicleSize > 200000) {
            Slog.e("ActivityManager", "Transaction too large, intent: " + intent + ", extras size: " + extrasSize + ", icicle size: " + icicleSize);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startSpecificActivityLocked(ActivityRecord r, boolean andResume, boolean checkConfig) {
        ProcessRecord app = this.mService.getProcessRecordLocked(r.processName, r.info.applicationInfo.uid, true);
        if (app != null && app.thread != null) {
            try {
                if ((r.info.flags & 1) == 0 || !PackageManagerService.PLATFORM_PACKAGE_NAME.equals(r.info.packageName)) {
                    app.addPackage(r.info.packageName, r.info.applicationInfo.longVersionCode, this.mService.mProcessStats);
                }
                try {
                    realStartActivityLocked(r, app, andResume, checkConfig);
                    return;
                } catch (RemoteException e) {
                    e = e;
                    Slog.w("ActivityManager", "Exception when starting activity " + r.intent.getComponent().flattenToShortString(), e);
                    this.mService.startProcessLocked(r.processName, r.info.applicationInfo, true, 0, "activity", r.intent.getComponent(), false, false, true);
                }
            } catch (RemoteException e2) {
                e = e2;
            }
        }
        this.mService.startProcessLocked(r.processName, r.info.applicationInfo, true, 0, "activity", r.intent.getComponent(), false, false, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendPowerHintForLaunchStartIfNeeded(boolean forceSend, ActivityRecord targetActivity) {
        boolean sendHint = forceSend;
        if (!sendHint) {
            ActivityRecord resumedActivity = getResumedActivityLocked();
            sendHint = resumedActivity == null || resumedActivity.app == null || !resumedActivity.app.equals(targetActivity.app);
        }
        if (sendHint && this.mService.mLocalPowerManager != null) {
            this.mService.mLocalPowerManager.powerHint(8, 1);
            this.mPowerHintSent = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendPowerHintForLaunchEndIfNeeded() {
        if (this.mPowerHintSent && this.mService.mLocalPowerManager != null) {
            this.mService.mLocalPowerManager.powerHint(8, 0);
            this.mPowerHintSent = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkStartAnyActivityPermission(Intent intent, ActivityInfo aInfo, String resultWho, int requestCode, int callingPid, int callingUid, String callingPackage, boolean ignoreTargetSecurity, boolean launchingInTask, ProcessRecord callerApp, ActivityRecord resultRecord, ActivityStack resultStack) {
        String msg;
        boolean isCallerRecents = this.mService.getRecentTasks() != null && this.mService.getRecentTasks().isCallerRecents(callingUid);
        int startAnyPerm = this.mService.checkPermission("android.permission.START_ANY_ACTIVITY", callingPid, callingUid);
        if (startAnyPerm == 0 || (isCallerRecents && launchingInTask)) {
            return true;
        }
        int componentRestriction = getComponentRestrictionForCallingPackage(aInfo, callingPackage, callingPid, callingUid, ignoreTargetSecurity);
        int actionRestriction = getActionRestrictionForCallingPackage(intent.getAction(), callingPackage, callingPid, callingUid);
        if (componentRestriction == 1 || actionRestriction == 1) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode, 0, null);
            }
            if (actionRestriction == 1) {
                msg = "Permission Denial: starting " + intent.toString() + " from " + callerApp + " (pid=" + callingPid + ", uid=" + callingUid + ") with revoked permission " + ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction());
            } else if (!aInfo.exported) {
                msg = "Permission Denial: starting " + intent.toString() + " from " + callerApp + " (pid=" + callingPid + ", uid=" + callingUid + ") not exported from uid " + aInfo.applicationInfo.uid;
            } else {
                msg = "Permission Denial: starting " + intent.toString() + " from " + callerApp + " (pid=" + callingPid + ", uid=" + callingUid + ") requires " + aInfo.permission;
            }
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        } else if (actionRestriction == 2) {
            String message = "Appop Denial: starting " + intent.toString() + " from " + callerApp + " (pid=" + callingPid + ", uid=" + callingUid + ") requires " + AppOpsManager.permissionToOp(ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction()));
            Slog.w("ActivityManager", message);
            return false;
        } else if (componentRestriction == 2) {
            String message2 = "Appop Denial: starting " + intent.toString() + " from " + callerApp + " (pid=" + callingPid + ", uid=" + callingUid + ") requires appop " + AppOpsManager.permissionToOp(aInfo.permission);
            Slog.w("ActivityManager", message2);
            return false;
        } else {
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCallerAllowedToLaunchOnDisplay(int callingPid, int callingUid, int launchDisplayId, ActivityInfo aInfo) {
        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
            Slog.d("ActivityManager", "Launch on display check: displayId=" + launchDisplayId + " callingPid=" + callingPid + " callingUid=" + callingUid);
        }
        if (callingPid == -1 && callingUid == -1) {
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityManager", "Launch on display check: no caller info, skip check");
            }
            return true;
        }
        ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(launchDisplayId);
        if (activityDisplay == null) {
            Slog.w("ActivityManager", "Launch on display check: display not found");
            return false;
        }
        int startAnyPerm = this.mService.checkPermission("android.permission.INTERNAL_SYSTEM_WINDOW", callingPid, callingUid);
        if (startAnyPerm == 0) {
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityManager", "Launch on display check: allow launch any on display");
            }
            return true;
        }
        boolean uidPresentOnDisplay = activityDisplay.isUidPresent(callingUid);
        int displayOwnerUid = activityDisplay.mDisplay.getOwnerUid();
        if (activityDisplay.mDisplay.getType() == 5 && displayOwnerUid != 1000 && displayOwnerUid != aInfo.applicationInfo.uid) {
            if ((aInfo.flags & Integer.MIN_VALUE) != 0) {
                if (this.mService.checkPermission("android.permission.ACTIVITY_EMBEDDING", callingPid, callingUid) == -1 && !uidPresentOnDisplay) {
                    if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                        Slog.d("ActivityManager", "Launch on display check: disallow activity embedding without permission.");
                    }
                    return false;
                }
            } else {
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityManager", "Launch on display check: disallow launch on virtual display for not-embedded activity.");
                }
                return false;
            }
        }
        if (!activityDisplay.isPrivate()) {
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityManager", "Launch on display check: allow launch on public display");
            }
            return true;
        } else if (displayOwnerUid == callingUid) {
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityManager", "Launch on display check: allow launch for owner of the display");
            }
            return true;
        } else if (uidPresentOnDisplay) {
            if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityManager", "Launch on display check: allow launch for caller present on the display");
            }
            return true;
        } else {
            Slog.w("ActivityManager", "Launch on display check: denied");
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateUIDsPresentOnDisplay() {
        this.mDisplayAccessUIDs.clear();
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.valueAt(displayNdx);
            if (activityDisplay.isPrivate()) {
                this.mDisplayAccessUIDs.append(activityDisplay.mDisplayId, activityDisplay.getPresentUIDs());
            }
        }
        this.mDisplayManagerInternal.setDisplayAccessUIDs(this.mDisplayAccessUIDs);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserInfo getUserInfo(int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            return UserManager.get(this.mService.mContext).getUserInfo(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private int getComponentRestrictionForCallingPackage(ActivityInfo activityInfo, String callingPackage, int callingPid, int callingUid, boolean ignoreTargetSecurity) {
        int opCode;
        if (ignoreTargetSecurity || this.mService.checkComponentPermission(activityInfo.permission, callingPid, callingUid, activityInfo.applicationInfo.uid, activityInfo.exported) != -1) {
            return (activityInfo.permission == null || (opCode = AppOpsManager.permissionToOpCode(activityInfo.permission)) == -1 || this.mService.mAppOpsService.noteOperation(opCode, callingUid, callingPackage) == 0 || ignoreTargetSecurity) ? 0 : 2;
        }
        return 1;
    }

    private int getActionRestrictionForCallingPackage(String action, String callingPackage, int callingPid, int callingUid) {
        String permission;
        if (action == null || (permission = ACTION_TO_RUNTIME_PERMISSION.get(action)) == null) {
            return 0;
        }
        try {
            PackageInfo packageInfo = this.mService.mContext.getPackageManager().getPackageInfo(callingPackage, 4096);
            if (!ArrayUtils.contains(packageInfo.requestedPermissions, permission)) {
                return 0;
            }
            if (this.mService.checkPermission(permission, callingPid, callingUid) == -1) {
                return 1;
            }
            int opCode = AppOpsManager.permissionToOpCode(permission);
            if (opCode == -1 || this.mService.mAppOpsService.noteOperation(opCode, callingUid, callingPackage) == 0) {
                return 0;
            }
            return 2;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i("ActivityManager", "Cannot find package info for " + callingPackage);
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLaunchSource(int uid) {
        this.mLaunchingActivity.setWorkSource(new WorkSource(uid));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void acquireLaunchWakelock() {
        this.mLaunchingActivity.acquire();
        if (!this.mHandler.hasMessages(104)) {
            this.mHandler.sendEmptyMessageDelayed(104, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    @GuardedBy("mService")
    private boolean checkFinishBootingLocked() {
        boolean booting = this.mService.mBooting;
        boolean enableScreen = false;
        this.mService.mBooting = false;
        if (!this.mService.mBooted) {
            this.mService.mBooted = true;
            enableScreen = true;
        }
        if (booting || enableScreen) {
            this.mService.postFinishBooting(booting, enableScreen);
        }
        return booting;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mService")
    public final ActivityRecord activityIdleInternalLocked(IBinder token, boolean fromTimeout, boolean processPausingActivities, Configuration config) {
        int i;
        if (ActivityManagerDebugConfig.DEBUG_ALL) {
            Slog.v("ActivityManager", "Activity idle: " + token);
        }
        ArrayList<ActivityRecord> finishes = null;
        ArrayList<UserState> startingUsers = null;
        boolean booting = false;
        ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r != null) {
            if (ActivityManagerDebugConfig.DEBUG_IDLE) {
                Slog.d("ActivityManager", "activityIdleInternalLocked: Callers=" + Debug.getCallers(4));
            }
            this.mHandler.removeMessages(100, r);
            r.finishLaunchTickingLocked();
            if (fromTimeout) {
                reportActivityLaunchedLocked(fromTimeout, r, -1L);
            }
            if (config != null) {
                r.setLastReportedGlobalConfiguration(config);
            }
            r.idle = true;
            if (isFocusedStack(r.getStack()) || fromTimeout) {
                booting = checkFinishBootingLocked();
            }
        }
        int i2 = 0;
        if (allResumedActivitiesIdle()) {
            if (r != null) {
                this.mService.scheduleAppGcsLocked();
            }
            if (this.mLaunchingActivity.isHeld()) {
                this.mHandler.removeMessages(104);
                this.mLaunchingActivity.release();
            }
            ensureActivitiesVisibleLocked(null, 0, false);
        }
        ArrayList<ActivityRecord> stops = processStoppingActivitiesLocked(r, true, processPausingActivities);
        int NS = stops != null ? stops.size() : 0;
        int NF = this.mFinishingActivities.size();
        if (NF > 0) {
            finishes = new ArrayList<>(this.mFinishingActivities);
            this.mFinishingActivities.clear();
        }
        if (this.mStartingUsers.size() > 0) {
            startingUsers = new ArrayList<>(this.mStartingUsers);
            this.mStartingUsers.clear();
        }
        ActivityRecord r2 = r;
        int i3 = 0;
        while (i3 < NS) {
            r2 = stops.get(i3);
            ActivityStack stack = r2.getStack();
            if (stack == null) {
                i = i2;
            } else if (r2.finishing) {
                i = 0;
                stack.finishCurrentActivityLocked(r2, 0, false, "activityIdleInternalLocked");
            } else {
                i = 0;
                stack.stopActivityLocked(r2);
            }
            i3++;
            i2 = i;
        }
        boolean activityRemoved = false;
        for (int i4 = i2; i4 < NF; i4++) {
            r2 = finishes.get(i4);
            ActivityStack stack2 = r2.getStack();
            if (stack2 != null) {
                activityRemoved |= stack2.destroyActivityLocked(r2, true, "finish-idle");
            }
        }
        if (!booting && startingUsers != null) {
            int i5 = 0;
            while (true) {
                int i6 = i5;
                if (i6 >= startingUsers.size()) {
                    break;
                }
                this.mService.mUserController.finishUserSwitch(startingUsers.get(i6));
                i5 = i6 + 1;
            }
        }
        this.mService.trimApplications();
        if (activityRemoved) {
            resumeFocusedStackTopActivityLocked();
        }
        return r2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handleAppDiedLocked(ProcessRecord app) {
        boolean hasVisibleActivities = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                hasVisibleActivities |= stack.handleAppDiedLocked(app);
            }
        }
        return hasVisibleActivities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void closeSystemDialogsLocked() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.closeSystemDialogsLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUserLocked(int userId) {
        this.mUserStackInFront.delete(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateUserStackLocked(int userId, ActivityStack stack) {
        if (userId != this.mCurrentUser) {
            this.mUserStackInFront.put(userId, stack != null ? stack.getStackId() : this.mHomeStack.mStackId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean finishDisabledPackageActivitiesLocked(String packageName, Set<String> filterByClasses, boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (stack.finishDisabledPackageActivitiesLocked(packageName, filterByClasses, doit, evenPersistent, userId)) {
                    didSomething = true;
                }
            }
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updatePreviousProcessLocked(ActivityRecord r) {
        ProcessRecord fgApp = null;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            int stackNdx = display.getChildCount() - 1;
            while (true) {
                if (stackNdx >= 0) {
                    ActivityStack stack = display.getChildAt(stackNdx);
                    if (isFocusedStack(stack)) {
                        ActivityRecord resumedActivity = stack.getResumedActivity();
                        if (resumedActivity != null) {
                            fgApp = resumedActivity.app;
                        } else if (stack.mPausingActivity != null) {
                            fgApp = stack.mPausingActivity.app;
                        }
                    } else {
                        stackNdx--;
                    }
                }
            }
        }
        if (r.app != null && fgApp != null && r.app != fgApp && r.lastVisibleTime > this.mService.mPreviousProcessVisibleTime && r.app != this.mService.mHomeProcess) {
            this.mService.mPreviousProcess = r.app;
            this.mService.mPreviousProcessVisibleTime = r.lastVisibleTime;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumeFocusedStackTopActivityLocked() {
        return resumeFocusedStackTopActivityLocked(null, null, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumeFocusedStackTopActivityLocked(ActivityStack targetStack, ActivityRecord target, ActivityOptions targetOptions) {
        if (readyToResume()) {
            if (targetStack != null && isFocusedStack(targetStack)) {
                return targetStack.resumeTopActivityUncheckedLocked(target, targetOptions);
            }
            ActivityRecord r = this.mFocusedStack.topRunningActivityLocked();
            if (r == null || !r.isState(ActivityStack.ActivityState.RESUMED)) {
                this.mFocusedStack.resumeTopActivityUncheckedLocked(null, null);
            } else if (r.isState(ActivityStack.ActivityState.RESUMED)) {
                this.mFocusedStack.executeAppTransition(targetOptions);
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumePhoneStackTopActivityLocked() {
        return resumePhoneStackTopActivityLocked(null, null, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean resumePhoneStackTopActivityLocked(ActivityStack targetStack, ActivityRecord target, ActivityOptions targetOptions) {
        if (targetStack != null && isPhoneStack(targetStack)) {
            return targetStack.resumeTopPhoneActivityUncheckedLocked(target, targetOptions);
        }
        ActivityRecord r = this.mPhoneStack.topRunningActivityLocked();
        if (r == null || !r.isState(ActivityStack.ActivityState.RESUMED)) {
            this.mPhoneStack.resumeTopPhoneActivityUncheckedLocked(null, null);
            return false;
        } else if (r.isState(ActivityStack.ActivityState.RESUMED)) {
            this.mPhoneStack.executeAppTransition(targetOptions);
            return false;
        } else {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateActivityApplicationInfoLocked(ApplicationInfo aInfo) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.updateActivityApplicationInfoLocked(aInfo);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord finishTopCrashedActivitiesLocked(ProcessRecord app, String reason) {
        TaskRecord finishedTask = null;
        ActivityStack focusedStack = getFocusedStack();
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = 0; stackNdx < display.getChildCount(); stackNdx++) {
                ActivityStack stack = display.getChildAt(stackNdx);
                TaskRecord t = stack.finishTopCrashedActivityLocked(app, reason);
                if (stack == focusedStack || finishedTask == null) {
                    finishedTask = t;
                }
            }
        }
        return finishedTask;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishVoiceTask(IVoiceInteractionSession session) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            int numStacks = display.getChildCount();
            for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.finishVoiceTask(session);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void findTaskToMoveToFront(TaskRecord task, int flags, ActivityOptions options, String reason, boolean forceNonResizeable) {
        AppTimeTracker appTimeTracker;
        ActivityStack currentStack = task.getStack();
        if (currentStack == null) {
            Slog.e("ActivityManager", "findTaskToMoveToFront: can't move task=" + task + " to front. Stack is null");
            return;
        }
        if ((flags & 2) == 0) {
            this.mUserLeaving = true;
        }
        ActivityRecord prev = topRunningActivityLocked();
        if ((flags & 1) != 0 || (prev != null && prev.isActivityTypeRecents())) {
            moveHomeStackToFront("findTaskToMoveToFront");
        }
        if (task.isResizeable() && canUseActivityOptionsLaunchBounds(options)) {
            Rect bounds = options.getLaunchBounds();
            task.updateOverrideConfiguration(bounds);
            ActivityStack stack = getLaunchStack(null, options, task, true);
            if (stack != currentStack) {
                task.reparent(stack, true, 1, false, true, "findTaskToMoveToFront");
                stack = currentStack;
            }
            if (stack.resizeStackWithLaunchBounds()) {
                resizeStackLocked(stack, bounds, null, null, false, true, false);
            } else {
                task.resizeWindowContainer();
            }
        }
        ActivityRecord r = task.getTopActivity();
        if (r == null) {
            appTimeTracker = null;
        } else {
            appTimeTracker = r.appTimeTracker;
        }
        currentStack.moveTaskToFrontLocked(task, false, options, appTimeTracker, reason);
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.d("ActivityManager", "findTaskToMoveToFront: moved to front of stack=" + currentStack);
        }
        handleNonResizableTaskIfNeeded(task, 0, 0, currentStack, forceNonResizeable);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canUseActivityOptionsLaunchBounds(ActivityOptions options) {
        if (options == null || options.getLaunchBounds() == null) {
            return false;
        }
        return (this.mService.mSupportsPictureInPicture && options.getLaunchWindowingMode() == 2) || this.mService.mSupportsFreeformWindowManagement;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public LaunchParamsController getLaunchParamsController() {
        return this.mLaunchParamsController;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public <T extends ActivityStack> T getStack(int stackId) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            T stack = (T) this.mActivityDisplays.valueAt(i).getStack(stackId);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    private <T extends ActivityStack> T getStack(int windowingMode, int activityType) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            T stack = (T) this.mActivityDisplays.valueAt(i).getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int resolveActivityType(ActivityRecord r, ActivityOptions options, TaskRecord task) {
        int activityType = r != null ? r.getActivityType() : 0;
        if (activityType == 0 && task != null) {
            activityType = task.getActivityType();
        }
        if (activityType != 0) {
            return activityType;
        }
        if (options != null) {
            activityType = options.getLaunchActivityType();
        }
        if (activityType != 0) {
            return activityType;
        }
        return 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getLaunchStack(ActivityRecord r, ActivityOptions options, TaskRecord candidateTask, boolean onTop) {
        return (T) getLaunchStack(r, options, candidateTask, onTop, -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public <T extends ActivityStack> T getLaunchStack(ActivityRecord r, ActivityOptions options, TaskRecord candidateTask, boolean onTop, int candidateDisplayId) {
        boolean z;
        ActivityDisplay display;
        T stack;
        int taskId = -1;
        int displayId = -1;
        if (options != null) {
            taskId = options.getLaunchTaskId();
            displayId = options.getLaunchDisplayId();
        }
        int taskId2 = taskId;
        if (taskId2 != -1) {
            options.setLaunchTaskId(-1);
            z = onTop;
            TaskRecord task = anyTaskForIdLocked(taskId2, 2, options, z);
            options.setLaunchTaskId(taskId2);
            if (task != null) {
                return (T) task.getStack();
            }
        } else {
            z = onTop;
        }
        int activityType = resolveActivityType(r, options, candidateTask);
        T stack2 = null;
        if (displayId == -1) {
            displayId = candidateDisplayId;
        }
        int displayId2 = displayId;
        if (displayId2 != -1 && canLaunchOnDisplay(r, displayId2)) {
            if (r != null && (stack2 = (T) getValidLaunchStackOnDisplay(displayId2, r)) != null) {
                return stack2;
            }
            ActivityDisplay display2 = getActivityDisplayOrCreateLocked(displayId2);
            if (display2 != null && (stack = (T) display2.getOrCreateStack(r, options, candidateTask, activityType, z)) != null) {
                return stack;
            }
        }
        ActivityDisplay display3 = null;
        ActivityStack stack3 = candidateTask != null ? candidateTask.getStack() : null;
        if (stack3 == null && r != null) {
            stack3 = r.getStack();
        }
        T stack4 = (T) stack3;
        if (stack4 != null && (display3 = stack4.getDisplay()) != null && canLaunchOnDisplay(r, display3.mDisplayId)) {
            int windowingMode = display3.resolveWindowingMode(r, options, candidateTask, activityType);
            if (stack4.isCompatible(windowingMode, activityType)) {
                return stack4;
            }
            if (windowingMode == 4 && display3.getSplitScreenPrimaryStack() == stack4 && candidateTask == stack4.topTask()) {
                return stack4;
            }
        }
        if (display3 == null || !canLaunchOnDisplay(r, display3.mDisplayId) || (activityType != 1 && activityType != 0)) {
            ActivityDisplay display4 = getDefaultDisplay();
            display = display4;
        } else {
            display = display3;
        }
        return (T) display.getOrCreateStack(r, options, candidateTask, activityType, z);
    }

    private boolean canLaunchOnDisplay(ActivityRecord r, int displayId) {
        if (r == null) {
            return true;
        }
        return r.canBeLaunchedOnDisplay(displayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getValidLaunchStackOnDisplay(int displayId, ActivityRecord r) {
        ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("Display with displayId=" + displayId + " not found.");
        } else if (r.canBeLaunchedOnDisplay(displayId)) {
            for (int i = activityDisplay.getChildCount() - 1; i >= 0; i--) {
                ActivityStack stack = activityDisplay.getChildAt(i);
                if (isValidLaunchStack(stack, displayId, r)) {
                    return stack;
                }
            }
            if (displayId != 0) {
                return activityDisplay.createStack(r.getWindowingMode(), r.getActivityType(), true);
            }
            Slog.w("ActivityManager", "getValidLaunchStackOnDisplay: can't launch on displayId " + displayId);
            return null;
        } else {
            return null;
        }
    }

    private boolean isValidLaunchStack(ActivityStack stack, int displayId, ActivityRecord r) {
        switch (stack.getActivityType()) {
            case 2:
                return r.isActivityTypeHome();
            case 3:
                return r.isActivityTypeRecents();
            case 4:
                return r.isActivityTypeAssistant();
            default:
                switch (stack.getWindowingMode()) {
                    case 1:
                        return true;
                    case 2:
                        return r.supportsPictureInPicture();
                    case 3:
                        return r.supportsSplitScreenWindowingMode();
                    case 4:
                        return r.supportsSplitScreenWindowingMode();
                    case 5:
                        return r.supportsFreeform();
                    default:
                        if (!stack.isOnHomeDisplay()) {
                            return r.canBeLaunchedOnDisplay(displayId);
                        }
                        Slog.e("ActivityManager", "isValidLaunchStack: Unexpected stack=" + stack);
                        return false;
                }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:30:0x0059, code lost:
        if (r6.inSplitScreenSecondaryWindowingMode() == false) goto L32;
     */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x005b, code lost:
        return r1;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public com.android.server.am.ActivityStack getNextFocusableStackLocked(com.android.server.am.ActivityStack r9, boolean r10) {
        /*
            r8 = this;
            com.android.server.wm.WindowManagerService r0 = r8.mWindowManager
            android.util.SparseIntArray r1 = r8.mTmpOrderedDisplayIds
            r0.getDisplaysInFocusOrder(r1)
            if (r9 == 0) goto Le
            int r0 = r9.getWindowingMode()
            goto Lf
        Le:
            r0 = 0
        Lf:
            r1 = 0
            android.util.SparseIntArray r2 = r8.mTmpOrderedDisplayIds
            int r2 = r2.size()
            int r2 = r2 + (-1)
        L18:
            if (r2 < 0) goto L63
            android.util.SparseIntArray r3 = r8.mTmpOrderedDisplayIds
            int r3 = r3.get(r2)
            com.android.server.am.ActivityDisplay r4 = r8.getActivityDisplayOrCreateLocked(r3)
            if (r4 != 0) goto L27
            goto L60
        L27:
            int r5 = r4.getChildCount()
            int r5 = r5 + (-1)
        L2d:
            if (r5 < 0) goto L60
            com.android.server.am.ActivityStack r6 = r4.getChildAt(r5)
            if (r10 == 0) goto L38
            if (r6 != r9) goto L38
            goto L5d
        L38:
            boolean r7 = r6.isFocusable()
            if (r7 == 0) goto L5d
            r7 = 0
            boolean r7 = r6.shouldBeVisible(r7)
            if (r7 != 0) goto L46
            goto L5d
        L46:
            r7 = 4
            if (r0 != r7) goto L53
            if (r1 != 0) goto L53
            boolean r7 = r6.inSplitScreenPrimaryWindowingMode()
            if (r7 == 0) goto L53
            r1 = r6
            goto L5d
        L53:
            if (r1 == 0) goto L5c
            boolean r7 = r6.inSplitScreenSecondaryWindowingMode()
            if (r7 == 0) goto L5c
            return r1
        L5c:
            return r6
        L5d:
            int r5 = r5 + (-1)
            goto L2d
        L60:
            int r2 = r2 + (-1)
            goto L18
        L63:
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStackSupervisor.getNextFocusableStackLocked(com.android.server.am.ActivityStack, boolean):com.android.server.am.ActivityStack");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getNextValidLaunchStackLocked(ActivityRecord r, int currentFocus) {
        ActivityStack stack;
        this.mWindowManager.getDisplaysInFocusOrder(this.mTmpOrderedDisplayIds);
        for (int i = this.mTmpOrderedDisplayIds.size() - 1; i >= 0; i--) {
            int displayId = this.mTmpOrderedDisplayIds.get(i);
            if (displayId != currentFocus && (stack = getValidLaunchStackOnDisplay(displayId, r)) != null) {
                return stack;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getHomeActivity() {
        return getHomeActivityForUser(this.mCurrentUser);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord getHomeActivityForUser(int userId) {
        ArrayList<TaskRecord> tasks = this.mHomeStack.getAllTasks();
        for (int taskNdx = tasks.size() - 1; taskNdx >= 0; taskNdx--) {
            TaskRecord task = tasks.get(taskNdx);
            if (task.isActivityTypeHome()) {
                ArrayList<ActivityRecord> activities = task.mActivities;
                for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
                    ActivityRecord r = activities.get(activityNdx);
                    if (r.isActivityTypeHome() && (userId == -1 || r.userId == userId)) {
                        return r;
                    }
                }
                continue;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resizeStackLocked(ActivityStack stack, Rect bounds, Rect tempTaskBounds, Rect tempTaskInsetBounds, boolean preserveWindows, boolean allowResizeInDockedMode, boolean deferResume) {
        if (stack.inSplitScreenPrimaryWindowingMode()) {
            resizeDockedStackLocked(bounds, tempTaskBounds, tempTaskInsetBounds, null, null, preserveWindows, deferResume);
            return;
        }
        boolean splitScreenActive = getDefaultDisplay().hasSplitScreenPrimaryStack();
        if (!allowResizeInDockedMode && !stack.getWindowConfiguration().tasksAreFloating() && splitScreenActive) {
            return;
        }
        Trace.traceBegin(64L, "am.resizeStack_" + stack.mStackId);
        this.mWindowManager.deferSurfaceLayout();
        try {
            if (stack.affectedBySplitScreenResize()) {
                if (bounds == null && stack.inSplitScreenWindowingMode()) {
                    stack.setWindowingMode(1);
                } else if (splitScreenActive) {
                    stack.setWindowingMode(4);
                }
            }
            stack.resize(bounds, tempTaskBounds, tempTaskInsetBounds);
            if (!deferResume) {
                try {
                    stack.ensureVisibleActivitiesConfigurationLocked(stack.topRunningActivityLocked(), preserveWindows);
                } catch (Throwable th) {
                    th = th;
                    this.mWindowManager.continueSurfaceLayout();
                    Trace.traceEnd(64L);
                    throw th;
                }
            }
            this.mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(64L);
        } catch (Throwable th2) {
            th = th2;
        }
    }

    void deferUpdateRecentsHomeStackBounds() {
        deferUpdateBounds(3);
        deferUpdateBounds(2);
    }

    void deferUpdateBounds(int activityType) {
        ActivityStack stack = getStack(0, activityType);
        if (stack != null) {
            stack.deferUpdateBounds();
        }
    }

    void continueUpdateRecentsHomeStackBounds() {
        continueUpdateBounds(3);
        continueUpdateBounds(2);
    }

    void continueUpdateBounds(int activityType) {
        ActivityStack stack = getStack(0, activityType);
        if (stack != null) {
            stack.continueUpdateBounds();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppTransitionDone() {
        continueUpdateRecentsHomeStackBounds();
        for (int i = this.mResizingTasksDuringAnimation.size() - 1; i >= 0; i--) {
            int taskId = this.mResizingTasksDuringAnimation.valueAt(i).intValue();
            TaskRecord task = anyTaskForIdLocked(taskId, 0);
            if (task != null) {
                task.setTaskDockedResizing(false);
            }
        }
        this.mResizingTasksDuringAnimation.clear();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void moveTasksToFullscreenStackInSurfaceTransaction(ActivityStack fromStack, int toDisplayId, boolean onTop) {
        int i;
        ActivityDisplay toDisplay;
        this.mWindowManager.deferSurfaceLayout();
        try {
            int windowingMode = fromStack.getWindowingMode();
            boolean inPinnedWindowingMode = windowingMode == 2;
            ActivityDisplay toDisplay2 = getActivityDisplay(toDisplayId);
            if (windowingMode == 3) {
                toDisplay2.onExitingSplitScreenMode();
                int i2 = toDisplay2.getChildCount() - 1;
                while (true) {
                    int i3 = i2;
                    if (i3 < 0) {
                        break;
                    }
                    ActivityStack otherStack = toDisplay2.getChildAt(i3);
                    if (otherStack.inSplitScreenSecondaryWindowingMode()) {
                        resizeStackLocked(otherStack, null, null, null, true, true, true);
                    }
                    i2 = i3 - 1;
                }
                this.mAllowDockedStackResize = false;
            }
            ArrayList<TaskRecord> tasks = fromStack.getAllTasks();
            if (!tasks.isEmpty()) {
                this.mTmpOptions.setLaunchWindowingMode(1);
                int size = tasks.size();
                int i4 = 0;
                while (true) {
                    int i5 = i4;
                    if (i5 >= size) {
                        break;
                    }
                    TaskRecord task = tasks.get(i5);
                    ActivityStack toStack = toDisplay2.getOrCreateStack(null, this.mTmpOptions, task, task.getActivityType(), onTop);
                    if (onTop) {
                        boolean isTopTask = i5 == size + (-1);
                        i = i5;
                        toDisplay = toDisplay2;
                        task.reparent(toStack, true, 0, isTopTask, true, inPinnedWindowingMode, "moveTasksToFullscreenStack - onTop");
                        MetricsLoggerWrapper.logPictureInPictureFullScreen(this.mService.mContext, task.effectiveUid, task.realActivity.flattenToString());
                    } else {
                        i = i5;
                        toDisplay = toDisplay2;
                        task.reparent(toStack, true, 2, false, true, inPinnedWindowingMode, "moveTasksToFullscreenStack - NOT_onTop");
                    }
                    i4 = i + 1;
                    toDisplay2 = toDisplay;
                }
            }
            ensureActivitiesVisibleLocked(null, 0, true);
            resumeFocusedStackTopActivityLocked();
        } finally {
            this.mAllowDockedStackResize = true;
            this.mWindowManager.continueSurfaceLayout();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveTasksToFullscreenStackLocked(ActivityStack fromStack, boolean onTop) {
        moveTasksToFullscreenStackLocked(fromStack, 0, onTop);
    }

    void moveTasksToFullscreenStackLocked(final ActivityStack fromStack, final int toDisplayId, final boolean onTop) {
        this.mWindowManager.inSurfaceTransaction(new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityStackSupervisor$2EfPspQe887pLmnBFuHkVjyLdzE
            @Override // java.lang.Runnable
            public final void run() {
                ActivityStackSupervisor.this.moveTasksToFullscreenStackInSurfaceTransaction(fromStack, toDisplayId, onTop);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSplitScreenResizing(boolean resizing) {
        if (resizing == this.mDockedStackResizing) {
            return;
        }
        this.mDockedStackResizing = resizing;
        this.mWindowManager.setDockedStackResizing(resizing);
        if (!resizing && this.mHasPendingDockedBounds) {
            resizeDockedStackLocked(this.mPendingDockedBounds, this.mPendingTempDockedTaskBounds, this.mPendingTempDockedTaskInsetBounds, this.mPendingTempOtherTaskBounds, this.mPendingTempOtherTaskInsetBounds, true);
            this.mHasPendingDockedBounds = false;
            this.mPendingDockedBounds = null;
            this.mPendingTempDockedTaskBounds = null;
            this.mPendingTempDockedTaskInsetBounds = null;
            this.mPendingTempOtherTaskBounds = null;
            this.mPendingTempOtherTaskInsetBounds = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resizeDockedStackLocked(Rect dockedBounds, Rect tempDockedTaskBounds, Rect tempDockedTaskInsetBounds, Rect tempOtherTaskBounds, Rect tempOtherTaskInsetBounds, boolean preserveWindows) {
        resizeDockedStackLocked(dockedBounds, tempDockedTaskBounds, tempDockedTaskInsetBounds, tempOtherTaskBounds, tempOtherTaskInsetBounds, preserveWindows, false);
    }

    /* JADX WARN: Removed duplicated region for block: B:48:0x00f2  */
    /* JADX WARN: Removed duplicated region for block: B:53:0x00fa  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void resizeDockedStackLocked(android.graphics.Rect r22, android.graphics.Rect r23, android.graphics.Rect r24, android.graphics.Rect r25, android.graphics.Rect r26, boolean r27, boolean r28) {
        /*
            Method dump skipped, instructions count: 282
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStackSupervisor.resizeDockedStackLocked(android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, boolean, boolean):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resizePinnedStackLocked(Rect pinnedBounds, Rect tempPinnedTaskBounds) {
        PinnedActivityStack stack = getDefaultDisplay().getPinnedStack();
        if (stack == null) {
            Slog.w("ActivityManager", "resizePinnedStackLocked: pinned stack not found");
            return;
        }
        PinnedStackWindowController stackController = stack.getWindowContainerController();
        if (stackController.pinnedStackResizeDisallowed()) {
            return;
        }
        Trace.traceBegin(64L, "am.resizePinnedStack");
        this.mWindowManager.deferSurfaceLayout();
        try {
            ActivityRecord r = stack.topRunningActivityLocked();
            Rect insetBounds = null;
            if (tempPinnedTaskBounds != null && stack.isAnimatingBoundsToFullscreen()) {
                insetBounds = this.tempRect;
                insetBounds.top = 0;
                insetBounds.left = 0;
                insetBounds.right = tempPinnedTaskBounds.width();
                insetBounds.bottom = tempPinnedTaskBounds.height();
            }
            if (pinnedBounds != null && tempPinnedTaskBounds == null) {
                stack.onPipAnimationEndResize();
            }
            stack.resize(pinnedBounds, tempPinnedTaskBounds, insetBounds);
            stack.ensureVisibleActivitiesConfigurationLocked(r, false);
        } finally {
            this.mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(64L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeStackInSurfaceTransaction(ActivityStack stack) {
        ArrayList<TaskRecord> tasks = stack.getAllTasks();
        if (stack.getWindowingMode() == 2) {
            PinnedActivityStack pinnedStack = (PinnedActivityStack) stack;
            pinnedStack.mForceHidden = true;
            pinnedStack.ensureActivitiesVisibleLocked(null, 0, true);
            pinnedStack.mForceHidden = false;
            activityIdleInternalLocked(null, false, true, null);
            moveTasksToFullscreenStackLocked(pinnedStack, false);
            return;
        }
        for (int i = tasks.size() - 1; i >= 0; i--) {
            removeTaskByIdLocked(tasks.get(i).taskId, true, true, "remove-stack");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStack(final ActivityStack stack) {
        this.mWindowManager.inSurfaceTransaction(new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityStackSupervisor$x0Vocp-itdO3YPTBM6d_k8Yij7g
            @Override // java.lang.Runnable
            public final void run() {
                ActivityStackSupervisor.this.removeStackInSurfaceTransaction(stack);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksInWindowingModes(int... windowingModes) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            this.mActivityDisplays.valueAt(i).removeStacksInWindowingModes(windowingModes);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStacksWithActivityTypes(int... activityTypes) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            this.mActivityDisplays.valueAt(i).removeStacksWithActivityTypes(activityTypes);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeTaskByIdLocked(int taskId, boolean killProcess, boolean removeFromRecents, String reason) {
        return removeTaskByIdLocked(taskId, killProcess, removeFromRecents, false, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeTaskByIdLocked(int taskId, boolean killProcess, boolean removeFromRecents, boolean pauseImmediately, String reason) {
        TaskRecord tr = anyTaskForIdLocked(taskId, 1);
        if (tr != null) {
            tr.removeTaskActivitiesLocked(pauseImmediately, reason);
            cleanUpRemovedTaskLocked(tr, killProcess, removeFromRecents);
            this.mService.getLockTaskController().clearLockedTask(tr);
            if (tr.isPersistable) {
                this.mService.notifyTaskPersisterLocked(null, true);
            }
            return true;
        }
        Slog.w("ActivityManager", "Request to remove task ignored for non-existent task " + taskId);
        return false;
    }

    void cleanUpRemovedTaskLocked(TaskRecord tr, boolean killProcess, boolean removeFromRecents) {
        if (removeFromRecents) {
            this.mRecentTasks.remove(tr);
        }
        ComponentName component = tr.getBaseIntent().getComponent();
        if (component == null) {
            Slog.w("ActivityManager", "No component for base intent of task: " + tr);
            return;
        }
        this.mService.mServices.cleanUpRemovedTaskLocked(tr, component, new Intent(tr.getBaseIntent()));
        if (!killProcess) {
            return;
        }
        String pkg = component.getPackageName();
        ArrayList<ProcessRecord> procsToKill = new ArrayList<>();
        ArrayMap<String, SparseArray<ProcessRecord>> pmap = this.mService.mProcessNames.getMap();
        for (int i = 0; i < pmap.size(); i++) {
            SparseArray<ProcessRecord> uids = pmap.valueAt(i);
            for (int j = 0; j < uids.size(); j++) {
                ProcessRecord proc = uids.valueAt(j);
                if (proc.userId == tr.userId && proc != this.mService.mHomeProcess && proc.pkgList.containsKey(pkg)) {
                    for (int k = 0; k < proc.activities.size(); k++) {
                        TaskRecord otherTask = proc.activities.get(k).getTask();
                        if (tr.taskId != otherTask.taskId && otherTask.inRecents) {
                            return;
                        }
                    }
                    if (proc.foregroundServices) {
                        return;
                    }
                    procsToKill.add(proc);
                }
            }
        }
        int i2 = 0;
        while (true) {
            int i3 = i2;
            if (i3 >= procsToKill.size()) {
                return;
            }
            ProcessRecord pr = procsToKill.get(i3);
            if (pr.setSchedGroup == 0 && pr.curReceivers.isEmpty()) {
                pr.kill("remove task", true);
            } else {
                pr.waitingToKill = "remove task";
            }
            i2 = i3 + 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean restoreRecentTaskLocked(TaskRecord task, ActivityOptions aOptions, boolean onTop) {
        ActivityStack stack = getLaunchStack(null, aOptions, task, onTop);
        ActivityStack currentStack = task.getStack();
        if (currentStack != null) {
            if (currentStack == stack) {
                return true;
            }
            currentStack.removeTask(task, "restoreRecentTaskLocked", 1);
        }
        stack.addTask(task, onTop, "restoreRecentTask");
        task.createWindowContainer(onTop, true);
        if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
            Slog.v("ActivityManager", "Added restored task=" + task + " to stack=" + stack);
        }
        ArrayList<ActivityRecord> activities = task.mActivities;
        for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
            activities.get(activityNdx).createWindowContainer();
        }
        return true;
    }

    @Override // com.android.server.am.RecentTasks.Callbacks
    public void onRecentTaskAdded(TaskRecord task) {
        task.touchActiveTime();
    }

    @Override // com.android.server.am.RecentTasks.Callbacks
    public void onRecentTaskRemoved(TaskRecord task, boolean wasTrimmed) {
        if (wasTrimmed) {
            removeTaskByIdLocked(task.taskId, false, false, false, "recent-task-trimmed");
        }
        task.removedFromRecents();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveStackToDisplayLocked(int stackId, int displayId, boolean onTop) {
        ActivityDisplay activityDisplay = getActivityDisplayOrCreateLocked(displayId);
        if (activityDisplay == null) {
            throw new IllegalArgumentException("moveStackToDisplayLocked: Unknown displayId=" + displayId);
        }
        ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("moveStackToDisplayLocked: Unknown stackId=" + stackId);
        }
        ActivityDisplay currentDisplay = stack.getDisplay();
        if (currentDisplay == null) {
            throw new IllegalStateException("moveStackToDisplayLocked: Stack with stack=" + stack + " is not attached to any display.");
        } else if (currentDisplay.mDisplayId == displayId) {
            throw new IllegalArgumentException("Trying to move stack=" + stack + " to its current displayId=" + displayId);
        } else {
            stack.reparent(activityDisplay, onTop);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getReparentTargetStack(TaskRecord task, ActivityStack stack, boolean toTop) {
        ActivityStack prevStack = task.getStack();
        int stackId = stack.mStackId;
        boolean inMultiWindowMode = stack.inMultiWindowMode();
        if (prevStack != null && prevStack.mStackId == stackId) {
            Slog.w("ActivityManager", "Can not reparent to same stack, task=" + task + " already in stackId=" + stackId);
            return prevStack;
        } else if (inMultiWindowMode && !this.mService.mSupportsMultiWindow) {
            throw new IllegalArgumentException("Device doesn't support multi-window, can not reparent task=" + task + " to stack=" + stack);
        } else if (stack.mDisplayId != 0 && !this.mService.mSupportsMultiDisplay) {
            throw new IllegalArgumentException("Device doesn't support multi-display, can not reparent task=" + task + " to stackId=" + stackId);
        } else if (stack.getWindowingMode() == 5 && !this.mService.mSupportsFreeformWindowManagement) {
            throw new IllegalArgumentException("Device doesn't support freeform, can not reparent task=" + task);
        } else if (inMultiWindowMode && !task.isResizeable()) {
            Slog.w("ActivityManager", "Can not move unresizeable task=" + task + " to multi-window stack=" + stack + " Moving to a fullscreen stack instead.");
            if (prevStack != null) {
                return prevStack;
            }
            return stack.getDisplay().createStack(1, stack.getActivityType(), toTop);
        } else {
            return stack;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean moveTopStackActivityToPinnedStackLocked(int stackId, Rect destBounds) {
        ActivityStack stack = getStack(stackId);
        if (stack == null) {
            throw new IllegalArgumentException("moveTopStackActivityToPinnedStackLocked: Unknown stackId=" + stackId);
        }
        ActivityRecord r = stack.topRunningActivityLocked();
        if (r == null) {
            Slog.w("ActivityManager", "moveTopStackActivityToPinnedStackLocked: No top running activity in stack=" + stack);
            return false;
        } else if (!this.mService.mForceResizableActivities && !r.supportsPictureInPicture()) {
            Slog.w("ActivityManager", "moveTopStackActivityToPinnedStackLocked: Picture-In-Picture not supported for  r=" + r);
            return false;
        } else {
            moveActivityToPinnedStackLocked(r, null, 0.0f, "moveTopActivityToPinnedStack");
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveActivityToPinnedStackLocked(ActivityRecord r, Rect sourceHintBounds, float aspectRatio, String reason) {
        PinnedActivityStack stack;
        this.mWindowManager.deferSurfaceLayout();
        ActivityDisplay display = r.getStack().getDisplay();
        PinnedActivityStack stack2 = display.getPinnedStack();
        if (stack2 != null) {
            moveTasksToFullscreenStackLocked(stack2, false);
        }
        PinnedActivityStack stack3 = (PinnedActivityStack) display.getOrCreateStack(2, r.getActivityType(), true);
        Rect destBounds = stack3.getDefaultPictureInPictureBounds(aspectRatio);
        try {
            TaskRecord task = r.getTask();
            try {
                resizeStackLocked(stack3, task.getOverrideBounds(), null, null, false, true, false);
                if (task.mActivities.size() == 1) {
                    stack = stack3;
                    try {
                        task.reparent((ActivityStack) stack3, true, 0, false, true, false, reason);
                    } catch (Throwable th) {
                        th = th;
                        this.mWindowManager.continueSurfaceLayout();
                        throw th;
                    }
                } else {
                    stack = stack3;
                    try {
                        TaskRecord newTask = task.getStack().createTaskRecord(getNextTaskIdForUserLocked(r.userId), r.info, r.intent, null, null, true);
                        r.reparent(newTask, Integer.MAX_VALUE, "moveActivityToStack");
                        newTask.reparent((ActivityStack) stack, true, 0, false, true, false, reason);
                    } catch (Throwable th2) {
                        th = th2;
                        this.mWindowManager.continueSurfaceLayout();
                        throw th;
                    }
                }
                r.supportsEnterPipOnTaskSwitch = false;
                this.mWindowManager.continueSurfaceLayout();
                stack.animateResizePinnedStack(sourceHintBounds, destBounds, -1, true);
                ensureActivitiesVisibleLocked(null, 0, false);
                resumeFocusedStackTopActivityLocked();
                this.mService.mTaskChangeNotificationController.notifyActivityPinned(r);
            } catch (Throwable th3) {
                th = th3;
            }
        } catch (Throwable th4) {
            th = th4;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean moveFocusableActivityStackToFrontLocked(ActivityRecord r, String reason) {
        if (r == null || !r.isFocusable()) {
            return false;
        }
        TaskRecord task = r.getTask();
        ActivityStack stack = r.getStack();
        if (stack == null) {
            Slog.w("ActivityManager", "moveActivityStackToFront: invalid task or stack: r=" + r + " task=" + task);
            return false;
        } else if (stack == this.mFocusedStack && stack.topRunningActivityLocked() == r) {
            return false;
        } else {
            stack.moveToFront(reason, task);
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord findTaskLocked(ActivityRecord r, int displayId) {
        this.mTmpFindTaskResult.r = null;
        this.mTmpFindTaskResult.matchedByRootAffinity = false;
        ActivityRecord affinityMatch = null;
        if (ActivityManagerDebugConfig.DEBUG_TASKS) {
            Slog.d("ActivityManager", "Looking for task of " + r);
        }
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (!r.hasCompatibleActivityType(stack)) {
                    if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                        Slog.d("ActivityManager", "Skipping stack: (mismatch activity/stack) " + stack);
                    }
                } else {
                    stack.findTaskLocked(r, this.mTmpFindTaskResult);
                    if (this.mTmpFindTaskResult.r == null) {
                        continue;
                    } else if (!this.mTmpFindTaskResult.matchedByRootAffinity) {
                        return this.mTmpFindTaskResult.r;
                    } else {
                        if (this.mTmpFindTaskResult.r.getDisplayId() == displayId) {
                            affinityMatch = this.mTmpFindTaskResult.r;
                        } else if (ActivityManagerDebugConfig.DEBUG_TASKS && this.mTmpFindTaskResult.matchedByRootAffinity) {
                            Slog.d("ActivityManager", "Skipping match on different display " + this.mTmpFindTaskResult.r.getDisplayId() + " " + displayId);
                        }
                    }
                }
            }
        }
        if (ActivityManagerDebugConfig.DEBUG_TASKS && affinityMatch == null) {
            Slog.d("ActivityManager", "No task found");
        }
        return affinityMatch;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityRecord findActivityLocked(Intent intent, ActivityInfo info, boolean compareIntentFilters) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord ar = stack.findActivityLocked(intent, info, compareIntentFilters);
                if (ar != null) {
                    return ar;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasAwakeDisplay() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            if (!display.shouldSleep()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void goingToSleepLocked() {
        scheduleSleepTimeout();
        if (!this.mGoingToSleep.isHeld()) {
            this.mGoingToSleep.acquire();
            if (this.mLaunchingActivity.isHeld()) {
                this.mLaunchingActivity.release();
                this.mService.mHandler.removeMessages(104);
            }
        }
        applySleepTokensLocked(false);
        checkReadyForSleepLocked(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareForShutdownLocked() {
        for (int i = 0; i < this.mActivityDisplays.size(); i++) {
            createSleepTokenLocked("shutdown", this.mActivityDisplays.keyAt(i));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shutdownLocked(int timeout) {
        goingToSleepLocked();
        boolean timedout = false;
        long endTime = System.currentTimeMillis() + timeout;
        while (true) {
            if (!putStacksToSleepLocked(true, true)) {
                long timeRemaining = endTime - System.currentTimeMillis();
                if (timeRemaining > 0) {
                    try {
                        this.mService.wait(timeRemaining);
                    } catch (InterruptedException e) {
                    }
                } else {
                    Slog.w("ActivityManager", "Activity manager shutdown timed out");
                    timedout = true;
                    break;
                }
            } else {
                break;
            }
        }
        checkReadyForSleepLocked(false);
        return timedout;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void comeOutOfSleepIfNeededLocked() {
        removeSleepTimeouts();
        if (this.mGoingToSleep.isHeld()) {
            this.mGoingToSleep.release();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applySleepTokensLocked(boolean applyToStacks) {
        xpLogger.i("ActivityManager", "applySleepTokensLocked applyToStacks=" + applyToStacks + " size=" + this.mActivityDisplays.size());
        for (int displayNdx = this.mActivityDisplays.size() + (-1); displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            boolean displayShouldSleep = display.shouldSleep();
            if (displayShouldSleep != display.isSleeping()) {
                display.setIsSleeping(displayShouldSleep);
                if (applyToStacks) {
                    for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                        ActivityStack stack = display.getChildAt(stackNdx);
                        Object component = (stack == null || stack.getTopActivity() == null) ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : stack.getTopActivity().realActivity;
                        StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                        buffer.append("applySleepTokensLocked");
                        buffer.append(" displayShouldSleep=" + displayShouldSleep);
                        buffer.append(" isFocusedStack=" + isFocusedStack(stack));
                        buffer.append(" component=" + component);
                        if (displayShouldSleep) {
                            buffer.append(" goToSleepIfPossible");
                            stack.goToSleepIfPossible(false);
                        } else {
                            stack.awakeFromSleepingLocked();
                            buffer.append(" awakeFromSleepingLocked");
                            if (isFocusedStack(stack) && !getKeyguardController().isKeyguardOrAodShowing(display.mDisplayId)) {
                                boolean ret = resumeFocusedStackTopActivityLocked();
                                buffer.append(" resumeFocusedStackTopActivityLocked ret=" + ret);
                            }
                        }
                        xpLogger.i("ActivityManager", buffer.toString());
                    }
                    if (!displayShouldSleep && !this.mGoingToSleepActivities.isEmpty()) {
                        Iterator<ActivityRecord> it = this.mGoingToSleepActivities.iterator();
                        while (it.hasNext()) {
                            ActivityRecord r = it.next();
                            if (r.getDisplayId() == display.mDisplayId) {
                                it.remove();
                            }
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void activitySleptLocked(ActivityRecord r) {
        this.mGoingToSleepActivities.remove(r);
        ActivityStack s = r.getStack();
        if (s != null) {
            s.checkReadyForSleep();
        } else {
            checkReadyForSleepLocked(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkReadyForSleepLocked(boolean allowDelay) {
        if (!this.mService.isSleepingOrShuttingDownLocked() || !putStacksToSleepLocked(allowDelay, false)) {
            return;
        }
        sendPowerHintForLaunchEndIfNeeded();
        removeSleepTimeouts();
        if (this.mGoingToSleep.isHeld()) {
            this.mGoingToSleep.release();
        }
        if (this.mService.mShuttingDown) {
            this.mService.notifyAll();
        }
    }

    private boolean putStacksToSleepLocked(boolean allowDelay, boolean shuttingDown) {
        boolean allSleep = true;
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (allowDelay) {
                    allSleep &= stack.goToSleepIfPossible(shuttingDown);
                } else {
                    stack.goToSleep();
                }
            }
        }
        return allSleep;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean reportResumedActivityLocked(ActivityRecord r) {
        this.mStoppingActivities.remove(r);
        ActivityStack stack = r.getStack();
        if (isFocusedStack(stack)) {
            this.mService.updateUsageStats(r, true);
        }
        if (allResumedActivitiesComplete()) {
            ensureActivitiesVisibleLocked(null, 0, false);
            this.mWindowManager.executeAppTransition();
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleAppCrashLocked(ProcessRecord app) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.handleAppCrashLocked(app);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLaunchTaskBehindCompleteLocked(ActivityRecord r) {
        TaskRecord task = r.getTask();
        ActivityStack stack = task.getStack();
        r.mLaunchTaskBehind = false;
        this.mRecentTasks.add(task);
        this.mService.mTaskChangeNotificationController.notifyTaskStackChanged();
        r.setVisibility(false);
        ActivityRecord top = stack.getTopActivity();
        if (top != null) {
            top.getTask().touchActiveTime();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleLaunchTaskBehindComplete(IBinder token) {
        this.mHandler.obtainMessage(112, token).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges, boolean preserveWindows) {
        ensureActivitiesVisibleLocked(starting, configChanges, preserveWindows, true);
    }

    void ensureActivitiesVisibleLocked(ActivityRecord starting, int configChanges, boolean preserveWindows, boolean notifyClients) {
        getKeyguardController().beginActivityVisibilityUpdate();
        try {
            for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
                ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
                for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                    ActivityStack stack = display.getChildAt(stackNdx);
                    stack.ensureActivitiesVisibleLocked(starting, configChanges, preserveWindows, notifyClients);
                }
            }
        } finally {
            getKeyguardController().endActivityVisibilityUpdate();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addStartingWindowsForVisibleActivities(boolean taskSwitch) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.addStartingWindowsForVisibleActivities(taskSwitch);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void invalidateTaskLayers() {
        this.mTaskLayersChanged = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void rankTaskLayersIfNeeded() {
        if (!this.mTaskLayersChanged) {
            return;
        }
        this.mTaskLayersChanged = false;
        for (int displayNdx = 0; displayNdx < this.mActivityDisplays.size(); displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            int baseLayer = 0;
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                baseLayer += stack.rankTaskLayers(baseLayer);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOtherAppTimeTrackers(AppTimeTracker except) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.clearOtherAppTimeTrackers(except);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleDestroyAllActivities(ProcessRecord app, String reason) {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.scheduleDestroyActivities(app, reason);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void releaseSomeActivitiesLocked(ProcessRecord app, String reason) {
        ArraySet<TaskRecord> tasks = null;
        if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
            Slog.d("ActivityManager", "Trying to release some activities in " + app);
        }
        TaskRecord firstTask = null;
        for (int i = 0; i < app.activities.size(); i++) {
            ActivityRecord r = app.activities.get(i);
            if (r.finishing || r.isState(ActivityStack.ActivityState.DESTROYING, ActivityStack.ActivityState.DESTROYED)) {
                if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
                    Slog.d("ActivityManager", "Abort release; already destroying: " + r);
                    return;
                }
                return;
            }
            if (r.visible || !r.stopped || !r.haveState || r.isState(ActivityStack.ActivityState.RESUMED, ActivityStack.ActivityState.PAUSING, ActivityStack.ActivityState.PAUSED, ActivityStack.ActivityState.STOPPING)) {
                if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
                    Slog.d("ActivityManager", "Not releasing in-use activity: " + r);
                }
            } else {
                TaskRecord task = r.getTask();
                if (task != null) {
                    if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
                        Slog.d("ActivityManager", "Collecting release task " + task + " from " + r);
                    }
                    if (firstTask == null) {
                        firstTask = task;
                    } else if (firstTask != task) {
                        if (tasks == null) {
                            tasks = new ArraySet<>();
                            tasks.add(firstTask);
                        }
                        tasks.add(task);
                    }
                }
            }
        }
        if (tasks == null) {
            if (ActivityManagerDebugConfig.DEBUG_RELEASE) {
                Slog.d("ActivityManager", "Didn't find two or more tasks to release");
                return;
            }
            return;
        }
        int numDisplays = this.mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            int stackCount = display.getChildCount();
            for (int stackNdx = 0; stackNdx < stackCount; stackNdx++) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (stack.releaseSomeActivitiesLocked(app, tasks, reason) > 0) {
                    return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean switchUserLocked(int userId, UserState uss) {
        int focusStackId = this.mFocusedStack.getStackId();
        ActivityStack dockedStack = getDefaultDisplay().getSplitScreenPrimaryStack();
        if (dockedStack != null) {
            moveTasksToFullscreenStackLocked(dockedStack, this.mFocusedStack == dockedStack);
        }
        removeStacksInWindowingModes(2);
        this.mUserStackInFront.put(this.mCurrentUser, focusStackId);
        int restoreStackId = this.mUserStackInFront.get(userId, this.mHomeStack.mStackId);
        this.mCurrentUser = userId;
        this.mStartingUsers.add(uss);
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                stack.switchUserLocked(userId);
                TaskRecord task = stack.topTask();
                if (task != null) {
                    stack.positionChildWindowContainerAtTop(task);
                }
            }
        }
        ActivityStack stack2 = getStack(restoreStackId);
        if (stack2 == null) {
            stack2 = this.mHomeStack;
        }
        boolean homeInFront = stack2.isActivityTypeHome();
        if (stack2.isOnHomeDisplay()) {
            stack2.moveToFront("switchUserOnHomeDisplay");
        } else {
            resumeHomeStackTask(null, "switchUserOnOtherDisplay");
        }
        return homeInFront;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCurrentProfileLocked(int userId) {
        if (userId == this.mCurrentUser) {
            return true;
        }
        return this.mService.mUserController.isCurrentProfile(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isStoppingNoHistoryActivity() {
        Iterator<ActivityRecord> it = this.mStoppingActivities.iterator();
        while (it.hasNext()) {
            ActivityRecord record = it.next();
            if (record.isNoHistory()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ArrayList<ActivityRecord> processStoppingActivitiesLocked(ActivityRecord idleActivity, boolean remove, boolean processPausingActivities) {
        boolean shouldSleepOrShutDown;
        ArrayList<ActivityRecord> stops = null;
        boolean nowVisible = allResumedActivitiesVisible();
        for (int activityNdx = this.mStoppingActivities.size() - 1; activityNdx >= 0; activityNdx--) {
            ActivityRecord s = this.mStoppingActivities.get(activityNdx);
            boolean waitingVisible = this.mActivitiesWaitingForVisibleActivity.contains(s);
            if (ActivityManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityManager", "Stopping " + s + ": nowVisible=" + nowVisible + " waitingVisible=" + waitingVisible + " finishing=" + s.finishing);
            }
            if (waitingVisible && nowVisible) {
                this.mActivitiesWaitingForVisibleActivity.remove(s);
                waitingVisible = false;
                if (s.finishing) {
                    if (ActivityManagerDebugConfig.DEBUG_STATES) {
                        Slog.v("ActivityManager", "Before stopping, can hide: " + s);
                    }
                    s.setVisibility(false);
                }
            }
            if (remove) {
                ActivityStack stack = s.getStack();
                if (stack != null) {
                    shouldSleepOrShutDown = stack.shouldSleepOrShutDownActivities();
                } else {
                    shouldSleepOrShutDown = this.mService.isSleepingOrShuttingDownLocked();
                }
                if (!waitingVisible || shouldSleepOrShutDown) {
                    if (!processPausingActivities && s.isState(ActivityStack.ActivityState.PAUSING)) {
                        removeTimeoutsForActivityLocked(idleActivity);
                        scheduleIdleTimeoutLocked(idleActivity);
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_STATES) {
                            Slog.v("ActivityManager", "Ready to stop: " + s);
                        }
                        if (stops == null) {
                            stops = new ArrayList<>();
                        }
                        stops.add(s);
                        this.mActivitiesWaitingForVisibleActivity.remove(s);
                        this.mStoppingActivities.remove(activityNdx);
                    }
                }
            }
        }
        return stops;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void validateTopActivitiesLocked() {
        for (int displayNdx = this.mActivityDisplays.size() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                ActivityRecord r = stack.topRunningActivityLocked();
                ActivityStack.ActivityState state = r == null ? ActivityStack.ActivityState.DESTROYED : r.getState();
                if (isFocusedStack(stack)) {
                    if (r == null) {
                        Slog.e("ActivityManager", "validateTop...: null top activity, stack=" + stack);
                    } else {
                        ActivityRecord pausing = stack.mPausingActivity;
                        if (pausing != null && pausing == r) {
                            Slog.e("ActivityManager", "validateTop...: top stack has pausing activity r=" + r + " state=" + state);
                        }
                        if (state != ActivityStack.ActivityState.INITIALIZING && state != ActivityStack.ActivityState.RESUMED) {
                            Slog.e("ActivityManager", "validateTop...: activity in front not resumed r=" + r + " state=" + state);
                        }
                    }
                } else {
                    ActivityRecord resumed = stack.getResumedActivity();
                    if (resumed != null && resumed == r) {
                        Slog.e("ActivityManager", "validateTop...: back stack has resumed activity r=" + r + " state=" + state);
                    }
                    if (r != null && (state == ActivityStack.ActivityState.INITIALIZING || state == ActivityStack.ActivityState.RESUMED)) {
                        Slog.e("ActivityManager", "validateTop...: activity in back resumed r=" + r + " state=" + state);
                    }
                }
            }
        }
    }

    public void dumpDisplays(PrintWriter pw) {
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i += -1) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(i);
            pw.print("[id:" + display.mDisplayId + " stacks:");
            display.dumpStacks(pw);
            pw.print("]");
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("mFocusedStack=" + this.mFocusedStack);
        pw.print(" mLastFocusedStack=");
        pw.println(this.mLastFocusedStack);
        pw.print(prefix);
        pw.println("mCurTaskIdForUser=" + this.mCurTaskIdForUser);
        pw.print(prefix);
        pw.println("mUserStackInFront=" + this.mUserStackInFront);
        for (int i = this.mActivityDisplays.size() + (-1); i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(i);
            display.dump(pw, prefix);
        }
        if (!this.mWaitingForActivityVisible.isEmpty()) {
            pw.print(prefix);
            pw.println("mWaitingForActivityVisible=");
            for (int i2 = 0; i2 < this.mWaitingForActivityVisible.size(); i2++) {
                pw.print(prefix);
                pw.print(prefix);
                this.mWaitingForActivityVisible.get(i2).dump(pw, prefix);
            }
        }
        pw.print(prefix);
        pw.print("isHomeRecentsComponent=");
        pw.print(this.mRecentTasks.isRecentsComponentHomeActivity(this.mCurrentUser));
        getKeyguardController().dump(pw, prefix);
        this.mService.getLockTaskController().dump(pw, prefix);
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, false);
        for (int displayNdx = 0; displayNdx < this.mActivityDisplays.size(); displayNdx++) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.valueAt(displayNdx);
            activityDisplay.writeToProto(proto, 2246267895810L);
        }
        getKeyguardController().writeToProto(proto, 1146756268035L);
        if (this.mFocusedStack != null) {
            proto.write(1120986464260L, this.mFocusedStack.mStackId);
            ActivityRecord focusedActivity = getResumedActivityLocked();
            if (focusedActivity != null) {
                focusedActivity.writeIdentifierToProto(proto, 1146756268037L);
            }
        } else {
            proto.write(1120986464260L, -1);
        }
        proto.write(1133871366150L, this.mRecentTasks.isRecentsComponentHomeActivity(this.mCurrentUser));
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpDisplayConfigs(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("Display override configurations:");
        int displayCount = this.mActivityDisplays.size();
        for (int i = 0; i < displayCount; i++) {
            ActivityDisplay activityDisplay = this.mActivityDisplays.valueAt(i);
            pw.print(prefix);
            pw.print("  ");
            pw.print(activityDisplay.mDisplayId);
            pw.print(": ");
            pw.println(activityDisplay.getOverrideConfiguration());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<ActivityRecord> getDumpActivitiesLocked(String name, boolean dumpVisibleStacksOnly, boolean dumpFocusedStackOnly) {
        if (dumpFocusedStackOnly) {
            return this.mFocusedStack.getDumpActivitiesLocked(name);
        }
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        int numDisplays = this.mActivityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (!dumpVisibleStacksOnly || stack.shouldBeVisible(null)) {
                    activities.addAll(stack.getDumpActivitiesLocked(name));
                }
            }
        }
        return activities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean printThisActivity(PrintWriter pw, ActivityRecord activity, String dumpPackage, boolean needSep, String prefix) {
        if (activity != null) {
            if (dumpPackage == null || dumpPackage.equals(activity.packageName)) {
                if (needSep) {
                    pw.println();
                }
                pw.print(prefix);
                pw.println(activity);
                return true;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        int displayNdx = 0;
        boolean printed = false;
        boolean needSep = false;
        while (true) {
            int displayNdx2 = displayNdx;
            if (displayNdx2 >= this.mActivityDisplays.size()) {
                return dumpHistoryList(fd, pw, this.mGoingToSleepActivities, "  ", "Sleep", false, !dumpAll, false, dumpPackage, true, "  Activities waiting to sleep:", null) | printed | dumpHistoryList(fd, pw, this.mFinishingActivities, "  ", "Fin", false, !dumpAll, false, dumpPackage, true, "  Activities waiting to finish:", null) | dumpHistoryList(fd, pw, this.mStoppingActivities, "  ", "Stop", false, !dumpAll, false, dumpPackage, true, "  Activities waiting to stop:", null) | dumpHistoryList(fd, pw, this.mActivitiesWaitingForVisibleActivity, "  ", "Wait", false, !dumpAll, false, dumpPackage, true, "  Activities waiting for another to become visible:", null);
            }
            ActivityDisplay activityDisplay = this.mActivityDisplays.valueAt(displayNdx2);
            pw.print("Display #");
            pw.print(activityDisplay.mDisplayId);
            pw.println(" (activities from top to bottom):");
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx2);
            int stackNdx = display.getChildCount() - 1;
            while (true) {
                int stackNdx2 = stackNdx;
                if (stackNdx2 >= 0) {
                    ActivityStack stack = display.getChildAt(stackNdx2);
                    pw.println();
                    pw.println("  Stack #" + stack.mStackId + ": type=" + WindowConfiguration.activityTypeToString(stack.getActivityType()) + " mode=" + WindowConfiguration.windowingModeToString(stack.getWindowingMode()));
                    StringBuilder sb = new StringBuilder();
                    sb.append("  isSleeping=");
                    sb.append(stack.shouldSleepActivities());
                    pw.println(sb.toString());
                    pw.println("  mBounds=" + stack.getOverrideBounds());
                    ActivityDisplay display2 = display;
                    ActivityDisplay activityDisplay2 = activityDisplay;
                    int displayNdx3 = displayNdx2;
                    boolean printed2 = dumpHistoryList(fd, pw, stack.mLRUActivities, "    ", "Run", false, !dumpAll, false, dumpPackage, true, "    Running activities (most recent first):", null) | printed | stack.dumpActivitiesLocked(fd, pw, dumpAll, dumpClient, dumpPackage, needSep);
                    boolean needSep2 = printed2;
                    boolean pr = printThisActivity(pw, stack.mPausingActivity, dumpPackage, needSep2, "    mPausingActivity: ");
                    if (pr) {
                        printed2 = true;
                        needSep2 = false;
                    }
                    boolean pr2 = printThisActivity(pw, stack.getResumedActivity(), dumpPackage, needSep2, "    mResumedActivity: ");
                    if (pr2) {
                        printed2 = true;
                        needSep2 = false;
                    }
                    if (dumpAll) {
                        boolean pr3 = printThisActivity(pw, stack.mLastPausedActivity, dumpPackage, needSep2, "    mLastPausedActivity: ");
                        if (pr3) {
                            printed2 = true;
                            needSep2 = true;
                        }
                        printed2 |= printThisActivity(pw, stack.mLastNoHistoryActivity, dumpPackage, needSep2, "    mLastNoHistoryActivity: ");
                    }
                    printed = printed2;
                    needSep = printed;
                    stackNdx = stackNdx2 - 1;
                    activityDisplay = activityDisplay2;
                    displayNdx2 = displayNdx3;
                    display = display2;
                }
            }
            displayNdx = displayNdx2 + 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean dumpHistoryList(FileDescriptor fd, PrintWriter pw, List<ActivityRecord> list, String prefix, String label, boolean complete, boolean brief, boolean client, String dumpPackage, boolean needNL, String header, TaskRecord lastTask) {
        TaskRecord lastTask2;
        TransferPipe tp;
        String str = prefix;
        String str2 = dumpPackage;
        boolean printed = false;
        int i = list.size() - 1;
        boolean needNL2 = needNL;
        String innerPrefix = null;
        String[] args = null;
        String innerPrefix2 = header;
        TaskRecord lastTask3 = lastTask;
        while (i >= 0) {
            ActivityRecord r = list.get(i);
            if (str2 != null && !str2.equals(r.packageName)) {
                lastTask2 = lastTask3;
            } else {
                boolean full = false;
                if (innerPrefix == null) {
                    innerPrefix = str + "      ";
                    args = new String[0];
                }
                printed = true;
                if (!brief && (complete || !r.isInHistory())) {
                    full = true;
                }
                if (needNL2) {
                    pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    needNL2 = false;
                }
                if (innerPrefix2 != null) {
                    pw.println(innerPrefix2);
                    innerPrefix2 = null;
                }
                String header2 = innerPrefix2;
                if (lastTask3 != r.getTask()) {
                    lastTask3 = r.getTask();
                    pw.print(str);
                    pw.print(full ? "* " : "  ");
                    pw.println(lastTask3);
                    if (full) {
                        lastTask3.dump(pw, str + "  ");
                    } else if (complete && lastTask3.intent != null) {
                        pw.print(str);
                        pw.print("  ");
                        pw.println(lastTask3.intent.toInsecureStringWithClip());
                    }
                }
                pw.print(str);
                pw.print(full ? "  * " : "    ");
                pw.print(label);
                pw.print(" #");
                pw.print(i);
                pw.print(": ");
                pw.println(r);
                if (full) {
                    r.dump(pw, innerPrefix);
                } else if (complete) {
                    pw.print(innerPrefix);
                    pw.println(r.intent.toInsecureString());
                    if (r.app != null) {
                        pw.print(innerPrefix);
                        pw.println(r.app);
                    }
                }
                if (!client || r.app == null || r.app.thread == null) {
                    lastTask2 = lastTask3;
                } else {
                    pw.flush();
                    try {
                        TransferPipe tp2 = new TransferPipe();
                        try {
                            tp = tp2;
                            try {
                                r.app.thread.dumpActivity(tp.getWriteFd(), r.appToken, innerPrefix, args);
                                lastTask2 = lastTask3;
                                try {
                                    tp.go(fd, 2000L);
                                    try {
                                        tp.kill();
                                    } catch (RemoteException e) {
                                        pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
                                        needNL2 = true;
                                        innerPrefix2 = header2;
                                        i--;
                                        lastTask3 = lastTask2;
                                        str = prefix;
                                        str2 = dumpPackage;
                                    } catch (IOException e2) {
                                        e = e2;
                                        pw.println(innerPrefix + "Failure while dumping the activity: " + e);
                                        needNL2 = true;
                                        innerPrefix2 = header2;
                                        i--;
                                        lastTask3 = lastTask2;
                                        str = prefix;
                                        str2 = dumpPackage;
                                    }
                                } catch (Throwable th) {
                                    th = th;
                                    tp.kill();
                                    throw th;
                                    break;
                                }
                            } catch (Throwable th2) {
                                th = th2;
                                lastTask2 = lastTask3;
                            }
                        } catch (Throwable th3) {
                            th = th3;
                            lastTask2 = lastTask3;
                            tp = tp2;
                        }
                    } catch (RemoteException e3) {
                        lastTask2 = lastTask3;
                    } catch (IOException e4) {
                        e = e4;
                        lastTask2 = lastTask3;
                    }
                    needNL2 = true;
                }
                innerPrefix2 = header2;
            }
            i--;
            lastTask3 = lastTask2;
            str = prefix;
            str2 = dumpPackage;
        }
        return printed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleIdleTimeoutLocked(ActivityRecord next) {
        if (ActivityManagerDebugConfig.DEBUG_IDLE) {
            Slog.d("ActivityManager", "scheduleIdleTimeoutLocked: Callers=" + Debug.getCallers(4));
        }
        Message msg = this.mHandler.obtainMessage(100, next);
        this.mHandler.sendMessageDelayed(msg, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleIdleLocked() {
        this.mHandler.sendEmptyMessage(101);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeTimeoutsForActivityLocked(ActivityRecord r) {
        if (ActivityManagerDebugConfig.DEBUG_IDLE) {
            Slog.d("ActivityManager", "removeTimeoutsForActivity: Callers=" + Debug.getCallers(4));
        }
        this.mHandler.removeMessages(100, r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleResumeTopActivities() {
        if (!this.mHandler.hasMessages(102)) {
            this.mHandler.sendEmptyMessage(102);
        }
    }

    void removeSleepTimeouts() {
        this.mHandler.removeMessages(103);
    }

    final void scheduleSleepTimeout() {
        removeSleepTimeouts();
        this.mHandler.sendEmptyMessageDelayed(103, 5000L);
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayAdded(int displayId) {
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityManager", "Display added displayId=" + displayId);
        }
        this.mHandler.sendMessage(this.mHandler.obtainMessage(105, displayId, 0));
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayRemoved(int displayId) {
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityManager", "Display removed displayId=" + displayId);
        }
        this.mHandler.sendMessage(this.mHandler.obtainMessage(107, displayId, 0));
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayChanged(int displayId) {
        if (ActivityManagerDebugConfig.DEBUG_STACK) {
            Slog.v("ActivityManager", "Display changed displayId=" + displayId);
        }
        this.mHandler.sendMessage(this.mHandler.obtainMessage(106, displayId, 0));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDisplayAdded(int displayId) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                getActivityDisplayOrCreateLocked(displayId);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDisplayAdded(int displayId) {
        return getActivityDisplayOrCreateLocked(displayId) != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getActivityDisplay(int displayId) {
        return this.mActivityDisplays.get(displayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getDefaultDisplay() {
        return this.mActivityDisplays.get(0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityDisplay getActivityDisplayOrCreateLocked(int displayId) {
        Display display;
        ActivityDisplay activityDisplay = this.mActivityDisplays.get(displayId);
        if (activityDisplay != null) {
            return activityDisplay;
        }
        if (this.mDisplayManager == null || (display = this.mDisplayManager.getDisplay(displayId)) == null) {
            return null;
        }
        ActivityDisplay activityDisplay2 = new ActivityDisplay(this, display);
        attachDisplay(activityDisplay2);
        calculateDefaultMinimalSizeOfResizeableTasks(activityDisplay2);
        this.mWindowManager.onDisplayAdded(displayId);
        return activityDisplay2;
    }

    @VisibleForTesting
    void attachDisplay(ActivityDisplay display) {
        this.mActivityDisplays.put(display.mDisplayId, display);
    }

    private void calculateDefaultMinimalSizeOfResizeableTasks(ActivityDisplay display) {
        this.mDefaultMinSizeOfResizeableTask = this.mService.mContext.getResources().getDimensionPixelSize(17105037);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDisplayRemoved(int displayId) {
        if (displayId == 0) {
            throw new IllegalArgumentException("Can't remove the primary display.");
        }
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                ActivityDisplay activityDisplay = this.mActivityDisplays.get(displayId);
                if (activityDisplay == null) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                activityDisplay.remove();
                releaseSleepTokens(activityDisplay);
                this.mActivityDisplays.remove(displayId);
                ActivityManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDisplayChanged(int displayId) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                ActivityDisplay activityDisplay = this.mActivityDisplays.get(displayId);
                if (activityDisplay != null) {
                    if (displayId != 0) {
                        int displayState = activityDisplay.mDisplay.getState();
                        if (displayState == 1 && activityDisplay.mOffToken == null) {
                            activityDisplay.mOffToken = this.mService.acquireSleepToken("Display-off", displayId);
                        } else if (displayState == 2 && activityDisplay.mOffToken != null) {
                            activityDisplay.mOffToken.release();
                            activityDisplay.mOffToken = null;
                        }
                    }
                    activityDisplay.updateBounds();
                }
                this.mWindowManager.onDisplayChanged(displayId);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManagerInternal.SleepToken createSleepTokenLocked(String tag, int displayId) {
        ActivityDisplay display = this.mActivityDisplays.get(displayId);
        if (display == null) {
            throw new IllegalArgumentException("Invalid display: " + displayId);
        }
        SleepTokenImpl token = new SleepTokenImpl(tag, displayId);
        this.mSleepTokens.add(token);
        display.mAllSleepTokens.add(token);
        return token;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeSleepTokenLocked(SleepTokenImpl token) {
        this.mSleepTokens.remove(token);
        ActivityDisplay display = this.mActivityDisplays.get(token.mDisplayId);
        if (display != null) {
            display.mAllSleepTokens.remove(token);
            xpLogger.i("ActivityManager", "removeSleepTokenLocked tokens isEmpty=" + display.mAllSleepTokens.isEmpty() + " token=" + token);
            if (display.mAllSleepTokens.isEmpty()) {
                this.mService.updateSleepIfNeededLocked();
                return;
            }
            Iterator<ActivityManagerInternal.SleepToken> it = display.mAllSleepTokens.iterator();
            while (it.hasNext()) {
                ActivityManagerInternal.SleepToken t = it.next();
                xpLogger.i("ActivityManager", "removeSleepTokenLocked t=" + t);
            }
        }
    }

    private void releaseSleepTokens(ActivityDisplay display) {
        if (display.mAllSleepTokens.isEmpty()) {
            return;
        }
        Iterator<ActivityManagerInternal.SleepToken> it = display.mAllSleepTokens.iterator();
        while (it.hasNext()) {
            ActivityManagerInternal.SleepToken token = it.next();
            this.mSleepTokens.remove(token);
        }
        display.mAllSleepTokens.clear();
        this.mService.updateSleepIfNeededLocked();
    }

    private ActivityManager.StackInfo getStackInfo(ActivityStack stack) {
        String str;
        int displayId = stack.mDisplayId;
        ActivityDisplay display = this.mActivityDisplays.get(displayId);
        ActivityManager.StackInfo info = new ActivityManager.StackInfo();
        stack.getWindowContainerBounds(info.bounds);
        info.displayId = displayId;
        info.stackId = stack.mStackId;
        info.userId = stack.mCurrentUser;
        info.visible = stack.shouldBeVisible(null);
        info.position = display != null ? display.getIndexOf(stack) : 0;
        info.configuration.setTo(stack.getConfiguration());
        ArrayList<TaskRecord> tasks = stack.getAllTasks();
        int numTasks = tasks.size();
        int[] taskIds = new int[numTasks];
        String[] taskNames = new String[numTasks];
        Rect[] taskBounds = new Rect[numTasks];
        int[] taskUserIds = new int[numTasks];
        for (int i = 0; i < numTasks; i++) {
            TaskRecord task = tasks.get(i);
            taskIds[i] = task.taskId;
            if (task.origActivity != null) {
                str = task.origActivity.flattenToString();
            } else if (task.realActivity != null) {
                str = task.realActivity.flattenToString();
            } else {
                str = task.getTopActivity() != null ? task.getTopActivity().packageName : UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
            }
            taskNames[i] = str;
            taskBounds[i] = new Rect();
            task.getWindowContainerBounds(taskBounds[i]);
            taskUserIds[i] = task.userId;
        }
        info.taskIds = taskIds;
        info.taskNames = taskNames;
        info.taskBounds = taskBounds;
        info.taskUserIds = taskUserIds;
        ActivityRecord top = stack.topRunningActivityLocked();
        info.topActivity = top != null ? top.intent.getComponent() : null;
        return info;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.StackInfo getStackInfo(int stackId) {
        ActivityStack stack = getStack(stackId);
        if (stack != null) {
            return getStackInfo(stack);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.StackInfo getStackInfo(int windowingMode, int activityType) {
        ActivityStack stack = getStack(windowingMode, activityType);
        if (stack != null) {
            return getStackInfo(stack);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<ActivityManager.StackInfo> getAllStackInfosLocked() {
        ArrayList<ActivityManager.StackInfo> list = new ArrayList<>();
        for (int displayNdx = 0; displayNdx < this.mActivityDisplays.size(); displayNdx++) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                list.add(getStackInfo(stack));
            }
        }
        return list;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredWindowingMode, int preferredDisplayId, ActivityStack actualStack) {
        handleNonResizableTaskIfNeeded(task, preferredWindowingMode, preferredDisplayId, actualStack, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredWindowingMode, int preferredDisplayId, ActivityStack actualStack, boolean forceNonResizable) {
        boolean isSecondaryDisplayPreferred = (preferredDisplayId == 0 || preferredDisplayId == -1) ? false : true;
        boolean inSplitScreenMode = actualStack != null && actualStack.getDisplay().hasSplitScreenPrimaryStack();
        if ((!inSplitScreenMode && preferredWindowingMode != 3 && !isSecondaryDisplayPreferred) || !task.isActivityTypeStandardOrUndefined()) {
            return;
        }
        if (isSecondaryDisplayPreferred) {
            int actualDisplayId = task.getStack().mDisplayId;
            if (!task.canBeLaunchedOnDisplay(actualDisplayId)) {
                throw new IllegalStateException("Task resolved to incompatible display");
            }
            this.mService.setTaskWindowingMode(task.taskId, 4, true);
            if (preferredDisplayId != actualDisplayId) {
                this.mService.mTaskChangeNotificationController.notifyActivityLaunchOnSecondaryDisplayFailed();
                return;
            }
        }
        if (!task.supportsSplitScreenWindowingMode() || forceNonResizable) {
            this.mService.mTaskChangeNotificationController.notifyActivityDismissingDockedStack();
            ActivityStack dockedStack = task.getStack().getDisplay().getSplitScreenPrimaryStack();
            if (dockedStack != null) {
                moveTasksToFullscreenStackLocked(dockedStack, actualStack == dockedStack);
                return;
            }
            return;
        }
        ActivityRecord topActivity = task.getTopActivity();
        if (topActivity != null && topActivity.isNonResizableOrForcedResizable() && !topActivity.noDisplay) {
            String packageName = topActivity.appInfo.packageName;
            int reason = isSecondaryDisplayPreferred ? 2 : 1;
            this.mService.mTaskChangeNotificationController.notifyActivityForcedResizable(task.taskId, reason, packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void activityRelaunchedLocked(IBinder token) {
        this.mWindowManager.notifyAppRelaunchingFinished(token);
        ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r != null && r.getStack().shouldSleepOrShutDownActivities()) {
            r.setSleeping(true, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void activityRelaunchingLocked(ActivityRecord r) {
        this.mWindowManager.notifyAppRelaunching(r.appToken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void logStackState() {
        this.mActivityMetricsLogger.logWindowState();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleUpdateMultiWindowMode(TaskRecord task) {
        if (task.getStack().deferScheduleMultiWindowModeChanged()) {
            return;
        }
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = task.mActivities.get(i);
            if (r.app != null && r.app.thread != null) {
                this.mMultiWindowModeChangedActivities.add(r);
            }
        }
        if (!this.mHandler.hasMessages(114)) {
            this.mHandler.sendEmptyMessage(114);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleUpdatePictureInPictureModeIfNeeded(TaskRecord task, ActivityStack prevStack) {
        ActivityStack stack = task.getStack();
        if (prevStack != null && prevStack != stack) {
            if (!prevStack.inPinnedWindowingMode() && !stack.inPinnedWindowingMode()) {
                return;
            }
            scheduleUpdatePictureInPictureModeIfNeeded(task, stack.getOverrideBounds());
        }
    }

    void scheduleUpdatePictureInPictureModeIfNeeded(TaskRecord task, Rect targetStackBounds) {
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = task.mActivities.get(i);
            if (r.app != null && r.app.thread != null) {
                this.mPipModeChangedActivities.add(r);
                this.mMultiWindowModeChangedActivities.remove(r);
            }
        }
        this.mPipModeChangedTargetStackBounds = targetStackBounds;
        if (!this.mHandler.hasMessages(115)) {
            this.mHandler.sendEmptyMessage(115);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updatePictureInPictureMode(TaskRecord task, Rect targetStackBounds, boolean forceUpdate) {
        this.mHandler.removeMessages(115);
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = task.mActivities.get(i);
            if (r.app != null && r.app.thread != null) {
                r.updatePictureInPictureMode(targetStackBounds, forceUpdate);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDockedStackMinimized(boolean minimized) {
        this.mIsDockMinimized = minimized;
        if (this.mIsDockMinimized) {
            ActivityStack current = getFocusedStack();
            if (current.inSplitScreenPrimaryWindowingMode()) {
                current.adjustFocusToNextFocusableStack("setDockedStackMinimized");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void wakeUp(String reason) {
        PowerManager powerManager = this.mPowerManager;
        long uptimeMillis = SystemClock.uptimeMillis();
        powerManager.wakeUp(uptimeMillis, "android.server.am:TURN_ON:" + reason);
    }

    private void beginDeferResume() {
        this.mDeferResumeCount++;
    }

    private void endDeferResume() {
        this.mDeferResumeCount--;
    }

    private boolean readyToResume() {
        return this.mDeferResumeCount == 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ActivityStackSupervisorHandler extends Handler {
        public ActivityStackSupervisorHandler(Looper looper) {
            super(looper);
        }

        void activityIdleInternal(ActivityRecord r, boolean processPausingActivities) {
            synchronized (ActivityStackSupervisor.this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityStackSupervisor.this.activityIdleInternalLocked(r != null ? r.appToken : null, true, processPausingActivities, null);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i != 112) {
                switch (i) {
                    case 100:
                        if (ActivityManagerDebugConfig.DEBUG_IDLE) {
                            Slog.d("ActivityManager", "handleMessage: IDLE_TIMEOUT_MSG: r=" + msg.obj);
                        }
                        activityIdleInternal((ActivityRecord) msg.obj, true);
                        return;
                    case 101:
                        if (ActivityManagerDebugConfig.DEBUG_IDLE) {
                            Slog.d("ActivityManager", "handleMessage: IDLE_NOW_MSG: r=" + msg.obj);
                        }
                        activityIdleInternal((ActivityRecord) msg.obj, false);
                        return;
                    case 102:
                        synchronized (ActivityStackSupervisor.this.mService) {
                            try {
                                ActivityManagerService.boostPriorityForLockedSection();
                                ActivityStackSupervisor.this.resumeFocusedStackTopActivityLocked();
                            } finally {
                                ActivityManagerService.resetPriorityAfterLockedSection();
                            }
                        }
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        return;
                    case 103:
                        synchronized (ActivityStackSupervisor.this.mService) {
                            try {
                                ActivityManagerService.boostPriorityForLockedSection();
                                if (ActivityStackSupervisor.this.mService.isSleepingOrShuttingDownLocked()) {
                                    Slog.w("ActivityManager", "Sleep timeout!  Sleeping now.");
                                    ActivityStackSupervisor.this.checkReadyForSleepLocked(false);
                                }
                            } finally {
                                ActivityManagerService.resetPriorityAfterLockedSection();
                            }
                        }
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        return;
                    case 104:
                        synchronized (ActivityStackSupervisor.this.mService) {
                            try {
                                ActivityManagerService.boostPriorityForLockedSection();
                                if (ActivityStackSupervisor.this.mLaunchingActivity.isHeld()) {
                                    Slog.w("ActivityManager", "Launch timeout has expired, giving up wake lock!");
                                    ActivityStackSupervisor.this.mLaunchingActivity.release();
                                }
                            } finally {
                                ActivityManagerService.resetPriorityAfterLockedSection();
                            }
                        }
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        return;
                    case 105:
                        ActivityStackSupervisor.this.handleDisplayAdded(msg.arg1);
                        return;
                    case 106:
                        ActivityStackSupervisor.this.handleDisplayChanged(msg.arg1);
                        return;
                    case 107:
                        ActivityStackSupervisor.this.handleDisplayRemoved(msg.arg1);
                        return;
                    default:
                        switch (i) {
                            case 114:
                                synchronized (ActivityStackSupervisor.this.mService) {
                                    try {
                                        ActivityManagerService.boostPriorityForLockedSection();
                                        for (int i2 = ActivityStackSupervisor.this.mMultiWindowModeChangedActivities.size() - 1; i2 >= 0; i2--) {
                                            ActivityStackSupervisor.this.mMultiWindowModeChangedActivities.remove(i2).updateMultiWindowMode();
                                        }
                                    } finally {
                                    }
                                }
                                ActivityManagerService.resetPriorityAfterLockedSection();
                                return;
                            case 115:
                                synchronized (ActivityStackSupervisor.this.mService) {
                                    try {
                                        ActivityManagerService.boostPriorityForLockedSection();
                                        int i3 = ActivityStackSupervisor.this.mPipModeChangedActivities.size() - 1;
                                        while (true) {
                                            int i4 = i3;
                                            if (i4 >= 0) {
                                                ActivityStackSupervisor.this.mPipModeChangedActivities.remove(i4).updatePictureInPictureMode(ActivityStackSupervisor.this.mPipModeChangedTargetStackBounds, false);
                                                i3 = i4 - 1;
                                            }
                                        }
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
            synchronized (ActivityStackSupervisor.this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityRecord r = ActivityRecord.forTokenLocked((IBinder) msg.obj);
                    if (r != null) {
                        ActivityStackSupervisor.this.handleLaunchTaskBehindCompleteLocked(r);
                    }
                } finally {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack findStackBehind(ActivityStack stack) {
        ActivityDisplay display = this.mActivityDisplays.get(0);
        if (display == null) {
            return null;
        }
        for (int i = display.getChildCount() - 1; i >= 0; i--) {
            if (display.getChildAt(i) == stack && i > 0) {
                return display.getChildAt(i - 1);
            }
        }
        throw new IllegalStateException("Failed to find a stack behind stack=" + stack + " in=" + display);
    }

    void setResizingDuringAnimation(TaskRecord task) {
        this.mResizingTasksDuringAnimation.add(Integer.valueOf(task.taskId));
        task.setTaskDockedResizing(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:71:0x019c A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:74:0x01b4  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int startActivityFromRecents(int r29, int r30, int r31, com.android.server.am.SafeActivityOptions r32) {
        /*
            Method dump skipped, instructions count: 487
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityStackSupervisor.startActivityFromRecents(int, int, int, com.android.server.am.SafeActivityOptions):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<IBinder> getTopVisibleActivities() {
        ActivityRecord top;
        ArrayList<IBinder> topActivityTokens = new ArrayList<>();
        for (int i = this.mActivityDisplays.size() - 1; i >= 0; i--) {
            ActivityDisplay display = this.mActivityDisplays.valueAt(i);
            for (int j = display.getChildCount() - 1; j >= 0; j--) {
                ActivityStack stack = display.getChildAt(j);
                if (stack.shouldBeVisible(null) && (top = stack.getTopActivity()) != null) {
                    if (stack == this.mFocusedStack) {
                        topActivityTokens.add(0, top.appToken);
                    } else {
                        topActivityTokens.add(top.appToken);
                    }
                }
            }
        }
        return topActivityTokens;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class WaitInfo {
        private final WaitResult mResult;
        private final long mStartTimeMs;
        private final ComponentName mTargetComponent;

        WaitInfo(ComponentName targetComponent, WaitResult result, long startTimeMs) {
            this.mTargetComponent = targetComponent;
            this.mResult = result;
            this.mStartTimeMs = startTimeMs;
        }

        public boolean matches(ComponentName targetComponent) {
            return this.mTargetComponent == null || this.mTargetComponent.equals(targetComponent);
        }

        public WaitResult getResult() {
            return this.mResult;
        }

        public long getStartTime() {
            return this.mStartTimeMs;
        }

        public ComponentName getComponent() {
            return this.mTargetComponent;
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "WaitInfo:");
            pw.println(prefix + "  mTargetComponent=" + this.mTargetComponent);
            StringBuilder sb = new StringBuilder();
            sb.append(prefix);
            sb.append("  mResult=");
            pw.println(sb.toString());
            this.mResult.dump(pw, prefix);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SleepTokenImpl extends ActivityManagerInternal.SleepToken {
        private final long mAcquireTime = SystemClock.uptimeMillis();
        private final int mDisplayId;
        private final String mTag;

        public SleepTokenImpl(String tag, int displayId) {
            this.mTag = tag;
            this.mDisplayId = displayId;
        }

        public void release() {
            synchronized (ActivityStackSupervisor.this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityStackSupervisor.this.removeSleepTokenLocked(this);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public String toString() {
            return "{\"" + this.mTag + "\", display " + this.mDisplayId + ", acquire at " + TimeUtils.formatUptime(this.mAcquireTime) + "}";
        }
    }
}
