package com.android.server.om;

import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import com.android.internal.util.ArrayUtils;
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
    private static final int FLAG_OVERLAY_IS_BEING_REPLACED = 2;
    @Deprecated
    private static final int FLAG_TARGET_IS_BEING_REPLACED = 1;
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
        if (oldSettings == null || !Objects.equals(theTruth.overlayTarget, oldSettings.targetPackageName) || !Objects.equals(theTruth.targetOverlayableName, oldSettings.targetOverlayableName) || theTruth.isStaticOverlayPackage() != oldSettings.isStatic) {
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
        int i;
        String[] strArr;
        ArraySet<String> enabledCategories;
        String str;
        int i2;
        Iterator<String> iter;
        int i3;
        ArrayMap<String, OverlayInfo> storedOverlayInfos;
        ArrayMap<String, OverlayInfo> storedOverlayInfos2;
        String str2;
        ArrayMap<String, List<OverlayInfo>> tmp;
        int tmpSize;
        PackageInfo overlayPackage;
        int tmpSize2;
        List<PackageInfo> overlayPackages;
        String str3 = "' for user ";
        Set<String> packagesToUpdateAssets = new ArraySet<>();
        ArrayMap<String, List<OverlayInfo>> tmp2 = this.mSettings.getOverlaysForUser(newUserId);
        int overlayPackagesSize = tmp2.size();
        ArrayMap<String, OverlayInfo> storedOverlayInfos3 = new ArrayMap<>(overlayPackagesSize);
        for (int i4 = 0; i4 < overlayPackagesSize; i4++) {
            List<OverlayInfo> chunk = tmp2.valueAt(i4);
            int chunkSize = chunk.size();
            for (int j = 0; j < chunkSize; j++) {
                OverlayInfo oi = chunk.get(j);
                storedOverlayInfos3.put(oi.packageName, oi);
            }
        }
        List<PackageInfo> overlayPackages2 = this.mPackageManager.getOverlayPackages(newUserId);
        int overlayPackagesSize2 = overlayPackages2.size();
        int i5 = 0;
        while (i5 < overlayPackagesSize2) {
            PackageInfo overlayPackage2 = overlayPackages2.get(i5);
            OverlayInfo oi2 = storedOverlayInfos3.get(overlayPackage2.packageName);
            if (mustReinitializeOverlay(overlayPackage2, oi2)) {
                if (oi2 != null) {
                    packagesToUpdateAssets.add(oi2.targetPackageName);
                }
                tmp = tmp2;
                overlayPackage = overlayPackage2;
                tmpSize = overlayPackagesSize;
                tmpSize2 = overlayPackagesSize2;
                str2 = str3;
                overlayPackages = overlayPackages2;
                storedOverlayInfos2 = storedOverlayInfos3;
                this.mSettings.init(overlayPackage2.packageName, newUserId, overlayPackage2.overlayTarget, overlayPackage2.targetOverlayableName, overlayPackage2.applicationInfo.getBaseCodePath(), overlayPackage2.isStaticOverlayPackage(), overlayPackage2.overlayPriority, overlayPackage2.overlayCategory);
            } else {
                storedOverlayInfos2 = storedOverlayInfos3;
                str2 = str3;
                tmp = tmp2;
                tmpSize = overlayPackagesSize;
                overlayPackage = overlayPackage2;
                tmpSize2 = overlayPackagesSize2;
                overlayPackages = overlayPackages2;
            }
            storedOverlayInfos2.remove(overlayPackage.packageName);
            i5++;
            storedOverlayInfos3 = storedOverlayInfos2;
            overlayPackages2 = overlayPackages;
            overlayPackagesSize2 = tmpSize2;
            tmp2 = tmp;
            overlayPackagesSize = tmpSize;
            str3 = str2;
        }
        ArrayMap<String, OverlayInfo> storedOverlayInfos4 = storedOverlayInfos3;
        String str4 = str3;
        int tmpSize3 = overlayPackagesSize2;
        List<PackageInfo> overlayPackages3 = overlayPackages2;
        int storedOverlayInfosSize = storedOverlayInfos4.size();
        for (int i6 = 0; i6 < storedOverlayInfosSize; i6++) {
            OverlayInfo oi3 = storedOverlayInfos4.valueAt(i6);
            this.mSettings.remove(oi3.packageName, oi3.userId);
            removeIdmapIfPossible(oi3);
            packagesToUpdateAssets.add(oi3.targetPackageName);
        }
        int i7 = 0;
        while (true) {
            i = 0;
            if (i7 >= tmpSize3) {
                break;
            }
            PackageInfo overlayPackage3 = overlayPackages3.get(i7);
            try {
                i3 = newUserId;
                storedOverlayInfos = storedOverlayInfos4;
            } catch (OverlayManagerSettings.BadKeyException e) {
                e = e;
                i3 = newUserId;
                storedOverlayInfos = storedOverlayInfos4;
            }
            try {
                updateState(overlayPackage3.overlayTarget, overlayPackage3.packageName, i3, 0);
            } catch (OverlayManagerSettings.BadKeyException e2) {
                e = e2;
                Slog.e("OverlayManager", "failed to update settings", e);
                this.mSettings.remove(overlayPackage3.packageName, i3);
                packagesToUpdateAssets.add(overlayPackage3.overlayTarget);
                i7++;
                storedOverlayInfos4 = storedOverlayInfos;
            }
            packagesToUpdateAssets.add(overlayPackage3.overlayTarget);
            i7++;
            storedOverlayInfos4 = storedOverlayInfos;
        }
        Iterator<String> iter2 = packagesToUpdateAssets.iterator();
        while (iter2.hasNext()) {
            String targetPackageName = iter2.next();
            if (this.mPackageManager.getPackageInfo(targetPackageName, newUserId) == null) {
                iter2.remove();
            }
        }
        ArraySet<String> enabledCategories2 = new ArraySet<>();
        ArrayMap<String, List<OverlayInfo>> userOverlays = this.mSettings.getOverlaysForUser(newUserId);
        int userOverlayTargetCount = userOverlays.size();
        int i8 = 0;
        while (i8 < userOverlayTargetCount) {
            List<OverlayInfo> overlayList = userOverlays.valueAt(i8);
            int overlayCount = overlayList != null ? overlayList.size() : i;
            int j2 = 0;
            while (j2 < overlayCount) {
                int storedOverlayInfosSize2 = storedOverlayInfosSize;
                OverlayInfo oi4 = overlayList.get(j2);
                if (!oi4.isEnabled()) {
                    iter = iter2;
                } else {
                    iter = iter2;
                    enabledCategories2.add(oi4.category);
                }
                j2++;
                iter2 = iter;
                storedOverlayInfosSize = storedOverlayInfosSize2;
            }
            i8++;
            i = 0;
        }
        String[] strArr2 = this.mDefaultOverlays;
        int length = strArr2.length;
        int i9 = 0;
        while (i9 < length) {
            String defaultOverlay = strArr2[i9];
            try {
                OverlayInfo oi5 = this.mSettings.getOverlayInfo(defaultOverlay, newUserId);
                if (enabledCategories2.contains(oi5.category)) {
                    strArr = strArr2;
                    enabledCategories = enabledCategories2;
                    str = str4;
                    i2 = length;
                } else {
                    StringBuilder sb = new StringBuilder();
                    strArr = strArr2;
                    try {
                        sb.append("Enabling default overlay '");
                        sb.append(defaultOverlay);
                        sb.append("' for target '");
                        sb.append(oi5.targetPackageName);
                        sb.append("' in category '");
                        sb.append(oi5.category);
                        str = str4;
                        try {
                            sb.append(str);
                            sb.append(newUserId);
                            Slog.w("OverlayManager", sb.toString());
                            i2 = length;
                        } catch (OverlayManagerSettings.BadKeyException e3) {
                            e = e3;
                            i2 = length;
                        }
                        try {
                            enabledCategories = enabledCategories2;
                            try {
                                this.mSettings.setEnabled(oi5.packageName, newUserId, true);
                            } catch (OverlayManagerSettings.BadKeyException e4) {
                                e = e4;
                            }
                        } catch (OverlayManagerSettings.BadKeyException e5) {
                            e = e5;
                            enabledCategories = enabledCategories2;
                            Slog.e("OverlayManager", "Failed to set default overlay '" + defaultOverlay + str + newUserId, e);
                            i9++;
                            length = i2;
                            enabledCategories2 = enabledCategories;
                            str4 = str;
                            strArr2 = strArr;
                        }
                        try {
                            if (updateState(oi5.targetPackageName, oi5.packageName, newUserId, 0)) {
                                packagesToUpdateAssets.add(oi5.targetPackageName);
                            }
                        } catch (OverlayManagerSettings.BadKeyException e6) {
                            e = e6;
                            Slog.e("OverlayManager", "Failed to set default overlay '" + defaultOverlay + str + newUserId, e);
                            i9++;
                            length = i2;
                            enabledCategories2 = enabledCategories;
                            str4 = str;
                            strArr2 = strArr;
                        }
                    } catch (OverlayManagerSettings.BadKeyException e7) {
                        e = e7;
                        enabledCategories = enabledCategories2;
                        str = str4;
                        i2 = length;
                        Slog.e("OverlayManager", "Failed to set default overlay '" + defaultOverlay + str + newUserId, e);
                        i9++;
                        length = i2;
                        enabledCategories2 = enabledCategories;
                        str4 = str;
                        strArr2 = strArr;
                    }
                }
            } catch (OverlayManagerSettings.BadKeyException e8) {
                e = e8;
                strArr = strArr2;
            }
            i9++;
            length = i2;
            enabledCategories2 = enabledCategories;
            str4 = str;
            strArr2 = strArr;
        }
        return new ArrayList<>(packagesToUpdateAssets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUserRemoved(int userId) {
        this.mSettings.removeUser(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageAdded(String packageName, int userId) {
        updateAndRefreshOverlaysForTarget(packageName, userId, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageChanged(String packageName, int userId) {
        updateAndRefreshOverlaysForTarget(packageName, userId, 0);
    }

    void onTargetPackageReplacing(String packageName, int userId) {
        updateAndRefreshOverlaysForTarget(packageName, userId, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageReplaced(String packageName, int userId) {
        updateAndRefreshOverlaysForTarget(packageName, userId, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTargetPackageRemoved(String packageName, int userId) {
        updateAndRefreshOverlaysForTarget(packageName, userId, 0);
    }

    private void updateAndRefreshOverlaysForTarget(String targetPackageName, int userId, int flags) {
        List<OverlayInfo> targetOverlays = this.mSettings.getOverlaysForTarget(targetPackageName, userId);
        boolean modified = false;
        for (OverlayInfo oi : targetOverlays) {
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
        if (!modified) {
            List<String> enabledOverlayPaths = new ArrayList<>(targetOverlays.size());
            for (OverlayInfo oi2 : this.mSettings.getOverlaysForTarget(PackageManagerService.PLATFORM_PACKAGE_NAME, userId)) {
                if (oi2.isEnabled()) {
                    enabledOverlayPaths.add(oi2.baseCodePath);
                }
            }
            for (OverlayInfo oi3 : targetOverlays) {
                if (oi3.isEnabled()) {
                    enabledOverlayPaths.add(oi3.baseCodePath);
                }
            }
            PackageInfo packageInfo = this.mPackageManager.getPackageInfo(targetPackageName, userId);
            ApplicationInfo appInfo = packageInfo == null ? null : packageInfo.applicationInfo;
            String[] resourceDirs = appInfo != null ? appInfo.resourceDirs : null;
            if (ArrayUtils.size(resourceDirs) != enabledOverlayPaths.size()) {
                modified = true;
            } else if (resourceDirs != null) {
                int index = 0;
                while (true) {
                    if (index < resourceDirs.length) {
                        if (resourceDirs[index].equals(enabledOverlayPaths.get(index))) {
                            index++;
                        } else {
                            modified = true;
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
        if (modified) {
            this.mListener.onOverlaysChanged(targetPackageName, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onOverlayPackageAdded(String packageName, int userId) {
        PackageInfo overlayPackage = this.mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w("OverlayManager", "overlay package " + packageName + " was added, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }
        this.mSettings.init(packageName, userId, overlayPackage.overlayTarget, overlayPackage.targetOverlayableName, overlayPackage.applicationInfo.getBaseCodePath(), overlayPackage.isStaticOverlayPackage(), overlayPackage.overlayPriority, overlayPackage.overlayCategory);
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
    public void onOverlayPackageReplacing(String packageName, int userId) {
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
    public void onOverlayPackageReplaced(String packageName, int userId) {
        PackageInfo pkg = this.mPackageManager.getPackageInfo(packageName, userId);
        if (pkg == null) {
            Slog.w("OverlayManager", "overlay package " + packageName + " was replaced, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }
        try {
            OverlayInfo oldOi = this.mSettings.getOverlayInfo(packageName, userId);
            if (mustReinitializeOverlay(pkg, oldOi)) {
                if (oldOi != null && !oldOi.targetPackageName.equals(pkg.overlayTarget)) {
                    this.mListener.onOverlaysChanged(pkg.overlayTarget, userId);
                }
                this.mSettings.init(packageName, userId, pkg.overlayTarget, pkg.targetOverlayableName, pkg.applicationInfo.getBaseCodePath(), pkg.isStaticOverlayPackage(), pkg.overlayPriority, pkg.overlayCategory);
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
                this.mListener.onOverlaysChanged(overlayInfo.targetPackageName, userId);
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
            boolean modified = false;
            allOverlays.remove(oi);
            for (int i = 0; i < allOverlays.size(); i++) {
                String disabledOverlayPackageName = allOverlays.get(i).packageName;
                PackageInfo disabledOverlayPackageInfo = this.mPackageManager.getPackageInfo(disabledOverlayPackageName, userId);
                if (disabledOverlayPackageInfo == null) {
                    modified |= this.mSettings.remove(disabledOverlayPackageName, userId);
                } else if (!disabledOverlayPackageInfo.isStaticOverlayPackage() && (!withinCategory || Objects.equals(disabledOverlayPackageInfo.overlayCategory, oi.category))) {
                    modified = modified | this.mSettings.setEnabled(disabledOverlayPackageName, userId, false) | updateState(targetPackageName, disabledOverlayPackageName, userId, 0);
                }
            }
            if (modified | this.mSettings.setEnabled(packageName, userId, true) | updateState(targetPackageName, packageName, userId, 0)) {
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
    public void dump(PrintWriter pw, DumpState dumpState) {
        this.mSettings.dump(pw, dumpState);
        if (dumpState.getPackageName() == null) {
            pw.println("Default overlays: " + TextUtils.join(";", this.mDefaultOverlays));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String[] getDefaultOverlayPackages() {
        return this.mDefaultOverlays;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<String> getEnabledOverlayPackageNames(String targetPackageName, int userId) {
        List<OverlayInfo> overlays = this.mSettings.getOverlaysForTarget(targetPackageName, userId);
        List<String> paths = new ArrayList<>(overlays.size());
        int n = overlays.size();
        for (int i = 0; i < n; i++) {
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
