package com.android.server.pm.dex;

import android.content.pm.IPackageManager;
import android.os.FileUtils;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.EventLog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.dex.PackageDynamicCodeLoading;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/* loaded from: classes.dex */
public class DynamicCodeLogger {
    private static final String DCL_DEX_SUBTAG = "dcl";
    private static final String DCL_NATIVE_SUBTAG = "dcln";
    private static final int SNET_TAG = 1397638484;
    private static final String TAG = "DynamicCodeLogger";
    private final Installer mInstaller;
    private final PackageDynamicCodeLoading mPackageDynamicCodeLoading;
    private final IPackageManager mPackageManager;

    /* JADX INFO: Access modifiers changed from: package-private */
    public DynamicCodeLogger(IPackageManager pms, Installer installer) {
        this(pms, installer, new PackageDynamicCodeLoading());
    }

    @VisibleForTesting
    DynamicCodeLogger(IPackageManager pms, Installer installer, PackageDynamicCodeLoading packageDynamicCodeLoading) {
        this.mPackageManager = pms;
        this.mPackageDynamicCodeLoading = packageDynamicCodeLoading;
        this.mInstaller = installer;
    }

    public Set<String> getAllPackagesWithDynamicCodeLoading() {
        return this.mPackageDynamicCodeLoading.getAllPackagesWithDynamicCodeLoading();
    }

    /* JADX WARN: Incorrect condition in loop: B:7:0x0021 */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0101  */
    /* JADX WARN: Removed duplicated region for block: B:44:0x0104  */
    /* JADX WARN: Removed duplicated region for block: B:54:0x0162  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void logDynamicCodeLoading(java.lang.String r24) {
        /*
            Method dump skipped, instructions count: 451
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.dex.DynamicCodeLogger.logDynamicCodeLoading(java.lang.String):void");
    }

    private boolean fileIsUnder(String filePath, String directoryPath) {
        if (directoryPath == null) {
            return false;
        }
        try {
            return FileUtils.contains(new File(directoryPath).getCanonicalPath(), new File(filePath).getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    @VisibleForTesting
    PackageDynamicCodeLoading.PackageDynamicCode getPackageDynamicCodeInfo(String packageName) {
        return this.mPackageDynamicCodeLoading.getPackageDynamicCodeInfo(packageName);
    }

    @VisibleForTesting
    void writeDclEvent(String subtag, int uid, String message) {
        EventLog.writeEvent((int) SNET_TAG, subtag, Integer.valueOf(uid), message);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void recordDex(int loaderUserId, String dexPath, String owningPackageName, String loadingPackageName) {
        if (this.mPackageDynamicCodeLoading.record(owningPackageName, dexPath, 68, loaderUserId, loadingPackageName)) {
            this.mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    public void recordNative(int loadingUid, String path) {
        try {
            String[] packages = this.mPackageManager.getPackagesForUid(loadingUid);
            if (packages != null) {
                if (packages.length == 0) {
                    return;
                }
                String loadingPackageName = packages[0];
                int loadingUserId = UserHandle.getUserId(loadingUid);
                if (this.mPackageDynamicCodeLoading.record(loadingPackageName, path, 78, loadingUserId, loadingPackageName)) {
                    this.mPackageDynamicCodeLoading.maybeWriteAsync();
                }
            }
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clear() {
        this.mPackageDynamicCodeLoading.clear();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removePackage(String packageName) {
        if (this.mPackageDynamicCodeLoading.removePackage(packageName)) {
            this.mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUserPackage(String packageName, int userId) {
        if (this.mPackageDynamicCodeLoading.removeUserPackage(packageName, userId)) {
            this.mPackageDynamicCodeLoading.maybeWriteAsync();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void readAndSync(Map<String, Set<Integer>> packageToUsersMap) {
        this.mPackageDynamicCodeLoading.read();
        this.mPackageDynamicCodeLoading.syncData(packageToUsersMap);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeNow() {
        this.mPackageDynamicCodeLoading.writeNow();
    }
}
