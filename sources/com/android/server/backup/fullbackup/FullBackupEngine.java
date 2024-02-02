package com.android.server.backup.fullbackup;

import android.app.IBackupAgent;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;
import android.util.StringBuilderPrinter;
import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.utils.FullBackupUtils;
import com.android.server.job.JobSchedulerShellCommand;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
/* loaded from: classes.dex */
public class FullBackupEngine {
    private BackupManagerService backupManagerService;
    IBackupAgent mAgent;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    boolean mIncludeApks;
    private final int mOpToken;
    OutputStream mOutput;
    PackageInfo mPkg;
    FullBackupPreflight mPreflightHook;
    private final long mQuota;
    BackupRestoreTask mTimeoutMonitor;
    private final int mTransportFlags;
    File mFilesDir = new File("/data/system");
    File mManifestFile = new File(this.mFilesDir, BackupManagerService.BACKUP_MANIFEST_FILENAME);
    File mMetadataFile = new File(this.mFilesDir, BackupManagerService.BACKUP_METADATA_FILENAME);

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class FullBackupRunner implements Runnable {
        IBackupAgent mAgent;
        PackageInfo mPackage;
        ParcelFileDescriptor mPipe;
        boolean mSendApk;
        int mToken;
        byte[] mWidgetData;
        boolean mWriteManifest;

        FullBackupRunner(PackageInfo pack, IBackupAgent agent, ParcelFileDescriptor pipe, int token, boolean sendApk, boolean writeManifest, byte[] widgetData) throws IOException {
            this.mPackage = pack;
            this.mWidgetData = widgetData;
            this.mAgent = agent;
            this.mPipe = ParcelFileDescriptor.dup(pipe.getFileDescriptor());
            this.mToken = token;
            this.mSendApk = sendApk;
            this.mWriteManifest = writeManifest;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                try {
                    try {
                        FullBackupDataOutput output = new FullBackupDataOutput(this.mPipe, -1L, FullBackupEngine.this.mTransportFlags);
                        if (this.mWriteManifest) {
                            boolean writeWidgetData = this.mWidgetData != null;
                            FullBackupUtils.writeAppManifest(this.mPackage, FullBackupEngine.this.backupManagerService.getPackageManager(), FullBackupEngine.this.mManifestFile, this.mSendApk, writeWidgetData);
                            FullBackup.backupToTar(this.mPackage.packageName, (String) null, (String) null, FullBackupEngine.this.mFilesDir.getAbsolutePath(), FullBackupEngine.this.mManifestFile.getAbsolutePath(), output);
                            FullBackupEngine.this.mManifestFile.delete();
                            if (writeWidgetData) {
                                FullBackupEngine.this.writeMetadata(this.mPackage, FullBackupEngine.this.mMetadataFile, this.mWidgetData);
                                FullBackup.backupToTar(this.mPackage.packageName, (String) null, (String) null, FullBackupEngine.this.mFilesDir.getAbsolutePath(), FullBackupEngine.this.mMetadataFile.getAbsolutePath(), output);
                                FullBackupEngine.this.mMetadataFile.delete();
                            }
                        }
                        if (this.mSendApk) {
                            FullBackupEngine.this.writeApkToBackup(this.mPackage, output);
                        }
                        boolean isSharedStorage = this.mPackage.packageName.equals(BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE);
                        long timeout = isSharedStorage ? FullBackupEngine.this.mAgentTimeoutParameters.getSharedBackupAgentTimeoutMillis() : FullBackupEngine.this.mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
                        Slog.d(BackupManagerService.TAG, "Calling doFullBackup() on " + this.mPackage.packageName);
                        FullBackupEngine.this.backupManagerService.prepareOperationTimeout(this.mToken, timeout, FullBackupEngine.this.mTimeoutMonitor, 0);
                        this.mAgent.doFullBackup(this.mPipe, FullBackupEngine.this.mQuota, this.mToken, FullBackupEngine.this.backupManagerService.getBackupManagerBinder(), FullBackupEngine.this.mTransportFlags);
                        this.mPipe.close();
                    } catch (IOException e) {
                    }
                } catch (RemoteException e2) {
                    Slog.e(BackupManagerService.TAG, "Remote agent vanished during full backup of " + this.mPackage.packageName);
                    this.mPipe.close();
                } catch (IOException e3) {
                    Slog.e(BackupManagerService.TAG, "Error running full backup for " + this.mPackage.packageName);
                    this.mPipe.close();
                }
            } catch (Throwable th) {
                try {
                    this.mPipe.close();
                } catch (IOException e4) {
                }
                throw th;
            }
        }
    }

    public FullBackupEngine(BackupManagerService backupManagerService, OutputStream output, FullBackupPreflight preflightHook, PackageInfo pkg, boolean alsoApks, BackupRestoreTask timeoutMonitor, long quota, int opToken, int transportFlags) {
        this.backupManagerService = backupManagerService;
        this.mOutput = output;
        this.mPreflightHook = preflightHook;
        this.mPkg = pkg;
        this.mIncludeApks = alsoApks;
        this.mTimeoutMonitor = timeoutMonitor;
        this.mQuota = quota;
        this.mOpToken = opToken;
        this.mTransportFlags = transportFlags;
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
    }

    public int preflightCheck() throws RemoteException {
        if (this.mPreflightHook == null) {
            return 0;
        }
        if (initializeAgent()) {
            int result = this.mPreflightHook.preflightFullBackup(this.mPkg, this.mAgent);
            return result;
        }
        Slog.w(BackupManagerService.TAG, "Unable to bind to full agent for " + this.mPkg.packageName);
        return -1003;
    }

    public int backupOnePackage() throws RemoteException {
        ParcelFileDescriptor[] pipes;
        int i;
        int result = -1003;
        if (!initializeAgent()) {
            Slog.w(BackupManagerService.TAG, "Unable to bind to full agent for " + this.mPkg.packageName);
        } else {
            ParcelFileDescriptor[] pipes2 = null;
            try {
                try {
                    pipes = ParcelFileDescriptor.createPipe();
                } catch (IOException e) {
                    e = e;
                }
            } catch (Throwable th) {
                th = th;
                pipes = pipes2;
            }
            try {
                ApplicationInfo app = this.mPkg.applicationInfo;
                boolean isSharedStorage = this.mPkg.packageName.equals(BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE);
                boolean sendApk = this.mIncludeApks && !isSharedStorage && (app.privateFlags & 4) == 0 && ((app.flags & 1) == 0 || (app.flags & 128) != 0);
                byte[] widgetBlob = AppWidgetBackupBridge.getWidgetState(this.mPkg.packageName, 0);
                FullBackupRunner runner = new FullBackupRunner(this.mPkg, this.mAgent, pipes[1], this.mOpToken, sendApk, !isSharedStorage, widgetBlob);
                pipes[1].close();
                pipes[1] = null;
                Thread t = new Thread(runner, "app-data-runner");
                t.start();
                FullBackupUtils.routeSocketDataToOutput(pipes[0], this.mOutput);
                if (!this.backupManagerService.waitUntilOperationComplete(this.mOpToken)) {
                    Slog.e(BackupManagerService.TAG, "Full backup failed on package " + this.mPkg.packageName);
                } else {
                    result = 0;
                }
                try {
                    this.mOutput.flush();
                    if (pipes != null) {
                        if (pipes[0] != null) {
                            pipes[0].close();
                        }
                        if (pipes[1] != null) {
                            pipes[1].close();
                        }
                    }
                } catch (IOException e2) {
                    Slog.w(BackupManagerService.TAG, "Error bringing down backup stack");
                    i = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                    result = i;
                    tearDown();
                    return result;
                }
            } catch (IOException e3) {
                e = e3;
                pipes2 = pipes;
                Slog.e(BackupManagerService.TAG, "Error backing up " + this.mPkg.packageName + ": " + e.getMessage());
                try {
                    this.mOutput.flush();
                    if (pipes2 != null) {
                        if (pipes2[0] != null) {
                            pipes2[0].close();
                        }
                        if (pipes2[1] != null) {
                            pipes2[1].close();
                        }
                    }
                    result = -1003;
                } catch (IOException e4) {
                    Slog.w(BackupManagerService.TAG, "Error bringing down backup stack");
                    i = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                    result = i;
                    tearDown();
                    return result;
                }
                tearDown();
                return result;
            } catch (Throwable th2) {
                th = th2;
                Throwable th3 = th;
                try {
                    this.mOutput.flush();
                    if (pipes != null) {
                        if (pipes[0] != null) {
                            pipes[0].close();
                        }
                        if (pipes[1] != null) {
                            pipes[1].close();
                        }
                    }
                } catch (IOException e5) {
                    Slog.w(BackupManagerService.TAG, "Error bringing down backup stack");
                }
                throw th3;
            }
        }
        tearDown();
        return result;
    }

    public void sendQuotaExceeded(long backupDataBytes, long quotaBytes) {
        if (initializeAgent()) {
            try {
                this.mAgent.doQuotaExceeded(backupDataBytes, quotaBytes);
            } catch (RemoteException e) {
                Slog.e(BackupManagerService.TAG, "Remote exception while telling agent about quota exceeded");
            }
        }
    }

    private boolean initializeAgent() {
        if (this.mAgent == null) {
            this.mAgent = this.backupManagerService.bindToAgentSynchronous(this.mPkg.applicationInfo, 1);
        }
        return this.mAgent != null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeApkToBackup(PackageInfo pkg, FullBackupDataOutput output) {
        File[] obbFiles;
        String appSourceDir = pkg.applicationInfo.getBaseCodePath();
        String apkDir = new File(appSourceDir).getParent();
        FullBackup.backupToTar(pkg.packageName, "a", (String) null, apkDir, appSourceDir, output);
        Environment.UserEnvironment userEnv = new Environment.UserEnvironment(0);
        File obbDir = userEnv.buildExternalStorageAppObbDirs(pkg.packageName)[0];
        if (obbDir != null && (obbFiles = obbDir.listFiles()) != null) {
            String obbDirName = obbDir.getAbsolutePath();
            for (File obb : obbFiles) {
                FullBackup.backupToTar(pkg.packageName, "obb", (String) null, obbDirName, obb.getAbsolutePath(), output);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeMetadata(PackageInfo pkg, File destination, byte[] widgetData) throws IOException {
        StringBuilder b = new StringBuilder(512);
        StringBuilderPrinter printer = new StringBuilderPrinter(b);
        printer.println(Integer.toString(1));
        printer.println(pkg.packageName);
        FileOutputStream fout = new FileOutputStream(destination);
        BufferedOutputStream bout = new BufferedOutputStream(fout);
        DataOutputStream out = new DataOutputStream(bout);
        bout.write(b.toString().getBytes());
        if (widgetData != null && widgetData.length > 0) {
            out.writeInt(BackupManagerService.BACKUP_WIDGET_METADATA_TOKEN);
            out.writeInt(widgetData.length);
            out.write(widgetData);
        }
        bout.flush();
        out.close();
        destination.setLastModified(0L);
    }

    private void tearDown() {
        if (this.mPkg != null) {
            this.backupManagerService.tearDownAgentAndKill(this.mPkg.applicationInfo);
        }
    }
}
