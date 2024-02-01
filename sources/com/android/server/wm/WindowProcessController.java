package com.android.server.wm;

import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.app.servertransaction.ConfigurationChangeItem;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.PackageManagerService;
import com.android.server.wm.ActivityStack;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/* loaded from: classes2.dex */
public class WindowProcessController extends ConfigurationContainer<ConfigurationContainer> implements ConfigurationContainerListener {
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_CONFIGURATION = "ActivityTaskManager";
    private static final String TAG_RELEASE = "ActivityTaskManager";
    private volatile boolean mAllowBackgroundActivityStarts;
    private final ActivityTaskManagerService mAtm;
    private volatile boolean mCrashing;
    private volatile int mCurSchedGroup;
    private volatile boolean mDebugging;
    private volatile long mFgInteractionTime;
    private volatile boolean mHasClientActivities;
    private volatile boolean mHasForegroundActivities;
    private volatile boolean mHasForegroundServices;
    private volatile boolean mHasOverlayUi;
    private volatile boolean mHasTopUi;
    final ApplicationInfo mInfo;
    private volatile boolean mInstrumenting;
    private volatile boolean mInstrumentingWithBackgroundActivityStartPrivileges;
    private volatile long mInteractionEventTime;
    private long mLastActivityFinishTime;
    private long mLastActivityLaunchTime;
    private final WindowProcessListener mListener;
    final String mName;
    private volatile boolean mNotResponding;
    public final Object mOwner;
    private volatile boolean mPendingUiClean;
    private volatile boolean mPerceptible;
    private volatile boolean mPersistent;
    private volatile int mPid;
    private volatile String mRequiredAbi;
    private boolean mRunningRecentsAnimation;
    private boolean mRunningRemoteAnimation;
    private IApplicationThread mThread;
    final int mUid;
    final int mUserId;
    private volatile boolean mUsingWrapper;
    int mVrThreadTid;
    private volatile long mWhenUnimportant;
    final ArraySet<String> mPkgList = new ArraySet<>();
    private volatile int mCurProcState = 21;
    private volatile int mRepProcState = 21;
    private volatile ArraySet<Integer> mBoundClientUids = new ArraySet<>();
    private final ArrayList<ActivityRecord> mActivities = new ArrayList<>();
    private final ArrayList<TaskRecord> mRecentTasks = new ArrayList<>();
    private ActivityRecord mPreQTopResumedActivity = null;
    private final Configuration mLastReportedConfiguration = new Configuration();
    private int mDisplayId = -1;

    /* loaded from: classes2.dex */
    public interface ComputeOomAdjCallback {
        void onOtherActivity();

        void onPausedActivity();

        void onStoppingActivity(boolean z);

        void onVisibleActivity();
    }

    public WindowProcessController(ActivityTaskManagerService atm, ApplicationInfo info, String name, int uid, int userId, Object owner, WindowProcessListener listener) {
        this.mInfo = info;
        this.mName = name;
        this.mUid = uid;
        this.mUserId = userId;
        this.mOwner = owner;
        this.mListener = listener;
        this.mAtm = atm;
        if (atm != null) {
            onConfigurationChanged(atm.getGlobalConfiguration());
        }
    }

    public void setPid(int pid) {
        this.mPid = pid;
    }

    public int getPid() {
        return this.mPid;
    }

