package com.android.server.locksettings;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.PasswordMetrics;
import android.app.backup.BackupManager;
import android.app.trust.IStrongAuthTracker;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.authsecret.V1_0.IAuthSecret;
import android.hardware.biometrics.BiometricManager;
import android.hardware.face.FaceManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.security.KeyStore;
import android.security.keystore.AndroidKeyStoreProvider;
import android.security.keystore.KeyProtection;
import android.security.keystore.UserNotAuthenticatedException;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.RecoveryCertPath;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.locksettings.LockSettingsStorage;
import com.android.server.locksettings.SyntheticPasswordManager;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.wm.WindowManagerInternal;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import libcore.util.HexEncoding;

/* loaded from: classes.dex */
public class LockSettingsService extends ILockSettings.Stub {
    private static final int CHALLENGE_FROM_CALLER = 1;
    private static final int CHALLENGE_INTERNAL = 2;
    private static final int CHALLENGE_NONE = 0;
    private static final boolean DEBUG = false;
    private static final String GSI_RUNNING_PROP = "ro.gsid.image_running";
    private static final String PERMISSION = "android.permission.ACCESS_KEYGUARD_SECURE_STORAGE";
    private static final int PROFILE_KEY_IV_SIZE = 12;
    private static final int SYNTHETIC_PASSWORD_ENABLED_BY_DEFAULT = 1;
    private static final String TAG = "LockSettingsService";
    private final IActivityManager mActivityManager;
    protected IAuthSecret mAuthSecretService;
    private final BroadcastReceiver mBroadcastReceiver;
    private final Context mContext;
    private final DeviceProvisionedObserver mDeviceProvisionedObserver;
    private boolean mFirstCallToVold;
    protected IGateKeeperService mGateKeeperService;
    @VisibleForTesting
    protected final Handler mHandler;
    private final Injector mInjector;
    private final KeyStore mKeyStore;
    private final LockPatternUtils mLockPatternUtils;
    private final NotificationManager mNotificationManager;
    private final RecoverableKeyStoreManager mRecoverableKeyStoreManager;
    private final Object mSeparateChallengeLock;
    @GuardedBy({"mSpManager"})
    private SparseArray<SyntheticPasswordManager.AuthenticationToken> mSpCache;
    private final SyntheticPasswordManager mSpManager;
    @VisibleForTesting
    protected final LockSettingsStorage mStorage;
    private final IStorageManager mStorageManager;
    private final LockSettingsStrongAuth mStrongAuth;
    private final SynchronizedStrongAuthTracker mStrongAuthTracker;
    private final UserManager mUserManager;
    private static final int[] SYSTEM_CREDENTIAL_UIDS = {1010, 1016, 0, 1000};
    private static final String[] VALID_SETTINGS = {"lockscreen.lockedoutpermanently", "lockscreen.patterneverchosen", "lockscreen.password_type", "lockscreen.password_type_alternate", "lockscreen.password_salt", "lockscreen.disabled", "lockscreen.options", "lockscreen.biometric_weak_fallback", "lockscreen.biometricweakeverchosen", "lockscreen.power_button_instantly_locks", "lockscreen.passwordhistory", "lock_pattern_autolock", "lock_biometric_weak_flags", "lock_pattern_visible_pattern", "lock_pattern_tactile_feedback_enabled"};
    private static final String[] READ_CONTACTS_PROTECTED_SETTINGS = {"lock_screen_owner_info_enabled", "lock_screen_owner_info"};
    private static final String SEPARATE_PROFILE_CHALLENGE_KEY = "lockscreen.profilechallenge";
    private static final String[] READ_PASSWORD_PROTECTED_SETTINGS = {"lockscreen.password_salt", "lockscreen.passwordhistory", "lockscreen.password_type", SEPARATE_PROFILE_CHALLENGE_KEY};
    private static final String[] SETTINGS_TO_BACKUP = {"lock_screen_owner_info_enabled", "lock_screen_owner_info", "lock_pattern_visible_pattern", "lockscreen.power_button_instantly_locks"};

    /* loaded from: classes.dex */
    @interface ChallengeType {
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        private LockSettingsService mLockSettingsService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            AndroidKeyStoreProvider.install();
            this.mLockSettingsService = new LockSettingsService(getContext());
            publishBinderService("lock_settings", this.mLockSettingsService);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
            if (phase == 550) {
                this.mLockSettingsService.migrateOldDataAfterSystemReady();
            }
        }

