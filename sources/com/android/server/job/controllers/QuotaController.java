package com.android.server.job.controllers;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseSetArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.QuotaController;
import com.android.server.usage.AppStandbyController;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/* loaded from: classes.dex */
public final class QuotaController extends StateController {
    private static final String ALARM_TAG_CLEANUP = "*job.cleanup*";
    private static final String ALARM_TAG_QUOTA_CHECK = "*job.quota_check*";
    private static final boolean DEBUG;
    private static final long MAX_PERIOD_MS = 86400000;
    private static final int MSG_CHECK_PACKAGE = 2;
    private static final int MSG_CLEAN_UP_SESSIONS = 1;
    private static final int MSG_REACHED_QUOTA = 0;
    private static final int MSG_UID_PROCESS_STATE_CHANGED = 3;
    private static final String TAG = "JobScheduler.Quota";
    private final ActivityManagerInternal mActivityManagerInternal;
    private final AlarmManager mAlarmManager;
    private long mAllowedTimeIntoQuotaMs;
    private long mAllowedTimePerPeriodMs;
    private final long[] mBucketPeriodsMs;
    private final ChargingTracker mChargeTracker;
    private final DeleteTimingSessionsFunctor mDeleteOldSessionsFunctor;
    private final EarliestEndTimeFunctor mEarliestEndTimeFunctor;
    private final UserPackageMap<ExecutionStats[]> mExecutionStatsCache;
    private final SparseBooleanArray mForegroundUids;
    private final Handler mHandler;
    private volatile boolean mInParole;
    private final UserPackageMap<QcAlarmListener> mInQuotaAlarmListeners;
    private final int[] mMaxBucketJobCounts;
    private final int[] mMaxBucketSessionCounts;
    private long mMaxExecutionTimeIntoQuotaMs;
    private long mMaxExecutionTimeMs;
    private int mMaxJobCountPerRateLimitingWindow;
    private int mMaxSessionCountPerRateLimitingWindow;
    private long mNextCleanupTimeElapsed;
    private final BroadcastReceiver mPackageAddedReceiver;
    private final UserPackageMap<Timer> mPkgTimers;
    private final QcConstants mQcConstants;
    private long mQuotaBufferMs;
    private long mRateLimitingWindowMs;
    private final AlarmManager.OnAlarmListener mSessionCleanupAlarmListener;
    private boolean mShouldThrottle;
    private long mTimingSessionCoalescingDurationMs;
    private final UserPackageMap<List<TimingSession>> mTimingSessions;
    private final ArraySet<JobStatus> mTopStartedJobs;
    private final UserPackageMap<ArraySet<JobStatus>> mTrackedJobs;
    private final IUidObserver mUidObserver;
    private final SparseSetArray<String> mUidToPackageCache;
    private final UidConstraintUpdater mUpdateUidConstraints;

