package com.android.server.trust;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustListener;
import android.app.trust.ITrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricSourceType;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.trust.TrustManagerService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* loaded from: classes2.dex */
public class TrustManagerService extends SystemService {
    static final boolean DEBUG;
    private static final int MSG_CLEANUP_USER = 8;
    private static final int MSG_DISPATCH_UNLOCK_ATTEMPT = 3;
    private static final int MSG_DISPATCH_UNLOCK_LOCKOUT = 13;
    private static final int MSG_ENABLED_AGENTS_CHANGED = 4;
    private static final int MSG_FLUSH_TRUST_USUALLY_MANAGED = 10;
    private static final int MSG_KEYGUARD_SHOWING_CHANGED = 6;
    private static final int MSG_REFRESH_DEVICE_LOCKED_FOR_USER = 14;
    private static final int MSG_REGISTER_LISTENER = 1;
    private static final int MSG_SCHEDULE_TRUST_TIMEOUT = 15;
    private static final int MSG_START_USER = 7;
    private static final int MSG_STOP_USER = 12;
    private static final int MSG_SWITCH_USER = 9;
    private static final int MSG_UNLOCK_USER = 11;
    private static final int MSG_UNREGISTER_LISTENER = 2;
    private static final String PERMISSION_PROVIDE_AGENT = "android.permission.PROVIDE_TRUST_AGENT";
    private static final String TAG = "TrustManagerService";
    private static final Intent TRUST_AGENT_INTENT;
    private static final String TRUST_TIMEOUT_ALARM_TAG = "TrustManagerService.trustTimeoutForUser";
    private static final long TRUST_TIMEOUT_IN_MILLIS = 14400000;
    private static final int TRUST_USUALLY_MANAGED_FLUSH_DELAY = 120000;
    private final ArraySet<AgentInfo> mActiveAgents;
    private final ActivityManager mActivityManager;
    private AlarmManager mAlarmManager;
    final TrustArchive mArchive;
    private final Context mContext;
    private int mCurrentUser;
    @GuardedBy({"mDeviceLockedForUser"})
    private final SparseBooleanArray mDeviceLockedForUser;
    private final Handler mHandler;
    private final LockPatternUtils mLockPatternUtils;
    private final PackageMonitor mPackageMonitor;
    private final Receiver mReceiver;
    private final IBinder mService;
    private final SettingsObserver mSettingsObserver;
    private final StrongAuthTracker mStrongAuthTracker;
    private boolean mTrustAgentsCanRun;
    private final ArrayList<ITrustListener> mTrustListeners;
    private final ArrayMap<Integer, TrustTimeoutAlarmListener> mTrustTimeoutAlarmListenerForUser;
    @GuardedBy({"mTrustUsuallyManagedForUser"})
    private final SparseBooleanArray mTrustUsuallyManagedForUser;
    @GuardedBy({"mUserIsTrusted"})
    private final SparseBooleanArray mUserIsTrusted;
    private final UserManager mUserManager;
    @GuardedBy({"mUsersUnlockedByBiometric"})
    private final SparseBooleanArray mUsersUnlockedByBiometric;

