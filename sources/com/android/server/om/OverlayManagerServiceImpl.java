package com.android.server.om;

import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import com.android.server.om.OverlayManagerSettings;
import com.android.server.pm.PackageManagerService;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class OverlayManagerServiceImpl {
    private static final int FLAG_OVERLAY_IS_UPGRADING = 2;
    private static final int FLAG_TARGET_IS_UPGRADING = 1;
    private final String[] mDefaultOverlays;
    private final IdmapManager mIdmapManager;
    private final OverlayChangeListener mListener;
    private final PackageManagerHelper mPackageManager;
    private final OverlayManagerSettings mSettings;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface OverlayChangeListener {
        void onOverlaysChanged(String str, int i);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface PackageManagerHelper {
        List<PackageInfo> getOverlayPackages(int i);

        PackageInfo getPackageInfo(String str, int i);

        boolean signaturesMatching(String str, String str2, int i);
    }

    private static boolean mustReinitializeOverlay(PackageInfo theTruth, OverlayInfo oldSettings) {
        if (oldSettings == null || !Objects.equals(theTruth.overlayTarget, oldSettings.targetPackageName) || theTruth.isStaticOverlayPackage() != oldSettings.isStatic) {
            return true;
        }
        if (theTruth.isStaticOverlayPackage() && theTruth.overlayPriority != oldSettings.priority) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public OverlayManagerServiceImpl(PackageManagerHelper packageManager, IdmapManager idmapManager, OverlayManagerSettings settings, String[] defaultOverlays, OverlayChangeListener listener) {
        this.mPackageManager = packageManager;
        this.mIdmapManager = idmapManager;
        this.mSettings = settings;
        this.mDefaultOverlays = defaultOverlays;
        this.mListener = listener;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayList<String> updateOverlaysForUser(int newUserId) {
        Iterator<String> iter;
        String[] strArr;
        Iterator<String> iter2;
        int storedOverlayInfosSize;
        List<PackageInfo> overlayPackages;
        int i;
        List<PackageInfo> overlayPackages2;
        ArrayMap<String, List<OverlayInfo>> tmp;
        int tmpSize;
        PackageInfo overlayPackage;
        int tmpSize2;
        int i2 = newUserId;
        Set<String> packagesToUpdateAssets = new ArraySet<>();
        ArrayMap<String, List<OverlayInfo>> tmp2 = this.mSettings.getOverlaysForUser(i2);
        int overlayPackagesSize = tmp2.size();
        ArrayMap<String, OverlayInfo> storedOverlayInfos = new ArrayMap<>(overlayPackagesSize);
        for (int i3 = 0; i3 < overlayPackagesSize; i3++) {
            List<OverlayInfo> chunk = tmp2.valueAt(i3);
            int chunkSize = chunk.size();
            for (int j = 0; j < chunkSize; j++) {
                OverlayInfo oi = chunk.get(j);
                storedOverlayInfos.put(oi.packageName, oi);
            }
        }
        List<PackageInfo> overlayPackages3 = this.mPackageManager.getOverlayPackages(i2);
        int overlayPackagesSize2 = overlayPackages3.size();
        int i4 = 0;
        while (i4 < overlayPackagesSize2) {
            PackageInfo overlayPackage2 = overlayPackages3.get(i4);
            OverlayInfo oi2 = storedOverlayInfos.get(overlayPackage2.packageName);
            if (mustReinitializeOverlay(overlayPackage2, oi2)) {
                if (oi2 != null) {
                    packagesToUpdateAssets.add(oi2.targetPackageName);
                }
                int i5 = i2;
                tmp = tmp2;
                overlayPackage = overlayPackage2;
                tmpSize = overlayPackagesSize;
                tmpSize2 = overlayPackagesSize2;
                overlayPackages2 = overlayPackages3;
                this.mSettings.init(overlayPackage2.packageName, i5, overlayPackage2.overlayTarget, overlayPackage2.applicationInfo.getBaseCodePath(), overlayPackage2.isStaticOverlayPackage(), overlayPackage2.overlayPriority, overlayPackage2.overlayCategory);
            } else {
                overlayPackages2 = overlayPackages3;
                tmp = tmp2;
                tmpSize = overlayPackagesSize;
                overlayPackage = overlayPackage2;
                tmpSize2 = overlayPackagesSize2;
            }
            storedOverlayInfos.remove(overlayPackage.packageName);
            i4++;
            overlayPackages3 = overlayPackages2;
            overlayPackagesSize2 = tmpSize2;
            tmp2 = tmp;
            overlayPackagesSize = tmpSize;
            i2 = newUserId;
        }
        List<PackageInfo> overlayPackages4 = overlayPackages3;
        int tmpSize3 = overlayPackagesSize2;
        int storedOverlayInfosSize2 = storedOverlayInfos.size();
        for (int i6 = 0; i6 < storedOverlayInfosSize2; i6++) {
            OverlayInfo oi3 = storedOverlayInfos.valueAt(i6);
            this.mSettings.remove(oi3.packageName, oi3.userId);
            removeIdmapIfPossible(oi3);
            packagesToUpdateAssets.add(oi3.targetPackageName);
        }
        int i7 = 0;
        while (true) {
            int i8 = i7;
            if (i8 >= tmpSize3) {
                break;
            }
            PackageInfo overlayPackage3 = overlayPackages4.get(i8);
            try {
                overlayPackages = overlayPackages4;
                i = newUserId;
                try {
                    updateState(overlayPackage3.overlayTarget, overlayPackage3.packageName, i, 0);
                } catch (OverlayManagerSettings.BadKeyException e) {
                    e = e;
                    Slog.e("OverlayManager", "failed to update settings", e);
                    this.mSettings.remove(overlayPackage3.packageName, i);
                    packagesToUpdateAssets.add(overlayPackage3.overlayTarget);
                    i7 = i8 + 1;
                    overlayPackages4 = overlayPackages;
                }
            } catch (OverlayManagerSettings.BadKeyException e2) {
                e = e2;
                overlayPackages = overlayPackages4;
                i = newUserId;
            }
            packagesToUpdateAssets.add(overlayPackage3.overlayTarget);
            i7 = i8 + 1;
            overlayPackages4 = overlayPackages;
        }
        Iterator<String> iter3 = packagesToUpdateAssets.iterator();
        while (true) {
            iter = iter3;
            if (!iter.hasNext()) {
                break;
            }
            String targetPackageName = iter.next();
            if (this.mPackageManager.getPackageInfo(targetPackageName, newUserId) == null) {
                iter.remove();
            }
            iter3 = iter;
        }
        ArraySet<String> enabledCategories = new ArraySet<>();
        ArrayMap<String, List<OverlayInfo>> userOverlays = this.mSettings.getOverlaysForUser(newUserId);
        int userOverlayTargetCount = userOverlays.size();
        for (int i9 = 0; i9 < userOverlayTargetCount; i9++) {
            List<OverlayInfo> overlayList = userOverlays.valueAt(i9);
            int overlayCount = overlayList != null ? overlayList.size() : 0;
            int j2 = 0;
            while (j2 < overlayCount) {
                OverlayInfo oi4 = overlayList.get(j2);
                if (!oi4.isEnabled()) {
                    storedOverlayInfosSize = storedOverlayInfosSize2;
                } else {
                    storedOverlayInfosSize = storedOverlayInfosSize2;
                    enabledCategories.add(oi4.category);
                }
                j2++;
                storedOverlayInfosSize2 = storedOverlayInfosSize;
            }
        }
        String[] strArr2 = this.mDefaultOverlays;
        int length = strArr2.length;
        int i10 = 0;
        while (i10 < length) {
            String defaultOverlay = strArr2[i10];
            try {
                OverlayInfo oi5 = this.mSettings.getOverlayInfo(defaultOverlay, newUserId);
                if (!enabledCategories.contains(oi5.category)) {
                    strArr = strArr2;
                    try {
                        StringBuilder sb = new StringBuilder();
                        iter2 = iter;
                        try {
                            sb.append("Enabling default overlay '");
                            sb.append(defaultOverlay);
                            sb.append("' for target '");
                            sb.append(oi5.targetPackageName);
                            sb.append("' in category '");
                            sb.append(oi5.category);
                            sb.append("' for user ");
                            sb.append(newUserId);
                            Slog.w("OverlayManager", sb.toString());
                            this.mSettings.setEnabled(oi5.packageName, newUserId, true);
                            try {
                                if (updateState(oi5.targetPackageName, oi5.packageName, newUserId, 0)) {
                                    packagesToUpdateAssets.add(oi5.targetPackageName);
                                }
                            } catch (OverlayManagerSettings.BadKeyException e3) {
                                e = e3;
                                Slog.e("OverlayManager", "Failed to set default overlay '" + defaultOverlay + "' for user " + newUserId, e);
                                i10++;
                                strArr2 = strArr;
                                iter = iter2;
                            }
                        } catch (OverlayManagerSettings.BadKeyException e4) {
                            e = e4;
                        }
                    } catch (OverlayManagerSettings.BadKeyException e5) {
                        e = e5;
                        iter2 = iter;
                        Slog.e("OverlayManager", "Failed to set default overlay '" + defaultOverlay + "' for user " + newUserId, e);
                        i10++;
                        strArr2 = strArr;
                        iter = iter2;
                    }
                } else {
                    strArr = strArr2;
                    iter2 = iter;
                }
            } catch (OverlayManagerSettings.BadKeyException e6) {
                e = e6;
                strArr = strArr2;
            }
            i10++;
            strArr2 = strArr;
            iter = iter2;
        }
        return new ArrayList<>(packagesToUpdateAssets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUserRemoved(int userId) {
        this.mSettings.removeUser(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageAdded(String packageName, int userId) {
        if (updateAllOverlaysForTarget(packageName, userId, 0)) {
            this.mListener.onOverlaysChanged(packageName, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageChanged(String packageName, int userId) {
        updateAllOverlaysForTarget(packageName, userId, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageUpgrading(String packageName, int userId) {
        updateAllOverlaysForTarget(packageName, userId, 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageUpgraded(String packageName, int userId) {
        updateAllOverlaysForTarget(packageName, userId, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageRemoved(String packageName, int userId) {
        if (updateAllOverlaysForTarget(packageName, userId, 0)) {
            this.mListener.onOverlaysChanged(packageName, userId);
        }
    }

    private boolean updateAllOverlaysForTarget(String targetPackageName, int userId, int flags) {
        List<OverlayInfo> ois = this.mSettings.getOverlaysForTarget(targetPackageName, userId);
        int N = ois.size();
        boolean z = false;
        boolean modified = false;
        for (int i = 0; i < N; i++) {
            OverlayInfo oi = ois.get(i);
            PackageInfo overlayPackage = this.mPackageManager.getPackageInfo(oi.packageName, userId);
            if (overlayPackage == null) {
                modified |= this.mSettings.remove(oi.packageName, oi.userId);
                removeIdmapIfPossible(oi);
            } else {
                try {
                    modified |= updateState(targetPackageName, oi.packageName, userId, flags);
                } catch (OverlayManagerSettings.BadKeyException e) {
                    Slog.e("OverlayManager", "failed to update settings", e);
                    modified |= this.mSettings.remove(oi.packageName, userId);
                }
            }
        }
        boolean modified2 = (modified || !getEnabledOverlayPackageNames(PackageManagerService.PLATFORM_PACKAGE_NAME, userId).isEmpty()) ? true : true;
        return modified2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onOverlayPackageAdded(String packageName, int userId) {
        PackageInfo overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w("OverlayManager", "overlay package " + packageName + " was added, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }
        this.mSettings.init(packageName, userId, overlayPackage.overlayTarget, overlayPackage.applicationInfo.getBaseCodePath(), overlayPackage.isStaticOverlayPackage(), overlayPackage.overlayPriority, overlayPackage.overlayCategory);
        try {
            if (updateState(overlayPackage.overlayTarget, packageName, userId, 0)) {
                this.mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e("OverlayManager", "failed to update settings", e);
            this.mSettings.remove(packageName, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onOverlayPackageChanged(String packageName, int userId) {
        try {
            OverlayInfo oi = this.mSettings.getOverlayInfo(packageName, userId);
            if (updateState(oi.targetPackageName, packageName, userId, 0)) {
                this.mListener.onOverlaysChanged(oi.targetPackageName, userId);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e("OverlayManager", "failed to update settings", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onOverlayPackageUpgrading(String packageName, int userId) {
        try {
            OverlayInfo oi = this.mSettings.getOverlayInfo(packageName, userId);
            if (updateState(oi.targetPackageName, packageName, userId, 2)) {
                removeIdmapIfPossible(oi);
                this.mListener.onOverlaysChanged(oi.targetPackageName, userId);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e("OverlayManager", "failed to update settings", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onOverlayPackageUpgraded(String packageName, int userId) {
        PackageInfo pkg = this.mPackageManager.getPackageInfo(packageName, userId);
        if (pkg == null) {
            Slog.w("OverlayManager", "overlay package " + packageName + " was upgraded, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }
        try {
            OverlayInfo oldOi = this.mSettings.getOverlayInfo(packageName, userId);
            if (mustReinitializeOverlay(pkg, oldOi)) {
                if (oldOi != null && !oldOi.targetPackageName.equals(pkg.overlayTarget)) {
                    this.mListener.onOverlaysChanged(pkg.overlayTarget, userId);
                }
                this.mSettings.init(packageName, userId, pkg.overlayTarget, pkg.applicationInfo.getBaseCodePath(), pkg.isStaticOverlayPackage(), pkg.overlayPriority, pkg.overlayCategory);
            }
            if (updateState(pkg.overlayTarget, packageName, userId, 0)) {
                this.mListener.onOverlaysChanged(pkg.overlayTarget, userId);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e("OverlayManager", "failed to update settings", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onOverlayPackageRemoved(String packageName, int userId) {
        try {
            OverlayInfo overlayInfo = this.mSettings.getOverlayInfo(packageName, userId);
            if (this.mSettings.remove(packageName, userId)) {
                removeIdmapIfPossible(overlayInfo);
                if (overlayInfo.isEnabled()) {
                    this.mListener.onOverlaysChanged(overlayInfo.targetPackageName, userId);
                }
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e("OverlayManager", "failed to remove overlay", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public OverlayInfo getOverlayInfo(String packageName, int userId) {
        try {
            return this.mSettings.getOverlayInfo(packageName, userId);
        } catch (OverlayManagerSettings.BadKeyException e) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<OverlayInfo> getOverlayInfosForTarget(String targetPackageName, int userId) {
        return this.mSettings.getOverlaysForTarget(targetPackageName, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Map<String, List<OverlayInfo>> getOverlaysForUser(int userId) {
        return this.mSettings.getOverlaysForUser(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setEnabled(String packageName, boolean enable, int userId) {
        PackageInfo overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null || overlayPackage.isStaticOverlayPackage()) {
            return false;
        }
        try {
            OverlayInfo oi = this.mSettings.getOverlayInfo(packageName, userId);
            boolean modified = this.mSettings.setEnabled(packageName, userId, enable);
            if (modified | updateState(oi.targetPackageName, oi.packageName, userId, 0)) {
                this.mListener.onOverlaysChanged(oi.targetPackageName, userId);
                return true;
            }
            return true;
        } catch (OverlayManagerSettings.BadKeyException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setEnabledExclusive(String packageName, boolean withinCategory, int userId) {
        PackageInfo overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }
        try {
            OverlayInfo oi = this.mSettings.getOverlayInfo(packageName, userId);
            String targetPackageName = oi.targetPackageName;
            List<OverlayInfo> allOverlays = getOverlayInfosForTarget(targetPackageName, userId);
            allOverlays.remove(oi);
            boolean modified = false;
            for (int i = 0; i < allOverlays.size(); i++) {
                String disabledOverlayPackageName = allOverlays.get(i).packageName;
                PackageInfo disabledOverlayPackageInfo = this.mPackageManager.getPackageInfo(disabledOverlayPackageName, userId);
                if (disabledOverlayPackageInfo == null) {
                    modified |= this.mSettings.remove(disabledOverlayPackageName, userId);
                } else if (!disabledOverlayPackageInfo.isStaticOverlayPackage() && (!withinCategory || Objects.equals(disabledOverlayPackageInfo.overlayCategory, oi.category))) {
                    modified = modified | this.mSettings.setEnabled(disabledOverlayPackageName, userId, false) | updateState(targetPackageName, disabledOverlayPackageName, userId, 0);
                }
            }
            boolean modified2 = this.mSettings.setEnabled(packageName, userId, true) | modified;
            boolean modified3 = updateState(targetPackageName, packageName, userId, 0);
            if (modified2 | modified3) {
                this.mListener.onOverlaysChanged(targetPackageName, userId);
            }
            return true;
        } catch (OverlayManagerSettings.BadKeyException e) {
            return false;
        }
    }

    private boolean isPackageUpdatableOverlay(String packageName, int userId) {
        PackageInfo overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null || overlayPackage.isStaticOverlayPackage()) {
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setPriority(String packageName, String newParentPackageName, int userId) {
        PackageInfo overlayPackage;
        if (isPackageUpdatableOverlay(packageName, userId) && (overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId)) != null) {
            if (this.mSettings.setPriority(packageName, newParentPackageName, userId)) {
                this.mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
                return true;
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setHighestPriority(String packageName, int userId) {
        PackageInfo overlayPackage;
        if (isPackageUpdatableOverlay(packageName, userId) && (overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId)) != null) {
            if (this.mSettings.setHighestPriority(packageName, userId)) {
                this.mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
                return true;
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setLowestPriority(String packageName, int userId) {
        PackageInfo overlayPackage;
        if (isPackageUpdatableOverlay(packageName, userId) && (overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId)) != null) {
            if (this.mSettings.setLowestPriority(packageName, userId)) {
                this.mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
                return true;
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onDump(PrintWriter pw) {
        this.mSettings.dump(pw);
        pw.println("Default overlays: " + TextUtils.join(";", this.mDefaultOverlays));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<String> getEnabledOverlayPackageNames(String targetPackageName, int userId) {
        List<OverlayInfo> overlays = this.mSettings.getOverlaysForTarget(targetPackageName, userId);
        List<String> paths = new ArrayList<>(overlays.size());
        int N = overlays.size();
        for (int i = 0; i < N; i++) {
            OverlayInfo oi = overlays.get(i);
            if (oi.isEnabled()) {
                paths.add(oi.packageName);
            }
        }
        return paths;
    }

    private boolean updateState(String targetPackageName, String overlayPackageName, int userId, int flags) throws OverlayManagerSettings.BadKeyException {
        PackageInfo targetPackage = this.mPackageManager.getPackageInfo(targetPackageName, userId);
        PackageInfo overlayPackage = this.mPackageManager.getPackageInfo(overlayPackageName, userId);
        if (targetPackage != null && overlayPackage != null && (!PackageManagerService.PLATFORM_PACKAGE_NAME.equals(targetPackageName) || !overlayPackage.isStaticOverlayPackage())) {
            this.mIdmapManager.createIdmap(targetPackage, overlayPackage, userId);
        }
        boolean modified = false;
        if (overlayPackage != null) {
            boolean modified2 = false | this.mSettings.setBaseCodePath(overlayPackageName, userId, overlayPackage.applicationInfo.getBaseCodePath());
            modified = modified2 | this.mSettings.setCategory(overlayPackageName, userId, overlayPackage.overlayCategory);
        }
        int currentState = this.mSettings.getState(overlayPackageName, userId);
        int newState = calculateNewState(targetPackage, overlayPackage, userId, flags);
        if (currentState != newState) {
            return modified | this.mSettings.setState(overlayPackageName, userId, newState);
        }
        return modified;
    }

    private int calculateNewState(PackageInfo targetPackage, PackageInfo overlayPackage, int userId, int flags) throws OverlayManagerSettings.BadKeyException {
        if ((flags & 1) != 0) {
            return 4;
        }
        if ((flags & 2) != 0) {
            return 5;
        }
        if (targetPackage == null) {
            return 0;
        }
        if (!this.mIdmapManager.idmapExists(overlayPackage, userId)) {
            return 1;
        }
        if (overlayPackage.isStaticOverlayPackage()) {
            return 6;
        }
        boolean enabled = this.mSettings.getEnabled(overlayPackage.packageName, userId);
        return enabled ? 3 : 2;
    }

    private void removeIdmapIfPossible(OverlayInfo oi) {
        if (!this.mIdmapManager.idmapExists(oi)) {
            return;
        }
        int[] userIds = this.mSettings.getUsers();
        for (int userId : userIds) {
            try {
                OverlayInfo tmp = this.mSettings.getOverlayInfo(oi.packageName, userId);
                if (tmp != null && tmp.isEnabled()) {
                    return;
                }
            } catch (OverlayManagerSettings.BadKeyException e) {
            }
        }
        this.mIdmapManager.removeIdmap(oi, oi.userId);
    }
}
