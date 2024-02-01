package com.android.server.job.controllers.idle;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.util.Slog;
import com.android.server.am.ActivityManagerService;
import com.android.server.job.JobSchedulerService;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public final class DeviceIdlenessTracker extends BroadcastReceiver implements IdlenessTracker {
    private static final boolean DEBUG;
    private static final String TAG = "JobScheduler.DeviceIdlenessTracker";
    private AlarmManager mAlarm;
    private IdlenessListener mIdleListener;
    private long mIdleWindowSlop;
    private long mInactivityIdleThreshold;
    private AlarmManager.OnAlarmListener mIdleAlarmListener = new AlarmManager.OnAlarmListener() { // from class: com.android.server.job.controllers.idle.-$$Lambda$DeviceIdlenessTracker$-H1-ZwZAJ-vh10A0PeYXaZLj_R0
        @Override // android.app.AlarmManager.OnAlarmListener
        public final void onAlarm() {
            DeviceIdlenessTracker.this.lambda$new$0$DeviceIdlenessTracker();
        }
    };
    private boolean mIdle = false;
    private boolean mScreenOn = true;
    private boolean mDockIdle = false;

    static {
        DEBUG = JobSchedulerService.DEBUG || Log.isLoggable(TAG, 3);
    }

    @Override // com.android.server.job.controllers.idle.IdlenessTracker
    public boolean isIdle() {
        return this.mIdle;
    }

    @Override // com.android.server.job.controllers.idle.IdlenessTracker
    public void startTracking(Context context, IdlenessListener listener) {
        this.mIdleListener = listener;
        this.mInactivityIdleThreshold = context.getResources().getInteger(17694816);
        this.mIdleWindowSlop = context.getResources().getInteger(17694815);
        this.mAlarm = (AlarmManager) context.getSystemService("alarm");
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.SCREEN_ON");
        filter.addAction("android.intent.action.SCREEN_OFF");
        filter.addAction("android.intent.action.DREAMING_STARTED");
        filter.addAction("android.intent.action.DREAMING_STOPPED");
        filter.addAction(ActivityManagerService.ACTION_TRIGGER_IDLE);
        filter.addAction("android.intent.action.DOCK_IDLE");
        filter.addAction("android.intent.action.DOCK_ACTIVE");
        context.registerReceiver(this, filter);
    }

    @Override // com.android.server.job.controllers.idle.IdlenessTracker
    public void dump(PrintWriter pw) {
        pw.print("  mIdle: ");
        pw.println(this.mIdle);
        pw.print("  mScreenOn: ");
        pw.println(this.mScreenOn);
        pw.print("  mDockIdle: ");
        pw.println(this.mDockIdle);
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
            if (DEBUG) {
                Slog.v(TAG, "exiting idle : " + action);
            }
            this.mAlarm.cancel(this.mIdleAlarmListener);
            if (this.mIdle) {
                this.mIdle = false;
                this.mIdleListener.reportNewIdleState(this.mIdle);
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
            long when = this.mInactivityIdleThreshold + nowElapsed;
            if (DEBUG) {
                Slog.v(TAG, "Scheduling idle : " + action + " now:" + nowElapsed + " when=" + when);
            }
            this.mAlarm.setWindow(2, when, this.mIdleWindowSlop, "JS idleness", this.mIdleAlarmListener, null);
        } else if (action.equals(ActivityManagerService.ACTION_TRIGGER_IDLE)) {
            lambda$new$0$DeviceIdlenessTracker();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: handleIdleTrigger */
    public void lambda$new$0$DeviceIdlenessTracker() {
        if (!this.mIdle && (!this.mScreenOn || this.mDockIdle)) {
            if (DEBUG) {
                Slog.v(TAG, "Idle trigger fired @ " + JobSchedulerService.sElapsedRealtimeClock.millis());
            }
            this.mIdle = true;
            this.mIdleListener.reportNewIdleState(this.mIdle);
        } else if (DEBUG) {
            Slog.v(TAG, "TRIGGER_IDLE received but not changing state; idle=" + this.mIdle + " screen=" + this.mScreenOn);
        }
    }
}
