package com.android.server.content;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import com.android.internal.annotations.GuardedBy;
import com.android.server.slice.SliceClientPermissions;
/* loaded from: classes.dex */
public class SyncJobService extends JobService {
    public static final String EXTRA_MESSENGER = "messenger";
    private static final String TAG = "SyncManager";
    private Messenger mMessenger;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<JobParameters> mJobParamsMap = new SparseArray<>();
    @GuardedBy("mLock")
    private final SparseBooleanArray mStartedSyncs = new SparseBooleanArray();
    @GuardedBy("mLock")
    private final SparseLongArray mJobStartUptimes = new SparseLongArray();
    private final SyncLogger mLogger = SyncLogger.getInstance();

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.mMessenger = (Messenger) intent.getParcelableExtra(EXTRA_MESSENGER);
        Message m = Message.obtain();
        m.what = 7;
        m.obj = this;
        sendMessage(m);
        return 2;
    }

    private void sendMessage(Message message) {
        if (this.mMessenger == null) {
            Slog.e("SyncManager", "Messenger not initialized.");
            return;
        }
        try {
            this.mMessenger.send(message);
        } catch (RemoteException e) {
            Slog.e("SyncManager", e.toString());
        }
    }

    @Override // android.app.job.JobService
    public boolean onStartJob(JobParameters params) {
        this.mLogger.purgeOldLogs();
        boolean isLoggable = Log.isLoggable("SyncManager", 2);
        synchronized (this.mLock) {
            int jobId = params.getJobId();
            this.mJobParamsMap.put(jobId, params);
            this.mStartedSyncs.delete(jobId);
            this.mJobStartUptimes.put(jobId, SystemClock.uptimeMillis());
        }
        Message m = Message.obtain();
        m.what = 10;
        SyncOperation op = SyncOperation.maybeCreateFromJobExtras(params.getExtras());
        this.mLogger.log("onStartJob() jobid=", Integer.valueOf(params.getJobId()), " op=", op);
        if (op == null) {
            Slog.e("SyncManager", "Got invalid job " + params.getJobId());
            return false;
        }
        if (isLoggable) {
            Slog.v("SyncManager", "Got start job message " + op.target);
        }
        m.obj = op;
        sendMessage(m);
        return true;
    }

    @Override // android.app.job.JobService
    public boolean onStopJob(JobParameters params) {
        if (Log.isLoggable("SyncManager", 2)) {
            Slog.v("SyncManager", "onStopJob called " + params.getJobId() + ", reason: " + params.getStopReason());
        }
        boolean readyToSync = SyncManager.readyToSync();
        this.mLogger.log("onStopJob() ", this.mLogger.jobParametersToString(params), " readyToSync=", Boolean.valueOf(readyToSync));
        synchronized (this.mLock) {
            int jobId = params.getJobId();
            this.mJobParamsMap.remove(jobId);
            long startUptime = this.mJobStartUptimes.get(jobId);
            long nowUptime = SystemClock.uptimeMillis();
            long runtime = nowUptime - startUptime;
            if (startUptime == 0) {
                wtf("Job " + jobId + " start uptime not found:  params=" + jobParametersToString(params));
            } else if (runtime > 60000 && readyToSync && !this.mStartedSyncs.get(jobId)) {
                wtf("Job " + jobId + " didn't start:  startUptime=" + startUptime + " nowUptime=" + nowUptime + " params=" + jobParametersToString(params));
            }
            this.mStartedSyncs.delete(jobId);
            this.mJobStartUptimes.delete(jobId);
        }
        Message m = Message.obtain();
        m.what = 11;
        m.obj = SyncOperation.maybeCreateFromJobExtras(params.getExtras());
        if (m.obj == null) {
            return false;
        }
        m.arg1 = params.getStopReason() != 0 ? 1 : 0;
        m.arg2 = params.getStopReason() != 3 ? 0 : 1;
        sendMessage(m);
        return false;
    }

    public void callJobFinished(int jobId, boolean needsReschedule, String why) {
        synchronized (this.mLock) {
            JobParameters params = this.mJobParamsMap.get(jobId);
            this.mLogger.log("callJobFinished()", " jobid=", Integer.valueOf(jobId), " needsReschedule=", Boolean.valueOf(needsReschedule), " ", this.mLogger.jobParametersToString(params), " why=", why);
            if (params != null) {
                jobFinished(params, needsReschedule);
                this.mJobParamsMap.remove(jobId);
            } else {
                Slog.e("SyncManager", "Job params not found for " + String.valueOf(jobId));
            }
        }
    }

    public void markSyncStarted(int jobId) {
        synchronized (this.mLock) {
            this.mStartedSyncs.put(jobId, true);
        }
    }

    public static String jobParametersToString(JobParameters params) {
        if (params == null) {
            return "job:null";
        }
        return "job:#" + params.getJobId() + ":sr=[" + params.getStopReason() + SliceClientPermissions.SliceAuthority.DELIMITER + params.getDebugStopReason() + "]:" + SyncOperation.maybeCreateFromJobExtras(params.getExtras());
    }

    private void wtf(String message) {
        this.mLogger.log(message);
        Slog.wtf("SyncManager", message);
    }
}
