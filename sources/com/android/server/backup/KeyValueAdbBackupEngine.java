package com.android.server.backup;

import android.app.IBackupAgent;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SELinux;
import android.util.Slog;
import com.android.internal.util.Preconditions;
import com.android.server.backup.fullbackup.AppMetadataBackupWriter;
import com.android.server.backup.remote.ServiceBackupCallback;
import com.android.server.job.controllers.JobStatus;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public class KeyValueAdbBackupEngine {
    private static final String BACKUP_KEY_VALUE_BACKUP_DATA_FILENAME_SUFFIX = ".data";
    private static final String BACKUP_KEY_VALUE_BLANK_STATE_FILENAME = "blank_state";
    private static final String BACKUP_KEY_VALUE_DIRECTORY_NAME = "key_value_dir";
    private static final String BACKUP_KEY_VALUE_NEW_STATE_FILENAME_SUFFIX = ".new";
    private static final boolean DEBUG = false;
    private static final String TAG = "KeyValueAdbBackupEngine";
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private ParcelFileDescriptor mBackupData;
    private final File mBackupDataName;
    private UserBackupManagerService mBackupManagerService;
    private final File mBlankStateName;
    private final PackageInfo mCurrentPackage;
    private final File mDataDir;
    private final File mManifestFile;
    private ParcelFileDescriptor mNewState;
    private final File mNewStateName;
    private final OutputStream mOutput;
    private final PackageManager mPackageManager;
    private ParcelFileDescriptor mSavedState;
    private final File mStateDir;

    public KeyValueAdbBackupEngine(OutputStream output, PackageInfo packageInfo, UserBackupManagerService backupManagerService, PackageManager packageManager, File baseStateDir, File dataDir) {
        this.mOutput = output;
        this.mCurrentPackage = packageInfo;
        this.mBackupManagerService = backupManagerService;
        this.mPackageManager = packageManager;
        this.mDataDir = dataDir;
        this.mStateDir = new File(baseStateDir, BACKUP_KEY_VALUE_DIRECTORY_NAME);
        this.mStateDir.mkdirs();
        String pkg = this.mCurrentPackage.packageName;
        this.mBlankStateName = new File(this.mStateDir, BACKUP_KEY_VALUE_BLANK_STATE_FILENAME);
        File file = this.mDataDir;
        this.mBackupDataName = new File(file, pkg + ".data");
        File file2 = this.mStateDir;
        this.mNewStateName = new File(file2, pkg + ".new");
        this.mManifestFile = new File(this.mDataDir, UserBackupManagerService.BACKUP_MANIFEST_FILENAME);
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
    }

    public void backupOnePackage() throws IOException {
        IBackupAgent agent;
        ApplicationInfo targetApp = this.mCurrentPackage.applicationInfo;
        try {
            try {
                prepareBackupFiles(this.mCurrentPackage.packageName);
                agent = bindToAgent(targetApp);
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Failed creating files for package " + this.mCurrentPackage.packageName + " will ignore package. " + e);
            }
            if (agent == null) {
                Slog.e(TAG, "Failed binding to BackupAgent for package " + this.mCurrentPackage.packageName);
            } else if (invokeAgentForAdbBackup(this.mCurrentPackage.packageName, agent)) {
                writeBackupData();
            } else {
                Slog.e(TAG, "Backup Failed for package " + this.mCurrentPackage.packageName);
            }
        } finally {
            cleanup();
        }
    }

    private void prepareBackupFiles(String packageName) throws FileNotFoundException {
        this.mSavedState = ParcelFileDescriptor.open(this.mBlankStateName, 402653184);
        this.mBackupData = ParcelFileDescriptor.open(this.mBackupDataName, 1006632960);
        if (!SELinux.restorecon(this.mBackupDataName)) {
            Slog.e(TAG, "SELinux restorecon failed on " + this.mBackupDataName);
        }
        this.mNewState = ParcelFileDescriptor.open(this.mNewStateName, 1006632960);
    }

    private IBackupAgent bindToAgent(ApplicationInfo targetApp) {
        try {
            return this.mBackupManagerService.bindToAgentSynchronous(targetApp, 0);
        } catch (SecurityException e) {
            Slog.e(TAG, "error in binding to agent for package " + targetApp.packageName + ". " + e);
            return null;
        }
    }

    private boolean invokeAgentForAdbBackup(String packageName, IBackupAgent agent) {
        int token = this.mBackupManagerService.generateRandomIntegerToken();
        long kvBackupAgentTimeoutMillis = this.mAgentTimeoutParameters.getKvBackupAgentTimeoutMillis();
        try {
            this.mBackupManagerService.prepareOperationTimeout(token, kvBackupAgentTimeoutMillis, null, 0);
            agent.doBackup(this.mSavedState, this.mBackupData, this.mNewState, (long) JobStatus.NO_LATEST_RUNTIME, new ServiceBackupCallback(this.mBackupManagerService.getBackupManagerBinder(), token), 0);
            if (!this.mBackupManagerService.waitUntilOperationComplete(token)) {
                Slog.e(TAG, "Key-value backup failed on package " + packageName);
                return false;
            }
            return true;
        } catch (RemoteException e) {
            Slog.e(TAG, "Error invoking agent for backup on " + packageName + ". " + e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class KeyValueAdbBackupDataCopier implements Runnable {
        private final PackageInfo mPackage;
        private final ParcelFileDescriptor mPipe;
        private final int mToken;

        KeyValueAdbBackupDataCopier(PackageInfo pack, ParcelFileDescriptor pipe, int token) throws IOException {
            this.mPackage = pack;
            this.mPipe = ParcelFileDescriptor.dup(pipe.getFileDescriptor());
            this.mToken = token;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                try {
                    FullBackupDataOutput output = new FullBackupDataOutput(this.mPipe);
                    AppMetadataBackupWriter writer = new AppMetadataBackupWriter(output, KeyValueAdbBackupEngine.this.mPackageManager);
                    writer.backupManifest(this.mPackage, KeyValueAdbBackupEngine.this.mManifestFile, KeyValueAdbBackupEngine.this.mDataDir, "k", null, false);
                    KeyValueAdbBackupEngine.this.mManifestFile.delete();
                    FullBackup.backupToTar(this.mPackage.packageName, "k", (String) null, KeyValueAdbBackupEngine.this.mDataDir.getAbsolutePath(), KeyValueAdbBackupEngine.this.mBackupDataName.getAbsolutePath(), output);
                    try {
                        FileOutputStream out = new FileOutputStream(this.mPipe.getFileDescriptor());
                        byte[] buf = new byte[4];
                        out.write(buf);
                    } catch (IOException e) {
                        Slog.e(KeyValueAdbBackupEngine.TAG, "Unable to finalize backup stream!");
                    }
                    try {
                        KeyValueAdbBackupEngine.this.mBackupManagerService.getBackupManagerBinder().opComplete(this.mToken, 0L);
                    } catch (RemoteException e2) {
                    }
                } catch (IOException e3) {
                    Slog.e(KeyValueAdbBackupEngine.TAG, "Error running full backup for " + this.mPackage.packageName + ". " + e3);
                }
            } finally {
                IoUtils.closeQuietly(this.mPipe);
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:25:0x00b4  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void writeBackupData() throws java.io.IOException {
        /*
            r13 = this;
            java.lang.String r0 = "KeyValueAdbBackupEngine"
            com.android.server.backup.UserBackupManagerService r1 = r13.mBackupManagerService
            int r1 = r1.generateRandomIntegerToken()
            com.android.server.backup.BackupAgentTimeoutParameters r2 = r13.mAgentTimeoutParameters
            long r8 = r2.getKvBackupAgentTimeoutMillis()
            r2 = 0
            r10 = 0
            r11 = 1
            android.os.ParcelFileDescriptor[] r3 = android.os.ParcelFileDescriptor.createPipe()     // Catch: java.lang.Throwable -> L76 java.io.IOException -> L79
            r12 = r3
            com.android.server.backup.UserBackupManagerService r2 = r13.mBackupManagerService     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r6 = 0
            r7 = 0
            r3 = r1
            r4 = r8
            r2.prepareOperationTimeout(r3, r4, r6, r7)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            com.android.server.backup.KeyValueAdbBackupEngine$KeyValueAdbBackupDataCopier r2 = new com.android.server.backup.KeyValueAdbBackupEngine$KeyValueAdbBackupDataCopier     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            android.content.pm.PackageInfo r3 = r13.mCurrentPackage     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r4 = r12[r11]     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r2.<init>(r3, r4, r1)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r3 = r12[r11]     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r3.close()     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r3 = 0
            r12[r11] = r3     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            java.lang.Thread r3 = new java.lang.Thread     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            java.lang.String r4 = "key-value-app-data-runner"
            r3.<init>(r2, r4)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r3.start()     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r4 = r12[r10]     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            java.io.OutputStream r5 = r13.mOutput     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            com.android.server.backup.utils.FullBackupUtils.routeSocketDataToOutput(r4, r5)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            com.android.server.backup.UserBackupManagerService r4 = r13.mBackupManagerService     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            boolean r4 = r4.waitUntilOperationComplete(r1)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            if (r4 != 0) goto L61
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r4.<init>()     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            java.lang.String r5 = "Full backup failed on package "
            r4.append(r5)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            android.content.pm.PackageInfo r5 = r13.mCurrentPackage     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            java.lang.String r5 = r5.packageName     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            r4.append(r5)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
            android.util.Slog.e(r0, r4)     // Catch: java.lang.Throwable -> L72 java.io.IOException -> L74
        L61:
            java.io.OutputStream r0 = r13.mOutput
            r0.flush()
            r0 = r12[r10]
            libcore.io.IoUtils.closeQuietly(r0)
            r0 = r12[r11]
        L6e:
            libcore.io.IoUtils.closeQuietly(r0)
            goto Lac
        L72:
            r0 = move-exception
            goto Lad
        L74:
            r2 = move-exception
            goto L7c
        L76:
            r0 = move-exception
            r12 = r2
            goto Lad
        L79:
            r3 = move-exception
            r12 = r2
            r2 = r3
        L7c:
            java.lang.StringBuilder r3 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L72
            r3.<init>()     // Catch: java.lang.Throwable -> L72
            java.lang.String r4 = "Error backing up "
            r3.append(r4)     // Catch: java.lang.Throwable -> L72
            android.content.pm.PackageInfo r4 = r13.mCurrentPackage     // Catch: java.lang.Throwable -> L72
            java.lang.String r4 = r4.packageName     // Catch: java.lang.Throwable -> L72
            r3.append(r4)     // Catch: java.lang.Throwable -> L72
            java.lang.String r4 = ": "
            r3.append(r4)     // Catch: java.lang.Throwable -> L72
            r3.append(r2)     // Catch: java.lang.Throwable -> L72
            java.lang.String r3 = r3.toString()     // Catch: java.lang.Throwable -> L72
            android.util.Slog.e(r0, r3)     // Catch: java.lang.Throwable -> L72
            java.io.OutputStream r0 = r13.mOutput
            r0.flush()
            if (r12 == 0) goto Lac
            r0 = r12[r10]
            libcore.io.IoUtils.closeQuietly(r0)
            r0 = r12[r11]
            goto L6e
        Lac:
            return
        Lad:
            java.io.OutputStream r2 = r13.mOutput
            r2.flush()
            if (r12 == 0) goto Lbe
            r2 = r12[r10]
            libcore.io.IoUtils.closeQuietly(r2)
            r2 = r12[r11]
            libcore.io.IoUtils.closeQuietly(r2)
        Lbe:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.KeyValueAdbBackupEngine.writeBackupData():void");
    }

    private void cleanup() {
        this.mBackupManagerService.tearDownAgentAndKill(this.mCurrentPackage.applicationInfo);
        this.mBlankStateName.delete();
        this.mNewStateName.delete();
        this.mBackupDataName.delete();
    }
}
