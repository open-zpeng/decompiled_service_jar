package com.android.server.location;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ExponentialBackOff {
    private static final int MULTIPLIER = 2;
    private long mCurrentIntervalMillis;
    private final long mInitIntervalMillis;
    private final long mMaxIntervalMillis;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ExponentialBackOff(long initIntervalMillis, long maxIntervalMillis) {
        this.mInitIntervalMillis = initIntervalMillis;
        this.mMaxIntervalMillis = maxIntervalMillis;
        this.mCurrentIntervalMillis = this.mInitIntervalMillis / 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long nextBackoffMillis() {
        if (this.mCurrentIntervalMillis > this.mMaxIntervalMillis) {
            return this.mMaxIntervalMillis;
        }
        this.mCurrentIntervalMillis *= 2;
        return this.mCurrentIntervalMillis;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reset() {
        this.mCurrentIntervalMillis = this.mInitIntervalMillis / 2;
    }
}
