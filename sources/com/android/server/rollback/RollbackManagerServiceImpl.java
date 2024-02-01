package com.android.server.rollback;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.content.rollback.IRollbackManager;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.pm.DumpState;
import com.android.server.pm.Installer;
import com.android.server.rollback.RollbackManagerServiceImpl;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RollbackManagerServiceImpl extends IRollbackManager.Stub {
    private static final long DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS = TimeUnit.DAYS.toMillis(14);
    private static final long HANDLER_THREAD_TIMEOUT_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final String TAG = "RollbackManager";
    private final AppDataRollbackHelper mAppDataRollbackHelper;
    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Installer mInstaller;
    private final RollbackPackageHealthObserver mPackageHealthObserver;
    private final RollbackStore mRollbackStore;
    @GuardedBy({"mLock"})
    private List<RollbackData> mRollbacks;
    private final Object mLock = new Object();
    private long mRollbackLifetimeDurationInMillis = DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS;
    private final Random mRandom = new SecureRandom();
    @GuardedBy({"mLock"})
    private final SparseBooleanArray mAllocatedRollbackIds = new SparseBooleanArray();
    @GuardedBy({"mLock"})
    private final Set<NewRollback> mNewRollbacks = new ArraySet();
    private long mRelativeBootTime = calculateRelativeBootTime();

    static /* synthetic */ long access$900() {
        return calculateRelativeBootTime();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RollbackManagerServiceImpl(Context context) {
        this.mContext = context;
        this.mInstaller = new Installer(this.mContext);
        this.mInstaller.onStart();
        this.mHandlerThread = new HandlerThread("RollbackManagerServiceHandler");
        this.mHandlerThread.start();
        Watchdog.getInstance().addThread(getHandler(), HANDLER_THREAD_TIMEOUT_DURATION_MILLIS);
        this.mRollbackStore = new RollbackStore(new File(Environment.getDataDirectory(), "rollback"));
        this.mPackageHealthObserver = new RollbackPackageHealthObserver(this.mContext);
        this.mAppDataRollbackHelper = new AppDataRollbackHelper(this.mInstaller);
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$2_NDf9EpLcTKkJVpkadZhudKips
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$new$0$RollbackManagerServiceImpl();
            }
        });
        new SessionCallback(this, null);
        for (UserInfo userInfo : UserManager.get(this.mContext).getUsers(true)) {
            registerUserCallbacks(userInfo.getUserHandle());
        }
        IntentFilter enableRollbackFilter = new IntentFilter();
        enableRollbackFilter.addAction("android.intent.action.PACKAGE_ENABLE_ROLLBACK");
        try {
            enableRollbackFilter.addDataType("application/vnd.android.package-archive");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Log.e(TAG, "addDataType", e);
        }
        this.mContext.registerReceiver(new AnonymousClass1(), enableRollbackFilter, null, getHandler());
        IntentFilter enableRollbackTimedOutFilter = new IntentFilter();
        enableRollbackTimedOutFilter.addAction("android.intent.action.CANCEL_ENABLE_ROLLBACK");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.rollback.RollbackManagerServiceImpl.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.CANCEL_ENABLE_ROLLBACK".equals(intent.getAction())) {
                    int token = intent.getIntExtra("android.content.pm.extra.ENABLE_ROLLBACK_TOKEN", -1);
                    synchronized (RollbackManagerServiceImpl.this.mLock) {
                        for (NewRollback rollback : RollbackManagerServiceImpl.this.mNewRollbacks) {
                            if (rollback.hasToken(token)) {
                                rollback.isCancelled = true;
                                return;
                            }
                        }
                    }
                }
            }
        }, enableRollbackTimedOutFilter, null, getHandler());
        IntentFilter userAddedIntentFilter = new IntentFilter("android.intent.action.USER_ADDED");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.rollback.RollbackManagerServiceImpl.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                int newUserId;
                if ("android.intent.action.USER_ADDED".equals(intent.getAction()) && (newUserId = intent.getIntExtra("android.intent.extra.user_handle", -1)) != -1) {
                    RollbackManagerServiceImpl.this.registerUserCallbacks(UserHandle.of(newUserId));
                }
            }
        }, userAddedIntentFilter, null, getHandler());
        registerTimeChangeReceiver();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.rollback.RollbackManagerServiceImpl$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 extends BroadcastReceiver {
        AnonymousClass1() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if ("android.intent.action.PACKAGE_ENABLE_ROLLBACK".equals(intent.getAction())) {
                final int token = intent.getIntExtra("android.content.pm.extra.ENABLE_ROLLBACK_TOKEN", -1);
                final int installFlags = intent.getIntExtra("android.content.pm.extra.ENABLE_ROLLBACK_INSTALL_FLAGS", 0);
                final int[] installedUsers = intent.getIntArrayExtra("android.content.pm.extra.ENABLE_ROLLBACK_INSTALLED_USERS");
                final int user = intent.getIntExtra("android.content.pm.extra.ENABLE_ROLLBACK_USER", 0);
                final File newPackageCodePath = new File(intent.getData().getPath());
                RollbackManagerServiceImpl.this.getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$1$TqXV32QQcmn2m-AeooJgWwLsvfE
                    @Override // java.lang.Runnable
                    public final void run() {
                        RollbackManagerServiceImpl.AnonymousClass1.this.lambda$onReceive$0$RollbackManagerServiceImpl$1(installFlags, newPackageCodePath, installedUsers, user, token);
                    }
                });
                abortBroadcast();
            }
        }

        public /* synthetic */ void lambda$onReceive$0$RollbackManagerServiceImpl$1(int installFlags, File newPackageCodePath, int[] installedUsers, int user, int token) {
            boolean success = RollbackManagerServiceImpl.this.enableRollback(installFlags, newPackageCodePath, installedUsers, user, token);
            int ret = 1;
            if (!success) {
                ret = -1;
            }
            PackageManagerInternal pm = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
            pm.setEnableRollbackCode(token, ret);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void registerUserCallbacks(UserHandle user) {
        Context context = getContextAsUser(user);
        if (context == null) {
            Log.e(TAG, "Unable to register user callbacks for user " + user);
            return;
        }
        context.getPackageManager().getPackageInstaller().registerSessionCallback(new SessionCallback(this, null), getHandler());
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_REPLACED");
        filter.addAction("android.intent.action.PACKAGE_FULLY_REMOVED");
        filter.addDataScheme("package");
        context.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.rollback.RollbackManagerServiceImpl.4
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                String action = intent.getAction();
                if ("android.intent.action.PACKAGE_REPLACED".equals(action)) {
                    String packageName = intent.getData().getSchemeSpecificPart();
                    RollbackManagerServiceImpl.this.onPackageReplaced(packageName);
                }
                if ("android.intent.action.PACKAGE_FULLY_REMOVED".equals(action)) {
                    String packageName2 = intent.getData().getSchemeSpecificPart();
                    RollbackManagerServiceImpl.this.onPackageFullyRemoved(packageName2);
                }
            }
        }, filter, null, getHandler());
    }

    public ParceledListSlice getAvailableRollbacks() {
        ParceledListSlice parceledListSlice;
        enforceManageRollbacks("getAvailableRollbacks");
        if (Thread.currentThread().equals(this.mHandlerThread)) {
            Log.wtf(TAG, "Calling getAvailableRollbacks from mHandlerThread causes a deadlock");
            throw new IllegalStateException("Cannot call RollbackManager#getAvailableRollbacks from the handler thread!");
        }
        final LinkedBlockingQueue<Boolean> result = new LinkedBlockingQueue<>();
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$oLwS8G_DUyKmAeNKhLfFpV3VJTA
            @Override // java.lang.Runnable
            public final void run() {
                result.offer(true);
            }
        });
        try {
            result.take();
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted while waiting for handler thread in getAvailableRollbacks");
        }
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < this.mRollbacks.size(); i++) {
                RollbackData data = this.mRollbacks.get(i);
                if (data.state == 1) {
                    rollbacks.add(data.info);
                }
            }
            parceledListSlice = new ParceledListSlice(rollbacks);
        }
        return parceledListSlice;
    }

    public ParceledListSlice<RollbackInfo> getRecentlyExecutedRollbacks() {
        ParceledListSlice<RollbackInfo> parceledListSlice;
        enforceManageRollbacks("getRecentlyCommittedRollbacks");
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < this.mRollbacks.size(); i++) {
                RollbackData data = this.mRollbacks.get(i);
                if (data.state == 3) {
                    rollbacks.add(data.info);
                }
            }
            parceledListSlice = new ParceledListSlice<>(rollbacks);
        }
        return parceledListSlice;
    }

    public void commitRollback(final int rollbackId, final ParceledListSlice causePackages, final String callerPackageName, final IntentSender statusReceiver) {
        enforceManageRollbacks("executeRollback");
        int callingUid = Binder.getCallingUid();
        AppOpsManager appOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        appOps.checkPackage(callingUid, callerPackageName);
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$aG_9_cawiXbCo0CF-5aX0ns2oy8
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$commitRollback$2$RollbackManagerServiceImpl(rollbackId, causePackages, callerPackageName, statusReceiver);
            }
        });
    }

    public /* synthetic */ void lambda$commitRollback$2$RollbackManagerServiceImpl(int rollbackId, ParceledListSlice causePackages, String callerPackageName, IntentSender statusReceiver) {
        commitRollbackInternal(rollbackId, causePackages.getList(), callerPackageName, statusReceiver);
    }

    private void registerTimeChangeReceiver() {
        BroadcastReceiver timeChangeIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.rollback.RollbackManagerServiceImpl.5
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                long oldRelativeBootTime = RollbackManagerServiceImpl.this.mRelativeBootTime;
                RollbackManagerServiceImpl.this.mRelativeBootTime = RollbackManagerServiceImpl.access$900();
                long timeDifference = RollbackManagerServiceImpl.this.mRelativeBootTime - oldRelativeBootTime;
                synchronized (RollbackManagerServiceImpl.this.mLock) {
                    RollbackManagerServiceImpl.this.ensureRollbackDataLoadedLocked();
                    for (RollbackData data : RollbackManagerServiceImpl.this.mRollbacks) {
                        data.timestamp = data.timestamp.plusMillis(timeDifference);
                        RollbackManagerServiceImpl.this.saveRollbackData(data);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.TIME_SET");
        this.mContext.registerReceiver(timeChangeIntentReceiver, filter, null, getHandler());
    }

    private static long calculateRelativeBootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /* JADX WARN: Type inference failed for: r10v0 */
    /* JADX WARN: Type inference failed for: r10v1, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r10v6 */
    private void commitRollbackInternal(int rollbackId, final List<VersionedPackage> causePackages, String callerPackageName, final IntentSender statusReceiver) {
        String installerPackageName;
        Log.i(TAG, "Initiating rollback");
        final RollbackData data = getRollbackForId(rollbackId);
        if (data != null) {
            ?? r10 = 1;
            if (data.state == 1) {
                try {
                    int i = 0;
                    Context context = this.mContext.createPackageContext(callerPackageName, 0);
                    PackageManager pm = context.getPackageManager();
                    try {
                        PackageInstaller packageInstaller = pm.getPackageInstaller();
                        PackageInstaller.SessionParams parentParams = new PackageInstaller.SessionParams(1);
                        parentParams.setRequestDowngrade(true);
                        parentParams.setMultiPackage();
                        if (data.isStaged()) {
                            parentParams.setStaged();
                        }
                        final int parentSessionId = packageInstaller.createSession(parentParams);
                        PackageInstaller.Session parentSession = packageInstaller.openSession(parentSessionId);
                        Iterator it = data.info.getPackages().iterator();
                        while (it.hasNext()) {
                            PackageRollbackInfo info = (PackageRollbackInfo) it.next();
                            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(r10);
                            if (!info.isApex() && (installerPackageName = pm.getInstallerPackageName(info.getPackageName())) != null) {
                                params.setInstallerPackageName(installerPackageName);
                            }
                            params.setRequestDowngrade(r10);
                            params.setRequiredInstalledVersionCode(info.getVersionRolledBackFrom().getLongVersionCode());
                            if (data.isStaged()) {
                                params.setStaged();
                            }
                            if (info.isApex()) {
                                params.setInstallAsApex();
                            }
                            int sessionId = packageInstaller.createSession(params);
                            PackageInstaller.Session session = packageInstaller.openSession(sessionId);
                            File[] packageCodePaths = RollbackStore.getPackageCodePaths(data, info.getPackageName());
                            if (packageCodePaths == null) {
                                sendFailure(statusReceiver, 1, "Backup copy of package inaccessible");
                                return;
                            }
                            int length = packageCodePaths.length;
                            while (i < length) {
                                File packageCodePath = packageCodePaths[i];
                                Iterator it2 = it;
                                PackageRollbackInfo info2 = info;
                                ParcelFileDescriptor fd = ParcelFileDescriptor.open(packageCodePath, 268435456);
                                long token = Binder.clearCallingIdentity();
                                try {
                                    session.write(packageCodePath.getName(), 0L, packageCodePath.length(), fd);
                                    if (fd != null) {
                                        fd.close();
                                    }
                                    i++;
                                    it = it2;
                                    info = info2;
                                } finally {
                                    Binder.restoreCallingIdentity(token);
                                }
                            }
                            parentSession.addChildSessionId(sessionId);
                            it = it;
                            i = 0;
                            r10 = 1;
                        }
                        LocalIntentReceiver receiver = new LocalIntentReceiver(new Consumer() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$oAkfsZ2q5BUu35KwHn4M46EMuGw
                            @Override // java.util.function.Consumer
                            public final void accept(Object obj) {
                                RollbackManagerServiceImpl.this.lambda$commitRollbackInternal$4$RollbackManagerServiceImpl(data, statusReceiver, parentSessionId, causePackages, (Intent) obj);
                            }
                        });
                        synchronized (this.mLock) {
                            data.state = 3;
                            data.restoreUserDataInProgress = true;
                        }
                        parentSession.commit(receiver.getIntentSender());
                        return;
                    } catch (IOException e) {
                        Log.e(TAG, "Rollback failed", e);
                        sendFailure(statusReceiver, 1, "IOException: " + e.toString());
                        return;
                    }
                } catch (PackageManager.NameNotFoundException e2) {
                    sendFailure(statusReceiver, 1, "Invalid callerPackageName");
                    return;
                }
            }
        }
        sendFailure(statusReceiver, 2, "Rollback unavailable");
    }

    public /* synthetic */ void lambda$commitRollbackInternal$4$RollbackManagerServiceImpl(final RollbackData data, final IntentSender statusReceiver, final int parentSessionId, final List causePackages, final Intent result) {
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$YVdIiq4-wvEBANvFTdY79W4LaS8
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$commitRollbackInternal$3$RollbackManagerServiceImpl(result, data, statusReceiver, parentSessionId, causePackages);
            }
        });
    }

    public /* synthetic */ void lambda$commitRollbackInternal$3$RollbackManagerServiceImpl(Intent result, RollbackData data, IntentSender statusReceiver, int parentSessionId, List causePackages) {
        int status = result.getIntExtra("android.content.pm.extra.STATUS", 1);
        if (status != 0) {
            synchronized (this.mLock) {
                data.state = 1;
                data.restoreUserDataInProgress = false;
            }
            sendFailure(statusReceiver, 3, "Rollback downgrade install failed: " + result.getStringExtra("android.content.pm.extra.STATUS_MESSAGE"));
            return;
        }
        synchronized (this.mLock) {
            if (!data.isStaged()) {
                data.restoreUserDataInProgress = false;
            }
            data.info.setCommittedSessionId(parentSessionId);
            data.info.getCausePackages().addAll(causePackages);
        }
        RollbackStore rollbackStore = this.mRollbackStore;
        RollbackStore.deletePackageCodePaths(data);
        saveRollbackData(data);
        sendSuccess(statusReceiver);
        Intent broadcast = new Intent("android.intent.action.ROLLBACK_COMMITTED");
        this.mContext.sendBroadcastAsUser(broadcast, UserHandle.SYSTEM, "android.permission.MANAGE_ROLLBACKS");
    }

    public void reloadPersistedData() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.TEST_MANAGE_ROLLBACKS", "reloadPersistedData");
        synchronized (this.mLock) {
            this.mRollbacks = null;
        }
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$p7U0gtaH93R3VtUt6jx4Xkt2Avs
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$reloadPersistedData$5$RollbackManagerServiceImpl();
            }
        });
    }

    public /* synthetic */ void lambda$reloadPersistedData$5$RollbackManagerServiceImpl() {
        lambda$onBootCompleted$7$RollbackManagerServiceImpl();
        lambda$new$0$RollbackManagerServiceImpl();
    }

    public void expireRollbackForPackage(String packageName) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.TEST_MANAGE_ROLLBACKS", "expireRollbackForPackage");
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackData> iter = this.mRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                Iterator it = data.info.getPackages().iterator();
                while (true) {
                    if (it.hasNext()) {
                        PackageRollbackInfo info = (PackageRollbackInfo) it.next();
                        if (info.getPackageName().equals(packageName)) {
                            iter.remove();
                            deleteRollback(data);
                            break;
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUnlockUser(final int userId) {
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$5wr7eOUmDTfGrVye83nSq68E9AA
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$onUnlockUser$6$RollbackManagerServiceImpl(userId);
            }
        });
    }

    public /* synthetic */ void lambda$onUnlockUser$6$RollbackManagerServiceImpl(int userId) {
        List<RollbackData> rollbacks;
        synchronized (this.mLock) {
            rollbacks = new ArrayList<>(this.mRollbacks);
        }
        Set<RollbackData> changed = this.mAppDataRollbackHelper.commitPendingBackupAndRestoreForUser(userId, rollbacks);
        for (RollbackData rd : changed) {
            saveRollbackData(rd);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: updateRollbackLifetimeDurationInMillis */
    public void lambda$onBootCompleted$7$RollbackManagerServiceImpl() {
        this.mRollbackLifetimeDurationInMillis = DeviceConfig.getLong("rollback_boot", "rollback_lifetime_in_millis", DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS);
        if (this.mRollbackLifetimeDurationInMillis < 0) {
            this.mRollbackLifetimeDurationInMillis = DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onBootCompleted() {
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$pS5jbfXLgvSVqxzjSkJaMnydaOY
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$onBootCompleted$7$RollbackManagerServiceImpl();
            }
        });
        scheduleExpiration(0L);
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$V7__18jactj68mqbmRTGjsuUOik
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$onBootCompleted$8$RollbackManagerServiceImpl();
            }
        });
    }

    public /* synthetic */ void lambda$onBootCompleted$8$RollbackManagerServiceImpl() {
        List<RollbackData> enabling = new ArrayList<>();
        List<RollbackData> restoreInProgress = new ArrayList<>();
        Set<String> apexPackageNames = new HashSet<>();
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            for (RollbackData data : this.mRollbacks) {
                if (data.isStaged()) {
                    if (data.state == 0) {
                        enabling.add(data);
                    } else if (data.restoreUserDataInProgress) {
                        restoreInProgress.add(data);
                    }
                    for (PackageRollbackInfo info : data.info.getPackages()) {
                        if (info.isApex()) {
                            apexPackageNames.add(info.getPackageName());
                        }
                    }
                }
            }
        }
        for (RollbackData data2 : enabling) {
            PackageInstaller installer = this.mContext.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo session = installer.getSessionInfo(data2.stagedSessionId);
            if (session != null) {
                if (session.isStagedSessionApplied()) {
                    makeRollbackAvailable(data2);
                } else if (session.isStagedSessionFailed()) {
                    deleteRollback(data2);
                }
            }
        }
        for (RollbackData data3 : restoreInProgress) {
            PackageInstaller installer2 = this.mContext.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo session2 = installer2.getSessionInfo(data3.stagedSessionId);
            if (session2 != null && (session2.isStagedSessionApplied() || session2.isStagedSessionFailed())) {
                synchronized (this.mLock) {
                    data3.restoreUserDataInProgress = false;
                }
                saveRollbackData(data3);
            }
        }
        for (String apexPackageName : apexPackageNames) {
            onPackageReplaced(apexPackageName);
        }
        this.mPackageHealthObserver.onBootCompletedAsync();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: ensureRollbackDataLoaded */
    public void lambda$new$0$RollbackManagerServiceImpl() {
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void ensureRollbackDataLoadedLocked() {
        if (this.mRollbacks == null) {
            loadAllRollbackDataLocked();
        }
    }

    @GuardedBy({"mLock"})
    private void loadAllRollbackDataLocked() {
        this.mRollbacks = this.mRollbackStore.loadAllRollbackData();
        for (RollbackData data : this.mRollbacks) {
            this.mAllocatedRollbackIds.put(data.info.getRollbackId(), true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPackageReplaced(String packageName) {
        VersionedPackage installedVersion = getInstalledPackageVersion(packageName);
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackData> iter = this.mRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                if (data.state == 1 || data.state == 0) {
                    Iterator it = data.info.getPackages().iterator();
                    while (true) {
                        if (it.hasNext()) {
                            PackageRollbackInfo info = (PackageRollbackInfo) it.next();
                            if (info.getPackageName().equals(packageName) && !packageVersionsEqual(info.getVersionRolledBackFrom(), installedVersion)) {
                                iter.remove();
                                deleteRollback(data);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPackageFullyRemoved(String packageName) {
        expireRollbackForPackage(packageName);
    }

    private void sendFailure(IntentSender statusReceiver, int status, String message) {
        Log.e(TAG, message);
        try {
            Intent fillIn = new Intent();
            fillIn.putExtra("android.content.rollback.extra.STATUS", status);
            fillIn.putExtra("android.content.rollback.extra.STATUS_MESSAGE", message);
            statusReceiver.sendIntent(this.mContext, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
        }
    }

    private void sendSuccess(IntentSender statusReceiver) {
        try {
            Intent fillIn = new Intent();
            fillIn.putExtra("android.content.rollback.extra.STATUS", 0);
            statusReceiver.sendIntent(this.mContext, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: runExpiration */
    public void lambda$scheduleExpiration$9$RollbackManagerServiceImpl() {
        Instant now = Instant.now();
        Instant oldest = null;
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackData> iter = this.mRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                if (data.state == 1) {
                    if (!now.isBefore(data.timestamp.plusMillis(this.mRollbackLifetimeDurationInMillis))) {
                        iter.remove();
                        deleteRollback(data);
                    } else if (oldest == null || oldest.isAfter(data.timestamp)) {
                        oldest = data.timestamp;
                    }
                }
            }
        }
        if (oldest != null) {
            scheduleExpiration(now.until(oldest.plusMillis(this.mRollbackLifetimeDurationInMillis), ChronoUnit.MILLIS));
        }
    }

    private void scheduleExpiration(long duration) {
        getHandler().postDelayed(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$CAasF8x0yNQCLBmx5TOpEjeyeEM
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$scheduleExpiration$9$RollbackManagerServiceImpl();
            }
        }, duration);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Handler getHandler() {
        return this.mHandlerThread.getThreadHandler();
    }

    private boolean sessionMatchesForEnableRollback(PackageInstaller.SessionInfo session, int installFlags, File newPackageCodePath) {
        if (session == null || session.resolvedBaseCodePath == null) {
            return false;
        }
        File packageCodePath = new File(session.resolvedBaseCodePath).getParentFile();
        if (!newPackageCodePath.equals(packageCodePath) || installFlags != session.installFlags) {
            return false;
        }
        return true;
    }

    private Context getContextAsUser(UserHandle user) {
        try {
            return this.mContext.createPackageContextAsUser(this.mContext.getPackageName(), 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean enableRollback(int installFlags, File newPackageCodePath, int[] installedUsers, int user, int token) {
        PackageInstaller.SessionInfo packageSession;
        PackageInstaller.SessionInfo packageSession2;
        NewRollback newRollback;
        Context context = getContextAsUser(UserHandle.of(user));
        if (context == null) {
            Log.e(TAG, "Unable to create context for install session user.");
            return false;
        }
        PackageInstaller.SessionInfo parentSession = null;
        PackageInstaller.SessionInfo packageSession3 = null;
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        Iterator<PackageInstaller.SessionInfo> it = installer.getAllSessions().iterator();
        while (true) {
            if (!it.hasNext()) {
                packageSession = packageSession3;
                packageSession2 = parentSession;
                break;
            }
            PackageInstaller.SessionInfo info = it.next();
            if (info.isMultiPackage()) {
                int[] childSessionIds = info.getChildSessionIds();
                int length = childSessionIds.length;
                int i = 0;
                while (true) {
                    if (i < length) {
                        int childId = childSessionIds[i];
                        PackageInstaller.SessionInfo child = installer.getSessionInfo(childId);
                        if (!sessionMatchesForEnableRollback(child, installFlags, newPackageCodePath)) {
                            i++;
                        } else {
                            parentSession = info;
                            packageSession3 = child;
                            break;
                        }
                    }
                }
            } else if (sessionMatchesForEnableRollback(info, installFlags, newPackageCodePath)) {
                packageSession = info;
                packageSession2 = info;
                break;
            }
        }
        if (packageSession2 != null && packageSession != null) {
            RollbackData rd = null;
            synchronized (this.mLock) {
                try {
                    ensureRollbackDataLoadedLocked();
                    int i2 = 0;
                    while (true) {
                        if (i2 >= this.mRollbacks.size()) {
                            break;
                        }
                        RollbackData data = this.mRollbacks.get(i2);
                        if (data.apkSessionId != packageSession2.getSessionId()) {
                            i2++;
                        } else {
                            rd = data;
                            break;
                        }
                    }
                } catch (Throwable th) {
                    th = th;
                    while (true) {
                        try {
                            break;
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    }
                    throw th;
                }
            }
            if (rd != null) {
                try {
                    PackageParser.PackageLite newPackage = PackageParser.parsePackageLite(new File(packageSession.resolvedBaseCodePath), 0);
                    String packageName = newPackage.packageName;
                    for (PackageRollbackInfo info2 : rd.info.getPackages()) {
                        if (info2.getPackageName().equals(packageName)) {
                            info2.getInstalledUsers().addAll(IntArray.wrap(installedUsers));
                            return true;
                        }
                    }
                    Log.e(TAG, "Unable to find package in apk session");
                    return false;
                } catch (PackageParser.PackageParserException e) {
                    Log.e(TAG, "Unable to parse new package", e);
                    return false;
                }
            }
            synchronized (this.mLock) {
                try {
                    newRollback = getNewRollbackForPackageSessionLocked(packageSession.getSessionId());
                    if (newRollback == null) {
                        newRollback = createNewRollbackLocked(packageSession2);
                        this.mNewRollbacks.add(newRollback);
                    }
                } catch (Throwable th3) {
                    th = th3;
                    while (true) {
                        try {
                            break;
                        } catch (Throwable th4) {
                            th = th4;
                        }
                    }
                    throw th;
                }
            }
            newRollback.addToken(token);
            return enableRollbackForPackageSession(newRollback.data, packageSession, installedUsers);
        }
        Log.e(TAG, "Unable to find session for enabled rollback.");
        return false;
    }

    private boolean enableRollbackForPackageSession(RollbackData data, PackageInstaller.SessionInfo session, int[] installedUsers) {
        String[] strArr;
        int installFlags = session.installFlags;
        if ((262144 & installFlags) == 0) {
            Log.e(TAG, "Rollback is not enabled.");
            return false;
        } else if ((installFlags & 2048) != 0) {
            Log.e(TAG, "Rollbacks not supported for instant app install");
            return false;
        } else if (session.resolvedBaseCodePath == null) {
            Log.e(TAG, "Session code path has not been resolved.");
            return false;
        } else {
            try {
                PackageParser.PackageLite newPackage = PackageParser.parsePackageLite(new File(session.resolvedBaseCodePath), 0);
                String packageName = newPackage.packageName;
                Log.i(TAG, "Enabling rollback for install of " + packageName + ", session:" + session.sessionId);
                String installerPackageName = session.getInstallerPackageName();
                if (!enableRollbackAllowed(installerPackageName, packageName)) {
                    Log.e(TAG, "Installer " + installerPackageName + " is not allowed to enable rollback on " + packageName);
                    return false;
                }
                VersionedPackage newVersion = new VersionedPackage(packageName, newPackage.versionCode);
                boolean isApex = (131072 & installFlags) != 0;
                this.mContext.getPackageManager();
                try {
                    PackageInfo pkgInfo = getPackageInfo(packageName);
                    VersionedPackage installedVersion = new VersionedPackage(packageName, pkgInfo.getLongVersionCode());
                    PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(newVersion, installedVersion, new IntArray(), new ArrayList(), isApex, IntArray.wrap(installedUsers), new SparseLongArray());
                    try {
                        ApplicationInfo appInfo = pkgInfo.applicationInfo;
                        RollbackStore.backupPackageCodePath(data, packageName, appInfo.sourceDir);
                        if (!ArrayUtils.isEmpty(appInfo.splitSourceDirs)) {
                            for (String sourceDir : appInfo.splitSourceDirs) {
                                RollbackStore.backupPackageCodePath(data, packageName, sourceDir);
                            }
                        }
                        synchronized (this.mLock) {
                            data.info.getPackages().add(packageRollbackInfo);
                        }
                        return true;
                    } catch (IOException e) {
                        Log.e(TAG, "Unable to copy package for rollback for " + packageName, e);
                        return false;
                    }
                } catch (PackageManager.NameNotFoundException e2) {
                    Log.e(TAG, packageName + " is not installed");
                    return false;
                }
            } catch (PackageParser.PackageParserException e3) {
                Log.e(TAG, "Unable to parse new package", e3);
                return false;
            }
        }
    }

    public void snapshotAndRestoreUserData(final String packageName, final int[] userIds, final int appId, final long ceDataInode, final String seInfo, final int token) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("snapshotAndRestoreUserData may only be called by the system.");
        }
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$o7MYzpkOoXbj0yHHTqdCNjmpt8U
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$snapshotAndRestoreUserData$10$RollbackManagerServiceImpl(packageName, userIds, appId, ceDataInode, seInfo, token);
            }
        });
    }

    public /* synthetic */ void lambda$snapshotAndRestoreUserData$10$RollbackManagerServiceImpl(String packageName, int[] userIds, int appId, long ceDataInode, String seInfo, int token) {
        snapshotUserDataInternal(packageName);
        restoreUserDataInternal(packageName, userIds, appId, ceDataInode, seInfo, token);
        PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        pmi.finishPackageInstall(token, false);
    }

    private void snapshotUserDataInternal(String packageName) {
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < this.mRollbacks.size(); i++) {
                RollbackData data = this.mRollbacks.get(i);
                if (data.state == 0) {
                    Iterator it = data.info.getPackages().iterator();
                    while (true) {
                        if (it.hasNext()) {
                            PackageRollbackInfo info = (PackageRollbackInfo) it.next();
                            if (info.getPackageName().equals(packageName)) {
                                this.mAppDataRollbackHelper.snapshotAppData(data.info.getRollbackId(), info);
                                saveRollbackData(data);
                                break;
                            }
                        }
                    }
                }
            }
            for (NewRollback rollback : this.mNewRollbacks) {
                PackageRollbackInfo info2 = getPackageRollbackInfo(rollback.data, packageName);
                if (info2 != null) {
                    this.mAppDataRollbackHelper.snapshotAppData(rollback.data.info.getRollbackId(), info2);
                    saveRollbackData(rollback.data);
                }
            }
        }
    }

    private void restoreUserDataInternal(String packageName, int[] userIds, int appId, long ceDataInode, String seInfo, int token) {
        PackageRollbackInfo info = null;
        RollbackData rollbackData = null;
        synchronized (this.mLock) {
            try {
                ensureRollbackDataLoadedLocked();
                int i = 0;
                while (true) {
                    if (i >= this.mRollbacks.size()) {
                        break;
                    }
                    RollbackData data = this.mRollbacks.get(i);
                    if (data.restoreUserDataInProgress) {
                        try {
                            info = getPackageRollbackInfo(data, packageName);
                            if (info != null) {
                                rollbackData = data;
                                break;
                            }
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                    i++;
                }
                if (rollbackData == null) {
                    return;
                }
                for (int userId : userIds) {
                    boolean changedRollbackData = this.mAppDataRollbackHelper.restoreAppData(rollbackData.info.getRollbackId(), info, userId, appId, seInfo);
                    if (changedRollbackData) {
                        saveRollbackData(rollbackData);
                    }
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public boolean notifyStagedSession(final int sessionId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("notifyStagedSession may only be called by the system.");
        }
        final LinkedBlockingQueue<Boolean> result = new LinkedBlockingQueue<>();
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$GitEUZMj6F_TZMXHx8fkTXAcvdo
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$notifyStagedSession$11$RollbackManagerServiceImpl(sessionId, result);
            }
        });
        try {
            return result.take().booleanValue();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for notifyStagedSession response");
            return false;
        }
    }

    public /* synthetic */ void lambda$notifyStagedSession$11$RollbackManagerServiceImpl(int sessionId, LinkedBlockingQueue result) {
        NewRollback newRollback;
        int[] childSessionIds;
        PackageInstaller installer = this.mContext.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionInfo session = installer.getSessionInfo(sessionId);
        boolean z = false;
        if (session == null) {
            Log.e(TAG, "No matching install session for: " + sessionId);
            result.offer(false);
            return;
        }
        synchronized (this.mLock) {
            newRollback = createNewRollbackLocked(session);
        }
        if (!session.isMultiPackage()) {
            if (!enableRollbackForPackageSession(newRollback.data, session, new int[0])) {
                Log.e(TAG, "Unable to enable rollback for session: " + sessionId);
                result.offer(false);
                return;
            }
        } else {
            for (int childSessionId : session.getChildSessionIds()) {
                PackageInstaller.SessionInfo childSession = installer.getSessionInfo(childSessionId);
                if (childSession != null) {
                    if (!enableRollbackForPackageSession(newRollback.data, childSession, new int[0])) {
                        Log.e(TAG, "Unable to enable rollback for session: " + sessionId);
                        result.offer(false);
                        return;
                    }
                } else {
                    Log.e(TAG, "No matching child install session for: " + childSessionId);
                    result.offer(false);
                    return;
                }
            }
        }
        if (completeEnableRollback(newRollback, true) != null) {
            z = true;
        }
        result.offer(Boolean.valueOf(z));
    }

    public void notifyStagedApkSession(final int originalSessionId, final int apkSessionId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("notifyStagedApkSession may only be called by the system.");
        }
        getHandler().post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackManagerServiceImpl$ohlyqMiNlQtoY5XHz6vC79CRKAA
            @Override // java.lang.Runnable
            public final void run() {
                RollbackManagerServiceImpl.this.lambda$notifyStagedApkSession$12$RollbackManagerServiceImpl(originalSessionId, apkSessionId);
            }
        });
    }

    public /* synthetic */ void lambda$notifyStagedApkSession$12$RollbackManagerServiceImpl(int originalSessionId, int apkSessionId) {
        RollbackData rd = null;
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            int i = 0;
            while (true) {
                if (i >= this.mRollbacks.size()) {
                    break;
                }
                RollbackData data = this.mRollbacks.get(i);
                if (data.stagedSessionId != originalSessionId) {
                    i++;
                } else {
                    data.apkSessionId = apkSessionId;
                    rd = data;
                    break;
                }
            }
        }
        if (rd != null) {
            saveRollbackData(rd);
        }
    }

    private boolean enableRollbackAllowed(String installerPackageName, String packageName) {
        if (installerPackageName == null) {
            return false;
        }
        PackageManager pm = this.mContext.getPackageManager();
        boolean manageRollbacksGranted = pm.checkPermission("android.permission.MANAGE_ROLLBACKS", installerPackageName) == 0;
        boolean testManageRollbacksGranted = pm.checkPermission("android.permission.TEST_MANAGE_ROLLBACKS", installerPackageName) == 0;
        return (isModule(packageName) && manageRollbacksGranted) || testManageRollbacksGranted;
    }

    private boolean isModule(String packageName) {
        PackageManager pm = this.mContext.getPackageManager();
        try {
            ModuleInfo moduleInfo = pm.getModuleInfo(packageName, 0);
            return moduleInfo != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private VersionedPackage getInstalledPackageVersion(String packageName) {
        this.mContext.getPackageManager();
        try {
            PackageInfo pkgInfo = getPackageInfo(packageName);
            return new VersionedPackage(packageName, pkgInfo.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private PackageInfo getPackageInfo(String packageName) throws PackageManager.NameNotFoundException {
        PackageManager pm = this.mContext.getPackageManager();
        try {
            return pm.getPackageInfo(packageName, DumpState.DUMP_CHANGES);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getPackageInfo(packageName, 1073741824);
        }
    }

    private boolean packageVersionsEqual(VersionedPackage a, VersionedPackage b) {
        return a != null && b != null && a.getPackageName().equals(b.getPackageName()) && a.getLongVersionCode() == b.getLongVersionCode();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class SessionCallback extends PackageInstaller.SessionCallback {
        private SessionCallback() {
        }

        /* synthetic */ SessionCallback(RollbackManagerServiceImpl x0, AnonymousClass1 x1) {
            this();
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onCreated(int sessionId) {
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onBadgingChanged(int sessionId) {
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onActiveChanged(int sessionId, boolean active) {
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onProgressChanged(int sessionId, float progress) {
        }

        @Override // android.content.pm.PackageInstaller.SessionCallback
        public void onFinished(int sessionId, boolean success) {
            NewRollback newRollback;
            RollbackData rollback;
            synchronized (RollbackManagerServiceImpl.this.mLock) {
                newRollback = RollbackManagerServiceImpl.this.getNewRollbackForPackageSessionLocked(sessionId);
                if (newRollback != null) {
                    RollbackManagerServiceImpl.this.mNewRollbacks.remove(newRollback);
                }
            }
            if (newRollback != null && (rollback = RollbackManagerServiceImpl.this.completeEnableRollback(newRollback, success)) != null && !rollback.isStaged()) {
                RollbackManagerServiceImpl.this.makeRollbackAvailable(rollback);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public RollbackData completeEnableRollback(NewRollback newRollback, boolean success) {
        RollbackData data = newRollback.data;
        if (!success) {
            deleteRollback(data);
            return null;
        } else if (newRollback.isCancelled) {
            Log.e(TAG, "Rollback has been cancelled by PackageManager");
            deleteRollback(data);
            return null;
        } else if (data.info.getPackages().size() != newRollback.packageSessionIds.length) {
            Log.e(TAG, "Failed to enable rollback for all packages in session.");
            deleteRollback(data);
            return null;
        } else {
            saveRollbackData(data);
            synchronized (this.mLock) {
                ensureRollbackDataLoadedLocked();
                this.mRollbacks.add(data);
            }
            return data;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void makeRollbackAvailable(RollbackData data) {
        synchronized (this.mLock) {
            data.state = 1;
            data.timestamp = Instant.now();
        }
        saveRollbackData(data);
        List<String> packages = new ArrayList<>();
        for (int i = 0; i < data.info.getPackages().size(); i++) {
            packages.add(((PackageRollbackInfo) data.info.getPackages().get(i)).getPackageName());
        }
        this.mPackageHealthObserver.startObservingHealth(packages, this.mRollbackLifetimeDurationInMillis);
        scheduleExpiration(this.mRollbackLifetimeDurationInMillis);
    }

    private RollbackData getRollbackForId(int rollbackId) {
        synchronized (this.mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < this.mRollbacks.size(); i++) {
                RollbackData data = this.mRollbacks.get(i);
                if (data.info.getRollbackId() == rollbackId) {
                    return data;
                }
            }
            return null;
        }
    }

    private static PackageRollbackInfo getPackageRollbackInfo(RollbackData data, String packageName) {
        for (PackageRollbackInfo info : data.info.getPackages()) {
            if (info.getPackageName().equals(packageName)) {
                return info;
            }
        }
        return null;
    }

    @GuardedBy({"mLock"})
    private int allocateRollbackIdLocked() {
        int n = 0;
        while (true) {
            int rollbackId = this.mRandom.nextInt(2147483646) + 1;
            if (!this.mAllocatedRollbackIds.get(rollbackId, false)) {
                this.mAllocatedRollbackIds.put(rollbackId, true);
                return rollbackId;
            }
            int n2 = n + 1;
            if (n >= 32) {
                throw new IllegalStateException("Failed to allocate rollback ID");
            }
            n = n2;
        }
    }

    private void deleteRollback(RollbackData rollbackData) {
        for (PackageRollbackInfo info : rollbackData.info.getPackages()) {
            IntArray installedUsers = info.getInstalledUsers();
            for (int i = 0; i < installedUsers.size(); i++) {
                int userId = installedUsers.get(i);
                this.mAppDataRollbackHelper.destroyAppDataSnapshot(rollbackData.info.getRollbackId(), info, userId);
            }
        }
        this.mRollbackStore.deleteRollbackData(rollbackData);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveRollbackData(RollbackData rollbackData) {
        try {
            this.mRollbackStore.saveRollbackData(rollbackData);
        } catch (IOException ioe) {
            Log.e(TAG, "Unable to save rollback info for: " + rollbackData.info.getRollbackId(), ioe);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
            synchronized (this.mLock) {
                for (RollbackData data : this.mRollbacks) {
                    RollbackInfo info = data.info;
                    ipw.println(info.getRollbackId() + ":");
                    ipw.increaseIndent();
                    ipw.println("-state: " + data.getStateAsString());
                    ipw.println("-timestamp: " + data.timestamp);
                    if (data.stagedSessionId != -1) {
                        ipw.println("-stagedSessionId: " + data.stagedSessionId);
                    }
                    ipw.println("-packages:");
                    ipw.increaseIndent();
                    for (PackageRollbackInfo pkg : info.getPackages()) {
                        ipw.println(pkg.getPackageName() + " " + pkg.getVersionRolledBackFrom().getLongVersionCode() + " -> " + pkg.getVersionRolledBackTo().getLongVersionCode());
                    }
                    ipw.decreaseIndent();
                    if (data.state == 3) {
                        ipw.println("-causePackages:");
                        ipw.increaseIndent();
                        for (VersionedPackage cPkg : info.getCausePackages()) {
                            ipw.println(cPkg.getPackageName() + " " + cPkg.getLongVersionCode());
                        }
                        ipw.decreaseIndent();
                        ipw.println("-committedSessionId: " + info.getCommittedSessionId());
                    }
                    ipw.decreaseIndent();
                }
            }
        }
    }

    private void enforceManageRollbacks(String message) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.MANAGE_ROLLBACKS") != 0 && this.mContext.checkCallingOrSelfPermission("android.permission.TEST_MANAGE_ROLLBACKS") != 0) {
            throw new SecurityException(message + " requires android.permission.MANAGE_ROLLBACKS or android.permission.TEST_MANAGE_ROLLBACKS");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class NewRollback {
        public final RollbackData data;
        public final int[] packageSessionIds;
        private final IntArray mTokens = new IntArray();
        public boolean isCancelled = false;

        NewRollback(RollbackData data, int[] packageSessionIds) {
            this.data = data;
            this.packageSessionIds = packageSessionIds;
        }

        public void addToken(int token) {
            this.mTokens.add(token);
        }

        public boolean hasToken(int token) {
            return this.mTokens.indexOf(token) != -1;
        }
    }

    NewRollback createNewRollbackLocked(PackageInstaller.SessionInfo parentSession) {
        RollbackData data;
        int rollbackId = allocateRollbackIdLocked();
        int parentSessionId = parentSession.getSessionId();
        if (parentSession.isStaged()) {
            data = this.mRollbackStore.createStagedRollback(rollbackId, parentSessionId);
        } else {
            data = this.mRollbackStore.createNonStagedRollback(rollbackId);
        }
        int[] packageSessionIds = parentSession.isMultiPackage() ? parentSession.getChildSessionIds() : new int[]{parentSessionId};
        return new NewRollback(data, packageSessionIds);
    }

    NewRollback getNewRollbackForPackageSessionLocked(int packageSessionId) {
        int[] iArr;
        for (NewRollback newRollbackData : this.mNewRollbacks) {
            for (int id : newRollbackData.packageSessionIds) {
                if (id == packageSessionId) {
                    return newRollbackData;
                }
            }
        }
        return null;
    }
}
