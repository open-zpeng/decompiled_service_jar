package com.android.server.wm;

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.ProfilerInfo;
import android.app.WaitResult;
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
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.UserState;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.wm.ActivityStack;
import com.android.server.wm.RecentTasks;
import com.android.server.wm.SharedDisplayContainer;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;

/* loaded from: classes2.dex */
public class ActivityStackSupervisor implements RecentTasks.Callbacks {
    private static final ArrayMap<String, String> ACTION_TO_RUNTIME_PERMISSION = new ArrayMap<>();
    private static final int ACTIVITY_RESTRICTION_APPOP = 2;
    private static final int ACTIVITY_RESTRICTION_NONE = 0;
    private static final int ACTIVITY_RESTRICTION_PERMISSION = 1;
    static final boolean DEFER_RESUME = true;
    static final int IDLE_NOW_MSG = 201;
    static final int IDLE_TIMEOUT = 10000;
    static final int IDLE_TIMEOUT_MSG = 200;
    static final int LAUNCH_TASK_BEHIND_COMPLETE = 212;
    static final int LAUNCH_TIMEOUT = 10000;
    static final int LAUNCH_TIMEOUT_MSG = 204;
    private static final int MAX_TASK_IDS_PER_USER = 100000;
    static final boolean ON_TOP = true;
    static final boolean PAUSE_IMMEDIATELY = true;
    static final boolean PRESERVE_WINDOWS = true;
    static final boolean REMOVE_FROM_RECENTS = true;
    static final int REPORT_HOME_CHANGED_MSG = 216;
    static final int REPORT_MULTI_WINDOW_MODE_CHANGED_MSG = 214;
    static final int REPORT_PIP_MODE_CHANGED_MSG = 215;
    static final int RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG = 213;
    static final int RESUME_TOP_ACTIVITY_MSG = 202;
    static final int SLEEP_TIMEOUT = 5000;
    static final int SLEEP_TIMEOUT_MSG = 203;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_IDLE = "ActivityTaskManager";
    private static final String TAG_PAUSE = "ActivityTaskManager";
    private static final String TAG_RECENTS = "ActivityTaskManager";
    private static final String TAG_STACK = "ActivityTaskManager";
    private static final String TAG_SWITCH = "ActivityTaskManager";
    static final String TAG_TASKS = "ActivityTaskManager";
    static final int TOP_RESUMED_STATE_LOSS_TIMEOUT = 500;
    static final int TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG = 217;
    static final boolean VALIDATE_WAKE_LOCK_CALLER = false;
    private ActivityMetricsLogger mActivityMetricsLogger;
    boolean mAppVisibilitiesChangedSinceLastPause;
    private int mDeferResumeCount;
    private boolean mDockedStackResizing;
    PowerManager.WakeLock mGoingToSleepWakeLock;
    final ActivityStackSupervisorHandler mHandler;
    private boolean mHasPendingDockedBounds;
    private boolean mInitialized;
    private KeyguardController mKeyguardController;
    private LaunchParamsController mLaunchParamsController;
    LaunchParamsPersister mLaunchParamsPersister;
    PowerManager.WakeLock mLaunchingActivityWakeLock;
    final Looper mLooper;
    private Rect mPendingDockedBounds;
    private Rect mPendingTempDockedTaskBounds;
    private Rect mPendingTempDockedTaskInsetBounds;
    private Rect mPendingTempOtherTaskBounds;
    private Rect mPendingTempOtherTaskInsetBounds;
    PersisterQueue mPersisterQueue;
    Rect mPipModeChangedTargetStackBounds;
    private PowerManager mPowerManager;
    RecentTasks mRecentTasks;
    RootActivityContainer mRootActivityContainer;
    RunningTasks mRunningTasks;
    final ActivityTaskManagerService mService;
    private ComponentName mSystemChooserActivity;
    private ActivityRecord mTopResumedActivity;
    private boolean mTopResumedActivityWaitingForPrev;
    WindowManagerService mWindowManager;
    private final SparseIntArray mCurTaskIdForUser = new SparseIntArray(20);
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
    private final Rect tempRect = new Rect();
    private final ActivityOptions mTmpOptions = ActivityOptions.makeBasic();
    private final ArraySet<Integer> mResizingTasksDuringAnimation = new ArraySet<>();
    private boolean mAllowDockedStackResize = true;

