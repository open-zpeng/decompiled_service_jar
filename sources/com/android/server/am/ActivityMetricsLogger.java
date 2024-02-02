package com.android.server.am;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.dex.ArtManagerInternal;
import android.content.pm.dex.PackageOptimizationInfo;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.Trace;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.server.LocalServices;
import com.android.server.am.MemoryStatUtil;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import java.util.ArrayList;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ActivityMetricsLogger {
    private static final int INVALID_DELAY = -1;
    private static final long INVALID_START_TIME = -1;
    private static final int INVALID_TRANSITION_TYPE = -1;
    private static final int MSG_CHECK_VISIBILITY = 0;
    private static final String TAG = "ActivityManager";
    private static final String[] TRON_WINDOW_STATE_VARZ_STRINGS = {"window_time_0", "window_time_1", "window_time_2", "window_time_3"};
    private static final int WINDOW_STATE_ASSISTANT = 3;
    private static final int WINDOW_STATE_FREEFORM = 2;
    private static final int WINDOW_STATE_INVALID = -1;
    private static final int WINDOW_STATE_SIDE_BY_SIDE = 1;
    private static final int WINDOW_STATE_STANDARD = 0;
    private ArtManagerInternal mArtManagerInternal;
    private final Context mContext;
    private int mCurrentTransitionDelayMs;
    private int mCurrentTransitionDeviceUptime;
    private boolean mDrawingTraceActive;
    private final H mHandler;
    private boolean mLoggedTransitionStarting;
    private final ActivityStackSupervisor mSupervisor;
    private int mWindowState = 0;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private long mCurrentTransitionStartTime = -1;
    private long mLastTransitionStartTime = -1;
    private final SparseArray<WindowingModeTransitionInfo> mWindowingModeTransitionInfo = new SparseArray<>();
    private final SparseArray<WindowingModeTransitionInfo> mLastWindowingModeTransitionInfo = new SparseArray<>();
    private final StringBuilder mStringBuilder = new StringBuilder();
    private long mLastLogTimeSecs = SystemClock.elapsedRealtime() / 1000;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 0) {
                SomeArgs args = (SomeArgs) msg.obj;
                ActivityMetricsLogger.this.checkVisibility((TaskRecord) args.arg1, (ActivityRecord) args.arg2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class WindowingModeTransitionInfo {
        private int bindApplicationDelayMs;
        private boolean currentTransitionProcessRunning;
        private boolean launchTraceActive;
        private ActivityRecord launchedActivity;
        private boolean loggedStartingWindowDrawn;
        private boolean loggedWindowsDrawn;
        private int reason;
        private int startResult;
        private int startingWindowDelayMs;
        private int windowsDrawnDelayMs;

        private WindowingModeTransitionInfo() {
            this.startingWindowDelayMs = -1;
            this.bindApplicationDelayMs = -1;
            this.reason = 3;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class WindowingModeTransitionInfoSnapshot {
        final int activityRecordIdHashCode;
        private final ApplicationInfo applicationInfo;
        private final int bindApplicationDelayMs;
        private final String launchedActivityAppRecordRequiredAbi;
        private final String launchedActivityLaunchToken;
        private final String launchedActivityLaunchedFromPackage;
        final String launchedActivityName;
        final String launchedActivityShortComponentName;
        final String packageName;
        private final String processName;
        private final ProcessRecord processRecord;
        private final int reason;
        private final int startingWindowDelayMs;
        final int type;
        final int userId;
        final int windowsDrawnDelayMs;
        final int windowsFullyDrawnDelayMs;

        private WindowingModeTransitionInfoSnapshot(ActivityMetricsLogger this$0, WindowingModeTransitionInfo info) {
            this(this$0, info, info.launchedActivity);
        }

        private WindowingModeTransitionInfoSnapshot(ActivityMetricsLogger this$0, WindowingModeTransitionInfo info, ActivityRecord launchedActivity) {
            this(info, launchedActivity, -1);
        }

        private WindowingModeTransitionInfoSnapshot(WindowingModeTransitionInfo info, ActivityRecord launchedActivity, int windowsFullyDrawnDelayMs) {
            String str;
            this.applicationInfo = launchedActivity.appInfo;
            this.packageName = launchedActivity.packageName;
            this.launchedActivityName = launchedActivity.info.name;
            this.launchedActivityLaunchedFromPackage = launchedActivity.launchedFromPackage;
            this.launchedActivityLaunchToken = launchedActivity.info.launchToken;
            if (launchedActivity.app == null) {
                str = null;
            } else {
                str = launchedActivity.app.requiredAbi;
            }
            this.launchedActivityAppRecordRequiredAbi = str;
            this.reason = info.reason;
            this.startingWindowDelayMs = info.startingWindowDelayMs;
            this.bindApplicationDelayMs = info.bindApplicationDelayMs;
            this.windowsDrawnDelayMs = info.windowsDrawnDelayMs;
            this.type = ActivityMetricsLogger.this.getTransitionType(info);
            this.processRecord = ActivityMetricsLogger.this.findProcessForActivity(launchedActivity);
            this.processName = launchedActivity.processName;
            this.userId = launchedActivity.userId;
            this.launchedActivityShortComponentName = launchedActivity.shortComponentName;
            this.activityRecordIdHashCode = System.identityHashCode(launchedActivity);
            this.windowsFullyDrawnDelayMs = windowsFullyDrawnDelayMs;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityMetricsLogger(ActivityStackSupervisor supervisor, Context context, Looper looper) {
        this.mSupervisor = supervisor;
        this.mContext = context;
        this.mHandler = new H(looper);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void logWindowState() {
        long now = SystemClock.elapsedRealtime() / 1000;
        this.mLastLogTimeSecs = now;
        this.mWindowState = -1;
        ActivityStack stack = this.mSupervisor.getFocusedStack();
        if (stack.isActivityTypeAssistant()) {
            this.mWindowState = 3;
            return;
        }
        int windowingMode = stack.getWindowingMode();
        if (windowingMode == 2) {
            stack = this.mSupervisor.findStackBehind(stack);
            windowingMode = stack.getWindowingMode();
        }
        if (windowingMode == 1) {
            this.mWindowState = 0;
            return;
        }
        switch (windowingMode) {
            case 3:
            case 4:
                this.mWindowState = 1;
                return;
            case 5:
                this.mWindowState = 2;
                return;
            default:
                if (windowingMode != 0) {
                    throw new IllegalStateException("Unknown windowing mode for stack=" + stack + " windowingMode=" + windowingMode);
                }
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityLaunching() {
        if (!isAnyTransitionActive()) {
            if (ActivityManagerDebugConfig.DEBUG_METRICS) {
                Slog.i(TAG, "notifyActivityLaunching");
            }
            this.mCurrentTransitionStartTime = SystemClock.uptimeMillis();
            this.mLastTransitionStartTime = this.mCurrentTransitionStartTime;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityLaunched(int resultCode, ActivityRecord launchedActivity) {
        ProcessRecord processRecord = findProcessForActivity(launchedActivity);
        boolean processSwitch = false;
        boolean processRunning = processRecord != null;
        if (processRecord == null || !hasStartedActivity(processRecord, launchedActivity)) {
            processSwitch = true;
        }
        notifyActivityLaunched(resultCode, launchedActivity, processRunning, processSwitch);
    }

    private boolean hasStartedActivity(ProcessRecord record, ActivityRecord launchedActivity) {
        ArrayList<ActivityRecord> activities = record.activities;
        for (int i = activities.size() - 1; i >= 0; i--) {
            ActivityRecord activity = activities.get(i);
            if (launchedActivity != activity && !activity.stopped) {
                return true;
            }
        }
        return false;
    }

    private void notifyActivityLaunched(int resultCode, ActivityRecord launchedActivity, boolean processRunning, boolean processSwitch) {
        int windowingMode;
        if (ActivityManagerDebugConfig.DEBUG_METRICS) {
            Slog.i(TAG, "notifyActivityLaunched resultCode=" + resultCode + " launchedActivity=" + launchedActivity + " processRunning=" + processRunning + " processSwitch=" + processSwitch);
        }
        boolean otherWindowModesLaunching = false;
        if (launchedActivity != null) {
            windowingMode = launchedActivity.getWindowingMode();
        } else {
            windowingMode = 0;
        }
        WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.get(windowingMode);
        if (this.mCurrentTransitionStartTime == -1) {
            return;
        }
        if (launchedActivity != null && launchedActivity.drawn) {
            reset(true, info);
        } else if (launchedActivity != null && info != null) {
            info.launchedActivity = launchedActivity;
        } else {
            if (this.mWindowingModeTransitionInfo.size() > 0 && info == null) {
                otherWindowModesLaunching = true;
            }
            if ((!isLoggableResultCode(resultCode) || launchedActivity == null || !processSwitch || windowingMode == 0) && !otherWindowModesLaunching) {
                reset(true, info);
            } else if (otherWindowModesLaunching) {
            } else {
                if (ActivityManagerDebugConfig.DEBUG_METRICS) {
                    Slog.i(TAG, "notifyActivityLaunched successful");
                }
                WindowingModeTransitionInfo newInfo = new WindowingModeTransitionInfo();
                newInfo.launchedActivity = launchedActivity;
                newInfo.currentTransitionProcessRunning = processRunning;
                newInfo.startResult = resultCode;
                this.mWindowingModeTransitionInfo.put(windowingMode, newInfo);
                this.mLastWindowingModeTransitionInfo.put(windowingMode, newInfo);
                this.mCurrentTransitionDeviceUptime = (int) (SystemClock.uptimeMillis() / 1000);
                startTraces(newInfo);
            }
        }
    }

    private boolean isLoggableResultCode(int resultCode) {
        return resultCode == 0 || resultCode == 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowingModeTransitionInfoSnapshot notifyWindowsDrawn(int windowingMode, long timestamp) {
        if (ActivityManagerDebugConfig.DEBUG_METRICS) {
            Slog.i(TAG, "notifyWindowsDrawn windowingMode=" + windowingMode);
        }
        WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.get(windowingMode);
        if (info != null && !info.loggedWindowsDrawn) {
            info.windowsDrawnDelayMs = calculateDelay(timestamp);
            info.loggedWindowsDrawn = true;
            WindowingModeTransitionInfoSnapshot infoSnapshot = new WindowingModeTransitionInfoSnapshot(info);
            if (allWindowsDrawn() && this.mLoggedTransitionStarting) {
                reset(false, info);
            }
            return infoSnapshot;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyStartingWindowDrawn(int windowingMode, long timestamp) {
        WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.get(windowingMode);
        if (info != null && !info.loggedStartingWindowDrawn) {
            info.loggedStartingWindowDrawn = true;
            info.startingWindowDelayMs = calculateDelay(timestamp);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTransitionStarting(SparseIntArray windowingModeToReason, long timestamp) {
        if (!isAnyTransitionActive() || this.mLoggedTransitionStarting) {
            return;
        }
        if (ActivityManagerDebugConfig.DEBUG_METRICS) {
            Slog.i(TAG, "notifyTransitionStarting");
        }
        this.mCurrentTransitionDelayMs = calculateDelay(timestamp);
        this.mLoggedTransitionStarting = true;
        int windowingMode = windowingModeToReason.size() - 1;
        while (true) {
            int index = windowingMode;
            if (index < 0) {
                break;
            }
            int windowingMode2 = windowingModeToReason.keyAt(index);
            WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.get(windowingMode2);
            if (info != null) {
                info.reason = windowingModeToReason.valueAt(index);
            }
            windowingMode = index - 1;
        }
        if (allWindowsDrawn()) {
            reset(false, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyVisibilityChanged(ActivityRecord activityRecord) {
        WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.get(activityRecord.getWindowingMode());
        if (info == null || info.launchedActivity != activityRecord) {
            return;
        }
        TaskRecord t = activityRecord.getTask();
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = t;
        args.arg2 = activityRecord;
        this.mHandler.obtainMessage(0, args).sendToTarget();
    }

    private boolean hasVisibleNonFinishingActivity(TaskRecord t) {
        for (int i = t.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord r = t.mActivities.get(i);
            if (r.visible && !r.finishing) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkVisibility(TaskRecord t, ActivityRecord r) {
        synchronized (this.mSupervisor.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.get(r.getWindowingMode());
                if (info != null && !hasVisibleNonFinishingActivity(t)) {
                    if (ActivityManagerDebugConfig.DEBUG_METRICS) {
                        Slog.i(TAG, "notifyVisibilityChanged to invisible activity=" + r);
                    }
                    logAppTransitionCancel(info);
                    this.mWindowingModeTransitionInfo.remove(r.getWindowingMode());
                    if (this.mWindowingModeTransitionInfo.size() == 0) {
                        reset(true, info);
                    }
                    stopFullyDrawnTraceIfNeeded();
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyBindApplication(ProcessRecord app) {
        for (int i = this.mWindowingModeTransitionInfo.size() - 1; i >= 0; i--) {
            WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.valueAt(i);
            if (info.launchedActivity.appInfo == app.info) {
                info.bindApplicationDelayMs = calculateCurrentDelay();
            }
        }
    }

    private boolean allWindowsDrawn() {
        for (int index = this.mWindowingModeTransitionInfo.size() - 1; index >= 0; index--) {
            if (!this.mWindowingModeTransitionInfo.valueAt(index).loggedWindowsDrawn) {
                return false;
            }
        }
        return true;
    }

    private boolean isAnyTransitionActive() {
        return this.mCurrentTransitionStartTime != -1 && this.mWindowingModeTransitionInfo.size() > 0;
    }

    private void reset(boolean abort, WindowingModeTransitionInfo info) {
        if (ActivityManagerDebugConfig.DEBUG_METRICS) {
            Slog.i(TAG, "reset abort=" + abort);
        }
        if (!abort && isAnyTransitionActive()) {
            logAppTransitionMultiEvents();
        }
        stopLaunchTrace(info);
        this.mCurrentTransitionStartTime = -1L;
        this.mCurrentTransitionDelayMs = -1;
        this.mLoggedTransitionStarting = false;
        this.mWindowingModeTransitionInfo.clear();
    }

    private int calculateCurrentDelay() {
        return (int) (SystemClock.uptimeMillis() - this.mCurrentTransitionStartTime);
    }

    private int calculateDelay(long timestamp) {
        return (int) (timestamp - this.mCurrentTransitionStartTime);
    }

    private void logAppTransitionCancel(WindowingModeTransitionInfo info) {
        int type = getTransitionType(info);
        if (type == -1) {
            return;
        }
        LogMaker builder = new LogMaker(1144);
        builder.setPackageName(info.launchedActivity.packageName);
        builder.setType(type);
        builder.addTaggedData(871, info.launchedActivity.info.name);
        this.mMetricsLogger.write(builder);
        StatsLog.write(49, info.launchedActivity.appInfo.uid, info.launchedActivity.packageName, convertAppStartTransitionType(type), info.launchedActivity.info.name);
    }

    private void logAppTransitionMultiEvents() {
        if (ActivityManagerDebugConfig.DEBUG_METRICS) {
            Slog.i(TAG, "logging transition events");
        }
        for (int index = this.mWindowingModeTransitionInfo.size() - 1; index >= 0; index--) {
            WindowingModeTransitionInfo info = this.mWindowingModeTransitionInfo.valueAt(index);
            int type = getTransitionType(info);
            if (type == -1) {
                return;
            }
            final WindowingModeTransitionInfoSnapshot infoSnapshot = new WindowingModeTransitionInfoSnapshot(info);
            final int currentTransitionDeviceUptime = this.mCurrentTransitionDeviceUptime;
            final int currentTransitionDelayMs = this.mCurrentTransitionDelayMs;
            BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityMetricsLogger$EXtnEt47a9lJOX0u5R1TXhfh0XE
                @Override // java.lang.Runnable
                public final void run() {
                    ActivityMetricsLogger.this.logAppTransition(currentTransitionDeviceUptime, currentTransitionDelayMs, infoSnapshot);
                }
            });
            BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityMetricsLogger$EC9JWHkuhz-8G6tyBRq_BEve0P4
                @Override // java.lang.Runnable
                public final void run() {
                    ActivityMetricsLogger.this.logAppDisplayed(infoSnapshot);
                }
            });
            info.launchedActivity.info.launchToken = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logAppTransition(int currentTransitionDeviceUptime, int currentTransitionDelayMs, WindowingModeTransitionInfoSnapshot info) {
        PackageOptimizationInfo createWithNoInfo;
        LogMaker builder = new LogMaker(761);
        builder.setPackageName(info.packageName);
        builder.setType(info.type);
        builder.addTaggedData(871, info.launchedActivityName);
        boolean isInstantApp = info.applicationInfo.isInstantApp();
        if (info.launchedActivityLaunchedFromPackage != null) {
            builder.addTaggedData(904, info.launchedActivityLaunchedFromPackage);
        }
        String launchToken = info.launchedActivityLaunchToken;
        if (launchToken != null) {
            builder.addTaggedData(903, launchToken);
        }
        builder.addTaggedData(905, Integer.valueOf(isInstantApp ? 1 : 0));
        builder.addTaggedData(325, Integer.valueOf(currentTransitionDeviceUptime));
        builder.addTaggedData(319, Integer.valueOf(currentTransitionDelayMs));
        builder.setSubtype(info.reason);
        if (info.startingWindowDelayMs != -1) {
            builder.addTaggedData(321, Integer.valueOf(info.startingWindowDelayMs));
        }
        if (info.bindApplicationDelayMs != -1) {
            builder.addTaggedData(945, Integer.valueOf(info.bindApplicationDelayMs));
        }
        builder.addTaggedData(322, Integer.valueOf(info.windowsDrawnDelayMs));
        ArtManagerInternal artManagerInternal = getArtManagerInternal();
        if (artManagerInternal == null || info.launchedActivityAppRecordRequiredAbi == null) {
            createWithNoInfo = PackageOptimizationInfo.createWithNoInfo();
        } else {
            createWithNoInfo = artManagerInternal.getPackageOptimizationInfo(info.applicationInfo, info.launchedActivityAppRecordRequiredAbi);
        }
        PackageOptimizationInfo packageOptimizationInfo = createWithNoInfo;
        builder.addTaggedData(1321, Integer.valueOf(packageOptimizationInfo.getCompilationReason()));
        builder.addTaggedData(1320, Integer.valueOf(packageOptimizationInfo.getCompilationFilter()));
        this.mMetricsLogger.write(builder);
        StatsLog.write(48, info.applicationInfo.uid, info.packageName, convertAppStartTransitionType(info.type), info.launchedActivityName, info.launchedActivityLaunchedFromPackage, isInstantApp, currentTransitionDeviceUptime * 1000, info.reason, currentTransitionDelayMs, info.startingWindowDelayMs, info.bindApplicationDelayMs, info.windowsDrawnDelayMs, launchToken, packageOptimizationInfo.getCompilationReason(), packageOptimizationInfo.getCompilationFilter());
        logAppStartMemoryStateCapture(info);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logAppDisplayed(WindowingModeTransitionInfoSnapshot info) {
        if (info.type != 8 && info.type != 7) {
            return;
        }
        EventLog.writeEvent((int) EventLogTags.AM_ACTIVITY_LAUNCH_TIME, Integer.valueOf(info.userId), Integer.valueOf(info.activityRecordIdHashCode), info.launchedActivityShortComponentName, Integer.valueOf(info.windowsDrawnDelayMs));
        StringBuilder sb = this.mStringBuilder;
        sb.setLength(0);
        sb.append("Displayed ");
        sb.append(info.launchedActivityShortComponentName);
        sb.append(": ");
        TimeUtils.formatDuration(info.windowsDrawnDelayMs, sb);
        Log.i(TAG, sb.toString());
    }

    private int convertAppStartTransitionType(int tronType) {
        if (tronType == 7) {
            return 3;
        }
        if (tronType == 8) {
            return 1;
        }
        if (tronType == 9) {
            return 2;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowingModeTransitionInfoSnapshot logAppTransitionReportedDrawn(ActivityRecord r, boolean restoredFromBundle) {
        int i;
        int i2;
        WindowingModeTransitionInfo info = this.mLastWindowingModeTransitionInfo.get(r.getWindowingMode());
        if (info == null) {
            return null;
        }
        LogMaker builder = new LogMaker(1090);
        builder.setPackageName(r.packageName);
        builder.addTaggedData(871, r.info.name);
        long startupTimeMs = SystemClock.uptimeMillis() - this.mLastTransitionStartTime;
        builder.addTaggedData(1091, Long.valueOf(startupTimeMs));
        if (restoredFromBundle) {
            i = 13;
        } else {
            i = 12;
        }
        builder.setType(i);
        builder.addTaggedData(324, Integer.valueOf(info.currentTransitionProcessRunning ? 1 : 0));
        this.mMetricsLogger.write(builder);
        int i3 = info.launchedActivity.appInfo.uid;
        String str = info.launchedActivity.packageName;
        if (restoredFromBundle) {
            i2 = 1;
        } else {
            i2 = 2;
        }
        StatsLog.write(50, i3, str, i2, info.launchedActivity.info.name, info.currentTransitionProcessRunning, startupTimeMs);
        stopFullyDrawnTraceIfNeeded();
        final WindowingModeTransitionInfoSnapshot infoSnapshot = new WindowingModeTransitionInfoSnapshot(info, r, (int) startupTimeMs);
        BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityMetricsLogger$hV69RM6bGwI03lqB25i1eiypVyE
            @Override // java.lang.Runnable
            public final void run() {
                ActivityMetricsLogger.this.logAppFullyDrawn(infoSnapshot);
            }
        });
        return infoSnapshot;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logAppFullyDrawn(WindowingModeTransitionInfoSnapshot info) {
        if (info.type != 8 && info.type != 7) {
            return;
        }
        StringBuilder sb = this.mStringBuilder;
        sb.setLength(0);
        sb.append("Fully drawn ");
        sb.append(info.launchedActivityShortComponentName);
        sb.append(": ");
        TimeUtils.formatDuration(info.windowsFullyDrawnDelayMs, sb);
        Log.i(TAG, sb.toString());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void logActivityStart(Intent intent, ProcessRecord callerApp, ActivityRecord r, int callingUid, String callingPackage, int callingUidProcState, boolean callingUidHasAnyVisibleWindow, int realCallingUid, int realCallingUidProcState, boolean realCallingUidHasAnyVisibleWindow, int targetUid, String targetPackage, int targetUidProcState, boolean targetUidHasAnyVisibleWindow, String targetWhitelistTag, boolean comingFromPendingIntent) {
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowUptime = SystemClock.uptimeMillis();
        LogMaker builder = new LogMaker(1513);
        builder.setTimestamp(System.currentTimeMillis());
        builder.addTaggedData(1514, Integer.valueOf(callingUid));
        builder.addTaggedData(1515, callingPackage);
        builder.addTaggedData(1516, Integer.valueOf(ActivityManager.processStateAmToProto(callingUidProcState)));
        builder.addTaggedData(1517, Integer.valueOf(callingUidHasAnyVisibleWindow ? 1 : 0));
        builder.addTaggedData(1518, Integer.valueOf(realCallingUid));
        builder.addTaggedData(1519, Integer.valueOf(ActivityManager.processStateAmToProto(realCallingUidProcState)));
        builder.addTaggedData(1520, Integer.valueOf(realCallingUidHasAnyVisibleWindow ? 1 : 0));
        builder.addTaggedData(1521, Integer.valueOf(targetUid));
        builder.addTaggedData(1522, targetPackage);
        builder.addTaggedData(1523, Integer.valueOf(ActivityManager.processStateAmToProto(targetUidProcState)));
        builder.addTaggedData(1524, Integer.valueOf(targetUidHasAnyVisibleWindow ? 1 : 0));
        builder.addTaggedData(1525, targetWhitelistTag);
        builder.addTaggedData(1526, r.shortComponentName);
        builder.addTaggedData(1527, Integer.valueOf(comingFromPendingIntent ? 1 : 0));
        builder.addTaggedData(1528, intent.getAction());
        if (callerApp != null) {
            builder.addTaggedData(1529, callerApp.processName);
            builder.addTaggedData(1530, Integer.valueOf(ActivityManager.processStateAmToProto(callerApp.curProcState)));
            builder.addTaggedData(1531, Integer.valueOf(callerApp.hasClientActivities ? 1 : 0));
            builder.addTaggedData(1532, Integer.valueOf(callerApp.hasForegroundServices() ? 1 : 0));
            builder.addTaggedData(1533, Integer.valueOf(callerApp.foregroundActivities ? 1 : 0));
            builder.addTaggedData(1534, Integer.valueOf(callerApp.hasTopUi ? 1 : 0));
            builder.addTaggedData(1535, Integer.valueOf(callerApp.hasOverlayUi ? 1 : 0));
            builder.addTaggedData(1536, Integer.valueOf(callerApp.pendingUiClean ? 1 : 0));
            if (callerApp.interactionEventTime != 0) {
                builder.addTaggedData((int) UsbTerminalTypes.TERMINAL_EXTERN_ANALOG, Long.valueOf(nowElapsed - callerApp.interactionEventTime));
            }
            if (callerApp.fgInteractionTime != 0) {
                builder.addTaggedData((int) UsbTerminalTypes.TERMINAL_EXTERN_DIGITAL, Long.valueOf(nowElapsed - callerApp.fgInteractionTime));
            }
            if (callerApp.whenUnimportant != 0) {
                builder.addTaggedData((int) UsbTerminalTypes.TERMINAL_EXTERN_LINE, Long.valueOf(nowUptime - callerApp.whenUnimportant));
            }
        }
        builder.addTaggedData((int) UsbTerminalTypes.TERMINAL_EXTERN_LEGACY, Integer.valueOf(r.info.launchMode));
        builder.addTaggedData((int) UsbTerminalTypes.TERMINAL_EXTERN_SPIDF, r.info.targetActivity);
        builder.addTaggedData((int) UsbTerminalTypes.TERMINAL_EXTERN_1394DA, Integer.valueOf(r.info.flags));
        builder.addTaggedData((int) UsbTerminalTypes.TERMINAL_EXTERN_1394DV, r.realActivity.toShortString());
        builder.addTaggedData(1544, r.shortComponentName);
        builder.addTaggedData(1545, r.processName);
        builder.addTaggedData(1546, Integer.valueOf(r.fullscreen ? 1 : 0));
        builder.addTaggedData(1547, Integer.valueOf(r.noDisplay ? 1 : 0));
        if (r.lastVisibleTime != 0) {
            builder.addTaggedData(1548, Long.valueOf(nowUptime - r.lastVisibleTime));
        }
        if (r.resultTo != null) {
            builder.addTaggedData(1549, r.resultTo.packageName);
            builder.addTaggedData(1550, r.resultTo.shortComponentName);
        }
        builder.addTaggedData(1551, Integer.valueOf(r.visible ? 1 : 0));
        builder.addTaggedData(1552, Integer.valueOf(r.visibleIgnoringKeyguard ? 1 : 0));
        if (r.lastLaunchTime != 0) {
            builder.addTaggedData(1553, Long.valueOf(nowUptime - r.lastLaunchTime));
        }
        this.mMetricsLogger.write(builder);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getTransitionType(WindowingModeTransitionInfo info) {
        if (info.currentTransitionProcessRunning) {
            if (info.startResult != 0) {
                if (info.startResult == 2) {
                    return 9;
                }
                return -1;
            }
            return 8;
        } else if (info.startResult == 0) {
            return 7;
        } else {
            return -1;
        }
    }

    private void logAppStartMemoryStateCapture(WindowingModeTransitionInfoSnapshot info) {
        if (info.processRecord != null) {
            int pid = info.processRecord.pid;
            int uid = info.applicationInfo.uid;
            MemoryStatUtil.MemoryStat memoryStat = MemoryStatUtil.readMemoryStatFromFilesystem(uid, pid);
            if (memoryStat == null) {
                if (ActivityManagerDebugConfig.DEBUG_METRICS) {
                    Slog.i(TAG, "logAppStartMemoryStateCapture memoryStat null");
                    return;
                }
                return;
            }
            StatsLog.write(55, uid, info.processName, info.launchedActivityName, memoryStat.pgfault, memoryStat.pgmajfault, memoryStat.rssInBytes, memoryStat.cacheInBytes, memoryStat.swapInBytes);
        } else if (ActivityManagerDebugConfig.DEBUG_METRICS) {
            Slog.i(TAG, "logAppStartMemoryStateCapture processRecord null");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ProcessRecord findProcessForActivity(ActivityRecord launchedActivity) {
        if (launchedActivity != null) {
            return (ProcessRecord) this.mSupervisor.mService.mProcessNames.get(launchedActivity.processName, launchedActivity.appInfo.uid);
        }
        return null;
    }

    private ArtManagerInternal getArtManagerInternal() {
        if (this.mArtManagerInternal == null) {
            this.mArtManagerInternal = (ArtManagerInternal) LocalServices.getService(ArtManagerInternal.class);
        }
        return this.mArtManagerInternal;
    }

    private void startTraces(WindowingModeTransitionInfo info) {
        if (info == null) {
            return;
        }
        stopFullyDrawnTraceIfNeeded();
        int transitionType = getTransitionType(info);
        if ((!info.launchTraceActive && transitionType == 8) || transitionType == 7) {
            Trace.asyncTraceBegin(64L, "launching: " + info.launchedActivity.packageName, 0);
            Trace.asyncTraceBegin(64L, "drawing", 0);
            this.mDrawingTraceActive = true;
            info.launchTraceActive = true;
        }
    }

    private void stopLaunchTrace(WindowingModeTransitionInfo info) {
        if (info != null && info.launchTraceActive) {
            Trace.asyncTraceEnd(64L, "launching: " + info.launchedActivity.packageName, 0);
            info.launchTraceActive = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopFullyDrawnTraceIfNeeded() {
        if (this.mDrawingTraceActive) {
            Trace.asyncTraceEnd(64L, "drawing", 0);
            this.mDrawingTraceActive = false;
        }
    }
}
