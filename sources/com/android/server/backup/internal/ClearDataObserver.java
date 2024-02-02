package com.android.server.backup.internal;

import android.content.pm.IPackageDataObserver;
import com.android.server.backup.BackupManagerService;
/* loaded from: classes.dex */
public class ClearDataObserver extends IPackageDataObserver.Stub {
    private BackupManagerService backupManagerService;

    public ClearDataObserver(BackupManagerService backupManagerService) {
        this.backupManagerService = backupManagerService;
    }

    public void onRemoveCompleted(String packageName, boolean succeeded) {
        synchronized (this.backupManagerService.getClearDataLock()) {
            this.backupManagerService.setClearingData(false);
            this.backupManagerService.getClearDataLock().notifyAll();
        }
    }
}
