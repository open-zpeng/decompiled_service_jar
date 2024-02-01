package com.android.server.om;

import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;
import com.android.server.om.OverlayManagerServiceImpl;
import com.android.server.pm.Installer;
import java.io.File;

/* loaded from: classes.dex */
class IdmapManager {
    private static final boolean FEATURE_FLAG_IDMAP2 = true;
    private static final boolean VENDOR_IS_Q_OR_LATER;
    private final IdmapDaemon mIdmapDaemon = IdmapDaemon.getInstance();
    private final Installer mInstaller;
    private final OverlayManagerServiceImpl.PackageManagerHelper mPackageManager;

    static {
        boolean isQOrLater;
        String value = SystemProperties.get("ro.vndk.version", "29");
        try {
            isQOrLater = Integer.parseInt(value) >= 29;
        } catch (NumberFormatException e) {
            isQOrLater = true;
        }
        VENDOR_IS_Q_OR_LATER = isQOrLater;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IdmapManager(Installer installer, OverlayManagerServiceImpl.PackageManagerHelper packageManager) {
        this.mInstaller = installer;
        this.mPackageManager = packageManager;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean createIdmap(PackageInfo targetPackage, PackageInfo overlayPackage, int userId) {
        UserHandle.getSharedAppGid(targetPackage.applicationInfo.uid);
        String targetPath = targetPackage.applicationInfo.getBaseCodePath();
        String overlayPath = overlayPackage.applicationInfo.getBaseCodePath();
        try {
            int policies = calculateFulfilledPolicies(targetPackage, overlayPackage, userId);
            boolean enforce = enforceOverlayable(overlayPackage);
            try {
                if (this.mIdmapDaemon.verifyIdmap(overlayPath, policies, enforce, userId)) {
                    return true;
                }
                return this.mIdmapDaemon.createIdmap(targetPath, overlayPath, policies, enforce, userId) != null;
            } catch (Exception e) {
                e = e;
                Slog.w("OverlayManager", "failed to generate idmap for " + targetPath + " and " + overlayPath + ": " + e.getMessage());
                return false;
            }
        } catch (Exception e2) {
            e = e2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeIdmap(OverlayInfo oi, int userId) {
        try {
            return this.mIdmapDaemon.removeIdmap(oi.baseCodePath, userId);
        } catch (Exception e) {
            Slog.w("OverlayManager", "failed to remove idmap for " + oi.baseCodePath + ": " + e.getMessage());
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean idmapExists(OverlayInfo oi) {
        return new File(getIdmapPath(oi.baseCodePath, oi.userId)).isFile();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean idmapExists(PackageInfo overlayPackage, int userId) {
        return new File(getIdmapPath(overlayPackage.applicationInfo.getBaseCodePath(), userId)).isFile();
    }

    private String getIdmapPath(String overlayPackagePath, int userId) {
        try {
            return this.mIdmapDaemon.getIdmapPath(overlayPackagePath, userId);
        } catch (Exception e) {
            Slog.w("OverlayManager", "failed to get idmap path for " + overlayPackagePath + ": " + e.getMessage());
            return "";
        }
    }

    private boolean enforceOverlayable(PackageInfo overlayPackage) {
        ApplicationInfo ai = overlayPackage.applicationInfo;
        if (ai.targetSdkVersion >= 29) {
            return true;
        }
        if (ai.isVendor()) {
            return VENDOR_IS_Q_OR_LATER;
        }
        return (ai.isSystemApp() || ai.isSignedWithPlatformKey()) ? false : true;
    }

    private int calculateFulfilledPolicies(PackageInfo targetPackage, PackageInfo overlayPackage, int userId) {
        ApplicationInfo ai = overlayPackage.applicationInfo;
        int fulfilledPolicies = 1;
        if (this.mPackageManager.signaturesMatching(targetPackage.packageName, overlayPackage.packageName, userId)) {
            fulfilledPolicies = 1 | 16;
        }
        if (ai.isVendor()) {
            return fulfilledPolicies | 4;
        }
        if (ai.isProduct()) {
            return fulfilledPolicies | 8;
        }
        if (ai.isOdm()) {
            return fulfilledPolicies | 32;
        }
        if (ai.isOem()) {
            return fulfilledPolicies | 64;
        }
        if (ai.isProductServices()) {
            return fulfilledPolicies;
        }
        if (ai.isSystemApp()) {
            return fulfilledPolicies | 2;
        }
        return fulfilledPolicies;
    }
}
