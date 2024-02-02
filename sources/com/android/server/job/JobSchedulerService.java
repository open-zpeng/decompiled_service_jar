package com.android.server.job;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.job.IJobScheduler;
import android.app.job.JobInfo;
import android.app.job.JobWorkItem;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.AppStateTracker;
import com.android.server.DeviceIdleController;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.BackgroundJobsController;
import com.android.server.job.controllers.BatteryController;
import com.android.server.job.controllers.ConnectivityController;
import com.android.server.job.controllers.ContentObserverController;
import com.android.server.job.controllers.DeviceIdleJobsController;
import com.android.server.job.controllers.IdleController;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;
import com.android.server.job.controllers.StorageController;
import com.android.server.job.controllers.TimeController;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.utils.PriorityDump;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import libcore.util.EmptyArray;
/* loaded from: classes.dex */
public class JobSchedulerService extends SystemService implements StateChangedListener, JobCompletedListener {
    static final int ACTIVE_INDEX = 0;
    public static final boolean DEBUG_STANDBY;
    private static final boolean ENFORCE_MAX_JOBS = true;
    static final int FREQUENT_INDEX = 2;
    static final String HEARTBEAT_TAG = "*job.heartbeat*";
    private static final int MAX_JOBS_PER_APP = 100;
    private static final int MAX_JOB_CONTEXTS_COUNT = 16;
    static final int MSG_CHECK_JOB = 1;
    static final int MSG_CHECK_JOB_GREEDY = 3;
    static final int MSG_JOB_EXPIRED = 0;
    static final int MSG_STOP_JOB = 2;
    static final int MSG_UID_ACTIVE = 6;
    static final int MSG_UID_GONE = 5;
    static final int MSG_UID_IDLE = 7;
    static final int MSG_UID_STATE_CHANGED = 4;
    static final int NEVER_INDEX = 4;
    static final int RARE_INDEX = 3;
    static final int WORKING_INDEX = 1;
    static final Comparator<JobStatus> mEnqueueTimeComparator;
    @VisibleForTesting
    public static Clock sElapsedRealtimeClock;
    @VisibleForTesting
    public static Clock sSystemClock;
    @VisibleForTesting
    public static Clock sUptimeMillisClock;
    final List<JobServiceContext> mActiveServices;
    ActivityManagerInternal mActivityManagerInternal;
    AppStateTracker mAppStateTracker;
    final SparseIntArray mBackingUpUids;
    private final BatteryController mBatteryController;
    IBatteryStats mBatteryStats;
    private final BroadcastReceiver mBroadcastReceiver;
    final Constants mConstants;
    final ConstantsObserver mConstantsObserver;
    private final List<StateController> mControllers;
    private final DeviceIdleJobsController mDeviceIdleJobsController;
    final JobHandler mHandler;
    long mHeartbeat;
    final HeartbeatAlarmListener mHeartbeatAlarm;
    volatile boolean mInParole;
    private final Predicate<Integer> mIsUidActivePredicate;
    final JobPackageTracker mJobPackageTracker;
    final JobSchedulerStub mJobSchedulerStub;
    private final Runnable mJobTimeUpdater;
    final JobStore mJobs;
    long mLastHeartbeatTime;
    final SparseArray<HashMap<String, Long>> mLastJobHeartbeats;
    DeviceIdleController.LocalService mLocalDeviceIdleController;
    PackageManagerInternal mLocalPM;
    final Object mLock;
    int mMaxActiveJobs;
    private final MaybeReadyJobQueueFunctor mMaybeQueueFunctor;
    final long[] mNextBucketHeartbeat;
    final ArrayList<JobStatus> mPendingJobs;
    private final ReadyJobQueueFunctor mReadyQueueFunctor;
    boolean mReadyToRock;
    boolean mReportedActive;
    final StandbyTracker mStandbyTracker;
    int[] mStartedUsers;
    private final StorageController mStorageController;
    private final BroadcastReceiver mTimeSetReceiver;
    boolean[] mTmpAssignAct;
    JobStatus[] mTmpAssignContextIdToJobMap;
    int[] mTmpAssignPreferredUidForContext;
    private final IUidObserver mUidObserver;
    final SparseIntArray mUidPriorityOverride;
    final UsageStatsManagerInternal mUsageStats;
    public static final String TAG = "JobScheduler";
    public static final boolean DEBUG = Log.isLoggable(TAG, 3);

