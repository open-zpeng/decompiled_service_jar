package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.pm.DumpState;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.wm.RecentTasks;
import com.google.android.collect.Sets;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class RecentTasks {
    private static final int DEFAULT_INITIAL_CAPACITY = 5;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_RECENTS = "ActivityTaskManager";
    private static final String TAG_TASKS = "ActivityTaskManager";
    private long mActiveTasksSessionDurationMs;
    private final ArrayList<Callbacks> mCallbacks;
    private boolean mFreezeTaskListReordering;
    private long mFreezeTaskListTimeoutMs;
    private int mGlobalMaxNumTasks;
    private boolean mHasVisibleRecentTasks;
    private final WindowManagerPolicyConstants.PointerEventListener mListener;
    private int mMaxNumVisibleTasks;
    private int mMinNumVisibleTasks;
    private final SparseArray<SparseBooleanArray> mPersistedTaskIds;
    private ComponentName mRecentsComponent;
    private int mRecentsUid;
    private final Runnable mResetFreezeTaskListOnTimeoutRunnable;
    private final ActivityTaskManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private final TaskPersister mTaskPersister;
    private final ArrayList<TaskRecord> mTasks;
    private final HashMap<ComponentName, ActivityInfo> mTmpAvailActCache;
    private final HashMap<String, ApplicationInfo> mTmpAvailAppCache;
    private final SparseBooleanArray mTmpQuietProfileUserIds;
    private final ArrayList<TaskRecord> mTmpRecents;
    private final SparseBooleanArray mUsersWithRecentsLoaded;
    private static final long FREEZE_TASK_LIST_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
    private static final Comparator<TaskRecord> TASK_ID_COMPARATOR = new Comparator() { // from class: com.android.server.wm.-$$Lambda$RecentTasks$KPkDUQ9KJ-vmXlmV8HHAucQJJdQ
        @Override // java.util.Comparator
        public final int compare(Object obj, Object obj2) {
            return RecentTasks.lambda$static$0((TaskRecord) obj, (TaskRecord) obj2);
        }
    };
    private static final ActivityInfo NO_ACTIVITY_INFO_TOKEN = new ActivityInfo();
    private static final ApplicationInfo NO_APPLICATION_INFO_TOKEN = new ApplicationInfo();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public interface Callbacks {
        void onRecentTaskAdded(TaskRecord taskRecord);

        void onRecentTaskRemoved(TaskRecord taskRecord, boolean z, boolean z2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$static$0(TaskRecord lhs, TaskRecord rhs) {
        return rhs.taskId - lhs.taskId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.wm.RecentTasks$1  reason: invalid class name */
    /* loaded from: classes2.dex */
    public class AnonymousClass1 implements WindowManagerPolicyConstants.PointerEventListener {
        AnonymousClass1() {
        }

        public void onPointerEvent(MotionEvent ev) {
            if (!RecentTasks.this.mFreezeTaskListReordering || ev.getAction() != 0) {
                return;
            }
            final int displayId = ev.getDisplayId();
            final int x = (int) ev.getX();
            final int y = (int) ev.getY();
            RecentTasks.this.mService.mH.post(PooledLambda.obtainRunnable(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RecentTasks$1$yqVuu6fkQgjlTTs6kgJbxqq3Hng
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    RecentTasks.AnonymousClass1.this.lambda$onPointerEvent$0$RecentTasks$1(displayId, x, y, obj);
                }
            }, (Object) null).recycleOnUse());
        }

        public /* synthetic */ void lambda$onPointerEvent$0$RecentTasks$1(int displayId, int x, int y, Object nonArg) {
            synchronized (RecentTasks.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    RootActivityContainer rac = RecentTasks.this.mService.mRootActivityContainer;
                    DisplayContent dc = rac.getActivityDisplay(displayId).mDisplayContent;
                    if (dc.pointWithinAppWindow(x, y)) {
                        ActivityStack stack = RecentTasks.this.mService.getTopDisplayFocusedStack();
                        TaskRecord topTask = stack != null ? stack.topTask() : null;
                        RecentTasks.this.resetFreezeTaskListReordering(topTask);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }
    }

    @VisibleForTesting
    RecentTasks(ActivityTaskManagerService service, TaskPersister taskPersister) {
        this.mRecentsUid = -1;
        this.mRecentsComponent = null;
        this.mUsersWithRecentsLoaded = new SparseBooleanArray(5);
        this.mPersistedTaskIds = new SparseArray<>(5);
        this.mTasks = new ArrayList<>();
        this.mCallbacks = new ArrayList<>();
        this.mFreezeTaskListTimeoutMs = FREEZE_TASK_LIST_TIMEOUT_MS;
        this.mTmpRecents = new ArrayList<>();
        this.mTmpAvailActCache = new HashMap<>();
        this.mTmpAvailAppCache = new HashMap<>();
        this.mTmpQuietProfileUserIds = new SparseBooleanArray();
        this.mListener = new AnonymousClass1();
        this.mResetFreezeTaskListOnTimeoutRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$Z9QEXZevRsInPMEXX0zFWg8YGMQ
            @Override // java.lang.Runnable
            public final void run() {
                RecentTasks.this.resetFreezeTaskListReorderingOnTimeout();
            }
        };
        this.mService = service;
        this.mSupervisor = this.mService.mStackSupervisor;
        this.mTaskPersister = taskPersister;
        this.mGlobalMaxNumTasks = ActivityTaskManager.getMaxRecentTasksStatic();
        this.mHasVisibleRecentTasks = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecentTasks(ActivityTaskManagerService service, ActivityStackSupervisor stackSupervisor) {
        this.mRecentsUid = -1;
        this.mRecentsComponent = null;
        this.mUsersWithRecentsLoaded = new SparseBooleanArray(5);
        this.mPersistedTaskIds = new SparseArray<>(5);
        this.mTasks = new ArrayList<>();
        this.mCallbacks = new ArrayList<>();
        this.mFreezeTaskListTimeoutMs = FREEZE_TASK_LIST_TIMEOUT_MS;
        this.mTmpRecents = new ArrayList<>();
        this.mTmpAvailActCache = new HashMap<>();
        this.mTmpAvailAppCache = new HashMap<>();
        this.mTmpQuietProfileUserIds = new SparseBooleanArray();
        this.mListener = new AnonymousClass1();
        this.mResetFreezeTaskListOnTimeoutRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$Z9QEXZevRsInPMEXX0zFWg8YGMQ
            @Override // java.lang.Runnable
            public final void run() {
                RecentTasks.this.resetFreezeTaskListReorderingOnTimeout();
            }
        };
        File systemDir = Environment.getDataSystemDirectory();
        Resources res = service.mContext.getResources();
        this.mService = service;
        this.mSupervisor = this.mService.mStackSupervisor;
        this.mTaskPersister = new TaskPersister(systemDir, stackSupervisor, service, this, stackSupervisor.mPersisterQueue);
        this.mGlobalMaxNumTasks = ActivityTaskManager.getMaxRecentTasksStatic();
        this.mHasVisibleRecentTasks = res.getBoolean(17891468);
        loadParametersFromResources(res);
    }

    @VisibleForTesting
    void setParameters(int minNumVisibleTasks, int maxNumVisibleTasks, long activeSessionDurationMs) {
        this.mMinNumVisibleTasks = minNumVisibleTasks;
        this.mMaxNumVisibleTasks = maxNumVisibleTasks;
        this.mActiveTasksSessionDurationMs = activeSessionDurationMs;
    }

    @VisibleForTesting
    void setGlobalMaxNumTasks(int globalMaxNumTasks) {
        this.mGlobalMaxNumTasks = globalMaxNumTasks;
    }

    @VisibleForTesting
    void setFreezeTaskListTimeout(long timeoutMs) {
        this.mFreezeTaskListTimeoutMs = timeoutMs;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowManagerPolicyConstants.PointerEventListener getInputListener() {
        return this.mListener;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFreezeTaskListReordering() {
        this.mFreezeTaskListReordering = true;
        this.mService.mH.removeCallbacks(this.mResetFreezeTaskListOnTimeoutRunnable);
        this.mService.mH.postDelayed(this.mResetFreezeTaskListOnTimeoutRunnable, this.mFreezeTaskListTimeoutMs);
    }

    void resetFreezeTaskListReordering(TaskRecord topTask) {
        if (!this.mFreezeTaskListReordering) {
            return;
        }
        this.mFreezeTaskListReordering = false;
        this.mService.mH.removeCallbacks(this.mResetFreezeTaskListOnTimeoutRunnable);
        if (topTask != null) {
            this.mTasks.remove(topTask);
            this.mTasks.add(0, topTask);
        }
        trimInactiveRecentTasks();
        this.mService.getTaskChangeNotificationController().notifyTaskStackChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public void resetFreezeTaskListReorderingOnTimeout() {
        TaskRecord topTask;
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack focusedStack = this.mService.getTopDisplayFocusedStack();
                if (focusedStack != null) {
                    topTask = focusedStack.topTask();
                } else {
                    topTask = null;
                }
                resetFreezeTaskListReordering(topTask);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public boolean isFreezeTaskListReorderingSet() {
        return this.mFreezeTaskListReordering;
    }

    @VisibleForTesting
    void loadParametersFromResources(Resources res) {
        long j;
        if (ActivityManager.isLowRamDeviceStatic()) {
            this.mMinNumVisibleTasks = res.getInteger(17694842);
            this.mMaxNumVisibleTasks = res.getInteger(17694833);
        } else if (SystemProperties.getBoolean("ro.recents.grid", false)) {
            this.mMinNumVisibleTasks = res.getInteger(17694841);
            this.mMaxNumVisibleTasks = res.getInteger(17694832);
        } else {
            this.mMinNumVisibleTasks = res.getInteger(17694840);
            this.mMaxNumVisibleTasks = res.getInteger(17694831);
        }
        int sessionDurationHrs = res.getInteger(17694729);
        if (sessionDurationHrs > 0) {
            j = TimeUnit.HOURS.toMillis(sessionDurationHrs);
        } else {
            j = -1;
        }
        this.mActiveTasksSessionDurationMs = j;
    }

    void loadRecentsComponent(Resources res) {
        ComponentName cn;
        String rawRecentsComponent = res.getString(17039774);
        if (!TextUtils.isEmpty(rawRecentsComponent) && (cn = ComponentName.unflattenFromString(rawRecentsComponent)) != null) {
            try {
                ApplicationInfo appInfo = AppGlobals.getPackageManager().getApplicationInfo(cn.getPackageName(), 0, this.mService.mContext.getUserId());
                if (appInfo != null) {
                    this.mRecentsUid = appInfo.uid;
                    this.mRecentsComponent = cn;
                }
            } catch (RemoteException e) {
                Slog.w("ActivityTaskManager", "Could not load application info for recents component: " + cn);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCallerRecents(int callingUid) {
        return UserHandle.isSameApp(callingUid, this.mRecentsUid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRecentsComponent(ComponentName cn, int uid) {
        return cn.equals(this.mRecentsComponent) && UserHandle.isSameApp(uid, this.mRecentsUid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRecentsComponentHomeActivity(int userId) {
        ComponentName defaultHomeActivity = this.mService.getPackageManagerInternalLocked().getDefaultHomeActivity(userId);
        return (defaultHomeActivity == null || this.mRecentsComponent == null || !defaultHomeActivity.getPackageName().equals(this.mRecentsComponent.getPackageName())) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ComponentName getRecentsComponent() {
        return this.mRecentsComponent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRecentsComponentUid() {
        return this.mRecentsUid;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerCallback(Callbacks callback) {
        this.mCallbacks.add(callback);
    }

    void unregisterCallback(Callbacks callback) {
        this.mCallbacks.remove(callback);
    }

    private void notifyTaskAdded(TaskRecord task) {
        for (int i = 0; i < this.mCallbacks.size(); i++) {
            this.mCallbacks.get(i).onRecentTaskAdded(task);
        }
    }

    private void notifyTaskRemoved(TaskRecord task, boolean wasTrimmed, boolean killProcess) {
        for (int i = 0; i < this.mCallbacks.size(); i++) {
            this.mCallbacks.get(i).onRecentTaskRemoved(task, wasTrimmed, killProcess);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void loadUserRecentsLocked(int userId) {
        if (this.mUsersWithRecentsLoaded.get(userId)) {
            return;
        }
        loadPersistedTaskIdsForUserLocked(userId);
        SparseBooleanArray preaddedTasks = new SparseBooleanArray();
        Iterator<TaskRecord> it = this.mTasks.iterator();
        while (it.hasNext()) {
            TaskRecord task = it.next();
            if (task.userId == userId && shouldPersistTaskLocked(task)) {
                preaddedTasks.put(task.taskId, true);
            }
        }
        Slog.i("ActivityTaskManager", "Loading recents for user " + userId + " into memory.");
        List<TaskRecord> tasks = this.mTaskPersister.restoreTasksForUserLocked(userId, preaddedTasks);
        this.mTasks.addAll(tasks);
        cleanupLocked(userId);
        this.mUsersWithRecentsLoaded.put(userId, true);
        if (preaddedTasks.size() > 0) {
            syncPersistentTaskIdsLocked();
        }
    }

    private void loadPersistedTaskIdsForUserLocked(int userId) {
        if (this.mPersistedTaskIds.get(userId) == null) {
            this.mPersistedTaskIds.put(userId, this.mTaskPersister.loadPersistedTaskIdsForUser(userId));
            Slog.i("ActivityTaskManager", "Loaded persisted task ids for user " + userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean containsTaskId(int taskId, int userId) {
        loadPersistedTaskIdsForUserLocked(userId);
        return this.mPersistedTaskIds.get(userId).get(taskId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SparseBooleanArray getTaskIdsForUser(int userId) {
        loadPersistedTaskIdsForUserLocked(userId);
        return this.mPersistedTaskIds.get(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskPersisterLocked(TaskRecord task, boolean flush) {
        ActivityStack stack = task != null ? task.getStack() : null;
        if (stack != null && stack.isHomeOrRecentsStack()) {
            return;
        }
        syncPersistentTaskIdsLocked();
        this.mTaskPersister.wakeup(task, flush);
    }

    private void syncPersistentTaskIdsLocked() {
        for (int i = this.mPersistedTaskIds.size() - 1; i >= 0; i--) {
            int userId = this.mPersistedTaskIds.keyAt(i);
            if (this.mUsersWithRecentsLoaded.get(userId)) {
                this.mPersistedTaskIds.valueAt(i).clear();
            }
        }
        for (int i2 = this.mTasks.size() - 1; i2 >= 0; i2--) {
            TaskRecord task = this.mTasks.get(i2);
            if (shouldPersistTaskLocked(task)) {
                if (this.mPersistedTaskIds.get(task.userId) == null) {
                    Slog.wtf("ActivityTaskManager", "No task ids found for userId " + task.userId + ". task=" + task + " mPersistedTaskIds=" + this.mPersistedTaskIds);
                    this.mPersistedTaskIds.put(task.userId, new SparseBooleanArray());
                }
                this.mPersistedTaskIds.get(task.userId).put(task.taskId, true);
            }
        }
    }

    private static boolean shouldPersistTaskLocked(TaskRecord task) {
        ActivityStack stack = task.getStack();
        return task.isPersistable && (stack == null || !stack.isHomeOrRecentsStack());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSystemReadyLocked() {
        loadRecentsComponent(this.mService.mContext.getResources());
        this.mTasks.clear();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Bitmap getTaskDescriptionIcon(String path) {
        return this.mTaskPersister.getTaskDescriptionIcon(path);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveImage(Bitmap image, String path) {
        this.mTaskPersister.saveImage(image, path);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void flush() {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                syncPersistentTaskIdsLocked();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        this.mTaskPersister.flush();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] usersWithRecentsLoadedLocked() {
        int[] usersWithRecentsLoaded = new int[this.mUsersWithRecentsLoaded.size()];
        int len = 0;
        for (int i = 0; i < usersWithRecentsLoaded.length; i++) {
            int userId = this.mUsersWithRecentsLoaded.keyAt(i);
            if (this.mUsersWithRecentsLoaded.valueAt(i)) {
                usersWithRecentsLoaded[len] = userId;
                len++;
            }
        }
        int i2 = usersWithRecentsLoaded.length;
        if (len < i2) {
            return Arrays.copyOf(usersWithRecentsLoaded, len);
        }
        return usersWithRecentsLoaded;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unloadUserDataFromMemoryLocked(int userId) {
        if (this.mUsersWithRecentsLoaded.get(userId)) {
            Slog.i("ActivityTaskManager", "Unloading recents for user " + userId + " from memory.");
            this.mUsersWithRecentsLoaded.delete(userId);
            removeTasksForUserLocked(userId);
        }
        this.mPersistedTaskIds.delete(userId);
        this.mTaskPersister.unloadUserDataFromMemory(userId);
    }

    private void removeTasksForUserLocked(int userId) {
        if (userId <= 0) {
            Slog.i("ActivityTaskManager", "Can't remove recent task on user " + userId);
            return;
        }
        for (int i = this.mTasks.size() - 1; i >= 0; i--) {
            TaskRecord tr = this.mTasks.get(i);
            if (tr.userId == userId) {
                if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                    Slog.i("ActivityTaskManager", "remove RecentTask " + tr + " when finishing user" + userId);
                }
                remove(tr);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPackagesSuspendedChanged(String[] packages, boolean suspended, int userId) {
        Set<String> packageNames = Sets.newHashSet(packages);
        for (int i = this.mTasks.size() - 1; i >= 0; i--) {
            TaskRecord tr = this.mTasks.get(i);
            if (tr.realActivity != null && packageNames.contains(tr.realActivity.getPackageName()) && tr.userId == userId && tr.realActivitySuspended != suspended) {
                tr.realActivitySuspended = suspended;
                if (suspended) {
                    this.mSupervisor.removeTaskByIdLocked(tr.taskId, false, true, "suspended-package");
                }
                notifyTaskPersisterLocked(tr, false);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onLockTaskModeStateChanged(int lockTaskModeState, int userId) {
        if (lockTaskModeState == 1) {
            for (int i = this.mTasks.size() - 1; i >= 0; i--) {
                TaskRecord tr = this.mTasks.get(i);
                if (tr.userId == userId && !this.mService.getLockTaskController().isTaskWhitelisted(tr)) {
                    remove(tr);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeTasksByPackageName(String packageName, int userId) {
        for (int i = this.mTasks.size() - 1; i >= 0; i--) {
            TaskRecord tr = this.mTasks.get(i);
            String taskPackageName = tr.getBaseIntent().getComponent().getPackageName();
            if (tr.userId == userId && taskPackageName.equals(packageName)) {
                this.mSupervisor.removeTaskByIdLocked(tr.taskId, true, true, "remove-package-task");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAllVisibleTasks(int userId) {
        Set<Integer> profileIds = getProfileIds(userId);
        for (int i = this.mTasks.size() - 1; i >= 0; i--) {
            TaskRecord tr = this.mTasks.get(i);
            if (profileIds.contains(Integer.valueOf(tr.userId)) && isVisibleRecentTask(tr)) {
                this.mTasks.remove(i);
                notifyTaskRemoved(tr, true, true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cleanupDisabledPackageTasksLocked(String packageName, Set<String> filterByClasses, int userId) {
        for (int i = this.mTasks.size() - 1; i >= 0; i--) {
            TaskRecord tr = this.mTasks.get(i);
            if (userId == -1 || tr.userId == userId) {
                ComponentName cn = tr.intent != null ? tr.intent.getComponent() : null;
                boolean sameComponent = cn != null && cn.getPackageName().equals(packageName) && (filterByClasses == null || filterByClasses.contains(cn.getClassName()));
                if (sameComponent) {
                    this.mSupervisor.removeTaskByIdLocked(tr.taskId, false, true, "disabled-package");
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cleanupLocked(int userId) {
        int recentsCount = this.mTasks.size();
        if (recentsCount == 0) {
            return;
        }
        this.mTmpAvailActCache.clear();
        this.mTmpAvailAppCache.clear();
        IPackageManager pm = AppGlobals.getPackageManager();
        for (int i = recentsCount - 1; i >= 0; i--) {
            TaskRecord task = this.mTasks.get(i);
            if (userId == -1 || task.userId == userId) {
                if (task.autoRemoveRecents && task.getTopActivity() == null) {
                    remove(task);
                    Slog.w("ActivityTaskManager", "Removing auto-remove without activity: " + task);
                } else if (task.realActivity != null) {
                    ActivityInfo ai = this.mTmpAvailActCache.get(task.realActivity);
                    if (ai == null) {
                        try {
                            ai = pm.getActivityInfo(task.realActivity, 268436480, userId);
                            if (ai == null) {
                                ai = NO_ACTIVITY_INFO_TOKEN;
                            }
                            this.mTmpAvailActCache.put(task.realActivity, ai);
                        } catch (RemoteException e) {
                        }
                    }
                    if (ai == NO_ACTIVITY_INFO_TOKEN) {
                        ApplicationInfo app = this.mTmpAvailAppCache.get(task.realActivity.getPackageName());
                        if (app == null) {
                            try {
                                app = pm.getApplicationInfo(task.realActivity.getPackageName(), 8192, userId);
                                if (app == null) {
                                    app = NO_APPLICATION_INFO_TOKEN;
                                }
                                this.mTmpAvailAppCache.put(task.realActivity.getPackageName(), app);
                            } catch (RemoteException e2) {
                            }
                        }
                        if (app == NO_APPLICATION_INFO_TOKEN || (8388608 & app.flags) == 0) {
                            remove(task);
                            Slog.w("ActivityTaskManager", "Removing no longer valid recent: " + task);
                        } else {
                            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS && task.isAvailable) {
                                Slog.d("ActivityTaskManager", "Making recent unavailable: " + task);
                            }
                            task.isAvailable = false;
                        }
                    } else if (!ai.enabled || !ai.applicationInfo.enabled || (ai.applicationInfo.flags & DumpState.DUMP_VOLUMES) == 0) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS && task.isAvailable) {
                            Slog.d("ActivityTaskManager", "Making recent unavailable: " + task + " (enabled=" + ai.enabled + SliceClientPermissions.SliceAuthority.DELIMITER + ai.applicationInfo.enabled + " flags=" + Integer.toHexString(ai.applicationInfo.flags) + ")");
                        }
                        task.isAvailable = false;
                    } else {
                        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS && !task.isAvailable) {
                            Slog.d("ActivityTaskManager", "Making recent available: " + task);
                        }
                        task.isAvailable = true;
                    }
                }
            }
        }
        int i2 = 0;
        int recentsCount2 = this.mTasks.size();
        while (i2 < recentsCount2) {
            i2 = processNextAffiliateChainLocked(i2);
        }
    }

    private boolean canAddTaskWithoutTrim(TaskRecord task) {
        return findRemoveIndexForAddTask(task) == -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<IBinder> getAppTasksList(int callingUid, String callingPackage) {
        Intent intent;
        ArrayList<IBinder> list = new ArrayList<>();
        int size = this.mTasks.size();
        for (int i = 0; i < size; i++) {
            TaskRecord tr = this.mTasks.get(i);
            if (tr.effectiveUid == callingUid && (intent = tr.getBaseIntent()) != null && callingPackage.equals(intent.getComponent().getPackageName())) {
                AppTaskImpl taskImpl = new AppTaskImpl(this.mService, tr.taskId, callingUid);
                list.add(taskImpl.asBinder());
            }
        }
        return list;
    }

    @VisibleForTesting
    Set<Integer> getProfileIds(int userId) {
        Set<Integer> userIds = new ArraySet<>();
        List<UserInfo> profiles = this.mService.getUserManager().getProfiles(userId, false);
        for (int i = profiles.size() - 1; i >= 0; i--) {
            userIds.add(Integer.valueOf(profiles.get(i).id));
        }
        return userIds;
    }

    @VisibleForTesting
    UserInfo getUserInfo(int userId) {
        return this.mService.getUserManager().getUserInfo(userId);
    }

    @VisibleForTesting
    int[] getCurrentProfileIds() {
        return this.mService.mAmInternal.getCurrentProfileIds();
    }

    @VisibleForTesting
    boolean isUserRunning(int userId, int flags) {
        return this.mService.mAmInternal.isUserRunning(userId, flags);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags, boolean getTasksAllowed, boolean getDetailedTasks, int userId, int callingUid) {
        return new ParceledListSlice<>(getRecentTasksImpl(maxNum, flags, getTasksAllowed, getDetailedTasks, userId, callingUid));
    }

    private ArrayList<ActivityManager.RecentTaskInfo> getRecentTasksImpl(int maxNum, int flags, boolean getTasksAllowed, boolean getDetailedTasks, int userId, int callingUid) {
        boolean withExcluded = (flags & 1) != 0;
        if (!isUserRunning(userId, 4)) {
            Slog.i("ActivityTaskManager", "user " + userId + " is still locked. Cannot load recents");
            return new ArrayList<>();
        }
        loadUserRecentsLocked(userId);
        Set<Integer> includedUsers = getProfileIds(userId);
        includedUsers.add(Integer.valueOf(userId));
        ArrayList<ActivityManager.RecentTaskInfo> res = new ArrayList<>();
        int size = this.mTasks.size();
        int numVisibleTasks = 0;
        for (int i = 0; i < size; i++) {
            TaskRecord tr = this.mTasks.get(i);
            if (isVisibleRecentTask(tr)) {
                numVisibleTasks++;
                if (isInVisibleRange(tr, i, numVisibleTasks, withExcluded) && res.size() < maxNum) {
                    if (!includedUsers.contains(Integer.valueOf(tr.userId))) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                            Slog.d("ActivityTaskManager", "Skipping, not user: " + tr);
                        }
                    } else if (tr.realActivitySuspended) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                            Slog.d("ActivityTaskManager", "Skipping, activity suspended: " + tr);
                        }
                    } else {
                        if (!getTasksAllowed && !tr.isActivityTypeHome() && tr.effectiveUid != callingUid) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                                Slog.d("ActivityTaskManager", "Skipping, not allowed: " + tr);
                            }
                        }
                        if (tr.autoRemoveRecents && tr.getTopActivity() == null) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                                Slog.d("ActivityTaskManager", "Skipping, auto-remove without activity: " + tr);
                            }
                        } else if ((flags & 2) != 0 && !tr.isAvailable) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                                Slog.d("ActivityTaskManager", "Skipping, unavail real act: " + tr);
                            }
                        } else if (!tr.mUserSetupComplete) {
                            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                                Slog.d("ActivityTaskManager", "Skipping, user setup not complete: " + tr);
                            }
                        } else {
                            ActivityManager.RecentTaskInfo rti = createRecentTaskInfo(tr);
                            if (!getDetailedTasks) {
                                rti.baseIntent.replaceExtras((Bundle) null);
                            }
                            res.add(rti);
                        }
                    }
                }
            }
        }
        return res;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getPersistableTaskIds(ArraySet<Integer> persistentTaskIds) {
        int size = this.mTasks.size();
        for (int i = 0; i < size; i++) {
            TaskRecord task = this.mTasks.get(i);
            ActivityStack stack = task.getStack();
            if ((task.isPersistable || task.inRecents) && (stack == null || !stack.isHomeOrRecentsStack())) {
                persistentTaskIds.add(Integer.valueOf(task.taskId));
            }
        }
    }

    @VisibleForTesting
    ArrayList<TaskRecord> getRawTasks() {
        return this.mTasks;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SparseBooleanArray getRecentTaskIds() {
        SparseBooleanArray res = new SparseBooleanArray();
        int size = this.mTasks.size();
        int numVisibleTasks = 0;
        for (int i = 0; i < size; i++) {
            TaskRecord tr = this.mTasks.get(i);
            if (isVisibleRecentTask(tr)) {
                numVisibleTasks++;
                if (isInVisibleRange(tr, i, numVisibleTasks, false)) {
                    res.put(tr.taskId, true);
                }
            }
        }
        return res;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskRecord getTask(int id) {
        int recentsCount = this.mTasks.size();
        for (int i = 0; i < recentsCount; i++) {
            TaskRecord tr = this.mTasks.get(i);
            if (tr.taskId == id) {
                return tr;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void add(TaskRecord task) {
        int taskIndex;
        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
            Slog.d("ActivityTaskManager", "add: task=" + task);
        }
        boolean isAffiliated = (task.mAffiliatedTaskId == task.taskId && task.mNextAffiliateTaskId == -1 && task.mPrevAffiliateTaskId == -1) ? false : true;
        int recentsCount = this.mTasks.size();
        if (task.voiceSession != null) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityTaskManager", "addRecent: not adding voice interaction " + task);
            }
        } else if (!isAffiliated && recentsCount > 0 && this.mTasks.get(0) == task) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityTaskManager", "addRecent: already at top: " + task);
            }
        } else if (isAffiliated && recentsCount > 0 && task.inRecents && task.mAffiliatedTaskId == this.mTasks.get(0).mAffiliatedTaskId) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityTaskManager", "addRecent: affiliated " + this.mTasks.get(0) + " at top when adding " + task);
            }
        } else {
            boolean needAffiliationFix = false;
            if (task.inRecents) {
                int taskIndex2 = this.mTasks.indexOf(task);
                if (taskIndex2 >= 0) {
                    if (!isAffiliated) {
                        if (!this.mFreezeTaskListReordering) {
                            this.mTasks.remove(taskIndex2);
                            this.mTasks.add(0, task);
                            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                                Slog.d("ActivityTaskManager", "addRecent: moving to top " + task + " from " + taskIndex2);
                            }
                        }
                        notifyTaskPersisterLocked(task, false);
                        return;
                    }
                } else {
                    Slog.wtf("ActivityTaskManager", "Task with inRecent not in recents: " + task);
                    needAffiliationFix = true;
                }
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityTaskManager", "addRecent: trimming tasks for " + task);
            }
            removeForAddTask(task);
            task.inRecents = true;
            if (!isAffiliated || needAffiliationFix) {
                this.mTasks.add(0, task);
                notifyTaskAdded(task);
                if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                    Slog.d("ActivityTaskManager", "addRecent: adding " + task);
                }
            } else if (isAffiliated) {
                TaskRecord other = task.mNextAffiliate;
                if (other == null) {
                    other = task.mPrevAffiliate;
                }
                if (other != null) {
                    int otherIndex = this.mTasks.indexOf(other);
                    if (otherIndex >= 0) {
                        if (other == task.mNextAffiliate) {
                            taskIndex = otherIndex + 1;
                        } else {
                            taskIndex = otherIndex;
                        }
                        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                            Slog.d("ActivityTaskManager", "addRecent: new affiliated task added at " + taskIndex + ": " + task);
                        }
                        this.mTasks.add(taskIndex, task);
                        notifyTaskAdded(task);
                        if (moveAffiliatedTasksToFront(task, taskIndex)) {
                            return;
                        }
                        needAffiliationFix = true;
                    } else {
                        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                            Slog.d("ActivityTaskManager", "addRecent: couldn't find other affiliation " + other);
                        }
                        needAffiliationFix = true;
                    }
                } else {
                    if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                        Slog.d("ActivityTaskManager", "addRecent: adding affiliated task without next/prev:" + task);
                    }
                    needAffiliationFix = true;
                }
            }
            if (needAffiliationFix) {
                if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS) {
                    Slog.d("ActivityTaskManager", "addRecent: regrouping affiliations");
                }
                cleanupLocked(task.userId);
            }
            trimInactiveRecentTasks();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean addToBottom(TaskRecord task) {
        if (!canAddTaskWithoutTrim(task)) {
            return false;
        }
        add(task);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void remove(TaskRecord task) {
        this.mTasks.remove(task);
        notifyTaskRemoved(task, false, false);
    }

    private void trimInactiveRecentTasks() {
        if (this.mFreezeTaskListReordering) {
            return;
        }
        int recentsCount = this.mTasks.size();
        while (recentsCount > this.mGlobalMaxNumTasks) {
            TaskRecord tr = this.mTasks.remove(recentsCount - 1);
            notifyTaskRemoved(tr, true, false);
            recentsCount--;
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityTaskManager", "Trimming over max-recents task=" + tr + " max=" + this.mGlobalMaxNumTasks);
            }
        }
        int[] profileUserIds = getCurrentProfileIds();
        this.mTmpQuietProfileUserIds.clear();
        for (int userId : profileUserIds) {
            UserInfo userInfo = getUserInfo(userId);
            if (userInfo != null && userInfo.isManagedProfile() && userInfo.isQuietModeEnabled()) {
                this.mTmpQuietProfileUserIds.put(userId, true);
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityTaskManager", "User: " + userInfo + " quiet=" + this.mTmpQuietProfileUserIds.get(userId));
            }
        }
        int numVisibleTasks = 0;
        int i = 0;
        while (i < this.mTasks.size()) {
            TaskRecord task = this.mTasks.get(i);
            if (isActiveRecentTask(task, this.mTmpQuietProfileUserIds)) {
                if (!this.mHasVisibleRecentTasks) {
                    i++;
                } else if (!isVisibleRecentTask(task)) {
                    i++;
                } else {
                    numVisibleTasks++;
                    if (isInVisibleRange(task, i, numVisibleTasks, false) || !isTrimmable(task)) {
                        i++;
                    } else if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                        Slog.d("ActivityTaskManager", "Trimming out-of-range visible task=" + task);
                    }
                }
            } else if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityTaskManager", "Trimming inactive task=" + task);
            }
            this.mTasks.remove(task);
            notifyTaskRemoved(task, true, false);
            notifyTaskPersisterLocked(task, false);
        }
    }

    private boolean isActiveRecentTask(TaskRecord task, SparseBooleanArray quietProfileUserIds) {
        TaskRecord affiliatedTask;
        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
            Slog.d("ActivityTaskManager", "isActiveRecentTask: task=" + task + " globalMax=" + this.mGlobalMaxNumTasks);
        }
        if (quietProfileUserIds.get(task.userId)) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityTaskManager", "\tisQuietProfileTask=true");
            }
            return false;
        } else if (task.mAffiliatedTaskId != -1 && task.mAffiliatedTaskId != task.taskId && (affiliatedTask = getTask(task.mAffiliatedTaskId)) != null && !isActiveRecentTask(affiliatedTask, quietProfileUserIds)) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityTaskManager", "\taffiliatedWithTask=" + affiliatedTask + " is not active");
            }
            return false;
        } else {
            return true;
        }
    }

    @VisibleForTesting
    boolean isVisibleRecentTask(TaskRecord task) {
        int windowingMode;
        ActivityDisplay display;
        if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
            Slog.d("ActivityTaskManager", "isVisibleRecentTask: task=" + task + " minVis=" + this.mMinNumVisibleTasks + " maxVis=" + this.mMaxNumVisibleTasks + " sessionDuration=" + this.mActiveTasksSessionDurationMs + " inactiveDuration=" + task.getInactiveDuration() + " activityType=" + task.getActivityType() + " windowingMode=" + task.getWindowingMode() + " intentFlags=" + task.getBaseIntent().getFlags());
        }
        int activityType = task.getActivityType();
        if (activityType == 2 || activityType == 3) {
            return false;
        }
        if ((activityType == 4 && (task.getBaseIntent().getFlags() & DumpState.DUMP_VOLUMES) == 8388608) || (windowingMode = task.getWindowingMode()) == 2) {
            return false;
        }
        if (windowingMode == 3) {
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityTaskManager", "\ttop=" + task.getStack().topTask());
            }
            ActivityStack stack = task.getStack();
            if (stack != null && stack.topTask() == task) {
                return false;
            }
        }
        ActivityStack stack2 = task.getStack();
        return (stack2 == null || (display = stack2.getDisplay()) == null || !display.isSingleTaskInstance()) && task != this.mService.getLockTaskController().getRootTask();
    }

    private boolean isInVisibleRange(TaskRecord task, int taskIndex, int numVisibleTasks, boolean skipExcludedCheck) {
        if (!skipExcludedCheck) {
            boolean isExcludeFromRecents = (task.getBaseIntent().getFlags() & DumpState.DUMP_VOLUMES) == 8388608;
            if (isExcludeFromRecents) {
                if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                    Slog.d("ActivityTaskManager", "\texcludeFromRecents=true");
                }
                return taskIndex == 0;
            }
        }
        int i = this.mMinNumVisibleTasks;
        if (i < 0 || numVisibleTasks > i) {
            int i2 = this.mMaxNumVisibleTasks;
            return i2 >= 0 ? numVisibleTasks <= i2 : this.mActiveTasksSessionDurationMs > 0 && task.getInactiveDuration() <= this.mActiveTasksSessionDurationMs;
        }
        return true;
    }

    protected boolean isTrimmable(TaskRecord task) {
        ActivityStack stack = task.getStack();
        if (stack == null) {
            return true;
        }
        if (stack.mDisplayId != 0) {
            return false;
        }
        ActivityDisplay display = stack.getDisplay();
        return display.getIndexOf(stack) < display.getIndexOf(display.getHomeStack());
    }

    private void removeForAddTask(TaskRecord task) {
        int removeIndex = findRemoveIndexForAddTask(task);
        if (removeIndex == -1) {
            return;
        }
        TaskRecord removedTask = this.mTasks.remove(removeIndex);
        if (removedTask != task) {
            notifyTaskRemoved(removedTask, false, false);
            if (ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityTaskManager", "Trimming task=" + removedTask + " for addition of task=" + task);
            }
        }
        notifyTaskPersisterLocked(removedTask, false);
    }

    private int findRemoveIndexForAddTask(TaskRecord task) {
        if (this.mFreezeTaskListReordering) {
            return -1;
        }
        int recentsCount = this.mTasks.size();
        Intent intent = task.intent;
        boolean z = true;
        boolean document = intent != null && intent.isDocument();
        int maxRecents = task.maxRecents - 1;
        int i = 0;
        while (i < recentsCount) {
            TaskRecord tr = this.mTasks.get(i);
            if (task != tr) {
                if (hasCompatibleActivityTypeAndWindowingMode(task, tr) && task.userId == tr.userId) {
                    Intent trIntent = tr.intent;
                    boolean sameAffinity = (task.affinity == null || !task.affinity.equals(tr.affinity)) ? false : z;
                    boolean sameIntent = (intent == null || !intent.filterEquals(trIntent)) ? false : z;
                    boolean multiTasksAllowed = false;
                    int flags = intent.getFlags();
                    if ((flags & 268959744) != 0 && (flags & 134217728) != 0) {
                        multiTasksAllowed = true;
                    }
                    boolean trIsDocument = (trIntent == null || !trIntent.isDocument()) ? false : z;
                    boolean bothDocuments = (document && trIsDocument) ? z : false;
                    if (sameAffinity || sameIntent || bothDocuments) {
                        if (bothDocuments) {
                            boolean sameActivity = (task.realActivity == null || tr.realActivity == null || !task.realActivity.equals(tr.realActivity)) ? false : true;
                            if (!sameActivity) {
                                continue;
                            } else if (maxRecents > 0) {
                                maxRecents--;
                                if (sameIntent && !multiTasksAllowed) {
                                }
                            }
                        } else if (!document && !trIsDocument) {
                        }
                    }
                }
                i++;
                z = true;
            }
            return i;
        }
        return -1;
    }

    private int processNextAffiliateChainLocked(int start) {
        TaskRecord startTask = this.mTasks.get(start);
        int affiliateId = startTask.mAffiliatedTaskId;
        if (startTask.taskId == affiliateId && startTask.mPrevAffiliate == null && startTask.mNextAffiliate == null) {
            startTask.inRecents = true;
            return start + 1;
        }
        this.mTmpRecents.clear();
        for (int i = this.mTasks.size() - 1; i >= start; i--) {
            TaskRecord task = this.mTasks.get(i);
            if (task.mAffiliatedTaskId == affiliateId) {
                this.mTasks.remove(i);
                this.mTmpRecents.add(task);
            }
        }
        Collections.sort(this.mTmpRecents, TASK_ID_COMPARATOR);
        TaskRecord first = this.mTmpRecents.get(0);
        first.inRecents = true;
        if (first.mNextAffiliate != null) {
            Slog.w("ActivityTaskManager", "Link error 1 first.next=" + first.mNextAffiliate);
            first.setNextAffiliate(null);
            notifyTaskPersisterLocked(first, false);
        }
        int tmpSize = this.mTmpRecents.size();
        for (int i2 = 0; i2 < tmpSize - 1; i2++) {
            TaskRecord next = this.mTmpRecents.get(i2);
            TaskRecord prev = this.mTmpRecents.get(i2 + 1);
            if (next.mPrevAffiliate != prev) {
                Slog.w("ActivityTaskManager", "Link error 2 next=" + next + " prev=" + next.mPrevAffiliate + " setting prev=" + prev);
                next.setPrevAffiliate(prev);
                notifyTaskPersisterLocked(next, false);
            }
            if (prev.mNextAffiliate != next) {
                Slog.w("ActivityTaskManager", "Link error 3 prev=" + prev + " next=" + prev.mNextAffiliate + " setting next=" + next);
                prev.setNextAffiliate(next);
                notifyTaskPersisterLocked(prev, false);
            }
            prev.inRecents = true;
        }
        TaskRecord last = this.mTmpRecents.get(tmpSize - 1);
        if (last.mPrevAffiliate != null) {
            Slog.w("ActivityTaskManager", "Link error 4 last.prev=" + last.mPrevAffiliate);
            last.setPrevAffiliate(null);
            notifyTaskPersisterLocked(last, false);
        }
        this.mTasks.addAll(start, this.mTmpRecents);
        this.mTmpRecents.clear();
        return start + tmpSize;
    }

    /* JADX WARN: Code restructure failed: missing block: B:51:0x0185, code lost:
        android.util.Slog.wtf("ActivityTaskManager", "Bad chain @" + r8 + ": middle task " + r14 + " @" + r8 + " has bad next affiliate " + r14.mNextAffiliate + " id " + r14.mNextAffiliateTaskId + ", expected " + r11);
        r6 = false;
     */
    /* JADX WARN: Removed duplicated region for block: B:40:0x00f7  */
    /* JADX WARN: Removed duplicated region for block: B:78:0x00b8 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean moveAffiliatedTasksToFront(com.android.server.wm.TaskRecord r18, int r19) {
        /*
            Method dump skipped, instructions count: 589
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.RecentTasks.moveAffiliatedTasksToFront(com.android.server.wm.TaskRecord, int):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, boolean dumpAll, String dumpPackage) {
        pw.println("ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)");
        pw.println("mRecentsUid=" + this.mRecentsUid);
        pw.println("mRecentsComponent=" + this.mRecentsComponent);
        pw.println("mFreezeTaskListReordering=" + this.mFreezeTaskListReordering);
        pw.println("mFreezeTaskListReorderingPendingTimeout=" + this.mService.mH.hasCallbacks(this.mResetFreezeTaskListOnTimeoutRunnable));
        if (this.mTasks.isEmpty()) {
            return;
        }
        boolean printedHeader = false;
        int size = this.mTasks.size();
        boolean printedAnything = false;
        for (int i = 0; i < size; i++) {
            TaskRecord tr = this.mTasks.get(i);
            if (dumpPackage == null || (tr.realActivity != null && dumpPackage.equals(tr.realActivity.getPackageName()))) {
                if (!printedHeader) {
                    pw.println("  Recent tasks:");
                    printedHeader = true;
                    printedAnything = true;
                }
                pw.print("  * Recent #");
                pw.print(i);
                pw.print(": ");
                pw.println(tr);
                if (dumpAll) {
                    tr.dump(pw, "    ");
                }
            }
        }
        if (this.mHasVisibleRecentTasks) {
            boolean printedHeader2 = false;
            ArrayList<ActivityManager.RecentTaskInfo> tasks = getRecentTasksImpl(Integer.MAX_VALUE, 0, true, false, this.mService.getCurrentUserId(), 1000);
            for (int i2 = 0; i2 < tasks.size(); i2++) {
                ActivityManager.RecentTaskInfo taskInfo = tasks.get(i2);
                if (!printedHeader2) {
                    if (printedAnything) {
                        pw.println();
                    }
                    pw.println("  Visible recent tasks (most recent first):");
                    printedHeader2 = true;
                    printedAnything = true;
                }
                pw.print("  * RecentTaskInfo #");
                pw.print(i2);
                pw.print(": ");
                taskInfo.dump(pw, "    ");
            }
        }
        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.RecentTaskInfo createRecentTaskInfo(TaskRecord tr) {
        ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
        tr.fillTaskInfo(rti);
        rti.id = rti.isRunning ? rti.taskId : -1;
        rti.persistentId = rti.taskId;
        return rti;
    }

    private boolean hasCompatibleActivityTypeAndWindowingMode(TaskRecord t1, TaskRecord t2) {
        int activityType = t1.getActivityType();
        int windowingMode = t1.getWindowingMode();
        boolean isUndefinedType = activityType == 0;
        boolean isUndefinedMode = windowingMode == 0;
        int otherActivityType = t2.getActivityType();
        int otherWindowingMode = t2.getWindowingMode();
        boolean isOtherUndefinedType = otherActivityType == 0;
        boolean isOtherUndefinedMode = otherWindowingMode == 0;
        boolean isCompatibleType = activityType == otherActivityType || isUndefinedType || isOtherUndefinedType;
        boolean isCompatibleMode = windowingMode == otherWindowingMode || isUndefinedMode || isOtherUndefinedMode;
        return isCompatibleType && isCompatibleMode;
    }
}
