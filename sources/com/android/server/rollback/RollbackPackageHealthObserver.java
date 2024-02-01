package com.android.server.rollback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.StatsLog;
import com.android.internal.annotations.GuardedBy;
import com.android.server.PackageWatchdog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public final class RollbackPackageHealthObserver implements PackageWatchdog.PackageHealthObserver {
    private static final int INVALID_ROLLBACK_ID = -1;
    private static final String NAME = "rollback-observer";
    private static final long NATIVE_CRASH_POLLING_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long NUMBER_OF_NATIVE_CRASH_POLLS = 10;
    private static final String TAG = "RollbackPackageHealthObserver";
    private final Context mContext;
    private final Handler mHandler;
    private final File mLastStagedRollbackIdFile;
    @GuardedBy({"mPendingStagedRollbackIds"})
    private final Set<Integer> mPendingStagedRollbackIds = new ArraySet();
    private long mNumberOfNativeCrashPollsRemaining = NUMBER_OF_NATIVE_CRASH_POLLS;

    /* JADX INFO: Access modifiers changed from: package-private */
    public RollbackPackageHealthObserver(Context context) {
        this.mContext = context;
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        this.mHandler = handlerThread.getThreadHandler();
        File dataDir = new File(Environment.getDataDirectory(), NAME);
        dataDir.mkdirs();
        this.mLastStagedRollbackIdFile = new File(dataDir, "last-staged-rollback-id");
        PackageWatchdog.getInstance(this.mContext).registerHealthObserver(this);
    }

    @Override // com.android.server.PackageWatchdog.PackageHealthObserver
    public int onHealthCheckFailed(VersionedPackage failedPackage) {
        if (getAvailableRollback((RollbackManager) this.mContext.getSystemService(RollbackManager.class), failedPackage) == null) {
            return 0;
        }
        return 3;
    }

    @Override // com.android.server.PackageWatchdog.PackageHealthObserver
    public boolean execute(final VersionedPackage failedPackage, int rollbackReason) {
        String failedPackageToLog;
        final RollbackManager rollbackManager = (RollbackManager) this.mContext.getSystemService(RollbackManager.class);
        final VersionedPackage moduleMetadataPackage = getModuleMetadataPackage();
        final RollbackInfo rollback = getAvailableRollback(rollbackManager, failedPackage);
        final int reasonToLog = mapFailureReasonToMetric(rollbackReason);
        if (rollbackReason == 1) {
            failedPackageToLog = SystemProperties.get("ro.init.updatable_crashing_process_name", "");
        } else {
            String failedPackageToLog2 = failedPackage.getPackageName();
            failedPackageToLog = failedPackageToLog2;
        }
        if (rollback == null) {
            Slog.w(TAG, "Expected rollback but no valid rollback found for package: [ " + failedPackage.getPackageName() + "] with versionCode: [" + failedPackage.getVersionCode() + "]");
            return false;
        }
        logEvent(moduleMetadataPackage, 1, reasonToLog, failedPackageToLog);
        final String str = failedPackageToLog;
        final LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver(new Consumer() { // from class: com.android.server.rollback.-$$Lambda$RollbackPackageHealthObserver$S-LBpORIptxKG87YS1jh9-oZbmc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RollbackPackageHealthObserver.this.lambda$execute$0$RollbackPackageHealthObserver(rollback, rollbackManager, moduleMetadataPackage, reasonToLog, str, (Intent) obj);
            }
        });
        this.mHandler.post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackPackageHealthObserver$oOra6LB_hHJjBK__eXmSDqgCWsk
            @Override // java.lang.Runnable
            public final void run() {
                rollbackManager.commitRollback(rollback.getRollbackId(), Collections.singletonList(failedPackage), rollbackReceiver.getIntentSender());
            }
        });
        return true;
    }

    public /* synthetic */ void lambda$execute$0$RollbackPackageHealthObserver(RollbackInfo rollback, RollbackManager rollbackManager, VersionedPackage moduleMetadataPackage, int reasonToLog, String failedPackageToLog, Intent result) {
        int status = result.getIntExtra("android.content.rollback.extra.STATUS", 1);
        if (status == 0) {
            if (rollback.isStaged()) {
                int rollbackId = rollback.getRollbackId();
                synchronized (this.mPendingStagedRollbackIds) {
                    this.mPendingStagedRollbackIds.add(Integer.valueOf(rollbackId));
                }
                BroadcastReceiver listener = listenForStagedSessionReady(rollbackManager, rollbackId, moduleMetadataPackage);
                handleStagedSessionChange(rollbackManager, rollbackId, listener, moduleMetadataPackage);
                return;
            }
            logEvent(moduleMetadataPackage, 2, reasonToLog, failedPackageToLog);
            return;
        }
        logEvent(moduleMetadataPackage, 3, reasonToLog, failedPackageToLog);
    }

    @Override // com.android.server.PackageWatchdog.PackageHealthObserver
    public String getName() {
        return NAME;
    }

    public void startObservingHealth(List<String> packages, long durationMs) {
        PackageWatchdog.getInstance(this.mContext).startObservingHealth(this, packages, durationMs);
    }

    public void onBootCompletedAsync() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackPackageHealthObserver$AIuzQKXcl9vSW9YzEpmdp7QJz1M
            @Override // java.lang.Runnable
            public final void run() {
                RollbackPackageHealthObserver.this.lambda$onBootCompletedAsync$2$RollbackPackageHealthObserver();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: onBootCompleted */
    public void lambda$onBootCompletedAsync$2$RollbackPackageHealthObserver() {
        RollbackManager rollbackManager = (RollbackManager) this.mContext.getSystemService(RollbackManager.class);
        PackageInstaller packageInstaller = this.mContext.getPackageManager().getPackageInstaller();
        String moduleMetadataPackageName = getModuleMetadataPackageName();
        VersionedPackage newModuleMetadataPackage = getModuleMetadataPackage();
        if (getAvailableRollback(rollbackManager, newModuleMetadataPackage) != null) {
            scheduleCheckAndMitigateNativeCrashes();
        }
        int rollbackId = popLastStagedRollbackId();
        if (rollbackId == -1) {
            return;
        }
        RollbackInfo rollback = null;
        Iterator it = rollbackManager.getRecentlyCommittedRollbacks().iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            RollbackInfo info = (RollbackInfo) it.next();
            if (rollbackId == info.getRollbackId()) {
                rollback = info;
                break;
            }
        }
        if (rollback == null) {
            Slog.e(TAG, "rollback info not found for last staged rollback: " + rollbackId);
            return;
        }
        VersionedPackage oldModuleMetadataPackage = null;
        Iterator it2 = rollback.getPackages().iterator();
        while (true) {
            if (!it2.hasNext()) {
                break;
            }
            PackageRollbackInfo packageRollback = (PackageRollbackInfo) it2.next();
            if (packageRollback.getPackageName().equals(moduleMetadataPackageName)) {
                oldModuleMetadataPackage = packageRollback.getVersionRolledBackFrom();
                break;
            }
        }
        int sessionId = rollback.getCommittedSessionId();
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            Slog.e(TAG, "On boot completed, could not load session id " + sessionId);
        } else if (sessionInfo.isStagedSessionApplied()) {
            logEvent(oldModuleMetadataPackage, 2, 0, "");
        } else if (!sessionInfo.isStagedSessionReady()) {
            logEvent(oldModuleMetadataPackage, 3, 0, "");
        }
    }

    private RollbackInfo getAvailableRollback(RollbackManager rollbackManager, VersionedPackage failedPackage) {
        boolean hasFailedPackage;
        for (RollbackInfo rollback : rollbackManager.getAvailableRollbacks()) {
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                if (packageRollback.getPackageName().equals(failedPackage.getPackageName()) && packageRollback.getVersionRolledBackFrom().getVersionCode() == failedPackage.getVersionCode()) {
                    hasFailedPackage = true;
                    continue;
                } else {
                    hasFailedPackage = false;
                    continue;
                }
                if (hasFailedPackage) {
                    return rollback;
                }
            }
        }
        return null;
    }

    private String getModuleMetadataPackageName() {
        String packageName = this.mContext.getResources().getString(17039707);
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        return packageName;
    }

    private VersionedPackage getModuleMetadataPackage() {
        String packageName = getModuleMetadataPackageName();
        if (packageName == null) {
            return null;
        }
        try {
            return new VersionedPackage(packageName, this.mContext.getPackageManager().getPackageInfo(packageName, 0).getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Module metadata provider not found");
            return null;
        }
    }

    private BroadcastReceiver listenForStagedSessionReady(final RollbackManager rollbackManager, final int rollbackId, final VersionedPackage moduleMetadataPackage) {
        BroadcastReceiver sessionUpdatedReceiver = new BroadcastReceiver() { // from class: com.android.server.rollback.RollbackPackageHealthObserver.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                RollbackPackageHealthObserver.this.handleStagedSessionChange(rollbackManager, rollbackId, this, moduleMetadataPackage);
            }
        };
        IntentFilter sessionUpdatedFilter = new IntentFilter("android.content.pm.action.SESSION_UPDATED");
        this.mContext.registerReceiver(sessionUpdatedReceiver, sessionUpdatedFilter);
        return sessionUpdatedReceiver;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleStagedSessionChange(RollbackManager rollbackManager, int rollbackId, BroadcastReceiver listener, VersionedPackage moduleMetadataPackage) {
        PackageInstaller packageInstaller = this.mContext.getPackageManager().getPackageInstaller();
        List<RollbackInfo> recentRollbacks = rollbackManager.getRecentlyCommittedRollbacks();
        for (int i = 0; i < recentRollbacks.size(); i++) {
            RollbackInfo recentRollback = recentRollbacks.get(i);
            int sessionId = recentRollback.getCommittedSessionId();
            if (rollbackId == recentRollback.getRollbackId() && sessionId != -1) {
                PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
                if (sessionInfo.isStagedSessionReady() && markStagedSessionHandled(rollbackId)) {
                    this.mContext.unregisterReceiver(listener);
                    saveLastStagedRollbackId(rollbackId);
                    logEvent(moduleMetadataPackage, 4, 0, "");
                    ((PowerManager) this.mContext.getSystemService(PowerManager.class)).reboot("Rollback staged install");
                } else if (sessionInfo.isStagedSessionFailed() && markStagedSessionHandled(rollbackId)) {
                    logEvent(moduleMetadataPackage, 3, 0, "");
                    this.mContext.unregisterReceiver(listener);
                }
            }
        }
    }

    private boolean markStagedSessionHandled(int rollbackId) {
        boolean remove;
        synchronized (this.mPendingStagedRollbackIds) {
            remove = this.mPendingStagedRollbackIds.remove(Integer.valueOf(rollbackId));
        }
        return remove;
    }

    private void saveLastStagedRollbackId(int stagedRollbackId) {
        try {
            FileOutputStream fos = new FileOutputStream(this.mLastStagedRollbackIdFile);
            PrintWriter pw = new PrintWriter(fos);
            pw.println(stagedRollbackId);
            pw.flush();
            FileUtils.sync(fos);
            pw.close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save last staged rollback id", e);
            this.mLastStagedRollbackIdFile.delete();
        }
    }

    private int popLastStagedRollbackId() {
        int rollbackId = -1;
        if (!this.mLastStagedRollbackIdFile.exists()) {
            return -1;
        }
        try {
            rollbackId = Integer.parseInt(IoUtils.readFileAsString(this.mLastStagedRollbackIdFile.getAbsolutePath()).trim());
        } catch (IOException | NumberFormatException e) {
            Slog.e(TAG, "Failed to retrieve last staged rollback id", e);
        }
        this.mLastStagedRollbackIdFile.delete();
        return rollbackId;
    }

    private static void logEvent(VersionedPackage moduleMetadataPackage, int type, int rollbackReason, String failingPackageName) {
        Slog.i(TAG, "Watchdog event occurred of type: " + type);
        if (moduleMetadataPackage != null) {
            StatsLog.logWatchdogRollbackOccurred(type, moduleMetadataPackage.getPackageName(), moduleMetadataPackage.getVersionCode(), rollbackReason, failingPackageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: checkAndMitigateNativeCrashes */
    public void lambda$scheduleCheckAndMitigateNativeCrashes$4$RollbackPackageHealthObserver() {
        this.mNumberOfNativeCrashPollsRemaining--;
        if ("1".equals(SystemProperties.get("ro.init.updatable_crashing"))) {
            execute(getModuleMetadataPackage(), 1);
        } else if (this.mNumberOfNativeCrashPollsRemaining > 0) {
            this.mHandler.postDelayed(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackPackageHealthObserver$qxl9oIFwPlFs_BOCz2kdHqQtB0U
                @Override // java.lang.Runnable
                public final void run() {
                    RollbackPackageHealthObserver.this.lambda$checkAndMitigateNativeCrashes$3$RollbackPackageHealthObserver();
                }
            }, NATIVE_CRASH_POLLING_INTERVAL_MILLIS);
        }
    }

    private void scheduleCheckAndMitigateNativeCrashes() {
        Slog.i(TAG, "Scheduling " + this.mNumberOfNativeCrashPollsRemaining + " polls to check and mitigate native crashes");
        this.mHandler.post(new Runnable() { // from class: com.android.server.rollback.-$$Lambda$RollbackPackageHealthObserver$0IN9novFyWgQaB1z9Q2H7ZQ6gqY
            @Override // java.lang.Runnable
            public final void run() {
                RollbackPackageHealthObserver.this.lambda$scheduleCheckAndMitigateNativeCrashes$4$RollbackPackageHealthObserver();
            }
        });
    }

    private int mapFailureReasonToMetric(int failureReason) {
        if (failureReason != 1) {
            if (failureReason != 2) {
                if (failureReason != 3) {
                    return failureReason != 4 ? 0 : 4;
                }
                return 3;
            }
            return 2;
        }
        return 1;
    }
}
