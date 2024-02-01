package com.android.server.rollback;

import android.content.rollback.PackageRollbackInfo;
import android.os.storage.StorageManager;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseLongArray;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@VisibleForTesting
/* loaded from: classes.dex */
public class AppDataRollbackHelper {
    private static final String TAG = "RollbackManager";
    private final Installer mInstaller;

    public AppDataRollbackHelper(Installer installer) {
        this.mInstaller = installer;
    }

    public void snapshotAppData(int snapshotId, PackageRollbackInfo packageRollbackInfo) {
        int storageFlags;
        int[] installedUsers = packageRollbackInfo.getInstalledUsers().toArray();
        for (int user : installedUsers) {
            if (isUserCredentialLocked(user)) {
                Log.v(TAG, "User: " + user + " isn't unlocked, skipping CE userdata backup.");
                storageFlags = 1;
                packageRollbackInfo.addPendingBackup(user);
            } else {
                storageFlags = 3;
            }
            try {
                long ceSnapshotInode = this.mInstaller.snapshotAppData(packageRollbackInfo.getPackageName(), user, snapshotId, storageFlags);
                if ((storageFlags & 2) != 0) {
                    packageRollbackInfo.putCeSnapshotInode(user, ceSnapshotInode);
                }
            } catch (Installer.InstallerException ie) {
                Log.e(TAG, "Unable to create app data snapshot for: " + packageRollbackInfo.getPackageName() + ", userId: " + user, ie);
            }
        }
    }

    public boolean restoreAppData(int rollbackId, PackageRollbackInfo packageRollbackInfo, int userId, int appId, String seInfo) {
        int storageFlags;
        boolean changedRollbackData;
        IntArray pendingBackups = packageRollbackInfo.getPendingBackups();
        List<PackageRollbackInfo.RestoreInfo> pendingRestores = packageRollbackInfo.getPendingRestores();
        if (pendingBackups != null && pendingBackups.indexOf(userId) != -1) {
            pendingBackups.remove(pendingBackups.indexOf(userId));
            storageFlags = 1;
            changedRollbackData = true;
        } else if (!isUserCredentialLocked(userId)) {
            int storageFlags2 = 1 | 2;
            storageFlags = storageFlags2;
            changedRollbackData = false;
        } else {
            pendingRestores.add(new PackageRollbackInfo.RestoreInfo(userId, appId, seInfo));
            storageFlags = 1;
            changedRollbackData = true;
        }
        try {
            this.mInstaller.restoreAppDataSnapshot(packageRollbackInfo.getPackageName(), appId, seInfo, userId, rollbackId, storageFlags);
        } catch (Installer.InstallerException ie) {
            Log.e(TAG, "Unable to restore app data snapshot: " + packageRollbackInfo.getPackageName(), ie);
        }
        return changedRollbackData;
    }

    public void destroyAppDataSnapshot(int rollbackId, PackageRollbackInfo packageRollbackInfo, int user) {
        int storageFlags = 1;
        SparseLongArray ceSnapshotInodes = packageRollbackInfo.getCeSnapshotInodes();
        long ceSnapshotInode = ceSnapshotInodes.get(user);
        if (ceSnapshotInode > 0) {
            storageFlags = 1 | 2;
        }
        try {
            this.mInstaller.destroyAppDataSnapshot(packageRollbackInfo.getPackageName(), user, ceSnapshotInode, rollbackId, storageFlags);
            if ((storageFlags & 2) != 0) {
                ceSnapshotInodes.delete(user);
            }
        } catch (Installer.InstallerException ie) {
            Log.e(TAG, "Unable to delete app data snapshot for " + packageRollbackInfo.getPackageName(), ie);
        }
    }

    private static List<RollbackData> computePendingBackups(int userId, Map<String, PackageRollbackInfo> pendingBackupPackages, List<RollbackData> rollbacks) {
        List<RollbackData> rd = new ArrayList<>();
        for (RollbackData data : rollbacks) {
            for (PackageRollbackInfo info : data.info.getPackages()) {
                IntArray pendingBackupUsers = info.getPendingBackups();
                if (pendingBackupUsers != null) {
                    int idx = pendingBackupUsers.indexOf(userId);
                    if (idx != -1) {
                        pendingBackupPackages.put(info.getPackageName(), info);
                        if (rd.indexOf(data) == -1) {
                            rd.add(data);
                        }
                    }
                }
            }
        }
        return rd;
    }

    private static List<RollbackData> computePendingRestores(int userId, Map<String, PackageRollbackInfo> pendingRestorePackages, List<RollbackData> rollbacks) {
        List<RollbackData> rd = new ArrayList<>();
        for (RollbackData data : rollbacks) {
            for (PackageRollbackInfo info : data.info.getPackages()) {
                PackageRollbackInfo.RestoreInfo ri = info.getRestoreInfo(userId);
                if (ri != null) {
                    pendingRestorePackages.put(info.getPackageName(), info);
                    if (rd.indexOf(data) == -1) {
                        rd.add(data);
                    }
                }
            }
        }
        return rd;
    }

