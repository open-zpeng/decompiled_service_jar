package com.android.server.pm;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Environment;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.StatsLog;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.PinnerService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import java.io.File;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/* loaded from: classes.dex */
public class BackgroundDexOptService extends JobService {
    private static final long IDLE_OPTIMIZATION_PERIOD;
    private static final int JOB_IDLE_OPTIMIZE = 800;
    private static final int JOB_POST_BOOT_UPDATE = 801;
    private static final int LOW_THRESHOLD_MULTIPLIER_FOR_DOWNGRADE = 2;
    private static final int OPTIMIZE_ABORT_BY_JOB_SCHEDULER = 2;
    private static final int OPTIMIZE_ABORT_NO_SPACE_LEFT = 3;
    private static final int OPTIMIZE_CONTINUE = 1;
    private static final int OPTIMIZE_PROCESSED = 0;
    private static final long mDowngradeUnusedAppsThresholdInMillis;
    private static ComponentName sDexoptServiceName;
    static final ArraySet<String> sFailedPackageNamesPrimary;
    static final ArraySet<String> sFailedPackageNamesSecondary;
    private static final String TAG = "BackgroundDexOptService";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private final AtomicBoolean mAbortPostBootUpdate = new AtomicBoolean(false);
    private final AtomicBoolean mAbortIdleOptimization = new AtomicBoolean(false);
    private final AtomicBoolean mExitPostBootUpdate = new AtomicBoolean(false);
    private final File mDataDir = Environment.getDataDirectory();

    static {
        long millis;
        if (DEBUG) {
            millis = TimeUnit.MINUTES.toMillis(1L);
        } else {
            millis = TimeUnit.DAYS.toMillis(7L);
        }
        IDLE_OPTIMIZATION_PERIOD = millis;
        sDexoptServiceName = new ComponentName(PackageManagerService.PLATFORM_PACKAGE_NAME, BackgroundDexOptService.class.getName());
        sFailedPackageNamesPrimary = new ArraySet<>();
        sFailedPackageNamesSecondary = new ArraySet<>();
        mDowngradeUnusedAppsThresholdInMillis = getDowngradeUnusedAppsThresholdInMillis();
    }

    public static void schedule(Context context) {
        if (isBackgroundDexoptDisabled()) {
            return;
        }
        JobScheduler js = (JobScheduler) context.getSystemService("jobscheduler");
        js.schedule(new JobInfo.Builder(JOB_POST_BOOT_UPDATE, sDexoptServiceName).setMinimumLatency(TimeUnit.MINUTES.toMillis(1L)).setOverrideDeadline(TimeUnit.MINUTES.toMillis(1L)).build());
        js.schedule(new JobInfo.Builder(JOB_IDLE_OPTIMIZE, sDexoptServiceName).setRequiresDeviceIdle(true).setRequiresCharging(true).setPeriodic(IDLE_OPTIMIZATION_PERIOD).build());
    }

    public static void notifyPackageChanged(String packageName) {
        synchronized (sFailedPackageNamesPrimary) {
            sFailedPackageNamesPrimary.remove(packageName);
        }
        synchronized (sFailedPackageNamesSecondary) {
            sFailedPackageNamesSecondary.remove(packageName);
        }
    }

    private int getBatteryLevel() {
        IntentFilter filter = new IntentFilter("android.intent.action.BATTERY_CHANGED");
        Intent intent = registerReceiver(null, filter);
        int level = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", -1);
        boolean present = intent.getBooleanExtra("present", true);
        if (!present) {
            return 100;
        }
        if (level < 0 || scale <= 0) {
            return 0;
        }
        return (level * 100) / scale;
    }

    private long getLowStorageThreshold(Context context) {
        long lowThreshold = StorageManager.from(context).getStorageLowBytes(this.mDataDir);
        if (lowThreshold == 0) {
            Log.e(TAG, "Invalid low storage threshold");
        }
        return lowThreshold;
    }

