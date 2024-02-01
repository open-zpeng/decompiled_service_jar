package com.android.server.job.controllers;

import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobStore;
import com.android.server.pm.DumpState;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import java.util.function.Consumer;
import java.util.function.Predicate;
/* loaded from: classes.dex */
public final class BackgroundJobsController extends StateController {
    private static final boolean DEBUG;
    static final int KNOWN_ACTIVE = 1;
    static final int KNOWN_INACTIVE = 2;
    private static final String TAG = "JobScheduler.Background";
    static final int UNKNOWN = 0;
    private final AppStateTracker mAppStateTracker;
    private final AppStateTracker.Listener mForceAppStandbyListener;

    static {
        DEBUG = JobSchedulerService.DEBUG || Log.isLoggable(TAG, 3);
    }

    public BackgroundJobsController(JobSchedulerService service) {
        super(service);
        this.mForceAppStandbyListener = new AppStateTracker.Listener() { // from class: com.android.server.job.controllers.BackgroundJobsController.1
            @Override // com.android.server.AppStateTracker.Listener
            public void updateAllJobs() {
                synchronized (BackgroundJobsController.this.mLock) {
                    BackgroundJobsController.this.updateAllJobRestrictionsLocked();
                }
            }

            @Override // com.android.server.AppStateTracker.Listener
            public void updateJobsForUid(int uid, boolean isActive) {
                synchronized (BackgroundJobsController.this.mLock) {
                    BackgroundJobsController.this.updateJobRestrictionsForUidLocked(uid, isActive);
                }
            }

            @Override // com.android.server.AppStateTracker.Listener
            public void updateJobsForUidPackage(int uid, String packageName, boolean isActive) {
                synchronized (BackgroundJobsController.this.mLock) {
                    BackgroundJobsController.this.updateJobRestrictionsForUidLocked(uid, isActive);
                }
            }
        };
        this.mAppStateTracker = (AppStateTracker) Preconditions.checkNotNull((AppStateTracker) LocalServices.getService(AppStateTracker.class));
        this.mAppStateTracker.addListener(this.mForceAppStandbyListener);
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        updateSingleJobRestrictionLocked(jobStatus, 0);
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob, boolean forUpdate) {
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(final IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        this.mAppStateTracker.dump(pw);
        pw.println();
        this.mService.getJobStore().forEachJob(predicate, new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$BackgroundJobsController$5YoufKSiImueGHv9obiMns19gXE
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                BackgroundJobsController.lambda$dumpControllerStateLocked$0(BackgroundJobsController.this, pw, (JobStatus) obj);
            }
        });
    }

    public static /* synthetic */ void lambda$dumpControllerStateLocked$0(BackgroundJobsController backgroundJobsController, IndentingPrintWriter pw, JobStatus jobStatus) {
        int uid = jobStatus.getSourceUid();
        String sourcePkg = jobStatus.getSourcePackageName();
        pw.print(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
        jobStatus.printUniqueId(pw);
        pw.print(" from ");
        UserHandle.formatUid(pw, uid);
        pw.print(backgroundJobsController.mAppStateTracker.isUidActive(uid) ? " active" : " idle");
        if (backgroundJobsController.mAppStateTracker.isUidPowerSaveWhitelisted(uid) || backgroundJobsController.mAppStateTracker.isUidTempPowerSaveWhitelisted(uid)) {
            pw.print(", whitelisted");
        }
        pw.print(": ");
        pw.print(sourcePkg);
        pw.print(" [RUN_ANY_IN_BACKGROUND ");
        pw.print(backgroundJobsController.mAppStateTracker.isRunAnyInBackgroundAppOpsAllowed(uid, sourcePkg) ? "allowed]" : "disallowed]");
        if ((jobStatus.satisfiedConstraints & DumpState.DUMP_CHANGES) != 0) {
            pw.println(" RUNNABLE");
        } else {
            pw.println(" WAITING");
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(final ProtoOutputStream proto, long fieldId, Predicate<JobStatus> predicate) {
        long token = proto.start(fieldId);
        long mToken = proto.start(1146756268033L);
        this.mAppStateTracker.dumpProto(proto, 1146756268033L);
        this.mService.getJobStore().forEachJob(predicate, new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$BackgroundJobsController$ypgNv91qX_67RP8z3Z9CsC0SRRs
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                BackgroundJobsController.lambda$dumpControllerStateLocked$1(BackgroundJobsController.this, proto, (JobStatus) obj);
            }
        });
        proto.end(mToken);
        proto.end(token);
    }

    public static /* synthetic */ void lambda$dumpControllerStateLocked$1(BackgroundJobsController backgroundJobsController, ProtoOutputStream proto, JobStatus jobStatus) {
        long jsToken = proto.start(2246267895810L);
        jobStatus.writeToShortProto(proto, 1146756268033L);
        int sourceUid = jobStatus.getSourceUid();
        proto.write(1120986464258L, sourceUid);
        String sourcePkg = jobStatus.getSourcePackageName();
        proto.write(1138166333443L, sourcePkg);
        proto.write(1133871366148L, backgroundJobsController.mAppStateTracker.isUidActive(sourceUid));
        proto.write(1133871366149L, backgroundJobsController.mAppStateTracker.isUidPowerSaveWhitelisted(sourceUid) || backgroundJobsController.mAppStateTracker.isUidTempPowerSaveWhitelisted(sourceUid));
        proto.write(1133871366150L, backgroundJobsController.mAppStateTracker.isRunAnyInBackgroundAppOpsAllowed(sourceUid, sourcePkg));
        proto.write(1133871366151L, (jobStatus.satisfiedConstraints & DumpState.DUMP_CHANGES) != 0);
        proto.end(jsToken);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateAllJobRestrictionsLocked() {
        updateJobRestrictionsLocked(-1, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateJobRestrictionsForUidLocked(int uid, boolean isActive) {
        updateJobRestrictionsLocked(uid, isActive ? 1 : 2);
    }

    private void updateJobRestrictionsLocked(int filterUid, int newActiveState) {
        UpdateJobFunctor updateTrackedJobs = new UpdateJobFunctor(newActiveState);
        long start = DEBUG ? SystemClock.elapsedRealtimeNanos() : 0L;
        JobStore store = this.mService.getJobStore();
        if (filterUid > 0) {
            store.forEachJobForSourceUid(filterUid, updateTrackedJobs);
        } else {
            store.forEachJob(updateTrackedJobs);
        }
        long time = DEBUG ? SystemClock.elapsedRealtimeNanos() - start : 0L;
        if (DEBUG) {
            Slog.d(TAG, String.format("Job status updated: %d/%d checked/total jobs, %d us", Integer.valueOf(updateTrackedJobs.mCheckedCount), Integer.valueOf(updateTrackedJobs.mTotalCount), Long.valueOf(time / 1000)));
        }
        if (updateTrackedJobs.mChanged) {
            this.mStateChangedListener.onControllerStateChanged();
        }
    }

    boolean updateSingleJobRestrictionLocked(JobStatus jobStatus, int activeState) {
        boolean isActive;
        int uid = jobStatus.getSourceUid();
        String packageName = jobStatus.getSourcePackageName();
        boolean canRun = !this.mAppStateTracker.areJobsRestricted(uid, packageName, (jobStatus.getInternalFlags() & 1) != 0);
        if (activeState == 0) {
            isActive = this.mAppStateTracker.isUidActive(uid);
        } else {
            isActive = activeState == 1;
        }
        boolean didChange = jobStatus.setBackgroundNotRestrictedConstraintSatisfied(canRun);
        return didChange | jobStatus.setUidActive(isActive);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class UpdateJobFunctor implements Consumer<JobStatus> {
        final int activeState;
        boolean mChanged = false;
        int mTotalCount = 0;
        int mCheckedCount = 0;

        public UpdateJobFunctor(int newActiveState) {
            this.activeState = newActiveState;
        }

        @Override // java.util.function.Consumer
        public void accept(JobStatus jobStatus) {
            this.mTotalCount++;
            this.mCheckedCount++;
            if (BackgroundJobsController.this.updateSingleJobRestrictionLocked(jobStatus, this.activeState)) {
                this.mChanged = true;
            }
        }
    }
}
