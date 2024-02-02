package com.android.server.pm;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.util.ArraySet;
import android.util.Log;
import com.android.server.LocalServices;
import com.android.server.PinnerService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
/* loaded from: classes.dex */
public class BackgroundDexOptService extends JobService {
    private static final boolean DEBUG = false;
    private static final int JOB_IDLE_OPTIMIZE = 800;
    private static final int JOB_POST_BOOT_UPDATE = 801;
    private static final int LOW_THRESHOLD_MULTIPLIER_FOR_DOWNGRADE = 2;
    private static final int OPTIMIZE_ABORT_BY_JOB_SCHEDULER = 2;
    private static final int OPTIMIZE_ABORT_NO_SPACE_LEFT = 3;
    private static final int OPTIMIZE_CONTINUE = 1;
    private static final int OPTIMIZE_PROCESSED = 0;
    private static final String TAG = "BackgroundDexOptService";
    private static final long IDLE_OPTIMIZATION_PERIOD = TimeUnit.DAYS.toMillis(7);
    private static ComponentName sDexoptServiceName = new ComponentName(PackageManagerService.PLATFORM_PACKAGE_NAME, BackgroundDexOptService.class.getName());
    static final ArraySet<String> sFailedPackageNamesPrimary = new ArraySet<>();
    static final ArraySet<String> sFailedPackageNamesSecondary = new ArraySet<>();
    private static final long mDowngradeUnusedAppsThresholdInMillis = getDowngradeUnusedAppsThresholdInMillis();
    private final AtomicBoolean mAbortPostBootUpdate = new AtomicBoolean(false);
    private final AtomicBoolean mAbortIdleOptimization = new AtomicBoolean(false);
    private final AtomicBoolean mExitPostBootUpdate = new AtomicBoolean(false);
    private final File mDataDir = Environment.getDataDirectory();

