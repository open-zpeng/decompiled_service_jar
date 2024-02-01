package com.android.server.devicepolicy;

import android.app.AlarmManager;
import android.app.admin.NetworkEvent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.LongSparseArray;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.job.controllers.JobStatus;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class NetworkLoggingHandler extends Handler {
    private static final long BATCH_FINALIZATION_TIMEOUT_ALARM_INTERVAL_MS = 1800000;
    private static final long BATCH_FINALIZATION_TIMEOUT_MS = 5400000;
    @VisibleForTesting
    static final int LOG_NETWORK_EVENT_MSG = 1;
    private static final int MAX_BATCHES = 5;
    private static final int MAX_EVENTS_PER_BATCH = 1200;
    static final String NETWORK_EVENT_KEY = "network_event";
    private static final String NETWORK_LOGGING_TIMEOUT_ALARM_TAG = "NetworkLogging.batchTimeout";
    private static final long RETRIEVED_BATCH_DISCARD_DELAY_MS = 300000;
    private final AlarmManager mAlarmManager;
    private final AlarmManager.OnAlarmListener mBatchTimeoutAlarmListener;
    @GuardedBy({"this"})
    private final LongSparseArray<ArrayList<NetworkEvent>> mBatches;
    @GuardedBy({"this"})
    private long mCurrentBatchToken;
    private final DevicePolicyManagerService mDpm;
    private long mId;
    @GuardedBy({"this"})
    private long mLastFinalizationNanos;
    @GuardedBy({"this"})
    private long mLastRetrievedBatchToken;
    @GuardedBy({"this"})
    private ArrayList<NetworkEvent> mNetworkEvents;
    @GuardedBy({"this"})
    private boolean mPaused;
    private static final String TAG = NetworkLoggingHandler.class.getSimpleName();
    private static final long FORCE_FETCH_THROTTLE_NS = TimeUnit.SECONDS.toNanos(10);

    /* JADX INFO: Access modifiers changed from: package-private */
    public NetworkLoggingHandler(Looper looper, DevicePolicyManagerService dpm) {
        this(looper, dpm, 0L);
    }

    @VisibleForTesting
    NetworkLoggingHandler(Looper looper, DevicePolicyManagerService dpm, long id) {
        super(looper);
        this.mLastFinalizationNanos = -1L;
        this.mBatchTimeoutAlarmListener = new AlarmManager.OnAlarmListener() { // from class: com.android.server.devicepolicy.NetworkLoggingHandler.1
            @Override // android.app.AlarmManager.OnAlarmListener
            public void onAlarm() {
                Bundle notificationExtras;
                String str = NetworkLoggingHandler.TAG;
                Slog.d(str, "Received a batch finalization timeout alarm, finalizing " + NetworkLoggingHandler.this.mNetworkEvents.size() + " pending events.");
                synchronized (NetworkLoggingHandler.this) {
                    notificationExtras = NetworkLoggingHandler.this.finalizeBatchAndBuildDeviceOwnerMessageLocked();
                }
                if (notificationExtras != null) {
                    NetworkLoggingHandler.this.notifyDeviceOwner(notificationExtras);
                }
            }
        };
        this.mNetworkEvents = new ArrayList<>();
        this.mBatches = new LongSparseArray<>(5);
        this.mPaused = false;
        this.mDpm = dpm;
        this.mAlarmManager = this.mDpm.mInjector.getAlarmManager();
        this.mId = id;
    }

    @Override // android.os.Handler
    public void handleMessage(Message msg) {
        if (msg.what == 1) {
            NetworkEvent networkEvent = (NetworkEvent) msg.getData().getParcelable(NETWORK_EVENT_KEY);
            if (networkEvent != null) {
                Bundle notificationExtras = null;
                synchronized (this) {
                    this.mNetworkEvents.add(networkEvent);
                    if (this.mNetworkEvents.size() >= MAX_EVENTS_PER_BATCH) {
                        notificationExtras = finalizeBatchAndBuildDeviceOwnerMessageLocked();
                    }
                }
                if (notificationExtras != null) {
                    notifyDeviceOwner(notificationExtras);
                    return;
                }
                return;
            }
            return;
        }
        Slog.d(TAG, "NetworkLoggingHandler received an unknown of message.");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleBatchFinalization() {
        long when = SystemClock.elapsedRealtime() + BATCH_FINALIZATION_TIMEOUT_MS;
        this.mAlarmManager.setWindow(2, when, 1800000L, NETWORK_LOGGING_TIMEOUT_ALARM_TAG, this.mBatchTimeoutAlarmListener, this);
        Slog.d(TAG, "Scheduled a new batch finalization alarm 5400000ms from now.");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long forceBatchFinalization() {
        synchronized (this) {
            long toWaitNanos = (this.mLastFinalizationNanos + FORCE_FETCH_THROTTLE_NS) - System.nanoTime();
            if (toWaitNanos > 0) {
                return TimeUnit.NANOSECONDS.toMillis(toWaitNanos) + 1;
            }
            Bundle notificationExtras = finalizeBatchAndBuildDeviceOwnerMessageLocked();
            if (notificationExtras != null) {
                notifyDeviceOwner(notificationExtras);
            }
            return 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void pause() {
        Slog.d(TAG, "Paused network logging");
        this.mPaused = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resume() {
        Bundle notificationExtras = null;
        synchronized (this) {
            if (!this.mPaused) {
                Slog.d(TAG, "Attempted to resume network logging, but logging is not paused.");
                return;
            }
            String str = TAG;
            Slog.d(str, "Resumed network logging. Current batch=" + this.mCurrentBatchToken + ", LastRetrievedBatch=" + this.mLastRetrievedBatchToken);
            this.mPaused = false;
            if (this.mBatches.size() > 0 && this.mLastRetrievedBatchToken != this.mCurrentBatchToken) {
                scheduleBatchFinalization();
                notificationExtras = buildDeviceOwnerMessageLocked();
            }
            if (notificationExtras != null) {
                notifyDeviceOwner(notificationExtras);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void discardLogs() {
        this.mBatches.clear();
        this.mNetworkEvents = new ArrayList<>();
        Slog.d(TAG, "Discarded all network logs");
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"this"})
    public Bundle finalizeBatchAndBuildDeviceOwnerMessageLocked() {
        this.mLastFinalizationNanos = System.nanoTime();
        Bundle notificationExtras = null;
        if (this.mNetworkEvents.size() > 0) {
            Iterator<NetworkEvent> it = this.mNetworkEvents.iterator();
            while (it.hasNext()) {
                NetworkEvent event = it.next();
                event.setId(this.mId);
                long j = this.mId;
                if (j == JobStatus.NO_LATEST_RUNTIME) {
                    Slog.i(TAG, "Reached maximum id value; wrapping around ." + this.mCurrentBatchToken);
                    this.mId = 0L;
                } else {
                    this.mId = j + 1;
                }
            }
            if (this.mBatches.size() >= 5) {
                this.mBatches.removeAt(0);
            }
            this.mCurrentBatchToken++;
            this.mBatches.append(this.mCurrentBatchToken, this.mNetworkEvents);
            this.mNetworkEvents = new ArrayList<>();
            if (!this.mPaused) {
                notificationExtras = buildDeviceOwnerMessageLocked();
            }
        } else {
            Slog.d(TAG, "Was about to finalize the batch, but there were no events to send to the DPC, the batchToken of last available batch: " + this.mCurrentBatchToken);
        }
        scheduleBatchFinalization();
        return notificationExtras;
    }

    @GuardedBy({"this"})
    private Bundle buildDeviceOwnerMessageLocked() {
        Bundle extras = new Bundle();
        LongSparseArray<ArrayList<NetworkEvent>> longSparseArray = this.mBatches;
        int lastBatchSize = longSparseArray.valueAt(longSparseArray.size() - 1).size();
        extras.putLong("android.app.extra.EXTRA_NETWORK_LOGS_TOKEN", this.mCurrentBatchToken);
        extras.putInt("android.app.extra.EXTRA_NETWORK_LOGS_COUNT", lastBatchSize);
        return extras;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyDeviceOwner(Bundle extras) {
        String str = TAG;
        Slog.d(str, "Sending network logging batch broadcast to device owner, batchToken: " + extras.getLong("android.app.extra.EXTRA_NETWORK_LOGS_TOKEN", -1L));
        if (Thread.holdsLock(this)) {
            Slog.wtfStack(TAG, "Shouldn't be called with NetworkLoggingHandler lock held");
        } else {
            this.mDpm.sendDeviceOwnerCommand("android.app.action.NETWORK_LOGS_AVAILABLE", extras);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized List<NetworkEvent> retrieveFullLogBatch(final long batchToken) {
        int index = this.mBatches.indexOfKey(batchToken);
        if (index < 0) {
            return null;
        }
        postDelayed(new Runnable() { // from class: com.android.server.devicepolicy.-$$Lambda$NetworkLoggingHandler$VKC_fB9Ws13yQKJ8zNkiF3Wp0Jk
            @Override // java.lang.Runnable
            public final void run() {
                NetworkLoggingHandler.this.lambda$retrieveFullLogBatch$0$NetworkLoggingHandler(batchToken);
            }
        }, 300000L);
        this.mLastRetrievedBatchToken = batchToken;
        return this.mBatches.valueAt(index);
    }

    public /* synthetic */ void lambda$retrieveFullLogBatch$0$NetworkLoggingHandler(long batchToken) {
        synchronized (this) {
            while (this.mBatches.size() > 0 && this.mBatches.keyAt(0) <= batchToken) {
                this.mBatches.removeAt(0);
            }
        }
    }
}