    static {
        DEBUG = JobSchedulerService.DEBUG || Log.isLoggable(TAG, 3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class UserPackageMap<T> {
        private final SparseArray<ArrayMap<String, T>> mData;

        private UserPackageMap() {
            this.mData = new SparseArray<>();
        }

        public void add(int userId, String packageName, T obj) {
            ArrayMap<String, T> data = this.mData.get(userId);
            if (data == null) {
                data = new ArrayMap<>();
                this.mData.put(userId, data);
            }
            data.put(packageName, obj);
        }

        public void clear() {
            for (int i = 0; i < this.mData.size(); i++) {
                this.mData.valueAt(i).clear();
            }
        }

        public void delete(int userId) {
            this.mData.delete(userId);
        }

        public void delete(int userId, String packageName) {
            ArrayMap<String, T> data = this.mData.get(userId);
            if (data != null) {
                data.remove(packageName);
            }
        }

        public T get(int userId, String packageName) {
            ArrayMap<String, T> data = this.mData.get(userId);
            if (data != null) {
                return data.get(packageName);
            }
            return null;
        }

        public int indexOfKey(int userId) {
            return this.mData.indexOfKey(userId);
        }

        public int keyAt(int index) {
            return this.mData.keyAt(index);
        }

        public String keyAt(int userIndex, int packageIndex) {
            return this.mData.valueAt(userIndex).keyAt(packageIndex);
        }

        public int numUsers() {
            return this.mData.size();
        }

        public int numPackagesForUser(int userId) {
            ArrayMap<String, T> data = this.mData.get(userId);
            if (data == null) {
                return 0;
            }
            return data.size();
        }

        public T valueAt(int userIndex, int packageIndex) {
            return this.mData.valueAt(userIndex).valueAt(packageIndex);
        }

        public void forEach(Consumer<T> consumer) {
            for (int i = numUsers() - 1; i >= 0; i--) {
                ArrayMap<String, T> data = this.mData.valueAt(i);
                for (int j = data.size() - 1; j >= 0; j--) {
                    consumer.accept(data.valueAt(j));
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String string(int userId, String packageName) {
        return "<" + userId + ">" + packageName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Package {
        public final String packageName;
        public final int userId;

        Package(int userId, String packageName) {
            this.userId = userId;
            this.packageName = packageName;
        }

        public String toString() {
            return QuotaController.string(this.userId, this.packageName);
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1120986464257L, this.userId);
            proto.write(1138166333442L, this.packageName);
            proto.end(token);
        }

        public boolean equals(Object obj) {
            if (obj instanceof Package) {
                Package other = (Package) obj;
                return this.userId == other.userId && Objects.equals(this.packageName, other.packageName);
            }
            return false;
        }

        public int hashCode() {
            return this.packageName.hashCode() + this.userId;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int hashLong(long val) {
        return (int) ((val >>> 32) ^ val);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class ExecutionStats {
        public int bgJobCountInMaxPeriod;
        public int bgJobCountInWindow;
        public long executionTimeInMaxPeriodMs;
        public long executionTimeInWindowMs;
        public long expirationTimeElapsed;
        public long inQuotaTimeElapsed;
        public int jobCountInRateLimitingWindow;
        public int jobCountLimit;
        public long jobRateLimitExpirationTimeElapsed;
        public int sessionCountInRateLimitingWindow;
        public int sessionCountInWindow;
        public int sessionCountLimit;
        public long sessionRateLimitExpirationTimeElapsed;
        public long windowSizeMs;

        ExecutionStats() {
        }

        public String toString() {
            return "expirationTime=" + this.expirationTimeElapsed + ", windowSizeMs=" + this.windowSizeMs + ", jobCountLimit=" + this.jobCountLimit + ", sessionCountLimit=" + this.sessionCountLimit + ", executionTimeInWindow=" + this.executionTimeInWindowMs + ", bgJobCountInWindow=" + this.bgJobCountInWindow + ", executionTimeInMaxPeriod=" + this.executionTimeInMaxPeriodMs + ", bgJobCountInMaxPeriod=" + this.bgJobCountInMaxPeriod + ", sessionCountInWindow=" + this.sessionCountInWindow + ", inQuotaTime=" + this.inQuotaTimeElapsed + ", jobCountExpirationTime=" + this.jobRateLimitExpirationTimeElapsed + ", jobCountInRateLimitingWindow=" + this.jobCountInRateLimitingWindow + ", sessionCountExpirationTime=" + this.sessionRateLimitExpirationTimeElapsed + ", sessionCountInRateLimitingWindow=" + this.sessionCountInRateLimitingWindow;
        }

        public boolean equals(Object obj) {
            if (obj instanceof ExecutionStats) {
                ExecutionStats other = (ExecutionStats) obj;
                return this.expirationTimeElapsed == other.expirationTimeElapsed && this.windowSizeMs == other.windowSizeMs && this.jobCountLimit == other.jobCountLimit && this.sessionCountLimit == other.sessionCountLimit && this.executionTimeInWindowMs == other.executionTimeInWindowMs && this.bgJobCountInWindow == other.bgJobCountInWindow && this.executionTimeInMaxPeriodMs == other.executionTimeInMaxPeriodMs && this.sessionCountInWindow == other.sessionCountInWindow && this.bgJobCountInMaxPeriod == other.bgJobCountInMaxPeriod && this.inQuotaTimeElapsed == other.inQuotaTimeElapsed && this.jobRateLimitExpirationTimeElapsed == other.jobRateLimitExpirationTimeElapsed && this.jobCountInRateLimitingWindow == other.jobCountInRateLimitingWindow && this.sessionRateLimitExpirationTimeElapsed == other.sessionRateLimitExpirationTimeElapsed && this.sessionCountInRateLimitingWindow == other.sessionCountInRateLimitingWindow;
            }
            return false;
        }

        public int hashCode() {
            int result = (0 * 31) + QuotaController.hashLong(this.expirationTimeElapsed);
            return (((((((((((((((((((((((((result * 31) + QuotaController.hashLong(this.windowSizeMs)) * 31) + QuotaController.hashLong(this.jobCountLimit)) * 31) + QuotaController.hashLong(this.sessionCountLimit)) * 31) + QuotaController.hashLong(this.executionTimeInWindowMs)) * 31) + this.bgJobCountInWindow) * 31) + QuotaController.hashLong(this.executionTimeInMaxPeriodMs)) * 31) + this.bgJobCountInMaxPeriod) * 31) + this.sessionCountInWindow) * 31) + QuotaController.hashLong(this.inQuotaTimeElapsed)) * 31) + QuotaController.hashLong(this.jobRateLimitExpirationTimeElapsed)) * 31) + this.jobCountInRateLimitingWindow) * 31) + QuotaController.hashLong(this.sessionRateLimitExpirationTimeElapsed)) * 31) + this.sessionCountInRateLimitingWindow;
        }
    }

    public QuotaController(JobSchedulerService service) {
        super(service);
        this.mTrackedJobs = new UserPackageMap<>();
        this.mPkgTimers = new UserPackageMap<>();
        this.mTimingSessions = new UserPackageMap<>();
        this.mInQuotaAlarmListeners = new UserPackageMap<>();
        this.mExecutionStatsCache = new UserPackageMap<>();
        this.mForegroundUids = new SparseBooleanArray();
        this.mUidToPackageCache = new SparseSetArray<>();
        this.mTopStartedJobs = new ArraySet<>();
        this.mAllowedTimePerPeriodMs = 600000L;
        this.mMaxExecutionTimeMs = 14400000L;
        this.mQuotaBufferMs = 30000L;
        long j = this.mAllowedTimePerPeriodMs;
        long j2 = this.mQuotaBufferMs;
        this.mAllowedTimeIntoQuotaMs = j - j2;
        this.mMaxExecutionTimeIntoQuotaMs = this.mMaxExecutionTimeMs - j2;
        this.mRateLimitingWindowMs = 60000L;
        this.mMaxJobCountPerRateLimitingWindow = 20;
        this.mMaxSessionCountPerRateLimitingWindow = 20;
        this.mNextCleanupTimeElapsed = 0L;
        this.mSessionCleanupAlarmListener = new AlarmManager.OnAlarmListener() { // from class: com.android.server.job.controllers.QuotaController.1
            @Override // android.app.AlarmManager.OnAlarmListener
            public void onAlarm() {
                QuotaController.this.mHandler.obtainMessage(1).sendToTarget();
            }
        };
        this.mUidObserver = new IUidObserver.Stub() { // from class: com.android.server.job.controllers.QuotaController.2
            public void onUidStateChanged(int uid, int procState, long procStateSeq) {
                QuotaController.this.mHandler.obtainMessage(3, uid, procState).sendToTarget();
            }

            public void onUidGone(int uid, boolean disabled) {
            }

            public void onUidActive(int uid) {
            }

            public void onUidIdle(int uid, boolean disabled) {
            }

            public void onUidCachedChanged(int uid, boolean cached) {
            }
        };
        this.mPackageAddedReceiver = new BroadcastReceiver() { // from class: com.android.server.job.controllers.QuotaController.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getBooleanExtra("android.intent.extra.REPLACING", false)) {
                    return;
                }
                int uid = intent.getIntExtra("android.intent.extra.UID", -1);
                synchronized (QuotaController.this.mLock) {
                    QuotaController.this.mUidToPackageCache.remove(uid);
                }
            }
        };
        this.mBucketPeriodsMs = new long[]{600000, AppStandbyController.SettingsObserver.DEFAULT_SYSTEM_UPDATE_TIMEOUT, 28800000, 86400000};
        this.mMaxBucketJobCounts = new int[]{75, 120, 200, 48};
        this.mMaxBucketSessionCounts = new int[]{75, 10, 8, 3};
        this.mTimingSessionCoalescingDurationMs = 5000L;
        this.mEarliestEndTimeFunctor = new EarliestEndTimeFunctor();
        this.mUpdateUidConstraints = new UidConstraintUpdater();
        this.mDeleteOldSessionsFunctor = new DeleteTimingSessionsFunctor();
        this.mHandler = new QcHandler(this.mContext.getMainLooper());
        this.mChargeTracker = new ChargingTracker();
        this.mChargeTracker.startTracking();
        this.mActivityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        this.mAlarmManager = (AlarmManager) this.mContext.getSystemService("alarm");
        this.mQcConstants = new QcConstants(this.mHandler);
        IntentFilter filter = new IntentFilter("android.intent.action.PACKAGE_ADDED");
        this.mContext.registerReceiverAsUser(this.mPackageAddedReceiver, UserHandle.ALL, filter, null, null);
        UsageStatsManagerInternal usageStats = (UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class);
        usageStats.addAppIdleStateChangeListener(new StandbyTracker());
        try {
            ActivityManager.getService().registerUidObserver(this.mUidObserver, 1, 5, (String) null);
        } catch (RemoteException e) {
        }
        this.mShouldThrottle = !this.mConstants.USE_HEARTBEATS;
    }

    @Override // com.android.server.job.controllers.StateController
    public void onSystemServicesReady() {
        this.mQcConstants.start(this.mContext.getContentResolver());
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        int userId = jobStatus.getSourceUserId();
        String pkgName = jobStatus.getSourcePackageName();
        ArraySet<JobStatus> jobs = this.mTrackedJobs.get(userId, pkgName);
        if (jobs == null) {
            jobs = new ArraySet<>();
            this.mTrackedJobs.add(userId, pkgName, jobs);
        }
        jobs.add(jobStatus);
        jobStatus.setTrackingController(64);
        if (this.mShouldThrottle) {
            boolean isWithinQuota = isWithinQuotaLocked(jobStatus);
            setConstraintSatisfied(jobStatus, isWithinQuota);
            if (!isWithinQuota) {
                maybeScheduleStartAlarmLocked(userId, pkgName, getEffectiveStandbyBucket(jobStatus));
                return;
            }
            return;
        }
        jobStatus.setQuotaConstraintSatisfied(true);
    }

    @Override // com.android.server.job.controllers.StateController
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        if (DEBUG) {
            Slog.d(TAG, "Prepping for " + jobStatus.toShortString());
        }
        int uid = jobStatus.getSourceUid();
        if (this.mActivityManagerInternal.getUidProcessState(uid) <= 2) {
            if (DEBUG) {
                Slog.d(TAG, jobStatus.toShortString() + " is top started job");
            }
            this.mTopStartedJobs.add(jobStatus);
            return;
        }
        int userId = jobStatus.getSourceUserId();
        String packageName = jobStatus.getSourcePackageName();
        Timer timer = this.mPkgTimers.get(userId, packageName);
        if (timer == null) {
            timer = new Timer(uid, userId, packageName);
            this.mPkgTimers.add(userId, packageName, timer);
        }
        timer.startTrackingJobLocked(jobStatus);
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob, boolean forUpdate) {
        if (jobStatus.clearTrackingController(64)) {
            Timer timer = this.mPkgTimers.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
            if (timer != null) {
                timer.stopTrackingJob(jobStatus);
            }
            ArraySet<JobStatus> jobs = this.mTrackedJobs.get(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName());
            if (jobs != null) {
                jobs.remove(jobStatus);
            }
            this.mTopStartedJobs.remove(jobStatus);
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void onConstantsUpdatedLocked() {
        if (this.mShouldThrottle == this.mConstants.USE_HEARTBEATS) {
            this.mShouldThrottle = !this.mConstants.USE_HEARTBEATS;
            BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$Nr0Q3oPwHBGHfHSdpzIm80t7M7s
                @Override // java.lang.Runnable
                public final void run() {
                    QuotaController.this.lambda$onConstantsUpdatedLocked$0$QuotaController();
                }
            });
        }
    }

    public /* synthetic */ void lambda$onConstantsUpdatedLocked$0$QuotaController() {
        synchronized (this.mLock) {
            maybeUpdateAllConstraintsLocked();
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void onAppRemovedLocked(String packageName, int uid) {
        if (packageName == null) {
            Slog.wtf(TAG, "Told app removed but given null package name.");
            return;
        }
        int userId = UserHandle.getUserId(uid);
        this.mTrackedJobs.delete(userId, packageName);
        Timer timer = this.mPkgTimers.get(userId, packageName);
        if (timer != null) {
            if (timer.isActive()) {
                Slog.wtf(TAG, "onAppRemovedLocked called before Timer turned off.");
                timer.dropEverythingLocked();
            }
            this.mPkgTimers.delete(userId, packageName);
        }
        this.mTimingSessions.delete(userId, packageName);
        QcAlarmListener alarmListener = this.mInQuotaAlarmListeners.get(userId, packageName);
        if (alarmListener != null) {
            this.mAlarmManager.cancel(alarmListener);
            this.mInQuotaAlarmListeners.delete(userId, packageName);
        }
        this.mExecutionStatsCache.delete(userId, packageName);
        this.mForegroundUids.delete(uid);
        this.mUidToPackageCache.remove(uid);
    }

    @Override // com.android.server.job.controllers.StateController
    public void onUserRemovedLocked(int userId) {
        this.mTrackedJobs.delete(userId);
        this.mPkgTimers.delete(userId);
        this.mTimingSessions.delete(userId);
        this.mInQuotaAlarmListeners.delete(userId);
        this.mExecutionStatsCache.delete(userId);
        this.mUidToPackageCache.clear();
    }

    private boolean isUidInForeground(int uid) {
        boolean z;
        if (UserHandle.isCore(uid)) {
            return true;
        }
        synchronized (this.mLock) {
            z = this.mForegroundUids.get(uid);
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isTopStartedJobLocked(JobStatus jobStatus) {
        return this.mTopStartedJobs.contains(jobStatus);
    }

    private int getEffectiveStandbyBucket(JobStatus jobStatus) {
        if (jobStatus.uidActive || jobStatus.getJob().isExemptedFromAppStandby()) {
            return 0;
        }
        return jobStatus.getStandbyBucket();
    }

    @VisibleForTesting
    boolean isWithinQuotaLocked(JobStatus jobStatus) {
        int standbyBucket = getEffectiveStandbyBucket(jobStatus);
        return isTopStartedJobLocked(jobStatus) || isUidInForeground(jobStatus.getSourceUid()) || isWithinQuotaLocked(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(), standbyBucket);
    }

    @VisibleForTesting
    boolean isWithinQuotaLocked(int userId, String packageName, int standbyBucket) {
        if (standbyBucket == 4) {
            return false;
        }
        if (!this.mShouldThrottle || this.mChargeTracker.isCharging() || this.mInParole) {
            return true;
        }
        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        return getRemainingExecutionTimeLocked(stats) > 0 && isUnderJobCountQuotaLocked(stats, standbyBucket) && isUnderSessionCountQuotaLocked(stats, standbyBucket);
    }

    private boolean isUnderJobCountQuotaLocked(ExecutionStats stats, int standbyBucket) {
        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        boolean isUnderAllowedTimeQuota = stats.jobRateLimitExpirationTimeElapsed <= now || stats.jobCountInRateLimitingWindow < this.mMaxJobCountPerRateLimitingWindow;
        return isUnderAllowedTimeQuota && stats.bgJobCountInWindow < this.mMaxBucketJobCounts[standbyBucket];
    }

    private boolean isUnderSessionCountQuotaLocked(ExecutionStats stats, int standbyBucket) {
        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        boolean isUnderAllowedTimeQuota = stats.sessionRateLimitExpirationTimeElapsed <= now || stats.sessionCountInRateLimitingWindow < this.mMaxSessionCountPerRateLimitingWindow;
        return isUnderAllowedTimeQuota && stats.sessionCountInWindow < this.mMaxBucketSessionCounts[standbyBucket];
    }

    @VisibleForTesting
    long getRemainingExecutionTimeLocked(JobStatus jobStatus) {
        return getRemainingExecutionTimeLocked(jobStatus.getSourceUserId(), jobStatus.getSourcePackageName(), getEffectiveStandbyBucket(jobStatus));
    }

    @VisibleForTesting
    long getRemainingExecutionTimeLocked(int userId, String packageName) {
        int standbyBucket = JobSchedulerService.standbyBucketForPackage(packageName, userId, JobSchedulerService.sElapsedRealtimeClock.millis());
        return getRemainingExecutionTimeLocked(userId, packageName, standbyBucket);
    }

    private long getRemainingExecutionTimeLocked(int userId, String packageName, int standbyBucket) {
        if (standbyBucket == 4) {
            return 0L;
        }
        return getRemainingExecutionTimeLocked(getExecutionStatsLocked(userId, packageName, standbyBucket));
    }

    private long getRemainingExecutionTimeLocked(ExecutionStats stats) {
        return Math.min(this.mAllowedTimePerPeriodMs - stats.executionTimeInWindowMs, this.mMaxExecutionTimeMs - stats.executionTimeInMaxPeriodMs);
    }

    @VisibleForTesting
    long getTimeUntilQuotaConsumedLocked(int userId, String packageName) {
        long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
        int standbyBucket = JobSchedulerService.standbyBucketForPackage(packageName, userId, nowElapsed);
        if (standbyBucket == 4) {
            return 0L;
        }
        List<TimingSession> sessions = this.mTimingSessions.get(userId, packageName);
        if (sessions == null || sessions.size() == 0) {
            return this.mAllowedTimePerPeriodMs;
        }
        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        long startWindowElapsed = nowElapsed - stats.windowSizeMs;
        long startMaxElapsed = nowElapsed - 86400000;
        long allowedTimeRemainingMs = this.mAllowedTimePerPeriodMs - stats.executionTimeInWindowMs;
        long maxExecutionTimeRemainingMs = this.mMaxExecutionTimeMs - stats.executionTimeInMaxPeriodMs;
        if (stats.windowSizeMs == this.mAllowedTimePerPeriodMs) {
            return calculateTimeUntilQuotaConsumedLocked(sessions, startMaxElapsed, maxExecutionTimeRemainingMs);
        }
        return Math.min(calculateTimeUntilQuotaConsumedLocked(sessions, startMaxElapsed, maxExecutionTimeRemainingMs), calculateTimeUntilQuotaConsumedLocked(sessions, startWindowElapsed, allowedTimeRemainingMs));
    }

    private long calculateTimeUntilQuotaConsumedLocked(List<TimingSession> sessions, long windowStartElapsed, long deadSpaceMs) {
        long start = windowStartElapsed;
        long timeUntilQuotaConsumedMs = 0;
        long deadSpaceMs2 = deadSpaceMs;
        for (int i = 0; i < sessions.size(); i++) {
            TimingSession session = sessions.get(i);
            if (session.endTimeElapsed >= windowStartElapsed) {
                if (session.startTimeElapsed <= windowStartElapsed) {
                    timeUntilQuotaConsumedMs += session.endTimeElapsed - windowStartElapsed;
                    start = session.endTimeElapsed;
                } else {
                    long diff = session.startTimeElapsed - start;
                    if (diff > deadSpaceMs2) {
                        break;
                    }
                    timeUntilQuotaConsumedMs += (session.endTimeElapsed - session.startTimeElapsed) + diff;
                    deadSpaceMs2 -= diff;
                    start = session.endTimeElapsed;
                }
            }
        }
        long timeUntilQuotaConsumedMs2 = timeUntilQuotaConsumedMs + deadSpaceMs2;
        if (timeUntilQuotaConsumedMs2 > this.mMaxExecutionTimeMs) {
            Slog.wtf(TAG, "Calculated quota consumed time too high: " + timeUntilQuotaConsumedMs2);
        }
        return timeUntilQuotaConsumedMs2;
    }

    @VisibleForTesting
    ExecutionStats getExecutionStatsLocked(int userId, String packageName, int standbyBucket) {
        return getExecutionStatsLocked(userId, packageName, standbyBucket, true);
    }

    private ExecutionStats getExecutionStatsLocked(int userId, String packageName, int standbyBucket, boolean refreshStatsIfOld) {
        if (standbyBucket == 4) {
            Slog.wtf(TAG, "getExecutionStatsLocked called for a NEVER app.");
            return new ExecutionStats();
        }
        ExecutionStats[] appStats = this.mExecutionStatsCache.get(userId, packageName);
        if (appStats == null) {
            appStats = new ExecutionStats[this.mBucketPeriodsMs.length];
            this.mExecutionStatsCache.add(userId, packageName, appStats);
        }
        ExecutionStats stats = appStats[standbyBucket];
        if (stats == null) {
            stats = new ExecutionStats();
            appStats[standbyBucket] = stats;
        }
        if (refreshStatsIfOld) {
            long bucketWindowSizeMs = this.mBucketPeriodsMs[standbyBucket];
            int jobCountLimit = this.mMaxBucketJobCounts[standbyBucket];
            int sessionCountLimit = this.mMaxBucketSessionCounts[standbyBucket];
            Timer timer = this.mPkgTimers.get(userId, packageName);
            if ((timer != null && timer.isActive()) || stats.expirationTimeElapsed <= JobSchedulerService.sElapsedRealtimeClock.millis() || stats.windowSizeMs != bucketWindowSizeMs || stats.jobCountLimit != jobCountLimit || stats.sessionCountLimit != sessionCountLimit) {
                stats.windowSizeMs = bucketWindowSizeMs;
                stats.jobCountLimit = jobCountLimit;
                stats.sessionCountLimit = sessionCountLimit;
                updateExecutionStatsLocked(userId, packageName, stats);
            }
        }
        return stats;
    }

    @VisibleForTesting
    void updateExecutionStatsLocked(int userId, String packageName, ExecutionStats stats) {
        long nowElapsed;
        int sessionCountInWindow;
        long startWindowElapsed;
        int loopStart;
        long start;
        long emptyTimeMs;
        stats.executionTimeInWindowMs = 0L;
        stats.bgJobCountInWindow = 0;
        stats.executionTimeInMaxPeriodMs = 0L;
        stats.bgJobCountInMaxPeriod = 0;
        stats.sessionCountInWindow = 0;
        stats.inQuotaTimeElapsed = 0L;
        Timer timer = this.mPkgTimers.get(userId, packageName);
        long nowElapsed2 = JobSchedulerService.sElapsedRealtimeClock.millis();
        stats.expirationTimeElapsed = nowElapsed2 + 86400000;
        if (timer != null && timer.isActive()) {
            long currentDuration = timer.getCurrentDuration(nowElapsed2);
            stats.executionTimeInMaxPeriodMs = currentDuration;
            stats.executionTimeInWindowMs = currentDuration;
            int bgJobCount = timer.getBgJobCount();
            stats.bgJobCountInMaxPeriod = bgJobCount;
            stats.bgJobCountInWindow = bgJobCount;
            stats.expirationTimeElapsed = nowElapsed2;
            if (stats.executionTimeInWindowMs >= this.mAllowedTimeIntoQuotaMs) {
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, (nowElapsed2 - this.mAllowedTimeIntoQuotaMs) + stats.windowSizeMs);
            }
            if (stats.executionTimeInMaxPeriodMs >= this.mMaxExecutionTimeIntoQuotaMs) {
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, (nowElapsed2 - this.mMaxExecutionTimeIntoQuotaMs) + 86400000);
            }
        }
        List<TimingSession> sessions = this.mTimingSessions.get(userId, packageName);
        if (sessions != null && sessions.size() != 0) {
            long startWindowElapsed2 = nowElapsed2 - stats.windowSizeMs;
            long startMaxElapsed = nowElapsed2 - 86400000;
            int loopStart2 = sessions.size() - 1;
            int i = loopStart2;
            int sessionCountInWindow2 = 0;
            long emptyTimeMs2 = Long.MAX_VALUE;
            while (true) {
                if (i < 0) {
                    nowElapsed = nowElapsed2;
                    sessionCountInWindow = sessionCountInWindow2;
                    break;
                }
                TimingSession session = sessions.get(i);
                Timer timer2 = timer;
                nowElapsed = nowElapsed2;
                if (startWindowElapsed2 < session.endTimeElapsed) {
                    if (startWindowElapsed2 < session.startTimeElapsed) {
                        long start2 = session.startTimeElapsed;
                        long start3 = session.startTimeElapsed;
                        emptyTimeMs = Math.min(emptyTimeMs2, start3 - startWindowElapsed2);
                        start = start2;
                    } else {
                        start = startWindowElapsed2;
                        emptyTimeMs = 0;
                    }
                    startWindowElapsed = startWindowElapsed2;
                    long startWindowElapsed3 = stats.executionTimeInWindowMs;
                    long emptyTimeMs3 = emptyTimeMs;
                    long emptyTimeMs4 = session.endTimeElapsed;
                    stats.executionTimeInWindowMs = startWindowElapsed3 + (emptyTimeMs4 - start);
                    stats.bgJobCountInWindow += session.bgJobCount;
                    if (stats.executionTimeInWindowMs >= this.mAllowedTimeIntoQuotaMs) {
                        long j = stats.inQuotaTimeElapsed;
                        long j2 = stats.executionTimeInWindowMs + start;
                        long start4 = this.mAllowedTimeIntoQuotaMs;
                        stats.inQuotaTimeElapsed = Math.max(j, (j2 - start4) + stats.windowSizeMs);
                    }
                    if (stats.bgJobCountInWindow >= stats.jobCountLimit) {
                        stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, session.endTimeElapsed + stats.windowSizeMs);
                    }
                    if (i == loopStart2 || sessions.get(i + 1).startTimeElapsed - session.endTimeElapsed > this.mTimingSessionCoalescingDurationMs) {
                        int sessionCountInWindow3 = sessionCountInWindow2 + 1;
                        if (sessionCountInWindow3 >= stats.sessionCountLimit) {
                            stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, session.endTimeElapsed + stats.windowSizeMs);
                        }
                        sessionCountInWindow2 = sessionCountInWindow3;
                        emptyTimeMs2 = emptyTimeMs3;
                    } else {
                        emptyTimeMs2 = emptyTimeMs3;
                    }
                } else {
                    startWindowElapsed = startWindowElapsed2;
                }
                if (startMaxElapsed >= session.startTimeElapsed) {
                    loopStart = loopStart2;
                    if (startMaxElapsed >= session.endTimeElapsed) {
                        sessionCountInWindow = sessionCountInWindow2;
                        break;
                    }
                    stats.executionTimeInMaxPeriodMs += session.endTimeElapsed - startMaxElapsed;
                    stats.bgJobCountInMaxPeriod += session.bgJobCount;
                    if (stats.executionTimeInMaxPeriodMs >= this.mMaxExecutionTimeIntoQuotaMs) {
                        stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, ((stats.executionTimeInMaxPeriodMs + startMaxElapsed) - this.mMaxExecutionTimeIntoQuotaMs) + 86400000);
                    }
                    emptyTimeMs2 = 0;
                } else {
                    loopStart = loopStart2;
                    stats.executionTimeInMaxPeriodMs += session.endTimeElapsed - session.startTimeElapsed;
                    stats.bgJobCountInMaxPeriod += session.bgJobCount;
                    long emptyTimeMs5 = Math.min(emptyTimeMs2, session.startTimeElapsed - startMaxElapsed);
                    if (stats.executionTimeInMaxPeriodMs >= this.mMaxExecutionTimeIntoQuotaMs) {
                        stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed, ((session.startTimeElapsed + stats.executionTimeInMaxPeriodMs) - this.mMaxExecutionTimeIntoQuotaMs) + 86400000);
                    }
                    emptyTimeMs2 = emptyTimeMs5;
                }
                i--;
                timer = timer2;
                nowElapsed2 = nowElapsed;
                startWindowElapsed2 = startWindowElapsed;
                loopStart2 = loopStart;
            }
            stats.expirationTimeElapsed = nowElapsed + emptyTimeMs2;
            stats.sessionCountInWindow = sessionCountInWindow;
        }
    }

    @VisibleForTesting
    void invalidateAllExecutionStatsLocked() {
        final long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
        this.mExecutionStatsCache.forEach(new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$_TfEfRX3HfrCL4MPpYyPFNwGLtM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                QuotaController.lambda$invalidateAllExecutionStatsLocked$1(nowElapsed, (QuotaController.ExecutionStats[]) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$invalidateAllExecutionStatsLocked$1(long nowElapsed, ExecutionStats[] appStats) {
        if (appStats != null) {
            for (ExecutionStats stats : appStats) {
                if (stats != null) {
                    stats.expirationTimeElapsed = nowElapsed;
                }
            }
        }
    }

    @VisibleForTesting
    void invalidateAllExecutionStatsLocked(int userId, String packageName) {
        ExecutionStats[] appStats = this.mExecutionStatsCache.get(userId, packageName);
        if (appStats != null) {
            long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
            for (ExecutionStats stats : appStats) {
                if (stats != null) {
                    stats.expirationTimeElapsed = nowElapsed;
                }
            }
        }
    }

    @VisibleForTesting
    void incrementJobCount(int userId, String packageName, int count) {
        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        ExecutionStats[] appStats = this.mExecutionStatsCache.get(userId, packageName);
        if (appStats == null) {
            appStats = new ExecutionStats[this.mBucketPeriodsMs.length];
            this.mExecutionStatsCache.add(userId, packageName, appStats);
        }
        for (int i = 0; i < appStats.length; i++) {
            ExecutionStats stats = appStats[i];
            if (stats == null) {
                stats = new ExecutionStats();
                appStats[i] = stats;
            }
            if (stats.jobRateLimitExpirationTimeElapsed <= now) {
                stats.jobRateLimitExpirationTimeElapsed = this.mRateLimitingWindowMs + now;
                stats.jobCountInRateLimitingWindow = 0;
            }
            stats.jobCountInRateLimitingWindow += count;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void incrementTimingSessionCount(int userId, String packageName) {
        long now = JobSchedulerService.sElapsedRealtimeClock.millis();
        ExecutionStats[] appStats = this.mExecutionStatsCache.get(userId, packageName);
        if (appStats == null) {
            appStats = new ExecutionStats[this.mBucketPeriodsMs.length];
            this.mExecutionStatsCache.add(userId, packageName, appStats);
        }
        for (int i = 0; i < appStats.length; i++) {
            ExecutionStats stats = appStats[i];
            if (stats == null) {
                stats = new ExecutionStats();
                appStats[i] = stats;
            }
            if (stats.sessionRateLimitExpirationTimeElapsed <= now) {
                stats.sessionRateLimitExpirationTimeElapsed = this.mRateLimitingWindowMs + now;
                stats.sessionCountInRateLimitingWindow = 0;
            }
            stats.sessionCountInRateLimitingWindow++;
        }
    }

    @VisibleForTesting
    void saveTimingSession(int userId, String packageName, TimingSession session) {
        synchronized (this.mLock) {
            List<TimingSession> sessions = this.mTimingSessions.get(userId, packageName);
            if (sessions == null) {
                sessions = new ArrayList();
                this.mTimingSessions.add(userId, packageName, sessions);
            }
            sessions.add(session);
            invalidateAllExecutionStatsLocked(userId, packageName);
            maybeScheduleCleanupAlarmLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class EarliestEndTimeFunctor implements Consumer<List<TimingSession>> {
        public long earliestEndElapsed;

        private EarliestEndTimeFunctor() {
            this.earliestEndElapsed = JobStatus.NO_LATEST_RUNTIME;
        }

        @Override // java.util.function.Consumer
        public void accept(List<TimingSession> sessions) {
            if (sessions != null && sessions.size() > 0) {
                this.earliestEndElapsed = Math.min(this.earliestEndElapsed, sessions.get(0).endTimeElapsed);
            }
        }

        void reset() {
            this.earliestEndElapsed = JobStatus.NO_LATEST_RUNTIME;
        }
    }

    @VisibleForTesting
    void maybeScheduleCleanupAlarmLocked() {
        if (this.mNextCleanupTimeElapsed > JobSchedulerService.sElapsedRealtimeClock.millis()) {
            if (DEBUG) {
                Slog.v(TAG, "Not scheduling cleanup since there's already one at " + this.mNextCleanupTimeElapsed + " (in " + (this.mNextCleanupTimeElapsed - JobSchedulerService.sElapsedRealtimeClock.millis()) + "ms)");
                return;
            }
            return;
        }
        this.mEarliestEndTimeFunctor.reset();
        this.mTimingSessions.forEach(this.mEarliestEndTimeFunctor);
        long earliestEndElapsed = this.mEarliestEndTimeFunctor.earliestEndElapsed;
        if (earliestEndElapsed == JobStatus.NO_LATEST_RUNTIME) {
            if (DEBUG) {
                Slog.d(TAG, "Didn't find a time to schedule cleanup");
                return;
            }
            return;
        }
        long nextCleanupElapsed = 86400000 + earliestEndElapsed;
        if (nextCleanupElapsed - this.mNextCleanupTimeElapsed <= 600000) {
            nextCleanupElapsed += 600000;
        }
        this.mNextCleanupTimeElapsed = nextCleanupElapsed;
        this.mAlarmManager.set(3, nextCleanupElapsed, ALARM_TAG_CLEANUP, this.mSessionCleanupAlarmListener, this.mHandler);
        if (DEBUG) {
            Slog.d(TAG, "Scheduled next cleanup for " + this.mNextCleanupTimeElapsed);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNewChargingStateLocked() {
        final long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
        final boolean isCharging = this.mChargeTracker.isCharging();
        if (DEBUG) {
            Slog.d(TAG, "handleNewChargingStateLocked: " + isCharging);
        }
        this.mPkgTimers.forEach(new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$DLtQo5Uin5fgikFII8lOB91DOkc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((QuotaController.Timer) obj).onStateChangedLocked(nowElapsed, isCharging);
            }
        });
        maybeUpdateAllConstraintsLocked();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeUpdateAllConstraintsLocked() {
        boolean changed = false;
        for (int u = 0; u < this.mTrackedJobs.numUsers(); u++) {
            int userId = this.mTrackedJobs.keyAt(u);
            for (int p = 0; p < this.mTrackedJobs.numPackagesForUser(userId); p++) {
                String packageName = this.mTrackedJobs.keyAt(u, p);
                changed |= maybeUpdateConstraintForPkgLocked(userId, packageName);
            }
        }
        if (changed) {
            this.mStateChangedListener.onControllerStateChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean maybeUpdateConstraintForPkgLocked(int userId, String packageName) {
        boolean constraintSatisfied;
        ArraySet<JobStatus> jobs = this.mTrackedJobs.get(userId, packageName);
        if (jobs == null || jobs.size() == 0) {
            return false;
        }
        int realStandbyBucket = jobs.valueAt(0).getStandbyBucket();
        boolean realInQuota = isWithinQuotaLocked(userId, packageName, realStandbyBucket);
        boolean changed = false;
        for (int i = jobs.size() - 1; i >= 0; i--) {
            JobStatus js = jobs.valueAt(i);
            if (isTopStartedJobLocked(js)) {
                constraintSatisfied = js.setQuotaConstraintSatisfied(true);
            } else if (realStandbyBucket != 0 && realStandbyBucket == getEffectiveStandbyBucket(js)) {
                constraintSatisfied = setConstraintSatisfied(js, realInQuota);
            } else {
                constraintSatisfied = setConstraintSatisfied(js, isWithinQuotaLocked(js));
            }
            changed |= constraintSatisfied;
        }
        if (!realInQuota) {
            maybeScheduleStartAlarmLocked(userId, packageName, realStandbyBucket);
        } else {
            QcAlarmListener alarmListener = this.mInQuotaAlarmListeners.get(userId, packageName);
            if (alarmListener != null && alarmListener.isWaiting()) {
                this.mAlarmManager.cancel(alarmListener);
                alarmListener.setTriggerTime(0L);
            }
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UidConstraintUpdater implements Consumer<JobStatus> {
        private final UserPackageMap<Integer> mToScheduleStartAlarms;
        public boolean wasJobChanged;

        private UidConstraintUpdater() {
            this.mToScheduleStartAlarms = new UserPackageMap<>();
        }

        @Override // java.util.function.Consumer
        public void accept(JobStatus jobStatus) {
            boolean z = this.wasJobChanged;
            QuotaController quotaController = QuotaController.this;
            this.wasJobChanged = z | quotaController.setConstraintSatisfied(jobStatus, quotaController.isWithinQuotaLocked(jobStatus));
            int userId = jobStatus.getSourceUserId();
            String packageName = jobStatus.getSourcePackageName();
            int realStandbyBucket = jobStatus.getStandbyBucket();
            if (QuotaController.this.isWithinQuotaLocked(userId, packageName, realStandbyBucket)) {
                QcAlarmListener alarmListener = (QcAlarmListener) QuotaController.this.mInQuotaAlarmListeners.get(userId, packageName);
                if (alarmListener != null && alarmListener.isWaiting()) {
                    QuotaController.this.mAlarmManager.cancel(alarmListener);
                    alarmListener.setTriggerTime(0L);
                    return;
                }
                return;
            }
            this.mToScheduleStartAlarms.add(userId, packageName, Integer.valueOf(realStandbyBucket));
        }

        void postProcess() {
            for (int u = 0; u < this.mToScheduleStartAlarms.numUsers(); u++) {
                int userId = this.mToScheduleStartAlarms.keyAt(u);
                for (int p = 0; p < this.mToScheduleStartAlarms.numPackagesForUser(userId); p++) {
                    String packageName = this.mToScheduleStartAlarms.keyAt(u, p);
                    int standbyBucket = this.mToScheduleStartAlarms.get(userId, packageName).intValue();
                    QuotaController.this.maybeScheduleStartAlarmLocked(userId, packageName, standbyBucket);
                }
            }
        }

        void reset() {
            this.wasJobChanged = false;
            this.mToScheduleStartAlarms.clear();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean maybeUpdateConstraintForUidLocked(int uid) {
        this.mService.getJobStore().forEachJobForSourceUid(uid, this.mUpdateUidConstraints);
        this.mUpdateUidConstraints.postProcess();
        boolean changed = this.mUpdateUidConstraints.wasJobChanged;
        this.mUpdateUidConstraints.reset();
        return changed;
    }

    @VisibleForTesting
    void maybeScheduleStartAlarmLocked(int userId, String packageName, int standbyBucket) {
        long inQuotaTimeElapsed;
        if (standbyBucket == 4) {
            return;
        }
        String pkgString = string(userId, packageName);
        ExecutionStats stats = getExecutionStatsLocked(userId, packageName, standbyBucket);
        boolean isUnderJobCountQuota = isUnderJobCountQuotaLocked(stats, standbyBucket);
        boolean isUnderTimingSessionCountQuota = isUnderSessionCountQuotaLocked(stats, standbyBucket);
        QcAlarmListener alarmListener = this.mInQuotaAlarmListeners.get(userId, packageName);
        if (stats.executionTimeInWindowMs < this.mAllowedTimePerPeriodMs && stats.executionTimeInMaxPeriodMs < this.mMaxExecutionTimeMs && isUnderJobCountQuota && isUnderTimingSessionCountQuota) {
            if (DEBUG) {
                Slog.e(TAG, "maybeScheduleStartAlarmLocked called for " + pkgString + " even though it already has " + getRemainingExecutionTimeLocked(userId, packageName, standbyBucket) + "ms in its quota.");
            }
            if (alarmListener != null) {
                this.mAlarmManager.cancel(alarmListener);
                alarmListener.setTriggerTime(0L);
            }
            this.mHandler.obtainMessage(2, userId, 0, packageName).sendToTarget();
            return;
        }
        if (alarmListener == null) {
            alarmListener = new QcAlarmListener(userId, packageName);
            this.mInQuotaAlarmListeners.add(userId, packageName, alarmListener);
        }
        long inQuotaTimeElapsed2 = stats.inQuotaTimeElapsed;
        if (!isUnderJobCountQuota && stats.bgJobCountInWindow < stats.jobCountLimit) {
            inQuotaTimeElapsed2 = Math.max(inQuotaTimeElapsed2, stats.jobRateLimitExpirationTimeElapsed);
        }
        if (!isUnderTimingSessionCountQuota && stats.sessionCountInWindow < stats.sessionCountLimit) {
            inQuotaTimeElapsed = Math.max(inQuotaTimeElapsed2, stats.sessionRateLimitExpirationTimeElapsed);
        } else {
            inQuotaTimeElapsed = inQuotaTimeElapsed2;
        }
        if (!alarmListener.isWaiting() || inQuotaTimeElapsed < alarmListener.getTriggerTimeElapsed() - 180000 || alarmListener.getTriggerTimeElapsed() < inQuotaTimeElapsed) {
            if (DEBUG) {
                Slog.d(TAG, "Scheduling start alarm for " + pkgString);
            }
            this.mAlarmManager.set(3, inQuotaTimeElapsed, ALARM_TAG_QUOTA_CHECK, alarmListener, this.mHandler);
            alarmListener.setTriggerTime(inQuotaTimeElapsed);
        } else if (DEBUG) {
            Slog.d(TAG, "No need to schedule start alarm for " + pkgString);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean setConstraintSatisfied(JobStatus jobStatus, boolean isWithinQuota) {
        if (!isWithinQuota && jobStatus.getWhenStandbyDeferred() == 0) {
            jobStatus.setWhenStandbyDeferred(JobSchedulerService.sElapsedRealtimeClock.millis());
        }
        return jobStatus.setQuotaConstraintSatisfied(isWithinQuota);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ChargingTracker extends BroadcastReceiver {
        private boolean mCharging;

        ChargingTracker() {
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.os.action.CHARGING");
            filter.addAction("android.os.action.DISCHARGING");
            QuotaController.this.mContext.registerReceiver(this, filter);
            BatteryManagerInternal batteryManagerInternal = (BatteryManagerInternal) LocalServices.getService(BatteryManagerInternal.class);
            this.mCharging = batteryManagerInternal.isPowered(7);
        }

        public boolean isCharging() {
            return this.mCharging;
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            synchronized (QuotaController.this.mLock) {
                String action = intent.getAction();
                if ("android.os.action.CHARGING".equals(action)) {
                    if (QuotaController.DEBUG) {
                        Slog.d(QuotaController.TAG, "Received charging intent, fired @ " + JobSchedulerService.sElapsedRealtimeClock.millis());
                    }
                    this.mCharging = true;
                    QuotaController.this.handleNewChargingStateLocked();
                } else if ("android.os.action.DISCHARGING".equals(action)) {
                    if (QuotaController.DEBUG) {
                        Slog.d(QuotaController.TAG, "Disconnected from power.");
                    }
                    this.mCharging = false;
                    QuotaController.this.handleNewChargingStateLocked();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static final class TimingSession {
        public final int bgJobCount;
        public final long endTimeElapsed;
        private final int mHashCode;
        public final long startTimeElapsed;

        TimingSession(long startElapsed, long endElapsed, int bgJobCount) {
            this.startTimeElapsed = startElapsed;
            this.endTimeElapsed = endElapsed;
            this.bgJobCount = bgJobCount;
            int hashCode = (0 * 31) + QuotaController.hashLong(this.startTimeElapsed);
            this.mHashCode = (((hashCode * 31) + QuotaController.hashLong(this.endTimeElapsed)) * 31) + bgJobCount;
        }

        public String toString() {
            return "TimingSession{" + this.startTimeElapsed + "->" + this.endTimeElapsed + ", " + this.bgJobCount + "}";
        }

        public boolean equals(Object obj) {
            if (obj instanceof TimingSession) {
                TimingSession other = (TimingSession) obj;
                return this.startTimeElapsed == other.startTimeElapsed && this.endTimeElapsed == other.endTimeElapsed && this.bgJobCount == other.bgJobCount;
            }
            return false;
        }

        public int hashCode() {
            return this.mHashCode;
        }

        public void dump(IndentingPrintWriter pw) {
            pw.print(this.startTimeElapsed);
            pw.print(" -> ");
            pw.print(this.endTimeElapsed);
            pw.print(" (");
            pw.print(this.endTimeElapsed - this.startTimeElapsed);
            pw.print("), ");
            pw.print(this.bgJobCount);
            pw.print(" bg jobs.");
            pw.println();
        }

        public void dump(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1112396529665L, this.startTimeElapsed);
            proto.write(1112396529666L, this.endTimeElapsed);
            proto.write(1120986464259L, this.bgJobCount);
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Timer {
        private int mBgJobCount;
        private final Package mPkg;
        private final ArraySet<JobStatus> mRunningBgJobs = new ArraySet<>();
        private long mStartTimeElapsed;
        private final int mUid;

        Timer(int uid, int userId, String packageName) {
            this.mPkg = new Package(userId, packageName);
            this.mUid = uid;
        }

        void startTrackingJobLocked(JobStatus jobStatus) {
            if (QuotaController.this.isTopStartedJobLocked(jobStatus)) {
                if (QuotaController.DEBUG) {
                    Slog.v(QuotaController.TAG, "Timer ignoring " + jobStatus.toShortString() + " because isTop");
                    return;
                }
                return;
            }
            if (QuotaController.DEBUG) {
                Slog.v(QuotaController.TAG, "Starting to track " + jobStatus.toShortString());
            }
            this.mRunningBgJobs.add(jobStatus);
            if (shouldTrackLocked()) {
                this.mBgJobCount++;
                QuotaController.this.incrementJobCount(this.mPkg.userId, this.mPkg.packageName, 1);
                if (this.mRunningBgJobs.size() == 1) {
                    this.mStartTimeElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
                    QuotaController.this.invalidateAllExecutionStatsLocked(this.mPkg.userId, this.mPkg.packageName);
                    scheduleCutoff();
                }
            }
        }

        void stopTrackingJob(JobStatus jobStatus) {
            if (QuotaController.DEBUG) {
                Slog.v(QuotaController.TAG, "Stopping tracking of " + jobStatus.toShortString());
            }
            synchronized (QuotaController.this.mLock) {
                if (this.mRunningBgJobs.size() == 0) {
                    if (QuotaController.DEBUG) {
                        Slog.d(QuotaController.TAG, "Timer isn't tracking any jobs but still told to stop");
                    }
                    return;
                }
                if (this.mRunningBgJobs.remove(jobStatus) && !QuotaController.this.mChargeTracker.isCharging() && this.mRunningBgJobs.size() == 0) {
                    emitSessionLocked(JobSchedulerService.sElapsedRealtimeClock.millis());
                    cancelCutoff();
                }
            }
        }

        void dropEverythingLocked() {
            this.mRunningBgJobs.clear();
            cancelCutoff();
        }

        private void emitSessionLocked(long nowElapsed) {
            int i = this.mBgJobCount;
            if (i <= 0) {
                return;
            }
            TimingSession ts = new TimingSession(this.mStartTimeElapsed, nowElapsed, i);
            QuotaController.this.saveTimingSession(this.mPkg.userId, this.mPkg.packageName, ts);
            this.mBgJobCount = 0;
            cancelCutoff();
            QuotaController.this.incrementTimingSessionCount(this.mPkg.userId, this.mPkg.packageName);
        }

        public boolean isActive() {
            boolean z;
            synchronized (QuotaController.this.mLock) {
                z = this.mBgJobCount > 0;
            }
            return z;
        }

        boolean isRunning(JobStatus jobStatus) {
            return this.mRunningBgJobs.contains(jobStatus);
        }

        long getCurrentDuration(long nowElapsed) {
            long j;
            synchronized (QuotaController.this.mLock) {
                j = !isActive() ? 0L : nowElapsed - this.mStartTimeElapsed;
            }
            return j;
        }

        int getBgJobCount() {
            int i;
            synchronized (QuotaController.this.mLock) {
                i = this.mBgJobCount;
            }
            return i;
        }

        private boolean shouldTrackLocked() {
            return (QuotaController.this.mChargeTracker.isCharging() || QuotaController.this.mForegroundUids.get(this.mUid)) ? false : true;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void onStateChangedLocked(long nowElapsed, boolean isQuotaFree) {
            if (isQuotaFree) {
                emitSessionLocked(nowElapsed);
            } else if (!isActive() && shouldTrackLocked() && this.mRunningBgJobs.size() > 0) {
                this.mStartTimeElapsed = nowElapsed;
                this.mBgJobCount = this.mRunningBgJobs.size();
                QuotaController.this.incrementJobCount(this.mPkg.userId, this.mPkg.packageName, this.mBgJobCount);
                QuotaController.this.invalidateAllExecutionStatsLocked(this.mPkg.userId, this.mPkg.packageName);
                scheduleCutoff();
            }
        }

        void rescheduleCutoff() {
            cancelCutoff();
            scheduleCutoff();
        }

        private void scheduleCutoff() {
            synchronized (QuotaController.this.mLock) {
                if (isActive()) {
                    Message msg = QuotaController.this.mHandler.obtainMessage(0, this.mPkg);
                    long timeRemainingMs = QuotaController.this.getTimeUntilQuotaConsumedLocked(this.mPkg.userId, this.mPkg.packageName);
                    if (QuotaController.DEBUG) {
                        Slog.i(QuotaController.TAG, "Job for " + this.mPkg + " has " + timeRemainingMs + "ms left.");
                    }
                    QuotaController.this.mHandler.sendMessageDelayed(msg, timeRemainingMs);
                }
            }
        }

        private void cancelCutoff() {
            QuotaController.this.mHandler.removeMessages(0, this.mPkg);
        }

        public void dump(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
            pw.print("Timer{");
            pw.print(this.mPkg);
            pw.print("} ");
            if (isActive()) {
                pw.print("started at ");
                pw.print(this.mStartTimeElapsed);
                pw.print(" (");
                pw.print(JobSchedulerService.sElapsedRealtimeClock.millis() - this.mStartTimeElapsed);
                pw.print("ms ago)");
            } else {
                pw.print("NOT active");
            }
            pw.print(", ");
            pw.print(this.mBgJobCount);
            pw.print(" running bg jobs");
            pw.println();
            pw.increaseIndent();
            for (int i = 0; i < this.mRunningBgJobs.size(); i++) {
                JobStatus js = this.mRunningBgJobs.valueAt(i);
                if (predicate.test(js)) {
                    pw.println(js.toShortString());
                }
            }
            pw.decreaseIndent();
        }

        public void dump(ProtoOutputStream proto, long fieldId, Predicate<JobStatus> predicate) {
            long token = proto.start(fieldId);
            this.mPkg.writeToProto(proto, 1146756268033L);
            proto.write(1133871366146L, isActive());
            proto.write(1112396529667L, this.mStartTimeElapsed);
            proto.write(1120986464260L, this.mBgJobCount);
            for (int i = 0; i < this.mRunningBgJobs.size(); i++) {
                JobStatus js = this.mRunningBgJobs.valueAt(i);
                if (predicate.test(js)) {
                    js.writeToShortProto(proto, 2246267895813L);
                }
            }
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class StandbyTracker extends UsageStatsManagerInternal.AppIdleStateChangeListener {
        StandbyTracker() {
        }

        public void onAppIdleStateChanged(final String packageName, final int userId, boolean idle, final int bucket, int reason) {
            BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$StandbyTracker$UNCXPiY4xGPFhTnC-LuVzvqiAl4
                @Override // java.lang.Runnable
                public final void run() {
                    QuotaController.StandbyTracker.this.lambda$onAppIdleStateChanged$0$QuotaController$StandbyTracker(bucket, userId, packageName);
                }
            });
        }

        public /* synthetic */ void lambda$onAppIdleStateChanged$0$QuotaController$StandbyTracker(int bucket, int userId, String packageName) {
            int bucketIndex = JobSchedulerService.standbyBucketToBucketIndex(bucket);
            if (QuotaController.DEBUG) {
                Slog.i(QuotaController.TAG, "Moving pkg " + QuotaController.string(userId, packageName) + " to bucketIndex " + bucketIndex);
            }
            synchronized (QuotaController.this.mLock) {
                ArraySet<JobStatus> jobs = (ArraySet) QuotaController.this.mTrackedJobs.get(userId, packageName);
                if (jobs != null && jobs.size() != 0) {
                    for (int i = jobs.size() - 1; i >= 0; i--) {
                        JobStatus js = jobs.valueAt(i);
                        js.setStandbyBucket(bucketIndex);
                    }
                    Timer timer = (Timer) QuotaController.this.mPkgTimers.get(userId, packageName);
                    if (timer != null && timer.isActive()) {
                        timer.rescheduleCutoff();
                    }
                    if (!QuotaController.this.mShouldThrottle || QuotaController.this.maybeUpdateConstraintForPkgLocked(userId, packageName)) {
                        QuotaController.this.mStateChangedListener.onControllerStateChanged();
                    }
                }
            }
        }

        public void onParoleStateChanged(boolean isParoleOn) {
            QuotaController.this.mInParole = isParoleOn;
            if (QuotaController.DEBUG) {
                StringBuilder sb = new StringBuilder();
                sb.append("Global parole state now ");
                sb.append(isParoleOn ? "ON" : "OFF");
                Slog.i(QuotaController.TAG, sb.toString());
            }
            BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$StandbyTracker$HBosnPX15xU_maD6xbBsC7aJqOU
                @Override // java.lang.Runnable
                public final void run() {
                    QuotaController.StandbyTracker.this.lambda$onParoleStateChanged$1$QuotaController$StandbyTracker();
                }
            });
        }

        public /* synthetic */ void lambda$onParoleStateChanged$1$QuotaController$StandbyTracker() {
            synchronized (QuotaController.this.mLock) {
                QuotaController.this.maybeUpdateAllConstraintsLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DeleteTimingSessionsFunctor implements Consumer<List<TimingSession>> {
        private final Predicate<TimingSession> mTooOld;

        private DeleteTimingSessionsFunctor() {
            this.mTooOld = new Predicate<TimingSession>() { // from class: com.android.server.job.controllers.QuotaController.DeleteTimingSessionsFunctor.1
                @Override // java.util.function.Predicate
                public boolean test(TimingSession ts) {
                    return ts.endTimeElapsed <= JobSchedulerService.sElapsedRealtimeClock.millis() - 86400000;
                }
            };
        }

        @Override // java.util.function.Consumer
        public void accept(List<TimingSession> sessions) {
            if (sessions != null) {
                sessions.removeIf(this.mTooOld);
            }
        }
    }

    @VisibleForTesting
    void deleteObsoleteSessionsLocked() {
        this.mTimingSessions.forEach(this.mDeleteOldSessionsFunctor);
    }

    /* loaded from: classes.dex */
    private class QcHandler extends Handler {
        QcHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            boolean isQuotaFree;
            synchronized (QuotaController.this.mLock) {
                int i = msg.what;
                if (i == 0) {
                    Package pkg = (Package) msg.obj;
                    if (QuotaController.DEBUG) {
                        Slog.d(QuotaController.TAG, "Checking if " + pkg + " has reached its quota.");
                    }
                    if (QuotaController.this.getRemainingExecutionTimeLocked(pkg.userId, pkg.packageName) <= 50) {
                        if (QuotaController.DEBUG) {
                            Slog.d(QuotaController.TAG, pkg + " has reached its quota.");
                        }
                        if (QuotaController.this.maybeUpdateConstraintForPkgLocked(pkg.userId, pkg.packageName)) {
                            QuotaController.this.mStateChangedListener.onControllerStateChanged();
                        }
                    } else {
                        Message rescheduleMsg = obtainMessage(0, pkg);
                        long timeRemainingMs = QuotaController.this.getTimeUntilQuotaConsumedLocked(pkg.userId, pkg.packageName);
                        if (QuotaController.DEBUG) {
                            Slog.d(QuotaController.TAG, pkg + " has " + timeRemainingMs + "ms left.");
                        }
                        sendMessageDelayed(rescheduleMsg, timeRemainingMs);
                    }
                } else if (i == 1) {
                    if (QuotaController.DEBUG) {
                        Slog.d(QuotaController.TAG, "Cleaning up timing sessions.");
                    }
                    QuotaController.this.deleteObsoleteSessionsLocked();
                    QuotaController.this.maybeScheduleCleanupAlarmLocked();
                } else if (i == 2) {
                    String packageName = (String) msg.obj;
                    int userId = msg.arg1;
                    if (QuotaController.DEBUG) {
                        Slog.d(QuotaController.TAG, "Checking pkg " + QuotaController.string(userId, packageName));
                    }
                    if (QuotaController.this.maybeUpdateConstraintForPkgLocked(userId, packageName)) {
                        QuotaController.this.mStateChangedListener.onControllerStateChanged();
                    }
                } else if (i == 3) {
                    int uid = msg.arg1;
                    int procState = msg.arg2;
                    int userId2 = UserHandle.getUserId(uid);
                    long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
                    synchronized (QuotaController.this.mLock) {
                        if (procState <= 5) {
                            QuotaController.this.mForegroundUids.put(uid, true);
                            isQuotaFree = true;
                        } else {
                            QuotaController.this.mForegroundUids.delete(uid);
                            isQuotaFree = false;
                        }
                        if (QuotaController.this.mPkgTimers.indexOfKey(userId2) >= 0) {
                            ArraySet<String> packages = QuotaController.this.mUidToPackageCache.get(uid);
                            if (packages == null) {
                                try {
                                    String[] pkgs = AppGlobals.getPackageManager().getPackagesForUid(uid);
                                    if (pkgs != null) {
                                        for (String pkg2 : pkgs) {
                                            QuotaController.this.mUidToPackageCache.add(uid, pkg2);
                                        }
                                        packages = QuotaController.this.mUidToPackageCache.get(uid);
                                    }
                                } catch (RemoteException e) {
                                    Slog.wtf(QuotaController.TAG, "Failed to get package list", e);
                                }
                            }
                            if (packages != null) {
                                for (int i2 = packages.size() - 1; i2 >= 0; i2--) {
                                    Timer t = (Timer) QuotaController.this.mPkgTimers.get(userId2, packages.valueAt(i2));
                                    if (t != null) {
                                        t.onStateChangedLocked(nowElapsed, isQuotaFree);
                                    }
                                }
                            }
                        }
                        if (QuotaController.this.maybeUpdateConstraintForUidLocked(uid)) {
                            QuotaController.this.mStateChangedListener.onControllerStateChanged();
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class QcAlarmListener implements AlarmManager.OnAlarmListener {
        private final String mPackageName;
        private volatile long mTriggerTimeElapsed;
        private final int mUserId;

        QcAlarmListener(int userId, String packageName) {
            this.mUserId = userId;
            this.mPackageName = packageName;
        }

        boolean isWaiting() {
            return this.mTriggerTimeElapsed > 0;
        }

        void setTriggerTime(long timeElapsed) {
            this.mTriggerTimeElapsed = timeElapsed;
        }

        long getTriggerTimeElapsed() {
            return this.mTriggerTimeElapsed;
        }

        @Override // android.app.AlarmManager.OnAlarmListener
        public void onAlarm() {
            QuotaController.this.mHandler.obtainMessage(2, this.mUserId, 0, this.mPackageName).sendToTarget();
            this.mTriggerTimeElapsed = 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public class QcConstants extends ContentObserver {
        private static final long DEFAULT_ALLOWED_TIME_PER_PERIOD_MS = 600000;
        private static final long DEFAULT_IN_QUOTA_BUFFER_MS = 30000;
        private static final long DEFAULT_MAX_EXECUTION_TIME_MS = 14400000;
        private static final int DEFAULT_MAX_JOB_COUNT_ACTIVE = 75;
        private static final int DEFAULT_MAX_JOB_COUNT_FREQUENT = 200;
        private static final int DEFAULT_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW = 20;
        private static final int DEFAULT_MAX_JOB_COUNT_RARE = 48;
        private static final int DEFAULT_MAX_JOB_COUNT_WORKING = 120;
        private static final int DEFAULT_MAX_SESSION_COUNT_ACTIVE = 75;
        private static final int DEFAULT_MAX_SESSION_COUNT_FREQUENT = 8;
        private static final int DEFAULT_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW = 20;
        private static final int DEFAULT_MAX_SESSION_COUNT_RARE = 3;
        private static final int DEFAULT_MAX_SESSION_COUNT_WORKING = 10;
        private static final long DEFAULT_RATE_LIMITING_WINDOW_MS = 60000;
        private static final long DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS = 5000;
        private static final long DEFAULT_WINDOW_SIZE_ACTIVE_MS = 600000;
        private static final long DEFAULT_WINDOW_SIZE_FREQUENT_MS = 28800000;
        private static final long DEFAULT_WINDOW_SIZE_RARE_MS = 86400000;
        private static final long DEFAULT_WINDOW_SIZE_WORKING_MS = 7200000;
        private static final String KEY_ALLOWED_TIME_PER_PERIOD_MS = "allowed_time_per_period_ms";
        private static final String KEY_IN_QUOTA_BUFFER_MS = "in_quota_buffer_ms";
        private static final String KEY_MAX_EXECUTION_TIME_MS = "max_execution_time_ms";
        private static final String KEY_MAX_JOB_COUNT_ACTIVE = "max_job_count_active";
        private static final String KEY_MAX_JOB_COUNT_FREQUENT = "max_job_count_frequent";
        private static final String KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW = "max_job_count_per_rate_limiting_window";
        private static final String KEY_MAX_JOB_COUNT_RARE = "max_job_count_rare";
        private static final String KEY_MAX_JOB_COUNT_WORKING = "max_job_count_working";
        private static final String KEY_MAX_SESSION_COUNT_ACTIVE = "max_session_count_active";
        private static final String KEY_MAX_SESSION_COUNT_FREQUENT = "max_session_count_frequent";
        private static final String KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW = "max_session_count_per_rate_limiting_window";
        private static final String KEY_MAX_SESSION_COUNT_RARE = "max_session_count_rare";
        private static final String KEY_MAX_SESSION_COUNT_WORKING = "max_session_count_working";
        private static final String KEY_RATE_LIMITING_WINDOW_MS = "rate_limiting_window_ms";
        private static final String KEY_TIMING_SESSION_COALESCING_DURATION_MS = "timing_session_coalescing_duration_ms";
        private static final String KEY_WINDOW_SIZE_ACTIVE_MS = "window_size_active_ms";
        private static final String KEY_WINDOW_SIZE_FREQUENT_MS = "window_size_frequent_ms";
        private static final String KEY_WINDOW_SIZE_RARE_MS = "window_size_rare_ms";
        private static final String KEY_WINDOW_SIZE_WORKING_MS = "window_size_working_ms";
        private static final int MIN_BUCKET_JOB_COUNT = 10;
        private static final int MIN_BUCKET_SESSION_COUNT = 1;
        private static final long MIN_MAX_EXECUTION_TIME_MS = 3600000;
        private static final int MIN_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW = 10;
        private static final int MIN_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW = 10;
        private static final long MIN_RATE_LIMITING_WINDOW_MS = 30000;
        public long ALLOWED_TIME_PER_PERIOD_MS;
        public long IN_QUOTA_BUFFER_MS;
        public long MAX_EXECUTION_TIME_MS;
        public int MAX_JOB_COUNT_ACTIVE;
        public int MAX_JOB_COUNT_FREQUENT;
        public int MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW;
        public int MAX_JOB_COUNT_RARE;
        public int MAX_JOB_COUNT_WORKING;
        public int MAX_SESSION_COUNT_ACTIVE;
        public int MAX_SESSION_COUNT_FREQUENT;
        public int MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW;
        public int MAX_SESSION_COUNT_RARE;
        public int MAX_SESSION_COUNT_WORKING;
        public long RATE_LIMITING_WINDOW_MS;
        public long TIMING_SESSION_COALESCING_DURATION_MS;
        public long WINDOW_SIZE_ACTIVE_MS;
        public long WINDOW_SIZE_FREQUENT_MS;
        public long WINDOW_SIZE_RARE_MS;
        public long WINDOW_SIZE_WORKING_MS;
        private final KeyValueListParser mParser;
        private ContentResolver mResolver;

        QcConstants(Handler handler) {
            super(handler);
            this.mParser = new KeyValueListParser(',');
            this.ALLOWED_TIME_PER_PERIOD_MS = 600000L;
            this.IN_QUOTA_BUFFER_MS = 30000L;
            this.WINDOW_SIZE_ACTIVE_MS = 600000L;
            this.WINDOW_SIZE_WORKING_MS = 7200000L;
            this.WINDOW_SIZE_FREQUENT_MS = DEFAULT_WINDOW_SIZE_FREQUENT_MS;
            this.WINDOW_SIZE_RARE_MS = 86400000L;
            this.MAX_EXECUTION_TIME_MS = 14400000L;
            this.MAX_JOB_COUNT_ACTIVE = 75;
            this.MAX_JOB_COUNT_WORKING = DEFAULT_MAX_JOB_COUNT_WORKING;
            this.MAX_JOB_COUNT_FREQUENT = 200;
            this.MAX_JOB_COUNT_RARE = 48;
            this.RATE_LIMITING_WINDOW_MS = 60000L;
            this.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW = 20;
            this.MAX_SESSION_COUNT_ACTIVE = 75;
            this.MAX_SESSION_COUNT_WORKING = 10;
            this.MAX_SESSION_COUNT_FREQUENT = 8;
            this.MAX_SESSION_COUNT_RARE = 3;
            this.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW = 20;
            this.TIMING_SESSION_COALESCING_DURATION_MS = DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void start(ContentResolver resolver) {
            this.mResolver = resolver;
            this.mResolver.registerContentObserver(Settings.Global.getUriFor("job_scheduler_quota_controller_constants"), false, this);
            onChange(true, null);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            String constants = Settings.Global.getString(this.mResolver, "job_scheduler_quota_controller_constants");
            try {
                this.mParser.setString(constants);
            } catch (Exception e) {
                Slog.e(QuotaController.TAG, "Bad jobscheduler quota controller settings", e);
            }
            this.ALLOWED_TIME_PER_PERIOD_MS = this.mParser.getDurationMillis(KEY_ALLOWED_TIME_PER_PERIOD_MS, 600000L);
            this.IN_QUOTA_BUFFER_MS = this.mParser.getDurationMillis(KEY_IN_QUOTA_BUFFER_MS, 30000L);
            this.WINDOW_SIZE_ACTIVE_MS = this.mParser.getDurationMillis(KEY_WINDOW_SIZE_ACTIVE_MS, 600000L);
            this.WINDOW_SIZE_WORKING_MS = this.mParser.getDurationMillis(KEY_WINDOW_SIZE_WORKING_MS, 7200000L);
            this.WINDOW_SIZE_FREQUENT_MS = this.mParser.getDurationMillis(KEY_WINDOW_SIZE_FREQUENT_MS, (long) DEFAULT_WINDOW_SIZE_FREQUENT_MS);
            this.WINDOW_SIZE_RARE_MS = this.mParser.getDurationMillis(KEY_WINDOW_SIZE_RARE_MS, 86400000L);
            this.MAX_EXECUTION_TIME_MS = this.mParser.getDurationMillis(KEY_MAX_EXECUTION_TIME_MS, 14400000L);
            this.MAX_JOB_COUNT_ACTIVE = this.mParser.getInt(KEY_MAX_JOB_COUNT_ACTIVE, 75);
            this.MAX_JOB_COUNT_WORKING = this.mParser.getInt(KEY_MAX_JOB_COUNT_WORKING, (int) DEFAULT_MAX_JOB_COUNT_WORKING);
            this.MAX_JOB_COUNT_FREQUENT = this.mParser.getInt(KEY_MAX_JOB_COUNT_FREQUENT, 200);
            this.MAX_JOB_COUNT_RARE = this.mParser.getInt(KEY_MAX_JOB_COUNT_RARE, 48);
            this.RATE_LIMITING_WINDOW_MS = this.mParser.getLong(KEY_RATE_LIMITING_WINDOW_MS, 60000L);
            this.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW = this.mParser.getInt(KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW, 20);
            this.MAX_SESSION_COUNT_ACTIVE = this.mParser.getInt(KEY_MAX_SESSION_COUNT_ACTIVE, 75);
            this.MAX_SESSION_COUNT_WORKING = this.mParser.getInt(KEY_MAX_SESSION_COUNT_WORKING, 10);
            this.MAX_SESSION_COUNT_FREQUENT = this.mParser.getInt(KEY_MAX_SESSION_COUNT_FREQUENT, 8);
            this.MAX_SESSION_COUNT_RARE = this.mParser.getInt(KEY_MAX_SESSION_COUNT_RARE, 3);
            this.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW = this.mParser.getInt(KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW, 20);
            this.TIMING_SESSION_COALESCING_DURATION_MS = this.mParser.getLong(KEY_TIMING_SESSION_COALESCING_DURATION_MS, (long) DEFAULT_TIMING_SESSION_COALESCING_DURATION_MS);
            updateConstants();
        }

        @VisibleForTesting
        void updateConstants() {
            synchronized (QuotaController.this.mLock) {
                boolean changed = false;
                long newMaxExecutionTimeMs = Math.max(3600000L, Math.min(86400000L, this.MAX_EXECUTION_TIME_MS));
                if (QuotaController.this.mMaxExecutionTimeMs != newMaxExecutionTimeMs) {
                    QuotaController.this.mMaxExecutionTimeMs = newMaxExecutionTimeMs;
                    QuotaController.this.mMaxExecutionTimeIntoQuotaMs = QuotaController.this.mMaxExecutionTimeMs - QuotaController.this.mQuotaBufferMs;
                    changed = true;
                }
                long newAllowedTimeMs = Math.min(QuotaController.this.mMaxExecutionTimeMs, Math.max(60000L, this.ALLOWED_TIME_PER_PERIOD_MS));
                if (QuotaController.this.mAllowedTimePerPeriodMs != newAllowedTimeMs) {
                    QuotaController.this.mAllowedTimePerPeriodMs = newAllowedTimeMs;
                    QuotaController.this.mAllowedTimeIntoQuotaMs = QuotaController.this.mAllowedTimePerPeriodMs - QuotaController.this.mQuotaBufferMs;
                    changed = true;
                }
                long newQuotaBufferMs = Math.max(0L, Math.min((long) BackupAgentTimeoutParameters.DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS, this.IN_QUOTA_BUFFER_MS));
                if (QuotaController.this.mQuotaBufferMs != newQuotaBufferMs) {
                    QuotaController.this.mQuotaBufferMs = newQuotaBufferMs;
                    QuotaController.this.mAllowedTimeIntoQuotaMs = QuotaController.this.mAllowedTimePerPeriodMs - QuotaController.this.mQuotaBufferMs;
                    QuotaController.this.mMaxExecutionTimeIntoQuotaMs = QuotaController.this.mMaxExecutionTimeMs - QuotaController.this.mQuotaBufferMs;
                    changed = true;
                }
                long newActivePeriodMs = Math.max(QuotaController.this.mAllowedTimePerPeriodMs, Math.min(86400000L, this.WINDOW_SIZE_ACTIVE_MS));
                if (QuotaController.this.mBucketPeriodsMs[0] != newActivePeriodMs) {
                    QuotaController.this.mBucketPeriodsMs[0] = newActivePeriodMs;
                    changed = true;
                }
                long newWorkingPeriodMs = Math.max(QuotaController.this.mAllowedTimePerPeriodMs, Math.min(86400000L, this.WINDOW_SIZE_WORKING_MS));
                if (QuotaController.this.mBucketPeriodsMs[1] != newWorkingPeriodMs) {
                    QuotaController.this.mBucketPeriodsMs[1] = newWorkingPeriodMs;
                    changed = true;
                }
                long newFrequentPeriodMs = Math.max(QuotaController.this.mAllowedTimePerPeriodMs, Math.min(86400000L, this.WINDOW_SIZE_FREQUENT_MS));
                if (QuotaController.this.mBucketPeriodsMs[2] != newFrequentPeriodMs) {
                    QuotaController.this.mBucketPeriodsMs[2] = newFrequentPeriodMs;
                    changed = true;
                }
                long newRarePeriodMs = Math.max(QuotaController.this.mAllowedTimePerPeriodMs, Math.min(86400000L, this.WINDOW_SIZE_RARE_MS));
                if (QuotaController.this.mBucketPeriodsMs[3] != newRarePeriodMs) {
                    QuotaController.this.mBucketPeriodsMs[3] = newRarePeriodMs;
                    changed = true;
                }
                long newRateLimitingWindowMs = Math.min(86400000L, Math.max(30000L, this.RATE_LIMITING_WINDOW_MS));
                if (QuotaController.this.mRateLimitingWindowMs != newRateLimitingWindowMs) {
                    QuotaController.this.mRateLimitingWindowMs = newRateLimitingWindowMs;
                    changed = true;
                }
                int newMaxJobCountPerRateLimitingWindow = Math.max(10, this.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW);
                if (QuotaController.this.mMaxJobCountPerRateLimitingWindow != newMaxJobCountPerRateLimitingWindow) {
                    QuotaController.this.mMaxJobCountPerRateLimitingWindow = newMaxJobCountPerRateLimitingWindow;
                    changed = true;
                }
                int newActiveMaxJobCount = Math.max(10, this.MAX_JOB_COUNT_ACTIVE);
                if (QuotaController.this.mMaxBucketJobCounts[0] != newActiveMaxJobCount) {
                    QuotaController.this.mMaxBucketJobCounts[0] = newActiveMaxJobCount;
                    changed = true;
                }
                int newWorkingMaxJobCount = Math.max(10, this.MAX_JOB_COUNT_WORKING);
                if (QuotaController.this.mMaxBucketJobCounts[1] != newWorkingMaxJobCount) {
                    QuotaController.this.mMaxBucketJobCounts[1] = newWorkingMaxJobCount;
                    changed = true;
                }
                int newFrequentMaxJobCount = Math.max(10, this.MAX_JOB_COUNT_FREQUENT);
                if (QuotaController.this.mMaxBucketJobCounts[2] != newFrequentMaxJobCount) {
                    QuotaController.this.mMaxBucketJobCounts[2] = newFrequentMaxJobCount;
                    changed = true;
                }
                int newRareMaxJobCount = Math.max(10, this.MAX_JOB_COUNT_RARE);
                if (QuotaController.this.mMaxBucketJobCounts[3] != newRareMaxJobCount) {
                    QuotaController.this.mMaxBucketJobCounts[3] = newRareMaxJobCount;
                    changed = true;
                }
                boolean changed2 = changed;
                int newMaxSessionCountPerRateLimitPeriod = Math.max(10, this.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW);
                if (QuotaController.this.mMaxSessionCountPerRateLimitingWindow != newMaxSessionCountPerRateLimitPeriod) {
                    QuotaController.this.mMaxSessionCountPerRateLimitingWindow = newMaxSessionCountPerRateLimitPeriod;
                    changed2 = true;
                }
                int newActiveMaxSessionCount = Math.max(1, this.MAX_SESSION_COUNT_ACTIVE);
                if (QuotaController.this.mMaxBucketSessionCounts[0] != newActiveMaxSessionCount) {
                    QuotaController.this.mMaxBucketSessionCounts[0] = newActiveMaxSessionCount;
                    changed2 = true;
                }
                int newWorkingMaxSessionCount = Math.max(1, this.MAX_SESSION_COUNT_WORKING);
                if (QuotaController.this.mMaxBucketSessionCounts[1] != newWorkingMaxSessionCount) {
                    QuotaController.this.mMaxBucketSessionCounts[1] = newWorkingMaxSessionCount;
                    changed2 = true;
                }
                int newFrequentMaxSessionCount = Math.max(1, this.MAX_SESSION_COUNT_FREQUENT);
                if (QuotaController.this.mMaxBucketSessionCounts[2] != newFrequentMaxSessionCount) {
                    QuotaController.this.mMaxBucketSessionCounts[2] = newFrequentMaxSessionCount;
                    changed2 = true;
                }
                int newRareMaxSessionCount = Math.max(1, this.MAX_SESSION_COUNT_RARE);
                if (QuotaController.this.mMaxBucketSessionCounts[3] != newRareMaxSessionCount) {
                    QuotaController.this.mMaxBucketSessionCounts[3] = newRareMaxSessionCount;
                    changed2 = true;
                }
                long newSessionCoalescingDurationMs = Math.min(900000L, Math.max(0L, this.TIMING_SESSION_COALESCING_DURATION_MS));
                if (QuotaController.this.mTimingSessionCoalescingDurationMs != newSessionCoalescingDurationMs) {
                    QuotaController.this.mTimingSessionCoalescingDurationMs = newSessionCoalescingDurationMs;
                    changed2 = true;
                }
                if (changed2 && QuotaController.this.mShouldThrottle) {
                    BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$QcConstants$RqRCx_b6VU7ay15cmbscxEnJw7Q
                        @Override // java.lang.Runnable
                        public final void run() {
                            QuotaController.QcConstants.this.lambda$updateConstants$0$QuotaController$QcConstants();
                        }
                    });
                }
            }
        }

        public /* synthetic */ void lambda$updateConstants$0$QuotaController$QcConstants() {
            synchronized (QuotaController.this.mLock) {
                QuotaController.this.invalidateAllExecutionStatsLocked();
                QuotaController.this.maybeUpdateAllConstraintsLocked();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dump(IndentingPrintWriter pw) {
            pw.println();
            pw.println("QuotaController:");
            pw.increaseIndent();
            pw.printPair(KEY_ALLOWED_TIME_PER_PERIOD_MS, Long.valueOf(this.ALLOWED_TIME_PER_PERIOD_MS)).println();
            pw.printPair(KEY_IN_QUOTA_BUFFER_MS, Long.valueOf(this.IN_QUOTA_BUFFER_MS)).println();
            pw.printPair(KEY_WINDOW_SIZE_ACTIVE_MS, Long.valueOf(this.WINDOW_SIZE_ACTIVE_MS)).println();
            pw.printPair(KEY_WINDOW_SIZE_WORKING_MS, Long.valueOf(this.WINDOW_SIZE_WORKING_MS)).println();
            pw.printPair(KEY_WINDOW_SIZE_FREQUENT_MS, Long.valueOf(this.WINDOW_SIZE_FREQUENT_MS)).println();
            pw.printPair(KEY_WINDOW_SIZE_RARE_MS, Long.valueOf(this.WINDOW_SIZE_RARE_MS)).println();
            pw.printPair(KEY_MAX_EXECUTION_TIME_MS, Long.valueOf(this.MAX_EXECUTION_TIME_MS)).println();
            pw.printPair(KEY_MAX_JOB_COUNT_ACTIVE, Integer.valueOf(this.MAX_JOB_COUNT_ACTIVE)).println();
            pw.printPair(KEY_MAX_JOB_COUNT_WORKING, Integer.valueOf(this.MAX_JOB_COUNT_WORKING)).println();
            pw.printPair(KEY_MAX_JOB_COUNT_FREQUENT, Integer.valueOf(this.MAX_JOB_COUNT_FREQUENT)).println();
            pw.printPair(KEY_MAX_JOB_COUNT_RARE, Integer.valueOf(this.MAX_JOB_COUNT_RARE)).println();
            pw.printPair(KEY_RATE_LIMITING_WINDOW_MS, Long.valueOf(this.RATE_LIMITING_WINDOW_MS)).println();
            pw.printPair(KEY_MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW, Integer.valueOf(this.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW)).println();
            pw.printPair(KEY_MAX_SESSION_COUNT_ACTIVE, Integer.valueOf(this.MAX_SESSION_COUNT_ACTIVE)).println();
            pw.printPair(KEY_MAX_SESSION_COUNT_WORKING, Integer.valueOf(this.MAX_SESSION_COUNT_WORKING)).println();
            pw.printPair(KEY_MAX_SESSION_COUNT_FREQUENT, Integer.valueOf(this.MAX_SESSION_COUNT_FREQUENT)).println();
            pw.printPair(KEY_MAX_SESSION_COUNT_RARE, Integer.valueOf(this.MAX_SESSION_COUNT_RARE)).println();
            pw.printPair(KEY_MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW, Integer.valueOf(this.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW)).println();
            pw.printPair(KEY_TIMING_SESSION_COALESCING_DURATION_MS, Long.valueOf(this.TIMING_SESSION_COALESCING_DURATION_MS)).println();
            pw.decreaseIndent();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dump(ProtoOutputStream proto) {
            long qcToken = proto.start(1146756268056L);
            proto.write(1112396529665L, this.ALLOWED_TIME_PER_PERIOD_MS);
            proto.write(1112396529666L, this.IN_QUOTA_BUFFER_MS);
            proto.write(1112396529667L, this.WINDOW_SIZE_ACTIVE_MS);
            proto.write(1112396529668L, this.WINDOW_SIZE_WORKING_MS);
            proto.write(1112396529669L, this.WINDOW_SIZE_FREQUENT_MS);
            proto.write(1112396529670L, this.WINDOW_SIZE_RARE_MS);
            proto.write(1112396529671L, this.MAX_EXECUTION_TIME_MS);
            proto.write(1120986464264L, this.MAX_JOB_COUNT_ACTIVE);
            proto.write(1120986464265L, this.MAX_JOB_COUNT_WORKING);
            proto.write(1120986464266L, this.MAX_JOB_COUNT_FREQUENT);
            proto.write(1120986464267L, this.MAX_JOB_COUNT_RARE);
            proto.write(1120986464275L, this.RATE_LIMITING_WINDOW_MS);
            proto.write(1120986464268L, this.MAX_JOB_COUNT_PER_RATE_LIMITING_WINDOW);
            proto.write(1120986464269L, this.MAX_SESSION_COUNT_ACTIVE);
            proto.write(1120986464270L, this.MAX_SESSION_COUNT_WORKING);
            proto.write(1120986464271L, this.MAX_SESSION_COUNT_FREQUENT);
            proto.write(1120986464272L, this.MAX_SESSION_COUNT_RARE);
            proto.write(1120986464273L, this.MAX_SESSION_COUNT_PER_RATE_LIMITING_WINDOW);
            proto.write(1112396529682L, this.TIMING_SESSION_COALESCING_DURATION_MS);
            proto.end(qcToken);
        }
    }

    @VisibleForTesting
    long getAllowedTimePerPeriodMs() {
        return this.mAllowedTimePerPeriodMs;
    }

    @VisibleForTesting
    int[] getBucketMaxJobCounts() {
        return this.mMaxBucketJobCounts;
    }

    @VisibleForTesting
    int[] getBucketMaxSessionCounts() {
        return this.mMaxBucketSessionCounts;
    }

    @VisibleForTesting
    long[] getBucketWindowSizes() {
        return this.mBucketPeriodsMs;
    }

    @VisibleForTesting
    SparseBooleanArray getForegroundUids() {
        return this.mForegroundUids;
    }

    @VisibleForTesting
    Handler getHandler() {
        return this.mHandler;
    }

    @VisibleForTesting
    long getInQuotaBufferMs() {
        return this.mQuotaBufferMs;
    }

    @VisibleForTesting
    long getMaxExecutionTimeMs() {
        return this.mMaxExecutionTimeMs;
    }

    @VisibleForTesting
    int getMaxJobCountPerRateLimitingWindow() {
        return this.mMaxJobCountPerRateLimitingWindow;
    }

    @VisibleForTesting
    int getMaxSessionCountPerRateLimitingWindow() {
        return this.mMaxSessionCountPerRateLimitingWindow;
    }

    @VisibleForTesting
    long getRateLimitingWindowMs() {
        return this.mRateLimitingWindowMs;
    }

    @VisibleForTesting
    long getTimingSessionCoalescingDurationMs() {
        return this.mTimingSessionCoalescingDurationMs;
    }

    @VisibleForTesting
    List<TimingSession> getTimingSessions(int userId, String packageName) {
        return this.mTimingSessions.get(userId, packageName);
    }

    @VisibleForTesting
    QcConstants getQcConstants() {
        return this.mQcConstants;
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(final IndentingPrintWriter pw, final Predicate<JobStatus> predicate) {
        pw.println("Is throttling: " + this.mShouldThrottle);
        pw.println("Is charging: " + this.mChargeTracker.isCharging());
        pw.println("In parole: " + this.mInParole);
        pw.println("Current elapsed time: " + JobSchedulerService.sElapsedRealtimeClock.millis());
        pw.println();
        pw.print("Foreground UIDs: ");
        pw.println(this.mForegroundUids.toString());
        pw.println();
        pw.println("Cached UID->package map:");
        pw.increaseIndent();
        for (int i = 0; i < this.mUidToPackageCache.size(); i++) {
            int uid = this.mUidToPackageCache.keyAt(i);
            pw.print(uid);
            pw.print(": ");
            pw.println(this.mUidToPackageCache.get(uid));
        }
        pw.decreaseIndent();
        pw.println();
        this.mTrackedJobs.forEach(new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$LrhE3MR6b_HLbgtFW6XDyRkYhjc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                QuotaController.this.lambda$dumpControllerStateLocked$3$QuotaController(predicate, pw, (ArraySet) obj);
            }
        });
        pw.println();
        for (int u = 0; u < this.mPkgTimers.numUsers(); u++) {
            int userId = this.mPkgTimers.keyAt(u);
            for (int p = 0; p < this.mPkgTimers.numPackagesForUser(userId); p++) {
                String pkgName = this.mPkgTimers.keyAt(u, p);
                this.mPkgTimers.valueAt(u, p).dump(pw, predicate);
                pw.println();
                List<TimingSession> sessions = this.mTimingSessions.get(userId, pkgName);
                if (sessions != null) {
                    pw.increaseIndent();
                    pw.println("Saved sessions:");
                    pw.increaseIndent();
                    for (int j = sessions.size() - 1; j >= 0; j--) {
                        TimingSession session = sessions.get(j);
                        session.dump(pw);
                    }
                    pw.decreaseIndent();
                    pw.decreaseIndent();
                    pw.println();
                }
            }
        }
        pw.println("Cached execution stats:");
        pw.increaseIndent();
        for (int u2 = 0; u2 < this.mExecutionStatsCache.numUsers(); u2++) {
            int userId2 = this.mExecutionStatsCache.keyAt(u2);
            for (int p2 = 0; p2 < this.mExecutionStatsCache.numPackagesForUser(userId2); p2++) {
                String pkgName2 = this.mExecutionStatsCache.keyAt(u2, p2);
                ExecutionStats[] stats = this.mExecutionStatsCache.valueAt(u2, p2);
                pw.println(string(userId2, pkgName2));
                pw.increaseIndent();
                for (int i2 = 0; i2 < stats.length; i2++) {
                    ExecutionStats executionStats = stats[i2];
                    if (executionStats != null) {
                        pw.print(JobStatus.bucketName(i2));
                        pw.print(": ");
                        pw.println(executionStats);
                    }
                }
                pw.decreaseIndent();
            }
        }
        pw.decreaseIndent();
        pw.println();
        pw.println("In quota alarms:");
        pw.increaseIndent();
        for (int u3 = 0; u3 < this.mInQuotaAlarmListeners.numUsers(); u3++) {
            int userId3 = this.mInQuotaAlarmListeners.keyAt(u3);
            for (int p3 = 0; p3 < this.mInQuotaAlarmListeners.numPackagesForUser(userId3); p3++) {
                String pkgName3 = this.mInQuotaAlarmListeners.keyAt(u3, p3);
                QcAlarmListener alarmListener = this.mInQuotaAlarmListeners.valueAt(u3, p3);
                pw.print(string(userId3, pkgName3));
                pw.print(": ");
                if (alarmListener.isWaiting()) {
                    pw.println(alarmListener.getTriggerTimeElapsed());
                } else {
                    pw.println("NOT WAITING");
                }
            }
        }
        pw.decreaseIndent();
    }

    public /* synthetic */ void lambda$dumpControllerStateLocked$3$QuotaController(Predicate predicate, IndentingPrintWriter pw, ArraySet jobs) {
        for (int j = 0; j < jobs.size(); j++) {
            JobStatus js = (JobStatus) jobs.valueAt(j);
            if (predicate.test(js)) {
                pw.print("#");
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                if (this.mTopStartedJobs.contains(js)) {
                    pw.print(" (TOP)");
                }
                pw.println();
                pw.increaseIndent();
                pw.print(JobStatus.bucketName(getEffectiveStandbyBucket(js)));
                pw.print(", ");
                if (js.isConstraintSatisfied(16777216)) {
                    pw.print("within quota");
                } else {
                    pw.print("not within quota");
                }
                pw.print(", ");
                pw.print(getRemainingExecutionTimeLocked(js));
                pw.print("ms remaining in quota");
                pw.decreaseIndent();
                pw.println();
            }
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(final ProtoOutputStream proto, long fieldId, Predicate<JobStatus> predicate) {
        long token;
        long mToken;
        int p;
        long mToken2;
        int p2;
        List<TimingSession> sessions;
        final Predicate<JobStatus> predicate2 = predicate;
        long token2 = proto.start(fieldId);
        long mToken3 = proto.start(1146756268041L);
        proto.write(1133871366145L, this.mChargeTracker.isCharging());
        proto.write(1133871366146L, this.mInParole);
        proto.write(1112396529670L, JobSchedulerService.sElapsedRealtimeClock.millis());
        for (int i = 0; i < this.mForegroundUids.size(); i++) {
            proto.write(2220498092035L, this.mForegroundUids.keyAt(i));
        }
        this.mTrackedJobs.forEach(new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$QuotaController$URLEdatPa0Sor76K2xt12wlVxx4
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                QuotaController.this.lambda$dumpControllerStateLocked$4$QuotaController(predicate2, proto, (ArraySet) obj);
            }
        });
        int u = 0;
        while (u < this.mPkgTimers.numUsers()) {
            int userId = this.mPkgTimers.keyAt(u);
            int p3 = 0;
            while (p3 < this.mPkgTimers.numPackagesForUser(userId)) {
                String pkgName = this.mPkgTimers.keyAt(u, p3);
                long psToken = proto.start(2246267895813L);
                this.mPkgTimers.valueAt(u, p3).dump(proto, 1146756268034L, predicate2);
                List<TimingSession> sessions2 = this.mTimingSessions.get(userId, pkgName);
                if (sessions2 == null) {
                    token = token2;
                } else {
                    int j = sessions2.size() - 1;
                    while (j >= 0) {
                        TimingSession session = sessions2.get(j);
                        session.dump(proto, 2246267895811L);
                        j--;
                        token2 = token2;
                    }
                    token = token2;
                }
                ExecutionStats[] stats = this.mExecutionStatsCache.get(userId, pkgName);
                if (stats == null) {
                    mToken = mToken3;
                    p = p3;
                } else {
                    int bucketIndex = 0;
                    while (bucketIndex < stats.length) {
                        ExecutionStats es = stats[bucketIndex];
                        if (es == null) {
                            mToken2 = mToken3;
                            p2 = p3;
                            sessions = sessions2;
                        } else {
                            long esToken = proto.start(2246267895812L);
                            mToken2 = mToken3;
                            proto.write(1159641169921L, bucketIndex);
                            p2 = p3;
                            sessions = sessions2;
                            proto.write(1112396529666L, es.expirationTimeElapsed);
                            proto.write(1112396529667L, es.windowSizeMs);
                            proto.write(1120986464270L, es.jobCountLimit);
                            proto.write(1120986464271L, es.sessionCountLimit);
                            proto.write(1112396529668L, es.executionTimeInWindowMs);
                            proto.write(1120986464261L, es.bgJobCountInWindow);
                            proto.write(1112396529670L, es.executionTimeInMaxPeriodMs);
                            proto.write(1120986464263L, es.bgJobCountInMaxPeriod);
                            proto.write(1120986464267L, es.sessionCountInWindow);
                            proto.write(1112396529672L, es.inQuotaTimeElapsed);
                            proto.write(1112396529673L, es.jobRateLimitExpirationTimeElapsed);
                            proto.write(1120986464266L, es.jobCountInRateLimitingWindow);
                            proto.write(1112396529676L, es.sessionRateLimitExpirationTimeElapsed);
                            proto.write(1120986464269L, es.sessionCountInRateLimitingWindow);
                            proto.end(esToken);
                        }
                        bucketIndex++;
                        p3 = p2;
                        mToken3 = mToken2;
                        sessions2 = sessions;
                    }
                    mToken = mToken3;
                    p = p3;
                }
                QcAlarmListener alarmListener = this.mInQuotaAlarmListeners.get(userId, pkgName);
                if (alarmListener != null) {
                    long alToken = proto.start(1146756268037L);
                    proto.write(1133871366145L, alarmListener.isWaiting());
                    proto.write(1112396529666L, alarmListener.getTriggerTimeElapsed());
                    proto.end(alToken);
                }
                proto.end(psToken);
                p3 = p + 1;
                predicate2 = predicate;
                token2 = token;
                mToken3 = mToken;
            }
            u++;
            predicate2 = predicate;
        }
        proto.end(mToken3);
        proto.end(token2);
    }

    public /* synthetic */ void lambda$dumpControllerStateLocked$4$QuotaController(Predicate predicate, ProtoOutputStream proto, ArraySet jobs) {
        for (int j = 0; j < jobs.size(); j++) {
            JobStatus js = (JobStatus) jobs.valueAt(j);
            if (predicate.test(js)) {
                long jsToken = proto.start(2246267895812L);
                js.writeToShortProto(proto, 1146756268033L);
                proto.write(1120986464258L, js.getSourceUid());
                proto.write(1159641169923L, getEffectiveStandbyBucket(js));
                proto.write(1133871366148L, this.mTopStartedJobs.contains(js));
                proto.write(1133871366149L, js.isConstraintSatisfied(16777216));
                proto.write(1112396529670L, getRemainingExecutionTimeLocked(js));
                proto.end(jsToken);
            }
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpConstants(IndentingPrintWriter pw) {
        this.mQcConstants.dump(pw);
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpConstants(ProtoOutputStream proto) {
        this.mQcConstants.dump(proto);
    }
}