    static {
        DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, 2);
        TRUST_AGENT_INTENT = new Intent("android.service.trust.TrustAgentService");
    }

    public TrustManagerService(Context context) {
        super(context);
        this.mActiveAgents = new ArraySet<>();
        this.mTrustListeners = new ArrayList<>();
        this.mReceiver = new Receiver(this, null);
        this.mArchive = new TrustArchive();
        this.mUserIsTrusted = new SparseBooleanArray();
        this.mDeviceLockedForUser = new SparseBooleanArray();
        this.mTrustUsuallyManagedForUser = new SparseBooleanArray();
        this.mUsersUnlockedByBiometric = new SparseBooleanArray();
        this.mTrustTimeoutAlarmListenerForUser = new ArrayMap<>();
        this.mTrustAgentsCanRun = false;
        this.mCurrentUser = 0;
        this.mService = new AnonymousClass1();
        this.mHandler = new Handler() { // from class: com.android.server.trust.TrustManagerService.2
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                SparseBooleanArray usuallyManaged;
                switch (msg.what) {
                    case 1:
                        TrustManagerService.this.addListener((ITrustListener) msg.obj);
                        return;
                    case 2:
                        TrustManagerService.this.removeListener((ITrustListener) msg.obj);
                        return;
                    case 3:
                        TrustManagerService.this.dispatchUnlockAttempt(msg.arg1 != 0, msg.arg2);
                        return;
                    case 4:
                        TrustManagerService.this.refreshAgentList(-1);
                        TrustManagerService.this.refreshDeviceLockedForUser(-1);
                        return;
                    case 5:
                    default:
                        return;
                    case 6:
                        TrustManagerService trustManagerService = TrustManagerService.this;
                        trustManagerService.refreshDeviceLockedForUser(trustManagerService.mCurrentUser);
                        return;
                    case 7:
                    case 8:
                    case 11:
                        TrustManagerService.this.refreshAgentList(msg.arg1);
                        return;
                    case 9:
                        TrustManagerService.this.mCurrentUser = msg.arg1;
                        TrustManagerService.this.mSettingsObserver.updateContentObserver();
                        TrustManagerService.this.refreshDeviceLockedForUser(-1);
                        return;
                    case 10:
                        synchronized (TrustManagerService.this.mTrustUsuallyManagedForUser) {
                            usuallyManaged = TrustManagerService.this.mTrustUsuallyManagedForUser.clone();
                        }
                        for (int i = 0; i < usuallyManaged.size(); i++) {
                            int userId = usuallyManaged.keyAt(i);
                            boolean value = usuallyManaged.valueAt(i);
                            if (value != TrustManagerService.this.mLockPatternUtils.isTrustUsuallyManaged(userId)) {
                                TrustManagerService.this.mLockPatternUtils.setTrustUsuallyManaged(value, userId);
                            }
                        }
                        return;
                    case 12:
                        TrustManagerService.this.setDeviceLockedForUser(msg.arg1, true);
                        return;
                    case 13:
                        TrustManagerService.this.dispatchUnlockLockout(msg.arg1, msg.arg2);
                        return;
                    case 14:
                        if (msg.arg2 == 1) {
                            TrustManagerService.this.updateTrust(msg.arg1, 0, true);
                        }
                        TrustManagerService.this.refreshDeviceLockedForUser(msg.arg1);
                        return;
                    case 15:
                        TrustManagerService.this.handleScheduleTrustTimeout(msg.arg1, msg.arg2);
                        return;
                }
            }
        };
        this.mPackageMonitor = new PackageMonitor() { // from class: com.android.server.trust.TrustManagerService.3
            public void onSomePackagesChanged() {
                TrustManagerService.this.refreshAgentList(-1);
            }

            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                return true;
            }

            public void onPackageDisappeared(String packageName, int reason) {
                TrustManagerService.this.removeAgentsOfPackage(packageName);
            }
        };
        this.mContext = context;
        this.mUserManager = (UserManager) this.mContext.getSystemService("user");
        this.mActivityManager = (ActivityManager) this.mContext.getSystemService("activity");
        this.mLockPatternUtils = new LockPatternUtils(context);
        this.mStrongAuthTracker = new StrongAuthTracker(context);
        this.mAlarmManager = (AlarmManager) this.mContext.getSystemService("alarm");
        this.mSettingsObserver = new SettingsObserver(this.mHandler);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("trust", this.mService);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (isSafeMode()) {
            return;
        }
        if (phase == 500) {
            this.mPackageMonitor.register(this.mContext, this.mHandler.getLooper(), UserHandle.ALL, true);
            this.mReceiver.register(this.mContext);
            this.mLockPatternUtils.registerStrongAuthTracker(this.mStrongAuthTracker);
        } else if (phase == 600) {
            this.mTrustAgentsCanRun = true;
            refreshAgentList(-1);
            refreshDeviceLockedForUser(-1);
        } else if (phase == 1000) {
            maybeEnableFactoryTrustAgents(this.mLockPatternUtils, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class SettingsObserver extends ContentObserver {
        private final Uri LOCK_SCREEN_WHEN_TRUST_LOST;
        private final Uri TRUST_AGENTS_EXTEND_UNLOCK;
        private final ContentResolver mContentResolver;
        private final boolean mIsAutomotive;
        private boolean mLockWhenTrustLost;
        private boolean mTrustAgentsExtendUnlock;

        SettingsObserver(Handler handler) {
            super(handler);
            this.TRUST_AGENTS_EXTEND_UNLOCK = Settings.Secure.getUriFor("trust_agents_extend_unlock");
            this.LOCK_SCREEN_WHEN_TRUST_LOST = Settings.Secure.getUriFor("lock_screen_when_trust_lost");
            PackageManager packageManager = TrustManagerService.this.getContext().getPackageManager();
            this.mIsAutomotive = packageManager.hasSystemFeature("android.hardware.type.automotive");
            this.mContentResolver = TrustManagerService.this.getContext().getContentResolver();
            updateContentObserver();
        }

        void updateContentObserver() {
            this.mContentResolver.unregisterContentObserver(this);
            this.mContentResolver.registerContentObserver(this.TRUST_AGENTS_EXTEND_UNLOCK, false, this, TrustManagerService.this.mCurrentUser);
            this.mContentResolver.registerContentObserver(this.LOCK_SCREEN_WHEN_TRUST_LOST, false, this, TrustManagerService.this.mCurrentUser);
            onChange(true, this.TRUST_AGENTS_EXTEND_UNLOCK);
            onChange(true, this.LOCK_SCREEN_WHEN_TRUST_LOST);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            if (this.TRUST_AGENTS_EXTEND_UNLOCK.equals(uri)) {
                int defaultValue = !this.mIsAutomotive ? 1 : 0;
                this.mTrustAgentsExtendUnlock = Settings.Secure.getIntForUser(this.mContentResolver, "trust_agents_extend_unlock", defaultValue, TrustManagerService.this.mCurrentUser) != 0;
            } else if (this.LOCK_SCREEN_WHEN_TRUST_LOST.equals(uri)) {
                this.mLockWhenTrustLost = Settings.Secure.getIntForUser(this.mContentResolver, "lock_screen_when_trust_lost", 0, TrustManagerService.this.mCurrentUser) != 0;
            }
        }

        boolean getTrustAgentsExtendUnlock() {
            return this.mTrustAgentsExtendUnlock;
        }

        boolean getLockWhenTrustLost() {
            return this.mLockWhenTrustLost;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeLockScreen(int userId) {
        if (userId == this.mCurrentUser && this.mSettingsObserver.getLockWhenTrustLost()) {
            if (DEBUG) {
                Slog.d(TAG, "Locking device because trust was lost");
            }
            try {
                WindowManagerGlobal.getWindowManagerService().lockNow((Bundle) null);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error locking screen when trust was lost");
            }
            TrustTimeoutAlarmListener alarm = this.mTrustTimeoutAlarmListenerForUser.get(Integer.valueOf(userId));
            if (alarm != null && this.mSettingsObserver.getTrustAgentsExtendUnlock()) {
                this.mAlarmManager.cancel(alarm);
                alarm.setQueued(false);
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v0 */
    /* JADX WARN: Type inference failed for: r0v1, types: [int] */
    /* JADX WARN: Type inference failed for: r0v2 */
    /* JADX WARN: Type inference failed for: r1v0, types: [android.os.Handler] */
    private void scheduleTrustTimeout(int userId, boolean override) {
        ?? r0 = override;
        if (override) {
            r0 = 1;
        }
        this.mHandler.obtainMessage(15, userId, r0).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleScheduleTrustTimeout(int userId, int shouldOverride) {
        long when = SystemClock.elapsedRealtime() + 14400000;
        int userId2 = this.mCurrentUser;
        TrustTimeoutAlarmListener alarm = this.mTrustTimeoutAlarmListenerForUser.get(Integer.valueOf(userId2));
        if (alarm != null) {
            if (shouldOverride == 0 && alarm.isQueued()) {
                if (DEBUG) {
                    Slog.d(TAG, "Found existing trust timeout alarm. Skipping.");
                    return;
                }
                return;
            }
            this.mAlarmManager.cancel(alarm);
        } else {
            alarm = new TrustTimeoutAlarmListener(userId2);
            this.mTrustTimeoutAlarmListenerForUser.put(Integer.valueOf(userId2), alarm);
        }
        if (DEBUG) {
            Slog.d(TAG, "\tSetting up trust timeout alarm");
        }
        alarm.setQueued(true);
        this.mAlarmManager.setExact(2, when, TRUST_TIMEOUT_ALARM_TAG, alarm, this.mHandler);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class AgentInfo {
        TrustAgentWrapper agent;
        ComponentName component;
        Drawable icon;
        CharSequence label;
        SettingsAttrs settings;
        int userId;

        private AgentInfo() {
        }

        /* synthetic */ AgentInfo(AnonymousClass1 x0) {
            this();
        }

        public boolean equals(Object other) {
            if (other instanceof AgentInfo) {
                AgentInfo o = (AgentInfo) other;
                return this.component.equals(o.component) && this.userId == o.userId;
            }
            return false;
        }

        public int hashCode() {
            return (this.component.hashCode() * 31) + this.userId;
        }
    }

    private void updateTrustAll() {
        List<UserInfo> userInfos = this.mUserManager.getUsers(true);
        for (UserInfo userInfo : userInfos) {
            updateTrust(userInfo.id, 0);
        }
    }

    public void updateTrust(int userId, int flags) {
        updateTrust(userId, flags, false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateTrust(int userId, int flags, boolean isFromUnlock) {
        boolean changed;
        boolean managed = aggregateIsTrustManaged(userId);
        dispatchOnTrustManagedChanged(managed, userId);
        if (this.mStrongAuthTracker.isTrustAllowedForUser(userId) && isTrustUsuallyManagedInternal(userId) != managed) {
            updateTrustUsuallyManaged(userId, managed);
        }
        boolean trusted = aggregateIsTrusted(userId);
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        boolean showingKeyguard = true;
        try {
            showingKeyguard = wm.isKeyguardLocked();
        } catch (RemoteException e) {
        }
        synchronized (this.mUserIsTrusted) {
            boolean z = true;
            if (this.mSettingsObserver.getTrustAgentsExtendUnlock()) {
                boolean changed2 = this.mUserIsTrusted.get(userId) != trusted;
                trusted = trusted && !(showingKeyguard && !isFromUnlock && changed2) && userId == this.mCurrentUser;
                if (DEBUG) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Extend unlock setting trusted as ");
                    sb.append(Boolean.toString(trusted));
                    sb.append(" && ");
                    sb.append(Boolean.toString(!showingKeyguard));
                    sb.append(" && ");
                    sb.append(Boolean.toString(userId == this.mCurrentUser));
                    Slog.d(TAG, sb.toString());
                }
            }
            if (this.mUserIsTrusted.get(userId) == trusted) {
                z = false;
            }
            changed = z;
            this.mUserIsTrusted.put(userId, trusted);
        }
        dispatchOnTrustChanged(trusted, userId, flags);
        if (changed) {
            refreshDeviceLockedForUser(userId);
            if (!trusted) {
                maybeLockScreen(userId);
            } else {
                scheduleTrustTimeout(userId, false);
            }
        }
    }

    private void updateTrustUsuallyManaged(int userId, boolean managed) {
        synchronized (this.mTrustUsuallyManagedForUser) {
            this.mTrustUsuallyManagedForUser.put(userId, managed);
        }
        this.mHandler.removeMessages(10);
        Handler handler = this.mHandler;
        handler.sendMessageDelayed(handler.obtainMessage(10), JobStatus.DEFAULT_TRIGGER_MAX_DELAY);
    }

    public long addEscrowToken(byte[] token, int userId) {
        return this.mLockPatternUtils.addEscrowToken(token, userId, new LockPatternUtils.EscrowTokenStateChangeCallback() { // from class: com.android.server.trust.-$$Lambda$TrustManagerService$fEkVwjahpkATIGtXudiFOG8VXOo
            public final void onEscrowTokenActivated(long j, int i) {
                TrustManagerService.this.lambda$addEscrowToken$0$TrustManagerService(j, i);
            }
        });
    }

    public boolean removeEscrowToken(long handle, int userId) {
        return this.mLockPatternUtils.removeEscrowToken(handle, userId);
    }

    public boolean isEscrowTokenActive(long handle, int userId) {
        return this.mLockPatternUtils.isEscrowTokenActive(handle, userId);
    }

    public void unlockUserWithToken(long handle, byte[] token, int userId) {
        this.mLockPatternUtils.unlockUserWithToken(handle, token, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void showKeyguardErrorMessage(CharSequence message) {
        dispatchOnTrustError(message);
    }

    void refreshAgentList(int userIdOrAll) {
        List<UserInfo> userInfos;
        Iterator<UserInfo> it;
        PackageManager pm;
        int userIdOrAll2 = userIdOrAll;
        if (DEBUG) {
            Slog.d(TAG, "refreshAgentList(" + userIdOrAll2 + ")");
        }
        if (!this.mTrustAgentsCanRun) {
            return;
        }
        if (userIdOrAll2 != -1 && userIdOrAll2 < 0) {
            Log.e(TAG, "refreshAgentList(userId=" + userIdOrAll2 + "): Invalid user handle, must be USER_ALL or a specific user.", new Throwable("here"));
            userIdOrAll2 = -1;
        }
        PackageManager pm2 = this.mContext.getPackageManager();
        boolean z = true;
        if (userIdOrAll2 == -1) {
            userInfos = this.mUserManager.getUsers(true);
        } else {
            userInfos = new ArrayList<>();
            userInfos.add(this.mUserManager.getUserInfo(userIdOrAll2));
        }
        LockPatternUtils lockPatternUtils = this.mLockPatternUtils;
        ArraySet<AgentInfo> obsoleteAgents = new ArraySet<>();
        obsoleteAgents.addAll(this.mActiveAgents);
        Iterator<UserInfo> it2 = userInfos.iterator();
        while (it2.hasNext()) {
            UserInfo userInfo = it2.next();
            if (userInfo == null || userInfo.partial || !userInfo.isEnabled()) {
                z = true;
            } else if (!userInfo.guestToRemove) {
                if (!userInfo.supportsSwitchToByUser()) {
                    if (DEBUG) {
                        Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id + ": switchToByUser=false");
                    }
                } else if (!this.mActivityManager.isUserRunning(userInfo.id)) {
                    if (DEBUG) {
                        Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id + ": user not started");
                    }
                } else if (!lockPatternUtils.isSecure(userInfo.id)) {
                    if (DEBUG) {
                        Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id + ": no secure credential");
                    }
                } else {
                    DevicePolicyManager dpm = lockPatternUtils.getDevicePolicyManager();
                    int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, userInfo.id);
                    boolean disableTrustAgents = (disabledFeatures & 16) != 0 ? z : false;
                    List<ComponentName> enabledAgents = lockPatternUtils.getEnabledTrustAgents(userInfo.id);
                    if (enabledAgents != null) {
                        List<ResolveInfo> resolveInfos = resolveAllowedTrustAgents(pm2, userInfo.id);
                        for (ResolveInfo resolveInfo : resolveInfos) {
                            ComponentName name = getComponentName(resolveInfo);
                            List<ResolveInfo> resolveInfos2 = resolveInfos;
                            List<UserInfo> userInfos2 = userInfos;
                            if (!enabledAgents.contains(name)) {
                                if (DEBUG) {
                                    LockPatternUtils lockPatternUtils2 = lockPatternUtils;
                                    Slog.d(TAG, "refreshAgentList: skipping " + name.flattenToShortString() + " u" + userInfo.id + ": not enabled by user");
                                    lockPatternUtils = lockPatternUtils2;
                                }
                                resolveInfos = resolveInfos2;
                                userInfos = userInfos2;
                            } else {
                                LockPatternUtils lockPatternUtils3 = lockPatternUtils;
                                if (!disableTrustAgents) {
                                    it = it2;
                                } else {
                                    it = it2;
                                    List<PersistableBundle> config = dpm.getTrustAgentConfiguration(null, name, userInfo.id);
                                    if (config == null || config.isEmpty()) {
                                        if (DEBUG) {
                                            Slog.d(TAG, "refreshAgentList: skipping " + name.flattenToShortString() + " u" + userInfo.id + ": not allowed by DPM");
                                        }
                                        lockPatternUtils = lockPatternUtils3;
                                        resolveInfos = resolveInfos2;
                                        userInfos = userInfos2;
                                        it2 = it;
                                    }
                                }
                                AgentInfo agentInfo = new AgentInfo(null);
                                agentInfo.component = name;
                                agentInfo.userId = userInfo.id;
                                if (!this.mActiveAgents.contains(agentInfo)) {
                                    agentInfo.label = resolveInfo.loadLabel(pm2);
                                    agentInfo.icon = resolveInfo.loadIcon(pm2);
                                    agentInfo.settings = getSettingsAttrs(pm2, resolveInfo);
                                } else {
                                    int index = this.mActiveAgents.indexOf(agentInfo);
                                    agentInfo = this.mActiveAgents.valueAt(index);
                                }
                                boolean directUnlock = false;
                                if (agentInfo.settings != null) {
                                    directUnlock = resolveInfo.serviceInfo.directBootAware && agentInfo.settings.canUnlockProfile;
                                }
                                if (directUnlock && DEBUG) {
                                    Slog.d(TAG, "refreshAgentList: trustagent " + name + "of user " + userInfo.id + "can unlock user profile.");
                                }
                                if (!this.mUserManager.isUserUnlockingOrUnlocked(userInfo.id) && !directUnlock) {
                                    if (DEBUG) {
                                        Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id + "'s trust agent " + name + ": FBE still locked and  the agent cannot unlock user profile.");
                                    }
                                    lockPatternUtils = lockPatternUtils3;
                                    resolveInfos = resolveInfos2;
                                    userInfos = userInfos2;
                                    it2 = it;
                                } else {
                                    if (this.mStrongAuthTracker.canAgentsRunForUser(userInfo.id)) {
                                        pm = pm2;
                                    } else {
                                        int flag = this.mStrongAuthTracker.getStrongAuthForUser(userInfo.id);
                                        if (flag == 8) {
                                            pm = pm2;
                                        } else if (flag == 1 && directUnlock) {
                                            pm = pm2;
                                        } else if (DEBUG) {
                                            Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id + ": prevented by StrongAuthTracker = 0x" + Integer.toHexString(this.mStrongAuthTracker.getStrongAuthForUser(userInfo.id)));
                                            lockPatternUtils = lockPatternUtils3;
                                            resolveInfos = resolveInfos2;
                                            userInfos = userInfos2;
                                            it2 = it;
                                            pm2 = pm2;
                                        } else {
                                            lockPatternUtils = lockPatternUtils3;
                                            resolveInfos = resolveInfos2;
                                            userInfos = userInfos2;
                                            it2 = it;
                                        }
                                    }
                                    if (agentInfo.agent == null) {
                                        agentInfo.agent = new TrustAgentWrapper(this.mContext, this, new Intent().setComponent(name), userInfo.getUserHandle());
                                    }
                                    if (!this.mActiveAgents.contains(agentInfo)) {
                                        this.mActiveAgents.add(agentInfo);
                                    } else {
                                        obsoleteAgents.remove(agentInfo);
                                    }
                                    lockPatternUtils = lockPatternUtils3;
                                    resolveInfos = resolveInfos2;
                                    userInfos = userInfos2;
                                    it2 = it;
                                    pm2 = pm;
                                }
                            }
                        }
                        z = true;
                    } else if (DEBUG) {
                        Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id + ": no agents enabled by user");
                    }
                }
            }
        }
        boolean trustMayHaveChanged = false;
        for (int i = 0; i < obsoleteAgents.size(); i++) {
            AgentInfo info = obsoleteAgents.valueAt(i);
            if (userIdOrAll2 == -1 || userIdOrAll2 == info.userId) {
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                this.mActiveAgents.remove(info);
            }
        }
        if (trustMayHaveChanged) {
            if (userIdOrAll2 != -1) {
                updateTrust(userIdOrAll2, 0);
            } else {
                updateTrustAll();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDeviceLockedInner(int userId) {
        boolean z;
        synchronized (this.mDeviceLockedForUser) {
            z = this.mDeviceLockedForUser.get(userId, true);
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refreshDeviceLockedForUser(int userId) {
        List<UserInfo> userInfos;
        if (userId != -1 && userId < 0) {
            Log.e(TAG, "refreshDeviceLockedForUser(userId=" + userId + "): Invalid user handle, must be USER_ALL or a specific user.", new Throwable("here"));
            userId = -1;
        }
        if (userId == -1) {
            userInfos = this.mUserManager.getUsers(true);
        } else {
            userInfos = new ArrayList<>();
            userInfos.add(this.mUserManager.getUserInfo(userId));
        }
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        for (int i = 0; i < userInfos.size(); i++) {
            UserInfo info = userInfos.get(i);
            if (info != null && !info.partial && info.isEnabled() && !info.guestToRemove) {
                int id = info.id;
                boolean secure = this.mLockPatternUtils.isSecure(id);
                boolean deviceLocked = false;
                if (!info.supportsSwitchToByUser()) {
                    if (info.isManagedProfile() && !secure) {
                        setDeviceLockedForUser(id, false);
                    }
                } else {
                    boolean trusted = aggregateIsTrusted(id);
                    boolean showingKeyguard = true;
                    boolean biometricAuthenticated = false;
                    if (this.mCurrentUser == id) {
                        synchronized (this.mUsersUnlockedByBiometric) {
                            biometricAuthenticated = this.mUsersUnlockedByBiometric.get(id, false);
                        }
                        try {
                            showingKeyguard = wm.isKeyguardLocked();
                        } catch (RemoteException e) {
                        }
                    }
                    if (secure && showingKeyguard && !trusted && !biometricAuthenticated) {
                        deviceLocked = true;
                    }
                    setDeviceLockedForUser(id, deviceLocked);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setDeviceLockedForUser(int userId, boolean locked) {
        int i;
        boolean changed;
        int[] enabledProfileIds;
        synchronized (this.mDeviceLockedForUser) {
            changed = isDeviceLockedInner(userId) != locked;
            this.mDeviceLockedForUser.put(userId, locked);
        }
        if (changed) {
            dispatchDeviceLocked(userId, locked);
            KeyStore.getInstance().onUserLockedStateChanged(userId, locked);
            for (int profileHandle : this.mUserManager.getEnabledProfileIds(userId)) {
                if (this.mLockPatternUtils.isManagedProfileWithUnifiedChallenge(profileHandle)) {
                    KeyStore.getInstance().onUserLockedStateChanged(profileHandle, locked);
                }
            }
        }
    }

    private void dispatchDeviceLocked(int userId, boolean isLocked) {
        for (int i = 0; i < this.mActiveAgents.size(); i++) {
            AgentInfo agent = this.mActiveAgents.valueAt(i);
            if (agent.userId == userId) {
                if (isLocked) {
                    agent.agent.onDeviceLocked();
                } else {
                    agent.agent.onDeviceUnlocked();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: dispatchEscrowTokenActivatedLocked */
    public void lambda$addEscrowToken$0$TrustManagerService(long handle, int userId) {
        for (int i = 0; i < this.mActiveAgents.size(); i++) {
            AgentInfo agent = this.mActiveAgents.valueAt(i);
            if (agent.userId == userId) {
                agent.agent.onEscrowTokenActivated(handle, userId);
            }
        }
    }

    void updateDevicePolicyFeatures() {
        boolean changed = false;
        for (int i = 0; i < this.mActiveAgents.size(); i++) {
            AgentInfo info = this.mActiveAgents.valueAt(i);
            if (info.agent.isConnected()) {
                info.agent.updateDevicePolicyFeatures();
                changed = true;
            }
        }
        if (changed) {
            this.mArchive.logDevicePolicyChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeAgentsOfPackage(String packageName) {
        boolean trustMayHaveChanged = false;
        for (int i = this.mActiveAgents.size() - 1; i >= 0; i--) {
            AgentInfo info = this.mActiveAgents.valueAt(i);
            if (packageName.equals(info.component.getPackageName())) {
                Log.i(TAG, "Resetting agent " + info.component.flattenToShortString());
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                this.mActiveAgents.removeAt(i);
            }
        }
        if (trustMayHaveChanged) {
            updateTrustAll();
        }
    }

    public void resetAgent(ComponentName name, int userId) {
        boolean trustMayHaveChanged = false;
        for (int i = this.mActiveAgents.size() - 1; i >= 0; i--) {
            AgentInfo info = this.mActiveAgents.valueAt(i);
            if (name.equals(info.component) && userId == info.userId) {
                Log.i(TAG, "Resetting agent " + info.component.flattenToShortString());
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                this.mActiveAgents.removeAt(i);
            }
        }
        if (trustMayHaveChanged) {
            updateTrust(userId, 0);
        }
        refreshAgentList(userId);
    }

    /* JADX WARN: Code restructure failed: missing block: B:34:0x007d, code lost:
        if (0 == 0) goto L29;
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x0082, code lost:
        if (0 == 0) goto L29;
     */
    /* JADX WARN: Code restructure failed: missing block: B:42:0x0087, code lost:
        if (0 == 0) goto L29;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.trust.TrustManagerService.SettingsAttrs getSettingsAttrs(android.content.pm.PackageManager r14, android.content.pm.ResolveInfo r15) {
        /*
            r13 = this;
            java.lang.String r0 = "TrustManagerService"
            r1 = 0
            if (r15 == 0) goto Ld2
            android.content.pm.ServiceInfo r2 = r15.serviceInfo
            if (r2 == 0) goto Ld2
            android.content.pm.ServiceInfo r2 = r15.serviceInfo
            android.os.Bundle r2 = r2.metaData
            if (r2 != 0) goto L11
            goto Ld2
        L11:
            r2 = 0
            r3 = 0
            r4 = 0
            r5 = 0
            android.content.pm.ServiceInfo r6 = r15.serviceInfo     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            java.lang.String r7 = "android.service.trust.trustagent"
            android.content.res.XmlResourceParser r6 = r6.loadXmlMetaData(r14, r7)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            r4 = r6
            if (r4 != 0) goto L2c
            java.lang.String r6 = "Can't find android.service.trust.trustagent meta-data"
            android.util.Slog.w(r0, r6)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            if (r4 == 0) goto L2b
            r4.close()
        L2b:
            return r1
        L2c:
            android.content.pm.ServiceInfo r6 = r15.serviceInfo     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            android.content.pm.ApplicationInfo r6 = r6.applicationInfo     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            android.content.res.Resources r6 = r14.getResourcesForApplication(r6)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            android.util.AttributeSet r7 = android.util.Xml.asAttributeSet(r4)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
        L38:
            int r8 = r4.next()     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            r9 = r8
            r10 = 1
            r11 = 2
            if (r8 == r10) goto L44
            if (r9 == r11) goto L44
            goto L38
        L44:
            java.lang.String r8 = r4.getName()     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            java.lang.String r10 = "trust-agent"
            boolean r10 = r10.equals(r8)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            if (r10 != 0) goto L5a
            java.lang.String r10 = "Meta-data does not start with trust-agent tag"
            android.util.Slog.w(r0, r10)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            r4.close()
            return r1
        L5a:
            int[] r10 = com.android.internal.R.styleable.TrustAgent     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            android.content.res.TypedArray r10 = r6.obtainAttributes(r7, r10)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            java.lang.String r11 = r10.getString(r11)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            r2 = r11
            r11 = 3
            r12 = 0
            boolean r11 = r10.getBoolean(r11, r12)     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
            r3 = r11
            r10.recycle()     // Catch: java.lang.Throwable -> L74 org.xmlpull.v1.XmlPullParserException -> L7b java.io.IOException -> L80 android.content.pm.PackageManager.NameNotFoundException -> L85
        L70:
            r4.close()
            goto L8a
        L74:
            r0 = move-exception
            if (r4 == 0) goto L7a
            r4.close()
        L7a:
            throw r0
        L7b:
            r6 = move-exception
            r5 = r6
            if (r4 == 0) goto L8a
            goto L70
        L80:
            r6 = move-exception
            r5 = r6
            if (r4 == 0) goto L8a
            goto L70
        L85:
            r6 = move-exception
            r5 = r6
            if (r4 == 0) goto L8a
            goto L70
        L8a:
            if (r5 == 0) goto La5
            java.lang.StringBuilder r6 = new java.lang.StringBuilder
            r6.<init>()
            java.lang.String r7 = "Error parsing : "
            r6.append(r7)
            android.content.pm.ServiceInfo r7 = r15.serviceInfo
            java.lang.String r7 = r7.packageName
            r6.append(r7)
            java.lang.String r6 = r6.toString()
            android.util.Slog.w(r0, r6, r5)
            return r1
        La5:
            if (r2 != 0) goto La8
            return r1
        La8:
            r0 = 47
            int r0 = r2.indexOf(r0)
            if (r0 >= 0) goto Lc8
            java.lang.StringBuilder r0 = new java.lang.StringBuilder
            r0.<init>()
            android.content.pm.ServiceInfo r1 = r15.serviceInfo
            java.lang.String r1 = r1.packageName
            r0.append(r1)
            java.lang.String r1 = "/"
            r0.append(r1)
            r0.append(r2)
            java.lang.String r2 = r0.toString()
        Lc8:
            com.android.server.trust.TrustManagerService$SettingsAttrs r0 = new com.android.server.trust.TrustManagerService$SettingsAttrs
            android.content.ComponentName r1 = android.content.ComponentName.unflattenFromString(r2)
            r0.<init>(r1, r3)
            return r0
        Ld2:
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.trust.TrustManagerService.getSettingsAttrs(android.content.pm.PackageManager, android.content.pm.ResolveInfo):com.android.server.trust.TrustManagerService$SettingsAttrs");
    }

    private ComponentName getComponentName(ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            return null;
        }
        return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeEnableFactoryTrustAgents(LockPatternUtils utils, int userId) {
        if (Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "trust_agents_initialized", 0, userId) != 0) {
            return;
        }
        PackageManager pm = this.mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = resolveAllowedTrustAgents(pm, userId);
        ComponentName defaultAgent = getDefaultFactoryTrustAgent(this.mContext);
        boolean shouldUseDefaultAgent = defaultAgent != null;
        ArraySet<ComponentName> discoveredAgents = new ArraySet<>();
        if (shouldUseDefaultAgent) {
            discoveredAgents.add(defaultAgent);
            Log.i(TAG, "Enabling " + defaultAgent + " because it is a default agent.");
        } else {
            for (ResolveInfo resolveInfo : resolveInfos) {
                ComponentName componentName = getComponentName(resolveInfo);
                int applicationInfoFlags = resolveInfo.serviceInfo.applicationInfo.flags;
                if ((applicationInfoFlags & 1) == 0) {
                    Log.i(TAG, "Leaving agent " + componentName + " disabled because package is not a system package.");
                } else {
                    discoveredAgents.add(componentName);
                }
            }
        }
        List<ComponentName> previouslyEnabledAgents = utils.getEnabledTrustAgents(userId);
        if (previouslyEnabledAgents != null) {
            discoveredAgents.addAll(previouslyEnabledAgents);
        }
        utils.setEnabledTrustAgents(discoveredAgents, userId);
        Settings.Secure.putIntForUser(this.mContext.getContentResolver(), "trust_agents_initialized", 1, userId);
    }

    private static ComponentName getDefaultFactoryTrustAgent(Context context) {
        String defaultTrustAgent = context.getResources().getString(17039716);
        if (TextUtils.isEmpty(defaultTrustAgent)) {
            return null;
        }
        return ComponentName.unflattenFromString(defaultTrustAgent);
    }

    private List<ResolveInfo> resolveAllowedTrustAgents(PackageManager pm, int userId) {
        List<ResolveInfo> resolveInfos = pm.queryIntentServicesAsUser(TRUST_AGENT_INTENT, 786560, userId);
        ArrayList<ResolveInfo> allowedAgents = new ArrayList<>(resolveInfos.size());
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.serviceInfo != null && resolveInfo.serviceInfo.applicationInfo != null) {
                String packageName = resolveInfo.serviceInfo.packageName;
                if (pm.checkPermission(PERMISSION_PROVIDE_AGENT, packageName) != 0) {
                    ComponentName name = getComponentName(resolveInfo);
                    Log.w(TAG, "Skipping agent " + name + " because package does not have permission " + PERMISSION_PROVIDE_AGENT + ".");
                } else {
                    allowedAgents.add(resolveInfo);
                }
            }
        }
        return allowedAgents;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean aggregateIsTrusted(int userId) {
        if (this.mStrongAuthTracker.isTrustAllowedForUser(userId)) {
            for (int i = 0; i < this.mActiveAgents.size(); i++) {
                AgentInfo info = this.mActiveAgents.valueAt(i);
                if (info.userId == userId && info.agent.isTrusted()) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean aggregateIsTrustManaged(int userId) {
        if (this.mStrongAuthTracker.isTrustAllowedForUser(userId)) {
            for (int i = 0; i < this.mActiveAgents.size(); i++) {
                AgentInfo info = this.mActiveAgents.valueAt(i);
                if (info.userId == userId && info.agent.isManagingTrust()) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchUnlockAttempt(boolean successful, int userId) {
        if (successful) {
            this.mStrongAuthTracker.allowTrustFromUnlock(userId);
            updateTrust(userId, 0, true);
        }
        for (int i = 0; i < this.mActiveAgents.size(); i++) {
            AgentInfo info = this.mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUnlockAttempt(successful);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchUnlockLockout(int timeoutMs, int userId) {
        for (int i = 0; i < this.mActiveAgents.size(); i++) {
            AgentInfo info = this.mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUnlockLockout(timeoutMs);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addListener(ITrustListener listener) {
        for (int i = 0; i < this.mTrustListeners.size(); i++) {
            if (this.mTrustListeners.get(i).asBinder() == listener.asBinder()) {
                return;
            }
        }
        this.mTrustListeners.add(listener);
        updateTrustAll();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeListener(ITrustListener listener) {
        for (int i = 0; i < this.mTrustListeners.size(); i++) {
            if (this.mTrustListeners.get(i).asBinder() == listener.asBinder()) {
                this.mTrustListeners.remove(i);
                return;
            }
        }
    }

    private void dispatchOnTrustChanged(boolean enabled, int userId, int flags) {
        if (DEBUG) {
            Log.i(TAG, "onTrustChanged(" + enabled + ", " + userId + ", 0x" + Integer.toHexString(flags) + ")");
        }
        if (!enabled) {
            flags = 0;
        }
        int i = 0;
        while (i < this.mTrustListeners.size()) {
            try {
                this.mTrustListeners.get(i).onTrustChanged(enabled, userId, flags);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                this.mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e2) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e2);
            }
            i++;
        }
    }

    private void dispatchOnTrustManagedChanged(boolean managed, int userId) {
        if (DEBUG) {
            Log.i(TAG, "onTrustManagedChanged(" + managed + ", " + userId + ")");
        }
        int i = 0;
        while (i < this.mTrustListeners.size()) {
            try {
                this.mTrustListeners.get(i).onTrustManagedChanged(managed, userId);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                this.mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e2) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e2);
            }
            i++;
        }
    }

    private void dispatchOnTrustError(CharSequence message) {
        if (DEBUG) {
            Log.i(TAG, "onTrustError(" + ((Object) message) + ")");
        }
        int i = 0;
        while (i < this.mTrustListeners.size()) {
            try {
                this.mTrustListeners.get(i).onTrustError(message);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                this.mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e2) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e2);
            }
            i++;
        }
    }

    @Override // com.android.server.SystemService
    public void onStartUser(int userId) {
        this.mHandler.obtainMessage(7, userId, 0, null).sendToTarget();
    }

    @Override // com.android.server.SystemService
    public void onCleanupUser(int userId) {
        this.mHandler.obtainMessage(8, userId, 0, null).sendToTarget();
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userId) {
        this.mHandler.obtainMessage(9, userId, 0, null).sendToTarget();
    }

    @Override // com.android.server.SystemService
    public void onUnlockUser(int userId) {
        this.mHandler.obtainMessage(11, userId, 0, null).sendToTarget();
    }

    @Override // com.android.server.SystemService
    public void onStopUser(int userId) {
        this.mHandler.obtainMessage(12, userId, 0, null).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.trust.TrustManagerService$1  reason: invalid class name */
    /* loaded from: classes2.dex */
    public class AnonymousClass1 extends ITrustManager.Stub {
        AnonymousClass1() {
        }

        public void reportUnlockAttempt(boolean authenticated, int userId) throws RemoteException {
            enforceReportPermission();
            TrustManagerService.this.mHandler.obtainMessage(3, authenticated ? 1 : 0, userId).sendToTarget();
        }

        public void reportUnlockLockout(int timeoutMs, int userId) throws RemoteException {
            enforceReportPermission();
            TrustManagerService.this.mHandler.obtainMessage(13, timeoutMs, userId).sendToTarget();
        }

        public void reportEnabledTrustAgentsChanged(int userId) throws RemoteException {
            enforceReportPermission();
            TrustManagerService.this.mHandler.removeMessages(4);
            TrustManagerService.this.mHandler.sendEmptyMessage(4);
        }

        public void reportKeyguardShowingChanged() throws RemoteException {
            enforceReportPermission();
            TrustManagerService.this.mHandler.removeMessages(6);
            TrustManagerService.this.mHandler.sendEmptyMessage(6);
            TrustManagerService.this.mHandler.runWithScissors(new Runnable() { // from class: com.android.server.trust.-$$Lambda$TrustManagerService$1$98HKBkg-C1PLlz_Q1vJz1OJtw4c
                @Override // java.lang.Runnable
                public final void run() {
                    TrustManagerService.AnonymousClass1.lambda$reportKeyguardShowingChanged$0();
                }
            }, 0L);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$reportKeyguardShowingChanged$0() {
        }

        public void registerTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            TrustManagerService.this.mHandler.obtainMessage(1, trustListener).sendToTarget();
        }

        public void unregisterTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            TrustManagerService.this.mHandler.obtainMessage(2, trustListener).sendToTarget();
        }

        public boolean isDeviceLocked(int userId) throws RemoteException {
            int userId2 = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId, false, true, "isDeviceLocked", null);
            long token = Binder.clearCallingIdentity();
            try {
                if (!TrustManagerService.this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId2)) {
                    userId2 = TrustManagerService.this.resolveProfileParent(userId2);
                }
                return TrustManagerService.this.isDeviceLockedInner(userId2);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public boolean isDeviceSecure(int userId) throws RemoteException {
            int userId2 = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId, false, true, "isDeviceSecure", null);
            long token = Binder.clearCallingIdentity();
            try {
                if (!TrustManagerService.this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId2)) {
                    userId2 = TrustManagerService.this.resolveProfileParent(userId2);
                }
                return TrustManagerService.this.mLockPatternUtils.isSecure(userId2);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void enforceReportPermission() {
            TrustManagerService.this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_KEYGUARD_SECURE_STORAGE", "reporting trust events");
        }

        private void enforceListenerPermission() {
            TrustManagerService.this.mContext.enforceCallingPermission("android.permission.TRUST_LISTENER", "register trust listener");
        }

        protected void dump(FileDescriptor fd, final PrintWriter fout, String[] args) {
            if (DumpUtils.checkDumpPermission(TrustManagerService.this.mContext, TrustManagerService.TAG, fout)) {
                if (!TrustManagerService.this.isSafeMode()) {
                    if (TrustManagerService.this.mTrustAgentsCanRun) {
                        final List<UserInfo> userInfos = TrustManagerService.this.mUserManager.getUsers(true);
                        TrustManagerService.this.mHandler.runWithScissors(new Runnable() { // from class: com.android.server.trust.TrustManagerService.1.1
                            @Override // java.lang.Runnable
                            public void run() {
                                fout.println("Trust manager state:");
                                for (UserInfo user : userInfos) {
                                    AnonymousClass1.this.dumpUser(fout, user, user.id == TrustManagerService.this.mCurrentUser);
                                }
                            }
                        }, 1500L);
                        return;
                    }
                    fout.println("disabled because the third-party apps can't run yet.");
                    return;
                }
                fout.println("disabled because the system is in safe mode.");
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dumpUser(PrintWriter fout, UserInfo user, boolean isCurrent) {
            fout.printf(" User \"%s\" (id=%d, flags=%#x)", user.name, Integer.valueOf(user.id), Integer.valueOf(user.flags));
            if (!user.supportsSwitchToByUser()) {
                fout.println("(managed profile)");
                fout.println("   disabled because switching to this user is not possible.");
                return;
            }
            if (isCurrent) {
                fout.print(" (current)");
            }
            fout.print(": trusted=" + dumpBool(TrustManagerService.this.aggregateIsTrusted(user.id)));
            fout.print(", trustManaged=" + dumpBool(TrustManagerService.this.aggregateIsTrustManaged(user.id)));
            fout.print(", deviceLocked=" + dumpBool(TrustManagerService.this.isDeviceLockedInner(user.id)));
            fout.print(", strongAuthRequired=" + dumpHex(TrustManagerService.this.mStrongAuthTracker.getStrongAuthForUser(user.id)));
            fout.println();
            fout.println("   Enabled agents:");
            boolean duplicateSimpleNames = false;
            ArraySet<String> simpleNames = new ArraySet<>();
            Iterator it = TrustManagerService.this.mActiveAgents.iterator();
            while (it.hasNext()) {
                AgentInfo info = (AgentInfo) it.next();
                if (info.userId == user.id) {
                    boolean trusted = info.agent.isTrusted();
                    fout.print("    ");
                    fout.println(info.component.flattenToShortString());
                    fout.print("     bound=" + dumpBool(info.agent.isBound()));
                    fout.print(", connected=" + dumpBool(info.agent.isConnected()));
                    fout.print(", managingTrust=" + dumpBool(info.agent.isManagingTrust()));
                    fout.print(", trusted=" + dumpBool(trusted));
                    fout.println();
                    if (trusted) {
                        fout.println("      message=\"" + ((Object) info.agent.getMessage()) + "\"");
                    }
                    if (!info.agent.isConnected()) {
                        String restartTime = TrustArchive.formatDuration(info.agent.getScheduledRestartUptimeMillis() - SystemClock.uptimeMillis());
                        fout.println("      restartScheduledAt=" + restartTime);
                    }
                    if (!simpleNames.add(TrustArchive.getSimpleName(info.component))) {
                        duplicateSimpleNames = true;
                    }
                }
            }
            fout.println("   Events:");
            TrustManagerService.this.mArchive.dump(fout, 50, user.id, "    ", duplicateSimpleNames);
            fout.println();
        }

        private String dumpBool(boolean b) {
            return b ? "1" : "0";
        }

        private String dumpHex(int i) {
            return "0x" + Integer.toHexString(i);
        }

        public void setDeviceLockedForUser(int userId, boolean locked) {
            enforceReportPermission();
            long identity = Binder.clearCallingIdentity();
            try {
                if (TrustManagerService.this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId) && TrustManagerService.this.mLockPatternUtils.isSecure(userId)) {
                    synchronized (TrustManagerService.this.mDeviceLockedForUser) {
                        TrustManagerService.this.mDeviceLockedForUser.put(userId, locked);
                    }
                    KeyStore.getInstance().onUserLockedStateChanged(userId, locked);
                    if (locked) {
                        try {
                            ActivityManager.getService().notifyLockedProfile(userId);
                        } catch (RemoteException e) {
                        }
                    }
                    Intent lockIntent = new Intent("android.intent.action.DEVICE_LOCKED_CHANGED");
                    lockIntent.addFlags(1073741824);
                    lockIntent.putExtra("android.intent.extra.user_handle", userId);
                    TrustManagerService.this.mContext.sendBroadcastAsUser(lockIntent, UserHandle.SYSTEM, "android.permission.TRUST_LISTENER", null);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public boolean isTrustUsuallyManaged(int userId) {
            TrustManagerService.this.mContext.enforceCallingPermission("android.permission.TRUST_LISTENER", "query trust state");
            return TrustManagerService.this.isTrustUsuallyManagedInternal(userId);
        }

        public void unlockedByBiometricForUser(int userId, BiometricSourceType biometricSource) {
            enforceReportPermission();
            synchronized (TrustManagerService.this.mUsersUnlockedByBiometric) {
                TrustManagerService.this.mUsersUnlockedByBiometric.put(userId, true);
            }
            boolean trustAgentsExtendUnlock = TrustManagerService.this.mSettingsObserver.getTrustAgentsExtendUnlock();
            Handler handler = TrustManagerService.this.mHandler;
            int updateTrustOnUnlock = trustAgentsExtendUnlock ? 1 : 0;
            handler.obtainMessage(14, userId, updateTrustOnUnlock).sendToTarget();
        }

        public void clearAllBiometricRecognized(BiometricSourceType biometricSource) {
            enforceReportPermission();
            synchronized (TrustManagerService.this.mUsersUnlockedByBiometric) {
                TrustManagerService.this.mUsersUnlockedByBiometric.clear();
            }
            TrustManagerService.this.mHandler.obtainMessage(14, -1, 0).sendToTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isTrustUsuallyManagedInternal(int userId) {
        synchronized (this.mTrustUsuallyManagedForUser) {
            int i = this.mTrustUsuallyManagedForUser.indexOfKey(userId);
            if (i >= 0) {
                return this.mTrustUsuallyManagedForUser.valueAt(i);
            }
            boolean persistedValue = this.mLockPatternUtils.isTrustUsuallyManaged(userId);
            synchronized (this.mTrustUsuallyManagedForUser) {
                int i2 = this.mTrustUsuallyManagedForUser.indexOfKey(userId);
                if (i2 >= 0) {
                    return this.mTrustUsuallyManagedForUser.valueAt(i2);
                }
                this.mTrustUsuallyManagedForUser.put(userId, persistedValue);
                return persistedValue;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int resolveProfileParent(int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            UserInfo parent = this.mUserManager.getProfileParent(userId);
            if (parent != null) {
                return parent.getUserHandle().getIdentifier();
            }
            return userId;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class SettingsAttrs {
        public boolean canUnlockProfile;
        public ComponentName componentName;

        public SettingsAttrs(ComponentName componentName, boolean canUnlockProfile) {
            this.componentName = componentName;
            this.canUnlockProfile = canUnlockProfile;
        }
    }

    /* loaded from: classes2.dex */
    private class Receiver extends BroadcastReceiver {
        private Receiver() {
        }

        /* synthetic */ Receiver(TrustManagerService x0, AnonymousClass1 x1) {
            this();
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            int userId;
            String action = intent.getAction();
            if ("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED".equals(action)) {
                TrustManagerService.this.refreshAgentList(getSendingUserId());
                TrustManagerService.this.updateDevicePolicyFeatures();
            } else if ("android.intent.action.USER_ADDED".equals(action) || "android.intent.action.USER_STARTED".equals(action)) {
                int userId2 = getUserId(intent);
                if (userId2 > 0) {
                    TrustManagerService trustManagerService = TrustManagerService.this;
                    trustManagerService.maybeEnableFactoryTrustAgents(trustManagerService.mLockPatternUtils, userId2);
                }
            } else if ("android.intent.action.USER_REMOVED".equals(action) && (userId = getUserId(intent)) > 0) {
                synchronized (TrustManagerService.this.mUserIsTrusted) {
                    TrustManagerService.this.mUserIsTrusted.delete(userId);
                }
                synchronized (TrustManagerService.this.mDeviceLockedForUser) {
                    TrustManagerService.this.mDeviceLockedForUser.delete(userId);
                }
                synchronized (TrustManagerService.this.mTrustUsuallyManagedForUser) {
                    TrustManagerService.this.mTrustUsuallyManagedForUser.delete(userId);
                }
                synchronized (TrustManagerService.this.mUsersUnlockedByBiometric) {
                    TrustManagerService.this.mUsersUnlockedByBiometric.delete(userId);
                }
                TrustManagerService.this.refreshAgentList(userId);
                TrustManagerService.this.refreshDeviceLockedForUser(userId);
            }
        }

        private int getUserId(Intent intent) {
            int userId = intent.getIntExtra("android.intent.extra.user_handle", -100);
            if (userId > 0) {
                return userId;
            }
            if (Build.IS_USER) {
                Slog.w(TrustManagerService.TAG, "EXTRA_USER_HANDLE missing or invalid, value=" + userId);
            } else {
                Slog.wtf(TrustManagerService.TAG, "EXTRA_USER_HANDLE missing or invalid, value=" + userId);
            }
            return -100;
        }

        public void register(Context context) {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED");
            filter.addAction("android.intent.action.USER_ADDED");
            filter.addAction("android.intent.action.USER_REMOVED");
            filter.addAction("android.intent.action.USER_STARTED");
            context.registerReceiverAsUser(this, UserHandle.ALL, filter, null, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class StrongAuthTracker extends LockPatternUtils.StrongAuthTracker {
        SparseBooleanArray mStartFromSuccessfulUnlock;

        public StrongAuthTracker(Context context) {
            super(context);
            this.mStartFromSuccessfulUnlock = new SparseBooleanArray();
        }

        public void onStrongAuthRequiredChanged(int userId) {
            TrustTimeoutAlarmListener alarm;
            this.mStartFromSuccessfulUnlock.delete(userId);
            if (TrustManagerService.DEBUG) {
                Log.i(TrustManagerService.TAG, "onStrongAuthRequiredChanged(" + userId + ") -> trustAllowed=" + isTrustAllowedForUser(userId) + " agentsCanRun=" + canAgentsRunForUser(userId));
            }
            if (!isTrustAllowedForUser(userId) && (alarm = (TrustTimeoutAlarmListener) TrustManagerService.this.mTrustTimeoutAlarmListenerForUser.get(Integer.valueOf(userId))) != null && alarm.isQueued()) {
                alarm.setQueued(false);
                TrustManagerService.this.mAlarmManager.cancel(alarm);
            }
            TrustManagerService.this.refreshAgentList(userId);
            TrustManagerService.this.updateTrust(userId, 0);
        }

        boolean canAgentsRunForUser(int userId) {
            return this.mStartFromSuccessfulUnlock.get(userId) || super.isTrustAllowedForUser(userId);
        }

        void allowTrustFromUnlock(int userId) {
            if (userId < 0) {
                throw new IllegalArgumentException("userId must be a valid user: " + userId);
            }
            boolean previous = canAgentsRunForUser(userId);
            this.mStartFromSuccessfulUnlock.put(userId, true);
            if (TrustManagerService.DEBUG) {
                Log.i(TrustManagerService.TAG, "allowTrustFromUnlock(" + userId + ") -> trustAllowed=" + isTrustAllowedForUser(userId) + " agentsCanRun=" + canAgentsRunForUser(userId));
            }
            if (canAgentsRunForUser(userId) != previous) {
                TrustManagerService.this.refreshAgentList(userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class TrustTimeoutAlarmListener implements AlarmManager.OnAlarmListener {
        private boolean mIsQueued = false;
        private final int mUserId;

        TrustTimeoutAlarmListener(int userId) {
            this.mUserId = userId;
        }

        @Override // android.app.AlarmManager.OnAlarmListener
        public void onAlarm() {
            this.mIsQueued = false;
            TrustManagerService.this.mStrongAuthTracker.getStrongAuthForUser(this.mUserId);
            if (TrustManagerService.this.mStrongAuthTracker.isTrustAllowedForUser(this.mUserId)) {
                if (TrustManagerService.DEBUG) {
                    Slog.d(TrustManagerService.TAG, "Revoking all trust because of trust timeout");
                }
                LockPatternUtils lockPatternUtils = TrustManagerService.this.mLockPatternUtils;
                StrongAuthTracker unused = TrustManagerService.this.mStrongAuthTracker;
                lockPatternUtils.requireStrongAuth(4, this.mUserId);
            }
            TrustManagerService.this.maybeLockScreen(this.mUserId);
        }

        public void setQueued(boolean isQueued) {
            this.mIsQueued = isQueued;
        }

        public boolean isQueued() {
            return this.mIsQueued;
        }
    }
}
