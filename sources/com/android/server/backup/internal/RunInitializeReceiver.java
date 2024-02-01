package com.android.server.backup.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Slog;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.UserBackupManagerService;
import java.util.Set;

/* loaded from: classes.dex */
public class RunInitializeReceiver extends BroadcastReceiver {
    private final UserBackupManagerService mUserBackupManagerService;

    public RunInitializeReceiver(UserBackupManagerService userBackupManagerService) {
        this.mUserBackupManagerService = userBackupManagerService;
    }

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (!UserBackupManagerService.RUN_INITIALIZE_ACTION.equals(intent.getAction())) {
            return;
        }
        synchronized (this.mUserBackupManagerService.getQueueLock()) {
            Set<String> pendingInits = this.mUserBackupManagerService.getPendingInits();
            Slog.v(BackupManagerService.TAG, "Running a device init; " + pendingInits.size() + " pending");
            if (pendingInits.size() > 0) {
                String[] transports = (String[]) pendingInits.toArray(new String[pendingInits.size()]);
                this.mUserBackupManagerService.clearPendingInits();
                final UserBackupManagerService.BackupWakeLock wakelock = this.mUserBackupManagerService.getWakelock();
                wakelock.acquire();
                OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.internal.-$$Lambda$RunInitializeReceiver$P5klzxUXc7WxTPKz3eSndgIx-xA
                    @Override // com.android.server.backup.internal.OnTaskFinishedListener
                    public final void onFinished(String str) {
                        UserBackupManagerService.BackupWakeLock.this.release();
                    }
                };
                Runnable task = new PerformInitializeTask(this.mUserBackupManagerService, transports, null, listener);
                this.mUserBackupManagerService.getBackupHandler().post(task);
            }
        }
    }
}
