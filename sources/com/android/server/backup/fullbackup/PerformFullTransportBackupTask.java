package com.android.server.backup.fullbackup;

import android.app.IBackupAgent;
import android.app.backup.IBackupCallback;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.FullBackupJob;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.internal.Operation;
import com.android.server.backup.remote.RemoteCall;
import com.android.server.backup.remote.RemoteCallable;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportNotAvailableException;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.BackupObserverUtils;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/* loaded from: classes.dex */
public class PerformFullTransportBackupTask extends FullBackupTask implements BackupRestoreTask {
    private static final String TAG = "PFTBT";
    private UserBackupManagerService backupManagerService;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    IBackupObserver mBackupObserver;
    SinglePackageBackupRunner mBackupRunner;
    private final int mBackupRunnerOpToken;
    private volatile boolean mCancelAll;
    private final Object mCancelLock;
    private final int mCurrentOpToken;
    PackageInfo mCurrentPackage;
    private volatile boolean mIsDoingBackup;
    FullBackupJob mJob;
    CountDownLatch mLatch;
    private final OnTaskFinishedListener mListener;
    private IBackupManagerMonitor mMonitor;
    ArrayList<PackageInfo> mPackages;
    private final TransportClient mTransportClient;
    boolean mUpdateSchedule;
    private final int mUserId;
    boolean mUserInitiated;

