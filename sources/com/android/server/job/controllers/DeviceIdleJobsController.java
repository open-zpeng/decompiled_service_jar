package com.android.server.job.controllers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.DeviceIdleController;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;
/* loaded from: classes.dex */
public final class DeviceIdleJobsController extends StateController {
    private static final long BACKGROUND_JOBS_DELAY = 3000;
    private static final boolean DEBUG;
    static final int PROCESS_BACKGROUND_JOBS = 1;
    private static final String TAG = "JobScheduler.DeviceIdle";
    private final ArraySet<JobStatus> mAllowInIdleJobs;
    private final BroadcastReceiver mBroadcastReceiver;
    private boolean mDeviceIdleMode;
    private final DeviceIdleUpdateFunctor mDeviceIdleUpdateFunctor;
    private int[] mDeviceIdleWhitelistAppIds;
    private final SparseBooleanArray mForegroundUids;
    private final DeviceIdleJobsDelayHandler mHandler;
    private final DeviceIdleController.LocalService mLocalDeviceIdleController;
    private final PowerManager mPowerManager;
    private int[] mPowerSaveTempWhitelistAppIds;

    static {
        DEBUG = JobSchedulerService.DEBUG || Log.isLoggable(TAG, 3);
    }

    public DeviceIdleJobsController(JobSchedulerService service) {
        super(service);
        this.mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.job.controllers.DeviceIdleJobsController.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                char c;
                String action = intent.getAction();
                int hashCode = action.hashCode();
                boolean z = false;
                int i = 0;
                z = false;
                if (hashCode == -712152692) {
                    if (action.equals("android.os.action.POWER_SAVE_TEMP_WHITELIST_CHANGED")) {
                        c = 3;
                    }
                    c = 65535;
                } else if (hashCode == -65633567) {
                    if (action.equals("android.os.action.POWER_SAVE_WHITELIST_CHANGED")) {
                        c = 2;
                    }
                    c = 65535;
                } else if (hashCode != 498807504) {
                    if (hashCode == 870701415 && action.equals("android.os.action.DEVICE_IDLE_MODE_CHANGED")) {
                        c = 1;
                    }
                    c = 65535;
                } else {
                    if (action.equals("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED")) {
                        c = 0;
                    }
                    c = 65535;
                }
                switch (c) {
                    case 0:
                    case 1:
                        DeviceIdleJobsController deviceIdleJobsController = DeviceIdleJobsController.this;
                        if (DeviceIdleJobsController.this.mPowerManager != null && (DeviceIdleJobsController.this.mPowerManager.isDeviceIdleMode() || DeviceIdleJobsController.this.mPowerManager.isLightDeviceIdleMode())) {
                            z = true;
                        }
                        deviceIdleJobsController.updateIdleMode(z);
                        return;
                    case 2:
                        synchronized (DeviceIdleJobsController.this.mLock) {
                            DeviceIdleJobsController.this.mDeviceIdleWhitelistAppIds = DeviceIdleJobsController.this.mLocalDeviceIdleController.getPowerSaveWhitelistUserAppIds();
                            if (DeviceIdleJobsController.DEBUG) {
                                Slog.d(DeviceIdleJobsController.TAG, "Got whitelist " + Arrays.toString(DeviceIdleJobsController.this.mDeviceIdleWhitelistAppIds));
                            }
                        }
                        return;
                    case 3:
                        synchronized (DeviceIdleJobsController.this.mLock) {
                            DeviceIdleJobsController.this.mPowerSaveTempWhitelistAppIds = DeviceIdleJobsController.this.mLocalDeviceIdleController.getPowerSaveTempWhitelistAppIds();
                            if (DeviceIdleJobsController.DEBUG) {
                                Slog.d(DeviceIdleJobsController.TAG, "Got temp whitelist " + Arrays.toString(DeviceIdleJobsController.this.mPowerSaveTempWhitelistAppIds));
                            }
                            boolean changed = false;
                            while (true) {
                                int i2 = i;
                                if (i2 < DeviceIdleJobsController.this.mAllowInIdleJobs.size()) {
                                    changed |= DeviceIdleJobsController.this.updateTaskStateLocked((JobStatus) DeviceIdleJobsController.this.mAllowInIdleJobs.valueAt(i2));
                                    i = i2 + 1;
                                } else if (changed) {
                                    DeviceIdleJobsController.this.mStateChangedListener.onControllerStateChanged();
                                }
                            }
                        }
                        return;
                    default:
                        return;
                }
            }
        };
        this.mHandler = new DeviceIdleJobsDelayHandler(this.mContext.getMainLooper());
        this.mPowerManager = (PowerManager) this.mContext.getSystemService("power");
        this.mLocalDeviceIdleController = (DeviceIdleController.LocalService) LocalServices.getService(DeviceIdleController.LocalService.class);
        this.mDeviceIdleWhitelistAppIds = this.mLocalDeviceIdleController.getPowerSaveWhitelistUserAppIds();
        this.mPowerSaveTempWhitelistAppIds = this.mLocalDeviceIdleController.getPowerSaveTempWhitelistAppIds();
        this.mDeviceIdleUpdateFunctor = new DeviceIdleUpdateFunctor();
        this.mAllowInIdleJobs = new ArraySet<>();
        this.mForegroundUids = new SparseBooleanArray();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.os.action.DEVICE_IDLE_MODE_CHANGED");
        filter.addAction("android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED");
        filter.addAction("android.os.action.POWER_SAVE_WHITELIST_CHANGED");
        filter.addAction("android.os.action.POWER_SAVE_TEMP_WHITELIST_CHANGED");
        this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, filter, null, null);
    }

    void updateIdleMode(boolean enabled) {
        boolean changed = false;
        synchronized (this.mLock) {
            if (this.mDeviceIdleMode != enabled) {
                changed = true;
            }
            this.mDeviceIdleMode = enabled;
            if (DEBUG) {
                Slog.d(TAG, "mDeviceIdleMode=" + this.mDeviceIdleMode);
            }
            if (enabled) {
                this.mHandler.removeMessages(1);
                this.mService.getJobStore().forEachJob(this.mDeviceIdleUpdateFunctor);
            } else {
                for (int i = 0; i < this.mForegroundUids.size(); i++) {
                    if (this.mForegroundUids.valueAt(i)) {
                        this.mService.getJobStore().forEachJobForSourceUid(this.mForegroundUids.keyAt(i), this.mDeviceIdleUpdateFunctor);
                    }
                }
                this.mHandler.sendEmptyMessageDelayed(1, BACKGROUND_JOBS_DELAY);
            }
        }
        if (changed) {
            this.mStateChangedListener.onDeviceIdleStateChanged(enabled);
        }
    }

    public void setUidActiveLocked(int uid, boolean active) {
        boolean changed = active != this.mForegroundUids.get(uid);
        if (!changed) {
            return;
        }
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("uid ");
            sb.append(uid);
            sb.append(" going ");
            sb.append(active ? "active" : "inactive");
            Slog.d(TAG, sb.toString());
        }
        this.mForegroundUids.put(uid, active);
        this.mDeviceIdleUpdateFunctor.mChanged = false;
        this.mService.getJobStore().forEachJobForSourceUid(uid, this.mDeviceIdleUpdateFunctor);
        if (this.mDeviceIdleUpdateFunctor.mChanged) {
            this.mStateChangedListener.onControllerStateChanged();
        }
    }

    boolean isWhitelistedLocked(JobStatus job) {
        return Arrays.binarySearch(this.mDeviceIdleWhitelistAppIds, UserHandle.getAppId(job.getSourceUid())) >= 0;
    }

    boolean isTempWhitelistedLocked(JobStatus job) {
        return ArrayUtils.contains(this.mPowerSaveTempWhitelistAppIds, UserHandle.getAppId(job.getSourceUid()));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updateTaskStateLocked(JobStatus task) {
        boolean enableTask = false;
        boolean allowInIdle = (task.getFlags() & 2) != 0 && (this.mForegroundUids.get(task.getSourceUid()) || isTempWhitelistedLocked(task));
        boolean whitelisted = isWhitelistedLocked(task);
        if (!this.mDeviceIdleMode || whitelisted || allowInIdle) {
            enableTask = true;
        }
        return task.setDeviceNotDozingConstraintSatisfied(enableTask, whitelisted);
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if ((jobStatus.getFlags() & 2) != 0) {
            this.mAllowInIdleJobs.add(jobStatus);
        }
        updateTaskStateLocked(jobStatus);
    }

    @Override // com.android.server.job.controllers.StateController
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob, boolean forUpdate) {
        if ((jobStatus.getFlags() & 2) != 0) {
            this.mAllowInIdleJobs.remove(jobStatus);
        }
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(final IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        pw.println("Idle mode: " + this.mDeviceIdleMode);
        pw.println();
        this.mService.getJobStore().forEachJob(predicate, new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$DeviceIdleJobsController$essc-q8XD1L8ojfbmN1Aow_AVPk
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DeviceIdleJobsController.lambda$dumpControllerStateLocked$0(DeviceIdleJobsController.this, pw, (JobStatus) obj);
            }
        });
    }

    public static /* synthetic */ void lambda$dumpControllerStateLocked$0(DeviceIdleJobsController deviceIdleJobsController, IndentingPrintWriter pw, JobStatus jobStatus) {
        pw.print(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
        jobStatus.printUniqueId(pw);
        pw.print(" from ");
        UserHandle.formatUid(pw, jobStatus.getSourceUid());
        pw.print(": ");
        pw.print(jobStatus.getSourcePackageName());
        pw.print((jobStatus.satisfiedConstraints & 33554432) != 0 ? " RUNNABLE" : " WAITING");
        if (jobStatus.dozeWhitelisted) {
            pw.print(" WHITELISTED");
        }
        if (deviceIdleJobsController.mAllowInIdleJobs.contains(jobStatus)) {
            pw.print(" ALLOWED_IN_DOZE");
        }
        pw.println();
    }

    @Override // com.android.server.job.controllers.StateController
    public void dumpControllerStateLocked(final ProtoOutputStream proto, long fieldId, Predicate<JobStatus> predicate) {
        long token = proto.start(fieldId);
        long mToken = proto.start(1146756268037L);
        proto.write(1133871366145L, this.mDeviceIdleMode);
        this.mService.getJobStore().forEachJob(predicate, new Consumer() { // from class: com.android.server.job.controllers.-$$Lambda$DeviceIdleJobsController$JMszgdQK87AK2bjaiI_rwQuTKpc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DeviceIdleJobsController.lambda$dumpControllerStateLocked$1(DeviceIdleJobsController.this, proto, (JobStatus) obj);
            }
        });
        proto.end(mToken);
        proto.end(token);
    }

    public static /* synthetic */ void lambda$dumpControllerStateLocked$1(DeviceIdleJobsController deviceIdleJobsController, ProtoOutputStream proto, JobStatus jobStatus) {
        long jsToken = proto.start(2246267895810L);
        jobStatus.writeToShortProto(proto, 1146756268033L);
        proto.write(1120986464258L, jobStatus.getSourceUid());
        proto.write(1138166333443L, jobStatus.getSourcePackageName());
        proto.write(1133871366148L, (jobStatus.satisfiedConstraints & 33554432) != 0);
        proto.write(1133871366149L, jobStatus.dozeWhitelisted);
        proto.write(1133871366150L, deviceIdleJobsController.mAllowInIdleJobs.contains(jobStatus));
        proto.end(jsToken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class DeviceIdleUpdateFunctor implements Consumer<JobStatus> {
        boolean mChanged;

        DeviceIdleUpdateFunctor() {
        }

        @Override // java.util.function.Consumer
        public void accept(JobStatus jobStatus) {
            this.mChanged |= DeviceIdleJobsController.this.updateTaskStateLocked(jobStatus);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class DeviceIdleJobsDelayHandler extends Handler {
        public DeviceIdleJobsDelayHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                synchronized (DeviceIdleJobsController.this.mLock) {
                    DeviceIdleJobsController.this.mDeviceIdleUpdateFunctor.mChanged = false;
                    DeviceIdleJobsController.this.mService.getJobStore().forEachJob(DeviceIdleJobsController.this.mDeviceIdleUpdateFunctor);
                    if (DeviceIdleJobsController.this.mDeviceIdleUpdateFunctor.mChanged) {
                        DeviceIdleJobsController.this.mStateChangedListener.onControllerStateChanged();
                    }
                }
            }
        }
    }
}
