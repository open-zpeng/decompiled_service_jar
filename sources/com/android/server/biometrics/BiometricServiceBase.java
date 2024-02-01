package com.android.server.biometrics;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.IActivityTaskManager;
import android.app.SynchronousUserSwitchObserver;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.fingerprint.Fingerprint;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.StatsLog;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.server.SystemService;
import com.android.server.slice.SliceClientPermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/* loaded from: classes.dex */
public abstract class BiometricServiceBase extends SystemService implements IHwBinder.DeathRecipient {
    private static final long CANCEL_TIMEOUT_LIMIT = 3000;
    private static final boolean CLEANUP_UNKNOWN_TEMPLATES = true;
    protected static final boolean DEBUG = true;
    private static final String KEY_LOCKOUT_RESET_USER = "lockout_reset_user";
    private static final int MSG_USER_SWITCHING = 10;
    private final IActivityTaskManager mActivityTaskManager;
    protected final AppOpsManager mAppOps;
    protected final Map<Integer, Long> mAuthenticatorIds;
    private IBiometricService mBiometricService;
    private final Context mContext;
    protected HashMap<Integer, PerformanceStats> mCryptoPerformanceMap;
    private ClientMonitor mCurrentClient;
    protected int mCurrentUserId;
    protected int mHALDeathCount;
    protected long mHalDeviceId;
    protected final H mHandler;
    protected boolean mIsCrypto;
    private final String mKeyguardPackage;
    private final ArrayList<LockoutResetMonitor> mLockoutMonitors;
    private final MetricsLogger mMetricsLogger;
    private final Runnable mOnTaskStackChangedRunnable;
    private ClientMonitor mPendingClient;
    protected HashMap<Integer, PerformanceStats> mPerformanceMap;
    private PerformanceStats mPerformanceStats;
    private final PowerManager mPowerManager;
    private final ResetClientStateRunnable mResetClientState;
    protected final IStatusBarService mStatusBarService;
    private final BiometricTaskStackListener mTaskStackListener;
    private final IBinder mToken;
    private final ArrayList<UserTemplate> mUnknownHALTemplates;
    private final UserManager mUserManager;

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public interface DaemonWrapper {
        public static final int ERROR_ESRCH = 3;

        int authenticate(long j, int i) throws RemoteException;

        int cancel() throws RemoteException;

        int enroll(byte[] bArr, int i, int i2, ArrayList<Integer> arrayList) throws RemoteException;

        int enumerate() throws RemoteException;

        int remove(int i, int i2) throws RemoteException;

        void resetLockout(byte[] bArr) throws RemoteException;
    }

    protected abstract boolean checkAppOps(int i, String str);

    protected abstract void checkUseBiometricPermission();

    protected abstract BiometricUtils getBiometricUtils();

    protected abstract Constants getConstants();

    protected abstract DaemonWrapper getDaemonWrapper();

    protected abstract List<? extends BiometricAuthenticator.Identifier> getEnrolledTemplates(int i);

    protected abstract long getHalDeviceId();

    protected abstract String getLockoutBroadcastPermission();

    protected abstract int getLockoutMode();

    protected abstract String getLockoutResetIntent();

    protected abstract String getManageBiometricPermission();

    protected abstract String getTag();

    protected abstract boolean hasEnrolledBiometrics(int i);

    protected abstract boolean hasReachedEnrollmentLimit(int i);

    protected abstract int statsModality();

    protected abstract void updateActiveGroup(int i, String str);

    /* loaded from: classes.dex */
    protected class PerformanceStats {
        public int accept;
        public int acquire;
        public int lockout;
        public int permanentLockout;
        public int reject;

        protected PerformanceStats() {
        }
    }

