package com.android.server.job.controllers;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.am.ActivityManagerService;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.controllers.IdleController;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import java.util.function.Predicate;
/* loaded from: classes.dex */
public final class IdleController extends StateController {
    private static final boolean DEBUG;
    private static final String TAG = "JobScheduler.Idle";
    IdlenessTracker mIdleTracker;
    private long mIdleWindowSlop;
    private long mInactivityIdleThreshold;
    final ArraySet<JobStatus> mTrackedTasks;

    static {
        DEBUG = JobSchedulerService.DEBUG || Log.isLoggable(TAG, 3);
    }

    public IdleController(JobSchedulerService service) {
        super(service);
        this.mTrackedTasks = new ArraySet<>();
        initIdleStateTracking();
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasIdleConstraint()) {
            this.mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(8);
            taskStatus.setIdleConstraintSatisfied(this.mIdleTracker.isIdle());
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob, boolean forUpdate) {
        if (taskStatus.clearTrackingController(8)) {
            this.mTrackedTasks.remove(taskStatus);
        }
    }

    void reportNewIdleState(boolean isIdle) {
        synchronized (this.mLock) {
            for (int i = this.mTrackedTasks.size() - 1; i >= 0; i--) {
                this.mTrackedTasks.valueAt(i).setIdleConstraintSatisfied(isIdle);
            }
        }
        this.mStateChangedListener.onControllerStateChanged();
    }

    private void initIdleStateTracking() {
        this.mInactivityIdleThreshold = this.mContext.getResources().getInteger(17694793);
        this.mIdleWindowSlop = this.mContext.getResources().getInteger(17694792);
        this.mIdleTracker = new IdlenessTracker();
        this.mIdleTracker.startTracking();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class IdlenessTracker extends BroadcastReceiver {
        private AlarmManager mAlarm;
        private AlarmManager.OnAlarmListener mIdleAlarmListener = new AlarmManager.OnAlarmListener() { // from class: com.android.server.job.controllers.-$$Lambda$IdleController$IdlenessTracker$nTdS-lGBXcES5VWKcJFmQFgU7IU
            @Override // android.app.AlarmManager.OnAlarmListener
            public final void onAlarm() {
                IdleController.IdlenessTracker.this.handleIdleTrigger();
            }
        };
        private boolean mIdle = false;
        private boolean mScreenOn = true;
        private boolean mDockIdle = false;

        public IdlenessTracker() {
            this.mAlarm = (AlarmManager) IdleController.this.mContext.getSystemService("alarm");
        }

        public boolean isIdle() {
            return this.mIdle;
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.SCREEN_ON");
            filter.addAction("android.intent.action.SCREEN_OFF");
            filter.addAction("android.intent.action.DREAMING_STARTED");
            filter.addAction("android.intent.action.DREAMING_STOPPED");
            filter.addAction(ActivityManagerService.ACTION_TRIGGER_IDLE);
            filter.addAction("android.intent.action.DOCK_IDLE");
            filter.addAction("android.intent.action.DOCK_ACTIVE");
            IdleController.this.mContext.registerReceiver(this, filter);
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals("android.intent.action.SCREEN_ON") || action.equals("android.intent.action.DREAMING_STOPPED") || action.equals("android.intent.action.DOCK_ACTIVE")) {
                if (action.equals("android.intent.action.DOCK_ACTIVE")) {
                    if (!this.mScreenOn) {
                        return;
                    }
                    this.mDockIdle = false;
                } else {
                    this.mScreenOn = true;
                    this.mDockIdle = false;
                }
                if (IdleController.DEBUG) {
                    Slog.v(IdleController.TAG, "exiting idle : " + action);
                }
                this.mAlarm.cancel(this.mIdleAlarmListener);
                if (this.mIdle) {
                    this.mIdle = false;
                    IdleController.this.reportNewIdleState(this.mIdle);
                }
            } else if (action.equals("android.intent.action.SCREEN_OFF") || action.equals("android.intent.action.DREAMING_STARTED") || action.equals("android.intent.action.DOCK_IDLE")) {
                if (action.equals("android.intent.action.DOCK_IDLE")) {
                    if (!this.mScreenOn) {
                        return;
                    }
                    this.mDockIdle = true;
                } else {
                    this.mScreenOn = false;
                    this.mDockIdle = false;
                }
                long nowElapsed = JobSchedulerService.sElapsedRealtimeClock.millis();
                long when = IdleController.this.mInactivityIdleThreshold + nowElapsed;
                if (IdleController.DEBUG) {
                    Slog.v(IdleController.TAG, "Scheduling idle : " + action + " now:" + nowElapsed + " when=" + when);
                }
                this.mAlarm.setWindow(2, when, IdleController.this.mIdleWindowSlop, "JS idleness", this.mIdleAlarmListener, null);
            } else if (action.equals(ActivityManagerService.ACTION_TRIGGER_IDLE)) {
                handleIdleTrigger();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleIdleTrigger() {
            if (this.mIdle || (this.mScreenOn && !this.mDockIdle)) {
                if (IdleController.DEBUG) {
                    Slog.v(IdleController.TAG, "TRIGGER_IDLE received but not changing state; idle=" + this.mIdle + " screen=" + this.mScreenOn);
                    return;
                }
                return;
            }
            if (IdleController.DEBUG) {
                Slog.v(IdleController.TAG, "Idle trigger fired @ " + JobSchedulerService.sElapsedRealtimeClock.millis());
            }
            this.mIdle = true;
            IdleController.this.reportNewIdleState(this.mIdle);
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        pw.println("Currently idle: " + this.mIdleTracker.isIdle());
        pw.println();
        for (int i = 0; i < this.mTrackedTasks.size(); i++) {
            JobStatus js = this.mTrackedTasks.valueAt(i);
            if (predicate.test(js)) {
                pw.print(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                pw.println();
            }
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId, Predicate<JobStatus> predicate) {
        long token = proto.start(fieldId);
        long mToken = proto.start(1146756268038L);
        proto.write(1133871366145L, this.mIdleTracker.isIdle());
        for (int i = 0; i < this.mTrackedTasks.size(); i++) {
            JobStatus js = this.mTrackedTasks.valueAt(i);
            if (predicate.test(js)) {
                long jsToken = proto.start(2246267895810L);
                js.writeToShortProto(proto, 1146756268033L);
                proto.write(1120986464258L, js.getSourceUid());
                proto.end(jsToken);
            }
        }
        proto.end(mToken);
        proto.end(token);
    }
}
