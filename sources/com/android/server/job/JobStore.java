package com.android.server.job;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkRequest;
import android.os.Environment;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.BitUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.content.SyncJobService;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobStore;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.android.server.pm.PackageManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public final class JobStore {
    private static final int JOBS_FILE_VERSION = 0;
    private static final long JOB_PERSIST_DELAY = 2000;
    private static final String TAG = "JobStore";
    private static final String XML_TAG_EXTRAS = "extras";
    private static final String XML_TAG_ONEOFF = "one-off";
    private static final String XML_TAG_PARAMS_CONSTRAINTS = "constraints";
    private static final String XML_TAG_PERIODIC = "periodic";
    private static JobStore sSingleton;
    final Context mContext;
    final JobSet mJobSet;
    private final AtomicFile mJobsFile;
    final Object mLock;
    private boolean mRtcGood;
    @GuardedBy({"mWriteScheduleLock"})
    private boolean mWriteInProgress;
    @GuardedBy({"mWriteScheduleLock"})
    private boolean mWriteScheduled;
    private final long mXmlTimestamp;
    private static final boolean DEBUG = JobSchedulerService.DEBUG;
    private static final Object sSingletonLock = new Object();
    private final Handler mIoHandler = IoThread.getHandler();
    private JobSchedulerInternal.JobStorePersistStats mPersistInfo = new JobSchedulerInternal.JobStorePersistStats();
    private final Runnable mWriteRunnable = new AnonymousClass1();
    final Object mWriteScheduleLock = new Object();

    /* JADX INFO: Access modifiers changed from: package-private */
    public static JobStore initAndGet(JobSchedulerService jobManagerService) {
        JobStore jobStore;
        synchronized (sSingletonLock) {
            if (sSingleton == null) {
                sSingleton = new JobStore(jobManagerService.getContext(), jobManagerService.getLock(), Environment.getDataDirectory());
            }
            jobStore = sSingleton;
        }
        return jobStore;
    }

    @VisibleForTesting
    public static JobStore initAndGetForTesting(Context context, File dataDir) {
        JobStore jobStoreUnderTest = new JobStore(context, new Object(), dataDir);
        jobStoreUnderTest.clear();
        return jobStoreUnderTest;
    }

    private JobStore(Context context, Object lock, File dataDir) {
        this.mLock = lock;
        this.mContext = context;
        File systemDir = new File(dataDir, "system");
        File jobDir = new File(systemDir, "job");
        jobDir.mkdirs();
        this.mJobsFile = new AtomicFile(new File(jobDir, "jobs.xml"), "jobs");
        this.mJobSet = new JobSet();
        this.mXmlTimestamp = this.mJobsFile.getLastModifiedTime();
        this.mRtcGood = JobSchedulerService.sSystemClock.millis() > this.mXmlTimestamp;
        readJobMapFromDisk(this.mJobSet, this.mRtcGood);
    }

    public boolean jobTimesInflatedValid() {
        return this.mRtcGood;
    }

    public boolean clockNowValidToInflate(long now) {
        return now >= this.mXmlTimestamp;
    }

    public void getRtcCorrectedJobsLocked(final ArrayList<JobStatus> toAdd, final ArrayList<JobStatus> toRemove) {
        final long elapsedNow = JobSchedulerService.sElapsedRealtimeClock.millis();
        final IActivityManager am = ActivityManager.getService();
        forEachJob(new Consumer() { // from class: com.android.server.job.-$$Lambda$JobStore$aR-nUP_cO7r3CFj_LAjeNjVBP-4
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                JobStore.lambda$getRtcCorrectedJobsLocked$0(elapsedNow, am, toAdd, toRemove, (JobStatus) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getRtcCorrectedJobsLocked$0(long elapsedNow, IActivityManager am, ArrayList toAdd, ArrayList toRemove, JobStatus job) {
        Pair<Long, Long> utcTimes = job.getPersistedUtcTimes();
        if (utcTimes != null) {
            Pair<Long, Long> elapsedRuntimes = convertRtcBoundsToElapsed(utcTimes, elapsedNow);
            JobStatus newJob = new JobStatus(job, job.getBaseHeartbeat(), ((Long) elapsedRuntimes.first).longValue(), ((Long) elapsedRuntimes.second).longValue(), 0, job.getLastSuccessfulRunTime(), job.getLastFailedRunTime());
            newJob.prepareLocked(am);
            toAdd.add(newJob);
            toRemove.add(job);
        }
    }

    public boolean add(JobStatus jobStatus) {
        boolean replaced = this.mJobSet.remove(jobStatus);
        this.mJobSet.add(jobStatus);
        if (jobStatus.isPersisted()) {
            maybeWriteStatusToDiskAsync();
        }
        if (DEBUG) {
            Slog.d(TAG, "Added job status to store: " + jobStatus);
        }
        return replaced;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean containsJob(JobStatus jobStatus) {
        return this.mJobSet.contains(jobStatus);
    }

    public int size() {
        return this.mJobSet.size();
    }

    public JobSchedulerInternal.JobStorePersistStats getPersistStats() {
        return this.mPersistInfo;
    }

    public int countJobsForUid(int uid) {
        return this.mJobSet.countJobsForUid(uid);
    }

    public boolean remove(JobStatus jobStatus, boolean writeBack) {
        boolean removed = this.mJobSet.remove(jobStatus);
        if (!removed) {
            if (DEBUG) {
                Slog.d(TAG, "Couldn't remove job: didn't exist: " + jobStatus);
                return false;
            }
            return false;
        }
        if (writeBack && jobStatus.isPersisted()) {
            maybeWriteStatusToDiskAsync();
        }
        return removed;
    }

    public void removeJobsOfNonUsers(int[] whitelist) {
        this.mJobSet.removeJobsOfNonUsers(whitelist);
    }

    @VisibleForTesting
    public void clear() {
        this.mJobSet.clear();
        maybeWriteStatusToDiskAsync();
    }

    public List<JobStatus> getJobsByUser(int userHandle) {
        return this.mJobSet.getJobsByUser(userHandle);
    }

    public List<JobStatus> getJobsByUid(int uid) {
        return this.mJobSet.getJobsByUid(uid);
    }

    public JobStatus getJobByUidAndJobId(int uid, int jobId) {
        return this.mJobSet.get(uid, jobId);
    }

    public void forEachJob(Consumer<JobStatus> functor) {
        this.mJobSet.forEachJob((Predicate<JobStatus>) null, functor);
    }

    public void forEachJob(Predicate<JobStatus> filterPredicate, Consumer<JobStatus> functor) {
        this.mJobSet.forEachJob(filterPredicate, functor);
    }

    public void forEachJob(int uid, Consumer<JobStatus> functor) {
        this.mJobSet.forEachJob(uid, functor);
    }

    public void forEachJobForSourceUid(int sourceUid, Consumer<JobStatus> functor) {
        this.mJobSet.forEachJobForSourceUid(sourceUid, functor);
    }

    private void maybeWriteStatusToDiskAsync() {
        synchronized (this.mWriteScheduleLock) {
            if (!this.mWriteScheduled) {
                if (DEBUG) {
                    Slog.v(TAG, "Scheduling persist of jobs to disk.");
                }
                this.mIoHandler.postDelayed(this.mWriteRunnable, JOB_PERSIST_DELAY);
                this.mWriteInProgress = true;
                this.mWriteScheduled = true;
            }
        }
    }

    @VisibleForTesting
    public void readJobMapFromDisk(JobSet jobSet, boolean rtcGood) {
        new ReadJobMapFromDiskRunnable(jobSet, rtcGood).run();
    }

    @VisibleForTesting
    public boolean waitForWriteToCompleteForTesting(long maxWaitMillis) {
        long start = SystemClock.uptimeMillis();
        long end = start + maxWaitMillis;
        synchronized (this.mWriteScheduleLock) {
            while (this.mWriteInProgress) {
                long now = SystemClock.uptimeMillis();
                if (now >= end) {
                    return false;
                }
                try {
                    this.mWriteScheduleLock.wait((now - start) + maxWaitMillis);
                } catch (InterruptedException e) {
                }
            }
            return true;
        }
    }

    /* renamed from: com.android.server.job.JobStore$1  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass1 implements Runnable {
        AnonymousClass1() {
        }

        @Override // java.lang.Runnable
        public void run() {
            long startElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
            final List<JobStatus> storeCopy = new ArrayList<>();
            synchronized (JobStore.this.mWriteScheduleLock) {
                JobStore.this.mWriteScheduled = false;
            }
            synchronized (JobStore.this.mLock) {
                JobStore.this.mJobSet.forEachJob((Predicate<JobStatus>) null, new Consumer() { // from class: com.android.server.job.-$$Lambda$JobStore$1$Wgepg1oHZp0-Q01q1baIVZKWujU
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        JobStore.AnonymousClass1.lambda$run$0(storeCopy, (JobStatus) obj);
                    }
                });
            }
            writeJobsMapImpl(storeCopy);
            if (JobStore.DEBUG) {
                Slog.v(JobStore.TAG, "Finished writing, took " + (JobSchedulerService.sElapsedRealtimeClock.millis() - startElapsed) + "ms");
            }
            synchronized (JobStore.this.mWriteScheduleLock) {
                JobStore.this.mWriteInProgress = false;
                JobStore.this.mWriteScheduleLock.notifyAll();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$run$0(List storeCopy, JobStatus job) {
            if (job.isPersisted()) {
                storeCopy.add(new JobStatus(job));
            }
        }

        private void writeJobsMapImpl(List<JobStatus> jobList) {
            int numJobs = 0;
            int numSystemJobs = 0;
            int numSyncJobs = 0;
            try {
                try {
                    long startTime = SystemClock.uptimeMillis();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    XmlSerializer out = new FastXmlSerializer();
                    out.setOutput(baos, StandardCharsets.UTF_8.name());
                    out.startDocument(null, true);
                    out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    out.startTag(null, "job-info");
                    out.attribute(null, "version", Integer.toString(0));
                    for (int i = 0; i < jobList.size(); i++) {
                        try {
                            JobStatus jobStatus = jobList.get(i);
                            if (JobStore.DEBUG) {
                                Slog.d(JobStore.TAG, "Saving job " + jobStatus.getJobId());
                            }
                            out.startTag(null, "job");
                            addAttributesToJobTag(out, jobStatus);
                            writeConstraintsToXml(out, jobStatus);
                            writeExecutionCriteriaToXml(out, jobStatus);
                            writeBundleToXml(jobStatus.getJob().getExtras(), out);
                            out.endTag(null, "job");
                            numJobs++;
                            if (jobStatus.getUid() == 1000) {
                                numSystemJobs++;
                                if (JobStore.isSyncJob(jobStatus)) {
                                    numSyncJobs++;
                                }
                            }
                        } catch (IOException e) {
                            e = e;
                            if (JobStore.DEBUG) {
                                Slog.v(JobStore.TAG, "Error writing out job data.", e);
                            }
                            JobStore.this.mPersistInfo.countAllJobsSaved = numJobs;
                            JobStore.this.mPersistInfo.countSystemServerJobsSaved = numSystemJobs;
                            JobStore.this.mPersistInfo.countSystemSyncManagerJobsSaved = numSyncJobs;
                        } catch (XmlPullParserException e2) {
                            e = e2;
                            if (JobStore.DEBUG) {
                                Slog.d(JobStore.TAG, "Error persisting bundle.", e);
                            }
                            JobStore.this.mPersistInfo.countAllJobsSaved = numJobs;
                            JobStore.this.mPersistInfo.countSystemServerJobsSaved = numSystemJobs;
                            JobStore.this.mPersistInfo.countSystemSyncManagerJobsSaved = numSyncJobs;
                        }
                    }
                    out.endTag(null, "job-info");
                    out.endDocument();
                    FileOutputStream fos = JobStore.this.mJobsFile.startWrite(startTime);
                    fos.write(baos.toByteArray());
                    JobStore.this.mJobsFile.finishWrite(fos);
                } catch (IOException e3) {
                    e = e3;
                } catch (XmlPullParserException e4) {
                    e = e4;
                } catch (Throwable th) {
                    th = th;
                    JobStore.this.mPersistInfo.countAllJobsSaved = numJobs;
                    JobStore.this.mPersistInfo.countSystemServerJobsSaved = numSystemJobs;
                    JobStore.this.mPersistInfo.countSystemSyncManagerJobsSaved = numSyncJobs;
                    throw th;
                }
                JobStore.this.mPersistInfo.countAllJobsSaved = numJobs;
                JobStore.this.mPersistInfo.countSystemServerJobsSaved = numSystemJobs;
                JobStore.this.mPersistInfo.countSystemSyncManagerJobsSaved = numSyncJobs;
            } catch (Throwable th2) {
                th = th2;
                JobStore.this.mPersistInfo.countAllJobsSaved = numJobs;
                JobStore.this.mPersistInfo.countSystemServerJobsSaved = numSystemJobs;
                JobStore.this.mPersistInfo.countSystemSyncManagerJobsSaved = numSyncJobs;
                throw th;
            }
        }

        private void addAttributesToJobTag(XmlSerializer out, JobStatus jobStatus) throws IOException {
            out.attribute(null, "jobid", Integer.toString(jobStatus.getJobId()));
            out.attribute(null, "package", jobStatus.getServiceComponent().getPackageName());
            out.attribute(null, "class", jobStatus.getServiceComponent().getClassName());
            if (jobStatus.getSourcePackageName() != null) {
                out.attribute(null, "sourcePackageName", jobStatus.getSourcePackageName());
            }
            if (jobStatus.getSourceTag() != null) {
                out.attribute(null, "sourceTag", jobStatus.getSourceTag());
            }
            out.attribute(null, "sourceUserId", String.valueOf(jobStatus.getSourceUserId()));
            out.attribute(null, WatchlistLoggingHandler.WatchlistEventKeys.UID, Integer.toString(jobStatus.getUid()));
            out.attribute(null, xpInputManagerService.InputPolicyKey.KEY_PRIORITY, String.valueOf(jobStatus.getPriority()));
            out.attribute(null, xpInputManagerService.InputPolicyKey.KEY_FLAGS, String.valueOf(jobStatus.getFlags()));
            if (jobStatus.getInternalFlags() != 0) {
                out.attribute(null, "internalFlags", String.valueOf(jobStatus.getInternalFlags()));
            }
            out.attribute(null, "lastSuccessfulRunTime", String.valueOf(jobStatus.getLastSuccessfulRunTime()));
            out.attribute(null, "lastFailedRunTime", String.valueOf(jobStatus.getLastFailedRunTime()));
        }

        private void writeBundleToXml(PersistableBundle extras, XmlSerializer out) throws IOException, XmlPullParserException {
            out.startTag(null, JobStore.XML_TAG_EXTRAS);
            PersistableBundle extrasCopy = deepCopyBundle(extras, 10);
            extrasCopy.saveToXml(out);
            out.endTag(null, JobStore.XML_TAG_EXTRAS);
        }

        private PersistableBundle deepCopyBundle(PersistableBundle bundle, int maxDepth) {
            if (maxDepth <= 0) {
                return null;
            }
            PersistableBundle copy = (PersistableBundle) bundle.clone();
            Set<String> keySet = bundle.keySet();
            for (String key : keySet) {
                Object o = copy.get(key);
                if (o instanceof PersistableBundle) {
                    PersistableBundle bCopy = deepCopyBundle((PersistableBundle) o, maxDepth - 1);
                    copy.putPersistableBundle(key, bCopy);
                }
            }
            return copy;
        }

        private void writeConstraintsToXml(XmlSerializer out, JobStatus jobStatus) throws IOException {
            out.startTag(null, JobStore.XML_TAG_PARAMS_CONSTRAINTS);
            if (jobStatus.hasConnectivityConstraint()) {
                NetworkRequest network = jobStatus.getJob().getRequiredNetwork();
                out.attribute(null, "net-capabilities", Long.toString(BitUtils.packBits(network.networkCapabilities.getCapabilities())));
                out.attribute(null, "net-unwanted-capabilities", Long.toString(BitUtils.packBits(network.networkCapabilities.getUnwantedCapabilities())));
                out.attribute(null, "net-transport-types", Long.toString(BitUtils.packBits(network.networkCapabilities.getTransportTypes())));
            }
            if (jobStatus.hasIdleConstraint()) {
                out.attribute(null, "idle", Boolean.toString(true));
            }
            if (jobStatus.hasChargingConstraint()) {
                out.attribute(null, "charging", Boolean.toString(true));
            }
            if (jobStatus.hasBatteryNotLowConstraint()) {
                out.attribute(null, "battery-not-low", Boolean.toString(true));
            }
            if (jobStatus.hasStorageNotLowConstraint()) {
                out.attribute(null, "storage-not-low", Boolean.toString(true));
            }
            out.endTag(null, JobStore.XML_TAG_PARAMS_CONSTRAINTS);
        }

        private void writeExecutionCriteriaToXml(XmlSerializer out, JobStatus jobStatus) throws IOException {
            long delayWallclock;
            long deadlineWallclock;
            JobInfo job = jobStatus.getJob();
            if (jobStatus.getJob().isPeriodic()) {
                out.startTag(null, JobStore.XML_TAG_PERIODIC);
                out.attribute(null, "period", Long.toString(job.getIntervalMillis()));
                out.attribute(null, "flex", Long.toString(job.getFlexMillis()));
            } else {
                out.startTag(null, JobStore.XML_TAG_ONEOFF);
            }
            Pair<Long, Long> utcJobTimes = jobStatus.getPersistedUtcTimes();
            if (JobStore.DEBUG && utcJobTimes != null) {
                Slog.i(JobStore.TAG, "storing original UTC timestamps for " + jobStatus);
            }
            long nowRTC = JobSchedulerService.sSystemClock.millis();
            long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
            if (jobStatus.hasDeadlineConstraint()) {
                if (utcJobTimes == null) {
                    deadlineWallclock = (jobStatus.getLatestRunTimeElapsed() - nowElapsed) + nowRTC;
                } else {
                    deadlineWallclock = ((Long) utcJobTimes.second).longValue();
                }
                out.attribute(null, "deadline", Long.toString(deadlineWallclock));
            }
            if (jobStatus.hasTimingDelayConstraint()) {
                if (utcJobTimes == null) {
                    delayWallclock = (jobStatus.getEarliestRunTime() - nowElapsed) + nowRTC;
                } else {
                    delayWallclock = ((Long) utcJobTimes.first).longValue();
                }
                out.attribute(null, "delay", Long.toString(delayWallclock));
            }
            if (jobStatus.getJob().getInitialBackoffMillis() != 30000 || jobStatus.getJob().getBackoffPolicy() != 1) {
                out.attribute(null, "backoff-policy", Integer.toString(job.getBackoffPolicy()));
                out.attribute(null, "initial-backoff", Long.toString(job.getInitialBackoffMillis()));
            }
            if (job.isPeriodic()) {
                out.endTag(null, JobStore.XML_TAG_PERIODIC);
            } else {
                out.endTag(null, JobStore.XML_TAG_ONEOFF);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Pair<Long, Long> convertRtcBoundsToElapsed(Pair<Long, Long> rtcTimes, long nowElapsed) {
        long earliest;
        long nowWallclock = JobSchedulerService.sSystemClock.millis();
        if (((Long) rtcTimes.first).longValue() > 0) {
            earliest = Math.max(((Long) rtcTimes.first).longValue() - nowWallclock, 0L) + nowElapsed;
        } else {
            earliest = 0;
        }
        long longValue = ((Long) rtcTimes.second).longValue();
        long j = JobStatus.NO_LATEST_RUNTIME;
        if (longValue < JobStatus.NO_LATEST_RUNTIME) {
            j = nowElapsed + Math.max(((Long) rtcTimes.second).longValue() - nowWallclock, 0L);
        }
        long latest = j;
        return Pair.create(Long.valueOf(earliest), Long.valueOf(latest));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isSyncJob(JobStatus status) {
        return SyncJobService.class.getName().equals(status.getServiceComponent().getClassName());
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ReadJobMapFromDiskRunnable implements Runnable {
        private final JobSet jobSet;
        private final boolean rtcGood;

        ReadJobMapFromDiskRunnable(JobSet jobSet, boolean rtcIsGood) {
            this.jobSet = jobSet;
            this.rtcGood = rtcIsGood;
        }

        /* JADX WARN: Code restructure failed: missing block: B:29:0x008f, code lost:
            if (r13.this$0.mPersistInfo.countAllJobsLoaded >= 0) goto L27;
         */
        /* JADX WARN: Code restructure failed: missing block: B:36:0x00a8, code lost:
            if (r13.this$0.mPersistInfo.countAllJobsLoaded >= 0) goto L27;
         */
        /* JADX WARN: Code restructure failed: missing block: B:38:0x00ab, code lost:
            android.util.Slog.i(com.android.server.job.JobStore.TAG, "Read " + r0 + " jobs");
         */
        /* JADX WARN: Code restructure failed: missing block: B:39:0x00c6, code lost:
            return;
         */
        @Override // java.lang.Runnable
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void run() {
            /*
                r13 = this;
                r0 = 0
                r1 = 0
                r2 = 0
                com.android.server.job.JobStore r3 = com.android.server.job.JobStore.this     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
                android.util.AtomicFile r3 = com.android.server.job.JobStore.access$400(r3)     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
                java.io.FileInputStream r3 = r3.openRead()     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
                com.android.server.job.JobStore r4 = com.android.server.job.JobStore.this     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
                java.lang.Object r4 = r4.mLock     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
                monitor-enter(r4)     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
                boolean r5 = r13.rtcGood     // Catch: java.lang.Throwable -> L79
                java.util.List r5 = r13.readJobMapImpl(r3, r5)     // Catch: java.lang.Throwable -> L79
                if (r5 == 0) goto L52
                java.time.Clock r6 = com.android.server.job.JobSchedulerService.sElapsedRealtimeClock     // Catch: java.lang.Throwable -> L79
                long r6 = r6.millis()     // Catch: java.lang.Throwable -> L79
                android.app.IActivityManager r8 = android.app.ActivityManager.getService()     // Catch: java.lang.Throwable -> L79
                r9 = 0
            L25:
                int r10 = r5.size()     // Catch: java.lang.Throwable -> L79
                if (r9 >= r10) goto L52
                java.lang.Object r10 = r5.get(r9)     // Catch: java.lang.Throwable -> L79
                com.android.server.job.controllers.JobStatus r10 = (com.android.server.job.controllers.JobStatus) r10     // Catch: java.lang.Throwable -> L79
                r10.prepareLocked(r8)     // Catch: java.lang.Throwable -> L79
                r10.enqueueTime = r6     // Catch: java.lang.Throwable -> L79
                com.android.server.job.JobStore$JobSet r11 = r13.jobSet     // Catch: java.lang.Throwable -> L79
                r11.add(r10)     // Catch: java.lang.Throwable -> L79
                int r0 = r0 + 1
                int r11 = r10.getUid()     // Catch: java.lang.Throwable -> L79
                r12 = 1000(0x3e8, float:1.401E-42)
                if (r11 != r12) goto L4f
                int r1 = r1 + 1
                boolean r11 = com.android.server.job.JobStore.access$300(r10)     // Catch: java.lang.Throwable -> L79
                if (r11 == 0) goto L4f
                int r2 = r2 + 1
            L4f:
                int r9 = r9 + 1
                goto L25
            L52:
                monitor-exit(r4)     // Catch: java.lang.Throwable -> L79
                r3.close()     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
                com.android.server.job.JobStore r3 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r3 = com.android.server.job.JobStore.access$500(r3)
                int r3 = r3.countAllJobsLoaded
                if (r3 >= 0) goto Lab
            L60:
                com.android.server.job.JobStore r3 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r3 = com.android.server.job.JobStore.access$500(r3)
                r3.countAllJobsLoaded = r0
                com.android.server.job.JobStore r3 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r3 = com.android.server.job.JobStore.access$500(r3)
                r3.countSystemServerJobsLoaded = r1
                com.android.server.job.JobStore r3 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r3 = com.android.server.job.JobStore.access$500(r3)
                r3.countSystemSyncManagerJobsLoaded = r2
                goto Lab
            L79:
                r5 = move-exception
                monitor-exit(r4)     // Catch: java.lang.Throwable -> L79
                throw r5     // Catch: java.lang.Throwable -> L7c java.lang.Throwable -> L7e java.lang.Throwable -> L7e java.io.FileNotFoundException -> L92
            L7c:
                r3 = move-exception
                goto Lc7
            L7e:
                r3 = move-exception
                java.lang.String r4 = "JobStore"
                java.lang.String r5 = "Error jobstore xml."
                android.util.Slog.wtf(r4, r5, r3)     // Catch: java.lang.Throwable -> L7c
                com.android.server.job.JobStore r3 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r3 = com.android.server.job.JobStore.access$500(r3)
                int r3 = r3.countAllJobsLoaded
                if (r3 >= 0) goto Lab
                goto L60
            L92:
                r3 = move-exception
                boolean r4 = com.android.server.job.JobStore.access$100()     // Catch: java.lang.Throwable -> L7c
                if (r4 == 0) goto La0
                java.lang.String r4 = "JobStore"
                java.lang.String r5 = "Could not find jobs file, probably there was nothing to load."
                android.util.Slog.d(r4, r5)     // Catch: java.lang.Throwable -> L7c
            La0:
                com.android.server.job.JobStore r3 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r3 = com.android.server.job.JobStore.access$500(r3)
                int r3 = r3.countAllJobsLoaded
                if (r3 >= 0) goto Lab
                goto L60
            Lab:
                java.lang.StringBuilder r3 = new java.lang.StringBuilder
                r3.<init>()
                java.lang.String r4 = "Read "
                r3.append(r4)
                r3.append(r0)
                java.lang.String r4 = " jobs"
                r3.append(r4)
                java.lang.String r3 = r3.toString()
                java.lang.String r4 = "JobStore"
                android.util.Slog.i(r4, r3)
                return
            Lc7:
                com.android.server.job.JobStore r4 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r4 = com.android.server.job.JobStore.access$500(r4)
                int r4 = r4.countAllJobsLoaded
                if (r4 >= 0) goto Le9
                com.android.server.job.JobStore r4 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r4 = com.android.server.job.JobStore.access$500(r4)
                r4.countAllJobsLoaded = r0
                com.android.server.job.JobStore r4 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r4 = com.android.server.job.JobStore.access$500(r4)
                r4.countSystemServerJobsLoaded = r1
                com.android.server.job.JobStore r4 = com.android.server.job.JobStore.this
                com.android.server.job.JobSchedulerInternal$JobStorePersistStats r4 = com.android.server.job.JobStore.access$500(r4)
                r4.countSystemSyncManagerJobsLoaded = r2
            Le9:
                throw r3
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.job.JobStore.ReadJobMapFromDiskRunnable.run():void");
        }

        private List<JobStatus> readJobMapImpl(FileInputStream fis, boolean rtcIsGood) throws XmlPullParserException, IOException {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
            int eventType = parser.getEventType();
            while (eventType != 2 && eventType != 1) {
                eventType = parser.next();
                Slog.d(JobStore.TAG, "Start tag: " + parser.getName());
            }
            if (eventType == 1) {
                if (JobStore.DEBUG) {
                    Slog.d(JobStore.TAG, "No persisted jobs.");
                }
                return null;
            }
            String tagName = parser.getName();
            if (!"job-info".equals(tagName)) {
                return null;
            }
            List<JobStatus> jobs = new ArrayList<>();
            try {
                int version = Integer.parseInt(parser.getAttributeValue(null, "version"));
                if (version != 0) {
                    Slog.d(JobStore.TAG, "Invalid version number, aborting jobs file read.");
                    return null;
                }
                int eventType2 = parser.next();
                do {
                    if (eventType2 == 2) {
                        String tagName2 = parser.getName();
                        if ("job".equals(tagName2)) {
                            JobStatus persistedJob = restoreJobFromXml(rtcIsGood, parser);
                            if (persistedJob != null) {
                                if (JobStore.DEBUG) {
                                    Slog.d(JobStore.TAG, "Read out " + persistedJob);
                                }
                                jobs.add(persistedJob);
                            } else {
                                Slog.d(JobStore.TAG, "Error reading job from file.");
                            }
                        }
                    }
                    eventType2 = parser.next();
                } while (eventType2 != 1);
                return jobs;
            } catch (NumberFormatException e) {
                Slog.e(JobStore.TAG, "Invalid version number, aborting jobs file read.");
                return null;
            }
        }

        /* JADX WARN: Multi-variable type inference failed */
        /* JADX WARN: Type inference failed for: r5v18 */
        /* JADX WARN: Type inference failed for: r5v22 */
        /* JADX WARN: Type inference failed for: r5v4, types: [com.android.server.job.controllers.JobStatus, java.lang.String] */
        private JobStatus restoreJobFromXml(boolean rtcIsGood, XmlPullParser parser) throws XmlPullParserException, IOException {
            int eventType;
            int eventType2;
            long periodMillis;
            long longValue;
            long flexMillis;
            int internalFlags;
            boolean z;
            Pair<Long, Long> elapsedRuntimes;
            int eventType3;
            String sourcePackageName;
            ReadJobMapFromDiskRunnable readJobMapFromDiskRunnable = this;
            JobStatus jobStatus = null;
            try {
                JobInfo.Builder jobBuilder = readJobMapFromDiskRunnable.buildBuilderFromXml(parser);
                boolean z2 = true;
                jobBuilder.setPersisted(true);
                int uid = Integer.parseInt(parser.getAttributeValue(null, WatchlistLoggingHandler.WatchlistEventKeys.UID));
                String val = parser.getAttributeValue(null, xpInputManagerService.InputPolicyKey.KEY_PRIORITY);
                if (val != null) {
                    jobBuilder.setPriority(Integer.parseInt(val));
                }
                String val2 = parser.getAttributeValue(null, xpInputManagerService.InputPolicyKey.KEY_FLAGS);
                if (val2 != null) {
                    jobBuilder.setFlags(Integer.parseInt(val2));
                }
                String val3 = parser.getAttributeValue(null, "internalFlags");
                int internalFlags2 = val3 != null ? Integer.parseInt(val3) : 0;
                try {
                    String val4 = parser.getAttributeValue(null, "sourceUserId");
                    int sourceUserId = val4 == null ? -1 : Integer.parseInt(val4);
                    String val5 = parser.getAttributeValue(null, "lastSuccessfulRunTime");
                    long lastSuccessfulRunTime = val5 == null ? 0L : Long.parseLong(val5);
                    String val6 = parser.getAttributeValue(null, "lastFailedRunTime");
                    long lastFailedRunTime = val6 == null ? 0L : Long.parseLong(val6);
                    String sourcePackageName2 = parser.getAttributeValue(null, "sourcePackageName");
                    String sourceTag = parser.getAttributeValue(null, "sourceTag");
                    while (true) {
                        eventType = parser.next();
                        if (eventType != 4) {
                            break;
                        }
                        readJobMapFromDiskRunnable = this;
                        jobStatus = null;
                    }
                    if (eventType == 2 && JobStore.XML_TAG_PARAMS_CONSTRAINTS.equals(parser.getName())) {
                        try {
                            readJobMapFromDiskRunnable.buildConstraintsFromXml(jobBuilder, parser);
                            parser.next();
                            ?? r5 = jobStatus;
                            while (true) {
                                eventType2 = parser.next();
                                if (eventType2 != 4) {
                                    break;
                                }
                                z2 = z2;
                                r5 = r5;
                                readJobMapFromDiskRunnable = this;
                            }
                            if (eventType2 != 2) {
                                return r5;
                            }
                            try {
                                Pair<Long, Long> rtcRuntimes = readJobMapFromDiskRunnable.buildRtcExecutionTimesFromXml(parser);
                                long elapsedNow = JobSchedulerService.sElapsedRealtimeClock.millis();
                                Pair<Long, Long> elapsedRuntimes2 = JobStore.convertRtcBoundsToElapsed(rtcRuntimes, elapsedNow);
                                if (JobStore.XML_TAG_PERIODIC.equals(parser.getName())) {
                                    try {
                                        periodMillis = Long.parseLong(parser.getAttributeValue(r5, "period"));
                                        String val7 = parser.getAttributeValue(r5, "flex");
                                        if (val7 == null) {
                                            longValue = periodMillis;
                                        } else {
                                            try {
                                                longValue = Long.valueOf(val7).longValue();
                                            } catch (NumberFormatException e) {
                                                Slog.d(JobStore.TAG, "Error reading periodic execution criteria, skipping.");
                                                return null;
                                            }
                                        }
                                        flexMillis = longValue;
                                        internalFlags = internalFlags2;
                                    } catch (NumberFormatException e2) {
                                    }
                                    try {
                                        jobBuilder.setPeriodic(periodMillis, flexMillis);
                                        if (((Long) elapsedRuntimes2.second).longValue() > elapsedNow + periodMillis + flexMillis) {
                                            long clampedLateRuntimeElapsed = elapsedNow + flexMillis + periodMillis;
                                            long clampedEarlyRuntimeElapsed = clampedLateRuntimeElapsed - flexMillis;
                                            z = false;
                                            Slog.w(JobStore.TAG, String.format("Periodic job for uid='%d' persisted run-time is too big [%s, %s]. Clamping to [%s,%s]", Integer.valueOf(uid), DateUtils.formatElapsedTime(((Long) elapsedRuntimes2.first).longValue() / 1000), DateUtils.formatElapsedTime(((Long) elapsedRuntimes2.second).longValue() / 1000), DateUtils.formatElapsedTime(clampedEarlyRuntimeElapsed / 1000), DateUtils.formatElapsedTime(clampedLateRuntimeElapsed / 1000)));
                                            elapsedRuntimes2 = Pair.create(Long.valueOf(clampedEarlyRuntimeElapsed), Long.valueOf(clampedLateRuntimeElapsed));
                                        } else {
                                            z = false;
                                        }
                                        elapsedRuntimes = elapsedRuntimes2;
                                    } catch (NumberFormatException e3) {
                                        Slog.d(JobStore.TAG, "Error reading periodic execution criteria, skipping.");
                                        return null;
                                    }
                                } else {
                                    internalFlags = internalFlags2;
                                    z = false;
                                    if (!JobStore.XML_TAG_ONEOFF.equals(parser.getName())) {
                                        if (JobStore.DEBUG) {
                                            Slog.d(JobStore.TAG, "Invalid parameter tag, skipping - " + parser.getName());
                                            return null;
                                        }
                                        return null;
                                    }
                                    try {
                                        if (((Long) elapsedRuntimes2.first).longValue() != 0) {
                                            try {
                                                jobBuilder.setMinimumLatency(((Long) elapsedRuntimes2.first).longValue() - elapsedNow);
                                            } catch (NumberFormatException e4) {
                                                Slog.d(JobStore.TAG, "Error reading job execution criteria, skipping.");
                                                return null;
                                            }
                                        }
                                        if (((Long) elapsedRuntimes2.second).longValue() != JobStatus.NO_LATEST_RUNTIME) {
                                            jobBuilder.setOverrideDeadline(((Long) elapsedRuntimes2.second).longValue() - elapsedNow);
                                        }
                                        elapsedRuntimes = elapsedRuntimes2;
                                    } catch (NumberFormatException e5) {
                                    }
                                }
                                maybeBuildBackoffPolicyFromXml(jobBuilder, parser);
                                parser.nextTag();
                                while (true) {
                                    eventType3 = parser.next();
                                    if (eventType3 != 4) {
                                        break;
                                    }
                                }
                                if (eventType3 == 2 && JobStore.XML_TAG_EXTRAS.equals(parser.getName())) {
                                    PersistableBundle extras = PersistableBundle.restoreFromXml(parser);
                                    jobBuilder.setExtras(extras);
                                    parser.nextTag();
                                    try {
                                        jobBuilder.build();
                                        if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(sourcePackageName2) && extras != null && extras.getBoolean("SyncManagerJob", z)) {
                                            sourcePackageName = extras.getString("owningPackage", sourcePackageName2);
                                            if (JobStore.DEBUG) {
                                                Slog.i(JobStore.TAG, "Fixing up sync job source package name from 'android' to '" + sourcePackageName + "'");
                                            }
                                        } else {
                                            sourcePackageName = sourcePackageName2;
                                        }
                                        JobSchedulerInternal service = (JobSchedulerInternal) LocalServices.getService(JobSchedulerInternal.class);
                                        int appBucket = JobSchedulerService.standbyBucketForPackage(sourcePackageName, sourceUserId, elapsedNow);
                                        long currentHeartbeat = service != null ? service.currentHeartbeat() : 0L;
                                        JobStatus js = new JobStatus(jobBuilder.build(), uid, sourcePackageName, sourceUserId, appBucket, currentHeartbeat, sourceTag, ((Long) elapsedRuntimes.first).longValue(), ((Long) elapsedRuntimes.second).longValue(), lastSuccessfulRunTime, lastFailedRunTime, rtcIsGood ? null : rtcRuntimes, internalFlags);
                                        return js;
                                    } catch (Exception e6) {
                                        Slog.w(JobStore.TAG, "Unable to build job from XML, ignoring: " + jobBuilder.summarize());
                                        return null;
                                    }
                                }
                                if (JobStore.DEBUG) {
                                    Slog.d(JobStore.TAG, "Error reading extras, skipping.");
                                    return null;
                                }
                                return null;
                            } catch (NumberFormatException e7) {
                                if (JobStore.DEBUG) {
                                    Slog.d(JobStore.TAG, "Error parsing execution time parameters, skipping.");
                                    return null;
                                }
                                return null;
                            }
                        } catch (NumberFormatException e8) {
                            JobStatus jobStatus2 = jobStatus;
                            Slog.d(JobStore.TAG, "Error reading constraints, skipping.");
                            return jobStatus2;
                        }
                    }
                    return jobStatus;
                } catch (NumberFormatException e9) {
                    Slog.e(JobStore.TAG, "Error parsing job's required fields, skipping");
                    return null;
                }
            } catch (NumberFormatException e10) {
            }
        }

        private JobInfo.Builder buildBuilderFromXml(XmlPullParser parser) throws NumberFormatException {
            int jobId = Integer.parseInt(parser.getAttributeValue(null, "jobid"));
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            ComponentName cname = new ComponentName(packageName, className);
            return new JobInfo.Builder(jobId, cname);
        }

        private void buildConstraintsFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser) {
            long unwantedCapabilities;
            String netCapabilities = parser.getAttributeValue(null, "net-capabilities");
            String netUnwantedCapabilities = parser.getAttributeValue(null, "net-unwanted-capabilities");
            String netTransportTypes = parser.getAttributeValue(null, "net-transport-types");
            if (netCapabilities == null || netTransportTypes == null) {
                String val = parser.getAttributeValue(null, "connectivity");
                if (val != null) {
                    jobBuilder.setRequiredNetworkType(1);
                }
                String val2 = parser.getAttributeValue(null, "metered");
                if (val2 != null) {
                    jobBuilder.setRequiredNetworkType(4);
                }
                String val3 = parser.getAttributeValue(null, "unmetered");
                if (val3 != null) {
                    jobBuilder.setRequiredNetworkType(2);
                }
                String val4 = parser.getAttributeValue(null, "not-roaming");
                if (val4 != null) {
                    jobBuilder.setRequiredNetworkType(3);
                }
            } else {
                NetworkRequest request = new NetworkRequest.Builder().build();
                if (netUnwantedCapabilities != null) {
                    unwantedCapabilities = Long.parseLong(netUnwantedCapabilities);
                } else {
                    unwantedCapabilities = BitUtils.packBits(request.networkCapabilities.getUnwantedCapabilities());
                }
                request.networkCapabilities.setCapabilities(BitUtils.unpackBits(Long.parseLong(netCapabilities)), BitUtils.unpackBits(unwantedCapabilities));
                request.networkCapabilities.setTransportTypes(BitUtils.unpackBits(Long.parseLong(netTransportTypes)));
                jobBuilder.setRequiredNetwork(request);
            }
            String val5 = parser.getAttributeValue(null, "idle");
            if (val5 != null) {
                jobBuilder.setRequiresDeviceIdle(true);
            }
            String val6 = parser.getAttributeValue(null, "charging");
            if (val6 != null) {
                jobBuilder.setRequiresCharging(true);
            }
            String val7 = parser.getAttributeValue(null, "battery-not-low");
            if (val7 != null) {
                jobBuilder.setRequiresBatteryNotLow(true);
            }
            String val8 = parser.getAttributeValue(null, "storage-not-low");
            if (val8 != null) {
                jobBuilder.setRequiresStorageNotLow(true);
            }
        }

        private void maybeBuildBackoffPolicyFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "initial-backoff");
            if (val != null) {
                long initialBackoff = Long.parseLong(val);
                int backoffPolicy = Integer.parseInt(parser.getAttributeValue(null, "backoff-policy"));
                jobBuilder.setBackoffCriteria(initialBackoff, backoffPolicy);
            }
        }

        private Pair<Long, Long> buildRtcExecutionTimesFromXml(XmlPullParser parser) throws NumberFormatException {
            long earliestRunTimeRtc;
            long latestRunTimeRtc;
            String val = parser.getAttributeValue(null, "delay");
            if (val != null) {
                earliestRunTimeRtc = Long.parseLong(val);
            } else {
                earliestRunTimeRtc = 0;
            }
            String val2 = parser.getAttributeValue(null, "deadline");
            if (val2 != null) {
                latestRunTimeRtc = Long.parseLong(val2);
            } else {
                latestRunTimeRtc = JobStatus.NO_LATEST_RUNTIME;
            }
            return Pair.create(Long.valueOf(earliestRunTimeRtc), Long.valueOf(latestRunTimeRtc));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class JobSet {
        @VisibleForTesting
        final SparseArray<ArraySet<JobStatus>> mJobs = new SparseArray<>();
        @VisibleForTesting
        final SparseArray<ArraySet<JobStatus>> mJobsPerSourceUid = new SparseArray<>();

        public List<JobStatus> getJobsByUid(int uid) {
            ArrayList<JobStatus> matchingJobs = new ArrayList<>();
            ArraySet<JobStatus> jobs = this.mJobs.get(uid);
            if (jobs != null) {
                matchingJobs.addAll(jobs);
            }
            return matchingJobs;
        }

        public List<JobStatus> getJobsByUser(int userId) {
            ArraySet<JobStatus> jobs;
            ArrayList<JobStatus> result = new ArrayList<>();
            for (int i = this.mJobsPerSourceUid.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(this.mJobsPerSourceUid.keyAt(i)) == userId && (jobs = this.mJobsPerSourceUid.valueAt(i)) != null) {
                    result.addAll(jobs);
                }
            }
            return result;
        }

        public boolean add(JobStatus job) {
            int uid = job.getUid();
            int sourceUid = job.getSourceUid();
            ArraySet<JobStatus> jobs = this.mJobs.get(uid);
            if (jobs == null) {
                jobs = new ArraySet<>();
                this.mJobs.put(uid, jobs);
            }
            ArraySet<JobStatus> jobsForSourceUid = this.mJobsPerSourceUid.get(sourceUid);
            if (jobsForSourceUid == null) {
                jobsForSourceUid = new ArraySet<>();
                this.mJobsPerSourceUid.put(sourceUid, jobsForSourceUid);
            }
            boolean added = jobs.add(job);
            boolean addedInSource = jobsForSourceUid.add(job);
            if (added != addedInSource) {
                Slog.wtf(JobStore.TAG, "mJobs and mJobsPerSourceUid mismatch; caller= " + added + " source= " + addedInSource);
            }
            return added || addedInSource;
        }

        public boolean remove(JobStatus job) {
            int uid = job.getUid();
            ArraySet<JobStatus> jobs = this.mJobs.get(uid);
            int sourceUid = job.getSourceUid();
            ArraySet<JobStatus> jobsForSourceUid = this.mJobsPerSourceUid.get(sourceUid);
            boolean didRemove = jobs != null && jobs.remove(job);
            boolean sourceRemove = jobsForSourceUid != null && jobsForSourceUid.remove(job);
            if (didRemove != sourceRemove) {
                Slog.wtf(JobStore.TAG, "Job presence mismatch; caller=" + didRemove + " source=" + sourceRemove);
            }
            if (didRemove || sourceRemove) {
                if (jobs != null && jobs.size() == 0) {
                    this.mJobs.remove(uid);
                }
                if (jobsForSourceUid != null && jobsForSourceUid.size() == 0) {
                    this.mJobsPerSourceUid.remove(sourceUid);
                }
                return true;
            }
            return false;
        }

        public void removeJobsOfNonUsers(final int[] whitelist) {
            Predicate<JobStatus> noSourceUser = new Predicate() { // from class: com.android.server.job.-$$Lambda$JobStore$JobSet$D9839QVHHu4X-hnxouyIMkP5NWA
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return JobStore.JobSet.lambda$removeJobsOfNonUsers$0(whitelist, (JobStatus) obj);
                }
            };
            Predicate<JobStatus> noCallingUser = new Predicate() { // from class: com.android.server.job.-$$Lambda$JobStore$JobSet$id1Y3Yh8Y9sEb-njlNCUNay6U9k
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return JobStore.JobSet.lambda$removeJobsOfNonUsers$1(whitelist, (JobStatus) obj);
                }
            };
            removeAll(noSourceUser.or(noCallingUser));
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$removeJobsOfNonUsers$0(int[] whitelist, JobStatus job) {
            return !ArrayUtils.contains(whitelist, job.getSourceUserId());
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$removeJobsOfNonUsers$1(int[] whitelist, JobStatus job) {
            return !ArrayUtils.contains(whitelist, job.getUserId());
        }

        private void removeAll(Predicate<JobStatus> predicate) {
            for (int jobSetIndex = this.mJobs.size() - 1; jobSetIndex >= 0; jobSetIndex--) {
                ArraySet<JobStatus> jobs = this.mJobs.valueAt(jobSetIndex);
                for (int jobIndex = jobs.size() - 1; jobIndex >= 0; jobIndex--) {
                    if (predicate.test(jobs.valueAt(jobIndex))) {
                        jobs.removeAt(jobIndex);
                    }
                }
                int jobIndex2 = jobs.size();
                if (jobIndex2 == 0) {
                    this.mJobs.removeAt(jobSetIndex);
                }
            }
            for (int jobSetIndex2 = this.mJobsPerSourceUid.size() - 1; jobSetIndex2 >= 0; jobSetIndex2--) {
                ArraySet<JobStatus> jobs2 = this.mJobsPerSourceUid.valueAt(jobSetIndex2);
                for (int jobIndex3 = jobs2.size() - 1; jobIndex3 >= 0; jobIndex3--) {
                    if (predicate.test(jobs2.valueAt(jobIndex3))) {
                        jobs2.removeAt(jobIndex3);
                    }
                }
                int jobIndex4 = jobs2.size();
                if (jobIndex4 == 0) {
                    this.mJobsPerSourceUid.removeAt(jobSetIndex2);
                }
            }
        }

        public boolean contains(JobStatus job) {
            int uid = job.getUid();
            ArraySet<JobStatus> jobs = this.mJobs.get(uid);
            return jobs != null && jobs.contains(job);
        }

        public JobStatus get(int uid, int jobId) {
            ArraySet<JobStatus> jobs = this.mJobs.get(uid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    JobStatus job = jobs.valueAt(i);
                    if (job.getJobId() == jobId) {
                        return job;
                    }
                }
                return null;
            }
            return null;
        }

        public List<JobStatus> getAllJobs() {
            ArrayList<JobStatus> allJobs = new ArrayList<>(size());
            for (int i = this.mJobs.size() - 1; i >= 0; i--) {
                ArraySet<JobStatus> jobs = this.mJobs.valueAt(i);
                if (jobs != null) {
                    for (int j = jobs.size() - 1; j >= 0; j--) {
                        allJobs.add(jobs.valueAt(j));
                    }
                }
            }
            return allJobs;
        }

        public void clear() {
            this.mJobs.clear();
            this.mJobsPerSourceUid.clear();
        }

        public int size() {
            int total = 0;
            for (int i = this.mJobs.size() - 1; i >= 0; i--) {
                total += this.mJobs.valueAt(i).size();
            }
            return total;
        }

        public int countJobsForUid(int uid) {
            int total = 0;
            ArraySet<JobStatus> jobs = this.mJobs.get(uid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    JobStatus job = jobs.valueAt(i);
                    if (job.getUid() == job.getSourceUid()) {
                        total++;
                    }
                }
            }
            return total;
        }

        public void forEachJob(Predicate<JobStatus> filterPredicate, Consumer<JobStatus> functor) {
            for (int uidIndex = this.mJobs.size() - 1; uidIndex >= 0; uidIndex--) {
                ArraySet<JobStatus> jobs = this.mJobs.valueAt(uidIndex);
                if (jobs != null) {
                    for (int i = jobs.size() - 1; i >= 0; i--) {
                        JobStatus jobStatus = jobs.valueAt(i);
                        if (filterPredicate == null || filterPredicate.test(jobStatus)) {
                            functor.accept(jobStatus);
                        }
                    }
                }
            }
        }

        public void forEachJob(int callingUid, Consumer<JobStatus> functor) {
            ArraySet<JobStatus> jobs = this.mJobs.get(callingUid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    functor.accept(jobs.valueAt(i));
                }
            }
        }

        public void forEachJobForSourceUid(int sourceUid, Consumer<JobStatus> functor) {
            ArraySet<JobStatus> jobs = this.mJobsPerSourceUid.get(sourceUid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    functor.accept(jobs.valueAt(i));
                }
            }
        }
    }
}
