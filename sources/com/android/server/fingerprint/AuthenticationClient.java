package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.server.backup.BackupManagerConstants;
/* loaded from: classes.dex */
public abstract class AuthenticationClient extends ClientMonitor {
    public static final int LOCKOUT_NONE = 0;
    public static final int LOCKOUT_PERMANENT = 2;
    public static final int LOCKOUT_TIMED = 1;
    private Bundle mBundle;
    protected boolean mDialogDismissed;
    protected IBiometricPromptReceiver mDialogReceiver;
    private IBiometricPromptReceiver mDialogReceiverFromClient;
    private final FingerprintManager mFingerprintManager;
    private boolean mInLockout;
    private long mOpId;
    private IStatusBarService mStatusBarService;

    public abstract int handleFailedAttempt();

    public abstract void onStart();

    public abstract void onStop();

    public abstract void resetFailedAttempts();

    public AuthenticationClient(Context context, long halDeviceId, IBinder token, IFingerprintServiceReceiver receiver, int targetUserId, int groupId, long opId, boolean restricted, String owner, Bundle bundle, IBiometricPromptReceiver dialogReceiver, IStatusBarService statusBarService) {
        super(context, halDeviceId, token, receiver, targetUserId, groupId, restricted, owner);
        this.mDialogReceiver = new IBiometricPromptReceiver.Stub() { // from class: com.android.server.fingerprint.AuthenticationClient.1
            public void onDialogDismissed(int reason) {
                if (AuthenticationClient.this.mBundle != null && AuthenticationClient.this.mDialogReceiverFromClient != null) {
                    try {
                        AuthenticationClient.this.mDialogReceiverFromClient.onDialogDismissed(reason);
                        if (reason == 3) {
                            AuthenticationClient.this.onError(10, 0);
                        }
                        AuthenticationClient.this.mDialogDismissed = true;
                    } catch (RemoteException e) {
                        Slog.e("FingerprintService", "Unable to notify dialog dismissed", e);
                    }
                    AuthenticationClient.this.stop(true);
                }
            }
        };
        this.mOpId = opId;
        this.mBundle = bundle;
        this.mDialogReceiverFromClient = dialogReceiver;
        this.mStatusBarService = statusBarService;
        this.mFingerprintManager = (FingerprintManager) getContext().getSystemService("fingerprint");
    }

