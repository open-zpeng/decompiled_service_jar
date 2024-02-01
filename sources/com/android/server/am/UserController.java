package com.android.server.am;

import android.app.AppGlobals;
import android.app.Dialog;
import android.app.IStopUserCallback;
import android.app.IUserSwitchObserver;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetManagerInternal;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.IRemoteCallback;
import android.os.IUserManager;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimingsTraceLog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.am.UserController;
import com.android.server.am.UserState;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.pm.UserManagerService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerService;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class UserController implements Handler.Callback {
    static final int CONTINUE_USER_SWITCH_MSG = 20;
    static final int FOREGROUND_PROFILE_CHANGED_MSG = 70;
    static final int REPORT_LOCKED_BOOT_COMPLETE_MSG = 110;
    static final int REPORT_USER_SWITCH_COMPLETE_MSG = 80;
    static final int REPORT_USER_SWITCH_MSG = 10;
    static final int START_PROFILES_MSG = 40;
    static final int START_USER_SWITCH_FG_MSG = 120;
    static final int START_USER_SWITCH_UI_MSG = 1000;
    private static final String TAG = "ActivityManager";
    static final int USER_CURRENT_MSG = 60;
    static final int USER_START_MSG = 50;
    private static final int USER_SWITCH_CALLBACKS_TIMEOUT_MS = 5000;
    static final int USER_SWITCH_CALLBACKS_TIMEOUT_MSG = 90;
    static final int USER_SWITCH_TIMEOUT_MS = 3000;
    static final int USER_SWITCH_TIMEOUT_MSG = 30;
    private static final int USER_SWITCH_WARNING_TIMEOUT_MS = 500;
    static final int USER_UNLOCK_MSG = 100;
    volatile boolean mBootCompleted;
    @GuardedBy({"mLock"})
    private volatile ArraySet<String> mCurWaitingUserSwitchCallbacks;
    @GuardedBy({"mLock"})
    private int[] mCurrentProfileIds;
    @GuardedBy({"mLock"})
    private volatile int mCurrentUserId;
    boolean mDelayUserDataLocking;
    private final Handler mHandler;
    private final Injector mInjector;
    @GuardedBy({"mLock"})
    private final ArrayList<Integer> mLastActiveUsers;
    private final Object mLock;
    private final LockPatternUtils mLockPatternUtils;
    int mMaxRunningUsers;
    @GuardedBy({"mLock"})
    private int[] mStartedUserArray;
    @GuardedBy({"mLock"})
    private final SparseArray<UserState> mStartedUsers;
    @GuardedBy({"mLock"})
    private String mSwitchingFromSystemUserMessage;
    @GuardedBy({"mLock"})
    private String mSwitchingToSystemUserMessage;
    @GuardedBy({"mLock"})
    private volatile int mTargetUserId;
    @GuardedBy({"mLock"})
    private ArraySet<String> mTimeoutUserSwitchCallbacks;
    private final Handler mUiHandler;
    @GuardedBy({"mLock"})
    private final ArrayList<Integer> mUserLru;
    @GuardedBy({"mLock"})
    private final SparseIntArray mUserProfileGroupIds;
    private final RemoteCallbackList<IUserSwitchObserver> mUserSwitchObservers;
    boolean mUserSwitchUiEnabled;

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserController(ActivityManagerService service) {
        this(new Injector(service));
    }

    @VisibleForTesting
    UserController(Injector injector) {
        this.mLock = new Object();
        this.mCurrentUserId = 0;
        this.mTargetUserId = -10000;
        this.mStartedUsers = new SparseArray<>();
        this.mUserLru = new ArrayList<>();
        this.mStartedUserArray = new int[]{0};
        this.mCurrentProfileIds = new int[0];
        this.mUserProfileGroupIds = new SparseIntArray();
        this.mUserSwitchObservers = new RemoteCallbackList<>();
        this.mUserSwitchUiEnabled = true;
        this.mLastActiveUsers = new ArrayList<>();
        this.mInjector = injector;
        this.mHandler = this.mInjector.getHandler(this);
        this.mUiHandler = this.mInjector.getUiHandler(this);
        UserState uss = new UserState(UserHandle.SYSTEM);
        uss.mUnlockProgress.addListener(new UserProgressListener());
        this.mStartedUsers.put(0, uss);
        this.mUserLru.add(0);
        this.mLockPatternUtils = this.mInjector.getLockPatternUtils();
        updateStartedUserArrayLU();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishUserSwitch(final UserState uss) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$f2F3ceAG58MOmBJm9cmZ7HhYcmE
            @Override // java.lang.Runnable
            public final void run() {
                UserController.this.lambda$finishUserSwitch$0$UserController(uss);
            }
        });
    }

    public /* synthetic */ void lambda$finishUserSwitch$0$UserController(UserState uss) {
        finishUserBoot(uss);
        startProfiles();
        synchronized (this.mLock) {
            stopRunningUsersLU(this.mMaxRunningUsers);
        }
    }

    @GuardedBy({"mLock"})
    List<Integer> getRunningUsersLU() {
        ArrayList<Integer> runningUsers = new ArrayList<>();
        Iterator<Integer> it = this.mUserLru.iterator();
        while (it.hasNext()) {
            Integer userId = it.next();
            UserState uss = this.mStartedUsers.get(userId.intValue());
            if (uss != null && uss.state != 4 && uss.state != 5 && (userId.intValue() != 0 || !UserInfo.isSystemOnly(userId.intValue()))) {
                runningUsers.add(userId);
            }
        }
        return runningUsers;
    }

    @GuardedBy({"mLock"})
    void stopRunningUsersLU(int maxRunningUsers) {
        List<Integer> currentlyRunning = getRunningUsersLU();
        Iterator<Integer> iterator = currentlyRunning.iterator();
        while (currentlyRunning.size() > maxRunningUsers && iterator.hasNext()) {
            Integer userId = iterator.next();
            if (userId.intValue() != 0 && userId.intValue() != this.mCurrentUserId && stopUsersLU(userId.intValue(), false, null, null) == 0) {
                iterator.remove();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canStartMoreUsers() {
        boolean z;
        synchronized (this.mLock) {
            z = getRunningUsersLU().size() < this.mMaxRunningUsers;
        }
        return z;
    }

    private void finishUserBoot(UserState uss) {
        finishUserBoot(uss, null);
    }

    private void finishUserBoot(UserState uss, IIntentReceiver resultTo) {
        int userId;
        int userId2 = uss.mHandle.getIdentifier();
        Slog.d(TAG, "Finishing user boot " + userId2);
        synchronized (this.mLock) {
            try {
                if (this.mStartedUsers.get(userId2) != uss) {
                    try {
                        return;
                    } catch (Throwable th) {
                        th = th;
                        while (true) {
                            try {
                                break;
                            } catch (Throwable th2) {
                                th = th2;
                            }
                        }
                        throw th;
                    }
                }
                if (!uss.setState(0, 1)) {
                    userId = userId2;
                } else {
                    this.mInjector.getUserManagerInternal().setUserState(userId2, uss.state);
                    if (userId2 == 0 && !this.mInjector.isRuntimeRestarted() && !this.mInjector.isFirstBootOrUpgrade()) {
                        int uptimeSeconds = (int) (SystemClock.elapsedRealtime() / 1000);
                        MetricsLogger.histogram(this.mInjector.getContext(), "framework_locked_boot_completed", uptimeSeconds);
                        if (uptimeSeconds > START_USER_SWITCH_FG_MSG) {
                            Slog.wtf("SystemServerTiming", "finishUserBoot took too long. uptimeSeconds=" + uptimeSeconds);
                        }
                    }
                    if (this.mInjector.getUserManager().isPreCreated(userId2)) {
                        userId = userId2;
                    } else {
                        Handler handler = this.mHandler;
                        handler.sendMessage(handler.obtainMessage(REPORT_LOCKED_BOOT_COMPLETE_MSG, userId2, 0));
                        Intent intent = new Intent("android.intent.action.LOCKED_BOOT_COMPLETED", (Uri) null);
                        intent.putExtra("android.intent.extra.user_handle", userId2);
                        intent.addFlags(150994944);
                        userId = userId2;
                        this.mInjector.broadcastIntent(intent, null, resultTo, 0, null, null, new String[]{"android.permission.RECEIVE_BOOT_COMPLETED"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), userId);
                    }
                }
                int userId3 = userId;
                if (this.mInjector.getUserManager().isManagedProfile(userId3)) {
                    UserInfo parent = this.mInjector.getUserManager().getProfileParent(userId3);
                    if (parent != null && isUserRunning(parent.id, 4)) {
                        Slog.d(TAG, "User " + userId3 + " (parent " + parent.id + "): attempting unlock because parent is unlocked");
                        maybeUnlockUser(userId3);
                        return;
                    }
                    String parentId = parent == null ? "<null>" : String.valueOf(parent.id);
                    Slog.d(TAG, "User " + userId3 + " (parent " + parentId + "): delaying unlock because parent is locked");
                    return;
                }
                maybeUnlockUser(userId3);
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private boolean finishUserUnlocking(final UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        Slog.d(TAG, "UserController event: finishUserUnlocking(" + userId + ")");
        if (StorageManager.isUserKeyUnlocked(userId)) {
            synchronized (this.mLock) {
                if (this.mStartedUsers.get(userId) == uss && uss.state == 1) {
                    uss.mUnlockProgress.start();
                    uss.mUnlockProgress.setProgress(5, this.mInjector.getContext().getString(17039489));
                    FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$stQk1028ON105v_u-VMykVjcxLk
                        @Override // java.lang.Runnable
                        public final void run() {
                            UserController.this.lambda$finishUserUnlocking$1$UserController(userId, uss);
                        }
                    });
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public /* synthetic */ void lambda$finishUserUnlocking$1$UserController(int userId, UserState uss) {
        if (!StorageManager.isUserKeyUnlocked(userId)) {
            Slog.w(TAG, "User key got locked unexpectedly, leaving user locked.");
            return;
        }
        this.mInjector.getUserManager().onBeforeUnlockUser(userId);
        synchronized (this.mLock) {
            if (uss.setState(1, 2)) {
                this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                uss.mUnlockProgress.setProgress(20);
                this.mHandler.obtainMessage(100, userId, 0, uss).sendToTarget();
            }
        }
    }

    void finishUserUnlocked(final UserState uss) {
        boolean quiet;
        UserInfo parent;
        int userId = uss.mHandle.getIdentifier();
        Slog.d(TAG, "UserController event: finishUserUnlocked(" + userId + ")");
        if (!StorageManager.isUserKeyUnlocked(userId)) {
            return;
        }
        synchronized (this.mLock) {
            try {
                try {
                    if (this.mStartedUsers.get(uss.mHandle.getIdentifier()) != uss) {
                        return;
                    }
                    if (uss.setState(2, 3)) {
                        this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                        uss.mUnlockProgress.finish();
                        if (userId == 0) {
                            this.mInjector.startPersistentApps(262144);
                        }
                        this.mInjector.installEncryptionUnawareProviders(userId);
                        Intent unlockedIntent = new Intent("android.intent.action.USER_UNLOCKED");
                        unlockedIntent.putExtra("android.intent.extra.user_handle", userId);
                        unlockedIntent.addFlags(1342177280);
                        this.mInjector.broadcastIntent(unlockedIntent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), userId);
                        if (getUserInfo(userId).isManagedProfile() && (parent = this.mInjector.getUserManager().getProfileParent(userId)) != null) {
                            Intent profileUnlockedIntent = new Intent("android.intent.action.MANAGED_PROFILE_UNLOCKED");
                            profileUnlockedIntent.putExtra("android.intent.extra.USER", UserHandle.of(userId));
                            profileUnlockedIntent.addFlags(1342177280);
                            this.mInjector.broadcastIntent(profileUnlockedIntent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), parent.id);
                        }
                        UserInfo info = getUserInfo(userId);
                        if (!Objects.equals(info.lastLoggedInFingerprint, Build.FINGERPRINT)) {
                            if (info.isManagedProfile()) {
                                quiet = (uss.tokenProvided && this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)) ? false : true;
                            } else {
                                quiet = false;
                            }
                            this.mInjector.sendPreBootBroadcast(userId, quiet, new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$K71HFCIuD0iCwrDTKYnIUDyAeWg
                                @Override // java.lang.Runnable
                                public final void run() {
                                    UserController.this.lambda$finishUserUnlocked$2$UserController(uss);
                                }
                            });
                            return;
                        }
                        lambda$finishUserUnlocked$2$UserController(uss);
                    }
                } catch (Throwable th) {
                    th = th;
                    while (true) {
                        try {
                            break;
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    }
                    throw th;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: finishUserUnlockedCompleted */
    public void lambda$finishUserUnlocked$2$UserController(UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        Slog.d(TAG, "UserController event: finishUserUnlockedCompleted(" + userId + ")");
        synchronized (this.mLock) {
            try {
                if (this.mStartedUsers.get(uss.mHandle.getIdentifier()) != uss) {
                    try {
                        return;
                    } catch (Throwable th) {
                        th = th;
                        while (true) {
                            try {
                                break;
                            } catch (Throwable th2) {
                                th = th2;
                            }
                        }
                        throw th;
                    }
                }
                final UserInfo userInfo = getUserInfo(userId);
                if (userInfo != null && StorageManager.isUserKeyUnlocked(userId)) {
                    this.mInjector.getUserManager().onUserLoggedIn(userId);
                    if (!userInfo.isInitialized() && userId != 0) {
                        Slog.d(TAG, "Initializing user #" + userId);
                        Intent intent = new Intent("android.intent.action.USER_INITIALIZE");
                        intent.addFlags(285212672);
                        this.mInjector.broadcastIntent(intent, null, new IIntentReceiver.Stub() { // from class: com.android.server.am.UserController.1
                            public void performReceive(Intent intent2, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                                UserController.this.mInjector.getUserManager().makeInitialized(userInfo.id);
                            }
                        }, 0, null, null, null, -1, null, true, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), userId);
                    }
                    if (userInfo.preCreated) {
                        Slog.i(TAG, "Stopping pre-created user " + userInfo.toFullString());
                        stopUser(userInfo.id, true, null, null);
                        return;
                    }
                    this.mInjector.startUserWidgets(userId);
                    Slog.i(TAG, "Posting BOOT_COMPLETED user #" + userId);
                    if (userId == 0 && !this.mInjector.isRuntimeRestarted() && !this.mInjector.isFirstBootOrUpgrade()) {
                        int uptimeSeconds = (int) (SystemClock.elapsedRealtime() / 1000);
                        MetricsLogger.histogram(this.mInjector.getContext(), "framework_boot_completed", uptimeSeconds);
                    }
                    final Intent bootIntent = new Intent("android.intent.action.BOOT_COMPLETED", (Uri) null);
                    bootIntent.putExtra("android.intent.extra.user_handle", userId);
                    bootIntent.addFlags(-1996488704);
                    final int callingUid = Binder.getCallingUid();
                    final int callingPid = Binder.getCallingPid();
                    FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$I0p0bKjuvsSPLZB71mKQFfdUjZ4
                        @Override // java.lang.Runnable
                        public final void run() {
                            UserController.this.lambda$finishUserUnlockedCompleted$3$UserController(bootIntent, userId, callingUid, callingPid);
                        }
                    });
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    public /* synthetic */ void lambda$finishUserUnlockedCompleted$3$UserController(Intent bootIntent, final int userId, int callingUid, int callingPid) {
        this.mInjector.broadcastIntent(bootIntent, null, new IIntentReceiver.Stub() { // from class: com.android.server.am.UserController.2
            public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
                Slog.i(UserController.TAG, "Finished processing BOOT_COMPLETED for u" + userId);
                UserController.this.mBootCompleted = true;
            }
        }, 0, null, null, new String[]{"android.permission.RECEIVE_BOOT_COMPLETED"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, callingUid, callingPid, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.am.UserController$3  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass3 implements UserState.KeyEvictedCallback {
        final /* synthetic */ boolean val$foreground;

        AnonymousClass3(boolean z) {
            this.val$foreground = z;
        }

        @Override // com.android.server.am.UserState.KeyEvictedCallback
        public void keyEvicted(final int userId) {
            Handler handler = UserController.this.mHandler;
            final boolean z = this.val$foreground;
            handler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$3$A5KxB7wo13M_s4_guYgNtw-Vi9U
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.AnonymousClass3.this.lambda$keyEvicted$0$UserController$3(userId, z);
                }
            });
        }

        public /* synthetic */ void lambda$keyEvicted$0$UserController$3(int userId, boolean foreground) {
            UserController.this.startUser(userId, foreground);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int restartUser(int userId, boolean foreground) {
        return stopUser(userId, true, null, new AnonymousClass3(foreground));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int stopUser(int userId, boolean force, IStopUserCallback stopUserCallback, UserState.KeyEvictedCallback keyEvictedCallback) {
        int stopUsersLU;
        if (this.mInjector.checkCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            String msg = "Permission Denial: switchUser() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS_FULL";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        } else if (userId < 0 || userId == 0) {
            throw new IllegalArgumentException("Can't stop system user " + userId);
        } else {
            enforceShellRestriction("no_debugging_features", userId);
            synchronized (this.mLock) {
                stopUsersLU = stopUsersLU(userId, force, stopUserCallback, keyEvictedCallback);
            }
            return stopUsersLU;
        }
    }

    @GuardedBy({"mLock"})
    private int stopUsersLU(int userId, boolean force, IStopUserCallback stopUserCallback, UserState.KeyEvictedCallback keyEvictedCallback) {
        if (userId == 0) {
            return -3;
        }
        if (isCurrentUserLU(userId)) {
            return -2;
        }
        int[] usersToStop = getUsersToStopLU(userId);
        for (int relatedUserId : usersToStop) {
            if (relatedUserId == 0 || isCurrentUserLU(relatedUserId)) {
                if (ActivityManagerDebugConfig.DEBUG_MU) {
                    Slog.i(TAG, "stopUsersLocked cannot stop related user " + relatedUserId);
                }
                if (force) {
                    Slog.i(TAG, "Force stop user " + userId + ". Related users will not be stopped");
                    stopSingleUserLU(userId, stopUserCallback, keyEvictedCallback);
                    return 0;
                }
                return -4;
            }
        }
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.i(TAG, "stopUsersLocked usersToStop=" + Arrays.toString(usersToStop));
        }
        int length = usersToStop.length;
        for (int i = 0; i < length; i++) {
            int userIdToStop = usersToStop[i];
            UserState.KeyEvictedCallback keyEvictedCallback2 = null;
            IStopUserCallback iStopUserCallback = userIdToStop == userId ? stopUserCallback : null;
            if (userIdToStop == userId) {
                keyEvictedCallback2 = keyEvictedCallback;
            }
            stopSingleUserLU(userIdToStop, iStopUserCallback, keyEvictedCallback2);
        }
        return 0;
    }

    @GuardedBy({"mLock"})
    private void stopSingleUserLU(final int userId, final IStopUserCallback stopUserCallback, UserState.KeyEvictedCallback keyEvictedCallback) {
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.i(TAG, "stopSingleUserLocked userId=" + userId);
        }
        final UserState uss = this.mStartedUsers.get(userId);
        if (uss == null) {
            if (stopUserCallback != null) {
                this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$QAvDazb_bK3Biqbrt7rtbU_i_EQ
                    @Override // java.lang.Runnable
                    public final void run() {
                        stopUserCallback.userStopped(userId);
                    }
                });
                return;
            }
            return;
        }
        if (stopUserCallback != null) {
            uss.mStopCallbacks.add(stopUserCallback);
        }
        if (keyEvictedCallback != null) {
            uss.mKeyEvictedCallbacks.add(keyEvictedCallback);
        }
        if (uss.state != 4 && uss.state != 5) {
            uss.setState(4);
            this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
            updateStartedUserArrayLU();
            this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$fU2mcMYCcCOsyUuGHKIUB-nSo1Y
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.this.lambda$stopSingleUserLU$5$UserController(userId, uss);
                }
            });
        }
    }

    public /* synthetic */ void lambda$stopSingleUserLU$5$UserController(int userId, UserState uss) {
        Intent stoppingIntent = new Intent("android.intent.action.USER_STOPPING");
        stoppingIntent.addFlags(1073741824);
        stoppingIntent.putExtra("android.intent.extra.user_handle", userId);
        stoppingIntent.putExtra("android.intent.extra.SHUTDOWN_USERSPACE_ONLY", true);
        IIntentReceiver stoppingReceiver = new AnonymousClass4(userId, uss);
        this.mInjector.clearBroadcastQueueForUser(userId);
        this.mInjector.broadcastIntent(stoppingIntent, null, stoppingReceiver, 0, null, null, new String[]{"android.permission.INTERACT_ACROSS_USERS"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.am.UserController$4  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass4 extends IIntentReceiver.Stub {
        final /* synthetic */ int val$userId;
        final /* synthetic */ UserState val$uss;

        AnonymousClass4(int i, UserState userState) {
            this.val$userId = i;
            this.val$uss = userState;
        }

        public /* synthetic */ void lambda$performReceive$0$UserController$4(int userId, UserState uss) {
            UserController.this.finishUserStopping(userId, uss);
        }

        public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
            Handler handler = UserController.this.mHandler;
            final int i = this.val$userId;
            final UserState userState = this.val$uss;
            handler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$4$P3Sj7pxBXLC7k_puCIIki2uVgGE
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.AnonymousClass4.this.lambda$performReceive$0$UserController$4(i, userState);
                }
            });
        }
    }

    void finishUserStopping(int userId, final UserState uss) {
        Slog.d(TAG, "UserController event: finishUserStopping(" + userId + ")");
        Intent shutdownIntent = new Intent("android.intent.action.ACTION_SHUTDOWN");
        IIntentReceiver shutdownReceiver = new IIntentReceiver.Stub() { // from class: com.android.server.am.UserController.5
            public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                UserController.this.mHandler.post(new Runnable() { // from class: com.android.server.am.UserController.5.1
                    @Override // java.lang.Runnable
                    public void run() {
                        UserController.this.finishUserStopped(uss);
                    }
                });
            }
        };
        synchronized (this.mLock) {
            if (uss.state != 4) {
                return;
            }
            uss.setState(5);
            this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
            this.mInjector.batteryStatsServiceNoteEvent(16391, Integer.toString(userId), userId);
            this.mInjector.getSystemServiceManager().stopUser(userId);
            this.mInjector.broadcastIntent(shutdownIntent, null, shutdownReceiver, 0, null, null, null, -1, null, true, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), userId);
        }
    }

    void finishUserStopped(UserState uss) {
        ArrayList<IStopUserCallback> stopCallbacks;
        final ArrayList<UserState.KeyEvictedCallback> keyEvictedCallbacks;
        boolean stopped;
        final int userId = uss.mHandle.getIdentifier();
        Slog.d(TAG, "UserController event: finishUserStopped(" + userId + ")");
        boolean lockUser = true;
        int userIdToLock = userId;
        synchronized (this.mLock) {
            stopCallbacks = new ArrayList<>(uss.mStopCallbacks);
            keyEvictedCallbacks = new ArrayList<>(uss.mKeyEvictedCallbacks);
            if (this.mStartedUsers.get(userId) == uss && uss.state == 5) {
                stopped = true;
                this.mStartedUsers.remove(userId);
                this.mUserLru.remove(Integer.valueOf(userId));
                updateStartedUserArrayLU();
                userIdToLock = updateUserToLockLU(userId);
                if (userIdToLock == -10000) {
                    lockUser = false;
                }
            }
            stopped = false;
        }
        if (stopped) {
            this.mInjector.getUserManagerInternal().removeUserState(userId);
            this.mInjector.activityManagerOnUserStopped(userId);
            forceStopUser(userId, "finish user");
        }
        Iterator<IStopUserCallback> it = stopCallbacks.iterator();
        while (it.hasNext()) {
            IStopUserCallback callback = it.next();
            if (stopped) {
                try {
                    callback.userStopped(userId);
                } catch (RemoteException e) {
                }
            } else {
                callback.userStopAborted(userId);
            }
        }
        if (stopped) {
            this.mInjector.systemServiceManagerCleanupUser(userId);
            this.mInjector.stackSupervisorRemoveUser(userId);
            UserInfo userInfo = getUserInfo(userId);
            if (userInfo.isEphemeral() && !userInfo.preCreated) {
                this.mInjector.getUserManager().removeUserEvenWhenDisallowed(userId);
            }
            if (!lockUser) {
                return;
            }
            final int userIdToLockF = userIdToLock;
            FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$WUEqPFGA7TEsxb4dlgZAmMu5O-s
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.this.lambda$finishUserStopped$6$UserController(userIdToLockF, userId, keyEvictedCallbacks);
                }
            });
        }
    }

    public /* synthetic */ void lambda$finishUserStopped$6$UserController(int userIdToLockF, int userId, ArrayList keyEvictedCallbacks) {
        synchronized (this.mLock) {
            if (this.mStartedUsers.get(userIdToLockF) != null) {
                Slog.w(TAG, "User was restarted, skipping key eviction");
                return;
            }
            try {
                this.mInjector.getStorageManager().lockUserKey(userIdToLockF);
                if (userIdToLockF == userId) {
                    Iterator it = keyEvictedCallbacks.iterator();
                    while (it.hasNext()) {
                        UserState.KeyEvictedCallback callback = (UserState.KeyEvictedCallback) it.next();
                        callback.keyEvicted(userId);
                    }
                }
            } catch (RemoteException re) {
                throw re.rethrowAsRuntimeException();
            }
        }
    }

    @GuardedBy({"mLock"})
    private int updateUserToLockLU(int userId) {
        if (!this.mDelayUserDataLocking || getUserInfo(userId).isEphemeral() || hasUserRestriction("no_run_in_background", userId)) {
            return userId;
        }
        this.mLastActiveUsers.remove(Integer.valueOf(userId));
        this.mLastActiveUsers.add(0, Integer.valueOf(userId));
        int totalUnlockedUsers = this.mStartedUsers.size() + this.mLastActiveUsers.size();
        if (totalUnlockedUsers > this.mMaxRunningUsers) {
            ArrayList<Integer> arrayList = this.mLastActiveUsers;
            int userIdToLock = arrayList.get(arrayList.size() - 1).intValue();
            ArrayList<Integer> arrayList2 = this.mLastActiveUsers;
            arrayList2.remove(arrayList2.size() - 1);
            Slog.i(TAG, "finishUserStopped, stopping user:" + userId + " lock user:" + userIdToLock);
            return userIdToLock;
        }
        Slog.i(TAG, "finishUserStopped, user:" + userId + ",skip locking");
        return -10000;
    }

    @GuardedBy({"mLock"})
    private int[] getUsersToStopLU(int userId) {
        int startedUsersSize = this.mStartedUsers.size();
        IntArray userIds = new IntArray();
        userIds.add(userId);
        int userGroupId = this.mUserProfileGroupIds.get(userId, -10000);
        for (int i = 0; i < startedUsersSize; i++) {
            UserState uss = this.mStartedUsers.valueAt(i);
            int startedUserId = uss.mHandle.getIdentifier();
            int startedUserGroupId = this.mUserProfileGroupIds.get(startedUserId, -10000);
            boolean sameGroup = userGroupId != -10000 && userGroupId == startedUserGroupId;
            boolean sameUserId = startedUserId == userId;
            if (sameGroup && !sameUserId) {
                userIds.add(startedUserId);
            }
        }
        return userIds.toArray();
    }

    private void forceStopUser(int userId, String reason) {
        this.mInjector.activityManagerForceStopPackage(userId, reason);
        Intent intent = new Intent("android.intent.action.USER_STOPPED");
        intent.addFlags(1342177280);
        intent.putExtra("android.intent.extra.user_handle", userId);
        this.mInjector.broadcastIntent(intent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), -1);
    }

    private void stopGuestOrEphemeralUserIfBackground(int oldUserId) {
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.i(TAG, "Stop guest or ephemeral user if background: " + oldUserId);
        }
        synchronized (this.mLock) {
            UserState oldUss = this.mStartedUsers.get(oldUserId);
            if (oldUserId != 0 && oldUserId != this.mCurrentUserId && oldUss != null && oldUss.state != 4 && oldUss.state != 5) {
                UserInfo userInfo = getUserInfo(oldUserId);
                if (userInfo.isEphemeral()) {
                    ((UserManagerInternal) LocalServices.getService(UserManagerInternal.class)).onEphemeralUserStop(oldUserId);
                }
                if (userInfo.isGuest() || userInfo.isEphemeral()) {
                    synchronized (this.mLock) {
                        stopUsersLU(oldUserId, true, null, null);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleStartProfiles() {
        FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$G0WJmqt4X_QG30fRlvXobn18mrE
            @Override // java.lang.Runnable
            public final void run() {
                UserController.this.lambda$scheduleStartProfiles$7$UserController();
            }
        });
    }

    public /* synthetic */ void lambda$scheduleStartProfiles$7$UserController() {
        if (!this.mHandler.hasMessages(40)) {
            Handler handler = this.mHandler;
            handler.sendMessageDelayed(handler.obtainMessage(40), 1000L);
        }
    }

    void startProfiles() {
        int currentUserId = getCurrentUserId();
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.i(TAG, "startProfilesLocked");
        }
        List<UserInfo> profiles = this.mInjector.getUserManager().getProfiles(currentUserId, false);
        List<UserInfo> profilesToStart = new ArrayList<>(profiles.size());
        for (UserInfo user : profiles) {
            if ((user.flags & 16) == 16 && user.id != currentUserId && !user.isQuietModeEnabled()) {
                profilesToStart.add(user);
            }
        }
        int profilesToStartSize = profilesToStart.size();
        int i = 0;
        while (i < profilesToStartSize && i < this.mMaxRunningUsers - 1) {
            startUser(profilesToStart.get(i).id, false);
            i++;
        }
        if (i < profilesToStartSize) {
            Slog.w(TAG, "More profiles than MAX_RUNNING_USERS");
        }
    }

    boolean startUser(int userId, boolean foreground) {
        return lambda$startUser$8$UserController(userId, foreground, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: startUser */
    public boolean lambda$startUser$8$UserController(final int userId, final boolean foreground, final IProgressListener unlockListener) {
        boolean updateUmState;
        UserState uss;
        UserState uss2;
        int oldUserId;
        int i;
        UserState uss3;
        int oldUserId2;
        if (this.mInjector.checkCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            String msg = "Permission Denial: switchUser() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS_FULL";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        Slog.i(TAG, "Starting userid:" + userId + " fg:" + foreground);
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long ident = Binder.clearCallingIdentity();
        try {
            int oldUserId3 = getCurrentUserId();
            if (oldUserId3 == userId) {
                UserState state = getStartedUserState(userId);
                if (state == null) {
                    Slog.wtf(TAG, "Current user has no UserState");
                } else if (userId != 0 || state.state != 0) {
                    if (state.state == 3) {
                        notifyFinished(userId, unlockListener);
                    }
                    Binder.restoreCallingIdentity(ident);
                    return true;
                }
            }
            if (foreground) {
                this.mInjector.clearAllLockedTasks("startUser");
            }
            UserInfo userInfo = getUserInfo(userId);
            if (userInfo == null) {
                Slog.w(TAG, "No user info for user #" + userId);
                Binder.restoreCallingIdentity(ident);
                return false;
            } else if (foreground && userInfo.isManagedProfile()) {
                Slog.w(TAG, "Cannot switch to User #" + userId + ": not a full user");
                Binder.restoreCallingIdentity(ident);
                return false;
            } else if (foreground && userInfo.preCreated) {
                Slog.w(TAG, "Cannot start pre-created user #" + userId + " as foreground");
                Binder.restoreCallingIdentity(ident);
                return false;
            } else {
                if (foreground && this.mUserSwitchUiEnabled) {
                    this.mInjector.getWindowManager().startFreezingScreen(17432729, 17432728);
                }
                boolean needStart = false;
                try {
                    synchronized (this.mLock) {
                        try {
                            UserState uss4 = this.mStartedUsers.get(userId);
                            try {
                                if (uss4 == null) {
                                    UserState uss5 = new UserState(UserHandle.of(userId));
                                    uss5.mUnlockProgress.addListener(new UserProgressListener());
                                    this.mStartedUsers.put(userId, uss5);
                                    updateStartedUserArrayLU();
                                    needStart = true;
                                    updateUmState = true;
                                    uss = uss5;
                                } else if (uss4.state == 5 && !isCallingOnHandlerThread()) {
                                    Slog.i(TAG, "User #" + userId + " is shutting down - will start after full stop");
                                    this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$QOnwyVAoixnII6oArWHXXxl2_mo
                                        @Override // java.lang.Runnable
                                        public final void run() {
                                            UserController.this.lambda$startUser$8$UserController(userId, foreground, unlockListener);
                                        }
                                    });
                                    Binder.restoreCallingIdentity(ident);
                                    return true;
                                } else {
                                    updateUmState = false;
                                    uss = uss4;
                                }
                                try {
                                    Integer userIdInt = Integer.valueOf(userId);
                                    this.mUserLru.remove(userIdInt);
                                    this.mUserLru.add(userIdInt);
                                    if (unlockListener != null) {
                                        uss.mUnlockProgress.addListener(unlockListener);
                                    }
                                    if (updateUmState) {
                                        this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                                    }
                                    if (foreground) {
                                        this.mInjector.reportGlobalUsageEventLocked(16);
                                        synchronized (this.mLock) {
                                            this.mCurrentUserId = userId;
                                            this.mTargetUserId = -10000;
                                        }
                                        this.mInjector.updateUserConfiguration();
                                        updateCurrentProfileIds();
                                        this.mInjector.getWindowManager().setCurrentUser(userId, getCurrentProfileIds());
                                        this.mInjector.reportCurWakefulnessUsageEvent();
                                        if (this.mUserSwitchUiEnabled) {
                                            this.mInjector.getWindowManager().setSwitchingUser(true);
                                            this.mInjector.getWindowManager().lockNow(null);
                                        }
                                    } else {
                                        Integer currentUserIdInt = Integer.valueOf(this.mCurrentUserId);
                                        updateCurrentProfileIds();
                                        this.mInjector.getWindowManager().setCurrentProfileIds(getCurrentProfileIds());
                                        synchronized (this.mLock) {
                                            this.mUserLru.remove(currentUserIdInt);
                                            this.mUserLru.add(currentUserIdInt);
                                        }
                                    }
                                    if (uss.state == 4) {
                                        uss.setState(uss.lastState);
                                        this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                                        synchronized (this.mLock) {
                                            updateStartedUserArrayLU();
                                        }
                                        needStart = true;
                                    } else if (uss.state == 5) {
                                        uss.setState(0);
                                        this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                                        synchronized (this.mLock) {
                                            updateStartedUserArrayLU();
                                        }
                                        needStart = true;
                                    }
                                    if (uss.state == 0) {
                                        this.mInjector.getUserManager().onBeforeStartUser(userId);
                                        this.mHandler.sendMessage(this.mHandler.obtainMessage(50, userId, 0));
                                    }
                                    if (foreground) {
                                        this.mHandler.sendMessage(this.mHandler.obtainMessage(60, userId, oldUserId3));
                                        this.mHandler.removeMessages(10);
                                        this.mHandler.removeMessages(30);
                                        this.mHandler.sendMessage(this.mHandler.obtainMessage(10, oldUserId3, userId, uss));
                                        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(30, oldUserId3, userId, uss), BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
                                    }
                                    boolean needStart2 = userInfo.preCreated ? false : needStart;
                                    if (needStart2) {
                                        try {
                                            Intent intent = new Intent("android.intent.action.USER_STARTED");
                                            intent.addFlags(1342177280);
                                            intent.putExtra("android.intent.extra.user_handle", userId);
                                            uss2 = uss;
                                            oldUserId = oldUserId3;
                                            this.mInjector.broadcastIntent(intent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, callingUid, callingPid, userId);
                                        } catch (Throwable th) {
                                            th = th;
                                            Binder.restoreCallingIdentity(ident);
                                            throw th;
                                        }
                                    } else {
                                        uss2 = uss;
                                        oldUserId = oldUserId3;
                                    }
                                    if (foreground) {
                                        i = userId;
                                        uss3 = uss2;
                                        oldUserId2 = oldUserId;
                                        moveUserToForeground(uss3, oldUserId2, i);
                                    } else {
                                        i = userId;
                                        uss3 = uss2;
                                        oldUserId2 = oldUserId;
                                        finishUserBoot(uss3);
                                    }
                                    if (needStart2) {
                                        Intent intent2 = new Intent("android.intent.action.USER_STARTING");
                                        intent2.addFlags(1073741824);
                                        intent2.putExtra("android.intent.extra.user_handle", i);
                                        this.mInjector.broadcastIntent(intent2, null, new IIntentReceiver.Stub() { // from class: com.android.server.am.UserController.6
                                            public void performReceive(Intent intent3, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
                                            }
                                        }, 0, null, null, new String[]{"android.permission.INTERACT_ACROSS_USERS"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, callingUid, callingPid, -1);
                                    }
                                    Binder.restoreCallingIdentity(ident);
                                    return true;
                                } catch (Throwable th2) {
                                    th = th2;
                                    while (true) {
                                        try {
                                            break;
                                        } catch (Throwable th3) {
                                            th = th3;
                                        }
                                    }
                                    throw th;
                                }
                            } catch (Throwable th4) {
                                th = th4;
                            }
                        } catch (Throwable th5) {
                            th = th5;
                        }
                    }
                } catch (Throwable th6) {
                    th = th6;
                }
            }
        } catch (Throwable th7) {
            th = th7;
        }
    }

    private boolean isCallingOnHandlerThread() {
        return Looper.myLooper() == this.mHandler.getLooper();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startUserInForeground(int targetUserId) {
        boolean success = startUser(targetUserId, true);
        if (!success) {
            this.mInjector.getWindowManager().setSwitchingUser(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean unlockUser(int userId, byte[] token, byte[] secret, IProgressListener listener) {
        if (this.mInjector.checkCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            String msg = "Permission Denial: unlockUser() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS_FULL";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        Slog.i(TAG, "unlocking user " + userId);
        long binderToken = Binder.clearCallingIdentity();
        try {
            return unlockUserCleared(userId, token, secret, listener);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    private boolean maybeUnlockUser(int userId) {
        return unlockUserCleared(userId, null, null, null);
    }

    private static void notifyFinished(int userId, IProgressListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.onFinished(userId, (Bundle) null);
        } catch (RemoteException e) {
        }
    }

    private boolean unlockUserCleared(int userId, byte[] token, byte[] secret, IProgressListener listener) {
        UserState uss;
        int i;
        int[] userIds;
        if (!StorageManager.isUserKeyUnlocked(userId)) {
            UserInfo userInfo = getUserInfo(userId);
            IStorageManager storageManager = this.mInjector.getStorageManager();
            try {
                storageManager.unlockUserKey(userId, userInfo.serialNumber, token, secret);
            } catch (RemoteException | RuntimeException e) {
                Slog.w(TAG, "Failed to unlock: " + e.getMessage());
            }
        }
        synchronized (this.mLock) {
            uss = this.mStartedUsers.get(userId);
            if (uss != null) {
                uss.mUnlockProgress.addListener(listener);
                uss.tokenProvided = token != null;
            }
        }
        if (uss == null) {
            notifyFinished(userId, listener);
            return false;
        } else if (!finishUserUnlocking(uss)) {
            notifyFinished(userId, listener);
            return false;
        } else {
            synchronized (this.mLock) {
                userIds = new int[this.mStartedUsers.size()];
                for (int i2 = 0; i2 < userIds.length; i2++) {
                    userIds[i2] = this.mStartedUsers.keyAt(i2);
                }
            }
            for (int testUserId : userIds) {
                UserInfo parent = this.mInjector.getUserManager().getProfileParent(testUserId);
                if (parent != null && parent.id == userId && testUserId != userId) {
                    Slog.d(TAG, "User " + testUserId + " (parent " + parent.id + "): attempting unlock because parent was just unlocked");
                    maybeUnlockUser(testUserId);
                }
            }
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean switchUser(int targetUserId) {
        enforceShellRestriction("no_debugging_features", targetUserId);
        Slog.i(TAG, "switching to user " + targetUserId);
        int currentUserId = getCurrentUserId();
        UserInfo targetUserInfo = getUserInfo(targetUserId);
        if (targetUserId == currentUserId) {
            Slog.i(TAG, "user #" + targetUserId + " is already the current user");
            return true;
        } else if (targetUserInfo == null) {
            Slog.w(TAG, "No user info for user #" + targetUserId);
            return false;
        } else if (!targetUserInfo.supportsSwitchTo()) {
            Slog.w(TAG, "Cannot switch to User #" + targetUserId + ": not supported");
            return false;
        } else if (targetUserInfo.isManagedProfile()) {
            Slog.w(TAG, "Cannot switch to User #" + targetUserId + ": not a full user");
            return false;
        } else {
            synchronized (this.mLock) {
                this.mTargetUserId = targetUserId;
            }
            if (this.mUserSwitchUiEnabled) {
                UserInfo currentUserInfo = getUserInfo(currentUserId);
                Pair<UserInfo, UserInfo> userNames = new Pair<>(currentUserInfo, targetUserInfo);
                this.mUiHandler.removeMessages(1000);
                this.mUiHandler.sendMessage(this.mHandler.obtainMessage(1000, userNames));
            } else {
                this.mHandler.removeMessages(START_USER_SWITCH_FG_MSG);
                Handler handler = this.mHandler;
                handler.sendMessage(handler.obtainMessage(START_USER_SWITCH_FG_MSG, targetUserId, 0));
            }
            return true;
        }
    }

    private void showUserSwitchDialog(Pair<UserInfo, UserInfo> fromToUserPair) {
        this.mInjector.showUserSwitchingDialog((UserInfo) fromToUserPair.first, (UserInfo) fromToUserPair.second, getSwitchingFromSystemUserMessage(), getSwitchingToSystemUserMessage());
    }

    private void dispatchForegroundProfileChanged(int userId) {
        int observerCount = this.mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                this.mUserSwitchObservers.getBroadcastItem(i).onForegroundProfileSwitch(userId);
            } catch (RemoteException e) {
            }
        }
        this.mUserSwitchObservers.finishBroadcast();
    }

    void dispatchUserSwitchComplete(int userId) {
        this.mInjector.getWindowManager().setSwitchingUser(false);
        int observerCount = this.mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                this.mUserSwitchObservers.getBroadcastItem(i).onUserSwitchComplete(userId);
            } catch (RemoteException e) {
            }
        }
        this.mUserSwitchObservers.finishBroadcast();
    }

    private void dispatchLockedBootComplete(int userId) {
        int observerCount = this.mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                this.mUserSwitchObservers.getBroadcastItem(i).onLockedBootComplete(userId);
            } catch (RemoteException e) {
            }
        }
        this.mUserSwitchObservers.finishBroadcast();
    }

    private void stopBackgroundUsersIfEnforced(int oldUserId) {
        if (oldUserId == 0) {
            return;
        }
        boolean disallowRunInBg = hasUserRestriction("no_run_in_background", oldUserId) || this.mDelayUserDataLocking;
        if (!disallowRunInBg) {
            return;
        }
        synchronized (this.mLock) {
            if (ActivityManagerDebugConfig.DEBUG_MU) {
                Slog.i(TAG, "stopBackgroundUsersIfEnforced stopping " + oldUserId + " and related users");
            }
            stopUsersLU(oldUserId, false, null, null);
        }
    }

    private void timeoutUserSwitch(UserState uss, int oldUserId, int newUserId) {
        synchronized (this.mLock) {
            Slog.e(TAG, "User switch timeout: from " + oldUserId + " to " + newUserId);
            this.mTimeoutUserSwitchCallbacks = this.mCurWaitingUserSwitchCallbacks;
            this.mHandler.removeMessages(USER_SWITCH_CALLBACKS_TIMEOUT_MSG);
            sendContinueUserSwitchLU(uss, oldUserId, newUserId);
            this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(USER_SWITCH_CALLBACKS_TIMEOUT_MSG, oldUserId, newUserId), 5000L);
        }
    }

    private void timeoutUserSwitchCallbacks(int oldUserId, int newUserId) {
        synchronized (this.mLock) {
            if (this.mTimeoutUserSwitchCallbacks != null && !this.mTimeoutUserSwitchCallbacks.isEmpty()) {
                Slog.wtf(TAG, "User switch timeout: from " + oldUserId + " to " + newUserId + ". Observers that didn't respond: " + this.mTimeoutUserSwitchCallbacks);
                this.mTimeoutUserSwitchCallbacks = null;
            }
        }
    }

    void dispatchUserSwitch(final UserState uss, final int oldUserId, final int newUserId) {
        int i;
        Slog.d(TAG, "Dispatch onUserSwitching oldUser #" + oldUserId + " newUser #" + newUserId);
        int observerCount = this.mUserSwitchObservers.beginBroadcast();
        if (observerCount > 0) {
            final ArraySet<String> curWaitingUserSwitchCallbacks = new ArraySet<>();
            synchronized (this.mLock) {
                uss.switching = true;
                this.mCurWaitingUserSwitchCallbacks = curWaitingUserSwitchCallbacks;
            }
            final AtomicInteger waitingCallbacksCount = new AtomicInteger(observerCount);
            final long dispatchStartedTime = SystemClock.elapsedRealtime();
            int i2 = 0;
            while (i2 < observerCount) {
                try {
                    final String name = "#" + i2 + " " + this.mUserSwitchObservers.getBroadcastCookie(i2);
                    synchronized (this.mLock) {
                        curWaitingUserSwitchCallbacks.add(name);
                    }
                    i = i2;
                    try {
                        this.mUserSwitchObservers.getBroadcastItem(i).onUserSwitching(newUserId, new IRemoteCallback.Stub() { // from class: com.android.server.am.UserController.7
                            public void sendResult(Bundle data) throws RemoteException {
                                synchronized (UserController.this.mLock) {
                                    long delay = SystemClock.elapsedRealtime() - dispatchStartedTime;
                                    if (delay > BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS) {
                                        Slog.e(UserController.TAG, "User switch timeout: observer " + name + " sent result after " + delay + " ms");
                                    } else if (delay > 500) {
                                        Slog.w(UserController.TAG, "User switch slowed down by observer " + name + ": result sent after " + delay + " ms");
                                    }
                                    curWaitingUserSwitchCallbacks.remove(name);
                                    if (waitingCallbacksCount.decrementAndGet() == 0 && curWaitingUserSwitchCallbacks == UserController.this.mCurWaitingUserSwitchCallbacks) {
                                        UserController.this.sendContinueUserSwitchLU(uss, oldUserId, newUserId);
                                    }
                                }
                            }
                        });
                    } catch (RemoteException e) {
                    }
                } catch (RemoteException e2) {
                    i = i2;
                }
                i2 = i + 1;
            }
        } else {
            synchronized (this.mLock) {
                sendContinueUserSwitchLU(uss, oldUserId, newUserId);
            }
        }
        this.mUserSwitchObservers.finishBroadcast();
    }

    @GuardedBy({"mLock"})
    void sendContinueUserSwitchLU(UserState uss, int oldUserId, int newUserId) {
        this.mCurWaitingUserSwitchCallbacks = null;
        this.mHandler.removeMessages(30);
        Handler handler = this.mHandler;
        handler.sendMessage(handler.obtainMessage(20, oldUserId, newUserId, uss));
    }

    void continueUserSwitch(UserState uss, int oldUserId, int newUserId) {
        Slog.d(TAG, "Continue user switch oldUser #" + oldUserId + ", newUser #" + newUserId);
        if (this.mUserSwitchUiEnabled) {
            this.mInjector.getWindowManager().stopFreezingScreen();
        }
        uss.switching = false;
        this.mHandler.removeMessages(80);
        Handler handler = this.mHandler;
        handler.sendMessage(handler.obtainMessage(80, newUserId, 0));
        stopGuestOrEphemeralUserIfBackground(oldUserId);
        stopBackgroundUsersIfEnforced(oldUserId);
    }

    private void moveUserToForeground(UserState uss, int oldUserId, int newUserId) {
        boolean homeInFront = this.mInjector.stackSupervisorSwitchUser(newUserId, uss);
        if (homeInFront) {
            this.mInjector.startHomeActivity(newUserId, "moveUserToForeground");
        } else {
            this.mInjector.stackSupervisorResumeFocusedStackTopActivity();
        }
        EventLogTags.writeAmSwitchUser(newUserId);
        sendUserSwitchBroadcasts(oldUserId, newUserId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendUserSwitchBroadcasts(int oldUserId, int newUserId) {
        String str;
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long ident = Binder.clearCallingIdentity();
        String str2 = "android.intent.extra.user_handle";
        int i = 1342177280;
        if (oldUserId >= 0) {
            try {
                List<UserInfo> profiles = this.mInjector.getUserManager().getProfiles(oldUserId, false);
                int count = profiles.size();
                int i2 = 0;
                while (i2 < count) {
                    int profileUserId = profiles.get(i2).id;
                    Intent intent = new Intent("android.intent.action.USER_BACKGROUND");
                    intent.addFlags(i);
                    intent.putExtra(str2, profileUserId);
                    this.mInjector.broadcastIntent(intent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, callingUid, callingPid, profileUserId);
                    i2++;
                    count = count;
                    profiles = profiles;
                    str2 = str2;
                    i = 1342177280;
                }
                str = str2;
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
        } else {
            str = "android.intent.extra.user_handle";
        }
        if (newUserId >= 0) {
            List<UserInfo> profiles2 = this.mInjector.getUserManager().getProfiles(newUserId, false);
            int count2 = profiles2.size();
            int i3 = 0;
            while (i3 < count2) {
                int profileUserId2 = profiles2.get(i3).id;
                Intent intent2 = new Intent("android.intent.action.USER_FOREGROUND");
                intent2.addFlags(1342177280);
                String str3 = str;
                intent2.putExtra(str3, profileUserId2);
                this.mInjector.broadcastIntent(intent2, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, callingUid, callingPid, profileUserId2);
                i3++;
                count2 = count2;
                str = str3;
            }
            Intent intent3 = new Intent("android.intent.action.USER_SWITCHED");
            intent3.addFlags(1342177280);
            intent3.putExtra(str, newUserId);
            this.mInjector.broadcastIntent(intent3, null, null, 0, null, null, new String[]{"android.permission.MANAGE_USERS"}, -1, null, false, false, ActivityManagerService.MY_PID, 1000, callingUid, callingPid, -1);
        }
        Binder.restoreCallingIdentity(ident);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll, int allowMode, String name, String callerPackage) {
        boolean allow;
        int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId) {
            return userId;
        }
        int targetUserId = unsafeConvertIncomingUser(userId);
        if (1041 == callingUid) {
            return targetUserId;
        }
        if (callingUid != 0 && callingUid != 1000) {
            if (this.mInjector.isCallerRecents(callingUid) && callingUserId == getCurrentUserId() && isSameProfileGroup(callingUserId, targetUserId)) {
                allow = true;
            } else if (this.mInjector.checkComponentPermission("android.permission.INTERACT_ACROSS_USERS_FULL", callingPid, callingUid, -1, true) == 0) {
                allow = true;
            } else if (allowMode == 2) {
                allow = false;
            } else if (this.mInjector.checkComponentPermission("android.permission.INTERACT_ACROSS_USERS", callingPid, callingUid, -1, true) != 0) {
                allow = false;
            } else if (allowMode == 0) {
                allow = true;
            } else if (allowMode != 1) {
                throw new IllegalArgumentException("Unknown mode: " + allowMode);
            } else {
                allow = isSameProfileGroup(callingUserId, targetUserId);
            }
            if (!allow) {
                if (userId == -3) {
                    targetUserId = callingUserId;
                } else {
                    StringBuilder builder = new StringBuilder(128);
                    builder.append("Permission Denial: ");
                    builder.append(name);
                    if (callerPackage != null) {
                        builder.append(" from ");
                        builder.append(callerPackage);
                    }
                    builder.append(" asks to run as user ");
                    builder.append(userId);
                    builder.append(" but is calling from uid ");
                    UserHandle.formatUid(builder, callingUid);
                    builder.append("; this requires ");
                    builder.append("android.permission.INTERACT_ACROSS_USERS_FULL");
                    if (allowMode != 2) {
                        builder.append(" or ");
                        builder.append("android.permission.INTERACT_ACROSS_USERS");
                    }
                    String msg = builder.toString();
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
        }
        if (!allowAll) {
            ensureNotSpecialUser(targetUserId);
        }
        if (callingUid == 2000 && targetUserId >= 0 && hasUserRestriction("no_debugging_features", targetUserId)) {
            throw new SecurityException("Shell does not have permission to access user " + targetUserId + "\n " + Debug.getCallers(3));
        }
        return targetUserId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int unsafeConvertIncomingUser(int userId) {
        return (userId == -2 || userId == -3) ? getCurrentUserId() : userId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void ensureNotSpecialUser(int userId) {
        if (userId >= 0) {
            return;
        }
        throw new IllegalArgumentException("Call does not support special user #" + userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerUserSwitchObserver(IUserSwitchObserver observer, String name) {
        Preconditions.checkNotNull(name, "Observer name cannot be null");
        if (this.mInjector.checkCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            String msg = "Permission Denial: registerUserSwitchObserver() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS_FULL";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        this.mUserSwitchObservers.register(observer, name);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendForegroundProfileChanged(int userId) {
        this.mHandler.removeMessages(70);
        this.mHandler.obtainMessage(70, userId, 0).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterUserSwitchObserver(IUserSwitchObserver observer) {
        this.mUserSwitchObservers.unregister(observer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserState getStartedUserState(int userId) {
        UserState userState;
        synchronized (this.mLock) {
            userState = this.mStartedUsers.get(userId);
        }
        return userState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasStartedUserState(int userId) {
        boolean z;
        synchronized (this.mLock) {
            z = this.mStartedUsers.get(userId) != null;
        }
        return z;
    }

    @GuardedBy({"mLock"})
    private void updateStartedUserArrayLU() {
        int num = 0;
        for (int i = 0; i < this.mStartedUsers.size(); i++) {
            UserState uss = this.mStartedUsers.valueAt(i);
            if (uss.state != 4 && uss.state != 5) {
                num++;
            }
        }
        this.mStartedUserArray = new int[num];
        int num2 = 0;
        for (int i2 = 0; i2 < this.mStartedUsers.size(); i2++) {
            UserState uss2 = this.mStartedUsers.valueAt(i2);
            if (uss2.state != 4 && uss2.state != 5) {
                this.mStartedUserArray[num2] = this.mStartedUsers.keyAt(i2);
                num2++;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendBootCompleted(IIntentReceiver resultTo) {
        SparseArray<UserState> startedUsers;
        synchronized (this.mLock) {
            startedUsers = this.mStartedUsers.clone();
        }
        for (int i = 0; i < startedUsers.size(); i++) {
            UserState uss = startedUsers.valueAt(i);
            finishUserBoot(uss, resultTo);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSystemReady() {
        updateCurrentProfileIds();
        this.mInjector.reportCurWakefulnessUsageEvent();
    }

    private void updateCurrentProfileIds() {
        List<UserInfo> profiles = this.mInjector.getUserManager().getProfiles(getCurrentUserId(), false);
        int[] currentProfileIds = new int[profiles.size()];
        for (int i = 0; i < currentProfileIds.length; i++) {
            currentProfileIds[i] = profiles.get(i).id;
        }
        List<UserInfo> users = this.mInjector.getUserManager().getUsers(false);
        synchronized (this.mLock) {
            this.mCurrentProfileIds = currentProfileIds;
            this.mUserProfileGroupIds.clear();
            for (int i2 = 0; i2 < users.size(); i2++) {
                UserInfo user = users.get(i2);
                if (user.profileGroupId != -10000) {
                    this.mUserProfileGroupIds.put(user.id, user.profileGroupId);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] getStartedUserArray() {
        int[] iArr;
        synchronized (this.mLock) {
            iArr = this.mStartedUserArray;
        }
        return iArr;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUserRunning(int userId, int flags) {
        UserState state = getStartedUserState(userId);
        if (state == null) {
            return false;
        }
        if ((flags & 1) != 0) {
            return true;
        }
        if ((flags & 2) != 0) {
            int i = state.state;
            return i == 0 || i == 1;
        } else if ((flags & 8) != 0) {
            int i2 = state.state;
            if (i2 == 2 || i2 == 3) {
                return true;
            }
            if (i2 != 4 && i2 != 5) {
                return false;
            }
            return StorageManager.isUserKeyUnlocked(userId);
        } else if ((flags & 4) == 0) {
            return (state.state == 4 || state.state == 5) ? false : true;
        } else {
            int i3 = state.state;
            if (i3 != 3) {
                if (i3 != 4 && i3 != 5) {
                    return false;
                }
                return StorageManager.isUserKeyUnlocked(userId);
            }
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSystemUserStarted() {
        synchronized (this.mLock) {
            boolean z = false;
            UserState uss = this.mStartedUsers.get(0);
            if (uss == null) {
                return false;
            }
            if (uss.state == 1 || uss.state == 2 || uss.state == 3) {
                z = true;
            }
            return z;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserInfo getCurrentUser() {
        UserInfo currentUserLU;
        if (this.mInjector.checkCallingPermission("android.permission.INTERACT_ACROSS_USERS") != 0 && this.mInjector.checkCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            String msg = "Permission Denial: getCurrentUser() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        } else if (this.mTargetUserId == -10000) {
            return getUserInfo(this.mCurrentUserId);
        } else {
            synchronized (this.mLock) {
                currentUserLU = getCurrentUserLU();
            }
            return currentUserLU;
        }
    }

    @GuardedBy({"mLock"})
    UserInfo getCurrentUserLU() {
        int userId = this.mTargetUserId != -10000 ? this.mTargetUserId : this.mCurrentUserId;
        return getUserInfo(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurrentOrTargetUserId() {
        int i;
        synchronized (this.mLock) {
            i = this.mTargetUserId != -10000 ? this.mTargetUserId : this.mCurrentUserId;
        }
        return i;
    }

    @GuardedBy({"mLock"})
    int getCurrentOrTargetUserIdLU() {
        return this.mTargetUserId != -10000 ? this.mTargetUserId : this.mCurrentUserId;
    }

    @GuardedBy({"mLock"})
    int getCurrentUserIdLU() {
        return this.mCurrentUserId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurrentUserId() {
        int i;
        synchronized (this.mLock) {
            i = this.mCurrentUserId;
        }
        return i;
    }

    @GuardedBy({"mLock"})
    private boolean isCurrentUserLU(int userId) {
        return userId == getCurrentOrTargetUserIdLU();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] getUsers() {
        UserManagerService ums = this.mInjector.getUserManager();
        return ums != null ? ums.getUserIds() : new int[]{0};
    }

    private UserInfo getUserInfo(int userId) {
        return this.mInjector.getUserManager().getUserInfo(userId);
    }

    int[] getUserIds() {
        return this.mInjector.getUserManager().getUserIds();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] expandUserId(int userId) {
        return userId != -1 ? new int[]{userId} : getUsers();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean exists(int userId) {
        return this.mInjector.getUserManager().exists(userId);
    }

    private void enforceShellRestriction(String restriction, int userHandle) {
        if (Binder.getCallingUid() == 2000) {
            if (userHandle < 0 || hasUserRestriction(restriction, userHandle)) {
                throw new SecurityException("Shell does not have permission to access user " + userHandle);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasUserRestriction(String restriction, int userId) {
        return this.mInjector.getUserManager().hasUserRestriction(restriction, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSameProfileGroup(int callingUserId, int targetUserId) {
        boolean z = true;
        if (callingUserId == targetUserId) {
            return true;
        }
        synchronized (this.mLock) {
            int callingProfile = this.mUserProfileGroupIds.get(callingUserId, -10000);
            int targetProfile = this.mUserProfileGroupIds.get(targetUserId, -10000);
            if (callingProfile == -10000 || callingProfile != targetProfile) {
                z = false;
            }
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUserOrItsParentRunning(int userId) {
        synchronized (this.mLock) {
            if (isUserRunning(userId, 0)) {
                return true;
            }
            int parentUserId = this.mUserProfileGroupIds.get(userId, -10000);
            if (parentUserId == -10000) {
                return false;
            }
            return isUserRunning(parentUserId, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCurrentProfile(int userId) {
        boolean contains;
        synchronized (this.mLock) {
            contains = ArrayUtils.contains(this.mCurrentProfileIds, userId);
        }
        return contains;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] getCurrentProfileIds() {
        int[] iArr;
        synchronized (this.mLock) {
            iArr = this.mCurrentProfileIds;
        }
        return iArr;
    }

    void onUserRemoved(int userId) {
        synchronized (this.mLock) {
            int size = this.mUserProfileGroupIds.size();
            for (int i = size - 1; i >= 0; i--) {
                if (this.mUserProfileGroupIds.keyAt(i) == userId || this.mUserProfileGroupIds.valueAt(i) == userId) {
                    this.mUserProfileGroupIds.removeAt(i);
                }
            }
            this.mCurrentProfileIds = ArrayUtils.removeInt(this.mCurrentProfileIds, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean shouldConfirmCredentials(int userId) {
        synchronized (this.mLock) {
            if (this.mStartedUsers.get(userId) == null) {
                return false;
            }
            if (this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)) {
                KeyguardManager km = this.mInjector.getKeyguardManager();
                return km.isDeviceLocked(userId) && km.isDeviceSecure(userId);
            }
            return false;
        }
    }

    boolean isLockScreenDisabled(int userId) {
        return this.mLockPatternUtils.isLockScreenDisabled(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSwitchingFromSystemUserMessage(String switchingFromSystemUserMessage) {
        synchronized (this.mLock) {
            this.mSwitchingFromSystemUserMessage = switchingFromSystemUserMessage;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSwitchingToSystemUserMessage(String switchingToSystemUserMessage) {
        synchronized (this.mLock) {
            this.mSwitchingToSystemUserMessage = switchingToSystemUserMessage;
        }
    }

    private String getSwitchingFromSystemUserMessage() {
        String str;
        synchronized (this.mLock) {
            str = this.mSwitchingFromSystemUserMessage;
        }
        return str;
    }

    private String getSwitchingToSystemUserMessage() {
        String str;
        synchronized (this.mLock) {
            str = this.mSwitchingToSystemUserMessage;
        }
        return str;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        synchronized (this.mLock) {
            long token = proto.start(fieldId);
            for (int i = 0; i < this.mStartedUsers.size(); i++) {
                UserState uss = this.mStartedUsers.valueAt(i);
                long uToken = proto.start(2246267895809L);
                proto.write(1120986464257L, uss.mHandle.getIdentifier());
                uss.writeToProto(proto, 1146756268034L);
                proto.end(uToken);
            }
            for (int i2 = 0; i2 < this.mStartedUserArray.length; i2++) {
                proto.write(2220498092034L, this.mStartedUserArray[i2]);
            }
            for (int i3 = 0; i3 < this.mUserLru.size(); i3++) {
                proto.write(2220498092035L, this.mUserLru.get(i3).intValue());
            }
            if (this.mUserProfileGroupIds.size() > 0) {
                for (int i4 = 0; i4 < this.mUserProfileGroupIds.size(); i4++) {
                    long uToken2 = proto.start(2246267895812L);
                    proto.write(1120986464257L, this.mUserProfileGroupIds.keyAt(i4));
                    proto.write(1120986464258L, this.mUserProfileGroupIds.valueAt(i4));
                    proto.end(uToken2);
                }
            }
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, boolean dumpAll) {
        synchronized (this.mLock) {
            pw.println("  mStartedUsers:");
            for (int i = 0; i < this.mStartedUsers.size(); i++) {
                UserState uss = this.mStartedUsers.valueAt(i);
                pw.print("    User #");
                pw.print(uss.mHandle.getIdentifier());
                pw.print(": ");
                uss.dump("", pw);
            }
            pw.print("  mStartedUserArray: [");
            for (int i2 = 0; i2 < this.mStartedUserArray.length; i2++) {
                if (i2 > 0) {
                    pw.print(", ");
                }
                pw.print(this.mStartedUserArray[i2]);
            }
            pw.println("]");
            pw.print("  mUserLru: [");
            for (int i3 = 0; i3 < this.mUserLru.size(); i3++) {
                if (i3 > 0) {
                    pw.print(", ");
                }
                pw.print(this.mUserLru.get(i3));
            }
            pw.println("]");
            if (this.mUserProfileGroupIds.size() > 0) {
                pw.println("  mUserProfileGroupIds:");
                for (int i4 = 0; i4 < this.mUserProfileGroupIds.size(); i4++) {
                    pw.print("    User #");
                    pw.print(this.mUserProfileGroupIds.keyAt(i4));
                    pw.print(" -> profile #");
                    pw.println(this.mUserProfileGroupIds.valueAt(i4));
                }
            }
            pw.println("  mCurrentUserId:" + this.mCurrentUserId);
            pw.println("  mLastActiveUsers:" + this.mLastActiveUsers);
        }
    }

    @Override // android.os.Handler.Callback
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case 10:
                dispatchUserSwitch((UserState) msg.obj, msg.arg1, msg.arg2);
                return false;
            case 20:
                continueUserSwitch((UserState) msg.obj, msg.arg1, msg.arg2);
                return false;
            case 30:
                timeoutUserSwitch((UserState) msg.obj, msg.arg1, msg.arg2);
                return false;
            case 40:
                startProfiles();
                return false;
            case 50:
                this.mInjector.batteryStatsServiceNoteEvent(32775, Integer.toString(msg.arg1), msg.arg1);
                this.mInjector.getSystemServiceManager().startUser(msg.arg1);
                return false;
            case 60:
                this.mInjector.batteryStatsServiceNoteEvent(16392, Integer.toString(msg.arg2), msg.arg2);
                this.mInjector.batteryStatsServiceNoteEvent(32776, Integer.toString(msg.arg1), msg.arg1);
                this.mInjector.getSystemServiceManager().switchUser(msg.arg1);
                return false;
            case 70:
                dispatchForegroundProfileChanged(msg.arg1);
                return false;
            case 80:
                dispatchUserSwitchComplete(msg.arg1);
                return false;
            case USER_SWITCH_CALLBACKS_TIMEOUT_MSG /* 90 */:
                timeoutUserSwitchCallbacks(msg.arg1, msg.arg2);
                return false;
            case 100:
                final int userId = msg.arg1;
                this.mInjector.getSystemServiceManager().unlockUser(userId);
                FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$avTAix2Aub5zSKSBBofMYY2qXyk
                    @Override // java.lang.Runnable
                    public final void run() {
                        UserController.this.lambda$handleMessage$9$UserController(userId);
                    }
                });
                finishUserUnlocked((UserState) msg.obj);
                return false;
            case REPORT_LOCKED_BOOT_COMPLETE_MSG /* 110 */:
                dispatchLockedBootComplete(msg.arg1);
                return false;
            case START_USER_SWITCH_FG_MSG /* 120 */:
                startUserInForeground(msg.arg1);
                return false;
            case 1000:
                showUserSwitchDialog((Pair) msg.obj);
                return false;
            default:
                return false;
        }
    }

    public /* synthetic */ void lambda$handleMessage$9$UserController(int userId) {
        this.mInjector.loadUserRecents(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class UserProgressListener extends IProgressListener.Stub {
        private volatile long mUnlockStarted;

        private UserProgressListener() {
        }

        public void onStarted(int id, Bundle extras) throws RemoteException {
            Slog.d(UserController.TAG, "Started unlocking user " + id);
            this.mUnlockStarted = SystemClock.uptimeMillis();
        }

        public void onProgress(int id, int progress, Bundle extras) throws RemoteException {
            Slog.d(UserController.TAG, "Unlocking user " + id + " progress " + progress);
        }

        public void onFinished(int id, Bundle extras) throws RemoteException {
            long unlockTime = SystemClock.uptimeMillis() - this.mUnlockStarted;
            if (id == 0) {
                new TimingsTraceLog("SystemServerTiming", 524288L).logDuration("SystemUserUnlock", unlockTime);
                return;
            }
            TimingsTraceLog timingsTraceLog = new TimingsTraceLog("SystemServerTiming", 524288L);
            timingsTraceLog.logDuration("User" + id + "Unlock", unlockTime);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class Injector {
        private final ActivityManagerService mService;
        private UserManagerService mUserManager;
        private UserManagerInternal mUserManagerInternal;

        Injector(ActivityManagerService service) {
            this.mService = service;
        }

        protected Handler getHandler(Handler.Callback callback) {
            return new Handler(this.mService.mHandlerThread.getLooper(), callback);
        }

        protected Handler getUiHandler(Handler.Callback callback) {
            return new Handler(this.mService.mUiHandler.getLooper(), callback);
        }

        protected Context getContext() {
            return this.mService.mContext;
        }

        protected LockPatternUtils getLockPatternUtils() {
            return new LockPatternUtils(getContext());
        }

        protected int broadcastIntent(Intent intent, String resolvedType, IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered, boolean sticky, int callingPid, int callingUid, int realCallingUid, int realCallingPid, int userId) {
            int broadcastIntentLocked;
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    broadcastIntentLocked = this.mService.broadcastIntentLocked(null, null, intent, resolvedType, resultTo, resultCode, resultData, resultExtras, requiredPermissions, appOp, bOptions, ordered, sticky, callingPid, callingUid, realCallingUid, realCallingPid, userId);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            return broadcastIntentLocked;
        }

        int checkCallingPermission(String permission) {
            return this.mService.checkCallingPermission(permission);
        }

        WindowManagerService getWindowManager() {
            return this.mService.mWindowManager;
        }

        void activityManagerOnUserStopped(int userId) {
            ((ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class)).onUserStopped(userId);
        }

        void systemServiceManagerCleanupUser(int userId) {
            this.mService.mSystemServiceManager.cleanupUser(userId);
        }

        protected UserManagerService getUserManager() {
            if (this.mUserManager == null) {
                IBinder b = ServiceManager.getService("user");
                this.mUserManager = IUserManager.Stub.asInterface(b);
            }
            return this.mUserManager;
        }

        UserManagerInternal getUserManagerInternal() {
            if (this.mUserManagerInternal == null) {
                this.mUserManagerInternal = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
            }
            return this.mUserManagerInternal;
        }

        KeyguardManager getKeyguardManager() {
            return (KeyguardManager) this.mService.mContext.getSystemService(KeyguardManager.class);
        }

        void batteryStatsServiceNoteEvent(int code, String name, int uid) {
            this.mService.mBatteryStatsService.noteEvent(code, name, uid);
        }

        boolean isRuntimeRestarted() {
            return this.mService.mSystemServiceManager.isRuntimeRestarted();
        }

        SystemServiceManager getSystemServiceManager() {
            return this.mService.mSystemServiceManager;
        }

        boolean isFirstBootOrUpgrade() {
            IPackageManager pm = AppGlobals.getPackageManager();
            try {
                if (!pm.isFirstBoot()) {
                    if (!pm.isDeviceUpgrading()) {
                        return false;
                    }
                }
                return true;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendPreBootBroadcast(int userId, boolean quiet, final Runnable onFinish) {
            new PreBootBroadcaster(this.mService, userId, null, quiet) { // from class: com.android.server.am.UserController.Injector.1
                @Override // com.android.server.am.PreBootBroadcaster
                public void onFinished() {
                    onFinish.run();
                }
            }.sendNext();
        }

        void activityManagerForceStopPackage(int userId, String reason) {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.forceStopPackageLocked(null, -1, false, false, true, false, false, userId, reason);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        int checkComponentPermission(String permission, int pid, int uid, int owningUid, boolean exported) {
            ActivityManagerService activityManagerService = this.mService;
            return ActivityManagerService.checkComponentPermission(permission, pid, uid, owningUid, exported);
        }

        protected void startHomeActivity(int userId, String reason) {
            this.mService.mAtmInternal.startHomeActivity(userId, reason);
        }

        void startUserWidgets(final int userId) {
            final AppWidgetManagerInternal awm = (AppWidgetManagerInternal) LocalServices.getService(AppWidgetManagerInternal.class);
            if (awm != null) {
                FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$Injector$MYTLl7MOQKjyMJknWdxPeBLoPCc
                    @Override // java.lang.Runnable
                    public final void run() {
                        awm.unlockUser(userId);
                    }
                });
            }
        }

        void updateUserConfiguration() {
            this.mService.mAtmInternal.updateUserConfiguration();
        }

        void clearBroadcastQueueForUser(int userId) {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.clearBroadcastQueueForUserLocked(userId);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        void loadUserRecents(int userId) {
            this.mService.mAtmInternal.loadRecentTasksForUser(userId);
        }

        void startPersistentApps(int matchFlags) {
            this.mService.startPersistentApps(matchFlags);
        }

        void installEncryptionUnawareProviders(int userId) {
            this.mService.installEncryptionUnawareProviders(userId);
        }

        void showUserSwitchingDialog(UserInfo fromUser, UserInfo toUser, String switchingFromSystemUserMessage, String switchingToSystemUserMessage) {
            Dialog d;
            if (!this.mService.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
                ActivityManagerService activityManagerService = this.mService;
                d = new UserSwitchingDialog(activityManagerService, activityManagerService.mContext, fromUser, toUser, true, switchingFromSystemUserMessage, switchingToSystemUserMessage);
            } else {
                ActivityManagerService activityManagerService2 = this.mService;
                d = new CarUserSwitchingDialog(activityManagerService2, activityManagerService2.mContext, fromUser, toUser, true, switchingFromSystemUserMessage, switchingToSystemUserMessage);
            }
            d.show();
        }

        void reportGlobalUsageEventLocked(int event) {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.reportGlobalUsageEventLocked(event);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        void reportCurWakefulnessUsageEvent() {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.reportCurWakefulnessUsageEventLocked();
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        void stackSupervisorRemoveUser(int userId) {
            this.mService.mAtmInternal.removeUser(userId);
        }

        protected boolean stackSupervisorSwitchUser(int userId, UserState uss) {
            return this.mService.mAtmInternal.switchUser(userId, uss);
        }

        protected void stackSupervisorResumeFocusedStackTopActivity() {
            this.mService.mAtmInternal.resumeTopActivities(false);
        }

        protected void clearAllLockedTasks(String reason) {
            this.mService.mAtmInternal.clearLockedTasks(reason);
        }

        protected boolean isCallerRecents(int callingUid) {
            return this.mService.mAtmInternal.isCallerRecents(callingUid);
        }

        protected IStorageManager getStorageManager() {
            return IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));
        }
    }
}
