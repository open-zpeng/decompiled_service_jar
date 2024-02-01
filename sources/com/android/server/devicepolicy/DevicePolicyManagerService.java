package com.android.server.devicepolicy;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.NetworkEvent;
import android.app.admin.PasswordMetrics;
import android.app.admin.SecurityLog;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.app.backup.IBackupManager;
import android.app.backup.ISelectBackupTransportCallback;
import android.app.trust.TrustManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.StringParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.IAudioService;
import android.net.ConnectivityManager;
import android.net.IIpConnectivityMetrics;
import android.net.ProxyInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RecoverySystem;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.StorageManager;
import android.provider.ContactsContract;
import android.provider.ContactsInternal;
import android.provider.Settings;
import android.provider.Telephony;
import android.security.IKeyChainAliasCallback;
import android.security.IKeyChainService;
import android.security.KeyChain;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.IWindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThreadxp;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.telephony.SmsApplication;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.Preconditions;
import com.android.internal.util.StatLogger;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.BatteryService;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.NetworkManagementService;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.UiModeManagerService;
import com.android.server.am.ProcessManagerPolicy;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.devicepolicy.TransferOwnershipMetadataManager;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.UserRestrictionsUtils;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.google.android.collect.Sets;
import com.xiaopeng.server.aftersales.AfterSalesService;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
public class DevicePolicyManagerService extends BaseIDevicePolicyManager {
    private static final String ACTION_EXPIRED_PASSWORD_NOTIFICATION = "com.android.server.ACTION_EXPIRED_PASSWORD_NOTIFICATION";
    private static final String ATTR_ALIAS = "alias";
    private static final String ATTR_APPLICATION_RESTRICTIONS_MANAGER = "application-restrictions-manager";
    private static final String ATTR_DELEGATED_CERT_INSTALLER = "delegated-cert-installer";
    private static final String ATTR_DEVICE_PAIRED = "device-paired";
    private static final String ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED = "device-provisioning-config-applied";
    private static final String ATTR_DISABLED = "disabled";
    private static final String ATTR_ID = "id";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_PERMISSION_POLICY = "permission-policy";
    private static final String ATTR_PERMISSION_PROVIDER = "permission-provider";
    private static final String ATTR_PROVISIONING_STATE = "provisioning-state";
    private static final String ATTR_SETUP_COMPLETE = "setup-complete";
    private static final String ATTR_VALUE = "value";
    private static final int DEVICE_ADMIN_DEACTIVATE_TIMEOUT = 10000;
    private static final String DEVICE_POLICIES_XML = "device_policies.xml";
    private static final String DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML = "do-not-ask-credentials-on-boot";
    private static boolean ENABLE_LOCK_GUARD = false;
    private static final Set<String> GLOBAL_SETTINGS_DEPRECATED;
    private static final Set<String> GLOBAL_SETTINGS_WHITELIST;
    protected static final String LOG_TAG = "DevicePolicyManager";
    private static final String LOG_TAG_DEVICE_OWNER = "device-owner";
    private static final String LOG_TAG_PROFILE_OWNER = "profile-owner";
    private static final long MINIMUM_STRONG_AUTH_TIMEOUT_MS;
    private static final int PROFILE_KEYGUARD_FEATURES = 440;
    private static final int PROFILE_KEYGUARD_FEATURES_PROFILE_ONLY = 8;
    private static final String PROPERTY_DEVICE_OWNER_PRESENT = "ro.device_owner";
    private static final int REQUEST_EXPIRE_PASSWORD = 5571;
    private static final Set<String> SECURE_SETTINGS_DEVICEOWNER_WHITELIST;
    private static final int STATUS_BAR_DISABLE2_MASK = 1;
    private static final int STATUS_BAR_DISABLE_MASK = 34013184;
    private static final Set<String> SYSTEM_SETTINGS_WHITELIST;
    private static final String TAG_ACCEPTED_CA_CERTIFICATES = "accepted-ca-certificate";
    private static final String TAG_ADMIN_BROADCAST_PENDING = "admin-broadcast-pending";
    private static final String TAG_AFFILIATION_ID = "affiliation-id";
    private static final String TAG_CURRENT_INPUT_METHOD_SET = "current-ime-set";
    private static final String TAG_INITIALIZATION_BUNDLE = "initialization-bundle";
    private static final String TAG_LAST_BUG_REPORT_REQUEST = "last-bug-report-request";
    private static final String TAG_LAST_NETWORK_LOG_RETRIEVAL = "last-network-log-retrieval";
    private static final String TAG_LAST_SECURITY_LOG_RETRIEVAL = "last-security-log-retrieval";
    private static final String TAG_LOCK_TASK_COMPONENTS = "lock-task-component";
    private static final String TAG_LOCK_TASK_FEATURES = "lock-task-features";
    private static final String TAG_OWNER_INSTALLED_CA_CERT = "owner-installed-ca-cert";
    private static final String TAG_PASSWORD_TOKEN_HANDLE = "password-token";
    private static final String TAG_PASSWORD_VALIDITY = "password-validity";
    private static final String TAG_STATUS_BAR = "statusbar";
    private static final String TAG_TRANSFER_OWNERSHIP_BUNDLE = "transfer-ownership-bundle";
    private static final String TRANSFER_OWNERSHIP_PARAMETERS_XML = "transfer-ownership-parameters.xml";
    private static final boolean VERBOSE_LOG = false;
    final Handler mBackgroundHandler;
    private final CertificateMonitor mCertificateMonitor;
    private final DevicePolicyConstants mConstants;
    final Context mContext;
    private final DeviceAdminServiceController mDeviceAdminServiceController;
    final Handler mHandler;
    final boolean mHasFeature;
    final IPackageManager mIPackageManager;
    final Injector mInjector;
    final boolean mIsWatch;
    final LocalService mLocalService;
    private final Object mLockDoNoUseDirectly;
    private final LockPatternUtils mLockPatternUtils;
    @GuardedBy("getLockObject()")
    private NetworkLogger mNetworkLogger;
    private final OverlayPackagesProvider mOverlayPackagesProvider;
    @VisibleForTesting
    final Owners mOwners;
    private final Set<Pair<String, Integer>> mPackagesToRemove;
    private final DevicePolicyCacheImpl mPolicyCache;
    final BroadcastReceiver mReceiver;
    private final BroadcastReceiver mRemoteBugreportConsentReceiver;
    private final BroadcastReceiver mRemoteBugreportFinishedReceiver;
    private final AtomicBoolean mRemoteBugreportServiceIsActive;
    private final AtomicBoolean mRemoteBugreportSharingAccepted;
    private final Runnable mRemoteBugreportTimeoutRunnable;
    private final SecurityLogMonitor mSecurityLogMonitor;
    private final SetupContentObserver mSetupContentObserver;
    private final StatLogger mStatLogger;
    final TelephonyManager mTelephonyManager;
    private final Binder mToken;
    @VisibleForTesting
    final TransferOwnershipMetadataManager mTransferOwnershipMetadataManager;
    final UsageStatsManagerInternal mUsageStatsManagerInternal;
    @GuardedBy("getLockObject()")
    final SparseArray<DevicePolicyData> mUserData;
    final UserManager mUserManager;
    final UserManagerInternal mUserManagerInternal;
    @GuardedBy("getLockObject()")
    final SparseArray<PasswordMetrics> mUserPasswordMetrics;
    private static final long MS_PER_DAY = TimeUnit.DAYS.toMillis(1);
    private static final long EXPIRATION_GRACE_PERIOD_MS = 5 * MS_PER_DAY;
    private static final String[] DELEGATIONS = {"delegation-cert-install", "delegation-app-restrictions", "delegation-block-uninstall", "delegation-enable-system-app", "delegation-keep-uninstalled-packages", "delegation-package-access", "delegation-permission-grant", "delegation-install-existing-package", "delegation-keep-uninstalled-packages"};
    private static final Set<String> SECURE_SETTINGS_WHITELIST = new ArraySet();

    /* loaded from: classes.dex */
    interface Stats {
        public static final int COUNT = 1;
        public static final int LOCK_GUARD_GUARD = 0;
    }

    static {
        SECURE_SETTINGS_WHITELIST.add("default_input_method");
        SECURE_SETTINGS_WHITELIST.add("skip_first_use_hints");
        SECURE_SETTINGS_WHITELIST.add("install_non_market_apps");
        SECURE_SETTINGS_DEVICEOWNER_WHITELIST = new ArraySet();
        SECURE_SETTINGS_DEVICEOWNER_WHITELIST.addAll(SECURE_SETTINGS_WHITELIST);
        SECURE_SETTINGS_DEVICEOWNER_WHITELIST.add("location_mode");
        GLOBAL_SETTINGS_WHITELIST = new ArraySet();
        GLOBAL_SETTINGS_WHITELIST.add("adb_enabled");
        GLOBAL_SETTINGS_WHITELIST.add("auto_time");
        GLOBAL_SETTINGS_WHITELIST.add("auto_time_zone");
        GLOBAL_SETTINGS_WHITELIST.add("data_roaming");
        GLOBAL_SETTINGS_WHITELIST.add("usb_mass_storage_enabled");
        GLOBAL_SETTINGS_WHITELIST.add("wifi_sleep_policy");
        GLOBAL_SETTINGS_WHITELIST.add("stay_on_while_plugged_in");
        GLOBAL_SETTINGS_WHITELIST.add("wifi_device_owner_configs_lockdown");
        GLOBAL_SETTINGS_DEPRECATED = new ArraySet();
        GLOBAL_SETTINGS_DEPRECATED.add("bluetooth_on");
        GLOBAL_SETTINGS_DEPRECATED.add("development_settings_enabled");
        GLOBAL_SETTINGS_DEPRECATED.add("mode_ringer");
        GLOBAL_SETTINGS_DEPRECATED.add("network_preference");
        GLOBAL_SETTINGS_DEPRECATED.add("wifi_on");
        SYSTEM_SETTINGS_WHITELIST = new ArraySet();
        SYSTEM_SETTINGS_WHITELIST.add("screen_brightness");
        SYSTEM_SETTINGS_WHITELIST.add("screen_brightness_mode");
        SYSTEM_SETTINGS_WHITELIST.add("screen_off_timeout");
        MINIMUM_STRONG_AUTH_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1L);
        boolean z = true;
        if (!Build.IS_ENG && SystemProperties.getInt("debug.dpm.lock_guard", 0) != 1) {
            z = false;
        }
        ENABLE_LOCK_GUARD = z;
    }

    final Object getLockObject() {
        if (ENABLE_LOCK_GUARD) {
            long start = this.mStatLogger.getTime();
            LockGuard.guard(7);
            this.mStatLogger.logDurationStat(0, start);
        }
        return this.mLockDoNoUseDirectly;
    }

    final void ensureLocked() {
        if (Thread.holdsLock(this.mLockDoNoUseDirectly)) {
            return;
        }
        Slog.wtfStack(LOG_TAG, "Not holding DPMS lock.");
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        private BaseIDevicePolicyManager mService;

        public Lifecycle(Context context) {
            super(context);
            String dpmsClassName = context.getResources().getString(17039666);
            dpmsClassName = TextUtils.isEmpty(dpmsClassName) ? DevicePolicyManagerService.class.getName() : dpmsClassName;
            try {
                Class serviceClass = Class.forName(dpmsClassName);
                Constructor constructor = serviceClass.getConstructor(Context.class);
                this.mService = (BaseIDevicePolicyManager) constructor.newInstance(context);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to instantiate DevicePolicyManagerService with class name: " + dpmsClassName, e);
            }
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService("device_policy", this.mService);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            this.mService.systemReady(phase);
        }

        @Override // com.android.server.SystemService
        public void onStartUser(int userHandle) {
            this.mService.handleStartUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            this.mService.handleUnlockUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userHandle) {
            this.mService.handleStopUser(userHandle);
        }
    }

    /* loaded from: classes.dex */
    public static class DevicePolicyData {
        int mPermissionPolicy;
        ComponentName mRestrictionsProvider;
        int mUserHandle;
        int mUserProvisioningState;
        int mFailedPasswordAttempts = 0;
        boolean mPasswordValidAtLastCheckpoint = true;
        int mPasswordOwner = -1;
        long mLastMaximumTimeToLock = -1;
        boolean mUserSetupComplete = false;
        boolean mPaired = false;
        boolean mDeviceProvisioningConfigApplied = false;
        final ArrayMap<ComponentName, ActiveAdmin> mAdminMap = new ArrayMap<>();
        final ArrayList<ActiveAdmin> mAdminList = new ArrayList<>();
        final ArrayList<ComponentName> mRemovingAdmins = new ArrayList<>();
        final ArraySet<String> mAcceptedCaCertificates = new ArraySet<>();
        List<String> mLockTaskPackages = new ArrayList();
        int mLockTaskFeatures = 16;
        boolean mStatusBarDisabled = false;
        final ArrayMap<String, List<String>> mDelegationMap = new ArrayMap<>();
        boolean doNotAskCredentialsOnBoot = false;
        Set<String> mAffiliationIds = new ArraySet();
        long mLastSecurityLogRetrievalTime = -1;
        long mLastBugReportRequestTime = -1;
        long mLastNetworkLogsRetrievalTime = -1;
        boolean mCurrentInputMethodSet = false;
        Set<String> mOwnerInstalledCaCerts = new ArraySet();
        boolean mAdminBroadcastPending = false;
        PersistableBundle mInitBundle = null;
        long mPasswordTokenHandle = 0;

        public DevicePolicyData(int userHandle) {
            this.mUserHandle = userHandle;
        }
    }

    /* loaded from: classes.dex */
    protected static class RestrictionsListener implements UserManagerInternal.UserRestrictionsListener {
        private Context mContext;

        public RestrictionsListener(Context context) {
            this.mContext = context;
        }

        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions, Bundle prevRestrictions) {
            boolean newlyDisallowed = newRestrictions.getBoolean("no_sharing_into_profile");
            boolean previouslyDisallowed = prevRestrictions.getBoolean("no_sharing_into_profile");
            boolean restrictionChanged = newlyDisallowed != previouslyDisallowed;
            if (restrictionChanged) {
                Intent intent = new Intent("android.app.action.DATA_SHARING_RESTRICTION_CHANGED");
                intent.setPackage(DevicePolicyManagerService.getManagedProvisioningPackage(this.mContext));
                intent.putExtra("android.intent.extra.USER_ID", userId);
                intent.addFlags(268435456);
                this.mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class ActiveAdmin {
        private static final String ATTR_LAST_NETWORK_LOGGING_NOTIFICATION = "last-notification";
        private static final String ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS = "num-notifications";
        private static final String ATTR_VALUE = "value";
        static final int DEF_KEYGUARD_FEATURES_DISABLED = 0;
        static final int DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE = 0;
        static final int DEF_MAXIMUM_NETWORK_LOGGING_NOTIFICATIONS_SHOWN = 2;
        static final long DEF_MAXIMUM_TIME_TO_UNLOCK = 0;
        static final int DEF_MINIMUM_PASSWORD_LENGTH = 0;
        static final int DEF_MINIMUM_PASSWORD_LETTERS = 1;
        static final int DEF_MINIMUM_PASSWORD_LOWER_CASE = 0;
        static final int DEF_MINIMUM_PASSWORD_NON_LETTER = 0;
        static final int DEF_MINIMUM_PASSWORD_NUMERIC = 1;
        static final int DEF_MINIMUM_PASSWORD_SYMBOLS = 1;
        static final int DEF_MINIMUM_PASSWORD_UPPER_CASE = 0;
        static final int DEF_ORGANIZATION_COLOR = Color.parseColor("#00796B");
        static final long DEF_PASSWORD_EXPIRATION_DATE = 0;
        static final long DEF_PASSWORD_EXPIRATION_TIMEOUT = 0;
        static final int DEF_PASSWORD_HISTORY_LENGTH = 0;
        private static final String TAG_ACCOUNT_TYPE = "account-type";
        private static final String TAG_CROSS_PROFILE_WIDGET_PROVIDERS = "cross-profile-widget-providers";
        private static final String TAG_DEFAULT_ENABLED_USER_RESTRICTIONS = "default-enabled-user-restrictions";
        private static final String TAG_DISABLE_ACCOUNT_MANAGEMENT = "disable-account-management";
        private static final String TAG_DISABLE_BLUETOOTH_CONTACT_SHARING = "disable-bt-contacts-sharing";
        private static final String TAG_DISABLE_CALLER_ID = "disable-caller-id";
        private static final String TAG_DISABLE_CAMERA = "disable-camera";
        private static final String TAG_DISABLE_CONTACTS_SEARCH = "disable-contacts-search";
        private static final String TAG_DISABLE_KEYGUARD_FEATURES = "disable-keyguard-features";
        private static final String TAG_DISABLE_SCREEN_CAPTURE = "disable-screen-capture";
        private static final String TAG_ENCRYPTION_REQUESTED = "encryption-requested";
        private static final String TAG_END_USER_SESSION_MESSAGE = "end_user_session_message";
        private static final String TAG_FORCE_EPHEMERAL_USERS = "force_ephemeral_users";
        private static final String TAG_GLOBAL_PROXY_EXCLUSION_LIST = "global-proxy-exclusion-list";
        private static final String TAG_GLOBAL_PROXY_SPEC = "global-proxy-spec";
        private static final String TAG_IS_LOGOUT_ENABLED = "is_logout_enabled";
        private static final String TAG_IS_NETWORK_LOGGING_ENABLED = "is_network_logging_enabled";
        private static final String TAG_KEEP_UNINSTALLED_PACKAGES = "keep-uninstalled-packages";
        private static final String TAG_LONG_SUPPORT_MESSAGE = "long-support-message";
        private static final String TAG_MANAGE_TRUST_AGENT_FEATURES = "manage-trust-agent-features";
        private static final String TAG_MANDATORY_BACKUP_TRANSPORT = "mandatory_backup_transport";
        private static final String TAG_MAX_FAILED_PASSWORD_WIPE = "max-failed-password-wipe";
        private static final String TAG_MAX_TIME_TO_UNLOCK = "max-time-to-unlock";
        private static final String TAG_METERED_DATA_DISABLED_PACKAGES = "metered_data_disabled_packages";
        private static final String TAG_MIN_PASSWORD_LENGTH = "min-password-length";
        private static final String TAG_MIN_PASSWORD_LETTERS = "min-password-letters";
        private static final String TAG_MIN_PASSWORD_LOWERCASE = "min-password-lowercase";
        private static final String TAG_MIN_PASSWORD_NONLETTER = "min-password-nonletter";
        private static final String TAG_MIN_PASSWORD_NUMERIC = "min-password-numeric";
        private static final String TAG_MIN_PASSWORD_SYMBOLS = "min-password-symbols";
        private static final String TAG_MIN_PASSWORD_UPPERCASE = "min-password-uppercase";
        private static final String TAG_ORGANIZATION_COLOR = "organization-color";
        private static final String TAG_ORGANIZATION_NAME = "organization-name";
        private static final String TAG_PACKAGE_LIST_ITEM = "item";
        private static final String TAG_PARENT_ADMIN = "parent-admin";
        private static final String TAG_PASSWORD_EXPIRATION_DATE = "password-expiration-date";
        private static final String TAG_PASSWORD_EXPIRATION_TIMEOUT = "password-expiration-timeout";
        private static final String TAG_PASSWORD_HISTORY_LENGTH = "password-history-length";
        private static final String TAG_PASSWORD_QUALITY = "password-quality";
        private static final String TAG_PERMITTED_ACCESSIBILITY_SERVICES = "permitted-accessiblity-services";
        private static final String TAG_PERMITTED_IMES = "permitted-imes";
        private static final String TAG_PERMITTED_NOTIFICATION_LISTENERS = "permitted-notification-listeners";
        private static final String TAG_POLICIES = "policies";
        private static final String TAG_PROVIDER = "provider";
        private static final String TAG_REQUIRE_AUTO_TIME = "require_auto_time";
        private static final String TAG_RESTRICTION = "restriction";
        private static final String TAG_SHORT_SUPPORT_MESSAGE = "short-support-message";
        private static final String TAG_SPECIFIES_GLOBAL_PROXY = "specifies-global-proxy";
        private static final String TAG_START_USER_SESSION_MESSAGE = "start_user_session_message";
        private static final String TAG_STRONG_AUTH_UNLOCK_TIMEOUT = "strong-auth-unlock-timeout";
        private static final String TAG_TEST_ONLY_ADMIN = "test-only-admin";
        private static final String TAG_TRUST_AGENT_COMPONENT = "component";
        private static final String TAG_TRUST_AGENT_COMPONENT_OPTIONS = "trust-agent-component-options";
        private static final String TAG_USER_RESTRICTIONS = "user-restrictions";
        List<String> crossProfileWidgetProviders;
        DeviceAdminInfo info;
        final boolean isParent;
        List<String> keepUninstalledPackages;
        List<String> meteredDisabledPackages;
        ActiveAdmin parentAdmin;
        List<String> permittedAccessiblityServices;
        List<String> permittedInputMethods;
        List<String> permittedNotificationListeners;
        Bundle userRestrictions;
        int passwordHistoryLength = 0;
        PasswordMetrics minimumPasswordMetrics = new PasswordMetrics(0, 0, 1, 0, 0, 1, 1, 0);
        long maximumTimeToUnlock = 0;
        long strongAuthUnlockTimeout = 0;
        int maximumFailedPasswordsForWipe = 0;
        long passwordExpirationTimeout = 0;
        long passwordExpirationDate = 0;
        int disabledKeyguardFeatures = 0;
        boolean encryptionRequested = false;
        boolean testOnlyAdmin = false;
        boolean disableCamera = false;
        boolean disableCallerId = false;
        boolean disableContactsSearch = false;
        boolean disableBluetoothContactSharing = true;
        boolean disableScreenCapture = false;
        boolean requireAutoTime = false;
        boolean forceEphemeralUsers = false;
        boolean isNetworkLoggingEnabled = false;
        boolean isLogoutEnabled = false;
        int numNetworkLoggingNotifications = 0;
        long lastNetworkLoggingNotificationTimeMs = 0;
        final Set<String> accountTypesWithManagementDisabled = new ArraySet();
        boolean specifiesGlobalProxy = false;
        String globalProxySpec = null;
        String globalProxyExclusionList = null;
        ArrayMap<String, TrustAgentInfo> trustAgentInfos = new ArrayMap<>();
        final Set<String> defaultEnabledRestrictionsAlreadySet = new ArraySet();
        CharSequence shortSupportMessage = null;
        CharSequence longSupportMessage = null;
        int organizationColor = DEF_ORGANIZATION_COLOR;
        String organizationName = null;
        ComponentName mandatoryBackupTransport = null;
        String startUserSessionMessage = null;
        String endUserSessionMessage = null;

        /* JADX INFO: Access modifiers changed from: package-private */
        /* loaded from: classes.dex */
        public static class TrustAgentInfo {
            public PersistableBundle options;

            TrustAgentInfo(PersistableBundle bundle) {
                this.options = bundle;
            }
        }

        ActiveAdmin(DeviceAdminInfo _info, boolean parent) {
            this.info = _info;
            this.isParent = parent;
        }

        ActiveAdmin getParentActiveAdmin() {
            Preconditions.checkState(!this.isParent);
            if (this.parentAdmin == null) {
                this.parentAdmin = new ActiveAdmin(this.info, true);
            }
            return this.parentAdmin;
        }

        boolean hasParentActiveAdmin() {
            return this.parentAdmin != null;
        }

        int getUid() {
            return this.info.getActivityInfo().applicationInfo.uid;
        }

        public UserHandle getUserHandle() {
            return UserHandle.of(UserHandle.getUserId(this.info.getActivityInfo().applicationInfo.uid));
        }

        void writeToXml(XmlSerializer out) throws IllegalArgumentException, IllegalStateException, IOException {
            out.startTag(null, "policies");
            this.info.writePoliciesToXml(out);
            out.endTag(null, "policies");
            if (this.minimumPasswordMetrics.quality != 0) {
                out.startTag(null, TAG_PASSWORD_QUALITY);
                out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.quality));
                out.endTag(null, TAG_PASSWORD_QUALITY);
                if (this.minimumPasswordMetrics.length != 0) {
                    out.startTag(null, TAG_MIN_PASSWORD_LENGTH);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.length));
                    out.endTag(null, TAG_MIN_PASSWORD_LENGTH);
                }
                if (this.passwordHistoryLength != 0) {
                    out.startTag(null, TAG_PASSWORD_HISTORY_LENGTH);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.passwordHistoryLength));
                    out.endTag(null, TAG_PASSWORD_HISTORY_LENGTH);
                }
                if (this.minimumPasswordMetrics.upperCase != 0) {
                    out.startTag(null, TAG_MIN_PASSWORD_UPPERCASE);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.upperCase));
                    out.endTag(null, TAG_MIN_PASSWORD_UPPERCASE);
                }
                if (this.minimumPasswordMetrics.lowerCase != 0) {
                    out.startTag(null, TAG_MIN_PASSWORD_LOWERCASE);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.lowerCase));
                    out.endTag(null, TAG_MIN_PASSWORD_LOWERCASE);
                }
                if (this.minimumPasswordMetrics.letters != 1) {
                    out.startTag(null, TAG_MIN_PASSWORD_LETTERS);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.letters));
                    out.endTag(null, TAG_MIN_PASSWORD_LETTERS);
                }
                if (this.minimumPasswordMetrics.numeric != 1) {
                    out.startTag(null, TAG_MIN_PASSWORD_NUMERIC);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.numeric));
                    out.endTag(null, TAG_MIN_PASSWORD_NUMERIC);
                }
                if (this.minimumPasswordMetrics.symbols != 1) {
                    out.startTag(null, TAG_MIN_PASSWORD_SYMBOLS);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.symbols));
                    out.endTag(null, TAG_MIN_PASSWORD_SYMBOLS);
                }
                if (this.minimumPasswordMetrics.nonLetter > 0) {
                    out.startTag(null, TAG_MIN_PASSWORD_NONLETTER);
                    out.attribute(null, ATTR_VALUE, Integer.toString(this.minimumPasswordMetrics.nonLetter));
                    out.endTag(null, TAG_MIN_PASSWORD_NONLETTER);
                }
            }
            if (this.maximumTimeToUnlock != 0) {
                out.startTag(null, TAG_MAX_TIME_TO_UNLOCK);
                out.attribute(null, ATTR_VALUE, Long.toString(this.maximumTimeToUnlock));
                out.endTag(null, TAG_MAX_TIME_TO_UNLOCK);
            }
            if (this.strongAuthUnlockTimeout != 259200000) {
                out.startTag(null, TAG_STRONG_AUTH_UNLOCK_TIMEOUT);
                out.attribute(null, ATTR_VALUE, Long.toString(this.strongAuthUnlockTimeout));
                out.endTag(null, TAG_STRONG_AUTH_UNLOCK_TIMEOUT);
            }
            if (this.maximumFailedPasswordsForWipe != 0) {
                out.startTag(null, TAG_MAX_FAILED_PASSWORD_WIPE);
                out.attribute(null, ATTR_VALUE, Integer.toString(this.maximumFailedPasswordsForWipe));
                out.endTag(null, TAG_MAX_FAILED_PASSWORD_WIPE);
            }
            if (this.specifiesGlobalProxy) {
                out.startTag(null, TAG_SPECIFIES_GLOBAL_PROXY);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.specifiesGlobalProxy));
                out.endTag(null, TAG_SPECIFIES_GLOBAL_PROXY);
                if (this.globalProxySpec != null) {
                    out.startTag(null, TAG_GLOBAL_PROXY_SPEC);
                    out.attribute(null, ATTR_VALUE, this.globalProxySpec);
                    out.endTag(null, TAG_GLOBAL_PROXY_SPEC);
                }
                if (this.globalProxyExclusionList != null) {
                    out.startTag(null, TAG_GLOBAL_PROXY_EXCLUSION_LIST);
                    out.attribute(null, ATTR_VALUE, this.globalProxyExclusionList);
                    out.endTag(null, TAG_GLOBAL_PROXY_EXCLUSION_LIST);
                }
            }
            if (this.passwordExpirationTimeout != 0) {
                out.startTag(null, TAG_PASSWORD_EXPIRATION_TIMEOUT);
                out.attribute(null, ATTR_VALUE, Long.toString(this.passwordExpirationTimeout));
                out.endTag(null, TAG_PASSWORD_EXPIRATION_TIMEOUT);
            }
            if (this.passwordExpirationDate != 0) {
                out.startTag(null, TAG_PASSWORD_EXPIRATION_DATE);
                out.attribute(null, ATTR_VALUE, Long.toString(this.passwordExpirationDate));
                out.endTag(null, TAG_PASSWORD_EXPIRATION_DATE);
            }
            if (this.encryptionRequested) {
                out.startTag(null, TAG_ENCRYPTION_REQUESTED);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.encryptionRequested));
                out.endTag(null, TAG_ENCRYPTION_REQUESTED);
            }
            if (this.testOnlyAdmin) {
                out.startTag(null, TAG_TEST_ONLY_ADMIN);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.testOnlyAdmin));
                out.endTag(null, TAG_TEST_ONLY_ADMIN);
            }
            if (this.disableCamera) {
                out.startTag(null, TAG_DISABLE_CAMERA);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.disableCamera));
                out.endTag(null, TAG_DISABLE_CAMERA);
            }
            if (this.disableCallerId) {
                out.startTag(null, TAG_DISABLE_CALLER_ID);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.disableCallerId));
                out.endTag(null, TAG_DISABLE_CALLER_ID);
            }
            if (this.disableContactsSearch) {
                out.startTag(null, TAG_DISABLE_CONTACTS_SEARCH);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.disableContactsSearch));
                out.endTag(null, TAG_DISABLE_CONTACTS_SEARCH);
            }
            if (!this.disableBluetoothContactSharing) {
                out.startTag(null, TAG_DISABLE_BLUETOOTH_CONTACT_SHARING);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.disableBluetoothContactSharing));
                out.endTag(null, TAG_DISABLE_BLUETOOTH_CONTACT_SHARING);
            }
            if (this.disableScreenCapture) {
                out.startTag(null, TAG_DISABLE_SCREEN_CAPTURE);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.disableScreenCapture));
                out.endTag(null, TAG_DISABLE_SCREEN_CAPTURE);
            }
            if (this.requireAutoTime) {
                out.startTag(null, TAG_REQUIRE_AUTO_TIME);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.requireAutoTime));
                out.endTag(null, TAG_REQUIRE_AUTO_TIME);
            }
            if (this.forceEphemeralUsers) {
                out.startTag(null, TAG_FORCE_EPHEMERAL_USERS);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.forceEphemeralUsers));
                out.endTag(null, TAG_FORCE_EPHEMERAL_USERS);
            }
            if (this.isNetworkLoggingEnabled) {
                out.startTag(null, TAG_IS_NETWORK_LOGGING_ENABLED);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.isNetworkLoggingEnabled));
                out.attribute(null, ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS, Integer.toString(this.numNetworkLoggingNotifications));
                out.attribute(null, ATTR_LAST_NETWORK_LOGGING_NOTIFICATION, Long.toString(this.lastNetworkLoggingNotificationTimeMs));
                out.endTag(null, TAG_IS_NETWORK_LOGGING_ENABLED);
            }
            if (this.disabledKeyguardFeatures != 0) {
                out.startTag(null, TAG_DISABLE_KEYGUARD_FEATURES);
                out.attribute(null, ATTR_VALUE, Integer.toString(this.disabledKeyguardFeatures));
                out.endTag(null, TAG_DISABLE_KEYGUARD_FEATURES);
            }
            if (!this.accountTypesWithManagementDisabled.isEmpty()) {
                out.startTag(null, TAG_DISABLE_ACCOUNT_MANAGEMENT);
                writeAttributeValuesToXml(out, TAG_ACCOUNT_TYPE, this.accountTypesWithManagementDisabled);
                out.endTag(null, TAG_DISABLE_ACCOUNT_MANAGEMENT);
            }
            if (!this.trustAgentInfos.isEmpty()) {
                Set<Map.Entry<String, TrustAgentInfo>> set = this.trustAgentInfos.entrySet();
                out.startTag(null, TAG_MANAGE_TRUST_AGENT_FEATURES);
                for (Map.Entry<String, TrustAgentInfo> entry : set) {
                    TrustAgentInfo trustAgentInfo = entry.getValue();
                    out.startTag(null, TAG_TRUST_AGENT_COMPONENT);
                    out.attribute(null, ATTR_VALUE, entry.getKey());
                    if (trustAgentInfo.options != null) {
                        out.startTag(null, TAG_TRUST_AGENT_COMPONENT_OPTIONS);
                        try {
                            trustAgentInfo.options.saveToXml(out);
                        } catch (XmlPullParserException e) {
                            Log.e(DevicePolicyManagerService.LOG_TAG, "Failed to save TrustAgent options", e);
                        }
                        out.endTag(null, TAG_TRUST_AGENT_COMPONENT_OPTIONS);
                    }
                    out.endTag(null, TAG_TRUST_AGENT_COMPONENT);
                }
                out.endTag(null, TAG_MANAGE_TRUST_AGENT_FEATURES);
            }
            if (this.crossProfileWidgetProviders != null && !this.crossProfileWidgetProviders.isEmpty()) {
                out.startTag(null, TAG_CROSS_PROFILE_WIDGET_PROVIDERS);
                writeAttributeValuesToXml(out, TAG_PROVIDER, this.crossProfileWidgetProviders);
                out.endTag(null, TAG_CROSS_PROFILE_WIDGET_PROVIDERS);
            }
            writePackageListToXml(out, TAG_PERMITTED_ACCESSIBILITY_SERVICES, this.permittedAccessiblityServices);
            writePackageListToXml(out, TAG_PERMITTED_IMES, this.permittedInputMethods);
            writePackageListToXml(out, TAG_PERMITTED_NOTIFICATION_LISTENERS, this.permittedNotificationListeners);
            writePackageListToXml(out, TAG_KEEP_UNINSTALLED_PACKAGES, this.keepUninstalledPackages);
            writePackageListToXml(out, TAG_METERED_DATA_DISABLED_PACKAGES, this.meteredDisabledPackages);
            if (hasUserRestrictions()) {
                UserRestrictionsUtils.writeRestrictions(out, this.userRestrictions, TAG_USER_RESTRICTIONS);
            }
            if (!this.defaultEnabledRestrictionsAlreadySet.isEmpty()) {
                out.startTag(null, TAG_DEFAULT_ENABLED_USER_RESTRICTIONS);
                writeAttributeValuesToXml(out, TAG_RESTRICTION, this.defaultEnabledRestrictionsAlreadySet);
                out.endTag(null, TAG_DEFAULT_ENABLED_USER_RESTRICTIONS);
            }
            if (!TextUtils.isEmpty(this.shortSupportMessage)) {
                out.startTag(null, TAG_SHORT_SUPPORT_MESSAGE);
                out.text(this.shortSupportMessage.toString());
                out.endTag(null, TAG_SHORT_SUPPORT_MESSAGE);
            }
            if (!TextUtils.isEmpty(this.longSupportMessage)) {
                out.startTag(null, TAG_LONG_SUPPORT_MESSAGE);
                out.text(this.longSupportMessage.toString());
                out.endTag(null, TAG_LONG_SUPPORT_MESSAGE);
            }
            if (this.parentAdmin != null) {
                out.startTag(null, TAG_PARENT_ADMIN);
                this.parentAdmin.writeToXml(out);
                out.endTag(null, TAG_PARENT_ADMIN);
            }
            if (this.organizationColor != DEF_ORGANIZATION_COLOR) {
                out.startTag(null, TAG_ORGANIZATION_COLOR);
                out.attribute(null, ATTR_VALUE, Integer.toString(this.organizationColor));
                out.endTag(null, TAG_ORGANIZATION_COLOR);
            }
            if (this.organizationName != null) {
                out.startTag(null, TAG_ORGANIZATION_NAME);
                out.text(this.organizationName);
                out.endTag(null, TAG_ORGANIZATION_NAME);
            }
            if (this.isLogoutEnabled) {
                out.startTag(null, TAG_IS_LOGOUT_ENABLED);
                out.attribute(null, ATTR_VALUE, Boolean.toString(this.isLogoutEnabled));
                out.endTag(null, TAG_IS_LOGOUT_ENABLED);
            }
            if (this.mandatoryBackupTransport != null) {
                out.startTag(null, TAG_MANDATORY_BACKUP_TRANSPORT);
                out.attribute(null, ATTR_VALUE, this.mandatoryBackupTransport.flattenToString());
                out.endTag(null, TAG_MANDATORY_BACKUP_TRANSPORT);
            }
            if (this.startUserSessionMessage != null) {
                out.startTag(null, TAG_START_USER_SESSION_MESSAGE);
                out.text(this.startUserSessionMessage);
                out.endTag(null, TAG_START_USER_SESSION_MESSAGE);
            }
            if (this.endUserSessionMessage != null) {
                out.startTag(null, TAG_END_USER_SESSION_MESSAGE);
                out.text(this.endUserSessionMessage);
                out.endTag(null, TAG_END_USER_SESSION_MESSAGE);
            }
        }

        void writePackageListToXml(XmlSerializer out, String outerTag, List<String> packageList) throws IllegalArgumentException, IllegalStateException, IOException {
            if (packageList == null) {
                return;
            }
            out.startTag(null, outerTag);
            writeAttributeValuesToXml(out, "item", packageList);
            out.endTag(null, outerTag);
        }

        void writeAttributeValuesToXml(XmlSerializer out, String tag, Collection<String> values) throws IOException {
            for (String value : values) {
                out.startTag(null, tag);
                out.attribute(null, ATTR_VALUE, value);
                out.endTag(null, tag);
            }
        }

        void readFromXml(XmlPullParser parser) throws XmlPullParserException, IOException {
            int outerDepth = parser.getDepth();
            while (true) {
                int type = parser.next();
                if (type != 1) {
                    if (type != 3 || parser.getDepth() > outerDepth) {
                        if (type != 3 && type != 4) {
                            String tag = parser.getName();
                            if ("policies".equals(tag)) {
                                this.info.readPoliciesFromXml(parser);
                            } else if (TAG_PASSWORD_QUALITY.equals(tag)) {
                                this.minimumPasswordMetrics.quality = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MIN_PASSWORD_LENGTH.equals(tag)) {
                                this.minimumPasswordMetrics.length = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_PASSWORD_HISTORY_LENGTH.equals(tag)) {
                                this.passwordHistoryLength = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MIN_PASSWORD_UPPERCASE.equals(tag)) {
                                this.minimumPasswordMetrics.upperCase = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MIN_PASSWORD_LOWERCASE.equals(tag)) {
                                this.minimumPasswordMetrics.lowerCase = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MIN_PASSWORD_LETTERS.equals(tag)) {
                                this.minimumPasswordMetrics.letters = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MIN_PASSWORD_NUMERIC.equals(tag)) {
                                this.minimumPasswordMetrics.numeric = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MIN_PASSWORD_SYMBOLS.equals(tag)) {
                                this.minimumPasswordMetrics.symbols = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MIN_PASSWORD_NONLETTER.equals(tag)) {
                                this.minimumPasswordMetrics.nonLetter = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MAX_TIME_TO_UNLOCK.equals(tag)) {
                                this.maximumTimeToUnlock = Long.parseLong(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_STRONG_AUTH_UNLOCK_TIMEOUT.equals(tag)) {
                                this.strongAuthUnlockTimeout = Long.parseLong(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MAX_FAILED_PASSWORD_WIPE.equals(tag)) {
                                this.maximumFailedPasswordsForWipe = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_SPECIFIES_GLOBAL_PROXY.equals(tag)) {
                                this.specifiesGlobalProxy = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_GLOBAL_PROXY_SPEC.equals(tag)) {
                                this.globalProxySpec = parser.getAttributeValue(null, ATTR_VALUE);
                            } else if (TAG_GLOBAL_PROXY_EXCLUSION_LIST.equals(tag)) {
                                this.globalProxyExclusionList = parser.getAttributeValue(null, ATTR_VALUE);
                            } else if (TAG_PASSWORD_EXPIRATION_TIMEOUT.equals(tag)) {
                                this.passwordExpirationTimeout = Long.parseLong(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_PASSWORD_EXPIRATION_DATE.equals(tag)) {
                                this.passwordExpirationDate = Long.parseLong(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_ENCRYPTION_REQUESTED.equals(tag)) {
                                this.encryptionRequested = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_TEST_ONLY_ADMIN.equals(tag)) {
                                this.testOnlyAdmin = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_DISABLE_CAMERA.equals(tag)) {
                                this.disableCamera = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_DISABLE_CALLER_ID.equals(tag)) {
                                this.disableCallerId = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_DISABLE_CONTACTS_SEARCH.equals(tag)) {
                                this.disableContactsSearch = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_DISABLE_BLUETOOTH_CONTACT_SHARING.equals(tag)) {
                                this.disableBluetoothContactSharing = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_DISABLE_SCREEN_CAPTURE.equals(tag)) {
                                this.disableScreenCapture = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_REQUIRE_AUTO_TIME.equals(tag)) {
                                this.requireAutoTime = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_FORCE_EPHEMERAL_USERS.equals(tag)) {
                                this.forceEphemeralUsers = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_IS_NETWORK_LOGGING_ENABLED.equals(tag)) {
                                this.isNetworkLoggingEnabled = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                                this.lastNetworkLoggingNotificationTimeMs = Long.parseLong(parser.getAttributeValue(null, ATTR_LAST_NETWORK_LOGGING_NOTIFICATION));
                                this.numNetworkLoggingNotifications = Integer.parseInt(parser.getAttributeValue(null, ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS));
                            } else if (TAG_DISABLE_KEYGUARD_FEATURES.equals(tag)) {
                                this.disabledKeyguardFeatures = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_DISABLE_ACCOUNT_MANAGEMENT.equals(tag)) {
                                readAttributeValues(parser, TAG_ACCOUNT_TYPE, this.accountTypesWithManagementDisabled);
                            } else if (TAG_MANAGE_TRUST_AGENT_FEATURES.equals(tag)) {
                                this.trustAgentInfos = getAllTrustAgentInfos(parser, tag);
                            } else if (TAG_CROSS_PROFILE_WIDGET_PROVIDERS.equals(tag)) {
                                this.crossProfileWidgetProviders = new ArrayList();
                                readAttributeValues(parser, TAG_PROVIDER, this.crossProfileWidgetProviders);
                            } else if (TAG_PERMITTED_ACCESSIBILITY_SERVICES.equals(tag)) {
                                this.permittedAccessiblityServices = readPackageList(parser, tag);
                            } else if (TAG_PERMITTED_IMES.equals(tag)) {
                                this.permittedInputMethods = readPackageList(parser, tag);
                            } else if (TAG_PERMITTED_NOTIFICATION_LISTENERS.equals(tag)) {
                                this.permittedNotificationListeners = readPackageList(parser, tag);
                            } else if (TAG_KEEP_UNINSTALLED_PACKAGES.equals(tag)) {
                                this.keepUninstalledPackages = readPackageList(parser, tag);
                            } else if (TAG_METERED_DATA_DISABLED_PACKAGES.equals(tag)) {
                                this.meteredDisabledPackages = readPackageList(parser, tag);
                            } else if (TAG_USER_RESTRICTIONS.equals(tag)) {
                                this.userRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                            } else if (TAG_DEFAULT_ENABLED_USER_RESTRICTIONS.equals(tag)) {
                                readAttributeValues(parser, TAG_RESTRICTION, this.defaultEnabledRestrictionsAlreadySet);
                            } else if (TAG_SHORT_SUPPORT_MESSAGE.equals(tag)) {
                                if (parser.next() == 4) {
                                    this.shortSupportMessage = parser.getText();
                                } else {
                                    Log.w(DevicePolicyManagerService.LOG_TAG, "Missing text when loading short support message");
                                }
                            } else if (TAG_LONG_SUPPORT_MESSAGE.equals(tag)) {
                                if (parser.next() == 4) {
                                    this.longSupportMessage = parser.getText();
                                } else {
                                    Log.w(DevicePolicyManagerService.LOG_TAG, "Missing text when loading long support message");
                                }
                            } else if (TAG_PARENT_ADMIN.equals(tag)) {
                                Preconditions.checkState(!this.isParent);
                                this.parentAdmin = new ActiveAdmin(this.info, true);
                                this.parentAdmin.readFromXml(parser);
                            } else if (TAG_ORGANIZATION_COLOR.equals(tag)) {
                                this.organizationColor = Integer.parseInt(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_ORGANIZATION_NAME.equals(tag)) {
                                if (parser.next() == 4) {
                                    this.organizationName = parser.getText();
                                } else {
                                    Log.w(DevicePolicyManagerService.LOG_TAG, "Missing text when loading organization name");
                                }
                            } else if (TAG_IS_LOGOUT_ENABLED.equals(tag)) {
                                this.isLogoutEnabled = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_MANDATORY_BACKUP_TRANSPORT.equals(tag)) {
                                this.mandatoryBackupTransport = ComponentName.unflattenFromString(parser.getAttributeValue(null, ATTR_VALUE));
                            } else if (TAG_START_USER_SESSION_MESSAGE.equals(tag)) {
                                if (parser.next() == 4) {
                                    this.startUserSessionMessage = parser.getText();
                                } else {
                                    Log.w(DevicePolicyManagerService.LOG_TAG, "Missing text when loading start session message");
                                }
                            } else if (TAG_END_USER_SESSION_MESSAGE.equals(tag)) {
                                if (parser.next() == 4) {
                                    this.endUserSessionMessage = parser.getText();
                                } else {
                                    Log.w(DevicePolicyManagerService.LOG_TAG, "Missing text when loading end session message");
                                }
                            } else {
                                Slog.w(DevicePolicyManagerService.LOG_TAG, "Unknown admin tag: " + tag);
                                XmlUtils.skipCurrentTag(parser);
                            }
                        }
                    } else {
                        return;
                    }
                } else {
                    return;
                }
            }
        }

        private List<String> readPackageList(XmlPullParser parser, String tag) throws XmlPullParserException, IOException {
            List<String> result = new ArrayList<>();
            int outerDepth = parser.getDepth();
            while (true) {
                int outerType = parser.next();
                if (outerType == 1 || (outerType == 3 && parser.getDepth() <= outerDepth)) {
                    break;
                } else if (outerType != 3 && outerType != 4) {
                    String outerTag = parser.getName();
                    if ("item".equals(outerTag)) {
                        String packageName = parser.getAttributeValue(null, ATTR_VALUE);
                        if (packageName != null) {
                            result.add(packageName);
                        } else {
                            Slog.w(DevicePolicyManagerService.LOG_TAG, "Package name missing under " + outerTag);
                        }
                    } else {
                        Slog.w(DevicePolicyManagerService.LOG_TAG, "Unknown tag under " + tag + ": " + outerTag);
                    }
                }
            }
            return result;
        }

        private void readAttributeValues(XmlPullParser parser, String tag, Collection<String> result) throws XmlPullParserException, IOException {
            result.clear();
            int outerDepthDAM = parser.getDepth();
            while (true) {
                int typeDAM = parser.next();
                if (typeDAM != 1) {
                    if (typeDAM != 3 || parser.getDepth() > outerDepthDAM) {
                        if (typeDAM != 3 && typeDAM != 4) {
                            String tagDAM = parser.getName();
                            if (tag.equals(tagDAM)) {
                                result.add(parser.getAttributeValue(null, ATTR_VALUE));
                            } else {
                                Slog.e(DevicePolicyManagerService.LOG_TAG, "Expected tag " + tag + " but found " + tagDAM);
                            }
                        }
                    } else {
                        return;
                    }
                } else {
                    return;
                }
            }
        }

        private ArrayMap<String, TrustAgentInfo> getAllTrustAgentInfos(XmlPullParser parser, String tag) throws XmlPullParserException, IOException {
            int outerDepthDAM = parser.getDepth();
            ArrayMap<String, TrustAgentInfo> result = new ArrayMap<>();
            while (true) {
                int typeDAM = parser.next();
                if (typeDAM == 1 || (typeDAM == 3 && parser.getDepth() <= outerDepthDAM)) {
                    break;
                } else if (typeDAM != 3 && typeDAM != 4) {
                    String tagDAM = parser.getName();
                    if (TAG_TRUST_AGENT_COMPONENT.equals(tagDAM)) {
                        String component = parser.getAttributeValue(null, ATTR_VALUE);
                        TrustAgentInfo trustAgentInfo = getTrustAgentInfo(parser, tag);
                        result.put(component, trustAgentInfo);
                    } else {
                        Slog.w(DevicePolicyManagerService.LOG_TAG, "Unknown tag under " + tag + ": " + tagDAM);
                    }
                }
            }
            return result;
        }

        private TrustAgentInfo getTrustAgentInfo(XmlPullParser parser, String tag) throws XmlPullParserException, IOException {
            int outerDepthDAM = parser.getDepth();
            TrustAgentInfo result = new TrustAgentInfo(null);
            while (true) {
                int typeDAM = parser.next();
                if (typeDAM == 1 || (typeDAM == 3 && parser.getDepth() <= outerDepthDAM)) {
                    break;
                } else if (typeDAM != 3 && typeDAM != 4) {
                    String tagDAM = parser.getName();
                    if (TAG_TRUST_AGENT_COMPONENT_OPTIONS.equals(tagDAM)) {
                        result.options = PersistableBundle.restoreFromXml(parser);
                    } else {
                        Slog.w(DevicePolicyManagerService.LOG_TAG, "Unknown tag under " + tag + ": " + tagDAM);
                    }
                }
            }
            return result;
        }

        boolean hasUserRestrictions() {
            return this.userRestrictions != null && this.userRestrictions.size() > 0;
        }

        Bundle ensureUserRestrictions() {
            if (this.userRestrictions == null) {
                this.userRestrictions = new Bundle();
            }
            return this.userRestrictions;
        }

        public void transfer(DeviceAdminInfo deviceAdminInfo) {
            if (hasParentActiveAdmin()) {
                this.parentAdmin.info = deviceAdminInfo;
            }
            this.info = deviceAdminInfo;
        }

        void dump(String prefix, PrintWriter pw) {
            pw.print(prefix);
            pw.print("uid=");
            pw.println(getUid());
            pw.print(prefix);
            pw.print("testOnlyAdmin=");
            pw.println(this.testOnlyAdmin);
            pw.print(prefix);
            pw.println("policies:");
            ArrayList<DeviceAdminInfo.PolicyInfo> pols = this.info.getUsedPolicies();
            if (pols != null) {
                for (int i = 0; i < pols.size(); i++) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.println(pols.get(i).tag);
                }
            }
            pw.print(prefix);
            pw.print("passwordQuality=0x");
            pw.println(Integer.toHexString(this.minimumPasswordMetrics.quality));
            pw.print(prefix);
            pw.print("minimumPasswordLength=");
            pw.println(this.minimumPasswordMetrics.length);
            pw.print(prefix);
            pw.print("passwordHistoryLength=");
            pw.println(this.passwordHistoryLength);
            pw.print(prefix);
            pw.print("minimumPasswordUpperCase=");
            pw.println(this.minimumPasswordMetrics.upperCase);
            pw.print(prefix);
            pw.print("minimumPasswordLowerCase=");
            pw.println(this.minimumPasswordMetrics.lowerCase);
            pw.print(prefix);
            pw.print("minimumPasswordLetters=");
            pw.println(this.minimumPasswordMetrics.letters);
            pw.print(prefix);
            pw.print("minimumPasswordNumeric=");
            pw.println(this.minimumPasswordMetrics.numeric);
            pw.print(prefix);
            pw.print("minimumPasswordSymbols=");
            pw.println(this.minimumPasswordMetrics.symbols);
            pw.print(prefix);
            pw.print("minimumPasswordNonLetter=");
            pw.println(this.minimumPasswordMetrics.nonLetter);
            pw.print(prefix);
            pw.print("maximumTimeToUnlock=");
            pw.println(this.maximumTimeToUnlock);
            pw.print(prefix);
            pw.print("strongAuthUnlockTimeout=");
            pw.println(this.strongAuthUnlockTimeout);
            pw.print(prefix);
            pw.print("maximumFailedPasswordsForWipe=");
            pw.println(this.maximumFailedPasswordsForWipe);
            pw.print(prefix);
            pw.print("specifiesGlobalProxy=");
            pw.println(this.specifiesGlobalProxy);
            pw.print(prefix);
            pw.print("passwordExpirationTimeout=");
            pw.println(this.passwordExpirationTimeout);
            pw.print(prefix);
            pw.print("passwordExpirationDate=");
            pw.println(this.passwordExpirationDate);
            if (this.globalProxySpec != null) {
                pw.print(prefix);
                pw.print("globalProxySpec=");
                pw.println(this.globalProxySpec);
            }
            if (this.globalProxyExclusionList != null) {
                pw.print(prefix);
                pw.print("globalProxyEclusionList=");
                pw.println(this.globalProxyExclusionList);
            }
            pw.print(prefix);
            pw.print("encryptionRequested=");
            pw.println(this.encryptionRequested);
            pw.print(prefix);
            pw.print("disableCamera=");
            pw.println(this.disableCamera);
            pw.print(prefix);
            pw.print("disableCallerId=");
            pw.println(this.disableCallerId);
            pw.print(prefix);
            pw.print("disableContactsSearch=");
            pw.println(this.disableContactsSearch);
            pw.print(prefix);
            pw.print("disableBluetoothContactSharing=");
            pw.println(this.disableBluetoothContactSharing);
            pw.print(prefix);
            pw.print("disableScreenCapture=");
            pw.println(this.disableScreenCapture);
            pw.print(prefix);
            pw.print("requireAutoTime=");
            pw.println(this.requireAutoTime);
            pw.print(prefix);
            pw.print("forceEphemeralUsers=");
            pw.println(this.forceEphemeralUsers);
            pw.print(prefix);
            pw.print("isNetworkLoggingEnabled=");
            pw.println(this.isNetworkLoggingEnabled);
            pw.print(prefix);
            pw.print("disabledKeyguardFeatures=");
            pw.println(this.disabledKeyguardFeatures);
            pw.print(prefix);
            pw.print("crossProfileWidgetProviders=");
            pw.println(this.crossProfileWidgetProviders);
            if (this.permittedAccessiblityServices != null) {
                pw.print(prefix);
                pw.print("permittedAccessibilityServices=");
                pw.println(this.permittedAccessiblityServices);
            }
            if (this.permittedInputMethods != null) {
                pw.print(prefix);
                pw.print("permittedInputMethods=");
                pw.println(this.permittedInputMethods);
            }
            if (this.permittedNotificationListeners != null) {
                pw.print(prefix);
                pw.print("permittedNotificationListeners=");
                pw.println(this.permittedNotificationListeners);
            }
            if (this.keepUninstalledPackages != null) {
                pw.print(prefix);
                pw.print("keepUninstalledPackages=");
                pw.println(this.keepUninstalledPackages);
            }
            pw.print(prefix);
            pw.print("organizationColor=");
            pw.println(this.organizationColor);
            if (this.organizationName != null) {
                pw.print(prefix);
                pw.print("organizationName=");
                pw.println(this.organizationName);
            }
            pw.print(prefix);
            pw.println("userRestrictions:");
            UserRestrictionsUtils.dumpRestrictions(pw, prefix + "  ", this.userRestrictions);
            pw.print(prefix);
            pw.print("defaultEnabledRestrictionsAlreadySet=");
            pw.println(this.defaultEnabledRestrictionsAlreadySet);
            pw.print(prefix);
            pw.print("isParent=");
            pw.println(this.isParent);
            if (this.parentAdmin != null) {
                pw.print(prefix);
                pw.println("parentAdmin:");
                ActiveAdmin activeAdmin = this.parentAdmin;
                activeAdmin.dump(prefix + "  ", pw);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackagesChanged(String packageName, int userHandle) {
        boolean removedAdmin = false;
        DevicePolicyData policy = getUserData(userHandle);
        synchronized (getLockObject()) {
            for (int i = policy.mAdminList.size() - 1; i >= 0; i--) {
                ActiveAdmin aa = policy.mAdminList.get(i);
                try {
                    String adminPackage = aa.info.getPackageName();
                    if ((packageName == null || packageName.equals(adminPackage)) && (this.mIPackageManager.getPackageInfo(adminPackage, 0, userHandle) == null || this.mIPackageManager.getReceiverInfo(aa.info.getComponent(), 786432, userHandle) == null)) {
                        removedAdmin = true;
                        policy.mAdminList.remove(i);
                        policy.mAdminMap.remove(aa.info.getComponent());
                        pushActiveAdminPackagesLocked(userHandle);
                        pushMeteredDisabledPackagesLocked(userHandle);
                    }
                } catch (RemoteException e) {
                }
            }
            if (removedAdmin) {
                validatePasswordOwnerLocked(policy);
            }
            boolean removedDelegate = false;
            for (int i2 = policy.mDelegationMap.size() - 1; i2 >= 0; i2--) {
                String delegatePackage = policy.mDelegationMap.keyAt(i2);
                if (isRemovedPackage(packageName, delegatePackage, userHandle)) {
                    policy.mDelegationMap.removeAt(i2);
                    removedDelegate = true;
                }
            }
            ComponentName owner = getOwnerComponent(userHandle);
            if (packageName != null && owner != null && owner.getPackageName().equals(packageName)) {
                startOwnerService(userHandle, "package-broadcast");
            }
            if (removedAdmin || removedDelegate) {
                saveSettingsLocked(policy.mUserHandle);
            }
        }
        if (removedAdmin) {
            pushUserRestrictions(userHandle);
        }
    }

    private boolean isRemovedPackage(String changedPackage, String targetPackage, int userHandle) {
        if (targetPackage != null) {
            if (changedPackage != null) {
                try {
                    if (!changedPackage.equals(targetPackage)) {
                        return false;
                    }
                } catch (RemoteException e) {
                    return false;
                }
            }
            return this.mIPackageManager.getPackageInfo(targetPackage, 0, userHandle) == null;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class Injector {
        public final Context mContext;

        Injector(Context context) {
            this.mContext = context;
        }

        public boolean hasFeature() {
            return getPackageManager().hasSystemFeature("android.software.device_admin");
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public Context createContextAsUser(UserHandle user) throws PackageManager.NameNotFoundException {
            String packageName = this.mContext.getPackageName();
            return this.mContext.createPackageContextAsUser(packageName, 0, user);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public Resources getResources() {
            return this.mContext.getResources();
        }

        Owners newOwners() {
            return new Owners(getUserManager(), getUserManagerInternal(), getPackageManagerInternal());
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public UserManager getUserManager() {
            return UserManager.get(this.mContext);
        }

        UserManagerInternal getUserManagerInternal() {
            return (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
        }

        PackageManagerInternal getPackageManagerInternal() {
            return (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        }

        UsageStatsManagerInternal getUsageStatsManagerInternal() {
            return (UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class);
        }

        NetworkPolicyManagerInternal getNetworkPolicyManagerInternal() {
            return (NetworkPolicyManagerInternal) LocalServices.getService(NetworkPolicyManagerInternal.class);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public NotificationManager getNotificationManager() {
            return (NotificationManager) this.mContext.getSystemService(NotificationManager.class);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public IIpConnectivityMetrics getIIpConnectivityMetrics() {
            return IIpConnectivityMetrics.Stub.asInterface(ServiceManager.getService("connmetrics"));
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public PackageManager getPackageManager() {
            return this.mContext.getPackageManager();
        }

        PowerManagerInternal getPowerManagerInternal() {
            return (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
        }

        TelephonyManager getTelephonyManager() {
            return TelephonyManager.from(this.mContext);
        }

        TrustManager getTrustManager() {
            return (TrustManager) this.mContext.getSystemService("trust");
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public AlarmManager getAlarmManager() {
            return (AlarmManager) this.mContext.getSystemService(AlarmManager.class);
        }

        IWindowManager getIWindowManager() {
            return IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        }

        IActivityManager getIActivityManager() {
            return ActivityManager.getService();
        }

        ActivityManagerInternal getActivityManagerInternal() {
            return (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }

        IBackupManager getIBackupManager() {
            return IBackupManager.Stub.asInterface(ServiceManager.getService(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD));
        }

        IAudioService getIAudioService() {
            return IAudioService.Stub.asInterface(ServiceManager.getService("audio"));
        }

        boolean isBuildDebuggable() {
            return Build.IS_DEBUGGABLE;
        }

        LockPatternUtils newLockPatternUtils() {
            return new LockPatternUtils(this.mContext);
        }

        boolean storageManagerIsFileBasedEncryptionEnabled() {
            return StorageManager.isFileEncryptedNativeOnly();
        }

        boolean storageManagerIsNonDefaultBlockEncrypted() {
            long identity = Binder.clearCallingIdentity();
            try {
                return StorageManager.isNonDefaultBlockEncrypted();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        boolean storageManagerIsEncrypted() {
            return StorageManager.isEncrypted();
        }

        boolean storageManagerIsEncryptable() {
            return StorageManager.isEncryptable();
        }

        Looper getMyLooper() {
            return Looper.myLooper();
        }

        WifiManager getWifiManager() {
            return (WifiManager) this.mContext.getSystemService(WifiManager.class);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public long binderClearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void binderRestoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        int binderGetCallingUid() {
            return Binder.getCallingUid();
        }

        int binderGetCallingPid() {
            return Binder.getCallingPid();
        }

        UserHandle binderGetCallingUserHandle() {
            return Binder.getCallingUserHandle();
        }

        boolean binderIsCallingUidMyUid() {
            return Binder.getCallingUid() == Process.myUid();
        }

        void binderWithCleanCallingIdentity(FunctionalUtils.ThrowingRunnable action) {
            Binder.withCleanCallingIdentity(action);
        }

        final int userHandleGetCallingUserId() {
            return UserHandle.getUserId(binderGetCallingUid());
        }

        File environmentGetUserSystemDirectory(int userId) {
            return Environment.getUserSystemDirectory(userId);
        }

        void powerManagerGoToSleep(long time, int reason, int flags) {
            ((PowerManager) this.mContext.getSystemService(PowerManager.class)).goToSleep(time, reason, flags);
        }

        void powerManagerReboot(String reason) {
            ((PowerManager) this.mContext.getSystemService(PowerManager.class)).reboot(reason);
        }

        void recoverySystemRebootWipeUserData(boolean shutdown, String reason, boolean force, boolean wipeEuicc) throws IOException {
            RecoverySystem.rebootWipeUserData(this.mContext, shutdown, reason, force, wipeEuicc);
        }

        boolean systemPropertiesGetBoolean(String key, boolean def) {
            return SystemProperties.getBoolean(key, def);
        }

        long systemPropertiesGetLong(String key, long def) {
            return SystemProperties.getLong(key, def);
        }

        String systemPropertiesGet(String key, String def) {
            return SystemProperties.get(key, def);
        }

        String systemPropertiesGet(String key) {
            return SystemProperties.get(key);
        }

        void systemPropertiesSet(String key, String value) {
            SystemProperties.set(key, value);
        }

        boolean userManagerIsSplitSystemUser() {
            return UserManager.isSplitSystemUser();
        }

        String getDevicePolicyFilePathForSystemUser() {
            return "/data/system/";
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public PendingIntent pendingIntentGetActivityAsUser(Context context, int requestCode, Intent intent, int flags, Bundle options, UserHandle user) {
            return PendingIntent.getActivityAsUser(context, requestCode, intent, flags, options, user);
        }

        void registerContentObserver(Uri uri, boolean notifyForDescendents, ContentObserver observer, int userHandle) {
            this.mContext.getContentResolver().registerContentObserver(uri, notifyForDescendents, observer, userHandle);
        }

        int settingsSecureGetIntForUser(String name, int def, int userHandle) {
            return Settings.Secure.getIntForUser(this.mContext.getContentResolver(), name, def, userHandle);
        }

        String settingsSecureGetStringForUser(String name, int userHandle) {
            return Settings.Secure.getStringForUser(this.mContext.getContentResolver(), name, userHandle);
        }

        void settingsSecurePutIntForUser(String name, int value, int userHandle) {
            Settings.Secure.putIntForUser(this.mContext.getContentResolver(), name, value, userHandle);
        }

        void settingsSecurePutStringForUser(String name, String value, int userHandle) {
            Settings.Secure.putStringForUser(this.mContext.getContentResolver(), name, value, userHandle);
        }

        void settingsGlobalPutStringForUser(String name, String value, int userHandle) {
            Settings.Global.putStringForUser(this.mContext.getContentResolver(), name, value, userHandle);
        }

        void settingsSecurePutInt(String name, int value) {
            Settings.Secure.putInt(this.mContext.getContentResolver(), name, value);
        }

        int settingsGlobalGetInt(String name, int def) {
            return Settings.Global.getInt(this.mContext.getContentResolver(), name, def);
        }

        String settingsGlobalGetString(String name) {
            return Settings.Global.getString(this.mContext.getContentResolver(), name);
        }

        void settingsGlobalPutInt(String name, int value) {
            Settings.Global.putInt(this.mContext.getContentResolver(), name, value);
        }

        void settingsSecurePutString(String name, String value) {
            Settings.Secure.putString(this.mContext.getContentResolver(), name, value);
        }

        void settingsGlobalPutString(String name, String value) {
            Settings.Global.putString(this.mContext.getContentResolver(), name, value);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void settingsSystemPutStringForUser(String name, String value, int userId) {
            Settings.System.putStringForUser(this.mContext.getContentResolver(), name, value, userId);
        }

        void securityLogSetLoggingEnabledProperty(boolean enabled) {
            SecurityLog.setLoggingEnabledProperty(enabled);
        }

        boolean securityLogGetLoggingEnabledProperty() {
            return SecurityLog.getLoggingEnabledProperty();
        }

        boolean securityLogIsLoggingEnabled() {
            return SecurityLog.isLoggingEnabled();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public KeyChain.KeyChainConnection keyChainBindAsUser(UserHandle user) throws InterruptedException {
            return KeyChain.bindAsUser(this.mContext, user);
        }

        void postOnSystemServerInitThreadPool(Runnable runnable) {
            SystemServerInitThreadPool.get().submit(runnable, DevicePolicyManagerService.LOG_TAG);
        }

        public TransferOwnershipMetadataManager newTransferOwnershipMetadataManager() {
            return new TransferOwnershipMetadataManager();
        }

        public void runCryptoSelfTest() {
            CryptoTestHelper.runAndLogSelfTest();
        }
    }

    public DevicePolicyManagerService(Context context) {
        this(new Injector(context));
    }

    @VisibleForTesting
    DevicePolicyManagerService(Injector injector) {
        this.mPolicyCache = new DevicePolicyCacheImpl();
        this.mPackagesToRemove = new ArraySet();
        this.mToken = new Binder();
        this.mRemoteBugreportServiceIsActive = new AtomicBoolean();
        this.mRemoteBugreportSharingAccepted = new AtomicBoolean();
        this.mStatLogger = new StatLogger(new String[]{"LockGuard.guard()"});
        this.mLockDoNoUseDirectly = LockGuard.installNewLock(7, true);
        this.mRemoteBugreportTimeoutRunnable = new Runnable() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.1
            @Override // java.lang.Runnable
            public void run() {
                if (DevicePolicyManagerService.this.mRemoteBugreportServiceIsActive.get()) {
                    DevicePolicyManagerService.this.onBugreportFailed();
                }
            }
        };
        this.mRemoteBugreportFinishedReceiver = new BroadcastReceiver() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                if ("android.intent.action.REMOTE_BUGREPORT_DISPATCH".equals(intent.getAction()) && DevicePolicyManagerService.this.mRemoteBugreportServiceIsActive.get()) {
                    DevicePolicyManagerService.this.onBugreportFinished(intent);
                }
            }
        };
        this.mRemoteBugreportConsentReceiver = new BroadcastReceiver() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                DevicePolicyManagerService.this.mInjector.getNotificationManager().cancel(DevicePolicyManagerService.LOG_TAG, 678432343);
                if ("com.android.server.action.REMOTE_BUGREPORT_SHARING_ACCEPTED".equals(action)) {
                    DevicePolicyManagerService.this.onBugreportSharingAccepted();
                } else if ("com.android.server.action.REMOTE_BUGREPORT_SHARING_DECLINED".equals(action)) {
                    DevicePolicyManagerService.this.onBugreportSharingDeclined();
                }
                DevicePolicyManagerService.this.mContext.unregisterReceiver(DevicePolicyManagerService.this.mRemoteBugreportConsentReceiver);
            }
        };
        this.mUserData = new SparseArray<>();
        this.mUserPasswordMetrics = new SparseArray<>();
        this.mReceiver = new BroadcastReceiver() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.4
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                final int userHandle = intent.getIntExtra("android.intent.extra.user_handle", getSendingUserId());
                if ("android.intent.action.USER_STARTED".equals(action) && userHandle == DevicePolicyManagerService.this.mOwners.getDeviceOwnerUserId()) {
                    synchronized (DevicePolicyManagerService.this.getLockObject()) {
                        if (DevicePolicyManagerService.this.isNetworkLoggingEnabledInternalLocked()) {
                            DevicePolicyManagerService.this.setNetworkLoggingActiveInternal(true);
                        }
                    }
                }
                if ("android.intent.action.BOOT_COMPLETED".equals(action) && userHandle == DevicePolicyManagerService.this.mOwners.getDeviceOwnerUserId() && DevicePolicyManagerService.this.getDeviceOwnerRemoteBugreportUri() != null) {
                    IntentFilter filterConsent = new IntentFilter();
                    filterConsent.addAction("com.android.server.action.REMOTE_BUGREPORT_SHARING_DECLINED");
                    filterConsent.addAction("com.android.server.action.REMOTE_BUGREPORT_SHARING_ACCEPTED");
                    DevicePolicyManagerService.this.mContext.registerReceiver(DevicePolicyManagerService.this.mRemoteBugreportConsentReceiver, filterConsent);
                    DevicePolicyManagerService.this.mInjector.getNotificationManager().notifyAsUser(DevicePolicyManagerService.LOG_TAG, 678432343, RemoteBugreportUtils.buildNotification(DevicePolicyManagerService.this.mContext, 3), UserHandle.ALL);
                }
                if ("android.intent.action.BOOT_COMPLETED".equals(action) || DevicePolicyManagerService.ACTION_EXPIRED_PASSWORD_NOTIFICATION.equals(action)) {
                    DevicePolicyManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.4.1
                        @Override // java.lang.Runnable
                        public void run() {
                            DevicePolicyManagerService.this.handlePasswordExpirationNotification(userHandle);
                        }
                    });
                }
                if ("android.intent.action.USER_ADDED".equals(action)) {
                    sendDeviceOwnerUserCommand("android.app.action.USER_ADDED", userHandle);
                    synchronized (DevicePolicyManagerService.this.getLockObject()) {
                        DevicePolicyManagerService.this.maybePauseDeviceWideLoggingLocked();
                    }
                } else if ("android.intent.action.USER_REMOVED".equals(action)) {
                    sendDeviceOwnerUserCommand("android.app.action.USER_REMOVED", userHandle);
                    synchronized (DevicePolicyManagerService.this.getLockObject()) {
                        boolean isRemovedUserAffiliated = DevicePolicyManagerService.this.isUserAffiliatedWithDeviceLocked(userHandle);
                        DevicePolicyManagerService.this.removeUserData(userHandle);
                        if (!isRemovedUserAffiliated) {
                            DevicePolicyManagerService.this.discardDeviceWideLogsLocked();
                            DevicePolicyManagerService.this.maybeResumeDeviceWideLoggingLocked();
                        }
                    }
                } else if ("android.intent.action.USER_STARTED".equals(action)) {
                    sendDeviceOwnerUserCommand("android.app.action.USER_STARTED", userHandle);
                    synchronized (DevicePolicyManagerService.this.getLockObject()) {
                        DevicePolicyManagerService.this.maybeSendAdminEnabledBroadcastLocked(userHandle);
                        DevicePolicyManagerService.this.mUserData.remove(userHandle);
                    }
                    DevicePolicyManagerService.this.handlePackagesChanged(null, userHandle);
                } else if ("android.intent.action.USER_STOPPED".equals(action)) {
                    sendDeviceOwnerUserCommand("android.app.action.USER_STOPPED", userHandle);
                } else if ("android.intent.action.USER_SWITCHED".equals(action)) {
                    sendDeviceOwnerUserCommand("android.app.action.USER_SWITCHED", userHandle);
                } else if ("android.intent.action.USER_UNLOCKED".equals(action)) {
                    synchronized (DevicePolicyManagerService.this.getLockObject()) {
                        DevicePolicyManagerService.this.maybeSendAdminEnabledBroadcastLocked(userHandle);
                    }
                } else if ("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE".equals(action)) {
                    DevicePolicyManagerService.this.handlePackagesChanged(null, userHandle);
                } else if ("android.intent.action.PACKAGE_CHANGED".equals(action) || ("android.intent.action.PACKAGE_ADDED".equals(action) && intent.getBooleanExtra("android.intent.extra.REPLACING", false))) {
                    DevicePolicyManagerService.this.handlePackagesChanged(intent.getData().getSchemeSpecificPart(), userHandle);
                } else if ("android.intent.action.PACKAGE_REMOVED".equals(action) && !intent.getBooleanExtra("android.intent.extra.REPLACING", false)) {
                    DevicePolicyManagerService.this.handlePackagesChanged(intent.getData().getSchemeSpecificPart(), userHandle);
                } else if ("android.intent.action.MANAGED_PROFILE_ADDED".equals(action)) {
                    DevicePolicyManagerService.this.clearWipeProfileNotification();
                } else if ("android.intent.action.DATE_CHANGED".equals(action) || "android.intent.action.TIME_SET".equals(action)) {
                    DevicePolicyManagerService.this.updateSystemUpdateFreezePeriodsRecord(true);
                }
            }

            private void sendDeviceOwnerUserCommand(String action, int userHandle) {
                synchronized (DevicePolicyManagerService.this.getLockObject()) {
                    ActiveAdmin deviceOwner = DevicePolicyManagerService.this.getDeviceOwnerAdminLocked();
                    if (deviceOwner != null) {
                        Bundle extras = new Bundle();
                        extras.putParcelable("android.intent.extra.USER", UserHandle.of(userHandle));
                        DevicePolicyManagerService.this.sendAdminCommandLocked(deviceOwner, action, extras, null, true);
                    }
                }
            }
        };
        this.mInjector = injector;
        this.mContext = (Context) Preconditions.checkNotNull(injector.mContext);
        this.mHandler = new Handler((Looper) Preconditions.checkNotNull(injector.getMyLooper()));
        this.mConstants = DevicePolicyConstants.loadFromString(this.mInjector.settingsGlobalGetString("device_policy_constants"));
        this.mOwners = (Owners) Preconditions.checkNotNull(injector.newOwners());
        this.mUserManager = (UserManager) Preconditions.checkNotNull(injector.getUserManager());
        this.mUserManagerInternal = (UserManagerInternal) Preconditions.checkNotNull(injector.getUserManagerInternal());
        this.mUsageStatsManagerInternal = (UsageStatsManagerInternal) Preconditions.checkNotNull(injector.getUsageStatsManagerInternal());
        this.mIPackageManager = (IPackageManager) Preconditions.checkNotNull(injector.getIPackageManager());
        this.mTelephonyManager = (TelephonyManager) Preconditions.checkNotNull(injector.getTelephonyManager());
        this.mLocalService = new LocalService();
        this.mLockPatternUtils = injector.newLockPatternUtils();
        this.mSecurityLogMonitor = new SecurityLogMonitor(this);
        this.mHasFeature = this.mInjector.hasFeature();
        this.mIsWatch = this.mInjector.getPackageManager().hasSystemFeature("android.hardware.type.watch");
        this.mBackgroundHandler = BackgroundThreadxp.getHandler();
        this.mCertificateMonitor = new CertificateMonitor(this, this.mInjector, this.mBackgroundHandler);
        this.mDeviceAdminServiceController = new DeviceAdminServiceController(this, this.mConstants);
        this.mOverlayPackagesProvider = new OverlayPackagesProvider(this.mContext);
        this.mTransferOwnershipMetadataManager = this.mInjector.newTransferOwnershipMetadataManager();
        if (!this.mHasFeature) {
            this.mSetupContentObserver = null;
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.BOOT_COMPLETED");
        filter.addAction(ACTION_EXPIRED_PASSWORD_NOTIFICATION);
        filter.addAction("android.intent.action.USER_ADDED");
        filter.addAction("android.intent.action.USER_REMOVED");
        filter.addAction("android.intent.action.USER_STARTED");
        filter.addAction("android.intent.action.USER_STOPPED");
        filter.addAction("android.intent.action.USER_SWITCHED");
        filter.addAction("android.intent.action.USER_UNLOCKED");
        filter.setPriority(1000);
        this.mContext.registerReceiverAsUser(this.mReceiver, UserHandle.ALL, filter, null, this.mHandler);
        IntentFilter filter2 = new IntentFilter();
        filter2.addAction("android.intent.action.PACKAGE_CHANGED");
        filter2.addAction("android.intent.action.PACKAGE_REMOVED");
        filter2.addAction("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE");
        filter2.addAction("android.intent.action.PACKAGE_ADDED");
        filter2.addDataScheme("package");
        this.mContext.registerReceiverAsUser(this.mReceiver, UserHandle.ALL, filter2, null, this.mHandler);
        IntentFilter filter3 = new IntentFilter();
        filter3.addAction("android.intent.action.MANAGED_PROFILE_ADDED");
        filter3.addAction("android.intent.action.TIME_SET");
        filter3.addAction("android.intent.action.DATE_CHANGED");
        this.mContext.registerReceiverAsUser(this.mReceiver, UserHandle.ALL, filter3, null, this.mHandler);
        LocalServices.addService(DevicePolicyManagerInternal.class, this.mLocalService);
        this.mSetupContentObserver = new SetupContentObserver(this.mHandler);
        this.mUserManagerInternal.addUserRestrictionsListener(new RestrictionsListener(this.mContext));
    }

    DevicePolicyData getUserData(int userHandle) {
        DevicePolicyData policy;
        synchronized (getLockObject()) {
            policy = this.mUserData.get(userHandle);
            if (policy == null) {
                policy = new DevicePolicyData(userHandle);
                this.mUserData.append(userHandle, policy);
                loadSettingsLocked(policy, userHandle);
            }
        }
        return policy;
    }

    PasswordMetrics getUserPasswordMetricsLocked(int userHandle) {
        return this.mUserPasswordMetrics.get(userHandle);
    }

    DevicePolicyData getUserDataUnchecked(int userHandle) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            return getUserData(userHandle);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    void removeUserData(int userHandle) {
        synchronized (getLockObject()) {
            try {
                if (userHandle == 0) {
                    Slog.w(LOG_TAG, "Tried to remove device policy file for user 0! Ignoring.");
                    return;
                }
                this.mPolicyCache.onUserRemoved(userHandle);
                this.mOwners.removeProfileOwner(userHandle);
                this.mOwners.writeProfileOwner(userHandle);
                DevicePolicyData policy = this.mUserData.get(userHandle);
                if (policy != null) {
                    this.mUserData.remove(userHandle);
                }
                if (this.mUserPasswordMetrics.get(userHandle) != null) {
                    this.mUserPasswordMetrics.remove(userHandle);
                }
                File policyFile = new File(this.mInjector.environmentGetUserSystemDirectory(userHandle), DEVICE_POLICIES_XML);
                policyFile.delete();
                Slog.i(LOG_TAG, "Removed device policy file " + policyFile.getAbsolutePath());
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    void loadOwners() {
        synchronized (getLockObject()) {
            this.mOwners.load();
            setDeviceOwnerSystemPropertyLocked();
            findOwnerComponentIfNecessaryLocked();
            migrateUserRestrictionsIfNecessaryLocked();
            maybeSetDefaultDeviceOwnerUserRestrictionsLocked();
            updateDeviceOwnerLocked();
        }
    }

    private void maybeSetDefaultDeviceOwnerUserRestrictionsLocked() {
        ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        if (deviceOwner != null) {
            maybeSetDefaultRestrictionsForAdminLocked(this.mOwners.getDeviceOwnerUserId(), deviceOwner, UserRestrictionsUtils.getDefaultEnabledForDeviceOwner());
        }
    }

    private void maybeSetDefaultProfileOwnerUserRestrictions() {
        synchronized (getLockObject()) {
            for (Integer num : this.mOwners.getProfileOwnerKeys()) {
                int userId = num.intValue();
                ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
                if (profileOwner != null && this.mUserManager.isManagedProfile(userId)) {
                    maybeSetDefaultRestrictionsForAdminLocked(userId, profileOwner, UserRestrictionsUtils.getDefaultEnabledForManagedProfiles());
                    ensureUnknownSourcesRestrictionForProfileOwnerLocked(userId, profileOwner, false);
                }
            }
        }
    }

    private void ensureUnknownSourcesRestrictionForProfileOwnerLocked(int userId, ActiveAdmin profileOwner, boolean newOwner) {
        if (newOwner || this.mInjector.settingsSecureGetIntForUser("unknown_sources_default_reversed", 0, userId) != 0) {
            profileOwner.ensureUserRestrictions().putBoolean("no_install_unknown_sources", true);
            saveUserRestrictionsLocked(userId);
            this.mInjector.settingsSecurePutIntForUser("unknown_sources_default_reversed", 0, userId);
        }
    }

    private void maybeSetDefaultRestrictionsForAdminLocked(int userId, ActiveAdmin admin, Set<String> defaultRestrictions) {
        if (defaultRestrictions.equals(admin.defaultEnabledRestrictionsAlreadySet)) {
            return;
        }
        Slog.i(LOG_TAG, "New user restrictions need to be set by default for user " + userId);
        Set<String> restrictionsToSet = new ArraySet<>(defaultRestrictions);
        restrictionsToSet.removeAll(admin.defaultEnabledRestrictionsAlreadySet);
        if (!restrictionsToSet.isEmpty()) {
            for (String restriction : restrictionsToSet) {
                admin.ensureUserRestrictions().putBoolean(restriction, true);
            }
            admin.defaultEnabledRestrictionsAlreadySet.addAll(restrictionsToSet);
            Slog.i(LOG_TAG, "Enabled the following restrictions by default: " + restrictionsToSet);
            saveUserRestrictionsLocked(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setDeviceOwnerSystemPropertyLocked() {
        boolean deviceProvisioned = this.mInjector.settingsGlobalGetInt("device_provisioned", 0) != 0;
        boolean hasDeviceOwner = this.mOwners.hasDeviceOwner();
        if ((!hasDeviceOwner && !deviceProvisioned) || StorageManager.inCryptKeeperBounce()) {
            return;
        }
        if (!this.mInjector.systemPropertiesGet(PROPERTY_DEVICE_OWNER_PRESENT, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS).isEmpty()) {
            Slog.w(LOG_TAG, "Trying to set ro.device_owner, but it has already been set?");
            return;
        }
        String value = Boolean.toString(hasDeviceOwner);
        this.mInjector.systemPropertiesSet(PROPERTY_DEVICE_OWNER_PRESENT, value);
        Slog.i(LOG_TAG, "Set ro.device_owner property to " + value);
    }

    private void maybeStartSecurityLogMonitorOnActivityManagerReady() {
        synchronized (getLockObject()) {
            if (this.mInjector.securityLogIsLoggingEnabled()) {
                this.mSecurityLogMonitor.start();
                this.mInjector.runCryptoSelfTest();
                maybePauseDeviceWideLoggingLocked();
            }
        }
    }

    private void findOwnerComponentIfNecessaryLocked() {
        if (!this.mOwners.hasDeviceOwner()) {
            return;
        }
        ComponentName doComponentName = this.mOwners.getDeviceOwnerComponent();
        if (!TextUtils.isEmpty(doComponentName.getClassName())) {
            return;
        }
        ComponentName doComponent = findAdminComponentWithPackageLocked(doComponentName.getPackageName(), this.mOwners.getDeviceOwnerUserId());
        if (doComponent == null) {
            Slog.e(LOG_TAG, "Device-owner isn't registered as device-admin");
            return;
        }
        this.mOwners.setDeviceOwnerWithRestrictionsMigrated(doComponent, this.mOwners.getDeviceOwnerName(), this.mOwners.getDeviceOwnerUserId(), !this.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration());
        this.mOwners.writeDeviceOwner();
    }

    private void migrateUserRestrictionsIfNecessaryLocked() {
        if (this.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration()) {
            ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();
            migrateUserRestrictionsForUser(UserHandle.SYSTEM, deviceOwnerAdmin, null, true);
            pushUserRestrictions(0);
            this.mOwners.setDeviceOwnerUserRestrictionsMigrated();
        }
        Set<String> secondaryUserExceptionList = Sets.newArraySet(new String[]{"no_outgoing_calls", "no_sms"});
        for (UserInfo ui : this.mUserManager.getUsers()) {
            int userId = ui.id;
            if (this.mOwners.getProfileOwnerUserRestrictionsNeedsMigration(userId)) {
                ActiveAdmin profileOwnerAdmin = getProfileOwnerAdminLocked(userId);
                Set<String> exceptionList = userId == 0 ? null : secondaryUserExceptionList;
                migrateUserRestrictionsForUser(ui.getUserHandle(), profileOwnerAdmin, exceptionList, false);
                pushUserRestrictions(userId);
                this.mOwners.setProfileOwnerUserRestrictionsMigrated(userId);
            }
        }
    }

    private void migrateUserRestrictionsForUser(UserHandle user, ActiveAdmin admin, Set<String> exceptionList, boolean isDeviceOwner) {
        boolean canOwnerChange;
        Bundle origRestrictions = this.mUserManagerInternal.getBaseUserRestrictions(user.getIdentifier());
        Bundle newBaseRestrictions = new Bundle();
        Bundle newOwnerRestrictions = new Bundle();
        for (String key : origRestrictions.keySet()) {
            if (origRestrictions.getBoolean(key)) {
                if (isDeviceOwner) {
                    canOwnerChange = UserRestrictionsUtils.canDeviceOwnerChange(key);
                } else {
                    canOwnerChange = UserRestrictionsUtils.canProfileOwnerChange(key, user.getIdentifier());
                }
                if (!canOwnerChange || (exceptionList != null && exceptionList.contains(key))) {
                    newBaseRestrictions.putBoolean(key, true);
                } else {
                    newOwnerRestrictions.putBoolean(key, true);
                }
            }
        }
        this.mUserManagerInternal.setBaseUserRestrictionsByDpmsForMigration(user.getIdentifier(), newBaseRestrictions);
        if (admin != null) {
            admin.ensureUserRestrictions().clear();
            admin.ensureUserRestrictions().putAll(newOwnerRestrictions);
        } else {
            Slog.w(LOG_TAG, "ActiveAdmin for DO/PO not found. user=" + user.getIdentifier());
        }
        saveSettingsLocked(user.getIdentifier());
    }

    private ComponentName findAdminComponentWithPackageLocked(String packageName, int userId) {
        DevicePolicyData policy = getUserData(userId);
        int n = policy.mAdminList.size();
        ComponentName found = null;
        int nFound = 0;
        for (int i = 0; i < n; i++) {
            ActiveAdmin admin = policy.mAdminList.get(i);
            if (packageName.equals(admin.info.getPackageName())) {
                if (nFound == 0) {
                    found = admin.info.getComponent();
                }
                nFound++;
            }
        }
        if (nFound > 1) {
            Slog.w(LOG_TAG, "Multiple DA found; assume the first one is DO.");
        }
        return found;
    }

    private void setExpirationAlarmCheckLocked(Context context, int userHandle, boolean parent) {
        long alarmInterval;
        int affectedUserHandle;
        long expiration = getPasswordExpirationLocked(null, userHandle, parent);
        long now = System.currentTimeMillis();
        long timeToExpire = expiration - now;
        if (expiration == 0) {
            alarmInterval = 0;
        } else if (timeToExpire <= 0) {
            alarmInterval = MS_PER_DAY + now;
        } else {
            long alarmInterval2 = timeToExpire % MS_PER_DAY;
            if (alarmInterval2 == 0) {
                alarmInterval2 = MS_PER_DAY;
            }
            alarmInterval = alarmInterval2 + now;
        }
        long token = this.mInjector.binderClearCallingIdentity();
        if (!parent) {
            affectedUserHandle = userHandle;
        } else {
            try {
                affectedUserHandle = getProfileParentId(userHandle);
            } catch (Throwable th) {
                th = th;
                this.mInjector.binderRestoreCallingIdentity(token);
                throw th;
            }
        }
        try {
            AlarmManager am = this.mInjector.getAlarmManager();
            try {
                PendingIntent pi = PendingIntent.getBroadcastAsUser(context, REQUEST_EXPIRE_PASSWORD, new Intent(ACTION_EXPIRED_PASSWORD_NOTIFICATION), 1207959552, UserHandle.of(affectedUserHandle));
                am.cancel(pi);
                if (alarmInterval != 0) {
                    am.set(1, alarmInterval, pi);
                }
                this.mInjector.binderRestoreCallingIdentity(token);
            } catch (Throwable th2) {
                th = th2;
                this.mInjector.binderRestoreCallingIdentity(token);
                throw th;
            }
        } catch (Throwable th3) {
            th = th3;
        }
    }

    ActiveAdmin getActiveAdminUncheckedLocked(ComponentName who, int userHandle) {
        ensureLocked();
        ActiveAdmin admin = getUserData(userHandle).mAdminMap.get(who);
        if (admin != null && who.getPackageName().equals(admin.info.getActivityInfo().packageName) && who.getClassName().equals(admin.info.getActivityInfo().name)) {
            return admin;
        }
        return null;
    }

    ActiveAdmin getActiveAdminUncheckedLocked(ComponentName who, int userHandle, boolean parent) {
        ensureLocked();
        if (parent) {
            enforceManagedProfile(userHandle, "call APIs on the parent profile");
        }
        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
        if (admin != null && parent) {
            return admin.getParentActiveAdmin();
        }
        return admin;
    }

    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who, int reqPolicy) throws SecurityException {
        ensureLocked();
        int callingUid = this.mInjector.binderGetCallingUid();
        ActiveAdmin result = getActiveAdminWithPolicyForUidLocked(who, reqPolicy, callingUid);
        if (result != null) {
            return result;
        }
        if (who != null) {
            int userId = UserHandle.getUserId(callingUid);
            DevicePolicyData policy = getUserData(userId);
            ActiveAdmin admin = policy.mAdminMap.get(who);
            if (reqPolicy == -2) {
                throw new SecurityException("Admin " + admin.info.getComponent() + " does not own the device");
            } else if (reqPolicy == -1) {
                throw new SecurityException("Admin " + admin.info.getComponent() + " does not own the profile");
            } else {
                throw new SecurityException("Admin " + admin.info.getComponent() + " did not specify uses-policy for: " + admin.info.getTagForPolicy(reqPolicy));
            }
        }
        throw new SecurityException("No active admin owned by uid " + this.mInjector.binderGetCallingUid() + " for policy #" + reqPolicy);
    }

    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who, int reqPolicy, boolean parent) throws SecurityException {
        ensureLocked();
        if (parent) {
            enforceManagedProfile(this.mInjector.userHandleGetCallingUserId(), "call APIs on the parent profile");
        }
        ActiveAdmin admin = getActiveAdminForCallerLocked(who, reqPolicy);
        return parent ? admin.getParentActiveAdmin() : admin;
    }

    private ActiveAdmin getActiveAdminForUidLocked(ComponentName who, int uid) {
        ensureLocked();
        int userId = UserHandle.getUserId(uid);
        DevicePolicyData policy = getUserData(userId);
        ActiveAdmin admin = policy.mAdminMap.get(who);
        if (admin == null) {
            throw new SecurityException("No active admin " + who);
        } else if (admin.getUid() != uid) {
            throw new SecurityException("Admin " + who + " is not owned by uid " + uid);
        } else {
            return admin;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ActiveAdmin getActiveAdminWithPolicyForUidLocked(ComponentName who, int reqPolicy, int uid) {
        ensureLocked();
        int userId = UserHandle.getUserId(uid);
        DevicePolicyData policy = getUserData(userId);
        if (who != null) {
            ActiveAdmin admin = policy.mAdminMap.get(who);
            if (admin == null) {
                throw new SecurityException("No active admin " + who);
            } else if (admin.getUid() != uid) {
                throw new SecurityException("Admin " + who + " is not owned by uid " + uid);
            } else if (isActiveAdminWithPolicyForUserLocked(admin, reqPolicy, userId)) {
                return admin;
            } else {
                return null;
            }
        }
        Iterator<ActiveAdmin> it = policy.mAdminList.iterator();
        while (it.hasNext()) {
            ActiveAdmin admin2 = it.next();
            if (admin2.getUid() == uid && isActiveAdminWithPolicyForUserLocked(admin2, reqPolicy, userId)) {
                return admin2;
            }
        }
        return null;
    }

    @VisibleForTesting
    boolean isActiveAdminWithPolicyForUserLocked(ActiveAdmin admin, int reqPolicy, int userId) {
        ensureLocked();
        boolean ownsDevice = isDeviceOwner(admin.info.getComponent(), userId);
        boolean ownsProfile = isProfileOwner(admin.info.getComponent(), userId);
        if (reqPolicy == -2) {
            return ownsDevice;
        }
        if (reqPolicy == -1) {
            return ownsDevice || ownsProfile;
        }
        return admin.info.usesPolicy(reqPolicy);
    }

    void sendAdminCommandLocked(ActiveAdmin admin, String action) {
        sendAdminCommandLocked(admin, action, null);
    }

    void sendAdminCommandLocked(ActiveAdmin admin, String action, BroadcastReceiver result) {
        sendAdminCommandLocked(admin, action, (Bundle) null, result);
    }

    void sendAdminCommandLocked(ActiveAdmin admin, String action, Bundle adminExtras, BroadcastReceiver result) {
        sendAdminCommandLocked(admin, action, adminExtras, result, false);
    }

    boolean sendAdminCommandLocked(ActiveAdmin admin, String action, Bundle adminExtras, BroadcastReceiver result, boolean inForeground) {
        Intent intent = new Intent(action);
        intent.setComponent(admin.info.getComponent());
        if (UserManager.isDeviceInDemoMode(this.mContext)) {
            intent.addFlags(268435456);
        }
        if (action.equals("android.app.action.ACTION_PASSWORD_EXPIRING")) {
            intent.putExtra("expiration", admin.passwordExpirationDate);
        }
        if (inForeground) {
            intent.addFlags(268435456);
        }
        if (adminExtras != null) {
            intent.putExtras(adminExtras);
        }
        if (this.mInjector.getPackageManager().queryBroadcastReceiversAsUser(intent, 268435456, admin.getUserHandle()).isEmpty()) {
            return false;
        }
        if (result != null) {
            this.mContext.sendOrderedBroadcastAsUser(intent, admin.getUserHandle(), null, result, this.mHandler, -1, null, null);
            return true;
        }
        this.mContext.sendBroadcastAsUser(intent, admin.getUserHandle());
        return true;
    }

    void sendAdminCommandLocked(String action, int reqPolicy, int userHandle, Bundle adminExtras) {
        DevicePolicyData policy = getUserData(userHandle);
        int count = policy.mAdminList.size();
        for (int i = 0; i < count; i++) {
            ActiveAdmin admin = policy.mAdminList.get(i);
            if (admin.info.usesPolicy(reqPolicy)) {
                sendAdminCommandLocked(admin, action, adminExtras, (BroadcastReceiver) null);
            }
        }
    }

    private void sendAdminCommandToSelfAndProfilesLocked(String action, int reqPolicy, int userHandle, Bundle adminExtras) {
        int[] profileIds = this.mUserManager.getProfileIdsWithDisabled(userHandle);
        for (int profileId : profileIds) {
            sendAdminCommandLocked(action, reqPolicy, profileId, adminExtras);
        }
    }

    private void sendAdminCommandForLockscreenPoliciesLocked(String action, int reqPolicy, int userHandle) {
        Bundle extras = new Bundle();
        extras.putParcelable("android.intent.extra.USER", UserHandle.of(userHandle));
        if (isSeparateProfileChallengeEnabled(userHandle)) {
            sendAdminCommandLocked(action, reqPolicy, userHandle, extras);
        } else {
            sendAdminCommandToSelfAndProfilesLocked(action, reqPolicy, userHandle, extras);
        }
    }

    void removeActiveAdminLocked(final ComponentName adminReceiver, final int userHandle) {
        ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
        DevicePolicyData policy = getUserData(userHandle);
        if (admin != null && !policy.mRemovingAdmins.contains(adminReceiver)) {
            policy.mRemovingAdmins.add(adminReceiver);
            sendAdminCommandLocked(admin, "android.app.action.DEVICE_ADMIN_DISABLED", new BroadcastReceiver() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.5
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    DevicePolicyManagerService.this.removeAdminArtifacts(adminReceiver, userHandle);
                    DevicePolicyManagerService.this.removePackageIfRequired(adminReceiver.getPackageName(), userHandle);
                }
            });
        }
    }

    public DeviceAdminInfo findAdmin(ComponentName adminName, int userHandle, boolean throwForMissingPermission) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            ActivityInfo ai = null;
            try {
                ai = this.mIPackageManager.getReceiverInfo(adminName, 819328, userHandle);
            } catch (RemoteException e) {
            }
            if (ai == null) {
                throw new IllegalArgumentException("Unknown admin: " + adminName);
            }
            if (!"android.permission.BIND_DEVICE_ADMIN".equals(ai.permission)) {
                String message = "DeviceAdminReceiver " + adminName + " must be protected with android.permission.BIND_DEVICE_ADMIN";
                Slog.w(LOG_TAG, message);
                if (throwForMissingPermission && ai.applicationInfo.targetSdkVersion > 23) {
                    throw new IllegalArgumentException(message);
                }
            }
            try {
                return new DeviceAdminInfo(this.mContext, ai);
            } catch (IOException | XmlPullParserException e2) {
                Slog.w(LOG_TAG, "Bad device admin requested for user=" + userHandle + ": " + adminName, e2);
                return null;
            }
        }
        return null;
    }

    private File getPolicyFileDirectory(int userId) {
        if (userId == 0) {
            return new File(this.mInjector.getDevicePolicyFilePathForSystemUser());
        }
        return this.mInjector.environmentGetUserSystemDirectory(userId);
    }

    private JournaledFile makeJournaledFile(int userId) {
        String base = new File(getPolicyFileDirectory(userId), DEVICE_POLICIES_XML).getAbsolutePath();
        File file = new File(base);
        return new JournaledFile(file, new File(base + ".tmp"));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveSettingsLocked(int userHandle) {
        DevicePolicyData policy = getUserData(userHandle);
        JournaledFile journal = makeJournaledFile(userHandle);
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(journal.chooseForWrite(), false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, ProcessManagerPolicy.ProcessPolicyKey.KEY_POLICIES);
            if (policy.mRestrictionsProvider != null) {
                out.attribute(null, ATTR_PERMISSION_PROVIDER, policy.mRestrictionsProvider.flattenToString());
            }
            if (policy.mUserSetupComplete) {
                out.attribute(null, ATTR_SETUP_COMPLETE, Boolean.toString(true));
            }
            if (policy.mPaired) {
                out.attribute(null, ATTR_DEVICE_PAIRED, Boolean.toString(true));
            }
            if (policy.mDeviceProvisioningConfigApplied) {
                out.attribute(null, ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED, Boolean.toString(true));
            }
            if (policy.mUserProvisioningState != 0) {
                out.attribute(null, ATTR_PROVISIONING_STATE, Integer.toString(policy.mUserProvisioningState));
            }
            if (policy.mPermissionPolicy != 0) {
                out.attribute(null, ATTR_PERMISSION_POLICY, Integer.toString(policy.mPermissionPolicy));
            }
            for (int i = 0; i < policy.mDelegationMap.size(); i++) {
                String delegatePackage = policy.mDelegationMap.keyAt(i);
                List<String> scopes = policy.mDelegationMap.valueAt(i);
                for (String scope : scopes) {
                    out.startTag(null, "delegation");
                    out.attribute(null, "delegatePackage", delegatePackage);
                    out.attribute(null, "scope", scope);
                    out.endTag(null, "delegation");
                }
            }
            int N = policy.mAdminList.size();
            for (int i2 = 0; i2 < N; i2++) {
                ActiveAdmin ap = policy.mAdminList.get(i2);
                if (ap != null) {
                    out.startTag(null, "admin");
                    out.attribute(null, "name", ap.info.getComponent().flattenToString());
                    ap.writeToXml(out);
                    out.endTag(null, "admin");
                }
            }
            int i3 = policy.mPasswordOwner;
            if (i3 >= 0) {
                out.startTag(null, "password-owner");
                out.attribute(null, ATTR_VALUE, Integer.toString(policy.mPasswordOwner));
                out.endTag(null, "password-owner");
            }
            if (policy.mFailedPasswordAttempts != 0) {
                out.startTag(null, "failed-password-attempts");
                out.attribute(null, ATTR_VALUE, Integer.toString(policy.mFailedPasswordAttempts));
                out.endTag(null, "failed-password-attempts");
            }
            if (!this.mInjector.storageManagerIsFileBasedEncryptionEnabled()) {
                out.startTag(null, TAG_PASSWORD_VALIDITY);
                out.attribute(null, ATTR_VALUE, Boolean.toString(policy.mPasswordValidAtLastCheckpoint));
                out.endTag(null, TAG_PASSWORD_VALIDITY);
            }
            for (int i4 = 0; i4 < policy.mAcceptedCaCertificates.size(); i4++) {
                out.startTag(null, TAG_ACCEPTED_CA_CERTIFICATES);
                out.attribute(null, "name", policy.mAcceptedCaCertificates.valueAt(i4));
                out.endTag(null, TAG_ACCEPTED_CA_CERTIFICATES);
            }
            for (int i5 = 0; i5 < policy.mLockTaskPackages.size(); i5++) {
                String component = policy.mLockTaskPackages.get(i5);
                out.startTag(null, TAG_LOCK_TASK_COMPONENTS);
                out.attribute(null, "name", component);
                out.endTag(null, TAG_LOCK_TASK_COMPONENTS);
            }
            int i6 = policy.mLockTaskFeatures;
            if (i6 != 0) {
                out.startTag(null, TAG_LOCK_TASK_FEATURES);
                out.attribute(null, ATTR_VALUE, Integer.toString(policy.mLockTaskFeatures));
                out.endTag(null, TAG_LOCK_TASK_FEATURES);
            }
            if (policy.mStatusBarDisabled) {
                out.startTag(null, TAG_STATUS_BAR);
                out.attribute(null, ATTR_DISABLED, Boolean.toString(policy.mStatusBarDisabled));
                out.endTag(null, TAG_STATUS_BAR);
            }
            if (policy.doNotAskCredentialsOnBoot) {
                out.startTag(null, DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML);
                out.endTag(null, DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML);
            }
            for (String id : policy.mAffiliationIds) {
                out.startTag(null, TAG_AFFILIATION_ID);
                out.attribute(null, ATTR_ID, id);
                out.endTag(null, TAG_AFFILIATION_ID);
            }
            if (policy.mLastSecurityLogRetrievalTime >= 0) {
                out.startTag(null, TAG_LAST_SECURITY_LOG_RETRIEVAL);
                out.attribute(null, ATTR_VALUE, Long.toString(policy.mLastSecurityLogRetrievalTime));
                out.endTag(null, TAG_LAST_SECURITY_LOG_RETRIEVAL);
            }
            if (policy.mLastBugReportRequestTime >= 0) {
                out.startTag(null, TAG_LAST_BUG_REPORT_REQUEST);
                out.attribute(null, ATTR_VALUE, Long.toString(policy.mLastBugReportRequestTime));
                out.endTag(null, TAG_LAST_BUG_REPORT_REQUEST);
            }
            if (policy.mLastNetworkLogsRetrievalTime >= 0) {
                out.startTag(null, TAG_LAST_NETWORK_LOG_RETRIEVAL);
                out.attribute(null, ATTR_VALUE, Long.toString(policy.mLastNetworkLogsRetrievalTime));
                out.endTag(null, TAG_LAST_NETWORK_LOG_RETRIEVAL);
            }
            if (policy.mAdminBroadcastPending) {
                out.startTag(null, TAG_ADMIN_BROADCAST_PENDING);
                out.attribute(null, ATTR_VALUE, Boolean.toString(policy.mAdminBroadcastPending));
                out.endTag(null, TAG_ADMIN_BROADCAST_PENDING);
            }
            if (policy.mInitBundle != null) {
                out.startTag(null, TAG_INITIALIZATION_BUNDLE);
                policy.mInitBundle.saveToXml(out);
                out.endTag(null, TAG_INITIALIZATION_BUNDLE);
            }
            if (policy.mPasswordTokenHandle != 0) {
                out.startTag(null, TAG_PASSWORD_TOKEN_HANDLE);
                out.attribute(null, ATTR_VALUE, Long.toString(policy.mPasswordTokenHandle));
                out.endTag(null, TAG_PASSWORD_TOKEN_HANDLE);
            }
            if (policy.mCurrentInputMethodSet) {
                out.startTag(null, TAG_CURRENT_INPUT_METHOD_SET);
                out.endTag(null, TAG_CURRENT_INPUT_METHOD_SET);
            }
            for (String cert : policy.mOwnerInstalledCaCerts) {
                out.startTag(null, TAG_OWNER_INSTALLED_CA_CERT);
                out.attribute(null, ATTR_ALIAS, cert);
                out.endTag(null, TAG_OWNER_INSTALLED_CA_CERT);
            }
            out.endTag(null, ProcessManagerPolicy.ProcessPolicyKey.KEY_POLICIES);
            out.endDocument();
            stream.flush();
            FileUtils.sync(stream);
            stream.close();
            journal.commit();
            sendChangedNotification(userHandle);
        } catch (IOException | XmlPullParserException e) {
            Slog.w(LOG_TAG, "failed writing file", e);
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e2) {
                }
            }
            journal.rollback();
        }
    }

    private void sendChangedNotification(int userHandle) {
        Intent intent = new Intent("android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED");
        intent.setFlags(1073741824);
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            this.mContext.sendBroadcastAsUser(intent, new UserHandle(userHandle));
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    /* JADX WARN: Not initialized variable reg: 18, insn: 0x0400: MOVE  (r5 I:??[OBJECT, ARRAY]) = (r18 I:??[OBJECT, ARRAY] A[D('stream' java.io.FileInputStream)]), block:B:181:0x0400 */
    /* JADX WARN: Not initialized variable reg: 18, insn: 0x0404: MOVE  (r5 I:??[OBJECT, ARRAY]) = (r18 I:??[OBJECT, ARRAY] A[D('stream' java.io.FileInputStream)]), block:B:183:0x0404 */
    /* JADX WARN: Removed duplicated region for block: B:202:0x0447  */
    /* JADX WARN: Removed duplicated region for block: B:205:0x045e  */
    /* JADX WARN: Removed duplicated region for block: B:209:0x0433 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:233:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void loadSettingsLocked(com.android.server.devicepolicy.DevicePolicyManagerService.DevicePolicyData r25, int r26) {
        /*
            Method dump skipped, instructions count: 1124
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.loadSettingsLocked(com.android.server.devicepolicy.DevicePolicyManagerService$DevicePolicyData, int):void");
    }

    private void updateLockTaskPackagesLocked(List<String> packages, int userId) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            this.mInjector.getIActivityManager().updateLockTaskPackages(userId, (String[]) packages.toArray(new String[packages.size()]));
        } catch (RemoteException e) {
        } catch (Throwable th) {
            this.mInjector.binderRestoreCallingIdentity(ident);
            throw th;
        }
        this.mInjector.binderRestoreCallingIdentity(ident);
    }

    private void updateLockTaskFeaturesLocked(int flags, int userId) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            this.mInjector.getIActivityManager().updateLockTaskFeatures(userId, flags);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            this.mInjector.binderRestoreCallingIdentity(ident);
            throw th;
        }
        this.mInjector.binderRestoreCallingIdentity(ident);
    }

    private void updateDeviceOwnerLocked() {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            ComponentName deviceOwnerComponent = this.mOwners.getDeviceOwnerComponent();
            if (deviceOwnerComponent != null) {
                this.mInjector.getIActivityManager().updateDeviceOwner(deviceOwnerComponent.getPackageName());
            }
        } catch (RemoteException e) {
        } catch (Throwable th) {
            this.mInjector.binderRestoreCallingIdentity(ident);
            throw th;
        }
        this.mInjector.binderRestoreCallingIdentity(ident);
    }

    static void validateQualityConstant(int quality) {
        if (quality == 0 || quality == 32768 || quality == 65536 || quality == 131072 || quality == 196608 || quality == 262144 || quality == 327680 || quality == 393216 || quality == 524288) {
            return;
        }
        throw new IllegalArgumentException("Invalid quality constant: 0x" + Integer.toHexString(quality));
    }

    void validatePasswordOwnerLocked(DevicePolicyData policy) {
        if (policy.mPasswordOwner >= 0) {
            boolean haveOwner = false;
            int i = policy.mAdminList.size() - 1;
            while (true) {
                if (i < 0) {
                    break;
                } else if (policy.mAdminList.get(i).getUid() != policy.mPasswordOwner) {
                    i--;
                } else {
                    haveOwner = true;
                    break;
                }
            }
            if (!haveOwner) {
                Slog.w(LOG_TAG, "Previous password owner " + policy.mPasswordOwner + " no longer active; disabling");
                policy.mPasswordOwner = -1;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    @VisibleForTesting
    public void systemReady(int phase) {
        if (!this.mHasFeature) {
            return;
        }
        if (phase == 480) {
            onLockSettingsReady();
            loadAdminDataAsync();
            this.mOwners.systemReady();
        } else if (phase == 550) {
            maybeStartSecurityLogMonitorOnActivityManagerReady();
        } else if (phase == 1000) {
            ensureDeviceOwnerUserStarted();
        }
    }

    private void onLockSettingsReady() {
        List<String> packageList;
        getUserData(0);
        loadOwners();
        cleanUpOldUsers();
        maybeSetDefaultProfileOwnerUserRestrictions();
        handleStartUser(0);
        maybeLogStart();
        this.mSetupContentObserver.register();
        updateUserSetupCompleteAndPaired();
        synchronized (getLockObject()) {
            packageList = getKeepUninstalledPackagesLocked();
        }
        if (packageList != null) {
            this.mInjector.getPackageManagerInternal().setKeepUninstalledPackages(packageList);
        }
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            if (deviceOwner != null) {
                this.mUserManagerInternal.setForceEphemeralUsers(deviceOwner.forceEphemeralUsers);
                ActivityManagerInternal activityManagerInternal = this.mInjector.getActivityManagerInternal();
                activityManagerInternal.setSwitchingFromSystemUserMessage(deviceOwner.startUserSessionMessage);
                activityManagerInternal.setSwitchingToSystemUserMessage(deviceOwner.endUserSessionMessage);
            }
            revertTransferOwnershipIfNecessaryLocked();
        }
    }

    private void revertTransferOwnershipIfNecessaryLocked() {
        if (!this.mTransferOwnershipMetadataManager.metadataFileExists()) {
            return;
        }
        Slog.e(LOG_TAG, "Owner transfer metadata file exists! Reverting transfer.");
        TransferOwnershipMetadataManager.Metadata metadata = this.mTransferOwnershipMetadataManager.loadMetadataFile();
        if (metadata.adminType.equals(LOG_TAG_PROFILE_OWNER)) {
            transferProfileOwnershipLocked(metadata.targetComponent, metadata.sourceComponent, metadata.userId);
            deleteTransferOwnershipMetadataFileLocked();
            deleteTransferOwnershipBundleLocked(metadata.userId);
        } else if (metadata.adminType.equals(LOG_TAG_DEVICE_OWNER)) {
            transferDeviceOwnershipLocked(metadata.targetComponent, metadata.sourceComponent, metadata.userId);
            deleteTransferOwnershipMetadataFileLocked();
            deleteTransferOwnershipBundleLocked(metadata.userId);
        }
        updateSystemUpdateFreezePeriodsRecord(true);
    }

    private void maybeLogStart() {
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }
        String verifiedBootState = this.mInjector.systemPropertiesGet("ro.boot.verifiedbootstate");
        String verityMode = this.mInjector.systemPropertiesGet("ro.boot.veritymode");
        SecurityLog.writeEvent(210009, new Object[]{verifiedBootState, verityMode});
    }

    private void ensureDeviceOwnerUserStarted() {
        synchronized (getLockObject()) {
            if (this.mOwners.hasDeviceOwner()) {
                int userId = this.mOwners.getDeviceOwnerUserId();
                if (userId != 0) {
                    try {
                        this.mInjector.getIActivityManager().startUserInBackground(userId);
                    } catch (RemoteException e) {
                        Slog.w(LOG_TAG, "Exception starting user", e);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void handleStartUser(int userId) {
        updateScreenCaptureDisabled(userId, getScreenCaptureDisabled(null, userId));
        pushUserRestrictions(userId);
        startOwnerService(userId, "start-user");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void handleUnlockUser(int userId) {
        startOwnerService(userId, "unlock-user");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void handleStopUser(int userId) {
        stopOwnerService(userId, "stop-user");
    }

    private void startOwnerService(int userId, String actionForLog) {
        ComponentName owner = getOwnerComponent(userId);
        if (owner != null) {
            this.mDeviceAdminServiceController.startServiceForOwner(owner.getPackageName(), userId, actionForLog);
        }
    }

    private void stopOwnerService(int userId, String actionForLog) {
        this.mDeviceAdminServiceController.stopServiceForOwner(userId, actionForLog);
    }

    private void cleanUpOldUsers() {
        Collection<? extends Integer> usersWithProfileOwners;
        ArraySet arraySet;
        synchronized (getLockObject()) {
            usersWithProfileOwners = this.mOwners.getProfileOwnerKeys();
            arraySet = new ArraySet();
            for (int i = 0; i < this.mUserData.size(); i++) {
                arraySet.add(Integer.valueOf(this.mUserData.keyAt(i)));
            }
        }
        List<UserInfo> allUsers = this.mUserManager.getUsers();
        Set<Integer> deletedUsers = new ArraySet<>();
        deletedUsers.addAll(usersWithProfileOwners);
        deletedUsers.addAll(arraySet);
        for (UserInfo userInfo : allUsers) {
            deletedUsers.remove(Integer.valueOf(userInfo.id));
        }
        for (Integer userId : deletedUsers) {
            removeUserData(userId.intValue());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePasswordExpirationNotification(int userHandle) {
        Bundle adminExtras = new Bundle();
        adminExtras.putParcelable("android.intent.extra.USER", UserHandle.of(userHandle));
        synchronized (getLockObject()) {
            long now = System.currentTimeMillis();
            List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, false);
            int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (admin.info.usesPolicy(6) && admin.passwordExpirationTimeout > 0 && now >= admin.passwordExpirationDate - EXPIRATION_GRACE_PERIOD_MS && admin.passwordExpirationDate > 0) {
                    sendAdminCommandLocked(admin, "android.app.action.ACTION_PASSWORD_EXPIRING", adminExtras, (BroadcastReceiver) null);
                }
            }
            setExpirationAlarmCheckLocked(this.mContext, userHandle, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onInstalledCertificatesChanged(UserHandle userHandle, Collection<String> installedCertificates) {
        if (!this.mHasFeature) {
            return;
        }
        enforceManageUsers();
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle.getIdentifier());
            boolean changed = false | policy.mAcceptedCaCertificates.retainAll(installedCertificates);
            if (changed | policy.mOwnerInstalledCaCerts.retainAll(installedCertificates)) {
                saveSettingsLocked(userHandle.getIdentifier());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public Set<String> getAcceptedCaCertificates(UserHandle userHandle) {
        ArraySet<String> arraySet;
        if (!this.mHasFeature) {
            return Collections.emptySet();
        }
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle.getIdentifier());
            arraySet = policy.mAcceptedCaCertificates;
        }
        return arraySet;
    }

    public void setActiveAdmin(ComponentName adminReceiver, boolean refreshing, int userHandle) {
        if (!this.mHasFeature) {
            return;
        }
        setActiveAdmin(adminReceiver, refreshing, userHandle, null);
    }

    private void setActiveAdmin(ComponentName adminReceiver, boolean refreshing, int userHandle, Bundle onEnableData) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_DEVICE_ADMINS", null);
        enforceFullCrossUsersPermission(userHandle);
        DevicePolicyData policy = getUserData(userHandle);
        DeviceAdminInfo info = findAdmin(adminReceiver, userHandle, true);
        synchronized (getLockObject()) {
            try {
                try {
                    checkActiveAdminPrecondition(adminReceiver, info, policy);
                    long ident = this.mInjector.binderClearCallingIdentity();
                    try {
                        ActiveAdmin existingAdmin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
                        if (!refreshing && existingAdmin != null) {
                            throw new IllegalArgumentException("Admin is already added");
                        }
                        int i = 0;
                        ActiveAdmin newAdmin = new ActiveAdmin(info, false);
                        newAdmin.testOnlyAdmin = existingAdmin != null ? existingAdmin.testOnlyAdmin : isPackageTestOnly(adminReceiver.getPackageName(), userHandle);
                        policy.mAdminMap.put(adminReceiver, newAdmin);
                        int replaceIndex = -1;
                        int N = policy.mAdminList.size();
                        while (true) {
                            if (i >= N) {
                                break;
                            }
                            ActiveAdmin oldAdmin = policy.mAdminList.get(i);
                            if (!oldAdmin.info.getComponent().equals(adminReceiver)) {
                                i++;
                            } else {
                                replaceIndex = i;
                                break;
                            }
                        }
                        if (replaceIndex == -1) {
                            policy.mAdminList.add(newAdmin);
                            enableIfNecessary(info.getPackageName(), userHandle);
                            this.mUsageStatsManagerInternal.onActiveAdminAdded(adminReceiver.getPackageName(), userHandle);
                        } else {
                            policy.mAdminList.set(replaceIndex, newAdmin);
                        }
                        saveSettingsLocked(userHandle);
                        try {
                            sendAdminCommandLocked(newAdmin, "android.app.action.DEVICE_ADMIN_ENABLED", onEnableData, (BroadcastReceiver) null);
                            this.mInjector.binderRestoreCallingIdentity(ident);
                        } catch (Throwable th) {
                            th = th;
                            this.mInjector.binderRestoreCallingIdentity(ident);
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                    throw th;
                }
            } catch (Throwable th4) {
                th = th4;
            }
        }
    }

    private void loadAdminDataAsync() {
        this.mInjector.postOnSystemServerInitThreadPool(new Runnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$_Nw-YGl5ncBg-LJs8W81WNW6xoU
            @Override // java.lang.Runnable
            public final void run() {
                DevicePolicyManagerService.lambda$loadAdminDataAsync$0(DevicePolicyManagerService.this);
            }
        });
    }

    public static /* synthetic */ void lambda$loadAdminDataAsync$0(DevicePolicyManagerService devicePolicyManagerService) {
        devicePolicyManagerService.pushActiveAdminPackages();
        devicePolicyManagerService.mUsageStatsManagerInternal.onAdminDataAvailable();
        devicePolicyManagerService.pushAllMeteredRestrictedPackages();
        devicePolicyManagerService.mInjector.getNetworkPolicyManagerInternal().onAdminDataAvailable();
    }

    private void pushActiveAdminPackages() {
        synchronized (getLockObject()) {
            List<UserInfo> users = this.mUserManager.getUsers();
            for (int i = users.size() - 1; i >= 0; i--) {
                int userId = users.get(i).id;
                this.mUsageStatsManagerInternal.setActiveAdminApps(getActiveAdminPackagesLocked(userId), userId);
            }
        }
    }

    private void pushAllMeteredRestrictedPackages() {
        synchronized (getLockObject()) {
            List<UserInfo> users = this.mUserManager.getUsers();
            for (int i = users.size() - 1; i >= 0; i--) {
                int userId = users.get(i).id;
                this.mInjector.getNetworkPolicyManagerInternal().setMeteredRestrictedPackagesAsync(getMeteredDisabledPackagesLocked(userId), userId);
            }
        }
    }

    private void pushActiveAdminPackagesLocked(int userId) {
        this.mUsageStatsManagerInternal.setActiveAdminApps(getActiveAdminPackagesLocked(userId), userId);
    }

    private Set<String> getActiveAdminPackagesLocked(int userId) {
        DevicePolicyData policy = getUserData(userId);
        Set<String> adminPkgs = null;
        for (int i = policy.mAdminList.size() - 1; i >= 0; i--) {
            String pkgName = policy.mAdminList.get(i).info.getPackageName();
            if (adminPkgs == null) {
                adminPkgs = new ArraySet<>();
            }
            adminPkgs.add(pkgName);
        }
        return adminPkgs;
    }

    private void transferActiveAdminUncheckedLocked(ComponentName incomingReceiver, ComponentName outgoingReceiver, int userHandle) {
        DevicePolicyData policy = getUserData(userHandle);
        if (!policy.mAdminMap.containsKey(outgoingReceiver) && policy.mAdminMap.containsKey(incomingReceiver)) {
            return;
        }
        DeviceAdminInfo incomingDeviceInfo = findAdmin(incomingReceiver, userHandle, true);
        ActiveAdmin adminToTransfer = policy.mAdminMap.get(outgoingReceiver);
        int oldAdminUid = adminToTransfer.getUid();
        adminToTransfer.transfer(incomingDeviceInfo);
        policy.mAdminMap.remove(outgoingReceiver);
        policy.mAdminMap.put(incomingReceiver, adminToTransfer);
        if (policy.mPasswordOwner == oldAdminUid) {
            policy.mPasswordOwner = adminToTransfer.getUid();
        }
        saveSettingsLocked(userHandle);
        sendAdminCommandLocked(adminToTransfer, "android.app.action.DEVICE_ADMIN_ENABLED", (Bundle) null, (BroadcastReceiver) null);
    }

    private void checkActiveAdminPrecondition(ComponentName adminReceiver, DeviceAdminInfo info, DevicePolicyData policy) {
        if (info == null) {
            throw new IllegalArgumentException("Bad admin: " + adminReceiver);
        } else if (!info.getActivityInfo().applicationInfo.isInternal()) {
            throw new IllegalArgumentException("Only apps in internal storage can be active admin: " + adminReceiver);
        } else if (info.getActivityInfo().applicationInfo.isInstantApp()) {
            throw new IllegalArgumentException("Instant apps cannot be device admins: " + adminReceiver);
        } else if (policy.mRemovingAdmins.contains(adminReceiver)) {
            throw new IllegalArgumentException("Trying to set an admin which is being removed");
        }
    }

    public boolean isAdminActive(ComponentName adminReceiver, int userHandle) {
        boolean z;
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                z = getActiveAdminUncheckedLocked(adminReceiver, userHandle) != null;
            }
            return z;
        }
        return false;
    }

    public boolean isRemovingAdmin(ComponentName adminReceiver, int userHandle) {
        boolean contains;
        if (!this.mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            DevicePolicyData policyData = getUserData(userHandle);
            contains = policyData.mRemovingAdmins.contains(adminReceiver);
        }
        return contains;
    }

    public boolean hasGrantedPolicy(ComponentName adminReceiver, int policyId, int userHandle) {
        boolean usesPolicy;
        if (!this.mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            ActiveAdmin administrator = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
            if (administrator == null) {
                throw new SecurityException("No active admin " + adminReceiver);
            }
            usesPolicy = administrator.info.usesPolicy(policyId);
        }
        return usesPolicy;
    }

    public List<ComponentName> getActiveAdmins(int userHandle) {
        if (!this.mHasFeature) {
            return Collections.EMPTY_LIST;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle);
            int N = policy.mAdminList.size();
            if (N <= 0) {
                return null;
            }
            ArrayList<ComponentName> res = new ArrayList<>(N);
            for (int i = 0; i < N; i++) {
                res.add(policy.mAdminList.get(i).info.getComponent());
            }
            return res;
        }
    }

    public boolean packageHasActiveAdmins(String packageName, int userHandle) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                DevicePolicyData policy = getUserData(userHandle);
                int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    if (policy.mAdminList.get(i).info.getPackageName().equals(packageName)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    public void forceRemoveActiveAdmin(ComponentName adminReceiver, int userHandle) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(adminReceiver, "ComponentName is null");
        enforceShell("forceRemoveActiveAdmin");
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                if (!isAdminTestOnlyLocked(adminReceiver, userHandle)) {
                    throw new SecurityException("Attempt to remove non-test admin " + adminReceiver + " " + userHandle);
                }
                if (isDeviceOwner(adminReceiver, userHandle)) {
                    clearDeviceOwnerLocked(getDeviceOwnerAdminLocked(), userHandle);
                }
                if (isProfileOwner(adminReceiver, userHandle)) {
                    ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle, false);
                    clearProfileOwnerLocked(admin, userHandle);
                }
            }
            removeAdminArtifacts(adminReceiver, userHandle);
            Slog.i(LOG_TAG, "Admin " + adminReceiver + " removed from user " + userHandle);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void clearDeviceOwnerUserRestrictionLocked(UserHandle userHandle) {
        if (this.mUserManager.hasUserRestriction("no_add_user", userHandle)) {
            this.mUserManager.setUserRestriction("no_add_user", false, userHandle);
        }
    }

    private boolean isPackageTestOnly(String packageName, int userHandle) {
        try {
            ApplicationInfo ai = this.mInjector.getIPackageManager().getApplicationInfo(packageName, 786432, userHandle);
            if (ai != null) {
                return (ai.flags & 256) != 0;
            }
            throw new IllegalStateException("Couldn't find package: " + packageName + " on user " + userHandle);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isAdminTestOnlyLocked(ComponentName who, int userHandle) {
        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
        return admin != null && admin.testOnlyAdmin;
    }

    private void enforceShell(String method) {
        int callingUid = this.mInjector.binderGetCallingUid();
        if (callingUid != 2000 && callingUid != 0) {
            throw new SecurityException("Non-shell user attempted to call " + method);
        }
    }

    public void removeActiveAdmin(ComponentName adminReceiver, int userHandle) {
        if (!this.mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceUserUnlocked(userHandle);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
            if (admin == null) {
                return;
            }
            if (!isDeviceOwner(adminReceiver, userHandle) && !isProfileOwner(adminReceiver, userHandle)) {
                if (admin.getUid() != this.mInjector.binderGetCallingUid()) {
                    this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_DEVICE_ADMINS", null);
                }
                long ident = this.mInjector.binderClearCallingIdentity();
                removeActiveAdminLocked(adminReceiver, userHandle);
                this.mInjector.binderRestoreCallingIdentity(ident);
                return;
            }
            Slog.e(LOG_TAG, "Device/profile owner cannot be removed: component=" + adminReceiver);
        }
    }

    public boolean isSeparateProfileChallengeAllowed(int userHandle) {
        if (!isCallerWithSystemUid()) {
            throw new SecurityException("Caller must be system");
        }
        ComponentName profileOwner = getProfileOwner(userHandle);
        return profileOwner != null && getTargetSdk(profileOwner.getPackageName(), userHandle) > 23;
    }

    public void setPasswordQuality(ComponentName who, int quality, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        validateQualityConstant(quality);
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.quality != quality) {
                metrics.quality = quality;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    private void updatePasswordValidityCheckpointLocked(int userHandle, boolean parent) {
        int credentialOwner = getCredentialOwner(userHandle, parent);
        DevicePolicyData policy = getUserData(credentialOwner);
        PasswordMetrics metrics = getUserPasswordMetricsLocked(credentialOwner);
        if (metrics == null) {
            metrics = new PasswordMetrics();
        }
        policy.mPasswordValidAtLastCheckpoint = isPasswordSufficientForUserWithoutCheckpointLocked(metrics, userHandle, parent);
        saveSettingsLocked(credentialOwner);
    }

    public int getPasswordQuality(ComponentName who, int userHandle, boolean parent) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                int mode = 0;
                try {
                    if (who != null) {
                        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                        return admin != null ? admin.minimumPasswordMetrics.quality : 0;
                    }
                    List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                    int N = admins.size();
                    for (int i = 0; i < N; i++) {
                        ActiveAdmin admin2 = admins.get(i);
                        if (mode < admin2.minimumPasswordMetrics.quality) {
                            mode = admin2.minimumPasswordMetrics.quality;
                        }
                    }
                    return mode;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        return 0;
    }

    private List<ActiveAdmin> getActiveAdminsForLockscreenPoliciesLocked(int userHandle, boolean parent) {
        if (!parent && isSeparateProfileChallengeEnabled(userHandle)) {
            return getUserDataUnchecked(userHandle).mAdminList;
        }
        ArrayList<ActiveAdmin> admins = new ArrayList<>();
        for (UserInfo userInfo : this.mUserManager.getProfiles(userHandle)) {
            DevicePolicyData policy = getUserData(userInfo.id);
            if (!userInfo.isManagedProfile()) {
                admins.addAll(policy.mAdminList);
            } else {
                boolean hasSeparateChallenge = isSeparateProfileChallengeEnabled(userInfo.id);
                int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (admin.hasParentActiveAdmin()) {
                        admins.add(admin.getParentActiveAdmin());
                    }
                    if (!hasSeparateChallenge) {
                        admins.add(admin);
                    }
                }
            }
        }
        return admins;
    }

    private boolean isSeparateProfileChallengeEnabled(int userHandle) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mLockPatternUtils.isSeparateProfileChallengeEnabled(userHandle);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    public void setPasswordMinimumLength(ComponentName who, int length, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.length != length) {
                metrics.length = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    public int getPasswordMinimumLength(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$NzTaj70nEECGXhr52RbDyXK_fPU
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).minimumPasswordMetrics.length);
                return valueOf;
            }
        }, 0);
    }

    public void setPasswordHistoryLength(ComponentName who, int length, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            if (ap.passwordHistoryLength != length) {
                ap.passwordHistoryLength = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            int affectedUserId = parent ? getProfileParentId(userId) : userId;
            SecurityLog.writeEvent(210018, new Object[]{who.getPackageName(), Integer.valueOf(userId), Integer.valueOf(affectedUserId), Integer.valueOf(length)});
        }
    }

    public int getPasswordHistoryLength(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$kf4uUzLBApkNlieB7zr8MNfAxbg
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).passwordHistoryLength);
                return valueOf;
            }
        }, 0);
    }

    public void setPasswordExpirationTimeout(ComponentName who, long timeout, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkArgumentNonnegative(timeout, "Timeout must be >= 0 ms");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 6, parent);
            long expiration = timeout > 0 ? System.currentTimeMillis() + timeout : 0L;
            ap.passwordExpirationDate = expiration;
            ap.passwordExpirationTimeout = timeout;
            if (timeout > 0) {
                Slog.w(LOG_TAG, "setPasswordExpiration(): password will expire on " + DateFormat.getDateTimeInstance(2, 2).format(new Date(expiration)));
            }
            saveSettingsLocked(userHandle);
            setExpirationAlarmCheckLocked(this.mContext, userHandle, parent);
        }
        if (SecurityLog.isLoggingEnabled()) {
            int affectedUserId = parent ? getProfileParentId(userHandle) : userHandle;
            SecurityLog.writeEvent(210016, new Object[]{who.getPackageName(), Integer.valueOf(userHandle), Integer.valueOf(affectedUserId), Long.valueOf(timeout)});
        }
    }

    public long getPasswordExpirationTimeout(ComponentName who, int userHandle, boolean parent) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                long timeout = 0;
                try {
                    if (who != null) {
                        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                        return admin != null ? admin.passwordExpirationTimeout : 0L;
                    }
                    List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                    int N = admins.size();
                    for (int i = 0; i < N; i++) {
                        ActiveAdmin admin2 = admins.get(i);
                        if (timeout == 0 || (admin2.passwordExpirationTimeout != 0 && timeout > admin2.passwordExpirationTimeout)) {
                            timeout = admin2.passwordExpirationTimeout;
                        }
                    }
                    return timeout;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        return 0L;
    }

    public boolean addCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        int userId = UserHandle.getCallingUserId();
        List<String> changedProviders = null;
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin, -1);
            if (activeAdmin.crossProfileWidgetProviders == null) {
                activeAdmin.crossProfileWidgetProviders = new ArrayList();
            }
            List<String> providers = activeAdmin.crossProfileWidgetProviders;
            if (!providers.contains(packageName)) {
                providers.add(packageName);
                changedProviders = new ArrayList<>(providers);
                saveSettingsLocked(userId);
            }
        }
        if (changedProviders == null) {
            return false;
        }
        this.mLocalService.notifyCrossProfileProvidersChanged(userId, changedProviders);
        return true;
    }

    public boolean removeCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        int userId = UserHandle.getCallingUserId();
        List<String> changedProviders = null;
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin, -1);
            if (activeAdmin.crossProfileWidgetProviders != null && !activeAdmin.crossProfileWidgetProviders.isEmpty()) {
                List<String> providers = activeAdmin.crossProfileWidgetProviders;
                if (providers.remove(packageName)) {
                    changedProviders = new ArrayList<>(providers);
                    saveSettingsLocked(userId);
                }
                if (changedProviders != null) {
                    this.mLocalService.notifyCrossProfileProvidersChanged(userId, changedProviders);
                    return true;
                }
                return false;
            }
            return false;
        }
    }

    public List<String> getCrossProfileWidgetProviders(ComponentName admin) {
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin, -1);
            if (activeAdmin.crossProfileWidgetProviders != null && !activeAdmin.crossProfileWidgetProviders.isEmpty()) {
                if (this.mInjector.binderIsCallingUidMyUid()) {
                    return new ArrayList(activeAdmin.crossProfileWidgetProviders);
                }
                return activeAdmin.crossProfileWidgetProviders;
            }
            return null;
        }
    }

    private long getPasswordExpirationLocked(ComponentName who, int userHandle, boolean parent) {
        long timeout = 0;
        if (who != null) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
            if (admin != null) {
                return admin.passwordExpirationDate;
            }
            return 0L;
        }
        List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
        int N = admins.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin admin2 = admins.get(i);
            if (timeout == 0 || (admin2.passwordExpirationDate != 0 && timeout > admin2.passwordExpirationDate)) {
                timeout = admin2.passwordExpirationDate;
            }
        }
        return timeout;
    }

    public long getPasswordExpiration(ComponentName who, int userHandle, boolean parent) {
        long passwordExpirationLocked;
        if (!this.mHasFeature) {
            return 0L;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            passwordExpirationLocked = getPasswordExpirationLocked(who, userHandle, parent);
        }
        return passwordExpirationLocked;
    }

    public void setPasswordMinimumUpperCase(ComponentName who, int length, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.upperCase != length) {
                metrics.upperCase = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    public int getPasswordMinimumUpperCase(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$GdvC4eub6BtkkX5BnHuPR5Ob0ag
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).minimumPasswordMetrics.upperCase);
                return valueOf;
            }
        }, 393216);
    }

    public void setPasswordMinimumLowerCase(ComponentName who, int length, boolean parent) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.lowerCase != length) {
                metrics.lowerCase = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    public int getPasswordMinimumLowerCase(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$O6O5T5aoG6MmH8aAAGYNwYhbtw8
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).minimumPasswordMetrics.lowerCase);
                return valueOf;
            }
        }, 393216);
    }

    public void setPasswordMinimumLetters(ComponentName who, int length, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.letters != length) {
                metrics.letters = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    public int getPasswordMinimumLetters(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$tN28Me5AH2pjgYHvPnMAsCjK_NU
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).minimumPasswordMetrics.letters);
                return valueOf;
            }
        }, 393216);
    }

    public void setPasswordMinimumNumeric(ComponentName who, int length, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.numeric != length) {
                metrics.numeric = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    public int getPasswordMinimumNumeric(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$BYd2ftVebU2Ktj6tr-DFfrGE5TE
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).minimumPasswordMetrics.numeric);
                return valueOf;
            }
        }, 393216);
    }

    public void setPasswordMinimumSymbols(ComponentName who, int length, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.symbols != length) {
                ap.minimumPasswordMetrics.symbols = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    public int getPasswordMinimumSymbols(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$CClEW-CtZQRadOocoqGh0wiKhG4
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).minimumPasswordMetrics.symbols);
                return valueOf;
            }
        }, 393216);
    }

    public void setPasswordMinimumNonLetter(ComponentName who, int length, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 0, parent);
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.nonLetter != length) {
                ap.minimumPasswordMetrics.nonLetter = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    public int getPasswordMinimumNonLetter(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent, new Function() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$8nvbMteplUbtaSMuw4DWJ-MQa4g
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                Integer valueOf;
                valueOf = Integer.valueOf(((DevicePolicyManagerService.ActiveAdmin) obj).minimumPasswordMetrics.nonLetter);
                return valueOf;
            }
        }, 393216);
    }

    private int getStrictestPasswordRequirement(ComponentName who, int userHandle, boolean parent, Function<ActiveAdmin, Integer> getter, int minimumPasswordQuality) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                try {
                    if (who != null) {
                        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                        i = admin != null ? getter.apply(admin).intValue() : 0;
                        return i;
                    }
                    int maxValue = 0;
                    List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                    int N = admins.size();
                    while (i < N) {
                        ActiveAdmin admin2 = admins.get(i);
                        if (isLimitPasswordAllowed(admin2, minimumPasswordQuality)) {
                            Integer adminValue = getter.apply(admin2);
                            if (adminValue.intValue() > maxValue) {
                                maxValue = adminValue.intValue();
                            }
                        }
                        i++;
                    }
                    return maxValue;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        return 0;
    }

    public boolean isActivePasswordSufficient(int userHandle, boolean parent) {
        boolean isActivePasswordSufficientForUserLocked;
        if (!this.mHasFeature) {
            return true;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceUserUnlocked(userHandle, parent);
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(null, 0, parent);
            int credentialOwner = getCredentialOwner(userHandle, parent);
            DevicePolicyData policy = getUserDataUnchecked(credentialOwner);
            PasswordMetrics metrics = getUserPasswordMetricsLocked(credentialOwner);
            isActivePasswordSufficientForUserLocked = isActivePasswordSufficientForUserLocked(policy.mPasswordValidAtLastCheckpoint, metrics, userHandle, parent);
        }
        return isActivePasswordSufficientForUserLocked;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public boolean isUsingUnifiedPassword(ComponentName admin) {
        if (this.mHasFeature) {
            int userId = this.mInjector.userHandleGetCallingUserId();
            enforceProfileOrDeviceOwner(admin);
            enforceManagedProfile(userId, "query unified challenge status");
            return true ^ isSeparateProfileChallengeEnabled(userId);
        }
        return true;
    }

    public boolean isProfileActivePasswordSufficientForParent(int userHandle) {
        boolean isActivePasswordSufficientForUserLocked;
        if (!this.mHasFeature) {
            return true;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceManagedProfile(userHandle, "call APIs refering to the parent profile");
        synchronized (getLockObject()) {
            int targetUser = getProfileParentId(userHandle);
            enforceUserUnlocked(targetUser, false);
            int credentialOwner = getCredentialOwner(userHandle, false);
            DevicePolicyData policy = getUserDataUnchecked(credentialOwner);
            PasswordMetrics metrics = getUserPasswordMetricsLocked(credentialOwner);
            isActivePasswordSufficientForUserLocked = isActivePasswordSufficientForUserLocked(policy.mPasswordValidAtLastCheckpoint, metrics, targetUser, false);
        }
        return isActivePasswordSufficientForUserLocked;
    }

    private boolean isActivePasswordSufficientForUserLocked(boolean passwordValidAtLastCheckpoint, PasswordMetrics metrics, int userHandle, boolean parent) {
        if (!this.mInjector.storageManagerIsFileBasedEncryptionEnabled() && metrics == null) {
            return passwordValidAtLastCheckpoint;
        }
        if (metrics == null) {
            metrics = new PasswordMetrics();
        }
        return isPasswordSufficientForUserWithoutCheckpointLocked(metrics, userHandle, parent);
    }

    private boolean isPasswordSufficientForUserWithoutCheckpointLocked(PasswordMetrics passwordMetrics, int userHandle, boolean parent) {
        int requiredPasswordQuality = getPasswordQuality(null, userHandle, parent);
        if (passwordMetrics.quality < requiredPasswordQuality) {
            return false;
        }
        if (requiredPasswordQuality < 131072 || passwordMetrics.length >= getPasswordMinimumLength(null, userHandle, parent)) {
            if (requiredPasswordQuality != 393216) {
                return true;
            }
            return passwordMetrics.upperCase >= getPasswordMinimumUpperCase(null, userHandle, parent) && passwordMetrics.lowerCase >= getPasswordMinimumLowerCase(null, userHandle, parent) && passwordMetrics.letters >= getPasswordMinimumLetters(null, userHandle, parent) && passwordMetrics.numeric >= getPasswordMinimumNumeric(null, userHandle, parent) && passwordMetrics.symbols >= getPasswordMinimumSymbols(null, userHandle, parent) && passwordMetrics.nonLetter >= getPasswordMinimumNonLetter(null, userHandle, parent);
        }
        return false;
    }

    public int getCurrentFailedPasswordAttempts(int userHandle, boolean parent) {
        int i;
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            if (!isCallerWithSystemUid() && this.mContext.checkCallingPermission("android.permission.ACCESS_KEYGUARD_SECURE_STORAGE") != 0) {
                getActiveAdminForCallerLocked(null, 1, parent);
            }
            DevicePolicyData policy = getUserDataUnchecked(getCredentialOwner(userHandle, parent));
            i = policy.mFailedPasswordAttempts;
        }
        return i;
    }

    public void setMaximumFailedPasswordsForWipe(ComponentName who, int num, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, 4, parent);
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 1, parent);
            if (ap.maximumFailedPasswordsForWipe != num) {
                ap.maximumFailedPasswordsForWipe = num;
                saveSettingsLocked(userId);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            int affectedUserId = parent ? getProfileParentId(userId) : userId;
            SecurityLog.writeEvent(210020, new Object[]{who.getPackageName(), Integer.valueOf(userId), Integer.valueOf(affectedUserId), Integer.valueOf(num)});
        }
    }

    public int getMaximumFailedPasswordsForWipe(ComponentName who, int userHandle, boolean parent) {
        ActiveAdmin admin;
        int i;
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                try {
                    if (who != null) {
                        admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                    } else {
                        admin = getAdminWithMinimumFailedPasswordsForWipeLocked(userHandle, parent);
                    }
                    i = admin != null ? admin.maximumFailedPasswordsForWipe : 0;
                } catch (Throwable th) {
                    throw th;
                }
            }
            return i;
        }
        return 0;
    }

    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle, boolean parent) {
        int identifier;
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                ActiveAdmin admin = getAdminWithMinimumFailedPasswordsForWipeLocked(userHandle, parent);
                identifier = admin != null ? admin.getUserHandle().getIdentifier() : -10000;
            }
            return identifier;
        }
        return -10000;
    }

    private ActiveAdmin getAdminWithMinimumFailedPasswordsForWipeLocked(int userHandle, boolean parent) {
        int count = 0;
        ActiveAdmin strictestAdmin = null;
        List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
        int N = admins.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin admin = admins.get(i);
            if (admin.maximumFailedPasswordsForWipe != 0) {
                int userId = admin.getUserHandle().getIdentifier();
                if (count == 0 || count > admin.maximumFailedPasswordsForWipe || (count == admin.maximumFailedPasswordsForWipe && getUserInfo(userId).isPrimary())) {
                    count = admin.maximumFailedPasswordsForWipe;
                    strictestAdmin = admin;
                }
            }
        }
        return strictestAdmin;
    }

    private UserInfo getUserInfo(int userId) {
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mUserManager.getUserInfo(userId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private boolean canPOorDOCallResetPassword(ActiveAdmin admin, int userId) {
        return getTargetSdk(admin.info.getPackageName(), userId) < 26;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean canUserHaveUntrustedCredentialReset(int userId) {
        synchronized (getLockObject()) {
            Iterator<ActiveAdmin> it = getUserData(userId).mAdminList.iterator();
            while (it.hasNext()) {
                ActiveAdmin admin = it.next();
                if (isActiveAdminWithPolicyForUserLocked(admin, -1, userId) && canPOorDOCallResetPassword(admin, userId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean resetPassword(String passwordOrNull, int flags) throws RemoteException {
        boolean preN;
        int callingUid = this.mInjector.binderGetCallingUid();
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        String password = passwordOrNull != null ? passwordOrNull : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        if (TextUtils.isEmpty(password)) {
            enforceNotManagedProfile(userHandle, "clear the active password");
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminWithPolicyForUidLocked(null, -1, callingUid);
            boolean z = true;
            if (admin != null) {
                if (!canPOorDOCallResetPassword(admin, userHandle)) {
                    throw new SecurityException("resetPassword() is deprecated for DPC targeting O or later");
                }
                if (getTargetSdk(admin.info.getPackageName(), userHandle) > 23) {
                    z = false;
                }
                preN = z;
            } else {
                if (getTargetSdk(getActiveAdminForCallerLocked(null, 2).info.getPackageName(), userHandle) > 23) {
                    z = false;
                }
                preN = z;
                if (TextUtils.isEmpty(password)) {
                    if (!preN) {
                        throw new SecurityException("Cannot call with null password");
                    }
                    Slog.e(LOG_TAG, "Cannot call with null password");
                    return false;
                } else if (isLockScreenSecureUnchecked(userHandle)) {
                    if (!preN) {
                        throw new SecurityException("Admin cannot change current password");
                    }
                    Slog.e(LOG_TAG, "Admin cannot change current password");
                    return false;
                }
            }
            if (!isManagedProfile(userHandle)) {
                for (UserInfo userInfo : this.mUserManager.getProfiles(userHandle)) {
                    if (userInfo.isManagedProfile()) {
                        if (!preN) {
                            throw new IllegalStateException("Cannot reset password on user has managed profile");
                        }
                        Slog.e(LOG_TAG, "Cannot reset password on user has managed profile");
                        return false;
                    }
                }
            }
            if (!this.mUserManager.isUserUnlocked(userHandle)) {
                if (!preN) {
                    throw new IllegalStateException("Cannot reset password when user is locked");
                }
                Slog.e(LOG_TAG, "Cannot reset password when user is locked");
                return false;
            }
            return resetPasswordInternal(password, 0L, null, flags, callingUid, userHandle);
        }
    }

    private boolean resetPasswordInternal(String password, long tokenHandle, byte[] token, int flags, int callingUid, int userHandle) {
        long ident;
        boolean result;
        int i;
        int i2;
        long ident2;
        synchronized (getLockObject()) {
            int quality = getPasswordQuality(null, userHandle, false);
            if (quality == 524288) {
                quality = 0;
            }
            PasswordMetrics metrics = PasswordMetrics.computeForPassword(password);
            int realQuality = metrics.quality;
            if (realQuality < quality && quality != 393216) {
                Slog.w(LOG_TAG, "resetPassword: password quality 0x" + Integer.toHexString(realQuality) + " does not meet required quality 0x" + Integer.toHexString(quality));
                return false;
            }
            int quality2 = Math.max(realQuality, quality);
            int length = getPasswordMinimumLength(null, userHandle, false);
            if (password.length() < length) {
                Slog.w(LOG_TAG, "resetPassword: password length " + password.length() + " does not meet required length " + length);
                return false;
            }
            if (quality2 == 393216) {
                int neededLetters = getPasswordMinimumLetters(null, userHandle, false);
                if (metrics.letters < neededLetters) {
                    Slog.w(LOG_TAG, "resetPassword: number of letters " + metrics.letters + " does not meet required number of letters " + neededLetters);
                    return false;
                }
                int neededNumeric = getPasswordMinimumNumeric(null, userHandle, false);
                if (metrics.numeric < neededNumeric) {
                    Slog.w(LOG_TAG, "resetPassword: number of numerical digits " + metrics.numeric + " does not meet required number of numerical digits " + neededNumeric);
                    return false;
                }
                int neededLowerCase = getPasswordMinimumLowerCase(null, userHandle, false);
                if (metrics.lowerCase < neededLowerCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of lowercase letters " + metrics.lowerCase + " does not meet required number of lowercase letters " + neededLowerCase);
                    return false;
                }
                int neededUpperCase = getPasswordMinimumUpperCase(null, userHandle, false);
                if (metrics.upperCase < neededUpperCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of uppercase letters " + metrics.upperCase + " does not meet required number of uppercase letters " + neededUpperCase);
                    return false;
                }
                int neededSymbols = getPasswordMinimumSymbols(null, userHandle, false);
                if (metrics.symbols < neededSymbols) {
                    Slog.w(LOG_TAG, "resetPassword: number of special symbols " + metrics.symbols + " does not meet required number of special symbols " + neededSymbols);
                    return false;
                }
                int neededNonLetter = getPasswordMinimumNonLetter(null, userHandle, false);
                if (metrics.nonLetter < neededNonLetter) {
                    Slog.w(LOG_TAG, "resetPassword: number of non-letter characters " + metrics.nonLetter + " does not meet required number of non-letter characters " + neededNonLetter);
                    return false;
                }
            }
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mPasswordOwner >= 0 && policy.mPasswordOwner != callingUid) {
                Slog.w(LOG_TAG, "resetPassword: already set by another uid and not entered by user");
                return false;
            }
            boolean callerIsDeviceOwnerAdmin = isCallerDeviceOwner(callingUid);
            boolean doNotAskCredentialsOnBoot = (flags & 2) != 0;
            if (callerIsDeviceOwnerAdmin && doNotAskCredentialsOnBoot) {
                setDoNotAskCredentialsOnBoot();
            }
            long ident3 = this.mInjector.binderClearCallingIdentity();
            if (token == null) {
                try {
                    try {
                        if (TextUtils.isEmpty(password)) {
                            this.mLockPatternUtils.clearLock((String) null, userHandle);
                        } else {
                            this.mLockPatternUtils.saveLockPassword(password, (String) null, quality2, userHandle);
                        }
                        result = true;
                        i = -1;
                        i2 = 2;
                        ident2 = ident3;
                    } catch (Throwable th) {
                        th = th;
                        ident = ident3;
                        this.mInjector.binderRestoreCallingIdentity(ident);
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } else {
                try {
                    i = -1;
                    i2 = 2;
                    ident2 = ident3;
                } catch (Throwable th3) {
                    th = th3;
                    ident = ident3;
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    throw th;
                }
                try {
                    result = this.mLockPatternUtils.setLockCredentialWithToken(password, TextUtils.isEmpty(password) ? -1 : 2, quality2, tokenHandle, token, userHandle);
                } catch (Throwable th4) {
                    th = th4;
                    ident = ident2;
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    throw th;
                }
            }
            boolean requireEntry = (flags & 1) != 0;
            if (requireEntry) {
                try {
                    this.mLockPatternUtils.requireStrongAuth(i2, i);
                } catch (Throwable th5) {
                    th = th5;
                    ident = ident2;
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    throw th;
                }
            }
            synchronized (getLockObject()) {
                if (requireEntry) {
                    i = callingUid;
                }
                int newOwner = i;
                try {
                    if (policy.mPasswordOwner != newOwner) {
                        try {
                            policy.mPasswordOwner = newOwner;
                            saveSettingsLocked(userHandle);
                        } catch (Throwable th6) {
                            th = th6;
                            ident = ident2;
                            while (true) {
                                try {
                                    try {
                                        break;
                                    } catch (Throwable th7) {
                                        th = th7;
                                        this.mInjector.binderRestoreCallingIdentity(ident);
                                        throw th;
                                    }
                                } catch (Throwable th8) {
                                    th = th8;
                                }
                            }
                            throw th;
                        }
                    }
                    this.mInjector.binderRestoreCallingIdentity(ident2);
                    return result;
                } catch (Throwable th9) {
                    th = th9;
                    ident = ident2;
                }
            }
        }
    }

    private boolean isLockScreenSecureUnchecked(int userId) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mLockPatternUtils.isSecure(userId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void setDoNotAskCredentialsOnBoot() {
        synchronized (getLockObject()) {
            DevicePolicyData policyData = getUserData(0);
            if (!policyData.doNotAskCredentialsOnBoot) {
                policyData.doNotAskCredentialsOnBoot = true;
                saveSettingsLocked(0);
            }
        }
    }

    public boolean getDoNotAskCredentialsOnBoot() {
        boolean z;
        this.mContext.enforceCallingOrSelfPermission("android.permission.QUERY_DO_NOT_ASK_CREDENTIALS_ON_BOOT", null);
        synchronized (getLockObject()) {
            DevicePolicyData policyData = getUserData(0);
            z = policyData.doNotAskCredentialsOnBoot;
        }
        return z;
    }

    public void setMaximumTimeToLock(ComponentName who, long timeMs, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 3, parent);
            if (ap.maximumTimeToUnlock != timeMs) {
                ap.maximumTimeToUnlock = timeMs;
                saveSettingsLocked(userHandle);
                updateMaximumTimeToLockLocked(userHandle);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            int affectedUserId = parent ? getProfileParentId(userHandle) : userHandle;
            SecurityLog.writeEvent(210019, new Object[]{who.getPackageName(), Integer.valueOf(userHandle), Integer.valueOf(affectedUserId), Long.valueOf(timeMs)});
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateMaximumTimeToLockLocked(int userId) {
        if (isManagedProfile(userId)) {
            updateProfileLockTimeoutLocked(userId);
        }
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            int parentId = getProfileParentId(userId);
            long timeMs = getMaximumTimeToLockPolicyFromAdmins(getActiveAdminsForLockscreenPoliciesLocked(parentId, false));
            DevicePolicyData policy = getUserDataUnchecked(parentId);
            if (policy.mLastMaximumTimeToLock == timeMs) {
                return;
            }
            policy.mLastMaximumTimeToLock = timeMs;
            if (policy.mLastMaximumTimeToLock != JobStatus.NO_LATEST_RUNTIME) {
                this.mInjector.settingsGlobalPutInt("stay_on_while_plugged_in", 0);
            }
            this.mInjector.binderRestoreCallingIdentity(ident);
            this.mInjector.getPowerManagerInternal().setMaximumScreenOffTimeoutFromDeviceAdmin(0, timeMs);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void updateProfileLockTimeoutLocked(int userId) {
        long timeMs;
        if (isSeparateProfileChallengeEnabled(userId)) {
            timeMs = getMaximumTimeToLockPolicyFromAdmins(getActiveAdminsForLockscreenPoliciesLocked(userId, false));
        } else {
            timeMs = JobStatus.NO_LATEST_RUNTIME;
        }
        DevicePolicyData policy = getUserDataUnchecked(userId);
        if (policy.mLastMaximumTimeToLock == timeMs) {
            return;
        }
        policy.mLastMaximumTimeToLock = timeMs;
        this.mInjector.getPowerManagerInternal().setMaximumScreenOffTimeoutFromDeviceAdmin(userId, policy.mLastMaximumTimeToLock);
    }

    public long getMaximumTimeToLock(ComponentName who, int userHandle, boolean parent) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                try {
                    if (who != null) {
                        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                        return admin != null ? admin.maximumTimeToUnlock : 0L;
                    }
                    List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                    long timeMs = getMaximumTimeToLockPolicyFromAdmins(admins);
                    if (timeMs != JobStatus.NO_LATEST_RUNTIME) {
                        r1 = timeMs;
                    }
                    return r1;
                } finally {
                }
            }
        }
        return 0L;
    }

    private long getMaximumTimeToLockPolicyFromAdmins(List<ActiveAdmin> admins) {
        long time = JobStatus.NO_LATEST_RUNTIME;
        for (ActiveAdmin admin : admins) {
            if (admin.maximumTimeToUnlock > 0 && admin.maximumTimeToUnlock < time) {
                time = admin.maximumTimeToUnlock;
            }
        }
        return time;
    }

    public void setRequiredStrongAuthTimeout(ComponentName who, long timeoutMs, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkArgument(timeoutMs >= 0, "Timeout must not be a negative number.");
        long minimumStrongAuthTimeout = getMinimumStrongAuthTimeoutMs();
        if (timeoutMs != 0 && timeoutMs < minimumStrongAuthTimeout) {
            timeoutMs = minimumStrongAuthTimeout;
        }
        if (timeoutMs > 259200000) {
            timeoutMs = 259200000;
        }
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, -1, parent);
            if (ap.strongAuthUnlockTimeout != timeoutMs) {
                ap.strongAuthUnlockTimeout = timeoutMs;
                saveSettingsLocked(userHandle);
            }
        }
    }

    public long getRequiredStrongAuthTimeout(ComponentName who, int userId, boolean parent) {
        if (!this.mHasFeature) {
            return 259200000L;
        }
        enforceFullCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            try {
                if (who != null) {
                    ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userId, parent);
                    return admin != null ? admin.strongAuthUnlockTimeout : 0L;
                }
                List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userId, parent);
                long strongAuthUnlockTimeout = 259200000;
                for (int i = 0; i < admins.size(); i++) {
                    long timeout = admins.get(i).strongAuthUnlockTimeout;
                    if (timeout != 0) {
                        strongAuthUnlockTimeout = Math.min(timeout, strongAuthUnlockTimeout);
                    }
                }
                return Math.max(strongAuthUnlockTimeout, getMinimumStrongAuthTimeoutMs());
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    private long getMinimumStrongAuthTimeoutMs() {
        if (!this.mInjector.isBuildDebuggable()) {
            return MINIMUM_STRONG_AUTH_TIMEOUT_MS;
        }
        return Math.min(this.mInjector.systemPropertiesGetLong("persist.sys.min_str_auth_timeo", MINIMUM_STRONG_AUTH_TIMEOUT_MS), MINIMUM_STRONG_AUTH_TIMEOUT_MS);
    }

    public void lockNow(int flags, boolean parent) {
        Injector injector;
        ComponentName adminComponent;
        int userToLock;
        if (this.mHasFeature) {
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            synchronized (getLockObject()) {
                ActiveAdmin admin = getActiveAdminForCallerLocked(null, 3, parent);
                long ident = this.mInjector.binderClearCallingIdentity();
                try {
                    adminComponent = admin.info.getComponent();
                    if ((flags & 1) != 0) {
                        try {
                            enforceManagedProfile(callingUserId, "set FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY");
                            if (!isProfileOwner(adminComponent, callingUserId)) {
                                throw new SecurityException("Only profile owner admins can set FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY");
                            }
                            if (parent) {
                                throw new IllegalArgumentException("Cannot set FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY for the parent");
                            }
                            if (!this.mInjector.storageManagerIsFileBasedEncryptionEnabled()) {
                                throw new UnsupportedOperationException("FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY only applies to FBE devices");
                            }
                            this.mUserManager.evictCredentialEncryptionKey(callingUserId);
                        } catch (RemoteException e) {
                            injector = this.mInjector;
                            injector.binderRestoreCallingIdentity(ident);
                        } catch (Throwable th) {
                            th = th;
                            this.mInjector.binderRestoreCallingIdentity(ident);
                            throw th;
                        }
                    }
                    userToLock = (parent || !isSeparateProfileChallengeEnabled(callingUserId)) ? -1 : callingUserId;
                    this.mLockPatternUtils.requireStrongAuth(2, userToLock);
                } catch (RemoteException e2) {
                } catch (Throwable th2) {
                    th = th2;
                }
                try {
                    if (userToLock == -1) {
                        this.mInjector.powerManagerGoToSleep(SystemClock.uptimeMillis(), 1, 0);
                        this.mInjector.getIWindowManager().lockNow((Bundle) null);
                    } else {
                        this.mInjector.getTrustManager().setDeviceLockedForUser(userToLock, true);
                    }
                    if (SecurityLog.isLoggingEnabled()) {
                        int affectedUserId = parent ? getProfileParentId(callingUserId) : callingUserId;
                        SecurityLog.writeEvent(210022, new Object[]{adminComponent.getPackageName(), Integer.valueOf(callingUserId), Integer.valueOf(affectedUserId)});
                    }
                    injector = this.mInjector;
                } catch (RemoteException e3) {
                    injector = this.mInjector;
                    injector.binderRestoreCallingIdentity(ident);
                } catch (Throwable th3) {
                    th = th3;
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    throw th;
                }
                injector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    public void enforceCanManageCaCerts(ComponentName who, String callerPackage) {
        if (who == null) {
            if (!isCallerDelegate(callerPackage, "delegation-cert-install")) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_CA_CERTIFICATES", null);
                return;
            }
            return;
        }
        enforceProfileOrDeviceOwner(who);
    }

    private void enforceProfileOrDeviceOwner(ComponentName who) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
        }
    }

    public boolean approveCaCert(String alias, int userId, boolean approval) {
        enforceManageUsers();
        synchronized (getLockObject()) {
            Set<String> certs = getUserData(userId).mAcceptedCaCertificates;
            boolean changed = approval ? certs.add(alias) : certs.remove(alias);
            if (!changed) {
                return false;
            }
            saveSettingsLocked(userId);
            this.mCertificateMonitor.onCertificateApprovalsChanged(userId);
            return true;
        }
    }

    public boolean isCaCertApproved(String alias, int userId) {
        boolean contains;
        enforceManageUsers();
        synchronized (getLockObject()) {
            contains = getUserData(userId).mAcceptedCaCertificates.contains(alias);
        }
        return contains;
    }

    private void removeCaApprovalsIfNeeded(int userId) {
        for (UserInfo userInfo : this.mUserManager.getProfiles(userId)) {
            boolean isSecure = this.mLockPatternUtils.isSecure(userInfo.id);
            if (userInfo.isManagedProfile()) {
                isSecure |= this.mLockPatternUtils.isSecure(getProfileParentId(userInfo.id));
            }
            if (!isSecure) {
                synchronized (getLockObject()) {
                    getUserData(userInfo.id).mAcceptedCaCertificates.clear();
                    saveSettingsLocked(userInfo.id);
                }
                this.mCertificateMonitor.onCertificateApprovalsChanged(userId);
            }
        }
    }

    public boolean installCaCert(ComponentName admin, String callerPackage, byte[] certBuffer) throws RemoteException {
        if (this.mHasFeature) {
            enforceCanManageCaCerts(admin, callerPackage);
            UserHandle userHandle = this.mInjector.binderGetCallingUserHandle();
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                String alias = this.mCertificateMonitor.installCaCert(userHandle, certBuffer);
                if (alias == null) {
                    Log.w(LOG_TAG, "Problem installing cert");
                    return false;
                }
                this.mInjector.binderRestoreCallingIdentity(id);
                synchronized (getLockObject()) {
                    getUserData(userHandle.getIdentifier()).mOwnerInstalledCaCerts.add(alias);
                    saveSettingsLocked(userHandle.getIdentifier());
                }
                return true;
            } finally {
                this.mInjector.binderRestoreCallingIdentity(id);
            }
        }
        return false;
    }

    public void uninstallCaCerts(ComponentName admin, String callerPackage, String[] aliases) {
        if (!this.mHasFeature) {
            return;
        }
        enforceCanManageCaCerts(admin, callerPackage);
        int userId = this.mInjector.userHandleGetCallingUserId();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            this.mCertificateMonitor.uninstallCaCerts(UserHandle.of(userId), aliases);
            this.mInjector.binderRestoreCallingIdentity(id);
            synchronized (getLockObject()) {
                if (getUserData(userId).mOwnerInstalledCaCerts.removeAll(Arrays.asList(aliases))) {
                    saveSettingsLocked(userId);
                }
            }
        } catch (Throwable th) {
            this.mInjector.binderRestoreCallingIdentity(id);
            throw th;
        }
    }

    public boolean installKeyPair(ComponentName who, String callerPackage, byte[] privKey, byte[] cert, byte[] chain, String alias, boolean requestAccess, boolean isUserSelectable) {
        IKeyChainService keyChain;
        enforceCanManageScope(who, callerPackage, -1, "delegation-cert-install");
        int callingUid = this.mInjector.binderGetCallingUid();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                KeyChain.KeyChainConnection keyChainConnection = KeyChain.bindAsUser(this.mContext, UserHandle.getUserHandleForUid(callingUid));
                try {
                    try {
                        keyChain = keyChainConnection.getService();
                    } catch (InterruptedException e) {
                        e = e;
                        Log.w(LOG_TAG, "Interrupted while installing certificate", e);
                        Thread.currentThread().interrupt();
                        this.mInjector.binderRestoreCallingIdentity(id);
                        return false;
                    }
                    try {
                        if (!keyChain.installKeyPair(privKey, cert, chain, alias)) {
                            try {
                                keyChainConnection.close();
                                this.mInjector.binderRestoreCallingIdentity(id);
                                return false;
                            } catch (InterruptedException e2) {
                                e = e2;
                                Log.w(LOG_TAG, "Interrupted while installing certificate", e);
                                Thread.currentThread().interrupt();
                                this.mInjector.binderRestoreCallingIdentity(id);
                                return false;
                            } catch (Throwable th) {
                                th = th;
                                this.mInjector.binderRestoreCallingIdentity(id);
                                throw th;
                            }
                        }
                        if (requestAccess) {
                            keyChain.setGrant(callingUid, alias, true);
                        }
                        try {
                            try {
                                keyChain.setUserSelectable(alias, isUserSelectable);
                                keyChainConnection.close();
                                this.mInjector.binderRestoreCallingIdentity(id);
                                return true;
                            } catch (RemoteException e3) {
                                e = e3;
                                Log.e(LOG_TAG, "Installing certificate", e);
                                keyChainConnection.close();
                                this.mInjector.binderRestoreCallingIdentity(id);
                                return false;
                            }
                        } catch (Throwable th2) {
                            th = th2;
                            keyChainConnection.close();
                            throw th;
                        }
                    } catch (RemoteException e4) {
                        e = e4;
                        Log.e(LOG_TAG, "Installing certificate", e);
                        keyChainConnection.close();
                        this.mInjector.binderRestoreCallingIdentity(id);
                        return false;
                    } catch (Throwable th3) {
                        th = th3;
                        keyChainConnection.close();
                        throw th;
                    }
                } catch (RemoteException e5) {
                    e = e5;
                } catch (Throwable th4) {
                    th = th4;
                }
            } catch (Throwable th5) {
                th = th5;
                this.mInjector.binderRestoreCallingIdentity(id);
                throw th;
            }
        } catch (InterruptedException e6) {
            e = e6;
        } catch (Throwable th6) {
            th = th6;
        }
    }

    public boolean removeKeyPair(ComponentName who, String callerPackage, String alias) {
        KeyChain.KeyChainConnection keyChainConnection;
        enforceCanManageScope(who, callerPackage, -1, "delegation-cert-install");
        UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());
        long id = Binder.clearCallingIdentity();
        try {
            try {
                keyChainConnection = KeyChain.bindAsUser(this.mContext, userHandle);
            } catch (InterruptedException e) {
                Log.w(LOG_TAG, "Interrupted while removing keypair", e);
                Thread.currentThread().interrupt();
            }
            try {
                try {
                    IKeyChainService keyChain = keyChainConnection.getService();
                    return keyChain.removeKeyPair(alias);
                } finally {
                    keyChainConnection.close();
                }
            } catch (RemoteException e2) {
                Log.e(LOG_TAG, "Removing keypair", e2);
                keyChainConnection.close();
                Binder.restoreCallingIdentity(id);
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    private void enforceIsDeviceOwnerOrCertInstallerOfDeviceOwner(ComponentName who, String callerPackage, int callerUid) throws SecurityException {
        if (who == null) {
            if (!this.mOwners.hasDeviceOwner()) {
                throw new SecurityException("Not in Device Owner mode.");
            }
            if (UserHandle.getUserId(callerUid) != this.mOwners.getDeviceOwnerUserId()) {
                throw new SecurityException("Caller not from device owner user");
            }
            if (!isCallerDelegate(callerPackage, "delegation-cert-install")) {
                throw new SecurityException("Caller with uid " + this.mInjector.binderGetCallingUid() + "has no permission to generate keys.");
            }
            return;
        }
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
    }

    @VisibleForTesting
    public static int[] translateIdAttestationFlags(int idAttestationFlags) {
        Map<Integer, Integer> idTypeToAttestationFlag = new HashMap<>();
        idTypeToAttestationFlag.put(2, 1);
        idTypeToAttestationFlag.put(4, 2);
        idTypeToAttestationFlag.put(8, 3);
        int numFlagsSet = Integer.bitCount(idAttestationFlags);
        if (numFlagsSet == 0) {
            return null;
        }
        if ((idAttestationFlags & 1) != 0) {
            numFlagsSet--;
            idAttestationFlags &= -2;
        }
        int[] attestationUtilsFlags = new int[numFlagsSet];
        int i = 0;
        for (Integer idType : idTypeToAttestationFlag.keySet()) {
            if ((idType.intValue() & idAttestationFlags) != 0) {
                attestationUtilsFlags[i] = idTypeToAttestationFlag.get(idType).intValue();
                i++;
            }
        }
        return attestationUtilsFlags;
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public boolean generateKeyPair(android.content.ComponentName r22, java.lang.String r23, java.lang.String r24, android.security.keystore.ParcelableKeyGenParameterSpec r25, int r26, android.security.keymaster.KeymasterCertificateChain r27) {
        /*
            Method dump skipped, instructions count: 379
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.generateKeyPair(android.content.ComponentName, java.lang.String, java.lang.String, android.security.keystore.ParcelableKeyGenParameterSpec, int, android.security.keymaster.KeymasterCertificateChain):boolean");
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public boolean setKeyPairCertificate(ComponentName who, String callerPackage, String alias, byte[] cert, byte[] chain, boolean isUserSelectable) {
        enforceCanManageScope(who, callerPackage, -1, "delegation-cert-install");
        int callingUid = this.mInjector.binderGetCallingUid();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                KeyChain.KeyChainConnection keyChainConnection = KeyChain.bindAsUser(this.mContext, UserHandle.getUserHandleForUid(callingUid));
                try {
                    IKeyChainService keyChain = keyChainConnection.getService();
                    if (!keyChain.setKeyPairCertificate(alias, cert, chain)) {
                        if (keyChainConnection != null) {
                            $closeResource(null, keyChainConnection);
                        }
                        return false;
                    }
                    keyChain.setUserSelectable(alias, isUserSelectable);
                    if (keyChainConnection != null) {
                        $closeResource(null, keyChainConnection);
                    }
                    return true;
                } finally {
                }
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Failed setting keypair certificate", e);
                return false;
            } catch (InterruptedException e2) {
                Log.w(LOG_TAG, "Interrupted while setting keypair certificate", e2);
                Thread.currentThread().interrupt();
                return false;
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public void choosePrivateKeyAlias(int uid, Uri uri, String alias, final IBinder response) {
        long id;
        if (!isCallerWithSystemUid()) {
            return;
        }
        UserHandle caller = this.mInjector.binderGetCallingUserHandle();
        ComponentName aliasChooser = getProfileOwner(caller.getIdentifier());
        if (aliasChooser == null && caller.isSystem()) {
            synchronized (getLockObject()) {
                ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();
                if (deviceOwnerAdmin != null) {
                    aliasChooser = deviceOwnerAdmin.info.getComponent();
                }
            }
        }
        ComponentName aliasChooser2 = aliasChooser;
        if (aliasChooser2 == null) {
            sendPrivateKeyAliasResponse(null, response);
            return;
        }
        Intent intent = new Intent("android.app.action.CHOOSE_PRIVATE_KEY_ALIAS");
        intent.setComponent(aliasChooser2);
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_SENDER_UID", uid);
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_URI", uri);
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_ALIAS", alias);
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_RESPONSE", response);
        intent.addFlags(268435456);
        long id2 = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                this.mContext.sendOrderedBroadcastAsUser(intent, caller, null, new BroadcastReceiver() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.6
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent2) {
                        String chosenAlias = getResultData();
                        DevicePolicyManagerService.this.sendPrivateKeyAliasResponse(chosenAlias, response);
                    }
                }, null, -1, null, null);
                this.mInjector.binderRestoreCallingIdentity(id2);
            } catch (Throwable th) {
                th = th;
                id = id2;
                this.mInjector.binderRestoreCallingIdentity(id);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            id = id2;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendPrivateKeyAliasResponse(String alias, IBinder responseBinder) {
        IKeyChainAliasCallback keyChainAliasResponse = IKeyChainAliasCallback.Stub.asInterface(responseBinder);
        try {
            keyChainAliasResponse.alias(alias);
        } catch (Exception e) {
            Log.e(LOG_TAG, "error while responding to callback", e);
        }
    }

    private static boolean shouldCheckIfDelegatePackageIsInstalled(String delegatePackage, int targetSdk, List<String> scopes) {
        if (targetSdk >= 24) {
            return true;
        }
        return ((scopes.size() == 1 && scopes.get(0).equals("delegation-cert-install")) || scopes.isEmpty()) ? false : true;
    }

    public void setDelegatedScopes(ComponentName who, String delegatePackage, List<String> scopes) throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkStringNotEmpty(delegatePackage, "Delegate package is null or empty");
        Preconditions.checkCollectionElementsNotNull(scopes, "Scopes");
        List<String> scopes2 = new ArrayList<>(new ArraySet(scopes));
        if (scopes2.retainAll(Arrays.asList(DELEGATIONS))) {
            throw new IllegalArgumentException("Unexpected delegation scopes");
        }
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            if (shouldCheckIfDelegatePackageIsInstalled(delegatePackage, getTargetSdk(who.getPackageName(), userId), scopes2) && !isPackageInstalledForUser(delegatePackage, userId)) {
                throw new IllegalArgumentException("Package " + delegatePackage + " is not installed on the current user");
            }
            DevicePolicyData policy = getUserData(userId);
            if (!scopes2.isEmpty()) {
                policy.mDelegationMap.put(delegatePackage, new ArrayList(scopes2));
            } else {
                policy.mDelegationMap.remove(delegatePackage);
            }
            Intent intent = new Intent("android.app.action.APPLICATION_DELEGATION_SCOPES_CHANGED");
            intent.addFlags(1073741824);
            intent.setPackage(delegatePackage);
            intent.putStringArrayListExtra("android.app.extra.DELEGATION_SCOPES", (ArrayList) scopes2);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
            saveSettingsLocked(userId);
        }
    }

    public List<String> getDelegatedScopes(ComponentName who, String delegatePackage) throws SecurityException {
        List<String> list;
        Preconditions.checkNotNull(delegatePackage, "Delegate package is null");
        int callingUid = this.mInjector.binderGetCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        synchronized (getLockObject()) {
            if (who != null) {
                getActiveAdminForCallerLocked(who, -1);
            } else {
                int uid = 0;
                try {
                    uid = this.mInjector.getPackageManager().getPackageUidAsUser(delegatePackage, userId);
                } catch (PackageManager.NameNotFoundException e) {
                }
                if (uid != callingUid) {
                    throw new SecurityException("Caller with uid " + callingUid + " is not " + delegatePackage);
                }
            }
            DevicePolicyData policy = getUserData(userId);
            List<String> scopes = policy.mDelegationMap.get(delegatePackage);
            list = scopes == null ? Collections.EMPTY_LIST : scopes;
        }
        return list;
    }

    public List<String> getDelegatePackages(ComponentName who, String scope) throws SecurityException {
        List<String> delegatePackagesWithScope;
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(scope, "Scope is null");
        if (!Arrays.asList(DELEGATIONS).contains(scope)) {
            throw new IllegalArgumentException("Unexpected delegation scope: " + scope);
        }
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            DevicePolicyData policy = getUserData(userId);
            delegatePackagesWithScope = new ArrayList<>();
            for (int i = 0; i < policy.mDelegationMap.size(); i++) {
                if (policy.mDelegationMap.valueAt(i).contains(scope)) {
                    delegatePackagesWithScope.add(policy.mDelegationMap.keyAt(i));
                }
            }
        }
        return delegatePackagesWithScope;
    }

    private boolean isCallerDelegate(String callerPackage, String scope) {
        Preconditions.checkNotNull(callerPackage, "callerPackage is null");
        if (!Arrays.asList(DELEGATIONS).contains(scope)) {
            throw new IllegalArgumentException("Unexpected delegation scope: " + scope);
        }
        int callingUid = this.mInjector.binderGetCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userId);
            List<String> scopes = policy.mDelegationMap.get(callerPackage);
            if (scopes != null && scopes.contains(scope)) {
                try {
                    int uid = this.mInjector.getPackageManager().getPackageUidAsUser(callerPackage, userId);
                    return uid == callingUid;
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
            return false;
        }
    }

    private void enforceCanManageScope(ComponentName who, String callerPackage, int reqPolicy, String scope) {
        if (who != null) {
            synchronized (getLockObject()) {
                getActiveAdminForCallerLocked(who, reqPolicy);
            }
        } else if (!isCallerDelegate(callerPackage, scope)) {
            throw new SecurityException("Caller with uid " + this.mInjector.binderGetCallingUid() + " is not a delegate of scope " + scope + ".");
        }
    }

    private void setDelegatedScopePreO(ComponentName who, String delegatePackage, String scope) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            DevicePolicyData policy = getUserData(userId);
            if (delegatePackage != null) {
                List<String> scopes = policy.mDelegationMap.get(delegatePackage);
                if (scopes == null) {
                    scopes = new ArrayList();
                }
                if (!scopes.contains(scope)) {
                    scopes.add(scope);
                    setDelegatedScopes(who, delegatePackage, scopes);
                }
            }
            for (int i = 0; i < policy.mDelegationMap.size(); i++) {
                String currentPackage = policy.mDelegationMap.keyAt(i);
                List<String> currentScopes = policy.mDelegationMap.valueAt(i);
                if (!currentPackage.equals(delegatePackage) && currentScopes.contains(scope)) {
                    List<String> newScopes = new ArrayList<>(currentScopes);
                    newScopes.remove(scope);
                    setDelegatedScopes(who, currentPackage, newScopes);
                }
            }
        }
    }

    public void setCertInstallerPackage(ComponentName who, String installerPackage) throws SecurityException {
        setDelegatedScopePreO(who, installerPackage, "delegation-cert-install");
    }

    public String getCertInstallerPackage(ComponentName who) throws SecurityException {
        List<String> delegatePackages = getDelegatePackages(who, "delegation-cert-install");
        if (delegatePackages.size() > 0) {
            return delegatePackages.get(0);
        }
        return null;
    }

    public boolean setAlwaysOnVpnPackage(ComponentName admin, String vpnPackage, boolean lockdown) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);
        int userId = this.mInjector.userHandleGetCallingUserId();
        long token = this.mInjector.binderClearCallingIdentity();
        if (vpnPackage != null) {
            try {
                if (!isPackageInstalledForUser(vpnPackage, userId)) {
                    return false;
                }
            } finally {
                this.mInjector.binderRestoreCallingIdentity(token);
            }
        }
        ConnectivityManager connectivityManager = (ConnectivityManager) this.mContext.getSystemService("connectivity");
        if (!connectivityManager.setAlwaysOnVpnPackageForUser(userId, vpnPackage, lockdown)) {
            throw new UnsupportedOperationException();
        }
        this.mInjector.binderRestoreCallingIdentity(token);
        return true;
    }

    public String getAlwaysOnVpnPackage(ComponentName admin) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);
        int userId = this.mInjector.userHandleGetCallingUserId();
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) this.mContext.getSystemService("connectivity");
            return connectivityManager.getAlwaysOnVpnPackageForUser(userId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private void forceWipeDeviceNoLock(boolean wipeExtRequested, String reason, boolean wipeEuicc) {
        wtfIfInLock();
        if (wipeExtRequested) {
            try {
                try {
                    StorageManager sm = (StorageManager) this.mContext.getSystemService("storage");
                    sm.wipeAdoptableDisks();
                } catch (IOException | SecurityException e) {
                    Slog.w(LOG_TAG, "Failed requesting data wipe", e);
                    if (0 != 0) {
                        return;
                    }
                }
            } catch (Throwable th) {
                if (0 == 0) {
                    SecurityLog.writeEvent(210023, new Object[0]);
                }
                throw th;
            }
        }
        this.mInjector.recoverySystemRebootWipeUserData(false, reason, true, wipeEuicc);
        if (1 != 0) {
            return;
        }
        SecurityLog.writeEvent(210023, new Object[0]);
    }

    private void forceWipeUser(int userId, String wipeReasonForUser) {
        try {
            IActivityManager am = this.mInjector.getIActivityManager();
            if (am.getCurrentUser().id == userId) {
                am.switchUser(0);
            }
            boolean success = this.mUserManagerInternal.removeUserEvenWhenDisallowed(userId);
            if (!success) {
                Slog.w(LOG_TAG, "Couldn't remove user " + userId);
            } else if (isManagedProfile(userId)) {
                sendWipeProfileNotification(wipeReasonForUser);
            }
            if (success) {
                return;
            }
        } catch (RemoteException e) {
            if (0 != 0) {
                return;
            }
        } catch (Throwable th) {
            if (0 == 0) {
                SecurityLog.writeEvent(210023, new Object[0]);
            }
            throw th;
        }
        SecurityLog.writeEvent(210023, new Object[0]);
    }

    public void wipeDataWithReason(int flags, String wipeReasonForUser) {
        ActiveAdmin admin;
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkStringNotEmpty(wipeReasonForUser, "wipeReasonForUser is null or empty");
        enforceFullCrossUsersPermission(this.mInjector.userHandleGetCallingUserId());
        synchronized (getLockObject()) {
            admin = getActiveAdminForCallerLocked(null, 4);
        }
        String internalReason = "DevicePolicyManager.wipeDataWithReason() from " + admin.info.getComponent().flattenToShortString();
        wipeDataNoLock(admin.info.getComponent(), flags, internalReason, wipeReasonForUser, admin.getUserHandle().getIdentifier());
    }

    /* JADX WARN: Removed duplicated region for block: B:14:0x0025 A[Catch: all -> 0x000f, TryCatch #0 {all -> 0x000f, blocks: (B:12:0x001f, B:14:0x0025, B:16:0x0029, B:18:0x002f, B:20:0x003c, B:21:0x0040, B:22:0x0047, B:24:0x004a, B:28:0x0053, B:31:0x0059, B:32:0x005d, B:35:0x0067, B:36:0x0085, B:8:0x0012), top: B:39:0x0012 }] */
    /* JADX WARN: Removed duplicated region for block: B:35:0x0067 A[Catch: all -> 0x000f, TRY_ENTER, TryCatch #0 {all -> 0x000f, blocks: (B:12:0x001f, B:14:0x0025, B:16:0x0029, B:18:0x002f, B:20:0x003c, B:21:0x0040, B:22:0x0047, B:24:0x004a, B:28:0x0053, B:31:0x0059, B:32:0x005d, B:35:0x0067, B:36:0x0085, B:8:0x0012), top: B:39:0x0012 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void wipeDataNoLock(android.content.ComponentName r8, int r9, java.lang.String r10, java.lang.String r11, int r12) {
        /*
            r7 = this;
            r7.wtfIfInLock()
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r7.mInjector
            long r0 = r0.binderClearCallingIdentity()
            if (r12 != 0) goto L12
            java.lang.String r2 = "no_factory_reset"
        Le:
            goto L1f
        Lf:
            r2 = move-exception
            goto L86
        L12:
            boolean r2 = r7.isManagedProfile(r12)     // Catch: java.lang.Throwable -> Lf
            if (r2 == 0) goto L1c
            java.lang.String r2 = "no_remove_managed_profile"
            goto Le
        L1c:
            java.lang.String r2 = "no_remove_user"
        L1f:
            boolean r3 = r7.isAdminAffectedByRestriction(r8, r2, r12)     // Catch: java.lang.Throwable -> Lf
            if (r3 != 0) goto L67
            r3 = r9 & 2
            if (r3 == 0) goto L48
            boolean r3 = r7.isDeviceOwner(r8, r12)     // Catch: java.lang.Throwable -> Lf
            if (r3 == 0) goto L40
            android.content.Context r3 = r7.mContext     // Catch: java.lang.Throwable -> Lf
            java.lang.String r4 = "persistent_data_block"
            java.lang.Object r3 = r3.getSystemService(r4)     // Catch: java.lang.Throwable -> Lf
            android.service.persistentdata.PersistentDataBlockManager r3 = (android.service.persistentdata.PersistentDataBlockManager) r3     // Catch: java.lang.Throwable -> Lf
            if (r3 == 0) goto L48
            r3.wipe()     // Catch: java.lang.Throwable -> Lf
            goto L48
        L40:
            java.lang.SecurityException r3 = new java.lang.SecurityException     // Catch: java.lang.Throwable -> Lf
            java.lang.String r4 = "Only device owner admins can set WIPE_RESET_PROTECTION_DATA"
            r3.<init>(r4)     // Catch: java.lang.Throwable -> Lf
            throw r3     // Catch: java.lang.Throwable -> Lf
        L48:
            if (r12 != 0) goto L5d
            r3 = r9 & 1
            r4 = 0
            r5 = 1
            if (r3 == 0) goto L52
            r3 = r5
            goto L53
        L52:
            r3 = r4
        L53:
            r6 = r9 & 4
            if (r6 == 0) goto L59
            r4 = r5
        L59:
            r7.forceWipeDeviceNoLock(r3, r10, r4)     // Catch: java.lang.Throwable -> Lf
            goto L60
        L5d:
            r7.forceWipeUser(r12, r11)     // Catch: java.lang.Throwable -> Lf
        L60:
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r2 = r7.mInjector
            r2.binderRestoreCallingIdentity(r0)
            return
        L67:
            java.lang.SecurityException r3 = new java.lang.SecurityException     // Catch: java.lang.Throwable -> Lf
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> Lf
            r4.<init>()     // Catch: java.lang.Throwable -> Lf
            java.lang.String r5 = "Cannot wipe data. "
            r4.append(r5)     // Catch: java.lang.Throwable -> Lf
            r4.append(r2)     // Catch: java.lang.Throwable -> Lf
            java.lang.String r5 = " restriction is set for user "
            r4.append(r5)     // Catch: java.lang.Throwable -> Lf
            r4.append(r12)     // Catch: java.lang.Throwable -> Lf
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> Lf
            r3.<init>(r4)     // Catch: java.lang.Throwable -> Lf
            throw r3     // Catch: java.lang.Throwable -> Lf
        L86:
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r3 = r7.mInjector
            r3.binderRestoreCallingIdentity(r0)
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.wipeDataNoLock(android.content.ComponentName, int, java.lang.String, java.lang.String, int):void");
    }

    private void sendWipeProfileNotification(String wipeReasonForUser) {
        Notification notification = new Notification.Builder(this.mContext, SystemNotificationChannels.DEVICE_ADMIN).setSmallIcon(17301642).setContentTitle(this.mContext.getString(17041146)).setContentText(wipeReasonForUser).setColor(this.mContext.getColor(17170861)).setStyle(new Notification.BigTextStyle().bigText(wipeReasonForUser)).build();
        this.mInjector.getNotificationManager().notify(NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE, notification);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearWipeProfileNotification() {
        this.mInjector.getNotificationManager().cancel(NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE);
    }

    public void getRemoveWarning(ComponentName comp, final RemoteCallback result, int userHandle) {
        if (!this.mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(comp, userHandle);
            if (admin == null) {
                result.sendResult((Bundle) null);
                return;
            }
            Intent intent = new Intent("android.app.action.DEVICE_ADMIN_DISABLE_REQUESTED");
            intent.setFlags(268435456);
            intent.setComponent(admin.info.getComponent());
            this.mContext.sendOrderedBroadcastAsUser(intent, new UserHandle(userHandle), null, new BroadcastReceiver() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.7
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent2) {
                    result.sendResult(getResultExtras(false));
                }
            }, null, -1, null, null);
        }
    }

    public void setActivePasswordState(PasswordMetrics metrics, int userHandle) {
        if (!this.mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        if (isManagedProfile(userHandle) && !isSeparateProfileChallengeEnabled(userHandle)) {
            metrics = new PasswordMetrics();
        }
        validateQualityConstant(metrics.quality);
        synchronized (getLockObject()) {
            this.mUserPasswordMetrics.put(userHandle, metrics);
        }
    }

    public void reportPasswordChanged(int userId) {
        if (!this.mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userId);
        if (!isSeparateProfileChallengeEnabled(userId)) {
            enforceNotManagedProfile(userId, "set the active password");
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        DevicePolicyData policy = getUserData(userId);
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                policy.mFailedPasswordAttempts = 0;
                updatePasswordValidityCheckpointLocked(userId, false);
                saveSettingsLocked(userId);
                updatePasswordExpirationsLocked(userId);
                setExpirationAlarmCheckLocked(this.mContext, userId, false);
                sendAdminCommandForLockscreenPoliciesLocked("android.app.action.ACTION_PASSWORD_CHANGED", 0, userId);
            }
            removeCaApprovalsIfNeeded(userId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void updatePasswordExpirationsLocked(int userHandle) {
        ArraySet<Integer> affectedUserIds = new ArraySet<>();
        List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, false);
        int N = admins.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin admin = admins.get(i);
            if (admin.info.usesPolicy(6)) {
                affectedUserIds.add(Integer.valueOf(admin.getUserHandle().getIdentifier()));
                long timeout = admin.passwordExpirationTimeout;
                long expiration = timeout > 0 ? System.currentTimeMillis() + timeout : 0L;
                admin.passwordExpirationDate = expiration;
            }
        }
        Iterator<Integer> it = affectedUserIds.iterator();
        while (it.hasNext()) {
            int affectedUserId = it.next().intValue();
            saveSettingsLocked(affectedUserId);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:22:0x005b, code lost:
        if (r13 == false) goto L32;
     */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x005d, code lost:
        if (r14 == null) goto L32;
     */
    /* JADX WARN: Code restructure failed: missing block: B:24:0x005f, code lost:
        r15 = r14.getUserHandle().getIdentifier();
        android.util.Slog.i(com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG, "Max failed password attempts policy reached for admin: " + r14.info.getComponent().flattenToShortString() + ". Calling wipeData for user " + r15);
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x008f, code lost:
        r5 = r16.mContext.getString(17041149);
        wipeDataNoLock(r14.info.getComponent(), 0, "reportFailedPasswordAttempt()", r5, r15);
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x00a8, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00a9, code lost:
        android.util.Slog.w(com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG, "Failed to wipe user " + r15 + " after max failed password attempts reached.", r0);
     */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:35:0x00e6 -> B:36:0x00e7). Please submit an issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void reportFailedPasswordAttempt(int r17) {
        /*
            Method dump skipped, instructions count: 240
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.reportFailedPasswordAttempt(int):void");
    }

    public void reportSuccessfulPasswordAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mFailedPasswordAttempts != 0 || policy.mPasswordOwner >= 0) {
                long ident = this.mInjector.binderClearCallingIdentity();
                policy.mFailedPasswordAttempts = 0;
                policy.mPasswordOwner = -1;
                saveSettingsLocked(userHandle);
                if (this.mHasFeature) {
                    sendAdminCommandForLockscreenPoliciesLocked("android.app.action.ACTION_PASSWORD_SUCCEEDED", 1, userHandle);
                }
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
        if (this.mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(210007, new Object[]{1, 1});
        }
    }

    public void reportFailedFingerprintAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        if (this.mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(210007, new Object[]{0, 0});
        }
    }

    public void reportSuccessfulFingerprintAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        if (this.mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(210007, new Object[]{1, 0});
        }
    }

    public void reportKeyguardDismissed(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        if (this.mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(210006, new Object[0]);
        }
    }

    public void reportKeyguardSecured(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        if (this.mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(210008, new Object[0]);
        }
    }

    public ComponentName setGlobalProxy(ComponentName who, String proxySpec, String exclusionList) {
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
                Preconditions.checkNotNull(who, "ComponentName is null");
                DevicePolicyData policy = getUserData(0);
                ActiveAdmin admin = getActiveAdminForCallerLocked(who, 5);
                Set<ComponentName> compSet = policy.mAdminMap.keySet();
                for (ComponentName component : compSet) {
                    ActiveAdmin ap = policy.mAdminMap.get(component);
                    if (ap.specifiesGlobalProxy && !component.equals(who)) {
                        return component;
                    }
                }
                if (UserHandle.getCallingUserId() != 0) {
                    Slog.w(LOG_TAG, "Only the owner is allowed to set the global proxy. User " + UserHandle.getCallingUserId() + " is not permitted.");
                    return null;
                }
                if (proxySpec == null) {
                    admin.specifiesGlobalProxy = false;
                    admin.globalProxySpec = null;
                    admin.globalProxyExclusionList = null;
                } else {
                    admin.specifiesGlobalProxy = true;
                    admin.globalProxySpec = proxySpec;
                    admin.globalProxyExclusionList = exclusionList;
                }
                long origId = this.mInjector.binderClearCallingIdentity();
                resetGlobalProxyLocked(policy);
                this.mInjector.binderRestoreCallingIdentity(origId);
                return null;
            }
        }
        return null;
    }

    public ComponentName getGlobalProxyAdmin(int userHandle) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                DevicePolicyData policy = getUserData(0);
                int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin ap = policy.mAdminList.get(i);
                    if (ap.specifiesGlobalProxy) {
                        return ap.info.getComponent();
                    }
                }
                return null;
            }
        }
        return null;
    }

    public void setRecommendedGlobalProxy(ComponentName who, ProxyInfo proxyInfo) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) this.mContext.getSystemService("connectivity");
            connectivityManager.setGlobalProxy(proxyInfo);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private void resetGlobalProxyLocked(DevicePolicyData policy) {
        int N = policy.mAdminList.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin ap = policy.mAdminList.get(i);
            if (ap.specifiesGlobalProxy) {
                saveGlobalProxyLocked(ap.globalProxySpec, ap.globalProxyExclusionList);
                return;
            }
        }
        saveGlobalProxyLocked(null, null);
    }

    private void saveGlobalProxyLocked(String proxySpec, String exclusionList) {
        if (exclusionList == null) {
            exclusionList = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        if (proxySpec == null) {
            proxySpec = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        String[] data = proxySpec.trim().split(":");
        int proxyPort = 8080;
        if (data.length > 1) {
            try {
                proxyPort = Integer.parseInt(data[1]);
            } catch (NumberFormatException e) {
            }
        }
        String exclusionList2 = exclusionList.trim();
        ProxyInfo proxyProperties = new ProxyInfo(data[0], proxyPort, exclusionList2);
        if (!proxyProperties.isValid()) {
            Slog.e(LOG_TAG, "Invalid proxy properties, ignoring: " + proxyProperties.toString());
            return;
        }
        this.mInjector.settingsGlobalPutString("global_http_proxy_host", data[0]);
        this.mInjector.settingsGlobalPutInt("global_http_proxy_port", proxyPort);
        this.mInjector.settingsGlobalPutString("global_http_proxy_exclusion_list", exclusionList2);
    }

    public int setStorageEncryption(ComponentName who, boolean encrypt) {
        int i;
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            int userHandle = UserHandle.getCallingUserId();
            synchronized (getLockObject()) {
                try {
                    if (userHandle != 0) {
                        Slog.w(LOG_TAG, "Only owner/system user is allowed to set storage encryption. User " + UserHandle.getCallingUserId() + " is not permitted.");
                        return 0;
                    }
                    ActiveAdmin ap = getActiveAdminForCallerLocked(who, 7);
                    if (isEncryptionSupported()) {
                        if (ap.encryptionRequested != encrypt) {
                            ap.encryptionRequested = encrypt;
                            saveSettingsLocked(userHandle);
                        }
                        DevicePolicyData policy = getUserData(0);
                        boolean newRequested = false;
                        int N = policy.mAdminList.size();
                        for (int i2 = 0; i2 < N; i2++) {
                            newRequested |= policy.mAdminList.get(i2).encryptionRequested;
                        }
                        setEncryptionRequested(newRequested);
                        if (newRequested) {
                            i = 3;
                        } else {
                            i = 1;
                        }
                        return i;
                    }
                    return 0;
                } finally {
                }
            }
        }
        return 0;
    }

    public boolean getStorageEncryption(ComponentName who, int userHandle) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                try {
                    if (who != null) {
                        ActiveAdmin ap = getActiveAdminUncheckedLocked(who, userHandle);
                        return ap != null ? ap.encryptionRequested : false;
                    }
                    DevicePolicyData policy = getUserData(userHandle);
                    int N = policy.mAdminList.size();
                    for (int i = 0; i < N; i++) {
                        if (policy.mAdminList.get(i).encryptionRequested) {
                            return true;
                        }
                    }
                    return false;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        return false;
    }

    public int getStorageEncryptionStatus(String callerPackage, int userHandle) {
        boolean z = this.mHasFeature;
        enforceFullCrossUsersPermission(userHandle);
        ensureCallerPackage(callerPackage);
        try {
            ApplicationInfo ai = this.mIPackageManager.getApplicationInfo(callerPackage, 0, userHandle);
            boolean legacyApp = false;
            if (ai.targetSdkVersion <= 23) {
                legacyApp = true;
            }
            int rawStatus = getEncryptionStatus();
            if (rawStatus == 5 && legacyApp) {
                return 3;
            }
            return rawStatus;
        } catch (RemoteException e) {
            throw new SecurityException(e);
        }
    }

    private boolean isEncryptionSupported() {
        return getEncryptionStatus() != 0;
    }

    private int getEncryptionStatus() {
        if (this.mInjector.storageManagerIsFileBasedEncryptionEnabled()) {
            return 5;
        }
        if (this.mInjector.storageManagerIsNonDefaultBlockEncrypted()) {
            return 3;
        }
        if (this.mInjector.storageManagerIsEncrypted()) {
            return 4;
        }
        if (this.mInjector.storageManagerIsEncryptable()) {
            return 1;
        }
        return 0;
    }

    private void setEncryptionRequested(boolean encrypt) {
    }

    public void setScreenCaptureDisabled(ComponentName who, boolean disabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, -1);
            if (ap.disableScreenCapture != disabled) {
                ap.disableScreenCapture = disabled;
                saveSettingsLocked(userHandle);
                updateScreenCaptureDisabled(userHandle, disabled);
            }
        }
    }

    public boolean getScreenCaptureDisabled(ComponentName who, int userHandle) {
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
                try {
                    if (who != null) {
                        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                        return admin != null ? admin.disableScreenCapture : false;
                    }
                    DevicePolicyData policy = getUserData(userHandle);
                    int N = policy.mAdminList.size();
                    for (int i = 0; i < N; i++) {
                        if (policy.mAdminList.get(i).disableScreenCapture) {
                            return true;
                        }
                    }
                    return false;
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        return false;
    }

    private void updateScreenCaptureDisabled(final int userHandle, boolean disabled) {
        this.mPolicyCache.setScreenCaptureDisabled(userHandle, disabled);
        this.mHandler.post(new Runnable() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.8
            @Override // java.lang.Runnable
            public void run() {
                try {
                    DevicePolicyManagerService.this.mInjector.getIWindowManager().refreshScreenCaptureDisabled(userHandle);
                } catch (RemoteException e) {
                    Log.w(DevicePolicyManagerService.LOG_TAG, "Unable to notify WindowManager.", e);
                }
            }
        });
    }

    public void setAutoTimeRequired(ComponentName who, boolean required) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            if (admin.requireAutoTime != required) {
                admin.requireAutoTime = required;
                saveSettingsLocked(userHandle);
            }
        }
        if (required) {
            long ident = this.mInjector.binderClearCallingIdentity();
            try {
                this.mInjector.settingsGlobalPutInt("auto_time", 1);
            } finally {
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    public boolean getAutoTimeRequired() {
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
                ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
                if (deviceOwner != null && deviceOwner.requireAutoTime) {
                    return true;
                }
                for (Integer userId : this.mOwners.getProfileOwnerKeys()) {
                    ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId.intValue());
                    if (profileOwner != null && profileOwner.requireAutoTime) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    public void setForceEphemeralUsers(ComponentName who, boolean forceEphemeralUsers) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (forceEphemeralUsers && !this.mInjector.userManagerIsSplitSystemUser()) {
            throw new UnsupportedOperationException("Cannot force ephemeral users on systems without split system user.");
        }
        boolean removeAllUsers = false;
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getActiveAdminForCallerLocked(who, -2);
            if (deviceOwner.forceEphemeralUsers != forceEphemeralUsers) {
                deviceOwner.forceEphemeralUsers = forceEphemeralUsers;
                saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
                this.mUserManagerInternal.setForceEphemeralUsers(forceEphemeralUsers);
                removeAllUsers = forceEphemeralUsers;
            }
        }
        if (removeAllUsers) {
            long identitity = this.mInjector.binderClearCallingIdentity();
            try {
                this.mUserManagerInternal.removeAllUsers();
            } finally {
                this.mInjector.binderRestoreCallingIdentity(identitity);
            }
        }
    }

    public boolean getForceEphemeralUsers(ComponentName who) {
        boolean z;
        if (!this.mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getActiveAdminForCallerLocked(who, -2);
            z = deviceOwner.forceEphemeralUsers;
        }
        return z;
    }

    private void ensureDeviceOwnerAndAllUsersAffiliated(ComponentName who) throws SecurityException {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
            if (!areAllUsersAffiliatedWithDeviceLocked()) {
                throw new SecurityException("Not all users are affiliated.");
            }
        }
    }

    public boolean requestBugreport(ComponentName who) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            ensureDeviceOwnerAndAllUsersAffiliated(who);
            if (this.mRemoteBugreportServiceIsActive.get() || getDeviceOwnerRemoteBugreportUri() != null) {
                Slog.d(LOG_TAG, "Remote bugreport wasn't started because there's already one running.");
                return false;
            }
            long currentTime = System.currentTimeMillis();
            synchronized (getLockObject()) {
                DevicePolicyData policyData = getUserData(0);
                if (currentTime > policyData.mLastBugReportRequestTime) {
                    policyData.mLastBugReportRequestTime = currentTime;
                    saveSettingsLocked(0);
                }
            }
            long callingIdentity = this.mInjector.binderClearCallingIdentity();
            try {
                this.mInjector.getIActivityManager().requestBugReport(2);
                this.mRemoteBugreportServiceIsActive.set(true);
                this.mRemoteBugreportSharingAccepted.set(false);
                registerRemoteBugreportReceivers();
                this.mInjector.getNotificationManager().notifyAsUser(LOG_TAG, 678432343, RemoteBugreportUtils.buildNotification(this.mContext, 1), UserHandle.ALL);
                this.mHandler.postDelayed(this.mRemoteBugreportTimeoutRunnable, 600000L);
                return true;
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to make remote calls to start bugreportremote service", re);
                return false;
            } finally {
                this.mInjector.binderRestoreCallingIdentity(callingIdentity);
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendDeviceOwnerCommand(String action, Bundle extras) {
        int deviceOwnerUserId;
        ComponentName deviceOwnerComponent;
        synchronized (getLockObject()) {
            deviceOwnerUserId = this.mOwners.getDeviceOwnerUserId();
            deviceOwnerComponent = this.mOwners.getDeviceOwnerComponent();
        }
        sendActiveAdminCommand(action, extras, deviceOwnerUserId, deviceOwnerComponent);
    }

    private void sendProfileOwnerCommand(String action, Bundle extras, int userHandle) {
        sendActiveAdminCommand(action, extras, userHandle, this.mOwners.getProfileOwnerComponent(userHandle));
    }

    private void sendActiveAdminCommand(String action, Bundle extras, int userHandle, ComponentName receiverComponent) {
        Intent intent = new Intent(action);
        intent.setComponent(receiverComponent);
        if (extras != null) {
            intent.putExtras(extras);
        }
        this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userHandle));
    }

    private void sendOwnerChangedBroadcast(String broadcast, int userId) {
        Intent intent = new Intent(broadcast).addFlags(16777216);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getDeviceOwnerRemoteBugreportUri() {
        String deviceOwnerRemoteBugreportUri;
        synchronized (getLockObject()) {
            deviceOwnerRemoteBugreportUri = this.mOwners.getDeviceOwnerRemoteBugreportUri();
        }
        return deviceOwnerRemoteBugreportUri;
    }

    private void setDeviceOwnerRemoteBugreportUriAndHash(String bugreportUri, String bugreportHash) {
        synchronized (getLockObject()) {
            this.mOwners.setDeviceOwnerRemoteBugreportUriAndHash(bugreportUri, bugreportHash);
        }
    }

    private void registerRemoteBugreportReceivers() {
        try {
            IntentFilter filterFinished = new IntentFilter("android.intent.action.REMOTE_BUGREPORT_DISPATCH", "application/vnd.android.bugreport");
            this.mContext.registerReceiver(this.mRemoteBugreportFinishedReceiver, filterFinished);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Slog.w(LOG_TAG, "Failed to set type application/vnd.android.bugreport", e);
        }
        IntentFilter filterConsent = new IntentFilter();
        filterConsent.addAction("com.android.server.action.REMOTE_BUGREPORT_SHARING_DECLINED");
        filterConsent.addAction("com.android.server.action.REMOTE_BUGREPORT_SHARING_ACCEPTED");
        this.mContext.registerReceiver(this.mRemoteBugreportConsentReceiver, filterConsent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBugreportFinished(Intent intent) {
        this.mHandler.removeCallbacks(this.mRemoteBugreportTimeoutRunnable);
        this.mRemoteBugreportServiceIsActive.set(false);
        Uri bugreportUri = intent.getData();
        String bugreportUriString = null;
        if (bugreportUri != null) {
            bugreportUriString = bugreportUri.toString();
        }
        String bugreportHash = intent.getStringExtra("android.intent.extra.REMOTE_BUGREPORT_HASH");
        if (this.mRemoteBugreportSharingAccepted.get()) {
            shareBugreportWithDeviceOwnerIfExists(bugreportUriString, bugreportHash);
            this.mInjector.getNotificationManager().cancel(LOG_TAG, 678432343);
        } else {
            setDeviceOwnerRemoteBugreportUriAndHash(bugreportUriString, bugreportHash);
            this.mInjector.getNotificationManager().notifyAsUser(LOG_TAG, 678432343, RemoteBugreportUtils.buildNotification(this.mContext, 3), UserHandle.ALL);
        }
        this.mContext.unregisterReceiver(this.mRemoteBugreportFinishedReceiver);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBugreportFailed() {
        this.mRemoteBugreportServiceIsActive.set(false);
        this.mInjector.systemPropertiesSet("ctl.stop", "bugreportremote");
        this.mRemoteBugreportSharingAccepted.set(false);
        setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        this.mInjector.getNotificationManager().cancel(LOG_TAG, 678432343);
        Bundle extras = new Bundle();
        extras.putInt("android.app.extra.BUGREPORT_FAILURE_REASON", 0);
        sendDeviceOwnerCommand("android.app.action.BUGREPORT_FAILED", extras);
        this.mContext.unregisterReceiver(this.mRemoteBugreportConsentReceiver);
        this.mContext.unregisterReceiver(this.mRemoteBugreportFinishedReceiver);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBugreportSharingAccepted() {
        String bugreportUriString;
        String bugreportHash;
        this.mRemoteBugreportSharingAccepted.set(true);
        synchronized (getLockObject()) {
            bugreportUriString = getDeviceOwnerRemoteBugreportUri();
            bugreportHash = this.mOwners.getDeviceOwnerRemoteBugreportHash();
        }
        if (bugreportUriString != null) {
            shareBugreportWithDeviceOwnerIfExists(bugreportUriString, bugreportHash);
        } else if (this.mRemoteBugreportServiceIsActive.get()) {
            this.mInjector.getNotificationManager().notifyAsUser(LOG_TAG, 678432343, RemoteBugreportUtils.buildNotification(this.mContext, 2), UserHandle.ALL);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBugreportSharingDeclined() {
        if (this.mRemoteBugreportServiceIsActive.get()) {
            this.mInjector.systemPropertiesSet("ctl.stop", "bugreportremote");
            this.mRemoteBugreportServiceIsActive.set(false);
            this.mHandler.removeCallbacks(this.mRemoteBugreportTimeoutRunnable);
            this.mContext.unregisterReceiver(this.mRemoteBugreportFinishedReceiver);
        }
        this.mRemoteBugreportSharingAccepted.set(false);
        setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        sendDeviceOwnerCommand("android.app.action.BUGREPORT_SHARING_DECLINED", null);
    }

    private void shareBugreportWithDeviceOwnerIfExists(String bugreportUriString, String bugreportHash) {
        ParcelFileDescriptor pfd = null;
        try {
            try {
            } catch (Throwable th) {
                if (0 != 0) {
                    try {
                        pfd.close();
                    } catch (IOException e) {
                    }
                }
                this.mRemoteBugreportSharingAccepted.set(false);
                setDeviceOwnerRemoteBugreportUriAndHash(null, null);
                throw th;
            }
        } catch (FileNotFoundException e2) {
            Bundle extras = new Bundle();
            extras.putInt("android.app.extra.BUGREPORT_FAILURE_REASON", 1);
            sendDeviceOwnerCommand("android.app.action.BUGREPORT_FAILED", extras);
            if (0 != 0) {
                try {
                    pfd.close();
                } catch (IOException e3) {
                }
            }
        }
        if (bugreportUriString == null) {
            throw new FileNotFoundException();
        }
        Uri bugreportUri = Uri.parse(bugreportUriString);
        ParcelFileDescriptor pfd2 = this.mContext.getContentResolver().openFileDescriptor(bugreportUri, "r");
        synchronized (getLockObject()) {
            Intent intent = new Intent("android.app.action.BUGREPORT_SHARE");
            intent.setComponent(this.mOwners.getDeviceOwnerComponent());
            intent.setDataAndType(bugreportUri, "application/vnd.android.bugreport");
            intent.putExtra("android.app.extra.BUGREPORT_HASH", bugreportHash);
            intent.setFlags(1);
            ((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).grantUriPermissionFromIntent(2000, this.mOwners.getDeviceOwnerComponent().getPackageName(), intent, this.mOwners.getDeviceOwnerUserId());
            this.mContext.sendBroadcastAsUser(intent, UserHandle.of(this.mOwners.getDeviceOwnerUserId()));
        }
        if (pfd2 != null) {
            try {
                pfd2.close();
            } catch (IOException e4) {
            }
        }
        this.mRemoteBugreportSharingAccepted.set(false);
        setDeviceOwnerRemoteBugreportUriAndHash(null, null);
    }

    public void setCameraDisabled(ComponentName who, boolean disabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 8);
            if (ap.disableCamera != disabled) {
                ap.disableCamera = disabled;
                saveSettingsLocked(userHandle);
            }
        }
        pushUserRestrictions(userHandle);
    }

    public boolean getCameraDisabled(ComponentName who, int userHandle) {
        return getCameraDisabled(who, userHandle, true);
    }

    private boolean getCameraDisabled(ComponentName who, int userHandle, boolean mergeDeviceOwnerRestriction) {
        ActiveAdmin deviceOwner;
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
                try {
                    if (who != null) {
                        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                        return admin != null ? admin.disableCamera : false;
                    } else if (mergeDeviceOwnerRestriction && (deviceOwner = getDeviceOwnerAdminLocked()) != null && deviceOwner.disableCamera) {
                        return true;
                    } else {
                        DevicePolicyData policy = getUserData(userHandle);
                        int N = policy.mAdminList.size();
                        for (int i = 0; i < N; i++) {
                            if (policy.mAdminList.get(i).disableCamera) {
                                return true;
                            }
                        }
                        return false;
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        return false;
    }

    public void setKeyguardDisabledFeatures(ComponentName who, int which, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        if (isManagedProfile(userHandle)) {
            if (parent) {
                which &= 432;
            } else {
                which &= PROFILE_KEYGUARD_FEATURES;
            }
        }
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, 9, parent);
            if (ap.disabledKeyguardFeatures != which) {
                ap.disabledKeyguardFeatures = which;
                saveSettingsLocked(userHandle);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            int affectedUserId = parent ? getProfileParentId(userHandle) : userHandle;
            SecurityLog.writeEvent(210021, new Object[]{who.getPackageName(), Integer.valueOf(userHandle), Integer.valueOf(affectedUserId), Integer.valueOf(which)});
        }
    }

    public int getKeyguardDisabledFeatures(ComponentName who, int userHandle, boolean parent) {
        List<ActiveAdmin> admins;
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            long ident = this.mInjector.binderClearCallingIdentity();
            try {
                synchronized (getLockObject()) {
                    if (who != null) {
                        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                        return admin != null ? admin.disabledKeyguardFeatures : 0;
                    }
                    if (!parent && isManagedProfile(userHandle)) {
                        admins = getUserDataUnchecked(userHandle).mAdminList;
                    } else {
                        admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                    }
                    int N = admins.size();
                    int which = 0;
                    for (int which2 = 0; which2 < N; which2++) {
                        ActiveAdmin admin2 = admins.get(which2);
                        int userId = admin2.getUserHandle().getIdentifier();
                        boolean isRequestedUser = !parent && userId == userHandle;
                        if (!isRequestedUser && isManagedProfile(userId)) {
                            which |= admin2.disabledKeyguardFeatures & 432;
                        }
                        which |= admin2.disabledKeyguardFeatures;
                    }
                    return which;
                }
            } finally {
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
        return 0;
    }

    public void setKeepUninstalledPackages(ComponentName who, String callerPackage, List<String> packageList) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(packageList, "packageList is null");
        int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -2, "delegation-keep-uninstalled-packages");
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            deviceOwner.keepUninstalledPackages = packageList;
            saveSettingsLocked(userHandle);
            this.mInjector.getPackageManagerInternal().setKeepUninstalledPackages(packageList);
        }
    }

    public List<String> getKeepUninstalledPackages(ComponentName who, String callerPackage) {
        List<String> keepUninstalledPackagesLocked;
        if (!this.mHasFeature) {
            return null;
        }
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -2, "delegation-keep-uninstalled-packages");
            keepUninstalledPackagesLocked = getKeepUninstalledPackagesLocked();
        }
        return keepUninstalledPackagesLocked;
    }

    private List<String> getKeepUninstalledPackagesLocked() {
        ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        if (deviceOwner != null) {
            return deviceOwner.keepUninstalledPackages;
        }
        return null;
    }

    public boolean setDeviceOwner(ComponentName admin, String ownerName, int userId) {
        if (this.mHasFeature) {
            if (admin == null || !isPackageInstalledForUser(admin.getPackageName(), userId)) {
                throw new IllegalArgumentException("Invalid component " + admin + " for device owner");
            }
            boolean hasIncompatibleAccountsOrNonAdb = hasIncompatibleAccountsOrNonAdbNoLock(userId, admin);
            synchronized (getLockObject()) {
                enforceCanSetDeviceOwnerLocked(admin, userId, hasIncompatibleAccountsOrNonAdb);
                ActiveAdmin activeAdmin = getActiveAdminUncheckedLocked(admin, userId);
                if (activeAdmin == null || getUserData(userId).mRemovingAdmins.contains(admin)) {
                    throw new IllegalArgumentException("Not active admin: " + admin);
                }
                long ident = this.mInjector.binderClearCallingIdentity();
                try {
                    if (this.mInjector.getIBackupManager() != null) {
                        this.mInjector.getIBackupManager().setBackupServiceActive(0, false);
                    }
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    if (isAdb()) {
                        MetricsLogger.action(this.mContext, (int) NetworkManagementService.NetdResponseCode.StrictCleartext, LOG_TAG_DEVICE_OWNER);
                    }
                    this.mOwners.setDeviceOwner(admin, ownerName, userId);
                    this.mOwners.writeDeviceOwner();
                    updateDeviceOwnerLocked();
                    setDeviceOwnerSystemPropertyLocked();
                    Set<String> restrictions = UserRestrictionsUtils.getDefaultEnabledForDeviceOwner();
                    if (!restrictions.isEmpty()) {
                        for (String restriction : restrictions) {
                            activeAdmin.ensureUserRestrictions().putBoolean(restriction, true);
                        }
                        activeAdmin.defaultEnabledRestrictionsAlreadySet.addAll(restrictions);
                        Slog.i(LOG_TAG, "Enabled the following restrictions by default: " + restrictions);
                        saveUserRestrictionsLocked(userId);
                    }
                    long ident2 = this.mInjector.binderClearCallingIdentity();
                    sendOwnerChangedBroadcast("android.app.action.DEVICE_OWNER_CHANGED", userId);
                    this.mInjector.binderRestoreCallingIdentity(ident2);
                    this.mDeviceAdminServiceController.startServiceForOwner(admin.getPackageName(), userId, "set-device-owner");
                    Slog.i(LOG_TAG, "Device owner set: " + admin + " on user " + userId);
                } catch (RemoteException e) {
                    throw new IllegalStateException("Failed deactivating backup service.", e);
                }
            }
            return true;
        }
        return false;
    }

    public boolean hasDeviceOwner() {
        enforceDeviceOwnerOrManageUsers();
        return this.mOwners.hasDeviceOwner();
    }

    boolean isDeviceOwner(ActiveAdmin admin) {
        return isDeviceOwner(admin.info.getComponent(), admin.getUserHandle().getIdentifier());
    }

    public boolean isDeviceOwner(ComponentName who, int userId) {
        boolean z;
        synchronized (getLockObject()) {
            z = this.mOwners.hasDeviceOwner() && this.mOwners.getDeviceOwnerUserId() == userId && this.mOwners.getDeviceOwnerComponent().equals(who);
        }
        return z;
    }

    private boolean isDeviceOwnerPackage(String packageName, int userId) {
        boolean z;
        synchronized (getLockObject()) {
            z = this.mOwners.hasDeviceOwner() && this.mOwners.getDeviceOwnerUserId() == userId && this.mOwners.getDeviceOwnerPackageName().equals(packageName);
        }
        return z;
    }

    private boolean isProfileOwnerPackage(String packageName, int userId) {
        boolean z;
        synchronized (getLockObject()) {
            z = this.mOwners.hasProfileOwner(userId) && this.mOwners.getProfileOwnerPackage(userId).equals(packageName);
        }
        return z;
    }

    public boolean isProfileOwner(ComponentName who, int userId) {
        ComponentName profileOwner = getProfileOwner(userId);
        return who != null && who.equals(profileOwner);
    }

    public ComponentName getDeviceOwnerComponent(boolean callingUserOnly) {
        if (this.mHasFeature) {
            if (!callingUserOnly) {
                enforceManageUsers();
            }
            synchronized (getLockObject()) {
                if (this.mOwners.hasDeviceOwner()) {
                    if (!callingUserOnly || this.mInjector.userHandleGetCallingUserId() == this.mOwners.getDeviceOwnerUserId()) {
                        return this.mOwners.getDeviceOwnerComponent();
                    }
                    return null;
                }
                return null;
            }
        }
        return null;
    }

    public int getDeviceOwnerUserId() {
        int deviceOwnerUserId;
        if (this.mHasFeature) {
            enforceManageUsers();
            synchronized (getLockObject()) {
                deviceOwnerUserId = this.mOwners.hasDeviceOwner() ? this.mOwners.getDeviceOwnerUserId() : -10000;
            }
            return deviceOwnerUserId;
        }
        return -10000;
    }

    public String getDeviceOwnerName() {
        if (this.mHasFeature) {
            enforceManageUsers();
            synchronized (getLockObject()) {
                if (this.mOwners.hasDeviceOwner()) {
                    String deviceOwnerPackage = this.mOwners.getDeviceOwnerPackageName();
                    return getApplicationLabel(deviceOwnerPackage, 0);
                }
                return null;
            }
        }
        return null;
    }

    @VisibleForTesting
    ActiveAdmin getDeviceOwnerAdminLocked() {
        ensureLocked();
        ComponentName component = this.mOwners.getDeviceOwnerComponent();
        if (component == null) {
            return null;
        }
        DevicePolicyData policy = getUserData(this.mOwners.getDeviceOwnerUserId());
        int n = policy.mAdminList.size();
        for (int i = 0; i < n; i++) {
            ActiveAdmin admin = policy.mAdminList.get(i);
            if (component.equals(admin.info.getComponent())) {
                return admin;
            }
        }
        Slog.wtf(LOG_TAG, "Active admin for device owner not found. component=" + component);
        return null;
    }

    public void clearDeviceOwner(String packageName) {
        Preconditions.checkNotNull(packageName, "packageName is null");
        int callingUid = this.mInjector.binderGetCallingUid();
        try {
            int uid = this.mInjector.getPackageManager().getPackageUidAsUser(packageName, UserHandle.getUserId(callingUid));
            if (uid != callingUid) {
                throw new SecurityException("Invalid packageName");
            }
            synchronized (getLockObject()) {
                ComponentName deviceOwnerComponent = this.mOwners.getDeviceOwnerComponent();
                int deviceOwnerUserId = this.mOwners.getDeviceOwnerUserId();
                if (!this.mOwners.hasDeviceOwner() || !deviceOwnerComponent.getPackageName().equals(packageName) || deviceOwnerUserId != UserHandle.getUserId(callingUid)) {
                    throw new SecurityException("clearDeviceOwner can only be called by the device owner");
                }
                enforceUserUnlocked(deviceOwnerUserId);
                ActiveAdmin admin = getDeviceOwnerAdminLocked();
                long ident = this.mInjector.binderClearCallingIdentity();
                clearDeviceOwnerLocked(admin, deviceOwnerUserId);
                removeActiveAdminLocked(deviceOwnerComponent, deviceOwnerUserId);
                sendOwnerChangedBroadcast("android.app.action.DEVICE_OWNER_CHANGED", deviceOwnerUserId);
                this.mInjector.binderRestoreCallingIdentity(ident);
                Slog.i(LOG_TAG, "Device owner removed: " + deviceOwnerComponent);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(e);
        }
    }

    private void clearOverrideApnUnchecked() {
        setOverrideApnsEnabledUnchecked(false);
        List<ApnSetting> apns = getOverrideApnsUnchecked();
        for (int i = 0; i < apns.size(); i++) {
            removeOverrideApnUnchecked(apns.get(i).getId());
        }
    }

    private void clearDeviceOwnerLocked(ActiveAdmin admin, int userId) {
        this.mDeviceAdminServiceController.stopServiceForOwner(userId, "clear-device-owner");
        if (admin != null) {
            admin.disableCamera = false;
            admin.userRestrictions = null;
            admin.defaultEnabledRestrictionsAlreadySet.clear();
            admin.forceEphemeralUsers = false;
            admin.isNetworkLoggingEnabled = false;
            this.mUserManagerInternal.setForceEphemeralUsers(admin.forceEphemeralUsers);
        }
        DevicePolicyData policyData = getUserData(userId);
        policyData.mCurrentInputMethodSet = false;
        saveSettingsLocked(userId);
        DevicePolicyData systemPolicyData = getUserData(0);
        systemPolicyData.mLastSecurityLogRetrievalTime = -1L;
        systemPolicyData.mLastBugReportRequestTime = -1L;
        systemPolicyData.mLastNetworkLogsRetrievalTime = -1L;
        saveSettingsLocked(0);
        clearUserPoliciesLocked(userId);
        clearOverrideApnUnchecked();
        this.mOwners.clearDeviceOwner();
        this.mOwners.writeDeviceOwner();
        updateDeviceOwnerLocked();
        clearDeviceOwnerUserRestrictionLocked(UserHandle.of(userId));
        this.mInjector.securityLogSetLoggingEnabledProperty(false);
        this.mSecurityLogMonitor.stop();
        setNetworkLoggingActiveInternal(false);
        deleteTransferOwnershipBundleLocked(userId);
        try {
            if (this.mInjector.getIBackupManager() != null) {
                this.mInjector.getIBackupManager().setBackupServiceActive(0, true);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed reactivating backup service.", e);
        }
    }

    public boolean setProfileOwner(ComponentName who, String ownerName, int userHandle) {
        if (!this.mHasFeature) {
            return false;
        }
        if (who == null || !isPackageInstalledForUser(who.getPackageName(), userHandle)) {
            throw new IllegalArgumentException("Component " + who + " not installed for userId:" + userHandle);
        }
        boolean hasIncompatibleAccountsOrNonAdb = hasIncompatibleAccountsOrNonAdbNoLock(userHandle, who);
        synchronized (getLockObject()) {
            enforceCanSetProfileOwnerLocked(who, userHandle, hasIncompatibleAccountsOrNonAdb);
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin == null || getUserData(userHandle).mRemovingAdmins.contains(who)) {
                throw new IllegalArgumentException("Not active admin: " + who);
            }
            if (isAdb()) {
                MetricsLogger.action(this.mContext, (int) NetworkManagementService.NetdResponseCode.StrictCleartext, LOG_TAG_PROFILE_OWNER);
            }
            this.mOwners.setProfileOwner(who, ownerName, userHandle);
            this.mOwners.writeProfileOwner(userHandle);
            Slog.i(LOG_TAG, "Profile owner set: " + who + " on user " + userHandle);
            long id = this.mInjector.binderClearCallingIdentity();
            if (this.mUserManager.isManagedProfile(userHandle)) {
                maybeSetDefaultRestrictionsForAdminLocked(userHandle, admin, UserRestrictionsUtils.getDefaultEnabledForManagedProfiles());
                ensureUnknownSourcesRestrictionForProfileOwnerLocked(userHandle, admin, true);
            }
            sendOwnerChangedBroadcast("android.app.action.PROFILE_OWNER_CHANGED", userHandle);
            this.mInjector.binderRestoreCallingIdentity(id);
            this.mDeviceAdminServiceController.startServiceForOwner(who.getPackageName(), userHandle, "set-profile-owner");
        }
        return true;
    }

    public void clearProfileOwner(ComponentName who) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        enforceNotManagedProfile(userId, "clear profile owner");
        enforceUserUnlocked(userId);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            long ident = this.mInjector.binderClearCallingIdentity();
            clearProfileOwnerLocked(admin, userId);
            removeActiveAdminLocked(who, userId);
            sendOwnerChangedBroadcast("android.app.action.PROFILE_OWNER_CHANGED", userId);
            this.mInjector.binderRestoreCallingIdentity(ident);
            Slog.i(LOG_TAG, "Profile owner " + who + " removed from user " + userId);
        }
    }

    public void clearProfileOwnerLocked(ActiveAdmin admin, int userId) {
        this.mDeviceAdminServiceController.stopServiceForOwner(userId, "clear-profile-owner");
        if (admin != null) {
            admin.disableCamera = false;
            admin.userRestrictions = null;
            admin.defaultEnabledRestrictionsAlreadySet.clear();
        }
        DevicePolicyData policyData = getUserData(userId);
        policyData.mCurrentInputMethodSet = false;
        policyData.mOwnerInstalledCaCerts.clear();
        saveSettingsLocked(userId);
        clearUserPoliciesLocked(userId);
        this.mOwners.removeProfileOwner(userId);
        this.mOwners.writeProfileOwner(userId);
        deleteTransferOwnershipBundleLocked(userId);
    }

    public void setDeviceOwnerLockScreenInfo(ComponentName who, CharSequence info) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!this.mHasFeature) {
            return;
        }
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
            long token = this.mInjector.binderClearCallingIdentity();
            this.mLockPatternUtils.setDeviceOwnerInfo(info != null ? info.toString() : null);
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    public CharSequence getDeviceOwnerLockScreenInfo() {
        return this.mLockPatternUtils.getDeviceOwnerInfo();
    }

    private void clearUserPoliciesLocked(int userId) {
        DevicePolicyData policy = getUserData(userId);
        policy.mPermissionPolicy = 0;
        policy.mDelegationMap.clear();
        policy.mStatusBarDisabled = false;
        policy.mUserProvisioningState = 0;
        policy.mAffiliationIds.clear();
        policy.mLockTaskPackages.clear();
        updateLockTaskPackagesLocked(policy.mLockTaskPackages, userId);
        policy.mLockTaskFeatures = 0;
        saveSettingsLocked(userId);
        try {
            this.mIPackageManager.updatePermissionFlagsForAllApps(4, 0, userId);
            pushUserRestrictions(userId);
        } catch (RemoteException e) {
        }
    }

    public boolean hasUserSetupCompleted() {
        return hasUserSetupCompleted(UserHandle.getCallingUserId());
    }

    private boolean hasUserSetupCompleted(int userHandle) {
        if (!this.mHasFeature) {
            return true;
        }
        return getUserData(userHandle).mUserSetupComplete;
    }

    private boolean hasPaired(int userHandle) {
        if (!this.mHasFeature) {
            return true;
        }
        return getUserData(userHandle).mPaired;
    }

    public int getUserProvisioningState() {
        if (!this.mHasFeature) {
            return 0;
        }
        enforceManageUsers();
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        return getUserProvisioningState(userHandle);
    }

    private int getUserProvisioningState(int userHandle) {
        return getUserData(userHandle).mUserProvisioningState;
    }

    public void setUserProvisioningState(int newState, int userHandle) {
        if (!this.mHasFeature) {
            return;
        }
        if (userHandle != this.mOwners.getDeviceOwnerUserId() && !this.mOwners.hasProfileOwner(userHandle) && getManagedUserId(userHandle) == -1) {
            throw new IllegalStateException("Not allowed to change provisioning state unless a device or profile owner is set.");
        }
        synchronized (getLockObject()) {
            boolean transitionCheckNeeded = true;
            if (isAdb()) {
                if (getUserProvisioningState(userHandle) != 0 || newState != 3) {
                    throw new IllegalStateException("Not allowed to change provisioning state unless current provisioning state is unmanaged, and new state is finalized.");
                }
                transitionCheckNeeded = false;
            } else {
                enforceCanManageProfileAndDeviceOwners();
            }
            DevicePolicyData policyData = getUserData(userHandle);
            if (transitionCheckNeeded) {
                checkUserProvisioningStateTransition(policyData.mUserProvisioningState, newState);
            }
            policyData.mUserProvisioningState = newState;
            saveSettingsLocked(userHandle);
        }
    }

    private void checkUserProvisioningStateTransition(int currentState, int newState) {
        if (currentState != 4) {
            switch (currentState) {
                case 0:
                    if (newState != 0) {
                        return;
                    }
                    break;
                case 1:
                case 2:
                    if (newState == 3) {
                        return;
                    }
                    break;
            }
        } else if (newState == 0) {
            return;
        }
        throw new IllegalStateException("Cannot move to user provisioning state [" + newState + "] from state [" + currentState + "]");
    }

    public void setProfileEnabled(ComponentName who) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            int userId = UserHandle.getCallingUserId();
            enforceManagedProfile(userId, "enable the profile");
            UserInfo managedProfile = getUserInfo(userId);
            if (managedProfile.isEnabled()) {
                Slog.e(LOG_TAG, "setProfileEnabled is called when the profile is already enabled");
                return;
            }
            long id = this.mInjector.binderClearCallingIdentity();
            this.mUserManager.setUserEnabled(userId);
            UserInfo parent = this.mUserManager.getProfileParent(userId);
            Intent intent = new Intent("android.intent.action.MANAGED_PROFILE_ADDED");
            intent.putExtra("android.intent.extra.USER", new UserHandle(userId));
            intent.addFlags(1342177280);
            this.mContext.sendBroadcastAsUser(intent, new UserHandle(parent.id));
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public void setProfileName(ComponentName who, String profileName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = UserHandle.getCallingUserId();
        getActiveAdminForCallerLocked(who, -1);
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            this.mUserManager.setUserName(userId, profileName);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public ComponentName getProfileOwner(int userHandle) {
        ComponentName profileOwnerComponent;
        if (!this.mHasFeature) {
            return null;
        }
        synchronized (getLockObject()) {
            profileOwnerComponent = this.mOwners.getProfileOwnerComponent(userHandle);
        }
        return profileOwnerComponent;
    }

    @VisibleForTesting
    ActiveAdmin getProfileOwnerAdminLocked(int userHandle) {
        ComponentName profileOwner = this.mOwners.getProfileOwnerComponent(userHandle);
        if (profileOwner == null) {
            return null;
        }
        DevicePolicyData policy = getUserData(userHandle);
        int n = policy.mAdminList.size();
        for (int i = 0; i < n; i++) {
            ActiveAdmin admin = policy.mAdminList.get(i);
            if (profileOwner.equals(admin.info.getComponent())) {
                return admin;
            }
        }
        return null;
    }

    public String getProfileOwnerName(int userHandle) {
        if (this.mHasFeature) {
            enforceManageUsers();
            ComponentName profileOwner = getProfileOwner(userHandle);
            if (profileOwner == null) {
                return null;
            }
            return getApplicationLabel(profileOwner.getPackageName(), userHandle);
        }
        return null;
    }

    private String getApplicationLabel(String packageName, int userHandle) {
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            UserHandle handle = new UserHandle(userHandle);
            Context userContext = this.mContext.createPackageContextAsUser(packageName, 0, handle);
            ApplicationInfo appInfo = userContext.getApplicationInfo();
            CharSequence result = appInfo != null ? appInfo.loadUnsafeLabel(userContext.getPackageManager()) : null;
            return result != null ? result.toString() : null;
        } catch (PackageManager.NameNotFoundException nnfe) {
            Log.w(LOG_TAG, packageName + " is not installed for user " + userHandle, nnfe);
            return null;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private void wtfIfInLock() {
        if (Thread.holdsLock(this)) {
            Slog.wtfStack(LOG_TAG, "Shouldn't be called with DPMS lock held");
        }
    }

    private void enforceCanSetProfileOwnerLocked(ComponentName owner, int userHandle, boolean hasIncompatibleAccountsOrNonAdb) {
        UserInfo info = getUserInfo(userHandle);
        if (info == null) {
            throw new IllegalArgumentException("Attempted to set profile owner for invalid userId: " + userHandle);
        } else if (info.isGuest()) {
            throw new IllegalStateException("Cannot set a profile owner on a guest");
        } else {
            if (this.mOwners.hasProfileOwner(userHandle)) {
                throw new IllegalStateException("Trying to set the profile owner, but profile owner is already set.");
            }
            if (this.mOwners.hasDeviceOwner() && this.mOwners.getDeviceOwnerUserId() == userHandle) {
                throw new IllegalStateException("Trying to set the profile owner, but the user already has a device owner.");
            }
            if (isAdb()) {
                if ((this.mIsWatch || hasUserSetupCompleted(userHandle)) && hasIncompatibleAccountsOrNonAdb) {
                    throw new IllegalStateException("Not allowed to set the profile owner because there are already some accounts on the profile");
                }
                return;
            }
            enforceCanManageProfileAndDeviceOwners();
            if ((this.mIsWatch || hasUserSetupCompleted(userHandle)) && !isCallerWithSystemUid()) {
                throw new IllegalStateException("Cannot set the profile owner on a user which is already set-up");
            }
        }
    }

    private void enforceCanSetDeviceOwnerLocked(ComponentName owner, int userId, boolean hasIncompatibleAccountsOrNonAdb) {
        if (!isAdb()) {
            enforceCanManageProfileAndDeviceOwners();
        }
        int code = checkDeviceOwnerProvisioningPreConditionLocked(owner, userId, isAdb(), hasIncompatibleAccountsOrNonAdb);
        switch (code) {
            case 0:
                return;
            case 1:
                throw new IllegalStateException("Trying to set the device owner, but device owner is already set.");
            case 2:
                throw new IllegalStateException("Trying to set the device owner, but the user already has a profile owner.");
            case 3:
                throw new IllegalStateException("User not running: " + userId);
            case 4:
                throw new IllegalStateException("Cannot set the device owner if the device is already set-up");
            case 5:
                throw new IllegalStateException("Not allowed to set the device owner because there are already several users on the device");
            case 6:
                throw new IllegalStateException("Not allowed to set the device owner because there are already some accounts on the device");
            case 7:
                throw new IllegalStateException("User is not system user");
            case 8:
                throw new IllegalStateException("Not allowed to set the device owner because this device has already paired");
            default:
                throw new IllegalStateException("Unexpected @ProvisioningPreCondition " + code);
        }
    }

    private void enforceUserUnlocked(int userId) {
        Preconditions.checkState(this.mUserManager.isUserUnlocked(userId), "User must be running and unlocked");
    }

    private void enforceUserUnlocked(int userId, boolean parent) {
        if (parent) {
            enforceUserUnlocked(getProfileParentId(userId));
        } else {
            enforceUserUnlocked(userId);
        }
    }

    private void enforceManageUsers() {
        int callingUid = this.mInjector.binderGetCallingUid();
        if (!isCallerWithSystemUid() && callingUid != 0) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USERS", null);
        }
    }

    private void enforceFullCrossUsersPermission(int userHandle) {
        enforceSystemUserOrPermissionIfCrossUser(userHandle, "android.permission.INTERACT_ACROSS_USERS_FULL");
    }

    private void enforceCrossUsersPermission(int userHandle) {
        enforceSystemUserOrPermissionIfCrossUser(userHandle, "android.permission.INTERACT_ACROSS_USERS");
    }

    private void enforceSystemUserOrPermission(String permission) {
        if (!isCallerWithSystemUid() && this.mInjector.binderGetCallingUid() != 0) {
            Context context = this.mContext;
            context.enforceCallingOrSelfPermission(permission, "Must be system or have " + permission + " permission");
        }
    }

    private void enforceSystemUserOrPermissionIfCrossUser(int userHandle, String permission) {
        if (userHandle < 0) {
            throw new IllegalArgumentException("Invalid userId " + userHandle);
        } else if (userHandle == this.mInjector.userHandleGetCallingUserId()) {
        } else {
            enforceSystemUserOrPermission(permission);
        }
    }

    private void enforceManagedProfile(int userHandle, String message) {
        if (!isManagedProfile(userHandle)) {
            throw new SecurityException("You can not " + message + " outside a managed profile.");
        }
    }

    private void enforceNotManagedProfile(int userHandle, String message) {
        if (isManagedProfile(userHandle)) {
            throw new SecurityException("You can not " + message + " for a managed profile.");
        }
    }

    private void enforceDeviceOwnerOrManageUsers() {
        synchronized (getLockObject()) {
            if (getActiveAdminWithPolicyForUidLocked(null, -2, this.mInjector.binderGetCallingUid()) != null) {
                return;
            }
            enforceManageUsers();
        }
    }

    private void enforceProfileOwnerOrSystemUser() {
        synchronized (getLockObject()) {
            if (getActiveAdminWithPolicyForUidLocked(null, -1, this.mInjector.binderGetCallingUid()) != null) {
                return;
            }
            Preconditions.checkState(isCallerWithSystemUid(), "Only profile owner, device owner and system may call this method.");
        }
    }

    private void enforceProfileOwnerOrFullCrossUsersPermission(int userId) {
        if (userId == this.mInjector.userHandleGetCallingUserId()) {
            synchronized (getLockObject()) {
                if (getActiveAdminWithPolicyForUidLocked(null, -1, this.mInjector.binderGetCallingUid()) != null) {
                    return;
                }
            }
        }
        enforceSystemUserOrPermission("android.permission.INTERACT_ACROSS_USERS_FULL");
    }

    private boolean canUserUseLockTaskLocked(int userId) {
        if (isUserAffiliatedWithDeviceLocked(userId)) {
            return true;
        }
        if (this.mOwners.hasDeviceOwner()) {
            return false;
        }
        ComponentName profileOwner = getProfileOwner(userId);
        return (profileOwner == null || isManagedProfile(userId)) ? false : true;
    }

    private void enforceCanCallLockTaskLocked(ComponentName who) {
        getActiveAdminForCallerLocked(who, -1);
        int userId = this.mInjector.userHandleGetCallingUserId();
        if (!canUserUseLockTaskLocked(userId)) {
            throw new SecurityException("User " + userId + " is not allowed to use lock task");
        }
    }

    private void ensureCallerPackage(String packageName) {
        if (packageName == null) {
            Preconditions.checkState(isCallerWithSystemUid(), "Only caller can omit package name");
            return;
        }
        int callingUid = this.mInjector.binderGetCallingUid();
        int userId = this.mInjector.userHandleGetCallingUserId();
        try {
            ApplicationInfo ai = this.mIPackageManager.getApplicationInfo(packageName, 0, userId);
            Preconditions.checkState(ai.uid == callingUid, "Unmatching package name");
        } catch (RemoteException e) {
        }
    }

    private boolean isCallerWithSystemUid() {
        return UserHandle.isSameApp(this.mInjector.binderGetCallingUid(), 1000);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public int getProfileParentId(int userHandle) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            UserInfo parentUser = this.mUserManager.getProfileParent(userHandle);
            return parentUser != null ? parentUser.id : userHandle;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private int getCredentialOwner(int userHandle, boolean parent) {
        long ident = this.mInjector.binderClearCallingIdentity();
        if (parent) {
            try {
                UserInfo parentProfile = this.mUserManager.getProfileParent(userHandle);
                if (parentProfile != null) {
                    userHandle = parentProfile.id;
                }
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(ident);
                throw th;
            }
        }
        int credentialOwnerProfile = this.mUserManager.getCredentialOwnerProfile(userHandle);
        this.mInjector.binderRestoreCallingIdentity(ident);
        return credentialOwnerProfile;
    }

    private boolean isManagedProfile(int userHandle) {
        UserInfo user = getUserInfo(userHandle);
        return user != null && user.isManagedProfile();
    }

    private void enableIfNecessary(String packageName, int userId) {
        try {
            ApplicationInfo ai = this.mIPackageManager.getApplicationInfo(packageName, 32768, userId);
            if (ai.enabledSetting == 4) {
                this.mIPackageManager.setApplicationEnabledSetting(packageName, 0, 1, userId, LOG_TAG);
            }
        } catch (RemoteException e) {
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, LOG_TAG, pw)) {
            synchronized (getLockObject()) {
                pw.println("Current Device Policy Manager state:");
                this.mOwners.dump("  ", pw);
                this.mDeviceAdminServiceController.dump("  ", pw);
                int userCount = this.mUserData.size();
                for (int u = 0; u < userCount; u++) {
                    DevicePolicyData policy = getUserData(this.mUserData.keyAt(u));
                    pw.println();
                    pw.println("  Enabled Device Admins (User " + policy.mUserHandle + ", provisioningState: " + policy.mUserProvisioningState + "):");
                    int N = policy.mAdminList.size();
                    for (int i = 0; i < N; i++) {
                        ActiveAdmin ap = policy.mAdminList.get(i);
                        if (ap != null) {
                            pw.print("    ");
                            pw.print(ap.info.getComponent().flattenToShortString());
                            pw.println(":");
                            ap.dump("      ", pw);
                        }
                    }
                    if (!policy.mRemovingAdmins.isEmpty()) {
                        pw.println("    Removing Device Admins (User " + policy.mUserHandle + "): " + policy.mRemovingAdmins);
                    }
                    pw.println(" ");
                    pw.print("    mPasswordOwner=");
                    pw.println(policy.mPasswordOwner);
                }
                pw.println();
                this.mConstants.dump("  ", pw);
                pw.println();
                this.mStatLogger.dump(pw, "  ");
                pw.println();
                pw.println("  Encryption Status: " + getEncryptionStatusName(getEncryptionStatus()));
            }
        }
    }

    private String getEncryptionStatusName(int encryptionStatus) {
        switch (encryptionStatus) {
            case 0:
                return "unsupported";
            case 1:
                return "inactive";
            case 2:
                return "activating";
            case 3:
                return "block";
            case 4:
                return "block default key";
            case 5:
                return "per-user";
            default:
                return UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
        }
    }

    public void addPersistentPreferredActivity(ComponentName who, IntentFilter filter, ComponentName activity) {
        Injector injector;
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                this.mIPackageManager.addPersistentPreferredActivity(filter, activity, userHandle);
                this.mIPackageManager.flushPackageRestrictionsAsUser(userHandle);
                injector = this.mInjector;
            } catch (RemoteException e) {
                injector = this.mInjector;
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(id);
                throw th;
            }
            injector.binderRestoreCallingIdentity(id);
        }
    }

    public void clearPackagePersistentPreferredActivities(ComponentName who, String packageName) {
        Injector injector;
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                this.mIPackageManager.clearPackagePersistentPreferredActivities(packageName, userHandle);
                this.mIPackageManager.flushPackageRestrictionsAsUser(userHandle);
                injector = this.mInjector;
            } catch (RemoteException e) {
                injector = this.mInjector;
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(id);
                throw th;
            }
            injector.binderRestoreCallingIdentity(id);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void setDefaultSmsApplication(ComponentName admin, final String packageName) {
        Preconditions.checkNotNull(admin, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -2);
        }
        this.mInjector.binderWithCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$dDeS1FUetDCbtT673Qp0Hcsm5Vw
            public final void runOrThrow() {
                SmsApplication.setDefaultApplication(packageName, DevicePolicyManagerService.this.mContext);
            }
        });
    }

    public boolean setApplicationRestrictionsManagingPackage(ComponentName admin, String packageName) {
        try {
            setDelegatedScopePreO(admin, packageName, "delegation-app-restrictions");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String getApplicationRestrictionsManagingPackage(ComponentName admin) {
        List<String> delegatePackages = getDelegatePackages(admin, "delegation-app-restrictions");
        if (delegatePackages.size() > 0) {
            return delegatePackages.get(0);
        }
        return null;
    }

    public boolean isCallerApplicationRestrictionsManagingPackage(String callerPackage) {
        return isCallerDelegate(callerPackage, "delegation-app-restrictions");
    }

    public void setApplicationRestrictions(ComponentName who, String callerPackage, String packageName, Bundle settings) {
        enforceCanManageScope(who, callerPackage, -1, "delegation-app-restrictions");
        UserHandle userHandle = this.mInjector.binderGetCallingUserHandle();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            this.mUserManager.setApplicationRestrictions(packageName, settings, userHandle);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public void setTrustAgentConfiguration(ComponentName admin, ComponentName agent, PersistableBundle args, boolean parent) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin, "admin is null");
        Preconditions.checkNotNull(agent, "agent is null");
        int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(admin, 9, parent);
            ap.trustAgentInfos.put(agent.flattenToString(), new ActiveAdmin.TrustAgentInfo(args));
            saveSettingsLocked(userHandle);
        }
    }

    public List<PersistableBundle> getTrustAgentConfiguration(ComponentName admin, ComponentName agent, int userHandle, boolean parent) {
        String componentName;
        if (this.mHasFeature) {
            Preconditions.checkNotNull(agent, "agent null");
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                String componentName2 = agent.flattenToString();
                if (admin != null) {
                    ActiveAdmin ap = getActiveAdminUncheckedLocked(admin, userHandle, parent);
                    if (ap == null) {
                        return null;
                    }
                    ActiveAdmin.TrustAgentInfo trustAgentInfo = ap.trustAgentInfos.get(componentName2);
                    if (trustAgentInfo != null && trustAgentInfo.options != null) {
                        List<PersistableBundle> result = new ArrayList<>();
                        result.add(trustAgentInfo.options);
                        return result;
                    }
                    return null;
                }
                List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                boolean allAdminsHaveOptions = true;
                int N = admins.size();
                List<PersistableBundle> result2 = null;
                int i = 0;
                while (true) {
                    if (i >= N) {
                        break;
                    }
                    ActiveAdmin active = admins.get(i);
                    boolean disablesTrust = (active.disabledKeyguardFeatures & 16) != 0;
                    ActiveAdmin.TrustAgentInfo info = active.trustAgentInfos.get(componentName2);
                    if (info != null && info.options != null && !info.options.isEmpty()) {
                        if (disablesTrust) {
                            if (result2 == null) {
                                result2 = new ArrayList<>();
                            }
                            result2.add(info.options);
                            componentName = componentName2;
                        } else {
                            componentName = componentName2;
                            Log.w(LOG_TAG, "Ignoring admin " + active.info + " because it has trust options but doesn't declare KEYGUARD_DISABLE_TRUST_AGENTS");
                        }
                    } else {
                        componentName = componentName2;
                        if (disablesTrust) {
                            allAdminsHaveOptions = false;
                            break;
                        }
                    }
                    i++;
                    componentName2 = componentName;
                }
                return allAdminsHaveOptions ? result2 : null;
            }
        }
        return null;
    }

    public void setRestrictionsProvider(ComponentName who, ComponentName permissionProvider) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            int userHandle = UserHandle.getCallingUserId();
            DevicePolicyData userData = getUserData(userHandle);
            userData.mRestrictionsProvider = permissionProvider;
            saveSettingsLocked(userHandle);
        }
    }

    public ComponentName getRestrictionsProvider(int userHandle) {
        ComponentName componentName;
        synchronized (getLockObject()) {
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query the permission provider");
            }
            DevicePolicyData userData = getUserData(userHandle);
            componentName = userData != null ? userData.mRestrictionsProvider : null;
        }
        return componentName;
    }

    public void addCrossProfileIntentFilter(ComponentName who, IntentFilter filter, int flags) {
        Injector injector;
        UserInfo parent;
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                parent = this.mUserManager.getProfileParent(callingUserId);
            } catch (RemoteException e) {
                injector = this.mInjector;
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(id);
                throw th;
            }
            if (parent == null) {
                Slog.e(LOG_TAG, "Cannot call addCrossProfileIntentFilter if there is no parent");
                this.mInjector.binderRestoreCallingIdentity(id);
                return;
            }
            if ((flags & 1) != 0) {
                this.mIPackageManager.addCrossProfileIntentFilter(filter, who.getPackageName(), callingUserId, parent.id, 0);
            }
            if ((flags & 2) != 0) {
                this.mIPackageManager.addCrossProfileIntentFilter(filter, who.getPackageName(), parent.id, callingUserId, 0);
            }
            injector = this.mInjector;
            injector.binderRestoreCallingIdentity(id);
        }
    }

    public void clearCrossProfileIntentFilters(ComponentName who) {
        Injector injector;
        UserInfo parent;
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                parent = this.mUserManager.getProfileParent(callingUserId);
            } catch (RemoteException e) {
                injector = this.mInjector;
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(id);
                throw th;
            }
            if (parent == null) {
                Slog.e(LOG_TAG, "Cannot call clearCrossProfileIntentFilter if there is no parent");
                this.mInjector.binderRestoreCallingIdentity(id);
                return;
            }
            this.mIPackageManager.clearCrossProfileIntentFilters(callingUserId, who.getPackageName());
            this.mIPackageManager.clearCrossProfileIntentFilters(parent.id, who.getPackageName());
            injector = this.mInjector;
            injector.binderRestoreCallingIdentity(id);
        }
    }

    private boolean checkPackagesInPermittedListOrSystem(List<String> enabledPackages, List<String> permittedList, int userIdToCheck) {
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            UserInfo user = getUserInfo(userIdToCheck);
            if (user.isManagedProfile()) {
                userIdToCheck = user.profileGroupId;
            }
            Iterator<String> it = enabledPackages.iterator();
            while (true) {
                if (!it.hasNext()) {
                    return true;
                }
                String enabledPackage = it.next();
                boolean systemService = false;
                try {
                    ApplicationInfo applicationInfo = this.mIPackageManager.getApplicationInfo(enabledPackage, 8192, userIdToCheck);
                    systemService = (applicationInfo.flags & 1) != 0;
                } catch (RemoteException e) {
                    Log.i(LOG_TAG, "Can't talk to package managed", e);
                }
                if (!systemService && !permittedList.contains(enabledPackage)) {
                    return false;
                }
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private AccessibilityManager getAccessibilityManagerForUser(int userId) {
        IBinder iBinder = ServiceManager.getService("accessibility");
        IAccessibilityManager service = iBinder == null ? null : IAccessibilityManager.Stub.asInterface(iBinder);
        return new AccessibilityManager(this.mContext, service, userId);
    }

    public boolean setPermittedAccessibilityServices(ComponentName who, List packageList) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            if (packageList != null) {
                int userId = UserHandle.getCallingUserId();
                long id = this.mInjector.binderClearCallingIdentity();
                try {
                    UserInfo user = getUserInfo(userId);
                    if (user.isManagedProfile()) {
                        userId = user.profileGroupId;
                    }
                    AccessibilityManager accessibilityManager = getAccessibilityManagerForUser(userId);
                    List<AccessibilityServiceInfo> enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(-1);
                    if (enabledServices != null) {
                        List<String> enabledPackages = new ArrayList<>();
                        for (AccessibilityServiceInfo service : enabledServices) {
                            enabledPackages.add(service.getResolveInfo().serviceInfo.packageName);
                        }
                        if (!checkPackagesInPermittedListOrSystem(enabledPackages, packageList, userId)) {
                            Slog.e(LOG_TAG, "Cannot set permitted accessibility services, because it contains already enabled accesibility services.");
                            return false;
                        }
                    }
                } finally {
                    this.mInjector.binderRestoreCallingIdentity(id);
                }
            }
            synchronized (getLockObject()) {
                ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
                admin.permittedAccessiblityServices = packageList;
                saveSettingsLocked(UserHandle.getCallingUserId());
            }
            return true;
        }
        return false;
    }

    public List getPermittedAccessibilityServices(ComponentName who) {
        List<String> list;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            list = admin.permittedAccessiblityServices;
        }
        return list;
    }

    public List getPermittedAccessibilityServicesForUser(int userId) {
        List<String> result;
        if (!this.mHasFeature) {
            return null;
        }
        enforceManageUsers();
        synchronized (getLockObject()) {
            int[] profileIds = this.mUserManager.getProfileIdsWithDisabled(userId);
            int length = profileIds.length;
            result = null;
            int i = 0;
            while (i < length) {
                int profileId = profileIds[i];
                DevicePolicyData policy = getUserDataUnchecked(profileId);
                int N = policy.mAdminList.size();
                List<String> result2 = result;
                for (int j = 0; j < N; j++) {
                    ActiveAdmin admin = policy.mAdminList.get(j);
                    List<String> fromAdmin = admin.permittedAccessiblityServices;
                    if (fromAdmin != null) {
                        if (result2 == null) {
                            result2 = new ArrayList<>(fromAdmin);
                        } else {
                            result2.retainAll(fromAdmin);
                        }
                    }
                }
                i++;
                result = result2;
            }
            if (result != null) {
                long id = this.mInjector.binderClearCallingIdentity();
                UserInfo user = getUserInfo(userId);
                if (user.isManagedProfile()) {
                    userId = user.profileGroupId;
                }
                AccessibilityManager accessibilityManager = getAccessibilityManagerForUser(userId);
                List<AccessibilityServiceInfo> installedServices = accessibilityManager.getInstalledAccessibilityServiceList();
                if (installedServices != null) {
                    for (AccessibilityServiceInfo service : installedServices) {
                        ServiceInfo serviceInfo = service.getResolveInfo().serviceInfo;
                        ApplicationInfo applicationInfo = serviceInfo.applicationInfo;
                        if ((applicationInfo.flags & 1) != 0) {
                            result.add(serviceInfo.packageName);
                        }
                    }
                }
                this.mInjector.binderRestoreCallingIdentity(id);
            }
        }
        return result;
    }

    public boolean isAccessibilityServicePermittedByAdmin(ComponentName who, String packageName, int userHandle) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            Preconditions.checkStringNotEmpty(packageName, "packageName is null");
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query if an accessibility service is disabled by admin");
            }
            synchronized (getLockObject()) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                if (admin == null) {
                    return false;
                }
                if (admin.permittedAccessiblityServices == null) {
                    return true;
                }
                return checkPackagesInPermittedListOrSystem(Collections.singletonList(packageName), admin.permittedAccessiblityServices, userHandle);
            }
        }
        return true;
    }

    private boolean checkCallerIsCurrentUserOrProfile() {
        int callingUserId = UserHandle.getCallingUserId();
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            UserInfo callingUser = getUserInfo(callingUserId);
            UserInfo currentUser = this.mInjector.getIActivityManager().getCurrentUser();
            if (callingUser.isManagedProfile() && callingUser.profileGroupId != currentUser.id) {
                Slog.e(LOG_TAG, "Cannot set permitted input methods for managed profile of a user that isn't the foreground user.");
                return false;
            } else if (callingUser.isManagedProfile() || callingUserId == currentUser.id) {
                this.mInjector.binderRestoreCallingIdentity(token);
                return true;
            } else {
                Slog.e(LOG_TAG, "Cannot set permitted input methods of a user that isn't the foreground user.");
                return false;
            }
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Failed to talk to activity managed.", e);
            return false;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    public boolean setPermittedInputMethods(ComponentName who, List packageList) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            if (checkCallerIsCurrentUserOrProfile()) {
                int callingUserId = this.mInjector.userHandleGetCallingUserId();
                if (packageList != null) {
                    InputMethodManager inputMethodManager = (InputMethodManager) this.mContext.getSystemService(InputMethodManager.class);
                    List<InputMethodInfo> enabledImes = inputMethodManager.getEnabledInputMethodList();
                    if (enabledImes != null) {
                        List<String> enabledPackages = new ArrayList<>();
                        for (InputMethodInfo ime : enabledImes) {
                            enabledPackages.add(ime.getPackageName());
                        }
                        if (!checkPackagesInPermittedListOrSystem(enabledPackages, packageList, callingUserId)) {
                            Slog.e(LOG_TAG, "Cannot set permitted input methods, because it contains already enabled input method.");
                            return false;
                        }
                    }
                }
                synchronized (getLockObject()) {
                    ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
                    admin.permittedInputMethods = packageList;
                    saveSettingsLocked(callingUserId);
                }
                return true;
            }
            return false;
        }
        return false;
    }

    public List getPermittedInputMethods(ComponentName who) {
        List<String> list;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            list = admin.permittedInputMethods;
        }
        return list;
    }

    public List getPermittedInputMethodsForCurrentUser() {
        List<String> result;
        enforceManageUsers();
        try {
            UserInfo currentUser = this.mInjector.getIActivityManager().getCurrentUser();
            int userId = currentUser.id;
            synchronized (getLockObject()) {
                int[] profileIds = this.mUserManager.getProfileIdsWithDisabled(userId);
                int length = profileIds.length;
                result = null;
                int i = 0;
                while (i < length) {
                    int profileId = profileIds[i];
                    DevicePolicyData policy = getUserDataUnchecked(profileId);
                    int N = policy.mAdminList.size();
                    List<String> result2 = result;
                    for (int j = 0; j < N; j++) {
                        ActiveAdmin admin = policy.mAdminList.get(j);
                        List<String> fromAdmin = admin.permittedInputMethods;
                        if (fromAdmin != null) {
                            if (result2 == null) {
                                result2 = new ArrayList<>(fromAdmin);
                            } else {
                                result2.retainAll(fromAdmin);
                            }
                        }
                    }
                    i++;
                    result = result2;
                }
                if (result != null) {
                    InputMethodManager inputMethodManager = (InputMethodManager) this.mContext.getSystemService(InputMethodManager.class);
                    List<InputMethodInfo> imes = inputMethodManager.getInputMethodList();
                    long id = this.mInjector.binderClearCallingIdentity();
                    if (imes != null) {
                        for (InputMethodInfo ime : imes) {
                            ServiceInfo serviceInfo = ime.getServiceInfo();
                            ApplicationInfo applicationInfo = serviceInfo.applicationInfo;
                            if ((applicationInfo.flags & 1) != 0) {
                                result.add(serviceInfo.packageName);
                            }
                        }
                    }
                    this.mInjector.binderRestoreCallingIdentity(id);
                }
            }
            return result;
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Failed to make remote calls to get current user", e);
            return null;
        }
    }

    public boolean isInputMethodPermittedByAdmin(ComponentName who, String packageName, int userHandle) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            Preconditions.checkStringNotEmpty(packageName, "packageName is null");
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query if an input method is disabled by admin");
            }
            synchronized (getLockObject()) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                if (admin == null) {
                    return false;
                }
                if (admin.permittedInputMethods == null) {
                    return true;
                }
                return checkPackagesInPermittedListOrSystem(Collections.singletonList(packageName), admin.permittedInputMethods, userHandle);
            }
        }
        return true;
    }

    public boolean setPermittedCrossProfileNotificationListeners(ComponentName who, List<String> packageList) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            if (isManagedProfile(callingUserId)) {
                synchronized (getLockObject()) {
                    ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
                    admin.permittedNotificationListeners = packageList;
                    saveSettingsLocked(callingUserId);
                }
                return true;
            }
            return false;
        }
        return false;
    }

    public List<String> getPermittedCrossProfileNotificationListeners(ComponentName who) {
        List<String> list;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            list = admin.permittedNotificationListeners;
        }
        return list;
    }

    public boolean isNotificationListenerServicePermitted(String packageName, int userId) {
        if (this.mHasFeature) {
            Preconditions.checkStringNotEmpty(packageName, "packageName is null or empty");
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query if a notification listener service is permitted");
            }
            synchronized (getLockObject()) {
                ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
                if (profileOwner != null && profileOwner.permittedNotificationListeners != null) {
                    return checkPackagesInPermittedListOrSystem(Collections.singletonList(packageName), profileOwner.permittedNotificationListeners, userId);
                }
                return true;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeSendAdminEnabledBroadcastLocked(int userHandle) {
        DevicePolicyData policyData = getUserData(userHandle);
        if (policyData.mAdminBroadcastPending) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userHandle);
            boolean clearInitBundle = true;
            if (admin != null) {
                PersistableBundle initBundle = policyData.mInitBundle;
                clearInitBundle = sendAdminCommandLocked(admin, "android.app.action.DEVICE_ADMIN_ENABLED", initBundle == null ? null : new Bundle(initBundle), null, true);
            }
            if (clearInitBundle) {
                policyData.mInitBundle = null;
                policyData.mAdminBroadcastPending = false;
                saveSettingsLocked(userHandle);
            }
        }
    }

    public UserHandle createAndManageUser(ComponentName admin, String name, ComponentName profileOwner, PersistableBundle adminExtras, int flags) {
        UserHandle user;
        String[] disallowedPackages;
        UserHandle user2;
        Preconditions.checkNotNull(admin, "admin is null");
        Preconditions.checkNotNull(profileOwner, "profileOwner is null");
        if (!admin.getPackageName().equals(profileOwner.getPackageName())) {
            throw new IllegalArgumentException("profileOwner " + profileOwner + " and admin " + admin + " are not in the same package");
        } else if (this.mInjector.binderGetCallingUserHandle().isSystem()) {
            boolean ephemeral = (flags & 2) != 0;
            boolean demo = (flags & 4) != 0 && UserManager.isDeviceInDemoMode(this.mContext);
            boolean leaveAllSystemAppsEnabled = (flags & 16) != 0;
            synchronized (getLockObject()) {
                try {
                    try {
                        getActiveAdminForCallerLocked(admin, -2);
                        int callingUid = this.mInjector.binderGetCallingUid();
                        long id = this.mInjector.binderClearCallingIdentity();
                        try {
                            int targetSdkVersion = this.mInjector.getPackageManagerInternal().getUidTargetSdkVersion(callingUid);
                            DeviceStorageMonitorInternal deviceStorageMonitorInternal = (DeviceStorageMonitorInternal) LocalServices.getService(DeviceStorageMonitorInternal.class);
                            try {
                                if (!deviceStorageMonitorInternal.isMemoryLow()) {
                                    user = null;
                                    try {
                                        if (!this.mUserManager.canAddMoreUsers()) {
                                            if (targetSdkVersion < 28) {
                                                try {
                                                    return null;
                                                } catch (Throwable th) {
                                                    th = th;
                                                    throw th;
                                                }
                                            }
                                            throw new ServiceSpecificException(6, "user limit reached");
                                        }
                                        int userInfoFlags = ephemeral ? 0 | 256 : 0;
                                        if (demo) {
                                            userInfoFlags |= 512;
                                        }
                                        if (leaveAllSystemAppsEnabled) {
                                            disallowedPackages = null;
                                        } else {
                                            try {
                                                disallowedPackages = (String[]) this.mOverlayPackagesProvider.getNonRequiredApps(admin, UserHandle.myUserId(), "android.app.action.PROVISION_MANAGED_USER").toArray(new String[0]);
                                            } catch (Throwable th2) {
                                                th = th2;
                                            }
                                        }
                                        UserInfo userInfo = this.mUserManagerInternal.createUserEvenWhenDisallowed(name, userInfoFlags, disallowedPackages);
                                        if (userInfo != null) {
                                            UserHandle user3 = userInfo.getUserHandle();
                                            user2 = user3;
                                        } else {
                                            user2 = null;
                                        }
                                        if (user2 == null) {
                                            if (targetSdkVersion < 28) {
                                                return null;
                                            }
                                            throw new ServiceSpecificException(1, "failed to create user");
                                        }
                                        int userHandle = user2.getIdentifier();
                                        Intent intent = new Intent("android.app.action.MANAGED_USER_CREATED").putExtra("android.intent.extra.user_handle", userHandle).putExtra("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", leaveAllSystemAppsEnabled).setPackage(getManagedProvisioningPackage(this.mContext)).addFlags(268435456);
                                        this.mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
                                        id = this.mInjector.binderClearCallingIdentity();
                                        try {
                                            String adminPkg = admin.getPackageName();
                                            try {
                                                if (!this.mIPackageManager.isPackageAvailable(adminPkg, userHandle)) {
                                                    this.mIPackageManager.installExistingPackageAsUser(adminPkg, userHandle, 0, 1);
                                                }
                                            } catch (RemoteException e) {
                                            }
                                            setActiveAdmin(profileOwner, true, userHandle);
                                            String ownerName = getProfileOwnerName(Process.myUserHandle().getIdentifier());
                                            setProfileOwner(profileOwner, ownerName, userHandle);
                                            try {
                                                synchronized (getLockObject()) {
                                                    try {
                                                        DevicePolicyData policyData = getUserData(userHandle);
                                                        policyData.mInitBundle = adminExtras;
                                                        policyData.mAdminBroadcastPending = true;
                                                        saveSettingsLocked(userHandle);
                                                        if ((flags & 1) != 0) {
                                                            Settings.Secure.putIntForUser(this.mContext.getContentResolver(), "user_setup_complete", 1, userHandle);
                                                        }
                                                        return user2;
                                                    } catch (Throwable th3) {
                                                        th = th3;
                                                        throw th;
                                                    }
                                                }
                                            } catch (Throwable th4) {
                                                th = th4;
                                            }
                                        } finally {
                                            this.mInjector.binderRestoreCallingIdentity(id);
                                        }
                                    } catch (Throwable th5) {
                                        th = th5;
                                    }
                                } else if (targetSdkVersion < 28) {
                                    try {
                                        return null;
                                    } catch (Throwable th6) {
                                        th = th6;
                                        throw th;
                                    }
                                } else {
                                    try {
                                        throw new ServiceSpecificException(5, "low device storage");
                                    } catch (Throwable th7) {
                                        th = th7;
                                        user = null;
                                    }
                                }
                            } catch (Throwable th8) {
                                th = th8;
                            }
                        } catch (Throwable th9) {
                            th = th9;
                            user = null;
                        }
                    } catch (Throwable th10) {
                        th = th10;
                    }
                } catch (Throwable th11) {
                    th = th11;
                }
                try {
                    throw th;
                } catch (Throwable th12) {
                    th = th12;
                    throw th;
                }
            }
        } else {
            throw new SecurityException("createAndManageUser was called from non-system user");
        }
    }

    public boolean removeUser(ComponentName who, UserHandle userHandle) {
        String restriction;
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(userHandle, "UserHandle is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            if (isManagedProfile(userHandle.getIdentifier())) {
                restriction = "no_remove_managed_profile";
            } else {
                restriction = "no_remove_user";
            }
            if (isAdminAffectedByRestriction(who, restriction, callingUserId)) {
                Log.w(LOG_TAG, "The device owner cannot remove a user because " + restriction + " is enabled, and was not set by the device owner");
                return false;
            }
            return this.mUserManagerInternal.removeUserEvenWhenDisallowed(userHandle.getIdentifier());
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private boolean isAdminAffectedByRestriction(ComponentName admin, String userRestriction, int userId) {
        int userRestrictionSource = this.mUserManager.getUserRestrictionSource(userRestriction, UserHandle.of(userId));
        if (userRestrictionSource != 0) {
            if (userRestrictionSource != 2) {
                if (userRestrictionSource != 4) {
                    return true;
                }
                return !isProfileOwner(admin, userId);
            }
            return !isDeviceOwner(admin, userId);
        }
        return false;
    }

    public boolean switchUser(ComponentName who, UserHandle userHandle) {
        boolean switchUser;
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
            long id = this.mInjector.binderClearCallingIdentity();
            int userId = 0;
            if (userHandle != null) {
                try {
                    userId = userHandle.getIdentifier();
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Couldn't switch user", e);
                    this.mInjector.binderRestoreCallingIdentity(id);
                    return false;
                }
            }
            switchUser = this.mInjector.getIActivityManager().switchUser(userId);
            this.mInjector.binderRestoreCallingIdentity(id);
        }
        return switchUser;
    }

    public int startUserInBackground(ComponentName who, UserHandle userHandle) {
        Injector injector;
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(userHandle, "UserHandle is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        int userId = userHandle.getIdentifier();
        if (isManagedProfile(userId)) {
            Log.w(LOG_TAG, "Managed profile cannot be started in background");
            return 2;
        }
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            if (!this.mInjector.getActivityManagerInternal().canStartMoreUsers()) {
                Log.w(LOG_TAG, "Cannot start more users in background");
                return 3;
            } else if (this.mInjector.getIActivityManager().startUserInBackground(userId)) {
                return 0;
            } else {
                return 1;
            }
        } catch (RemoteException e) {
            return 1;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public int stopUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(userHandle, "UserHandle is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        int userId = userHandle.getIdentifier();
        if (isManagedProfile(userId)) {
            Log.w(LOG_TAG, "Managed profile cannot be stopped");
            return 2;
        }
        return stopUserUnchecked(userId);
    }

    public int logoutUser(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            if (!isUserAffiliatedWithDeviceLocked(callingUserId)) {
                throw new SecurityException("Admin " + who + " is neither the device owner or affiliated user's profile owner.");
            }
        }
        if (isManagedProfile(callingUserId)) {
            Log.w(LOG_TAG, "Managed profile cannot be logout");
            return 2;
        }
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            if (this.mInjector.getIActivityManager().switchUser(0)) {
                this.mInjector.binderRestoreCallingIdentity(id);
                return stopUserUnchecked(callingUserId);
            }
            Log.w(LOG_TAG, "Failed to switch to primary user");
            return 1;
        } catch (RemoteException e) {
            return 1;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private int stopUserUnchecked(int userId) {
        Injector injector;
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            int stopUser = this.mInjector.getIActivityManager().stopUser(userId, true, (IStopUserCallback) null);
            if (stopUser == -2) {
                return 4;
            } else if (stopUser != 0) {
                return 1;
            } else {
                return 0;
            }
        } catch (RemoteException e) {
            return 1;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public List<UserHandle> getSecondaryUsers(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            List<UserInfo> userInfos = this.mInjector.getUserManager().getUsers(true);
            List<UserHandle> userHandles = new ArrayList<>();
            for (UserInfo userInfo : userInfos) {
                UserHandle userHandle = userInfo.getUserHandle();
                if (!userHandle.isSystem() && !isManagedProfile(userHandle.getIdentifier())) {
                    userHandles.add(userInfo.getUserHandle());
                }
            }
            return userHandles;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public boolean isEphemeralUser(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
        }
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mInjector.getUserManager().isUserEphemeral(callingUserId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public Bundle getApplicationRestrictions(ComponentName who, String callerPackage, String packageName) {
        Bundle bundle;
        enforceCanManageScope(who, callerPackage, -1, "delegation-app-restrictions");
        UserHandle userHandle = this.mInjector.binderGetCallingUserHandle();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            Bundle bundle2 = this.mUserManager.getApplicationRestrictions(packageName, userHandle);
            if (bundle2 == null) {
                bundle = Bundle.EMPTY;
            } else {
                bundle = bundle2;
            }
            return bundle;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:24:0x004d
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public java.lang.String[] setPackagesSuspended(android.content.ComponentName r17, java.lang.String r18, java.lang.String[] r19, boolean r20) {
        /*
            r16 = this;
            r1 = r16
            int r10 = android.os.UserHandle.getCallingUserId()
            java.lang.Object r11 = r16.getLockObject()
            monitor-enter(r11)
            r0 = -1
            java.lang.String r2 = "delegation-package-access"
            r12 = r17
            r13 = r18
            r1.enforceCanManageScope(r12, r13, r0, r2)     // Catch: java.lang.Throwable -> L4b
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r1.mInjector     // Catch: java.lang.Throwable -> L4b
            long r2 = r0.binderClearCallingIdentity()     // Catch: java.lang.Throwable -> L4b
            r14 = r2
            android.content.pm.IPackageManager r2 = r1.mIPackageManager     // Catch: java.lang.Throwable -> L33 android.os.RemoteException -> L35
            r5 = 0
            r6 = 0
            r7 = 0
            java.lang.String r8 = "android"
            r3 = r19
            r4 = r20
            r9 = r10
            java.lang.String[] r0 = r2.setPackagesSuspendedAsUser(r3, r4, r5, r6, r7, r8, r9)     // Catch: java.lang.Throwable -> L33 android.os.RemoteException -> L35
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r2 = r1.mInjector     // Catch: java.lang.Throwable -> L4b
            r2.binderRestoreCallingIdentity(r14)     // Catch: java.lang.Throwable -> L4b
            monitor-exit(r11)     // Catch: java.lang.Throwable -> L4b
            return r0
        L33:
            r0 = move-exception
            goto L45
        L35:
            r0 = move-exception
            java.lang.String r2 = "DevicePolicyManager"
            java.lang.String r3 = "Failed talking to the package manager"
            android.util.Slog.e(r2, r3, r0)     // Catch: java.lang.Throwable -> L33
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r1.mInjector     // Catch: java.lang.Throwable -> L4b
            r0.binderRestoreCallingIdentity(r14)     // Catch: java.lang.Throwable -> L4b
            monitor-exit(r11)     // Catch: java.lang.Throwable -> L4b
            return r19
        L45:
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r3 = r1.mInjector     // Catch: java.lang.Throwable -> L4b
            r3.binderRestoreCallingIdentity(r14)     // Catch: java.lang.Throwable -> L4b
            throw r0     // Catch: java.lang.Throwable -> L4b
        L4b:
            r0 = move-exception
            goto L52
        L4d:
            r0 = move-exception
            r12 = r17
            r13 = r18
        L52:
            monitor-exit(r11)     // Catch: java.lang.Throwable -> L4b
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.setPackagesSuspended(android.content.ComponentName, java.lang.String, java.lang.String[], boolean):java.lang.String[]");
    }

    public boolean isPackageSuspended(ComponentName who, String callerPackage, String packageName) {
        boolean isPackageSuspendedForUser;
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-package-access");
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                isPackageSuspendedForUser = this.mIPackageManager.isPackageSuspendedForUser(packageName, callingUserId);
                this.mInjector.binderRestoreCallingIdentity(id);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed talking to the package manager", re);
                this.mInjector.binderRestoreCallingIdentity(id);
                return false;
            }
        }
        return isPackageSuspendedForUser;
    }

    public void setUserRestriction(ComponentName who, String key, boolean enabledFromThisOwner) {
        int eventTag;
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!UserRestrictionsUtils.isValidRestriction(key)) {
            return;
        }
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(who, -1);
            boolean isDeviceOwner = isDeviceOwner(who, userHandle);
            if (isDeviceOwner) {
                if (!UserRestrictionsUtils.canDeviceOwnerChange(key)) {
                    throw new SecurityException("Device owner cannot set user restriction " + key);
                }
            } else if (!UserRestrictionsUtils.canProfileOwnerChange(key, userHandle)) {
                throw new SecurityException("Profile owner cannot set user restriction " + key);
            }
            Bundle restrictions = activeAdmin.ensureUserRestrictions();
            if (enabledFromThisOwner) {
                restrictions.putBoolean(key, true);
            } else {
                restrictions.remove(key);
            }
            saveUserRestrictionsLocked(userHandle);
        }
        if (SecurityLog.isLoggingEnabled()) {
            if (enabledFromThisOwner) {
                eventTag = 210027;
            } else {
                eventTag = 210028;
            }
            SecurityLog.writeEvent(eventTag, new Object[]{who.getPackageName(), Integer.valueOf(userHandle), key});
        }
    }

    private void saveUserRestrictionsLocked(int userId) {
        saveSettingsLocked(userId);
        pushUserRestrictions(userId);
        sendChangedNotification(userId);
    }

    private void pushUserRestrictions(int userId) {
        Bundle userRestrictions;
        synchronized (getLockObject()) {
            boolean isDeviceOwner = this.mOwners.isDeviceOwnerUserId(userId);
            boolean disallowCameraGlobally = false;
            if (isDeviceOwner) {
                ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
                if (deviceOwner == null) {
                    return;
                }
                userRestrictions = deviceOwner.userRestrictions;
                disallowCameraGlobally = deviceOwner.disableCamera;
            } else {
                ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
                userRestrictions = profileOwner != null ? profileOwner.userRestrictions : null;
            }
            Bundle userRestrictions2 = userRestrictions;
            int cameraRestrictionScope = getCameraRestrictionScopeLocked(userId, disallowCameraGlobally);
            this.mUserManagerInternal.setDevicePolicyUserRestrictions(userId, userRestrictions2, isDeviceOwner, cameraRestrictionScope);
        }
    }

    private int getCameraRestrictionScopeLocked(int userId, boolean disallowCameraGlobally) {
        if (disallowCameraGlobally) {
            return 2;
        }
        return getCameraDisabled(null, userId, false) ? 1 : 0;
    }

    public Bundle getUserRestrictions(ComponentName who) {
        Bundle bundle;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(who, -1);
            bundle = activeAdmin.userRestrictions;
        }
        return bundle;
    }

    public boolean setApplicationHidden(ComponentName who, String callerPackage, String packageName, boolean hidden) {
        boolean applicationHiddenSettingAsUser;
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-package-access");
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                applicationHiddenSettingAsUser = this.mIPackageManager.setApplicationHiddenSettingAsUser(packageName, hidden, callingUserId);
                this.mInjector.binderRestoreCallingIdentity(id);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to setApplicationHiddenSetting", re);
                this.mInjector.binderRestoreCallingIdentity(id);
                return false;
            }
        }
        return applicationHiddenSettingAsUser;
    }

    public boolean isApplicationHidden(ComponentName who, String callerPackage, String packageName) {
        boolean applicationHiddenSettingAsUser;
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-package-access");
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                applicationHiddenSettingAsUser = this.mIPackageManager.getApplicationHiddenSettingAsUser(packageName, callingUserId);
                this.mInjector.binderRestoreCallingIdentity(id);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to getApplicationHiddenSettingAsUser", re);
                this.mInjector.binderRestoreCallingIdentity(id);
                return false;
            }
        }
        return applicationHiddenSettingAsUser;
    }

    public void enableSystemApp(ComponentName who, String callerPackage, String packageName) {
        Injector injector;
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-enable-system-app");
            boolean isDemo = isCurrentUserDemo();
            int userId = UserHandle.getCallingUserId();
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                int parentUserId = getProfileParentId(userId);
                if (!isDemo && !isSystemApp(this.mIPackageManager, packageName, parentUserId)) {
                    throw new IllegalArgumentException("Only system apps can be enabled this way.");
                }
                this.mIPackageManager.installExistingPackageAsUser(packageName, userId, 0, 1);
                if (isDemo) {
                    this.mIPackageManager.setApplicationEnabledSetting(packageName, 1, 1, userId, LOG_TAG);
                }
                injector = this.mInjector;
            } catch (RemoteException re) {
                Slog.wtf(LOG_TAG, "Failed to install " + packageName, re);
                injector = this.mInjector;
            }
            injector.binderRestoreCallingIdentity(id);
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:39:0x00be
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public int enableSystemAppWithIntent(android.content.ComponentName r19, java.lang.String r20, android.content.Intent r21) {
        /*
            r18 = this;
            r1 = r18
            r2 = r21
            java.lang.Object r3 = r18.getLockObject()
            monitor-enter(r3)
            r0 = -1
            java.lang.String r4 = "delegation-enable-system-app"
            r5 = r19
            r6 = r20
            r1.enforceCanManageScope(r5, r6, r0, r4)     // Catch: java.lang.Throwable -> Lbc
            int r0 = android.os.UserHandle.getCallingUserId()     // Catch: java.lang.Throwable -> Lbc
            r4 = r0
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r1.mInjector     // Catch: java.lang.Throwable -> Lbc
            long r7 = r0.binderClearCallingIdentity()     // Catch: java.lang.Throwable -> Lbc
            r9 = 0
            int r0 = r1.getProfileParentId(r4)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            android.content.pm.IPackageManager r10 = r1.mIPackageManager     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            android.content.Context r11 = r1.mContext     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            android.content.ContentResolver r11 = r11.getContentResolver()     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            java.lang.String r11 = r2.resolveTypeIfNeeded(r11)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            r12 = 786432(0xc0000, float:1.102026E-39)
            android.content.pm.ParceledListSlice r10 = r10.queryIntentActivities(r2, r11, r12, r0)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            java.util.List r10 = r10.getList()     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            r11 = 0
            if (r10 == 0) goto L8b
            java.util.Iterator r12 = r10.iterator()     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
        L40:
            boolean r13 = r12.hasNext()     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            if (r13 == 0) goto L8b
            java.lang.Object r13 = r12.next()     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            android.content.pm.ResolveInfo r13 = (android.content.pm.ResolveInfo) r13     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            android.content.pm.ActivityInfo r14 = r13.activityInfo     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            if (r14 == 0) goto L85
            android.content.pm.ActivityInfo r14 = r13.activityInfo     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            java.lang.String r14 = r14.packageName     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            android.content.pm.IPackageManager r15 = r1.mIPackageManager     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            boolean r15 = r1.isSystemApp(r15, r14, r0)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            if (r15 == 0) goto L67
            int r11 = r11 + 1
            android.content.pm.IPackageManager r15 = r1.mIPackageManager     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            r16 = r0
            r0 = 1
            r15.installExistingPackageAsUser(r14, r4, r9, r0)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            goto L87
        L67:
            r16 = r0
            java.lang.String r0 = "DevicePolicyManager"
            java.lang.StringBuilder r15 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            r15.<init>()     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            java.lang.String r9 = "Not enabling "
            r15.append(r9)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            r15.append(r14)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            java.lang.String r9 = " since is not a system app"
            r15.append(r9)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            java.lang.String r9 = r15.toString()     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            android.util.Slog.d(r0, r9)     // Catch: java.lang.Throwable -> L94 android.os.RemoteException -> L96
            goto L87
        L85:
            r16 = r0
        L87:
            r0 = r16
            r9 = 0
            goto L40
        L8b:
            r16 = r0
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r1.mInjector     // Catch: java.lang.Throwable -> Lbc
            r0.binderRestoreCallingIdentity(r7)     // Catch: java.lang.Throwable -> Lbc
            monitor-exit(r3)     // Catch: java.lang.Throwable -> Lbc
            return r11
        L94:
            r0 = move-exception
            goto Lb6
        L96:
            r0 = move-exception
            java.lang.String r9 = "DevicePolicyManager"
            java.lang.StringBuilder r10 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L94
            r10.<init>()     // Catch: java.lang.Throwable -> L94
            java.lang.String r11 = "Failed to resolve intent for: "
            r10.append(r11)     // Catch: java.lang.Throwable -> L94
            r10.append(r2)     // Catch: java.lang.Throwable -> L94
            java.lang.String r10 = r10.toString()     // Catch: java.lang.Throwable -> L94
            android.util.Slog.wtf(r9, r10)     // Catch: java.lang.Throwable -> L94
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r9 = r1.mInjector     // Catch: java.lang.Throwable -> Lbc
            r9.binderRestoreCallingIdentity(r7)     // Catch: java.lang.Throwable -> Lbc
            monitor-exit(r3)     // Catch: java.lang.Throwable -> Lbc
            r3 = 0
            return r3
        Lb6:
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r9 = r1.mInjector     // Catch: java.lang.Throwable -> Lbc
            r9.binderRestoreCallingIdentity(r7)     // Catch: java.lang.Throwable -> Lbc
            throw r0     // Catch: java.lang.Throwable -> Lbc
        Lbc:
            r0 = move-exception
            goto Lc3
        Lbe:
            r0 = move-exception
            r5 = r19
            r6 = r20
        Lc3:
            monitor-exit(r3)     // Catch: java.lang.Throwable -> Lbc
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.enableSystemAppWithIntent(android.content.ComponentName, java.lang.String, android.content.Intent):int");
    }

    private boolean isSystemApp(IPackageManager pm, String packageName, int userId) throws RemoteException {
        ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 8192, userId);
        if (appInfo != null) {
            return (appInfo.flags & 1) != 0;
        }
        throw new IllegalArgumentException("The application " + packageName + " is not present on this device");
    }

    public boolean installExistingPackage(ComponentName who, String callerPackage, String packageName) {
        boolean z;
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-install-existing-package");
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            if (!isUserAffiliatedWithDeviceLocked(callingUserId)) {
                throw new SecurityException("Admin " + who + " is neither the device owner or affiliated user's profile owner.");
            }
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                z = this.mIPackageManager.installExistingPackageAsUser(packageName, callingUserId, 0, 1) == 1;
            } catch (RemoteException e) {
                return false;
            } finally {
                this.mInjector.binderRestoreCallingIdentity(id);
            }
        }
        return z;
    }

    public void setAccountManagementDisabled(ComponentName who, String accountType, boolean disabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, -1);
            if (disabled) {
                ap.accountTypesWithManagementDisabled.add(accountType);
            } else {
                ap.accountTypesWithManagementDisabled.remove(accountType);
            }
            saveSettingsLocked(UserHandle.getCallingUserId());
        }
    }

    public String[] getAccountTypesWithManagementDisabled() {
        return getAccountTypesWithManagementDisabledAsUser(UserHandle.getCallingUserId());
    }

    public String[] getAccountTypesWithManagementDisabledAsUser(int userId) {
        String[] strArr;
        enforceFullCrossUsersPermission(userId);
        if (!this.mHasFeature) {
            return null;
        }
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userId);
            int N = policy.mAdminList.size();
            ArraySet<String> resultSet = new ArraySet<>();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = policy.mAdminList.get(i);
                resultSet.addAll(admin.accountTypesWithManagementDisabled);
            }
            int i2 = resultSet.size();
            strArr = (String[]) resultSet.toArray(new String[i2]);
        }
        return strArr;
    }

    public void setUninstallBlocked(ComponentName who, String callerPackage, String packageName, boolean uninstallBlocked) {
        Injector injector;
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-block-uninstall");
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                this.mIPackageManager.setBlockUninstallForUser(packageName, uninstallBlocked, userId);
                injector = this.mInjector;
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to setBlockUninstallForUser", re);
                injector = this.mInjector;
            }
            injector.binderRestoreCallingIdentity(id);
        }
    }

    public boolean isUninstallBlocked(ComponentName who, String packageName) {
        boolean blockUninstallForUser;
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            if (who != null) {
                try {
                    getActiveAdminForCallerLocked(who, -1);
                } catch (Throwable th) {
                    throw th;
                }
            }
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                blockUninstallForUser = this.mIPackageManager.getBlockUninstallForUser(packageName, userId);
                this.mInjector.binderRestoreCallingIdentity(id);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to getBlockUninstallForUser", re);
                this.mInjector.binderRestoreCallingIdentity(id);
                return false;
            }
        }
        return blockUninstallForUser;
    }

    public void setCrossProfileCallerIdDisabled(ComponentName who, boolean disabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            if (admin.disableCallerId != disabled) {
                admin.disableCallerId = disabled;
                saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
            }
        }
    }

    public boolean getCrossProfileCallerIdDisabled(ComponentName who) {
        boolean z;
        if (!this.mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            z = admin.disableCallerId;
        }
        return z;
    }

    public boolean getCrossProfileCallerIdDisabledForUser(int userId) {
        boolean z;
        enforceCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            z = admin != null ? admin.disableCallerId : false;
        }
        return z;
    }

    public void setCrossProfileContactsSearchDisabled(ComponentName who, boolean disabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            if (admin.disableContactsSearch != disabled) {
                admin.disableContactsSearch = disabled;
                saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
            }
        }
    }

    public boolean getCrossProfileContactsSearchDisabled(ComponentName who) {
        boolean z;
        if (!this.mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            z = admin.disableContactsSearch;
        }
        return z;
    }

    public boolean getCrossProfileContactsSearchDisabledForUser(int userId) {
        boolean z;
        enforceCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            z = admin != null ? admin.disableContactsSearch : false;
        }
        return z;
    }

    public void startManagedQuickContact(String actualLookupKey, long actualContactId, boolean isContactIdIgnored, long actualDirectoryId, Intent originalIntent) {
        Intent intent = ContactsContract.QuickContact.rebuildManagedQuickContactsIntent(actualLookupKey, actualContactId, isContactIdIgnored, actualDirectoryId, originalIntent);
        int callingUserId = UserHandle.getCallingUserId();
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                int managedUserId = getManagedUserId(callingUserId);
                if (managedUserId < 0) {
                    return;
                }
                if (isCrossProfileQuickContactDisabled(managedUserId)) {
                    return;
                }
                ContactsInternal.startQuickContactWithErrorToastForUser(this.mContext, intent, new UserHandle(managedUserId));
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private boolean isCrossProfileQuickContactDisabled(int userId) {
        return getCrossProfileCallerIdDisabledForUser(userId) && getCrossProfileContactsSearchDisabledForUser(userId);
    }

    public int getManagedUserId(int callingUserId) {
        for (UserInfo ui : this.mUserManager.getProfiles(callingUserId)) {
            if (ui.id != callingUserId && ui.isManagedProfile()) {
                return ui.id;
            }
        }
        return -1;
    }

    public void setBluetoothContactSharingDisabled(ComponentName who, boolean disabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            if (admin.disableBluetoothContactSharing != disabled) {
                admin.disableBluetoothContactSharing = disabled;
                saveSettingsLocked(UserHandle.getCallingUserId());
            }
        }
    }

    public boolean getBluetoothContactSharingDisabled(ComponentName who) {
        boolean z;
        if (!this.mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            z = admin.disableBluetoothContactSharing;
        }
        return z;
    }

    public boolean getBluetoothContactSharingDisabledForUser(int userId) {
        boolean z;
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            z = admin != null ? admin.disableBluetoothContactSharing : false;
        }
        return z;
    }

    public void setLockTaskPackages(ComponentName who, String[] packages) throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(packages, "packages is null");
        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            int userHandle = this.mInjector.userHandleGetCallingUserId();
            setLockTaskPackagesLocked(userHandle, new ArrayList(Arrays.asList(packages)));
        }
    }

    private void setLockTaskPackagesLocked(int userHandle, List<String> packages) {
        DevicePolicyData policy = getUserData(userHandle);
        policy.mLockTaskPackages = packages;
        saveSettingsLocked(userHandle);
        updateLockTaskPackagesLocked(packages, userHandle);
    }

    public String[] getLockTaskPackages(ComponentName who) {
        String[] strArr;
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.binderGetCallingUserHandle().getIdentifier();
        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            List<String> packages = getUserData(userHandle).mLockTaskPackages;
            strArr = (String[]) packages.toArray(new String[packages.size()]);
        }
        return strArr;
    }

    public boolean isLockTaskPermitted(String pkg) {
        boolean contains;
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            contains = getUserData(userHandle).mLockTaskPackages.contains(pkg);
        }
        return contains;
    }

    public void setLockTaskFeatures(ComponentName who, int flags) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        boolean z = false;
        boolean hasHome = (flags & 4) != 0;
        boolean hasOverview = (flags & 8) != 0;
        Preconditions.checkArgument(hasHome || !hasOverview, "Cannot use LOCK_TASK_FEATURE_OVERVIEW without LOCK_TASK_FEATURE_HOME");
        boolean hasNotification = (flags & 2) != 0;
        if (hasHome || !hasNotification) {
            z = true;
        }
        Preconditions.checkArgument(z, "Cannot use LOCK_TASK_FEATURE_NOTIFICATIONS without LOCK_TASK_FEATURE_HOME");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            setLockTaskFeaturesLocked(userHandle, flags);
        }
    }

    private void setLockTaskFeaturesLocked(int userHandle, int flags) {
        DevicePolicyData policy = getUserData(userHandle);
        policy.mLockTaskFeatures = flags;
        saveSettingsLocked(userHandle);
        updateLockTaskFeaturesLocked(flags, userHandle);
    }

    public int getLockTaskFeatures(ComponentName who) {
        int i;
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            i = getUserData(userHandle).mLockTaskFeatures;
        }
        return i;
    }

    private void maybeClearLockTaskPolicyLocked() {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            List<UserInfo> userInfos = this.mUserManager.getUsers(true);
            int userId = userInfos.size() - 1;
            while (true) {
                int i = userId;
                if (i >= 0) {
                    int userId2 = userInfos.get(i).id;
                    if (!canUserUseLockTaskLocked(userId2)) {
                        List<String> lockTaskPackages = getUserData(userId2).mLockTaskPackages;
                        if (!lockTaskPackages.isEmpty()) {
                            Slog.d(LOG_TAG, "User id " + userId2 + " not affiliated. Clearing lock task packages");
                            setLockTaskPackagesLocked(userId2, Collections.emptyList());
                        }
                        int lockTaskFeatures = getUserData(userId2).mLockTaskFeatures;
                        if (lockTaskFeatures != 0) {
                            Slog.d(LOG_TAG, "User id " + userId2 + " not affiliated. Clearing lock task features");
                            setLockTaskFeaturesLocked(userId2, 0);
                        }
                    }
                    userId = i - 1;
                } else {
                    return;
                }
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    public void notifyLockTaskModeChanged(boolean isEnabled, String pkg, int userHandle) {
        if (!isCallerWithSystemUid()) {
            throw new SecurityException("notifyLockTaskModeChanged can only be called by system");
        }
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mStatusBarDisabled) {
                setStatusBarDisabledInternal(!isEnabled, userHandle);
            }
            Bundle adminExtras = new Bundle();
            adminExtras.putString("android.app.extra.LOCK_TASK_PACKAGE", pkg);
            Iterator<ActiveAdmin> it = policy.mAdminList.iterator();
            while (it.hasNext()) {
                ActiveAdmin admin = it.next();
                boolean ownsDevice = isDeviceOwner(admin.info.getComponent(), userHandle);
                boolean ownsProfile = isProfileOwner(admin.info.getComponent(), userHandle);
                if (ownsDevice || ownsProfile) {
                    if (isEnabled) {
                        sendAdminCommandLocked(admin, "android.app.action.LOCK_TASK_ENTERING", adminExtras, (BroadcastReceiver) null);
                    } else {
                        sendAdminCommandLocked(admin, "android.app.action.LOCK_TASK_EXITING");
                    }
                }
            }
        }
    }

    public void setGlobalSetting(ComponentName who, String setting, String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
            if (GLOBAL_SETTINGS_DEPRECATED.contains(setting)) {
                Log.i(LOG_TAG, "Global setting no longer supported: " + setting);
                return;
            }
            if (!GLOBAL_SETTINGS_WHITELIST.contains(setting) && !UserManager.isDeviceInDemoMode(this.mContext)) {
                throw new SecurityException(String.format("Permission denial: device owners cannot update %1$s", setting));
            }
            if ("stay_on_while_plugged_in".equals(setting)) {
                long timeMs = getMaximumTimeToLock(who, this.mInjector.userHandleGetCallingUserId(), false);
                if (timeMs > 0 && timeMs < JobStatus.NO_LATEST_RUNTIME) {
                    return;
                }
            }
            long id = this.mInjector.binderClearCallingIdentity();
            this.mInjector.settingsGlobalPutString(setting, value);
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void setSystemSetting(ComponentName who, final String setting, final String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkStringNotEmpty(setting, "String setting is null or empty");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            if (!SYSTEM_SETTINGS_WHITELIST.contains(setting)) {
                throw new SecurityException(String.format("Permission denial: device owners cannot update %1$s", setting));
            }
            final int callingUserId = this.mInjector.userHandleGetCallingUserId();
            this.mInjector.binderWithCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$mignzFcOqIvnBFOYi8O3tmqXI68
                public final void runOrThrow() {
                    DevicePolicyManagerService.this.mInjector.settingsSystemPutStringForUser(setting, value, callingUserId);
                }
            });
        }
    }

    public boolean setTime(ComponentName who, final long millis) {
        Preconditions.checkNotNull(who, "ComponentName is null in setTime");
        getActiveAdminForCallerLocked(who, -2);
        if (this.mInjector.settingsGlobalGetInt("auto_time", 0) == 1) {
            return false;
        }
        this.mInjector.binderWithCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$zqf4q6-7wkQreppEUOBfp0NE94M
            public final void runOrThrow() {
                DevicePolicyManagerService.this.mInjector.getAlarmManager().setTime(millis);
            }
        });
        return true;
    }

    public boolean setTimeZone(ComponentName who, final String timeZone) {
        Preconditions.checkNotNull(who, "ComponentName is null in setTimeZone");
        getActiveAdminForCallerLocked(who, -2);
        if (this.mInjector.settingsGlobalGetInt("auto_time_zone", 0) == 1) {
            return false;
        }
        this.mInjector.binderWithCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$1qc4cD7h8K2CVmZeyPCWra8TVtQ
            public final void runOrThrow() {
                DevicePolicyManagerService.this.mInjector.getAlarmManager().setTimeZone(timeZone);
            }
        });
        return true;
    }

    public void setSecureSetting(ComponentName who, String setting, String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            if (isDeviceOwner(who, callingUserId)) {
                if (!SECURE_SETTINGS_DEVICEOWNER_WHITELIST.contains(setting) && !isCurrentUserDemo()) {
                    throw new SecurityException(String.format("Permission denial: Device owners cannot update %1$s", setting));
                }
            } else if (!SECURE_SETTINGS_WHITELIST.contains(setting) && !isCurrentUserDemo()) {
                throw new SecurityException(String.format("Permission denial: Profile owners cannot update %1$s", setting));
            }
            if (setting.equals("install_non_market_apps")) {
                if (getTargetSdk(who.getPackageName(), callingUserId) >= 26) {
                    throw new UnsupportedOperationException("install_non_market_apps is deprecated. Please use the user restriction no_install_unknown_sources instead.");
                }
                if (!this.mUserManager.isManagedProfile(callingUserId)) {
                    Slog.e(LOG_TAG, "Ignoring setSecureSetting request for " + setting + ". User restriction no_install_unknown_sources should be used instead.");
                } else {
                    try {
                        setUserRestriction(who, "no_install_unknown_sources", Integer.parseInt(value) == 0);
                    } catch (NumberFormatException e) {
                        Slog.e(LOG_TAG, "Invalid value: " + value + " for setting " + setting);
                    }
                }
                return;
            }
            long id = this.mInjector.binderClearCallingIdentity();
            if ("default_input_method".equals(setting)) {
                String currentValue = this.mInjector.settingsSecureGetStringForUser("default_input_method", callingUserId);
                if (!TextUtils.equals(currentValue, value)) {
                    this.mSetupContentObserver.addPendingChangeByOwnerLocked(callingUserId);
                }
                getUserData(callingUserId).mCurrentInputMethodSet = true;
                saveSettingsLocked(callingUserId);
            }
            this.mInjector.settingsSecurePutStringForUser(setting, value, callingUserId);
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public void setMasterVolumeMuted(ComponentName who, boolean on) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            setUserRestriction(who, "disallow_unmute_device", on);
        }
    }

    public boolean isMasterVolumeMuted(ComponentName who) {
        boolean isMasterMute;
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            AudioManager audioManager = (AudioManager) this.mContext.getSystemService("audio");
            isMasterMute = audioManager.isMasterMute();
        }
        return isMasterMute;
    }

    public void setUserIcon(ComponentName who, Bitmap icon) {
        synchronized (getLockObject()) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            getActiveAdminForCallerLocked(who, -1);
            int userId = UserHandle.getCallingUserId();
            long id = this.mInjector.binderClearCallingIdentity();
            this.mUserManagerInternal.setUserIcon(userId, icon);
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public boolean setKeyguardDisabled(ComponentName who, boolean disabled) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            if (!isUserAffiliatedWithDeviceLocked(userId)) {
                throw new SecurityException("Admin " + who + " is neither the device owner or affiliated user's profile owner.");
            }
        }
        if (isManagedProfile(userId)) {
            throw new SecurityException("Managed profile cannot disable keyguard");
        }
        long ident = this.mInjector.binderClearCallingIdentity();
        if (disabled) {
            try {
                if (this.mLockPatternUtils.isSecure(userId)) {
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    return false;
                }
            } catch (RemoteException e) {
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(ident);
                throw th;
            }
        }
        this.mLockPatternUtils.setLockScreenDisabled(disabled, userId);
        this.mInjector.getIWindowManager().dismissKeyguard((IKeyguardDismissCallback) null, (CharSequence) null);
        this.mInjector.binderRestoreCallingIdentity(ident);
        return true;
    }

    public boolean setStatusBarDisabled(ComponentName who, boolean disabled) {
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            if (!isUserAffiliatedWithDeviceLocked(userId)) {
                throw new SecurityException("Admin " + who + " is neither the device owner or affiliated user's profile owner.");
            } else if (isManagedProfile(userId)) {
                throw new SecurityException("Managed profile cannot disable status bar");
            } else {
                DevicePolicyData policy = getUserData(userId);
                if (policy.mStatusBarDisabled != disabled) {
                    boolean isLockTaskMode = false;
                    try {
                        isLockTaskMode = this.mInjector.getIActivityManager().getLockTaskModeState() != 0;
                    } catch (RemoteException e) {
                        Slog.e(LOG_TAG, "Failed to get LockTask mode");
                    }
                    if (!isLockTaskMode && !setStatusBarDisabledInternal(disabled, userId)) {
                        return false;
                    }
                    policy.mStatusBarDisabled = disabled;
                    saveSettingsLocked(userId);
                }
                return true;
            }
        }
    }

    private boolean setStatusBarDisabledInternal(boolean disabled, int userId) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                IStatusBarService statusBarService = IStatusBarService.Stub.asInterface(ServiceManager.checkService(TAG_STATUS_BAR));
                if (statusBarService != null) {
                    int flags1 = disabled ? STATUS_BAR_DISABLE_MASK : 0;
                    statusBarService.disableForUser(flags1, this.mToken, this.mContext.getPackageName(), userId);
                    statusBarService.disable2ForUser(disabled ? 1 : 0, this.mToken, this.mContext.getPackageName(), userId);
                    return true;
                }
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Failed to disable the status bar", e);
            }
            return false;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    void updateUserSetupCompleteAndPaired() {
        List<UserInfo> users = this.mUserManager.getUsers(true);
        int N = users.size();
        for (int i = 0; i < N; i++) {
            int userHandle = users.get(i).id;
            if (this.mInjector.settingsSecureGetIntForUser("user_setup_complete", 0, userHandle) != 0) {
                DevicePolicyData policy = getUserData(userHandle);
                if (!policy.mUserSetupComplete) {
                    policy.mUserSetupComplete = true;
                    synchronized (getLockObject()) {
                        saveSettingsLocked(userHandle);
                    }
                }
            }
            if (this.mIsWatch && this.mInjector.settingsSecureGetIntForUser("device_paired", 0, userHandle) != 0) {
                DevicePolicyData policy2 = getUserData(userHandle);
                if (policy2.mPaired) {
                    continue;
                } else {
                    policy2.mPaired = true;
                    synchronized (getLockObject()) {
                        saveSettingsLocked(userHandle);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class SetupContentObserver extends ContentObserver {
        private final Uri mDefaultImeChanged;
        private final Uri mDeviceProvisioned;
        private final Uri mPaired;
        @GuardedBy("getLockObject()")
        private Set<Integer> mUserIdsWithPendingChangesByOwner;
        private final Uri mUserSetupComplete;

        public SetupContentObserver(Handler handler) {
            super(handler);
            this.mUserSetupComplete = Settings.Secure.getUriFor("user_setup_complete");
            this.mDeviceProvisioned = Settings.Global.getUriFor("device_provisioned");
            this.mPaired = Settings.Secure.getUriFor("device_paired");
            this.mDefaultImeChanged = Settings.Secure.getUriFor("default_input_method");
            this.mUserIdsWithPendingChangesByOwner = new ArraySet();
        }

        void register() {
            DevicePolicyManagerService.this.mInjector.registerContentObserver(this.mUserSetupComplete, false, this, -1);
            DevicePolicyManagerService.this.mInjector.registerContentObserver(this.mDeviceProvisioned, false, this, -1);
            if (DevicePolicyManagerService.this.mIsWatch) {
                DevicePolicyManagerService.this.mInjector.registerContentObserver(this.mPaired, false, this, -1);
            }
            DevicePolicyManagerService.this.mInjector.registerContentObserver(this.mDefaultImeChanged, false, this, -1);
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy("getLockObject()")
        public void addPendingChangeByOwnerLocked(int userId) {
            this.mUserIdsWithPendingChangesByOwner.add(Integer.valueOf(userId));
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (this.mUserSetupComplete.equals(uri) || (DevicePolicyManagerService.this.mIsWatch && this.mPaired.equals(uri))) {
                DevicePolicyManagerService.this.updateUserSetupCompleteAndPaired();
            } else if (this.mDeviceProvisioned.equals(uri)) {
                synchronized (DevicePolicyManagerService.this.getLockObject()) {
                    DevicePolicyManagerService.this.setDeviceOwnerSystemPropertyLocked();
                }
            } else if (this.mDefaultImeChanged.equals(uri)) {
                synchronized (DevicePolicyManagerService.this.getLockObject()) {
                    if (this.mUserIdsWithPendingChangesByOwner.contains(Integer.valueOf(userId))) {
                        this.mUserIdsWithPendingChangesByOwner.remove(Integer.valueOf(userId));
                    } else {
                        DevicePolicyManagerService.this.getUserData(userId).mCurrentInputMethodSet = false;
                        DevicePolicyManagerService.this.saveSettingsLocked(userId);
                    }
                }
            }
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    final class LocalService extends DevicePolicyManagerInternal {
        private List<DevicePolicyManagerInternal.OnCrossProfileWidgetProvidersChangeListener> mWidgetProviderListeners;

        LocalService() {
        }

        public List<String> getCrossProfileWidgetProviders(int profileId) {
            synchronized (DevicePolicyManagerService.this.getLockObject()) {
                if (DevicePolicyManagerService.this.mOwners == null) {
                    return Collections.emptyList();
                }
                ComponentName ownerComponent = DevicePolicyManagerService.this.mOwners.getProfileOwnerComponent(profileId);
                if (ownerComponent == null) {
                    return Collections.emptyList();
                }
                DevicePolicyData policy = DevicePolicyManagerService.this.getUserDataUnchecked(profileId);
                ActiveAdmin admin = policy.mAdminMap.get(ownerComponent);
                if (admin != null && admin.crossProfileWidgetProviders != null && !admin.crossProfileWidgetProviders.isEmpty()) {
                    return admin.crossProfileWidgetProviders;
                }
                return Collections.emptyList();
            }
        }

        public void addOnCrossProfileWidgetProvidersChangeListener(DevicePolicyManagerInternal.OnCrossProfileWidgetProvidersChangeListener listener) {
            synchronized (DevicePolicyManagerService.this.getLockObject()) {
                if (this.mWidgetProviderListeners == null) {
                    this.mWidgetProviderListeners = new ArrayList();
                }
                if (!this.mWidgetProviderListeners.contains(listener)) {
                    this.mWidgetProviderListeners.add(listener);
                }
            }
        }

        public boolean isActiveAdminWithPolicy(int uid, int reqPolicy) {
            boolean z;
            synchronized (DevicePolicyManagerService.this.getLockObject()) {
                z = DevicePolicyManagerService.this.getActiveAdminWithPolicyForUidLocked(null, reqPolicy, uid) != null;
            }
            return z;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyCrossProfileProvidersChanged(int userId, List<String> packages) {
            List<DevicePolicyManagerInternal.OnCrossProfileWidgetProvidersChangeListener> listeners;
            synchronized (DevicePolicyManagerService.this.getLockObject()) {
                listeners = new ArrayList<>(this.mWidgetProviderListeners);
            }
            int listenerCount = listeners.size();
            for (int i = 0; i < listenerCount; i++) {
                DevicePolicyManagerInternal.OnCrossProfileWidgetProvidersChangeListener listener = listeners.get(i);
                listener.onCrossProfileWidgetProvidersChanged(userId, packages);
            }
        }

        public Intent createShowAdminSupportIntent(int userId, boolean useDefaultIfNoAdmin) {
            ComponentName profileOwner = DevicePolicyManagerService.this.mOwners.getProfileOwnerComponent(userId);
            if (profileOwner != null) {
                return DevicePolicyManagerService.this.createShowAdminSupportIntent(profileOwner, userId);
            }
            Pair<Integer, ComponentName> deviceOwner = DevicePolicyManagerService.this.mOwners.getDeviceOwnerUserIdAndComponent();
            if (deviceOwner == null || ((Integer) deviceOwner.first).intValue() != userId) {
                if (useDefaultIfNoAdmin) {
                    return DevicePolicyManagerService.this.createShowAdminSupportIntent(null, userId);
                }
                return null;
            }
            return DevicePolicyManagerService.this.createShowAdminSupportIntent((ComponentName) deviceOwner.second, userId);
        }

        public Intent createUserRestrictionSupportIntent(int userId, String userRestriction) {
            Pair<Integer, ComponentName> deviceOwner;
            long ident = DevicePolicyManagerService.this.mInjector.binderClearCallingIdentity();
            try {
                int source = DevicePolicyManagerService.this.mUserManager.getUserRestrictionSource(userRestriction, UserHandle.of(userId));
                DevicePolicyManagerService.this.mInjector.binderRestoreCallingIdentity(ident);
                if ((source & 1) != 0) {
                    return null;
                }
                boolean enforcedByDo = (source & 2) != 0;
                boolean enforcedByPo = (source & 4) != 0;
                if (enforcedByDo && enforcedByPo) {
                    return DevicePolicyManagerService.this.createShowAdminSupportIntent(null, userId);
                }
                if (enforcedByPo) {
                    ComponentName profileOwner = DevicePolicyManagerService.this.mOwners.getProfileOwnerComponent(userId);
                    if (profileOwner != null) {
                        return DevicePolicyManagerService.this.createShowAdminSupportIntent(profileOwner, userId);
                    }
                    return null;
                } else if (!enforcedByDo || (deviceOwner = DevicePolicyManagerService.this.mOwners.getDeviceOwnerUserIdAndComponent()) == null) {
                    return null;
                } else {
                    return DevicePolicyManagerService.this.createShowAdminSupportIntent((ComponentName) deviceOwner.second, ((Integer) deviceOwner.first).intValue());
                }
            } catch (Throwable th) {
                DevicePolicyManagerService.this.mInjector.binderRestoreCallingIdentity(ident);
                throw th;
            }
        }

        public boolean isUserAffiliatedWithDevice(int userId) {
            return DevicePolicyManagerService.this.isUserAffiliatedWithDeviceLocked(userId);
        }

        public void reportSeparateProfileChallengeChanged(int userId) {
            synchronized (DevicePolicyManagerService.this.getLockObject()) {
                DevicePolicyManagerService.this.updateMaximumTimeToLockLocked(userId);
            }
        }

        public boolean canUserHaveUntrustedCredentialReset(int userId) {
            return DevicePolicyManagerService.this.canUserHaveUntrustedCredentialReset(userId);
        }

        public CharSequence getPrintingDisabledReasonForUser(int userId) {
            synchronized (DevicePolicyManagerService.this.getLockObject()) {
                DevicePolicyManagerService.this.getUserData(userId);
                if (!DevicePolicyManagerService.this.mUserManager.hasUserRestriction("no_printing", UserHandle.of(userId))) {
                    Log.e(DevicePolicyManagerService.LOG_TAG, "printing is enabled");
                    return null;
                }
                String ownerPackage = DevicePolicyManagerService.this.mOwners.getProfileOwnerPackage(userId);
                if (ownerPackage == null) {
                    ownerPackage = DevicePolicyManagerService.this.mOwners.getDeviceOwnerPackageName();
                }
                PackageManager pm = DevicePolicyManagerService.this.mInjector.getPackageManager();
                try {
                    PackageInfo packageInfo = pm.getPackageInfo(ownerPackage, 0);
                    if (packageInfo == null) {
                        Log.e(DevicePolicyManagerService.LOG_TAG, "packageInfo is inexplicably null");
                        return null;
                    }
                    ApplicationInfo appInfo = packageInfo.applicationInfo;
                    if (appInfo == null) {
                        Log.e(DevicePolicyManagerService.LOG_TAG, "appInfo is inexplicably null");
                        return null;
                    }
                    CharSequence appLabel = pm.getApplicationLabel(appInfo);
                    if (appLabel == null) {
                        Log.e(DevicePolicyManagerService.LOG_TAG, "appLabel is inexplicably null");
                        return null;
                    }
                    return ActivityThread.currentActivityThread().getSystemUiContext().getResources().getString(17040744, appLabel);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(DevicePolicyManagerService.LOG_TAG, "getPackageInfo error", e);
                    return null;
                }
            }
        }

        protected DevicePolicyCache getDevicePolicyCache() {
            return DevicePolicyManagerService.this.mPolicyCache;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Intent createShowAdminSupportIntent(ComponentName admin, int userId) {
        Intent intent = new Intent("android.settings.SHOW_ADMIN_SUPPORT_DETAILS");
        intent.putExtra("android.intent.extra.USER_ID", userId);
        intent.putExtra("android.app.extra.DEVICE_ADMIN", admin);
        intent.setFlags(268435456);
        return intent;
    }

    public Intent createAdminSupportIntent(String restriction) {
        ActiveAdmin admin;
        Preconditions.checkNotNull(restriction);
        int uid = this.mInjector.binderGetCallingUid();
        int userId = UserHandle.getUserId(uid);
        Intent intent = null;
        if ("policy_disable_camera".equals(restriction) || "policy_disable_screen_capture".equals(restriction) || "policy_mandatory_backups".equals(restriction)) {
            synchronized (getLockObject()) {
                DevicePolicyData policy = getUserData(userId);
                int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin2 = policy.mAdminList.get(i);
                    if ((admin2.disableCamera && "policy_disable_camera".equals(restriction)) || ((admin2.disableScreenCapture && "policy_disable_screen_capture".equals(restriction)) || (admin2.mandatoryBackupTransport != null && "policy_mandatory_backups".equals(restriction)))) {
                        intent = createShowAdminSupportIntent(admin2.info.getComponent(), userId);
                        break;
                    }
                }
                if (intent == null && "policy_disable_camera".equals(restriction) && (admin = getDeviceOwnerAdminLocked()) != null && admin.disableCamera) {
                    intent = createShowAdminSupportIntent(admin.info.getComponent(), this.mOwners.getDeviceOwnerUserId());
                }
            }
        } else {
            intent = this.mLocalService.createUserRestrictionSupportIntent(userId, restriction);
        }
        if (intent != null) {
            intent.putExtra("android.app.extra.RESTRICTION", restriction);
        }
        return intent;
    }

    private static boolean isLimitPasswordAllowed(ActiveAdmin admin, int minPasswordQuality) {
        if (admin.minimumPasswordMetrics.quality < minPasswordQuality) {
            return false;
        }
        return admin.info.usesPolicy(0);
    }

    public void setSystemUpdatePolicy(ComponentName who, SystemUpdatePolicy policy) {
        if (policy != null) {
            policy.validateType();
            policy.validateFreezePeriods();
            Pair<LocalDate, LocalDate> record = this.mOwners.getSystemUpdateFreezePeriodRecord();
            policy.validateAgainstPreviousFreezePeriod((LocalDate) record.first, (LocalDate) record.second, LocalDate.now());
        }
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
            if (policy == null) {
                this.mOwners.clearSystemUpdatePolicy();
            } else {
                this.mOwners.setSystemUpdatePolicy(policy);
                updateSystemUpdateFreezePeriodsRecord(false);
            }
            this.mOwners.writeDeviceOwner();
        }
        this.mContext.sendBroadcastAsUser(new Intent("android.app.action.SYSTEM_UPDATE_POLICY_CHANGED"), UserHandle.SYSTEM);
    }

    public SystemUpdatePolicy getSystemUpdatePolicy() {
        synchronized (getLockObject()) {
            SystemUpdatePolicy policy = this.mOwners.getSystemUpdatePolicy();
            if (policy == null || policy.isValid()) {
                return policy;
            }
            Slog.w(LOG_TAG, "Stored system update policy is invalid, return null instead.");
            return null;
        }
    }

    private static boolean withinRange(Pair<LocalDate, LocalDate> range, LocalDate date) {
        return (date.isBefore((ChronoLocalDate) range.first) || date.isAfter((ChronoLocalDate) range.second)) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSystemUpdateFreezePeriodsRecord(boolean saveIfChanged) {
        boolean changed;
        Slog.d(LOG_TAG, "updateSystemUpdateFreezePeriodsRecord");
        synchronized (getLockObject()) {
            SystemUpdatePolicy policy = this.mOwners.getSystemUpdatePolicy();
            if (policy == null) {
                return;
            }
            LocalDate now = LocalDate.now();
            Pair<LocalDate, LocalDate> currentPeriod = policy.getCurrentFreezePeriod(now);
            if (currentPeriod == null) {
                return;
            }
            Pair<LocalDate, LocalDate> record = this.mOwners.getSystemUpdateFreezePeriodRecord();
            LocalDate start = (LocalDate) record.first;
            LocalDate end = (LocalDate) record.second;
            if (end != null && start != null) {
                if (now.equals(end.plusDays(1L))) {
                    changed = this.mOwners.setSystemUpdateFreezePeriodRecord(start, now);
                } else if (now.isAfter(end.plusDays(1L))) {
                    if (withinRange(currentPeriod, start) && withinRange(currentPeriod, end)) {
                        changed = this.mOwners.setSystemUpdateFreezePeriodRecord(start, now);
                    } else {
                        changed = this.mOwners.setSystemUpdateFreezePeriodRecord(now, now);
                    }
                } else if (now.isBefore(start)) {
                    changed = this.mOwners.setSystemUpdateFreezePeriodRecord(now, now);
                } else {
                    changed = false;
                }
                if (changed && saveIfChanged) {
                    this.mOwners.writeDeviceOwner();
                }
            }
            changed = this.mOwners.setSystemUpdateFreezePeriodRecord(now, now);
            if (changed) {
                this.mOwners.writeDeviceOwner();
            }
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void clearSystemUpdatePolicyFreezePeriodRecord() {
        enforceShell("clearSystemUpdatePolicyFreezePeriodRecord");
        synchronized (getLockObject()) {
            Slog.i(LOG_TAG, "Clear freeze period record: " + this.mOwners.getSystemUpdateFreezePeriodRecordAsString());
            if (this.mOwners.setSystemUpdateFreezePeriodRecord(null, null)) {
                this.mOwners.writeDeviceOwner();
            }
        }
    }

    @VisibleForTesting
    boolean isCallerDeviceOwner(int callerUid) {
        synchronized (getLockObject()) {
            if (this.mOwners.hasDeviceOwner()) {
                if (UserHandle.getUserId(callerUid) != this.mOwners.getDeviceOwnerUserId()) {
                    return false;
                }
                String deviceOwnerPackageName = this.mOwners.getDeviceOwnerComponent().getPackageName();
                try {
                    String[] pkgs = this.mInjector.getIPackageManager().getPackagesForUid(callerUid);
                    for (String pkg : pkgs) {
                        if (deviceOwnerPackageName.equals(pkg)) {
                            return true;
                        }
                    }
                    return false;
                } catch (RemoteException e) {
                    return false;
                }
            }
            return false;
        }
    }

    public void notifyPendingSystemUpdate(SystemUpdateInfo info) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.NOTIFY_PENDING_SYSTEM_UPDATE", "Only the system update service can broadcast update information");
        if (UserHandle.getCallingUserId() != 0) {
            Slog.w(LOG_TAG, "Only the system update service in the system user can broadcast update information.");
        } else if (this.mOwners.saveSystemUpdateInfo(info)) {
            Intent intent = new Intent("android.app.action.NOTIFY_PENDING_SYSTEM_UPDATE").putExtra("android.app.extra.SYSTEM_UPDATE_RECEIVED_TIME", info == null ? -1L : info.getReceivedTime());
            long ident = this.mInjector.binderClearCallingIdentity();
            try {
                synchronized (getLockObject()) {
                    if (this.mOwners.hasDeviceOwner()) {
                        UserHandle deviceOwnerUser = UserHandle.of(this.mOwners.getDeviceOwnerUserId());
                        intent.setComponent(this.mOwners.getDeviceOwnerComponent());
                        this.mContext.sendBroadcastAsUser(intent, deviceOwnerUser);
                    }
                }
                int[] runningUserIds = this.mInjector.getIActivityManager().getRunningUserIds();
                for (int userId : runningUserIds) {
                    synchronized (getLockObject()) {
                        ComponentName profileOwnerPackage = this.mOwners.getProfileOwnerComponent(userId);
                        if (profileOwnerPackage != null) {
                            intent.setComponent(profileOwnerPackage);
                            this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Could not retrieve the list of running users", e);
            } finally {
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    public SystemUpdateInfo getPendingSystemUpdate(ComponentName admin) {
        Preconditions.checkNotNull(admin, "ComponentName is null");
        enforceProfileOrDeviceOwner(admin);
        return this.mOwners.getSystemUpdateInfo();
    }

    public void setPermissionPolicy(ComponentName admin, String callerPackage, int policy) throws RemoteException {
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            enforceCanManageScope(admin, callerPackage, -1, "delegation-permission-grant");
            DevicePolicyData userPolicy = getUserData(userId);
            if (userPolicy.mPermissionPolicy != policy) {
                userPolicy.mPermissionPolicy = policy;
                saveSettingsLocked(userId);
            }
        }
    }

    public int getPermissionPolicy(ComponentName admin) throws RemoteException {
        int i;
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            DevicePolicyData userPolicy = getUserData(userId);
            i = userPolicy.mPermissionPolicy;
        }
        return i;
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r13v12 */
    /* JADX WARN: Type inference failed for: r13v13 */
    /* JADX WARN: Type inference failed for: r13v15 */
    public boolean setPermissionGrantState(ComponentName admin, String callerPackage, String packageName, String permission, int grantState) throws RemoteException {
        UserHandle user = this.mInjector.binderGetCallingUserHandle();
        synchronized (getLockObject()) {
            long ident = admin;
            enforceCanManageScope(ident, callerPackage, -1, "delegation-permission-grant");
            long ident2 = this.mInjector.binderClearCallingIdentity();
            try {
                if (getTargetSdk(packageName, user.getIdentifier()) < 23) {
                    this.mInjector.binderRestoreCallingIdentity(ident2);
                    return false;
                } else if (!isRuntimePermission(permission)) {
                    this.mInjector.binderRestoreCallingIdentity(ident2);
                    return false;
                } else {
                    PackageManager packageManager = this.mInjector.getPackageManager();
                    try {
                        switch (grantState) {
                            case 0:
                                ident = ident2;
                                packageManager.updatePermissionFlags(permission, packageName, 4, 0, user);
                                break;
                            case 1:
                                ident = ident2;
                                this.mInjector.getPackageManagerInternal().grantRuntimePermission(packageName, permission, user.getIdentifier(), true);
                                packageManager.updatePermissionFlags(permission, packageName, 4, 4, user);
                                break;
                            case 2:
                                this.mInjector.getPackageManagerInternal().revokeRuntimePermission(packageName, permission, user.getIdentifier(), true);
                                ident = ident2;
                                packageManager.updatePermissionFlags(permission, packageName, 4, 4, user);
                                break;
                            default:
                                ident = ident2;
                                break;
                        }
                        this.mInjector.binderRestoreCallingIdentity(ident);
                        return true;
                    } catch (PackageManager.NameNotFoundException e) {
                        this.mInjector.binderRestoreCallingIdentity(ident);
                        return false;
                    } catch (SecurityException e2) {
                        this.mInjector.binderRestoreCallingIdentity(ident);
                        return false;
                    } catch (Throwable th) {
                        th = th;
                        this.mInjector.binderRestoreCallingIdentity(ident);
                        throw th;
                    }
                }
            } catch (PackageManager.NameNotFoundException e3) {
                ident = ident2;
            } catch (SecurityException e4) {
                ident = ident2;
            } catch (Throwable th2) {
                th = th2;
                ident = ident2;
            }
        }
    }

    public int getPermissionGrantState(ComponentName admin, String callerPackage, String packageName, String permission) throws RemoteException {
        int i;
        PackageManager packageManager = this.mInjector.getPackageManager();
        UserHandle user = this.mInjector.binderGetCallingUserHandle();
        if (!isCallerWithSystemUid()) {
            enforceCanManageScope(admin, callerPackage, -1, "delegation-permission-grant");
        }
        synchronized (getLockObject()) {
            long ident = this.mInjector.binderClearCallingIdentity();
            int granted = this.mIPackageManager.checkPermission(permission, packageName, user.getIdentifier());
            int permFlags = packageManager.getPermissionFlags(permission, packageName, user);
            if ((permFlags & 4) == 4) {
                if (granted == 0) {
                    i = 1;
                } else {
                    i = 2;
                }
                this.mInjector.binderRestoreCallingIdentity(ident);
                return i;
            }
            this.mInjector.binderRestoreCallingIdentity(ident);
            return 0;
        }
    }

    boolean isPackageInstalledForUser(String packageName, int userHandle) {
        try {
            PackageInfo pi = this.mInjector.getIPackageManager().getPackageInfo(packageName, 0, userHandle);
            if (pi != null) {
                return pi.applicationInfo.flags != 0;
            }
            return false;
        } catch (RemoteException re) {
            throw new RuntimeException("Package manager has died", re);
        }
    }

    public boolean isRuntimePermission(String permissionName) throws PackageManager.NameNotFoundException {
        PackageManager packageManager = this.mInjector.getPackageManager();
        PermissionInfo permissionInfo = packageManager.getPermissionInfo(permissionName, 0);
        return (permissionInfo.protectionLevel & 15) == 1;
    }

    public boolean isProvisioningAllowed(String action, String packageName) {
        Preconditions.checkNotNull(packageName);
        int callingUid = this.mInjector.binderGetCallingUid();
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                int uidForPackage = this.mInjector.getPackageManager().getPackageUidAsUser(packageName, UserHandle.getUserId(callingUid));
                Preconditions.checkArgument(callingUid == uidForPackage, "Caller uid doesn't match the one for the provided package.");
                this.mInjector.binderRestoreCallingIdentity(ident);
                return checkProvisioningPreConditionSkipPermission(action, packageName) == 0;
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException("Invalid package provided " + packageName, e);
            }
        } catch (Throwable th) {
            this.mInjector.binderRestoreCallingIdentity(ident);
            throw th;
        }
    }

    public int checkProvisioningPreCondition(String action, String packageName) {
        Preconditions.checkNotNull(packageName);
        enforceCanManageProfileAndDeviceOwners();
        return checkProvisioningPreConditionSkipPermission(action, packageName);
    }

    private int checkProvisioningPreConditionSkipPermission(String action, String packageName) {
        if (!this.mHasFeature) {
            return 13;
        }
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        if (action != null) {
            char c = 65535;
            int hashCode = action.hashCode();
            if (hashCode != -920528692) {
                if (hashCode != -514404415) {
                    if (hashCode != -340845101) {
                        if (hashCode == 631897778 && action.equals("android.app.action.PROVISION_MANAGED_SHAREABLE_DEVICE")) {
                            c = 3;
                        }
                    } else if (action.equals("android.app.action.PROVISION_MANAGED_PROFILE")) {
                        c = 0;
                    }
                } else if (action.equals("android.app.action.PROVISION_MANAGED_USER")) {
                    c = 2;
                }
            } else if (action.equals("android.app.action.PROVISION_MANAGED_DEVICE")) {
                c = 1;
            }
            switch (c) {
                case 0:
                    return checkManagedProfileProvisioningPreCondition(packageName, callingUserId);
                case 1:
                    return checkDeviceOwnerProvisioningPreCondition(callingUserId);
                case 2:
                    return checkManagedUserProvisioningPreCondition(callingUserId);
                case 3:
                    return checkManagedShareableDeviceProvisioningPreCondition(callingUserId);
            }
        }
        throw new IllegalArgumentException("Unknown provisioning action " + action);
    }

    private int checkDeviceOwnerProvisioningPreConditionLocked(ComponentName owner, int deviceOwnerUserId, boolean isAdb, boolean hasIncompatibleAccountsOrNonAdb) {
        if (this.mOwners.hasDeviceOwner()) {
            return 1;
        }
        if (this.mOwners.hasProfileOwner(deviceOwnerUserId)) {
            return 2;
        }
        if (!this.mUserManager.isUserRunning(new UserHandle(deviceOwnerUserId))) {
            return 3;
        }
        if (this.mIsWatch && hasPaired(0)) {
            return 8;
        }
        if (isAdb) {
            if ((this.mIsWatch || hasUserSetupCompleted(0)) && !this.mInjector.userManagerIsSplitSystemUser()) {
                if (this.mUserManager.getUserCount() > 1) {
                    return 5;
                }
                if (hasIncompatibleAccountsOrNonAdb) {
                    return 6;
                }
            }
            return 0;
        }
        if (!this.mInjector.userManagerIsSplitSystemUser()) {
            if (deviceOwnerUserId != 0) {
                return 7;
            }
            if (hasUserSetupCompleted(0)) {
                return 4;
            }
        }
        return 0;
    }

    private int checkDeviceOwnerProvisioningPreCondition(int deviceOwnerUserId) {
        int checkDeviceOwnerProvisioningPreConditionLocked;
        synchronized (getLockObject()) {
            checkDeviceOwnerProvisioningPreConditionLocked = checkDeviceOwnerProvisioningPreConditionLocked(null, deviceOwnerUserId, false, true);
        }
        return checkDeviceOwnerProvisioningPreConditionLocked;
    }

    private int checkManagedProfileProvisioningPreCondition(String packageName, int callingUserId) {
        Injector injector;
        if (hasFeatureManagedUsers()) {
            if (callingUserId == 0 && this.mInjector.userManagerIsSplitSystemUser()) {
                return 14;
            }
            if (getProfileOwner(callingUserId) != null) {
                return 2;
            }
            long ident = this.mInjector.binderClearCallingIdentity();
            try {
                UserHandle callingUserHandle = UserHandle.of(callingUserId);
                ComponentName ownerAdmin = getOwnerComponent(packageName, callingUserId);
                if (this.mUserManager.hasUserRestriction("no_add_managed_profile", callingUserHandle) && (ownerAdmin == null || isAdminAffectedByRestriction(ownerAdmin, "no_add_managed_profile", callingUserId))) {
                    return 15;
                }
                boolean canRemoveProfile = true;
                if (this.mUserManager.hasUserRestriction("no_remove_managed_profile", callingUserHandle) && (ownerAdmin == null || isAdminAffectedByRestriction(ownerAdmin, "no_remove_managed_profile", callingUserId))) {
                    canRemoveProfile = false;
                }
                if (!this.mUserManager.canAddMoreManagedProfiles(callingUserId, canRemoveProfile)) {
                    return 11;
                }
                this.mInjector.binderRestoreCallingIdentity(ident);
                return 0;
            } finally {
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
        return 9;
    }

    private ComponentName getOwnerComponent(String packageName, int userId) {
        if (isDeviceOwnerPackage(packageName, userId)) {
            return this.mOwners.getDeviceOwnerComponent();
        }
        if (isProfileOwnerPackage(packageName, userId)) {
            return this.mOwners.getProfileOwnerComponent(userId);
        }
        return null;
    }

    private ComponentName getOwnerComponent(int userId) {
        synchronized (getLockObject()) {
            if (this.mOwners.getDeviceOwnerUserId() == userId) {
                return this.mOwners.getDeviceOwnerComponent();
            } else if (this.mOwners.hasProfileOwner(userId)) {
                return this.mOwners.getProfileOwnerComponent(userId);
            } else {
                return null;
            }
        }
    }

    private int checkManagedUserProvisioningPreCondition(int callingUserId) {
        if (!hasFeatureManagedUsers()) {
            return 9;
        }
        if (!this.mInjector.userManagerIsSplitSystemUser()) {
            return 12;
        }
        if (callingUserId == 0) {
            return 10;
        }
        if (hasUserSetupCompleted(callingUserId)) {
            return 4;
        }
        return (this.mIsWatch && hasPaired(0)) ? 8 : 0;
    }

    private int checkManagedShareableDeviceProvisioningPreCondition(int callingUserId) {
        if (!this.mInjector.userManagerIsSplitSystemUser()) {
            return 12;
        }
        return checkDeviceOwnerProvisioningPreCondition(callingUserId);
    }

    private boolean hasFeatureManagedUsers() {
        try {
            return this.mIPackageManager.hasSystemFeature("android.software.managed_users", 0);
        } catch (RemoteException e) {
            return false;
        }
    }

    public String getWifiMacAddress(ComponentName admin) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -2);
        }
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            WifiInfo wifiInfo = this.mInjector.getWifiManager().getConnectionInfo();
            if (wifiInfo == null) {
                return null;
            }
            return wifiInfo.hasRealMacAddress() ? wifiInfo.getMacAddress() : null;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private int getTargetSdk(String packageName, int userId) {
        try {
            ApplicationInfo ai = this.mIPackageManager.getApplicationInfo(packageName, 0, userId);
            if (ai == null) {
                return 0;
            }
            int targetSdkVersion = ai.targetSdkVersion;
            return targetSdkVersion;
        } catch (RemoteException e) {
            return 0;
        }
    }

    public boolean isManagedProfile(ComponentName admin) {
        enforceProfileOrDeviceOwner(admin);
        return isManagedProfile(this.mInjector.userHandleGetCallingUserId());
    }

    public boolean isSystemOnlyUser(ComponentName admin) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -2);
        }
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        return UserManager.isSplitSystemUser() && callingUserId == 0;
    }

    public void reboot(ComponentName admin) {
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -2);
        }
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            if (this.mTelephonyManager.getCallState() != 0) {
                throw new IllegalStateException("Cannot be called with ongoing call on the device");
            }
            this.mInjector.powerManagerReboot("deviceowner");
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    public void setShortSupportMessage(ComponentName who, CharSequence message) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who, this.mInjector.binderGetCallingUid());
            if (!TextUtils.equals(admin.shortSupportMessage, message)) {
                admin.shortSupportMessage = message;
                saveSettingsLocked(userHandle);
            }
        }
    }

    public CharSequence getShortSupportMessage(ComponentName who) {
        CharSequence charSequence;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who, this.mInjector.binderGetCallingUid());
            charSequence = admin.shortSupportMessage;
        }
        return charSequence;
    }

    public void setLongSupportMessage(ComponentName who, CharSequence message) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who, this.mInjector.binderGetCallingUid());
            if (!TextUtils.equals(admin.longSupportMessage, message)) {
                admin.longSupportMessage = message;
                saveSettingsLocked(userHandle);
            }
        }
    }

    public CharSequence getLongSupportMessage(ComponentName who) {
        CharSequence charSequence;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who, this.mInjector.binderGetCallingUid());
            charSequence = admin.longSupportMessage;
        }
        return charSequence;
    }

    public CharSequence getShortSupportMessageForUser(ComponentName who, int userHandle) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query support message for user");
            }
            synchronized (getLockObject()) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                if (admin != null) {
                    return admin.shortSupportMessage;
                }
                return null;
            }
        }
        return null;
    }

    public CharSequence getLongSupportMessageForUser(ComponentName who, int userHandle) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query support message for user");
            }
            synchronized (getLockObject()) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                if (admin != null) {
                    return admin.longSupportMessage;
                }
                return null;
            }
        }
        return null;
    }

    public void setOrganizationColor(ComponentName who, int color) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        enforceManagedProfile(userHandle, "set organization color");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            admin.organizationColor = color;
            saveSettingsLocked(userHandle);
        }
    }

    public void setOrganizationColorForUser(int color, int userId) {
        if (!this.mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userId);
        enforceManageUsers();
        enforceManagedProfile(userId, "set organization color");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            admin.organizationColor = color;
            saveSettingsLocked(userId);
        }
    }

    public int getOrganizationColor(ComponentName who) {
        int i;
        if (!this.mHasFeature) {
            return ActiveAdmin.DEF_ORGANIZATION_COLOR;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        enforceManagedProfile(this.mInjector.userHandleGetCallingUserId(), "get organization color");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            i = admin.organizationColor;
        }
        return i;
    }

    public int getOrganizationColorForUser(int userHandle) {
        int i;
        if (!this.mHasFeature) {
            return ActiveAdmin.DEF_ORGANIZATION_COLOR;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceManagedProfile(userHandle, "get organization color");
        synchronized (getLockObject()) {
            ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userHandle);
            if (profileOwner != null) {
                i = profileOwner.organizationColor;
            } else {
                i = ActiveAdmin.DEF_ORGANIZATION_COLOR;
            }
        }
        return i;
    }

    public void setOrganizationName(ComponentName who, CharSequence text) {
        String str;
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            if (!TextUtils.equals(admin.organizationName, text)) {
                if (text != null && text.length() != 0) {
                    str = text.toString();
                    admin.organizationName = str;
                    saveSettingsLocked(userHandle);
                }
                str = null;
                admin.organizationName = str;
                saveSettingsLocked(userHandle);
            }
        }
    }

    public CharSequence getOrganizationName(ComponentName who) {
        String str;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        enforceManagedProfile(this.mInjector.userHandleGetCallingUserId(), "get organization name");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            str = admin.organizationName;
        }
        return str;
    }

    public CharSequence getDeviceOwnerOrganizationName() {
        String str = null;
        if (this.mHasFeature) {
            enforceDeviceOwnerOrManageUsers();
            synchronized (getLockObject()) {
                ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();
                if (deviceOwnerAdmin != null) {
                    str = deviceOwnerAdmin.organizationName;
                }
            }
            return str;
        }
        return null;
    }

    public CharSequence getOrganizationNameForUser(int userHandle) {
        String str;
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            enforceManagedProfile(userHandle, "get organization name");
            synchronized (getLockObject()) {
                ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userHandle);
                str = profileOwner != null ? profileOwner.organizationName : null;
            }
            return str;
        }
        return null;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public List<String> setMeteredDataDisabledPackages(ComponentName who, List<String> packageNames) {
        List<String> excludedPkgs;
        Preconditions.checkNotNull(who);
        Preconditions.checkNotNull(packageNames);
        if (!this.mHasFeature) {
            return packageNames;
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            long identity = this.mInjector.binderClearCallingIdentity();
            excludedPkgs = removeInvalidPkgsForMeteredDataRestriction(callingUserId, packageNames);
            admin.meteredDisabledPackages = packageNames;
            pushMeteredDisabledPackagesLocked(callingUserId);
            saveSettingsLocked(callingUserId);
            this.mInjector.binderRestoreCallingIdentity(identity);
        }
        return excludedPkgs;
    }

    private List<String> removeInvalidPkgsForMeteredDataRestriction(int userId, List<String> pkgNames) {
        Set<String> activeAdmins = getActiveAdminPackagesLocked(userId);
        List<String> excludedPkgs = new ArrayList<>();
        for (int i = pkgNames.size() - 1; i >= 0; i--) {
            String pkgName = pkgNames.get(i);
            if (activeAdmins.contains(pkgName)) {
                excludedPkgs.add(pkgName);
            } else {
                try {
                    if (!this.mInjector.getIPackageManager().isPackageAvailable(pkgName, userId)) {
                        excludedPkgs.add(pkgName);
                    }
                } catch (RemoteException e) {
                }
            }
        }
        pkgNames.removeAll(excludedPkgs);
        return excludedPkgs;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public List<String> getMeteredDataDisabledPackages(ComponentName who) {
        List<String> arrayList;
        Preconditions.checkNotNull(who);
        if (!this.mHasFeature) {
            return new ArrayList();
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            arrayList = admin.meteredDisabledPackages == null ? new ArrayList<>() : admin.meteredDisabledPackages;
        }
        return arrayList;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public boolean isMeteredDataDisabledPackageForUser(ComponentName who, String packageName, int userId) {
        Preconditions.checkNotNull(who);
        if (this.mHasFeature) {
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query restricted pkgs for a specific user");
            }
            synchronized (getLockObject()) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userId);
                if (admin == null || admin.meteredDisabledPackages == null) {
                    return false;
                }
                return admin.meteredDisabledPackages.contains(packageName);
            }
        }
        return false;
    }

    private void pushMeteredDisabledPackagesLocked(int userId) {
        this.mInjector.getNetworkPolicyManagerInternal().setMeteredRestrictedPackages(getMeteredDisabledPackagesLocked(userId), userId);
    }

    private Set<String> getMeteredDisabledPackagesLocked(int userId) {
        ActiveAdmin admin;
        ComponentName who = getOwnerComponent(userId);
        Set<String> restrictedPkgs = new ArraySet<>();
        if (who != null && (admin = getActiveAdminUncheckedLocked(who, userId)) != null && admin.meteredDisabledPackages != null) {
            restrictedPkgs.addAll(admin.meteredDisabledPackages);
        }
        return restrictedPkgs;
    }

    public void setAffiliationIds(ComponentName admin, List<String> ids) {
        if (!this.mHasFeature) {
            return;
        }
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        for (String id : ids) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("ids must not contain empty string");
            }
        }
        Set<String> affiliationIds = new ArraySet<>(ids);
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -1);
            getUserData(callingUserId).mAffiliationIds = affiliationIds;
            saveSettingsLocked(callingUserId);
            if (callingUserId != 0 && isDeviceOwner(admin, callingUserId)) {
                getUserData(0).mAffiliationIds = affiliationIds;
                saveSettingsLocked(0);
            }
            maybePauseDeviceWideLoggingLocked();
            maybeResumeDeviceWideLoggingLocked();
            maybeClearLockTaskPolicyLocked();
        }
    }

    public List<String> getAffiliationIds(ComponentName admin) {
        ArrayList arrayList;
        if (!this.mHasFeature) {
            return Collections.emptyList();
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -1);
            arrayList = new ArrayList(getUserData(this.mInjector.userHandleGetCallingUserId()).mAffiliationIds);
        }
        return arrayList;
    }

    public boolean isAffiliatedUser() {
        boolean isUserAffiliatedWithDeviceLocked;
        if (!this.mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            isUserAffiliatedWithDeviceLocked = isUserAffiliatedWithDeviceLocked(this.mInjector.userHandleGetCallingUserId());
        }
        return isUserAffiliatedWithDeviceLocked;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isUserAffiliatedWithDeviceLocked(int userId) {
        if (this.mOwners.hasDeviceOwner()) {
            if (userId == this.mOwners.getDeviceOwnerUserId() || userId == 0) {
                return true;
            }
            ComponentName profileOwner = getProfileOwner(userId);
            if (profileOwner == null) {
                return false;
            }
            Set<String> userAffiliationIds = getUserData(userId).mAffiliationIds;
            Set<String> deviceAffiliationIds = getUserData(0).mAffiliationIds;
            for (String id : userAffiliationIds) {
                if (deviceAffiliationIds.contains(id)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private boolean areAllUsersAffiliatedWithDeviceLocked() {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            List<UserInfo> userInfos = this.mUserManager.getUsers(true);
            for (int i = 0; i < userInfos.size(); i++) {
                int userId = userInfos.get(i).id;
                if (!isUserAffiliatedWithDeviceLocked(userId)) {
                    Slog.d(LOG_TAG, "User id " + userId + " not affiliated.");
                    return false;
                }
            }
            return true;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    public void setSecurityLoggingEnabled(ComponentName admin, boolean enabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -2);
            if (enabled == this.mInjector.securityLogGetLoggingEnabledProperty()) {
                return;
            }
            this.mInjector.securityLogSetLoggingEnabledProperty(enabled);
            if (enabled) {
                this.mSecurityLogMonitor.start();
                maybePauseDeviceWideLoggingLocked();
            } else {
                this.mSecurityLogMonitor.stop();
            }
        }
    }

    public boolean isSecurityLoggingEnabled(ComponentName admin) {
        boolean securityLogGetLoggingEnabledProperty;
        if (!this.mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            if (!isCallerWithSystemUid()) {
                Preconditions.checkNotNull(admin);
                getActiveAdminForCallerLocked(admin, -2);
            }
            securityLogGetLoggingEnabledProperty = this.mInjector.securityLogGetLoggingEnabledProperty();
        }
        return securityLogGetLoggingEnabledProperty;
    }

    private void recordSecurityLogRetrievalTime() {
        synchronized (getLockObject()) {
            long currentTime = System.currentTimeMillis();
            DevicePolicyData policyData = getUserData(0);
            if (currentTime > policyData.mLastSecurityLogRetrievalTime) {
                policyData.mLastSecurityLogRetrievalTime = currentTime;
                saveSettingsLocked(0);
            }
        }
    }

    public ParceledListSlice<SecurityLog.SecurityEvent> retrievePreRebootSecurityLogs(ComponentName admin) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(admin);
            ensureDeviceOwnerAndAllUsersAffiliated(admin);
            if (this.mContext.getResources().getBoolean(17957045) && this.mInjector.securityLogGetLoggingEnabledProperty()) {
                recordSecurityLogRetrievalTime();
                ArrayList<SecurityLog.SecurityEvent> output = new ArrayList<>();
                try {
                    SecurityLog.readPreviousEvents(output);
                    return new ParceledListSlice<>(output);
                } catch (IOException e) {
                    Slog.w(LOG_TAG, "Fail to read previous events", e);
                    return new ParceledListSlice<>(Collections.emptyList());
                }
            }
            return null;
        }
        return null;
    }

    public ParceledListSlice<SecurityLog.SecurityEvent> retrieveSecurityLogs(ComponentName admin) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(admin);
            ensureDeviceOwnerAndAllUsersAffiliated(admin);
            if (this.mInjector.securityLogGetLoggingEnabledProperty()) {
                recordSecurityLogRetrievalTime();
                List<SecurityLog.SecurityEvent> logs = this.mSecurityLogMonitor.retrieveLogs();
                if (logs != null) {
                    return new ParceledListSlice<>(logs);
                }
                return null;
            }
            return null;
        }
        return null;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public long forceSecurityLogs() {
        enforceShell("forceSecurityLogs");
        if (!this.mInjector.securityLogGetLoggingEnabledProperty()) {
            throw new IllegalStateException("logging is not available");
        }
        return this.mSecurityLogMonitor.forceLogs();
    }

    private void enforceCanManageDeviceAdmin() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_DEVICE_ADMINS", null);
    }

    private void enforceCanManageProfileAndDeviceOwners() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS", null);
    }

    private void enforceCallerSystemUserHandle() {
        int callingUid = this.mInjector.binderGetCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        if (userId != 0) {
            throw new SecurityException("Caller has to be in user 0");
        }
    }

    public boolean isUninstallInQueue(String packageName) {
        boolean contains;
        enforceCanManageDeviceAdmin();
        int userId = this.mInjector.userHandleGetCallingUserId();
        Pair<String, Integer> packageUserPair = new Pair<>(packageName, Integer.valueOf(userId));
        synchronized (getLockObject()) {
            contains = this.mPackagesToRemove.contains(packageUserPair);
        }
        return contains;
    }

    public void uninstallPackageWithActiveAdmins(final String packageName) {
        enforceCanManageDeviceAdmin();
        Preconditions.checkArgument(!TextUtils.isEmpty(packageName));
        final int userId = this.mInjector.userHandleGetCallingUserId();
        enforceUserUnlocked(userId);
        ComponentName profileOwner = getProfileOwner(userId);
        if (profileOwner != null && packageName.equals(profileOwner.getPackageName())) {
            throw new IllegalArgumentException("Cannot uninstall a package with a profile owner");
        }
        ComponentName deviceOwner = getDeviceOwnerComponent(false);
        if (getDeviceOwnerUserId() == userId && deviceOwner != null && packageName.equals(deviceOwner.getPackageName())) {
            throw new IllegalArgumentException("Cannot uninstall a package with a device owner");
        }
        Pair<String, Integer> packageUserPair = new Pair<>(packageName, Integer.valueOf(userId));
        synchronized (getLockObject()) {
            this.mPackagesToRemove.add(packageUserPair);
        }
        List<ComponentName> allActiveAdmins = getActiveAdmins(userId);
        final List<ComponentName> packageActiveAdmins = new ArrayList<>();
        if (allActiveAdmins != null) {
            for (ComponentName activeAdmin : allActiveAdmins) {
                if (packageName.equals(activeAdmin.getPackageName())) {
                    packageActiveAdmins.add(activeAdmin);
                    removeActiveAdmin(activeAdmin, userId);
                }
            }
        }
        if (packageActiveAdmins.size() == 0) {
            startUninstallIntent(packageName, userId);
        } else {
            this.mHandler.postDelayed(new Runnable() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.9
                @Override // java.lang.Runnable
                public void run() {
                    for (ComponentName activeAdmin2 : packageActiveAdmins) {
                        DevicePolicyManagerService.this.removeAdminArtifacts(activeAdmin2, userId);
                    }
                    DevicePolicyManagerService.this.startUninstallIntent(packageName, userId);
                }
            }, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    public boolean isDeviceProvisioned() {
        boolean z;
        enforceManageUsers();
        synchronized (getLockObject()) {
            z = getUserDataUnchecked(0).mUserSetupComplete;
        }
        return z;
    }

    private boolean isCurrentUserDemo() {
        if (UserManager.isDeviceInDemoMode(this.mContext)) {
            int userId = this.mInjector.userHandleGetCallingUserId();
            long callingIdentity = this.mInjector.binderClearCallingIdentity();
            try {
                return this.mUserManager.getUserInfo(userId).isDemo();
            } finally {
                this.mInjector.binderRestoreCallingIdentity(callingIdentity);
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removePackageIfRequired(String packageName, int userId) {
        if (!packageHasActiveAdmins(packageName, userId)) {
            startUninstallIntent(packageName, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startUninstallIntent(String packageName, int userId) {
        Pair<String, Integer> packageUserPair = new Pair<>(packageName, Integer.valueOf(userId));
        synchronized (getLockObject()) {
            if (this.mPackagesToRemove.contains(packageUserPair)) {
                this.mPackagesToRemove.remove(packageUserPair);
                try {
                    if (this.mInjector.getIPackageManager().getPackageInfo(packageName, 0, userId) == null) {
                        return;
                    }
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Failure talking to PackageManager while getting package info");
                }
                try {
                    this.mInjector.getIActivityManager().forceStopPackage(packageName, userId);
                } catch (RemoteException e2) {
                    Log.e(LOG_TAG, "Failure talking to ActivityManager while force stopping package");
                }
                Uri packageURI = Uri.parse("package:" + packageName);
                Intent uninstallIntent = new Intent("android.intent.action.UNINSTALL_PACKAGE", packageURI);
                uninstallIntent.setFlags(268435456);
                this.mContext.startActivityAsUser(uninstallIntent, UserHandle.of(userId));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeAdminArtifacts(ComponentName adminReceiver, int userHandle) {
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
            if (admin == null) {
                return;
            }
            DevicePolicyData policy = getUserData(userHandle);
            boolean doProxyCleanup = admin.info.usesPolicy(5);
            policy.mAdminList.remove(admin);
            policy.mAdminMap.remove(adminReceiver);
            validatePasswordOwnerLocked(policy);
            if (doProxyCleanup) {
                resetGlobalProxyLocked(policy);
            }
            pushActiveAdminPackagesLocked(userHandle);
            pushMeteredDisabledPackagesLocked(userHandle);
            saveSettingsLocked(userHandle);
            updateMaximumTimeToLockLocked(userHandle);
            policy.mRemovingAdmins.remove(adminReceiver);
            Slog.i(LOG_TAG, "Device admin " + adminReceiver + " removed from user " + userHandle);
            pushUserRestrictions(userHandle);
        }
    }

    public void setDeviceProvisioningConfigApplied() {
        enforceManageUsers();
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(0);
            policy.mDeviceProvisioningConfigApplied = true;
            saveSettingsLocked(0);
        }
    }

    public boolean isDeviceProvisioningConfigApplied() {
        boolean z;
        enforceManageUsers();
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(0);
            z = policy.mDeviceProvisioningConfigApplied;
        }
        return z;
    }

    public void forceUpdateUserSetupComplete() {
        enforceCanManageProfileAndDeviceOwners();
        enforceCallerSystemUserHandle();
        if (!this.mInjector.isBuildDebuggable()) {
            return;
        }
        boolean isUserCompleted = this.mInjector.settingsSecureGetIntForUser("user_setup_complete", 0, 0) != 0;
        DevicePolicyData policy = getUserData(0);
        policy.mUserSetupComplete = isUserCompleted;
        synchronized (getLockObject()) {
            saveSettingsLocked(0);
        }
    }

    public void setBackupServiceEnabled(ComponentName admin, boolean enabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin, -2);
            if (!enabled) {
                activeAdmin.mandatoryBackupTransport = null;
                saveSettingsLocked(0);
            }
        }
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                IBackupManager ibm = this.mInjector.getIBackupManager();
                if (ibm != null) {
                    ibm.setBackupServiceActive(0, enabled);
                }
            } catch (RemoteException e) {
                StringBuilder sb = new StringBuilder();
                sb.append("Failed ");
                sb.append(enabled ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "de");
                sb.append("activating backup service.");
                throw new IllegalStateException(sb.toString(), e);
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:11:0x001f, code lost:
        if (r2.isBackupServiceActive(0) != false) goto L13;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean isBackupServiceEnabled(android.content.ComponentName r6) {
        /*
            r5 = this;
            com.android.internal.util.Preconditions.checkNotNull(r6)
            boolean r0 = r5.mHasFeature
            r1 = 1
            if (r0 != 0) goto L9
            return r1
        L9:
            java.lang.Object r0 = r5.getLockObject()
            monitor-enter(r0)
            r2 = -2
            r5.getActiveAdminForCallerLocked(r6, r2)     // Catch: java.lang.Throwable -> L25 android.os.RemoteException -> L27
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r2 = r5.mInjector     // Catch: java.lang.Throwable -> L25 android.os.RemoteException -> L27
            android.app.backup.IBackupManager r2 = r2.getIBackupManager()     // Catch: java.lang.Throwable -> L25 android.os.RemoteException -> L27
            r3 = 0
            if (r2 == 0) goto L22
            boolean r4 = r2.isBackupServiceActive(r3)     // Catch: java.lang.Throwable -> L25 android.os.RemoteException -> L27
            if (r4 == 0) goto L22
            goto L23
        L22:
            r1 = r3
        L23:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L25
            return r1
        L25:
            r1 = move-exception
            goto L30
        L27:
            r1 = move-exception
            java.lang.IllegalStateException r2 = new java.lang.IllegalStateException     // Catch: java.lang.Throwable -> L25
            java.lang.String r3 = "Failed requesting backup service state."
            r2.<init>(r3, r1)     // Catch: java.lang.Throwable -> L25
            throw r2     // Catch: java.lang.Throwable -> L25
        L30:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L25
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.isBackupServiceEnabled(android.content.ComponentName):boolean");
    }

    public boolean setMandatoryBackupTransport(final ComponentName admin, final ComponentName backupTransportComponent) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(admin);
            synchronized (getLockObject()) {
                getActiveAdminForCallerLocked(admin, -2);
            }
            final int callingUid = this.mInjector.binderGetCallingUid();
            final AtomicBoolean success = new AtomicBoolean(false);
            final CountDownLatch countDownLatch = new CountDownLatch(1);
            ISelectBackupTransportCallback.Stub stub = new ISelectBackupTransportCallback.Stub() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.10
                public void onSuccess(String transportName) {
                    DevicePolicyManagerService.this.saveMandatoryBackupTransport(admin, callingUid, backupTransportComponent);
                    success.set(true);
                    countDownLatch.countDown();
                }

                public void onFailure(int reason) {
                    countDownLatch.countDown();
                }
            };
            long identity = this.mInjector.binderClearCallingIdentity();
            try {
                try {
                    IBackupManager ibm = this.mInjector.getIBackupManager();
                    if (ibm != null && backupTransportComponent != null) {
                        if (!ibm.isBackupServiceActive(0)) {
                            ibm.setBackupServiceActive(0, true);
                        }
                        ibm.selectBackupTransportAsync(backupTransportComponent, stub);
                        countDownLatch.await();
                        if (success.get()) {
                            ibm.setBackupEnabled(true);
                        }
                    } else if (backupTransportComponent == null) {
                        saveMandatoryBackupTransport(admin, callingUid, backupTransportComponent);
                        success.set(true);
                    }
                    this.mInjector.binderRestoreCallingIdentity(identity);
                    return success.get();
                } catch (RemoteException e) {
                    throw new IllegalStateException("Failed to set mandatory backup transport.", e);
                } catch (InterruptedException e2) {
                    throw new IllegalStateException("Failed to set mandatory backup transport.", e2);
                }
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(identity);
                throw th;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveMandatoryBackupTransport(ComponentName admin, int callingUid, ComponentName backupTransportComponent) {
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminWithPolicyForUidLocked(admin, -2, callingUid);
            if (!Objects.equals(backupTransportComponent, activeAdmin.mandatoryBackupTransport)) {
                activeAdmin.mandatoryBackupTransport = backupTransportComponent;
                saveSettingsLocked(0);
            }
        }
    }

    public ComponentName getMandatoryBackupTransport() {
        ComponentName componentName = null;
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
                ActiveAdmin activeAdmin = getDeviceOwnerAdminLocked();
                if (activeAdmin != null) {
                    componentName = activeAdmin.mandatoryBackupTransport;
                }
            }
            return componentName;
        }
        return null;
    }

    public boolean bindDeviceAdminServiceAsUser(ComponentName admin, IApplicationThread caller, IBinder activtiyToken, Intent serviceIntent, IServiceConnection connection, int flags, int targetUserId) {
        String targetPackage;
        long callingIdentity;
        if (!this.mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(admin);
        Preconditions.checkNotNull(caller);
        Preconditions.checkNotNull(serviceIntent);
        boolean z = (serviceIntent.getComponent() == null && serviceIntent.getPackage() == null) ? false : true;
        Preconditions.checkArgument(z, "Service intent must be explicit (with a package name or component): " + serviceIntent);
        Preconditions.checkNotNull(connection);
        Preconditions.checkArgument(this.mInjector.userHandleGetCallingUserId() != targetUserId, "target user id must be different from the calling user id");
        if (!getBindDeviceAdminTargetUsers(admin).contains(UserHandle.of(targetUserId))) {
            throw new SecurityException("Not allowed to bind to target user id");
        }
        synchronized (getLockObject()) {
            targetPackage = getOwnerPackageNameForUserLocked(targetUserId);
        }
        long callingIdentity2 = this.mInjector.binderClearCallingIdentity();
        try {
            Intent sanitizedIntent = createCrossUserServiceIntent(serviceIntent, targetPackage, targetUserId);
            if (sanitizedIntent == null) {
                this.mInjector.binderRestoreCallingIdentity(callingIdentity2);
                return false;
            }
            callingIdentity = callingIdentity2;
            try {
                boolean z2 = this.mInjector.getIActivityManager().bindService(caller, activtiyToken, serviceIntent, serviceIntent.resolveTypeIfNeeded(this.mContext.getContentResolver()), connection, flags, this.mContext.getOpPackageName(), targetUserId) != 0;
                this.mInjector.binderRestoreCallingIdentity(callingIdentity);
                return z2;
            } catch (RemoteException e) {
                this.mInjector.binderRestoreCallingIdentity(callingIdentity);
                return false;
            } catch (Throwable th) {
                th = th;
                this.mInjector.binderRestoreCallingIdentity(callingIdentity);
                throw th;
            }
        } catch (RemoteException e2) {
            callingIdentity = callingIdentity2;
        } catch (Throwable th2) {
            th = th2;
            callingIdentity = callingIdentity2;
        }
    }

    public List<UserHandle> getBindDeviceAdminTargetUsers(ComponentName admin) {
        ArrayList<UserHandle> targetUsers;
        if (!this.mHasFeature) {
            return Collections.emptyList();
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -1);
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            long callingIdentity = this.mInjector.binderClearCallingIdentity();
            targetUsers = new ArrayList<>();
            if (!isDeviceOwner(admin, callingUserId)) {
                if (canUserBindToDeviceOwnerLocked(callingUserId)) {
                    targetUsers.add(UserHandle.of(this.mOwners.getDeviceOwnerUserId()));
                }
            } else {
                List<UserInfo> userInfos = this.mUserManager.getUsers(true);
                for (int i = 0; i < userInfos.size(); i++) {
                    int userId = userInfos.get(i).id;
                    if (userId != callingUserId && canUserBindToDeviceOwnerLocked(userId)) {
                        targetUsers.add(UserHandle.of(userId));
                    }
                }
            }
            this.mInjector.binderRestoreCallingIdentity(callingIdentity);
        }
        return targetUsers;
    }

    private boolean canUserBindToDeviceOwnerLocked(int userId) {
        if (this.mOwners.hasDeviceOwner() && userId != this.mOwners.getDeviceOwnerUserId() && this.mOwners.hasProfileOwner(userId) && TextUtils.equals(this.mOwners.getDeviceOwnerPackageName(), this.mOwners.getProfileOwnerPackage(userId))) {
            return isUserAffiliatedWithDeviceLocked(userId);
        }
        return false;
    }

    private boolean hasIncompatibleAccountsOrNonAdbNoLock(int userId, ComponentName owner) {
        if (isAdb()) {
            wtfIfInLock();
            long token = this.mInjector.binderClearCallingIdentity();
            try {
                AccountManager am = AccountManager.get(this.mContext);
                Account[] accounts = am.getAccountsAsUser(userId);
                if (accounts.length == 0) {
                    return false;
                }
                synchronized (getLockObject()) {
                    if (owner != null && isAdminTestOnlyLocked(owner, userId)) {
                        String[] feature_allow = {"android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED"};
                        String[] feature_disallow = {"android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED"};
                        boolean compatible = true;
                        int length = accounts.length;
                        int i = 0;
                        while (true) {
                            if (i >= length) {
                                break;
                            }
                            Account account = accounts[i];
                            if (hasAccountFeatures(am, account, feature_disallow)) {
                                Log.e(LOG_TAG, account + " has " + feature_disallow[0]);
                                compatible = false;
                                break;
                            } else if (!hasAccountFeatures(am, account, feature_allow)) {
                                Log.e(LOG_TAG, account + " doesn't have " + feature_allow[0]);
                                compatible = false;
                                break;
                            } else {
                                i++;
                            }
                        }
                        if (compatible) {
                            Log.w(LOG_TAG, "All accounts are compatible");
                        } else {
                            Log.e(LOG_TAG, "Found incompatible accounts");
                        }
                        return compatible ? false : true;
                    }
                    Log.w(LOG_TAG, "Non test-only owner can't be installed with existing accounts.");
                    return true;
                }
            } finally {
                this.mInjector.binderRestoreCallingIdentity(token);
            }
        }
        return true;
    }

    private boolean hasAccountFeatures(AccountManager am, Account account, String[] features) {
        try {
            return am.hasFeatures(account, features, null, null).getResult().booleanValue();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to get account feature", e);
            return false;
        }
    }

    private boolean isAdb() {
        int callingUid = this.mInjector.binderGetCallingUid();
        return callingUid == 2000 || callingUid == 0;
    }

    public void setNetworkLoggingEnabled(ComponentName admin, boolean enabled) {
        if (!this.mHasFeature) {
            return;
        }
        synchronized (getLockObject()) {
            Preconditions.checkNotNull(admin);
            getActiveAdminForCallerLocked(admin, -2);
            if (enabled == isNetworkLoggingEnabledInternalLocked()) {
                return;
            }
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            deviceOwner.isNetworkLoggingEnabled = enabled;
            if (!enabled) {
                deviceOwner.numNetworkLoggingNotifications = 0;
                deviceOwner.lastNetworkLoggingNotificationTimeMs = 0L;
            }
            saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
            setNetworkLoggingActiveInternal(enabled);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setNetworkLoggingActiveInternal(boolean active) {
        synchronized (getLockObject()) {
            long callingIdentity = this.mInjector.binderClearCallingIdentity();
            if (active) {
                this.mNetworkLogger = new NetworkLogger(this, this.mInjector.getPackageManagerInternal());
                if (!this.mNetworkLogger.startNetworkLogging()) {
                    this.mNetworkLogger = null;
                    Slog.wtf(LOG_TAG, "Network logging could not be started due to the logging service not being available yet.");
                }
                maybePauseDeviceWideLoggingLocked();
                sendNetworkLoggingNotificationLocked();
            } else {
                if (this.mNetworkLogger != null && !this.mNetworkLogger.stopNetworkLogging()) {
                    Slog.wtf(LOG_TAG, "Network logging could not be stopped due to the logging service not being available yet.");
                }
                this.mNetworkLogger = null;
                this.mInjector.getNotificationManager().cancel(1002);
            }
            this.mInjector.binderRestoreCallingIdentity(callingIdentity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybePauseDeviceWideLoggingLocked() {
        if (!areAllUsersAffiliatedWithDeviceLocked()) {
            Slog.i(LOG_TAG, "There are unaffiliated users, security and network logging will be paused if enabled.");
            this.mSecurityLogMonitor.pause();
            if (this.mNetworkLogger != null) {
                this.mNetworkLogger.pause();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeResumeDeviceWideLoggingLocked() {
        if (areAllUsersAffiliatedWithDeviceLocked()) {
            long ident = this.mInjector.binderClearCallingIdentity();
            try {
                this.mSecurityLogMonitor.resume();
                if (this.mNetworkLogger != null) {
                    this.mNetworkLogger.resume();
                }
            } finally {
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void discardDeviceWideLogsLocked() {
        this.mSecurityLogMonitor.discardLogs();
        if (this.mNetworkLogger != null) {
            this.mNetworkLogger.discardLogs();
        }
    }

    public boolean isNetworkLoggingEnabled(ComponentName admin) {
        boolean isNetworkLoggingEnabledInternalLocked;
        if (!this.mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            enforceDeviceOwnerOrManageUsers();
            isNetworkLoggingEnabledInternalLocked = isNetworkLoggingEnabledInternalLocked();
        }
        return isNetworkLoggingEnabledInternalLocked;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isNetworkLoggingEnabledInternalLocked() {
        ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        return deviceOwner != null && deviceOwner.isNetworkLoggingEnabled;
    }

    public List<NetworkEvent> retrieveNetworkLogs(ComponentName admin, long batchToken) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(admin);
            ensureDeviceOwnerAndAllUsersAffiliated(admin);
            synchronized (getLockObject()) {
                if (this.mNetworkLogger != null && isNetworkLoggingEnabledInternalLocked()) {
                    long currentTime = System.currentTimeMillis();
                    DevicePolicyData policyData = getUserData(0);
                    if (currentTime > policyData.mLastNetworkLogsRetrievalTime) {
                        policyData.mLastNetworkLogsRetrievalTime = currentTime;
                        saveSettingsLocked(0);
                    }
                    return this.mNetworkLogger.retrieveLogs(batchToken);
                }
                return null;
            }
        }
        return null;
    }

    private void sendNetworkLoggingNotificationLocked() {
        ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        if (deviceOwner == null || !deviceOwner.isNetworkLoggingEnabled || deviceOwner.numNetworkLoggingNotifications >= 2) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - deviceOwner.lastNetworkLoggingNotificationTimeMs < MS_PER_DAY) {
            return;
        }
        deviceOwner.numNetworkLoggingNotifications++;
        if (deviceOwner.numNetworkLoggingNotifications >= 2) {
            deviceOwner.lastNetworkLoggingNotificationTimeMs = 0L;
        } else {
            deviceOwner.lastNetworkLoggingNotificationTimeMs = now;
        }
        Intent intent = new Intent("android.app.action.SHOW_DEVICE_MONITORING_DIALOG");
        intent.setPackage(AfterSalesService.PackgeName.SYSTEMUI);
        PendingIntent pendingIntent = PendingIntent.getBroadcastAsUser(this.mContext, 0, intent, 0, UserHandle.CURRENT);
        Notification notification = new Notification.Builder(this.mContext, SystemNotificationChannels.DEVICE_ADMIN).setSmallIcon(17302393).setContentTitle(this.mContext.getString(17040348)).setContentText(this.mContext.getString(17040347)).setTicker(this.mContext.getString(17040348)).setShowWhen(true).setContentIntent(pendingIntent).setStyle(new Notification.BigTextStyle().bigText(this.mContext.getString(17040347))).build();
        this.mInjector.getNotificationManager().notify(1002, notification);
        saveSettingsLocked(this.mOwners.getDeviceOwnerUserId());
    }

    private String getOwnerPackageNameForUserLocked(int userId) {
        if (this.mOwners.getDeviceOwnerUserId() == userId) {
            return this.mOwners.getDeviceOwnerPackageName();
        }
        return this.mOwners.getProfileOwnerPackage(userId);
    }

    private Intent createCrossUserServiceIntent(Intent rawIntent, String expectedPackageName, int targetUserId) throws RemoteException, SecurityException {
        ResolveInfo info = this.mIPackageManager.resolveService(rawIntent, rawIntent.resolveTypeIfNeeded(this.mContext.getContentResolver()), 0, targetUserId);
        if (info == null || info.serviceInfo == null) {
            Log.e(LOG_TAG, "Fail to look up the service: " + rawIntent + " or user " + targetUserId + " is not running");
            return null;
        } else if (!expectedPackageName.equals(info.serviceInfo.packageName)) {
            throw new SecurityException("Only allow to bind service in " + expectedPackageName);
        } else if (info.serviceInfo.exported && !"android.permission.BIND_DEVICE_ADMIN".equals(info.serviceInfo.permission)) {
            throw new SecurityException("Service must be protected by BIND_DEVICE_ADMIN permission");
        } else {
            rawIntent.setComponent(info.serviceInfo.getComponentName());
            return rawIntent;
        }
    }

    public long getLastSecurityLogRetrievalTime() {
        enforceDeviceOwnerOrManageUsers();
        return getUserData(0).mLastSecurityLogRetrievalTime;
    }

    public long getLastBugReportRequestTime() {
        enforceDeviceOwnerOrManageUsers();
        return getUserData(0).mLastBugReportRequestTime;
    }

    public long getLastNetworkLogRetrievalTime() {
        enforceDeviceOwnerOrManageUsers();
        return getUserData(0).mLastNetworkLogsRetrievalTime;
    }

    public boolean setResetPasswordToken(ComponentName admin, byte[] token) {
        boolean z;
        if (this.mHasFeature) {
            if (token == null || token.length < 32) {
                throw new IllegalArgumentException("token must be at least 32-byte long");
            }
            synchronized (getLockObject()) {
                int userHandle = this.mInjector.userHandleGetCallingUserId();
                getActiveAdminForCallerLocked(admin, -1);
                DevicePolicyData policy = getUserData(userHandle);
                long ident = this.mInjector.binderClearCallingIdentity();
                if (policy.mPasswordTokenHandle != 0) {
                    this.mLockPatternUtils.removeEscrowToken(policy.mPasswordTokenHandle, userHandle);
                }
                policy.mPasswordTokenHandle = this.mLockPatternUtils.addEscrowToken(token, userHandle);
                saveSettingsLocked(userHandle);
                z = policy.mPasswordTokenHandle != 0;
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
            return z;
        }
        return false;
    }

    public boolean clearResetPasswordToken(ComponentName admin) {
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
                int userHandle = this.mInjector.userHandleGetCallingUserId();
                getActiveAdminForCallerLocked(admin, -1);
                DevicePolicyData policy = getUserData(userHandle);
                if (policy.mPasswordTokenHandle != 0) {
                    long ident = this.mInjector.binderClearCallingIdentity();
                    boolean result = this.mLockPatternUtils.removeEscrowToken(policy.mPasswordTokenHandle, userHandle);
                    policy.mPasswordTokenHandle = 0L;
                    saveSettingsLocked(userHandle);
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    return result;
                }
                return false;
            }
        }
        return false;
    }

    public boolean isResetPasswordTokenActive(ComponentName admin) {
        synchronized (getLockObject()) {
            int userHandle = this.mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(admin, -1);
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mPasswordTokenHandle != 0) {
                long ident = this.mInjector.binderClearCallingIdentity();
                boolean isEscrowTokenActive = this.mLockPatternUtils.isEscrowTokenActive(policy.mPasswordTokenHandle, userHandle);
                this.mInjector.binderRestoreCallingIdentity(ident);
                return isEscrowTokenActive;
            }
            return false;
        }
    }

    public boolean resetPasswordWithToken(ComponentName admin, String passwordOrNull, byte[] token, int flags) {
        Preconditions.checkNotNull(token);
        synchronized (getLockObject()) {
            int userHandle = this.mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(admin, -1);
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mPasswordTokenHandle != 0) {
                String password = passwordOrNull != null ? passwordOrNull : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                return resetPasswordInternal(password, policy.mPasswordTokenHandle, token, flags, this.mInjector.binderGetCallingUid(), userHandle);
            }
            Slog.w(LOG_TAG, "No saved token handle");
            return false;
        }
    }

    public boolean isCurrentInputMethodSetByOwner() {
        enforceProfileOwnerOrSystemUser();
        return getUserData(this.mInjector.userHandleGetCallingUserId()).mCurrentInputMethodSet;
    }

    public StringParceledListSlice getOwnerInstalledCaCerts(UserHandle user) {
        StringParceledListSlice stringParceledListSlice;
        int userId = user.getIdentifier();
        enforceProfileOwnerOrFullCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            stringParceledListSlice = new StringParceledListSlice(new ArrayList(getUserData(userId).mOwnerInstalledCaCerts));
        }
        return stringParceledListSlice;
    }

    public void clearApplicationUserData(ComponentName admin, String packageName, IPackageDataObserver callback) {
        Preconditions.checkNotNull(admin, "ComponentName is null");
        Preconditions.checkNotNull(packageName, "packageName is null");
        Preconditions.checkNotNull(callback, "callback is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, -1);
        }
        int userId = UserHandle.getCallingUserId();
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                ActivityManager.getService().clearApplicationUserData(packageName, false, callback, userId);
            } catch (RemoteException e) {
            } catch (SecurityException se) {
                Slog.w(LOG_TAG, "Not allowed to clear application user data for package " + packageName, se);
                try {
                    callback.onRemoveCompleted(packageName, false);
                } catch (RemoteException e2) {
                }
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    public void setLogoutEnabled(ComponentName admin, boolean enabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getActiveAdminForCallerLocked(admin, -2);
            if (deviceOwner.isLogoutEnabled == enabled) {
                return;
            }
            deviceOwner.isLogoutEnabled = enabled;
            saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
        }
    }

    public boolean isLogoutEnabled() {
        boolean z = false;
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
                ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
                if (deviceOwner != null && deviceOwner.isLogoutEnabled) {
                    z = true;
                }
            }
            return z;
        }
        return false;
    }

    public List<String> getDisallowedSystemApps(ComponentName admin, int userId, String provisioningAction) throws RemoteException {
        enforceCanManageProfileAndDeviceOwners();
        return new ArrayList(this.mOverlayPackagesProvider.getNonRequiredApps(admin, userId, provisioningAction));
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void transferOwnership(ComponentName admin, ComponentName target, PersistableBundle bundle) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin, "Admin cannot be null.");
        Preconditions.checkNotNull(target, "Target cannot be null.");
        enforceProfileOrDeviceOwner(admin);
        if (admin.equals(target)) {
            throw new IllegalArgumentException("Provided administrator and target are the same object.");
        }
        if (admin.getPackageName().equals(target.getPackageName())) {
            throw new IllegalArgumentException("Provided administrator and target have the same package name.");
        }
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        DevicePolicyData policy = getUserData(callingUserId);
        DeviceAdminInfo incomingDeviceInfo = findAdmin(target, callingUserId, true);
        checkActiveAdminPrecondition(target, incomingDeviceInfo, policy);
        if (!incomingDeviceInfo.supportsTransferOwnership()) {
            throw new IllegalArgumentException("Provided target does not support ownership transfer.");
        }
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                if (bundle == null) {
                    bundle = new PersistableBundle();
                }
                if (isProfileOwner(admin, callingUserId)) {
                    prepareTransfer(admin, target, bundle, callingUserId, LOG_TAG_PROFILE_OWNER);
                    transferProfileOwnershipLocked(admin, target, callingUserId);
                    sendProfileOwnerCommand("android.app.action.TRANSFER_OWNERSHIP_COMPLETE", getTransferOwnershipAdminExtras(bundle), callingUserId);
                    postTransfer("android.app.action.PROFILE_OWNER_CHANGED", callingUserId);
                    if (isUserAffiliatedWithDeviceLocked(callingUserId)) {
                        notifyAffiliatedProfileTransferOwnershipComplete(callingUserId);
                    }
                } else if (isDeviceOwner(admin, callingUserId)) {
                    prepareTransfer(admin, target, bundle, callingUserId, LOG_TAG_DEVICE_OWNER);
                    transferDeviceOwnershipLocked(admin, target, callingUserId);
                    sendDeviceOwnerCommand("android.app.action.TRANSFER_OWNERSHIP_COMPLETE", getTransferOwnershipAdminExtras(bundle));
                    postTransfer("android.app.action.DEVICE_OWNER_CHANGED", callingUserId);
                }
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private void prepareTransfer(ComponentName admin, ComponentName target, PersistableBundle bundle, int callingUserId, String adminType) {
        saveTransferOwnershipBundleLocked(bundle, callingUserId);
        this.mTransferOwnershipMetadataManager.saveMetadataFile(new TransferOwnershipMetadataManager.Metadata(admin, target, callingUserId, adminType));
    }

    private void postTransfer(String broadcast, int callingUserId) {
        deleteTransferOwnershipMetadataFileLocked();
        sendOwnerChangedBroadcast(broadcast, callingUserId);
    }

    private void notifyAffiliatedProfileTransferOwnershipComplete(int callingUserId) {
        Bundle extras = new Bundle();
        extras.putParcelable("android.intent.extra.USER", UserHandle.of(callingUserId));
        sendDeviceOwnerCommand("android.app.action.AFFILIATED_PROFILE_TRANSFER_OWNERSHIP_COMPLETE", extras);
    }

    private void transferProfileOwnershipLocked(ComponentName admin, ComponentName target, int profileOwnerUserId) {
        transferActiveAdminUncheckedLocked(target, admin, profileOwnerUserId);
        this.mOwners.transferProfileOwner(target, profileOwnerUserId);
        Slog.i(LOG_TAG, "Profile owner set: " + target + " on user " + profileOwnerUserId);
        this.mOwners.writeProfileOwner(profileOwnerUserId);
        this.mDeviceAdminServiceController.startServiceForOwner(target.getPackageName(), profileOwnerUserId, "transfer-profile-owner");
    }

    private void transferDeviceOwnershipLocked(ComponentName admin, ComponentName target, int userId) {
        transferActiveAdminUncheckedLocked(target, admin, userId);
        this.mOwners.transferDeviceOwnership(target);
        Slog.i(LOG_TAG, "Device owner set: " + target + " on user " + userId);
        this.mOwners.writeDeviceOwner();
        this.mDeviceAdminServiceController.startServiceForOwner(target.getPackageName(), userId, "transfer-device-owner");
    }

    private Bundle getTransferOwnershipAdminExtras(PersistableBundle bundle) {
        Bundle extras = new Bundle();
        if (bundle != null) {
            extras.putParcelable("android.app.extra.TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE", bundle);
        }
        return extras;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void setStartUserSessionMessage(ComponentName admin, CharSequence startUserSessionMessage) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);
        String startUserSessionMessageString = startUserSessionMessage != null ? startUserSessionMessage.toString() : null;
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getActiveAdminForCallerLocked(admin, -2);
            if (TextUtils.equals(deviceOwner.startUserSessionMessage, startUserSessionMessage)) {
                return;
            }
            deviceOwner.startUserSessionMessage = startUserSessionMessageString;
            saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
            this.mInjector.getActivityManagerInternal().setSwitchingFromSystemUserMessage(startUserSessionMessageString);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void setEndUserSessionMessage(ComponentName admin, CharSequence endUserSessionMessage) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);
        String endUserSessionMessageString = endUserSessionMessage != null ? endUserSessionMessage.toString() : null;
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getActiveAdminForCallerLocked(admin, -2);
            if (TextUtils.equals(deviceOwner.endUserSessionMessage, endUserSessionMessage)) {
                return;
            }
            deviceOwner.endUserSessionMessage = endUserSessionMessageString;
            saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
            this.mInjector.getActivityManagerInternal().setSwitchingToSystemUserMessage(endUserSessionMessageString);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public String getStartUserSessionMessage(ComponentName admin) {
        String str;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getActiveAdminForCallerLocked(admin, -2);
            str = deviceOwner.startUserSessionMessage;
        }
        return str;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public String getEndUserSessionMessage(ComponentName admin) {
        String str;
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getActiveAdminForCallerLocked(admin, -2);
            str = deviceOwner.endUserSessionMessage;
        }
        return str;
    }

    private void deleteTransferOwnershipMetadataFileLocked() {
        this.mTransferOwnershipMetadataManager.deleteMetadataFile();
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public PersistableBundle getTransferOwnershipBundle() {
        synchronized (getLockObject()) {
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(null, -1);
            File bundleFile = new File(this.mInjector.environmentGetUserSystemDirectory(callingUserId), TRANSFER_OWNERSHIP_PARAMETERS_XML);
            if (!bundleFile.exists()) {
                return null;
            }
            try {
                FileInputStream stream = new FileInputStream(bundleFile);
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(stream, null);
                    parser.next();
                    PersistableBundle restoreFromXml = PersistableBundle.restoreFromXml(parser);
                    $closeResource(null, stream);
                    return restoreFromXml;
                } finally {
                }
            } catch (IOException | IllegalArgumentException | XmlPullParserException e) {
                Slog.e(LOG_TAG, "Caught exception while trying to load the owner transfer parameters from file " + bundleFile, e);
                return null;
            }
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public int addOverrideApn(ComponentName who, ApnSetting apnSetting) {
        if (!this.mHasFeature) {
            return -1;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in addOverrideApn");
        Preconditions.checkNotNull(apnSetting, "ApnSetting is null in addOverrideApn");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            Uri resultUri = this.mContext.getContentResolver().insert(Telephony.Carriers.DPC_URI, apnSetting.toContentValues());
            if (resultUri == null) {
                return -1;
            }
            try {
                int operatedId = Integer.parseInt(resultUri.getLastPathSegment());
                return operatedId;
            } catch (NumberFormatException e) {
                Slog.e(LOG_TAG, "Failed to parse inserted override APN id.", e);
                return -1;
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public boolean updateOverrideApn(ComponentName who, int apnId, ApnSetting apnSetting) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null in updateOverrideApn");
            Preconditions.checkNotNull(apnSetting, "ApnSetting is null in updateOverrideApn");
            synchronized (getLockObject()) {
                getActiveAdminForCallerLocked(who, -2);
            }
            if (apnId < 0) {
                return false;
            }
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                return this.mContext.getContentResolver().update(Uri.withAppendedPath(Telephony.Carriers.DPC_URI, Integer.toString(apnId)), apnSetting.toContentValues(), null, null) > 0;
            } finally {
                this.mInjector.binderRestoreCallingIdentity(id);
            }
        }
        return false;
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public boolean removeOverrideApn(ComponentName who, int apnId) {
        if (!this.mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in removeOverrideApn");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        return removeOverrideApnUnchecked(apnId);
    }

    private boolean removeOverrideApnUnchecked(int apnId) {
        if (apnId < 0) {
            return false;
        }
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            int numDeleted = this.mContext.getContentResolver().delete(Uri.withAppendedPath(Telephony.Carriers.DPC_URI, Integer.toString(apnId)), null, null);
            return numDeleted > 0;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public List<ApnSetting> getOverrideApns(ComponentName who) {
        if (!this.mHasFeature) {
            return Collections.emptyList();
        }
        Preconditions.checkNotNull(who, "ComponentName is null in getOverrideApns");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        return getOverrideApnsUnchecked();
    }

    private List<ApnSetting> getOverrideApnsUnchecked() {
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            Cursor cursor = this.mContext.getContentResolver().query(Telephony.Carriers.DPC_URI, null, null, null, null);
            if (cursor == null) {
                return Collections.emptyList();
            }
            try {
                List<ApnSetting> apnList = new ArrayList<>();
                cursor.moveToPosition(-1);
                while (cursor.moveToNext()) {
                    ApnSetting apn = ApnSetting.makeApnSetting(cursor);
                    apnList.add(apn);
                }
                return apnList;
            } finally {
                cursor.close();
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public void setOverrideApnsEnabled(ComponentName who, boolean enabled) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in setOverrideApnEnabled");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
        setOverrideApnsEnabledUnchecked(enabled);
    }

    private void setOverrideApnsEnabledUnchecked(boolean enabled) {
        ContentValues value = new ContentValues();
        value.put("enforced", Boolean.valueOf(enabled));
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            this.mContext.getContentResolver().update(Telephony.Carriers.ENFORCE_MANAGED_URI, value, null, null);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override // com.android.server.devicepolicy.BaseIDevicePolicyManager
    public boolean isOverrideApnEnabled(ComponentName who) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null in isOverrideApnEnabled");
            synchronized (getLockObject()) {
                getActiveAdminForCallerLocked(who, -2);
            }
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                Cursor enforceCursor = this.mContext.getContentResolver().query(Telephony.Carriers.ENFORCE_MANAGED_URI, null, null, null, null);
                if (enforceCursor == null) {
                    return false;
                }
                try {
                    try {
                        if (enforceCursor.moveToFirst()) {
                            return enforceCursor.getInt(enforceCursor.getColumnIndex("enforced")) == 1;
                        }
                    } catch (IllegalArgumentException e) {
                        Slog.e(LOG_TAG, "Cursor returned from ENFORCE_MANAGED_URI doesn't contain correct info.", e);
                    }
                    return false;
                } finally {
                    enforceCursor.close();
                }
            } finally {
                this.mInjector.binderRestoreCallingIdentity(id);
            }
        }
        return false;
    }

    @VisibleForTesting
    void saveTransferOwnershipBundleLocked(PersistableBundle bundle, int userId) {
        File parametersFile = new File(this.mInjector.environmentGetUserSystemDirectory(userId), TRANSFER_OWNERSHIP_PARAMETERS_XML);
        AtomicFile atomicFile = new AtomicFile(parametersFile);
        FileOutputStream stream = null;
        try {
            stream = atomicFile.startWrite();
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(stream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_TRANSFER_OWNERSHIP_BUNDLE);
            bundle.saveToXml(serializer);
            serializer.endTag(null, TAG_TRANSFER_OWNERSHIP_BUNDLE);
            serializer.endDocument();
            atomicFile.finishWrite(stream);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(LOG_TAG, "Caught exception while trying to save the owner transfer parameters to file " + parametersFile, e);
            parametersFile.delete();
            atomicFile.failWrite(stream);
        }
    }

    void deleteTransferOwnershipBundleLocked(int userId) {
        File parametersFile = new File(this.mInjector.environmentGetUserSystemDirectory(userId), TRANSFER_OWNERSHIP_PARAMETERS_XML);
        parametersFile.delete();
    }

    private void maybeLogPasswordComplexitySet(ComponentName who, int userId, boolean parent, PasswordMetrics metrics) {
        if (SecurityLog.isLoggingEnabled()) {
            int affectedUserId = parent ? getProfileParentId(userId) : userId;
            SecurityLog.writeEvent(210017, new Object[]{who.getPackageName(), Integer.valueOf(userId), Integer.valueOf(affectedUserId), Integer.valueOf(metrics.length), Integer.valueOf(metrics.quality), Integer.valueOf(metrics.letters), Integer.valueOf(metrics.nonLetter), Integer.valueOf(metrics.numeric), Integer.valueOf(metrics.upperCase), Integer.valueOf(metrics.lowerCase), Integer.valueOf(metrics.symbols)});
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String getManagedProvisioningPackage(Context context) {
        return context.getResources().getString(17039700);
    }
}
