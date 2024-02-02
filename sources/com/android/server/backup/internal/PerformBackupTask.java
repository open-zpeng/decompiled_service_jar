package com.android.server.backup.internal;

import android.app.IBackupAgent;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SELinux;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.DataChangedJournal;
import com.android.server.backup.KeyValueBackupJob;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.BackupObserverUtils;
import com.android.server.job.JobSchedulerShellCommand;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
/* loaded from: classes.dex */
public class PerformBackupTask implements BackupRestoreTask {
    private static final String TAG = "PerformBackupTask";
    private BackupManagerService backupManagerService;
    private IBackupAgent mAgentBinder;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private ParcelFileDescriptor mBackupData;
    private File mBackupDataName;
    private volatile boolean mCancelAll;
    private final int mCurrentOpToken;
    private PackageInfo mCurrentPackage;
    private BackupState mCurrentState;
    private volatile int mEphemeralOpToken;
    private boolean mFinished;
    private final PerformFullTransportBackupTask mFullBackupTask;
    private DataChangedJournal mJournal;
    private final OnTaskFinishedListener mListener;
    private IBackupManagerMonitor mMonitor;
    private ParcelFileDescriptor mNewState;
    private File mNewStateName;
    private final boolean mNonIncremental;
    private IBackupObserver mObserver;
    private ArrayList<BackupRequest> mOriginalQueue;
    private List<String> mPendingFullBackups;
    private ParcelFileDescriptor mSavedState;
    private File mSavedStateName;
    private File mStateDir;
    private int mStatus;
    private final TransportClient mTransportClient;
    private final boolean mUserInitiated;
    private final Object mCancelLock = new Object();
    private ArrayList<BackupRequest> mQueue = new ArrayList<>();

    public PerformBackupTask(BackupManagerService backupManagerService, TransportClient transportClient, String dirName, ArrayList<BackupRequest> queue, DataChangedJournal journal, IBackupObserver observer, IBackupManagerMonitor monitor, OnTaskFinishedListener listener, List<String> pendingFullBackups, boolean userInitiated, boolean nonIncremental) {
        this.backupManagerService = backupManagerService;
        this.mTransportClient = transportClient;
        this.mOriginalQueue = queue;
        this.mJournal = journal;
        this.mObserver = observer;
        this.mMonitor = monitor;
        this.mListener = listener != null ? listener : OnTaskFinishedListener.NOP;
        this.mPendingFullBackups = pendingFullBackups;
        this.mUserInitiated = userInitiated;
        this.mNonIncremental = nonIncremental;
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
        this.mStateDir = new File(backupManagerService.getBaseStateDir(), dirName);
        this.mCurrentOpToken = backupManagerService.generateRandomIntegerToken();
        this.mFinished = false;
        synchronized (backupManagerService.getCurrentOpLock()) {
            if (backupManagerService.isBackupOperationInProgress()) {
                Slog.d(TAG, "Skipping backup since one is already in progress.");
                this.mCancelAll = true;
                this.mFullBackupTask = null;
                this.mCurrentState = BackupState.FINAL;
                backupManagerService.addBackupTrace("Skipped. Backup already in progress.");
            } else {
                this.mCurrentState = BackupState.INITIAL;
                CountDownLatch latch = new CountDownLatch(1);
                String[] fullBackups = (String[]) this.mPendingFullBackups.toArray(new String[this.mPendingFullBackups.size()]);
                this.mFullBackupTask = new PerformFullTransportBackupTask(backupManagerService, transportClient, null, fullBackups, false, null, latch, this.mObserver, this.mMonitor, this.mListener, this.mUserInitiated);
                registerTask();
                backupManagerService.addBackupTrace("STATE => INITIAL");
            }
        }
    }

    private void registerTask() {
        synchronized (this.backupManagerService.getCurrentOpLock()) {
            this.backupManagerService.getCurrentOperations().put(this.mCurrentOpToken, new Operation(0, this, 2));
        }
    }

    private void unregisterTask() {
        this.backupManagerService.removeOperation(this.mCurrentOpToken);
    }