    public static PerformFullTransportBackupTask newWithCurrentTransport(UserBackupManagerService backupManagerService, IFullBackupRestoreObserver observer, String[] whichPackages, boolean updateSchedule, FullBackupJob runningJob, CountDownLatch latch, IBackupObserver backupObserver, IBackupManagerMonitor monitor, boolean userInitiated, String caller) {
        final TransportManager transportManager = backupManagerService.getTransportManager();
        final TransportClient transportClient = transportManager.getCurrentTransportClient(caller);
        OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.fullbackup.-$$Lambda$PerformFullTransportBackupTask$ymLoQLrsEpmGaMrcudrdAgsU1Zk
            @Override // com.android.server.backup.internal.OnTaskFinishedListener
            public final void onFinished(String str) {
                TransportManager.this.disposeOfTransportClient(transportClient, str);
            }
        };
        return new PerformFullTransportBackupTask(backupManagerService, transportClient, observer, whichPackages, updateSchedule, runningJob, latch, backupObserver, monitor, listener, userInitiated);
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public PerformFullTransportBackupTask(UserBackupManagerService backupManagerService, TransportClient transportClient, IFullBackupRestoreObserver observer, String[] whichPackages, boolean updateSchedule, FullBackupJob runningJob, CountDownLatch latch, IBackupObserver backupObserver, IBackupManagerMonitor monitor, OnTaskFinishedListener listener, boolean userInitiated) {
        super(observer);
        String[] strArr = whichPackages;
        this.mCancelLock = new Object();
        this.backupManagerService = backupManagerService;
        this.mTransportClient = transportClient;
        this.mUpdateSchedule = updateSchedule;
        this.mLatch = latch;
        this.mJob = runningJob;
        this.mPackages = new ArrayList<>(strArr.length);
        this.mBackupObserver = backupObserver;
        this.mMonitor = monitor;
        this.mListener = listener != null ? listener : OnTaskFinishedListener.NOP;
        this.mUserInitiated = userInitiated;
        this.mCurrentOpToken = backupManagerService.generateRandomIntegerToken();
        this.mBackupRunnerOpToken = backupManagerService.generateRandomIntegerToken();
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
        this.mUserId = backupManagerService.getUserId();
        if (backupManagerService.isBackupOperationInProgress()) {
            Slog.d(TAG, "Skipping full backup. A backup is already in progress.");
            this.mCancelAll = true;
            return;
        }
        registerTask();
        int length = strArr.length;
        int i = 0;
        while (i < length) {
            String pkg = strArr[i];
            try {
                PackageManager pm = backupManagerService.getPackageManager();
                PackageInfo info = pm.getPackageInfoAsUser(pkg, 134217728, this.mUserId);
                this.mCurrentPackage = info;
                if (!AppBackupUtils.appIsEligibleForBackup(info.applicationInfo, this.mUserId)) {
                    this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 9, this.mCurrentPackage, 3, null);
                    BackupObserverUtils.sendBackupOnPackageResult(this.mBackupObserver, pkg, -2001);
                } else if (!AppBackupUtils.appGetsFullBackup(info)) {
                    this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 10, this.mCurrentPackage, 3, null);
                    BackupObserverUtils.sendBackupOnPackageResult(this.mBackupObserver, pkg, -2001);
                } else if (AppBackupUtils.appIsStopped(info.applicationInfo)) {
                    this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 11, this.mCurrentPackage, 3, null);
                    BackupObserverUtils.sendBackupOnPackageResult(this.mBackupObserver, pkg, -2001);
                } else {
                    this.mPackages.add(info);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Slog.i(TAG, "Requested package " + pkg + " not found; ignoring");
                this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 12, this.mCurrentPackage, 3, null);
            }
            i++;
            strArr = whichPackages;
        }
    }

    private void registerTask() {
        synchronized (this.backupManagerService.getCurrentOpLock()) {
            Slog.d(TAG, "backupmanager pftbt token=" + Integer.toHexString(this.mCurrentOpToken));
            this.backupManagerService.getCurrentOperations().put(this.mCurrentOpToken, new Operation(0, this, 2));
        }
    }

    public void unregisterTask() {
        this.backupManagerService.removeOperation(this.mCurrentOpToken);
    }

    @Override // com.android.server.backup.BackupRestoreTask
    public void execute() {
    }

    @Override // com.android.server.backup.BackupRestoreTask
    public void handleCancel(boolean cancelAll) {
        synchronized (this.mCancelLock) {
            if (!cancelAll) {
                Slog.wtf(TAG, "Expected cancelAll to be true.");
            }
            if (this.mCancelAll) {
                Slog.d(TAG, "Ignoring duplicate cancel call.");
                return;
            }
            this.mCancelAll = true;
            if (this.mIsDoingBackup) {
                this.backupManagerService.handleCancel(this.mBackupRunnerOpToken, cancelAll);
                try {
                    IBackupTransport transport = this.mTransportClient.getConnectedTransport("PFTBT.handleCancel()");
                    transport.cancelFullBackup();
                } catch (RemoteException | TransportNotAvailableException e) {
                    Slog.w(TAG, "Error calling cancelFullBackup() on transport: " + e);
                }
            }
        }
    }

    @Override // com.android.server.backup.BackupRestoreTask
    public void operationComplete(long result) {
    }

    /* JADX WARN: Code restructure failed: missing block: B:183:0x04cb, code lost:
        com.android.server.backup.utils.BackupObserverUtils.sendBackupOnPackageResult(r35.mBackupObserver, r3, com.android.server.job.JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
        android.util.Slog.w(com.android.server.backup.fullbackup.PerformFullTransportBackupTask.TAG, "Transport failed; aborting backup: " + r1);
        android.util.EventLog.writeEvent((int) com.android.server.EventLogTags.FULL_BACKUP_TRANSPORT_FAILURE, new java.lang.Object[0]);
     */
    /* JADX WARN: Code restructure failed: missing block: B:185:0x04f2, code lost:
        r35.backupManagerService.tearDownAgentAndKill(r4.applicationInfo);
     */
    /* JADX WARN: Code restructure failed: missing block: B:187:0x04fb, code lost:
        if (r35.mCancelAll == false) goto L240;
     */
    /* JADX WARN: Code restructure failed: missing block: B:188:0x04fd, code lost:
        r2 = -2003;
     */
    /* JADX WARN: Code restructure failed: missing block: B:189:0x0501, code lost:
        r2 = -1000;
     */
    /* JADX WARN: Code restructure failed: missing block: B:190:0x0502, code lost:
        android.util.Slog.i(com.android.server.backup.fullbackup.PerformFullTransportBackupTask.TAG, "Full backup completed with status: " + r2);
        com.android.server.backup.utils.BackupObserverUtils.sendBackupFinished(r35.mBackupObserver, r2);
        cleanUpPipes(r5);
        cleanUpPipes(r31);
        unregisterTask();
        r9 = r35.mJob;
     */
    /* JADX WARN: Code restructure failed: missing block: B:191:0x052a, code lost:
        if (r9 == null) goto L221;
     */
    /* JADX WARN: Code restructure failed: missing block: B:192:0x052c, code lost:
        r9.finishBackupPass(r35.mUserId);
     */
    /* JADX WARN: Code restructure failed: missing block: B:193:0x0531, code lost:
        r9 = r35.backupManagerService.getQueueLock();
     */
    /* JADX WARN: Code restructure failed: missing block: B:194:0x0537, code lost:
        monitor-enter(r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:197:0x053d, code lost:
        r35.backupManagerService.setRunningFullBackupTask(null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:198:0x0540, code lost:
        monitor-exit(r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:199:0x0541, code lost:
        r35.mListener.onFinished("PFTBT.run()");
        r35.mLatch.countDown();
     */
    /* JADX WARN: Code restructure failed: missing block: B:200:0x054f, code lost:
        if (r35.mUpdateSchedule == false) goto L231;
     */
    /* JADX WARN: Code restructure failed: missing block: B:201:0x0551, code lost:
        r35.backupManagerService.scheduleNextFullBackupJob(r12);
     */
    /* JADX WARN: Code restructure failed: missing block: B:202:0x0556, code lost:
        android.util.Slog.i(com.android.server.backup.fullbackup.PerformFullTransportBackupTask.TAG, "Full data backup pass finished.");
        r35.backupManagerService.getWakelock().release();
     */
    /* JADX WARN: Code restructure failed: missing block: B:203:0x0566, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:204:0x0567, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:205:0x0568, code lost:
        r1 = r0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:206:0x056a, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:207:0x056b, code lost:
        r1 = r0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:208:0x056e, code lost:
        monitor-exit(r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:209:0x056f, code lost:
        throw r1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:210:0x0570, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:211:0x0571, code lost:
        r8 = r31;
        r1 = r0;
        r27 = -1000;
     */
    /* JADX WARN: Code restructure failed: missing block: B:212:0x0578, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:213:0x0579, code lost:
        r8 = r31;
        r1 = r0;
        r2 = r5;
        r3 = r12;
     */
    /* JADX WARN: Code restructure failed: missing block: B:313:0x07d5, code lost:
        r1 = -2003;
     */
    /* JADX WARN: Code restructure failed: missing block: B:314:0x07d8, code lost:
        r1 = -1000;
     */
    /* JADX WARN: Code restructure failed: missing block: B:317:0x0801, code lost:
        r5.finishBackupPass(r35.mUserId);
     */
    /* JADX WARN: Code restructure failed: missing block: B:320:0x080d, code lost:
        r35.backupManagerService.setRunningFullBackupTask(null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:322:0x0814, code lost:
        r35.mListener.onFinished("PFTBT.run()");
        r35.mLatch.countDown();
     */
    /* JADX WARN: Code restructure failed: missing block: B:323:0x0822, code lost:
        if (r35.mUpdateSchedule != false) goto L55;
     */
    /* JADX WARN: Code restructure failed: missing block: B:324:0x0824, code lost:
        r35.backupManagerService.scheduleNextFullBackupJob(r3);
     */
    /* JADX WARN: Code restructure failed: missing block: B:325:0x0829, code lost:
        android.util.Slog.i(com.android.server.backup.fullbackup.PerformFullTransportBackupTask.TAG, "Full data backup pass finished.");
        r35.backupManagerService.getWakelock().release();
     */
    /* JADX WARN: Code restructure failed: missing block: B:326:0x083b, code lost:
        return;
     */
    /* JADX WARN: Finally extract failed */
    /* JADX WARN: Removed duplicated region for block: B:313:0x07d5  */
    /* JADX WARN: Removed duplicated region for block: B:314:0x07d8  */
    /* JADX WARN: Removed duplicated region for block: B:317:0x0801  */
    /* JADX WARN: Removed duplicated region for block: B:335:0x084a  */
    /* JADX WARN: Removed duplicated region for block: B:336:0x084f  */
    /* JADX WARN: Removed duplicated region for block: B:339:0x0879  */
    /* JADX WARN: Removed duplicated region for block: B:361:0x0885 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:367:0x080d A[EXC_TOP_SPLITTER, SYNTHETIC] */
    @Override // java.lang.Runnable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void run() {
        /*
            Method dump skipped, instructions count: 2230
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.fullbackup.PerformFullTransportBackupTask.run():void");
    }

    void cleanUpPipes(ParcelFileDescriptor[] pipes) {
        if (pipes != null) {
            if (pipes[0] != null) {
                ParcelFileDescriptor fd = pipes[0];
                pipes[0] = null;
                try {
                    fd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Unable to close pipe!");
                }
            }
            if (pipes[1] != null) {
                ParcelFileDescriptor fd2 = pipes[1];
                pipes[1] = null;
                try {
                    fd2.close();
                } catch (IOException e2) {
                    Slog.w(TAG, "Unable to close pipe!");
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class SinglePackageBackupPreflight implements BackupRestoreTask, FullBackupPreflight {
        private final int mCurrentOpToken;
        final long mQuota;
        final TransportClient mTransportClient;
        private final int mTransportFlags;
        final AtomicLong mResult = new AtomicLong(-1003);
        final CountDownLatch mLatch = new CountDownLatch(1);

        SinglePackageBackupPreflight(TransportClient transportClient, long quota, int currentOpToken, int transportFlags) {
            this.mTransportClient = transportClient;
            this.mQuota = quota;
            this.mCurrentOpToken = currentOpToken;
            this.mTransportFlags = transportFlags;
        }

        @Override // com.android.server.backup.fullbackup.FullBackupPreflight
        public int preflightFullBackup(PackageInfo pkg, final IBackupAgent agent) {
            long fullBackupAgentTimeoutMillis = PerformFullTransportBackupTask.this.mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
            try {
                PerformFullTransportBackupTask.this.backupManagerService.prepareOperationTimeout(this.mCurrentOpToken, fullBackupAgentTimeoutMillis, this, 0);
                agent.doMeasureFullBackup(this.mQuota, this.mCurrentOpToken, PerformFullTransportBackupTask.this.backupManagerService.getBackupManagerBinder(), this.mTransportFlags);
                this.mLatch.await(fullBackupAgentTimeoutMillis, TimeUnit.MILLISECONDS);
                final long totalSize = this.mResult.get();
                if (totalSize < 0) {
                    return (int) totalSize;
                }
                IBackupTransport transport = this.mTransportClient.connectOrThrow("PFTBT$SPBP.preflightFullBackup()");
                int result = transport.checkFullBackupSize(totalSize);
                if (result == -1005) {
                    try {
                        RemoteCall.execute(new RemoteCallable() { // from class: com.android.server.backup.fullbackup.-$$Lambda$PerformFullTransportBackupTask$SinglePackageBackupPreflight$hWbC3_rWMPrteAdbbM5aSW2SKD0
                            @Override // com.android.server.backup.remote.RemoteCallable
                            public final void call(Object obj) {
                                PerformFullTransportBackupTask.SinglePackageBackupPreflight.this.lambda$preflightFullBackup$0$PerformFullTransportBackupTask$SinglePackageBackupPreflight(agent, totalSize, (IBackupCallback) obj);
                            }
                        }, PerformFullTransportBackupTask.this.mAgentTimeoutParameters.getQuotaExceededTimeoutMillis());
                    } catch (Exception e) {
                        e = e;
                        Slog.w(PerformFullTransportBackupTask.TAG, "Exception preflighting " + pkg.packageName + ": " + e.getMessage());
                        return -1003;
                    }
                }
                return result;
            } catch (Exception e2) {
                e = e2;
            }
        }

        public /* synthetic */ void lambda$preflightFullBackup$0$PerformFullTransportBackupTask$SinglePackageBackupPreflight(IBackupAgent agent, long totalSize, IBackupCallback callback) throws RemoteException {
            agent.doQuotaExceeded(totalSize, this.mQuota, callback);
        }

        @Override // com.android.server.backup.BackupRestoreTask
        public void execute() {
        }

        @Override // com.android.server.backup.BackupRestoreTask
        public void operationComplete(long result) {
            this.mResult.set(result);
            this.mLatch.countDown();
            PerformFullTransportBackupTask.this.backupManagerService.removeOperation(this.mCurrentOpToken);
        }

        @Override // com.android.server.backup.BackupRestoreTask
        public void handleCancel(boolean cancelAll) {
            this.mResult.set(-1003L);
            this.mLatch.countDown();
            PerformFullTransportBackupTask.this.backupManagerService.removeOperation(this.mCurrentOpToken);
        }

        @Override // com.android.server.backup.fullbackup.FullBackupPreflight
        public long getExpectedSizeOrErrorCode() {
            long fullBackupAgentTimeoutMillis = PerformFullTransportBackupTask.this.mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
            try {
                this.mLatch.await(fullBackupAgentTimeoutMillis, TimeUnit.MILLISECONDS);
                return this.mResult.get();
            } catch (InterruptedException e) {
                return -1L;
            }
        }
    }

    /* loaded from: classes.dex */
    class SinglePackageBackupRunner implements Runnable, BackupRestoreTask {
        private final int mCurrentOpToken;
        private FullBackupEngine mEngine;
        private final int mEphemeralToken;
        private volatile boolean mIsCancelled;
        final ParcelFileDescriptor mOutput;
        final SinglePackageBackupPreflight mPreflight;
        private final long mQuota;
        final PackageInfo mTarget;
        private final int mTransportFlags;
        final CountDownLatch mPreflightLatch = new CountDownLatch(1);
        final CountDownLatch mBackupLatch = new CountDownLatch(1);
        private volatile int mPreflightResult = -1003;
        private volatile int mBackupResult = -1003;

        SinglePackageBackupRunner(ParcelFileDescriptor output, PackageInfo target, TransportClient transportClient, long quota, int currentOpToken, int transportFlags) throws IOException {
            this.mOutput = ParcelFileDescriptor.dup(output.getFileDescriptor());
            this.mTarget = target;
            this.mCurrentOpToken = currentOpToken;
            this.mEphemeralToken = PerformFullTransportBackupTask.this.backupManagerService.generateRandomIntegerToken();
            this.mPreflight = new SinglePackageBackupPreflight(transportClient, quota, this.mEphemeralToken, transportFlags);
            this.mQuota = quota;
            this.mTransportFlags = transportFlags;
            registerTask();
        }

        void registerTask() {
            synchronized (PerformFullTransportBackupTask.this.backupManagerService.getCurrentOpLock()) {
                PerformFullTransportBackupTask.this.backupManagerService.getCurrentOperations().put(this.mCurrentOpToken, new Operation(0, this, 0));
            }
        }

        void unregisterTask() {
            synchronized (PerformFullTransportBackupTask.this.backupManagerService.getCurrentOpLock()) {
                PerformFullTransportBackupTask.this.backupManagerService.getCurrentOperations().remove(this.mCurrentOpToken);
            }
        }

        @Override // java.lang.Runnable
        public void run() {
            FileOutputStream out = new FileOutputStream(this.mOutput.getFileDescriptor());
            this.mEngine = new FullBackupEngine(PerformFullTransportBackupTask.this.backupManagerService, out, this.mPreflight, this.mTarget, false, this, this.mQuota, this.mCurrentOpToken, this.mTransportFlags);
            try {
                try {
                    try {
                        try {
                            if (!this.mIsCancelled) {
                                this.mPreflightResult = this.mEngine.preflightCheck();
                            }
                            this.mPreflightLatch.countDown();
                            if (this.mPreflightResult == 0 && !this.mIsCancelled) {
                                this.mBackupResult = this.mEngine.backupOnePackage();
                            }
                            unregisterTask();
                            this.mBackupLatch.countDown();
                            this.mOutput.close();
                        } catch (Throwable th) {
                            this.mPreflightLatch.countDown();
                            throw th;
                        }
                    } catch (Exception e) {
                        Slog.e(PerformFullTransportBackupTask.TAG, "Exception during full package backup of " + this.mTarget.packageName);
                        unregisterTask();
                        this.mBackupLatch.countDown();
                        this.mOutput.close();
                    }
                } catch (IOException e2) {
                    Slog.w(PerformFullTransportBackupTask.TAG, "Error closing transport pipe in runner");
                }
            } catch (Throwable th2) {
                unregisterTask();
                this.mBackupLatch.countDown();
                try {
                    this.mOutput.close();
                } catch (IOException e3) {
                    Slog.w(PerformFullTransportBackupTask.TAG, "Error closing transport pipe in runner");
                }
                throw th2;
            }
        }

        public void sendQuotaExceeded(long backupDataBytes, long quotaBytes) {
            this.mEngine.sendQuotaExceeded(backupDataBytes, quotaBytes);
        }

        long getPreflightResultBlocking() {
            long fullBackupAgentTimeoutMillis = PerformFullTransportBackupTask.this.mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
            try {
                this.mPreflightLatch.await(fullBackupAgentTimeoutMillis, TimeUnit.MILLISECONDS);
                if (this.mIsCancelled) {
                    return -2003L;
                }
                if (this.mPreflightResult == 0) {
                    return this.mPreflight.getExpectedSizeOrErrorCode();
                }
                return this.mPreflightResult;
            } catch (InterruptedException e) {
                return -1003L;
            }
        }

        int getBackupResultBlocking() {
            long fullBackupAgentTimeoutMillis = PerformFullTransportBackupTask.this.mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
            try {
                this.mBackupLatch.await(fullBackupAgentTimeoutMillis, TimeUnit.MILLISECONDS);
                if (this.mIsCancelled) {
                    return -2003;
                }
                return this.mBackupResult;
            } catch (InterruptedException e) {
                return -1003;
            }
        }

        @Override // com.android.server.backup.BackupRestoreTask
        public void execute() {
        }

        @Override // com.android.server.backup.BackupRestoreTask
        public void operationComplete(long result) {
        }

        @Override // com.android.server.backup.BackupRestoreTask
        public void handleCancel(boolean cancelAll) {
            Slog.w(PerformFullTransportBackupTask.TAG, "Full backup cancel of " + this.mTarget.packageName);
            PerformFullTransportBackupTask performFullTransportBackupTask = PerformFullTransportBackupTask.this;
            performFullTransportBackupTask.mMonitor = BackupManagerMonitorUtils.monitorEvent(performFullTransportBackupTask.mMonitor, 4, PerformFullTransportBackupTask.this.mCurrentPackage, 2, null);
            this.mIsCancelled = true;
            PerformFullTransportBackupTask.this.backupManagerService.handleCancel(this.mEphemeralToken, cancelAll);
            PerformFullTransportBackupTask.this.backupManagerService.tearDownAgentAndKill(this.mTarget.applicationInfo);
            this.mPreflightLatch.countDown();
            this.mBackupLatch.countDown();
            PerformFullTransportBackupTask.this.backupManagerService.removeOperation(this.mCurrentOpToken);
        }
    }
}
