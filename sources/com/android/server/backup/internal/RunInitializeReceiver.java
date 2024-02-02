package com.android.server.backup.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.ArraySet;
import android.util.Slog;
import com.android.server.backup.BackupManagerService;
/* loaded from: classes.dex */
public class RunInitializeReceiver extends BroadcastReceiver {
    private final BackupManagerService mBackupManagerService;

    public RunInitializeReceiver(BackupManagerService backupManagerService) {
        this.mBackupManagerService = backupManagerService;
    }

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        if (BackupManagerService.RUN_INITIALIZE_ACTION.equals(intent.getAction())) {
            synchronized (this.mBackupManagerService.getQueueLock()) {
                ArraySet<String> pendingInits = this.mBackupManagerService.getPendingInits();
                Slog.v(BackupManagerService.TAG, "Running a device init; " + pendingInits.size() + " pending");
                if (pendingInits.size() > 0) {
                    String[] transports = (String[]) pendingInits.toArray(new String[pendingInits.size()]);
                    this.mBackupManagerService.clearPendingInits();
                    final PowerManager.WakeLock wakelock = this.mBackupManagerService.getWakelock();
                    wakelock.acquire();
                    OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.internal.-$$Lambda$RunInitializeReceiver$6NFkS59RniyJ8xe_gfe6oyt63HQ
                        @Override // com.android.server.backup.internal.OnTaskFinishedListener
                        public final void onFinished(String str) {
                            wakelock.release();
                        }
                    };
                    Runnable task = new PerformInitializeTask(this.mBackupManagerService, transports, null, listener);
                    this.mBackupManagerService.getBackupHandler().post(task);
                }
            }
        }
    }
}
