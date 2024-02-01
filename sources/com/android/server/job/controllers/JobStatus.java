package com.android.server.job.controllers;

import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobWorkItem;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.net.Network;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.format.Time;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.server.LocalServices;
import com.android.server.job.GrantedUriPermissions;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.ContentObserverController;
import com.android.server.slice.SliceClientPermissions;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Predicate;
/* loaded from: classes.dex */
public final class JobStatus {
    static final int CONSTRAINTS_OF_INTEREST = -1811939313;
    static final int CONSTRAINT_BACKGROUND_NOT_RESTRICTED = 4194304;
    static final int CONSTRAINT_BATTERY_NOT_LOW = 2;
    static final int CONSTRAINT_CHARGING = 1;
    static final int CONSTRAINT_CONNECTIVITY = 268435456;
    static final int CONSTRAINT_CONTENT_TRIGGER = 67108864;
    static final int CONSTRAINT_DEADLINE = 1073741824;
    static final int CONSTRAINT_DEVICE_NOT_DOZING = 33554432;
    static final int CONSTRAINT_IDLE = 4;
    static final int CONSTRAINT_STORAGE_NOT_LOW = 8;
    static final int CONSTRAINT_TIMING_DELAY = Integer.MIN_VALUE;
    static final boolean DEBUG = JobSchedulerService.DEBUG;
    static final boolean DEBUG_PREPARE = true;
    public static final long DEFAULT_TRIGGER_MAX_DELAY = 120000;
    public static final long DEFAULT_TRIGGER_UPDATE_DELAY = 10000;
    public static final int INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION = 1;
    public static final long MIN_TRIGGER_MAX_DELAY = 1000;
    public static final long MIN_TRIGGER_UPDATE_DELAY = 500;
    public static final long NO_EARLIEST_RUNTIME = 0;
    public static final long NO_LATEST_RUNTIME = Long.MAX_VALUE;
    public static final int OVERRIDE_FULL = 2;
    public static final int OVERRIDE_SOFT = 1;
    static final int SOFT_OVERRIDE_CONSTRAINTS = -2147483633;
    static final String TAG = "JobSchedulerService";
    public static final int TRACKING_BATTERY = 1;
    public static final int TRACKING_CONNECTIVITY = 2;
    public static final int TRACKING_CONTENT = 4;
    public static final int TRACKING_IDLE = 8;
    public static final int TRACKING_STORAGE = 16;
    public static final int TRACKING_TIME = 32;
    private final long baseHeartbeat;
    final String batteryName;
    final int callingUid;
    public ArraySet<String> changedAuthorities;
    public ArraySet<Uri> changedUris;
    ContentObserverController.JobInstance contentObserverJobInstance;
    public boolean dozeWhitelisted;
    private final long earliestRunTimeElapsedMillis;
    public long enqueueTime;
    public ArrayList<JobWorkItem> executingWork;
    final JobInfo job;
    public int lastEvaluatedPriority;
    private final long latestRunTimeElapsedMillis;
    private int mInternalFlags;
    private long mLastFailedRunTime;
    private long mLastSuccessfulRunTime;
    private Pair<Long, Long> mPersistedUtcTimes;
    public long madeActive;
    public long madePending;
    public Network network;
    public int nextPendingWorkId;
    private final int numFailures;
    public int overrideState;
    public ArrayList<JobWorkItem> pendingWork;
    private boolean prepared;
    final int requiredConstraints;
    int satisfiedConstraints;
    final String sourcePackageName;
    final String sourceTag;
    final int sourceUid;
    final int sourceUserId;
    private int standbyBucket;
    final String tag;
    final int targetSdkVersion;
    private long totalNetworkBytes;
    private int trackingControllers;
    public boolean uidActive;
    private Throwable unpreparedPoint;
    private GrantedUriPermissions uriPerms;
    private long whenStandbyDeferred;

    public int getServiceToken() {
        return this.callingUid;
    }

    private JobStatus(JobInfo job, int callingUid, int targetSdkVersion, String sourcePackageName, int sourceUserId, int standbyBucket, long heartbeat, String tag, int numFailures, long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis, long lastSuccessfulRunTime, long lastFailedRunTime, int internalFlags) {
        String flattenToShortString;
        this.unpreparedPoint = null;
        this.satisfiedConstraints = 0;
        this.nextPendingWorkId = 1;
        this.overrideState = 0;
        this.totalNetworkBytes = -1L;
        this.job = job;
        this.callingUid = callingUid;
        this.targetSdkVersion = targetSdkVersion;
        this.standbyBucket = standbyBucket;
        this.baseHeartbeat = heartbeat;
        int tempSourceUid = -1;
        if (sourceUserId != -1 && sourcePackageName != null) {
            try {
                tempSourceUid = AppGlobals.getPackageManager().getPackageUid(sourcePackageName, 0, sourceUserId);
            } catch (RemoteException e) {
            }
        }
        if (tempSourceUid == -1) {
            this.sourceUid = callingUid;
            this.sourceUserId = UserHandle.getUserId(callingUid);
            this.sourcePackageName = job.getService().getPackageName();
            this.sourceTag = null;
        } else {
            this.sourceUid = tempSourceUid;
            this.sourceUserId = sourceUserId;
            this.sourcePackageName = sourcePackageName;
            this.sourceTag = tag;
        }
        if (this.sourceTag != null) {
            flattenToShortString = this.sourceTag + ":" + job.getService().getPackageName();
        } else {
            flattenToShortString = job.getService().flattenToShortString();
        }
        this.batteryName = flattenToShortString;
        this.tag = "*job*/" + this.batteryName;
        this.earliestRunTimeElapsedMillis = earliestRunTimeElapsedMillis;
        this.latestRunTimeElapsedMillis = latestRunTimeElapsedMillis;
        this.numFailures = numFailures;
        int requiredConstraints = job.getConstraintFlags();
        requiredConstraints = job.getRequiredNetwork() != null ? requiredConstraints | CONSTRAINT_CONNECTIVITY : requiredConstraints;
        requiredConstraints = earliestRunTimeElapsedMillis != 0 ? requiredConstraints | Integer.MIN_VALUE : requiredConstraints;
        requiredConstraints = latestRunTimeElapsedMillis != NO_LATEST_RUNTIME ? requiredConstraints | CONSTRAINT_DEADLINE : requiredConstraints;
        this.requiredConstraints = job.getTriggerContentUris() != null ? requiredConstraints | CONSTRAINT_CONTENT_TRIGGER : requiredConstraints;
        this.mLastSuccessfulRunTime = lastSuccessfulRunTime;
        this.mLastFailedRunTime = lastFailedRunTime;
        this.mInternalFlags = internalFlags;
        updateEstimatedNetworkBytesLocked();
        if (job.getRequiredNetwork() != null) {
            job.getRequiredNetwork().networkCapabilities.setSingleUid(this.sourceUid);
        }
    }

