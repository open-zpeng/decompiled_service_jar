package com.android.server.pm;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.ResourcesManager;
import android.app.admin.IDevicePolicyManager;
import android.app.admin.SecurityLog;
import android.app.backup.IBackupManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AppsQueryHelper;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IDexModuleRegisterCallback;
import android.content.pm.IOnPermissionsChangeListener;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageManagerNative;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstantAppInfo;
import android.content.pm.InstantAppRequest;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageBackwardCompatibility;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageList;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PackageStats;
import android.content.pm.PackageUserState;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.SELinuxUtil;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VerifierInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.dex.IArtManager;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.content.res.Resources;
import android.content.rollback.IRollbackManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.hardware.biometrics.fingerprint.V2_1.RequestStatus;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.DiskInfo;
import android.os.storage.IStorageManager;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeInfo;
import android.permission.PermissionManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.security.KeyStore;
import android.security.SystemKeyStore;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Base64;
import android.util.ByteStringUtils;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.IntArray;
import android.util.Log;
import android.util.LogPrinter;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.MathUtils;
import android.util.PackageUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimingsTraceLog;
import android.util.Xml;
import android.util.jar.StrictJarFile;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IntentForwarderActivity;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.SomeArgs;
import com.android.internal.telephony.CarrierAppUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.IntPair;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.TriFunction;
import com.android.server.AttributeCache;
import com.android.server.BatteryService;
import com.android.server.DeviceIdleController;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.PackageWatchdog;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.android.server.pm.CompilerStats;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageDexOptimizer;
import com.android.server.pm.PackageInstallerService;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.Settings;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.dex.PackageDexUsage;
import com.android.server.pm.dex.ViewCompiler;
import com.android.server.pm.permission.BasePermission;
import com.android.server.pm.permission.DefaultPermissionGrantPolicy;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.permission.PermissionsState;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.security.VerityUtils;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.xiaopeng.app.xpAppInfo;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import dalvik.system.CloseGuard;
import dalvik.system.VMRuntime;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.DigestException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import libcore.io.IoUtils;
import libcore.util.EmptyArray;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public class PackageManagerService extends IPackageManager.Stub implements PackageSender {
    private static final String ATTR_IS_GRANTED = "g";
    private static final String ATTR_PACKAGE_NAME = "pkg";
    private static final String ATTR_PERMISSION_NAME = "name";
    private static final String ATTR_REVOKE_ON_UPGRADE = "rou";
    private static final String ATTR_USER_FIXED = "fixed";
    private static final String ATTR_USER_SET = "set";
    private static final int BLUETOOTH_UID = 1002;
    private static final long BROADCAST_DELAY = 1000;
    private static final long BROADCAST_DELAY_DURING_STARTUP = 10000;
    static final int CHECK_PENDING_VERIFICATION = 16;
    static final boolean CLEAR_RUNTIME_PERMISSIONS_ON_UPGRADE = false;
    public static final String COMPRESSED_EXTENSION = ".gz";
    private static final int DATA_TRANSFER_PROCESS_DONE = 1;
    private static final boolean DEBUG_ABI_SELECTION = false;
    private static final boolean DEBUG_APP_DATA = false;
    private static final boolean DEBUG_BACKUP = false;
    private static final boolean DEBUG_BROADCASTS = false;
    public static final boolean DEBUG_DEXOPT = false;
    static final boolean DEBUG_DOMAIN_VERIFICATION = false;
    public static final boolean DEBUG_INSTALL = false;
    private static final boolean DEBUG_INTENT_MATCHING = false;
    private static final boolean DEBUG_PACKAGE_INFO = false;
    public static final boolean DEBUG_PACKAGE_SCANNING = false;
    public static final boolean DEBUG_PERMISSIONS = false;
    static final boolean DEBUG_PREFERRED = false;
    public static final boolean DEBUG_REMOVE = false;
    static final boolean DEBUG_SD_INSTALL = false;
    public static final boolean DEBUG_SETTINGS = false;
    private static final boolean DEBUG_SHARED_LIBRARIES = false;
    static final boolean DEBUG_UPGRADE = false;
    private static final boolean DEBUG_VERIFY = false;
    private static final long DEFAULT_ENABLE_ROLLBACK_TIMEOUT_MILLIS = 10000;
    private static final long DEFAULT_MANDATORY_FSTRIM_INTERVAL = 259200000;
    private static final boolean DEFAULT_PACKAGE_PARSER_CACHE_ENABLED = true;
    private static final long DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD = 7200000;
    private static final int DEFAULT_VERIFICATION_RESPONSE = 1;
    private static final long DEFAULT_VERIFICATION_TIMEOUT = 10000;
    private static final boolean DEFAULT_VERIFY_ENABLE = true;
    static final int DEFERRED_NO_KILL_INSTALL_OBSERVER = 24;
    static final int DEFERRED_NO_KILL_INSTALL_OBSERVER_DELAY_MS = 500;
    static final int DEFERRED_NO_KILL_POST_DELETE = 23;
    static final int DEFERRED_NO_KILL_POST_DELETE_DELAY_MS = 3000;
    private static final int[] EMPTY_INT_ARRAY;
    private static final boolean ENABLE_FREE_CACHE_V2;
    static final int ENABLE_ROLLBACK_STATUS = 21;
    static final int ENABLE_ROLLBACK_TIMEOUT = 22;
    private static final boolean HIDE_EPHEMERAL_APIS = false;
    static final int INIT_COPY = 5;
    private static final String[] INSTANT_APP_BROADCAST_PERMISSION;
    static final int INSTANT_APP_RESOLUTION_PHASE_TWO = 20;
    static final int INTENT_FILTER_VERIFIED = 18;
    private static final String KILL_APP_REASON_GIDS_CHANGED = "permission grant or revoke changed gids";
    private static final String KILL_APP_REASON_PERMISSIONS_REVOKED = "permissions revoked";
    private static final int LOG_UID = 1007;
    private static final int NETWORKSTACK_UID = 1073;
    private static final int NFC_UID = 1027;
    private static final String ODM_OVERLAY_DIR = "/odm/overlay";
    private static final String OEM_OVERLAY_DIR = "/oem/overlay";
    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String PACKAGE_SCHEME = "package";
    static final int PACKAGE_VERIFIED = 15;
    public static final String PLATFORM_PACKAGE_NAME = "android";
    static final int POST_INSTALL = 9;
    private static final String PRECOMPILE_LAYOUTS = "pm.precompile_layouts";
    private static final String PRODUCT_OVERLAY_DIR = "/product/overlay";
    private static final String PRODUCT_SERVICES_OVERLAY_DIR = "/product_services/overlay";
    private static final String PROPERTY_ENABLE_ROLLBACK_TIMEOUT_MILLIS = "enable_rollback_timeout";
    private static final int RADIO_UID = 1001;
    public static final int REASON_AB_OTA = 4;
    public static final int REASON_BACKGROUND_DEXOPT = 3;
    public static final int REASON_BOOT = 1;
    public static final int REASON_FIRST_BOOT = 0;
    public static final int REASON_INACTIVE_PACKAGE_DOWNGRADE = 5;
    public static final int REASON_INSTALL = 2;
    public static final int REASON_LAST = 6;
    public static final int REASON_SHARED = 6;
    public static final int REASON_UNKNOWN = -1;
    static final int SCAN_AS_FULL_APP = 32768;
    static final int SCAN_AS_INSTANT_APP = 16384;
    static final int SCAN_AS_ODM = 8388608;
    static final int SCAN_AS_OEM = 524288;
    static final int SCAN_AS_PRIVILEGED = 262144;
    static final int SCAN_AS_PRODUCT = 2097152;
    static final int SCAN_AS_PRODUCT_SERVICES = 4194304;
    static final int SCAN_AS_SYSTEM = 131072;
    static final int SCAN_AS_VENDOR = 1048576;
    static final int SCAN_AS_VIRTUAL_PRELOAD = 65536;
    static final int SCAN_BOOTING = 16;
    static final int SCAN_CHECK_ONLY = 1024;
    static final int SCAN_DONT_KILL_APP = 2048;
    static final int SCAN_FIRST_BOOT_OR_UPGRADE = 8192;
    static final int SCAN_IGNORE_FROZEN = 4096;
    static final int SCAN_INITIAL = 512;
    static final int SCAN_MOVE = 256;
    static final int SCAN_NEW_INSTALL = 4;
    static final int SCAN_NO_DEX = 1;
    static final int SCAN_REQUIRE_KNOWN = 128;
    static final int SCAN_UPDATE_SIGNATURE = 2;
    static final int SCAN_UPDATE_TIME = 8;
    private static final String SD_ENCRYPTION_ALGORITHM = "AES";
    private static final String SD_ENCRYPTION_KEYSTORE_NAME = "AppsOnSD";
    static final int SEND_PENDING_BROADCAST = 1;
    private static final int SE_UID = 1068;
    private static final int SHELL_UID = 2000;
    static final int START_INTENT_FILTER_VERIFICATIONS = 17;
    private static final String STATIC_SHARED_LIB_DELIMITER = "_";
    public static final String STUB_SUFFIX = "-Stub";
    private static final int SYSTEM_RUNTIME_GRANT_MASK = 52;
    static final String TAG = "PackageManager";
    private static final String TAG_ALL_GRANTS = "rt-grants";
    private static final String TAG_DEFAULT_APPS = "da";
    private static final String TAG_GRANT = "grant";
    private static final String TAG_INTENT_FILTER_VERIFICATION = "iv";
    private static final String TAG_PERMISSION = "perm";
    private static final String TAG_PERMISSION_BACKUP = "perm-grant-backup";
    private static final String TAG_PREFERRED_BACKUP = "pa";
    private static final int TYPE_ACTIVITY = 1;
    private static final int TYPE_PROVIDER = 4;
    private static final int TYPE_RECEIVER = 2;
    private static final int TYPE_SERVICE = 3;
    private static final int TYPE_UNKNOWN = 0;
    private static final int USER_RUNTIME_GRANT_MASK = 11;
    private static final String VENDOR_OVERLAY_DIR = "/vendor/overlay";
    static final long WATCHDOG_TIMEOUT = 600000;
    static final int WRITE_PACKAGE_LIST = 19;
    static final int WRITE_PACKAGE_RESTRICTIONS = 14;
    static final int WRITE_SETTINGS = 13;
    static final int WRITE_SETTINGS_DELAY = 10000;
    public static final boolean is3DUI;
    private static List<xpAppInfoInner> mNapaAppInfoInnerList = null;
    private static List<xpAppInfo> mNapaAppInfoList = null;
    private static List<xpAppInfo> mNapaAppInfoListOne = null;
    private static List<xpAppInfo> mNapaAppInfoListTwo = null;
    public static final boolean needTransfer = false;
    private static final File sAppInstallDir;
    private static final File sAppLib32InstallDir;
    private static final Intent sBrowserIntent;
    private static final Comparator<ProviderInfo> sProviderInitOrderSorter;
    static UserManagerService sUserManager;
    private ActivityManagerInternal mActivityManagerInternal;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    ApplicationInfo mAndroidApplication;
    private final ApexManager mApexManager;
    final String mAppPredictionServicePackage;
    final ArtManagerService mArtManagerService;
    @GuardedBy({"mAvailableFeatures"})
    final ArrayMap<String, FeatureInfo> mAvailableFeatures;
    private File mCacheDir;
    @GuardedBy({"mPackages"})
    int mChangedPackagesSequenceNumber;
    @GuardedBy({"mPackages"})
    private PackageManagerInternal.CheckPermissionDelegate mCheckPermissionDelegate;
    private final ComponentResolver mComponentResolver;
    final String mConfiguratorPackage;
    final Context mContext;
    ComponentName mCustomResolverComponentName;
    final int mDefParseFlags;
    @GuardedBy({"mPackages"})
    private PackageManagerInternal.DefaultBrowserProvider mDefaultBrowserProvider;
    @GuardedBy({"mPackages"})
    private PackageManagerInternal.DefaultDialerProvider mDefaultDialerProvider;
    @GuardedBy({"mPackages"})
    private PackageManagerInternal.DefaultHomeProvider mDefaultHomeProvider;
    final DefaultPermissionGrantPolicy mDefaultPermissionPolicy;
    private DeviceIdleController.LocalService mDeviceIdleController;
    private final DexManager mDexManager;
    @GuardedBy({"mPackages"})
    private boolean mDexOptDialogShown;
    final String mDocumenterPackage;
    PackageManagerInternal.ExternalSourcesPolicy mExternalSourcesPolicy;
    final boolean mFactoryTest;
    boolean mFirstBoot;
    final PackageHandler mHandler;
    final ServiceThread mHandlerThread;
    volatile boolean mHasSystemUidErrors;
    final String mIncidentReportApproverPackage;
    @GuardedBy({"mInstallLock"})
    final Installer mInstaller;
    final PackageInstallerService mInstallerService;
    ActivityInfo mInstantAppInstallerActivity;
    private final InstantAppRegistry mInstantAppRegistry;
    final InstantAppResolverConnection mInstantAppResolverConnection;
    final ComponentName mInstantAppResolverSettingsComponent;
    private final IntentFilterVerifier<PackageParser.ActivityIntentInfo> mIntentFilterVerifier;
    private final ComponentName mIntentFilterVerifierComponent;
    final boolean mIsPreNMR1Upgrade;
    final boolean mIsPreNUpgrade;
    final boolean mIsPreQUpgrade;
    final boolean mIsUpgrade;
    private List<String> mKeepUninstalledPackages;
    final DisplayMetrics mMetrics;
    private final ModuleInfoProvider mModuleInfoProvider;
    private final MoveCallbacks mMoveCallbacks;
    private final OnPermissionChangeListeners mOnPermissionChangeListeners;
    final boolean mOnlyCore;
    private final PackageDexOptimizer mPackageDexOptimizer;
    private PackageValueObserver mPackageValueObserver;
    private final PermissionManagerServiceInternal mPermissionManager;
    PackageParser.Package mPlatformPackage;
    private Future<?> mPrepareAppDataFuture;
    private final ProcessLoggingHandler mProcessLoggingHandler;
    boolean mPromoteSystemApps;
    final ProtectedPackages mProtectedPackages;
    final String mRequiredInstallerPackage;
    final String mRequiredPermissionControllerPackage;
    final String mRequiredUninstallerPackage;
    final String mRequiredVerifierPackage;
    ComponentName mResolveComponentName;
    volatile boolean mSafeMode;
    final String[] mSeparateProcesses;
    private long mServiceStartWithDelay;
    final String mServicesSystemSharedLibraryPackageName;
    @GuardedBy({"mPackages"})
    final Settings mSettings;
    final String mSetupWizardPackage;
    final String mSharedSystemSharedLibraryPackageName;
    private StorageManagerInternal mStorageManagerInternal;
    final String mStorageManagerPackage;
    volatile boolean mSystemReady;
    final String mSystemTextClassifierPackage;
    private UserManagerInternal mUserManagerInternal;
    private final ViewCompiler mViewCompiler;
    final String mWellbeingPackage;
    private List<xpAppInfo> mXpAppPackageList;
    public static final boolean DEBUG_COMPRESSION = Build.IS_DEBUGGABLE;
    private static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;
    final int mSdkVersion = Build.VERSION.SDK_INT;
    final Object mInstallLock = new Object();
    @GuardedBy({"mPackages"})
    final ArrayMap<String, PackageParser.Package> mPackages = new ArrayMap<>();
    @GuardedBy({"mPackages"})
    final SparseIntArray mIsolatedOwners = new SparseIntArray();
    private final ArrayMap<String, File> mExpectingBetter = new ArrayMap<>();
    private final ArraySet<String> mExistingSystemPackages = new ArraySet<>();
    @GuardedBy({"mPackages"})
    final ArraySet<String> mFrozenPackages = new ArraySet<>();
    @GuardedBy({"mLoadedVolumes"})
    final ArraySet<String> mLoadedVolumes = new ArraySet<>();
    @GuardedBy({"mPackages"})
    final SparseArray<SparseArray<String>> mChangedPackages = new SparseArray<>();
    @GuardedBy({"mPackages"})
    final SparseArray<Map<String, Integer>> mChangedPackagesSequenceNumbers = new SparseArray<>();
    @GuardedBy({"mPackages"})
    private final ArraySet<PackageManagerInternal.PackageListObserver> mPackageListObservers = new ArraySet<>();
    @GuardedBy({"mPackages"})
    private final SparseIntArray mDefaultPermissionsGrantedUsers = new SparseIntArray();
    final PackageParser.Callback mPackageParserCallback = new PackageParserCallback();
    final ParallelPackageParserCallback mParallelPackageParserCallback = new ParallelPackageParserCallback();
    final ArrayMap<String, LongSparseArray<SharedLibraryInfo>> mSharedLibraries = new ArrayMap<>();
    final ArrayMap<String, LongSparseArray<SharedLibraryInfo>> mStaticLibsByDeclaringPackage = new ArrayMap<>();
    final ArrayMap<ComponentName, PackageParser.Instrumentation> mInstrumentation = new ArrayMap<>();
    final ArraySet<String> mTransferedPackages = new ArraySet<>();
    @GuardedBy({"mProtectedBroadcasts"})
    final ArraySet<String> mProtectedBroadcasts = new ArraySet<>();
    final SparseArray<PackageVerificationState> mPendingVerification = new SparseArray<>();
    final SparseArray<InstallParams> mPendingEnableRollback = new SparseArray<>();
    private AtomicInteger mNextMoveId = new AtomicInteger();
    private final SparseBooleanArray mUserNeedsBadging = new SparseBooleanArray();
    private int mPendingVerificationToken = 0;
    private int mPendingEnableRollbackToken = 0;
    private volatile SparseBooleanArray mWebInstantAppsDisabled = new SparseBooleanArray();
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    boolean mResolverReplaced = false;
    private int mIntentFilterVerificationToken = 0;
    final ResolveInfo mInstantAppInstallerInfo = new ResolveInfo();
    private final Map<String, Pair<PackageInstalledInfo, IPackageInstallObserver2>> mNoKillInstallObservers = Collections.synchronizedMap(new HashMap());
    final SparseArray<IntentFilterVerificationState> mIntentFilterVerificationStates = new SparseArray<>();
    private final ConditionVariable mBlockDeleteOnUserRemoveForTest = new ConditionVariable(true);
    final PendingPackageBroadcasts mPendingBroadcasts = new PendingPackageBroadcasts();
    private ArraySet<Integer> mDirtyUsers = new ArraySet<>();
    final SparseArray<PostInstallData> mRunningInstalls = new SparseArray<>();
    int mNextInstallToken = 1;
    private final PackageUsage mPackageUsage = new PackageUsage();
    private final CompilerStats mCompilerStats = new CompilerStats();
    private PermissionManagerServiceInternal.PermissionCallback mPermissionCallback = new AnonymousClass1();
    private StorageEventListener mStorageListener = new StorageEventListener() { // from class: com.android.server.pm.PackageManagerService.2
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            if (vol.type == 1) {
                if (vol.state == 2) {
                    String volumeUuid = vol.getFsUuid();
                    PackageManagerService.sUserManager.reconcileUsers(volumeUuid);
                    PackageManagerService.this.reconcileApps(volumeUuid);
                    PackageManagerService.this.mInstallerService.onPrivateVolumeMounted(volumeUuid);
                    PackageManagerService.this.loadPrivatePackages(vol);
                } else if (vol.state == 5) {
                    PackageManagerService.this.unloadPrivatePackages(vol);
                }
            }
        }

        public void onVolumeForgotten(String fsUuid) {
            if (TextUtils.isEmpty(fsUuid)) {
                Slog.e(PackageManagerService.TAG, "Forgetting internal storage is probably a mistake; ignoring");
                return;
            }
            synchronized (PackageManagerService.this.mPackages) {
                List<PackageSetting> packages = PackageManagerService.this.mSettings.getVolumePackagesLPr(fsUuid);
                for (PackageSetting ps : packages) {
                    Slog.d(PackageManagerService.TAG, "Destroying " + ps.name + " because volume was forgotten");
                    PackageManagerService.this.deletePackageVersioned(new VersionedPackage(ps.name, -1), new PackageManager.LegacyPackageDeleteObserver((IPackageDeleteObserver) null).getBinder(), 0, 2);
                    AttributeCache.instance().removePackage(ps.name);
                }
                PackageManagerService.this.mSettings.onVolumeForgotten(fsUuid);
                PackageManagerService.this.mSettings.writeLPr();
            }
        }
    };
    private boolean mMediaMounted = false;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public interface BlobXmlRestorer {
        void apply(XmlPullParser xmlPullParser, int i) throws IOException, XmlPullParserException;
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface ComponentType {
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public interface IntentFilterVerifier<T extends IntentFilter> {
        boolean addOneIntentFilterVerification(int i, int i2, int i3, T t, String str);

        void receiveVerificationResponse(int i);

        void startVerifications(int i);
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface ScanFlags {
    }

    static /* synthetic */ int access$3408(PackageManagerService x0) {
        int i = x0.mPendingVerificationToken;
        x0.mPendingVerificationToken = i + 1;
        return i;
    }

    static /* synthetic */ int access$3708(PackageManagerService x0) {
        int i = x0.mPendingEnableRollbackToken;
        x0.mPendingEnableRollbackToken = i + 1;
        return i;
    }

    static {
        is3DUI = FeatureOption.FO_PROJECT_UI_TYPE == 2;
        ENABLE_FREE_CACHE_V2 = SystemProperties.getBoolean("fw.free_cache_v2", true);
        EMPTY_INT_ARRAY = new int[0];
        sBrowserIntent = new Intent();
        sBrowserIntent.setAction("android.intent.action.VIEW");
        sBrowserIntent.addCategory("android.intent.category.BROWSABLE");
        sBrowserIntent.setData(Uri.parse("http:"));
        sBrowserIntent.addFlags(512);
        INSTANT_APP_BROADCAST_PERMISSION = new String[]{"android.permission.ACCESS_INSTANT_APPS"};
        sAppInstallDir = new File(Environment.getDataDirectory(), "app");
        sAppLib32InstallDir = new File(Environment.getDataDirectory(), "app-lib");
        sProviderInitOrderSorter = new Comparator() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$2YJvUTVT-Ci_lr8H2GsUrtJlWH8
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return PackageManagerService.lambda$static$9((ProviderInfo) obj, (ProviderInfo) obj2);
            }
        };
        mNapaAppInfoList = null;
        mNapaAppInfoListOne = null;
        mNapaAppInfoListTwo = null;
        mNapaAppInfoInnerList = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PackageValueObserver extends ContentObserver {
        String KEY_NAPA;
        int PROV_NAPA;

        public PackageValueObserver(Handler handler) {
            super(handler);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            onPackageValueChanged();
        }

        public void startObserving(String key) {
            this.KEY_NAPA = key;
            ContentResolver resolver = PackageManagerService.this.mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, this, -1);
        }

        public void stopObserving() {
            ContentResolver resolver = PackageManagerService.this.mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        public void onPackageValueChanged() {
            if (this.KEY_NAPA != null) {
                this.PROV_NAPA = Settings.Global.getInt(PackageManagerService.this.mContext.getContentResolver(), this.KEY_NAPA, 0);
            }
            if (this.KEY_NAPA != null && PackageManagerService.mNapaAppInfoInnerList != null) {
                xpAppInfoInner mInfoInner = null;
                int i = 0;
                while (true) {
                    if (i >= PackageManagerService.mNapaAppInfoInnerList.size()) {
                        break;
                    }
                    String extr = this.KEY_NAPA.split(PackageManagerService.STATIC_SHARED_LIB_DELIMITER)[2];
                    String total = "applist_" + extr;
                    if (total.equals(((xpAppInfoInner) PackageManagerService.mNapaAppInfoInnerList.get(i)).appInfo.resId)) {
                        ((xpAppInfoInner) PackageManagerService.mNapaAppInfoInnerList.get(i)).display = this.PROV_NAPA != 0;
                        mInfoInner = (xpAppInfoInner) PackageManagerService.mNapaAppInfoInnerList.get(i);
                    } else {
                        i++;
                    }
                }
                if (mInfoInner != null) {
                    PackageManagerService packageManagerService = PackageManagerService.this;
                    packageManagerService.sendBroadcastForProvChange(packageManagerService.mContext, mInfoInner);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBroadcastForProvChange(Context context, xpAppInfoInner infoInner) {
        Intent intent = new Intent();
        intent.setAction("com.xiaopeng.intent.action.XP_NAPA_APP_LIST_CHANGE");
        intent.addFlags(20971552);
        Bundle bundle = new Bundle();
        bundle.putString("display", String.valueOf(infoInner.display));
        bundle.putString("XpAppId", infoInner.appInfo.mXpAppId);
        intent.putExtra("data", bundle);
        context.sendBroadcast(intent, "android.permission.RECV_XP_NAPA_APP_LIST_CHANGE");
    }

    private void addBootEvent(String bootevent) {
        try {
            FileOutputStream fbp = new FileOutputStream("/proc/bootprof");
            fbp.write(bootevent.getBytes());
            fbp.flush();
            fbp.close();
        } catch (FileNotFoundException e) {
            Slog.e("BOOTPROF", "Failure open /proc/bootprof, not found!", e);
        } catch (IOException e2) {
            Slog.e("BOOTPROF", "Failure open /proc/bootprof entry", e2);
        }
    }

    /* loaded from: classes.dex */
    class PackageParserCallback implements PackageParser.Callback {
        PackageParserCallback() {
        }

        public final boolean hasFeature(String feature) {
            return PackageManagerService.this.hasSystemFeature(feature, 0);
        }

        final List<PackageParser.Package> getStaticOverlayPackages(Collection<PackageParser.Package> allPackages, String targetPackageName) {
            if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(targetPackageName)) {
                return null;
            }
            List<PackageParser.Package> overlayPackages = null;
            for (PackageParser.Package p : allPackages) {
                if (targetPackageName.equals(p.mOverlayTarget) && p.mOverlayIsStatic) {
                    if (overlayPackages == null) {
                        overlayPackages = new ArrayList<>();
                    }
                    overlayPackages.add(p);
                }
            }
            if (overlayPackages != null) {
                overlayPackages.sort(Comparator.comparingInt(new ToIntFunction() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$PackageParserCallback$xinvBJUpQse3J1IBBKjvYTIW8MQ
                    @Override // java.util.function.ToIntFunction
                    public final int applyAsInt(Object obj) {
                        int i;
                        i = ((PackageParser.Package) obj).mOverlayPriority;
                        return i;
                    }
                }));
            }
            return overlayPackages;
        }

        final String[] getStaticOverlayPaths(List<PackageParser.Package> overlayPackages, String targetPath) {
            if (overlayPackages == null || overlayPackages.isEmpty()) {
                return null;
            }
            List<String> overlayPathList = null;
            for (PackageParser.Package overlayPackage : overlayPackages) {
                if (targetPath == null) {
                    if (overlayPathList == null) {
                        overlayPathList = new ArrayList<>();
                    }
                    overlayPathList.add(overlayPackage.baseCodePath);
                } else {
                    try {
                        PackageManagerService.this.mInstaller.idmap(targetPath, overlayPackage.baseCodePath, UserHandle.getSharedAppGid(UserHandle.getUserGid(0)));
                        if (overlayPathList == null) {
                            overlayPathList = new ArrayList<>();
                        }
                        overlayPathList.add(overlayPackage.baseCodePath);
                    } catch (Installer.InstallerException e) {
                        Slog.e(PackageManagerService.TAG, "Failed to generate idmap for " + targetPath + " and " + overlayPackage.baseCodePath);
                    }
                }
            }
            if (overlayPathList == null) {
                return null;
            }
            return (String[]) overlayPathList.toArray(new String[0]);
        }

        String[] getStaticOverlayPaths(String targetPackageName, String targetPath) {
            List<PackageParser.Package> overlayPackages;
            String[] staticOverlayPaths;
            synchronized (PackageManagerService.this.mInstallLock) {
                synchronized (PackageManagerService.this.mPackages) {
                    overlayPackages = getStaticOverlayPackages(PackageManagerService.this.mPackages.values(), targetPackageName);
                }
                staticOverlayPaths = getStaticOverlayPaths(overlayPackages, targetPath);
            }
            return staticOverlayPaths;
        }

        public final String[] getOverlayApks(String targetPackageName) {
            return getStaticOverlayPaths(targetPackageName, (String) null);
        }

        public final String[] getOverlayPaths(String targetPackageName, String targetPath) {
            return getStaticOverlayPaths(targetPackageName, targetPath);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ParallelPackageParserCallback extends PackageParserCallback {
        List<PackageParser.Package> mOverlayPackages;

        ParallelPackageParserCallback() {
            super();
            this.mOverlayPackages = null;
        }

        void findStaticOverlayPackages() {
            synchronized (PackageManagerService.this.mPackages) {
                for (PackageParser.Package p : PackageManagerService.this.mPackages.values()) {
                    if (p.mOverlayIsStatic) {
                        if (this.mOverlayPackages == null) {
                            this.mOverlayPackages = new ArrayList();
                        }
                        this.mOverlayPackages.add(p);
                    }
                }
            }
        }

        @Override // com.android.server.pm.PackageManagerService.PackageParserCallback
        synchronized String[] getStaticOverlayPaths(String targetPackageName, String targetPath) {
            return this.mOverlayPackages == null ? null : getStaticOverlayPaths(getStaticOverlayPackages(this.mOverlayPackages, targetPackageName), targetPath);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class IFVerificationParams {
        PackageParser.Package pkg;
        boolean replacing;
        int userId;
        int verifierUid;

        public IFVerificationParams(PackageParser.Package _pkg, boolean _replacing, int _userId, int _verifierUid) {
            this.pkg = _pkg;
            this.replacing = _replacing;
            this.userId = _userId;
            this.verifierUid = _verifierUid;
        }
    }

    /* loaded from: classes.dex */
    private class IntentVerifierProxy implements IntentFilterVerifier<PackageParser.ActivityIntentInfo> {
        private Context mContext;
        private ArrayList<Integer> mCurrentIntentFilterVerifications = new ArrayList<>();
        private ComponentName mIntentFilterVerifierComponent;

        public IntentVerifierProxy(Context context, ComponentName verifierComponent) {
            this.mContext = context;
            this.mIntentFilterVerifierComponent = verifierComponent;
        }

        private String getDefaultScheme() {
            return "https";
        }

        @Override // com.android.server.pm.PackageManagerService.IntentFilterVerifier
        public void startVerifications(int userId) {
            int count = this.mCurrentIntentFilterVerifications.size();
            for (int n = 0; n < count; n++) {
                int verificationId = this.mCurrentIntentFilterVerifications.get(n).intValue();
                IntentFilterVerificationState ivs = PackageManagerService.this.mIntentFilterVerificationStates.get(verificationId);
                String packageName = ivs.getPackageName();
                ArrayList<PackageParser.ActivityIntentInfo> filters = ivs.getFilters();
                int filterCount = filters.size();
                ArraySet<String> domainsSet = new ArraySet<>();
                for (int m = 0; m < filterCount; m++) {
                    PackageParser.ActivityIntentInfo filter = filters.get(m);
                    domainsSet.addAll(filter.getHostsList());
                }
                synchronized (PackageManagerService.this.mPackages) {
                    if (PackageManagerService.this.mSettings.createIntentFilterVerificationIfNeededLPw(packageName, domainsSet) != null) {
                        PackageManagerService.this.scheduleWriteSettingsLocked();
                    }
                }
                sendVerificationRequest(verificationId, ivs);
            }
            this.mCurrentIntentFilterVerifications.clear();
        }

        private void sendVerificationRequest(int verificationId, IntentFilterVerificationState ivs) {
            Intent verificationIntent = new Intent("android.intent.action.INTENT_FILTER_NEEDS_VERIFICATION");
            verificationIntent.putExtra("android.content.pm.extra.INTENT_FILTER_VERIFICATION_ID", verificationId);
            verificationIntent.putExtra("android.content.pm.extra.INTENT_FILTER_VERIFICATION_URI_SCHEME", getDefaultScheme());
            verificationIntent.putExtra("android.content.pm.extra.INTENT_FILTER_VERIFICATION_HOSTS", ivs.getHostsString());
            verificationIntent.putExtra("android.content.pm.extra.INTENT_FILTER_VERIFICATION_PACKAGE_NAME", ivs.getPackageName());
            verificationIntent.setComponent(this.mIntentFilterVerifierComponent);
            verificationIntent.addFlags(268435456);
            long whitelistTimeout = PackageManagerService.this.getVerificationTimeout();
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setTemporaryAppWhitelistDuration(whitelistTimeout);
            DeviceIdleController.LocalService idleController = PackageManagerService.this.getDeviceIdleController();
            idleController.addPowerSaveTempWhitelistApp(Process.myUid(), this.mIntentFilterVerifierComponent.getPackageName(), whitelistTimeout, 0, true, "intent filter verifier");
            this.mContext.sendBroadcastAsUser(verificationIntent, UserHandle.SYSTEM, null, options.toBundle());
        }

        /* JADX WARN: Code restructure failed: missing block: B:36:0x00aa, code lost:
            r14.this$0.mSettings.updateIntentFilterVerificationStatusLPw(r4, r11, r9);
            r14.this$0.scheduleWritePackageRestrictionsLocked(r9);
         */
        @Override // com.android.server.pm.PackageManagerService.IntentFilterVerifier
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void receiveVerificationResponse(int r15) {
            /*
                r14 = this;
                com.android.server.pm.PackageManagerService r0 = com.android.server.pm.PackageManagerService.this
                android.util.SparseArray<com.android.server.pm.IntentFilterVerificationState> r0 = r0.mIntentFilterVerificationStates
                java.lang.Object r0 = r0.get(r15)
                com.android.server.pm.IntentFilterVerificationState r0 = (com.android.server.pm.IntentFilterVerificationState) r0
                boolean r1 = r0.isVerified()
                java.util.ArrayList r2 = r0.getFilters()
                int r3 = r2.size()
                r4 = 0
            L17:
                if (r4 >= r3) goto L25
                java.lang.Object r5 = r2.get(r4)
                android.content.pm.PackageParser$ActivityIntentInfo r5 = (android.content.pm.PackageParser.ActivityIntentInfo) r5
                r5.setVerified(r1)
                int r4 = r4 + 1
                goto L17
            L25:
                com.android.server.pm.PackageManagerService r4 = com.android.server.pm.PackageManagerService.this
                android.util.SparseArray<com.android.server.pm.IntentFilterVerificationState> r4 = r4.mIntentFilterVerificationStates
                r4.remove(r15)
                java.lang.String r4 = r0.getPackageName()
                com.android.server.pm.PackageManagerService r5 = com.android.server.pm.PackageManagerService.this
                android.util.ArrayMap<java.lang.String, android.content.pm.PackageParser$Package> r5 = r5.mPackages
                monitor-enter(r5)
                com.android.server.pm.PackageManagerService r6 = com.android.server.pm.PackageManagerService.this     // Catch: java.lang.Throwable -> Lc3
                com.android.server.pm.Settings r6 = r6.mSettings     // Catch: java.lang.Throwable -> Lc3
                android.content.pm.IntentFilterVerificationInfo r6 = r6.getIntentFilterVerificationLPr(r4)     // Catch: java.lang.Throwable -> Lc3
                monitor-exit(r5)     // Catch: java.lang.Throwable -> Lc3
                if (r6 != 0) goto L5f
                java.lang.StringBuilder r5 = new java.lang.StringBuilder
                r5.<init>()
                java.lang.String r7 = "IntentFilterVerificationInfo not found for verificationId:"
                r5.append(r7)
                r5.append(r15)
                java.lang.String r7 = " packageName:"
                r5.append(r7)
                r5.append(r4)
                java.lang.String r5 = r5.toString()
                java.lang.String r7 = "PackageManager"
                android.util.Slog.w(r7, r5)
                return
            L5f:
                com.android.server.pm.PackageManagerService r5 = com.android.server.pm.PackageManagerService.this
                android.util.ArrayMap<java.lang.String, android.content.pm.PackageParser$Package> r7 = r5.mPackages
                monitor-enter(r7)
                r5 = 2
                r8 = 1
                if (r1 == 0) goto L6c
                r6.setStatus(r5)     // Catch: java.lang.Throwable -> Lc0
                goto L6f
            L6c:
                r6.setStatus(r8)     // Catch: java.lang.Throwable -> Lc0
            L6f:
                com.android.server.pm.PackageManagerService r9 = com.android.server.pm.PackageManagerService.this     // Catch: java.lang.Throwable -> Lc0
                r9.scheduleWriteSettingsLocked()     // Catch: java.lang.Throwable -> Lc0
                int r9 = r0.getUserId()     // Catch: java.lang.Throwable -> Lc0
                r10 = -1
                if (r9 == r10) goto Lb7
                com.android.server.pm.PackageManagerService r10 = com.android.server.pm.PackageManagerService.this     // Catch: java.lang.Throwable -> Lc0
                com.android.server.pm.Settings r10 = r10.mSettings     // Catch: java.lang.Throwable -> Lc0
                int r10 = r10.getIntentFilterVerificationStatusLPr(r4, r9)     // Catch: java.lang.Throwable -> Lc0
                r11 = 0
                r12 = 0
                if (r10 == 0) goto La3
                if (r10 == r8) goto L9e
                if (r10 == r5) goto L8c
                goto La8
            L8c:
                if (r1 != 0) goto La8
                com.android.server.SystemConfig r5 = com.android.server.SystemConfig.getInstance()     // Catch: java.lang.Throwable -> Lc0
                android.util.ArraySet r8 = r5.getLinkedApps()     // Catch: java.lang.Throwable -> Lc0
                boolean r13 = r8.contains(r4)     // Catch: java.lang.Throwable -> Lc0
                if (r13 != 0) goto L9d
                r12 = 1
            L9d:
                goto La8
            L9e:
                if (r1 == 0) goto La8
                r11 = 2
                r12 = 1
                goto La8
            La3:
                if (r1 == 0) goto La6
                r11 = 2
            La6:
                r12 = 1
            La8:
                if (r12 == 0) goto Lb6
                com.android.server.pm.PackageManagerService r5 = com.android.server.pm.PackageManagerService.this     // Catch: java.lang.Throwable -> Lc0
                com.android.server.pm.Settings r5 = r5.mSettings     // Catch: java.lang.Throwable -> Lc0
                r5.updateIntentFilterVerificationStatusLPw(r4, r11, r9)     // Catch: java.lang.Throwable -> Lc0
                com.android.server.pm.PackageManagerService r5 = com.android.server.pm.PackageManagerService.this     // Catch: java.lang.Throwable -> Lc0
                r5.scheduleWritePackageRestrictionsLocked(r9)     // Catch: java.lang.Throwable -> Lc0
            Lb6:
                goto Lbe
            Lb7:
                java.lang.String r5 = "PackageManager"
                java.lang.String r8 = "autoVerify ignored when installing for all users"
                android.util.Slog.i(r5, r8)     // Catch: java.lang.Throwable -> Lc0
            Lbe:
                monitor-exit(r7)     // Catch: java.lang.Throwable -> Lc0
                return
            Lc0:
                r5 = move-exception
                monitor-exit(r7)     // Catch: java.lang.Throwable -> Lc0
                throw r5
            Lc3:
                r6 = move-exception
                monitor-exit(r5)     // Catch: java.lang.Throwable -> Lc3
                throw r6
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.IntentVerifierProxy.receiveVerificationResponse(int):void");
        }

        @Override // com.android.server.pm.PackageManagerService.IntentFilterVerifier
        public boolean addOneIntentFilterVerification(int verifierUid, int userId, int verificationId, PackageParser.ActivityIntentInfo filter, String packageName) {
            if (!PackageManagerService.hasValidDomains(filter)) {
                return false;
            }
            IntentFilterVerificationState ivs = PackageManagerService.this.mIntentFilterVerificationStates.get(verificationId);
            if (ivs == null) {
                ivs = createDomainVerificationState(verifierUid, userId, verificationId, packageName);
            }
            ivs.addFilter(filter);
            return true;
        }

        private IntentFilterVerificationState createDomainVerificationState(int verifierUid, int userId, int verificationId, String packageName) {
            IntentFilterVerificationState ivs = new IntentFilterVerificationState(verifierUid, userId, packageName);
            ivs.setPendingState();
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mIntentFilterVerificationStates.append(verificationId, ivs);
                this.mCurrentIntentFilterVerifications.add(Integer.valueOf(verificationId));
            }
            return ivs;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean hasValidDomains(PackageParser.ActivityIntentInfo filter) {
        return filter.hasCategory("android.intent.category.BROWSABLE") && (filter.hasDataScheme("http") || filter.hasDataScheme("https"));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class PendingPackageBroadcasts {
        final SparseArray<ArrayMap<String, ArrayList<String>>> mUidMap = new SparseArray<>(2);

        public ArrayList<String> get(int userId, String packageName) {
            ArrayMap<String, ArrayList<String>> packages = getOrAllocate(userId);
            return packages.get(packageName);
        }

        public void put(int userId, String packageName, ArrayList<String> components) {
            ArrayMap<String, ArrayList<String>> packages = getOrAllocate(userId);
            packages.put(packageName, components);
        }

        public void remove(int userId, String packageName) {
            ArrayMap<String, ArrayList<String>> packages = this.mUidMap.get(userId);
            if (packages != null) {
                packages.remove(packageName);
            }
        }

        public void remove(int userId) {
            this.mUidMap.remove(userId);
        }

        public int userIdCount() {
            return this.mUidMap.size();
        }

        public int userIdAt(int n) {
            return this.mUidMap.keyAt(n);
        }

        public ArrayMap<String, ArrayList<String>> packagesForUserId(int userId) {
            return this.mUidMap.get(userId);
        }

        public int size() {
            int num = 0;
            for (int i = 0; i < this.mUidMap.size(); i++) {
                num += this.mUidMap.valueAt(i).size();
            }
            return num;
        }

        public void clear() {
            this.mUidMap.clear();
        }

        private ArrayMap<String, ArrayList<String>> getOrAllocate(int userId) {
            ArrayMap<String, ArrayList<String>> map = this.mUidMap.get(userId);
            if (map == null) {
                ArrayMap<String, ArrayList<String>> map2 = new ArrayMap<>();
                this.mUidMap.put(userId, map2);
                return map2;
            }
            return map;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class PostInstallData {
        public final InstallArgs args;
        public final Runnable mPostInstallRunnable;
        public final PackageInstalledInfo res;

        PostInstallData(InstallArgs _a, PackageInstalledInfo _r, Runnable postInstallRunnable) {
            this.args = _a;
            this.res = _r;
            this.mPostInstallRunnable = postInstallRunnable;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class PackageHandler extends Handler {
        PackageHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            try {
                doHandleMessage(msg);
            } finally {
                Process.setThreadPriority(10);
            }
        }

        void doHandleMessage(Message msg) {
            int i;
            long j;
            ArrayList whitelistedRestrictedPermissions;
            int i2 = msg.what;
            if (i2 == 1) {
                Process.setThreadPriority(0);
                synchronized (PackageManagerService.this.mPackages) {
                    int size = PackageManagerService.this.mPendingBroadcasts.size();
                    if (size <= 0) {
                        return;
                    }
                    String[] packages = new String[size];
                    ArrayList<String>[] components = new ArrayList[size];
                    int[] uids = new int[size];
                    int i3 = 0;
                    while (childCount < PackageManagerService.this.mPendingBroadcasts.userIdCount()) {
                        int packageUserId = PackageManagerService.this.mPendingBroadcasts.userIdAt(childCount);
                        Iterator<Map.Entry<String, ArrayList<String>>> it = PackageManagerService.this.mPendingBroadcasts.packagesForUserId(packageUserId).entrySet().iterator();
                        while (it.hasNext() && i3 < size) {
                            Map.Entry<String, ArrayList<String>> ent = it.next();
                            packages[i3] = ent.getKey();
                            components[i3] = ent.getValue();
                            PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(ent.getKey());
                            if (ps != null) {
                                i = UserHandle.getUid(packageUserId, ps.appId);
                            } else {
                                i = -1;
                            }
                            uids[i3] = i;
                            i3++;
                        }
                        childCount++;
                    }
                    int size2 = i3;
                    PackageManagerService.this.mPendingBroadcasts.clear();
                    for (int i4 = 0; i4 < size2; i4++) {
                        PackageManagerService.this.sendPackageChangedBroadcast(packages[i4], true, components[i4], uids[i4]);
                    }
                    Process.setThreadPriority(10);
                }
            } else if (i2 == 5) {
                HandlerParams params = (HandlerParams) msg.obj;
                if (params != null) {
                    Trace.asyncTraceEnd(262144L, "queueInstall", System.identityHashCode(params));
                    Trace.traceBegin(262144L, "startCopy");
                    params.startCopy();
                    Trace.traceEnd(262144L);
                }
            } else if (i2 == 9) {
                PostInstallData data = PackageManagerService.this.mRunningInstalls.get(msg.arg1);
                boolean didRestore = msg.arg2 != 0;
                PackageManagerService.this.mRunningInstalls.delete(msg.arg1);
                if (data != null && data.mPostInstallRunnable != null) {
                    data.mPostInstallRunnable.run();
                    j = 262144;
                } else if (data == null) {
                    j = 262144;
                } else {
                    InstallArgs args = data.args;
                    PackageInstalledInfo parentRes = data.res;
                    boolean grantPermissions = (args.installFlags & 256) != 0;
                    boolean killApp = (args.installFlags & 4096) == 0;
                    boolean virtualPreload = (args.installFlags & 65536) != 0;
                    String[] grantedPermissions = args.installGrantPermissions;
                    if ((args.installFlags & 4194304) != 0 && parentRes.pkg != null) {
                        whitelistedRestrictedPermissions = parentRes.pkg.requestedPermissions;
                    } else {
                        whitelistedRestrictedPermissions = args.whitelistedRestrictedPermissions;
                    }
                    PackageManagerService.this.handlePackagePostInstall(parentRes, grantPermissions, killApp, virtualPreload, grantedPermissions, whitelistedRestrictedPermissions, didRestore, args.installerPackageName, args.observer);
                    childCount = parentRes.addedChildPackages != null ? parentRes.addedChildPackages.size() : 0;
                    for (int i5 = 0; i5 < childCount; i5++) {
                        PackageInstalledInfo childRes = parentRes.addedChildPackages.valueAt(i5);
                        PackageManagerService.this.handlePackagePostInstall(childRes, grantPermissions, killApp, virtualPreload, grantedPermissions, whitelistedRestrictedPermissions, false, args.installerPackageName, args.observer);
                    }
                    if (args.traceMethod == null) {
                        j = 262144;
                    } else {
                        j = 262144;
                        Trace.asyncTraceEnd(262144L, args.traceMethod, args.traceCookie);
                    }
                }
                Trace.asyncTraceEnd(j, "postInstall", msg.arg1);
            } else {
                switch (i2) {
                    case 13:
                        Process.setThreadPriority(0);
                        synchronized (PackageManagerService.this.mPackages) {
                            removeMessages(13);
                            removeMessages(14);
                            PackageManagerService.this.mSettings.writeLPr();
                            PackageManagerService.this.mDirtyUsers.clear();
                        }
                        Process.setThreadPriority(10);
                        return;
                    case 14:
                        Process.setThreadPriority(0);
                        synchronized (PackageManagerService.this.mPackages) {
                            removeMessages(14);
                            Iterator it2 = PackageManagerService.this.mDirtyUsers.iterator();
                            while (it2.hasNext()) {
                                int userId = ((Integer) it2.next()).intValue();
                                PackageManagerService.this.mSettings.writePackageRestrictionsLPr(userId);
                            }
                            PackageManagerService.this.mDirtyUsers.clear();
                        }
                        Process.setThreadPriority(10);
                        return;
                    case 15:
                        int verificationId = msg.arg1;
                        PackageVerificationState state = PackageManagerService.this.mPendingVerification.get(verificationId);
                        if (state == null) {
                            Slog.w(PackageManagerService.TAG, "Invalid verification token " + verificationId + " received");
                            return;
                        }
                        PackageVerificationResponse response = (PackageVerificationResponse) msg.obj;
                        state.setVerifierResponse(response.callerUid, response.code);
                        if (state.isVerificationComplete()) {
                            PackageManagerService.this.mPendingVerification.remove(verificationId);
                            InstallParams params2 = state.getInstallParams();
                            InstallArgs args2 = params2.mArgs;
                            Uri originUri = Uri.fromFile(args2.origin.resolvedFile);
                            if (state.isInstallAllowed()) {
                                PackageManagerService.this.broadcastPackageVerified(verificationId, originUri, response.code, args2.getUser());
                            } else {
                                params2.setReturnCode(-22);
                            }
                            Trace.asyncTraceEnd(262144L, "verification", verificationId);
                            params2.handleVerificationFinished();
                            return;
                        }
                        return;
                    case 16:
                        int verificationId2 = msg.arg1;
                        PackageVerificationState state2 = PackageManagerService.this.mPendingVerification.get(verificationId2);
                        if (state2 != null && !state2.timeoutExtended()) {
                            InstallParams params3 = state2.getInstallParams();
                            InstallArgs args3 = params3.mArgs;
                            Uri originUri2 = Uri.fromFile(args3.origin.resolvedFile);
                            Slog.i(PackageManagerService.TAG, "Verification timed out for " + originUri2);
                            PackageManagerService.this.mPendingVerification.remove(verificationId2);
                            UserHandle user = args3.getUser();
                            if (PackageManagerService.this.getDefaultVerificationResponse(user) != 1) {
                                PackageManagerService.this.broadcastPackageVerified(verificationId2, originUri2, -1, user);
                                params3.setReturnCode(-22);
                            } else {
                                Slog.i(PackageManagerService.TAG, "Continuing with installation of " + originUri2);
                                state2.setVerifierResponse(Binder.getCallingUid(), 2);
                                PackageManagerService.this.broadcastPackageVerified(verificationId2, originUri2, 1, user);
                            }
                            Trace.asyncTraceEnd(262144L, "verification", verificationId2);
                            params3.handleVerificationFinished();
                            return;
                        }
                        return;
                    case 17:
                        IFVerificationParams params4 = (IFVerificationParams) msg.obj;
                        PackageManagerService.this.verifyIntentFiltersIfNeeded(params4.userId, params4.verifierUid, params4.replacing, params4.pkg);
                        return;
                    case 18:
                        int verificationId3 = msg.arg1;
                        IntentFilterVerificationState state3 = PackageManagerService.this.mIntentFilterVerificationStates.get(verificationId3);
                        if (state3 == null) {
                            Slog.w(PackageManagerService.TAG, "Invalid IntentFilter verification token " + verificationId3 + " received");
                            return;
                        }
                        state3.getUserId();
                        IntentFilterVerificationResponse response2 = (IntentFilterVerificationResponse) msg.obj;
                        state3.setVerifierResponse(response2.callerUid, response2.code);
                        int i6 = response2.code;
                        if (state3.isVerificationComplete()) {
                            PackageManagerService.this.mIntentFilterVerifier.receiveVerificationResponse(verificationId3);
                            return;
                        }
                        return;
                    case 19:
                        Process.setThreadPriority(0);
                        synchronized (PackageManagerService.this.mPackages) {
                            removeMessages(19);
                            PackageManagerService.this.mSettings.writePackageListLPr(msg.arg1);
                        }
                        Process.setThreadPriority(10);
                        return;
                    case 20:
                        InstantAppResolver.doInstantAppResolutionPhaseTwo(PackageManagerService.this.mContext, PackageManagerService.this.mInstantAppResolverConnection, (InstantAppRequest) msg.obj, PackageManagerService.this.mInstantAppInstallerActivity, PackageManagerService.this.mHandler);
                        return;
                    case 21:
                        int enableRollbackToken = msg.arg1;
                        int enableRollbackCode = msg.arg2;
                        InstallParams params5 = PackageManagerService.this.mPendingEnableRollback.get(enableRollbackToken);
                        if (params5 == null) {
                            Slog.w(PackageManagerService.TAG, "Invalid rollback enabled token " + enableRollbackToken + " received");
                            return;
                        }
                        PackageManagerService.this.mPendingEnableRollback.remove(enableRollbackToken);
                        if (enableRollbackCode != 1) {
                            Uri originUri3 = Uri.fromFile(params5.mArgs.origin.resolvedFile);
                            Slog.w(PackageManagerService.TAG, "Failed to enable rollback for " + originUri3);
                            Slog.w(PackageManagerService.TAG, "Continuing with installation of " + originUri3);
                        }
                        Trace.asyncTraceEnd(262144L, "enable_rollback", enableRollbackToken);
                        params5.handleRollbackEnabled();
                        return;
                    case 22:
                        int enableRollbackToken2 = msg.arg1;
                        InstallParams params6 = PackageManagerService.this.mPendingEnableRollback.get(enableRollbackToken2);
                        if (params6 != null) {
                            Uri originUri4 = Uri.fromFile(params6.mArgs.origin.resolvedFile);
                            Slog.w(PackageManagerService.TAG, "Enable rollback timed out for " + originUri4);
                            PackageManagerService.this.mPendingEnableRollback.remove(enableRollbackToken2);
                            Slog.w(PackageManagerService.TAG, "Continuing with installation of " + originUri4);
                            Trace.asyncTraceEnd(262144L, "enable_rollback", enableRollbackToken2);
                            params6.handleRollbackEnabled();
                            Intent rollbackTimeoutIntent = new Intent("android.intent.action.CANCEL_ENABLE_ROLLBACK");
                            rollbackTimeoutIntent.putExtra("android.content.pm.extra.ENABLE_ROLLBACK_TOKEN", enableRollbackToken2);
                            rollbackTimeoutIntent.addFlags(67108864);
                            PackageManagerService.this.mContext.sendBroadcastAsUser(rollbackTimeoutIntent, UserHandle.SYSTEM, "android.permission.PACKAGE_ROLLBACK_AGENT");
                            return;
                        }
                        return;
                    case 23:
                        synchronized (PackageManagerService.this.mInstallLock) {
                            InstallArgs args4 = (InstallArgs) msg.obj;
                            if (args4 != null) {
                                args4.doPostDeleteLI(true);
                            }
                        }
                        return;
                    case 24:
                        String packageName = (String) msg.obj;
                        if (packageName != null) {
                            PackageManagerService.this.notifyInstallObserver(packageName);
                            return;
                        }
                        return;
                    default:
                        return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.pm.PackageManagerService$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 extends PermissionManagerServiceInternal.PermissionCallback {
        AnonymousClass1() {
        }

        public /* synthetic */ void lambda$onGidsChanged$0$PackageManagerService$1(int appId, int userId) {
            PackageManagerService.this.killUid(appId, userId, PackageManagerService.KILL_APP_REASON_GIDS_CHANGED);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onGidsChanged(final int appId, final int userId) {
            PackageManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$1$7o99DenVu604GN9uaO7x0s_Ispw
                @Override // java.lang.Runnable
                public final void run() {
                    PackageManagerService.AnonymousClass1.this.lambda$onGidsChanged$0$PackageManagerService$1(appId, userId);
                }
            });
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onPermissionGranted(int uid, int userId) {
            PackageManagerService.this.mOnPermissionChangeListeners.onPermissionsChanged(uid);
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.writeRuntimePermissionsForUserLPr(userId, false);
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onInstallPermissionGranted() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.scheduleWriteSettingsLocked();
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onPermissionRevoked(int uid, int userId) {
            PackageManagerService.this.mOnPermissionChangeListeners.onPermissionsChanged(uid);
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.writeRuntimePermissionsForUserLPr(userId, true);
            }
            int appId = UserHandle.getAppId(uid);
            PackageManagerService.this.killUid(appId, userId, PackageManagerService.KILL_APP_REASON_PERMISSIONS_REVOKED);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onInstallPermissionRevoked() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.scheduleWriteSettingsLocked();
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onPermissionUpdated(int[] updatedUserIds, boolean sync) {
            synchronized (PackageManagerService.this.mPackages) {
                for (int userId : updatedUserIds) {
                    PackageManagerService.this.mSettings.writeRuntimePermissionsForUserLPr(userId, sync);
                }
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onInstallPermissionUpdated() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.scheduleWriteSettingsLocked();
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
        public void onPermissionRemoved() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.writeLPr();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackagePostInstall(PackageInstalledInfo res, boolean grantPermissions, boolean killApp, boolean virtualPreload, String[] grantedPermissions, List<String> whitelistedRestrictedPermissions, boolean launchedForRestore, String installerPackage, IPackageInstallObserver2 installObserver) {
        boolean z;
        String str;
        int[] firstUserIds;
        InstallArgs installArgs;
        String packageName;
        String installerPackageName;
        int[] iArr;
        int i;
        boolean succeeded = res.returnCode == 1;
        boolean update = (res.removedInfo == null || res.removedInfo.removedPackage == null) ? false : true;
        if (succeeded) {
            if (res.removedInfo != null) {
                res.removedInfo.sendPackageRemovedBroadcasts(killApp);
            }
            if (whitelistedRestrictedPermissions != null && !whitelistedRestrictedPermissions.isEmpty()) {
                this.mPermissionManager.setWhitelistedRestrictedPermissions(res.pkg, res.newUsers, whitelistedRestrictedPermissions, Process.myUid(), 2, this.mPermissionCallback);
            }
            if (grantPermissions) {
                int callingUid = Binder.getCallingUid();
                this.mPermissionManager.grantRequestedRuntimePermissions(res.pkg, res.newUsers, grantedPermissions, callingUid, this.mPermissionCallback);
            }
            if (res.installerPackageName != null) {
                str = res.installerPackageName;
            } else if (res.removedInfo != null) {
                str = res.removedInfo.installerPackageName;
            } else {
                str = null;
            }
            String installerPackageName2 = str;
            if (res.pkg.parentPackage != null) {
                int callingUid2 = Binder.getCallingUid();
                this.mPermissionManager.grantRuntimePermissionsGrantedToDisabledPackage(res.pkg, callingUid2, this.mPermissionCallback);
            }
            synchronized (this.mPackages) {
                try {
                    this.mInstantAppRegistry.onPackageInstalledLPw(res.pkg, res.newUsers);
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
            String packageName2 = res.pkg.applicationInfo.packageName;
            int[] firstUserIds2 = EMPTY_INT_ARRAY;
            int[] firstInstantUserIds = EMPTY_INT_ARRAY;
            int[] updateUserIds = EMPTY_INT_ARRAY;
            int[] instantUserIds = EMPTY_INT_ARRAY;
            boolean allNewUsers = res.origUsers == null || res.origUsers.length == 0;
            PackageSetting ps = (PackageSetting) res.pkg.mExtras;
            int[] iArr2 = res.newUsers;
            int length = iArr2.length;
            int[] instantUserIds2 = instantUserIds;
            int[] firstUserIds3 = firstUserIds2;
            int i2 = 0;
            int[] firstInstantUserIds2 = firstInstantUserIds;
            int[] firstInstantUserIds3 = updateUserIds;
            while (i2 < length) {
                int newUser = iArr2[i2];
                boolean isInstantApp = ps.getInstantApp(newUser);
                if (allNewUsers) {
                    if (isInstantApp) {
                        firstInstantUserIds2 = ArrayUtils.appendInt(firstInstantUserIds2, newUser);
                        iArr = iArr2;
                        i = length;
                    } else {
                        firstUserIds3 = ArrayUtils.appendInt(firstUserIds3, newUser);
                        iArr = iArr2;
                        i = length;
                    }
                } else {
                    boolean isNew = true;
                    int[] iArr3 = res.origUsers;
                    iArr = iArr2;
                    int length2 = iArr3.length;
                    i = length;
                    int i3 = 0;
                    while (true) {
                        if (i3 >= length2) {
                            break;
                        }
                        int i4 = length2;
                        int origUser = iArr3[i3];
                        if (origUser != newUser) {
                            i3++;
                            length2 = i4;
                        } else {
                            isNew = false;
                            break;
                        }
                    }
                    if (isNew) {
                        if (isInstantApp) {
                            firstInstantUserIds2 = ArrayUtils.appendInt(firstInstantUserIds2, newUser);
                        } else {
                            firstUserIds3 = ArrayUtils.appendInt(firstUserIds3, newUser);
                        }
                    } else if (isInstantApp) {
                        instantUserIds2 = ArrayUtils.appendInt(instantUserIds2, newUser);
                    } else {
                        firstInstantUserIds3 = ArrayUtils.appendInt(firstInstantUserIds3, newUser);
                    }
                }
                i2++;
                iArr2 = iArr;
                length = i;
            }
            if (res.pkg.staticSharedLibName == null) {
                this.mProcessLoggingHandler.invalidateProcessLoggingBaseApkHash(res.pkg.baseCodePath);
                int appId = UserHandle.getAppId(res.uid);
                boolean isSystem = res.pkg.applicationInfo.isSystemApp();
                int[] updateUserIds2 = firstInstantUserIds3;
                int[] firstInstantUserIds4 = firstInstantUserIds2;
                int[] firstUserIds4 = firstUserIds3;
                sendPackageAddedForNewUsers(packageName2, isSystem || virtualPreload, virtualPreload, appId, firstUserIds4, firstInstantUserIds4);
                Bundle extras = new Bundle(1);
                extras.putInt("android.intent.extra.UID", res.uid);
                if (update) {
                    extras.putBoolean("android.intent.extra.REPLACING", true);
                }
                installArgs = null;
                int[] firstInstantUserIds5 = instantUserIds2;
                sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName2, extras, 0, null, null, updateUserIds2, firstInstantUserIds5);
                if (installerPackageName2 != null) {
                    installerPackageName = installerPackageName2;
                    sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName2, extras, 0, installerPackageName2, null, updateUserIds2, instantUserIds2);
                } else {
                    installerPackageName = installerPackageName2;
                }
                String str2 = this.mRequiredVerifierPackage;
                boolean notifyVerifier = (str2 == null || str2.equals(installerPackageName)) ? false : true;
                if (notifyVerifier) {
                    sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName2, extras, 0, this.mRequiredVerifierPackage, null, updateUserIds2, instantUserIds2);
                }
                String str3 = this.mRequiredInstallerPackage;
                if (str3 != null) {
                    sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName2, extras, 16777216, str3, null, firstUserIds4, instantUserIds2);
                }
                if (update) {
                    sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", packageName2, extras, 0, null, null, updateUserIds2, instantUserIds2);
                    if (installerPackageName != null) {
                        sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", packageName2, extras, 0, installerPackageName, null, updateUserIds2, instantUserIds2);
                    }
                    if (notifyVerifier) {
                        sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", packageName2, extras, 0, this.mRequiredVerifierPackage, null, updateUserIds2, instantUserIds2);
                    }
                    sendPackageBroadcast("android.intent.action.MY_PACKAGE_REPLACED", null, null, 0, packageName2, null, updateUserIds2, instantUserIds2);
                    firstUserIds = firstUserIds4;
                    packageName = packageName2;
                } else if (!launchedForRestore || isSystemApp(res.pkg)) {
                    firstUserIds = firstUserIds4;
                    packageName = packageName2;
                } else {
                    firstUserIds = firstUserIds4;
                    packageName = packageName2;
                    sendFirstLaunchBroadcast(packageName, installerPackage, firstUserIds, firstInstantUserIds4);
                }
                if (isExternal(res.pkg)) {
                    if (!update) {
                        StorageManager storage = (StorageManager) this.mContext.getSystemService(StorageManager.class);
                        VolumeInfo volume = storage.findVolumeByUuid(res.pkg.applicationInfo.storageUuid.toString());
                        int packageExternalStorageType = getPackageExternalStorageType(volume, isExternal(res.pkg));
                        if (packageExternalStorageType != 0) {
                            StatsLog.write(181, packageExternalStorageType, res.pkg.packageName);
                        }
                    }
                    int[] uidArray = {res.pkg.applicationInfo.uid};
                    ArrayList<String> pkgList = new ArrayList<>(1);
                    pkgList.add(packageName);
                    sendResourcesChangedBroadcast(true, true, pkgList, uidArray, (IIntentReceiver) null);
                }
            } else {
                firstUserIds = firstUserIds3;
                installArgs = null;
                packageName = packageName2;
                if (!ArrayUtils.isEmpty(res.libraryConsumers)) {
                    for (int i5 = 0; i5 < res.libraryConsumers.size(); i5++) {
                        PackageParser.Package pkg = res.libraryConsumers.get(i5);
                        sendPackageChangedBroadcast(pkg.packageName, false, new ArrayList<>(Collections.singletonList(pkg.packageName)), pkg.applicationInfo.uid);
                    }
                }
            }
            if (firstUserIds != null && firstUserIds.length > 0) {
                for (int userId : firstUserIds) {
                    if (packageIsBrowser(packageName, userId)) {
                        synchronized (this.mPackages) {
                            PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName);
                            if (pkgSetting.getInstallReason(userId) == 2) {
                                installArgs = null;
                            } else {
                                installArgs = null;
                                setDefaultBrowserAsyncLPw(null, userId);
                            }
                        }
                    }
                    this.mPermissionManager.restoreDelayedRuntimePermissions(packageName, UserHandle.of(userId));
                    updateDefaultHomeNotLocked(userId);
                }
            }
            if (!allNewUsers || update) {
                notifyPackageChanged(packageName, res.uid);
            } else {
                notifyPackageAdded(packageName, res.uid);
            }
            EventLog.writeEvent((int) EventLogTags.UNKNOWN_SOURCES_ENABLED, getUnknownSourcesSettings());
            if (res.removedInfo != null) {
                installArgs = res.removedInfo.args;
            }
            InstallArgs args = installArgs;
            if (args == null) {
                VMRuntime.getRuntime().requestConcurrentGC();
            } else if (!killApp) {
                scheduleDeferredNoKillPostDelete(args);
            } else {
                synchronized (this.mInstallLock) {
                    args.doPostDeleteLI(true);
                }
            }
            for (int userId2 : firstUserIds) {
                PackageInfo info = getPackageInfo(packageName, 0, userId2);
                if (info != null) {
                    this.mDexManager.notifyPackageInstalled(info, userId2);
                }
            }
            z = false;
        } else {
            z = false;
        }
        boolean deferInstallObserver = (succeeded && update && !killApp) ? true : z;
        if (deferInstallObserver) {
            scheduleDeferredNoKillInstallObserver(res, installObserver);
        } else {
            notifyInstallObserver(res, installObserver);
        }
    }

    public void notifyPackagesReplacedReceived(String[] packages) {
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        for (String packageName : packages) {
            PackageSetting setting = this.mSettings.mPackages.get(packageName);
            if (setting != null && filterAppAccessLPr(setting, callingUid, callingUserId)) {
                notifyInstallObserver(packageName);
            }
        }
    }

    public List<SplitPermissionInfoParcelable> getSplitPermissions() {
        return PermissionManager.splitPermissionInfoListToParcelableList(SystemConfig.getInstance().getSplitPermissions());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyInstallObserver(String packageName) {
        Pair<PackageInstalledInfo, IPackageInstallObserver2> pair = this.mNoKillInstallObservers.remove(packageName);
        if (pair != null) {
            notifyInstallObserver((PackageInstalledInfo) pair.first, (IPackageInstallObserver2) pair.second);
        }
    }

    private void notifyInstallObserver(PackageInstalledInfo info, IPackageInstallObserver2 installObserver) {
        if (installObserver != null) {
            try {
                Bundle extras = extrasForInstallResult(info);
                installObserver.onPackageInstalled(info.name, info.returnCode, info.returnMsg, extras);
            } catch (RemoteException e) {
                Slog.i(TAG, "Observer no longer exists.");
            }
        }
    }

    private void scheduleDeferredNoKillPostDelete(InstallArgs args) {
        Message message = this.mHandler.obtainMessage(23, args);
        this.mHandler.sendMessageDelayed(message, BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
    }

    private void scheduleDeferredNoKillInstallObserver(PackageInstalledInfo info, IPackageInstallObserver2 observer) {
        String packageName = info.pkg.packageName;
        this.mNoKillInstallObservers.put(packageName, Pair.create(info, observer));
        Message message = this.mHandler.obtainMessage(24, packageName);
        this.mHandler.sendMessageDelayed(message, 500L);
    }

    private static int getPackageExternalStorageType(VolumeInfo packageVolume, boolean packageIsExternal) {
        DiskInfo disk;
        if (packageVolume != null && (disk = packageVolume.getDisk()) != null) {
            if (disk.isSd()) {
                return 1;
            }
            if (disk.isUsb()) {
                return 2;
            }
            if (packageIsExternal) {
                return 3;
            }
            return 0;
        }
        return 0;
    }

    Bundle extrasForInstallResult(PackageInstalledInfo res) {
        int i = res.returnCode;
        if (i == -112) {
            Bundle extras = new Bundle();
            extras.putString("android.content.pm.extra.FAILURE_EXISTING_PERMISSION", res.origPermission);
            extras.putString("android.content.pm.extra.FAILURE_EXISTING_PACKAGE", res.origPackage);
            return extras;
        }
        boolean z = true;
        if (i != 1) {
            return null;
        }
        Bundle extras2 = new Bundle();
        extras2.putBoolean("android.intent.extra.REPLACING", (res.removedInfo == null || res.removedInfo.removedPackage == null) ? false : false);
        return extras2;
    }

    void scheduleWriteSettingsLocked() {
        if (!this.mHandler.hasMessages(13)) {
            this.mHandler.sendEmptyMessageDelayed(13, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    void scheduleWritePackageListLocked(int userId) {
        if (!this.mHandler.hasMessages(19)) {
            Message msg = this.mHandler.obtainMessage(19);
            msg.arg1 = userId;
            this.mHandler.sendMessageDelayed(msg, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    void scheduleWritePackageRestrictionsLocked(UserHandle user) {
        int userId = user == null ? -1 : user.getIdentifier();
        scheduleWritePackageRestrictionsLocked(userId);
    }

    void scheduleWritePackageRestrictionsLocked(int userId) {
        int[] userIds = userId == -1 ? sUserManager.getUserIds() : new int[]{userId};
        for (int nextUserId : userIds) {
            if (!sUserManager.exists(nextUserId)) {
                return;
            }
            this.mDirtyUsers.add(Integer.valueOf(nextUserId));
            if (!this.mHandler.hasMessages(14)) {
                this.mHandler.sendEmptyMessageDelayed(14, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
            }
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.pm.PackageManagerService, java.lang.Object, android.os.IBinder] */
    public static PackageManagerService main(Context context, Installer installer, boolean factoryTest, boolean onlyCore) {
        PackageManagerServiceCompilerMapping.checkProperties();
        ?? packageManagerService = new PackageManagerService(context, installer, factoryTest, onlyCore);
        packageManagerService.enableSystemUserPackages();
        ServiceManager.addService("package", (IBinder) packageManagerService);
        Objects.requireNonNull(packageManagerService);
        ServiceManager.addService("package_native", new PackageManagerNative(packageManagerService, null));
        return packageManagerService;
    }

    private boolean isSystemUserPackagesBlacklistSupported() {
        return Resources.getSystem().getBoolean(17891553);
    }

    private void enableSystemUserPackages() {
        boolean install;
        if (!isSystemUserPackagesBlacklistSupported()) {
            Log.i(TAG, "Skipping system user blacklist since config_systemUserPackagesBlacklistSupported is false");
            return;
        }
        boolean isHeadlessSystemUserMode = UserManager.isHeadlessSystemUserMode();
        if (!isHeadlessSystemUserMode && !UserManager.isSplitSystemUser()) {
            Log.i(TAG, "Skipping system user blacklist on 'regular' device type");
            return;
        }
        Log.i(TAG, "blacklisting packages for system user");
        Set<String> enableApps = new ArraySet<>();
        AppsQueryHelper queryHelper = new AppsQueryHelper(this);
        List<String> allAps = queryHelper.queryApps(0, false, UserHandle.SYSTEM);
        if (isHeadlessSystemUserMode) {
            enableApps.addAll(allAps);
        } else {
            enableApps.addAll(queryHelper.queryApps(AppsQueryHelper.GET_NON_LAUNCHABLE_APPS | AppsQueryHelper.GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM | AppsQueryHelper.GET_IMES, true, UserHandle.SYSTEM));
            enableApps.addAll(queryHelper.queryApps(AppsQueryHelper.GET_REQUIRED_FOR_SYSTEM_USER, false, UserHandle.SYSTEM));
            ArraySet<String> whitelistedSystemUserApps = SystemConfig.getInstance().getSystemUserWhitelistedApps();
            enableApps.addAll(whitelistedSystemUserApps);
            Log.i(TAG, "Whitelisted packages: " + whitelistedSystemUserApps);
        }
        ArraySet<String> blacklistedSystemUserApps = SystemConfig.getInstance().getSystemUserBlacklistedApps();
        enableApps.removeAll(blacklistedSystemUserApps);
        Log.i(TAG, "Blacklisted packages: " + blacklistedSystemUserApps);
        int allAppsSize = allAps.size();
        synchronized (this.mPackages) {
            for (int i = 0; i < allAppsSize; i++) {
                String pName = allAps.get(i);
                PackageSetting pkgSetting = this.mSettings.mPackages.get(pName);
                if (pkgSetting != null && pkgSetting.getInstalled(0) != (install = enableApps.contains(pName))) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(install ? "Installing " : "Uninstalling ");
                    sb.append(pName);
                    sb.append(" for system user");
                    Log.i(TAG, sb.toString());
                    pkgSetting.setInstalled(install, 0);
                }
            }
            scheduleWritePackageRestrictionsLocked(0);
        }
    }

    private static void getDefaultDisplayMetrics(Context context, DisplayMetrics metrics) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService("display");
        displayManager.getDisplay(0).getMetrics(metrics);
    }

    private static void requestCopyPreoptedFiles() {
        if (SystemProperties.getInt("ro.cp_system_other_odex", 0) == 1) {
            SystemProperties.set("sys.cppreopt", "requested");
            long timeStart = SystemClock.uptimeMillis();
            long timeEnd = 100000 + timeStart;
            long timeNow = timeStart;
            while (true) {
                if (!SystemProperties.get("sys.cppreopt").equals("finished")) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                    }
                    timeNow = SystemClock.uptimeMillis();
                    if (timeNow > timeEnd) {
                        SystemProperties.set("sys.cppreopt", "timed-out");
                        Slog.wtf(TAG, "cppreopt did not finish!");
                        break;
                    }
                } else {
                    break;
                }
            }
            Slog.i(TAG, "cppreopts took " + (timeNow - timeStart) + " ms");
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:132:0x05ca A[Catch: all -> 0x1015, TRY_LEAVE, TryCatch #14 {all -> 0x1015, blocks: (B:130:0x05bd, B:132:0x05ca, B:133:0x0614, B:137:0x061d, B:138:0x063a, B:142:0x0643, B:143:0x0663, B:147:0x066c, B:148:0x068d, B:152:0x0696, B:153:0x06d6, B:157:0x06df, B:158:0x0700, B:162:0x0709, B:163:0x072a, B:167:0x0733, B:168:0x0754, B:172:0x075d, B:174:0x0781, B:175:0x078b, B:177:0x0791, B:179:0x079b, B:181:0x07a1, B:182:0x07ad, B:184:0x07b3, B:187:0x07c5, B:189:0x07d1, B:191:0x07db, B:193:0x082d, B:195:0x0839, B:196:0x085b, B:198:0x0868, B:200:0x0870, B:205:0x0885, B:203:0x0877, B:209:0x089e, B:213:0x08de, B:216:0x08f8, B:218:0x0903, B:220:0x0907, B:222:0x0930, B:224:0x0947, B:236:0x09d6, B:238:0x09e4, B:240:0x09ec, B:241:0x09f7, B:225:0x096a, B:226:0x0985, B:228:0x09a6, B:234:0x09ba, B:243:0x0a0d, B:245:0x0a15, B:247:0x0a26, B:249:0x0a4f, B:283:0x0b7f, B:285:0x0b9d, B:291:0x0bc6, B:289:0x0ba3, B:250:0x0a61, B:252:0x0a67, B:253:0x0a77, B:255:0x0a7d, B:282:0x0b71, B:258:0x0a89, B:260:0x0a91, B:280:0x0b60, B:263:0x0aa1, B:265:0x0aa9, B:266:0x0abf, B:268:0x0ac7, B:269:0x0add, B:271:0x0ae7, B:272:0x0afb, B:274:0x0b05, B:275:0x0b1b, B:277:0x0b25, B:278:0x0b38, B:292:0x0bce, B:296:0x0c16, B:299:0x0c2e, B:301:0x0c47, B:302:0x0c95, B:304:0x0c9b, B:306:0x0caa, B:308:0x0cb0, B:310:0x0cb8, B:311:0x0cbe, B:315:0x0cd0, B:316:0x0cd4, B:322:0x0d22, B:323:0x0d49, B:325:0x0d5e, B:327:0x0d62, B:329:0x0d66, B:330:0x0d71, B:332:0x0d77, B:333:0x0d8a, B:337:0x0d96, B:340:0x0dc1, B:341:0x0dca, B:343:0x0dd4, B:345:0x0de8, B:346:0x0def, B:347:0x0df2, B:349:0x0df8, B:351:0x0dfc, B:353:0x0e0e, B:357:0x0e2a, B:356:0x0e23, B:360:0x0e35, B:362:0x0e63, B:364:0x0e7f, B:366:0x0e8e, B:368:0x0eaf, B:369:0x0ec7, B:371:0x0ecd, B:374:0x0eda, B:376:0x0edd, B:378:0x0ee9, B:380:0x0ef3, B:386:0x0f17, B:383:0x0efe, B:388:0x0f2a, B:390:0x0f3e, B:392:0x0f42, B:393:0x0f58, B:395:0x0f7a, B:397:0x0f86, B:398:0x0fa4, B:400:0x0fb1, B:401:0x0fbf, B:394:0x0f75, B:365:0x0e8b, B:367:0x0ea0, B:295:0x0c10, B:212:0x08db, B:406:0x1001, B:407:0x1008, B:413:0x1013), top: B:456:0x05bd, inners: #13 }] */
    /* JADX WARN: Removed duplicated region for block: B:405:0x0ffd  */
    /* JADX WARN: Type inference failed for: r2v106 */
    /* JADX WARN: Type inference failed for: r2v107, types: [int] */
    /* JADX WARN: Type inference failed for: r3v113 */
    /* JADX WARN: Type inference failed for: r3v78, types: [int] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public PackageManagerService(android.content.Context r62, com.android.server.pm.Installer r63, boolean r64, boolean r65) {
        /*
            Method dump skipped, instructions count: 4139
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.<init>(android.content.Context, com.android.server.pm.Installer, boolean, boolean):void");
    }

    public /* synthetic */ void lambda$new$0$PackageManagerService(List deferPackages, int storageFlags) {
        TimingsTraceLog traceLog = new TimingsTraceLog("SystemServerTimingAsync", 262144L);
        traceLog.traceBegin("AppDataFixup");
        try {
            this.mInstaller.fixupAppData(StorageManager.UUID_PRIVATE_INTERNAL, 3);
        } catch (Installer.InstallerException e) {
            Slog.w(TAG, "Trouble fixing GIDs", e);
        }
        traceLog.traceEnd();
        traceLog.traceBegin("AppDataPrepare");
        if (deferPackages == null || deferPackages.isEmpty()) {
            return;
        }
        int count = 0;
        Iterator it = deferPackages.iterator();
        while (it.hasNext()) {
            String pkgName = (String) it.next();
            PackageParser.Package pkg = null;
            synchronized (this.mPackages) {
                PackageSetting ps = this.mSettings.getPackageLPr(pkgName);
                if (ps != null && ps.getInstalled(0)) {
                    pkg = ps.pkg;
                }
            }
            if (pkg != null) {
                synchronized (this.mInstallLock) {
                    prepareAppDataAndMigrateLIF(pkg, 0, storageFlags, true);
                }
                count++;
            }
        }
        traceLog.traceEnd();
        Slog.i(TAG, "Deferred reconcileAppsData finished " + count + " packages");
    }

    private void installSystemStubPackages(List<String> systemStubPackageNames, int scanFlags) {
        int i = systemStubPackageNames.size();
        while (true) {
            i--;
            if (i < 0) {
                break;
            }
            String packageName = systemStubPackageNames.get(i);
            if (this.mSettings.isDisabledSystemPackageLPr(packageName)) {
                systemStubPackageNames.remove(i);
            } else {
                PackageParser.Package pkg = this.mPackages.get(packageName);
                if (pkg == null) {
                    systemStubPackageNames.remove(i);
                } else {
                    PackageSetting ps = this.mSettings.mPackages.get(packageName);
                    if (ps != null) {
                        int enabledState = ps.getEnabled(0);
                        if (enabledState == 3) {
                            systemStubPackageNames.remove(i);
                        }
                    }
                    try {
                        installStubPackageLI(pkg, 0, scanFlags);
                        ps.setEnabled(0, 0, PLATFORM_PACKAGE_NAME);
                        systemStubPackageNames.remove(i);
                    } catch (PackageManagerException e) {
                        Slog.e(TAG, "Failed to parse uncompressed system package: " + e.getMessage());
                    }
                }
            }
        }
        int i2 = systemStubPackageNames.size();
        for (int i3 = i2 - 1; i3 >= 0; i3 += -1) {
            String pkgName = systemStubPackageNames.get(i3);
            this.mSettings.mPackages.get(pkgName).setEnabled(2, 0, PLATFORM_PACKAGE_NAME);
            PackageManagerServiceUtils.logCriticalInfo(6, "Stub disabled; pkg: " + pkgName);
        }
    }

    private boolean enableCompressedPackage(PackageParser.Package stubPkg) {
        PackageFreezer freezer;
        int parseFlags = this.mDefParseFlags | Integer.MIN_VALUE | 64;
        synchronized (this.mInstallLock) {
            try {
                freezer = freezePackage(stubPkg.packageName, "setEnabledSetting");
                try {
                    PackageParser.Package pkg = installStubPackageLI(stubPkg, parseFlags, 0);
                    synchronized (this.mPackages) {
                        prepareAppDataAfterInstallLIF(pkg);
                        try {
                            updateSharedLibrariesLocked(pkg, null, this.mPackages);
                        } catch (PackageManagerException e) {
                            Slog.e(TAG, "updateAllSharedLibrariesLPw failed: ", e);
                        }
                        this.mPermissionManager.updatePermissions(pkg.packageName, pkg, true, this.mPackages.values(), this.mPermissionCallback);
                        this.mSettings.writeLPr();
                    }
                    if (freezer != null) {
                        $closeResource(null, freezer);
                    }
                    clearAppDataLIF(pkg, -1, 39);
                    this.mDexManager.notifyPackageUpdated(pkg.packageName, pkg.baseCodePath, pkg.splitCodePaths);
                } finally {
                }
            } catch (PackageManagerException e2) {
                try {
                    freezer = freezePackage(stubPkg.packageName, "setEnabledSetting");
                    try {
                    } finally {
                        try {
                            throw th;
                        } finally {
                        }
                    }
                } catch (PackageManagerException pme) {
                    Slog.wtf(TAG, "Failed to restore system package:" + stubPkg.packageName, pme);
                    synchronized (this.mPackages) {
                        try {
                            PackageSetting stubPs = this.mSettings.mPackages.get(stubPkg.packageName);
                            if (stubPs != null) {
                                stubPs.setEnabled(2, 0, PLATFORM_PACKAGE_NAME);
                            }
                            this.mSettings.writeLPr();
                            return false;
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                }
                synchronized (this.mPackages) {
                    enableSystemPackageLPw(stubPkg);
                    installPackageFromSystemLIF(stubPkg.codePath, null, null, null, true);
                    if (freezer != null) {
                        $closeResource(null, freezer);
                    }
                    synchronized (this.mPackages) {
                        try {
                            PackageSetting stubPs2 = this.mSettings.mPackages.get(stubPkg.packageName);
                            if (stubPs2 != null) {
                                stubPs2.setEnabled(2, 0, PLATFORM_PACKAGE_NAME);
                            }
                            this.mSettings.writeLPr();
                            return false;
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                }
            }
        }
        return true;
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

    private PackageParser.Package installStubPackageLI(PackageParser.Package stubPkg, int parseFlags, int scanFlags) throws PackageManagerException {
        if (DEBUG_COMPRESSION) {
            Slog.i(TAG, "Uncompressing system stub; pkg: " + stubPkg.packageName);
        }
        File scanFile = decompressPackage(stubPkg.packageName, stubPkg.codePath);
        if (scanFile == null) {
            throw new PackageManagerException("Unable to decompress stub at " + stubPkg.codePath);
        }
        synchronized (this.mPackages) {
            this.mSettings.disableSystemPackageLPw(stubPkg.packageName, true);
        }
        removePackageLI(stubPkg, true);
        try {
            return scanPackageTracedLI(scanFile, parseFlags, scanFlags, 0L, (UserHandle) null);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to install compressed system package:" + stubPkg.packageName, e);
            removeCodePathLI(scanFile);
            throw e;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:13:0x0057, code lost:
        com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo(6, "Failed to decompress; pkg: " + r18 + ", file: " + r14);
     */
    /* JADX WARN: Removed duplicated region for block: B:23:0x0098  */
    /* JADX WARN: Removed duplicated region for block: B:35:0x00cf  */
    /* JADX WARN: Removed duplicated region for block: B:40:0x00da A[RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private java.io.File decompressPackage(java.lang.String r18, java.lang.String r19) {
        /*
            Method dump skipped, instructions count: 251
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.decompressPackage(java.lang.String, java.lang.String):java.io.File");
    }

    @GuardedBy({"mPackages"})
    private void updateInstantAppInstallerLocked(String modifiedPackage) {
        ActivityInfo activityInfo = this.mInstantAppInstallerActivity;
        if (activityInfo != null && !activityInfo.getComponentName().getPackageName().equals(modifiedPackage)) {
            return;
        }
        setUpInstantAppInstallerActivityLP(getInstantAppInstallerLPr());
    }

    private static File preparePackageParserCache() {
        File[] listFilesOrEmpty;
        if (Build.IS_ENG) {
            return null;
        }
        if (SystemProperties.getBoolean("pm.boot.disable_package_cache", false)) {
            Slog.i(TAG, "Disabling package parser cache due to system property.");
            return null;
        }
        File cacheBaseDir = Environment.getPackageCacheDirectory();
        if (FileUtils.createDir(cacheBaseDir)) {
            String cacheName = SystemProperties.digestOf(new String[]{"ro.build.fingerprint", "persist.sys.isolated_storage", "sys.isolated_storage_snapshot"});
            for (File cacheDir : FileUtils.listFilesOrEmpty(cacheBaseDir)) {
                if (Objects.equals(cacheName, cacheDir.getName())) {
                    Slog.d(TAG, "Keeping known cache " + cacheDir.getName());
                } else {
                    Slog.d(TAG, "Destroying unknown cache " + cacheDir.getName());
                    FileUtils.deleteContentsAndDir(cacheDir);
                }
            }
            File cacheDir2 = FileUtils.createDir(cacheBaseDir, cacheName);
            if (cacheDir2 == null) {
                Slog.wtf(TAG, "Cache directory cannot be created - wiping base dir " + cacheBaseDir);
                FileUtils.deleteContentsAndDir(cacheBaseDir);
                return null;
            } else if (Build.IS_USERDEBUG && Build.VERSION.INCREMENTAL.startsWith("eng.")) {
                Slog.w(TAG, "Wiping cache directory because the system partition changed.");
                File frameworkDir = new File(Environment.getRootDirectory(), "framework");
                if (cacheDir2.lastModified() < frameworkDir.lastModified()) {
                    FileUtils.deleteContents(cacheBaseDir);
                    return FileUtils.createDir(cacheBaseDir, cacheName);
                }
                return cacheDir2;
            } else {
                return cacheDir2;
            }
        }
        return null;
    }

    public boolean isFirstBoot() {
        return this.mFirstBoot;
    }

    public boolean isOnlyCoreApps() {
        return this.mOnlyCore;
    }

    public boolean isDeviceUpgrading() {
        return this.mIsUpgrade || SystemProperties.getBoolean("persist.pm.mock-upgrade", false);
    }

    private String getRequiredButNotReallyRequiredVerifierLPr() {
        Intent intent = new Intent("android.intent.action.PACKAGE_NEEDS_VERIFICATION");
        List<ResolveInfo> matches = queryIntentReceiversInternal(intent, PACKAGE_MIME_TYPE, 1835008, 0, false);
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        }
        if (matches.size() == 0) {
            Log.e(TAG, "There should probably be a verifier, but, none were found");
            return null;
        }
        throw new RuntimeException("There must be exactly one verifier; found " + matches);
    }

    private String getRequiredSharedLibraryLPr(String name, int version) {
        String packageName;
        synchronized (this.mPackages) {
            SharedLibraryInfo libraryInfo = getSharedLibraryInfoLPr(name, version);
            if (libraryInfo == null) {
                throw new IllegalStateException("Missing required shared library:" + name);
            }
            packageName = libraryInfo.getPackageName();
            if (packageName == null) {
                throw new IllegalStateException("Expected a package for shared library " + name);
            }
        }
        return packageName;
    }

    private String getRequiredInstallerLPr() {
        Intent intent = new Intent("android.intent.action.INSTALL_PACKAGE");
        intent.addCategory("android.intent.category.DEFAULT");
        intent.setDataAndType(Uri.parse("content://com.example/foo.apk"), PACKAGE_MIME_TYPE);
        List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, PACKAGE_MIME_TYPE, 1835008, 0);
        if (matches.size() == 1) {
            ResolveInfo resolveInfo = matches.get(0);
            if (!resolveInfo.activityInfo.applicationInfo.isPrivilegedApp()) {
                throw new RuntimeException("The installer must be a privileged app");
            }
            return matches.get(0).getComponentInfo().packageName;
        }
        throw new RuntimeException("There must be exactly one installer; found " + matches);
    }

    private String getRequiredUninstallerLPr() {
        Intent intent = new Intent("android.intent.action.UNINSTALL_PACKAGE");
        intent.addCategory("android.intent.category.DEFAULT");
        intent.setData(Uri.fromParts("package", "foo.bar", null));
        ResolveInfo resolveInfo = resolveIntent(intent, null, 1835008, 0);
        if (resolveInfo == null || this.mResolveActivity.name.equals(resolveInfo.getComponentInfo().name)) {
            throw new RuntimeException("There must be exactly one uninstaller; found " + resolveInfo);
        }
        return resolveInfo.getComponentInfo().packageName;
    }

    private String getRequiredPermissionControllerLPr() {
        Intent intent = new Intent("android.intent.action.MANAGE_PERMISSIONS");
        intent.addCategory("android.intent.category.DEFAULT");
        List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null, 1835008, 0);
        if (matches.size() == 1) {
            ResolveInfo resolveInfo = matches.get(0);
            if (!resolveInfo.activityInfo.applicationInfo.isPrivilegedApp()) {
                throw new RuntimeException("The permissions manager must be a privileged app");
            }
            return matches.get(0).getComponentInfo().packageName;
        }
        throw new RuntimeException("There must be exactly one permissions manager; found " + matches);
    }

    private ComponentName getIntentFilterVerifierComponentNameLPr() {
        Intent intent = new Intent("android.intent.action.INTENT_FILTER_NEEDS_VERIFICATION");
        List<ResolveInfo> matches = queryIntentReceiversInternal(intent, PACKAGE_MIME_TYPE, 1835008, 0, false);
        ResolveInfo best = null;
        int N = matches.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo cur = matches.get(i);
            String packageName = cur.getComponentInfo().packageName;
            if (checkPermission("android.permission.INTENT_FILTER_VERIFICATION_AGENT", packageName, 0) == 0 && (best == null || cur.priority > best.priority)) {
                best = cur;
            }
        }
        if (best != null) {
            return best.getComponentInfo().getComponentName();
        }
        Slog.w(TAG, "Intent filter verifier not found");
        return null;
    }

    public ComponentName getInstantAppResolverComponent() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (this.mPackages) {
            Pair<ComponentName, String> instantAppResolver = getInstantAppResolverLPr();
            if (instantAppResolver == null) {
                return null;
            }
            return (ComponentName) instantAppResolver.first;
        }
    }

    private Pair<ComponentName, String> getInstantAppResolverLPr() {
        String[] packageArray = this.mContext.getResources().getStringArray(17236029);
        if (packageArray.length == 0 && !Build.IS_DEBUGGABLE) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Ephemeral resolver NOT found; empty package list");
            }
            return null;
        }
        int callingUid = Binder.getCallingUid();
        int resolveFlags = (!Build.IS_DEBUGGABLE ? 1048576 : 0) | 786432;
        Intent resolverIntent = new Intent("android.intent.action.RESOLVE_INSTANT_APP_PACKAGE");
        List<ResolveInfo> resolvers = queryIntentServicesInternal(resolverIntent, null, resolveFlags, 0, callingUid, false);
        int N = resolvers.size();
        if (N == 0) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Ephemeral resolver NOT found; no matching intent filters");
            }
            return null;
        }
        Set<String> possiblePackages = new ArraySet<>(Arrays.asList(packageArray));
        for (int i = 0; i < N; i++) {
            ResolveInfo info = resolvers.get(i);
            if (info.serviceInfo != null) {
                String packageName = info.serviceInfo.packageName;
                if (!possiblePackages.contains(packageName) && !Build.IS_DEBUGGABLE) {
                    if (DEBUG_INSTANT) {
                        Slog.d(TAG, "Ephemeral resolver not in allowed package list; pkg: " + packageName + ", info:" + info);
                    }
                } else {
                    if (DEBUG_INSTANT) {
                        Slog.v(TAG, "Ephemeral resolver found; pkg: " + packageName + ", info:" + info);
                    }
                    return new Pair<>(new ComponentName(packageName, info.serviceInfo.name), "android.intent.action.RESOLVE_INSTANT_APP_PACKAGE");
                }
            }
        }
        if (DEBUG_INSTANT) {
            Slog.v(TAG, "Ephemeral resolver NOT found");
            return null;
        }
        return null;
    }

    @GuardedBy({"mPackages"})
    private ActivityInfo getInstantAppInstallerLPr() {
        String[] orderedActions;
        if (Build.IS_ENG) {
            orderedActions = new String[]{"android.intent.action.INSTALL_INSTANT_APP_PACKAGE_TEST", "android.intent.action.INSTALL_INSTANT_APP_PACKAGE"};
        } else {
            orderedActions = new String[]{"android.intent.action.INSTALL_INSTANT_APP_PACKAGE"};
        }
        int resolveFlags = 786944 | (!Build.IS_ENG ? 1048576 : 0);
        Intent intent = new Intent();
        intent.addCategory("android.intent.category.DEFAULT");
        intent.setDataAndType(Uri.fromFile(new File("foo.apk")), PACKAGE_MIME_TYPE);
        List<ResolveInfo> matches = null;
        for (String action : orderedActions) {
            intent.setAction(action);
            matches = queryIntentActivitiesInternal(intent, PACKAGE_MIME_TYPE, resolveFlags, 0);
            if (!matches.isEmpty()) {
                break;
            }
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Instant App installer not found with " + action);
            }
        }
        Iterator<ResolveInfo> iter = matches.iterator();
        while (iter.hasNext()) {
            ResolveInfo rInfo = iter.next();
            if (checkPermission("android.permission.INSTALL_PACKAGES", rInfo.activityInfo.packageName, 0) != 0 && !Build.IS_ENG) {
                iter.remove();
            }
        }
        if (matches.size() == 0) {
            return null;
        }
        if (matches.size() == 1) {
            return (ActivityInfo) matches.get(0).getComponentInfo();
        }
        throw new RuntimeException("There must be at most one ephemeral installer; found " + matches);
    }

    private ComponentName getInstantAppResolverSettingsLPr(ComponentName resolver) {
        Intent intent = new Intent("android.intent.action.INSTANT_APP_RESOLVER_SETTINGS").addCategory("android.intent.category.DEFAULT").setPackage(resolver.getPackageName());
        List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null, 786432, 0);
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(0).getComponentInfo().getComponentName();
    }

    @GuardedBy({"mPackages"})
    private void primeDomainVerificationsLPw(int userId) {
        SystemConfig systemConfig = SystemConfig.getInstance();
        ArraySet<String> packages = systemConfig.getLinkedApps();
        Iterator<String> it = packages.iterator();
        while (it.hasNext()) {
            String packageName = it.next();
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg != null) {
                if (!pkg.isSystem()) {
                    Slog.w(TAG, "Non-system app '" + packageName + "' in sysconfig <app-link>");
                } else {
                    ArraySet<String> domains = null;
                    Iterator it2 = pkg.activities.iterator();
                    while (it2.hasNext()) {
                        PackageParser.Activity a = (PackageParser.Activity) it2.next();
                        Iterator it3 = a.intents.iterator();
                        while (it3.hasNext()) {
                            PackageParser.ActivityIntentInfo filter = (PackageParser.ActivityIntentInfo) it3.next();
                            if (hasValidDomains(filter)) {
                                if (domains == null) {
                                    domains = new ArraySet<>();
                                }
                                domains.addAll(filter.getHostsList());
                            }
                        }
                    }
                    if (domains != null && domains.size() > 0) {
                        IntentFilterVerificationInfo ivi = this.mSettings.createIntentFilterVerificationIfNeededLPw(packageName, domains);
                        ivi.setStatus(0);
                        this.mSettings.updateIntentFilterVerificationStatusLPw(packageName, 2, userId);
                    } else {
                        Slog.w(TAG, "Sysconfig <app-link> package '" + packageName + "' does not handle web links");
                    }
                }
            } else {
                Slog.w(TAG, "Unknown package " + packageName + " in sysconfig <app-link>");
            }
        }
        scheduleWritePackageRestrictionsLocked(userId);
        scheduleWriteSettingsLocked();
    }

    private boolean packageIsBrowser(String packageName, int userId) {
        List<ResolveInfo> list = queryIntentActivitiesInternal(sBrowserIntent, null, 131072, userId);
        int N = list.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo info = list.get(i);
            if (info.priority >= 0 && packageName.equals(info.activityInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException) && !(e instanceof IllegalArgumentException)) {
                Slog.wtf(TAG, "Package Manager Crash", e);
            }
            throw e;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean canViewInstantApps(int callingUid, int userId) {
        if (callingUid < 10000 || this.mContext.checkCallingOrSelfPermission("android.permission.ACCESS_INSTANT_APPS") == 0) {
            return true;
        }
        if (this.mContext.checkCallingOrSelfPermission("android.permission.VIEW_INSTANT_APPS") == 0) {
            ComponentName homeComponent = getDefaultHomeActivity(userId);
            if (homeComponent != null && isCallerSameApp(homeComponent.getPackageName(), callingUid)) {
                return true;
            }
            String str = this.mAppPredictionServicePackage;
            if (str != null && isCallerSameApp(str, callingUid)) {
                return true;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PackageInfo generatePackageInfo(final PackageSetting ps, int flags, final int userId) {
        int flags2;
        Set<String> permissions;
        if (sUserManager.exists(userId) && ps != null) {
            int callingUid = Binder.getCallingUid();
            if (filterAppAccessLPr(ps, callingUid, userId)) {
                return null;
            }
            if ((flags & 8192) != 0 && ps.isSystem()) {
                flags2 = flags | 4194304;
            } else {
                flags2 = flags;
            }
            PackageUserState state = ps.readUserState(userId);
            PackageParser.Package p = ps.pkg;
            if (p != null) {
                PermissionsState permissionsState = ps.getPermissionsState();
                int[] gids = (flags2 & 256) == 0 ? EMPTY_INT_ARRAY : permissionsState.computeGids(userId);
                Set<String> permissions2 = ArrayUtils.isEmpty(p.requestedPermissions) ? Collections.emptySet() : permissionsState.getPermissions(userId);
                if (!state.instantApp) {
                    permissions = permissions2;
                } else {
                    Set<String> arraySet = new ArraySet<>(permissions2);
                    arraySet.removeIf(new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$O3zXDb2FgkdfYNqsCHEFfFF-nEA
                        @Override // java.util.function.Predicate
                        public final boolean test(Object obj) {
                            return PackageManagerService.this.lambda$generatePackageInfo$1$PackageManagerService(userId, ps, (String) obj);
                        }
                    });
                    permissions = arraySet;
                }
                PackageInfo packageInfo = PackageParser.generatePackageInfo(p, gids, flags2, ps.firstInstallTime, ps.lastUpdateTime, permissions, state, userId);
                if (packageInfo == null) {
                    return null;
                }
                ApplicationInfo applicationInfo = packageInfo.applicationInfo;
                String resolveExternalPackageNameLPr = resolveExternalPackageNameLPr(p);
                applicationInfo.packageName = resolveExternalPackageNameLPr;
                packageInfo.packageName = resolveExternalPackageNameLPr;
                return packageInfo;
            }
            if ((flags2 & 8192) != 0 && state.isAvailable(flags2)) {
                PackageInfo pi = new PackageInfo();
                pi.packageName = ps.name;
                pi.setLongVersionCode(ps.versionCode);
                pi.sharedUserId = ps.sharedUser != null ? ps.sharedUser.name : null;
                pi.firstInstallTime = ps.firstInstallTime;
                pi.lastUpdateTime = ps.lastUpdateTime;
                ApplicationInfo ai = new ApplicationInfo();
                ai.packageName = ps.name;
                ai.uid = UserHandle.getUid(userId, ps.appId);
                ai.primaryCpuAbi = ps.primaryCpuAbiString;
                ai.secondaryCpuAbi = ps.secondaryCpuAbiString;
                ai.setVersionCode(ps.versionCode);
                ai.flags = ps.pkgFlags;
                ai.privateFlags = ps.pkgPrivateFlags;
                pi.applicationInfo = PackageParser.generateApplicationInfo(ai, flags2, state, userId);
                return pi;
            }
            return null;
        }
        return null;
    }

    public /* synthetic */ boolean lambda$generatePackageInfo$1$PackageManagerService(int userId, PackageSetting ps, String permissionName) {
        BasePermission permission = this.mPermissionManager.getPermissionTEMP(permissionName);
        if (permission == null) {
            return true;
        }
        if (permission.isInstant()) {
            return false;
        }
        EventLog.writeEvent(1397638484, "140256621", Integer.valueOf(UserHandle.getUid(userId, ps.appId)), permissionName);
        return true;
    }

    public void checkPackageStartable(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        boolean userKeyUnlocked = StorageManager.isUserKeyUnlocked(userId);
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps == null || filterAppAccessLPr(ps, callingUid, userId)) {
                throw new SecurityException("Package " + packageName + " was not found!");
            } else if (!ps.getInstalled(userId)) {
                throw new SecurityException("Package " + packageName + " was not installed for user " + userId + "!");
            } else {
                if (this.mSafeMode && !ps.isSystem()) {
                    throw new SecurityException("Package " + packageName + " not a system app!");
                }
                if (this.mFrozenPackages.contains(packageName)) {
                    throw new SecurityException("Package " + packageName + " is currently frozen!");
                } else if (!userKeyUnlocked && !ps.pkg.applicationInfo.isEncryptionAware()) {
                    throw new SecurityException("Package " + packageName + " is not encryption aware!");
                }
            }
        }
    }

    public boolean isPackageAvailable(String packageName, int userId) {
        PackageUserState state;
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "is package available");
            synchronized (this.mPackages) {
                PackageParser.Package p = this.mPackages.get(packageName);
                if (p != null) {
                    PackageSetting ps = (PackageSetting) p.mExtras;
                    if (filterAppAccessLPr(ps, callingUid, userId)) {
                        return false;
                    }
                    if (ps != null && (state = ps.readUserState(userId)) != null) {
                        return PackageParser.isAvailable(state);
                    }
                }
                return false;
            }
        }
        return false;
    }

    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        return getPackageInfoInternal(packageName, -1L, flags, Binder.getCallingUid(), userId);
    }

    public PackageInfo getPackageInfoVersioned(VersionedPackage versionedPackage, int flags, int userId) {
        return getPackageInfoInternal(versionedPackage.getPackageName(), versionedPackage.getLongVersionCode(), flags, Binder.getCallingUid(), userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PackageInfo getPackageInfoInternal(String packageName, long versionCode, int flags, int filterCallingUid, int userId) {
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForPackage(flags, userId, packageName);
            this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get package info");
            synchronized (this.mPackages) {
                String packageName2 = resolveInternalPackageNameLPr(packageName, versionCode);
                boolean matchFactoryOnly = (2097152 & flags2) != 0;
                if (matchFactoryOnly) {
                    if ((flags2 & 1073741824) != 0) {
                        return this.mApexManager.getPackageInfo(packageName2, 2);
                    }
                    PackageSetting ps = this.mSettings.getDisabledSystemPkgLPr(packageName2);
                    if (ps != null) {
                        if (filterSharedLibPackageLPr(ps, filterCallingUid, userId, flags2)) {
                            return null;
                        }
                        if (filterAppAccessLPr(ps, filterCallingUid, userId)) {
                            return null;
                        }
                        return generatePackageInfo(ps, flags2, userId);
                    }
                }
                PackageParser.Package p = this.mPackages.get(packageName2);
                if (!matchFactoryOnly || p == null || isSystemApp(p)) {
                    if (p != null) {
                        PackageSetting ps2 = (PackageSetting) p.mExtras;
                        if (filterSharedLibPackageLPr(ps2, filterCallingUid, userId, flags2)) {
                            return null;
                        }
                        if (ps2 == null || !filterAppAccessLPr(ps2, filterCallingUid, userId)) {
                            return generatePackageInfo((PackageSetting) p.mExtras, flags2, userId);
                        }
                        return null;
                    } else if (!matchFactoryOnly && (4202496 & flags2) != 0) {
                        PackageSetting ps3 = this.mSettings.mPackages.get(packageName2);
                        if (ps3 == null) {
                            return null;
                        }
                        if (filterSharedLibPackageLPr(ps3, filterCallingUid, userId, flags2)) {
                            return null;
                        }
                        if (filterAppAccessLPr(ps3, filterCallingUid, userId)) {
                            return null;
                        }
                        return generatePackageInfo(ps3, flags2, userId);
                    } else if (matchFactoryOnly || (1073741824 & flags2) == 0) {
                        return null;
                    } else {
                        return this.mApexManager.getPackageInfo(packageName2, 1);
                    }
                }
                return null;
            }
        }
        return null;
    }

    private boolean isComponentVisibleToInstantApp(ComponentName component) {
        if (isComponentVisibleToInstantApp(component, 1) || isComponentVisibleToInstantApp(component, 3) || isComponentVisibleToInstantApp(component, 4)) {
            return true;
        }
        return false;
    }

    private boolean isComponentVisibleToInstantApp(ComponentName component, int type) {
        if (type == 1) {
            PackageParser.Activity activity = this.mComponentResolver.getActivity(component);
            if (activity == null) {
                return false;
            }
            boolean visibleToInstantApp = (1048576 & activity.info.flags) != 0;
            boolean explicitlyVisibleToInstantApp = (2097152 & activity.info.flags) == 0;
            return visibleToInstantApp && explicitlyVisibleToInstantApp;
        } else if (type == 2) {
            PackageParser.Activity activity2 = this.mComponentResolver.getReceiver(component);
            if (activity2 == null) {
                return false;
            }
            boolean visibleToInstantApp2 = (1048576 & activity2.info.flags) != 0;
            boolean explicitlyVisibleToInstantApp2 = (2097152 & activity2.info.flags) == 0;
            return visibleToInstantApp2 && !explicitlyVisibleToInstantApp2;
        } else if (type == 3) {
            PackageParser.Service service = this.mComponentResolver.getService(component);
            return (service == null || (1048576 & service.info.flags) == 0) ? false : true;
        } else if (type == 4) {
            PackageParser.Provider provider = this.mComponentResolver.getProvider(component);
            return (provider == null || (1048576 & provider.info.flags) == 0) ? false : true;
        } else if (type == 0) {
            return isComponentVisibleToInstantApp(component);
        } else {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public boolean filterAppAccessLPr(PackageSetting ps, int callingUid, ComponentName component, int componentType, int userId) {
        if (Process.isIsolated(callingUid)) {
            callingUid = this.mIsolatedOwners.get(callingUid);
        }
        String instantAppPkgName = getInstantAppPackageName(callingUid);
        boolean callerIsInstantApp = instantAppPkgName != null;
        if (ps == null) {
            return callerIsInstantApp;
        } else if (isCallerSameApp(ps.name, callingUid)) {
            return false;
        } else {
            if (callerIsInstantApp) {
                if (ps.getInstantApp(userId)) {
                    return true;
                }
                if (component != null) {
                    PackageParser.Instrumentation instrumentation = this.mInstrumentation.get(component);
                    if (instrumentation != null && isCallerSameApp(instrumentation.info.targetPackage, callingUid)) {
                        return false;
                    }
                    return !isComponentVisibleToInstantApp(component, componentType);
                }
                return !ps.pkg.visibleToInstantApps;
            } else if (!ps.getInstantApp(userId) || canViewInstantApps(callingUid, userId)) {
                return false;
            } else {
                if (component != null) {
                    return true;
                }
                return !this.mInstantAppRegistry.isInstantAccessGranted(userId, UserHandle.getAppId(callingUid), ps.appId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public boolean filterAppAccessLPr(PackageSetting ps, int callingUid, int userId) {
        return filterAppAccessLPr(ps, callingUid, null, 0, userId);
    }

    @GuardedBy({"mPackages"})
    private boolean filterSharedLibPackageLPr(PackageSetting ps, int uid, int userId, int flags) {
        SharedLibraryInfo libraryInfo;
        int index;
        if ((flags & 67108864) != 0) {
            int appId = UserHandle.getAppId(uid);
            if (appId != 1000 && appId != SHELL_UID) {
                if (appId == 0 || checkUidPermission("android.permission.INSTALL_PACKAGES", uid) == 0) {
                    return false;
                }
            }
            return false;
        }
        if (ps != null && ps.pkg != null) {
            if (ps.pkg.applicationInfo.isStaticSharedLibrary() && (libraryInfo = getSharedLibraryInfoLPr(ps.pkg.staticSharedLibName, ps.pkg.staticSharedLibVersion)) != null) {
                int resolvedUid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
                String[] uidPackageNames = getPackagesForUid(resolvedUid);
                if (uidPackageNames == null) {
                    return true;
                }
                for (String uidPackageName : uidPackageNames) {
                    if (ps.name.equals(uidPackageName)) {
                        return false;
                    }
                    PackageSetting uidPs = this.mSettings.getPackageLPr(uidPackageName);
                    if (uidPs != null && (index = ArrayUtils.indexOf(uidPs.usesStaticLibraries, libraryInfo.getName())) >= 0 && uidPs.pkg.usesStaticLibrariesVersions[index] == libraryInfo.getLongVersion()) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
        return false;
    }

    public String[] currentToCanonicalPackageNames(String[] names) {
        boolean z;
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return names;
        }
        String[] out = new String[names.length];
        synchronized (this.mPackages) {
            int callingUserId = UserHandle.getUserId(callingUid);
            boolean canViewInstantApps = canViewInstantApps(callingUid, callingUserId);
            for (int i = names.length - 1; i >= 0; i--) {
                PackageSetting ps = this.mSettings.mPackages.get(names[i]);
                boolean translateName = false;
                if (ps != null && ps.realName != null) {
                    boolean targetIsInstantApp = ps.getInstantApp(callingUserId);
                    if (targetIsInstantApp && !canViewInstantApps && !this.mInstantAppRegistry.isInstantAccessGranted(callingUserId, UserHandle.getAppId(callingUid), ps.appId)) {
                        z = false;
                        translateName = z;
                    }
                    z = true;
                    translateName = z;
                }
                out[i] = translateName ? ps.realName : names[i];
            }
        }
        return out;
    }

    public String[] canonicalToCurrentPackageNames(String[] names) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return names;
        }
        String[] out = new String[names.length];
        synchronized (this.mPackages) {
            int callingUserId = UserHandle.getUserId(callingUid);
            boolean canViewInstantApps = canViewInstantApps(callingUid, callingUserId);
            boolean z = true;
            int i = names.length - 1;
            while (i >= 0) {
                String cur = this.mSettings.getRenamedPackageLPr(names[i]);
                boolean translateName = false;
                if (cur != null) {
                    PackageSetting ps = this.mSettings.mPackages.get(names[i]);
                    boolean z2 = false;
                    boolean targetIsInstantApp = (ps == null || !ps.getInstantApp(callingUserId)) ? false : z;
                    translateName = (!targetIsInstantApp || canViewInstantApps || this.mInstantAppRegistry.isInstantAccessGranted(callingUserId, UserHandle.getAppId(callingUid), ps.appId)) ? true : true;
                }
                out[i] = translateName ? cur : names[i];
                i--;
                z = true;
            }
        }
        return out;
    }

    public int getPackageUid(String packageName, int flags, int userId) {
        PackageSetting ps;
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            int flags2 = updateFlagsForPackage(flags, userId, packageName);
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "getPackageUid");
            synchronized (this.mPackages) {
                PackageParser.Package p = this.mPackages.get(packageName);
                if (p != null && p.isMatch(flags2)) {
                    if (filterAppAccessLPr((PackageSetting) p.mExtras, callingUid, userId)) {
                        return -1;
                    }
                    return UserHandle.getUid(userId, p.applicationInfo.uid);
                } else if ((4202496 & flags2) == 0 || (ps = this.mSettings.mPackages.get(packageName)) == null || !ps.isMatch(flags2) || filterAppAccessLPr(ps, callingUid, userId)) {
                    return -1;
                } else {
                    return UserHandle.getUid(userId, ps.appId);
                }
            }
        }
        return -1;
    }

    private boolean hasTargetSdkInUidLowerThan(int uid, int higherTargetSDK) {
        int userId = UserHandle.getUserId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(UserHandle.getAppId(uid));
            if (obj == null) {
                return false;
            }
            if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting) obj;
                if (!ps.getInstalled(userId)) {
                    return false;
                }
                return ps.pkg.applicationInfo.targetSdkVersion < higherTargetSDK;
            } else if (!(obj instanceof SharedUserSetting)) {
                return false;
            } else {
                SharedUserSetting sus = (SharedUserSetting) obj;
                int numPkgs = sus.packages.size();
                for (int i = 0; i < numPkgs; i++) {
                    PackageSetting ps2 = sus.packages.valueAt(i);
                    if (ps2.getInstalled(userId) && ps2.pkg.applicationInfo.targetSdkVersion < higherTargetSDK) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    public int[] getPackageGids(String packageName, int flags, int userId) {
        PackageSetting ps;
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            int flags2 = updateFlagsForPackage(flags, userId, packageName);
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "getPackageGids");
            synchronized (this.mPackages) {
                PackageParser.Package p = this.mPackages.get(packageName);
                if (p != null && p.isMatch(flags2)) {
                    PackageSetting ps2 = (PackageSetting) p.mExtras;
                    if (filterAppAccessLPr(ps2, callingUid, userId)) {
                        return null;
                    }
                    return ps2.getPermissionsState().computeGids(userId);
                } else if ((4202496 & flags2) == 0 || (ps = this.mSettings.mPackages.get(packageName)) == null || !ps.isMatch(flags2) || filterAppAccessLPr(ps, callingUid, userId)) {
                    return null;
                } else {
                    return ps.getPermissionsState().computeGids(userId);
                }
            }
        }
        return null;
    }

    public PermissionInfo getPermissionInfo(String name, String packageName, int flags) {
        return this.mPermissionManager.getPermissionInfo(name, packageName, flags, getCallingUid());
    }

    public ParceledListSlice<PermissionInfo> queryPermissionsByGroup(String groupName, int flags) {
        List<PermissionInfo> permissionList = this.mPermissionManager.getPermissionInfoByGroup(groupName, flags, getCallingUid());
        if (permissionList == null) {
            return null;
        }
        return new ParceledListSlice<>(permissionList);
    }

    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        return this.mPermissionManager.getPermissionGroupInfo(groupName, flags, getCallingUid());
    }

    public ParceledListSlice<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        List<PermissionGroupInfo> permissionList = this.mPermissionManager.getAllPermissionGroups(flags, getCallingUid());
        return permissionList == null ? ParceledListSlice.emptyList() : new ParceledListSlice<>(permissionList);
    }

    @GuardedBy({"mPackages"})
    private ApplicationInfo generateApplicationInfoFromSettingsLPw(String packageName, int flags, int filterCallingUid, int userId) {
        PackageSetting ps;
        if (!sUserManager.exists(userId) || (ps = this.mSettings.mPackages.get(packageName)) == null || filterSharedLibPackageLPr(ps, filterCallingUid, userId, flags) || filterAppAccessLPr(ps, filterCallingUid, userId)) {
            return null;
        }
        if (ps.pkg == null) {
            PackageInfo pInfo = generatePackageInfo(ps, flags, userId);
            if (pInfo != null) {
                return pInfo.applicationInfo;
            }
            return null;
        }
        ApplicationInfo ai = PackageParser.generateApplicationInfo(ps.pkg, flags, ps.readUserState(userId), userId);
        if (ai != null) {
            ai.packageName = resolveExternalPackageNameLPr(ps.pkg);
        }
        return ai;
    }

    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        return getApplicationInfoInternal(packageName, flags, Binder.getCallingUid(), userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ApplicationInfo getApplicationInfoInternal(String packageName, int flags, int filterCallingUid, int userId) {
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForApplication(flags, userId, packageName);
            if (!isRecentsAccessingChildProfiles(Binder.getCallingUid(), userId)) {
                this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get application info");
            }
            synchronized (this.mPackages) {
                String packageName2 = resolveInternalPackageNameLPr(packageName, -1L);
                PackageParser.Package p = this.mPackages.get(packageName2);
                if (p != null) {
                    PackageSetting ps = this.mSettings.mPackages.get(packageName2);
                    if (ps == null) {
                        return null;
                    }
                    if (filterSharedLibPackageLPr(ps, filterCallingUid, userId, flags2)) {
                        return null;
                    }
                    if (filterAppAccessLPr(ps, filterCallingUid, userId)) {
                        return null;
                    }
                    ApplicationInfo ai = PackageParser.generateApplicationInfo(p, flags2, ps.readUserState(userId), userId);
                    if (ai != null) {
                        ai.packageName = resolveExternalPackageNameLPr(p);
                    }
                    return ai;
                }
                if (!PLATFORM_PACKAGE_NAME.equals(packageName2) && !"system".equals(packageName2)) {
                    if ((4202496 & flags2) != 0) {
                        return generateApplicationInfoFromSettingsLPw(packageName2, flags2, filterCallingUid, userId);
                    }
                    return null;
                }
                return this.mAndroidApplication;
            }
        }
        return null;
    }

    @GuardedBy({"mPackages"})
    private String normalizePackageNameLPr(String packageName) {
        String normalizedPackageName = this.mSettings.getRenamedPackageLPr(packageName);
        return normalizedPackageName != null ? normalizedPackageName : packageName;
    }

    public void deletePreloadsFileCache() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.CLEAR_APP_CACHE", "deletePreloadsFileCache");
        File dir = Environment.getDataPreloadsFileCacheDirectory();
        Slog.i(TAG, "Deleting preloaded file cache " + dir);
        FileUtils.deleteContents(dir);
    }

    public void freeStorageAndNotify(final String volumeUuid, final long freeStorageSize, final int storageFlags, final IPackageDataObserver observer) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.CLEAR_APP_CACHE", null);
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$wVxjBUhhrST_8tgGFnKwa3dHr7w
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$freeStorageAndNotify$2$PackageManagerService(volumeUuid, freeStorageSize, storageFlags, observer);
            }
        });
    }

    public /* synthetic */ void lambda$freeStorageAndNotify$2$PackageManagerService(String volumeUuid, long freeStorageSize, int storageFlags, IPackageDataObserver observer) {
        boolean success = false;
        try {
            freeStorage(volumeUuid, freeStorageSize, storageFlags);
            success = true;
        } catch (IOException e) {
            Slog.w(TAG, e);
        }
        if (observer != null) {
            try {
                observer.onRemoveCompleted((String) null, success);
            } catch (RemoteException e2) {
                Slog.w(TAG, e2);
            }
        }
    }

    public void freeStorage(final String volumeUuid, final long freeStorageSize, final int storageFlags, final IntentSender pi) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.CLEAR_APP_CACHE", TAG);
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$JzZnAIsG_0v1PIJvKY2tajsOnWg
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$freeStorage$3$PackageManagerService(volumeUuid, freeStorageSize, storageFlags, pi);
            }
        });
    }

    public /* synthetic */ void lambda$freeStorage$3$PackageManagerService(String volumeUuid, long freeStorageSize, int storageFlags, IntentSender pi) {
        boolean success = false;
        try {
            freeStorage(volumeUuid, freeStorageSize, storageFlags);
            success = true;
        } catch (IOException e) {
            Slog.w(TAG, e);
        }
        if (pi != null) {
            try {
                pi.sendIntent(null, success ? 1 : 0, null, null, null);
            } catch (IntentSender.SendIntentException e2) {
                Slog.w(TAG, e2);
            }
        }
    }

    public void freeStorage(String volumeUuid, long bytes, int storageFlags) throws IOException {
        long j;
        StorageManager storage = (StorageManager) this.mContext.getSystemService(StorageManager.class);
        File file = storage.findPathForUuid(volumeUuid);
        if (file.getUsableSpace() >= bytes) {
            return;
        }
        if (!ENABLE_FREE_CACHE_V2) {
            try {
                this.mInstaller.freeCache(volumeUuid, bytes, 0L, 0);
            } catch (Installer.InstallerException e) {
            }
            if (file.getUsableSpace() >= bytes) {
                return;
            }
        } else {
            boolean internalVolume = Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, volumeUuid);
            boolean aggressive = (storageFlags & 1) != 0;
            long reservedBytes = storage.getStorageCacheBytes(file, storageFlags);
            if (internalVolume && (aggressive || SystemProperties.getBoolean("persist.sys.preloads.file_cache_expired", false))) {
                deletePreloadsFileCache();
                if (file.getUsableSpace() >= bytes) {
                    return;
                }
            }
            if (internalVolume && aggressive) {
                FileUtils.deleteContents(this.mCacheDir);
                if (file.getUsableSpace() >= bytes) {
                    return;
                }
            }
            try {
                this.mInstaller.freeCache(volumeUuid, bytes, reservedBytes, 256);
            } catch (Installer.InstallerException e2) {
            }
            if (file.getUsableSpace() >= bytes) {
                return;
            }
            if (internalVolume && pruneUnusedStaticSharedLibraries(bytes, Settings.Global.getLong(this.mContext.getContentResolver(), "unused_static_shared_lib_min_cache_period", 7200000L))) {
                return;
            }
            if (!internalVolume || !this.mInstantAppRegistry.pruneInstalledInstantApps(bytes, Settings.Global.getLong(this.mContext.getContentResolver(), "installed_instant_app_min_cache_period", 604800000L))) {
                try {
                    j = 604800000;
                    try {
                        this.mInstaller.freeCache(volumeUuid, bytes, reservedBytes, 768);
                    } catch (Installer.InstallerException e3) {
                    }
                } catch (Installer.InstallerException e4) {
                    j = 604800000;
                }
                if (file.getUsableSpace() >= bytes) {
                    return;
                }
                if (internalVolume && this.mInstantAppRegistry.pruneUninstalledInstantApps(bytes, Settings.Global.getLong(this.mContext.getContentResolver(), "uninstalled_instant_app_min_cache_period", j))) {
                    return;
                }
            } else {
                return;
            }
        }
        throw new IOException("Failed to free " + bytes + " on storage device at " + file);
    }

    private boolean pruneUnusedStaticSharedLibraries(long neededSpace, long maxCachePeriod) throws IOException {
        int[] allUsers;
        StorageManager storage;
        long now;
        StorageManager storage2;
        long now2;
        PackageManagerService packageManagerService = this;
        StorageManager storage3 = (StorageManager) packageManagerService.mContext.getSystemService(StorageManager.class);
        File volume = storage3.findPathForUuid(StorageManager.UUID_PRIVATE_INTERNAL);
        long now3 = System.currentTimeMillis();
        synchronized (packageManagerService.mPackages) {
            try {
                int[] allUsers2 = sUserManager.getUserIds();
                int libCount = packageManagerService.mSharedLibraries.size();
                int i = 0;
                ArrayList arrayList = null;
                while (i < libCount) {
                    try {
                        LongSparseArray<SharedLibraryInfo> versionedLib = packageManagerService.mSharedLibraries.valueAt(i);
                        if (versionedLib != null) {
                            int versionCount = versionedLib.size();
                            int j = 0;
                            while (true) {
                                if (j >= versionCount) {
                                    allUsers = allUsers2;
                                    storage = storage3;
                                    now = now3;
                                    break;
                                }
                                SharedLibraryInfo libInfo = versionedLib.valueAt(j);
                                if (!libInfo.isStatic()) {
                                    allUsers = allUsers2;
                                    storage = storage3;
                                    now = now3;
                                    break;
                                }
                                VersionedPackage declaringPackage = libInfo.getDeclaringPackage();
                                int[] allUsers3 = allUsers2;
                                LongSparseArray<SharedLibraryInfo> versionedLib2 = versionedLib;
                                String internalPackageName = packageManagerService.resolveInternalPackageNameLPr(declaringPackage.getPackageName(), declaringPackage.getLongVersionCode());
                                PackageSetting ps = packageManagerService.mSettings.getPackageLPr(internalPackageName);
                                if (ps != null) {
                                    storage2 = storage3;
                                    try {
                                        if (now3 - ps.lastUpdateTime < maxCachePeriod) {
                                            now2 = now3;
                                        } else if (ps.pkg.isSystem()) {
                                            now2 = now3;
                                        } else {
                                            if (arrayList == null) {
                                                try {
                                                    arrayList = new ArrayList();
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
                                            now2 = now3;
                                            long now4 = declaringPackage.getLongVersionCode();
                                            arrayList.add(new VersionedPackage(internalPackageName, now4));
                                        }
                                    } catch (Throwable th3) {
                                        th = th3;
                                    }
                                } else {
                                    storage2 = storage3;
                                    now2 = now3;
                                }
                                j++;
                                packageManagerService = this;
                                storage3 = storage2;
                                allUsers2 = allUsers3;
                                versionedLib = versionedLib2;
                                now3 = now2;
                            }
                        } else {
                            allUsers = allUsers2;
                            storage = storage3;
                            now = now3;
                        }
                    } catch (Throwable th4) {
                        th = th4;
                    }
                    try {
                        i++;
                        packageManagerService = this;
                        storage3 = storage;
                        allUsers2 = allUsers;
                        now3 = now;
                    } catch (Throwable th5) {
                        th = th5;
                        while (true) {
                            break;
                            break;
                        }
                        throw th;
                    }
                }
                if (arrayList != null) {
                    int packageCount = arrayList.size();
                    for (int i2 = 0; i2 < packageCount; i2++) {
                        VersionedPackage pkgToDelete = (VersionedPackage) arrayList.get(i2);
                        if (deletePackageX(pkgToDelete.getPackageName(), pkgToDelete.getLongVersionCode(), 0, 2) == 1 && volume.getUsableSpace() >= neededSpace) {
                            return true;
                        }
                    }
                    return false;
                }
                return false;
            } catch (Throwable th6) {
                th = th6;
            }
        }
    }

    private int updateFlags(int flags, int userId) {
        if ((flags & 786432) == 0) {
            if (getUserManagerInternal().isUserUnlockingOrUnlocked(userId)) {
                return flags | 786432;
            }
            return flags | 524288;
        }
        return flags;
    }

    private UserManagerInternal getUserManagerInternal() {
        if (this.mUserManagerInternal == null) {
            this.mUserManagerInternal = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
        }
        return this.mUserManagerInternal;
    }

    private ActivityManagerInternal getActivityManagerInternal() {
        if (this.mActivityManagerInternal == null) {
            this.mActivityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        }
        return this.mActivityManagerInternal;
    }

    private ActivityTaskManagerInternal getActivityTaskManagerInternal() {
        if (this.mActivityTaskManagerInternal == null) {
            this.mActivityTaskManagerInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
        }
        return this.mActivityTaskManagerInternal;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public DeviceIdleController.LocalService getDeviceIdleController() {
        if (this.mDeviceIdleController == null) {
            this.mDeviceIdleController = (DeviceIdleController.LocalService) LocalServices.getService(DeviceIdleController.LocalService.class);
        }
        return this.mDeviceIdleController;
    }

    private StorageManagerInternal getStorageManagerInternal() {
        if (this.mStorageManagerInternal == null) {
            this.mStorageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
        }
        return this.mStorageManagerInternal;
    }

    private int updateFlagsForPackage(int flags, int userId, Object cookie) {
        boolean isCallerSystemUser = UserHandle.getCallingUserId() == 0;
        if ((flags & 4194304) != 0) {
            this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, !isRecentsAccessingChildProfiles(Binder.getCallingUid(), userId), "MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission at " + Debug.getCallers(5));
        } else if ((flags & 8192) != 0 && isCallerSystemUser && sUserManager.hasManagedProfile(0)) {
            flags |= 4194304;
        }
        return updateFlags(flags, userId);
    }

    private int updateFlagsForApplication(int flags, int userId, Object cookie) {
        return updateFlagsForPackage(flags, userId, cookie);
    }

    private int updateFlagsForComponent(int flags, int userId, Object cookie) {
        return updateFlags(flags, userId);
    }

    private Intent updateIntentForResolve(Intent intent) {
        if (intent.getSelector() != null) {
            return intent.getSelector();
        }
        return intent;
    }

    int updateFlagsForResolve(int flags, int userId, Intent intent, int callingUid) {
        return updateFlagsForResolve(flags, userId, intent, callingUid, false, false);
    }

    int updateFlagsForResolve(int flags, int userId, Intent intent, int callingUid, boolean wantInstantApps) {
        return updateFlagsForResolve(flags, userId, intent, callingUid, wantInstantApps, false);
    }

    int updateFlagsForResolve(int flags, int userId, Intent intent, int callingUid, boolean wantInstantApps, boolean onlyExposedExplicitly) {
        int flags2;
        if (this.mSafeMode) {
            flags |= 1048576;
        }
        if (getInstantAppPackageName(callingUid) != null) {
            if (onlyExposedExplicitly) {
                flags |= DumpState.DUMP_APEX;
            }
            flags2 = flags | 16777216 | 8388608;
        } else {
            boolean allowMatchInstant = true;
            boolean wantMatchInstant = (flags & 8388608) != 0;
            if (!wantInstantApps && (!wantMatchInstant || !canViewInstantApps(callingUid, userId))) {
                allowMatchInstant = false;
            }
            flags2 = flags & (-50331649);
            if (!allowMatchInstant) {
                flags2 &= -8388609;
            }
        }
        return updateFlagsForComponent(flags2, userId, intent);
    }

    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        return getActivityInfoInternal(component, flags, Binder.getCallingUid(), userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ActivityInfo getActivityInfoInternal(ComponentName component, int flags, int filterCallingUid, int userId) {
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForComponent(flags, userId, component);
            if (!isRecentsAccessingChildProfiles(Binder.getCallingUid(), userId)) {
                this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get activity info");
            }
            synchronized (this.mPackages) {
                PackageParser.Activity a = this.mComponentResolver.getActivity(component);
                if (a != null && this.mSettings.isEnabledAndMatchLPr(a.info, flags2, userId)) {
                    PackageSetting ps = this.mSettings.mPackages.get(component.getPackageName());
                    if (ps == null) {
                        return null;
                    }
                    if (filterAppAccessLPr(ps, filterCallingUid, component, 1, userId)) {
                        return null;
                    }
                    return PackageParser.generateActivityInfo(a, flags2, ps.readUserState(userId), userId);
                } else if (this.mResolveComponentName.equals(component)) {
                    return PackageParser.generateActivityInfo(this.mResolveActivity, flags2, new PackageUserState(), userId);
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean isRecentsAccessingChildProfiles(int callingUid, int targetUserId) {
        if (getActivityTaskManagerInternal().isCallerRecents(callingUid)) {
            long token = Binder.clearCallingIdentity();
            try {
                int callingUserId = UserHandle.getUserId(callingUid);
                if (ActivityManager.getCurrentUser() != callingUserId) {
                    return false;
                }
                return sUserManager.isSameProfileGroup(callingUserId, targetUserId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return false;
    }

    public boolean activitySupportsIntent(ComponentName component, Intent intent, String resolvedType) {
        synchronized (this.mPackages) {
            if (component.equals(this.mResolveComponentName)) {
                return true;
            }
            int callingUid = Binder.getCallingUid();
            int callingUserId = UserHandle.getUserId(callingUid);
            PackageParser.Activity a = this.mComponentResolver.getActivity(component);
            if (a == null) {
                return false;
            }
            PackageSetting ps = this.mSettings.mPackages.get(component.getPackageName());
            if (ps == null) {
                return false;
            }
            if (filterAppAccessLPr(ps, callingUid, component, 1, callingUserId)) {
                return false;
            }
            for (int i = 0; i < a.intents.size(); i++) {
                if (((PackageParser.ActivityIntentInfo) a.intents.get(i)).match(intent.getAction(), resolvedType, intent.getScheme(), intent.getData(), intent.getCategories(), TAG) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            int flags2 = updateFlagsForComponent(flags, userId, component);
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get receiver info");
            synchronized (this.mPackages) {
                PackageParser.Activity a = this.mComponentResolver.getReceiver(component);
                if (a == null || !this.mSettings.isEnabledAndMatchLPr(a.info, flags2, userId)) {
                    return null;
                }
                PackageSetting ps = this.mSettings.mPackages.get(component.getPackageName());
                if (ps == null) {
                    return null;
                }
                if (filterAppAccessLPr(ps, callingUid, component, 2, userId)) {
                    return null;
                }
                return PackageParser.generateActivityInfo(a, flags2, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    /* JADX WARN: Code restructure failed: missing block: B:17:0x0056, code lost:
        if (r30.mContext.checkCallingOrSelfPermission("android.permission.ACCESS_SHARED_LIBRARIES") != 0) goto L18;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public android.content.pm.ParceledListSlice<android.content.pm.SharedLibraryInfo> getSharedLibraries(java.lang.String r31, int r32, int r33) {
        /*
            Method dump skipped, instructions count: 275
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.getSharedLibraries(java.lang.String, int, int):android.content.pm.ParceledListSlice");
    }

    public ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(String packageName, int flags, int userId) {
        ParceledListSlice<SharedLibraryInfo> parceledListSlice;
        PackageManagerService packageManagerService = this;
        packageManagerService.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_SHARED_LIBRARIES", "getDeclaredSharedLibraries");
        int callingUid = Binder.getCallingUid();
        packageManagerService.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, false, "getDeclaredSharedLibraries");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        Preconditions.checkArgumentNonnegative(userId, "userId must be >= 0");
        ArrayList arrayList = null;
        if (sUserManager.exists(userId) && packageManagerService.getInstantAppPackageName(callingUid) == null) {
            synchronized (packageManagerService.mPackages) {
                List<SharedLibraryInfo> result = null;
                int libraryCount = packageManagerService.mSharedLibraries.size();
                int i = 0;
                while (i < libraryCount) {
                    LongSparseArray<SharedLibraryInfo> versionedLibrary = packageManagerService.mSharedLibraries.valueAt(i);
                    if (versionedLibrary != null) {
                        int versionCount = versionedLibrary.size();
                        int j = 0;
                        List<SharedLibraryInfo> result2 = result;
                        while (j < versionCount) {
                            SharedLibraryInfo libraryInfo = versionedLibrary.valueAt(j);
                            VersionedPackage declaringPackage = libraryInfo.getDeclaringPackage();
                            if (Objects.equals(declaringPackage.getPackageName(), packageName)) {
                                long identity = Binder.clearCallingIdentity();
                                PackageInfo packageInfo = packageManagerService.getPackageInfoVersioned(declaringPackage, 67108864 | flags, userId);
                                if (packageInfo == null) {
                                    Binder.restoreCallingIdentity(identity);
                                } else {
                                    Binder.restoreCallingIdentity(identity);
                                    String path = libraryInfo.getPath();
                                    String packageName2 = libraryInfo.getPackageName();
                                    List allCodePaths = libraryInfo.getAllCodePaths();
                                    String name = libraryInfo.getName();
                                    long longVersion = libraryInfo.getLongVersion();
                                    int type = libraryInfo.getType();
                                    VersionedPackage declaringPackage2 = libraryInfo.getDeclaringPackage();
                                    List<VersionedPackage> packagesUsingSharedLibraryLPr = packageManagerService.getPackagesUsingSharedLibraryLPr(libraryInfo, flags, userId);
                                    if (libraryInfo.getDependencies() != null) {
                                        arrayList = new ArrayList(libraryInfo.getDependencies());
                                    }
                                    SharedLibraryInfo resultLibraryInfo = new SharedLibraryInfo(path, packageName2, allCodePaths, name, longVersion, type, declaringPackage2, packagesUsingSharedLibraryLPr, arrayList);
                                    if (result2 == null) {
                                        result2 = new ArrayList<>();
                                    }
                                    result2.add(resultLibraryInfo);
                                }
                            }
                            j++;
                            arrayList = null;
                            packageManagerService = this;
                        }
                        result = result2;
                    }
                    i++;
                    arrayList = null;
                    packageManagerService = this;
                }
                parceledListSlice = result != null ? new ParceledListSlice<>(result) : null;
            }
            return parceledListSlice;
        }
        return null;
    }

    @GuardedBy({"mPackages"})
    private List<VersionedPackage> getPackagesUsingSharedLibraryLPr(SharedLibraryInfo libInfo, int flags, int userId) {
        List<VersionedPackage> versionedPackages = null;
        int packageCount = this.mSettings.mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageSetting ps = this.mSettings.mPackages.valueAt(i);
            if (ps != null && ps.readUserState(userId).isAvailable(flags)) {
                String libName = libInfo.getName();
                if (libInfo.isStatic()) {
                    int libIdx = ArrayUtils.indexOf(ps.usesStaticLibraries, libName);
                    if (libIdx >= 0 && ps.usesStaticLibrariesVersions[libIdx] == libInfo.getLongVersion()) {
                        if (versionedPackages == null) {
                            versionedPackages = new ArrayList<>();
                        }
                        String dependentPackageName = ps.name;
                        if (ps.pkg != null && ps.pkg.applicationInfo.isStaticSharedLibrary()) {
                            dependentPackageName = ps.pkg.manifestPackageName;
                        }
                        versionedPackages.add(new VersionedPackage(dependentPackageName, ps.versionCode));
                    }
                } else if (ps.pkg != null && (ArrayUtils.contains(ps.pkg.usesLibraries, libName) || ArrayUtils.contains(ps.pkg.usesOptionalLibraries, libName))) {
                    if (versionedPackages == null) {
                        versionedPackages = new ArrayList<>();
                    }
                    versionedPackages.add(new VersionedPackage(ps.name, ps.versionCode));
                }
            }
        }
        return versionedPackages;
    }

    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            int flags2 = updateFlagsForComponent(flags, userId, component);
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get service info");
            synchronized (this.mPackages) {
                PackageParser.Service s = this.mComponentResolver.getService(component);
                if (s == null || !this.mSettings.isEnabledAndMatchLPr(s.info, flags2, userId)) {
                    return null;
                }
                PackageSetting ps = this.mSettings.mPackages.get(component.getPackageName());
                if (ps == null) {
                    return null;
                }
                if (filterAppAccessLPr(ps, callingUid, component, 3, userId)) {
                    return null;
                }
                return PackageParser.generateServiceInfo(s, flags2, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            int flags2 = updateFlagsForComponent(flags, userId, component);
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get provider info");
            synchronized (this.mPackages) {
                PackageParser.Provider p = this.mComponentResolver.getProvider(component);
                if (p == null || !this.mSettings.isEnabledAndMatchLPr(p.info, flags2, userId)) {
                    return null;
                }
                PackageSetting ps = this.mSettings.mPackages.get(component.getPackageName());
                if (ps == null) {
                    return null;
                }
                if (filterAppAccessLPr(ps, callingUid, component, 4, userId)) {
                    return null;
                }
                return PackageParser.generateProviderInfo(p, flags2, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    public ModuleInfo getModuleInfo(String packageName, int flags) {
        return this.mModuleInfoProvider.getModuleInfo(packageName, flags);
    }

    public List<ModuleInfo> getInstalledModules(int flags) {
        return this.mModuleInfoProvider.getInstalledModules(flags);
    }

    public String[] getSystemSharedLibraryNames() {
        synchronized (this.mPackages) {
            Set<String> libs = null;
            int libCount = this.mSharedLibraries.size();
            for (int i = 0; i < libCount; i++) {
                LongSparseArray<SharedLibraryInfo> versionedLib = this.mSharedLibraries.valueAt(i);
                if (versionedLib != null) {
                    int versionCount = versionedLib.size();
                    int j = 0;
                    while (true) {
                        if (j < versionCount) {
                            SharedLibraryInfo libraryInfo = versionedLib.valueAt(j);
                            if (!libraryInfo.isStatic()) {
                                if (libs == null) {
                                    libs = new ArraySet<>();
                                }
                                libs.add(libraryInfo.getName());
                            } else {
                                PackageSetting ps = this.mSettings.getPackageLPr(libraryInfo.getPackageName());
                                if (ps == null || filterSharedLibPackageLPr(ps, Binder.getCallingUid(), UserHandle.getUserId(Binder.getCallingUid()), 67108864)) {
                                    j++;
                                } else {
                                    if (libs == null) {
                                        libs = new ArraySet<>();
                                    }
                                    libs.add(libraryInfo.getName());
                                }
                            }
                        }
                    }
                }
            }
            if (libs != null) {
                String[] libsArray = new String[libs.size()];
                libs.toArray(libsArray);
                return libsArray;
            }
            return null;
        }
    }

    public String getServicesSystemSharedLibraryPackageName() {
        String str;
        synchronized (this.mPackages) {
            str = this.mServicesSystemSharedLibraryPackageName;
        }
        return str;
    }

    public String getSharedSystemSharedLibraryPackageName() {
        String str;
        synchronized (this.mPackages) {
            str = this.mSharedSystemSharedLibraryPackageName;
        }
        return str;
    }

    @GuardedBy({"mPackages"})
    private void updateSequenceNumberLP(PackageSetting pkgSetting, int[] userList) {
        for (int i = userList.length - 1; i >= 0; i--) {
            int userId = userList[i];
            if (!pkgSetting.getInstantApp(userId)) {
                SparseArray<String> changedPackages = this.mChangedPackages.get(userId);
                if (changedPackages == null) {
                    changedPackages = new SparseArray<>();
                    this.mChangedPackages.put(userId, changedPackages);
                }
                Map<String, Integer> sequenceNumbers = this.mChangedPackagesSequenceNumbers.get(userId);
                if (sequenceNumbers == null) {
                    sequenceNumbers = new HashMap();
                    this.mChangedPackagesSequenceNumbers.put(userId, sequenceNumbers);
                }
                Integer sequenceNumber = sequenceNumbers.get(pkgSetting.name);
                if (sequenceNumber != null) {
                    changedPackages.remove(sequenceNumber.intValue());
                }
                changedPackages.put(this.mChangedPackagesSequenceNumber, pkgSetting.name);
                sequenceNumbers.put(pkgSetting.name, Integer.valueOf(this.mChangedPackagesSequenceNumber));
            }
        }
        int i2 = this.mChangedPackagesSequenceNumber;
        this.mChangedPackagesSequenceNumber = i2 + 1;
    }

    public ChangedPackages getChangedPackages(int sequenceNumber, int userId) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (this.mPackages) {
            if (sequenceNumber >= this.mChangedPackagesSequenceNumber) {
                return null;
            }
            SparseArray<String> changedPackages = this.mChangedPackages.get(userId);
            if (changedPackages == null) {
                return null;
            }
            List<String> packageNames = new ArrayList<>(this.mChangedPackagesSequenceNumber - sequenceNumber);
            for (int i = sequenceNumber; i < this.mChangedPackagesSequenceNumber; i++) {
                String packageName = changedPackages.get(i);
                if (packageName != null) {
                    packageNames.add(packageName);
                }
            }
            return packageNames.isEmpty() ? null : new ChangedPackages(this.mChangedPackagesSequenceNumber, packageNames);
        }
    }

    public ParceledListSlice<FeatureInfo> getSystemAvailableFeatures() {
        ArrayList<FeatureInfo> res;
        synchronized (this.mAvailableFeatures) {
            res = new ArrayList<>(this.mAvailableFeatures.size() + 1);
            res.addAll(this.mAvailableFeatures.values());
        }
        FeatureInfo fi = new FeatureInfo();
        fi.reqGlEsVersion = SystemProperties.getInt("ro.opengles.version", 0);
        res.add(fi);
        return new ParceledListSlice<>(res);
    }

    public boolean hasSystemFeature(String name, int version) {
        synchronized (this.mAvailableFeatures) {
            FeatureInfo feat = this.mAvailableFeatures.get(name);
            if (feat == null) {
                return false;
            }
            return feat.version >= version;
        }
    }

    public int checkPermission(String permName, String pkgName, int userId) {
        synchronized (this.mPackages) {
            if (this.mCheckPermissionDelegate == null) {
                return checkPermissionImpl(permName, pkgName, userId);
            }
            PackageManagerInternal.CheckPermissionDelegate checkPermissionDelegate = this.mCheckPermissionDelegate;
            return checkPermissionDelegate.checkPermission(permName, pkgName, userId, new TriFunction() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$b2Z8hEDt-0dbtmX9ytdWFgSa9tc
                public final Object apply(Object obj, Object obj2, Object obj3) {
                    int checkPermissionImpl;
                    checkPermissionImpl = PackageManagerService.this.checkPermissionImpl((String) obj, (String) obj2, ((Integer) obj3).intValue());
                    return Integer.valueOf(checkPermissionImpl);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkPermissionImpl(String permName, String pkgName, int userId) {
        return this.mPermissionManager.checkPermission(permName, pkgName, getCallingUid(), userId);
    }

    public int checkUidPermission(String permName, int uid) {
        synchronized (this.mPackages) {
            if (this.mCheckPermissionDelegate == null) {
                return checkUidPermissionImpl(permName, uid);
            }
            PackageManagerInternal.CheckPermissionDelegate checkPermissionDelegate = this.mCheckPermissionDelegate;
            return checkPermissionDelegate.checkUidPermission(permName, uid, new BiFunction() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$XJQWfaGM1EYfvHM2L3JN55XapIQ
                @Override // java.util.function.BiFunction
                public final Object apply(Object obj, Object obj2) {
                    int checkUidPermissionImpl;
                    checkUidPermissionImpl = PackageManagerService.this.checkUidPermissionImpl((String) obj, ((Integer) obj2).intValue());
                    return Integer.valueOf(checkUidPermissionImpl);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkUidPermissionImpl(String permName, int uid) {
        int checkUidPermission;
        synchronized (this.mPackages) {
            String[] packageNames = getPackagesForUid(uid);
            PackageParser.Package pkg = null;
            int N = packageNames == null ? 0 : packageNames.length;
            for (int i = 0; pkg == null && i < N; i++) {
                pkg = this.mPackages.get(packageNames[i]);
            }
            checkUidPermission = this.mPermissionManager.checkUidPermission(permName, pkg, uid, getCallingUid());
        }
        return checkUidPermission;
    }

    public boolean isPermissionRevokedByPolicy(String permission, String packageName, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            Context context = this.mContext;
            context.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "isPermissionRevokedByPolicy for user " + userId);
        }
        if (checkPermission(permission, packageName, userId) == 0) {
            return false;
        }
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            if (!isCallerSameApp(packageName, callingUid)) {
                return false;
            }
        } else if (isInstantApp(packageName, userId)) {
            return false;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            int flags = getPermissionFlags(permission, packageName, userId);
            return (flags & 4) != 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public String getPermissionControllerPackageName() {
        String str;
        synchronized (this.mPackages) {
            str = this.mRequiredPermissionControllerPackage;
        }
        return str;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getPackageInstallerPackageName() {
        String str;
        synchronized (this.mPackages) {
            str = this.mRequiredInstallerPackage;
        }
        return str;
    }

    private boolean addDynamicPermission(PermissionInfo info, final boolean async) {
        return this.mPermissionManager.addDynamicPermission(info, async, getCallingUid(), new PermissionManagerServiceInternal.PermissionCallback() { // from class: com.android.server.pm.PackageManagerService.3
            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onPermissionChanged() {
                if (!async) {
                    PackageManagerService.this.mSettings.writeLPr();
                } else {
                    PackageManagerService.this.scheduleWriteSettingsLocked();
                }
            }
        });
    }

    public boolean addPermission(PermissionInfo info) {
        boolean addDynamicPermission;
        synchronized (this.mPackages) {
            addDynamicPermission = addDynamicPermission(info, false);
        }
        return addDynamicPermission;
    }

    public boolean addPermissionAsync(PermissionInfo info) {
        boolean addDynamicPermission;
        synchronized (this.mPackages) {
            addDynamicPermission = addDynamicPermission(info, true);
        }
        return addDynamicPermission;
    }

    public void removePermission(String permName) {
        this.mPermissionManager.removeDynamicPermission(permName, getCallingUid(), this.mPermissionCallback);
    }

    public void grantRuntimePermission(String packageName, String permName, int userId) {
        boolean overridePolicy = checkUidPermission("android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY", Binder.getCallingUid()) == 0;
        this.mPermissionManager.grantRuntimePermission(permName, packageName, overridePolicy, getCallingUid(), userId, this.mPermissionCallback);
    }

    public void revokeRuntimePermission(String packageName, String permName, int userId) {
        boolean overridePolicy = checkUidPermission("android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY", Binder.getCallingUid()) == 0;
        this.mPermissionManager.revokeRuntimePermission(permName, packageName, overridePolicy, userId, this.mPermissionCallback);
    }

    public void resetRuntimePermissions() {
        int[] userIds;
        this.mContext.enforceCallingOrSelfPermission("android.permission.REVOKE_RUNTIME_PERMISSIONS", "revokeRuntimePermission");
        int callingUid = Binder.getCallingUid();
        if (callingUid != 1000 && callingUid != 0) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "resetRuntimePermissions");
        }
        synchronized (this.mPackages) {
            this.mPermissionManager.updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, false, this.mPackages.values(), this.mPermissionCallback);
            for (int userId : UserManagerService.getInstance().getUserIds()) {
                int packageCount = this.mPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    PackageParser.Package pkg = this.mPackages.valueAt(i);
                    if (pkg.mExtras instanceof PackageSetting) {
                        PackageSetting ps = (PackageSetting) pkg.mExtras;
                        resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, userId);
                    }
                }
            }
        }
    }

    public int getPermissionFlags(String permName, String packageName, int userId) {
        return this.mPermissionManager.getPermissionFlags(permName, packageName, getCallingUid(), userId);
    }

    public void updatePermissionFlags(String permName, String packageName, int flagMask, int flagValues, boolean checkAdjustPolicyFlagPermission, int userId) {
        boolean overridePolicy;
        int callingUid = getCallingUid();
        boolean overridePolicy2 = false;
        if (callingUid != 1000 && callingUid != 0) {
            long callingIdentity = Binder.clearCallingIdentity();
            if ((flagMask & 4) != 0) {
                try {
                    if (checkAdjustPolicyFlagPermission) {
                        this.mContext.enforceCallingOrSelfPermission("android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY", "Need android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY to change policy flags");
                    } else if (!hasTargetSdkInUidLowerThan(callingUid, 29)) {
                        throw new IllegalArgumentException("android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY needs  to be checked for packages targeting 29 or later when changing policy flags");
                    }
                    overridePolicy2 = true;
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(callingIdentity);
                    throw th;
                }
            }
            Binder.restoreCallingIdentity(callingIdentity);
            overridePolicy = overridePolicy2;
        } else {
            overridePolicy = false;
        }
        this.mPermissionManager.updatePermissionFlags(permName, packageName, flagMask, flagValues, callingUid, userId, overridePolicy, this.mPermissionCallback);
    }

    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId) {
        synchronized (this.mPackages) {
            boolean changed = this.mPermissionManager.updatePermissionFlagsForAllApps(flagMask, flagValues, getCallingUid(), userId, this.mPackages.values(), this.mPermissionCallback);
            if (changed) {
                this.mSettings.writeRuntimePermissionsForUserLPr(userId, false);
            }
        }
    }

    public List<String> getWhitelistedRestrictedPermissions(String packageName, int whitelistFlags, int userId) {
        Preconditions.checkNotNull(packageName);
        Preconditions.checkFlagsArgument(whitelistFlags, 7);
        Preconditions.checkArgumentNonNegative(userId, (String) null);
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS", "getWhitelistedRestrictedPermissions for user " + userId);
        }
        synchronized (this.mPackages) {
            PackageSetting packageSetting = this.mSettings.mPackages.get(packageName);
            if (packageSetting == null) {
                Slog.w(TAG, "Unknown package: " + packageName);
                return null;
            }
            PackageParser.Package pkg = packageSetting.pkg;
            boolean isCallerInstallerOnRecord = false;
            boolean isCallerPrivileged = this.mContext.checkCallingOrSelfPermission("android.permission.WHITELIST_RESTRICTED_PERMISSIONS") == 0;
            PackageSetting installerPackageSetting = this.mSettings.mPackages.get(packageSetting.installerPackageName);
            if (installerPackageSetting != null && UserHandle.isSameApp(installerPackageSetting.appId, Binder.getCallingUid())) {
                isCallerInstallerOnRecord = true;
            }
            if ((whitelistFlags & 1) != 0 && !isCallerPrivileged) {
                throw new SecurityException("Querying system whitelist requires android.permission.WHITELIST_RESTRICTED_PERMISSIONS");
            }
            if ((whitelistFlags & 6) != 0 && !isCallerPrivileged && !isCallerInstallerOnRecord) {
                throw new SecurityException("Querying upgrade or installer whitelist requires being installer on record or android.permission.WHITELIST_RESTRICTED_PERMISSIONS");
            }
            if (filterAppAccessLPr(packageSetting, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                return null;
            }
            long identity = Binder.clearCallingIdentity();
            try {
                return this.mPermissionManager.getWhitelistedRestrictedPermissions(pkg, whitelistFlags, userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public boolean addWhitelistedRestrictedPermission(String packageName, String permission, int whitelistFlags, int userId) {
        Preconditions.checkNotNull(permission);
        if (checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(permission)) {
            List<String> permissions = getWhitelistedRestrictedPermissions(packageName, whitelistFlags, userId);
            if (permissions == null) {
                permissions = new ArrayList(1);
            }
            if (permissions.indexOf(permission) < 0) {
                permissions.add(permission);
                return setWhitelistedRestrictedPermissions(packageName, permissions, whitelistFlags, userId);
            }
            return false;
        }
        return false;
    }

    private boolean checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(String permission) {
        synchronized (this.mPackages) {
            BasePermission bp = this.mPermissionManager.getPermissionTEMP(permission);
            if (bp == null) {
                Slog.w(TAG, "No such permissions: " + permission);
                return false;
            }
            if (bp.isHardOrSoftRestricted() && bp.isImmutablyRestricted() && this.mContext.checkCallingOrSelfPermission("android.permission.WHITELIST_RESTRICTED_PERMISSIONS") != 0) {
                throw new SecurityException("Cannot modify whitelisting of an immutably restricted permission: " + permission);
            }
            return true;
        }
    }

    public boolean removeWhitelistedRestrictedPermission(String packageName, String permission, int whitelistFlags, int userId) {
        List<String> permissions;
        Preconditions.checkNotNull(permission);
        if (checkExistsAndEnforceCannotModifyImmutablyRestrictedPermission(permission) && (permissions = getWhitelistedRestrictedPermissions(packageName, whitelistFlags, userId)) != null && permissions.remove(permission)) {
            return setWhitelistedRestrictedPermissions(packageName, permissions, whitelistFlags, userId);
        }
        return false;
    }

    /* JADX WARN: Code restructure failed: missing block: B:55:0x00ea, code lost:
        if (r9.isEmpty() == false) goto L60;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean setWhitelistedRestrictedPermissions(java.lang.String r17, java.util.List<java.lang.String> r18, int r19, int r20) {
        /*
            Method dump skipped, instructions count: 310
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.setWhitelistedRestrictedPermissions(java.lang.String, java.util.List, int, int):boolean");
    }

    public boolean shouldShowRequestPermissionRationale(String permissionName, String packageName, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            Context context = this.mContext;
            context.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "canShowRequestPermissionRationale for user " + userId);
        }
        int uid = getPackageUid(packageName, 268435456, userId);
        if (UserHandle.getAppId(getCallingUid()) == UserHandle.getAppId(uid) && checkPermission(permissionName, packageName, userId) != 0) {
            long identity = Binder.clearCallingIdentity();
            try {
                int flags = getPermissionFlags(permissionName, packageName, userId);
                Binder.restoreCallingIdentity(identity);
                return (flags & 22) == 0 && (flags & 1) != 0;
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
        return false;
    }

    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS", "addOnPermissionsChangeListener");
        synchronized (this.mPackages) {
            this.mOnPermissionChangeListeners.addListenerLocked(listener);
        }
    }

    public void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        synchronized (this.mPackages) {
            this.mOnPermissionChangeListeners.removeListenerLocked(listener);
        }
    }

    public boolean isProtectedBroadcast(String actionName) {
        synchronized (this.mProtectedBroadcasts) {
            if (this.mProtectedBroadcasts.contains(actionName)) {
                return true;
            }
            return actionName != null && (actionName.startsWith("android.net.netmon.lingerExpired") || actionName.startsWith("com.android.server.sip.SipWakeupTimer") || actionName.startsWith("com.android.internal.telephony.data-reconnect") || actionName.startsWith("android.net.netmon.launchCaptivePortalApp"));
        }
    }

    public int checkSignatures(String pkg1, String pkg2) {
        synchronized (this.mPackages) {
            PackageParser.Package p1 = this.mPackages.get(pkg1);
            PackageParser.Package p2 = this.mPackages.get(pkg2);
            if (p1 != null && p1.mExtras != null && p2 != null && p2.mExtras != null) {
                int callingUid = Binder.getCallingUid();
                int callingUserId = UserHandle.getUserId(callingUid);
                PackageSetting ps1 = (PackageSetting) p1.mExtras;
                PackageSetting ps2 = (PackageSetting) p2.mExtras;
                if (!filterAppAccessLPr(ps1, callingUid, callingUserId) && !filterAppAccessLPr(ps2, callingUid, callingUserId)) {
                    return PackageManagerServiceUtils.compareSignatures(p1.mSigningDetails.signatures, p2.mSigningDetails.signatures);
                }
                return -4;
            }
            return -4;
        }
    }

    public int checkUidSignatures(int uid1, int uid2) {
        Signature[] s1;
        Signature[] s2;
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        boolean isCallerInstantApp = getInstantAppPackageName(callingUid) != null;
        int appId1 = UserHandle.getAppId(uid1);
        int appId2 = UserHandle.getAppId(uid2);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(appId1);
            if (obj == null) {
                return -4;
            }
            if (obj instanceof SharedUserSetting) {
                if (isCallerInstantApp) {
                    return -4;
                }
                s1 = ((SharedUserSetting) obj).signatures.mSigningDetails.signatures;
            } else if (!(obj instanceof PackageSetting)) {
                return -4;
            } else {
                PackageSetting ps = (PackageSetting) obj;
                if (filterAppAccessLPr(ps, callingUid, callingUserId)) {
                    return -4;
                }
                s1 = ps.signatures.mSigningDetails.signatures;
            }
            Object obj2 = this.mSettings.getSettingLPr(appId2);
            if (obj2 == null) {
                return -4;
            }
            if (obj2 instanceof SharedUserSetting) {
                if (isCallerInstantApp) {
                    return -4;
                }
                s2 = ((SharedUserSetting) obj2).signatures.mSigningDetails.signatures;
            } else if (!(obj2 instanceof PackageSetting)) {
                return -4;
            } else {
                PackageSetting ps2 = (PackageSetting) obj2;
                if (filterAppAccessLPr(ps2, callingUid, callingUserId)) {
                    return -4;
                }
                s2 = ps2.signatures.mSigningDetails.signatures;
            }
            return PackageManagerServiceUtils.compareSignatures(s1, s2);
        }
    }

    public boolean hasSigningCertificate(String packageName, byte[] certificate, int type) {
        synchronized (this.mPackages) {
            PackageParser.Package p = this.mPackages.get(packageName);
            if (p != null && p.mExtras != null) {
                int callingUid = Binder.getCallingUid();
                int callingUserId = UserHandle.getUserId(callingUid);
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (filterAppAccessLPr(ps, callingUid, callingUserId)) {
                    return false;
                }
                if (type == 0) {
                    return p.mSigningDetails.hasCertificate(certificate);
                } else if (type != 1) {
                    return false;
                } else {
                    return p.mSigningDetails.hasSha256Certificate(certificate);
                }
            }
            return false;
        }
    }

    public boolean hasUidSigningCertificate(int uid, byte[] certificate, int type) {
        PackageParser.SigningDetails signingDetails;
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        int appId = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj == null) {
                return false;
            }
            if (obj instanceof SharedUserSetting) {
                boolean isCallerInstantApp = getInstantAppPackageName(callingUid) != null;
                if (isCallerInstantApp) {
                    return false;
                }
                signingDetails = ((SharedUserSetting) obj).signatures.mSigningDetails;
            } else if (!(obj instanceof PackageSetting)) {
                return false;
            } else {
                PackageSetting ps = (PackageSetting) obj;
                if (filterAppAccessLPr(ps, callingUid, callingUserId)) {
                    return false;
                }
                signingDetails = ps.signatures.mSigningDetails;
            }
            if (type == 0) {
                return signingDetails.hasCertificate(certificate);
            } else if (type != 1) {
                return false;
            } else {
                return signingDetails.hasSha256Certificate(certificate);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void killUid(int appId, int userId, String reason) {
        long identity = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killUid(appId, userId, reason);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isCompatSignatureUpdateNeeded(PackageParser.Package scannedPkg) {
        return isCompatSignatureUpdateNeeded(getSettingsVersionForPackage(scannedPkg));
    }

    private static boolean isCompatSignatureUpdateNeeded(Settings.VersionInfo ver) {
        return ver.databaseVersion < 2;
    }

    private boolean isRecoverSignatureUpdateNeeded(PackageParser.Package scannedPkg) {
        return isRecoverSignatureUpdateNeeded(getSettingsVersionForPackage(scannedPkg));
    }

    private static boolean isRecoverSignatureUpdateNeeded(Settings.VersionInfo ver) {
        return ver.databaseVersion < 3;
    }

    public List<String> getAllPackages() {
        enforceSystemOrRootOrShell("getAllPackages is limited to privileged callers");
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        synchronized (this.mPackages) {
            if (canViewInstantApps(callingUid, callingUserId)) {
                return new ArrayList(this.mPackages.keySet());
            }
            String instantAppPkgName = getInstantAppPackageName(callingUid);
            List<String> result = new ArrayList<>();
            if (instantAppPkgName != null) {
                for (PackageParser.Package pkg : this.mPackages.values()) {
                    if (pkg.visibleToInstantApps) {
                        result.add(pkg.packageName);
                    }
                }
            } else {
                for (PackageParser.Package pkg2 : this.mPackages.values()) {
                    PackageSetting ps = pkg2.mExtras != null ? (PackageSetting) pkg2.mExtras : null;
                    if (ps == null || !ps.getInstantApp(callingUserId) || this.mInstantAppRegistry.isInstantAccessGranted(callingUserId, UserHandle.getAppId(callingUid), ps.appId)) {
                        result.add(pkg2.packageName);
                    }
                }
            }
            return result;
        }
    }

    public String[] getPackagesForUid(int uid) {
        int callingUid = Binder.getCallingUid();
        int i = 0;
        boolean isCallerInstantApp = getInstantAppPackageName(callingUid) != null;
        int userId = UserHandle.getUserId(uid);
        int appId = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                if (isCallerInstantApp) {
                    return null;
                }
                SharedUserSetting sus = (SharedUserSetting) obj;
                int N = sus.packages.size();
                String[] res = new String[N];
                Iterator<PackageSetting> it = sus.packages.iterator();
                while (it.hasNext()) {
                    PackageSetting ps = it.next();
                    if (ps.getInstalled(userId)) {
                        res[i] = ps.name;
                        i++;
                    } else {
                        res = (String[]) ArrayUtils.removeElement(String.class, res, res[i]);
                    }
                }
                return res;
            }
            if (obj instanceof PackageSetting) {
                PackageSetting ps2 = (PackageSetting) obj;
                if (ps2.getInstalled(userId) && !filterAppAccessLPr(ps2, callingUid, userId)) {
                    return new String[]{ps2.name};
                }
            }
            return null;
        }
    }

    public String getNameForUid(int uid) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        int appId = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.name + ":" + sus.userId;
            } else if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting) obj;
                if (filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                    return null;
                }
                return ps.name;
            } else {
                return null;
            }
        }
    }

    public String[] getNamesForUids(int[] uids) {
        if (uids == null || uids.length == 0) {
            return null;
        }
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        String[] names = new String[uids.length];
        synchronized (this.mPackages) {
            for (int i = uids.length - 1; i >= 0; i--) {
                int appId = UserHandle.getAppId(uids[i]);
                Object obj = this.mSettings.getSettingLPr(appId);
                if (obj instanceof SharedUserSetting) {
                    SharedUserSetting sus = (SharedUserSetting) obj;
                    names[i] = "shared:" + sus.name;
                } else if (obj instanceof PackageSetting) {
                    PackageSetting ps = (PackageSetting) obj;
                    if (filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                        names[i] = null;
                    } else {
                        names[i] = ps.name;
                    }
                } else {
                    names[i] = null;
                }
            }
        }
        return names;
    }

    public int getUidForSharedUser(String sharedUserName) {
        SharedUserSetting suid;
        if (getInstantAppPackageName(Binder.getCallingUid()) == null && sharedUserName != null) {
            synchronized (this.mPackages) {
                try {
                    try {
                        suid = this.mSettings.getSharedUserLPw(sharedUserName, 0, 0, false);
                    } catch (PackageManagerException e) {
                    }
                    if (suid != null) {
                        return suid.userId;
                    }
                    return -1;
                } finally {
                }
            }
        }
        return -1;
    }

    public int getFlagsForUid(int uid) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        int appId = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.pkgFlags;
            } else if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting) obj;
                if (filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                    return 0;
                }
                return ps.pkgFlags;
            } else {
                return 0;
            }
        }
    }

    public int getPrivateFlagsForUid(int uid) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        int appId = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.pkgPrivateFlags;
            } else if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting) obj;
                if (filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                    return 0;
                }
                return ps.pkgPrivateFlags;
            } else {
                return 0;
            }
        }
    }

    public boolean isUidPrivileged(int uid) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        }
        int appId = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                SharedUserSetting sus = (SharedUserSetting) obj;
                Iterator<PackageSetting> it = sus.packages.iterator();
                while (it.hasNext()) {
                    if (it.next().isPrivileged()) {
                        return true;
                    }
                }
            } else if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting) obj;
                return ps.isPrivileged();
            }
            return false;
        }
    }

    public String[] getAppOpPermissionPackages(String permName) {
        return this.mPermissionManager.getAppOpPermissionPackages(permName);
    }

    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId) {
        return resolveIntentInternal(intent, resolvedType, flags, userId, false, Binder.getCallingUid());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ResolveInfo resolveIntentInternal(Intent intent, String resolvedType, int flags, int userId, boolean resolveForStart, int filterCallingUid) {
        try {
            Trace.traceBegin(262144L, "resolveIntent");
            if (sUserManager.exists(userId)) {
                int callingUid = Binder.getCallingUid();
                int flags2 = updateFlagsForResolve(flags, userId, intent, filterCallingUid, resolveForStart);
                try {
                    this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "resolve intent");
                    Trace.traceBegin(262144L, "queryIntentActivities");
                    List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags2, filterCallingUid, userId, resolveForStart, true);
                    Trace.traceEnd(262144L);
                    ResolveInfo bestChoice = chooseBestActivity(intent, resolvedType, flags2, query, userId);
                    Trace.traceEnd(262144L);
                    return bestChoice;
                } catch (Throwable th) {
                    th = th;
                    Trace.traceEnd(262144L);
                    throw th;
                }
            }
            Trace.traceEnd(262144L);
            return null;
        } catch (Throwable th2) {
            th = th2;
        }
    }

    public ResolveInfo findPersistentPreferredActivity(Intent intent, int userId) {
        ResolveInfo findPersistentPreferredActivityLP;
        if (!UserHandle.isSameApp(Binder.getCallingUid(), 1000)) {
            throw new SecurityException("findPersistentPreferredActivity can only be run by the system");
        }
        if (!sUserManager.exists(userId)) {
            return null;
        }
        int callingUid = Binder.getCallingUid();
        Intent intent2 = updateIntentForResolve(intent);
        String resolvedType = intent2.resolveTypeIfNeeded(this.mContext.getContentResolver());
        int flags = updateFlagsForResolve(0, userId, intent2, callingUid, false);
        List<ResolveInfo> query = queryIntentActivitiesInternal(intent2, resolvedType, flags, userId);
        synchronized (this.mPackages) {
            findPersistentPreferredActivityLP = findPersistentPreferredActivityLP(intent2, resolvedType, flags, query, false, userId);
        }
        return findPersistentPreferredActivityLP;
    }

    public void setLastChosenActivity(Intent intent, String resolvedType, int flags, IntentFilter filter, int match, ComponentName activity) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        int userId = UserHandle.getCallingUserId();
        intent.setComponent(null);
        List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags, userId);
        findPreferredActivityNotLocked(intent, resolvedType, flags, query, 0, false, true, false, userId);
        addPreferredActivityInternal(filter, match, null, activity, false, userId, "Setting last chosen");
    }

    public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        int userId = UserHandle.getCallingUserId();
        List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags, userId);
        return findPreferredActivityNotLocked(intent, resolvedType, flags, query, 0, false, false, false, userId);
    }

    private boolean areWebInstantAppsDisabled(int userId) {
        return this.mWebInstantAppsDisabled.get(userId);
    }

    private boolean isInstantAppResolutionAllowed(Intent intent, List<ResolveInfo> resolvedActivities, int userId, boolean skipPackageCheck) {
        if (this.mInstantAppResolverConnection != null && this.mInstantAppInstallerActivity != null && intent.getComponent() == null && (intent.getFlags() & 512) == 0) {
            if (skipPackageCheck || intent.getPackage() == null) {
                if (!intent.isWebIntent()) {
                    if ((resolvedActivities != null && resolvedActivities.size() != 0) || (intent.getFlags() & 2048) == 0) {
                        return false;
                    }
                } else if (intent.getData() == null || TextUtils.isEmpty(intent.getData().getHost()) || areWebInstantAppsDisabled(userId)) {
                    return false;
                }
                synchronized (this.mPackages) {
                    int count = resolvedActivities == null ? 0 : resolvedActivities.size();
                    for (int n = 0; n < count; n++) {
                        ResolveInfo info = resolvedActivities.get(n);
                        String packageName = info.activityInfo.packageName;
                        PackageSetting ps = this.mSettings.mPackages.get(packageName);
                        if (ps != null) {
                            if (!info.handleAllWebDataURI) {
                                long packedStatus = getDomainVerificationStatusLPr(ps, userId);
                                int status = (int) (packedStatus >> 32);
                                if (status == 2 || status == 4) {
                                    if (DEBUG_INSTANT) {
                                        Slog.v(TAG, "DENY instant app; pkg: " + packageName + ", status: " + status);
                                    }
                                    return false;
                                }
                            }
                            if (ps.getInstantApp(userId)) {
                                if (DEBUG_INSTANT) {
                                    Slog.v(TAG, "DENY instant app installed; pkg: " + packageName);
                                }
                                return false;
                            }
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj, Intent origIntent, String resolvedType, String callingPackage, Bundle verificationBundle, int userId) {
        Message msg = this.mHandler.obtainMessage(20, new InstantAppRequest(responseObj, origIntent, resolvedType, callingPackage, userId, verificationBundle, false));
        this.mHandler.sendMessage(msg);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType, int flags, List<ResolveInfo> query, int userId) {
        if (query != null) {
            int N = query.size();
            if (N == 1) {
                return query.get(0);
            }
            if (N > 1) {
                boolean debug = (intent.getFlags() & 8) != 0;
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                if (debug) {
                    Slog.v(TAG, r0.activityInfo.name + "=" + r0.priority + " vs " + r1.activityInfo.name + "=" + r1.priority);
                }
                if (r0.priority == r1.priority && r0.preferredOrder == r1.preferredOrder) {
                    if (r0.isDefault == r1.isDefault) {
                        intent.addPrivateFlags(64);
                        ResolveInfo ri = findPreferredActivityNotLocked(intent, resolvedType, flags, query, r0.priority, true, false, debug, userId);
                        if (ri != null) {
                            return ri;
                        }
                        for (int i = 0; i < N; i++) {
                            ResolveInfo ri2 = query.get(i);
                            if (ri2.activityInfo.applicationInfo.isInstantApp()) {
                                String packageName = ri2.activityInfo.packageName;
                                PackageSetting ps = this.mSettings.mPackages.get(packageName);
                                long packedStatus = getDomainVerificationStatusLPr(ps, userId);
                                int status = (int) (packedStatus >> 32);
                                if (status != 4) {
                                    return ri2;
                                }
                            }
                        }
                        ResolveInfo ri3 = new ResolveInfo(this.mResolveInfo);
                        ri3.activityInfo = new ActivityInfo(ri3.activityInfo);
                        ri3.activityInfo.labelRes = ResolverActivity.getLabelRes(intent.getAction());
                        String intentPackage = intent.getPackage();
                        if (!TextUtils.isEmpty(intentPackage) && allHavePackage(query, intentPackage)) {
                            ApplicationInfo appi = query.get(0).activityInfo.applicationInfo;
                            ri3.resolvePackageName = intentPackage;
                            if (userNeedsBadging(userId)) {
                                ri3.noResourceId = true;
                            } else {
                                ri3.icon = appi.icon;
                            }
                            ri3.iconResourceId = appi.icon;
                            ri3.labelRes = appi.labelRes;
                        }
                        ri3.activityInfo.applicationInfo = new ApplicationInfo(ri3.activityInfo.applicationInfo);
                        if (userId != 0) {
                            ri3.activityInfo.applicationInfo.uid = UserHandle.getUid(userId, UserHandle.getAppId(ri3.activityInfo.applicationInfo.uid));
                        }
                        if (ri3.activityInfo.metaData == null) {
                            ri3.activityInfo.metaData = new Bundle();
                        }
                        ri3.activityInfo.metaData.putBoolean("android.dock_home", true);
                        return ri3;
                    }
                }
                return query.get(0);
            }
            return null;
        }
        return null;
    }

    private boolean allHavePackage(List<ResolveInfo> list, String packageName) {
        if (ArrayUtils.isEmpty(list)) {
            return false;
        }
        int N = list.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo ri = list.get(i);
            ActivityInfo ai = ri != null ? ri.activityInfo : null;
            if (ai == null || !packageName.equals(ai.packageName)) {
                return false;
            }
        }
        return true;
    }

    @GuardedBy({"mPackages"})
    private ResolveInfo findPersistentPreferredActivityLP(Intent intent, String resolvedType, int flags, List<ResolveInfo> query, boolean debug, int userId) {
        List<PersistentPreferredActivity> pprefs;
        PackageManagerService packageManagerService = this;
        int i = flags;
        int N = query.size();
        PersistentPreferredIntentResolver ppir = packageManagerService.mSettings.mPersistentPreferredActivities.get(userId);
        if (debug) {
            Slog.v(TAG, "Looking for presistent preferred activities...");
        }
        int i2 = 0;
        if (ppir != null) {
            pprefs = ppir.queryIntent(intent, resolvedType, (65536 & i) != 0, userId);
        } else {
            pprefs = null;
        }
        if (pprefs != null && pprefs.size() > 0) {
            int M = pprefs.size();
            int i3 = 0;
            while (i3 < M) {
                PersistentPreferredActivity ppa = pprefs.get(i3);
                if (debug) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Checking PersistentPreferredActivity ds=");
                    sb.append(ppa.countDataSchemes() > 0 ? ppa.getDataScheme(i2) : "<none>");
                    sb.append("\n  component=");
                    sb.append(ppa.mComponent);
                    Slog.v(TAG, sb.toString());
                    ppa.dump(new LogPrinter(2, TAG, 3), "  ");
                }
                ActivityInfo ai = packageManagerService.getActivityInfo(ppa.mComponent, i | 512, userId);
                if (debug) {
                    Slog.v(TAG, "Found persistent preferred activity:");
                    if (ai == null) {
                        Slog.v(TAG, "  null");
                    } else {
                        ai.dump(new LogPrinter(2, TAG, 3), "  ");
                    }
                }
                if (ai != null) {
                    for (int j = 0; j < N; j++) {
                        ResolveInfo ri = query.get(j);
                        if (ri.activityInfo.applicationInfo.packageName.equals(ai.applicationInfo.packageName) && ri.activityInfo.name.equals(ai.name)) {
                            if (debug) {
                                Slog.v(TAG, "Returning persistent preferred activity: " + ri.activityInfo.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + ri.activityInfo.name);
                            }
                            return ri;
                        }
                    }
                    continue;
                }
                i3++;
                i2 = 0;
                packageManagerService = this;
                i = flags;
            }
            return null;
        }
        return null;
    }

    private boolean isHomeIntent(Intent intent) {
        return "android.intent.action.MAIN".equals(intent.getAction()) && intent.hasCategory("android.intent.category.HOME") && intent.hasCategory("android.intent.category.DEFAULT");
    }

    /* JADX WARN: Removed duplicated region for block: B:164:0x03bd A[Catch: all -> 0x03d9, TryCatch #11 {all -> 0x03d9, blocks: (B:164:0x03bd, B:166:0x03c1, B:151:0x0382, B:147:0x0377, B:148:0x037a, B:138:0x0342, B:139:0x0345, B:175:0x03d7, B:158:0x03a4, B:168:0x03c8), top: B:199:0x0080 }] */
    /* JADX WARN: Removed duplicated region for block: B:170:0x03cb  */
    /* JADX WARN: Removed duplicated region for block: B:217:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    android.content.pm.ResolveInfo findPreferredActivityNotLocked(android.content.Intent r32, java.lang.String r33, int r34, java.util.List<android.content.pm.ResolveInfo> r35, int r36, boolean r37, boolean r38, boolean r39, int r40) {
        /*
            Method dump skipped, instructions count: 987
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.findPreferredActivityNotLocked(android.content.Intent, java.lang.String, int, java.util.List, int, boolean, boolean, boolean, int):android.content.pm.ResolveInfo");
    }

    public boolean canForwardTo(Intent intent, String resolvedType, int sourceUserId, int targetUserId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        List<CrossProfileIntentFilter> matches = getMatchingCrossProfileIntentFilters(intent, resolvedType, sourceUserId);
        boolean z = true;
        if (matches != null) {
            int size = matches.size();
            for (int i = 0; i < size; i++) {
                if (matches.get(i).getTargetUserId() == targetUserId) {
                    return true;
                }
            }
        }
        if (intent.hasWebURI()) {
            int callingUid = Binder.getCallingUid();
            UserInfo parent = getProfileParent(sourceUserId);
            synchronized (this.mPackages) {
                int flags = updateFlagsForResolve(0, parent.id, intent, callingUid, false);
                CrossProfileDomainInfo xpDomainInfo = getCrossProfileDomainPreferredLpr(intent, resolvedType, flags, sourceUserId, parent.id);
                if (xpDomainInfo == null) {
                    z = false;
                }
            }
            return z;
        }
        return false;
    }

    private UserInfo getProfileParent(int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            return sUserManager.getProfileParent(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(Intent intent, String resolvedType, int userId) {
        CrossProfileIntentResolver resolver = this.mSettings.mCrossProfileIntentResolvers.get(userId);
        if (resolver != null) {
            return resolver.queryIntent(intent, resolvedType, false, userId);
        }
        return null;
    }

    public ParceledListSlice<ResolveInfo> queryIntentActivities(Intent intent, String resolvedType, int flags, int userId) {
        try {
            Trace.traceBegin(262144L, "queryIntentActivities");
            return new ParceledListSlice<>(queryIntentActivitiesInternal(intent, resolvedType, flags, userId));
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getInstantAppPackageName(int callingUid) {
        synchronized (this.mPackages) {
            if (Process.isIsolated(callingUid)) {
                callingUid = this.mIsolatedOwners.get(callingUid);
            }
            int appId = UserHandle.getAppId(callingUid);
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj instanceof PackageSetting) {
                PackageSetting ps = (PackageSetting) obj;
                boolean isInstantApp = ps.getInstantApp(UserHandle.getUserId(callingUid));
                return isInstantApp ? ps.pkg.packageName : null;
            }
            return null;
        }
    }

    private List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType, int flags, int userId) {
        return queryIntentActivitiesInternal(intent, resolvedType, flags, Binder.getCallingUid(), userId, false, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:109:0x01de A[Catch: all -> 0x02ba, TRY_LEAVE, TryCatch #12 {all -> 0x02ba, blocks: (B:107:0x01d8, B:109:0x01de), top: B:214:0x01d8 }] */
    /* JADX WARN: Removed duplicated region for block: B:148:0x02ab  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivitiesInternal(android.content.Intent r26, java.lang.String r27, int r28, int r29, int r30, boolean r31, boolean r32) {
        /*
            Method dump skipped, instructions count: 897
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.queryIntentActivitiesInternal(android.content.Intent, java.lang.String, int, int, int, boolean, boolean):java.util.List");
    }

    /* JADX WARN: Removed duplicated region for block: B:29:0x00a6  */
    /* JADX WARN: Removed duplicated region for block: B:32:0x00e9  */
    /* JADX WARN: Removed duplicated region for block: B:44:0x0138  */
    /* JADX WARN: Removed duplicated region for block: B:52:0x016a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private java.util.List<android.content.pm.ResolveInfo> maybeAddInstantAppInstaller(java.util.List<android.content.pm.ResolveInfo> r22, android.content.Intent r23, java.lang.String r24, int r25, int r26, boolean r27) {
        /*
            Method dump skipped, instructions count: 372
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.maybeAddInstantAppInstaller(java.util.List, android.content.Intent, java.lang.String, int, int, boolean):java.util.List");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class CrossProfileDomainInfo {
        int bestDomainVerificationStatus;
        ResolveInfo resolveInfo;

        private CrossProfileDomainInfo() {
        }

        /* synthetic */ CrossProfileDomainInfo(AnonymousClass1 x0) {
            this();
        }
    }

    private CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent, String resolvedType, int flags, int sourceUserId, int parentUserId) {
        List<ResolveInfo> resultTargetUser;
        List<ResolveInfo> resultTargetUser2;
        if (sUserManager.hasUserRestriction("allow_parent_profile_app_linking", sourceUserId) && (resultTargetUser = this.mComponentResolver.queryActivities(intent, resolvedType, flags, parentUserId)) != null && !resultTargetUser.isEmpty()) {
            CrossProfileDomainInfo result = null;
            int size = resultTargetUser.size();
            int i = 0;
            while (i < size) {
                ResolveInfo riTargetUser = resultTargetUser.get(i);
                if (riTargetUser.handleAllWebDataURI) {
                    resultTargetUser2 = resultTargetUser;
                } else {
                    String packageName = riTargetUser.activityInfo.packageName;
                    PackageSetting ps = this.mSettings.mPackages.get(packageName);
                    if (ps == null) {
                        resultTargetUser2 = resultTargetUser;
                    } else {
                        long verificationState = getDomainVerificationStatusLPr(ps, parentUserId);
                        int status = (int) (verificationState >> 32);
                        if (result == null) {
                            resultTargetUser2 = resultTargetUser;
                            CrossProfileDomainInfo result2 = new CrossProfileDomainInfo(null);
                            result2.resolveInfo = createForwardingResolveInfoUnchecked(new IntentFilter(), sourceUserId, parentUserId);
                            result2.bestDomainVerificationStatus = status;
                            result = result2;
                        } else {
                            resultTargetUser2 = resultTargetUser;
                            result.bestDomainVerificationStatus = bestDomainVerificationStatus(status, result.bestDomainVerificationStatus);
                        }
                    }
                }
                i++;
                resultTargetUser = resultTargetUser2;
            }
            if (result != null && result.bestDomainVerificationStatus == 3) {
                return null;
            }
            return result;
        }
        return null;
    }

    private int bestDomainVerificationStatus(int status1, int status2) {
        if (status1 == 3) {
            return status2;
        }
        if (status2 == 3) {
            return status1;
        }
        return (int) MathUtils.max(status1, status2);
    }

    private boolean isUserEnabled(int userId) {
        boolean z;
        long callingId = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = sUserManager.getUserInfo(userId);
            if (userInfo != null) {
                if (userInfo.isEnabled()) {
                    z = true;
                    return z;
                }
            }
            z = false;
            return z;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private List<ResolveInfo> filterIfNotSystemUser(List<ResolveInfo> resolveInfos, int userId) {
        if (userId == 0) {
            return resolveInfos;
        }
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            ResolveInfo info = resolveInfos.get(i);
            if ((info.activityInfo.flags & 536870912) != 0) {
                resolveInfos.remove(i);
            }
        }
        return resolveInfos;
    }

    private List<ResolveInfo> applyPostResolutionFilter(List<ResolveInfo> resolveInfos, String ephemeralPkgName, boolean allowDynamicSplits, int filterCallingUid, boolean resolveForStart, int userId, Intent intent) {
        boolean blockInstant = intent.isWebIntent() && areWebInstantAppsDisabled(userId);
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            ResolveInfo info = resolveInfos.get(i);
            if (info.isInstantAppAvailable && blockInstant) {
                resolveInfos.remove(i);
            } else {
                if (allowDynamicSplits && info.activityInfo != null && info.activityInfo.splitName != null) {
                    if (!ArrayUtils.contains(info.activityInfo.applicationInfo.splitNames, info.activityInfo.splitName)) {
                        if (this.mInstantAppInstallerActivity == null) {
                            resolveInfos.remove(i);
                        } else if (blockInstant && isInstantApp(info.activityInfo.packageName, userId)) {
                            resolveInfos.remove(i);
                        } else {
                            ResolveInfo installerInfo = new ResolveInfo(this.mInstantAppInstallerInfo);
                            ComponentName installFailureActivity = findInstallFailureActivity(info.activityInfo.packageName, filterCallingUid, userId);
                            installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo(installFailureActivity, info.activityInfo.packageName, info.activityInfo.applicationInfo.longVersionCode, info.activityInfo.splitName);
                            installerInfo.filter = new IntentFilter();
                            installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                            installerInfo.labelRes = info.resolveLabelResId();
                            installerInfo.icon = info.resolveIconResId();
                            installerInfo.isInstantAppAvailable = true;
                            resolveInfos.set(i, installerInfo);
                        }
                    }
                }
                if (ephemeralPkgName != null && !ephemeralPkgName.equals(info.activityInfo.packageName) && (!resolveForStart || ((!intent.isWebIntent() && (intent.getFlags() & 2048) == 0) || intent.getPackage() != null || intent.getComponent() != null))) {
                    boolean isEphemeralApp = info.activityInfo.applicationInfo.isInstantApp();
                    if (isEphemeralApp || (info.activityInfo.flags & 1048576) == 0) {
                        resolveInfos.remove(i);
                    }
                }
            }
        }
        return resolveInfos;
    }

    private ComponentName findInstallFailureActivity(String packageName, int filterCallingUid, int userId) {
        Intent failureActivityIntent = new Intent("android.intent.action.INSTALL_FAILURE");
        failureActivityIntent.setPackage(packageName);
        List<ResolveInfo> result = queryIntentActivitiesInternal(failureActivityIntent, null, 0, filterCallingUid, userId, false, false);
        int NR = result.size();
        if (NR > 0) {
            for (int i = 0; i < NR; i++) {
                ResolveInfo info = result.get(i);
                if (info.activityInfo.splitName == null) {
                    return new ComponentName(packageName, info.activityInfo.name);
                }
            }
            return null;
        }
        return null;
    }

    private boolean hasNonNegativePriority(List<ResolveInfo> resolveInfos) {
        return resolveInfos.size() > 0 && resolveInfos.get(0).priority >= 0;
    }

    private List<ResolveInfo> filterCandidatesWithDomainPreferredActivitiesLPr(Intent intent, int matchFlags, List<ResolveInfo> candidates, CrossProfileDomainInfo xpDomainInfo, int userId) {
        int maxMatchPrio;
        PackageManagerService packageManagerService = this;
        List<ResolveInfo> list = candidates;
        int i = userId;
        boolean debug = (intent.getFlags() & 8) != 0;
        ArrayList<ResolveInfo> result = new ArrayList<>();
        ArrayList<ResolveInfo> alwaysList = new ArrayList<>();
        ArrayList<ResolveInfo> undefinedList = new ArrayList<>();
        ArrayList<ResolveInfo> alwaysAskList = new ArrayList<>();
        ArrayList<ResolveInfo> neverList = new ArrayList<>();
        ArrayList<ResolveInfo> matchAllList = new ArrayList<>();
        synchronized (packageManagerService.mPackages) {
            try {
                try {
                    int count = candidates.size();
                    int n = 0;
                    while (n < count) {
                        try {
                            ResolveInfo info = list.get(n);
                            String packageName = info.activityInfo.packageName;
                            int count2 = count;
                            PackageSetting ps = packageManagerService.mSettings.mPackages.get(packageName);
                            if (ps != null) {
                                if (info.handleAllWebDataURI) {
                                    matchAllList.add(info);
                                } else {
                                    long packedStatus = packageManagerService.getDomainVerificationStatusLPr(ps, i);
                                    int status = (int) (packedStatus >> 32);
                                    int linkGeneration = (int) (packedStatus & (-1));
                                    if (status == 2) {
                                        if (debug) {
                                            Slog.i(TAG, "  + always: " + info.activityInfo.packageName + " : linkgen=" + linkGeneration);
                                        }
                                        info.preferredOrder = linkGeneration;
                                        alwaysList.add(info);
                                    } else if (status == 3) {
                                        if (debug) {
                                            Slog.i(TAG, "  + never: " + info.activityInfo.packageName);
                                        }
                                        neverList.add(info);
                                    } else if (status == 4) {
                                        if (debug) {
                                            Slog.i(TAG, "  + always-ask: " + info.activityInfo.packageName);
                                        }
                                        alwaysAskList.add(info);
                                    } else {
                                        if (status != 0 && status != 1) {
                                        }
                                        if (debug) {
                                            Slog.i(TAG, "  + ask: " + info.activityInfo.packageName);
                                        }
                                        undefinedList.add(info);
                                    }
                                }
                            }
                            n++;
                            packageManagerService = this;
                            list = candidates;
                            i = userId;
                            count = count2;
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                    boolean includeBrowser = false;
                    if (alwaysList.size() > 0) {
                        result.addAll(alwaysList);
                    } else {
                        result.addAll(undefinedList);
                        if (xpDomainInfo != null && xpDomainInfo.bestDomainVerificationStatus != 3) {
                            result.add(xpDomainInfo.resolveInfo);
                        }
                        includeBrowser = true;
                    }
                    if (alwaysAskList.size() > 0) {
                        Iterator<ResolveInfo> it = result.iterator();
                        while (it.hasNext()) {
                            ResolveInfo i2 = it.next();
                            i2.preferredOrder = 0;
                        }
                        result.addAll(alwaysAskList);
                        includeBrowser = true;
                    }
                    if (includeBrowser) {
                        if ((matchFlags & 131072) != 0) {
                            result.addAll(matchAllList);
                        } else {
                            String defaultBrowserPackageName = getDefaultBrowserPackageName(userId);
                            ResolveInfo defaultBrowserMatch = null;
                            int numCandidates = matchAllList.size();
                            int maxMatchPrio2 = 0;
                            int maxMatchPrio3 = 0;
                            while (maxMatchPrio3 < numCandidates) {
                                ResolveInfo info2 = matchAllList.get(maxMatchPrio3);
                                if (info2.priority > maxMatchPrio2) {
                                    maxMatchPrio2 = info2.priority;
                                }
                                if (!info2.activityInfo.packageName.equals(defaultBrowserPackageName)) {
                                    maxMatchPrio = maxMatchPrio2;
                                } else {
                                    if (defaultBrowserMatch != null) {
                                        int i3 = defaultBrowserMatch.priority;
                                        maxMatchPrio = maxMatchPrio2;
                                        int maxMatchPrio4 = info2.priority;
                                        if (i3 < maxMatchPrio4) {
                                        }
                                    } else {
                                        maxMatchPrio = maxMatchPrio2;
                                    }
                                    if (debug) {
                                        Slog.v(TAG, "Considering default browser match " + info2);
                                    }
                                    defaultBrowserMatch = info2;
                                }
                                maxMatchPrio3++;
                                maxMatchPrio2 = maxMatchPrio;
                            }
                            if (defaultBrowserMatch != null && defaultBrowserMatch.priority >= maxMatchPrio2 && !TextUtils.isEmpty(defaultBrowserPackageName)) {
                                if (debug) {
                                    Slog.v(TAG, "Default browser match " + defaultBrowserMatch);
                                }
                                result.add(defaultBrowserMatch);
                            } else {
                                result.addAll(matchAllList);
                            }
                        }
                        int maxMatchPrio5 = result.size();
                        if (maxMatchPrio5 == 0) {
                            result.addAll(candidates);
                            result.removeAll(neverList);
                        }
                    }
                    return result;
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private long getDomainVerificationStatusLPr(PackageSetting ps, int userId) {
        long result = ps.getDomainVerificationStatusForUser(userId);
        if ((result >> 32) == 0 && ps.getIntentFilterVerificationInfo() != null) {
            return ps.getIntentFilterVerificationInfo().getStatus() << 32;
        }
        return result;
    }

    private ResolveInfo querySkipCurrentProfileIntents(List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType, int flags, int sourceUserId) {
        ResolveInfo resolveInfo;
        if (matchingFilters != null) {
            int size = matchingFilters.size();
            for (int i = 0; i < size; i++) {
                CrossProfileIntentFilter filter = matchingFilters.get(i);
                if ((filter.getFlags() & 2) != 0 && (resolveInfo = createForwardingResolveInfo(filter, intent, resolvedType, flags, sourceUserId)) != null) {
                    return resolveInfo;
                }
            }
            return null;
        }
        return null;
    }

    private ResolveInfo queryCrossProfileIntents(List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType, int flags, int sourceUserId, boolean matchInCurrentProfile) {
        if (matchingFilters != null) {
            SparseBooleanArray alreadyTriedUserIds = new SparseBooleanArray();
            int size = matchingFilters.size();
            for (int i = 0; i < size; i++) {
                CrossProfileIntentFilter filter = matchingFilters.get(i);
                int targetUserId = filter.getTargetUserId();
                boolean skipCurrentProfile = (filter.getFlags() & 2) != 0;
                boolean skipCurrentProfileIfNoMatchFound = (filter.getFlags() & 4) != 0;
                if (!skipCurrentProfile && !alreadyTriedUserIds.get(targetUserId) && (!skipCurrentProfileIfNoMatchFound || !matchInCurrentProfile)) {
                    ResolveInfo resolveInfo = createForwardingResolveInfo(filter, intent, resolvedType, flags, sourceUserId);
                    if (resolveInfo != null) {
                        return resolveInfo;
                    }
                    alreadyTriedUserIds.put(targetUserId, true);
                }
            }
            return null;
        }
        return null;
    }

    private ResolveInfo createForwardingResolveInfo(CrossProfileIntentFilter filter, Intent intent, String resolvedType, int flags, int sourceUserId) {
        int targetUserId = filter.getTargetUserId();
        List<ResolveInfo> resultTargetUser = this.mComponentResolver.queryActivities(intent, resolvedType, flags, targetUserId);
        if (resultTargetUser != null && isUserEnabled(targetUserId)) {
            for (int i = resultTargetUser.size() - 1; i >= 0; i--) {
                if ((resultTargetUser.get(i).activityInfo.applicationInfo.flags & 1073741824) == 0) {
                    return createForwardingResolveInfoUnchecked(filter, sourceUserId, targetUserId);
                }
            }
            return null;
        }
        return null;
    }

    private ResolveInfo createForwardingResolveInfoUnchecked(IntentFilter filter, int sourceUserId, int targetUserId) {
        String className;
        ResolveInfo forwardingResolveInfo = new ResolveInfo();
        long ident = Binder.clearCallingIdentity();
        try {
            boolean targetIsProfile = sUserManager.getUserInfo(targetUserId).isManagedProfile();
            if (targetIsProfile) {
                className = IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE;
            } else {
                className = IntentForwarderActivity.FORWARD_INTENT_TO_PARENT;
            }
            ComponentName forwardingActivityComponentName = new ComponentName(this.mAndroidApplication.packageName, className);
            ActivityInfo forwardingActivityInfo = getActivityInfo(forwardingActivityComponentName, 0, sourceUserId);
            if (!targetIsProfile) {
                forwardingActivityInfo.showUserIcon = targetUserId;
                forwardingResolveInfo.noResourceId = true;
            }
            forwardingResolveInfo.activityInfo = forwardingActivityInfo;
            forwardingResolveInfo.priority = 0;
            forwardingResolveInfo.preferredOrder = 0;
            forwardingResolveInfo.match = 0;
            forwardingResolveInfo.isDefault = true;
            forwardingResolveInfo.filter = filter;
            forwardingResolveInfo.targetUserId = targetUserId;
            return forwardingResolveInfo;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public ParceledListSlice<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics, String[] specificTypes, Intent intent, String resolvedType, int flags, int userId) {
        return new ParceledListSlice<>(queryIntentActivityOptionsInternal(caller, specifics, specificTypes, intent, resolvedType, flags, userId));
    }

    /* JADX WARN: Removed duplicated region for block: B:33:0x0090  */
    /* JADX WARN: Removed duplicated region for block: B:46:0x00d1  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptionsInternal(android.content.ComponentName r18, android.content.Intent[] r19, java.lang.String[] r20, android.content.Intent r21, java.lang.String r22, int r23, int r24) {
        /*
            Method dump skipped, instructions count: 405
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.queryIntentActivityOptionsInternal(android.content.ComponentName, android.content.Intent[], java.lang.String[], android.content.Intent, java.lang.String, int, int):java.util.List");
    }

    public ParceledListSlice<ResolveInfo> queryIntentReceivers(Intent intent, String resolvedType, int flags, int userId) {
        return new ParceledListSlice<>(queryIntentReceiversInternal(intent, resolvedType, flags, userId, false));
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:60:0x00c8  */
    /* JADX WARN: Removed duplicated region for block: B:61:0x00d9  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public java.util.List<android.content.pm.ResolveInfo> queryIntentReceiversInternal(android.content.Intent r21, java.lang.String r22, int r23, int r24, boolean r25) {
        /*
            Method dump skipped, instructions count: 328
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.queryIntentReceiversInternal(android.content.Intent, java.lang.String, int, int, boolean):java.util.List");
    }

    public ResolveInfo resolveService(Intent intent, String resolvedType, int flags, int userId) {
        int callingUid = Binder.getCallingUid();
        return resolveServiceInternal(intent, resolvedType, flags, userId, callingUid);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ResolveInfo resolveServiceInternal(Intent intent, String resolvedType, int flags, int userId, int callingUid) {
        List<ResolveInfo> query;
        if (sUserManager.exists(userId) && (query = queryIntentServicesInternal(intent, resolvedType, updateFlagsForResolve(flags, userId, intent, callingUid, false), userId, callingUid, false)) != null && query.size() >= 1) {
            return query.get(0);
        }
        return null;
    }

    public ParceledListSlice<ResolveInfo> queryIntentServices(Intent intent, String resolvedType, int flags, int userId) {
        int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(queryIntentServicesInternal(intent, resolvedType, flags, userId, callingUid, false));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType, int flags, int userId, int callingUid, boolean includeInstantApps) {
        Intent intent2;
        ComponentName comp;
        if (sUserManager.exists(userId)) {
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "query intent receivers");
            String instantAppPkgName = getInstantAppPackageName(callingUid);
            int flags2 = updateFlagsForResolve(flags, userId, intent, callingUid, includeInstantApps);
            ComponentName comp2 = intent.getComponent();
            if (comp2 == null && intent.getSelector() != null) {
                Intent intent3 = intent.getSelector();
                comp = intent3.getComponent();
                intent2 = intent3;
            } else {
                intent2 = intent;
                comp = comp2;
            }
            if (comp != null) {
                List<ResolveInfo> list = new ArrayList<>(1);
                ServiceInfo si = getServiceInfo(comp, flags2, userId);
                if (si != null) {
                    boolean blockResolution = false;
                    boolean matchInstantApp = (8388608 & flags2) != 0;
                    boolean matchVisibleToInstantAppOnly = (16777216 & flags2) != 0;
                    boolean isCallerInstantApp = instantAppPkgName != null;
                    boolean isTargetSameInstantApp = comp.getPackageName().equals(instantAppPkgName);
                    boolean isTargetInstantApp = (si.applicationInfo.privateFlags & 128) != 0;
                    boolean isTargetHiddenFromInstantApp = (si.flags & 1048576) == 0;
                    if (!isTargetSameInstantApp && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp) || (matchVisibleToInstantAppOnly && isCallerInstantApp && isTargetHiddenFromInstantApp))) {
                        blockResolution = true;
                    }
                    if (!blockResolution) {
                        ResolveInfo ri = new ResolveInfo();
                        ri.serviceInfo = si;
                        list.add(ri);
                    }
                }
                return list;
            }
            synchronized (this.mPackages) {
                try {
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    String pkgName = intent2.getPackage();
                    if (pkgName != null) {
                        PackageParser.Package pkg = this.mPackages.get(pkgName);
                        if (pkg != null) {
                            return applyPostServiceResolutionFilter(this.mComponentResolver.queryServices(intent2, resolvedType, flags2, pkg.services, userId), instantAppPkgName);
                        }
                        return Collections.emptyList();
                    }
                    return applyPostServiceResolutionFilter(this.mComponentResolver.queryServices(intent2, resolvedType, flags2, userId), instantAppPkgName);
                } catch (Throwable th2) {
                    th = th2;
                    throw th;
                }
            }
        }
        return Collections.emptyList();
    }

    private List<ResolveInfo> applyPostServiceResolutionFilter(List<ResolveInfo> resolveInfos, String instantAppPkgName) {
        if (instantAppPkgName == null) {
            return resolveInfos;
        }
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            ResolveInfo info = resolveInfos.get(i);
            boolean isEphemeralApp = info.serviceInfo.applicationInfo.isInstantApp();
            if (isEphemeralApp && instantAppPkgName.equals(info.serviceInfo.packageName)) {
                if (info.serviceInfo.splitName != null && !ArrayUtils.contains(info.serviceInfo.applicationInfo.splitNames, info.serviceInfo.splitName)) {
                    if (DEBUG_INSTANT) {
                        Slog.v(TAG, "Adding ephemeral installer to the ResolveInfo list");
                    }
                    ResolveInfo installerInfo = new ResolveInfo(this.mInstantAppInstallerInfo);
                    installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo((ComponentName) null, info.serviceInfo.packageName, info.serviceInfo.applicationInfo.longVersionCode, info.serviceInfo.splitName);
                    installerInfo.filter = new IntentFilter();
                    installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                    resolveInfos.set(i, installerInfo);
                }
            } else if (isEphemeralApp || (info.serviceInfo.flags & 1048576) == 0) {
                resolveInfos.remove(i);
            }
        }
        return resolveInfos;
    }

    public ParceledListSlice<ResolveInfo> queryIntentContentProviders(Intent intent, String resolvedType, int flags, int userId) {
        return new ParceledListSlice<>(queryIntentContentProvidersInternal(intent, resolvedType, flags, userId));
    }

    private List<ResolveInfo> queryIntentContentProvidersInternal(Intent intent, String resolvedType, int flags, int userId) {
        Intent intent2;
        ComponentName comp;
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            String instantAppPkgName = getInstantAppPackageName(callingUid);
            int flags2 = updateFlagsForResolve(flags, userId, intent, callingUid, false);
            ComponentName comp2 = intent.getComponent();
            if (comp2 == null && intent.getSelector() != null) {
                Intent intent3 = intent.getSelector();
                comp = intent3.getComponent();
                intent2 = intent3;
            } else {
                intent2 = intent;
                comp = comp2;
            }
            if (comp != null) {
                List<ResolveInfo> list = new ArrayList<>(1);
                ProviderInfo pi = getProviderInfo(comp, flags2, userId);
                if (pi != null) {
                    boolean blockResolution = false;
                    boolean matchInstantApp = (8388608 & flags2) != 0;
                    boolean matchVisibleToInstantAppOnly = (16777216 & flags2) != 0;
                    boolean isCallerInstantApp = instantAppPkgName != null;
                    boolean isTargetSameInstantApp = comp.getPackageName().equals(instantAppPkgName);
                    boolean isTargetInstantApp = (pi.applicationInfo.privateFlags & 128) != 0;
                    boolean isTargetHiddenFromInstantApp = (pi.flags & 1048576) == 0;
                    if (!isTargetSameInstantApp && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp) || (matchVisibleToInstantAppOnly && isCallerInstantApp && isTargetHiddenFromInstantApp))) {
                        blockResolution = true;
                    }
                    if (!blockResolution) {
                        ResolveInfo ri = new ResolveInfo();
                        ri.providerInfo = pi;
                        list.add(ri);
                    }
                }
                return list;
            }
            synchronized (this.mPackages) {
                try {
                    try {
                        String pkgName = intent2.getPackage();
                        if (pkgName != null) {
                            PackageParser.Package pkg = this.mPackages.get(pkgName);
                            if (pkg != null) {
                                return applyPostContentProviderResolutionFilter(this.mComponentResolver.queryProviders(intent2, resolvedType, flags2, pkg.providers, userId), instantAppPkgName);
                            }
                            return Collections.emptyList();
                        }
                        return applyPostContentProviderResolutionFilter(this.mComponentResolver.queryProviders(intent2, resolvedType, flags2, userId), instantAppPkgName);
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                    throw th;
                }
            }
        }
        return Collections.emptyList();
    }

    private List<ResolveInfo> applyPostContentProviderResolutionFilter(List<ResolveInfo> resolveInfos, String instantAppPkgName) {
        if (instantAppPkgName == null) {
            return resolveInfos;
        }
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            ResolveInfo info = resolveInfos.get(i);
            boolean isEphemeralApp = info.providerInfo.applicationInfo.isInstantApp();
            if (isEphemeralApp && instantAppPkgName.equals(info.providerInfo.packageName)) {
                if (info.providerInfo.splitName != null && !ArrayUtils.contains(info.providerInfo.applicationInfo.splitNames, info.providerInfo.splitName)) {
                    if (DEBUG_INSTANT) {
                        Slog.v(TAG, "Adding ephemeral installer to the ResolveInfo list");
                    }
                    ResolveInfo installerInfo = new ResolveInfo(this.mInstantAppInstallerInfo);
                    installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo((ComponentName) null, info.providerInfo.packageName, info.providerInfo.applicationInfo.longVersionCode, info.providerInfo.splitName);
                    installerInfo.filter = new IntentFilter();
                    installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                    resolveInfos.set(i, installerInfo);
                }
            } else if (isEphemeralApp || (info.providerInfo.flags & 1048576) == 0) {
                resolveInfos.remove(i);
            }
        }
        return resolveInfos;
    }

    public ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId) {
        ArrayList<PackageInfo> list;
        ParceledListSlice<PackageInfo> parceledListSlice;
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return ParceledListSlice.emptyList();
        }
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForPackage(flags, userId, null);
            boolean listUninstalled = (4202496 & flags2) != 0;
            boolean listApex = (1073741824 & flags2) != 0;
            boolean listFactory = (2097152 & flags2) != 0;
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get installed packages");
            synchronized (this.mPackages) {
                if (listUninstalled) {
                    list = new ArrayList<>(this.mSettings.mPackages.size());
                    for (PackageSetting ps : this.mSettings.mPackages.values()) {
                        if (!filterSharedLibPackageLPr(ps, callingUid, userId, flags2) && !filterAppAccessLPr(ps, callingUid, userId)) {
                            PackageInfo pi = generatePackageInfo(ps, flags2, userId);
                            if (pi != null) {
                                list.add(pi);
                            }
                        }
                    }
                } else {
                    list = new ArrayList<>(this.mPackages.size());
                    for (PackageParser.Package p : this.mPackages.values()) {
                        PackageSetting ps2 = (PackageSetting) p.mExtras;
                        if (!filterSharedLibPackageLPr(ps2, callingUid, userId, flags2) && !filterAppAccessLPr(ps2, callingUid, userId)) {
                            PackageInfo pi2 = generatePackageInfo((PackageSetting) p.mExtras, flags2, userId);
                            if (pi2 != null) {
                                list.add(pi2);
                            }
                        }
                    }
                }
                if (listApex) {
                    if (listFactory) {
                        list.addAll(this.mApexManager.getFactoryPackages());
                    } else {
                        list.addAll(this.mApexManager.getActivePackages());
                    }
                    if (listUninstalled) {
                        list.addAll(this.mApexManager.getInactivePackages());
                    }
                }
                parceledListSlice = new ParceledListSlice<>(list);
            }
            return parceledListSlice;
        }
        return ParceledListSlice.emptyList();
    }

    private void addPackageHoldingPermissions(ArrayList<PackageInfo> list, PackageSetting ps, String[] permissions, boolean[] tmp, int flags, int userId) {
        PackageInfo pi;
        int numMatch = 0;
        for (int i = 0; i < permissions.length; i++) {
            String permission = permissions[i];
            if (checkPermission(permission, ps.name, userId) == 0) {
                tmp[i] = true;
                numMatch++;
            } else {
                tmp[i] = false;
            }
        }
        if (numMatch != 0 && (pi = generatePackageInfo(ps, flags, userId)) != null) {
            if ((flags & 4096) == 0) {
                if (numMatch == permissions.length) {
                    pi.requestedPermissions = permissions;
                } else {
                    pi.requestedPermissions = new String[numMatch];
                    int numMatch2 = 0;
                    for (int i2 = 0; i2 < permissions.length; i2++) {
                        if (tmp[i2]) {
                            pi.requestedPermissions[numMatch2] = permissions[i2];
                            numMatch2++;
                        }
                    }
                }
            }
            list.add(pi);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r4v0 */
    /* JADX WARN: Type inference failed for: r4v1 */
    /* JADX WARN: Type inference failed for: r4v2 */
    public ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags, int userId) {
        ArrayMap<String, PackageParser.Package> arrayMap;
        ArrayMap<String, PackageParser.Package> arrayMap2;
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForPackage(flags, userId, permissions);
            ?? r4 = 1;
            this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "get packages holding permissions");
            boolean listUninstalled = (flags2 & 4202496) != 0;
            ArrayMap<String, PackageParser.Package> arrayMap3 = this.mPackages;
            synchronized (arrayMap3) {
                try {
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    ArrayList<PackageInfo> list = new ArrayList<>();
                    boolean[] tmpBools = new boolean[permissions.length];
                    if (listUninstalled) {
                        for (PackageSetting ps : this.mSettings.mPackages.values()) {
                            addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags2, userId);
                        }
                        arrayMap = arrayMap3;
                    } else {
                        for (PackageParser.Package pkg : this.mPackages.values()) {
                            PackageSetting ps2 = (PackageSetting) pkg.mExtras;
                            if (ps2 == null) {
                                arrayMap2 = arrayMap3;
                            } else {
                                arrayMap2 = arrayMap3;
                                addPackageHoldingPermissions(list, ps2, permissions, tmpBools, flags2, userId);
                            }
                            arrayMap3 = arrayMap2;
                        }
                        arrayMap = arrayMap3;
                    }
                    ParceledListSlice<PackageInfo> parceledListSlice = new ParceledListSlice<>(list);
                    return parceledListSlice;
                } catch (Throwable th2) {
                    th = th2;
                    r4 = arrayMap3;
                    throw th;
                }
            }
        }
        return ParceledListSlice.emptyList();
    }

    public ParceledListSlice<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(getInstalledApplicationsListInternal(flags, userId, callingUid));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<ApplicationInfo> getInstalledApplicationsListInternal(int flags, int userId, int callingUid) {
        ArrayList<ApplicationInfo> list;
        ApplicationInfo ai;
        if (getInstantAppPackageName(callingUid) != null) {
            return Collections.emptyList();
        }
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForApplication(flags, userId, null);
            boolean listUninstalled = (4202496 & flags2) != 0;
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get installed application info");
            synchronized (this.mPackages) {
                if (listUninstalled) {
                    list = new ArrayList<>(this.mSettings.mPackages.size());
                    for (PackageSetting ps : this.mSettings.mPackages.values()) {
                        int effectiveFlags = flags2;
                        if (ps.isSystem()) {
                            effectiveFlags |= 4194304;
                        }
                        if (ps.pkg != null) {
                            if (!filterSharedLibPackageLPr(ps, callingUid, userId, flags2) && !filterAppAccessLPr(ps, callingUid, userId)) {
                                ai = PackageParser.generateApplicationInfo(ps.pkg, effectiveFlags, ps.readUserState(userId), userId);
                                if (ai != null) {
                                    ai.packageName = resolveExternalPackageNameLPr(ps.pkg);
                                }
                            }
                        } else {
                            ai = generateApplicationInfoFromSettingsLPw(ps.name, callingUid, effectiveFlags, userId);
                        }
                        if (ai != null) {
                            list.add(ai);
                        }
                    }
                } else {
                    list = new ArrayList<>(this.mPackages.size());
                    for (PackageParser.Package p : this.mPackages.values()) {
                        if (p.mExtras != null) {
                            PackageSetting ps2 = (PackageSetting) p.mExtras;
                            if (!filterSharedLibPackageLPr(ps2, Binder.getCallingUid(), userId, flags2) && !filterAppAccessLPr(ps2, callingUid, userId)) {
                                ApplicationInfo ai2 = PackageParser.generateApplicationInfo(p, flags2, ps2.readUserState(userId), userId);
                                if (ai2 != null) {
                                    ai2.packageName = resolveExternalPackageNameLPr(p);
                                    list.add(ai2);
                                }
                            }
                        }
                    }
                }
            }
            return list;
        }
        return Collections.emptyList();
    }

    public ParceledListSlice<InstantAppInfo> getInstantApps(int userId) {
        if (!canViewInstantApps(Binder.getCallingUid(), userId)) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_INSTANT_APPS", "getEphemeralApplications");
        }
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "getEphemeralApplications");
        synchronized (this.mPackages) {
            List<InstantAppInfo> instantApps = this.mInstantAppRegistry.getInstantAppsLPr(userId);
            if (instantApps != null) {
                return new ParceledListSlice<>(instantApps);
            }
            return null;
        }
    }

    public boolean isInstantApp(String packageName, int userId) {
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "isInstantApp");
        synchronized (this.mPackages) {
            int callingUid = Binder.getCallingUid();
            if (Process.isIsolated(callingUid)) {
                callingUid = this.mIsolatedOwners.get(callingUid);
            }
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            this.mPackages.get(packageName);
            boolean returnAllowed = ps != null && (isCallerSameApp(packageName, callingUid) || canViewInstantApps(callingUid, userId) || this.mInstantAppRegistry.isInstantAccessGranted(userId, UserHandle.getAppId(callingUid), ps.appId));
            if (!returnAllowed) {
                return false;
            }
            return ps.getInstantApp(userId);
        }
    }

    public byte[] getInstantAppCookie(String packageName, int userId) {
        byte[] instantAppCookieLPw;
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "getInstantAppCookie");
        if (!isCallerSameApp(packageName, Binder.getCallingUid())) {
            return null;
        }
        synchronized (this.mPackages) {
            instantAppCookieLPw = this.mInstantAppRegistry.getInstantAppCookieLPw(packageName, userId);
        }
        return instantAppCookieLPw;
    }

    public boolean setInstantAppCookie(String packageName, byte[] cookie, int userId) {
        boolean instantAppCookieLPw;
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, true, "setInstantAppCookie");
        if (!isCallerSameApp(packageName, Binder.getCallingUid())) {
            return false;
        }
        synchronized (this.mPackages) {
            instantAppCookieLPw = this.mInstantAppRegistry.setInstantAppCookieLPw(packageName, cookie, userId);
        }
        return instantAppCookieLPw;
    }

    public Bitmap getInstantAppIcon(String packageName, int userId) {
        Bitmap instantAppIconLPw;
        if (!canViewInstantApps(Binder.getCallingUid(), userId)) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_INSTANT_APPS", "getInstantAppIcon");
        }
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "getInstantAppIcon");
        synchronized (this.mPackages) {
            instantAppIconLPw = this.mInstantAppRegistry.getInstantAppIconLPw(packageName, userId);
        }
        return instantAppIconLPw;
    }

    private boolean isCallerSameApp(String packageName, int uid) {
        PackageParser.Package pkg = this.mPackages.get(packageName);
        return pkg != null && UserHandle.getAppId(uid) == pkg.applicationInfo.uid;
    }

    public ParceledListSlice<ApplicationInfo> getPersistentApplications(int flags) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return ParceledListSlice.emptyList();
        }
        return new ParceledListSlice<>(getPersistentApplicationsInternal(flags));
    }

    private List<ApplicationInfo> getPersistentApplicationsInternal(int flags) {
        PackageSetting ps;
        ApplicationInfo ai;
        ArrayList<ApplicationInfo> finalList = new ArrayList<>();
        synchronized (this.mPackages) {
            int userId = UserHandle.getCallingUserId();
            for (PackageParser.Package p : this.mPackages.values()) {
                if (p.applicationInfo != null) {
                    boolean matchesAware = true;
                    boolean matchesUnaware = ((262144 & flags) == 0 || p.applicationInfo.isDirectBootAware()) ? false : true;
                    if ((524288 & flags) == 0 || !p.applicationInfo.isDirectBootAware()) {
                        matchesAware = false;
                    }
                    if ((p.applicationInfo.flags & 8) != 0 && ((!this.mSafeMode || isSystemApp(p)) && ((matchesUnaware || matchesAware) && (ps = this.mSettings.mPackages.get(p.packageName)) != null && (ai = PackageParser.generateApplicationInfo(p, flags, ps.readUserState(userId), userId)) != null))) {
                        finalList.add(ai);
                    }
                }
            }
        }
        return finalList;
    }

    public ProviderInfo resolveContentProvider(String name, int flags, int userId) {
        return resolveContentProviderInternal(name, flags, userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ProviderInfo resolveContentProviderInternal(String name, int flags, int userId) {
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForComponent(flags, userId, name);
            int callingUid = Binder.getCallingUid();
            ProviderInfo providerInfo = this.mComponentResolver.queryProvider(name, flags2, userId);
            if (providerInfo != null && this.mSettings.isEnabledAndMatchLPr(providerInfo, flags2, userId)) {
                synchronized (this.mPackages) {
                    PackageSetting ps = this.mSettings.mPackages.get(providerInfo.packageName);
                    ComponentName component = new ComponentName(providerInfo.packageName, providerInfo.name);
                    if (filterAppAccessLPr(ps, callingUid, component, 4, userId)) {
                        return null;
                    }
                    return providerInfo;
                }
            }
            return null;
        }
        return null;
    }

    @Deprecated
    public void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        this.mComponentResolver.querySyncProviders(outNames, outInfo, this.mSafeMode, UserHandle.getCallingUserId());
    }

    public ParceledListSlice<ProviderInfo> queryContentProviders(String processName, int uid, int flags, String metaDataKey) {
        ArrayList<ProviderInfo> finalList;
        PackageManagerService packageManagerService = this;
        int callingUid = Binder.getCallingUid();
        int userId = processName != null ? UserHandle.getUserId(uid) : UserHandle.getCallingUserId();
        if (sUserManager.exists(userId)) {
            int flags2 = packageManagerService.updateFlagsForComponent(flags, userId, processName);
            List<ProviderInfo> matchList = packageManagerService.mComponentResolver.queryProviders(processName, metaDataKey, uid, flags2, userId);
            int listSize = matchList == null ? 0 : matchList.size();
            synchronized (packageManagerService.mPackages) {
                finalList = null;
                int i = 0;
                while (i < listSize) {
                    ProviderInfo providerInfo = matchList.get(i);
                    if (packageManagerService.mSettings.isEnabledAndMatchLPr(providerInfo, flags2, userId)) {
                        PackageSetting ps = packageManagerService.mSettings.mPackages.get(providerInfo.packageName);
                        ComponentName component = new ComponentName(providerInfo.packageName, providerInfo.name);
                        if (!filterAppAccessLPr(ps, callingUid, component, 4, userId)) {
                            if (finalList == null) {
                                finalList = new ArrayList<>(listSize - i);
                            }
                            finalList.add(providerInfo);
                        }
                    }
                    i++;
                    packageManagerService = this;
                }
            }
            if (finalList != null) {
                finalList.sort(sProviderInitOrderSorter);
                return new ParceledListSlice<>(finalList);
            }
            return ParceledListSlice.emptyList();
        }
        return ParceledListSlice.emptyList();
    }

    public InstrumentationInfo getInstrumentationInfo(ComponentName component, int flags) {
        synchronized (this.mPackages) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = UserHandle.getUserId(callingUid);
            PackageSetting ps = this.mSettings.mPackages.get(component.getPackageName());
            if (ps == null) {
                return null;
            }
            if (filterAppAccessLPr(ps, callingUid, component, 0, callingUserId)) {
                return null;
            }
            PackageParser.Instrumentation i = this.mInstrumentation.get(component);
            return PackageParser.generateInstrumentationInfo(i, flags);
        }
    }

    public ParceledListSlice<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) {
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        PackageSetting ps = this.mSettings.mPackages.get(targetPackage);
        if (filterAppAccessLPr(ps, callingUid, callingUserId)) {
            return ParceledListSlice.emptyList();
        }
        return new ParceledListSlice<>(queryInstrumentationInternal(targetPackage, flags));
    }

    private List<InstrumentationInfo> queryInstrumentationInternal(String targetPackage, int flags) {
        InstrumentationInfo ii;
        ArrayList<InstrumentationInfo> finalList = new ArrayList<>();
        synchronized (this.mPackages) {
            for (PackageParser.Instrumentation p : this.mInstrumentation.values()) {
                if ((targetPackage == null || targetPackage.equals(p.info.targetPackage)) && (ii = PackageParser.generateInstrumentationInfo(p, flags)) != null) {
                    finalList.add(ii);
                }
            }
        }
        return finalList;
    }

    private void scanDirTracedLI(File scanDir, int parseFlags, int scanFlags, long currentTime) {
        Trace.traceBegin(262144L, "scanDir [" + scanDir.getAbsolutePath() + "]");
        try {
            scanDirLI(scanDir, parseFlags, scanFlags, currentTime);
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:52:0x0131  */
    /* JADX WARN: Removed duplicated region for block: B:55:0x0151 A[ADDED_TO_REGION] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void scanDirLI(java.io.File r20, int r21, int r22, long r23) {
        /*
            Method dump skipped, instructions count: 394
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.scanDirLI(java.io.File, int, int, long):void");
    }

    public static void reportSettingsProblem(int priority, String msg) {
        PackageManagerServiceUtils.logCriticalInfo(priority, msg);
    }

    private void collectCertificatesLI(PackageSetting ps, PackageParser.Package pkg, boolean forceCollect, boolean skipVerify) throws PackageManagerException {
        long lastModifiedTime = this.mIsPreNMR1Upgrade ? new File(pkg.codePath).lastModified() : PackageManagerServiceUtils.getLastModifiedTime(pkg);
        Settings.VersionInfo settingsVersionForPackage = getSettingsVersionForPackage(pkg);
        if (ps != null && !forceCollect && ps.codePathString.equals(pkg.codePath) && ps.timeStamp == lastModifiedTime && !isCompatSignatureUpdateNeeded(settingsVersionForPackage) && !isRecoverSignatureUpdateNeeded(settingsVersionForPackage)) {
            if (ps.signatures.mSigningDetails.signatures != null && ps.signatures.mSigningDetails.signatures.length != 0 && ps.signatures.mSigningDetails.signatureSchemeVersion != 0) {
                pkg.mSigningDetails = new PackageParser.SigningDetails(ps.signatures.mSigningDetails);
                return;
            }
            Slog.w(TAG, "PackageSetting for " + ps.name + " is missing signatures.  Collecting certs again to recover them.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(pkg.codePath);
            sb.append(" changed; collecting certs");
            sb.append(forceCollect ? " (forced)" : "");
            Slog.i(TAG, sb.toString());
        }
        try {
            try {
                Trace.traceBegin(262144L, "collectCertificates");
                PackageParser.collectCertificates(pkg, skipVerify);
            } catch (PackageParser.PackageParserException e) {
                throw PackageManagerException.from(e);
            }
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    private void maybeClearProfilesForUpgradesLI(PackageSetting originalPkgSetting, PackageParser.Package currentPkg) {
        if (originalPkgSetting == null || !isDeviceUpgrading() || originalPkgSetting.versionCode == currentPkg.mVersionCode) {
            return;
        }
        clearAppProfilesLIF(currentPkg, -1);
    }

    @GuardedBy({"mInstallLock", "mPackages"})
    private PackageParser.Package scanPackageTracedLI(File scanFile, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        Trace.traceBegin(262144L, "scanPackage [" + scanFile.toString() + "]");
        try {
            return scanPackageLI(scanFile, parseFlags, scanFlags, currentTime, user);
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    @GuardedBy({"mInstallLock", "mPackages"})
    private PackageParser.Package scanPackageLI(File scanFile, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        PackageParser pp = new PackageParser();
        pp.setSeparateProcesses(this.mSeparateProcesses);
        pp.setOnlyCoreApps(this.mOnlyCore);
        pp.setDisplayMetrics(this.mMetrics);
        pp.setCallback(this.mPackageParserCallback);
        Trace.traceBegin(262144L, "parsePackage");
        try {
            try {
                PackageParser.Package pkg = pp.parsePackage(scanFile, parseFlags);
                Trace.traceEnd(262144L);
                if (pkg.applicationInfo.isStaticSharedLibrary()) {
                    renameStaticSharedLibraryPackage(pkg);
                }
                return scanPackageChildLI(pkg, parseFlags, scanFlags, currentTime, user);
            } catch (PackageParser.PackageParserException e) {
                throw PackageManagerException.from(e);
            }
        } catch (Throwable th) {
            Trace.traceEnd(262144L);
            throw th;
        }
    }

    @GuardedBy({"mInstallLock", "mPackages"})
    private PackageParser.Package scanPackageChildLI(PackageParser.Package pkg, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        int scanFlags2;
        if ((scanFlags & 1024) == 0) {
            if (pkg.childPackages != null && pkg.childPackages.size() > 0) {
                scanFlags2 = scanFlags | 1024;
            } else {
                scanFlags2 = scanFlags;
            }
        } else {
            scanFlags2 = scanFlags & (-1025);
        }
        PackageParser.Package scannedPkg = addForInitLI(pkg, parseFlags, scanFlags2, currentTime, user);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPackage = (PackageParser.Package) pkg.childPackages.get(i);
            addForInitLI(childPackage, parseFlags, scanFlags2, currentTime, user);
        }
        int i2 = scanFlags2 & 1024;
        if (i2 != 0) {
            return scanPackageChildLI(pkg, parseFlags, scanFlags2, currentTime, user);
        }
        return scannedPkg;
    }

    private boolean canSkipForcedPackageVerification(PackageParser.Package pkg) {
        if (canSkipForcedApkVerification(pkg.baseCodePath)) {
            if (!ArrayUtils.isEmpty(pkg.splitCodePaths)) {
                for (int i = 0; i < pkg.splitCodePaths.length; i++) {
                    if (!canSkipForcedApkVerification(pkg.splitCodePaths[i])) {
                        return false;
                    }
                }
                return true;
            }
            return true;
        }
        return false;
    }

    private boolean canSkipForcedApkVerification(String apkPath) {
        if (!PackageManagerServiceUtils.isLegacyApkVerityEnabled()) {
            return VerityUtils.hasFsverity(apkPath);
        }
        try {
            byte[] rootHashObserved = VerityUtils.generateApkVerityRootHash(apkPath);
            if (rootHashObserved == null) {
                return false;
            }
            synchronized (this.mInstallLock) {
                this.mInstaller.assertFsverityRootHashMatches(apkPath, rootHashObserved);
            }
            return true;
        } catch (Installer.InstallerException | IOException | DigestException | NoSuchAlgorithmException e) {
            Slog.w(TAG, "Error in fsverity check. Fallback to full apk verification.", e);
            return false;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:167:0x03c9  */
    /* JADX WARN: Removed duplicated region for block: B:183:0x041d  */
    @com.android.internal.annotations.GuardedBy({"mInstallLock", "mPackages"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private android.content.pm.PackageParser.Package addForInitLI(android.content.pm.PackageParser.Package r43, int r44, int r45, long r46, android.os.UserHandle r48) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 1080
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.addForInitLI(android.content.pm.PackageParser$Package, int, int, long, android.os.UserHandle):android.content.pm.PackageParser$Package");
    }

    private static void renameStaticSharedLibraryPackage(PackageParser.Package pkg) {
        pkg.setPackageName(pkg.packageName + STATIC_SHARED_LIB_DELIMITER + pkg.staticSharedLibVersion);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String fixProcessName(String defProcessName, String processName) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    private static void enforceSystemOrRoot(String message) {
        int uid = Binder.getCallingUid();
        if (uid != 1000 && uid != 0) {
            throw new SecurityException(message);
        }
    }

    private static void enforceSystemOrRootOrShell(String message) {
        int uid = Binder.getCallingUid();
        if (uid != 1000 && uid != 0 && uid != SHELL_UID) {
            throw new SecurityException(message);
        }
    }

    public void performFstrimIfNeeded() {
        boolean dexOptDialogShown;
        enforceSystemOrRoot("Only the system can request fstrim");
        try {
            IStorageManager sm = PackageHelper.getStorageManager();
            if (sm != null) {
                boolean doTrim = false;
                long interval = Settings.Global.getLong(this.mContext.getContentResolver(), "fstrim_mandatory_interval", DEFAULT_MANDATORY_FSTRIM_INTERVAL);
                if (interval > 0) {
                    long timeSinceLast = System.currentTimeMillis() - sm.lastMaintenance();
                    if (timeSinceLast > interval) {
                        doTrim = true;
                        Slog.w(TAG, "No disk maintenance in " + timeSinceLast + "; running immediately");
                    }
                }
                if (doTrim) {
                    synchronized (this.mPackages) {
                        dexOptDialogShown = this.mDexOptDialogShown;
                    }
                    if (!isFirstBoot() && dexOptDialogShown) {
                        try {
                            ActivityManager.getService().showBootMessage(this.mContext.getResources().getString(17039493), true);
                        } catch (RemoteException e) {
                        }
                    }
                    sm.runMaintenance();
                }
                return;
            }
            Slog.e(TAG, "storageManager service unavailable!");
        } catch (RemoteException e2) {
        }
    }

    private void transferPackageDada() {
        int process = SystemProperties.getInt("persist.xiaopeng.transprocess", 0);
        Slog.i(TAG, "transferPackageDada start for process = " + process + " option " + FeatureOption.FO_PROJECT_UI_TYPE);
        if (process == 1) {
            return;
        }
        SystemProperties.set("ctl.start", "transferdata");
        while (process != 1) {
            process = SystemProperties.getInt("persist.xiaopeng.transprocess", 0);
            try {
                Thread.sleep(200L);
            } catch (Exception e) {
                Slog.e(TAG, "We restart system server");
                throw new RuntimeException("PackageManagerService transferPackeDada failed");
            }
        }
        Slog.i(TAG, "transferPackageDada done.");
    }

    public void updatePackagesIfNeeded() {
        List<PackageParser.Package> pkgs;
        enforceSystemOrRoot("Only the system can request package update");
        boolean z = is3DUI;
        boolean causeUpgrade = isDeviceUpgrading();
        boolean causeFirstBoot = isFirstBoot() || this.mIsPreNUpgrade;
        boolean causePrunedCache = VMRuntime.didPruneDalvikCache();
        if (!causeUpgrade && !causeFirstBoot && !causePrunedCache) {
            return;
        }
        synchronized (this.mPackages) {
            pkgs = PackageManagerServiceUtils.getPackagesForDexopt(this.mPackages.values(), this);
        }
        long startTime = System.nanoTime();
        int[] stats = performDexOptUpgrade(pkgs, this.mIsPreNUpgrade, causeFirstBoot ? 0 : 1, false);
        int elapsedTimeSeconds = (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);
        MetricsLogger.histogram(this.mContext, "opt_dialog_num_dexopted", stats[0]);
        MetricsLogger.histogram(this.mContext, "opt_dialog_num_skipped", stats[1]);
        MetricsLogger.histogram(this.mContext, "opt_dialog_num_failed", stats[2]);
        MetricsLogger.histogram(this.mContext, "opt_dialog_num_total", getOptimizablePackages().size());
        MetricsLogger.histogram(this.mContext, "opt_dialog_time_s", elapsedTimeSeconds);
    }

    private static String getPrebuildProfilePath(PackageParser.Package pkg) {
        return pkg.baseCodePath + ".prof";
    }

    /* JADX WARN: Removed duplicated region for block: B:94:0x0110 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:96:0x0109 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int[] performDexOptUpgrade(java.util.List<android.content.pm.PackageParser.Package> r20, boolean r21, int r22, boolean r23) {
        /*
            Method dump skipped, instructions count: 427
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.performDexOptUpgrade(java.util.List, boolean, int, boolean):int[]");
    }

    public void notifyPackageUse(String packageName, int reason) {
        synchronized (this.mPackages) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = UserHandle.getUserId(callingUid);
            if (getInstantAppPackageName(callingUid) != null) {
                if (!isCallerSameApp(packageName, callingUid)) {
                    return;
                }
            } else if (isInstantApp(packageName, callingUserId)) {
                return;
            }
            notifyPackageUseLocked(packageName, reason);
        }
    }

    @GuardedBy({"mPackages"})
    public PackageManagerInternal.CheckPermissionDelegate getCheckPermissionDelegateLocked() {
        return this.mCheckPermissionDelegate;
    }

    @GuardedBy({"mPackages"})
    public void setCheckPermissionDelegateLocked(PackageManagerInternal.CheckPermissionDelegate delegate) {
        this.mCheckPermissionDelegate = delegate;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public void notifyPackageUseLocked(String packageName, int reason) {
        PackageParser.Package p = this.mPackages.get(packageName);
        if (p == null) {
            return;
        }
        p.mLastPackageUsageTimeInMills[reason] = System.currentTimeMillis();
    }

    public void notifyDexLoad(String loadingPackageName, List<String> classLoaderNames, List<String> classPaths, String loaderIsa) {
        int userId = UserHandle.getCallingUserId();
        ApplicationInfo ai = getApplicationInfo(loadingPackageName, 0, userId);
        if (ai == null) {
            Slog.w(TAG, "Loading a package that does not exist for the calling user. package=" + loadingPackageName + ", user=" + userId);
            return;
        }
        this.mDexManager.notifyDexLoad(ai, classLoaderNames, classPaths, loaderIsa, userId);
    }

    public void registerDexModule(String packageName, final String dexModulePath, boolean isSharedModule, final IDexModuleRegisterCallback callback) {
        final DexManager.RegisterDexModuleResult result;
        int userId = UserHandle.getCallingUserId();
        ApplicationInfo ai = getApplicationInfo(packageName, 0, userId);
        if (ai == null) {
            Slog.w(TAG, "Registering a dex module for a package that does not exist for the calling user. package=" + packageName + ", user=" + userId);
            result = new DexManager.RegisterDexModuleResult(false, "Package not installed");
        } else {
            result = this.mDexManager.registerDexModule(ai, dexModulePath, isSharedModule, userId);
        }
        if (callback != null) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$zyuddDJhFJp3TkyomSnR_V1OTpA
                @Override // java.lang.Runnable
                public final void run() {
                    PackageManagerService.lambda$registerDexModule$4(callback, dexModulePath, result);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$registerDexModule$4(IDexModuleRegisterCallback callback, String dexModulePath, DexManager.RegisterDexModuleResult result) {
        try {
            callback.onDexModuleRegistered(dexModulePath, result.success, result.message);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to callback after module registration " + dexModulePath, e);
        }
    }

    public boolean performDexOptMode(String packageName, boolean checkProfiles, String targetCompilerFilter, boolean force, boolean bootComplete, String splitName) {
        int flags = (bootComplete ? 4 : 0) | (force ? 2 : 0) | (checkProfiles ? 1 : 0);
        return performDexOpt(new DexoptOptions(packageName, -1, targetCompilerFilter, splitName, flags));
    }

    public boolean performDexOptSecondary(String packageName, String compilerFilter, boolean force) {
        int flags = (force ? 2 : 0) | 13;
        return performDexOpt(new DexoptOptions(packageName, compilerFilter, flags));
    }

    public boolean compileLayouts(String packageName) {
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg == null) {
                return false;
            }
            return this.mViewCompiler.compileLayouts(pkg);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean performDexOpt(DexoptOptions options) {
        if (getInstantAppPackageName(Binder.getCallingUid()) == null && !isInstantApp(options.getPackageName(), UserHandle.getCallingUserId())) {
            if (options.isDexoptOnlySecondaryDex()) {
                return this.mDexManager.dexoptSecondaryDex(options);
            }
            int dexoptStatus = performDexOptWithStatus(options);
            return dexoptStatus != -1;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int performDexOptWithStatus(DexoptOptions options) {
        return performDexOptTraced(options);
    }

    private int performDexOptTraced(DexoptOptions options) {
        Trace.traceBegin(262144L, "dexopt");
        try {
            return performDexOptInternal(options);
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    private int performDexOptInternal(DexoptOptions options) {
        int performDexOptInternalWithDependenciesLI;
        synchronized (this.mPackages) {
            PackageParser.Package p = this.mPackages.get(options.getPackageName());
            if (p == null) {
                return -1;
            }
            this.mPackageUsage.maybeWriteAsync(this.mPackages);
            this.mCompilerStats.maybeWriteAsync();
            long callingId = Binder.clearCallingIdentity();
            try {
                synchronized (this.mInstallLock) {
                    performDexOptInternalWithDependenciesLI = performDexOptInternalWithDependenciesLI(p, options);
                }
                return performDexOptInternalWithDependenciesLI;
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }
    }

    public ArraySet<String> getOptimizablePackages() {
        ArraySet<String> pkgs = new ArraySet<>();
        synchronized (this.mPackages) {
            for (PackageParser.Package p : this.mPackages.values()) {
                if (PackageDexOptimizer.canOptimizePackage(p)) {
                    pkgs.add(p.packageName);
                }
            }
        }
        return pkgs;
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:21:0x007c -> B:22:0x007d). Please submit an issue!!! */
    private int performDexOptInternalWithDependenciesLI(PackageParser.Package p, DexoptOptions options) {
        PackageDexOptimizer.ForcedUpdatePackageDexOptimizer pdo;
        PackageParser.Package depPackage;
        if (options.isForce()) {
            pdo = new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(this.mPackageDexOptimizer);
        } else {
            pdo = this.mPackageDexOptimizer;
        }
        Collection<SharedLibraryInfo> deps = findSharedLibraries(p);
        String[] instructionSets = InstructionSets.getAppDexInstructionSets(p.applicationInfo);
        if (!deps.isEmpty()) {
            DexoptOptions libraryOptions = new DexoptOptions(options.getPackageName(), options.getCompilationReason(), options.getCompilerFilter(), options.getSplitName(), options.getFlags() | 64);
            for (SharedLibraryInfo info : deps) {
                synchronized (this.mPackages) {
                    try {
                        depPackage = this.mPackages.get(info.getPackageName());
                    } catch (Throwable th) {
                        th = th;
                    }
                    try {
                    } catch (Throwable th2) {
                        th = th2;
                        throw th;
                    }
                }
                if (depPackage != null) {
                    pdo.performDexOpt(depPackage, instructionSets, getOrCreateCompilerPackageStats(depPackage), this.mDexManager.getPackageUseInfoOrDefault(depPackage.packageName), libraryOptions);
                }
            }
        }
        return pdo.performDexOpt(p, instructionSets, getOrCreateCompilerPackageStats(p), this.mDexManager.getPackageUseInfoOrDefault(p.packageName), options);
    }

    public void reconcileSecondaryDexFiles(String packageName) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null || isInstantApp(packageName, UserHandle.getCallingUserId())) {
            return;
        }
        this.mDexManager.reconcileSecondaryDexFiles(packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DexManager getDexManager() {
        return this.mDexManager;
    }

    public boolean runBackgroundDexoptJob(List<String> packageNames) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        }
        enforceSystemOrRootOrShell("runBackgroundDexoptJob");
        long identity = Binder.clearCallingIdentity();
        try {
            return BackgroundDexOptService.runIdleOptimizationsNow(this, this.mContext, packageNames);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static List<SharedLibraryInfo> findSharedLibraries(PackageParser.Package p) {
        if (p.usesLibraryInfos != null) {
            ArrayList<SharedLibraryInfo> retValue = new ArrayList<>();
            Set<String> collectedNames = new HashSet<>();
            Iterator it = p.usesLibraryInfos.iterator();
            while (it.hasNext()) {
                SharedLibraryInfo info = (SharedLibraryInfo) it.next();
                findSharedLibrariesRecursive(info, retValue, collectedNames);
            }
            return retValue;
        }
        return Collections.emptyList();
    }

    private static void findSharedLibrariesRecursive(SharedLibraryInfo info, ArrayList<SharedLibraryInfo> collected, Set<String> collectedNames) {
        if (!collectedNames.contains(info.getName())) {
            collectedNames.add(info.getName());
            collected.add(info);
            if (info.getDependencies() != null) {
                for (SharedLibraryInfo dep : info.getDependencies()) {
                    findSharedLibrariesRecursive(dep, collected, collectedNames);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<PackageParser.Package> findSharedNonSystemLibraries(PackageParser.Package pkg) {
        List<SharedLibraryInfo> deps = findSharedLibraries(pkg);
        if (!deps.isEmpty()) {
            ArrayList<PackageParser.Package> retValue = new ArrayList<>();
            synchronized (this.mPackages) {
                for (SharedLibraryInfo info : deps) {
                    PackageParser.Package depPackage = this.mPackages.get(info.getPackageName());
                    if (depPackage != null) {
                        retValue.add(depPackage);
                    }
                }
            }
            return retValue;
        }
        return Collections.emptyList();
    }

    private SharedLibraryInfo getSharedLibraryInfoLPr(String name, long version) {
        return getSharedLibraryInfo(name, version, this.mSharedLibraries, null);
    }

    private static SharedLibraryInfo getSharedLibraryInfo(String name, long version, Map<String, LongSparseArray<SharedLibraryInfo>> existingLibraries, Map<String, LongSparseArray<SharedLibraryInfo>> newLibraries) {
        if (newLibraries != null) {
            LongSparseArray<SharedLibraryInfo> versionedLib = newLibraries.get(name);
            SharedLibraryInfo info = null;
            if (versionedLib != null) {
                SharedLibraryInfo info2 = versionedLib.get(version);
                info = info2;
            }
            if (info != null) {
                return info;
            }
        }
        LongSparseArray<SharedLibraryInfo> versionedLib2 = existingLibraries.get(name);
        if (versionedLib2 == null) {
            return null;
        }
        return versionedLib2.get(version);
    }

    private SharedLibraryInfo getLatestSharedLibraVersionLPr(PackageParser.Package pkg) {
        LongSparseArray<SharedLibraryInfo> versionedLib = this.mSharedLibraries.get(pkg.staticSharedLibName);
        if (versionedLib == null) {
            return null;
        }
        long previousLibVersion = -1;
        int versionCount = versionedLib.size();
        for (int i = 0; i < versionCount; i++) {
            long libVersion = versionedLib.keyAt(i);
            if (libVersion < pkg.staticSharedLibVersion) {
                previousLibVersion = Math.max(previousLibVersion, libVersion);
            }
        }
        if (previousLibVersion < 0) {
            return null;
        }
        return versionedLib.get(previousLibVersion);
    }

    private PackageSetting getSharedLibLatestVersionSetting(ScanResult scanResult) {
        PackageSetting sharedLibPackage = null;
        synchronized (this.mPackages) {
            SharedLibraryInfo latestSharedLibraVersionLPr = getLatestSharedLibraVersionLPr(scanResult.pkgSetting.pkg);
            if (latestSharedLibraVersionLPr != null) {
                sharedLibPackage = this.mSettings.getPackageLPr(latestSharedLibraVersionLPr.getPackageName());
            }
        }
        return sharedLibPackage;
    }

    public void shutdown() {
        this.mPackageUsage.writeNow(this.mPackages);
        this.mCompilerStats.writeNow();
        this.mDexManager.writePackageDexUsageNow();
        PackageWatchdog.getInstance(this.mContext).writeNow();
        synchronized (this.mPackages) {
            if (this.mHandler.hasMessages(14)) {
                this.mHandler.removeMessages(14);
                Iterator<Integer> it = this.mDirtyUsers.iterator();
                while (it.hasNext()) {
                    int userId = it.next().intValue();
                    this.mSettings.writePackageRestrictionsLPr(userId);
                }
                this.mDirtyUsers.clear();
            }
        }
    }

    public void dumpProfiles(String packageName) {
        PackageParser.Package pkg;
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
        }
        int callingUid = Binder.getCallingUid();
        if (callingUid != SHELL_UID && callingUid != 0 && callingUid != pkg.applicationInfo.uid) {
            throw new SecurityException("dumpProfiles");
        }
        synchronized (this.mInstallLock) {
            Trace.traceBegin(262144L, "dump profiles");
            this.mArtManagerService.dumpProfiles(pkg);
            Trace.traceEnd(262144L);
        }
    }

    public void forceDexOpt(String packageName) {
        PackageParser.Package pkg;
        enforceSystemOrRoot("forceDexOpt");
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
        }
        synchronized (this.mInstallLock) {
            Trace.traceBegin(262144L, "dexopt");
            int res = performDexOptInternalWithDependenciesLI(pkg, new DexoptOptions(packageName, PackageManagerServiceCompilerMapping.getDefaultCompilerFilter(), 6));
            Trace.traceEnd(262144L);
            if (res != 1) {
                throw new IllegalStateException("Failed to dexopt: " + res);
            }
        }
    }

    @GuardedBy({"mPackages"})
    private boolean verifyPackageUpdateLPr(PackageSetting oldPkg, PackageParser.Package newPkg) {
        if ((oldPkg.pkgFlags & 1) == 0) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name + " to " + newPkg.packageName + ": old package not in system partition");
            return false;
        } else if (this.mPackages.get(oldPkg.name) != null) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name + " to " + newPkg.packageName + ": old package still exists");
            return false;
        } else {
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mInstallLock"})
    public void removeCodePathLI(File codePath) {
        if (codePath.isDirectory()) {
            try {
                this.mInstaller.rmPackageDir(codePath.getAbsolutePath());
                return;
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Failed to remove code path", e);
                return;
            }
        }
        codePath.delete();
    }

    private int[] resolveUserIds(int userId) {
        return userId == -1 ? sUserManager.getUserIds() : new int[]{userId};
    }

    private void clearAppDataLIF(PackageParser.Package pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        clearAppDataLeafLIF(pkg, userId, flags);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            clearAppDataLeafLIF((PackageParser.Package) pkg.childPackages.get(i), userId, flags);
        }
        clearAppProfilesLIF(pkg, -1);
    }

    private void clearAppDataLeafLIF(PackageParser.Package pkg, int userId, int flags) {
        PackageSetting ps;
        int[] resolveUserIds;
        synchronized (this.mPackages) {
            ps = this.mSettings.mPackages.get(pkg.packageName);
        }
        for (int realUserId : resolveUserIds(userId)) {
            long ceDataInode = ps != null ? ps.getCeDataInode(realUserId) : 0L;
            try {
                this.mInstaller.clearAppData(pkg.volumeUuid, pkg.packageName, realUserId, flags, ceDataInode);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
        }
    }

    private void destroyAppDataLIF(PackageParser.Package pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppDataLeafLIF(pkg, userId, flags);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            destroyAppDataLeafLIF((PackageParser.Package) pkg.childPackages.get(i), userId, flags);
        }
    }

    private void destroyAppDataLeafLIF(PackageParser.Package pkg, int userId, int flags) {
        PackageSetting ps;
        int[] resolveUserIds;
        synchronized (this.mPackages) {
            ps = this.mSettings.mPackages.get(pkg.packageName);
        }
        for (int realUserId : resolveUserIds(userId)) {
            long ceDataInode = ps != null ? ps.getCeDataInode(realUserId) : 0L;
            try {
                this.mInstaller.destroyAppData(pkg.volumeUuid, pkg.packageName, realUserId, flags, ceDataInode);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
            this.mDexManager.notifyPackageDataDestroyed(pkg.packageName, userId);
        }
    }

    private void destroyAppProfilesLIF(PackageParser.Package pkg) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppProfilesLeafLIF(pkg);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            destroyAppProfilesLeafLIF((PackageParser.Package) pkg.childPackages.get(i));
        }
    }

    private void destroyAppProfilesLeafLIF(PackageParser.Package pkg) {
        try {
            this.mInstaller.destroyAppProfiles(pkg.packageName);
        } catch (Installer.InstallerException e) {
            Slog.w(TAG, String.valueOf(e));
        }
    }

    private void clearAppProfilesLIF(PackageParser.Package pkg, int userId) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        this.mArtManagerService.clearAppProfiles(pkg);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            this.mArtManagerService.clearAppProfiles((PackageParser.Package) pkg.childPackages.get(i));
        }
    }

    private void setInstallAndUpdateTime(PackageParser.Package pkg, long firstInstallTime, long lastUpdateTime) {
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps != null) {
            ps.firstInstallTime = firstInstallTime;
            ps.lastUpdateTime = lastUpdateTime;
        }
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
            PackageSetting ps2 = (PackageSetting) childPkg.mExtras;
            if (ps2 != null) {
                ps2.firstInstallTime = firstInstallTime;
                ps2.lastUpdateTime = lastUpdateTime;
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void applyDefiningSharedLibraryUpdateLocked(PackageParser.Package pkg, SharedLibraryInfo libInfo, BiConsumer<SharedLibraryInfo, SharedLibraryInfo> action) {
        if (pkg.isLibrary()) {
            if (pkg.staticSharedLibName != null) {
                SharedLibraryInfo definedLibrary = getSharedLibraryInfoLPr(pkg.staticSharedLibName, pkg.staticSharedLibVersion);
                if (definedLibrary != null) {
                    action.accept(definedLibrary, libInfo);
                    return;
                }
                return;
            }
            Iterator it = pkg.libraryNames.iterator();
            while (it.hasNext()) {
                String libraryName = (String) it.next();
                SharedLibraryInfo definedLibrary2 = getSharedLibraryInfoLPr(libraryName, -1L);
                if (definedLibrary2 != null) {
                    action.accept(definedLibrary2, libInfo);
                }
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void addSharedLibraryLPr(PackageParser.Package pkg, Set<String> usesLibraryFiles, SharedLibraryInfo libInfo, PackageParser.Package changingLib) {
        if (libInfo.getPath() != null) {
            usesLibraryFiles.add(libInfo.getPath());
            return;
        }
        PackageParser.Package p = this.mPackages.get(libInfo.getPackageName());
        if (changingLib != null && changingLib.packageName.equals(libInfo.getPackageName()) && (p == null || p.packageName.equals(changingLib.packageName))) {
            p = changingLib;
        }
        if (p != null) {
            usesLibraryFiles.addAll(p.getAllCodePaths());
            applyDefiningSharedLibraryUpdateLocked(pkg, libInfo, new BiConsumer() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$tQg409Z6C-tp5FPOVwhjUsdZLWA
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((SharedLibraryInfo) obj).addDependency((SharedLibraryInfo) obj2);
                }
            });
            if (p.usesLibraryFiles != null) {
                Collections.addAll(usesLibraryFiles, p.usesLibraryFiles);
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void updateSharedLibrariesLocked(PackageParser.Package pkg, PackageParser.Package changingLib, Map<String, PackageParser.Package> availablePackages) throws PackageManagerException {
        ArrayList<SharedLibraryInfo> sharedLibraryInfos = collectSharedLibraryInfos(pkg, availablePackages, this.mSharedLibraries, null);
        executeSharedLibrariesUpdateLPr(pkg, changingLib, sharedLibraryInfos);
    }

    private static ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(PackageParser.Package pkg, Map<String, PackageParser.Package> availablePackages, Map<String, LongSparseArray<SharedLibraryInfo>> existingLibraries, Map<String, LongSparseArray<SharedLibraryInfo>> newLibraries) throws PackageManagerException {
        if (pkg == null) {
            return null;
        }
        ArrayList<SharedLibraryInfo> usesLibraryInfos = null;
        if (pkg.usesLibraries != null) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.usesLibraries, null, null, pkg.packageName, true, pkg.applicationInfo.targetSdkVersion, null, availablePackages, existingLibraries, newLibraries);
        }
        if (pkg.usesStaticLibraries != null) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.usesStaticLibraries, pkg.usesStaticLibrariesVersions, pkg.usesStaticLibrariesCertDigests, pkg.packageName, true, pkg.applicationInfo.targetSdkVersion, usesLibraryInfos, availablePackages, existingLibraries, newLibraries);
        }
        if (pkg.usesOptionalLibraries != null) {
            return collectSharedLibraryInfos(pkg.usesOptionalLibraries, null, null, pkg.packageName, false, pkg.applicationInfo.targetSdkVersion, usesLibraryInfos, availablePackages, existingLibraries, newLibraries);
        }
        return usesLibraryInfos;
    }

    private void executeSharedLibrariesUpdateLPr(PackageParser.Package pkg, PackageParser.Package changingLib, ArrayList<SharedLibraryInfo> usesLibraryInfos) {
        applyDefiningSharedLibraryUpdateLocked(pkg, null, new BiConsumer() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$GRQMseHE8dORyaqj3rZB-aYJdvk
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                SharedLibraryInfo sharedLibraryInfo = (SharedLibraryInfo) obj2;
                ((SharedLibraryInfo) obj).clearDependencies();
            }
        });
        if (usesLibraryInfos != null) {
            pkg.usesLibraryInfos = usesLibraryInfos;
            Set<String> usesLibraryFiles = new LinkedHashSet<>();
            Iterator<SharedLibraryInfo> it = usesLibraryInfos.iterator();
            while (it.hasNext()) {
                SharedLibraryInfo libInfo = it.next();
                addSharedLibraryLPr(pkg, usesLibraryFiles, libInfo, changingLib);
            }
            pkg.usesLibraryFiles = (String[]) usesLibraryFiles.toArray(new String[usesLibraryFiles.size()]);
            return;
        }
        pkg.usesLibraryInfos = null;
        pkg.usesLibraryFiles = null;
    }

    @GuardedBy({"mPackages"})
    private static ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(List<String> requestedLibraries, long[] requiredVersions, String[][] requiredCertDigests, String packageName, boolean required, int targetSdk, ArrayList<SharedLibraryInfo> outUsedLibraries, Map<String, PackageParser.Package> availablePackages, Map<String, LongSparseArray<SharedLibraryInfo>> existingLibraries, Map<String, LongSparseArray<SharedLibraryInfo>> newLibraries) throws PackageManagerException {
        int libCount;
        int libCount2 = requestedLibraries.size();
        int i = 0;
        ArrayList<SharedLibraryInfo> outUsedLibraries2 = outUsedLibraries;
        while (i < libCount2) {
            String libName = requestedLibraries.get(i);
            long libVersion = requiredVersions != null ? requiredVersions[i] : -1L;
            SharedLibraryInfo libraryInfo = getSharedLibraryInfo(libName, libVersion, existingLibraries, newLibraries);
            if (libraryInfo == null) {
                if (required) {
                    throw new PackageManagerException(-9, "Package " + packageName + " requires unavailable shared library " + libName + "; failing!");
                }
                libCount = libCount2;
            } else {
                if (requiredVersions == null || requiredCertDigests == null) {
                    libCount = libCount2;
                } else if (libraryInfo.getLongVersion() != requiredVersions[i]) {
                    throw new PackageManagerException(-9, "Package " + packageName + " requires unavailable static shared library " + libName + " version " + libraryInfo.getLongVersion() + "; failing!");
                } else {
                    PackageParser.Package libPkg = availablePackages.get(libraryInfo.getPackageName());
                    if (libPkg == null) {
                        throw new PackageManagerException(-9, "Package " + packageName + " requires unavailable static shared library; failing!");
                    }
                    String[] expectedCertDigests = requiredCertDigests[i];
                    libCount = libCount2;
                    if (expectedCertDigests.length > 1) {
                        String[] libCertDigests = targetSdk >= 27 ? PackageUtils.computeSignaturesSha256Digests(libPkg.mSigningDetails.signatures) : PackageUtils.computeSignaturesSha256Digests(new Signature[]{libPkg.mSigningDetails.signatures[0]});
                        if (expectedCertDigests.length != libCertDigests.length) {
                            throw new PackageManagerException(-9, "Package " + packageName + " requires differently signed static shared library; failing!");
                        }
                        Arrays.sort(libCertDigests);
                        Arrays.sort(expectedCertDigests);
                        int certCount = libCertDigests.length;
                        int j = 0;
                        while (j < certCount) {
                            String[] libCertDigests2 = libCertDigests;
                            if (!libCertDigests[j].equalsIgnoreCase(expectedCertDigests[j])) {
                                throw new PackageManagerException(-9, "Package " + packageName + " requires differently signed static shared library; failing!");
                            }
                            j++;
                            libCertDigests = libCertDigests2;
                        }
                    } else if (!libPkg.mSigningDetails.hasSha256Certificate(ByteStringUtils.fromHexToByteArray(expectedCertDigests[0]))) {
                        throw new PackageManagerException(-9, "Package " + packageName + " requires differently signed static shared library; failing!");
                    }
                }
                if (outUsedLibraries2 == null) {
                    outUsedLibraries2 = new ArrayList<>();
                }
                outUsedLibraries2.add(libraryInfo);
            }
            i++;
            libCount2 = libCount;
        }
        return outUsedLibraries2;
    }

    private static boolean hasString(List<String> list, List<String> which) {
        if (list == null || which == null) {
            return false;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            for (int j = which.size() - 1; j >= 0; j--) {
                if (which.get(j).equals(list.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    @GuardedBy({"mPackages"})
    private ArrayList<PackageParser.Package> updateAllSharedLibrariesLocked(PackageParser.Package updatedPkg, Map<String, PackageParser.Package> availablePackages) {
        ArrayList<PackageParser.Package> needsUpdating;
        ArrayList<PackageParser.Package> resultList;
        ArraySet<String> descendants;
        int flags;
        ArrayList<PackageParser.Package> resultList2;
        int i;
        ArrayList<PackageParser.Package> resultList3 = null;
        ArraySet<String> descendants2 = null;
        if (updatedPkg == null) {
            needsUpdating = null;
        } else {
            ArrayList<PackageParser.Package> needsUpdating2 = new ArrayList<>(1);
            needsUpdating2.add(updatedPkg);
            needsUpdating = needsUpdating2;
        }
        do {
            PackageParser.Package changingPkg = needsUpdating == null ? null : needsUpdating.remove(0);
            int i2 = this.mPackages.size() - 1;
            while (i2 >= 0) {
                PackageParser.Package pkg = this.mPackages.valueAt(i2);
                if (changingPkg != null && !hasString(pkg.usesLibraries, changingPkg.libraryNames) && !hasString(pkg.usesOptionalLibraries, changingPkg.libraryNames) && !ArrayUtils.contains(pkg.usesStaticLibraries, changingPkg.staticSharedLibName)) {
                    i = i2;
                } else {
                    if (resultList3 != null) {
                        resultList = resultList3;
                    } else {
                        ArrayList<PackageParser.Package> resultList4 = new ArrayList<>();
                        resultList = resultList4;
                    }
                    resultList.add(pkg);
                    if (changingPkg == null) {
                        descendants = descendants2;
                    } else {
                        if (descendants2 == null) {
                            descendants2 = new ArraySet<>();
                        }
                        if (!descendants2.contains(pkg.packageName)) {
                            descendants2.add(pkg.packageName);
                            needsUpdating.add(pkg);
                        }
                        descendants = descendants2;
                    }
                    try {
                        updateSharedLibrariesLocked(pkg, changingPkg, availablePackages);
                        resultList2 = resultList;
                        i = i2;
                    } catch (PackageManagerException e) {
                        if (!pkg.isSystem() || pkg.isUpdatedSystemApp()) {
                            if (!pkg.isUpdatedSystemApp()) {
                                flags = 0;
                            } else {
                                flags = 1;
                            }
                            resultList2 = resultList;
                            i = i2;
                            deletePackageLIF(pkg.packageName, null, true, sUserManager.getUserIds(), flags, null, true, null);
                        } else {
                            resultList2 = resultList;
                            i = i2;
                        }
                        Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
                    }
                    descendants2 = descendants;
                    resultList3 = resultList2;
                }
                i2 = i - 1;
            }
            if (needsUpdating == null) {
                break;
            }
        } while (needsUpdating.size() > 0);
        return resultList3;
    }

    @GuardedBy({"mInstallLock", "mPackages"})
    private List<ScanResult> scanPackageTracedLI(PackageParser.Package pkg, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        int scanFlags2;
        Trace.traceBegin(262144L, "scanPackage");
        if ((scanFlags & 1024) == 0) {
            if (pkg.childPackages != null && pkg.childPackages.size() > 0) {
                scanFlags2 = scanFlags | 1024;
            } else {
                scanFlags2 = scanFlags;
            }
        } else {
            scanFlags2 = scanFlags & (-1025);
        }
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        List<ScanResult> scanResults = new ArrayList<>(childCount + 1);
        try {
            scanResults.add(scanPackageNewLI(pkg, parseFlags, scanFlags2, currentTime, user));
            for (int i = 0; i < childCount; i++) {
                PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
                scanResults.add(scanPackageNewLI(childPkg, parseFlags, scanFlags2, currentTime, user));
            }
            Trace.traceEnd(262144L);
            return (scanFlags2 & 1024) != 0 ? scanPackageTracedLI(pkg, parseFlags, scanFlags2, currentTime, user) : scanResults;
        } catch (Throwable th) {
            Trace.traceEnd(262144L);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ScanResult {
        public final List<String> changedAbiCodePath;
        public final List<SharedLibraryInfo> dynamicSharedLibraryInfos;
        public final boolean existingSettingCopied;
        public final PackageSetting pkgSetting;
        public final ScanRequest request;
        public final SharedLibraryInfo staticSharedLibraryInfo;
        public final boolean success;

        public ScanResult(ScanRequest request, boolean success, PackageSetting pkgSetting, List<String> changedAbiCodePath, boolean existingSettingCopied, SharedLibraryInfo staticSharedLibraryInfo, List<SharedLibraryInfo> dynamicSharedLibraryInfos) {
            this.request = request;
            this.success = success;
            this.pkgSetting = pkgSetting;
            this.changedAbiCodePath = changedAbiCodePath;
            this.existingSettingCopied = existingSettingCopied;
            this.staticSharedLibraryInfo = staticSharedLibraryInfo;
            this.dynamicSharedLibraryInfos = dynamicSharedLibraryInfos;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ScanRequest {
        public final PackageSetting disabledPkgSetting;
        public final boolean isPlatformPackage;
        public final PackageParser.Package oldPkg;
        public final PackageSetting oldPkgSetting;
        public final PackageSetting originalPkgSetting;
        public final int parseFlags;
        public final PackageParser.Package pkg;
        public final PackageSetting pkgSetting;
        public final String realPkgName;
        public final int scanFlags;
        public final SharedUserSetting sharedUserSetting;
        public final UserHandle user;

        public ScanRequest(PackageParser.Package pkg, SharedUserSetting sharedUserSetting, PackageParser.Package oldPkg, PackageSetting pkgSetting, PackageSetting disabledPkgSetting, PackageSetting originalPkgSetting, String realPkgName, int parseFlags, int scanFlags, boolean isPlatformPackage, UserHandle user) {
            this.pkg = pkg;
            this.oldPkg = oldPkg;
            this.pkgSetting = pkgSetting;
            this.sharedUserSetting = sharedUserSetting;
            this.oldPkgSetting = pkgSetting == null ? null : new PackageSetting(pkgSetting);
            this.disabledPkgSetting = disabledPkgSetting;
            this.originalPkgSetting = originalPkgSetting;
            this.realPkgName = realPkgName;
            this.parseFlags = parseFlags;
            this.scanFlags = scanFlags;
            this.isPlatformPackage = isPlatformPackage;
            this.user = user;
        }
    }

    private int adjustScanFlags(int scanFlags, PackageSetting pkgSetting, PackageSetting disabledPkgSetting, UserHandle user, PackageParser.Package pkg) {
        PackageSetting systemPkgSetting;
        if ((scanFlags & 4) != 0 && disabledPkgSetting == null && pkgSetting != null && pkgSetting.isSystem()) {
            systemPkgSetting = pkgSetting;
        } else {
            systemPkgSetting = disabledPkgSetting;
        }
        if (systemPkgSetting != null) {
            scanFlags |= 131072;
            if ((systemPkgSetting.pkgPrivateFlags & 8) != 0) {
                scanFlags |= 262144;
            }
            if ((131072 & systemPkgSetting.pkgPrivateFlags) != 0) {
                scanFlags |= 524288;
            }
            if ((systemPkgSetting.pkgPrivateFlags & 262144) != 0) {
                scanFlags |= 1048576;
            }
            if ((systemPkgSetting.pkgPrivateFlags & 524288) != 0) {
                scanFlags |= 2097152;
            }
            if ((systemPkgSetting.pkgPrivateFlags & 2097152) != 0) {
                scanFlags |= 4194304;
            }
            if ((systemPkgSetting.pkgPrivateFlags & 1073741824) != 0) {
                scanFlags |= 8388608;
            }
        }
        if (pkgSetting != null) {
            int userId = user == null ? 0 : user.getIdentifier();
            if (pkgSetting.getInstantApp(userId)) {
                scanFlags |= 16384;
            }
            if (pkgSetting.getVirtulalPreload(userId)) {
                scanFlags |= 65536;
            }
        }
        boolean skipVendorPrivilegeScan = (1048576 & scanFlags) != 0 && SystemProperties.getInt("ro.vndk.version", 28) < 28;
        if ((scanFlags & 262144) == 0 && !pkg.isPrivileged() && pkg.mSharedUserId != null && !skipVendorPrivilegeScan) {
            SharedUserSetting sharedUserSetting = null;
            try {
                sharedUserSetting = this.mSettings.getSharedUserLPw(pkg.mSharedUserId, 0, 0, false);
            } catch (PackageManagerException e) {
            }
            if (sharedUserSetting != null && sharedUserSetting.isPrivileged()) {
                synchronized (this.mPackages) {
                    PackageSetting platformPkgSetting = this.mSettings.mPackages.get(PLATFORM_PACKAGE_NAME);
                    if (PackageManagerServiceUtils.compareSignatures(platformPkgSetting.signatures.mSigningDetails.signatures, pkg.mSigningDetails.signatures) != 0) {
                        scanFlags |= 262144;
                    }
                }
            }
        }
        return scanFlags;
    }

    @GuardedBy({"mInstallLock", "mPackages"})
    private ScanResult scanPackageNewLI(PackageParser.Package pkg, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        SharedUserSetting sharedUserSetting;
        String renamedPkgName = this.mSettings.getRenamedPackageLPr(pkg.mRealPackage);
        String realPkgName = getRealPackageName(pkg, renamedPkgName);
        if (realPkgName != null) {
            ensurePackageRenamed(pkg, renamedPkgName);
        }
        PackageSetting originalPkgSetting = getOriginalPackageLocked(pkg, renamedPkgName);
        PackageSetting pkgSetting = this.mSettings.getPackageLPr(pkg.packageName);
        PackageSetting disabledPkgSetting = this.mSettings.getDisabledSystemPkgLPr(pkg.packageName);
        if (this.mTransferedPackages.contains(pkg.packageName)) {
            Slog.w(TAG, "Package " + pkg.packageName + " was transferred to another, but its .apk remains");
        }
        int scanFlags2 = adjustScanFlags(scanFlags, pkgSetting, disabledPkgSetting, user, pkg);
        synchronized (this.mPackages) {
            try {
                try {
                    applyPolicy(pkg, parseFlags, scanFlags2, this.mPlatformPackage);
                    assertPackageIsValid(pkg, parseFlags, scanFlags2);
                    sharedUserSetting = null;
                    if (pkg.mSharedUserId != null) {
                        try {
                            sharedUserSetting = this.mSettings.getSharedUserLPw(pkg.mSharedUserId, 0, 0, true);
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
            try {
                ScanRequest request = new ScanRequest(pkg, sharedUserSetting, pkgSetting == null ? null : pkgSetting.pkg, pkgSetting, disabledPkgSetting, originalPkgSetting, realPkgName, parseFlags, scanFlags2, pkg == this.mPlatformPackage, user);
                return scanPackageOnlyLI(request, this.mFactoryTest, currentTime);
            } catch (Throwable th4) {
                th = th4;
                throw th;
            }
        }
    }

    private boolean optimisticallyRegisterAppId(ScanResult result) throws PackageManagerException {
        if (!result.existingSettingCopied) {
            return this.mSettings.registerAppIdLPw(result.pkgSetting);
        }
        return false;
    }

    private void cleanUpAppIdCreation(ScanResult result) {
        if (result.pkgSetting.appId > 0) {
            this.mSettings.removeAppIdLPw(result.pkgSetting.appId);
        }
    }

    @GuardedBy({"mPackages", "mInstallLock"})
    private void commitReconciledScanResultLocked(ReconciledPackage reconciledPkg) {
        PackageSetting pkgSetting;
        PackageSetting pkgSetting2;
        PackageSetting pkgSetting3;
        String realPkgName;
        String realPkgName2;
        ScanResult result = reconciledPkg.scanResult;
        ScanRequest request = result.request;
        PackageParser.Package pkg = request.pkg;
        PackageParser.Package oldPkg = request.oldPkg;
        int parseFlags = request.parseFlags;
        int scanFlags = request.scanFlags;
        PackageSetting oldPkgSetting = request.oldPkgSetting;
        PackageSetting originalPkgSetting = request.originalPkgSetting;
        UserHandle user = request.user;
        String realPkgName3 = request.realPkgName;
        List<String> changedAbiCodePath = result.changedAbiCodePath;
        if (request.pkgSetting != null && request.pkgSetting.sharedUser != null && request.pkgSetting.sharedUser != result.pkgSetting.sharedUser) {
            request.pkgSetting.sharedUser.removePackage(request.pkgSetting);
        }
        if (result.existingSettingCopied) {
            PackageSetting pkgSetting4 = request.pkgSetting;
            pkgSetting4.updateFrom(result.pkgSetting);
            pkg.mExtras = pkgSetting4;
            pkgSetting2 = pkgSetting4;
        } else {
            PackageSetting pkgSetting5 = result.pkgSetting;
            if (originalPkgSetting == null) {
                pkgSetting = pkgSetting5;
            } else {
                pkgSetting = pkgSetting5;
                this.mSettings.addRenamedPackageLPw(pkg.packageName, originalPkgSetting.name);
            }
            if (originalPkgSetting != null && (scanFlags & 1024) == 0) {
                this.mTransferedPackages.add(originalPkgSetting.name);
            }
            pkgSetting2 = pkgSetting;
        }
        if (pkgSetting2.sharedUser != null) {
            pkgSetting2.sharedUser.addPackage(pkgSetting2);
        }
        pkg.applicationInfo.uid = pkgSetting2.appId;
        this.mSettings.writeUserRestrictionsLPw(pkgSetting2, oldPkgSetting);
        if ((scanFlags & 1024) == 0 && realPkgName3 != null) {
            this.mTransferedPackages.add(pkg.packageName);
        }
        if (reconciledPkg.collectedSharedLibraryInfos != null) {
            executeSharedLibrariesUpdateLPr(pkg, null, reconciledPkg.collectedSharedLibraryInfos);
        }
        KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
        if (reconciledPkg.removeAppKeySetData) {
            ksms.removeAppKeySetDataLPw(pkg.packageName);
        }
        if (reconciledPkg.sharedUserSignaturesChanged) {
            pkgSetting2.sharedUser.signaturesChanged = Boolean.TRUE;
            pkgSetting2.sharedUser.signatures.mSigningDetails = reconciledPkg.signingDetails;
        }
        pkgSetting2.signatures.mSigningDetails = reconciledPkg.signingDetails;
        if ((scanFlags & 1024) != 0 || pkg.mAdoptPermissions == null) {
            pkgSetting3 = pkgSetting2;
            realPkgName = realPkgName3;
        } else {
            int i = pkg.mAdoptPermissions.size() - 1;
            while (i >= 0) {
                String origName = (String) pkg.mAdoptPermissions.get(i);
                PackageSetting pkgSetting6 = pkgSetting2;
                PackageSetting orig = this.mSettings.getPackageLPr(origName);
                if (orig == null) {
                    realPkgName2 = realPkgName3;
                } else if (verifyPackageUpdateLPr(orig, pkg)) {
                    StringBuilder sb = new StringBuilder();
                    realPkgName2 = realPkgName3;
                    sb.append("Adopting permissions from ");
                    sb.append(origName);
                    sb.append(" to ");
                    sb.append(pkg.packageName);
                    Slog.i(TAG, sb.toString());
                    this.mSettings.mPermissions.transferPermissions(origName, pkg.packageName);
                } else {
                    realPkgName2 = realPkgName3;
                }
                i--;
                pkgSetting2 = pkgSetting6;
                realPkgName3 = realPkgName2;
            }
            pkgSetting3 = pkgSetting2;
            realPkgName = realPkgName3;
        }
        if (changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
            for (int i2 = changedAbiCodePath.size() - 1; i2 >= 0; i2--) {
                String codePathString = changedAbiCodePath.get(i2);
                try {
                    this.mInstaller.rmdex(codePathString, InstructionSets.getDexCodeInstructionSet(InstructionSets.getPreferredInstructionSet()));
                } catch (Installer.InstallerException e) {
                }
            }
        }
        if ((scanFlags & 1024) != 0) {
            if (oldPkgSetting != null) {
                synchronized (this.mPackages) {
                    this.mSettings.mPackages.put(oldPkgSetting.name, oldPkgSetting);
                }
                return;
            }
            return;
        }
        int userId = user == null ? 0 : user.getIdentifier();
        PackageSetting pkgSetting7 = pkgSetting3;
        commitPackageSettings(pkg, oldPkg, pkgSetting7, scanFlags, (Integer.MIN_VALUE & parseFlags) != 0, reconciledPkg);
        if (pkgSetting7.getInstantApp(userId)) {
            this.mInstantAppRegistry.addInstantAppLPw(userId, pkgSetting7.appId);
        }
    }

    private static String getRealPackageName(PackageParser.Package pkg, String renamedPkgName) {
        if (isPackageRenamed(pkg, renamedPkgName)) {
            return pkg.mRealPackage;
        }
        return null;
    }

    private static boolean isPackageRenamed(PackageParser.Package pkg, String renamedPkgName) {
        return pkg.mOriginalPackages != null && pkg.mOriginalPackages.contains(renamedPkgName);
    }

    @GuardedBy({"mPackages"})
    private PackageSetting getOriginalPackageLocked(PackageParser.Package pkg, String renamedPkgName) {
        if (isPackageRenamed(pkg, renamedPkgName)) {
            for (int i = pkg.mOriginalPackages.size() - 1; i >= 0; i--) {
                PackageSetting originalPs = this.mSettings.getPackageLPr((String) pkg.mOriginalPackages.get(i));
                if (originalPs != null && verifyPackageUpdateLPr(originalPs, pkg)) {
                    if (originalPs.sharedUser == null || originalPs.sharedUser.name.equals(pkg.mSharedUserId)) {
                        return originalPs;
                    }
                    Slog.w(TAG, "Unable to migrate data from " + originalPs.name + " to " + pkg.packageName + ": old uid " + originalPs.sharedUser.name + " differs from " + pkg.mSharedUserId);
                }
            }
            return null;
        }
        return null;
    }

    private static void ensurePackageRenamed(PackageParser.Package pkg, String renamedPackageName) {
        if (pkg.mOriginalPackages == null || !pkg.mOriginalPackages.contains(renamedPackageName) || pkg.packageName.equals(renamedPackageName)) {
            return;
        }
        pkg.setPackageName(renamedPackageName);
    }

    @GuardedBy({"mInstallLock"})
    private static ScanResult scanPackageOnlyLI(ScanRequest request, boolean isUnderFactoryTest, long currentTime) throws PackageManagerException {
        boolean needToDeriveAbi;
        String primaryCpuAbiFromSettings;
        String secondaryCpuAbiFromSettings;
        int parseFlags;
        String str;
        String primaryCpuAbiFromSettings2;
        boolean isPlatformPackage;
        String secondaryCpuAbiFromSettings2;
        PackageSetting pkgSetting;
        UserHandle user;
        String secondaryCpuAbiFromSettings3;
        boolean z;
        String secondaryCpuAbiFromSettings4;
        SharedLibraryInfo staticSharedLibraryInfo;
        List<SharedLibraryInfo> dynamicSharedLibraryInfos;
        String str2;
        PackageParser.Package pkg = request.pkg;
        PackageSetting pkgSetting2 = request.pkgSetting;
        PackageSetting disabledPkgSetting = request.disabledPkgSetting;
        PackageSetting originalPkgSetting = request.originalPkgSetting;
        int parseFlags2 = request.parseFlags;
        int scanFlags = request.scanFlags;
        String realPkgName = request.realPkgName;
        SharedUserSetting sharedUserSetting = request.sharedUserSetting;
        UserHandle user2 = request.user;
        boolean isPlatformPackage2 = request.isPlatformPackage;
        List<String> changedAbiCodePath = null;
        new File(pkg.codePath);
        File destCodeFile = new File(pkg.applicationInfo.getCodePath());
        File destResourceFile = new File(pkg.applicationInfo.getResourcePath());
        String secondaryCpuAbiFromSettings5 = null;
        boolean needToDeriveAbi2 = (scanFlags & 8192) != 0;
        if (needToDeriveAbi2) {
            needToDeriveAbi = needToDeriveAbi2;
            primaryCpuAbiFromSettings = null;
        } else if (pkgSetting2 != null) {
            String primaryCpuAbiFromSettings3 = pkgSetting2.primaryCpuAbiString;
            secondaryCpuAbiFromSettings5 = pkgSetting2.secondaryCpuAbiString;
            needToDeriveAbi = needToDeriveAbi2;
            primaryCpuAbiFromSettings = primaryCpuAbiFromSettings3;
        } else {
            needToDeriveAbi = true;
            primaryCpuAbiFromSettings = null;
        }
        if (pkgSetting2 == null || pkgSetting2.sharedUser == sharedUserSetting) {
            secondaryCpuAbiFromSettings = secondaryCpuAbiFromSettings5;
        } else {
            StringBuilder sb = new StringBuilder();
            secondaryCpuAbiFromSettings = secondaryCpuAbiFromSettings5;
            sb.append("Package ");
            sb.append(pkg.packageName);
            sb.append(" shared user changed from ");
            sb.append(pkgSetting2.sharedUser != null ? pkgSetting2.sharedUser.name : "<nothing>");
            sb.append(" to ");
            sb.append(sharedUserSetting != null ? sharedUserSetting.name : "<nothing>");
            sb.append("; replacing with new");
            reportSettingsProblem(5, sb.toString());
            pkgSetting2 = null;
        }
        String[] usesStaticLibraries = null;
        if (pkg.usesStaticLibraries != null) {
            usesStaticLibraries = new String[pkg.usesStaticLibraries.size()];
            pkg.usesStaticLibraries.toArray(usesStaticLibraries);
        }
        boolean createNewPackage = pkgSetting2 == null;
        if (createNewPackage) {
            String parentPackageName = pkg.parentPackage != null ? pkg.parentPackage.packageName : null;
            boolean instantApp = (scanFlags & 16384) != 0;
            boolean virtualPreload = (65536 & scanFlags) != 0;
            str = " to ";
            parseFlags = parseFlags2;
            primaryCpuAbiFromSettings2 = primaryCpuAbiFromSettings;
            isPlatformPackage = isPlatformPackage2;
            secondaryCpuAbiFromSettings2 = secondaryCpuAbiFromSettings;
            pkgSetting = Settings.createNewSetting(pkg.packageName, originalPkgSetting, disabledPkgSetting, realPkgName, sharedUserSetting, destCodeFile, destResourceFile, pkg.applicationInfo.nativeLibraryRootDir, pkg.applicationInfo.primaryCpuAbi, pkg.applicationInfo.secondaryCpuAbi, pkg.mVersionCode, pkg.applicationInfo.flags, pkg.applicationInfo.privateFlags, user2, true, instantApp, virtualPreload, parentPackageName, pkg.getChildPackageNames(), UserManagerService.getInstance(), usesStaticLibraries, pkg.usesStaticLibrariesVersions);
        } else {
            parseFlags = parseFlags2;
            str = " to ";
            primaryCpuAbiFromSettings2 = primaryCpuAbiFromSettings;
            isPlatformPackage = isPlatformPackage2;
            PackageSetting pkgSetting3 = new PackageSetting(pkgSetting2);
            pkgSetting3.pkg = pkg;
            secondaryCpuAbiFromSettings2 = secondaryCpuAbiFromSettings;
            Settings.updatePackageSetting(pkgSetting3, disabledPkgSetting, sharedUserSetting, destCodeFile, destResourceFile, pkg.applicationInfo.nativeLibraryDir, pkg.applicationInfo.primaryCpuAbi, pkg.applicationInfo.secondaryCpuAbi, pkg.applicationInfo.flags, pkg.applicationInfo.privateFlags, pkg.getChildPackageNames(), UserManagerService.getInstance(), usesStaticLibraries, pkg.usesStaticLibrariesVersions);
            pkgSetting = pkgSetting3;
        }
        if (createNewPackage && originalPkgSetting != null) {
            pkg.setPackageName(originalPkgSetting.name);
            String msg = "New package " + pkgSetting.realName + " renamed to replace old package " + pkgSetting.name;
            reportSettingsProblem(5, msg);
        }
        int userId = user2 == null ? 0 : user2.getIdentifier();
        if (!createNewPackage) {
            boolean instantApp2 = (scanFlags & 16384) != 0;
            boolean fullApp = (32768 & scanFlags) != 0;
            setInstantAppForUser(pkgSetting, userId, instantApp2, fullApp);
        }
        if (disabledPkgSetting != null || ((scanFlags & 4) != 0 && pkgSetting != null && pkgSetting.isSystem())) {
            pkg.applicationInfo.flags |= 128;
        }
        int targetSdkVersion = (sharedUserSetting == null || sharedUserSetting.packages.size() == 0) ? pkg.applicationInfo.targetSdkVersion : sharedUserSetting.seInfoTargetSdkVersion;
        boolean isPrivileged = sharedUserSetting != null ? sharedUserSetting.isPrivileged() | pkg.isPrivileged() : pkg.isPrivileged();
        pkg.applicationInfo.seInfo = SELinuxMMAC.getSeInfo(pkg, isPrivileged, pkg.applicationInfo.targetSandboxVersion, targetSdkVersion);
        pkg.applicationInfo.seInfoUser = SELinuxUtil.assignSeinfoUser(pkgSetting.readUserState(userId == -1 ? 0 : userId));
        pkg.mExtras = pkgSetting;
        pkg.applicationInfo.processName = fixProcessName(pkg.applicationInfo.packageName, pkg.applicationInfo.processName);
        if (!isPlatformPackage) {
            pkg.applicationInfo.initForUser(0);
        }
        String cpuAbiOverride = PackageManagerServiceUtils.deriveAbiOverride(pkg.cpuAbiOverride, pkgSetting);
        if ((scanFlags & 4) == 0) {
            if (needToDeriveAbi) {
                user = user2;
                Trace.traceBegin(262144L, "derivePackageAbi");
                boolean extractNativeLibs = !pkg.isLibrary();
                derivePackageAbi(pkg, cpuAbiOverride, extractNativeLibs);
                Trace.traceEnd(262144L);
                if (isSystemApp(pkg) && !pkg.isUpdatedSystemApp() && pkg.applicationInfo.primaryCpuAbi == null) {
                    setBundledAppAbisAndRoots(pkg, pkgSetting);
                    setNativeLibraryPaths(pkg, sAppLib32InstallDir);
                }
                secondaryCpuAbiFromSettings3 = secondaryCpuAbiFromSettings2;
            } else {
                user = user2;
                pkg.applicationInfo.primaryCpuAbi = primaryCpuAbiFromSettings2;
                secondaryCpuAbiFromSettings3 = secondaryCpuAbiFromSettings2;
                pkg.applicationInfo.secondaryCpuAbi = secondaryCpuAbiFromSettings3;
                setNativeLibraryPaths(pkg, sAppLib32InstallDir);
            }
        } else {
            user = user2;
            secondaryCpuAbiFromSettings3 = secondaryCpuAbiFromSettings2;
            if ((scanFlags & 256) != 0) {
                pkg.applicationInfo.primaryCpuAbi = pkgSetting.primaryCpuAbiString;
                pkg.applicationInfo.secondaryCpuAbi = pkgSetting.secondaryCpuAbiString;
            }
            setNativeLibraryPaths(pkg, sAppLib32InstallDir);
        }
        if (!isPlatformPackage) {
            z = false;
        } else {
            ApplicationInfo applicationInfo = pkg.applicationInfo;
            if (VMRuntime.getRuntime().is64Bit()) {
                z = false;
                str2 = Build.SUPPORTED_64_BIT_ABIS[0];
            } else {
                z = false;
                str2 = Build.SUPPORTED_32_BIT_ABIS[0];
            }
            applicationInfo.primaryCpuAbi = str2;
        }
        if ((scanFlags & 1) != 0 || (scanFlags & 4) == 0) {
            secondaryCpuAbiFromSettings4 = secondaryCpuAbiFromSettings3;
        } else if (cpuAbiOverride != null || pkg.packageName == null) {
            secondaryCpuAbiFromSettings4 = secondaryCpuAbiFromSettings3;
        } else {
            StringBuilder sb2 = new StringBuilder();
            secondaryCpuAbiFromSettings4 = secondaryCpuAbiFromSettings3;
            sb2.append("Ignoring persisted ABI override ");
            sb2.append(cpuAbiOverride);
            sb2.append(" for package ");
            sb2.append(pkg.packageName);
            Slog.w(TAG, sb2.toString());
        }
        pkgSetting.primaryCpuAbiString = pkg.applicationInfo.primaryCpuAbi;
        pkgSetting.secondaryCpuAbiString = pkg.applicationInfo.secondaryCpuAbi;
        pkgSetting.cpuAbiOverrideString = cpuAbiOverride;
        pkg.cpuAbiOverride = cpuAbiOverride;
        pkgSetting.legacyNativeLibraryPathString = pkg.applicationInfo.nativeLibraryRootDir;
        if ((scanFlags & 16) == 0 && pkgSetting.sharedUser != null) {
            changedAbiCodePath = adjustCpuAbisForSharedUserLPw(pkgSetting.sharedUser.packages, pkg);
        }
        if (isUnderFactoryTest && pkg.requestedPermissions.contains("android.permission.FACTORY_TEST")) {
            pkg.applicationInfo.flags |= 16;
        }
        if (isSystemApp(pkg)) {
            pkgSetting.isOrphaned = true;
        }
        long scanFileTime = PackageManagerServiceUtils.getLastModifiedTime(pkg);
        if (currentTime != 0) {
            if (pkgSetting.firstInstallTime == 0) {
                pkgSetting.lastUpdateTime = currentTime;
                pkgSetting.firstInstallTime = currentTime;
            } else if ((scanFlags & 8) != 0) {
                pkgSetting.lastUpdateTime = currentTime;
            }
        } else if (pkgSetting.firstInstallTime == 0) {
            pkgSetting.lastUpdateTime = scanFileTime;
            pkgSetting.firstInstallTime = scanFileTime;
        } else if ((parseFlags & 16) != 0 && scanFileTime != pkgSetting.timeStamp) {
            pkgSetting.lastUpdateTime = scanFileTime;
        }
        pkgSetting.setTimeStamp(scanFileTime);
        pkgSetting.pkg = pkg;
        pkgSetting.pkgFlags = pkg.applicationInfo.flags;
        if (pkg.getLongVersionCode() != pkgSetting.versionCode) {
            pkgSetting.versionCode = pkg.getLongVersionCode();
        }
        String volumeUuid = pkg.applicationInfo.volumeUuid;
        if (!Objects.equals(volumeUuid, pkgSetting.volumeUuid)) {
            StringBuilder sb3 = new StringBuilder();
            sb3.append("Update");
            sb3.append(pkgSetting.isSystem() ? " system" : "");
            sb3.append(" package ");
            sb3.append(pkg.packageName);
            sb3.append(" volume from ");
            sb3.append(pkgSetting.volumeUuid);
            sb3.append(str);
            sb3.append(volumeUuid);
            Slog.i(TAG, sb3.toString());
            pkgSetting.volumeUuid = volumeUuid;
        }
        if (TextUtils.isEmpty(pkg.staticSharedLibName)) {
            staticSharedLibraryInfo = null;
        } else {
            SharedLibraryInfo staticSharedLibraryInfo2 = SharedLibraryInfo.createForStatic(pkg);
            staticSharedLibraryInfo = staticSharedLibraryInfo2;
        }
        if (ArrayUtils.isEmpty(pkg.libraryNames)) {
            dynamicSharedLibraryInfos = null;
        } else {
            List<SharedLibraryInfo> dynamicSharedLibraryInfos2 = new ArrayList<>(pkg.libraryNames.size());
            Iterator it = pkg.libraryNames.iterator();
            while (it.hasNext()) {
                String name = (String) it.next();
                dynamicSharedLibraryInfos2.add(SharedLibraryInfo.createForDynamic(pkg, name));
            }
            dynamicSharedLibraryInfos = dynamicSharedLibraryInfos2;
        }
        if (!createNewPackage) {
            z = true;
        }
        return new ScanResult(request, true, pkgSetting, changedAbiCodePath, z, staticSharedLibraryInfo, dynamicSharedLibraryInfos);
    }

    private static boolean apkHasCode(String fileName) {
        StrictJarFile jarFile = null;
        try {
            jarFile = new StrictJarFile(fileName, false, false);
            boolean z = jarFile.findEntry("classes.dex") != null;
            try {
                jarFile.close();
            } catch (IOException e) {
            }
            return z;
        } catch (IOException e2) {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException e3) {
                }
            }
            return false;
        } catch (Throwable th) {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException e4) {
                }
            }
            throw th;
        }
    }

    private static void assertCodePolicy(PackageParser.Package pkg) throws PackageManagerException {
        boolean shouldHaveCode = (pkg.applicationInfo.flags & 4) != 0;
        if (shouldHaveCode && !apkHasCode(pkg.baseCodePath)) {
            throw new PackageManagerException(-2, "Package " + pkg.baseCodePath + " code is missing");
        } else if (!ArrayUtils.isEmpty(pkg.splitCodePaths)) {
            for (int i = 0; i < pkg.splitCodePaths.length; i++) {
                boolean splitShouldHaveCode = (pkg.splitFlags[i] & 4) != 0;
                if (splitShouldHaveCode && !apkHasCode(pkg.splitCodePaths[i])) {
                    throw new PackageManagerException(-2, "Package " + pkg.splitCodePaths[i] + " code is missing");
                }
            }
        }
    }

    private static void applyPolicy(PackageParser.Package pkg, int parseFlags, int scanFlags, PackageParser.Package platformPkg) {
        if ((scanFlags & 131072) != 0) {
            pkg.applicationInfo.flags |= 1;
            if (pkg.applicationInfo.isDirectBootAware()) {
                Iterator it = pkg.services.iterator();
                while (it.hasNext()) {
                    PackageParser.Service s = (PackageParser.Service) it.next();
                    ServiceInfo serviceInfo = s.info;
                    s.info.directBootAware = true;
                    serviceInfo.encryptionAware = true;
                }
                Iterator it2 = pkg.providers.iterator();
                while (it2.hasNext()) {
                    PackageParser.Provider p = (PackageParser.Provider) it2.next();
                    ProviderInfo providerInfo = p.info;
                    p.info.directBootAware = true;
                    providerInfo.encryptionAware = true;
                }
                Iterator it3 = pkg.activities.iterator();
                while (it3.hasNext()) {
                    PackageParser.Activity a = (PackageParser.Activity) it3.next();
                    ActivityInfo activityInfo = a.info;
                    a.info.directBootAware = true;
                    activityInfo.encryptionAware = true;
                }
                Iterator it4 = pkg.receivers.iterator();
                while (it4.hasNext()) {
                    PackageParser.Activity r = (PackageParser.Activity) it4.next();
                    ActivityInfo activityInfo2 = r.info;
                    r.info.directBootAware = true;
                    activityInfo2.encryptionAware = true;
                }
            }
            if (PackageManagerServiceUtils.compressedFileExists(pkg.codePath)) {
                pkg.isStub = true;
            }
        } else {
            pkg.coreApp = false;
            pkg.applicationInfo.flags &= -9;
            pkg.applicationInfo.privateFlags &= -33;
            pkg.applicationInfo.privateFlags &= -65;
            pkg.protectedBroadcasts = null;
            if (pkg.permissionGroups != null && pkg.permissionGroups.size() > 0) {
                for (int i = pkg.permissionGroups.size() - 1; i >= 0; i--) {
                    ((PackageParser.PermissionGroup) pkg.permissionGroups.get(i)).info.priority = 0;
                }
            }
        }
        if ((scanFlags & 262144) == 0) {
            if (pkg.receivers != null) {
                for (int i2 = pkg.receivers.size() - 1; i2 >= 0; i2--) {
                    PackageParser.Activity receiver = (PackageParser.Activity) pkg.receivers.get(i2);
                    if ((receiver.info.flags & 1073741824) != 0) {
                        receiver.info.exported = false;
                    }
                }
            }
            if (pkg.services != null) {
                for (int i3 = pkg.services.size() - 1; i3 >= 0; i3--) {
                    PackageParser.Service service = (PackageParser.Service) pkg.services.get(i3);
                    if ((service.info.flags & 1073741824) != 0) {
                        service.info.exported = false;
                    }
                }
            }
            if (pkg.providers != null) {
                for (int i4 = pkg.providers.size() - 1; i4 >= 0; i4--) {
                    PackageParser.Provider provider = (PackageParser.Provider) pkg.providers.get(i4);
                    if ((provider.info.flags & 1073741824) != 0) {
                        provider.info.exported = false;
                    }
                }
            }
        }
        if ((scanFlags & 262144) != 0) {
            pkg.applicationInfo.privateFlags |= 8;
        }
        if ((scanFlags & 524288) != 0) {
            ApplicationInfo applicationInfo = pkg.applicationInfo;
            applicationInfo.privateFlags = 131072 | applicationInfo.privateFlags;
        }
        if ((scanFlags & 1048576) != 0) {
            ApplicationInfo applicationInfo2 = pkg.applicationInfo;
            applicationInfo2.privateFlags = 262144 | applicationInfo2.privateFlags;
        }
        if ((scanFlags & 2097152) != 0) {
            ApplicationInfo applicationInfo3 = pkg.applicationInfo;
            applicationInfo3.privateFlags = 524288 | applicationInfo3.privateFlags;
        }
        if ((4194304 & scanFlags) != 0) {
            ApplicationInfo applicationInfo4 = pkg.applicationInfo;
            applicationInfo4.privateFlags = 2097152 | applicationInfo4.privateFlags;
        }
        if ((8388608 & scanFlags) != 0) {
            pkg.applicationInfo.privateFlags |= 1073741824;
        }
        if (PLATFORM_PACKAGE_NAME.equals(pkg.packageName) || (platformPkg != null && PackageManagerServiceUtils.compareSignatures(platformPkg.mSigningDetails.signatures, pkg.mSigningDetails.signatures) == 0)) {
            ApplicationInfo applicationInfo5 = pkg.applicationInfo;
            applicationInfo5.privateFlags = 1048576 | applicationInfo5.privateFlags;
        }
        if (!isSystemApp(pkg)) {
            pkg.mOriginalPackages = null;
            pkg.mRealPackage = null;
            pkg.mAdoptPermissions = null;
        }
        PackageBackwardCompatibility.modifySharedLibraries(pkg);
    }

    private static <T> T assertNotNull(T object, String message) throws PackageManagerException {
        if (object == null) {
            throw new PackageManagerException(RequestStatus.SYS_ETIMEDOUT, message);
        }
        return object;
    }

    private void assertPackageIsValid(PackageParser.Package pkg, int parseFlags, int scanFlags) throws PackageManagerException {
        PackageSetting targetPkgSetting;
        if ((parseFlags & 64) != 0) {
            assertCodePolicy(pkg);
        }
        if (pkg.applicationInfo.getCodePath() == null || pkg.applicationInfo.getResourcePath() == null) {
            throw new PackageManagerException(-2, "Code and resource paths haven't been set correctly");
        }
        boolean isUserInstall = (scanFlags & 16) == 0;
        boolean isFirstBootOrUpgrade = (scanFlags & 8192) != 0;
        if ((isUserInstall || isFirstBootOrUpgrade) && this.mApexManager.isApexPackage(pkg.packageName)) {
            throw new PackageManagerException(-5, pkg.packageName + " is an APEX package and can't be installed as an APK.");
        }
        KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
        ksms.assertScannedPackageValid(pkg);
        synchronized (this.mPackages) {
            try {
                try {
                    if (pkg.packageName.equals(PLATFORM_PACKAGE_NAME)) {
                        try {
                            if (this.mAndroidApplication != null) {
                                Slog.w(TAG, "*************************************************");
                                Slog.w(TAG, "Core android package being redefined.  Skipping.");
                                Slog.w(TAG, " codePath=" + pkg.codePath);
                                Slog.w(TAG, "*************************************************");
                                throw new PackageManagerException(-5, "Core android package being redefined.  Skipping.");
                            }
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                    if ((scanFlags & 4) == 0 && this.mPackages.containsKey(pkg.packageName)) {
                        throw new PackageManagerException(-5, "Application package " + pkg.packageName + " already installed.  Skipping duplicate.");
                    }
                    if (pkg.applicationInfo.isStaticSharedLibrary()) {
                        if ((scanFlags & 4) == 0 && this.mPackages.containsKey(pkg.manifestPackageName)) {
                            throw new PackageManagerException("Duplicate static shared lib provider package");
                        }
                        if (pkg.applicationInfo.targetSdkVersion < 26) {
                            throw new PackageManagerException("Packages declaring static-shared libs must target O SDK or higher");
                        }
                        if ((scanFlags & 16384) != 0) {
                            throw new PackageManagerException("Packages declaring static-shared libs cannot be instant apps");
                        }
                        if (!ArrayUtils.isEmpty(pkg.mOriginalPackages)) {
                            throw new PackageManagerException("Packages declaring static-shared libs cannot be renamed");
                        }
                        if (!ArrayUtils.isEmpty(pkg.childPackages)) {
                            throw new PackageManagerException("Packages declaring static-shared libs cannot have child packages");
                        }
                        if (!ArrayUtils.isEmpty(pkg.libraryNames)) {
                            throw new PackageManagerException("Packages declaring static-shared libs cannot declare dynamic libs");
                        }
                        if (pkg.mSharedUserId != null) {
                            throw new PackageManagerException("Packages declaring static-shared libs cannot declare shared users");
                        }
                        if (!pkg.activities.isEmpty()) {
                            throw new PackageManagerException("Static shared libs cannot declare activities");
                        }
                        if (!pkg.services.isEmpty()) {
                            throw new PackageManagerException("Static shared libs cannot declare services");
                        }
                        if (!pkg.providers.isEmpty()) {
                            throw new PackageManagerException("Static shared libs cannot declare content providers");
                        }
                        if (!pkg.receivers.isEmpty()) {
                            throw new PackageManagerException("Static shared libs cannot declare broadcast receivers");
                        }
                        if (!pkg.permissionGroups.isEmpty()) {
                            throw new PackageManagerException("Static shared libs cannot declare permission groups");
                        }
                        if (!pkg.permissions.isEmpty()) {
                            throw new PackageManagerException("Static shared libs cannot declare permissions");
                        }
                        if (pkg.protectedBroadcasts != null) {
                            throw new PackageManagerException("Static shared libs cannot declare protected broadcasts");
                        }
                        if (pkg.mOverlayTarget != null) {
                            throw new PackageManagerException("Static shared libs cannot be overlay targets");
                        }
                        long minVersionCode = Long.MIN_VALUE;
                        long maxVersionCode = JobStatus.NO_LATEST_RUNTIME;
                        LongSparseArray<SharedLibraryInfo> versionedLib = this.mSharedLibraries.get(pkg.staticSharedLibName);
                        if (versionedLib != null) {
                            int versionCount = versionedLib.size();
                            int i = 0;
                            while (true) {
                                if (i >= versionCount) {
                                    break;
                                }
                                SharedLibraryInfo libInfo = versionedLib.valueAt(i);
                                long libVersionCode = libInfo.getDeclaringPackage().getLongVersionCode();
                                boolean isUserInstall2 = isUserInstall;
                                if (libInfo.getLongVersion() < pkg.staticSharedLibVersion) {
                                    minVersionCode = Math.max(minVersionCode, libVersionCode + 1);
                                } else {
                                    long minVersionCode2 = minVersionCode;
                                    if (libInfo.getLongVersion() > pkg.staticSharedLibVersion) {
                                        maxVersionCode = Math.min(maxVersionCode, libVersionCode - 1);
                                        minVersionCode = minVersionCode2;
                                    } else {
                                        maxVersionCode = libVersionCode;
                                        minVersionCode = libVersionCode;
                                        break;
                                    }
                                }
                                i++;
                                isUserInstall = isUserInstall2;
                            }
                        }
                        if (pkg.getLongVersionCode() < minVersionCode || pkg.getLongVersionCode() > maxVersionCode) {
                            throw new PackageManagerException("Static shared lib version codes must be ordered as lib versions");
                        }
                    }
                    if (pkg.childPackages != null && !pkg.childPackages.isEmpty()) {
                        if ((262144 & scanFlags) == 0) {
                            throw new PackageManagerException("Only privileged apps can add child packages. Ignoring package " + pkg.packageName);
                        }
                        int childCount = pkg.childPackages.size();
                        for (int i2 = 0; i2 < childCount; i2++) {
                            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i2);
                            if (this.mSettings.hasOtherDisabledSystemPkgWithChildLPr(pkg.packageName, childPkg.packageName)) {
                                throw new PackageManagerException("Can't override child of another disabled app. Ignoring package " + pkg.packageName);
                            }
                        }
                    }
                    if ((scanFlags & 128) != 0) {
                        if (this.mExpectingBetter.containsKey(pkg.packageName)) {
                            PackageManagerServiceUtils.logCriticalInfo(5, "Relax SCAN_REQUIRE_KNOWN requirement for package " + pkg.packageName);
                        } else {
                            PackageSetting known = this.mSettings.getPackageLPr(pkg.packageName);
                            if (known != null) {
                                if (!pkg.applicationInfo.getCodePath().equals(known.codePathString) || !pkg.applicationInfo.getResourcePath().equals(known.resourcePathString)) {
                                    throw new PackageManagerException(-23, "Application package " + pkg.packageName + " found at " + pkg.applicationInfo.getCodePath() + " but expected at " + known.codePathString + "; ignoring.");
                                }
                            } else {
                                throw new PackageManagerException(-19, "Application package " + pkg.packageName + " not found; ignoring.");
                            }
                        }
                    }
                    if ((scanFlags & 4) != 0) {
                        this.mComponentResolver.assertProvidersNotDefined(pkg);
                    }
                    if (!pkg.isPrivileged() && pkg.mSharedUserId != null) {
                        SharedUserSetting sharedUserSetting = null;
                        try {
                            sharedUserSetting = this.mSettings.getSharedUserLPw(pkg.mSharedUserId, 0, 0, false);
                        } catch (PackageManagerException e) {
                        }
                        if (sharedUserSetting != null && sharedUserSetting.isPrivileged()) {
                            PackageSetting platformPkgSetting = this.mSettings.mPackages.get(PLATFORM_PACKAGE_NAME);
                            if (platformPkgSetting.signatures.mSigningDetails != PackageParser.SigningDetails.UNKNOWN && PackageManagerServiceUtils.compareSignatures(platformPkgSetting.signatures.mSigningDetails.signatures, pkg.mSigningDetails.signatures) != 0) {
                                throw new PackageManagerException("Apps that share a user with a privileged app must themselves be marked as privileged. " + pkg.packageName + " shares privileged user " + pkg.mSharedUserId + ".");
                            }
                        }
                    }
                    if (pkg.mOverlayTarget != null) {
                        if ((131072 & scanFlags) != 0) {
                            if ((parseFlags & 16) == 0) {
                                PackageSetting previousPkg = (PackageSetting) assertNotNull(this.mSettings.getPackageLPr(pkg.packageName), "previous package state not present");
                                PackageParser.Package ppkg = previousPkg.pkg;
                                if (ppkg == null) {
                                    try {
                                        PackageParser pp = new PackageParser();
                                        ppkg = pp.parsePackage(previousPkg.codePath, parseFlags | 16);
                                    } catch (PackageParser.PackageParserException e2) {
                                        Slog.w(TAG, "failed to parse " + previousPkg.codePath, e2);
                                    }
                                }
                                if (ppkg != null && ppkg.mOverlayIsStatic) {
                                    throw new PackageManagerException("Overlay " + pkg.packageName + " is static and cannot be upgraded.");
                                }
                                if (pkg.mOverlayIsStatic) {
                                    throw new PackageManagerException("Overlay " + pkg.packageName + " cannot be upgraded into a static overlay.");
                                }
                            }
                        } else if (pkg.mOverlayIsStatic) {
                            throw new PackageManagerException("Overlay " + pkg.packageName + " is static but not pre-installed.");
                        } else {
                            if (pkg.applicationInfo.targetSdkVersion < 29) {
                                PackageSetting platformPkgSetting2 = this.mSettings.getPackageLPr(PLATFORM_PACKAGE_NAME);
                                if (platformPkgSetting2.signatures.mSigningDetails != PackageParser.SigningDetails.UNKNOWN && PackageManagerServiceUtils.compareSignatures(platformPkgSetting2.signatures.mSigningDetails.signatures, pkg.mSigningDetails.signatures) != 0) {
                                    throw new PackageManagerException("Overlay " + pkg.packageName + " must target Q or later, or be signed with the platform certificate");
                                }
                            }
                            if (pkg.mOverlayTargetName == null && (targetPkgSetting = this.mSettings.getPackageLPr(pkg.mOverlayTarget)) != null && targetPkgSetting.signatures.mSigningDetails != PackageParser.SigningDetails.UNKNOWN && PackageManagerServiceUtils.compareSignatures(targetPkgSetting.signatures.mSigningDetails.signatures, pkg.mSigningDetails.signatures) != 0) {
                                throw new PackageManagerException("Overlay " + pkg.packageName + " and target " + pkg.mOverlayTarget + " signed with different certificates, and the overlay lacks <overlay android:targetName>");
                            }
                        }
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
                throw th;
            }
        }
    }

    @GuardedBy({"mPackages"})
    private boolean addBuiltInSharedLibraryLocked(String path, String name) {
        if (nonStaticSharedLibExistsLocked(name)) {
            return false;
        }
        SharedLibraryInfo libraryInfo = new SharedLibraryInfo(path, null, null, name, -1L, 0, new VersionedPackage(PLATFORM_PACKAGE_NAME, 0L), null, null);
        commitSharedLibraryInfoLocked(libraryInfo);
        return true;
    }

    @GuardedBy({"mPackages"})
    private boolean nonStaticSharedLibExistsLocked(String name) {
        return sharedLibExists(name, -1L, this.mSharedLibraries);
    }

    private static boolean sharedLibExists(String name, long version, Map<String, LongSparseArray<SharedLibraryInfo>> librarySource) {
        LongSparseArray<SharedLibraryInfo> versionedLib = librarySource.get(name);
        if (versionedLib != null && versionedLib.indexOfKey(version) >= 0) {
            return true;
        }
        return false;
    }

    @GuardedBy({"mPackages"})
    private void commitSharedLibraryInfoLocked(SharedLibraryInfo libraryInfo) {
        String name = libraryInfo.getName();
        LongSparseArray<SharedLibraryInfo> versionedLib = this.mSharedLibraries.get(name);
        if (versionedLib == null) {
            versionedLib = new LongSparseArray<>();
            this.mSharedLibraries.put(name, versionedLib);
        }
        String declaringPackageName = libraryInfo.getDeclaringPackage().getPackageName();
        if (libraryInfo.getType() == 2) {
            this.mStaticLibsByDeclaringPackage.put(declaringPackageName, versionedLib);
        }
        versionedLib.put(libraryInfo.getLongVersion(), libraryInfo);
    }

    private boolean removeSharedLibraryLPw(String name, long version) {
        int libIdx;
        LongSparseArray<SharedLibraryInfo> versionedLib = this.mSharedLibraries.get(name);
        if (versionedLib == null || (libIdx = versionedLib.indexOfKey(version)) < 0) {
            return false;
        }
        SharedLibraryInfo libraryInfo = versionedLib.valueAt(libIdx);
        versionedLib.remove(version);
        if (versionedLib.size() <= 0) {
            this.mSharedLibraries.remove(name);
            if (libraryInfo.getType() == 2) {
                this.mStaticLibsByDeclaringPackage.remove(libraryInfo.getDeclaringPackage().getPackageName());
                return true;
            }
            return true;
        }
        return true;
    }

    /* JADX WARN: Removed duplicated region for block: B:122:0x013a A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:44:0x00f8  */
    /* JADX WARN: Removed duplicated region for block: B:56:0x0110  */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:115:0x02dc -> B:116:0x02dd). Please submit an issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void commitPackageSettings(final android.content.pm.PackageParser.Package r27, final android.content.pm.PackageParser.Package r28, com.android.server.pm.PackageSetting r29, int r30, boolean r31, com.android.server.pm.PackageManagerService.ReconciledPackage r32) {
        /*
            Method dump skipped, instructions count: 735
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.commitPackageSettings(android.content.pm.PackageParser$Package, android.content.pm.PackageParser$Package, com.android.server.pm.PackageSetting, int, boolean, com.android.server.pm.PackageManagerService$ReconciledPackage):void");
    }

    public /* synthetic */ void lambda$commitPackageSettings$7$PackageManagerService(boolean hasOldPkg, PackageParser.Package pkg, PackageParser.Package oldPkg, ArrayList allPackageNames, boolean hasPermissionDefinitionChanges, List permissionsWithChangedDefinition) {
        if (hasOldPkg) {
            this.mPermissionManager.revokeRuntimePermissionsIfGroupChanged(pkg, oldPkg, allPackageNames, this.mPermissionCallback);
            this.mPermissionManager.revokeStoragePermissionsIfScopeExpanded(pkg, oldPkg, this.mPermissionCallback);
        }
        if (hasPermissionDefinitionChanges) {
            this.mPermissionManager.revokeRuntimePermissionsIfPermissionDefinitionChanged(permissionsWithChangedDefinition, allPackageNames, this.mPermissionCallback);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:76:0x0151, code lost:
        if (r17.isLibrary() != false) goto L79;
     */
    /* JADX WARN: Code restructure failed: missing block: B:77:0x0153, code lost:
        r17.applicationInfo.primaryCpuAbi = r11[r9];
     */
    /* JADX WARN: Code restructure failed: missing block: B:79:0x0163, code lost:
        throw new com.android.server.pm.PackageManagerException(android.hardware.biometrics.fingerprint.V2_1.RequestStatus.SYS_ETIMEDOUT, "Shared library with native libs must be multiarch");
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static void derivePackageAbi(android.content.pm.PackageParser.Package r17, java.lang.String r18, boolean r19) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 416
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.derivePackageAbi(android.content.pm.PackageParser$Package, java.lang.String, boolean):void");
    }

    private static List<String> adjustCpuAbisForSharedUserLPw(Set<PackageSetting> packagesForUser, PackageParser.Package scannedPackage) {
        String adjustedAbi;
        List<String> changedAbiCodePath = null;
        String requiredInstructionSet = null;
        if (scannedPackage != null && scannedPackage.applicationInfo.primaryCpuAbi != null) {
            requiredInstructionSet = VMRuntime.getInstructionSet(scannedPackage.applicationInfo.primaryCpuAbi);
        }
        PackageSetting requirer = null;
        for (PackageSetting ps : packagesForUser) {
            if (scannedPackage == null || !scannedPackage.packageName.equals(ps.name)) {
                if (ps.primaryCpuAbiString != null) {
                    String instructionSet = VMRuntime.getInstructionSet(ps.primaryCpuAbiString);
                    if (requiredInstructionSet != null && !instructionSet.equals(requiredInstructionSet)) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Instruction set mismatch, ");
                        sb.append(requirer == null ? "[caller]" : requirer);
                        sb.append(" requires ");
                        sb.append(requiredInstructionSet);
                        sb.append(" whereas ");
                        sb.append(ps);
                        sb.append(" requires ");
                        sb.append(instructionSet);
                        String errorMessage = sb.toString();
                        Slog.w(TAG, errorMessage);
                    }
                    if (requiredInstructionSet == null) {
                        requiredInstructionSet = instructionSet;
                        requirer = ps;
                    }
                }
            }
        }
        if (requiredInstructionSet != null) {
            if (requirer != null) {
                adjustedAbi = requirer.primaryCpuAbiString;
                if (scannedPackage != null) {
                    scannedPackage.applicationInfo.primaryCpuAbi = adjustedAbi;
                }
            } else {
                adjustedAbi = scannedPackage.applicationInfo.primaryCpuAbi;
            }
            for (PackageSetting ps2 : packagesForUser) {
                if (scannedPackage == null || !scannedPackage.packageName.equals(ps2.name)) {
                    if (ps2.primaryCpuAbiString == null) {
                        ps2.primaryCpuAbiString = adjustedAbi;
                        if (ps2.pkg != null && ps2.pkg.applicationInfo != null && !TextUtils.equals(adjustedAbi, ps2.pkg.applicationInfo.primaryCpuAbi)) {
                            ps2.pkg.applicationInfo.primaryCpuAbi = adjustedAbi;
                            if (changedAbiCodePath == null) {
                                changedAbiCodePath = new ArrayList<>();
                            }
                            changedAbiCodePath.add(ps2.codePathString);
                        }
                    }
                }
            }
        }
        return changedAbiCodePath;
    }

    private void setUpCustomResolverActivity(PackageParser.Package pkg) {
        synchronized (this.mPackages) {
            this.mResolverReplaced = true;
            this.mResolveActivity.applicationInfo = pkg.applicationInfo;
            this.mResolveActivity.name = this.mCustomResolverComponentName.getClassName();
            this.mResolveActivity.packageName = pkg.applicationInfo.packageName;
            this.mResolveActivity.processName = pkg.applicationInfo.packageName;
            this.mResolveActivity.launchMode = 0;
            this.mResolveActivity.flags = 288;
            this.mResolveActivity.theme = 0;
            this.mResolveActivity.exported = true;
            this.mResolveActivity.enabled = true;
            this.mResolveInfo.activityInfo = this.mResolveActivity;
            this.mResolveInfo.priority = 0;
            this.mResolveInfo.preferredOrder = 0;
            this.mResolveInfo.match = 0;
            this.mResolveComponentName = this.mCustomResolverComponentName;
            Slog.i(TAG, "Replacing default ResolverActivity with custom activity: " + this.mResolveComponentName);
        }
    }

    private void setUpInstantAppInstallerActivityLP(ActivityInfo installerActivity) {
        if (installerActivity == null) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Clear ephemeral installer activity");
            }
            this.mInstantAppInstallerActivity = null;
            return;
        }
        if (DEBUG_INSTANT) {
            Slog.d(TAG, "Set ephemeral installer activity: " + installerActivity.getComponentName());
        }
        this.mInstantAppInstallerActivity = installerActivity;
        this.mInstantAppInstallerActivity.flags |= 288;
        ActivityInfo activityInfo = this.mInstantAppInstallerActivity;
        activityInfo.exported = true;
        activityInfo.enabled = true;
        ResolveInfo resolveInfo = this.mInstantAppInstallerInfo;
        resolveInfo.activityInfo = activityInfo;
        resolveInfo.priority = 1;
        resolveInfo.preferredOrder = 1;
        resolveInfo.isDefault = true;
        resolveInfo.match = 5799936;
    }

    private static String calculateBundledApkRoot(String codePathString) {
        File codeRoot;
        File codePath = new File(codePathString);
        if (FileUtils.contains(Environment.getRootDirectory(), codePath)) {
            codeRoot = Environment.getRootDirectory();
        } else if (FileUtils.contains(Environment.getOemDirectory(), codePath)) {
            codeRoot = Environment.getOemDirectory();
        } else if (FileUtils.contains(Environment.getVendorDirectory(), codePath)) {
            codeRoot = Environment.getVendorDirectory();
        } else if (FileUtils.contains(Environment.getOdmDirectory(), codePath)) {
            codeRoot = Environment.getOdmDirectory();
        } else if (FileUtils.contains(Environment.getProductDirectory(), codePath)) {
            codeRoot = Environment.getProductDirectory();
        } else if (FileUtils.contains(Environment.getProductServicesDirectory(), codePath)) {
            codeRoot = Environment.getProductServicesDirectory();
        } else if (FileUtils.contains(Environment.getOdmDirectory(), codePath)) {
            codeRoot = Environment.getOdmDirectory();
        } else {
            try {
                File f = codePath.getCanonicalFile();
                File parent = f.getParentFile();
                while (true) {
                    File tmp = parent.getParentFile();
                    if (tmp == null) {
                        break;
                    }
                    f = parent;
                    parent = tmp;
                }
                File codeRoot2 = f;
                Slog.w(TAG, "Unrecognized code path " + codePath + " - using " + codeRoot2);
                codeRoot = codeRoot2;
            } catch (IOException e) {
                Slog.w(TAG, "Can't canonicalize code path " + codePath);
                return Environment.getRootDirectory().getPath();
            }
        }
        return codeRoot.getPath();
    }

    private static void setNativeLibraryPaths(PackageParser.Package pkg, File appLib32InstallDir) {
        ApplicationInfo info = pkg.applicationInfo;
        String codePath = pkg.codePath;
        File codeFile = new File(codePath);
        boolean bundledApp = info.isSystemApp() && !info.isUpdatedSystemApp();
        info.nativeLibraryRootDir = null;
        info.nativeLibraryRootRequiresIsa = false;
        info.nativeLibraryDir = null;
        info.secondaryNativeLibraryDir = null;
        if (PackageParser.isApkFile(codeFile)) {
            if (bundledApp) {
                String apkRoot = calculateBundledApkRoot(info.sourceDir);
                boolean is64Bit = VMRuntime.is64BitInstructionSet(InstructionSets.getPrimaryInstructionSet(info));
                String apkName = deriveCodePathName(codePath);
                String libDir = is64Bit ? "lib64" : "lib";
                info.nativeLibraryRootDir = Environment.buildPath(new File(apkRoot), new String[]{libDir, apkName}).getAbsolutePath();
                if (info.secondaryCpuAbi != null) {
                    String secondaryLibDir = is64Bit ? "lib" : "lib64";
                    info.secondaryNativeLibraryDir = Environment.buildPath(new File(apkRoot), new String[]{secondaryLibDir, apkName}).getAbsolutePath();
                }
            } else {
                info.nativeLibraryRootDir = new File(appLib32InstallDir, deriveCodePathName(codePath)).getAbsolutePath();
            }
            info.nativeLibraryRootRequiresIsa = false;
            info.nativeLibraryDir = info.nativeLibraryRootDir;
            return;
        }
        info.nativeLibraryRootDir = new File(codeFile, "lib").getAbsolutePath();
        info.nativeLibraryRootRequiresIsa = true;
        info.nativeLibraryDir = new File(info.nativeLibraryRootDir, InstructionSets.getPrimaryInstructionSet(info)).getAbsolutePath();
        if (info.secondaryCpuAbi != null) {
            info.secondaryNativeLibraryDir = new File(info.nativeLibraryRootDir, VMRuntime.getInstructionSet(info.secondaryCpuAbi)).getAbsolutePath();
        }
    }

    private static void setBundledAppAbisAndRoots(PackageParser.Package pkg, PackageSetting pkgSetting) {
        String apkName = deriveCodePathName(pkg.applicationInfo.getCodePath());
        String apkRoot = calculateBundledApkRoot(pkg.applicationInfo.sourceDir);
        setBundledAppAbi(pkg, apkRoot, apkName);
        if (pkgSetting != null) {
            pkgSetting.primaryCpuAbiString = pkg.applicationInfo.primaryCpuAbi;
            pkgSetting.secondaryCpuAbiString = pkg.applicationInfo.secondaryCpuAbi;
        }
    }

    private static void setBundledAppAbi(PackageParser.Package pkg, String apkRoot, String apkName) {
        boolean has64BitLibs;
        boolean has64BitLibs2;
        File codeFile = new File(pkg.codePath);
        if (PackageParser.isApkFile(codeFile)) {
            boolean has64BitLibs3 = new File(apkRoot, new File("lib64", apkName).getPath()).exists();
            has64BitLibs = has64BitLibs3;
            has64BitLibs2 = new File(apkRoot, new File("lib", apkName).getPath()).exists();
        } else {
            File rootDir = new File(codeFile, "lib");
            if (!ArrayUtils.isEmpty(Build.SUPPORTED_64_BIT_ABIS) && !TextUtils.isEmpty(Build.SUPPORTED_64_BIT_ABIS[0])) {
                String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_64_BIT_ABIS[0]);
                has64BitLibs = new File(rootDir, isa).exists();
            } else {
                has64BitLibs = false;
            }
            if (!ArrayUtils.isEmpty(Build.SUPPORTED_32_BIT_ABIS) && !TextUtils.isEmpty(Build.SUPPORTED_32_BIT_ABIS[0])) {
                String isa2 = VMRuntime.getInstructionSet(Build.SUPPORTED_32_BIT_ABIS[0]);
                boolean has32BitLibs = new File(rootDir, isa2).exists();
                has64BitLibs2 = has32BitLibs;
            } else {
                has64BitLibs2 = false;
            }
        }
        if (has64BitLibs && !has64BitLibs2) {
            pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
            pkg.applicationInfo.secondaryCpuAbi = null;
        } else if (has64BitLibs2 && !has64BitLibs) {
            pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            pkg.applicationInfo.secondaryCpuAbi = null;
        } else if (!has64BitLibs2 || !has64BitLibs) {
            pkg.applicationInfo.primaryCpuAbi = null;
            pkg.applicationInfo.secondaryCpuAbi = null;
        } else {
            if ((pkg.applicationInfo.flags & Integer.MIN_VALUE) == 0) {
                Slog.e(TAG, "Package " + pkg + " has multiple bundled libs, but is not multiarch.");
            }
            if (VMRuntime.is64BitInstructionSet(InstructionSets.getPreferredInstructionSet())) {
                pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
                pkg.applicationInfo.secondaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
                return;
            }
            pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            pkg.applicationInfo.secondaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
        }
    }

    private void killApplication(String pkgName, int appId, String reason) {
        killApplication(pkgName, appId, -1, reason);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void killApplication(String pkgName, int appId, int userId, String reason) {
        long token = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killApplication(pkgName, appId, userId, reason);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void removePackageLI(PackageParser.Package pkg, boolean chatty) {
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps != null) {
            removePackageLI(ps.name, chatty);
        }
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
            PackageSetting ps2 = (PackageSetting) childPkg.mExtras;
            if (ps2 != null) {
                removePackageLI(ps2.name, chatty);
            }
        }
    }

    void removePackageLI(String packageName, boolean chatty) {
        synchronized (this.mPackages) {
            PackageParser.Package removedPackage = this.mPackages.remove(packageName);
            if (removedPackage != null) {
                cleanPackageDataStructuresLILPw(removedPackage, chatty);
            }
        }
    }

    void removeInstalledPackageLI(PackageParser.Package pkg, boolean chatty) {
        synchronized (this.mPackages) {
            this.mPackages.remove(pkg.applicationInfo.packageName);
            cleanPackageDataStructuresLILPw(pkg, chatty);
            int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
            for (int i = 0; i < childCount; i++) {
                PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
                this.mPackages.remove(childPkg.applicationInfo.packageName);
                cleanPackageDataStructuresLILPw(childPkg, chatty);
            }
        }
    }

    void cleanPackageDataStructuresLILPw(PackageParser.Package pkg, boolean chatty) {
        this.mComponentResolver.removeAllComponents(pkg, chatty);
        this.mPermissionManager.removeAllPermissions(pkg, chatty);
        int instrumentationSize = pkg.instrumentation.size();
        for (int i = 0; i < instrumentationSize; i++) {
            PackageParser.Instrumentation a = (PackageParser.Instrumentation) pkg.instrumentation.get(i);
            this.mInstrumentation.remove(a.getComponentName());
        }
        if ((pkg.applicationInfo.flags & 1) != 0 && pkg.libraryNames != null) {
            int libraryNamesSize = pkg.libraryNames.size();
            for (int i2 = 0; i2 < libraryNamesSize; i2++) {
                String name = (String) pkg.libraryNames.get(i2);
                removeSharedLibraryLPw(name, 0L);
            }
        }
        if (pkg.staticSharedLibName != null) {
            removeSharedLibraryLPw(pkg.staticSharedLibName, pkg.staticSharedLibVersion);
        }
    }

    @Override // com.android.server.pm.PackageSender
    public void sendPackageBroadcast(final String action, final String pkg, final Bundle extras, final int flags, final String targetPkg, final IIntentReceiver finishedReceiver, final int[] userIds, final int[] instantUserIds) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$UtVCpL0mJ1ePNHwGgkapgCkTreo
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$sendPackageBroadcast$8$PackageManagerService(userIds, action, pkg, extras, flags, targetPkg, finishedReceiver, instantUserIds);
            }
        });
    }

    public /* synthetic */ void lambda$sendPackageBroadcast$8$PackageManagerService(int[] userIds, String action, String pkg, Bundle extras, int flags, String targetPkg, IIntentReceiver finishedReceiver, int[] instantUserIds) {
        int[] resolvedUserIds;
        try {
            IActivityManager am = ActivityManager.getService();
            if (am == null) {
                return;
            }
            if (userIds == null) {
                resolvedUserIds = am.getRunningUserIds();
            } else {
                resolvedUserIds = userIds;
            }
            doSendBroadcast(am, action, pkg, extras, flags, targetPkg, finishedReceiver, resolvedUserIds, false);
            if (instantUserIds != null && instantUserIds != EMPTY_INT_ARRAY) {
                doSendBroadcast(am, action, pkg, extras, flags, targetPkg, finishedReceiver, instantUserIds, true);
            }
        } catch (RemoteException e) {
        }
    }

    @Override // com.android.server.pm.PackageSender
    public void notifyPackageAdded(String packageName, int uid) {
        synchronized (this.mPackages) {
            if (this.mPackageListObservers.size() == 0) {
                return;
            }
            PackageManagerInternal.PackageListObserver[] observerArray = new PackageManagerInternal.PackageListObserver[this.mPackageListObservers.size()];
            PackageManagerInternal.PackageListObserver[] observers = (PackageManagerInternal.PackageListObserver[]) this.mPackageListObservers.toArray(observerArray);
            for (int i = observers.length - 1; i >= 0; i--) {
                observers[i].onPackageAdded(packageName, uid);
            }
        }
    }

    @Override // com.android.server.pm.PackageSender
    public void notifyPackageChanged(String packageName, int uid) {
        synchronized (this.mPackages) {
            if (this.mPackageListObservers.size() == 0) {
                return;
            }
            PackageManagerInternal.PackageListObserver[] observerArray = new PackageManagerInternal.PackageListObserver[this.mPackageListObservers.size()];
            PackageManagerInternal.PackageListObserver[] observers = (PackageManagerInternal.PackageListObserver[]) this.mPackageListObservers.toArray(observerArray);
            for (int i = observers.length - 1; i >= 0; i--) {
                observers[i].onPackageChanged(packageName, uid);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$static$9(ProviderInfo p1, ProviderInfo p2) {
        int v1 = p1.initOrder;
        int v2 = p2.initOrder;
        if (v1 > v2) {
            return -1;
        }
        return v1 < v2 ? 1 : 0;
    }

    @Override // com.android.server.pm.PackageSender
    public void notifyPackageRemoved(String packageName, int uid) {
        synchronized (this.mPackages) {
            if (this.mPackageListObservers.size() == 0) {
                return;
            }
            PackageManagerInternal.PackageListObserver[] observerArray = new PackageManagerInternal.PackageListObserver[this.mPackageListObservers.size()];
            PackageManagerInternal.PackageListObserver[] observers = (PackageManagerInternal.PackageListObserver[]) this.mPackageListObservers.toArray(observerArray);
            for (int i = observers.length - 1; i >= 0; i--) {
                observers[i].onPackageRemoved(packageName, uid);
            }
        }
    }

    private void doSendBroadcast(IActivityManager am, String action, String pkg, Bundle extras, int flags, String targetPkg, IIntentReceiver finishedReceiver, int[] userIds, boolean isInstantApp) throws RemoteException {
        Uri uri;
        for (int id : userIds) {
            if (pkg != null) {
                uri = Uri.fromParts("package", pkg, null);
            } else {
                uri = null;
            }
            Intent intent = new Intent(action, uri);
            String[] requiredPermissions = isInstantApp ? INSTANT_APP_BROADCAST_PERMISSION : null;
            if (extras != null) {
                intent.putExtras(extras);
            }
            if (targetPkg != null) {
                intent.setPackage(targetPkg);
            }
            int uid = intent.getIntExtra("android.intent.extra.UID", -1);
            if (uid > 0 && UserHandle.getUserId(uid) != id) {
                intent.putExtra("android.intent.extra.UID", UserHandle.getUid(id, UserHandle.getAppId(uid)));
            }
            intent.putExtra("android.intent.extra.user_handle", id);
            intent.addFlags(flags | 67108864);
            am.broadcastIntent((IApplicationThread) null, intent, (String) null, finishedReceiver, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, finishedReceiver != null, false, id);
        }
    }

    private boolean isExternalMediaAvailable() {
        return this.mMediaMounted || Environment.isExternalStorageEmulated();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int fixUpInstallReason(String installerPackageName, int installerUid, int installReason) {
        if (checkUidPermission("android.permission.INSTALL_PACKAGES", installerUid) == 0) {
            return installReason;
        }
        String ownerPackage = this.mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(UserHandle.getUserId(installerUid));
        if (ownerPackage != null && ownerPackage.equals(installerPackageName)) {
            return 1;
        }
        if (installReason == 1) {
            return 0;
        }
        return installReason;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void installStage(ActiveInstallSession activeInstallSession) {
        if (DEBUG_INSTANT && (activeInstallSession.getSessionParams().installFlags & 2048) != 0) {
            Slog.d(TAG, "Ephemeral install of " + activeInstallSession.getPackageName());
        }
        Message msg = this.mHandler.obtainMessage(5);
        InstallParams params = new InstallParams(activeInstallSession);
        params.setTraceMethod("installStage").setTraceCookie(System.identityHashCode(params));
        msg.obj = params;
        Trace.asyncTraceBegin(262144L, "installStage", System.identityHashCode(msg.obj));
        Trace.asyncTraceBegin(262144L, "queueInstall", System.identityHashCode(msg.obj));
        this.mHandler.sendMessage(msg);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void installStage(List<ActiveInstallSession> children) throws PackageManagerException {
        Message msg = this.mHandler.obtainMessage(5);
        MultiPackageInstallParams params = new MultiPackageInstallParams(UserHandle.ALL, children);
        params.setTraceMethod("installStageMultiPackage").setTraceCookie(System.identityHashCode(params));
        msg.obj = params;
        Trace.asyncTraceBegin(262144L, "installStageMultiPackage", System.identityHashCode(msg.obj));
        Trace.asyncTraceBegin(262144L, "queueInstall", System.identityHashCode(msg.obj));
        this.mHandler.sendMessage(msg);
    }

    private void sendPackageAddedForUser(String packageName, PackageSetting pkgSetting, int userId) {
        boolean isSystem = isSystemApp(pkgSetting) || isUpdatedSystemApp(pkgSetting);
        boolean isInstantApp = pkgSetting.getInstantApp(userId);
        int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[]{userId};
        int[] instantUserIds = isInstantApp ? new int[]{userId} : EMPTY_INT_ARRAY;
        sendPackageAddedForNewUsers(packageName, isSystem, false, pkgSetting.appId, userIds, instantUserIds);
        PackageInstaller.SessionInfo info = new PackageInstaller.SessionInfo();
        info.installReason = pkgSetting.getInstallReason(userId);
        info.appPackageName = packageName;
        sendSessionCommitBroadcast(info, userId);
    }

    @Override // com.android.server.pm.PackageSender
    public void sendPackageAddedForNewUsers(final String packageName, boolean sendBootCompleted, final boolean includeStopped, int appId, final int[] userIds, int[] instantUserIds) {
        if (ArrayUtils.isEmpty(userIds) && ArrayUtils.isEmpty(instantUserIds)) {
            return;
        }
        Bundle extras = new Bundle(1);
        int uid = UserHandle.getUid(ArrayUtils.isEmpty(userIds) ? instantUserIds[0] : userIds[0], appId);
        extras.putInt("android.intent.extra.UID", uid);
        sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName, extras, 0, null, null, userIds, instantUserIds);
        if (sendBootCompleted && !ArrayUtils.isEmpty(userIds)) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$dNpDOaNQuARBbRnLTCuImaANwU4
                @Override // java.lang.Runnable
                public final void run() {
                    PackageManagerService.this.lambda$sendPackageAddedForNewUsers$10$PackageManagerService(userIds, packageName, includeStopped);
                }
            });
        }
    }

    public /* synthetic */ void lambda$sendPackageAddedForNewUsers$10$PackageManagerService(int[] userIds, String packageName, boolean includeStopped) {
        for (int userId : userIds) {
            sendBootCompletedBroadcastToSystemApp(packageName, includeStopped, userId);
        }
    }

    private void sendBootCompletedBroadcastToSystemApp(String packageName, boolean includeStopped, int userId) {
        if (!this.mUserManagerInternal.isUserRunning(userId)) {
            return;
        }
        IActivityManager am = ActivityManager.getService();
        try {
            Intent lockedBcIntent = new Intent("android.intent.action.LOCKED_BOOT_COMPLETED").setPackage(packageName);
            if (includeStopped) {
                lockedBcIntent.addFlags(32);
            }
            String[] requiredPermissions = {"android.permission.RECEIVE_BOOT_COMPLETED"};
            try {
                am.broadcastIntent((IApplicationThread) null, lockedBcIntent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, false, false, userId);
            } catch (RemoteException e) {
                e = e;
            }
            try {
                if (this.mUserManagerInternal.isUserUnlockingOrUnlocked(userId)) {
                    Intent bcIntent = new Intent("android.intent.action.BOOT_COMPLETED").setPackage(packageName);
                    if (includeStopped) {
                        bcIntent.addFlags(32);
                    }
                    am.broadcastIntent((IApplicationThread) null, bcIntent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, false, false, userId);
                }
            } catch (RemoteException e2) {
                e = e2;
                throw e.rethrowFromSystemServer();
            }
        } catch (RemoteException e3) {
            e = e3;
        }
    }

    public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USERS", null);
        int callingUid = Binder.getCallingUid();
        PermissionManagerServiceInternal permissionManagerServiceInternal = this.mPermissionManager;
        permissionManagerServiceInternal.enforceCrossUserPermission(callingUid, userId, true, true, "setApplicationHiddenSetting for user " + userId);
        if (hidden && isPackageDeviceAdmin(packageName, userId)) {
            Slog.w(TAG, "Not hiding package " + packageName + ": has active device admin");
            return false;
        }
        long callingId = Binder.clearCallingIdentity();
        boolean sendAdded = false;
        boolean sendRemoved = false;
        try {
            synchronized (this.mPackages) {
                PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName);
                if (pkgSetting == null) {
                    return false;
                }
                if (filterAppAccessLPr(pkgSetting, callingUid, userId)) {
                    return false;
                }
                if (PLATFORM_PACKAGE_NAME.equals(packageName)) {
                    Slog.w(TAG, "Cannot hide package: android");
                    return false;
                }
                PackageParser.Package pkg = this.mPackages.get(packageName);
                if (pkg != null && pkg.staticSharedLibName != null) {
                    Slog.w(TAG, "Cannot hide package: " + packageName + " providing static shared library: " + pkg.staticSharedLibName);
                    return false;
                } else if (hidden && !UserHandle.isSameApp(callingUid, pkgSetting.appId) && this.mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                    Slog.w(TAG, "Not hiding protected package: " + packageName);
                    return false;
                } else {
                    if (pkgSetting.getHidden(userId) != hidden) {
                        pkgSetting.setHidden(hidden, userId);
                        this.mSettings.writePackageRestrictionsLPr(userId);
                        if (hidden) {
                            sendRemoved = true;
                        } else {
                            sendAdded = true;
                        }
                    }
                    if (sendAdded) {
                        sendPackageAddedForUser(packageName, pkgSetting, userId);
                        return true;
                    } else if (sendRemoved) {
                        killApplication(packageName, UserHandle.getUid(userId, pkgSetting.appId), "hiding pkg");
                        sendApplicationHiddenForUser(packageName, pkgSetting, userId);
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void setSystemAppHiddenUntilInstalled(String packageName, boolean hidden) {
        enforceSystemOrPhoneCaller("setSystemAppHiddenUntilInstalled");
        synchronized (this.mPackages) {
            PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName);
            if (pkgSetting != null && pkgSetting.isSystem()) {
                PackageParser.Package pkg = pkgSetting.pkg;
                if (pkg != null && pkg.applicationInfo != null) {
                    pkg.applicationInfo.hiddenUntilInstalled = hidden;
                }
                PackageSetting disabledPs = this.mSettings.getDisabledSystemPkgLPr(packageName);
                if (disabledPs == null) {
                    return;
                }
                PackageParser.Package pkg2 = disabledPs.pkg;
                if (pkg2 != null && pkg2.applicationInfo != null) {
                    pkg2.applicationInfo.hiddenUntilInstalled = hidden;
                }
            }
        }
    }

    public boolean setSystemAppInstallState(String packageName, boolean installed, int userId) {
        enforceSystemOrPhoneCaller("setSystemAppInstallState");
        synchronized (this.mPackages) {
            PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName);
            if (pkgSetting != null && pkgSetting.isSystem()) {
                if (pkgSetting.getInstalled(userId) == installed) {
                    return false;
                }
                long callingId = Binder.clearCallingIdentity();
                try {
                    if (installed) {
                        installExistingPackageAsUser(packageName, userId, 4194304, 3, null);
                        return true;
                    }
                    deletePackageVersioned(new VersionedPackage(packageName, -1), new PackageManager.LegacyPackageDeleteObserver((IPackageDeleteObserver) null).getBinder(), userId, 4);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
            return false;
        }
    }

    private void sendApplicationHiddenForUser(String packageName, PackageSetting pkgSetting, int userId) {
        PackageRemovedInfo info = new PackageRemovedInfo(this);
        info.removedPackage = packageName;
        info.installerPackageName = pkgSetting.installerPackageName;
        info.removedUsers = new int[]{userId};
        info.broadcastUsers = new int[]{userId};
        info.uid = UserHandle.getUid(userId, pkgSetting.appId);
        info.sendPackageRemovedBroadcasts(true);
    }

    private void sendDistractingPackagesChanged(String[] pkgList, int[] uidList, int userId, int distractionFlags) {
        Bundle extras = new Bundle(3);
        extras.putStringArray("android.intent.extra.changed_package_list", pkgList);
        extras.putIntArray("android.intent.extra.changed_uid_list", uidList);
        extras.putInt("android.intent.extra.distraction_restrictions", distractionFlags);
        sendPackageBroadcast("android.intent.action.DISTRACTING_PACKAGES_CHANGED", null, extras, 1073741824, null, null, new int[]{userId}, null);
    }

    private void sendPackagesSuspendedForUser(String[] pkgList, int[] uidList, int userId, boolean suspended, PersistableBundle launcherExtras) {
        Bundle extras = new Bundle(3);
        extras.putStringArray("android.intent.extra.changed_package_list", pkgList);
        extras.putIntArray("android.intent.extra.changed_uid_list", uidList);
        if (launcherExtras != null) {
            extras.putBundle("android.intent.extra.LAUNCHER_EXTRAS", new Bundle(launcherExtras.deepCopy()));
        }
        sendPackageBroadcast(suspended ? "android.intent.action.PACKAGES_SUSPENDED" : "android.intent.action.PACKAGES_UNSUSPENDED", null, extras, 1073741824, null, null, new int[]{userId}, null);
    }

    public boolean getApplicationHiddenSettingAsUser(String packageName, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USERS", null);
        int callingUid = Binder.getCallingUid();
        PermissionManagerServiceInternal permissionManagerServiceInternal = this.mPermissionManager;
        permissionManagerServiceInternal.enforceCrossUserPermission(callingUid, userId, true, false, "getApplicationHidden for user " + userId);
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mPackages) {
                PackageSetting ps = this.mSettings.mPackages.get(packageName);
                if (ps == null) {
                    return true;
                }
                if (filterAppAccessLPr(ps, callingUid, userId)) {
                    return true;
                }
                return ps.getHidden(userId);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public int installExistingPackageAsUser(String packageName, int userId, int installFlags, int installReason, List<String> whiteListedPermissions) {
        return installExistingPackageAsUser(packageName, userId, installFlags, installReason, whiteListedPermissions, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int installExistingPackageAsUser(String packageName, int userId, int installFlags, int installReason, List<String> whiteListedPermissions, final IntentSender intentSender) {
        int callingUid = Binder.getCallingUid();
        if (this.mContext.checkCallingOrSelfPermission("android.permission.INSTALL_PACKAGES") != 0 && this.mContext.checkCallingOrSelfPermission("com.android.permission.INSTALL_EXISTING_PACKAGES") != 0) {
            throw new SecurityException("Neither user " + callingUid + " nor current process has android.permission.INSTALL_PACKAGES.");
        }
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, true, "installExistingPackage for user " + userId);
        if (isUserRestricted(userId, "no_install_apps")) {
            return -111;
        }
        long callingId = Binder.clearCallingIdentity();
        boolean installed = false;
        boolean instantApp = (installFlags & 2048) != 0;
        boolean fullApp = (installFlags & 16384) != 0;
        try {
        } catch (Throwable th) {
            th = th;
        }
        try {
            try {
                synchronized (this.mPackages) {
                    try {
                        PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName);
                        if (pkgSetting == null) {
                            Binder.restoreCallingIdentity(callingId);
                            return -3;
                        }
                        if (!canViewInstantApps(callingUid, UserHandle.getUserId(callingUid))) {
                            int[] userIds = sUserManager.getUserIds();
                            int length = userIds.length;
                            boolean installAllowed = false;
                            int i = 0;
                            while (i < length) {
                                int checkUserId = userIds[i];
                                int[] iArr = userIds;
                                installAllowed = !pkgSetting.getInstantApp(checkUserId);
                                if (installAllowed) {
                                    break;
                                }
                                i++;
                                userIds = iArr;
                            }
                            if (!installAllowed) {
                                Binder.restoreCallingIdentity(callingId);
                                return -3;
                            }
                        }
                        if (!pkgSetting.getInstalled(userId)) {
                            pkgSetting.setInstalled(true, userId);
                            pkgSetting.setHidden(false, userId);
                            pkgSetting.setInstallReason(installReason, userId);
                            this.mSettings.writePackageRestrictionsLPr(userId);
                            this.mSettings.writeKernelMappingLPr(pkgSetting);
                            installed = true;
                        } else if (fullApp && pkgSetting.getInstantApp(userId)) {
                            installed = true;
                        }
                        try {
                            setInstantAppForUser(pkgSetting, userId, instantApp, fullApp);
                            if (installed) {
                                try {
                                    setWhitelistedRestrictedPermissions(packageName, ((4194304 & installFlags) == 0 || pkgSetting.pkg == null) ? whiteListedPermissions : pkgSetting.pkg.requestedPermissions, 2, userId);
                                    if (pkgSetting.pkg != null) {
                                        synchronized (this.mInstallLock) {
                                            prepareAppDataAfterInstallLIF(pkgSetting.pkg);
                                        }
                                    }
                                    sendPackageAddedForUser(packageName, pkgSetting, userId);
                                    synchronized (this.mPackages) {
                                        updateSequenceNumberLP(pkgSetting, new int[]{userId});
                                    }
                                    final PackageInstalledInfo res = createPackageInstalledInfo(1);
                                    res.pkg = pkgSetting.pkg;
                                    res.newUsers = new int[]{userId};
                                    PostInstallData postInstallData = intentSender == null ? null : new PostInstallData(null, res, new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$5bfG3NWHFMyAy9KctvBO9i2pciI
                                        @Override // java.lang.Runnable
                                        public final void run() {
                                            PackageManagerService.this.lambda$installExistingPackageAsUser$11$PackageManagerService(res, intentSender);
                                        }
                                    });
                                    restoreAndPostInstall(userId, res, postInstallData);
                                } catch (Throwable th2) {
                                    th = th2;
                                    Binder.restoreCallingIdentity(callingId);
                                    throw th;
                                }
                            }
                            Binder.restoreCallingIdentity(callingId);
                            return 1;
                        } catch (Throwable th3) {
                            th = th3;
                            throw th;
                        }
                    } catch (Throwable th4) {
                        th = th4;
                    }
                }
            } catch (Throwable th5) {
                th = th5;
            }
        } catch (Throwable th6) {
            th = th6;
            Binder.restoreCallingIdentity(callingId);
            throw th;
        }
    }

    public /* synthetic */ void lambda$installExistingPackageAsUser$11$PackageManagerService(PackageInstalledInfo res, IntentSender intentSender) {
        onRestoreComplete(res.returnCode, this.mContext, intentSender);
    }

    static void onRestoreComplete(int returnCode, Context context, IntentSender target) {
        Intent fillIn = new Intent();
        fillIn.putExtra("android.content.pm.extra.STATUS", PackageManager.installStatusToPublicStatus(returnCode));
        try {
            target.sendIntent(context, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
        }
    }

    static void setInstantAppForUser(PackageSetting pkgSetting, int userId, boolean instantApp, boolean fullApp) {
        int[] userIds;
        if (!instantApp && !fullApp) {
            return;
        }
        if (userId != -1) {
            if (instantApp && !pkgSetting.getInstantApp(userId)) {
                pkgSetting.setInstantApp(true, userId);
                return;
            } else if (fullApp && pkgSetting.getInstantApp(userId)) {
                pkgSetting.setInstantApp(false, userId);
                return;
            } else {
                return;
            }
        }
        for (int currentUserId : sUserManager.getUserIds()) {
            if (instantApp && !pkgSetting.getInstantApp(currentUserId)) {
                pkgSetting.setInstantApp(true, currentUserId);
            } else if (fullApp && pkgSetting.getInstantApp(currentUserId)) {
                pkgSetting.setInstantApp(false, currentUserId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUserRestricted(int userId, String restrictionKey) {
        Bundle restrictions = sUserManager.getUserRestrictions(userId);
        if (!restrictions.getBoolean(restrictionKey, false)) {
            return false;
        }
        Log.w(TAG, "User is restricted: " + restrictionKey);
        return true;
    }

    public String[] setDistractingPackageRestrictionsAsUser(String[] packageNames, int restrictionFlags, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.SUSPEND_APPS", "setDistractingPackageRestrictionsAsUser");
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != 1000 && UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Calling uid " + callingUid + " cannot call for user " + userId);
        }
        Preconditions.checkNotNull(packageNames, "packageNames cannot be null");
        List<String> changedPackagesList = new ArrayList<>(packageNames.length);
        IntArray changedUids = new IntArray(packageNames.length);
        List<String> unactionedPackages = new ArrayList<>(packageNames.length);
        boolean[] canRestrict = restrictionFlags != 0 ? canSuspendPackageForUserInternal(packageNames, userId) : null;
        for (int i = 0; i < packageNames.length; i++) {
            String packageName = packageNames[i];
            synchronized (this.mPackages) {
                PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName);
                if (pkgSetting != null && !filterAppAccessLPr(pkgSetting, callingUid, userId)) {
                    if (canRestrict != null && !canRestrict[i]) {
                        unactionedPackages.add(packageName);
                    } else {
                        synchronized (this.mPackages) {
                            int oldDistractionFlags = pkgSetting.getDistractionFlags(userId);
                            if (restrictionFlags != oldDistractionFlags) {
                                pkgSetting.setDistractionFlags(restrictionFlags, userId);
                                changedPackagesList.add(packageName);
                                changedUids.add(UserHandle.getUid(userId, pkgSetting.appId));
                            }
                        }
                    }
                }
                Slog.w(TAG, "Could not find package setting for package: " + packageName + ". Skipping...");
                unactionedPackages.add(packageName);
            }
        }
        if (!changedPackagesList.isEmpty()) {
            String[] changedPackages = (String[]) changedPackagesList.toArray(new String[changedPackagesList.size()]);
            sendDistractingPackagesChanged(changedPackages, changedUids.toArray(), userId, restrictionFlags);
            synchronized (this.mPackages) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
        return (String[]) unactionedPackages.toArray(new String[0]);
    }

    private void enforceCanSetPackagesSuspendedAsUser(String callingPackage, int callingUid, int userId, String callingMethod) {
        boolean allowedPackageUid;
        if (callingUid == 0 || callingUid == 1000) {
            return;
        }
        String ownerPackage = this.mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(userId);
        boolean allowedShell = false;
        if (ownerPackage != null) {
            int ownerUid = getPackageUid(ownerPackage, 0, userId);
            if (ownerUid == callingUid) {
                return;
            }
            throw new UnsupportedOperationException("Cannot suspend/unsuspend packages. User " + userId + " has an active DO or PO");
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.SUSPEND_APPS", callingMethod);
        int packageUid = getPackageUid(callingPackage, 0, userId);
        if (packageUid != callingUid) {
            allowedPackageUid = false;
        } else {
            allowedPackageUid = true;
        }
        if (callingUid == SHELL_UID && UserHandle.isSameApp(packageUid, callingUid)) {
            allowedShell = true;
        }
        if (!allowedShell && !allowedPackageUid) {
            throw new SecurityException("Calling package " + callingPackage + " in user " + userId + " does not belong to calling uid " + callingUid);
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:66:? -> B:48:0x0112). Please submit an issue!!! */
    public String[] setPackagesSuspendedAsUser(String[] packageNames, boolean suspended, PersistableBundle appExtras, PersistableBundle launcherExtras, SuspendDialogInfo dialogInfo, String callingPackage, int userId) {
        boolean[] zArr;
        PackageSetting pkgSetting;
        int callingUid;
        String packageName;
        int i;
        List<String> unactionedPackages;
        String[] strArr = packageNames;
        String str = callingPackage;
        int callingUid2 = Binder.getCallingUid();
        enforceCanSetPackagesSuspendedAsUser(str, callingUid2, userId, "setPackagesSuspendedAsUser");
        if (ArrayUtils.isEmpty(packageNames)) {
            return strArr;
        }
        List<String> changedPackagesList = new ArrayList<>(strArr.length);
        IntArray changedUids = new IntArray(strArr.length);
        List<String> unactionedPackages2 = new ArrayList<>(strArr.length);
        if (suspended) {
            zArr = canSuspendPackageForUserInternal(strArr, userId);
        } else {
            zArr = null;
        }
        boolean[] canSuspend = zArr;
        int i2 = 0;
        while (i2 < strArr.length) {
            String packageName2 = strArr[i2];
            if (str.equals(packageName2)) {
                StringBuilder sb = new StringBuilder();
                sb.append("Calling package: ");
                sb.append(str);
                sb.append(" trying to ");
                sb.append(suspended ? "" : "un");
                sb.append("suspend itself. Ignoring");
                Slog.w(TAG, sb.toString());
                unactionedPackages2.add(packageName2);
                callingUid = callingUid2;
                i = i2;
                unactionedPackages = unactionedPackages2;
            } else {
                synchronized (this.mPackages) {
                    try {
                        pkgSetting = this.mSettings.mPackages.get(packageName2);
                        if (pkgSetting != null) {
                            if (filterAppAccessLPr(pkgSetting, callingUid2, userId)) {
                                callingUid = callingUid2;
                                packageName = packageName2;
                                i = i2;
                                unactionedPackages = unactionedPackages2;
                            }
                        } else {
                            callingUid = callingUid2;
                            packageName = packageName2;
                            i = i2;
                            unactionedPackages = unactionedPackages2;
                        }
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                    try {
                        Slog.w(TAG, "Could not find package setting for package: " + packageName + ". Skipping suspending/un-suspending.");
                        unactionedPackages.add(packageName);
                    } catch (Throwable th2) {
                        th = th2;
                        throw th;
                    }
                }
                if (canSuspend != null && !canSuspend[i2]) {
                    unactionedPackages2.add(packageName2);
                    callingUid = callingUid2;
                    i = i2;
                    unactionedPackages = unactionedPackages2;
                } else {
                    synchronized (this.mPackages) {
                        callingUid = callingUid2;
                        i = i2;
                        unactionedPackages = unactionedPackages2;
                        pkgSetting.setSuspended(suspended, callingPackage, dialogInfo, appExtras, launcherExtras, userId);
                    }
                    changedPackagesList.add(packageName2);
                    changedUids.add(UserHandle.getUid(userId, pkgSetting.appId));
                }
            }
            i2 = i + 1;
            str = callingPackage;
            unactionedPackages2 = unactionedPackages;
            callingUid2 = callingUid;
            strArr = packageNames;
        }
        List<String> unactionedPackages3 = unactionedPackages2;
        if (!changedPackagesList.isEmpty()) {
            String[] changedPackages = (String[]) changedPackagesList.toArray(new String[changedPackagesList.size()]);
            sendPackagesSuspendedForUser(changedPackages, changedUids.toArray(), userId, suspended, launcherExtras);
            sendMyPackageSuspendedOrUnsuspended(changedPackages, suspended, appExtras, userId);
            synchronized (this.mPackages) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
        return (String[]) unactionedPackages3.toArray(new String[unactionedPackages3.size()]);
    }

    public PersistableBundle getSuspendedPackageAppExtras(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (getPackageUid(packageName, 0, userId) != callingUid) {
            throw new SecurityException("Calling package " + packageName + " does not belong to calling uid " + callingUid);
        }
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps == null || filterAppAccessLPr(ps, callingUid, userId)) {
                throw new IllegalArgumentException("Unknown target package: " + packageName);
            }
            PackageUserState packageUserState = ps.readUserState(userId);
            if (packageUserState.suspended) {
                return packageUserState.suspendedAppExtras;
            }
            return null;
        }
    }

    private void sendMyPackageSuspendedOrUnsuspended(final String[] affectedPackages, final boolean suspended, PersistableBundle appExtras, final int userId) {
        String action;
        final Bundle intentExtras = new Bundle();
        if (suspended) {
            if (appExtras != null) {
                Bundle bundledAppExtras = new Bundle(appExtras.deepCopy());
                intentExtras.putBundle("android.intent.extra.SUSPENDED_PACKAGE_EXTRAS", bundledAppExtras);
            }
            action = "android.intent.action.MY_PACKAGE_SUSPENDED";
        } else {
            action = "android.intent.action.MY_PACKAGE_UNSUSPENDED";
        }
        final String str = action;
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$lmWx5KdKJSi601pB8nl6c_pFRXI
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$sendMyPackageSuspendedOrUnsuspended$12$PackageManagerService(suspended, userId, affectedPackages, str, intentExtras);
            }
        });
    }

    public /* synthetic */ void lambda$sendMyPackageSuspendedOrUnsuspended$12$PackageManagerService(boolean suspended, int userId, String[] affectedPackages, String action, Bundle intentExtras) {
        try {
            IActivityManager am = ActivityManager.getService();
            if (am == null) {
                StringBuilder sb = new StringBuilder();
                sb.append("IActivityManager null. Cannot send MY_PACKAGE_ ");
                sb.append(suspended ? "" : "UN");
                sb.append("SUSPENDED broadcasts");
                Slog.wtf(TAG, sb.toString());
                return;
            }
            int[] targetUserIds = {userId};
            for (String packageName : affectedPackages) {
                doSendBroadcast(am, action, null, intentExtras, 16777216, packageName, null, targetUserIds, false);
            }
        } catch (RemoteException e) {
        }
    }

    public boolean isPackageSuspendedForUser(String packageName, int userId) {
        boolean suspended;
        int callingUid = Binder.getCallingUid();
        PermissionManagerServiceInternal permissionManagerServiceInternal = this.mPermissionManager;
        permissionManagerServiceInternal.enforceCrossUserPermission(callingUid, userId, true, false, "isPackageSuspendedForUser for user " + userId);
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps == null || filterAppAccessLPr(ps, callingUid, userId)) {
                throw new IllegalArgumentException("Unknown target package: " + packageName);
            }
            suspended = ps.getSuspended(userId);
        }
        return suspended;
    }

    void unsuspendForSuspendingPackage(final String packageName, int affectedUser) {
        int[] userIds = affectedUser == -1 ? sUserManager.getUserIds() : new int[]{affectedUser};
        for (int userId : userIds) {
            Objects.requireNonNull(packageName);
            unsuspendForSuspendingPackages(new Predicate() { // from class: com.android.server.pm.-$$Lambda$S4BXTl5Ly3EHhXAReFCtlz2B8eo
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return packageName.equals((String) obj);
                }
            }, userId);
        }
    }

    void unsuspendForNonSystemSuspendingPackages(ArraySet<Integer> userIds) {
        int sz = userIds.size();
        for (int i = 0; i < sz; i++) {
            unsuspendForSuspendingPackages(new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$8F_cRTr5jDNmLElQPCCP6cIQBH4
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return PackageManagerService.lambda$unsuspendForNonSystemSuspendingPackages$13((String) obj);
                }
            }, userIds.valueAt(i).intValue());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$unsuspendForNonSystemSuspendingPackages$13(String suspendingPackage) {
        return !PLATFORM_PACKAGE_NAME.equals(suspendingPackage);
    }

    private void unsuspendForSuspendingPackages(Predicate<String> packagePredicate, int userId) {
        List<String> affectedPackages = new ArrayList<>();
        IntArray affectedUids = new IntArray();
        synchronized (this.mPackages) {
            for (PackageSetting ps : this.mSettings.mPackages.values()) {
                PackageUserState pus = ps.readUserState(userId);
                if (pus.suspended && packagePredicate.test(pus.suspendingPackage)) {
                    ps.setSuspended(false, null, null, null, null, userId);
                    affectedPackages.add(ps.name);
                    affectedUids.add(UserHandle.getUid(userId, ps.getAppId()));
                }
            }
        }
        if (!affectedPackages.isEmpty()) {
            String[] packageArray = (String[]) affectedPackages.toArray(new String[affectedPackages.size()]);
            sendMyPackageSuspendedOrUnsuspended(packageArray, false, null, userId);
            sendPackagesSuspendedForUser(packageArray, affectedUids.toArray(), userId, false, null);
            this.mSettings.writePackageRestrictionsLPr(userId);
        }
    }

    public String[] getUnsuspendablePackagesForUser(String[] packageNames, int userId) {
        Preconditions.checkNotNull("packageNames cannot be null", packageNames);
        this.mContext.enforceCallingOrSelfPermission("android.permission.SUSPEND_APPS", "getUnsuspendablePackagesForUser");
        int callingUid = Binder.getCallingUid();
        if (UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Calling uid " + callingUid + " cannot query getUnsuspendablePackagesForUser for user " + userId);
        }
        ArraySet<String> unactionablePackages = new ArraySet<>();
        boolean[] canSuspend = canSuspendPackageForUserInternal(packageNames, userId);
        for (int i = 0; i < packageNames.length; i++) {
            if (!canSuspend[i]) {
                unactionablePackages.add(packageNames[i]);
            }
        }
        int i2 = unactionablePackages.size();
        return (String[]) unactionablePackages.toArray(new String[i2]);
    }

    private boolean[] canSuspendPackageForUserInternal(String[] packageNames, int userId) {
        boolean[] canSuspend = new boolean[packageNames.length];
        long callingId = Binder.clearCallingIdentity();
        try {
            String activeLauncherPackageName = getActiveLauncherPackageName(userId);
            String dialerPackageName = getDefaultDialerPackageName(userId);
            for (int i = 0; i < packageNames.length; i++) {
                canSuspend[i] = false;
                String packageName = packageNames[i];
                if (isPackageDeviceAdmin(packageName, userId)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": has an active device admin");
                } else if (packageName.equals(activeLauncherPackageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": contains the active launcher");
                } else if (packageName.equals(this.mRequiredInstallerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": required for package installation");
                } else if (packageName.equals(this.mRequiredUninstallerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": required for package uninstallation");
                } else if (packageName.equals(this.mRequiredVerifierPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": required for package verification");
                } else if (packageName.equals(dialerPackageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": is the default dialer");
                } else if (packageName.equals(this.mRequiredPermissionControllerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": required for permissions management");
                } else {
                    synchronized (this.mPackages) {
                        if (this.mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": protected package");
                        } else {
                            PackageParser.Package pkg = this.mPackages.get(packageName);
                            if (pkg != null && pkg.applicationInfo.isStaticSharedLibrary()) {
                                Slog.w(TAG, "Cannot suspend package: " + packageName + " providing static shared library: " + pkg.staticSharedLibName);
                            } else if (PLATFORM_PACKAGE_NAME.equals(packageName)) {
                                Slog.w(TAG, "Cannot suspend the platform package: " + packageName);
                            } else {
                                canSuspend[i] = true;
                            }
                        }
                    }
                }
            }
            return canSuspend;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private String getActiveLauncherPackageName(int userId) {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory("android.intent.category.HOME");
        ResolveInfo resolveInfo = resolveIntent(intent, intent.resolveTypeIfNeeded(this.mContext.getContentResolver()), 65536, userId);
        if (resolveInfo == null) {
            return null;
        }
        return resolveInfo.activityInfo.packageName;
    }

    private String getDefaultDialerPackageName(int userId) {
        PackageManagerInternal.DefaultDialerProvider provider;
        synchronized (this.mPackages) {
            provider = this.mDefaultDialerProvider;
        }
        if (provider == null) {
            Slog.e(TAG, "mDefaultDialerProvider is null");
            return null;
        }
        return provider.getDefaultDialer(userId);
    }

    public void verifyPendingInstall(int id, int verificationCode) throws RemoteException {
        this.mContext.enforceCallingOrSelfPermission("android.permission.PACKAGE_VERIFICATION_AGENT", "Only package verification agents can verify applications");
        Message msg = this.mHandler.obtainMessage(15);
        PackageVerificationResponse response = new PackageVerificationResponse(verificationCode, Binder.getCallingUid());
        msg.arg1 = id;
        msg.obj = response;
        this.mHandler.sendMessage(msg);
    }

    /* JADX WARN: Code restructure failed: missing block: B:15:0x0039, code lost:
        if (r0.timeoutExtended() != false) goto L18;
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x003b, code lost:
        r0.extendTimeout();
        r2 = r4.mHandler.obtainMessage(15);
        r2.arg1 = r5;
        r2.obj = r1;
        r4.mHandler.sendMessageDelayed(r2, r7);
     */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x004f, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:19:?, code lost:
        return;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void extendVerificationTimeout(int r5, int r6, long r7) {
        /*
            r4 = this;
            android.content.Context r0 = r4.mContext
            java.lang.String r1 = "android.permission.PACKAGE_VERIFICATION_AGENT"
            java.lang.String r2 = "Only package verification agents can extend verification timeouts"
            r0.enforceCallingOrSelfPermission(r1, r2)
            android.util.SparseArray<com.android.server.pm.PackageVerificationState> r0 = r4.mPendingVerification
            java.lang.Object r0 = r0.get(r5)
            com.android.server.pm.PackageVerificationState r0 = (com.android.server.pm.PackageVerificationState) r0
            com.android.server.pm.PackageVerificationResponse r1 = new com.android.server.pm.PackageVerificationResponse
            int r2 = android.os.Binder.getCallingUid()
            r1.<init>(r6, r2)
            r2 = 3600000(0x36ee80, double:1.7786363E-317)
            int r2 = (r7 > r2 ? 1 : (r7 == r2 ? 0 : -1))
            if (r2 <= 0) goto L24
            r7 = 3600000(0x36ee80, double:1.7786363E-317)
        L24:
            r2 = 0
            int r2 = (r7 > r2 ? 1 : (r7 == r2 ? 0 : -1))
            if (r2 >= 0) goto L2c
            r7 = 0
        L2c:
            r2 = 1
            if (r6 == r2) goto L33
            r2 = -1
            if (r6 == r2) goto L33
            r6 = -1
        L33:
            if (r0 == 0) goto L4f
            boolean r2 = r0.timeoutExtended()
            if (r2 != 0) goto L4f
            r0.extendTimeout()
            com.android.server.pm.PackageManagerService$PackageHandler r2 = r4.mHandler
            r3 = 15
            android.os.Message r2 = r2.obtainMessage(r3)
            r2.arg1 = r5
            r2.obj = r1
            com.android.server.pm.PackageManagerService$PackageHandler r3 = r4.mHandler
            r3.sendMessageDelayed(r2, r7)
        L4f:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.extendVerificationTimeout(int, int, long):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void broadcastPackageVerified(int verificationId, Uri packageUri, int verificationCode, UserHandle user) {
        Intent intent = new Intent("android.intent.action.PACKAGE_VERIFIED");
        intent.setDataAndType(packageUri, PACKAGE_MIME_TYPE);
        intent.addFlags(1);
        intent.putExtra("android.content.pm.extra.VERIFICATION_ID", verificationId);
        intent.putExtra("android.content.pm.extra.VERIFICATION_RESULT", verificationCode);
        this.mContext.sendBroadcastAsUser(intent, user, "android.permission.PACKAGE_VERIFICATION_AGENT");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ComponentName matchComponentForVerifier(String packageName, List<ResolveInfo> receivers) {
        ActivityInfo targetReceiver = null;
        int NR = receivers.size();
        int i = 0;
        while (true) {
            if (i >= NR) {
                break;
            }
            ResolveInfo info = receivers.get(i);
            if (info.activityInfo == null || !packageName.equals(info.activityInfo.packageName)) {
                i++;
            } else {
                targetReceiver = info.activityInfo;
                break;
            }
        }
        if (targetReceiver == null) {
            return null;
        }
        return new ComponentName(targetReceiver.packageName, targetReceiver.name);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<ComponentName> matchVerifiers(PackageInfoLite pkgInfo, List<ResolveInfo> receivers, PackageVerificationState verificationState) {
        int verifierUid;
        if (pkgInfo.verifiers.length == 0) {
            return null;
        }
        int N = pkgInfo.verifiers.length;
        List<ComponentName> sufficientVerifiers = new ArrayList<>(N + 1);
        for (int i = 0; i < N; i++) {
            VerifierInfo verifierInfo = pkgInfo.verifiers[i];
            ComponentName comp = matchComponentForVerifier(verifierInfo.packageName, receivers);
            if (comp != null && (verifierUid = getUidForVerifier(verifierInfo)) != -1) {
                sufficientVerifiers.add(comp);
                verificationState.addSufficientVerifier(verifierUid);
            }
        }
        return sufficientVerifiers;
    }

    private int getUidForVerifier(VerifierInfo verifierInfo) {
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(verifierInfo.packageName);
            if (pkg == null) {
                return -1;
            }
            if (pkg.mSigningDetails.signatures.length != 1) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName + " has more than one signature; ignoring");
                return -1;
            }
            try {
                Signature verifierSig = pkg.mSigningDetails.signatures[0];
                PublicKey publicKey = verifierSig.getPublicKey();
                byte[] expectedPublicKey = publicKey.getEncoded();
                byte[] actualPublicKey = verifierInfo.publicKey.getEncoded();
                if (!Arrays.equals(actualPublicKey, expectedPublicKey)) {
                    Slog.i(TAG, "Verifier package " + verifierInfo.packageName + " does not have the expected public key; ignoring");
                    return -1;
                }
                return pkg.applicationInfo.uid;
            } catch (CertificateException e) {
                return -1;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setEnableRollbackCode(int token, int enableRollbackCode) {
        Message msg = this.mHandler.obtainMessage(21);
        msg.arg1 = token;
        msg.arg2 = enableRollbackCode;
        this.mHandler.sendMessage(msg);
    }

    public void finishPackageInstall(int token, boolean didLaunch) {
        enforceSystemOrRoot("Only the system is allowed to finish installs");
        Trace.asyncTraceEnd(262144L, "restore", token);
        Message msg = this.mHandler.obtainMessage(9, token, didLaunch ? 1 : 0);
        this.mHandler.sendMessage(msg);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long getVerificationTimeout() {
        return Settings.Global.getLong(this.mContext.getContentResolver(), "verifier_timeout", JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getDefaultVerificationResponse(UserHandle user) {
        if (sUserManager.hasUserRestriction("ensure_verify_apps", user.getIdentifier())) {
            return -1;
        }
        return Settings.Global.getInt(this.mContext.getContentResolver(), "verifier_default_response", 1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isVerificationEnabled(int userId, int installFlags, int installerUid) {
        ActivityInfo activityInfo;
        if ((524288 & installFlags) != 0) {
            return false;
        }
        boolean ensureVerifyAppsEnabled = isUserRestricted(userId, "ensure_verify_apps");
        if ((installFlags & 32) != 0) {
            if (ActivityManager.isRunningInTestHarness()) {
                return false;
            }
            if (ensureVerifyAppsEnabled) {
                return true;
            }
            if (Settings.Global.getInt(this.mContext.getContentResolver(), "verifier_verify_adb_installs", 1) == 0) {
                return false;
            }
        } else if ((installFlags & 2048) != 0 && (activityInfo = this.mInstantAppInstallerActivity) != null && activityInfo.packageName.equals(this.mRequiredVerifierPackage)) {
            try {
                ((AppOpsManager) this.mContext.getSystemService(AppOpsManager.class)).checkPackage(installerUid, this.mRequiredVerifierPackage);
                return false;
            } catch (SecurityException e) {
            }
        }
        return ensureVerifyAppsEnabled || Settings.Global.getInt(this.mContext.getContentResolver(), "package_verifier_enable", 1) == 1;
    }

    public void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains) throws RemoteException {
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTENT_FILTER_VERIFICATION_AGENT", "Only intentfilter verification agents can verify applications");
        Message msg = this.mHandler.obtainMessage(18);
        IntentFilterVerificationResponse response = new IntentFilterVerificationResponse(Binder.getCallingUid(), verificationCode, failedDomains);
        msg.arg1 = id;
        msg.obj = response;
        this.mHandler.sendMessage(msg);
    }

    public int getIntentVerificationStatus(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (UserHandle.getUserId(callingUid) != userId) {
            Context context = this.mContext;
            context.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "getIntentVerificationStatus" + userId);
        }
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps != null && !filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                return this.mSettings.getIntentFilterVerificationStatusLPr(packageName, userId);
            }
            return 0;
        }
    }

    public boolean updateIntentVerificationStatus(String packageName, int status, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (filterAppAccessLPr(ps, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                return false;
            }
            boolean result = this.mSettings.updateIntentFilterVerificationStatusLPw(packageName, status, userId);
            if (result) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
            return result;
        }
    }

    public ParceledListSlice<IntentFilterVerificationInfo> getIntentFilterVerifications(String packageName) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return ParceledListSlice.emptyList();
        }
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                return ParceledListSlice.emptyList();
            }
            return new ParceledListSlice<>(this.mSettings.getIntentFilterVerificationsLPr(packageName));
        }
    }

    public ParceledListSlice<IntentFilter> getAllIntentFilters(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return ParceledListSlice.emptyList();
        }
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg != null && pkg.activities != null) {
                if (pkg.mExtras == null) {
                    return ParceledListSlice.emptyList();
                }
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                if (filterAppAccessLPr(ps, callingUid, callingUserId)) {
                    return ParceledListSlice.emptyList();
                }
                int count = pkg.activities.size();
                ArrayList<IntentFilter> result = new ArrayList<>();
                for (int n = 0; n < count; n++) {
                    PackageParser.Activity activity = (PackageParser.Activity) pkg.activities.get(n);
                    if (activity.intents != null && activity.intents.size() > 0) {
                        result.addAll(activity.intents);
                    }
                }
                return new ParceledListSlice<>(result);
            }
            return ParceledListSlice.emptyList();
        }
    }

    public boolean setDefaultBrowserPackageName(String packageName, int userId) {
        PackageManagerInternal.DefaultBrowserProvider provider;
        this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        }
        if (userId == -1) {
            return false;
        }
        synchronized (this.mPackages) {
            provider = this.mDefaultBrowserProvider;
        }
        if (provider == null) {
            Slog.e(TAG, "mDefaultBrowserProvider is null");
            return false;
        }
        boolean successful = provider.setDefaultBrowser(packageName, userId);
        if (successful) {
            if (packageName != null) {
                synchronized (this.mPackages) {
                    this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultBrowser(packageName, userId);
                }
                return true;
            }
            return true;
        }
        return false;
    }

    private void setDefaultBrowserAsyncLPw(String packageName, int userId) {
        if (userId == -1) {
            return;
        }
        PackageManagerInternal.DefaultBrowserProvider defaultBrowserProvider = this.mDefaultBrowserProvider;
        if (defaultBrowserProvider == null) {
            Slog.e(TAG, "mDefaultBrowserProvider is null");
            return;
        }
        defaultBrowserProvider.setDefaultBrowserAsync(packageName, userId);
        if (packageName != null) {
            synchronized (this.mPackages) {
                this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultBrowser(packageName, userId);
            }
        }
    }

    public String getDefaultBrowserPackageName(int userId) {
        PackageManagerInternal.DefaultBrowserProvider provider;
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        }
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (this.mPackages) {
            provider = this.mDefaultBrowserProvider;
        }
        if (provider == null) {
            Slog.e(TAG, "mDefaultBrowserProvider is null");
            return null;
        }
        return provider.getDefaultBrowser(userId);
    }

    private int getUnknownSourcesSettings() {
        return Settings.Secure.getInt(this.mContext.getContentResolver(), "install_non_market_apps", -1);
    }

    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        PackageSetting installerPackageSetting;
        Signature[] callerSignature;
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return;
        }
        synchronized (this.mPackages) {
            PackageSetting targetPackageSetting = this.mSettings.mPackages.get(targetPackage);
            if (targetPackageSetting == null || filterAppAccessLPr(targetPackageSetting, callingUid, UserHandle.getUserId(callingUid))) {
                throw new IllegalArgumentException("Unknown target package: " + targetPackage);
            }
            PackageSetting targetInstallerPkgSetting = null;
            if (installerPackageName != null) {
                installerPackageSetting = this.mSettings.mPackages.get(installerPackageName);
                if (installerPackageSetting == null) {
                    throw new IllegalArgumentException("Unknown installer package: " + installerPackageName);
                }
            } else {
                installerPackageSetting = null;
            }
            int appId = UserHandle.getAppId(callingUid);
            Object obj = this.mSettings.getSettingLPr(appId);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    callerSignature = ((SharedUserSetting) obj).signatures.mSigningDetails.signatures;
                } else if (obj instanceof PackageSetting) {
                    callerSignature = ((PackageSetting) obj).signatures.mSigningDetails.signatures;
                } else {
                    throw new SecurityException("Bad object " + obj + " for uid " + callingUid);
                }
                if (installerPackageSetting != null && PackageManagerServiceUtils.compareSignatures(callerSignature, installerPackageSetting.signatures.mSigningDetails.signatures) != 0) {
                    throw new SecurityException("Caller does not have same cert as new installer package " + installerPackageName);
                }
                String targetInstallerPackageName = targetPackageSetting.installerPackageName;
                if (targetInstallerPackageName != null) {
                    targetInstallerPkgSetting = this.mSettings.mPackages.get(targetInstallerPackageName);
                }
                if (targetInstallerPkgSetting != null) {
                    if (PackageManagerServiceUtils.compareSignatures(callerSignature, targetInstallerPkgSetting.signatures.mSigningDetails.signatures) != 0) {
                        throw new SecurityException("Caller does not have same cert as old installer package " + targetInstallerPackageName);
                    }
                } else if (this.mContext.checkCallingOrSelfPermission("android.permission.INSTALL_PACKAGES") != 0) {
                    EventLog.writeEvent(1397638484, "150857253", Integer.valueOf(callingUid), "");
                    if (getUidTargetSdkVersionLockedLPr(callingUid) <= 29) {
                        return;
                    }
                    throw new SecurityException("Neither user " + callingUid + " nor current process has android.permission.INSTALL_PACKAGES");
                }
                targetPackageSetting.installerPackageName = installerPackageName;
                if (installerPackageName != null) {
                    this.mSettings.mInstallerPackages.add(installerPackageName);
                }
                scheduleWriteSettingsLocked();
                return;
            }
            throw new SecurityException("Unknown calling UID: " + callingUid);
        }
    }

    public void setApplicationCategoryHint(String packageName, int categoryHint, String callerPackageName) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        ((AppOpsManager) this.mContext.getSystemService(AppOpsManager.class)).checkPackage(Binder.getCallingUid(), callerPackageName);
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps == null) {
                throw new IllegalArgumentException("Unknown target package " + packageName);
            } else if (filterAppAccessLPr(ps, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                throw new IllegalArgumentException("Unknown target package " + packageName);
            } else if (!Objects.equals(callerPackageName, ps.installerPackageName)) {
                throw new IllegalArgumentException("Calling package " + callerPackageName + " is not installer for " + packageName);
            } else if (ps.categoryHint != categoryHint) {
                ps.categoryHint = categoryHint;
                scheduleWriteSettingsLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void processPendingInstall(InstallArgs args, int currentStatus) {
        if (args.mMultiPackageInstallParams != null) {
            args.mMultiPackageInstallParams.tryProcessInstallRequest(args, currentStatus);
            return;
        }
        PackageInstalledInfo res = createPackageInstalledInfo(currentStatus);
        processInstallRequestsAsync(res.returnCode == 1, Collections.singletonList(new InstallRequest(args, res, null)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void processInstallRequestsAsync(final boolean success, final List<InstallRequest> installRequests) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$6_Yp7BSB4TgtrWSoFlODKgHIvZY
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$processInstallRequestsAsync$14$PackageManagerService(success, installRequests);
            }
        });
    }

    public /* synthetic */ void lambda$processInstallRequestsAsync$14$PackageManagerService(boolean success, List installRequests) {
        if (success) {
            Iterator it = installRequests.iterator();
            while (it.hasNext()) {
                InstallRequest request = (InstallRequest) it.next();
                request.args.doPreInstall(request.installResult.returnCode);
            }
            synchronized (this.mInstallLock) {
                installPackagesTracedLI(installRequests);
            }
            Iterator it2 = installRequests.iterator();
            while (it2.hasNext()) {
                InstallRequest request2 = (InstallRequest) it2.next();
                request2.args.doPostInstall(request2.installResult.returnCode, request2.installResult.uid);
            }
        }
        Iterator it3 = installRequests.iterator();
        while (it3.hasNext()) {
            InstallRequest request3 = (InstallRequest) it3.next();
            restoreAndPostInstall(request3.args.user.getIdentifier(), request3.installResult, new PostInstallData(request3.args, request3.installResult, null));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PackageInstalledInfo createPackageInstalledInfo(int currentStatus) {
        PackageInstalledInfo res = new PackageInstalledInfo();
        res.setReturnCode(currentStatus);
        res.uid = -1;
        res.pkg = null;
        res.removedInfo = null;
        return res;
    }

    private void restoreAndPostInstall(int userId, PackageInstalledInfo res, PostInstallData data) {
        int userId2;
        long j;
        long ceDataInode;
        int appId;
        boolean update = (res.removedInfo == null || res.removedInfo.removedPackage == null) ? false : true;
        int flags = res.pkg == null ? 0 : res.pkg.applicationInfo.flags;
        boolean doRestore = (update || (32768 & flags) == 0) ? false : true;
        if (this.mNextInstallToken < 0) {
            this.mNextInstallToken = 1;
        }
        int token = this.mNextInstallToken;
        this.mNextInstallToken = token + 1;
        if (data != null) {
            this.mRunningInstalls.put(token, data);
        }
        if (res.returnCode == 1 && doRestore) {
            IBackupManager bm = IBackupManager.Stub.asInterface(ServiceManager.getService(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD));
            if (bm != null) {
                int userId3 = userId;
                if (userId3 == -1) {
                    userId3 = 0;
                }
                Trace.asyncTraceBegin(262144L, "restore", token);
                try {
                    if (bm.isBackupServiceActive(userId3)) {
                        bm.restoreAtInstallForUser(userId3, res.pkg.applicationInfo.packageName, token);
                    } else {
                        doRestore = false;
                    }
                } catch (RemoteException e) {
                } catch (Exception e2) {
                    Slog.e(TAG, "Exception trying to enqueue restore", e2);
                    doRestore = false;
                }
                userId2 = userId3;
            } else {
                Slog.e(TAG, "Backup Manager not found!");
                doRestore = false;
                userId2 = userId;
            }
        } else {
            userId2 = userId;
        }
        if (res.returnCode == 1 && !doRestore && update) {
            IRollbackManager rm = IRollbackManager.Stub.asInterface(ServiceManager.getService("rollback"));
            String packageName = res.pkg.applicationInfo.packageName;
            String seInfo = res.pkg.applicationInfo.seInfo;
            int[] allUsers = sUserManager.getUserIds();
            synchronized (this.mSettings) {
                try {
                    PackageSetting ps = this.mSettings.getPackageLPr(packageName);
                    if (ps == null) {
                        ceDataInode = -1;
                        appId = -1;
                    } else {
                        try {
                            int appId2 = ps.appId;
                            ceDataInode = ps.getCeDataInode(userId2);
                            appId = appId2;
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
                    try {
                        int[] installedUsers = ps.queryInstalledUsers(allUsers, true);
                        try {
                            j = 262144;
                            try {
                                rm.snapshotAndRestoreUserData(packageName, installedUsers, appId, ceDataInode, seInfo, token);
                            } catch (RemoteException e3) {
                            }
                            doRestore = true;
                        } catch (Throwable th3) {
                            th = th3;
                            while (true) {
                                break;
                                break;
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
        } else {
            j = 262144;
        }
        if (!doRestore) {
            Trace.asyncTraceBegin(j, "postInstall", token);
            Message msg = this.mHandler.obtainMessage(9, token, 0);
            this.mHandler.sendMessage(msg);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyFirstLaunch(final String packageName, final String installerPackage, final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$SxjjyVM6HSS7vYvHTON2BdU4enc
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$notifyFirstLaunch$15$PackageManagerService(packageName, userId, installerPackage);
            }
        });
    }

    public /* synthetic */ void lambda$notifyFirstLaunch$15$PackageManagerService(String packageName, int userId, String installerPackage) {
        for (int i = 0; i < this.mRunningInstalls.size(); i++) {
            PostInstallData data = this.mRunningInstalls.valueAt(i);
            if (data.res.returnCode == 1 && packageName.equals(data.res.pkg.applicationInfo.packageName)) {
                for (int uIndex = 0; uIndex < data.res.newUsers.length; uIndex++) {
                    if (userId == data.res.newUsers[uIndex]) {
                        return;
                    }
                }
                continue;
            }
        }
        boolean isInstantApp = isInstantApp(packageName, userId);
        int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[]{userId};
        int[] instantUserIds = isInstantApp ? new int[]{userId} : EMPTY_INT_ARRAY;
        sendFirstLaunchBroadcast(packageName, installerPackage, userIds, instantUserIds);
    }

    private void sendFirstLaunchBroadcast(String pkgName, String installerPkg, int[] userIds, int[] instantUserIds) {
        sendPackageBroadcast("android.intent.action.PACKAGE_FIRST_LAUNCH", pkgName, null, 0, installerPkg, null, userIds, instantUserIds);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class HandlerParams {
        private final UserHandle mUser;
        int traceCookie;
        String traceMethod;

        abstract void handleReturnCode();

        abstract void handleStartCopy();

        HandlerParams(UserHandle user) {
            this.mUser = user;
        }

        UserHandle getUser() {
            return this.mUser;
        }

        UserHandle getRollbackUser() {
            if (this.mUser == UserHandle.ALL) {
                return UserHandle.SYSTEM;
            }
            return this.mUser;
        }

        HandlerParams setTraceMethod(String traceMethod) {
            this.traceMethod = traceMethod;
            return this;
        }

        HandlerParams setTraceCookie(int traceCookie) {
            this.traceCookie = traceCookie;
            return this;
        }

        final void startCopy() {
            handleStartCopy();
            handleReturnCode();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class OriginInfo {
        final boolean existing;
        final File file;
        final File resolvedFile;
        final String resolvedPath;
        final boolean staged;

        static OriginInfo fromNothing() {
            return new OriginInfo(null, false, false);
        }

        static OriginInfo fromUntrustedFile(File file) {
            return new OriginInfo(file, false, false);
        }

        static OriginInfo fromExistingFile(File file) {
            return new OriginInfo(file, false, true);
        }

        static OriginInfo fromStagedFile(File file) {
            return new OriginInfo(file, true, false);
        }

        private OriginInfo(File file, boolean staged, boolean existing) {
            this.file = file;
            this.staged = staged;
            this.existing = existing;
            if (file != null) {
                this.resolvedPath = file.getAbsolutePath();
                this.resolvedFile = file;
                return;
            }
            this.resolvedPath = null;
            this.resolvedFile = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class MoveInfo {
        final int appId;
        final String dataAppName;
        final String fromUuid;
        final int moveId;
        final String packageName;
        final String seinfo;
        final int targetSdkVersion;
        final String toUuid;

        public MoveInfo(int moveId, String fromUuid, String toUuid, String packageName, String dataAppName, int appId, String seinfo, int targetSdkVersion) {
            this.moveId = moveId;
            this.fromUuid = fromUuid;
            this.toUuid = toUuid;
            this.packageName = packageName;
            this.dataAppName = dataAppName;
            this.appId = appId;
            this.seinfo = seinfo;
            this.targetSdkVersion = targetSdkVersion;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class VerificationInfo {
        public static final int NO_UID = -1;
        final int installerUid;
        final int originatingUid;
        final Uri originatingUri;
        final Uri referrer;

        VerificationInfo(Uri originatingUri, Uri referrer, int originatingUid, int installerUid) {
            this.originatingUri = originatingUri;
            this.referrer = referrer;
            this.originatingUid = originatingUid;
            this.installerUid = installerUid;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class MultiPackageInstallParams extends HandlerParams {
        private final ArrayList<InstallParams> mChildParams;
        private final Map<InstallArgs, Integer> mCurrentState;
        private int mRet;

        MultiPackageInstallParams(UserHandle user, List<ActiveInstallSession> activeInstallSessions) throws PackageManagerException {
            super(user);
            this.mRet = 1;
            if (activeInstallSessions.size() == 0) {
                throw new PackageManagerException("No child sessions found!");
            }
            this.mChildParams = new ArrayList<>(activeInstallSessions.size());
            for (int i = 0; i < activeInstallSessions.size(); i++) {
                InstallParams childParams = new InstallParams(activeInstallSessions.get(i));
                childParams.mParentInstallParams = this;
                this.mChildParams.add(childParams);
            }
            this.mCurrentState = new ArrayMap(this.mChildParams.size());
        }

        @Override // com.android.server.pm.PackageManagerService.HandlerParams
        void handleStartCopy() {
            Iterator<InstallParams> it = this.mChildParams.iterator();
            while (it.hasNext()) {
                InstallParams params = it.next();
                params.handleStartCopy();
                if (params.mRet != 1) {
                    this.mRet = params.mRet;
                }
            }
        }

        @Override // com.android.server.pm.PackageManagerService.HandlerParams
        void handleReturnCode() {
            Iterator<InstallParams> it = this.mChildParams.iterator();
            while (it.hasNext()) {
                InstallParams params = it.next();
                params.handleReturnCode();
                if (params.mRet != 1) {
                    this.mRet = params.mRet;
                }
            }
        }

        void tryProcessInstallRequest(InstallArgs args, int currentStatus) {
            this.mCurrentState.put(args, Integer.valueOf(currentStatus));
            if (this.mCurrentState.size() != this.mChildParams.size()) {
                return;
            }
            int completeStatus = 1;
            Iterator<Integer> it = this.mCurrentState.values().iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                Integer status = it.next();
                if (status.intValue() == 0) {
                    return;
                }
                if (status.intValue() != 1) {
                    completeStatus = status.intValue();
                    break;
                }
            }
            List<InstallRequest> installRequests = new ArrayList<>(this.mCurrentState.size());
            for (Map.Entry<InstallArgs, Integer> entry : this.mCurrentState.entrySet()) {
                installRequests.add(new InstallRequest(entry.getKey(), PackageManagerService.this.createPackageInstalledInfo(completeStatus), null));
            }
            PackageManagerService.this.processInstallRequestsAsync(completeStatus == 1, installRequests);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class InstallParams extends HandlerParams {
        final String[] grantedRuntimePermissions;
        int installFlags;
        final int installReason;
        final String installerPackageName;
        private InstallArgs mArgs;
        private boolean mEnableRollbackCompleted;
        MultiPackageInstallParams mParentInstallParams;
        int mRet;
        private boolean mVerificationCompleted;
        final MoveInfo move;
        final IPackageInstallObserver2 observer;
        final OriginInfo origin;
        final String packageAbiOverride;
        final long requiredInstalledVersionCode;
        final PackageParser.SigningDetails signingDetails;
        final VerificationInfo verificationInfo;
        final String volumeUuid;
        final List<String> whitelistedRestrictedPermissions;

        InstallParams(OriginInfo origin, MoveInfo move, IPackageInstallObserver2 observer, int installFlags, String installerPackageName, String volumeUuid, VerificationInfo verificationInfo, UserHandle user, String packageAbiOverride, String[] grantedPermissions, List<String> whitelistedRestrictedPermissions, PackageParser.SigningDetails signingDetails, int installReason, long requiredInstalledVersionCode) {
            super(user);
            this.origin = origin;
            this.move = move;
            this.observer = observer;
            this.installFlags = installFlags;
            this.installerPackageName = installerPackageName;
            this.volumeUuid = volumeUuid;
            this.verificationInfo = verificationInfo;
            this.packageAbiOverride = packageAbiOverride;
            this.grantedRuntimePermissions = grantedPermissions;
            this.whitelistedRestrictedPermissions = whitelistedRestrictedPermissions;
            this.signingDetails = signingDetails;
            this.installReason = installReason;
            this.requiredInstalledVersionCode = requiredInstalledVersionCode;
        }

        InstallParams(ActiveInstallSession activeInstallSession) {
            super(activeInstallSession.getUser());
            if (PackageManagerService.DEBUG_INSTANT && (activeInstallSession.getSessionParams().installFlags & 2048) != 0) {
                Slog.d(PackageManagerService.TAG, "Ephemeral install of " + activeInstallSession.getPackageName());
            }
            this.verificationInfo = new VerificationInfo(activeInstallSession.getSessionParams().originatingUri, activeInstallSession.getSessionParams().referrerUri, activeInstallSession.getSessionParams().originatingUid, activeInstallSession.getInstallerUid());
            this.origin = OriginInfo.fromStagedFile(activeInstallSession.getStagedDir());
            this.move = null;
            this.installReason = PackageManagerService.this.fixUpInstallReason(activeInstallSession.getInstallerPackageName(), activeInstallSession.getInstallerUid(), activeInstallSession.getSessionParams().installReason);
            this.observer = activeInstallSession.getObserver();
            this.installFlags = activeInstallSession.getSessionParams().installFlags;
            this.installerPackageName = activeInstallSession.getInstallerPackageName();
            this.volumeUuid = activeInstallSession.getSessionParams().volumeUuid;
            this.packageAbiOverride = activeInstallSession.getSessionParams().abiOverride;
            this.grantedRuntimePermissions = activeInstallSession.getSessionParams().grantedRuntimePermissions;
            this.whitelistedRestrictedPermissions = activeInstallSession.getSessionParams().whitelistedRestrictedPermissions;
            this.signingDetails = activeInstallSession.getSigningDetails();
            this.requiredInstalledVersionCode = activeInstallSession.getSessionParams().requiredInstalledVersionCode;
        }

        public String toString() {
            return "InstallParams{" + Integer.toHexString(System.identityHashCode(this)) + " file=" + this.origin.file + "}";
        }

        private int installLocationPolicy(PackageInfoLite pkgLite) {
            PackageSetting ps;
            String packageName = pkgLite.packageName;
            int installLocation = pkgLite.installLocation;
            synchronized (PackageManagerService.this.mPackages) {
                PackageParser.Package installedPkg = PackageManagerService.this.mPackages.get(packageName);
                PackageParser.Package dataOwnerPkg = installedPkg;
                if (dataOwnerPkg == null && (ps = PackageManagerService.this.mSettings.mPackages.get(packageName)) != null) {
                    dataOwnerPkg = ps.pkg;
                }
                if (this.requiredInstalledVersionCode != -1) {
                    if (dataOwnerPkg == null) {
                        Slog.w(PackageManagerService.TAG, "Required installed version code was " + this.requiredInstalledVersionCode + " but package is not installed");
                        return -8;
                    } else if (dataOwnerPkg.getLongVersionCode() != this.requiredInstalledVersionCode) {
                        Slog.w(PackageManagerService.TAG, "Required installed version code was " + this.requiredInstalledVersionCode + " but actual installed version is " + dataOwnerPkg.getLongVersionCode());
                        return -8;
                    }
                }
                if (dataOwnerPkg != null && !PackageManagerServiceUtils.isDowngradePermitted(this.installFlags, dataOwnerPkg.applicationInfo.flags)) {
                    try {
                        PackageManagerService.checkDowngrade(dataOwnerPkg, pkgLite);
                    } catch (PackageManagerException e) {
                        Slog.w(PackageManagerService.TAG, "Downgrade detected: " + e.getMessage());
                        return -7;
                    }
                }
                if (installedPkg != null) {
                    if ((this.installFlags & 2) != 0) {
                        if ((installedPkg.applicationInfo.flags & 1) != 0) {
                            return 1;
                        }
                        if (installLocation == 1) {
                            return 1;
                        }
                        if (installLocation != 2) {
                            return PackageManagerService.isExternal(installedPkg) ? 2 : 1;
                        }
                    } else {
                        return -4;
                    }
                }
                return pkgLite.recommendedInstallLocation;
            }
        }

        /* JADX WARN: Removed duplicated region for block: B:124:0x030d  */
        @Override // com.android.server.pm.PackageManagerService.HandlerParams
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void handleStartCopy() {
            /*
                Method dump skipped, instructions count: 947
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.InstallParams.handleStartCopy():void");
        }

        void setReturnCode(int ret) {
            if (this.mRet == 1) {
                this.mRet = ret;
            }
        }

        void handleVerificationFinished() {
            this.mVerificationCompleted = true;
            handleReturnCode();
        }

        void handleRollbackEnabled() {
            this.mEnableRollbackCompleted = true;
            handleReturnCode();
        }

        @Override // com.android.server.pm.PackageManagerService.HandlerParams
        void handleReturnCode() {
            if (this.mVerificationCompleted && this.mEnableRollbackCompleted) {
                if ((this.installFlags & 8388608) != 0) {
                    String packageName = "";
                    try {
                        new PackageParser();
                        PackageParser.PackageLite packageInfo = PackageParser.parsePackageLite(this.origin.file, 0);
                        packageName = packageInfo.packageName;
                    } catch (PackageParser.PackageParserException e) {
                        Slog.e(PackageManagerService.TAG, "Can't parse package at " + this.origin.file.getAbsolutePath(), e);
                    }
                    try {
                        this.observer.onPackageInstalled(packageName, this.mRet, "Dry run", new Bundle());
                        return;
                    } catch (RemoteException e2) {
                        Slog.i(PackageManagerService.TAG, "Observer no longer exists.");
                        return;
                    }
                }
                if (this.mRet == 1) {
                    this.mRet = this.mArgs.copyApk();
                }
                PackageManagerService.this.processPendingInstall(this.mArgs, this.mRet);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public InstallArgs createInstallArgs(InstallParams params) {
        if (params.move != null) {
            return new MoveInstallArgs(params);
        }
        return new FileInstallArgs(params);
    }

    private InstallArgs createInstallArgsForExisting(String codePath, String resourcePath, String[] instructionSets) {
        return new FileInstallArgs(codePath, resourcePath, instructionSets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static abstract class InstallArgs {
        final String abiOverride;
        final int installFlags;
        final String[] installGrantPermissions;
        final int installReason;
        final String installerPackageName;
        String[] instructionSets;
        final MultiPackageInstallParams mMultiPackageInstallParams;
        final MoveInfo move;
        final IPackageInstallObserver2 observer;
        final OriginInfo origin;
        final PackageParser.SigningDetails signingDetails;
        final int traceCookie;
        final String traceMethod;
        final UserHandle user;
        final String volumeUuid;
        final List<String> whitelistedRestrictedPermissions;

        abstract void cleanUpResourcesLI();

        abstract int copyApk();

        abstract boolean doPostDeleteLI(boolean z);

        abstract int doPostInstall(int i, int i2);

        abstract int doPreInstall(int i);

        abstract boolean doRename(int i, PackageParser.Package r2);

        abstract String getCodePath();

        abstract String getResourcePath();

        InstallArgs(OriginInfo origin, MoveInfo move, IPackageInstallObserver2 observer, int installFlags, String installerPackageName, String volumeUuid, UserHandle user, String[] instructionSets, String abiOverride, String[] installGrantPermissions, List<String> whitelistedRestrictedPermissions, String traceMethod, int traceCookie, PackageParser.SigningDetails signingDetails, int installReason, MultiPackageInstallParams multiPackageInstallParams) {
            this.origin = origin;
            this.move = move;
            this.installFlags = installFlags;
            this.observer = observer;
            this.installerPackageName = installerPackageName;
            this.volumeUuid = volumeUuid;
            this.user = user;
            this.instructionSets = instructionSets;
            this.abiOverride = abiOverride;
            this.installGrantPermissions = installGrantPermissions;
            this.whitelistedRestrictedPermissions = whitelistedRestrictedPermissions;
            this.traceMethod = traceMethod;
            this.traceCookie = traceCookie;
            this.signingDetails = signingDetails;
            this.installReason = installReason;
            this.mMultiPackageInstallParams = multiPackageInstallParams;
        }

        int doPreCopy() {
            return 1;
        }

        int doPostCopy(int uid) {
            return 1;
        }

        protected boolean isEphemeral() {
            return (this.installFlags & 2048) != 0;
        }

        UserHandle getUser() {
            return this.user;
        }
    }

    void removeDexFiles(List<String> allCodePaths, String[] instructionSets) {
        if (!allCodePaths.isEmpty()) {
            if (instructionSets == null) {
                throw new IllegalStateException("instructionSet == null");
            }
            String[] dexCodeInstructionSets = InstructionSets.getDexCodeInstructionSets(instructionSets);
            for (String codePath : allCodePaths) {
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    try {
                        this.mInstaller.rmdex(codePath, dexCodeInstructionSet);
                    } catch (Installer.InstallerException e) {
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class FileInstallArgs extends InstallArgs {
        private File codeFile;
        private File resourceFile;

        FileInstallArgs(InstallParams params) {
            super(params.origin, params.move, params.observer, params.installFlags, params.installerPackageName, params.volumeUuid, params.getUser(), null, params.packageAbiOverride, params.grantedRuntimePermissions, params.whitelistedRestrictedPermissions, params.traceMethod, params.traceCookie, params.signingDetails, params.installReason, params.mParentInstallParams);
        }

        FileInstallArgs(String codePath, String resourcePath, String[] instructionSets) {
            super(OriginInfo.fromNothing(), null, null, 0, null, null, null, instructionSets, null, null, null, null, 0, PackageParser.SigningDetails.UNKNOWN, 0, null);
            this.codeFile = codePath != null ? new File(codePath) : null;
            this.resourceFile = resourcePath != null ? new File(resourcePath) : null;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int copyApk() {
            Trace.traceBegin(262144L, "copyApk");
            try {
                return doCopyApk();
            } finally {
                Trace.traceEnd(262144L);
            }
        }

        private int doCopyApk() {
            int ret;
            if (this.origin.staged) {
                this.codeFile = this.origin.file;
                this.resourceFile = this.origin.file;
                return 1;
            }
            try {
                boolean isEphemeral = (this.installFlags & 2048) != 0;
                File tempDir = PackageManagerService.this.mInstallerService.allocateStageDirLegacy(this.volumeUuid, isEphemeral);
                this.codeFile = tempDir;
                this.resourceFile = tempDir;
                int ret2 = PackageManagerServiceUtils.copyPackage(this.origin.file.getAbsolutePath(), this.codeFile);
                if (ret2 != 1) {
                    Slog.e(PackageManagerService.TAG, "Failed to copy package");
                    return ret2;
                }
                File libraryRoot = new File(this.codeFile, "lib");
                NativeLibraryHelper.Handle handle = null;
                try {
                    try {
                        handle = NativeLibraryHelper.Handle.create(this.codeFile);
                        ret = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot, this.abiOverride);
                    } finally {
                        IoUtils.closeQuietly(handle);
                    }
                } catch (IOException e) {
                    Slog.e(PackageManagerService.TAG, "Copying native libraries failed", e);
                    ret = RequestStatus.SYS_ETIMEDOUT;
                }
                return ret;
            } catch (IOException e2) {
                Slog.w(PackageManagerService.TAG, "Failed to create copy file: " + e2);
                return -4;
            }
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int doPreInstall(int status) {
            if (status != 1) {
                cleanUp();
            }
            return status;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        boolean doRename(int status, PackageParser.Package pkg) {
            if (status != 1) {
                cleanUp();
                return false;
            }
            File targetDir = this.codeFile.getParentFile();
            File beforeCodeFile = this.codeFile;
            File afterCodeFile = PackageManagerService.this.getNextCodePath(targetDir, pkg.packageName);
            try {
                Os.rename(beforeCodeFile.getAbsolutePath(), afterCodeFile.getAbsolutePath());
                if (!SELinux.restoreconRecursive(afterCodeFile)) {
                    Slog.w(PackageManagerService.TAG, "Failed to restorecon");
                    return false;
                }
                this.codeFile = afterCodeFile;
                this.resourceFile = afterCodeFile;
                try {
                    pkg.setCodePath(afterCodeFile.getCanonicalPath());
                    pkg.setBaseCodePath(FileUtils.rewriteAfterRename(beforeCodeFile, afterCodeFile, pkg.baseCodePath));
                    pkg.setSplitCodePaths(FileUtils.rewriteAfterRename(beforeCodeFile, afterCodeFile, pkg.splitCodePaths));
                    pkg.setApplicationVolumeUuid(pkg.volumeUuid);
                    pkg.setApplicationInfoCodePath(pkg.codePath);
                    pkg.setApplicationInfoBaseCodePath(pkg.baseCodePath);
                    pkg.setApplicationInfoSplitCodePaths(pkg.splitCodePaths);
                    pkg.setApplicationInfoResourcePath(pkg.codePath);
                    pkg.setApplicationInfoBaseResourcePath(pkg.baseCodePath);
                    pkg.setApplicationInfoSplitResourcePaths(pkg.splitCodePaths);
                    return true;
                } catch (IOException e) {
                    Slog.e(PackageManagerService.TAG, "Failed to get path: " + afterCodeFile, e);
                    return false;
                }
            } catch (ErrnoException e2) {
                Slog.w(PackageManagerService.TAG, "Failed to rename", e2);
                return false;
            }
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int doPostInstall(int status, int uid) {
            if (status != 1) {
                cleanUp();
            }
            return status;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        String getCodePath() {
            File file = this.codeFile;
            if (file != null) {
                return file.getAbsolutePath();
            }
            return null;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        String getResourcePath() {
            File file = this.resourceFile;
            if (file != null) {
                return file.getAbsolutePath();
            }
            return null;
        }

        private boolean cleanUp() {
            File file = this.codeFile;
            if (file == null || !file.exists()) {
                return false;
            }
            PackageManagerService.this.removeCodePathLI(this.codeFile);
            File file2 = this.resourceFile;
            if (file2 != null && !FileUtils.contains(this.codeFile, file2)) {
                this.resourceFile.delete();
                return true;
            }
            return true;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        void cleanUpResourcesLI() {
            List<String> allCodePaths = Collections.EMPTY_LIST;
            File file = this.codeFile;
            if (file != null && file.exists()) {
                try {
                    PackageParser.PackageLite pkg = PackageParser.parsePackageLite(this.codeFile, 0);
                    allCodePaths = pkg.getAllCodePaths();
                } catch (PackageParser.PackageParserException e) {
                }
            }
            cleanUp();
            PackageManagerService.this.removeDexFiles(allCodePaths, this.instructionSets);
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        boolean doPostDeleteLI(boolean delete) {
            cleanUpResourcesLI();
            return true;
        }
    }

    private static void maybeThrowExceptionForMultiArchCopy(String message, int copyRet) throws PackageManagerException {
        if (copyRet < 0 && copyRet != -114 && copyRet != -113) {
            throw new PackageManagerException(copyRet, message);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class MoveInstallArgs extends InstallArgs {
        private File codeFile;
        private File resourceFile;

        MoveInstallArgs(InstallParams params) {
            super(params.origin, params.move, params.observer, params.installFlags, params.installerPackageName, params.volumeUuid, params.getUser(), null, params.packageAbiOverride, params.grantedRuntimePermissions, params.whitelistedRestrictedPermissions, params.traceMethod, params.traceCookie, params.signingDetails, params.installReason, params.mParentInstallParams);
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int copyApk() {
            synchronized (PackageManagerService.this.mInstaller) {
                try {
                    PackageManagerService.this.mInstaller.moveCompleteApp(this.move.fromUuid, this.move.toUuid, this.move.packageName, this.move.dataAppName, this.move.appId, this.move.seinfo, this.move.targetSdkVersion);
                } catch (Installer.InstallerException e) {
                    Slog.w(PackageManagerService.TAG, "Failed to move app", e);
                    return RequestStatus.SYS_ETIMEDOUT;
                }
            }
            this.codeFile = new File(Environment.getDataAppDirectory(this.move.toUuid), this.move.dataAppName);
            this.resourceFile = this.codeFile;
            return 1;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int doPreInstall(int status) {
            if (status != 1) {
                cleanUp(this.move.toUuid);
            }
            return status;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        boolean doRename(int status, PackageParser.Package pkg) {
            if (status != 1) {
                cleanUp(this.move.toUuid);
                return false;
            }
            pkg.setApplicationVolumeUuid(pkg.volumeUuid);
            pkg.setApplicationInfoCodePath(pkg.codePath);
            pkg.setApplicationInfoBaseCodePath(pkg.baseCodePath);
            pkg.setApplicationInfoSplitCodePaths(pkg.splitCodePaths);
            pkg.setApplicationInfoResourcePath(pkg.codePath);
            pkg.setApplicationInfoBaseResourcePath(pkg.baseCodePath);
            pkg.setApplicationInfoSplitResourcePaths(pkg.splitCodePaths);
            return true;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int doPostInstall(int status, int uid) {
            if (status == 1) {
                cleanUp(this.move.fromUuid);
            } else {
                cleanUp(this.move.toUuid);
            }
            return status;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        String getCodePath() {
            File file = this.codeFile;
            if (file != null) {
                return file.getAbsolutePath();
            }
            return null;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        String getResourcePath() {
            File file = this.resourceFile;
            if (file != null) {
                return file.getAbsolutePath();
            }
            return null;
        }

        private boolean cleanUp(String volumeUuid) {
            File codeFile = new File(Environment.getDataAppDirectory(volumeUuid), this.move.dataAppName);
            Slog.d(PackageManagerService.TAG, "Cleaning up " + this.move.packageName + " on " + volumeUuid);
            int[] userIds = PackageManagerService.sUserManager.getUserIds();
            synchronized (PackageManagerService.this.mInstallLock) {
                for (int userId : userIds) {
                    try {
                        PackageManagerService.this.mInstaller.destroyAppData(volumeUuid, this.move.packageName, userId, 3, 0L);
                    } catch (Installer.InstallerException e) {
                        Slog.w(PackageManagerService.TAG, String.valueOf(e));
                    }
                }
                PackageManagerService.this.removeCodePathLI(codeFile);
            }
            return true;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        void cleanUpResourcesLI() {
            throw new UnsupportedOperationException();
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        boolean doPostDeleteLI(boolean delete) {
            throw new UnsupportedOperationException();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public File getNextCodePath(File targetDir, String packageName) {
        File result;
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        do {
            random.nextBytes(bytes);
            String suffix = Base64.encodeToString(bytes, 10);
            result = new File(targetDir, packageName + "-" + suffix);
        } while (result.exists());
        return result;
    }

    static String deriveCodePathName(String codePath) {
        if (codePath == null) {
            return null;
        }
        File codeFile = new File(codePath);
        String name = codeFile.getName();
        if (codeFile.isDirectory()) {
            return name;
        }
        if (name.endsWith(".apk") || name.endsWith(".tmp")) {
            int lastDot = name.lastIndexOf(46);
            return name.substring(0, lastDot);
        }
        Slog.w(TAG, "Odd, " + codePath + " doesn't look like an APK");
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class PackageInstalledInfo {
        ArrayMap<String, PackageInstalledInfo> addedChildPackages;
        String installerPackageName;
        ArrayList<PackageParser.Package> libraryConsumers;
        String name;
        int[] newUsers;
        String origPackage;
        String origPermission;
        int[] origUsers;
        PackageParser.Package pkg;
        PackageRemovedInfo removedInfo;
        int returnCode;
        String returnMsg;
        int uid;

        PackageInstalledInfo() {
        }

        public void setError(int code, String msg) {
            setReturnCode(code);
            setReturnMessage(msg);
            Slog.w(PackageManagerService.TAG, msg);
        }

        public void setError(String msg, PackageParser.PackageParserException e) {
            setReturnCode(e.error);
            setReturnMessage(ExceptionUtils.getCompleteMessage(msg, e));
            ArrayMap<String, PackageInstalledInfo> arrayMap = this.addedChildPackages;
            int childCount = arrayMap != null ? arrayMap.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).setError(msg, e);
            }
            Slog.w(PackageManagerService.TAG, msg, e);
        }

        public void setError(String msg, PackageManagerException e) {
            this.returnCode = e.error;
            setReturnMessage(ExceptionUtils.getCompleteMessage(msg, e));
            ArrayMap<String, PackageInstalledInfo> arrayMap = this.addedChildPackages;
            int childCount = arrayMap != null ? arrayMap.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).setError(msg, e);
            }
            Slog.w(PackageManagerService.TAG, msg, e);
        }

        public void setReturnCode(int returnCode) {
            this.returnCode = returnCode;
            ArrayMap<String, PackageInstalledInfo> arrayMap = this.addedChildPackages;
            int childCount = arrayMap != null ? arrayMap.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).returnCode = returnCode;
            }
        }

        private void setReturnMessage(String returnMsg) {
            this.returnMsg = returnMsg;
            ArrayMap<String, PackageInstalledInfo> arrayMap = this.addedChildPackages;
            int childCount = arrayMap != null ? arrayMap.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).returnMsg = returnMsg;
            }
        }
    }

    private static void updateDigest(MessageDigest digest, File file) throws IOException {
        DigestInputStream digestStream = new DigestInputStream(new FileInputStream(file), digest);
        do {
            try {
            } finally {
            }
        } while (digestStream.read() != -1);
        $closeResource(null, digestStream);
    }

    private String getParentOrChildPackageChangedSharedUser(PackageParser.Package oldPkg, PackageParser.Package newPkg) {
        if (!Objects.equals(oldPkg.mSharedUserId, newPkg.mSharedUserId)) {
            return newPkg.packageName;
        }
        int oldChildCount = oldPkg.childPackages != null ? oldPkg.childPackages.size() : 0;
        int newChildCount = newPkg.childPackages != null ? newPkg.childPackages.size() : 0;
        for (int i = 0; i < newChildCount; i++) {
            PackageParser.Package newChildPkg = (PackageParser.Package) newPkg.childPackages.get(i);
            for (int j = 0; j < oldChildCount; j++) {
                PackageParser.Package oldChildPkg = (PackageParser.Package) oldPkg.childPackages.get(j);
                if (newChildPkg.packageName.equals(oldChildPkg.packageName) && !Objects.equals(newChildPkg.mSharedUserId, oldChildPkg.mSharedUserId)) {
                    return newChildPkg.packageName;
                }
            }
        }
        return null;
    }

    private void removeNativeBinariesLI(PackageSetting ps) {
        PackageSetting childPs;
        if (ps != null) {
            NativeLibraryHelper.removeNativeBinariesLI(ps.legacyNativeLibraryPathString);
            int childCount = ps.childPackageNames != null ? ps.childPackageNames.size() : 0;
            for (int i = 0; i < childCount; i++) {
                synchronized (this.mPackages) {
                    childPs = this.mSettings.getPackageLPr(ps.childPackageNames.get(i));
                }
                if (childPs != null) {
                    NativeLibraryHelper.removeNativeBinariesLI(childPs.legacyNativeLibraryPathString);
                }
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void enableSystemPackageLPw(PackageParser.Package pkg) {
        this.mSettings.enableSystemPackageLPw(pkg.packageName);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
            this.mSettings.enableSystemPackageLPw(childPkg.packageName);
        }
    }

    @GuardedBy({"mPackages"})
    private boolean disableSystemPackageLPw(PackageParser.Package oldPkg, PackageParser.Package newPkg) {
        boolean disabled = this.mSettings.disableSystemPackageLPw(oldPkg.packageName, true);
        int childCount = oldPkg.childPackages != null ? oldPkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) oldPkg.childPackages.get(i);
            boolean replace = newPkg.hasChildPackage(childPkg.packageName);
            disabled |= this.mSettings.disableSystemPackageLPw(childPkg.packageName, replace);
        }
        return disabled;
    }

    @GuardedBy({"mPackages"})
    private void setInstallerPackageNameLPw(PackageParser.Package pkg, String installerPackageName) {
        this.mSettings.setInstallerPackageName(pkg.packageName, installerPackageName);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
            this.mSettings.setInstallerPackageName(childPkg.packageName, installerPackageName);
        }
    }

    private void updateSettingsLI(PackageParser.Package newPackage, String installerPackageName, int[] allUsers, PackageInstalledInfo res, UserHandle user, int installReason) {
        updateSettingsInternalLI(newPackage, installerPackageName, allUsers, res.origUsers, res, user, installReason);
        int childCount = newPackage.childPackages != null ? newPackage.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPackage = (PackageParser.Package) newPackage.childPackages.get(i);
            PackageInstalledInfo childRes = res.addedChildPackages.get(childPackage.packageName);
            updateSettingsInternalLI(childPackage, installerPackageName, allUsers, childRes.origUsers, childRes, user, installReason);
        }
    }

    private void updateSettingsInternalLI(PackageParser.Package pkg, String installerPackageName, int[] allUsers, int[] installedForUsers, PackageInstalledInfo res, UserHandle user, int installReason) {
        int[] userIds;
        int[] iArr;
        int origUserId;
        Trace.traceBegin(262144L, "updateSettings");
        String pkgName = pkg.packageName;
        synchronized (this.mPackages) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                this.mPermissionManager.updatePermissions(pkg.packageName, pkg, true, this.mPackages.values(), this.mPermissionCallback);
                PackageSetting ps = this.mSettings.mPackages.get(pkgName);
                int userId = user.getIdentifier();
                if (ps != null) {
                    if (isSystemApp(pkg)) {
                        if (res.origUsers != null) {
                            for (int origUserId2 : res.origUsers) {
                                if (userId != -1) {
                                    origUserId = origUserId2;
                                    if (userId != origUserId) {
                                    }
                                } else {
                                    origUserId = origUserId2;
                                }
                                ps.setEnabled(0, origUserId, installerPackageName);
                            }
                        }
                        if (allUsers != null && installedForUsers != null) {
                            for (int currentUserId : allUsers) {
                                boolean installed = ArrayUtils.contains(installedForUsers, currentUserId);
                                ps.setInstalled(installed, currentUserId);
                            }
                        }
                    }
                    if (userId != -1) {
                        ps.setInstalled(true, userId);
                        ps.setEnabled(0, userId, installerPackageName);
                    }
                    Set<Integer> previousUserIds = new ArraySet<>();
                    if (res.removedInfo != null && res.removedInfo.installReasons != null) {
                        int installReasonCount = res.removedInfo.installReasons.size();
                        for (int i = 0; i < installReasonCount; i++) {
                            int previousUserId = res.removedInfo.installReasons.keyAt(i);
                            int previousInstallReason = res.removedInfo.installReasons.valueAt(i).intValue();
                            ps.setInstallReason(previousInstallReason, previousUserId);
                            previousUserIds.add(Integer.valueOf(previousUserId));
                        }
                    }
                    if (userId == -1) {
                        for (int currentUserId2 : sUserManager.getUserIds()) {
                            if (!previousUserIds.contains(Integer.valueOf(currentUserId2))) {
                                ps.setInstallReason(installReason, currentUserId2);
                            }
                        }
                    } else if (!previousUserIds.contains(Integer.valueOf(userId))) {
                        ps.setInstallReason(installReason, userId);
                    }
                    this.mSettings.writeKernelMappingLPr(ps);
                }
                res.name = pkgName;
                res.uid = pkg.applicationInfo.uid;
                res.pkg = pkg;
                this.mSettings.setInstallerPackageName(pkgName, installerPackageName);
                res.setReturnCode(1);
                Trace.traceBegin(262144L, "writeSettings");
                this.mSettings.writeLPr();
                Trace.traceEnd(262144L);
                Trace.traceEnd(262144L);
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class InstallRequest {
        public final InstallArgs args;
        public final PackageInstalledInfo installResult;

        /* synthetic */ InstallRequest(InstallArgs x0, PackageInstalledInfo x1, AnonymousClass1 x2) {
            this(x0, x1);
        }

        private InstallRequest(InstallArgs args, PackageInstalledInfo res) {
            this.args = args;
            this.installResult = res;
        }
    }

    @GuardedBy({"mInstallLock", "mPackages"})
    private void installPackagesTracedLI(List<InstallRequest> requests) {
        try {
            Trace.traceBegin(262144L, "installPackages");
            installPackagesLI(requests);
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class CommitRequest {
        final int[] mAllUsers;
        final Map<String, ReconciledPackage> reconciledPackages;

        /* synthetic */ CommitRequest(Map x0, int[] x1, AnonymousClass1 x2) {
            this(x0, x1);
        }

        private CommitRequest(Map<String, ReconciledPackage> reconciledPackages, int[] allUsers) {
            this.reconciledPackages = reconciledPackages;
            this.mAllUsers = allUsers;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ReconcileRequest {
        public final Map<String, PackageParser.Package> allPackages;
        public final Map<String, InstallArgs> installArgs;
        public final Map<String, PackageInstalledInfo> installResults;
        public final Map<String, PackageSetting> lastStaticSharedLibSettings;
        public final Map<String, PrepareResult> preparedPackages;
        public final Map<String, ScanResult> scannedPackages;
        public final Map<String, LongSparseArray<SharedLibraryInfo>> sharedLibrarySource;
        public final Map<String, Settings.VersionInfo> versionInfos;

        /* synthetic */ ReconcileRequest(Map x0, Map x1, Map x2, Map x3, Map x4, AnonymousClass1 x5) {
            this(x0, x1, x2, x3, x4);
        }

        /* synthetic */ ReconcileRequest(Map x0, Map x1, Map x2, Map x3, Map x4, Map x5, Map x6, Map x7, AnonymousClass1 x8) {
            this(x0, x1, x2, x3, x4, x5, x6, x7);
        }

        private ReconcileRequest(Map<String, ScanResult> scannedPackages, Map<String, InstallArgs> installArgs, Map<String, PackageInstalledInfo> installResults, Map<String, PrepareResult> preparedPackages, Map<String, LongSparseArray<SharedLibraryInfo>> sharedLibrarySource, Map<String, PackageParser.Package> allPackages, Map<String, Settings.VersionInfo> versionInfos, Map<String, PackageSetting> lastStaticSharedLibSettings) {
            this.scannedPackages = scannedPackages;
            this.installArgs = installArgs;
            this.installResults = installResults;
            this.preparedPackages = preparedPackages;
            this.sharedLibrarySource = sharedLibrarySource;
            this.allPackages = allPackages;
            this.versionInfos = versionInfos;
            this.lastStaticSharedLibSettings = lastStaticSharedLibSettings;
        }

        private ReconcileRequest(Map<String, ScanResult> scannedPackages, Map<String, LongSparseArray<SharedLibraryInfo>> sharedLibrarySource, Map<String, PackageParser.Package> allPackages, Map<String, Settings.VersionInfo> versionInfos, Map<String, PackageSetting> lastStaticSharedLibSettings) {
            this(scannedPackages, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), sharedLibrarySource, allPackages, versionInfos, lastStaticSharedLibSettings);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ReconcileFailure extends PackageManagerException {
        ReconcileFailure(String message) {
            super("Reconcile failed: " + message);
        }

        ReconcileFailure(int reason, String message) {
            super(reason, "Reconcile failed: " + message);
        }

        ReconcileFailure(PackageManagerException e) {
            this(e.error, e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ReconciledPackage {
        public final List<SharedLibraryInfo> allowedSharedLibraryInfos;
        public ArrayList<SharedLibraryInfo> collectedSharedLibraryInfos;
        public final DeletePackageAction deletePackageAction;
        public final InstallArgs installArgs;
        public final PackageInstalledInfo installResult;
        public final PackageSetting pkgSetting;
        public final PrepareResult prepareResult;
        public final boolean removeAppKeySetData;
        public final ReconcileRequest request;
        public final ScanResult scanResult;
        public final boolean sharedUserSignaturesChanged;
        public final PackageParser.SigningDetails signingDetails;

        /* synthetic */ ReconciledPackage(ReconcileRequest x0, InstallArgs x1, PackageSetting x2, PackageInstalledInfo x3, PrepareResult x4, ScanResult x5, DeletePackageAction x6, List x7, PackageParser.SigningDetails x8, boolean x9, boolean x10, AnonymousClass1 x11) {
            this(x0, x1, x2, x3, x4, x5, x6, x7, x8, x9, x10);
        }

        private ReconciledPackage(ReconcileRequest request, InstallArgs installArgs, PackageSetting pkgSetting, PackageInstalledInfo installResult, PrepareResult prepareResult, ScanResult scanResult, DeletePackageAction deletePackageAction, List<SharedLibraryInfo> allowedSharedLibraryInfos, PackageParser.SigningDetails signingDetails, boolean sharedUserSignaturesChanged, boolean removeAppKeySetData) {
            this.request = request;
            this.installArgs = installArgs;
            this.pkgSetting = pkgSetting;
            this.installResult = installResult;
            this.prepareResult = prepareResult;
            this.scanResult = scanResult;
            this.deletePackageAction = deletePackageAction;
            this.allowedSharedLibraryInfos = allowedSharedLibraryInfos;
            this.signingDetails = signingDetails;
            this.sharedUserSignaturesChanged = sharedUserSignaturesChanged;
            this.removeAppKeySetData = removeAppKeySetData;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public Map<String, PackageParser.Package> getCombinedPackages() {
            ArrayMap<String, PackageParser.Package> combinedPackages = new ArrayMap<>(this.request.allPackages.size() + this.request.scannedPackages.size());
            combinedPackages.putAll(this.request.allPackages);
            for (ScanResult scanResult : this.request.scannedPackages.values()) {
                combinedPackages.put(scanResult.pkgSetting.name, scanResult.request.pkg);
            }
            return combinedPackages;
        }
    }

    @GuardedBy({"mPackages"})
    private static Map<String, ReconciledPackage> reconcilePackagesLocked(ReconcileRequest request, KeySetManagerService ksms) throws ReconcileFailure {
        Map<String, LongSparseArray<SharedLibraryInfo>> map;
        ArrayMap<String, PackageParser.Package> combinedPackages;
        DeletePackageAction deletePackageAction;
        PackageSetting packageSetting;
        PackageSetting disabledPkgSetting;
        Map<String, LongSparseArray<SharedLibraryInfo>> incomingSharedLibraries;
        PackageParser.SigningDetails signingDetails;
        KeySetManagerService keySetManagerService = ksms;
        Map<String, ScanResult> scannedPackages = request.scannedPackages;
        Map<String, ReconciledPackage> result = new ArrayMap<>(scannedPackages.size());
        ArrayMap<String, PackageParser.Package> combinedPackages2 = new ArrayMap<>(request.allPackages.size() + scannedPackages.size());
        combinedPackages2.putAll(request.allPackages);
        Map<String, LongSparseArray<SharedLibraryInfo>> arrayMap = new ArrayMap<>();
        for (String installPackageName : scannedPackages.keySet()) {
            ScanResult scanResult = scannedPackages.get(installPackageName);
            combinedPackages2.put(scanResult.pkgSetting.name, scanResult.request.pkg);
            List<SharedLibraryInfo> allowedSharedLibInfos = getAllowedSharedLibInfos(scanResult, request.sharedLibrarySource);
            SharedLibraryInfo staticLib = scanResult.staticSharedLibraryInfo;
            if (allowedSharedLibInfos != null) {
                for (SharedLibraryInfo info : allowedSharedLibInfos) {
                    if (!addSharedLibraryToPackageVersionMap(arrayMap, info)) {
                        throw new ReconcileFailure("Static Shared Library " + staticLib.getName() + " is being installed twice in this set!");
                    }
                }
            }
            InstallArgs installArgs = request.installArgs.get(installPackageName);
            PackageInstalledInfo res = request.installResults.get(installPackageName);
            PrepareResult prepareResult = request.preparedPackages.get(installPackageName);
            boolean isInstall = installArgs != null;
            if (isInstall && (res == null || prepareResult == null)) {
                throw new ReconcileFailure("Reconcile arguments are not balanced for " + installPackageName + "!");
            }
            if (isInstall && prepareResult.replace && !prepareResult.system) {
                boolean killApp = (scanResult.request.scanFlags & 2048) == 0;
                int deleteFlags = 1 | (killApp ? 0 : 8);
                DeletePackageAction deletePackageAction2 = mayDeletePackageLocked(res.removedInfo, prepareResult.originalPs, prepareResult.disabledPs, prepareResult.childPackageSettings, deleteFlags, null);
                if (deletePackageAction2 == null) {
                    throw new ReconcileFailure(-10, "May not delete " + installPackageName + " to replace");
                }
                deletePackageAction = deletePackageAction2;
            } else {
                deletePackageAction = null;
            }
            int scanFlags = scanResult.request.scanFlags;
            int parseFlags = scanResult.request.parseFlags;
            PackageParser.Package pkg = scanResult.request.pkg;
            PackageSetting disabledPkgSetting2 = scanResult.request.disabledPkgSetting;
            PackageSetting lastStaticSharedLibSetting = request.lastStaticSharedLibSettings.get(installPackageName);
            if (prepareResult != null && lastStaticSharedLibSetting != null) {
                packageSetting = lastStaticSharedLibSetting;
            } else {
                packageSetting = scanResult.pkgSetting;
            }
            PackageSetting signatureCheckPs = packageSetting;
            boolean removeAppKeySetData = false;
            boolean sharedUserSignaturesChanged = false;
            Map<String, ScanResult> scannedPackages2 = scannedPackages;
            if (keySetManagerService.shouldCheckUpgradeKeySetLocked(signatureCheckPs, scanFlags)) {
                if (!keySetManagerService.checkUpgradeKeySetLocked(signatureCheckPs, pkg)) {
                    if ((parseFlags & 16) == 0) {
                        throw new ReconcileFailure(-7, "Package " + pkg.packageName + " upgrade keys do not match the previously installed version");
                    }
                    String msg = "System package " + pkg.packageName + " signature changed; retaining data.";
                    reportSettingsProblem(5, msg);
                }
                signingDetails = pkg.mSigningDetails;
                disabledPkgSetting = disabledPkgSetting2;
                incomingSharedLibraries = arrayMap;
            } else {
                try {
                    Settings.VersionInfo versionInfo = request.versionInfos.get(installPackageName);
                    boolean compareCompat = isCompatSignatureUpdateNeeded(versionInfo);
                    boolean compareRecover = isRecoverSignatureUpdateNeeded(versionInfo);
                    incomingSharedLibraries = arrayMap;
                    try {
                        boolean compatMatch = PackageManagerServiceUtils.verifySignatures(signatureCheckPs, disabledPkgSetting2, pkg.mSigningDetails, compareCompat, compareRecover);
                        if (compatMatch) {
                            removeAppKeySetData = true;
                        }
                        signingDetails = pkg.mSigningDetails;
                        if (signatureCheckPs.sharedUser == null) {
                            disabledPkgSetting = disabledPkgSetting2;
                        } else {
                            disabledPkgSetting = disabledPkgSetting2;
                            try {
                                if (pkg.mSigningDetails.hasAncestor(signatureCheckPs.sharedUser.signatures.mSigningDetails)) {
                                    signatureCheckPs.sharedUser.signatures.mSigningDetails = pkg.mSigningDetails;
                                }
                                if (signatureCheckPs.sharedUser.signaturesChanged == null) {
                                    signatureCheckPs.sharedUser.signaturesChanged = Boolean.FALSE;
                                }
                            } catch (PackageManagerException e) {
                                e = e;
                                if ((parseFlags & 16) == 0) {
                                    throw new ReconcileFailure(e);
                                }
                                PackageParser.SigningDetails signingDetails2 = pkg.mSigningDetails;
                                if (signatureCheckPs.sharedUser == null) {
                                    signingDetails = signingDetails2;
                                } else {
                                    Signature[] sharedUserSignatures = signatureCheckPs.sharedUser.signatures.mSigningDetails.signatures;
                                    if (signatureCheckPs.sharedUser.signaturesChanged != null && PackageManagerServiceUtils.compareSignatures(sharedUserSignatures, pkg.mSigningDetails.signatures) != 0) {
                                        if (SystemProperties.getInt("ro.product.first_api_level", 0) <= 29) {
                                            throw new ReconcileFailure(-104, "Signature mismatch for shared user: " + scanResult.pkgSetting.sharedUser);
                                        }
                                        throw new IllegalStateException("Signature mismatch on system package " + pkg.packageName + " for shared user " + scanResult.pkgSetting.sharedUser);
                                    }
                                    sharedUserSignaturesChanged = true;
                                    signingDetails = signingDetails2;
                                    signatureCheckPs.sharedUser.signatures.mSigningDetails = pkg.mSigningDetails;
                                    signatureCheckPs.sharedUser.signaturesChanged = Boolean.TRUE;
                                }
                                String msg2 = "System package " + pkg.packageName + " signature changed; retaining data.";
                                reportSettingsProblem(5, msg2);
                                Map<String, ReconciledPackage> result2 = result;
                                result2.put(installPackageName, new ReconciledPackage(request, installArgs, scanResult.pkgSetting, res, request.preparedPackages.get(installPackageName), scanResult, deletePackageAction, allowedSharedLibInfos, signingDetails, sharedUserSignaturesChanged, removeAppKeySetData, null));
                                result = result2;
                                scannedPackages = scannedPackages2;
                                arrayMap = incomingSharedLibraries;
                                combinedPackages2 = combinedPackages2;
                                keySetManagerService = ksms;
                            } catch (IllegalArgumentException e2) {
                                e = e2;
                                throw new RuntimeException("Signing certificates comparison made on incomparable signing details but somehow passed verifySignatures!", e);
                            }
                        }
                    } catch (PackageManagerException e3) {
                        e = e3;
                        disabledPkgSetting = disabledPkgSetting2;
                    } catch (IllegalArgumentException e4) {
                        e = e4;
                    }
                } catch (PackageManagerException e5) {
                    e = e5;
                    disabledPkgSetting = disabledPkgSetting2;
                    incomingSharedLibraries = arrayMap;
                } catch (IllegalArgumentException e6) {
                    e = e6;
                }
            }
            Map<String, ReconciledPackage> result22 = result;
            result22.put(installPackageName, new ReconciledPackage(request, installArgs, scanResult.pkgSetting, res, request.preparedPackages.get(installPackageName), scanResult, deletePackageAction, allowedSharedLibInfos, signingDetails, sharedUserSignaturesChanged, removeAppKeySetData, null));
            result = result22;
            scannedPackages = scannedPackages2;
            arrayMap = incomingSharedLibraries;
            combinedPackages2 = combinedPackages2;
            keySetManagerService = ksms;
        }
        Map<String, LongSparseArray<SharedLibraryInfo>> map2 = arrayMap;
        ArrayMap<String, PackageParser.Package> combinedPackages3 = combinedPackages2;
        Map<String, ReconciledPackage> result3 = result;
        Map<String, ScanResult> scannedPackages3 = scannedPackages;
        for (String installPackageName2 : scannedPackages3.keySet()) {
            Map<String, ScanResult> scannedPackages4 = scannedPackages3;
            ScanResult scanResult2 = scannedPackages4.get(installPackageName2);
            if ((scanResult2.request.scanFlags & 16) != 0) {
                scannedPackages3 = scannedPackages4;
            } else if ((scanResult2.request.parseFlags & 16) != 0) {
                scannedPackages3 = scannedPackages4;
            } else {
                try {
                    map = map2;
                    combinedPackages = combinedPackages3;
                } catch (PackageManagerException e7) {
                    e = e7;
                }
                try {
                    result3.get(installPackageName2).collectedSharedLibraryInfos = collectSharedLibraryInfos(scanResult2.request.pkg, combinedPackages, request.sharedLibrarySource, map);
                    scannedPackages3 = scannedPackages4;
                    combinedPackages3 = combinedPackages;
                    map2 = map;
                } catch (PackageManagerException e8) {
                    e = e8;
                    throw new ReconcileFailure(e.error, e.getMessage());
                }
            }
        }
        return result3;
    }

    private static List<SharedLibraryInfo> getAllowedSharedLibInfos(ScanResult scanResult, Map<String, LongSparseArray<SharedLibraryInfo>> existingSharedLibraries) {
        PackageSetting updatedSystemPs;
        PackageParser.Package pkg = scanResult.request.pkg;
        if (scanResult.staticSharedLibraryInfo == null && scanResult.dynamicSharedLibraryInfos == null) {
            return null;
        }
        if (scanResult.staticSharedLibraryInfo != null) {
            return Collections.singletonList(scanResult.staticSharedLibraryInfo);
        }
        boolean z = true;
        boolean hasDynamicLibraries = ((pkg.applicationInfo.flags & 1) == 0 || scanResult.dynamicSharedLibraryInfos == null) ? false : false;
        if (hasDynamicLibraries) {
            boolean isUpdatedSystemApp = pkg.isUpdatedSystemApp();
            if (isUpdatedSystemApp) {
                if (scanResult.request.disabledPkgSetting == null) {
                    updatedSystemPs = scanResult.request.oldPkgSetting;
                } else {
                    updatedSystemPs = scanResult.request.disabledPkgSetting;
                }
            } else {
                updatedSystemPs = null;
            }
            if (isUpdatedSystemApp && (updatedSystemPs.pkg == null || updatedSystemPs.pkg.libraryNames == null)) {
                Slog.w(TAG, "Package " + pkg.packageName + " declares libraries that are not declared on the system image; skipping");
                return null;
            }
            ArrayList<SharedLibraryInfo> infos = new ArrayList<>(scanResult.dynamicSharedLibraryInfos.size());
            for (SharedLibraryInfo info : scanResult.dynamicSharedLibraryInfos) {
                String name = info.getName();
                if (isUpdatedSystemApp && !updatedSystemPs.pkg.libraryNames.contains(name)) {
                    Slog.w(TAG, "Package " + pkg.packageName + " declares library " + name + " that is not declared on system image; skipping");
                } else if (sharedLibExists(name, -1L, existingSharedLibraries)) {
                    Slog.w(TAG, "Package " + pkg.packageName + " declares library " + name + " that already exists; skipping");
                } else {
                    infos.add(info);
                }
            }
            return infos;
        }
        return null;
    }

    private static boolean addSharedLibraryToPackageVersionMap(Map<String, LongSparseArray<SharedLibraryInfo>> target, SharedLibraryInfo library) {
        String name = library.getName();
        if (target.containsKey(name)) {
            if (library.getType() != 2 || target.get(name).indexOfKey(library.getLongVersion()) >= 0) {
                return false;
            }
        } else {
            target.put(name, new LongSparseArray<>());
        }
        target.get(name).put(library.getLongVersion(), library);
        return true;
    }

    /* JADX WARN: Removed duplicated region for block: B:101:0x028c  */
    /* JADX WARN: Removed duplicated region for block: B:102:0x029e  */
    /* JADX WARN: Removed duplicated region for block: B:105:0x02a3  */
    /* JADX WARN: Removed duplicated region for block: B:108:0x02ae  */
    /* JADX WARN: Removed duplicated region for block: B:114:0x02df  */
    /* JADX WARN: Removed duplicated region for block: B:124:0x02e7 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:61:0x019c  */
    /* JADX WARN: Removed duplicated region for block: B:67:0x01c0  */
    /* JADX WARN: Removed duplicated region for block: B:70:0x01c6  */
    /* JADX WARN: Removed duplicated region for block: B:75:0x01f0  */
    /* JADX WARN: Removed duplicated region for block: B:95:0x0258  */
    @com.android.internal.annotations.GuardedBy({"mPackages"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void commitPackagesLocked(com.android.server.pm.PackageManagerService.CommitRequest r29) {
        /*
            Method dump skipped, instructions count: 748
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.commitPackagesLocked(com.android.server.pm.PackageManagerService$CommitRequest):void");
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:193:0x0465
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    @com.android.internal.annotations.GuardedBy({"mInstallLock"})
    private void installPackagesLI(java.util.List<com.android.server.pm.PackageManagerService.InstallRequest> r30) {
        /*
            Method dump skipped, instructions count: 1422
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.installPackagesLI(java.util.List):void");
    }

    private void executePostCommitSteps(CommitRequest commitRequest) {
        for (ReconciledPackage reconciledPkg : commitRequest.reconciledPackages.values()) {
            boolean instantApp = (reconciledPkg.scanResult.request.scanFlags & 16384) != 0;
            PackageParser.Package pkg = reconciledPkg.pkgSetting.pkg;
            String packageName = pkg.packageName;
            prepareAppDataAfterInstallLIF(pkg);
            if (reconciledPkg.prepareResult.clearCodeCache) {
                clearAppDataLIF(pkg, -1, 39);
            }
            if (reconciledPkg.prepareResult.replace) {
                this.mDexManager.notifyPackageUpdated(pkg.packageName, pkg.baseCodePath, pkg.splitCodePaths);
            }
            this.mArtManagerService.prepareAppProfiles(pkg, resolveUserIds(reconciledPkg.installArgs.user.getIdentifier()), true);
            boolean performDexopt = !(instantApp && Settings.Global.getInt(this.mContext.getContentResolver(), "instant_app_dexopt_enabled", 0) == 0) && (pkg.applicationInfo.flags & 2) == 0;
            if (performDexopt) {
                if (SystemProperties.getBoolean(PRECOMPILE_LAYOUTS, false)) {
                    Trace.traceBegin(262144L, "compileLayouts");
                    this.mViewCompiler.compileLayouts(pkg);
                    Trace.traceEnd(262144L);
                }
                Trace.traceBegin(262144L, "dexopt");
                DexoptOptions dexoptOptions = new DexoptOptions(packageName, 2, (int) UsbTerminalTypes.TERMINAL_BIDIR_SKRPHONE_SUPRESS);
                this.mPackageDexOptimizer.performDexOpt(pkg, null, getOrCreateCompilerPackageStats(pkg), this.mDexManager.getPackageUseInfoOrDefault(packageName), dexoptOptions);
                Trace.traceEnd(262144L);
            }
            BackgroundDexOptService.notifyPackageChanged(packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class PrepareResult {
        public final PackageSetting[] childPackageSettings;
        public final boolean clearCodeCache;
        public final PackageSetting disabledPs;
        public final PackageParser.Package existingPackage;
        public final PackageFreezer freezer;
        public final int installReason;
        public final String installerPackageName;
        public final PackageSetting originalPs;
        public final PackageParser.Package packageToScan;
        public final int parseFlags;
        public final String renamedPackage;
        public final boolean replace;
        public final int scanFlags;
        public final boolean system;
        public final UserHandle user;
        public final String volumeUuid;

        /* synthetic */ PrepareResult(int x0, String x1, String x2, UserHandle x3, boolean x4, int x5, int x6, PackageParser.Package x7, PackageParser.Package x8, boolean x9, boolean x10, String x11, PackageFreezer x12, PackageSetting x13, PackageSetting x14, PackageSetting[] x15, AnonymousClass1 x16) {
            this(x0, x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15);
        }

        private PrepareResult(int installReason, String volumeUuid, String installerPackageName, UserHandle user, boolean replace, int scanFlags, int parseFlags, PackageParser.Package existingPackage, PackageParser.Package packageToScan, boolean clearCodeCache, boolean system, String renamedPackage, PackageFreezer freezer, PackageSetting originalPs, PackageSetting disabledPs, PackageSetting[] childPackageSettings) {
            this.installReason = installReason;
            this.volumeUuid = volumeUuid;
            this.installerPackageName = installerPackageName;
            this.user = user;
            this.replace = replace;
            this.scanFlags = scanFlags;
            this.parseFlags = parseFlags;
            this.existingPackage = existingPackage;
            this.packageToScan = packageToScan;
            this.clearCodeCache = clearCodeCache;
            this.system = system;
            this.renamedPackage = renamedPackage;
            this.freezer = freezer;
            this.originalPs = originalPs;
            this.disabledPs = disabledPs;
            this.childPackageSettings = childPackageSettings;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class PrepareFailure extends PackageManagerException {
        public String conflictingPackage;
        public String conflictingPermission;

        PrepareFailure(int error) {
            super(error, "Failed to prepare for install.");
        }

        PrepareFailure(int error, String detailMessage) {
            super(error, detailMessage);
        }

        /* JADX WARN: Illegal instructions before constructor call */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        PrepareFailure(java.lang.String r3, java.lang.Exception r4) {
            /*
                r2 = this;
                boolean r0 = r4 instanceof android.content.pm.PackageParser.PackageParserException
                if (r0 == 0) goto La
                r0 = r4
                android.content.pm.PackageParser$PackageParserException r0 = (android.content.pm.PackageParser.PackageParserException) r0
                int r0 = r0.error
                goto Lf
            La:
                r0 = r4
                com.android.server.pm.PackageManagerException r0 = (com.android.server.pm.PackageManagerException) r0
                int r0 = r0.error
            Lf:
                java.lang.String r1 = android.util.ExceptionUtils.getCompleteMessage(r3, r4)
                r2.<init>(r0, r1)
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.PrepareFailure.<init>(java.lang.String, java.lang.Exception):void");
        }

        PrepareFailure conflictsWithExistingPermission(String conflictingPermission, String conflictingPackage) {
            this.conflictingPermission = conflictingPermission;
            this.conflictingPackage = conflictingPackage;
            return this;
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Not initialized variable reg: 16, insn: 0x0cc0: MOVE  (r42 I:??[int, float, boolean, short, byte, char, OBJECT, ARRAY] A[D('installFlags' int)]) = (r16 I:??[int, float, boolean, short, byte, char, OBJECT, ARRAY] A[D('onExternal' boolean)]), block:B:585:0x0cb9 */
    /* JADX WARN: Not initialized variable reg: 42, insn: 0x0cbe: MOVE  (r28 I:??[int, float, boolean, short, byte, char, OBJECT, ARRAY] A[D('parseFlags' int)]) = (r42 I:??[int, float, boolean, short, byte, char, OBJECT, ARRAY] A[D('installFlags' int)]), block:B:585:0x0cb9 */
    /* JADX WARN: Removed duplicated region for block: B:142:0x02a5  */
    /* JADX WARN: Removed duplicated region for block: B:178:0x03ab A[Catch: all -> 0x03df, TryCatch #36 {all -> 0x03df, blocks: (B:158:0x0312, B:160:0x031b, B:162:0x0320, B:163:0x0336, B:169:0x035d, B:172:0x0362, B:173:0x038b, B:167:0x0342, B:153:0x02d3, B:154:0x02fb, B:178:0x03ab, B:179:0x03de), top: B:727:0x02a3, inners: #62 }] */
    /* JADX WARN: Removed duplicated region for block: B:444:0x09ba A[Catch: all -> 0x0c66, TRY_LEAVE, TryCatch #41 {all -> 0x0c66, blocks: (B:442:0x09b4, B:444:0x09ba), top: B:735:0x09b4 }] */
    /* JADX WARN: Removed duplicated region for block: B:569:0x0c3a  */
    /* JADX WARN: Removed duplicated region for block: B:632:0x0df5  */
    /* JADX WARN: Type inference failed for: r22v3 */
    /* JADX WARN: Type inference failed for: r22v5 */
    @com.android.internal.annotations.GuardedBy({"mInstallLock"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.pm.PackageManagerService.PrepareResult preparePackageLI(com.android.server.pm.PackageManagerService.InstallArgs r48, com.android.server.pm.PackageManagerService.PackageInstalledInfo r49) throws com.android.server.pm.PackageManagerService.PrepareFailure {
        /*
            Method dump skipped, instructions count: 3800
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.preparePackageLI(com.android.server.pm.PackageManagerService$InstallArgs, com.android.server.pm.PackageManagerService$PackageInstalledInfo):com.android.server.pm.PackageManagerService$PrepareResult");
    }

    private void setUpFsVerityIfPossible(PackageParser.Package pkg) throws Installer.InstallerException, PrepareFailure, IOException, DigestException, NoSuchAlgorithmException {
        boolean standardMode = PackageManagerServiceUtils.isApkVerityEnabled();
        boolean legacyMode = PackageManagerServiceUtils.isLegacyApkVerityEnabled();
        if (!standardMode && !legacyMode) {
            return;
        }
        ArrayMap<String, String> fsverityCandidates = new ArrayMap<>();
        int i = 0;
        if (legacyMode) {
            synchronized (this.mPackages) {
                PackageSetting ps = this.mSettings.mPackages.get(pkg.packageName);
                if (ps != null && ps.isPrivileged()) {
                    fsverityCandidates.put(pkg.baseCodePath, null);
                    if (pkg.splitCodePaths != null) {
                        String[] strArr = pkg.splitCodePaths;
                        int length = strArr.length;
                        while (i < length) {
                            String splitPath = strArr[i];
                            fsverityCandidates.put(splitPath, null);
                            i++;
                        }
                    }
                }
            }
        } else {
            fsverityCandidates.put(pkg.baseCodePath, VerityUtils.getFsveritySignatureFilePath(pkg.baseCodePath));
            String dmPath = DexMetadataHelper.buildDexMetadataPathForApk(pkg.baseCodePath);
            if (new File(dmPath).exists()) {
                fsverityCandidates.put(dmPath, VerityUtils.getFsveritySignatureFilePath(dmPath));
            }
            if (pkg.splitCodePaths != null) {
                String[] strArr2 = pkg.splitCodePaths;
                int length2 = strArr2.length;
                while (i < length2) {
                    String path = strArr2[i];
                    fsverityCandidates.put(path, VerityUtils.getFsveritySignatureFilePath(path));
                    String splitDmPath = DexMetadataHelper.buildDexMetadataPathForApk(path);
                    if (new File(splitDmPath).exists()) {
                        fsverityCandidates.put(splitDmPath, VerityUtils.getFsveritySignatureFilePath(splitDmPath));
                    }
                    i++;
                }
            }
        }
        for (Map.Entry<String, String> entry : fsverityCandidates.entrySet()) {
            String filePath = entry.getKey();
            String signaturePath = entry.getValue();
            if (!legacyMode) {
                if (new File(signaturePath).exists() && !VerityUtils.hasFsverity(filePath)) {
                    try {
                        VerityUtils.setUpFsverity(filePath, signaturePath);
                    } catch (IOException | SecurityException | DigestException | NoSuchAlgorithmException e) {
                        throw new PrepareFailure(-118, "Failed to enable fs-verity: " + e);
                    }
                }
            } else {
                VerityUtils.SetupResult result = VerityUtils.generateApkVeritySetupData(filePath);
                if (result.isOk()) {
                    if (Build.IS_DEBUGGABLE) {
                        Slog.i(TAG, "Enabling verity to " + filePath);
                    }
                    FileDescriptor fd = result.getUnownedFileDescriptor();
                    try {
                        byte[] rootHash = VerityUtils.generateApkVerityRootHash(filePath);
                        try {
                            this.mInstaller.assertFsverityRootHashMatches(filePath, rootHash);
                        } catch (Installer.InstallerException e2) {
                            this.mInstaller.installApkVerity(filePath, fd, result.getContentSize());
                            this.mInstaller.assertFsverityRootHashMatches(filePath, rootHash);
                        }
                    } finally {
                        IoUtils.closeQuietly(fd);
                    }
                } else if (result.isFailed()) {
                    throw new PrepareFailure(-118, "Failed to generate verity");
                }
            }
        }
    }

    private void startIntentFilterVerifications(int userId, boolean replacing, PackageParser.Package pkg) {
        ComponentName componentName = this.mIntentFilterVerifierComponent;
        if (componentName == null) {
            Slog.w(TAG, "No IntentFilter verification will not be done as there is no IntentFilterVerifier available!");
            return;
        }
        int verifierUid = getPackageUid(componentName.getPackageName(), 268435456, userId == -1 ? 0 : userId);
        Message msg = this.mHandler.obtainMessage(17);
        msg.obj = new IFVerificationParams(pkg, replacing, userId, verifierUid);
        this.mHandler.sendMessage(msg);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
            Message msg2 = this.mHandler.obtainMessage(17);
            msg2.obj = new IFVerificationParams(childPkg, replacing, userId, verifierUid);
            this.mHandler.sendMessage(msg2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:106:? A[ADDED_TO_REGION, RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:65:0x0120  */
    /* JADX WARN: Removed duplicated region for block: B:70:0x012a A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:75:0x0136 A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:84:0x0147 A[ADDED_TO_REGION] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void verifyIntentFiltersIfNeeded(int r28, int r29, boolean r30, android.content.pm.PackageParser.Package r31) {
        /*
            Method dump skipped, instructions count: 344
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.verifyIntentFiltersIfNeeded(int, int, boolean, android.content.pm.PackageParser$Package):void");
    }

    @GuardedBy({"mPackages"})
    private boolean needsNetworkVerificationLPr(String packageName) {
        int status;
        IntentFilterVerificationInfo ivi = this.mSettings.getIntentFilterVerificationLPr(packageName);
        if (ivi == null || (status = ivi.getStatus()) == 0 || status == 1 || status == 2) {
            return true;
        }
        return false;
    }

    private static boolean isMultiArch(ApplicationInfo info) {
        return (info.flags & Integer.MIN_VALUE) != 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isExternal(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & 262144) != 0;
    }

    private static boolean isExternal(PackageSetting ps) {
        return (ps.pkgFlags & 262144) != 0;
    }

    private static boolean isSystemApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & 1) != 0;
    }

    private static boolean isPrivilegedApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 8) != 0;
    }

    private static boolean isOemApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 131072) != 0;
    }

    private static boolean isVendorApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 262144) != 0;
    }

    private static boolean isProductApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 524288) != 0;
    }

    private static boolean isProductServicesApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 2097152) != 0;
    }

    private static boolean isOdmApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 1073741824) != 0;
    }

    private static boolean hasDomainURLs(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 16) != 0;
    }

    private static boolean isSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & 1) != 0;
    }

    private static boolean isUpdatedSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & 128) != 0;
    }

    private Settings.VersionInfo getSettingsVersionForPackage(PackageParser.Package pkg) {
        if (isExternal(pkg)) {
            if (TextUtils.isEmpty(pkg.volumeUuid)) {
                return this.mSettings.getExternalVersion();
            }
            return this.mSettings.findOrCreateVersion(pkg.volumeUuid);
        }
        return this.mSettings.getInternalVersion();
    }

    private void deleteTempPackageFiles() {
        $$Lambda$PackageManagerService$InxwP9v95IsHM1AIPecZaxo1Q __lambda_packagemanagerservice_inxwp9v95ishm1aipeczaxo1q = new FilenameFilter() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$InxwP9v95IsHM1A-IPecZaxo-1Q
            @Override // java.io.FilenameFilter
            public final boolean accept(File file, String str) {
                return PackageManagerService.lambda$deleteTempPackageFiles$16(file, str);
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$deleteTempPackageFiles$16(File dir, String name) {
        return name.startsWith("vmdl") && name.endsWith(".tmp");
    }

    public void deletePackageAsUser(String packageName, int versionCode, IPackageDeleteObserver observer, int userId, int flags) {
        deletePackageVersioned(new VersionedPackage(packageName, versionCode), new PackageManager.LegacyPackageDeleteObserver(observer).getBinder(), userId, flags);
    }

    public void deletePackageVersioned(VersionedPackage versionedPackage, final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags) {
        final String internalPackageName;
        final int callingUid = Binder.getCallingUid();
        this.mContext.enforceCallingOrSelfPermission("android.permission.DELETE_PACKAGES", null);
        final boolean canViewInstantApps = canViewInstantApps(callingUid, userId);
        Preconditions.checkNotNull(versionedPackage);
        Preconditions.checkNotNull(observer);
        Preconditions.checkArgumentInRange(versionedPackage.getLongVersionCode(), -1L, (long) JobStatus.NO_LATEST_RUNTIME, "versionCode must be >= -1");
        final String packageName = versionedPackage.getPackageName();
        final long versionCode = versionedPackage.getLongVersionCode();
        synchronized (this.mPackages) {
            try {
                internalPackageName = resolveInternalPackageNameLPr(packageName, versionCode);
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
        int uid = Binder.getCallingUid();
        if (!isOrphaned(internalPackageName) && !isCallerAllowedToSilentlyUninstall(uid, internalPackageName)) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$fswFUK6dCGUD9_CR_zyaZxmYAp0
                @Override // java.lang.Runnable
                public final void run() {
                    PackageManagerService.lambda$deletePackageVersioned$17(packageName, observer);
                }
            });
            return;
        }
        final boolean deleteAllUsers = (deleteFlags & 2) != 0;
        final int[] users = deleteAllUsers ? sUserManager.getUserIds() : new int[]{userId};
        if (UserHandle.getUserId(uid) != userId || (deleteAllUsers && users.length > 1)) {
            Context context = this.mContext;
            context.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "deletePackage for user " + userId);
        }
        if (isUserRestricted(userId, "no_uninstall_apps")) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$CS3aJUelGPn4PWtkx1QPsjrbNtc
                @Override // java.lang.Runnable
                public final void run() {
                    observer.onPackageDeleted(packageName, -3, (String) null);
                }
            });
        } else if (!deleteAllUsers && getBlockUninstallForUser(internalPackageName, userId)) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$A9GGP3Sl3gE_C_zP7bNUbtlbRjI
                @Override // java.lang.Runnable
                public final void run() {
                    observer.onPackageDeleted(packageName, -4, (String) null);
                }
            });
        } else {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$_6B9au9z1U04Ew3TKUI7EAfYytI
                @Override // java.lang.Runnable
                public final void run() {
                    PackageManagerService.this.lambda$deletePackageVersioned$20$PackageManagerService(internalPackageName, callingUid, canViewInstantApps, deleteAllUsers, versionCode, userId, deleteFlags, users, observer, packageName);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$deletePackageVersioned$17(String packageName, IPackageDeleteObserver2 observer) {
        try {
            Intent intent = new Intent("android.intent.action.UNINSTALL_PACKAGE");
            intent.setData(Uri.fromParts("package", packageName, null));
            intent.putExtra("android.content.pm.extra.CALLBACK", observer.asBinder());
            observer.onUserActionRequired(intent);
        } catch (RemoteException e) {
        }
    }

    public /* synthetic */ void lambda$deletePackageVersioned$20$PackageManagerService(String internalPackageName, int callingUid, boolean canViewInstantApps, boolean deleteAllUsers, long versionCode, int userId, int deleteFlags, int[] users, IPackageDeleteObserver2 observer, String packageName) {
        boolean doDeletePackage;
        int returnCode;
        int returnCode2;
        int i;
        PackageSetting ps = this.mSettings.mPackages.get(internalPackageName);
        if (ps == null) {
            doDeletePackage = true;
        } else {
            boolean targetIsInstantApp = ps.getInstantApp(UserHandle.getUserId(callingUid));
            boolean doDeletePackage2 = !targetIsInstantApp || canViewInstantApps;
            doDeletePackage = doDeletePackage2;
        }
        if (doDeletePackage) {
            if (!deleteAllUsers) {
                returnCode = deletePackageX(internalPackageName, versionCode, userId, deleteFlags);
            } else {
                int[] blockUninstallUserIds = getBlockUninstallForUsers(internalPackageName, users);
                if (ArrayUtils.isEmpty(blockUninstallUserIds)) {
                    returnCode2 = deletePackageX(internalPackageName, versionCode, userId, deleteFlags);
                } else {
                    int userFlags = deleteFlags & (-3);
                    int length = users.length;
                    int i2 = 0;
                    while (i2 < length) {
                        int userId1 = users[i2];
                        if (ArrayUtils.contains(blockUninstallUserIds, userId1)) {
                            i = i2;
                        } else {
                            i = i2;
                            int returnCode3 = deletePackageX(internalPackageName, versionCode, userId1, userFlags);
                            if (returnCode3 != 1) {
                                Slog.w(TAG, "Package delete failed for user " + userId1 + ", returnCode " + returnCode3);
                            }
                        }
                        i2 = i + 1;
                    }
                    returnCode2 = -4;
                }
                returnCode = returnCode2;
            }
        } else {
            returnCode = -1;
        }
        try {
            observer.onPackageDeleted(packageName, returnCode, (String) null);
        } catch (RemoteException e) {
            Log.i(TAG, "Observer no longer exists.");
        }
    }

    private String resolveExternalPackageNameLPr(PackageParser.Package pkg) {
        if (pkg.staticSharedLibName != null) {
            return pkg.manifestPackageName;
        }
        return pkg.packageName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public String resolveInternalPackageNameLPr(String packageName, long versionCode) {
        String normalizedPackageName = this.mSettings.getRenamedPackageLPr(packageName);
        String packageName2 = normalizedPackageName != null ? normalizedPackageName : packageName;
        LongSparseArray<SharedLibraryInfo> versionedLib = this.mStaticLibsByDeclaringPackage.get(packageName2);
        if (versionedLib == null || versionedLib.size() <= 0) {
            return packageName2;
        }
        LongSparseLongArray versionsCallerCanSee = null;
        int callingAppId = UserHandle.getAppId(Binder.getCallingUid());
        if (callingAppId != 1000 && callingAppId != SHELL_UID && callingAppId != 0) {
            versionsCallerCanSee = new LongSparseLongArray();
            String libName = versionedLib.valueAt(0).getName();
            String[] uidPackages = getPackagesForUid(Binder.getCallingUid());
            if (uidPackages != null) {
                for (String uidPackage : uidPackages) {
                    PackageSetting ps = this.mSettings.getPackageLPr(uidPackage);
                    int libIdx = ArrayUtils.indexOf(ps.usesStaticLibraries, libName);
                    if (libIdx >= 0) {
                        long libVersion = ps.usesStaticLibrariesVersions[libIdx];
                        versionsCallerCanSee.append(libVersion, libVersion);
                    }
                }
            }
        }
        if (versionsCallerCanSee != null && versionsCallerCanSee.size() <= 0) {
            return packageName2;
        }
        SharedLibraryInfo highestVersion = null;
        int versionCount = versionedLib.size();
        for (int i = 0; i < versionCount; i++) {
            SharedLibraryInfo libraryInfo = versionedLib.valueAt(i);
            if (versionsCallerCanSee == null || versionsCallerCanSee.indexOfKey(libraryInfo.getLongVersion()) >= 0) {
                long libVersionCode = libraryInfo.getDeclaringPackage().getLongVersionCode();
                if (versionCode != -1) {
                    if (libVersionCode == versionCode) {
                        return libraryInfo.getPackageName();
                    }
                } else if (highestVersion == null) {
                    highestVersion = libraryInfo;
                } else if (libVersionCode > highestVersion.getDeclaringPackage().getLongVersionCode()) {
                    highestVersion = libraryInfo;
                }
            }
        }
        if (highestVersion != null) {
            return highestVersion.getPackageName();
        }
        return packageName2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCallerVerifier(int callingUid) {
        int callingUserId = UserHandle.getUserId(callingUid);
        String str = this.mRequiredVerifierPackage;
        return str != null && callingUid == getPackageUid(str, 0, callingUserId);
    }

    private boolean isCallerAllowedToSilentlyUninstall(int callingUid, String pkgName) {
        if (callingUid == SHELL_UID || callingUid == 0 || UserHandle.getAppId(callingUid) == 1000) {
            return true;
        }
        int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUid == getPackageUid(getInstallerPackageName(pkgName), 0, callingUserId)) {
            return true;
        }
        String str = this.mRequiredVerifierPackage;
        if (str != null && callingUid == getPackageUid(str, 0, callingUserId)) {
            return true;
        }
        String str2 = this.mRequiredUninstallerPackage;
        if (str2 != null && callingUid == getPackageUid(str2, 0, callingUserId)) {
            return true;
        }
        String str3 = this.mStorageManagerPackage;
        return (str3 != null && callingUid == getPackageUid(str3, 0, callingUserId)) || checkUidPermission("android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS", callingUid) == 0;
    }

    private int[] getBlockUninstallForUsers(String packageName, int[] userIds) {
        int[] result = EMPTY_INT_ARRAY;
        for (int userId : userIds) {
            if (getBlockUninstallForUser(packageName, userId)) {
                result = ArrayUtils.appendInt(result, userId);
            }
        }
        return result;
    }

    public boolean isPackageDeviceAdminOnAnyUser(String packageName) {
        int callingUid = Binder.getCallingUid();
        if (checkUidPermission("android.permission.MANAGE_USERS", callingUid) != 0) {
            EventLog.writeEvent(1397638484, "128599183", -1, "");
            throw new SecurityException("android.permission.MANAGE_USERS permission is required to call this API");
        } else if (getInstantAppPackageName(callingUid) == null || isCallerSameApp(packageName, callingUid)) {
            return isPackageDeviceAdmin(packageName, -1);
        } else {
            return false;
        }
    }

    private boolean isPackageDeviceAdmin(String packageName, int userId) {
        IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(ServiceManager.getService("device_policy"));
        if (dpm != null) {
            try {
                ComponentName deviceOwnerComponentName = dpm.getDeviceOwnerComponent(false);
                String deviceOwnerPackageName = deviceOwnerComponentName == null ? null : deviceOwnerComponentName.getPackageName();
                if (packageName.equals(deviceOwnerPackageName)) {
                    return true;
                }
                int[] users = userId == -1 ? sUserManager.getUserIds() : new int[]{userId};
                for (int i : users) {
                    if (dpm.packageHasActiveAdmins(packageName, i)) {
                        return true;
                    }
                }
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    private boolean shouldKeepUninstalledPackageLPr(String packageName) {
        List<String> list = this.mKeepUninstalledPackages;
        return list != null && list.contains(packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:195:0x0179 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int deletePackageX(java.lang.String r30, long r31, int r33, int r34) {
        /*
            Method dump skipped, instructions count: 756
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.deletePackageX(java.lang.String, long, int, int):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class PackageRemovedInfo {
        ArrayMap<String, PackageInstalledInfo> appearedChildPackages;
        boolean dataRemoved;
        SparseArray<Integer> installReasons;
        String installerPackageName;
        boolean isStaticSharedLib;
        boolean isUpdate;
        int[] origUsers;
        final PackageSender packageSender;
        ArrayMap<String, PackageRemovedInfo> removedChildPackages;
        boolean removedForAllUsers;
        String removedPackage;
        int uid = -1;
        int removedAppId = -1;
        int[] removedUsers = null;
        int[] broadcastUsers = null;
        int[] instantUserIds = null;
        boolean isRemovedPackageSystemUpdate = false;
        InstallArgs args = null;

        PackageRemovedInfo(PackageSender packageSender) {
            this.packageSender = packageSender;
        }

        void sendPackageRemovedBroadcasts(boolean killApp) {
            sendPackageRemovedBroadcastInternal(killApp);
            ArrayMap<String, PackageRemovedInfo> arrayMap = this.removedChildPackages;
            int childCount = arrayMap != null ? arrayMap.size() : 0;
            for (int i = 0; i < childCount; i++) {
                PackageRemovedInfo childInfo = this.removedChildPackages.valueAt(i);
                childInfo.sendPackageRemovedBroadcastInternal(killApp);
            }
        }

        void sendSystemPackageUpdatedBroadcasts() {
            if (this.isRemovedPackageSystemUpdate) {
                sendSystemPackageUpdatedBroadcastsInternal();
                ArrayMap<String, PackageRemovedInfo> arrayMap = this.removedChildPackages;
                int childCount = arrayMap != null ? arrayMap.size() : 0;
                for (int i = 0; i < childCount; i++) {
                    PackageRemovedInfo childInfo = this.removedChildPackages.valueAt(i);
                    if (childInfo.isRemovedPackageSystemUpdate) {
                        childInfo.sendSystemPackageUpdatedBroadcastsInternal();
                    }
                }
            }
        }

        void sendSystemPackageAppearedBroadcasts() {
            ArrayMap<String, PackageInstalledInfo> arrayMap = this.appearedChildPackages;
            int packageCount = arrayMap != null ? arrayMap.size() : 0;
            for (int i = 0; i < packageCount; i++) {
                PackageInstalledInfo installedInfo = this.appearedChildPackages.valueAt(i);
                this.packageSender.sendPackageAddedForNewUsers(installedInfo.name, true, false, UserHandle.getAppId(installedInfo.uid), installedInfo.newUsers, null);
            }
        }

        private void sendSystemPackageUpdatedBroadcastsInternal() {
            Bundle extras = new Bundle(2);
            int i = this.removedAppId;
            if (i < 0) {
                i = this.uid;
            }
            extras.putInt("android.intent.extra.UID", i);
            extras.putBoolean("android.intent.extra.REPLACING", true);
            this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", this.removedPackage, extras, 0, null, null, null, null);
            this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", this.removedPackage, extras, 0, null, null, null, null);
            this.packageSender.sendPackageBroadcast("android.intent.action.MY_PACKAGE_REPLACED", null, null, 0, this.removedPackage, null, null, null);
            String str = this.installerPackageName;
            if (str != null) {
                this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", this.removedPackage, extras, 0, str, null, null, null);
                this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", this.removedPackage, extras, 0, this.installerPackageName, null, null, null);
            }
        }

        private void sendPackageRemovedBroadcastInternal(boolean killApp) {
            if (this.isStaticSharedLib) {
                return;
            }
            Bundle extras = new Bundle(2);
            int removedUid = this.removedAppId;
            if (removedUid < 0) {
                removedUid = this.uid;
            }
            extras.putInt("android.intent.extra.UID", removedUid);
            extras.putBoolean("android.intent.extra.DATA_REMOVED", this.dataRemoved);
            extras.putBoolean("android.intent.extra.DONT_KILL_APP", !killApp);
            if (this.isUpdate || this.isRemovedPackageSystemUpdate) {
                extras.putBoolean("android.intent.extra.REPLACING", true);
            }
            extras.putBoolean("android.intent.extra.REMOVED_FOR_ALL_USERS", this.removedForAllUsers);
            String str = this.removedPackage;
            if (str != null) {
                this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REMOVED", str, extras, 0, null, null, this.broadcastUsers, this.instantUserIds);
                String str2 = this.installerPackageName;
                if (str2 != null) {
                    this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REMOVED", this.removedPackage, extras, 0, str2, null, this.broadcastUsers, this.instantUserIds);
                }
                if (this.dataRemoved && !this.isRemovedPackageSystemUpdate) {
                    this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_FULLY_REMOVED", this.removedPackage, extras, 16777216, null, null, this.broadcastUsers, this.instantUserIds);
                    this.packageSender.notifyPackageRemoved(this.removedPackage, removedUid);
                }
            }
            if (this.removedAppId >= 0) {
                if (extras.getBoolean("android.intent.extra.REPLACING", false)) {
                    extras.putString("android.intent.extra.PACKAGE_NAME", this.removedPackage);
                }
                this.packageSender.sendPackageBroadcast("android.intent.action.UID_REMOVED", null, extras, 16777216, null, null, this.broadcastUsers, this.instantUserIds);
            }
        }

        void populateUsers(int[] userIds, PackageSetting deletedPackageSetting) {
            this.removedUsers = userIds;
            if (this.removedUsers != null) {
                this.broadcastUsers = PackageManagerService.EMPTY_INT_ARRAY;
                this.instantUserIds = PackageManagerService.EMPTY_INT_ARRAY;
                for (int i = userIds.length - 1; i >= 0; i--) {
                    int userId = userIds[i];
                    if (deletedPackageSetting.getInstantApp(userId)) {
                        this.instantUserIds = ArrayUtils.appendInt(this.instantUserIds, userId);
                    } else {
                        this.broadcastUsers = ArrayUtils.appendInt(this.broadcastUsers, userId);
                    }
                }
                return;
            }
            this.broadcastUsers = null;
        }
    }

    private void removePackageDataLIF(final PackageSetting deletedPs, int[] allUserHandles, PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        PackageParser.Package resolvedPkg;
        String packageName = deletedPs.name;
        PackageParser.Package deletedPkg = deletedPs.pkg;
        if (outInfo != null) {
            outInfo.removedPackage = packageName;
            outInfo.installerPackageName = deletedPs.installerPackageName;
            outInfo.isStaticSharedLib = (deletedPkg == null || deletedPkg.staticSharedLibName == null) ? false : true;
            outInfo.populateUsers(deletedPs.queryInstalledUsers(sUserManager.getUserIds(), true), deletedPs);
        }
        removePackageLI(deletedPs.name, (flags & Integer.MIN_VALUE) != 0);
        if ((flags & 1) == 0) {
            if (deletedPkg != null) {
                resolvedPkg = deletedPkg;
            } else {
                resolvedPkg = new PackageParser.Package(deletedPs.name);
                resolvedPkg.setVolumeUuid(deletedPs.volumeUuid);
            }
            destroyAppDataLIF(resolvedPkg, -1, 7);
            destroyAppProfilesLIF(resolvedPkg);
            if (outInfo != null) {
                outInfo.dataRemoved = true;
            }
        }
        int removedAppId = -1;
        boolean installedStateChanged = false;
        if ((flags & 1) == 0) {
            SparseBooleanArray changedUsers = new SparseBooleanArray();
            synchronized (this.mPackages) {
                try {
                    try {
                        clearIntentFilterVerificationsLPw(deletedPs.name, -1, true);
                        clearDefaultBrowserIfNeeded(packageName);
                        this.mSettings.mKeySetManagerService.removeAppKeySetDataLPw(packageName);
                        removedAppId = this.mSettings.removePackageLPw(packageName);
                        if (outInfo != null) {
                            try {
                                outInfo.removedAppId = removedAppId;
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        }
                        this.mPermissionManager.updatePermissions(deletedPs.name, null, false, this.mPackages.values(), this.mPermissionCallback);
                        if (deletedPs.sharedUser != null) {
                            int[] userIds = UserManagerService.getInstance().getUserIds();
                            int length = userIds.length;
                            boolean shouldKill = false;
                            int i = 0;
                            while (i < length) {
                                int userIdToKill = this.mSettings.updateSharedUserPermsLPw(deletedPs, userIds[i]);
                                String packageName2 = packageName;
                                shouldKill |= userIdToKill == -1 || userIdToKill >= 0;
                                i++;
                                packageName = packageName2;
                            }
                            if (shouldKill) {
                                this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$gcU1RKL3rEcBWLR2OlkcyejcM9M
                                    @Override // java.lang.Runnable
                                    public final void run() {
                                        PackageManagerService.this.lambda$removePackageDataLIF$21$PackageManagerService(deletedPs);
                                    }
                                });
                            }
                        }
                        clearPackagePreferredActivitiesLPw(deletedPs.name, changedUsers, -1);
                        if (changedUsers.size() > 0) {
                            updateDefaultHomeNotLocked(changedUsers);
                            postPreferredActivityChangedBroadcast(-1);
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
        }
        if (allUserHandles != null && outInfo != null && outInfo.origUsers != null) {
            for (int userId : allUserHandles) {
                boolean installed = ArrayUtils.contains(outInfo.origUsers, userId);
                if (installed != deletedPs.getInstalled(userId)) {
                    installedStateChanged = true;
                }
                deletedPs.setInstalled(installed, userId);
            }
        }
        synchronized (this.mPackages) {
            if (writeSettings) {
                try {
                    this.mSettings.writeLPr();
                } catch (Throwable th4) {
                    throw th4;
                }
            }
            if (installedStateChanged) {
                this.mSettings.writeKernelMappingLPr(deletedPs);
            }
        }
        if (removedAppId != -1) {
            removeKeystoreDataIfNeeded(-1, removedAppId);
        }
    }

    public /* synthetic */ void lambda$removePackageDataLIF$21$PackageManagerService(PackageSetting deletedPs) {
        killApplication(deletedPs.name, deletedPs.appId, KILL_APP_REASON_GIDS_CHANGED);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean locationIsPrivileged(String path) {
        try {
            File privilegedAppDir = new File(Environment.getRootDirectory(), "priv-app");
            File privilegedVendorAppDir = new File(Environment.getVendorDirectory(), "priv-app");
            File privilegedOdmAppDir = new File(Environment.getOdmDirectory(), "priv-app");
            File privilegedProductAppDir = new File(Environment.getProductDirectory(), "priv-app");
            File privilegedProductServicesAppDir = new File(Environment.getProductServicesDirectory(), "priv-app");
            if (!path.startsWith(privilegedAppDir.getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER)) {
                if (!path.startsWith(privilegedVendorAppDir.getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER)) {
                    if (!path.startsWith(privilegedOdmAppDir.getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER)) {
                        if (!path.startsWith(privilegedProductAppDir.getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER)) {
                            if (!path.startsWith(privilegedProductServicesAppDir.getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER)) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    static boolean locationIsOem(String path) {
        try {
            return path.startsWith(Environment.getOemDirectory().getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    static boolean locationIsVendor(String path) {
        try {
            if (!path.startsWith(Environment.getVendorDirectory().getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER)) {
                if (!path.startsWith(Environment.getOdmDirectory().getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    static boolean locationIsProduct(String path) {
        try {
            return path.startsWith(Environment.getProductDirectory().getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    static boolean locationIsProductServices(String path) {
        try {
            return path.startsWith(Environment.getProductServicesDirectory().getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    static boolean locationIsOdm(String path) {
        try {
            return path.startsWith(Environment.getOdmDirectory().getCanonicalPath() + SliceClientPermissions.SliceAuthority.DELIMITER);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    private void deleteSystemPackageLIF(DeletePackageAction action, PackageSetting deletedPs, int[] allUserHandles, int flags, PackageRemovedInfo outInfo, boolean writeSettings) throws SystemDeleteException {
        int flags2;
        PackageSetting stubPs;
        PackageRemovedInfo childInfo;
        boolean z = (allUserHandles == null || outInfo == null || outInfo.origUsers == null) ? false : true;
        PackageParser.Package deletedPkg = deletedPs.pkg;
        PackageSetting disabledPs = action.disabledPs;
        Slog.d(TAG, "Deleting system pkg from data partition");
        if (outInfo != null) {
            outInfo.isRemovedPackageSystemUpdate = true;
            if (outInfo.removedChildPackages != null) {
                int childCount = deletedPs.childPackageNames != null ? deletedPs.childPackageNames.size() : 0;
                for (int i = 0; i < childCount; i++) {
                    String childPackageName = deletedPs.childPackageNames.get(i);
                    if (disabledPs.childPackageNames != null && disabledPs.childPackageNames.contains(childPackageName) && (childInfo = outInfo.removedChildPackages.get(childPackageName)) != null) {
                        childInfo.isRemovedPackageSystemUpdate = true;
                    }
                }
            }
        }
        if (disabledPs.versionCode < deletedPs.versionCode) {
            flags2 = flags & (-2);
        } else {
            flags2 = flags | 1;
        }
        deleteInstalledPackageLIF(deletedPs, true, flags2, allUserHandles, outInfo, writeSettings, disabledPs.pkg);
        synchronized (this.mPackages) {
            enableSystemPackageLPw(disabledPs.pkg);
            removeNativeBinariesLI(deletedPs);
        }
        try {
            try {
                installPackageFromSystemLIF(disabledPs.codePathString, allUserHandles, outInfo == null ? null : outInfo.origUsers, deletedPs.getPermissionsState(), writeSettings);
            } catch (PackageManagerException e) {
                Slog.w(TAG, "Failed to restore system package:" + deletedPkg.packageName + ": " + e.getMessage());
                throw new SystemDeleteException(e, null);
            }
        } finally {
            if (disabledPs.pkg.isStub && (stubPs = this.mSettings.mPackages.get(deletedPkg.packageName)) != null) {
                stubPs.setEnabled(2, 0, PLATFORM_PACKAGE_NAME);
            }
        }
    }

    private PackageParser.Package installPackageFromSystemLIF(String codePathString, int[] allUserHandles, int[] origUserHandles, PermissionsState origPermissionState, boolean writeSettings) throws PackageManagerException {
        int scanFlags;
        boolean z = true;
        int parseFlags = this.mDefParseFlags | 1 | 16;
        int scanFlags2 = locationIsPrivileged(codePathString) ? 131072 | 262144 : 131072;
        if (locationIsOem(codePathString)) {
            scanFlags2 |= 524288;
        }
        if (locationIsVendor(codePathString)) {
            scanFlags2 |= 1048576;
        }
        if (locationIsProduct(codePathString)) {
            scanFlags2 |= 2097152;
        }
        if (locationIsProductServices(codePathString)) {
            scanFlags2 |= 4194304;
        }
        if (!locationIsOdm(codePathString)) {
            scanFlags = scanFlags2;
        } else {
            scanFlags = scanFlags2 | 8388608;
        }
        File codePath = new File(codePathString);
        PackageParser.Package pkg = scanPackageTracedLI(codePath, parseFlags, scanFlags, 0L, (UserHandle) null);
        try {
            updateSharedLibrariesLocked(pkg, null, Collections.unmodifiableMap(this.mPackages));
        } catch (PackageManagerException e) {
            Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
        }
        prepareAppDataAfterInstallLIF(pkg);
        synchronized (this.mPackages) {
            try {
                PackageSetting ps = this.mSettings.mPackages.get(pkg.packageName);
                if (origPermissionState != null) {
                    try {
                        ps.getPermissionsState().copyFrom(origPermissionState);
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
                this.mPermissionManager.updatePermissions(pkg.packageName, pkg, true, this.mPackages.values(), this.mPermissionCallback);
                if (allUserHandles == null || origUserHandles == null) {
                    z = false;
                }
                boolean applyUserRestrictions = z;
                if (applyUserRestrictions) {
                    int length = allUserHandles.length;
                    boolean installedStateChanged = false;
                    int i = 0;
                    while (i < length) {
                        int userId = allUserHandles[i];
                        boolean installed = ArrayUtils.contains(origUserHandles, userId);
                        File codePath2 = codePath;
                        try {
                            boolean applyUserRestrictions2 = applyUserRestrictions;
                            if (installed != ps.getInstalled(userId)) {
                                installedStateChanged = true;
                            }
                            ps.setInstalled(installed, userId);
                            this.mSettings.writeRuntimePermissionsForUserLPr(userId, false);
                            i++;
                            codePath = codePath2;
                            applyUserRestrictions = applyUserRestrictions2;
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                    this.mSettings.writeAllUsersPackageRestrictionsLPr();
                    if (installedStateChanged) {
                        this.mSettings.writeKernelMappingLPr(ps);
                    }
                }
                if (writeSettings) {
                    this.mSettings.writeLPr();
                }
                return pkg;
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private void deleteInstalledPackageLIF(PackageSetting ps, boolean deleteCodeAndResources, int flags, int[] allUserHandles, PackageRemovedInfo outInfo, boolean writeSettings, PackageParser.Package replacingPackage) {
        PackageSetting childPs;
        int childCount;
        synchronized (this.mPackages) {
            if (outInfo != null) {
                try {
                    outInfo.uid = ps.appId;
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (outInfo != null && outInfo.removedChildPackages != null) {
                if (ps.childPackageNames == null) {
                    childCount = 0;
                } else {
                    childCount = ps.childPackageNames.size();
                }
                for (int i = 0; i < childCount; i++) {
                    String childPackageName = ps.childPackageNames.get(i);
                    PackageSetting childPs2 = this.mSettings.mPackages.get(childPackageName);
                    PackageRemovedInfo childInfo = outInfo.removedChildPackages.get(childPackageName);
                    if (childInfo != null) {
                        childInfo.uid = childPs2.appId;
                    }
                }
            }
        }
        removePackageDataLIF(ps, allUserHandles, outInfo, flags, writeSettings);
        int childCount2 = ps.childPackageNames != null ? ps.childPackageNames.size() : 0;
        for (int i2 = 0; i2 < childCount2; i2++) {
            synchronized (this.mPackages) {
                childPs = this.mSettings.getPackageLPr(ps.childPackageNames.get(i2));
            }
            if (childPs != null) {
                PackageRemovedInfo childOutInfo = (outInfo == null || outInfo.removedChildPackages == null) ? null : outInfo.removedChildPackages.get(childPs.name);
                int deleteFlags = ((flags & 1) == 0 || replacingPackage == null || replacingPackage.hasChildPackage(childPs.name)) ? flags : flags & (-2);
                removePackageDataLIF(childPs, allUserHandles, childOutInfo, deleteFlags, writeSettings);
            }
        }
        if (ps.parentPackageName == null && deleteCodeAndResources && outInfo != null) {
            outInfo.args = createInstallArgsForExisting(ps.codePathString, ps.resourcePathString, InstructionSets.getAppDexInstructionSets(ps));
        }
    }

    public boolean setBlockUninstallForUser(String packageName, boolean blockUninstall, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.DELETE_PACKAGES", null);
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg != null && pkg.staticSharedLibName != null) {
                Slog.w(TAG, "Cannot block uninstall of package: " + packageName + " providing static shared library: " + pkg.staticSharedLibName);
                return false;
            }
            this.mSettings.setBlockUninstallLPw(userId, packageName, blockUninstall);
            this.mSettings.writePackageRestrictionsLPr(userId);
            return true;
        }
    }

    public boolean getBlockUninstallForUser(String packageName, int userId) {
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps != null && !filterAppAccessLPr(ps, Binder.getCallingUid(), userId)) {
                return this.mSettings.getBlockUninstallLPr(userId, packageName);
            }
            return false;
        }
    }

    public boolean setRequiredForSystemUser(String packageName, boolean systemUserApp) {
        enforceSystemOrRoot("setRequiredForSystemUser can only be run by the system or root");
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps == null) {
                Log.w(TAG, "Package doesn't exist: " + packageName);
                return false;
            }
            if (systemUserApp) {
                ps.pkgPrivateFlags |= 512;
            } else {
                ps.pkgPrivateFlags &= -513;
            }
            this.mSettings.writeLPr();
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class DeletePackageAction {
        public final PackageSetting deletingPs;
        public final PackageSetting disabledPs;
        public final int flags;
        public final PackageRemovedInfo outInfo;
        public final UserHandle user;

        /* synthetic */ DeletePackageAction(PackageSetting x0, PackageSetting x1, PackageRemovedInfo x2, int x3, UserHandle x4, AnonymousClass1 x5) {
            this(x0, x1, x2, x3, x4);
        }

        private DeletePackageAction(PackageSetting deletingPs, PackageSetting disabledPs, PackageRemovedInfo outInfo, int flags, UserHandle user) {
            this.deletingPs = deletingPs;
            this.disabledPs = disabledPs;
            this.outInfo = outInfo;
            this.flags = flags;
            this.user = user;
        }
    }

    @GuardedBy({"mPackages"})
    private static DeletePackageAction mayDeletePackageLocked(PackageRemovedInfo outInfo, PackageSetting ps, PackageSetting disabledPs, PackageSetting[] children, int flags, UserHandle user) {
        if (ps == null) {
            return null;
        }
        if (isSystemApp(ps)) {
            if (ps.parentPackageName != null) {
                Slog.w(TAG, "Attempt to delete child system package " + ps.pkg.packageName);
                return null;
            }
            boolean deleteAllUsers = true;
            boolean deleteSystem = (flags & 4) != 0;
            if (user != null && user.getIdentifier() != -1) {
                deleteAllUsers = false;
            }
            if ((!deleteSystem || deleteAllUsers) && disabledPs == null) {
                Slog.w(TAG, "Attempt to delete unknown system package " + ps.pkg.packageName);
                return null;
            }
        }
        int parentReferenceCount = ps.childPackageNames != null ? ps.childPackageNames.size() : 0;
        int childCount = children != null ? children.length : 0;
        if (childCount != parentReferenceCount) {
            return null;
        }
        if (childCount != 0 && outInfo != null && outInfo.removedChildPackages != null) {
            for (PackageSetting child : children) {
                if (child == null || !ps.childPackageNames.contains(child.name)) {
                    return null;
                }
            }
        }
        return new DeletePackageAction(ps, disabledPs, outInfo, flags, user, null);
    }

    private boolean deletePackageLIF(String packageName, UserHandle user, boolean deleteCodeAndResources, int[] allUserHandles, int flags, PackageRemovedInfo outInfo, boolean writeSettings, PackageParser.Package replacingPackage) {
        synchronized (this.mPackages) {
            try {
                try {
                    PackageSetting ps = this.mSettings.mPackages.get(packageName);
                    PackageSetting disabledPs = this.mSettings.getDisabledSystemPkgLPr(ps);
                    PackageSetting[] children = this.mSettings.getChildSettingsLPr(ps);
                    DeletePackageAction action = mayDeletePackageLocked(outInfo, ps, disabledPs, children, flags, user);
                    if (action == null) {
                        return false;
                    }
                    try {
                        executeDeletePackageLIF(action, packageName, deleteCodeAndResources, allUserHandles, writeSettings, replacingPackage);
                        return true;
                    } catch (SystemDeleteException e) {
                        return false;
                    }
                } catch (Throwable th) {
                    e = th;
                    throw e;
                }
            } catch (Throwable th2) {
                e = th2;
                throw e;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class SystemDeleteException extends Exception {
        public final PackageManagerException reason;

        /* synthetic */ SystemDeleteException(PackageManagerException x0, AnonymousClass1 x1) {
            this(x0);
        }

        private SystemDeleteException(PackageManagerException reason) {
            this.reason = reason;
        }
    }

    private void executeDeletePackageLIF(DeletePackageAction action, String packageName, boolean deleteCodeAndResources, int[] allUserHandles, boolean writeSettings, PackageParser.Package replacingPackage) throws SystemDeleteException {
        boolean keepUninstalledPackage;
        PackageSetting childPs;
        int childCount;
        PackageSetting ps = action.deletingPs;
        PackageRemovedInfo outInfo = action.outInfo;
        UserHandle user = action.user;
        int flags = action.flags;
        boolean systemApp = isSystemApp(ps);
        if (ps.parentPackageName != null && (!systemApp || (flags & 4) != 0)) {
            int removedUserId = user != null ? user.getIdentifier() : -1;
            clearPackageStateForUserLIF(ps, removedUserId, outInfo, flags);
            synchronized (this.mPackages) {
                markPackageUninstalledForUserLPw(ps, user);
                scheduleWritePackageRestrictionsLocked(user);
            }
            return;
        }
        int userId = user == null ? -1 : user.getIdentifier();
        if (checkPermission("android.permission.SUSPEND_APPS", packageName, userId) == 0) {
            unsuspendForSuspendingPackage(packageName, userId);
        }
        if ((!systemApp || (flags & 4) != 0) && userId != -1) {
            synchronized (this.mPackages) {
                markPackageUninstalledForUserLPw(ps, user);
                if (!systemApp) {
                    boolean keepUninstalledPackage2 = shouldKeepUninstalledPackageLPr(packageName);
                    if (!ps.isAnyInstalled(sUserManager.getUserIds()) && !keepUninstalledPackage2) {
                        ps.setInstalled(true, userId);
                        this.mSettings.writeKernelMappingLPr(ps);
                        keepUninstalledPackage = false;
                    }
                    keepUninstalledPackage = true;
                } else {
                    keepUninstalledPackage = true;
                }
            }
            if (keepUninstalledPackage) {
                clearPackageStateForUserLIF(ps, userId, outInfo, flags);
                synchronized (this.mPackages) {
                    scheduleWritePackageRestrictionsLocked(user);
                }
                return;
            }
        }
        if (ps.childPackageNames != null && outInfo != null) {
            synchronized (this.mPackages) {
                int childCount2 = ps.childPackageNames.size();
                outInfo.removedChildPackages = new ArrayMap<>(childCount2);
                int i = 0;
                while (i < childCount2) {
                    String childPackageName = ps.childPackageNames.get(i);
                    PackageRemovedInfo childInfo = new PackageRemovedInfo(this);
                    childInfo.removedPackage = childPackageName;
                    childInfo.installerPackageName = ps.installerPackageName;
                    outInfo.removedChildPackages.put(childPackageName, childInfo);
                    PackageSetting childPs2 = this.mSettings.getPackageLPr(childPackageName);
                    if (childPs2 == null) {
                        childCount = childCount2;
                    } else {
                        childCount = childCount2;
                        childInfo.origUsers = childPs2.queryInstalledUsers(allUserHandles, true);
                    }
                    i++;
                    childCount2 = childCount;
                }
            }
        }
        if (systemApp) {
            deleteSystemPackageLIF(action, ps, allUserHandles, flags, outInfo, writeSettings);
        } else {
            deleteInstalledPackageLIF(ps, deleteCodeAndResources, flags, allUserHandles, outInfo, writeSettings, replacingPackage);
        }
        if (outInfo != null) {
            outInfo.removedForAllUsers = this.mPackages.get(ps.name) == null;
            if (outInfo.removedChildPackages != null) {
                synchronized (this.mPackages) {
                    int childCount3 = outInfo.removedChildPackages.size();
                    for (int i2 = 0; i2 < childCount3; i2++) {
                        PackageRemovedInfo childInfo2 = outInfo.removedChildPackages.valueAt(i2);
                        if (childInfo2 != null) {
                            childInfo2.removedForAllUsers = this.mPackages.get(childInfo2.removedPackage) == null;
                        }
                    }
                }
            }
            if (systemApp) {
                synchronized (this.mPackages) {
                    PackageSetting updatedPs = this.mSettings.getPackageLPr(ps.name);
                    int childCount4 = updatedPs.childPackageNames != null ? updatedPs.childPackageNames.size() : 0;
                    for (int i3 = 0; i3 < childCount4; i3++) {
                        String childPackageName2 = updatedPs.childPackageNames.get(i3);
                        if ((outInfo.removedChildPackages == null || outInfo.removedChildPackages.indexOfKey(childPackageName2) < 0) && (childPs = this.mSettings.getPackageLPr(childPackageName2)) != null) {
                            PackageInstalledInfo installRes = new PackageInstalledInfo();
                            installRes.name = childPackageName2;
                            installRes.newUsers = childPs.queryInstalledUsers(allUserHandles, true);
                            installRes.pkg = this.mPackages.get(childPackageName2);
                            installRes.uid = childPs.pkg.applicationInfo.uid;
                            if (outInfo.appearedChildPackages == null) {
                                outInfo.appearedChildPackages = new ArrayMap<>();
                            }
                            outInfo.appearedChildPackages.put(childPackageName2, installRes);
                        }
                    }
                }
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void markPackageUninstalledForUserLPw(PackageSetting ps, UserHandle user) {
        PackageSetting packageSetting = ps;
        int[] userIds = (user == null || user.getIdentifier() == -1) ? sUserManager.getUserIds() : new int[]{user.getIdentifier()};
        int length = userIds.length;
        int i = 0;
        while (i < length) {
            int nextUserId = userIds[i];
            packageSetting = ps;
            packageSetting.setUserState(nextUserId, 0L, 0, false, true, true, false, 0, false, null, null, null, null, false, false, null, null, null, packageSetting.readUserState(nextUserId).domainVerificationStatus, 0, 0, null);
            i++;
            length = length;
            userIds = userIds;
        }
        this.mSettings.writeKernelMappingLPr(ps);
    }

    private void clearPackageStateForUserLIF(PackageSetting ps, int userId, PackageRemovedInfo outInfo, int flags) {
        PackageParser.Package pkg;
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(ps.name);
        }
        destroyAppProfilesLIF(pkg);
        boolean z = false;
        int[] userIds = userId == -1 ? sUserManager.getUserIds() : new int[]{userId};
        for (int nextUserId : userIds) {
            destroyAppDataLIF(pkg, nextUserId, 7);
            clearDefaultBrowserIfNeededForUser(ps.name, nextUserId);
            removeKeystoreDataIfNeeded(nextUserId, ps.appId);
            SparseBooleanArray changedUsers = new SparseBooleanArray();
            clearPackagePreferredActivitiesLPw(ps.name, changedUsers, nextUserId);
            if (changedUsers.size() > 0) {
                updateDefaultHomeNotLocked(changedUsers);
                postPreferredActivityChangedBroadcast(nextUserId);
                synchronized (this.mPackages) {
                    scheduleWritePackageRestrictionsLocked(nextUserId);
                }
            }
            synchronized (this.mPackages) {
                resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, nextUserId);
            }
            if ((flags & 16) != 0) {
                try {
                    MediaStore.deleteContributedMedia(this.mContext, ps.name, UserHandle.of(nextUserId));
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to delete contributed media for " + ps.name, e);
                }
            }
        }
        if (outInfo != null) {
            outInfo.removedPackage = ps.name;
            outInfo.installerPackageName = ps.installerPackageName;
            if (pkg != null && pkg.staticSharedLibName != null) {
                z = true;
            }
            outInfo.isStaticSharedLib = z;
            outInfo.removedAppId = ps.appId;
            outInfo.removedUsers = userIds;
            outInfo.broadcastUsers = userIds;
        }
    }

    public void clearApplicationProfileData(String packageName) {
        PackageParser.Package pkg;
        enforceSystemOrRoot("Only the system can clear all profile data");
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(packageName);
        }
        PackageFreezer freezer = freezePackage(packageName, "clearApplicationProfileData");
        try {
            synchronized (this.mInstallLock) {
                clearAppProfilesLIF(pkg, -1);
            }
            if (freezer != null) {
                $closeResource(null, freezer);
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (freezer != null) {
                    $closeResource(th, freezer);
                }
                throw th2;
            }
        }
    }

    public void clearApplicationUserData(final String packageName, final IPackageDataObserver observer, final int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.CLEAR_APP_USER_DATA", null);
        int callingUid = Binder.getCallingUid();
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, false, "clear application data");
        PackageSetting ps = this.mSettings.getPackageLPr(packageName);
        boolean filterApp = ps != null && filterAppAccessLPr(ps, callingUid, userId);
        if (!filterApp && this.mProtectedPackages.isPackageDataProtected(userId, packageName)) {
            throw new SecurityException("Cannot clear data for a protected package: " + packageName);
        }
        final boolean z = filterApp;
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.4
            @Override // java.lang.Runnable
            public void run() {
                boolean succeeded;
                PackageManagerService.this.mHandler.removeCallbacks(this);
                if (!z) {
                    PackageFreezer freezer = PackageManagerService.this.freezePackage(packageName, "clearApplicationUserData");
                    try {
                        synchronized (PackageManagerService.this.mInstallLock) {
                            succeeded = PackageManagerService.this.clearApplicationUserDataLIF(packageName, userId);
                        }
                        synchronized (PackageManagerService.this.mPackages) {
                            PackageManagerService.this.mInstantAppRegistry.deleteInstantApplicationMetadataLPw(packageName, userId);
                        }
                        if (freezer != null) {
                            freezer.close();
                        }
                        if (succeeded) {
                            DeviceStorageMonitorInternal dsm = (DeviceStorageMonitorInternal) LocalServices.getService(DeviceStorageMonitorInternal.class);
                            if (dsm != null) {
                                dsm.checkMemory();
                            }
                            if (PackageManagerService.this.checkPermission("android.permission.SUSPEND_APPS", packageName, userId) == 0) {
                                PackageManagerService.this.unsuspendForSuspendingPackage(packageName, userId);
                            }
                        }
                    } catch (Throwable th) {
                        try {
                            throw th;
                        } catch (Throwable th2) {
                            if (freezer != null) {
                                try {
                                    freezer.close();
                                } catch (Throwable th3) {
                                    th.addSuppressed(th3);
                                }
                            }
                            throw th2;
                        }
                    }
                } else {
                    succeeded = false;
                }
                IPackageDataObserver iPackageDataObserver = observer;
                if (iPackageDataObserver != null) {
                    try {
                        iPackageDataObserver.onRemoveCompleted(packageName, succeeded);
                    } catch (RemoteException e) {
                        Log.i(PackageManagerService.TAG, "Observer no longer exists.");
                    }
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean clearApplicationUserDataLIF(String packageName, int userId) {
        int flags;
        PackageSetting ps;
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg == null && (ps = this.mSettings.mPackages.get(packageName)) != null) {
                pkg = ps.pkg;
            }
            if (pkg == null) {
                Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
                return false;
            }
            resetUserChangesToRuntimePermissionsAndFlagsLPw((PackageSetting) pkg.mExtras, userId);
            clearAppDataLIF(pkg, userId, 7);
            int appId = UserHandle.getAppId(pkg.applicationInfo.uid);
            removeKeystoreDataIfNeeded(userId, appId);
            UserManagerInternal umInternal = getUserManagerInternal();
            if (umInternal.isUserUnlockingOrUnlocked(userId)) {
                flags = 3;
            } else if (umInternal.isUserRunning(userId)) {
                flags = 1;
            } else {
                flags = 0;
            }
            prepareAppDataContentsLIF(pkg, userId, flags);
            return true;
        }
    }

    @GuardedBy({"mPackages"})
    private void resetUserChangesToRuntimePermissionsAndFlagsLPw(int userId) {
        int packageCount = this.mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageParser.Package pkg = this.mPackages.valueAt(i);
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, userId);
        }
    }

    private void resetNetworkPolicies(int userId) {
        ((NetworkPolicyManagerInternal) LocalServices.getService(NetworkPolicyManagerInternal.class)).resetUserState(userId);
    }

    @GuardedBy({"mPackages"})
    private void resetUserChangesToRuntimePermissionsAndFlagsLPw(PackageSetting ps, int userId) {
        int i;
        int permissionCount;
        int uid;
        int flags;
        SparseBooleanArray updatedUsers;
        ArraySet<Long> revokedPermissions;
        final int uid2;
        final AppOpsManager appOpsManager;
        if (ps.pkg == null) {
            return;
        }
        String packageName = ps.pkg.packageName;
        final boolean[] permissionRemoved = new boolean[1];
        final ArraySet<Long> revokedPermissions2 = new ArraySet<>();
        final SparseBooleanArray updatedUsers2 = new SparseBooleanArray();
        PermissionManagerServiceInternal.PermissionCallback delayingPermCallback = new PermissionManagerServiceInternal.PermissionCallback() { // from class: com.android.server.pm.PackageManagerService.5
            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onGidsChanged(int appId, int userId2) {
                PackageManagerService.this.mPermissionCallback.onGidsChanged(appId, userId2);
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onPermissionChanged() {
                PackageManagerService.this.mPermissionCallback.onPermissionChanged();
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onPermissionGranted(int uid3, int userId2) {
                PackageManagerService.this.mPermissionCallback.onPermissionGranted(uid3, userId2);
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onInstallPermissionGranted() {
                PackageManagerService.this.mPermissionCallback.onInstallPermissionGranted();
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onPermissionRevoked(int uid3, int userId2) {
                revokedPermissions2.add(Long.valueOf(IntPair.of(uid3, userId2)));
                updatedUsers2.put(userId2, true);
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onInstallPermissionRevoked() {
                PackageManagerService.this.mPermissionCallback.onInstallPermissionRevoked();
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onPermissionUpdated(int[] updatedUserIds, boolean sync) {
                for (int userId2 : updatedUserIds) {
                    if (sync) {
                        updatedUsers2.put(userId2, true);
                    } else if (!updatedUsers2.get(userId2)) {
                        updatedUsers2.put(userId2, false);
                    }
                }
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onPermissionRemoved() {
                permissionRemoved[0] = true;
            }

            @Override // com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback
            public void onInstallPermissionUpdated() {
                PackageManagerService.this.mPermissionCallback.onInstallPermissionUpdated();
            }
        };
        AppOpsManager appOpsManager2 = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        int uid3 = UserHandle.getUid(userId, ps.pkg.applicationInfo.uid);
        int permissionCount2 = ps.pkg.requestedPermissions.size();
        int i2 = 0;
        while (i2 < permissionCount2) {
            String permName = (String) ps.pkg.requestedPermissions.get(i2);
            BasePermission bp = this.mPermissionManager.getPermissionTEMP(permName);
            if (bp == null) {
                i = i2;
                permissionCount = permissionCount2;
                updatedUsers = updatedUsers2;
                revokedPermissions = revokedPermissions2;
                uid2 = uid3;
                appOpsManager = appOpsManager2;
            } else if (bp.isRemoved()) {
                i = i2;
                permissionCount = permissionCount2;
                updatedUsers = updatedUsers2;
                revokedPermissions = revokedPermissions2;
                uid2 = uid3;
                appOpsManager = appOpsManager2;
            } else {
                if (ps.sharedUser == null) {
                    i = i2;
                    permissionCount = permissionCount2;
                    uid = uid3;
                } else {
                    boolean used = false;
                    int packageCount = ps.sharedUser.packages.size();
                    i = i2;
                    int i3 = 0;
                    while (true) {
                        if (i3 >= packageCount) {
                            permissionCount = permissionCount2;
                            uid = uid3;
                            break;
                        }
                        int packageCount2 = packageCount;
                        PackageSetting pkg = ps.sharedUser.packages.valueAt(i3);
                        permissionCount = permissionCount2;
                        if (pkg.pkg == null) {
                            uid = uid3;
                        } else {
                            uid = uid3;
                            if (!pkg.pkg.packageName.equals(ps.pkg.packageName) && pkg.pkg.requestedPermissions.contains(permName)) {
                                used = true;
                                break;
                            }
                        }
                        i3++;
                        packageCount = packageCount2;
                        permissionCount2 = permissionCount;
                        uid3 = uid;
                    }
                    if (used) {
                        revokedPermissions = revokedPermissions2;
                        uid2 = uid;
                        updatedUsers = updatedUsers2;
                        appOpsManager = appOpsManager2;
                    }
                }
                int oldFlags = this.mPermissionManager.getPermissionFlags(permName, packageName, 1000, userId);
                if (ps.pkg.applicationInfo.targetSdkVersion < 23 && bp.isRuntime()) {
                    int flags2 = 0 | 72;
                    flags = flags2;
                } else {
                    flags = 0;
                }
                int uid4 = uid;
                AppOpsManager appOpsManager3 = appOpsManager2;
                updatedUsers = updatedUsers2;
                revokedPermissions = revokedPermissions2;
                this.mPermissionManager.updatePermissionFlags(permName, packageName, 75, flags, 1000, userId, false, delayingPermCallback);
                if (!bp.isRuntime()) {
                    uid2 = uid4;
                    appOpsManager = appOpsManager3;
                } else if ((oldFlags & 20) != 0) {
                    uid2 = uid4;
                    appOpsManager = appOpsManager3;
                } else if ((oldFlags & 32) != 0) {
                    this.mPermissionManager.grantRuntimePermission(permName, packageName, false, 1000, userId, delayingPermCallback);
                    final String appOp = AppOpsManager.permissionToOp(permName);
                    if (appOp == null) {
                        uid2 = uid4;
                        appOpsManager = appOpsManager3;
                    } else {
                        uid2 = uid4;
                        appOpsManager = appOpsManager3;
                        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$HaVha3_11KK8sVYnQRLIWTyWoDA
                            @Override // java.lang.Runnable
                            public final void run() {
                                appOpsManager.setUidMode(appOp, uid2, 0);
                            }
                        });
                    }
                } else {
                    uid2 = uid4;
                    appOpsManager = appOpsManager3;
                    if ((flags & 64) == 0) {
                        this.mPermissionManager.revokeRuntimePermission(permName, packageName, false, userId, delayingPermCallback);
                    }
                }
            }
            i2 = i + 1;
            appOpsManager2 = appOpsManager;
            uid3 = uid2;
            permissionCount2 = permissionCount;
            updatedUsers2 = updatedUsers;
            revokedPermissions2 = revokedPermissions;
        }
        SparseBooleanArray updatedUsers3 = updatedUsers2;
        ArraySet<Long> revokedPermissions3 = revokedPermissions2;
        if (permissionRemoved[0]) {
            this.mPermissionCallback.onPermissionRemoved();
        }
        if (!revokedPermissions3.isEmpty()) {
            int numRevokedPermissions = revokedPermissions3.size();
            for (int i4 = 0; i4 < numRevokedPermissions; i4++) {
                final int revocationUID = IntPair.first(revokedPermissions3.valueAt(i4).longValue());
                final int revocationUserId = IntPair.second(revokedPermissions3.valueAt(i4).longValue());
                this.mOnPermissionChangeListeners.onPermissionsChanged(revocationUID);
                this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$aAkPAkjRsEBVehb4PRgJ6d2jA18
                    @Override // java.lang.Runnable
                    public final void run() {
                        PackageManagerService.this.lambda$resetUserChangesToRuntimePermissionsAndFlagsLPw$23$PackageManagerService(revocationUID, revocationUserId);
                    }
                });
            }
        }
        int numUpdatedUsers = updatedUsers3.size();
        for (int i5 = 0; i5 < numUpdatedUsers; i5++) {
            this.mSettings.writeRuntimePermissionsForUserLPr(updatedUsers3.keyAt(i5), updatedUsers3.valueAt(i5));
        }
    }

    public /* synthetic */ void lambda$resetUserChangesToRuntimePermissionsAndFlagsLPw$23$PackageManagerService(int revocationUID, int revocationUserId) {
        killUid(UserHandle.getAppId(revocationUID), revocationUserId, KILL_APP_REASON_PERMISSIONS_REVOKED);
    }

    private static void removeKeystoreDataIfNeeded(int userId, int appId) {
        int[] userIds;
        if (appId < 0) {
            return;
        }
        KeyStore keyStore = KeyStore.getInstance();
        if (keyStore != null) {
            if (userId == -1) {
                for (int individual : sUserManager.getUserIds()) {
                    keyStore.clearUid(UserHandle.getUid(individual, appId));
                }
                return;
            }
            keyStore.clearUid(UserHandle.getUid(userId, appId));
            return;
        }
        Slog.w(TAG, "Could not contact keystore to clear entries for app id " + appId);
    }

    public void deleteApplicationCacheFiles(String packageName, IPackageDataObserver observer) {
        int userId = UserHandle.getCallingUserId();
        deleteApplicationCacheFilesAsUser(packageName, userId, observer);
    }

    public void deleteApplicationCacheFilesAsUser(final String packageName, final int userId, final IPackageDataObserver observer) {
        final PackageParser.Package pkg;
        final int callingUid = Binder.getCallingUid();
        if (this.mContext.checkCallingOrSelfPermission("android.permission.INTERNAL_DELETE_CACHE_FILES") != 0) {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.DELETE_CACHE_FILES") == 0) {
                Slog.w(TAG, "Calling uid " + callingUid + " does not have android.permission.INTERNAL_DELETE_CACHE_FILES, silently ignoring");
                return;
            }
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERNAL_DELETE_CACHE_FILES", null);
        }
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, false, "delete application cache files");
        final int hasAccessInstantApps = this.mContext.checkCallingOrSelfPermission("android.permission.ACCESS_INSTANT_APPS");
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(packageName);
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$wBrPST4WJu7X6dicfun32p3hExg
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$deleteApplicationCacheFilesAsUser$24$PackageManagerService(pkg, callingUid, hasAccessInstantApps, userId, observer, packageName);
            }
        });
    }

    public /* synthetic */ void lambda$deleteApplicationCacheFilesAsUser$24$PackageManagerService(PackageParser.Package pkg, int callingUid, int hasAccessInstantApps, int userId, IPackageDataObserver observer, String packageName) {
        PackageSetting ps = pkg == null ? null : (PackageSetting) pkg.mExtras;
        boolean doClearData = true;
        if (ps != null) {
            boolean targetIsInstantApp = ps.getInstantApp(UserHandle.getUserId(callingUid));
            doClearData = !targetIsInstantApp || hasAccessInstantApps == 0;
        }
        if (doClearData) {
            synchronized (this.mInstallLock) {
                clearAppDataLIF(pkg, userId, 23);
                clearAppDataLIF(pkg, userId, 39);
            }
        }
        if (observer != null) {
            try {
                observer.onRemoveCompleted(packageName, true);
            } catch (RemoteException e) {
                Log.i(TAG, "Observer no longer exists.");
            }
        }
    }

    public void getPackageSizeInfo(String packageName, int userHandle, IPackageStatsObserver observer) {
        throw new UnsupportedOperationException("Shame on you for calling the hidden API getPackageSizeInfo(). Shame!");
    }

    @GuardedBy({"mInstallLock"})
    private boolean getPackageSizeInfoLI(String packageName, int userId, PackageStats stats) {
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps == null) {
                Slog.w(TAG, "Failed to find settings for " + packageName);
                return false;
            }
            String[] packageNames = {packageName};
            long[] ceDataInodes = {ps.getCeDataInode(userId)};
            String[] codePaths = {ps.codePathString};
            try {
                this.mInstaller.getAppSize(ps.volumeUuid, packageNames, userId, 0, ps.appId, ceDataInodes, codePaths, stats);
                if (isSystemApp(ps) && !isUpdatedSystemApp(ps)) {
                    stats.codeSize = 0L;
                }
                stats.dataSize -= stats.cacheSize;
                return true;
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
                return false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public int getUidTargetSdkVersionLockedLPr(int uid) {
        int v;
        int appId = UserHandle.getAppId(uid);
        Object obj = this.mSettings.getSettingLPr(appId);
        if (obj instanceof SharedUserSetting) {
            SharedUserSetting sus = (SharedUserSetting) obj;
            int vers = 10000;
            Iterator<PackageSetting> it = sus.packages.iterator();
            while (it.hasNext()) {
                PackageSetting ps = it.next();
                if (ps.pkg != null && (v = ps.pkg.applicationInfo.targetSdkVersion) < vers) {
                    vers = v;
                }
            }
            return vers;
        } else if (obj instanceof PackageSetting) {
            PackageSetting ps2 = (PackageSetting) obj;
            if (ps2.pkg != null) {
                return ps2.pkg.applicationInfo.targetSdkVersion;
            }
            return 10000;
        } else {
            return 10000;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public int getPackageTargetSdkVersionLockedLPr(String packageName) {
        PackageParser.Package p = this.mPackages.get(packageName);
        if (p != null) {
            return p.applicationInfo.targetSdkVersion;
        }
        return 10000;
    }

    public void addPreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity, int userId) {
        addPreferredActivityInternal(filter, match, set, activity, true, userId, "Adding preferred");
    }

    private void addPreferredActivityInternal(IntentFilter filter, int match, ComponentName[] set, ComponentName activity, boolean always, int userId, String opname) {
        int callingUid = Binder.getCallingUid();
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, false, "add preferred activity");
        if (this.mContext.checkCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS") != 0) {
            if (getUidTargetSdkVersionLockedLPr(callingUid) < 8) {
                Slog.w(TAG, "Ignoring addPreferredActivity() from uid " + callingUid);
                return;
            }
            this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
        }
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        synchronized (this.mPackages) {
            PreferredIntentResolver pir = this.mSettings.editPreferredActivitiesLPw(userId);
            pir.addFilter(new PreferredActivity(filter, match, set, activity, always));
            scheduleWritePackageRestrictionsLocked(userId);
        }
        if (!updateDefaultHomeNotLocked(userId)) {
            postPreferredActivityChangedBroadcast(userId);
        }
    }

    private void postPreferredActivityChangedBroadcast(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$0PosSKX4veyXp7ssRo-qePJ6fuo
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.lambda$postPreferredActivityChangedBroadcast$25(userId);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$postPreferredActivityChangedBroadcast$25(int userId) {
        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            return;
        }
        Intent intent = new Intent("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED");
        intent.putExtra("android.intent.extra.user_handle", userId);
        intent.addFlags(67108864);
        try {
            am.broadcastIntent((IApplicationThread) null, intent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, (String[]) null, -1, (Bundle) null, false, false, userId);
        } catch (RemoteException e) {
        }
    }

    public void replacePreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity, int userId) {
        if (filter.countActions() != 1) {
            throw new IllegalArgumentException("replacePreferredActivity expects filter to have only 1 action.");
        }
        if (filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0 && filter.countDataSchemes() <= 1 && filter.countDataTypes() == 0) {
            int callingUid = Binder.getCallingUid();
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, false, "replace preferred activity");
            if (this.mContext.checkCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS") != 0) {
                synchronized (this.mPackages) {
                    if (getUidTargetSdkVersionLockedLPr(callingUid) < 8) {
                        Slog.w(TAG, "Ignoring replacePreferredActivity() from uid " + Binder.getCallingUid());
                        return;
                    }
                    this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
                }
            }
            synchronized (this.mPackages) {
                try {
                    try {
                        try {
                            PreferredIntentResolver pir = this.mSettings.mPreferredActivities.get(userId);
                            if (pir != null) {
                                try {
                                    ArrayList<PreferredActivity> existing = pir.findFilters(filter);
                                    if (existing != null && existing.size() == 1) {
                                        PreferredActivity cur = existing.get(0);
                                        if (cur.mPref.mAlways) {
                                            try {
                                                if (cur.mPref.mComponent.equals(activity) && cur.mPref.mMatch == (match & 268369920)) {
                                                    if (cur.mPref.sameSet(set)) {
                                                        return;
                                                    }
                                                }
                                            } catch (Throwable th) {
                                                th = th;
                                                throw th;
                                            }
                                        }
                                    }
                                    if (existing != null) {
                                        for (int i = existing.size() - 1; i >= 0; i--) {
                                            PreferredActivity pa = existing.get(i);
                                            pir.removeFilter(pa);
                                        }
                                    }
                                } catch (Throwable th2) {
                                    th = th2;
                                    throw th;
                                }
                            }
                            addPreferredActivityInternal(filter, match, set, activity, true, userId, "Replacing preferred");
                            return;
                        } catch (Throwable th3) {
                            th = th3;
                        }
                    } catch (Throwable th4) {
                        th = th4;
                    }
                } catch (Throwable th5) {
                    th = th5;
                }
            }
        }
        throw new IllegalArgumentException("replacePreferredActivity expects filter to have no data authorities, paths, or types; and at most one scheme.");
    }

    public void clearPackagePreferredActivities(String packageName) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return;
        }
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if ((pkg == null || !isCallerSameApp(packageName, callingUid)) && this.mContext.checkCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS") != 0) {
                if (getUidTargetSdkVersionLockedLPr(callingUid) < 8) {
                    Slog.w(TAG, "Ignoring clearPackagePreferredActivities() from uid " + callingUid);
                    return;
                }
                this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
            }
            PackageSetting ps = this.mSettings.getPackageLPr(packageName);
            if (ps == null || !filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                int callingUserId = UserHandle.getCallingUserId();
                SparseBooleanArray changedUsers = new SparseBooleanArray();
                clearPackagePreferredActivitiesLPw(packageName, changedUsers, callingUserId);
                if (changedUsers.size() > 0) {
                    updateDefaultHomeNotLocked(changedUsers);
                    postPreferredActivityChangedBroadcast(callingUserId);
                    synchronized (this.mPackages) {
                        scheduleWritePackageRestrictionsLocked(callingUserId);
                    }
                }
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void clearPackagePreferredActivitiesLPw(String packageName, SparseBooleanArray outUserChanged, int userId) {
        ArrayList<PreferredActivity> removed = null;
        for (int i = 0; i < this.mSettings.mPreferredActivities.size(); i++) {
            int thisUserId = this.mSettings.mPreferredActivities.keyAt(i);
            PreferredIntentResolver pir = this.mSettings.mPreferredActivities.valueAt(i);
            if (userId == -1 || userId == thisUserId) {
                Iterator<PreferredActivity> it = pir.filterIterator();
                while (it.hasNext()) {
                    PreferredActivity pa = it.next();
                    if (packageName == null || (pa.mPref.mComponent.getPackageName().equals(packageName) && pa.mPref.mAlways)) {
                        if (removed == null) {
                            removed = new ArrayList<>();
                        }
                        removed.add(pa);
                    }
                }
                if (removed != null) {
                    for (int j = 0; j < removed.size(); j++) {
                        pir.removeFilter(removed.get(j));
                    }
                    outUserChanged.put(thisUserId, true);
                }
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void clearIntentFilterVerificationsLPw(int userId) {
        int packageCount = this.mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageParser.Package pkg = this.mPackages.valueAt(i);
            clearIntentFilterVerificationsLPw(pkg.packageName, userId, true);
        }
    }

    @GuardedBy({"mPackages"})
    void clearIntentFilterVerificationsLPw(String packageName, int userId, boolean alsoResetStatus) {
        int[] userIds;
        if (userId == -1) {
            if (this.mSettings.removeIntentFilterVerificationLPw(packageName, sUserManager.getUserIds())) {
                for (int oneUserId : sUserManager.getUserIds()) {
                    scheduleWritePackageRestrictionsLocked(oneUserId);
                }
            }
        } else if (this.mSettings.removeIntentFilterVerificationLPw(packageName, userId, alsoResetStatus)) {
            scheduleWritePackageRestrictionsLocked(userId);
        }
    }

    void clearDefaultBrowserIfNeeded(String packageName) {
        int[] userIds;
        for (int oneUserId : sUserManager.getUserIds()) {
            clearDefaultBrowserIfNeededForUser(packageName, oneUserId);
        }
    }

    private void clearDefaultBrowserIfNeededForUser(String packageName, int userId) {
        String defaultBrowserPackageName = getDefaultBrowserPackageName(userId);
        if (!TextUtils.isEmpty(defaultBrowserPackageName) && packageName.equals(defaultBrowserPackageName)) {
            setDefaultBrowserPackageName(null, userId);
        }
    }

    public void resetApplicationPreferences(int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
        long identity = Binder.clearCallingIdentity();
        try {
            SparseBooleanArray changedUsers = new SparseBooleanArray();
            clearPackagePreferredActivitiesLPw(null, changedUsers, userId);
            if (changedUsers.size() > 0) {
                postPreferredActivityChangedBroadcast(userId);
            }
            synchronized (this.mPackages) {
                this.mSettings.applyDefaultPreferredAppsLPw(userId);
                clearIntentFilterVerificationsLPw(userId);
                primeDomainVerificationsLPw(userId);
                resetUserChangesToRuntimePermissionsAndFlagsLPw(userId);
            }
            updateDefaultHomeNotLocked(userId);
            setDefaultBrowserPackageName(null, userId);
            resetNetworkPolicies(userId);
            synchronized (this.mPackages) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public int getPreferredActivities(List<IntentFilter> outFilters, List<ComponentName> outActivities, String packageName) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return 0;
        }
        int userId = UserHandle.getCallingUserId();
        synchronized (this.mPackages) {
            PreferredIntentResolver pir = this.mSettings.mPreferredActivities.get(userId);
            if (pir != null) {
                Iterator<PreferredActivity> it = pir.filterIterator();
                while (it.hasNext()) {
                    PreferredActivity pa = it.next();
                    if (packageName == null || (pa.mPref.mComponent.getPackageName().equals(packageName) && pa.mPref.mAlways)) {
                        if (outFilters != null) {
                            outFilters.add(new IntentFilter(pa));
                        }
                        if (outActivities != null) {
                            outActivities.add(pa.mPref.mComponent);
                        }
                    }
                }
            }
        }
        return 0;
    }

    public void addPersistentPreferredActivity(IntentFilter filter, ComponentName activity, int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 1000) {
            throw new SecurityException("addPersistentPreferredActivity can only be run by the system");
        }
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        synchronized (this.mPackages) {
            this.mSettings.editPersistentPreferredActivitiesLPw(userId).addFilter(new PersistentPreferredActivity(filter, activity));
            scheduleWritePackageRestrictionsLocked(userId);
        }
        updateDefaultHomeNotLocked(userId);
        postPreferredActivityChangedBroadcast(userId);
    }

    public void clearPackagePersistentPreferredActivities(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 1000) {
            throw new SecurityException("clearPackagePersistentPreferredActivities can only be run by the system");
        }
        ArrayList<PersistentPreferredActivity> removed = null;
        boolean changed = false;
        synchronized (this.mPackages) {
            for (int i = 0; i < this.mSettings.mPersistentPreferredActivities.size(); i++) {
                int thisUserId = this.mSettings.mPersistentPreferredActivities.keyAt(i);
                PersistentPreferredIntentResolver ppir = this.mSettings.mPersistentPreferredActivities.valueAt(i);
                if (userId == thisUserId) {
                    Iterator<PersistentPreferredActivity> it = ppir.filterIterator();
                    while (it.hasNext()) {
                        PersistentPreferredActivity ppa = it.next();
                        if (ppa.mComponent.getPackageName().equals(packageName)) {
                            if (removed == null) {
                                removed = new ArrayList<>();
                            }
                            removed.add(ppa);
                        }
                    }
                    if (removed != null) {
                        for (int j = 0; j < removed.size(); j++) {
                            ppir.removeFilter(removed.get(j));
                        }
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            updateDefaultHomeNotLocked(userId);
            postPreferredActivityChangedBroadcast(userId);
            synchronized (this.mPackages) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    private void restoreFromXml(XmlPullParser parser, int userId, String expectedStartTag, BlobXmlRestorer functor) throws IOException, XmlPullParserException {
        int type;
        do {
            type = parser.next();
            if (type == 2) {
                break;
            }
        } while (type != 1);
        if (type != 2 || !expectedStartTag.equals(parser.getName())) {
            return;
        }
        do {
        } while (parser.next() == 4);
        functor.apply(parser, userId);
    }

    public byte[] getPreferredActivityBackup(int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call getPreferredActivityBackup()");
        }
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_PREFERRED_BACKUP);
            synchronized (this.mPackages) {
                this.mSettings.writePreferredActivitiesLPr(serializer, userId, true);
            }
            serializer.endTag(null, TAG_PREFERRED_BACKUP);
            serializer.endDocument();
            serializer.flush();
            return dataStream.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public void restorePreferredActivities(byte[] backup, int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call restorePreferredActivities()");
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_PREFERRED_BACKUP, new BlobXmlRestorer() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$owzvespYnSG3k53wFgqA8dIrzCI
                @Override // com.android.server.pm.PackageManagerService.BlobXmlRestorer
                public final void apply(XmlPullParser xmlPullParser, int i) {
                    PackageManagerService.this.lambda$restorePreferredActivities$26$PackageManagerService(xmlPullParser, i);
                }
            });
        } catch (Exception e) {
        }
    }

    public /* synthetic */ void lambda$restorePreferredActivities$26$PackageManagerService(XmlPullParser readParser, int readUserId) throws IOException, XmlPullParserException {
        synchronized (this.mPackages) {
            this.mSettings.readPreferredActivitiesLPw(readParser, readUserId);
        }
        updateDefaultHomeNotLocked(readUserId);
    }

    public byte[] getDefaultAppsBackup(int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call getDefaultAppsBackup()");
        }
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_DEFAULT_APPS);
            synchronized (this.mPackages) {
                this.mSettings.writeDefaultAppsLPr(serializer, userId);
            }
            serializer.endTag(null, TAG_DEFAULT_APPS);
            serializer.endDocument();
            serializer.flush();
            return dataStream.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public void restoreDefaultApps(byte[] backup, int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call restoreDefaultApps()");
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_DEFAULT_APPS, new BlobXmlRestorer() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$lzioMNzZkED1ivRsBfeRkwJEBW4
                @Override // com.android.server.pm.PackageManagerService.BlobXmlRestorer
                public final void apply(XmlPullParser xmlPullParser, int i) {
                    PackageManagerService.this.lambda$restoreDefaultApps$27$PackageManagerService(xmlPullParser, i);
                }
            });
        } catch (Exception e) {
        }
    }

    public /* synthetic */ void lambda$restoreDefaultApps$27$PackageManagerService(XmlPullParser parser1, int userId1) throws IOException, XmlPullParserException {
        String defaultBrowser;
        PackageManagerInternal.DefaultBrowserProvider provider;
        synchronized (this.mPackages) {
            this.mSettings.readDefaultAppsLPw(parser1, userId1);
            defaultBrowser = this.mSettings.removeDefaultBrowserPackageNameLPw(userId1);
        }
        if (defaultBrowser != null) {
            synchronized (this.mPackages) {
                provider = this.mDefaultBrowserProvider;
            }
            provider.setDefaultBrowser(defaultBrowser, userId1);
        }
    }

    public byte[] getIntentFilterVerificationBackup(int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call getIntentFilterVerificationBackup()");
        }
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_INTENT_FILTER_VERIFICATION);
            synchronized (this.mPackages) {
                this.mSettings.writeAllDomainVerificationsLPr(serializer, userId);
            }
            serializer.endTag(null, TAG_INTENT_FILTER_VERIFICATION);
            serializer.endDocument();
            serializer.flush();
            return dataStream.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    public void restoreIntentFilterVerification(byte[] backup, int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call restorePreferredActivities()");
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_INTENT_FILTER_VERIFICATION, new BlobXmlRestorer() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$wnVrM2Mr6uaXS-ge5Ff00wRy0JE
                @Override // com.android.server.pm.PackageManagerService.BlobXmlRestorer
                public final void apply(XmlPullParser xmlPullParser, int i) {
                    PackageManagerService.this.lambda$restoreIntentFilterVerification$28$PackageManagerService(xmlPullParser, i);
                }
            });
        } catch (Exception e) {
        }
    }

    public /* synthetic */ void lambda$restoreIntentFilterVerification$28$PackageManagerService(XmlPullParser parser1, int userId1) throws IOException, XmlPullParserException {
        synchronized (this.mPackages) {
            this.mSettings.readAllDomainVerificationsLPr(parser1, userId1);
            this.mSettings.writeLPr();
        }
    }

    public void addCrossProfileIntentFilter(IntentFilter intentFilter, String ownerPackage, int sourceUserId, int targetUserId, int flags) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        PackageManagerServiceUtils.enforceShellRestriction("no_debugging_features", callingUid, sourceUserId);
        if (intentFilter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a crossProfile intent filter with no filter actions");
            return;
        }
        synchronized (this.mPackages) {
            CrossProfileIntentFilter newFilter = new CrossProfileIntentFilter(intentFilter, ownerPackage, targetUserId, flags);
            CrossProfileIntentResolver resolver = this.mSettings.editCrossProfileIntentResolverLPw(sourceUserId);
            ArrayList<CrossProfileIntentFilter> existing = resolver.findFilters(intentFilter);
            if (existing != null) {
                int size = existing.size();
                for (int i = 0; i < size; i++) {
                    if (newFilter.equalsIgnoreFilter(existing.get(i))) {
                        return;
                    }
                }
            }
            resolver.addFilter(newFilter);
            scheduleWritePackageRestrictionsLocked(sourceUserId);
        }
    }

    public void clearCrossProfileIntentFilters(int sourceUserId, String ownerPackage) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        PackageManagerServiceUtils.enforceShellRestriction("no_debugging_features", callingUid, sourceUserId);
        synchronized (this.mPackages) {
            CrossProfileIntentResolver resolver = this.mSettings.editCrossProfileIntentResolverLPw(sourceUserId);
            ArraySet<CrossProfileIntentFilter> set = new ArraySet<>(resolver.filterSet());
            Iterator<CrossProfileIntentFilter> it = set.iterator();
            while (it.hasNext()) {
                CrossProfileIntentFilter filter = it.next();
                if (filter.getOwnerPackage().equals(ownerPackage)) {
                    resolver.removeFilter(filter);
                }
            }
            scheduleWritePackageRestrictionsLocked(sourceUserId);
        }
    }

    private void enforceOwnerRights(String pkg, int callingUid) {
        if (UserHandle.getAppId(callingUid) == 1000) {
            return;
        }
        int callingUserId = UserHandle.getUserId(callingUid);
        PackageInfo pi = getPackageInfo(pkg, 0, callingUserId);
        if (pi == null) {
            throw new IllegalArgumentException("Unknown package " + pkg + " on user " + callingUserId);
        } else if (!UserHandle.isSameApp(pi.applicationInfo.uid, callingUid)) {
            throw new SecurityException("Calling uid " + callingUid + " does not own package " + pkg);
        }
    }

    public ComponentName getHomeActivities(List<ResolveInfo> allHomeCandidates) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return getHomeActivitiesAsUser(allHomeCandidates, UserHandle.getCallingUserId());
    }

    public void sendSessionUpdatedBroadcast(PackageInstaller.SessionInfo sessionInfo, int userId) {
        if (TextUtils.isEmpty(sessionInfo.installerPackageName)) {
            return;
        }
        Intent sessionUpdatedIntent = new Intent("android.content.pm.action.SESSION_UPDATED").putExtra("android.content.pm.extra.SESSION", sessionInfo).setPackage(sessionInfo.installerPackageName);
        this.mContext.sendBroadcastAsUser(sessionUpdatedIntent, UserHandle.of(userId));
    }

    public void sendSessionCommitBroadcast(PackageInstaller.SessionInfo sessionInfo, int userId) {
        UserManagerService ums = UserManagerService.getInstance();
        if (ums != null && !sessionInfo.isStaged()) {
            UserInfo parent = ums.getProfileParent(userId);
            int launcherUid = parent != null ? parent.id : userId;
            ComponentName launcherComponent = getDefaultHomeActivity(launcherUid);
            if (launcherComponent != null) {
                Intent launcherIntent = new Intent("android.content.pm.action.SESSION_COMMITTED").putExtra("android.content.pm.extra.SESSION", sessionInfo).putExtra("android.intent.extra.USER", UserHandle.of(userId)).setPackage(launcherComponent.getPackageName());
                this.mContext.sendBroadcastAsUser(launcherIntent, UserHandle.of(launcherUid));
            }
            if (this.mAppPredictionServicePackage != null) {
                Intent predictorIntent = new Intent("android.content.pm.action.SESSION_COMMITTED").putExtra("android.content.pm.extra.SESSION", sessionInfo).putExtra("android.intent.extra.USER", UserHandle.of(userId)).setPackage(this.mAppPredictionServicePackage);
                this.mContext.sendBroadcastAsUser(predictorIntent, UserHandle.of(launcherUid));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ComponentName getDefaultHomeActivity(int userId) {
        List<ResolveInfo> allHomeCandidates = new ArrayList<>();
        ComponentName cn = getHomeActivitiesAsUser(allHomeCandidates, userId);
        if (cn != null) {
            return cn;
        }
        int lastPriority = Integer.MIN_VALUE;
        ComponentName lastComponent = null;
        int size = allHomeCandidates.size();
        for (int i = 0; i < size; i++) {
            ResolveInfo ri = allHomeCandidates.get(i);
            if (ri.priority > lastPriority) {
                lastComponent = ri.activityInfo.getComponentName();
                lastPriority = ri.priority;
            } else if (ri.priority == lastPriority) {
                lastComponent = null;
            }
        }
        return lastComponent;
    }

    private Intent getHomeIntent() {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory("android.intent.category.HOME");
        intent.addCategory("android.intent.category.DEFAULT");
        return intent;
    }

    private IntentFilter getHomeFilter() {
        IntentFilter filter = new IntentFilter("android.intent.action.MAIN");
        filter.addCategory("android.intent.category.HOME");
        filter.addCategory("android.intent.category.DEFAULT");
        return filter;
    }

    ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates, int userId) {
        PackageManagerInternal.DefaultHomeProvider provider;
        Intent intent = getHomeIntent();
        List<ResolveInfo> resolveInfos = queryIntentActivitiesInternal(intent, null, 128, userId);
        allHomeCandidates.clear();
        if (resolveInfos == null) {
            return null;
        }
        allHomeCandidates.addAll(resolveInfos);
        synchronized (this.mPackages) {
            provider = this.mDefaultHomeProvider;
        }
        if (provider == null) {
            Slog.e(TAG, "mDefaultHomeProvider is null");
            return null;
        }
        String packageName = provider.getDefaultHome(userId);
        if (packageName == null) {
            return null;
        }
        int resolveInfosSize = resolveInfos.size();
        for (int i = 0; i < resolveInfosSize; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (resolveInfo.activityInfo != null && TextUtils.equals(resolveInfo.activityInfo.packageName, packageName)) {
                return new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
            }
        }
        return null;
    }

    private void updateDefaultHomeNotLocked(SparseBooleanArray userIds) {
        if (Thread.holdsLock(this.mPackages)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding mPackages", new Throwable());
        }
        for (int i = userIds.size() - 1; i >= 0; i--) {
            int userId = userIds.keyAt(i);
            updateDefaultHomeNotLocked(userId);
        }
    }

    private boolean updateDefaultHomeNotLocked(final int userId) {
        PackageManagerInternal.DefaultHomeProvider provider;
        if (Thread.holdsLock(this.mPackages)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName() + " is holding mPackages", new Throwable());
        }
        if (this.mSystemReady) {
            Intent intent = getHomeIntent();
            String str = null;
            List<ResolveInfo> resolveInfos = queryIntentActivitiesInternal(intent, null, 128, userId);
            ResolveInfo preferredResolveInfo = findPreferredActivityNotLocked(intent, null, 0, resolveInfos, 0, true, false, false, userId);
            if (preferredResolveInfo != null && preferredResolveInfo.activityInfo != null) {
                str = preferredResolveInfo.activityInfo.packageName;
            }
            String packageName = str;
            synchronized (this.mPackages) {
                provider = this.mDefaultHomeProvider;
            }
            if (provider == null) {
                Slog.e(TAG, "Default home provider has not been set");
                return false;
            }
            String currentPackageName = provider.getDefaultHome(userId);
            if (TextUtils.equals(currentPackageName, packageName)) {
                return false;
            }
            String[] callingPackages = getPackagesForUid(Binder.getCallingUid());
            if (callingPackages == null || !ArrayUtils.contains(callingPackages, this.mRequiredPermissionControllerPackage)) {
                provider.setDefaultHomeAsync(packageName, userId, new Consumer() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$lHrGVlaTNwBrD-eozHhhp2pppkk
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        PackageManagerService.this.lambda$updateDefaultHomeNotLocked$29$PackageManagerService(userId, (Boolean) obj);
                    }
                });
                return true;
            }
            return false;
        }
        return false;
    }

    public /* synthetic */ void lambda$updateDefaultHomeNotLocked$29$PackageManagerService(int userId, Boolean successful) {
        if (successful.booleanValue()) {
            postPreferredActivityChangedBroadcast(userId);
        }
    }

    public void setHomeActivity(ComponentName comp, int userId) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        ArrayList<ResolveInfo> homeActivities = new ArrayList<>();
        getHomeActivitiesAsUser(homeActivities, userId);
        boolean found = false;
        int size = homeActivities.size();
        ComponentName[] set = new ComponentName[size];
        for (int i = 0; i < size; i++) {
            ResolveInfo candidate = homeActivities.get(i);
            ActivityInfo info = candidate.activityInfo;
            ComponentName activityName = new ComponentName(info.packageName, info.name);
            set[i] = activityName;
            if (!found && activityName.equals(comp)) {
                found = true;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Component " + comp + " cannot be home on user " + userId);
        }
        replacePreferredActivity(getHomeFilter(), 1048576, set, comp, userId);
    }

    private String getSetupWizardPackageName() {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory("android.intent.category.SETUP_WIZARD");
        List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null, 1835520, UserHandle.myUserId());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        }
        Slog.e(TAG, "There should probably be exactly one setup wizard; found " + matches.size() + ": matches=" + matches);
        return null;
    }

    private String getStorageManagerPackageName() {
        Intent intent = new Intent("android.os.storage.action.MANAGE_STORAGE");
        List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null, 1835520, UserHandle.myUserId());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        }
        Slog.e(TAG, "There should probably be exactly one storage manager; found " + matches.size() + ": matches=" + matches);
        return null;
    }

    public String getSystemTextClassifierPackageName() {
        return ensureSystemPackageName(this.mContext.getString(17039715));
    }

    public String getAttentionServicePackageName() {
        ComponentName componentName;
        String flattenedComponentName = this.mContext.getString(17039700);
        if (flattenedComponentName != null && (componentName = ComponentName.unflattenFromString(flattenedComponentName)) != null && componentName.getPackageName() != null) {
            return ensureSystemPackageName(componentName.getPackageName());
        }
        return null;
    }

    private String getDocumenterPackageName() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        String resolvedType = intent.resolveTypeIfNeeded(this.mContext.getContentResolver());
        List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, resolvedType, 1835520, UserHandle.myUserId());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        }
        Slog.e(TAG, "There should probably be exactly one documenter; found " + matches.size() + ": matches=" + matches);
        return null;
    }

    private String getDeviceConfiguratorPackageName() {
        return ensureSystemPackageName(this.mContext.getString(17039719));
    }

    public String getWellbeingPackageName() {
        return ensureSystemPackageName(this.mContext.getString(17039717));
    }

    public String getAppPredictionServicePackageName() {
        ComponentName appPredictionServiceComponentName;
        String flattenedAppPredictionServiceComponentName = this.mContext.getString(17039698);
        if (flattenedAppPredictionServiceComponentName == null || (appPredictionServiceComponentName = ComponentName.unflattenFromString(flattenedAppPredictionServiceComponentName)) == null) {
            return null;
        }
        return ensureSystemPackageName(appPredictionServiceComponentName.getPackageName());
    }

    public String getSystemCaptionsServicePackageName() {
        ComponentName systemCaptionsServiceComponentName;
        String flattenedSystemCaptionsServiceComponentName = this.mContext.getString(17039714);
        if (TextUtils.isEmpty(flattenedSystemCaptionsServiceComponentName) || (systemCaptionsServiceComponentName = ComponentName.unflattenFromString(flattenedSystemCaptionsServiceComponentName)) == null) {
            return null;
        }
        return ensureSystemPackageName(systemCaptionsServiceComponentName.getPackageName());
    }

    public String getIncidentReportApproverPackageName() {
        return ensureSystemPackageName(this.mContext.getString(17039750));
    }

    private String ensureSystemPackageName(String packageName) {
        if (packageName == null) {
            return null;
        }
        long token = Binder.clearCallingIdentity();
        try {
            if (getPackageInfo(packageName, 2097152, 0) == null) {
                PackageInfo packageInfo = getPackageInfo(packageName, 0, 0);
                if (packageInfo != null) {
                    EventLog.writeEvent(1397638484, "145981139", Integer.valueOf(packageInfo.applicationInfo.uid), "");
                }
                return null;
            }
            return packageName;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setApplicationEnabledSetting(String appPackageName, int newState, int flags, int userId, String callingPackage) {
        if (sUserManager.exists(userId)) {
            if (callingPackage == null) {
                callingPackage = Integer.toString(Binder.getCallingUid());
            }
            setEnabledSetting(appPackageName, null, newState, flags, userId, callingPackage);
        }
    }

    public void setUpdateAvailable(String packageName, boolean updateAvailable) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.INSTALL_PACKAGES", null);
        synchronized (this.mPackages) {
            PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName);
            if (pkgSetting != null) {
                pkgSetting.setUpdateAvailable(updateAvailable);
            }
        }
    }

    public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags, int userId) {
        if (sUserManager.exists(userId)) {
            setEnabledSetting(componentName.getPackageName(), componentName.getClassName(), newState, flags, userId, null);
        }
    }

    private void setEnabledSetting(String packageName, String className, int newState, int flags, int userId, String callingPackage) {
        PackageSetting pkgSetting;
        ArrayList<String> components;
        boolean z;
        String str;
        String str2;
        if (newState != 0 && newState != 1 && newState != 2 && newState != 3 && newState != 4) {
            throw new IllegalArgumentException("Invalid new component state: " + newState);
        }
        int callingUid = Binder.getCallingUid();
        int permission = callingUid == 1000 ? 0 : this.mContext.checkCallingOrSelfPermission("android.permission.CHANGE_COMPONENT_ENABLED_STATE");
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, true, "set enabled");
        boolean allowedByPermission = permission == 0;
        boolean isApp = className == null;
        boolean isCallerInstantApp = getInstantAppPackageName(callingUid) != null;
        String componentName = isApp ? packageName : className;
        synchronized (this.mPackages) {
            try {
                try {
                    pkgSetting = this.mSettings.mPackages.get(packageName);
                } catch (Throwable th) {
                    th = th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
            if (pkgSetting == null) {
                if (isCallerInstantApp) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Attempt to change component state; pid=");
                        sb.append(Binder.getCallingPid());
                        sb.append(", uid=");
                        sb.append(callingUid);
                        if (className == null) {
                            StringBuilder sb2 = new StringBuilder();
                            sb2.append(", package=");
                            sb2.append(packageName);
                            str2 = sb2.toString();
                        } else {
                            str2 = ", component=" + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + className;
                        }
                        sb.append(str2);
                        throw new SecurityException(sb.toString());
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } else {
                    try {
                        if (className != null) {
                            throw new IllegalArgumentException("Unknown component: " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + className);
                        }
                        try {
                            StringBuilder sb3 = new StringBuilder();
                            sb3.append("Unknown package: ");
                            sb3.append(packageName);
                            throw new IllegalArgumentException(sb3.toString());
                        } catch (Throwable th4) {
                            th = th4;
                        }
                    } catch (Throwable th5) {
                        th = th5;
                    }
                }
                throw th;
            }
            boolean sendNow = false;
            if (!UserHandle.isSameApp(callingUid, pkgSetting.appId)) {
                if (!allowedByPermission || filterAppAccessLPr(pkgSetting, callingUid, userId)) {
                    StringBuilder sb4 = new StringBuilder();
                    sb4.append("Attempt to change component state; pid=");
                    sb4.append(Binder.getCallingPid());
                    sb4.append(", uid=");
                    sb4.append(callingUid);
                    if (className == null) {
                        str = ", package=" + packageName;
                    } else {
                        str = ", component=" + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + className;
                    }
                    sb4.append(str);
                    throw new SecurityException(sb4.toString());
                } else if (this.mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                    throw new SecurityException("Cannot disable a protected package: " + packageName);
                }
            }
            if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className) && !allowedByPermission) {
                throw new SecurityException("Cannot disable a system-generated component");
            }
            synchronized (this.mPackages) {
                if (callingUid == SHELL_UID) {
                    if ((pkgSetting.pkgFlags & 256) == 0) {
                        int oldState = pkgSetting.getEnabled(userId);
                        if (className != null || ((oldState != 3 && oldState != 0 && oldState != 1) || (newState != 3 && newState != 0 && newState != 1))) {
                            throw new SecurityException("Shell cannot change component state for " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + className + " to " + newState);
                        }
                    }
                }
            }
            if (className == null) {
                synchronized (this.mPackages) {
                    if (pkgSetting.getEnabled(userId) == newState) {
                        return;
                    }
                    PackageParser.Package deletedPkg = pkgSetting.pkg;
                    boolean isSystemStub = deletedPkg.isStub && deletedPkg.isSystem();
                    if (isSystemStub && ((newState == 0 || newState == 1) && !enableCompressedPackage(deletedPkg))) {
                        return;
                    }
                    String callingPackage2 = (newState == 0 || newState == 1) ? null : callingPackage;
                    synchronized (this.mPackages) {
                        pkgSetting.setEnabled(newState, userId, callingPackage2);
                    }
                }
            } else {
                synchronized (this.mPackages) {
                    PackageParser.Package pkg = pkgSetting.pkg;
                    if (pkg == null || !pkg.hasComponentClassName(className)) {
                        if (pkg != null && pkg.applicationInfo.targetSdkVersion >= 16) {
                            throw new IllegalArgumentException("Component class " + className + " does not exist in " + packageName);
                        }
                        Slog.w(TAG, "Failed setComponentEnabledSetting: component class " + className + " does not exist in " + packageName);
                    }
                    if (newState != 0) {
                        if (newState != 1) {
                            if (newState != 2) {
                                Slog.e(TAG, "Invalid new component state: " + newState);
                                return;
                            } else if (!pkgSetting.disableComponentLPw(className, userId)) {
                                return;
                            }
                        } else if (!pkgSetting.enableComponentLPw(className, userId)) {
                            return;
                        }
                    } else if (!pkgSetting.restoreComponentLPw(className, userId)) {
                        return;
                    }
                }
            }
            synchronized (this.mPackages) {
                scheduleWritePackageRestrictionsLocked(userId);
                updateSequenceNumberLP(pkgSetting, new int[]{userId});
                long callingId = Binder.clearCallingIdentity();
                updateInstantAppInstallerLocked(packageName);
                Binder.restoreCallingIdentity(callingId);
                components = this.mPendingBroadcasts.get(userId, packageName);
                boolean newPackage = components == null;
                if (newPackage) {
                    components = new ArrayList<>();
                }
                if (!components.contains(componentName)) {
                    components.add(componentName);
                }
                if ((flags & 1) == 0) {
                    sendNow = true;
                    this.mPendingBroadcasts.remove(userId, packageName);
                    z = true;
                } else {
                    if (newPackage) {
                        this.mPendingBroadcasts.put(userId, packageName, components);
                    }
                    if (this.mHandler.hasMessages(1)) {
                        z = true;
                    } else {
                        long broadcastDelay = SystemClock.uptimeMillis() > this.mServiceStartWithDelay ? 1000L : JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
                        z = true;
                        this.mHandler.sendEmptyMessageDelayed(1, broadcastDelay);
                    }
                }
            }
            long callingId2 = Binder.clearCallingIdentity();
            if (sendNow) {
                try {
                    int packageUid = UserHandle.getUid(userId, pkgSetting.appId);
                    if ((flags & 1) == 0) {
                        z = false;
                    }
                    sendPackageChangedBroadcast(packageName, z, components, packageUid);
                } finally {
                    Binder.restoreCallingIdentity(callingId2);
                }
            }
        }
    }

    public void flushPackageRestrictionsAsUser(int userId) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null || !sUserManager.exists(userId)) {
            return;
        }
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "flushPackageRestrictions");
        synchronized (this.mPackages) {
            this.mSettings.writePackageRestrictionsLPr(userId);
            this.mDirtyUsers.remove(Integer.valueOf(userId));
            if (this.mDirtyUsers.isEmpty()) {
                this.mHandler.removeMessages(14);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendPackageChangedBroadcast(String packageName, boolean killFlag, ArrayList<String> componentNames, int packageUid) {
        int flags;
        Bundle extras = new Bundle(4);
        extras.putString("android.intent.extra.changed_component_name", componentNames.get(0));
        String[] nameList = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray("android.intent.extra.changed_component_name_list", nameList);
        extras.putBoolean("android.intent.extra.DONT_KILL_APP", killFlag);
        extras.putInt("android.intent.extra.UID", packageUid);
        if (componentNames.contains(packageName)) {
            flags = 0;
        } else {
            flags = 1073741824;
        }
        int userId = UserHandle.getUserId(packageUid);
        boolean isInstantApp = isInstantApp(packageName, userId);
        int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[]{userId};
        int[] instantUserIds = isInstantApp ? new int[]{userId} : EMPTY_INT_ARRAY;
        sendPackageBroadcast("android.intent.action.PACKAGE_CHANGED", packageName, extras, flags, null, null, userIds, instantUserIds);
    }

    public void setPackageStoppedState(String packageName, boolean stopped, int userId) {
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            if (getInstantAppPackageName(callingUid) != null) {
                return;
            }
            int permission = this.mContext.checkCallingOrSelfPermission("android.permission.CHANGE_COMPONENT_ENABLED_STATE");
            boolean allowedByPermission = permission == 0;
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, true, "stop package");
            synchronized (this.mPackages) {
                try {
                    try {
                    } catch (Throwable th) {
                        th = th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
                try {
                    PackageSetting ps = this.mSettings.mPackages.get(packageName);
                    if (!filterAppAccessLPr(ps, callingUid, userId)) {
                        if (this.mSettings.setPackageStoppedStateLPw(this, packageName, stopped, allowedByPermission, callingUid, userId)) {
                            scheduleWritePackageRestrictionsLocked(userId);
                        }
                    }
                } catch (Throwable th3) {
                    th = th3;
                    throw th;
                }
            }
        }
    }

    public String getInstallerPackageName(String packageName) {
        int callingUid = Binder.getCallingUid();
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                return null;
            }
            if (ps == null && this.mApexManager.isApexPackage(packageName)) {
                return null;
            }
            return this.mSettings.getInstallerPackageNameLPr(packageName);
        }
    }

    public boolean isOrphaned(String packageName) {
        synchronized (this.mPackages) {
            if (!this.mPackages.containsKey(packageName)) {
                return false;
            }
            return this.mSettings.isOrphaned(packageName);
        }
    }

    public int getApplicationEnabledSetting(String packageName, int userId) {
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get enabled");
            synchronized (this.mPackages) {
                if (filterAppAccessLPr(this.mSettings.getPackageLPr(packageName), callingUid, userId)) {
                    return 2;
                }
                return this.mSettings.getApplicationEnabledSettingLPr(packageName, userId);
            }
        }
        return 2;
    }

    public int getComponentEnabledSetting(ComponentName component, int userId) {
        if (component == null) {
            return 0;
        }
        if (sUserManager.exists(userId)) {
            int callingUid = Binder.getCallingUid();
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "getComponentEnabled");
            synchronized (this.mPackages) {
                if (filterAppAccessLPr(this.mSettings.getPackageLPr(component.getPackageName()), callingUid, component, 0, userId)) {
                    return 2;
                }
                return this.mSettings.getComponentEnabledSettingLPr(component, userId);
            }
        }
        return 2;
    }

    public void enterSafeMode() {
        enforceSystemOrRoot("Only the system can request entering safe mode");
        if (!this.mSystemReady) {
            this.mSafeMode = true;
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:54:0x01bc -> B:55:0x01bd). Please submit an issue!!! */
    public void systemReady() {
        int[] userIds;
        enforceSystemOrRoot("Only the system can claim the system is ready");
        this.mSystemReady = true;
        final ContentResolver resolver = this.mContext.getContentResolver();
        ContentObserver co = new ContentObserver(this.mHandler) { // from class: com.android.server.pm.PackageManagerService.6
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                int[] userIds2;
                boolean ephemeralFeatureDisabled = Settings.Global.getInt(resolver, "enable_ephemeral_feature", 1) == 0;
                for (int userId : UserManagerService.getInstance().getUserIds()) {
                    boolean instantAppsDisabledForUser = ephemeralFeatureDisabled || Settings.Secure.getIntForUser(resolver, "instant_apps_enabled", 1, userId) == 0;
                    PackageManagerService.this.mWebInstantAppsDisabled.put(userId, instantAppsDisabledForUser);
                }
            }
        };
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("enable_ephemeral_feature"), false, co, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("instant_apps_enabled"), false, co, -1);
        co.onChange(true);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(this.mContext.getOpPackageName(), this, this.mContext.getContentResolver(), 0);
        disableSkuSpecificApps();
        boolean compatibilityModeEnabled = Settings.Global.getInt(this.mContext.getContentResolver(), "compatibility_mode", 1) == 1;
        PackageParser.setCompatibilityModeEnabled(compatibilityModeEnabled);
        int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;
        synchronized (this.mPackages) {
            try {
                ArrayList<PreferredActivity> removed = new ArrayList<>();
                for (int i = 0; i < this.mSettings.mPreferredActivities.size(); i++) {
                    PreferredIntentResolver pir = this.mSettings.mPreferredActivities.valueAt(i);
                    removed.clear();
                    for (PreferredActivity pa : pir.filterSet()) {
                        if (!this.mComponentResolver.isActivityDefined(pa.mPref.mComponent)) {
                            removed.add(pa);
                        }
                    }
                    if (removed.size() > 0) {
                        for (int r = 0; r < removed.size(); r++) {
                            PreferredActivity pa2 = removed.get(r);
                            Slog.w(TAG, "Removing dangling preferred activity: " + pa2.mPref.mComponent);
                            pir.removeFilter(pa2);
                        }
                        this.mSettings.writePackageRestrictionsLPr(this.mSettings.mPreferredActivities.keyAt(i));
                    }
                }
                int[] grantPermissionsUserIds2 = grantPermissionsUserIds;
                for (int userId : UserManagerService.getInstance().getUserIds()) {
                    try {
                        if (!this.mSettings.areDefaultRuntimePermissionsGrantedLPr(userId)) {
                            grantPermissionsUserIds2 = ArrayUtils.appendInt(grantPermissionsUserIds2, userId);
                        }
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
                sUserManager.systemReady();
                for (int userId2 : grantPermissionsUserIds2) {
                    this.mDefaultPermissionPolicy.grantDefaultPermissions(userId2);
                }
                if (grantPermissionsUserIds2 == EMPTY_INT_ARRAY) {
                    this.mDefaultPermissionPolicy.scheduleReadDefaultPermissionExceptions();
                }
                synchronized (this.mPackages) {
                    this.mPermissionManager.updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, false, this.mPackages.values(), this.mPermissionCallback);
                    PermissionPolicyInternal permissionPolicyInternal = (PermissionPolicyInternal) LocalServices.getService(PermissionPolicyInternal.class);
                    permissionPolicyInternal.setOnInitializedCallback(new PermissionPolicyInternal.OnInitializedCallback() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$7hPE7B8Ek5O5gGvlR-Se3ejwgKE
                        @Override // com.android.server.policy.PermissionPolicyInternal.OnInitializedCallback
                        public final void onInitialized(int i2) {
                            PackageManagerService.this.lambda$systemReady$30$PackageManagerService(i2);
                        }
                    });
                }
                StorageManager storage = (StorageManager) this.mContext.getSystemService(StorageManager.class);
                storage.registerListener(this.mStorageListener);
                this.mInstallerService.systemReady();
                this.mApexManager.systemReady();
                this.mPackageDexOptimizer.systemReady();
                getStorageManagerInternal().addExternalStoragePolicy(new StorageManagerInternal.ExternalStorageMountPolicy() { // from class: com.android.server.pm.PackageManagerService.7
                    public int getMountMode(int uid, String packageName) {
                        if (Process.isIsolated(uid)) {
                            return 0;
                        }
                        if (PackageManagerService.this.checkUidPermission("android.permission.READ_EXTERNAL_STORAGE", uid) == -1) {
                            return 1;
                        }
                        if (PackageManagerService.this.checkUidPermission("android.permission.WRITE_EXTERNAL_STORAGE", uid) == -1) {
                            return 2;
                        }
                        return 3;
                    }

                    public boolean hasExternalStorage(int uid, String packageName) {
                        return true;
                    }
                });
                sUserManager.reconcileUsers(StorageManager.UUID_PRIVATE_INTERNAL);
                reconcileApps(StorageManager.UUID_PRIVATE_INTERNAL);
                this.mPermissionManager.systemReady();
                if (this.mInstantAppResolverConnection != null) {
                    this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.pm.PackageManagerService.8
                        @Override // android.content.BroadcastReceiver
                        public void onReceive(Context context, Intent intent) {
                            PackageManagerService.this.mInstantAppResolverConnection.optimisticBind();
                            PackageManagerService.this.mContext.unregisterReceiver(this);
                        }
                    }, new IntentFilter("android.intent.action.BOOT_COMPLETED"));
                }
                this.mModuleInfoProvider.systemReady();
                this.mInstallerService.restoreAndApplyStagedSessionIfNeeded();
                xpPackageManagerService.get(this.mContext).systemReady();
                new Thread(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$DQmriwjjhN-Zb5vzz8p2RoGXHrg
                    @Override // java.lang.Runnable
                    public final void run() {
                        PackageManagerService.this.lambda$systemReady$31$PackageManagerService();
                    }
                }).start();
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public /* synthetic */ void lambda$systemReady$30$PackageManagerService(int userId) {
        synchronized (this.mPackages) {
            this.mPermissionManager.updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, false, this.mPackages.values(), this.mPermissionCallback);
        }
    }

    public /* synthetic */ void lambda$systemReady$31$PackageManagerService() {
        mNapaAppInfoInnerList = napaAppInfoLoad();
        Slog.d(TAG, "when systemReady() napaAppInfoLoad  mNapaAppInfoInnerList=" + mNapaAppInfoInnerList);
    }

    public void waitForAppDataPrepared() {
        Future<?> future = this.mPrepareAppDataFuture;
        if (future == null) {
            return;
        }
        ConcurrentUtils.waitForFutureNoInterrupt(future, "wait for prepareAppData");
        this.mPrepareAppDataFuture = null;
    }

    public boolean isSafeMode() {
        return this.mSafeMode;
    }

    public boolean hasSystemUidErrors() {
        return this.mHasSystemUidErrors;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String arrayToString(int[] array) {
        StringBuilder stringBuilder = new StringBuilder(128);
        stringBuilder.append('[');
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    stringBuilder.append(", ");
                }
                stringBuilder.append(array[i]);
            }
        }
        stringBuilder.append(']');
        return stringBuilder.toString();
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new PackageManagerShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* JADX WARN: Removed duplicated region for block: B:412:0x0867 A[Catch: all -> 0x09da, TryCatch #11 {all -> 0x09da, blocks: (B:391:0x07ce, B:392:0x07db, B:407:0x0850, B:410:0x085a, B:412:0x0867, B:413:0x0871, B:415:0x0881, B:416:0x088b, B:417:0x0898, B:419:0x089e, B:421:0x08aa, B:424:0x08b1, B:427:0x0907, B:428:0x090f, B:430:0x091a, B:431:0x093f, B:433:0x0945, B:436:0x095a, B:439:0x09c0, B:396:0x0801, B:398:0x0817, B:400:0x082e), top: B:584:0x07ce, inners: #12, #15 }] */
    /* JADX WARN: Removed duplicated region for block: B:413:0x0871 A[Catch: all -> 0x09da, TryCatch #11 {all -> 0x09da, blocks: (B:391:0x07ce, B:392:0x07db, B:407:0x0850, B:410:0x085a, B:412:0x0867, B:413:0x0871, B:415:0x0881, B:416:0x088b, B:417:0x0898, B:419:0x089e, B:421:0x08aa, B:424:0x08b1, B:427:0x0907, B:428:0x090f, B:430:0x091a, B:431:0x093f, B:433:0x0945, B:436:0x095a, B:439:0x09c0, B:396:0x0801, B:398:0x0817, B:400:0x082e), top: B:584:0x07ce, inners: #12, #15 }] */
    /* JADX WARN: Removed duplicated region for block: B:445:0x09df  */
    /* JADX WARN: Removed duplicated region for block: B:455:0x09f9 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:459:0x0a08 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:464:0x0a1f A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:469:0x0a68 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:472:0x0a7b A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:488:0x0b01 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:493:0x0b11 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:496:0x0b2e A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:497:0x0b34  */
    /* JADX WARN: Removed duplicated region for block: B:503:0x0b51 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:508:0x0b61 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:511:0x0b7e A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:512:0x0b84  */
    /* JADX WARN: Removed duplicated region for block: B:518:0x0ba0 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:523:0x0bb1 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:527:0x0bbf A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:530:0x0bc7 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:534:0x0bd5 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:537:0x0bdd A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:542:0x0bed A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:545:0x0c03 A[Catch: all -> 0x0c55, TryCatch #9 {all -> 0x0c55, blocks: (B:567:0x0c53, B:450:0x09eb, B:455:0x09f9, B:457:0x0a01, B:459:0x0a08, B:461:0x0a10, B:462:0x0a17, B:464:0x0a1f, B:466:0x0a45, B:467:0x0a60, B:469:0x0a68, B:470:0x0a73, B:472:0x0a7b, B:474:0x0a81, B:475:0x0a84, B:477:0x0a9c, B:479:0x0abd, B:484:0x0af6, B:482:0x0acd, B:488:0x0b01, B:491:0x0b0b, B:493:0x0b11, B:494:0x0b14, B:496:0x0b2e, B:501:0x0b4c, B:498:0x0b36, B:500:0x0b3e, B:503:0x0b51, B:506:0x0b5b, B:508:0x0b61, B:509:0x0b64, B:511:0x0b7e, B:516:0x0b9b, B:513:0x0b85, B:515:0x0b8d, B:518:0x0ba0, B:521:0x0baa, B:523:0x0bb1, B:525:0x0bb9, B:527:0x0bbf, B:528:0x0bc2, B:530:0x0bc7, B:532:0x0bcf, B:534:0x0bd5, B:535:0x0bd8, B:537:0x0bdd, B:540:0x0be7, B:542:0x0bed, B:543:0x0bf0, B:545:0x0c03, B:547:0x0c0b, B:548:0x0c11), top: B:583:0x04b9 }] */
    /* JADX WARN: Removed duplicated region for block: B:550:0x0c14  */
    /* JADX WARN: Removed duplicated region for block: B:555:0x0c24  */
    /* JADX WARN: Removed duplicated region for block: B:558:0x0c35  */
    /* JADX WARN: Removed duplicated region for block: B:654:? A[ADDED_TO_REGION, RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    protected void dump(java.io.FileDescriptor r31, java.io.PrintWriter r32, java.lang.String[] r33) {
        /*
            Method dump skipped, instructions count: 3160
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
    }

    private void dumpPackagesList(PrintWriter pw, String prefix, String name, ArraySet<String> list) {
        pw.print(prefix);
        pw.print(name);
        pw.print(": ");
        int size = list.size();
        if (size == 0) {
            pw.println("empty");
            return;
        }
        pw.print(size);
        pw.println(" packages");
        String prefix2 = prefix + "  ";
        for (int i = 0; i < size; i++) {
            pw.print(prefix2);
            pw.println(list.valueAt(i));
        }
    }

    private void disableSkuSpecificApps() {
        String[] apkList = this.mContext.getResources().getStringArray(17236011);
        String[] skuArray = this.mContext.getResources().getStringArray(17236010);
        if (ArrayUtils.isEmpty(apkList)) {
            return;
        }
        String sku = SystemProperties.get("ro.boot.hardware.sku");
        if (!TextUtils.isEmpty(sku) && ArrayUtils.contains(skuArray, sku)) {
            return;
        }
        for (String packageName : apkList) {
            setSystemAppHiddenUntilInstalled(packageName, true);
            for (UserInfo user : sUserManager.getUsers(false)) {
                setSystemAppInstallState(packageName, false, user.id);
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (this.mPackages) {
            long requiredVerifierPackageToken = proto.start(1146756268033L);
            proto.write(1138166333441L, this.mRequiredVerifierPackage);
            proto.write(1120986464258L, getPackageUid(this.mRequiredVerifierPackage, 268435456, 0));
            proto.end(requiredVerifierPackageToken);
            if (this.mIntentFilterVerifierComponent != null) {
                String verifierPackageName = this.mIntentFilterVerifierComponent.getPackageName();
                long verifierPackageToken = proto.start(1146756268034L);
                proto.write(1138166333441L, verifierPackageName);
                proto.write(1120986464258L, getPackageUid(verifierPackageName, 268435456, 0));
                proto.end(verifierPackageToken);
            }
            dumpSharedLibrariesProto(proto);
            dumpFeaturesProto(proto);
            this.mSettings.dumpPackagesProto(proto);
            this.mSettings.dumpSharedUsersProto(proto);
            PackageManagerServiceUtils.dumpCriticalInfo(proto);
        }
        proto.flush();
    }

    private void dumpFeaturesProto(ProtoOutputStream proto) {
        synchronized (this.mAvailableFeatures) {
            int count = this.mAvailableFeatures.size();
            for (int i = 0; i < count; i++) {
                this.mAvailableFeatures.valueAt(i).writeToProto(proto, 2246267895812L);
            }
        }
    }

    private void dumpSharedLibrariesProto(ProtoOutputStream proto) {
        int count = this.mSharedLibraries.size();
        for (int i = 0; i < count; i++) {
            String libName = this.mSharedLibraries.keyAt(i);
            LongSparseArray<SharedLibraryInfo> versionedLib = this.mSharedLibraries.get(libName);
            if (versionedLib != null) {
                int versionCount = versionedLib.size();
                for (int j = 0; j < versionCount; j++) {
                    SharedLibraryInfo libraryInfo = versionedLib.valueAt(j);
                    long sharedLibraryToken = proto.start(2246267895811L);
                    proto.write(1138166333441L, libraryInfo.getName());
                    boolean isJar = libraryInfo.getPath() != null;
                    proto.write(1133871366146L, isJar);
                    if (isJar) {
                        proto.write(1138166333443L, libraryInfo.getPath());
                    } else {
                        proto.write(1138166333444L, libraryInfo.getPackageName());
                    }
                    proto.end(sharedLibraryToken);
                }
            }
        }
    }

    @GuardedBy({"mPackages"})
    private void dumpDexoptStateLPr(PrintWriter pw, String packageName) {
        Collection<PackageParser.Package> packages;
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println();
        ipw.println("Dexopt state:");
        ipw.increaseIndent();
        if (packageName != null) {
            PackageParser.Package targetPackage = this.mPackages.get(packageName);
            if (targetPackage != null) {
                packages = Collections.singletonList(targetPackage);
            } else {
                ipw.println("Unable to find package: " + packageName);
                return;
            }
        } else {
            packages = this.mPackages.values();
        }
        for (PackageParser.Package pkg : packages) {
            ipw.println("[" + pkg.packageName + "]");
            ipw.increaseIndent();
            this.mPackageDexOptimizer.dumpDexoptState(ipw, pkg, this.mDexManager.getPackageUseInfoOrDefault(pkg.packageName));
            ipw.decreaseIndent();
        }
    }

    @GuardedBy({"mPackages"})
    private void dumpCompilerStatsLPr(PrintWriter pw, String packageName) {
        Collection<PackageParser.Package> packages;
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println();
        ipw.println("Compiler stats:");
        ipw.increaseIndent();
        if (packageName != null) {
            PackageParser.Package targetPackage = this.mPackages.get(packageName);
            if (targetPackage != null) {
                packages = Collections.singletonList(targetPackage);
            } else {
                ipw.println("Unable to find package: " + packageName);
                return;
            }
        } else {
            packages = this.mPackages.values();
        }
        for (PackageParser.Package pkg : packages) {
            ipw.println("[" + pkg.packageName + "]");
            ipw.increaseIndent();
            CompilerStats.PackageStats stats = getCompilerPackageStats(pkg.packageName);
            if (stats == null) {
                ipw.println("(No recorded stats)");
            } else {
                stats.dump(ipw);
            }
            ipw.decreaseIndent();
        }
    }

    private String dumpDomainString(String packageName) {
        List<IntentFilterVerificationInfo> iviList = getIntentFilterVerifications(packageName).getList();
        List<IntentFilter> filters = getAllIntentFilters(packageName).getList();
        ArraySet<String> result = new ArraySet<>();
        if (iviList.size() > 0) {
            for (IntentFilterVerificationInfo ivi : iviList) {
                result.addAll(ivi.getDomains());
            }
        }
        if (filters != null && filters.size() > 0) {
            for (IntentFilter filter : filters) {
                if (filter.hasCategory("android.intent.category.BROWSABLE") && (filter.hasDataScheme("http") || filter.hasDataScheme("https"))) {
                    result.addAll(filter.getHostsList());
                }
            }
        }
        StringBuilder sb = new StringBuilder(result.size() * 16);
        Iterator<String> it = result.iterator();
        while (it.hasNext()) {
            String domain = it.next();
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(domain);
        }
        return sb.toString();
    }

    static String getEncryptKey() {
        try {
            String sdEncKey = SystemKeyStore.getInstance().retrieveKeyHexString(SD_ENCRYPTION_KEYSTORE_NAME);
            if (sdEncKey == null && (sdEncKey = SystemKeyStore.getInstance().generateNewKeyHexString(128, SD_ENCRYPTION_ALGORITHM, SD_ENCRYPTION_KEYSTORE_NAME)) == null) {
                Slog.e(TAG, "Failed to create encryption keys");
                return null;
            }
            return sdEncKey;
        } catch (IOException ioe) {
            Slog.e(TAG, "Failed to retrieve encryption keys with exception: " + ioe);
            return null;
        } catch (NoSuchAlgorithmException nsae) {
            Slog.e(TAG, "Failed to create encryption keys with exception: " + nsae);
            return null;
        }
    }

    private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing, ArrayList<ApplicationInfo> infos, IIntentReceiver finishedReceiver) {
        int size = infos.size();
        String[] packageNames = new String[size];
        int[] packageUids = new int[size];
        for (int i = 0; i < size; i++) {
            ApplicationInfo info = infos.get(i);
            packageNames[i] = info.packageName;
            packageUids[i] = info.uid;
        }
        sendResourcesChangedBroadcast(mediaStatus, replacing, packageNames, packageUids, finishedReceiver);
    }

    private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing, ArrayList<String> pkgList, int[] uidArr, IIntentReceiver finishedReceiver) {
        sendResourcesChangedBroadcast(mediaStatus, replacing, (String[]) pkgList.toArray(new String[pkgList.size()]), uidArr, finishedReceiver);
    }

    private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing, String[] pkgList, int[] uidArr, IIntentReceiver finishedReceiver) {
        int size = pkgList.length;
        if (size > 0) {
            Bundle extras = new Bundle();
            extras.putStringArray("android.intent.extra.changed_package_list", pkgList);
            if (uidArr != null) {
                extras.putIntArray("android.intent.extra.changed_uid_list", uidArr);
            }
            if (replacing) {
                extras.putBoolean("android.intent.extra.REPLACING", replacing);
            }
            String action = mediaStatus ? "android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE" : "android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE";
            sendPackageBroadcast(action, null, extras, 0, null, finishedReceiver, null, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadPrivatePackages(final VolumeInfo vol) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$AaKgqc5zhv3JFvjLGzRCsG1oLBA
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$loadPrivatePackages$32$PackageManagerService(vol);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't wrap try/catch for region: R(10:42|(2:62|63)(2:44|(2:46|47)(2:61|57))|48|49|106|54|55|56|57|40) */
    /* JADX WARN: Code restructure failed: missing block: B:52:0x0111, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:53:0x0112, code lost:
        android.util.Slog.w(com.android.server.pm.PackageManagerService.TAG, "Failed to prepare storage: " + r0);
     */
    /* JADX WARN: Removed duplicated region for block: B:30:0x00aa A[Catch: all -> 0x006f, TryCatch #7 {all -> 0x006f, blocks: (B:17:0x0065, B:28:0x00a0, B:30:0x00aa, B:31:0x00b2, B:33:0x00b6, B:27:0x007c), top: B:77:0x0065 }] */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:83:? -> B:19:0x006f). Please submit an issue!!! */
    /* renamed from: loadPrivatePackagesInner */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void lambda$loadPrivatePackages$32$PackageManagerService(android.os.storage.VolumeInfo r21) {
        /*
            Method dump skipped, instructions count: 416
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.lambda$loadPrivatePackages$32$PackageManagerService(android.os.storage.VolumeInfo):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unloadPrivatePackages(final VolumeInfo vol) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$7hDqO17HZYwQzSXIwFiBZMFccTA
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$unloadPrivatePackages$33$PackageManagerService(vol);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: unloadPrivatePackagesInner */
    public void lambda$unloadPrivatePackages$33$PackageManagerService(VolumeInfo vol) {
        PackageFreezer freezer;
        Throwable th;
        StringBuilder sb;
        PackageSetting ps;
        String volumeUuid = vol.fsUuid;
        if (TextUtils.isEmpty(volumeUuid)) {
            Slog.e(TAG, "Unloading internal storage is probably a mistake; ignoring");
            return;
        }
        ArrayList<ApplicationInfo> unloaded = new ArrayList<>();
        synchronized (this.mInstallLock) {
            synchronized (this.mPackages) {
                List<PackageSetting> packages = this.mSettings.getVolumePackagesLPr(volumeUuid);
                Iterator<PackageSetting> it = packages.iterator();
                while (it.hasNext()) {
                    PackageSetting ps2 = it.next();
                    if (ps2.pkg != null) {
                        ApplicationInfo info = ps2.pkg.applicationInfo;
                        PackageRemovedInfo outInfo = new PackageRemovedInfo(this);
                        PackageFreezer freezer2 = freezePackageForDelete(ps2.name, 1, "unloadPrivatePackagesInner");
                        try {
                            Iterator<PackageSetting> it2 = it;
                            try {
                                if (!deletePackageLIF(ps2.name, null, false, null, 1, outInfo, false, null)) {
                                    try {
                                        sb = new StringBuilder();
                                        sb.append("Failed to unload ");
                                        ps = ps2;
                                    } catch (Throwable th2) {
                                        freezer = freezer2;
                                        th = th2;
                                    }
                                    try {
                                        sb.append(ps.codePath);
                                        Slog.w(TAG, sb.toString());
                                    } catch (Throwable th3) {
                                        freezer = freezer2;
                                        th = th3;
                                        throw th;
                                    }
                                } else {
                                    try {
                                        unloaded.add(info);
                                        ps = ps2;
                                    } catch (Throwable th4) {
                                        th = th4;
                                        freezer = freezer2;
                                        try {
                                            throw th;
                                        } catch (Throwable th5) {
                                            if (freezer != null) {
                                                $closeResource(th, freezer);
                                            }
                                            throw th5;
                                        }
                                    }
                                }
                                if (freezer2 != null) {
                                    $closeResource(null, freezer2);
                                }
                                AttributeCache.instance().removePackage(ps.name);
                                it = it2;
                            } catch (Throwable th6) {
                                freezer = freezer2;
                                th = th6;
                            }
                        } catch (Throwable th7) {
                            freezer = freezer2;
                            th = th7;
                        }
                    }
                }
                this.mSettings.writeLPr();
            }
        }
        sendResourcesChangedBroadcast(false, false, unloaded, null);
        this.mLoadedVolumes.remove(vol.getId());
        ResourcesManager.getInstance().invalidatePath(vol.getPath().getAbsolutePath());
        for (int i = 0; i < 3; i++) {
            System.gc();
            System.runFinalization();
        }
    }

    private void assertPackageKnownAndInstalled(String volumeUuid, String packageName, int userId) throws PackageManagerException {
        synchronized (this.mPackages) {
            String packageName2 = normalizePackageNameLPr(packageName);
            PackageSetting ps = this.mSettings.mPackages.get(packageName2);
            if (ps == null) {
                throw new PackageManagerException("Package " + packageName2 + " is unknown");
            } else if (!TextUtils.equals(volumeUuid, ps.volumeUuid)) {
                throw new PackageManagerException("Package " + packageName2 + " found on unknown volume " + volumeUuid + "; expected volume " + ps.volumeUuid);
            } else if (!ps.getInstalled(userId)) {
                throw new PackageManagerException("Package " + packageName2 + " not installed for user " + userId);
            }
        }
    }

    private List<String> collectAbsoluteCodePaths() {
        List<String> codePaths;
        synchronized (this.mPackages) {
            codePaths = new ArrayList<>();
            int packageCount = this.mSettings.mPackages.size();
            for (int i = 0; i < packageCount; i++) {
                PackageSetting ps = this.mSettings.mPackages.valueAt(i);
                codePaths.add(ps.codePath.getAbsolutePath());
            }
        }
        return codePaths;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reconcileApps(String volumeUuid) {
        List<String> absoluteCodePaths = collectAbsoluteCodePaths();
        File[] files = FileUtils.listFilesOrEmpty(Environment.getDataAppDirectory(volumeUuid));
        List<File> filesToDelete = null;
        for (File file : files) {
            boolean isPackage = (PackageParser.isApkFile(file) || file.isDirectory()) && !PackageInstallerService.isStageName(file.getName());
            if (isPackage) {
                String absolutePath = file.getAbsolutePath();
                boolean pathValid = false;
                int absoluteCodePathCount = absoluteCodePaths.size();
                int i = 0;
                while (true) {
                    if (i >= absoluteCodePathCount) {
                        break;
                    }
                    String absoluteCodePath = absoluteCodePaths.get(i);
                    if (!absolutePath.startsWith(absoluteCodePath)) {
                        i++;
                    } else {
                        pathValid = true;
                        break;
                    }
                }
                if (!pathValid) {
                    if (filesToDelete == null) {
                        filesToDelete = new ArrayList<>();
                    }
                    filesToDelete.add(file);
                }
            }
        }
        if (filesToDelete != null) {
            int fileToDeleteCount = filesToDelete.size();
            for (int i2 = 0; i2 < fileToDeleteCount; i2++) {
                File fileToDelete = filesToDelete.get(i2);
                PackageManagerServiceUtils.logCriticalInfo(5, "Destroying orphaned" + fileToDelete);
                synchronized (this.mInstallLock) {
                    removeCodePathLI(fileToDelete);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reconcileAppsData(int userId, int flags, boolean migrateAppsData) {
        StorageManager storage = (StorageManager) this.mContext.getSystemService(StorageManager.class);
        for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
            String volumeUuid = vol.getFsUuid();
            synchronized (this.mInstallLock) {
                reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppsData);
            }
        }
    }

    @GuardedBy({"mInstallLock"})
    private void reconcileAppsDataLI(String volumeUuid, int userId, int flags, boolean migrateAppData) {
        reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppData, false);
    }

    @GuardedBy({"mInstallLock"})
    private List<String> reconcileAppsDataLI(String volumeUuid, int userId, int flags, boolean migrateAppData, boolean onlyCoreApps) {
        int i;
        List<PackageSetting> packages;
        int i2;
        File[] files;
        int i3;
        int i4;
        int i5;
        File[] files2;
        File ceDir;
        int i6;
        Slog.v(TAG, "reconcileAppsData for " + volumeUuid + " u" + userId + " 0x" + Integer.toHexString(flags) + " migrateAppData=" + migrateAppData);
        addBootEvent("PMS:reconcileAppsDataLI");
        List<String> result = onlyCoreApps ? new ArrayList<>() : null;
        File ceDir2 = Environment.getDataUserCeDirectory(volumeUuid, userId);
        File deDir = Environment.getDataUserDeDirectory(volumeUuid, userId);
        if ((flags & 2) == 0) {
            i = 5;
        } else if (StorageManager.isFileEncryptedNativeOrEmulated() && !StorageManager.isUserKeyUnlocked(userId)) {
            throw new RuntimeException("Yikes, someone asked us to reconcile CE storage while " + userId + " was still locked; this would have caused massive data loss!");
        } else {
            File[] files3 = FileUtils.listFilesOrEmpty(ceDir2);
            int length = files3.length;
            int i7 = 0;
            while (i7 < length) {
                File file = files3[i7];
                String packageName = file.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName, userId);
                    i4 = i7;
                    i5 = length;
                    files2 = files3;
                    ceDir = ceDir2;
                } catch (PackageManagerException e) {
                    PackageManagerServiceUtils.logCriticalInfo(5, "Destroying " + file + " due to: " + e);
                    try {
                        i4 = i7;
                        i5 = length;
                        files2 = files3;
                        ceDir = ceDir2;
                        i6 = 5;
                    } catch (Installer.InstallerException e2) {
                        e2 = e2;
                        i4 = i7;
                        i5 = length;
                        files2 = files3;
                        ceDir = ceDir2;
                        i6 = 5;
                    }
                    try {
                        this.mInstaller.destroyAppData(volumeUuid, packageName, userId, 2, 0L);
                    } catch (Installer.InstallerException e3) {
                        e2 = e3;
                        PackageManagerServiceUtils.logCriticalInfo(i6, "Failed to destroy: " + e2);
                        i7 = i4 + 1;
                        files3 = files2;
                        length = i5;
                        ceDir2 = ceDir;
                    }
                }
                i7 = i4 + 1;
                files3 = files2;
                length = i5;
                ceDir2 = ceDir;
            }
            i = 5;
        }
        if ((flags & 1) != 0) {
            File[] files4 = FileUtils.listFilesOrEmpty(deDir);
            int length2 = files4.length;
            int i8 = 0;
            while (i8 < length2) {
                File file2 = files4[i8];
                String packageName2 = file2.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName2, userId);
                    i2 = i8;
                    files = files4;
                    i3 = length2;
                } catch (PackageManagerException e4) {
                    PackageManagerServiceUtils.logCriticalInfo(i, "Destroying " + file2 + " due to: " + e4);
                    try {
                        i2 = i8;
                        files = files4;
                        i3 = length2;
                    } catch (Installer.InstallerException e5) {
                        e2 = e5;
                        i2 = i8;
                        files = files4;
                        i3 = length2;
                    }
                    try {
                        this.mInstaller.destroyAppData(volumeUuid, packageName2, userId, 1, 0L);
                    } catch (Installer.InstallerException e6) {
                        e2 = e6;
                        PackageManagerServiceUtils.logCriticalInfo(i, "Failed to destroy: " + e2);
                        i8 = i2 + 1;
                        files4 = files;
                        length2 = i3;
                    }
                }
                i8 = i2 + 1;
                files4 = files;
                length2 = i3;
            }
        }
        synchronized (this.mPackages) {
            packages = this.mSettings.getVolumePackagesLPr(volumeUuid);
        }
        int preparedCount = 0;
        for (PackageSetting ps : packages) {
            String packageName3 = ps.name;
            if (ps.pkg == null) {
                Slog.w(TAG, "Odd, missing scanned package " + packageName3);
            } else if (onlyCoreApps && !ps.pkg.coreApp) {
                result.add(packageName3);
            } else if (ps.getInstalled(userId)) {
                prepareAppDataAndMigrateLIF(ps.pkg, userId, flags, migrateAppData);
                preparedCount++;
            }
        }
        Slog.v(TAG, "reconcileAppsData finished " + preparedCount + " packages");
        return result;
    }

    private void prepareAppDataAfterInstallLIF(PackageParser.Package pkg) {
        PackageSetting ps;
        int flags;
        synchronized (this.mPackages) {
            ps = this.mSettings.mPackages.get(pkg.packageName);
            this.mSettings.writeKernelMappingLPr(ps);
        }
        UserManagerService um = sUserManager;
        UserManagerInternal umInternal = getUserManagerInternal();
        for (UserInfo user : um.getUsers(false)) {
            if (umInternal.isUserUnlockingOrUnlocked(user.id)) {
                flags = 3;
            } else {
                int flags2 = user.id;
                if (umInternal.isUserRunning(flags2)) {
                    flags = 1;
                }
            }
            if (ps.getInstalled(user.id)) {
                prepareAppDataLIF(pkg, user.id, flags);
            }
        }
    }

    private void prepareAppDataLIF(PackageParser.Package pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        prepareAppDataLeafLIF(pkg, userId, flags);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            prepareAppDataLeafLIF((PackageParser.Package) pkg.childPackages.get(i), userId, flags);
        }
    }

    private void prepareAppDataAndMigrateLIF(PackageParser.Package pkg, int userId, int flags, boolean maybeMigrateAppData) {
        prepareAppDataLIF(pkg, userId, flags);
        if (maybeMigrateAppData && maybeMigrateAppDataLIF(pkg, userId)) {
            prepareAppDataLIF(pkg, userId, flags);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:47:0x00fd  */
    /* JADX WARN: Removed duplicated region for block: B:53:0x010d  */
    /* JADX WARN: Removed duplicated region for block: B:57:0x0116  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void prepareAppDataLeafLIF(android.content.pm.PackageParser.Package r23, int r24, int r25) {
        /*
            Method dump skipped, instructions count: 295
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.prepareAppDataLeafLIF(android.content.pm.PackageParser$Package, int, int):void");
    }

    private void prepareAppDataContentsLIF(PackageParser.Package pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        prepareAppDataContentsLeafLIF(pkg, userId, flags);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            prepareAppDataContentsLeafLIF((PackageParser.Package) pkg.childPackages.get(i), userId, flags);
        }
    }

    private void prepareAppDataContentsLeafLIF(PackageParser.Package pkg, int userId, int flags) {
        String volumeUuid = pkg.volumeUuid;
        String packageName = pkg.packageName;
        ApplicationInfo app = pkg.applicationInfo;
        if ((flags & 2) != 0 && app.primaryCpuAbi != null && !VMRuntime.is64BitAbi(app.primaryCpuAbi)) {
            String nativeLibPath = app.nativeLibraryDir;
            try {
                this.mInstaller.linkNativeLibraryDirectory(volumeUuid, packageName, nativeLibPath, userId);
            } catch (Installer.InstallerException e) {
                Slog.e(TAG, "Failed to link native for " + packageName + ": " + e);
            }
        }
    }

    private boolean maybeMigrateAppDataLIF(PackageParser.Package pkg, int userId) {
        if (pkg.isSystem() && !StorageManager.isFileEncryptedNativeOrEmulated()) {
            int storageTarget = pkg.applicationInfo.isDefaultToDeviceProtectedStorage() ? 1 : 2;
            try {
                this.mInstaller.migrateAppData(pkg.volumeUuid, pkg.packageName, userId, storageTarget);
            } catch (Installer.InstallerException e) {
                PackageManagerServiceUtils.logCriticalInfo(5, "Failed to migrate " + pkg.packageName + ": " + e.getMessage());
            }
            return true;
        }
        return false;
    }

    public PackageFreezer freezePackage(String packageName, String killReason) {
        return freezePackage(packageName, -1, killReason);
    }

    public PackageFreezer freezePackage(String packageName, int userId, String killReason) {
        return new PackageFreezer(packageName, userId, killReason);
    }

    public PackageFreezer freezePackageForInstall(String packageName, int installFlags, String killReason) {
        return freezePackageForInstall(packageName, -1, installFlags, killReason);
    }

    public PackageFreezer freezePackageForInstall(String packageName, int userId, int installFlags, String killReason) {
        if ((installFlags & 4096) != 0) {
            return new PackageFreezer();
        }
        return freezePackage(packageName, userId, killReason);
    }

    public PackageFreezer freezePackageForDelete(String packageName, int deleteFlags, String killReason) {
        return freezePackageForDelete(packageName, -1, deleteFlags, killReason);
    }

    public PackageFreezer freezePackageForDelete(String packageName, int userId, int deleteFlags, String killReason) {
        if ((deleteFlags & 8) != 0) {
            return new PackageFreezer();
        }
        return freezePackage(packageName, userId, killReason);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PackageFreezer implements AutoCloseable {
        private final PackageFreezer[] mChildren;
        private final CloseGuard mCloseGuard;
        private final AtomicBoolean mClosed;
        private final String mPackageName;
        private final boolean mWeFroze;

        public PackageFreezer() {
            this.mClosed = new AtomicBoolean();
            this.mCloseGuard = CloseGuard.get();
            this.mPackageName = null;
            this.mChildren = null;
            this.mWeFroze = false;
            this.mCloseGuard.open("close");
        }

        public PackageFreezer(String packageName, int userId, String killReason) {
            this.mClosed = new AtomicBoolean();
            this.mCloseGuard = CloseGuard.get();
            synchronized (PackageManagerService.this.mPackages) {
                this.mPackageName = packageName;
                this.mWeFroze = PackageManagerService.this.mFrozenPackages.add(this.mPackageName);
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(this.mPackageName);
                if (ps != null) {
                    PackageManagerService.this.killApplication(ps.name, ps.appId, userId, killReason);
                }
                PackageParser.Package p = PackageManagerService.this.mPackages.get(packageName);
                if (p != null && p.childPackages != null) {
                    int N = p.childPackages.size();
                    this.mChildren = new PackageFreezer[N];
                    for (int i = 0; i < N; i++) {
                        this.mChildren[i] = new PackageFreezer(((PackageParser.Package) p.childPackages.get(i)).packageName, userId, killReason);
                    }
                } else {
                    this.mChildren = null;
                }
            }
            this.mCloseGuard.open("close");
        }

        protected void finalize() throws Throwable {
            try {
                this.mCloseGuard.warnIfOpen();
                close();
            } finally {
                super.finalize();
            }
        }

        @Override // java.lang.AutoCloseable
        public void close() {
            PackageFreezer[] packageFreezerArr;
            this.mCloseGuard.close();
            if (this.mClosed.compareAndSet(false, true)) {
                synchronized (PackageManagerService.this.mPackages) {
                    if (this.mWeFroze) {
                        PackageManagerService.this.mFrozenPackages.remove(this.mPackageName);
                    }
                    if (this.mChildren != null) {
                        for (PackageFreezer freezer : this.mChildren) {
                            freezer.close();
                        }
                    }
                }
            }
        }
    }

    private void checkPackageFrozen(String packageName) {
        synchronized (this.mPackages) {
            if (!this.mFrozenPackages.contains(packageName)) {
                Slog.wtf(TAG, "Expected " + packageName + " to be frozen!", new Throwable());
            }
        }
    }

    public int movePackage(final String packageName, final String volumeUuid) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MOVE_PACKAGE", null);
        final int callingUid = Binder.getCallingUid();
        final UserHandle user = new UserHandle(UserHandle.getUserId(callingUid));
        final int moveId = this.mNextMoveId.getAndIncrement();
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$3hVyw8Ur_HYK6lQHCJU4WB7sbis
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.this.lambda$movePackage$34$PackageManagerService(packageName, volumeUuid, moveId, callingUid, user);
            }
        });
        return moveId;
    }

    public /* synthetic */ void lambda$movePackage$34$PackageManagerService(String packageName, String volumeUuid, int moveId, int callingUid, UserHandle user) {
        try {
            movePackageInternal(packageName, volumeUuid, moveId, callingUid, user);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to move " + packageName, e);
            this.mMoveCallbacks.notifyStatusChanged(moveId, e.error);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:73:0x01d9, code lost:
        r4.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:76:0x01e8, code lost:
        throw new com.android.server.pm.PackageManagerException(-6, "Failed to measure package size");
     */
    /* JADX WARN: Code restructure failed: missing block: B:77:0x01e9, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:83:0x0212, code lost:
        r45 = r1.getUsableSpace();
     */
    /* JADX WARN: Code restructure failed: missing block: B:84:0x0216, code lost:
        if (r44 == false) goto L99;
     */
    /* JADX WARN: Code restructure failed: missing block: B:85:0x0218, code lost:
        r24 = r4;
        r47 = r0.codeSize + r0.dataSize;
     */
    /* JADX WARN: Code restructure failed: missing block: B:86:0x0222, code lost:
        r24 = r4;
        r47 = r0.codeSize;
     */
    /* JADX WARN: Code restructure failed: missing block: B:88:0x022e, code lost:
        if (r47 > r12.getStorageBytesUntilLow(r1)) goto L97;
     */
    /* JADX WARN: Code restructure failed: missing block: B:89:0x0230, code lost:
        r56.mMoveCallbacks.notifyStatusChanged(r59, 10);
        r3 = new java.util.concurrent.CountDownLatch(1);
        r50 = r1;
        r21 = new com.android.server.pm.PackageManagerService.AnonymousClass9(r56);
     */
    /* JADX WARN: Code restructure failed: missing block: B:90:0x0254, code lost:
        if (r44 == false) goto L96;
     */
    /* JADX WARN: Code restructure failed: missing block: B:91:0x0256, code lost:
        r2 = r56;
        r14 = r47;
        new java.lang.Thread(new com.android.server.pm.$$Lambda$PackageManagerService$VFweAjInAqFvcaIe8ZPXteinKmo(r56, r3, r45, r50, r14, r59)).start();
        r0 = r3.getName();
        r0 = new com.android.server.pm.PackageManagerService.MoveInfo(r59, r24, r58, r57, r0, r40, r3, r4);
     */
    /* JADX WARN: Code restructure failed: missing block: B:92:0x028e, code lost:
        r2 = r56;
        r0 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:93:0x0297, code lost:
        r1 = 16 | 2;
        r8 = r2.mHandler.obtainMessage(5);
        r9 = com.android.server.pm.PackageManagerService.OriginInfo.fromExistingFile(r3);
        r10 = new com.android.server.pm.PackageManagerService.InstallParams(r56, r9, r0, r21, r1, r3, r58, null, r61, r3, null, null, android.content.pm.PackageParser.SigningDetails.UNKNOWN, 0, -1);
        r10.setTraceMethod("movePackage").setTraceCookie(java.lang.System.identityHashCode(r10));
        r8.obj = r10;
        android.os.Trace.asyncTraceBegin(262144, "movePackage", java.lang.System.identityHashCode(r8.obj));
        android.os.Trace.asyncTraceBegin(262144, "queueInstall", java.lang.System.identityHashCode(r8.obj));
        r2.mHandler.sendMessage(r8);
     */
    /* JADX WARN: Code restructure failed: missing block: B:94:0x02f3, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:95:0x02f4, code lost:
        r4.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:96:0x0301, code lost:
        throw new com.android.server.pm.PackageManagerException(-6, "Not enough free space to move");
     */
    /* JADX WARN: Code restructure failed: missing block: B:97:0x0302, code lost:
        r0 = th;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void movePackageInternal(final java.lang.String r57, java.lang.String r58, final int r59, int r60, android.os.UserHandle r61) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 972
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.movePackageInternal(java.lang.String, java.lang.String, int, int, android.os.UserHandle):void");
    }

    public /* synthetic */ void lambda$movePackageInternal$35$PackageManagerService(CountDownLatch installedLatch, long startFreeBytes, File measurePath, long sizeBytes, int moveId) {
        while (!installedLatch.await(1L, TimeUnit.SECONDS)) {
            long deltaFreeBytes = startFreeBytes - measurePath.getUsableSpace();
            int progress = ((int) MathUtils.constrain((80 * deltaFreeBytes) / sizeBytes, 0L, 80L)) + 10;
            this.mMoveCallbacks.notifyStatusChanged(moveId, progress);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logAppMovedStorage(String packageName, boolean isPreviousLocationExternal) {
        PackageParser.Package pkg;
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(packageName);
        }
        if (pkg == null) {
            return;
        }
        StorageManager storage = (StorageManager) this.mContext.getSystemService(StorageManager.class);
        VolumeInfo volume = storage.findVolumeByUuid(pkg.applicationInfo.storageUuid.toString());
        int packageExternalStorageType = getPackageExternalStorageType(volume, isExternal(pkg));
        if (!isPreviousLocationExternal && isExternal(pkg)) {
            StatsLog.write(183, packageExternalStorageType, 1, packageName);
        } else if (isPreviousLocationExternal && !isExternal(pkg)) {
            StatsLog.write(183, packageExternalStorageType, 2, packageName);
        }
    }

    public int movePrimaryStorage(String volumeUuid) throws RemoteException {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MOVE_PACKAGE", null);
        final int realMoveId = this.mNextMoveId.getAndIncrement();
        Bundle extras = new Bundle();
        extras.putString("android.os.storage.extra.FS_UUID", volumeUuid);
        this.mMoveCallbacks.notifyCreated(realMoveId, extras);
        IPackageMoveObserver callback = new IPackageMoveObserver.Stub() { // from class: com.android.server.pm.PackageManagerService.10
            public void onCreated(int moveId, Bundle extras2) {
            }

            public void onStatusChanged(int moveId, int status, long estMillis) {
                PackageManagerService.this.mMoveCallbacks.notifyStatusChanged(realMoveId, status, estMillis);
            }
        };
        StorageManager storage = (StorageManager) this.mContext.getSystemService(StorageManager.class);
        storage.setPrimaryStorageUuid(volumeUuid, callback);
        return realMoveId;
    }

    public int getMoveStatus(int moveId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MOUNT_UNMOUNT_FILESYSTEMS", null);
        return this.mMoveCallbacks.mLastStatus.get(moveId);
    }

    public void registerMoveCallback(IPackageMoveObserver callback) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MOUNT_UNMOUNT_FILESYSTEMS", null);
        this.mMoveCallbacks.register(callback);
    }

    public void unregisterMoveCallback(IPackageMoveObserver callback) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MOUNT_UNMOUNT_FILESYSTEMS", null);
        this.mMoveCallbacks.unregister(callback);
    }

    public boolean setInstallLocation(int loc) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS", null);
        if (getInstallLocation() == loc) {
            return true;
        }
        if (loc == 0 || loc == 1 || loc == 2) {
            Settings.Global.putInt(this.mContext.getContentResolver(), "default_install_location", loc);
            return true;
        }
        return false;
    }

    public int getInstallLocation() {
        return Settings.Global.getInt(this.mContext.getContentResolver(), "default_install_location", 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cleanUpUser(UserManagerService userManager, int userHandle) {
        synchronized (this.mPackages) {
            this.mDirtyUsers.remove(Integer.valueOf(userHandle));
            this.mUserNeedsBadging.delete(userHandle);
            this.mSettings.removeUserLPw(userHandle);
            this.mPendingBroadcasts.remove(userHandle);
            this.mInstantAppRegistry.onUserRemovedLPw(userHandle);
            removeUnusedPackagesLPw(userManager, userHandle);
        }
    }

    @GuardedBy({"mPackages"})
    private void removeUnusedPackagesLPw(UserManagerService userManager, final int userHandle) {
        int[] users = userManager.getUserIds();
        for (PackageSetting ps : this.mSettings.mPackages.values()) {
            if (ps.pkg != null) {
                final String packageName = ps.pkg.packageName;
                if ((ps.pkgFlags & 1) == 0 && TextUtils.isEmpty(ps.pkg.staticSharedLibName)) {
                    boolean keep = shouldKeepUninstalledPackageLPr(packageName);
                    if (!keep) {
                        int i = 0;
                        while (true) {
                            if (i >= users.length) {
                                break;
                            } else if (users[i] == userHandle || !ps.getInstalled(users[i])) {
                                i++;
                            } else {
                                keep = true;
                                break;
                            }
                        }
                    }
                    if (!keep) {
                        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$eOBF7qUUJMB2a1vS9XEnV8gm7kQ
                            @Override // java.lang.Runnable
                            public final void run() {
                                PackageManagerService.this.lambda$removeUnusedPackagesLPw$36$PackageManagerService(packageName, userHandle);
                            }
                        });
                    }
                }
            }
        }
    }

    public /* synthetic */ void lambda$removeUnusedPackagesLPw$36$PackageManagerService(String packageName, int userHandle) {
        if (!this.mBlockDeleteOnUserRemoveForTest.block(30000L)) {
            this.mBlockDeleteOnUserRemoveForTest.open();
        }
        deletePackageX(packageName, -1L, userHandle, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createNewUser(int userId, String[] disallowedPackages) {
        synchronized (this.mInstallLock) {
            this.mSettings.createNewUserLI(this, this.mInstaller, userId, disallowedPackages);
        }
        synchronized (this.mPackages) {
            scheduleWritePackageRestrictionsLocked(userId);
            scheduleWritePackageListLocked(userId);
            primeDomainVerificationsLPw(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNewUserCreated(int userId) {
        this.mDefaultPermissionPolicy.grantDefaultPermissions(userId);
        synchronized (this.mPackages) {
            this.mPermissionManager.updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, true, this.mPackages.values(), this.mPermissionCallback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean readPermissionStateForUser(int userId) {
        boolean areDefaultRuntimePermissionsGrantedLPr;
        synchronized (this.mPackages) {
            this.mSettings.readPermissionStateForUserSyncLPr(userId);
            areDefaultRuntimePermissionsGrantedLPr = this.mSettings.areDefaultRuntimePermissionsGrantedLPr(userId);
        }
        return areDefaultRuntimePermissionsGrantedLPr;
    }

    public VerifierDeviceIdentity getVerifierDeviceIdentity() throws RemoteException {
        VerifierDeviceIdentity verifierDeviceIdentityLPw;
        this.mContext.enforceCallingOrSelfPermission("android.permission.PACKAGE_VERIFICATION_AGENT", "Only package verification agents can read the verifier device identity");
        synchronized (this.mPackages) {
            verifierDeviceIdentityLPw = this.mSettings.getVerifierDeviceIdentityLPw();
        }
        return verifierDeviceIdentityLPw;
    }

    public void setPermissionEnforced(String permission, boolean enforced) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.GRANT_RUNTIME_PERMISSIONS", "setPermissionEnforced");
        if ("android.permission.READ_EXTERNAL_STORAGE".equals(permission)) {
            synchronized (this.mPackages) {
                if (this.mSettings.mReadExternalStorageEnforced == null || this.mSettings.mReadExternalStorageEnforced.booleanValue() != enforced) {
                    this.mSettings.mReadExternalStorageEnforced = enforced ? Boolean.TRUE : Boolean.FALSE;
                    this.mSettings.writeLPr();
                }
            }
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                long token = Binder.clearCallingIdentity();
                try {
                    am.killProcessesBelowForeground("setPermissionEnforcement");
                } catch (RemoteException e) {
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(token);
                    throw th;
                }
                Binder.restoreCallingIdentity(token);
                return;
            }
            return;
        }
        throw new IllegalArgumentException("No selective enforcement for " + permission);
    }

    @Deprecated
    public boolean isPermissionEnforced(String permission) {
        return true;
    }

    public boolean isStorageLow() {
        long token = Binder.clearCallingIdentity();
        try {
            DeviceStorageMonitorInternal dsm = (DeviceStorageMonitorInternal) LocalServices.getService(DeviceStorageMonitorInternal.class);
            if (dsm != null) {
                return dsm.isMemoryLow();
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public IPackageInstaller getPackageInstaller() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return this.mInstallerService;
    }

    public IArtManager getArtManager() {
        return this.mArtManagerService;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean userNeedsBadging(int userId) {
        boolean b;
        int index = this.mUserNeedsBadging.indexOfKey(userId);
        if (index < 0) {
            long token = Binder.clearCallingIdentity();
            try {
                UserInfo userInfo = sUserManager.getUserInfo(userId);
                if (userInfo != null && userInfo.isManagedProfile()) {
                    b = true;
                } else {
                    b = false;
                }
                this.mUserNeedsBadging.put(userId, b);
                return b;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return this.mUserNeedsBadging.valueAt(index);
    }

    public KeySet getKeySetByAlias(String packageName, String alias) {
        KeySet keySet;
        if (packageName == null || alias == null) {
            return null;
        }
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg == null) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            if (filterAppAccessLPr(ps, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                Slog.w(TAG, "KeySet requested for filtered package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
            keySet = new KeySet(ksms.getKeySetByAliasAndPackageNameLPr(packageName, alias));
        }
        return keySet;
    }

    public KeySet getSigningKeySet(String packageName) {
        KeySet keySet;
        if (packageName == null) {
            return null;
        }
        synchronized (this.mPackages) {
            int callingUid = Binder.getCallingUid();
            int callingUserId = UserHandle.getUserId(callingUid);
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg == null) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            if (filterAppAccessLPr(ps, callingUid, callingUserId)) {
                Slog.w(TAG, "KeySet requested for filtered package: " + packageName + ", uid:" + callingUid);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            if (pkg.applicationInfo.uid != callingUid && 1000 != callingUid) {
                throw new SecurityException("May not access signing KeySet of other apps.");
            }
            KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
            keySet = new KeySet(ksms.getSigningKeySetByPackageNameLPr(packageName));
        }
        return keySet;
    }

    public boolean isPackageSignedByKeySet(String packageName, KeySet ks) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null || packageName == null || ks == null) {
            return false;
        }
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg == null || filterAppAccessLPr((PackageSetting) pkg.mExtras, callingUid, UserHandle.getUserId(callingUid))) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            IBinder ksh = ks.getToken();
            if (ksh instanceof KeySetHandle) {
                KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
                return ksms.packageIsSignedByLPr(packageName, (KeySetHandle) ksh);
            }
            return false;
        }
    }

    public boolean isPackageSignedByKeySetExactly(String packageName, KeySet ks) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null || packageName == null || ks == null) {
            return false;
        }
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg == null || filterAppAccessLPr((PackageSetting) pkg.mExtras, callingUid, UserHandle.getUserId(callingUid))) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            IBinder ksh = ks.getToken();
            if (ksh instanceof KeySetHandle) {
                KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
                return ksms.packageIsSignedByExactlyLPr(packageName, (KeySetHandle) ksh);
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public void deletePackageIfUnusedLPr(final String packageName) {
        PackageSetting ps = this.mSettings.mPackages.get(packageName);
        if (ps != null && !ps.isAnyInstalled(sUserManager.getUserIds())) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$g_SDMy3fJv4rxEqHTnQ3DxfgaZY
                @Override // java.lang.Runnable
                public final void run() {
                    PackageManagerService.this.lambda$deletePackageIfUnusedLPr$37$PackageManagerService(packageName);
                }
            });
        }
    }

    public /* synthetic */ void lambda$deletePackageIfUnusedLPr$37$PackageManagerService(String packageName) {
        deletePackageX(packageName, -1L, 0, 2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void checkDowngrade(PackageParser.Package before, PackageInfoLite after) throws PackageManagerException {
        if (after.getLongVersionCode() < before.getLongVersionCode()) {
            throw new PackageManagerException(-25, "Update version code " + after.versionCode + " is older than current " + before.getLongVersionCode());
        } else if (after.getLongVersionCode() == before.getLongVersionCode()) {
            if (after.baseRevisionCode < before.baseRevisionCode) {
                throw new PackageManagerException(-25, "Update base revision code " + after.baseRevisionCode + " is older than current " + before.baseRevisionCode);
            } else if (!ArrayUtils.isEmpty(after.splitNames)) {
                for (int i = 0; i < after.splitNames.length; i++) {
                    String splitName = after.splitNames[i];
                    int j = ArrayUtils.indexOf(before.splitNames, splitName);
                    if (j != -1 && after.splitRevisionCodes[i] < before.splitRevisionCodes[j]) {
                        throw new PackageManagerException(-25, "Update split " + splitName + " revision code " + after.splitRevisionCodes[i] + " is older than current " + before.splitRevisionCodes[j]);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class MoveCallbacks extends Handler {
        private static final int MSG_CREATED = 1;
        private static final int MSG_STATUS_CHANGED = 2;
        private final RemoteCallbackList<IPackageMoveObserver> mCallbacks;
        private final SparseIntArray mLastStatus;

        public MoveCallbacks(Looper looper) {
            super(looper);
            this.mCallbacks = new RemoteCallbackList<>();
            this.mLastStatus = new SparseIntArray();
        }

        public void register(IPackageMoveObserver callback) {
            this.mCallbacks.register(callback);
        }

        public void unregister(IPackageMoveObserver callback) {
            this.mCallbacks.unregister(callback);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            SomeArgs args = (SomeArgs) msg.obj;
            int n = this.mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                IPackageMoveObserver callback = this.mCallbacks.getBroadcastItem(i);
                try {
                    invokeCallback(callback, msg.what, args);
                } catch (RemoteException e) {
                }
            }
            this.mCallbacks.finishBroadcast();
            args.recycle();
        }

        private void invokeCallback(IPackageMoveObserver callback, int what, SomeArgs args) throws RemoteException {
            if (what == 1) {
                callback.onCreated(args.argi1, (Bundle) args.arg2);
            } else if (what == 2) {
                callback.onStatusChanged(args.argi1, args.argi2, ((Long) args.arg3).longValue());
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyCreated(int moveId, Bundle extras) {
            Slog.v(PackageManagerService.TAG, "Move " + moveId + " created " + extras.toString());
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = moveId;
            args.arg2 = extras;
            obtainMessage(1, args).sendToTarget();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyStatusChanged(int moveId, int status) {
            notifyStatusChanged(moveId, status, -1L);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyStatusChanged(int moveId, int status, long estMillis) {
            Slog.v(PackageManagerService.TAG, "Move " + moveId + " status " + status);
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = moveId;
            args.argi2 = status;
            args.arg3 = Long.valueOf(estMillis);
            obtainMessage(2, args).sendToTarget();
            synchronized (this.mLastStatus) {
                this.mLastStatus.put(moveId, status);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class OnPermissionChangeListeners extends Handler {
        private static final int MSG_ON_PERMISSIONS_CHANGED = 1;
        private final RemoteCallbackList<IOnPermissionsChangeListener> mPermissionListeners;

        public OnPermissionChangeListeners(Looper looper) {
            super(looper);
            this.mPermissionListeners = new RemoteCallbackList<>();
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                int uid = msg.arg1;
                handleOnPermissionsChanged(uid);
            }
        }

        public void addListenerLocked(IOnPermissionsChangeListener listener) {
            this.mPermissionListeners.register(listener);
        }

        public void removeListenerLocked(IOnPermissionsChangeListener listener) {
            this.mPermissionListeners.unregister(listener);
        }

        public void onPermissionsChanged(int uid) {
            if (this.mPermissionListeners.getRegisteredCallbackCount() > 0) {
                obtainMessage(1, uid, 0).sendToTarget();
            }
        }

        private void handleOnPermissionsChanged(int uid) {
            int count = this.mPermissionListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    IOnPermissionsChangeListener callback = this.mPermissionListeners.getBroadcastItem(i);
                    try {
                        callback.onPermissionsChanged(uid);
                    } catch (RemoteException e) {
                        Log.e(PackageManagerService.TAG, "Permission listener is dead", e);
                    }
                } finally {
                    this.mPermissionListeners.finishBroadcast();
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private class PackageManagerNative extends IPackageManagerNative.Stub {
        private PackageManagerNative() {
        }

        /* synthetic */ PackageManagerNative(PackageManagerService x0, AnonymousClass1 x1) {
            this();
        }

        public String[] getNamesForUids(int[] uids) throws RemoteException {
            String[] results = PackageManagerService.this.getNamesForUids(uids);
            for (int i = results.length - 1; i >= 0; i--) {
                if (results[i] == null) {
                    results[i] = "";
                }
            }
            return results;
        }

        public String getInstallerForPackage(String packageName) throws RemoteException {
            String installerName = PackageManagerService.this.getInstallerPackageName(packageName);
            if (!TextUtils.isEmpty(installerName)) {
                return installerName;
            }
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            ApplicationInfo appInfo = PackageManagerService.this.getApplicationInfo(packageName, 0, callingUser);
            if (appInfo != null && (appInfo.flags & 1) != 0) {
                return "preload";
            }
            return "";
        }

        public long getVersionCodeForPackage(String packageName) throws RemoteException {
            try {
                int callingUser = UserHandle.getUserId(Binder.getCallingUid());
                PackageInfo pInfo = PackageManagerService.this.getPackageInfo(packageName, 0, callingUser);
                if (pInfo != null) {
                    return pInfo.getLongVersionCode();
                }
                return 0L;
            } catch (Exception e) {
                return 0L;
            }
        }

        public int getTargetSdkVersionForPackage(String packageName) throws RemoteException {
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            ApplicationInfo info = PackageManagerService.this.getApplicationInfo(packageName, 0, callingUser);
            if (info == null) {
                throw new RemoteException("Couldn't get ApplicationInfo for package " + packageName);
            }
            return info.targetSdkVersion;
        }

        public boolean[] isAudioPlaybackCaptureAllowed(String[] packageNames) throws RemoteException {
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            boolean[] results = new boolean[packageNames.length];
            for (int i = results.length - 1; i >= 0; i--) {
                boolean z = false;
                ApplicationInfo appInfo = PackageManagerService.this.getApplicationInfo(packageNames[i], 0, callingUser);
                if (appInfo != null) {
                    z = appInfo.isAudioPlaybackCaptureAllowed();
                }
                results[i] = z;
            }
            return results;
        }

        public int getLocationFlags(String packageName) throws RemoteException {
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            ApplicationInfo appInfo = PackageManagerService.this.getApplicationInfo(packageName, 0, callingUser);
            if (appInfo == null) {
                throw new RemoteException("Couldn't get ApplicationInfo for package " + packageName);
            }
            return (appInfo.isProduct() ? 4 : 0) | appInfo.isSystemApp() | (appInfo.isVendor() ? 2 : 0);
        }

        public String getModuleMetadataPackageName() throws RemoteException {
            return PackageManagerService.this.mModuleInfoProvider.getPackageName();
        }
    }

    /* loaded from: classes.dex */
    private class PackageManagerInternalImpl extends PackageManagerInternal {
        private PackageManagerInternalImpl() {
        }

        /* synthetic */ PackageManagerInternalImpl(PackageManagerService x0, AnonymousClass1 x1) {
            this();
        }

        public void updatePermissionFlagsTEMP(String permName, String packageName, int flagMask, int flagValues, int userId) {
            PackageManagerService.this.updatePermissionFlags(permName, packageName, flagMask, flagValues, true, userId);
        }

        public List<ApplicationInfo> getInstalledApplications(int flags, int userId, int callingUid) {
            return PackageManagerService.this.getInstalledApplicationsListInternal(flags, userId, callingUid);
        }

        public boolean isPlatformSigned(String packageName) {
            PackageParser.Package pkg;
            PackageSetting packageSetting = PackageManagerService.this.mSettings.mPackages.get(packageName);
            if (packageSetting == null || (pkg = packageSetting.pkg) == null) {
                return false;
            }
            return pkg.mSigningDetails.hasAncestorOrSelf(PackageManagerService.this.mPlatformPackage.mSigningDetails) || PackageManagerService.this.mPlatformPackage.mSigningDetails.checkCapability(pkg.mSigningDetails, 4);
        }

        public boolean isDataRestoreSafe(byte[] restoringFromSigHash, String packageName) {
            PackageParser.SigningDetails sd = getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasSha256Certificate(restoringFromSigHash, 1);
        }

        public boolean isDataRestoreSafe(Signature restoringFromSig, String packageName) {
            PackageParser.SigningDetails sd = getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasCertificate(restoringFromSig, 1);
        }

        public boolean hasSignatureCapability(int serverUid, int clientUid, @PackageParser.SigningDetails.CertCapabilities int capability) {
            PackageParser.SigningDetails serverSigningDetails = getSigningDetails(serverUid);
            PackageParser.SigningDetails clientSigningDetails = getSigningDetails(clientUid);
            return serverSigningDetails.checkCapability(clientSigningDetails, capability) || clientSigningDetails.hasAncestorOrSelf(serverSigningDetails);
        }

        private PackageParser.SigningDetails getSigningDetails(String packageName) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageParser.Package p = PackageManagerService.this.mPackages.get(packageName);
                if (p == null) {
                    return null;
                }
                return p.mSigningDetails;
            }
        }

        private PackageParser.SigningDetails getSigningDetails(int uid) {
            synchronized (PackageManagerService.this.mPackages) {
                int appId = UserHandle.getAppId(uid);
                Object obj = PackageManagerService.this.mSettings.getSettingLPr(appId);
                if (obj != null) {
                    if (obj instanceof SharedUserSetting) {
                        return ((SharedUserSetting) obj).signatures.mSigningDetails;
                    } else if (obj instanceof PackageSetting) {
                        PackageSetting ps = (PackageSetting) obj;
                        return ps.signatures.mSigningDetails;
                    }
                }
                return PackageParser.SigningDetails.UNKNOWN;
            }
        }

        public int getPermissionFlagsTEMP(String permName, String packageName, int userId) {
            return PackageManagerService.this.getPermissionFlags(permName, packageName, userId);
        }

        public boolean isInstantApp(String packageName, int userId) {
            return PackageManagerService.this.isInstantApp(packageName, userId);
        }

        public String getInstantAppPackageName(int uid) {
            return PackageManagerService.this.getInstantAppPackageName(uid);
        }

        public boolean filterAppAccess(PackageParser.Package pkg, int callingUid, int userId) {
            boolean filterAppAccessLPr;
            synchronized (PackageManagerService.this.mPackages) {
                filterAppAccessLPr = PackageManagerService.this.filterAppAccessLPr((PackageSetting) pkg.mExtras, callingUid, userId);
            }
            return filterAppAccessLPr;
        }

        public PackageParser.Package getPackage(String packageName) {
            PackageParser.Package r1;
            synchronized (PackageManagerService.this.mPackages) {
                r1 = PackageManagerService.this.mPackages.get(PackageManagerService.this.resolveInternalPackageNameLPr(packageName, -1L));
            }
            return r1;
        }

        public PackageList getPackageList(PackageManagerInternal.PackageListObserver observer) {
            PackageList packageList;
            synchronized (PackageManagerService.this.mPackages) {
                int N = PackageManagerService.this.mPackages.size();
                ArrayList<String> list = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    list.add(PackageManagerService.this.mPackages.keyAt(i));
                }
                packageList = new PackageList(list, observer);
                if (observer != null) {
                    PackageManagerService.this.mPackageListObservers.add(packageList);
                }
            }
            return packageList;
        }

        public void removePackageListObserver(PackageManagerInternal.PackageListObserver observer) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mPackageListObservers.remove(observer);
            }
        }

        public PackageParser.Package getDisabledSystemPackage(String packageName) {
            PackageParser.Package r2;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.getDisabledSystemPkgLPr(packageName);
                r2 = ps != null ? ps.pkg : null;
            }
            return r2;
        }

        public String getDisabledSystemPackageName(String packageName) {
            PackageParser.Package pkg = getDisabledSystemPackage(packageName);
            if (pkg == null) {
                return null;
            }
            return pkg.packageName;
        }

        public String getKnownPackageName(int knownPackage, int userId) {
            switch (knownPackage) {
                case 0:
                    return PackageManagerService.PLATFORM_PACKAGE_NAME;
                case 1:
                    return PackageManagerService.this.mSetupWizardPackage;
                case 2:
                    return PackageManagerService.this.mRequiredInstallerPackage;
                case 3:
                    return PackageManagerService.this.mRequiredVerifierPackage;
                case 4:
                    return PackageManagerService.this.getDefaultBrowserPackageName(userId);
                case 5:
                    return PackageManagerService.this.mSystemTextClassifierPackage;
                case 6:
                    return PackageManagerService.this.mRequiredPermissionControllerPackage;
                case 7:
                    return PackageManagerService.this.mWellbeingPackage;
                case 8:
                    return PackageManagerService.this.mDocumenterPackage;
                case 9:
                    return PackageManagerService.this.mConfiguratorPackage;
                case 10:
                    return PackageManagerService.this.mIncidentReportApproverPackage;
                case 11:
                    return PackageManagerService.this.mAppPredictionServicePackage;
                default:
                    return null;
            }
        }

        public boolean isResolveActivityComponent(ComponentInfo component) {
            return PackageManagerService.this.mResolveActivity.packageName.equals(component.packageName) && PackageManagerService.this.mResolveActivity.name.equals(component.name);
        }

        public void setLocationPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setLocationPackagesProvider(provider);
        }

        public void setLocationExtraPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setLocationExtraPackagesProvider(provider);
        }

        public void setVoiceInteractionPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setVoiceInteractionPackagesProvider(provider);
        }

        public void setUseOpenWifiAppPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setUseOpenWifiAppPackagesProvider(provider);
        }

        public void setSyncAdapterPackagesprovider(PackageManagerInternal.SyncAdapterPackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setSyncAdapterPackagesProvider(provider);
        }

        public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
            PackageManagerService.this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultUseOpenWifiApp(packageName, userId);
        }

        public void setKeepUninstalledPackages(List<String> packageList) {
            Preconditions.checkNotNull(packageList);
            List<String> removedFromList = null;
            synchronized (PackageManagerService.this.mPackages) {
                if (PackageManagerService.this.mKeepUninstalledPackages != null) {
                    int packagesCount = PackageManagerService.this.mKeepUninstalledPackages.size();
                    for (int i = 0; i < packagesCount; i++) {
                        String oldPackage = (String) PackageManagerService.this.mKeepUninstalledPackages.get(i);
                        if (packageList == null || !packageList.contains(oldPackage)) {
                            if (removedFromList == null) {
                                removedFromList = new ArrayList<>();
                            }
                            removedFromList.add(oldPackage);
                        }
                    }
                }
                PackageManagerService.this.mKeepUninstalledPackages = new ArrayList(packageList);
                if (removedFromList != null) {
                    int removedCount = removedFromList.size();
                    for (int i2 = 0; i2 < removedCount; i2++) {
                        PackageManagerService.this.deletePackageIfUnusedLPr(removedFromList.get(i2));
                    }
                }
            }
        }

        public boolean isPermissionsReviewRequired(String packageName, int userId) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageParser.Package pkg = PackageManagerService.this.mPackages.get(packageName);
                if (pkg != null) {
                    return PackageManagerService.this.mPermissionManager.isPermissionsReviewRequired(pkg, userId);
                }
                return false;
            }
        }

        public PackageInfo getPackageInfo(String packageName, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this.getPackageInfoInternal(packageName, -1L, flags, filterCallingUid, userId);
        }

        public Bundle getSuspendedPackageLauncherExtras(String packageName, int userId) {
            Bundle bundle;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(packageName);
                PersistableBundle launcherExtras = null;
                if (ps != null) {
                    launcherExtras = ps.readUserState(userId).suspendedLauncherExtras;
                }
                bundle = launcherExtras != null ? new Bundle(launcherExtras.deepCopy()) : null;
            }
            return bundle;
        }

        public boolean isPackageSuspended(String packageName, int userId) {
            boolean suspended;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(packageName);
                suspended = ps != null ? ps.getSuspended(userId) : false;
            }
            return suspended;
        }

        public String getSuspendingPackage(String suspendedPackage, int userId) {
            String str;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(suspendedPackage);
                str = ps != null ? ps.readUserState(userId).suspendingPackage : null;
            }
            return str;
        }

        public SuspendDialogInfo getSuspendedDialogInfo(String suspendedPackage, int userId) {
            SuspendDialogInfo suspendDialogInfo;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(suspendedPackage);
                suspendDialogInfo = ps != null ? ps.readUserState(userId).dialogInfo : null;
            }
            return suspendDialogInfo;
        }

        public int getDistractingPackageRestrictions(String packageName, int userId) {
            int distractionFlags;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(packageName);
                distractionFlags = ps != null ? ps.getDistractionFlags(userId) : 0;
            }
            return distractionFlags;
        }

        public int getPackageUid(String packageName, int flags, int userId) {
            return PackageManagerService.this.getPackageUid(packageName, flags, userId);
        }

        public ApplicationInfo getApplicationInfo(String packageName, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this.getApplicationInfoInternal(packageName, flags, filterCallingUid, userId);
        }

        public ActivityInfo getActivityInfo(ComponentName component, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this.getActivityInfoInternal(component, flags, filterCallingUid, userId);
        }

        public List<ResolveInfo> queryIntentActivities(Intent intent, int flags, int filterCallingUid, int userId) {
            String resolvedType = intent.resolveTypeIfNeeded(PackageManagerService.this.mContext.getContentResolver());
            return PackageManagerService.this.queryIntentActivitiesInternal(intent, resolvedType, flags, filterCallingUid, userId, false, true);
        }

        public List<ResolveInfo> queryIntentServices(Intent intent, int flags, int callingUid, int userId) {
            String resolvedType = intent.resolveTypeIfNeeded(PackageManagerService.this.mContext.getContentResolver());
            return PackageManagerService.this.queryIntentServicesInternal(intent, resolvedType, flags, userId, callingUid, false);
        }

        public ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates, int userId) {
            return PackageManagerService.this.getHomeActivitiesAsUser(allHomeCandidates, userId);
        }

        public ComponentName getDefaultHomeActivity(int userId) {
            return PackageManagerService.this.getDefaultHomeActivity(userId);
        }

        public void setDeviceAndProfileOwnerPackages(int deviceOwnerUserId, String deviceOwnerPackage, SparseArray<String> profileOwnerPackages) {
            PackageManagerService.this.mProtectedPackages.setDeviceAndProfileOwnerPackages(deviceOwnerUserId, deviceOwnerPackage, profileOwnerPackages);
            ArraySet<Integer> usersWithPoOrDo = new ArraySet<>();
            if (deviceOwnerPackage != null) {
                usersWithPoOrDo.add(Integer.valueOf(deviceOwnerUserId));
            }
            int sz = profileOwnerPackages.size();
            for (int i = 0; i < sz; i++) {
                if (profileOwnerPackages.valueAt(i) != null) {
                    usersWithPoOrDo.add(Integer.valueOf(profileOwnerPackages.keyAt(i)));
                }
            }
            PackageManagerService.this.unsuspendForNonSystemSuspendingPackages(usersWithPoOrDo);
        }

        public boolean isPackageDataProtected(int userId, String packageName) {
            return PackageManagerService.this.mProtectedPackages.isPackageDataProtected(userId, packageName);
        }

        public boolean isPackageStateProtected(String packageName, int userId) {
            return PackageManagerService.this.mProtectedPackages.isPackageStateProtected(userId, packageName);
        }

        public boolean isPackageEphemeral(int userId, String packageName) {
            boolean instantApp;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(packageName);
                instantApp = ps != null ? ps.getInstantApp(userId) : false;
            }
            return instantApp;
        }

        public boolean wasPackageEverLaunched(String packageName, int userId) {
            boolean wasPackageEverLaunchedLPr;
            synchronized (PackageManagerService.this.mPackages) {
                wasPackageEverLaunchedLPr = PackageManagerService.this.mSettings.wasPackageEverLaunchedLPr(packageName, userId);
            }
            return wasPackageEverLaunchedLPr;
        }

        public boolean isEnabledAndMatches(ComponentInfo info, int flags, int userId) {
            boolean isEnabledAndMatchLPr;
            synchronized (PackageManagerService.this.mPackages) {
                isEnabledAndMatchLPr = PackageManagerService.this.mSettings.isEnabledAndMatchLPr(info, flags, userId);
            }
            return isEnabledAndMatchLPr;
        }

        public boolean userNeedsBadging(int userId) {
            boolean userNeedsBadging;
            synchronized (PackageManagerService.this.mPackages) {
                userNeedsBadging = PackageManagerService.this.userNeedsBadging(userId);
            }
            return userNeedsBadging;
        }

        public void grantRuntimePermission(String packageName, String permName, int userId, boolean overridePolicy) {
            PackageManagerService.this.mPermissionManager.grantRuntimePermission(permName, packageName, overridePolicy, Binder.getCallingUid(), userId, PackageManagerService.this.mPermissionCallback);
        }

        public void revokeRuntimePermission(String packageName, String permName, int userId, boolean overridePolicy) {
            PackageManagerService.this.mPermissionManager.revokeRuntimePermission(permName, packageName, overridePolicy, userId, PackageManagerService.this.mPermissionCallback);
        }

        public String getNameForUid(int uid) {
            return PackageManagerService.this.getNameForUid(uid);
        }

        public void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj, Intent origIntent, String resolvedType, String callingPackage, Bundle verificationBundle, int userId) {
            PackageManagerService.this.requestInstantAppResolutionPhaseTwo(responseObj, origIntent, resolvedType, callingPackage, verificationBundle, userId);
        }

        public void grantEphemeralAccess(int userId, Intent intent, int targetAppId, int ephemeralAppId) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mInstantAppRegistry.grantInstantAccessLPw(userId, intent, targetAppId, ephemeralAppId);
            }
        }

        public boolean isInstantAppInstallerComponent(ComponentName component) {
            boolean z;
            synchronized (PackageManagerService.this.mPackages) {
                z = PackageManagerService.this.mInstantAppInstallerActivity != null && PackageManagerService.this.mInstantAppInstallerActivity.getComponentName().equals(component);
            }
            return z;
        }

        public void pruneInstantApps() {
            PackageManagerService.this.mInstantAppRegistry.pruneInstantApps();
        }

        public String getSetupWizardPackageName() {
            return PackageManagerService.this.mSetupWizardPackage;
        }

        public void setExternalSourcesPolicy(PackageManagerInternal.ExternalSourcesPolicy policy) {
            if (policy != null) {
                PackageManagerService.this.mExternalSourcesPolicy = policy;
            }
        }

        public boolean isPackagePersistent(String packageName) {
            boolean z;
            synchronized (PackageManagerService.this.mPackages) {
                PackageParser.Package pkg = PackageManagerService.this.mPackages.get(packageName);
                z = false;
                if (pkg != null && (pkg.applicationInfo.flags & 9) == 9) {
                    z = true;
                }
            }
            return z;
        }

        public boolean isLegacySystemApp(PackageParser.Package pkg) {
            boolean z;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                z = PackageManagerService.this.mPromoteSystemApps && ps.isSystem() && PackageManagerService.this.mExistingSystemPackages.contains(ps.name);
            }
            return z;
        }

        public List<PackageInfo> getOverlayPackages(int userId) {
            PackageInfo pkg;
            ArrayList<PackageInfo> overlayPackages = new ArrayList<>();
            synchronized (PackageManagerService.this.mPackages) {
                for (PackageParser.Package p : PackageManagerService.this.mPackages.values()) {
                    if (p.mOverlayTarget != null && (pkg = PackageManagerService.this.generatePackageInfo((PackageSetting) p.mExtras, 0, userId)) != null) {
                        overlayPackages.add(pkg);
                    }
                }
            }
            return overlayPackages;
        }

        public List<String> getTargetPackageNames(int userId) {
            List<String> targetPackages = new ArrayList<>();
            synchronized (PackageManagerService.this.mPackages) {
                for (PackageParser.Package p : PackageManagerService.this.mPackages.values()) {
                    if (p.mOverlayTarget == null) {
                        targetPackages.add(p.packageName);
                    }
                }
            }
            return targetPackages;
        }

        public boolean setEnabledOverlayPackages(int userId, String targetPackageName, List<String> overlayPackageNames) {
            synchronized (PackageManagerService.this.mPackages) {
                if (targetPackageName != null) {
                    if (PackageManagerService.this.mPackages.get(targetPackageName) != null) {
                        ArrayList<String> overlayPaths = null;
                        if (overlayPackageNames != null && overlayPackageNames.size() > 0) {
                            int N = overlayPackageNames.size();
                            overlayPaths = new ArrayList<>(N);
                            for (int i = 0; i < N; i++) {
                                String packageName = overlayPackageNames.get(i);
                                PackageParser.Package pkg = PackageManagerService.this.mPackages.get(packageName);
                                if (pkg == null) {
                                    Slog.e(PackageManagerService.TAG, "failed to find package " + packageName);
                                    return false;
                                }
                                overlayPaths.add(pkg.baseCodePath);
                            }
                        }
                        PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(targetPackageName);
                        ps.setOverlayPaths(overlayPaths, userId);
                        return true;
                    }
                }
                Slog.e(PackageManagerService.TAG, "failed to find package " + targetPackageName);
                return false;
            }
        }

        public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId, boolean resolveForStart, int filterCallingUid) {
            return PackageManagerService.this.resolveIntentInternal(intent, resolvedType, flags, userId, resolveForStart, filterCallingUid);
        }

        public ResolveInfo resolveService(Intent intent, String resolvedType, int flags, int userId, int callingUid) {
            return PackageManagerService.this.resolveServiceInternal(intent, resolvedType, flags, userId, callingUid);
        }

        public ProviderInfo resolveContentProvider(String name, int flags, int userId) {
            return PackageManagerService.this.resolveContentProviderInternal(name, flags, userId);
        }

        public void addIsolatedUid(int isolatedUid, int ownerUid) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mIsolatedOwners.put(isolatedUid, ownerUid);
            }
        }

        public void removeIsolatedUid(int isolatedUid) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mIsolatedOwners.delete(isolatedUid);
            }
        }

        public int getUidTargetSdkVersion(int uid) {
            int uidTargetSdkVersionLockedLPr;
            synchronized (PackageManagerService.this.mPackages) {
                uidTargetSdkVersionLockedLPr = PackageManagerService.this.getUidTargetSdkVersionLockedLPr(uid);
            }
            return uidTargetSdkVersionLockedLPr;
        }

        public int getPackageTargetSdkVersion(String packageName) {
            int packageTargetSdkVersionLockedLPr;
            synchronized (PackageManagerService.this.mPackages) {
                packageTargetSdkVersionLockedLPr = PackageManagerService.this.getPackageTargetSdkVersionLockedLPr(packageName);
            }
            return packageTargetSdkVersionLockedLPr;
        }

        public boolean canAccessInstantApps(int callingUid, int userId) {
            return PackageManagerService.this.canViewInstantApps(callingUid, userId);
        }

        public boolean canAccessComponent(int callingUid, ComponentName component, int userId) {
            boolean z;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(component.getPackageName());
                z = (ps == null || PackageManagerService.this.filterAppAccessLPr(ps, callingUid, component, 0, userId)) ? false : true;
            }
            return z;
        }

        public boolean hasInstantApplicationMetadata(String packageName, int userId) {
            boolean hasInstantApplicationMetadataLPr;
            synchronized (PackageManagerService.this.mPackages) {
                hasInstantApplicationMetadataLPr = PackageManagerService.this.mInstantAppRegistry.hasInstantApplicationMetadataLPr(packageName, userId);
            }
            return hasInstantApplicationMetadataLPr;
        }

        public void notifyPackageUse(String packageName, int reason) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.notifyPackageUseLocked(packageName, reason);
            }
        }

        public PackageManagerInternal.CheckPermissionDelegate getCheckPermissionDelegate() {
            PackageManagerInternal.CheckPermissionDelegate checkPermissionDelegateLocked;
            synchronized (PackageManagerService.this.mPackages) {
                checkPermissionDelegateLocked = PackageManagerService.this.getCheckPermissionDelegateLocked();
            }
            return checkPermissionDelegateLocked;
        }

        public void setCheckPermissionDelegate(PackageManagerInternal.CheckPermissionDelegate delegate) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.setCheckPermissionDelegateLocked(delegate);
            }
        }

        public SparseArray<String> getAppsWithSharedUserIds() {
            SparseArray<String> appsWithSharedUserIdsLocked;
            synchronized (PackageManagerService.this.mPackages) {
                appsWithSharedUserIdsLocked = PackageManagerService.this.getAppsWithSharedUserIdsLocked();
            }
            return appsWithSharedUserIdsLocked;
        }

        public String getSharedUserIdForPackage(String packageName) {
            String sharedUserIdForPackageLocked;
            synchronized (PackageManagerService.this.mPackages) {
                sharedUserIdForPackageLocked = PackageManagerService.this.getSharedUserIdForPackageLocked(packageName);
            }
            return sharedUserIdForPackageLocked;
        }

        public String[] getPackagesForSharedUserId(String sharedUserId, int userId) {
            String[] packagesForSharedUserIdLocked;
            synchronized (PackageManagerService.this.mPackages) {
                packagesForSharedUserIdLocked = PackageManagerService.this.getPackagesForSharedUserIdLocked(sharedUserId, userId);
            }
            return packagesForSharedUserIdLocked;
        }

        public boolean isOnlyCoreApps() {
            return PackageManagerService.this.isOnlyCoreApps();
        }

        public void freeStorage(String volumeUuid, long bytes, int storageFlags) throws IOException {
            PackageManagerService.this.freeStorage(volumeUuid, bytes, storageFlags);
        }

        public void forEachPackage(Consumer<PackageParser.Package> actionLocked) {
            PackageManagerService.this.forEachPackage(actionLocked);
        }

        public void forEachInstalledPackage(Consumer<PackageParser.Package> actionLocked, int userId) {
            PackageManagerService.this.forEachInstalledPackage(actionLocked, userId);
        }

        public ArraySet<String> getEnabledComponents(String packageName, int userId) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting setting = PackageManagerService.this.mSettings.getPackageLPr(packageName);
                if (setting == null) {
                    return new ArraySet<>();
                }
                return setting.getEnabledComponents(userId);
            }
        }

        public ArraySet<String> getDisabledComponents(String packageName, int userId) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting setting = PackageManagerService.this.mSettings.getPackageLPr(packageName);
                if (setting == null) {
                    return new ArraySet<>();
                }
                return setting.getDisabledComponents(userId);
            }
        }

        public int getApplicationEnabledState(String packageName, int userId) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting setting = PackageManagerService.this.mSettings.getPackageLPr(packageName);
                if (setting == null) {
                    return 0;
                }
                return setting.getEnabled(userId);
            }
        }

        public void setEnableRollbackCode(int token, int enableRollbackCode) {
            PackageManagerService.this.setEnableRollbackCode(token, enableRollbackCode);
        }

        public boolean compileLayouts(String packageName) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageParser.Package pkg = PackageManagerService.this.mPackages.get(packageName);
                if (pkg == null) {
                    return false;
                }
                return PackageManagerService.this.mArtManagerService.compileLayouts(pkg);
            }
        }

        public void finishPackageInstall(int token, boolean didLaunch) {
            PackageManagerService.this.finishPackageInstall(token, didLaunch);
        }

        public String removeLegacyDefaultBrowserPackageName(int userId) {
            String removeDefaultBrowserPackageNameLPw;
            synchronized (PackageManagerService.this.mPackages) {
                removeDefaultBrowserPackageNameLPw = PackageManagerService.this.mSettings.removeDefaultBrowserPackageNameLPw(userId);
            }
            return removeDefaultBrowserPackageNameLPw;
        }

        public void setDefaultBrowserProvider(PackageManagerInternal.DefaultBrowserProvider provider) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mDefaultBrowserProvider = provider;
            }
        }

        public void setDefaultDialerProvider(PackageManagerInternal.DefaultDialerProvider provider) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mDefaultDialerProvider = provider;
            }
        }

        public void setDefaultHomeProvider(PackageManagerInternal.DefaultHomeProvider provider) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mDefaultHomeProvider = provider;
            }
        }

        public boolean isApexPackage(String packageName) {
            return PackageManagerService.this.mApexManager.isApexPackage(packageName);
        }

        public void uninstallApex(String packageName, long versionCode, int userId, IntentSender intentSender) {
            int callerUid = Binder.getCallingUid();
            if (callerUid != 0 && callerUid != PackageManagerService.SHELL_UID) {
                throw new SecurityException("Not allowed to uninstall apexes");
            }
            PackageInstallerService.PackageDeleteObserverAdapter adapter = new PackageInstallerService.PackageDeleteObserverAdapter(PackageManagerService.this.mContext, intentSender, packageName, false, userId);
            if (userId == -1) {
                ApexManager am = PackageManagerService.this.mApexManager;
                PackageInfo activePackage = am.getPackageInfo(packageName, 1);
                if (activePackage == null) {
                    adapter.onPackageDeleted(packageName, -5, packageName + " is not an apex package");
                    return;
                } else if (versionCode != -1 && activePackage.getLongVersionCode() != versionCode) {
                    adapter.onPackageDeleted(packageName, -5, "Active version " + activePackage.getLongVersionCode() + " is not equal to " + versionCode + "]");
                    return;
                } else if (am.uninstallApex(activePackage.applicationInfo.sourceDir)) {
                    adapter.onPackageDeleted(packageName, 1, null);
                    return;
                } else {
                    adapter.onPackageDeleted(packageName, -5, "Failed to uninstall apex " + packageName);
                    return;
                }
            }
            adapter.onPackageDeleted(packageName, -5, "Can't uninstall an apex for a single user");
        }

        public boolean wereDefaultPermissionsGrantedSinceBoot(int userId) {
            boolean wereDefaultPermissionsGrantedSinceBoot;
            synchronized (PackageManagerService.this.mPackages) {
                wereDefaultPermissionsGrantedSinceBoot = PackageManagerService.this.mDefaultPermissionPolicy.wereDefaultPermissionsGrantedSinceBoot(userId);
            }
            return wereDefaultPermissionsGrantedSinceBoot;
        }

        public void setRuntimePermissionsFingerPrint(String fingerPrint, int userId) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.setRuntimePermissionsFingerPrintLPr(fingerPrint, userId);
            }
        }

        public void migrateLegacyObbData() {
            try {
                PackageManagerService.this.mInstaller.migrateLegacyObbData();
            } catch (Exception e) {
                Slog.wtf(PackageManagerService.TAG, e);
            }
        }

        public void notifyingOnNextUserRemovalForTest() {
            PackageManagerService.this.mBlockDeleteOnUserRemoveForTest.close();
        }

        public void userRemovedForTest() {
            PackageManagerService.this.mBlockDeleteOnUserRemoveForTest.open();
        }

        public xpPackageInfo getXpPackageInfo(String packageName) {
            return xpPackageManagerService.get(PackageManagerService.this.mContext).getXpPackageInfo(packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public SparseArray<String> getAppsWithSharedUserIdsLocked() {
        SparseArray<String> sharedUserIds = new SparseArray<>();
        synchronized (this.mPackages) {
            for (SharedUserSetting setting : this.mSettings.getAllSharedUsersLPw()) {
                sharedUserIds.put(UserHandle.getAppId(setting.userId), setting.name);
            }
        }
        return sharedUserIds;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public String getSharedUserIdForPackageLocked(String packageName) {
        PackageSetting ps = this.mSettings.mPackages.get(packageName);
        if (ps == null || !ps.isSharedUser()) {
            return null;
        }
        return ps.sharedUser.name;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mPackages"})
    public String[] getPackagesForSharedUserIdLocked(String sharedUserId, int userId) {
        try {
            int i = 0;
            SharedUserSetting sus = this.mSettings.getSharedUserLPw(sharedUserId, 0, 0, false);
            if (sus == null) {
                return EmptyArray.STRING;
            }
            String[] res = new String[sus.packages.size()];
            Iterator<PackageSetting> it = sus.packages.iterator();
            while (it.hasNext()) {
                PackageSetting ps = it.next();
                if (ps.getInstalled(userId)) {
                    res[i] = ps.name;
                    i++;
                } else {
                    res = (String[]) ArrayUtils.removeElement(String.class, res, res[i]);
                }
            }
            return res;
        } catch (PackageManagerException e) {
            return EmptyArray.STRING;
        }
    }

    public int getRuntimePermissionsVersion(int userId) {
        int defaultRuntimePermissionsVersionLPr;
        Preconditions.checkArgumentNonnegative(userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY", "setRuntimePermissionVersion");
        synchronized (this.mPackages) {
            defaultRuntimePermissionsVersionLPr = this.mSettings.getDefaultRuntimePermissionsVersionLPr(userId);
        }
        return defaultRuntimePermissionsVersionLPr;
    }

    public void setRuntimePermissionsVersion(int version, int userId) {
        Preconditions.checkArgumentNonnegative(version);
        Preconditions.checkArgumentNonnegative(userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY", "setRuntimePermissionVersion");
        synchronized (this.mPackages) {
            this.mSettings.setDefaultRuntimePermissionsVersionLPr(version, userId);
        }
    }

    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        enforceSystemOrPhoneCaller("grantPermissionsToEnabledCarrierApps");
        synchronized (this.mPackages) {
            long identity = Binder.clearCallingIdentity();
            this.mDefaultPermissionPolicy.grantDefaultPermissionsToEnabledCarrierApps(packageNames, userId);
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId) {
        enforceSystemOrPhoneCaller("grantDefaultPermissionsToEnabledImsServices");
        synchronized (this.mPackages) {
            long identity = Binder.clearCallingIdentity();
            this.mDefaultPermissionPolicy.grantDefaultPermissionsToEnabledImsServices(packageNames, userId);
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void grantDefaultPermissionsToEnabledTelephonyDataServices(final String[] packageNames, final int userId) {
        enforceSystemOrPhoneCaller("grantDefaultPermissionsToEnabledTelephonyDataServices");
        synchronized (this.mPackages) {
            Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$z4kQS6Vp_boV8S3inVCqNLuSsc0
                public final void runOrThrow() {
                    PackageManagerService.this.lambda$grantDefaultPermissionsToEnabledTelephonyDataServices$38$PackageManagerService(packageNames, userId);
                }
            });
        }
    }

    public /* synthetic */ void lambda$grantDefaultPermissionsToEnabledTelephonyDataServices$38$PackageManagerService(String[] packageNames, int userId) throws Exception {
        this.mDefaultPermissionPolicy.grantDefaultPermissionsToEnabledTelephonyDataServices(packageNames, userId);
    }

    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(final String[] packageNames, final int userId) {
        enforceSystemOrPhoneCaller("revokeDefaultPermissionsFromDisabledTelephonyDataServices");
        synchronized (this.mPackages) {
            Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$dyaZ6QFtotZmymrJHkCMW1IdDuw
                public final void runOrThrow() {
                    PackageManagerService.this.lambda$revokeDefaultPermissionsFromDisabledTelephonyDataServices$39$PackageManagerService(packageNames, userId);
                }
            });
        }
    }

    public /* synthetic */ void lambda$revokeDefaultPermissionsFromDisabledTelephonyDataServices$39$PackageManagerService(String[] packageNames, int userId) throws Exception {
        this.mDefaultPermissionPolicy.revokeDefaultPermissionsFromDisabledTelephonyDataServices(packageNames, userId);
    }

    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId) {
        enforceSystemOrPhoneCaller("grantDefaultPermissionsToActiveLuiApp");
        synchronized (this.mPackages) {
            long identity = Binder.clearCallingIdentity();
            this.mDefaultPermissionPolicy.grantDefaultPermissionsToActiveLuiApp(packageName, userId);
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId) {
        enforceSystemOrPhoneCaller("revokeDefaultPermissionsFromLuiApps");
        synchronized (this.mPackages) {
            long identity = Binder.clearCallingIdentity();
            this.mDefaultPermissionPolicy.revokeDefaultPermissionsFromLuiApps(packageNames, userId);
            Binder.restoreCallingIdentity(identity);
        }
    }

    void forEachPackage(Consumer<PackageParser.Package> actionLocked) {
        synchronized (this.mPackages) {
            int numPackages = this.mPackages.size();
            for (int i = 0; i < numPackages; i++) {
                actionLocked.accept(this.mPackages.valueAt(i));
            }
        }
    }

    void forEachInstalledPackage(Consumer<PackageParser.Package> actionLocked, int userId) {
        synchronized (this.mPackages) {
            int numPackages = this.mPackages.size();
            for (int i = 0; i < numPackages; i++) {
                PackageParser.Package pkg = this.mPackages.valueAt(i);
                PackageSetting setting = this.mSettings.getPackageLPr(pkg.packageName);
                if (setting != null && setting.getInstalled(userId)) {
                    actionLocked.accept(pkg);
                }
            }
        }
    }

    private static void enforceSystemOrPhoneCaller(String tag) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 1001 && callingUid != 1000) {
            throw new SecurityException("Cannot call " + tag + " from UID " + callingUid);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isHistoricalPackageUsageAvailable() {
        return this.mPackageUsage.isHistoricalPackageUsageAvailable();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Collection<PackageParser.Package> getPackages() {
        ArrayList arrayList;
        synchronized (this.mPackages) {
            arrayList = new ArrayList(this.mPackages.values());
        }
        return arrayList;
    }

    public void logAppProcessStartIfNeeded(String processName, int uid, String seinfo, String apkFile, int pid) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null || !SecurityLog.isLoggingEnabled()) {
            return;
        }
        Bundle data = new Bundle();
        data.putLong("startTimestamp", System.currentTimeMillis());
        data.putString("processName", processName);
        data.putInt(WatchlistLoggingHandler.WatchlistEventKeys.UID, uid);
        data.putString("seinfo", seinfo);
        data.putString("apkFile", apkFile);
        data.putInt("pid", pid);
        Message msg = this.mProcessLoggingHandler.obtainMessage(1);
        msg.setData(data);
        this.mProcessLoggingHandler.sendMessage(msg);
    }

    public CompilerStats.PackageStats getCompilerPackageStats(String pkgName) {
        return this.mCompilerStats.getPackageStats(pkgName);
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(PackageParser.Package pkg) {
        return getOrCreateCompilerPackageStats(pkg.packageName);
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(String pkgName) {
        return this.mCompilerStats.getOrCreatePackageStats(pkgName);
    }

    public void deleteCompilerPackageStats(String pkgName) {
        this.mCompilerStats.deletePackageStats(pkgName);
    }

    public int getInstallReason(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, false, "get install reason");
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (filterAppAccessLPr(ps, callingUid, userId)) {
                return 0;
            }
            if (ps != null) {
                return ps.getInstallReason(userId);
            }
            return 0;
        }
    }

    public boolean canRequestPackageInstalls(String packageName, int userId) {
        return canRequestPackageInstallsInternal(packageName, 0, userId, true);
    }

    private boolean canRequestPackageInstallsInternal(String packageName, int flags, int userId, boolean throwIfPermNotDeclared) {
        PackageManagerInternal.ExternalSourcesPolicy externalSourcesPolicy;
        int callingUid = Binder.getCallingUid();
        int uid = getPackageUid(packageName, 0, userId);
        if (callingUid != uid && callingUid != 0 && callingUid != 1000) {
            throw new SecurityException("Caller uid " + callingUid + " does not own package " + packageName);
        }
        ApplicationInfo info = getApplicationInfo(packageName, flags, userId);
        if (info == null || info.targetSdkVersion < 26 || isInstantApp(packageName, userId)) {
            return false;
        }
        String[] packagesDeclaringPermission = getAppOpPermissionPackages("android.permission.REQUEST_INSTALL_PACKAGES");
        if (ArrayUtils.contains(packagesDeclaringPermission, packageName)) {
            if (sUserManager.hasUserRestriction("no_install_unknown_sources", userId) || sUserManager.hasUserRestriction("no_install_unknown_sources_globally", userId) || (externalSourcesPolicy = this.mExternalSourcesPolicy) == null) {
                return false;
            }
            int isTrusted = externalSourcesPolicy.getPackageTrustedToInstallApps(packageName, uid);
            return isTrusted == 0;
        } else if (throwIfPermNotDeclared) {
            throw new SecurityException("Need to declare android.permission.REQUEST_INSTALL_PACKAGES to call this api");
        } else {
            Slog.e(TAG, "Need to declare android.permission.REQUEST_INSTALL_PACKAGES to call this api");
            return false;
        }
    }

    public ComponentName getInstantAppResolverSettingsComponent() {
        return this.mInstantAppResolverSettingsComponent;
    }

    public ComponentName getInstantAppInstallerComponent() {
        ActivityInfo activityInfo;
        if (getInstantAppPackageName(Binder.getCallingUid()) == null && (activityInfo = this.mInstantAppInstallerActivity) != null) {
            return activityInfo.getComponentName();
        }
        return null;
    }

    public String getInstantAppAndroidId(String packageName, int userId) {
        String instantAppAndroidIdLPw;
        this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_INSTANT_APPS", "getInstantAppAndroidId");
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "getInstantAppAndroidId");
        if (!isInstantApp(packageName, userId)) {
            return null;
        }
        synchronized (this.mPackages) {
            instantAppAndroidIdLPw = this.mInstantAppRegistry.getInstantAppAndroidIdLPw(packageName, userId);
        }
        return instantAppAndroidIdLPw;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canHaveOatDir(String packageName) {
        synchronized (this.mPackages) {
            PackageParser.Package p = this.mPackages.get(packageName);
            if (p == null) {
                return false;
            }
            return p.canHaveOatDir();
        }
    }

    private String getOatDir(PackageParser.Package pkg) {
        if (pkg.canHaveOatDir()) {
            File codePath = new File(pkg.codePath);
            if (codePath.isDirectory()) {
                return PackageDexOptimizer.getOatDir(codePath).getAbsolutePath();
            }
            return null;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deleteOatArtifactsOfPackage(String packageName) {
        PackageParser.Package pkg;
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(packageName);
        }
        String[] instructionSets = InstructionSets.getAppDexInstructionSets(pkg.applicationInfo);
        List<String> codePaths = pkg.getAllCodePaths();
        String oatDir = getOatDir(pkg);
        for (String codePath : codePaths) {
            for (String isa : instructionSets) {
                try {
                    this.mInstaller.deleteOdex(codePath, isa, oatDir);
                } catch (Installer.InstallerException e) {
                    Log.e(TAG, "Failed deleting oat files for " + codePath, e);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Set<String> getUnusedPackages(long downgradeTimeThresholdMillis) {
        Set<String> unusedPackages = new HashSet<>();
        long currentTimeInMillis = System.currentTimeMillis();
        ArrayMap<String, PackageParser.Package> arrayMap = this.mPackages;
        synchronized (arrayMap) {
            try {
                try {
                    Iterator<PackageParser.Package> it = this.mPackages.values().iterator();
                    while (it.hasNext()) {
                        PackageParser.Package pkg = it.next();
                        PackageSetting ps = this.mSettings.mPackages.get(pkg.packageName);
                        if (ps != null) {
                            PackageDexUsage.PackageUseInfo packageUseInfo = getDexManager().getPackageUseInfoOrDefault(pkg.packageName);
                            Iterator<PackageParser.Package> it2 = it;
                            ArrayMap<String, PackageParser.Package> arrayMap2 = arrayMap;
                            if (PackageManagerServiceUtils.isUnusedSinceTimeInMillis(ps.firstInstallTime, currentTimeInMillis, downgradeTimeThresholdMillis, packageUseInfo, pkg.getLatestPackageUseTimeInMills(), pkg.getLatestForegroundPackageUseTimeInMills())) {
                                unusedPackages.add(pkg.packageName);
                            }
                            arrayMap = arrayMap2;
                            it = it2;
                        }
                    }
                    return unusedPackages;
                } catch (Throwable th) {
                    th = th;
                    ArrayMap<String, PackageParser.Package> arrayMap3 = arrayMap;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    public void setHarmfulAppWarning(String packageName, CharSequence warning, int userId) {
        int callingUid = Binder.getCallingUid();
        int callingAppId = UserHandle.getAppId(callingUid);
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, true, "setHarmfulAppInfo");
        if (callingAppId != 1000 && callingAppId != 0 && checkUidPermission("android.permission.SET_HARMFUL_APP_WARNINGS", callingUid) != 0) {
            throw new SecurityException("Caller must have the android.permission.SET_HARMFUL_APP_WARNINGS permission.");
        }
        synchronized (this.mPackages) {
            this.mSettings.setHarmfulAppWarningLPw(packageName, warning, userId);
            scheduleWritePackageRestrictionsLocked(userId);
        }
    }

    public CharSequence getHarmfulAppWarning(String packageName, int userId) {
        String harmfulAppWarningLPr;
        int callingUid = Binder.getCallingUid();
        int callingAppId = UserHandle.getAppId(callingUid);
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, true, true, "getHarmfulAppInfo");
        if (callingAppId != 1000 && callingAppId != 0 && checkUidPermission("android.permission.SET_HARMFUL_APP_WARNINGS", callingUid) != 0) {
            throw new SecurityException("Caller must have the android.permission.SET_HARMFUL_APP_WARNINGS permission.");
        }
        synchronized (this.mPackages) {
            harmfulAppWarningLPr = this.mSettings.getHarmfulAppWarningLPr(packageName, userId);
        }
        return harmfulAppWarningLPr;
    }

    public boolean isPackageStateProtected(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        int callingAppId = UserHandle.getAppId(callingUid);
        this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, true, "isPackageStateProtected");
        if (callingAppId != 1000 && callingAppId != 0 && checkUidPermission("android.permission.MANAGE_DEVICE_ADMINS", callingUid) != 0) {
            throw new SecurityException("Caller must have the android.permission.MANAGE_DEVICE_ADMINS permission.");
        }
        return this.mProtectedPackages.isPackageStateProtected(userId, packageName);
    }

    public void sendDeviceCustomizationReadyBroadcast() {
        this.mContext.enforceCallingPermission("android.permission.SEND_DEVICE_CUSTOMIZATION_READY", "sendDeviceCustomizationReadyBroadcast");
        long ident = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent("android.intent.action.DEVICE_CUSTOMIZATION_READY");
            intent.setFlags(16777216);
            IActivityManager am = ActivityManager.getService();
            String[] requiredPermissions = {"android.permission.RECEIVE_DEVICE_CUSTOMIZATION_READY"};
            try {
                am.broadcastIntent((IApplicationThread) null, intent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, false, false, -1);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public xpPackageInfo getXpPackageInfo(String packageName) {
        return xpPackageManagerService.get(this.mContext).getXpPackageInfo(packageName);
    }

    public void setXpPackageInfo(String packageName, String packageInfo) {
        xpPackageManagerService.get(this.mContext).setXpPackageInfo(packageName, packageInfo);
    }

    public void updateAppScreenFlag(int gearLevel) {
        xpPackageManagerService.get(this.mContext).updateAppScreenFlag(gearLevel);
    }

    public ApplicationInfo getOverlayApplicationInfo(ApplicationInfo info) {
        return xpPackageManagerService.get(this.mContext).getOverlayApplicationInfo(info);
    }

    public ActivityInfo getOverlayActivityInfo(ActivityInfo info) {
        return xpPackageManagerService.get(this.mContext).getOverlayActivityInfo(info);
    }

    public void setPackageNotLaunched(String packageName, boolean stop, int userId) {
        synchronized (this.mPackages) {
            try {
                PackageSetting ps = this.mSettings.mPackages.get(packageName);
                if (ps != null) {
                    ps.setNotLaunched(stop, userId);
                    int currentuserId = UserHandle.getUserId(userId);
                    ps.setNotLaunched(stop, currentuserId);
                    ps.setStopped(stop, currentuserId);
                    this.mSettings.writePackageRestrictionsLPr(currentuserId);
                }
            } catch (Exception e) {
                xpLogger.e(TAG, "setPackageNotLaunched e=" + e);
            }
        }
    }

    public List<xpPackageInfo> getAllXpPackageInfos() {
        return xpPackageManagerService.get(this.mContext).getAllXpPackageInfos();
    }

    public List<xpAppInfo> getXpAppPackageList(int screenId) {
        List<xpAppInfo> list;
        List<xpAppInfo> list2;
        List<xpAppInfoInner> list3 = mNapaAppInfoInnerList;
        if (list3 == null) {
            synchronized (xpAppInfo.class) {
                if (mNapaAppInfoInnerList == null) {
                    mNapaAppInfoInnerList = napaAppInfoLoad();
                    dispatchNapaAppInfo(screenId);
                }
            }
        } else {
            synchronized (list3) {
                for (xpAppInfoInner infoInner : mNapaAppInfoInnerList) {
                    infoInner.appInfo.mXpAppIcon = xpPackageManagerService.get(this.mContext).getXpAppIcon(infoInner.appInfo.resId);
                    if (infoInner.appInfo.mXpAppIcon == null) {
                        infoInner.appInfo.mXpAppIcon = xpPackageManagerService.get(this.mContext).getXpAppIcon("default");
                    }
                }
                dispatchNapaAppInfo(screenId);
            }
        }
        Slog.d(TAG, "getXpAppPackageList screenId=" + screenId + " ,mNapaAppInfoListOne=" + mNapaAppInfoListOne + " ,mNapaAppInfoListTwo" + mNapaAppInfoListTwo);
        if (screenId == 0 && (list2 = mNapaAppInfoListOne) != null) {
            return list2;
        }
        if (screenId == 1 && (list = mNapaAppInfoListTwo) != null) {
            return list;
        }
        return mNapaAppInfoList;
    }

    private List<xpAppInfoInner> napaAppInfoLoad() {
        File fileName;
        List<xpAppInfoInner> infoInnerList = new ArrayList<>();
        List<xpAppInfo> infoList = new ArrayList<>();
        try {
            fileName = new File("/system/etc/xuiservice/napaAppInfo.txt");
        } catch (Exception e) {
            Slog.w(TAG, "appInfoLoad e=" + e);
        }
        if (!fileName.exists()) {
            Slog.w(TAG, "napaxpAppInfoLoad error on:/system/etc/xuiservice/napaAppInfo.txt");
            return infoInnerList;
        }
        InputStreamReader reader = new InputStreamReader(new FileInputStream("/system/etc/xuiservice/napaAppInfo.txt"));
        BufferedReader br = new BufferedReader(reader);
        while (true) {
            String line = br.readLine();
            if (line == null) {
                break;
            }
            xpAppInfoInner infoInner = parseNapaAppInfo(line);
            if (infoInner != null) {
                infoInnerList.add(infoInner);
            }
            if (infoInner != null && infoInner.display) {
                infoList.add(infoInner.appInfo);
            }
        }
        br.close();
        reader.close();
        Slog.d(TAG, "parsed xpAppInfo size:" + infoList.size() + " ,infoList=" + infoList + " infoInnerList.size()" + infoInnerList.size());
        return infoInnerList;
    }

    private void dispatchNapaAppInfo(int screenId) {
        synchronized (mNapaAppInfoInnerList) {
            try {
                if (screenId == 0) {
                    mNapaAppInfoListOne = new ArrayList();
                    for (xpAppInfoInner infoInner : mNapaAppInfoInnerList) {
                        if ((infoInner.appInfo.supportScreenId == -1 || infoInner.appInfo.supportScreenId == 0) && infoInner.display) {
                            mNapaAppInfoListOne.add(infoInner.appInfo);
                        }
                    }
                } else if (screenId == 1) {
                    mNapaAppInfoListTwo = new ArrayList();
                    for (xpAppInfoInner infoInner2 : mNapaAppInfoInnerList) {
                        if ((infoInner2.appInfo.supportScreenId == -1 || infoInner2.appInfo.supportScreenId == 1) && infoInner2.display) {
                            mNapaAppInfoListTwo.add(infoInner2.appInfo);
                        }
                    }
                } else {
                    mNapaAppInfoList = new ArrayList();
                    for (xpAppInfoInner infoInner3 : mNapaAppInfoInnerList) {
                        if (infoInner3.display) {
                            mNapaAppInfoList.add(infoInner3.appInfo);
                        }
                    }
                    Slog.w(TAG, "dispatchNapaAppInfo undefined screenId=" + screenId + " mNapaAppInfoList=" + mNapaAppInfoList);
                }
            } finally {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class xpAppInfoInner {
        public xpAppInfo appInfo;
        public boolean display;

        private xpAppInfoInner() {
        }

        /* synthetic */ xpAppInfoInner(AnonymousClass1 x0) {
            this();
        }
    }

    private xpAppInfoInner parseNapaAppInfo(String data) {
        int i;
        boolean z;
        if (data == null || data.startsWith("#") || data.length() < 8) {
            return null;
        }
        String[] params = data.split("#%");
        if (params == null || params.length < 7) {
            Slog.w(TAG, "invalid data:" + data);
            return null;
        }
        boolean display = false;
        if (params[0].startsWith("CFC:")) {
            String suffix = params[0].split(":")[1];
            if (!"0".equals(suffix)) {
                boolean adjudgeFalse = suffix.startsWith("!");
                String cfc_param = adjudgeFalse ? suffix.substring(1) : suffix;
                String[] props = cfc_param.split("&");
                int mPropValue = 0;
                for (String str : props) {
                    mPropValue += FeatureOption.hasFeature(str) ? 1 : 0;
                }
                Slog.i(TAG, "app info suffix=" + suffix + " props[0]=" + props[0] + "props.length=" + props.length + "mPropValue=" + mPropValue);
                boolean support = mPropValue == props.length;
                if (adjudgeFalse) {
                    z = !support;
                } else {
                    z = support;
                }
                display = z;
                if (display) {
                    Slog.w(TAG, "app info not support,data=" + data);
                }
            } else {
                display = true;
            }
        } else if ("DIS:0".equals(params[0])) {
            Slog.i(TAG, "app info disable:" + data);
            display = false;
        } else if (params[0].startsWith("PROV:")) {
            String provKey = params[0].split(":")[1];
            Settings.Global.getUriFor(provKey);
            HandlerThread mProvHandlerThread = new HandlerThread("MyHandlerThread");
            mProvHandlerThread.start();
            Handler mProvHandler = new Handler(mProvHandlerThread.getLooper());
            this.mPackageValueObserver = new PackageValueObserver(mProvHandler);
            this.mPackageValueObserver.startObserving(provKey);
            if (!"0".equals(provKey)) {
                int value = Settings.Global.getInt(this.mContext.getContentResolver(), provKey, 0);
                boolean isProvSupport = value == 1;
                display = isProvSupport;
                Slog.d(TAG, "app info when PROV provKey=" + provKey + ",isProvSupport=" + isProvSupport + ",display=" + display + ",value=" + value);
            }
        }
        xpAppInfoInner infoinner = new xpAppInfoInner(null);
        infoinner.display = display;
        infoinner.appInfo = new xpAppInfo();
        infoinner.appInfo.mLaunchMode = 0;
        infoinner.appInfo.resId = params[1];
        infoinner.appInfo.mXpAppTitle = params[2];
        infoinner.appInfo.mXpAppId = params[3];
        infoinner.appInfo.mXpAppPage = params[4];
        if (params.length > 5) {
            if ("0".equals(params[5])) {
                infoinner.appInfo.mLaunchMode = 0;
                infoinner.appInfo.mLaunchParam = null;
                i = 1;
            } else {
                String[] launchParams = params[5].split("&", -1);
                infoinner.appInfo.mLaunchMode = Integer.parseInt(launchParams[0]);
                i = 1;
                infoinner.appInfo.mLaunchParam = launchParams[1];
            }
            int intValue = Integer.valueOf(params[6]).intValue();
            if (intValue == -1) {
                infoinner.appInfo.supportScreenId = -1;
            } else if (intValue == 0) {
                infoinner.appInfo.supportScreenId = 0;
            } else if (intValue != i) {
                Slog.w(TAG, "parseNapaAppInfo undefined params[6] value is =" + Integer.valueOf(params[6]).intValue());
                infoinner.appInfo.supportScreenId = -1;
            } else {
                infoinner.appInfo.supportScreenId = 1;
            }
        }
        infoinner.appInfo.mXpAppIcon = xpPackageManagerService.get(this.mContext).getXpAppIcon(infoinner.appInfo.resId);
        return infoinner;
    }

    public Bitmap getXpAppIcon(String pkgName) {
        return xpPackageManagerService.get(this.mContext).getXpAppIcon(pkgName);
    }

    /* loaded from: classes.dex */
    static class ActiveInstallSession {
        private final String mInstallerPackageName;
        private final int mInstallerUid;
        private final IPackageInstallObserver2 mObserver;
        private final String mPackageName;
        private final PackageInstaller.SessionParams mSessionParams;
        private final PackageParser.SigningDetails mSigningDetails;
        private final File mStagedDir;
        private final UserHandle mUser;

        /* JADX INFO: Access modifiers changed from: package-private */
        public ActiveInstallSession(String packageName, File stagedDir, IPackageInstallObserver2 observer, PackageInstaller.SessionParams sessionParams, String installerPackageName, int installerUid, UserHandle user, PackageParser.SigningDetails signingDetails) {
            this.mPackageName = packageName;
            this.mStagedDir = stagedDir;
            this.mObserver = observer;
            this.mSessionParams = sessionParams;
            this.mInstallerPackageName = installerPackageName;
            this.mInstallerUid = installerUid;
            this.mUser = user;
            this.mSigningDetails = signingDetails;
        }

        public String getPackageName() {
            return this.mPackageName;
        }

        public File getStagedDir() {
            return this.mStagedDir;
        }

        public IPackageInstallObserver2 getObserver() {
            return this.mObserver;
        }

        public PackageInstaller.SessionParams getSessionParams() {
            return this.mSessionParams;
        }

        public String getInstallerPackageName() {
            return this.mInstallerPackageName;
        }

        public int getInstallerUid() {
            return this.mInstallerUid;
        }

        public UserHandle getUser() {
            return this.mUser;
        }

        public PackageParser.SigningDetails getSigningDetails() {
            return this.mSigningDetails;
        }
    }
}
