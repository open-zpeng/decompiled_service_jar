package com.android.server.backup.internal;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.util.Slog;
import com.android.server.backup.BackupManagerService;
/* loaded from: classes.dex */
public class RunBackupReceiver extends BroadcastReceiver {
    private BackupManagerService backupManagerService;

    public RunBackupReceiver(BackupManagerService backupManagerService) {
        this.backupManagerService = backupManagerService;
    }

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (BackupManagerService.RUN_BACKUP_ACTION.equals(intent.getAction())) {
            synchronized (this.backupManagerService.getQueueLock()) {
                if (this.backupManagerService.getPendingInits().size() > 0) {
                    try {
                        this.backupManagerService.getAlarmManager().cancel(this.backupManagerService.getRunInitIntent());
                        this.backupManagerService.getRunInitIntent().send();
                    } catch (PendingIntent.CanceledException e) {
                        Slog.e(BackupManagerService.TAG, "Run init intent cancelled");
                    }
                } else if (this.backupManagerService.isEnabled() && this.backupManagerService.isProvisioned()) {
                    if (!this.backupManagerService.isBackupRunning()) {
                        Slog.v(BackupManagerService.TAG, "Running a backup pass");
                        this.backupManagerService.setBackupRunning(true);
                        this.backupManagerService.getWakelock().acquire();
                        Message msg = this.backupManagerService.getBackupHandler().obtainMessage(1);
                        this.backupManagerService.getBackupHandler().sendMessage(msg);
                    } else {
                        Slog.i(BackupManagerService.TAG, "Backup time but one already running");
                    }
                } else {
                    Slog.w(BackupManagerService.TAG, "Backup pass but e=" + this.backupManagerService.isEnabled() + " p=" + this.backupManagerService.isProvisioned());
                }
            }
        }
    }
}
