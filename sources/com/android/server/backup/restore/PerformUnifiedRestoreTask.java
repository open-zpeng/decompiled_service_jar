package com.android.server.backup.restore;

import android.app.IBackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.app.backup.RestoreDescription;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.BackupUtils;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.TransportManager;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.job.JobSchedulerShellCommand;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import libcore.io.IoUtils;
/* loaded from: classes.dex */
public class PerformUnifiedRestoreTask implements BackupRestoreTask {
    private BackupManagerService backupManagerService;
    private List<PackageInfo> mAcceptSet;
    private IBackupAgent mAgent;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    ParcelFileDescriptor mBackupData;
    private File mBackupDataName;
    private int mCount;
    private PackageInfo mCurrentPackage;
    private final int mEphemeralOpToken;
    private boolean mIsSystemRestore;
    private final OnTaskFinishedListener mListener;
    private IBackupManagerMonitor mMonitor;
    ParcelFileDescriptor mNewState;
    private File mNewStateName;
    private IRestoreObserver mObserver;
    private PackageManagerBackupAgent mPmAgent;
    private int mPmToken;
    private RestoreDescription mRestoreDescription;
    private File mSavedStateName;
    private File mStageName;
    File mStateDir;
    private int mStatus;
    private PackageInfo mTargetPackage;
    private long mToken;
    private final TransportClient mTransportClient;
    private final TransportManager mTransportManager;
    private byte[] mWidgetData;
    private UnifiedRestoreState mState = UnifiedRestoreState.INITIAL;
    private long mStartRealtime = SystemClock.elapsedRealtime();
    private boolean mFinished = false;
    private boolean mDidLaunch = false;