    public JobStatus(JobStatus jobStatus) {
        this(jobStatus.getJob(), jobStatus.getUid(), jobStatus.targetSdkVersion, jobStatus.getSourcePackageName(), jobStatus.getSourceUserId(), jobStatus.getStandbyBucket(), jobStatus.getBaseHeartbeat(), jobStatus.getSourceTag(), jobStatus.getNumFailures(), jobStatus.getEarliestRunTime(), jobStatus.getLatestRunTimeElapsed(), jobStatus.getLastSuccessfulRunTime(), jobStatus.getLastFailedRunTime(), jobStatus.getInternalFlags());
        this.mPersistedUtcTimes = jobStatus.mPersistedUtcTimes;
        if (jobStatus.mPersistedUtcTimes != null && DEBUG) {
            Slog.i(TAG, "Cloning job with persisted run times", new RuntimeException("here"));
        }
    }

    public JobStatus(JobInfo job, int callingUid, String sourcePkgName, int sourceUserId, int standbyBucket, long baseHeartbeat, String sourceTag, long earliestRunTimeElapsedMillis, long latestRunTimeElapsedMillis, long lastSuccessfulRunTime, long lastFailedRunTime, Pair<Long, Long> persistedExecutionTimesUTC, int innerFlags) {
        this(job, callingUid, resolveTargetSdkVersion(job), sourcePkgName, sourceUserId, standbyBucket, baseHeartbeat, sourceTag, 0, earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis, lastSuccessfulRunTime, lastFailedRunTime, innerFlags);
        this.mPersistedUtcTimes = persistedExecutionTimesUTC;
        if (persistedExecutionTimesUTC != null && DEBUG) {
            Slog.i(TAG, "+ restored job with RTC times because of bad boot clock");
        }
    }

    public JobStatus(JobStatus rescheduling, long newBaseHeartbeat, long newEarliestRuntimeElapsedMillis, long newLatestRuntimeElapsedMillis, int backoffAttempt, long lastSuccessfulRunTime, long lastFailedRunTime) {
        this(rescheduling.job, rescheduling.getUid(), resolveTargetSdkVersion(rescheduling.job), rescheduling.getSourcePackageName(), rescheduling.getSourceUserId(), rescheduling.getStandbyBucket(), newBaseHeartbeat, rescheduling.getSourceTag(), backoffAttempt, newEarliestRuntimeElapsedMillis, newLatestRuntimeElapsedMillis, lastSuccessfulRunTime, lastFailedRunTime, rescheduling.getInternalFlags());
    }

    public static JobStatus createFromJobInfo(JobInfo job, int callingUid, String sourcePkg, int sourceUserId, String tag) {
        long earliestRunTimeElapsedMillis;
        long latestRunTimeElapsedMillis;
        long elapsedNow = JobSchedulerService.sElapsedRealtimeClock.millis();
        if (job.isPeriodic()) {
            long latestRunTimeElapsedMillis2 = job.getIntervalMillis() + elapsedNow;
            long earliestRunTimeElapsedMillis2 = latestRunTimeElapsedMillis2 - job.getFlexMillis();
            latestRunTimeElapsedMillis = latestRunTimeElapsedMillis2;
            earliestRunTimeElapsedMillis = earliestRunTimeElapsedMillis2;
        } else {
            long earliestRunTimeElapsedMillis3 = job.hasEarlyConstraint() ? job.getMinLatencyMillis() + elapsedNow : 0L;
            earliestRunTimeElapsedMillis = earliestRunTimeElapsedMillis3;
            latestRunTimeElapsedMillis = job.hasLateConstraint() ? job.getMaxExecutionDelayMillis() + elapsedNow : NO_LATEST_RUNTIME;
        }
        String jobPackage = sourcePkg != null ? sourcePkg : job.getService().getPackageName();
        int standbyBucket = JobSchedulerService.standbyBucketForPackage(jobPackage, sourceUserId, elapsedNow);
        JobSchedulerInternal js = (JobSchedulerInternal) LocalServices.getService(JobSchedulerInternal.class);
        long currentHeartbeat = js != null ? js.baseHeartbeatForApp(jobPackage, sourceUserId, standbyBucket) : 0L;
        return new JobStatus(job, callingUid, resolveTargetSdkVersion(job), sourcePkg, sourceUserId, standbyBucket, currentHeartbeat, tag, 0, earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis, 0L, 0L, 0);
    }

    public void enqueueWorkLocked(IActivityManager am, JobWorkItem work) {
        if (this.pendingWork == null) {
            this.pendingWork = new ArrayList<>();
        }
        work.setWorkId(this.nextPendingWorkId);
        this.nextPendingWorkId++;
        if (work.getIntent() != null && GrantedUriPermissions.checkGrantFlags(work.getIntent().getFlags())) {
            work.setGrants(GrantedUriPermissions.createFromIntent(am, work.getIntent(), this.sourceUid, this.sourcePackageName, this.sourceUserId, toShortString()));
        }
        this.pendingWork.add(work);
        updateEstimatedNetworkBytesLocked();
    }

