package com.android.server.pm;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.IUserManager;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.security.GateKeeper;
import android.service.gatekeeper.IGateKeeperService;
import android.util.AtomicFile;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsService;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.SystemService;
import com.android.server.am.UserState;
import com.android.server.backup.BackupManagerConstants;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
public class UserManagerService extends IUserManager.Stub {
    private static final int ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION = 812;
    private static final String ATTR_CREATION_TIME = "created";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_GUEST_TO_REMOVE = "guestToRemove";
    private static final String ATTR_ICON_PATH = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_LAST_LOGGED_IN_FINGERPRINT = "lastLoggedInFingerprint";
    private static final String ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn";
    private static final String ATTR_MULTIPLE = "m";
    private static final String ATTR_NEXT_SERIAL_NO = "nextSerialNumber";
    private static final String ATTR_PARTIAL = "partial";
    private static final String ATTR_PROFILE_BADGE = "profileBadge";
    private static final String ATTR_PROFILE_GROUP_ID = "profileGroupId";
    private static final String ATTR_RESTRICTED_PROFILE_PARENT_ID = "restrictedProfileParentId";
    private static final String ATTR_SEED_ACCOUNT_NAME = "seedAccountName";
    private static final String ATTR_SEED_ACCOUNT_TYPE = "seedAccountType";
    private static final String ATTR_SERIAL_NO = "serialNumber";
    private static final String ATTR_TYPE_BOOLEAN = "b";
    private static final String ATTR_TYPE_BUNDLE = "B";
    private static final String ATTR_TYPE_BUNDLE_ARRAY = "BA";
    private static final String ATTR_TYPE_INTEGER = "i";
    private static final String ATTR_TYPE_STRING = "s";
    private static final String ATTR_TYPE_STRING_ARRAY = "sa";
    private static final String ATTR_USER_VERSION = "version";
    private static final String ATTR_VALUE_TYPE = "type";
    static final boolean DBG = false;
    private static final boolean DBG_WITH_STACKTRACE = false;
    private static final long EPOCH_PLUS_30_YEARS = 946080000000L;
    private static final String LOG_TAG = "UserManagerService";
    @VisibleForTesting
    static final int MAX_MANAGED_PROFILES = 1;
    @VisibleForTesting
    static final int MAX_RECENTLY_REMOVED_IDS_SIZE = 100;
    @VisibleForTesting
    static final int MAX_USER_ID = 21474;
    @VisibleForTesting
    static final int MIN_USER_ID = 10;
    private static final boolean RELEASE_DELETED_USER_ID = false;
    private static final String RESTRICTIONS_FILE_PREFIX = "res_";
    private static final String TAG_ACCOUNT = "account";
    private static final String TAG_DEVICE_OWNER_USER_ID = "deviceOwnerUserId";
    private static final String TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS = "device_policy_global_restrictions";
    private static final String TAG_DEVICE_POLICY_RESTRICTIONS = "device_policy_restrictions";
    private static final String TAG_ENTRY = "entry";
    private static final String TAG_GLOBAL_RESTRICTION_OWNER_ID = "globalRestrictionOwnerUserId";
    private static final String TAG_GUEST_RESTRICTIONS = "guestRestrictions";
    private static final String TAG_NAME = "name";
    private static final String TAG_RESTRICTIONS = "restrictions";
    private static final String TAG_SEED_ACCOUNT_OPTIONS = "seedAccountOptions";
    private static final String TAG_USER = "user";
    private static final String TAG_USERS = "users";
    private static final String TAG_VALUE = "value";
    private static final String TRON_DEMO_CREATED = "users_demo_created";
    private static final String TRON_GUEST_CREATED = "users_guest_created";
    private static final String TRON_USER_CREATED = "users_user_created";
    private static final String USER_LIST_FILENAME = "userlist.xml";
    private static final String USER_PHOTO_FILENAME = "photo.png";
    private static final String USER_PHOTO_FILENAME_TMP = "photo.png.tmp";
    private static final int USER_VERSION = 7;
    static final int WRITE_USER_DELAY = 2000;
    static final int WRITE_USER_MSG = 1;
    private static final String XML_SUFFIX = ".xml";
    private static UserManagerService sInstance;
    private final String ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK;
    private IAppOpsService mAppOpsService;
    private final Object mAppRestrictionsLock;
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mAppliedUserRestrictions;
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mBaseUserRestrictions;
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mCachedEffectiveUserRestrictions;
    private final Context mContext;
    @GuardedBy("mRestrictionsLock")
    private int mDeviceOwnerUserId;
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mDevicePolicyGlobalUserRestrictions;
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mDevicePolicyLocalUserRestrictions;
    private final BroadcastReceiver mDisableQuietModeCallback;
    @GuardedBy("mUsersLock")
    private boolean mForceEphemeralUsers;
    @GuardedBy("mGuestRestrictions")
    private final Bundle mGuestRestrictions;
    private final Handler mHandler;
    @GuardedBy("mUsersLock")
    private boolean mIsDeviceManaged;
    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mIsUserManaged;
    private final LocalService mLocalService;
    private final LockPatternUtils mLockPatternUtils;
    @GuardedBy("mPackagesLock")
    private int mNextSerialNumber;
    private final Object mPackagesLock;
    private final PackageManagerService mPm;
    @GuardedBy("mUsersLock")
    private final LinkedList<Integer> mRecentlyRemovedIds;
    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mRemovingUserIds;
    private final Object mRestrictionsLock;
    private final UserDataPreparer mUserDataPreparer;
    @GuardedBy("mUsersLock")
    private int[] mUserIds;
    private final File mUserListFile;
    @GuardedBy("mUserRestrictionsListeners")
    private final ArrayList<UserManagerInternal.UserRestrictionsListener> mUserRestrictionsListeners;
    @GuardedBy("mUserStates")
    private final SparseIntArray mUserStates;
    private int mUserVersion;
    @GuardedBy("mUsersLock")
    private final SparseArray<UserData> mUsers;
    private final File mUsersDir;
    private final Object mUsersLock;
    private static final String USER_INFO_DIR = "system" + File.separator + "users";
    private static final IBinder mUserRestriconToken = new Binder();

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class UserData {
        String account;
        UserInfo info;
        boolean persistSeedData;
        String seedAccountName;
        PersistableBundle seedAccountOptions;
        String seedAccountType;
        long startRealtime;
        long unlockRealtime;

        UserData() {
        }

        void clearSeedAccountData() {
            this.seedAccountName = null;
            this.seedAccountType = null;
            this.seedAccountOptions = null;
            this.persistSeedData = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.pm.UserManagerService$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 extends BroadcastReceiver {
        AnonymousClass1() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (!"com.android.server.pm.DISABLE_QUIET_MODE_AFTER_UNLOCK".equals(intent.getAction())) {
                return;
            }
            final IntentSender target = (IntentSender) intent.getParcelableExtra("android.intent.extra.INTENT");
            final int userHandle = intent.getIntExtra("android.intent.extra.USER_ID", -10000);
            BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$UserManagerService$1$DQ_02g7kZ7QrJXO6aCATwE6DYCE
                @Override // java.lang.Runnable
                public final void run() {
                    UserManagerService.this.setQuietModeEnabled(userHandle, false, target);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class DisableQuietModeUserUnlockedCallback extends IProgressListener.Stub {
        private final IntentSender mTarget;

        public DisableQuietModeUserUnlockedCallback(IntentSender target) {
            Preconditions.checkNotNull(target);
            this.mTarget = target;
        }

        public void onStarted(int id, Bundle extras) {
        }

        public void onProgress(int id, int progress, Bundle extras) {
        }

        public void onFinished(int id, Bundle extras) {
            try {
                UserManagerService.this.mContext.startIntentSender(this.mTarget, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                Slog.e(UserManagerService.LOG_TAG, "Failed to start the target in the callback", e);
            }
        }
    }

    public static UserManagerService getInstance() {
        UserManagerService userManagerService;
        synchronized (UserManagerService.class) {
            userManagerService = sInstance;
        }
        return userManagerService;
    }

    /* loaded from: classes.dex */
    public static class LifeCycle extends SystemService {
        private UserManagerService mUms;

        public LifeCycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mUms = UserManagerService.getInstance();
            publishBinderService(UserManagerService.TAG_USER, this.mUms);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            if (phase == 550) {
                this.mUms.cleanupPartialUsers();
            }
        }

        @Override // com.android.server.SystemService
        public void onStartUser(int userHandle) {
            synchronized (this.mUms.mUsersLock) {
                UserData user = this.mUms.getUserDataLU(userHandle);
                if (user != null) {
                    user.startRealtime = SystemClock.elapsedRealtime();
                }
            }
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            synchronized (this.mUms.mUsersLock) {
                UserData user = this.mUms.getUserDataLU(userHandle);
                if (user != null) {
                    user.unlockRealtime = SystemClock.elapsedRealtime();
                }
            }
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userHandle) {
            synchronized (this.mUms.mUsersLock) {
                UserData user = this.mUms.getUserDataLU(userHandle);
                if (user != null) {
                    user.startRealtime = 0L;
                    user.unlockRealtime = 0L;
                }
            }
        }
    }

    @VisibleForTesting
    UserManagerService(Context context) {
        this(context, null, null, new Object(), context.getCacheDir());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserManagerService(Context context, PackageManagerService pm, UserDataPreparer userDataPreparer, Object packagesLock) {
        this(context, pm, userDataPreparer, packagesLock, Environment.getDataDirectory());
    }

    private UserManagerService(Context context, PackageManagerService pm, UserDataPreparer userDataPreparer, Object packagesLock, File dataDir) {
        this.mUsersLock = LockGuard.installNewLock(2);
        this.mRestrictionsLock = new Object();
        this.mAppRestrictionsLock = new Object();
        this.mUsers = new SparseArray<>();
        this.mBaseUserRestrictions = new SparseArray<>();
        this.mCachedEffectiveUserRestrictions = new SparseArray<>();
        this.mAppliedUserRestrictions = new SparseArray<>();
        this.mDevicePolicyGlobalUserRestrictions = new SparseArray<>();
        this.mDeviceOwnerUserId = -10000;
        this.mDevicePolicyLocalUserRestrictions = new SparseArray<>();
        this.mGuestRestrictions = new Bundle();
        this.mRemovingUserIds = new SparseBooleanArray();
        this.mRecentlyRemovedIds = new LinkedList<>();
        this.mUserVersion = 0;
        this.mIsUserManaged = new SparseBooleanArray();
        this.mUserRestrictionsListeners = new ArrayList<>();
        this.ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK = "com.android.server.pm.DISABLE_QUIET_MODE_AFTER_UNLOCK";
        this.mDisableQuietModeCallback = new AnonymousClass1();
        this.mUserStates = new SparseIntArray();
        this.mContext = context;
        this.mPm = pm;
        this.mPackagesLock = packagesLock;
        this.mHandler = new MainHandler();
        this.mUserDataPreparer = userDataPreparer;
        synchronized (this.mPackagesLock) {
            this.mUsersDir = new File(dataDir, USER_INFO_DIR);
            this.mUsersDir.mkdirs();
            File userZeroDir = new File(this.mUsersDir, String.valueOf(0));
            userZeroDir.mkdirs();
            FileUtils.setPermissions(this.mUsersDir.toString(), 509, -1, -1);
            this.mUserListFile = new File(this.mUsersDir, USER_LIST_FILENAME);
            initDefaultGuestRestrictions();
            readUserListLP();
            sInstance = this;
        }
        this.mLocalService = new LocalService(this, null);
        LocalServices.addService(UserManagerInternal.class, this.mLocalService);
        this.mLockPatternUtils = new LockPatternUtils(this.mContext);
        this.mUserStates.put(0, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemReady() {
        this.mAppOpsService = IAppOpsService.Stub.asInterface(ServiceManager.getService("appops"));
        synchronized (this.mRestrictionsLock) {
            applyUserRestrictionsLR(0);
        }
        UserInfo currentGuestUser = findCurrentGuestUser();
        if (currentGuestUser != null && !hasUserRestriction("no_config_wifi", currentGuestUser.id)) {
            setUserRestriction("no_config_wifi", true, currentGuestUser.id);
        }
        this.mContext.registerReceiver(this.mDisableQuietModeCallback, new IntentFilter("com.android.server.pm.DISABLE_QUIET_MODE_AFTER_UNLOCK"), null, this.mHandler);
    }

    void cleanupPartialUsers() {
        int i;
        ArrayList<UserInfo> partials = new ArrayList<>();
        synchronized (this.mUsersLock) {
            int userSize = this.mUsers.size();
            i = 0;
            for (int i2 = 0; i2 < userSize; i2++) {
                UserInfo ui = this.mUsers.valueAt(i2).info;
                if ((ui.partial || ui.guestToRemove || ui.isEphemeral()) && i2 != 0) {
                    partials.add(ui);
                    addRemovingUserIdLocked(ui.id);
                    ui.partial = true;
                }
            }
        }
        int partialsSize = partials.size();
        while (true) {
            int i3 = i;
            if (i3 < partialsSize) {
                UserInfo ui2 = partials.get(i3);
                Slog.w(LOG_TAG, "Removing partially created user " + ui2.id + " (name=" + ui2.name + ")");
                removeUserState(ui2.id);
                i = i3 + 1;
            } else {
                return;
            }
        }
    }

    public String getUserAccount(int userId) {
        String str;
        checkManageUserAndAcrossUsersFullPermission("get user account");
        synchronized (this.mUsersLock) {
            str = this.mUsers.get(userId).account;
        }
        return str;
    }

    public void setUserAccount(int userId, String accountName) {
        checkManageUserAndAcrossUsersFullPermission("set user account");
        UserData userToUpdate = null;
        synchronized (this.mPackagesLock) {
            synchronized (this.mUsersLock) {
                UserData userData = this.mUsers.get(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, "User not found for setting user account: u" + userId);
                    return;
                }
                String currentAccount = userData.account;
                if (!Objects.equals(currentAccount, accountName)) {
                    userData.account = accountName;
                    userToUpdate = userData;
                }
                if (userToUpdate != null) {
                    writeUserLP(userToUpdate);
                }
            }
        }
    }

    public UserInfo getPrimaryUser() {
        checkManageUsersPermission("query users");
        synchronized (this.mUsersLock) {
            int userSize = this.mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = this.mUsers.valueAt(i).info;
                if (ui.isPrimary() && !this.mRemovingUserIds.get(ui.id)) {
                    return ui;
                }
            }
            return null;
        }
    }

    public List<UserInfo> getUsers(boolean excludeDying) {
        ArrayList<UserInfo> users;
        checkManageOrCreateUsersPermission("query users");
        synchronized (this.mUsersLock) {
            users = new ArrayList<>(this.mUsers.size());
            int userSize = this.mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = this.mUsers.valueAt(i).info;
                if (!ui.partial && (!excludeDying || !this.mRemovingUserIds.get(ui.id))) {
                    users.add(userWithName(ui));
                }
            }
        }
        return users;
    }

    public List<UserInfo> getProfiles(int userId, boolean enabledOnly) {
        List<UserInfo> profilesLU;
        boolean returnFullInfo = true;
        if (userId != UserHandle.getCallingUserId()) {
            checkManageOrCreateUsersPermission("getting profiles related to user " + userId);
        } else {
            returnFullInfo = hasManageUsersPermission();
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mUsersLock) {
                profilesLU = getProfilesLU(userId, enabledOnly, returnFullInfo);
            }
            return profilesLU;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public int[] getProfileIds(int userId, boolean enabledOnly) {
        int[] array;
        if (userId != UserHandle.getCallingUserId()) {
            checkManageOrCreateUsersPermission("getting profiles related to user " + userId);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mUsersLock) {
                array = getProfileIdsLU(userId, enabledOnly).toArray();
            }
            return array;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private List<UserInfo> getProfilesLU(int userId, boolean enabledOnly, boolean fullInfo) {
        UserInfo userInfo;
        IntArray profileIds = getProfileIdsLU(userId, enabledOnly);
        ArrayList<UserInfo> users = new ArrayList<>(profileIds.size());
        for (int i = 0; i < profileIds.size(); i++) {
            int profileId = profileIds.get(i);
            UserInfo userInfo2 = this.mUsers.get(profileId).info;
            if (!fullInfo) {
                userInfo = new UserInfo(userInfo2);
                userInfo.name = null;
                userInfo.iconPath = null;
            } else {
                userInfo = userWithName(userInfo2);
            }
            users.add(userInfo);
        }
        return users;
    }

    private IntArray getProfileIdsLU(int userId, boolean enabledOnly) {
        UserInfo user = getUserInfoLU(userId);
        IntArray result = new IntArray(this.mUsers.size());
        if (user == null) {
            return result;
        }
        int userSize = this.mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo profile = this.mUsers.valueAt(i).info;
            if (isProfileOf(user, profile) && ((!enabledOnly || profile.isEnabled()) && !this.mRemovingUserIds.get(profile.id) && !profile.partial)) {
                result.add(profile.id);
            }
        }
        return result;
    }

    public int getCredentialOwnerProfile(int userHandle) {
        checkManageUsersPermission("get the credential owner");
        if (!this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userHandle)) {
            synchronized (this.mUsersLock) {
                UserInfo profileParent = getProfileParentLU(userHandle);
                if (profileParent != null) {
                    return profileParent.id;
                }
            }
        }
        return userHandle;
    }

    public boolean isSameProfileGroup(int userId, int otherUserId) {
        if (userId == otherUserId) {
            return true;
        }
        checkManageUsersPermission("check if in the same profile group");
        return isSameProfileGroupNoChecks(userId, otherUserId);
    }

    private boolean isSameProfileGroupNoChecks(int userId, int otherUserId) {
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo != null && userInfo.profileGroupId != -10000) {
                UserInfo otherUserInfo = getUserInfoLU(otherUserId);
                if (otherUserInfo != null && otherUserInfo.profileGroupId != -10000) {
                    return userInfo.profileGroupId == otherUserInfo.profileGroupId;
                }
                return false;
            }
            return false;
        }
    }

    public UserInfo getProfileParent(int userHandle) {
        UserInfo profileParentLU;
        checkManageUsersPermission("get the profile parent");
        synchronized (this.mUsersLock) {
            profileParentLU = getProfileParentLU(userHandle);
        }
        return profileParentLU;
    }

    public int getProfileParentId(int userHandle) {
        checkManageUsersPermission("get the profile parent");
        return this.mLocalService.getProfileParentId(userHandle);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public UserInfo getProfileParentLU(int userHandle) {
        int parentUserId;
        UserInfo profile = getUserInfoLU(userHandle);
        if (profile == null || (parentUserId = profile.profileGroupId) == userHandle || parentUserId == -10000) {
            return null;
        }
        return getUserInfoLU(parentUserId);
    }

    private static boolean isProfileOf(UserInfo user, UserInfo profile) {
        return user.id == profile.id || (user.profileGroupId != -10000 && user.profileGroupId == profile.profileGroupId);
    }

    private void broadcastProfileAvailabilityChanges(UserHandle profileHandle, UserHandle parentHandle, boolean inQuietMode) {
        Intent intent = new Intent();
        if (inQuietMode) {
            intent.setAction("android.intent.action.MANAGED_PROFILE_UNAVAILABLE");
        } else {
            intent.setAction("android.intent.action.MANAGED_PROFILE_AVAILABLE");
        }
        intent.putExtra("android.intent.extra.QUIET_MODE", inQuietMode);
        intent.putExtra("android.intent.extra.USER", profileHandle);
        intent.putExtra("android.intent.extra.user_handle", profileHandle.getIdentifier());
        intent.addFlags(1073741824);
        this.mContext.sendBroadcastAsUser(intent, parentHandle);
    }

    public boolean requestQuietModeEnabled(String callingPackage, boolean enableQuietMode, int userHandle, IntentSender target) {
        Preconditions.checkNotNull(callingPackage);
        if (!enableQuietMode || target == null) {
            ensureCanModifyQuietMode(callingPackage, Binder.getCallingUid(), target != null);
            long identity = Binder.clearCallingIdentity();
            try {
                if (enableQuietMode) {
                    setQuietModeEnabled(userHandle, true, target);
                    return true;
                }
                boolean needToShowConfirmCredential = this.mLockPatternUtils.isSecure(userHandle) && !StorageManager.isUserKeyUnlocked(userHandle);
                if (needToShowConfirmCredential) {
                    showConfirmCredentialToDisableQuietMode(userHandle, target);
                    return false;
                }
                setQuietModeEnabled(userHandle, false, target);
                return true;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        throw new IllegalArgumentException("target should only be specified when we are disabling quiet mode.");
    }

    private void ensureCanModifyQuietMode(String callingPackage, int callingUid, boolean startIntent) {
        if (hasManageUsersPermission()) {
            return;
        }
        if (startIntent) {
            throw new SecurityException("MANAGE_USERS permission is required to start intent after disabling quiet mode.");
        }
        boolean hasModifyQuietModePermission = hasPermissionGranted("android.permission.MODIFY_QUIET_MODE", callingUid);
        if (hasModifyQuietModePermission) {
            return;
        }
        verifyCallingPackage(callingPackage, callingUid);
        ShortcutServiceInternal shortcutInternal = (ShortcutServiceInternal) LocalServices.getService(ShortcutServiceInternal.class);
        if (shortcutInternal != null) {
            boolean isForegroundLauncher = shortcutInternal.isForegroundDefaultLauncher(callingPackage, callingUid);
            if (isForegroundLauncher) {
                return;
            }
        }
        throw new SecurityException("Can't modify quiet mode, caller is neither foreground default launcher nor has MANAGE_USERS/MODIFY_QUIET_MODE permission");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setQuietModeEnabled(int userHandle, boolean enableQuietMode, IntentSender target) {
        synchronized (this.mUsersLock) {
            UserInfo profile = getUserInfoLU(userHandle);
            UserInfo parent = getProfileParentLU(userHandle);
            if (profile == null || !profile.isManagedProfile()) {
                throw new IllegalArgumentException("User " + userHandle + " is not a profile");
            } else if (profile.isQuietModeEnabled() == enableQuietMode) {
                Slog.i(LOG_TAG, "Quiet mode is already " + enableQuietMode);
            } else {
                profile.flags ^= 128;
                UserData profileUserData = getUserDataLU(profile.id);
                synchronized (this.mPackagesLock) {
                    writeUserLP(profileUserData);
                }
                DisableQuietModeUserUnlockedCallback disableQuietModeUserUnlockedCallback = null;
                try {
                    if (enableQuietMode) {
                        ActivityManager.getService().stopUser(userHandle, true, (IStopUserCallback) null);
                        ((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).killForegroundAppsForUser(userHandle);
                    } else {
                        if (target != null) {
                            disableQuietModeUserUnlockedCallback = new DisableQuietModeUserUnlockedCallback(target);
                        }
                        ActivityManager.getService().startUserInBackgroundWithListener(userHandle, disableQuietModeUserUnlockedCallback);
                    }
                } catch (RemoteException e) {
                    e.rethrowAsRuntimeException();
                }
                broadcastProfileAvailabilityChanges(profile.getUserHandle(), parent.getUserHandle(), enableQuietMode);
            }
        }
    }

    public boolean isQuietModeEnabled(int userHandle) {
        UserInfo info;
        synchronized (this.mPackagesLock) {
            synchronized (this.mUsersLock) {
                info = getUserInfoLU(userHandle);
            }
            if (info != null && info.isManagedProfile()) {
                return info.isQuietModeEnabled();
            }
            return false;
        }
    }

    private void showConfirmCredentialToDisableQuietMode(int userHandle, IntentSender target) {
        KeyguardManager km = (KeyguardManager) this.mContext.getSystemService("keyguard");
        Intent unlockIntent = km.createConfirmDeviceCredentialIntent(null, null, userHandle);
        if (unlockIntent == null) {
            return;
        }
        Intent callBackIntent = new Intent("com.android.server.pm.DISABLE_QUIET_MODE_AFTER_UNLOCK");
        if (target != null) {
            callBackIntent.putExtra("android.intent.extra.INTENT", target);
        }
        callBackIntent.putExtra("android.intent.extra.USER_ID", userHandle);
        callBackIntent.setPackage(this.mContext.getPackageName());
        callBackIntent.addFlags(268435456);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this.mContext, 0, callBackIntent, 1409286144);
        unlockIntent.putExtra("android.intent.extra.INTENT", pendingIntent.getIntentSender());
        unlockIntent.setFlags(276824064);
        this.mContext.startActivity(unlockIntent);
    }

    public void setUserEnabled(int userId) {
        UserInfo info;
        checkManageUsersPermission("enable user");
        synchronized (this.mPackagesLock) {
            synchronized (this.mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info != null && !info.isEnabled()) {
                info.flags ^= 64;
                writeUserLP(getUserDataLU(info.id));
            }
        }
    }

    public void setUserAdmin(int userId) {
        UserInfo info;
        checkManageUserAndAcrossUsersFullPermission("set user admin");
        synchronized (this.mPackagesLock) {
            synchronized (this.mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info != null && !info.isAdmin()) {
                info.flags ^= 2;
                writeUserLP(getUserDataLU(info.id));
                setUserRestriction("no_sms", false, userId);
                setUserRestriction("no_outgoing_calls", false, userId);
            }
        }
    }

    public void evictCredentialEncryptionKey(int userId) {
        checkManageUsersPermission("evict CE key");
        IActivityManager am = ActivityManagerNative.getDefault();
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                am.restartUserInBackground(userId);
            } catch (RemoteException re) {
                throw re.rethrowAsRuntimeException();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public UserInfo getUserInfo(int userId) {
        UserInfo userWithName;
        checkManageOrCreateUsersPermission("query user");
        synchronized (this.mUsersLock) {
            userWithName = userWithName(getUserInfoLU(userId));
        }
        return userWithName;
    }

    private UserInfo userWithName(UserInfo orig) {
        if (orig != null && orig.name == null && orig.id == 0) {
            UserInfo withName = new UserInfo(orig);
            withName.name = getOwnerName();
            return withName;
        }
        return orig;
    }

    public int getManagedProfileBadge(int userId) {
        int i;
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId && !hasManageUsersPermission() && !isSameProfileGroupNoChecks(callingUserId, userId)) {
            throw new SecurityException("You need MANAGE_USERS permission to: check if specified user a managed profile outside your profile group");
        }
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            i = userInfo != null ? userInfo.profileBadge : 0;
        }
        return i;
    }

    public boolean isManagedProfile(int userId) {
        boolean z;
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId && !hasManageUsersPermission() && !isSameProfileGroupNoChecks(callingUserId, userId)) {
            throw new SecurityException("You need MANAGE_USERS permission to: check if specified user a managed profile outside your profile group");
        }
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            z = userInfo != null && userInfo.isManagedProfile();
        }
        return z;
    }

    public boolean isUserUnlockingOrUnlocked(int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "isUserUnlockingOrUnlocked");
        return this.mLocalService.isUserUnlockingOrUnlocked(userId);
    }

    public boolean isUserUnlocked(int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "isUserUnlocked");
        return this.mLocalService.isUserUnlocked(userId);
    }

    public boolean isUserRunning(int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "isUserRunning");
        return this.mLocalService.isUserRunning(userId);
    }

