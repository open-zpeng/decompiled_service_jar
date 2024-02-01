package com.android.server.location;

import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;

/* loaded from: classes.dex */
public class GnssBatchingProvider {
    private boolean mEnabled;
    private final GnssBatchingProviderNative mNative;
    private long mPeriodNanos;
    private boolean mStarted;
    private boolean mWakeOnFifoFull;
    private static final String TAG = "GnssBatchingProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void native_cleanup_batching();

    /* JADX INFO: Access modifiers changed from: private */
    public static native void native_flush_batch();

    private static native int native_get_batch_size();

    private static native boolean native_init_batching();

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_start_batch(long j, boolean z);

    private static native boolean native_stop_batch();

    static /* synthetic */ int access$000() {
        return native_get_batch_size();
    }

    static /* synthetic */ boolean access$300() {
        return native_stop_batch();
    }

    static /* synthetic */ boolean access$400() {
        return native_init_batching();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public GnssBatchingProvider() {
        this(new GnssBatchingProviderNative());
    }

    @VisibleForTesting
    GnssBatchingProvider(GnssBatchingProviderNative gnssBatchingProviderNative) {
        this.mNative = gnssBatchingProviderNative;
    }

    public int getBatchSize() {
        return this.mNative.getBatchSize();
    }

    public void enable() {
        this.mEnabled = this.mNative.initBatching();
        if (!this.mEnabled) {
            Log.e(TAG, "Failed to initialize GNSS batching");
        }
    }

    public boolean start(long periodNanos, boolean wakeOnFifoFull) {
        if (!this.mEnabled) {
            throw new IllegalStateException();
        }
        if (periodNanos <= 0) {
            Log.e(TAG, "Invalid periodNanos " + periodNanos + " in batching request, not started");
            return false;
        }
        this.mStarted = this.mNative.startBatch(periodNanos, wakeOnFifoFull);
        if (this.mStarted) {
            this.mPeriodNanos = periodNanos;
            this.mWakeOnFifoFull = wakeOnFifoFull;
        }
        return this.mStarted;
    }

    public void flush() {
        if (!this.mStarted) {
            Log.w(TAG, "Cannot flush since GNSS batching has not started.");
        } else {
            this.mNative.flushBatch();
        }
    }

    public boolean stop() {
        boolean stopped = this.mNative.stopBatch();
        if (stopped) {
            this.mStarted = false;
        }
        return stopped;
    }

    public void disable() {
        stop();
        this.mNative.cleanupBatching();
        this.mEnabled = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        if (this.mStarted) {
            this.mNative.startBatch(this.mPeriodNanos, this.mWakeOnFifoFull);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class GnssBatchingProviderNative {
        GnssBatchingProviderNative() {
        }

        public int getBatchSize() {
            return GnssBatchingProvider.access$000();
        }

        public boolean startBatch(long periodNanos, boolean wakeOnFifoFull) {
            return GnssBatchingProvider.native_start_batch(periodNanos, wakeOnFifoFull);
        }

        public void flushBatch() {
            GnssBatchingProvider.native_flush_batch();
        }

        public boolean stopBatch() {
            return GnssBatchingProvider.access$300();
        }

        public boolean initBatching() {
            return GnssBatchingProvider.access$400();
        }

        public void cleanupBatching() {
            GnssBatchingProvider.native_cleanup_batching();
        }
    }
}
