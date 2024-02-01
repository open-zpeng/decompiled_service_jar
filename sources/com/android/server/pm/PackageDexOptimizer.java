package com.android.server.pm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.WorkSource;
import android.util.Log;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.CompilerStats;
import com.android.server.pm.Installer;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.dex.PackageDexUsage;
import dalvik.system.DexFile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/* loaded from: classes.dex */
public class PackageDexOptimizer {
    public static final int DEX_OPT_FAILED = -1;
    public static final int DEX_OPT_PERFORMED = 1;
    public static final int DEX_OPT_SKIPPED = 0;
    static final String OAT_DIR_NAME = "oat";
    public static final String SKIP_SHARED_LIBRARY_CHECK = "&";
    private static final String TAG = "PackageManager.DexOptimizer";
    private static final long WAKELOCK_TIMEOUT_MS = 660000;
    @GuardedBy("mInstallLock")
    private final PowerManager.WakeLock mDexoptWakeLock;
    private final Object mInstallLock;
    @GuardedBy("mInstallLock")
    private final Installer mInstaller;
    private volatile boolean mSystemReady;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageDexOptimizer(Installer installer, Object installLock, Context context, String wakeLockTag) {
        this.mInstaller = installer;
        this.mInstallLock = installLock;
        PowerManager powerManager = (PowerManager) context.getSystemService("power");
        this.mDexoptWakeLock = powerManager.newWakeLock(1, wakeLockTag);
    }