        @Override // com.android.server.SystemService
        public void onStartUser(int userHandle) {
            this.mLockSettingsService.onStartUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            this.mLockSettingsService.onUnlockUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onCleanupUser(int userHandle) {
            this.mLockSettingsService.onCleanupUser(userHandle);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class SynchronizedStrongAuthTracker extends LockPatternUtils.StrongAuthTracker {
        public SynchronizedStrongAuthTracker(Context context) {
            super(context);
        }

        protected void handleStrongAuthRequiredChanged(int strongAuthFlags, int userId) {
            synchronized (this) {
                super.handleStrongAuthRequiredChanged(strongAuthFlags, userId);
            }
        }

        public int getStrongAuthForUser(int userId) {
            int strongAuthForUser;
            synchronized (this) {
                strongAuthForUser = super.getStrongAuthForUser(userId);
            }
            return strongAuthForUser;
        }

        void register(LockSettingsStrongAuth strongAuth) {
            strongAuth.registerStrongAuthTracker(this.mStub);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PendingResetLockout {
        final byte[] mHAT;
        final int mUserId;

        PendingResetLockout(int userId, byte[] hat) {
            this.mUserId = userId;
            this.mHAT = hat;
        }
    }

    public void tieManagedProfileLockIfNecessary(int managedUserId, byte[] managedUserPassword) {
        if (!this.mUserManager.getUserInfo(managedUserId).isManagedProfile() || this.mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId) || this.mStorage.hasChildProfileLock(managedUserId)) {
            return;
        }
        int parentId = this.mUserManager.getProfileParent(managedUserId).id;
        if (!isUserSecure(parentId)) {
            return;
        }
        try {
            if (getGateKeeperService().getSecureUserId(parentId) == 0) {
                return;
            }
            byte[] bArr = new byte[0];
            try {
                byte[] randomLockSeed = SecureRandom.getInstance("SHA1PRNG").generateSeed(40);
                try {
                    char[] newPasswordChars = HexEncoding.encode(randomLockSeed);
                    byte[] newPassword = new byte[newPasswordChars.length];
                    for (int i = 0; i < newPasswordChars.length; i++) {
                        newPassword[i] = (byte) newPasswordChars[i];
                    }
                    Arrays.fill(newPasswordChars, (char) 0);
                    setLockCredentialInternal(newPassword, 2, managedUserPassword, 327680, managedUserId, false, true);
                    setLong("lockscreen.password_type", 327680L, managedUserId);
                    tieProfileLockToParent(managedUserId, newPassword);
                    Arrays.fill(newPassword, (byte) 0);
                } catch (RemoteException | NoSuchAlgorithmException e) {
                    e = e;
                    Slog.e(TAG, "Fail to tie managed profile", e);
                }
            } catch (RemoteException | NoSuchAlgorithmException e2) {
                e = e2;
            }
        } catch (RemoteException e3) {
            Slog.e(TAG, "Failed to talk to GateKeeper service", e3);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class Injector {
        protected Context mContext;

        public Injector(Context context) {
            this.mContext = context;
        }

        public Context getContext() {
            return this.mContext;
        }

        public Handler getHandler() {
            return new Handler();
        }

        public LockSettingsStorage getStorage() {
            final LockSettingsStorage storage = new LockSettingsStorage(this.mContext);
            storage.setDatabaseOnCreateCallback(new LockSettingsStorage.Callback() { // from class: com.android.server.locksettings.LockSettingsService.Injector.1
                @Override // com.android.server.locksettings.LockSettingsStorage.Callback
                public void initialize(SQLiteDatabase db) {
                    boolean lockScreenDisable = SystemProperties.getBoolean("ro.lockscreen.disable.default", false);
                    if (lockScreenDisable) {
                        storage.writeKeyValue(db, "lockscreen.disabled", "1", 0);
                    }
                }
            });
            return storage;
        }

        public LockSettingsStrongAuth getStrongAuth() {
            return new LockSettingsStrongAuth(this.mContext);
        }

        public SynchronizedStrongAuthTracker getStrongAuthTracker() {
            return new SynchronizedStrongAuthTracker(this.mContext);
        }

        public IActivityManager getActivityManager() {
            return ActivityManager.getService();
        }

        public LockPatternUtils getLockPatternUtils() {
            return new LockPatternUtils(this.mContext);
        }

        public NotificationManager getNotificationManager() {
            return (NotificationManager) this.mContext.getSystemService("notification");
        }

        public UserManager getUserManager() {
            return (UserManager) this.mContext.getSystemService("user");
        }

        public DevicePolicyManager getDevicePolicyManager() {
            return (DevicePolicyManager) this.mContext.getSystemService("device_policy");
        }

        public KeyStore getKeyStore() {
            return KeyStore.getInstance();
        }

        public RecoverableKeyStoreManager getRecoverableKeyStoreManager(KeyStore keyStore) {
            return RecoverableKeyStoreManager.getInstance(this.mContext, keyStore);
        }

        public IStorageManager getStorageManager() {
            IBinder service = ServiceManager.getService("mount");
            if (service != null) {
                return IStorageManager.Stub.asInterface(service);
            }
            return null;
        }

        public SyntheticPasswordManager getSyntheticPasswordManager(LockSettingsStorage storage) {
            return new SyntheticPasswordManager(getContext(), storage, getUserManager(), new PasswordSlotManager());
        }

        public boolean hasEnrolledBiometrics(int userId) {
            BiometricManager bm = (BiometricManager) this.mContext.getSystemService(BiometricManager.class);
            return bm.hasEnrolledBiometrics(userId);
        }

        public int binderGetCallingUid() {
            return Binder.getCallingUid();
        }

        public boolean isGsiRunning() {
            return SystemProperties.getInt(LockSettingsService.GSI_RUNNING_PROP, 0) > 0;
        }
    }

    public LockSettingsService(Context context) {
        this(new Injector(context));
    }

    @VisibleForTesting
    protected LockSettingsService(Injector injector) {
        this.mSeparateChallengeLock = new Object();
        this.mDeviceProvisionedObserver = new DeviceProvisionedObserver();
        this.mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.locksettings.LockSettingsService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                int userHandle;
                if ("android.intent.action.USER_ADDED".equals(intent.getAction())) {
                    int userHandle2 = intent.getIntExtra("android.intent.extra.user_handle", 0);
                    if (userHandle2 > 0) {
                        LockSettingsService.this.removeUser(userHandle2, true);
                    }
                    KeyStore ks = KeyStore.getInstance();
                    UserInfo parentInfo = LockSettingsService.this.mUserManager.getProfileParent(userHandle2);
                    int parentHandle = parentInfo != null ? parentInfo.id : -1;
                    ks.onUserAdded(userHandle2, parentHandle);
                } else if ("android.intent.action.USER_STARTING".equals(intent.getAction())) {
                    LockSettingsService.this.mStorage.prefetchUser(intent.getIntExtra("android.intent.extra.user_handle", 0));
                } else if ("android.intent.action.USER_REMOVED".equals(intent.getAction()) && (userHandle = intent.getIntExtra("android.intent.extra.user_handle", 0)) > 0) {
                    LockSettingsService.this.removeUser(userHandle, false);
                }
            }
        };
        this.mSpCache = new SparseArray<>();
        this.mInjector = injector;
        this.mContext = injector.getContext();
        this.mKeyStore = injector.getKeyStore();
        this.mRecoverableKeyStoreManager = injector.getRecoverableKeyStoreManager(this.mKeyStore);
        this.mHandler = injector.getHandler();
        this.mStrongAuth = injector.getStrongAuth();
        this.mActivityManager = injector.getActivityManager();
        this.mLockPatternUtils = injector.getLockPatternUtils();
        this.mFirstCallToVold = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.USER_ADDED");
        filter.addAction("android.intent.action.USER_STARTING");
        filter.addAction("android.intent.action.USER_REMOVED");
        injector.getContext().registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        this.mStorage = injector.getStorage();
        this.mNotificationManager = injector.getNotificationManager();
        this.mUserManager = injector.getUserManager();
        this.mStorageManager = injector.getStorageManager();
        this.mStrongAuthTracker = injector.getStrongAuthTracker();
        this.mStrongAuthTracker.register(this.mStrongAuth);
        this.mSpManager = injector.getSyntheticPasswordManager(this.mStorage);
        LocalServices.addService(LockSettingsInternal.class, new LocalService());
    }

    private void maybeShowEncryptionNotificationForUser(int userId) {
        UserInfo parent;
        UserInfo user = this.mUserManager.getUserInfo(userId);
        if (!user.isManagedProfile() || isUserKeyUnlocked(userId)) {
            return;
        }
        UserHandle userHandle = user.getUserHandle();
        boolean isSecure = isUserSecure(userId);
        if (isSecure && !this.mUserManager.isUserUnlockingOrUnlocked(userHandle) && (parent = this.mUserManager.getProfileParent(userId)) != null && this.mUserManager.isUserUnlockingOrUnlocked(parent.getUserHandle()) && !this.mUserManager.isQuietModeEnabled(userHandle)) {
            showEncryptionNotificationForProfile(userHandle);
        }
    }

    private void showEncryptionNotificationForProfile(UserHandle user) {
        Resources r = this.mContext.getResources();
        CharSequence title = r.getText(17040903);
        CharSequence message = r.getText(17040902);
        CharSequence detail = r.getText(17040901);
        KeyguardManager km = (KeyguardManager) this.mContext.getSystemService("keyguard");
        Intent unlockIntent = km.createConfirmDeviceCredentialIntent(null, null, user.getIdentifier());
        if (unlockIntent == null) {
            return;
        }
        unlockIntent.setFlags(276824064);
        PendingIntent intent = PendingIntent.getActivity(this.mContext, 0, unlockIntent, 134217728);
        showEncryptionNotification(user, title, message, detail, intent);
    }

    private void showEncryptionNotification(UserHandle user, CharSequence title, CharSequence message, CharSequence detail, PendingIntent intent) {
        if (StorageManager.isFileEncryptedNativeOrEmulated()) {
            Notification notification = new Notification.Builder(this.mContext, SystemNotificationChannels.DEVICE_ADMIN).setSmallIcon(17302838).setWhen(0L).setOngoing(true).setTicker(title).setColor(this.mContext.getColor(17170460)).setContentTitle(title).setContentText(message).setSubText(detail).setVisibility(1).setContentIntent(intent).build();
            this.mNotificationManager.notifyAsUser(null, 9, notification, user);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideEncryptionNotification(UserHandle userHandle) {
        this.mNotificationManager.cancelAsUser(null, 9, userHandle);
    }

    public void onCleanupUser(int userId) {
        hideEncryptionNotification(new UserHandle(userId));
        int strongAuthRequired = LockPatternUtils.StrongAuthTracker.getDefaultFlags(this.mContext);
        requireStrongAuth(strongAuthRequired, userId);
    }

    public void onStartUser(int userId) {
        maybeShowEncryptionNotificationForUser(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void ensureProfileKeystoreUnlocked(int userId) {
        KeyStore ks = KeyStore.getInstance();
        if (ks.state(userId) == KeyStore.State.LOCKED && tiedManagedProfileReadyToUnlock(this.mUserManager.getUserInfo(userId))) {
            Slog.i(TAG, "Managed profile got unlocked, will unlock its keystore");
            try {
                unlockChildProfile(userId, true, 0, 0L, null);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to unlock child profile");
            }
        }
    }

    public void onUnlockUser(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.locksettings.LockSettingsService.1
            @Override // java.lang.Runnable
            public void run() {
                LockSettingsService.this.ensureProfileKeystoreUnlocked(userId);
                LockSettingsService.this.hideEncryptionNotification(new UserHandle(userId));
                if (LockSettingsService.this.mUserManager.getUserInfo(userId).isManagedProfile()) {
                    LockSettingsService.this.tieManagedProfileLockIfNecessary(userId, null);
                }
                if (LockSettingsService.this.mUserManager.getUserInfo(userId).isPrimary() && !LockSettingsService.this.isUserSecure(userId)) {
                    LockSettingsService.this.tryDeriveAuthTokenForUnsecuredPrimaryUser(userId);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryDeriveAuthTokenForUnsecuredPrimaryUser(int userId) {
        synchronized (this.mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                try {
                    long handle = getSyntheticPasswordHandleLocked(userId);
                    SyntheticPasswordManager.AuthenticationResult result = this.mSpManager.unwrapPasswordBasedSyntheticPassword(getGateKeeperService(), handle, null, userId, null);
                    if (result.authToken != null) {
                        Slog.i(TAG, "Retrieved auth token for user " + userId);
                        onAuthTokenKnownForUser(userId, result.authToken);
                    } else {
                        Slog.e(TAG, "Auth token not available for user " + userId);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failure retrieving auth token", e);
                }
            }
        }
    }

    public void systemReady() {
        if (this.mContext.checkCallingOrSelfPermission(PERMISSION) != 0) {
            EventLog.writeEvent(1397638484, "28251513", Integer.valueOf(getCallingUid()), "");
        }
        checkWritePermission(0);
        migrateOldData();
        try {
            getGateKeeperService();
            this.mSpManager.initWeaverService();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failure retrieving IGateKeeperService", e);
        }
        try {
            this.mAuthSecretService = IAuthSecret.getService();
        } catch (RemoteException e2) {
            Slog.w(TAG, "Failed to get AuthSecret HAL", e2);
        } catch (NoSuchElementException e3) {
            Slog.i(TAG, "Device doesn't implement AuthSecret HAL");
        }
        this.mDeviceProvisionedObserver.onSystemReady();
        this.mStorage.prefetchUser(0);
    }

    private void migrateOldData() {
        int i;
        int i2;
        String[] strArr;
        int i3 = 0;
        if (getString("migrated", null, 0) == null) {
            ContentResolver cr = this.mContext.getContentResolver();
            for (String validSetting : VALID_SETTINGS) {
                String value = Settings.Secure.getString(cr, validSetting);
                if (value != null) {
                    setString(validSetting, value, 0);
                }
            }
            setString("migrated", "true", 0);
            Slog.i(TAG, "Migrated lock settings to new location");
        }
        if (getString("migrated_user_specific", null, 0) != null) {
            i = 0;
        } else {
            ContentResolver cr2 = this.mContext.getContentResolver();
            List<UserInfo> users = this.mUserManager.getUsers();
            int user = 0;
            while (user < users.size()) {
                int userId = users.get(user).id;
                String ownerInfo = Settings.Secure.getStringForUser(cr2, "lock_screen_owner_info", userId);
                if (!TextUtils.isEmpty(ownerInfo)) {
                    setString("lock_screen_owner_info", ownerInfo, userId);
                    Settings.Secure.putStringForUser(cr2, "lock_screen_owner_info", "", userId);
                }
                try {
                    int ivalue = Settings.Secure.getIntForUser(cr2, "lock_screen_owner_info_enabled", userId);
                    setLong("lock_screen_owner_info_enabled", (ivalue != 0 ? 1 : i3) != 0 ? 1L : 0L, userId);
                } catch (Settings.SettingNotFoundException e) {
                    if (!TextUtils.isEmpty(ownerInfo)) {
                        setLong("lock_screen_owner_info_enabled", 1L, userId);
                    }
                }
                Settings.Secure.putIntForUser(cr2, "lock_screen_owner_info_enabled", 0, userId);
                user++;
                i3 = 0;
            }
            i = i3;
            setString("migrated_user_specific", "true", i);
            Slog.i(TAG, "Migrated per-user lock settings to new location");
        }
        if (getString("migrated_biometric_weak", null, i) != null) {
            i2 = 0;
        } else {
            List<UserInfo> users2 = this.mUserManager.getUsers();
            for (int i4 = 0; i4 < users2.size(); i4++) {
                int userId2 = users2.get(i4).id;
                long type = getLong("lockscreen.password_type", 0L, userId2);
                long alternateType = getLong("lockscreen.password_type_alternate", 0L, userId2);
                if (type == 32768) {
                    setLong("lockscreen.password_type", alternateType, userId2);
                }
                setLong("lockscreen.password_type_alternate", 0L, userId2);
            }
            i2 = 0;
            setString("migrated_biometric_weak", "true", 0);
            Slog.i(TAG, "Migrated biometric weak to use the fallback instead");
        }
        if (getString("migrated_lockscreen_disabled", null, i2) == null) {
            List<UserInfo> users3 = this.mUserManager.getUsers();
            int userCount = users3.size();
            int switchableUsers = 0;
            for (int i5 = 0; i5 < userCount; i5++) {
                if (users3.get(i5).supportsSwitchTo()) {
                    switchableUsers++;
                }
            }
            if (switchableUsers > 1) {
                for (int i6 = 0; i6 < userCount; i6++) {
                    int id = users3.get(i6).id;
                    if (getBoolean("lockscreen.disabled", false, id)) {
                        setBoolean("lockscreen.disabled", false, id);
                    }
                }
            }
            setString("migrated_lockscreen_disabled", "true", 0);
            Slog.i(TAG, "Migrated lockscreen disabled flag");
        }
        List<UserInfo> users4 = this.mUserManager.getUsers();
        for (int i7 = 0; i7 < users4.size(); i7++) {
            UserInfo userInfo = users4.get(i7);
            if (userInfo.isManagedProfile() && this.mStorage.hasChildProfileLock(userInfo.id)) {
                long quality = getLong("lockscreen.password_type", 0L, userInfo.id);
                if (quality == 0) {
                    Slog.i(TAG, "Migrated tied profile lock type");
                    setLong("lockscreen.password_type", 327680L, userInfo.id);
                } else if (quality != 327680) {
                    Slog.e(TAG, "Invalid tied profile lock type: " + quality);
                }
            }
            try {
                String alias = "profile_key_name_encrypt_" + userInfo.id;
                java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias);
                }
            } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e2) {
                Slog.e(TAG, "Unable to remove tied profile key", e2);
            }
        }
        boolean isWatch = this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.watch");
        if (isWatch && getString("migrated_wear_lockscreen_disabled", null, 0) == null) {
            int userCount2 = users4.size();
            for (int i8 = 0; i8 < userCount2; i8++) {
                setBoolean("lockscreen.disabled", false, users4.get(i8).id);
            }
            setString("migrated_wear_lockscreen_disabled", "true", 0);
            Slog.i(TAG, "Migrated lockscreen_disabled for Wear devices");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void migrateOldDataAfterSystemReady() {
        try {
            if (LockPatternUtils.frpCredentialEnabled(this.mContext) && !getBoolean("migrated_frp", false, 0)) {
                migrateFrpCredential();
                setBoolean("migrated_frp", true, 0);
                Slog.i(TAG, "Migrated migrated_frp.");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to migrateOldDataAfterSystemReady", e);
        }
    }

    private void migrateFrpCredential() throws RemoteException {
        if (this.mStorage.readPersistentDataBlock() != LockSettingsStorage.PersistentData.NONE) {
            return;
        }
        for (UserInfo userInfo : this.mUserManager.getUsers()) {
            if (LockPatternUtils.userOwnsFrpCredential(this.mContext, userInfo) && isUserSecure(userInfo.id)) {
                synchronized (this.mSpManager) {
                    if (isSyntheticPasswordBasedCredentialLocked(userInfo.id)) {
                        int actualQuality = (int) getLong("lockscreen.password_type", 0L, userInfo.id);
                        this.mSpManager.migrateFrpPasswordLocked(getSyntheticPasswordHandleLocked(userInfo.id), userInfo, redactActualQualityToMostLenientEquivalentQuality(actualQuality));
                    }
                }
                return;
            }
        }
    }

    private int redactActualQualityToMostLenientEquivalentQuality(int quality) {
        if (quality == 131072 || quality == 196608) {
            return 131072;
        }
        if (quality == 262144 || quality == 327680 || quality == 393216) {
            return 262144;
        }
        return quality;
    }

    private final void checkWritePermission(int userId) {
        this.mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsWrite");
    }

    private final void checkPasswordReadPermission(int userId) {
        this.mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsRead");
    }

    private final void checkPasswordHavePermission(int userId) {
        if (this.mContext.checkCallingOrSelfPermission(PERMISSION) != 0) {
            EventLog.writeEvent(1397638484, "28251513", Integer.valueOf(getCallingUid()), "");
        }
        this.mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsHave");
    }

    private final void checkReadPermission(String requestedKey, int userId) {
        int callingUid = Binder.getCallingUid();
        int i = 0;
        while (true) {
            String[] strArr = READ_CONTACTS_PROTECTED_SETTINGS;
            if (i < strArr.length) {
                String key = strArr[i];
                if (!key.equals(requestedKey) || this.mContext.checkCallingOrSelfPermission("android.permission.READ_CONTACTS") == 0) {
                    i++;
                } else {
                    throw new SecurityException("uid=" + callingUid + " needs permission android.permission.READ_CONTACTS to read " + requestedKey + " for user " + userId);
                }
            } else {
                int i2 = 0;
                while (true) {
                    String[] strArr2 = READ_PASSWORD_PROTECTED_SETTINGS;
                    if (i2 < strArr2.length) {
                        String key2 = strArr2[i2];
                        if (!key2.equals(requestedKey) || this.mContext.checkCallingOrSelfPermission(PERMISSION) == 0) {
                            i2++;
                        } else {
                            throw new SecurityException("uid=" + callingUid + " needs permission " + PERMISSION + " to read " + requestedKey + " for user " + userId);
                        }
                    } else {
                        return;
                    }
                }
            }
        }
    }

    public boolean getSeparateProfileChallengeEnabled(int userId) {
        boolean z;
        checkReadPermission(SEPARATE_PROFILE_CHALLENGE_KEY, userId);
        synchronized (this.mSeparateChallengeLock) {
            z = getBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, false, userId);
        }
        return z;
    }

    public void setSeparateProfileChallengeEnabled(int userId, boolean enabled, byte[] managedUserPassword) {
        checkWritePermission(userId);
        if (!this.mLockPatternUtils.hasSecureLockScreen()) {
            throw new UnsupportedOperationException("This operation requires secure lock screen feature.");
        }
        synchronized (this.mSeparateChallengeLock) {
            setSeparateProfileChallengeEnabledLocked(userId, enabled, managedUserPassword);
        }
        notifySeparateProfileChallengeChanged(userId);
    }

    @GuardedBy({"mSeparateChallengeLock"})
    private void setSeparateProfileChallengeEnabledLocked(int userId, boolean enabled, byte[] managedUserPassword) {
        boolean old = getBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, false, userId);
        setBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, enabled, userId);
        try {
            if (enabled) {
                this.mStorage.removeChildProfileLock(userId);
                removeKeystoreProfileKey(userId);
                return;
            }
            tieManagedProfileLockIfNecessary(userId, managedUserPassword);
        } catch (IllegalStateException e) {
            setBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, old, userId);
            throw e;
        }
    }

    private void notifySeparateProfileChallengeChanged(int userId) {
        DevicePolicyManagerInternal dpmi = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class);
        if (dpmi != null) {
            dpmi.reportSeparateProfileChallengeChanged(userId);
        }
    }

    public void setBoolean(String key, boolean value, int userId) {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value ? "1" : "0");
    }

    public void setLong(String key, long value, int userId) {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, Long.toString(value));
    }

    public void setString(String key, String value, int userId) {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value);
    }

    private void setStringUnchecked(String key, int userId, String value) {
        Preconditions.checkArgument(userId != -9999, "cannot store lock settings for FRP user");
        this.mStorage.writeKeyValue(key, value, userId);
        if (ArrayUtils.contains(SETTINGS_TO_BACKUP, key)) {
            BackupManager.dataChanged(UserBackupManagerService.SETTINGS_PACKAGE);
        }
    }

    public boolean getBoolean(String key, boolean defaultValue, int userId) {
        checkReadPermission(key, userId);
        String value = getStringUnchecked(key, null, userId);
        if (TextUtils.isEmpty(value)) {
            return defaultValue;
        }
        return value.equals("1") || value.equals("true");
    }

    public long getLong(String key, long defaultValue, int userId) {
        checkReadPermission(key, userId);
        String value = getStringUnchecked(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Long.parseLong(value);
    }

    public String getString(String key, String defaultValue, int userId) {
        checkReadPermission(key, userId);
        return getStringUnchecked(key, defaultValue, userId);
    }

    public String getStringUnchecked(String key, String defaultValue, int userId) {
        if ("lock_pattern_autolock".equals(key)) {
            long ident = Binder.clearCallingIdentity();
            try {
                return this.mLockPatternUtils.isLockPatternEnabled(userId) ? "1" : "0";
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        } else if (userId == -9999) {
            return getFrpStringUnchecked(key);
        } else {
            if ("legacy_lock_pattern_enabled".equals(key)) {
                key = "lock_pattern_autolock";
            }
            return this.mStorage.readKeyValue(key, defaultValue, userId);
        }
    }

    private String getFrpStringUnchecked(String key) {
        if ("lockscreen.password_type".equals(key)) {
            return String.valueOf(readFrpPasswordQuality());
        }
        return null;
    }

    private int readFrpPasswordQuality() {
        return this.mStorage.readPersistentDataBlock().qualityForUi;
    }

    public boolean havePassword(int userId) {
        checkPasswordHavePermission(userId);
        synchronized (this.mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                long handle = getSyntheticPasswordHandleLocked(userId);
                return this.mSpManager.getCredentialType(handle, userId) == 2;
            }
            return this.mStorage.hasPassword(userId);
        }
    }

    public boolean havePattern(int userId) {
        checkPasswordHavePermission(userId);
        synchronized (this.mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                long handle = getSyntheticPasswordHandleLocked(userId);
                boolean z = true;
                if (this.mSpManager.getCredentialType(handle, userId) != 1) {
                    z = false;
                }
                return z;
            }
            return this.mStorage.hasPattern(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isUserSecure(int userId) {
        synchronized (this.mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                long handle = getSyntheticPasswordHandleLocked(userId);
                return this.mSpManager.getCredentialType(handle, userId) != -1;
            }
            return this.mStorage.hasCredential(userId);
        }
    }

    private void setKeystorePassword(byte[] password, int userHandle) {
        KeyStore ks = KeyStore.getInstance();
        String passwordString = password == null ? null : new String(password);
        ks.onUserPasswordChanged(userHandle, passwordString);
    }

    private void unlockKeystore(byte[] password, int userHandle) {
        String passwordString = password == null ? null : new String(password);
        KeyStore ks = KeyStore.getInstance();
        ks.unlock(userHandle, passwordString);
    }

    @VisibleForTesting
    protected byte[] getDecryptedPasswordForTiedProfile(int userId) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException, CertificateException, IOException {
        byte[] storedData = this.mStorage.readChildProfileLock(userId);
        if (storedData == null) {
            throw new FileNotFoundException("Child profile lock file not found");
        }
        byte[] iv = Arrays.copyOfRange(storedData, 0, 12);
        byte[] encryptedPassword = Arrays.copyOfRange(storedData, 12, storedData.length);
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey decryptionKey = (SecretKey) keyStore.getKey("profile_key_name_decrypt_" + userId, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(2, decryptionKey, new GCMParameterSpec(128, iv));
        byte[] decryptionResult = cipher.doFinal(encryptedPassword);
        return decryptionResult;
    }

    private void unlockChildProfile(int profileHandle, boolean ignoreUserNotAuthenticated, @ChallengeType int challengeType, long challenge, ArrayList<PendingResetLockout> resetLockouts) throws RemoteException {
        try {
            doVerifyCredential(getDecryptedPasswordForTiedProfile(profileHandle), 2, challengeType, challenge, profileHandle, null, resetLockouts);
        } catch (IOException | InvalidAlgorithmParameterException | InvalidKeyException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException e) {
            if (e instanceof FileNotFoundException) {
                Slog.i(TAG, "Child profile key not found");
            } else if (ignoreUserNotAuthenticated && (e instanceof UserNotAuthenticatedException)) {
                Slog.i(TAG, "Parent keystore seems locked, ignoring");
            } else {
                Slog.e(TAG, "Failed to decrypt child profile key", e);
            }
        }
    }

    private void unlockUser(int userId, byte[] token, byte[] secret) {
        unlockUser(userId, token, secret, 0, 0L, null);
    }

    /* JADX WARN: Removed duplicated region for block: B:30:0x007c  */
    /* JADX WARN: Removed duplicated region for block: B:62:0x008e A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void unlockUser(int r18, byte[] r19, byte[] r20, @com.android.server.locksettings.LockSettingsService.ChallengeType final int r21, long r22, final java.util.ArrayList<com.android.server.locksettings.LockSettingsService.PendingResetLockout> r24) {
        /*
            r17 = this;
            r8 = r17
            r9 = r18
            r10 = r24
            android.os.UserManager r0 = r8.mUserManager
            boolean r11 = r0.isUserUnlockingOrUnlocked(r9)
            java.util.concurrent.CountDownLatch r0 = new java.util.concurrent.CountDownLatch
            r1 = 1
            r0.<init>(r1)
            r12 = r0
            com.android.server.locksettings.LockSettingsService$3 r0 = new com.android.server.locksettings.LockSettingsService$3
            r0.<init>()
            r13 = r0
            android.app.IActivityManager r0 = r8.mActivityManager     // Catch: android.os.RemoteException -> Lab
            r14 = r19
            r15 = r20
            r0.unlockUser(r9, r14, r15, r13)     // Catch: android.os.RemoteException -> La9
            r0 = 15
            java.util.concurrent.TimeUnit r2 = java.util.concurrent.TimeUnit.SECONDS     // Catch: java.lang.InterruptedException -> L2b
            r12.await(r0, r2)     // Catch: java.lang.InterruptedException -> L2b
            goto L33
        L2b:
            r0 = move-exception
            java.lang.Thread r1 = java.lang.Thread.currentThread()
            r1.interrupt()
        L33:
            android.os.UserManager r0 = r8.mUserManager
            android.content.pm.UserInfo r0 = r0.getUserInfo(r9)
            boolean r0 = r0.isManagedProfile()
            if (r0 == 0) goto L40
            return
        L40:
            android.os.UserManager r0 = r8.mUserManager
            java.util.List r0 = r0.getProfiles(r9)
            java.util.Iterator r16 = r0.iterator()
        L4a:
            boolean r0 = r16.hasNext()
            if (r0 == 0) goto L91
            java.lang.Object r0 = r16.next()
            r7 = r0
            android.content.pm.UserInfo r7 = (android.content.pm.UserInfo) r7
            boolean r0 = r8.tiedManagedProfileReadyToUnlock(r7)
            if (r0 == 0) goto L79
            int r2 = r7.id     // Catch: android.os.RemoteException -> L6f
            r3 = 0
            r1 = r17
            r4 = r21
            r5 = r22
            r9 = r7
            r7 = r24
            r1.unlockChildProfile(r2, r3, r4, r5, r7)     // Catch: android.os.RemoteException -> L6d
            goto L7a
        L6d:
            r0 = move-exception
            goto L71
        L6f:
            r0 = move-exception
            r9 = r7
        L71:
            java.lang.String r1 = "LockSettingsService"
            java.lang.String r2 = "Failed to unlock child profile"
            android.util.Log.d(r1, r2, r0)
            goto L7a
        L79:
            r9 = r7
        L7a:
            if (r11 != 0) goto L8e
            long r1 = clearCallingIdentity()
            int r0 = r9.id     // Catch: java.lang.Throwable -> L89
            r8.maybeShowEncryptionNotificationForUser(r0)     // Catch: java.lang.Throwable -> L89
            restoreCallingIdentity(r1)
            goto L8e
        L89:
            r0 = move-exception
            restoreCallingIdentity(r1)
            throw r0
        L8e:
            r9 = r18
            goto L4a
        L91:
            if (r10 == 0) goto La6
            boolean r0 = r24.isEmpty()
            if (r0 != 0) goto La6
            android.os.Handler r0 = r8.mHandler
            com.android.server.locksettings.-$$Lambda$LockSettingsService$AUVBZ9-CG1pE2tUZGYyBunk-llo r1 = new com.android.server.locksettings.-$$Lambda$LockSettingsService$AUVBZ9-CG1pE2tUZGYyBunk-llo
            r2 = r21
            r1.<init>()
            r0.post(r1)
            goto La8
        La6:
            r2 = r21
        La8:
            return
        La9:
            r0 = move-exception
            goto Lb0
        Lab:
            r0 = move-exception
            r14 = r19
            r15 = r20
        Lb0:
            r2 = r21
            java.lang.RuntimeException r1 = r0.rethrowAsRuntimeException()
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.locksettings.LockSettingsService.unlockUser(int, byte[], byte[], int, long, java.util.ArrayList):void");
    }

    public /* synthetic */ void lambda$unlockUser$0$LockSettingsService(ArrayList resetLockouts, int challengeType) {
        BiometricManager bm = (BiometricManager) this.mContext.getSystemService(BiometricManager.class);
        PackageManager pm = this.mContext.getPackageManager();
        for (int i = 0; i < resetLockouts.size(); i++) {
            bm.setActiveUser(((PendingResetLockout) resetLockouts.get(i)).mUserId);
            bm.resetLockout(((PendingResetLockout) resetLockouts.get(i)).mHAT);
        }
        if (challengeType == 2 && pm.hasSystemFeature("android.hardware.biometrics.face")) {
            ((FaceManager) this.mContext.getSystemService(FaceManager.class)).revokeChallenge();
        }
    }

    private boolean tiedManagedProfileReadyToUnlock(UserInfo userInfo) {
        return userInfo.isManagedProfile() && !this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userInfo.id) && this.mStorage.hasChildProfileLock(userInfo.id) && this.mUserManager.isUserRunning(userInfo.id);
    }

    private Map<Integer, byte[]> getDecryptedPasswordsForAllTiedProfiles(int userId) {
        if (this.mUserManager.getUserInfo(userId).isManagedProfile()) {
            return null;
        }
        Map<Integer, byte[]> result = new ArrayMap<>();
        List<UserInfo> profiles = this.mUserManager.getProfiles(userId);
        int size = profiles.size();
        for (int i = 0; i < size; i++) {
            UserInfo profile = profiles.get(i);
            if (profile.isManagedProfile()) {
                int managedUserId = profile.id;
                if (!this.mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId)) {
                    try {
                        result.put(Integer.valueOf(managedUserId), getDecryptedPasswordForTiedProfile(managedUserId));
                    } catch (IOException | InvalidAlgorithmParameterException | InvalidKeyException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException e) {
                        Slog.e(TAG, "getDecryptedPasswordsForAllTiedProfiles failed for user " + managedUserId, e);
                    }
                }
            }
        }
        return result;
    }

    private void synchronizeUnifiedWorkChallengeForProfiles(int userId, Map<Integer, byte[]> profilePasswordMap) throws RemoteException {
        int managedUserId;
        if (this.mUserManager.getUserInfo(userId).isManagedProfile()) {
            return;
        }
        boolean isSecure = isUserSecure(userId);
        List<UserInfo> profiles = this.mUserManager.getProfiles(userId);
        int size = profiles.size();
        for (int i = 0; i < size; i++) {
            UserInfo profile = profiles.get(i);
            if (profile.isManagedProfile()) {
                int managedUserId2 = profile.id;
                if (!this.mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId2)) {
                    if (isSecure) {
                        tieManagedProfileLockIfNecessary(managedUserId2, null);
                    } else {
                        if (profilePasswordMap == null || !profilePasswordMap.containsKey(Integer.valueOf(managedUserId2))) {
                            managedUserId = managedUserId2;
                            Slog.wtf(TAG, "clear tied profile challenges, but no password supplied.");
                            setLockCredentialInternal(null, -1, null, 0, managedUserId, true, true);
                        } else {
                            managedUserId = managedUserId2;
                            setLockCredentialInternal(null, -1, profilePasswordMap.get(Integer.valueOf(managedUserId2)), 0, managedUserId2, false, true);
                        }
                        int managedUserId3 = managedUserId;
                        this.mStorage.removeChildProfileLock(managedUserId3);
                        removeKeystoreProfileKey(managedUserId3);
                    }
                }
            }
        }
    }

    private boolean isManagedProfileWithUnifiedLock(int userId) {
        return this.mUserManager.getUserInfo(userId).isManagedProfile() && !this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId);
    }

    private boolean isManagedProfileWithSeparatedLock(int userId) {
        return this.mUserManager.getUserInfo(userId).isManagedProfile() && this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userId);
    }

