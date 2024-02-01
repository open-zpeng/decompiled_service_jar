package com.android.server.pm.dex;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.ByteStringUtils;
import android.util.EventLog;
import android.util.PackageUtils;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.PackageDexUsage;
import java.io.File;
import java.util.Set;
/* loaded from: classes.dex */
public class DexLogger implements DexManager.Listener {
    private static final String DCL_SUBTAG = "dcl";
    private static final int SNET_TAG = 1397638484;
    private static final String TAG = "DexLogger";
    private final Object mInstallLock;
    @GuardedBy("mInstallLock")
    private final Installer mInstaller;
    private final IPackageManager mPackageManager;

    public static DexManager.Listener getListener(IPackageManager pms, Installer installer, Object installLock) {
        return new DexLogger(pms, installer, installLock);
    }

    @VisibleForTesting
    DexLogger(IPackageManager pms, Installer installer, Object installLock) {
        this.mPackageManager = pms;
        this.mInstaller = installer;
        this.mInstallLock = installLock;
    }

    @Override // com.android.server.pm.dex.DexManager.Listener
    public void onReconcileSecondaryDexFile(ApplicationInfo appInfo, PackageDexUsage.DexUseInfo dexUseInfo, String dexPath, int storageFlags) {
        int ownerUid = appInfo.uid;
        byte[] hash = null;
        synchronized (this.mInstallLock) {
            try {
                byte[] hash2 = this.mInstaller.hashSecondaryDexFile(dexPath, appInfo.packageName, ownerUid, appInfo.volumeUuid, storageFlags);
                hash = hash2;
            } catch (Installer.InstallerException e) {
                Slog.e(TAG, "Got InstallerException when hashing dex " + dexPath + " : " + e.getMessage());
            }
        }
        if (hash == null) {
            return;
        }
        String dexFileName = new File(dexPath).getName();
        String message = PackageUtils.computeSha256Digest(dexFileName.getBytes());
        if (hash.length == 32) {
            message = message + ' ' + ByteStringUtils.toHexString(hash);
        }
        writeDclEvent(ownerUid, message);
        if (dexUseInfo.isUsedByOtherApps()) {
            Set<String> otherPackages = dexUseInfo.getLoadingPackages();
            Set<Integer> otherUids = new ArraySet<>(otherPackages.size());
            for (String otherPackageName : otherPackages) {
                try {
                    int otherUid = this.mPackageManager.getPackageUid(otherPackageName, 0, dexUseInfo.getOwnerUserId());
                    if (otherUid != -1 && otherUid != ownerUid) {
                        otherUids.add(Integer.valueOf(otherUid));
                    }
                } catch (RemoteException e2) {
                }
            }
            for (Integer num : otherUids) {
                writeDclEvent(num.intValue(), message);
            }
        }
    }

    @VisibleForTesting
    void writeDclEvent(int uid, String message) {
        EventLog.writeEvent((int) SNET_TAG, DCL_SUBTAG, Integer.valueOf(uid), message);
    }
}