    static {
        DEBUG_STANDBY = DEBUG;
        sSystemClock = Clock.systemUTC();
        sUptimeMillisClock = SystemClock.uptimeMillisClock();
        sElapsedRealtimeClock = SystemClock.elapsedRealtimeClock();
        mEnqueueTimeComparator = new Comparator() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$V6_ZmVmzJutg4w0s0LktDOsRAss
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return JobSchedulerService.lambda$static$0((JobStatus) obj, (JobStatus) obj2);
            }
        };
    }

    /* loaded from: classes.dex */
    private class ConstantsObserver extends ContentObserver {
        private ContentResolver mResolver;

        public ConstantsObserver(Handler handler) {
            super(handler);
        }

        public void start(ContentResolver resolver) {
            this.mResolver = resolver;
            this.mResolver.registerContentObserver(Settings.Global.getUriFor("job_scheduler_constants"), false, this);
            updateConstants();
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (JobSchedulerService.this.mLock) {
                try {
                    JobSchedulerService.this.mConstants.updateConstantsLocked(Settings.Global.getString(this.mResolver, "job_scheduler_constants"));
                } catch (IllegalArgumentException e) {
                    Slog.e(JobSchedulerService.TAG, "Bad jobscheduler settings", e);
                }
            }
            JobSchedulerService.this.setNextHeartbeatAlarm();
        }
    }

    /* loaded from: classes.dex */
    public static class Constants {
        private static final int DEFAULT_BG_CRITICAL_JOB_COUNT = 1;
        private static final int DEFAULT_BG_LOW_JOB_COUNT = 1;
        private static final int DEFAULT_BG_MODERATE_JOB_COUNT = 4;
        private static final int DEFAULT_BG_NORMAL_JOB_COUNT = 6;
        private static final float DEFAULT_CONN_CONGESTION_DELAY_FRAC = 0.5f;
        private static final float DEFAULT_CONN_PREFETCH_RELAX_FRAC = 0.5f;
        private static final int DEFAULT_FG_JOB_COUNT = 4;
        private static final float DEFAULT_HEAVY_USE_FACTOR = 0.9f;
        private static final int DEFAULT_MAX_STANDARD_RESCHEDULE_COUNT = Integer.MAX_VALUE;
        private static final int DEFAULT_MAX_WORK_RESCHEDULE_COUNT = Integer.MAX_VALUE;
        private static final int DEFAULT_MIN_BATTERY_NOT_LOW_COUNT = 1;
        private static final int DEFAULT_MIN_CHARGING_COUNT = 1;
        private static final int DEFAULT_MIN_CONNECTIVITY_COUNT = 1;
        private static final int DEFAULT_MIN_CONTENT_COUNT = 1;
        private static final long DEFAULT_MIN_EXP_BACKOFF_TIME = 10000;
        private static final int DEFAULT_MIN_IDLE_COUNT = 1;
        private static final long DEFAULT_MIN_LINEAR_BACKOFF_TIME = 10000;
        private static final int DEFAULT_MIN_READY_JOBS_COUNT = 1;
        private static final int DEFAULT_MIN_STORAGE_NOT_LOW_COUNT = 1;
        private static final float DEFAULT_MODERATE_USE_FACTOR = 0.5f;
        private static final int DEFAULT_STANDBY_FREQUENT_BEATS = 43;
        private static final long DEFAULT_STANDBY_HEARTBEAT_TIME = 660000;
        private static final int DEFAULT_STANDBY_RARE_BEATS = 130;
        private static final int DEFAULT_STANDBY_WORKING_BEATS = 11;
        private static final String KEY_BG_CRITICAL_JOB_COUNT = "bg_critical_job_count";
        private static final String KEY_BG_LOW_JOB_COUNT = "bg_low_job_count";
        private static final String KEY_BG_MODERATE_JOB_COUNT = "bg_moderate_job_count";
        private static final String KEY_BG_NORMAL_JOB_COUNT = "bg_normal_job_count";
        private static final String KEY_CONN_CONGESTION_DELAY_FRAC = "conn_congestion_delay_frac";
        private static final String KEY_CONN_PREFETCH_RELAX_FRAC = "conn_prefetch_relax_frac";
        private static final String KEY_FG_JOB_COUNT = "fg_job_count";
        private static final String KEY_HEAVY_USE_FACTOR = "heavy_use_factor";
        private static final String KEY_MAX_STANDARD_RESCHEDULE_COUNT = "max_standard_reschedule_count";
        private static final String KEY_MAX_WORK_RESCHEDULE_COUNT = "max_work_reschedule_count";
        private static final String KEY_MIN_BATTERY_NOT_LOW_COUNT = "min_battery_not_low_count";
        private static final String KEY_MIN_CHARGING_COUNT = "min_charging_count";
        private static final String KEY_MIN_CONNECTIVITY_COUNT = "min_connectivity_count";
        private static final String KEY_MIN_CONTENT_COUNT = "min_content_count";
        private static final String KEY_MIN_EXP_BACKOFF_TIME = "min_exp_backoff_time";
        private static final String KEY_MIN_IDLE_COUNT = "min_idle_count";
        private static final String KEY_MIN_LINEAR_BACKOFF_TIME = "min_linear_backoff_time";
        private static final String KEY_MIN_READY_JOBS_COUNT = "min_ready_jobs_count";
        private static final String KEY_MIN_STORAGE_NOT_LOW_COUNT = "min_storage_not_low_count";
        private static final String KEY_MODERATE_USE_FACTOR = "moderate_use_factor";
        private static final String KEY_STANDBY_FREQUENT_BEATS = "standby_frequent_beats";
        private static final String KEY_STANDBY_HEARTBEAT_TIME = "standby_heartbeat_time";
        private static final String KEY_STANDBY_RARE_BEATS = "standby_rare_beats";
        private static final String KEY_STANDBY_WORKING_BEATS = "standby_working_beats";
        int MIN_IDLE_COUNT = 1;
        int MIN_CHARGING_COUNT = 1;
        int MIN_BATTERY_NOT_LOW_COUNT = 1;
        int MIN_STORAGE_NOT_LOW_COUNT = 1;
        int MIN_CONNECTIVITY_COUNT = 1;
        int MIN_CONTENT_COUNT = 1;
        int MIN_READY_JOBS_COUNT = 1;
        float HEAVY_USE_FACTOR = DEFAULT_HEAVY_USE_FACTOR;
        float MODERATE_USE_FACTOR = 0.5f;
        int FG_JOB_COUNT = 4;
        int BG_NORMAL_JOB_COUNT = 6;
        int BG_MODERATE_JOB_COUNT = 4;
        int BG_LOW_JOB_COUNT = 1;
        int BG_CRITICAL_JOB_COUNT = 1;
        int MAX_STANDARD_RESCHEDULE_COUNT = Integer.MAX_VALUE;
        int MAX_WORK_RESCHEDULE_COUNT = Integer.MAX_VALUE;
        long MIN_LINEAR_BACKOFF_TIME = JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
        long MIN_EXP_BACKOFF_TIME = JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
        long STANDBY_HEARTBEAT_TIME = DEFAULT_STANDBY_HEARTBEAT_TIME;
        final int[] STANDBY_BEATS = {0, 11, 43, DEFAULT_STANDBY_RARE_BEATS};
        public float CONN_CONGESTION_DELAY_FRAC = 0.5f;
        public float CONN_PREFETCH_RELAX_FRAC = 0.5f;
        private final KeyValueListParser mParser = new KeyValueListParser(',');

        void updateConstantsLocked(String value) {
            try {
                this.mParser.setString(value);
            } catch (Exception e) {
                Slog.e(JobSchedulerService.TAG, "Bad jobscheduler settings", e);
            }
            this.MIN_IDLE_COUNT = this.mParser.getInt(KEY_MIN_IDLE_COUNT, 1);
            this.MIN_CHARGING_COUNT = this.mParser.getInt(KEY_MIN_CHARGING_COUNT, 1);
            this.MIN_BATTERY_NOT_LOW_COUNT = this.mParser.getInt(KEY_MIN_BATTERY_NOT_LOW_COUNT, 1);
            this.MIN_STORAGE_NOT_LOW_COUNT = this.mParser.getInt(KEY_MIN_STORAGE_NOT_LOW_COUNT, 1);
            this.MIN_CONNECTIVITY_COUNT = this.mParser.getInt(KEY_MIN_CONNECTIVITY_COUNT, 1);
            this.MIN_CONTENT_COUNT = this.mParser.getInt(KEY_MIN_CONTENT_COUNT, 1);
            this.MIN_READY_JOBS_COUNT = this.mParser.getInt(KEY_MIN_READY_JOBS_COUNT, 1);
            this.HEAVY_USE_FACTOR = this.mParser.getFloat(KEY_HEAVY_USE_FACTOR, (float) DEFAULT_HEAVY_USE_FACTOR);
            this.MODERATE_USE_FACTOR = this.mParser.getFloat(KEY_MODERATE_USE_FACTOR, 0.5f);
            this.FG_JOB_COUNT = this.mParser.getInt(KEY_FG_JOB_COUNT, 4);
            this.BG_NORMAL_JOB_COUNT = this.mParser.getInt(KEY_BG_NORMAL_JOB_COUNT, 6);
            if (this.FG_JOB_COUNT + this.BG_NORMAL_JOB_COUNT > 16) {
                this.BG_NORMAL_JOB_COUNT = 16 - this.FG_JOB_COUNT;
            }
            this.BG_MODERATE_JOB_COUNT = this.mParser.getInt(KEY_BG_MODERATE_JOB_COUNT, 4);
            if (this.FG_JOB_COUNT + this.BG_MODERATE_JOB_COUNT > 16) {
                this.BG_MODERATE_JOB_COUNT = 16 - this.FG_JOB_COUNT;
            }
            this.BG_LOW_JOB_COUNT = this.mParser.getInt(KEY_BG_LOW_JOB_COUNT, 1);
            if (this.FG_JOB_COUNT + this.BG_LOW_JOB_COUNT > 16) {
                this.BG_LOW_JOB_COUNT = 16 - this.FG_JOB_COUNT;
            }
            this.BG_CRITICAL_JOB_COUNT = this.mParser.getInt(KEY_BG_CRITICAL_JOB_COUNT, 1);
            if (this.FG_JOB_COUNT + this.BG_CRITICAL_JOB_COUNT > 16) {
                this.BG_CRITICAL_JOB_COUNT = 16 - this.FG_JOB_COUNT;
            }
            this.MAX_STANDARD_RESCHEDULE_COUNT = this.mParser.getInt(KEY_MAX_STANDARD_RESCHEDULE_COUNT, Integer.MAX_VALUE);
            this.MAX_WORK_RESCHEDULE_COUNT = this.mParser.getInt(KEY_MAX_WORK_RESCHEDULE_COUNT, Integer.MAX_VALUE);
            this.MIN_LINEAR_BACKOFF_TIME = this.mParser.getDurationMillis(KEY_MIN_LINEAR_BACKOFF_TIME, (long) JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
            this.MIN_EXP_BACKOFF_TIME = this.mParser.getDurationMillis(KEY_MIN_EXP_BACKOFF_TIME, (long) JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
            this.STANDBY_HEARTBEAT_TIME = this.mParser.getDurationMillis(KEY_STANDBY_HEARTBEAT_TIME, (long) DEFAULT_STANDBY_HEARTBEAT_TIME);
            this.STANDBY_BEATS[1] = this.mParser.getInt(KEY_STANDBY_WORKING_BEATS, 11);
            this.STANDBY_BEATS[2] = this.mParser.getInt(KEY_STANDBY_FREQUENT_BEATS, 43);
            this.STANDBY_BEATS[3] = this.mParser.getInt(KEY_STANDBY_RARE_BEATS, (int) DEFAULT_STANDBY_RARE_BEATS);
            this.CONN_CONGESTION_DELAY_FRAC = this.mParser.getFloat(KEY_CONN_CONGESTION_DELAY_FRAC, 0.5f);
            this.CONN_PREFETCH_RELAX_FRAC = this.mParser.getFloat(KEY_CONN_PREFETCH_RELAX_FRAC, 0.5f);
        }

        void dump(IndentingPrintWriter pw) {
            pw.println("Settings:");
            pw.increaseIndent();
            pw.printPair(KEY_MIN_IDLE_COUNT, Integer.valueOf(this.MIN_IDLE_COUNT)).println();
            pw.printPair(KEY_MIN_CHARGING_COUNT, Integer.valueOf(this.MIN_CHARGING_COUNT)).println();
            pw.printPair(KEY_MIN_BATTERY_NOT_LOW_COUNT, Integer.valueOf(this.MIN_BATTERY_NOT_LOW_COUNT)).println();
            pw.printPair(KEY_MIN_STORAGE_NOT_LOW_COUNT, Integer.valueOf(this.MIN_STORAGE_NOT_LOW_COUNT)).println();
            pw.printPair(KEY_MIN_CONNECTIVITY_COUNT, Integer.valueOf(this.MIN_CONNECTIVITY_COUNT)).println();
            pw.printPair(KEY_MIN_CONTENT_COUNT, Integer.valueOf(this.MIN_CONTENT_COUNT)).println();
            pw.printPair(KEY_MIN_READY_JOBS_COUNT, Integer.valueOf(this.MIN_READY_JOBS_COUNT)).println();
            pw.printPair(KEY_HEAVY_USE_FACTOR, Float.valueOf(this.HEAVY_USE_FACTOR)).println();
            pw.printPair(KEY_MODERATE_USE_FACTOR, Float.valueOf(this.MODERATE_USE_FACTOR)).println();
            pw.printPair(KEY_FG_JOB_COUNT, Integer.valueOf(this.FG_JOB_COUNT)).println();
            pw.printPair(KEY_BG_NORMAL_JOB_COUNT, Integer.valueOf(this.BG_NORMAL_JOB_COUNT)).println();
            pw.printPair(KEY_BG_MODERATE_JOB_COUNT, Integer.valueOf(this.BG_MODERATE_JOB_COUNT)).println();
            pw.printPair(KEY_BG_LOW_JOB_COUNT, Integer.valueOf(this.BG_LOW_JOB_COUNT)).println();
            pw.printPair(KEY_BG_CRITICAL_JOB_COUNT, Integer.valueOf(this.BG_CRITICAL_JOB_COUNT)).println();
            pw.printPair(KEY_MAX_STANDARD_RESCHEDULE_COUNT, Integer.valueOf(this.MAX_STANDARD_RESCHEDULE_COUNT)).println();
            pw.printPair(KEY_MAX_WORK_RESCHEDULE_COUNT, Integer.valueOf(this.MAX_WORK_RESCHEDULE_COUNT)).println();
            pw.printPair(KEY_MIN_LINEAR_BACKOFF_TIME, Long.valueOf(this.MIN_LINEAR_BACKOFF_TIME)).println();
            pw.printPair(KEY_MIN_EXP_BACKOFF_TIME, Long.valueOf(this.MIN_EXP_BACKOFF_TIME)).println();
            pw.printPair(KEY_STANDBY_HEARTBEAT_TIME, Long.valueOf(this.STANDBY_HEARTBEAT_TIME)).println();
            pw.print("standby_beats={");
            pw.print(this.STANDBY_BEATS[0]);
            for (int i = 1; i < this.STANDBY_BEATS.length; i++) {
                pw.print(", ");
                pw.print(this.STANDBY_BEATS[i]);
            }
            pw.println('}');
            pw.printPair(KEY_CONN_CONGESTION_DELAY_FRAC, Float.valueOf(this.CONN_CONGESTION_DELAY_FRAC)).println();
            pw.printPair(KEY_CONN_PREFETCH_RELAX_FRAC, Float.valueOf(this.CONN_PREFETCH_RELAX_FRAC)).println();
            pw.decreaseIndent();
        }

        void dump(ProtoOutputStream proto, long fieldId) {
            int[] iArr;
            long token = proto.start(fieldId);
            proto.write(1120986464257L, this.MIN_IDLE_COUNT);
            proto.write(1120986464258L, this.MIN_CHARGING_COUNT);
            proto.write(1120986464259L, this.MIN_BATTERY_NOT_LOW_COUNT);
            proto.write(1120986464260L, this.MIN_STORAGE_NOT_LOW_COUNT);
            proto.write(1120986464261L, this.MIN_CONNECTIVITY_COUNT);
            proto.write(1120986464262L, this.MIN_CONTENT_COUNT);
            proto.write(1120986464263L, this.MIN_READY_JOBS_COUNT);
            proto.write(1103806595080L, this.HEAVY_USE_FACTOR);
            proto.write(1103806595081L, this.MODERATE_USE_FACTOR);
            proto.write(1120986464266L, this.FG_JOB_COUNT);
            proto.write(1120986464267L, this.BG_NORMAL_JOB_COUNT);
            proto.write(1120986464268L, this.BG_MODERATE_JOB_COUNT);
            proto.write(1120986464269L, this.BG_LOW_JOB_COUNT);
            proto.write(1120986464270L, this.BG_CRITICAL_JOB_COUNT);
            proto.write(1120986464271L, this.MAX_STANDARD_RESCHEDULE_COUNT);
            proto.write(1120986464272L, this.MAX_WORK_RESCHEDULE_COUNT);
            proto.write(1112396529681L, this.MIN_LINEAR_BACKOFF_TIME);
            proto.write(1112396529682L, this.MIN_EXP_BACKOFF_TIME);
            proto.write(1112396529683L, this.STANDBY_HEARTBEAT_TIME);
            for (int period : this.STANDBY_BEATS) {
                proto.write(2220498092052L, period);
            }
            proto.write(1103806595093L, this.CONN_CONGESTION_DELAY_FRAC);
            proto.write(1103806595094L, this.CONN_PREFETCH_RELAX_FRAC);
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$static$0(JobStatus o1, JobStatus o2) {
        if (o1.enqueueTime < o2.enqueueTime) {
            return -1;
        }
        return o1.enqueueTime > o2.enqueueTime ? 1 : 0;
    }

    static <T> void addOrderedItem(ArrayList<T> array, T newItem, Comparator<T> comparator) {
        int where = Collections.binarySearch(array, newItem, comparator);
        if (where < 0) {
            where = ~where;
        }
        array.add(where, newItem);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getPackageName(Intent intent) {
        Uri uri = intent.getData();
        if (uri != null) {
            String pkg = uri.getSchemeSpecificPart();
            return pkg;
        }
        return null;
    }

    public Context getTestableContext() {
        return getContext();
    }

    public Object getLock() {
        return this.mLock;
    }

    public JobStore getJobStore() {
        return this.mJobs;
    }

    public Constants getConstants() {
        return this.mConstants;
    }

    @Override // com.android.server.SystemService
    public void onStartUser(int userHandle) {
        this.mStartedUsers = ArrayUtils.appendInt(this.mStartedUsers, userHandle);
        this.mHandler.obtainMessage(1).sendToTarget();
    }

    @Override // com.android.server.SystemService
    public void onUnlockUser(int userHandle) {
        this.mHandler.obtainMessage(1).sendToTarget();
    }

    @Override // com.android.server.SystemService
    public void onStopUser(int userHandle) {
        this.mStartedUsers = ArrayUtils.removeInt(this.mStartedUsers, userHandle);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isUidActive(int uid) {
        return this.mAppStateTracker.isUidActiveSynced(uid);
    }

    public int scheduleAsPackage(JobInfo job, JobWorkItem work, int uId, String packageName, int userId, String tag) {
        try {
            if (ActivityManager.getService().isAppStartModeDisabled(uId, job.getService().getPackageName())) {
                Slog.w(TAG, "Not scheduling job " + uId + ":" + job.toString() + " -- package not allowed to start");
                return 0;
            }
        } catch (RemoteException e) {
        }
        synchronized (this.mLock) {
            try {
                try {
                    JobStatus toCancel = this.mJobs.getJobByUidAndJobId(uId, job.getId());
                    if (work != null && toCancel != null && toCancel.getJob().equals(job)) {
                        toCancel.enqueueWorkLocked(ActivityManager.getService(), work);
                        toCancel.maybeAddForegroundExemption(this.mIsUidActivePredicate);
                        return 1;
                    }
                    JobStatus jobStatus = JobStatus.createFromJobInfo(job, uId, packageName, userId, tag);
                    jobStatus.maybeAddForegroundExemption(this.mIsUidActivePredicate);
                    if (DEBUG) {
                        Slog.d(TAG, "SCHEDULE: " + jobStatus.toShortString());
                    }
                    if (packageName == null && this.mJobs.countJobsForUid(uId) > 100) {
                        Slog.w(TAG, "Too many jobs for uid " + uId);
                        throw new IllegalStateException("Apps may not schedule more than 100 distinct jobs");
                    }
                    jobStatus.prepareLocked(ActivityManager.getService());
                    if (work != null) {
                        jobStatus.enqueueWorkLocked(ActivityManager.getService(), work);
                    }
                    if (toCancel != null) {
                        cancelJobImplLocked(toCancel, jobStatus, "job rescheduled by app");
                    } else {
                        startTrackingJobLocked(jobStatus, null);
                    }
                    StatsLog.write_non_chained(8, uId, null, jobStatus.getBatteryName(), 2, 0);
                    if (isReadyToBeExecutedLocked(jobStatus)) {
                        this.mJobPackageTracker.notePending(jobStatus);
                        addOrderedItem(this.mPendingJobs, jobStatus, mEnqueueTimeComparator);
                        maybeRunPendingJobsLocked();
                    }
                    return 1;
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    public List<JobInfo> getPendingJobs(int uid) {
        ArrayList<JobInfo> outList;
        synchronized (this.mLock) {
            List<JobStatus> jobs = this.mJobs.getJobsByUid(uid);
            outList = new ArrayList<>(jobs.size());
            for (int i = jobs.size() - 1; i >= 0; i--) {
                JobStatus job = jobs.get(i);
                outList.add(job.getJob());
            }
        }
        return outList;
    }

    public JobInfo getPendingJob(int uid, int jobId) {
        synchronized (this.mLock) {
            List<JobStatus> jobs = this.mJobs.getJobsByUid(uid);
            for (int i = jobs.size() - 1; i >= 0; i--) {
                JobStatus job = jobs.get(i);
                if (job.getJobId() == jobId) {
                    return job.getJob();
                }
            }
            return null;
        }
    }

    void cancelJobsForUser(int userHandle) {
        synchronized (this.mLock) {
            List<JobStatus> jobsForUser = this.mJobs.getJobsByUser(userHandle);
            for (int i = 0; i < jobsForUser.size(); i++) {
                JobStatus toRemove = jobsForUser.get(i);
                cancelJobImplLocked(toRemove, null, "user removed");
            }
        }
    }

    private void cancelJobsForNonExistentUsers() {
        UserManagerInternal umi = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
        synchronized (this.mLock) {
            this.mJobs.removeJobsOfNonUsers(umi.getUserIds());
        }
    }

    void cancelJobsForPackageAndUid(String pkgName, int uid, String reason) {
        if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkgName)) {
            Slog.wtfStack(TAG, "Can't cancel all jobs for system package");
            return;
        }
        synchronized (this.mLock) {
            List<JobStatus> jobsForUid = this.mJobs.getJobsByUid(uid);
            for (int i = jobsForUid.size() - 1; i >= 0; i--) {
                JobStatus job = jobsForUid.get(i);
                if (job.getSourcePackageName().equals(pkgName)) {
                    cancelJobImplLocked(job, null, reason);
                }
            }
        }
    }

    public boolean cancelJobsForUid(int uid, String reason) {
        if (uid == 1000) {
            Slog.wtfStack(TAG, "Can't cancel all jobs for system uid");
            return false;
        }
        boolean jobsCanceled = false;
        synchronized (this.mLock) {
            List<JobStatus> jobsForUid = this.mJobs.getJobsByUid(uid);
            for (int i = 0; i < jobsForUid.size(); i++) {
                JobStatus toRemove = jobsForUid.get(i);
                cancelJobImplLocked(toRemove, null, reason);
                jobsCanceled = true;
            }
        }
        return jobsCanceled;
    }

    public boolean cancelJob(int uid, int jobId, int callingUid) {
        boolean z;
        synchronized (this.mLock) {
            JobStatus toCancel = this.mJobs.getJobByUidAndJobId(uid, jobId);
            if (toCancel != null) {
                cancelJobImplLocked(toCancel, null, "cancel() called by app, callingUid=" + callingUid + " uid=" + uid + " jobId=" + jobId);
            }
            z = toCancel != null;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelJobImplLocked(JobStatus cancelled, JobStatus incomingJob, String reason) {
        if (DEBUG) {
            Slog.d(TAG, "CANCEL: " + cancelled.toShortString());
        }
        cancelled.unprepareLocked(ActivityManager.getService());
        stopTrackingJobLocked(cancelled, incomingJob, true);
        if (this.mPendingJobs.remove(cancelled)) {
            this.mJobPackageTracker.noteNonpending(cancelled);
        }
        stopJobOnServiceContextLocked(cancelled, 0, reason);
        if (incomingJob != null) {
            if (DEBUG) {
                Slog.i(TAG, "Tracking replacement job " + incomingJob.toShortString());
            }
            startTrackingJobLocked(incomingJob, cancelled);
        }
        reportActiveLocked();
    }

    void updateUidState(int uid, int procState) {
        synchronized (this.mLock) {
            try {
                if (procState == 2) {
                    this.mUidPriorityOverride.put(uid, 40);
                } else if (procState <= 4) {
                    this.mUidPriorityOverride.put(uid, 30);
                } else {
                    this.mUidPriorityOverride.delete(uid);
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    @Override // com.android.server.job.StateChangedListener
    public void onDeviceIdleStateChanged(boolean deviceIdle) {
        synchronized (this.mLock) {
            if (deviceIdle) {
                for (int i = 0; i < this.mActiveServices.size(); i++) {
                    JobServiceContext jsc = this.mActiveServices.get(i);
                    JobStatus executing = jsc.getRunningJobLocked();
                    if (executing != null && (executing.getFlags() & 1) == 0) {
                        jsc.cancelExecutingJobLocked(4, "cancelled due to doze");
                    }
                }
            } else if (this.mReadyToRock) {
                if (this.mLocalDeviceIdleController != null && !this.mReportedActive) {
                    this.mReportedActive = true;
                    this.mLocalDeviceIdleController.setJobsActive(true);
                }
                this.mHandler.obtainMessage(1).sendToTarget();
            }
        }
    }

    void reportActiveLocked() {
        int i = 0;
        boolean active = this.mPendingJobs.size() > 0;
        if (this.mPendingJobs.size() <= 0) {
            while (true) {
                if (i >= this.mActiveServices.size()) {
                    break;
                }
                JobServiceContext jsc = this.mActiveServices.get(i);
                JobStatus job = jsc.getRunningJobLocked();
                if (job == null || (job.getJob().getFlags() & 1) != 0 || job.dozeWhitelisted || job.uidActive) {
                    i++;
                } else {
                    active = true;
                    break;
                }
            }
        }
        if (this.mReportedActive != active) {
            this.mReportedActive = active;
            if (this.mLocalDeviceIdleController != null) {
                this.mLocalDeviceIdleController.setJobsActive(active);
            }
        }
    }

    void reportAppUsage(String packageName, int userId) {
    }

    public JobSchedulerService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mJobPackageTracker = new JobPackageTracker();
        this.mActiveServices = new ArrayList();
        this.mPendingJobs = new ArrayList<>();
        this.mStartedUsers = EmptyArray.INT;
        this.mMaxActiveJobs = 1;
        this.mUidPriorityOverride = new SparseIntArray();
        this.mBackingUpUids = new SparseIntArray();
        this.mNextBucketHeartbeat = new long[]{0, 0, 0, 0, JobStatus.NO_LATEST_RUNTIME};
        this.mHeartbeat = 0L;
        this.mLastHeartbeatTime = sElapsedRealtimeClock.millis();
        this.mLastJobHeartbeats = new SparseArray<>();
        this.mHeartbeatAlarm = new HeartbeatAlarmListener();
        this.mTmpAssignContextIdToJobMap = new JobStatus[16];
        this.mTmpAssignAct = new boolean[16];
        this.mTmpAssignPreferredUidForContext = new int[16];
        this.mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.job.JobSchedulerService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                List<JobStatus> jobsForUid;
                String action = intent.getAction();
                if (JobSchedulerService.DEBUG) {
                    Slog.d(JobSchedulerService.TAG, "Receieved: " + action);
                }
                String pkgName = JobSchedulerService.this.getPackageName(intent);
                int pkgUid = intent.getIntExtra("android.intent.extra.UID", -1);
                if ("android.intent.action.PACKAGE_CHANGED".equals(action)) {
                    if (pkgName != null && pkgUid != -1) {
                        String[] changedComponents = intent.getStringArrayExtra("android.intent.extra.changed_component_name_list");
                        if (changedComponents != null) {
                            for (String component : changedComponents) {
                                if (component.equals(pkgName)) {
                                    if (JobSchedulerService.DEBUG) {
                                        Slog.d(JobSchedulerService.TAG, "Package state change: " + pkgName);
                                    }
                                    try {
                                        int userId = UserHandle.getUserId(pkgUid);
                                        IPackageManager pm = AppGlobals.getPackageManager();
                                        int state = pm.getApplicationEnabledSetting(pkgName, userId);
                                        if (state == 2 || state == 3) {
                                            if (JobSchedulerService.DEBUG) {
                                                Slog.d(JobSchedulerService.TAG, "Removing jobs for package " + pkgName + " in user " + userId);
                                            }
                                            JobSchedulerService.this.cancelJobsForPackageAndUid(pkgName, pkgUid, "app disabled");
                                            return;
                                        }
                                        return;
                                    } catch (RemoteException | IllegalArgumentException e) {
                                        return;
                                    }
                                }
                            }
                            return;
                        }
                        return;
                    }
                    Slog.w(JobSchedulerService.TAG, "PACKAGE_CHANGED for " + pkgName + " / uid " + pkgUid);
                } else if ("android.intent.action.PACKAGE_REMOVED".equals(action)) {
                    if (!intent.getBooleanExtra("android.intent.extra.REPLACING", false)) {
                        int uidRemoved = intent.getIntExtra("android.intent.extra.UID", -1);
                        if (JobSchedulerService.DEBUG) {
                            Slog.d(JobSchedulerService.TAG, "Removing jobs for uid: " + uidRemoved);
                        }
                        JobSchedulerService.this.cancelJobsForPackageAndUid(pkgName, uidRemoved, "app uninstalled");
                    }
                } else if ("android.intent.action.USER_REMOVED".equals(action)) {
                    int userId2 = intent.getIntExtra("android.intent.extra.user_handle", 0);
                    if (JobSchedulerService.DEBUG) {
                        Slog.d(JobSchedulerService.TAG, "Removing jobs for user: " + userId2);
                    }
                    JobSchedulerService.this.cancelJobsForUser(userId2);
                } else if ("android.intent.action.QUERY_PACKAGE_RESTART".equals(action)) {
                    if (pkgUid != -1) {
                        synchronized (JobSchedulerService.this.mLock) {
                            jobsForUid = JobSchedulerService.this.mJobs.getJobsByUid(pkgUid);
                        }
                        for (int i = jobsForUid.size() - 1; i >= 0; i--) {
                            if (jobsForUid.get(i).getSourcePackageName().equals(pkgName)) {
                                if (JobSchedulerService.DEBUG) {
                                    Slog.d(JobSchedulerService.TAG, "Restart query: package " + pkgName + " at uid " + pkgUid + " has jobs");
                                }
                                setResultCode(-1);
                                return;
                            }
                        }
                    }
                } else if ("android.intent.action.PACKAGE_RESTARTED".equals(action) && pkgUid != -1) {
                    if (JobSchedulerService.DEBUG) {
                        Slog.d(JobSchedulerService.TAG, "Removing jobs for pkg " + pkgName + " at uid " + pkgUid);
                    }
                    JobSchedulerService.this.cancelJobsForPackageAndUid(pkgName, pkgUid, "app force stopped");
                }
            }
        };
        this.mUidObserver = new IUidObserver.Stub() { // from class: com.android.server.job.JobSchedulerService.2
            public void onUidStateChanged(int uid, int procState, long procStateSeq) {
                JobSchedulerService.this.mHandler.obtainMessage(4, uid, procState).sendToTarget();
            }

            public void onUidGone(int uid, boolean disabled) {
                JobSchedulerService.this.mHandler.obtainMessage(5, uid, disabled ? 1 : 0).sendToTarget();
            }

            public void onUidActive(int uid) throws RemoteException {
                JobSchedulerService.this.mHandler.obtainMessage(6, uid, 0).sendToTarget();
            }

            public void onUidIdle(int uid, boolean disabled) {
                JobSchedulerService.this.mHandler.obtainMessage(7, uid, disabled ? 1 : 0).sendToTarget();
            }

            public void onUidCachedChanged(int uid, boolean cached) {
            }
        };
        this.mIsUidActivePredicate = new Predicate() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$AauD0it1BcgWldVm_V1m2Jo7_Zc
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isUidActive;
                isUidActive = JobSchedulerService.this.isUidActive(((Integer) obj).intValue());
                return isUidActive;
            }
        };
        this.mTimeSetReceiver = new BroadcastReceiver() { // from class: com.android.server.job.JobSchedulerService.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.TIME_SET".equals(intent.getAction()) && JobSchedulerService.this.mJobs.clockNowValidToInflate(JobSchedulerService.sSystemClock.millis())) {
                    Slog.i(JobSchedulerService.TAG, "RTC now valid; recalculating persisted job windows");
                    context2.unregisterReceiver(this);
                    FgThread.getHandler().post(JobSchedulerService.this.mJobTimeUpdater);
                }
            }
        };
        this.mJobTimeUpdater = new Runnable() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$nXpbkYDrU0yC5DuTafFiblXBdTY
            @Override // java.lang.Runnable
            public final void run() {
                JobSchedulerService.lambda$new$1(JobSchedulerService.this);
            }
        };
        this.mReadyQueueFunctor = new ReadyJobQueueFunctor();
        this.mMaybeQueueFunctor = new MaybeReadyJobQueueFunctor();
        this.mLocalPM = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        this.mActivityManagerInternal = (ActivityManagerInternal) Preconditions.checkNotNull((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class));
        this.mHandler = new JobHandler(context.getMainLooper());
        this.mConstants = new Constants();
        this.mConstantsObserver = new ConstantsObserver(this.mHandler);
        this.mJobSchedulerStub = new JobSchedulerStub();
        this.mStandbyTracker = new StandbyTracker();
        this.mUsageStats = (UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class);
        this.mUsageStats.addAppIdleStateChangeListener(this.mStandbyTracker);
        publishLocalService(JobSchedulerInternal.class, new LocalService());
        this.mJobs = JobStore.initAndGet(this);
        this.mControllers = new ArrayList();
        this.mControllers.add(new ConnectivityController(this));
        this.mControllers.add(new TimeController(this));
        this.mControllers.add(new IdleController(this));
        this.mBatteryController = new BatteryController(this);
        this.mControllers.add(this.mBatteryController);
        this.mStorageController = new StorageController(this);
        this.mControllers.add(this.mStorageController);
        this.mControllers.add(new BackgroundJobsController(this));
        this.mControllers.add(new ContentObserverController(this));
        this.mDeviceIdleJobsController = new DeviceIdleJobsController(this);
        this.mControllers.add(this.mDeviceIdleJobsController);
        if (!this.mJobs.jobTimesInflatedValid()) {
            Slog.w(TAG, "!!! RTC not yet good; tracking time updates for job scheduling");
            context.registerReceiver(this.mTimeSetReceiver, new IntentFilter("android.intent.action.TIME_SET"));
        }
    }

    public static /* synthetic */ void lambda$new$1(JobSchedulerService jobSchedulerService) {
        ArrayList<JobStatus> toRemove = new ArrayList<>();
        ArrayList<JobStatus> toAdd = new ArrayList<>();
        synchronized (jobSchedulerService.mLock) {
            jobSchedulerService.getJobStore().getRtcCorrectedJobsLocked(toAdd, toRemove);
            int N = toAdd.size();
            for (int i = 0; i < N; i++) {
                JobStatus oldJob = toRemove.get(i);
                JobStatus newJob = toAdd.get(i);
                if (DEBUG) {
                    Slog.v(TAG, "  replacing " + oldJob + " with " + newJob);
                }
                jobSchedulerService.cancelJobImplLocked(oldJob, newJob, "deferred rtc calculation");
            }
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("jobscheduler", this.mJobSchedulerStub);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (500 != phase) {
            if (phase == 600) {
                synchronized (this.mLock) {
                    this.mReadyToRock = true;
                    this.mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batterystats"));
                    this.mLocalDeviceIdleController = (DeviceIdleController.LocalService) LocalServices.getService(DeviceIdleController.LocalService.class);
                    for (int i = 0; i < 16; i++) {
                        this.mActiveServices.add(new JobServiceContext(this, this.mBatteryStats, this.mJobPackageTracker, getContext().getMainLooper()));
                    }
                    this.mJobs.forEachJob(new Consumer() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$Lfddr1PhKRLtm92W7niRGMWO69M
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            JobSchedulerService.lambda$onBootPhase$2(JobSchedulerService.this, (JobStatus) obj);
                        }
                    });
                    this.mHandler.obtainMessage(1).sendToTarget();
                }
                return;
            }
            return;
        }
        this.mConstantsObserver.start(getContext().getContentResolver());
        this.mAppStateTracker = (AppStateTracker) Preconditions.checkNotNull((AppStateTracker) LocalServices.getService(AppStateTracker.class));
        setNextHeartbeatAlarm();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addAction("android.intent.action.PACKAGE_CHANGED");
        filter.addAction("android.intent.action.PACKAGE_RESTARTED");
        filter.addAction("android.intent.action.QUERY_PACKAGE_RESTART");
        filter.addDataScheme("package");
        getContext().registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        IntentFilter userFilter = new IntentFilter("android.intent.action.USER_REMOVED");
        getContext().registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);
        try {
            ActivityManager.getService().registerUidObserver(this.mUidObserver, 15, -1, (String) null);
        } catch (RemoteException e) {
        }
        cancelJobsForNonExistentUsers();
    }

    public static /* synthetic */ void lambda$onBootPhase$2(JobSchedulerService jobSchedulerService, JobStatus job) {
        for (int controller = 0; controller < jobSchedulerService.mControllers.size(); controller++) {
            StateController sc = jobSchedulerService.mControllers.get(controller);
            sc.maybeStartTrackingJobLocked(job, null);
        }
    }

    private void startTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (!jobStatus.isPreparedLocked()) {
            Slog.wtf(TAG, "Not yet prepared when started tracking: " + jobStatus);
        }
        jobStatus.enqueueTime = sElapsedRealtimeClock.millis();
        boolean update = this.mJobs.add(jobStatus);
        if (this.mReadyToRock) {
            for (int i = 0; i < this.mControllers.size(); i++) {
                StateController controller = this.mControllers.get(i);
                if (update) {
                    controller.maybeStopTrackingJobLocked(jobStatus, null, true);
                }
                controller.maybeStartTrackingJobLocked(jobStatus, lastJob);
            }
        }
    }

    private boolean stopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob, boolean writeBack) {
        jobStatus.stopTrackingJobLocked(ActivityManager.getService(), incomingJob);
        boolean removed = this.mJobs.remove(jobStatus, writeBack);
        if (removed && this.mReadyToRock) {
            for (int i = 0; i < this.mControllers.size(); i++) {
                StateController controller = this.mControllers.get(i);
                controller.maybeStopTrackingJobLocked(jobStatus, incomingJob, false);
            }
        }
        return removed;
    }

    private boolean stopJobOnServiceContextLocked(JobStatus job, int reason, String debugReason) {
        for (int i = 0; i < this.mActiveServices.size(); i++) {
            JobServiceContext jsc = this.mActiveServices.get(i);
            JobStatus executing = jsc.getRunningJobLocked();
            if (executing != null && executing.matches(job.getUid(), job.getJobId())) {
                jsc.cancelExecutingJobLocked(reason, debugReason);
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isCurrentlyActiveLocked(JobStatus job) {
        for (int i = 0; i < this.mActiveServices.size(); i++) {
            JobServiceContext serviceContext = this.mActiveServices.get(i);
            JobStatus running = serviceContext.getRunningJobLocked();
            if (running != null && running.matches(job.getUid(), job.getJobId())) {
                return true;
            }
        }
        return false;
    }

    void noteJobsPending(List<JobStatus> jobs) {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            JobStatus job = jobs.get(i);
            this.mJobPackageTracker.notePending(job);
        }
    }

    void noteJobsNonpending(List<JobStatus> jobs) {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            JobStatus job = jobs.get(i);
            this.mJobPackageTracker.noteNonpending(job);
        }
    }

    private JobStatus getRescheduleJobForFailureLocked(JobStatus failureToReschedule) {
        long backoff;
        long elapsedNowMillis = sElapsedRealtimeClock.millis();
        JobInfo job = failureToReschedule.getJob();
        long initialBackoffMillis = job.getInitialBackoffMillis();
        int backoffAttempts = failureToReschedule.getNumFailures() + 1;
        if (failureToReschedule.hasWorkLocked()) {
            if (backoffAttempts > this.mConstants.MAX_WORK_RESCHEDULE_COUNT) {
                Slog.w(TAG, "Not rescheduling " + failureToReschedule + ": attempt #" + backoffAttempts + " > work limit " + this.mConstants.MAX_STANDARD_RESCHEDULE_COUNT);
                return null;
            }
        } else if (backoffAttempts > this.mConstants.MAX_STANDARD_RESCHEDULE_COUNT) {
            Slog.w(TAG, "Not rescheduling " + failureToReschedule + ": attempt #" + backoffAttempts + " > std limit " + this.mConstants.MAX_STANDARD_RESCHEDULE_COUNT);
            return null;
        }
        switch (job.getBackoffPolicy()) {
            case 0:
                long backoff2 = initialBackoffMillis;
                if (backoff2 < this.mConstants.MIN_LINEAR_BACKOFF_TIME) {
                    backoff2 = this.mConstants.MIN_LINEAR_BACKOFF_TIME;
                }
                backoff = backoff2 * backoffAttempts;
                break;
            default:
                if (DEBUG) {
                    Slog.v(TAG, "Unrecognised back-off policy, defaulting to exponential.");
                }
            case 1:
                long backoff3 = initialBackoffMillis;
                if (backoff3 < this.mConstants.MIN_EXP_BACKOFF_TIME) {
                    backoff3 = this.mConstants.MIN_EXP_BACKOFF_TIME;
                }
                backoff = Math.scalb((float) backoff3, backoffAttempts - 1);
                break;
        }
        long delayMillis = Math.min(backoff, 18000000L);
        JobStatus newJob = new JobStatus(failureToReschedule, getCurrentHeartbeat(), elapsedNowMillis + delayMillis, JobStatus.NO_LATEST_RUNTIME, backoffAttempts, failureToReschedule.getLastSuccessfulRunTime(), sSystemClock.millis());
        for (int ic = 0; ic < this.mControllers.size(); ic++) {
            StateController controller = this.mControllers.get(ic);
            controller.rescheduleForFailureLocked(newJob, failureToReschedule);
        }
        return newJob;
    }

    private JobStatus getRescheduleJobForPeriodic(JobStatus periodicToReschedule) {
        long elapsedNow = sElapsedRealtimeClock.millis();
        long runEarly = 0;
        if (periodicToReschedule.hasDeadlineConstraint()) {
            runEarly = Math.max(periodicToReschedule.getLatestRunTimeElapsed() - elapsedNow, 0L);
        }
        long flex = periodicToReschedule.getJob().getFlexMillis();
        long period = periodicToReschedule.getJob().getIntervalMillis();
        long newLatestRuntimeElapsed = elapsedNow + runEarly + period;
        long newEarliestRunTimeElapsed = newLatestRuntimeElapsed - flex;
        if (DEBUG) {
            Slog.v(TAG, "Rescheduling executed periodic. New execution window [" + (newEarliestRunTimeElapsed / 1000) + ", " + (newLatestRuntimeElapsed / 1000) + "]s");
        }
        return new JobStatus(periodicToReschedule, getCurrentHeartbeat(), newEarliestRunTimeElapsed, newLatestRuntimeElapsed, 0, sSystemClock.millis(), periodicToReschedule.getLastFailedRunTime());
    }

    long heartbeatWhenJobsLastRun(String packageName, int userId) {
        long heartbeat = -this.mConstants.STANDBY_BEATS[3];
        boolean cacheHit = false;
        synchronized (this.mLock) {
            HashMap<String, Long> jobPackages = this.mLastJobHeartbeats.get(userId);
            if (jobPackages != null) {
                long cachedValue = jobPackages.getOrDefault(packageName, Long.valueOf((long) JobStatus.NO_LATEST_RUNTIME)).longValue();
                if (cachedValue < JobStatus.NO_LATEST_RUNTIME) {
                    cacheHit = true;
                    heartbeat = cachedValue;
                }
            }
            if (!cacheHit) {
                long timeSinceJob = this.mUsageStats.getTimeSinceLastJobRun(packageName, userId);
                if (timeSinceJob < JobStatus.NO_LATEST_RUNTIME) {
                    heartbeat = this.mHeartbeat - (timeSinceJob / this.mConstants.STANDBY_HEARTBEAT_TIME);
                }
                setLastJobHeartbeatLocked(packageName, userId, heartbeat);
            }
        }
        if (DEBUG_STANDBY) {
            Slog.v(TAG, "Last job heartbeat " + heartbeat + " for " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + userId);
        }
        return heartbeat;
    }

    long heartbeatWhenJobsLastRun(JobStatus job) {
        return heartbeatWhenJobsLastRun(job.getSourcePackageName(), job.getSourceUserId());
    }

    void setLastJobHeartbeatLocked(String packageName, int userId, long heartbeat) {
        HashMap<String, Long> jobPackages = this.mLastJobHeartbeats.get(userId);
        if (jobPackages == null) {
            jobPackages = new HashMap<>();
            this.mLastJobHeartbeats.put(userId, jobPackages);
        }
        jobPackages.put(packageName, Long.valueOf(heartbeat));
    }

    @Override // com.android.server.job.JobCompletedListener
    public void onJobCompletedLocked(JobStatus jobStatus, boolean needsReschedule) {
        if (DEBUG) {
            Slog.d(TAG, "Completed " + jobStatus + ", reschedule=" + needsReschedule);
        }
        JobStatus rescheduledJob = needsReschedule ? getRescheduleJobForFailureLocked(jobStatus) : null;
        if (!stopTrackingJobLocked(jobStatus, rescheduledJob, !jobStatus.getJob().isPeriodic())) {
            if (DEBUG) {
                Slog.d(TAG, "Could not find job to remove. Was job removed while executing?");
            }
            this.mHandler.obtainMessage(3).sendToTarget();
            return;
        }
        if (rescheduledJob != null) {
            try {
                rescheduledJob.prepareLocked(ActivityManager.getService());
            } catch (SecurityException e) {
                Slog.w(TAG, "Unable to regrant job permissions for " + rescheduledJob);
            }
            startTrackingJobLocked(rescheduledJob, jobStatus);
        } else if (jobStatus.getJob().isPeriodic()) {
            JobStatus rescheduledPeriodic = getRescheduleJobForPeriodic(jobStatus);
            try {
                rescheduledPeriodic.prepareLocked(ActivityManager.getService());
            } catch (SecurityException e2) {
                Slog.w(TAG, "Unable to regrant job permissions for " + rescheduledPeriodic);
            }
            startTrackingJobLocked(rescheduledPeriodic, jobStatus);
        }
        jobStatus.unprepareLocked(ActivityManager.getService());
        reportActiveLocked();
        this.mHandler.obtainMessage(3).sendToTarget();
    }

    @Override // com.android.server.job.StateChangedListener
    public void onControllerStateChanged() {
        this.mHandler.obtainMessage(1).sendToTarget();
    }

    @Override // com.android.server.job.StateChangedListener
    public void onRunJobNow(JobStatus jobStatus) {
        this.mHandler.obtainMessage(0, jobStatus).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class JobHandler extends Handler {
        public JobHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            synchronized (JobSchedulerService.this.mLock) {
                if (JobSchedulerService.this.mReadyToRock) {
                    switch (message.what) {
                        case 0:
                            JobStatus runNow = (JobStatus) message.obj;
                            if (runNow == null || !JobSchedulerService.this.isReadyToBeExecutedLocked(runNow)) {
                                JobSchedulerService.this.queueReadyJobsForExecutionLocked();
                                break;
                            } else {
                                JobSchedulerService.this.mJobPackageTracker.notePending(runNow);
                                JobSchedulerService.addOrderedItem(JobSchedulerService.this.mPendingJobs, runNow, JobSchedulerService.mEnqueueTimeComparator);
                                break;
                            }
                            break;
                        case 1:
                            if (JobSchedulerService.this.mReportedActive) {
                                JobSchedulerService.this.queueReadyJobsForExecutionLocked();
                                break;
                            } else {
                                JobSchedulerService.this.maybeQueueReadyJobsForExecutionLocked();
                                break;
                            }
                        case 2:
                            JobSchedulerService.this.cancelJobImplLocked((JobStatus) message.obj, null, "app no longer allowed to run");
                            break;
                        case 3:
                            JobSchedulerService.this.queueReadyJobsForExecutionLocked();
                            break;
                        case 4:
                            int uid = message.arg1;
                            int procState = message.arg2;
                            JobSchedulerService.this.updateUidState(uid, procState);
                            break;
                        case 5:
                            int uid2 = message.arg1;
                            boolean disabled = message.arg2 != 0;
                            JobSchedulerService.this.updateUidState(uid2, 18);
                            if (disabled) {
                                JobSchedulerService.this.cancelJobsForUid(uid2, "uid gone");
                            }
                            synchronized (JobSchedulerService.this.mLock) {
                                JobSchedulerService.this.mDeviceIdleJobsController.setUidActiveLocked(uid2, false);
                            }
                            break;
                        case 6:
                            int uid3 = message.arg1;
                            synchronized (JobSchedulerService.this.mLock) {
                                JobSchedulerService.this.mDeviceIdleJobsController.setUidActiveLocked(uid3, true);
                            }
                            break;
                        case 7:
                            int uid4 = message.arg1;
                            boolean disabled2 = message.arg2 != 0;
                            if (disabled2) {
                                JobSchedulerService.this.cancelJobsForUid(uid4, "app uid idle");
                            }
                            synchronized (JobSchedulerService.this.mLock) {
                                JobSchedulerService.this.mDeviceIdleJobsController.setUidActiveLocked(uid4, false);
                            }
                            break;
                    }
                    JobSchedulerService.this.maybeRunPendingJobsLocked();
                    removeMessages(1);
                }
            }
        }
    }

    private void stopNonReadyActiveJobsLocked() {
        for (int i = 0; i < this.mActiveServices.size(); i++) {
            JobServiceContext serviceContext = this.mActiveServices.get(i);
            JobStatus running = serviceContext.getRunningJobLocked();
            if (running != null && !running.isReady()) {
                serviceContext.cancelExecutingJobLocked(1, "cancelled due to unsatisfied constraints");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void queueReadyJobsForExecutionLocked() {
        if (DEBUG) {
            Slog.d(TAG, "queuing all ready jobs for execution:");
        }
        noteJobsNonpending(this.mPendingJobs);
        this.mPendingJobs.clear();
        stopNonReadyActiveJobsLocked();
        this.mJobs.forEachJob(this.mReadyQueueFunctor);
        this.mReadyQueueFunctor.postProcess();
        if (DEBUG) {
            int queuedJobs = this.mPendingJobs.size();
            if (queuedJobs == 0) {
                Slog.d(TAG, "No jobs pending.");
                return;
            }
            Slog.d(TAG, queuedJobs + " jobs queued.");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ReadyJobQueueFunctor implements Consumer<JobStatus> {
        ArrayList<JobStatus> newReadyJobs;

        ReadyJobQueueFunctor() {
        }

        @Override // java.util.function.Consumer
        public void accept(JobStatus job) {
            if (JobSchedulerService.this.isReadyToBeExecutedLocked(job)) {
                if (JobSchedulerService.DEBUG) {
                    Slog.d(JobSchedulerService.TAG, "    queued " + job.toShortString());
                }
                if (this.newReadyJobs == null) {
                    this.newReadyJobs = new ArrayList<>();
                }
                this.newReadyJobs.add(job);
            }
        }

        public void postProcess() {
            if (this.newReadyJobs != null) {
                JobSchedulerService.this.noteJobsPending(this.newReadyJobs);
                JobSchedulerService.this.mPendingJobs.addAll(this.newReadyJobs);
                if (JobSchedulerService.this.mPendingJobs.size() > 1) {
                    JobSchedulerService.this.mPendingJobs.sort(JobSchedulerService.mEnqueueTimeComparator);
                }
            }
            this.newReadyJobs = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class MaybeReadyJobQueueFunctor implements Consumer<JobStatus> {
        int backoffCount;
        int batteryNotLowCount;
        int chargingCount;
        int connectivityCount;
        int contentCount;
        int idleCount;
        List<JobStatus> runnableJobs;
        int storageNotLowCount;

        public MaybeReadyJobQueueFunctor() {
            reset();
        }

        @Override // java.util.function.Consumer
        public void accept(JobStatus job) {
            if (JobSchedulerService.this.isReadyToBeExecutedLocked(job)) {
                try {
                    if (ActivityManager.getService().isAppStartModeDisabled(job.getUid(), job.getJob().getService().getPackageName())) {
                        Slog.w(JobSchedulerService.TAG, "Aborting job " + job.getUid() + ":" + job.getJob().toString() + " -- package not allowed to start");
                        JobSchedulerService.this.mHandler.obtainMessage(2, job).sendToTarget();
                        return;
                    }
                } catch (RemoteException e) {
                }
                if (job.getNumFailures() > 0) {
                    this.backoffCount++;
                }
                if (job.hasIdleConstraint()) {
                    this.idleCount++;
                }
                if (job.hasConnectivityConstraint()) {
                    this.connectivityCount++;
                }
                if (job.hasChargingConstraint()) {
                    this.chargingCount++;
                }
                if (job.hasBatteryNotLowConstraint()) {
                    this.batteryNotLowCount++;
                }
                if (job.hasStorageNotLowConstraint()) {
                    this.storageNotLowCount++;
                }
                if (job.hasContentTriggerConstraint()) {
                    this.contentCount++;
                }
                if (this.runnableJobs == null) {
                    this.runnableJobs = new ArrayList();
                }
                this.runnableJobs.add(job);
            }
        }

        public void postProcess() {
            if (this.backoffCount > 0 || this.idleCount >= JobSchedulerService.this.mConstants.MIN_IDLE_COUNT || this.connectivityCount >= JobSchedulerService.this.mConstants.MIN_CONNECTIVITY_COUNT || this.chargingCount >= JobSchedulerService.this.mConstants.MIN_CHARGING_COUNT || this.batteryNotLowCount >= JobSchedulerService.this.mConstants.MIN_BATTERY_NOT_LOW_COUNT || this.storageNotLowCount >= JobSchedulerService.this.mConstants.MIN_STORAGE_NOT_LOW_COUNT || this.contentCount >= JobSchedulerService.this.mConstants.MIN_CONTENT_COUNT || (this.runnableJobs != null && this.runnableJobs.size() >= JobSchedulerService.this.mConstants.MIN_READY_JOBS_COUNT)) {
                if (JobSchedulerService.DEBUG) {
                    Slog.d(JobSchedulerService.TAG, "maybeQueueReadyJobsForExecutionLocked: Running jobs.");
                }
                JobSchedulerService.this.noteJobsPending(this.runnableJobs);
                JobSchedulerService.this.mPendingJobs.addAll(this.runnableJobs);
                if (JobSchedulerService.this.mPendingJobs.size() > 1) {
                    JobSchedulerService.this.mPendingJobs.sort(JobSchedulerService.mEnqueueTimeComparator);
                }
            } else if (JobSchedulerService.DEBUG) {
                Slog.d(JobSchedulerService.TAG, "maybeQueueReadyJobsForExecutionLocked: Not running anything.");
            }
            reset();
        }

        private void reset() {
            this.chargingCount = 0;
            this.idleCount = 0;
            this.backoffCount = 0;
            this.connectivityCount = 0;
            this.batteryNotLowCount = 0;
            this.storageNotLowCount = 0;
            this.contentCount = 0;
            this.runnableJobs = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeQueueReadyJobsForExecutionLocked() {
        if (DEBUG) {
            Slog.d(TAG, "Maybe queuing ready jobs...");
        }
        noteJobsNonpending(this.mPendingJobs);
        this.mPendingJobs.clear();
        stopNonReadyActiveJobsLocked();
        this.mJobs.forEachJob(this.mMaybeQueueFunctor);
        this.mMaybeQueueFunctor.postProcess();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class HeartbeatAlarmListener implements AlarmManager.OnAlarmListener {
        HeartbeatAlarmListener() {
        }

        @Override // android.app.AlarmManager.OnAlarmListener
        public void onAlarm() {
            synchronized (JobSchedulerService.this.mLock) {
                long sinceLast = JobSchedulerService.sElapsedRealtimeClock.millis() - JobSchedulerService.this.mLastHeartbeatTime;
                long beatsElapsed = sinceLast / JobSchedulerService.this.mConstants.STANDBY_HEARTBEAT_TIME;
                if (beatsElapsed > 0) {
                    JobSchedulerService.this.mLastHeartbeatTime += JobSchedulerService.this.mConstants.STANDBY_HEARTBEAT_TIME * beatsElapsed;
                    JobSchedulerService.this.advanceHeartbeatLocked(beatsElapsed);
                }
            }
            JobSchedulerService.this.setNextHeartbeatAlarm();
        }
    }

    void advanceHeartbeatLocked(long beatsElapsed) {
        this.mHeartbeat += beatsElapsed;
        if (DEBUG_STANDBY) {
            Slog.v(TAG, "Advancing standby heartbeat by " + beatsElapsed + " to " + this.mHeartbeat);
        }
        boolean didAdvanceBucket = false;
        for (int i = 1; i < this.mNextBucketHeartbeat.length - 1; i++) {
            if (this.mHeartbeat >= this.mNextBucketHeartbeat[i]) {
                didAdvanceBucket = true;
            }
            while (this.mHeartbeat > this.mNextBucketHeartbeat[i]) {
                long[] jArr = this.mNextBucketHeartbeat;
                jArr[i] = jArr[i] + this.mConstants.STANDBY_BEATS[i];
            }
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "   Bucket " + i + " next heartbeat " + this.mNextBucketHeartbeat[i]);
            }
        }
        if (didAdvanceBucket) {
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "Hit bucket boundary; reevaluating job runnability");
            }
            this.mHandler.obtainMessage(1).sendToTarget();
        }
    }

    void setNextHeartbeatAlarm() {
        long heartbeatLength;
        synchronized (this.mLock) {
            heartbeatLength = this.mConstants.STANDBY_HEARTBEAT_TIME;
        }
        long now = sElapsedRealtimeClock.millis();
        long nextBeatOrdinal = (now + heartbeatLength) / heartbeatLength;
        long nextHeartbeat = nextBeatOrdinal * heartbeatLength;
        if (DEBUG_STANDBY) {
            Slog.i(TAG, "Setting heartbeat alarm for " + nextHeartbeat + " = " + TimeUtils.formatDuration(nextHeartbeat - now));
        }
        AlarmManager am = (AlarmManager) getContext().getSystemService("alarm");
        am.setExact(3, nextHeartbeat, HEARTBEAT_TAG, this.mHeartbeatAlarm, this.mHandler);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isReadyToBeExecutedLocked(JobStatus job) {
        boolean jobReady = job.isReady();
        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString() + " ready=" + jobReady);
        }
        if (!jobReady) {
            if (job.getSourcePackageName().equals("android.jobscheduler.cts.jobtestapp")) {
                Slog.v(TAG, "    NOT READY: " + job);
            }
            return false;
        }
        boolean jobExists = this.mJobs.containsJob(job);
        int userId = job.getUserId();
        boolean userStarted = ArrayUtils.contains(this.mStartedUsers, userId);
        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString() + " exists=" + jobExists + " userStarted=" + userStarted);
        }
        if (!jobExists || !userStarted) {
            return false;
        }
        boolean jobPending = this.mPendingJobs.contains(job);
        boolean jobActive = isCurrentlyActiveLocked(job);
        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString() + " pending=" + jobPending + " active=" + jobActive);
        }
        if (jobPending || jobActive) {
            return false;
        }
        if (DEBUG_STANDBY) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString() + " parole=" + this.mInParole + " active=" + job.uidActive + " exempt=" + job.getJob().isExemptedFromAppStandby());
        }
        if (!this.mInParole && !job.uidActive && !job.getJob().isExemptedFromAppStandby()) {
            int bucket = job.getStandbyBucket();
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "  bucket=" + bucket + " heartbeat=" + this.mHeartbeat + " next=" + this.mNextBucketHeartbeat[bucket]);
            }
            if (this.mHeartbeat < this.mNextBucketHeartbeat[bucket]) {
                long appLastRan = heartbeatWhenJobsLastRun(job);
                if (bucket >= this.mConstants.STANDBY_BEATS.length || (this.mHeartbeat > appLastRan && this.mHeartbeat < this.mConstants.STANDBY_BEATS[bucket] + appLastRan)) {
                    if (job.getWhenStandbyDeferred() == 0) {
                        if (DEBUG_STANDBY) {
                            Slog.v(TAG, "Bucket deferral: " + this.mHeartbeat + " < " + (this.mConstants.STANDBY_BEATS[bucket] + appLastRan) + " for " + job);
                        }
                        job.setWhenStandbyDeferred(sElapsedRealtimeClock.millis());
                    }
                    return false;
                } else if (DEBUG_STANDBY) {
                    Slog.v(TAG, "Bucket deferred job aged into runnability at " + this.mHeartbeat + " : " + job);
                }
            }
        }
        try {
            boolean componentPresent = AppGlobals.getPackageManager().getServiceInfo(job.getServiceComponent(), 268435456, userId) != null;
            if (DEBUG) {
                Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString() + " componentPresent=" + componentPresent);
            }
            return componentPresent;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeRunPendingJobsLocked() {
        if (DEBUG) {
            Slog.d(TAG, "pending queue: " + this.mPendingJobs.size() + " jobs.");
        }
        assignJobsToContextsLocked();
        reportActiveLocked();
    }

    private int adjustJobPriority(int curPriority, JobStatus job) {
        if (curPriority < 40) {
            float factor = this.mJobPackageTracker.getLoadFactor(job);
            if (factor >= this.mConstants.HEAVY_USE_FACTOR) {
                return curPriority - 80;
            }
            if (factor >= this.mConstants.MODERATE_USE_FACTOR) {
                return curPriority - 40;
            }
            return curPriority;
        }
        return curPriority;
    }

    private int evaluateJobPriorityLocked(JobStatus job) {
        int priority = job.getPriority();
        if (priority >= 30) {
            return adjustJobPriority(priority, job);
        }
        int override = this.mUidPriorityOverride.get(job.getSourceUid(), 0);
        if (override != 0) {
            return adjustJobPriority(override, job);
        }
        return adjustJobPriority(priority, job);
    }

    /* JADX WARN: Removed duplicated region for block: B:63:0x0108  */
    /* JADX WARN: Removed duplicated region for block: B:66:0x0116  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void assignJobsToContextsLocked() {
        /*
            Method dump skipped, instructions count: 530
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.job.JobSchedulerService.assignJobsToContextsLocked():void");
    }

    int findJobContextIdFromMap(JobStatus jobStatus, JobStatus[] map) {
        for (int i = 0; i < map.length; i++) {
            if (map[i] != null && map[i].matches(jobStatus.getUid(), jobStatus.getJobId())) {
                return i;
            }
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class LocalService implements JobSchedulerInternal {
        LocalService() {
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public long currentHeartbeat() {
            return JobSchedulerService.this.getCurrentHeartbeat();
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public long nextHeartbeatForBucket(int bucket) {
            long j;
            synchronized (JobSchedulerService.this.mLock) {
                j = JobSchedulerService.this.mNextBucketHeartbeat[bucket];
            }
            return j;
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public long baseHeartbeatForApp(String packageName, int userId, int appStandbyBucket) {
            if (appStandbyBucket == 0 || appStandbyBucket >= JobSchedulerService.this.mConstants.STANDBY_BEATS.length) {
                if (JobSchedulerService.DEBUG_STANDBY) {
                    Slog.v(JobSchedulerService.TAG, "Base heartbeat forced ZERO for new job in " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + userId);
                    return 0L;
                }
                return 0L;
            }
            long baseHeartbeat = JobSchedulerService.this.heartbeatWhenJobsLastRun(packageName, userId);
            if (JobSchedulerService.DEBUG_STANDBY) {
                Slog.v(JobSchedulerService.TAG, "Base heartbeat " + baseHeartbeat + " for new job in " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + userId);
            }
            return baseHeartbeat;
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public void noteJobStart(String packageName, int userId) {
            synchronized (JobSchedulerService.this.mLock) {
                JobSchedulerService.this.setLastJobHeartbeatLocked(packageName, userId, JobSchedulerService.this.mHeartbeat);
            }
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public List<JobInfo> getSystemScheduledPendingJobs() {
            final List<JobInfo> pendingJobs;
            synchronized (JobSchedulerService.this.mLock) {
                pendingJobs = new ArrayList<>();
                JobSchedulerService.this.mJobs.forEachJob(1000, new Consumer() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$LocalService$yaChpLJ2odu2Fk7A6H8erUndrN8
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        JobSchedulerService.LocalService.lambda$getSystemScheduledPendingJobs$0(JobSchedulerService.LocalService.this, pendingJobs, (JobStatus) obj);
                    }
                });
            }
            return pendingJobs;
        }

        public static /* synthetic */ void lambda$getSystemScheduledPendingJobs$0(LocalService localService, List pendingJobs, JobStatus job) {
            if (job.getJob().isPeriodic() || !JobSchedulerService.this.isCurrentlyActiveLocked(job)) {
                pendingJobs.add(job.getJob());
            }
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public void cancelJobsForUid(int uid, String reason) {
            JobSchedulerService.this.cancelJobsForUid(uid, reason);
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public void addBackingUpUid(int uid) {
            synchronized (JobSchedulerService.this.mLock) {
                JobSchedulerService.this.mBackingUpUids.put(uid, uid);
            }
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public void removeBackingUpUid(int uid) {
            synchronized (JobSchedulerService.this.mLock) {
                JobSchedulerService.this.mBackingUpUids.delete(uid);
                if (JobSchedulerService.this.mJobs.countJobsForUid(uid) > 0) {
                    JobSchedulerService.this.mHandler.obtainMessage(1).sendToTarget();
                }
            }
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public void clearAllBackingUpUids() {
            synchronized (JobSchedulerService.this.mLock) {
                if (JobSchedulerService.this.mBackingUpUids.size() > 0) {
                    JobSchedulerService.this.mBackingUpUids.clear();
                    JobSchedulerService.this.mHandler.obtainMessage(1).sendToTarget();
                }
            }
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public void reportAppUsage(String packageName, int userId) {
            JobSchedulerService.this.reportAppUsage(packageName, userId);
        }

        @Override // com.android.server.job.JobSchedulerInternal
        public JobSchedulerInternal.JobStorePersistStats getPersistStats() {
            JobSchedulerInternal.JobStorePersistStats jobStorePersistStats;
            synchronized (JobSchedulerService.this.mLock) {
                jobStorePersistStats = new JobSchedulerInternal.JobStorePersistStats(JobSchedulerService.this.mJobs.getPersistStats());
            }
            return jobStorePersistStats;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class StandbyTracker extends UsageStatsManagerInternal.AppIdleStateChangeListener {
        StandbyTracker() {
        }

        public void onAppIdleStateChanged(final String packageName, int userId, boolean idle, int bucket, int reason) {
            final int uid = JobSchedulerService.this.mLocalPM.getPackageUid(packageName, 8192, userId);
            if (uid < 0) {
                if (JobSchedulerService.DEBUG_STANDBY) {
                    Slog.i(JobSchedulerService.TAG, "App idle state change for unknown app " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + userId);
                    return;
                }
                return;
            }
            final int bucketIndex = JobSchedulerService.standbyBucketToBucketIndex(bucket);
            BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$StandbyTracker$18Nt1smLe-l9bimlwR39k5RbMdM
                @Override // java.lang.Runnable
                public final void run() {
                    JobSchedulerService.StandbyTracker.lambda$onAppIdleStateChanged$1(JobSchedulerService.StandbyTracker.this, uid, bucketIndex, packageName);
                }
            });
        }

        public static /* synthetic */ void lambda$onAppIdleStateChanged$1(StandbyTracker standbyTracker, int uid, final int bucketIndex, final String packageName) {
            if (JobSchedulerService.DEBUG_STANDBY) {
                Slog.i(JobSchedulerService.TAG, "Moving uid " + uid + " to bucketIndex " + bucketIndex);
            }
            synchronized (JobSchedulerService.this.mLock) {
                JobSchedulerService.this.mJobs.forEachJobForSourceUid(uid, new Consumer() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$StandbyTracker$Ofnn0P__SXhzXRU-7eLyuPrl31w
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        JobSchedulerService.StandbyTracker.lambda$onAppIdleStateChanged$0(packageName, bucketIndex, (JobStatus) obj);
                    }
                });
                JobSchedulerService.this.onControllerStateChanged();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$onAppIdleStateChanged$0(String packageName, int bucketIndex, JobStatus job) {
            if (packageName.equals(job.getSourcePackageName())) {
                job.setStandbyBucket(bucketIndex);
            }
        }

        public void onParoleStateChanged(boolean isParoleOn) {
            if (JobSchedulerService.DEBUG_STANDBY) {
                StringBuilder sb = new StringBuilder();
                sb.append("Global parole state now ");
                sb.append(isParoleOn ? "ON" : "OFF");
                Slog.i(JobSchedulerService.TAG, sb.toString());
            }
            JobSchedulerService.this.mInParole = isParoleOn;
        }

        public void onUserInteractionStarted(String packageName, int userId) {
            int uid = JobSchedulerService.this.mLocalPM.getPackageUid(packageName, 8192, userId);
            if (uid < 0) {
                return;
            }
            long sinceLast = JobSchedulerService.this.mUsageStats.getTimeSinceLastJobRun(packageName, userId);
            if (sinceLast > 172800000) {
                sinceLast = 0;
            }
            DeferredJobCounter counter = new DeferredJobCounter();
            synchronized (JobSchedulerService.this.mLock) {
                JobSchedulerService.this.mJobs.forEachJobForSourceUid(uid, counter);
            }
            if (counter.numDeferred() > 0 || sinceLast > 0) {
                BatteryStatsInternal mBatteryStatsInternal = (BatteryStatsInternal) LocalServices.getService(BatteryStatsInternal.class);
                mBatteryStatsInternal.noteJobsDeferred(uid, counter.numDeferred(), sinceLast);
            }
        }
    }

    /* loaded from: classes.dex */
    static class DeferredJobCounter implements Consumer<JobStatus> {
        private int mDeferred = 0;

        DeferredJobCounter() {
        }

        public int numDeferred() {
            return this.mDeferred;
        }

        @Override // java.util.function.Consumer
        public void accept(JobStatus job) {
            if (job.getWhenStandbyDeferred() > 0) {
                this.mDeferred++;
            }
        }
    }

    public static int standbyBucketToBucketIndex(int bucket) {
        if (bucket == 50) {
            return 4;
        }
        if (bucket > 30) {
            return 3;
        }
        if (bucket > 20) {
            return 2;
        }
        return bucket > 10 ? 1 : 0;
    }

    public static int standbyBucketForPackage(String packageName, int userId, long elapsedNow) {
        int bucket;
        UsageStatsManagerInternal usageStats = (UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class);
        if (usageStats != null) {
            bucket = usageStats.getAppStandbyBucket(packageName, userId, elapsedNow);
        } else {
            bucket = 0;
        }
        int bucket2 = standbyBucketToBucketIndex(bucket);
        if (DEBUG_STANDBY) {
            Slog.v(TAG, packageName + SliceClientPermissions.SliceAuthority.DELIMITER + userId + " standby bucket index: " + bucket2);
        }
        return bucket2;
    }

    /* loaded from: classes.dex */
    final class JobSchedulerStub extends IJobScheduler.Stub {
        private final SparseArray<Boolean> mPersistCache = new SparseArray<>();

        JobSchedulerStub() {
        }

        private void enforceValidJobRequest(int uid, JobInfo job) {
            IPackageManager pm = AppGlobals.getPackageManager();
            ComponentName service = job.getService();
            try {
                ServiceInfo si = pm.getServiceInfo(service, 786432, UserHandle.getUserId(uid));
                if (si == null) {
                    throw new IllegalArgumentException("No such service " + service);
                } else if (si.applicationInfo.uid != uid) {
                    throw new IllegalArgumentException("uid " + uid + " cannot schedule job in " + service.getPackageName());
                } else if (!"android.permission.BIND_JOB_SERVICE".equals(si.permission)) {
                    throw new IllegalArgumentException("Scheduled service " + service + " does not require android.permission.BIND_JOB_SERVICE permission");
                }
            } catch (RemoteException e) {
            }
        }

        private boolean canPersistJobs(int pid, int uid) {
            boolean canPersist;
            synchronized (this.mPersistCache) {
                Boolean cached = this.mPersistCache.get(uid);
                if (cached != null) {
                    canPersist = cached.booleanValue();
                } else {
                    int result = JobSchedulerService.this.getContext().checkPermission("android.permission.RECEIVE_BOOT_COMPLETED", pid, uid);
                    boolean canPersist2 = result == 0;
                    this.mPersistCache.put(uid, Boolean.valueOf(canPersist2));
                    canPersist = canPersist2;
                }
            }
            return canPersist;
        }

        private void validateJobFlags(JobInfo job, int callingUid) {
            if ((job.getFlags() & 1) != 0) {
                JobSchedulerService.this.getContext().enforceCallingOrSelfPermission("android.permission.CONNECTIVITY_INTERNAL", JobSchedulerService.TAG);
            }
            if ((job.getFlags() & 8) != 0) {
                if (callingUid != 1000) {
                    throw new SecurityException("Job has invalid flags");
                }
                if (job.isPeriodic()) {
                    Slog.wtf(JobSchedulerService.TAG, "Periodic jobs mustn't have FLAG_EXEMPT_FROM_APP_STANDBY. Job=" + job);
                }
            }
        }

        public int schedule(JobInfo job) throws RemoteException {
            if (JobSchedulerService.DEBUG) {
                Slog.d(JobSchedulerService.TAG, "Scheduling job: " + job.toString());
            }
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(uid);
            enforceValidJobRequest(uid, job);
            if (job.isPersisted() && !canPersistJobs(pid, uid)) {
                throw new IllegalArgumentException("Error: requested job be persisted without holding RECEIVE_BOOT_COMPLETED permission.");
            }
            validateJobFlags(job, uid);
            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, null, uid, null, userId, null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public int enqueue(JobInfo job, JobWorkItem work) throws RemoteException {
            if (JobSchedulerService.DEBUG) {
                Slog.d(JobSchedulerService.TAG, "Enqueueing job: " + job.toString() + " work: " + work);
            }
            int uid = Binder.getCallingUid();
            int userId = UserHandle.getUserId(uid);
            enforceValidJobRequest(uid, job);
            if (job.isPersisted()) {
                throw new IllegalArgumentException("Can't enqueue work for persisted jobs");
            }
            if (work == null) {
                throw new NullPointerException("work is null");
            }
            validateJobFlags(job, uid);
            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, work, uid, null, userId, null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public int scheduleAsPackage(JobInfo job, String packageName, int userId, String tag) throws RemoteException {
            int callerUid = Binder.getCallingUid();
            if (JobSchedulerService.DEBUG) {
                Slog.d(JobSchedulerService.TAG, "Caller uid " + callerUid + " scheduling job: " + job.toString() + " on behalf of " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER);
            }
            if (packageName == null) {
                throw new NullPointerException("Must specify a package for scheduleAsPackage()");
            }
            int mayScheduleForOthers = JobSchedulerService.this.getContext().checkCallingOrSelfPermission("android.permission.UPDATE_DEVICE_STATS");
            if (mayScheduleForOthers != 0) {
                throw new SecurityException("Caller uid " + callerUid + " not permitted to schedule jobs for other apps");
            }
            validateJobFlags(job, callerUid);
            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, null, callerUid, packageName, userId, tag);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public List<JobInfo> getAllPendingJobs() throws RemoteException {
            int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.getPendingJobs(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public JobInfo getPendingJob(int jobId) throws RemoteException {
            int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.getPendingJob(uid, jobId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public void cancelAll() throws RemoteException {
            int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService jobSchedulerService = JobSchedulerService.this;
                jobSchedulerService.cancelJobsForUid(uid, "cancelAll() called by app, callingUid=" + uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public void cancel(int jobId) throws RemoteException {
            int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJob(uid, jobId, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            boolean proto;
            if (DumpUtils.checkDumpAndUsageStatsPermission(JobSchedulerService.this.getContext(), JobSchedulerService.TAG, pw)) {
                int filterUid = -1;
                if (!ArrayUtils.isEmpty(args)) {
                    proto = false;
                    int opti = 0;
                    while (true) {
                        if (opti >= args.length) {
                            break;
                        }
                        String arg = args[opti];
                        if ("-h".equals(arg)) {
                            JobSchedulerService.dumpHelp(pw);
                            return;
                        }
                        if (!"-a".equals(arg)) {
                            if (PriorityDump.PROTO_ARG.equals(arg)) {
                                proto = true;
                            } else if (arg.length() > 0 && arg.charAt(0) == '-') {
                                pw.println("Unknown option: " + arg);
                                return;
                            }
                        }
                        opti++;
                    }
                    if (opti < args.length) {
                        String pkg = args[opti];
                        try {
                            filterUid = JobSchedulerService.this.getContext().getPackageManager().getPackageUid(pkg, DumpState.DUMP_CHANGES);
                        } catch (PackageManager.NameNotFoundException e) {
                            pw.println("Invalid package: " + pkg);
                            return;
                        }
                    }
                } else {
                    proto = false;
                }
                long identityToken = Binder.clearCallingIdentity();
                try {
                    if (proto) {
                        JobSchedulerService.this.dumpInternalProto(fd, filterUid);
                    } else {
                        JobSchedulerService.this.dumpInternal(new IndentingPrintWriter(pw, "  "), filterUid);
                    }
                    Binder.restoreCallingIdentity(identityToken);
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(identityToken);
                    throw th;
                }
            }
        }

        /* JADX WARN: Multi-variable type inference failed */
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new JobSchedulerShellCommand(JobSchedulerService.this).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int executeRunCommand(String pkgName, int userId, int jobId, boolean force) {
        int uid;
        if (DEBUG) {
            Slog.v(TAG, "executeRunCommand(): " + pkgName + SliceClientPermissions.SliceAuthority.DELIMITER + userId + " " + jobId + " f=" + force);
        }
        try {
            uid = AppGlobals.getPackageManager().getPackageUid(pkgName, 0, userId != -1 ? userId : 0);
        } catch (RemoteException e) {
        }
        if (uid < 0) {
            return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
        }
        synchronized (this.mLock) {
            JobStatus js = this.mJobs.getJobByUidAndJobId(uid, jobId);
            if (js == null) {
                return JobSchedulerShellCommand.CMD_ERR_NO_JOB;
            }
            js.overrideState = force ? 2 : 1;
            if (!js.isConstraintsSatisfied()) {
                js.overrideState = 0;
                return JobSchedulerShellCommand.CMD_ERR_CONSTRAINTS;
            }
            queueReadyJobsForExecutionLocked();
            maybeRunPendingJobsLocked();
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int executeTimeoutCommand(PrintWriter pw, String pkgName, int userId, boolean hasJobId, int jobId) {
        String str;
        int i;
        int i2;
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("executeTimeoutCommand(): ");
            str = pkgName;
            sb.append(str);
            sb.append(SliceClientPermissions.SliceAuthority.DELIMITER);
            i = userId;
            sb.append(i);
            sb.append(" ");
            i2 = jobId;
            sb.append(i2);
            Slog.v(TAG, sb.toString());
        } else {
            str = pkgName;
            i = userId;
            i2 = jobId;
        }
        synchronized (this.mLock) {
            boolean foundSome = false;
            for (int i3 = 0; i3 < this.mActiveServices.size(); i3++) {
                JobServiceContext jc = this.mActiveServices.get(i3);
                JobStatus js = jc.getRunningJobLocked();
                if (jc.timeoutIfExecutingLocked(str, i, hasJobId, i2, "shell")) {
                    pw.print("Timing out: ");
                    js.printUniqueId(pw);
                    pw.print(" ");
                    pw.println(js.getServiceComponent().flattenToShortString());
                    foundSome = true;
                }
            }
            if (!foundSome) {
                pw.println("No matching executing jobs found.");
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int executeCancelCommand(PrintWriter pw, String pkgName, int userId, boolean hasJobId, int jobId) {
        if (DEBUG) {
            Slog.v(TAG, "executeCancelCommand(): " + pkgName + SliceClientPermissions.SliceAuthority.DELIMITER + userId + " " + jobId);
        }
        int pkgUid = -1;
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            pkgUid = pm.getPackageUid(pkgName, 0, userId);
        } catch (RemoteException e) {
        }
        if (pkgUid < 0) {
            pw.println("Package " + pkgName + " not found.");
            return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
        }
        if (!hasJobId) {
            pw.println("Canceling all jobs for " + pkgName + " in user " + userId);
            if (!cancelJobsForUid(pkgUid, "cancel shell command for package")) {
                pw.println("No matching jobs found.");
            }
        } else {
            pw.println("Canceling job " + pkgName + "/#" + jobId + " in user " + userId);
            if (!cancelJob(pkgUid, jobId, 2000)) {
                pw.println("No matching job found.");
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setMonitorBattery(boolean enabled) {
        synchronized (this.mLock) {
            if (this.mBatteryController != null) {
                this.mBatteryController.getTracker().setMonitorBatteryLocked(enabled);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getBatterySeq() {
        int seq;
        synchronized (this.mLock) {
            seq = this.mBatteryController != null ? this.mBatteryController.getTracker().getSeq() : -1;
        }
        return seq;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getBatteryCharging() {
        boolean isOnStablePower;
        synchronized (this.mLock) {
            isOnStablePower = this.mBatteryController != null ? this.mBatteryController.getTracker().isOnStablePower() : false;
        }
        return isOnStablePower;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getBatteryNotLow() {
        boolean isBatteryNotLow;
        synchronized (this.mLock) {
            isBatteryNotLow = this.mBatteryController != null ? this.mBatteryController.getTracker().isBatteryNotLow() : false;
        }
        return isBatteryNotLow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getStorageSeq() {
        int seq;
        synchronized (this.mLock) {
            seq = this.mStorageController != null ? this.mStorageController.getTracker().getSeq() : -1;
        }
        return seq;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getStorageNotLow() {
        boolean isStorageNotLow;
        synchronized (this.mLock) {
            isStorageNotLow = this.mStorageController != null ? this.mStorageController.getTracker().isStorageNotLow() : false;
        }
        return isStorageNotLow;
    }

    long getCurrentHeartbeat() {
        long j;
        synchronized (this.mLock) {
            j = this.mHeartbeat;
        }
        return j;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getJobState(PrintWriter pw, String pkgName, int userId, int jobId) {
        int uid;
        try {
            uid = AppGlobals.getPackageManager().getPackageUid(pkgName, 0, userId != -1 ? userId : 0);
        } catch (RemoteException e) {
        }
        if (uid < 0) {
            pw.print("unknown(");
            pw.print(pkgName);
            pw.println(")");
            return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
        }
        synchronized (this.mLock) {
            JobStatus js = this.mJobs.getJobByUidAndJobId(uid, jobId);
            if (DEBUG) {
                Slog.d(TAG, "get-job-state " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + jobId + ": " + js);
            }
            if (js == null) {
                pw.print("unknown(");
                UserHandle.formatUid(pw, uid);
                pw.print("/jid");
                pw.print(jobId);
                pw.println(")");
                return JobSchedulerShellCommand.CMD_ERR_NO_JOB;
            }
            boolean printed = false;
            if (this.mPendingJobs.contains(js)) {
                pw.print("pending");
                printed = true;
            }
            if (isCurrentlyActiveLocked(js)) {
                if (printed) {
                    pw.print(" ");
                }
                printed = true;
                pw.println("active");
            }
            if (!ArrayUtils.contains(this.mStartedUsers, js.getUserId())) {
                if (printed) {
                    pw.print(" ");
                }
                printed = true;
                pw.println("user-stopped");
            }
            if (this.mBackingUpUids.indexOfKey(js.getSourceUid()) >= 0) {
                if (printed) {
                    pw.print(" ");
                }
                printed = true;
                pw.println("backing-up");
            }
            boolean componentPresent = false;
            try {
                componentPresent = AppGlobals.getPackageManager().getServiceInfo(js.getServiceComponent(), 268435456, js.getUserId()) != null;
            } catch (RemoteException e2) {
            }
            if (!componentPresent) {
                if (printed) {
                    pw.print(" ");
                }
                printed = true;
                pw.println("no-component");
            }
            if (js.isReady()) {
                if (printed) {
                    pw.print(" ");
                }
                printed = true;
                pw.println("ready");
            }
            if (!printed) {
                pw.print("waiting");
            }
            pw.println();
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int executeHeartbeatCommand(PrintWriter pw, int numBeats) {
        if (numBeats < 1) {
            pw.println(getCurrentHeartbeat());
            return 0;
        }
        pw.print("Advancing standby heartbeat by ");
        pw.println(numBeats);
        synchronized (this.mLock) {
            advanceHeartbeatLocked(numBeats);
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void triggerDockState(boolean idleState) {
        Intent dockIntent;
        if (idleState) {
            dockIntent = new Intent("android.intent.action.DOCK_IDLE");
        } else {
            dockIntent = new Intent("android.intent.action.DOCK_ACTIVE");
        }
        dockIntent.setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME);
        dockIntent.addFlags(1342177280);
        getContext().sendBroadcastAsUser(dockIntent, UserHandle.ALL);
    }

    private String printContextIdToJobMap(JobStatus[] map, String initial) {
        StringBuilder s = new StringBuilder(initial + ": ");
        for (int i = 0; i < map.length; i++) {
            s.append("(");
            int i2 = -1;
            s.append(map[i] == null ? -1 : map[i].getJobId());
            if (map[i] != null) {
                i2 = map[i].getUid();
            }
            s.append(i2);
            s.append(")");
        }
        return s.toString();
    }

    private String printPendingQueue() {
        StringBuilder s = new StringBuilder("Pending queue: ");
        Iterator<JobStatus> it = this.mPendingJobs.iterator();
        while (it.hasNext()) {
            JobStatus js = it.next();
            s.append("(");
            s.append(js.getJob().getId());
            s.append(", ");
            s.append(js.getUid());
            s.append(") ");
        }
        return s.toString();
    }

    static void dumpHelp(PrintWriter pw) {
        pw.println("Job Scheduler (jobscheduler) dump options:");
        pw.println("  [-h] [package] ...");
        pw.println("    -h: print this help");
        pw.println("  [package] is an optional package name to limit the output to.");
    }

    private static void sortJobs(List<JobStatus> jobs) {
        Collections.sort(jobs, new Comparator<JobStatus>() { // from class: com.android.server.job.JobSchedulerService.4
            @Override // java.util.Comparator
            public int compare(JobStatus o1, JobStatus o2) {
                int uid1 = o1.getUid();
                int uid2 = o2.getUid();
                int id1 = o1.getJobId();
                int id2 = o2.getJobId();
                if (uid1 != uid2) {
                    return uid1 < uid2 ? -1 : 1;
                } else if (id1 < id2) {
                    return -1;
                } else {
                    return id1 > id2 ? 1 : 0;
                }
            }
        });
    }

    void dumpInternal(IndentingPrintWriter pw, int filterUid) {
        long nowUptime;
        final int filterUidFinal = UserHandle.getAppId(filterUid);
        long nowElapsed = sElapsedRealtimeClock.millis();
        long nowUptime2 = sUptimeMillisClock.millis();
        Predicate<JobStatus> predicate = new Predicate() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$e8zIA2HHN2tnGMuc6TZ2xWw_c20
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return JobSchedulerService.lambda$dumpInternal$3(filterUidFinal, (JobStatus) obj);
            }
        };
        synchronized (this.mLock) {
            try {
                try {
                    this.mConstants.dump(pw);
                    pw.println();
                    pw.println("  Heartbeat:");
                    pw.print("    Current:    ");
                    pw.println(this.mHeartbeat);
                    pw.println("    Next");
                    pw.print("      ACTIVE:   ");
                    int i = 0;
                    pw.println(this.mNextBucketHeartbeat[0]);
                    pw.print("      WORKING:  ");
                    pw.println(this.mNextBucketHeartbeat[1]);
                    pw.print("      FREQUENT: ");
                    pw.println(this.mNextBucketHeartbeat[2]);
                    pw.print("      RARE:     ");
                    pw.println(this.mNextBucketHeartbeat[3]);
                    pw.print("    Last heartbeat: ");
                    TimeUtils.formatDuration(this.mLastHeartbeatTime, nowElapsed, pw);
                    pw.println();
                    pw.print("    Next heartbeat: ");
                    TimeUtils.formatDuration(this.mLastHeartbeatTime + this.mConstants.STANDBY_HEARTBEAT_TIME, nowElapsed, pw);
                    pw.println();
                    pw.print("    In parole?: ");
                    pw.print(this.mInParole);
                    pw.println();
                    pw.println();
                    pw.println("Started users: " + Arrays.toString(this.mStartedUsers));
                    pw.print("Registered ");
                    pw.print(this.mJobs.size());
                    pw.println(" jobs:");
                    try {
                        if (this.mJobs.size() > 0) {
                            try {
                                List<JobStatus> jobs = this.mJobs.mJobSet.getAllJobs();
                                sortJobs(jobs);
                                Iterator<JobStatus> it = jobs.iterator();
                                while (it.hasNext()) {
                                    JobStatus job = it.next();
                                    pw.print("  JOB #");
                                    job.printUniqueId(pw);
                                    pw.print(": ");
                                    pw.println(job.toShortStringExceptUniqueId());
                                    if (predicate.test(job)) {
                                        long nowUptime3 = nowUptime2;
                                        List<JobStatus> jobs2 = jobs;
                                        Iterator<JobStatus> it2 = it;
                                        job.dump((PrintWriter) pw, "    ", true, nowElapsed);
                                        pw.print("    Last run heartbeat: ");
                                        pw.print(heartbeatWhenJobsLastRun(job));
                                        pw.println();
                                        pw.print("    Ready: ");
                                        pw.print(isReadyToBeExecutedLocked(job));
                                        pw.print(" (job=");
                                        pw.print(job.isReady());
                                        pw.print(" user=");
                                        pw.print(ArrayUtils.contains(this.mStartedUsers, job.getUserId()));
                                        pw.print(" !pending=");
                                        pw.print(!this.mPendingJobs.contains(job));
                                        pw.print(" !active=");
                                        pw.print(!isCurrentlyActiveLocked(job));
                                        pw.print(" !backingup=");
                                        pw.print(this.mBackingUpUids.indexOfKey(job.getSourceUid()) < 0);
                                        pw.print(" comp=");
                                        boolean componentPresent = false;
                                        try {
                                            componentPresent = AppGlobals.getPackageManager().getServiceInfo(job.getServiceComponent(), 268435456, job.getUserId()) != null;
                                        } catch (RemoteException e) {
                                        }
                                        pw.print(componentPresent);
                                        pw.println(")");
                                        jobs = jobs2;
                                        nowUptime2 = nowUptime3;
                                        it = it2;
                                    }
                                }
                                nowUptime = nowUptime2;
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        } else {
                            nowUptime = nowUptime2;
                            pw.println("  None.");
                        }
                        for (int i2 = 0; i2 < this.mControllers.size(); i2++) {
                            pw.println();
                            pw.println(this.mControllers.get(i2).getClass().getSimpleName() + ":");
                            pw.increaseIndent();
                            this.mControllers.get(i2).dumpControllerStateLocked(pw, predicate);
                            pw.decreaseIndent();
                        }
                        pw.println();
                        pw.println("Uid priority overrides:");
                        for (int i3 = 0; i3 < this.mUidPriorityOverride.size(); i3++) {
                            int uid = this.mUidPriorityOverride.keyAt(i3);
                            if (filterUidFinal == -1 || filterUidFinal == UserHandle.getAppId(uid)) {
                                pw.print("  ");
                                pw.print(UserHandle.formatUid(uid));
                                pw.print(": ");
                                pw.println(this.mUidPriorityOverride.valueAt(i3));
                            }
                        }
                        if (this.mBackingUpUids.size() > 0) {
                            pw.println();
                            pw.println("Backing up uids:");
                            boolean first = true;
                            for (int i4 = 0; i4 < this.mBackingUpUids.size(); i4++) {
                                int uid2 = this.mBackingUpUids.keyAt(i4);
                                if (filterUidFinal == -1 || filterUidFinal == UserHandle.getAppId(uid2)) {
                                    if (first) {
                                        pw.print("  ");
                                        first = false;
                                    } else {
                                        pw.print(", ");
                                    }
                                    pw.print(UserHandle.formatUid(uid2));
                                }
                            }
                            pw.println();
                        }
                        pw.println();
                        this.mJobPackageTracker.dump((PrintWriter) pw, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, filterUidFinal);
                        pw.println();
                        if (this.mJobPackageTracker.dumpHistory((PrintWriter) pw, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, filterUidFinal)) {
                            pw.println();
                        }
                        pw.println("Pending queue:");
                        for (int i5 = 0; i5 < this.mPendingJobs.size(); i5++) {
                            JobStatus job2 = this.mPendingJobs.get(i5);
                            pw.print("  Pending #");
                            pw.print(i5);
                            pw.print(": ");
                            pw.println(job2.toShortString());
                            job2.dump((PrintWriter) pw, "    ", false, nowElapsed);
                            int priority = evaluateJobPriorityLocked(job2);
                            if (priority != 0) {
                                pw.print("    Evaluated priority: ");
                                pw.println(priority);
                            }
                            pw.print("    Tag: ");
                            pw.println(job2.getTag());
                            pw.print("    Enq: ");
                            TimeUtils.formatDuration(job2.madePending - nowUptime, pw);
                            pw.println();
                        }
                        pw.println();
                        pw.println("Active jobs:");
                        while (true) {
                            int i6 = i;
                            if (i6 >= this.mActiveServices.size()) {
                                break;
                            }
                            JobServiceContext jsc = this.mActiveServices.get(i6);
                            pw.print("  Slot #");
                            pw.print(i6);
                            pw.print(": ");
                            JobStatus job3 = jsc.getRunningJobLocked();
                            if (job3 != null) {
                                pw.println(job3.toShortString());
                                pw.print("    Running for: ");
                                TimeUtils.formatDuration(nowElapsed - jsc.getExecutionStartTimeElapsed(), pw);
                                pw.print(", timeout at: ");
                                TimeUtils.formatDuration(jsc.getTimeoutElapsed() - nowElapsed, pw);
                                pw.println();
                                job3.dump((PrintWriter) pw, "    ", false, nowElapsed);
                                int priority2 = evaluateJobPriorityLocked(jsc.getRunningJobLocked());
                                if (priority2 != 0) {
                                    pw.print("    Evaluated priority: ");
                                    pw.println(priority2);
                                }
                                pw.print("    Active at ");
                                TimeUtils.formatDuration(job3.madeActive - nowUptime, pw);
                                pw.print(", pending for ");
                                TimeUtils.formatDuration(job3.madeActive - job3.madePending, pw);
                                pw.println();
                            } else if (jsc.mStoppedReason != null) {
                                pw.print("inactive since ");
                                TimeUtils.formatDuration(jsc.mStoppedTime, nowElapsed, pw);
                                pw.print(", stopped because: ");
                                pw.println(jsc.mStoppedReason);
                            } else {
                                pw.println("inactive");
                            }
                            i = i6 + 1;
                        }
                        if (filterUid == -1) {
                            pw.println();
                            pw.print("mReadyToRock=");
                            pw.println(this.mReadyToRock);
                            pw.print("mReportedActive=");
                            pw.println(this.mReportedActive);
                            pw.print("mMaxActiveJobs=");
                            pw.println(this.mMaxActiveJobs);
                        }
                        pw.println();
                        pw.print("PersistStats: ");
                        pw.println(this.mJobs.getPersistStats());
                        pw.println();
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            } catch (Throwable th4) {
                th = th4;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$dumpInternal$3(int filterUidFinal, JobStatus js) {
        return filterUidFinal == -1 || UserHandle.getAppId(js.getUid()) == filterUidFinal || UserHandle.getAppId(js.getSourceUid()) == filterUidFinal;
    }

    void dumpInternalProto(FileDescriptor fd, int filterUid) {
        Object obj;
        int[] iArr;
        int filterUidFinal;
        int filterUidFinal2;
        long ajToken;
        Iterator<JobServiceContext> it;
        JobStatus job;
        long pjToken;
        int filterUidFinal3;
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        final int filterUidFinal4 = UserHandle.getAppId(filterUid);
        long nowElapsed = sElapsedRealtimeClock.millis();
        long nowUptime = sUptimeMillisClock.millis();
        Predicate<JobStatus> predicate = new Predicate() { // from class: com.android.server.job.-$$Lambda$JobSchedulerService$rARZcsrvtM2sYbF4SrEE2BXDQ3U
            @Override // java.util.function.Predicate
            public final boolean test(Object obj2) {
                return JobSchedulerService.lambda$dumpInternalProto$4(filterUidFinal4, (JobStatus) obj2);
            }
        };
        Object obj2 = this.mLock;
        synchronized (obj2) {
            try {
                try {
                    this.mConstants.dump(proto, 1146756268033L);
                    proto.write(1120986464270L, this.mHeartbeat);
                    int i = 0;
                    proto.write(2220498092047L, this.mNextBucketHeartbeat[0]);
                    proto.write(2220498092047L, this.mNextBucketHeartbeat[1]);
                    proto.write(2220498092047L, this.mNextBucketHeartbeat[2]);
                    proto.write(2220498092047L, this.mNextBucketHeartbeat[3]);
                    proto.write(1112396529680L, this.mLastHeartbeatTime - nowUptime);
                    proto.write(1112396529681L, (this.mLastHeartbeatTime + this.mConstants.STANDBY_HEARTBEAT_TIME) - nowUptime);
                    proto.write(1133871366162L, this.mInParole);
                    for (int u : this.mStartedUsers) {
                        try {
                            proto.write(2220498092034L, u);
                        } catch (Throwable th) {
                            th = th;
                            obj = obj2;
                            throw th;
                        }
                    }
                    if (this.mJobs.size() > 0) {
                        try {
                            List<JobStatus> jobs = this.mJobs.mJobSet.getAllJobs();
                            sortJobs(jobs);
                            Iterator<JobStatus> it2 = jobs.iterator();
                            while (it2.hasNext()) {
                                JobStatus job2 = it2.next();
                                long rjToken = proto.start(2246267895811L);
                                long nowUptime2 = nowUptime;
                                try {
                                    job2.writeToShortProto(proto, 1146756268033L);
                                    if (predicate.test(job2)) {
                                        Iterator<JobStatus> it3 = it2;
                                        List<JobStatus> jobs2 = jobs;
                                        obj = obj2;
                                        filterUidFinal = filterUidFinal4;
                                        Predicate<JobStatus> predicate2 = predicate;
                                        try {
                                            job2.dump(proto, 1146756268034L, true, nowElapsed);
                                            proto.write(1133871366147L, job2.isReady());
                                            proto.write(1133871366148L, ArrayUtils.contains(this.mStartedUsers, job2.getUserId()));
                                            proto.write(1133871366149L, this.mPendingJobs.contains(job2));
                                            proto.write(1133871366150L, isCurrentlyActiveLocked(job2));
                                            proto.write(1133871366151L, this.mBackingUpUids.indexOfKey(job2.getSourceUid()) >= 0);
                                            boolean componentPresent = false;
                                            try {
                                                componentPresent = AppGlobals.getPackageManager().getServiceInfo(job2.getServiceComponent(), 268435456, job2.getUserId()) != null;
                                            } catch (RemoteException e) {
                                            }
                                            proto.write(1133871366152L, componentPresent);
                                            proto.write(1112396529673L, heartbeatWhenJobsLastRun(job2));
                                            proto.end(rjToken);
                                            predicate = predicate2;
                                            jobs = jobs2;
                                            obj2 = obj;
                                            it2 = it3;
                                            nowUptime = nowUptime2;
                                            filterUidFinal4 = filterUidFinal;
                                        } catch (Throwable th2) {
                                            th = th2;
                                            throw th;
                                        }
                                    } else {
                                        nowUptime = nowUptime2;
                                    }
                                } catch (Throwable th3) {
                                    th = th3;
                                    obj = obj2;
                                    throw th;
                                }
                            }
                        } catch (Throwable th4) {
                            th = th4;
                            obj = obj2;
                        }
                    }
                    obj = obj2;
                    filterUidFinal = filterUidFinal4;
                    long nowUptime3 = nowUptime;
                    Predicate<JobStatus> predicate3 = predicate;
                    try {
                        for (StateController controller : this.mControllers) {
                            controller.dumpControllerStateLocked(proto, 2246267895812L, predicate3);
                        }
                        int i2 = 0;
                        while (i2 < this.mUidPriorityOverride.size()) {
                            try {
                                int uid = this.mUidPriorityOverride.keyAt(i2);
                                filterUidFinal2 = filterUidFinal;
                                if (filterUidFinal2 != -1) {
                                    try {
                                        if (filterUidFinal2 != UserHandle.getAppId(uid)) {
                                            i2++;
                                            filterUidFinal = filterUidFinal2;
                                        }
                                    } catch (Throwable th5) {
                                        th = th5;
                                        throw th;
                                    }
                                }
                                long pToken = proto.start(2246267895813L);
                                proto.write(1120986464257L, uid);
                                proto.write(1172526071810L, this.mUidPriorityOverride.valueAt(i2));
                                proto.end(pToken);
                                i2++;
                                filterUidFinal = filterUidFinal2;
                            } catch (Throwable th6) {
                                th = th6;
                                throw th;
                            }
                        }
                        filterUidFinal2 = filterUidFinal;
                        while (true) {
                            int i3 = i;
                            try {
                                if (i3 >= this.mBackingUpUids.size()) {
                                    break;
                                }
                                int uid2 = this.mBackingUpUids.keyAt(i3);
                                if (filterUidFinal2 == -1 || filterUidFinal2 == UserHandle.getAppId(uid2)) {
                                    proto.write(2220498092038L, uid2);
                                }
                                i = i3 + 1;
                            } catch (Throwable th7) {
                                th = th7;
                            }
                        }
                        this.mJobPackageTracker.dump(proto, 1146756268040L, filterUidFinal2);
                        this.mJobPackageTracker.dumpHistory(proto, 1146756268039L, filterUidFinal2);
                        Iterator<JobStatus> it4 = this.mPendingJobs.iterator();
                        while (it4.hasNext()) {
                            try {
                                job = it4.next();
                                pjToken = proto.start(2246267895817L);
                                job.writeToShortProto(proto, 1146756268033L);
                                filterUidFinal3 = filterUidFinal2;
                            } catch (Throwable th8) {
                                th = th8;
                                throw th;
                            }
                            try {
                                job.dump(proto, 1146756268034L, false, nowElapsed);
                                int priority = evaluateJobPriorityLocked(job);
                                if (priority != 0) {
                                    proto.write(1172526071811L, priority);
                                }
                                proto.write(1112396529668L, nowUptime3 - job.madePending);
                                proto.end(pjToken);
                                filterUidFinal2 = filterUidFinal3;
                            } catch (Throwable th9) {
                                th = th9;
                                throw th;
                            }
                        }
                        Iterator<JobServiceContext> it5 = this.mActiveServices.iterator();
                        while (it5.hasNext()) {
                            JobServiceContext jsc = it5.next();
                            long ajToken2 = proto.start(2246267895818L);
                            JobStatus job3 = jsc.getRunningJobLocked();
                            if (job3 == null) {
                                long ijToken = proto.start(1146756268033L);
                                ajToken = ajToken2;
                                proto.write(1112396529665L, nowElapsed - jsc.mStoppedTime);
                                if (jsc.mStoppedReason != null) {
                                    proto.write(1138166333442L, jsc.mStoppedReason);
                                }
                                proto.end(ijToken);
                                it = it5;
                            } else {
                                ajToken = ajToken2;
                                long rjToken2 = proto.start(1146756268034L);
                                job3.writeToShortProto(proto, 1146756268033L);
                                proto.write(1112396529666L, nowElapsed - jsc.getExecutionStartTimeElapsed());
                                proto.write(1112396529667L, jsc.getTimeoutElapsed() - nowElapsed);
                                it = it5;
                                job3.dump(proto, 1146756268036L, false, nowElapsed);
                                int priority2 = evaluateJobPriorityLocked(jsc.getRunningJobLocked());
                                if (priority2 != 0) {
                                    proto.write(1172526071813L, priority2);
                                }
                                proto.write(1112396529670L, nowUptime3 - job3.madeActive);
                                proto.write(1112396529671L, job3.madeActive - job3.madePending);
                                proto.end(rjToken2);
                            }
                            proto.end(ajToken);
                            it5 = it;
                        }
                        if (filterUid == -1) {
                            proto.write(1133871366155L, this.mReadyToRock);
                            proto.write(1133871366156L, this.mReportedActive);
                            proto.write(1120986464269L, this.mMaxActiveJobs);
                        }
                        proto.flush();
                    } catch (Throwable th10) {
                        th = th10;
                    }
                } catch (Throwable th11) {
                    th = th11;
                    obj = obj2;
                }
            } catch (Throwable th12) {
                th = th12;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$dumpInternalProto$4(int filterUidFinal, JobStatus js) {
        return filterUidFinal == -1 || UserHandle.getAppId(js.getUid()) == filterUidFinal || UserHandle.getAppId(js.getSourceUid()) == filterUidFinal;
    }
}