    private void sendCredentialsOnUnlockIfRequired(int credentialType, byte[] credential, int userId) {
        if (userId == -9999 || isManagedProfileWithUnifiedLock(userId)) {
            return;
        }
        for (Integer num : getProfilesWithSameLockScreen(userId)) {
            int profileId = num.intValue();
            this.mRecoverableKeyStoreManager.lockScreenSecretAvailable(credentialType, credential, profileId);
        }
    }

    private void sendCredentialsOnChangeIfRequired(int credentialType, byte[] credential, int userId, boolean isLockTiedToParent) {
        if (isLockTiedToParent) {
            return;
        }
        for (Integer num : getProfilesWithSameLockScreen(userId)) {
            int profileId = num.intValue();
            this.mRecoverableKeyStoreManager.lockScreenSecretChanged(credentialType, credential, profileId);
        }
    }

    private Set<Integer> getProfilesWithSameLockScreen(int userId) {
        Set<Integer> profiles = new ArraySet<>();
        for (UserInfo profile : this.mUserManager.getProfiles(userId)) {
            if (profile.id == userId || (profile.profileGroupId == userId && isManagedProfileWithUnifiedLock(profile.id))) {
                profiles.add(Integer.valueOf(profile.id));
            }
        }
        return profiles;
    }

    public void setLockCredential(byte[] credential, int type, byte[] savedCredential, int requestedQuality, int userId, boolean allowUntrustedChange) throws RemoteException {
        if (!this.mLockPatternUtils.hasSecureLockScreen()) {
            throw new UnsupportedOperationException("This operation requires secure lock screen feature");
        }
        checkWritePermission(userId);
        synchronized (this.mSeparateChallengeLock) {
            setLockCredentialInternal(credential, type, savedCredential, requestedQuality, userId, allowUntrustedChange, false);
            setSeparateProfileChallengeEnabledLocked(userId, true, null);
            notifyPasswordChanged(userId);
        }
        if (this.mUserManager.getUserInfo(userId).isManagedProfile()) {
            setDeviceUnlockedForUser(userId);
        }
        notifySeparateProfileChallengeChanged(userId);
    }

