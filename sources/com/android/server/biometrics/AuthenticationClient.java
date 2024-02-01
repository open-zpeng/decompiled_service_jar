package com.android.server.biometrics;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.IBinder;
import android.os.RemoteException;
import android.security.KeyStore;
import android.util.EventLog;
import android.util.Slog;
import com.android.server.biometrics.BiometricServiceBase;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
public abstract class AuthenticationClient extends ClientMonitor {
    public static final int LOCKOUT_NONE = 0;
    public static final int LOCKOUT_PERMANENT = 2;
    public static final int LOCKOUT_TIMED = 1;
    private long mOpId;
    private final boolean mRequireConfirmation;
    private long mStartTimeMs;
    private boolean mStarted;

    public abstract int handleFailedAttempt();

    public abstract void onStart();

    public abstract void onStop();

    public abstract boolean shouldFrameworkHandleLockout();

    public abstract boolean wasUserDetected();

    public void resetFailedAttempts() {
    }

    public AuthenticationClient(Context context, Constants constants, BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token, BiometricServiceBase.ServiceListener listener, int targetUserId, int groupId, long opId, boolean restricted, String owner, int cookie, boolean requireConfirmation) {
        super(context, constants, daemon, halDeviceId, token, listener, targetUserId, groupId, restricted, owner, cookie);
        this.mOpId = opId;
        this.mRequireConfirmation = requireConfirmation;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public long getStartTimeMs() {
        return this.mStartTimeMs;
    }

    @Override // com.android.server.biometrics.ClientMonitor, android.os.IBinder.DeathRecipient
    public void binderDied() {
        super.binderDied();
        stop(false);
    }

    @Override // com.android.server.biometrics.LoggableMonitor
    protected int statsAction() {
        return 2;
    }

    public boolean isBiometricPrompt() {
        return getCookie() != 0;
    }

    public boolean getRequireConfirmation() {
        return this.mRequireConfirmation;
    }

    @Override // com.android.server.biometrics.LoggableMonitor
    protected boolean isCryptoOperation() {
        return this.mOpId != 0;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onError(long deviceId, int error, int vendorCode) {
        if (!shouldFrameworkHandleLockout() && (error == 3 ? wasUserDetected() || isBiometricPrompt() : error == 7 || error == 9) && this.mStarted) {
            vibrateError();
        }
        return super.onError(deviceId, error, vendorCode);
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier, boolean authenticated, ArrayList<Byte> token) {
        boolean authenticated2;
        int errorCode;
        super.logOnAuthenticated(getContext(), authenticated, this.mRequireConfirmation, getTargetUserId(), isBiometricPrompt());
        BiometricServiceBase.ServiceListener listener = getListener();
        this.mMetricsLogger.action(this.mConstants.actionBiometricAuth(), authenticated);
        try {
            Slog.v(getLogTag(), "onAuthenticated(" + authenticated + "), ID:" + identifier.getBiometricId() + ", Owner: " + getOwnerString() + ", isBP: " + isBiometricPrompt() + ", listener: " + listener + ", requireConfirmation: " + this.mRequireConfirmation + ", user: " + getTargetUserId());
            boolean isBackgroundAuth = false;
            if (authenticated && !Utils.isKeyguard(getContext(), getOwnerString())) {
                try {
                    List<ActivityManager.RunningTaskInfo> tasks = ActivityTaskManager.getService().getTasks(1);
                    if (tasks != null && !tasks.isEmpty()) {
                        ComponentName topActivity = tasks.get(0).topActivity;
                        if (topActivity == null) {
                            Slog.e(LoggableMonitor.TAG, "Unable to get top activity");
                            isBackgroundAuth = true;
                        } else {
                            String topPackage = topActivity.getPackageName();
                            if (!topPackage.contentEquals(getOwnerString())) {
                                Slog.e(LoggableMonitor.TAG, "Background authentication detected, top: " + topPackage + ", client: " + this);
                                isBackgroundAuth = true;
                            }
                        }
                    }
                    Slog.e(LoggableMonitor.TAG, "No running tasks reported");
                    isBackgroundAuth = true;
                } catch (RemoteException e) {
                    Slog.e(LoggableMonitor.TAG, "Unable to get running tasks", e);
                    isBackgroundAuth = true;
                }
            }
            if (isBackgroundAuth) {
                Slog.e(LoggableMonitor.TAG, "Failing possible background authentication");
                authenticated2 = false;
                try {
                    ApplicationInfo appInfo = getContext().getApplicationInfo();
                    Object[] objArr = new Object[3];
                    objArr[0] = "159249069";
                    objArr[1] = Integer.valueOf(appInfo != null ? appInfo.uid : -1);
                    objArr[2] = "Attempted background authentication";
                    EventLog.writeEvent(1397638484, objArr);
                } catch (RemoteException e2) {
                    e = e2;
                    Slog.e(getLogTag(), "Remote exception", e);
                    return true;
                }
            } else {
                authenticated2 = authenticated;
            }
            try {
                if (authenticated2) {
                    if (isBackgroundAuth) {
                        ApplicationInfo appInfo2 = getContext().getApplicationInfo();
                        Object[] objArr2 = new Object[3];
                        objArr2[0] = "159249069";
                        objArr2[1] = Integer.valueOf(appInfo2 != null ? appInfo2.uid : -1);
                        objArr2[2] = "Successful background authentication!";
                        EventLog.writeEvent(1397638484, objArr2);
                    }
                    this.mAlreadyDone = true;
                    if (listener != null) {
                        vibrateSuccess();
                    }
                    boolean result = true;
                    if (shouldFrameworkHandleLockout()) {
                        resetFailedAttempts();
                    }
                    onStop();
                    byte[] byteToken = new byte[token.size()];
                    for (int i = 0; i < token.size(); i++) {
                        try {
                            byteToken[i] = token.get(i).byteValue();
                        } catch (RemoteException e3) {
                            e = e3;
                            Slog.e(getLogTag(), "Remote exception", e);
                            return true;
                        }
                    }
                    if (isBiometricPrompt() && listener != null) {
                        listener.onAuthenticationSucceededInternal(this.mRequireConfirmation, byteToken);
                    } else if (!isBiometricPrompt() && listener != null) {
                        KeyStore.getInstance().addAuthToken(byteToken);
                        try {
                        } catch (RemoteException e4) {
                            e = e4;
                        }
                        try {
                            if (!getIsRestricted()) {
                                listener.onAuthenticationSucceeded(getHalDeviceId(), identifier, getTargetUserId());
                            } else {
                                listener.onAuthenticationSucceeded(getHalDeviceId(), null, getTargetUserId());
                            }
                        } catch (RemoteException e5) {
                            e = e5;
                            Slog.e(getLogTag(), "Remote exception", e);
                            return result;
                        }
                    } else {
                        Slog.w(getLogTag(), "Client not listening");
                        result = true;
                    }
                    return result;
                }
                if (listener != null) {
                    vibrateError();
                }
                int lockoutMode = handleFailedAttempt();
                if (lockoutMode != 0 && shouldFrameworkHandleLockout()) {
                    Slog.w(getLogTag(), "Forcing lockout (driver code should do this!), mode(" + lockoutMode + ")");
                    stop(false);
                    if (lockoutMode == 1) {
                        errorCode = 7;
                    } else {
                        errorCode = 9;
                    }
                    onError(getHalDeviceId(), errorCode, 0);
                } else if (listener != null) {
                    if (isBiometricPrompt()) {
                        listener.onAuthenticationFailedInternal(getCookie(), getRequireConfirmation());
                    } else {
                        listener.onAuthenticationFailed(getHalDeviceId());
                    }
                }
                return lockoutMode != 0;
            } catch (RemoteException e6) {
                e = e6;
            }
        } catch (RemoteException e7) {
            e = e7;
            Slog.e(getLogTag(), "Remote exception", e);
            return true;
        }
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public int start() {
        this.mStarted = true;
        onStart();
        try {
            this.mStartTimeMs = System.currentTimeMillis();
            int result = getDaemonWrapper().authenticate(this.mOpId, getGroupId());
            if (result != 0) {
                String logTag = getLogTag();
                Slog.w(logTag, "startAuthentication failed, result=" + result);
                this.mMetricsLogger.histogram(this.mConstants.tagAuthStartError(), result);
                onError(getHalDeviceId(), 1, 0);
                return result;
            }
            String logTag2 = getLogTag();
            Slog.w(logTag2, "client " + getOwnerString() + " is authenticating...");
            return 0;
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "startAuthentication failed", e);
            return 3;
        }
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public int stop(boolean initiatedByClient) {
        if (this.mAlreadyCancelled) {
            Slog.w(getLogTag(), "stopAuthentication: already cancelled!");
            return 0;
        }
        this.mStarted = false;
        onStop();
        try {
            int result = getDaemonWrapper().cancel();
            if (result != 0) {
                String logTag = getLogTag();
                Slog.w(logTag, "stopAuthentication failed, result=" + result);
                return result;
            }
            String logTag2 = getLogTag();
            Slog.w(logTag2, "client " + getOwnerString() + " is no longer authenticating");
            this.mAlreadyCancelled = true;
            return 0;
        } catch (RemoteException e) {
            Slog.e(getLogTag(), "stopAuthentication failed", e);
            return 3;
        }
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        Slog.w(getLogTag(), "onEnrollResult() called for authenticate!");
        return true;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        Slog.w(getLogTag(), "onRemoved() called for authenticate!");
        return true;
    }

    @Override // com.android.server.biometrics.ClientMonitor
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        Slog.w(getLogTag(), "onEnumerationResult() called for authenticate!");
        return true;
    }
}