    public PerformUnifiedRestoreTask(BackupManagerService backupManagerService, TransportClient transportClient, IRestoreObserver observer, IBackupManagerMonitor monitor, long restoreSetToken, PackageInfo targetPackage, int pmToken, boolean isFullSystemRestore, String[] filterSet, OnTaskFinishedListener listener) {
        String[] filterSet2;
        this.backupManagerService = backupManagerService;
        this.mTransportManager = backupManagerService.getTransportManager();
        this.mEphemeralOpToken = backupManagerService.generateRandomIntegerToken();
        this.mTransportClient = transportClient;
        this.mObserver = observer;
        this.mMonitor = monitor;
        this.mToken = restoreSetToken;
        this.mPmToken = pmToken;
        this.mTargetPackage = targetPackage;
        this.mIsSystemRestore = isFullSystemRestore;
        this.mListener = listener;
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
        if (targetPackage != null) {
            this.mAcceptSet = new ArrayList();
            this.mAcceptSet.add(targetPackage);
            return;
        }
        if (filterSet == null) {
            List<PackageInfo> apps = PackageManagerBackupAgent.getStorableApplications(backupManagerService.getPackageManager());
            String[] filterSet3 = packagesToNames(apps);
            Slog.i(BackupManagerService.TAG, "Full restore; asking about " + filterSet3.length + " apps");
            filterSet2 = filterSet3;
        } else {
            filterSet2 = filterSet;
        }
        this.mAcceptSet = new ArrayList(filterSet2.length);
        boolean hasSettings = false;
        boolean hasSystem = false;
        int i = 0;
        while (true) {
            int i2 = i;
            int i3 = filterSet2.length;
            if (i2 >= i3) {
                break;
            }
            try {
                PackageManager pm = backupManagerService.getPackageManager();
                PackageInfo info = pm.getPackageInfo(filterSet2[i2], 0);
                if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(info.packageName)) {
                    hasSystem = true;
                } else if (BackupManagerService.SETTINGS_PACKAGE.equals(info.packageName)) {
                    hasSettings = true;
                } else if (AppBackupUtils.appIsEligibleForBackup(info.applicationInfo, pm)) {
                    this.mAcceptSet.add(info);
                }
            } catch (PackageManager.NameNotFoundException e) {
            }
            i = i2 + 1;
        }
        if (hasSystem) {
            try {
                this.mAcceptSet.add(0, backupManagerService.getPackageManager().getPackageInfo(PackageManagerService.PLATFORM_PACKAGE_NAME, 0));
            } catch (PackageManager.NameNotFoundException e2) {
            }
        }
        if (hasSettings) {
            try {
                this.mAcceptSet.add(backupManagerService.getPackageManager().getPackageInfo(BackupManagerService.SETTINGS_PACKAGE, 0));
            } catch (PackageManager.NameNotFoundException e3) {
            }
        }
    }

    private String[] packagesToNames(List<PackageInfo> apps) {
        int N = apps.size();
        String[] names = new String[N];
        for (int i = 0; i < N; i++) {
            names[i] = apps.get(i).packageName;
        }
        return names;
    }

    @Override // com.android.server.backup.BackupRestoreTask
    public void execute() {
        switch (this.mState) {
            case INITIAL:
                startRestore();
                return;
            case RUNNING_QUEUE:
                dispatchNextRestore();
                return;
            case RESTORE_KEYVALUE:
                restoreKeyValue();
                return;
            case RESTORE_FULL:
                restoreFull();
                return;
            case RESTORE_FINISHED:
                restoreFinished();
                return;
            case FINAL:
                if (!this.mFinished) {
                    finalizeRestore();
                } else {
                    Slog.e(BackupManagerService.TAG, "Duplicate finish");
                }
                this.mFinished = true;
                return;
            default:
                return;
        }
    }

    private void startRestore() {
        sendStartRestore(this.mAcceptSet.size());
        if (this.mIsSystemRestore) {
            AppWidgetBackupBridge.restoreStarting(0);
        }
        try {
            String transportDirName = this.mTransportManager.getTransportDirName(this.mTransportClient.getTransportComponent());
            this.mStateDir = new File(this.backupManagerService.getBaseStateDir(), transportDirName);
            PackageInfo pmPackage = new PackageInfo();
            pmPackage.packageName = BackupManagerService.PACKAGE_MANAGER_SENTINEL;
            this.mAcceptSet.add(0, pmPackage);
            PackageInfo[] packages = (PackageInfo[]) this.mAcceptSet.toArray(new PackageInfo[0]);
            IBackupTransport transport = this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask.startRestore()");
            this.mStatus = transport.startRestore(this.mToken, packages);
            if (this.mStatus != 0) {
                Slog.e(BackupManagerService.TAG, "Transport error " + this.mStatus + "; no restore possible");
                this.mStatus = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                executeNextState(UnifiedRestoreState.FINAL);
                return;
            }
            RestoreDescription desc = transport.nextRestorePackage();
            if (desc == null) {
                Slog.e(BackupManagerService.TAG, "No restore metadata available; halting");
                this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 22, this.mCurrentPackage, 3, null);
                this.mStatus = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                executeNextState(UnifiedRestoreState.FINAL);
            } else if (!BackupManagerService.PACKAGE_MANAGER_SENTINEL.equals(desc.getPackageName())) {
                Slog.e(BackupManagerService.TAG, "Required package metadata but got " + desc.getPackageName());
                this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 23, this.mCurrentPackage, 3, null);
                this.mStatus = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                executeNextState(UnifiedRestoreState.FINAL);
            } else {
                this.mCurrentPackage = new PackageInfo();
                this.mCurrentPackage.packageName = BackupManagerService.PACKAGE_MANAGER_SENTINEL;
                this.mPmAgent = this.backupManagerService.makeMetadataAgent(null);
                this.mAgent = IBackupAgent.Stub.asInterface(this.mPmAgent.onBind());
                initiateOneRestore(this.mCurrentPackage, 0L);
                this.backupManagerService.getBackupHandler().removeMessages(18);
                if (!this.mPmAgent.hasMetadata()) {
                    Slog.e(BackupManagerService.TAG, "PM agent has no metadata, so not restoring");
                    this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 24, this.mCurrentPackage, 3, null);
                    EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, BackupManagerService.PACKAGE_MANAGER_SENTINEL, "Package manager restore metadata missing");
                    this.mStatus = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                    this.backupManagerService.getBackupHandler().removeMessages(20, this);
                    executeNextState(UnifiedRestoreState.FINAL);
                }
            }
        } catch (Exception e) {
            Slog.e(BackupManagerService.TAG, "Unable to contact transport for restore: " + e.getMessage());
            this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 25, null, 1, null);
            this.mStatus = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            this.backupManagerService.getBackupHandler().removeMessages(20, this);
            executeNextState(UnifiedRestoreState.FINAL);
        }
    }

    private void dispatchNextRestore() {
        UnifiedRestoreState nextState;
        UnifiedRestoreState nextState2 = UnifiedRestoreState.FINAL;
        try {
            IBackupTransport transport = this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask.dispatchNextRestore()");
            this.mRestoreDescription = transport.nextRestorePackage();
            String pkgName = this.mRestoreDescription != null ? this.mRestoreDescription.getPackageName() : null;
            if (pkgName == null) {
                Slog.e(BackupManagerService.TAG, "Failure getting next package name");
                EventLog.writeEvent((int) EventLogTags.RESTORE_TRANSPORT_FAILURE, new Object[0]);
                nextState2 = UnifiedRestoreState.FINAL;
            } else if (this.mRestoreDescription == RestoreDescription.NO_MORE_PACKAGES) {
                Slog.v(BackupManagerService.TAG, "No more packages; finishing restore");
                int millis = (int) (SystemClock.elapsedRealtime() - this.mStartRealtime);
                EventLog.writeEvent((int) EventLogTags.RESTORE_SUCCESS, Integer.valueOf(this.mCount), Integer.valueOf(millis));
                nextState2 = UnifiedRestoreState.FINAL;
            } else {
                Slog.i(BackupManagerService.TAG, "Next restore package: " + this.mRestoreDescription);
                sendOnRestorePackage(pkgName);
                PackageManagerBackupAgent.Metadata metaInfo = this.mPmAgent.getRestoredMetadata(pkgName);
                if (metaInfo == null) {
                    Slog.e(BackupManagerService.TAG, "No metadata for " + pkgName);
                    EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, pkgName, "Package metadata missing");
                    nextState2 = UnifiedRestoreState.RUNNING_QUEUE;
                    return;
                }
                try {
                    this.mCurrentPackage = this.backupManagerService.getPackageManager().getPackageInfo(pkgName, 134217728);
                    if (metaInfo.versionCode > this.mCurrentPackage.getLongVersionCode()) {
                        if ((this.mCurrentPackage.applicationInfo.flags & DumpState.DUMP_INTENT_FILTER_VERIFIERS) == 0) {
                            String message = "Source version " + metaInfo.versionCode + " > installed version " + this.mCurrentPackage.getLongVersionCode();
                            Slog.w(BackupManagerService.TAG, "Package " + pkgName + ": " + message);
                            Bundle monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_RESTORE_VERSION", metaInfo.versionCode);
                            this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 27, this.mCurrentPackage, 3, BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras, "android.app.backup.extra.LOG_RESTORE_ANYWAY", false));
                            EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, pkgName, message);
                            nextState2 = UnifiedRestoreState.RUNNING_QUEUE;
                            return;
                        }
                        Slog.v(BackupManagerService.TAG, "Source version " + metaInfo.versionCode + " > installed version " + this.mCurrentPackage.getLongVersionCode() + " but restoreAnyVersion");
                        Bundle monitoringExtras2 = BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_RESTORE_VERSION", metaInfo.versionCode);
                        this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 27, this.mCurrentPackage, 3, BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras2, "android.app.backup.extra.LOG_RESTORE_ANYWAY", true));
                    }
                    this.mWidgetData = null;
                    int type = this.mRestoreDescription.getDataType();
                    if (type == 1) {
                        nextState = UnifiedRestoreState.RESTORE_KEYVALUE;
                    } else if (type != 2) {
                        Slog.e(BackupManagerService.TAG, "Unrecognized restore type " + type);
                        nextState2 = UnifiedRestoreState.RUNNING_QUEUE;
                    } else {
                        nextState = UnifiedRestoreState.RESTORE_FULL;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(BackupManagerService.TAG, "Package not present: " + pkgName);
                    this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 26, this.mCurrentPackage, 3, null);
                    EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, pkgName, "Package missing on device");
                    nextState2 = UnifiedRestoreState.RUNNING_QUEUE;
                }
            }
        } catch (Exception e2) {
            Slog.e(BackupManagerService.TAG, "Can't get next restore target from transport; halting: " + e2.getMessage());
            EventLog.writeEvent((int) EventLogTags.RESTORE_TRANSPORT_FAILURE, new Object[0]);
            nextState2 = UnifiedRestoreState.FINAL;
        } finally {
            executeNextState(nextState2);
        }
    }

    private void restoreKeyValue() {
        String packageName = this.mCurrentPackage.packageName;
        if (this.mCurrentPackage.applicationInfo.backupAgentName == null || BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS.equals(this.mCurrentPackage.applicationInfo.backupAgentName)) {
            this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 28, this.mCurrentPackage, 2, null);
            EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, packageName, "Package has no agent");
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            return;
        }
        PackageManagerBackupAgent.Metadata metaInfo = this.mPmAgent.getRestoredMetadata(packageName);
        PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        if (!BackupUtils.signaturesMatch(metaInfo.sigHashes, this.mCurrentPackage, pmi)) {
            Slog.w(BackupManagerService.TAG, "Signature mismatch restoring " + packageName);
            this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 29, this.mCurrentPackage, 3, null);
            EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, packageName, "Signature mismatch");
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            return;
        }
        this.mAgent = this.backupManagerService.bindToAgentSynchronous(this.mCurrentPackage.applicationInfo, 0);
        if (this.mAgent == null) {
            Slog.w(BackupManagerService.TAG, "Can't find backup agent for " + packageName);
            this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 30, this.mCurrentPackage, 3, null);
            EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, packageName, "Restore agent missing");
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            return;
        }
        this.mDidLaunch = true;
        try {
            initiateOneRestore(this.mCurrentPackage, metaInfo.versionCode);
            this.mCount++;
        } catch (Exception e) {
            Slog.e(BackupManagerService.TAG, "Error when attempting restore: " + e.toString());
            keyValueAgentErrorCleanup();
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    void initiateOneRestore(PackageInfo app, long appVersionCode) {
        String packageName = app.packageName;
        Slog.d(BackupManagerService.TAG, "initiateOneRestore packageName=" + packageName);
        File dataDir = this.backupManagerService.getDataDir();
        this.mBackupDataName = new File(dataDir, packageName + ".restore");
        File dataDir2 = this.backupManagerService.getDataDir();
        this.mStageName = new File(dataDir2, packageName + ".stage");
        File file = this.mStateDir;
        this.mNewStateName = new File(file, packageName + ".new");
        this.mSavedStateName = new File(this.mStateDir, packageName);
        boolean staging = packageName.equals(PackageManagerService.PLATFORM_PACKAGE_NAME) ^ true;
        File downloadFile = staging ? this.mStageName : this.mBackupDataName;
        try {
            IBackupTransport transport = this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask.initiateOneRestore()");
            ParcelFileDescriptor stage = ParcelFileDescriptor.open(downloadFile, 1006632960);
            if (transport.getRestoreData(stage) != 0) {
                Slog.e(BackupManagerService.TAG, "Error getting restore data for " + packageName);
                EventLog.writeEvent((int) EventLogTags.RESTORE_TRANSPORT_FAILURE, new Object[0]);
                stage.close();
                downloadFile.delete();
                executeNextState(UnifiedRestoreState.FINAL);
                return;
            }
            if (staging) {
                stage.close();
                stage = ParcelFileDescriptor.open(downloadFile, 268435456);
                this.mBackupData = ParcelFileDescriptor.open(this.mBackupDataName, 1006632960);
                BackupDataInput in = new BackupDataInput(stage.getFileDescriptor());
                BackupDataOutput out = new BackupDataOutput(this.mBackupData.getFileDescriptor());
                byte[] buffer = new byte[8192];
                while (in.readNextHeader()) {
                    String key = in.getKey();
                    int size = in.getDataSize();
                    if (key.equals(BackupManagerService.KEY_WIDGET_STATE)) {
                        Slog.i(BackupManagerService.TAG, "Restoring widget state for " + packageName);
                        this.mWidgetData = new byte[size];
                        in.readEntityData(this.mWidgetData, 0, size);
                    } else {
                        if (size > buffer.length) {
                            buffer = new byte[size];
                        }
                        in.readEntityData(buffer, 0, size);
                        out.writeEntityHeader(key, size);
                        out.writeEntityData(buffer, size);
                    }
                }
                this.mBackupData.close();
            }
            stage.close();
            this.mBackupData = ParcelFileDescriptor.open(this.mBackupDataName, 268435456);
            this.mNewState = ParcelFileDescriptor.open(this.mNewStateName, 1006632960);
            long restoreAgentTimeoutMillis = this.mAgentTimeoutParameters.getRestoreAgentTimeoutMillis();
            this.backupManagerService.prepareOperationTimeout(this.mEphemeralOpToken, restoreAgentTimeoutMillis, this, 1);
            this.mAgent.doRestore(this.mBackupData, appVersionCode, this.mNewState, this.mEphemeralOpToken, this.backupManagerService.getBackupManagerBinder());
        } catch (Exception e) {
            Slog.e(BackupManagerService.TAG, "Unable to call app for restore: " + packageName, e);
            EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, packageName, e.toString());
            keyValueAgentErrorCleanup();
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    private void restoreFull() {
        try {
            StreamFeederThread feeder = new StreamFeederThread();
            new Thread(feeder, "unified-stream-feeder").start();
        } catch (IOException e) {
            Slog.e(BackupManagerService.TAG, "Unable to construct pipes for stream restore!");
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    private void restoreFinished() {
        Slog.d(BackupManagerService.TAG, "restoreFinished packageName=" + this.mCurrentPackage.packageName);
        try {
            long restoreAgentFinishedTimeoutMillis = this.mAgentTimeoutParameters.getRestoreAgentFinishedTimeoutMillis();
            this.backupManagerService.prepareOperationTimeout(this.mEphemeralOpToken, restoreAgentFinishedTimeoutMillis, this, 1);
            this.mAgent.doRestoreFinished(this.mEphemeralOpToken, this.backupManagerService.getBackupManagerBinder());
        } catch (Exception e) {
            String packageName = this.mCurrentPackage.packageName;
            Slog.e(BackupManagerService.TAG, "Unable to finalize restore of " + packageName);
            EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, packageName, e.toString());
            keyValueAgentErrorCleanup();
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class StreamFeederThread extends RestoreEngine implements Runnable, BackupRestoreTask {
        FullRestoreEngine mEngine;
        EngineThread mEngineThread;
        private final int mEphemeralOpToken;
        final String TAG = "StreamFeederThread";
        ParcelFileDescriptor[] mTransportPipes = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] mEnginePipes = ParcelFileDescriptor.createPipe();

        public StreamFeederThread() throws IOException {
            this.mEphemeralOpToken = PerformUnifiedRestoreTask.this.backupManagerService.generateRandomIntegerToken();
            setRunning(true);
        }

        @Override // java.lang.Runnable
        public void run() {
            int status;
            UnifiedRestoreState nextState;
            UnifiedRestoreState nextState2;
            UnifiedRestoreState unifiedRestoreState = UnifiedRestoreState.RUNNING_QUEUE;
            int status2 = 0;
            EventLog.writeEvent((int) EventLogTags.FULL_RESTORE_PACKAGE, PerformUnifiedRestoreTask.this.mCurrentPackage.packageName);
            this.mEngine = new FullRestoreEngine(PerformUnifiedRestoreTask.this.backupManagerService, this, null, PerformUnifiedRestoreTask.this.mMonitor, PerformUnifiedRestoreTask.this.mCurrentPackage, false, false, this.mEphemeralOpToken);
            int i = 0;
            this.mEngineThread = new EngineThread(this.mEngine, this.mEnginePipes[0]);
            ParcelFileDescriptor eWriteEnd = this.mEnginePipes[1];
            ParcelFileDescriptor tReadEnd = this.mTransportPipes[0];
            ParcelFileDescriptor tWriteEnd = this.mTransportPipes[1];
            int bufferSize = 32768;
            byte[] buffer = new byte[32768];
            FileOutputStream engineOut = new FileOutputStream(eWriteEnd.getFileDescriptor());
            FileInputStream transportIn = new FileInputStream(tReadEnd.getFileDescriptor());
            new Thread(this.mEngineThread, "unified-restore-engine").start();
            try {
                try {
                    IBackupTransport transport = PerformUnifiedRestoreTask.this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask$StreamFeederThread.run()");
                    while (true) {
                        if (status2 != 0) {
                            break;
                        }
                        int result = transport.getNextFullRestoreDataChunk(tWriteEnd);
                        if (result > 0) {
                            if (result > bufferSize) {
                                bufferSize = result;
                                byte[] buffer2 = new byte[bufferSize];
                                buffer = buffer2;
                            }
                            int toCopy = result;
                            while (toCopy > 0) {
                                int n = transportIn.read(buffer, i, toCopy);
                                engineOut.write(buffer, i, n);
                                toCopy -= n;
                            }
                        } else if (result == -1) {
                            status2 = 0;
                            break;
                        } else {
                            Slog.e("StreamFeederThread", "Error " + result + " streaming restore for " + PerformUnifiedRestoreTask.this.mCurrentPackage.packageName);
                            EventLog.writeEvent((int) EventLogTags.RESTORE_TRANSPORT_FAILURE, new Object[0]);
                            status2 = result;
                        }
                        i = 0;
                    }
                    IoUtils.closeQuietly(this.mEnginePipes[1]);
                    IoUtils.closeQuietly(this.mTransportPipes[0]);
                    IoUtils.closeQuietly(this.mTransportPipes[1]);
                    this.mEngineThread.waitForResult();
                    IoUtils.closeQuietly(this.mEnginePipes[0]);
                    PerformUnifiedRestoreTask.this.mDidLaunch = this.mEngine.getAgent() != null;
                    if (status2 == 0) {
                        nextState = UnifiedRestoreState.RESTORE_FINISHED;
                        PerformUnifiedRestoreTask.this.mAgent = this.mEngine.getAgent();
                        PerformUnifiedRestoreTask.this.mWidgetData = this.mEngine.getWidgetData();
                    } else {
                        try {
                            IBackupTransport transport2 = PerformUnifiedRestoreTask.this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask$StreamFeederThread.run()");
                            transport2.abortFullRestore();
                        } catch (Exception e) {
                            Slog.e("StreamFeederThread", "Transport threw from abortFullRestore: " + e.getMessage());
                            status2 = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                        }
                        PerformUnifiedRestoreTask.this.backupManagerService.clearApplicationDataSynchronous(PerformUnifiedRestoreTask.this.mCurrentPackage.packageName, false);
                        nextState = status2 == -1000 ? UnifiedRestoreState.FINAL : UnifiedRestoreState.RUNNING_QUEUE;
                    }
                } catch (IOException e2) {
                    Slog.e("StreamFeederThread", "Unable to route data for restore");
                    EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, PerformUnifiedRestoreTask.this.mCurrentPackage.packageName, "I/O error on pipes");
                    status = -1003;
                    IoUtils.closeQuietly(this.mEnginePipes[1]);
                    IoUtils.closeQuietly(this.mTransportPipes[0]);
                    IoUtils.closeQuietly(this.mTransportPipes[1]);
                    this.mEngineThread.waitForResult();
                    IoUtils.closeQuietly(this.mEnginePipes[0]);
                    PerformUnifiedRestoreTask.this.mDidLaunch = this.mEngine.getAgent() != null;
                    if (-1003 == 0) {
                        nextState = UnifiedRestoreState.RESTORE_FINISHED;
                        PerformUnifiedRestoreTask.this.mAgent = this.mEngine.getAgent();
                        PerformUnifiedRestoreTask.this.mWidgetData = this.mEngine.getWidgetData();
                    } else {
                        try {
                            IBackupTransport transport3 = PerformUnifiedRestoreTask.this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask$StreamFeederThread.run()");
                            transport3.abortFullRestore();
                        } catch (Exception e3) {
                            Slog.e("StreamFeederThread", "Transport threw from abortFullRestore: " + e3.getMessage());
                            status = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                        }
                        PerformUnifiedRestoreTask.this.backupManagerService.clearApplicationDataSynchronous(PerformUnifiedRestoreTask.this.mCurrentPackage.packageName, false);
                        nextState = status == -1000 ? UnifiedRestoreState.FINAL : UnifiedRestoreState.RUNNING_QUEUE;
                    }
                    PerformUnifiedRestoreTask.this.executeNextState(nextState);
                    setRunning(false);
                } catch (Exception e4) {
                    Slog.e("StreamFeederThread", "Transport failed during restore: " + e4.getMessage());
                    EventLog.writeEvent((int) EventLogTags.RESTORE_TRANSPORT_FAILURE, new Object[0]);
                    status = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                    IoUtils.closeQuietly(this.mEnginePipes[1]);
                    IoUtils.closeQuietly(this.mTransportPipes[0]);
                    IoUtils.closeQuietly(this.mTransportPipes[1]);
                    this.mEngineThread.waitForResult();
                    IoUtils.closeQuietly(this.mEnginePipes[0]);
                    PerformUnifiedRestoreTask.this.mDidLaunch = this.mEngine.getAgent() != null;
                    if (-1000 == 0) {
                        nextState = UnifiedRestoreState.RESTORE_FINISHED;
                        PerformUnifiedRestoreTask.this.mAgent = this.mEngine.getAgent();
                        PerformUnifiedRestoreTask.this.mWidgetData = this.mEngine.getWidgetData();
                    } else {
                        try {
                            IBackupTransport transport4 = PerformUnifiedRestoreTask.this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask$StreamFeederThread.run()");
                            transport4.abortFullRestore();
                        } catch (Exception e5) {
                            Slog.e("StreamFeederThread", "Transport threw from abortFullRestore: " + e5.getMessage());
                            status = 64536;
                        }
                        PerformUnifiedRestoreTask.this.backupManagerService.clearApplicationDataSynchronous(PerformUnifiedRestoreTask.this.mCurrentPackage.packageName, false);
                        nextState = status == -1000 ? UnifiedRestoreState.FINAL : UnifiedRestoreState.RUNNING_QUEUE;
                    }
                    PerformUnifiedRestoreTask.this.executeNextState(nextState);
                    setRunning(false);
                }
                PerformUnifiedRestoreTask.this.executeNextState(nextState);
                setRunning(false);
            } catch (Throwable th) {
                IoUtils.closeQuietly(this.mEnginePipes[1]);
                IoUtils.closeQuietly(this.mTransportPipes[0]);
                IoUtils.closeQuietly(this.mTransportPipes[1]);
                this.mEngineThread.waitForResult();
                IoUtils.closeQuietly(this.mEnginePipes[0]);
                PerformUnifiedRestoreTask.this.mDidLaunch = this.mEngine.getAgent() != null;
                if (status2 != 0) {
                    try {
                        IBackupTransport transport5 = PerformUnifiedRestoreTask.this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask$StreamFeederThread.run()");
                        transport5.abortFullRestore();
                    } catch (Exception e6) {
                        Slog.e("StreamFeederThread", "Transport threw from abortFullRestore: " + e6.getMessage());
                        status2 = -1000;
                    }
                    PerformUnifiedRestoreTask.this.backupManagerService.clearApplicationDataSynchronous(PerformUnifiedRestoreTask.this.mCurrentPackage.packageName, false);
                    nextState2 = status2 == -1000 ? UnifiedRestoreState.FINAL : UnifiedRestoreState.RUNNING_QUEUE;
                } else {
                    nextState2 = UnifiedRestoreState.RESTORE_FINISHED;
                    PerformUnifiedRestoreTask.this.mAgent = this.mEngine.getAgent();
                    PerformUnifiedRestoreTask.this.mWidgetData = this.mEngine.getWidgetData();
                }
                PerformUnifiedRestoreTask.this.executeNextState(nextState2);
                setRunning(false);
                throw th;
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
            PerformUnifiedRestoreTask.this.backupManagerService.removeOperation(this.mEphemeralOpToken);
            Slog.w("StreamFeederThread", "Full-data restore target timed out; shutting down");
            PerformUnifiedRestoreTask.this.mMonitor = BackupManagerMonitorUtils.monitorEvent(PerformUnifiedRestoreTask.this.mMonitor, 45, PerformUnifiedRestoreTask.this.mCurrentPackage, 2, null);
            this.mEngineThread.handleTimeout();
            IoUtils.closeQuietly(this.mEnginePipes[1]);
            this.mEnginePipes[1] = null;
            IoUtils.closeQuietly(this.mEnginePipes[0]);
            this.mEnginePipes[0] = null;
        }
    }

    /* loaded from: classes.dex */
    class EngineThread implements Runnable {
        FullRestoreEngine mEngine;
        FileInputStream mEngineStream;

        EngineThread(FullRestoreEngine engine, ParcelFileDescriptor engineSocket) {
            this.mEngine = engine;
            engine.setRunning(true);
            this.mEngineStream = new FileInputStream(engineSocket.getFileDescriptor(), true);
        }

        public boolean isRunning() {
            return this.mEngine.isRunning();
        }

        public int waitForResult() {
            return this.mEngine.waitForResult();
        }

        @Override // java.lang.Runnable
        public void run() {
            while (this.mEngine.isRunning()) {
                try {
                    this.mEngine.restoreOneFile(this.mEngineStream, false, this.mEngine.mBuffer, this.mEngine.mOnlyPackage, this.mEngine.mAllowApks, this.mEngine.mEphemeralOpToken, this.mEngine.mMonitor);
                } finally {
                    IoUtils.closeQuietly(this.mEngineStream);
                }
            }
        }

        public void handleTimeout() {
            IoUtils.closeQuietly(this.mEngineStream);
            this.mEngine.handleTimeout();
        }
    }

    private void finalizeRestore() {
        try {
            IBackupTransport transport = this.mTransportClient.connectOrThrow("PerformUnifiedRestoreTask.finalizeRestore()");
            transport.finishRestore();
        } catch (Exception e) {
            Slog.e(BackupManagerService.TAG, "Error finishing restore", e);
        }
        if (this.mObserver != null) {
            try {
                this.mObserver.restoreFinished(this.mStatus);
            } catch (RemoteException e2) {
                Slog.d(BackupManagerService.TAG, "Restore observer died at restoreFinished");
            }
        }
        this.backupManagerService.getBackupHandler().removeMessages(8);
        if (this.mPmToken > 0) {
            try {
                this.backupManagerService.getPackageManagerBinder().finishPackageInstall(this.mPmToken, this.mDidLaunch);
            } catch (RemoteException e3) {
            }
        } else {
            long restoreAgentTimeoutMillis = this.mAgentTimeoutParameters.getRestoreAgentTimeoutMillis();
            this.backupManagerService.getBackupHandler().sendEmptyMessageDelayed(8, restoreAgentTimeoutMillis);
        }
        AppWidgetBackupBridge.restoreFinished(0);
        if (this.mIsSystemRestore && this.mPmAgent != null) {
            this.backupManagerService.setAncestralPackages(this.mPmAgent.getRestoredPackages());
            this.backupManagerService.setAncestralToken(this.mToken);
            this.backupManagerService.writeRestoreTokens();
        }
        synchronized (this.backupManagerService.getPendingRestores()) {
            if (this.backupManagerService.getPendingRestores().size() <= 0) {
                this.backupManagerService.setRestoreInProgress(false);
            } else {
                Slog.d(BackupManagerService.TAG, "Starting next pending restore.");
                PerformUnifiedRestoreTask task = this.backupManagerService.getPendingRestores().remove();
                this.backupManagerService.getBackupHandler().sendMessage(this.backupManagerService.getBackupHandler().obtainMessage(20, task));
            }
        }
        Slog.i(BackupManagerService.TAG, "Restore complete.");
        this.mListener.onFinished("PerformUnifiedRestoreTask.finalizeRestore()");
    }

    void keyValueAgentErrorCleanup() {
        this.backupManagerService.clearApplicationDataSynchronous(this.mCurrentPackage.packageName, false);
        keyValueAgentCleanup();
    }

    void keyValueAgentCleanup() {
        this.mBackupDataName.delete();
        this.mStageName.delete();
        try {
            if (this.mBackupData != null) {
                this.mBackupData.close();
            }
        } catch (IOException e) {
        }
        try {
            if (this.mNewState != null) {
                this.mNewState.close();
            }
        } catch (IOException e2) {
        }
        this.mNewState = null;
        this.mBackupData = null;
        this.mNewStateName.delete();
        if (this.mCurrentPackage.applicationInfo != null) {
            try {
                this.backupManagerService.getActivityManager().unbindBackupAgent(this.mCurrentPackage.applicationInfo);
                int appFlags = this.mCurrentPackage.applicationInfo.flags;
                boolean killAfterRestore = this.mCurrentPackage.applicationInfo.uid >= 10000 && (this.mRestoreDescription.getDataType() == 2 || (65536 & appFlags) != 0);
                if (this.mTargetPackage == null && killAfterRestore) {
                    Slog.d(BackupManagerService.TAG, "Restore complete, killing host process of " + this.mCurrentPackage.applicationInfo.processName);
                    this.backupManagerService.getActivityManager().killApplicationProcess(this.mCurrentPackage.applicationInfo.processName, this.mCurrentPackage.applicationInfo.uid);
                }
            } catch (RemoteException e3) {
            }
        }
        this.backupManagerService.getBackupHandler().removeMessages(18, this);
    }

    @Override // com.android.server.backup.BackupRestoreTask
    public void operationComplete(long unusedResult) {
        UnifiedRestoreState nextState;
        this.backupManagerService.removeOperation(this.mEphemeralOpToken);
        int i = AnonymousClass1.$SwitchMap$com$android$server$backup$restore$UnifiedRestoreState[this.mState.ordinal()];
        if (i == 1) {
            nextState = UnifiedRestoreState.RUNNING_QUEUE;
        } else {
            switch (i) {
                case 3:
                case 4:
                    nextState = UnifiedRestoreState.RESTORE_FINISHED;
                    break;
                case 5:
                    int size = (int) this.mBackupDataName.length();
                    EventLog.writeEvent((int) EventLogTags.RESTORE_PACKAGE, this.mCurrentPackage.packageName, Integer.valueOf(size));
                    keyValueAgentCleanup();
                    if (this.mWidgetData != null) {
                        this.backupManagerService.restoreWidgetData(this.mCurrentPackage.packageName, this.mWidgetData);
                    }
                    UnifiedRestoreState nextState2 = UnifiedRestoreState.RUNNING_QUEUE;
                    nextState = nextState2;
                    break;
                default:
                    Slog.e(BackupManagerService.TAG, "Unexpected restore callback into state " + this.mState);
                    keyValueAgentErrorCleanup();
                    nextState = UnifiedRestoreState.FINAL;
                    break;
            }
        }
        executeNextState(nextState);
    }

    @Override // com.android.server.backup.BackupRestoreTask
    public void handleCancel(boolean cancelAll) {
        this.backupManagerService.removeOperation(this.mEphemeralOpToken);
        Slog.e(BackupManagerService.TAG, "Timeout restoring application " + this.mCurrentPackage.packageName);
        this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 31, this.mCurrentPackage, 2, null);
        EventLog.writeEvent((int) EventLogTags.RESTORE_AGENT_FAILURE, this.mCurrentPackage.packageName, "restore timeout");
        keyValueAgentErrorCleanup();
        executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
    }

    void executeNextState(UnifiedRestoreState nextState) {
        this.mState = nextState;
        Message msg = this.backupManagerService.getBackupHandler().obtainMessage(20, this);
        this.backupManagerService.getBackupHandler().sendMessage(msg);
    }

    void sendStartRestore(int numPackages) {
        if (this.mObserver != null) {
            try {
                this.mObserver.restoreStarting(numPackages);
            } catch (RemoteException e) {
                Slog.w(BackupManagerService.TAG, "Restore observer went away: startRestore");
                this.mObserver = null;
            }
        }
    }

    void sendOnRestorePackage(String name) {
        if (this.mObserver != null) {
            try {
                this.mObserver.onUpdate(this.mCount, name);
            } catch (RemoteException e) {
                Slog.d(BackupManagerService.TAG, "Restore observer died in onUpdate");
                this.mObserver = null;
            }
        }
    }

    void sendEndRestore() {
        if (this.mObserver != null) {
            try {
                this.mObserver.restoreFinished(this.mStatus);
            } catch (RemoteException e) {
                Slog.w(BackupManagerService.TAG, "Restore observer went away: endRestore");
                this.mObserver = null;
            }
        }
    }
}