    public Set<RollbackData> commitPendingBackupAndRestoreForUser(int userId, List<RollbackData> rollbacks) {
        PackageRollbackInfo info;
        RollbackData data;
        String str;
        Iterator<RollbackData> it;
        RollbackData data2;
        Iterator it2;
        AppDataRollbackHelper appDataRollbackHelper = this;
        Map<String, PackageRollbackInfo> pendingBackupPackages = new HashMap<>();
        List<RollbackData> pendingBackups = computePendingBackups(userId, pendingBackupPackages, rollbacks);
        Map<String, PackageRollbackInfo> pendingRestorePackages = new HashMap<>();
        List<RollbackData> pendingRestores = computePendingRestores(userId, pendingRestorePackages, rollbacks);
        Iterator<Map.Entry<String, PackageRollbackInfo>> iter = pendingBackupPackages.entrySet().iterator();
        while (iter.hasNext()) {
            PackageRollbackInfo backupPackage = iter.next().getValue();
            PackageRollbackInfo restorePackage = pendingRestorePackages.get(backupPackage.getPackageName());
            if (restorePackage != null) {
                backupPackage.removePendingBackup(userId);
                backupPackage.removePendingRestoreInfo(userId);
                iter.remove();
                pendingRestorePackages.remove(backupPackage.getPackageName());
            }
        }
        boolean isEmpty = pendingBackupPackages.isEmpty();
        String str2 = TAG;
        if (!isEmpty) {
            Iterator<RollbackData> it3 = pendingBackups.iterator();
            while (it3.hasNext()) {
                RollbackData data3 = it3.next();
                Iterator it4 = data3.info.getPackages().iterator();
                while (it4.hasNext()) {
                    PackageRollbackInfo info2 = (PackageRollbackInfo) it4.next();
                    IntArray pendingBackupUsers = info2.getPendingBackups();
                    int idx = pendingBackupUsers.indexOf(userId);
                    if (idx == -1) {
                        it = it3;
                        data2 = data3;
                        it2 = it4;
                    } else {
                        try {
                            it = it3;
                            try {
                                it2 = it4;
                                try {
                                    data2 = data3;
                                } catch (Installer.InstallerException e) {
                                    ie = e;
                                    data2 = data3;
                                }
                            } catch (Installer.InstallerException e2) {
                                ie = e2;
                                data2 = data3;
                                it2 = it4;
                                Log.e(TAG, "Unable to create app data snapshot for: " + info2.getPackageName() + ", userId: " + userId, ie);
                                it3 = it;
                                it4 = it2;
                                data3 = data2;
                            }
                            try {
                                long ceSnapshotInode = appDataRollbackHelper.mInstaller.snapshotAppData(info2.getPackageName(), userId, data3.info.getRollbackId(), 2);
                                info2.putCeSnapshotInode(userId, ceSnapshotInode);
                                pendingBackupUsers.remove(idx);
                            } catch (Installer.InstallerException e3) {
                                ie = e3;
                                Log.e(TAG, "Unable to create app data snapshot for: " + info2.getPackageName() + ", userId: " + userId, ie);
                                it3 = it;
                                it4 = it2;
                                data3 = data2;
                            }
                        } catch (Installer.InstallerException e4) {
                            ie = e4;
                            it = it3;
                        }
                    }
                    it3 = it;
                    it4 = it2;
                    data3 = data2;
                }
            }
        }
        if (!pendingRestorePackages.isEmpty()) {
            Iterator<RollbackData> it5 = pendingRestores.iterator();
            while (it5.hasNext()) {
                RollbackData data4 = it5.next();
                for (PackageRollbackInfo info3 : data4.info.getPackages()) {
                    PackageRollbackInfo.RestoreInfo ri = info3.getRestoreInfo(userId);
                    if (ri != null) {
                        try {
                            Installer installer = appDataRollbackHelper.mInstaller;
                            data = data4;
                            str = str2;
                            try {
                                installer.restoreAppDataSnapshot(info3.getPackageName(), ri.appId, ri.seInfo, userId, data4.info.getRollbackId(), 2);
                                info = info3;
                            } catch (Installer.InstallerException e5) {
                                ie = e5;
                                info = info3;
                            }
                            try {
                                info.removeRestoreInfo(ri);
                            } catch (Installer.InstallerException e6) {
                                ie = e6;
                                Log.e(str, "Unable to restore app data snapshot for: " + info.getPackageName(), ie);
                                str2 = str;
                                data4 = data;
                                appDataRollbackHelper = this;
                            }
                        } catch (Installer.InstallerException e7) {
                            ie = e7;
                            info = info3;
                            data = data4;
                            str = str2;
                        }
                    } else {
                        data = data4;
                        str = str2;
                    }
                    str2 = str;
                    data4 = data;
                    appDataRollbackHelper = this;
                }
                appDataRollbackHelper = this;
            }
        }
        Set<RollbackData> changed = new HashSet<>(pendingBackups);
        changed.addAll(pendingRestores);
        return changed;
    }

    @VisibleForTesting
    public boolean isUserCredentialLocked(int userId) {
        return StorageManager.isFileEncryptedNativeOrEmulated() && !StorageManager.isUserKeyUnlocked(userId);
    }
}
