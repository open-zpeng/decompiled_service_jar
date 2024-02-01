package com.android.server.biometrics.face;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.face.IFaceService;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.am.AssistDataRequester;
import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
import com.android.server.biometrics.ClientMonitor;
import com.android.server.biometrics.Constants;
import com.android.server.biometrics.EnumerateClient;
import com.android.server.biometrics.RemovalClient;
import com.android.server.biometrics.face.FaceService;
import com.android.server.job.controllers.JobStatus;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class FaceService extends BiometricServiceBase {
    private static final String ACTION_LOCKOUT_RESET = "com.android.server.biometrics.face.ACTION_LOCKOUT_RESET";
    private static final int CHALLENGE_TIMEOUT_SEC = 600;
    private static final boolean DEBUG = true;
    private static final String FACE_DATA_DIR = "facedata";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_TAG = "FaceService";
    protected static final String TAG = "FaceService";
    private int[] mBiometricPromptIgnoreList;
    private int[] mBiometricPromptIgnoreListVendor;
    private int mCurrentUserLockoutMode;
    @GuardedBy({"this"})
    private IBiometricsFace mDaemon;
    private IBiometricsFaceClientCallback mDaemonCallback;
    private final BiometricServiceBase.DaemonWrapper mDaemonWrapper;
    private int[] mEnrollIgnoreList;
    private int[] mEnrollIgnoreListVendor;
    private final FaceConstants mFaceConstants;
    private int[] mKeyguardIgnoreList;
    private int[] mKeyguardIgnoreListVendor;
    private NotificationManager mNotificationManager;
    private boolean mRevokeChallengePending;
    private UsageStats mUsageStats;

    /* loaded from: classes.dex */
    public static final class AuthenticationEvent {
        private boolean mAuthenticated;
        private int mError;
        private long mLatency;
        private long mStartTime;
        private int mVendorError;

        AuthenticationEvent(long startTime, long latency, boolean authenticated, int error, int vendorError) {
            this.mStartTime = startTime;
            this.mLatency = latency;
            this.mAuthenticated = authenticated;
            this.mError = error;
            this.mVendorError = vendorError;
        }

        public String toString(Context context) {
            return "Start: " + this.mStartTime + "\tLatency: " + this.mLatency + "\tAuthenticated: " + this.mAuthenticated + "\tError: " + this.mError + "\tVendorCode: " + this.mVendorError + "\t" + FaceManager.getErrorString(context, this.mError, this.mVendorError);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UsageStats {
        static final int EVENT_LOG_SIZE = 100;
        int acceptCount;
        long acceptLatency;
        Context mContext;
        int rejectCount;
        long rejectLatency;
        List<AuthenticationEvent> mAuthenticationEvents = new ArrayList();
        Map<Integer, Integer> mErrorCount = new HashMap();
        Map<Integer, Long> mErrorLatency = new HashMap();

        UsageStats(Context context) {
            this.mContext = context;
        }

        void addEvent(AuthenticationEvent event) {
            if (this.mAuthenticationEvents.size() >= 100) {
                this.mAuthenticationEvents.remove(0);
            }
            this.mAuthenticationEvents.add(event);
            if (!event.mAuthenticated) {
                if (event.mError != 0) {
                    this.mErrorCount.put(Integer.valueOf(event.mError), Integer.valueOf(this.mErrorCount.getOrDefault(Integer.valueOf(event.mError), 0).intValue() + 1));
                    this.mErrorLatency.put(Integer.valueOf(event.mError), Long.valueOf(this.mErrorLatency.getOrDefault(Integer.valueOf(event.mError), 0L).longValue() + event.mLatency));
                    return;
                }
                this.rejectCount++;
                this.rejectLatency += event.mLatency;
                return;
            }
            this.acceptCount++;
            this.acceptLatency += event.mLatency;
        }

        void print(PrintWriter pw) {
            pw.println("Events since last reboot: " + this.mAuthenticationEvents.size());
            for (int i = 0; i < this.mAuthenticationEvents.size(); i++) {
                pw.println(this.mAuthenticationEvents.get(i).toString(this.mContext));
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Accept\tCount: ");
            sb.append(this.acceptCount);
            sb.append("\tLatency: ");
            sb.append(this.acceptLatency);
            sb.append("\tAverage: ");
            int i2 = this.acceptCount;
            sb.append(i2 > 0 ? this.acceptLatency / i2 : 0L);
            pw.println(sb.toString());
            StringBuilder sb2 = new StringBuilder();
            sb2.append("Reject\tCount: ");
            sb2.append(this.rejectCount);
            sb2.append("\tLatency: ");
            sb2.append(this.rejectLatency);
            sb2.append("\tAverage: ");
            int i3 = this.rejectCount;
            sb2.append(i3 > 0 ? this.rejectLatency / i3 : 0L);
            pw.println(sb2.toString());
            for (Integer key : this.mErrorCount.keySet()) {
                int count = this.mErrorCount.get(key).intValue();
                StringBuilder sb3 = new StringBuilder();
                sb3.append("Error");
                sb3.append(key);
                sb3.append("\tCount: ");
                sb3.append(count);
                sb3.append("\tLatency: ");
                sb3.append(this.mErrorLatency.getOrDefault(key, 0L));
                sb3.append("\tAverage: ");
                sb3.append(count > 0 ? this.mErrorLatency.getOrDefault(key, 0L).longValue() / count : 0L);
                sb3.append("\t");
                sb3.append(FaceManager.getErrorString(this.mContext, key.intValue(), 0));
                pw.println(sb3.toString());
            }
        }
    }

    /* loaded from: classes.dex */
    private final class FaceAuthClient extends BiometricServiceBase.AuthenticationClientImpl {
        private int mLastAcquire;

        public FaceAuthClient(Context context, BiometricServiceBase.DaemonWrapper daemon, long halDeviceId, IBinder token, BiometricServiceBase.ServiceListener listener, int targetUserId, int groupId, long opId, boolean restricted, String owner, int cookie, boolean requireConfirmation) {
            super(context, daemon, halDeviceId, token, listener, targetUserId, groupId, opId, restricted, owner, cookie, requireConfirmation);
        }

        @Override // com.android.server.biometrics.LoggableMonitor
        protected int statsModality() {
            return FaceService.this.statsModality();
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public boolean shouldFrameworkHandleLockout() {
            return false;
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public boolean wasUserDetected() {
            int i = this.mLastAcquire;
            return (i == 11 || i == 21) ? false : true;
        }

        @Override // com.android.server.biometrics.AuthenticationClient, com.android.server.biometrics.ClientMonitor
        public boolean onAuthenticated(BiometricAuthenticator.Identifier identifier, boolean authenticated, ArrayList<Byte> token) {
            boolean result = super.onAuthenticated(identifier, authenticated, token);
            FaceService.this.mUsageStats.addEvent(new AuthenticationEvent(getStartTimeMs(), System.currentTimeMillis() - getStartTimeMs(), authenticated, 0, 0));
            return result || !authenticated;
        }

        @Override // com.android.server.biometrics.AuthenticationClient, com.android.server.biometrics.ClientMonitor
        public boolean onError(long deviceId, int error, int vendorCode) {
            FaceService.this.mUsageStats.addEvent(new AuthenticationEvent(getStartTimeMs(), System.currentTimeMillis() - getStartTimeMs(), false, error, vendorCode));
            return super.onError(deviceId, error, vendorCode);
        }

        @Override // com.android.server.biometrics.ClientMonitor
        public int[] getAcquireIgnorelist() {
            return isBiometricPrompt() ? FaceService.this.mBiometricPromptIgnoreList : FaceService.this.mKeyguardIgnoreList;
        }

        @Override // com.android.server.biometrics.ClientMonitor
        public int[] getAcquireVendorIgnorelist() {
            return isBiometricPrompt() ? FaceService.this.mBiometricPromptIgnoreListVendor : FaceService.this.mKeyguardIgnoreListVendor;
        }

        @Override // com.android.server.biometrics.ClientMonitor
        public boolean onAcquired(int acquireInfo, int vendorCode) {
            this.mLastAcquire = acquireInfo;
            if (acquireInfo == 13) {
                String name = getContext().getString(17040000);
                String title = getContext().getString(17040001);
                String content = getContext().getString(17039999);
                Intent intent = new Intent("android.settings.FACE_SETTINGS");
                intent.setPackage("com.android.settings");
                PendingIntent pendingIntent = PendingIntent.getActivityAsUser(getContext(), 0, intent, 0, null, UserHandle.CURRENT);
                NotificationChannel channel = new NotificationChannel("FaceEnrollNotificationChannel", name, 4);
                Notification notification = new Notification.Builder(getContext(), "FaceEnrollNotificationChannel").setSmallIcon(17302452).setContentTitle(title).setContentText(content).setSubText(name).setOnlyAlertOnce(true).setLocalOnly(true).setAutoCancel(true).setCategory("sys").setContentIntent(pendingIntent).setVisibility(-1).build();
                FaceService.this.mNotificationManager.createNotificationChannel(channel);
                FaceService.this.mNotificationManager.notifyAsUser("FaceService", 1, notification, UserHandle.CURRENT);
            }
            return super.onAcquired(acquireInfo, vendorCode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class FaceServiceWrapper extends IFaceService.Stub {
        private static final int ENROLL_TIMEOUT_SEC = 75;

        private FaceServiceWrapper() {
        }

        /* synthetic */ FaceServiceWrapper(FaceService x0, AnonymousClass1 x1) {
            this();
        }

        public long generateChallenge(IBinder token) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            return FaceService.this.startGenerateChallenge(token);
        }

        public int revokeChallenge(final IBinder token) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$FaceServiceWrapper$oUY0TN9T4s4roMpe33Oc2nS7uzI
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.FaceServiceWrapper.this.lambda$revokeChallenge$0$FaceService$FaceServiceWrapper(token);
                }
            });
            return 0;
        }

        public /* synthetic */ void lambda$revokeChallenge$0$FaceService$FaceServiceWrapper(IBinder token) {
            if (FaceService.this.getCurrentClient() == null) {
                FaceService.this.startRevokeChallenge(token);
            } else {
                FaceService.this.mRevokeChallengePending = true;
            }
        }

        public void enroll(int userId, IBinder token, byte[] cryptoToken, IFaceServiceReceiver receiver, String opPackageName, int[] disabledFeatures) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.updateActiveGroup(userId, opPackageName);
            FaceService.this.mNotificationManager.cancelAsUser("FaceService", 1, UserHandle.CURRENT);
            boolean restricted = FaceService.this.isRestricted();
            BiometricServiceBase.EnrollClientImpl client = new BiometricServiceBase.EnrollClientImpl(FaceService.this.getContext(), FaceService.this.mDaemonWrapper, FaceService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), FaceService.this.mCurrentUserId, 0, cryptoToken, restricted, opPackageName, disabledFeatures, 75) { // from class: com.android.server.biometrics.face.FaceService.FaceServiceWrapper.1
                {
                    FaceService faceService = FaceService.this;
                }

                @Override // com.android.server.biometrics.ClientMonitor
                public int[] getAcquireIgnorelist() {
                    return FaceService.this.mEnrollIgnoreList;
                }

                @Override // com.android.server.biometrics.ClientMonitor
                public int[] getAcquireVendorIgnorelist() {
                    return FaceService.this.mEnrollIgnoreListVendor;
                }

                @Override // com.android.server.biometrics.EnrollClient
                public boolean shouldVibrate() {
                    return false;
                }

                @Override // com.android.server.biometrics.LoggableMonitor
                protected int statsModality() {
                    return FaceService.this.statsModality();
                }
            };
            FaceService faceService = FaceService.this;
            faceService.enrollInternal(client, faceService.mCurrentUserId);
        }

        public void cancelEnrollment(IBinder token) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.cancelEnrollmentInternal(token);
        }

        public void authenticate(IBinder token, long opId, int userId, IFaceServiceReceiver receiver, int flags, String opPackageName) {
            FaceService.this.checkPermission("android.permission.USE_BIOMETRIC_INTERNAL");
            FaceService.this.updateActiveGroup(userId, opPackageName);
            boolean restricted = FaceService.this.isRestricted();
            FaceService faceService = FaceService.this;
            BiometricServiceBase.AuthenticationClientImpl client = new FaceAuthClient(faceService.getContext(), FaceService.this.mDaemonWrapper, FaceService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), FaceService.this.mCurrentUserId, 0, opId, restricted, opPackageName, 0, false);
            FaceService.this.authenticateInternal(client, opId, opPackageName);
        }

        public void prepareForAuthentication(boolean requireConfirmation, IBinder token, long opId, int groupId, IBiometricServiceReceiverInternal wrapperReceiver, String opPackageName, int cookie, int callingUid, int callingPid, int callingUserId) {
            FaceService.this.checkPermission("android.permission.USE_BIOMETRIC_INTERNAL");
            FaceService.this.updateActiveGroup(groupId, opPackageName);
            FaceService faceService = FaceService.this;
            BiometricServiceBase.AuthenticationClientImpl client = new FaceAuthClient(faceService.getContext(), FaceService.this.mDaemonWrapper, FaceService.this.mHalDeviceId, token, new BiometricPromptServiceListenerImpl(wrapperReceiver), FaceService.this.mCurrentUserId, 0, opId, true, opPackageName, cookie, requireConfirmation);
            FaceService.this.authenticateInternal(client, opId, opPackageName, callingUid, callingPid, callingUserId);
        }

        public void startPreparedClient(int cookie) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.startCurrentClient(cookie);
        }

        public void cancelAuthentication(IBinder token, String opPackageName) {
            FaceService.this.checkPermission("android.permission.USE_BIOMETRIC_INTERNAL");
            FaceService.this.cancelAuthenticationInternal(token, opPackageName);
        }

        public void cancelAuthenticationFromService(IBinder token, String opPackageName, int callingUid, int callingPid, int callingUserId, boolean fromClient) {
            FaceService.this.checkPermission("android.permission.USE_BIOMETRIC_INTERNAL");
            FaceService.this.cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid, callingUserId, fromClient);
        }

        public void setActiveUser(int userId) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.setActiveUserInternal(userId);
        }

        public void remove(IBinder token, int faceId, int userId, IFaceServiceReceiver receiver, String opPackageName) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.updateActiveGroup(userId, opPackageName);
            if (token != null) {
                boolean restricted = FaceService.this.isRestricted();
                RemovalClient client = new RemovalClient(FaceService.this.getContext(), FaceService.this.getConstants(), FaceService.this.mDaemonWrapper, FaceService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), faceId, 0, userId, restricted, token.toString(), FaceService.this.getBiometricUtils()) { // from class: com.android.server.biometrics.face.FaceService.FaceServiceWrapper.2
                    @Override // com.android.server.biometrics.LoggableMonitor
                    protected int statsModality() {
                        return FaceService.this.statsModality();
                    }
                };
                FaceService.this.removeInternal(client);
                return;
            }
            Slog.w("FaceService", "remove(): token is null");
        }

        public void enumerate(IBinder token, int userId, IFaceServiceReceiver receiver) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            boolean restricted = FaceService.this.isRestricted();
            EnumerateClient client = new EnumerateClient(FaceService.this.getContext(), FaceService.this.getConstants(), FaceService.this.mDaemonWrapper, FaceService.this.mHalDeviceId, token, new ServiceListenerImpl(receiver), userId, userId, restricted, FaceService.this.getContext().getOpPackageName()) { // from class: com.android.server.biometrics.face.FaceService.FaceServiceWrapper.3
                @Override // com.android.server.biometrics.LoggableMonitor
                protected int statsModality() {
                    return FaceService.this.statsModality();
                }
            };
            FaceService.this.enumerateInternal(client);
        }

        public void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback) throws RemoteException {
            FaceService.this.checkPermission("android.permission.USE_BIOMETRIC_INTERNAL");
            FaceService.super.addLockoutResetCallback(callback);
        }

        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(FaceService.this.getContext(), "FaceService", pw)) {
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                if (args.length <= 1 || !"--hal".equals(args[0])) {
                    FaceService.this.dumpInternal(pw);
                } else {
                    FaceService.this.dumpHal(fd, (String[]) Arrays.copyOfRange(args, 1, args.length, args.getClass()));
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            FaceService.this.checkPermission("android.permission.USE_BIOMETRIC_INTERNAL");
            boolean z = false;
            if (FaceService.this.canUseBiometric(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                long token = Binder.clearCallingIdentity();
                try {
                    IBiometricsFace daemon = FaceService.this.getFaceDaemon();
                    if (daemon != null) {
                        if (FaceService.this.mHalDeviceId != 0) {
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

        public void rename(final int faceId, final String name) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            if (FaceService.this.isCurrentUserOrProfile(UserHandle.getCallingUserId())) {
                FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.FaceService.FaceServiceWrapper.4
                    @Override // java.lang.Runnable
                    public void run() {
                        FaceService.this.getBiometricUtils().renameBiometricForUser(FaceService.this.getContext(), FaceService.this.mCurrentUserId, faceId, name);
                    }
                });
            }
        }

        public List<Face> getEnrolledFaces(int userId, String opPackageName) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            if (!FaceService.this.canUseBiometric(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                return null;
            }
            return FaceService.this.getEnrolledTemplates(userId);
        }

        public boolean hasEnrolledFaces(int userId, String opPackageName) {
            FaceService.this.checkPermission("android.permission.USE_BIOMETRIC_INTERNAL");
            if (!FaceService.this.canUseBiometric(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                return false;
            }
            return FaceService.this.hasEnrolledBiometrics(userId);
        }

        public long getAuthenticatorId(String opPackageName) {
            return FaceService.this.getAuthenticatorId(opPackageName);
        }

        public void resetLockout(final byte[] token) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$FaceServiceWrapper$kw0BBGgTrFveHiSJWRbNG8sygqA
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.FaceServiceWrapper.this.lambda$resetLockout$1$FaceService$FaceServiceWrapper(token);
                }
            });
        }

        public /* synthetic */ void lambda$resetLockout$1$FaceService$FaceServiceWrapper(byte[] token) {
            FaceService faceService = FaceService.this;
            if (!faceService.hasEnrolledBiometrics(faceService.mCurrentUserId)) {
                Slog.w("FaceService", "Ignoring lockout reset, no templates enrolled");
                return;
            }
            Slog.d("FaceService", "Resetting lockout for user: " + FaceService.this.mCurrentUserId);
            try {
                FaceService.this.mDaemonWrapper.resetLockout(token);
            } catch (RemoteException e) {
                Slog.e(FaceService.this.getTag(), "Unable to reset lockout", e);
            }
        }

        public void setFeature(final int userId, final int feature, final boolean enabled, final byte[] token, final IFaceServiceReceiver receiver, final String opPackageName) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$FaceServiceWrapper$1ZJGnsaJl1du-I_XjU-JKzIy49Q
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.FaceServiceWrapper.this.lambda$setFeature$2$FaceService$FaceServiceWrapper(userId, opPackageName, feature, token, enabled, receiver);
                }
            });
        }

        public /* synthetic */ void lambda$setFeature$2$FaceService$FaceServiceWrapper(int userId, String opPackageName, int feature, byte[] token, boolean enabled, IFaceServiceReceiver receiver) {
            Slog.d("FaceService", "setFeature for user(" + userId + ")");
            FaceService.this.updateActiveGroup(userId, opPackageName);
            FaceService faceService = FaceService.this;
            if (!faceService.hasEnrolledBiometrics(faceService.mCurrentUserId)) {
                Slog.e("FaceService", "No enrolled biometrics while setting feature: " + feature);
                return;
            }
            ArrayList<Byte> byteToken = new ArrayList<>();
            for (byte b : token) {
                byteToken.add(Byte.valueOf(b));
            }
            int faceId = getFirstTemplateForUser(FaceService.this.mCurrentUserId);
            if (FaceService.this.mDaemon != null) {
                try {
                    int result = FaceService.this.mDaemon.setFeature(feature, enabled, byteToken, faceId);
                    receiver.onFeatureSet(result == 0, feature);
                } catch (RemoteException e) {
                    Slog.e(FaceService.this.getTag(), "Unable to set feature: " + feature + " to enabled:" + enabled, e);
                }
            }
        }

        public void getFeature(final int userId, final int feature, final IFaceServiceReceiver receiver, final String opPackageName) {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$FaceServiceWrapper$6Efp5LtXdV1OgyOr4AaGf19hmLs
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.FaceServiceWrapper.this.lambda$getFeature$3$FaceService$FaceServiceWrapper(userId, opPackageName, feature, receiver);
                }
            });
        }

        public /* synthetic */ void lambda$getFeature$3$FaceService$FaceServiceWrapper(int userId, String opPackageName, int feature, IFaceServiceReceiver receiver) {
            Slog.d("FaceService", "getFeature for user(" + userId + ")");
            FaceService.this.updateActiveGroup(userId, opPackageName);
            FaceService faceService = FaceService.this;
            if (faceService.hasEnrolledBiometrics(faceService.mCurrentUserId)) {
                int faceId = getFirstTemplateForUser(FaceService.this.mCurrentUserId);
                if (FaceService.this.mDaemon != null) {
                    try {
                        OptionalBool result = FaceService.this.mDaemon.getFeature(feature, faceId);
                        receiver.onFeatureGet(result.status == 0, feature, result.value);
                        return;
                    } catch (RemoteException e) {
                        Slog.e(FaceService.this.getTag(), "Unable to getRequireAttention", e);
                        return;
                    }
                }
                return;
            }
            Slog.e("FaceService", "No enrolled biometrics while getting feature: " + feature);
        }

        public void userActivity() {
            FaceService.this.checkPermission("android.permission.MANAGE_BIOMETRIC");
            if (FaceService.this.mDaemon != null) {
                try {
                    FaceService.this.mDaemon.userActivity();
                } catch (RemoteException e) {
                    Slog.e(FaceService.this.getTag(), "Unable to send userActivity", e);
                }
            }
        }

        private int getFirstTemplateForUser(int user) {
            List<Face> faces = FaceService.this.getEnrolledTemplates(user);
            if (faces.isEmpty()) {
                return 0;
            }
            return faces.get(0).getBiometricId();
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
                getWrapperReceiver().onAcquired(FaceManager.getMappedAcquiredInfo(acquiredInfo, vendorCode), FaceManager.getAcquiredString(FaceService.this.getContext(), acquiredInfo, vendorCode));
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onError(long deviceId, int error, int vendorCode, int cookie) throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onError(cookie, error, FaceManager.getErrorString(FaceService.this.getContext(), error, vendorCode));
            }
        }
    }

    /* loaded from: classes.dex */
    private class ServiceListenerImpl implements BiometricServiceBase.ServiceListener {
        private IFaceServiceReceiver mFaceServiceReceiver;

        public ServiceListenerImpl(IFaceServiceReceiver receiver) {
            this.mFaceServiceReceiver = receiver;
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
            IFaceServiceReceiver iFaceServiceReceiver = this.mFaceServiceReceiver;
            if (iFaceServiceReceiver != null) {
                iFaceServiceReceiver.onEnrollResult(identifier.getDeviceId(), identifier.getBiometricId(), remaining);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAcquired(long deviceId, int acquiredInfo, int vendorCode) throws RemoteException {
            IFaceServiceReceiver iFaceServiceReceiver = this.mFaceServiceReceiver;
            if (iFaceServiceReceiver != null) {
                iFaceServiceReceiver.onAcquired(deviceId, acquiredInfo, vendorCode);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAuthenticationSucceeded(long deviceId, BiometricAuthenticator.Identifier biometric, int userId) throws RemoteException {
            if (this.mFaceServiceReceiver != null) {
                if (biometric == null || (biometric instanceof Face)) {
                    this.mFaceServiceReceiver.onAuthenticationSucceeded(deviceId, (Face) biometric, userId);
                } else {
                    Slog.e("FaceService", "onAuthenticationSucceeded received non-face biometric");
                }
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAuthenticationFailed(long deviceId) throws RemoteException {
            IFaceServiceReceiver iFaceServiceReceiver = this.mFaceServiceReceiver;
            if (iFaceServiceReceiver != null) {
                iFaceServiceReceiver.onAuthenticationFailed(deviceId);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onError(long deviceId, int error, int vendorCode, int cookie) throws RemoteException {
            IFaceServiceReceiver iFaceServiceReceiver = this.mFaceServiceReceiver;
            if (iFaceServiceReceiver != null) {
                iFaceServiceReceiver.onError(deviceId, error, vendorCode);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
            IFaceServiceReceiver iFaceServiceReceiver = this.mFaceServiceReceiver;
            if (iFaceServiceReceiver != null) {
                iFaceServiceReceiver.onRemoved(identifier.getDeviceId(), identifier.getBiometricId(), remaining);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onEnumerated(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
            IFaceServiceReceiver iFaceServiceReceiver = this.mFaceServiceReceiver;
            if (iFaceServiceReceiver != null) {
                iFaceServiceReceiver.onEnumerated(identifier.getDeviceId(), identifier.getBiometricId(), remaining);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.biometrics.face.FaceService$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 extends IBiometricsFaceClientCallback.Stub {
        AnonymousClass1() {
        }

        @Override // android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback
        public void onEnrollResult(final long deviceId, final int faceId, final int userId, final int remaining) {
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$1$Dg7kqAVO92T8FbodjRCfn9vSkto
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.AnonymousClass1.this.lambda$onEnrollResult$0$FaceService$1(userId, faceId, deviceId, remaining);
                }
            });
        }

        public /* synthetic */ void lambda$onEnrollResult$0$FaceService$1(int userId, int faceId, long deviceId, int remaining) {
            Face face = new Face(FaceService.this.getBiometricUtils().getUniqueName(FaceService.this.getContext(), userId), faceId, deviceId);
            FaceService.super.handleEnrollResult(face, remaining);
            IBiometricsFace daemon = FaceService.this.getFaceDaemon();
            if (remaining == 0 && daemon != null) {
                try {
                    FaceService.this.mAuthenticatorIds.put(Integer.valueOf(userId), Long.valueOf(FaceService.this.hasEnrolledBiometrics(userId) ? daemon.getAuthenticatorId().value : 0L));
                } catch (RemoteException e) {
                    Slog.e("FaceService", "Unable to get authenticatorId", e);
                }
            }
        }

        @Override // android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback
        public void onAcquired(final long deviceId, int userId, final int acquiredInfo, final int vendorCode) {
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$1$7DzDQwoPfgYi40WuB8Xi0hA3qVQ
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.AnonymousClass1.this.lambda$onAcquired$1$FaceService$1(deviceId, acquiredInfo, vendorCode);
                }
            });
        }

        public /* synthetic */ void lambda$onAcquired$1$FaceService$1(long deviceId, int acquiredInfo, int vendorCode) {
            FaceService.super.handleAcquired(deviceId, acquiredInfo, vendorCode);
        }

        @Override // android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback
        public void onAuthenticated(final long deviceId, final int faceId, int userId, final ArrayList<Byte> token) {
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$1$GcU4ZG1fdDLhKvSxuMwfPargEnI
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.AnonymousClass1.this.lambda$onAuthenticated$2$FaceService$1(faceId, deviceId, token);
                }
            });
        }

        public /* synthetic */ void lambda$onAuthenticated$2$FaceService$1(int faceId, long deviceId, ArrayList token) {
            Face face = new Face("", faceId, deviceId);
            FaceService.super.handleAuthenticated(face, token);
        }

        @Override // android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback
        public void onError(final long deviceId, int userId, final int error, final int vendorCode) {
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$1$s3kBxUsmTmDZC9YLbT5yPR3KOWo
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.AnonymousClass1.this.lambda$onError$3$FaceService$1(deviceId, error, vendorCode);
                }
            });
        }

        public /* synthetic */ void lambda$onError$3$FaceService$1(long deviceId, int error, int vendorCode) {
            FaceService.super.handleError(deviceId, error, vendorCode);
            if (error == 1) {
                Slog.w("FaceService", "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
                synchronized (this) {
                    FaceService.this.mDaemon = null;
                    FaceService.this.mHalDeviceId = 0L;
                    FaceService.this.mCurrentUserId = -10000;
                }
            }
        }

        @Override // android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback
        public void onRemoved(final long deviceId, final ArrayList<Integer> faceIds, int userId) {
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$1$jaJb2y4UkoXOtV5wJimfIPNA_PM
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.AnonymousClass1.this.lambda$onRemoved$4$FaceService$1(faceIds, deviceId);
                }
            });
        }

        public /* synthetic */ void lambda$onRemoved$4$FaceService$1(ArrayList faceIds, long deviceId) {
            if (!faceIds.isEmpty()) {
                for (int i = 0; i < faceIds.size(); i++) {
                    Face face = new Face("", ((Integer) faceIds.get(i)).intValue(), deviceId);
                    FaceService.super.handleRemoved(face, (faceIds.size() - i) - 1);
                }
            } else {
                Face face2 = new Face("", 0, deviceId);
                FaceService.super.handleRemoved(face2, 0);
            }
            Settings.Secure.putIntForUser(FaceService.this.getContext().getContentResolver(), "face_unlock_re_enroll", 0, -2);
        }

        @Override // android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback
        public void onEnumerate(final long deviceId, final ArrayList<Integer> faceIds, int userId) throws RemoteException {
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$1$81olYJI06zsG8LvXV_gD76jaNyg
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.AnonymousClass1.this.lambda$onEnumerate$5$FaceService$1(faceIds, deviceId);
                }
            });
        }

        public /* synthetic */ void lambda$onEnumerate$5$FaceService$1(ArrayList faceIds, long deviceId) {
            if (faceIds.isEmpty()) {
                FaceService.super.handleEnumerate(null, 0);
                return;
            }
            for (int i = 0; i < faceIds.size(); i++) {
                Face face = new Face("", ((Integer) faceIds.get(i)).intValue(), deviceId);
                FaceService.super.handleEnumerate(face, (faceIds.size() - i) - 1);
            }
        }

        @Override // android.hardware.biometrics.face.V1_0.IBiometricsFaceClientCallback
        public void onLockoutChanged(final long duration) {
            Slog.d("FaceService", "onLockoutChanged: " + duration);
            if (duration == 0) {
                FaceService.this.mCurrentUserLockoutMode = 0;
            } else if (duration == JobStatus.NO_LATEST_RUNTIME) {
                FaceService.this.mCurrentUserLockoutMode = 2;
            } else {
                FaceService.this.mCurrentUserLockoutMode = 1;
            }
            FaceService.this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$1$OiHHyHFXrIcrZYUfSsf-E2as1qE
                @Override // java.lang.Runnable
                public final void run() {
                    FaceService.AnonymousClass1.this.lambda$onLockoutChanged$6$FaceService$1(duration);
                }
            });
        }

        public /* synthetic */ void lambda$onLockoutChanged$6$FaceService$1(long duration) {
            if (duration == 0) {
                FaceService.this.notifyLockoutResetMonitors();
            }
        }
    }

    public FaceService(Context context) {
        super(context);
        this.mFaceConstants = new FaceConstants();
        this.mRevokeChallengePending = false;
        this.mDaemonCallback = new AnonymousClass1();
        this.mDaemonWrapper = new BiometricServiceBase.DaemonWrapper() { // from class: com.android.server.biometrics.face.FaceService.2
            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int authenticate(long operationId, int groupId) throws RemoteException {
                IBiometricsFace daemon = FaceService.this.getFaceDaemon();
                if (daemon == null) {
                    Slog.w("FaceService", "authenticate(): no face HAL!");
                    return 3;
                }
                return daemon.authenticate(operationId);
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int cancel() throws RemoteException {
                IBiometricsFace daemon = FaceService.this.getFaceDaemon();
                if (daemon == null) {
                    Slog.w("FaceService", "cancel(): no face HAL!");
                    return 3;
                }
                return daemon.cancel();
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int remove(int groupId, int biometricId) throws RemoteException {
                IBiometricsFace daemon = FaceService.this.getFaceDaemon();
                if (daemon == null) {
                    Slog.w("FaceService", "remove(): no face HAL!");
                    return 3;
                }
                return daemon.remove(biometricId);
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int enumerate() throws RemoteException {
                IBiometricsFace daemon = FaceService.this.getFaceDaemon();
                if (daemon == null) {
                    Slog.w("FaceService", "enumerate(): no face HAL!");
                    return 3;
                }
                return daemon.enumerate();
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public int enroll(byte[] cryptoToken, int groupId, int timeout, ArrayList<Integer> disabledFeatures) throws RemoteException {
                IBiometricsFace daemon = FaceService.this.getFaceDaemon();
                if (daemon == null) {
                    Slog.w("FaceService", "enroll(): no face HAL!");
                    return 3;
                }
                ArrayList<Byte> token = new ArrayList<>();
                for (byte b : cryptoToken) {
                    token.add(Byte.valueOf(b));
                }
                int i = daemon.enroll(token, timeout, disabledFeatures);
                return i;
            }

            @Override // com.android.server.biometrics.BiometricServiceBase.DaemonWrapper
            public void resetLockout(byte[] cryptoToken) throws RemoteException {
                IBiometricsFace daemon = FaceService.this.getFaceDaemon();
                if (daemon == null) {
                    Slog.w("FaceService", "resetLockout(): no face HAL!");
                    return;
                }
                ArrayList<Byte> token = new ArrayList<>();
                for (byte b : cryptoToken) {
                    token.add(Byte.valueOf(b));
                }
                daemon.resetLockout(token);
            }
        };
        this.mUsageStats = new UsageStats(context);
        this.mNotificationManager = (NotificationManager) getContext().getSystemService(NotificationManager.class);
        this.mBiometricPromptIgnoreList = getContext().getResources().getIntArray(17236031);
        this.mBiometricPromptIgnoreListVendor = getContext().getResources().getIntArray(17236034);
        this.mKeyguardIgnoreList = getContext().getResources().getIntArray(17236033);
        this.mKeyguardIgnoreListVendor = getContext().getResources().getIntArray(17236036);
        this.mEnrollIgnoreList = getContext().getResources().getIntArray(17236032);
        this.mEnrollIgnoreListVendor = getContext().getResources().getIntArray(17236035);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.biometrics.BiometricServiceBase
    public void removeClient(ClientMonitor client) {
        super.removeClient(client);
        if (this.mRevokeChallengePending) {
            startRevokeChallenge(null);
            this.mRevokeChallengePending = false;
        }
    }

    @Override // com.android.server.biometrics.BiometricServiceBase, com.android.server.SystemService
    public void onStart() {
        super.onStart();
        publishBinderService("face", new FaceServiceWrapper(this, null));
        SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$A0dfsVDvPu3BDJsON7widXUriSs
            @Override // java.lang.Runnable
            public final void run() {
                FaceService.this.lambda$onStart$0$FaceService();
            }
        }, "FaceService.onStart");
    }

    public /* synthetic */ void lambda$onStart$0$FaceService() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.face.-$$Lambda$FaceService$rveb67MoYJ0egfY6LL-l05KvUz8
            @Override // java.lang.Runnable
            public final void run() {
                FaceService.this.getFaceDaemon();
            }
        });
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    public String getTag() {
        return "FaceService";
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected BiometricServiceBase.DaemonWrapper getDaemonWrapper() {
        return this.mDaemonWrapper;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected BiometricUtils getBiometricUtils() {
        return FaceUtils.getInstance();
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected Constants getConstants() {
        return this.mFaceConstants;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean hasReachedEnrollmentLimit(int userId) {
        int limit = getContext().getResources().getInteger(17694811);
        int enrolled = getEnrolledTemplates(userId).size();
        if (enrolled >= limit) {
            Slog.w("FaceService", "Too many faces registered, user: " + userId);
            return true;
        }
        return false;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    public void serviceDied(long cookie) {
        super.serviceDied(cookie);
        this.mDaemon = null;
        this.mCurrentUserId = -10000;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void updateActiveGroup(int userId, String clientPackage) {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon != null) {
            try {
                int userId2 = getUserOrWorkProfileId(clientPackage, userId);
                if (userId2 != this.mCurrentUserId) {
                    File baseDir = Environment.getDataVendorDeDirectory(userId2);
                    File faceDir = new File(baseDir, FACE_DATA_DIR);
                    if (!faceDir.exists()) {
                        if (!faceDir.mkdir()) {
                            Slog.v("FaceService", "Cannot make directory: " + faceDir.getAbsolutePath());
                            return;
                        } else if (!SELinux.restorecon(faceDir)) {
                            Slog.w("FaceService", "Restorecons failed. Directory will have wrong label.");
                            return;
                        }
                    }
                    daemon.setActiveUser(userId2, faceDir.getAbsolutePath());
                    this.mCurrentUserId = userId2;
                    this.mAuthenticatorIds.put(Integer.valueOf(userId2), Long.valueOf(hasEnrolledBiometrics(userId2) ? daemon.getAuthenticatorId().value : 0L));
                }
            } catch (RemoteException e) {
                Slog.e("FaceService", "Failed to setActiveUser():", e);
            }
        }
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getLockoutResetIntent() {
        return ACTION_LOCKOUT_RESET;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getLockoutBroadcastPermission() {
        return "android.permission.RESET_FACE_LOCKOUT";
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected long getHalDeviceId() {
        return this.mHalDeviceId;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.biometrics.BiometricServiceBase
    public void handleUserSwitching(int userId) {
        super.handleUserSwitching(userId);
        this.mCurrentUserLockoutMode = 0;
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
        return "android.permission.MANAGE_BIOMETRIC";
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void checkUseBiometricPermission() {
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean checkAppOps(int uid, String opPackageName) {
        return this.mAppOps.noteOp(78, uid, opPackageName) == 0;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected List<Face> getEnrolledTemplates(int userId) {
        return getBiometricUtils().getBiometricsForUser(getContext(), userId);
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void notifyClientActiveCallbacks(boolean isActive) {
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected int statsModality() {
        return 4;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected int getLockoutMode() {
        return this.mCurrentUserLockoutMode;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized IBiometricsFace getFaceDaemon() {
        if (this.mDaemon == null) {
            Slog.v("FaceService", "mDaemon was null, reconnect to face");
            try {
                this.mDaemon = IBiometricsFace.getService();
            } catch (RemoteException e) {
                Slog.e("FaceService", "Failed to get biometric interface", e);
            } catch (NoSuchElementException e2) {
            }
            if (this.mDaemon == null) {
                Slog.w("FaceService", "face HIDL not available");
                return null;
            }
            this.mDaemon.asBinder().linkToDeath(this, 0L);
            try {
                this.mHalDeviceId = this.mDaemon.setCallback(this.mDaemonCallback).value;
            } catch (RemoteException e3) {
                Slog.e("FaceService", "Failed to open face HAL", e3);
                this.mDaemon = null;
            }
            Slog.v("FaceService", "Face HAL id: " + this.mHalDeviceId);
            if (this.mHalDeviceId != 0) {
                loadAuthenticatorIds();
                updateActiveGroup(ActivityManager.getCurrentUser(), null);
                doTemplateCleanupForUser(ActivityManager.getCurrentUser());
            } else {
                Slog.w("FaceService", "Failed to open Face HAL!");
                MetricsLogger.count(getContext(), "faced_openhal_error", 1);
                this.mDaemon = null;
            }
        }
        return this.mDaemon;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long startGenerateChallenge(IBinder token) {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w("FaceService", "startGenerateChallenge: no face HAL!");
            return 0L;
        }
        try {
            return daemon.generateChallenge(600).value;
        } catch (RemoteException e) {
            Slog.e("FaceService", "startGenerateChallenge failed", e);
            return 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int startRevokeChallenge(IBinder token) {
        IBiometricsFace daemon = getFaceDaemon();
        if (daemon == null) {
            Slog.w("FaceService", "startRevokeChallenge: no face HAL!");
            return 0;
        }
        try {
            int res = daemon.revokeChallenge();
            if (res != 0) {
                Slog.e("FaceService", "revokeChallenge returned " + res);
            }
            return res;
        } catch (RemoteException e) {
            Slog.e("FaceService", "startRevokeChallenge failed", e);
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpInternal(PrintWriter pw) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Face Manager");
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
            Slog.e("FaceService", "dump formatting failure", e);
        }
        pw.println(dump);
        pw.println("HAL deaths since last reboot: " + this.mHALDeathCount);
        this.mUsageStats.print(pw);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:25:0x0062 -> B:36:0x006c). Please submit an issue!!! */
    public void dumpHal(FileDescriptor fd, String[] args) {
        IBiometricsFace daemon;
        if ((Build.IS_ENG || Build.IS_USERDEBUG) && !SystemProperties.getBoolean("ro.face.disable_debug_data", false) && !SystemProperties.getBoolean("persist.face.disable_debug_data", false) && (daemon = getFaceDaemon()) != null) {
            FileOutputStream devnull = null;
            try {
                try {
                    devnull = new FileOutputStream("/dev/null");
                    NativeHandle handle = new NativeHandle(new FileDescriptor[]{devnull.getFD(), fd}, new int[0], false);
                    daemon.debug(handle, new ArrayList<>(Arrays.asList(args)));
                    devnull.close();
                } catch (RemoteException | IOException ex) {
                    Slog.d("FaceService", "error while reading face debugging data", ex);
                    if (devnull != null) {
                        devnull.close();
                    }
                }
            } catch (Throwable th) {
                if (devnull != null) {
                    try {
                        devnull.close();
                    } catch (IOException e) {
                    }
                }
                throw th;
            }
        }
    }
}
