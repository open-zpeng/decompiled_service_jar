package com.android.server.usage;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.usage.AppStandbyInfo;
import android.app.usage.ConfigurationStats;
import android.app.usage.EventStats;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.usage.AppTimeLimitController;
import com.android.server.usage.UserUsageStatsService;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/* loaded from: classes2.dex */
public class UsageStatsService extends SystemService implements UserUsageStatsService.StatsUpdatedListener {
    static final boolean COMPRESS_TIME = false;
    static final boolean DEBUG = false;
    private static final boolean ENABLE_KERNEL_UPDATES = true;
    private static final long FLUSH_INTERVAL = 1200000;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_REPORT_EVENT_TO_ALL_USERID = 4;
    static final int MSG_UID_STATE_CHANGED = 3;
    static final String TAG = "UsageStatsService";
    private static final long TEN_SECONDS = 10000;
    private static final long TIME_CHANGE_THRESHOLD_MILLIS = 2000;
    private static final char TOKEN_DELIMITER = '/';
    private static final long TWENTY_MINUTES = 1200000;
    AppOpsManager mAppOps;
    AppStandbyController mAppStandby;
    AppTimeLimitController mAppTimeLimit;
    IDeviceIdleController mDeviceIdleController;
    DevicePolicyManagerInternal mDpmInternal;
    Handler mHandler;
    private final Object mLock;
    PackageManager mPackageManager;
    PackageManagerInternal mPackageManagerInternal;
    PackageMonitor mPackageMonitor;
    long mRealTimeSnapshot;
    private UsageStatsManagerInternal.AppIdleStateChangeListener mStandbyChangeListener;
    long mSystemTimeSnapshot;
    private final IUidObserver mUidObserver;
    private final SparseIntArray mUidToKernelCounter;
    final SparseArray<ArraySet<String>> mUsageReporters;
    int mUsageSource;
    private File mUsageStatsDir;
    UserManager mUserManager;
    private final SparseArray<UserUsageStatsService> mUserState;
    final SparseArray<ActivityData> mVisibleActivities;
    public static final boolean ENABLE_TIME_CHANGE_CORRECTION = SystemProperties.getBoolean("persist.debug.time_correction", true);
    private static final File KERNEL_COUNTER_FILE = new File("/proc/uid_procstat/set");

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class ActivityData {
        private final String mTaskRootClass;
        private final String mTaskRootPackage;

        private ActivityData(String taskRootPackage, String taskRootClass) {
            this.mTaskRootPackage = taskRootPackage;
            this.mTaskRootClass = taskRootClass;
        }
    }

