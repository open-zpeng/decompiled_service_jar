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
import com.android.server.backup.utils.FullBackupUtils;
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
    private BackupManagerServiceInterface mBackupManagerService;
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

    public KeyValueAdbBackupEngine(OutputStream output, PackageInfo packageInfo, BackupManagerServiceInterface backupManagerService, PackageManager packageManager, File baseStateDir, File dataDir) {
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
        this.mBackupDataName = new File(file, pkg + BACKUP_KEY_VALUE_BACKUP_DATA_FILENAME_SUFFIX);
        File file2 = this.mStateDir;
        this.mNewStateName = new File(file2, pkg + BACKUP_KEY_VALUE_NEW_STATE_FILENAME_SUFFIX);
        this.mManifestFile = new File(this.mDataDir, BackupManagerService.BACKUP_MANIFEST_FILENAME);
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
            try {
                agent.doBackup(this.mSavedState, this.mBackupData, this.mNewState, (long) JobStatus.NO_LATEST_RUNTIME, token, this.mBackupManagerService.getBackupManagerBinder(), 0);
                if (!this.mBackupManagerService.waitUntilOperationComplete(token)) {
                    Slog.e(TAG, "Key-value backup failed on package " + packageName);
                    return false;
                }
                return true;
            } catch (RemoteException e) {
                e = e;
                Slog.e(TAG, "Error invoking agent for backup on " + packageName + ". " + e);
                return false;
            }
        } catch (RemoteException e2) {
            e = e2;
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
                    FullBackupUtils.writeAppManifest(this.mPackage, KeyValueAdbBackupEngine.this.mPackageManager, KeyValueAdbBackupEngine.this.mManifestFile, false, false);
                    FullBackup.backupToTar(this.mPackage.packageName, "k", (String) null, KeyValueAdbBackupEngine.this.mDataDir.getAbsolutePath(), KeyValueAdbBackupEngine.this.mManifestFile.getAbsolutePath(), output);
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

    /* JADX WARN: Removed duplicated region for block: B:27:0x00b7  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void writeBackupData() throws java.io.IOException {
        /*
            r13 = this;
            com.android.server.backup.BackupManagerServiceInterface r0 = r13.mBackupManagerService
            int r0 = r0.generateRandomIntegerToken()
            com.android.server.backup.BackupAgentTimeoutParameters r1 = r13.mAgentTimeoutParameters
            long r7 = r1.getKvBackupAgentTimeoutMillis()
            r9 = 0
            r1 = r9
            r10 = 0
            r11 = 1
            android.os.ParcelFileDescriptor[] r2 = android.os.ParcelFileDescriptor.createPipe()     // Catch: java.lang.Throwable -> L77 java.io.IOException -> L7b
            r12 = r2
            com.android.server.backup.BackupManagerServiceInterface r1 = r13.mBackupManagerService     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r5 = 0
            r6 = 0
            r2 = r0
            r3 = r7
            r1.prepareOperationTimeout(r2, r3, r5, r6)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            com.android.server.backup.KeyValueAdbBackupEngine$KeyValueAdbBackupDataCopier r1 = new com.android.server.backup.KeyValueAdbBackupEngine$KeyValueAdbBackupDataCopier     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            android.content.pm.PackageInfo r2 = r13.mCurrentPackage     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r3 = r12[r11]     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r1.<init>(r2, r3, r0)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r2 = r12[r11]     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r2.close()     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r12[r11] = r9     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            java.lang.Thread r2 = new java.lang.Thread     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            java.lang.String r3 = "key-value-app-data-runner"
            r2.<init>(r1, r3)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r2.start()     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r3 = r12[r10]     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            java.io.OutputStream r4 = r13.mOutput     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            com.android.server.backup.utils.FullBackupUtils.routeSocketDataToOutput(r3, r4)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            com.android.server.backup.BackupManagerServiceInterface r3 = r13.mBackupManagerService     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            boolean r3 = r3.waitUntilOperationComplete(r0)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            if (r3 != 0) goto L61
            java.lang.String r3 = "KeyValueAdbBackupEngine"
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r4.<init>()     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            java.lang.String r5 = "Full backup failed on package "
            r4.append(r5)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            android.content.pm.PackageInfo r5 = r13.mCurrentPackage     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            java.lang.String r5 = r5.packageName     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            r4.append(r5)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
            android.util.Slog.e(r3, r4)     // Catch: java.lang.Throwable -> L73 java.io.IOException -> L75
        L61:
            java.io.OutputStream r1 = r13.mOutput
            r1.flush()
            if (r12 == 0) goto Laf
            r1 = r12[r10]
            libcore.io.IoUtils.closeQuietly(r1)
            r1 = r12[r11]
        L6f:
            libcore.io.IoUtils.closeQuietly(r1)
            goto Laf
        L73:
            r1 = move-exception
            goto Lb0
        L75:
            r1 = move-exception
            goto L7e
        L77:
            r2 = move-exception
            r12 = r1
            r1 = r2
            goto Lb0
        L7b:
            r2 = move-exception
            r12 = r1
            r1 = r2
        L7e:
            java.lang.String r2 = "KeyValueAdbBackupEngine"
            java.lang.StringBuilder r3 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L73
            r3.<init>()     // Catch: java.lang.Throwable -> L73
            java.lang.String r4 = "Error backing up "
            r3.append(r4)     // Catch: java.lang.Throwable -> L73
            android.content.pm.PackageInfo r4 = r13.mCurrentPackage     // Catch: java.lang.Throwable -> L73
            java.lang.String r4 = r4.packageName     // Catch: java.lang.Throwable -> L73
            r3.append(r4)     // Catch: java.lang.Throwable -> L73
            java.lang.String r4 = ": "
            r3.append(r4)     // Catch: java.lang.Throwable -> L73
            r3.append(r1)     // Catch: java.lang.Throwable -> L73
            java.lang.String r3 = r3.toString()     // Catch: java.lang.Throwable -> L73
            android.util.Slog.e(r2, r3)     // Catch: java.lang.Throwable -> L73
            java.io.OutputStream r1 = r13.mOutput
            r1.flush()
            if (r12 == 0) goto Laf
            r1 = r12[r10]
            libcore.io.IoUtils.closeQuietly(r1)
            r1 = r12[r11]
            goto L6f
        Laf:
            return
        Lb0:
            java.io.OutputStream r2 = r13.mOutput
            r2.flush()
            if (r12 == 0) goto Lc1
            r2 = r12[r10]
            libcore.io.IoUtils.closeQuietly(r2)
            r2 = r12[r11]
            libcore.io.IoUtils.closeQuietly(r2)
        Lc1:
            throw r1
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