    static {
        ACTION_TO_RUNTIME_PERMISSION.put("android.media.action.IMAGE_CAPTURE", "android.permission.CAMERA");
        ACTION_TO_RUNTIME_PERMISSION.put("android.media.action.VIDEO_CAPTURE", "android.permission.CAMERA");
        ACTION_TO_RUNTIME_PERMISSION.put("android.intent.action.CALL", "android.permission.CALL_PHONE");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canPlaceEntityOnDisplay(int displayId, int callingPid, int callingUid, ActivityInfo activityInfo) {
        if (displayId == 0) {
            return true;
        }
        return this.mService.mSupportsMultiDisplay && isCallerAllowedToLaunchOnDisplay(callingPid, callingUid, displayId, activityInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static class PendingActivityLaunch {
        final WindowProcessController callerApp;
        final ActivityRecord r;
        final ActivityRecord sourceRecord;
        final ActivityStack stack;
        final int startFlags;

        /* JADX INFO: Access modifiers changed from: package-private */
        public PendingActivityLaunch(ActivityRecord _r, ActivityRecord _sourceRecord, int _startFlags, ActivityStack _stack, WindowProcessController app) {
            this.r = _r;
            this.sourceRecord = _sourceRecord;
            this.startFlags = _startFlags;
            this.stack = _stack;
            this.callerApp = app;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void sendErrorResult(String message) {
            try {
                if (this.callerApp != null && this.callerApp.hasThread()) {
                    this.callerApp.getThread().scheduleCrash(message);
                }
            } catch (RemoteException e) {
                Slog.e("ActivityTaskManager", "Exception scheduling crash of failed activity launcher sourceRecord=" + this.sourceRecord, e);
            }
        }
    }

    public ActivityStackSupervisor(ActivityTaskManagerService service, Looper looper) {
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
        this.mPersisterQueue = new PersisterQueue();
        this.mLaunchParamsPersister = new LaunchParamsPersister(this.mPersisterQueue, this);
        this.mLaunchParamsController = new LaunchParamsController(this.mService, this.mLaunchParamsPersister);
        this.mLaunchParamsController.registerDefaultModifiers(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSystemReady() {
        this.mLaunchParamsPersister.onSystemReady();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUserUnlocked(int userId) {
        this.mPersisterQueue.startPersisting();
        this.mLaunchParamsPersister.onUnlockUser(userId);
    }

    public ActivityMetricsLogger getActivityMetricsLogger() {
        return this.mActivityMetricsLogger;
    }

    public KeyguardController getKeyguardController() {
        return this.mKeyguardController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ComponentName getSystemChooserActivity() {
        if (this.mSystemChooserActivity == null) {
            this.mSystemChooserActivity = ComponentName.unflattenFromString(this.mService.mContext.getResources().getString(17039688));
        }
        return this.mSystemChooserActivity;
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
        this.mPowerManager = (PowerManager) this.mService.mContext.getSystemService(PowerManager.class);
        this.mGoingToSleepWakeLock = this.mPowerManager.newWakeLock(1, "ActivityManager-Sleep");
        this.mLaunchingActivityWakeLock = this.mPowerManager.newWakeLock(1, "*launch*");
        this.mLaunchingActivityWakeLock.setReferenceCounted(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowManager(WindowManagerService wm) {
        this.mWindowManager = wm;
        getKeyguardController().setWindowManager(wm);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveRecentsStackToFront(String reason) {
        ActivityStack recentsStack = this.mRootActivityContainer.getDefaultDisplay().getStack(0, 3);
        if (recentsStack != null) {
            recentsStack.moveToFront(reason);
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
            if (this.mRecentTasks.containsTaskId(candidateTaskId, userId) || this.mRootActivityContainer.anyTaskForId(candidateTaskId, 1) != null) {
                candidateTaskId = nextTaskIdForUser(candidateTaskId, userId);
            } else {
                this.mCurTaskIdForUser.put(userId, candidateTaskId);
                return candidateTaskId;
            }
        } while (candidateTaskId != currentTaskId);
        throw new IllegalStateException("Cannot get an available task id. Reached limit of 100000 running tasks per user.");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void waitActivityVisible(ComponentName name, WaitResult result, long startTimeMs) {
        WaitInfo waitInfo = new WaitInfo(name, result, startTimeMs);
        this.mWaitingForActivityVisible.add(waitInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cleanupActivity(ActivityRecord r) {
        this.mFinishingActivities.remove(r);
        stopWaitingForActivityVisible(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopWaitingForActivityVisible(ActivityRecord r) {
        boolean changed = false;
        for (int i = this.mWaitingForActivityVisible.size() - 1; i >= 0; i--) {
            WaitInfo w = this.mWaitingForActivityVisible.get(i);
            if (w.matches(r.mActivityComponent)) {
                WaitResult result = w.getResult();
                changed = true;
                result.timeout = false;
                result.who = w.getComponent();
                result.totalTime = SystemClock.uptimeMillis() - w.getStartTime();
                this.mWaitingForActivityVisible.remove(w);
            }
        }
        if (changed) {
            this.mService.mGlobalLock.notifyAll();
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
                    w.who = r.mActivityComponent;
                }
            }
        }
        if (changed) {
            this.mService.mGlobalLock.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportActivityLaunchedLocked(boolean timeout, ActivityRecord r, long totalTime, int launchState) {
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
                w.launchState = launchState;
            }
        }
        if (changed) {
            this.mService.mGlobalLock.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityInfo resolveActivity(Intent intent, ResolveInfo rInfo, int startFlags, ProfilerInfo profilerInfo) {
        ActivityInfo aInfo = rInfo != null ? rInfo.activityInfo : null;
        if (aInfo != null) {
            intent.setComponent(new ComponentName(aInfo.applicationInfo.packageName, aInfo.name));
            if (!aInfo.processName.equals("system") && ((startFlags & 14) != 0 || profilerInfo != null)) {
                synchronized (this.mService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        Message msg = PooledLambda.obtainMessage(new QuintConsumer() { // from class: com.android.server.wm.-$$Lambda$8ew6SY_v_7ex9pwFGDswbkGWuXc
                            public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5) {
                                ((ActivityManagerInternal) obj).setDebugFlagsForStartingActivity((ActivityInfo) obj2, ((Integer) obj3).intValue(), (ProfilerInfo) obj4, (WindowManagerGlobalLock) obj5);
                            }
                        }, this.mService.mAmInternal, aInfo, Integer.valueOf(startFlags), profilerInfo, this.mService.mGlobalLock);
                        this.mService.mH.sendMessage(msg);
                        try {
                            this.mService.mGlobalLock.wait();
                        } catch (InterruptedException e) {
                        }
                    } catch (Throwable th) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
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
        int modifiedFlags;
        int modifiedFlags2;
        try {
            Trace.traceBegin(64L, "resolveIntent");
            modifiedFlags = flags | 65536 | 1024;
        } catch (Throwable th) {
            th = th;
        }
        try {
            if (!intent.isWebIntent() && (intent.getFlags() & 2048) == 0) {
                modifiedFlags2 = modifiedFlags;
                long token = Binder.clearCallingIdentity();
                ResolveInfo resolveIntent = this.mService.getPackageManagerInternalLocked().resolveIntent(intent, resolvedType, modifiedFlags2, userId, true, filterCallingUid);
                Binder.restoreCallingIdentity(token);
                Trace.traceEnd(64L);
                return resolveIntent;
            }
            ResolveInfo resolveIntent2 = this.mService.getPackageManagerInternalLocked().resolveIntent(intent, resolvedType, modifiedFlags2, userId, true, filterCallingUid);
            Binder.restoreCallingIdentity(token);
            Trace.traceEnd(64L);
            return resolveIntent2;
        } catch (Throwable th2) {
            th = th2;
            Trace.traceEnd(64L);
            throw th;
        }
        modifiedFlags2 = modifiedFlags | DumpState.DUMP_VOLUMES;
        long token2 = Binder.clearCallingIdentity();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityInfo resolveActivity(Intent intent, String resolvedType, int startFlags, ProfilerInfo profilerInfo, int userId, int filterCallingUid) {
        ResolveInfo rInfo = resolveIntent(intent, resolvedType, userId, 0, filterCallingUid);
        return resolveActivity(intent, rInfo, startFlags, profilerInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Can't wrap try/catch for region: R(30:12|13|14|(3:184|185|(24:187|17|18|(2:180|181)|20|(1:22)|23|(1:25)|26|(1:28)(1:179)|29|30|(1:178)|34|35|(1:37)|38|39|(1:177)|47|48|(38:50|(3:165|166|167)(1:52)|53|(1:55)|56|57|(1:59)|75|76|77|78|79|80|81|82|83|84|85|86|87|88|89|90|(2:141|142)(1:92)|93|94|(3:116|117|(9:119|(3:121|(1:136)(4:125|126|127|128)|129)(1:137)|97|(1:99)|(3:112|(1:114)|115)(1:103)|104|(1:106)|107|(2:109|110)(1:111)))|96|97|(0)|(1:101)|112|(0)|115|104|(0)|107|(0)(0))(4:168|169|170|171)|67|(3:69|70|71)(3:72|73|74)))|16|17|18|(0)|20|(0)|23|(0)|26|(0)(0)|29|30|(1:32)|178|34|35|(0)|38|39|(1:41)|177|47|48|(0)(0)|67|(0)(0)) */
    /* JADX WARN: Code restructure failed: missing block: B:155:0x039d, code lost:
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:156:0x039e, code lost:
        r34 = r0;
        r6 = "ActivityTaskManager";
        r4 = r13;
     */
    /* JADX WARN: Removed duplicated region for block: B:118:0x02f9  */
    /* JADX WARN: Removed duplicated region for block: B:125:0x0322  */
    /* JADX WARN: Removed duplicated region for block: B:129:0x0353  */
    /* JADX WARN: Removed duplicated region for block: B:132:0x0360  */
    /* JADX WARN: Removed duplicated region for block: B:150:0x038d  */
    /* JADX WARN: Removed duplicated region for block: B:159:0x03aa A[Catch: all -> 0x03ed, TRY_LEAVE, TryCatch #4 {all -> 0x03ed, blocks: (B:157:0x03a6, B:159:0x03aa, B:163:0x03e6, B:164:0x03ec, B:151:0x0395, B:152:0x039a), top: B:175:0x0127 }] */
    /* JADX WARN: Removed duplicated region for block: B:162:0x03e3  */
    /* JADX WARN: Removed duplicated region for block: B:186:0x006a A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:201:? A[RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:28:0x0077 A[Catch: all -> 0x006e, TRY_LEAVE, TryCatch #8 {all -> 0x006e, blocks: (B:23:0x006a, B:28:0x0077, B:31:0x008a, B:34:0x0093, B:40:0x00a2, B:46:0x00f3, B:50:0x0116, B:52:0x011a, B:54:0x011e, B:61:0x0132, B:68:0x014b, B:72:0x01ae), top: B:186:0x006a }] */
    /* JADX WARN: Removed duplicated region for block: B:31:0x008a A[Catch: all -> 0x006e, TRY_ENTER, TRY_LEAVE, TryCatch #8 {all -> 0x006e, blocks: (B:23:0x006a, B:28:0x0077, B:31:0x008a, B:34:0x0093, B:40:0x00a2, B:46:0x00f3, B:50:0x0116, B:52:0x011a, B:54:0x011e, B:61:0x0132, B:68:0x014b, B:72:0x01ae), top: B:186:0x006a }] */
    /* JADX WARN: Removed duplicated region for block: B:34:0x0093 A[Catch: all -> 0x006e, TRY_ENTER, TRY_LEAVE, TryCatch #8 {all -> 0x006e, blocks: (B:23:0x006a, B:28:0x0077, B:31:0x008a, B:34:0x0093, B:40:0x00a2, B:46:0x00f3, B:50:0x0116, B:52:0x011a, B:54:0x011e, B:61:0x0132, B:68:0x014b, B:72:0x01ae), top: B:186:0x006a }] */
    /* JADX WARN: Removed duplicated region for block: B:36:0x009a  */
    /* JADX WARN: Removed duplicated region for block: B:46:0x00f3 A[Catch: all -> 0x006e, TRY_ENTER, TRY_LEAVE, TryCatch #8 {all -> 0x006e, blocks: (B:23:0x006a, B:28:0x0077, B:31:0x008a, B:34:0x0093, B:40:0x00a2, B:46:0x00f3, B:50:0x0116, B:52:0x011a, B:54:0x011e, B:61:0x0132, B:68:0x014b, B:72:0x01ae), top: B:186:0x006a }] */
    /* JADX WARN: Removed duplicated region for block: B:59:0x012d  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean realStartActivityLocked(com.android.server.wm.ActivityRecord r40, com.android.server.wm.WindowProcessController r41, boolean r42, boolean r43) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 1022
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityStackSupervisor.realStartActivityLocked(com.android.server.wm.ActivityRecord, com.android.server.wm.WindowProcessController, boolean, boolean):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateHomeProcess(WindowProcessController app) {
        if (app != null && this.mService.mHomeProcess != app) {
            if (!this.mHandler.hasMessages(REPORT_HOME_CHANGED_MSG)) {
                this.mHandler.sendEmptyMessage(REPORT_HOME_CHANGED_MSG);
            }
            this.mService.mHomeProcess = app;
        }
    }

    private void logIfTransactionTooLarge(Intent intent, Bundle icicle) {
        Bundle extras;
        int extrasSize = 0;
        if (intent != null && (extras = intent.getExtras()) != null) {
            extrasSize = extras.getSize();
        }
        int icicleSize = icicle == null ? 0 : icicle.getSize();
        if (extrasSize + icicleSize > 200000) {
            Slog.e("ActivityTaskManager", "Transaction too large, intent: " + intent + ", extras size: " + extrasSize + ", icicle size: " + icicleSize);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startSpecificActivityLocked(ActivityRecord r, boolean andResume, boolean checkConfig) {
        WindowProcessController wpc = this.mService.getProcessController(r.processName, r.info.applicationInfo.uid);
        boolean knownToBeDead = false;
        if (wpc != null && wpc.hasThread()) {
            try {
                realStartActivityLocked(r, wpc, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Slog.w("ActivityTaskManager", "Exception when starting activity " + r.intent.getComponent().flattenToShortString(), e);
                knownToBeDead = true;
            }
        }
        if (getKeyguardController().isKeyguardLocked()) {
            r.notifyUnknownVisibilityLaunched();
        }
        try {
            if (Trace.isTagEnabled(64L)) {
                Trace.traceBegin(64L, "dispatchingStartProcess:" + r.processName);
            }
            Message msg = PooledLambda.obtainMessage(new HexConsumer() { // from class: com.android.server.wm.-$$Lambda$3W4Y_XVQUddVKzLjibuHW7h0R1g
                public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5, Object obj6) {
                    ((ActivityManagerInternal) obj).startProcess((String) obj2, (ApplicationInfo) obj3, ((Boolean) obj4).booleanValue(), (String) obj5, (ComponentName) obj6);
                }
            }, this.mService.mAmInternal, r.processName, r.info.applicationInfo, Boolean.valueOf(knownToBeDead), "activity", r.intent.getComponent());
            this.mService.mH.sendMessage(msg);
        } finally {
            Trace.traceEnd(64L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkStartAnyActivityPermission(Intent intent, ActivityInfo aInfo, String resultWho, int requestCode, int callingPid, int callingUid, String callingPackage, boolean ignoreTargetSecurity, boolean launchingInTask, WindowProcessController callerApp, ActivityRecord resultRecord, ActivityStack resultStack) {
        String msg;
        boolean isCallerRecents = this.mService.getRecentTasks() != null && this.mService.getRecentTasks().isCallerRecents(callingUid);
        ActivityTaskManagerService activityTaskManagerService = this.mService;
        int startAnyPerm = ActivityTaskManagerService.checkPermission("android.permission.START_ANY_ACTIVITY", callingPid, callingUid);
        if (startAnyPerm != 0) {
            if (isCallerRecents && launchingInTask) {
                return true;
            }
            int componentRestriction = getComponentRestrictionForCallingPackage(aInfo, callingPackage, callingPid, callingUid, ignoreTargetSecurity);
            int actionRestriction = getActionRestrictionForCallingPackage(intent.getAction(), callingPackage, callingPid, callingUid);
            if (componentRestriction != 1 && actionRestriction != 1) {
                if (actionRestriction == 2) {
                    String message = "Appop Denial: starting " + intent.toString() + " from " + callerApp + " (pid=" + callingPid + ", uid=" + callingUid + ") requires " + AppOpsManager.permissionToOp(ACTION_TO_RUNTIME_PERMISSION.get(intent.getAction()));
                    Slog.w("ActivityTaskManager", message);
                    return false;
                } else if (componentRestriction == 2) {
                    String message2 = "Appop Denial: starting " + intent.toString() + " from " + callerApp + " (pid=" + callingPid + ", uid=" + callingUid + ") requires appop " + AppOpsManager.permissionToOp(aInfo.permission);
                    Slog.w("ActivityTaskManager", message2);
                    return false;
                } else {
                    return true;
                }
            }
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
            Slog.w("ActivityTaskManager", msg);
            throw new SecurityException(msg);
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCallerAllowedToLaunchOnDisplay(int callingPid, int callingUid, int launchDisplayId, ActivityInfo aInfo) {
        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
            Slog.d("ActivityTaskManager", "Launch on display check: displayId=" + launchDisplayId + " callingPid=" + callingPid + " callingUid=" + callingUid);
        }
        if (callingPid == -1 && callingUid == -1) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityTaskManager", "Launch on display check: no caller info, skip check");
            }
            return true;
        }
        ActivityDisplay activityDisplay = this.mRootActivityContainer.getActivityDisplayOrCreate(launchDisplayId);
        if (activityDisplay == null || activityDisplay.isRemoved()) {
            Slog.w("ActivityTaskManager", "Launch on display check: display not found");
            return false;
        }
        ActivityTaskManagerService activityTaskManagerService = this.mService;
        int startAnyPerm = ActivityTaskManagerService.checkPermission("android.permission.INTERNAL_SYSTEM_WINDOW", callingPid, callingUid);
        if (startAnyPerm == 0) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityTaskManager", "Launch on display check: allow launch any on display");
            }
            return true;
        }
        boolean uidPresentOnDisplay = activityDisplay.isUidPresent(callingUid);
        int displayOwnerUid = activityDisplay.mDisplay.getOwnerUid();
        if (activityDisplay.mDisplay.getType() == 5 && displayOwnerUid != 1000 && displayOwnerUid != aInfo.applicationInfo.uid) {
            if ((aInfo.flags & Integer.MIN_VALUE) == 0) {
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityTaskManager", "Launch on display check: disallow launch on virtual display for not-embedded activity.");
                }
                return false;
            }
            ActivityTaskManagerService activityTaskManagerService2 = this.mService;
            if (ActivityTaskManagerService.checkPermission("android.permission.ACTIVITY_EMBEDDING", callingPid, callingUid) == -1 && !uidPresentOnDisplay) {
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.d("ActivityTaskManager", "Launch on display check: disallow activity embedding without permission.");
                }
                return false;
            }
        }
        if (!activityDisplay.isPrivate()) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityTaskManager", "Launch on display check: allow launch on public display");
            }
            return true;
        } else if (displayOwnerUid == callingUid) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityTaskManager", "Launch on display check: allow launch for owner of the display");
            }
            return true;
        } else if (uidPresentOnDisplay) {
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.d("ActivityTaskManager", "Launch on display check: allow launch for caller present on the display");
            }
            return true;
        } else {
            Slog.w("ActivityTaskManager", "Launch on display check: denied");
            return false;
        }
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
        if (!ignoreTargetSecurity) {
            ActivityTaskManagerService activityTaskManagerService = this.mService;
            if (ActivityTaskManagerService.checkComponentPermission(activityInfo.permission, callingPid, callingUid, activityInfo.applicationInfo.uid, activityInfo.exported) == -1) {
                return 1;
            }
        }
        return (activityInfo.permission == null || (opCode = AppOpsManager.permissionToOpCode(activityInfo.permission)) == -1 || this.mService.getAppOpsService().noteOperation(opCode, callingUid, callingPackage) == 0 || ignoreTargetSecurity) ? 0 : 2;
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
            ActivityTaskManagerService activityTaskManagerService = this.mService;
            if (ActivityTaskManagerService.checkPermission(permission, callingPid, callingUid) == -1) {
                return 1;
            }
            int opCode = AppOpsManager.permissionToOpCode(permission);
            if (opCode == -1 || this.mService.getAppOpsService().noteOperation(opCode, callingUid, callingPackage) == 0) {
                return 0;
            }
            return 2;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i("ActivityTaskManager", "Cannot find package info for " + callingPackage);
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLaunchSource(int uid) {
        this.mLaunchingActivityWakeLock.setWorkSource(new WorkSource(uid));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void acquireLaunchWakelock() {
        this.mLaunchingActivityWakeLock.acquire();
        if (!this.mHandler.hasMessages(LAUNCH_TIMEOUT_MSG)) {
            this.mHandler.sendEmptyMessageDelayed(LAUNCH_TIMEOUT_MSG, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    @GuardedBy({"mService"})
    private boolean checkFinishBootingLocked() {
        boolean booting = this.mService.isBooting();
        boolean enableScreen = false;
        this.mService.setBooting(false);
        if (!this.mService.isBooted()) {
            this.mService.setBooted(true);
            enableScreen = true;
        }
        if (booting || enableScreen) {
            this.mService.postFinishBooting(booting, enableScreen);
        }
        return booting;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v1 */
    /* JADX WARN: Type inference failed for: r0v16 */
    /* JADX WARN: Type inference failed for: r0v2, types: [boolean, int] */
    @GuardedBy({"mService"})
    public final ActivityRecord activityIdleInternalLocked(IBinder token, boolean fromTimeout, boolean processPausingActivities, Configuration config) {
        boolean z;
        ?? r0;
        if (ActivityTaskManagerDebugConfig.DEBUG_ALL) {
            Slog.v("ActivityTaskManager", "Activity idle: " + token);
        }
        ArrayList<ActivityRecord> finishes = null;
        ArrayList<UserState> startingUsers = null;
        boolean booting = false;
        boolean activityRemoved = false;
        ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            z = true;
            r0 = 0;
        } else {
            if (ActivityTaskManagerDebugConfig.DEBUG_IDLE) {
                Slog.d("ActivityTaskManager", "activityIdleInternalLocked: Callers=" + Debug.getCallers(4));
            }
            this.mHandler.removeMessages(200, r);
            r.finishLaunchTickingLocked();
            if (!fromTimeout) {
                z = true;
            } else {
                z = true;
                reportActivityLaunchedLocked(fromTimeout, r, -1L, -1);
            }
            if (config != null) {
                r.setLastReportedGlobalConfiguration(config);
            }
            r.idle = z;
            if ((this.mService.isBooting() && this.mRootActivityContainer.allResumedActivitiesIdle()) || fromTimeout) {
                booting = checkFinishBootingLocked();
            }
            r0 = 0;
            r.mRelaunchReason = 0;
        }
        if (this.mRootActivityContainer.allResumedActivitiesIdle()) {
            if (r != null) {
                this.mService.scheduleAppGcsLocked();
            }
            if (this.mLaunchingActivityWakeLock.isHeld()) {
                this.mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                this.mLaunchingActivityWakeLock.release();
            }
            this.mRootActivityContainer.ensureActivitiesVisible(null, r0, r0);
        }
        ArrayList<ActivityRecord> stops = processStoppingActivitiesLocked(r, z, processPausingActivities);
        int NS = stops != null ? stops.size() : r0;
        int NF = this.mFinishingActivities.size();
        if (NF > 0) {
            finishes = new ArrayList<>(this.mFinishingActivities);
            this.mFinishingActivities.clear();
        }
        if (this.mStartingUsers.size() > 0) {
            startingUsers = new ArrayList<>(this.mStartingUsers);
            this.mStartingUsers.clear();
        }
        for (int i = 0; i < NS; i++) {
            r = stops.get(i);
            ActivityStack stack = r.getActivityStack();
            if (stack != 0) {
                if (r.finishing) {
                    stack.finishCurrentActivityLocked(r, r0, r0, "activityIdleInternalLocked");
                } else {
                    stack.stopActivityLocked(r);
                }
            }
        }
        for (int i2 = 0; i2 < NF; i2++) {
            r = finishes.get(i2);
            ActivityStack stack2 = r.getActivityStack();
            if (stack2 != null) {
                activityRemoved = stack2.destroyActivityLocked(r, z, "finish-idle") | activityRemoved;
            }
        }
        if (!booting && startingUsers != null) {
            for (int i3 = 0; i3 < startingUsers.size(); i3++) {
                this.mService.mAmInternal.finishUserSwitch(startingUsers.get(i3));
            }
        }
        this.mService.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityStackSupervisor$28Zuzbi6usdgbDcOi8hrJg6nZO0
            @Override // java.lang.Runnable
            public final void run() {
                ActivityStackSupervisor.this.lambda$activityIdleInternalLocked$0$ActivityStackSupervisor();
            }
        });
        if (activityRemoved) {
            this.mRootActivityContainer.resumeFocusedStacksTopActivities();
        }
        return r;
    }

    public /* synthetic */ void lambda$activityIdleInternalLocked$0$ActivityStackSupervisor() {
        this.mService.mAmInternal.trimApplications();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void findTaskToMoveToFront(TaskRecord task, int flags, ActivityOptions options, String reason, boolean forceNonResizeable) {
        AppTimeTracker appTimeTracker;
        Rect bounds;
        ActivityStack currentStack = task.getStack();
        if (currentStack == null) {
            Slog.e("ActivityTaskManager", "findTaskToMoveToFront: can't move task=" + task + " to front. Stack is null");
            return;
        }
        if ((flags & 2) == 0) {
            this.mUserLeaving = true;
        }
        String reason2 = reason + " findTaskToMoveToFront";
        boolean reparented = false;
        if (!task.isResizeable() || !canUseActivityOptionsLaunchBounds(options)) {
            appTimeTracker = null;
        } else {
            Rect bounds2 = options.getLaunchBounds();
            task.updateOverrideConfiguration(bounds2);
            ActivityStack stack = this.mRootActivityContainer.getLaunchStack(null, options, task, true);
            if (stack != currentStack) {
                moveHomeStackToFrontIfNeeded(flags, stack.getDisplay(), reason2);
                bounds = bounds2;
                appTimeTracker = null;
                task.reparent(stack, true, 1, false, true, reason2);
                currentStack = stack;
                reparented = true;
            } else {
                bounds = bounds2;
                appTimeTracker = null;
            }
            if (stack.resizeStackWithLaunchBounds()) {
                this.mRootActivityContainer.resizeStack(stack, bounds, null, null, false, true, false);
            } else {
                task.resizeWindowContainer();
            }
        }
        ActivityStack currentStack2 = currentStack;
        if (!reparented) {
            moveHomeStackToFrontIfNeeded(flags, currentStack2.getDisplay(), reason2);
        }
        ActivityRecord r = task.getTopActivity();
        if (r != null) {
            appTimeTracker = r.appTimeTracker;
        }
        currentStack2.moveTaskToFrontLocked(task, false, options, appTimeTracker, reason2);
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.d("ActivityTaskManager", "findTaskToMoveToFront: moved to front of stack=" + currentStack2);
        }
        handleNonResizableTaskIfNeeded(task, 0, 0, currentStack2, forceNonResizeable);
    }

    private void moveHomeStackToFrontIfNeeded(int flags, ActivityDisplay display, String reason) {
        ActivityStack focusedStack = display.getFocusedStack();
        if ((display.getWindowingMode() == 1 && (flags & 1) != 0) || (focusedStack != null && focusedStack.isActivityTypeRecents())) {
            display.moveHomeStackToFront(reason);
        }
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

    private void deferUpdateRecentsHomeStackBounds() {
        this.mRootActivityContainer.deferUpdateBounds(3);
        this.mRootActivityContainer.deferUpdateBounds(2);
    }

    private void continueUpdateRecentsHomeStackBounds() {
        this.mRootActivityContainer.continueUpdateBounds(3);
        this.mRootActivityContainer.continueUpdateBounds(2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppTransitionDone() {
        continueUpdateRecentsHomeStackBounds();
        for (int i = this.mResizingTasksDuringAnimation.size() - 1; i >= 0; i--) {
            int taskId = this.mResizingTasksDuringAnimation.valueAt(i).intValue();
            TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
            if (task != null) {
                task.setTaskDockedResizing(false);
            }
        }
        this.mResizingTasksDuringAnimation.clear();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: moveTasksToFullscreenStackInSurfaceTransaction */
    public void lambda$moveTasksToFullscreenStackLocked$1$ActivityStackSupervisor(ActivityStack fromStack, int toDisplayId, boolean onTop) {
        int windowingMode;
        boolean inPinnedWindowingMode;
        int i;
        int size;
        ArrayList<TaskRecord> tasks;
        ActivityDisplay toDisplay;
        this.mWindowManager.deferSurfaceLayout();
        try {
            windowingMode = fromStack.getWindowingMode();
            inPinnedWindowingMode = windowingMode == 2;
        } catch (Throwable th) {
            th = th;
        }
        try {
            ActivityDisplay toDisplay2 = this.mRootActivityContainer.getActivityDisplay(toDisplayId);
            if (windowingMode == 3) {
                toDisplay2.onExitingSplitScreenMode();
                for (int i2 = toDisplay2.getChildCount() - 1; i2 >= 0; i2--) {
                    ActivityStack otherStack = toDisplay2.getChildAt(i2);
                    if (otherStack.inSplitScreenSecondaryWindowingMode()) {
                        otherStack.setWindowingMode(0);
                    }
                }
                this.mAllowDockedStackResize = false;
            }
            boolean schedulePictureInPictureModeChange = inPinnedWindowingMode;
            ArrayList<TaskRecord> tasks2 = fromStack.getAllTasks();
            if (!tasks2.isEmpty()) {
                this.mTmpOptions.setLaunchWindowingMode(1);
                int size2 = tasks2.size();
                int i3 = 0;
                while (i3 < size2) {
                    TaskRecord task = tasks2.get(i3);
                    ActivityStack toStack = toDisplay2.getOrCreateStack(null, this.mTmpOptions, task, task.getActivityType(), onTop);
                    if (onTop) {
                        boolean isTopTask = i3 == size2 + (-1);
                        i = i3;
                        size = size2;
                        tasks = tasks2;
                        toDisplay = toDisplay2;
                        task.reparent(toStack, true, 0, isTopTask, true, schedulePictureInPictureModeChange, "moveTasksToFullscreenStack - onTop");
                        MetricsLoggerWrapper.logPictureInPictureFullScreen(this.mService.mContext, task.effectiveUid, task.realActivity.flattenToString());
                    } else {
                        i = i3;
                        size = size2;
                        tasks = tasks2;
                        toDisplay = toDisplay2;
                        task.reparent(toStack, true, 2, false, true, schedulePictureInPictureModeChange, "moveTasksToFullscreenStack - NOT_onTop");
                    }
                    i3 = i + 1;
                    size2 = size;
                    tasks2 = tasks;
                    toDisplay2 = toDisplay;
                }
            }
            this.mRootActivityContainer.ensureActivitiesVisible(null, 0, true);
            this.mRootActivityContainer.resumeFocusedStacksTopActivities();
            this.mAllowDockedStackResize = true;
            this.mWindowManager.continueSurfaceLayout();
        } catch (Throwable th2) {
            th = th2;
            this.mAllowDockedStackResize = true;
            this.mWindowManager.continueSurfaceLayout();
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveTasksToFullscreenStackLocked(ActivityStack fromStack, boolean onTop) {
        moveTasksToFullscreenStackLocked(fromStack, 0, onTop);
    }

    void moveTasksToFullscreenStackLocked(final ActivityStack fromStack, final int toDisplayId, final boolean onTop) {
        this.mWindowManager.inSurfaceTransaction(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityStackSupervisor$PHIj4FpzoLIwUTmMRMOYA9us0rc
            @Override // java.lang.Runnable
            public final void run() {
                ActivityStackSupervisor.this.lambda$moveTasksToFullscreenStackLocked$1$ActivityStackSupervisor(fromStack, toDisplayId, onTop);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:47:0x00d6  */
    /* JADX WARN: Removed duplicated region for block: B:52:0x00de  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void resizeDockedStackLocked(android.graphics.Rect r22, android.graphics.Rect r23, android.graphics.Rect r24, android.graphics.Rect r25, android.graphics.Rect r26, boolean r27, boolean r28) {
        /*
            Method dump skipped, instructions count: 256
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityStackSupervisor.resizeDockedStackLocked(android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, boolean, boolean):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resizePinnedStackLocked(Rect pinnedBounds, Rect tempPinnedTaskBounds) {
        ActivityStack stack = this.mRootActivityContainer.getDefaultDisplay().getPinnedStack();
        if (stack == null) {
            Slog.w("ActivityTaskManager", "resizePinnedStackLocked: pinned stack not found");
            return;
        }
        TaskStack stackController = stack.getTaskStack();
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
    /* renamed from: removeStackInSurfaceTransaction */
    public void lambda$removeStack$2$ActivityStackSupervisor(ActivityStack stack) {
        ArrayList<TaskRecord> tasks = stack.getAllTasks();
        if (stack.getWindowingMode() == 2) {
            stack.mForceHidden = true;
            stack.ensureActivitiesVisibleLocked(null, 0, true);
            stack.mForceHidden = false;
            activityIdleInternalLocked(null, false, true, null);
            moveTasksToFullscreenStackLocked(stack, false);
            return;
        }
        for (int i = tasks.size() - 1; i >= 0; i--) {
            removeTaskByIdLocked(tasks.get(i).taskId, true, true, "remove-stack");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeStack(final ActivityStack stack) {
        this.mWindowManager.inSurfaceTransaction(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityStackSupervisor$0u1RcpeZ6m0BHDGGv8EXroS3KyE
            @Override // java.lang.Runnable
            public final void run() {
                ActivityStackSupervisor.this.lambda$removeStack$2$ActivityStackSupervisor(stack);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeTaskByIdLocked(int taskId, boolean killProcess, boolean removeFromRecents, String reason) {
        return removeTaskByIdLocked(taskId, killProcess, removeFromRecents, false, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeTaskByIdLocked(int taskId, boolean killProcess, boolean removeFromRecents, boolean pauseImmediately, String reason) {
        TaskRecord tr = this.mRootActivityContainer.anyTaskForId(taskId, 1);
        if (tr != null) {
            tr.removeTaskActivitiesLocked(pauseImmediately, reason);
            cleanUpRemovedTaskLocked(tr, killProcess, removeFromRecents);
            this.mService.getLockTaskController().clearLockedTask(tr);
            if (tr.isPersistable) {
                this.mService.notifyTaskPersisterLocked(null, true);
            }
            return true;
        }
        Slog.w("ActivityTaskManager", "Request to remove task ignored for non-existent task " + taskId);
        return false;
    }

    void cleanUpRemovedTaskLocked(TaskRecord tr, boolean killProcess, boolean removeFromRecents) {
        if (removeFromRecents) {
            this.mRecentTasks.remove(tr);
        }
        ComponentName component = tr.getBaseIntent().getComponent();
        if (component == null) {
            Slog.w("ActivityTaskManager", "No component for base intent of task: " + tr);
            return;
        }
        Message msg = PooledLambda.obtainMessage(new QuadConsumer() { // from class: com.android.server.wm.-$$Lambda$z5j5fiv3cZuY5AODkt3H3rhKimk
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4) {
                ((ActivityManagerInternal) obj).cleanUpServices(((Integer) obj2).intValue(), (ComponentName) obj3, (Intent) obj4);
            }
        }, this.mService.mAmInternal, Integer.valueOf(tr.userId), component, new Intent(tr.getBaseIntent()));
        this.mService.mH.sendMessage(msg);
        if (!killProcess) {
            return;
        }
        String pkg = component.getPackageName();
        ArrayList<Object> procsToKill = new ArrayList<>();
        ArrayMap<String, SparseArray<WindowProcessController>> pmap = this.mService.mProcessNames.getMap();
        for (int i = 0; i < pmap.size(); i++) {
            SparseArray<WindowProcessController> uids = pmap.valueAt(i);
            for (int j = 0; j < uids.size(); j++) {
                WindowProcessController proc = uids.valueAt(j);
                if (proc.mUserId == tr.userId && proc != this.mService.mHomeProcess && proc.mPkgList.contains(pkg)) {
                    if (!proc.shouldKillProcessForRemovedTask(tr) || proc.hasForegroundServices()) {
                        return;
                    }
                    procsToKill.add(proc);
                }
            }
        }
        Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$j9nJq2XXOKyN4f0dfDaTjqmQRvg
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((ActivityManagerInternal) obj).killProcessesForRemovedTask((ArrayList) obj2);
            }
        }, this.mService.mAmInternal, procsToKill);
        this.mService.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean restoreRecentTaskLocked(TaskRecord task, ActivityOptions aOptions, boolean onTop) {
        ActivityStack stack = this.mRootActivityContainer.getLaunchStack(null, aOptions, task, onTop);
        ActivityStack currentStack = task.getStack();
        if (currentStack != null) {
            if (currentStack == stack) {
                return true;
            }
            currentStack.removeTask(task, "restoreRecentTaskLocked", 1);
        }
        stack.addTask(task, onTop, "restoreRecentTask");
        task.createTask(onTop, true);
        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
            Slog.v("ActivityTaskManager", "Added restored task=" + task + " to stack=" + stack);
        }
        ArrayList<ActivityRecord> activities = task.mActivities;
        for (int activityNdx = activities.size() - 1; activityNdx >= 0; activityNdx--) {
            activities.get(activityNdx).createAppWindowToken();
        }
        return true;
    }

    @Override // com.android.server.wm.RecentTasks.Callbacks
    public void onRecentTaskAdded(TaskRecord task) {
        task.touchActiveTime();
    }

    @Override // com.android.server.wm.RecentTasks.Callbacks
    public void onRecentTaskRemoved(TaskRecord task, boolean wasTrimmed, boolean killProcess) {
        if (wasTrimmed) {
            removeTaskByIdLocked(task.taskId, killProcess, false, false, "recent-task-trimmed");
        }
        task.removedFromRecents();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getReparentTargetStack(TaskRecord task, ActivityStack stack, boolean toTop) {
        ActivityStack prevStack = task.getStack();
        int stackId = stack.mStackId;
        boolean inMultiWindowMode = stack.inMultiWindowMode();
        if (prevStack != null && prevStack.mStackId == stackId) {
            Slog.w("ActivityTaskManager", "Can not reparent to same stack, task=" + task + " already in stackId=" + stackId);
            return prevStack;
        } else if (inMultiWindowMode && !this.mService.mSupportsMultiWindow) {
            throw new IllegalArgumentException("Device doesn't support multi-window, can not reparent task=" + task + " to stack=" + stack);
        } else if (stack.mDisplayId != 0 && !this.mService.mSupportsMultiDisplay) {
            throw new IllegalArgumentException("Device doesn't support multi-display, can not reparent task=" + task + " to stackId=" + stackId);
        } else if (stack.getWindowingMode() == 5 && !this.mService.mSupportsFreeformWindowManagement) {
            throw new IllegalArgumentException("Device doesn't support freeform, can not reparent task=" + task);
        } else if (inMultiWindowMode && !task.isResizeable()) {
            Slog.w("ActivityTaskManager", "Can not move unresizeable task=" + task + " to multi-window stack=" + stack + " Moving to a fullscreen stack instead.");
            if (prevStack != null) {
                return prevStack;
            }
            return stack.getDisplay().createStack(1, stack.getActivityType(), toTop);
        } else {
            return stack;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void goingToSleepLocked() {
        scheduleSleepTimeout();
        if (!this.mGoingToSleepWakeLock.isHeld()) {
            this.mGoingToSleepWakeLock.acquire();
            if (this.mLaunchingActivityWakeLock.isHeld()) {
                this.mLaunchingActivityWakeLock.release();
                this.mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
            }
        }
        this.mRootActivityContainer.applySleepTokens(false);
        checkReadyForSleepLocked(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shutdownLocked(int timeout) {
        goingToSleepLocked();
        boolean timedout = false;
        long endTime = System.currentTimeMillis() + timeout;
        while (true) {
            if (!this.mRootActivityContainer.putStacksToSleep(true, true)) {
                long timeRemaining = endTime - System.currentTimeMillis();
                if (timeRemaining > 0) {
                    try {
                        this.mService.mGlobalLock.wait(timeRemaining);
                    } catch (InterruptedException e) {
                    }
                } else {
                    Slog.w("ActivityTaskManager", "Activity manager shutdown timed out");
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
        if (this.mGoingToSleepWakeLock.isHeld()) {
            this.mGoingToSleepWakeLock.release();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void activitySleptLocked(ActivityRecord r) {
        this.mGoingToSleepActivities.remove(r);
        ActivityStack s = r.getActivityStack();
        if (s != null) {
            s.checkReadyForSleep();
        } else {
            checkReadyForSleepLocked(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkReadyForSleepLocked(boolean allowDelay) {
        if (!this.mService.isSleepingOrShuttingDownLocked() || !this.mRootActivityContainer.putStacksToSleep(allowDelay, false)) {
            return;
        }
        this.mRootActivityContainer.sendPowerHintForLaunchEndIfNeeded();
        removeSleepTimeouts();
        if (this.mGoingToSleepWakeLock.isHeld()) {
            this.mGoingToSleepWakeLock.release();
        }
        if (this.mService.mShuttingDown) {
            this.mService.mGlobalLock.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean reportResumedActivityLocked(ActivityRecord r) {
        this.mStoppingActivities.remove(r);
        ActivityStack stack = r.getActivityStack();
        if (stack.getDisplay().allResumedActivitiesComplete()) {
            this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
            this.mRootActivityContainer.executeAppTransitionForAllDisplay();
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLaunchTaskBehindCompleteLocked(ActivityRecord r) {
        TaskRecord task = r.getTaskRecord();
        ActivityStack stack = task.getStack();
        r.mLaunchTaskBehind = false;
        this.mRecentTasks.add(task);
        this.mService.getTaskChangeNotificationController().notifyTaskStackChanged();
        r.setVisibility(false);
        ActivityRecord top = stack.getTopActivity();
        if (top != null) {
            top.getTaskRecord().touchActiveTime();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleLaunchTaskBehindComplete(IBinder token) {
        this.mHandler.obtainMessage(212, token).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCurrentProfileLocked(int userId) {
        if (userId == this.mRootActivityContainer.mCurrentUser) {
            return true;
        }
        return this.mService.mAmInternal.isCurrentProfile(userId);
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
        boolean nowVisible = this.mRootActivityContainer.allResumedActivitiesVisible();
        for (int activityNdx = this.mStoppingActivities.size() - 1; activityNdx >= 0; activityNdx--) {
            ActivityRecord s = this.mStoppingActivities.get(activityNdx);
            boolean animating = s.mAppWindowToken.isSelfAnimating();
            if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                Slog.v("ActivityTaskManager", "Stopping " + s + ": nowVisible=" + nowVisible + " animating=" + animating + " finishing=" + s.finishing);
            }
            if (nowVisible && s.finishing) {
                if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                    Slog.v("ActivityTaskManager", "Before stopping, can hide: " + s);
                }
                s.setVisibility(false);
            }
            if (remove) {
                ActivityStack stack = s.getActivityStack();
                if (stack != null) {
                    shouldSleepOrShutDown = stack.shouldSleepOrShutDownActivities();
                } else {
                    shouldSleepOrShutDown = this.mService.isSleepingOrShuttingDownLocked();
                }
                if (!animating || shouldSleepOrShutDown) {
                    if (!processPausingActivities && s.isState(ActivityStack.ActivityState.PAUSING)) {
                        removeTimeoutsForActivityLocked(idleActivity);
                        scheduleIdleTimeoutLocked(idleActivity);
                    } else {
                        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
                            Slog.v("ActivityTaskManager", "Ready to stop: " + s);
                        }
                        if (stops == null) {
                            stops = new ArrayList<>();
                        }
                        stops.add(s);
                        this.mStoppingActivities.remove(activityNdx);
                    }
                }
            }
        }
        return stops;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println();
        pw.println("ActivityStackSupervisor state:");
        this.mRootActivityContainer.dump(pw, prefix);
        pw.print(prefix);
        pw.println("mCurTaskIdForUser=" + this.mCurTaskIdForUser);
        pw.println(prefix + "mUserStackInFront=" + this.mRootActivityContainer.mUserStackInFront);
        if (!this.mWaitingForActivityVisible.isEmpty()) {
            pw.println(prefix + "mWaitingForActivityVisible=");
            for (int i = 0; i < this.mWaitingForActivityVisible.size(); i++) {
                pw.print(prefix + prefix);
                this.mWaitingForActivityVisible.get(i).dump(pw, prefix);
            }
        }
        pw.print(prefix);
        pw.print("isHomeRecentsComponent=");
        pw.print(this.mRecentTasks.isRecentsComponentHomeActivity(this.mRootActivityContainer.mCurrentUser));
        getKeyguardController().dump(pw, prefix);
        this.mService.getLockTaskController().dump(pw, prefix);
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
    public static boolean dumpHistoryList(FileDescriptor fd, PrintWriter pw, List<ActivityRecord> list, String prefix, String label, boolean complete, boolean brief, boolean client, String dumpPackage, boolean needNL, String header, TaskRecord lastTask) {
        String header2;
        TaskRecord lastTask2;
        TransferPipe tp;
        String str = prefix;
        String str2 = dumpPackage;
        boolean printed = false;
        boolean z = true;
        int i = list.size() - 1;
        boolean needNL2 = needNL;
        String innerPrefix = null;
        String[] args = null;
        String innerPrefix2 = header;
        TaskRecord lastTask3 = lastTask;
        while (i >= 0) {
            ActivityRecord r = list.get(i);
            if (str2 == null || str2.equals(r.packageName)) {
                boolean full = false;
                if (innerPrefix == null) {
                    innerPrefix = str + "      ";
                    args = new String[0];
                }
                printed = true;
                if (!brief && (complete || !r.isInHistory())) {
                    full = z;
                }
                if (needNL2) {
                    pw.println("");
                    needNL2 = false;
                }
                if (innerPrefix2 == null) {
                    header2 = innerPrefix2;
                } else {
                    pw.println(innerPrefix2);
                    header2 = null;
                }
                if (lastTask3 != r.getTaskRecord()) {
                    lastTask3 = r.getTaskRecord();
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
                if (!client || !r.attachedToProcess()) {
                    lastTask3 = lastTask3;
                    innerPrefix2 = header2;
                } else {
                    pw.flush();
                    try {
                        TransferPipe tp2 = new TransferPipe();
                        try {
                            r.app.getThread().dumpActivity(tp2.getWriteFd(), r.appToken, innerPrefix, args);
                            TaskRecord taskRecord = lastTask3;
                            tp = tp2;
                            lastTask2 = taskRecord;
                        } catch (Throwable th) {
                            th = th;
                            TaskRecord taskRecord2 = lastTask3;
                            tp = tp2;
                            lastTask2 = taskRecord2;
                        }
                    } catch (RemoteException e) {
                        lastTask2 = lastTask3;
                    } catch (IOException e2) {
                        e = e2;
                        lastTask2 = lastTask3;
                    }
                    try {
                        tp.go(fd, 2000L);
                        try {
                            tp.kill();
                        } catch (RemoteException e3) {
                            pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
                            lastTask3 = lastTask2;
                            needNL2 = true;
                            innerPrefix2 = header2;
                            i--;
                            str = prefix;
                            str2 = dumpPackage;
                            z = true;
                        } catch (IOException e4) {
                            e = e4;
                            pw.println(innerPrefix + "Failure while dumping the activity: " + e);
                            lastTask3 = lastTask2;
                            needNL2 = true;
                            innerPrefix2 = header2;
                            i--;
                            str = prefix;
                            str2 = dumpPackage;
                            z = true;
                        }
                        lastTask3 = lastTask2;
                        needNL2 = true;
                        innerPrefix2 = header2;
                    } catch (Throwable th2) {
                        th = th2;
                        tp.kill();
                        throw th;
                        break;
                    }
                }
            }
            i--;
            str = prefix;
            str2 = dumpPackage;
            z = true;
        }
        return printed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleIdleTimeoutLocked(ActivityRecord next) {
        if (ActivityTaskManagerDebugConfig.DEBUG_IDLE) {
            Slog.d("ActivityTaskManager", "scheduleIdleTimeoutLocked: Callers=" + Debug.getCallers(4));
        }
        Message msg = this.mHandler.obtainMessage(200, next);
        this.mHandler.sendMessageDelayed(msg, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleIdleLocked() {
        this.mHandler.sendEmptyMessage(IDLE_NOW_MSG);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateTopResumedActivityIfNeeded() {
        ActivityRecord prevTopActivity = this.mTopResumedActivity;
        ActivityStack topStack = this.mRootActivityContainer.getTopDisplayFocusedStack();
        if (topStack == null || topStack.mResumedActivity == prevTopActivity) {
            return;
        }
        boolean prevActivityReceivedTopState = (prevTopActivity == null || this.mTopResumedActivityWaitingForPrev) ? false : true;
        if (prevActivityReceivedTopState && prevTopActivity.scheduleTopResumedActivityChanged(false)) {
            scheduleTopResumedStateLossTimeout(prevTopActivity);
            this.mTopResumedActivityWaitingForPrev = true;
        }
        this.mTopResumedActivity = topStack.mResumedActivity;
        scheduleTopResumedActivityStateIfNeeded();
    }

    private void scheduleTopResumedActivityStateIfNeeded() {
        ActivityRecord activityRecord = this.mTopResumedActivity;
        if (activityRecord != null && !this.mTopResumedActivityWaitingForPrev) {
            activityRecord.scheduleTopResumedActivityChanged(true);
        }
    }

    private void scheduleTopResumedStateLossTimeout(ActivityRecord r) {
        Message msg = this.mHandler.obtainMessage(TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG);
        msg.obj = r;
        r.topResumedStateLossTime = SystemClock.uptimeMillis();
        this.mHandler.sendMessageDelayed(msg, 500L);
        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            Slog.v("ActivityTaskManager", "Waiting for top state to be released by " + r);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleTopResumedStateReleased(boolean timeout) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STATES) {
            StringBuilder sb = new StringBuilder();
            sb.append("Top resumed state released ");
            sb.append(timeout ? " (due to timeout)" : " (transition complete)");
            Slog.v("ActivityTaskManager", sb.toString());
        }
        this.mHandler.removeMessages(TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG);
        if (!this.mTopResumedActivityWaitingForPrev) {
            return;
        }
        this.mTopResumedActivityWaitingForPrev = false;
        scheduleTopResumedActivityStateIfNeeded();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeTimeoutsForActivityLocked(ActivityRecord r) {
        if (ActivityTaskManagerDebugConfig.DEBUG_IDLE) {
            Slog.d("ActivityTaskManager", "removeTimeoutsForActivity: Callers=" + Debug.getCallers(4));
        }
        this.mHandler.removeMessages(200, r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleResumeTopActivities() {
        if (!this.mHandler.hasMessages(RESUME_TOP_ACTIVITY_MSG)) {
            this.mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG);
        }
    }

    void removeSleepTimeouts() {
        this.mHandler.removeMessages(SLEEP_TIMEOUT_MSG);
    }

    final void scheduleSleepTimeout() {
        removeSleepTimeouts();
        this.mHandler.sendEmptyMessageDelayed(SLEEP_TIMEOUT_MSG, 5000L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeRestartTimeouts(ActivityRecord r) {
        this.mHandler.removeMessages(RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG, r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleRestartTimeout(ActivityRecord r) {
        removeRestartTimeouts(r);
        ActivityStackSupervisorHandler activityStackSupervisorHandler = this.mHandler;
        activityStackSupervisorHandler.sendMessageDelayed(activityStackSupervisorHandler.obtainMessage(RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG, r), 2000L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredWindowingMode, int preferredDisplayId, ActivityStack actualStack) {
        handleNonResizableTaskIfNeeded(task, preferredWindowingMode, preferredDisplayId, actualStack, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleNonResizableTaskIfNeeded(TaskRecord task, int preferredWindowingMode, int preferredDisplayId, ActivityStack actualStack, boolean forceNonResizable) {
        if (task == null) {
            Slog.w("ActivityTaskManager", "Failed to handleNonResizableTask actualStack" + actualStack + " on display " + preferredDisplayId);
            return;
        }
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
            ActivityDisplay preferredDisplay = this.mRootActivityContainer.getActivityDisplay(preferredDisplayId);
            if (preferredDisplay != null && preferredDisplay.isSingleTaskInstance()) {
                singleTaskInstance = true;
            }
            if (preferredDisplayId != actualDisplayId) {
                if (singleTaskInstance) {
                    this.mService.getTaskChangeNotificationController().notifyActivityLaunchOnSecondaryDisplayRerouted(task.getTaskInfo(), preferredDisplayId);
                    return;
                }
                Slog.w("ActivityTaskManager", "Failed to put " + task + " on display " + preferredDisplayId);
                this.mService.getTaskChangeNotificationController().notifyActivityLaunchOnSecondaryDisplayFailed(task.getTaskInfo(), preferredDisplayId);
            } else if (!forceNonResizable) {
                handleForcedResizableTaskIfNeeded(task, 2);
            }
        } else if (!task.supportsSplitScreenWindowingMode() || forceNonResizable) {
            ActivityStack dockedStack = task.getStack().getDisplay().getSplitScreenPrimaryStack();
            if (dockedStack != null) {
                this.mService.getTaskChangeNotificationController().notifyActivityDismissingDockedStack();
                singleTaskInstance = actualStack == dockedStack;
                moveTasksToFullscreenStackLocked(dockedStack, singleTaskInstance);
            }
        } else {
            handleForcedResizableTaskIfNeeded(task, 1);
        }
    }

    private void handleForcedResizableTaskIfNeeded(TaskRecord task, int reason) {
        ActivityRecord topActivity = task.getTopActivity();
        if (topActivity == null || topActivity.noDisplay || !topActivity.isNonResizableOrForcedResizable()) {
            return;
        }
        this.mService.getTaskChangeNotificationController().notifyActivityForcedResizable(task.taskId, reason, topActivity.appInfo.packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void activityRelaunchedLocked(IBinder token) {
        this.mWindowManager.notifyAppRelaunchingFinished(token);
        ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r != null && r.getActivityStack().shouldSleepOrShutDownActivities()) {
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
            if (r.attachedToProcess()) {
                this.mMultiWindowModeChangedActivities.add(r);
            }
        }
        if (!this.mHandler.hasMessages(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG)) {
            this.mHandler.sendEmptyMessage(REPORT_MULTI_WINDOW_MODE_CHANGED_MSG);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleUpdatePictureInPictureModeIfNeeded(TaskRecord task, ActivityStack prevStack) {
        ActivityStack stack = task.getStack();
        if (prevStack != null && prevStack != stack) {
            if (!prevStack.inPinnedWindowingMode() && !stack.inPinnedWindowingMode()) {
                return;
            }
            scheduleUpdatePictureInPictureModeIfNeeded(task, stack.getRequestedOverrideBounds());
        }
    }

    void scheduleUpdatePictureInPictureModeIfNeeded(TaskRecord task, Rect targetStackBounds) {
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = task.mActivities.get(i);
            if (r.attachedToProcess()) {
                this.mPipModeChangedActivities.add(r);
                this.mMultiWindowModeChangedActivities.remove(r);
            }
        }
        this.mPipModeChangedTargetStackBounds = targetStackBounds;
        if (!this.mHandler.hasMessages(REPORT_PIP_MODE_CHANGED_MSG)) {
            this.mHandler.sendEmptyMessage(REPORT_PIP_MODE_CHANGED_MSG);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updatePictureInPictureMode(TaskRecord task, Rect targetStackBounds, boolean forceUpdate) {
        this.mHandler.removeMessages(REPORT_PIP_MODE_CHANGED_MSG);
        for (int i = task.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = task.mActivities.get(i);
            if (r.attachedToProcess()) {
                r.updatePictureInPictureMode(targetStackBounds, forceUpdate);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void wakeUp(String reason) {
        PowerManager powerManager = this.mPowerManager;
        long uptimeMillis = SystemClock.uptimeMillis();
        powerManager.wakeUp(uptimeMillis, 2, "android.server.am:TURN_ON:" + reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void beginDeferResume() {
        this.mDeferResumeCount++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void endDeferResume() {
        this.mDeferResumeCount--;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean readyToResume() {
        return this.mDeferResumeCount == 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class ActivityStackSupervisorHandler extends Handler {
        public ActivityStackSupervisorHandler(Looper looper) {
            super(looper);
        }

        void activityIdleInternal(ActivityRecord r, boolean processPausingActivities) {
            synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityStackSupervisor.this.activityIdleInternalLocked(r != null ? r.appToken : null, true, processPausingActivities, null);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            switch (i) {
                case 200:
                    if (ActivityTaskManagerDebugConfig.DEBUG_IDLE) {
                        Slog.d("ActivityTaskManager", "handleMessage: IDLE_TIMEOUT_MSG: r=" + msg.obj);
                    }
                    activityIdleInternal((ActivityRecord) msg.obj, true);
                    return;
                case ActivityStackSupervisor.IDLE_NOW_MSG /* 201 */:
                    if (ActivityTaskManagerDebugConfig.DEBUG_IDLE) {
                        Slog.d("ActivityTaskManager", "handleMessage: IDLE_NOW_MSG: r=" + msg.obj);
                    }
                    activityIdleInternal((ActivityRecord) msg.obj, false);
                    return;
                case ActivityStackSupervisor.RESUME_TOP_ACTIVITY_MSG /* 202 */:
                    synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            ActivityStackSupervisor.this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                case ActivityStackSupervisor.SLEEP_TIMEOUT_MSG /* 203 */:
                    synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (ActivityStackSupervisor.this.mService.isSleepingOrShuttingDownLocked()) {
                                Slog.w("ActivityTaskManager", "Sleep timeout!  Sleeping now.");
                                ActivityStackSupervisor.this.checkReadyForSleepLocked(false);
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                case ActivityStackSupervisor.LAUNCH_TIMEOUT_MSG /* 204 */:
                    synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                        try {
                            WindowManagerService.boostPriorityForLockedSection();
                            if (ActivityStackSupervisor.this.mLaunchingActivityWakeLock.isHeld()) {
                                Slog.w("ActivityTaskManager", "Launch timeout has expired, giving up wake lock!");
                                ActivityStackSupervisor.this.mLaunchingActivityWakeLock.release();
                            }
                        } finally {
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                default:
                    switch (i) {
                        case 212:
                            synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    ActivityRecord r = ActivityRecord.forTokenLocked((IBinder) msg.obj);
                                    if (r != null) {
                                        ActivityStackSupervisor.this.handleLaunchTaskBehindCompleteLocked(r);
                                    }
                                } finally {
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        case ActivityStackSupervisor.RESTART_ACTIVITY_PROCESS_TIMEOUT_MSG /* 213 */:
                            ActivityRecord r2 = (ActivityRecord) msg.obj;
                            String processName = null;
                            int uid = 0;
                            synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    if (r2.attachedToProcess() && r2.isState(ActivityStack.ActivityState.RESTARTING_PROCESS)) {
                                        processName = r2.app.mName;
                                        uid = r2.app.mUid;
                                    }
                                } finally {
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            if (processName != null) {
                                ActivityStackSupervisor.this.mService.mAmInternal.killProcess(processName, uid, "restartActivityProcessTimeout");
                                return;
                            }
                            return;
                        case ActivityStackSupervisor.REPORT_MULTI_WINDOW_MODE_CHANGED_MSG /* 214 */:
                            synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    for (int i2 = ActivityStackSupervisor.this.mMultiWindowModeChangedActivities.size() - 1; i2 >= 0; i2--) {
                                        ActivityStackSupervisor.this.mMultiWindowModeChangedActivities.remove(i2).updateMultiWindowMode();
                                    }
                                } finally {
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        case ActivityStackSupervisor.REPORT_PIP_MODE_CHANGED_MSG /* 215 */:
                            synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    for (int i3 = ActivityStackSupervisor.this.mPipModeChangedActivities.size() - 1; i3 >= 0; i3--) {
                                        ActivityStackSupervisor.this.mPipModeChangedActivities.remove(i3).updatePictureInPictureMode(ActivityStackSupervisor.this.mPipModeChangedTargetStackBounds, false);
                                    }
                                } finally {
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        case ActivityStackSupervisor.REPORT_HOME_CHANGED_MSG /* 216 */:
                            synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    ActivityStackSupervisor.this.mHandler.removeMessages(ActivityStackSupervisor.REPORT_HOME_CHANGED_MSG);
                                    ActivityStackSupervisor.this.mRootActivityContainer.startHomeOnEmptyDisplays("homeChanged");
                                } finally {
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        case ActivityStackSupervisor.TOP_RESUMED_STATE_LOSS_TIMEOUT_MSG /* 217 */:
                            ActivityRecord r3 = (ActivityRecord) msg.obj;
                            Slog.w("ActivityTaskManager", "Activity top resumed state loss timeout for " + r3);
                            synchronized (ActivityStackSupervisor.this.mService.mGlobalLock) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    if (r3.hasProcess()) {
                                        ActivityStackSupervisor.this.mService.logAppTooSlow(r3.app, r3.topResumedStateLossTime, "top state loss for " + r3);
                                    }
                                } finally {
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            ActivityStackSupervisor.this.handleTopResumedStateReleased(true);
                            return;
                        default:
                            return;
                    }
            }
        }
    }

    void setResizingDuringAnimation(TaskRecord task) {
        this.mResizingTasksDuringAnimation.add(Integer.valueOf(task.taskId));
        task.setTaskDockedResizing(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int startActivityFromRecents(int callingPid, int callingUid, int taskId, SafeActivityOptions options) {
        int activityType;
        int windowingMode;
        int i;
        int windowingMode2;
        boolean z;
        String str;
        int i2;
        TaskRecord task;
        int activityType2;
        int i3;
        String str2;
        int i4;
        boolean z2;
        TaskRecord task2;
        ActivityRecord targetActivity;
        int activityType3;
        int i5;
        String str3;
        int i6;
        boolean z3;
        TaskRecord task3;
        ActivityOptions activityOptions = options != null ? options.getOptions(this) : null;
        if (activityOptions != null) {
            int activityType4 = activityOptions.getLaunchActivityType();
            int windowingMode3 = activityOptions.getLaunchWindowingMode();
            if (activityOptions.freezeRecentTasksReordering() && this.mRecentTasks.isCallerRecents(callingUid)) {
                this.mRecentTasks.setFreezeTaskListReordering();
            }
            activityType = activityType4;
            windowingMode = windowingMode3;
        } else {
            activityType = 0;
            windowingMode = 0;
        }
        if (activityType == 2 || activityType == 3) {
            throw new IllegalArgumentException("startActivityFromRecents: Task " + taskId + " can't be launch in the home/recents stack.");
        }
        this.mWindowManager.deferSurfaceLayout();
        if (windowingMode == 3) {
            try {
                this.mWindowManager.setDockedStackCreateState(activityOptions.getSplitScreenCreateMode(), null);
                deferUpdateRecentsHomeStackBounds();
                this.mWindowManager.prepareAppTransition(19, false);
            } catch (Throwable th) {
                th = th;
                i = 3;
                windowingMode2 = windowingMode;
                z = false;
                str = "startActivityFromRecents: homeVisibleInSplitScreen";
                i2 = 4;
                task = null;
            }
        }
        try {
            TaskRecord task4 = this.mRootActivityContainer.anyTaskForId(taskId, 2, activityOptions, true);
            if (task4 != null) {
                if (windowingMode != 3) {
                    try {
                        this.mRootActivityContainer.getDefaultDisplay().moveHomeStackToFront("startActivityFromRecents");
                    } catch (Throwable th2) {
                        th = th2;
                        i = 3;
                        windowingMode2 = windowingMode;
                        z = false;
                        task = task4;
                        str = "startActivityFromRecents: homeVisibleInSplitScreen";
                        i2 = 4;
                    }
                }
                try {
                    if (this.mService.mAmInternal.shouldConfirmCredentials(task4.userId)) {
                        activityType2 = activityType;
                        i3 = 4;
                        str2 = "startActivityFromRecents: homeVisibleInSplitScreen";
                        i4 = 3;
                        z2 = false;
                        task2 = task4;
                    } else {
                        try {
                            if (task4.getRootActivity() != null) {
                                ActivityRecord targetActivity2 = task4.getTopActivity();
                                this.mRootActivityContainer.sendPowerHintForLaunchStartIfNeeded(true, targetActivity2);
                                this.mActivityMetricsLogger.notifyActivityLaunching(task4.intent);
                                try {
                                } catch (Throwable th3) {
                                    th = th3;
                                    targetActivity = targetActivity2;
                                    activityType3 = activityType;
                                    i5 = 4;
                                    str3 = "startActivityFromRecents: homeVisibleInSplitScreen";
                                    i6 = 3;
                                    z3 = false;
                                }
                                try {
                                    i5 = 4;
                                    activityType3 = activityType;
                                    str3 = "startActivityFromRecents: homeVisibleInSplitScreen";
                                    i6 = 3;
                                } catch (Throwable th4) {
                                    th = th4;
                                    targetActivity = targetActivity2;
                                    activityType3 = activityType;
                                    i5 = 4;
                                    str3 = "startActivityFromRecents: homeVisibleInSplitScreen";
                                    i6 = 3;
                                    z3 = false;
                                    task3 = task4;
                                    try {
                                        this.mActivityMetricsLogger.notifyActivityLaunched(2, targetActivity);
                                        throw th;
                                    } catch (Throwable th5) {
                                        th = th5;
                                        task = task3;
                                        windowingMode2 = windowingMode;
                                        str = str3;
                                        i = i6;
                                        z = z3;
                                        i2 = i5;
                                    }
                                }
                                try {
                                    this.mService.moveTaskToFrontLocked(null, null, task4.taskId, 0, options, true);
                                    targetActivity2.applyOptionsLocked();
                                    try {
                                        this.mActivityMetricsLogger.notifyActivityLaunched(2, targetActivity2);
                                        this.mService.getActivityStartController().postStartActivityProcessingForLastStarter(task4.getTopActivity(), 2, task4.getStack());
                                        if (windowingMode == 3) {
                                            setResizingDuringAnimation(task4);
                                            ActivityDisplay display = task4.getStack().getDisplay();
                                            ActivityStack topSecondaryStack = display.getTopStackInWindowingMode(4);
                                            if (topSecondaryStack.isActivityTypeHome()) {
                                                display.moveHomeStackToFront(str3);
                                                this.mWindowManager.checkSplitScreenMinimizedChanged(false);
                                            }
                                        }
                                        this.mWindowManager.continueSurfaceLayout();
                                        return 2;
                                    } catch (Throwable th6) {
                                        th = th6;
                                        task = task4;
                                        windowingMode2 = windowingMode;
                                        str = str3;
                                        i2 = 4;
                                        i = 3;
                                        z = false;
                                    }
                                } catch (Throwable th7) {
                                    th = th7;
                                    targetActivity = targetActivity2;
                                    task3 = task4;
                                    z3 = false;
                                    this.mActivityMetricsLogger.notifyActivityLaunched(2, targetActivity);
                                    throw th;
                                }
                            } else {
                                activityType2 = activityType;
                                i3 = 4;
                                str2 = "startActivityFromRecents: homeVisibleInSplitScreen";
                                i4 = 3;
                                z2 = false;
                                task2 = task4;
                            }
                        } catch (Throwable th8) {
                            th = th8;
                            windowingMode2 = windowingMode;
                            i2 = 4;
                            i = 3;
                            z = false;
                            task = task4;
                            str = "startActivityFromRecents: homeVisibleInSplitScreen";
                        }
                    }
                    try {
                        String callingPackage = task2.mCallingPackage;
                        Intent intent = task2.intent;
                        intent.addFlags(DumpState.DUMP_DEXOPT);
                        int userId = task2.userId;
                        TaskRecord task5 = task2;
                        int windowingMode4 = windowingMode;
                        String str4 = str2;
                        try {
                            int startActivityInPackage = this.mService.getActivityStartController().startActivityInPackage(task2.mCallingUid, callingPid, callingUid, callingPackage, intent, null, null, null, 0, 0, options, userId, task5, "startActivityFromRecents", false, null, false);
                            if (windowingMode4 == 3) {
                                setResizingDuringAnimation(task5);
                                ActivityDisplay display2 = task5.getStack().getDisplay();
                                ActivityStack topSecondaryStack2 = display2.getTopStackInWindowingMode(4);
                                if (topSecondaryStack2.isActivityTypeHome()) {
                                    display2.moveHomeStackToFront(str4);
                                    this.mWindowManager.checkSplitScreenMinimizedChanged(false);
                                }
                            }
                            this.mWindowManager.continueSurfaceLayout();
                            return startActivityInPackage;
                        } catch (Throwable th9) {
                            th = th9;
                            task = task5;
                            windowingMode2 = windowingMode4;
                            str = str4;
                            i = 3;
                            i2 = 4;
                            z = false;
                        }
                    } catch (Throwable th10) {
                        th = th10;
                        task = task2;
                        windowingMode2 = windowingMode;
                        str = str2;
                        i = i4;
                        z = z2;
                        i2 = i3;
                    }
                } catch (Throwable th11) {
                    th = th11;
                    i = 3;
                    windowingMode2 = windowingMode;
                    z = false;
                    task = task4;
                    str = "startActivityFromRecents: homeVisibleInSplitScreen";
                    i2 = 4;
                }
            } else {
                i = 3;
                windowingMode2 = windowingMode;
                z = false;
                task = task4;
                str = "startActivityFromRecents: homeVisibleInSplitScreen";
                i2 = 4;
                try {
                    continueUpdateRecentsHomeStackBounds();
                    this.mWindowManager.executeAppTransition();
                    throw new IllegalArgumentException("startActivityFromRecents: Task " + taskId + " not found.");
                } catch (Throwable th12) {
                    th = th12;
                }
            }
        } catch (Throwable th13) {
            th = th13;
            i = 3;
            windowingMode2 = windowingMode;
            z = false;
            str = "startActivityFromRecents: homeVisibleInSplitScreen";
            i2 = 4;
            task = null;
        }
        if (windowingMode2 == i && task != null) {
            setResizingDuringAnimation(task);
            ActivityDisplay display3 = task.getStack().getDisplay();
            ActivityStack topSecondaryStack3 = display3.getTopStackInWindowingMode(i2);
            if (topSecondaryStack3.isActivityTypeHome()) {
                display3.moveHomeStackToFront(str);
                this.mWindowManager.checkSplitScreenMinimizedChanged(z);
            }
        }
        this.mWindowManager.continueSurfaceLayout();
        throw th;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
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
            ComponentName componentName = this.mTargetComponent;
            return componentName == null || componentName.equals(targetComponent);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onLaunchParamsLoaded(ArrayList<SharedDisplayContainer.LaunchParams> list) {
        ActivityTaskManagerService activityTaskManagerService = this.mService;
        if (activityTaskManagerService != null) {
            activityTaskManagerService.onLaunchParamsLoaded(list);
        }
    }
}
