package com.android.server.backup.restore;

import android.app.IBackupAgent;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
import com.android.server.wm.ActivityTaskManagerService;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/* loaded from: classes.dex */
public class FullRestoreEngine extends RestoreEngine {
    private IBackupAgent mAgent;
    private String mAgentPackage;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    final boolean mAllowApks;
    private final boolean mAllowObbs;
    private long mAppVersion;
    private final UserBackupManagerService mBackupManagerService;
    final int mEphemeralOpToken;
    final boolean mIsAdbRestore;
    final IBackupManagerMonitor mMonitor;
    private final BackupRestoreTask mMonitorTask;
    private IFullBackupRestoreObserver mObserver;
    final PackageInfo mOnlyPackage;
    @GuardedBy({"mPipesLock"})
    private boolean mPipesClosed;
    private ApplicationInfo mTargetApp;
    private final int mUserId;
    private final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();
    private FullBackupObbConnection mObbConnection = null;
    private final HashMap<String, RestorePolicy> mPackagePolicies = new HashMap<>();
    private final HashMap<String, String> mPackageInstallers = new HashMap<>();
    private final HashMap<String, Signature[]> mManifestSignatures = new HashMap<>();
    private final HashSet<String> mClearedPackages = new HashSet<>();
    private ParcelFileDescriptor[] mPipes = null;
    private final Object mPipesLock = new Object();
    private byte[] mWidgetData = null;
    final byte[] mBuffer = new byte[32768];
    private long mBytes = 0;

    static /* synthetic */ long access$014(FullRestoreEngine x0, long x1) {
        long j = x0.mBytes + x1;
        x0.mBytes = j;
        return j;
    }

    public FullRestoreEngine(UserBackupManagerService backupManagerService, BackupRestoreTask monitorTask, IFullBackupRestoreObserver observer, IBackupManagerMonitor monitor, PackageInfo onlyPackage, boolean allowApks, boolean allowObbs, int ephemeralOpToken, boolean isAdbRestore) {
        this.mBackupManagerService = backupManagerService;
        this.mEphemeralOpToken = ephemeralOpToken;
        this.mMonitorTask = monitorTask;
        this.mObserver = observer;
        this.mMonitor = monitor;
        this.mOnlyPackage = onlyPackage;
        this.mAllowApks = allowApks;
        this.mAllowObbs = allowObbs;
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
        this.mIsAdbRestore = isAdbRestore;
        this.mUserId = backupManagerService.getUserId();
    }

    public IBackupAgent getAgent() {
        return this.mAgent;
    }

    public byte[] getWidgetData() {
        return this.mWidgetData;
    }

