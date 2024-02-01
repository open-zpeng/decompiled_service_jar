package com.android.server.devicepolicy;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.NetworkEvent;
import android.app.admin.PasswordMetrics;
import android.app.admin.SecurityLog;
import android.app.admin.StartInstallingUpdateCallback;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.app.backup.IBackupManager;
import android.app.trust.TrustManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
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
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.StorageManager;
import android.permission.PermissionControllerManager;
import android.provider.ContactsContract;
import android.provider.ContactsInternal;
import android.provider.Settings;
import android.provider.Telephony;
import android.security.IKeyChainAliasCallback;
import android.security.IKeyChainService;
import android.security.KeyChain;
import android.service.persistentdata.PersistentDataBlockManager;
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
import android.view.inputmethod.InputMethodSystemProperty;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
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
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.UiModeManagerService;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.devicepolicy.TransferOwnershipMetadataManager;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.DumpState;
import com.android.server.pm.UserRestrictionsUtils;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;
import com.google.android.collect.Sets;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public class DevicePolicyManagerService extends BaseIDevicePolicyManager {
    private static final String AB_DEVICE_KEY = "ro.build.ab_update";
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
    private static final Set<Integer> DA_DISALLOWED_POLICIES;
    private static final int DEVICE_ADMIN_DEACTIVATE_TIMEOUT = 10000;
    private static final String DEVICE_POLICIES_XML = "device_policies.xml";
    private static final String DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML = "do-not-ask-credentials-on-boot";
    private static final boolean ENABLE_LOCK_GUARD = true;
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
    private static final int UNATTENDED_MANAGED_KIOSK_MS = 30000;
    private static final boolean VERBOSE_LOG = false;
    final Handler mBackgroundHandler;
    private final CertificateMonitor mCertificateMonitor;
    private DevicePolicyConstants mConstants;
    private final DevicePolicyConstantsObserver mConstantsObserver;
    final Context mContext;
    private final DeviceAdminServiceController mDeviceAdminServiceController;
    final Handler mHandler;
    final boolean mHasFeature;
    final boolean mHasTelephonyFeature;
    final IPackageManager mIPackageManager;
    final Injector mInjector;
    final boolean mIsWatch;
    final LocalService mLocalService;
    private final Object mLockDoNoUseDirectly;
    private final LockPatternUtils mLockPatternUtils;
    @GuardedBy({"getLockObject()"})
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
    @GuardedBy({"getLockObject()"})
    final SparseArray<DevicePolicyData> mUserData;
    final UserManager mUserManager;
    final UserManagerInternal mUserManagerInternal;
    @GuardedBy({"getLockObject()"})
    final SparseArray<PasswordMetrics> mUserPasswordMetrics;
    private static final long MS_PER_DAY = TimeUnit.DAYS.toMillis(1);
    private static final long EXPIRATION_GRACE_PERIOD_MS = MS_PER_DAY * 5;
    private static final String[] DELEGATIONS = {"delegation-cert-install", "delegation-app-restrictions", "delegation-block-uninstall", "delegation-enable-system-app", "delegation-keep-uninstalled-packages", "delegation-package-access", "delegation-permission-grant", "delegation-install-existing-package", "delegation-keep-uninstalled-packages", "delegation-network-logging", "delegation-cert-selection"};
    private static final List<String> DEVICE_OWNER_DELEGATIONS = Arrays.asList("delegation-network-logging");
    private static final List<String> EXCLUSIVE_DELEGATIONS = Arrays.asList("delegation-network-logging", "delegation-cert-selection");
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
        GLOBAL_SETTINGS_WHITELIST.add("private_dns_mode");
        GLOBAL_SETTINGS_WHITELIST.add("private_dns_specifier");
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
        DA_DISALLOWED_POLICIES = new ArraySet();
        DA_DISALLOWED_POLICIES.add(8);
        DA_DISALLOWED_POLICIES.add(9);
        DA_DISALLOWED_POLICIES.add(6);
        DA_DISALLOWED_POLICIES.add(0);
        MINIMUM_STRONG_AUTH_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1L);
    }

    final Object getLockObject() {
        long start = this.mStatLogger.getTime();
        LockGuard.guard(7);
        this.mStatLogger.logDurationStat(0, start);
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
            String dpmsClassName = context.getResources().getString(17039722);
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
        private static final String TAG_CROSS_PROFILE_CALENDAR_PACKAGES = "cross-profile-calendar-packages";
        private static final String TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL = "cross-profile-calendar-packages-null";
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
        String startUserSessionMessage = null;
        String endUserSessionMessage = null;
        List<String> mCrossProfileCalendarPackages = Collections.emptyList();

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
            List<String> list = this.crossProfileWidgetProviders;
            if (list != null && !list.isEmpty()) {
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
            List<String> list2 = this.mCrossProfileCalendarPackages;
            if (list2 == null) {
                out.startTag(null, TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL);
                out.endTag(null, TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL);
                return;
            }
            writePackageListToXml(out, TAG_CROSS_PROFILE_CALENDAR_PACKAGES, list2);
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

        void readFromXml(XmlPullParser parser, boolean shouldOverridePolicies) throws XmlPullParserException, IOException {
            int outerDepth = parser.getDepth();
            while (true) {
                int type = parser.next();
                if (type != 1) {
                    if (type != 3 || parser.getDepth() > outerDepth) {
                        if (type != 3 && type != 4) {
                            String tag = parser.getName();
                            if ("policies".equals(tag)) {
                                if (shouldOverridePolicies) {
                                    Log.d(DevicePolicyManagerService.LOG_TAG, "Overriding device admin policies from XML.");
                                    this.info.readPoliciesFromXml(parser);
                                }
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
                                this.parentAdmin.readFromXml(parser, shouldOverridePolicies);
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
                            } else if (TAG_CROSS_PROFILE_CALENDAR_PACKAGES.equals(tag)) {
                                this.mCrossProfileCalendarPackages = readPackageList(parser, tag);
                            } else if (TAG_CROSS_PROFILE_CALENDAR_PACKAGES_NULL.equals(tag)) {
                                this.mCrossProfileCalendarPackages = null;
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
            Bundle bundle = this.userRestrictions;
            return bundle != null && bundle.size() > 0;
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
            if (this.mCrossProfileCalendarPackages != null) {
                pw.print(prefix);
                pw.print("mCrossProfileCalendarPackages=");
                pw.println(this.mCrossProfileCalendarPackages);
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
            return new Owners(getUserManager(), getUserManagerInternal(), getPackageManagerInternal(), getActivityTaskManagerInternal());
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

        ActivityTaskManagerInternal getActivityTaskManagerInternal() {
            return (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
        }

        PermissionControllerManager getPermissionControllerManager(UserHandle user) {
            if (user.equals(this.mContext.getUser())) {
                return (PermissionControllerManager) this.mContext.getSystemService(PermissionControllerManager.class);
            }
            try {
                return (PermissionControllerManager) this.mContext.createPackageContextAsUser(this.mContext.getPackageName(), 0, user).getSystemService(PermissionControllerManager.class);
            } catch (PackageManager.NameNotFoundException notPossible) {
                throw new IllegalStateException(notPossible);
            }
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

        ConnectivityManager getConnectivityManager() {
            return (ConnectivityManager) this.mContext.getSystemService(ConnectivityManager.class);
        }

        IWindowManager getIWindowManager() {
            return IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        }

        IActivityManager getIActivityManager() {
            return ActivityManager.getService();
        }

        IActivityTaskManager getIActivityTaskManager() {
            return ActivityTaskManager.getService();
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

        /* JADX INFO: Access modifiers changed from: package-private */
        public void powerManagerReboot(String reason) {
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

        void settingsSystemPutStringForUser(String name, String value, int userId) {
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
        this.mConstantsObserver = new DevicePolicyConstantsObserver(this.mHandler);
        this.mConstantsObserver.register();
        this.mConstants = loadConstants();
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
        this.mHasTelephonyFeature = this.mInjector.getPackageManager().hasSystemFeature("android.hardware.telephony");
        this.mBackgroundHandler = BackgroundThread.getHandler();
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

    @GuardedBy({"getLockObject()"})
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
            if (userHandle == 0) {
                Slog.w(LOG_TAG, "Tried to remove device policy file for user 0! Ignoring.");
                return;
            }
            updatePasswordQualityCacheForUserGroup(userHandle);
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
        if (!this.mInjector.systemPropertiesGet(PROPERTY_DEVICE_OWNER_PRESENT, "").isEmpty()) {
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
        Owners owners = this.mOwners;
        owners.setDeviceOwnerWithRestrictionsMigrated(doComponent, owners.getDeviceOwnerName(), this.mOwners.getDeviceOwnerUserId(), !this.mOwners.getDeviceOwnerUserRestrictionsNeedsMigration());
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
        AlarmManager am;
        long expiration = getPasswordExpirationLocked(null, userHandle, parent);
        long now = System.currentTimeMillis();
        long timeToExpire = expiration - now;
        if (expiration == 0) {
            alarmInterval = 0;
        } else if (timeToExpire <= 0) {
            alarmInterval = MS_PER_DAY + now;
        } else {
            long alarmTime = MS_PER_DAY;
            long alarmInterval2 = timeToExpire % alarmTime;
            if (alarmInterval2 == 0) {
                alarmInterval2 = MS_PER_DAY;
            }
            alarmInterval = alarmInterval2 + now;
        }
        long token = this.mInjector.binderClearCallingIdentity();
        if (parent) {
            try {
                affectedUserHandle = getProfileParentId(userHandle);
            } catch (Throwable th) {
                th = th;
                this.mInjector.binderRestoreCallingIdentity(token);
                throw th;
            }
        } else {
            affectedUserHandle = userHandle;
        }
        try {
            am = this.mInjector.getAlarmManager();
        } catch (Throwable th2) {
            th = th2;
        }
        try {
            PendingIntent pi = PendingIntent.getBroadcastAsUser(context, REQUEST_EXPIRE_PASSWORD, new Intent(ACTION_EXPIRED_PASSWORD_NOTIFICATION), 1207959552, UserHandle.of(affectedUserHandle));
            am.cancel(pi);
            if (alarmInterval != 0) {
                am.set(1, alarmInterval, pi);
            }
            this.mInjector.binderRestoreCallingIdentity(token);
        } catch (Throwable th3) {
            th = th3;
            this.mInjector.binderRestoreCallingIdentity(token);
            throw th;
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
        return getActiveAdminOrCheckPermissionForCallerLocked(who, reqPolicy, null);
    }

    ActiveAdmin getActiveAdminOrCheckPermissionForCallerLocked(ComponentName who, int reqPolicy, String permission) throws SecurityException {
        ensureLocked();
        int callingUid = this.mInjector.binderGetCallingUid();
        ActiveAdmin result = getActiveAdminWithPolicyForUidLocked(who, reqPolicy, callingUid);
        if (result != null) {
            return result;
        }
        if (permission != null && this.mContext.checkCallingPermission(permission) == 0) {
            return null;
        }
        if (who != null) {
            int userId = UserHandle.getUserId(callingUid);
            DevicePolicyData policy = getUserData(userId);
            ActiveAdmin admin = policy.mAdminMap.get(who);
            boolean isDeviceOwner = isDeviceOwner(admin.info.getComponent(), userId);
            boolean isProfileOwner = isProfileOwner(admin.info.getComponent(), userId);
            if (reqPolicy == -2) {
                throw new SecurityException("Admin " + admin.info.getComponent() + " does not own the device");
            } else if (reqPolicy == -1) {
                throw new SecurityException("Admin " + admin.info.getComponent() + " does not own the profile");
            } else if (DA_DISALLOWED_POLICIES.contains(Integer.valueOf(reqPolicy)) && !isDeviceOwner && !isProfileOwner) {
                throw new SecurityException("Admin " + admin.info.getComponent() + " is not a device owner or profile owner, so may not use policy: " + admin.info.getTagForPolicy(reqPolicy));
            } else {
                throw new SecurityException("Admin " + admin.info.getComponent() + " did not specify uses-policy for: " + admin.info.getTagForPolicy(reqPolicy));
            }
        }
        throw new SecurityException("No active admin owned by uid " + callingUid + " for policy #" + reqPolicy);
    }

    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who, int reqPolicy, boolean parent) throws SecurityException {
        return getActiveAdminOrCheckPermissionForCallerLocked(who, reqPolicy, parent, null);
    }

    ActiveAdmin getActiveAdminOrCheckPermissionForCallerLocked(ComponentName who, int reqPolicy, boolean parent, String permission) throws SecurityException {
        ensureLocked();
        if (parent) {
            enforceManagedProfile(this.mInjector.userHandleGetCallingUserId(), "call APIs on the parent profile");
        }
        ActiveAdmin admin = getActiveAdminOrCheckPermissionForCallerLocked(who, reqPolicy, permission);
        return parent ? admin.getParentActiveAdmin() : admin;
    }

    private ActiveAdmin getActiveAdminForUidLocked(ComponentName who, int uid) {
        ensureLocked();
        int userId = UserHandle.getUserId(uid);
        DevicePolicyData policy = getUserData(userId);
        ActiveAdmin admin = policy.mAdminMap.get(who);
        if (admin == null) {
            throw new SecurityException("No active admin " + who + " for UID " + uid);
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
        boolean allowedToUsePolicy = ownsDevice || ownsProfile || !DA_DISALLOWED_POLICIES.contains(Integer.valueOf(reqPolicy)) || getTargetSdk(admin.info.getPackageName(), userId) < 29;
        return allowedToUsePolicy && admin.info.usesPolicy(reqPolicy);
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
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setBackgroundActivityStartsAllowed(true);
        if (result == null) {
            this.mContext.sendBroadcastAsUser(intent, admin.getUserHandle(), null, options.toBundle());
            return true;
        }
        this.mContext.sendOrderedBroadcastAsUser(intent, admin.getUserHandle(), null, -1, options.toBundle(), result, this.mHandler, -1, null, null);
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
    /* JADX WARN: Removed duplicated region for block: B:130:0x0327 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void saveSettingsLocked(int r22) {
        /*
            Method dump skipped, instructions count: 818
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.saveSettingsLocked(int):void");
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

    /* JADX WARN: Not initialized variable reg: 17, insn: 0x0417: MOVE  (r8 I:??[OBJECT, ARRAY]) = (r17 I:??[OBJECT, ARRAY] A[D('stream' java.io.FileInputStream)]), block:B:182:0x0416 */
    /* JADX WARN: Not initialized variable reg: 17, insn: 0x041b: MOVE  (r8 I:??[OBJECT, ARRAY]) = (r17 I:??[OBJECT, ARRAY] A[D('stream' java.io.FileInputStream)]), block:B:184:0x041b */
    /* JADX WARN: Removed duplicated region for block: B:203:0x045e  */
    /* JADX WARN: Removed duplicated region for block: B:206:0x0475  */
    /* JADX WARN: Removed duplicated region for block: B:209:0x044a A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:251:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void loadSettingsLocked(com.android.server.devicepolicy.DevicePolicyManagerService.DevicePolicyData r26, int r27) {
        /*
            Method dump skipped, instructions count: 1147
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.loadSettingsLocked(com.android.server.devicepolicy.DevicePolicyManagerService$DevicePolicyData, int):void");
    }

    private boolean shouldOverwritePoliciesFromXml(ComponentName deviceAdminComponent, int userHandle) {
        return (isProfileOwner(deviceAdminComponent, userHandle) || isDeviceOwner(deviceAdminComponent, userHandle)) ? false : true;
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
            this.mInjector.getIActivityTaskManager().updateLockTaskFeatures(userId, flags);
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
        updatePasswordQualityCacheForUserGroup(userId == 0 ? -1 : userId);
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
        ActiveAdmin newAdmin;
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_DEVICE_ADMINS", null);
        enforceFullCrossUsersPermission(userHandle);
        DevicePolicyData policy = getUserData(userHandle);
        DeviceAdminInfo info = findAdmin(adminReceiver, userHandle, true);
        synchronized (getLockObject()) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                checkActiveAdminPrecondition(adminReceiver, info, policy);
                long ident = this.mInjector.binderClearCallingIdentity();
                try {
                    ActiveAdmin existingAdmin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
                    if (!refreshing && existingAdmin != null) {
                        throw new IllegalArgumentException("Admin is already added");
                    }
                    newAdmin = new ActiveAdmin(info, false);
                    newAdmin.testOnlyAdmin = existingAdmin != null ? existingAdmin.testOnlyAdmin : isPackageTestOnly(adminReceiver.getPackageName(), userHandle);
                    policy.mAdminMap.put(adminReceiver, newAdmin);
                    int replaceIndex = -1;
                    int N = policy.mAdminList.size();
                    int i = 0;
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
                } catch (Throwable th2) {
                    th = th2;
                }
                try {
                    sendAdminCommandLocked(newAdmin, "android.app.action.DEVICE_ADMIN_ENABLED", onEnableData, (BroadcastReceiver) null);
                    this.mInjector.binderRestoreCallingIdentity(ident);
                } catch (Throwable th3) {
                    th = th3;
                    this.mInjector.binderRestoreCallingIdentity(ident);
                    throw th;
                }
            } catch (Throwable th4) {
                th = th4;
                throw th;
            }
        }
    }

    private void loadAdminDataAsync() {
        this.mInjector.postOnSystemServerInitThreadPool(new Runnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$_Nw-YGl5ncBg-LJs8W81WNW6xoU
            @Override // java.lang.Runnable
            public final void run() {
                DevicePolicyManagerService.this.lambda$loadAdminDataAsync$0$DevicePolicyManagerService();
            }
        });
    }

    public /* synthetic */ void lambda$loadAdminDataAsync$0$DevicePolicyManagerService() {
        pushActiveAdminPackages();
        this.mUsageStatsManagerInternal.onAdminDataAvailable();
        pushAllMeteredRestrictedPackages();
        this.mInjector.getNetworkPolicyManagerInternal().onAdminDataAvailable();
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
            long ident = this.mInjector.binderClearCallingIdentity();
            PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.quality != quality) {
                metrics.quality = quality;
                updatePasswordValidityCheckpointLocked(userId, parent);
                updatePasswordQualityCacheForUserGroup(userId);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
        DevicePolicyEventLogger.createEvent(1).setAdmin(who).setInt(quality).setBoolean(parent).write();
    }

    @GuardedBy({"getLockObject()"})
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

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePasswordQualityCacheForUserGroup(int userId) {
        List<UserInfo> users;
        if (userId == -1) {
            users = this.mUserManager.getUsers();
        } else {
            users = this.mUserManager.getProfiles(userId);
        }
        for (UserInfo userInfo : users) {
            int currentUserId = userInfo.id;
            this.mPolicyCache.setPasswordQuality(currentUserId, getPasswordQuality(null, currentUserId, false));
        }
    }

    public int getPasswordQuality(ComponentName who, int userHandle, boolean parent) {
        if (!this.mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            int mode = 0;
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
        }
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

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isSeparateProfileChallengeEnabled(int userHandle) {
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
        DevicePolicyEventLogger.createEvent(2).setAdmin(who).setInt(length).write();
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
        if (!this.mHasFeature || !this.mLockPatternUtils.hasSecureLockScreen()) {
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
        if (!this.mLockPatternUtils.hasSecureLockScreen()) {
            return 0;
        }
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
        if (!this.mHasFeature || !this.mLockPatternUtils.hasSecureLockScreen()) {
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
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                long timeout = 0;
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
        DevicePolicyEventLogger.createEvent(49).setAdmin(admin).write();
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
                DevicePolicyEventLogger.createEvent((int) HdmiCecKeycode.CEC_KEYCODE_F5).setAdmin(admin).write();
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
        if (!this.mHasFeature || !this.mLockPatternUtils.hasSecureLockScreen()) {
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
        DevicePolicyEventLogger.createEvent(7).setAdmin(who).setInt(length).write();
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
        DevicePolicyEventLogger.createEvent(6).setAdmin(who).setInt(length).write();
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
        DevicePolicyEventLogger.createEvent(5).setAdmin(who).setInt(length).write();
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
        DevicePolicyEventLogger.createEvent(3).setAdmin(who).setInt(length).write();
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
        DevicePolicyEventLogger.createEvent(8).setAdmin(who).setInt(length).write();
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
        DevicePolicyEventLogger.createEvent(4).setAdmin(who).setInt(length).write();
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
                if (who != null) {
                    ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                    return admin != null ? getter.apply(admin).intValue() : 0;
                }
                int maxValue = 0;
                List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                int N = admins.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin2 = admins.get(i);
                    if (isLimitPasswordAllowed(admin2, minimumPasswordQuality)) {
                        Integer adminValue = getter.apply(admin2);
                        if (adminValue.intValue() > maxValue) {
                            maxValue = adminValue.intValue();
                        }
                    }
                }
                return maxValue;
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

    private boolean isPasswordSufficientForUserWithoutCheckpointLocked(PasswordMetrics metrics, int userId, boolean parent) {
        int requiredQuality = getPasswordQuality(null, userId, parent);
        if (requiredQuality < 131072 || metrics.length >= getPasswordMinimumLength(null, userId, parent)) {
            return requiredQuality == 393216 ? metrics.upperCase >= getPasswordMinimumUpperCase(null, userId, parent) && metrics.lowerCase >= getPasswordMinimumLowerCase(null, userId, parent) && metrics.letters >= getPasswordMinimumLetters(null, userId, parent) && metrics.numeric >= getPasswordMinimumNumeric(null, userId, parent) && metrics.symbols >= getPasswordMinimumSymbols(null, userId, parent) && metrics.nonLetter >= getPasswordMinimumNonLetter(null, userId, parent) : metrics.quality >= requiredQuality;
        }
        return false;
    }

    public int getPasswordComplexity() {
        int i;
        DevicePolicyEventLogger.createEvent(72).setStrings(this.mInjector.getPackageManager().getPackagesForUid(this.mInjector.binderGetCallingUid())).write();
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        enforceUserUnlocked(callingUserId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.REQUEST_PASSWORD_COMPLEXITY", "Must have android.permission.REQUEST_PASSWORD_COMPLEXITY permission.");
        synchronized (getLockObject()) {
            i = 0;
            int targetUserId = getCredentialOwner(callingUserId, false);
            PasswordMetrics metrics = getUserPasswordMetricsLocked(targetUserId);
            if (metrics != null) {
                i = metrics.determineComplexity();
            }
        }
        return i;
    }

    public int getCurrentFailedPasswordAttempts(int userHandle, boolean parent) {
        int i;
        if (!this.mLockPatternUtils.hasSecureLockScreen()) {
            return 0;
        }
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
        if (!this.mHasFeature || !this.mLockPatternUtils.hasSecureLockScreen()) {
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
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                if (who != null) {
                    admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                } else {
                    admin = getAdminWithMinimumFailedPasswordsForWipeLocked(userHandle, parent);
                }
                i = admin != null ? admin.maximumFailedPasswordsForWipe : 0;
            }
            return i;
        }
        return 0;
    }

    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle, boolean parent) {
        int identifier;
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
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
        if (!this.mLockPatternUtils.hasSecureLockScreen()) {
            Slog.w(LOG_TAG, "Cannot reset password when the device has no lock screen");
            return false;
        }
        int callingUid = this.mInjector.binderGetCallingUid();
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        String password = passwordOrNull != null ? passwordOrNull : "";
        if (TextUtils.isEmpty(password)) {
            enforceNotManagedProfile(userHandle, "clear the active password");
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminWithPolicyForUidLocked(null, -1, callingUid);
            if (admin != null) {
                if (!canPOorDOCallResetPassword(admin, userHandle)) {
                    throw new SecurityException("resetPassword() is deprecated for DPC targeting O or later");
                }
                if (getTargetSdk(admin.info.getPackageName(), userHandle) > 23) {
                    r6 = false;
                }
                preN = r6;
            } else {
                ActiveAdmin admin2 = getActiveAdminOrCheckPermissionForCallerLocked(null, 2, "android.permission.RESET_PASSWORD");
                preN = admin2 != null && getTargetSdk(admin2.info.getPackageName(), userHandle) <= 23;
                if (TextUtils.isEmpty(password)) {
                    if (!preN) {
                        throw new SecurityException("Cannot call with null password");
                    }
                    Slog.e(LOG_TAG, "Cannot call with null password");
                    return false;
                } else if (isLockScreenSecureUnchecked(userHandle)) {
                    if (!preN) {
                        throw new SecurityException("Cannot change current password");
                    }
                    Slog.e(LOG_TAG, "Cannot change current password");
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

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v13, types: [com.android.server.devicepolicy.DevicePolicyManagerService$Injector] */
    /* JADX WARN: Type inference failed for: r14v0 */
    /* JADX WARN: Type inference failed for: r14v1 */
    /* JADX WARN: Type inference failed for: r14v16 */
    /* JADX WARN: Type inference failed for: r14v17 */
    /* JADX WARN: Type inference failed for: r14v18 */
    /* JADX WARN: Type inference failed for: r14v2, types: [long] */
    /* JADX WARN: Type inference failed for: r14v7, types: [long] */
    /* JADX WARN: Type inference failed for: r3v6, types: [com.android.server.devicepolicy.DevicePolicyManagerService$Injector] */
    private boolean resetPasswordInternal(String password, long tokenHandle, byte[] token, int flags, int callingUid, int userHandle) {
        long ident;
        boolean result;
        boolean z;
        synchronized (getLockObject()) {
            int quality = getPasswordQuality(null, userHandle, false);
            if (quality == 524288) {
                quality = 0;
            }
            PasswordMetrics metrics = PasswordMetrics.computeForPassword(password.getBytes());
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
                if (metrics.symbols >= neededSymbols) {
                    int neededNonLetter = getPasswordMinimumNonLetter(null, userHandle, false);
                    if (metrics.nonLetter < neededNonLetter) {
                        Slog.w(LOG_TAG, "resetPassword: number of non-letter characters " + metrics.nonLetter + " does not meet required number of non-letter characters " + neededNonLetter);
                        return false;
                    }
                } else {
                    Slog.w(LOG_TAG, "resetPassword: number of special symbols " + metrics.symbols + " does not meet required number of special symbols " + neededSymbols);
                    return false;
                }
            }
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mPasswordOwner < 0 || policy.mPasswordOwner == callingUid) {
                long ident2 = 0;
                boolean callerIsDeviceOwnerAdmin = isCallerDeviceOwner(callingUid);
                boolean doNotAskCredentialsOnBoot = (flags & 2) != 0;
                if (callerIsDeviceOwnerAdmin && doNotAskCredentialsOnBoot) {
                    setDoNotAskCredentialsOnBoot();
                }
                long ident3 = this.mInjector.binderClearCallingIdentity();
                try {
                    if (token == null) {
                        try {
                            if (TextUtils.isEmpty(password)) {
                                ident = ident3;
                                this.mLockPatternUtils.clearLock((byte[]) null, userHandle, true);
                            } else {
                                ident = ident3;
                                this.mLockPatternUtils.saveLockPassword(password.getBytes(), (byte[]) null, quality2, userHandle, true);
                            }
                            result = true;
                            z = true;
                            ident2 = ident;
                        } catch (Throwable th) {
                            th = th;
                            ident2 = ident3;
                            this.mInjector.binderRestoreCallingIdentity(ident2);
                            throw th;
                        }
                    } else {
                        long j = ident3;
                        if (!TextUtils.isEmpty(password)) {
                            z = true;
                            result = this.mLockPatternUtils.setLockCredentialWithToken(password.getBytes(), 2, quality2, tokenHandle, token, userHandle);
                            ident2 = j;
                        } else {
                            z = true;
                            result = this.mLockPatternUtils.setLockCredentialWithToken((byte[]) null, -1, quality2, tokenHandle, token, userHandle);
                            ident2 = j;
                        }
                    }
                    if ((flags & 1) == 0) {
                        z = false;
                    }
                    boolean requireEntry = z;
                    if (requireEntry) {
                        this.mLockPatternUtils.requireStrongAuth(2, -1);
                    }
                    synchronized (getLockObject()) {
                        int newOwner = requireEntry ? callingUid : -1;
                        if (policy.mPasswordOwner != newOwner) {
                            policy.mPasswordOwner = newOwner;
                            saveSettingsLocked(userHandle);
                        }
                    }
                    this.mInjector.binderRestoreCallingIdentity(ident2);
                    return result;
                } catch (Throwable th2) {
                    th = th2;
                }
            } else {
                Slog.w(LOG_TAG, "resetPassword: already set by another uid and not entered by user");
                return false;
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
            getPowerManagerInternal().setMaximumScreenOffTimeoutFromDeviceAdmin(0, timeMs);
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
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            getPowerManagerInternal().setMaximumScreenOffTimeoutFromDeviceAdmin(userId, policy.mLastMaximumTimeToLock);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    public long getMaximumTimeToLock(ComponentName who, int userHandle, boolean parent) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
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
        long timeoutMs2;
        if (!this.mHasFeature || !this.mLockPatternUtils.hasSecureLockScreen()) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkArgument(timeoutMs >= 0, "Timeout must not be a negative number.");
        long minimumStrongAuthTimeout = getMinimumStrongAuthTimeoutMs();
        if (timeoutMs != 0 && timeoutMs < minimumStrongAuthTimeout) {
            timeoutMs = minimumStrongAuthTimeout;
        }
        if (timeoutMs <= 259200000) {
            timeoutMs2 = timeoutMs;
        } else {
            timeoutMs2 = 259200000;
        }
        int userHandle = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who, -1, parent);
            if (ap.strongAuthUnlockTimeout != timeoutMs2) {
                ap.strongAuthUnlockTimeout = timeoutMs2;
                saveSettingsLocked(userHandle);
            }
        }
    }

    public long getRequiredStrongAuthTimeout(ComponentName who, int userId, boolean parent) {
        if (!this.mHasFeature) {
            return 259200000L;
        }
        if (this.mLockPatternUtils.hasSecureLockScreen()) {
            enforceFullCrossUsersPermission(userId);
            synchronized (getLockObject()) {
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
            }
        }
        return 0L;
    }

    private long getMinimumStrongAuthTimeoutMs() {
        if (!this.mInjector.isBuildDebuggable()) {
            return MINIMUM_STRONG_AUTH_TIMEOUT_MS;
        }
        return Math.min(this.mInjector.systemPropertiesGetLong("persist.sys.min_str_auth_timeo", MINIMUM_STRONG_AUTH_TIMEOUT_MS), MINIMUM_STRONG_AUTH_TIMEOUT_MS);
    }

    /* JADX WARN: Removed duplicated region for block: B:36:0x0082 A[Catch: all -> 0x00cd, RemoteException -> 0x00d4, TryCatch #3 {RemoteException -> 0x00d4, all -> 0x00cd, blocks: (B:13:0x0033, B:15:0x0037, B:18:0x0045, B:20:0x004d, B:21:0x0053, B:22:0x005a, B:23:0x005b, B:24:0x0062, B:25:0x0063, B:26:0x006a, B:29:0x006e, B:34:0x0078, B:36:0x0082, B:38:0x009f, B:42:0x00a9, B:44:0x00af, B:37:0x0096, B:10:0x002a), top: B:59:0x002a, outer: #2 }] */
    /* JADX WARN: Removed duplicated region for block: B:37:0x0096 A[Catch: all -> 0x00cd, RemoteException -> 0x00d4, TryCatch #3 {RemoteException -> 0x00d4, all -> 0x00cd, blocks: (B:13:0x0033, B:15:0x0037, B:18:0x0045, B:20:0x004d, B:21:0x0053, B:22:0x005a, B:23:0x005b, B:24:0x0062, B:25:0x0063, B:26:0x006a, B:29:0x006e, B:34:0x0078, B:36:0x0082, B:38:0x009f, B:42:0x00a9, B:44:0x00af, B:37:0x0096, B:10:0x002a), top: B:59:0x002a, outer: #2 }] */
    /* JADX WARN: Removed duplicated region for block: B:42:0x00a9 A[Catch: all -> 0x00cd, RemoteException -> 0x00d4, TryCatch #3 {RemoteException -> 0x00d4, all -> 0x00cd, blocks: (B:13:0x0033, B:15:0x0037, B:18:0x0045, B:20:0x004d, B:21:0x0053, B:22:0x005a, B:23:0x005b, B:24:0x0062, B:25:0x0063, B:26:0x006a, B:29:0x006e, B:34:0x0078, B:36:0x0082, B:38:0x009f, B:42:0x00a9, B:44:0x00af, B:37:0x0096, B:10:0x002a), top: B:59:0x002a, outer: #2 }] */
    /* JADX WARN: Removed duplicated region for block: B:43:0x00ae  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void lockNow(int r18, boolean r19) {
        /*
            Method dump skipped, instructions count: 241
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.lockNow(int, boolean):void");
    }

    public void enforceCanManageCaCerts(ComponentName who, String callerPackage) {
        if (who == null) {
            if (!isCallerDelegate(callerPackage, this.mInjector.binderGetCallingUid(), "delegation-cert-install")) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_CA_CERTIFICATES", null);
                return;
            }
            return;
        }
        enforceProfileOrDeviceOwner(who);
    }

    private void enforceDeviceOwner(ComponentName who) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -2);
        }
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
                boolean isDelegate = admin == null;
                DevicePolicyEventLogger.createEvent(21).setAdmin(callerPackage).setBoolean(isDelegate).write();
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
            boolean isDelegate = admin == null;
            DevicePolicyEventLogger.createEvent(24).setAdmin(callerPackage).setBoolean(isDelegate).write();
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
        KeyChain.KeyChainConnection keyChainConnection;
        enforceCanManageScope(who, callerPackage, -1, "delegation-cert-install");
        int callingUid = this.mInjector.binderGetCallingUid();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                keyChainConnection = KeyChain.bindAsUser(this.mContext, UserHandle.getUserHandleForUid(callingUid));
            } catch (InterruptedException e) {
                e = e;
            } catch (Throwable th) {
                th = th;
            }
            try {
                try {
                    IKeyChainService keyChain = keyChainConnection.getService();
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
                            } catch (Throwable th2) {
                                th = th2;
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
                                boolean isDelegate = who == null;
                                DevicePolicyEventLogger.createEvent(20).setAdmin(callerPackage).setBoolean(isDelegate).write();
                                keyChainConnection.close();
                                this.mInjector.binderRestoreCallingIdentity(id);
                                return true;
                            } catch (Throwable th3) {
                                th = th3;
                                keyChainConnection.close();
                                throw th;
                            }
                        } catch (RemoteException e3) {
                            e = e3;
                            Log.e(LOG_TAG, "Installing certificate", e);
                            keyChainConnection.close();
                            this.mInjector.binderRestoreCallingIdentity(id);
                            return false;
                        }
                    } catch (RemoteException e4) {
                        e = e4;
                        Log.e(LOG_TAG, "Installing certificate", e);
                        keyChainConnection.close();
                        this.mInjector.binderRestoreCallingIdentity(id);
                        return false;
                    } catch (Throwable th4) {
                        th = th4;
                        keyChainConnection.close();
                        throw th;
                    }
                } catch (RemoteException e5) {
                    e = e5;
                } catch (Throwable th5) {
                    th = th5;
                }
            } catch (InterruptedException e6) {
                e = e6;
                Log.w(LOG_TAG, "Interrupted while installing certificate", e);
                Thread.currentThread().interrupt();
                this.mInjector.binderRestoreCallingIdentity(id);
                return false;
            }
        } catch (Throwable th6) {
            th = th6;
            this.mInjector.binderRestoreCallingIdentity(id);
            throw th;
        }
    }

    public boolean removeKeyPair(ComponentName who, String callerPackage, String alias) {
        enforceCanManageScope(who, callerPackage, -1, "delegation-cert-install");
        UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());
        long id = Binder.clearCallingIdentity();
        try {
            try {
                KeyChain.KeyChainConnection keyChainConnection = KeyChain.bindAsUser(this.mContext, userHandle);
                try {
                    try {
                        IKeyChainService keyChain = keyChainConnection.getService();
                        boolean result = keyChain.removeKeyPair(alias);
                        boolean isDelegate = who == null;
                        DevicePolicyEventLogger.createEvent(23).setAdmin(callerPackage).setBoolean(isDelegate).write();
                        return result;
                    } catch (RemoteException e) {
                        Log.e(LOG_TAG, "Removing keypair", e);
                        keyChainConnection.close();
                        return false;
                    }
                } finally {
                    keyChainConnection.close();
                }
            } catch (InterruptedException e2) {
                Log.w(LOG_TAG, "Interrupted while removing keypair", e2);
                Thread.currentThread().interrupt();
            }
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @VisibleForTesting
    public void enforceCallerCanRequestDeviceIdAttestation(ComponentName who, String callerPackage, int callerUid) throws SecurityException {
        int userId = UserHandle.getUserId(callerUid);
        if (hasProfileOwner(userId)) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-cert-install");
            if (canProfileOwnerAccessDeviceIds(userId)) {
                return;
            }
            throw new SecurityException("Profile Owner is not allowed to access Device IDs.");
        }
        enforceCanManageScope(who, callerPackage, -2, "delegation-cert-install");
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
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:46:0x00db
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public boolean generateKeyPair(android.content.ComponentName r24, java.lang.String r25, java.lang.String r26, android.security.keystore.ParcelableKeyGenParameterSpec r27, int r28, android.security.keymaster.KeymasterCertificateChain r29) {
        /*
            Method dump skipped, instructions count: 482
            To view this dump change 'Code comments level' option to 'DEBUG'
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

    public boolean setKeyPairCertificate(ComponentName who, String callerPackage, String alias, byte[] cert, byte[] chain, boolean isUserSelectable) {
        KeyChain.KeyChainConnection keyChainConnection;
        Throwable th;
        IKeyChainService keyChain;
        enforceCanManageScope(who, callerPackage, -1, "delegation-cert-install");
        int callingUid = this.mInjector.binderGetCallingUid();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                keyChainConnection = KeyChain.bindAsUser(this.mContext, UserHandle.getUserHandleForUid(callingUid));
            } catch (RemoteException e) {
                e = e;
            } catch (InterruptedException e2) {
                e = e2;
            } catch (Throwable th2) {
                th = th2;
            }
            try {
                try {
                    keyChain = keyChainConnection.getService();
                } catch (Throwable th3) {
                    th = th3;
                }
                try {
                    if (keyChain.setKeyPairCertificate(alias, cert, chain)) {
                        try {
                            keyChain.setUserSelectable(alias, isUserSelectable);
                            boolean isDelegate = who == null;
                            DevicePolicyEventLogger.createEvent(60).setAdmin(callerPackage).setBoolean(isDelegate).write();
                            $closeResource(null, keyChainConnection);
                            this.mInjector.binderRestoreCallingIdentity(id);
                            return true;
                        } catch (Throwable th4) {
                            th = th4;
                            th = th;
                            try {
                                throw th;
                            } catch (Throwable th5) {
                                if (keyChainConnection != null) {
                                    $closeResource(th, keyChainConnection);
                                }
                                throw th5;
                            }
                        }
                    }
                    try {
                        $closeResource(null, keyChainConnection);
                        this.mInjector.binderRestoreCallingIdentity(id);
                        return false;
                    } catch (RemoteException e3) {
                        e = e3;
                        Log.e(LOG_TAG, "Failed setting keypair certificate", e);
                        this.mInjector.binderRestoreCallingIdentity(id);
                        return false;
                    } catch (InterruptedException e4) {
                        e = e4;
                        Log.w(LOG_TAG, "Interrupted while setting keypair certificate", e);
                        Thread.currentThread().interrupt();
                        this.mInjector.binderRestoreCallingIdentity(id);
                        return false;
                    } catch (Throwable th6) {
                        th = th6;
                        this.mInjector.binderRestoreCallingIdentity(id);
                        throw th;
                    }
                } catch (Throwable th7) {
                    th = th7;
                    th = th;
                    throw th;
                }
            } catch (RemoteException e5) {
                e = e5;
                Log.e(LOG_TAG, "Failed setting keypair certificate", e);
                this.mInjector.binderRestoreCallingIdentity(id);
                return false;
            } catch (InterruptedException e6) {
                e = e6;
                Log.w(LOG_TAG, "Interrupted while setting keypair certificate", e);
                Thread.currentThread().interrupt();
                this.mInjector.binderRestoreCallingIdentity(id);
                return false;
            }
        } catch (Throwable th8) {
            th = th8;
            this.mInjector.binderRestoreCallingIdentity(id);
            throw th;
        }
    }

    public void choosePrivateKeyAlias(int uid, Uri uri, String alias, final IBinder response) {
        ComponentName aliasChooser;
        boolean isDelegate;
        long id;
        boolean isDelegate2;
        if (!isCallerWithSystemUid()) {
            return;
        }
        UserHandle caller = this.mInjector.binderGetCallingUserHandle();
        ComponentName aliasChooser2 = getProfileOwner(caller.getIdentifier());
        if (aliasChooser2 == null && caller.isSystem()) {
            synchronized (getLockObject()) {
                ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();
                if (deviceOwnerAdmin != null) {
                    aliasChooser2 = deviceOwnerAdmin.info.getComponent();
                }
            }
            aliasChooser = aliasChooser2;
        } else {
            aliasChooser = aliasChooser2;
        }
        if (aliasChooser == null) {
            sendPrivateKeyAliasResponse(null, response);
            return;
        }
        Intent intent = new Intent("android.app.action.CHOOSE_PRIVATE_KEY_ALIAS");
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_SENDER_UID", uid);
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_URI", uri);
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_ALIAS", alias);
        intent.putExtra("android.app.extra.CHOOSE_PRIVATE_KEY_RESPONSE", response);
        intent.addFlags(268435456);
        ComponentName delegateReceiver = resolveDelegateReceiver("delegation-cert-selection", "android.app.action.CHOOSE_PRIVATE_KEY_ALIAS", caller.getIdentifier());
        if (delegateReceiver != null) {
            intent.setComponent(delegateReceiver);
            isDelegate = true;
        } else {
            intent.setComponent(aliasChooser);
            isDelegate = false;
        }
        long id2 = this.mInjector.binderClearCallingIdentity();
        try {
            isDelegate2 = isDelegate;
            try {
                this.mContext.sendOrderedBroadcastAsUser(intent, caller, null, new BroadcastReceiver() { // from class: com.android.server.devicepolicy.DevicePolicyManagerService.6
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent2) {
                        String chosenAlias = getResultData();
                        DevicePolicyManagerService.this.sendPrivateKeyAliasResponse(chosenAlias, response);
                    }
                }, null, -1, null, null);
            } catch (Throwable th) {
                th = th;
                id = id2;
            }
        } catch (Throwable th2) {
            th = th2;
            id = id2;
        }
        try {
            DevicePolicyEventLogger.createEvent(22).setAdmin(intent.getComponent()).setBoolean(isDelegate2).write();
            this.mInjector.binderRestoreCallingIdentity(id2);
        } catch (Throwable th3) {
            th = th3;
            id = id2;
            this.mInjector.binderRestoreCallingIdentity(id);
            throw th;
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

    public void setDelegatedScopes(ComponentName who, String delegatePackage, List<String> scopeList) throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkStringNotEmpty(delegatePackage, "Delegate package is null or empty");
        Preconditions.checkCollectionElementsNotNull(scopeList, "Scopes");
        ArrayList<String> scopes = new ArrayList<>(new ArraySet(scopeList));
        if (scopes.retainAll(Arrays.asList(DELEGATIONS))) {
            throw new IllegalArgumentException("Unexpected delegation scopes");
        }
        boolean hasDoDelegation = !Collections.disjoint(scopes, DEVICE_OWNER_DELEGATIONS);
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            if (hasDoDelegation) {
                getActiveAdminForCallerLocked(who, -2);
            } else {
                getActiveAdminForCallerLocked(who, -1);
            }
            if (shouldCheckIfDelegatePackageIsInstalled(delegatePackage, getTargetSdk(who.getPackageName(), userId), scopes) && !isPackageInstalledForUser(delegatePackage, userId)) {
                throw new IllegalArgumentException("Package " + delegatePackage + " is not installed on the current user");
            }
            DevicePolicyData policy = getUserData(userId);
            List<String> exclusiveScopes = null;
            if (!scopes.isEmpty()) {
                policy.mDelegationMap.put(delegatePackage, new ArrayList(scopes));
                exclusiveScopes = new ArrayList<>(scopes);
                exclusiveScopes.retainAll(EXCLUSIVE_DELEGATIONS);
            } else {
                policy.mDelegationMap.remove(delegatePackage);
            }
            sendDelegationChangedBroadcast(delegatePackage, scopes, userId);
            if (exclusiveScopes != null && !exclusiveScopes.isEmpty()) {
                for (int i = policy.mDelegationMap.size() - 1; i >= 0; i--) {
                    String currentPackage = policy.mDelegationMap.keyAt(i);
                    List<String> currentScopes = policy.mDelegationMap.valueAt(i);
                    if (!currentPackage.equals(delegatePackage) && currentScopes.removeAll(exclusiveScopes)) {
                        if (currentScopes.isEmpty()) {
                            policy.mDelegationMap.removeAt(i);
                        }
                        sendDelegationChangedBroadcast(currentPackage, new ArrayList<>(currentScopes), userId);
                    }
                }
            }
            saveSettingsLocked(userId);
        }
    }

    private void sendDelegationChangedBroadcast(String delegatePackage, ArrayList<String> scopes, int userId) {
        Intent intent = new Intent("android.app.action.APPLICATION_DELEGATION_SCOPES_CHANGED");
        intent.addFlags(1073741824);
        intent.setPackage(delegatePackage);
        intent.putStringArrayListExtra("android.app.extra.DELEGATION_SCOPES", scopes);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
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
        List<String> delegatePackagesInternalLocked;
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(scope, "Scope is null");
        if (!Arrays.asList(DELEGATIONS).contains(scope)) {
            throw new IllegalArgumentException("Unexpected delegation scope: " + scope);
        }
        int userId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            delegatePackagesInternalLocked = getDelegatePackagesInternalLocked(scope, userId);
        }
        return delegatePackagesInternalLocked;
    }

    private List<String> getDelegatePackagesInternalLocked(String scope, int userId) {
        DevicePolicyData policy = getUserData(userId);
        List<String> delegatePackagesWithScope = new ArrayList<>();
        for (int i = 0; i < policy.mDelegationMap.size(); i++) {
            if (policy.mDelegationMap.valueAt(i).contains(scope)) {
                delegatePackagesWithScope.add(policy.mDelegationMap.keyAt(i));
            }
        }
        return delegatePackagesWithScope;
    }

    private ComponentName resolveDelegateReceiver(String scope, String action, int userId) {
        List<String> delegates;
        synchronized (getLockObject()) {
            delegates = getDelegatePackagesInternalLocked(scope, userId);
        }
        if (delegates.size() == 0) {
            return null;
        }
        if (delegates.size() > 1) {
            Slog.wtf(LOG_TAG, "More than one delegate holds " + scope);
            return null;
        }
        String pkg = delegates.get(0);
        Intent intent = new Intent(action);
        intent.setPackage(pkg);
        try {
            List<ResolveInfo> receivers = this.mIPackageManager.queryIntentReceivers(intent, (String) null, 0, userId).getList();
            int count = receivers.size();
            if (count >= 1) {
                if (count > 1) {
                    Slog.w(LOG_TAG, pkg + " defines more than one delegate receiver for " + action);
                }
                return receivers.get(0).activityInfo.getComponentName();
            }
            return null;
        } catch (RemoteException e) {
            return null;
        }
    }

    private boolean isCallerDelegate(String callerPackage, int callerUid, String scope) {
        Preconditions.checkNotNull(callerPackage, "callerPackage is null");
        if (!Arrays.asList(DELEGATIONS).contains(scope)) {
            throw new IllegalArgumentException("Unexpected delegation scope: " + scope);
        }
        int userId = UserHandle.getUserId(callerUid);
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userId);
            List<String> scopes = policy.mDelegationMap.get(callerPackage);
            if (scopes != null && scopes.contains(scope)) {
                try {
                    int uid = this.mInjector.getPackageManager().getPackageUidAsUser(callerPackage, userId);
                    return uid == callerUid;
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
            return false;
        }
    }

    private void enforceCanManageScope(ComponentName who, String callerPackage, int reqPolicy, String scope) {
        enforceCanManageScopeOrCheckPermission(who, callerPackage, reqPolicy, scope, null);
    }

    private void enforceCanManageScopeOrCheckPermission(ComponentName who, String callerPackage, int reqPolicy, String scope, String permission) {
        if (who != null) {
            synchronized (getLockObject()) {
                getActiveAdminForCallerLocked(who, reqPolicy);
            }
        } else if (isCallerDelegate(callerPackage, this.mInjector.binderGetCallingUid(), scope)) {
        } else {
            if (permission == null) {
                throw new SecurityException("Caller with uid " + this.mInjector.binderGetCallingUid() + " is not a delegate of scope " + scope + ".");
            }
            this.mContext.enforceCallingOrSelfPermission(permission, null);
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
        DevicePolicyEventLogger.createEvent(25).setAdmin(who).setStrings(new String[]{installerPackage}).write();
    }

    public String getCertInstallerPackage(ComponentName who) throws SecurityException {
        List<String> delegatePackages = getDelegatePackages(who, "delegation-cert-install");
        if (delegatePackages.size() > 0) {
            return delegatePackages.get(0);
        }
        return null;
    }

    public boolean setAlwaysOnVpnPackage(ComponentName admin, String vpnPackage, boolean lockdown, List<String> lockdownWhitelist) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);
        int userId = this.mInjector.userHandleGetCallingUserId();
        long token = this.mInjector.binderClearCallingIdentity();
        if (vpnPackage != null) {
            try {
                if (!isPackageInstalledForUser(vpnPackage, userId)) {
                    Slog.w(LOG_TAG, "Non-existent VPN package specified: " + vpnPackage);
                    throw new ServiceSpecificException(1, vpnPackage);
                }
            } catch (Throwable th) {
                this.mInjector.binderRestoreCallingIdentity(token);
                throw th;
            }
        }
        if (vpnPackage != null && lockdown && lockdownWhitelist != null) {
            for (String packageName : lockdownWhitelist) {
                if (!isPackageInstalledForUser(packageName, userId)) {
                    Slog.w(LOG_TAG, "Non-existent package in VPN whitelist: " + packageName);
                    throw new ServiceSpecificException(1, packageName);
                }
            }
        }
        if (!this.mInjector.getConnectivityManager().setAlwaysOnVpnPackageForUser(userId, vpnPackage, lockdown, lockdownWhitelist)) {
            throw new UnsupportedOperationException();
        }
        DevicePolicyEventLogger.createEvent(26).setAdmin(admin).setStrings(new String[]{vpnPackage}).setBoolean(lockdown).setInt(lockdownWhitelist != null ? lockdownWhitelist.size() : 0).write();
        this.mInjector.binderRestoreCallingIdentity(token);
        return true;
    }

    public String getAlwaysOnVpnPackage(ComponentName admin) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);
        int userId = this.mInjector.userHandleGetCallingUserId();
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mInjector.getConnectivityManager().getAlwaysOnVpnPackageForUser(userId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    public boolean isAlwaysOnVpnLockdownEnabled(ComponentName admin) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);
        int userId = this.mInjector.userHandleGetCallingUserId();
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mInjector.getConnectivityManager().isVpnLockdownEnabled(userId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(token);
        }
    }

    public List<String> getAlwaysOnVpnLockdownWhitelist(ComponentName admin) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);
        int userId = this.mInjector.userHandleGetCallingUserId();
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mInjector.getConnectivityManager().getVpnLockdownWhitelist(userId);
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

    private void forceWipeUser(int userId, String wipeReasonForUser, boolean wipeSilently) {
        try {
            IActivityManager am = this.mInjector.getIActivityManager();
            if (am.getCurrentUser().id == userId) {
                am.switchUser(0);
            }
            boolean success = this.mUserManagerInternal.removeUserEvenWhenDisallowed(userId);
            if (!success) {
                Slog.w(LOG_TAG, "Couldn't remove user " + userId);
            } else if (isManagedProfile(userId) && !wipeSilently) {
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
        DevicePolicyEventLogger.createEvent(11).setAdmin(admin.info.getComponent()).setInt(flags).write();
        String internalReason = "DevicePolicyManager.wipeDataWithReason() from " + admin.info.getComponent().flattenToShortString();
        wipeDataNoLock(admin.info.getComponent(), flags, internalReason, wipeReasonForUser, admin.getUserHandle().getIdentifier());
    }

    private void wipeDataNoLock(ComponentName admin, int flags, String internalReason, String wipeReasonForUser, int userId) {
        String restriction;
        wtfIfInLock();
        long ident = this.mInjector.binderClearCallingIdentity();
        if (userId == 0) {
            restriction = "no_factory_reset";
        } else {
            try {
                if (isManagedProfile(userId)) {
                    restriction = "no_remove_managed_profile";
                } else {
                    restriction = "no_remove_user";
                }
            } finally {
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
        if (isAdminAffectedByRestriction(admin, restriction, userId)) {
            throw new SecurityException("Cannot wipe data. " + restriction + " restriction is set for user " + userId);
        }
        if ((flags & 2) != 0) {
            if (!isDeviceOwner(admin, userId)) {
                throw new SecurityException("Only device owner admins can set WIPE_RESET_PROTECTION_DATA");
            }
            PersistentDataBlockManager manager = (PersistentDataBlockManager) this.mContext.getSystemService("persistent_data_block");
            if (manager != null) {
                manager.wipe();
            }
        }
        if (userId == 0) {
            forceWipeDeviceNoLock((flags & 1) != 0, internalReason, (flags & 4) != 0);
        } else {
            forceWipeUser(userId, wipeReasonForUser, (flags & 8) != 0);
        }
    }

    private void sendWipeProfileNotification(String wipeReasonForUser) {
        Notification notification = new Notification.Builder(this.mContext, SystemNotificationChannels.DEVICE_ADMIN).setSmallIcon(17301642).setContentTitle(this.mContext.getString(17041342)).setContentText(wipeReasonForUser).setColor(this.mContext.getColor(17170460)).setStyle(new Notification.BigTextStyle().bigText(wipeReasonForUser)).build();
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
        if (!this.mLockPatternUtils.hasSecureLockScreen()) {
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
        if (!this.mHasFeature || !this.mLockPatternUtils.hasSecureLockScreen()) {
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

    /* JADX WARN: Code restructure failed: missing block: B:23:0x005d, code lost:
        if (r13 == false) goto L31;
     */
    /* JADX WARN: Code restructure failed: missing block: B:24:0x005f, code lost:
        if (r14 == null) goto L31;
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x0061, code lost:
        r15 = r14.getUserHandle().getIdentifier();
        android.util.Slog.i(com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG, "Max failed password attempts policy reached for admin: " + r14.info.getComponent().flattenToShortString() + ". Calling wipeData for user " + r15);
     */
    /* JADX WARN: Code restructure failed: missing block: B:26:0x0091, code lost:
        r5 = r16.mContext.getString(17041345);
        wipeDataNoLock(r14.info.getComponent(), 0, "reportFailedPasswordAttempt()", r5, r15);
     */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00ab, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:29:0x00ac, code lost:
        android.util.Slog.w(com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG, "Failed to wipe user " + r15 + " after max failed password attempts reached.", r0);
     */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:36:0x00e9 -> B:37:0x00ea). Please submit an issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void reportFailedPasswordAttempt(int r17) {
        /*
            Method dump skipped, instructions count: 243
            To view this dump change 'Code comments level' option to 'DEBUG'
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

    public void reportFailedBiometricAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_DEVICE_ADMIN", null);
        if (this.mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(210007, new Object[]{0, 0});
        }
    }

    public void reportSuccessfulBiometricAttempt(int userHandle) {
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
        enforceDeviceOwner(who);
        long token = this.mInjector.binderClearCallingIdentity();
        try {
            this.mInjector.getConnectivityManager().setGlobalProxy(proxyInfo);
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
            exclusionList = "";
        }
        if (proxySpec == null) {
            proxySpec = "";
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
            }
        }
        return 0;
    }

    public boolean getStorageEncryption(ComponentName who, int userHandle) {
        if (this.mHasFeature) {
            enforceFullCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
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
        DevicePolicyEventLogger.createEvent(29).setAdmin(who).setBoolean(disabled).write();
    }

    public boolean getScreenCaptureDisabled(ComponentName who, int userHandle) {
        if (this.mHasFeature) {
            synchronized (getLockObject()) {
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
        DevicePolicyEventLogger.createEvent(36).setAdmin(who).setBoolean(required).write();
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
        }
        ensureAllUsersAffiliated();
    }

    private void ensureAllUsersAffiliated() throws SecurityException {
        synchronized (getLockObject()) {
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
                DevicePolicyEventLogger.createEvent(53).setAdmin(who).write();
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
        synchronized (getLockObject()) {
            deviceOwnerUserId = this.mOwners.getDeviceOwnerUserId();
        }
        ComponentName receiverComponent = null;
        if (action.equals("android.app.action.NETWORK_LOGS_AVAILABLE")) {
            receiverComponent = resolveDelegateReceiver("delegation-network-logging", action, deviceOwnerUserId);
        }
        if (receiverComponent == null) {
            synchronized (getLockObject()) {
                receiverComponent = this.mOwners.getDeviceOwnerComponent();
            }
        }
        sendActiveAdminCommand(action, extras, deviceOwnerUserId, receiverComponent);
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
            } catch (FileNotFoundException e) {
                Bundle extras = new Bundle();
                extras.putInt("android.app.extra.BUGREPORT_FAILURE_REASON", 1);
                sendDeviceOwnerCommand("android.app.action.BUGREPORT_FAILED", extras);
                if (0 != 0) {
                    try {
                        pfd.close();
                    } catch (IOException e2) {
                    }
                }
            }
            if (bugreportUriString == null) {
                throw new FileNotFoundException();
            }
            Uri bugreportUri = Uri.parse(bugreportUriString);
            ParcelFileDescriptor pfd2 = this.mContext.getContentResolver().openFileDescriptor(bugreportUri, ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD);
            synchronized (getLockObject()) {
                Intent intent = new Intent("android.app.action.BUGREPORT_SHARE");
                intent.setComponent(this.mOwners.getDeviceOwnerComponent());
                intent.setDataAndType(bugreportUri, "application/vnd.android.bugreport");
                intent.putExtra("android.app.extra.BUGREPORT_HASH", bugreportHash);
                intent.setFlags(1);
                ((UriGrantsManagerInternal) LocalServices.getService(UriGrantsManagerInternal.class)).grantUriPermissionFromIntent(2000, this.mOwners.getDeviceOwnerComponent().getPackageName(), intent, this.mOwners.getDeviceOwnerUserId());
                this.mContext.sendBroadcastAsUser(intent, UserHandle.of(this.mOwners.getDeviceOwnerUserId()));
            }
            if (pfd2 != null) {
                try {
                    pfd2.close();
                } catch (IOException e3) {
                }
            }
            this.mRemoteBugreportSharingAccepted.set(false);
            setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        } catch (Throwable th) {
            if (0 != 0) {
                try {
                    pfd.close();
                } catch (IOException e4) {
                }
            }
            this.mRemoteBugreportSharingAccepted.set(false);
            setDeviceOwnerRemoteBugreportUriAndHash(null, null);
            throw th;
        }
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
        DevicePolicyEventLogger.createEvent(30).setAdmin(who).setBoolean(disabled).write();
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
        DevicePolicyEventLogger.createEvent(9).setAdmin(who).setInt(which).setBoolean(parent).write();
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
                    int which = 0;
                    int N = admins.size();
                    for (int i = 0; i < N; i++) {
                        ActiveAdmin admin2 = admins.get(i);
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
        boolean isDelegate = who == null;
        DevicePolicyEventLogger.createEvent(61).setAdmin(callerPackage).setBoolean(isDelegate).setStrings((String[]) packageList.toArray(new String[0])).write();
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
                toggleBackupServiceActive(0, false);
                if (isAdb()) {
                    MetricsLogger.action(this.mContext, 617, LOG_TAG_DEVICE_OWNER);
                    DevicePolicyEventLogger.createEvent(82).setAdmin(admin).setStrings(new String[]{LOG_TAG_DEVICE_OWNER}).write();
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
                long ident = this.mInjector.binderClearCallingIdentity();
                sendOwnerChangedBroadcast("android.app.action.DEVICE_OWNER_CHANGED", userId);
                this.mInjector.binderRestoreCallingIdentity(ident);
                this.mDeviceAdminServiceController.startServiceForOwner(admin.getPackageName(), userId, "set-device-owner");
                Slog.i(LOG_TAG, "Device owner set: " + admin + " on user " + userId);
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

    private boolean hasProfileOwner(int userId) {
        boolean hasProfileOwner;
        synchronized (getLockObject()) {
            hasProfileOwner = this.mOwners.hasProfileOwner(userId);
        }
        return hasProfileOwner;
    }

    private boolean canProfileOwnerAccessDeviceIds(int userId) {
        boolean canProfileOwnerAccessDeviceIds;
        synchronized (getLockObject()) {
            canProfileOwnerAccessDeviceIds = this.mOwners.canProfileOwnerAccessDeviceIds(userId);
        }
        return canProfileOwnerAccessDeviceIds;
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
        toggleBackupServiceActive(0, true);
    }

    public boolean setProfileOwner(ComponentName who, String ownerName, int userHandle) {
        if (this.mHasFeature) {
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
                    MetricsLogger.action(this.mContext, 617, LOG_TAG_PROFILE_OWNER);
                    DevicePolicyEventLogger.createEvent(82).setAdmin(who).setStrings(new String[]{LOG_TAG_PROFILE_OWNER}).write();
                }
                toggleBackupServiceActive(userHandle, false);
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
        return false;
    }

    private void toggleBackupServiceActive(int userId, boolean makeActive) {
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                if (this.mInjector.getIBackupManager() != null) {
                    this.mInjector.getIBackupManager().setBackupServiceActive(userId, makeActive);
                }
            } catch (RemoteException e) {
                Object[] objArr = new Object[1];
                objArr[0] = makeActive ? "activating" : "deactivating";
                throw new IllegalStateException(String.format("Failed %s backup service.", objArr), e);
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(ident);
        }
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
        toggleBackupServiceActive(userId, true);
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
        DevicePolicyEventLogger.createEvent(42).setAdmin(who).write();
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
        if (currentState != 0) {
            if (currentState == 1 || currentState == 2) {
                if (newState == 3) {
                    return;
                }
            } else if (currentState == 4 && newState == 0) {
                return;
            }
        } else if (newState != 0) {
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
        enforceProfileOrDeviceOwner(who);
        int userId = UserHandle.getCallingUserId();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            this.mUserManager.setUserName(userId, profileName);
            DevicePolicyEventLogger.createEvent(40).setAdmin(who).write();
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public ComponentName getProfileOwnerAsUser(int userHandle) {
        enforceCrossUsersPermission(userHandle);
        return getProfileOwner(userHandle);
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

    public boolean checkDeviceIdentifierAccess(String packageName, int pid, int uid) {
        int callingUid = this.mInjector.binderGetCallingUid();
        int callingPid = this.mInjector.binderGetCallingPid();
        if (UserHandle.getAppId(callingUid) >= 10000 && (callingUid != uid || callingPid != pid)) {
            String message = String.format("Calling uid %d, pid %d cannot check device identifier access for package %s (uid=%d, pid=%d)", Integer.valueOf(callingUid), Integer.valueOf(callingPid), packageName, Integer.valueOf(uid), Integer.valueOf(pid));
            Log.w(LOG_TAG, message);
            throw new SecurityException(message);
        }
        int userId = UserHandle.getUserId(uid);
        try {
            ApplicationInfo appInfo = this.mIPackageManager.getApplicationInfo(packageName, 0, userId);
            if (appInfo == null) {
                Log.w(LOG_TAG, String.format("appInfo could not be found for package %s", packageName));
                return false;
            } else if (uid != appInfo.uid) {
                String message2 = String.format("Package %s (uid=%d) does not match provided uid %d", packageName, Integer.valueOf(appInfo.uid), Integer.valueOf(uid));
                Log.w(LOG_TAG, message2);
                throw new SecurityException(message2);
            } else if (this.mContext.checkPermission("android.permission.READ_PHONE_STATE", pid, uid) != 0) {
                return false;
            } else {
                ComponentName deviceOwner = getDeviceOwnerComponent(true);
                if (deviceOwner == null || !(deviceOwner.getPackageName().equals(packageName) || isCallerDelegate(packageName, uid, "delegation-cert-install"))) {
                    ComponentName profileOwner = getProfileOwnerAsUser(userId);
                    if (profileOwner == null || !(profileOwner.getPackageName().equals(packageName) || isCallerDelegate(packageName, uid, "delegation-cert-install"))) {
                        Log.w(LOG_TAG, String.format("Package %s (uid=%d, pid=%d) cannot access Device IDs", packageName, Integer.valueOf(uid), Integer.valueOf(pid)));
                        return false;
                    }
                    return true;
                }
                return true;
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Exception caught obtaining appInfo for package " + packageName, e);
            return false;
        }
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
            if (this.mIsWatch || hasUserSetupCompleted(userHandle)) {
                if (!isCallerWithSystemUid()) {
                    throw new IllegalStateException("Cannot set the profile owner on a user which is already set-up");
                }
                if (!this.mIsWatch) {
                    String supervisor = this.mContext.getResources().getString(17039712);
                    if (supervisor == null) {
                        throw new IllegalStateException("Unable to set profile owner post-setup, nodefault supervisor profile owner defined");
                    }
                    ComponentName supervisorComponent = ComponentName.unflattenFromString(supervisor);
                    if (!owner.equals(supervisorComponent)) {
                        throw new IllegalStateException("Unable to set non-default profile owner post-setup " + owner);
                    }
                }
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
            } finally {
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }
        return this.mUserManager.getCredentialOwnerProfile(userHandle);
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
                pw.println();
                this.mPolicyCache.dump("  ", pw);
            }
        }
    }

    private String getEncryptionStatusName(int encryptionStatus) {
        if (encryptionStatus != 0) {
            if (encryptionStatus != 1) {
                if (encryptionStatus != 2) {
                    if (encryptionStatus != 3) {
                        if (encryptionStatus != 4) {
                            if (encryptionStatus == 5) {
                                return "per-user";
                            }
                            return UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
                        }
                        return "block default key";
                    }
                    return "block";
                }
                return "activating";
            }
            return "inactive";
        }
        return "unsupported";
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
            }
            injector.binderRestoreCallingIdentity(id);
        }
        String activityPackage = activity != null ? activity.getPackageName() : null;
        DevicePolicyEventLogger.createEvent(52).setAdmin(who).setStrings(activityPackage, getIntentFilterActions(filter)).write();
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

    public void setDefaultSmsApplication(ComponentName admin, final String packageName) {
        Preconditions.checkNotNull(admin, "ComponentName is null");
        enforceDeviceOwner(admin);
        this.mInjector.binderWithCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$dDeS1FUetDCbtT673Qp0Hcsm5Vw
            public final void runOrThrow() {
                DevicePolicyManagerService.this.lambda$setDefaultSmsApplication$9$DevicePolicyManagerService(packageName);
            }
        });
    }

    public /* synthetic */ void lambda$setDefaultSmsApplication$9$DevicePolicyManagerService(String packageName) throws Exception {
        SmsApplication.setDefaultApplication(packageName, this.mContext);
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
        return isCallerDelegate(callerPackage, this.mInjector.binderGetCallingUid(), "delegation-app-restrictions");
    }

    public void setApplicationRestrictions(ComponentName who, String callerPackage, String packageName, Bundle settings) {
        enforceCanManageScope(who, callerPackage, -1, "delegation-app-restrictions");
        UserHandle userHandle = this.mInjector.binderGetCallingUserHandle();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            this.mUserManager.setApplicationRestrictions(packageName, settings, userHandle);
            boolean isDelegate = who == null;
            DevicePolicyEventLogger.createEvent(62).setAdmin(callerPackage).setBoolean(isDelegate).setStrings(new String[]{packageName}).write();
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public void setTrustAgentConfiguration(ComponentName admin, ComponentName agent, PersistableBundle args, boolean parent) {
        if (!this.mHasFeature || !this.mLockPatternUtils.hasSecureLockScreen()) {
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
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
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
                List<PersistableBundle> result2 = null;
                List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                boolean allAdminsHaveOptions = true;
                int N = admins.size();
                int i = 0;
                while (true) {
                    if (i >= N) {
                        break;
                    }
                    ActiveAdmin active = admins.get(i);
                    boolean disablesTrust = (active.disabledKeyguardFeatures & 16) != 0;
                    ActiveAdmin.TrustAgentInfo info = active.trustAgentInfos.get(componentName2);
                    if (info == null || info.options == null || info.options.isEmpty()) {
                        componentName = componentName2;
                        if (disablesTrust) {
                            allAdminsHaveOptions = false;
                            break;
                        }
                    } else if (disablesTrust) {
                        if (result2 == null) {
                            result2 = new ArrayList<>();
                        }
                        result2.add(info.options);
                        componentName = componentName2;
                    } else {
                        componentName = componentName2;
                        Log.w(LOG_TAG, "Ignoring admin " + active.info + " because it has trust options but doesn't declare KEYGUARD_DISABLE_TRUST_AGENTS");
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
            DevicePolicyEventLogger.createEvent(48).setAdmin(who).setStrings(getIntentFilterActions(filter)).setInt(flags).write();
        }
    }

    private static String[] getIntentFilterActions(IntentFilter filter) {
        if (filter == null) {
            return null;
        }
        int actionsCount = filter.countActions();
        String[] actions = new String[actionsCount];
        for (int i = 0; i < actionsCount; i++) {
            actions[i] = filter.getAction(i);
        }
        return actions;
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
            String[] packageArray = packageList != null ? (String[]) packageList.toArray(new String[0]) : null;
            DevicePolicyEventLogger.createEvent(28).setAdmin(who).setStrings(packageArray).write();
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
            result = null;
            int[] profileIds = this.mUserManager.getProfileIdsWithDisabled(userId);
            for (int profileId : profileIds) {
                DevicePolicyData policy = getUserDataUnchecked(profileId);
                int N = policy.mAdminList.size();
                for (int j = 0; j < N; j++) {
                    ActiveAdmin admin = policy.mAdminList.get(j);
                    List<String> fromAdmin = admin.permittedAccessiblityServices;
                    if (fromAdmin != null) {
                        if (result == null) {
                            result = new ArrayList<>(fromAdmin);
                        } else {
                            result.retainAll(fromAdmin);
                        }
                    }
                }
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
        List<InputMethodInfo> enabledImes;
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            if (InputMethodSystemProperty.PER_PROFILE_IME_ENABLED || checkCallerIsCurrentUserOrProfile()) {
                int callingUserId = this.mInjector.userHandleGetCallingUserId();
                if (packageList != null && (enabledImes = InputMethodManagerInternal.get().getEnabledInputMethodListAsUser(callingUserId)) != null) {
                    List<String> enabledPackages = new ArrayList<>();
                    for (InputMethodInfo ime : enabledImes) {
                        enabledPackages.add(ime.getPackageName());
                    }
                    if (!checkPackagesInPermittedListOrSystem(enabledPackages, packageList, callingUserId)) {
                        Slog.e(LOG_TAG, "Cannot set permitted input methods, because it contains already enabled input method.");
                        return false;
                    }
                }
                synchronized (getLockObject()) {
                    ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
                    admin.permittedInputMethods = packageList;
                    saveSettingsLocked(callingUserId);
                }
                String[] packageArray = packageList != null ? (String[]) packageList.toArray(new String[0]) : null;
                DevicePolicyEventLogger.createEvent(27).setAdmin(who).setStrings(packageArray).write();
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
        List<InputMethodInfo> imes;
        enforceManageUsers();
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            result = null;
            int[] profileIds = InputMethodSystemProperty.PER_PROFILE_IME_ENABLED ? new int[]{callingUserId} : this.mUserManager.getProfileIdsWithDisabled(callingUserId);
            for (int profileId : profileIds) {
                DevicePolicyData policy = getUserDataUnchecked(profileId);
                int N = policy.mAdminList.size();
                for (int j = 0; j < N; j++) {
                    ActiveAdmin admin = policy.mAdminList.get(j);
                    List<String> fromAdmin = admin.permittedInputMethods;
                    if (fromAdmin != null) {
                        if (result == null) {
                            result = new ArrayList<>(fromAdmin);
                        } else {
                            result.retainAll(fromAdmin);
                        }
                    }
                }
            }
            if (result != null && (imes = InputMethodManagerInternal.get().getInputMethodListAsUser(callingUserId)) != null) {
                for (InputMethodInfo ime : imes) {
                    ServiceInfo serviceInfo = ime.getServiceInfo();
                    ApplicationInfo applicationInfo = serviceInfo.applicationInfo;
                    if ((applicationInfo.flags & 1) != 0) {
                        result.add(serviceInfo.packageName);
                    }
                }
            }
        }
        return result;
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

    /* JADX WARN: Removed duplicated region for block: B:104:0x01bd A[DONT_GENERATE] */
    /* JADX WARN: Removed duplicated region for block: B:106:0x01c3 A[Catch: all -> 0x01ce, TRY_ENTER, TryCatch #10 {all -> 0x01ce, blocks: (B:101:0x01b3, B:106:0x01c3, B:107:0x01cd), top: B:133:0x01b3 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public android.os.UserHandle createAndManageUser(android.content.ComponentName r25, java.lang.String r26, android.content.ComponentName r27, android.os.PersistableBundle r28, int r29) {
        /*
            Method dump skipped, instructions count: 530
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.createAndManageUser(android.content.ComponentName, java.lang.String, android.content.ComponentName, android.os.PersistableBundle, int):android.os.UserHandle");
    }

    public boolean removeUser(ComponentName who, UserHandle userHandle) {
        String restriction;
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(userHandle, "UserHandle is null");
        enforceDeviceOwner(who);
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
        enforceDeviceOwner(who);
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
        enforceDeviceOwner(who);
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
        enforceDeviceOwner(who);
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
        enforceProfileOrDeviceOwner(who);
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            return this.mInjector.getUserManager().isUserEphemeral(callingUserId);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public Bundle getApplicationRestrictions(ComponentName who, String callerPackage, String packageName) {
        enforceCanManageScope(who, callerPackage, -1, "delegation-app-restrictions");
        UserHandle userHandle = this.mInjector.binderGetCallingUserHandle();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            Bundle bundle = this.mUserManager.getApplicationRestrictions(packageName, userHandle);
            return bundle != null ? bundle : Bundle.EMPTY;
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:25:0x0059  */
    /* JADX WARN: Removed duplicated region for block: B:26:0x005b  */
    /* JADX WARN: Removed duplicated region for block: B:29:0x0073 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:30:0x0074 A[RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public java.lang.String[] setPackagesSuspended(android.content.ComponentName r19, java.lang.String r20, java.lang.String[] r21, boolean r22) {
        /*
            r18 = this;
            r1 = r18
            r2 = r19
            r3 = r20
            r12 = r21
            int r13 = android.os.UserHandle.getCallingUserId()
            r14 = 0
            java.lang.Object r15 = r18.getLockObject()
            monitor-enter(r15)
            r0 = -1
            java.lang.String r4 = "delegation-package-access"
            r1.enforceCanManageScope(r2, r3, r0, r4)     // Catch: java.lang.Throwable -> L7c
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r1.mInjector     // Catch: java.lang.Throwable -> L7c
            long r4 = r0.binderClearCallingIdentity()     // Catch: java.lang.Throwable -> L7c
            r10 = r4
            android.content.pm.IPackageManager r4 = r1.mIPackageManager     // Catch: java.lang.Throwable -> L43 android.os.RemoteException -> L46
            r7 = 0
            r8 = 0
            r9 = 0
            java.lang.String r0 = "android"
            r5 = r21
            r6 = r22
            r16 = r10
            r10 = r0
            r11 = r13
            java.lang.String[] r0 = r4.setPackagesSuspendedAsUser(r5, r6, r7, r8, r9, r10, r11)     // Catch: java.lang.Throwable -> L3b android.os.RemoteException -> L3f
            r14 = r0
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r1.mInjector     // Catch: java.lang.Throwable -> L7c
            r4 = r16
            r0.binderRestoreCallingIdentity(r4)     // Catch: java.lang.Throwable -> L7c
        L3a:
            goto L56
        L3b:
            r0 = move-exception
            r4 = r16
            goto L76
        L3f:
            r0 = move-exception
            r4 = r16
            goto L48
        L43:
            r0 = move-exception
            r4 = r10
            goto L76
        L46:
            r0 = move-exception
            r4 = r10
        L48:
            java.lang.String r6 = "DevicePolicyManager"
            java.lang.String r7 = "Failed talking to the package manager"
            android.util.Slog.e(r6, r7, r0)     // Catch: java.lang.Throwable -> L75
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r0 = r1.mInjector     // Catch: java.lang.Throwable -> L7c
            r0.binderRestoreCallingIdentity(r4)     // Catch: java.lang.Throwable -> L7c
            goto L3a
        L56:
            monitor-exit(r15)     // Catch: java.lang.Throwable -> L7c
            if (r2 != 0) goto L5b
            r0 = 1
            goto L5c
        L5b:
            r0 = 0
        L5c:
            r4 = 68
            android.app.admin.DevicePolicyEventLogger r4 = android.app.admin.DevicePolicyEventLogger.createEvent(r4)
            android.app.admin.DevicePolicyEventLogger r4 = r4.setAdmin(r3)
            android.app.admin.DevicePolicyEventLogger r4 = r4.setBoolean(r0)
            android.app.admin.DevicePolicyEventLogger r4 = r4.setStrings(r12)
            r4.write()
            if (r14 == 0) goto L74
            return r14
        L74:
            return r12
        L75:
            r0 = move-exception
        L76:
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r6 = r1.mInjector     // Catch: java.lang.Throwable -> L7c
            r6.binderRestoreCallingIdentity(r4)     // Catch: java.lang.Throwable -> L7c
            throw r0     // Catch: java.lang.Throwable -> L7c
        L7c:
            r0 = move-exception
            monitor-exit(r15)     // Catch: java.lang.Throwable -> L7c
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
        int eventId;
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
        if (enabledFromThisOwner) {
            eventId = 12;
        } else {
            eventId = 13;
        }
        DevicePolicyEventLogger.createEvent(eventId).setAdmin(who).setStrings(new String[]{key}).write();
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
            int cameraRestrictionScope = getCameraRestrictionScopeLocked(userId, disallowCameraGlobally);
            this.mUserManagerInternal.setDevicePolicyUserRestrictions(userId, userRestrictions, isDeviceOwner, cameraRestrictionScope);
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
        Injector injector;
        int callingUserId = UserHandle.getCallingUserId();
        boolean result = false;
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-package-access");
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                result = this.mIPackageManager.setApplicationHiddenSettingAsUser(packageName, hidden, callingUserId);
                injector = this.mInjector;
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to setApplicationHiddenSetting", re);
                injector = this.mInjector;
            }
            injector.binderRestoreCallingIdentity(id);
        }
        boolean isDelegate = who == null;
        DevicePolicyEventLogger devicePolicyEventLogger = DevicePolicyEventLogger.createEvent(63).setAdmin(callerPackage).setBoolean(isDelegate);
        String[] strArr = new String[2];
        strArr[0] = packageName;
        strArr[1] = hidden ? "hidden" : "not_hidden";
        devicePolicyEventLogger.setStrings(strArr).write();
        return result;
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
                this.mIPackageManager.installExistingPackageAsUser(packageName, userId, (int) DumpState.DUMP_CHANGES, 1, (List) null);
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
        boolean isDelegate = who == null;
        DevicePolicyEventLogger.createEvent(64).setAdmin(callerPackage).setBoolean(isDelegate).setStrings(new String[]{packageName}).write();
    }

    public int enableSystemAppWithIntent(ComponentName who, String callerPackage, Intent intent) {
        int numberOfAppsInstalled = 0;
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-enable-system-app");
            int userId = UserHandle.getCallingUserId();
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                int parentUserId = getProfileParentId(userId);
                List<ResolveInfo> activitiesToEnable = this.mIPackageManager.queryIntentActivities(intent, intent.resolveTypeIfNeeded(this.mContext.getContentResolver()), 786432, parentUserId).getList();
                if (activitiesToEnable != null) {
                    for (ResolveInfo info : activitiesToEnable) {
                        if (info.activityInfo != null) {
                            String packageName = info.activityInfo.packageName;
                            if (isSystemApp(this.mIPackageManager, packageName, parentUserId)) {
                                numberOfAppsInstalled++;
                                this.mIPackageManager.installExistingPackageAsUser(packageName, userId, (int) DumpState.DUMP_CHANGES, 1, (List) null);
                            } else {
                                Slog.d(LOG_TAG, "Not enabling " + packageName + " since is not a system app");
                            }
                        }
                    }
                }
                this.mInjector.binderRestoreCallingIdentity(id);
            } catch (RemoteException e) {
                Slog.wtf(LOG_TAG, "Failed to resolve intent for: " + intent);
                this.mInjector.binderRestoreCallingIdentity(id);
                return 0;
            }
        }
        boolean isDelegate = who == null;
        DevicePolicyEventLogger.createEvent(65).setAdmin(callerPackage).setBoolean(isDelegate).setStrings(new String[]{intent.getAction()}).write();
        return numberOfAppsInstalled;
    }

    private boolean isSystemApp(IPackageManager pm, String packageName, int userId) throws RemoteException {
        ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 8192, userId);
        if (appInfo != null) {
            return (appInfo.flags & 1) != 0;
        }
        throw new IllegalArgumentException("The application " + packageName + " is not present on this device");
    }

    public boolean installExistingPackage(ComponentName who, String callerPackage, String packageName) {
        boolean result;
        synchronized (getLockObject()) {
            enforceCanManageScope(who, callerPackage, -1, "delegation-install-existing-package");
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            if (!isUserAffiliatedWithDeviceLocked(callingUserId)) {
                throw new SecurityException("Admin " + who + " is neither the device owner or affiliated user's profile owner.");
            }
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                result = this.mIPackageManager.installExistingPackageAsUser(packageName, callingUserId, (int) DumpState.DUMP_CHANGES, 1, (List) null) == 1;
            } catch (RemoteException e) {
                return false;
            } finally {
                this.mInjector.binderRestoreCallingIdentity(id);
            }
        }
        if (result) {
            boolean isDelegate = who == null;
            DevicePolicyEventLogger.createEvent(66).setAdmin(callerPackage).setBoolean(isDelegate).setStrings(new String[]{packageName}).write();
        }
        return result;
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
        boolean isDelegate = who == null;
        DevicePolicyEventLogger.createEvent(67).setAdmin(callerPackage).setBoolean(isDelegate).setStrings(new String[]{packageName}).write();
    }

    public boolean isUninstallBlocked(ComponentName who, String packageName) {
        boolean blockUninstallForUser;
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            if (who != null) {
                getActiveAdminForCallerLocked(who, -1);
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
        DevicePolicyEventLogger.createEvent(46).setAdmin(who).setBoolean(disabled).write();
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
        DevicePolicyEventLogger.createEvent(45).setAdmin(who).setBoolean(disabled).write();
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
        DevicePolicyEventLogger.createEvent(47).setAdmin(who).setBoolean(disabled).write();
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
        boolean z = true;
        boolean hasHome = (flags & 4) != 0;
        boolean hasOverview = (flags & 8) != 0;
        Preconditions.checkArgument(hasHome || !hasOverview, "Cannot use LOCK_TASK_FEATURE_OVERVIEW without LOCK_TASK_FEATURE_HOME");
        boolean hasNotification = (flags & 2) != 0;
        if (!hasHome && hasNotification) {
            z = false;
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
            for (int i = userInfos.size() - 1; i >= 0; i--) {
                int userId = userInfos.get(i).id;
                if (!canUserUseLockTaskLocked(userId)) {
                    List<String> lockTaskPackages = getUserData(userId).mLockTaskPackages;
                    if (!lockTaskPackages.isEmpty()) {
                        Slog.d(LOG_TAG, "User id " + userId + " not affiliated. Clearing lock task packages");
                        setLockTaskPackagesLocked(userId, Collections.emptyList());
                    }
                    int lockTaskFeatures = getUserData(userId).mLockTaskFeatures;
                    if (lockTaskFeatures != 0) {
                        Slog.d(LOG_TAG, "User id " + userId + " not affiliated. Clearing lock task features");
                        setLockTaskFeaturesLocked(userId, 0);
                    }
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
                    DevicePolicyEventLogger.createEvent(51).setAdmin(admin.info.getPackageName()).setBoolean(isEnabled).setStrings(new String[]{pkg}).write();
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
                    DevicePolicyManagerService.this.lambda$setSystemSetting$10$DevicePolicyManagerService(setting, value, callingUserId);
                }
            });
        }
    }

    public /* synthetic */ void lambda$setSystemSetting$10$DevicePolicyManagerService(String setting, String value, int callingUserId) throws Exception {
        this.mInjector.settingsSystemPutStringForUser(setting, value, callingUserId);
    }

    public boolean setTime(ComponentName who, final long millis) {
        Preconditions.checkNotNull(who, "ComponentName is null in setTime");
        enforceDeviceOwner(who);
        if (this.mInjector.settingsGlobalGetInt("auto_time", 0) == 1) {
            return false;
        }
        this.mInjector.binderWithCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$zqf4q6-7wkQreppEUOBfp0NE94M
            public final void runOrThrow() {
                DevicePolicyManagerService.this.lambda$setTime$11$DevicePolicyManagerService(millis);
            }
        });
        return true;
    }

    public /* synthetic */ void lambda$setTime$11$DevicePolicyManagerService(long millis) throws Exception {
        this.mInjector.getAlarmManager().setTime(millis);
    }

    public boolean setTimeZone(ComponentName who, final String timeZone) {
        Preconditions.checkNotNull(who, "ComponentName is null in setTimeZone");
        enforceDeviceOwner(who);
        if (this.mInjector.settingsGlobalGetInt("auto_time_zone", 0) == 1) {
            return false;
        }
        this.mInjector.binderWithCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.devicepolicy.-$$Lambda$DevicePolicyManagerService$1qc4cD7h8K2CVmZeyPCWra8TVtQ
            public final void runOrThrow() {
                DevicePolicyManagerService.this.lambda$setTimeZone$12$DevicePolicyManagerService(timeZone);
            }
        });
        return true;
    }

    public /* synthetic */ void lambda$setTimeZone$12$DevicePolicyManagerService(String timeZone) throws Exception {
        this.mInjector.getAlarmManager().setTimeZone(timeZone);
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
                    throw new UnsupportedOperationException("install_non_market_apps is deprecated. Please use one of the user restrictions no_install_unknown_sources or no_install_unknown_sources_globally instead.");
                }
                if (!this.mUserManager.isManagedProfile(callingUserId)) {
                    Slog.e(LOG_TAG, "Ignoring setSecureSetting request for " + setting + ". User restriction no_install_unknown_sources or no_install_unknown_sources_globally should be used instead.");
                } else {
                    try {
                        setUserRestriction(who, "no_install_unknown_sources", Integer.parseInt(value) == 0);
                        DevicePolicyEventLogger.createEvent(14).setAdmin(who).setStrings(new String[]{setting, value}).write();
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
            DevicePolicyEventLogger.createEvent(14).setAdmin(who).setStrings(new String[]{setting, value}).write();
        }
    }

    public void setMasterVolumeMuted(ComponentName who, boolean on) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, -1);
            setUserRestriction(who, "disallow_unmute_device", on);
            DevicePolicyEventLogger.createEvent(35).setAdmin(who).setBoolean(on).write();
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
        DevicePolicyEventLogger.createEvent(41).setAdmin(who).write();
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
        if (disabled) {
            this.mInjector.getIWindowManager().dismissKeyguard((IKeyguardDismissCallback) null, (CharSequence) null);
        }
        DevicePolicyEventLogger.createEvent(37).setAdmin(who).setBoolean(disabled).write();
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
                        isLockTaskMode = this.mInjector.getIActivityTaskManager().getLockTaskModeState() != 0;
                    } catch (RemoteException e) {
                        Slog.e(LOG_TAG, "Failed to get LockTask mode");
                    }
                    if (!isLockTaskMode && !setStatusBarDisabledInternal(disabled, userId)) {
                        return false;
                    }
                    policy.mStatusBarDisabled = disabled;
                    saveSettingsLocked(userId);
                }
                DevicePolicyEventLogger.createEvent(38).setAdmin(who).setBoolean(disabled).write();
                return true;
            }
        }
    }

    private boolean setStatusBarDisabledInternal(boolean disabled, int userId) {
        int flags2;
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                IStatusBarService statusBarService = IStatusBarService.Stub.asInterface(ServiceManager.checkService(TAG_STATUS_BAR));
                if (statusBarService != null) {
                    int flags1 = disabled ? STATUS_BAR_DISABLE_MASK : 0;
                    if (!disabled) {
                        flags2 = 0;
                    } else {
                        flags2 = 1;
                    }
                    statusBarService.disableForUser(flags1, this.mToken, this.mContext.getPackageName(), userId);
                    statusBarService.disable2ForUser(flags2, this.mToken, this.mContext.getPackageName(), userId);
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
        @GuardedBy({"getLockObject()"})
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
        @GuardedBy({"getLockObject()"})
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

    /* loaded from: classes.dex */
    private class DevicePolicyConstantsObserver extends ContentObserver {
        final Uri mConstantsUri;

        DevicePolicyConstantsObserver(Handler handler) {
            super(handler);
            this.mConstantsUri = Settings.Global.getUriFor("device_policy_constants");
        }

        void register() {
            DevicePolicyManagerService.this.mInjector.registerContentObserver(this.mConstantsUri, false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            DevicePolicyManagerService devicePolicyManagerService = DevicePolicyManagerService.this;
            devicePolicyManagerService.mConstants = devicePolicyManagerService.loadConstants();
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
            long ident = DevicePolicyManagerService.this.mInjector.binderClearCallingIdentity();
            try {
                List<UserManager.EnforcingUser> sources = DevicePolicyManagerService.this.mUserManager.getUserRestrictionSources(userRestriction, UserHandle.of(userId));
                if (sources != null && !sources.isEmpty()) {
                    if (sources.size() > 1) {
                        return DevicePolicyManagerService.this.createShowAdminSupportIntent(null, userId);
                    }
                    UserManager.EnforcingUser enforcingUser = sources.get(0);
                    int sourceType = enforcingUser.getUserRestrictionSource();
                    int enforcingUserId = enforcingUser.getUserHandle().getIdentifier();
                    if (sourceType == 4) {
                        ComponentName profileOwner = DevicePolicyManagerService.this.mOwners.getProfileOwnerComponent(enforcingUserId);
                        if (profileOwner != null) {
                            return DevicePolicyManagerService.this.createShowAdminSupportIntent(profileOwner, enforcingUserId);
                        }
                    } else if (sourceType == 2) {
                        Pair<Integer, ComponentName> deviceOwner = DevicePolicyManagerService.this.mOwners.getDeviceOwnerUserIdAndComponent();
                        if (deviceOwner != null) {
                            return DevicePolicyManagerService.this.createShowAdminSupportIntent((ComponentName) deviceOwner.second, ((Integer) deviceOwner.first).intValue());
                        }
                    } else if (sourceType == 1) {
                        return null;
                    }
                    return null;
                }
                return null;
            } finally {
                DevicePolicyManagerService.this.mInjector.binderRestoreCallingIdentity(ident);
            }
        }

        public boolean isUserAffiliatedWithDevice(int userId) {
            return DevicePolicyManagerService.this.isUserAffiliatedWithDeviceLocked(userId);
        }

        public boolean canSilentlyInstallPackage(String callerPackage, int callerUid) {
            if (callerPackage == null || !isUserAffiliatedWithDevice(UserHandle.getUserId(callerUid)) || !isActiveAdminWithPolicy(callerUid, -1)) {
                return false;
            }
            return true;
        }

        public void reportSeparateProfileChallengeChanged(int userId) {
            long ident = DevicePolicyManagerService.this.mInjector.binderClearCallingIdentity();
            try {
                synchronized (DevicePolicyManagerService.this.getLockObject()) {
                    DevicePolicyManagerService.this.updateMaximumTimeToLockLocked(userId);
                    DevicePolicyManagerService.this.updatePasswordQualityCacheForUserGroup(userId);
                }
                DevicePolicyManagerService.this.mInjector.binderRestoreCallingIdentity(ident);
                DevicePolicyEventLogger.createEvent(110).setBoolean(DevicePolicyManagerService.this.isSeparateProfileChallengeEnabled(userId)).write();
            } catch (Throwable th) {
                DevicePolicyManagerService.this.mInjector.binderRestoreCallingIdentity(ident);
                throw th;
            }
        }

        public boolean canUserHaveUntrustedCredentialReset(int userId) {
            return DevicePolicyManagerService.this.canUserHaveUntrustedCredentialReset(userId);
        }

        public CharSequence getPrintingDisabledReasonForUser(int userId) {
            synchronized (DevicePolicyManagerService.this.getLockObject()) {
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
                    return ActivityThread.currentActivityThread().getSystemUiContext().getResources().getString(17040900, appLabel);
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
        if ("policy_disable_camera".equals(restriction) || "policy_disable_screen_capture".equals(restriction)) {
            synchronized (getLockObject()) {
                DevicePolicyData policy = getUserData(userId);
                int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin2 = policy.mAdminList.get(i);
                    if ((admin2.disableCamera && "policy_disable_camera".equals(restriction)) || (admin2.disableScreenCapture && "policy_disable_screen_capture".equals(restriction))) {
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
        DevicePolicyEventLogger.createEvent(50).setAdmin(who).setInt(policy != null ? policy.getPolicyType() : 0).write();
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
                } else {
                    boolean changed2 = now.isBefore(start);
                    if (changed2) {
                        changed = this.mOwners.setSystemUpdateFreezePeriodRecord(now, now);
                    } else {
                        changed = false;
                    }
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
        boolean isDelegate = admin == null;
        DevicePolicyEventLogger.createEvent(18).setAdmin(callerPackage).setInt(policy).setBoolean(isDelegate).write();
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:91:0x0125
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public void setPermissionGrantState(android.content.ComponentName r20, java.lang.String r21, java.lang.String r22, java.lang.String r23, int r24, android.os.RemoteCallback r25) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 301
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.setPermissionGrantState(android.content.ComponentName, java.lang.String, java.lang.String, java.lang.String, int, android.os.RemoteCallback):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setPermissionGrantState$13(boolean isPostQAdmin, RemoteCallback callback, ComponentName admin, String callerPackage, String permission, int grantState, Boolean permissionWasSet) {
        if (isPostQAdmin && !permissionWasSet.booleanValue()) {
            callback.sendResult((Bundle) null);
            return;
        }
        boolean isDelegate = admin == null;
        DevicePolicyEventLogger.createEvent(19).setAdmin(callerPackage).setStrings(new String[]{permission}).setInt(grantState).setBoolean(isDelegate).write();
        callback.sendResult(Bundle.EMPTY);
    }

    public int getPermissionGrantState(ComponentName admin, String callerPackage, String packageName, String permission) throws RemoteException {
        int granted;
        PackageManager packageManager = this.mInjector.getPackageManager();
        UserHandle user = this.mInjector.binderGetCallingUserHandle();
        if (!isCallerWithSystemUid()) {
            enforceCanManageScope(admin, callerPackage, -1, "delegation-permission-grant");
        }
        synchronized (getLockObject()) {
            long ident = this.mInjector.binderClearCallingIdentity();
            int i = 1;
            if (getTargetSdk(callerPackage, user.getIdentifier()) < 29) {
                granted = this.mIPackageManager.checkPermission(permission, packageName, user.getIdentifier());
            } else {
                try {
                    int uid = packageManager.getPackageUidAsUser(packageName, user.getIdentifier());
                    if (PermissionChecker.checkPermissionForPreflight(this.mContext, permission, -1, uid, packageName) != 0) {
                        granted = -1;
                    } else {
                        granted = 0;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RemoteException("Cannot check if " + permission + "is a runtime permission", e, false, true);
                }
            }
            int permFlags = packageManager.getPermissionFlags(permission, packageName, user);
            if ((permFlags & 4) == 4) {
                if (granted != 0) {
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
            switch (action.hashCode()) {
                case -920528692:
                    if (action.equals("android.app.action.PROVISION_MANAGED_DEVICE")) {
                        c = 1;
                        break;
                    }
                    break;
                case -514404415:
                    if (action.equals("android.app.action.PROVISION_MANAGED_USER")) {
                        c = 2;
                        break;
                    }
                    break;
                case -340845101:
                    if (action.equals("android.app.action.PROVISION_MANAGED_PROFILE")) {
                        c = 0;
                        break;
                    }
                    break;
                case 631897778:
                    if (action.equals("android.app.action.PROVISION_MANAGED_SHAREABLE_DEVICE")) {
                        c = 3;
                        break;
                    }
                    break;
            }
            if (c == 0) {
                return checkManagedProfileProvisioningPreCondition(packageName, callingUserId);
            }
            if (c == 1) {
                return checkDeviceOwnerProvisioningPreCondition(callingUserId);
            }
            if (c == 2) {
                return checkManagedUserProvisioningPreCondition(callingUserId);
            }
            if (c == 3) {
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
        enforceDeviceOwner(admin);
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            String[] macAddresses = this.mInjector.getWifiManager().getFactoryMacAddresses();
            if (macAddresses == null) {
                return null;
            }
            DevicePolicyEventLogger.createEvent(54).setAdmin(admin).write();
            return macAddresses.length > 0 ? macAddresses[0] : null;
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
        enforceDeviceOwner(admin);
        int callingUserId = this.mInjector.userHandleGetCallingUserId();
        return UserManager.isSplitSystemUser() && callingUserId == 0;
    }

    public void reboot(ComponentName admin) {
        Preconditions.checkNotNull(admin);
        enforceDeviceOwner(admin);
        long ident = this.mInjector.binderClearCallingIdentity();
        try {
            if (this.mTelephonyManager.getCallState() != 0) {
                throw new IllegalStateException("Cannot be called with ongoing call on the device");
            }
            DevicePolicyEventLogger.createEvent(34).setAdmin(admin).write();
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
        DevicePolicyEventLogger.createEvent(43).setAdmin(who).write();
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
        DevicePolicyEventLogger.createEvent(44).setAdmin(who).write();
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
        DevicePolicyEventLogger.createEvent(39).setAdmin(who).write();
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

    private boolean hasGrantProfileOwnerDevcieIdAccessPermission() {
        return this.mContext.checkCallingPermission("android.permission.GRANT_PROFILE_OWNER_DEVICE_IDS_ACCESS") == 0;
    }

    public void grantDeviceIdsAccessToProfileOwner(ComponentName who, int userId) {
        Preconditions.checkNotNull(who);
        if (!this.mHasFeature) {
            return;
        }
        if (!isCallerWithSystemUid() && !isAdb() && !hasGrantProfileOwnerDevcieIdAccessPermission()) {
            throw new SecurityException("Only the system can grant Device IDs access for a profile owner.");
        }
        if (isAdb() && hasIncompatibleAccountsOrNonAdbNoLock(userId, who)) {
            throw new SecurityException("Can only be called from ADB if the device has no accounts.");
        }
        synchronized (getLockObject()) {
            if (!isProfileOwner(who, userId)) {
                throw new IllegalArgumentException(String.format("Component %s is not a Profile Owner of user %d", who.flattenToString(), Integer.valueOf(userId)));
            }
            Slog.i(LOG_TAG, String.format("Granting Device ID access to %s, for user %d", who.flattenToString(), Integer.valueOf(userId)));
            this.mOwners.setProfileOwnerCanAccessDeviceIds(userId);
        }
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
            DevicePolicyEventLogger.createEvent(15).setAdmin(admin).setBoolean(enabled).write();
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
            DevicePolicyEventLogger.createEvent(17).setAdmin(admin).write();
            if (this.mContext.getResources().getBoolean(17891538) && this.mInjector.securityLogGetLoggingEnabledProperty()) {
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
                DevicePolicyEventLogger.createEvent(16).setAdmin(admin).write();
                if (logs != null) {
                    return new ParceledListSlice<>(logs);
                }
                return null;
            }
            return null;
        }
        return null;
    }

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
        enforceProfileOrDeviceOwner(admin);
        int userId = this.mInjector.userHandleGetCallingUserId();
        toggleBackupServiceActive(userId, enabled);
    }

    /* JADX WARN: Code restructure failed: missing block: B:10:0x0023, code lost:
        if (r2.isBackupServiceActive(r4.mInjector.userHandleGetCallingUserId()) != false) goto L12;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean isBackupServiceEnabled(android.content.ComponentName r5) {
        /*
            r4 = this;
            com.android.internal.util.Preconditions.checkNotNull(r5)
            boolean r0 = r4.mHasFeature
            r1 = 1
            if (r0 != 0) goto L9
            return r1
        L9:
            r4.enforceProfileOrDeviceOwner(r5)
            java.lang.Object r0 = r4.getLockObject()
            monitor-enter(r0)
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r2 = r4.mInjector     // Catch: java.lang.Throwable -> L29 android.os.RemoteException -> L2b
            android.app.backup.IBackupManager r2 = r2.getIBackupManager()     // Catch: java.lang.Throwable -> L29 android.os.RemoteException -> L2b
            if (r2 == 0) goto L26
            com.android.server.devicepolicy.DevicePolicyManagerService$Injector r3 = r4.mInjector     // Catch: java.lang.Throwable -> L29 android.os.RemoteException -> L2b
            int r3 = r3.userHandleGetCallingUserId()     // Catch: java.lang.Throwable -> L29 android.os.RemoteException -> L2b
            boolean r3 = r2.isBackupServiceActive(r3)     // Catch: java.lang.Throwable -> L29 android.os.RemoteException -> L2b
            if (r3 == 0) goto L26
            goto L27
        L26:
            r1 = 0
        L27:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L29
            return r1
        L29:
            r1 = move-exception
            goto L34
        L2b:
            r1 = move-exception
            java.lang.IllegalStateException r2 = new java.lang.IllegalStateException     // Catch: java.lang.Throwable -> L29
            java.lang.String r3 = "Failed requesting backup service state."
            r2.<init>(r3, r1)     // Catch: java.lang.Throwable -> L29
            throw r2     // Catch: java.lang.Throwable -> L29
        L34:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L29
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.isBackupServiceEnabled(android.content.ComponentName):boolean");
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

    public void setNetworkLoggingEnabled(ComponentName admin, String packageName, boolean enabled) {
        boolean isDelegate;
        if (!this.mHasFeature) {
            return;
        }
        synchronized (getLockObject()) {
            enforceCanManageScope(admin, packageName, -2, "delegation-network-logging");
            if (enabled == isNetworkLoggingEnabledInternalLocked()) {
                return;
            }
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            deviceOwner.isNetworkLoggingEnabled = enabled;
            int i = 0;
            if (!enabled) {
                deviceOwner.numNetworkLoggingNotifications = 0;
                deviceOwner.lastNetworkLoggingNotificationTimeMs = 0L;
            }
            saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
            setNetworkLoggingActiveInternal(enabled);
            if (admin != null) {
                isDelegate = false;
            } else {
                isDelegate = true;
            }
            DevicePolicyEventLogger devicePolicyEventLogger = DevicePolicyEventLogger.createEvent(119).setAdmin(packageName).setBoolean(isDelegate);
            if (enabled) {
                i = 1;
            }
            devicePolicyEventLogger.setInt(i).write();
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

    public long forceNetworkLogs() {
        enforceShell("forceNetworkLogs");
        synchronized (getLockObject()) {
            if (!isNetworkLoggingEnabledInternalLocked()) {
                throw new IllegalStateException("logging is not available");
            }
            if (this.mNetworkLogger != null) {
                long ident = this.mInjector.binderClearCallingIdentity();
                long forceBatchFinalization = this.mNetworkLogger.forceBatchFinalization();
                this.mInjector.binderRestoreCallingIdentity(ident);
                return forceBatchFinalization;
            }
            return 0L;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"getLockObject()"})
    public void maybePauseDeviceWideLoggingLocked() {
        if (!areAllUsersAffiliatedWithDeviceLocked()) {
            Slog.i(LOG_TAG, "There are unaffiliated users, security and network logging will be paused if enabled.");
            this.mSecurityLogMonitor.pause();
            NetworkLogger networkLogger = this.mNetworkLogger;
            if (networkLogger != null) {
                networkLogger.pause();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"getLockObject()"})
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
    @GuardedBy({"getLockObject()"})
    public void discardDeviceWideLogsLocked() {
        this.mSecurityLogMonitor.discardLogs();
        NetworkLogger networkLogger = this.mNetworkLogger;
        if (networkLogger != null) {
            networkLogger.discardLogs();
        }
    }

    public boolean isNetworkLoggingEnabled(ComponentName admin, String packageName) {
        boolean isNetworkLoggingEnabledInternalLocked;
        if (!this.mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            enforceCanManageScopeOrCheckPermission(admin, packageName, -2, "delegation-network-logging", "android.permission.MANAGE_USERS");
            isNetworkLoggingEnabledInternalLocked = isNetworkLoggingEnabledInternalLocked();
        }
        return isNetworkLoggingEnabledInternalLocked;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isNetworkLoggingEnabledInternalLocked() {
        ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        return deviceOwner != null && deviceOwner.isNetworkLoggingEnabled;
    }

    public List<NetworkEvent> retrieveNetworkLogs(ComponentName admin, String packageName, long batchToken) {
        if (this.mHasFeature) {
            enforceCanManageScope(admin, packageName, -2, "delegation-network-logging");
            ensureAllUsersAffiliated();
            synchronized (getLockObject()) {
                if (this.mNetworkLogger != null && isNetworkLoggingEnabledInternalLocked()) {
                    boolean isDelegate = admin == null;
                    DevicePolicyEventLogger.createEvent(120).setAdmin(packageName).setBoolean(isDelegate).write();
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
        intent.setPackage("com.android.systemui");
        PendingIntent pendingIntent = PendingIntent.getBroadcastAsUser(this.mContext, 0, intent, 0, UserHandle.CURRENT);
        Notification notification = new Notification.Builder(this.mContext, SystemNotificationChannels.DEVICE_ADMIN).setSmallIcon(17302433).setContentTitle(this.mContext.getString(17040470)).setContentText(this.mContext.getString(17040469)).setTicker(this.mContext.getString(17040470)).setShowWhen(true).setContentIntent(pendingIntent).setStyle(new Notification.BigTextStyle().bigText(this.mContext.getString(17040469))).build();
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
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
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
                policy.mPasswordTokenHandle = this.mLockPatternUtils.addEscrowToken(token, userHandle, (LockPatternUtils.EscrowTokenStateChangeCallback) null);
                saveSettingsLocked(userHandle);
                z = policy.mPasswordTokenHandle != 0;
                this.mInjector.binderRestoreCallingIdentity(ident);
            }
            return z;
        }
        return false;
    }

    public boolean clearResetPasswordToken(ComponentName admin) {
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
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
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
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
        return false;
    }

    public boolean resetPasswordWithToken(ComponentName admin, String passwordOrNull, byte[] token, int flags) {
        if (this.mHasFeature && this.mLockPatternUtils.hasSecureLockScreen()) {
            Preconditions.checkNotNull(token);
            synchronized (getLockObject()) {
                try {
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    int userHandle = this.mInjector.userHandleGetCallingUserId();
                    getActiveAdminForCallerLocked(admin, -1);
                    DevicePolicyData policy = getUserData(userHandle);
                    if (policy.mPasswordTokenHandle != 0) {
                        String password = passwordOrNull != null ? passwordOrNull : "";
                        return resetPasswordInternal(password, policy.mPasswordTokenHandle, token, flags, this.mInjector.binderGetCallingUid(), userHandle);
                    }
                    Slog.w(LOG_TAG, "No saved token handle");
                    return false;
                } catch (Throwable th2) {
                    th = th2;
                    throw th;
                }
            }
        }
        return false;
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
        enforceProfileOrDeviceOwner(admin);
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:30:0x009b
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public void transferOwnership(android.content.ComponentName r20, android.content.ComponentName r21, android.os.PersistableBundle r22) {
        /*
            Method dump skipped, instructions count: 294
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.DevicePolicyManagerService.transferOwnership(android.content.ComponentName, android.content.ComponentName, android.os.PersistableBundle):void");
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

    public int addOverrideApn(ComponentName who, ApnSetting apnSetting) {
        if (!this.mHasFeature || !this.mHasTelephonyFeature) {
            return -1;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in addOverrideApn");
        Preconditions.checkNotNull(apnSetting, "ApnSetting is null in addOverrideApn");
        enforceDeviceOwner(who);
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

    public boolean updateOverrideApn(ComponentName who, int apnId, ApnSetting apnSetting) {
        if (this.mHasFeature && this.mHasTelephonyFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null in updateOverrideApn");
            Preconditions.checkNotNull(apnSetting, "ApnSetting is null in updateOverrideApn");
            enforceDeviceOwner(who);
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

    public boolean removeOverrideApn(ComponentName who, int apnId) {
        if (!this.mHasFeature || !this.mHasTelephonyFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in removeOverrideApn");
        enforceDeviceOwner(who);
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

    public List<ApnSetting> getOverrideApns(ComponentName who) {
        if (!this.mHasFeature || !this.mHasTelephonyFeature) {
            return Collections.emptyList();
        }
        Preconditions.checkNotNull(who, "ComponentName is null in getOverrideApns");
        enforceDeviceOwner(who);
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

    public void setOverrideApnsEnabled(ComponentName who, boolean enabled) {
        if (!this.mHasFeature || !this.mHasTelephonyFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in setOverrideApnEnabled");
        enforceDeviceOwner(who);
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

    public boolean isOverrideApnEnabled(ComponentName who) {
        if (this.mHasFeature && this.mHasTelephonyFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null in isOverrideApnEnabled");
            enforceDeviceOwner(who);
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
        return context.getResources().getString(17039756);
    }

    private void putPrivateDnsSettings(String mode, String host) {
        long origId = this.mInjector.binderClearCallingIdentity();
        try {
            this.mInjector.settingsGlobalPutString("private_dns_mode", mode);
            this.mInjector.settingsGlobalPutString("private_dns_specifier", host);
        } finally {
            this.mInjector.binderRestoreCallingIdentity(origId);
        }
    }

    public int setGlobalPrivateDns(ComponentName who, int mode, String privateDnsHost) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            enforceDeviceOwner(who);
            if (mode == 2) {
                if (!TextUtils.isEmpty(privateDnsHost)) {
                    throw new IllegalArgumentException("Host provided for opportunistic mode, but is not needed.");
                }
                putPrivateDnsSettings("opportunistic", null);
                return 0;
            } else if (mode == 3) {
                if (TextUtils.isEmpty(privateDnsHost) || !NetworkUtils.isWeaklyValidatedHostname(privateDnsHost)) {
                    throw new IllegalArgumentException(String.format("Provided hostname %s is not valid", privateDnsHost));
                }
                putPrivateDnsSettings("hostname", privateDnsHost);
                return 0;
            } else {
                throw new IllegalArgumentException(String.format("Provided mode, %d, is not a valid mode.", Integer.valueOf(mode)));
            }
        }
        return 2;
    }

    public int getGlobalPrivateDnsMode(ComponentName who) {
        if (this.mHasFeature) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            enforceDeviceOwner(who);
            String currentMode = this.mInjector.settingsGlobalGetString("private_dns_mode");
            if (currentMode == null) {
                currentMode = "opportunistic";
            }
            char c = 65535;
            int hashCode = currentMode.hashCode();
            if (hashCode != -539229175) {
                if (hashCode != -299803597) {
                    if (hashCode == 109935 && currentMode.equals("off")) {
                        c = 0;
                    }
                } else if (currentMode.equals("hostname")) {
                    c = 2;
                }
            } else if (currentMode.equals("opportunistic")) {
                c = 1;
            }
            if (c != 0) {
                if (c != 1) {
                    return c != 2 ? 0 : 3;
                }
                return 2;
            }
            return 1;
        }
        return 0;
    }

    public String getGlobalPrivateDnsHost(ComponentName who) {
        if (!this.mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        enforceDeviceOwner(who);
        return this.mInjector.settingsGlobalGetString("private_dns_specifier");
    }

    public void installUpdateFromFile(ComponentName admin, ParcelFileDescriptor updateFileDescriptor, StartInstallingUpdateCallback callback) {
        UpdateInstaller updateInstaller;
        DevicePolicyEventLogger.createEvent(73).setAdmin(admin).setBoolean(isDeviceAB()).write();
        enforceDeviceOwner(admin);
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            if (isDeviceAB()) {
                updateInstaller = new AbUpdateInstaller(this.mContext, updateFileDescriptor, callback, this.mInjector, this.mConstants);
            } else {
                updateInstaller = new NonAbUpdateInstaller(this.mContext, updateFileDescriptor, callback, this.mInjector, this.mConstants);
            }
            updateInstaller.startInstallUpdate();
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private boolean isDeviceAB() {
        return "true".equalsIgnoreCase(SystemProperties.get(AB_DEVICE_KEY, ""));
    }

    public void setCrossProfileCalendarPackages(ComponentName who, List<String> packageNames) {
        if (!this.mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            admin.mCrossProfileCalendarPackages = packageNames;
            saveSettingsLocked(this.mInjector.userHandleGetCallingUserId());
        }
        DevicePolicyEventLogger.createEvent(70).setAdmin(who).setStrings(packageNames == null ? null : (String[]) packageNames.toArray(new String[packageNames.size()])).write();
    }

    public List<String> getCrossProfileCalendarPackages(ComponentName who) {
        List<String> list;
        if (!this.mHasFeature) {
            return Collections.emptyList();
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who, -1);
            list = admin.mCrossProfileCalendarPackages;
        }
        return list;
    }

    public boolean isPackageAllowedToAccessCalendarForUser(String packageName, int userHandle) {
        if (this.mHasFeature) {
            Preconditions.checkStringNotEmpty(packageName, "Package name is null or empty");
            enforceCrossUsersPermission(userHandle);
            synchronized (getLockObject()) {
                if (this.mInjector.settingsSecureGetIntForUser("cross_profile_calendar_enabled", 0, userHandle) == 0) {
                    return false;
                }
                ActiveAdmin admin = getProfileOwnerAdminLocked(userHandle);
                if (admin != null) {
                    if (admin.mCrossProfileCalendarPackages == null) {
                        return true;
                    }
                    return admin.mCrossProfileCalendarPackages.contains(packageName);
                }
                return false;
            }
        }
        return false;
    }

    public List<String> getCrossProfileCalendarPackagesForUser(int userHandle) {
        if (!this.mHasFeature) {
            return Collections.emptyList();
        }
        enforceCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userHandle);
            if (admin != null) {
                return admin.mCrossProfileCalendarPackages;
            }
            return Collections.emptyList();
        }
    }

    public boolean isManagedKiosk() {
        if (!this.mHasFeature) {
            return false;
        }
        enforceManageUsers();
        long id = this.mInjector.binderClearCallingIdentity();
        try {
            try {
                return isManagedKioskInternal();
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        } finally {
            this.mInjector.binderRestoreCallingIdentity(id);
        }
    }

    public boolean isUnattendedManagedKiosk() {
        boolean z = false;
        if (this.mHasFeature) {
            enforceManageUsers();
            long id = this.mInjector.binderClearCallingIdentity();
            try {
                try {
                    if (isManagedKioskInternal()) {
                        if (getPowerManagerInternal().wasDeviceIdleFor(30000L)) {
                            z = true;
                        }
                    }
                    return z;
                } catch (RemoteException e) {
                    throw new IllegalStateException(e);
                }
            } finally {
                this.mInjector.binderRestoreCallingIdentity(id);
            }
        }
        return false;
    }

    private boolean isManagedKioskInternal() throws RemoteException {
        return (!this.mOwners.hasDeviceOwner() || this.mInjector.getIActivityManager().getLockTaskModeState() != 1 || isLockTaskFeatureEnabled(1) || deviceHasKeyguard() || inEphemeralUserSession()) ? false : true;
    }

    private boolean isLockTaskFeatureEnabled(int lockTaskFeature) throws RemoteException {
        int lockTaskFeatures = getUserData(this.mInjector.getIActivityManager().getCurrentUser().id).mLockTaskFeatures;
        return (lockTaskFeatures & lockTaskFeature) == lockTaskFeature;
    }

    private boolean deviceHasKeyguard() {
        for (UserInfo userInfo : this.mUserManager.getUsers()) {
            if (this.mLockPatternUtils.isSecure(userInfo.id)) {
                return true;
            }
        }
        return false;
    }

    private boolean inEphemeralUserSession() {
        for (UserInfo userInfo : this.mUserManager.getUsers()) {
            if (this.mInjector.getUserManager().isUserEphemeral(userInfo.id)) {
                return true;
            }
        }
        return false;
    }

    private PowerManagerInternal getPowerManagerInternal() {
        return this.mInjector.getPowerManagerInternal();
    }

    public boolean startViewCalendarEventInManagedProfile(String packageName, long eventId, long start, long end, boolean allDay, int flags) {
        int workProfileUserId;
        if (this.mHasFeature) {
            Preconditions.checkStringNotEmpty(packageName, "Package name is empty");
            int callingUid = this.mInjector.binderGetCallingUid();
            int callingUserId = this.mInjector.userHandleGetCallingUserId();
            if (isCallingFromPackage(packageName, callingUid)) {
                long identity = this.mInjector.binderClearCallingIdentity();
                try {
                    workProfileUserId = getManagedUserId(callingUserId);
                } catch (Throwable th) {
                    e = th;
                }
                if (workProfileUserId < 0) {
                    this.mInjector.binderRestoreCallingIdentity(identity);
                    return false;
                }
                if (isPackageAllowedToAccessCalendarForUser(packageName, workProfileUserId)) {
                    Intent intent = new Intent("android.provider.calendar.action.VIEW_MANAGED_PROFILE_CALENDAR_EVENT");
                    intent.setPackage(packageName);
                    try {
                        intent.putExtra(ATTR_ID, eventId);
                    } catch (Throwable th2) {
                        e = th2;
                        this.mInjector.binderRestoreCallingIdentity(identity);
                        throw e;
                    }
                    try {
                        intent.putExtra("beginTime", start);
                        try {
                            intent.putExtra("endTime", end);
                            try {
                                intent.putExtra("allDay", allDay);
                                intent.setFlags(flags);
                                try {
                                    this.mContext.startActivityAsUser(intent, UserHandle.of(workProfileUserId));
                                    this.mInjector.binderRestoreCallingIdentity(identity);
                                    return true;
                                } catch (ActivityNotFoundException e) {
                                    Log.e(LOG_TAG, "View event activity not found", e);
                                    this.mInjector.binderRestoreCallingIdentity(identity);
                                    return false;
                                }
                            } catch (Throwable th3) {
                                e = th3;
                            }
                        } catch (Throwable th4) {
                            e = th4;
                        }
                    } catch (Throwable th5) {
                        e = th5;
                        this.mInjector.binderRestoreCallingIdentity(identity);
                        throw e;
                    }
                } else {
                    try {
                        Log.d(LOG_TAG, String.format("Package %s is not allowed to access cross-profilecalendar APIs", packageName));
                        this.mInjector.binderRestoreCallingIdentity(identity);
                        return false;
                    } catch (Throwable th6) {
                        e = th6;
                    }
                }
                this.mInjector.binderRestoreCallingIdentity(identity);
                throw e;
            }
            throw new SecurityException("Input package name doesn't align with actual calling package.");
        }
        return false;
    }

    private boolean isCallingFromPackage(String packageName, int callingUid) {
        try {
            int packageUid = this.mInjector.getPackageManager().getPackageUidAsUser(packageName, UserHandle.getUserId(callingUid));
            return packageUid == callingUid;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(LOG_TAG, "Calling package not found", e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public DevicePolicyConstants loadConstants() {
        return DevicePolicyConstants.loadFromString(this.mInjector.settingsGlobalGetString("device_policy_constants"));
    }
}
