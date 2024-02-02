package com.android.server.am;

import android.app.ActivityManager;
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
import android.graphics.Rect;
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
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.TaskRecord;
import com.android.server.pm.DumpState;
import com.android.server.slice.SliceClientPermissions;
import com.google.android.collect.Sets;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RecentTasks {
    private static final int DEFAULT_INITIAL_CAPACITY = 5;
    private static final boolean MOVE_AFFILIATED_TASKS_TO_FRONT = false;
    private static final String TAG = "ActivityManager";
    private static final String TAG_RECENTS = "ActivityManager";
    private static final String TAG_TASKS = "ActivityManager";
    private static final boolean TRIMMED = true;
    private long mActiveTasksSessionDurationMs;
    private final ArrayList<Callbacks> mCallbacks;
    private int mGlobalMaxNumTasks;
    private boolean mHasVisibleRecentTasks;
    private int mMaxNumVisibleTasks;
    private int mMinNumVisibleTasks;
    private final SparseArray<SparseBooleanArray> mPersistedTaskIds;
    private ComponentName mRecentsComponent;
    private int mRecentsUid;
    private final ActivityManagerService mService;
    private final TaskPersister mTaskPersister;
    private final ArrayList<TaskRecord> mTasks;
    private final HashMap<ComponentName, ActivityInfo> mTmpAvailActCache;
    private final HashMap<String, ApplicationInfo> mTmpAvailAppCache;
    private final SparseBooleanArray mTmpQuietProfileUserIds;
    private final ArrayList<TaskRecord> mTmpRecents;
    private final TaskRecord.TaskActivitiesReport mTmpReport;
    private final UserController mUserController;
    private final SparseBooleanArray mUsersWithRecentsLoaded;
    private static final Comparator<TaskRecord> TASK_ID_COMPARATOR = new Comparator() { // from class: com.android.server.am.-$$Lambda$RecentTasks$NgzE6eN0wIO1cgLW7RzciPDBTHk
        @Override // java.util.Comparator
        public final int compare(Object obj, Object obj2) {
            return RecentTasks.lambda$static$0((TaskRecord) obj, (TaskRecord) obj2);
        }
    };
    private static final ActivityInfo NO_ACTIVITY_INFO_TOKEN = new ActivityInfo();
    private static final ApplicationInfo NO_APPLICATION_INFO_TOKEN = new ApplicationInfo();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface Callbacks {
        void onRecentTaskAdded(TaskRecord taskRecord);

        void onRecentTaskRemoved(TaskRecord taskRecord, boolean z);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$static$0(TaskRecord lhs, TaskRecord rhs) {
        return rhs.taskId - lhs.taskId;
    }

    @VisibleForTesting
    RecentTasks(ActivityManagerService service, TaskPersister taskPersister, UserController userController) {
        this.mRecentsUid = -1;
        this.mRecentsComponent = null;
        this.mUsersWithRecentsLoaded = new SparseBooleanArray(5);
        this.mPersistedTaskIds = new SparseArray<>(5);
        this.mTasks = new ArrayList<>();
        this.mCallbacks = new ArrayList<>();
        this.mTmpRecents = new ArrayList<>();
        this.mTmpAvailActCache = new HashMap<>();
        this.mTmpAvailAppCache = new HashMap<>();
        this.mTmpQuietProfileUserIds = new SparseBooleanArray();
        this.mTmpReport = new TaskRecord.TaskActivitiesReport();
        this.mService = service;
        this.mUserController = userController;
        this.mTaskPersister = taskPersister;
        this.mGlobalMaxNumTasks = ActivityManager.getMaxRecentTasksStatic();
        this.mHasVisibleRecentTasks = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecentTasks(ActivityManagerService service, ActivityStackSupervisor stackSupervisor) {
        this.mRecentsUid = -1;
        this.mRecentsComponent = null;
        this.mUsersWithRecentsLoaded = new SparseBooleanArray(5);
        this.mPersistedTaskIds = new SparseArray<>(5);
        this.mTasks = new ArrayList<>();
        this.mCallbacks = new ArrayList<>();
        this.mTmpRecents = new ArrayList<>();
        this.mTmpAvailActCache = new HashMap<>();
        this.mTmpAvailAppCache = new HashMap<>();
        this.mTmpQuietProfileUserIds = new SparseBooleanArray();
        this.mTmpReport = new TaskRecord.TaskActivitiesReport();
        File systemDir = Environment.getDataSystemDirectory();
        Resources res = service.mContext.getResources();
        this.mService = service;
        this.mUserController = service.mUserController;
        this.mTaskPersister = new TaskPersister(systemDir, stackSupervisor, service, this);
        this.mGlobalMaxNumTasks = ActivityManager.getMaxRecentTasksStatic();
        this.mHasVisibleRecentTasks = res.getBoolean(17956986);
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
    void loadParametersFromResources(Resources res) {
        long j;
        if (ActivityManager.isLowRamDeviceStatic()) {
            this.mMinNumVisibleTasks = res.getInteger(17694818);
            this.mMaxNumVisibleTasks = res.getInteger(17694810);
        } else if (SystemProperties.getBoolean("ro.recents.grid", false)) {
            this.mMinNumVisibleTasks = res.getInteger(17694817);
            this.mMaxNumVisibleTasks = res.getInteger(17694809);
        } else {
            this.mMinNumVisibleTasks = res.getInteger(17694816);
            this.mMaxNumVisibleTasks = res.getInteger(17694808);
        }
        int sessionDurationHrs = res.getInteger(17694728);
        if (sessionDurationHrs > 0) {
            j = TimeUnit.HOURS.toMillis(sessionDurationHrs);
        } else {
            j = -1;
        }
        this.mActiveTasksSessionDurationMs = j;
    }

    void loadRecentsComponent(Resources res) {
        ComponentName cn;
        String rawRecentsComponent = res.getString(17039715);
        if (!TextUtils.isEmpty(rawRecentsComponent) && (cn = ComponentName.unflattenFromString(rawRecentsComponent)) != null) {
            try {
                ApplicationInfo appInfo = AppGlobals.getPackageManager().getApplicationInfo(cn.getPackageName(), 0, this.mService.mContext.getUserId());
                if (appInfo != null) {
                    this.mRecentsUid = appInfo.uid;
                    this.mRecentsComponent = cn;
                }
            } catch (RemoteException e) {
                Slog.w("ActivityManager", "Could not load application info for recents component: " + cn);
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

    private void notifyTaskRemoved(TaskRecord task, boolean wasTrimmed) {
        for (int i = 0; i < this.mCallbacks.size(); i++) {
            this.mCallbacks.get(i).onRecentTaskRemoved(task, wasTrimmed);
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
        Slog.i("ActivityManager", "Loading recents for user " + userId + " into memory.");
        this.mTasks.addAll(this.mTaskPersister.restoreTasksForUserLocked(userId, preaddedTasks));
        cleanupLocked(userId);
        this.mUsersWithRecentsLoaded.put(userId, true);
        if (preaddedTasks.size() > 0) {
            syncPersistentTaskIdsLocked();
        }
    }

    private void loadPersistedTaskIdsForUserLocked(int userId) {
        if (this.mPersistedTaskIds.get(userId) == null) {
            this.mPersistedTaskIds.put(userId, this.mTaskPersister.loadPersistedTaskIdsForUser(userId));
            Slog.i("ActivityManager", "Loaded persisted task ids for user " + userId);
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
                    Slog.wtf("ActivityManager", "No task ids found for userId " + task.userId + ". task=" + task + " mPersistedTaskIds=" + this.mPersistedTaskIds);
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
        this.mTaskPersister.startPersisting();
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
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                syncPersistentTaskIdsLocked();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
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
            Slog.i("ActivityManager", "Unloading recents for user " + userId + " from memory.");
            this.mUsersWithRecentsLoaded.delete(userId);
            removeTasksForUserLocked(userId);
        }
        this.mPersistedTaskIds.delete(userId);
        this.mTaskPersister.unloadUserDataFromMemory(userId);
    }

    private void removeTasksForUserLocked(int userId) {
        if (userId <= 0) {
            Slog.i("ActivityManager", "Can't remove recent task on user " + userId);
            return;
        }
        for (int i = this.mTasks.size() - 1; i >= 0; i--) {
            TaskRecord tr = this.mTasks.get(i);
            if (tr.userId == userId) {
                if (ActivityManagerDebugConfig.DEBUG_TASKS) {
                    Slog.i("ActivityManager", "remove RecentTask " + tr + " when finishing user" + userId);
                }
                remove(this.mTasks.get(i));
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
                    this.mService.mStackSupervisor.removeTaskByIdLocked(tr.taskId, false, true, "suspended-package");
                }
                notifyTaskPersisterLocked(tr, false);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onLockTaskModeStateChanged(int lockTaskModeState, int userId) {
        if (lockTaskModeState == 1) {
            int i = this.mTasks.size() - 1;
            while (true) {
                int i2 = i;
                if (i2 >= 0) {
                    TaskRecord tr = this.mTasks.get(i2);
                    if (tr.userId == userId && !this.mService.getLockTaskController().isTaskWhitelisted(tr)) {
                        remove(tr);
                    }
                    i = i2 - 1;
                } else {
                    return;
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
                this.mService.mStackSupervisor.removeTaskByIdLocked(tr.taskId, true, true, "remove-package-task");
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
                    this.mService.mStackSupervisor.removeTaskByIdLocked(tr.taskId, false, true, "disabled-package");
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
                    this.mTasks.remove(i);
                    notifyTaskRemoved(task, false);
                    Slog.w("ActivityManager", "Removing auto-remove without activity: " + task);
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
                            this.mTasks.remove(i);
                            notifyTaskRemoved(task, false);
                            Slog.w("ActivityManager", "Removing no longer valid recent: " + task);
                        } else {
                            if (ActivityManagerDebugConfig.DEBUG_RECENTS && task.isAvailable) {
                                Slog.d("ActivityManager", "Making recent unavailable: " + task);
                            }
                            task.isAvailable = false;
                        }
                    } else if (!ai.enabled || !ai.applicationInfo.enabled || (ai.applicationInfo.flags & DumpState.DUMP_VOLUMES) == 0) {
                        if (ActivityManagerDebugConfig.DEBUG_RECENTS && task.isAvailable) {
                            Slog.d("ActivityManager", "Making recent unavailable: " + task + " (enabled=" + ai.enabled + SliceClientPermissions.SliceAuthority.DELIMITER + ai.applicationInfo.enabled + " flags=" + Integer.toHexString(ai.applicationInfo.flags) + ")");
                        }
                        task.isAvailable = false;
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_RECENTS && !task.isAvailable) {
                            Slog.d("ActivityManager", "Making recent available: " + task);
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
                ActivityManager.RecentTaskInfo taskInfo = createRecentTaskInfo(tr);
                AppTaskImpl taskImpl = new AppTaskImpl(this.mService, taskInfo.persistentId, callingUid);
                list.add(taskImpl.asBinder());
            }
        }
        return list;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags, boolean getTasksAllowed, boolean getDetailedTasks, int userId, int callingUid) {
        RecentTasks recentTasks = this;
        int i = 0;
        boolean withExcluded = (flags & 1) != 0;
        if (!recentTasks.mService.isUserRunning(userId, 4)) {
            Slog.i("ActivityManager", "user " + userId + " is still locked. Cannot load recents");
            return ParceledListSlice.emptyList();
        }
        recentTasks.loadUserRecentsLocked(userId);
        Set<Integer> includedUsers = recentTasks.mUserController.getProfileIds(userId);
        includedUsers.add(Integer.valueOf(userId));
        ArrayList<ActivityManager.RecentTaskInfo> res = new ArrayList<>();
        int size = recentTasks.mTasks.size();
        int numVisibleTasks = 0;
        while (i < size) {
            TaskRecord tr = recentTasks.mTasks.get(i);
            if (recentTasks.isVisibleRecentTask(tr)) {
                numVisibleTasks++;
                if (recentTasks.isInVisibleRange(tr, numVisibleTasks)) {
                    if (res.size() < maxNum) {
                        if (!includedUsers.contains(Integer.valueOf(tr.userId))) {
                            if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                                Slog.d("ActivityManager", "Skipping, not user: " + tr);
                            }
                        } else if (tr.realActivitySuspended) {
                            if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                                Slog.d("ActivityManager", "Skipping, activity suspended: " + tr);
                            }
                        } else if (i == 0 || withExcluded || tr.intent == null || (tr.intent.getFlags() & DumpState.DUMP_VOLUMES) == 0) {
                            if (!getTasksAllowed && !tr.isActivityTypeHome()) {
                                if (tr.effectiveUid != callingUid) {
                                    if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                                        Slog.d("ActivityManager", "Skipping, not allowed: " + tr);
                                    }
                                    i++;
                                    recentTasks = this;
                                }
                            }
                            if (tr.autoRemoveRecents && tr.getTopActivity() == null) {
                                if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                                    Slog.d("ActivityManager", "Skipping, auto-remove without activity: " + tr);
                                }
                            } else if ((flags & 2) != 0 && !tr.isAvailable) {
                                if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                                    Slog.d("ActivityManager", "Skipping, unavail real act: " + tr);
                                }
                            } else if (!tr.mUserSetupComplete) {
                                if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                                    Slog.d("ActivityManager", "Skipping, user setup not complete: " + tr);
                                }
                            } else {
                                ActivityManager.RecentTaskInfo rti = recentTasks.createRecentTaskInfo(tr);
                                if (!getDetailedTasks) {
                                    rti.baseIntent.replaceExtras((Bundle) null);
                                }
                                res.add(rti);
                            }
                            i++;
                            recentTasks = this;
                        }
                    }
                    i++;
                    recentTasks = this;
                }
            }
            i++;
            recentTasks = this;
        }
        return new ParceledListSlice<>(res);
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
                if (isInVisibleRange(tr, numVisibleTasks)) {
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
        if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
            Slog.d("ActivityManager", "add: task=" + task);
        }
        boolean isAffiliated = (task.mAffiliatedTaskId == task.taskId && task.mNextAffiliateTaskId == -1 && task.mPrevAffiliateTaskId == -1) ? false : true;
        int recentsCount = this.mTasks.size();
        if (task.voiceSession != null) {
            if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityManager", "addRecent: not adding voice interaction " + task);
            }
        } else if (!isAffiliated && recentsCount > 0 && this.mTasks.get(0) == task) {
            if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityManager", "addRecent: already at top: " + task);
            }
        } else if (isAffiliated && recentsCount > 0 && task.inRecents && task.mAffiliatedTaskId == this.mTasks.get(0).mAffiliatedTaskId) {
            if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityManager", "addRecent: affiliated " + this.mTasks.get(0) + " at top when adding " + task);
            }
        } else {
            boolean needAffiliationFix = false;
            if (task.inRecents) {
                int taskIndex2 = this.mTasks.indexOf(task);
                if (taskIndex2 >= 0) {
                    this.mTasks.remove(taskIndex2);
                    this.mTasks.add(0, task);
                    notifyTaskPersisterLocked(task, false);
                    if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                        Slog.d("ActivityManager", "addRecent: moving to top " + task + " from " + taskIndex2);
                        return;
                    }
                    return;
                }
                Slog.wtf("ActivityManager", "Task with inRecent not in recents: " + task);
                needAffiliationFix = true;
            }
            if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                Slog.d("ActivityManager", "addRecent: trimming tasks for " + task);
            }
            removeForAddTask(task);
            task.inRecents = true;
            if (!isAffiliated || needAffiliationFix) {
                this.mTasks.add(0, task);
                notifyTaskAdded(task);
                if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                    Slog.d("ActivityManager", "addRecent: adding " + task);
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
                        if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                            Slog.d("ActivityManager", "addRecent: new affiliated task added at " + taskIndex + ": " + task);
                        }
                        this.mTasks.add(taskIndex, task);
                        notifyTaskAdded(task);
                        if (moveAffiliatedTasksToFront(task, taskIndex)) {
                            return;
                        }
                        needAffiliationFix = true;
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                            Slog.d("ActivityManager", "addRecent: couldn't find other affiliation " + other);
                        }
                        needAffiliationFix = true;
                    }
                } else {
                    if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                        Slog.d("ActivityManager", "addRecent: adding affiliated task without next/prev:" + task);
                    }
                    needAffiliationFix = true;
                }
            }
            if (needAffiliationFix) {
                if (ActivityManagerDebugConfig.DEBUG_RECENTS) {
                    Slog.d("ActivityManager", "addRecent: regrouping affiliations");
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
        notifyTaskRemoved(task, false);
    }

    private void trimInactiveRecentTasks() {
        int recentsCount = this.mTasks.size();
        while (recentsCount > this.mGlobalMaxNumTasks) {
            TaskRecord tr = this.mTasks.remove(recentsCount - 1);
            notifyTaskRemoved(tr, true);
            recentsCount--;
            if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityManager", "Trimming over max-recents task=" + tr + " max=" + this.mGlobalMaxNumTasks);
            }
        }
        int[] profileUserIds = this.mUserController.getCurrentProfileIds();
        this.mTmpQuietProfileUserIds.clear();
        for (int userId : profileUserIds) {
            UserInfo userInfo = this.mUserController.getUserInfo(userId);
            if (userInfo != null && userInfo.isManagedProfile() && userInfo.isQuietModeEnabled()) {
                this.mTmpQuietProfileUserIds.put(userId, true);
            }
            if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityManager", "User: " + userInfo + " quiet=" + this.mTmpQuietProfileUserIds.get(userId));
            }
        }
        int numVisibleTasks = 0;
        int numVisibleTasks2 = 0;
        while (numVisibleTasks2 < this.mTasks.size()) {
            TaskRecord task = this.mTasks.get(numVisibleTasks2);
            if (isActiveRecentTask(task, this.mTmpQuietProfileUserIds)) {
                if (!this.mHasVisibleRecentTasks) {
                    numVisibleTasks2++;
                } else if (!isVisibleRecentTask(task)) {
                    numVisibleTasks2++;
                } else {
                    numVisibleTasks++;
                    if (isInVisibleRange(task, numVisibleTasks) || !isTrimmable(task)) {
                        numVisibleTasks2++;
                    } else if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                        Slog.d("ActivityManager", "Trimming out-of-range visible task=" + task);
                    }
                }
            } else if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityManager", "Trimming inactive task=" + task);
            }
            this.mTasks.remove(task);
            notifyTaskRemoved(task, true);
            notifyTaskPersisterLocked(task, false);
        }
    }

    private boolean isActiveRecentTask(TaskRecord task, SparseBooleanArray quietProfileUserIds) {
        TaskRecord affiliatedTask;
        if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
            Slog.d("ActivityManager", "isActiveRecentTask: task=" + task + " globalMax=" + this.mGlobalMaxNumTasks);
        }
        if (quietProfileUserIds.get(task.userId)) {
            if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityManager", "\tisQuietProfileTask=true");
            }
            return false;
        } else if (task.mAffiliatedTaskId != -1 && task.mAffiliatedTaskId != task.taskId && (affiliatedTask = getTask(task.mAffiliatedTaskId)) != null && !isActiveRecentTask(affiliatedTask, quietProfileUserIds)) {
            if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityManager", "\taffiliatedWithTask=" + affiliatedTask + " is not active");
            }
            return false;
        } else {
            return true;
        }
    }

    private boolean isVisibleRecentTask(TaskRecord task) {
        if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
            Slog.d("ActivityManager", "isVisibleRecentTask: task=" + task + " minVis=" + this.mMinNumVisibleTasks + " maxVis=" + this.mMaxNumVisibleTasks + " sessionDuration=" + this.mActiveTasksSessionDurationMs + " inactiveDuration=" + task.getInactiveDuration() + " activityType=" + task.getActivityType() + " windowingMode=" + task.getWindowingMode() + " intentFlags=" + task.getBaseIntent().getFlags());
        }
        switch (task.getActivityType()) {
            case 2:
            case 3:
                return false;
            case 4:
                if ((task.getBaseIntent().getFlags() & DumpState.DUMP_VOLUMES) == 8388608) {
                    return false;
                }
                break;
        }
        switch (task.getWindowingMode()) {
            case 2:
                return false;
            case 3:
                if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                    Slog.d("ActivityManager", "\ttop=" + task.getStack().topTask());
                }
                ActivityStack stack = task.getStack();
                if (stack != null && stack.topTask() == task) {
                    return false;
                }
                break;
        }
        return task != this.mService.getLockTaskController().getRootTask();
    }

    private boolean isInVisibleRange(TaskRecord task, int numVisibleTasks) {
        boolean isExcludeFromRecents = (task.getBaseIntent().getFlags() & DumpState.DUMP_VOLUMES) == 8388608;
        if (isExcludeFromRecents) {
            if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityManager", "\texcludeFromRecents=true");
            }
            return numVisibleTasks == 1;
        } else if (this.mMinNumVisibleTasks < 0 || numVisibleTasks > this.mMinNumVisibleTasks) {
            return this.mMaxNumVisibleTasks >= 0 ? numVisibleTasks <= this.mMaxNumVisibleTasks : this.mActiveTasksSessionDurationMs > 0 && task.getInactiveDuration() <= this.mActiveTasksSessionDurationMs;
        } else {
            return true;
        }
    }

    protected boolean isTrimmable(TaskRecord task) {
        ActivityStack stack = task.getStack();
        ActivityStack homeStack = this.mService.mStackSupervisor.mHomeStack;
        if (stack == null) {
            return true;
        }
        if (stack.getDisplay() != homeStack.getDisplay()) {
            return false;
        }
        ActivityDisplay display = stack.getDisplay();
        return display.getIndexOf(stack) < display.getIndexOf(homeStack);
    }

    private void removeForAddTask(TaskRecord task) {
        int removeIndex = findRemoveIndexForAddTask(task);
        if (removeIndex == -1) {
            return;
        }
        TaskRecord removedTask = this.mTasks.remove(removeIndex);
        if (removedTask != task) {
            notifyTaskRemoved(removedTask, false);
            if (ActivityManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS) {
                Slog.d("ActivityManager", "Trimming task=" + removedTask + " for addition of task=" + task);
            }
        }
        notifyTaskPersisterLocked(removedTask, false);
    }

    private int findRemoveIndexForAddTask(TaskRecord task) {
        int recentsCount = this.mTasks.size();
        Intent intent = task.intent;
        boolean z = true;
        boolean document = intent != null && intent.isDocument();
        int maxRecents = task.maxRecents - 1;
        int maxRecents2 = maxRecents;
        int maxRecents3 = 0;
        while (maxRecents3 < recentsCount) {
            TaskRecord tr = this.mTasks.get(maxRecents3);
            if (task != tr) {
                if (hasCompatibleActivityTypeAndWindowingMode(task, tr) && task.userId == tr.userId) {
                    Intent trIntent = tr.intent;
                    boolean sameAffinity = (task.affinity == null || !task.affinity.equals(tr.affinity)) ? false : z;
                    boolean sameIntent = (intent == null || !intent.filterEquals(trIntent)) ? false : z;
                    boolean multiTasksAllowed = false;
                    int flags = intent.getFlags();
                    if ((268959744 & flags) != 0 && (134217728 & flags) != 0) {
                        multiTasksAllowed = true;
                    }
                    boolean trIsDocument = (trIntent == null || !trIntent.isDocument()) ? false : z;
                    boolean bothDocuments = (document && trIsDocument) ? z : false;
                    if (sameAffinity || sameIntent || bothDocuments) {
                        if (bothDocuments) {
                            boolean sameActivity = (task.realActivity == null || tr.realActivity == null || !task.realActivity.equals(tr.realActivity)) ? false : true;
                            if (!sameActivity) {
                                continue;
                            } else if (maxRecents2 > 0) {
                                maxRecents2--;
                                if (sameIntent && !multiTasksAllowed) {
                                }
                            }
                        } else if (!document && !trIsDocument) {
                        }
                    }
                }
                maxRecents3++;
                z = true;
            }
            return maxRecents3;
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
            Slog.w("ActivityManager", "Link error 1 first.next=" + first.mNextAffiliate);
            first.setNextAffiliate(null);
            notifyTaskPersisterLocked(first, false);
        }
        int tmpSize = this.mTmpRecents.size();
        for (int i2 = 0; i2 < tmpSize - 1; i2++) {
            TaskRecord next = this.mTmpRecents.get(i2);
            TaskRecord prev = this.mTmpRecents.get(i2 + 1);
            if (next.mPrevAffiliate != prev) {
                Slog.w("ActivityManager", "Link error 2 next=" + next + " prev=" + next.mPrevAffiliate + " setting prev=" + prev);
                next.setPrevAffiliate(prev);
                notifyTaskPersisterLocked(next, false);
            }
            if (prev.mNextAffiliate != next) {
                Slog.w("ActivityManager", "Link error 3 prev=" + prev + " next=" + prev.mNextAffiliate + " setting next=" + next);
                prev.setNextAffiliate(next);
                notifyTaskPersisterLocked(prev, false);
            }
            prev.inRecents = true;
        }
        TaskRecord last = this.mTmpRecents.get(tmpSize - 1);
        if (last.mPrevAffiliate != null) {
            Slog.w("ActivityManager", "Link error 4 last.prev=" + last.mPrevAffiliate);
            last.setPrevAffiliate(null);
            notifyTaskPersisterLocked(last, false);
        }
        this.mTasks.addAll(start, this.mTmpRecents);
        this.mTmpRecents.clear();
        return start + tmpSize;
    }

    /* JADX WARN: Code restructure failed: missing block: B:25:0x007b, code lost:
        android.util.Slog.wtf("ActivityManager", "Bad chain @" + r7 + ": first task has next affiliate: " + r4);
        r3 = false;
     */
    /* JADX WARN: Removed duplicated region for block: B:39:0x00f5  */
    /* JADX WARN: Removed duplicated region for block: B:69:0x00ac A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private boolean moveAffiliatedTasksToFront(com.android.server.am.TaskRecord r13, int r14) {
        /*
            Method dump skipped, instructions count: 613
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.RecentTasks.moveAffiliatedTasksToFront(com.android.server.am.TaskRecord, int):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, boolean dumpAll, String dumpPackage) {
        pw.println("ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)");
        pw.println("mRecentsUid=" + this.mRecentsUid);
        pw.println("mRecentsComponent=" + this.mRecentsComponent);
        if (this.mTasks.isEmpty()) {
            return;
        }
        boolean printedAnything = false;
        boolean printedHeader = false;
        int size = this.mTasks.size();
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
        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.RecentTaskInfo createRecentTaskInfo(TaskRecord tr) {
        ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
        rti.id = tr.getTopActivity() == null ? -1 : tr.taskId;
        rti.persistentId = tr.taskId;
        rti.baseIntent = new Intent(tr.getBaseIntent());
        rti.origActivity = tr.origActivity;
        rti.realActivity = tr.realActivity;
        rti.description = tr.lastDescription;
        rti.stackId = tr.getStackId();
        rti.userId = tr.userId;
        rti.taskDescription = new ActivityManager.TaskDescription(tr.lastTaskDescription);
        rti.lastActiveTime = tr.lastActiveTime;
        rti.affiliatedTaskId = tr.mAffiliatedTaskId;
        rti.affiliatedTaskColor = tr.mAffiliatedTaskColor;
        rti.numActivities = 0;
        if (!tr.matchParentBounds()) {
            rti.bounds = new Rect(tr.getOverrideBounds());
        }
        rti.supportsSplitScreenMultiWindow = tr.supportsSplitScreenWindowingMode();
        rti.resizeMode = tr.mResizeMode;
        rti.configuration.setTo(tr.getConfiguration());
        tr.getNumRunningActivities(this.mTmpReport);
        rti.numActivities = this.mTmpReport.numActivities;
        rti.baseActivity = this.mTmpReport.base != null ? this.mTmpReport.base.intent.getComponent() : null;
        rti.topActivity = this.mTmpReport.top != null ? this.mTmpReport.top.intent.getComponent() : null;
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