    public static void schedule(Context context) {
        if (isBackgroundDexoptDisabled()) {
            return;
        }
        JobScheduler js = (JobScheduler) context.getSystemService("jobscheduler");
        js.schedule(new JobInfo.Builder(JOB_POST_BOOT_UPDATE, sDexoptServiceName).setMinimumLatency(TimeUnit.MINUTES.toMillis(1L)).setOverrideDeadline(TimeUnit.MINUTES.toMillis(1L)).build());
        js.schedule(new JobInfo.Builder(JOB_IDLE_OPTIMIZE, sDexoptServiceName).setRequiresDeviceIdle(true).setRequiresCharging(true).setPeriodic(IDLE_OPTIMIZATION_PERIOD).build());
        if (PackageManagerService.DEBUG_DEXOPT) {
            Log.i(TAG, "Jobs scheduled");
        }
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
        return (100 * level) / scale;
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
        int lowBatteryThreshold = getResources().getInteger(17694805);
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
            if (PackageManagerService.DEBUG_DEXOPT) {
                Log.i(TAG, "Updating package " + pkg);
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
                int result = BackgroundDexOptService.this.idleOptimization(pm, pkgs, BackgroundDexOptService.this);
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
        int result = optimizePackages(pm, pkgs, lowStorageThreshold, true, sFailedPackageNamesPrimary);
        if (result == 2) {
            return result;
        }
        if (SystemProperties.getBoolean("dalvik.vm.dexopt.secondary", false)) {
            int result2 = reconcileSecondaryDexFiles(pm.getDexManager());
            if (result2 == 2) {
                return result2;
            }
            return optimizePackages(pm, pkgs, lowStorageThreshold, false, sFailedPackageNamesSecondary);
        }
        return result;
    }

    /* JADX WARN: Removed duplicated region for block: B:60:0x005a A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int optimizePackages(com.android.server.pm.PackageManagerService r22, android.util.ArraySet<java.lang.String> r23, long r24, boolean r26, android.util.ArraySet<java.lang.String> r27) {
        /*
            r21 = this;
            r1 = r21
            r2 = r22
            r3 = r24
            r5 = r27
            android.util.ArraySet r0 = new android.util.ArraySet
            r0.<init>()
            r6 = r0
            long r7 = com.android.server.pm.BackgroundDexOptService.mDowngradeUnusedAppsThresholdInMillis
            java.util.Set r7 = r2.getUnusedPackages(r7)
            r8 = 2
            long r8 = r8 * r3
            boolean r10 = r1.shouldDowngrade(r8)
            java.util.Iterator r0 = r23.iterator()
        L1f:
            boolean r11 = r0.hasNext()
            if (r11 == 0) goto Lae
            java.lang.Object r11 = r0.next()
            java.lang.String r11 = (java.lang.String) r11
            int r13 = r1.abortIdleOptimizations(r3)
            r14 = 2
            if (r13 != r14) goto L33
            return r13
        L33:
            monitor-enter(r27)
            boolean r14 = r5.contains(r11)     // Catch: java.lang.Throwable -> Lab
            if (r14 == 0) goto L3c
            monitor-exit(r27)     // Catch: java.lang.Throwable -> Lab
            goto L1f
        L3c:
            monitor-exit(r27)     // Catch: java.lang.Throwable -> Lab
            boolean r14 = r7.contains(r11)
            if (r14 == 0) goto L54
            if (r10 == 0) goto L54
            if (r26 == 0) goto L51
            boolean r14 = r2.canHaveOatDir(r11)
            if (r14 != 0) goto L51
            r2.deleteOatArtifactsOfPackage(r11)
            goto L1f
        L51:
            r14 = 5
            r15 = 1
            goto L59
        L54:
            r14 = 3
            if (r13 == r14) goto L1f
            r14 = 3
            r15 = 0
        L59:
            monitor-enter(r27)
            r5.add(r11)     // Catch: java.lang.Throwable -> La8
            monitor-exit(r27)     // Catch: java.lang.Throwable -> La8
            r16 = 5
            if (r15 == 0) goto L65
            r17 = 32
            goto L67
        L65:
            r17 = 0
        L67:
            r12 = r16 | r17
            r12 = r12 | 512(0x200, float:7.17E-43)
            if (r26 == 0) goto L89
            r19 = r0
            com.android.server.pm.dex.DexoptOptions r0 = new com.android.server.pm.dex.DexoptOptions
            r0.<init>(r11, r14, r12)
            int r0 = r2.performDexOptWithStatus(r0)
            r3 = -1
            r4 = 1
            if (r0 == r3) goto L7f
            r18 = r4
            goto L81
        L7f:
            r18 = 0
        L81:
            r3 = r18
            if (r0 != r4) goto L88
            r6.add(r11)
        L88:
            goto L96
        L89:
            r19 = r0
            com.android.server.pm.dex.DexoptOptions r0 = new com.android.server.pm.dex.DexoptOptions
            r3 = r12 | 8
            r0.<init>(r11, r14, r3)
            boolean r3 = r2.performDexOpt(r0)
        L96:
            if (r3 == 0) goto La1
            monitor-enter(r27)
            r5.remove(r11)     // Catch: java.lang.Throwable -> L9e
            monitor-exit(r27)     // Catch: java.lang.Throwable -> L9e
            goto La1
        L9e:
            r0 = move-exception
            monitor-exit(r27)     // Catch: java.lang.Throwable -> L9e
            throw r0
        La1:
            r0 = r19
            r3 = r24
            goto L1f
        La8:
            r0 = move-exception
            monitor-exit(r27)     // Catch: java.lang.Throwable -> La8
            throw r0
        Lab:
            r0 = move-exception
            monitor-exit(r27)     // Catch: java.lang.Throwable -> Lab
            throw r0
        Lae:
            r1.notifyPinService(r6)
            r0 = 0
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.BackgroundDexOptService.optimizePackages(com.android.server.pm.PackageManagerService, android.util.ArraySet, long, boolean, android.util.ArraySet):int");
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
        if (PackageManagerService.DEBUG_DEXOPT) {
            Log.i(TAG, "onStartJob");
        }
        PackageManagerService pm = (PackageManagerService) ServiceManager.getService("package");
        if (pm.isStorageLow()) {
            if (PackageManagerService.DEBUG_DEXOPT) {
                Log.i(TAG, "Low storage, skipping this run");
            }
            return false;
        }
        ArraySet<String> pkgs = pm.getOptimizablePackages();
        if (pkgs.isEmpty()) {
            if (PackageManagerService.DEBUG_DEXOPT) {
                Log.i(TAG, "No packages to optimize");
            }
            return false;
        } else if (params.getJobId() == JOB_POST_BOOT_UPDATE) {
            boolean result = runPostBootUpdate(params, pm, pkgs);
            return result;
        } else {
            boolean result2 = runIdleOptimization(params, pm, pkgs);
            return result2;
        }
    }

    @Override // android.app.job.JobService
    public boolean onStopJob(JobParameters params) {
        if (PackageManagerService.DEBUG_DEXOPT) {
            Log.i(TAG, "onStopJob");
        }
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
