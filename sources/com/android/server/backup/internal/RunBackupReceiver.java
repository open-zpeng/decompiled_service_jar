package com.android.server.backup.internal;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Slog;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.UserBackupManagerService;

/* loaded from: classes.dex */
public class RunBackupReceiver extends BroadcastReceiver {
    private final UserBackupManagerService mUserBackupManagerService;

    public RunBackupReceiver(UserBackupManagerService userBackupManagerService) {
        this.mUserBackupManagerService = userBackupManagerService;
    }

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (!UserBackupManagerService.RUN_BACKUP_ACTION.equals(intent.getAction())) {
            return;
        }
        synchronized (this.mUserBackupManagerService.getQueueLock()) {
            if (this.mUserBackupManagerService.getPendingInits().size() > 0) {
                try {
                    PendingIntent runInitIntent = this.mUserBackupManagerService.getRunInitIntent();
                    this.mUserBackupManagerService.getAlarmManager().cancel(runInitIntent);
                    runInitIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Slog.w(BackupManagerService.TAG, "Run init intent cancelled");
                }
            } else {
                if (this.mUserBackupManagerService.isEnabled() && this.mUserBackupManagerService.isSetupComplete()) {
                    if (this.mUserBackupManagerService.isBackupRunning()) {
                        Slog.i(BackupManagerService.TAG, "Backup time but one already running");
                        return;
                    }
                    Slog.v(BackupManagerService.TAG, "Running a backup pass");
                    this.mUserBackupManagerService.setBackupRunning(true);
                    this.mUserBackupManagerService.getWakelock().acquire();
                    Handler backupHandler = this.mUserBackupManagerService.getBackupHandler();
                    Message message = backupHandler.obtainMessage(1);
                    backupHandler.sendMessage(message);
                }
                Slog.w(BackupManagerService.TAG, "Backup pass but enabled=" + this.mUserBackupManagerService.isEnabled() + " setupComplete=" + this.mUserBackupManagerService.isSetupComplete());
            }
        }
    }
}
