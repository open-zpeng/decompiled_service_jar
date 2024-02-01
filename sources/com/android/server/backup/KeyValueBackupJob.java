package com.android.server.backup;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;
import com.android.server.pm.PackageManagerService;
import java.util.Random;
/* loaded from: classes.dex */
public class KeyValueBackupJob extends JobService {
    private static final int JOB_ID = 20537;
    private static final long MAX_DEFERRAL = 86400000;
    private static final String TAG = "KeyValueBackupJob";
    private static ComponentName sKeyValueJobService = new ComponentName(PackageManagerService.PLATFORM_PACKAGE_NAME, KeyValueBackupJob.class.getName());
    private static boolean sScheduled = false;
    private static long sNextScheduled = 0;

    public static void schedule(Context ctx, BackupManagerConstants constants) {
        schedule(ctx, 0L, constants);
    }

    public static void schedule(Context ctx, long delay, BackupManagerConstants constants) {
        long interval;
        long fuzz;
        int networkType;
        boolean needsCharging;
        synchronized (KeyValueBackupJob.class) {
            try {
                try {
                    if (sScheduled) {
                        return;
                    }
                    synchronized (constants) {
                        try {
                            interval = constants.getKeyValueBackupIntervalMilliseconds();
                            fuzz = constants.getKeyValueBackupFuzzMilliseconds();
                            networkType = constants.getKeyValueBackupRequiredNetworkType();
                            needsCharging = constants.getKeyValueBackupRequireCharging();
                        } catch (Throwable th) {
                            th = th;
                            while (true) {
                                try {
                                    try {
                                        break;
                                    } catch (Throwable th2) {
                                        th = th2;
                                        throw th;
                                    }
                                } catch (Throwable th3) {
                                    th = th3;
                                }
                            }
                            throw th;
                        }
                    }
                    long delay2 = delay <= 0 ? new Random().nextInt((int) fuzz) + interval : delay;
                    try {
                        Slog.v(TAG, "Scheduling k/v pass in " + ((delay2 / 1000) / 60) + " minutes");
                        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, sKeyValueJobService).setMinimumLatency(delay2).setRequiredNetworkType(networkType).setRequiresCharging(needsCharging).setOverrideDeadline(86400000L);
                        JobScheduler js = (JobScheduler) ctx.getSystemService("jobscheduler");
                        js.schedule(builder.build());
                        sNextScheduled = System.currentTimeMillis() + delay2;
                        sScheduled = true;
                    } catch (Throwable th4) {
                        th = th4;
                        throw th;
                    }
                } catch (Throwable th5) {
                    th = th5;
                }
            } catch (Throwable th6) {
                th = th6;
            }
        }
    }

    public static void cancel(Context ctx) {
        synchronized (KeyValueBackupJob.class) {
            JobScheduler js = (JobScheduler) ctx.getSystemService("jobscheduler");
            js.cancel(JOB_ID);
            sNextScheduled = 0L;
            sScheduled = false;
        }
    }

    public static long nextScheduled() {
        long j;
        synchronized (KeyValueBackupJob.class) {
            j = sNextScheduled;
        }
        return j;
    }

    @Override // android.app.job.JobService
    public boolean onStartJob(JobParameters params) {
        synchronized (KeyValueBackupJob.class) {
            sNextScheduled = 0L;
            sScheduled = false;
        }
        Trampoline service = BackupManagerService.getInstance();
        try {
            service.backupNow();
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override // android.app.job.JobService
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