    public JobWorkItem dequeueWorkLocked() {
        if (this.pendingWork != null && this.pendingWork.size() > 0) {
            JobWorkItem work = this.pendingWork.remove(0);
            if (work != null) {
                if (this.executingWork == null) {
                    this.executingWork = new ArrayList<>();
                }
                this.executingWork.add(work);
                work.bumpDeliveryCount();
            }
            updateEstimatedNetworkBytesLocked();
            return work;
        }
        return null;
    }

    public boolean hasWorkLocked() {
        return (this.pendingWork != null && this.pendingWork.size() > 0) || hasExecutingWorkLocked();
    }

    public boolean hasExecutingWorkLocked() {
        return this.executingWork != null && this.executingWork.size() > 0;
    }

    private static void ungrantWorkItem(IActivityManager am, JobWorkItem work) {
        if (work.getGrants() != null) {
            ((GrantedUriPermissions) work.getGrants()).revoke(am);
        }
    }

    public boolean completeWorkLocked(IActivityManager am, int workId) {
        if (this.executingWork != null) {
            int N = this.executingWork.size();
            for (int i = 0; i < N; i++) {
                JobWorkItem work = this.executingWork.get(i);
                if (work.getWorkId() == workId) {
                    this.executingWork.remove(i);
                    ungrantWorkItem(am, work);
                    return true;
                }
            }
        }
        return false;
    }

    private static void ungrantWorkList(IActivityManager am, ArrayList<JobWorkItem> list) {
        if (list != null) {
            int N = list.size();
            for (int i = 0; i < N; i++) {
                ungrantWorkItem(am, list.get(i));
            }
        }
    }

    public void stopTrackingJobLocked(IActivityManager am, JobStatus incomingJob) {
        if (incomingJob != null) {
            if (this.executingWork != null && this.executingWork.size() > 0) {
                incomingJob.pendingWork = this.executingWork;
            }
            if (incomingJob.pendingWork == null) {
                incomingJob.pendingWork = this.pendingWork;
            } else if (this.pendingWork != null && this.pendingWork.size() > 0) {
                incomingJob.pendingWork.addAll(this.pendingWork);
            }
            this.pendingWork = null;
            this.executingWork = null;
            incomingJob.nextPendingWorkId = this.nextPendingWorkId;
            incomingJob.updateEstimatedNetworkBytesLocked();
        } else {
            ungrantWorkList(am, this.pendingWork);
            this.pendingWork = null;
            ungrantWorkList(am, this.executingWork);
            this.executingWork = null;
        }
        updateEstimatedNetworkBytesLocked();
    }

    public void prepareLocked(IActivityManager am) {
        if (this.prepared) {
            Slog.wtf(TAG, "Already prepared: " + this);
            return;
        }
        this.prepared = true;
        this.unpreparedPoint = null;
        ClipData clip = this.job.getClipData();
        if (clip != null) {
            this.uriPerms = GrantedUriPermissions.createFromClip(am, clip, this.sourceUid, this.sourcePackageName, this.sourceUserId, this.job.getClipGrantFlags(), toShortString());
        }
    }

    public void unprepareLocked(IActivityManager am) {
        if (!this.prepared) {
            Slog.wtf(TAG, "Hasn't been prepared: " + this);
            if (this.unpreparedPoint != null) {
                Slog.e(TAG, "Was already unprepared at ", this.unpreparedPoint);
                return;
            }
            return;
        }
        this.prepared = false;
        this.unpreparedPoint = new Throwable().fillInStackTrace();
        if (this.uriPerms != null) {
            this.uriPerms.revoke(am);
            this.uriPerms = null;
        }
    }

    public boolean isPreparedLocked() {
        return this.prepared;
    }

    public JobInfo getJob() {
        return this.job;
    }

    public int getJobId() {
        return this.job.getId();
    }

    public int getTargetSdkVersion() {
        return this.targetSdkVersion;
    }

    public void printUniqueId(PrintWriter pw) {
        UserHandle.formatUid(pw, this.callingUid);
        pw.print(SliceClientPermissions.SliceAuthority.DELIMITER);
        pw.print(this.job.getId());
    }

    public int getNumFailures() {
        return this.numFailures;
    }

    public ComponentName getServiceComponent() {
        return this.job.getService();
    }

    public String getSourcePackageName() {
        return this.sourcePackageName;
    }

    public int getSourceUid() {
        return this.sourceUid;
    }

    public int getSourceUserId() {
        return this.sourceUserId;
    }

    public int getUserId() {
        return UserHandle.getUserId(this.callingUid);
    }

    public int getStandbyBucket() {
        return this.standbyBucket;
    }

    public long getBaseHeartbeat() {
        return this.baseHeartbeat;
    }

    public void setStandbyBucket(int newBucket) {
        this.standbyBucket = newBucket;
    }

    public long getWhenStandbyDeferred() {
        return this.whenStandbyDeferred;
    }

    public void setWhenStandbyDeferred(long now) {
        this.whenStandbyDeferred = now;
    }

    public String getSourceTag() {
        return this.sourceTag;
    }

    public int getUid() {
        return this.callingUid;
    }

    public String getBatteryName() {
        return this.batteryName;
    }

    public String getTag() {
        return this.tag;
    }

    public int getPriority() {
        return this.job.getPriority();
    }

    public int getFlags() {
        return this.job.getFlags();
    }

    public int getInternalFlags() {
        return this.mInternalFlags;
    }

    public void addInternalFlags(int flags) {
        this.mInternalFlags |= flags;
    }

    public void maybeAddForegroundExemption(Predicate<Integer> uidForegroundChecker) {
        if (!this.job.hasEarlyConstraint() && !this.job.hasLateConstraint() && (this.mInternalFlags & 1) == 0 && uidForegroundChecker.test(Integer.valueOf(getSourceUid()))) {
            addInternalFlags(1);
        }
    }

    private void updateEstimatedNetworkBytesLocked() {
        this.totalNetworkBytes = computeEstimatedNetworkBytesLocked();
    }