    private void setLockCredentialInternal(byte[] credential, int credentialType, byte[] savedCredential, int requestedQuality, int userId, boolean allowUntrustedChange, boolean isLockTiedToParent) throws RemoteException {
        SyntheticPasswordManager syntheticPasswordManager;
        byte[] savedCredential2 = (savedCredential == null || savedCredential.length == 0) ? null : savedCredential;
        byte[] credential2 = (credential == null || credential.length == 0) ? null : credential;
        synchronized (this.mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                spBasedSetLockCredentialInternalLocked(credential2, credentialType, savedCredential2, requestedQuality, userId, allowUntrustedChange, isLockTiedToParent);
            } else if (credentialType == -1) {
                if (credential2 != null) {
                    Slog.wtf(TAG, "CredentialType is none, but credential is non-null.");
                }
                clearUserKeyProtection(userId);
                getGateKeeperService().clearSecureUserId(userId);
                this.mStorage.writeCredentialHash(LockSettingsStorage.CredentialHash.createEmptyHash(), userId);
                setKeystorePassword(null, userId);
                fixateNewestUserKeyAuth(userId);
                synchronizeUnifiedWorkChallengeForProfiles(userId, null);
                notifyActivePasswordMetricsAvailable(-1, null, userId);
                sendCredentialsOnChangeIfRequired(credentialType, credential2, userId, isLockTiedToParent);
            } else if (credential2 == null) {
                throw new RemoteException("Null credential with mismatched credential type");
            } else {
                LockSettingsStorage.CredentialHash currentHandle = this.mStorage.readCredentialHash(userId);
                if (isManagedProfileWithUnifiedLock(userId)) {
                    if (savedCredential2 == null) {
                        try {
                            savedCredential2 = getDecryptedPasswordForTiedProfile(userId);
                        } catch (FileNotFoundException e) {
                            Slog.i(TAG, "Child profile key not found");
                        } catch (IOException | InvalidAlgorithmParameterException | InvalidKeyException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException e2) {
                            Slog.e(TAG, "Failed to decrypt child profile key", e2);
                        }
                    }
                } else if (currentHandle.hash == null) {
                    if (savedCredential2 != null) {
                        Slog.w(TAG, "Saved credential provided, but none stored");
                    }
                    savedCredential2 = null;
                }
                SyntheticPasswordManager syntheticPasswordManager2 = this.mSpManager;
                synchronized (syntheticPasswordManager2) {
                    try {
                        if (!shouldMigrateToSyntheticPasswordLocked(userId)) {
                            syntheticPasswordManager = syntheticPasswordManager2;
                            try {
                                byte[] enrolledHandle = enrollCredential(currentHandle.hash, savedCredential2, credential2, userId);
                                if (enrolledHandle != null) {
                                    LockSettingsStorage.CredentialHash willStore = LockSettingsStorage.CredentialHash.create(enrolledHandle, credentialType);
                                    this.mStorage.writeCredentialHash(willStore, userId);
                                    GateKeeperResponse gkResponse = getGateKeeperService().verifyChallenge(userId, 0L, willStore.hash, credential2);
                                    setUserKeyProtection(userId, credential2, convertResponse(gkResponse));
                                    fixateNewestUserKeyAuth(userId);
                                    doVerifyCredential(credential2, credentialType, 1, 0L, userId, null);
                                    synchronizeUnifiedWorkChallengeForProfiles(userId, null);
                                    sendCredentialsOnChangeIfRequired(credentialType, credential2, userId, isLockTiedToParent);
                                    return;
                                }
                                StringBuilder sb = new StringBuilder();
                                sb.append("Failed to enroll ");
                                sb.append(credentialType == 2 ? "password" : "pattern");
                                throw new RemoteException(sb.toString());
                            } catch (Throwable th) {
                                th = th;
                                while (true) {
                                    break;
                                    break;
                                }
                                throw th;
                            }
                        }
                        try {
                            initializeSyntheticPasswordLocked(currentHandle.hash, savedCredential2, currentHandle.type, requestedQuality, userId);
                            syntheticPasswordManager = syntheticPasswordManager2;
                        } catch (Throwable th2) {
                            th = th2;
                            syntheticPasswordManager = syntheticPasswordManager2;
                        }
                        try {
                            spBasedSetLockCredentialInternalLocked(credential2, credentialType, savedCredential2, requestedQuality, userId, allowUntrustedChange, isLockTiedToParent);
                        } catch (Throwable th3) {
                            th = th3;
                            while (true) {
                                try {
                                    break;
                                } catch (Throwable th4) {
                                    th = th4;
                                }
                            }
                            throw th;
                        }
                    } catch (Throwable th5) {
                        th = th5;
                        syntheticPasswordManager = syntheticPasswordManager2;
                    }
                }
            }
        }
    }

    private VerifyCredentialResponse convertResponse(GateKeeperResponse gateKeeperResponse) {
        return VerifyCredentialResponse.fromGateKeeperResponse(gateKeeperResponse);
    }

    @VisibleForTesting
    protected void tieProfileLockToParent(int userId, byte[] password) {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.setEntry("profile_key_name_encrypt_" + userId, new KeyStore.SecretKeyEntry(secretKey), new KeyProtection.Builder(1).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build());
            keyStore.setEntry("profile_key_name_decrypt_" + userId, new KeyStore.SecretKeyEntry(secretKey), new KeyProtection.Builder(2).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(30).setCriticalToDeviceEncryption(true).build());
            SecretKey keyStoreEncryptionKey = (SecretKey) keyStore.getKey("profile_key_name_encrypt_" + userId, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(1, keyStoreEncryptionKey);
            byte[] encryptionResult = cipher.doFinal(password);
            byte[] iv = cipher.getIV();
            keyStore.deleteEntry("profile_key_name_encrypt_" + userId);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                if (iv.length != 12) {
                    throw new RuntimeException("Invalid iv length: " + iv.length);
                }
                outputStream.write(iv);
                outputStream.write(encryptionResult);
                this.mStorage.writeChildProfileLock(userId, outputStream.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException("Failed to concatenate byte arrays", e);
            }
        } catch (IOException | InvalidKeyException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException e2) {
            throw new RuntimeException("Failed to encrypt key", e2);
        }
    }

    private byte[] enrollCredential(byte[] enrolledHandle, byte[] enrolledCredential, byte[] toEnroll, int userId) throws RemoteException {
        checkWritePermission(userId);
        GateKeeperResponse response = getGateKeeperService().enroll(userId, enrolledHandle, enrolledCredential, toEnroll);
        if (response == null) {
            return null;
        }
        byte[] hash = response.getPayload();
        if (hash != null) {
            setKeystorePassword(toEnroll, userId);
        } else {
            Slog.e(TAG, "Throttled while enrolling a password");
        }
        return hash;
    }

    private void setAuthlessUserKeyProtection(int userId, byte[] key) throws RemoteException {
        addUserKeyAuth(userId, null, key);
    }

    private void setUserKeyProtection(int userId, byte[] credential, VerifyCredentialResponse vcr) throws RemoteException {
        if (vcr == null) {
            throw new RemoteException("Null response verifying a credential we just set");
        }
        if (vcr.getResponseCode() != 0) {
            throw new RemoteException("Non-OK response verifying a credential we just set: " + vcr.getResponseCode());
        }
        byte[] token = vcr.getPayload();
        if (token == null) {
            throw new RemoteException("Empty payload verifying a credential we just set");
        }
        addUserKeyAuth(userId, token, secretFromCredential(credential));
    }

    private void clearUserKeyProtection(int userId) throws RemoteException {
        addUserKeyAuth(userId, null, null);
    }

    private static byte[] secretFromCredential(byte[] credential) throws RemoteException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] personalization = "Android FBE credential hash".getBytes();
            digest.update(Arrays.copyOf(personalization, 128));
            digest.update(credential);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("NoSuchAlgorithmException for SHA-512");
        }
    }

    private boolean isUserKeyUnlocked(int userId) {
        try {
            return this.mStorageManager.isUserKeyUnlocked(userId);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to check user key locked state", e);
            return false;
        }
    }

    private void unlockUserKey(int userId, byte[] token, byte[] secret) throws RemoteException {
        UserInfo userInfo = this.mUserManager.getUserInfo(userId);
        this.mStorageManager.unlockUserKey(userId, userInfo.serialNumber, token, secret);
    }

    private void addUserKeyAuth(int userId, byte[] token, byte[] secret) throws RemoteException {
        UserInfo userInfo = this.mUserManager.getUserInfo(userId);
        long callingId = Binder.clearCallingIdentity();
        try {
            this.mStorageManager.addUserKeyAuth(userId, userInfo.serialNumber, token, secret);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void fixateNewestUserKeyAuth(int userId) throws RemoteException {
        long callingId = Binder.clearCallingIdentity();
        try {
            this.mStorageManager.fixateNewestUserKeyAuth(userId);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void resetKeyStore(int userId) throws RemoteException {
        int[] profileIdsWithDisabled;
        int[] iArr;
        checkWritePermission(userId);
        List<UserInfo> profiles = this.mUserManager.getProfiles(userId);
        Iterator<UserInfo> it = profiles.iterator();
        byte[] managedUserDecryptedPassword = null;
        int managedUserId = -1;
        while (true) {
            if (it.hasNext()) {
                UserInfo pi = it.next();
                if (pi.isManagedProfile() && !this.mLockPatternUtils.isSeparateProfileChallengeEnabled(pi.id) && this.mStorage.hasChildProfileLock(pi.id)) {
                    if (managedUserId == -1) {
                        try {
                            managedUserDecryptedPassword = getDecryptedPasswordForTiedProfile(pi.id);
                            managedUserId = pi.id;
                        } catch (IOException | InvalidAlgorithmParameterException | InvalidKeyException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException e) {
                            Slog.e(TAG, "Failed to decrypt child profile key", e);
                        }
                    } else {
                        Slog.e(TAG, "More than one managed profile, uid1:" + managedUserId + ", uid2:" + pi.id);
                    }
                }
            } else {
                try {
                    break;
                } finally {
                    if (managedUserId != -1 && managedUserDecryptedPassword != null) {
                        tieProfileLockToParent(managedUserId, managedUserDecryptedPassword);
                    }
                }
            }
        }
        for (int profileId : this.mUserManager.getProfileIdsWithDisabled(userId)) {
            for (int uid : SYSTEM_CREDENTIAL_UIDS) {
                this.mKeyStore.clearUid(UserHandle.getUid(profileId, uid));
            }
        }
        if (managedUserDecryptedPassword != null && managedUserDecryptedPassword.length > 0) {
            Arrays.fill(managedUserDecryptedPassword, (byte) 0);
        }
    }

    public VerifyCredentialResponse checkCredential(byte[] credential, int type, int userId, ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        checkPasswordReadPermission(userId);
        return doVerifyCredential(credential, type, 0, 0L, userId, progressCallback);
    }

    public VerifyCredentialResponse verifyCredential(byte[] credential, int type, long challenge, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        return doVerifyCredential(credential, type, 1, challenge, userId, null);
    }

    private VerifyCredentialResponse doVerifyCredential(byte[] credential, int credentialType, @ChallengeType int challengeType, long challenge, int userId, ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        return doVerifyCredential(credential, credentialType, challengeType, challenge, userId, progressCallback, null);
    }

    private VerifyCredentialResponse doVerifyCredential(byte[] credential, int credentialType, @ChallengeType int challengeType, long challenge, int userId, ICheckCredentialProgressCallback progressCallback, ArrayList<PendingResetLockout> resetLockouts) throws RemoteException {
        byte[] credentialToVerify;
        if (credential == null || credential.length == 0) {
            throw new IllegalArgumentException("Credential can't be null or empty");
        }
        boolean z = false;
        if (userId == -9999 && Settings.Global.getInt(this.mContext.getContentResolver(), "device_provisioned", 0) != 0) {
            Slog.e(TAG, "FRP credential can only be verified prior to provisioning.");
            return VerifyCredentialResponse.ERROR;
        }
        VerifyCredentialResponse response = spBasedDoVerifyCredential(credential, credentialType, challengeType, challenge, userId, progressCallback, resetLockouts);
        if (response != null) {
            if (response.getResponseCode() == 0) {
                sendCredentialsOnUnlockIfRequired(credentialType, credential, userId);
            }
            return response;
        } else if (userId == -9999) {
            Slog.wtf(TAG, "Unexpected FRP credential type, should be SP based.");
            return VerifyCredentialResponse.ERROR;
        } else {
            LockSettingsStorage.CredentialHash storedHash = this.mStorage.readCredentialHash(userId);
            if (storedHash.type != credentialType) {
                Slog.wtf(TAG, "doVerifyCredential type mismatch with stored credential?? stored: " + storedHash.type + " passed in: " + credentialType);
                return VerifyCredentialResponse.ERROR;
            }
            if (storedHash.type == 1 && storedHash.isBaseZeroPattern) {
                z = true;
            }
            boolean shouldReEnrollBaseZero = z;
            if (shouldReEnrollBaseZero) {
                credentialToVerify = LockPatternUtils.patternByteArrayToBaseZero(credential);
            } else {
                credentialToVerify = credential;
            }
            VerifyCredentialResponse response2 = verifyCredential(userId, storedHash, credentialToVerify, challengeType, challenge, progressCallback);
            if (response2.getResponseCode() == 0) {
                this.mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);
                if (shouldReEnrollBaseZero) {
                    setLockCredentialInternal(credential, storedHash.type, credentialToVerify, 65536, userId, false, false);
                }
            }
            return response2;
        }
    }

    public VerifyCredentialResponse verifyTiedProfileChallenge(byte[] credential, int type, long challenge, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        if (!isManagedProfileWithUnifiedLock(userId)) {
            throw new RemoteException("User id must be managed profile with unified lock");
        }
        int parentProfileId = this.mUserManager.getProfileParent(userId).id;
        VerifyCredentialResponse parentResponse = doVerifyCredential(credential, type, 1, challenge, parentProfileId, null);
        if (parentResponse.getResponseCode() != 0) {
            return parentResponse;
        }
        try {
            return doVerifyCredential(getDecryptedPasswordForTiedProfile(userId), 2, 1, challenge, userId, null);
        } catch (IOException | InvalidAlgorithmParameterException | InvalidKeyException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException | BadPaddingException | IllegalBlockSizeException | NoSuchPaddingException e) {
            Slog.e(TAG, "Failed to decrypt child profile key", e);
            throw new RemoteException("Unable to get tied profile token");
        }
    }

    private VerifyCredentialResponse verifyCredential(int userId, LockSettingsStorage.CredentialHash storedHash, byte[] credential, @ChallengeType int challengeType, long challenge, ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        int reEnrollQuality;
        byte[] hash;
        int i;
        if ((storedHash == null || storedHash.hash.length == 0) && (credential == null || credential.length == 0)) {
            return VerifyCredentialResponse.OK;
        }
        if (storedHash == null || credential == null || credential.length == 0) {
            return VerifyCredentialResponse.ERROR;
        }
        StrictMode.noteDiskRead();
        if (storedHash.version == 0) {
            if (storedHash.type == 1) {
                hash = LockPatternUtils.patternToHash(LockPatternUtils.byteArrayToPattern(credential));
            } else {
                hash = this.mLockPatternUtils.legacyPasswordToHash(credential, userId).getBytes();
            }
            if (Arrays.equals(hash, storedHash.hash)) {
                if (storedHash.type == 1) {
                    unlockKeystore(LockPatternUtils.patternByteArrayToBaseZero(credential), userId);
                } else {
                    unlockKeystore(credential, userId);
                }
                Slog.i(TAG, "Unlocking user with fake token: " + userId);
                byte[] fakeToken = String.valueOf(userId).getBytes();
                unlockUser(userId, fakeToken, fakeToken);
                int i2 = storedHash.type;
                if (storedHash.type == 1) {
                    i = 65536;
                } else {
                    i = 327680;
                }
                setLockCredentialInternal(credential, i2, null, i, userId, false, false);
                if (challengeType == 0) {
                    notifyActivePasswordMetricsAvailable(storedHash.type, credential, userId);
                    sendCredentialsOnUnlockIfRequired(storedHash.type, credential, userId);
                    return VerifyCredentialResponse.OK;
                }
            } else {
                return VerifyCredentialResponse.ERROR;
            }
        }
        GateKeeperResponse gateKeeperResponse = getGateKeeperService().verifyChallenge(userId, challenge, storedHash.hash, credential);
        VerifyCredentialResponse response = convertResponse(gateKeeperResponse);
        boolean shouldReEnroll = gateKeeperResponse.getShouldReEnroll();
        if (response.getResponseCode() == 0) {
            if (progressCallback != null) {
                progressCallback.onCredentialVerified();
            }
            notifyActivePasswordMetricsAvailable(storedHash.type, credential, userId);
            unlockKeystore(credential, userId);
            Slog.i(TAG, "Unlocking user " + userId + " with token length " + response.getPayload().length);
            unlockUser(userId, response.getPayload(), secretFromCredential(credential));
            if (isManagedProfileWithSeparatedLock(userId)) {
                setDeviceUnlockedForUser(userId);
            }
            if (storedHash.type == 1) {
                reEnrollQuality = 65536;
            } else {
                reEnrollQuality = 327680;
            }
            if (!shouldReEnroll) {
                synchronized (this.mSpManager) {
                    if (shouldMigrateToSyntheticPasswordLocked(userId)) {
                        SyntheticPasswordManager.AuthenticationToken auth = initializeSyntheticPasswordLocked(storedHash.hash, credential, storedHash.type, reEnrollQuality, userId);
                        activateEscrowTokens(auth, userId);
                    }
                }
            } else {
                setLockCredentialInternal(credential, storedHash.type, credential, reEnrollQuality, userId, false, false);
            }
            sendCredentialsOnUnlockIfRequired(storedHash.type, credential, userId);
        } else if (response.getResponseCode() == 1 && response.getTimeout() > 0) {
            requireStrongAuth(8, userId);
        }
        return response;
    }

    private void notifyActivePasswordMetricsAvailable(int credentialType, byte[] password, final int userId) {
        final PasswordMetrics metrics = PasswordMetrics.computeForCredential(credentialType, password);
        this.mHandler.post(new Runnable() { // from class: com.android.server.locksettings.-$$Lambda$LockSettingsService$QBoYkqRgslYmc5oBM_ZZV6ktQWg
            @Override // java.lang.Runnable
            public final void run() {
                LockSettingsService.this.lambda$notifyActivePasswordMetricsAvailable$1$LockSettingsService(metrics, userId);
            }
        });
    }

    public /* synthetic */ void lambda$notifyActivePasswordMetricsAvailable$1$LockSettingsService(PasswordMetrics metrics, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) this.mContext.getSystemService("device_policy");
        dpm.setActivePasswordState(metrics, userId);
    }

    private void notifyPasswordChanged(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.locksettings.-$$Lambda$LockSettingsService$uLUdo5pAFhnR0hn-L5FUgWTjl70
            @Override // java.lang.Runnable
            public final void run() {
                LockSettingsService.this.lambda$notifyPasswordChanged$2$LockSettingsService(userId);
            }
        });
    }

    public /* synthetic */ void lambda$notifyPasswordChanged$2$LockSettingsService(int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) this.mContext.getSystemService("device_policy");
        dpm.reportPasswordChanged(userId);
        ((WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class)).reportPasswordChanged(userId);
    }

    public boolean checkVoldPassword(int userId) throws RemoteException {
        if (this.mFirstCallToVold) {
            this.mFirstCallToVold = false;
            checkPasswordReadPermission(userId);
            IStorageManager service = this.mInjector.getStorageManager();
            long identity = Binder.clearCallingIdentity();
            try {
                String password = service.getPassword();
                service.clearPassword();
                if (password == null) {
                    return false;
                }
                try {
                    if (this.mLockPatternUtils.isLockPatternEnabled(userId)) {
                        if (checkCredential(password.getBytes(), 1, userId, null).getResponseCode() == 0) {
                            return true;
                        }
                    }
                } catch (Exception e) {
                }
                try {
                    if (this.mLockPatternUtils.isLockPasswordEnabled(userId)) {
                        if (checkCredential(password.getBytes(), 2, userId, null).getResponseCode() == 0) {
                            return true;
                        }
                    }
                } catch (Exception e2) {
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeUser(int userId, boolean unknownUser) {
        this.mSpManager.removeUser(userId);
        this.mStorage.removeUser(userId);
        this.mStrongAuth.removeUser(userId);
        tryRemoveUserFromSpCacheLater(userId);
        android.security.KeyStore ks = android.security.KeyStore.getInstance();
        ks.onUserRemoved(userId);
        try {
            IGateKeeperService gk = getGateKeeperService();
            if (gk != null) {
                gk.clearSecureUserId(userId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "unable to clear GK secure user id");
        }
        if (unknownUser || this.mUserManager.getUserInfo(userId).isManagedProfile()) {
            removeKeystoreProfileKey(userId);
        }
    }

    private void removeKeystoreProfileKey(int targetUserId) {
        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry("profile_key_name_encrypt_" + targetUserId);
            keyStore.deleteEntry("profile_key_name_decrypt_" + targetUserId);
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
            Slog.e(TAG, "Unable to remove keystore profile key for user:" + targetUserId, e);
        }
    }

    public void registerStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission(-1);
        this.mStrongAuth.registerStrongAuthTracker(tracker);
    }

    public void unregisterStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission(-1);
        this.mStrongAuth.unregisterStrongAuthTracker(tracker);
    }

    public void requireStrongAuth(int strongAuthReason, int userId) {
        checkWritePermission(userId);
        this.mStrongAuth.requireStrongAuth(strongAuthReason, userId);
    }

    public void userPresent(int userId) {
        checkWritePermission(userId);
        this.mStrongAuth.reportUnlock(userId);
    }

    public int getStrongAuthForUser(int userId) {
        checkPasswordReadPermission(userId);
        return this.mStrongAuthTracker.getStrongAuthForUser(userId);
    }

    private boolean isCallerShell() {
        int callingUid = Binder.getCallingUid();
        return callingUid == 2000 || callingUid == 0;
    }

    private void enforceShell() {
        if (!isCallerShell()) {
            throw new SecurityException("Caller must be shell");
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) throws RemoteException {
        enforceShell();
        long origId = Binder.clearCallingIdentity();
        try {
        } catch (Throwable th) {
            th = th;
        }
        try {
            new LockSettingsShellCommand(new LockPatternUtils(this.mContext)).exec(this, in, out, err, args, callback, resultReceiver);
            Binder.restoreCallingIdentity(origId);
        } catch (Throwable th2) {
            th = th2;
            Binder.restoreCallingIdentity(origId);
            throw th;
        }
    }

    public void initRecoveryServiceWithSigFile(String rootCertificateAlias, byte[] recoveryServiceCertFile, byte[] recoveryServiceSigFile) throws RemoteException {
        this.mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(rootCertificateAlias, recoveryServiceCertFile, recoveryServiceSigFile);
    }

    public KeyChainSnapshot getKeyChainSnapshot() throws RemoteException {
        return this.mRecoverableKeyStoreManager.getKeyChainSnapshot();
    }

    public void setSnapshotCreatedPendingIntent(PendingIntent intent) throws RemoteException {
        this.mRecoverableKeyStoreManager.setSnapshotCreatedPendingIntent(intent);
    }

    public void setServerParams(byte[] serverParams) throws RemoteException {
        this.mRecoverableKeyStoreManager.setServerParams(serverParams);
    }

    public void setRecoveryStatus(String alias, int status) throws RemoteException {
        this.mRecoverableKeyStoreManager.setRecoveryStatus(alias, status);
    }

    public Map getRecoveryStatus() throws RemoteException {
        return this.mRecoverableKeyStoreManager.getRecoveryStatus();
    }

    public void setRecoverySecretTypes(int[] secretTypes) throws RemoteException {
        this.mRecoverableKeyStoreManager.setRecoverySecretTypes(secretTypes);
    }

    public int[] getRecoverySecretTypes() throws RemoteException {
        return this.mRecoverableKeyStoreManager.getRecoverySecretTypes();
    }

    public byte[] startRecoverySessionWithCertPath(String sessionId, String rootCertificateAlias, RecoveryCertPath verifierCertPath, byte[] vaultParams, byte[] vaultChallenge, List<KeyChainProtectionParams> secrets) throws RemoteException {
        return this.mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(sessionId, rootCertificateAlias, verifierCertPath, vaultParams, vaultChallenge, secrets);
    }

    public Map<String, String> recoverKeyChainSnapshot(String sessionId, byte[] recoveryKeyBlob, List<WrappedApplicationKey> applicationKeys) throws RemoteException {
        return this.mRecoverableKeyStoreManager.recoverKeyChainSnapshot(sessionId, recoveryKeyBlob, applicationKeys);
    }

    public void closeSession(String sessionId) throws RemoteException {
        this.mRecoverableKeyStoreManager.closeSession(sessionId);
    }

    public void removeKey(String alias) throws RemoteException {
        this.mRecoverableKeyStoreManager.removeKey(alias);
    }

    public String generateKey(String alias) throws RemoteException {
        return this.mRecoverableKeyStoreManager.generateKey(alias);
    }

    public String generateKeyWithMetadata(String alias, byte[] metadata) throws RemoteException {
        return this.mRecoverableKeyStoreManager.generateKeyWithMetadata(alias, metadata);
    }

    public String importKey(String alias, byte[] keyBytes) throws RemoteException {
        return this.mRecoverableKeyStoreManager.importKey(alias, keyBytes);
    }

    public String importKeyWithMetadata(String alias, byte[] keyBytes, byte[] metadata) throws RemoteException {
        return this.mRecoverableKeyStoreManager.importKeyWithMetadata(alias, keyBytes, metadata);
    }

    public String getKey(String alias) throws RemoteException {
        return this.mRecoverableKeyStoreManager.getKey(alias);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class GateKeeperDiedRecipient implements IBinder.DeathRecipient {
        private GateKeeperDiedRecipient() {
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            LockSettingsService.this.mGateKeeperService.asBinder().unlinkToDeath(this, 0);
            LockSettingsService.this.mGateKeeperService = null;
        }
    }

    protected synchronized IGateKeeperService getGateKeeperService() throws RemoteException {
        if (this.mGateKeeperService != null) {
            return this.mGateKeeperService;
        }
        IBinder service = ServiceManager.getService("android.service.gatekeeper.IGateKeeperService");
        if (service != null) {
            service.linkToDeath(new GateKeeperDiedRecipient(), 0);
            this.mGateKeeperService = IGateKeeperService.Stub.asInterface(service);
            return this.mGateKeeperService;
        }
        Slog.e(TAG, "Unable to acquire GateKeeperService");
        return null;
    }

    private void onAuthTokenKnownForUser(int userId, SyntheticPasswordManager.AuthenticationToken auth) {
        Slog.i(TAG, "Caching SP for user " + userId);
        synchronized (this.mSpManager) {
            this.mSpCache.put(userId, auth);
        }
        tryRemoveUserFromSpCacheLater(userId);
        if (this.mInjector.isGsiRunning()) {
            Slog.w(TAG, "AuthSecret disabled in GSI");
        } else if (this.mAuthSecretService != null && this.mUserManager.getUserInfo(userId).isPrimary()) {
            try {
                byte[] rawSecret = auth.deriveVendorAuthSecret();
                ArrayList<Byte> secret = new ArrayList<>(rawSecret.length);
                for (byte b : rawSecret) {
                    secret.add(Byte.valueOf(b));
                }
                this.mAuthSecretService.primaryUserCredential(secret);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to pass primary user secret to AuthSecret HAL", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryRemoveUserFromSpCacheLater(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.locksettings.-$$Lambda$LockSettingsService$TCZYHfmf32xFYZ8VXhgwaf00Ndo
            @Override // java.lang.Runnable
            public final void run() {
                LockSettingsService.this.lambda$tryRemoveUserFromSpCacheLater$3$LockSettingsService(userId);
            }
        });
    }

    public /* synthetic */ void lambda$tryRemoveUserFromSpCacheLater$3$LockSettingsService(int userId) {
        if (!shouldCacheSpForUser(userId)) {
            Slog.i(TAG, "Removing SP from cache for user " + userId);
            synchronized (this.mSpManager) {
                this.mSpCache.remove(userId);
            }
        }
    }

    private boolean shouldCacheSpForUser(int userId) {
        if (Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "user_setup_complete", 0, userId) == 0) {
            return true;
        }
        DevicePolicyManagerInternal dpmi = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class);
        if (dpmi == null) {
            return false;
        }
        return dpmi.canUserHaveUntrustedCredentialReset(userId);
    }

    @GuardedBy({"mSpManager"})
    @VisibleForTesting
    protected SyntheticPasswordManager.AuthenticationToken initializeSyntheticPasswordLocked(byte[] credentialHash, byte[] credential, int credentialType, int requestedQuality, int userId) throws RemoteException {
        Slog.i(TAG, "Initialize SyntheticPassword for user: " + userId);
        SyntheticPasswordManager.AuthenticationToken auth = this.mSpManager.newSyntheticPasswordAndSid(getGateKeeperService(), credentialHash, credential, userId);
        onAuthTokenKnownForUser(userId, auth);
        if (auth == null) {
            Slog.wtf(TAG, "initializeSyntheticPasswordLocked returns null auth token");
            return null;
        }
        long handle = this.mSpManager.createPasswordBasedSyntheticPassword(getGateKeeperService(), credential, credentialType, auth, requestedQuality, userId);
        if (credential != null) {
            if (credentialHash == null) {
                this.mSpManager.newSidForUser(getGateKeeperService(), auth, userId);
            }
            this.mSpManager.verifyChallenge(getGateKeeperService(), auth, 0L, userId);
            setAuthlessUserKeyProtection(userId, auth.deriveDiskEncryptionKey());
            setKeystorePassword(auth.deriveKeyStorePassword(), userId);
        } else {
            clearUserKeyProtection(userId);
            setKeystorePassword(null, userId);
            getGateKeeperService().clearSecureUserId(userId);
        }
        fixateNewestUserKeyAuth(userId);
        setLong("sp-handle", handle, userId);
        return auth;
    }

    private long getSyntheticPasswordHandleLocked(int userId) {
        return getLong("sp-handle", 0L, userId);
    }

    private boolean isSyntheticPasswordBasedCredentialLocked(int userId) {
        if (userId == -9999) {
            int type = this.mStorage.readPersistentDataBlock().type;
            return type == 1 || type == 2;
        }
        long handle = getSyntheticPasswordHandleLocked(userId);
        long enabled = getLong("enable-sp", 1L, 0);
        return (enabled == 0 || handle == 0) ? false : true;
    }

    @VisibleForTesting
    protected boolean shouldMigrateToSyntheticPasswordLocked(int userId) {
        long handle = getSyntheticPasswordHandleLocked(userId);
        long enabled = getLong("enable-sp", 1L, 0);
        return enabled != 0 && handle == 0;
    }

    private void enableSyntheticPasswordLocked() {
        setLong("enable-sp", 1L, 0);
    }

    private VerifyCredentialResponse spBasedDoVerifyCredential(byte[] userCredential, int credentialType, @ChallengeType int challengeType, long challenge, int userId, ICheckCredentialProgressCallback progressCallback, ArrayList<PendingResetLockout> resetLockouts) throws RemoteException {
        long challenge2;
        int challengeType2;
        SyntheticPasswordManager syntheticPasswordManager;
        byte[] userCredential2;
        VerifyCredentialResponse response;
        ArrayList<PendingResetLockout> resetLockouts2;
        boolean hasEnrolledBiometrics = this.mInjector.hasEnrolledBiometrics(userId);
        Slog.d(TAG, "spBasedDoVerifyCredential: user=" + userId + " challengeType=" + challengeType + " hasEnrolledBiometrics=" + hasEnrolledBiometrics);
        byte[] userCredential3 = credentialType == -1 ? null : userCredential;
        PackageManager pm = this.mContext.getPackageManager();
        if (challengeType == 0 && pm.hasSystemFeature("android.hardware.biometrics.face") && hasEnrolledBiometrics) {
            challengeType2 = 2;
            challenge2 = ((FaceManager) this.mContext.getSystemService(FaceManager.class)).generateChallenge();
        } else {
            challenge2 = challenge;
            challengeType2 = challengeType;
        }
        SyntheticPasswordManager syntheticPasswordManager2 = this.mSpManager;
        synchronized (syntheticPasswordManager2) {
            try {
                try {
                    if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                        if (userId == -9999) {
                            try {
                                return this.mSpManager.verifyFrpCredential(getGateKeeperService(), userCredential3, credentialType, progressCallback);
                            } catch (Throwable th) {
                                th = th;
                                syntheticPasswordManager = syntheticPasswordManager2;
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
                        long handle = getSyntheticPasswordHandleLocked(userId);
                        SyntheticPasswordManager.AuthenticationResult authResult = this.mSpManager.unwrapPasswordBasedSyntheticPassword(getGateKeeperService(), handle, userCredential3, userId, progressCallback);
                        if (authResult.credentialType != credentialType) {
                            Slog.e(TAG, "Credential type mismatch.");
                            return VerifyCredentialResponse.ERROR;
                        }
                        VerifyCredentialResponse response2 = authResult.gkResponse;
                        if (response2.getResponseCode() == 0) {
                            try {
                                syntheticPasswordManager = syntheticPasswordManager2;
                                userCredential2 = userCredential3;
                            } catch (Throwable th3) {
                                th = th3;
                                syntheticPasswordManager = syntheticPasswordManager2;
                            }
                            try {
                                VerifyCredentialResponse response3 = this.mSpManager.verifyChallenge(getGateKeeperService(), authResult.authToken, challenge2, userId);
                                if (response3.getResponseCode() != 0) {
                                    Slog.wtf(TAG, "verifyChallenge with SP failed.");
                                    VerifyCredentialResponse verifyCredentialResponse = VerifyCredentialResponse.ERROR;
                                    return verifyCredentialResponse;
                                }
                                response = response3;
                            } catch (Throwable th4) {
                                th = th4;
                                while (true) {
                                    break;
                                    break;
                                }
                                throw th;
                            }
                        } else {
                            syntheticPasswordManager = syntheticPasswordManager2;
                            userCredential2 = userCredential3;
                            response = response2;
                        }
                        try {
                            if (response.getResponseCode() == 0) {
                                notifyActivePasswordMetricsAvailable(credentialType, userCredential2, userId);
                                unlockKeystore(authResult.authToken.deriveKeyStorePassword(), userId);
                                if (hasEnrolledBiometrics) {
                                    ArrayList<PendingResetLockout> resetLockouts3 = resetLockouts == null ? new ArrayList<>() : resetLockouts;
                                    resetLockouts3.add(new PendingResetLockout(userId, response.getPayload()));
                                    resetLockouts2 = resetLockouts3;
                                } else {
                                    resetLockouts2 = resetLockouts;
                                }
                                byte[] secret = authResult.authToken.deriveDiskEncryptionKey();
                                Slog.i(TAG, "Unlocking user " + userId + " with secret only, length " + secret.length);
                                unlockUser(userId, null, secret, challengeType2, challenge2, resetLockouts2);
                                activateEscrowTokens(authResult.authToken, userId);
                                if (isManagedProfileWithSeparatedLock(userId)) {
                                    setDeviceUnlockedForUser(userId);
                                }
                                this.mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);
                                onAuthTokenKnownForUser(userId, authResult.authToken);
                            } else if (response.getResponseCode() == 1 && response.getTimeout() > 0) {
                                requireStrongAuth(8, userId);
                            }
                            return response;
                        } catch (Throwable th5) {
                            th = th5;
                            while (true) {
                                break;
                                break;
                            }
                            throw th;
                        }
                    }
                    return null;
                } catch (Throwable th6) {
                    th = th6;
                }
            } catch (Throwable th7) {
                th = th7;
                syntheticPasswordManager = syntheticPasswordManager2;
            }
        }
    }

    private void setDeviceUnlockedForUser(int userId) {
        TrustManager trustManager = (TrustManager) this.mContext.getSystemService(TrustManager.class);
        trustManager.setDeviceLockedForUser(userId, false);
    }

    @GuardedBy({"mSpManager"})
    private long setLockCredentialWithAuthTokenLocked(byte[] credential, int credentialType, SyntheticPasswordManager.AuthenticationToken auth, int requestedQuality, int userId) throws RemoteException {
        Map<Integer, byte[]> profilePasswords;
        long newHandle = this.mSpManager.createPasswordBasedSyntheticPassword(getGateKeeperService(), credential, credentialType, auth, requestedQuality, userId);
        if (credential != null) {
            profilePasswords = null;
            if (this.mSpManager.hasSidForUser(userId)) {
                this.mSpManager.verifyChallenge(getGateKeeperService(), auth, 0L, userId);
            } else {
                this.mSpManager.newSidForUser(getGateKeeperService(), auth, userId);
                this.mSpManager.verifyChallenge(getGateKeeperService(), auth, 0L, userId);
                setAuthlessUserKeyProtection(userId, auth.deriveDiskEncryptionKey());
                fixateNewestUserKeyAuth(userId);
                setKeystorePassword(auth.deriveKeyStorePassword(), userId);
            }
        } else {
            profilePasswords = getDecryptedPasswordsForAllTiedProfiles(userId);
            this.mSpManager.clearSidForUser(userId);
            getGateKeeperService().clearSecureUserId(userId);
            unlockUserKey(userId, null, auth.deriveDiskEncryptionKey());
            clearUserKeyProtection(userId);
            fixateNewestUserKeyAuth(userId);
            unlockKeystore(auth.deriveKeyStorePassword(), userId);
            setKeystorePassword(null, userId);
        }
        setLong("sp-handle", newHandle, userId);
        synchronizeUnifiedWorkChallengeForProfiles(userId, profilePasswords);
        notifyActivePasswordMetricsAvailable(credentialType, credential, userId);
        if (profilePasswords != null) {
            for (Map.Entry<Integer, byte[]> entry : profilePasswords.entrySet()) {
                Arrays.fill(entry.getValue(), (byte) 0);
            }
        }
        return newHandle;
    }

    /* JADX WARN: Removed duplicated region for block: B:22:0x0063  */
    /* JADX WARN: Removed duplicated region for block: B:23:0x0069  */
    /* JADX WARN: Removed duplicated region for block: B:36:0x00a6  */
    /* JADX WARN: Removed duplicated region for block: B:40:0x00ce  */
    @com.android.internal.annotations.GuardedBy({"mSpManager"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void spBasedSetLockCredentialInternalLocked(byte[] r17, int r18, byte[] r19, int r20, int r21, boolean r22, boolean r23) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 232
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.locksettings.LockSettingsService.spBasedSetLockCredentialInternalLocked(byte[], int, byte[], int, int, boolean, boolean):void");
    }

    public byte[] getHashFactor(byte[] currentCredential, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        currentCredential = (currentCredential == null || currentCredential.length == 0) ? null : null;
        if (isManagedProfileWithUnifiedLock(userId)) {
            try {
                currentCredential = getDecryptedPasswordForTiedProfile(userId);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get work profile credential", e);
                return null;
            }
        }
        synchronized (this.mSpManager) {
            if (!isSyntheticPasswordBasedCredentialLocked(userId)) {
                Slog.w(TAG, "Synthetic password not enabled");
                return null;
            }
            long handle = getSyntheticPasswordHandleLocked(userId);
            SyntheticPasswordManager.AuthenticationResult auth = this.mSpManager.unwrapPasswordBasedSyntheticPassword(getGateKeeperService(), handle, currentCredential, userId, null);
            if (auth.authToken == null) {
                Slog.w(TAG, "Current credential is incorrect");
                return null;
            }
            return auth.authToken.derivePasswordHashFactor();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long addEscrowToken(byte[] token, int userId, LockPatternUtils.EscrowTokenStateChangeCallback callback) throws RemoteException {
        long handle;
        synchronized (this.mSpManager) {
            enableSyntheticPasswordLocked();
            SyntheticPasswordManager.AuthenticationToken auth = null;
            if (!isUserSecure(userId)) {
                if (shouldMigrateToSyntheticPasswordLocked(userId)) {
                    auth = initializeSyntheticPasswordLocked(null, null, -1, 0, userId);
                } else {
                    long pwdHandle = getSyntheticPasswordHandleLocked(userId);
                    auth = this.mSpManager.unwrapPasswordBasedSyntheticPassword(getGateKeeperService(), pwdHandle, null, userId, null).authToken;
                }
            }
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                disableEscrowTokenOnNonManagedDevicesIfNeeded(userId);
                if (!this.mSpManager.hasEscrowData(userId)) {
                    throw new SecurityException("Escrow token is disabled on the current user");
                }
            }
            handle = this.mSpManager.createTokenBasedSyntheticPassword(token, userId, callback);
            if (auth != null) {
                this.mSpManager.activateTokenBasedSyntheticPassword(handle, auth, userId);
            }
        }
        return handle;
    }

    private void activateEscrowTokens(SyntheticPasswordManager.AuthenticationToken auth, int userId) {
        synchronized (this.mSpManager) {
            disableEscrowTokenOnNonManagedDevicesIfNeeded(userId);
            for (Long l : this.mSpManager.getPendingTokensForUser(userId)) {
                long handle = l.longValue();
                Slog.i(TAG, String.format("activateEscrowTokens: %x %d ", Long.valueOf(handle), Integer.valueOf(userId)));
                this.mSpManager.activateTokenBasedSyntheticPassword(handle, auth, userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isEscrowTokenActive(long handle, int userId) {
        boolean existsHandle;
        synchronized (this.mSpManager) {
            existsHandle = this.mSpManager.existsHandle(handle, userId);
        }
        return existsHandle;
    }

    public boolean hasPendingEscrowToken(int userId) {
        boolean z;
        checkPasswordReadPermission(userId);
        synchronized (this.mSpManager) {
            z = !this.mSpManager.getPendingTokensForUser(userId).isEmpty();
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean removeEscrowToken(long handle, int userId) {
        synchronized (this.mSpManager) {
            if (handle == getSyntheticPasswordHandleLocked(userId)) {
                Slog.w(TAG, "Cannot remove password handle");
                return false;
            } else if (this.mSpManager.removePendingToken(handle, userId)) {
                return true;
            } else {
                if (this.mSpManager.existsHandle(handle, userId)) {
                    this.mSpManager.destroyTokenBasedSyntheticPassword(handle, userId);
                    return true;
                }
                return false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean setLockCredentialWithToken(byte[] credential, int type, long tokenHandle, byte[] token, int requestedQuality, final int userId) throws RemoteException {
        boolean result;
        synchronized (this.mSpManager) {
            if (!this.mSpManager.hasEscrowData(userId)) {
                throw new SecurityException("Escrow token is disabled on the current user");
            }
            result = setLockCredentialWithTokenInternalLocked(credential, type, tokenHandle, token, requestedQuality, userId);
        }
        if (result) {
            synchronized (this.mSeparateChallengeLock) {
                setSeparateProfileChallengeEnabledLocked(userId, true, null);
            }
            if (credential == null) {
                this.mHandler.post(new Runnable() { // from class: com.android.server.locksettings.-$$Lambda$LockSettingsService$gO9oFCXC4cllN2vDJyEnqnNPMMw
                    @Override // java.lang.Runnable
                    public final void run() {
                        LockSettingsService.this.lambda$setLockCredentialWithToken$4$LockSettingsService(userId);
                    }
                });
            }
            notifyPasswordChanged(userId);
            notifySeparateProfileChallengeChanged(userId);
        }
        return result;
    }

    public /* synthetic */ void lambda$setLockCredentialWithToken$4$LockSettingsService(int userId) {
        unlockUser(userId, null, null);
    }

    @GuardedBy({"mSpManager"})
    private boolean setLockCredentialWithTokenInternalLocked(byte[] credential, int type, long tokenHandle, byte[] token, int requestedQuality, int userId) throws RemoteException {
        SyntheticPasswordManager.AuthenticationResult result = this.mSpManager.unwrapTokenBasedSyntheticPassword(getGateKeeperService(), tokenHandle, token, userId);
        if (result.authToken == null) {
            Slog.w(TAG, "Invalid escrow token supplied");
            return false;
        } else if (result.gkResponse.getResponseCode() != 0) {
            Slog.e(TAG, "Obsolete token: synthetic password derived but it fails GK verification.");
            return false;
        } else {
            setLong("lockscreen.password_type", requestedQuality, userId);
            long oldHandle = getSyntheticPasswordHandleLocked(userId);
            setLockCredentialWithAuthTokenLocked(credential, type, result.authToken, requestedQuality, userId);
            this.mSpManager.destroyPasswordBasedSyntheticPassword(oldHandle, userId);
            onAuthTokenKnownForUser(userId, result.authToken);
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId) throws RemoteException {
        synchronized (this.mSpManager) {
            if (!this.mSpManager.hasEscrowData(userId)) {
                throw new SecurityException("Escrow token is disabled on the current user");
            }
            SyntheticPasswordManager.AuthenticationResult authResult = this.mSpManager.unwrapTokenBasedSyntheticPassword(getGateKeeperService(), tokenHandle, token, userId);
            if (authResult.authToken == null) {
                Slog.w(TAG, "Invalid escrow token supplied");
                return false;
            }
            unlockUser(userId, null, authResult.authToken.deriveDiskEncryptionKey());
            onAuthTokenKnownForUser(userId, authResult.authToken);
            return true;
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            pw.println("Current lock settings service state:");
            pw.println(String.format("SP Enabled = %b", Boolean.valueOf(this.mLockPatternUtils.isSyntheticPasswordEnabled())));
            List<UserInfo> users = this.mUserManager.getUsers();
            for (int user = 0; user < users.size(); user++) {
                int userId = users.get(user).id;
                pw.println("    User " + userId);
                synchronized (this.mSpManager) {
                    pw.println(String.format("        SP Handle = %x", Long.valueOf(getSyntheticPasswordHandleLocked(userId))));
                }
                try {
                    pw.println(String.format("        SID = %x", Long.valueOf(getGateKeeperService().getSecureUserId(userId))));
                } catch (RemoteException e) {
                }
            }
        }
    }

    private void disableEscrowTokenOnNonManagedDevicesIfNeeded(int userId) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (this.mUserManager.getUserInfo(userId).isManagedProfile()) {
                Slog.i(TAG, "Managed profile can have escrow token");
                return;
            }
            DevicePolicyManager dpm = this.mInjector.getDevicePolicyManager();
            if (dpm.getDeviceOwnerComponentOnAnyUser() != null) {
                Slog.i(TAG, "Corp-owned device can have escrow token");
            } else if (dpm.getProfileOwnerAsUser(userId) != null) {
                Slog.i(TAG, "User with profile owner can have escrow token");
            } else if (!dpm.isDeviceProvisioned()) {
                Slog.i(TAG, "Postpone disabling escrow tokens until device is provisioned");
            } else if (this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
            } else {
                Slog.i(TAG, "Disabling escrow token on user " + userId);
                if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                    this.mSpManager.destroyEscrowData(userId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* loaded from: classes.dex */
    private class DeviceProvisionedObserver extends ContentObserver {
        private final Uri mDeviceProvisionedUri;
        private boolean mRegistered;
        private final Uri mUserSetupCompleteUri;

        public DeviceProvisionedObserver() {
            super(null);
            this.mDeviceProvisionedUri = Settings.Global.getUriFor("device_provisioned");
            this.mUserSetupCompleteUri = Settings.Secure.getUriFor("user_setup_complete");
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (this.mDeviceProvisionedUri.equals(uri)) {
                updateRegistration();
                if (isProvisioned()) {
                    Slog.i(LockSettingsService.TAG, "Reporting device setup complete to IGateKeeperService");
                    reportDeviceSetupComplete();
                    clearFrpCredentialIfOwnerNotSecure();
                }
            } else if (this.mUserSetupCompleteUri.equals(uri)) {
                LockSettingsService.this.tryRemoveUserFromSpCacheLater(userId);
            }
        }

        public void onSystemReady() {
            if (LockPatternUtils.frpCredentialEnabled(LockSettingsService.this.mContext)) {
                updateRegistration();
            } else if (!isProvisioned()) {
                Slog.i(LockSettingsService.TAG, "FRP credential disabled, reporting device setup complete to Gatekeeper immediately");
                reportDeviceSetupComplete();
            }
        }

        private void reportDeviceSetupComplete() {
            try {
                LockSettingsService.this.getGateKeeperService().reportDeviceSetupComplete();
            } catch (RemoteException e) {
                Slog.e(LockSettingsService.TAG, "Failure reporting to IGateKeeperService", e);
            }
        }

        private void clearFrpCredentialIfOwnerNotSecure() {
            List<UserInfo> users = LockSettingsService.this.mUserManager.getUsers();
            for (UserInfo user : users) {
                if (LockPatternUtils.userOwnsFrpCredential(LockSettingsService.this.mContext, user)) {
                    if (!LockSettingsService.this.isUserSecure(user.id)) {
                        LockSettingsService.this.mStorage.writePersistentDataBlock(0, user.id, 0, null);
                        return;
                    }
                    return;
                }
            }
        }

        private void updateRegistration() {
            boolean register = !isProvisioned();
            if (register == this.mRegistered) {
                return;
            }
            if (register) {
                LockSettingsService.this.mContext.getContentResolver().registerContentObserver(this.mDeviceProvisionedUri, false, this);
                LockSettingsService.this.mContext.getContentResolver().registerContentObserver(this.mUserSetupCompleteUri, false, this, -1);
            } else {
                LockSettingsService.this.mContext.getContentResolver().unregisterContentObserver(this);
            }
            this.mRegistered = register;
        }

        private boolean isProvisioned() {
            return Settings.Global.getInt(LockSettingsService.this.mContext.getContentResolver(), "device_provisioned", 0) != 0;
        }
    }

    /* loaded from: classes.dex */
    private final class LocalService extends LockSettingsInternal {
        private LocalService() {
        }

        public long addEscrowToken(byte[] token, int userId, LockPatternUtils.EscrowTokenStateChangeCallback callback) {
            try {
                return LockSettingsService.this.addEscrowToken(token, userId, callback);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        public boolean removeEscrowToken(long handle, int userId) {
            return LockSettingsService.this.removeEscrowToken(handle, userId);
        }

        public boolean isEscrowTokenActive(long handle, int userId) {
            return LockSettingsService.this.isEscrowTokenActive(handle, userId);
        }

        public boolean setLockCredentialWithToken(byte[] credential, int type, long tokenHandle, byte[] token, int requestedQuality, int userId) {
            if (LockSettingsService.this.mLockPatternUtils.hasSecureLockScreen()) {
                try {
                    return LockSettingsService.this.setLockCredentialWithToken(credential, type, tokenHandle, token, requestedQuality, userId);
                } catch (RemoteException re) {
                    throw re.rethrowFromSystemServer();
                }
            }
            throw new UnsupportedOperationException("This operation requires secure lock screen feature.");
        }

        public boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId) {
            try {
                return LockSettingsService.this.unlockUserWithToken(tokenHandle, token, userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }
}
