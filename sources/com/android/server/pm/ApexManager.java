package com.android.server.pm;

import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.IApexService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.sysprop.ApexProperties;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import java.io.File;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ApexManager {
    static final int MATCH_ACTIVE_PACKAGE = 1;
    static final int MATCH_FACTORY_PACKAGE = 2;
    static final String TAG = "ApexManager";
    @GuardedBy({"mLock"})
    private List<PackageInfo> mAllPackagesCache;
    @GuardedBy({"mLock"})
    private ArrayMap<String, PackageInfo> mApexNameToPackageInfoCache;
    private final IApexService mApexService;
    private final Context mContext;
    private final Object mLock = new Object();

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    @interface PackageInfoFlags {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ApexManager(Context context) {
        try {
            this.mApexService = IApexService.Stub.asInterface(ServiceManager.getServiceOrThrow("apexservice"));
            this.mContext = context;
        } catch (ServiceManager.ServiceNotFoundException e) {
            throw new IllegalStateException("Required service apexservice not available");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemReady() {
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.pm.ApexManager.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                ApexManager.this.onBootCompleted();
                ApexManager.this.mContext.unregisterReceiver(this);
            }
        }, new IntentFilter("android.intent.action.BOOT_COMPLETED"));
    }

    private void populateAllPackagesCacheIfNeeded() {
        synchronized (this.mLock) {
            if (this.mAllPackagesCache != null) {
                return;
            }
            this.mApexNameToPackageInfoCache = new ArrayMap<>();
            try {
                this.mAllPackagesCache = new ArrayList();
                HashSet<String> activePackagesSet = new HashSet<>();
                HashSet<String> factoryPackagesSet = new HashSet<>();
                ApexInfo[] allPkgs = this.mApexService.getAllPackages();
                for (ApexInfo ai : allPkgs) {
                    if (new File(ai.packagePath).isDirectory()) {
                        break;
                    }
                    try {
                        PackageInfo pkg = PackageParser.generatePackageInfoFromApex(ai, 134217856);
                        this.mAllPackagesCache.add(pkg);
                        if (ai.isActive) {
                            if (activePackagesSet.contains(pkg.packageName)) {
                                throw new IllegalStateException("Two active packages have the same name: " + pkg.packageName);
                            }
                            activePackagesSet.add(ai.packageName);
                            this.mApexNameToPackageInfoCache.put(ai.packageName, pkg);
                        }
                        if (ai.isFactory) {
                            if (factoryPackagesSet.contains(pkg.packageName)) {
                                throw new IllegalStateException("Two factory packages have the same name: " + pkg.packageName);
                            }
                            factoryPackagesSet.add(ai.packageName);
                        }
                    } catch (PackageParser.PackageParserException pe) {
                        throw new IllegalStateException("Unable to parse: " + ai, pe);
                    }
                }
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to retrieve packages from apexservice: " + re.toString());
                throw new RuntimeException(re);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageInfo getPackageInfo(String packageName, int flags) {
        populateAllPackagesCacheIfNeeded();
        boolean matchActive = (flags & 1) != 0;
        boolean matchFactory = (flags & 2) != 0;
        for (PackageInfo packageInfo : this.mAllPackagesCache) {
            if (packageInfo.packageName.equals(packageName) && (!matchActive || isActive(packageInfo))) {
                if (!matchFactory || isFactory(packageInfo)) {
                    return packageInfo;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Deprecated
    public PackageInfo getPackageInfoForApexName(String apexName) {
        populateAllPackagesCacheIfNeeded();
        return this.mApexNameToPackageInfoCache.get(apexName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<PackageInfo> getActivePackages() {
        populateAllPackagesCacheIfNeeded();
        return (List) this.mAllPackagesCache.stream().filter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$ApexManager$9yA7boi20-T0AThVHsxq0Cfs1sk
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isActive;
                isActive = ApexManager.isActive((PackageInfo) obj);
                return isActive;
            }
        }).collect(Collectors.toList());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<PackageInfo> getFactoryPackages() {
        populateAllPackagesCacheIfNeeded();
        return (List) this.mAllPackagesCache.stream().filter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$ApexManager$KRyGqIC_rXI5fS6Qv87QmIXpa4k
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isFactory;
                isFactory = ApexManager.isFactory((PackageInfo) obj);
                return isFactory;
            }
        }).collect(Collectors.toList());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<PackageInfo> getInactivePackages() {
        populateAllPackagesCacheIfNeeded();
        return (List) this.mAllPackagesCache.stream().filter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$ApexManager$FbNDnGihrL8WZzgNwB1-eOeYBpE
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ApexManager.lambda$getInactivePackages$2((PackageInfo) obj);
            }
        }).collect(Collectors.toList());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$getInactivePackages$2(PackageInfo item) {
        return !isActive(item);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isApexPackage(String packageName) {
        populateAllPackagesCacheIfNeeded();
        for (PackageInfo packageInfo : this.mAllPackagesCache) {
            if (packageInfo.packageName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ApexSessionInfo getStagedSessionInfo(int sessionId) {
        try {
            ApexSessionInfo apexSessionInfo = this.mApexService.getStagedSessionInfo(sessionId);
            if (apexSessionInfo.isUnknown) {
                return null;
            }
            return apexSessionInfo;
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean submitStagedSession(int sessionId, int[] childSessionIds, ApexInfoList apexInfoList) {
        try {
            return this.mApexService.submitStagedSession(sessionId, childSessionIds, apexInfoList);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean markStagedSessionReady(int sessionId) {
        try {
            return this.mApexService.markStagedSessionReady(sessionId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void markStagedSessionSuccessful(int sessionId) {
        try {
            this.mApexService.markStagedSessionSuccessful(sessionId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to mark session " + sessionId + " as successful", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isApexSupported() {
        return ((Boolean) ApexProperties.updatable().orElse(false)).booleanValue();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean abortActiveSession() {
        try {
            this.mApexService.abortActiveSession();
            return true;
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean uninstallApex(String apexPackagePath) {
        try {
            this.mApexService.unstagePackages(Collections.singletonList(apexPackagePath));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isActive(PackageInfo packageInfo) {
        return (packageInfo.applicationInfo.flags & DumpState.DUMP_VOLUMES) != 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isFactory(PackageInfo packageInfo) {
        return (packageInfo.applicationInfo.flags & 1) != 0;
    }

    void dumpFromPackagesCache(List<PackageInfo> packagesCache, String packageName, IndentingPrintWriter ipw) {
        ipw.println();
        ipw.increaseIndent();
        for (PackageInfo pi : packagesCache) {
            if (packageName == null || packageName.equals(pi.packageName)) {
                ipw.println(pi.packageName);
                ipw.increaseIndent();
                ipw.println("Version: " + pi.versionCode);
                ipw.println("Path: " + pi.applicationInfo.sourceDir);
                ipw.println("IsActive: " + isActive(pi));
                ipw.println("IsFactory: " + isFactory(pi));
                ipw.decreaseIndent();
            }
        }
        ipw.decreaseIndent();
        ipw.println();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String packageName) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
        try {
            populateAllPackagesCacheIfNeeded();
            ipw.println();
            ipw.println("Active APEX packages:");
            dumpFromPackagesCache(getActivePackages(), packageName, ipw);
            ipw.println("Inactive APEX packages:");
            dumpFromPackagesCache(getInactivePackages(), packageName, ipw);
            ipw.println("Factory APEX packages:");
            dumpFromPackagesCache(getFactoryPackages(), packageName, ipw);
            ipw.increaseIndent();
            ipw.println("APEX session state:");
            ipw.increaseIndent();
            ApexSessionInfo[] sessions = this.mApexService.getSessions();
            for (ApexSessionInfo si : sessions) {
                ipw.println("Session ID: " + si.sessionId);
                ipw.increaseIndent();
                if (si.isUnknown) {
                    ipw.println("State: UNKNOWN");
                } else if (si.isVerified) {
                    ipw.println("State: VERIFIED");
                } else if (si.isStaged) {
                    ipw.println("State: STAGED");
                } else if (si.isActivated) {
                    ipw.println("State: ACTIVATED");
                } else if (si.isActivationFailed) {
                    ipw.println("State: ACTIVATION FAILED");
                } else if (si.isSuccess) {
                    ipw.println("State: SUCCESS");
                } else if (si.isRollbackInProgress) {
                    ipw.println("State: ROLLBACK IN PROGRESS");
                } else if (si.isRolledBack) {
                    ipw.println("State: ROLLED BACK");
                } else if (si.isRollbackFailed) {
                    ipw.println("State: ROLLBACK FAILED");
                }
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
        } catch (RemoteException e) {
            ipw.println("Couldn't communicate with apexd.");
        }
    }

    public void onBootCompleted() {
        populateAllPackagesCacheIfNeeded();
    }
}