    public long getUserStartRealtime() {
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (this.mUsersLock) {
            UserData user = getUserDataLU(userId);
            if (user != null) {
                return user.startRealtime;
            }
            return 0L;
        }
    }

    public long getUserUnlockRealtime() {
        synchronized (this.mUsersLock) {
            UserData user = getUserDataLU(UserHandle.getUserId(Binder.getCallingUid()));
            if (user != null) {
                return user.unlockRealtime;
            }
            return 0L;
        }
    }

    private void checkManageOrInteractPermIfCallerInOtherProfileGroup(int userId, String name) {
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId && !isSameProfileGroupNoChecks(callingUserId, userId) && !hasManageUsersPermission() && !hasPermissionGranted("android.permission.INTERACT_ACROSS_USERS", Binder.getCallingUid())) {
            throw new SecurityException("You need INTERACT_ACROSS_USERS or MANAGE_USERS permission to: check " + name);
        }
    }

    public boolean isDemoUser(int userId) {
        boolean z;
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId && !hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to query if u=" + userId + " is a demo user");
        }
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            z = userInfo != null && userInfo.isDemo();
        }
        return z;
    }

    public boolean isRestricted() {
        boolean isRestricted;
        synchronized (this.mUsersLock) {
            isRestricted = getUserInfoLU(UserHandle.getCallingUserId()).isRestricted();
        }
        return isRestricted;
    }

    public boolean canHaveRestrictedProfile(int userId) {
        checkManageUsersPermission("canHaveRestrictedProfile");
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            boolean z = false;
            if (userInfo != null && userInfo.canHaveProfile()) {
                if (!userInfo.isAdmin()) {
                    return false;
                }
                if (!this.mIsDeviceManaged && !this.mIsUserManaged.get(userId)) {
                    z = true;
                }
                return z;
            }
            return false;
        }
    }

    public boolean hasRestrictedProfiles() {
        checkManageUsersPermission("hasRestrictedProfiles");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this.mUsersLock) {
            int userSize = this.mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo profile = this.mUsers.valueAt(i).info;
                if (callingUserId != profile.id && profile.restrictedProfileParentId == callingUserId) {
                    return true;
                }
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public UserInfo getUserInfoLU(int userId) {
        UserData userData = this.mUsers.get(userId);
        if (userData != null && userData.info.partial && !this.mRemovingUserIds.get(userId)) {
            Slog.w(LOG_TAG, "getUserInfo: unknown user #" + userId);
            return null;
        } else if (userData != null) {
            return userData.info;
        } else {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public UserData getUserDataLU(int userId) {
        UserData userData = this.mUsers.get(userId);
        if (userData != null && userData.info.partial && !this.mRemovingUserIds.get(userId)) {
            return null;
        }
        return userData;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public UserInfo getUserInfoNoChecks(int userId) {
        UserInfo userInfo;
        synchronized (this.mUsersLock) {
            UserData userData = this.mUsers.get(userId);
            userInfo = userData != null ? userData.info : null;
        }
        return userInfo;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public UserData getUserDataNoChecks(int userId) {
        UserData userData;
        synchronized (this.mUsersLock) {
            userData = this.mUsers.get(userId);
        }
        return userData;
    }

    public boolean exists(int userId) {
        return this.mLocalService.exists(userId);
    }

    public void setUserName(int userId, String name) {
        checkManageUsersPermission("rename users");
        boolean changed = false;
        synchronized (this.mPackagesLock) {
            UserData userData = getUserDataNoChecks(userId);
            if (userData != null && !userData.info.partial) {
                if (name != null && !name.equals(userData.info.name)) {
                    userData.info.name = name;
                    writeUserLP(userData);
                    changed = true;
                }
                if (changed) {
                    sendUserInfoChangedBroadcast(userId);
                    return;
                }
                return;
            }
            Slog.w(LOG_TAG, "setUserName: unknown user #" + userId);
        }
    }

    public void setUserIcon(int userId, Bitmap bitmap) {
        checkManageUsersPermission("update users");
        if (hasUserRestriction("no_set_user_icon", userId)) {
            Log.w(LOG_TAG, "Cannot set user icon. DISALLOW_SET_USER_ICON is enabled.");
        } else {
            this.mLocalService.setUserIcon(userId, bitmap);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendUserInfoChangedBroadcast(int userId) {
        Intent changedIntent = new Intent("android.intent.action.USER_INFO_CHANGED");
        changedIntent.putExtra("android.intent.extra.user_handle", userId);
        changedIntent.addFlags(1073741824);
        this.mContext.sendBroadcastAsUser(changedIntent, UserHandle.ALL);
    }

    public ParcelFileDescriptor getUserIcon(int targetUserId) {
        synchronized (this.mPackagesLock) {
            UserInfo targetUserInfo = getUserInfoNoChecks(targetUserId);
            if (targetUserInfo != null && !targetUserInfo.partial) {
                int callingUserId = UserHandle.getCallingUserId();
                int callingGroupId = getUserInfoNoChecks(callingUserId).profileGroupId;
                int targetGroupId = targetUserInfo.profileGroupId;
                boolean sameGroup = callingGroupId != -10000 && callingGroupId == targetGroupId;
                if (callingUserId != targetUserId && !sameGroup) {
                    checkManageUsersPermission("get the icon of a user who is not related");
                }
                if (targetUserInfo.iconPath == null) {
                    return null;
                }
                String iconPath = targetUserInfo.iconPath;
                try {
                    return ParcelFileDescriptor.open(new File(iconPath), 268435456);
                } catch (FileNotFoundException e) {
                    Log.e(LOG_TAG, "Couldn't find icon file", e);
                    return null;
                }
            }
            Slog.w(LOG_TAG, "getUserIcon: unknown user #" + targetUserId);
            return null;
        }
    }

    public void makeInitialized(int userId) {
        checkManageUsersPermission("makeInitialized");
        boolean scheduleWriteUser = false;
        synchronized (this.mUsersLock) {
            UserData userData = this.mUsers.get(userId);
            if (userData != null && !userData.info.partial) {
                if ((userData.info.flags & 16) == 0) {
                    userData.info.flags |= 16;
                    scheduleWriteUser = true;
                }
                if (scheduleWriteUser) {
                    scheduleWriteUser(userData);
                    return;
                }
                return;
            }
            Slog.w(LOG_TAG, "makeInitialized: unknown user #" + userId);
        }
    }

    private void initDefaultGuestRestrictions() {
        synchronized (this.mGuestRestrictions) {
            if (this.mGuestRestrictions.isEmpty()) {
                this.mGuestRestrictions.putBoolean("no_config_wifi", true);
                this.mGuestRestrictions.putBoolean("no_install_unknown_sources", true);
                this.mGuestRestrictions.putBoolean("no_outgoing_calls", true);
                this.mGuestRestrictions.putBoolean("no_sms", true);
            }
        }
    }

    public Bundle getDefaultGuestRestrictions() {
        Bundle bundle;
        checkManageUsersPermission("getDefaultGuestRestrictions");
        synchronized (this.mGuestRestrictions) {
            bundle = new Bundle(this.mGuestRestrictions);
        }
        return bundle;
    }

    public void setDefaultGuestRestrictions(Bundle restrictions) {
        checkManageUsersPermission("setDefaultGuestRestrictions");
        synchronized (this.mGuestRestrictions) {
            this.mGuestRestrictions.clear();
            this.mGuestRestrictions.putAll(restrictions);
        }
        synchronized (this.mPackagesLock) {
            writeUserListLP();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setDevicePolicyUserRestrictionsInner(int userId, Bundle restrictions, boolean isDeviceOwner, int cameraRestrictionScope) {
        boolean globalChanged;
        boolean localChanged;
        Bundle global = new Bundle();
        Bundle local = new Bundle();
        UserRestrictionsUtils.sortToGlobalAndLocal(restrictions, isDeviceOwner, cameraRestrictionScope, global, local);
        synchronized (this.mRestrictionsLock) {
            globalChanged = updateRestrictionsIfNeededLR(userId, global, this.mDevicePolicyGlobalUserRestrictions);
            localChanged = updateRestrictionsIfNeededLR(userId, local, this.mDevicePolicyLocalUserRestrictions);
            if (isDeviceOwner) {
                this.mDeviceOwnerUserId = userId;
            } else if (this.mDeviceOwnerUserId == userId) {
                this.mDeviceOwnerUserId = -10000;
            }
        }
        synchronized (this.mPackagesLock) {
            if (localChanged || globalChanged) {
                writeUserLP(getUserDataNoChecks(userId));
            }
        }
        synchronized (this.mRestrictionsLock) {
            try {
                if (globalChanged) {
                    applyUserRestrictionsForAllUsersLR();
                } else if (localChanged) {
                    applyUserRestrictionsLR(userId);
                }
            } finally {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updateRestrictionsIfNeededLR(int userId, Bundle restrictions, SparseArray<Bundle> restrictionsArray) {
        boolean changed = !UserRestrictionsUtils.areEqual(restrictionsArray.get(userId), restrictions);
        if (changed) {
            if (!UserRestrictionsUtils.isEmpty(restrictions)) {
                restrictionsArray.put(userId, restrictions);
            } else {
                restrictionsArray.delete(userId);
            }
        }
        return changed;
    }

    @GuardedBy("mRestrictionsLock")
    private Bundle computeEffectiveUserRestrictionsLR(int userId) {
        Bundle baseRestrictions = UserRestrictionsUtils.nonNull(this.mBaseUserRestrictions.get(userId));
        Bundle global = UserRestrictionsUtils.mergeAll(this.mDevicePolicyGlobalUserRestrictions);
        Bundle local = this.mDevicePolicyLocalUserRestrictions.get(userId);
        if (UserRestrictionsUtils.isEmpty(global) && UserRestrictionsUtils.isEmpty(local)) {
            return baseRestrictions;
        }
        Bundle effective = UserRestrictionsUtils.clone(baseRestrictions);
        UserRestrictionsUtils.merge(effective, global);
        UserRestrictionsUtils.merge(effective, local);
        return effective;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mRestrictionsLock")
    public void invalidateEffectiveUserRestrictionsLR(int userId) {
        this.mCachedEffectiveUserRestrictions.remove(userId);
    }

    private Bundle getEffectiveUserRestrictions(int userId) {
        Bundle restrictions;
        synchronized (this.mRestrictionsLock) {
            restrictions = this.mCachedEffectiveUserRestrictions.get(userId);
            if (restrictions == null) {
                restrictions = computeEffectiveUserRestrictionsLR(userId);
                this.mCachedEffectiveUserRestrictions.put(userId, restrictions);
            }
        }
        return restrictions;
    }

    public boolean hasUserRestriction(String restrictionKey, int userId) {
        Bundle restrictions;
        return UserRestrictionsUtils.isValidRestriction(restrictionKey) && (restrictions = getEffectiveUserRestrictions(userId)) != null && restrictions.getBoolean(restrictionKey);
    }

    public boolean hasUserRestrictionOnAnyUser(String restrictionKey) {
        if (UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
            List<UserInfo> users = getUsers(true);
            for (int i = 0; i < users.size(); i++) {
                int userId = users.get(i).id;
                Bundle restrictions = getEffectiveUserRestrictions(userId);
                if (restrictions != null && restrictions.getBoolean(restrictionKey)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    public int getUserRestrictionSource(String restrictionKey, int userId) {
        List<UserManager.EnforcingUser> enforcingUsers = getUserRestrictionSources(restrictionKey, userId);
        int result = 0;
        for (int i = enforcingUsers.size() - 1; i >= 0; i--) {
            result |= enforcingUsers.get(i).getUserRestrictionSource();
        }
        return result;
    }

    public List<UserManager.EnforcingUser> getUserRestrictionSources(String restrictionKey, int userId) {
        checkManageUsersPermission("getUserRestrictionSource");
        if (!hasUserRestriction(restrictionKey, userId)) {
            return Collections.emptyList();
        }
        List<UserManager.EnforcingUser> result = new ArrayList<>();
        if (hasBaseUserRestriction(restrictionKey, userId)) {
            result.add(new UserManager.EnforcingUser(-10000, 1));
        }
        synchronized (this.mRestrictionsLock) {
            Bundle profileOwnerRestrictions = this.mDevicePolicyLocalUserRestrictions.get(userId);
            if (UserRestrictionsUtils.contains(profileOwnerRestrictions, restrictionKey)) {
                result.add(getEnforcingUserLocked(userId));
            }
            int i = this.mDevicePolicyGlobalUserRestrictions.size() - 1;
            while (true) {
                int i2 = i;
                if (i2 >= 0) {
                    Bundle globalRestrictions = this.mDevicePolicyGlobalUserRestrictions.valueAt(i2);
                    int profileUserId = this.mDevicePolicyGlobalUserRestrictions.keyAt(i2);
                    if (UserRestrictionsUtils.contains(globalRestrictions, restrictionKey)) {
                        result.add(getEnforcingUserLocked(profileUserId));
                    }
                    i = i2 - 1;
                }
            }
        }
        return result;
    }

    @GuardedBy("mRestrictionsLock")
    private UserManager.EnforcingUser getEnforcingUserLocked(int userId) {
        int source = this.mDeviceOwnerUserId == userId ? 2 : 4;
        return new UserManager.EnforcingUser(userId, source);
    }

    public Bundle getUserRestrictions(int userId) {
        return UserRestrictionsUtils.clone(getEffectiveUserRestrictions(userId));
    }

    public boolean hasBaseUserRestriction(String restrictionKey, int userId) {
        checkManageUsersPermission("hasBaseUserRestriction");
        boolean z = false;
        if (UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
            synchronized (this.mRestrictionsLock) {
                Bundle bundle = this.mBaseUserRestrictions.get(userId);
                if (bundle != null && bundle.getBoolean(restrictionKey, false)) {
                    z = true;
                }
            }
            return z;
        }
        return false;
    }

    public void setUserRestriction(String key, boolean value, int userId) {
        checkManageUsersPermission("setUserRestriction");
        if (!UserRestrictionsUtils.isValidRestriction(key)) {
            return;
        }
        synchronized (this.mRestrictionsLock) {
            Bundle newRestrictions = UserRestrictionsUtils.clone(this.mBaseUserRestrictions.get(userId));
            newRestrictions.putBoolean(key, value);
            updateUserRestrictionsInternalLR(newRestrictions, userId);
        }
    }

    @GuardedBy("mRestrictionsLock")
    private void updateUserRestrictionsInternalLR(Bundle newBaseRestrictions, final int userId) {
        Bundle prevAppliedRestrictions = UserRestrictionsUtils.nonNull(this.mAppliedUserRestrictions.get(userId));
        if (newBaseRestrictions != null) {
            Bundle prevBaseRestrictions = this.mBaseUserRestrictions.get(userId);
            Preconditions.checkState(prevBaseRestrictions != newBaseRestrictions);
            Preconditions.checkState(this.mCachedEffectiveUserRestrictions.get(userId) != newBaseRestrictions);
            if (updateRestrictionsIfNeededLR(userId, newBaseRestrictions, this.mBaseUserRestrictions)) {
                scheduleWriteUser(getUserDataNoChecks(userId));
            }
        }
        final Bundle effective = computeEffectiveUserRestrictionsLR(userId);
        this.mCachedEffectiveUserRestrictions.put(userId, effective);
        if (this.mAppOpsService != null) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.UserManagerService.2
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        UserManagerService.this.mAppOpsService.setUserRestrictions(effective, UserManagerService.mUserRestriconToken, userId);
                    } catch (RemoteException e) {
                        Log.w(UserManagerService.LOG_TAG, "Unable to notify AppOpsService of UserRestrictions");
                    }
                }
            });
        }
        propagateUserRestrictionsLR(userId, effective, prevAppliedRestrictions);
        this.mAppliedUserRestrictions.put(userId, new Bundle(effective));
    }

    private void propagateUserRestrictionsLR(final int userId, Bundle newRestrictions, Bundle prevRestrictions) {
        if (UserRestrictionsUtils.areEqual(newRestrictions, prevRestrictions)) {
            return;
        }
        final Bundle newRestrictionsFinal = new Bundle(newRestrictions);
        final Bundle prevRestrictionsFinal = new Bundle(prevRestrictions);
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.UserManagerService.3
            @Override // java.lang.Runnable
            public void run() {
                UserManagerInternal.UserRestrictionsListener[] listeners;
                UserRestrictionsUtils.applyUserRestrictions(UserManagerService.this.mContext, userId, newRestrictionsFinal, prevRestrictionsFinal);
                synchronized (UserManagerService.this.mUserRestrictionsListeners) {
                    listeners = new UserManagerInternal.UserRestrictionsListener[UserManagerService.this.mUserRestrictionsListeners.size()];
                    UserManagerService.this.mUserRestrictionsListeners.toArray(listeners);
                }
                for (UserManagerInternal.UserRestrictionsListener userRestrictionsListener : listeners) {
                    userRestrictionsListener.onUserRestrictionsChanged(userId, newRestrictionsFinal, prevRestrictionsFinal);
                }
                Intent broadcast = new Intent("android.os.action.USER_RESTRICTIONS_CHANGED").setFlags(1073741824);
                UserManagerService.this.mContext.sendBroadcastAsUser(broadcast, UserHandle.of(userId));
            }
        });
    }

    void applyUserRestrictionsLR(int userId) {
        updateUserRestrictionsInternalLR(null, userId);
    }

    @GuardedBy("mRestrictionsLock")
    void applyUserRestrictionsForAllUsersLR() {
        this.mCachedEffectiveUserRestrictions.clear();
        Runnable r = new Runnable() { // from class: com.android.server.pm.UserManagerService.4
            @Override // java.lang.Runnable
            public void run() {
                try {
                    int[] runningUsers = ActivityManager.getService().getRunningUserIds();
                    synchronized (UserManagerService.this.mRestrictionsLock) {
                        for (int i : runningUsers) {
                            UserManagerService.this.applyUserRestrictionsLR(i);
                        }
                    }
                } catch (RemoteException e) {
                    Log.w(UserManagerService.LOG_TAG, "Unable to access ActivityManagerService");
                }
            }
        };
        this.mHandler.post(r);
    }

    private boolean isUserLimitReached() {
        int count;
        synchronized (this.mUsersLock) {
            count = getAliveUsersExcludingGuestsCountLU();
        }
        return count >= UserManager.getMaxSupportedUsers();
    }

    public boolean canAddMoreManagedProfiles(int userId, boolean allowedToRemoveOne) {
        checkManageUsersPermission("check if more managed profiles can be added.");
        boolean z = false;
        if (!ActivityManager.isLowRamDeviceStatic() && this.mContext.getPackageManager().hasSystemFeature("android.software.managed_users")) {
            int managedProfilesCount = getProfiles(userId, false).size() - 1;
            int profilesRemovedCount = (managedProfilesCount <= 0 || !allowedToRemoveOne) ? 0 : 1;
            if (managedProfilesCount - profilesRemovedCount >= getMaxManagedProfiles()) {
                return false;
            }
            synchronized (this.mUsersLock) {
                UserInfo userInfo = getUserInfoLU(userId);
                if (userInfo != null && userInfo.canHaveProfile()) {
                    int usersCountAfterRemoving = getAliveUsersExcludingGuestsCountLU() - profilesRemovedCount;
                    if (usersCountAfterRemoving != 1 && usersCountAfterRemoving >= UserManager.getMaxSupportedUsers()) {
                        return z;
                    }
                    z = true;
                    return z;
                }
                return false;
            }
        }
        return false;
    }

    private int getAliveUsersExcludingGuestsCountLU() {
        int aliveUserCount = 0;
        int totalUserCount = this.mUsers.size();
        for (int i = 0; i < totalUserCount; i++) {
            UserInfo user = this.mUsers.valueAt(i).info;
            if (!this.mRemovingUserIds.get(user.id) && !user.isGuest()) {
                aliveUserCount++;
            }
        }
        return aliveUserCount;
    }

    private static final void checkManageUserAndAcrossUsersFullPermission(String message) {
        int uid = Binder.getCallingUid();
        if (uid == 1000 || uid == 0) {
            return;
        }
        if (hasPermissionGranted("android.permission.MANAGE_USERS", uid) && hasPermissionGranted("android.permission.INTERACT_ACROSS_USERS_FULL", uid)) {
            return;
        }
        throw new SecurityException("You need MANAGE_USERS and INTERACT_ACROSS_USERS_FULL permission to: " + message);
    }

    private static boolean hasPermissionGranted(String permission, int uid) {
        return ActivityManager.checkComponentPermission(permission, uid, -1, true) == 0;
    }

    private static final void checkManageUsersPermission(String message) {
        if (!hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to: " + message);
        }
    }

    private static final void checkManageOrCreateUsersPermission(String message) {
        if (!hasManageOrCreateUsersPermission()) {
            throw new SecurityException("You either need MANAGE_USERS or CREATE_USERS permission to: " + message);
        }
    }

    private static final void checkManageOrCreateUsersPermission(int creationFlags) {
        if ((creationFlags & (-813)) == 0) {
            if (!hasManageOrCreateUsersPermission()) {
                throw new SecurityException("You either need MANAGE_USERS or CREATE_USERS permission to create an user with flags: " + creationFlags);
            }
        } else if (!hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to create an user  with flags: " + creationFlags);
        }
    }

    private static final boolean hasManageUsersPermission() {
        int callingUid = Binder.getCallingUid();
        return UserHandle.isSameApp(callingUid, 1000) || callingUid == 0 || hasPermissionGranted("android.permission.MANAGE_USERS", callingUid);
    }

    private static final boolean hasManageOrCreateUsersPermission() {
        int callingUid = Binder.getCallingUid();
        return UserHandle.isSameApp(callingUid, 1000) || callingUid == 0 || hasPermissionGranted("android.permission.MANAGE_USERS", callingUid) || hasPermissionGranted("android.permission.CREATE_USERS", callingUid);
    }

    private static void checkSystemOrRoot(String message) {
        int uid = Binder.getCallingUid();
        if (!UserHandle.isSameApp(uid, 1000) && uid != 0) {
            throw new SecurityException("Only system may: " + message);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeBitmapLP(UserInfo info, Bitmap bitmap) {
        try {
            File dir = new File(this.mUsersDir, Integer.toString(info.id));
            File file = new File(dir, USER_PHOTO_FILENAME);
            File tmp = new File(dir, USER_PHOTO_FILENAME_TMP);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(dir.getPath(), 505, -1, -1);
            }
            Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.PNG;
            FileOutputStream os = new FileOutputStream(tmp);
            if (bitmap.compress(compressFormat, 100, os) && tmp.renameTo(file) && SELinux.restorecon(file)) {
                info.iconPath = file.getAbsolutePath();
            }
            try {
                os.close();
            } catch (IOException e) {
            }
            tmp.delete();
        } catch (FileNotFoundException e2) {
            Slog.w(LOG_TAG, "Error setting photo for user ", e2);
        }
    }

    public int[] getUserIds() {
        int[] iArr;
        synchronized (this.mUsersLock) {
            iArr = this.mUserIds;
        }
        return iArr;
    }

    /* JADX WARN: Code restructure failed: missing block: B:54:0x00dc, code lost:
        if (r2.getName().equals(com.android.server.pm.UserManagerService.TAG_RESTRICTIONS) == false) goto L52;
     */
    /* JADX WARN: Code restructure failed: missing block: B:55:0x00de, code lost:
        r9 = r14.mGuestRestrictions;
     */
    /* JADX WARN: Code restructure failed: missing block: B:56:0x00e0, code lost:
        monitor-enter(r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:57:0x00e1, code lost:
        com.android.server.pm.UserRestrictionsUtils.readRestrictions(r2, r14.mGuestRestrictions);
     */
    /* JADX WARN: Code restructure failed: missing block: B:58:0x00e6, code lost:
        monitor-exit(r9);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void readUserListLP() {
        /*
            Method dump skipped, instructions count: 306
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.UserManagerService.readUserListLP():void");
    }

    private void upgradeIfNecessaryLP(Bundle oldGlobalUserRestrictions) {
        int originalVersion = this.mUserVersion;
        int userVersion = this.mUserVersion;
        if (userVersion < 1) {
            UserData userData = getUserDataNoChecks(0);
            if ("Primary".equals(userData.info.name)) {
                userData.info.name = this.mContext.getResources().getString(17040420);
                scheduleWriteUser(userData);
            }
            userVersion = 1;
        }
        if (userVersion < 2) {
            UserData userData2 = getUserDataNoChecks(0);
            if ((userData2.info.flags & 16) == 0) {
                userData2.info.flags |= 16;
                scheduleWriteUser(userData2);
            }
            userVersion = 2;
        }
        if (userVersion < 4) {
            userVersion = 4;
        }
        if (userVersion < 5) {
            initDefaultGuestRestrictions();
            userVersion = 5;
        }
        if (userVersion < 6) {
            boolean splitSystemUser = UserManager.isSplitSystemUser();
            synchronized (this.mUsersLock) {
                for (int i = 0; i < this.mUsers.size(); i++) {
                    UserData userData3 = this.mUsers.valueAt(i);
                    if (!splitSystemUser && userData3.info.isRestricted() && userData3.info.restrictedProfileParentId == -10000) {
                        userData3.info.restrictedProfileParentId = 0;
                        scheduleWriteUser(userData3);
                    }
                }
            }
            userVersion = 6;
        }
        if (userVersion < 7) {
            synchronized (this.mRestrictionsLock) {
                if (!UserRestrictionsUtils.isEmpty(oldGlobalUserRestrictions) && this.mDeviceOwnerUserId != -10000) {
                    this.mDevicePolicyGlobalUserRestrictions.put(this.mDeviceOwnerUserId, oldGlobalUserRestrictions);
                }
                UserRestrictionsUtils.moveRestriction("ensure_verify_apps", this.mDevicePolicyLocalUserRestrictions, this.mDevicePolicyGlobalUserRestrictions);
            }
            userVersion = 7;
        }
        if (userVersion < 7) {
            Slog.w(LOG_TAG, "User version " + this.mUserVersion + " didn't upgrade as expected to 7");
            return;
        }
        this.mUserVersion = userVersion;
        if (originalVersion < this.mUserVersion) {
            writeUserListLP();
        }
    }

    private void fallbackToSingleUserLP() {
        int flags = 16;
        if (!UserManager.isSplitSystemUser()) {
            flags = 16 | 3;
        }
        UserInfo system = new UserInfo(0, (String) null, (String) null, flags);
        UserData userData = putUserInfo(system);
        this.mNextSerialNumber = 10;
        this.mUserVersion = 7;
        Bundle restrictions = new Bundle();
        try {
            String[] defaultFirstUserRestrictions = this.mContext.getResources().getStringArray(17236001);
            for (String userRestriction : defaultFirstUserRestrictions) {
                if (UserRestrictionsUtils.isValidRestriction(userRestriction)) {
                    restrictions.putBoolean(userRestriction, true);
                }
            }
        } catch (Resources.NotFoundException e) {
            Log.e(LOG_TAG, "Couldn't find resource: config_defaultFirstUserRestrictions", e);
        }
        if (!restrictions.isEmpty()) {
            synchronized (this.mRestrictionsLock) {
                this.mBaseUserRestrictions.append(0, restrictions);
            }
        }
        updateUserIds();
        initDefaultGuestRestrictions();
        writeUserLP(userData);
        writeUserListLP();
    }

    private String getOwnerName() {
        return this.mContext.getResources().getString(17040420);
    }

    private void scheduleWriteUser(UserData UserData2) {
        if (!this.mHandler.hasMessages(1, UserData2)) {
            Message msg = this.mHandler.obtainMessage(1, UserData2);
            this.mHandler.sendMessageDelayed(msg, 2000L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeUserLP(UserData userData) {
        FileOutputStream fos = null;
        File file = this.mUsersDir;
        AtomicFile userFile = new AtomicFile(new File(file, userData.info.id + XML_SUFFIX));
        try {
            fos = userFile.startWrite();
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            writeUserLP(userData, bos);
            userFile.finishWrite(fos);
        } catch (Exception ioe) {
            Slog.e(LOG_TAG, "Error writing user info " + userData.info.id, ioe);
            userFile.failWrite(fos);
        }
    }

    @VisibleForTesting
    void writeUserLP(UserData userData, OutputStream os) throws IOException, XmlPullParserException {
        XmlSerializer serializer = new FastXmlSerializer();
        serializer.setOutput(os, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        UserInfo userInfo = userData.info;
        serializer.startTag(null, TAG_USER);
        serializer.attribute(null, ATTR_ID, Integer.toString(userInfo.id));
        serializer.attribute(null, ATTR_SERIAL_NO, Integer.toString(userInfo.serialNumber));
        serializer.attribute(null, "flags", Integer.toString(userInfo.flags));
        serializer.attribute(null, ATTR_CREATION_TIME, Long.toString(userInfo.creationTime));
        serializer.attribute(null, ATTR_LAST_LOGGED_IN_TIME, Long.toString(userInfo.lastLoggedInTime));
        if (userInfo.lastLoggedInFingerprint != null) {
            serializer.attribute(null, ATTR_LAST_LOGGED_IN_FINGERPRINT, userInfo.lastLoggedInFingerprint);
        }
        if (userInfo.iconPath != null) {
            serializer.attribute(null, ATTR_ICON_PATH, userInfo.iconPath);
        }
        if (userInfo.partial) {
            serializer.attribute(null, ATTR_PARTIAL, "true");
        }
        if (userInfo.guestToRemove) {
            serializer.attribute(null, ATTR_GUEST_TO_REMOVE, "true");
        }
        if (userInfo.profileGroupId != -10000) {
            serializer.attribute(null, ATTR_PROFILE_GROUP_ID, Integer.toString(userInfo.profileGroupId));
        }
        serializer.attribute(null, ATTR_PROFILE_BADGE, Integer.toString(userInfo.profileBadge));
        if (userInfo.restrictedProfileParentId != -10000) {
            serializer.attribute(null, ATTR_RESTRICTED_PROFILE_PARENT_ID, Integer.toString(userInfo.restrictedProfileParentId));
        }
        if (userData.persistSeedData) {
            if (userData.seedAccountName != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_NAME, userData.seedAccountName);
            }
            if (userData.seedAccountType != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_TYPE, userData.seedAccountType);
            }
        }
        if (userInfo.name != null) {
            serializer.startTag(null, "name");
            serializer.text(userInfo.name);
            serializer.endTag(null, "name");
        }
        synchronized (this.mRestrictionsLock) {
            UserRestrictionsUtils.writeRestrictions(serializer, this.mBaseUserRestrictions.get(userInfo.id), TAG_RESTRICTIONS);
            UserRestrictionsUtils.writeRestrictions(serializer, this.mDevicePolicyLocalUserRestrictions.get(userInfo.id), TAG_DEVICE_POLICY_RESTRICTIONS);
            UserRestrictionsUtils.writeRestrictions(serializer, this.mDevicePolicyGlobalUserRestrictions.get(userInfo.id), TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS);
        }
        if (userData.account != null) {
            serializer.startTag(null, TAG_ACCOUNT);
            serializer.text(userData.account);
            serializer.endTag(null, TAG_ACCOUNT);
        }
        if (userData.persistSeedData && userData.seedAccountOptions != null) {
            serializer.startTag(null, TAG_SEED_ACCOUNT_OPTIONS);
            userData.seedAccountOptions.saveToXml(serializer);
            serializer.endTag(null, TAG_SEED_ACCOUNT_OPTIONS);
        }
        serializer.endTag(null, TAG_USER);
        serializer.endDocument();
    }

    private void writeUserListLP() {
        int[] userIdsToWrite;
        int i;
        FileOutputStream fos = null;
        AtomicFile userListFile = new AtomicFile(this.mUserListFile);
        try {
            fos = userListFile.startWrite();
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(bos, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            fastXmlSerializer.startTag(null, "users");
            fastXmlSerializer.attribute(null, ATTR_NEXT_SERIAL_NO, Integer.toString(this.mNextSerialNumber));
            fastXmlSerializer.attribute(null, "version", Integer.toString(this.mUserVersion));
            fastXmlSerializer.startTag(null, TAG_GUEST_RESTRICTIONS);
            synchronized (this.mGuestRestrictions) {
                UserRestrictionsUtils.writeRestrictions(fastXmlSerializer, this.mGuestRestrictions, TAG_RESTRICTIONS);
            }
            fastXmlSerializer.endTag(null, TAG_GUEST_RESTRICTIONS);
            fastXmlSerializer.startTag(null, TAG_DEVICE_OWNER_USER_ID);
            fastXmlSerializer.attribute(null, ATTR_ID, Integer.toString(this.mDeviceOwnerUserId));
            fastXmlSerializer.endTag(null, TAG_DEVICE_OWNER_USER_ID);
            synchronized (this.mUsersLock) {
                userIdsToWrite = new int[this.mUsers.size()];
                for (int i2 = 0; i2 < userIdsToWrite.length; i2++) {
                    UserInfo user = this.mUsers.valueAt(i2).info;
                    userIdsToWrite[i2] = user.id;
                }
            }
            for (int id : userIdsToWrite) {
                fastXmlSerializer.startTag(null, TAG_USER);
                fastXmlSerializer.attribute(null, ATTR_ID, Integer.toString(id));
                fastXmlSerializer.endTag(null, TAG_USER);
            }
            fastXmlSerializer.endTag(null, "users");
            fastXmlSerializer.endDocument();
            userListFile.finishWrite(fos);
        } catch (Exception e) {
            userListFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing user list");
        }
    }

    private UserData readUserLP(int id) {
        FileInputStream fis = null;
        try {
            try {
                try {
                    AtomicFile userFile = new AtomicFile(new File(this.mUsersDir, Integer.toString(id) + XML_SUFFIX));
                    fis = userFile.openRead();
                    return readUserLP(id, fis);
                } catch (XmlPullParserException e) {
                    Slog.e(LOG_TAG, "Error reading user list");
                    return null;
                }
            } catch (IOException e2) {
                Slog.e(LOG_TAG, "Error reading user list");
                return null;
            }
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    @VisibleForTesting
    UserData readUserLP(int id, InputStream is) throws IOException, XmlPullParserException {
        int type;
        int profileBadge;
        int restrictedProfileParentId;
        boolean guestToRemove;
        boolean persistSeedData;
        String seedAccountName;
        String seedAccountType;
        PersistableBundle seedAccountOptions;
        Bundle baseRestrictions;
        Bundle localRestrictions;
        Bundle globalRestrictions;
        int flags;
        String name;
        String account;
        String iconPath;
        int restrictedProfileParentId2;
        String lastLoggedInFingerprint;
        long lastLoggedInTime;
        long creationTime;
        int serialNumber;
        String iconPath2;
        int type2;
        String valueString;
        int type3;
        boolean persistSeedData2 = false;
        PersistableBundle seedAccountOptions2 = null;
        Bundle baseRestrictions2 = null;
        Bundle localRestrictions2 = null;
        Bundle globalRestrictions2 = null;
        XmlPullParser parser = Xml.newPullParser();
        String name2 = null;
        parser.setInput(is, StandardCharsets.UTF_8.name());
        while (true) {
            int type4 = parser.next();
            if (type4 == 2) {
                type = type4;
                break;
            }
            type = type4;
            if (type == 1) {
                break;
            }
        }
        if (type != 2) {
            Slog.e(LOG_TAG, "Unable to read user " + id);
            return null;
        }
        String account2 = null;
        if (type == 2 && parser.getName().equals(TAG_USER)) {
            int storedId = readIntAttribute(parser, ATTR_ID, -1);
            if (storedId != id) {
                Slog.e(LOG_TAG, "User id does not match the file name");
                return null;
            }
            int serialNumber2 = readIntAttribute(parser, ATTR_SERIAL_NO, id);
            int flags2 = readIntAttribute(parser, "flags", 0);
            String iconPath3 = parser.getAttributeValue(null, ATTR_ICON_PATH);
            long creationTime2 = readLongAttribute(parser, ATTR_CREATION_TIME, 0L);
            long lastLoggedInTime2 = readLongAttribute(parser, ATTR_LAST_LOGGED_IN_TIME, 0L);
            String lastLoggedInFingerprint2 = parser.getAttributeValue(null, ATTR_LAST_LOGGED_IN_FINGERPRINT);
            int profileGroupId = readIntAttribute(parser, ATTR_PROFILE_GROUP_ID, -10000);
            int profileBadge2 = readIntAttribute(parser, ATTR_PROFILE_BADGE, 0);
            int restrictedProfileParentId3 = readIntAttribute(parser, ATTR_RESTRICTED_PROFILE_PARENT_ID, -10000);
            partial = "true".equals(parser.getAttributeValue(null, ATTR_PARTIAL));
            String valueString2 = parser.getAttributeValue(null, ATTR_GUEST_TO_REMOVE);
            boolean guestToRemove2 = "true".equals(valueString2);
            String seedAccountName2 = parser.getAttributeValue(null, ATTR_SEED_ACCOUNT_NAME);
            String seedAccountType2 = parser.getAttributeValue(null, ATTR_SEED_ACCOUNT_TYPE);
            persistSeedData2 = (seedAccountName2 == null && seedAccountType2 == null) ? true : true;
            int outerDepth = parser.getDepth();
            while (true) {
                int type5 = parser.next();
                iconPath2 = iconPath3;
                if (type5 != 1 && (type5 != 3 || parser.getDepth() > outerDepth)) {
                    if (type5 == 3) {
                        type2 = type5;
                        valueString = valueString2;
                    } else if (type5 == 4) {
                        type2 = type5;
                        valueString = valueString2;
                    } else {
                        String tag = parser.getName();
                        if ("name".equals(tag)) {
                            type3 = parser.next();
                            valueString = valueString2;
                            if (type3 == 4) {
                                name2 = parser.getText();
                            }
                        } else {
                            valueString = valueString2;
                            if (TAG_RESTRICTIONS.equals(tag)) {
                                baseRestrictions2 = UserRestrictionsUtils.readRestrictions(parser);
                            } else if (TAG_DEVICE_POLICY_RESTRICTIONS.equals(tag)) {
                                localRestrictions2 = UserRestrictionsUtils.readRestrictions(parser);
                            } else if (TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS.equals(tag)) {
                                globalRestrictions2 = UserRestrictionsUtils.readRestrictions(parser);
                            } else if (TAG_ACCOUNT.equals(tag)) {
                                type3 = parser.next();
                                if (type3 == 4) {
                                    account2 = parser.getText();
                                }
                            } else if (TAG_SEED_ACCOUNT_OPTIONS.equals(tag)) {
                                seedAccountOptions2 = PersistableBundle.restoreFromXml(parser);
                                persistSeedData2 = true;
                            }
                            iconPath3 = iconPath2;
                            valueString2 = valueString;
                        }
                        iconPath3 = iconPath2;
                        valueString2 = valueString;
                    }
                    iconPath3 = iconPath2;
                    valueString2 = valueString;
                }
            }
            profileBadge = profileBadge2;
            restrictedProfileParentId = restrictedProfileParentId3;
            guestToRemove = guestToRemove2;
            persistSeedData = persistSeedData2;
            seedAccountName = seedAccountName2;
            seedAccountType = seedAccountType2;
            seedAccountOptions = seedAccountOptions2;
            baseRestrictions = baseRestrictions2;
            localRestrictions = localRestrictions2;
            globalRestrictions = globalRestrictions2;
            name = name2;
            account = account2;
            flags = flags2;
            iconPath = iconPath2;
            restrictedProfileParentId2 = profileGroupId;
            lastLoggedInFingerprint = lastLoggedInFingerprint2;
            lastLoggedInTime = lastLoggedInTime2;
            creationTime = creationTime2;
            serialNumber = serialNumber2;
        } else {
            profileBadge = 0;
            restrictedProfileParentId = -10000;
            guestToRemove = false;
            persistSeedData = false;
            seedAccountName = null;
            seedAccountType = null;
            seedAccountOptions = null;
            baseRestrictions = null;
            localRestrictions = null;
            globalRestrictions = null;
            flags = 0;
            name = null;
            account = null;
            iconPath = null;
            restrictedProfileParentId2 = -10000;
            lastLoggedInFingerprint = null;
            lastLoggedInTime = 0;
            creationTime = 0;
            serialNumber = id;
        }
        UserInfo userInfo = new UserInfo(id, name, iconPath, flags);
        userInfo.serialNumber = serialNumber;
        userInfo.creationTime = creationTime;
        userInfo.lastLoggedInTime = lastLoggedInTime;
        userInfo.lastLoggedInFingerprint = lastLoggedInFingerprint;
        userInfo.partial = partial;
        userInfo.guestToRemove = guestToRemove;
        userInfo.profileGroupId = restrictedProfileParentId2;
        userInfo.profileBadge = profileBadge;
        userInfo.restrictedProfileParentId = restrictedProfileParentId;
        UserData userData = new UserData();
        userData.info = userInfo;
        userData.account = account;
        String account3 = seedAccountName;
        userData.seedAccountName = account3;
        String seedAccountName3 = seedAccountType;
        userData.seedAccountType = seedAccountName3;
        userData.persistSeedData = persistSeedData;
        userData.seedAccountOptions = seedAccountOptions;
        synchronized (this.mRestrictionsLock) {
            Bundle baseRestrictions3 = baseRestrictions;
            try {
                if (baseRestrictions3 != null) {
                    try {
                        this.mBaseUserRestrictions.put(id, baseRestrictions3);
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
                Bundle baseRestrictions4 = localRestrictions;
                if (baseRestrictions4 != null) {
                    try {
                        this.mDevicePolicyLocalUserRestrictions.put(id, baseRestrictions4);
                    } catch (Throwable th2) {
                        th = th2;
                        throw th;
                    }
                }
                Bundle localRestrictions3 = globalRestrictions;
                if (localRestrictions3 != null) {
                    this.mDevicePolicyGlobalUserRestrictions.put(id, localRestrictions3);
                }
                return userData;
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private int readIntAttribute(XmlPullParser parser, String attr, int defaultValue) {
        String valueString = parser.getAttributeValue(null, attr);
        if (valueString == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(valueString);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long readLongAttribute(XmlPullParser parser, String attr, long defaultValue) {
        String valueString = parser.getAttributeValue(null, attr);
        if (valueString == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(valueString);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void cleanAppRestrictionsForPackageLAr(String pkg, int userId) {
        File dir = Environment.getUserSystemDirectory(userId);
        File resFile = new File(dir, packageToRestrictionsFileName(pkg));
        if (resFile.exists()) {
            resFile.delete();
        }
    }

    public UserInfo createProfileForUser(String name, int flags, int userId, String[] disallowedPackages) {
        checkManageOrCreateUsersPermission(flags);
        return createUserInternal(name, flags, userId, disallowedPackages);
    }

    public UserInfo createProfileForUserEvenWhenDisallowed(String name, int flags, int userId, String[] disallowedPackages) {
        checkManageOrCreateUsersPermission(flags);
        return createUserInternalUnchecked(name, flags, userId, disallowedPackages);
    }

    public boolean removeUserEvenWhenDisallowed(int userHandle) {
        checkManageOrCreateUsersPermission("Only the system can remove users");
        return removeUserUnchecked(userHandle);
    }

    public UserInfo createUser(String name, int flags) {
        checkManageOrCreateUsersPermission(flags);
        return createUserInternal(name, flags, -10000);
    }

    private UserInfo createUserInternal(String name, int flags, int parentId) {
        return createUserInternal(name, flags, parentId, null);
    }

    private UserInfo createUserInternal(String name, int flags, int parentId, String[] disallowedPackages) {
        String restriction;
        if ((flags & 32) != 0) {
            restriction = "no_add_managed_profile";
        } else {
            restriction = "no_add_user";
        }
        if (hasUserRestriction(restriction, UserHandle.getCallingUserId())) {
            Log.w(LOG_TAG, "Cannot add user. " + restriction + " is enabled.");
            return null;
        }
        return createUserInternalUnchecked(name, flags, parentId, disallowedPackages);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:107:0x0145, code lost:
        if (r15.info.isEphemeral() != false) goto L87;
     */
    /* JADX WARN: Removed duplicated region for block: B:119:0x017c  */
    /* JADX WARN: Removed duplicated region for block: B:120:0x0181  */
    /* JADX WARN: Removed duplicated region for block: B:151:0x0222 A[Catch: all -> 0x0293, TRY_ENTER, TryCatch #4 {all -> 0x02f3, blocks: (B:220:0x02f2, B:168:0x0246, B:169:0x024b, B:151:0x0222, B:152:0x0225), top: B:231:0x0049 }] */
    /* JADX WARN: Removed duplicated region for block: B:246:0x01b9 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.content.pm.UserInfo createUserInternalUnchecked(java.lang.String r29, int r30, int r31, java.lang.String[] r32) {
        /*
            Method dump skipped, instructions count: 773
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.UserManagerService.createUserInternalUnchecked(java.lang.String, int, int, java.lang.String[]):android.content.pm.UserInfo");
    }

    @VisibleForTesting
    UserData putUserInfo(UserInfo userInfo) {
        UserData userData = new UserData();
        userData.info = userInfo;
        synchronized (this.mUsers) {
            this.mUsers.put(userInfo.id, userData);
        }
        return userData;
    }

    @VisibleForTesting
    void removeUserInfo(int userId) {
        synchronized (this.mUsers) {
            this.mUsers.remove(userId);
        }
    }

    public UserInfo createRestrictedProfile(String name, int parentUserId) {
        checkManageOrCreateUsersPermission("setupRestrictedProfile");
        UserInfo user = createProfileForUser(name, 8, parentUserId, null);
        if (user == null) {
            return null;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            setUserRestriction("no_modify_accounts", true, user.id);
            Settings.Secure.putIntForUser(this.mContext.getContentResolver(), "location_mode", 0, user.id);
            setUserRestriction("no_share_location", true, user.id);
            return user;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private UserInfo findCurrentGuestUser() {
        synchronized (this.mUsersLock) {
            int size = this.mUsers.size();
            for (int i = 0; i < size; i++) {
                UserInfo user = this.mUsers.valueAt(i).info;
                if (user.isGuest() && !user.guestToRemove && !this.mRemovingUserIds.get(user.id)) {
                    return user;
                }
            }
            return null;
        }
    }

    public boolean markGuestForDeletion(int userHandle) {
        checkManageUsersPermission("Only the system can remove users");
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean("no_remove_user", false)) {
            Log.w(LOG_TAG, "Cannot remove user. DISALLOW_REMOVE_USER is enabled.");
            return false;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mPackagesLock) {
                synchronized (this.mUsersLock) {
                    UserData userData = this.mUsers.get(userHandle);
                    if (userHandle != 0 && userData != null && !this.mRemovingUserIds.get(userHandle)) {
                        if (userData.info.isGuest()) {
                            userData.info.guestToRemove = true;
                            userData.info.flags |= 64;
                            writeUserLP(userData);
                            return true;
                        }
                        return false;
                    }
                    return false;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean removeUser(int userHandle) {
        boolean isManagedProfile;
        Slog.i(LOG_TAG, "removeUser u" + userHandle);
        checkManageOrCreateUsersPermission("Only the system can remove users");
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userHandle);
            isManagedProfile = userInfo != null && userInfo.isManagedProfile();
        }
        String restriction = isManagedProfile ? "no_remove_managed_profile" : "no_remove_user";
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(restriction, false)) {
            Log.w(LOG_TAG, "Cannot remove user. " + restriction + " is enabled.");
            return false;
        }
        return removeUserUnchecked(userHandle);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean removeUserUnchecked(int userHandle) {
        long ident = Binder.clearCallingIdentity();
        try {
            int currentUser = ActivityManager.getCurrentUser();
            if (currentUser == userHandle) {
                Log.w(LOG_TAG, "Current user cannot be removed");
                return false;
            }
            synchronized (this.mPackagesLock) {
                synchronized (this.mUsersLock) {
                    UserData userData = this.mUsers.get(userHandle);
                    if (userHandle != 0 && userData != null && !this.mRemovingUserIds.get(userHandle)) {
                        addRemovingUserIdLocked(userHandle);
                        userData.info.partial = true;
                        userData.info.flags |= 64;
                        writeUserLP(userData);
                        try {
                            this.mAppOpsService.removeUser(userHandle);
                        } catch (RemoteException e) {
                            Log.w(LOG_TAG, "Unable to notify AppOpsService of removing user", e);
                        }
                        if (userData.info.profileGroupId != -10000 && userData.info.isManagedProfile()) {
                            sendProfileRemovedBroadcast(userData.info.profileGroupId, userData.info.id);
                        }
                        try {
                            int res = ActivityManager.getService().stopUser(userHandle, true, new IStopUserCallback.Stub() { // from class: com.android.server.pm.UserManagerService.5
                                public void userStopped(int userId) {
                                    UserManagerService.this.finishRemoveUser(userId);
                                }

                                public void userStopAborted(int userId) {
                                }
                            });
                            return res == 0;
                        } catch (RemoteException e2) {
                            return false;
                        }
                    }
                    return false;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mUsersLock")
    @VisibleForTesting
    void addRemovingUserIdLocked(int userId) {
        this.mRemovingUserIds.put(userId, true);
        this.mRecentlyRemovedIds.add(Integer.valueOf(userId));
        if (this.mRecentlyRemovedIds.size() > 100) {
            this.mRecentlyRemovedIds.removeFirst();
        }
    }

    void finishRemoveUser(final int userHandle) {
        long ident = Binder.clearCallingIdentity();
        try {
            Intent addedIntent = new Intent("android.intent.action.USER_REMOVED");
            addedIntent.putExtra("android.intent.extra.user_handle", userHandle);
            this.mContext.sendOrderedBroadcastAsUser(addedIntent, UserHandle.ALL, "android.permission.MANAGE_USERS", new BroadcastReceiver() { // from class: com.android.server.pm.UserManagerService.6
                /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.pm.UserManagerService$6$1] */
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    new Thread() { // from class: com.android.server.pm.UserManagerService.6.1
                        @Override // java.lang.Thread, java.lang.Runnable
                        public void run() {
                            ((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).onUserRemoved(userHandle);
                            UserManagerService.this.removeUserState(userHandle);
                        }
                    }.start();
                }
            }, null, -1, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeUserState(int userHandle) {
        try {
            ((StorageManager) this.mContext.getSystemService(StorageManager.class)).destroyUserKey(userHandle);
        } catch (IllegalStateException e) {
            Slog.i(LOG_TAG, "Destroying key for user " + userHandle + " failed, continuing anyway", e);
        }
        try {
            IGateKeeperService gk = GateKeeper.getService();
            if (gk != null) {
                gk.clearSecureUserId(userHandle);
            }
        } catch (Exception e2) {
            Slog.w(LOG_TAG, "unable to clear GK secure user id");
        }
        this.mPm.cleanUpUser(this, userHandle);
        this.mUserDataPreparer.destroyUserData(userHandle, 3);
        synchronized (this.mUsersLock) {
            this.mUsers.remove(userHandle);
            this.mIsUserManaged.delete(userHandle);
        }
        synchronized (this.mUserStates) {
            this.mUserStates.delete(userHandle);
        }
        synchronized (this.mRestrictionsLock) {
            this.mBaseUserRestrictions.remove(userHandle);
            this.mAppliedUserRestrictions.remove(userHandle);
            this.mCachedEffectiveUserRestrictions.remove(userHandle);
            this.mDevicePolicyLocalUserRestrictions.remove(userHandle);
            if (this.mDevicePolicyGlobalUserRestrictions.get(userHandle) != null) {
                this.mDevicePolicyGlobalUserRestrictions.remove(userHandle);
                applyUserRestrictionsForAllUsersLR();
            }
        }
        synchronized (this.mPackagesLock) {
            writeUserListLP();
        }
        File file = this.mUsersDir;
        AtomicFile userFile = new AtomicFile(new File(file, userHandle + XML_SUFFIX));
        userFile.delete();
        updateUserIds();
    }

    private void sendProfileRemovedBroadcast(int parentUserId, int removedUserId) {
        Intent managedProfileIntent = new Intent("android.intent.action.MANAGED_PROFILE_REMOVED");
        managedProfileIntent.addFlags(1342177280);
        managedProfileIntent.putExtra("android.intent.extra.USER", new UserHandle(removedUserId));
        managedProfileIntent.putExtra("android.intent.extra.user_handle", removedUserId);
        this.mContext.sendBroadcastAsUser(managedProfileIntent, new UserHandle(parentUserId), null);
    }

    public Bundle getApplicationRestrictions(String packageName) {
        return getApplicationRestrictionsForUser(packageName, UserHandle.getCallingUserId());
    }

    public Bundle getApplicationRestrictionsForUser(String packageName, int userId) {
        Bundle readApplicationRestrictionsLAr;
        if (UserHandle.getCallingUserId() != userId || !UserHandle.isSameApp(Binder.getCallingUid(), getUidForPackage(packageName))) {
            checkSystemOrRoot("get application restrictions for other user/app " + packageName);
        }
        synchronized (this.mAppRestrictionsLock) {
            readApplicationRestrictionsLAr = readApplicationRestrictionsLAr(packageName, userId);
        }
        return readApplicationRestrictionsLAr;
    }

    public void setApplicationRestrictions(String packageName, Bundle restrictions, int userId) {
        checkSystemOrRoot("set application restrictions");
        if (restrictions != null) {
            restrictions.setDefusable(true);
        }
        synchronized (this.mAppRestrictionsLock) {
            if (restrictions != null) {
                try {
                    if (!restrictions.isEmpty()) {
                        writeApplicationRestrictionsLAr(packageName, restrictions, userId);
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            cleanAppRestrictionsForPackageLAr(packageName, userId);
        }
        Intent changeIntent = new Intent("android.intent.action.APPLICATION_RESTRICTIONS_CHANGED");
        changeIntent.setPackage(packageName);
        changeIntent.addFlags(1073741824);
        this.mContext.sendBroadcastAsUser(changeIntent, UserHandle.of(userId));
    }

    private int getUidForPackage(String packageName) {
        long ident = Binder.clearCallingIdentity();
        try {
            return this.mContext.getPackageManager().getApplicationInfo(packageName, DumpState.DUMP_CHANGES).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mAppRestrictionsLock")
    private static Bundle readApplicationRestrictionsLAr(String packageName, int userId) {
        AtomicFile restrictionsFile = new AtomicFile(new File(Environment.getUserSystemDirectory(userId), packageToRestrictionsFileName(packageName)));
        return readApplicationRestrictionsLAr(restrictionsFile);
    }

    @GuardedBy("mAppRestrictionsLock")
    @VisibleForTesting
    static Bundle readApplicationRestrictionsLAr(AtomicFile restrictionsFile) {
        XmlPullParser parser;
        Bundle restrictions = new Bundle();
        ArrayList<String> values = new ArrayList<>();
        if (restrictionsFile.getBaseFile().exists()) {
            FileInputStream fis = null;
            try {
                try {
                    fis = restrictionsFile.openRead();
                    parser = Xml.newPullParser();
                    parser.setInput(fis, StandardCharsets.UTF_8.name());
                    XmlUtils.nextElement(parser);
                } catch (IOException | XmlPullParserException e) {
                    Log.w(LOG_TAG, "Error parsing " + restrictionsFile.getBaseFile(), e);
                }
                if (parser.getEventType() == 2) {
                    while (parser.next() != 1) {
                        readEntry(restrictions, values, parser);
                    }
                    return restrictions;
                }
                Slog.e(LOG_TAG, "Unable to read restrictions file " + restrictionsFile.getBaseFile());
                return restrictions;
            } finally {
                IoUtils.closeQuietly((AutoCloseable) null);
            }
        }
        return restrictions;
    }

    private static void readEntry(Bundle restrictions, ArrayList<String> values, XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() == 2 && parser.getName().equals(TAG_ENTRY)) {
            String key = parser.getAttributeValue(null, ATTR_KEY);
            String valType = parser.getAttributeValue(null, "type");
            String multiple = parser.getAttributeValue(null, ATTR_MULTIPLE);
            if (multiple != null) {
                values.clear();
                int count = Integer.parseInt(multiple);
                while (count > 0) {
                    int type = parser.next();
                    if (type == 1) {
                        break;
                    } else if (type == 2 && parser.getName().equals(TAG_VALUE)) {
                        values.add(parser.nextText().trim());
                        count--;
                    }
                }
                String[] valueStrings = new String[values.size()];
                values.toArray(valueStrings);
                restrictions.putStringArray(key, valueStrings);
            } else if (ATTR_TYPE_BUNDLE.equals(valType)) {
                restrictions.putBundle(key, readBundleEntry(parser, values));
            } else if (ATTR_TYPE_BUNDLE_ARRAY.equals(valType)) {
                int outerDepth = parser.getDepth();
                ArrayList<Bundle> bundleList = new ArrayList<>();
                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    Bundle childBundle = readBundleEntry(parser, values);
                    bundleList.add(childBundle);
                }
                restrictions.putParcelableArray(key, (Parcelable[]) bundleList.toArray(new Bundle[bundleList.size()]));
            } else {
                String value = parser.nextText().trim();
                if (ATTR_TYPE_BOOLEAN.equals(valType)) {
                    restrictions.putBoolean(key, Boolean.parseBoolean(value));
                } else if (ATTR_TYPE_INTEGER.equals(valType)) {
                    restrictions.putInt(key, Integer.parseInt(value));
                } else {
                    restrictions.putString(key, value);
                }
            }
        }
    }

    private static Bundle readBundleEntry(XmlPullParser parser, ArrayList<String> values) throws IOException, XmlPullParserException {
        Bundle childBundle = new Bundle();
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            readEntry(childBundle, values, parser);
        }
        return childBundle;
    }

    @GuardedBy("mAppRestrictionsLock")
    private static void writeApplicationRestrictionsLAr(String packageName, Bundle restrictions, int userId) {
        AtomicFile restrictionsFile = new AtomicFile(new File(Environment.getUserSystemDirectory(userId), packageToRestrictionsFileName(packageName)));
        writeApplicationRestrictionsLAr(restrictions, restrictionsFile);
    }

    @GuardedBy("mAppRestrictionsLock")
    @VisibleForTesting
    static void writeApplicationRestrictionsLAr(Bundle restrictions, AtomicFile restrictionsFile) {
        FileOutputStream fos = null;
        try {
            fos = restrictionsFile.startWrite();
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(bos, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            fastXmlSerializer.startTag(null, TAG_RESTRICTIONS);
            writeBundle(restrictions, fastXmlSerializer);
            fastXmlSerializer.endTag(null, TAG_RESTRICTIONS);
            fastXmlSerializer.endDocument();
            restrictionsFile.finishWrite(fos);
        } catch (Exception e) {
            restrictionsFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing application restrictions list", e);
        }
    }

    private static void writeBundle(Bundle restrictions, XmlSerializer serializer) throws IOException {
        for (String key : restrictions.keySet()) {
            Object value = restrictions.get(key);
            serializer.startTag(null, TAG_ENTRY);
            serializer.attribute(null, ATTR_KEY, key);
            if (value instanceof Boolean) {
                serializer.attribute(null, "type", ATTR_TYPE_BOOLEAN);
                serializer.text(value.toString());
            } else if (value instanceof Integer) {
                serializer.attribute(null, "type", ATTR_TYPE_INTEGER);
                serializer.text(value.toString());
            } else if (value == null || (value instanceof String)) {
                serializer.attribute(null, "type", ATTR_TYPE_STRING);
                serializer.text(value != null ? (String) value : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            } else if (value instanceof Bundle) {
                serializer.attribute(null, "type", ATTR_TYPE_BUNDLE);
                writeBundle((Bundle) value, serializer);
            } else {
                int i = 0;
                if (value instanceof Parcelable[]) {
                    serializer.attribute(null, "type", ATTR_TYPE_BUNDLE_ARRAY);
                    Parcelable[] array = (Parcelable[]) value;
                    int length = array.length;
                    while (i < length) {
                        Parcelable parcelable = array[i];
                        if (!(parcelable instanceof Bundle)) {
                            throw new IllegalArgumentException("bundle-array can only hold Bundles");
                        }
                        serializer.startTag(null, TAG_ENTRY);
                        serializer.attribute(null, "type", ATTR_TYPE_BUNDLE);
                        writeBundle((Bundle) parcelable, serializer);
                        serializer.endTag(null, TAG_ENTRY);
                        i++;
                    }
                    continue;
                } else {
                    serializer.attribute(null, "type", ATTR_TYPE_STRING_ARRAY);
                    String[] values = (String[]) value;
                    serializer.attribute(null, ATTR_MULTIPLE, Integer.toString(values.length));
                    int length2 = values.length;
                    while (i < length2) {
                        String choice = values[i];
                        serializer.startTag(null, TAG_VALUE);
                        serializer.text(choice != null ? choice : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                        serializer.endTag(null, TAG_VALUE);
                        i++;
                    }
                }
            }
            serializer.endTag(null, TAG_ENTRY);
        }
    }

    public int getUserSerialNumber(int userHandle) {
        synchronized (this.mUsersLock) {
            if (exists(userHandle)) {
                return getUserInfoLU(userHandle).serialNumber;
            }
            return -1;
        }
    }

    public boolean isUserNameSet(int userHandle) {
        boolean z;
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userHandle);
            z = (userInfo == null || userInfo.name == null) ? false : true;
        }
        return z;
    }

    public int getUserHandle(int userSerialNumber) {
        int[] iArr;
        synchronized (this.mUsersLock) {
            for (int userId : this.mUserIds) {
                UserInfo info = getUserInfoLU(userId);
                if (info != null && info.serialNumber == userSerialNumber) {
                    return userId;
                }
            }
            return -1;
        }
    }

    public long getUserCreationTime(int userHandle) {
        int callingUserId = UserHandle.getCallingUserId();
        UserInfo userInfo = null;
        synchronized (this.mUsersLock) {
            try {
                if (callingUserId == userHandle) {
                    userInfo = getUserInfoLU(userHandle);
                } else {
                    UserInfo parent = getProfileParentLU(userHandle);
                    if (parent != null && parent.id == callingUserId) {
                        userInfo = getUserInfoLU(userHandle);
                    }
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        if (userInfo == null) {
            throw new SecurityException("userHandle can only be the calling user or a managed profile associated with this user");
        }
        return userInfo.creationTime;
    }

    private void updateUserIds() {
        synchronized (this.mUsersLock) {
            try {
                try {
                    int userSize = this.mUsers.size();
                    int num = 0;
                    for (int num2 = 0; num2 < userSize; num2++) {
                        if (!this.mUsers.valueAt(num2).info.partial) {
                            num++;
                        }
                    }
                    int[] newUsers = new int[num];
                    int n = 0;
                    for (int i = 0; i < userSize; i++) {
                        if (!this.mUsers.valueAt(i).info.partial) {
                            newUsers[n] = this.mUsers.keyAt(i);
                            n++;
                        }
                    }
                    this.mUserIds = newUsers;
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public void onBeforeStartUser(int userId) {
        UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return;
        }
        int userSerial = userInfo.serialNumber;
        boolean migrateAppsData = !Build.FINGERPRINT.equals(userInfo.lastLoggedInFingerprint);
        this.mUserDataPreparer.prepareUserData(userId, userSerial, 1);
        this.mPm.reconcileAppsData(userId, 1, migrateAppsData);
        if (userId != 0) {
            synchronized (this.mRestrictionsLock) {
                applyUserRestrictionsLR(userId);
            }
        }
    }

    public void onBeforeUnlockUser(int userId) {
        UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return;
        }
        int userSerial = userInfo.serialNumber;
        boolean migrateAppsData = !Build.FINGERPRINT.equals(userInfo.lastLoggedInFingerprint);
        this.mUserDataPreparer.prepareUserData(userId, userSerial, 2);
        this.mPm.reconcileAppsData(userId, 2, migrateAppsData);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reconcileUsers(String volumeUuid) {
        this.mUserDataPreparer.reconcileUsers(volumeUuid, getUsers(true));
    }

    public void onUserLoggedIn(int userId) {
        UserData userData = getUserDataNoChecks(userId);
        if (userData == null || userData.info.partial) {
            Slog.w(LOG_TAG, "userForeground: unknown user #" + userId);
            return;
        }
        long now = System.currentTimeMillis();
        if (now > EPOCH_PLUS_30_YEARS) {
            userData.info.lastLoggedInTime = now;
        }
        userData.info.lastLoggedInFingerprint = Build.FINGERPRINT;
        scheduleWriteUser(userData);
    }

    @VisibleForTesting
    int getNextAvailableId() {
        synchronized (this.mUsersLock) {
            int nextId = scanNextAvailableIdLocked();
            if (nextId >= 0) {
                return nextId;
            }
            if (this.mRemovingUserIds.size() > 0) {
                Slog.i(LOG_TAG, "All available IDs are used. Recycling LRU ids.");
                this.mRemovingUserIds.clear();
                Iterator<Integer> it = this.mRecentlyRemovedIds.iterator();
                while (it.hasNext()) {
                    Integer recentlyRemovedId = it.next();
                    this.mRemovingUserIds.put(recentlyRemovedId.intValue(), true);
                }
                nextId = scanNextAvailableIdLocked();
            }
            int nextId2 = nextId;
            if (nextId2 < 0) {
                throw new IllegalStateException("No user id available!");
            }
            return nextId2;
        }
    }

    @GuardedBy("mUsersLock")
    private int scanNextAvailableIdLocked() {
        for (int i = 10; i < MAX_USER_ID; i++) {
            if (this.mUsers.indexOfKey(i) < 0 && !this.mRemovingUserIds.get(i)) {
                return i;
            }
        }
        return -1;
    }

    private static String packageToRestrictionsFileName(String packageName) {
        return RESTRICTIONS_FILE_PREFIX + packageName + XML_SUFFIX;
    }

    public void setSeedAccountData(int userId, String accountName, String accountType, PersistableBundle accountOptions, boolean persist) {
        checkManageUsersPermission("Require MANAGE_USERS permission to set user seed data");
        synchronized (this.mPackagesLock) {
            synchronized (this.mUsersLock) {
                UserData userData = getUserDataLU(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, "No such user for settings seed data u=" + userId);
                    return;
                }
                userData.seedAccountName = accountName;
                userData.seedAccountType = accountType;
                userData.seedAccountOptions = accountOptions;
                userData.persistSeedData = persist;
                if (persist) {
                    writeUserLP(userData);
                }
            }
        }
    }

    public String getSeedAccountName() throws RemoteException {
        String str;
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (this.mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            str = userData.seedAccountName;
        }
        return str;
    }

    public String getSeedAccountType() throws RemoteException {
        String str;
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (this.mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            str = userData.seedAccountType;
        }
        return str;
    }

    public PersistableBundle getSeedAccountOptions() throws RemoteException {
        PersistableBundle persistableBundle;
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (this.mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            persistableBundle = userData.seedAccountOptions;
        }
        return persistableBundle;
    }

    public void clearSeedAccountData() throws RemoteException {
        checkManageUsersPermission("Cannot clear seed account information");
        synchronized (this.mPackagesLock) {
            synchronized (this.mUsersLock) {
                UserData userData = getUserDataLU(UserHandle.getCallingUserId());
                if (userData == null) {
                    return;
                }
                userData.clearSeedAccountData();
                writeUserLP(userData);
            }
        }
    }

    public boolean someUserHasSeedAccount(String accountName, String accountType) throws RemoteException {
        checkManageUsersPermission("Cannot check seed account information");
        synchronized (this.mUsersLock) {
            int userSize = this.mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserData data = this.mUsers.valueAt(i);
                if (!data.info.isInitialized() && data.seedAccountName != null && data.seedAccountName.equals(accountName) && data.seedAccountType != null && data.seedAccountType.equals(accountType)) {
                    return true;
                }
            }
            return false;
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new Shell(this, null).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* JADX WARN: Removed duplicated region for block: B:14:0x0024  */
    /* JADX WARN: Removed duplicated region for block: B:15:0x0025 A[Catch: RemoteException -> 0x002a, TRY_LEAVE, TryCatch #0 {RemoteException -> 0x002a, blocks: (B:6:0x000c, B:15:0x0025, B:9:0x0016), top: B:20:0x000c }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    int onShellCommand(com.android.server.pm.UserManagerService.Shell r6, java.lang.String r7) {
        /*
            r5 = this;
            if (r7 != 0) goto L7
            int r0 = r6.handleDefaultCommands(r7)
            return r0
        L7:
            java.io.PrintWriter r0 = r6.getOutPrintWriter()
            r1 = -1
            int r2 = r7.hashCode()     // Catch: android.os.RemoteException -> L2a
            r3 = 3322014(0x32b09e, float:4.655133E-39)
            if (r2 == r3) goto L16
            goto L21
        L16:
            java.lang.String r2 = "list"
            boolean r2 = r7.equals(r2)     // Catch: android.os.RemoteException -> L2a
            if (r2 == 0) goto L21
            r2 = 0
            goto L22
        L21:
            r2 = r1
        L22:
            if (r2 == 0) goto L25
            goto L3f
        L25:
            int r2 = r5.runList(r0)     // Catch: android.os.RemoteException -> L2a
            return r2
        L2a:
            r2 = move-exception
            java.lang.StringBuilder r3 = new java.lang.StringBuilder
            r3.<init>()
            java.lang.String r4 = "Remote exception: "
            r3.append(r4)
            r3.append(r2)
            java.lang.String r3 = r3.toString()
            r0.println(r3)
        L3f:
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.UserManagerService.onShellCommand(com.android.server.pm.UserManagerService$Shell, java.lang.String):int");
    }

    private int runList(PrintWriter pw) throws RemoteException {
        IActivityManager am = ActivityManager.getService();
        List<UserInfo> users = getUsers(false);
        if (users == null) {
            pw.println("Error: couldn't get users");
            return 1;
        }
        pw.println("Users:");
        for (int i = 0; i < users.size(); i++) {
            String running = am.isUserRunning(users.get(i).id, 0) ? " running" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            pw.println("\t" + users.get(i).toString() + running);
        }
        return 0;
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        Object obj;
        int state;
        long nowRealtime;
        int i;
        UserManagerService userManagerService = this;
        if (!DumpUtils.checkDumpPermission(userManagerService.mContext, LOG_TAG, pw)) {
            return;
        }
        long now = System.currentTimeMillis();
        long nowRealtime2 = SystemClock.elapsedRealtime();
        StringBuilder sb = new StringBuilder();
        synchronized (userManagerService.mPackagesLock) {
            try {
                try {
                    Object obj2 = userManagerService.mUsersLock;
                    synchronized (obj2) {
                        try {
                            pw.println("Users:");
                            int i2 = 0;
                            while (true) {
                                int i3 = i2;
                                if (i3 >= userManagerService.mUsers.size()) {
                                    break;
                                }
                                UserData userData = userManagerService.mUsers.valueAt(i3);
                                if (userData == null) {
                                    obj = obj2;
                                    i = i3;
                                    nowRealtime = nowRealtime2;
                                } else {
                                    UserInfo userInfo = userData.info;
                                    int userId = userInfo.id;
                                    pw.print("  ");
                                    pw.print(userInfo);
                                    pw.print(" serialNo=");
                                    pw.print(userInfo.serialNumber);
                                    if (userManagerService.mRemovingUserIds.get(userId)) {
                                        try {
                                            pw.print(" <removing> ");
                                        } catch (Throwable th) {
                                            th = th;
                                            obj = obj2;
                                            throw th;
                                        }
                                    }
                                    if (userInfo.partial) {
                                        pw.print(" <partial>");
                                    }
                                    pw.println();
                                    pw.print("    State: ");
                                    synchronized (userManagerService.mUserStates) {
                                        state = userManagerService.mUserStates.get(userId, -1);
                                    }
                                    pw.println(UserState.stateToString(state));
                                    pw.print("    Created: ");
                                    try {
                                        nowRealtime = nowRealtime2;
                                        try {
                                            dumpTimeAgo(pw, sb, now, userInfo.creationTime);
                                            pw.print("    Last logged in: ");
                                            obj = obj2;
                                            i = i3;
                                            try {
                                                dumpTimeAgo(pw, sb, now, userInfo.lastLoggedInTime);
                                                pw.print("    Last logged in fingerprint: ");
                                                pw.println(userInfo.lastLoggedInFingerprint);
                                                pw.print("    Start time: ");
                                                dumpTimeAgo(pw, sb, nowRealtime, userData.startRealtime);
                                                pw.print("    Unlock time: ");
                                                dumpTimeAgo(pw, sb, nowRealtime, userData.unlockRealtime);
                                                pw.print("    Has profile owner: ");
                                                userManagerService = this;
                                            } catch (Throwable th2) {
                                                th = th2;
                                                throw th;
                                            }
                                        } catch (Throwable th3) {
                                            th = th3;
                                            obj = obj2;
                                        }
                                    } catch (Throwable th4) {
                                        th = th4;
                                        obj = obj2;
                                    }
                                    try {
                                        pw.println(userManagerService.mIsUserManaged.get(userId));
                                        pw.println("    Restrictions:");
                                        synchronized (userManagerService.mRestrictionsLock) {
                                            UserRestrictionsUtils.dumpRestrictions(pw, "      ", userManagerService.mBaseUserRestrictions.get(userInfo.id));
                                            pw.println("    Device policy global restrictions:");
                                            UserRestrictionsUtils.dumpRestrictions(pw, "      ", userManagerService.mDevicePolicyGlobalUserRestrictions.get(userInfo.id));
                                            pw.println("    Device policy local restrictions:");
                                            UserRestrictionsUtils.dumpRestrictions(pw, "      ", userManagerService.mDevicePolicyLocalUserRestrictions.get(userInfo.id));
                                            pw.println("    Effective restrictions:");
                                            UserRestrictionsUtils.dumpRestrictions(pw, "      ", userManagerService.mCachedEffectiveUserRestrictions.get(userInfo.id));
                                        }
                                        if (userData.account != null) {
                                            pw.print("    Account name: " + userData.account);
                                            pw.println();
                                        }
                                        if (userData.seedAccountName != null) {
                                            pw.print("    Seed account name: " + userData.seedAccountName);
                                            pw.println();
                                            if (userData.seedAccountType != null) {
                                                pw.print("         account type: " + userData.seedAccountType);
                                                pw.println();
                                            }
                                            if (userData.seedAccountOptions != null) {
                                                pw.print("         account options exist");
                                                pw.println();
                                            }
                                        }
                                    } catch (Throwable th5) {
                                        th = th5;
                                        throw th;
                                    }
                                }
                                i2 = i + 1;
                                nowRealtime2 = nowRealtime;
                                obj2 = obj;
                            }
                            pw.println();
                            pw.println("  Device owner id:" + userManagerService.mDeviceOwnerUserId);
                            pw.println();
                            pw.println("  Guest restrictions:");
                            synchronized (userManagerService.mGuestRestrictions) {
                                UserRestrictionsUtils.dumpRestrictions(pw, "    ", userManagerService.mGuestRestrictions);
                            }
                            synchronized (userManagerService.mUsersLock) {
                                pw.println();
                                pw.println("  Device managed: " + userManagerService.mIsDeviceManaged);
                                if (userManagerService.mRemovingUserIds.size() > 0) {
                                    pw.println();
                                    pw.println("  Recently removed userIds: " + userManagerService.mRecentlyRemovedIds);
                                }
                            }
                            synchronized (userManagerService.mUserStates) {
                                pw.println("  Started users state: " + userManagerService.mUserStates);
                            }
                            pw.println();
                            pw.println("  Max users: " + UserManager.getMaxSupportedUsers());
                            pw.println("  Supports switchable users: " + UserManager.supportsMultipleUsers());
                            pw.println("  All guests ephemeral: " + Resources.getSystem().getBoolean(17956983));
                        } catch (Throwable th6) {
                            th = th6;
                            obj = obj2;
                        }
                    }
                } catch (Throwable th7) {
                    th = th7;
                    throw th;
                }
            } catch (Throwable th8) {
                th = th8;
            }
        }
    }

    private static void dumpTimeAgo(PrintWriter pw, StringBuilder sb, long nowTime, long time) {
        if (time == 0) {
            pw.println("<unknown>");
            return;
        }
        sb.setLength(0);
        TimeUtils.formatDuration(nowTime - time, sb);
        sb.append(" ago");
        pw.println(sb);
    }

    /* loaded from: classes.dex */
    final class MainHandler extends Handler {
        MainHandler() {
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                removeMessages(1, msg.obj);
                synchronized (UserManagerService.this.mPackagesLock) {
                    int userId = ((UserData) msg.obj).info.id;
                    UserData userData = UserManagerService.this.getUserDataNoChecks(userId);
                    if (userData != null) {
                        UserManagerService.this.writeUserLP(userData);
                    }
                }
            }
        }
    }

    boolean isUserInitialized(int userId) {
        return this.mLocalService.isUserInitialized(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LocalService extends UserManagerInternal {
        private LocalService() {
        }

        /* synthetic */ LocalService(UserManagerService x0, AnonymousClass1 x1) {
            this();
        }

        public void setDevicePolicyUserRestrictions(int userId, Bundle restrictions, boolean isDeviceOwner, int cameraRestrictionScope) {
            UserManagerService.this.setDevicePolicyUserRestrictionsInner(userId, restrictions, isDeviceOwner, cameraRestrictionScope);
        }

        public Bundle getBaseUserRestrictions(int userId) {
            Bundle bundle;
            synchronized (UserManagerService.this.mRestrictionsLock) {
                bundle = (Bundle) UserManagerService.this.mBaseUserRestrictions.get(userId);
            }
            return bundle;
        }

        public void setBaseUserRestrictionsByDpmsForMigration(int userId, Bundle baseRestrictions) {
            synchronized (UserManagerService.this.mRestrictionsLock) {
                if (UserManagerService.this.updateRestrictionsIfNeededLR(userId, new Bundle(baseRestrictions), UserManagerService.this.mBaseUserRestrictions)) {
                    UserManagerService.this.invalidateEffectiveUserRestrictionsLR(userId);
                }
            }
            UserData userData = UserManagerService.this.getUserDataNoChecks(userId);
            synchronized (UserManagerService.this.mPackagesLock) {
                try {
                    if (userData != null) {
                        UserManagerService.this.writeUserLP(userData);
                    } else {
                        Slog.w(UserManagerService.LOG_TAG, "UserInfo not found for " + userId);
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }

        public boolean getUserRestriction(int userId, String key) {
            return UserManagerService.this.getUserRestrictions(userId).getBoolean(key);
        }

        public void addUserRestrictionsListener(UserManagerInternal.UserRestrictionsListener listener) {
            synchronized (UserManagerService.this.mUserRestrictionsListeners) {
                UserManagerService.this.mUserRestrictionsListeners.add(listener);
            }
        }

        public void removeUserRestrictionsListener(UserManagerInternal.UserRestrictionsListener listener) {
            synchronized (UserManagerService.this.mUserRestrictionsListeners) {
                UserManagerService.this.mUserRestrictionsListeners.remove(listener);
            }
        }

        public void setDeviceManaged(boolean isManaged) {
            synchronized (UserManagerService.this.mUsersLock) {
                UserManagerService.this.mIsDeviceManaged = isManaged;
            }
        }

        public void setUserManaged(int userId, boolean isManaged) {
            synchronized (UserManagerService.this.mUsersLock) {
                UserManagerService.this.mIsUserManaged.put(userId, isManaged);
            }
        }

        public void setUserIcon(int userId, Bitmap bitmap) {
            long ident = Binder.clearCallingIdentity();
            try {
                synchronized (UserManagerService.this.mPackagesLock) {
                    UserData userData = UserManagerService.this.getUserDataNoChecks(userId);
                    if (userData != null && !userData.info.partial) {
                        UserManagerService.this.writeBitmapLP(userData.info, bitmap);
                        UserManagerService.this.writeUserLP(userData);
                        UserManagerService.this.sendUserInfoChangedBroadcast(userId);
                        return;
                    }
                    Slog.w(UserManagerService.LOG_TAG, "setUserIcon: unknown user #" + userId);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public void setForceEphemeralUsers(boolean forceEphemeralUsers) {
            synchronized (UserManagerService.this.mUsersLock) {
                UserManagerService.this.mForceEphemeralUsers = forceEphemeralUsers;
            }
        }

        public void removeAllUsers() {
            if (ActivityManager.getCurrentUser() == 0) {
                UserManagerService.this.removeNonSystemUsers();
                return;
            }
            BroadcastReceiver userSwitchedReceiver = new BroadcastReceiver() { // from class: com.android.server.pm.UserManagerService.LocalService.1
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    if (userId == 0) {
                        UserManagerService.this.mContext.unregisterReceiver(this);
                        UserManagerService.this.removeNonSystemUsers();
                    }
                }
            };
            IntentFilter userSwitchedFilter = new IntentFilter();
            userSwitchedFilter.addAction("android.intent.action.USER_SWITCHED");
            UserManagerService.this.mContext.registerReceiver(userSwitchedReceiver, userSwitchedFilter, null, UserManagerService.this.mHandler);
            ActivityManager am = (ActivityManager) UserManagerService.this.mContext.getSystemService("activity");
            am.switchUser(0);
        }

        public void onEphemeralUserStop(int userId) {
            synchronized (UserManagerService.this.mUsersLock) {
                UserInfo userInfo = UserManagerService.this.getUserInfoLU(userId);
                if (userInfo != null && userInfo.isEphemeral()) {
                    userInfo.flags |= 64;
                    if (userInfo.isGuest()) {
                        userInfo.guestToRemove = true;
                    }
                }
            }
        }

        public UserInfo createUserEvenWhenDisallowed(String name, int flags, String[] disallowedPackages) {
            UserInfo user = UserManagerService.this.createUserInternalUnchecked(name, flags, -10000, disallowedPackages);
            if (user != null && !user.isAdmin() && !user.isDemo()) {
                UserManagerService.this.setUserRestriction("no_sms", true, user.id);
                UserManagerService.this.setUserRestriction("no_outgoing_calls", true, user.id);
            }
            return user;
        }

        public boolean removeUserEvenWhenDisallowed(int userId) {
            return UserManagerService.this.removeUserUnchecked(userId);
        }

        public boolean isUserRunning(int userId) {
            boolean z;
            synchronized (UserManagerService.this.mUserStates) {
                z = UserManagerService.this.mUserStates.get(userId, -1) >= 0;
            }
            return z;
        }

        public void setUserState(int userId, int userState) {
            synchronized (UserManagerService.this.mUserStates) {
                UserManagerService.this.mUserStates.put(userId, userState);
            }
        }

        public void removeUserState(int userId) {
            synchronized (UserManagerService.this.mUserStates) {
                UserManagerService.this.mUserStates.delete(userId);
            }
        }

        public int[] getUserIds() {
            return UserManagerService.this.getUserIds();
        }

        public boolean isUserUnlockingOrUnlocked(int userId) {
            int state;
            synchronized (UserManagerService.this.mUserStates) {
                state = UserManagerService.this.mUserStates.get(userId, -1);
            }
            if (state == 4 || state == 5) {
                return StorageManager.isUserKeyUnlocked(userId);
            }
            return state == 2 || state == 3;
        }

        public boolean isUserUnlocked(int userId) {
            int state;
            synchronized (UserManagerService.this.mUserStates) {
                state = UserManagerService.this.mUserStates.get(userId, -1);
            }
            if (state == 4 || state == 5) {
                return StorageManager.isUserKeyUnlocked(userId);
            }
            return state == 3;
        }

        public boolean isUserInitialized(int userId) {
            return (UserManagerService.this.getUserInfo(userId).flags & 16) != 0;
        }

        public boolean exists(int userId) {
            return UserManagerService.this.getUserInfoNoChecks(userId) != null;
        }

        /* JADX WARN: Code restructure failed: missing block: B:26:0x003d, code lost:
            return false;
         */
        /* JADX WARN: Code restructure failed: missing block: B:30:0x005a, code lost:
            android.util.Slog.w(com.android.server.pm.UserManagerService.LOG_TAG, r10 + " for disabled profile " + r9 + " from " + r8);
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public boolean isProfileAccessible(int r8, int r9, java.lang.String r10, boolean r11) {
            /*
                r7 = this;
                r0 = 1
                if (r9 != r8) goto L4
                return r0
            L4:
                com.android.server.pm.UserManagerService r1 = com.android.server.pm.UserManagerService.this
                java.lang.Object r1 = com.android.server.pm.UserManagerService.access$200(r1)
                monitor-enter(r1)
                com.android.server.pm.UserManagerService r2 = com.android.server.pm.UserManagerService.this     // Catch: java.lang.Throwable -> L9f
                android.content.pm.UserInfo r2 = com.android.server.pm.UserManagerService.access$2500(r2, r8)     // Catch: java.lang.Throwable -> L9f
                if (r2 == 0) goto L19
                boolean r3 = r2.isManagedProfile()     // Catch: java.lang.Throwable -> L9f
                if (r3 == 0) goto L1b
            L19:
                if (r11 != 0) goto L7d
            L1b:
                com.android.server.pm.UserManagerService r3 = com.android.server.pm.UserManagerService.this     // Catch: java.lang.Throwable -> L9f
                android.content.pm.UserInfo r3 = com.android.server.pm.UserManagerService.access$2500(r3, r9)     // Catch: java.lang.Throwable -> L9f
                r4 = 0
                if (r3 == 0) goto L58
                boolean r5 = r3.isEnabled()     // Catch: java.lang.Throwable -> L9f
                if (r5 != 0) goto L2b
                goto L58
            L2b:
                int r5 = r3.profileGroupId     // Catch: java.lang.Throwable -> L9f
                r6 = -10000(0xffffffffffffd8f0, float:NaN)
                if (r5 == r6) goto L3a
                int r5 = r3.profileGroupId     // Catch: java.lang.Throwable -> L9f
                int r6 = r2.profileGroupId     // Catch: java.lang.Throwable -> L9f
                if (r5 == r6) goto L38
                goto L3a
            L38:
                monitor-exit(r1)     // Catch: java.lang.Throwable -> L9f
                return r0
            L3a:
                if (r11 != 0) goto L3e
                monitor-exit(r1)     // Catch: java.lang.Throwable -> L9f
                return r4
            L3e:
                java.lang.SecurityException r0 = new java.lang.SecurityException     // Catch: java.lang.Throwable -> L9f
                java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L9f
                r4.<init>()     // Catch: java.lang.Throwable -> L9f
                r4.append(r10)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r5 = " for unrelated profile "
                r4.append(r5)     // Catch: java.lang.Throwable -> L9f
                r4.append(r9)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L9f
                r0.<init>(r4)     // Catch: java.lang.Throwable -> L9f
                throw r0     // Catch: java.lang.Throwable -> L9f
            L58:
                if (r11 == 0) goto L7b
                java.lang.String r0 = "UserManagerService"
                java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L9f
                r5.<init>()     // Catch: java.lang.Throwable -> L9f
                r5.append(r10)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r6 = " for disabled profile "
                r5.append(r6)     // Catch: java.lang.Throwable -> L9f
                r5.append(r9)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r6 = " from "
                r5.append(r6)     // Catch: java.lang.Throwable -> L9f
                r5.append(r8)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L9f
                android.util.Slog.w(r0, r5)     // Catch: java.lang.Throwable -> L9f
            L7b:
                monitor-exit(r1)     // Catch: java.lang.Throwable -> L9f
                return r4
            L7d:
                java.lang.SecurityException r0 = new java.lang.SecurityException     // Catch: java.lang.Throwable -> L9f
                java.lang.StringBuilder r3 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L9f
                r3.<init>()     // Catch: java.lang.Throwable -> L9f
                r3.append(r10)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r4 = " for another profile "
                r3.append(r4)     // Catch: java.lang.Throwable -> L9f
                r3.append(r9)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r4 = " from "
                r3.append(r4)     // Catch: java.lang.Throwable -> L9f
                r3.append(r8)     // Catch: java.lang.Throwable -> L9f
                java.lang.String r3 = r3.toString()     // Catch: java.lang.Throwable -> L9f
                r0.<init>(r3)     // Catch: java.lang.Throwable -> L9f
                throw r0     // Catch: java.lang.Throwable -> L9f
            L9f:
                r0 = move-exception
                monitor-exit(r1)     // Catch: java.lang.Throwable -> L9f
                throw r0
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.UserManagerService.LocalService.isProfileAccessible(int, int, java.lang.String, boolean):boolean");
        }

        public int getProfileParentId(int userId) {
            synchronized (UserManagerService.this.mUsersLock) {
                UserInfo profileParent = UserManagerService.this.getProfileParentLU(userId);
                if (profileParent == null) {
                    return userId;
                }
                return profileParent.id;
            }
        }

        public boolean isSettingRestrictedForUser(String setting, int userId, String value, int callingUid) {
            return UserRestrictionsUtils.isSettingRestrictedForUser(UserManagerService.this.mContext, setting, userId, value, callingUid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeNonSystemUsers() {
        ArrayList<UserInfo> usersToRemove = new ArrayList<>();
        synchronized (this.mUsersLock) {
            int userSize = this.mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = this.mUsers.valueAt(i).info;
                if (ui.id != 0) {
                    usersToRemove.add(ui);
                }
            }
        }
        Iterator<UserInfo> it = usersToRemove.iterator();
        while (it.hasNext()) {
            removeUser(it.next().id);
        }
    }

    /* loaded from: classes.dex */
    private class Shell extends ShellCommand {
        private Shell() {
        }

        /* synthetic */ Shell(UserManagerService x0, AnonymousClass1 x1) {
            this();
        }

        public int onCommand(String cmd) {
            return UserManagerService.this.onShellCommand(this, cmd);
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("User manager (user) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            pw.println("  list");
            pw.println("    Prints all users on the system.");
        }
    }

    private static void debug(String message) {
        Log.d(LOG_TAG, message + BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
    }

    @VisibleForTesting
    static int getMaxManagedProfiles() {
        if (Build.IS_DEBUGGABLE) {
            return SystemProperties.getInt("persist.sys.max_profiles", 1);
        }
        return 1;
    }

    @VisibleForTesting
    int getFreeProfileBadgeLU(int parentUserId) {
        int maxManagedProfiles = getMaxManagedProfiles();
        boolean[] usedBadges = new boolean[maxManagedProfiles];
        int userSize = this.mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo ui = this.mUsers.valueAt(i).info;
            if (ui.isManagedProfile() && ui.profileGroupId == parentUserId && !this.mRemovingUserIds.get(ui.id) && ui.profileBadge < maxManagedProfiles) {
                usedBadges[ui.profileBadge] = true;
            }
        }
        for (int i2 = 0; i2 < maxManagedProfiles; i2++) {
            if (!usedBadges[i2]) {
                return i2;
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasManagedProfile(int userId) {
        synchronized (this.mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            int userSize = this.mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo profile = this.mUsers.valueAt(i).info;
                if (userId != profile.id && isProfileOf(userInfo, profile)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void verifyCallingPackage(String callingPackage, int callingUid) {
        int packageUid = this.mPm.getPackageUid(callingPackage, 0, UserHandle.getUserId(callingUid));
        if (packageUid != callingUid) {
            throw new SecurityException("Specified package " + callingPackage + " does not match the calling uid " + callingUid);
        }
    }
}
