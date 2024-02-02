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
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.usage.AppTimeLimitController;
import com.android.server.usage.UserUsageStatsService;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
/* loaded from: classes.dex */
public class UsageStatsService extends SystemService implements UserUsageStatsService.StatsUpdatedListener {
    static final boolean COMPRESS_TIME = false;
    static final boolean DEBUG = false;
    private static final boolean ENABLE_KERNEL_UPDATES = true;
    private static final long FLUSH_INTERVAL = 1200000;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_UID_STATE_CHANGED = 3;
    static final String TAG = "UsageStatsService";
    private static final long TEN_SECONDS = 10000;
    private static final long TIME_CHANGE_THRESHOLD_MILLIS = 2000;
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
    private File mUsageStatsDir;
    UserManager mUserManager;
    private final SparseArray<UserUsageStatsService> mUserState;
    public static final boolean ENABLE_TIME_CHANGE_CORRECTION = SystemProperties.getBoolean("persist.debug.time_correction", true);
    private static final File KERNEL_COUNTER_FILE = new File("/proc/uid_procstat/set");

    public UsageStatsService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mUserState = new SparseArray<>();
        this.mUidToKernelCounter = new SparseIntArray();
        this.mStandbyChangeListener = new UsageStatsManagerInternal.AppIdleStateChangeListener() { // from class: com.android.server.usage.UsageStatsService.1
            public void onAppIdleStateChanged(String packageName, int userId, boolean idle, int bucket, int reason) {
                UsageEvents.Event event = new UsageEvents.Event();
                event.mEventType = 11;
                event.mBucketAndReason = (bucket << 16) | (65535 & reason);
                event.mPackage = packageName;
                event.mTimeStamp = SystemClock.elapsedRealtime();
                UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
            }

            public void onParoleStateChanged(boolean isParoleOn) {
            }
        };
        this.mUidObserver = new IUidObserver.Stub() { // from class: com.android.server.usage.UsageStatsService.2
            public void onUidStateChanged(int uid, int procState, long procStateSeq) {
                UsageStatsService.this.mHandler.obtainMessage(3, uid, procState).sendToTarget();
            }

            public void onUidIdle(int uid, boolean disabled) {
            }

            public void onUidGone(int uid, boolean disabled) {
                onUidStateChanged(uid, 19, 0L);
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
        this.mAppTimeLimit = new AppTimeLimitController(new AppTimeLimitController.OnLimitReachedListener() { // from class: com.android.server.usage.-$$Lambda$UsageStatsService$VoLNrRDaTqGpWDfCW6NTYC92LRY
            @Override // com.android.server.usage.AppTimeLimitController.OnLimitReachedListener
            public final void onLimitReached(int i, int i2, long j, long j2, PendingIntent pendingIntent) {
                UsageStatsService.lambda$onStart$0(UsageStatsService.this, i, i2, j, j2, pendingIntent);
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

    public static /* synthetic */ void lambda$onStart$0(UsageStatsService usageStatsService, int observerId, int userId, long timeLimit, long timeElapsed, PendingIntent callbackIntent) {
        Intent intent = new Intent();
        intent.putExtra("android.app.usage.extra.OBSERVER_ID", observerId);
        intent.putExtra("android.app.usage.extra.TIME_LIMIT", timeLimit);
        intent.putExtra("android.app.usage.extra.TIME_USED", timeElapsed);
        try {
            callbackIntent.send(usageStatsService.getContext(), 0, intent);
        } catch (PendingIntent.CanceledException e) {
            Slog.w(TAG, "Couldn't deliver callback: " + callbackIntent);
        }
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            this.mAppStandby.onBootPhase(phase);
            getDpmInternal();
            this.mDeviceIdleController = IDeviceIdleController.Stub.asInterface(ServiceManager.getService("deviceidle"));
            if (KERNEL_COUNTER_FILE.exists()) {
                try {
                    ActivityManager.getService().registerUidObserver(this.mUidObserver, 3, -1, (String) null);
                    return;
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            Slog.w(TAG, "Missing procfs interface: " + KERNEL_COUNTER_FILE);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public DevicePolicyManagerInternal getDpmInternal() {
        if (this.mDpmInternal == null) {
            this.mDpmInternal = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class);
        }
        return this.mDpmInternal;
    }

    /* loaded from: classes.dex */
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
            flushToDiskLocked();
        }
    }

    void reportEvent(UsageEvents.Event event, int userId) {
        synchronized (this.mLock) {
            long timeNow = checkAndGetTimeLocked();
            long elapsedRealtime = SystemClock.elapsedRealtime();
            convertToSystemTimeLocked(event);
            if (event.getPackageName() != null && this.mPackageManagerInternal.isPackageEphemeral(userId, event.getPackageName())) {
                event.mFlags |= 1;
            }
            UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            service.reportEvent(event);
            this.mAppStandby.reportEvent(event, elapsedRealtime, userId);
            switch (event.mEventType) {
                case 1:
                    this.mAppTimeLimit.moveToForeground(event.getPackageName(), event.getClassName(), userId);
                    break;
                case 2:
                    this.mAppTimeLimit.moveToBackground(event.getPackageName(), event.getClassName(), userId);
                    break;
            }
        }
    }

    void flushToDisk() {
        synchronized (this.mLock) {
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

    UsageEvents queryEventsForPackage(int userId, long beginTime, long endTime, String packageName) {
        synchronized (this.mLock) {
            long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }
            UserUsageStatsService service = getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEventsForPackage(beginTime, endTime, packageName);
        }
    }

    private static boolean validRange(long currentTime, long beginTime, long endTime) {
        return beginTime <= currentTime && beginTime < endTime;
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

    void dump(String[] args, PrintWriter pw) {
        boolean compact;
        boolean checkin;
        synchronized (this.mLock) {
            IndentingPrintWriter idpw = new IndentingPrintWriter(pw, "  ");
            String pkg = null;
            if (args != null) {
                compact = false;
                checkin = false;
                int i = 0;
                while (true) {
                    if (i >= args.length) {
                        break;
                    }
                    String arg = args[i];
                    if ("--checkin".equals(arg)) {
                        checkin = true;
                    } else if ("-c".equals(arg)) {
                        compact = true;
                    } else if ("flush".equals(arg)) {
                        flushToDiskLocked();
                        pw.println("Flushed stats to disk");
                        return;
                    } else if ("is-app-standby-enabled".equals(arg)) {
                        pw.println(this.mAppStandby.mAppIdleEnabled);
                        return;
                    } else if (arg != null && !arg.startsWith("-")) {
                        pkg = arg;
                        break;
                    }
                    i++;
                }
            } else {
                compact = false;
                checkin = false;
            }
            int userCount = this.mUserState.size();
            for (int i2 = 0; i2 < userCount; i2++) {
                int userId = this.mUserState.keyAt(i2);
                idpw.printPair("user", Integer.valueOf(userId));
                idpw.println();
                idpw.increaseIndent();
                if (checkin) {
                    this.mUserState.valueAt(i2).checkin(idpw);
                } else {
                    this.mUserState.valueAt(i2).dump(idpw, pkg, compact);
                    idpw.println();
                }
                this.mAppStandby.dumpUser(idpw, userId, pkg);
                idpw.decreaseIndent();
            }
            if (pkg == null) {
                pw.println();
                this.mAppStandby.dumpState(args, pw);
            }
            this.mAppTimeLimit.dump(pw);
        }
    }

    /* loaded from: classes.dex */
    class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    UsageStatsService.this.reportEvent((UsageEvents.Event) msg.obj, msg.arg1);
                    return;
                case 1:
                    UsageStatsService.this.flushToDisk();
                    return;
                case 2:
                    UsageStatsService.this.onUserRemoved(msg.arg1);
                    return;
                case 3:
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
                    return;
                default:
                    super.handleMessage(msg);
                    return;
            }
        }
    }

    /* loaded from: classes.dex */
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

        private boolean hasObserverPermission(String callingPackage) {
            int callingUid = Binder.getCallingUid();
            DevicePolicyManagerInternal dpmInternal = UsageStatsService.this.getDpmInternal();
            return callingUid == 1000 || (dpmInternal != null && dpmInternal.isActiveAdminWithPolicy(callingUid, -1)) || UsageStatsService.this.getContext().checkCallingPermission("android.permission.OBSERVE_APP_USAGE") == 0;
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
                throw new SecurityException("Calling uid " + pkg + " cannot query eventsfor package " + pkg);
            }
        }

        private boolean isCallingUidSystem() {
            int uid = Binder.getCallingUid();
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
            long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEventsForPackage(callingUserId, beginTime, endTime, callingPackage);
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
                return UsageStatsService.this.queryEventsForPackage(userId, beginTime, endTime, callingPackage);
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

        /* JADX WARN: Multi-variable type inference failed */
        /* JADX WARN: Type inference failed for: r12v0, types: [int] */
        /* JADX WARN: Type inference failed for: r12v1 */
        public void setAppStandbyBucket(String packageName, int bucket, int userId) {
            UsageStatsService.this.getContext().enforceCallingPermission("android.permission.CHANGE_APP_IDLE_STATE", "No permission to change app standby state");
            if (bucket < 10 || bucket > 50) {
                throw new IllegalArgumentException("Cannot set the standby bucket to " + bucket);
            }
            long token = Binder.getCallingUid();
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), (int) token, userId, false, true, "setAppStandbyBucket", (String) null);
                boolean shellCaller = token == 0 || token == 2000;
                boolean systemCaller = UserHandle.isCore(token);
                int reason = systemCaller ? 1024 : 1280;
                long token2 = Binder.clearCallingIdentity();
                try {
                    int packageUid = UsageStatsService.this.mPackageManagerInternal.getPackageUid(packageName, (int) DumpState.DUMP_CHANGES, userId2);
                    try {
                        if (packageUid == token) {
                            throw new IllegalArgumentException("Cannot set your own standby bucket");
                        }
                        if (packageUid >= 0) {
                            UsageStatsService.this.mAppStandby.setAppStandbyBucket(packageName, userId2, bucket, reason, SystemClock.elapsedRealtime(), shellCaller);
                            Binder.restoreCallingIdentity(token2);
                            return;
                        }
                        throw new IllegalArgumentException("Cannot set standby bucket for non existent package (" + packageName + ")");
                    } catch (Throwable th) {
                        th = th;
                        Binder.restoreCallingIdentity(token);
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                    token = token2;
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
            UsageStatsService.this.getContext().enforceCallingPermission("android.permission.CHANGE_APP_IDLE_STATE", "No permission to change app standby state");
            int callingUid = Binder.getCallingUid();
            try {
                int userId2 = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), callingUid, userId, false, true, "setAppStandbyBucket", (String) null);
                boolean shellCaller = callingUid == 0 || callingUid == 2000;
                int reason = shellCaller ? 1024 : 1280;
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
            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName;
            event.mTimeStamp = SystemClock.elapsedRealtime();
            event.mEventType = 9;
            event.mAction = action;
            event.mContentType = contentType;
            event.mContentAnnotations = annotations;
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void registerAppUsageObserver(int observerId, String[] packages, long timeLimitMs, PendingIntent callbackIntent, String callingPackage) {
            long token;
            if (!hasObserverPermission(callingPackage)) {
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
            long token2 = Binder.clearCallingIdentity();
            try {
                token = token2;
                try {
                    UsageStatsService.this.registerAppUsageObserver(callingUid, observerId, packages, timeLimitMs, callbackIntent, userId);
                    Binder.restoreCallingIdentity(token);
                } catch (Throwable th) {
                    th = th;
                    Binder.restoreCallingIdentity(token);
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                token = token2;
            }
        }

        public void unregisterAppUsageObserver(int observerId, String callingPackage) {
            if (!hasObserverPermission(callingPackage)) {
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
    }

    void registerAppUsageObserver(int callingUid, int observerId, String[] packages, long timeLimitMs, PendingIntent callbackIntent, int userId) {
        this.mAppTimeLimit.addObserver(callingUid, observerId, packages, timeLimitMs, callbackIntent, userId);
    }

    void unregisterAppUsageObserver(int callingUid, int observerId, int userId) {
        this.mAppTimeLimit.removeObserver(callingUid, observerId, userId);
    }

    /* loaded from: classes.dex */
    private final class LocalService extends UsageStatsManagerInternal {
        private LocalService() {
        }

        public void reportEvent(ComponentName component, int userId, int eventType) {
            if (component == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a component name");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = component.getPackageName();
            event.mClass = component.getClassName();
            event.mTimeStamp = SystemClock.elapsedRealtime();
            event.mEventType = eventType;
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportEvent(String packageName, int userId, int eventType) {
            if (packageName == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a package name");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName;
            event.mTimeStamp = SystemClock.elapsedRealtime();
            event.mEventType = eventType;
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportConfigurationChange(Configuration config, int userId) {
            if (config == null) {
                Slog.w(UsageStatsService.TAG, "Configuration event reported with a null config");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = PackageManagerService.PLATFORM_PACKAGE_NAME;
            event.mTimeStamp = SystemClock.elapsedRealtime();
            event.mEventType = 5;
            event.mConfiguration = new Configuration(config);
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportInterruptiveNotification(String packageName, String channelId, int userId) {
            if (packageName == null || channelId == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a package name or a channel ID");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName.intern();
            event.mNotificationChannelId = channelId.intern();
            event.mTimeStamp = SystemClock.elapsedRealtime();
            event.mEventType = 12;
            UsageStatsService.this.mHandler.obtainMessage(0, userId, 0, event).sendToTarget();
        }

        public void reportShortcutUsage(String packageName, String shortcutId, int userId) {
            if (packageName == null || shortcutId == null) {
                Slog.w(UsageStatsService.TAG, "Event reported without a package name or a shortcut ID");
                return;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName.intern();
            event.mShortcutId = shortcutId.intern();
            event.mTimeStamp = SystemClock.elapsedRealtime();
            event.mEventType = 8;
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

        public void addAppIdleStateChangeListener(UsageStatsManagerInternal.AppIdleStateChangeListener listener) {
            UsageStatsService.this.mAppStandby.addListener(listener);
            listener.onParoleStateChanged(isAppIdleParoleOn());
        }

        public void removeAppIdleStateChangeListener(UsageStatsManagerInternal.AppIdleStateChangeListener listener) {
            UsageStatsService.this.mAppStandby.removeListener(listener);
        }

        public byte[] getBackupPayload(int user, String key) {
            synchronized (UsageStatsService.this.mLock) {
                try {
                    if (user == 0) {
                        UserUsageStatsService userStats = UsageStatsService.this.getUserDataAndInitializeIfNeededLocked(user, UsageStatsService.this.checkAndGetTimeLocked());
                        return userStats.getBackupPayload(key);
                    }
                    return null;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }

        public void applyRestoredPayload(int user, String key, byte[] payload) {
            synchronized (UsageStatsService.this.mLock) {
                if (user == 0) {
                    try {
                        UserUsageStatsService userStats = UsageStatsService.this.getUserDataAndInitializeIfNeededLocked(user, UsageStatsService.this.checkAndGetTimeLocked());
                        userStats.applyRestoredPayload(key, payload);
                    } catch (Throwable th) {
                        throw th;
                    }
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

        public void reportExemptedSyncScheduled(String packageName, int userId) {
            UsageStatsService.this.mAppStandby.postReportExemptedSyncScheduled(packageName, userId);
        }

        public void reportExemptedSyncStart(String packageName, int userId) {
            UsageStatsService.this.mAppStandby.postReportExemptedSyncStart(packageName, userId);
        }
    }
}
