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
import android.os.StrictMode;
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
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.DumpState;
import com.android.server.pm.UserManagerService;
import com.android.server.wm.WindowManagerService;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    static final int SYSTEM_USER_CURRENT_MSG = 60;
    static final int SYSTEM_USER_START_MSG = 50;
    static final int SYSTEM_USER_UNLOCK_MSG = 100;
    private static final String TAG = "ActivityManager";
    private static final int USER_SWITCH_CALLBACKS_TIMEOUT_MS = 5000;
    static final int USER_SWITCH_CALLBACKS_TIMEOUT_MSG = 90;
    static final int USER_SWITCH_TIMEOUT_MS = 3000;
    static final int USER_SWITCH_TIMEOUT_MSG = 30;
    @GuardedBy("mLock")
    private volatile ArraySet<String> mCurWaitingUserSwitchCallbacks;
    @GuardedBy("mLock")
    private int[] mCurrentProfileIds;
    @GuardedBy("mLock")
    private volatile int mCurrentUserId;
    private final Handler mHandler;
    private final Injector mInjector;
    private final Object mLock;
    private final LockPatternUtils mLockPatternUtils;
    int mMaxRunningUsers;
    @GuardedBy("mLock")
    private int[] mStartedUserArray;
    @GuardedBy("mLock")
    private final SparseArray<UserState> mStartedUsers;
    @GuardedBy("mLock")
    private String mSwitchingFromSystemUserMessage;
    @GuardedBy("mLock")
    private String mSwitchingToSystemUserMessage;
    @GuardedBy("mLock")
    private volatile int mTargetUserId;
    @GuardedBy("mLock")
    private ArraySet<String> mTimeoutUserSwitchCallbacks;
    private final Handler mUiHandler;
    @GuardedBy("mLock")
    private final ArrayList<Integer> mUserLru;
    @GuardedBy("mLock")
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
    public void finishUserSwitch(UserState uss) {
        finishUserBoot(uss);
        startProfiles();
        synchronized (this.mLock) {
            stopRunningUsersLU(this.mMaxRunningUsers);
        }
    }

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

    void stopRunningUsersLU(int maxRunningUsers) {
        List<Integer> currentlyRunning = getRunningUsersLU();
        Iterator<Integer> iterator = currentlyRunning.iterator();
        while (currentlyRunning.size() > maxRunningUsers && iterator.hasNext()) {
            Integer userId = iterator.next();
            if (userId.intValue() != 0 && userId.intValue() != this.mCurrentUserId && stopUsersLU(userId.intValue(), false, null) == 0) {
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
                if (uss.setState(0, 1)) {
                    this.mInjector.getUserManagerInternal().setUserState(userId2, uss.state);
                    if (userId2 == 0 && !this.mInjector.isRuntimeRestarted() && !this.mInjector.isFirstBootOrUpgrade()) {
                        int uptimeSeconds = (int) (SystemClock.elapsedRealtime() / 1000);
                        MetricsLogger.histogram(this.mInjector.getContext(), "framework_locked_boot_completed", uptimeSeconds);
                        if (uptimeSeconds > START_USER_SWITCH_FG_MSG) {
                            Slog.wtf("SystemServerTiming", "finishUserBoot took too long. uptimeSeconds=" + uptimeSeconds);
                        }
                    }
                    this.mHandler.sendMessage(this.mHandler.obtainMessage(110, userId2, 0));
                    Intent intent = new Intent("android.intent.action.LOCKED_BOOT_COMPLETED", (Uri) null);
                    intent.putExtra("android.intent.extra.user_handle", userId2);
                    intent.addFlags(150994944);
                    userId = userId2;
                    this.mInjector.broadcastIntent(intent, null, resultTo, 0, null, null, new String[]{"android.permission.RECEIVE_BOOT_COMPLETED"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, userId);
                } else {
                    userId = userId2;
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

    private void finishUserUnlocking(final UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        if (StorageManager.isUserKeyUnlocked(userId)) {
            synchronized (this.mLock) {
                if (this.mStartedUsers.get(userId) == uss && uss.state == 1) {
                    uss.mUnlockProgress.start();
                    uss.mUnlockProgress.setProgress(5, this.mInjector.getContext().getString(17039472));
                    FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$o6oQFjGYYIfx-I94cSakTLPLt6s
                        @Override // java.lang.Runnable
                        public final void run() {
                            UserController.lambda$finishUserUnlocking$0(UserController.this, userId, uss);
                        }
                    });
                }
            }
        }
    }

    public static /* synthetic */ void lambda$finishUserUnlocking$0(UserController userController, int userId, UserState uss) {
        if (!StorageManager.isUserKeyUnlocked(userId)) {
            Slog.w(TAG, "User key got locked unexpectedly, leaving user locked.");
            return;
        }
        userController.mInjector.getUserManager().onBeforeUnlockUser(userId);
        synchronized (userController.mLock) {
            if (uss.setState(1, 2)) {
                userController.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                uss.mUnlockProgress.setProgress(20);
                userController.mHandler.obtainMessage(100, userId, 0, uss).sendToTarget();
            }
        }
    }

    void finishUserUnlocked(final UserState uss) {
        UserInfo parent;
        int userId = uss.mHandle.getIdentifier();
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
                            this.mInjector.startPersistentApps(DumpState.DUMP_DOMAIN_PREFERRED);
                        }
                        this.mInjector.installEncryptionUnawareProviders(userId);
                        Intent unlockedIntent = new Intent("android.intent.action.USER_UNLOCKED");
                        unlockedIntent.putExtra("android.intent.extra.user_handle", userId);
                        unlockedIntent.addFlags(1342177280);
                        this.mInjector.broadcastIntent(unlockedIntent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, userId);
                        if (getUserInfo(userId).isManagedProfile() && (parent = this.mInjector.getUserManager().getProfileParent(userId)) != null) {
                            Intent profileUnlockedIntent = new Intent("android.intent.action.MANAGED_PROFILE_UNLOCKED");
                            profileUnlockedIntent.putExtra("android.intent.extra.USER", UserHandle.of(userId));
                            profileUnlockedIntent.addFlags(1342177280);
                            this.mInjector.broadcastIntent(profileUnlockedIntent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, parent.id);
                        }
                        UserInfo info = getUserInfo(userId);
                        if (!Objects.equals(info.lastLoggedInFingerprint, Build.FINGERPRINT)) {
                            boolean quiet = false;
                            if (info.isManagedProfile() && (!uss.tokenProvided || !this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId))) {
                                quiet = true;
                            }
                            this.mInjector.sendPreBootBroadcast(userId, quiet, new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$d0zeElfogOIugnQQLWhCzumk53k
                                @Override // java.lang.Runnable
                                public final void run() {
                                    UserController.this.finishUserUnlockedCompleted(uss);
                                }
                            });
                            return;
                        }
                        finishUserUnlockedCompleted(uss);
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
    public void finishUserUnlockedCompleted(UserState uss) {
        int userId;
        int oldMaskone;
        int userId2 = uss.mHandle.getIdentifier();
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
                final UserInfo userInfo = getUserInfo(userId2);
                if (userInfo == null || !StorageManager.isUserKeyUnlocked(userId2)) {
                    return;
                }
                this.mInjector.getUserManager().onUserLoggedIn(userId2);
                if (userInfo.isInitialized() || userId2 == 0) {
                    userId = userId2;
                } else {
                    Slog.d(TAG, "Initializing user #" + userId2);
                    Intent intent = new Intent("android.intent.action.USER_INITIALIZE");
                    intent.addFlags(285212672);
                    userId = userId2;
                    this.mInjector.broadcastIntent(intent, null, new IIntentReceiver.Stub() { // from class: com.android.server.am.UserController.1
                        public void performReceive(Intent intent2, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                            UserController.this.mInjector.getUserManager().makeInitialized(userInfo.id);
                        }
                    }, 0, null, null, null, -1, null, true, false, ActivityManagerService.MY_PID, 1000, userId);
                }
                int oldMaskone2 = StrictMode.allowThreadDiskReadsMask();
                StrictMode.allowThreadDiskWritesMask();
                try {
                    Slog.i(TAG, "bein startUserWidgets");
                    final int userId3 = userId;
                    try {
                        this.mInjector.startUserWidgets(userId3);
                        Slog.i(TAG, "end startUserWidgets");
                        StrictMode.setThreadPolicyMask(oldMaskone2);
                        Slog.i(TAG, "Sending BOOT_COMPLETE user #" + userId3);
                        if (userId3 == 0 && !this.mInjector.isRuntimeRestarted() && !this.mInjector.isFirstBootOrUpgrade()) {
                            int uptimeSeconds = (int) (SystemClock.elapsedRealtime() / 1000);
                            MetricsLogger.histogram(this.mInjector.getContext(), "framework_boot_completed", uptimeSeconds);
                        }
                        Intent bootIntent = new Intent("android.intent.action.BOOT_COMPLETED", (Uri) null);
                        bootIntent.putExtra("android.intent.extra.user_handle", userId3);
                        bootIntent.addFlags(150994944);
                        this.mInjector.broadcastIntent(bootIntent, null, new IIntentReceiver.Stub() { // from class: com.android.server.am.UserController.2
                            public void performReceive(Intent intent2, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
                                Slog.i(UserController.TAG, "Finished processing BOOT_COMPLETED for u" + userId3);
                            }
                        }, 0, null, null, new String[]{"android.permission.RECEIVE_BOOT_COMPLETED"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, userId3);
                    } catch (Throwable th3) {
                        th = th3;
                        oldMaskone = oldMaskone2;
                        StrictMode.setThreadPolicyMask(oldMaskone);
                        throw th;
                    }
                } catch (Throwable th4) {
                    th = th4;
                    oldMaskone = oldMaskone2;
                }
            } catch (Throwable th5) {
                th = th5;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.am.UserController$3  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass3 extends IStopUserCallback.Stub {
        final /* synthetic */ boolean val$foreground;

        AnonymousClass3(boolean z) {
            this.val$foreground = z;
        }

        public void userStopped(final int userId) {
            Handler handler = UserController.this.mHandler;
            final boolean z = this.val$foreground;
            handler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$3$DwbhQjwQF2qoVH0y07dd4wykxRA
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.this.startUser(userId, z);
                }
            });
        }

        public void userStopAborted(int userId) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int restartUser(int userId, boolean foreground) {
        return stopUser(userId, true, new AnonymousClass3(foreground));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int stopUser(int userId, boolean force, IStopUserCallback callback) {
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
                stopUsersLU = stopUsersLU(userId, force, callback);
            }
            return stopUsersLU;
        }
    }

    private int stopUsersLU(int userId, boolean force, IStopUserCallback callback) {
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
                    stopSingleUserLU(userId, callback);
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
            stopSingleUserLU(userIdToStop, userIdToStop == userId ? callback : null);
        }
        return 0;
    }

    private void stopSingleUserLU(final int userId, final IStopUserCallback callback) {
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.i(TAG, "stopSingleUserLocked userId=" + userId);
        }
        final UserState uss = this.mStartedUsers.get(userId);
        if (uss == null) {
            if (callback != null) {
                this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$AHHTCREuropaUGilzG-tndQCCSM
                    @Override // java.lang.Runnable
                    public final void run() {
                        callback.userStopped(userId);
                    }
                });
                return;
            }
            return;
        }
        if (callback != null) {
            uss.mStopCallbacks.add(callback);
        }
        if (uss.state != 4 && uss.state != 5) {
            uss.setState(4);
            this.mInjector.getUserManagerInternal().setUserState(userId, uss.state);
            updateStartedUserArrayLU();
            this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$GGvEPHwny2cP0yTZnJTgitTq9_U
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.lambda$stopSingleUserLU$3(UserController.this, userId, uss);
                }
            });
        }
    }

    public static /* synthetic */ void lambda$stopSingleUserLU$3(UserController userController, int userId, UserState uss) {
        Intent stoppingIntent = new Intent("android.intent.action.USER_STOPPING");
        stoppingIntent.addFlags(1073741824);
        stoppingIntent.putExtra("android.intent.extra.user_handle", userId);
        stoppingIntent.putExtra("android.intent.extra.SHUTDOWN_USERSPACE_ONLY", true);
        IIntentReceiver stoppingReceiver = new AnonymousClass4(userId, uss);
        userController.mInjector.clearBroadcastQueueForUser(userId);
        userController.mInjector.broadcastIntent(stoppingIntent, null, stoppingReceiver, 0, null, null, new String[]{"android.permission.INTERACT_ACROSS_USERS"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, -1);
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

        public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
            Handler handler = UserController.this.mHandler;
            final int i = this.val$userId;
            final UserState userState = this.val$uss;
            handler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$4$P3Sj7pxBXLC7k_puCIIki2uVgGE
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.this.finishUserStopping(i, userState);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishUserStopping(int userId, final UserState uss) {
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
            this.mInjector.broadcastIntent(shutdownIntent, null, shutdownReceiver, 0, null, null, null, -1, null, true, false, ActivityManagerService.MY_PID, 1000, userId);
        }
    }

    void finishUserStopped(UserState uss) {
        ArrayList<IStopUserCallback> callbacks;
        boolean stopped;
        final int userId = uss.mHandle.getIdentifier();
        synchronized (this.mLock) {
            callbacks = new ArrayList<>(uss.mStopCallbacks);
            if (this.mStartedUsers.get(userId) == uss && uss.state == 5) {
                stopped = true;
                this.mStartedUsers.remove(userId);
                this.mUserLru.remove(Integer.valueOf(userId));
                updateStartedUserArrayLU();
            }
            stopped = false;
        }
        boolean stopped2 = stopped;
        if (stopped2) {
            this.mInjector.getUserManagerInternal().removeUserState(userId);
            this.mInjector.activityManagerOnUserStopped(userId);
            forceStopUser(userId, "finish user");
        }
        for (int i = 0; i < callbacks.size(); i++) {
            if (stopped2) {
                try {
                    callbacks.get(i).userStopped(userId);
                } catch (RemoteException e) {
                }
            } else {
                callbacks.get(i).userStopAborted(userId);
            }
        }
        if (stopped2) {
            this.mInjector.systemServiceManagerCleanupUser(userId);
            this.mInjector.stackSupervisorRemoveUser(userId);
            if (getUserInfo(userId).isEphemeral()) {
                this.mInjector.getUserManager().removeUserEvenWhenDisallowed(userId);
            }
            FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$OCWSENtTocgCKtAUTrbiQWfjiB4
                @Override // java.lang.Runnable
                public final void run() {
                    UserController.lambda$finishUserStopped$4(UserController.this, userId);
                }
            });
        }
    }

    public static /* synthetic */ void lambda$finishUserStopped$4(UserController userController, int userId) {
        synchronized (userController.mLock) {
            if (userController.mStartedUsers.get(userId) != null) {
                Slog.w(TAG, "User was restarted, skipping key eviction");
                return;
            }
            try {
                userController.getStorageManager().lockUserKey(userId);
            } catch (RemoteException re) {
                throw re.rethrowAsRuntimeException();
            }
        }
    }

    private int[] getUsersToStopLU(int userId) {
        boolean sameGroup;
        int startedUsersSize = this.mStartedUsers.size();
        IntArray userIds = new IntArray();
        userIds.add(userId);
        int userGroupId = this.mUserProfileGroupIds.get(userId, -10000);
        for (int i = 0; i < startedUsersSize; i++) {
            UserState uss = this.mStartedUsers.valueAt(i);
            int startedUserId = uss.mHandle.getIdentifier();
            int startedUserGroupId = this.mUserProfileGroupIds.get(startedUserId, -10000);
            boolean sameUserId = true;
            if (userGroupId == -10000 || userGroupId != startedUserGroupId) {
                sameGroup = false;
            } else {
                sameGroup = true;
            }
            if (startedUserId != userId) {
                sameUserId = false;
            }
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
        this.mInjector.broadcastIntent(intent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, -1);
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
                        stopUsersLU(oldUserId, true, null);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleStartProfiles() {
        FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$qvHU3An7LT0SKBclx4I2epe4KYI
            @Override // java.lang.Runnable
            public final void run() {
                UserController.lambda$scheduleStartProfiles$5(UserController.this);
            }
        });
    }

    public static /* synthetic */ void lambda$scheduleStartProfiles$5(UserController userController) {
        if (!userController.mHandler.hasMessages(40)) {
            userController.mHandler.sendMessageDelayed(userController.mHandler.obtainMessage(40), 1000L);
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

    private IStorageManager getStorageManager() {
        return IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean startUser(int userId, boolean foreground) {
        return startUser(userId, foreground, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean startUser(final int userId, final boolean foreground, final IProgressListener unlockListener) {
        long ident;
        UserState uss;
        int oldUserId;
        long ident2;
        boolean z;
        int i;
        if (this.mInjector.checkCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            String msg = "Permission Denial: switchUser() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS_FULL";
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        Slog.i(TAG, "Starting userid:" + userId + " fg:" + foreground);
        long ident3 = Binder.clearCallingIdentity();
        try {
            int oldUserId2 = getCurrentUserId();
            if (oldUserId2 == userId) {
                Binder.restoreCallingIdentity(ident3);
                return true;
            }
            if (foreground) {
                try {
                    this.mInjector.clearAllLockedTasks("startUser");
                } catch (Throwable th) {
                    th = th;
                    ident = ident3;
                    Binder.restoreCallingIdentity(ident);
                    throw th;
                }
            }
            UserInfo userInfo = getUserInfo(userId);
            if (userInfo == null) {
                Slog.w(TAG, "No user info for user #" + userId);
                Binder.restoreCallingIdentity(ident3);
                return false;
            } else if (foreground && userInfo.isManagedProfile()) {
                Slog.w(TAG, "Cannot switch to User #" + userId + ": not a full user");
                Binder.restoreCallingIdentity(ident3);
                return false;
            } else {
                if (foreground && this.mUserSwitchUiEnabled) {
                    this.mInjector.getWindowManager().startFreezingScreen(17432710, 17432709);
                }
                boolean needStart = false;
                boolean updateUmState = false;
                try {
                    synchronized (this.mLock) {
                        try {
                            UserState uss2 = this.mStartedUsers.get(userId);
                            try {
                                if (uss2 == null) {
                                    uss2 = new UserState(UserHandle.of(userId));
                                    uss2.mUnlockProgress.addListener(new UserProgressListener());
                                    this.mStartedUsers.put(userId, uss2);
                                    updateStartedUserArrayLU();
                                    updateUmState = true;
                                    needStart = true;
                                } else if (uss2.state == 5 && !isCallingOnHandlerThread()) {
                                    Slog.i(TAG, "User #" + userId + " is shutting down - will start after full stop");
                                    this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$itozNmdxq9RsTKqW4_f-sH8yPdY
                                        @Override // java.lang.Runnable
                                        public final void run() {
                                            UserController.this.startUser(userId, foreground, unlockListener);
                                        }
                                    });
                                    Binder.restoreCallingIdentity(ident3);
                                    return true;
                                }
                                boolean updateUmState2 = updateUmState;
                                try {
                                    Integer userIdInt = Integer.valueOf(userId);
                                    this.mUserLru.remove(userIdInt);
                                    this.mUserLru.add(userIdInt);
                                    if (unlockListener != null) {
                                        uss2.mUnlockProgress.addListener(unlockListener);
                                    }
                                    if (updateUmState2) {
                                        this.mInjector.getUserManagerInternal().setUserState(userId, uss2.state);
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
                                    if (uss2.state == 4) {
                                        uss2.setState(uss2.lastState);
                                        this.mInjector.getUserManagerInternal().setUserState(userId, uss2.state);
                                        synchronized (this.mLock) {
                                            updateStartedUserArrayLU();
                                        }
                                        needStart = true;
                                    } else if (uss2.state == 5) {
                                        uss2.setState(0);
                                        this.mInjector.getUserManagerInternal().setUserState(userId, uss2.state);
                                        synchronized (this.mLock) {
                                            updateStartedUserArrayLU();
                                        }
                                        needStart = true;
                                    }
                                    boolean needStart2 = needStart;
                                    if (uss2.state == 0) {
                                        this.mInjector.getUserManager().onBeforeStartUser(userId);
                                        this.mHandler.sendMessage(this.mHandler.obtainMessage(50, userId, 0));
                                    }
                                    if (foreground) {
                                        this.mHandler.sendMessage(this.mHandler.obtainMessage(60, userId, oldUserId2));
                                        this.mHandler.removeMessages(10);
                                        this.mHandler.removeMessages(30);
                                        this.mHandler.sendMessage(this.mHandler.obtainMessage(10, oldUserId2, userId, uss2));
                                        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(30, oldUserId2, userId, uss2), 3000L);
                                    }
                                    if (needStart2) {
                                        try {
                                            Intent intent = new Intent("android.intent.action.USER_STARTED");
                                            intent.addFlags(1342177280);
                                            intent.putExtra("android.intent.extra.user_handle", userId);
                                            uss = uss2;
                                            oldUserId = oldUserId2;
                                            ident2 = ident3;
                                            z = foreground;
                                            try {
                                                this.mInjector.broadcastIntent(intent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, userId);
                                            } catch (Throwable th2) {
                                                th = th2;
                                                ident = ident2;
                                                Binder.restoreCallingIdentity(ident);
                                                throw th;
                                            }
                                        } catch (Throwable th3) {
                                            th = th3;
                                            ident = ident3;
                                            Binder.restoreCallingIdentity(ident);
                                            throw th;
                                        }
                                    } else {
                                        uss = uss2;
                                        oldUserId = oldUserId2;
                                        ident2 = ident3;
                                        z = foreground;
                                    }
                                    if (z) {
                                        i = userId;
                                        try {
                                            moveUserToForeground(uss, oldUserId, i);
                                        } catch (Throwable th4) {
                                            th = th4;
                                            ident = ident2;
                                            Binder.restoreCallingIdentity(ident);
                                            throw th;
                                        }
                                    } else {
                                        i = userId;
                                        try {
                                            finishUserBoot(uss);
                                        } catch (Throwable th5) {
                                            th = th5;
                                            ident = ident2;
                                            Binder.restoreCallingIdentity(ident);
                                            throw th;
                                        }
                                    }
                                    if (needStart2) {
                                        Intent intent2 = new Intent("android.intent.action.USER_STARTING");
                                        intent2.addFlags(1073741824);
                                        intent2.putExtra("android.intent.extra.user_handle", i);
                                        this.mInjector.broadcastIntent(intent2, null, new IIntentReceiver.Stub() { // from class: com.android.server.am.UserController.6
                                            public void performReceive(Intent intent3, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
                                            }
                                        }, 0, null, null, new String[]{"android.permission.INTERACT_ACROSS_USERS"}, -1, null, true, false, ActivityManagerService.MY_PID, 1000, -1);
                                    }
                                    Binder.restoreCallingIdentity(ident2);
                                    return true;
                                } catch (Throwable th6) {
                                    th = th6;
                                    while (true) {
                                        try {
                                            break;
                                        } catch (Throwable th7) {
                                            th = th7;
                                        }
                                    }
                                    throw th;
                                }
                            } catch (Throwable th8) {
                                th = th8;
                            }
                        } catch (Throwable th9) {
                            th = th9;
                        }
                    }
                }
            }
        } catch (Throwable th10) {
            th = th10;
            ident = ident3;
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
            IStorageManager storageManager = getStorageManager();
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
        }
        finishUserUnlocking(uss);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean switchUser(int targetUserId) {
        enforceShellRestriction("no_debugging_features", targetUserId);
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
                this.mHandler.sendMessage(this.mHandler.obtainMessage(START_USER_SWITCH_FG_MSG, targetUserId, 0));
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
        boolean disallowRunInBg = hasUserRestriction("no_run_in_background", oldUserId);
        if (!disallowRunInBg) {
            return;
        }
        synchronized (this.mLock) {
            if (ActivityManagerDebugConfig.DEBUG_MU) {
                Slog.i(TAG, "stopBackgroundUsersIfEnforced stopping " + oldUserId + " and related users");
            }
            stopUsersLU(oldUserId, false, null);
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

    void dispatchUserSwitch(UserState uss, final int oldUserId, final int newUserId) {
        UserState userState;
        int observerCount;
        int observerCount2;
        Slog.d(TAG, "Dispatch onUserSwitching oldUser #" + oldUserId + " newUser #" + newUserId);
        int observerCount3 = this.mUserSwitchObservers.beginBroadcast();
        if (observerCount3 > 0) {
            final ArraySet<String> curWaitingUserSwitchCallbacks = new ArraySet<>();
            synchronized (this.mLock) {
                userState = uss;
                try {
                    userState.switching = true;
                    this.mCurWaitingUserSwitchCallbacks = curWaitingUserSwitchCallbacks;
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
            final AtomicInteger waitingCallbacksCount = new AtomicInteger(observerCount3);
            final long dispatchStartedTime = SystemClock.elapsedRealtime();
            int i = 0;
            while (true) {
                int i2 = i;
                if (i2 >= observerCount3) {
                    break;
                }
                try {
                    final String name = AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR + i2 + " " + this.mUserSwitchObservers.getBroadcastCookie(i2);
                    synchronized (this.mLock) {
                        curWaitingUserSwitchCallbacks.add(name);
                    }
                    final UserState userState2 = userState;
                    observerCount = observerCount3;
                    observerCount2 = i2;
                    try {
                        this.mUserSwitchObservers.getBroadcastItem(observerCount2).onUserSwitching(newUserId, new IRemoteCallback.Stub() { // from class: com.android.server.am.UserController.7
                            public void sendResult(Bundle data) throws RemoteException {
                                synchronized (UserController.this.mLock) {
                                    long delay = SystemClock.elapsedRealtime() - dispatchStartedTime;
                                    if (delay > 3000) {
                                        Slog.e(UserController.TAG, "User switch timeout: observer " + name + " sent result after " + delay + " ms");
                                    }
                                    curWaitingUserSwitchCallbacks.remove(name);
                                    if (waitingCallbacksCount.decrementAndGet() == 0 && curWaitingUserSwitchCallbacks == UserController.this.mCurWaitingUserSwitchCallbacks) {
                                        UserController.this.sendContinueUserSwitchLU(userState2, oldUserId, newUserId);
                                    }
                                }
                            }
                        });
                    } catch (RemoteException e) {
                    }
                } catch (RemoteException e2) {
                    observerCount = observerCount3;
                    observerCount2 = i2;
                }
                i = observerCount2 + 1;
                userState = uss;
                observerCount3 = observerCount;
            }
        } else {
            synchronized (this.mLock) {
                sendContinueUserSwitchLU(uss, oldUserId, newUserId);
            }
        }
        this.mUserSwitchObservers.finishBroadcast();
    }

    void sendContinueUserSwitchLU(UserState uss, int oldUserId, int newUserId) {
        this.mCurWaitingUserSwitchCallbacks = null;
        this.mHandler.removeMessages(30);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(20, oldUserId, newUserId, uss));
    }

    void continueUserSwitch(UserState uss, int oldUserId, int newUserId) {
        Slog.d(TAG, "Continue user switch oldUser #" + oldUserId + ", newUser #" + newUserId);
        if (this.mUserSwitchUiEnabled) {
            this.mInjector.getWindowManager().stopFreezingScreen();
        }
        uss.switching = false;
        this.mHandler.removeMessages(80);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(80, newUserId, 0));
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
        long ident = Binder.clearCallingIdentity();
        if (oldUserId >= 0) {
            try {
                List<UserInfo> profiles = this.mInjector.getUserManager().getProfiles(oldUserId, false);
                int count = profiles.size();
                for (int i = 0; i < count; i++) {
                    int profileUserId = profiles.get(i).id;
                    Intent intent = new Intent("android.intent.action.USER_BACKGROUND");
                    intent.addFlags(1342177280);
                    intent.putExtra("android.intent.extra.user_handle", profileUserId);
                    this.mInjector.broadcastIntent(intent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, profileUserId);
                }
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
        }
        if (newUserId >= 0) {
            List<UserInfo> profiles2 = this.mInjector.getUserManager().getProfiles(newUserId, false);
            int count2 = profiles2.size();
            for (int i2 = 0; i2 < count2; i2++) {
                int profileUserId2 = profiles2.get(i2).id;
                Intent intent2 = new Intent("android.intent.action.USER_FOREGROUND");
                intent2.addFlags(1342177280);
                intent2.putExtra("android.intent.extra.user_handle", profileUserId2);
                this.mInjector.broadcastIntent(intent2, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, profileUserId2);
            }
            Intent intent3 = new Intent("android.intent.action.USER_SWITCHED");
            intent3.addFlags(1342177280);
            intent3.putExtra("android.intent.extra.user_handle", newUserId);
            this.mInjector.broadcastIntent(intent3, null, null, 0, null, null, new String[]{"android.permission.MANAGE_USERS"}, -1, null, false, false, ActivityManagerService.MY_PID, 1000, -1);
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
                    builder.append(" but is calling from user ");
                    builder.append(UserHandle.getUserId(callingUid));
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
        return this.mStartedUsers.get(userId) != null;
    }

    private void updateStartedUserArrayLU() {
        int num = 0;
        for (int num2 = 0; num2 < this.mStartedUsers.size(); num2++) {
            UserState uss = this.mStartedUsers.valueAt(num2);
            if (uss.state != 4 && uss.state != 5) {
                num++;
            }
        }
        this.mStartedUserArray = new int[num];
        int num3 = 0;
        for (int i = 0; i < this.mStartedUsers.size(); i++) {
            UserState uss2 = this.mStartedUsers.valueAt(i);
            if (uss2.state != 4 && uss2.state != 5) {
                this.mStartedUserArray[num3] = this.mStartedUsers.keyAt(i);
                num3++;
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
            switch (state.state) {
                case 0:
                case 1:
                    return true;
                default:
                    return false;
            }
        } else if ((flags & 8) != 0) {
            switch (state.state) {
                case 2:
                case 3:
                    return true;
                case 4:
                case 5:
                    return StorageManager.isUserKeyUnlocked(userId);
                default:
                    return false;
            }
        } else if ((flags & 4) == 0) {
            return (state.state == 4 || state.state == 5) ? false : true;
        } else {
            switch (state.state) {
                case 3:
                    return true;
                case 4:
                case 5:
                    return StorageManager.isUserKeyUnlocked(userId);
                default:
                    return false;
            }
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

    int getCurrentOrTargetUserIdLU() {
        return this.mTargetUserId != -10000 ? this.mTargetUserId : this.mCurrentUserId;
    }

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

    private boolean isCurrentUserLU(int userId) {
        return userId == getCurrentOrTargetUserIdLU();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] getUsers() {
        UserManagerService ums = this.mInjector.getUserManager();
        return ums != null ? ums.getUserIds() : new int[]{0};
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserInfo getUserInfo(int userId) {
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

    void enforceShellRestriction(String restriction, int userHandle) {
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
    public Set<Integer> getProfileIds(int userId) {
        Set<Integer> userIds = new HashSet<>();
        List<UserInfo> profiles = this.mInjector.getUserManager().getProfiles(userId, false);
        for (UserInfo user : profiles) {
            userIds.add(Integer.valueOf(user.id));
        }
        return userIds;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUserRemoved(int userId) {
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
                uss.dump(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, pw);
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
                FgThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$UserController$dpKWakbnwonBpCp5_FOiINcMU6s
                    @Override // java.lang.Runnable
                    public final void run() {
                        UserController.this.mInjector.loadUserRecents(userId);
                    }
                });
                finishUserUnlocked((UserState) msg.obj);
                return false;
            case 110:
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
            Slog.d(UserController.TAG, "Unlocking user " + id + " took " + unlockTime + " ms");
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

        protected int broadcastIntent(Intent intent, String resolvedType, IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered, boolean sticky, int callingPid, int callingUid, int userId) {
            int broadcastIntentLocked;
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    broadcastIntentLocked = this.mService.broadcastIntentLocked(null, null, intent, resolvedType, resultTo, resultCode, resultData, resultExtras, requiredPermissions, appOp, bOptions, ordered, sticky, callingPid, callingUid, userId);
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
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.onUserStoppedLocked(userId);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
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
                    if (!pm.isUpgrade()) {
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
            return this.mService.checkComponentPermission(permission, pid, uid, owningUid, exported);
        }

        protected void startHomeActivity(int userId, String reason) {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.startHomeActivityLocked(userId, reason);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        void startUserWidgets(int userId) {
            AppWidgetManagerInternal awm = (AppWidgetManagerInternal) LocalServices.getService(AppWidgetManagerInternal.class);
            if (awm != null) {
                awm.unlockUser(userId);
            }
        }

        void updateUserConfiguration() {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.updateUserConfigurationLocked();
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
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

        /* JADX INFO: Access modifiers changed from: package-private */
        public void loadUserRecents(int userId) {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.getRecentTasks().loadUserRecentsLocked(userId);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
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
                d = new UserSwitchingDialog(this.mService, this.mService.mContext, fromUser, toUser, true, switchingFromSystemUserMessage, switchingToSystemUserMessage);
            } else {
                d = new CarUserSwitchingDialog(this.mService, this.mService.mContext, fromUser, toUser, true, switchingFromSystemUserMessage, switchingToSystemUserMessage);
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
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.mStackSupervisor.removeUserLocked(userId);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        protected boolean stackSupervisorSwitchUser(int userId, UserState uss) {
            boolean switchUserLocked;
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    switchUserLocked = this.mService.mStackSupervisor.switchUserLocked(userId, uss);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            return switchUserLocked;
        }

        protected void stackSupervisorResumeFocusedStackTopActivity() {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.mStackSupervisor.resumeFocusedStackTopActivityLocked();
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        protected void clearAllLockedTasks(String reason) {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mService.getLockTaskController().clearLockedTasks(reason);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        protected boolean isCallerRecents(int callingUid) {
            return this.mService.getRecentTasks().isCallerRecents(callingUid);
        }
    }
}
