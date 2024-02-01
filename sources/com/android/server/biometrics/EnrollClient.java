package com.android.server.biometrics;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import com.android.server.biometrics.BiometricServiceBase;
import java.util.ArrayList;
import java.util.Arrays;

/* loaded from: classes.dex */
public abstract class EnrollClient extends ClientMonitor {
    private final BiometricUtils mBiometricUtils;
    private final byte[] mCryptoToken;
    private final int[] mDisabledFeatures;
    private long mEnrollmentStartTimeMs;
    private final int mTimeoutSec;

    public abstract boolean shouldVibrate();

    public EnrollClient(Context context, Constants constants, BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token, BiometricServiceBase.ServiceListener listener, int userId, int groupId, byte[] cryptoToken, boolean restricted, String owner, BiometricUtils utils, int[] disabledFeatures, int timeoutSec) {
        super(context, constants, daemon, halDeviceId, token, listener, userId, groupId, restricted, owner, 0);
        this.mBiometricUtils = utils;
        this.mCryptoToken = Arrays.copyOf(cryptoToken, cryptoToken.length);
        this.mDisabledFeatures = Arrays.copyOf(disabledFeatures, disabledFeatures.length);
        this.mTimeoutSec = timeoutSec;
    }

    @Override // com.android.server.biometrics.LoggableMonitor
    protected int statsAction() {
        return 1;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (remaining == 0) {
            this.mBiometricUtils.addBiometricForUser(getContext(), getTargetUserId(), identifier);
            logOnEnrolled(getTargetUserId(), System.currentTimeMillis() - this.mEnrollmentStartTimeMs, true);
        }
        notifyUserActivity();
        return sendEnrollResult(identifier, remaining);
    }

    private boolean sendEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (shouldVibrate()) {
            vibrateSuccess();
        }
        this.mMetricsLogger.action(this.mConstants.actionBiometricEnroll());
        try {
            BiometricServiceBase.ServiceListener listener = getListener();
            if (listener != null) {
                listener.onEnrollResult(identifier, remaining);
            }
            return remaining == 0;
        } catch (RemoteException e) {
            Slog.w(getLogTag(), "Failed to notify EnrollResult:", e);
            return true;
        }
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public int start() {
        this.mEnrollmentStartTimeMs = System.currentTimeMillis();
        try {
            ArrayList<Integer> disabledFeatures = new ArrayList<>();
            for (int i = 0; i < this.mDisabledFeatures.length; i++) {
                disabledFeatures.add(Integer.valueOf(this.mDisabledFeatures[i]));
            }
            int result = getDaemonWrapper().enroll(this.mCryptoToken, getGroupId(), this.mTimeoutSec, disabledFeatures);
            if (result != 0) {
                Slog.w(getLogTag(), "startEnroll failed, result=" + result);
                this.mMetricsLogger.histogram(this.mConstants.tagEnrollStartError(), result);
                onError(getHalDeviceId(), 1, 0);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "startEnroll failed", e);
        }
        return 0;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public int stop(boolean initiatedByClient) {
        if (this.mAlreadyCancelled) {
            Slog.w(getLogTag(), "stopEnroll: already cancelled!");
            return 0;
        }
        try {
            int result = getDaemonWrapper().cancel();
            if (result != 0) {
                String logTag = getLogTag();
                Slog.w(logTag, "startEnrollCancel failed, result = " + result);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "stopEnrollment failed", e);
        }
        this.mAlreadyCancelled = true;
        return 0;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        Slog.w(getLogTag(), "onRemoved() called for enroll!");
        return true;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        Slog.w(getLogTag(), "onEnumerationResult() called for enroll!");
        return true;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier, boolean authenticated, ArrayList<Byte> token) {
        Slog.w(getLogTag(), "onAuthenticated() called for enroll!");
        return true;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onError(long deviceId, int error, int vendorCode) {
        logOnEnrolled(getTargetUserId(), System.currentTimeMillis() - this.mEnrollmentStartTimeMs, false);
        return super.onError(deviceId, error, vendorCode);
    }
}