    public void setThread(IApplicationThread thread) {
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            this.mThread = thread;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IApplicationThread getThread() {
        return this.mThread;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasThread() {
        return this.mThread != null;
    }

    public void setCurrentSchedulingGroup(int curSchedGroup) {
        this.mCurSchedGroup = curSchedGroup;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurrentSchedulingGroup() {
        return this.mCurSchedGroup;
    }

    public void setCurrentProcState(int curProcState) {
        this.mCurProcState = curProcState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurrentProcState() {
        return this.mCurProcState;
    }

    public void setReportedProcState(int repProcState) {
        this.mRepProcState = repProcState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getReportedProcState() {
        return this.mRepProcState;
    }

    public void setCrashing(boolean crashing) {
        this.mCrashing = crashing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCrashing() {
        return this.mCrashing;
    }

    public void setNotResponding(boolean notResponding) {
        this.mNotResponding = notResponding;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNotResponding() {
        return this.mNotResponding;
    }

    public void setPersistent(boolean persistent) {
        this.mPersistent = persistent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPersistent() {
        return this.mPersistent;
    }

    public void setHasForegroundServices(boolean hasForegroundServices) {
        this.mHasForegroundServices = hasForegroundServices;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasForegroundServices() {
        return this.mHasForegroundServices;
    }

    public void setHasForegroundActivities(boolean hasForegroundActivities) {
        this.mHasForegroundActivities = hasForegroundActivities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasForegroundActivities() {
        return this.mHasForegroundActivities;
    }

    public void setHasClientActivities(boolean hasClientActivities) {
        this.mHasClientActivities = hasClientActivities;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasClientActivities() {
        return this.mHasClientActivities;
    }

    public void setHasTopUi(boolean hasTopUi) {
        this.mHasTopUi = hasTopUi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasTopUi() {
        return this.mHasTopUi;
    }

    public void setHasOverlayUi(boolean hasOverlayUi) {
        this.mHasOverlayUi = hasOverlayUi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasOverlayUi() {
        return this.mHasOverlayUi;
    }

    public void setPendingUiClean(boolean hasPendingUiClean) {
        this.mPendingUiClean = hasPendingUiClean;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasPendingUiClean() {
        return this.mPendingUiClean;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean registeredForDisplayConfigChanges() {
        return this.mDisplayId != -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postPendingUiCleanMsg(boolean pendingUiClean) {
        WindowProcessListener windowProcessListener = this.mListener;
        if (windowProcessListener == null) {
            return;
        }
        Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$zP5AObb0-v-Zzwr-v8NXOg4Yt1c
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((WindowProcessListener) obj).setPendingUiClean(((Boolean) obj2).booleanValue());
            }
        }, windowProcessListener, Boolean.valueOf(pendingUiClean));
        this.mAtm.mH.sendMessage(m);
    }

    public void setInteractionEventTime(long interactionEventTime) {
        this.mInteractionEventTime = interactionEventTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getInteractionEventTime() {
        return this.mInteractionEventTime;
    }

    public void setFgInteractionTime(long fgInteractionTime) {
        this.mFgInteractionTime = fgInteractionTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getFgInteractionTime() {
        return this.mFgInteractionTime;
    }

    public void setWhenUnimportant(long whenUnimportant) {
        this.mWhenUnimportant = whenUnimportant;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getWhenUnimportant() {
        return this.mWhenUnimportant;
    }

    public void setRequiredAbi(String requiredAbi) {
        this.mRequiredAbi = requiredAbi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getRequiredAbi() {
        return this.mRequiredAbi;
    }

    @VisibleForTesting
    int getDisplayId() {
        return this.mDisplayId;
    }

    public void setDebugging(boolean debugging) {
        this.mDebugging = debugging;
    }

    boolean isDebugging() {
        return this.mDebugging;
    }

    public void setUsingWrapper(boolean usingWrapper) {
        this.mUsingWrapper = usingWrapper;
    }

    boolean isUsingWrapper() {
        return this.mUsingWrapper;
    }

    void setLastActivityLaunchTime(long launchTime) {
        if (launchTime <= this.mLastActivityLaunchTime) {
            return;
        }
        this.mLastActivityLaunchTime = launchTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastActivityFinishTimeIfNeeded(long finishTime) {
        if (finishTime <= this.mLastActivityFinishTime || !hasActivityInVisibleTask()) {
            return;
        }
        this.mLastActivityFinishTime = finishTime;
    }

    public void setAllowBackgroundActivityStarts(boolean allowBackgroundActivityStarts) {
        this.mAllowBackgroundActivityStarts = allowBackgroundActivityStarts;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean areBackgroundActivityStartsAllowed() {
        if (this.mAllowBackgroundActivityStarts) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        return ((now - this.mLastActivityLaunchTime < JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY || now - this.mLastActivityFinishTime < JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY) && (this.mLastActivityLaunchTime > this.mAtm.getLastStopAppSwitchesTime() || this.mLastActivityFinishTime > this.mAtm.getLastStopAppSwitchesTime())) || this.mInstrumentingWithBackgroundActivityStartPrivileges || hasActivityInVisibleTask() || isBoundByForegroundUid();
    }

    private boolean isBoundByForegroundUid() {
        for (int i = this.mBoundClientUids.size() - 1; i >= 0; i--) {
            if (this.mAtm.isUidForeground(this.mBoundClientUids.valueAt(i).intValue())) {
                return true;
            }
        }
        return false;
    }

    public void setBoundClientUids(ArraySet<Integer> boundClientUids) {
        this.mBoundClientUids = boundClientUids;
    }

    public void setInstrumenting(boolean instrumenting, boolean hasBackgroundActivityStartPrivileges) {
        this.mInstrumenting = instrumenting;
        this.mInstrumentingWithBackgroundActivityStartPrivileges = hasBackgroundActivityStartPrivileges;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInstrumenting() {
        return this.mInstrumenting;
    }

    public void setPerceptible(boolean perceptible) {
        this.mPerceptible = perceptible;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPerceptible() {
        return this.mPerceptible;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected int getChildCount() {
        return 0;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getChildAt(int index) {
        return null;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    protected ConfigurationContainer getParent() {
        return null;
    }

    public void addPackage(String packageName) {
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            this.mPkgList.add(packageName);
        }
    }

    public void clearPackageList() {
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            this.mPkgList.clear();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addActivityIfNeeded(ActivityRecord r) {
        setLastActivityLaunchTime(r.lastLaunchTime);
        if (this.mActivities.contains(r)) {
            return;
        }
        this.mActivities.add(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeActivity(ActivityRecord r) {
        this.mActivities.remove(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void makeFinishingForProcessRemoved() {
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            this.mActivities.get(i).makeFinishingLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearActivities() {
        this.mActivities.clear();
    }

    public boolean hasActivities() {
        boolean z;
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            z = !this.mActivities.isEmpty();
        }
        return z;
    }

    public boolean hasVisibleActivities() {
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            for (int i = this.mActivities.size() - 1; i >= 0; i--) {
                ActivityRecord r = this.mActivities.get(i);
                if (r.visible) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean hasActivitiesOrRecentTasks() {
        boolean z;
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            z = (this.mActivities.isEmpty() && this.mRecentTasks.isEmpty()) ? false : true;
        }
        return z;
    }

    private boolean hasActivityInVisibleTask() {
        ActivityRecord topActivity;
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            TaskRecord task = this.mActivities.get(i).getTaskRecord();
            if (task != null && (topActivity = task.getTopActivity()) != null && topActivity.visible) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateTopResumingActivityInProcessIfNeeded(ActivityRecord activity) {
        ActivityStack stack;
        if (this.mInfo.targetSdkVersion >= 29 || this.mPreQTopResumedActivity == activity) {
            return true;
        }
        ActivityDisplay display = activity.getDisplay();
        if (display == null) {
            return false;
        }
        boolean canUpdate = false;
        ActivityRecord activityRecord = this.mPreQTopResumedActivity;
        ActivityDisplay topDisplay = activityRecord != null ? activityRecord.getDisplay() : null;
        canUpdate = (topDisplay != null && this.mPreQTopResumedActivity.visible && this.mPreQTopResumedActivity.isFocusable()) ? true : true;
        if (!canUpdate && topDisplay.mDisplayContent.compareTo((WindowContainer) display.mDisplayContent) < 0) {
            canUpdate = true;
        }
        if (display == topDisplay && this.mPreQTopResumedActivity.getActivityStack().mTaskStack.compareTo((WindowContainer) activity.getActivityStack().mTaskStack) <= 0) {
            canUpdate = true;
        }
        if (canUpdate) {
            ActivityRecord activityRecord2 = this.mPreQTopResumedActivity;
            if (activityRecord2 != null && activityRecord2.isState(ActivityStack.ActivityState.RESUMED) && (stack = this.mPreQTopResumedActivity.getActivityStack()) != null) {
                stack.startPausingLocked(false, false, null, false);
            }
            this.mPreQTopResumedActivity = activity;
        }
        return canUpdate;
    }

    public void stopFreezingActivities() {
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                int i = this.mActivities.size();
                while (i > 0) {
                    i--;
                    this.mActivities.get(i).stopFreezingScreenLocked(true);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishActivities() {
        ArrayList<ActivityRecord> activities = new ArrayList<>(this.mActivities);
        for (int i = 0; i < activities.size(); i++) {
            ActivityRecord r = activities.get(i);
            if (!r.finishing && r.isInStackLocked()) {
                r.getActivityStack().finishActivityLocked(r, 0, null, "finish-heavy", true);
            }
        }
    }

    public boolean isInterestingToUser() {
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                int size = this.mActivities.size();
                for (int i = 0; i < size; i++) {
                    ActivityRecord r = this.mActivities.get(i);
                    if (r.isInterestingToUserLocked()) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return true;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean hasRunningActivity(String packageName) {
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                for (int i = this.mActivities.size() - 1; i >= 0; i--) {
                    ActivityRecord r = this.mActivities.get(i);
                    if (packageName.equals(r.packageName)) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return true;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void clearPackagePreferredForHomeActivities() {
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                for (int i = this.mActivities.size() - 1; i >= 0; i--) {
                    ActivityRecord r = this.mActivities.get(i);
                    if (r.isActivityTypeHome()) {
                        Log.i("ActivityTaskManager", "Clearing package preferred activities from " + r.packageName);
                        try {
                            ActivityThread.getPackageManager().clearPackagePreferredActivities(r.packageName);
                        } catch (RemoteException e) {
                        }
                    }
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasStartedActivity(ActivityRecord launchedActivity) {
        for (int i = this.mActivities.size() - 1; i >= 0; i--) {
            ActivityRecord activity = this.mActivities.get(i);
            if (launchedActivity != activity && !activity.stopped) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateIntentForHeavyWeightActivity(Intent intent) {
        if (this.mActivities.isEmpty()) {
            return;
        }
        ActivityRecord hist = this.mActivities.get(0);
        intent.putExtra("cur_app", hist.packageName);
        intent.putExtra("cur_task", hist.getTaskRecord().taskId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldKillProcessForRemovedTask(TaskRecord tr) {
        for (int k = 0; k < this.mActivities.size(); k++) {
            ActivityRecord activity = this.mActivities.get(k);
            if (!activity.stopped) {
                return false;
            }
            TaskRecord otherTask = activity.getTaskRecord();
            if (tr.taskId != otherTask.taskId && otherTask.inRecents) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArraySet<TaskRecord> getReleaseSomeActivitiesTasks() {
        TaskRecord firstTask = null;
        ArraySet<TaskRecord> tasks = null;
        if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
            Slog.d("ActivityTaskManager", "Trying to release some activities in " + this);
        }
        for (int i = 0; i < this.mActivities.size(); i++) {
            ActivityRecord r = this.mActivities.get(i);
            if (r.finishing || r.isState(ActivityStack.ActivityState.DESTROYING, ActivityStack.ActivityState.DESTROYED)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
                    Slog.d("ActivityTaskManager", "Abort release; already destroying: " + r);
                    return null;
                }
                return null;
            }
            if (r.visible || !r.stopped || !r.haveState || r.isState(ActivityStack.ActivityState.RESUMED, ActivityStack.ActivityState.PAUSING, ActivityStack.ActivityState.PAUSED, ActivityStack.ActivityState.STOPPING)) {
                if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
                    Slog.d("ActivityTaskManager", "Not releasing in-use activity: " + r);
                }
            } else {
                TaskRecord task = r.getTaskRecord();
                if (task != null) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_RELEASE) {
                        Slog.d("ActivityTaskManager", "Collecting release task " + task + " from " + r);
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
        return tasks;
    }

    public int computeOomAdjFromActivities(int minTaskLayer, ComputeOomAdjCallback callback) {
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            int activitiesSize = this.mActivities.size();
            int j = 0;
            while (true) {
                if (j >= activitiesSize) {
                    break;
                }
                ActivityRecord r = this.mActivities.get(j);
                if (r.app != this) {
                    Log.e("ActivityTaskManager", "Found activity " + r + " in proc activity list using " + r.app + " instead of expected " + this);
                    if (r.app != null && r.app.mUid != this.mUid) {
                        j++;
                    } else {
                        r.setProcess(this);
                    }
                }
                if (r.visible) {
                    callback.onVisibleActivity();
                    TaskRecord task = r.getTaskRecord();
                    if (task != null && minTaskLayer > 0) {
                        int layer = task.mLayerRank;
                        if (layer >= 0 && minTaskLayer > layer) {
                            minTaskLayer = layer;
                        }
                    }
                } else {
                    if (r.isState(ActivityStack.ActivityState.PAUSING, ActivityStack.ActivityState.PAUSED)) {
                        callback.onPausedActivity();
                    } else if (r.isState(ActivityStack.ActivityState.STOPPING)) {
                        callback.onStoppingActivity(r.finishing);
                    } else {
                        callback.onOtherActivity();
                    }
                    j++;
                }
            }
        }
        return minTaskLayer;
    }

    public int computeRelaunchReason() {
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                int activitiesSize = this.mActivities.size();
                for (int i = activitiesSize - 1; i >= 0; i--) {
                    ActivityRecord r = this.mActivities.get(i);
                    if (r.mRelaunchReason != 0) {
                        int i2 = r.mRelaunchReason;
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return i2;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return 0;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public long getInputDispatchingTimeout() {
        long j;
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                j = (isInstrumenting() || isUsingWrapper()) ? 60000L : 5000L;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return j;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearProfilerIfNeeded() {
        if (this.mListener == null) {
            return;
        }
        this.mAtm.mH.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.wm.-$$Lambda$9Kj79s-YFqaGRhFHazfExnbZExw
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowProcessListener) obj).clearProfilerIfNeeded();
            }
        }, this.mListener));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateProcessInfo(boolean updateServiceConnectionActivities, boolean activityChange, boolean updateOomAdj) {
        WindowProcessListener windowProcessListener = this.mListener;
        if (windowProcessListener == null) {
            return;
        }
        Message m = PooledLambda.obtainMessage(new QuadConsumer() { // from class: com.android.server.wm.-$$Lambda$BEx3OWenCvYAaV5h_J2ZkZXhEcY
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4) {
                ((WindowProcessListener) obj).updateProcessInfo(((Boolean) obj2).booleanValue(), ((Boolean) obj3).booleanValue(), ((Boolean) obj4).booleanValue());
            }
        }, windowProcessListener, Boolean.valueOf(updateServiceConnectionActivities), Boolean.valueOf(activityChange), Boolean.valueOf(updateOomAdj));
        this.mAtm.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateServiceConnectionActivities() {
        if (this.mListener == null) {
            return;
        }
        this.mAtm.mH.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.wm.-$$Lambda$HLz_SQuxQoIiuaK5SB5xJ6FnoxY
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowProcessListener) obj).updateServiceConnectionActivities();
            }
        }, this.mListener));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPendingUiCleanAndForceProcessStateUpTo(int newState) {
        WindowProcessListener windowProcessListener = this.mListener;
        if (windowProcessListener == null) {
            return;
        }
        Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$LI60v4Y5Me6khV12IZ-zEQtSx7A
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((WindowProcessListener) obj).setPendingUiCleanAndForceProcessStateUpTo(((Integer) obj2).intValue());
            }
        }, windowProcessListener, Integer.valueOf(newState));
        this.mAtm.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRemoved() {
        WindowProcessListener windowProcessListener = this.mListener;
        if (windowProcessListener == null) {
            return false;
        }
        return windowProcessListener.isRemoved();
    }

    private boolean shouldSetProfileProc() {
        return this.mAtm.mProfileApp != null && this.mAtm.mProfileApp.equals(this.mName) && (this.mAtm.mProfileProc == null || this.mAtm.mProfileProc == this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProfilerInfo createProfilerInfoIfNeeded() {
        ProfilerInfo currentProfilerInfo = this.mAtm.mProfilerInfo;
        if (currentProfilerInfo == null || currentProfilerInfo.profileFile == null || !shouldSetProfileProc()) {
            return null;
        }
        if (currentProfilerInfo.profileFd != null) {
            try {
                currentProfilerInfo.profileFd = currentProfilerInfo.profileFd.dup();
            } catch (IOException e) {
                currentProfilerInfo.closeFd();
            }
        }
        return new ProfilerInfo(currentProfilerInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onStartActivity(int topProcessState, ActivityInfo info) {
        if (this.mListener == null) {
            return;
        }
        String packageName = null;
        if ((info.flags & 1) == 0 || !PackageManagerService.PLATFORM_PACKAGE_NAME.equals(info.packageName)) {
            packageName = info.packageName;
        }
        Message m = PooledLambda.obtainMessage(new QuintConsumer() { // from class: com.android.server.wm.-$$Lambda$VY87MmFWaCLMkNa2qHGaPrThyrI
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5) {
                ((WindowProcessListener) obj).onStartActivity(((Integer) obj2).intValue(), ((Boolean) obj3).booleanValue(), (String) obj4, ((Long) obj5).longValue());
            }
        }, this.mListener, Integer.valueOf(topProcessState), Boolean.valueOf(shouldSetProfileProc()), packageName, Long.valueOf(info.applicationInfo.longVersionCode));
        this.mAtm.mH.sendMessageAtFrontOfQueue(m);
    }

    public void appDied() {
        WindowProcessListener windowProcessListener = this.mListener;
        if (windowProcessListener == null) {
            return;
        }
        Message m = PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.wm.-$$Lambda$MGgYXq0deCsjjGP-28PM6ahiI2U
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowProcessListener) obj).appDied();
            }
        }, windowProcessListener);
        this.mAtm.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerDisplayConfigurationListenerLocked(ActivityDisplay activityDisplay) {
        if (activityDisplay == null) {
            return;
        }
        unregisterDisplayConfigurationListenerLocked();
        this.mDisplayId = activityDisplay.mDisplayId;
        activityDisplay.registerConfigurationChangeListener(this);
    }

    @VisibleForTesting
    void unregisterDisplayConfigurationListenerLocked() {
        if (this.mDisplayId == -1) {
            return;
        }
        ActivityDisplay activityDisplay = this.mAtm.mRootActivityContainer.getActivityDisplay(this.mDisplayId);
        if (activityDisplay != null) {
            activityDisplay.unregisterConfigurationChangeListener(this);
        }
        this.mDisplayId = -1;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newGlobalConfig) {
        super.onConfigurationChanged(newGlobalConfig);
        updateConfiguration();
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onRequestedOverrideConfigurationChanged(Configuration newOverrideConfig) {
        super.onRequestedOverrideConfigurationChanged(newOverrideConfig);
        updateConfiguration();
    }

    private void updateConfiguration() {
        Configuration config = getConfiguration();
        if (this.mLastReportedConfiguration.diff(config) == 0) {
            return;
        }
        try {
            if (this.mThread == null) {
                return;
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Sending to proc " + this.mName + " new config " + config);
            }
            config.seq = this.mAtm.increaseConfigurationSeqLocked();
            this.mAtm.getLifecycleManager().scheduleTransaction(this.mThread, ConfigurationChangeItem.obtain(config));
            setLastReportedConfiguration(config);
        } catch (Exception e) {
            Slog.e("ActivityTaskManager", "Failed to schedule configuration change", e);
        }
    }

    private void setLastReportedConfiguration(Configuration config) {
        this.mLastReportedConfiguration.setTo(config);
    }

    Configuration getLastReportedConfiguration() {
        return this.mLastReportedConfiguration;
    }

    public long getCpuTime() {
        WindowProcessListener windowProcessListener = this.mListener;
        if (windowProcessListener != null) {
            return windowProcessListener.getCpuTime();
        }
        return 0L;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addRecentTask(TaskRecord task) {
        this.mRecentTasks.add(task);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeRecentTask(TaskRecord task) {
        this.mRecentTasks.remove(task);
    }

    public boolean hasRecentTasks() {
        boolean z;
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            z = !this.mRecentTasks.isEmpty();
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearRecentTasks() {
        for (int i = this.mRecentTasks.size() - 1; i >= 0; i--) {
            this.mRecentTasks.get(i).clearRootProcess();
        }
        this.mRecentTasks.clear();
    }

    public void appEarlyNotResponding(String annotation, Runnable killAppCallback) {
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mAtm.mController == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                try {
                    int res = this.mAtm.mController.appEarlyNotResponding(this.mName, this.mPid, annotation);
                    if (res < 0 && this.mPid != ActivityManagerService.MY_PID) {
                        killAppCallback.run();
                    }
                } catch (RemoteException e) {
                    this.mAtm.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean appNotResponding(String info, Runnable killAppCallback, Runnable serviceTimeoutCallback) {
        Runnable targetRunnable = null;
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mAtm.mController == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                try {
                    int res = this.mAtm.mController.appNotResponding(this.mName, this.mPid, info);
                    if (res != 0) {
                        if (res < 0) {
                            if (this.mPid != ActivityManagerService.MY_PID) {
                                targetRunnable = killAppCallback;
                            }
                        }
                        targetRunnable = serviceTimeoutCallback;
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (targetRunnable != null) {
                        targetRunnable.run();
                        return true;
                    }
                    return false;
                } catch (RemoteException e) {
                    this.mAtm.mController = null;
                    Watchdog.getInstance().setActivityController(null);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
            } catch (Throwable e2) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw e2;
            }
        }
    }

    public void onTopProcChanged() {
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            this.mAtm.mVrController.onTopProcChangedLocked(this);
        }
    }

    public boolean isHomeProcess() {
        boolean z;
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            z = this == this.mAtm.mHomeProcess;
        }
        return z;
    }

    public boolean isPreviousProcess() {
        boolean z;
        synchronized (this.mAtm.mGlobalLockWithoutBoost) {
            z = this == this.mAtm.mPreviousProcess;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRunningRecentsAnimation(boolean running) {
        if (this.mRunningRecentsAnimation == running) {
            return;
        }
        this.mRunningRecentsAnimation = running;
        updateRunningRemoteOrRecentsAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRunningRemoteAnimation(boolean running) {
        if (this.mRunningRemoteAnimation == running) {
            return;
        }
        this.mRunningRemoteAnimation = running;
        updateRunningRemoteOrRecentsAnimation();
    }

    private void updateRunningRemoteOrRecentsAnimation() {
        this.mAtm.mH.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$uwO6wQlqU3CG7OTdH7NBCKnHs64
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((WindowProcessListener) obj).setRunningRemoteAnimation(((Boolean) obj2).booleanValue());
            }
        }, this.mListener, Boolean.valueOf(this.mRunningRecentsAnimation || this.mRunningRemoteAnimation)));
    }

    public String toString() {
        Object obj = this.mOwner;
        if (obj != null) {
            return obj.toString();
        }
        return null;
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (this.mAtm.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mActivities.size() > 0) {
                    pw.print(prefix);
                    pw.println("Activities:");
                    for (int i = 0; i < this.mActivities.size(); i++) {
                        pw.print(prefix);
                        pw.print("  - ");
                        pw.println(this.mActivities.get(i));
                    }
                }
                if (this.mRecentTasks.size() > 0) {
                    pw.println(prefix + "Recent Tasks:");
                    for (int i2 = 0; i2 < this.mRecentTasks.size(); i2++) {
                        pw.println(prefix + "  - " + this.mRecentTasks.get(i2));
                    }
                }
                int i3 = this.mVrThreadTid;
                if (i3 != 0) {
                    pw.print(prefix);
                    pw.print("mVrThreadTid=");
                    pw.println(this.mVrThreadTid);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        pw.println(prefix + " Configuration=" + getConfiguration());
        pw.println(prefix + " OverrideConfiguration=" + getRequestedOverrideConfiguration());
        pw.println(prefix + " mLastReportedConfiguration=" + this.mLastReportedConfiguration);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        WindowProcessListener windowProcessListener = this.mListener;
        if (windowProcessListener != null) {
            windowProcessListener.writeToProto(proto, fieldId);
        }
    }
}
