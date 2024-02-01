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
import com.android.internal.util.Preconditions;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
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
    private final BackupManagerService mBackupManagerService;
    final int mEphemeralOpToken;
    final IBackupManagerMonitor mMonitor;
    private final BackupRestoreTask mMonitorTask;
    private IFullBackupRestoreObserver mObserver;
    final PackageInfo mOnlyPackage;
    private ApplicationInfo mTargetApp;
    private final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();
    private FullBackupObbConnection mObbConnection = null;
    private final HashMap<String, RestorePolicy> mPackagePolicies = new HashMap<>();
    private final HashMap<String, String> mPackageInstallers = new HashMap<>();
    private final HashMap<String, Signature[]> mManifestSignatures = new HashMap<>();
    private final HashSet<String> mClearedPackages = new HashSet<>();
    private ParcelFileDescriptor[] mPipes = null;
    private byte[] mWidgetData = null;
    final byte[] mBuffer = new byte[32768];
    private long mBytes = 0;

    static /* synthetic */ long access$014(FullRestoreEngine x0, long x1) {
        long j = x0.mBytes + x1;
        x0.mBytes = j;
        return j;
    }

    public FullRestoreEngine(BackupManagerService backupManagerService, BackupRestoreTask monitorTask, IFullBackupRestoreObserver observer, IBackupManagerMonitor monitor, PackageInfo onlyPackage, boolean allowApks, boolean allowObbs, int ephemeralOpToken) {
        this.mBackupManagerService = backupManagerService;
        this.mEphemeralOpToken = ephemeralOpToken;
        this.mMonitorTask = monitorTask;
        this.mObserver = observer;
        this.mMonitor = monitor;
        this.mOnlyPackage = onlyPackage;
        this.mAllowApks = allowApks;
        this.mAllowObbs = allowObbs;
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
    }

    public IBackupAgent getAgent() {
        return this.mAgent;
    }

    public byte[] getWidgetData() {
        return this.mWidgetData;
    }

    /* JADX WARN: Can't wrap try/catch for region: R(10:113|114|(3:(6:117|(1:119)(1:186)|120|(1:122)(1:185)|(8:142|143|144|145|146|147|148|149)(3:124|(4:126|127|128|130)(1:141)|131)|115)|148|149)|188|189|143|144|145|146|147) */
    /* JADX WARN: Code restructure failed: missing block: B:211:0x053f, code lost:
        r0 = e;
     */
    /* JADX WARN: Removed duplicated region for block: B:101:0x029c  */
    /* JADX WARN: Removed duplicated region for block: B:180:0x04c9 A[Catch: IOException -> 0x059e, TRY_LEAVE, TryCatch #32 {IOException -> 0x059e, blocks: (B:180:0x04c9, B:206:0x052e, B:177:0x04bc), top: B:285:0x04bc }] */
    /* JADX WARN: Removed duplicated region for block: B:213:0x0544  */
    /* JADX WARN: Removed duplicated region for block: B:215:0x054c A[Catch: IOException -> 0x0593, TRY_LEAVE, TryCatch #22 {IOException -> 0x0593, blocks: (B:210:0x0539, B:215:0x054c), top: B:276:0x0539 }] */
    /* JADX WARN: Removed duplicated region for block: B:223:0x0596  */
    /* JADX WARN: Removed duplicated region for block: B:227:0x05a9  */
    /* JADX WARN: Removed duplicated region for block: B:229:0x05b5 A[Catch: IOException -> 0x05ef, TryCatch #4 {IOException -> 0x05ef, blocks: (B:219:0x058b, B:229:0x05b5, B:232:0x05c3, B:234:0x05c9, B:236:0x05cc, B:238:0x05d8, B:235:0x05cb), top: B:268:0x029a }] */
    /* JADX WARN: Removed duplicated region for block: B:257:0x0634  */
    /* JADX WARN: Removed duplicated region for block: B:260:0x0643  */
    /* JADX WARN: Removed duplicated region for block: B:262:0x0646 A[RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:263:0x0649  */
    /* JADX WARN: Removed duplicated region for block: B:81:0x0213 A[Catch: NameNotFoundException -> 0x0246, IOException -> 0x0248, TryCatch #29 {NameNotFoundException -> 0x0246, IOException -> 0x0248, blocks: (B:79:0x01fe, B:81:0x0213, B:85:0x0230, B:84:0x0223, B:86:0x0235), top: B:295:0x01fe }] */
    /* JADX WARN: Removed duplicated region for block: B:94:0x024e A[Catch: IOException -> 0x01ed, TryCatch #7 {IOException -> 0x01ed, blocks: (B:69:0x01e4, B:77:0x01fa, B:92:0x024a, B:94:0x024e, B:96:0x0271, B:98:0x0279, B:105:0x02a8, B:55:0x019b, B:57:0x01a3, B:59:0x01a8, B:58:0x01a6, B:61:0x01b4), top: B:269:0x0142 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean restoreOneFile(java.io.InputStream r53, boolean r54, byte[] r55, android.content.pm.PackageInfo r56, boolean r57, int r58, android.app.backup.IBackupManagerMonitor r59) {
        /*
            Method dump skipped, instructions count: 1624
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.restore.FullRestoreEngine.restoreOneFile(java.io.InputStream, boolean, byte[], android.content.pm.PackageInfo, boolean, int, android.app.backup.IBackupManagerMonitor):boolean");
    }

    private void setUpPipes() throws IOException {
        this.mPipes = ParcelFileDescriptor.createPipe();
    }

    private void tearDownPipes() {
        synchronized (this) {
            if (this.mPipes != null) {
                try {
                    this.mPipes[0].close();
                    this.mPipes[0] = null;
                    this.mPipes[1].close();
                    this.mPipes[1] = null;
                } catch (IOException e) {
                    Slog.w(BackupManagerService.TAG, "Couldn't close agent pipes", e);
                }
                this.mPipes = null;
            }
        }
    }

    private void tearDownAgent(ApplicationInfo app) {
        if (this.mAgent != null) {
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
        return ("r".equals(info.domain) && info.path.startsWith("no_backup/")) ? false : true;
    }

    private static boolean isCanonicalFilePath(String path) {
        if (path.contains("..") || path.contains("//")) {
            return false;
        }
        return true;
    }

    private boolean shouldForceClearAppDataOnFullRestore(String packageName) {
        String packageListString = Settings.Secure.getString(this.mBackupManagerService.getContext().getContentResolver(), "packages_to_clear_data_before_full_restore");
        if (TextUtils.isEmpty(packageListString)) {
            return false;
        }
        List<String> packages = Arrays.asList(packageListString.split(";"));
        return packages.contains(packageName);
    }

    void sendOnRestorePackage(String name) {
        if (this.mObserver != null) {
            try {
                this.mObserver.onRestorePackage(name);
            } catch (RemoteException e) {
                Slog.w(BackupManagerService.TAG, "full restore observer went away: restorePackage");
                this.mObserver = null;
            }
        }
    }
}
