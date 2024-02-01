package com.android.server.biometrics;

import android.content.Context;
import android.util.Slog;
import android.util.StatsLog;

/* loaded from: classes.dex */
public abstract class LoggableMonitor {
    public static final boolean DEBUG = false;
    public static final String TAG = "BiometricStats";
    private long mFirstAcquireTimeMs;

    protected abstract int statsAction();

    protected abstract int statsModality();

    protected long getFirstAcquireTimeMs() {
        return this.mFirstAcquireTimeMs;
    }

    protected boolean isCryptoOperation() {
        return false;
    }

    protected int statsClient() {
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public final void logOnAcquired(Context context, int acquiredInfo, int vendorCode, int targetUserId) {
        if (statsModality() == 4) {
            if (acquiredInfo == 20) {
                this.mFirstAcquireTimeMs = System.currentTimeMillis();
            }
        } else if (acquiredInfo == 0 && this.mFirstAcquireTimeMs == 0) {
            this.mFirstAcquireTimeMs = System.currentTimeMillis();
        }
        StatsLog.write(87, statsModality(), targetUserId, isCryptoOperation(), statsAction(), statsClient(), acquiredInfo, vendorCode, Utils.isDebugEnabled(context, targetUserId));
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public final void logOnError(Context context, int error, int vendorCode, int targetUserId) {
        long latency = this.mFirstAcquireTimeMs != 0 ? System.currentTimeMillis() - this.mFirstAcquireTimeMs : -1L;
        Slog.v(TAG, "Error latency: " + latency);
        StatsLog.write(89, statsModality(), targetUserId, isCryptoOperation(), statsAction(), statsClient(), error, vendorCode, Utils.isDebugEnabled(context, targetUserId), latency);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public final void logOnAuthenticated(Context context, boolean authenticated, boolean requireConfirmation, int targetUserId, boolean isBiometricPrompt) {
        int authState;
        long j;
        if (!authenticated) {
            authState = 1;
        } else if (isBiometricPrompt && requireConfirmation) {
            authState = 2;
        } else {
            authState = 3;
        }
        if (this.mFirstAcquireTimeMs != 0) {
            j = System.currentTimeMillis() - this.mFirstAcquireTimeMs;
        } else {
            j = -1;
        }
        long latency = j;
        Slog.v(TAG, "Authentication latency: " + latency);
        StatsLog.write(88, statsModality(), targetUserId, isCryptoOperation(), statsClient(), requireConfirmation, authState, latency, Utils.isDebugEnabled(context, targetUserId));
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public final void logOnEnrolled(int targetUserId, long latency, boolean enrollSuccessful) {
        Slog.v(TAG, "Enroll latency: " + latency);
        StatsLog.write(184, statsModality(), targetUserId, latency, enrollSuccessful);
    }
}
