package com.android.server;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.AsyncTask;
import java.util.concurrent.TimeUnit;
/* loaded from: classes.dex */
public class PruneInstantAppsJobService extends JobService {
    private static final boolean DEBUG = false;
    private static final int JOB_ID = 765123;
    private static final long PRUNE_INSTANT_APPS_PERIOD_MILLIS = TimeUnit.DAYS.toMillis(1);

    public static void schedule(Context context) {
        JobInfo pruneJob = new JobInfo.Builder(JOB_ID, new ComponentName(context.getPackageName(), PruneInstantAppsJobService.class.getName())).setRequiresDeviceIdle(true).setPeriodic(PRUNE_INSTANT_APPS_PERIOD_MILLIS).build();
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(JobScheduler.class);
        jobScheduler.schedule(pruneJob);
    }

    @Override // android.app.job.JobService
    public boolean onStartJob(final JobParameters params) {
        AsyncTask.execute(new Runnable() { // from class: com.android.server.-$$Lambda$PruneInstantAppsJobService$i4sLSJdxcTXdgPAQZFbP66ZRprE
            @Override // java.lang.Runnable
            public final void run() {
                PruneInstantAppsJobService.lambda$onStartJob$0(PruneInstantAppsJobService.this, params);
            }
        });
        return true;
    }

    public static /* synthetic */ void lambda$onStartJob$0(PruneInstantAppsJobService pruneInstantAppsJobService, JobParameters params) {
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.pruneInstantApps();
        pruneInstantAppsJobService.jobFinished(params, false);
    }

    @Override // android.app.job.JobService
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
