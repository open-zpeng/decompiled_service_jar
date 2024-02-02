package com.android.server.job;

import android.app.job.JobInfo;
import com.android.server.slice.SliceClientPermissions;
import java.util.List;
/* loaded from: classes.dex */
public interface JobSchedulerInternal {
    void addBackingUpUid(int i);

    long baseHeartbeatForApp(String str, int i, int i2);

    void cancelJobsForUid(int i, String str);

    void clearAllBackingUpUids();

    long currentHeartbeat();

    JobStorePersistStats getPersistStats();

    List<JobInfo> getSystemScheduledPendingJobs();

    long nextHeartbeatForBucket(int i);

    void noteJobStart(String str, int i);

    void removeBackingUpUid(int i);

    void reportAppUsage(String str, int i);

    /* loaded from: classes.dex */
    public static class JobStorePersistStats {
        public int countAllJobsLoaded;
        public int countAllJobsSaved;
        public int countSystemServerJobsLoaded;
        public int countSystemServerJobsSaved;
        public int countSystemSyncManagerJobsLoaded;
        public int countSystemSyncManagerJobsSaved;

        public JobStorePersistStats() {
            this.countAllJobsLoaded = -1;
            this.countSystemServerJobsLoaded = -1;
            this.countSystemSyncManagerJobsLoaded = -1;
            this.countAllJobsSaved = -1;
            this.countSystemServerJobsSaved = -1;
            this.countSystemSyncManagerJobsSaved = -1;
        }

        public JobStorePersistStats(JobStorePersistStats source) {
            this.countAllJobsLoaded = -1;
            this.countSystemServerJobsLoaded = -1;
            this.countSystemSyncManagerJobsLoaded = -1;
            this.countAllJobsSaved = -1;
            this.countSystemServerJobsSaved = -1;
            this.countSystemSyncManagerJobsSaved = -1;
            this.countAllJobsLoaded = source.countAllJobsLoaded;
            this.countSystemServerJobsLoaded = source.countSystemServerJobsLoaded;
            this.countSystemSyncManagerJobsLoaded = source.countSystemSyncManagerJobsLoaded;
            this.countAllJobsSaved = source.countAllJobsSaved;
            this.countSystemServerJobsSaved = source.countSystemServerJobsSaved;
            this.countSystemSyncManagerJobsSaved = source.countSystemSyncManagerJobsSaved;
        }

        public String toString() {
            return "FirstLoad: " + this.countAllJobsLoaded + SliceClientPermissions.SliceAuthority.DELIMITER + this.countSystemServerJobsLoaded + SliceClientPermissions.SliceAuthority.DELIMITER + this.countSystemSyncManagerJobsLoaded + " LastSave: " + this.countAllJobsSaved + SliceClientPermissions.SliceAuthority.DELIMITER + this.countSystemServerJobsSaved + SliceClientPermissions.SliceAuthority.DELIMITER + this.countSystemSyncManagerJobsSaved;
        }
    }
}