    private long computeEstimatedNetworkBytesLocked() {
        long networkBytes = this.job.getEstimatedNetworkBytes();
        if (networkBytes == -1) {
            return -1L;
        }
        long totalNetworkBytes = 0 + networkBytes;
        if (this.pendingWork != null) {
            for (int i = 0; i < this.pendingWork.size(); i++) {
                long networkBytes2 = this.pendingWork.get(i).getEstimatedNetworkBytes();
                if (networkBytes2 == -1) {
                    return -1L;
                }
                totalNetworkBytes += networkBytes2;
            }
        }
        return totalNetworkBytes;
    }

    public long getEstimatedNetworkBytes() {
        return this.totalNetworkBytes;
    }

    public boolean hasConnectivityConstraint() {
        return (this.requiredConstraints & CONSTRAINT_CONNECTIVITY) != 0;
    }

    public boolean hasChargingConstraint() {
        return (this.requiredConstraints & 1) != 0;
    }

    public boolean hasBatteryNotLowConstraint() {
        return (this.requiredConstraints & 2) != 0;
    }

    public boolean hasPowerConstraint() {
        return (this.requiredConstraints & 3) != 0;
    }

    public boolean hasStorageNotLowConstraint() {
        return (this.requiredConstraints & 8) != 0;
    }

    public boolean hasTimingDelayConstraint() {
        return (this.requiredConstraints & Integer.MIN_VALUE) != 0;
    }

    public boolean hasDeadlineConstraint() {
        return (this.requiredConstraints & CONSTRAINT_DEADLINE) != 0;
    }

    public boolean hasIdleConstraint() {
        return (this.requiredConstraints & 4) != 0;
    }

    public boolean hasContentTriggerConstraint() {
        return (this.requiredConstraints & CONSTRAINT_CONTENT_TRIGGER) != 0;
    }

    public long getTriggerContentUpdateDelay() {
        long time = this.job.getTriggerContentUpdateDelay();
        if (time < 0) {
            return DEFAULT_TRIGGER_UPDATE_DELAY;
        }
        return Math.max(time, 500L);
    }

    public long getTriggerContentMaxDelay() {
        long time = this.job.getTriggerContentMaxDelay();
        if (time < 0) {
            return DEFAULT_TRIGGER_MAX_DELAY;
        }
        return Math.max(time, 1000L);
    }

    public boolean isPersisted() {
        return this.job.isPersisted();
    }

    public long getEarliestRunTime() {
        return this.earliestRunTimeElapsedMillis;
    }

    public long getLatestRunTimeElapsed() {
        return this.latestRunTimeElapsedMillis;
    }

    public float getFractionRunTime() {
        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        if (this.earliestRunTimeElapsedMillis == 0 && this.latestRunTimeElapsedMillis == NO_LATEST_RUNTIME) {
            return 1.0f;
        }
        if (this.earliestRunTimeElapsedMillis == 0) {
            return now >= this.latestRunTimeElapsedMillis ? 1.0f : 0.0f;
        } else if (this.latestRunTimeElapsedMillis == NO_LATEST_RUNTIME) {
            return now >= this.earliestRunTimeElapsedMillis ? 1.0f : 0.0f;
        } else if (now <= this.earliestRunTimeElapsedMillis) {
            return 0.0f;
        } else {
            if (now >= this.latestRunTimeElapsedMillis) {
                return 1.0f;
            }
            return ((float) (now - this.earliestRunTimeElapsedMillis)) / ((float) (this.latestRunTimeElapsedMillis - this.earliestRunTimeElapsedMillis));
        }
    }

    public Pair<Long, Long> getPersistedUtcTimes() {
        return this.mPersistedUtcTimes;
    }

