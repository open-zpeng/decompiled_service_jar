package com.android.server.backup.internal;

import android.content.pm.PackageInfo;
import android.util.Slog;
import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.transport.TransportClient;
import java.io.File;

/* loaded from: classes.dex */
public class PerformClearTask implements Runnable {
    private final UserBackupManagerService mBackupManagerService;
    private final OnTaskFinishedListener mListener;
    private final PackageInfo mPackage;
    private final TransportClient mTransportClient;
    private final TransportManager mTransportManager;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PerformClearTask(UserBackupManagerService backupManagerService, TransportClient transportClient, PackageInfo packageInfo, OnTaskFinishedListener listener) {
        this.mBackupManagerService = backupManagerService;
        this.mTransportManager = backupManagerService.getTransportManager();
        this.mTransportClient = transportClient;
        this.mPackage = packageInfo;
        this.mListener = listener;
    }

    @Override // java.lang.Runnable
    public void run() {
        StringBuilder sb;
        IBackupTransport transport = null;
        try {
            try {
                String transportDirName = this.mTransportManager.getTransportDirName(this.mTransportClient.getTransportComponent());
                File stateDir = new File(this.mBackupManagerService.getBaseStateDir(), transportDirName);
                File stateFile = new File(stateDir, this.mPackage.packageName);
                stateFile.delete();
                transport = this.mTransportClient.connectOrThrow("PerformClearTask.run()");
                transport.clearBackupData(this.mPackage);
            } catch (Exception e) {
                Slog.e(BackupManagerService.TAG, "Transport threw clearing data for " + this.mPackage + ": " + e.getMessage());
                if (transport != null) {
                    try {
                        transport.finishBackup();
                    } catch (Exception e2) {
                        e = e2;
                        sb = new StringBuilder();
                        sb.append("Unable to mark clear operation finished: ");
                        sb.append(e.getMessage());
                        Slog.e(BackupManagerService.TAG, sb.toString());
                        this.mListener.onFinished("PerformClearTask.run()");
                        this.mBackupManagerService.getWakelock().release();
                    }
                }
            }
            try {
                transport.finishBackup();
            } catch (Exception e3) {
                e = e3;
                sb = new StringBuilder();
                sb.append("Unable to mark clear operation finished: ");
                sb.append(e.getMessage());
                Slog.e(BackupManagerService.TAG, sb.toString());
                this.mListener.onFinished("PerformClearTask.run()");
                this.mBackupManagerService.getWakelock().release();
            }
            this.mListener.onFinished("PerformClearTask.run()");
            this.mBackupManagerService.getWakelock().release();
        } catch (Throwable e4) {
            if (transport != null) {
                try {
                    transport.finishBackup();
                } catch (Exception e5) {
                    Slog.e(BackupManagerService.TAG, "Unable to mark clear operation finished: " + e5.getMessage());
                }
            }
            this.mListener.onFinished("PerformClearTask.run()");
            this.mBackupManagerService.getWakelock().release();
            throw e4;
        }
    }
}