    public UsageStatsService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mUserState = new SparseArray<>();
        this.mUidToKernelCounter = new SparseIntArray();
        this.mUsageReporters = new SparseArray<>();
        this.mVisibleActivities = new SparseArray<>();
        this.mStandbyChangeListener = new UsageStatsManagerInternal.AppIdleStateChangeListener() { // from class: com.android.server.usage.UsageStatsService.1
            public void onAppIdleStateChanged(String packageName, int userId, boolean idle, int bucket, int reason) {
                UsageEvents.Event event = new UsageEvents.Event(11, SystemClock.elapsedRealtime());
                event.mBucketAndReason = (bucket << 16) | (65535 & reason);
                event.mPackage = packageName;
                UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
            }

            public void onParoleStateChanged(boolean isParoleOn) {
            }
        };
        this.mUidObserver = new IUidObserver.Stub() { // from class: com.android.server.usage.UsageStatsService.3
            public void onUidStateChanged(int uid, int procState, long procStateSeq) {
                UsageStatsService.this.mHandler.obtainMessage(3, uid, procState).sendToTarget();
            }

            public void onUidIdle(int uid, boolean disabled) {
            }

            public void onUidGone(int uid, boolean disabled) {
                onUidStateChanged(uid, 21, 0L);
            }

            public void onUidActive(int uid) {
            }

            public void onUidCachedChanged(int uid, boolean cached) {
            }
        };
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        this.mAppOps = (AppOpsManager) getContext().getSystemService("appops");
        this.mUserManager = (UserManager) getContext().getSystemService("user");
        this.mPackageManager = getContext().getPackageManager();
        this.mPackageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        this.mHandler = new H(BackgroundThread.get().getLooper());
        this.mAppStandby = new AppStandbyController(getContext(), BackgroundThread.get().getLooper());
        this.mAppTimeLimit = new AppTimeLimitController(new AppTimeLimitController.TimeLimitCallbackListener() { // from class: com.android.server.usage.UsageStatsService.2
            @Override // com.android.server.usage.AppTimeLimitController.TimeLimitCallbackListener
            public void onLimitReached(int observerId, int userId, long timeLimit, long timeElapsed, PendingIntent callbackIntent) {
                if (callbackIntent == null) {
                    return;
                }
                Intent intent = new Intent();
                intent.putExtra("android.app.usage.extra.OBSERVER_ID", observerId);
                intent.putExtra("android.app.usage.extra.TIME_LIMIT", timeLimit);
                intent.putExtra("android.app.usage.extra.TIME_USED", timeElapsed);
                try {
                    callbackIntent.send(UsageStatsService.this.getContext(), 0, intent);
                } catch (PendingIntent.CanceledException e) {
                    Slog.w(UsageStatsService.TAG, "Couldn't deliver callback: " + callbackIntent);
                }
            }

            @Override // com.android.server.usage.AppTimeLimitController.TimeLimitCallbackListener
            public void onSessionEnd(int observerId, int userId, long timeElapsed, PendingIntent callbackIntent) {
                if (callbackIntent == null) {
                    return;
                }
                Intent intent = new Intent();
                intent.putExtra("android.app.usage.extra.OBSERVER_ID", observerId);
                intent.putExtra("android.app.usage.extra.TIME_USED", timeElapsed);
                try {
                    callbackIntent.send(UsageStatsService.this.getContext(), 0, intent);
                } catch (PendingIntent.CanceledException e) {
                    Slog.w(UsageStatsService.TAG, "Couldn't deliver callback: " + callbackIntent);
                }
            }
        }, this.mHandler.getLooper());
        this.mAppStandby.addListener(this.mStandbyChangeListener);
        File systemDataDir = new File(Environment.getDataDirectory(), "system");
        this.mUsageStatsDir = new File(systemDataDir, "usagestats");
        this.mUsageStatsDir.mkdirs();
        if (!this.mUsageStatsDir.exists()) {
            throw new IllegalStateException("Usage stats directory does not exist: " + this.mUsageStatsDir.getAbsolutePath());
        }
        IntentFilter filter = new IntentFilter("android.intent.action.USER_REMOVED");
        filter.addAction("android.intent.action.USER_STARTED");
        getContext().registerReceiverAsUser(new UserActionsReceiver(), UserHandle.ALL, filter, null, this.mHandler);
        synchronized (this.mLock) {
            cleanUpRemovedUsersLocked();
        }
        this.mRealTimeSnapshot = SystemClock.elapsedRealtime();
        this.mSystemTimeSnapshot = System.currentTimeMillis();
        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishBinderService("usagestats", new BinderService());
        getUserDataAndInitializeIfNeededLocked(0, this.mSystemTimeSnapshot);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        this.mAppStandby.onBootPhase(phase);
        if (phase == 500) {
            getDpmInternal();
            this.mDeviceIdleController = IDeviceIdleController.Stub.asInterface(ServiceManager.getService("deviceidle"));
            if (KERNEL_COUNTER_FILE.exists()) {
                try {
                    ActivityManager.getService().registerUidObserver(this.mUidObserver, 3, -1, (String) null);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            } else {
                Slog.w(TAG, "Missing procfs interface: " + KERNEL_COUNTER_FILE);
            }
            readUsageSourceSetting();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public DevicePolicyManagerInternal getDpmInternal() {
        if (this.mDpmInternal == null) {
            this.mDpmInternal = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class);
        }
        return this.mDpmInternal;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void readUsageSourceSetting() {
        synchronized (this.mLock) {
            this.mUsageSource = Settings.Global.getInt(getContext().getContentResolver(), "app_time_limit_usage_source", 1);
        }
    }

    /* loaded from: classes2.dex */
    private class UserActionsReceiver extends BroadcastReceiver {
        private UserActionsReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            int userId = intent.getIntExtra("android.intent.extra.user_handle", -1);
            String action = intent.getAction();
            if ("android.intent.action.USER_REMOVED".equals(action)) {
                if (userId >= 0) {
                    UsageStatsService.this.mHandler.obtainMessage(2, userId, 0).sendToTarget();
                }
            } else if ("android.intent.action.USER_STARTED".equals(action) && userId >= 0) {
                UsageStatsService.this.mAppStandby.postCheckIdleStates(userId);
            }
        }
    }

    @Override // com.android.server.usage.UserUsageStatsService.StatsUpdatedListener
    public void onStatsUpdated() {
        this.mHandler.sendEmptyMessageDelayed(1, 1200000L);
    }

    @Override // com.android.server.usage.UserUsageStatsService.StatsUpdatedListener
    public void onStatsReloaded() {
        this.mAppStandby.postOneTimeCheckIdleStates();
    }

    @Override // com.android.server.usage.UserUsageStatsService.StatsUpdatedListener
    public void onNewUpdate(int userId) {
        this.mAppStandby.initializeDefaultsForSystemApps(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean shouldObfuscateInstantAppsForCaller(int callingUid, int userId) {
        return !this.mPackageManagerInternal.canAccessInstantApps(callingUid, userId);
    }

    private void cleanUpRemovedUsersLocked() {
        List<UserInfo> users = this.mUserManager.getUsers(true);
        if (users == null || users.size() == 0) {
            throw new IllegalStateException("There can't be no users");
        }
        ArraySet<String> toDelete = new ArraySet<>();
        String[] fileNames = this.mUsageStatsDir.list();
        if (fileNames == null) {
            return;
        }
        toDelete.addAll(Arrays.asList(fileNames));
        int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            UserInfo userInfo = users.get(i);
            toDelete.remove(Integer.toString(userInfo.id));
        }
        int deleteCount = toDelete.size();
        for (int i2 = 0; i2 < deleteCount; i2++) {
            deleteRecursively(new File(this.mUsageStatsDir, toDelete.valueAt(i2)));
        }
    }

    private static void deleteRecursively(File f) {
        File[] files = f.listFiles();
        if (files != null) {
            for (File subFile : files) {
                deleteRecursively(subFile);
            }
        }
        if (!f.delete()) {
            Slog.e(TAG, "Failed to delete " + f);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public UserUsageStatsService getUserDataAndInitializeIfNeededLocked(int userId, long currentTimeMillis) {
        UserUsageStatsService service = this.mUserState.get(userId);
        if (service == null) {
            UserUsageStatsService service2 = new UserUsageStatsService(getContext(), userId, new File(this.mUsageStatsDir, Integer.toString(userId)), this);
            service2.init(currentTimeMillis);
            this.mUserState.put(userId, service2);
            return service2;
        }
        return service;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long checkAndGetTimeLocked() {
        long actualSystemTime = System.currentTimeMillis();
        long actualRealtime = SystemClock.elapsedRealtime();
        long expectedSystemTime = (actualRealtime - this.mRealTimeSnapshot) + this.mSystemTimeSnapshot;
        long diffSystemTime = actualSystemTime - expectedSystemTime;
        if (Math.abs(diffSystemTime) > TIME_CHANGE_THRESHOLD_MILLIS && ENABLE_TIME_CHANGE_CORRECTION) {
            Slog.i(TAG, "Time changed in UsageStats by " + (diffSystemTime / 1000) + " seconds");
            int userCount = this.mUserState.size();
            for (int i = 0; i < userCount; i++) {
                UserUsageStatsService service = this.mUserState.valueAt(i);
                service.onTimeChanged(expectedSystemTime, actualSystemTime);
            }
            this.mRealTimeSnapshot = actualRealtime;
            this.mSystemTimeSnapshot = actualSystemTime;
        }
        return actualSystemTime;
    }

    private void convertToSystemTimeLocked(UsageEvents.Event event) {
        event.mTimeStamp = Math.max(0L, event.mTimeStamp - this.mRealTimeSnapshot) + this.mSystemTimeSnapshot;
    }

    void shutdown() {
        synchronized (this.mLock) {
            this.mHandler.removeMessages(0);
            UsageEvents.Event event = new UsageEvents.Event(26, SystemClock.elapsedRealtime());
            event.mPackage = PackageManagerService.PLATFORM_PACKAGE_NAME;
            reportEventToAllUserId(event);
            flushToDiskLocked();
        }
    }

    void prepareForPossibleShutdown() {
        UsageEvents.Event event = new UsageEvents.Event(26, SystemClock.elapsedRealtime());
        event.mPackage = PackageManagerService.PLATFORM_PACKAGE_NAME;
        this.mHandler.obtainMessage(4, event).sendToTarget();
        this.mHandler.sendEmptyMessage(1);
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:52:0x00c8 -> B:86:0x0168). Please submit an issue!!! */
    void reportEvent(UsageEvents.Event event, int userId) {
        ArraySet<String> tokens;
        int size;
        synchronized (this.mLock) {
            long timeNow = checkAndGetTimeLocked();
            long elapsedRealtime = SystemClock.elapsedRealtime();
            convertToSystemTimeLocked(event);
            if (event.mPackage != null && this.mPackageManagerInternal.isPackageEphemeral(userId, event.mPackage)) {
                event.mFlags |= 1;
            }
            int i = event.mEventType;
            if (i == 1) {
                if (this.mVisibleActivities.get(event.mInstanceId) == null) {
                    this.mVisibleActivities.put(event.mInstanceId, new ActivityData(event.mTaskRootPackage, event.mTaskRootClass));
                    try {
                        if (this.mUsageSource == 2) {
                            this.mAppTimeLimit.noteUsageStart(event.mPackage, userId);
                        } else {
                            this.mAppTimeLimit.noteUsageStart(event.mTaskRootPackage, userId);
                        }
                    } catch (IllegalArgumentException iae) {
                        Slog.e(TAG, "Failed to note usage start", iae);
                    }
                }
                UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
                service.reportEvent(event);
                this.mAppStandby.reportEvent(event, elapsedRealtime, userId);
                return;
            }
            if (i == 2) {
                if (event.mTaskRootPackage == null) {
                    ActivityData prevData = this.mVisibleActivities.get(event.mInstanceId);
                    if (prevData != null) {
                        event.mTaskRootPackage = prevData.mTaskRootPackage;
                        event.mTaskRootClass = prevData.mTaskRootClass;
                    } else {
                        Slog.w(TAG, "Unexpected activity event reported! (" + event.mPackage + SliceClientPermissions.SliceAuthority.DELIMITER + event.mClass + " event : " + event.mEventType + " instanceId : " + event.mInstanceId + ")");
                    }
                }
            } else {
                if (i != 23) {
                    if (i == 24) {
                        event.mEventType = 23;
                    }
                }
                ActivityData prevData2 = (ActivityData) this.mVisibleActivities.removeReturnOld(event.mInstanceId);
                if (prevData2 == null) {
                    return;
                }
                synchronized (this.mUsageReporters) {
                    tokens = (ArraySet) this.mUsageReporters.removeReturnOld(event.mInstanceId);
                }
                if (tokens != null) {
                    synchronized (tokens) {
                        int size2 = tokens.size();
                        int i2 = 0;
                        while (i2 < size2) {
                            String token = tokens.valueAt(i2);
                            try {
                                this.mAppTimeLimit.noteUsageStop(buildFullToken(event.mPackage, token), userId);
                                size = size2;
                            } catch (IllegalArgumentException iae2) {
                                StringBuilder sb = new StringBuilder();
                                size = size2;
                                sb.append("Failed to stop usage for during reporter death: ");
                                sb.append(iae2);
                                Slog.w(TAG, sb.toString());
                            }
                            i2++;
                            size2 = size;
                        }
                    }
                }
                if (event.mTaskRootPackage == null) {
                    event.mTaskRootPackage = prevData2.mTaskRootPackage;
                    event.mTaskRootClass = prevData2.mTaskRootClass;
                }
                try {
                    if (this.mUsageSource == 2) {
                        this.mAppTimeLimit.noteUsageStop(event.mPackage, userId);
                    } else {
                        this.mAppTimeLimit.noteUsageStop(event.mTaskRootPackage, userId);
                    }
                } catch (IllegalArgumentException iae3) {
                    Slog.w(TAG, "Failed to note usage stop", iae3);
                }
            }
            UserUsageStatsService service2 = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            service2.reportEvent(event);
            this.mAppStandby.reportEvent(event, elapsedRealtime, userId);
            return;
        }
    }

    void reportEventToAllUserId(UsageEvents.Event event) {
        synchronized (this.mLock) {
            int userCount = this.mUserState.size();
            for (int i = 0; i < userCount; i++) {
                UsageEvents.Event copy = new UsageEvents.Event(event);
                reportEvent(copy, this.mUserState.keyAt(i));
            }
        }
    }

    void flushToDisk() {
        synchronized (this.mLock) {
            UsageEvents.Event event = new UsageEvents.Event(25, SystemClock.elapsedRealtime());
            reportEventToAllUserId(event);
            flushToDiskLocked();
        }
    }

    void onUserRemoved(int userId) {
        synchronized (this.mLock) {
            Slog.i(TAG, "Removing user " + userId + " and all data.");
            this.mUserState.remove(userId);
            this.mAppStandby.onUserRemoved(userId);
            this.mAppTimeLimit.onUserRemoved(userId);
            cleanUpRemovedUsersLocked();
        }
    }

    List<UsageStats> queryUsageStats(int userId, int bucketType, long beginTime, long endTime, boolean obfuscateInstantApps) {
        synchronized (this.mLock) {
            long timeNow = checkAndGetTimeLocked();
            if (validRange(timeNow, beginTime, endTime)) {
                UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
                List<UsageStats> list = service.queryUsageStats(bucketType, beginTime, endTime);
                if (list == null) {
                    return null;
                }
                if (obfuscateInstantApps) {
                    for (int i = list.size() - 1; i >= 0; i--) {
                        UsageStats stats = list.get(i);
                        if (this.mPackageManagerInternal.isPackageEphemeral(userId, stats.mPackageName)) {
                            list.set(i, stats.getObfuscatedForInstantApp());
                        }
                    }
                }
                return list;
            }
            return null;
        }
    }

    List<ConfigurationStats> queryConfigurationStats(int userId, int bucketType, long beginTime, long endTime) {
        synchronized (this.mLock) {
            long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }
            UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryConfigurationStats(bucketType, beginTime, endTime);
        }
    }

    List<EventStats> queryEventStats(int userId, int bucketType, long beginTime, long endTime) {
        synchronized (this.mLock) {
            long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }
            UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEventStats(bucketType, beginTime, endTime);
        }
    }

    UsageEvents queryEvents(int userId, long beginTime, long endTime, boolean shouldObfuscateInstantApps) {
        synchronized (this.mLock) {
            long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }
            UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEvents(beginTime, endTime, shouldObfuscateInstantApps);
        }
    }

    UsageEvents queryEventsForPackage(int userId, long beginTime, long endTime, String packageName, boolean includeTaskRoot) {
        synchronized (this.mLock) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                long timeNow = checkAndGetTimeLocked();
                if (!validRange(timeNow, beginTime, endTime)) {
                    return null;
                }
                UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
                return service.queryEventsForPackage(beginTime, endTime, packageName, includeTaskRoot);
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    private static boolean validRange(long currentTime, long beginTime, long endTime) {
        return beginTime <= currentTime && beginTime < endTime;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String buildFullToken(String packageName, String token) {
        StringBuilder sb = new StringBuilder(packageName.length() + token.length() + 1);
        sb.append(packageName);
        sb.append(TOKEN_DELIMITER);
        sb.append(token);
        return sb.toString();
    }

    private void flushToDiskLocked() {
        int userCount = this.mUserState.size();
        for (int i = 0; i < userCount; i++) {
            UserUsageStatsService service = this.mUserState.valueAt(i);
            service.persistActiveStats();
            this.mAppStandby.flushToDisk(this.mUserState.keyAt(i));
        }
        this.mAppStandby.flushDurationsToDisk();
        this.mHandler.removeMessages(1);
    }

    /* JADX WARN: Code restructure failed: missing block: B:35:0x007a, code lost:
        r8 = new com.android.internal.util.IndentingPrintWriter(r15, "  ");
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x0084, code lost:
        if ((r6 + 1) < r14.length) goto L70;
     */
    /* JADX WARN: Code restructure failed: missing block: B:37:0x0086, code lost:
        r9 = r13.mUserState.size();
        r10 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x008d, code lost:
        if (r10 >= r9) goto L67;
     */
    /* JADX WARN: Code restructure failed: missing block: B:39:0x008f, code lost:
        r8.println("user=" + r13.mUserState.keyAt(r10));
        r8.increaseIndent();
        r13.mUserState.valueAt(r10).dumpFile(r8, null);
        r8.decreaseIndent();
     */
    /* JADX WARN: Code restructure failed: missing block: B:40:0x00ba, code lost:
        r10 = r10 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:43:0x00c0, code lost:
        r5 = java.lang.Integer.valueOf(r14[r6 + 1]).intValue();
     */
    /* JADX WARN: Code restructure failed: missing block: B:45:0x00d1, code lost:
        if (r13.mUserState.indexOfKey(r5) >= 0) goto L78;
     */
    /* JADX WARN: Code restructure failed: missing block: B:46:0x00d3, code lost:
        r8.println("the specified user does not exist.");
     */
    /* JADX WARN: Code restructure failed: missing block: B:48:0x00d9, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:49:0x00da, code lost:
        r9 = (java.lang.String[]) java.util.Arrays.copyOfRange(r14, r6 + 2, r14.length);
        r13.mUserState.get(r5).dumpFile(r8, r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:51:0x00ef, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:53:0x00f1, code lost:
        r8.println("invalid user specified.");
     */
    /* JADX WARN: Code restructure failed: missing block: B:55:0x00f7, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:58:0x0100, code lost:
        r5 = new com.android.internal.util.IndentingPrintWriter(r15, "  ");
     */
    /* JADX WARN: Code restructure failed: missing block: B:59:0x010a, code lost:
        if ((r6 + 1) < r14.length) goto L46;
     */
    /* JADX WARN: Code restructure failed: missing block: B:60:0x010c, code lost:
        r8 = r13.mUserState.size();
        r9 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:61:0x0113, code lost:
        if (r9 >= r8) goto L42;
     */
    /* JADX WARN: Code restructure failed: missing block: B:62:0x0115, code lost:
        r5.println("user=" + r13.mUserState.keyAt(r9));
        r5.increaseIndent();
        r13.mUserState.valueAt(r9).dumpDatabaseInfo(r5);
        r5.decreaseIndent();
     */
    /* JADX WARN: Code restructure failed: missing block: B:63:0x0140, code lost:
        r9 = r9 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:66:0x0146, code lost:
        r8 = java.lang.Integer.valueOf(r14[r6 + 1]).intValue();
     */
    /* JADX WARN: Code restructure failed: missing block: B:68:0x0157, code lost:
        if (r13.mUserState.indexOfKey(r8) >= 0) goto L54;
     */
    /* JADX WARN: Code restructure failed: missing block: B:69:0x0159, code lost:
        r5.println("the specified user does not exist.");
     */
    /* JADX WARN: Code restructure failed: missing block: B:71:0x015f, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:72:0x0160, code lost:
        r13.mUserState.get(r8).dumpDatabaseInfo(r5);
     */
    /* JADX WARN: Code restructure failed: missing block: B:74:0x016c, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:76:0x016e, code lost:
        r5.println("invalid user specified.");
     */
    /* JADX WARN: Code restructure failed: missing block: B:78:0x0174, code lost:
        return;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    void dump(java.lang.String[] r14, java.io.PrintWriter r15) {
        /*
            Method dump skipped, instructions count: 496
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usage.UsageStatsService.dump(java.lang.String[], java.io.PrintWriter):void");
    }

    /* loaded from: classes2.dex */
    class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 0) {
                UsageStatsService.this.reportEvent((UsageEvents.Event) msg.obj, msg.arg1);
                return;
            }
            if (i == 1) {
                UsageStatsService.this.flushToDisk();
            } else if (i == 2) {
                UsageStatsService.this.onUserRemoved(msg.arg1);
            } else if (i != 3) {
                if (i == 4) {
                    UsageStatsService.this.reportEventToAllUserId((UsageEvents.Event) msg.obj);
                } else {
                    super.handleMessage(msg);
                }
            } else {
                int uid = msg.arg1;
                int procState = msg.arg2;
                int newCounter = procState <= 2 ? 0 : 1;
                synchronized (UsageStatsService.this.mUidToKernelCounter) {
                    int oldCounter = UsageStatsService.this.mUidToKernelCounter.get(uid, 0);
                    if (newCounter != oldCounter) {
                        UsageStatsService.this.mUidToKernelCounter.put(uid, newCounter);
                        try {
                            File file = UsageStatsService.KERNEL_COUNTER_FILE;
                            FileUtils.stringToFile(file, uid + " " + newCounter);
                        } catch (IOException e) {
                            Slog.w(UsageStatsService.TAG, "Failed to update counter set: " + e);
                        }
                    }
                }
            }
        }
    }

    /* loaded from: classes2.dex */
    private final class BinderService extends IUsageStatsManager.Stub {
        private BinderService() {
        }

        private boolean hasPermission(String callingPackage) {
            int callingUid = Binder.getCallingUid();
            if (callingUid == 1000) {
                return true;
            }
            int mode = UsageStatsService.this.mAppOps.noteOp(43, callingUid, callingPackage);
            return mode == 3 ? UsageStatsService.this.getContext().checkCallingPermission("android.permission.PACKAGE_USAGE_STATS") == 0 : mode == 0;
        }

        private boolean hasObserverPermission() {
            int callingUid = Binder.getCallingUid();
            DevicePolicyManagerInternal dpmInternal = UsageStatsService.this.getDpmInternal();
            return callingUid == 1000 || (dpmInternal != null && dpmInternal.isActiveAdminWithPolicy(callingUid, -1)) || UsageStatsService.this.getContext().checkCallingPermission("android.permission.OBSERVE_APP_USAGE") == 0;
        }

        private boolean hasPermissions(String callingPackage, String... permissions) {
            int callingUid = Binder.getCallingUid();
            if (callingUid == 1000) {
                return true;
            }
            boolean hasPermissions = true;
            Context context = UsageStatsService.this.getContext();
            for (String str : permissions) {
                hasPermissions = hasPermissions && context.checkCallingPermission(str) == 0;
            }
            return hasPermissions;
        }

        private void checkCallerIsSystemOrSameApp(String pkg) {
            if (isCallingUidSystem()) {
                return;
            }
            checkCallerIsSameApp(pkg);
        }

        private void checkCallerIsSameApp(String pkg) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = UserHandle.getUserId(callingUid);
            if (UsageStatsService.this.mPackageManagerInternal.getPackageUid(pkg, 0, callingUserId) != callingUid) {
                throw new SecurityException("Calling uid " + callingUid + " cannot query eventsfor package " + pkg);
            }
        }

        private boolean isCallingUidSystem() {
            int uid = UserHandle.getAppId(Binder.getCallingUid());
            return uid == 1000;
        }

        public ParceledListSlice<UsageStats> queryUsageStats(int bucketType, long beginTime, long endTime, String callingPackage) {
            if (hasPermission(callingPackage)) {
                boolean obfuscateInstantApps = UsageStatsService.this.shouldObfuscateInstantAppsForCaller(Binder.getCallingUid(), UserHandle.getCallingUserId());
                int userId = UserHandle.getCallingUserId();
                long token = Binder.clearCallingIdentity();
                try {
                    List<UsageStats> results = UsageStatsService.this.queryUsageStats(userId, bucketType, beginTime, endTime, obfuscateInstantApps);
                    if (results != null) {
                        return new ParceledListSlice<>(results);
                    }
                    return null;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return null;
        }

        public ParceledListSlice<ConfigurationStats> queryConfigurationStats(int bucketType, long beginTime, long endTime, String callingPackage) throws RemoteException {
            if (hasPermission(callingPackage)) {
                int userId = UserHandle.getCallingUserId();
                long token = Binder.clearCallingIdentity();
                try {
                    List<ConfigurationStats> results = UsageStatsService.this.queryConfigurationStats(userId, bucketType, beginTime, endTime);
                    if (results != null) {
                        return new ParceledListSlice<>(results);
                    }
                    return null;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return null;
        }

        public ParceledListSlice<EventStats> queryEventStats(int bucketType, long beginTime, long endTime, String callingPackage) throws RemoteException {
            if (hasPermission(callingPackage)) {
                int userId = UserHandle.getCallingUserId();
                long token = Binder.clearCallingIdentity();
                try {
                    List<EventStats> results = UsageStatsService.this.queryEventStats(userId, bucketType, beginTime, endTime);
                    if (results != null) {
                        return new ParceledListSlice<>(results);
                    }
                    return null;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return null;
        }

        public UsageEvents queryEvents(long beginTime, long endTime, String callingPackage) {
            if (hasPermission(callingPackage)) {
                boolean obfuscateInstantApps = UsageStatsService.this.shouldObfuscateInstantAppsForCaller(Binder.getCallingUid(), UserHandle.getCallingUserId());
                int userId = UserHandle.getCallingUserId();
                long token = Binder.clearCallingIdentity();
                try {
                    return UsageStatsService.this.queryEvents(userId, beginTime, endTime, obfuscateInstantApps);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return null;
        }

        public UsageEvents queryEventsForPackage(long beginTime, long endTime, String callingPackage) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = UserHandle.getUserId(callingUid);
            checkCallerIsSameApp(callingPackage);
            boolean includeTaskRoot = hasPermission(callingPackage);
            long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEventsForPackage(callingUserId, beginTime, endTime, callingPackage, includeTaskRoot);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public UsageEvents queryEventsForUser(long beginTime, long endTime, int userId, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }
            if (userId != UserHandle.getCallingUserId()) {
                UsageStatsService.this.getContext().enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "No permission to query usage stats for this user");
            }
            boolean obfuscateInstantApps = UsageStatsService.this.shouldObfuscateInstantAppsForCaller(Binder.getCallingUid(), UserHandle.getCallingUserId());
            long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEvents(userId, beginTime, endTime, obfuscateInstantApps);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public UsageEvents queryEventsForPackageForUser(long beginTime, long endTime, int userId, String pkg, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }
            if (userId != UserHandle.getCallingUserId()) {
                UsageStatsService.this.getContext().enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "No permission to query usage stats for this user");
            }
            checkCallerIsSystemOrSameApp(pkg);
            long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEventsForPackage(userId, beginTime, endTime, pkg, true);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public boolean isAppInactive(String packageName, int userId) {
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, "isAppInactive", (String) null);
                boolean obfuscateInstantApps = UsageStatsService.this.shouldObfuscateInstantAppsForCaller(Binder.getCallingUid(), userId2);
                long token = Binder.clearCallingIdentity();
                try {
                    return UsageStatsService.this.mAppStandby.isAppIdleFilteredOrParoled(packageName, userId2, SystemClock.elapsedRealtime(), obfuscateInstantApps);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        public void setAppInactive(String packageName, boolean idle, int userId) {
            int callingUid = Binder.getCallingUid();
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), callingUid, userId, false, true, "setAppInactive", (String) null);
                UsageStatsService.this.getContext().enforceCallingPermission("android.permission.CHANGE_APP_IDLE_STATE", "No permission to change app idle state");
                long token = Binder.clearCallingIdentity();
                try {
                    int appId = UsageStatsService.this.mAppStandby.getAppId(packageName);
                    if (appId < 0) {
                        return;
                    }
                    UsageStatsService.this.mAppStandby.setAppIdleAsync(packageName, idle, userId2);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        public int getAppStandbyBucket(String packageName, String callingPackage, int userId) {
            int callingUid = Binder.getCallingUid();
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), callingUid, userId, false, false, "getAppStandbyBucket", (String) null);
                int packageUid = UsageStatsService.this.mPackageManagerInternal.getPackageUid(packageName, 0, userId2);
                if (packageUid != callingUid && !hasPermission(callingPackage)) {
                    throw new SecurityException("Don't have permission to query app standby bucket");
                }
                if (packageUid >= 0) {
                    boolean obfuscateInstantApps = UsageStatsService.this.shouldObfuscateInstantAppsForCaller(callingUid, userId2);
                    long token = Binder.clearCallingIdentity();
                    try {
                        return UsageStatsService.this.mAppStandby.getAppStandbyBucket(packageName, userId2, SystemClock.elapsedRealtime(), obfuscateInstantApps);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                throw new IllegalArgumentException("Cannot get standby bucket for non existent package (" + packageName + ")");
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        public void setAppStandbyBucket(String packageName, int bucket, int userId) {
            int reason;
            UsageStatsService.this.getContext().enforceCallingPermission("android.permission.CHANGE_APP_IDLE_STATE", "No permission to change app standby state");
            if (bucket < 10 || bucket > 50) {
                throw new IllegalArgumentException("Cannot set the standby bucket to " + bucket);
            }
            int callingUid = Binder.getCallingUid();
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), callingUid, userId, false, true, "setAppStandbyBucket", (String) null);
                boolean shellCaller = callingUid == 0 || callingUid == 2000;
                boolean systemCaller = UserHandle.isCore(callingUid);
                if (systemCaller) {
                    reason = 1024;
                } else {
                    reason = 1280;
                }
                long token = Binder.clearCallingIdentity();
                try {
                    int packageUid = UsageStatsService.this.mPackageManagerInternal.getPackageUid(packageName, 4980736, userId2);
                    if (packageUid == callingUid) {
                        throw new IllegalArgumentException("Cannot set your own standby bucket");
                    }
                    if (packageUid < 0) {
                        throw new IllegalArgumentException("Cannot set standby bucket for non existent package (" + packageName + ")");
                    }
                    UsageStatsService.this.mAppStandby.setAppStandbyBucket(packageName, userId2, bucket, reason, SystemClock.elapsedRealtime(), shellCaller);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        public ParceledListSlice<AppStandbyInfo> getAppStandbyBuckets(String callingPackageName, int userId) {
            int callingUid = Binder.getCallingUid();
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), callingUid, userId, false, false, "getAppStandbyBucket", (String) null);
                if (!hasPermission(callingPackageName)) {
                    throw new SecurityException("Don't have permission to query app standby bucket");
                }
                long token = Binder.clearCallingIdentity();
                try {
                    List<AppStandbyInfo> standbyBucketList = UsageStatsService.this.mAppStandby.getAppStandbyBuckets(userId2);
                    return standbyBucketList == null ? ParceledListSlice.emptyList() : new ParceledListSlice<>(standbyBucketList);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        public void setAppStandbyBuckets(ParceledListSlice appBuckets, int userId) {
            int reason;
            UsageStatsService.this.getContext().enforceCallingPermission("android.permission.CHANGE_APP_IDLE_STATE", "No permission to change app standby state");
            int callingUid = Binder.getCallingUid();
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), callingUid, userId, false, true, "setAppStandbyBucket", (String) null);
                boolean shellCaller = callingUid == 0 || callingUid == 2000;
                if (shellCaller) {
                    reason = 1024;
                } else {
                    reason = 1280;
                }
                long token = Binder.clearCallingIdentity();
                try {
                    long elapsedRealtime = SystemClock.elapsedRealtime();
                    List<AppStandbyInfo> bucketList = appBuckets.getList();
                    for (AppStandbyInfo bucketInfo : bucketList) {
                        String packageName = bucketInfo.mPackageName;
                        int bucket = bucketInfo.mStandbyBucket;
                        if (bucket < 10 || bucket > 50) {
                            throw new IllegalArgumentException("Cannot set the standby bucket to " + bucket);
                        } else if (UsageStatsService.this.mPackageManagerInternal.getPackageUid(packageName, (int) DumpState.DUMP_CHANGES, userId2) == callingUid) {
                            throw new IllegalArgumentException("Cannot set your own standby bucket");
                        } else {
                            UsageStatsService.this.mAppStandby.setAppStandbyBucket(packageName, userId2, bucket, reason, elapsedRealtime, shellCaller);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        public void whitelistAppTemporarily(String packageName, long duration, int userId) throws RemoteException {
            StringBuilder reason = new StringBuilder(32);
            reason.append("from:");
            UserHandle.formatUid(reason, Binder.getCallingUid());
            UsageStatsService.this.mDeviceIdleController.addPowerSaveTempWhitelistApp(packageName, duration, userId, reason.toString());
        }

        public void onCarrierPrivilegedAppsChanged() {
            UsageStatsService.this.getContext().enforceCallingOrSelfPermission("android.permission.BIND_CARRIER_SERVICES", "onCarrierPrivilegedAppsChanged can only be called by privileged apps.");
            UsageStatsService.this.mAppStandby.clearCarrierPrivilegedApps();
        }

        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (DumpUtils.checkDumpAndUsageStatsPermission(UsageStatsService.this.getContext(), UsageStatsService.TAG, pw)) {
                UsageStatsService.this.dump(args, pw);
            }
        }

        public void reportChooserSelection(String packageName, int userId, String contentType, String[] annotations, String action) {
            if (packageName == null) {
                Slog.w(UsageStatsService.TAG, "Event report user selecting a null package");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event(9, SystemClock.elapsedRealtime());
            event.mPackage = packageName;
            event.mAction = action;
            event.mContentType = contentType;
            event.mContentAnnotations = annotations;
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void registerAppUsageObserver(int observerId, String[] packages, long timeLimitMs, PendingIntent callbackIntent, String callingPackage) {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }
            if (packages == null || packages.length == 0) {
                throw new IllegalArgumentException("Must specify at least one package");
            }
            if (callbackIntent == null) {
                throw new NullPointerException("callbackIntent can't be null");
            }
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.registerAppUsageObserver(callingUid, observerId, packages, timeLimitMs, callbackIntent, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void unregisterAppUsageObserver(int observerId, String callingPackage) {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.unregisterAppUsageObserver(callingUid, observerId, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void registerUsageSessionObserver(int sessionObserverId, String[] observed, long timeLimitMs, long sessionThresholdTimeMs, PendingIntent limitReachedCallbackIntent, PendingIntent sessionEndCallbackIntent, String callingPackage) {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }
            if (observed == null || observed.length == 0) {
                throw new IllegalArgumentException("Must specify at least one observed entity");
            }
            if (limitReachedCallbackIntent == null) {
                throw new NullPointerException("limitReachedCallbackIntent can't be null");
            }
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.registerUsageSessionObserver(callingUid, sessionObserverId, observed, timeLimitMs, sessionThresholdTimeMs, limitReachedCallbackIntent, sessionEndCallbackIntent, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void unregisterUsageSessionObserver(int sessionObserverId, String callingPackage) {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.unregisterUsageSessionObserver(callingUid, sessionObserverId, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void registerAppUsageLimitObserver(int observerId, String[] packages, long timeLimitMs, long timeUsedMs, PendingIntent callbackIntent, String callingPackage) {
            if (!hasPermissions(callingPackage, "android.permission.SUSPEND_APPS", "android.permission.OBSERVE_APP_USAGE")) {
                throw new SecurityException("Caller doesn't have both SUSPEND_APPS and OBSERVE_APP_USAGE permissions");
            }
            if (packages == null || packages.length == 0) {
                throw new IllegalArgumentException("Must specify at least one package");
            }
            if (callbackIntent == null && timeUsedMs < timeLimitMs) {
                throw new NullPointerException("callbackIntent can't be null");
            }
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.registerAppUsageLimitObserver(callingUid, observerId, packages, timeLimitMs, timeUsedMs, callbackIntent, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void unregisterAppUsageLimitObserver(int observerId, String callingPackage) {
            if (!hasPermissions(callingPackage, "android.permission.SUSPEND_APPS", "android.permission.OBSERVE_APP_USAGE")) {
                throw new SecurityException("Caller doesn't have both SUSPEND_APPS and OBSERVE_APP_USAGE permissions");
            }
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.unregisterAppUsageLimitObserver(callingUid, observerId, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void reportUsageStart(IBinder activity, String token, String callingPackage) {
            reportPastUsageStart(activity, token, 0L, callingPackage);
        }

        public void reportPastUsageStart(IBinder activity, String token, long timeAgoMs, String callingPackage) {
            ArraySet<String> tokens;
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long binderToken = Binder.clearCallingIdentity();
            try {
                synchronized (UsageStatsService.this.mUsageReporters) {
                    tokens = UsageStatsService.this.mUsageReporters.get(activity.hashCode());
                    if (tokens == null) {
                        tokens = new ArraySet<>();
                        UsageStatsService.this.mUsageReporters.put(activity.hashCode(), tokens);
                    }
                }
                synchronized (tokens) {
                    if (!tokens.add(token)) {
                        throw new IllegalArgumentException(token + " for " + callingPackage + " is already reported as started for this activity");
                    }
                }
                UsageStatsService.this.mAppTimeLimit.noteUsageStart(UsageStatsService.this.buildFullToken(callingPackage, token), userId, timeAgoMs);
            } finally {
                Binder.restoreCallingIdentity(binderToken);
            }
        }

        public void reportUsageStop(IBinder activity, String token, String callingPackage) {
            ArraySet<String> tokens;
            int callingUid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(callingUid);
            long binderToken = Binder.clearCallingIdentity();
            try {
                synchronized (UsageStatsService.this.mUsageReporters) {
                    tokens = UsageStatsService.this.mUsageReporters.get(activity.hashCode());
                    if (tokens == null) {
                        throw new IllegalArgumentException("Unknown reporter trying to stop token " + token + " for " + callingPackage);
                    }
                }
                synchronized (tokens) {
                    if (!tokens.remove(token)) {
                        throw new IllegalArgumentException(token + " for " + callingPackage + " is already reported as stopped for this activity");
                    }
                }
                UsageStatsService.this.mAppTimeLimit.noteUsageStop(UsageStatsService.this.buildFullToken(callingPackage, token), userId);
            } finally {
                Binder.restoreCallingIdentity(binderToken);
            }
        }

        public int getUsageSource() {
            int i;
            if (hasObserverPermission()) {
                synchronized (UsageStatsService.this.mLock) {
                    i = UsageStatsService.this.mUsageSource;
                }
                return i;
            }
            throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
        }

        public void forceUsageSourceSettingRead() {
            UsageStatsService.this.readUsageSourceSetting();
        }
    }

    void registerAppUsageObserver(int callingUid, int observerId, String[] packages, long timeLimitMs, PendingIntent callbackIntent, int userId) {
        this.mAppTimeLimit.addAppUsageObserver(callingUid, observerId, packages, timeLimitMs, callbackIntent, userId);
    }

    void unregisterAppUsageObserver(int callingUid, int observerId, int userId) {
        this.mAppTimeLimit.removeAppUsageObserver(callingUid, observerId, userId);
    }

    void registerUsageSessionObserver(int callingUid, int observerId, String[] observed, long timeLimitMs, long sessionThresholdTime, PendingIntent limitReachedCallbackIntent, PendingIntent sessionEndCallbackIntent, int userId) {
        this.mAppTimeLimit.addUsageSessionObserver(callingUid, observerId, observed, timeLimitMs, sessionThresholdTime, limitReachedCallbackIntent, sessionEndCallbackIntent, userId);
    }

    void unregisterUsageSessionObserver(int callingUid, int sessionObserverId, int userId) {
        this.mAppTimeLimit.removeUsageSessionObserver(callingUid, sessionObserverId, userId);
    }

    void registerAppUsageLimitObserver(int callingUid, int observerId, String[] packages, long timeLimitMs, long timeUsedMs, PendingIntent callbackIntent, int userId) {
        this.mAppTimeLimit.addAppUsageLimitObserver(callingUid, observerId, packages, timeLimitMs, timeUsedMs, callbackIntent, userId);
    }

    void unregisterAppUsageLimitObserver(int callingUid, int observerId, int userId) {
        this.mAppTimeLimit.removeAppUsageLimitObserver(callingUid, observerId, userId);
    }

    /* loaded from: classes2.dex */
    private final class LocalService extends UsageStatsManagerInternal {
        private LocalService() {
        }

        public void reportEvent(ComponentName component, int userId, int eventType, int instanceId, ComponentName taskRoot) {
            if (component == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a component name");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event(eventType, SystemClock.elapsedRealtime());
            event.mPackage = component.getPackageName();
            event.mClass = component.getClassName();
            event.mInstanceId = instanceId;
            if (taskRoot == null) {
                event.mTaskRootPackage = null;
                event.mTaskRootClass = null;
            } else {
                event.mTaskRootPackage = taskRoot.getPackageName();
                event.mTaskRootClass = taskRoot.getClassName();
            }
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportEvent(String packageName, int userId, int eventType) {
            if (packageName == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a package name, eventType:" + eventType);
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event(eventType, SystemClock.elapsedRealtime());
            event.mPackage = packageName;
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportConfigurationChange(Configuration config, int userId) {
            if (config == null) {
                Slog.w(UsageStatsService.TAG, "Configuration event reported with a null config");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event(5, SystemClock.elapsedRealtime());
            event.mPackage = PackageManagerService.PLATFORM_PACKAGE_NAME;
            event.mConfiguration = new Configuration(config);
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportInterruptiveNotification(String packageName, String channelId, int userId) {
            if (packageName == null || channelId == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a package name or a channel ID");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event(12, SystemClock.elapsedRealtime());
            event.mPackage = packageName.intern();
            event.mNotificationChannelId = channelId.intern();
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportShortcutUsage(String packageName, String shortcutId, int userId) {
            if (packageName == null || shortcutId == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a package name or a shortcut ID");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event(8, SystemClock.elapsedRealtime());
            event.mPackage = packageName.intern();
            event.mShortcutId = shortcutId.intern();
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportContentProviderUsage(String name, String packageName, int userId) {
            UsageStatsService.this.mAppStandby.postReportContentProviderUsage(name, packageName, userId);
        }

        public boolean isAppIdle(String packageName, int uidForAppId, int userId) {
            return UsageStatsService.this.mAppStandby.isAppIdleFiltered(packageName, uidForAppId, userId, SystemClock.elapsedRealtime());
        }

        public int getAppStandbyBucket(String packageName, int userId, long nowElapsed) {
            return UsageStatsService.this.mAppStandby.getAppStandbyBucket(packageName, userId, nowElapsed, false);
        }

        public int[] getIdleUidsForUser(int userId) {
            return UsageStatsService.this.mAppStandby.getIdleUidsForUser(userId);
        }

        public boolean isAppIdleParoleOn() {
            return UsageStatsService.this.mAppStandby.isParoledOrCharging();
        }

        public void prepareShutdown() {
            UsageStatsService.this.shutdown();
        }

        public void prepareForPossibleShutdown() {
            UsageStatsService.this.prepareForPossibleShutdown();
        }

        public void addAppIdleStateChangeListener(UsageStatsManagerInternal.AppIdleStateChangeListener listener) {
            UsageStatsService.this.mAppStandby.addListener(listener);
            listener.onParoleStateChanged(isAppIdleParoleOn());
        }

        public void removeAppIdleStateChangeListener(UsageStatsManagerInternal.AppIdleStateChangeListener listener) {
            UsageStatsService.this.mAppStandby.removeListener(listener);
        }

        public byte[] getBackupPayload(int user, String key) {
            synchronized (UsageStatsService.this.mLock) {
                if (user == 0) {
                    UserUsageStatsService userStats = UsageStatsService.this.getUserDataAndInitializeIfNeededLocked(user, UsageStatsService.this.checkAndGetTimeLocked());
                    return userStats.getBackupPayload(key);
                }
                return null;
            }
        }

        public void applyRestoredPayload(int user, String key, byte[] payload) {
            synchronized (UsageStatsService.this.mLock) {
                if (user == 0) {
                    UserUsageStatsService userStats = UsageStatsService.this.getUserDataAndInitializeIfNeededLocked(user, UsageStatsService.this.checkAndGetTimeLocked());
                    userStats.applyRestoredPayload(key, payload);
                }
            }
        }

        public List<UsageStats> queryUsageStatsForUser(int userId, int intervalType, long beginTime, long endTime, boolean obfuscateInstantApps) {
            return UsageStatsService.this.queryUsageStats(userId, intervalType, beginTime, endTime, obfuscateInstantApps);
        }

        public void setLastJobRunTime(String packageName, int userId, long elapsedRealtime) {
            UsageStatsService.this.mAppStandby.setLastJobRunTime(packageName, userId, elapsedRealtime);
        }

        public long getTimeSinceLastJobRun(String packageName, int userId) {
            return UsageStatsService.this.mAppStandby.getTimeSinceLastJobRun(packageName, userId);
        }

        public void reportAppJobState(String packageName, int userId, int numDeferredJobs, long timeSinceLastJobRun) {
        }

        public void onActiveAdminAdded(String packageName, int userId) {
            UsageStatsService.this.mAppStandby.addActiveDeviceAdmin(packageName, userId);
        }

        public void setActiveAdminApps(Set<String> packageNames, int userId) {
            UsageStatsService.this.mAppStandby.setActiveAdminApps(packageNames, userId);
        }

        public void onAdminDataAvailable() {
            UsageStatsService.this.mAppStandby.onAdminDataAvailable();
        }

        public void reportSyncScheduled(String packageName, int userId, boolean exempted) {
            UsageStatsService.this.mAppStandby.postReportSyncScheduled(packageName, userId, exempted);
        }

        public void reportExemptedSyncStart(String packageName, int userId) {
            UsageStatsService.this.mAppStandby.postReportExemptedSyncStart(packageName, userId);
        }

        public UsageStatsManagerInternal.AppUsageLimitData getAppUsageLimit(String packageName, UserHandle user) {
            return UsageStatsService.this.mAppTimeLimit.getAppUsageLimit(packageName, user);
        }
    }
}
