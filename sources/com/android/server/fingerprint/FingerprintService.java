package com.android.server.fingerprint;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.SynchronousUserSwitchObserver;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.hardware.biometrics.IBiometricPromptReceiver;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceLockoutResetCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.KeyStore;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.am.AssistDataRequester;
import com.android.server.utils.PriorityDump;
import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class FingerprintService extends SystemService implements IHwBinder.DeathRecipient {
    private static final String ACTION_LOCKOUT_RESET = "com.android.server.fingerprint.ACTION_LOCKOUT_RESET";
    private static final long CANCEL_TIMEOUT_LIMIT = 3000;
    private static final boolean CLEANUP_UNUSED_FP = true;
    static final boolean DEBUG = true;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30000;
    private static final String FP_DATA_DIR = "fpdata";
    private static final String KEY_LOCKOUT_RESET_USER = "lockout_reset_user";
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 20;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5;
    private static final int MSG_USER_SWITCHING = 10;
    static final String TAG = "FingerprintService";
    private final IActivityManager mActivityManager;
    private final AlarmManager mAlarmManager;
    private final AppOpsManager mAppOps;
    private final Map<Integer, Long> mAuthenticatorIds;
    private final CopyOnWriteArrayList<IFingerprintClientActiveCallback> mClientActiveCallbacks;
    private Context mContext;
    private HashMap<Integer, PerformanceStats> mCryptoPerformanceMap;
    private ClientMonitor mCurrentClient;
    private int mCurrentUserId;
    @GuardedBy("this")
    private IBiometricsFingerprint mDaemon;
    private IBiometricsFingerprintClientCallback mDaemonCallback;
    private SparseIntArray mFailedAttempts;
    private final FingerprintUtils mFingerprintUtils;
    private long mHalDeviceId;
    private Handler mHandler;
    private final String mKeyguardPackage;
    private final ArrayList<FingerprintServiceLockoutResetMonitor> mLockoutMonitors;
    private final BroadcastReceiver mLockoutReceiver;
    private ClientMonitor mPendingClient;
    private HashMap<Integer, PerformanceStats> mPerformanceMap;
    private PerformanceStats mPerformanceStats;
    private final PowerManager mPowerManager;
    private final Runnable mResetClientState;
    private final Runnable mResetFailedAttemptsForCurrentUserRunnable;
    private IStatusBarService mStatusBarService;
    private final TaskStackListener mTaskStackListener;
    private SparseBooleanArray mTimedLockoutCleared;
    private IBinder mToken;
    private ArrayList<UserFingerprint> mUnknownFingerprints;
    private final UserManager mUserManager;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PerformanceStats {
        int accept;
        int acquire;
        int lockout;
        int permanentLockout;
        int reject;

        private PerformanceStats() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UserFingerprint {
        Fingerprint f;
        int userId;

        public UserFingerprint(Fingerprint f, int userId) {
            this.f = f;
            this.userId = userId;
        }
    }

    public FingerprintService(Context context) {
        super(context);
        this.mLockoutMonitors = new ArrayList<>();
        this.mClientActiveCallbacks = new CopyOnWriteArrayList<>();
        this.mAuthenticatorIds = Collections.synchronizedMap(new HashMap());
        this.mCurrentUserId = -10000;
        this.mFingerprintUtils = FingerprintUtils.getInstance();
        this.mToken = new Binder();
        this.mUnknownFingerprints = new ArrayList<>();
        this.mPerformanceMap = new HashMap<>();
        this.mCryptoPerformanceMap = new HashMap<>();
        this.mHandler = new Handler() { // from class: com.android.server.fingerprint.FingerprintService.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                if (msg.what == 10) {
                    FingerprintService.this.handleUserSwitching(msg.arg1);
                    return;
                }
                Slog.w(FingerprintService.TAG, "Unknown message:" + msg.what);
            }
        };
        this.mLockoutReceiver = new BroadcastReceiver() { // from class: com.android.server.fingerprint.FingerprintService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if (FingerprintService.ACTION_LOCKOUT_RESET.equals(intent.getAction())) {
                    int user = intent.getIntExtra(FingerprintService.KEY_LOCKOUT_RESET_USER, 0);
                    FingerprintService.this.resetFailedAttemptsForUser(false, user);
                }
            }
        };
        this.mResetFailedAttemptsForCurrentUserRunnable = new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.3
            @Override // java.lang.Runnable
            public void run() {
                FingerprintService.this.resetFailedAttemptsForUser(true, ActivityManager.getCurrentUser());
            }
        };
        this.mResetClientState = new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.4
            @Override // java.lang.Runnable
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("Client ");
                sb.append(FingerprintService.this.mCurrentClient != null ? FingerprintService.this.mCurrentClient.getOwnerString() : "null");
                sb.append(" failed to respond to cancel, starting client ");
                sb.append(FingerprintService.this.mPendingClient != null ? FingerprintService.this.mPendingClient.getOwnerString() : "null");
                Slog.w(FingerprintService.TAG, sb.toString());
                FingerprintService.this.mCurrentClient = null;
                FingerprintService.this.startClient(FingerprintService.this.mPendingClient, false);
            }
        };
        this.mTaskStackListener = new TaskStackListener() { // from class: com.android.server.fingerprint.FingerprintService.5
            public void onTaskStackChanged() {
                try {
                    if (FingerprintService.this.mCurrentClient instanceof AuthenticationClient) {
                        String currentClient = FingerprintService.this.mCurrentClient.getOwnerString();
                        if (!FingerprintService.this.isKeyguard(currentClient)) {
                            List<ActivityManager.RunningTaskInfo> runningTasks = FingerprintService.this.mActivityManager.getTasks(1);
                            if (!runningTasks.isEmpty()) {
                                String topPackage = runningTasks.get(0).topActivity.getPackageName();
                                if (!topPackage.contentEquals(currentClient)) {
                                    Slog.e(FingerprintService.TAG, "Stopping background authentication, top: " + topPackage + " currentClient: " + currentClient);
                                    FingerprintService.this.mCurrentClient.stop(false);
                                }
                            }
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(FingerprintService.TAG, "Unable to get running tasks", e);
                }
            }
        };
        this.mDaemonCallback = new IBiometricsFingerprintClientCallback.Stub() { // from class: com.android.server.fingerprint.FingerprintService.12
            @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
            public void onEnrollResult(final long deviceId, final int fingerId, final int groupId, final int remaining) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.12.1
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.handleEnrollResult(deviceId, fingerId, groupId, remaining);
                    }
                });
            }

            @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
            public void onAcquired(final long deviceId, final int acquiredInfo, final int vendorCode) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.12.2
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.handleAcquired(deviceId, acquiredInfo, vendorCode);
                    }
                });
            }

            @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
            public void onAuthenticated(final long deviceId, final int fingerId, final int groupId, final ArrayList<Byte> token) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.12.3
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.handleAuthenticated(deviceId, fingerId, groupId, token);
                    }
                });
            }

            @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
            public void onError(final long deviceId, final int error, final int vendorCode) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.12.4
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.handleError(deviceId, error, vendorCode);
                    }
                });
            }

            @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
            public void onRemoved(final long deviceId, final int fingerId, final int groupId, final int remaining) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.12.5
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.handleRemoved(deviceId, fingerId, groupId, remaining);
                    }
                });
            }

            @Override // android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprintClientCallback
            public void onEnumerate(final long deviceId, final int fingerId, final int groupId, final int remaining) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.12.6
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.handleEnumerate(deviceId, fingerId, groupId, remaining);
                    }
                });
            }
        };
        this.mContext = context;
        this.mKeyguardPackage = ComponentName.unflattenFromString(context.getResources().getString(17039697)).getPackageName();
        this.mAppOps = (AppOpsManager) context.getSystemService(AppOpsManager.class);
        this.mPowerManager = (PowerManager) this.mContext.getSystemService(PowerManager.class);
        this.mAlarmManager = (AlarmManager) this.mContext.getSystemService(AlarmManager.class);
        this.mContext.registerReceiver(this.mLockoutReceiver, new IntentFilter(ACTION_LOCKOUT_RESET), "android.permission.RESET_FINGERPRINT_LOCKOUT", null);
        this.mUserManager = UserManager.get(this.mContext);
        this.mTimedLockoutCleared = new SparseBooleanArray();
        this.mFailedAttempts = new SparseIntArray();
        this.mStatusBarService = IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar"));
        ActivityManager activityManager = (ActivityManager) context.getSystemService("activity");
        this.mActivityManager = ActivityManager.getService();
    }

    @Override // android.os.IHwBinder.DeathRecipient
    public void serviceDied(long cookie) {
        Slog.v(TAG, "fingerprint HAL died");
        MetricsLogger.count(this.mContext, "fingerprintd_died", 1);
        handleError(this.mHalDeviceId, 1, 0);
    }

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
                doFingerprintCleanupForUser(ActivityManager.getCurrentUser());
            } else {
                Slog.w(TAG, "Failed to open Fingerprint HAL!");
                MetricsLogger.count(this.mContext, "fingerprintd_openhal_error", 1);
                this.mDaemon = null;
            }
        }
        return this.mDaemon;
    }

    private void loadAuthenticatorIds() {
        long t = System.currentTimeMillis();
        this.mAuthenticatorIds.clear();
        for (UserInfo user : UserManager.get(this.mContext).getUsers(true)) {
            int userId = getUserOrWorkProfileId(null, user.id);
            if (!this.mAuthenticatorIds.containsKey(Integer.valueOf(userId))) {
                updateActiveGroup(userId, null);
            }
        }
        long t2 = System.currentTimeMillis() - t;
        if (t2 > 1000) {
            Slog.w(TAG, "loadAuthenticatorIds() taking too long: " + t2 + "ms");
        }
    }

    private void doFingerprintCleanupForUser(int userId) {
        enumerateUser(userId);
    }

    private void clearEnumerateState() {
        Slog.v(TAG, "clearEnumerateState()");
        this.mUnknownFingerprints.clear();
    }

    private void enumerateUser(int userId) {
        Slog.v(TAG, "Enumerating user(" + userId + ")");
        boolean restricted = hasPermission("android.permission.MANAGE_FINGERPRINT") ^ true;
        startEnumerate(this.mToken, userId, null, restricted, true);
    }

    private void cleanupUnknownFingerprints() {
        if (!this.mUnknownFingerprints.isEmpty()) {
            UserFingerprint uf = this.mUnknownFingerprints.get(0);
            this.mUnknownFingerprints.remove(uf);
            boolean restricted = !hasPermission("android.permission.MANAGE_FINGERPRINT");
            startRemove(this.mToken, uf.f.getFingerId(), uf.f.getGroupId(), uf.userId, null, restricted, true);
            return;
        }
        clearEnumerateState();
    }

    protected void handleEnumerate(long deviceId, int fingerId, int groupId, int remaining) {
        ClientMonitor client = this.mCurrentClient;
        if (!(client instanceof InternalRemovalClient) && !(client instanceof EnumerateClient)) {
            return;
        }
        client.onEnumerationResult(fingerId, groupId, remaining);
        if (remaining == 0) {
            if (client instanceof InternalEnumerateClient) {
                List<Fingerprint> unknownFingerprints = ((InternalEnumerateClient) client).getUnknownFingerprints();
                if (!unknownFingerprints.isEmpty()) {
                    Slog.w(TAG, "Adding " + unknownFingerprints.size() + " fingerprints for deletion");
                }
                for (Fingerprint f : unknownFingerprints) {
                    this.mUnknownFingerprints.add(new UserFingerprint(f, client.getTargetUserId()));
                }
                removeClient(client);
                cleanupUnknownFingerprints();
                return;
            }
            removeClient(client);
        }
    }

    protected void handleError(long deviceId, int error, int vendorCode) {
        ClientMonitor client = this.mCurrentClient;
        if ((client instanceof InternalRemovalClient) || (client instanceof InternalEnumerateClient)) {
            clearEnumerateState();
        }
        if (client != null && client.onError(error, vendorCode)) {
            removeClient(client);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("handleError(client=");
        sb.append(client != null ? client.getOwnerString() : "null");
        sb.append(", error = ");
        sb.append(error);
        sb.append(")");
        Slog.v(TAG, sb.toString());
        if (error == 5) {
            this.mHandler.removeCallbacks(this.mResetClientState);
            if (this.mPendingClient != null) {
                Slog.v(TAG, "start pending client " + this.mPendingClient.getOwnerString());
                startClient(this.mPendingClient, false);
                this.mPendingClient = null;
            }
        } else if (error == 1) {
            Slog.w(TAG, "Got ERROR_HW_UNAVAILABLE; try reconnecting next client.");
            synchronized (this) {
                this.mDaemon = null;
                this.mHalDeviceId = 0L;
                this.mCurrentUserId = -10000;
            }
        }
    }

    protected void handleRemoved(long deviceId, int fingerId, int groupId, int remaining) {
        Slog.w(TAG, "Removed: fid=" + fingerId + ", gid=" + groupId + ", dev=" + deviceId + ", rem=" + remaining);
        ClientMonitor client = this.mCurrentClient;
        if (client != null && client.onRemoved(fingerId, groupId, remaining)) {
            removeClient(client);
            if (!hasEnrolledFingerprints(groupId)) {
                updateActiveGroup(groupId, null);
            }
        }
        if ((client instanceof InternalRemovalClient) && !this.mUnknownFingerprints.isEmpty()) {
            cleanupUnknownFingerprints();
        } else if (client instanceof InternalRemovalClient) {
            clearEnumerateState();
        }
    }

    protected void handleAuthenticated(long deviceId, int fingerId, int groupId, ArrayList<Byte> token) {
        ClientMonitor client = this.mCurrentClient;
        if (fingerId != 0) {
            byte[] byteToken = new byte[token.size()];
            for (int i = 0; i < token.size(); i++) {
                byteToken[i] = token.get(i).byteValue();
            }
            KeyStore.getInstance().addAuthToken(byteToken);
        }
        if (client != null && client.onAuthenticated(fingerId, groupId)) {
            removeClient(client);
        }
        if (fingerId != 0) {
            this.mPerformanceStats.accept++;
            return;
        }
        this.mPerformanceStats.reject++;
    }

    protected void handleAcquired(long deviceId, int acquiredInfo, int vendorCode) {
        ClientMonitor client = this.mCurrentClient;
        if (client != null && client.onAcquired(acquiredInfo, vendorCode)) {
            removeClient(client);
        }
        if (this.mPerformanceStats != null && getLockoutMode() == 0 && (client instanceof AuthenticationClient)) {
            this.mPerformanceStats.acquire++;
        }
    }

    protected void handleEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
        ClientMonitor client = this.mCurrentClient;
        if (client != null && client.onEnrollResult(fingerId, groupId, remaining)) {
            removeClient(client);
            updateActiveGroup(groupId, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void userActivity() {
        long now = SystemClock.uptimeMillis();
        this.mPowerManager.userActivity(now, 2, 0);
    }

    void handleUserSwitching(int userId) {
        if ((this.mCurrentClient instanceof InternalRemovalClient) || (this.mCurrentClient instanceof InternalEnumerateClient)) {
            Slog.w(TAG, "User switched while performing cleanup");
            removeClient(this.mCurrentClient);
            clearEnumerateState();
        }
        updateActiveGroup(userId, null);
        doFingerprintCleanupForUser(userId);
    }

    private void removeClient(ClientMonitor client) {
        if (client != null) {
            client.destroy();
            if (client != this.mCurrentClient && this.mCurrentClient != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("Unexpected client: ");
                sb.append(client.getOwnerString());
                sb.append("expected: ");
                sb.append(this.mCurrentClient);
                Slog.w(TAG, sb.toString() != null ? this.mCurrentClient.getOwnerString() : "null");
            }
        }
        if (this.mCurrentClient != null) {
            Slog.v(TAG, "Done with client: " + client.getOwnerString());
            this.mCurrentClient = null;
        }
        if (this.mPendingClient == null) {
            notifyClientActiveCallbacks(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getLockoutMode() {
        int currentUser = ActivityManager.getCurrentUser();
        int failedAttempts = this.mFailedAttempts.get(currentUser, 0);
        if (failedAttempts >= 20) {
            return 2;
        }
        return (failedAttempts <= 0 || this.mTimedLockoutCleared.get(currentUser, false) || failedAttempts % 5 != 0) ? 0 : 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleLockoutResetForUser(int userId) {
        this.mAlarmManager.setExact(2, SystemClock.elapsedRealtime() + 30000, getLockoutResetIntentForUser(userId));
    }

    private void cancelLockoutResetForUser(int userId) {
        this.mAlarmManager.cancel(getLockoutResetIntentForUser(userId));
    }

    private PendingIntent getLockoutResetIntentForUser(int userId) {
        return PendingIntent.getBroadcast(this.mContext, userId, new Intent(ACTION_LOCKOUT_RESET).putExtra(KEY_LOCKOUT_RESET_USER, userId), 134217728);
    }

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
    public void startClient(ClientMonitor newClient, boolean initiatedByClient) {
        ClientMonitor currentClient = this.mCurrentClient;
        if (currentClient != null) {
            Slog.v(TAG, "request stop current client " + currentClient.getOwnerString());
            if ((currentClient instanceof InternalEnumerateClient) || (currentClient instanceof InternalRemovalClient)) {
                if (newClient != null) {
                    Slog.w(TAG, "Internal cleanup in progress but trying to start client " + newClient.getClass().getSuperclass().getSimpleName() + "(" + newClient.getOwnerString() + "), initiatedByClient = " + initiatedByClient);
                }
            } else {
                currentClient.stop(initiatedByClient);
            }
            this.mPendingClient = newClient;
            this.mHandler.removeCallbacks(this.mResetClientState);
            this.mHandler.postDelayed(this.mResetClientState, CANCEL_TIMEOUT_LIMIT);
        } else if (newClient != null) {
            this.mCurrentClient = newClient;
            Slog.v(TAG, "starting client " + newClient.getClass().getSuperclass().getSimpleName() + "(" + newClient.getOwnerString() + "), initiatedByClient = " + initiatedByClient);
            notifyClientActiveCallbacks(true);
            newClient.start();
        }
    }

    void startRemove(IBinder token, int fingerId, int groupId, int userId, IFingerprintServiceReceiver receiver, boolean restricted, boolean internal) {
        if (token == null) {
            Slog.w(TAG, "startRemove: token is null");
        } else if (receiver == null) {
            Slog.w(TAG, "startRemove: receiver is null");
        } else {
            IBiometricsFingerprint daemon = getFingerprintDaemon();
            if (daemon == null) {
                Slog.w(TAG, "startRemove: no fingerprint HAL!");
            } else if (internal) {
                Context context = getContext();
                InternalRemovalClient client = new InternalRemovalClient(context, this.mHalDeviceId, token, receiver, fingerId, groupId, userId, restricted, context.getOpPackageName()) { // from class: com.android.server.fingerprint.FingerprintService.6
                    @Override // com.android.server.fingerprint.ClientMonitor
                    public void notifyUserActivity() {
                    }

                    @Override // com.android.server.fingerprint.ClientMonitor
                    public IBiometricsFingerprint getFingerprintDaemon() {
                        return FingerprintService.this.getFingerprintDaemon();
                    }
                };
                startClient(client, true);
            } else {
                RemovalClient client2 = new RemovalClient(getContext(), this.mHalDeviceId, token, receiver, fingerId, groupId, userId, restricted, token.toString()) { // from class: com.android.server.fingerprint.FingerprintService.7
                    @Override // com.android.server.fingerprint.ClientMonitor
                    public void notifyUserActivity() {
                        FingerprintService.this.userActivity();
                    }

                    @Override // com.android.server.fingerprint.ClientMonitor
                    public IBiometricsFingerprint getFingerprintDaemon() {
                        return FingerprintService.this.getFingerprintDaemon();
                    }
                };
                startClient(client2, true);
            }
        }
    }

    void startEnumerate(IBinder token, int userId, IFingerprintServiceReceiver receiver, boolean restricted, boolean internal) {
        IBiometricsFingerprint daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startEnumerate: no fingerprint HAL!");
        } else if (internal) {
            List<Fingerprint> enrolledList = getEnrolledFingerprints(userId);
            Context context = getContext();
            InternalEnumerateClient client = new InternalEnumerateClient(context, this.mHalDeviceId, token, receiver, userId, userId, restricted, context.getOpPackageName(), enrolledList) { // from class: com.android.server.fingerprint.FingerprintService.8
                @Override // com.android.server.fingerprint.ClientMonitor
                public void notifyUserActivity() {
                }

                @Override // com.android.server.fingerprint.ClientMonitor
                public IBiometricsFingerprint getFingerprintDaemon() {
                    return FingerprintService.this.getFingerprintDaemon();
                }
            };
            startClient(client, true);
        } else {
            EnumerateClient client2 = new EnumerateClient(getContext(), this.mHalDeviceId, token, receiver, userId, userId, restricted, token.toString()) { // from class: com.android.server.fingerprint.FingerprintService.9
                @Override // com.android.server.fingerprint.ClientMonitor
                public void notifyUserActivity() {
                    FingerprintService.this.userActivity();
                }

                @Override // com.android.server.fingerprint.ClientMonitor
                public IBiometricsFingerprint getFingerprintDaemon() {
                    return FingerprintService.this.getFingerprintDaemon();
                }
            };
            startClient(client2, true);
        }
    }

    public List<Fingerprint> getEnrolledFingerprints(int userId) {
        return this.mFingerprintUtils.getFingerprintsForUser(this.mContext, userId);
    }

    public boolean hasEnrolledFingerprints(int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkPermission("android.permission.INTERACT_ACROSS_USERS");
        }
        return this.mFingerprintUtils.getFingerprintsForUser(this.mContext, userId).size() > 0;
    }

    boolean hasPermission(String permission) {
        return getContext().checkCallingOrSelfPermission(permission) == 0;
    }

    void checkPermission(String permission) {
        Context context = getContext();
        context.enforceCallingOrSelfPermission(permission, "Must have " + permission + " permission.");
    }

    int getEffectiveUserId(int userId) {
        UserManager um = UserManager.get(this.mContext);
        if (um != null) {
            long callingIdentity = Binder.clearCallingIdentity();
            int userId2 = um.getCredentialOwnerProfile(userId);
            Binder.restoreCallingIdentity(callingIdentity);
            return userId2;
        }
        Slog.e(TAG, "Unable to acquire UserManager");
        return userId;
    }

    boolean isCurrentUserOrProfile(int userId) {
        int[] enabledProfileIds;
        UserManager um = UserManager.get(this.mContext);
        if (um == null) {
            Slog.e(TAG, "Unable to acquire UserManager");
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
        } catch (RemoteException e) {
            Slog.w(TAG, "am.getRunningAppProcesses() failed");
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean canUseFingerprint(String opPackageName, boolean requireForeground, int uid, int pid, int userId) {
        if (getContext().checkCallingPermission("android.permission.USE_FINGERPRINT") != 0) {
            checkPermission("android.permission.USE_BIOMETRIC");
        }
        if (isKeyguard(opPackageName)) {
            return true;
        }
        if (!isCurrentUserOrProfile(userId)) {
            Slog.w(TAG, "Rejecting " + opPackageName + " ; not a current user or profile");
            return false;
        } else if (this.mAppOps.noteOp(55, uid, opPackageName) != 0) {
            Slog.w(TAG, "Rejecting " + opPackageName + " ; permission denied");
            return false;
        } else if (!requireForeground || isForegroundActivity(uid, pid) || currentClient(opPackageName)) {
            return true;
        } else {
            Slog.w(TAG, "Rejecting " + opPackageName + " ; not in foreground");
            return false;
        }
    }

    private boolean currentClient(String opPackageName) {
        return this.mCurrentClient != null && this.mCurrentClient.getOwnerString().equals(opPackageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isKeyguard(String clientPackage) {
        return this.mKeyguardPackage.equals(clientPackage);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addLockoutResetMonitor(FingerprintServiceLockoutResetMonitor monitor) {
        if (!this.mLockoutMonitors.contains(monitor)) {
            this.mLockoutMonitors.add(monitor);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeLockoutResetCallback(FingerprintServiceLockoutResetMonitor monitor) {
        this.mLockoutMonitors.remove(monitor);
    }

    private void notifyLockoutResetMonitors() {
        for (int i = 0; i < this.mLockoutMonitors.size(); i++) {
            this.mLockoutMonitors.get(i).sendLockoutReset();
        }
    }

    private void notifyClientActiveCallbacks(boolean isActive) {
        List<IFingerprintClientActiveCallback> callbacks = this.mClientActiveCallbacks;
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onClientActiveChanged(isActive);
            } catch (RemoteException e) {
                this.mClientActiveCallbacks.remove(callbacks.get(i));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startAuthentication(IBinder token, long opId, int callingUserId, int groupId, IFingerprintServiceReceiver receiver, int flags, boolean restricted, String opPackageName, Bundle bundle, IBiometricPromptReceiver dialogReceiver) {
        int errorCode;
        updateActiveGroup(groupId, opPackageName);
        Slog.v(TAG, "startAuthentication(" + opPackageName + ")");
        AuthenticationClient client = new AuthenticationClient(getContext(), this.mHalDeviceId, token, receiver, this.mCurrentUserId, groupId, opId, restricted, opPackageName, bundle, dialogReceiver, this.mStatusBarService) { // from class: com.android.server.fingerprint.FingerprintService.10
            @Override // com.android.server.fingerprint.AuthenticationClient
            public void onStart() {
                try {
                    FingerprintService.this.mActivityManager.registerTaskStackListener(FingerprintService.this.mTaskStackListener);
                } catch (RemoteException e) {
                    Slog.e(FingerprintService.TAG, "Could not register task stack listener", e);
                }
            }

            @Override // com.android.server.fingerprint.AuthenticationClient
            public void onStop() {
                try {
                    FingerprintService.this.mActivityManager.unregisterTaskStackListener(FingerprintService.this.mTaskStackListener);
                } catch (RemoteException e) {
                    Slog.e(FingerprintService.TAG, "Could not unregister task stack listener", e);
                }
            }

            @Override // com.android.server.fingerprint.AuthenticationClient
            public int handleFailedAttempt() {
                int currentUser = ActivityManager.getCurrentUser();
                FingerprintService.this.mFailedAttempts.put(currentUser, FingerprintService.this.mFailedAttempts.get(currentUser, 0) + 1);
                FingerprintService.this.mTimedLockoutCleared.put(ActivityManager.getCurrentUser(), false);
                int lockoutMode = FingerprintService.this.getLockoutMode();
                if (lockoutMode == 2) {
                    FingerprintService.this.mPerformanceStats.permanentLockout++;
                } else if (lockoutMode == 1) {
                    FingerprintService.this.mPerformanceStats.lockout++;
                }
                if (lockoutMode != 0) {
                    FingerprintService.this.scheduleLockoutResetForUser(currentUser);
                    return lockoutMode;
                }
                return 0;
            }

            @Override // com.android.server.fingerprint.AuthenticationClient
            public void resetFailedAttempts() {
                FingerprintService.this.resetFailedAttemptsForUser(true, ActivityManager.getCurrentUser());
            }

            @Override // com.android.server.fingerprint.ClientMonitor
            public void notifyUserActivity() {
                FingerprintService.this.userActivity();
            }

            @Override // com.android.server.fingerprint.ClientMonitor
            public IBiometricsFingerprint getFingerprintDaemon() {
                return FingerprintService.this.getFingerprintDaemon();
            }
        };
        int lockoutMode = getLockoutMode();
        if (lockoutMode != 0) {
            Slog.v(TAG, "In lockout mode(" + lockoutMode + ") ; disallowing authentication");
            if (lockoutMode == 1) {
                errorCode = 7;
            } else {
                errorCode = 9;
            }
            if (!client.onError(errorCode, 0)) {
                Slog.w(TAG, "Cannot send permanent lockout message to client");
                return;
            }
            return;
        }
        startClient(client, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startEnrollment(IBinder token, byte[] cryptoToken, int userId, IFingerprintServiceReceiver receiver, int flags, boolean restricted, String opPackageName) {
        updateActiveGroup(userId, opPackageName);
        EnrollClient client = new EnrollClient(getContext(), this.mHalDeviceId, token, receiver, userId, userId, cryptoToken, restricted, opPackageName) { // from class: com.android.server.fingerprint.FingerprintService.11
            @Override // com.android.server.fingerprint.ClientMonitor
            public IBiometricsFingerprint getFingerprintDaemon() {
                return FingerprintService.this.getFingerprintDaemon();
            }

            @Override // com.android.server.fingerprint.ClientMonitor
            public void notifyUserActivity() {
                FingerprintService.this.userActivity();
            }
        };
        startClient(client, true);
    }

    protected void resetFailedAttemptsForUser(boolean clearAttemptCounter, int userId) {
        if (getLockoutMode() != 0) {
            Slog.v(TAG, "Reset fingerprint lockout, clearAttemptCounter=" + clearAttemptCounter);
        }
        if (clearAttemptCounter) {
            this.mFailedAttempts.put(userId, 0);
        }
        this.mTimedLockoutCleared.put(userId, true);
        cancelLockoutResetForUser(userId);
        notifyLockoutResetMonitors();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class FingerprintServiceLockoutResetMonitor implements IBinder.DeathRecipient {
        private static final long WAKELOCK_TIMEOUT_MS = 2000;
        private final IFingerprintServiceLockoutResetCallback mCallback;
        private final Runnable mRemoveCallbackRunnable = new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceLockoutResetMonitor.2
            @Override // java.lang.Runnable
            public void run() {
                FingerprintServiceLockoutResetMonitor.this.releaseWakelock();
                FingerprintService.this.removeLockoutResetCallback(FingerprintServiceLockoutResetMonitor.this);
            }
        };
        private final PowerManager.WakeLock mWakeLock;

        public FingerprintServiceLockoutResetMonitor(IFingerprintServiceLockoutResetCallback callback) {
            this.mCallback = callback;
            this.mWakeLock = FingerprintService.this.mPowerManager.newWakeLock(1, "lockout reset callback");
            try {
                this.mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.w(FingerprintService.TAG, "caught remote exception in linkToDeath", e);
            }
        }

        public void sendLockoutReset() {
            if (this.mCallback != null) {
                try {
                    this.mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    this.mCallback.onLockoutReset(FingerprintService.this.mHalDeviceId, new IRemoteCallback.Stub() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceLockoutResetMonitor.1
                        public void sendResult(Bundle data) throws RemoteException {
                            FingerprintServiceLockoutResetMonitor.this.releaseWakelock();
                        }
                    });
                } catch (DeadObjectException e) {
                    Slog.w(FingerprintService.TAG, "Death object while invoking onLockoutReset: ", e);
                    FingerprintService.this.mHandler.post(this.mRemoveCallbackRunnable);
                } catch (RemoteException e2) {
                    Slog.w(FingerprintService.TAG, "Failed to invoke onLockoutReset: ", e2);
                    releaseWakelock();
                }
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Slog.e(FingerprintService.TAG, "Lockout reset callback binder died");
            FingerprintService.this.mHandler.post(this.mRemoveCallbackRunnable);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void releaseWakelock() {
            if (this.mWakeLock.isHeld()) {
                this.mWakeLock.release();
            }
        }
    }

    /* loaded from: classes.dex */
    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        private FingerprintServiceWrapper() {
        }

        public long preEnroll(IBinder token) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            return FingerprintService.this.startPreEnroll(token);
        }

        public int postEnroll(IBinder token) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            return FingerprintService.this.startPostEnroll(token);
        }

        public void enroll(final IBinder token, final byte[] cryptoToken, final int userId, final IFingerprintServiceReceiver receiver, final int flags, final String opPackageName) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            int limit = FingerprintService.this.mContext.getResources().getInteger(17694789);
            int enrolled = FingerprintService.this.getEnrolledFingerprints(userId).size();
            if (enrolled >= limit) {
                Slog.w(FingerprintService.TAG, "Too many fingerprints registered");
            } else if (!FingerprintService.this.isCurrentUserOrProfile(userId)) {
            } else {
                final boolean restricted = isRestricted();
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.1
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.startEnrollment(token, cryptoToken, userId, receiver, flags, restricted, opPackageName);
                    }
                });
            }
        }

        private boolean isRestricted() {
            boolean restricted = !FingerprintService.this.hasPermission("android.permission.MANAGE_FINGERPRINT");
            return restricted;
        }

        public void cancelEnrollment(final IBinder token) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.2
                @Override // java.lang.Runnable
                public void run() {
                    ClientMonitor client = FingerprintService.this.mCurrentClient;
                    if ((client instanceof EnrollClient) && client.getToken() == token) {
                        client.stop(client.getToken() == token);
                    }
                }
            });
        }

        public void authenticate(final IBinder token, final long opId, final int groupId, final IFingerprintServiceReceiver receiver, final int flags, final String opPackageName, final Bundle bundle, final IBiometricPromptReceiver dialogReceiver) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            final int callingUserId = UserHandle.getCallingUserId();
            final boolean restricted = isRestricted();
            if (!FingerprintService.this.canUseFingerprint(opPackageName, true, callingUid, callingPid, callingUserId)) {
                Slog.v(FingerprintService.TAG, "authenticate(): reject " + opPackageName);
                return;
            }
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.3
                @Override // java.lang.Runnable
                public void run() {
                    MetricsLogger.histogram(FingerprintService.this.mContext, "fingerprint_token", opId != 0 ? 1 : 0);
                    HashMap<Integer, PerformanceStats> pmap = opId == 0 ? FingerprintService.this.mPerformanceMap : FingerprintService.this.mCryptoPerformanceMap;
                    PerformanceStats stats = pmap.get(Integer.valueOf(FingerprintService.this.mCurrentUserId));
                    if (stats == null) {
                        stats = new PerformanceStats();
                        pmap.put(Integer.valueOf(FingerprintService.this.mCurrentUserId), stats);
                    }
                    FingerprintService.this.mPerformanceStats = stats;
                    FingerprintService.this.startAuthentication(token, opId, callingUserId, groupId, receiver, flags, restricted, opPackageName, bundle, dialogReceiver);
                }
            });
        }

        public void cancelAuthentication(final IBinder token, String opPackageName) {
            int callingUid = Binder.getCallingUid();
            int callingPid = Binder.getCallingPid();
            int callingUserId = UserHandle.getCallingUserId();
            if (FingerprintService.this.canUseFingerprint(opPackageName, true, callingUid, callingPid, callingUserId)) {
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.4
                    @Override // java.lang.Runnable
                    public void run() {
                        ClientMonitor client = FingerprintService.this.mCurrentClient;
                        if (client instanceof AuthenticationClient) {
                            if (client.getToken() == token) {
                                Slog.v(FingerprintService.TAG, "stop client " + client.getOwnerString());
                                client.stop(client.getToken() == token);
                                return;
                            }
                            Slog.v(FingerprintService.TAG, "can't stop client " + client.getOwnerString() + " since tokens don't match");
                        } else if (client != null) {
                            Slog.v(FingerprintService.TAG, "can't cancel non-authenticating client " + client.getOwnerString());
                        }
                    }
                });
                return;
            }
            Slog.v(FingerprintService.TAG, "cancelAuthentication(): reject " + opPackageName);
        }

        public void setActiveUser(final int userId) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.5
                @Override // java.lang.Runnable
                public void run() {
                    FingerprintService.this.updateActiveGroup(userId, null);
                }
            });
        }

        public void remove(final IBinder token, final int fingerId, final int groupId, final int userId, final IFingerprintServiceReceiver receiver) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            final boolean restricted = isRestricted();
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.6
                @Override // java.lang.Runnable
                public void run() {
                    FingerprintService.this.startRemove(token, fingerId, groupId, userId, receiver, restricted, false);
                }
            });
        }

        public void enumerate(final IBinder token, final int userId, final IFingerprintServiceReceiver receiver) {
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            final boolean restricted = isRestricted();
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.7
                @Override // java.lang.Runnable
                public void run() {
                    FingerprintService.this.startEnumerate(token, userId, receiver, restricted, false);
                }
            });
        }

        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            boolean z = false;
            if (FingerprintService.this.canUseFingerprint(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
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
                FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.8
                    @Override // java.lang.Runnable
                    public void run() {
                        FingerprintService.this.mFingerprintUtils.renameFingerprintForUser(FingerprintService.this.mContext, fingerId, groupId, name);
                    }
                });
            }
        }

        public List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName) {
            if (!FingerprintService.this.canUseFingerprint(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                return Collections.emptyList();
            }
            return FingerprintService.this.getEnrolledFingerprints(userId);
        }

        public boolean hasEnrolledFingerprints(int userId, String opPackageName) {
            if (!FingerprintService.this.canUseFingerprint(opPackageName, false, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getCallingUserId())) {
                return false;
            }
            return FingerprintService.this.hasEnrolledFingerprints(userId);
        }

        public long getAuthenticatorId(String opPackageName) {
            return FingerprintService.this.getAuthenticatorId(opPackageName);
        }

        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (DumpUtils.checkDumpPermission(FingerprintService.this.mContext, FingerprintService.TAG, pw)) {
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
        }

        public void resetTimeout(byte[] token) {
            FingerprintService.this.checkPermission("android.permission.RESET_FINGERPRINT_LOCKOUT");
            FingerprintService.this.mHandler.post(FingerprintService.this.mResetFailedAttemptsForCurrentUserRunnable);
        }

        public void addLockoutResetCallback(final IFingerprintServiceLockoutResetCallback callback) throws RemoteException {
            FingerprintService.this.mHandler.post(new Runnable() { // from class: com.android.server.fingerprint.FingerprintService.FingerprintServiceWrapper.9
                @Override // java.lang.Runnable
                public void run() {
                    FingerprintService.this.addLockoutResetMonitor(new FingerprintServiceLockoutResetMonitor(callback));
                }
            });
        }

        public boolean isClientActive() {
            boolean z;
            FingerprintService.this.checkPermission("android.permission.MANAGE_FINGERPRINT");
            synchronized (FingerprintService.this) {
                z = (FingerprintService.this.mCurrentClient == null && FingerprintService.this.mPendingClient == null) ? false : true;
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

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpInternal(PrintWriter pw) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Fingerprint Manager");
            JSONArray sets = new JSONArray();
            for (UserInfo user : UserManager.get(getContext()).getUsers()) {
                int userId = user.getUserHandle().getIdentifier();
                int N = this.mFingerprintUtils.getFingerprintsForUser(this.mContext, userId).size();
                PerformanceStats stats = this.mPerformanceMap.get(Integer.valueOf(userId));
                PerformanceStats cryptoStats = this.mCryptoPerformanceMap.get(Integer.valueOf(userId));
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
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpProto(FileDescriptor fd) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        for (UserInfo user : UserManager.get(getContext()).getUsers()) {
            int userId = user.getUserHandle().getIdentifier();
            long userToken = proto.start(2246267895809L);
            proto.write(1120986464257L, userId);
            proto.write(1120986464258L, this.mFingerprintUtils.getFingerprintsForUser(this.mContext, userId).size());
            PerformanceStats normal = this.mPerformanceMap.get(Integer.valueOf(userId));
            if (normal != null) {
                long countsToken = proto.start(1146756268035L);
                proto.write(1120986464257L, normal.accept);
                proto.write(1120986464258L, normal.reject);
                proto.write(1120986464259L, normal.acquire);
                proto.write(1120986464260L, normal.lockout);
                proto.write(1120986464261L, normal.permanentLockout);
                proto.end(countsToken);
            }
            PerformanceStats crypto = this.mCryptoPerformanceMap.get(Integer.valueOf(userId));
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

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("fingerprint", new FingerprintServiceWrapper());
        SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.fingerprint.-$$Lambda$l42rkDmfSgEoarEM7da3vinr3Iw
            @Override // java.lang.Runnable
            public final void run() {
                FingerprintService.this.getFingerprintDaemon();
            }
        }, "FingerprintService.onStart");
        listenForUserSwitches();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateActiveGroup(int userId, String clientPackage) {
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
                this.mAuthenticatorIds.put(Integer.valueOf(userId2), Long.valueOf(hasEnrolledFingerprints(userId2) ? daemon.getAuthenticatorId() : 0L));
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveGroup():", e);
            }
        }
    }

    private int getUserOrWorkProfileId(String clientPackage, int userId) {
        if (!isKeyguard(clientPackage) && isWorkProfile(userId)) {
            return userId;
        }
        return getEffectiveUserId(userId);
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

    private void listenForUserSwitches() {
        try {
            ActivityManager.getService().registerUserSwitchObserver(new SynchronousUserSwitchObserver() { // from class: com.android.server.fingerprint.FingerprintService.13
                public void onUserSwitching(int newUserId) throws RemoteException {
                    FingerprintService.this.mHandler.obtainMessage(10, newUserId, 0).sendToTarget();
                }
            }, TAG);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to listen for user switching event", e);
        }
    }

    public long getAuthenticatorId(String opPackageName) {
        int userId = getUserOrWorkProfileId(opPackageName, UserHandle.getCallingUserId());
        return this.mAuthenticatorIds.getOrDefault(Integer.valueOf(userId), 0L).longValue();
    }
}