    /* JADX WARN: Type inference failed for: r0v2, types: [com.android.server.pm.BackgroundDexOptService$1] */
    private boolean runPostBootUpdate(final JobParameters jobParams, final PackageManagerService pm, final ArraySet<String> pkgs) {
        if (this.mExitPostBootUpdate.get()) {
            return false;
        }
        new Thread("BackgroundDexOptService_PostBootUpdate") { // from class: com.android.server.pm.BackgroundDexOptService.1
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                BackgroundDexOptService.this.postBootUpdate(jobParams, pm, pkgs);
            }
        }.start();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postBootUpdate(JobParameters jobParams, PackageManagerService pm, ArraySet<String> pkgs) {
        int lowBatteryThreshold = getResources().getInteger(17694828);
        long lowThreshold = getLowStorageThreshold(this);
        this.mAbortPostBootUpdate.set(false);
        ArraySet<String> updatedPackages = new ArraySet<>();
        Iterator<String> it = pkgs.iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            String pkg = it.next();
            if (this.mAbortPostBootUpdate.get()) {
                return;
            }
            if (this.mExitPostBootUpdate.get() || getBatteryLevel() < lowBatteryThreshold) {
                break;
            }
            long usableSpace = this.mDataDir.getUsableSpace();
            if (usableSpace < lowThreshold) {
                Log.w(TAG, "Aborting background dex opt job due to low storage: " + usableSpace);
                break;
            }
            int result = pm.performDexOptWithStatus(new DexoptOptions(pkg, 1, 4));
            if (result == 1) {
                updatedPackages.add(pkg);
            }
        }
        notifyPinService(updatedPackages);
        jobFinished(jobParams, false);
    }

    /* JADX WARN: Type inference failed for: r6v0, types: [com.android.server.pm.BackgroundDexOptService$2] */
    private boolean runIdleOptimization(final JobParameters jobParams, final PackageManagerService pm, final ArraySet<String> pkgs) {
        new Thread("BackgroundDexOptService_IdleOptimization") { // from class: com.android.server.pm.BackgroundDexOptService.2
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                BackgroundDexOptService backgroundDexOptService = BackgroundDexOptService.this;
                int result = backgroundDexOptService.idleOptimization(pm, pkgs, backgroundDexOptService);
                if (result != 2) {
                    Log.w(BackgroundDexOptService.TAG, "Idle optimizations aborted because of space constraints.");
                    BackgroundDexOptService.this.jobFinished(jobParams, false);
                }
            }
        }.start();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int idleOptimization(PackageManagerService pm, ArraySet<String> pkgs, Context context) {
        Log.i(TAG, "Performing idle optimizations");
        this.mExitPostBootUpdate.set(true);
        this.mAbortIdleOptimization.set(false);
        long lowStorageThreshold = getLowStorageThreshold(context);
        int result = optimizePackages(pm, pkgs, lowStorageThreshold, true);
        if (result == 2) {
            return result;
        }
        if (supportSecondaryDex()) {
            int result2 = reconcileSecondaryDexFiles(pm.getDexManager());
            if (result2 == 2) {
                return result2;
            }
            return optimizePackages(pm, pkgs, lowStorageThreshold, false);
        }
        return result;
    }

    private long getDirectorySize(File f) {
        File[] listFiles;
        long size = 0;
        if (f.isDirectory()) {
            for (File file : f.listFiles()) {
                size += getDirectorySize(file);
            }
            return size;
        }
        long size2 = f.length();
        return size2;
    }

    private long getPackageSize(PackageManagerService pm, String pkg) {
        String[] strArr;
        PackageInfo info = pm.getPackageInfo(pkg, 0, 0);
        if (info != null && info.applicationInfo != null) {
            File path = Paths.get(info.applicationInfo.sourceDir, new String[0]).toFile();
            if (path.isFile()) {
                path = path.getParentFile();
            }
            long size = 0 + getDirectorySize(path);
            if (!ArrayUtils.isEmpty(info.applicationInfo.splitSourceDirs)) {
                long size2 = size;
                for (String splitSourceDir : info.applicationInfo.splitSourceDirs) {
                    File path2 = Paths.get(splitSourceDir, new String[0]).toFile();
                    if (path2.isFile()) {
                        path2 = path2.getParentFile();
                    }
                    size2 += getDirectorySize(path2);
                }
                return size2;
            }
            return size;
        }
        return 0L;
    }

    private int optimizePackages(PackageManagerService pm, ArraySet<String> pkgs, long lowStorageThreshold, boolean isForPrimaryDex) {
        boolean dex_opt_performed;
        ArraySet<String> updatedPackages = new ArraySet<>();
        Set<String> unusedPackages = pm.getUnusedPackages(mDowngradeUnusedAppsThresholdInMillis);
        Log.d(TAG, "Unsused Packages " + String.join(",", unusedPackages));
        long lowStorageThresholdForDowngrade = 2 * lowStorageThreshold;
        boolean shouldDowngrade = shouldDowngrade(lowStorageThresholdForDowngrade);
        Log.d(TAG, "Should Downgrade " + shouldDowngrade);
        Iterator<String> it = pkgs.iterator();
        while (it.hasNext()) {
            String pkg = it.next();
            int abort_code = abortIdleOptimizations(lowStorageThreshold);
            if (abort_code == 2) {
                return abort_code;
            }
            if (unusedPackages.contains(pkg) && shouldDowngrade) {
                dex_opt_performed = downgradePackage(pm, pkg, isForPrimaryDex);
            } else if (abort_code != 3) {
                dex_opt_performed = optimizePackage(pm, pkg, isForPrimaryDex);
            }
            if (dex_opt_performed) {
                updatedPackages.add(pkg);
            }
        }
        notifyPinService(updatedPackages);
        return 0;
    }

    private boolean downgradePackage(PackageManagerService pm, String pkg, boolean isForPrimaryDex) {
        Log.d(TAG, "Downgrading " + pkg);
        boolean dex_opt_performed = false;
        long package_size_before = getPackageSize(pm, pkg);
        if (isForPrimaryDex) {
            if (!pm.canHaveOatDir(pkg)) {
                pm.deleteOatArtifactsOfPackage(pkg);
            } else {
                dex_opt_performed = performDexOptPrimary(pm, pkg, 5, 548);
            }
        } else {
            dex_opt_performed = performDexOptSecondary(pm, pkg, 5, 548);
        }
        if (dex_opt_performed) {
            StatsLog.write(128, pkg, package_size_before, getPackageSize(pm, pkg), false);
        }
        return dex_opt_performed;
    }

    private boolean supportSecondaryDex() {
        return SystemProperties.getBoolean("dalvik.vm.dexopt.secondary", false);
    }

    private int reconcileSecondaryDexFiles(DexManager dm) {
        for (String p : dm.getAllPackagesWithSecondaryDexFiles()) {
            if (this.mAbortIdleOptimization.get()) {
                return 2;
            }
            dm.reconcileSecondaryDexFiles(p);
        }
        return 0;
    }

    private boolean optimizePackage(PackageManagerService pm, String pkg, boolean isForPrimaryDex) {
        if (isForPrimaryDex) {
            return performDexOptPrimary(pm, pkg, 3, UsbTerminalTypes.TERMINAL_IN_MIC_ARRAY);
        }
        return performDexOptSecondary(pm, pkg, 3, UsbTerminalTypes.TERMINAL_IN_MIC_ARRAY);
    }

    private boolean performDexOptPrimary(final PackageManagerService pm, final String pkg, final int reason, final int dexoptFlags) {
        int result = trackPerformDexOpt(pkg, false, new Supplier() { // from class: com.android.server.pm.-$$Lambda$BackgroundDexOptService$-KiE2NsUP--OYmoSDt9BwEQICZw
            @Override // java.util.function.Supplier
            public final Object get() {
                Integer valueOf;
                valueOf = Integer.valueOf(PackageManagerService.this.performDexOptWithStatus(new DexoptOptions(pkg, reason, dexoptFlags)));
                return valueOf;
            }
        });
        return result == 1;
    }

    private boolean performDexOptSecondary(final PackageManagerService pm, String pkg, int reason, int dexoptFlags) {
        final DexoptOptions dexoptOptions = new DexoptOptions(pkg, reason, dexoptFlags | 8);
        int result = trackPerformDexOpt(pkg, true, new Supplier() { // from class: com.android.server.pm.-$$Lambda$BackgroundDexOptService$TAsfDUuoxt92xKFoSCfpMUmY2Es
            @Override // java.util.function.Supplier
            public final Object get() {
                Integer valueOf;
                PackageManagerService packageManagerService = PackageManagerService.this;
                DexoptOptions dexoptOptions2 = dexoptOptions;
                valueOf = Integer.valueOf(pm.performDexOpt(dexoptOptions) ? 1 : -1);
                return valueOf;
            }
        });
        return result == 1;
    }

    private int trackPerformDexOpt(String pkg, boolean isForPrimaryDex, Supplier<Integer> performDexOptWrapper) {
        ArraySet<String> sFailedPackageNames = isForPrimaryDex ? sFailedPackageNamesPrimary : sFailedPackageNamesSecondary;
        synchronized (sFailedPackageNames) {
            if (sFailedPackageNames.contains(pkg)) {
                return 0;
            }
            sFailedPackageNames.add(pkg);
            int result = performDexOptWrapper.get().intValue();
            if (result != -1) {
                synchronized (sFailedPackageNames) {
                    sFailedPackageNames.remove(pkg);
                }
            }
            return result;
        }
    }

    private int abortIdleOptimizations(long lowStorageThreshold) {
        if (this.mAbortIdleOptimization.get()) {
            return 2;
        }
        long usableSpace = this.mDataDir.getUsableSpace();
        if (usableSpace < lowStorageThreshold) {
            Log.w(TAG, "Aborting background dex opt job due to low storage: " + usableSpace);
            return 3;
        }
        return 1;
    }

    private boolean shouldDowngrade(long lowStorageThresholdForDowngrade) {
        long usableSpace = this.mDataDir.getUsableSpace();
        if (usableSpace < lowStorageThresholdForDowngrade) {
            return true;
        }
        return false;
    }

    public static boolean runIdleOptimizationsNow(PackageManagerService pm, Context context, List<String> packageNames) {
        ArraySet<String> packagesToOptimize;
        BackgroundDexOptService bdos = new BackgroundDexOptService();
        if (packageNames == null) {
            packagesToOptimize = pm.getOptimizablePackages();
        } else {
            packagesToOptimize = new ArraySet<>(packageNames);
        }
        int result = bdos.idleOptimization(pm, packagesToOptimize, context);
        return result == 0;
    }

    @Override // android.app.job.JobService
    public boolean onStartJob(JobParameters params) {
        PackageManagerService pm = (PackageManagerService) ServiceManager.getService("package");
        if (pm.isStorageLow()) {
            return false;
        }
        ArraySet<String> pkgs = pm.getOptimizablePackages();
        if (pkgs.isEmpty()) {
            return false;
        }
        if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
            boolean result = runPostBootUpdate(params, pm, pkgs);
            return result;
        }
        boolean result2 = runIdleOptimization(params, pm, pkgs);
        return result2;
    }

    @Override // android.app.job.JobService
    public boolean onStopJob(JobParameters params) {
        if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
            this.mAbortPostBootUpdate.set(true);
            return false;
        }
        this.mAbortIdleOptimization.set(true);
        return true;
    }

    private void notifyPinService(ArraySet<String> updatedPackages) {
        PinnerService pinnerService = (PinnerService) LocalServices.getService(PinnerService.class);
        if (pinnerService != null) {
            Log.i(TAG, "Pinning optimized code " + updatedPackages);
            pinnerService.update(updatedPackages, false);
        }
    }

    private static long getDowngradeUnusedAppsThresholdInMillis() {
        String sysPropValue = SystemProperties.get("pm.dexopt.downgrade_after_inactive_days");
        if (sysPropValue == null || sysPropValue.isEmpty()) {
            Log.w(TAG, "SysProp pm.dexopt.downgrade_after_inactive_days not set");
            return JobStatus.NO_LATEST_RUNTIME;
        }
        return TimeUnit.DAYS.toMillis(Long.parseLong(sysPropValue));
    }

    private static boolean isBackgroundDexoptDisabled() {
        return SystemProperties.getBoolean("pm.dexopt.disable_bg_dexopt", false);
    }
}
