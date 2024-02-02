package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.logging.MetricsLogger;
/* loaded from: classes.dex */
public abstract class EnumerateClient extends ClientMonitor {
    public EnumerateClient(Context context, long halDeviceId, IBinder token, IFingerprintServiceReceiver receiver, int groupId, int userId, boolean restricted, String owner) {
        super(context, halDeviceId, token, receiver, userId, groupId, restricted, owner);
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public int start() {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        try {
            int result = daemon.enumerate();
            if (result != 0) {
                Slog.w("FingerprintService", "start enumerate for user " + getTargetUserId() + " failed, result=" + result);
                MetricsLogger.histogram(getContext(), "fingerprintd_enum_start_error", result);
                onError(1, 0);
                return result;
            }
        } catch (RemoteException e) {
            Slog.e("FingerprintService", "startEnumeration failed", e);
        }
        return 0;
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public int stop(boolean initiatedByClient) {
        if (this.mAlreadyCancelled) {
            Slog.w("FingerprintService", "stopEnumerate: already cancelled!");
            return 0;
        }
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w("FingerprintService", "stopEnumeration: no fingerprint HAL!");
            return 3;
        }
        try {
            int result = daemon.cancel();
            if (result != 0) {
                Slog.w("FingerprintService", "stop enumeration failed, result=" + result);
                return result;
            }
            if (initiatedByClient) {
                onError(5, 0);
            }
            this.mAlreadyCancelled = true;
            return 0;
        } catch (RemoteException e) {
            Slog.e("FingerprintService", "stopEnumeration failed", e);
            return 3;
        }
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        IFingerprintServiceReceiver receiver = getReceiver();
        if (receiver == null) {
            return true;
        }
        try {
            receiver.onEnumerated(getHalDeviceId(), fingerId, groupId, remaining);
        } catch (RemoteException e) {
            Slog.w("FingerprintService", "Failed to notify enumerated:", e);
        }
        return remaining == 0;
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onAuthenticated(int fingerId, int groupId) {
        Slog.w("FingerprintService", "onAuthenticated() called for enumerate!");
        return true;
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onEnrollResult(int fingerId, int groupId, int rem) {
        Slog.w("FingerprintService", "onEnrollResult() called for enumerate!");
        return true;
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        Slog.w("FingerprintService", "onRemoved() called for enumerate!");
        return true;
    }
}