    protected void notifyClientActiveCallbacks(boolean isActive) {
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public abstract class AuthenticationClientImpl extends AuthenticationClient {
        protected boolean isFingerprint() {
            return false;
        }

        public AuthenticationClientImpl(Context context, DaemonWrapper daemon, long halDeviceId, IBinder token, ServiceListener listener, int targetUserId, int groupId, long opId, boolean restricted, String owner, int cookie, boolean requireConfirmation) {
            super(context, BiometricServiceBase.this.getConstants(), daemon, halDeviceId, token, listener, targetUserId, groupId, opId, restricted, owner, cookie, requireConfirmation);
        }

        @Override // com.android.server.biometrics.LoggableMonitor
        protected int statsClient() {
            if (BiometricServiceBase.this.isKeyguard(getOwnerString())) {
                return 1;
            }
            if (isBiometricPrompt()) {
                return 2;
            }
            if (isFingerprint()) {
                return 3;
            }
            return 0;
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public void onStart() {
            try {
                BiometricServiceBase.this.mActivityTaskManager.registerTaskStackListener(BiometricServiceBase.this.mTaskStackListener);
            } catch (RemoteException e) {
                Slog.e(BiometricServiceBase.this.getTag(), "Could not register task stack listener", e);
            }
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public void onStop() {
            try {
                BiometricServiceBase.this.mActivityTaskManager.unregisterTaskStackListener(BiometricServiceBase.this.mTaskStackListener);
            } catch (RemoteException e) {
                Slog.e(BiometricServiceBase.this.getTag(), "Could not unregister task stack listener", e);
            }
        }

        @Override // com.android.server.biometrics.ClientMonitor
        public void notifyUserActivity() {
            BiometricServiceBase.this.userActivity();
        }

        @Override // com.android.server.biometrics.AuthenticationClient
        public int handleFailedAttempt() {
            int lockoutMode = BiometricServiceBase.this.getLockoutMode();
            if (lockoutMode == 2) {
                BiometricServiceBase.this.mPerformanceStats.permanentLockout++;
            } else if (lockoutMode == 1) {
                BiometricServiceBase.this.mPerformanceStats.lockout++;
            }
            if (lockoutMode != 0) {
                return lockoutMode;
            }
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public abstract class EnrollClientImpl extends EnrollClient {
        public EnrollClientImpl(Context context, DaemonWrapper daemon, long halDeviceId, IBinder token, ServiceListener listener, int userId, int groupId, byte[] cryptoToken, boolean restricted, String owner, int[] disabledFeatures, int timeoutSec) {
            super(context, BiometricServiceBase.this.getConstants(), daemon, halDeviceId, token, listener, userId, groupId, cryptoToken, restricted, owner, BiometricServiceBase.this.getBiometricUtils(), disabledFeatures, timeoutSec);
        }

        @Override // com.android.server.biometrics.ClientMonitor
        public void notifyUserActivity() {
            BiometricServiceBase.this.userActivity();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InternalRemovalClient extends RemovalClient {
        InternalRemovalClient(Context context, DaemonWrapper daemon, long halDeviceId, IBinder token, ServiceListener listener, int templateId, int groupId, int userId, boolean restricted, String owner) {
            super(context, BiometricServiceBase.this.getConstants(), daemon, halDeviceId, token, listener, templateId, groupId, userId, restricted, owner, BiometricServiceBase.this.getBiometricUtils());
        }

        @Override // com.android.server.biometrics.LoggableMonitor
        protected int statsModality() {
            return BiometricServiceBase.this.statsModality();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InternalEnumerateClient extends EnumerateClient {
        private List<? extends BiometricAuthenticator.Identifier> mEnrolledList;
        private List<BiometricAuthenticator.Identifier> mUnknownHALTemplates;
        private BiometricUtils mUtils;

        InternalEnumerateClient(Context context, DaemonWrapper daemon, long halDeviceId, IBinder token, ServiceListener listener, int groupId, int userId, boolean restricted, String owner, List<? extends BiometricAuthenticator.Identifier> enrolledList, BiometricUtils utils) {
            super(context, BiometricServiceBase.this.getConstants(), daemon, halDeviceId, token, listener, groupId, userId, restricted, owner);
            this.mUnknownHALTemplates = new ArrayList();
            this.mEnrolledList = enrolledList;
            this.mUtils = utils;
        }

        private void handleEnumeratedTemplate(BiometricAuthenticator.Identifier identifier) {
            if (identifier == null) {
                return;
            }
            String tag = BiometricServiceBase.this.getTag();
            Slog.v(tag, "handleEnumeratedTemplate: " + identifier.getBiometricId());
            boolean matched = false;
            int i = 0;
            while (true) {
                if (i >= this.mEnrolledList.size()) {
                    break;
                } else if (this.mEnrolledList.get(i).getBiometricId() != identifier.getBiometricId()) {
                    i++;
                } else {
                    this.mEnrolledList.remove(i);
                    matched = true;
                    break;
                }
            }
            if (!matched && identifier.getBiometricId() != 0) {
                this.mUnknownHALTemplates.add(identifier);
            }
            String tag2 = BiometricServiceBase.this.getTag();
            Slog.v(tag2, "Matched: " + matched);
        }

        private void doTemplateCleanup() {
            if (this.mEnrolledList == null) {
                return;
            }
            for (int i = 0; i < this.mEnrolledList.size(); i++) {
                BiometricAuthenticator.Identifier identifier = this.mEnrolledList.get(i);
                String tag = BiometricServiceBase.this.getTag();
                Slog.e(tag, "doTemplateCleanup(): Removing dangling template from framework: " + identifier.getBiometricId() + " " + ((Object) identifier.getName()));
                this.mUtils.removeBiometricForUser(getContext(), getTargetUserId(), identifier.getBiometricId());
                StatsLog.write(148, statsModality(), 2);
            }
            this.mEnrolledList.clear();
        }

        public List<BiometricAuthenticator.Identifier> getUnknownHALTemplates() {
            return this.mUnknownHALTemplates;
        }

        @Override // com.android.server.biometrics.EnumerateClient, com.android.server.biometrics.ClientMonitor
        public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier, int remaining) {
            handleEnumeratedTemplate(identifier);
            if (remaining == 0) {
                doTemplateCleanup();
            }
            return remaining == 0;
        }

        @Override // com.android.server.biometrics.LoggableMonitor
        protected int statsModality() {
            return BiometricServiceBase.this.statsModality();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public interface ServiceListener {
        void onAcquired(long j, int i, int i2) throws RemoteException;

        void onError(long j, int i, int i2, int i3) throws RemoteException;

        default void onEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
        }

        default void onAuthenticationSucceeded(long deviceId, BiometricAuthenticator.Identifier biometric, int userId) throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        default void onAuthenticationSucceededInternal(boolean requireConfirmation, byte[] token) throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        default void onAuthenticationFailed(long deviceId) throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        default void onAuthenticationFailedInternal(int cookie, boolean requireConfirmation) throws RemoteException {
            throw new UnsupportedOperationException("Stub!");
        }

        default void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
        }

        default void onEnumerated(BiometricAuthenticator.Identifier identifier, int remaining) throws RemoteException {
        }
    }

    /* loaded from: classes.dex */
    protected abstract class BiometricServiceListener implements ServiceListener {
        private IBiometricServiceReceiverInternal mWrapperReceiver;

        public BiometricServiceListener(IBiometricServiceReceiverInternal wrapperReceiver) {
            this.mWrapperReceiver = wrapperReceiver;
        }

        public IBiometricServiceReceiverInternal getWrapperReceiver() {
            return this.mWrapperReceiver;
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAuthenticationSucceededInternal(boolean requireConfirmation, byte[] token) throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onAuthenticationSucceeded(requireConfirmation, token);
            }
        }

        @Override // com.android.server.biometrics.BiometricServiceBase.ServiceListener
        public void onAuthenticationFailedInternal(int cookie, boolean requireConfirmation) throws RemoteException {
            if (getWrapperReceiver() != null) {
                getWrapperReceiver().onAuthenticationFailed(cookie, requireConfirmation);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public final class H extends Handler {
        protected H() {
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 10) {
                BiometricServiceBase.this.handleUserSwitching(msg.arg1);
                return;
            }
            String tag = BiometricServiceBase.this.getTag();
            Slog.w(tag, "Unknown message:" + msg.what);
        }
    }

    /* loaded from: classes.dex */
    private final class BiometricTaskStackListener extends TaskStackListener {
        private BiometricTaskStackListener() {
        }

        public void onTaskStackChanged() {
            BiometricServiceBase.this.mHandler.post(BiometricServiceBase.this.mOnTaskStackChangedRunnable);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ResetClientStateRunnable implements Runnable {
        private ResetClientStateRunnable() {
        }

        @Override // java.lang.Runnable
        public void run() {
            String tag = BiometricServiceBase.this.getTag();
            StringBuilder sb = new StringBuilder();
            sb.append("Client ");
            sb.append(BiometricServiceBase.this.mCurrentClient != null ? BiometricServiceBase.this.mCurrentClient.getOwnerString() : "null");
            sb.append(" failed to respond to cancel, starting client ");
            sb.append(BiometricServiceBase.this.mPendingClient != null ? BiometricServiceBase.this.mPendingClient.getOwnerString() : "null");
            Slog.w(tag, sb.toString());
            StatsLog.write(148, BiometricServiceBase.this.statsModality(), 4);
            BiometricServiceBase.this.mCurrentClient = null;
            BiometricServiceBase biometricServiceBase = BiometricServiceBase.this;
            biometricServiceBase.startClient(biometricServiceBase.mPendingClient, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class LockoutResetMonitor implements IBinder.DeathRecipient {
        private static final long WAKELOCK_TIMEOUT_MS = 2000;
        private final IBiometricServiceLockoutResetCallback mCallback;
        private final Runnable mRemoveCallbackRunnable = new Runnable() { // from class: com.android.server.biometrics.BiometricServiceBase.LockoutResetMonitor.2
            @Override // java.lang.Runnable
            public void run() {
                LockoutResetMonitor.this.releaseWakelock();
                BiometricServiceBase.this.removeLockoutResetCallback(LockoutResetMonitor.this);
            }
        };
        private final PowerManager.WakeLock mWakeLock;

        public LockoutResetMonitor(IBiometricServiceLockoutResetCallback callback) {
            this.mCallback = callback;
            this.mWakeLock = BiometricServiceBase.this.mPowerManager.newWakeLock(1, "lockout reset callback");
            try {
                this.mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.w(BiometricServiceBase.this.getTag(), "caught remote exception in linkToDeath", e);
            }
        }

        public void sendLockoutReset() {
            if (this.mCallback != null) {
                try {
                    this.mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    this.mCallback.onLockoutReset(BiometricServiceBase.this.getHalDeviceId(), new IRemoteCallback.Stub() { // from class: com.android.server.biometrics.BiometricServiceBase.LockoutResetMonitor.1
                        public void sendResult(Bundle data) throws RemoteException {
                            LockoutResetMonitor.this.releaseWakelock();
                        }
                    });
                } catch (DeadObjectException e) {
                    Slog.w(BiometricServiceBase.this.getTag(), "Death object while invoking onLockoutReset: ", e);
                    BiometricServiceBase.this.mHandler.post(this.mRemoveCallbackRunnable);
                } catch (RemoteException e2) {
                    Slog.w(BiometricServiceBase.this.getTag(), "Failed to invoke onLockoutReset: ", e2);
                    releaseWakelock();
                }
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Slog.e(BiometricServiceBase.this.getTag(), "Lockout reset callback binder died");
            BiometricServiceBase.this.mHandler.post(this.mRemoveCallbackRunnable);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void releaseWakelock() {
            if (this.mWakeLock.isHeld()) {
                this.mWakeLock.release();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class UserTemplate {
        final BiometricAuthenticator.Identifier mIdentifier;
        final int mUserId;

        UserTemplate(BiometricAuthenticator.Identifier identifier, int userId) {
            this.mIdentifier = identifier;
            this.mUserId = userId;
        }
    }

    public BiometricServiceBase(Context context) {
        super(context);
        this.mTaskStackListener = new BiometricTaskStackListener();
        this.mResetClientState = new ResetClientStateRunnable();
        this.mLockoutMonitors = new ArrayList<>();
        this.mAuthenticatorIds = Collections.synchronizedMap(new HashMap());
        this.mHandler = new H();
        this.mToken = new Binder();
        this.mUnknownHALTemplates = new ArrayList<>();
        this.mCurrentUserId = -10000;
        this.mPerformanceMap = new HashMap<>();
        this.mCryptoPerformanceMap = new HashMap<>();
        this.mOnTaskStackChangedRunnable = new Runnable() { // from class: com.android.server.biometrics.BiometricServiceBase.1
            @Override // java.lang.Runnable
            public void run() {
                try {
                    if (BiometricServiceBase.this.mCurrentClient instanceof AuthenticationClient) {
                        String currentClient = BiometricServiceBase.this.mCurrentClient.getOwnerString();
                        if (!BiometricServiceBase.this.isKeyguard(currentClient)) {
                            List<ActivityManager.RunningTaskInfo> runningTasks = BiometricServiceBase.this.mActivityTaskManager.getTasks(1);
                            if (!runningTasks.isEmpty()) {
                                String topPackage = runningTasks.get(0).topActivity.getPackageName();
                                if (!topPackage.contentEquals(currentClient) && !BiometricServiceBase.this.mCurrentClient.isAlreadyDone()) {
                                    String tag = BiometricServiceBase.this.getTag();
                                    Slog.e(tag, "Stopping background authentication, top: " + topPackage + " currentClient: " + currentClient);
                                    BiometricServiceBase.this.mCurrentClient.stop(false);
                                }
                            }
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(BiometricServiceBase.this.getTag(), "Unable to get running tasks", e);
                }
            }
        };
        this.mContext = context;
        this.mStatusBarService = IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar"));
        this.mKeyguardPackage = ComponentName.unflattenFromString(context.getResources().getString(17039753)).getPackageName();
        this.mAppOps = (AppOpsManager) context.getSystemService(AppOpsManager.class);
        ActivityTaskManager activityTaskManager = (ActivityTaskManager) context.getSystemService("activity_task");
        this.mActivityTaskManager = ActivityTaskManager.getService();
        this.mPowerManager = (PowerManager) this.mContext.getSystemService(PowerManager.class);
        this.mUserManager = UserManager.get(this.mContext);
        this.mMetricsLogger = new MetricsLogger();
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        listenForUserSwitches();
    }

    public void serviceDied(long cookie) {
        Slog.e(getTag(), "HAL died");
        this.mMetricsLogger.count(getConstants().tagHalDied(), 1);
        this.mHALDeathCount++;
        this.mCurrentUserId = -10000;
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$iRNlDOJhMpMFOTQxuHjuZ0z5dlY
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$serviceDied$0$BiometricServiceBase();
            }
        });
        StatsLog.write(148, statsModality(), 1);
    }

    public /* synthetic */ void lambda$serviceDied$0$BiometricServiceBase() {
        handleError(getHalDeviceId(), 1, 0);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public ClientMonitor getCurrentClient() {
        return this.mCurrentClient;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public ClientMonitor getPendingClient() {
        return this.mPendingClient;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void handleAcquired(long deviceId, int acquiredInfo, int vendorCode) {
        ClientMonitor client = this.mCurrentClient;
        if (client != null && client.onAcquired(acquiredInfo, vendorCode)) {
            removeClient(client);
        }
        if (this.mPerformanceStats != null && getLockoutMode() == 0 && (client instanceof AuthenticationClient)) {
            this.mPerformanceStats.acquire++;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void handleAuthenticated(BiometricAuthenticator.Identifier identifier, ArrayList<Byte> token) {
        ClientMonitor client = this.mCurrentClient;
        boolean authenticated = identifier.getBiometricId() != 0;
        if (client != null && client.onAuthenticated(identifier, authenticated, token)) {
            removeClient(client);
        }
        if (authenticated) {
            this.mPerformanceStats.accept++;
            return;
        }
        this.mPerformanceStats.reject++;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void handleEnrollResult(BiometricAuthenticator.Identifier identifier, int remaining) {
        ClientMonitor client = this.mCurrentClient;
        if (client != null && client.onEnrollResult(identifier, remaining)) {
            removeClient(client);
            if (identifier instanceof Fingerprint) {
                updateActiveGroup(((Fingerprint) identifier).getGroupId(), null);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void handleError(long deviceId, int error, int vendorCode) {
        ClientMonitor client = this.mCurrentClient;
        String tag = getTag();
        StringBuilder sb = new StringBuilder();
        sb.append("handleError(client=");
        sb.append(client != null ? client.getOwnerString() : "null");
        sb.append(", error = ");
        sb.append(error);
        sb.append(")");
        Slog.v(tag, sb.toString());
        if ((client instanceof InternalRemovalClient) || (client instanceof InternalEnumerateClient)) {
            clearEnumerateState();
        }
        if (client != null && client.onError(deviceId, error, vendorCode)) {
            removeClient(client);
        }
        if (error == 5) {
            this.mHandler.removeCallbacks(this.mResetClientState);
            if (this.mPendingClient != null) {
                String tag2 = getTag();
                Slog.v(tag2, "start pending client " + this.mPendingClient.getOwnerString());
                startClient(this.mPendingClient, false);
                this.mPendingClient = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void handleRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        String tag = getTag();
        Slog.w(tag, "Removed: fid=" + identifier.getBiometricId() + ", dev=" + identifier.getDeviceId() + ", rem=" + remaining);
        ClientMonitor client = this.mCurrentClient;
        if (client != null && client.onRemoved(identifier, remaining)) {
            removeClient(client);
            int userId = this.mCurrentUserId;
            if (identifier instanceof Fingerprint) {
                userId = ((Fingerprint) identifier).getGroupId();
            }
            if (!hasEnrolledBiometrics(userId)) {
                updateActiveGroup(userId, null);
            }
        }
        if ((client instanceof InternalRemovalClient) && !this.mUnknownHALTemplates.isEmpty()) {
            startCleanupUnknownHALTemplates();
        } else if (client instanceof InternalRemovalClient) {
            clearEnumerateState();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void handleEnumerate(BiometricAuthenticator.Identifier identifier, int remaining) {
        ClientMonitor client = getCurrentClient();
        client.onEnumerationResult(identifier, remaining);
        if (remaining == 0) {
            if (client instanceof InternalEnumerateClient) {
                List<BiometricAuthenticator.Identifier> unknownHALTemplates = ((InternalEnumerateClient) client).getUnknownHALTemplates();
                if (!unknownHALTemplates.isEmpty()) {
                    String tag = getTag();
                    Slog.w(tag, "Adding " + unknownHALTemplates.size() + " templates for deletion");
                }
                for (int i = 0; i < unknownHALTemplates.size(); i++) {
                    this.mUnknownHALTemplates.add(new UserTemplate(unknownHALTemplates.get(i), client.getTargetUserId()));
                }
                removeClient(client);
                startCleanupUnknownHALTemplates();
                return;
            }
            removeClient(client);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void enrollInternal(final EnrollClientImpl client, int userId) {
        if (hasReachedEnrollmentLimit(userId) || !isCurrentUserOrProfile(userId)) {
            return;
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$Zy4OXo3HMpNNxU1x5VMDe_5Q3vI
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$enrollInternal$1$BiometricServiceBase(client);
            }
        });
    }

    public /* synthetic */ void lambda$enrollInternal$1$BiometricServiceBase(EnrollClientImpl client) {
        startClient(client, true);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void cancelEnrollmentInternal(final IBinder token) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$yj0NG4umGnnyUerNM_EKxeka05A
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$cancelEnrollmentInternal$2$BiometricServiceBase(token);
            }
        });
    }

    public /* synthetic */ void lambda$cancelEnrollmentInternal$2$BiometricServiceBase(IBinder token) {
        ClientMonitor client = this.mCurrentClient;
        if ((client instanceof EnrollClient) && client.getToken() == token) {
            Slog.v(getTag(), "Cancelling enrollment");
            client.stop(client.getToken() == token);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void authenticateInternal(AuthenticationClientImpl client, long opId, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        int callingUserId = UserHandle.getCallingUserId();
        authenticateInternal(client, opId, opPackageName, callingUid, callingPid, callingUserId);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void authenticateInternal(final AuthenticationClientImpl client, final long opId, final String opPackageName, int callingUid, int callingPid, int callingUserId) {
        if (!canUseBiometric(opPackageName, true, callingUid, callingPid, callingUserId)) {
            String tag = getTag();
            Slog.v(tag, "authenticate(): reject " + opPackageName);
            return;
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$VFT8WmkESkAnonaxJDq_GS_vB4E
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$authenticateInternal$3$BiometricServiceBase(opId, client, opPackageName);
            }
        });
    }

    public /* synthetic */ void lambda$authenticateInternal$3$BiometricServiceBase(long opId, AuthenticationClientImpl client, String opPackageName) {
        this.mMetricsLogger.histogram(getConstants().tagAuthToken(), opId != 0 ? 1 : 0);
        HashMap<Integer, PerformanceStats> pmap = opId == 0 ? this.mPerformanceMap : this.mCryptoPerformanceMap;
        PerformanceStats stats = pmap.get(Integer.valueOf(this.mCurrentUserId));
        if (stats == null) {
            stats = new PerformanceStats();
            pmap.put(Integer.valueOf(this.mCurrentUserId), stats);
        }
        this.mPerformanceStats = stats;
        this.mIsCrypto = opId != 0;
        startAuthentication(client, opPackageName);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void cancelAuthenticationInternal(IBinder token, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        int callingUserId = UserHandle.getCallingUserId();
        cancelAuthenticationInternal(token, opPackageName, callingUid, callingPid, callingUserId, true);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void cancelAuthenticationInternal(final IBinder token, String opPackageName, int callingUid, int callingPid, int callingUserId, final boolean fromClient) {
        if (fromClient && !canUseBiometric(opPackageName, true, callingUid, callingPid, callingUserId)) {
            String tag = getTag();
            Slog.v(tag, "cancelAuthentication(): reject " + opPackageName);
            return;
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$B1PDNz5plOtQUbeZgXMkI_dh_yQ
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$cancelAuthenticationInternal$4$BiometricServiceBase(token, fromClient);
            }
        });
    }

    public /* synthetic */ void lambda$cancelAuthenticationInternal$4$BiometricServiceBase(IBinder token, boolean fromClient) {
        ClientMonitor client = this.mCurrentClient;
        if (client instanceof AuthenticationClient) {
            if (client.getToken() == token || !fromClient) {
                String tag = getTag();
                Slog.v(tag, "Stopping client " + client.getOwnerString() + ", fromClient: " + fromClient);
                client.stop(client.getToken() == token);
                return;
            }
            String tag2 = getTag();
            Slog.v(tag2, "Can't stop client " + client.getOwnerString() + " since tokens don't match. fromClient: " + fromClient);
        } else if (client != null) {
            String tag3 = getTag();
            Slog.v(tag3, "Can't cancel non-authenticating client " + client.getOwnerString());
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setActiveUserInternal(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$rf3hjPI_nf4EvVsQV7gFCF1-HpI
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$setActiveUserInternal$5$BiometricServiceBase(userId);
            }
        });
    }

    public /* synthetic */ void lambda$setActiveUserInternal$5$BiometricServiceBase(int userId) {
        String tag = getTag();
        Slog.d(tag, "setActiveUser(" + userId + ")");
        updateActiveGroup(userId, null);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void removeInternal(final RemovalClient client) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$8-hCNL3jMZVMKItY0KyN7TBk6u8
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$removeInternal$6$BiometricServiceBase(client);
            }
        });
    }

    public /* synthetic */ void lambda$removeInternal$6$BiometricServiceBase(RemovalClient client) {
        startClient(client, true);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void enumerateInternal(final EnumerateClient client) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$lM-Gght_XjLuQG2iY0xHchO8Xgk
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$enumerateInternal$7$BiometricServiceBase(client);
            }
        });
    }

    public /* synthetic */ void lambda$enumerateInternal$7$BiometricServiceBase(EnumerateClient client) {
        startClient(client, true);
    }

    private void startAuthentication(AuthenticationClientImpl client, String opPackageName) {
        int errorCode;
        String tag = getTag();
        Slog.v(tag, "startAuthentication(" + opPackageName + ")");
        int lockoutMode = getLockoutMode();
        if (lockoutMode != 0) {
            String tag2 = getTag();
            Slog.v(tag2, "In lockout mode(" + lockoutMode + ") ; disallowing authentication");
            if (lockoutMode == 1) {
                errorCode = 7;
            } else {
                errorCode = 9;
            }
            if (!client.onError(getHalDeviceId(), errorCode, 0)) {
                Slog.w(getTag(), "Cannot send permanent lockout message to client");
                return;
            }
            return;
        }
        startClient(client, true);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void addLockoutResetCallback(final IBiometricServiceLockoutResetCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.biometrics.-$$Lambda$BiometricServiceBase$5zE_f-JKSpUWsfwvdtw36YktZZ0
            @Override // java.lang.Runnable
            public final void run() {
                BiometricServiceBase.this.lambda$addLockoutResetCallback$8$BiometricServiceBase(callback);
            }
        });
    }

    public /* synthetic */ void lambda$addLockoutResetCallback$8$BiometricServiceBase(IBiometricServiceLockoutResetCallback callback) {
        LockoutResetMonitor monitor = new LockoutResetMonitor(callback);
        if (!this.mLockoutMonitors.contains(monitor)) {
            this.mLockoutMonitors.add(monitor);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean canUseBiometric(String opPackageName, boolean requireForeground, int uid, int pid, int userId) {
        checkUseBiometricPermission();
        if (Binder.getCallingUid() == 1000 || isKeyguard(opPackageName)) {
            return true;
        }
        if (!isCurrentUserOrProfile(userId)) {
            String tag = getTag();
            Slog.w(tag, "Rejecting " + opPackageName + "; not a current user or profile");
            return false;
        } else if (!checkAppOps(uid, opPackageName)) {
            String tag2 = getTag();
            Slog.w(tag2, "Rejecting " + opPackageName + "; permission denied");
            return false;
        } else if (!requireForeground || isForegroundActivity(uid, pid) || isCurrentClient(opPackageName)) {
            return true;
        } else {
            String tag3 = getTag();
            Slog.w(tag3, "Rejecting " + opPackageName + "; not in foreground");
            return false;
        }
    }

    private boolean isCurrentClient(String opPackageName) {
        ClientMonitor clientMonitor = this.mCurrentClient;
        return clientMonitor != null && clientMonitor.getOwnerString().equals(opPackageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isKeyguard(String clientPackage) {
        return this.mKeyguardPackage.equals(clientPackage);
    }

    private boolean isForegroundActivity(int uid, int pid) {
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = ActivityManager.getService().getRunningAppProcesses();
            int N = procs.size();
            for (int i = 0; i < N; i++) {
                ActivityManager.RunningAppProcessInfo proc = procs.get(i);
                if (proc.pid == pid && proc.uid == uid && proc.importance <= 125) {
                    return true;
                }
            }
            return false;
        } catch (RemoteException e) {
            Slog.w(getTag(), "am.getRunningAppProcesses() failed");
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startClient(ClientMonitor newClient, boolean initiatedByClient) {
        ClientMonitor currentClient = this.mCurrentClient;
        if (currentClient != null) {
            String tag = getTag();
            Slog.v(tag, "request stop current client " + currentClient.getOwnerString());
            if ((currentClient instanceof InternalEnumerateClient) || (currentClient instanceof InternalRemovalClient)) {
                if (newClient != null) {
                    String tag2 = getTag();
                    Slog.w(tag2, "Internal cleanup in progress but trying to start client " + newClient.getClass().getSuperclass().getSimpleName() + "(" + newClient.getOwnerString() + "), initiatedByClient = " + initiatedByClient);
                }
            } else {
                currentClient.stop(initiatedByClient);
                this.mHandler.removeCallbacks(this.mResetClientState);
                this.mHandler.postDelayed(this.mResetClientState, 3000L);
            }
            this.mPendingClient = newClient;
        } else if (newClient != null) {
            if (newClient instanceof AuthenticationClient) {
                AuthenticationClient client = (AuthenticationClient) newClient;
                if (client.isBiometricPrompt()) {
                    String tag3 = getTag();
                    Slog.v(tag3, "Returning cookie: " + client.getCookie());
                    this.mCurrentClient = newClient;
                    if (this.mBiometricService == null) {
                        this.mBiometricService = IBiometricService.Stub.asInterface(ServiceManager.getService("biometric"));
                    }
                    try {
                        this.mBiometricService.onReadyForAuthentication(client.getCookie(), client.getRequireConfirmation(), client.getTargetUserId());
                        return;
                    } catch (RemoteException e) {
                        Slog.e(getTag(), "Remote exception", e);
                        return;
                    }
                }
            }
            this.mCurrentClient = newClient;
            startCurrentClient(this.mCurrentClient.getCookie());
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void startCurrentClient(int cookie) {
        if (this.mCurrentClient == null) {
            Slog.e(getTag(), "Trying to start null client!");
            return;
        }
        String tag = getTag();
        Slog.v(tag, "starting client " + this.mCurrentClient.getClass().getSuperclass().getSimpleName() + "(" + this.mCurrentClient.getOwnerString() + ") targetUserId: " + this.mCurrentClient.getTargetUserId() + " currentUserId: " + this.mCurrentUserId + " cookie: " + cookie + SliceClientPermissions.SliceAuthority.DELIMITER + this.mCurrentClient.getCookie());
        if (cookie != this.mCurrentClient.getCookie()) {
            Slog.e(getTag(), "Mismatched cookie");
            return;
        }
        notifyClientActiveCallbacks(true);
        this.mCurrentClient.start();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void removeClient(ClientMonitor client) {
        if (client != null) {
            client.destroy();
            ClientMonitor clientMonitor = this.mCurrentClient;
            if (client != clientMonitor && clientMonitor != null) {
                String tag = getTag();
                Slog.w(tag, "Unexpected client: " + client.getOwnerString() + "expected: " + this.mCurrentClient.getOwnerString());
            }
        }
        if (this.mCurrentClient != null) {
            String tag2 = getTag();
            Slog.v(tag2, "Done with client: " + client.getOwnerString());
            this.mCurrentClient = null;
        }
        if (this.mPendingClient == null) {
            notifyClientActiveCallbacks(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void loadAuthenticatorIds() {
        long t = System.currentTimeMillis();
        this.mAuthenticatorIds.clear();
        for (UserInfo user : UserManager.get(getContext()).getUsers(true)) {
            int userId = getUserOrWorkProfileId(null, user.id);
            if (!this.mAuthenticatorIds.containsKey(Integer.valueOf(userId))) {
                updateActiveGroup(userId, null);
            }
        }
        long t2 = System.currentTimeMillis() - t;
        if (t2 > 1000) {
            String tag = getTag();
            Slog.w(tag, "loadAuthenticatorIds() taking too long: " + t2 + "ms");
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public int getUserOrWorkProfileId(String clientPackage, int userId) {
        if (!isKeyguard(clientPackage) && isWorkProfile(userId)) {
            return userId;
        }
        return getEffectiveUserId(userId);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean isRestricted() {
        boolean restricted = !hasPermission(getManageBiometricPermission());
        return restricted;
    }

    protected boolean hasPermission(String permission) {
        return getContext().checkCallingOrSelfPermission(permission) == 0;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void checkPermission(String permission) {
        Context context = getContext();
        context.enforceCallingOrSelfPermission(permission, "Must have " + permission + " permission.");
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean isCurrentUserOrProfile(int userId) {
        int[] enabledProfileIds;
        UserManager um = UserManager.get(this.mContext);
        if (um == null) {
            Slog.e(getTag(), "Unable to acquire UserManager");
            return false;
        }
        long token = Binder.clearCallingIdentity();
        try {
            for (int profileId : um.getEnabledProfileIds(ActivityManager.getCurrentUser())) {
                if (profileId == userId) {
                    Binder.restoreCallingIdentity(token);
                    return true;
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public long getAuthenticatorId(String opPackageName) {
        int userId = getUserOrWorkProfileId(opPackageName, UserHandle.getCallingUserId());
        return this.mAuthenticatorIds.getOrDefault(Integer.valueOf(userId), 0L).longValue();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void doTemplateCleanupForUser(int userId) {
        enumerateUser(userId);
    }

    private void clearEnumerateState() {
        Slog.v(getTag(), "clearEnumerateState()");
        this.mUnknownHALTemplates.clear();
    }

    private void startCleanupUnknownHALTemplates() {
        if (!this.mUnknownHALTemplates.isEmpty()) {
            UserTemplate template = this.mUnknownHALTemplates.get(0);
            this.mUnknownHALTemplates.remove(template);
            boolean restricted = !hasPermission(getManageBiometricPermission());
            InternalRemovalClient client = new InternalRemovalClient(getContext(), getDaemonWrapper(), this.mHalDeviceId, this.mToken, null, template.mIdentifier.getBiometricId(), 0, template.mUserId, restricted, getContext().getPackageName());
            removeInternal(client);
            StatsLog.write(148, statsModality(), 3);
            return;
        }
        clearEnumerateState();
        if (this.mPendingClient != null) {
            Slog.d(getTag(), "Enumerate finished, starting pending client");
            startClient(this.mPendingClient, false);
            this.mPendingClient = null;
        }
    }

    private void enumerateUser(int userId) {
        String tag = getTag();
        Slog.v(tag, "Enumerating user(" + userId + ")");
        boolean restricted = hasPermission(getManageBiometricPermission()) ^ true;
        List<? extends BiometricAuthenticator.Identifier> enrolledList = getEnrolledTemplates(userId);
        InternalEnumerateClient client = new InternalEnumerateClient(getContext(), getDaemonWrapper(), this.mHalDeviceId, this.mToken, null, userId, userId, restricted, getContext().getOpPackageName(), enrolledList, getBiometricUtils());
        enumerateInternal(client);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void handleUserSwitching(int userId) {
        if ((getCurrentClient() instanceof InternalRemovalClient) || (getCurrentClient() instanceof InternalEnumerateClient)) {
            Slog.w(getTag(), "User switched while performing cleanup");
        }
        updateActiveGroup(userId, null);
        doTemplateCleanupForUser(userId);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void notifyLockoutResetMonitors() {
        for (int i = 0; i < this.mLockoutMonitors.size(); i++) {
            this.mLockoutMonitors.get(i).sendLockoutReset();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void userActivity() {
        long now = SystemClock.uptimeMillis();
        this.mPowerManager.userActivity(now, 2, 0);
    }

    private boolean isWorkProfile(int userId) {
        long token = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = this.mUserManager.getUserInfo(userId);
            return userInfo != null && userInfo.isManagedProfile();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private int getEffectiveUserId(int userId) {
        UserManager um = UserManager.get(this.mContext);
        if (um != null) {
            long callingIdentity = Binder.clearCallingIdentity();
            int userId2 = um.getCredentialOwnerProfile(userId);
            Binder.restoreCallingIdentity(callingIdentity);
            return userId2;
        }
        Slog.e(getTag(), "Unable to acquire UserManager");
        return userId;
    }

    private void listenForUserSwitches() {
        try {
            ActivityManager.getService().registerUserSwitchObserver(new SynchronousUserSwitchObserver() { // from class: com.android.server.biometrics.BiometricServiceBase.2
                public void onUserSwitching(int newUserId) throws RemoteException {
                    BiometricServiceBase.this.mHandler.obtainMessage(10, newUserId, 0).sendToTarget();
                }
            }, getTag());
        } catch (RemoteException e) {
            Slog.w(getTag(), "Failed to listen for user switching event", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeLockoutResetCallback(LockoutResetMonitor monitor) {
        this.mLockoutMonitors.remove(monitor);
    }
}