    public void clearPersistedUtcTimes() {
        this.mPersistedUtcTimes = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setChargingConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(1, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setBatteryNotLowConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(2, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setStorageNotLowConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(8, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setTimingDelayConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(Integer.MIN_VALUE, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setDeadlineConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_DEADLINE, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setIdleConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(4, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setConnectivityConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONNECTIVITY, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setContentTriggerConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(CONSTRAINT_CONTENT_TRIGGER, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setDeviceNotDozingConstraintSatisfied(boolean state, boolean whitelisted) {
        this.dozeWhitelisted = whitelisted;
        return setConstraintSatisfied(CONSTRAINT_DEVICE_NOT_DOZING, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setBackgroundNotRestrictedConstraintSatisfied(boolean state) {
        return setConstraintSatisfied(4194304, state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setUidActive(boolean newActiveState) {
        if (newActiveState != this.uidActive) {
            this.uidActive = newActiveState;
            return true;
        }
        return false;
    }

    boolean setConstraintSatisfied(int constraint, boolean state) {
        boolean old = (this.satisfiedConstraints & constraint) != 0;
        if (old == state) {
            return false;
        }
        this.satisfiedConstraints = (state ? constraint : 0) | (this.satisfiedConstraints & (~constraint));
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isConstraintSatisfied(int constraint) {
        return (this.satisfiedConstraints & constraint) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean clearTrackingController(int which) {
        if ((this.trackingControllers & which) != 0) {
            this.trackingControllers &= ~which;
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTrackingController(int which) {
        this.trackingControllers |= which;
    }

    public long getLastSuccessfulRunTime() {
        return this.mLastSuccessfulRunTime;
    }

    public long getLastFailedRunTime() {
        return this.mLastFailedRunTime;
    }

    public boolean isReady() {
        boolean deadlineSatisfied = (this.job.isPeriodic() || !hasDeadlineConstraint() || (this.satisfiedConstraints & CONSTRAINT_DEADLINE) == 0) ? false : true;
        boolean notDozing = ((this.satisfiedConstraints & CONSTRAINT_DEVICE_NOT_DOZING) == 0 && (this.job.getFlags() & 1) == 0) ? false : true;
        boolean notRestrictedInBg = (this.satisfiedConstraints & 4194304) != 0;
        return (isConstraintsSatisfied() || deadlineSatisfied) && notDozing && notRestrictedInBg;
    }

    public boolean isConstraintsSatisfied() {
        if (this.overrideState == 2) {
            return true;
        }
        int req = this.requiredConstraints & CONSTRAINTS_OF_INTEREST;
        int sat = CONSTRAINTS_OF_INTEREST & this.satisfiedConstraints;
        if (this.overrideState == 1) {
            sat |= this.requiredConstraints & SOFT_OVERRIDE_CONSTRAINTS;
        }
        return (sat & req) == req;
    }

    public boolean matches(int uid, int jobId) {
        return this.job.getId() == jobId && this.callingUid == uid;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("JobStatus{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        UserHandle.formatUid(sb, this.callingUid);
        sb.append(SliceClientPermissions.SliceAuthority.DELIMITER);
        sb.append(this.job.getId());
        sb.append(' ');
        sb.append(this.batteryName);
        sb.append(" u=");
        sb.append(getUserId());
        sb.append(" s=");
        sb.append(getSourceUid());
        if (this.earliestRunTimeElapsedMillis != 0 || this.latestRunTimeElapsedMillis != NO_LATEST_RUNTIME) {
            long now = JobSchedulerService.sElapsedRealtimeClock.millis();
            sb.append(" TIME=");
            formatRunTime(sb, this.earliestRunTimeElapsedMillis, 0L, now);
            sb.append(":");
            formatRunTime(sb, this.latestRunTimeElapsedMillis, NO_LATEST_RUNTIME, now);
        }
        if (this.job.getRequiredNetwork() != null) {
            sb.append(" NET");
        }
        if (this.job.isRequireCharging()) {
            sb.append(" CHARGING");
        }
        if (this.job.isRequireBatteryNotLow()) {
            sb.append(" BATNOTLOW");
        }
        if (this.job.isRequireStorageNotLow()) {
            sb.append(" STORENOTLOW");
        }
        if (this.job.isRequireDeviceIdle()) {
            sb.append(" IDLE");
        }
        if (this.job.isPeriodic()) {
            sb.append(" PERIODIC");
        }
        if (this.job.isPersisted()) {
            sb.append(" PERSISTED");
        }
        if ((this.satisfiedConstraints & CONSTRAINT_DEVICE_NOT_DOZING) == 0) {
            sb.append(" WAIT:DEV_NOT_DOZING");
        }
        if (this.job.getTriggerContentUris() != null) {
            sb.append(" URIS=");
            sb.append(Arrays.toString(this.job.getTriggerContentUris()));
        }
        if (this.numFailures != 0) {
            sb.append(" failures=");
            sb.append(this.numFailures);
        }
        if (isReady()) {
            sb.append(" READY");
        }
        sb.append("}");
        return sb.toString();
    }

    private void formatRunTime(PrintWriter pw, long runtime, long defaultValue, long now) {
        if (runtime == defaultValue) {
            pw.print("none");
        } else {
            TimeUtils.formatDuration(runtime - now, pw);
        }
    }

    private void formatRunTime(StringBuilder sb, long runtime, long defaultValue, long now) {
        if (runtime == defaultValue) {
            sb.append("none");
        } else {
            TimeUtils.formatDuration(runtime - now, sb);
        }
    }

    public String toShortString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" #");
        UserHandle.formatUid(sb, this.callingUid);
        sb.append(SliceClientPermissions.SliceAuthority.DELIMITER);
        sb.append(this.job.getId());
        sb.append(' ');
        sb.append(this.batteryName);
        return sb.toString();
    }

    public String toShortStringExceptUniqueId() {
        return Integer.toHexString(System.identityHashCode(this)) + ' ' + this.batteryName;
    }

    public void writeToShortProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, this.callingUid);
        proto.write(1120986464258L, this.job.getId());
        proto.write(1138166333443L, this.batteryName);
        proto.end(token);
    }

    void dumpConstraints(PrintWriter pw, int constraints) {
        if ((constraints & 1) != 0) {
            pw.print(" CHARGING");
        }
        if ((constraints & 2) != 0) {
            pw.print(" BATTERY_NOT_LOW");
        }
        if ((constraints & 8) != 0) {
            pw.print(" STORAGE_NOT_LOW");
        }
        if ((Integer.MIN_VALUE & constraints) != 0) {
            pw.print(" TIMING_DELAY");
        }
        if ((CONSTRAINT_DEADLINE & constraints) != 0) {
            pw.print(" DEADLINE");
        }
        if ((constraints & 4) != 0) {
            pw.print(" IDLE");
        }
        if ((CONSTRAINT_CONNECTIVITY & constraints) != 0) {
            pw.print(" CONNECTIVITY");
        }
        if ((CONSTRAINT_CONTENT_TRIGGER & constraints) != 0) {
            pw.print(" CONTENT_TRIGGER");
        }
        if ((CONSTRAINT_DEVICE_NOT_DOZING & constraints) != 0) {
            pw.print(" DEVICE_NOT_DOZING");
        }
        if ((4194304 & constraints) != 0) {
            pw.print(" BACKGROUND_NOT_RESTRICTED");
        }
        if (constraints != 0) {
            pw.print(" [0x");
            pw.print(Integer.toHexString(constraints));
            pw.print("]");
        }
    }

    void dumpConstraints(ProtoOutputStream proto, long fieldId, int constraints) {
        if ((constraints & 1) != 0) {
            proto.write(fieldId, 1);
        }
        if ((constraints & 2) != 0) {
            proto.write(fieldId, 2);
        }
        if ((constraints & 8) != 0) {
            proto.write(fieldId, 3);
        }
        if ((Integer.MIN_VALUE & constraints) != 0) {
            proto.write(fieldId, 4);
        }
        if ((CONSTRAINT_DEADLINE & constraints) != 0) {
            proto.write(fieldId, 5);
        }
        if ((constraints & 4) != 0) {
            proto.write(fieldId, 6);
        }
        if ((CONSTRAINT_CONNECTIVITY & constraints) != 0) {
            proto.write(fieldId, 7);
        }
        if ((CONSTRAINT_CONTENT_TRIGGER & constraints) != 0) {
            proto.write(fieldId, 8);
        }
        if ((CONSTRAINT_DEVICE_NOT_DOZING & constraints) != 0) {
            proto.write(fieldId, 9);
        }
    }

    private void dumpJobWorkItem(PrintWriter pw, String prefix, JobWorkItem work, int index) {
        pw.print(prefix);
        pw.print("  #");
        pw.print(index);
        pw.print(": #");
        pw.print(work.getWorkId());
        pw.print(" ");
        pw.print(work.getDeliveryCount());
        pw.print("x ");
        pw.println(work.getIntent());
        if (work.getGrants() != null) {
            pw.print(prefix);
            pw.println("  URI grants:");
            ((GrantedUriPermissions) work.getGrants()).dump(pw, prefix + "    ");
        }
    }

    private void dumpJobWorkItem(ProtoOutputStream proto, long fieldId, JobWorkItem work) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, work.getWorkId());
        proto.write(1120986464258L, work.getDeliveryCount());
        if (work.getIntent() != null) {
            work.getIntent().writeToProto(proto, 1146756268035L);
        }
        Object grants = work.getGrants();
        if (grants != null) {
            ((GrantedUriPermissions) grants).dump(proto, 1146756268036L);
        }
        proto.end(token);
    }

    private String bucketName(int bucket) {
        switch (bucket) {
            case 0:
                return "ACTIVE";
            case 1:
                return "WORKING_SET";
            case 2:
                return "FREQUENT";
            case 3:
                return "RARE";
            case 4:
                return "NEVER";
            default:
                return "Unknown: " + bucket;
        }
    }

    private static int resolveTargetSdkVersion(JobInfo job) {
        return ((PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class)).getPackageTargetSdkVersion(job.getService().getPackageName());
    }

    public void dump(PrintWriter pw, String prefix, boolean full, long elapsedRealtimeMillis) {
        pw.print(prefix);
        UserHandle.formatUid(pw, this.callingUid);
        pw.print(" tag=");
        pw.println(this.tag);
        pw.print(prefix);
        pw.print("Source: uid=");
        UserHandle.formatUid(pw, getSourceUid());
        pw.print(" user=");
        pw.print(getSourceUserId());
        pw.print(" pkg=");
        pw.println(getSourcePackageName());
        int i = 0;
        if (full) {
            pw.print(prefix);
            pw.println("JobInfo:");
            pw.print(prefix);
            pw.print("  Service: ");
            pw.println(this.job.getService().flattenToShortString());
            if (this.job.isPeriodic()) {
                pw.print(prefix);
                pw.print("  PERIODIC: interval=");
                TimeUtils.formatDuration(this.job.getIntervalMillis(), pw);
                pw.print(" flex=");
                TimeUtils.formatDuration(this.job.getFlexMillis(), pw);
                pw.println();
            }
            if (this.job.isPersisted()) {
                pw.print(prefix);
                pw.println("  PERSISTED");
            }
            if (this.job.getPriority() != 0) {
                pw.print(prefix);
                pw.print("  Priority: ");
                pw.println(this.job.getPriority());
            }
            if (this.job.getFlags() != 0) {
                pw.print(prefix);
                pw.print("  Flags: ");
                pw.println(Integer.toHexString(this.job.getFlags()));
            }
            if (getInternalFlags() != 0) {
                pw.print(prefix);
                pw.print("  Internal flags: ");
                pw.print(Integer.toHexString(getInternalFlags()));
                if ((getInternalFlags() & 1) != 0) {
                    pw.print(" HAS_FOREGROUND_EXEMPTION");
                }
                pw.println();
            }
            pw.print(prefix);
            pw.print("  Requires: charging=");
            pw.print(this.job.isRequireCharging());
            pw.print(" batteryNotLow=");
            pw.print(this.job.isRequireBatteryNotLow());
            pw.print(" deviceIdle=");
            pw.println(this.job.isRequireDeviceIdle());
            if (this.job.getTriggerContentUris() != null) {
                pw.print(prefix);
                pw.println("  Trigger content URIs:");
                for (int i2 = 0; i2 < this.job.getTriggerContentUris().length; i2++) {
                    JobInfo.TriggerContentUri trig = this.job.getTriggerContentUris()[i2];
                    pw.print(prefix);
                    pw.print("    ");
                    pw.print(Integer.toHexString(trig.getFlags()));
                    pw.print(' ');
                    pw.println(trig.getUri());
                }
                if (this.job.getTriggerContentUpdateDelay() >= 0) {
                    pw.print(prefix);
                    pw.print("  Trigger update delay: ");
                    TimeUtils.formatDuration(this.job.getTriggerContentUpdateDelay(), pw);
                    pw.println();
                }
                if (this.job.getTriggerContentMaxDelay() >= 0) {
                    pw.print(prefix);
                    pw.print("  Trigger max delay: ");
                    TimeUtils.formatDuration(this.job.getTriggerContentMaxDelay(), pw);
                    pw.println();
                }
            }
            if (this.job.getExtras() != null && !this.job.getExtras().maybeIsEmpty()) {
                pw.print(prefix);
                pw.print("  Extras: ");
                pw.println(this.job.getExtras().toShortString());
            }
            if (this.job.getTransientExtras() != null && !this.job.getTransientExtras().maybeIsEmpty()) {
                pw.print(prefix);
                pw.print("  Transient extras: ");
                pw.println(this.job.getTransientExtras().toShortString());
            }
            if (this.job.getClipData() != null) {
                pw.print(prefix);
                pw.print("  Clip data: ");
                StringBuilder b = new StringBuilder(128);
                this.job.getClipData().toShortString(b);
                pw.println(b);
            }
            if (this.uriPerms != null) {
                pw.print(prefix);
                pw.println("  Granted URI permissions:");
                this.uriPerms.dump(pw, prefix + "  ");
            }
            if (this.job.getRequiredNetwork() != null) {
                pw.print(prefix);
                pw.print("  Network type: ");
                pw.println(this.job.getRequiredNetwork());
            }
            if (this.totalNetworkBytes != -1) {
                pw.print(prefix);
                pw.print("  Network bytes: ");
                pw.println(this.totalNetworkBytes);
            }
            if (this.job.getMinLatencyMillis() != 0) {
                pw.print(prefix);
                pw.print("  Minimum latency: ");
                TimeUtils.formatDuration(this.job.getMinLatencyMillis(), pw);
                pw.println();
            }
            if (this.job.getMaxExecutionDelayMillis() != 0) {
                pw.print(prefix);
                pw.print("  Max execution delay: ");
                TimeUtils.formatDuration(this.job.getMaxExecutionDelayMillis(), pw);
                pw.println();
            }
            pw.print(prefix);
            pw.print("  Backoff: policy=");
            pw.print(this.job.getBackoffPolicy());
            pw.print(" initial=");
            TimeUtils.formatDuration(this.job.getInitialBackoffMillis(), pw);
            pw.println();
            if (this.job.hasEarlyConstraint()) {
                pw.print(prefix);
                pw.println("  Has early constraint");
            }
            if (this.job.hasLateConstraint()) {
                pw.print(prefix);
                pw.println("  Has late constraint");
            }
        }
        pw.print(prefix);
        pw.print("Required constraints:");
        dumpConstraints(pw, this.requiredConstraints);
        pw.println();
        if (full) {
            pw.print(prefix);
            pw.print("Satisfied constraints:");
            dumpConstraints(pw, this.satisfiedConstraints);
            pw.println();
            pw.print(prefix);
            pw.print("Unsatisfied constraints:");
            dumpConstraints(pw, this.requiredConstraints & (~this.satisfiedConstraints));
            pw.println();
            if (this.dozeWhitelisted) {
                pw.print(prefix);
                pw.println("Doze whitelisted: true");
            }
            if (this.uidActive) {
                pw.print(prefix);
                pw.println("Uid: active");
            }
        }
        if (this.trackingControllers != 0) {
            pw.print(prefix);
            pw.print("Tracking:");
            if ((this.trackingControllers & 1) != 0) {
                pw.print(" BATTERY");
            }
            if ((this.trackingControllers & 2) != 0) {
                pw.print(" CONNECTIVITY");
            }
            if ((this.trackingControllers & 4) != 0) {
                pw.print(" CONTENT");
            }
            if ((this.trackingControllers & 8) != 0) {
                pw.print(" IDLE");
            }
            if ((this.trackingControllers & 16) != 0) {
                pw.print(" STORAGE");
            }
            if ((32 & this.trackingControllers) != 0) {
                pw.print(" TIME");
            }
            pw.println();
        }
        if (this.changedAuthorities != null) {
            pw.print(prefix);
            pw.println("Changed authorities:");
            for (int i3 = 0; i3 < this.changedAuthorities.size(); i3++) {
                pw.print(prefix);
                pw.print("  ");
                pw.println(this.changedAuthorities.valueAt(i3));
            }
            if (this.changedUris != null) {
                pw.print(prefix);
                pw.println("Changed URIs:");
                for (int i4 = 0; i4 < this.changedUris.size(); i4++) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.println(this.changedUris.valueAt(i4));
                }
            }
        }
        if (this.network != null) {
            pw.print(prefix);
            pw.print("Network: ");
            pw.println(this.network);
        }
        if (this.pendingWork != null && this.pendingWork.size() > 0) {
            pw.print(prefix);
            pw.println("Pending work:");
            for (int i5 = 0; i5 < this.pendingWork.size(); i5++) {
                dumpJobWorkItem(pw, prefix, this.pendingWork.get(i5), i5);
            }
        }
        if (this.executingWork != null && this.executingWork.size() > 0) {
            pw.print(prefix);
            pw.println("Executing work:");
            while (true) {
                int i6 = i;
                if (i6 >= this.executingWork.size()) {
                    break;
                }
                dumpJobWorkItem(pw, prefix, this.executingWork.get(i6), i6);
                i = i6 + 1;
            }
        }
        pw.print(prefix);
        pw.print("Standby bucket: ");
        pw.println(bucketName(this.standbyBucket));
        if (this.standbyBucket > 0) {
            pw.print(prefix);
            pw.print("Base heartbeat: ");
            pw.println(this.baseHeartbeat);
        }
        if (this.whenStandbyDeferred != 0) {
            pw.print(prefix);
            pw.print("  Deferred since: ");
            TimeUtils.formatDuration(this.whenStandbyDeferred, elapsedRealtimeMillis, pw);
            pw.println();
        }
        pw.print(prefix);
        pw.print("Enqueue time: ");
        TimeUtils.formatDuration(this.enqueueTime, elapsedRealtimeMillis, pw);
        pw.println();
        pw.print(prefix);
        pw.print("Run time: earliest=");
        formatRunTime(pw, this.earliestRunTimeElapsedMillis, 0L, elapsedRealtimeMillis);
        pw.print(", latest=");
        formatRunTime(pw, this.latestRunTimeElapsedMillis, NO_LATEST_RUNTIME, elapsedRealtimeMillis);
        pw.println();
        if (this.numFailures != 0) {
            pw.print(prefix);
            pw.print("Num failures: ");
            pw.println(this.numFailures);
        }
        Time t = new Time();
        if (this.mLastSuccessfulRunTime != 0) {
            pw.print(prefix);
            pw.print("Last successful run: ");
            t.set(this.mLastSuccessfulRunTime);
            pw.println(t.format("%Y-%m-%d %H:%M:%S"));
        }
        if (this.mLastFailedRunTime != 0) {
            pw.print(prefix);
            pw.print("Last failed run: ");
            t.set(this.mLastFailedRunTime);
            pw.println(t.format("%Y-%m-%d %H:%M:%S"));
        }
    }

    public void dump(ProtoOutputStream proto, long fieldId, boolean full, long elapsedRealtimeMillis) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, this.callingUid);
        proto.write(1138166333442L, this.tag);
        proto.write(1120986464259L, getSourceUid());
        proto.write(1120986464260L, getSourceUserId());
        proto.write(1138166333445L, getSourcePackageName());
        proto.write(1112396529688L, getInternalFlags());
        if (full) {
            long jiToken = proto.start(1146756268038L);
            this.job.getService().writeToProto(proto, 1146756268033L);
            proto.write(1133871366146L, this.job.isPeriodic());
            proto.write(1112396529667L, this.job.getIntervalMillis());
            proto.write(1112396529668L, this.job.getFlexMillis());
            proto.write(1133871366149L, this.job.isPersisted());
            proto.write(1172526071814L, this.job.getPriority());
            proto.write(1120986464263L, this.job.getFlags());
            proto.write(1133871366152L, this.job.isRequireCharging());
            proto.write(1133871366153L, this.job.isRequireBatteryNotLow());
            proto.write(1133871366154L, this.job.isRequireDeviceIdle());
            if (this.job.getTriggerContentUris() != null) {
                for (int i = 0; i < this.job.getTriggerContentUris().length; i++) {
                    long tcuToken = proto.start(2246267895819L);
                    JobInfo.TriggerContentUri trig = this.job.getTriggerContentUris()[i];
                    proto.write(1120986464257L, trig.getFlags());
                    Uri u = trig.getUri();
                    if (u != null) {
                        proto.write(1138166333442L, u.toString());
                    }
                    proto.end(tcuToken);
                }
                if (this.job.getTriggerContentUpdateDelay() >= 0) {
                    proto.write(1112396529676L, this.job.getTriggerContentUpdateDelay());
                }
                if (this.job.getTriggerContentMaxDelay() >= 0) {
                    proto.write(1112396529677L, this.job.getTriggerContentMaxDelay());
                }
            }
            if (this.job.getExtras() != null && !this.job.getExtras().maybeIsEmpty()) {
                this.job.getExtras().writeToProto(proto, 1146756268046L);
            }
            if (this.job.getTransientExtras() != null && !this.job.getTransientExtras().maybeIsEmpty()) {
                this.job.getTransientExtras().writeToProto(proto, 1146756268047L);
            }
            if (this.job.getClipData() != null) {
                this.job.getClipData().writeToProto(proto, 1146756268048L);
            }
            if (this.uriPerms != null) {
                this.uriPerms.dump(proto, 1146756268049L);
            }
            if (this.job.getRequiredNetwork() != null) {
                this.job.getRequiredNetwork().writeToProto(proto, 1146756268050L);
            }
            if (this.totalNetworkBytes != -1) {
                proto.write(1112396529683L, this.totalNetworkBytes);
            }
            proto.write(1112396529684L, this.job.getMinLatencyMillis());
            proto.write(1112396529685L, this.job.getMaxExecutionDelayMillis());
            long bpToken = proto.start(1146756268054L);
            proto.write(1159641169921L, this.job.getBackoffPolicy());
            proto.write(1112396529666L, this.job.getInitialBackoffMillis());
            proto.end(bpToken);
            proto.write(1133871366167L, this.job.hasEarlyConstraint());
            proto.write(1133871366168L, this.job.hasLateConstraint());
            proto.end(jiToken);
        }
        dumpConstraints(proto, 2259152797703L, this.requiredConstraints);
        if (full) {
            dumpConstraints(proto, 2259152797704L, this.satisfiedConstraints);
            dumpConstraints(proto, 2259152797705L, this.requiredConstraints & (~this.satisfiedConstraints));
            proto.write(1133871366154L, this.dozeWhitelisted);
        }
        if ((this.trackingControllers & 1) != 0) {
            proto.write(2259152797707L, 0);
        }
        if ((this.trackingControllers & 2) != 0) {
            proto.write(2259152797707L, 1);
        }
        if ((this.trackingControllers & 4) != 0) {
            proto.write(2259152797707L, 2);
        }
        if ((this.trackingControllers & 8) != 0) {
            proto.write(2259152797707L, 3);
        }
        if ((this.trackingControllers & 16) != 0) {
            proto.write(2259152797707L, 4);
        }
        if ((this.trackingControllers & 32) != 0) {
            proto.write(2259152797707L, 5);
        }
        if (this.changedAuthorities != null) {
            for (int k = 0; k < this.changedAuthorities.size(); k++) {
                proto.write(2237677961228L, this.changedAuthorities.valueAt(k));
            }
        }
        if (this.changedUris != null) {
            for (int i2 = 0; i2 < this.changedUris.size(); i2++) {
                proto.write(2237677961229L, this.changedUris.valueAt(i2).toString());
            }
        }
        if (this.network != null) {
            this.network.writeToProto(proto, 1146756268046L);
        }
        if (this.pendingWork != null && this.pendingWork.size() > 0) {
            for (int i3 = 0; i3 < this.pendingWork.size(); i3++) {
                dumpJobWorkItem(proto, 2246267895823L, this.pendingWork.get(i3));
            }
        }
        if (this.executingWork != null && this.executingWork.size() > 0) {
            for (int i4 = 0; i4 < this.executingWork.size(); i4++) {
                dumpJobWorkItem(proto, 2246267895824L, this.executingWork.get(i4));
            }
        }
        proto.write(1159641169937L, this.standbyBucket);
        proto.write(1112396529682L, elapsedRealtimeMillis - this.enqueueTime);
        if (this.earliestRunTimeElapsedMillis == 0) {
            proto.write(1176821039123L, 0);
        } else {
            proto.write(1176821039123L, this.earliestRunTimeElapsedMillis - elapsedRealtimeMillis);
        }
        if (this.latestRunTimeElapsedMillis == NO_LATEST_RUNTIME) {
            proto.write(1176821039124L, 0);
        } else {
            proto.write(1176821039124L, this.latestRunTimeElapsedMillis - elapsedRealtimeMillis);
        }
        proto.write(1120986464277L, this.numFailures);
        proto.write(1112396529686L, this.mLastSuccessfulRunTime);
        proto.write(1112396529687L, this.mLastFailedRunTime);
        proto.end(token);
    }
}