    /* JADX WARN: Removed duplicated region for block: B:185:0x048b A[Catch: IOException -> 0x0565, TRY_LEAVE, TryCatch #26 {IOException -> 0x0565, blocks: (B:185:0x048b, B:211:0x04f9, B:183:0x0481), top: B:287:0x0481 }] */
    /* JADX WARN: Removed duplicated region for block: B:218:0x050e  */
    /* JADX WARN: Removed duplicated region for block: B:220:0x051c A[Catch: IOException -> 0x05b3, TryCatch #4 {IOException -> 0x05b3, blocks: (B:215:0x0504, B:220:0x051c, B:222:0x0558, B:229:0x0579, B:232:0x0589, B:234:0x058f, B:236:0x0592, B:238:0x059e, B:235:0x0591), top: B:272:0x0504 }] */
    /* JADX WARN: Removed duplicated region for block: B:224:0x0561  */
    /* JADX WARN: Removed duplicated region for block: B:229:0x0579 A[Catch: IOException -> 0x05b3, TryCatch #4 {IOException -> 0x05b3, blocks: (B:215:0x0504, B:220:0x051c, B:222:0x0558, B:229:0x0579, B:232:0x0589, B:234:0x058f, B:236:0x0592, B:238:0x059e, B:235:0x0591), top: B:272:0x0504 }] */
    /* JADX WARN: Removed duplicated region for block: B:248:0x05b7  */
    /* JADX WARN: Removed duplicated region for block: B:260:0x05fa  */
    /* JADX WARN: Removed duplicated region for block: B:263:0x060b  */
    /* JADX WARN: Removed duplicated region for block: B:265:0x060e A[ORIG_RETURN, RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:317:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean restoreOneFile(java.io.InputStream r43, boolean r44, byte[] r45, android.content.pm.PackageInfo r46, boolean r47, int r48, android.app.backup.IBackupManagerMonitor r49) {
        /*
            Method dump skipped, instructions count: 1552
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.restore.FullRestoreEngine.restoreOneFile(java.io.InputStream, boolean, byte[], android.content.pm.PackageInfo, boolean, int, android.app.backup.IBackupManagerMonitor):boolean");
    }

    /* renamed from: com.android.server.backup.restore.FullRestoreEngine$2  reason: invalid class name */
    /* loaded from: classes.dex */
    static /* synthetic */ class AnonymousClass2 {
        static final /* synthetic */ int[] $SwitchMap$com$android$server$backup$restore$RestorePolicy = new int[RestorePolicy.values().length];

        static {
            try {
                $SwitchMap$com$android$server$backup$restore$RestorePolicy[RestorePolicy.IGNORE.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$com$android$server$backup$restore$RestorePolicy[RestorePolicy.ACCEPT_IF_APK.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
            try {
                $SwitchMap$com$android$server$backup$restore$RestorePolicy[RestorePolicy.ACCEPT.ordinal()] = 3;
            } catch (NoSuchFieldError e3) {
            }
        }
    }

    private void setUpPipes() throws IOException {
        synchronized (this.mPipesLock) {
            this.mPipes = ParcelFileDescriptor.createPipe();
            this.mPipesClosed = false;
        }
    }

    private void tearDownPipes() {
        synchronized (this.mPipesLock) {
            if (!this.mPipesClosed && this.mPipes != null) {
                try {
                    this.mPipes[0].close();
                    this.mPipes[1].close();
                    this.mPipesClosed = true;
                } catch (IOException e) {
                    Slog.w(BackupManagerService.TAG, "Couldn't close agent pipes", e);
                }
            }
        }
    }

    private void tearDownAgent(ApplicationInfo app, boolean doRestoreFinished) {
        if (this.mAgent != null) {
            if (doRestoreFinished) {
                try {
                    int token = this.mBackupManagerService.generateRandomIntegerToken();
                    long fullBackupAgentTimeoutMillis = this.mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
                    AdbRestoreFinishedLatch latch = new AdbRestoreFinishedLatch(this.mBackupManagerService, token);
                    this.mBackupManagerService.prepareOperationTimeout(token, fullBackupAgentTimeoutMillis, latch, 1);
                    if (this.mTargetApp.processName.equals("system")) {
                        Runnable runner = new AdbRestoreFinishedRunnable(this.mAgent, token, this.mBackupManagerService);
                        new Thread(runner, "restore-sys-finished-runner").start();
                    } else {
                        this.mAgent.doRestoreFinished(token, this.mBackupManagerService.getBackupManagerBinder());
                    }
                    latch.await();
                } catch (RemoteException e) {
                    Slog.d(BackupManagerService.TAG, "Lost app trying to shut down");
                }
            }
            this.mBackupManagerService.tearDownAgentAndKill(app);
            this.mAgent = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleTimeout() {
        tearDownPipes();
        setResult(-2);
        setRunning(false);
    }

    private static boolean isRestorableFile(FileMetadata info) {
        if ("c".equals(info.domain)) {
            return false;
        }
        return (ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD.equals(info.domain) && info.path.startsWith("no_backup/")) ? false : true;
    }

    private static boolean isCanonicalFilePath(String path) {
        if (path.contains("..") || path.contains("//")) {
            return false;
        }
        return true;
    }

    private boolean shouldForceClearAppDataOnFullRestore(String packageName) {
        String packageListString = Settings.Secure.getStringForUser(this.mBackupManagerService.getContext().getContentResolver(), "packages_to_clear_data_before_full_restore", this.mUserId);
        if (TextUtils.isEmpty(packageListString)) {
            return false;
        }
        List<String> packages = Arrays.asList(packageListString.split(";"));
        return packages.contains(packageName);
    }

    void sendOnRestorePackage(String name) {
        IFullBackupRestoreObserver iFullBackupRestoreObserver = this.mObserver;
        if (iFullBackupRestoreObserver != null) {
            try {
                iFullBackupRestoreObserver.onRestorePackage(name);
            } catch (RemoteException e) {
                Slog.w(BackupManagerService.TAG, "full restore observer went away: restorePackage");
                this.mObserver = null;
            }
        }
    }
}