    protected PackageDexOptimizer(PackageDexOptimizer from) {
        this.mInstaller = from.mInstaller;
        this.mInstallLock = from.mInstallLock;
        this.mDexoptWakeLock = from.mDexoptWakeLock;
        this.mSystemReady = from.mSystemReady;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean canOptimizePackage(PackageParser.Package pkg) {
        if ((pkg.applicationInfo.flags & 4) == 0) {
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int performDexOpt(PackageParser.Package pkg, String[] sharedLibraries, String[] instructionSets, CompilerStats.PackageStats packageStats, PackageDexUsage.PackageUseInfo packageUseInfo, DexoptOptions options) {
        int performDexOptLI;
        if (pkg.applicationInfo.uid == -1) {
            throw new IllegalArgumentException("Dexopt for " + pkg.packageName + " has invalid uid.");
        } else if (!canOptimizePackage(pkg)) {
            return 0;
        } else {
            synchronized (this.mInstallLock) {
                long acquireTime = acquireWakeLockLI(pkg.applicationInfo.uid);
                performDexOptLI = performDexOptLI(pkg, sharedLibraries, instructionSets, packageStats, packageUseInfo, options);
                releaseWakeLockLI(acquireTime);
            }
            return performDexOptLI;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:69:0x0186  */
    @com.android.internal.annotations.GuardedBy("mInstallLock")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int performDexOptLI(android.content.pm.PackageParser.Package r37, java.lang.String[] r38, java.lang.String[] r39, com.android.server.pm.CompilerStats.PackageStats r40, com.android.server.pm.dex.PackageDexUsage.PackageUseInfo r41, com.android.server.pm.dex.DexoptOptions r42) {
        /*
            Method dump skipped, instructions count: 606
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageDexOptimizer.performDexOptLI(android.content.pm.PackageParser$Package, java.lang.String[], java.lang.String[], com.android.server.pm.CompilerStats$PackageStats, com.android.server.pm.dex.PackageDexUsage$PackageUseInfo, com.android.server.pm.dex.DexoptOptions):int");
    }

    @GuardedBy("mInstallLock")
    private int dexOptPath(PackageParser.Package pkg, String path, String isa, String compilerFilter, boolean profileUpdated, String classLoaderContext, int dexoptFlags, int uid, CompilerStats.PackageStats packageStats, boolean downgrade, String profileName, String dexMetadataPath, int compilationReason) {
        long startTime;
        int dexoptNeeded = getDexoptNeeded(path, isa, compilerFilter, classLoaderContext, profileUpdated, downgrade);
        if (Math.abs(dexoptNeeded) == 0) {
            return 0;
        }
        String oatDir = createOatDirIfSupported(pkg, isa);
        Log.i(TAG, "Running dexopt (dexoptNeeded=" + dexoptNeeded + ") on: " + path + " pkg=" + pkg.applicationInfo.packageName + " isa=" + isa + " dexoptFlags=" + printDexoptFlags(dexoptFlags) + " targetFilter=" + compilerFilter + " oatDir=" + oatDir + " classLoaderContext=" + classLoaderContext);
        try {
            startTime = System.currentTimeMillis();
        } catch (Installer.InstallerException e) {
            e = e;
        }
        try {
            this.mInstaller.dexopt(path, uid, pkg.packageName, isa, dexoptNeeded, oatDir, dexoptFlags, compilerFilter, pkg.volumeUuid, classLoaderContext, pkg.applicationInfo.seInfo, false, pkg.applicationInfo.targetSdkVersion, profileName, dexMetadataPath, getAugmentedReasonName(compilationReason, dexMetadataPath != null));
            if (packageStats != null) {
                long endTime = System.currentTimeMillis();
                packageStats.setCompileTime(path, (int) (endTime - startTime));
            }
            return 1;
        } catch (Installer.InstallerException e2) {
            e = e2;
            Slog.w(TAG, "Failed to dexopt", e);
            return -1;
        }
    }

    private String getAugmentedReasonName(int compilationReason, boolean useDexMetadata) {
        String annotation = useDexMetadata ? ArtManagerService.DEXOPT_REASON_WITH_DEX_METADATA_ANNOTATION : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        return PackageManagerServiceCompilerMapping.getReasonName(compilationReason) + annotation;
    }

    public int dexOptSecondaryDexPath(ApplicationInfo info, String path, PackageDexUsage.DexUseInfo dexUseInfo, DexoptOptions options) {
        int dexOptSecondaryDexPathLI;
        if (info.uid == -1) {
            throw new IllegalArgumentException("Dexopt for path " + path + " has invalid uid.");
        }
        synchronized (this.mInstallLock) {
            long acquireTime = acquireWakeLockLI(info.uid);
            dexOptSecondaryDexPathLI = dexOptSecondaryDexPathLI(info, path, dexUseInfo, options);
            releaseWakeLockLI(acquireTime);
        }
        return dexOptSecondaryDexPathLI;
    }

    @GuardedBy("mInstallLock")
    private long acquireWakeLockLI(int uid) {
        if (!this.mSystemReady) {
            return -1L;
        }
        this.mDexoptWakeLock.setWorkSource(new WorkSource(uid));
        this.mDexoptWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        return SystemClock.elapsedRealtime();
    }

    @GuardedBy("mInstallLock")
    private void releaseWakeLockLI(long acquireTime) {
        if (acquireTime < 0) {
            return;
        }
        try {
            if (this.mDexoptWakeLock.isHeld()) {
                this.mDexoptWakeLock.release();
            }
            long duration = SystemClock.elapsedRealtime() - acquireTime;
            if (duration >= WAKELOCK_TIMEOUT_MS) {
                Slog.wtf(TAG, "WakeLock " + this.mDexoptWakeLock.getTag() + " time out. Operation took " + duration + " ms. Thread: " + Thread.currentThread().getName());
            }
        } catch (Exception e) {
            Slog.wtf(TAG, "Error while releasing " + this.mDexoptWakeLock.getTag() + " lock", e);
        }
    }

    @GuardedBy("mInstallLock")
    private int dexOptSecondaryDexPathLI(ApplicationInfo info, String path, PackageDexUsage.DexUseInfo dexUseInfo, DexoptOptions options) {
        int dexoptFlags;
        String str = path;
        if (options.isDexoptOnlySharedDex() && !dexUseInfo.isUsedByOtherApps()) {
            return 0;
        }
        String compilerFilter = getRealCompilerFilter(info, options.getCompilerFilter(), dexUseInfo.isUsedByOtherApps());
        int dexoptFlags2 = getDexFlags(info, compilerFilter, options) | 32;
        if (info.deviceProtectedDataDir != null && FileUtils.contains(info.deviceProtectedDataDir, str)) {
            dexoptFlags = dexoptFlags2 | 256;
        } else if (info.credentialProtectedDataDir == null || !FileUtils.contains(info.credentialProtectedDataDir, str)) {
            Slog.e(TAG, "Could not infer CE/DE storage for package " + info.packageName);
            return -1;
        } else {
            dexoptFlags = dexoptFlags2 | 128;
        }
        int dexoptFlags3 = dexoptFlags;
        Log.d(TAG, "Running dexopt on: " + str + " pkg=" + info.packageName + " isa=" + dexUseInfo.getLoaderIsas() + " dexoptFlags=" + printDexoptFlags(dexoptFlags3) + " target-filter=" + compilerFilter);
        int reason = options.getCompilationReason();
        try {
            for (String isa : dexUseInfo.getLoaderIsas()) {
                int reason2 = reason;
                int dexoptFlags4 = dexoptFlags3;
                String compilerFilter2 = compilerFilter;
                try {
                    this.mInstaller.dexopt(str, info.uid, info.packageName, isa, 0, null, dexoptFlags3, compilerFilter, info.volumeUuid, SKIP_SHARED_LIBRARY_CHECK, info.seInfoUser, options.isDowngrade(), info.targetSdkVersion, null, null, PackageManagerServiceCompilerMapping.getReasonName(reason));
                    str = path;
                    compilerFilter = compilerFilter2;
                    reason = reason2;
                    dexoptFlags3 = dexoptFlags4;
                } catch (Installer.InstallerException e) {
                    e = e;
                    Slog.w(TAG, "Failed to dexopt", e);
                    return -1;
                }
            }
            return 1;
        } catch (Installer.InstallerException e2) {
            e = e2;
        }
    }

    protected int adjustDexoptNeeded(int dexoptNeeded) {
        return dexoptNeeded;
    }

    protected int adjustDexoptFlags(int dexoptFlags) {
        return dexoptFlags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpDexoptState(IndentingPrintWriter pw, PackageParser.Package pkg, PackageDexUsage.PackageUseInfo useInfo) {
        String[] instructionSets = InstructionSets.getAppDexInstructionSets(pkg.applicationInfo);
        String[] dexCodeInstructionSets = InstructionSets.getDexCodeInstructionSets(instructionSets);
        List<String> paths = pkg.getAllCodePathsExcludingResourceOnly();
        for (String path : paths) {
            pw.println("path: " + path);
            pw.increaseIndent();
            for (String isa : dexCodeInstructionSets) {
                try {
                    DexFile.OptimizationInfo info = DexFile.getDexFileOptimizationInfo(path, isa);
                    pw.println(isa + ": [status=" + info.getStatus() + "] [reason=" + info.getReason() + "]");
                } catch (IOException ioe) {
                    pw.println(isa + ": [Exception]: " + ioe.getMessage());
                }
            }
            if (useInfo.isUsedByOtherApps(path)) {
                pw.println("used by other apps: " + useInfo.getLoadingPackages(path));
            }
            Map<String, PackageDexUsage.DexUseInfo> dexUseInfoMap = useInfo.getDexUseInfoMap();
            if (!dexUseInfoMap.isEmpty()) {
                pw.println("known secondary dex files:");
                pw.increaseIndent();
                for (Map.Entry<String, PackageDexUsage.DexUseInfo> e : dexUseInfoMap.entrySet()) {
                    String dex = e.getKey();
                    PackageDexUsage.DexUseInfo dexUseInfo = e.getValue();
                    pw.println(dex);
                    pw.increaseIndent();
                    pw.println("class loader context: " + dexUseInfo.getClassLoaderContext());
                    if (dexUseInfo.isUsedByOtherApps()) {
                        pw.println("used by other apps: " + dexUseInfo.getLoadingPackages());
                    }
                    pw.decreaseIndent();
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }
    }

    private String getRealCompilerFilter(ApplicationInfo info, String targetCompilerFilter, boolean isUsedByOtherApps) {
        int flags = info.flags;
        boolean vmSafeMode = (flags & 16384) != 0;
        if (info.isPrivilegedApp() && DexManager.isPackageSelectedToRunOob(info.packageName)) {
            return "verify";
        }
        if (vmSafeMode) {
            return DexFile.getSafeModeCompilerFilter(targetCompilerFilter);
        }
        if (DexFile.isProfileGuidedCompilerFilter(targetCompilerFilter) && isUsedByOtherApps) {
            return PackageManagerServiceCompilerMapping.getCompilerFilterForReason(6);
        }
        return targetCompilerFilter;
    }

    private int getDexFlags(PackageParser.Package pkg, String compilerFilter, DexoptOptions options) {
        return getDexFlags(pkg.applicationInfo, compilerFilter, options);
    }

    private boolean isAppImageEnabled() {
        return SystemProperties.get("dalvik.vm.appimageformat", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS).length() > 0;
    }

    private int getDexFlags(ApplicationInfo info, String compilerFilter, DexoptOptions options) {
        int flags = info.flags;
        boolean generateAppImage = true;
        boolean debuggable = (flags & 2) != 0;
        boolean isProfileGuidedFilter = DexFile.isProfileGuidedCompilerFilter(compilerFilter);
        boolean isPublic = !info.isForwardLocked() && (!isProfileGuidedFilter || options.isDexoptInstallWithDexMetadata());
        int profileFlag = isProfileGuidedFilter ? 16 : 0;
        int hiddenApiFlag = info.getHiddenApiEnforcementPolicy() == 0 ? 0 : 1024;
        int compilationReason = options.getCompilationReason();
        boolean generateCompactDex = true;
        switch (compilationReason) {
            case 0:
            case 1:
            case 2:
                generateCompactDex = false;
                break;
        }
        if (!isProfileGuidedFilter || ((info.splitDependencies != null && info.requestsIsolatedSplitLoading()) || !isAppImageEnabled())) {
            generateAppImage = false;
        }
        int dexFlags = (generateAppImage ? 4096 : 0) | (isPublic ? 2 : 0) | (debuggable ? 4 : 0) | profileFlag | (options.isBootComplete() ? 8 : 0) | (options.isDexoptIdleBackgroundJob() ? 512 : 0) | (generateCompactDex ? 2048 : 0) | hiddenApiFlag;
        return adjustDexoptFlags(dexFlags);
    }

    private int getDexoptNeeded(String path, String isa, String compilerFilter, String classLoaderContext, boolean newProfile, boolean downgrade) {
        try {
            int dexoptNeeded = DexFile.getDexOptNeeded(path, isa, compilerFilter, classLoaderContext, newProfile, downgrade);
            return adjustDexoptNeeded(dexoptNeeded);
        } catch (IOException ioe) {
            Slog.w(TAG, "IOException reading apk: " + path, ioe);
            return -1;
        }
    }

    private boolean isProfileUpdated(PackageParser.Package pkg, int uid, String profileName, String compilerFilter) {
        if (DexFile.isProfileGuidedCompilerFilter(compilerFilter)) {
            try {
                return this.mInstaller.mergeProfiles(uid, pkg.packageName, profileName);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Failed to merge profiles", e);
                return false;
            }
        }
        return false;
    }

    private String createOatDirIfSupported(PackageParser.Package pkg, String dexInstructionSet) {
        if (pkg.canHaveOatDir()) {
            File codePath = new File(pkg.codePath);
            if (codePath.isDirectory()) {
                File oatDir = getOatDir(codePath);
                try {
                    this.mInstaller.createOatDir(oatDir.getAbsolutePath(), dexInstructionSet);
                    return oatDir.getAbsolutePath();
                } catch (Installer.InstallerException e) {
                    Slog.w(TAG, "Failed to create oat dir", e);
                    return null;
                }
            }
            return null;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static File getOatDir(File codePath) {
        return new File(codePath, OAT_DIR_NAME);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemReady() {
        this.mSystemReady = true;
    }

    private String printDexoptFlags(int flags) {
        ArrayList<String> flagsList = new ArrayList<>();
        if ((flags & 8) == 8) {
            flagsList.add("boot_complete");
        }
        if ((flags & 4) == 4) {
            flagsList.add("debuggable");
        }
        if ((flags & 16) == 16) {
            flagsList.add("profile_guided");
        }
        if ((flags & 2) == 2) {
            flagsList.add("public");
        }
        if ((flags & 32) == 32) {
            flagsList.add("secondary");
        }
        if ((flags & 64) == 64) {
            flagsList.add("force");
        }
        if ((flags & 128) == 128) {
            flagsList.add("storage_ce");
        }
        if ((flags & 256) == 256) {
            flagsList.add("storage_de");
        }
        if ((flags & 512) == 512) {
            flagsList.add("idle_background_job");
        }
        if ((flags & 1024) == 1024) {
            flagsList.add("enable_hidden_api_checks");
        }
        return String.join(",", flagsList);
    }

    /* loaded from: classes.dex */
    public static class ForcedUpdatePackageDexOptimizer extends PackageDexOptimizer {
        public ForcedUpdatePackageDexOptimizer(Installer installer, Object installLock, Context context, String wakeLockTag) {
            super(installer, installLock, context, wakeLockTag);
        }

        public ForcedUpdatePackageDexOptimizer(PackageDexOptimizer from) {
            super(from);
        }

        @Override // com.android.server.pm.PackageDexOptimizer
        protected int adjustDexoptNeeded(int dexoptNeeded) {
            if (dexoptNeeded == 0) {
                return -3;
            }
            return dexoptNeeded;
        }

        @Override // com.android.server.pm.PackageDexOptimizer
        protected int adjustDexoptFlags(int flags) {
            return flags | 64;
        }
    }
}