    @Override // com.android.server.backup.BackupRestoreTask
    @GuardedBy("mCancelLock")
    public void execute() {
        synchronized (this.mCancelLock) {
            switch (this.mCurrentState) {
                case INITIAL:
                    beginBackup();
                    break;
                case BACKUP_PM:
                    backupPm();
                    break;
                case RUNNING_QUEUE:
                    invokeNextAgent();
                    break;
                case FINAL:
                    if (!this.mFinished) {
                        finalizeBackup();
                        break;
                    } else {
                        Slog.e(TAG, "Duplicate finish of K/V pass");
                        break;
                    }
            }
        }
    }

    private void beginBackup() {
        this.backupManagerService.clearBackupTrace();
        StringBuilder b = new StringBuilder(256);
        b.append("beginBackup: [");
        Iterator<BackupRequest> it = this.mOriginalQueue.iterator();
        while (it.hasNext()) {
            BackupRequest req = it.next();
            b.append(' ');
            b.append(req.packageName);
        }
        b.append(" ]");
        this.backupManagerService.addBackupTrace(b.toString());
        this.mAgentBinder = null;
        this.mStatus = 0;
        if (this.mOriginalQueue.isEmpty() && this.mPendingFullBackups.isEmpty()) {
            Slog.w(TAG, "Backup begun with an empty queue - nothing to do.");
            this.backupManagerService.addBackupTrace("queue empty at begin");
            BackupObserverUtils.sendBackupFinished(this.mObserver, 0);
            executeNextState(BackupState.FINAL);
            return;
        }
        this.mQueue = (ArrayList) this.mOriginalQueue.clone();
        boolean skipPm = this.mNonIncremental;
        int i = 0;
        while (true) {
            if (i >= this.mQueue.size()) {
                break;
            } else if (BackupManagerService.PACKAGE_MANAGER_SENTINEL.equals(this.mQueue.get(i).packageName)) {
                this.mQueue.remove(i);
                skipPm = false;
                break;
            } else {
                i++;
            }
        }
        Slog.v(TAG, "Beginning backup of " + this.mQueue.size() + " targets");
        File pmState = new File(this.mStateDir, BackupManagerService.PACKAGE_MANAGER_SENTINEL);
        try {
            try {
                IBackupTransport transport = this.mTransportClient.connectOrThrow("PBT.beginBackup()");
                String transportName = transport.transportDirName();
                EventLog.writeEvent((int) EventLogTags.BACKUP_START, transportName);
                if (this.mStatus == 0 && pmState.length() <= 0) {
                    Slog.i(TAG, "Initializing (wiping) backup state and transport storage");
                    this.backupManagerService.addBackupTrace("initializing transport " + transportName);
                    this.backupManagerService.resetBackupState(this.mStateDir);
                    this.mStatus = transport.initializeDevice();
                    this.backupManagerService.addBackupTrace("transport.initializeDevice() == " + this.mStatus);
                    if (this.mStatus == 0) {
                        EventLog.writeEvent((int) EventLogTags.BACKUP_INITIALIZE, new Object[0]);
                    } else {
                        EventLog.writeEvent((int) EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                        Slog.e(TAG, "Transport error in initializeDevice()");
                    }
                }
                if (skipPm) {
                    Slog.d(TAG, "Skipping backup of package metadata.");
                    executeNextState(BackupState.RUNNING_QUEUE);
                } else if (this.mStatus == 0) {
                    executeNextState(BackupState.BACKUP_PM);
                }
                this.backupManagerService.addBackupTrace("exiting prelim: " + this.mStatus);
                if (this.mStatus == 0) {
                    return;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error in backup thread during init", e);
                this.backupManagerService.addBackupTrace("Exception in backup thread during init: " + e);
                this.mStatus = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                this.backupManagerService.addBackupTrace("exiting prelim: " + this.mStatus);
                if (this.mStatus == 0) {
                    return;
                }
            }
            this.backupManagerService.resetBackupState(this.mStateDir);
            BackupObserverUtils.sendBackupFinished(this.mObserver, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
            executeNextState(BackupState.FINAL);
        } catch (Throwable th) {
            this.backupManagerService.addBackupTrace("exiting prelim: " + this.mStatus);
            if (this.mStatus != 0) {
                this.backupManagerService.resetBackupState(this.mStateDir);
                BackupObserverUtils.sendBackupFinished(this.mObserver, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                executeNextState(BackupState.FINAL);
            }
            throw th;
        }
    }

    private void backupPm() {
        try {
            try {
                PackageManagerBackupAgent pmAgent = this.backupManagerService.makeMetadataAgent();
                this.mStatus = invokeAgentForBackup(BackupManagerService.PACKAGE_MANAGER_SENTINEL, IBackupAgent.Stub.asInterface(pmAgent.onBind()));
                BackupManagerService backupManagerService = this.backupManagerService;
                backupManagerService.addBackupTrace("PMBA invoke: " + this.mStatus);
                this.backupManagerService.getBackupHandler().removeMessages(17);
                BackupManagerService backupManagerService2 = this.backupManagerService;
                backupManagerService2.addBackupTrace("exiting backupPm: " + this.mStatus);
                if (this.mStatus == 0) {
                    return;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error in backup thread during pm", e);
                BackupManagerService backupManagerService3 = this.backupManagerService;
                backupManagerService3.addBackupTrace("Exception in backup thread during pm: " + e);
                this.mStatus = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                BackupManagerService backupManagerService4 = this.backupManagerService;
                backupManagerService4.addBackupTrace("exiting backupPm: " + this.mStatus);
                if (this.mStatus == 0) {
                    return;
                }
            }
            this.backupManagerService.resetBackupState(this.mStateDir);
            BackupObserverUtils.sendBackupFinished(this.mObserver, invokeAgentToObserverError(this.mStatus));
            executeNextState(BackupState.FINAL);
        } catch (Throwable th) {
            BackupManagerService backupManagerService5 = this.backupManagerService;
            backupManagerService5.addBackupTrace("exiting backupPm: " + this.mStatus);
            if (this.mStatus != 0) {
                this.backupManagerService.resetBackupState(this.mStateDir);
                BackupObserverUtils.sendBackupFinished(this.mObserver, invokeAgentToObserverError(this.mStatus));
                executeNextState(BackupState.FINAL);
            }
            throw th;
        }
    }

    private int invokeAgentToObserverError(int error) {
        if (error == -1003) {
            return -1003;
        }
        return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
    }

    /* JADX WARN: Code restructure failed: missing block: B:81:0x0295, code lost:
        if (r11.mStatus == (-1004)) goto L88;
     */
    /* JADX WARN: Code restructure failed: missing block: B:96:0x02e0, code lost:
        if (r11.mStatus == (-1004)) goto L88;
     */
    /* JADX WARN: Code restructure failed: missing block: B:97:0x02e2, code lost:
        r11.mStatus = 0;
        com.android.server.backup.utils.BackupObserverUtils.sendBackupOnPackageResult(r11.mObserver, r1.packageName, -2002);
     */
    /* JADX WARN: Code restructure failed: missing block: B:98:0x02ec, code lost:
        revertAndEndBackup();
        r6 = com.android.server.backup.internal.BackupState.FINAL;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void invokeNextAgent() {
        /*
            Method dump skipped, instructions count: 847
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.internal.PerformBackupTask.invokeNextAgent():void");
    }

    private void finalizeBackup() {
        this.backupManagerService.addBackupTrace("finishing");
        Iterator<BackupRequest> it = this.mQueue.iterator();
        while (it.hasNext()) {
            BackupRequest req = it.next();
            this.backupManagerService.dataChangedImpl(req.packageName);
        }
        if (this.mJournal != null && !this.mJournal.delete()) {
            Slog.e(TAG, "Unable to remove backup journal file " + this.mJournal);
        }
        if (this.backupManagerService.getCurrentToken() == 0 && this.mStatus == 0) {
            this.backupManagerService.addBackupTrace("success; recording token");
            try {
                IBackupTransport transport = this.mTransportClient.connectOrThrow("PBT.finalizeBackup()");
                this.backupManagerService.setCurrentToken(transport.getCurrentRestoreSet());
                this.backupManagerService.writeRestoreTokens();
            } catch (Exception e) {
                Slog.e(TAG, "Transport threw reporting restore set: " + e.getMessage());
                this.backupManagerService.addBackupTrace("transport threw returning token");
            }
        }
        synchronized (this.backupManagerService.getQueueLock()) {
            this.backupManagerService.setBackupRunning(false);
            if (this.mStatus == -1001) {
                this.backupManagerService.addBackupTrace("init required; rerunning");
                try {
                    String name = this.backupManagerService.getTransportManager().getTransportName(this.mTransportClient.getTransportComponent());
                    this.backupManagerService.getPendingInits().add(name);
                } catch (Exception e2) {
                    Slog.w(TAG, "Failed to query transport name for init: " + e2.getMessage());
                }
                clearMetadata();
                this.backupManagerService.backupNow();
            }
        }
        this.backupManagerService.clearBackupTrace();
        unregisterTask();
        if (!this.mCancelAll && this.mStatus == 0 && this.mPendingFullBackups != null && !this.mPendingFullBackups.isEmpty()) {
            Slog.d(TAG, "Starting full backups for: " + this.mPendingFullBackups);
            this.backupManagerService.getWakelock().acquire();
            new Thread(this.mFullBackupTask, "full-transport-requested").start();
        } else if (this.mCancelAll) {
            this.mListener.onFinished("PBT.finalizeBackup()");
            if (this.mFullBackupTask != null) {
                this.mFullBackupTask.unregisterTask();
            }
            BackupObserverUtils.sendBackupFinished(this.mObserver, -2003);
        } else {
            this.mListener.onFinished("PBT.finalizeBackup()");
            this.mFullBackupTask.unregisterTask();
            int i = this.mStatus;
            if (i != -1005 && i != 0) {
                switch (i) {
                    case JobSchedulerShellCommand.CMD_ERR_CONSTRAINTS /* -1002 */:
                        break;
                    case JobSchedulerShellCommand.CMD_ERR_NO_JOB /* -1001 */:
                        BackupObserverUtils.sendBackupFinished(this.mObserver, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                        break;
                    default:
                        BackupObserverUtils.sendBackupFinished(this.mObserver, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                        break;
                }
            }
            BackupObserverUtils.sendBackupFinished(this.mObserver, 0);
        }
        this.mFinished = true;
        Slog.i(TAG, "K/V backup pass finished.");
        this.backupManagerService.getWakelock().release();
    }

    private void clearMetadata() {
        File pmState = new File(this.mStateDir, BackupManagerService.PACKAGE_MANAGER_SENTINEL);
        if (pmState.exists()) {
            pmState.delete();
        }
    }

    private int invokeAgentForBackup(String packageName, IBackupAgent agent) {
        IBackupTransport transport;
        long quota;
        Slog.d(TAG, "invokeAgentForBackup on " + packageName);
        this.backupManagerService.addBackupTrace("invoking " + packageName);
        File blankStateName = new File(this.mStateDir, "blank_state");
        this.mSavedStateName = new File(this.mStateDir, packageName);
        this.mBackupDataName = new File(this.backupManagerService.getDataDir(), packageName + ".data");
        this.mNewStateName = new File(this.mStateDir, packageName + ".new");
        this.mSavedState = null;
        this.mBackupData = null;
        this.mNewState = null;
        boolean callingAgent = false;
        this.mEphemeralOpToken = this.backupManagerService.generateRandomIntegerToken();
        try {
            try {
                if (packageName.equals(BackupManagerService.PACKAGE_MANAGER_SENTINEL)) {
                    this.mCurrentPackage = new PackageInfo();
                    this.mCurrentPackage.packageName = packageName;
                }
                this.mSavedState = ParcelFileDescriptor.open(this.mNonIncremental ? blankStateName : this.mSavedStateName, 402653184);
                this.mBackupData = ParcelFileDescriptor.open(this.mBackupDataName, 1006632960);
                if (!SELinux.restorecon(this.mBackupDataName)) {
                    Slog.e(TAG, "SELinux restorecon failed on " + this.mBackupDataName);
                }
                this.mNewState = ParcelFileDescriptor.open(this.mNewStateName, 1006632960);
                transport = this.mTransportClient.connectOrThrow("PBT.invokeAgentForBackup()");
                quota = transport.getBackupQuota(packageName, false);
            } catch (Exception e) {
                e = e;
            }
        } catch (Throwable th) {
            e = th;
        }
        try {
            this.backupManagerService.addBackupTrace("setting timeout");
            long kvBackupAgentTimeoutMillis = this.mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis();
            this.backupManagerService.prepareOperationTimeout(this.mEphemeralOpToken, kvBackupAgentTimeoutMillis, this, 0);
            this.backupManagerService.addBackupTrace("calling agent doBackup()");
            agent.doBackup(this.mSavedState, this.mBackupData, this.mNewState, quota, this.mEphemeralOpToken, this.backupManagerService.getBackupManagerBinder(), transport.getTransportFlags());
            if (this.mNonIncremental) {
                blankStateName.delete();
            }
            this.backupManagerService.addBackupTrace("invoke success");
            return 0;
        } catch (Exception e2) {
            e = e2;
            callingAgent = true;
            Slog.e(TAG, "Error invoking for backup on " + packageName + ". " + e);
            BackupManagerService backupManagerService = this.backupManagerService;
            StringBuilder sb = new StringBuilder();
            sb.append("exception: ");
            sb.append(e);
            backupManagerService.addBackupTrace(sb.toString());
            EventLog.writeEvent((int) EventLogTags.BACKUP_AGENT_FAILURE, packageName, e.toString());
            errorCleanup();
            int i = callingAgent ? -1003 : JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            if (this.mNonIncremental) {
                blankStateName.delete();
            }
            return i;
        } catch (Throwable th2) {
            e = th2;
            if (this.mNonIncremental) {
                blankStateName.delete();
            }
            throw e;
        }
    }

    private void failAgent(IBackupAgent agent, String message) {
        try {
            agent.fail(message);
        } catch (Exception e) {
            Slog.w(TAG, "Error conveying failure to " + this.mCurrentPackage.packageName);
        }
    }

    private String SHA1Checksum(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] checksum = md.digest(input);
            StringBuffer sb = new StringBuffer(checksum.length * 2);
            for (byte b : checksum) {
                sb.append(Integer.toHexString(b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            Slog.e(TAG, "Unable to use SHA-1!");
            return "00";
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private void writeWidgetPayloadIfAppropriate(java.io.FileDescriptor r12, java.lang.String r13) throws java.io.IOException {
        /*
            r11 = this;
            r0 = 0
            byte[] r0 = com.android.server.AppWidgetBackupBridge.getWidgetState(r13, r0)
            java.io.File r1 = new java.io.File
            java.io.File r2 = r11.mStateDir
            java.lang.StringBuilder r3 = new java.lang.StringBuilder
            r3.<init>()
            r3.append(r13)
            java.lang.String r4 = "_widget"
            r3.append(r4)
            java.lang.String r3 = r3.toString()
            r1.<init>(r2, r3)
            boolean r2 = r1.exists()
            if (r2 != 0) goto L26
            if (r0 != 0) goto L26
            return
        L26:
            r3 = 0
            r4 = 0
            if (r0 == 0) goto L62
            java.lang.String r3 = r11.SHA1Checksum(r0)
            if (r2 == 0) goto L62
            java.io.FileInputStream r5 = new java.io.FileInputStream
            r5.<init>(r1)
            java.io.DataInputStream r6 = new java.io.DataInputStream     // Catch: java.lang.Throwable -> L5c
            r6.<init>(r5)     // Catch: java.lang.Throwable -> L5c
            java.lang.String r7 = r6.readUTF()     // Catch: java.lang.Throwable -> L50
            $closeResource(r4, r6)     // Catch: java.lang.Throwable -> L5c
            $closeResource(r4, r5)
            boolean r5 = java.util.Objects.equals(r3, r7)
            if (r5 == 0) goto L62
            return
        L4d:
            r7 = move-exception
            r8 = r4
            goto L56
        L50:
            r7 = move-exception
            throw r7     // Catch: java.lang.Throwable -> L52
        L52:
            r8 = move-exception
            r10 = r8
            r8 = r7
            r7 = r10
        L56:
            $closeResource(r8, r6)     // Catch: java.lang.Throwable -> L5c
            throw r7     // Catch: java.lang.Throwable -> L5c
        L5a:
            r6 = move-exception
            goto L5e
        L5c:
            r4 = move-exception
            throw r4     // Catch: java.lang.Throwable -> L5a
        L5e:
            $closeResource(r4, r5)
            throw r6
        L62:
            android.app.backup.BackupDataOutput r5 = new android.app.backup.BackupDataOutput
            r5.<init>(r12)
            if (r0 == 0) goto L9f
            java.io.FileOutputStream r6 = new java.io.FileOutputStream
            r6.<init>(r1)
            java.io.DataOutputStream r7 = new java.io.DataOutputStream     // Catch: java.lang.Throwable -> L99
            r7.<init>(r6)     // Catch: java.lang.Throwable -> L99
            r7.writeUTF(r3)     // Catch: java.lang.Throwable -> L8d
            $closeResource(r4, r7)     // Catch: java.lang.Throwable -> L99
            $closeResource(r4, r6)
            java.lang.String r4 = "￭￭widget"
            int r6 = r0.length
            r5.writeEntityHeader(r4, r6)
            int r4 = r0.length
            r5.writeEntityData(r0, r4)
            goto La9
        L8a:
            r8 = move-exception
            r9 = r4
            goto L93
        L8d:
            r8 = move-exception
            throw r8     // Catch: java.lang.Throwable -> L8f
        L8f:
            r9 = move-exception
            r10 = r9
            r9 = r8
            r8 = r10
        L93:
            $closeResource(r9, r7)     // Catch: java.lang.Throwable -> L99
            throw r8     // Catch: java.lang.Throwable -> L99
        L97:
            r7 = move-exception
            goto L9b
        L99:
            r4 = move-exception
            throw r4     // Catch: java.lang.Throwable -> L97
        L9b:
            $closeResource(r4, r6)
            throw r7
        L9f:
            java.lang.String r4 = "￭￭widget"
            r6 = -1
            r5.writeEntityHeader(r4, r6)
            r1.delete()
        La9:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.internal.PerformBackupTask.writeWidgetPayloadIfAppropriate(java.io.FileDescriptor, java.lang.String):void");
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:32:0x008f, code lost:
        failAgent(r21.mAgentBinder, "Illegal backup key: " + r0);
        r21.backupManagerService.addBackupTrace("illegal key " + r0 + " from " + r0);
        r8 = new java.lang.Object[r11];
        r8[0] = r0;
        r8[r12] = "bad key";
        android.util.EventLog.writeEvent((int) com.android.server.EventLogTags.BACKUP_AGENT_FAILURE, r8);
        r21.mMonitor = com.android.server.backup.utils.BackupManagerMonitorUtils.monitorEvent(r21.mMonitor, 5, r21.mCurrentPackage, 3, com.android.server.backup.utils.BackupManagerMonitorUtils.putMonitoringExtra((android.os.Bundle) null, "android.app.backup.extra.LOG_ILLEGAL_KEY", r0));
        r21.backupManagerService.getBackupHandler().removeMessages(17);
        com.android.server.backup.utils.BackupObserverUtils.sendBackupOnPackageResult(r21.mObserver, r0, -1003);
        errorCleanup();
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00fd, code lost:
        return;
     */
    @Override // com.android.server.backup.BackupRestoreTask
    @com.android.internal.annotations.GuardedBy("mCancelLock")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void operationComplete(long r22) {
        /*
            Method dump skipped, instructions count: 928
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.internal.PerformBackupTask.operationComplete(long):void");
    }

    @Override // com.android.server.backup.BackupRestoreTask
    @GuardedBy("mCancelLock")
    public void handleCancel(boolean cancelAll) {
        String logPackageName;
        this.backupManagerService.removeOperation(this.mEphemeralOpToken);
        synchronized (this.mCancelLock) {
            if (this.mFinished) {
                return;
            }
            this.mCancelAll = cancelAll;
            if (this.mCurrentPackage != null) {
                logPackageName = this.mCurrentPackage.packageName;
            } else {
                logPackageName = "no_package_yet";
            }
            Slog.i(TAG, "Cancel backing up " + logPackageName);
            EventLog.writeEvent((int) EventLogTags.BACKUP_AGENT_FAILURE, logPackageName);
            BackupManagerService backupManagerService = this.backupManagerService;
            backupManagerService.addBackupTrace("cancel of " + logPackageName + ", cancelAll=" + cancelAll);
            this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 21, this.mCurrentPackage, 2, BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_CANCEL_ALL", this.mCancelAll));
            errorCleanup();
            if (!cancelAll) {
                executeNextState(this.mQueue.isEmpty() ? BackupState.FINAL : BackupState.RUNNING_QUEUE);
                this.backupManagerService.dataChangedImpl(this.mCurrentPackage.packageName);
            } else {
                finalizeBackup();
            }
        }
    }

    private void revertAndEndBackup() {
        long delay;
        this.backupManagerService.addBackupTrace("transport error; reverting");
        try {
            IBackupTransport transport = this.mTransportClient.connectOrThrow("PBT.revertAndEndBackup()");
            delay = transport.requestBackupTime();
        } catch (Exception e) {
            Slog.w(TAG, "Unable to contact transport for recommended backoff: " + e.getMessage());
            delay = 0;
        }
        KeyValueBackupJob.schedule(this.backupManagerService.getContext(), delay, this.backupManagerService.getConstants());
        Iterator<BackupRequest> it = this.mOriginalQueue.iterator();
        while (it.hasNext()) {
            BackupRequest request = it.next();
            this.backupManagerService.dataChangedImpl(request.packageName);
        }
    }

    private void errorCleanup() {
        this.mBackupDataName.delete();
        this.mNewStateName.delete();
        clearAgentState();
    }

    private void clearAgentState() {
        try {
            if (this.mSavedState != null) {
                this.mSavedState.close();
            }
        } catch (IOException e) {
        }
        try {
            if (this.mBackupData != null) {
                this.mBackupData.close();
            }
        } catch (IOException e2) {
        }
        try {
            if (this.mNewState != null) {
                this.mNewState.close();
            }
        } catch (IOException e3) {
        }
        synchronized (this.backupManagerService.getCurrentOpLock()) {
            this.backupManagerService.getCurrentOperations().remove(this.mEphemeralOpToken);
            this.mNewState = null;
            this.mBackupData = null;
            this.mSavedState = null;
        }
        if (this.mCurrentPackage.applicationInfo != null) {
            BackupManagerService backupManagerService = this.backupManagerService;
            backupManagerService.addBackupTrace("unbinding " + this.mCurrentPackage.packageName);
            try {
                this.backupManagerService.getActivityManager().unbindBackupAgent(this.mCurrentPackage.applicationInfo);
            } catch (RemoteException e4) {
            }
        }
    }

    private void executeNextState(BackupState nextState) {
        BackupManagerService backupManagerService = this.backupManagerService;
        backupManagerService.addBackupTrace("executeNextState => " + nextState);
        this.mCurrentState = nextState;
        Message msg = this.backupManagerService.getBackupHandler().obtainMessage(20, this);
        this.backupManagerService.getBackupHandler().sendMessage(msg);
    }
}
