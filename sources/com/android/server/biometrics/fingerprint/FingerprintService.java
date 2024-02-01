package com.android.server.biometrics.fingerprint;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.am.AssistDataRequester;
import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
import com.android.server.biometrics.Constants;
import com.android.server.biometrics.EnumerateClient;
import com.android.server.biometrics.RemovalClient;
import com.android.server.biometrics.fingerprint.FingerprintService;
import com.android.server.utils.PriorityDump;
import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class FingerprintService extends BiometricServiceBase {
    private static final String ACTION_LOCKOUT_RESET = "com.android.server.biometrics.fingerprint.ACTION_LOCKOUT_RESET";
    private static final boolean DEBUG = true;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30000;
    private static final String FP_DATA_DIR = "fpdata";
    private static final String KEY_LOCKOUT_RESET_USER = "lockout_reset_user";
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 20;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5;
    protected static final String TAG = "FingerprintService";
    private final AlarmManager mAlarmManager;
    private final CopyOnWriteArrayList<IFingerprintClientActiveCallback> mClientActiveCallbacks;
    @GuardedBy({"this"})
    private IBiometricsFingerprint mDaemon;
    private IBiometricsFingerprintClientCallback mDaemonCallback;
    private final BiometricServiceBase.DaemonWrapper mDaemonWrapper;
    private final SparseIntArray mFailedAttempts;
    private final FingerprintConstants mFingerprintConstants;
    private final LockoutReceiver mLockoutReceiver;
    protected final ResetFailedAttemptsForUserRunnable mResetFailedAttemptsForCurrentUserRunnable;
    private final SparseBooleanArray mTimedLockoutCleared;

    /* loaded from: classes.dex */
    private final class ResetFailedAttemptsForUserRunnable implements Runnable {
        private ResetFailedAttemptsForUserRunnable() {
        }

        /* synthetic */ ResetFailedAttemptsForUserRunnable(FingerprintService x0, AnonymousClass1 x1) {
            this();
        }

        @Override // java.lang.Runnable
        public void run() {
            FingerprintService.this.resetFailedAttemptsForUser(true, ActivityManager.getCurrentUser());
        }
    }

    /* loaded from: classes.dex */
    private final class LockoutReceiver extends BroadcastReceiver {
        private LockoutReceiver() {
        }

        /* synthetic */ LockoutReceiver(FingerprintService x0, AnonymousClass1 x1) {
            this();
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String tag = FingerprintService.this.getTag();
            Slog.v(tag, "Resetting lockout: " + intent.getAction());
            if (FingerprintService.this.getLockoutResetIntent().equals(intent.getAction())) {
                int user = intent.getIntExtra(FingerprintService.KEY_LOCKOUT_RESET_USER, 0);
                FingerprintService.this.resetFailedAttemptsForUser(false, user);
            }
        }
    }

    /* loaded from: classes.dex */
    private final class FingerprintAuthClient extends BiometricServiceBase.AuthenticationClientImpl {
        @Override // com.android.server.biometrics.BiometricServiceBase.AuthenticationClientImpl
        protected boolean isFingerprint() {
            return true;
        }

        public FingerprintAuthClient(Context context, BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token, BiometricServiceBase.ServiceListener listener, int targetUserId, int groupId, long opId, boolean restricted, String owner, int cookie, boolean requireConfirmation) {
            super(context, daemon, halDeviceId, token, listener, targetUserId, groupId, opId, restricted, owner, cookie, requireConfirmation);
        }

        @Override // com.android.server.biometrics.LoggableMonitor
        protected int statsModality() {
            return FingerprintService.this.statsModality();
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public void resetFailedAttempts() {
            FingerprintService.this.resetFailedAttemptsForUser(true, ActivityManager.getCurrentUser());
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public boolean shouldFrameworkHandleLockout() {
            return true;
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public boolean wasUserDetected() {
            return false;
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.AuthenticationClientImpl, com.android.server.biometrics.AuthenticationClient
        public int handleFailedAttempt() {
            int currentUser = ActivityManager.getCurrentUser();
            FingerprintService.this.mFailedAttempts.put(currentUser, FingerprintService.this.mFailedAttempts.get(currentUser, 0) + 1);
            FingerprintService.this.mTimedLockoutCleared.put(ActivityManager.getCurrentUser(), false);
            if (FingerprintService.this.getLockoutMode() != 0) {
                FingerprintService.this.scheduleLockoutResetForUser(currentUser);
            }
            return super.handleFailedAttempt();
        }
    }

    /* loaded from: classes.dex */
    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        private static final int ENROLL_TIMEOUT_SEC = 60;

        private FingerprintServiceWrapper() {
        }

        /* synthetic */ FingerprintServiceWrapper(FingerprintService x0, AnonymousClass1 x1) {
            this();
        }

        public long preEnroll(IBinder token) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            return FingerprintService.this.startPreEnroll(token);
        }

        public int postEnroll(IBinder token) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            return FingerprintService.this.startPostEnroll(token);
        }

        public void enroll(IBinder token, byte[] cryptoToken, int userId, IFingerprintServiceReceiver receiver, int flags, String opPackageName) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            boolean restricted = FingerprintService.this.isRestricted();
            BiometricServiceBase.EnrollClientImpl client = new BiometricServiceBase.EnrollClientImpl(FingerprintService.this.getContext(), FingerprintService.this.mDaemonWrapper, FingerprintService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), FingerprintService.this.mCurrentUserId, userId, cryptoToken, restricted, opPackageName, new int[0], 60) { // from class: com.android.server.biometrics.fingerprint.FingerprintService.FingerprintServiceWrapper.1
                {
                    FingerprintService fingerprintService = FingerprintService.this;
                }

                @Override // com.android.server.biometrics.EnrollClient
                public boolean shouldVibrate() {
                    return true;
                }

                @Override // com.android.server.biometrics.LoggableMonitor
                protected int statsModality() {
                    return FingerprintService.this.statsModality();
                }
            };
            FingerprintService.this.enrollInternal(client, userId);
        }

        public void cancelEnrollment(IBinder token) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            FingerprintService.this.cancelEnrollmentInternal(token);
        }

        public void authenticate(IBinder token, long opId, int groupId, IFingerprintServiceReceiver receiver, int flags, String opPackageName) {
            FingerprintService.this.updateActiveGroup(groupId, opPackageName);
            boolean restricted = FingerprintService.this.isRestricted();
            FingerprintService fingerprintService = FingerprintService.this;
            BiometricServiceBase.AuthenticationClientImpl client = new FingerprintAuthClient(fingerprintService.getContext(), FingerprintService.this.mDaemonWrapper, FingerprintService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), FingerprintService.this.mCurrentUserId, groupId, opId, restricted, opPackageName, 0, false);
            FingerprintService.this.authenticateInternal(client, opId, opPackageName);
        }

        public void prepareForAuthentication(IBinder token, long opId, int groupId, IBiometricServiceReceiverInternal wrapperReceiver, String opPackageName, int cookie, int callingUid, int callingPid, int callingUserId) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FingerprintService.this.updateActiveGroup(groupId, opPackageName);
            FingerprintService fingerprintService = FingerprintService.this;
            BiometricServiceBase.AuthenticationClientImpl client = new FingerprintAuthClient(fingerprintService.getContext(), FingerprintService.this.mDaemonWrapper, FingerprintService.this.mHalDeviceId, token, new BiometricPromptServiceListenerImpl(wrapperReceiver), FingerprintService.this.mCurrentUserId, groupId, opId, true, opPackageName, cookie, false);
            FingerprintService.this.authenticateInternal(client, opId, opPackageName, callingUid, callingPid, callingUserId);
        }

        public void startPreparedClient(int cookie) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FingerprintService.this.startCurrentClient(cookie);
        }

        public void cancelAuthentication(IBinder token, String opPackageName) {
            FingerprintService.this.cancelAuthenticationInternal(token, opPackageName);
        }

        public void cancelAuthenticationFromService(IBinder token, String opPackageName, int callingUid, int callingPid, int callingUserId, boolean fromClient) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FingerprintService.this.cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid, callingUserId, fromClient);
        }

        public void setActiveUser(int userId) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            FingerprintService.this.setActiveUserInternal(userId);
        }

        public void remove(IBinder token, int fingerId, int groupId, int userId, IFingerprintServiceReceiver receiver) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            if (token != null) {
                boolean restricted = FingerprintService.this.isRestricted();
                RemovalClient client = new RemovalClient(FingerprintService.this.getContext(), FingerprintService.this.getConstants(), FingerprintService.this.mDaemonWrapper, FingerprintService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), fingerId, groupId, userId, restricted, token.toString(), FingerprintService.this.getBiometricUtils()) { // from class: com.android.server.biometrics.fingerprint.FingerprintService.FingerprintServiceWrapper.2
                    @Override // com.android.server.biometrics.LoggableMonitor
                    protected int statsModality() {
                        return FingerprintService.this.statsModality();
                    }
                };
                FingerprintService.this.removeInternal(client);
                return;
            }
            Slog.w(FingerprintService.TAG, "remove(): token is null");
        }

        public void enumerate(IBinder token, int userId, IFingerprintServiceReceiver receiver) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            boolean restricted = FingerprintService.this.isRestricted();
            EnumerateClient client = new EnumerateClient(FingerprintService.this.getContext(), FingerprintService.this.getConstants(), FingerprintService.this.mDaemonWrapper, FingerprintService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), userId, userId, restricted, FingerprintService.this.getContext().getOpPackageName()) { // from class: com.android.server.biometrics.fingerprint.FingerprintService.FingerprintServiceWrapper.3
                @Override // com.android.server.biometrics.LoggableMonitor
                protected int statsModality() {
                    return FingerprintService.this.statsModality();
                }
            };
            FingerprintService.this.enumerateInternal(client);
        }

        public void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback) throws RemoteException {
            FingerprintService.super.addLockoutResetCallback(callback);
        }

        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(FingerprintService.this.getContext(), FingerprintService.TAG, pw)) {
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                if (args.length <= 0 || !PriorityDump.PROTO_ARG.equals(args[0])) {
                    FingerprintService.this.dumpInternal(pw);
                } else {
                    FingerprintService.this.dumpProto(fd);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            boolean z = false;
            if (FingerprintService.this.canUseBiometric(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                long token = Binder.clearCallingIdentity();
                try {
                    IBiometricsFingerprint daemon = FingerprintService.this.getFingerprintDaemon();
                    if (daemon != null) {
                        if (FingerprintService.this.mHalDeviceId != 0) {
                            z = true;
                        }
                    }
                    return z;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
            return false;
        }

        public void rename(final int fingerId, final int groupId, final String name) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            if (FingerprintService.this.isCurrentUserOrProfile(groupId)) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.fingerprint.FingerprintService.FingerprintServiceWrapper.4
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.getBiometricUtils().renameBiometricForUser(FingerprintService.this.getContext(), groupId, fingerId, name);
                    }
                });
            }
        }

        public List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName) {
            if (!FingerprintService.this.canUseBiometric(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }
            return FingerprintService.this.getEnrolledTemplates(userId);
        }

        public boolean hasEnrolledFingerprints(int userId, String opPackageName) {
            if (!FingerprintService.this.canUseBiometric(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                return false;
            }
            return FingerprintService.this.hasEnrolledBiometrics(userId);
        }

        public long getAuthenticatorId(String opPackageName) {
            return FingerprintService.super.getAuthenticatorId(opPackageName);
        }

        public void resetTimeout(byte[] token) {
            FingerprintService.this.checkPermission("android.permission.RESET_FINGERPRINT_LOCKOUT");
            FingerprintService fingerprintService = FingerprintService.this;
            if (fingerprintService.hasEnrolledBiometrics(fingerprintService.mCurrentUserId)) {
                FingerprintService.this.mHandler.post(FingerprintService.this.mResetFailedAttemptsForCurrentUserRunnable);
            } else {
                Slog.w(FingerprintService.TAG, "Ignoring lockout reset, no templates enrolled");
            }
        }

        public boolean isClientActive() {
            boolean z;
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            synchronized (FingerprintService.this) {
                z = (FingerprintService.this.getCurrentClient() == null && FingerprintService.this.getPendingClient() == null) ? false : true;
            }
            return z;
        }

        public void addClientActiveCallback(IFingerprintClientActiveCallback callback) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            FingerprintService.this.mClientActiveCallbacks.add(callback);
        }

        public void removeClientActiveCallback(IFingerprintClientActiveCallback callback) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            FingerprintService.this.mClientActiveCallbacks.remove(callback);
        }
    }

    /* loaded from: classes.dex */
    private class BiometricPromptServiceListenerImpl extends BiometricServiceBase.BiometricServiceListener {
        BiometricPromptServiceListenerImpl(IBiometricServiceReceiverInternal wrapperReceiver) {
            super(wrapperReceiver);
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode) throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onAcquired(acquiredInfo, FingerprintManager.getAcquiredString(FingerprintService.this.getContext(), acquiredInfo, vendorCode));
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onError(long deviceId, int error, int vendorCode, int cookie) throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onError(cookie, error, FingerprintManager.getErrorString(FingerprintService.this.getContext(), error, vendorCode));
            }
        }
    }

    /* loaded from: classes.dex */
    private class ServiceListenerImpl implements BiometricServiceBase.ServiceListener {
        private IFingerprintServiceReceiver mFingerprintServiceReceiver;

        public ServiceListenerImpl(IFingerprintServiceReceiver receiver) {
            this.mFingerprintServiceReceiver = receiver;
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
            IFingerprintServiceReceiver iFingerprintServiceReceiver = this.mFingerprintServiceReceiver;
            if (iFingerprintServiceReceiver != null) {
                Fingerprint fp = (Fingerprint) identifier;
                iFingerprintServiceReceiver.onEnrollResult(fp.getDeviceId(), fp.getBiometricId(), fp.getGroupId(), remaining);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode) throws RemoteException {
            IFingerprintServiceReceiver iFingerprintServiceReceiver = this.mFingerprintServiceReceiver;
            if (iFingerprintServiceReceiver != null) {
                iFingerprintServiceReceiver.onAcquired(deviceId, acquiredInfo, vendorCode);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAuthenticationSucceeded(long deviceId, BiometricAuthenticator.Identifier biometric, int userId) throws RemoteException {
            if (this.mFingerprintServiceReceiver != null) {
                if (biometric == null || (biometric instanceof Fingerprint)) {
                    this.mFingerprintServiceReceiver.onAuthenticationSucceeded(deviceId, (Fingerprint) biometric, userId);
                } else {
                    Slog.e(FingerprintService.TAG, "onAuthenticationSucceeded received non-fingerprint biometric");
                }
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAuthenticationFailed(long deviceId) throws RemoteException {
            IFingerprintServiceReceiver iFingerprintServiceReceiver = this.mFingerprintServiceReceiver;
            if (iFingerprintServiceReceiver != null) {
                iFingerprintServiceReceiver.onAuthenticationFailed(deviceId);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onError(long deviceId, int error, int vendorCode, int cookie) throws RemoteException {
            IFingerprintServiceReceiver iFingerprintServiceReceiver = this.mFingerprintServiceReceiver;
            if (iFingerprintServiceReceiver != null) {
                iFingerprintServiceReceiver.onError(deviceId, error, vendorCode);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
            IFingerprintServiceReceiver iFingerprintServiceReceiver = this.mFingerprintServiceReceiver;
            if (iFingerprintServiceReceiver != null) {
                Fingerprint fp = (Fingerprint) identifier;
                iFingerprintServiceReceiver.onRemoved(fp.getDeviceId(), fp.getBiometricId(), fp.getGroupId(), remaining);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onEnumerated(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
            IFingerprintServiceReceiver iFingerprintServiceReceiver = this.mFingerprintServiceReceiver;
            if (iFingerprintServiceReceiver != null) {
                Fingerprint fp = (Fingerprint) identifier;
                iFingerprintServiceReceiver.onEnumerated(fp.getDeviceId(), fp.getBiometricId(), fp.getGroupId(), remaining);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.biometrics.fingerprint.FingerprintService$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 extends IBiometricsFingerprintClientCallback.Stub {
        AnonymousClass1() {
        }

        @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
        public void onEnrollResult(final long deviceId, final int fingerId, final int groupId, final int remaining) {
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.fingerprint.-$$Lambda$FingerprintService$1$7-RPI0PwwgOAZtsXq2j72pQWwMc
                @Override // java.lang.Runnable
                public final void run() {
                    FingerprintService.AnonymousClass1.this.lambda$onEnrollResult$0$FingerprintService$1(groupId, fingerId, deviceId, remaining);
                }
            });
        }

        public /* synthetic */ void lambda$onEnrollResult$0$FingerprintService$1(int groupId, int fingerId, long deviceId, int remaining) {
            Fingerprint fingerprint = new Fingerprint(FingerprintService.this.getBiometricUtils().getUniqueName(FingerprintService.this.getContext(), groupId), groupId, fingerId, deviceId);
            FingerprintService.super.handleEnrollResult(fingerprint, remaining);
        }

        @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
        public void onAcquired(final long deviceId, final int acquiredInfo, final int vendorCode) {
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.fingerprint.-$$Lambda$FingerprintService$1$N1Y2Zwqq-x5yDKQsDTj2KQ5q7g4
                @Override // java.lang.Runnable
                public final void run() {
                    FingerprintService.AnonymousClass1.this.lambda$onAcquired$1$FingerprintService$1(deviceId, acquiredInfo, vendorCode);
                }
            });
        }

        public /* synthetic */ void lambda$onAcquired$1$FingerprintService$1(long deviceId, int acquiredInfo, int vendorCode) {
            FingerprintService.super.handleAcquired(deviceId, acquiredInfo, vendorCode);
        }

        @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
        public void onAuthenticated(final long deviceId, final int fingerId, final int groupId, final ArrayList<Byte> token) {
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.fingerprint.-$$Lambda$FingerprintService$1$7nMWCt41OE3k8ihjPNPqB0O8POU
                @Override // java.lang.Runnable
                public final void run() {
                    FingerprintService.AnonymousClass1.this.lambda$onAuthenticated$2$FingerprintService$1(groupId, fingerId, deviceId, token);
                }
            });
        }

        public /* synthetic */ void lambda$onAuthenticated$2$FingerprintService$1(int groupId, int fingerId, long deviceId, ArrayList token) {
            Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
            FingerprintService.super.handleAuthenticated(fp, token);
        }

        @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
        public void onError(final long deviceId, final int error, final int vendorCode) {
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.fingerprint.-$$Lambda$FingerprintService$1$cO88ecWuvWIBecLAEccxr5yeJK4
                @Override // java.lang.Runnable
                public final void run() {
                    FingerprintService.AnonymousClass1.this.lambda$onError$3$FingerprintService$1(deviceId, error, vendorCode);
                }
            });
        }

        public /* synthetic */ void lambda$onError$3$FingerprintService$1(long deviceId, int error, int vendorCode) {
            FingerprintService.super.handleError(deviceId, error, vendorCode);
            if (error == 1) {
                Slog.w(FingerprintService.TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
                synchronized (this) {
                    FingerprintService.this.mDaemon = null;
                    FingerprintService.this.mHalDeviceId = 0L;
                    FingerprintService.this.mCurrentUserId = -10000;
                }
            }
        }

        @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
        public void onRemoved(final long deviceId, final int fingerId, final int groupId, final int remaining) {
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.fingerprint.-$$Lambda$FingerprintService$1$BJntfNoFTejPmUJ_45WFIwis8Nw
                @Override // java.lang.Runnable
                public final void run() {
                    FingerprintService.AnonymousClass1.this.lambda$onRemoved$4$FingerprintService$1(groupId, fingerId, deviceId, remaining);
                }
            });
        }

        public /* synthetic */ void lambda$onRemoved$4$FingerprintService$1(int groupId, int fingerId, long deviceId, int remaining) {
            FingerprintService.this.getCurrentClient();
            Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
            FingerprintService.super.handleRemoved(fp, remaining);
        }

        @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
        public void onEnumerate(final long deviceId, final int fingerId, final int groupId, final int remaining) {
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.fingerprint.-$$Lambda$FingerprintService$1$3I9ge5BoesXZUovbayCOCR754fc
                @Override // java.lang.Runnable
                public final void run() {
                    FingerprintService.AnonymousClass1.this.lambda$onEnumerate$5$FingerprintService$1(groupId, fingerId, deviceId, remaining);
                }
            });
        }

        public /* synthetic */ void lambda$onEnumerate$5$FingerprintService$1(int groupId, int fingerId, long deviceId, int remaining) {
            Fingerprint fp = new Fingerprint("", groupId, fingerId, deviceId);
            FingerprintService.super.handleEnumerate(fp, remaining);
        }
    }

    public FingerprintService(Context context) {
        super(context);
        this.mFingerprintConstants = new FingerprintConstants();
        this.mClientActiveCallbacks = new CopyOnWriteArrayList<>();
        this.mLockoutReceiver = new LockoutReceiver(this, null);
        this.mResetFailedAttemptsForCurrentUserRunnable = new ResetFailedAttemptsForUserRunnable(this, null);
        this.mDaemonCallback = new AnonymousClass1();
        this.mDaemonWrapper = new BiometricServiceBase.DaemonWrapper() { // from class: com.android.server.biometrics.fingerprint.FingerprintService.2
            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int authenticate(long operationId, int groupId) throws RemoteException {
                IBiometricsFingerprint daemon = FingerprintService.this.getFingerprintDaemon();
                if (daemon == null) {
                    Slog.w(FingerprintService.TAG, "authenticate(): no fingerprint HAL!");
                    return 3;
                }
                return daemon.authenticate(operationId, groupId);
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int cancel() throws RemoteException {
                IBiometricsFingerprint daemon = FingerprintService.this.getFingerprintDaemon();
                if (daemon == null) {
                    Slog.w(FingerprintService.TAG, "cancel(): no fingerprint HAL!");
                    return 3;
                }
                return daemon.cancel();
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int remove(int groupId, int biometricId) throws RemoteException {
                IBiometricsFingerprint daemon = FingerprintService.this.getFingerprintDaemon();
                if (daemon == null) {
                    Slog.w(FingerprintService.TAG, "remove(): no fingerprint HAL!");
                    return 3;
                }
                return daemon.remove(groupId, biometricId);
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int enumerate() throws RemoteException {
                IBiometricsFingerprint daemon = FingerprintService.this.getFingerprintDaemon();
                if (daemon == null) {
                    Slog.w(FingerprintService.TAG, "enumerate(): no fingerprint HAL!");
                    return 3;
                }
                return daemon.enumerate();
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int enroll(byte[] cryptoToken, int groupId, int timeout, ArrayList<Integer> disabledFeatures) throws RemoteException {
                IBiometricsFingerprint daemon = FingerprintService.this.getFingerprintDaemon();
                if (daemon == null) {
                    Slog.w(FingerprintService.TAG, "enroll(): no fingerprint HAL!");
                    return 3;
                }
                return daemon.enroll(cryptoToken, groupId, timeout);
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public void resetLockout(byte[] token) throws RemoteException {
                Slog.e(FingerprintService.TAG, "Not supported");
            }
        };
        this.mTimedLockoutCleared = new SparseBooleanArray();
        this.mFailedAttempts = new SparseIntArray();
        this.mAlarmManager = (AlarmManager) context.getSystemService(AlarmManager.class);
        context.registerReceiver(this.mLockoutReceiver, new IntentFilter(getLockoutResetIntent()), getLockoutBroadcastPermission(), null);
    }

    @Override // com.android.server.biometrics.BiometricServiceBase, com.android.server.SystemService
    public void onStart() {
        super.onStart();
        publishBinderService("fingerprint", new FingerprintServiceWrapper(this, null));
        SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.biometrics.fingerprint.-$$Lambda$FingerprintService$YOMIOLvco2SvXVeJIulOSVKdX7A
            @Override // java.lang.Runnable
            public final void run() {
                FingerprintService.this.getFingerprintDaemon();
            }
        }, "FingerprintService.onStart");
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getTag() {
        return TAG;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected BiometricServiceBase.DaemonWrapper getDaemonWrapper() {
        return this.mDaemonWrapper;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected BiometricUtils getBiometricUtils() {
        return FingerprintUtils.getInstance();
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected Constants getConstants() {
        return this.mFingerprintConstants;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean hasReachedEnrollmentLimit(int userId) {
        int limit = getContext().getResources().getInteger(17694812);
        int enrolled = getEnrolledTemplates(userId).size();
        if (enrolled >= limit) {
            Slog.w(TAG, "Too many fingerprints registered");
            return true;
        }
        return false;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    public void serviceDied(long cookie) {
        super.serviceDied(cookie);
        this.mDaemon = null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void updateActiveGroup(int userId, String clientPackage) {
        File baseDir;
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon != null) {
            try {
                int userId2 = getUserOrWorkProfileId(clientPackage, userId);
                if (userId2 != this.mCurrentUserId) {
                    int firstSdkInt = Build.VERSION.FIRST_SDK_INT;
                    if (firstSdkInt < 1) {
                        Slog.e(TAG, "First SDK version " + firstSdkInt + " is invalid; must be at least VERSION_CODES.BASE");
                    }
                    if (firstSdkInt <= 27) {
                        baseDir = Environment.getUserSystemDirectory(userId2);
                    } else {
                        baseDir = Environment.getDataVendorDeDirectory(userId2);
                    }
                    File fpDir = new File(baseDir, FP_DATA_DIR);
                    if (!fpDir.exists()) {
                        if (!fpDir.mkdir()) {
                            Slog.v(TAG, "Cannot make directory: " + fpDir.getAbsolutePath());
                            return;
                        } else if (!SELinux.restorecon(fpDir)) {
                            Slog.w(TAG, "Restorecons failed. Directory will have wrong label.");
                            return;
                        }
                    }
                    daemon.setActiveGroup(userId2, fpDir.getAbsolutePath());
                    this.mCurrentUserId = userId2;
                }
                this.mAuthenticatorIds.put(Integer.valueOf(userId2), Long.valueOf(hasEnrolledBiometrics(userId2) ? daemon.getAuthenticatorId() : 0L));
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveGroup():", e);
            }
        }
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getLockoutResetIntent() {
        return ACTION_LOCKOUT_RESET;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getLockoutBroadcastPermission() {
        return "android.permission.RESET_FINGERPRINT_LOCKOUT";
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected long getHalDeviceId() {
        return this.mHalDeviceId;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean hasEnrolledBiometrics(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission("android.permission.INTERACT_ACROSS_USERS");
        }
        return getBiometricUtils().getBiometricsForUser(getContext(), userId).size() > 0;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getManageBiometricPermission() {
        return "android.permission.MANAGE_FINGERPRINT";
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void checkUseBiometricPermission() {
        if (getContext().checkCallingPermission("android.permission.USE_FINGERPRINT") != 0) {
            checkPermission("android.permission.USE_BIOMETRIC");
        }
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean checkAppOps(int uid, String opPackageName) {
        if (this.mAppOps.noteOp(78, uid, opPackageName) != 0 && this.mAppOps.noteOp(55, uid, opPackageName) != 0) {
            return false;
        }
        return true;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected List<Fingerprint> getEnrolledTemplates(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission("android.permission.INTERACT_ACROSS_USERS");
        }
        return getBiometricUtils().getBiometricsForUser(getContext(), userId);
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void notifyClientActiveCallbacks(boolean isActive) {
        List<IFingerprintClientActiveCallback> callbacks = this.mClientActiveCallbacks;
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onClientActiveChanged(isActive);
            } catch (RemoteException e) {
                this.mClientActiveCallbacks.remove(callbacks.get(i));
            }
        }
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected int statsModality() {
        return 1;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected int getLockoutMode() {
        int currentUser = ActivityManager.getCurrentUser();
        int failedAttempts = this.mFailedAttempts.get(currentUser, 0);
        if (failedAttempts >= 20) {
            return 2;
        }
        return (failedAttempts <= 0 || this.mTimedLockoutCleared.get(currentUser, false) || failedAttempts % 5 != 0) ? 0 : 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized IBiometricsFingerprint getFingerprintDaemon() {
        if (this.mDaemon == null) {
            Slog.v(TAG, "mDaemon was null, reconnect to fingerprint");
            try {
                this.mDaemon = IBiometricsFingerprint.getService();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get biometric interface", e);
            } catch (NoSuchElementException e2) {
            }
            if (this.mDaemon == null) {
                Slog.w(TAG, "fingerprint HIDL not available");
                return null;
            }
            this.mDaemon.asBinder().linkToDeath(this, 0L);
            try {
                this.mHalDeviceId = this.mDaemon.setNotify(this.mDaemonCallback);
            } catch (RemoteException e3) {
                Slog.e(TAG, "Failed to open fingerprint HAL", e3);
                this.mDaemon = null;
            }
            Slog.v(TAG, "Fingerprint HAL id: " + this.mHalDeviceId);
            if (this.mHalDeviceId != 0) {
                loadAuthenticatorIds();
                updateActiveGroup(ActivityManager.getCurrentUser(), null);
                doTemplateCleanupForUser(ActivityManager.getCurrentUser());
            } else {
                Slog.w(TAG, "Failed to open Fingerprint HAL!");
                MetricsLogger.count(getContext(), "fingerprintd_openhal_error", 1);
                this.mDaemon = null;
            }
        }
        return this.mDaemon;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long startPreEnroll(IBinder token) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPreEnroll: no fingerprint HAL!");
            return 0L;
        }
        try {
            return daemon.preEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "startPreEnroll failed", e);
            return 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int startPostEnroll(IBinder token) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPostEnroll: no fingerprint HAL!");
            return 0;
        }
        try {
            return daemon.postEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "startPostEnroll failed", e);
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetFailedAttemptsForUser(boolean clearAttemptCounter, int userId) {
        if (getLockoutMode() != 0) {
            String tag = getTag();
            Slog.v(tag, "Reset biometric lockout, clearAttemptCounter=" + clearAttemptCounter);
        }
        if (clearAttemptCounter) {
            this.mFailedAttempts.put(userId, 0);
        }
        this.mTimedLockoutCleared.put(userId, true);
        cancelLockoutResetForUser(userId);
        notifyLockoutResetMonitors();
    }

    private void cancelLockoutResetForUser(int userId) {
        this.mAlarmManager.cancel(getLockoutResetIntentForUser(userId));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleLockoutResetForUser(int userId) {
        this.mAlarmManager.setExact(2, SystemClock.elapsedRealtime() + 30000, getLockoutResetIntentForUser(userId));
    }

    private PendingIntent getLockoutResetIntentForUser(int userId) {
        return PendingIntent.getBroadcast(getContext(), userId, new Intent(getLockoutResetIntent()).putExtra(KEY_LOCKOUT_RESET_USER, userId), 134217728);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpInternal(PrintWriter pw) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Fingerprint Manager");
            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                int userId = user.getUserHandle().getIdentifier();
                int N = getBiometricUtils().getBiometricsForUser(getContext(), userId).size();
                BiometricServiceBase.PerformanceStats stats = this.mPerformanceMap.get(Integer.valueOf(userId));
                BiometricServiceBase.PerformanceStats cryptoStats = this.mCryptoPerformanceMap.get(Integer.valueOf(userId));
                JSONObject set = new JSONObject();
                set.put("id", userId);
                set.put(AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, N);
                int i = 0;
                set.put("accept", stats != null ? stats.accept : 0);
                set.put("reject", stats != null ? stats.reject : 0);
                set.put("acquire", stats != null ? stats.acquire : 0);
                set.put("lockout", stats != null ? stats.lockout : 0);
                set.put("permanentLockout", stats != null ? stats.permanentLockout : 0);
                set.put("acceptCrypto", cryptoStats != null ? cryptoStats.accept : 0);
                set.put("rejectCrypto", cryptoStats != null ? cryptoStats.reject : 0);
                set.put("acquireCrypto", cryptoStats != null ? cryptoStats.acquire : 0);
                set.put("lockoutCrypto", cryptoStats != null ? cryptoStats.lockout : 0);
                if (cryptoStats != null) {
                    i = cryptoStats.permanentLockout;
                }
                set.put("permanentLockoutCrypto", i);
                sets.put(set);
            }
            dump.put("prints", sets);
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + this.mHALDeathCount);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpProto(FileDescriptor fd) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(getContext()).getUsers()) {
            int userId = user.getUserHandle().getIdentifier();
            long userToken = proto.start(2246267895809L);
            proto.write(1120986464257L, userId);
            proto.write(1120986464258L, getBiometricUtils().getBiometricsForUser(getContext(), userId).size());
            BiometricServiceBase.PerformanceStats normal = this.mPerformanceMap.get(Integer.valueOf(userId));
            if (normal != null) {
                long countsToken = proto.start(1146756268035L);
                proto.write(1120986464257L, normal.accept);
                proto.write(1120986464258L, normal.reject);
                proto.write(1120986464259L, normal.acquire);
                proto.write(1120986464260L, normal.lockout);
                proto.write(1120986464261L, normal.permanentLockout);
                proto.end(countsToken);
            }
            BiometricServiceBase.PerformanceStats crypto = this.mCryptoPerformanceMap.get(Integer.valueOf(userId));
            if (crypto != null) {
                long countsToken2 = proto.start(1146756268036L);
                proto.write(1120986464257L, crypto.accept);
                proto.write(1120986464258L, crypto.reject);
                proto.write(1120986464259L, crypto.acquire);
                proto.write(1120986464260L, crypto.lockout);
                proto.write(1120986464261L, crypto.permanentLockout);
                proto.end(countsToken2);
            }
            proto.end(userToken);
        }
        proto.flush();
        this.mPerformanceMap.clear();
        this.mCryptoPerformanceMap.clear();
    }
}
