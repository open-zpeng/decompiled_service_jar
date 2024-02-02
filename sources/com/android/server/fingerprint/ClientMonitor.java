package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.media.AudioAttributes;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Slog;
import java.util.NoSuchElementException;
/* loaded from: classes.dex */
public abstract class ClientMonitor implements IBinder.DeathRecipient {
    protected static final boolean DEBUG = true;
    protected static final int ERROR_ESRCH = 3;
    protected static final String TAG = "FingerprintService";
    protected boolean mAlreadyCancelled;
    private final Context mContext;
    private final int mGroupId;
    private final long mHalDeviceId;
    private final boolean mIsRestricted;
    private final String mOwner;
    private IFingerprintServiceReceiver mReceiver;
    private final int mTargetUserId;
    private IBinder mToken;
    private static final long[] DEFAULT_SUCCESS_VIBRATION_PATTERN = {0, 30};
    private static final AudioAttributes FINGERPRINT_SONFICATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).setUsage(13).build();
    private final VibrationEffect mSuccessVibrationEffect = VibrationEffect.get(0);
    private final VibrationEffect mErrorVibrationEffect = VibrationEffect.get(1);

    public abstract IBiometricsFingerprint getFingerprintDaemon();

    public abstract void notifyUserActivity();

    public abstract boolean onAuthenticated(int i, int i2);

    public abstract boolean onEnrollResult(int i, int i2, int i3);

    public abstract boolean onEnumerationResult(int i, int i2, int i3);

    public abstract boolean onRemoved(int i, int i2, int i3);

    public abstract int start();

    public abstract int stop(boolean z);

    public ClientMonitor(Context context, long halDeviceId, IBinder token, IFingerprintServiceReceiver receiver, int userId, int groupId, boolean restricted, String owner) {
        this.mContext = context;
        this.mHalDeviceId = halDeviceId;
        this.mToken = token;
        this.mReceiver = receiver;
        this.mTargetUserId = userId;
        this.mGroupId = groupId;
        this.mIsRestricted = restricted;
        this.mOwner = owner;
        if (token != null) {
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
            }
        }
    }

    public boolean onAcquired(int acquiredInfo, int vendorCode) {
        try {
            if (this.mReceiver == null) {
                return true;
            }
            try {
                this.mReceiver.onAcquired(getHalDeviceId(), acquiredInfo, vendorCode);
                if (acquiredInfo == 0) {
                    notifyUserActivity();
                }
                return false;
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke sendAcquired:", e);
                if (acquiredInfo == 0) {
                    notifyUserActivity();
                }
                return true;
            }
        } catch (Throwable th) {
            if (acquiredInfo == 0) {
                notifyUserActivity();
            }
            throw th;
        }
    }

    public boolean onError(int error, int vendorCode) {
        if (this.mReceiver != null) {
            try {
                this.mReceiver.onError(getHalDeviceId(), error, vendorCode);
                return true;
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke sendError:", e);
                return true;
            }
        }
        return true;
    }

    public void destroy() {
        if (this.mToken != null) {
            try {
                this.mToken.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Slog.e(TAG, "destroy(): " + this + ":", new Exception("here"));
            }
            this.mToken = null;
        }
        this.mReceiver = null;
    }

    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        this.mToken = null;
        this.mReceiver = null;
        onError(1, 0);
    }

    protected void finalize() throws Throwable {
        try {
            if (this.mToken != null) {
                Slog.w(TAG, "removing leaked reference: " + this.mToken);
                onError(1, 0);
            }
        } finally {
            super.finalize();
        }
    }

    public final Context getContext() {
        return this.mContext;
    }

    public final long getHalDeviceId() {
        return this.mHalDeviceId;
    }

    public final String getOwnerString() {
        return this.mOwner;
    }

    public final IFingerprintServiceReceiver getReceiver() {
        return this.mReceiver;
    }

    public final boolean getIsRestricted() {
        return this.mIsRestricted;
    }

    public final int getTargetUserId() {
        return this.mTargetUserId;
    }

    public final int getGroupId() {
        return this.mGroupId;
    }

    public final IBinder getToken() {
        return this.mToken;
    }

    public final void vibrateSuccess() {
        Vibrator vibrator = (Vibrator) this.mContext.getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(this.mSuccessVibrationEffect, FINGERPRINT_SONFICATION_ATTRIBUTES);
        }
    }

    public final void vibrateError() {
        Vibrator vibrator = (Vibrator) this.mContext.getSystemService(Vibrator.class);
        if (vibrator != null) {
            vibrator.vibrate(this.mErrorVibrationEffect, FINGERPRINT_SONFICATION_ATTRIBUTES);
        }
    }
}
