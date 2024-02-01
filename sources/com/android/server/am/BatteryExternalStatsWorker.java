package com.android.server.am;

import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiActivityEnergyInfo;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.ThreadLocalWorkSource;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;
import android.util.IntArray;
import android.util.Slog;
import android.util.StatsLog;
import android.util.TimeUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.stats.StatsCompanionService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import libcore.util.EmptyArray;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class BatteryExternalStatsWorker implements BatteryStatsImpl.ExternalStatsSync {
    private static final boolean DEBUG = false;
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;
    private static final long MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS = 750;
    private static final String TAG = "BatteryExternalStatsWorker";
    @GuardedBy({"this"})
    private Future<?> mBatteryLevelSync;
    private final Context mContext;
    @GuardedBy({"this"})
    private long mLastCollectionTimeStamp;
    @GuardedBy({"this"})
    private boolean mOnBattery;
    @GuardedBy({"this"})
    private boolean mOnBatteryScreenOff;
    private final BatteryStatsImpl mStats;
    @GuardedBy({"this"})
    private Future<?> mWakelockChangesUpdate;
    private final ScheduledExecutorService mExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() { // from class: com.android.server.am.-$$Lambda$BatteryExternalStatsWorker$ML8sXrbYk0MflPvsY2cfCYlcU0w
        @Override // java.util.concurrent.ThreadFactory
        public final Thread newThread(Runnable runnable) {
            return BatteryExternalStatsWorker.lambda$new$1(runnable);
        }
    });
    @GuardedBy({"this"})
    private int mUpdateFlags = 0;
    @GuardedBy({"this"})
    private Future<?> mCurrentFuture = null;
    @GuardedBy({"this"})
    private String mCurrentReason = null;
    @GuardedBy({"this"})
    private boolean mUseLatestStates = true;
    @GuardedBy({"this"})
    private final IntArray mUidsToRemove = new IntArray();
    private final Object mWorkerLock = new Object();
    @GuardedBy({"mWorkerLock"})
    private IWifiManager mWifiManager = null;
    @GuardedBy({"mWorkerLock"})
    private TelephonyManager mTelephony = null;
    @GuardedBy({"mWorkerLock"})
    private WifiActivityEnergyInfo mLastInfo = new WifiActivityEnergyInfo(0, 0, 0, new long[]{0}, 0, 0, 0, 0);
    private final Runnable mSyncTask = new Runnable() { // from class: com.android.server.am.BatteryExternalStatsWorker.1
        @Override // java.lang.Runnable
        public void run() {
            int updateFlags;
            String reason;
            int[] uidsToRemove;
            boolean onBattery;
            boolean onBatteryScreenOff;
            boolean useLatestStates;
            synchronized (BatteryExternalStatsWorker.this) {
                updateFlags = BatteryExternalStatsWorker.this.mUpdateFlags;
                reason = BatteryExternalStatsWorker.this.mCurrentReason;
                uidsToRemove = BatteryExternalStatsWorker.this.mUidsToRemove.size() > 0 ? BatteryExternalStatsWorker.this.mUidsToRemove.toArray() : EmptyArray.INT;
                onBattery = BatteryExternalStatsWorker.this.mOnBattery;
                onBatteryScreenOff = BatteryExternalStatsWorker.this.mOnBatteryScreenOff;
                useLatestStates = BatteryExternalStatsWorker.this.mUseLatestStates;
                BatteryExternalStatsWorker.this.mUpdateFlags = 0;
                BatteryExternalStatsWorker.this.mCurrentReason = null;
                BatteryExternalStatsWorker.this.mUidsToRemove.clear();
                BatteryExternalStatsWorker.this.mCurrentFuture = null;
                BatteryExternalStatsWorker.this.mUseLatestStates = true;
                if ((updateFlags & 31) != 0) {
                    BatteryExternalStatsWorker.this.cancelSyncDueToBatteryLevelChangeLocked();
                }
                if ((updateFlags & 1) != 0) {
                    BatteryExternalStatsWorker.this.cancelCpuSyncDueToWakelockChange();
                }
            }
            try {
                synchronized (BatteryExternalStatsWorker.this.mWorkerLock) {
                    BatteryExternalStatsWorker.this.updateExternalStatsLocked(reason, updateFlags, onBattery, onBatteryScreenOff, useLatestStates);
                }
                if ((updateFlags & 1) != 0) {
                    BatteryExternalStatsWorker.this.mStats.copyFromAllUidsCpuTimes();
                }
                synchronized (BatteryExternalStatsWorker.this.mStats) {
                    for (int uid : uidsToRemove) {
                        StatsLog.write(43, -1, uid, 0);
                        BatteryExternalStatsWorker.this.mStats.removeIsolatedUidLocked(uid);
                    }
                    BatteryExternalStatsWorker.this.mStats.clearPendingRemovedUids();
                }
            } catch (Exception e) {
                Slog.wtf(BatteryExternalStatsWorker.TAG, "Error updating external stats: ", e);
            }
            synchronized (BatteryExternalStatsWorker.this) {
                BatteryExternalStatsWorker.this.mLastCollectionTimeStamp = SystemClock.elapsedRealtime();
            }
        }
    };
    private final Runnable mWriteTask = new Runnable() { // from class: com.android.server.am.BatteryExternalStatsWorker.2
        @Override // java.lang.Runnable
        public void run() {
            synchronized (BatteryExternalStatsWorker.this.mStats) {
                BatteryExternalStatsWorker.this.mStats.writeAsyncLocked();
            }
        }
    };

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Thread lambda$new$1(final Runnable r) {
        Thread t = new Thread(new Runnable() { // from class: com.android.server.am.-$$Lambda$BatteryExternalStatsWorker$ddVY5lmqswnSjXppAxPTOHbuzzQ
            @Override // java.lang.Runnable
            public final void run() {
                BatteryExternalStatsWorker.lambda$new$0(r);
            }
        }, "batterystats-worker");
        t.setPriority(5);
        return t;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$new$0(Runnable r) {
        ThreadLocalWorkSource.setUid(Process.myUid());
        r.run();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BatteryExternalStatsWorker(Context context, BatteryStatsImpl stats) {
        this.mContext = context;
        this.mStats = stats;
    }

    public synchronized Future<?> scheduleSync(String reason, int flags) {
        return scheduleSyncLocked(reason, flags);
    }

    public synchronized Future<?> scheduleCpuSyncDueToRemovedUid(int uid) {
        this.mUidsToRemove.add(uid);
        return scheduleSyncLocked("remove-uid", 1);
    }

    public synchronized Future<?> scheduleCpuSyncDueToSettingChange() {
        return scheduleSyncLocked("setting-change", 1);
    }

    public Future<?> scheduleReadProcStateCpuTimes(boolean onBattery, boolean onBatteryScreenOff, long delayMillis) {
        synchronized (this.mStats) {
            if (this.mStats.trackPerProcStateCpuTimes()) {
                synchronized (this) {
                    if (this.mExecutorService.isShutdown()) {
                        return null;
                    }
                    return this.mExecutorService.schedule((Runnable) PooledLambda.obtainRunnable(new TriConsumer() { // from class: com.android.server.am.-$$Lambda$cC4f0pNQX9_D9f8AXLmKk2sArGY
                        public final void accept(Object obj, Object obj2, Object obj3) {
                            ((BatteryStatsImpl) obj).updateProcStateCpuTimes(((Boolean) obj2).booleanValue(), ((Boolean) obj3).booleanValue());
                        }
                    }, this.mStats, Boolean.valueOf(onBattery), Boolean.valueOf(onBatteryScreenOff)).recycleOnUse(), delayMillis, TimeUnit.MILLISECONDS);
                }
            }
            return null;
        }
    }

    public Future<?> scheduleCopyFromAllUidsCpuTimes(boolean onBattery, boolean onBatteryScreenOff) {
        synchronized (this.mStats) {
            if (this.mStats.trackPerProcStateCpuTimes()) {
                synchronized (this) {
                    if (this.mExecutorService.isShutdown()) {
                        return null;
                    }
                    return this.mExecutorService.submit((Runnable) PooledLambda.obtainRunnable(new TriConsumer() { // from class: com.android.server.am.-$$Lambda$7toxTvZDSEytL0rCkoEfGilPDWM
                        public final void accept(Object obj, Object obj2, Object obj3) {
                            ((BatteryStatsImpl) obj).copyFromAllUidsCpuTimes(((Boolean) obj2).booleanValue(), ((Boolean) obj3).booleanValue());
                        }
                    }, this.mStats, Boolean.valueOf(onBattery), Boolean.valueOf(onBatteryScreenOff)).recycleOnUse());
                }
            }
            return null;
        }
    }

    public Future<?> scheduleCpuSyncDueToScreenStateChange(boolean onBattery, boolean onBatteryScreenOff) {
        Future<?> scheduleSyncLocked;
        synchronized (this) {
            if (this.mCurrentFuture == null || (this.mUpdateFlags & 1) == 0) {
                this.mOnBattery = onBattery;
                this.mOnBatteryScreenOff = onBatteryScreenOff;
                this.mUseLatestStates = false;
            }
            scheduleSyncLocked = scheduleSyncLocked("screen-state", 1);
        }
        return scheduleSyncLocked;
    }

    public Future<?> scheduleCpuSyncDueToWakelockChange(long delayMillis) {
        Future<?> future;
        synchronized (this) {
            this.mWakelockChangesUpdate = scheduleDelayedSyncLocked(this.mWakelockChangesUpdate, new Runnable() { // from class: com.android.server.am.-$$Lambda$BatteryExternalStatsWorker$r3x3xYmhrLG8kgeNVPXl5EILHwU
                @Override // java.lang.Runnable
                public final void run() {
                    BatteryExternalStatsWorker.this.lambda$scheduleCpuSyncDueToWakelockChange$3$BatteryExternalStatsWorker();
                }
            }, delayMillis);
            future = this.mWakelockChangesUpdate;
        }
        return future;
    }

    public /* synthetic */ void lambda$scheduleCpuSyncDueToWakelockChange$3$BatteryExternalStatsWorker() {
        scheduleSync("wakelock-change", 1);
        scheduleRunnable(new Runnable() { // from class: com.android.server.am.-$$Lambda$BatteryExternalStatsWorker$PpNEY15dspg9oLlkg1OsyjrPTqw
            @Override // java.lang.Runnable
            public final void run() {
                BatteryExternalStatsWorker.this.lambda$scheduleCpuSyncDueToWakelockChange$2$BatteryExternalStatsWorker();
            }
        });
    }

    public /* synthetic */ void lambda$scheduleCpuSyncDueToWakelockChange$2$BatteryExternalStatsWorker() {
        this.mStats.postBatteryNeedsCpuUpdateMsg();
    }

    public void cancelCpuSyncDueToWakelockChange() {
        synchronized (this) {
            if (this.mWakelockChangesUpdate != null) {
                this.mWakelockChangesUpdate.cancel(false);
                this.mWakelockChangesUpdate = null;
            }
        }
    }

    public Future<?> scheduleSyncDueToBatteryLevelChange(long delayMillis) {
        Future<?> future;
        synchronized (this) {
            this.mBatteryLevelSync = scheduleDelayedSyncLocked(this.mBatteryLevelSync, new Runnable() { // from class: com.android.server.am.-$$Lambda$BatteryExternalStatsWorker$xR3yCbbVfCo3oq_xPiH7j5l5uac
                @Override // java.lang.Runnable
                public final void run() {
                    BatteryExternalStatsWorker.this.lambda$scheduleSyncDueToBatteryLevelChange$4$BatteryExternalStatsWorker();
                }
            }, delayMillis);
            future = this.mBatteryLevelSync;
        }
        return future;
    }

    public /* synthetic */ void lambda$scheduleSyncDueToBatteryLevelChange$4$BatteryExternalStatsWorker() {
        scheduleSync("battery-level", 31);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"this"})
    public void cancelSyncDueToBatteryLevelChangeLocked() {
        Future<?> future = this.mBatteryLevelSync;
        if (future != null) {
            future.cancel(false);
            this.mBatteryLevelSync = null;
        }
    }

    @GuardedBy({"this"})
    private Future<?> scheduleDelayedSyncLocked(Future<?> lastScheduledSync, Runnable syncRunnable, long delayMillis) {
        if (this.mExecutorService.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("worker shutdown"));
        }
        if (lastScheduledSync != null) {
            if (delayMillis == 0) {
                lastScheduledSync.cancel(false);
            } else {
                return lastScheduledSync;
            }
        }
        return this.mExecutorService.schedule(syncRunnable, delayMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized Future<?> scheduleWrite() {
        if (this.mExecutorService.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("worker shutdown"));
        }
        scheduleSyncLocked("write", 31);
        return this.mExecutorService.submit(this.mWriteTask);
    }

    public synchronized void scheduleRunnable(Runnable runnable) {
        if (!this.mExecutorService.isShutdown()) {
            this.mExecutorService.submit(runnable);
        }
    }

    public void shutdown() {
        this.mExecutorService.shutdownNow();
    }

    @GuardedBy({"this"})
    private Future<?> scheduleSyncLocked(String reason, int flags) {
        if (this.mExecutorService.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("worker shutdown"));
        }
        if (this.mCurrentFuture == null) {
            this.mUpdateFlags = flags;
            this.mCurrentReason = reason;
            this.mCurrentFuture = this.mExecutorService.submit(this.mSyncTask);
        }
        this.mUpdateFlags |= flags;
        return this.mCurrentFuture;
    }

    long getLastCollectionTimeStamp() {
        long j;
        synchronized (this) {
            j = this.mLastCollectionTimeStamp;
        }
        return j;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mWorkerLock"})
    public void updateExternalStatsLocked(String reason, int updateFlags, boolean onBattery, boolean onBatteryScreenOff, boolean useLatestStates) {
        boolean onBattery2;
        boolean onBatteryScreenOff2;
        BluetoothAdapter adapter;
        SynchronousResultReceiver wifiReceiver;
        SynchronousResultReceiver wifiReceiver2 = null;
        ResultReceiver resultReceiver = null;
        ResultReceiver resultReceiver2 = null;
        boolean railUpdated = false;
        if ((updateFlags & 2) != 0) {
            if (this.mWifiManager == null) {
                this.mWifiManager = IWifiManager.Stub.asInterface(ServiceManager.getService("wifi"));
            }
            IWifiManager iWifiManager = this.mWifiManager;
            if (iWifiManager == null) {
                wifiReceiver = null;
            } else {
                try {
                    if ((iWifiManager.getSupportedFeatures() & 65536) != 0) {
                        wifiReceiver2 = new SynchronousResultReceiver("wifi");
                        this.mWifiManager.requestActivityInfo(wifiReceiver2);
                    }
                    wifiReceiver = wifiReceiver2;
                } catch (RemoteException e) {
                    wifiReceiver = wifiReceiver2;
                }
            }
            synchronized (this.mStats) {
                this.mStats.updateRailStatsLocked();
            }
            railUpdated = true;
            wifiReceiver2 = wifiReceiver;
        }
        if ((updateFlags & 8) != 0 && (adapter = BluetoothAdapter.getDefaultAdapter()) != null) {
            resultReceiver = new SynchronousResultReceiver("bluetooth");
            adapter.requestControllerActivityEnergyInfo(resultReceiver);
        }
        if ((updateFlags & 4) != 0) {
            if (this.mTelephony == null) {
                this.mTelephony = TelephonyManager.from(this.mContext);
            }
            if (this.mTelephony != null) {
                resultReceiver2 = new SynchronousResultReceiver("telephony");
                this.mTelephony.requestModemActivityInfo(resultReceiver2);
            }
            if (!railUpdated) {
                synchronized (this.mStats) {
                    this.mStats.updateRailStatsLocked();
                }
            }
        }
        WifiActivityEnergyInfo wifiInfo = (WifiActivityEnergyInfo) awaitControllerInfo(wifiReceiver2);
        BluetoothActivityEnergyInfo bluetoothInfo = awaitControllerInfo(resultReceiver);
        ModemActivityInfo modemInfo = awaitControllerInfo(resultReceiver2);
        synchronized (this.mStats) {
            try {
                try {
                    this.mStats.addHistoryEventLocked(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), 14, reason, 0);
                    if ((updateFlags & 1) != 0) {
                        if (!useLatestStates) {
                            onBattery2 = onBattery;
                            onBatteryScreenOff2 = onBatteryScreenOff;
                        } else {
                            onBattery2 = this.mStats.isOnBatteryLocked();
                            try {
                                onBatteryScreenOff2 = this.mStats.isOnBatteryScreenOffLocked();
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        }
                        this.mStats.updateCpuTimeLocked(onBattery2, onBatteryScreenOff2);
                    }
                    if ((updateFlags & 31) != 0) {
                        this.mStats.updateKernelWakelocksLocked();
                        this.mStats.updateKernelMemoryBandwidthLocked();
                    }
                    if ((updateFlags & 16) != 0) {
                        this.mStats.updateRpmStatsLocked();
                    }
                    if (bluetoothInfo != null) {
                        if (bluetoothInfo.isValid()) {
                            this.mStats.updateBluetoothStateLocked(bluetoothInfo);
                        } else {
                            Slog.w(TAG, "bluetooth info is invalid: " + bluetoothInfo);
                        }
                    }
                    if (wifiInfo != null) {
                        if (wifiInfo.isValid()) {
                            this.mStats.updateWifiState(extractDeltaLocked(wifiInfo));
                        } else {
                            Slog.w(TAG, "wifi info is invalid: " + wifiInfo);
                        }
                    }
                    if (modemInfo != null) {
                        if (modemInfo.isValid()) {
                            this.mStats.updateMobileRadioState(modemInfo);
                            return;
                        }
                        Slog.w(TAG, "modem info is invalid: " + modemInfo);
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private static <T extends Parcelable> T awaitControllerInfo(SynchronousResultReceiver receiver) {
        if (receiver == null) {
            return null;
        }
        try {
            SynchronousResultReceiver.Result result = receiver.awaitResult((long) EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
            if (result.bundle != null) {
                result.bundle.setDefusable(true);
                T data = (T) result.bundle.getParcelable(StatsCompanionService.RESULT_RECEIVER_CONTROLLER_KEY);
                if (data != null) {
                    return data;
                }
            }
            Slog.e(TAG, "no controller energy info supplied for " + receiver.getName());
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    @GuardedBy({"mWorkerLock"})
    private WifiActivityEnergyInfo extractDeltaLocked(WifiActivityEnergyInfo latest) {
        WifiActivityEnergyInfo delta;
        long scanTimeMs;
        long rxTimeMs;
        long maxExpectedIdleTimeMs;
        long timePeriodMs = latest.mTimestamp - this.mLastInfo.mTimestamp;
        long lastScanMs = this.mLastInfo.mControllerScanTimeMs;
        long lastIdleMs = this.mLastInfo.mControllerIdleTimeMs;
        long lastTxMs = this.mLastInfo.mControllerTxTimeMs;
        long lastRxMs = this.mLastInfo.mControllerRxTimeMs;
        long lastEnergy = this.mLastInfo.mControllerEnergyUsed;
        WifiActivityEnergyInfo delta2 = this.mLastInfo;
        delta2.mTimestamp = latest.getTimeStamp();
        delta2.mStackState = latest.getStackState();
        long txTimeMs = latest.mControllerTxTimeMs - lastTxMs;
        long rxTimeMs2 = latest.mControllerRxTimeMs - lastRxMs;
        long idleTimeMs = latest.mControllerIdleTimeMs - lastIdleMs;
        long scanTimeMs2 = latest.mControllerScanTimeMs - lastScanMs;
        if (txTimeMs < 0 || rxTimeMs2 < 0 || scanTimeMs2 < 0) {
            delta = delta2;
        } else if (idleTimeMs < 0) {
            delta = delta2;
        } else {
            long idleTimeMs2 = txTimeMs + rxTimeMs2;
            if (idleTimeMs2 > timePeriodMs) {
                if (idleTimeMs2 > timePeriodMs + MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Total Active time ");
                    TimeUtils.formatDuration(idleTimeMs2, sb);
                    sb.append(" is longer than sample period ");
                    TimeUtils.formatDuration(timePeriodMs, sb);
                    sb.append(".\n");
                    sb.append("Previous WiFi snapshot: ");
                    sb.append("idle=");
                    TimeUtils.formatDuration(lastIdleMs, sb);
                    sb.append(" rx=");
                    scanTimeMs = scanTimeMs2;
                    TimeUtils.formatDuration(lastRxMs, sb);
                    sb.append(" tx=");
                    TimeUtils.formatDuration(lastTxMs, sb);
                    sb.append(" e=");
                    rxTimeMs = rxTimeMs2;
                    sb.append(lastEnergy);
                    sb.append("\n");
                    sb.append("Current WiFi snapshot: ");
                    sb.append("idle=");
                    TimeUtils.formatDuration(latest.mControllerIdleTimeMs, sb);
                    sb.append(" rx=");
                    TimeUtils.formatDuration(latest.mControllerRxTimeMs, sb);
                    sb.append(" tx=");
                    TimeUtils.formatDuration(latest.mControllerTxTimeMs, sb);
                    sb.append(" e=");
                    sb.append(latest.mControllerEnergyUsed);
                    Slog.wtf(TAG, sb.toString());
                } else {
                    scanTimeMs = scanTimeMs2;
                    rxTimeMs = rxTimeMs2;
                }
                maxExpectedIdleTimeMs = 0;
            } else {
                scanTimeMs = scanTimeMs2;
                rxTimeMs = rxTimeMs2;
                maxExpectedIdleTimeMs = timePeriodMs - idleTimeMs2;
            }
            delta = delta2;
            delta.mControllerTxTimeMs = txTimeMs;
            delta.mControllerRxTimeMs = rxTimeMs;
            delta.mControllerScanTimeMs = scanTimeMs;
            delta.mControllerIdleTimeMs = Math.min(maxExpectedIdleTimeMs, Math.max(0L, idleTimeMs));
            delta.mControllerEnergyUsed = Math.max(0L, latest.mControllerEnergyUsed - lastEnergy);
            this.mLastInfo = latest;
            return delta;
        }
        long totalOnTimeMs = latest.mControllerTxTimeMs + latest.mControllerRxTimeMs + latest.mControllerIdleTimeMs;
        if (totalOnTimeMs <= timePeriodMs + MAX_WIFI_STATS_SAMPLE_ERROR_MILLIS) {
            delta.mControllerEnergyUsed = latest.mControllerEnergyUsed;
            delta.mControllerRxTimeMs = latest.mControllerRxTimeMs;
            delta.mControllerTxTimeMs = latest.mControllerTxTimeMs;
            delta.mControllerIdleTimeMs = latest.mControllerIdleTimeMs;
            delta.mControllerScanTimeMs = latest.mControllerScanTimeMs;
        } else {
            delta.mControllerEnergyUsed = 0L;
            delta.mControllerRxTimeMs = 0L;
            delta.mControllerTxTimeMs = 0L;
            delta.mControllerIdleTimeMs = 0L;
            delta.mControllerScanTimeMs = 0L;
        }
        Slog.v(TAG, "WiFi energy data was reset, new WiFi energy data is " + delta);
        this.mLastInfo = latest;
        return delta;
    }
}