    @Override // com.android.server.fingerprint.ClientMonitor, android.os.IBinder.DeathRecipient
    public void binderDied() {
        super.binderDied();
        stop(false);
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onAcquired(int acquiredInfo, int vendorCode) {
        if (this.mBundle != null) {
            try {
                if (acquiredInfo != 0) {
                    try {
                        this.mStatusBarService.onFingerprintHelp(this.mFingerprintManager.getAcquiredString(acquiredInfo, vendorCode));
                    } catch (RemoteException e) {
                        Slog.e("FingerprintService", "Remote exception when sending acquired message", e);
                        if (acquiredInfo == 0) {
                            notifyUserActivity();
                        }
                        return true;
                    }
                }
                if (acquiredInfo == 0) {
                    notifyUserActivity();
                }
                return false;
            } catch (Throwable th) {
                if (acquiredInfo == 0) {
                    notifyUserActivity();
                }
                throw th;
            }
        }
        return super.onAcquired(acquiredInfo, vendorCode);
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onError(int error, int vendorCode) {
        if (this.mDialogDismissed) {
            return true;
        }
        if (this.mBundle != null) {
            try {
                this.mStatusBarService.onFingerprintError(this.mFingerprintManager.getErrorString(error, vendorCode));
            } catch (RemoteException e) {
                Slog.e("FingerprintService", "Remote exception when sending error", e);
            }
        }
        return super.onError(error, vendorCode);
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onAuthenticated(int fingerId, int groupId) {
        Fingerprint fp;
        int errorCode;
        boolean result = false;
        boolean authenticated = fingerId != 0;
        if (this.mBundle != null) {
            try {
                if (authenticated) {
                    this.mStatusBarService.onFingerprintAuthenticated();
                } else {
                    this.mStatusBarService.onFingerprintHelp(getContext().getResources().getString(17039929));
                }
            } catch (RemoteException e) {
                Slog.e("FingerprintService", "Failed to notify Authenticated:", e);
            }
        }
        IFingerprintServiceReceiver receiver = getReceiver();
        if (receiver != null) {
            try {
                MetricsLogger.action(getContext(), 252, authenticated);
                if (!authenticated) {
                    receiver.onAuthenticationFailed(getHalDeviceId());
                } else {
                    Slog.v("FingerprintService", "onAuthenticated(owner=" + getOwnerString() + ", id=" + fingerId + ", gp=" + groupId + ")");
                    if (!getIsRestricted()) {
                        fp = new Fingerprint(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, groupId, fingerId, getHalDeviceId());
                    } else {
                        fp = null;
                    }
                    receiver.onAuthenticationSucceeded(getHalDeviceId(), fp, getTargetUserId());
                }
            } catch (RemoteException e2) {
                Slog.w("FingerprintService", "Failed to notify Authenticated:", e2);
                result = true;
            }
        } else {
            result = true;
        }
        if (!authenticated) {
            if (receiver != null) {
                vibrateError();
            }
            int lockoutMode = handleFailedAttempt();
            if (lockoutMode != 0) {
                try {
                    this.mInLockout = true;
                    Slog.w("FingerprintService", "Forcing lockout (fp driver code should do this!), mode(" + lockoutMode + ")");
                    stop(false);
                    if (lockoutMode == 1) {
                        errorCode = 7;
                    } else {
                        errorCode = 9;
                    }
                    receiver.onError(getHalDeviceId(), errorCode, 0);
                    if (this.mBundle != null) {
                        this.mStatusBarService.onFingerprintError(this.mFingerprintManager.getErrorString(errorCode, 0));
                    }
                } catch (RemoteException e3) {
                    Slog.w("FingerprintService", "Failed to notify lockout:", e3);
                }
            }
            return result | (lockoutMode != 0);
        }
        if (receiver != null) {
            vibrateSuccess();
        }
        boolean result2 = result | true;
        resetFailedAttempts();
        onStop();
        return result2;
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public int start() {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w("FingerprintService", "start authentication: no fingerprint HAL!");
            return 3;
        }
        onStart();
        try {
            int result = daemon.authenticate(this.mOpId, getGroupId());
            if (result != 0) {
                Slog.w("FingerprintService", "startAuthentication failed, result=" + result);
                MetricsLogger.histogram(getContext(), "fingeprintd_auth_start_error", result);
                onError(1, 0);
                return result;
            }
            Slog.w("FingerprintService", "client " + getOwnerString() + " is authenticating...");
            if (this.mBundle != null) {
                try {
                    this.mStatusBarService.showFingerprintDialog(this.mBundle, this.mDialogReceiver);
                } catch (RemoteException e) {
                    Slog.e("FingerprintService", "Unable to show fingerprint dialog", e);
                }
            }
            return 0;
        } catch (RemoteException e2) {
            Slog.e("FingerprintService", "startAuthentication failed", e2);
            return 3;
        }
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public int stop(boolean initiatedByClient) {
        if (this.mAlreadyCancelled) {
            Slog.w("FingerprintService", "stopAuthentication: already cancelled!");
            return 0;
        }
        onStop();
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        try {
            if (daemon == null) {
                Slog.w("FingerprintService", "stopAuthentication: no fingerprint HAL!");
                return 3;
            }
            try {
                int result = daemon.cancel();
                if (result != 0) {
                    Slog.w("FingerprintService", "stopAuthentication failed, result=" + result);
                    if (this.mBundle != null && !this.mDialogDismissed && !this.mInLockout) {
                        try {
                            this.mStatusBarService.hideFingerprintDialog();
                        } catch (RemoteException e) {
                            Slog.e("FingerprintService", "Unable to hide fingerprint dialog", e);
                        }
                    }
                    return result;
                }
                Slog.w("FingerprintService", "client " + getOwnerString() + " is no longer authenticating");
                if (this.mBundle != null && !this.mDialogDismissed && !this.mInLockout) {
                    try {
                        this.mStatusBarService.hideFingerprintDialog();
                    } catch (RemoteException e2) {
                        Slog.e("FingerprintService", "Unable to hide fingerprint dialog", e2);
                    }
                }
                this.mAlreadyCancelled = true;
                return 0;
            } catch (RemoteException e3) {
                Slog.e("FingerprintService", "stopAuthentication failed", e3);
                if (this.mBundle != null && !this.mDialogDismissed && !this.mInLockout) {
                    try {
                        this.mStatusBarService.hideFingerprintDialog();
                    } catch (RemoteException e4) {
                        Slog.e("FingerprintService", "Unable to hide fingerprint dialog", e4);
                    }
                }
                return 3;
            }
        } catch (Throwable th) {
            if (this.mBundle != null && !this.mDialogDismissed && !this.mInLockout) {
                try {
                    this.mStatusBarService.hideFingerprintDialog();
                } catch (RemoteException e5) {
                    Slog.e("FingerprintService", "Unable to hide fingerprint dialog", e5);
                }
            }
            throw th;
        }
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onEnrollResult(int fingerId, int groupId, int remaining) {
        Slog.w("FingerprintService", "onEnrollResult() called for authenticate!");
        return true;
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onRemoved(int fingerId, int groupId, int remaining) {
        Slog.w("FingerprintService", "onRemoved() called for authenticate!");
        return true;
    }

    @Override // com.android.server.fingerprint.ClientMonitor
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        Slog.w("FingerprintService", "onEnumerationResult() called for authenticate!");
        return true;
    }
}
