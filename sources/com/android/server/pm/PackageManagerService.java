package com.android.server.pm;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IApplicationThread;
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
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AppsQueryHelper;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.ComponentInfo;
import android.content.pm.FallbackCategoryProvider;
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
import android.content.pm.InstantAppResolveInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.PackageCleanItem;
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
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VerifierInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.IArtManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.hardware.biometrics.fingerprint.V2_1.RequestStatus;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
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
import android.os.storage.IStorageManager;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.security.KeyStore;
import android.security.SystemKeyStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Base64;
import android.util.ByteStringUtils;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.LogPrinter;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.MathUtils;
import android.util.PackageUtils;
import android.util.Pair;
import android.util.PrintStreamPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimingsTraceLog;
import android.util.Xml;
import android.util.jar.StrictJarFile;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.app.IntentForwarderActivity;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.IParcelFileDescriptorFactory;
import com.android.internal.os.SomeArgs;
import com.android.internal.telephony.CarrierAppUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.AttributeCache;
import com.android.server.BatteryService;
import com.android.server.DeviceIdleController;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.IntentResolver;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.Watchdog;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.android.server.pm.CompilerStats;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageDexOptimizer;
import com.android.server.pm.Settings;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.DexLogger;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.dex.PackageDexUsage;
import com.android.server.pm.permission.BasePermission;
import com.android.server.pm.permission.DefaultPermissionGrantPolicy;
import com.android.server.pm.permission.PermissionManagerInternal;
import com.android.server.pm.permission.PermissionManagerService;
import com.android.server.pm.permission.PermissionsState;
import com.android.server.security.VerityUtils;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import com.xiaopeng.app.xpAppInfo;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.util.DebugOption;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.LocaleStrings;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
@SuppressLint({"all"})
/* loaded from: classes.dex */
public class PackageManagerService extends IPackageManager.Stub implements PackageSender {
    private static final String ATTR_IS_GRANTED = "g";
    private static final String ATTR_PACKAGE_NAME = "pkg";
    private static final String ATTR_PERMISSION_NAME = "name";
    private static final String ATTR_REVOKE_ON_UPGRADE = "rou";
    private static final String ATTR_USER_FIXED = "fixed";
    private static final String ATTR_USER_SET = "set";
    private static final int BLUETOOTH_UID = 1002;
    static final int BROADCAST_DELAY = 10000;
    static final int CHECK_PENDING_VERIFICATION = 16;
    static final boolean CLEAR_RUNTIME_PERMISSIONS_ON_UPGRADE = false;
    public static final String COMPRESSED_EXTENSION = ".gz";
    static final boolean DEBUG_SD_INSTALL = false;
    private static final long DEFAULT_MANDATORY_FSTRIM_INTERVAL = 259200000;
    private static final boolean DEFAULT_PACKAGE_PARSER_CACHE_ENABLED = true;
    private static final long DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD = 7200000;
    private static final int DEFAULT_VERIFICATION_RESPONSE = 1;
    private static final long DEFAULT_VERIFICATION_TIMEOUT = 10000;
    private static final boolean DEFAULT_VERIFY_ENABLE = true;
    static final int DEF_CONTAINER_BIND = 21;
    static final int END_COPY = 4;
    static final int FIND_INSTALL_LOC = 8;
    private static final boolean HIDE_EPHEMERAL_APIS = false;
    static final int INIT_COPY = 5;
    private static final String INSTALL_PACKAGE_SUFFIX = "-";
    private static final String[] INSTANT_APP_BROADCAST_PERMISSION;
    static final int INSTANT_APP_RESOLUTION_PHASE_TWO = 20;
    static final int INTENT_FILTER_VERIFIED = 18;
    private static final String KILL_APP_REASON_GIDS_CHANGED = "permission grant or revoke changed gids";
    private static final String KILL_APP_REASON_PERMISSIONS_REVOKED = "permissions revoked";
    private static final int LOG_UID = 1007;
    static final int MCS_BOUND = 3;
    static final int MCS_GIVE_UP = 11;
    static final int MCS_RECONNECT = 10;
    static final int MCS_UNBIND = 6;
    private static final int NFC_UID = 1027;
    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String PACKAGE_PARSER_CACHE_VERSION = "1";
    private static final String PACKAGE_SCHEME = "package";
    static final int PACKAGE_VERIFIED = 15;
    public static final String PLATFORM_PACKAGE_NAME = "android";
    static final int POST_INSTALL = 9;
    private static final String PRODUCT_OVERLAY_DIR = "/product/overlay";
    private static final Set<String> PROTECTED_ACTIONS;
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
    static final int SCAN_AS_OEM = 524288;
    static final int SCAN_AS_PRIVILEGED = 262144;
    static final int SCAN_AS_PRODUCT = 2097152;
    static final int SCAN_AS_SYSTEM = 131072;
    static final int SCAN_AS_VENDOR = 1048576;
    static final int SCAN_AS_VIRTUAL_PRELOAD = 65536;
    static final int SCAN_BOOTING = 16;
    static final int SCAN_CHECK_ONLY = 1024;
    static final int SCAN_DELETE_DATA_ON_FAILURES = 64;
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
    static final int START_CLEANING_PACKAGE = 7;
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
    private static final Comparator<ProviderInfo> mProviderInitOrderSorter;
    private static final Comparator<ResolveInfo> mResolvePrioritySorter;
    private static List<xpAppInfoInner> mXuiAppInfoInnerList;
    private static List<xpAppInfo> mXuiAppInfoList;
    public static final HashMap<Integer, List<xpAppInfo>> mXuiAppInfoListMapping;
    private static final File sAppInstallDir;
    private static final File sAppLib32InstallDir;
    private static final File sDrmAppPrivateInstallDir;
    static UserManagerService sUserManager;
    private ActivityManagerInternal mActivityManagerInternal;
    ApplicationInfo mAndroidApplication;
    final ArtManagerService mArtManagerService;
    @GuardedBy("mAvailableFeatures")
    final ArrayMap<String, FeatureInfo> mAvailableFeatures;
    private boolean mBootProfDisable;
    private File mCacheDir;
    @GuardedBy("mPackages")
    int mChangedPackagesSequenceNumber;
    final Context mContext;
    ComponentName mCustomResolverComponentName;
    final int mDefParseFlags;
    final DefaultPermissionGrantPolicy mDefaultPermissionPolicy;
    private boolean mDeferProtectedFilters;
    private DeviceIdleController.LocalService mDeviceIdleController;
    private final DexManager mDexManager;
    @GuardedBy("mPackages")
    private boolean mDexOptDialogShown;
    PackageManagerInternal.ExternalSourcesPolicy mExternalSourcesPolicy;
    final boolean mFactoryTest;
    boolean mFirstBoot;
    final PackageHandler mHandler;
    final ServiceThread mHandlerThread;
    volatile boolean mHasSystemUidErrors;
    @GuardedBy("mInstallLock")
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
    final boolean mIsUpgrade;
    private List<String> mKeepUninstalledPackages;
    final DisplayMetrics mMetrics;
    private final MoveCallbacks mMoveCallbacks;
    private final OnPermissionChangeListeners mOnPermissionChangeListeners;
    final boolean mOnlyCore;
    private final PackageDexOptimizer mPackageDexOptimizer;
    private PackageValueObserver mPackageValueObserver;
    private final PermissionManagerInternal mPermissionManager;
    PackageParser.Package mPlatformPackage;
    private ArrayList<Message> mPostSystemReadyMessages;
    private Future<?> mPrepareAppDataFuture;
    private final ProcessLoggingHandler mProcessLoggingHandler;
    boolean mPromoteSystemApps;
    final ProtectedPackages mProtectedPackages;
    final String mRequiredInstallerPackage;
    final String mRequiredUninstallerPackage;
    final String mRequiredVerifierPackage;
    ComponentName mResolveComponentName;
    volatile boolean mSafeMode;
    final String[] mSeparateProcesses;
    final String mServicesSystemSharedLibraryPackageName;
    @GuardedBy("mPackages")
    final Settings mSettings;
    final String mSetupWizardPackage;
    final String mSharedSystemSharedLibraryPackageName;
    final String mStorageManagerPackage;
    volatile boolean mSystemReady;
    final String mSystemTextClassifierPackage;
    private UserManagerInternal mUserManagerInternal;
    private volatile boolean mWebInstantAppsDisabled;
    private List<xpAppInfo> mXpAppPackageList;
    private static final boolean DEBUG_ALL = DebugOption.DEBUG_PM;
    public static final boolean DEBUG_SETTINGS = DEBUG_ALL;
    static final boolean DEBUG_PREFERRED = DEBUG_ALL;
    static final boolean DEBUG_UPGRADE = DEBUG_ALL;
    static final boolean DEBUG_DOMAIN_VERIFICATION = DEBUG_ALL;
    private static final boolean DEBUG_BACKUP = DEBUG_ALL;
    public static final boolean DEBUG_INSTALL = DEBUG_ALL;
    public static final boolean DEBUG_REMOVE = DEBUG_ALL;
    private static final boolean DEBUG_BROADCASTS = DEBUG_ALL;
    private static final boolean DEBUG_SHOW_INFO = DEBUG_ALL;
    private static final boolean DEBUG_PACKAGE_INFO = DEBUG_ALL;
    private static final boolean DEBUG_INTENT_MATCHING = DEBUG_ALL;
    public static final boolean DEBUG_PACKAGE_SCANNING = DEBUG_ALL;
    private static final boolean DEBUG_VERIFY = DEBUG_ALL;
    private static final boolean DEBUG_FILTERS = DEBUG_ALL;
    public static final boolean DEBUG_PERMISSIONS = DEBUG_ALL;
    private static final boolean DEBUG_SHARED_LIBRARIES = DEBUG_ALL;
    public static final boolean DEBUG_COMPRESSION = Build.IS_DEBUGGABLE;
    public static final boolean DEBUG_DEXOPT = DEBUG_ALL;
    private static final boolean DEBUG_ABI_SELECTION = DEBUG_ALL;
    private static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;
    private static final boolean DEBUG_TRIAGED_MISSING = DEBUG_ALL;
    private static final boolean DEBUG_APP_DATA = DEBUG_ALL;
    private static final boolean ENABLE_FREE_CACHE_V2 = SystemProperties.getBoolean("fw.free_cache_v2", true);
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    public static final String DEFAULT_CONTAINER_PACKAGE = "com.android.defcontainer";
    public static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(DEFAULT_CONTAINER_PACKAGE, "com.android.defcontainer.DefaultContainerService");
    private static final Intent sBrowserIntent = new Intent();
    final int mSdkVersion = Build.VERSION.SDK_INT;
    @GuardedBy("mPackages")
    boolean mDefaultContainerWhitelisted = false;
    final Object mInstallLock = new Object();
    @GuardedBy("mPackages")
    final ArrayMap<String, PackageParser.Package> mPackages = new ArrayMap<>();
    final ArrayMap<String, Set<String>> mKnownCodebase = new ArrayMap<>();
    @GuardedBy("mPackages")
    final SparseIntArray mIsolatedOwners = new SparseIntArray();
    private final ArrayMap<String, File> mExpectingBetter = new ArrayMap<>();
    private final List<PackageParser.ActivityIntentInfo> mProtectedFilters = new ArrayList();
    private final ArraySet<String> mExistingSystemPackages = new ArraySet<>();
    @GuardedBy("mPackages")
    final ArraySet<String> mFrozenPackages = new ArraySet<>();
    @GuardedBy("mLoadedVolumes")
    final ArraySet<String> mLoadedVolumes = new ArraySet<>();
    @GuardedBy("mPackages")
    final SparseArray<SparseArray<String>> mChangedPackages = new SparseArray<>();
    @GuardedBy("mPackages")
    final SparseArray<Map<String, Integer>> mChangedPackagesSequenceNumbers = new SparseArray<>();
    @GuardedBy("mPackages")
    private final ArraySet<PackageManagerInternal.PackageListObserver> mPackageListObservers = new ArraySet<>();
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.android.server.pm.PackageManagerService.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (((action.hashCode() == -19011148 && action.equals("android.intent.action.LOCALE_CHANGED")) ? (char) 0 : (char) 65535) == 0) {
                Slog.i(PackageManagerService.TAG, "ACTION_LOCALE_CHANGED,update app titles");
                synchronized (PackageManagerService.mXuiAppInfoInnerList) {
                    for (xpAppInfoInner infoInner : PackageManagerService.mXuiAppInfoInnerList) {
                        infoInner.appInfo.mXpAppTitle = PackageManagerService.getResourceTitleName(infoInner.appInfo.resId);
                        if (infoInner.appInfo.mXpAppTitle == null) {
                            infoInner.appInfo.mXpAppTitle = infoInner.defaultTitle;
                            Slog.w(PackageManagerService.TAG, "app info undefined,app=" + infoInner.appInfo.resId + ",set default:" + infoInner.defaultTitle);
                        }
                    }
                }
            }
        }
    };
    final PackageParser.Callback mPackageParserCallback = new PackageParserCallback();
    final ParallelPackageParserCallback mParallelPackageParserCallback = new ParallelPackageParserCallback();
    final ArrayMap<String, LongSparseArray<SharedLibraryEntry>> mSharedLibraries = new ArrayMap<>();
    final ArrayMap<String, LongSparseArray<SharedLibraryEntry>> mStaticLibsByDeclaringPackage = new ArrayMap<>();
    final ActivityIntentResolver mActivities = new ActivityIntentResolver();
    final ActivityIntentResolver mReceivers = new ActivityIntentResolver();
    final ServiceIntentResolver mServices = new ServiceIntentResolver();
    final ProviderIntentResolver mProviders = new ProviderIntentResolver();
    final ArrayMap<String, PackageParser.Provider> mProvidersByAuthority = new ArrayMap<>();
    final ArrayMap<ComponentName, PackageParser.Instrumentation> mInstrumentation = new ArrayMap<>();
    final ArraySet<String> mTransferedPackages = new ArraySet<>();
    @GuardedBy("mProtectedBroadcasts")
    final ArraySet<String> mProtectedBroadcasts = new ArraySet<>();
    final SparseArray<PackageVerificationState> mPendingVerification = new SparseArray<>();
    private AtomicInteger mNextMoveId = new AtomicInteger();
    SparseBooleanArray mUserNeedsBadging = new SparseBooleanArray();
    private int mPendingVerificationToken = 0;
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    boolean mResolverReplaced = false;
    private int mIntentFilterVerificationToken = 0;
    final ResolveInfo mInstantAppInstallerInfo = new ResolveInfo();
    final SparseArray<IntentFilterVerificationState> mIntentFilterVerificationStates = new SparseArray<>();
    final PendingPackageBroadcasts mPendingBroadcasts = new PendingPackageBroadcasts();
    private IMediaContainerService mContainerService = null;
    private ArraySet<Integer> mDirtyUsers = new ArraySet<>();
    private final DefaultContainerConnection mDefContainerConn = new DefaultContainerConnection();
    final SparseArray<PostInstallData> mRunningInstalls = new SparseArray<>();
    int mNextInstallToken = 1;
    private final PackageUsage mPackageUsage = new PackageUsage();
    private final CompilerStats mCompilerStats = new CompilerStats();
    private PermissionManagerInternal.PermissionCallback mPermissionCallback = new PermissionManagerInternal.PermissionCallback() { // from class: com.android.server.pm.PackageManagerService.2
        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onGidsChanged(final int appId, final int userId) {
            PackageManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.2.1
                @Override // java.lang.Runnable
                public void run() {
                    PackageManagerService.this.killUid(appId, userId, PackageManagerService.KILL_APP_REASON_GIDS_CHANGED);
                }
            });
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onPermissionGranted(int uid, int userId) {
            PackageManagerService.this.mOnPermissionChangeListeners.onPermissionsChanged(uid);
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.writeRuntimePermissionsForUserLPr(userId, false);
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onInstallPermissionGranted() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.scheduleWriteSettingsLocked();
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onPermissionRevoked(int uid, int userId) {
            PackageManagerService.this.mOnPermissionChangeListeners.onPermissionsChanged(uid);
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.writeRuntimePermissionsForUserLPr(userId, true);
            }
            int appId = UserHandle.getAppId(uid);
            PackageManagerService.this.killUid(appId, userId, PackageManagerService.KILL_APP_REASON_PERMISSIONS_REVOKED);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onInstallPermissionRevoked() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.scheduleWriteSettingsLocked();
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onPermissionUpdated(int[] updatedUserIds, boolean sync) {
            synchronized (PackageManagerService.this.mPackages) {
                for (int userId : updatedUserIds) {
                    PackageManagerService.this.mSettings.writeRuntimePermissionsForUserLPr(userId, sync);
                }
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onInstallPermissionUpdated() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.scheduleWriteSettingsLocked();
            }
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
        public void onPermissionRemoved() {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.writeLPr();
            }
        }
    };
    private StorageEventListener mStorageListener = new StorageEventListener() { // from class: com.android.server.pm.PackageManagerService.3
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            if (vol.type == 1) {
                if (vol.state != 2) {
                    if (vol.state == 5) {
                        PackageManagerService.this.unloadPrivatePackages(vol);
                        return;
                    }
                    return;
                }
                String volumeUuid = vol.getFsUuid();
                PackageManagerService.sUserManager.reconcileUsers(volumeUuid);
                PackageManagerService.this.reconcileApps(volumeUuid);
                PackageManagerService.this.mInstallerService.onPrivateVolumeMounted(volumeUuid);
                PackageManagerService.this.loadPrivatePackages(vol);
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

    static /* synthetic */ int access$4808(PackageManagerService x0) {
        int i = x0.mPendingVerificationToken;
        x0.mPendingVerificationToken = i + 1;
        return i;
    }

    static {
        sBrowserIntent.setAction("android.intent.action.VIEW");
        sBrowserIntent.addCategory("android.intent.category.BROWSABLE");
        sBrowserIntent.setData(Uri.parse("http:"));
        sBrowserIntent.addFlags(512);
        PROTECTED_ACTIONS = new ArraySet();
        PROTECTED_ACTIONS.add("android.intent.action.SEND");
        PROTECTED_ACTIONS.add("android.intent.action.SENDTO");
        PROTECTED_ACTIONS.add("android.intent.action.SEND_MULTIPLE");
        PROTECTED_ACTIONS.add("android.intent.action.VIEW");
        INSTANT_APP_BROADCAST_PERMISSION = new String[]{"android.permission.ACCESS_INSTANT_APPS"};
        sAppInstallDir = new File(Environment.getDataDirectory(), "app");
        sAppLib32InstallDir = new File(Environment.getDataDirectory(), "app-lib");
        sDrmAppPrivateInstallDir = new File(Environment.getDataDirectory(), "app-private");
        mResolvePrioritySorter = new Comparator<ResolveInfo>() { // from class: com.android.server.pm.PackageManagerService.6
            @Override // java.util.Comparator
            public int compare(ResolveInfo r1, ResolveInfo r2) {
                int v1 = r1.priority;
                int v2 = r2.priority;
                if (v1 != v2) {
                    return v1 > v2 ? -1 : 1;
                }
                int v12 = r1.preferredOrder;
                int v22 = r2.preferredOrder;
                if (v12 != v22) {
                    return v12 > v22 ? -1 : 1;
                } else if (r1.isDefault != r2.isDefault) {
                    return r1.isDefault ? -1 : 1;
                } else {
                    int v13 = r1.match;
                    int v23 = r2.match;
                    if (v13 != v23) {
                        return v13 > v23 ? -1 : 1;
                    } else if (r1.system != r2.system) {
                        return r1.system ? -1 : 1;
                    } else if (r1.activityInfo != null) {
                        return r1.activityInfo.packageName.compareTo(r2.activityInfo.packageName);
                    } else {
                        if (r1.serviceInfo != null) {
                            return r1.serviceInfo.packageName.compareTo(r2.serviceInfo.packageName);
                        }
                        if (r1.providerInfo != null) {
                            return r1.providerInfo.packageName.compareTo(r2.providerInfo.packageName);
                        }
                        return 0;
                    }
                }
            }
        };
        mProviderInitOrderSorter = new Comparator<ProviderInfo>() { // from class: com.android.server.pm.PackageManagerService.7
            @Override // java.util.Comparator
            public int compare(ProviderInfo p1, ProviderInfo p2) {
                int v1 = p1.initOrder;
                int v2 = p2.initOrder;
                if (v1 > v2) {
                    return -1;
                }
                return v1 < v2 ? 1 : 0;
            }
        };
        mXuiAppInfoList = null;
        mXuiAppInfoInnerList = null;
        mXuiAppInfoListMapping = new HashMap<>();
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
            if (this.KEY_NAPA != null && PackageManagerService.mXuiAppInfoInnerList != null) {
                xpAppInfoInner mInfoInner = null;
                int i = 0;
                while (true) {
                    if (i >= PackageManagerService.mXuiAppInfoInnerList.size()) {
                        break;
                    }
                    String extr = this.KEY_NAPA.split(PackageManagerService.STATIC_SHARED_LIB_DELIMITER)[2];
                    String total = "applist_" + extr;
                    if (total.equals(((xpAppInfoInner) PackageManagerService.mXuiAppInfoInnerList.get(i)).appInfo.resId)) {
                        ((xpAppInfoInner) PackageManagerService.mXuiAppInfoInnerList.get(i)).display = this.PROV_NAPA != 0;
                        mInfoInner = (xpAppInfoInner) PackageManagerService.mXuiAppInfoInnerList.get(i);
                    } else {
                        i++;
                    }
                }
                if (mInfoInner != null) {
                    PackageManagerService.this.sendBroadcastForProvChange(PackageManagerService.this.mContext, mInfoInner);
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
        if (this.mBootProfDisable) {
            return;
        }
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
                Comparator<PackageParser.Package> cmp = new Comparator<PackageParser.Package>() { // from class: com.android.server.pm.PackageManagerService.PackageParserCallback.1
                    @Override // java.util.Comparator
                    public int compare(PackageParser.Package p1, PackageParser.Package p2) {
                        return p1.mOverlayPriority - p2.mOverlayPriority;
                    }
                };
                Collections.sort(overlayPackages, cmp);
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

    /* loaded from: classes.dex */
    public static final class SharedLibraryEntry {
        public final String apk;
        public final SharedLibraryInfo info;
        public final String path;

        SharedLibraryEntry(String _path, String _apk, String name, long version, int type, String declaringPackageName, long declaringPackageVersionCode) {
            this.path = _path;
            this.apk = _apk;
            this.info = new SharedLibraryInfo(name, version, type, new VersionedPackage(declaringPackageName, declaringPackageVersionCode), null);
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
            this.replacing = _replacing;
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
            DeviceIdleController.LocalService idleController = PackageManagerService.this.getDeviceIdleController();
            idleController.addPowerSaveTempWhitelistApp(Process.myUid(), this.mIntentFilterVerifierComponent.getPackageName(), PackageManagerService.this.getVerificationTimeout(), 0, true, "intent filter verifier");
            this.mContext.sendBroadcastAsUser(verificationIntent, UserHandle.SYSTEM);
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(PackageManagerService.TAG, "Sending IntentFilter verification broadcast");
            }
        }

        @Override // com.android.server.pm.PackageManagerService.IntentFilterVerifier
        public void receiveVerificationResponse(int verificationId) {
            IntentFilterVerificationInfo ivi;
            IntentFilterVerificationState ivs = PackageManagerService.this.mIntentFilterVerificationStates.get(verificationId);
            boolean verified = ivs.isVerified();
            ArrayList<PackageParser.ActivityIntentInfo> filters = ivs.getFilters();
            int count = filters.size();
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.i(PackageManagerService.TAG, "Received verification response " + verificationId + " for " + count + " filters, verified=" + verified);
            }
            for (int n = 0; n < count; n++) {
                PackageParser.ActivityIntentInfo filter = filters.get(n);
                filter.setVerified(verified);
                if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                    Slog.d(PackageManagerService.TAG, "IntentFilter " + filter.toString() + " verified with result:" + verified + " and hosts:" + ivs.getHostsString());
                }
            }
            PackageManagerService.this.mIntentFilterVerificationStates.remove(verificationId);
            String packageName = ivs.getPackageName();
            synchronized (PackageManagerService.this.mPackages) {
                ivi = PackageManagerService.this.mSettings.getIntentFilterVerificationLPr(packageName);
            }
            if (ivi == null) {
                Slog.w(PackageManagerService.TAG, "IntentFilterVerificationInfo not found for verificationId:" + verificationId + " packageName:" + packageName);
                return;
            }
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(PackageManagerService.TAG, "Updating IntentFilterVerificationInfo for package " + packageName + " verificationId:" + verificationId);
            }
            synchronized (PackageManagerService.this.mPackages) {
                try {
                    if (verified) {
                        ivi.setStatus(2);
                    } else {
                        ivi.setStatus(1);
                    }
                    PackageManagerService.this.scheduleWriteSettingsLocked();
                    int userId = ivs.getUserId();
                    if (userId != -1) {
                        int userStatus = PackageManagerService.this.mSettings.getIntentFilterVerificationStatusLPr(packageName, userId);
                        int updatedStatus = 0;
                        boolean needUpdate = false;
                        switch (userStatus) {
                            case 0:
                                if (verified) {
                                    updatedStatus = 2;
                                } else {
                                    updatedStatus = 1;
                                }
                                needUpdate = true;
                                break;
                            case 1:
                                if (verified) {
                                    updatedStatus = 2;
                                    needUpdate = true;
                                    break;
                                }
                                break;
                        }
                        if (needUpdate) {
                            PackageManagerService.this.mSettings.updateIntentFilterVerificationStatusLPw(packageName, updatedStatus, userId);
                            PackageManagerService.this.scheduleWritePackageRestrictionsLocked(userId);
                        }
                    }
                } finally {
                }
            }
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
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(PackageManagerService.TAG, "Adding verification filter for " + packageName + ": " + filter);
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
    public class DefaultContainerConnection implements ServiceConnection {
        DefaultContainerConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            IMediaContainerService imcs = IMediaContainerService.Stub.asInterface(Binder.allowBlocking(service));
            PackageManagerService.this.mHandler.sendMessage(PackageManagerService.this.mHandler.obtainMessage(3, imcs));
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class PostInstallData {
        public InstallArgs args;
        public PackageInstalledInfo res;

        PostInstallData(InstallArgs _a, PackageInstalledInfo _r) {
            this.args = _a;
            this.res = _r;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class PackageHandler extends Handler {
        private boolean mBound;
        final ArrayList<HandlerParams> mPendingInstalls;

        private boolean connectToService() {
            if (PackageManagerService.DEBUG_INSTALL) {
                Log.i(PackageManagerService.TAG, "Trying to bind to DefaultContainerService");
            }
            Intent service = new Intent().setComponent(PackageManagerService.DEFAULT_CONTAINER_COMPONENT);
            Process.setThreadPriority(0);
            if (PackageManagerService.this.mContext.bindServiceAsUser(service, PackageManagerService.this.mDefContainerConn, 1, UserHandle.SYSTEM)) {
                Process.setThreadPriority(10);
                this.mBound = true;
                return true;
            }
            Process.setThreadPriority(10);
            return false;
        }

        private void disconnectService() {
            PackageManagerService.this.mContainerService = null;
            this.mBound = false;
            Process.setThreadPriority(0);
            PackageManagerService.this.mContext.unbindService(PackageManagerService.this.mDefContainerConn);
            Process.setThreadPriority(10);
        }

        PackageHandler(Looper looper) {
            super(looper);
            this.mBound = false;
            this.mPendingInstalls = new ArrayList<>();
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
            int ret;
            int i = 0;
            switch (msg.what) {
                case 1:
                    Process.setThreadPriority(0);
                    synchronized (PackageManagerService.this.mPackages) {
                        if (PackageManagerService.this.mPendingBroadcasts == null) {
                            return;
                        }
                        int size = PackageManagerService.this.mPendingBroadcasts.size();
                        if (size <= 0) {
                            return;
                        }
                        String[] packages = new String[size];
                        ArrayList<String>[] components = new ArrayList[size];
                        int[] uids = new int[size];
                        int i2 = 0;
                        for (int i3 = 0; i3 < PackageManagerService.this.mPendingBroadcasts.userIdCount(); i3++) {
                            int packageUserId = PackageManagerService.this.mPendingBroadcasts.userIdAt(i3);
                            Iterator<Map.Entry<String, ArrayList<String>>> it = PackageManagerService.this.mPendingBroadcasts.packagesForUserId(packageUserId).entrySet().iterator();
                            while (it.hasNext() && i2 < size) {
                                Map.Entry<String, ArrayList<String>> ent = it.next();
                                packages[i2] = ent.getKey();
                                components[i2] = ent.getValue();
                                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(ent.getKey());
                                uids[i2] = ps != null ? UserHandle.getUid(packageUserId, ps.appId) : -1;
                                i2++;
                            }
                        }
                        int size2 = i2;
                        PackageManagerService.this.mPendingBroadcasts.clear();
                        int i4 = 0;
                        while (true) {
                            int i5 = i4;
                            if (i5 >= size2) {
                                Process.setThreadPriority(10);
                                return;
                            } else {
                                PackageManagerService.this.sendPackageChangedBroadcast(packages[i5], true, components[i5], uids[i5]);
                                i4 = i5 + 1;
                            }
                        }
                    }
                    break;
                case 2:
                case 4:
                case 8:
                case 12:
                default:
                    return;
                case 3:
                    if (PackageManagerService.DEBUG_INSTALL) {
                        Slog.i(PackageManagerService.TAG, "mcs_bound");
                    }
                    if (msg.obj != null) {
                        PackageManagerService.this.mContainerService = (IMediaContainerService) msg.obj;
                        Trace.asyncTraceEnd(262144L, "bindingMCS", System.identityHashCode(PackageManagerService.this.mHandler));
                    }
                    if (PackageManagerService.this.mContainerService == null) {
                        if (this.mBound) {
                            Slog.w(PackageManagerService.TAG, "Waiting to connect to media container service");
                            return;
                        }
                        Slog.e(PackageManagerService.TAG, "Cannot bind to media container service");
                        Iterator<HandlerParams> it2 = this.mPendingInstalls.iterator();
                        while (it2.hasNext()) {
                            HandlerParams params = it2.next();
                            params.serviceError();
                            Trace.asyncTraceEnd(262144L, "queueInstall", System.identityHashCode(params));
                            if (params.traceMethod != null) {
                                Trace.asyncTraceEnd(262144L, params.traceMethod, params.traceCookie);
                            }
                        }
                        this.mPendingInstalls.clear();
                        return;
                    } else if (this.mPendingInstalls.size() <= 0) {
                        Slog.w(PackageManagerService.TAG, "Empty queue");
                        return;
                    } else {
                        HandlerParams params2 = this.mPendingInstalls.get(0);
                        if (params2 != null) {
                            Trace.asyncTraceEnd(262144L, "queueInstall", System.identityHashCode(params2));
                            Trace.traceBegin(262144L, "startCopy");
                            if (params2.startCopy()) {
                                if (this.mPendingInstalls.size() > 0) {
                                    this.mPendingInstalls.remove(0);
                                }
                                if (this.mPendingInstalls.size() != 0) {
                                    PackageManagerService.this.mHandler.sendEmptyMessage(3);
                                } else if (this.mBound) {
                                    removeMessages(6);
                                    Message ubmsg = obtainMessage(6);
                                    sendMessageDelayed(ubmsg, 10000L);
                                }
                            }
                            Trace.traceEnd(262144L);
                            return;
                        }
                        return;
                    }
                case 5:
                    HandlerParams params3 = (HandlerParams) msg.obj;
                    int idx = this.mPendingInstalls.size();
                    if (PackageManagerService.DEBUG_INSTALL) {
                        Slog.i(PackageManagerService.TAG, "init_copy idx=" + idx + ": " + params3);
                    }
                    if (this.mBound) {
                        this.mPendingInstalls.add(idx, params3);
                        if (idx == 0) {
                            PackageManagerService.this.mHandler.sendEmptyMessage(3);
                            return;
                        }
                        return;
                    }
                    Trace.asyncTraceBegin(262144L, "bindingMCS", System.identityHashCode(PackageManagerService.this.mHandler));
                    if (connectToService()) {
                        this.mPendingInstalls.add(idx, params3);
                        return;
                    }
                    Slog.e(PackageManagerService.TAG, "Failed to bind to media container service");
                    params3.serviceError();
                    Trace.asyncTraceEnd(262144L, "bindingMCS", System.identityHashCode(PackageManagerService.this.mHandler));
                    if (params3.traceMethod != null) {
                        Trace.asyncTraceEnd(262144L, params3.traceMethod, params3.traceCookie);
                        return;
                    }
                    return;
                case 6:
                    if (PackageManagerService.DEBUG_INSTALL) {
                        Slog.i(PackageManagerService.TAG, "mcs_unbind");
                    }
                    if (this.mPendingInstalls.size() != 0 || PackageManagerService.this.mPendingVerification.size() != 0) {
                        if (this.mPendingInstalls.size() > 0) {
                            PackageManagerService.this.mHandler.sendEmptyMessage(3);
                            return;
                        }
                        return;
                    } else if (this.mBound) {
                        if (PackageManagerService.DEBUG_INSTALL) {
                            Slog.i(PackageManagerService.TAG, "calling disconnectService()");
                        }
                        disconnectService();
                        return;
                    } else {
                        return;
                    }
                case 7:
                    Process.setThreadPriority(0);
                    String packageName = (String) msg.obj;
                    int userId = msg.arg1;
                    boolean andCode = msg.arg2 != 0;
                    synchronized (PackageManagerService.this.mPackages) {
                        try {
                            if (userId == -1) {
                                int[] users = PackageManagerService.sUserManager.getUserIds();
                                int length = users.length;
                                while (i < length) {
                                    PackageManagerService.this.mSettings.addPackageToCleanLPw(new PackageCleanItem(users[i], packageName, andCode));
                                    i++;
                                }
                            } else {
                                PackageManagerService.this.mSettings.addPackageToCleanLPw(new PackageCleanItem(userId, packageName, andCode));
                            }
                        } finally {
                        }
                    }
                    Process.setThreadPriority(10);
                    PackageManagerService.this.startCleaningPackages();
                    return;
                case 9:
                    if (PackageManagerService.DEBUG_INSTALL) {
                        Log.v(PackageManagerService.TAG, "Handling post-install for " + msg.arg1);
                    }
                    PostInstallData data = PackageManagerService.this.mRunningInstalls.get(msg.arg1);
                    boolean didRestore = msg.arg2 != 0;
                    PackageManagerService.this.mRunningInstalls.delete(msg.arg1);
                    if (data != null) {
                        InstallArgs args = data.args;
                        PackageInstalledInfo parentRes = data.res;
                        boolean grantPermissions = (args.installFlags & 256) != 0;
                        boolean killApp = (args.installFlags & 4096) == 0;
                        boolean virtualPreload = (args.installFlags & 65536) != 0;
                        String[] grantedPermissions = args.installGrantPermissions;
                        PackageManagerService.this.handlePackagePostInstall(parentRes, grantPermissions, killApp, virtualPreload, grantedPermissions, didRestore, args.installerPackageName, args.observer);
                        int childCount = parentRes.addedChildPackages != null ? parentRes.addedChildPackages.size() : 0;
                        while (true) {
                            int i6 = i;
                            if (i6 < childCount) {
                                PackageInstalledInfo childRes = parentRes.addedChildPackages.valueAt(i6);
                                PackageManagerService.this.handlePackagePostInstall(childRes, grantPermissions, killApp, virtualPreload, grantedPermissions, false, args.installerPackageName, args.observer);
                                i = i6 + 1;
                            } else if (args.traceMethod != null) {
                                Trace.asyncTraceEnd(262144L, args.traceMethod, args.traceCookie);
                            }
                        }
                    } else {
                        Slog.e(PackageManagerService.TAG, "Bogus post-install token " + msg.arg1);
                    }
                    Trace.asyncTraceEnd(262144L, "postInstall", msg.arg1);
                    return;
                case 10:
                    if (PackageManagerService.DEBUG_INSTALL) {
                        Slog.i(PackageManagerService.TAG, "mcs_reconnect");
                    }
                    if (this.mPendingInstalls.size() > 0) {
                        if (this.mBound) {
                            disconnectService();
                        }
                        if (connectToService()) {
                            return;
                        }
                        Slog.e(PackageManagerService.TAG, "Failed to bind to media container service");
                        Iterator<HandlerParams> it3 = this.mPendingInstalls.iterator();
                        while (it3.hasNext()) {
                            HandlerParams params4 = it3.next();
                            params4.serviceError();
                            Trace.asyncTraceEnd(262144L, "queueInstall", System.identityHashCode(params4));
                        }
                        this.mPendingInstalls.clear();
                        return;
                    }
                    return;
                case 11:
                    if (PackageManagerService.DEBUG_INSTALL) {
                        Slog.i(PackageManagerService.TAG, "mcs_giveup too many retries");
                    }
                    Trace.asyncTraceEnd(262144L, "queueInstall", System.identityHashCode(this.mPendingInstalls.remove(0)));
                    return;
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
                        Iterator it4 = PackageManagerService.this.mDirtyUsers.iterator();
                        while (it4.hasNext()) {
                            PackageManagerService.this.mSettings.writePackageRestrictionsLPr(((Integer) it4.next()).intValue());
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
                        InstallArgs args2 = state.getInstallArgs();
                        Uri originUri = Uri.fromFile(args2.origin.resolvedFile);
                        if (state.isInstallAllowed()) {
                            ret = RequestStatus.SYS_ETIMEDOUT;
                            PackageManagerService.this.broadcastPackageVerified(verificationId, originUri, response.code, state.getInstallArgs().getUser());
                            try {
                                ret = args2.copyApk(PackageManagerService.this.mContainerService, true);
                            } catch (RemoteException e) {
                                Slog.e(PackageManagerService.TAG, "Could not contact the ContainerService");
                            }
                        } else {
                            ret = -22;
                        }
                        Trace.asyncTraceEnd(262144L, "verification", verificationId);
                        PackageManagerService.this.processPendingInstall(args2, ret);
                        PackageManagerService.this.mHandler.sendEmptyMessage(6);
                        return;
                    }
                    return;
                case 16:
                    int verificationId2 = msg.arg1;
                    PackageVerificationState state2 = PackageManagerService.this.mPendingVerification.get(verificationId2);
                    if (state2 == null || state2.timeoutExtended()) {
                        return;
                    }
                    InstallArgs args3 = state2.getInstallArgs();
                    Uri originUri2 = Uri.fromFile(args3.origin.resolvedFile);
                    Slog.i(PackageManagerService.TAG, "Verification timed out for " + originUri2);
                    PackageManagerService.this.mPendingVerification.remove(verificationId2);
                    int ret2 = -22;
                    UserHandle user = args3.getUser();
                    if (PackageManagerService.this.getDefaultVerificationResponse(user) == 1) {
                        Slog.i(PackageManagerService.TAG, "Continuing with installation of " + originUri2);
                        state2.setVerifierResponse(Binder.getCallingUid(), 2);
                        PackageManagerService.this.broadcastPackageVerified(verificationId2, originUri2, 1, user);
                        try {
                            ret2 = args3.copyApk(PackageManagerService.this.mContainerService, true);
                        } catch (RemoteException e2) {
                            Slog.e(PackageManagerService.TAG, "Could not contact the ContainerService");
                        }
                    } else {
                        PackageManagerService.this.broadcastPackageVerified(verificationId2, originUri2, -1, user);
                    }
                    Trace.asyncTraceEnd(262144L, "verification", verificationId2);
                    PackageManagerService.this.processPendingInstall(args3, ret2);
                    PackageManagerService.this.mHandler.sendEmptyMessage(6);
                    return;
                case 17:
                    IFVerificationParams params5 = (IFVerificationParams) msg.obj;
                    PackageManagerService.this.verifyIntentFiltersIfNeeded(params5.userId, params5.verifierUid, params5.replacing, params5.pkg);
                    return;
                case 18:
                    int verificationId3 = msg.arg1;
                    IntentFilterVerificationState state3 = PackageManagerService.this.mIntentFilterVerificationStates.get(verificationId3);
                    if (state3 == null) {
                        Slog.w(PackageManagerService.TAG, "Invalid IntentFilter verification token " + verificationId3 + " received");
                        return;
                    }
                    int userId2 = state3.getUserId();
                    if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                        Slog.d(PackageManagerService.TAG, "Processing IntentFilter verification with token:" + verificationId3 + " and userId:" + userId2);
                    }
                    IntentFilterVerificationResponse response2 = (IntentFilterVerificationResponse) msg.obj;
                    state3.setVerifierResponse(response2.callerUid, response2.code);
                    if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                        Slog.d(PackageManagerService.TAG, "IntentFilter verification with token:" + verificationId3 + " and userId:" + userId2 + " is settings verifier response with response code:" + response2.code);
                    }
                    if (response2.code == -1 && PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                        Slog.d(PackageManagerService.TAG, "Domains failing verification: " + response2.getFailedDomainsString());
                    }
                    if (state3.isVerificationComplete()) {
                        PackageManagerService.this.mIntentFilterVerifier.receiveVerificationResponse(verificationId3);
                        return;
                    } else if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                        Slog.d(PackageManagerService.TAG, "IntentFilter verification with token:" + verificationId3 + " was not said to be complete");
                        return;
                    } else {
                        return;
                    }
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
                    if (this.mBound) {
                        return;
                    }
                    Trace.asyncTraceBegin(262144L, "earlyBindingMCS", System.identityHashCode(PackageManagerService.this.mHandler));
                    if (!connectToService()) {
                        Slog.e(PackageManagerService.TAG, "Failed to bind to media container service");
                    }
                    Trace.asyncTraceEnd(262144L, "earlyBindingMCS", System.identityHashCode(PackageManagerService.this.mHandler));
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackagePostInstall(PackageInstalledInfo res, boolean grantPermissions, boolean killApp, boolean virtualPreload, String[] grantedPermissions, boolean launchedForRestore, String installerPackage, IPackageInstallObserver2 installObserver) {
        int[] firstUserIds;
        String packageName;
        int appId;
        int[] firstInstantUserIds;
        int[] iArr;
        PackageSetting ps;
        String installerPackageName;
        if (res.returnCode == 1) {
            if (res.removedInfo != null) {
                res.removedInfo.sendPackageRemovedBroadcasts(killApp);
            }
            if (grantPermissions) {
                int callingUid = Binder.getCallingUid();
                this.mPermissionManager.grantRequestedRuntimePermissions(res.pkg, res.newUsers, grantedPermissions, callingUid, this.mPermissionCallback);
            }
            boolean update = (res.removedInfo == null || res.removedInfo.removedPackage == null) ? false : true;
            String installerPackageName2 = res.installerPackageName != null ? res.installerPackageName : res.removedInfo != null ? res.removedInfo.installerPackageName : null;
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
            int[] firstInstantUserIds2 = EMPTY_INT_ARRAY;
            int[] updateUserIds = EMPTY_INT_ARRAY;
            int[] instantUserIds = EMPTY_INT_ARRAY;
            boolean allNewUsers = res.origUsers == null || res.origUsers.length == 0;
            PackageSetting ps2 = (PackageSetting) res.pkg.mExtras;
            int[] iArr2 = res.newUsers;
            int length = iArr2.length;
            int[] instantUserIds2 = instantUserIds;
            int[] firstInstantUserIds3 = firstInstantUserIds2;
            int[] firstInstantUserIds4 = updateUserIds;
            int[] firstUserIds3 = firstUserIds2;
            int i = 0;
            while (i < length) {
                int newUser = iArr2[i];
                boolean isInstantApp = ps2.getInstantApp(newUser);
                if (allNewUsers) {
                    if (isInstantApp) {
                        firstInstantUserIds3 = ArrayUtils.appendInt(firstInstantUserIds3, newUser);
                    } else {
                        firstUserIds3 = ArrayUtils.appendInt(firstUserIds3, newUser);
                    }
                    iArr = iArr2;
                    ps = ps2;
                    installerPackageName = installerPackageName2;
                } else {
                    boolean isNew = true;
                    iArr = iArr2;
                    int[] iArr3 = res.origUsers;
                    ps = ps2;
                    int length2 = iArr3.length;
                    installerPackageName = installerPackageName2;
                    int i2 = 0;
                    while (true) {
                        if (i2 >= length2) {
                            break;
                        }
                        int i3 = length2;
                        int origUser = iArr3[i2];
                        if (origUser == newUser) {
                            isNew = false;
                            break;
                        } else {
                            i2++;
                            length2 = i3;
                        }
                    }
                    if (isNew) {
                        if (isInstantApp) {
                            firstInstantUserIds3 = ArrayUtils.appendInt(firstInstantUserIds3, newUser);
                        } else {
                            firstUserIds3 = ArrayUtils.appendInt(firstUserIds3, newUser);
                        }
                    } else if (isInstantApp) {
                        instantUserIds2 = ArrayUtils.appendInt(instantUserIds2, newUser);
                    } else {
                        firstInstantUserIds4 = ArrayUtils.appendInt(firstInstantUserIds4, newUser);
                    }
                }
                i++;
                iArr2 = iArr;
                ps2 = ps;
                installerPackageName2 = installerPackageName;
            }
            String installerPackageName3 = installerPackageName2;
            if (res.pkg.staticSharedLibName == null) {
                this.mProcessLoggingHandler.invalidateProcessLoggingBaseApkHash(res.pkg.baseCodePath);
                int appId2 = UserHandle.getAppId(res.uid);
                boolean isSystem = res.pkg.applicationInfo.isSystemApp();
                int[] updateUserIds2 = firstInstantUserIds4;
                int[] firstUserIds4 = firstUserIds3;
                int[] firstInstantUserIds5 = firstInstantUserIds3;
                sendPackageAddedForNewUsers(packageName2, isSystem || virtualPreload, virtualPreload, appId2, firstUserIds4, firstInstantUserIds5);
                Bundle extras = new Bundle(1);
                extras.putInt("android.intent.extra.UID", res.uid);
                if (update) {
                    extras.putBoolean("android.intent.extra.REPLACING", true);
                }
                firstUserIds = firstUserIds4;
                appId = 0;
                int[] firstUserIds5 = instantUserIds2;
                sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName2, extras, 0, null, null, updateUserIds2, firstUserIds5);
                if (installerPackageName3 != null) {
                    sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName2, extras, 0, installerPackageName3, null, updateUserIds2, instantUserIds2);
                }
                boolean notifyVerifier = (this.mRequiredVerifierPackage == null || this.mRequiredVerifierPackage.equals(installerPackageName3)) ? false : true;
                if (notifyVerifier) {
                    sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", packageName2, extras, 0, this.mRequiredVerifierPackage, null, updateUserIds2, instantUserIds2);
                }
                if (update) {
                    sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", packageName2, extras, 0, null, null, updateUserIds2, instantUserIds2);
                    if (installerPackageName3 != null) {
                        sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", packageName2, extras, 0, installerPackageName3, null, updateUserIds2, instantUserIds2);
                    }
                    if (notifyVerifier) {
                        sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", packageName2, extras, 0, this.mRequiredVerifierPackage, null, updateUserIds2, instantUserIds2);
                    }
                    sendPackageBroadcast("android.intent.action.MY_PACKAGE_REPLACED", null, null, 0, packageName2, null, updateUserIds2, instantUserIds2);
                    packageName = packageName2;
                    firstInstantUserIds = firstInstantUserIds5;
                } else if (!launchedForRestore || isSystemApp(res.pkg)) {
                    packageName = packageName2;
                    firstInstantUserIds = firstInstantUserIds5;
                } else {
                    if (DEBUG_BACKUP) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Post-restore of ");
                        packageName = packageName2;
                        sb.append(packageName);
                        sb.append(" sending FIRST_LAUNCH in ");
                        sb.append(Arrays.toString(firstUserIds));
                        Slog.i(TAG, sb.toString());
                    } else {
                        packageName = packageName2;
                    }
                    firstInstantUserIds = firstInstantUserIds5;
                    sendFirstLaunchBroadcast(packageName, installerPackage, firstUserIds, firstInstantUserIds);
                }
                if (res.pkg.isForwardLocked() || isExternal(res.pkg)) {
                    if (DEBUG_INSTALL) {
                        Slog.i(TAG, "upgrading pkg " + res.pkg + " is ASEC-hosted -> AVAILABLE");
                    }
                    int[] uidArray = {res.pkg.applicationInfo.uid};
                    ArrayList<String> pkgList = new ArrayList<>(1);
                    pkgList.add(packageName);
                    sendResourcesChangedBroadcast(true, true, pkgList, uidArray, (IIntentReceiver) null);
                }
            } else {
                firstUserIds = firstUserIds3;
                packageName = packageName2;
                appId = 0;
            }
            if (firstUserIds != null && firstUserIds.length > 0) {
                synchronized (this.mPackages) {
                    int length3 = firstUserIds.length;
                    for (int i4 = appId; i4 < length3; i4++) {
                        int userId = firstUserIds[i4];
                        if (packageIsBrowser(packageName, userId)) {
                            this.mSettings.setDefaultBrowserPackageNameLPw(null, userId);
                        }
                        this.mSettings.applyPendingPermissionGrantsLPw(packageName, userId);
                    }
                }
            }
            if (allNewUsers && !update) {
                notifyPackageAdded(packageName);
            }
            EventLog.writeEvent((int) EventLogTags.UNKNOWN_SOURCES_ENABLED, getUnknownSourcesSettings());
            if (res.removedInfo == null || res.removedInfo.args == null) {
                VMRuntime.getRuntime().requestConcurrentGC();
            } else {
                Runtime.getRuntime().gc();
                synchronized (this.mInstallLock) {
                    res.removedInfo.args.doPostDeleteLI(true);
                }
            }
            int length4 = firstUserIds.length;
            for (int i5 = appId; i5 < length4; i5++) {
                int userId2 = firstUserIds[i5];
                PackageInfo info = getPackageInfo(packageName, appId, userId2);
                if (info != null) {
                    this.mDexManager.notifyPackageInstalled(info, userId2);
                }
            }
        }
        if (installObserver != null) {
            try {
                installObserver.onPackageInstalled(res.name, res.returnCode, res.returnMsg, extrasForInstallResult(res));
            } catch (RemoteException e) {
                Slog.i(TAG, "Observer no longer exists.");
            }
        }
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
            this.mHandler.sendEmptyMessageDelayed(13, 10000L);
        }
    }

    void scheduleWritePackageListLocked(int userId) {
        if (!this.mHandler.hasMessages(19)) {
            Message msg = this.mHandler.obtainMessage(19);
            msg.arg1 = userId;
            this.mHandler.sendMessageDelayed(msg, 10000L);
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
                this.mHandler.sendEmptyMessageDelayed(14, 10000L);
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
        ServiceManager.addService("package_native", new PackageManagerNative());
        return packageManagerService;
    }

    private void enableSystemUserPackages() {
        boolean install;
        if (!UserManager.isSplitSystemUser()) {
            return;
        }
        AppsQueryHelper queryHelper = new AppsQueryHelper(this);
        Set<String> enableApps = new ArraySet<>();
        enableApps.addAll(queryHelper.queryApps(AppsQueryHelper.GET_NON_LAUNCHABLE_APPS | AppsQueryHelper.GET_APPS_WITH_INTERACT_ACROSS_USERS_PERM | AppsQueryHelper.GET_IMES, true, UserHandle.SYSTEM));
        ArraySet<String> wlApps = SystemConfig.getInstance().getSystemUserWhitelistedApps();
        enableApps.addAll(wlApps);
        enableApps.addAll(queryHelper.queryApps(AppsQueryHelper.GET_REQUIRED_FOR_SYSTEM_USER, false, UserHandle.SYSTEM));
        ArraySet<String> blApps = SystemConfig.getInstance().getSystemUserBlacklistedApps();
        enableApps.removeAll(blApps);
        Log.i(TAG, "Applications installed for system user: " + enableApps);
        List<String> allAps = queryHelper.queryApps(0, false, UserHandle.SYSTEM);
        int allAppsSize = allAps.size();
        synchronized (this.mPackages) {
            for (int i = 0; i < allAppsSize; i++) {
                try {
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
                } catch (Throwable th) {
                    throw th;
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

    public PackageManagerService(Context context, Installer installer, boolean factoryTest, boolean onlyCore) {
        ArrayMap<String, PackageParser.Package> arrayMap;
        File privilegedVendorAppDir;
        File vendorAppDir;
        File privilegedOdmAppDir;
        File odmAppDir;
        File privilegedProductAppDir;
        File productAppDir;
        long j;
        String str;
        int i;
        File privilegedAppDir;
        File vendorAppDir2;
        File privilegedOdmAppDir2;
        int scanFlags;
        int i2;
        File vendorAppDir3;
        File privilegedOdmAppDir3;
        File odmAppDir2;
        File oemAppDir;
        File privilegedProductAppDir2;
        File productAppDir2;
        int rescanFlags;
        int reparseFlags;
        int reparseFlags2;
        int rescanFlags2;
        int reparseFlags3;
        int rescanFlags3;
        String msg;
        this.mBootProfDisable = false;
        this.mDeferProtectedFilters = true;
        LockGuard.installLock(this.mPackages, 3);
        Trace.traceBegin(262144L, "create package manager");
        EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_PMS_START, SystemClock.uptimeMillis());
        this.mBootProfDisable = PACKAGE_PARSER_CACHE_VERSION.equals(SystemProperties.get("ro.bootprof.disable", "0"));
        addBootEvent("PMS: boot_progress_pms_start");
        if (this.mSdkVersion <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }
        this.mContext = context;
        this.mFactoryTest = factoryTest;
        this.mOnlyCore = onlyCore;
        this.mMetrics = new DisplayMetrics();
        this.mInstaller = installer;
        synchronized (this.mInstallLock) {
            try {
                synchronized (this.mPackages) {
                    try {
                        LocalServices.addService(PackageManagerInternal.class, new PackageManagerInternalImpl());
                        sUserManager = new UserManagerService(context, this, new UserDataPreparer(this.mInstaller, this.mInstallLock, this.mContext, this.mOnlyCore), this.mPackages);
                        this.mPermissionManager = PermissionManagerService.create(context, new DefaultPermissionGrantPolicy.DefaultPermissionGrantedCallback() { // from class: com.android.server.pm.PackageManagerService.4
                            @Override // com.android.server.pm.permission.DefaultPermissionGrantPolicy.DefaultPermissionGrantedCallback
                            public void onDefaultRuntimePermissionsGranted(int userId) {
                                synchronized (PackageManagerService.this.mPackages) {
                                    PackageManagerService.this.mSettings.onDefaultRuntimePermissionsGrantedLPr(userId);
                                }
                            }
                        }, this.mPackages);
                        this.mDefaultPermissionPolicy = this.mPermissionManager.getDefaultPermissionGrantPolicy();
                        this.mSettings = new Settings(this.mPermissionManager.getPermissionSettings(), this.mPackages);
                    }
                }
                this.mSettings.addSharedUserLPw("android.uid.system", 1000, 1, 8);
                this.mSettings.addSharedUserLPw("android.uid.phone", 1001, 1, 8);
                this.mSettings.addSharedUserLPw("android.uid.log", LOG_UID, 1, 8);
                this.mSettings.addSharedUserLPw("android.uid.nfc", 1027, 1, 8);
                this.mSettings.addSharedUserLPw("android.uid.bluetooth", BLUETOOTH_UID, 1, 8);
                this.mSettings.addSharedUserLPw("android.uid.shell", SHELL_UID, 1, 8);
                this.mSettings.addSharedUserLPw("android.uid.se", SE_UID, 1, 8);
                String separateProcesses = SystemProperties.get("debug.separate_processes");
                if (separateProcesses == null || separateProcesses.length() <= 0) {
                    this.mDefParseFlags = 0;
                    this.mSeparateProcesses = null;
                } else if ("*".equals(separateProcesses)) {
                    this.mDefParseFlags = 2;
                    this.mSeparateProcesses = null;
                    Slog.w(TAG, "Running with debug.separate_processes: * (ALL)");
                } else {
                    this.mDefParseFlags = 0;
                    this.mSeparateProcesses = separateProcesses.split(",");
                    Slog.w(TAG, "Running with debug.separate_processes: " + separateProcesses);
                }
                this.mPackageDexOptimizer = new PackageDexOptimizer(installer, this.mInstallLock, context, "*dexopt*");
                DexManager.Listener dexManagerListener = DexLogger.getListener(this, installer, this.mInstallLock);
                this.mDexManager = new DexManager(this.mContext, this, this.mPackageDexOptimizer, installer, this.mInstallLock, dexManagerListener);
                this.mArtManagerService = new ArtManagerService(this.mContext, this, installer, this.mInstallLock);
                this.mMoveCallbacks = new MoveCallbacks(FgThread.get().getLooper());
                this.mOnPermissionChangeListeners = new OnPermissionChangeListeners(FgThread.get().getLooper());
                getDefaultDisplayMetrics(context, this.mMetrics);
                Trace.traceBegin(262144L, "get system config");
                SystemConfig systemConfig = SystemConfig.getInstance();
                this.mAvailableFeatures = systemConfig.getAvailableFeatures();
                Trace.traceEnd(262144L);
                this.mProtectedPackages = new ProtectedPackages(this.mContext);
                Object obj = this.mInstallLock;
                synchronized (obj) {
                    try {
                        try {
                            ArrayMap<String, PackageParser.Package> arrayMap2 = this.mPackages;
                            try {
                                synchronized (arrayMap2) {
                                    try {
                                        this.mHandlerThread = new ServiceThread(TAG, 10, true);
                                        this.mHandlerThread.start();
                                        this.mHandler = new PackageHandler(this.mHandlerThread.getLooper());
                                        this.mProcessLoggingHandler = new ProcessLoggingHandler();
                                        Watchdog.getInstance().addThread(this.mHandler, 600000L);
                                        this.mInstantAppRegistry = new InstantAppRegistry(this);
                                        ArrayMap<String, String> libConfig = systemConfig.getSharedLibraries();
                                        int builtInLibCount = libConfig.size();
                                        int i3 = 0;
                                        while (i3 < builtInLibCount) {
                                            String name = libConfig.keyAt(i3);
                                            String path = libConfig.valueAt(i3);
                                            int builtInLibCount2 = builtInLibCount;
                                            ArrayMap<String, String> libConfig2 = libConfig;
                                            Object obj2 = obj;
                                            arrayMap = arrayMap2;
                                            SystemConfig systemConfig2 = systemConfig;
                                            String separateProcesses2 = separateProcesses;
                                            try {
                                                addSharedLibraryLPw(path, null, name, -1L, 0, PLATFORM_PACKAGE_NAME, 0L);
                                                i3++;
                                                obj = obj2;
                                                separateProcesses = separateProcesses2;
                                                systemConfig = systemConfig2;
                                                builtInLibCount = builtInLibCount2;
                                                libConfig = libConfig2;
                                                arrayMap2 = arrayMap;
                                            } catch (Throwable th) {
                                                th = th;
                                                throw th;
                                            }
                                        }
                                        Object obj3 = obj;
                                        arrayMap = arrayMap2;
                                        SELinuxMMAC.readInstallPolicy();
                                        Trace.traceBegin(262144L, "loadFallbacks");
                                        FallbackCategoryProvider.loadFallbacks();
                                        Trace.traceEnd(262144L);
                                        Trace.traceBegin(262144L, "read user settings");
                                        this.mFirstBoot = !this.mSettings.readLPw(sUserManager.getUsers(false));
                                        Trace.traceEnd(262144L);
                                        int packageSettingCount = this.mSettings.mPackages.size();
                                        for (int i4 = packageSettingCount - 1; i4 >= 0; i4--) {
                                            PackageSetting ps = this.mSettings.mPackages.valueAt(i4);
                                            if (!isExternal(ps) && ((ps.codePath == null || !ps.codePath.exists()) && this.mSettings.getDisabledSystemPkgLPr(ps.name) != null)) {
                                                this.mSettings.mPackages.removeAt(i4);
                                                this.mSettings.enableSystemPackageLPw(ps.name);
                                            }
                                        }
                                        if (this.mFirstBoot) {
                                            requestCopyPreoptedFiles();
                                        }
                                        String customResolverActivity = Resources.getSystem().getString(17039646);
                                        if (TextUtils.isEmpty(customResolverActivity)) {
                                            customResolverActivity = null;
                                        } else {
                                            this.mCustomResolverComponentName = ComponentName.unflattenFromString(customResolverActivity);
                                        }
                                        long startTime = SystemClock.uptimeMillis();
                                        EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_PMS_SYSTEM_SCAN_START, startTime);
                                        addBootEvent("PMS: boot_progress_pms_system_scan_start");
                                        String bootClassPath = System.getenv("BOOTCLASSPATH");
                                        String systemServerClassPath = System.getenv("SYSTEMSERVERCLASSPATH");
                                        if (bootClassPath == null) {
                                            Slog.w(TAG, "No BOOTCLASSPATH found!");
                                        }
                                        if (systemServerClassPath == null) {
                                            Slog.w(TAG, "No SYSTEMSERVERCLASSPATH found!");
                                        }
                                        File frameworkDir = new File(Environment.getRootDirectory(), "framework");
                                        Settings.VersionInfo ver = this.mSettings.getInternalVersion();
                                        this.mIsUpgrade = !Build.FINGERPRINT.equals(ver.fingerprint);
                                        if (this.mIsUpgrade) {
                                            PackageManagerServiceUtils.logCriticalInfo(4, "Upgrading from " + ver.fingerprint + " to " + Build.FINGERPRINT);
                                        }
                                        this.mPromoteSystemApps = this.mIsUpgrade && ver.sdkVersion <= 22;
                                        this.mIsPreNUpgrade = this.mIsUpgrade && ver.sdkVersion < 24;
                                        this.mIsPreNMR1Upgrade = this.mIsUpgrade && ver.sdkVersion < 25;
                                        if (this.mPromoteSystemApps) {
                                            for (PackageSetting ps2 : this.mSettings.mPackages.values()) {
                                                if (isSystemApp(ps2)) {
                                                    this.mExistingSystemPackages.add(ps2.name);
                                                }
                                            }
                                        }
                                        this.mCacheDir = preparePackageParserCache(this.mIsUpgrade);
                                        int scanFlags2 = 528;
                                        int scanFlags3 = (this.mIsUpgrade || this.mFirstBoot) ? 528 | 8192 : scanFlags2;
                                        scanDirTracedLI(new File(VENDOR_OVERLAY_DIR), this.mDefParseFlags | 16, scanFlags3 | 131072 | 1048576, 0L);
                                        scanDirTracedLI(new File(PRODUCT_OVERLAY_DIR), this.mDefParseFlags | 16, scanFlags3 | 131072 | 2097152, 0L);
                                        this.mParallelPackageParserCallback.findStaticOverlayPackages();
                                        try {
                                            scanDirTracedLI(frameworkDir, this.mDefParseFlags | 16, scanFlags3 | 1 | 131072 | 262144, 0L);
                                            File privilegedAppDir2 = new File(Environment.getRootDirectory(), "priv-app");
                                            int scanFlags4 = scanFlags3;
                                            int i5 = 1;
                                            scanDirTracedLI(privilegedAppDir2, this.mDefParseFlags | 16, scanFlags3 | 131072 | 262144, 0L);
                                            File systemAppDir = new File(Environment.getRootDirectory(), "app");
                                            scanDirTracedLI(systemAppDir, this.mDefParseFlags | 16, scanFlags4 | 131072, 0L);
                                            File privilegedVendorAppDir2 = new File(Environment.getVendorDirectory(), "priv-app");
                                            try {
                                                privilegedVendorAppDir = privilegedVendorAppDir2.getCanonicalFile();
                                            } catch (IOException e) {
                                                privilegedVendorAppDir = privilegedVendorAppDir2;
                                            }
                                            scanDirTracedLI(privilegedVendorAppDir, this.mDefParseFlags | 16, scanFlags4 | 131072 | 1048576 | 262144, 0L);
                                            File vendorAppDir4 = new File(Environment.getVendorDirectory(), "app");
                                            try {
                                                vendorAppDir = vendorAppDir4.getCanonicalFile();
                                            } catch (IOException e2) {
                                                vendorAppDir = vendorAppDir4;
                                            }
                                            File vendorAppDir5 = vendorAppDir;
                                            scanDirTracedLI(vendorAppDir, this.mDefParseFlags | 16, scanFlags4 | 131072 | 1048576, 0L);
                                            File privilegedOdmAppDir4 = new File(Environment.getOdmDirectory(), "priv-app");
                                            try {
                                                privilegedOdmAppDir = privilegedOdmAppDir4.getCanonicalFile();
                                            } catch (IOException e3) {
                                                privilegedOdmAppDir = privilegedOdmAppDir4;
                                            }
                                            File privilegedOdmAppDir5 = privilegedOdmAppDir;
                                            scanDirTracedLI(privilegedOdmAppDir, this.mDefParseFlags | 16, scanFlags4 | 131072 | 1048576 | 262144, 0L);
                                            File odmAppDir3 = new File(Environment.getOdmDirectory(), "app");
                                            try {
                                                odmAppDir = odmAppDir3.getCanonicalFile();
                                            } catch (IOException e4) {
                                                odmAppDir = odmAppDir3;
                                            }
                                            File odmAppDir4 = odmAppDir;
                                            scanDirTracedLI(odmAppDir, this.mDefParseFlags | 16, scanFlags4 | 131072 | 1048576, 0L);
                                            File oemAppDir2 = new File(Environment.getOemDirectory(), "app");
                                            File oemAppDir3 = oemAppDir2;
                                            scanDirTracedLI(oemAppDir2, this.mDefParseFlags | 16, scanFlags4 | 131072 | 524288, 0L);
                                            File privilegedProductAppDir3 = new File(Environment.getProductDirectory(), "priv-app");
                                            try {
                                                privilegedProductAppDir = privilegedProductAppDir3.getCanonicalFile();
                                            } catch (IOException e5) {
                                                privilegedProductAppDir = privilegedProductAppDir3;
                                            }
                                            File privilegedProductAppDir4 = privilegedProductAppDir;
                                            scanDirTracedLI(privilegedProductAppDir, this.mDefParseFlags | 16, scanFlags4 | 131072 | 2097152 | 262144, 0L);
                                            File productAppDir3 = new File(Environment.getProductDirectory(), "app");
                                            try {
                                                productAppDir = productAppDir3.getCanonicalFile();
                                            } catch (IOException e6) {
                                                productAppDir = productAppDir3;
                                            }
                                            File productAppDir4 = productAppDir;
                                            scanDirTracedLI(productAppDir, this.mDefParseFlags | 16, scanFlags4 | 131072 | 2097152, 0L);
                                            List<String> possiblyDeletedUpdatedSystemApps = new ArrayList<>();
                                            List<String> stubSystemApps = new ArrayList<>();
                                            if (!this.mOnlyCore) {
                                                for (PackageParser.Package pkg : this.mPackages.values()) {
                                                    if (pkg.isStub) {
                                                        stubSystemApps.add(pkg.packageName);
                                                    }
                                                }
                                                Iterator<PackageSetting> psit = this.mSettings.mPackages.values().iterator();
                                                while (psit.hasNext()) {
                                                    PackageSetting ps3 = psit.next();
                                                    if ((ps3.pkgFlags & i5) != 0) {
                                                        PackageParser.Package scannedPkg = this.mPackages.get(ps3.name);
                                                        if (scannedPkg == null) {
                                                            if (this.mSettings.isDisabledSystemPackageLPr(ps3.name)) {
                                                                PackageSetting disabledPs = this.mSettings.getDisabledSystemPkgLPr(ps3.name);
                                                                if (disabledPs.codePath == null || !disabledPs.codePath.exists() || disabledPs.pkg == null) {
                                                                    possiblyDeletedUpdatedSystemApps.add(ps3.name);
                                                                }
                                                            } else {
                                                                psit.remove();
                                                                PackageManagerServiceUtils.logCriticalInfo(5, "System package " + ps3.name + " no longer exists; it's data will be wiped");
                                                            }
                                                            i5 = 1;
                                                        } else if (this.mSettings.isDisabledSystemPackageLPr(ps3.name)) {
                                                            PackageManagerServiceUtils.logCriticalInfo(5, "Expecting better updated system app for " + ps3.name + "; removing system app.  Last known codePath=" + ps3.codePathString + ", versionCode=" + ps3.versionCode + "; scanned versionCode=" + scannedPkg.getLongVersionCode());
                                                            removePackageLI(scannedPkg, true);
                                                            this.mExpectingBetter.put(ps3.name, ps3.codePath);
                                                        }
                                                    }
                                                    i5 = 1;
                                                }
                                            }
                                            deleteTempPackageFiles();
                                            int cachedSystemApps = PackageParser.sCachedPackageReadCount.get();
                                            this.mSettings.pruneSharedUsersLPw();
                                            long systemScanTime = SystemClock.uptimeMillis() - startTime;
                                            int systemPackagesCount = this.mPackages.size();
                                            StringBuilder sb = new StringBuilder();
                                            sb.append("Finished scanning system apps. Time: ");
                                            sb.append(systemScanTime);
                                            sb.append(" ms, packageCount: ");
                                            sb.append(systemPackagesCount);
                                            sb.append(" , timePerPackage: ");
                                            if (systemPackagesCount == 0) {
                                                j = 0;
                                            } else {
                                                try {
                                                    j = systemScanTime / systemPackagesCount;
                                                } catch (Throwable th2) {
                                                    th = th2;
                                                    throw th;
                                                }
                                            }
                                            sb.append(j);
                                            sb.append(" , cached: ");
                                            sb.append(cachedSystemApps);
                                            Slog.i(TAG, sb.toString());
                                            if (this.mIsUpgrade && systemPackagesCount > 0) {
                                                MetricsLogger.histogram((Context) null, "ota_package_manager_system_app_avg_scan_time", ((int) systemScanTime) / systemPackagesCount);
                                            }
                                            if (!this.mOnlyCore) {
                                                EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_PMS_DATA_SCAN_START, SystemClock.uptimeMillis());
                                                addBootEvent("PMS: boot_progress_pms_data_scan_start");
                                                scanDirTracedLI(sAppInstallDir, 0, scanFlags4 | 128, 0L);
                                                scanDirTracedLI(sDrmAppPrivateInstallDir, this.mDefParseFlags | 4, scanFlags4 | 128, 0L);
                                                for (String deletedAppName : possiblyDeletedUpdatedSystemApps) {
                                                    PackageParser.Package deletedPkg = this.mPackages.get(deletedAppName);
                                                    this.mSettings.removeDisabledSystemPackageLPw(deletedAppName);
                                                    if (deletedPkg == null) {
                                                        msg = "Updated system package " + deletedAppName + " no longer exists; removing its data";
                                                    } else {
                                                        msg = "Updated system package + " + deletedAppName + " no longer exists; revoking system privileges";
                                                        PackageSetting deletedPs = this.mSettings.mPackages.get(deletedAppName);
                                                        deletedPkg.applicationInfo.flags &= -2;
                                                        deletedPs.pkgFlags &= -2;
                                                    }
                                                    PackageManagerServiceUtils.logCriticalInfo(5, msg);
                                                }
                                                int i6 = 0;
                                                while (true) {
                                                    int i7 = i6;
                                                    if (i7 >= this.mExpectingBetter.size()) {
                                                        break;
                                                    }
                                                    String packageName = this.mExpectingBetter.keyAt(i7);
                                                    if (this.mPackages.containsKey(packageName)) {
                                                        i = i7;
                                                        privilegedAppDir = privilegedAppDir2;
                                                        vendorAppDir2 = vendorAppDir5;
                                                        privilegedOdmAppDir2 = privilegedOdmAppDir5;
                                                        scanFlags = scanFlags4;
                                                    } else {
                                                        File scanFile = this.mExpectingBetter.valueAt(i7);
                                                        PackageManagerServiceUtils.logCriticalInfo(5, "Expected better " + packageName + " but never showed up; reverting to system");
                                                        try {
                                                            if (FileUtils.contains(privilegedAppDir2, scanFile)) {
                                                                reparseFlags3 = this.mDefParseFlags | 16;
                                                                rescanFlags3 = scanFlags4 | 131072 | 262144;
                                                            } else if (FileUtils.contains(systemAppDir, scanFile)) {
                                                                reparseFlags3 = this.mDefParseFlags | 16;
                                                                rescanFlags3 = scanFlags4 | 131072;
                                                            } else {
                                                                if (FileUtils.contains(privilegedVendorAppDir, scanFile)) {
                                                                    i2 = i7;
                                                                    vendorAppDir3 = vendorAppDir5;
                                                                    privilegedOdmAppDir3 = privilegedOdmAppDir5;
                                                                    odmAppDir2 = odmAppDir4;
                                                                    oemAppDir = oemAppDir3;
                                                                    privilegedProductAppDir2 = privilegedProductAppDir4;
                                                                    productAppDir2 = productAppDir4;
                                                                } else {
                                                                    privilegedOdmAppDir3 = privilegedOdmAppDir5;
                                                                    if (FileUtils.contains(privilegedOdmAppDir3, scanFile)) {
                                                                        i2 = i7;
                                                                        vendorAppDir3 = vendorAppDir5;
                                                                        odmAppDir2 = odmAppDir4;
                                                                        oemAppDir = oemAppDir3;
                                                                        privilegedProductAppDir2 = privilegedProductAppDir4;
                                                                        productAppDir2 = productAppDir4;
                                                                    } else {
                                                                        File vendorAppDir6 = vendorAppDir5;
                                                                        if (FileUtils.contains(vendorAppDir6, scanFile)) {
                                                                            vendorAppDir3 = vendorAppDir6;
                                                                            i2 = i7;
                                                                            odmAppDir2 = odmAppDir4;
                                                                            oemAppDir = oemAppDir3;
                                                                            privilegedProductAppDir2 = privilegedProductAppDir4;
                                                                            productAppDir2 = productAppDir4;
                                                                        } else {
                                                                            i2 = i7;
                                                                            File odmAppDir5 = odmAppDir4;
                                                                            if (FileUtils.contains(odmAppDir5, scanFile)) {
                                                                                vendorAppDir3 = vendorAppDir6;
                                                                                odmAppDir2 = odmAppDir5;
                                                                                oemAppDir = oemAppDir3;
                                                                                privilegedProductAppDir2 = privilegedProductAppDir4;
                                                                                productAppDir2 = productAppDir4;
                                                                            } else {
                                                                                odmAppDir2 = odmAppDir5;
                                                                                File odmAppDir6 = oemAppDir3;
                                                                                if (FileUtils.contains(odmAppDir6, scanFile)) {
                                                                                    reparseFlags3 = this.mDefParseFlags | 16;
                                                                                    rescanFlags2 = scanFlags4 | 131072 | 524288;
                                                                                    vendorAppDir3 = vendorAppDir6;
                                                                                    oemAppDir = odmAppDir6;
                                                                                    privilegedProductAppDir2 = privilegedProductAppDir4;
                                                                                    productAppDir2 = productAppDir4;
                                                                                    reparseFlags2 = reparseFlags3;
                                                                                    vendorAppDir2 = vendorAppDir3;
                                                                                    privilegedOdmAppDir2 = privilegedOdmAppDir3;
                                                                                    int rescanFlags4 = rescanFlags2;
                                                                                    this.mSettings.enableSystemPackageLPw(packageName);
                                                                                    productAppDir4 = productAppDir2;
                                                                                    i = i2;
                                                                                    odmAppDir4 = odmAppDir2;
                                                                                    oemAppDir3 = oemAppDir;
                                                                                    privilegedProductAppDir4 = privilegedProductAppDir2;
                                                                                    privilegedAppDir = privilegedAppDir2;
                                                                                    scanFlags = scanFlags4;
                                                                                    scanPackageTracedLI(scanFile, reparseFlags2, rescanFlags4, 0L, (UserHandle) null);
                                                                                } else {
                                                                                    oemAppDir = odmAppDir6;
                                                                                    File oemAppDir4 = privilegedProductAppDir4;
                                                                                    if (FileUtils.contains(oemAppDir4, scanFile)) {
                                                                                        reparseFlags3 = this.mDefParseFlags | 16;
                                                                                        rescanFlags2 = scanFlags4 | 131072 | 2097152 | 262144;
                                                                                        vendorAppDir3 = vendorAppDir6;
                                                                                        privilegedProductAppDir2 = oemAppDir4;
                                                                                        productAppDir2 = productAppDir4;
                                                                                        reparseFlags2 = reparseFlags3;
                                                                                        vendorAppDir2 = vendorAppDir3;
                                                                                        privilegedOdmAppDir2 = privilegedOdmAppDir3;
                                                                                        int rescanFlags42 = rescanFlags2;
                                                                                        this.mSettings.enableSystemPackageLPw(packageName);
                                                                                        productAppDir4 = productAppDir2;
                                                                                        i = i2;
                                                                                        odmAppDir4 = odmAppDir2;
                                                                                        oemAppDir3 = oemAppDir;
                                                                                        privilegedProductAppDir4 = privilegedProductAppDir2;
                                                                                        privilegedAppDir = privilegedAppDir2;
                                                                                        scanFlags = scanFlags4;
                                                                                        scanPackageTracedLI(scanFile, reparseFlags2, rescanFlags42, 0L, (UserHandle) null);
                                                                                    } else {
                                                                                        privilegedProductAppDir2 = oemAppDir4;
                                                                                        productAppDir2 = productAppDir4;
                                                                                        if (FileUtils.contains(productAppDir2, scanFile)) {
                                                                                            reparseFlags3 = this.mDefParseFlags | 16;
                                                                                            rescanFlags2 = scanFlags4 | 131072 | 2097152;
                                                                                            vendorAppDir3 = vendorAppDir6;
                                                                                            reparseFlags2 = reparseFlags3;
                                                                                            vendorAppDir2 = vendorAppDir3;
                                                                                            privilegedOdmAppDir2 = privilegedOdmAppDir3;
                                                                                            int rescanFlags422 = rescanFlags2;
                                                                                            this.mSettings.enableSystemPackageLPw(packageName);
                                                                                            productAppDir4 = productAppDir2;
                                                                                            i = i2;
                                                                                            odmAppDir4 = odmAppDir2;
                                                                                            oemAppDir3 = oemAppDir;
                                                                                            privilegedProductAppDir4 = privilegedProductAppDir2;
                                                                                            privilegedAppDir = privilegedAppDir2;
                                                                                            scanFlags = scanFlags4;
                                                                                            scanPackageTracedLI(scanFile, reparseFlags2, rescanFlags422, 0L, (UserHandle) null);
                                                                                        } else {
                                                                                            Slog.e(TAG, "Ignoring unexpected fallback path " + scanFile);
                                                                                            privilegedOdmAppDir2 = privilegedOdmAppDir3;
                                                                                            productAppDir4 = productAppDir2;
                                                                                            privilegedAppDir = privilegedAppDir2;
                                                                                            i = i2;
                                                                                            odmAppDir4 = odmAppDir2;
                                                                                            oemAppDir3 = oemAppDir;
                                                                                            privilegedProductAppDir4 = privilegedProductAppDir2;
                                                                                            vendorAppDir2 = vendorAppDir6;
                                                                                            scanFlags = scanFlags4;
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                        reparseFlags = this.mDefParseFlags | 16;
                                                                        rescanFlags = scanFlags4 | 131072 | 1048576;
                                                                        reparseFlags2 = reparseFlags;
                                                                        rescanFlags2 = rescanFlags;
                                                                        vendorAppDir2 = vendorAppDir3;
                                                                        privilegedOdmAppDir2 = privilegedOdmAppDir3;
                                                                        int rescanFlags4222 = rescanFlags2;
                                                                        this.mSettings.enableSystemPackageLPw(packageName);
                                                                        productAppDir4 = productAppDir2;
                                                                        i = i2;
                                                                        odmAppDir4 = odmAppDir2;
                                                                        oemAppDir3 = oemAppDir;
                                                                        privilegedProductAppDir4 = privilegedProductAppDir2;
                                                                        privilegedAppDir = privilegedAppDir2;
                                                                        scanFlags = scanFlags4;
                                                                        scanPackageTracedLI(scanFile, reparseFlags2, rescanFlags4222, 0L, (UserHandle) null);
                                                                    }
                                                                }
                                                                reparseFlags = this.mDefParseFlags | 16;
                                                                rescanFlags = scanFlags4 | 131072 | 1048576 | 262144;
                                                                reparseFlags2 = reparseFlags;
                                                                rescanFlags2 = rescanFlags;
                                                                vendorAppDir2 = vendorAppDir3;
                                                                privilegedOdmAppDir2 = privilegedOdmAppDir3;
                                                                int rescanFlags42222 = rescanFlags2;
                                                                this.mSettings.enableSystemPackageLPw(packageName);
                                                                productAppDir4 = productAppDir2;
                                                                i = i2;
                                                                odmAppDir4 = odmAppDir2;
                                                                oemAppDir3 = oemAppDir;
                                                                privilegedProductAppDir4 = privilegedProductAppDir2;
                                                                privilegedAppDir = privilegedAppDir2;
                                                                scanFlags = scanFlags4;
                                                                scanPackageTracedLI(scanFile, reparseFlags2, rescanFlags42222, 0L, (UserHandle) null);
                                                            }
                                                            scanPackageTracedLI(scanFile, reparseFlags2, rescanFlags42222, 0L, (UserHandle) null);
                                                        } catch (PackageManagerException e7) {
                                                            Slog.e(TAG, "Failed to parse original system package: " + e7.getMessage());
                                                        }
                                                        rescanFlags2 = rescanFlags3;
                                                        i2 = i7;
                                                        vendorAppDir3 = vendorAppDir5;
                                                        privilegedOdmAppDir3 = privilegedOdmAppDir5;
                                                        odmAppDir2 = odmAppDir4;
                                                        oemAppDir = oemAppDir3;
                                                        privilegedProductAppDir2 = privilegedProductAppDir4;
                                                        productAppDir2 = productAppDir4;
                                                        reparseFlags2 = reparseFlags3;
                                                        vendorAppDir2 = vendorAppDir3;
                                                        privilegedOdmAppDir2 = privilegedOdmAppDir3;
                                                        int rescanFlags422222 = rescanFlags2;
                                                        this.mSettings.enableSystemPackageLPw(packageName);
                                                        productAppDir4 = productAppDir2;
                                                        i = i2;
                                                        odmAppDir4 = odmAppDir2;
                                                        oemAppDir3 = oemAppDir;
                                                        privilegedProductAppDir4 = privilegedProductAppDir2;
                                                        privilegedAppDir = privilegedAppDir2;
                                                        scanFlags = scanFlags4;
                                                    }
                                                    i6 = i + 1;
                                                    scanFlags4 = scanFlags;
                                                    vendorAppDir5 = vendorAppDir2;
                                                    privilegedOdmAppDir5 = privilegedOdmAppDir2;
                                                    privilegedAppDir2 = privilegedAppDir;
                                                }
                                                decompressSystemApplications(stubSystemApps, scanFlags4);
                                                int cachedNonSystemApps = PackageParser.sCachedPackageReadCount.get() - cachedSystemApps;
                                                long dataScanTime = (SystemClock.uptimeMillis() - systemScanTime) - startTime;
                                                int dataPackagesCount = this.mPackages.size() - systemPackagesCount;
                                                StringBuilder sb2 = new StringBuilder();
                                                sb2.append("Finished scanning non-system apps. Time: ");
                                                sb2.append(dataScanTime);
                                                sb2.append(" ms, packageCount: ");
                                                sb2.append(dataPackagesCount);
                                                sb2.append(" , timePerPackage: ");
                                                sb2.append(dataPackagesCount == 0 ? 0L : dataScanTime / dataPackagesCount);
                                                sb2.append(" , cached: ");
                                                sb2.append(cachedNonSystemApps);
                                                Slog.i(TAG, sb2.toString());
                                                if (this.mIsUpgrade && dataPackagesCount > 0) {
                                                    MetricsLogger.histogram((Context) null, "ota_package_manager_data_app_avg_scan_time", ((int) dataScanTime) / dataPackagesCount);
                                                }
                                            }
                                            this.mExpectingBetter.clear();
                                            this.mStorageManagerPackage = getStorageManagerPackageName();
                                            this.mSetupWizardPackage = getSetupWizardPackageName();
                                            if (this.mProtectedFilters.size() > 0) {
                                                if (DEBUG_FILTERS && this.mSetupWizardPackage == null) {
                                                    Slog.i(TAG, "No setup wizard; All protected intents capped to priority 0");
                                                }
                                                for (PackageParser.ActivityIntentInfo filter : this.mProtectedFilters) {
                                                    if (!filter.activity.info.packageName.equals(this.mSetupWizardPackage)) {
                                                        if (DEBUG_FILTERS) {
                                                            Slog.i(TAG, "Protected action; cap priority to 0; package: " + filter.activity.info.packageName + " activity: " + filter.activity.className + " origPrio: " + filter.getPriority());
                                                        }
                                                        filter.setPriority(0);
                                                    } else if (DEBUG_FILTERS) {
                                                        Slog.i(TAG, "Found setup wizard; allow priority " + filter.getPriority() + "; package: " + filter.activity.info.packageName + " activity: " + filter.activity.className + " priority: " + filter.getPriority());
                                                    }
                                                }
                                            }
                                            this.mSystemTextClassifierPackage = getSystemTextClassifierPackageName();
                                            this.mDeferProtectedFilters = false;
                                            this.mProtectedFilters.clear();
                                            updateAllSharedLibrariesLPw(null);
                                            for (SharedUserSetting setting : this.mSettings.getAllSharedUsersLPw()) {
                                                List<String> changedAbiCodePath = adjustCpuAbisForSharedUserLPw(setting.packages, null);
                                                if (changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
                                                    int i8 = changedAbiCodePath.size() - 1;
                                                    while (true) {
                                                        int i9 = i8;
                                                        if (i9 >= 0) {
                                                            String codePathString = changedAbiCodePath.get(i9);
                                                            try {
                                                                this.mInstaller.rmdex(codePathString, InstructionSets.getDexCodeInstructionSet(InstructionSets.getPreferredInstructionSet()));
                                                            } catch (Installer.InstallerException e8) {
                                                            }
                                                            i8 = i9 - 1;
                                                        }
                                                    }
                                                }
                                                setting.fixSeInfoLocked();
                                            }
                                            this.mPackageUsage.read(this.mPackages);
                                            this.mCompilerStats.read();
                                            EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_PMS_SCAN_END, SystemClock.uptimeMillis());
                                            addBootEvent("PMS: boot_progress_pms_scan_end");
                                            Slog.i(TAG, "Time to scan packages: " + (((float) (SystemClock.uptimeMillis() - startTime)) / 1000.0f) + " seconds");
                                            boolean sdkUpdated = ver.sdkVersion != this.mSdkVersion;
                                            if (sdkUpdated) {
                                                Slog.i(TAG, "Platform changed from " + ver.sdkVersion + " to " + this.mSdkVersion + "; regranting permissions for internal storage");
                                            }
                                            this.mPermissionManager.updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, sdkUpdated, this.mPackages.values(), this.mPermissionCallback);
                                            ver.sdkVersion = this.mSdkVersion;
                                            if (!onlyCore && (this.mPromoteSystemApps || this.mFirstBoot)) {
                                                for (UserInfo user : sUserManager.getUsers(true)) {
                                                    this.mSettings.applyDefaultPreferredAppsLPw(this, user.id);
                                                    applyFactoryDefaultBrowserLPw(user.id);
                                                    primeDomainVerificationsLPw(user.id);
                                                }
                                            }
                                            final int storageFlags = StorageManager.isFileEncryptedNativeOrEmulated() ? 1 : 3;
                                            final List<String> deferPackages = reconcileAppsDataLI(StorageManager.UUID_PRIVATE_INTERNAL, 0, storageFlags, true, true);
                                            this.mPrepareAppDataFuture = SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$sJ5w9GfSftnZPyv5hBDxQkxDJMU
                                                @Override // java.lang.Runnable
                                                public final void run() {
                                                    PackageManagerService.lambda$new$0(PackageManagerService.this, deferPackages, storageFlags);
                                                }
                                            }, "prepareAppData");
                                            if (this.mIsUpgrade && !onlyCore) {
                                                Slog.i(TAG, "Build fingerprint changed; clearing code caches");
                                                for (int i10 = 0; i10 < this.mSettings.mPackages.size(); i10++) {
                                                    PackageSetting ps4 = this.mSettings.mPackages.valueAt(i10);
                                                    if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, ps4.volumeUuid)) {
                                                        clearAppDataLIF(ps4.pkg, -1, UsbTerminalTypes.TERMINAL_IN_PERSONAL_MIC);
                                                    }
                                                }
                                                ver.fingerprint = Build.FINGERPRINT;
                                            }
                                            checkDefaultBrowser();
                                            this.mExistingSystemPackages.clear();
                                            this.mPromoteSystemApps = false;
                                            ver.databaseVersion = 3;
                                            Trace.traceBegin(262144L, "write settings");
                                            this.mSettings.writeLPr();
                                            Trace.traceEnd(262144L);
                                            EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_PMS_READY, SystemClock.uptimeMillis());
                                            addBootEvent("PMS: boot_progress_pms_ready");
                                            if (this.mOnlyCore) {
                                                this.mRequiredVerifierPackage = null;
                                                this.mRequiredInstallerPackage = null;
                                                this.mRequiredUninstallerPackage = null;
                                                this.mIntentFilterVerifierComponent = null;
                                                this.mIntentFilterVerifier = null;
                                                this.mServicesSystemSharedLibraryPackageName = null;
                                                this.mSharedSystemSharedLibraryPackageName = null;
                                            } else {
                                                this.mRequiredVerifierPackage = getRequiredButNotReallyRequiredVerifierLPr();
                                                this.mRequiredInstallerPackage = getRequiredInstallerLPr();
                                                this.mRequiredUninstallerPackage = getRequiredUninstallerLPr();
                                                this.mIntentFilterVerifierComponent = getIntentFilterVerifierComponentNameLPr();
                                                if (this.mIntentFilterVerifierComponent != null) {
                                                    this.mIntentFilterVerifier = new IntentVerifierProxy(this.mContext, this.mIntentFilterVerifierComponent);
                                                } else {
                                                    this.mIntentFilterVerifier = null;
                                                }
                                                this.mServicesSystemSharedLibraryPackageName = getRequiredSharedLibraryLPr("android.ext.services", -1);
                                                this.mSharedSystemSharedLibraryPackageName = getRequiredSharedLibraryLPr("android.ext.shared", -1);
                                            }
                                            this.mInstallerService = new PackageInstallerService(context, this);
                                            Pair<ComponentName, String> instantAppResolverComponent = getInstantAppResolverLPr();
                                            if (instantAppResolverComponent != null) {
                                                if (DEBUG_INSTANT) {
                                                    Slog.d(TAG, "Set ephemeral resolver: " + instantAppResolverComponent);
                                                }
                                                this.mInstantAppResolverConnection = new InstantAppResolverConnection(this.mContext, (ComponentName) instantAppResolverComponent.first, (String) instantAppResolverComponent.second);
                                                this.mInstantAppResolverSettingsComponent = getInstantAppResolverSettingsLPr((ComponentName) instantAppResolverComponent.first);
                                                str = null;
                                            } else {
                                                str = null;
                                                this.mInstantAppResolverConnection = null;
                                                this.mInstantAppResolverSettingsComponent = null;
                                            }
                                            updateInstantAppInstallerLocked(str);
                                            Map<Integer, List<PackageInfo>> userPackages = new HashMap<>();
                                            int[] currentUserIds = UserManagerService.getInstance().getUserIds();
                                            int length = currentUserIds.length;
                                            int i11 = 0;
                                            while (i11 < length) {
                                                int userId = currentUserIds[i11];
                                                userPackages.put(Integer.valueOf(userId), getInstalledPackages(0, userId).getList());
                                                i11++;
                                                deferPackages = deferPackages;
                                                instantAppResolverComponent = instantAppResolverComponent;
                                                currentUserIds = currentUserIds;
                                            }
                                            this.mDexManager.load(userPackages);
                                            if (this.mIsUpgrade) {
                                                MetricsLogger.histogram((Context) null, "ota_package_manager_init_time", (int) (SystemClock.uptimeMillis() - startTime));
                                            }
                                            xpPackageManagerService.get(this.mContext).onStart();
                                            Trace.traceBegin(262144L, "GC");
                                            Runtime.getRuntime().gc();
                                            Trace.traceEnd(262144L);
                                            this.mInstaller.setWarnIfHeld(this.mPackages);
                                            Trace.traceEnd(262144L);
                                            return;
                                        } catch (Throwable th3) {
                                            th = th3;
                                        }
                                    } catch (Throwable th4) {
                                        th = th4;
                                        arrayMap = arrayMap2;
                                    }
                                }
                            } catch (Throwable th5) {
                                th = th5;
                            }
                        } catch (Throwable th6) {
                            th = th6;
                            throw th;
                        }
                    } catch (Throwable th7) {
                        th = th7;
                    }
                }
                throw th;
            } catch (Throwable th8) {
                throw th8;
            }
        }
    }

    public static /* synthetic */ void lambda$new$0(PackageManagerService packageManagerService, List deferPackages, int storageFlags) {
        TimingsTraceLog traceLog = new TimingsTraceLog("SystemServerTimingAsync", 262144L);
        traceLog.traceBegin("AppDataFixup");
        try {
            packageManagerService.mInstaller.fixupAppData(StorageManager.UUID_PRIVATE_INTERNAL, 3);
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
            synchronized (packageManagerService.mPackages) {
                PackageSetting ps = packageManagerService.mSettings.getPackageLPr(pkgName);
                if (ps != null && ps.getInstalled(0)) {
                    pkg = ps.pkg;
                }
            }
            if (pkg != null) {
                synchronized (packageManagerService.mInstallLock) {
                    packageManagerService.prepareAppDataAndMigrateLIF(pkg, 0, storageFlags, true);
                }
                count++;
            }
        }
        traceLog.traceEnd();
        Slog.i(TAG, "Deferred reconcileAppsData finished " + count + " packages");
    }

    private void decompressSystemApplications(List<String> stubSystemApps, int scanFlags) {
        int i = stubSystemApps.size() - 1;
        while (true) {
            int i2 = i;
            if (i2 < 0) {
                break;
            }
            String pkgName = stubSystemApps.get(i2);
            if (this.mSettings.isDisabledSystemPackageLPr(pkgName)) {
                stubSystemApps.remove(i2);
            } else {
                PackageParser.Package pkg = this.mPackages.get(pkgName);
                if (pkg == null) {
                    stubSystemApps.remove(i2);
                } else {
                    PackageSetting ps = this.mSettings.mPackages.get(pkgName);
                    if (ps != null) {
                        int enabledState = ps.getEnabled(0);
                        if (enabledState == 3) {
                            stubSystemApps.remove(i2);
                        }
                    }
                    if (DEBUG_COMPRESSION) {
                        Slog.i(TAG, "Uncompressing system stub; pkg: " + pkgName);
                    }
                    File scanFile = decompressPackage(pkg);
                    if (scanFile != null) {
                        try {
                            this.mSettings.disableSystemPackageLPw(pkgName, true);
                            removePackageLI(pkg, true);
                            scanPackageTracedLI(scanFile, 0, scanFlags, 0L, (UserHandle) null);
                            ps.setEnabled(0, 0, PLATFORM_PACKAGE_NAME);
                            stubSystemApps.remove(i2);
                        } catch (PackageManagerException e) {
                            Slog.e(TAG, "Failed to parse uncompressed system package: " + e.getMessage());
                        }
                    }
                }
            }
            i = i2 - 1;
        }
        for (int i3 = stubSystemApps.size() - 1; i3 >= 0; i3 += -1) {
            String pkgName2 = stubSystemApps.get(i3);
            this.mSettings.mPackages.get(pkgName2).setEnabled(2, 0, PLATFORM_PACKAGE_NAME);
            PackageManagerServiceUtils.logCriticalInfo(6, "Stub disabled; pkg: " + pkgName2);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:13:0x0054, code lost:
        com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo(6, "Failed to decompress; pkg: " + r15.packageName + ", file: " + r11);
     */
    /* JADX WARN: Removed duplicated region for block: B:23:0x009e  */
    /* JADX WARN: Removed duplicated region for block: B:35:0x00d7  */
    /* JADX WARN: Removed duplicated region for block: B:42:0x00e5 A[RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.io.File decompressPackage(android.content.pm.PackageParser.Package r15) {
        /*
            Method dump skipped, instructions count: 259
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.decompressPackage(android.content.pm.PackageParser$Package):java.io.File");
    }

    private void updateInstantAppInstallerLocked(String modifiedPackage) {
        if (this.mInstantAppInstallerActivity != null && !this.mInstantAppInstallerActivity.getComponentName().getPackageName().equals(modifiedPackage)) {
            return;
        }
        setUpInstantAppInstallerActivityLP(getInstantAppInstallerLPr());
    }

    private static File preparePackageParserCache(boolean isUpgrade) {
        if (Build.IS_ENG) {
            return null;
        }
        if (SystemProperties.getBoolean("pm.boot.disable_package_cache", false)) {
            Slog.i(TAG, "Disabling package parser cache due to system property.");
            return null;
        }
        File cacheBaseDir = FileUtils.createDir(Environment.getDataSystemDirectory(), "package_cache");
        if (cacheBaseDir == null) {
            return null;
        }
        if (isUpgrade) {
            FileUtils.deleteContents(cacheBaseDir);
        }
        File cacheDir = FileUtils.createDir(cacheBaseDir, PACKAGE_PARSER_CACHE_VERSION);
        if (cacheDir == null) {
            Slog.wtf(TAG, "Cache directory cannot be created - wiping base dir " + cacheBaseDir);
            FileUtils.deleteContentsAndDir(cacheBaseDir);
            return null;
        } else if (Build.IS_USERDEBUG && Build.VERSION.INCREMENTAL.startsWith("eng.")) {
            Slog.w(TAG, "Wiping cache directory because the system partition changed.");
            File frameworkDir = new File(Environment.getRootDirectory(), "framework");
            if (cacheDir.lastModified() < frameworkDir.lastModified()) {
                FileUtils.deleteContents(cacheBaseDir);
                return FileUtils.createDir(cacheBaseDir, PACKAGE_PARSER_CACHE_VERSION);
            }
            return cacheDir;
        } else {
            return cacheDir;
        }
    }

    public boolean isFirstBoot() {
        return this.mFirstBoot;
    }

    public boolean isOnlyCoreApps() {
        return this.mOnlyCore;
    }

    public boolean isUpgrade() {
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
        String str;
        synchronized (this.mPackages) {
            SharedLibraryEntry libraryEntry = getSharedLibraryEntryLPr(name, version);
            if (libraryEntry == null) {
                throw new IllegalStateException("Missing required shared library:" + name);
            }
            str = libraryEntry.apk;
        }
        return str;
    }

    private String getRequiredInstallerLPr() {
        Intent intent = new Intent("android.intent.action.INSTALL_PACKAGE");
        intent.addCategory("android.intent.category.DEFAULT");
        intent.setDataAndType(Uri.fromFile(new File("foo.apk")), PACKAGE_MIME_TYPE);
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

    private ComponentName getIntentFilterVerifierComponentNameLPr() {
        Intent intent = new Intent("android.intent.action.INTENT_FILTER_NEEDS_VERIFICATION");
        List<ResolveInfo> matches = queryIntentReceiversInternal(intent, PACKAGE_MIME_TYPE, 1835008, 0, false);
        int N = matches.size();
        ResolveInfo best = null;
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

    /* JADX WARN: Code restructure failed: missing block: B:35:0x00a1, code lost:
        if (com.android.server.pm.PackageManagerService.DEBUG_INSTANT == false) goto L38;
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00a3, code lost:
        android.util.Slog.v(com.android.server.pm.PackageManagerService.TAG, "Ephemeral resolver found; pkg: " + r10 + ", info:" + r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x00cf, code lost:
        return new android.util.Pair<>(new android.content.ComponentName(r10, r9.serviceInfo.name), "android.intent.action.RESOLVE_INSTANT_APP_PACKAGE");
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private android.util.Pair<android.content.ComponentName, java.lang.String> getInstantAppResolverLPr() {
        /*
            r15 = this;
            android.content.Context r0 = r15.mContext
            android.content.res.Resources r0 = r0.getResources()
            r1 = 17236010(0x107002a, float:2.4795702E-38)
            java.lang.String[] r0 = r0.getStringArray(r1)
            int r1 = r0.length
            r2 = 0
            if (r1 != 0) goto L21
            boolean r1 = android.os.Build.IS_DEBUGGABLE
            if (r1 != 0) goto L21
            boolean r1 = com.android.server.pm.PackageManagerService.DEBUG_INSTANT
            if (r1 == 0) goto L20
            java.lang.String r1 = "PackageManager"
            java.lang.String r3 = "Ephemeral resolver NOT found; empty package list"
            android.util.Slog.d(r1, r3)
        L20:
            return r2
        L21:
            int r1 = android.os.Binder.getCallingUid()
            r3 = 786432(0xc0000, float:1.102026E-39)
            boolean r4 = android.os.Build.IS_DEBUGGABLE
            r11 = 0
            if (r4 != 0) goto L2f
            r4 = 1048576(0x100000, float:1.469368E-39)
            goto L30
        L2f:
            r4 = r11
        L30:
            r3 = r3 | r4
            java.lang.String r12 = "android.intent.action.RESOLVE_INSTANT_APP_PACKAGE"
            android.content.Intent r5 = new android.content.Intent
            r5.<init>(r12)
            r6 = 0
            r8 = 0
            r10 = 0
            r4 = r15
            r7 = r3
            r9 = r1
            java.util.List r4 = r4.queryIntentServicesInternal(r5, r6, r7, r8, r9, r10)
            int r6 = r4.size()
            if (r6 != 0) goto L54
            boolean r7 = com.android.server.pm.PackageManagerService.DEBUG_INSTANT
            if (r7 == 0) goto L53
            java.lang.String r7 = "PackageManager"
            java.lang.String r8 = "Ephemeral resolver NOT found; no matching intent filters"
            android.util.Slog.d(r7, r8)
        L53:
            return r2
        L54:
            android.util.ArraySet r7 = new android.util.ArraySet
            java.util.List r8 = java.util.Arrays.asList(r0)
            r7.<init>(r8)
        L5e:
            r8 = r11
            if (r8 >= r6) goto Ld0
            java.lang.Object r9 = r4.get(r8)
            android.content.pm.ResolveInfo r9 = (android.content.pm.ResolveInfo) r9
            android.content.pm.ServiceInfo r10 = r9.serviceInfo
            if (r10 != 0) goto L6c
            goto L9c
        L6c:
            android.content.pm.ServiceInfo r10 = r9.serviceInfo
            java.lang.String r10 = r10.packageName
            boolean r11 = r7.contains(r10)
            if (r11 != 0) goto L9f
            boolean r11 = android.os.Build.IS_DEBUGGABLE
            if (r11 != 0) goto L9f
            boolean r11 = com.android.server.pm.PackageManagerService.DEBUG_INSTANT
            if (r11 == 0) goto L9c
            java.lang.String r11 = "PackageManager"
            java.lang.StringBuilder r13 = new java.lang.StringBuilder
            r13.<init>()
            java.lang.String r14 = "Ephemeral resolver not in allowed package list; pkg: "
            r13.append(r14)
            r13.append(r10)
            java.lang.String r14 = ", info:"
            r13.append(r14)
            r13.append(r9)
            java.lang.String r13 = r13.toString()
            android.util.Slog.d(r11, r13)
        L9c:
            int r11 = r8 + 1
            goto L5e
        L9f:
            boolean r2 = com.android.server.pm.PackageManagerService.DEBUG_INSTANT
            if (r2 == 0) goto Lc1
            java.lang.String r2 = "PackageManager"
            java.lang.StringBuilder r11 = new java.lang.StringBuilder
            r11.<init>()
            java.lang.String r13 = "Ephemeral resolver found; pkg: "
            r11.append(r13)
            r11.append(r10)
            java.lang.String r13 = ", info:"
            r11.append(r13)
            r11.append(r9)
            java.lang.String r11 = r11.toString()
            android.util.Slog.v(r2, r11)
        Lc1:
            android.util.Pair r2 = new android.util.Pair
            android.content.ComponentName r11 = new android.content.ComponentName
            android.content.pm.ServiceInfo r13 = r9.serviceInfo
            java.lang.String r13 = r13.name
            r11.<init>(r10, r13)
            r2.<init>(r11, r12)
            return r2
        Ld0:
            boolean r8 = com.android.server.pm.PackageManagerService.DEBUG_INSTANT
            if (r8 == 0) goto Ldb
            java.lang.String r8 = "PackageManager"
            java.lang.String r9 = "Ephemeral resolver NOT found"
            android.util.Slog.v(r8, r9)
        Ldb:
            return r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.getInstantAppResolverLPr():android.util.Pair");
    }

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
            PackageSetting ps = this.mSettings.mPackages.get(rInfo.activityInfo.packageName);
            if (ps != null) {
                PermissionsState permissionsState = ps.getPermissionsState();
                if (!permissionsState.hasPermission("android.permission.INSTALL_PACKAGES", 0) && !Build.IS_ENG) {
                }
            }
            iter.remove();
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

    private void primeDomainVerificationsLPw(int userId) {
        if (DEBUG_DOMAIN_VERIFICATION) {
            Slog.d(TAG, "Priming domain verifications in user " + userId);
        }
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
                        if (DEBUG_DOMAIN_VERIFICATION) {
                            Slog.v(TAG, "      + " + packageName);
                        }
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

    private void applyFactoryDefaultBrowserLPw(int userId) {
        String browserPkg = this.mContext.getResources().getString(17039795);
        if (!TextUtils.isEmpty(browserPkg)) {
            PackageSetting ps = this.mSettings.mPackages.get(browserPkg);
            if (ps == null) {
                Slog.e(TAG, "Product default browser app does not exist: " + browserPkg);
                browserPkg = null;
            } else {
                this.mSettings.setDefaultBrowserPackageNameLPw(browserPkg, userId);
            }
        }
        if (browserPkg == null) {
            calculateDefaultBrowserLPw(userId);
        }
    }

    private void calculateDefaultBrowserLPw(int userId) {
        List<String> allBrowsers = resolveAllBrowserApps(userId);
        String browserPkg = allBrowsers.size() == 1 ? allBrowsers.get(0) : null;
        this.mSettings.setDefaultBrowserPackageNameLPw(browserPkg, userId);
    }

    private List<String> resolveAllBrowserApps(int userId) {
        List<ResolveInfo> list = queryIntentActivitiesInternal(sBrowserIntent, null, 131072, userId);
        int count = list.size();
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResolveInfo info = list.get(i);
            if (info.activityInfo != null && info.handleAllWebDataURI && (info.activityInfo.applicationInfo.flags & 1) != 0 && !result.contains(info.activityInfo.packageName)) {
                result.add(info.activityInfo.packageName);
            }
        }
        return result;
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

    private void checkDefaultBrowser() {
        int myUserId = UserHandle.myUserId();
        String packageName = getDefaultBrowserPackageName(myUserId);
        if (packageName != null) {
            PackageInfo info = getPackageInfo(packageName, 0, myUserId);
            if (info == null) {
                Slog.w(TAG, "Default browser no longer installed: " + packageName);
                synchronized (this.mPackages) {
                    applyFactoryDefaultBrowserLPw(myUserId);
                }
            }
        }
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

    static int[] appendInts(int[] cur, int[] add) {
        if (add == null) {
            return cur;
        }
        if (cur == null) {
            return add;
        }
        for (int i : add) {
            cur = ArrayUtils.appendInt(cur, i);
        }
        return cur;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean canViewInstantApps(int callingUid, int userId) {
        ComponentName homeComponent;
        if (callingUid < 10000 || this.mContext.checkCallingOrSelfPermission("android.permission.ACCESS_INSTANT_APPS") == 0) {
            return true;
        }
        if (this.mContext.checkCallingOrSelfPermission("android.permission.VIEW_INSTANT_APPS") == 0 && (homeComponent = getDefaultHomeActivity(userId)) != null && isCallerSameApp(homeComponent.getPackageName(), callingUid)) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PackageInfo generatePackageInfo(PackageSetting ps, int flags, int userId) {
        int i = flags;
        if (sUserManager.exists(userId) && ps != null) {
            int callingUid = Binder.getCallingUid();
            if (filterAppAccessLPr(ps, callingUid, userId)) {
                return null;
            }
            if ((i & 8192) != 0 && ps.isSystem()) {
                i |= DumpState.DUMP_CHANGES;
            }
            int flags2 = i;
            PackageUserState state = ps.readUserState(userId);
            PackageParser.Package p = ps.pkg;
            if (p != null) {
                PermissionsState permissionsState = ps.getPermissionsState();
                int[] gids = (flags2 & 256) == 0 ? EMPTY_INT_ARRAY : permissionsState.computeGids(userId);
                Set<String> permissions = ArrayUtils.isEmpty(p.requestedPermissions) ? Collections.emptySet() : permissionsState.getPermissions(userId);
                PackageInfo packageInfo = PackageParser.generatePackageInfo(p, gids, flags2, ps.firstInstallTime, ps.lastUpdateTime, permissions, state, userId);
                if (packageInfo == null) {
                    return null;
                }
                ApplicationInfo applicationInfo = packageInfo.applicationInfo;
                String resolveExternalPackageNameLPr = resolveExternalPackageNameLPr(p);
                applicationInfo.packageName = resolveExternalPackageNameLPr;
                packageInfo.packageName = resolveExternalPackageNameLPr;
                return packageInfo;
            } else if ((flags2 & 8192) == 0 || !state.isAvailable(flags2)) {
                return null;
            } else {
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
                if (DEBUG_PACKAGE_INFO) {
                    Log.v(TAG, "ps.pkg is n/a for [" + ps.name + "]. Provides a minimum info.");
                }
                return pi;
            }
        }
        return null;
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
        PackageSetting ps;
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForPackage(flags, userId, packageName);
            this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get package info");
            synchronized (this.mPackages) {
                String packageName2 = resolveInternalPackageNameLPr(packageName, versionCode);
                boolean matchFactoryOnly = (2097152 & flags2) != 0;
                if (matchFactoryOnly && (ps = this.mSettings.getDisabledSystemPkgLPr(packageName2)) != null) {
                    if (filterSharedLibPackageLPr(ps, filterCallingUid, userId, flags2)) {
                        return null;
                    }
                    if (filterAppAccessLPr(ps, filterCallingUid, userId)) {
                        return null;
                    }
                    return generatePackageInfo(ps, flags2, userId);
                }
                PackageParser.Package p = this.mPackages.get(packageName2);
                if (!matchFactoryOnly || p == null || isSystemApp(p)) {
                    if (DEBUG_PACKAGE_INFO) {
                        Log.v(TAG, "getPackageInfo " + packageName2 + ": " + p);
                    }
                    if (p != null) {
                        PackageSetting ps2 = (PackageSetting) p.mExtras;
                        if (filterSharedLibPackageLPr(ps2, filterCallingUid, userId, flags2)) {
                            return null;
                        }
                        if (ps2 == null || !filterAppAccessLPr(ps2, filterCallingUid, userId)) {
                            return generatePackageInfo((PackageSetting) p.mExtras, flags2, userId);
                        }
                        return null;
                    } else if (matchFactoryOnly || (4202496 & flags2) == 0) {
                        return null;
                    } else {
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
        if (type != 1) {
            if (type != 2) {
                if (type != 3) {
                    if (type != 4) {
                        if (type == 0) {
                            return isComponentVisibleToInstantApp(component);
                        }
                        return false;
                    }
                    PackageParser.Provider provider = (PackageParser.Provider) this.mProviders.mProviders.get(component);
                    return (provider == null || (1048576 & provider.info.flags) == 0) ? false : true;
                }
                PackageParser.Service service = (PackageParser.Service) this.mServices.mServices.get(component);
                return (service == null || (1048576 & service.info.flags) == 0) ? false : true;
            }
            PackageParser.Activity activity = (PackageParser.Activity) this.mReceivers.mActivities.get(component);
            if (activity == null) {
                return false;
            }
            boolean visibleToInstantApp = (1048576 & activity.info.flags) != 0;
            boolean explicitlyVisibleToInstantApp = (2097152 & activity.info.flags) == 0;
            return visibleToInstantApp && !explicitlyVisibleToInstantApp;
        }
        PackageParser.Activity activity2 = (PackageParser.Activity) this.mActivities.mActivities.get(component);
        if (activity2 == null) {
            return false;
        }
        boolean visibleToInstantApp2 = (1048576 & activity2.info.flags) != 0;
        boolean explicitlyVisibleToInstantApp2 = (2097152 & activity2.info.flags) == 0;
        return visibleToInstantApp2 && explicitlyVisibleToInstantApp2;
    }

    /* JADX INFO: Access modifiers changed from: private */
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
    public boolean filterAppAccessLPr(PackageSetting ps, int callingUid, int userId) {
        return filterAppAccessLPr(ps, callingUid, null, 0, userId);
    }

    private boolean filterSharedLibPackageLPr(PackageSetting ps, int uid, int userId, int flags) {
        int index;
        if ((flags & 67108864) != 0) {
            int appId = UserHandle.getAppId(uid);
            if (appId == 1000 || appId == SHELL_UID || appId == 0) {
                return false;
            }
            if (checkUidPermission("android.permission.INSTALL_PACKAGES", uid) == 0) {
                return false;
            }
        }
        if (ps == null || ps.pkg == null || !ps.pkg.applicationInfo.isStaticSharedLibrary()) {
            return false;
        }
        SharedLibraryEntry libEntry = getSharedLibraryEntryLPr(ps.pkg.staticSharedLibName, ps.pkg.staticSharedLibVersion);
        if (libEntry == null) {
            return false;
        }
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
            if (uidPs != null && (index = ArrayUtils.indexOf(uidPs.usesStaticLibraries, libEntry.info.getName())) >= 0 && uidPs.pkg.usesStaticLibrariesVersions[index] == libEntry.info.getLongVersion()) {
                return false;
            }
        }
        return true;
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
        boolean translateName;
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
                if (cur != null) {
                    PackageSetting ps = this.mSettings.mPackages.get(names[i]);
                    boolean z2 = false;
                    boolean targetIsInstantApp = (ps == null || !ps.getInstantApp(callingUserId)) ? false : z;
                    translateName = (!targetIsInstantApp || canViewInstantApps || this.mInstantAppRegistry.isInstantAccessGranted(callingUserId, UserHandle.getAppId(callingUid), ps.appId)) ? false : z2;
                    z2 = true;
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
            this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get application info");
            synchronized (this.mPackages) {
                String packageName2 = resolveInternalPackageNameLPr(packageName, -1L);
                PackageParser.Package p = this.mPackages.get(packageName2);
                if (DEBUG_PACKAGE_INFO) {
                    Log.v(TAG, "getApplicationInfo " + packageName2 + ": " + p);
                }
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

    private String normalizePackageNameLPr(String packageName) {
        String normalizedPackageName = this.mSettings.getRenamedPackageLPr(packageName);
        return normalizedPackageName != null ? normalizedPackageName : packageName;
    }

    public void deletePreloadsFileCache() {
        if (!UserHandle.isSameApp(Binder.getCallingUid(), 1000)) {
            throw new SecurityException("Only system or settings may call deletePreloadsFileCache");
        }
        File dir = Environment.getDataPreloadsFileCacheDirectory();
        Slog.i(TAG, "Deleting preloaded file cache " + dir);
        FileUtils.deleteContents(dir);
    }

    public void freeStorageAndNotify(final String volumeUuid, final long freeStorageSize, final int storageFlags, final IPackageDataObserver observer) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.CLEAR_APP_CACHE", null);
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$Iz1l7RVtATr5Ybl_zHeYuCbGMvA
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.lambda$freeStorageAndNotify$1(PackageManagerService.this, volumeUuid, freeStorageSize, storageFlags, observer);
            }
        });
    }

    public static /* synthetic */ void lambda$freeStorageAndNotify$1(PackageManagerService packageManagerService, String volumeUuid, long freeStorageSize, int storageFlags, IPackageDataObserver observer) {
        boolean success = false;
        try {
            packageManagerService.freeStorage(volumeUuid, freeStorageSize, storageFlags);
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
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$gqdNHYJiYM0w_nIH0nGMWWU8yzQ
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.lambda$freeStorage$2(PackageManagerService.this, volumeUuid, freeStorageSize, storageFlags, pi);
            }
        });
    }

    public static /* synthetic */ void lambda$freeStorage$2(PackageManagerService packageManagerService, String volumeUuid, long freeStorageSize, int storageFlags, IntentSender pi) {
        boolean success = false;
        try {
            packageManagerService.freeStorage(volumeUuid, freeStorageSize, storageFlags);
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
        if (ENABLE_FREE_CACHE_V2) {
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
                this.mInstaller.freeCache(volumeUuid, bytes, reservedBytes, 8192);
            } catch (Installer.InstallerException e) {
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
                        this.mInstaller.freeCache(volumeUuid, bytes, reservedBytes, 24576);
                    } catch (Installer.InstallerException e2) {
                    }
                } catch (Installer.InstallerException e3) {
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
        } else {
            try {
                this.mInstaller.freeCache(volumeUuid, bytes, 0L, 0);
            } catch (Installer.InstallerException e4) {
            }
            if (file.getUsableSpace() >= bytes) {
                return;
            }
        }
        throw new IOException("Failed to free " + bytes + " on storage device at " + file);
    }

    private boolean pruneUnusedStaticSharedLibraries(long neededSpace, long maxCachePeriod) throws IOException {
        File volume;
        int libCount;
        StorageManager storage;
        File volume2;
        int versionCount;
        StorageManager storage2;
        File volume3;
        StorageManager storage3 = (StorageManager) this.mContext.getSystemService(StorageManager.class);
        File volume4 = storage3.findPathForUuid(StorageManager.UUID_PRIVATE_INTERNAL);
        long now = System.currentTimeMillis();
        synchronized (this.mPackages) {
            try {
                sUserManager.getUserIds();
                int libCount2 = this.mSharedLibraries.size();
                ArrayList arrayList = null;
                int i = 0;
                while (i < libCount2) {
                    try {
                        LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.valueAt(i);
                        if (versionedLib != null) {
                            int versionCount2 = versionedLib.size();
                            int j = 0;
                            while (j < versionCount2) {
                                SharedLibraryInfo libInfo = versionedLib.valueAt(j).info;
                                if (libInfo.isStatic()) {
                                    VersionedPackage declaringPackage = libInfo.getDeclaringPackage();
                                    int libCount3 = libCount2;
                                    LongSparseArray<SharedLibraryEntry> versionedLib2 = versionedLib;
                                    String internalPackageName = resolveInternalPackageNameLPr(declaringPackage.getPackageName(), declaringPackage.getLongVersionCode());
                                    PackageSetting ps = this.mSettings.getPackageLPr(internalPackageName);
                                    if (ps == null) {
                                        versionCount = versionCount2;
                                        storage2 = storage3;
                                        volume3 = volume4;
                                    } else {
                                        storage2 = storage3;
                                        volume3 = volume4;
                                        try {
                                            if (now - ps.lastUpdateTime < maxCachePeriod) {
                                                versionCount = versionCount2;
                                            } else {
                                                if (arrayList == null) {
                                                    arrayList = new ArrayList();
                                                }
                                                versionCount = versionCount2;
                                                arrayList.add(new VersionedPackage(internalPackageName, declaringPackage.getLongVersionCode()));
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
                                    }
                                    j++;
                                    libCount2 = libCount3;
                                    versionedLib = versionedLib2;
                                    storage3 = storage2;
                                    volume4 = volume3;
                                    versionCount2 = versionCount;
                                }
                            }
                            libCount = libCount2;
                            storage = storage3;
                            volume2 = volume4;
                            i++;
                            libCount2 = libCount;
                            storage3 = storage;
                            volume4 = volume2;
                        }
                        libCount = libCount2;
                        storage = storage3;
                        volume2 = volume4;
                        i++;
                        libCount2 = libCount;
                        storage3 = storage;
                        volume4 = volume2;
                    } catch (Throwable th3) {
                        th = th3;
                    }
                }
                File volume5 = volume4;
                try {
                    if (arrayList != null) {
                        int packageCount = arrayList.size();
                        int i2 = 0;
                        while (true) {
                            int i3 = i2;
                            if (i3 >= packageCount) {
                                break;
                            }
                            VersionedPackage pkgToDelete = (VersionedPackage) arrayList.get(i3);
                            if (deletePackageX(pkgToDelete.getPackageName(), pkgToDelete.getLongVersionCode(), 0, 2) != 1) {
                                volume = volume5;
                            } else {
                                volume = volume5;
                                if (volume.getUsableSpace() >= neededSpace) {
                                    return true;
                                }
                            }
                            i2 = i3 + 1;
                            volume5 = volume;
                        }
                    }
                    return false;
                } catch (Throwable th4) {
                    th = th4;
                    while (true) {
                        break;
                        break;
                    }
                    throw th;
                }
            } catch (Throwable th5) {
                th = th5;
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

    /* JADX INFO: Access modifiers changed from: private */
    public DeviceIdleController.LocalService getDeviceIdleController() {
        if (this.mDeviceIdleController == null) {
            this.mDeviceIdleController = (DeviceIdleController.LocalService) LocalServices.getService(DeviceIdleController.LocalService.class);
        }
        return this.mDeviceIdleController;
    }

    private int updateFlagsForPackage(int flags, int userId, Object cookie) {
        int flags2;
        boolean isCallerSystemUser = UserHandle.getCallingUserId() == 0;
        boolean triaged = true;
        if ((flags & 15) != 0 && (269221888 & flags) == 0) {
            triaged = false;
        }
        if ((269492224 & flags) == 0) {
            triaged = false;
        }
        boolean triaged2 = triaged;
        if ((flags & DumpState.DUMP_CHANGES) != 0) {
            this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, !isRecentsAccessingChildProfiles(Binder.getCallingUid(), userId), "MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission at " + Debug.getCallers(5));
        } else if ((flags & 8192) != 0 && isCallerSystemUser && sUserManager.hasManagedProfile(0)) {
            flags2 = flags | DumpState.DUMP_CHANGES;
            if (DEBUG_TRIAGED_MISSING && Binder.getCallingUid() == 1000 && !triaged2) {
                Log.w(TAG, "Caller hasn't been triaged for missing apps; they asked about " + cookie + " with flags 0x" + Integer.toHexString(flags2), new Throwable());
            }
            return updateFlags(flags2, userId);
        }
        flags2 = flags;
        if (DEBUG_TRIAGED_MISSING) {
            Log.w(TAG, "Caller hasn't been triaged for missing apps; they asked about " + cookie + " with flags 0x" + Integer.toHexString(flags2), new Throwable());
        }
        return updateFlags(flags2, userId);
    }

    private int updateFlagsForApplication(int flags, int userId, Object cookie) {
        return updateFlagsForPackage(flags, userId, cookie);
    }

    private int updateFlagsForComponent(int flags, int userId, Object cookie) {
        if ((cookie instanceof Intent) && (((Intent) cookie).getFlags() & 256) != 0) {
            flags |= 268435456;
        }
        boolean triaged = true;
        if ((269221888 & flags) == 0) {
            triaged = false;
        }
        if (DEBUG_TRIAGED_MISSING && Binder.getCallingUid() == 1000 && !triaged) {
            Log.w(TAG, "Caller hasn't been triaged for missing apps; they asked about " + cookie + " with flags 0x" + Integer.toHexString(flags), new Throwable());
        }
        return updateFlags(flags, userId);
    }

    private Intent updateIntentForResolve(Intent intent) {
        if (intent.getSelector() != null) {
            intent = intent.getSelector();
        }
        if (DEBUG_PREFERRED) {
            intent.addFlags(8);
        }
        return intent;
    }

    int updateFlagsForResolve(int flags, int userId, Intent intent, int callingUid) {
        return updateFlagsForResolve(flags, userId, intent, callingUid, false, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int updateFlagsForResolve(int flags, int userId, Intent intent, int callingUid, boolean wantInstantApps) {
        return updateFlagsForResolve(flags, userId, intent, callingUid, wantInstantApps, false);
    }

    int updateFlagsForResolve(int flags, int userId, Intent intent, int callingUid, boolean wantInstantApps, boolean onlyExposedExplicitly) {
        int flags2;
        if (this.mSafeMode) {
            flags |= 1048576;
        }
        if (getInstantAppPackageName(callingUid) != null) {
            if (onlyExposedExplicitly) {
                flags |= 33554432;
            }
            flags2 = flags | 16777216 | DumpState.DUMP_VOLUMES;
        } else {
            boolean allowMatchInstant = false;
            boolean wantMatchInstant = (flags & DumpState.DUMP_VOLUMES) != 0;
            if (wantInstantApps || (wantMatchInstant && canViewInstantApps(callingUid, userId))) {
                allowMatchInstant = true;
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
                PackageParser.Activity a = (PackageParser.Activity) this.mActivities.mActivities.get(component);
                if (DEBUG_PACKAGE_INFO) {
                    Log.v(TAG, "getActivityInfo " + component + ": " + a);
                }
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
        if (getActivityManagerInternal().isCallerRecents(callingUid)) {
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
            PackageParser.Activity a = (PackageParser.Activity) this.mActivities.mActivities.get(component);
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
                PackageParser.Activity a = (PackageParser.Activity) this.mReceivers.mActivities.get(component);
                if (DEBUG_PACKAGE_INFO) {
                    Log.v(TAG, "getReceiverInfo " + component + ": " + a);
                }
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

    /* JADX WARN: Removed duplicated region for block: B:22:0x0058  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.content.pm.ParceledListSlice<android.content.pm.SharedLibraryInfo> getSharedLibraries(java.lang.String r31, int r32, int r33) {
        /*
            Method dump skipped, instructions count: 257
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.getSharedLibraries(java.lang.String, int, int):android.content.pm.ParceledListSlice");
    }

    private List<VersionedPackage> getPackagesUsingSharedLibraryLPr(SharedLibraryInfo libInfo, int flags, int userId) {
        List<VersionedPackage> versionedPackages = null;
        int packageCount = this.mSettings.mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageSetting ps = this.mSettings.mPackages.valueAt(i);
            if (ps != null && ps.getUserState().get(userId).isAvailable(flags)) {
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
                PackageParser.Service s = (PackageParser.Service) this.mServices.mServices.get(component);
                if (DEBUG_PACKAGE_INFO) {
                    Log.v(TAG, "getServiceInfo " + component + ": " + s);
                }
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
                PackageParser.Provider p = (PackageParser.Provider) this.mProviders.mProviders.get(component);
                if (DEBUG_PACKAGE_INFO) {
                    Log.v(TAG, "getProviderInfo " + component + ": " + p);
                }
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

    public String[] getSystemSharedLibraryNames() {
        synchronized (this.mPackages) {
            int libCount = this.mSharedLibraries.size();
            Set<String> libs = null;
            for (int i = 0; i < libCount; i++) {
                LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.valueAt(i);
                if (versionedLib != null) {
                    int versionCount = versionedLib.size();
                    int j = 0;
                    while (true) {
                        if (j < versionCount) {
                            SharedLibraryEntry libEntry = versionedLib.valueAt(j);
                            if (!libEntry.info.isStatic()) {
                                if (libs == null) {
                                    libs = new ArraySet<>();
                                }
                                libs.add(libEntry.info.getName());
                            } else {
                                PackageSetting ps = this.mSettings.getPackageLPr(libEntry.apk);
                                if (ps == null || filterSharedLibPackageLPr(ps, Binder.getCallingUid(), UserHandle.getUserId(Binder.getCallingUid()), 67108864)) {
                                    j++;
                                } else {
                                    if (libs == null) {
                                        libs = new ArraySet<>();
                                    }
                                    libs.add(libEntry.info.getName());
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
        ChangedPackages changedPackages = null;
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (this.mPackages) {
            if (sequenceNumber >= this.mChangedPackagesSequenceNumber) {
                return null;
            }
            SparseArray<String> changedPackages2 = this.mChangedPackages.get(userId);
            if (changedPackages2 == null) {
                return null;
            }
            List<String> packageNames = new ArrayList<>(this.mChangedPackagesSequenceNumber - sequenceNumber);
            for (int i = sequenceNumber; i < this.mChangedPackagesSequenceNumber; i++) {
                String packageName = changedPackages2.get(i);
                if (packageName != null) {
                    packageNames.add(packageName);
                }
            }
            if (!packageNames.isEmpty()) {
                changedPackages = new ChangedPackages(this.mChangedPackagesSequenceNumber, packageNames);
            }
            return changedPackages;
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
        if ("com.xiaopeng.montecarlo".equals(pkgName)) {
            return 0;
        }
        return this.mPermissionManager.checkPermission(permName, pkgName, getCallingUid(), userId);
    }

    public int checkUidPermission(String permName, int uid) {
        PackageParser.Package pkg;
        synchronized (this.mPackages) {
            String[] packageNames = getPackagesForUid(uid);
            if (packageNames != null && packageNames.length > 0) {
                pkg = this.mPackages.get(packageNames[0]);
            } else {
                pkg = null;
            }
            if (packageNames != null && packageNames.length > 0 && "com.xiaopeng.montecarlo".equals(packageNames[0])) {
                return 0;
            }
            return this.mPermissionManager.checkUidPermission(permName, pkg, uid, getCallingUid());
        }
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
            str = this.mRequiredInstallerPackage;
        }
        return str;
    }

    private boolean addDynamicPermission(PermissionInfo info, final boolean async) {
        return this.mPermissionManager.addDynamicPermission(info, async, getCallingUid(), new PermissionManagerInternal.PermissionCallback() { // from class: com.android.server.pm.PackageManagerService.5
            @Override // com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback
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
        this.mPermissionManager.grantRuntimePermission(permName, packageName, false, getCallingUid(), userId, this.mPermissionCallback);
    }

    public void revokeRuntimePermission(String packageName, String permName, int userId) {
        this.mPermissionManager.revokeRuntimePermission(permName, packageName, false, getCallingUid(), userId, this.mPermissionCallback);
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

    public void updatePermissionFlags(String permName, String packageName, int flagMask, int flagValues, int userId) {
        this.mPermissionManager.updatePermissionFlags(permName, packageName, flagMask, flagValues, getCallingUid(), userId, this.mPermissionCallback);
    }

    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId) {
        synchronized (this.mPackages) {
            boolean changed = this.mPermissionManager.updatePermissionFlagsForAllApps(flagMask, flagValues, getCallingUid(), userId, this.mPackages.values(), this.mPermissionCallback);
            if (changed) {
                this.mSettings.writeRuntimePermissionsForUserLPr(userId, false);
            }
        }
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
        int uid12 = UserHandle.getAppId(uid1);
        int uid22 = UserHandle.getAppId(uid2);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getUserIdLPr(uid12);
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
            Object obj2 = this.mSettings.getUserIdLPr(uid22);
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
                switch (type) {
                    case 0:
                        return p.mSigningDetails.hasCertificate(certificate);
                    case 1:
                        return p.mSigningDetails.hasSha256Certificate(certificate);
                    default:
                        return false;
                }
            }
            return false;
        }
    }

    public boolean hasUidSigningCertificate(int uid, byte[] certificate, int type) {
        PackageParser.SigningDetails signingDetails;
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        int uid2 = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getUserIdLPr(uid2);
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
            switch (type) {
                case 0:
                    return signingDetails.hasCertificate(certificate);
                case 1:
                    return signingDetails.hasSha256Certificate(certificate);
                default:
                    return false;
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
        Settings.VersionInfo ver = getSettingsVersionForPackage(scannedPkg);
        return ver.databaseVersion < 2;
    }

    private boolean isRecoverSignatureUpdateNeeded(PackageParser.Package scannedPkg) {
        Settings.VersionInfo ver = getSettingsVersionForPackage(scannedPkg);
        return ver.databaseVersion < 3;
    }

    public List<String> getAllPackages() {
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
        int uid2 = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getUserIdLPr(uid2);
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
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getUserIdLPr(UserHandle.getAppId(uid));
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
                int uid = uids[i];
                Object obj = this.mSettings.getUserIdLPr(UserHandle.getAppId(uid));
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
                    } finally {
                    }
                } catch (PackageManagerException e) {
                }
                if (suid != null) {
                    return suid.userId;
                }
                return -1;
            }
        }
        return -1;
    }

    public int getFlagsForUid(int uid) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getUserIdLPr(UserHandle.getAppId(uid));
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
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getUserIdLPr(UserHandle.getAppId(uid));
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
        int uid2 = UserHandle.getAppId(uid);
        synchronized (this.mPackages) {
            Object obj = this.mSettings.getUserIdLPr(uid2);
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
            if (!sUserManager.exists(userId)) {
                Trace.traceEnd(262144L);
                return null;
            }
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
        if (DEBUG_PREFERRED) {
            Log.v(TAG, "setLastChosenActivity intent=" + intent + " resolvedType=" + resolvedType + " flags=" + flags + " filter=" + filter + " match=" + match + " activity=" + activity);
            filter.dump(new PrintStreamPrinter(System.out), "    ");
        }
        intent.setComponent(null);
        List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags, userId);
        findPreferredActivity(intent, resolvedType, flags, query, 0, false, true, false, userId);
        addPreferredActivityInternal(filter, match, null, activity, false, userId, "Setting last chosen");
    }

    public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) {
            Log.v(TAG, "Querying last chosen activity for " + intent);
        }
        List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags, userId);
        return findPreferredActivity(intent, resolvedType, flags, query, 0, false, false, false, userId);
    }

    private boolean areWebInstantAppsDisabled() {
        return this.mWebInstantAppsDisabled;
    }

    private boolean isInstantAppResolutionAllowed(Intent intent, List<ResolveInfo> resolvedActivities, int userId, boolean skipPackageCheck) {
        if (this.mInstantAppResolverConnection != null && this.mInstantAppInstallerActivity != null && intent.getComponent() == null && (intent.getFlags() & 512) == 0) {
            if (skipPackageCheck || intent.getPackage() == null) {
                if (!intent.isWebIntent()) {
                    if ((resolvedActivities != null && resolvedActivities.size() != 0) || (intent.getFlags() & 2048) == 0) {
                        return false;
                    }
                } else if (intent.getData() == null || TextUtils.isEmpty(intent.getData().getHost()) || areWebInstantAppsDisabled()) {
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
                if (DEBUG_INTENT_MATCHING || debug) {
                    Slog.v(TAG, r0.activityInfo.name + "=" + r0.priority + " vs " + r1.activityInfo.name + "=" + r1.priority);
                }
                if (r0.priority == r1.priority && r0.preferredOrder == r1.preferredOrder) {
                    if (r0.isDefault == r1.isDefault) {
                        intent.addPrivateFlags(64);
                        ResolveInfo ri = findPreferredActivity(intent, resolvedType, flags, query, r0.priority, true, false, debug, userId);
                        if (ri == null) {
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
                        return ri;
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

    private ResolveInfo findPersistentPreferredActivityLP(Intent intent, String resolvedType, int flags, List<ResolveInfo> query, boolean debug, int userId) {
        List<PersistentPreferredActivity> pprefs;
        PackageManagerService packageManagerService = this;
        int i = flags;
        int N = query.size();
        PersistentPreferredIntentResolver ppir = packageManagerService.mSettings.mPersistentPreferredActivities.get(userId);
        if (DEBUG_PREFERRED || debug) {
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
                if (DEBUG_PREFERRED || debug) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Checking PersistentPreferredActivity ds=");
                    sb.append(ppa.countDataSchemes() > 0 ? ppa.getDataScheme(i2) : "<none>");
                    sb.append("\n  component=");
                    sb.append(ppa.mComponent);
                    Slog.v(TAG, sb.toString());
                    ppa.dump(new LogPrinter(2, TAG, 3), "  ");
                }
                ActivityInfo ai = packageManagerService.getActivityInfo(ppa.mComponent, i | 512, userId);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Found persistent preferred activity:");
                    if (ai != null) {
                        ai.dump(new LogPrinter(2, TAG, 3), "  ");
                    } else {
                        Slog.v(TAG, "  null");
                    }
                }
                if (ai != null) {
                    for (int j = 0; j < N; j++) {
                        ResolveInfo ri = query.get(j);
                        if (ri.activityInfo.applicationInfo.packageName.equals(ai.applicationInfo.packageName) && ri.activityInfo.name.equals(ai.name)) {
                            if (DEBUG_PREFERRED || debug) {
                                Slog.v(TAG, "Returning persistent preferred activity: " + ri.activityInfo.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + ri.activityInfo.name);
                            }
                            return ri;
                        }
                    }
                }
                i3++;
                packageManagerService = this;
                i = flags;
                i2 = 0;
            }
        }
        return null;
    }

    /* JADX WARN: Can't wrap try/catch for region: R(7:(2:33|34)|(8:(4:38|39|(4:41|42|43|44)(1:55)|45)|61|62|63|64|39|(0)(0)|45)|56|57|58|59|60) */
    /* JADX WARN: Code restructure failed: missing block: B:57:0x00e4, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:58:0x00e5, code lost:
        r19 = r4;
     */
    /* JADX WARN: Removed duplicated region for block: B:108:0x0200 A[Catch: all -> 0x03b7, TryCatch #5 {all -> 0x03b7, blocks: (B:83:0x017b, B:85:0x0181, B:90:0x018c, B:92:0x01ad, B:94:0x01b3, B:97:0x01b9, B:98:0x01c1, B:108:0x0200, B:113:0x022a, B:118:0x024e, B:116:0x0241, B:120:0x0253, B:132:0x028e, B:134:0x0296, B:136:0x029e, B:138:0x02a2, B:103:0x01db, B:105:0x01e4, B:106:0x01f5, B:82:0x0173), top: B:213:0x0181 }] */
    /* JADX WARN: Removed duplicated region for block: B:111:0x0227  */
    /* JADX WARN: Removed duplicated region for block: B:186:0x03df A[Catch: all -> 0x0403, TryCatch #1 {all -> 0x0403, blocks: (B:186:0x03df, B:188:0x03e3, B:189:0x03ea, B:190:0x03ed, B:172:0x03ab, B:163:0x0395, B:165:0x0399, B:166:0x03a0, B:167:0x03a3, B:149:0x0353, B:151:0x0357, B:152:0x035e, B:153:0x0361, B:201:0x0401, B:177:0x03c0, B:179:0x03c4, B:180:0x03cb, B:192:0x03ef), top: B:207:0x0035 }] */
    /* JADX WARN: Removed duplicated region for block: B:213:0x0181 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:215:0x00ca A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:225:0x00cd A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:91:0x01ab  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    android.content.pm.ResolveInfo findPreferredActivity(android.content.Intent r34, java.lang.String r35, int r36, java.util.List<android.content.pm.ResolveInfo> r37, int r38, boolean r39, boolean r40, boolean r41, int r42) {
        /*
            Method dump skipped, instructions count: 1029
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.findPreferredActivity(android.content.Intent, java.lang.String, int, java.util.List, int, boolean, boolean, boolean, int):android.content.pm.ResolveInfo");
    }

    public boolean canForwardTo(Intent intent, String resolvedType, int sourceUserId, int targetUserId) {
        boolean z;
        this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        List<CrossProfileIntentFilter> matches = getMatchingCrossProfileIntentFilters(intent, resolvedType, sourceUserId);
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
                z = xpDomainInfo != null;
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
            Object obj = this.mSettings.getUserIdLPr(appId);
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
    public List<ResolveInfo> queryIntentActivitiesInternal(Intent intent, String resolvedType, int flags, int filterCallingUid, int userId, boolean resolveForStart, boolean allowDynamicSplits) {
        Intent intent2;
        ComponentName comp;
        ArrayMap<String, PackageParser.Package> arrayMap;
        Intent intent3;
        String instantAppPkgName;
        int i;
        PackageManagerService packageManagerService;
        int flags2;
        List<ResolveInfo> result;
        String pkgName;
        Intent intent4;
        String str;
        ResolveInfo xpResolveInfo;
        boolean sortResult;
        List<ResolveInfo> result2;
        ResolveInfo xpResolveInfo2;
        CrossProfileDomainInfo xpDomainInfo;
        UserInfo parent;
        String str2;
        String str3;
        Intent intent5;
        int i2;
        PackageManagerService packageManagerService2;
        int flags3;
        ComponentName comp2;
        if (!sUserManager.exists(userId)) {
            return Collections.emptyList();
        }
        String instantAppPkgName2 = getInstantAppPackageName(filterCallingUid);
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "query intent activities");
        String pkgName2 = intent.getPackage();
        ComponentName comp3 = intent.getComponent();
        if (comp3 != null || intent.getSelector() == null) {
            intent2 = intent;
            comp = comp3;
        } else {
            Intent intent6 = intent.getSelector();
            comp = intent6.getComponent();
            intent2 = intent6;
        }
        int flags4 = updateFlagsForResolve(flags, userId, intent2, filterCallingUid, resolveForStart, (comp == null && pkgName2 == null) ? false : true);
        if (comp != null) {
            List<ResolveInfo> list = new ArrayList<>(1);
            ActivityInfo ai = getActivityInfo(comp, flags4, userId);
            if (ai != null) {
                boolean matchInstantApp = (8388608 & flags4) != 0;
                boolean matchVisibleToInstantAppOnly = (16777216 & flags4) != 0;
                boolean matchExplicitlyVisibleOnly = (33554432 & flags4) != 0;
                boolean isCallerInstantApp = instantAppPkgName2 != null;
                boolean isTargetSameInstantApp = comp.getPackageName().equals(instantAppPkgName2);
                flags3 = flags4;
                int flags5 = ai.applicationInfo.privateFlags;
                boolean isTargetInstantApp = (flags5 & 128) != 0;
                comp2 = comp;
                boolean isTargetVisibleToInstantApp = (ai.flags & 1048576) != 0;
                boolean isTargetExplicitlyVisibleToInstantApp = isTargetVisibleToInstantApp && (ai.flags & 2097152) == 0;
                boolean isTargetHiddenFromInstantApp = !isTargetVisibleToInstantApp || (matchExplicitlyVisibleOnly && !isTargetExplicitlyVisibleToInstantApp);
                boolean blockResolution = !isTargetSameInstantApp && (!(matchInstantApp || isCallerInstantApp || !isTargetInstantApp) || (matchVisibleToInstantAppOnly && isCallerInstantApp && isTargetHiddenFromInstantApp));
                if (!blockResolution) {
                    ResolveInfo ri = new ResolveInfo();
                    ri.activityInfo = ai;
                    list.add(ri);
                }
            } else {
                flags3 = flags4;
                comp2 = comp;
            }
            return applyPostResolutionFilter(list, instantAppPkgName2, allowDynamicSplits, filterCallingUid, resolveForStart, userId, intent2);
        }
        boolean sortResult2 = false;
        boolean addInstant = false;
        ArrayMap<String, PackageParser.Package> arrayMap2 = this.mPackages;
        synchronized (arrayMap2) {
            try {
                if (pkgName2 == null) {
                    try {
                        List<CrossProfileIntentFilter> matchingFilters = getMatchingCrossProfileIntentFilters(intent2, resolvedType, userId);
                        ResolveInfo xpResolveInfo3 = querySkipCurrentProfileIntents(matchingFilters, intent2, resolvedType, flags4, userId);
                        if (xpResolveInfo3 != null) {
                            try {
                                List<ResolveInfo> xpResult = new ArrayList<>(1);
                                xpResult.add(xpResolveInfo3);
                                arrayMap = arrayMap2;
                                pkgName = pkgName2;
                                try {
                                    List<ResolveInfo> applyPostResolutionFilter = applyPostResolutionFilter(filterIfNotSystemUser(xpResult, userId), instantAppPkgName2, allowDynamicSplits, filterCallingUid, resolveForStart, userId, intent2);
                                    return applyPostResolutionFilter;
                                } catch (Throwable th) {
                                    th = th;
                                    throw th;
                                }
                            } catch (Throwable th2) {
                                th = th2;
                                arrayMap = arrayMap2;
                                throw th;
                            }
                        }
                        arrayMap = arrayMap2;
                        Intent intent7 = intent2;
                        pkgName = pkgName2;
                        try {
                            try {
                                List<ResolveInfo> result3 = filterIfNotSystemUser(this.mActivities.queryIntent(intent7, resolvedType, flags4, userId), userId);
                                boolean addInstant2 = isInstantAppResolutionAllowed(intent7, result3, userId, false);
                                try {
                                    boolean hasNonNegativePriorityResult = hasNonNegativePriority(result3);
                                    intent4 = intent7;
                                    boolean z = false;
                                    str = resolvedType;
                                    flags2 = flags4;
                                    try {
                                        xpResolveInfo = queryCrossProfileIntents(matchingFilters, intent4, str, flags4, userId, hasNonNegativePriorityResult);
                                        if (xpResolveInfo != null) {
                                            try {
                                                if (isUserEnabled(xpResolveInfo.targetUserId)) {
                                                    if (filterIfNotSystemUser(Collections.singletonList(xpResolveInfo), userId).size() > 0) {
                                                        z = true;
                                                    }
                                                    boolean isVisibleToUser = z;
                                                    if (isVisibleToUser) {
                                                        result3.add(xpResolveInfo);
                                                        sortResult2 = true;
                                                    }
                                                }
                                            } catch (Throwable th3) {
                                                th = th3;
                                                throw th;
                                            }
                                        }
                                        sortResult = sortResult2;
                                    } catch (Throwable th4) {
                                        th = th4;
                                    }
                                } catch (Throwable th5) {
                                    th = th5;
                                    throw th;
                                }
                                try {
                                    if (intent7.hasWebURI()) {
                                        UserInfo parent2 = getProfileParent(userId);
                                        if (parent2 != null) {
                                            try {
                                                int i3 = parent2.id;
                                                String str4 = resolvedType;
                                                int i4 = flags2;
                                                int i5 = userId;
                                                xpResolveInfo2 = xpResolveInfo;
                                                CrossProfileDomainInfo xpDomainInfo2 = getCrossProfileDomainPreferredLpr(intent7, str4, i4, i5, i3);
                                                xpDomainInfo = xpDomainInfo2;
                                                str3 = str4;
                                                str2 = i4;
                                                parent = i5;
                                            } catch (Throwable th6) {
                                                th = th6;
                                                throw th;
                                            }
                                        } else {
                                            xpResolveInfo2 = xpResolveInfo;
                                            xpDomainInfo = null;
                                            str3 = intent4;
                                            str2 = str;
                                            parent = parent2;
                                        }
                                        try {
                                            if (xpDomainInfo != null) {
                                                if (xpResolveInfo2 != null) {
                                                    result3.remove(xpResolveInfo2);
                                                }
                                                try {
                                                    if (result3.size() == 0 && !addInstant2) {
                                                        result3.add(xpDomainInfo.resolveInfo);
                                                        List<ResolveInfo> applyPostResolutionFilter2 = applyPostResolutionFilter(result3, instantAppPkgName2, allowDynamicSplits, filterCallingUid, resolveForStart, userId, intent7);
                                                        return applyPostResolutionFilter2;
                                                    }
                                                    intent5 = intent7;
                                                    instantAppPkgName = instantAppPkgName2;
                                                    i2 = userId;
                                                    packageManagerService2 = this;
                                                } catch (Throwable th7) {
                                                    th = th7;
                                                    throw th;
                                                }
                                            } else {
                                                intent5 = intent7;
                                                instantAppPkgName = instantAppPkgName2;
                                                i2 = userId;
                                                packageManagerService2 = this;
                                                try {
                                                    if (result3.size() <= 1 && !addInstant2) {
                                                        List<ResolveInfo> applyPostResolutionFilter3 = packageManagerService2.applyPostResolutionFilter(result3, instantAppPkgName, allowDynamicSplits, filterCallingUid, resolveForStart, i2, intent5);
                                                        return applyPostResolutionFilter3;
                                                    }
                                                } catch (Throwable th8) {
                                                    th = th8;
                                                    throw th;
                                                }
                                            }
                                            packageManagerService = packageManagerService2;
                                            i = i2;
                                            intent3 = intent5;
                                        } catch (Throwable th9) {
                                            th = th9;
                                        }
                                        try {
                                            result2 = packageManagerService2.filterCandidatesWithDomainPreferredActivitiesLPr(intent5, flags2, result3, xpDomainInfo, i);
                                            sortResult2 = true;
                                        } catch (Throwable th10) {
                                            th = th10;
                                            throw th;
                                        }
                                    } else {
                                        intent3 = intent7;
                                        instantAppPkgName = instantAppPkgName2;
                                        i = userId;
                                        packageManagerService = this;
                                        result2 = result3;
                                        sortResult2 = sortResult;
                                    }
                                    result = result2;
                                    addInstant = addInstant2;
                                } catch (Throwable th11) {
                                    th = th11;
                                    throw th;
                                }
                            } catch (Throwable th12) {
                                th = th12;
                            }
                        } catch (Throwable th13) {
                            th = th13;
                        }
                    } catch (Throwable th14) {
                        th = th14;
                        arrayMap = arrayMap2;
                    }
                } else {
                    arrayMap = arrayMap2;
                    intent3 = intent2;
                    instantAppPkgName = instantAppPkgName2;
                    i = userId;
                    packageManagerService = this;
                    flags2 = flags4;
                    try {
                        PackageParser.Package pkg = packageManagerService.mPackages.get(pkgName2);
                        List<ResolveInfo> result4 = pkg != null ? packageManagerService.filterIfNotSystemUser(packageManagerService.mActivities.queryIntentForPackage(intent3, resolvedType, flags2, pkg.activities, i), i) : null;
                        if (result4 != null) {
                            if (result4.size() == 0) {
                            }
                            result = result4;
                        }
                        addInstant = packageManagerService.isInstantAppResolutionAllowed(intent3, null, i, true);
                        if (result4 == null) {
                            result = new ArrayList<>();
                        }
                        result = result4;
                    } catch (Throwable th15) {
                        th = th15;
                        throw th;
                    }
                }
                List<ResolveInfo> result5 = addInstant ? packageManagerService.maybeAddInstantAppInstaller(result, intent3, resolvedType, flags2, i, resolveForStart) : result;
                if (sortResult2) {
                    Collections.sort(result5, mResolvePrioritySorter);
                }
                return packageManagerService.applyPostResolutionFilter(result5, instantAppPkgName, allowDynamicSplits, filterCallingUid, resolveForStart, i, intent3);
            } catch (Throwable th16) {
                th = th16;
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:27:0x009d  */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0134  */
    /* JADX WARN: Removed duplicated region for block: B:51:0x0166  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.util.List<android.content.pm.ResolveInfo> maybeAddInstantAppInstaller(java.util.List<android.content.pm.ResolveInfo> r22, android.content.Intent r23, java.lang.String r24, int r25, int r26, boolean r27) {
        /*
            Method dump skipped, instructions count: 370
            To view this dump add '--comments-level debug' option
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
    }

    private CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent, String resolvedType, int flags, int sourceUserId, int parentUserId) {
        List<ResolveInfo> resultTargetUser;
        List<ResolveInfo> resultTargetUser2;
        if (sUserManager.hasUserRestriction("allow_parent_profile_app_linking", sourceUserId) && (resultTargetUser = this.mActivities.queryIntent(intent, resolvedType, flags, parentUserId)) != null && !resultTargetUser.isEmpty()) {
            CrossProfileDomainInfo result = null;
            int size = resultTargetUser.size();
            int i = 0;
            while (i < size) {
                ResolveInfo riTargetUser = resultTargetUser.get(i);
                if (!riTargetUser.handleAllWebDataURI) {
                    String packageName = riTargetUser.activityInfo.packageName;
                    PackageSetting ps = this.mSettings.mPackages.get(packageName);
                    if (ps != null) {
                        long verificationState = getDomainVerificationStatusLPr(ps, parentUserId);
                        int status = (int) (verificationState >> 32);
                        if (result == null) {
                            resultTargetUser2 = resultTargetUser;
                            CrossProfileDomainInfo result2 = new CrossProfileDomainInfo();
                            result2.resolveInfo = createForwardingResolveInfoUnchecked(new IntentFilter(), sourceUserId, parentUserId);
                            result2.bestDomainVerificationStatus = status;
                            result = result2;
                        } else {
                            resultTargetUser2 = resultTargetUser;
                            result.bestDomainVerificationStatus = bestDomainVerificationStatus(status, result.bestDomainVerificationStatus);
                        }
                        i++;
                        resultTargetUser = resultTargetUser2;
                    }
                }
                resultTargetUser2 = resultTargetUser;
                i++;
                resultTargetUser = resultTargetUser2;
            }
            if (result == null || result.bestDomainVerificationStatus != 3) {
                return result;
            }
            return null;
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
        boolean z;
        PackageManagerService packageManagerService = this;
        boolean z2 = true;
        boolean blockInstant = intent.isWebIntent() && areWebInstantAppsDisabled();
        int i = resolveInfos.size() - 1;
        while (i >= 0) {
            ResolveInfo info = resolveInfos.get(i);
            if (info.isInstantAppAvailable && blockInstant) {
                resolveInfos.remove(i);
            } else {
                if (allowDynamicSplits && info.activityInfo != null && info.activityInfo.splitName != null && !ArrayUtils.contains(info.activityInfo.applicationInfo.splitNames, info.activityInfo.splitName)) {
                    if (packageManagerService.mInstantAppInstallerActivity == null) {
                        if (DEBUG_INSTALL) {
                            Slog.v(TAG, "No installer - not adding it to the ResolveInfo list");
                        }
                        resolveInfos.remove(i);
                    } else if (blockInstant && packageManagerService.isInstantApp(info.activityInfo.packageName, userId)) {
                        resolveInfos.remove(i);
                    } else {
                        if (DEBUG_INSTALL) {
                            Slog.v(TAG, "Adding installer to the ResolveInfo list");
                        }
                        ResolveInfo installerInfo = new ResolveInfo(packageManagerService.mInstantAppInstallerInfo);
                        ComponentName installFailureActivity = packageManagerService.findInstallFailureActivity(info.activityInfo.packageName, filterCallingUid, userId);
                        installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo(installFailureActivity, info.activityInfo.packageName, info.activityInfo.applicationInfo.longVersionCode, info.activityInfo.splitName);
                        installerInfo.filter = new IntentFilter();
                        installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                        installerInfo.labelRes = info.resolveLabelResId();
                        installerInfo.icon = info.resolveIconResId();
                        z = true;
                        installerInfo.isInstantAppAvailable = true;
                        resolveInfos.set(i, installerInfo);
                    }
                } else {
                    z = z2;
                    if (ephemeralPkgName != null && !ephemeralPkgName.equals(info.activityInfo.packageName) && (!resolveForStart || ((!intent.isWebIntent() && (intent.getFlags() & 2048) == 0) || intent.getPackage() != null || intent.getComponent() != null))) {
                        boolean isEphemeralApp = info.activityInfo.applicationInfo.isInstantApp();
                        if (isEphemeralApp || (info.activityInfo.flags & 1048576) == 0) {
                            resolveInfos.remove(i);
                        }
                    }
                }
                i--;
                z2 = z;
                packageManagerService = this;
            }
            z = z2;
            i--;
            z2 = z;
            packageManagerService = this;
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
        int n;
        int maxMatchPrio;
        PackageManagerService packageManagerService = this;
        List<ResolveInfo> list = candidates;
        int i = userId;
        boolean debug = (intent.getFlags() & 8) != 0;
        if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
            Slog.v(TAG, "Filtering results with preferred activities. Candidates count: " + candidates.size());
        }
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
                    int n2 = 0;
                    while (n2 < count) {
                        ResolveInfo info = list.get(n2);
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
                                    try {
                                        if (DEBUG_DOMAIN_VERIFICATION || debug) {
                                            StringBuilder sb = new StringBuilder();
                                            try {
                                                sb.append("  + always: ");
                                                sb.append(info.activityInfo.packageName);
                                                sb.append(" : linkgen=");
                                                sb.append(linkGeneration);
                                                Slog.i(TAG, sb.toString());
                                            } catch (Throwable th) {
                                                th = th;
                                                throw th;
                                            }
                                        }
                                        info.preferredOrder = linkGeneration;
                                        alwaysList.add(info);
                                    } catch (Throwable th2) {
                                        th = th2;
                                        throw th;
                                    }
                                } else if (status == 3) {
                                    if (DEBUG_DOMAIN_VERIFICATION || debug) {
                                        Slog.i(TAG, "  + never: " + info.activityInfo.packageName);
                                    }
                                    neverList.add(info);
                                } else if (status == 4) {
                                    if (DEBUG_DOMAIN_VERIFICATION || debug) {
                                        Slog.i(TAG, "  + always-ask: " + info.activityInfo.packageName);
                                    }
                                    alwaysAskList.add(info);
                                } else {
                                    if (status != 0 && status != 1) {
                                    }
                                    if (DEBUG_DOMAIN_VERIFICATION || debug) {
                                        Slog.i(TAG, "  + ask: " + info.activityInfo.packageName);
                                    }
                                    undefinedList.add(info);
                                }
                            }
                        }
                        n2++;
                        count = count2;
                        packageManagerService = this;
                        list = candidates;
                        i = userId;
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
                        n = 0;
                        result.addAll(alwaysAskList);
                        includeBrowser = true;
                    } else {
                        n = 0;
                    }
                    if (includeBrowser) {
                        if (DEBUG_DOMAIN_VERIFICATION) {
                            Slog.v(TAG, "   ...including browsers in candidate set");
                        }
                        if ((matchFlags & 131072) != 0) {
                            result.addAll(matchAllList);
                        } else {
                            try {
                                String defaultBrowserPackageName = getDefaultBrowserPackageName(userId);
                                int numCandidates = matchAllList.size();
                                int maxMatchPrio2 = 0;
                                ResolveInfo defaultBrowserMatch = null;
                                while (true) {
                                    int numCandidates2 = numCandidates;
                                    if (n >= numCandidates2) {
                                        break;
                                    }
                                    ResolveInfo info2 = matchAllList.get(n);
                                    if (info2.priority > maxMatchPrio2) {
                                        maxMatchPrio2 = info2.priority;
                                    }
                                    if (info2.activityInfo.packageName.equals(defaultBrowserPackageName)) {
                                        if (defaultBrowserMatch != null) {
                                            int i3 = defaultBrowserMatch.priority;
                                            maxMatchPrio = maxMatchPrio2;
                                            int maxMatchPrio3 = info2.priority;
                                            if (i3 < maxMatchPrio3) {
                                            }
                                        } else {
                                            maxMatchPrio = maxMatchPrio2;
                                        }
                                        if (debug) {
                                            Slog.v(TAG, "Considering default browser match " + info2);
                                        }
                                        defaultBrowserMatch = info2;
                                    } else {
                                        maxMatchPrio = maxMatchPrio2;
                                    }
                                    n++;
                                    numCandidates = numCandidates2;
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
                            } catch (Throwable th3) {
                                th = th3;
                                throw th;
                            }
                        }
                        int maxMatchPrio4 = result.size();
                        if (maxMatchPrio4 == 0) {
                            result.addAll(candidates);
                            result.removeAll(neverList);
                        }
                    }
                    if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
                        Slog.v(TAG, "Filtered results with preferred activities. New candidates count: " + result.size());
                        Iterator<ResolveInfo> it2 = result.iterator();
                        while (it2.hasNext()) {
                            Slog.v(TAG, "  + " + it2.next().activityInfo);
                        }
                    }
                    return result;
                } catch (Throwable th4) {
                    th = th4;
                }
            } catch (Throwable th5) {
                th = th5;
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
        List<ResolveInfo> resultTargetUser = this.mActivities.queryIntent(intent, resolvedType, flags, targetUserId);
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

    /* JADX WARN: Removed duplicated region for block: B:40:0x00db  */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0101  */
    /* JADX WARN: Removed duplicated region for block: B:61:0x0171  */
    /* JADX WARN: Removed duplicated region for block: B:62:0x0179  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptionsInternal(android.content.ComponentName r22, android.content.Intent[] r23, java.lang.String[] r24, android.content.Intent r25, java.lang.String r26, int r27, int r28) {
        /*
            Method dump skipped, instructions count: 640
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.queryIntentActivityOptionsInternal(android.content.ComponentName, android.content.Intent[], java.lang.String[], android.content.Intent, java.lang.String, int, int):java.util.List");
    }

    public ParceledListSlice<ResolveInfo> queryIntentReceivers(Intent intent, String resolvedType, int flags, int userId) {
        return new ParceledListSlice<>(queryIntentReceiversInternal(intent, resolvedType, flags, userId, false));
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:60:0x00c6  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.util.List<android.content.pm.ResolveInfo> queryIntentReceiversInternal(android.content.Intent r21, java.lang.String r22, int r23, int r24, boolean r25) {
        /*
            Method dump skipped, instructions count: 312
            To view this dump add '--comments-level debug' option
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
        if (!sUserManager.exists(userId)) {
            return Collections.emptyList();
        }
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
                boolean matchInstantApp = (8388608 & flags2) != 0;
                boolean matchVisibleToInstantAppOnly = (16777216 & flags2) != 0;
                boolean isCallerInstantApp = instantAppPkgName != null;
                boolean isTargetSameInstantApp = comp.getPackageName().equals(instantAppPkgName);
                boolean isTargetInstantApp = (si.applicationInfo.privateFlags & 128) != 0;
                boolean isTargetHiddenFromInstantApp = (si.flags & 1048576) == 0;
                boolean blockResolution = !isTargetSameInstantApp && (!(matchInstantApp || isCallerInstantApp || !isTargetInstantApp) || (matchVisibleToInstantAppOnly && isCallerInstantApp && isTargetHiddenFromInstantApp));
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
                try {
                    String pkgName = intent2.getPackage();
                    if (pkgName == null) {
                        return applyPostServiceResolutionFilter(this.mServices.queryIntent(intent2, resolvedType, flags2, userId), instantAppPkgName);
                    }
                    PackageParser.Package pkg = this.mPackages.get(pkgName);
                    if (pkg != null) {
                        return applyPostServiceResolutionFilter(this.mServices.queryIntentForPackage(intent2, resolvedType, flags2, pkg.services, userId), instantAppPkgName);
                    }
                    return Collections.emptyList();
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
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
        if (!sUserManager.exists(userId)) {
            return Collections.emptyList();
        }
        int callingUid = Binder.getCallingUid();
        String instantAppPkgName = getInstantAppPackageName(callingUid);
        int flags2 = updateFlagsForResolve(flags, userId, intent, callingUid, false);
        ComponentName comp2 = intent.getComponent();
        if (comp2 != null || intent.getSelector() == null) {
            intent2 = intent;
            comp = comp2;
        } else {
            Intent intent3 = intent.getSelector();
            comp = intent3.getComponent();
            intent2 = intent3;
        }
        if (comp != null) {
            List<ResolveInfo> list = new ArrayList<>(1);
            ProviderInfo pi = getProviderInfo(comp, flags2, userId);
            if (pi != null) {
                boolean matchInstantApp = (8388608 & flags2) != 0;
                boolean matchVisibleToInstantAppOnly = (16777216 & flags2) != 0;
                boolean isCallerInstantApp = instantAppPkgName != null;
                boolean isTargetSameInstantApp = comp.getPackageName().equals(instantAppPkgName);
                boolean isTargetInstantApp = (pi.applicationInfo.privateFlags & 128) != 0;
                boolean isTargetHiddenFromInstantApp = (pi.flags & 1048576) == 0;
                boolean blockResolution = !isTargetSameInstantApp && (!(matchInstantApp || isCallerInstantApp || !isTargetInstantApp) || (matchVisibleToInstantAppOnly && isCallerInstantApp && isTargetHiddenFromInstantApp));
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
                    if (pkgName == null) {
                        return applyPostContentProviderResolutionFilter(this.mProviders.queryIntent(intent2, resolvedType, flags2, userId), instantAppPkgName);
                    }
                    PackageParser.Package pkg = this.mPackages.get(pkgName);
                    if (pkg != null) {
                        return applyPostContentProviderResolutionFilter(this.mProviders.queryIntentForPackage(intent2, resolvedType, flags2, pkg.providers, userId), instantAppPkgName);
                    }
                    return Collections.emptyList();
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
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
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get installed packages");
            synchronized (this.mPackages) {
                try {
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
                    parceledListSlice = new ParceledListSlice<>(list);
                } catch (Throwable th) {
                    throw th;
                }
            }
            return parceledListSlice;
        }
        return ParceledListSlice.emptyList();
    }

    private void addPackageHoldingPermissions(ArrayList<PackageInfo> list, PackageSetting ps, String[] permissions, boolean[] tmp, int flags, int userId) {
        PackageInfo pi;
        PermissionsState permissionsState = ps.getPermissionsState();
        int numMatch = 0;
        for (int numMatch2 = 0; numMatch2 < permissions.length; numMatch2++) {
            String permission = permissions[numMatch2];
            if (permissionsState.hasPermission(permission, userId)) {
                tmp[numMatch2] = true;
                numMatch++;
            } else {
                tmp[numMatch2] = false;
            }
        }
        if (numMatch != 0 && (pi = generatePackageInfo(ps, flags, userId)) != null) {
            if ((flags & 4096) == 0) {
                if (numMatch == permissions.length) {
                    pi.requestedPermissions = permissions;
                } else {
                    pi.requestedPermissions = new String[numMatch];
                    int numMatch3 = 0;
                    for (int i = 0; i < permissions.length; i++) {
                        if (tmp[i]) {
                            pi.requestedPermissions[numMatch3] = permissions[i];
                            numMatch3++;
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
        if (!sUserManager.exists(userId)) {
            return ParceledListSlice.emptyList();
        }
        int flags2 = updateFlagsForPackage(flags, userId, permissions);
        ?? r4 = 1;
        this.mPermissionManager.enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "get packages holding permissions");
        boolean listUninstalled = (flags2 & 4202496) != 0;
        ArrayMap<String, PackageParser.Package> arrayMap2 = this.mPackages;
        synchronized (arrayMap2) {
            try {
                try {
                    ArrayList<PackageInfo> list = new ArrayList<>();
                    boolean[] tmpBools = new boolean[permissions.length];
                    if (listUninstalled) {
                        for (PackageSetting ps : this.mSettings.mPackages.values()) {
                            addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags2, userId);
                        }
                    } else {
                        for (PackageParser.Package pkg : this.mPackages.values()) {
                            PackageSetting ps2 = (PackageSetting) pkg.mExtras;
                            if (ps2 != null) {
                                arrayMap = arrayMap2;
                                addPackageHoldingPermissions(list, ps2, permissions, tmpBools, flags2, userId);
                            } else {
                                arrayMap = arrayMap2;
                            }
                            arrayMap2 = arrayMap;
                        }
                    }
                    ArrayMap<String, PackageParser.Package> arrayMap3 = arrayMap2;
                    ParceledListSlice<PackageInfo> parceledListSlice = new ParceledListSlice<>(list);
                    return parceledListSlice;
                } catch (Throwable th) {
                    th = th;
                    r4 = arrayMap2;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public ParceledListSlice<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        ArrayList<ApplicationInfo> list;
        ParceledListSlice<ApplicationInfo> parceledListSlice;
        ApplicationInfo ai;
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return ParceledListSlice.emptyList();
        }
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForApplication(flags, userId, null);
            boolean listUninstalled = (4202496 & flags2) != 0;
            this.mPermissionManager.enforceCrossUserPermission(callingUid, userId, false, false, "get installed application info");
            synchronized (this.mPackages) {
                try {
                    if (listUninstalled) {
                        list = new ArrayList<>(this.mSettings.mPackages.size());
                        for (PackageSetting ps : this.mSettings.mPackages.values()) {
                            int effectiveFlags = flags2;
                            if (ps.isSystem()) {
                                effectiveFlags |= DumpState.DUMP_CHANGES;
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
                    parceledListSlice = new ParceledListSlice<>(list);
                } catch (Throwable th) {
                    throw th;
                }
            }
            return parceledListSlice;
        }
        return ParceledListSlice.emptyList();
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
                    boolean matchesAware = false;
                    boolean matchesUnaware = ((262144 & flags) == 0 || p.applicationInfo.isDirectBootAware()) ? false : true;
                    if ((524288 & flags) != 0 && p.applicationInfo.isDirectBootAware()) {
                        matchesAware = true;
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
        PackageSetting packageSetting;
        if (sUserManager.exists(userId)) {
            int flags2 = updateFlagsForComponent(flags, userId, name);
            int callingUid = Binder.getCallingUid();
            synchronized (this.mPackages) {
                PackageParser.Provider provider = this.mProvidersByAuthority.get(name);
                if (provider != null) {
                    packageSetting = this.mSettings.mPackages.get(provider.owner.packageName);
                } else {
                    packageSetting = null;
                }
                PackageSetting ps = packageSetting;
                if (ps != null) {
                    if (this.mSettings.isEnabledAndMatchLPr(provider.info, flags2, userId)) {
                        ComponentName component = new ComponentName(provider.info.packageName, provider.info.name);
                        if (filterAppAccessLPr(ps, callingUid, component, 4, userId)) {
                            return null;
                        }
                        return PackageParser.generateProviderInfo(provider, flags2, ps.readUserState(userId), userId);
                    }
                    return null;
                }
                return null;
            }
        }
        return null;
    }

    @Deprecated
    public void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo) {
        ProviderInfo info;
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        synchronized (this.mPackages) {
            int userId = UserHandle.getCallingUserId();
            for (Map.Entry<String, PackageParser.Provider> entry : this.mProvidersByAuthority.entrySet()) {
                PackageParser.Provider p = entry.getValue();
                PackageSetting ps = this.mSettings.mPackages.get(p.owner.packageName);
                if (ps != null && p.syncable && ((!this.mSafeMode || (p.info.applicationInfo.flags & 1) != 0) && (info = PackageParser.generateProviderInfo(p, 0, ps.readUserState(userId), userId)) != null)) {
                    outNames.add(entry.getKey());
                    outInfo.add(info);
                }
            }
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:62:0x00fc -> B:63:0x00fd). Please submit an issue!!! */
    public ParceledListSlice<ProviderInfo> queryContentProviders(String processName, int uid, int flags, String metaDataKey) {
        Iterator<PackageParser.Provider> i;
        int callingUid = Binder.getCallingUid();
        int userId = processName != null ? UserHandle.getUserId(uid) : UserHandle.getCallingUserId();
        if (!sUserManager.exists(userId)) {
            return ParceledListSlice.emptyList();
        }
        int flags2 = updateFlagsForComponent(flags, userId, processName);
        synchronized (this.mPackages) {
            try {
                Iterator<PackageParser.Provider> i2 = this.mProviders.mProviders.values().iterator();
                ArrayList<ProviderInfo> finalList = null;
                while (i2.hasNext()) {
                    try {
                        PackageParser.Provider p = i2.next();
                        PackageSetting ps = this.mSettings.mPackages.get(p.owner.packageName);
                        if (ps != null && p.info.authority != null) {
                            if (processName != null) {
                                try {
                                    if (p.info.processName.equals(processName) && UserHandle.isSameApp(p.info.applicationInfo.uid, uid)) {
                                    }
                                    i = i2;
                                    i2 = i;
                                } catch (Throwable th) {
                                    th = th;
                                    throw th;
                                }
                            }
                            if (this.mSettings.isEnabledAndMatchLPr(p.info, flags2, userId)) {
                                if (metaDataKey == null || (p.metaData != null && p.metaData.containsKey(metaDataKey))) {
                                    ComponentName component = new ComponentName(p.info.packageName, p.info.name);
                                    i = i2;
                                    if (!filterAppAccessLPr(ps, callingUid, component, 4, userId)) {
                                        ArrayList<ProviderInfo> finalList2 = finalList == null ? new ArrayList<>(3) : finalList;
                                        ProviderInfo info = PackageParser.generateProviderInfo(p, flags2, ps.readUserState(userId), userId);
                                        if (info != null) {
                                            finalList2.add(info);
                                        }
                                        finalList = finalList2;
                                        i2 = i;
                                    }
                                } else {
                                    i = i2;
                                }
                                i2 = i;
                            }
                        }
                        i = i2;
                        i2 = i;
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
                if (finalList != null) {
                    Collections.sort(finalList, mProviderInitOrderSorter);
                    return new ParceledListSlice<>(finalList);
                }
                return ParceledListSlice.emptyList();
            } catch (Throwable th3) {
                th = th3;
            }
        }
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private void scanDirLI(java.io.File r20, int r21, int r22, long r23) {
        /*
            Method dump skipped, instructions count: 437
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.scanDirLI(java.io.File, int, int, long):void");
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

    public static void reportSettingsProblem(int priority, String msg) {
        PackageManagerServiceUtils.logCriticalInfo(priority, msg);
    }

    private void collectCertificatesLI(PackageSetting ps, PackageParser.Package pkg, boolean forceCollect, boolean skipVerify) throws PackageManagerException {
        long lastModifiedTime = this.mIsPreNMR1Upgrade ? new File(pkg.codePath).lastModified() : PackageManagerServiceUtils.getLastModifiedTime(pkg);
        if (ps != null && !forceCollect && ps.codePathString.equals(pkg.codePath) && ps.timeStamp == lastModifiedTime && !isCompatSignatureUpdateNeeded(pkg) && !isRecoverSignatureUpdateNeeded(pkg)) {
            if (ps.signatures.mSigningDetails.signatures != null && ps.signatures.mSigningDetails.signatures.length != 0 && ps.signatures.mSigningDetails.signatureSchemeVersion != 0) {
                pkg.mSigningDetails = new PackageParser.SigningDetails(ps.signatures.mSigningDetails);
                return;
            }
            Slog.w(TAG, "PackageSetting for " + ps.name + " is missing signatures.  Collecting certs again to recover them.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(pkg.codePath);
            sb.append(" changed; collecting certs");
            sb.append(forceCollect ? " (forced)" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
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
        if (originalPkgSetting == null || !isUpgrade() || originalPkgSetting.versionCode == currentPkg.mVersionCode) {
            return;
        }
        clearAppProfilesLIF(currentPkg, -1);
        if (DEBUG_INSTALL) {
            Slog.d(TAG, originalPkgSetting.name + " clear profile due to version change " + originalPkgSetting.versionCode + " != " + currentPkg.mVersionCode);
        }
    }

    private PackageParser.Package scanPackageTracedLI(File scanFile, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        Trace.traceBegin(262144L, "scanPackage [" + scanFile.toString() + "]");
        try {
            return scanPackageLI(scanFile, parseFlags, scanFlags, currentTime, user);
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    private PackageParser.Package scanPackageLI(File scanFile, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        if (DEBUG_INSTALL) {
            Slog.d(TAG, "Parsing: " + scanFile);
        }
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

    private PackageParser.Package scanPackageChildLI(PackageParser.Package pkg, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        int i = scanFlags;
        if ((i & 1024) == 0) {
            if (pkg.childPackages != null && pkg.childPackages.size() > 0) {
                i |= 1024;
            }
        } else {
            i &= -1025;
        }
        int scanFlags2 = i;
        PackageParser.Package scannedPkg = addForInitLI(pkg, parseFlags, scanFlags2, currentTime, user);
        int i2 = 0;
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        while (true) {
            int i3 = i2;
            if (i3 >= childCount) {
                break;
            }
            PackageParser.Package childPackage = (PackageParser.Package) pkg.childPackages.get(i3);
            addForInitLI(childPackage, parseFlags, scanFlags2, currentTime, user);
            i2 = i3 + 1;
        }
        return (scanFlags2 & 1024) != 0 ? scanPackageChildLI(pkg, parseFlags, scanFlags2, currentTime, user) : scannedPkg;
    }

    private boolean canSkipFullPackageVerification(PackageParser.Package pkg) {
        if (canSkipFullApkVerification(pkg.baseCodePath)) {
            if (!ArrayUtils.isEmpty(pkg.splitCodePaths)) {
                for (int i = 0; i < pkg.splitCodePaths.length; i++) {
                    if (!canSkipFullApkVerification(pkg.splitCodePaths[i])) {
                        return false;
                    }
                }
                return true;
            }
            return true;
        }
        return false;
    }

    private boolean canSkipFullApkVerification(String apkPath) {
        try {
            byte[] rootHashObserved = VerityUtils.generateFsverityRootHash(apkPath);
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private android.content.pm.PackageParser.Package addForInitLI(android.content.pm.PackageParser.Package r37, int r38, int r39, long r40, android.os.UserHandle r42) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 1091
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.addForInitLI(android.content.pm.PackageParser$Package, int, int, long, android.os.UserHandle):android.content.pm.PackageParser$Package");
    }

    private static void renameStaticSharedLibraryPackage(PackageParser.Package pkg) {
        pkg.setPackageName(pkg.packageName + STATIC_SHARED_LIB_DELIMITER + pkg.staticSharedLibVersion);
    }

    private static String fixProcessName(String defProcessName, String processName) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    private static final void enforceSystemOrRoot(String message) {
        int uid = Binder.getCallingUid();
        if (uid != 1000 && uid != 0) {
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
                            ActivityManager.getService().showBootMessage(this.mContext.getResources().getString(17039476), true);
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

    public void updatePackagesIfNeeded() {
        List<PackageParser.Package> pkgs;
        enforceSystemOrRoot("Only the system can request package update");
        boolean causeUpgrade = isUpgrade();
        boolean causeFirstBoot = isFirstBoot() || this.mIsPreNUpgrade;
        boolean causePrunedCache = VMRuntime.didPruneDalvikCache();
        if (!causeUpgrade && !causeFirstBoot && !causePrunedCache) {
            return;
        }
        synchronized (this.mPackages) {
            pkgs = PackageManagerServiceUtils.getPackagesForDexopt(this.mPackages.values(), this);
        }
        long startTime = System.nanoTime();
        int[] stats = performDexOptUpgrade(pkgs, causeFirstBoot, causeFirstBoot ? 0 : 1, false);
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

    /* JADX WARN: Removed duplicated region for block: B:43:0x00fc  */
    /* JADX WARN: Removed duplicated region for block: B:48:0x0120  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int[] performDexOptUpgrade(java.util.List<android.content.pm.PackageParser.Package> r19, boolean r20, int r21, boolean r22) {
        /*
            Method dump skipped, instructions count: 472
            To view this dump add '--comments-level debug' option
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

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mPackages")
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
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$opO5L-t6aW9gAx6B5CGlW6sAaX8
                @Override // java.lang.Runnable
                public final void run() {
                    PackageManagerService.lambda$registerDexModule$3(callback, dexModulePath, result);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$registerDexModule$3(IDexModuleRegisterCallback callback, String dexModulePath, DexManager.RegisterDexModuleResult result) {
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
        addBootEvent("PMS: performDexOpt:" + options.getPackageName());
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

    private int performDexOptInternalWithDependenciesLI(PackageParser.Package p, DexoptOptions options) {
        PackageDexOptimizer pdo = options.isForce() ? new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(this.mPackageDexOptimizer) : this.mPackageDexOptimizer;
        Collection<PackageParser.Package> deps = findSharedNonSystemLibraries(p);
        String[] instructionSets = InstructionSets.getAppDexInstructionSets(p.applicationInfo);
        if (!deps.isEmpty()) {
            DexoptOptions libraryOptions = new DexoptOptions(options.getPackageName(), options.getCompilationReason(), options.getCompilerFilter(), options.getSplitName(), options.getFlags() | 64);
            for (PackageParser.Package depPackage : deps) {
                pdo.performDexOpt(depPackage, null, instructionSets, getOrCreateCompilerPackageStats(depPackage), this.mDexManager.getPackageUseInfoOrDefault(depPackage.packageName), libraryOptions);
            }
        }
        return pdo.performDexOpt(p, p.usesLibraryFiles, instructionSets, getOrCreateCompilerPackageStats(p), this.mDexManager.getPackageUseInfoOrDefault(p.packageName), options);
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
        return BackgroundDexOptService.runIdleOptimizationsNow(this, this.mContext, packageNames);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<PackageParser.Package> findSharedNonSystemLibraries(PackageParser.Package p) {
        if (p.usesLibraries != null || p.usesOptionalLibraries != null || p.usesStaticLibraries != null) {
            ArrayList<PackageParser.Package> retValue = new ArrayList<>();
            Set<String> collectedNames = new HashSet<>();
            findSharedNonSystemLibrariesRecursive(p, retValue, collectedNames);
            retValue.remove(p);
            return retValue;
        }
        return Collections.emptyList();
    }

    private void findSharedNonSystemLibrariesRecursive(PackageParser.Package p, ArrayList<PackageParser.Package> collected, Set<String> collectedNames) {
        if (!collectedNames.contains(p.packageName)) {
            collectedNames.add(p.packageName);
            collected.add(p);
            if (p.usesLibraries != null) {
                findSharedNonSystemLibrariesRecursive(p.usesLibraries, null, collected, collectedNames);
            }
            if (p.usesOptionalLibraries != null) {
                findSharedNonSystemLibrariesRecursive(p.usesOptionalLibraries, null, collected, collectedNames);
            }
            if (p.usesStaticLibraries != null) {
                findSharedNonSystemLibrariesRecursive(p.usesStaticLibraries, p.usesStaticLibrariesVersions, collected, collectedNames);
            }
        }
    }

    private void findSharedNonSystemLibrariesRecursive(ArrayList<String> libs, long[] versions, ArrayList<PackageParser.Package> collected, Set<String> collectedNames) {
        int libNameCount = libs.size();
        for (int i = 0; i < libNameCount; i++) {
            String libName = libs.get(i);
            long version = (versions == null || versions.length != libNameCount) ? -1L : versions[i];
            PackageParser.Package libPkg = findSharedNonSystemLibrary(libName, version);
            if (libPkg != null) {
                findSharedNonSystemLibrariesRecursive(libPkg, collected, collectedNames);
            }
        }
    }

    private PackageParser.Package findSharedNonSystemLibrary(String name, long version) {
        synchronized (this.mPackages) {
            SharedLibraryEntry libEntry = getSharedLibraryEntryLPr(name, version);
            if (libEntry != null) {
                return this.mPackages.get(libEntry.apk);
            }
            return null;
        }
    }

    private SharedLibraryEntry getSharedLibraryEntryLPr(String name, long version) {
        LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.get(name);
        if (versionedLib == null) {
            return null;
        }
        return versionedLib.get(version);
    }

    private SharedLibraryEntry getLatestSharedLibraVersionLPr(PackageParser.Package pkg) {
        LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.get(pkg.staticSharedLibName);
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

    public void shutdown() {
        this.mPackageUsage.writeNow(this.mPackages);
        this.mCompilerStats.writeNow();
        this.mDexManager.writePackageDexUsageNow();
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

    /* JADX INFO: Access modifiers changed from: private */
    public void clearAppDataLIF(PackageParser.Package pkg, int userId, int flags) {
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

    private void destroyAppProfilesLIF(PackageParser.Package pkg, int userId) {
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

    private void removeDexFiles(PackageParser.Package pkg) {
        if (pkg == null) {
            Log.v(TAG, "PackageParser.Package is null");
            return;
        }
        String codePath = pkg.applicationInfo.getCodePath();
        String[] instructionSets = InstructionSets.getAppDexInstructionSets(pkg.applicationInfo);
        File codeFile = codePath != null ? new File(codePath) : null;
        List<String> allCodePaths = Collections.EMPTY_LIST;
        if (codeFile != null && codeFile.exists()) {
            try {
                PackageParser.PackageLite pkgLite = PackageParser.parsePackageLite(codeFile, 0);
                allCodePaths = pkgLite.getAllCodePaths();
            } catch (PackageParser.PackageParserException e) {
            }
            if (!allCodePaths.isEmpty()) {
                if (instructionSets == null) {
                    throw new IllegalStateException("instructionSet == null");
                }
                String[] dexCodeInstructionSets = InstructionSets.getDexCodeInstructionSets(instructionSets);
                for (String codePathtemp : allCodePaths) {
                    for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                        try {
                            Log.v(TAG, "removeDexFiles codePath:" + codePathtemp + " dexCodeInstructionSet:" + dexCodeInstructionSet);
                            this.mInstaller.rmdex(codePathtemp, dexCodeInstructionSet);
                        } catch (Installer.InstallerException e2) {
                        }
                    }
                }
                return;
            }
            return;
        }
        Log.v(TAG, "codeFile is null,return");
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

    private void addSharedLibraryLPr(Set<String> usesLibraryFiles, SharedLibraryEntry file, PackageParser.Package changingLib) {
        if (file.path != null) {
            usesLibraryFiles.add(file.path);
            return;
        }
        PackageParser.Package p = this.mPackages.get(file.apk);
        if (changingLib != null && changingLib.packageName.equals(file.apk) && (p == null || p.packageName.equals(changingLib.packageName))) {
            p = changingLib;
        }
        if (p != null) {
            usesLibraryFiles.addAll(p.getAllCodePaths());
            if (p.usesLibraryFiles != null) {
                Collections.addAll(usesLibraryFiles, p.usesLibraryFiles);
            }
        }
    }

    private void updateSharedLibrariesLPr(PackageParser.Package pkg, PackageParser.Package changingLib) throws PackageManagerException {
        if (pkg == null) {
            return;
        }
        Set<String> usesLibraryFiles = null;
        if (pkg.usesLibraries != null) {
            usesLibraryFiles = addSharedLibrariesLPw(pkg.usesLibraries, null, null, pkg.packageName, changingLib, true, pkg.applicationInfo.targetSdkVersion, null);
        }
        if (pkg.usesStaticLibraries != null) {
            usesLibraryFiles = addSharedLibrariesLPw(pkg.usesStaticLibraries, pkg.usesStaticLibrariesVersions, pkg.usesStaticLibrariesCertDigests, pkg.packageName, changingLib, true, pkg.applicationInfo.targetSdkVersion, usesLibraryFiles);
        }
        if (pkg.usesOptionalLibraries != null) {
            usesLibraryFiles = addSharedLibrariesLPw(pkg.usesOptionalLibraries, null, null, pkg.packageName, changingLib, false, pkg.applicationInfo.targetSdkVersion, usesLibraryFiles);
        }
        if (!ArrayUtils.isEmpty(usesLibraryFiles)) {
            pkg.usesLibraryFiles = (String[]) usesLibraryFiles.toArray(new String[usesLibraryFiles.size()]);
        } else {
            pkg.usesLibraryFiles = null;
        }
    }

    private Set<String> addSharedLibrariesLPw(List<String> requestedLibraries, long[] requiredVersions, String[][] requiredCertDigests, String packageName, PackageParser.Package changingLib, boolean required, int targetSdk, Set<String> outUsedLibraries) throws PackageManagerException {
        int libCount;
        int libCount2 = requestedLibraries.size();
        Set<String> outUsedLibraries2 = outUsedLibraries;
        int i = 0;
        while (i < libCount2) {
            String libName = requestedLibraries.get(i);
            long libVersion = requiredVersions != null ? requiredVersions[i] : -1L;
            SharedLibraryEntry libEntry = getSharedLibraryEntryLPr(libName, libVersion);
            if (libEntry == null) {
                if (required) {
                    throw new PackageManagerException(-9, "Package " + packageName + " requires unavailable shared library " + libName + "; failing!");
                }
                if (DEBUG_SHARED_LIBRARIES) {
                    Slog.i(TAG, "Package " + packageName + " desires unavailable shared library " + libName + "; ignoring!");
                }
                libCount = libCount2;
            } else {
                if (requiredVersions != null && requiredCertDigests != null) {
                    if (libEntry.info.getLongVersion() != requiredVersions[i]) {
                        throw new PackageManagerException(-9, "Package " + packageName + " requires unavailable static shared library " + libName + " version " + libEntry.info.getLongVersion() + "; failing!");
                    }
                    PackageParser.Package libPkg = this.mPackages.get(libEntry.apk);
                    if (libPkg == null) {
                        throw new PackageManagerException(-9, "Package " + packageName + " requires unavailable static shared library; failing!");
                    }
                    String[] expectedCertDigests = requiredCertDigests[i];
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
                            int certCount2 = certCount;
                            String[] libCertDigests2 = libCertDigests;
                            if (libCertDigests[j].equalsIgnoreCase(expectedCertDigests[j])) {
                                j++;
                                certCount = certCount2;
                                libCertDigests = libCertDigests2;
                            } else {
                                throw new PackageManagerException(-9, "Package " + packageName + " requires differently signed static shared library; failing!");
                            }
                        }
                        libCount = libCount2;
                    } else {
                        libCount = libCount2;
                        if (!libPkg.mSigningDetails.hasSha256Certificate(ByteStringUtils.fromHexToByteArray(expectedCertDigests[0]))) {
                            throw new PackageManagerException(-9, "Package " + packageName + " requires differently signed static shared library; failing!");
                        }
                    }
                } else {
                    libCount = libCount2;
                }
                if (outUsedLibraries2 == null) {
                    outUsedLibraries2 = new LinkedHashSet<>();
                }
                addSharedLibraryLPr(outUsedLibraries2, libEntry, changingLib);
            }
            i++;
            libCount2 = libCount;
        }
        return outUsedLibraries2;
    }

    private static boolean hasString(List<String> list, List<String> which) {
        if (list == null) {
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

    private ArrayList<PackageParser.Package> updateAllSharedLibrariesLPw(PackageParser.Package changingPkg) {
        ArrayList<PackageParser.Package> res = null;
        for (PackageParser.Package pkg : this.mPackages.values()) {
            if (changingPkg != null && !hasString(pkg.usesLibraries, changingPkg.libraryNames) && !hasString(pkg.usesOptionalLibraries, changingPkg.libraryNames) && !ArrayUtils.contains(pkg.usesStaticLibraries, changingPkg.staticSharedLibName)) {
                return null;
            }
            if (res == null) {
                res = new ArrayList<>();
            }
            res.add(pkg);
            try {
                updateSharedLibrariesLPr(pkg, changingPkg);
            } catch (PackageManagerException e) {
                if (!pkg.isSystem() || pkg.isUpdatedSystemApp()) {
                    int flags = pkg.isUpdatedSystemApp() ? 1 : 0;
                    deletePackageLIF(pkg.packageName, null, true, sUserManager.getUserIds(), flags, null, true, null);
                }
                Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
            }
        }
        return res;
    }

    private PackageParser.Package scanPackageTracedLI(PackageParser.Package pkg, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        int i = scanFlags;
        Trace.traceBegin(262144L, "scanPackage");
        if ((i & 1024) != 0) {
            i &= -1025;
        } else if (pkg.childPackages != null && pkg.childPackages.size() > 0) {
            i |= 1024;
        }
        int scanFlags2 = i;
        try {
            PackageParser.Package scannedPkg = scanPackageNewLI(pkg, parseFlags, scanFlags2, currentTime, user);
            int i2 = 0;
            int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
            while (true) {
                int i3 = i2;
                if (i3 >= childCount) {
                    break;
                }
                PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i3);
                scanPackageNewLI(childPkg, parseFlags, scanFlags2, currentTime, user);
                i2 = i3 + 1;
            }
            Trace.traceEnd(262144L);
            return (scanFlags2 & 1024) != 0 ? scanPackageTracedLI(pkg, parseFlags, scanFlags2, currentTime, user) : scannedPkg;
        } catch (Throwable th) {
            Trace.traceEnd(262144L);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ScanResult {
        public final List<String> changedAbiCodePath;
        public final PackageSetting pkgSetting;
        public final boolean success;

        public ScanResult(boolean success, PackageSetting pkgSetting, List<String> changedAbiCodePath) {
            this.success = success;
            this.pkgSetting = pkgSetting;
            this.changedAbiCodePath = changedAbiCodePath;
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
        if (disabledPkgSetting != null) {
            scanFlags |= 131072;
            if ((disabledPkgSetting.pkgPrivateFlags & 8) != 0) {
                scanFlags |= 262144;
            }
            if ((131072 & disabledPkgSetting.pkgPrivateFlags) != 0) {
                scanFlags |= 524288;
            }
            if ((disabledPkgSetting.pkgPrivateFlags & 262144) != 0) {
                scanFlags |= 1048576;
            }
            if ((disabledPkgSetting.pkgPrivateFlags & 524288) != 0) {
                scanFlags |= 2097152;
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

    @GuardedBy("mInstallLock")
    private PackageParser.Package scanPackageNewLI(PackageParser.Package pkg, int parseFlags, int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        int i;
        PackageParser.Package r4;
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
                    SharedUserSetting sharedUserSetting = null;
                    if (pkg.mSharedUserId != null) {
                        try {
                            sharedUserSetting = this.mSettings.getSharedUserLPw(pkg.mSharedUserId, 0, 0, true);
                            if (DEBUG_PACKAGE_SCANNING && (Integer.MIN_VALUE & parseFlags) != 0) {
                                Log.d(TAG, "Shared UserID " + pkg.mSharedUserId + " (uid=" + sharedUserSetting.userId + "): packages=" + sharedUserSetting.packages);
                            }
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                    try {
                        i = 3;
                        r4 = pkg;
                        try {
                            ScanRequest request = new ScanRequest(pkg, sharedUserSetting, pkgSetting == null ? null : pkgSetting.pkg, pkgSetting, disabledPkgSetting, originalPkgSetting, realPkgName, parseFlags, scanFlags2, pkg == this.mPlatformPackage, user);
                            try {
                                ScanResult result = scanPackageOnlyLI(request, this.mFactoryTest, currentTime);
                                if (result.success) {
                                    commitScanResultsLocked(request, result);
                                }
                                if (1 == 0 && (scanFlags2 & 64) != 0) {
                                    destroyAppDataLIF(r4, -1, 3);
                                    destroyAppProfilesLIF(r4, -1);
                                }
                                return r4;
                            } catch (Throwable th2) {
                                th = th2;
                                if (0 == 0 && (scanFlags2 & 64) != 0) {
                                    destroyAppDataLIF(r4, -1, i);
                                    destroyAppProfilesLIF(r4, -1);
                                }
                                throw th;
                            }
                        } catch (Throwable th3) {
                            th = th3;
                        }
                    } catch (Throwable th4) {
                        th = th4;
                        i = 3;
                        r4 = pkg;
                    }
                } catch (Throwable th5) {
                    th = th5;
                }
            } catch (Throwable th6) {
                th = th6;
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:104:0x0241  */
    /* JADX WARN: Removed duplicated region for block: B:111:0x025e  */
    /* JADX WARN: Removed duplicated region for block: B:121:0x0280  */
    /* JADX WARN: Removed duplicated region for block: B:146:0x025a A[EDGE_INSN: B:146:0x025a->B:109:0x025a ?: BREAK  , SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:90:0x01e3  */
    @com.android.internal.annotations.GuardedBy("mPackages")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void commitScanResultsLocked(com.android.server.pm.PackageManagerService.ScanRequest r31, com.android.server.pm.PackageManagerService.ScanResult r32) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 712
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.commitScanResultsLocked(com.android.server.pm.PackageManagerService$ScanRequest, com.android.server.pm.PackageManagerService$ScanResult):void");
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

    @GuardedBy("mPackages")
    private PackageSetting getOriginalPackageLocked(PackageParser.Package pkg, String renamedPkgName) {
        if (isPackageRenamed(pkg, renamedPkgName)) {
            for (int i = pkg.mOriginalPackages.size() - 1; i >= 0; i--) {
                PackageSetting originalPs = this.mSettings.getPackageLPr((String) pkg.mOriginalPackages.get(i));
                if (originalPs != null && verifyPackageUpdateLPr(originalPs, pkg)) {
                    if (originalPs.sharedUser != null) {
                        if (!originalPs.sharedUser.name.equals(pkg.mSharedUserId)) {
                            Slog.w(TAG, "Unable to migrate data from " + originalPs.name + " to " + pkg.packageName + ": old uid " + originalPs.sharedUser.name + " differs from " + pkg.mSharedUserId);
                        }
                    } else if (DEBUG_UPGRADE) {
                        Log.v(TAG, "Renaming new package " + pkg.packageName + " to old name " + originalPs.name);
                    }
                    return originalPs;
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

    /* JADX WARN: Removed duplicated region for block: B:105:0x0309  */
    /* JADX WARN: Removed duplicated region for block: B:110:0x0328  */
    /* JADX WARN: Removed duplicated region for block: B:125:0x0383  */
    /* JADX WARN: Removed duplicated region for block: B:128:0x03bd  */
    /* JADX WARN: Removed duplicated region for block: B:141:0x0419  */
    /* JADX WARN: Removed duplicated region for block: B:142:0x041d  */
    /* JADX WARN: Removed duplicated region for block: B:145:0x0431  */
    /* JADX WARN: Removed duplicated region for block: B:151:0x0445  */
    /* JADX WARN: Removed duplicated region for block: B:161:0x0475  */
    /* JADX WARN: Removed duplicated region for block: B:164:0x0487  */
    /* JADX WARN: Removed duplicated region for block: B:31:0x00c8  */
    /* JADX WARN: Removed duplicated region for block: B:33:0x00d7  */
    /* JADX WARN: Removed duplicated region for block: B:34:0x00d9  */
    /* JADX WARN: Removed duplicated region for block: B:37:0x00de  */
    /* JADX WARN: Removed duplicated region for block: B:51:0x016a  */
    /* JADX WARN: Removed duplicated region for block: B:53:0x01be  */
    /* JADX WARN: Removed duplicated region for block: B:56:0x01e9  */
    /* JADX WARN: Removed duplicated region for block: B:59:0x01ef  */
    /* JADX WARN: Removed duplicated region for block: B:60:0x01f1  */
    /* JADX WARN: Removed duplicated region for block: B:62:0x01f7  */
    /* JADX WARN: Removed duplicated region for block: B:71:0x020d  */
    /* JADX WARN: Removed duplicated region for block: B:73:0x0211  */
    /* JADX WARN: Removed duplicated region for block: B:81:0x022e  */
    /* JADX WARN: Removed duplicated region for block: B:82:0x0238  */
    /* JADX WARN: Removed duplicated region for block: B:85:0x024d  */
    /* JADX WARN: Removed duplicated region for block: B:86:0x024f  */
    /* JADX WARN: Removed duplicated region for block: B:89:0x026e  */
    /* JADX WARN: Removed duplicated region for block: B:92:0x027e  */
    @com.android.internal.annotations.GuardedBy("mInstallLock")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static com.android.server.pm.PackageManagerService.ScanResult scanPackageOnlyLI(com.android.server.pm.PackageManagerService.ScanRequest r54, boolean r55, long r56) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 1229
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.scanPackageOnlyLI(com.android.server.pm.PackageManagerService$ScanRequest, boolean, long):com.android.server.pm.PackageManagerService$ScanResult");
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
            if (pkg.permissionGroups != null && pkg.permissionGroups.size() > 0) {
                for (int i = pkg.permissionGroups.size() - 1; i >= 0; i--) {
                    ((PackageParser.PermissionGroup) pkg.permissionGroups.get(i)).info.priority = 0;
                }
            }
        }
        if ((scanFlags & 262144) == 0) {
            pkg.protectedBroadcasts = null;
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
                int i4 = pkg.providers.size() - 1;
                while (true) {
                    int i5 = i4;
                    if (i5 < 0) {
                        break;
                    }
                    PackageParser.Provider provider = (PackageParser.Provider) pkg.providers.get(i5);
                    if ((provider.info.flags & 1073741824) != 0) {
                        provider.info.exported = false;
                    }
                    i4 = i5 - 1;
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
        if ((2097152 & scanFlags) != 0) {
            ApplicationInfo applicationInfo3 = pkg.applicationInfo;
            applicationInfo3.privateFlags = 524288 | applicationInfo3.privateFlags;
        }
        if (PLATFORM_PACKAGE_NAME.equals(pkg.packageName) || (platformPkg != null && PackageManagerServiceUtils.compareSignatures(platformPkg.mSigningDetails.signatures, pkg.mSigningDetails.signatures) == 0)) {
            ApplicationInfo applicationInfo4 = pkg.applicationInfo;
            applicationInfo4.privateFlags = 1048576 | applicationInfo4.privateFlags;
        }
        if (!isSystemApp(pkg)) {
            pkg.mOriginalPackages = null;
            pkg.mRealPackage = null;
            pkg.mAdoptPermissions = null;
        }
    }

    private static <T> T assertNotNull(T object, String message) throws PackageManagerException {
        if (object == null) {
            throw new PackageManagerException(RequestStatus.SYS_ETIMEDOUT, message);
        }
        return object;
    }

    private void assertPackageIsValid(PackageParser.Package pkg, int parseFlags, int scanFlags) throws PackageManagerException {
        long minVersionCode;
        if ((parseFlags & 64) != 0) {
            assertCodePolicy(pkg);
        }
        if (pkg.applicationInfo.getCodePath() == null || pkg.applicationInfo.getResourcePath() == null) {
            throw new PackageManagerException(-2, "Code and resource paths haven't been set correctly");
        }
        KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
        ksms.assertScannedPackageValid(pkg);
        synchronized (this.mPackages) {
            if (pkg.packageName.equals(PLATFORM_PACKAGE_NAME) && this.mAndroidApplication != null) {
                Slog.w(TAG, "*************************************************");
                Slog.w(TAG, "Core android package being redefined.  Skipping.");
                Slog.w(TAG, " codePath=" + pkg.codePath);
                Slog.w(TAG, "*************************************************");
                throw new PackageManagerException(-5, "Core android package being redefined.  Skipping.");
            }
            if (this.mPackages.containsKey(pkg.packageName)) {
                throw new PackageManagerException(-5, "Application package " + pkg.packageName + " already installed.  Skipping duplicate.");
            }
            if (pkg.applicationInfo.isStaticSharedLibrary()) {
                if (this.mPackages.containsKey(pkg.manifestPackageName)) {
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
                long maxVersionCode = JobStatus.NO_LATEST_RUNTIME;
                LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.get(pkg.staticSharedLibName);
                if (versionedLib != null) {
                    int versionCount = versionedLib.size();
                    long maxVersionCode2 = Long.MAX_VALUE;
                    long minVersionCode2 = Long.MIN_VALUE;
                    int i = 0;
                    while (true) {
                        if (i >= versionCount) {
                            minVersionCode = minVersionCode2;
                            maxVersionCode = maxVersionCode2;
                            break;
                        }
                        SharedLibraryInfo libInfo = versionedLib.valueAt(i).info;
                        long libVersionCode = libInfo.getDeclaringPackage().getLongVersionCode();
                        int i2 = i;
                        if (libInfo.getLongVersion() >= pkg.staticSharedLibVersion) {
                            long minVersionCode3 = libInfo.getLongVersion();
                            long minVersionCode4 = minVersionCode2;
                            long minVersionCode5 = pkg.staticSharedLibVersion;
                            if (minVersionCode3 <= minVersionCode5) {
                                minVersionCode = libVersionCode;
                                maxVersionCode = libVersionCode;
                                break;
                            }
                            maxVersionCode2 = Math.min(maxVersionCode2, libVersionCode - 1);
                            minVersionCode2 = minVersionCode4;
                        } else {
                            minVersionCode2 = Math.max(minVersionCode2, libVersionCode + 1);
                        }
                        i = i2 + 1;
                    }
                } else {
                    minVersionCode = Long.MIN_VALUE;
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
                for (int i3 = 0; i3 < childCount; i3++) {
                    PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i3);
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
                    if (known == null) {
                        throw new PackageManagerException(-19, "Application package " + pkg.packageName + " not found; ignoring.");
                    }
                    if (DEBUG_PACKAGE_SCANNING) {
                        Log.d(TAG, "Examining " + pkg.codePath + " and requiring known paths " + known.codePathString + " & " + known.resourcePathString);
                    }
                    if (!pkg.applicationInfo.getCodePath().equals(known.codePathString) || !pkg.applicationInfo.getResourcePath().equals(known.resourcePathString)) {
                        throw new PackageManagerException(-23, "Application package " + pkg.packageName + " found at " + pkg.applicationInfo.getCodePath() + " but expected at " + known.codePathString + "; ignoring.");
                    }
                }
            }
            if ((scanFlags & 4) != 0) {
                int N = pkg.providers.size();
                for (int i4 = 0; i4 < N; i4++) {
                    PackageParser.Provider p = (PackageParser.Provider) pkg.providers.get(i4);
                    if (p.info.authority != null) {
                        String[] names = p.info.authority.split(";");
                        for (int j = 0; j < names.length; j++) {
                            if (this.mProvidersByAuthority.containsKey(names[j])) {
                                PackageParser.Provider other = this.mProvidersByAuthority.get(names[j]);
                                String otherPackageName = (other == null || other.getComponentName() == null) ? "?" : other.getComponentName().getPackageName();
                                throw new PackageManagerException(-13, "Can't install because provider name " + names[j] + " (in package " + pkg.applicationInfo.packageName + ") is already used by " + otherPackageName);
                            }
                        }
                        continue;
                    }
                }
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
                    PackageSetting platformPkgSetting2 = this.mSettings.getPackageLPr(PLATFORM_PACKAGE_NAME);
                    if (platformPkgSetting2.signatures.mSigningDetails != PackageParser.SigningDetails.UNKNOWN && PackageManagerServiceUtils.compareSignatures(platformPkgSetting2.signatures.mSigningDetails.signatures, pkg.mSigningDetails.signatures) != 0) {
                        throw new PackageManagerException("Overlay " + pkg.packageName + " must be signed with the platform certificate.");
                    }
                }
            }
        }
    }

    private boolean addSharedLibraryLPw(String path, String apk, String name, long version, int type, String declaringPackageName, long declaringVersionCode) {
        int i;
        String str;
        LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.get(name);
        if (versionedLib == null) {
            versionedLib = new LongSparseArray<>();
            this.mSharedLibraries.put(name, versionedLib);
            i = type;
            if (i == 2) {
                str = declaringPackageName;
                this.mStaticLibsByDeclaringPackage.put(str, versionedLib);
            } else {
                str = declaringPackageName;
            }
        } else {
            i = type;
            str = declaringPackageName;
            if (versionedLib.indexOfKey(version) >= 0) {
                return false;
            }
        }
        LongSparseArray<SharedLibraryEntry> versionedLib2 = versionedLib;
        SharedLibraryEntry libEntry = new SharedLibraryEntry(path, apk, name, version, i, str, declaringVersionCode);
        versionedLib2.put(version, libEntry);
        return true;
    }

    private boolean removeSharedLibraryLPw(String name, long version) {
        int libIdx;
        LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.get(name);
        if (versionedLib == null || (libIdx = versionedLib.indexOfKey(version)) < 0) {
            return false;
        }
        SharedLibraryEntry libEntry = versionedLib.valueAt(libIdx);
        versionedLib.remove(version);
        if (versionedLib.size() <= 0) {
            this.mSharedLibraries.remove(name);
            if (libEntry.info.getType() == 2) {
                this.mStaticLibsByDeclaringPackage.remove(libEntry.info.getDeclaringPackage().getPackageName());
                return true;
            }
            return true;
        }
        return true;
    }

    /* JADX WARN: Can't wrap try/catch for region: R(11:26|(3:27|28|(7:30|31|32|33|34|35|(1:37)(1:305))(1:311))|(2:39|(1:303)(4:43|(8:44|(5:46|(3:48|(2:52|(2:53|(1:60)(2:55|(2:58|59)(1:57))))(0)|61)(1:85)|62|(5:64|65|66|67|(2:69|70)(1:72))(2:83|84)|71)(1:86)|73|74|75|76|(3:77|78|80)|81)|87|(9:89|90|91|92|93|(1:99)|(3:101|(2:104|102)|105)|106|24d)))(1:304)|301|91|92|93|(3:95|97|99)|(0)|106|24d) */
    /* JADX WARN: Code restructure failed: missing block: B:262:0x06d3, code lost:
        r0 = th;
     */
    /* JADX WARN: Removed duplicated region for block: B:126:0x02f9 A[Catch: all -> 0x0409, TRY_LEAVE, TryCatch #5 {all -> 0x0409, blocks: (B:112:0x029e, B:114:0x02c7, B:115:0x02d8, B:124:0x02ed, B:126:0x02f9, B:130:0x030f), top: B:278:0x029e }] */
    /* JADX WARN: Removed duplicated region for block: B:142:0x0373 A[Catch: all -> 0x0438, TryCatch #11 {all -> 0x0438, blocks: (B:137:0x0338, B:149:0x03c2, B:142:0x0373, B:144:0x03a7, B:146:0x03ad, B:148:0x03b8, B:154:0x03e4, B:156:0x03f2, B:155:0x03ed, B:157:0x03f9, B:162:0x041d, B:164:0x0421, B:171:0x0446, B:174:0x0467, B:176:0x0475, B:175:0x0470, B:177:0x047c, B:179:0x0481, B:181:0x0485, B:185:0x04a6, B:188:0x04ca, B:190:0x04d8, B:189:0x04d3, B:191:0x04df, B:193:0x04e4, B:195:0x04e8, B:199:0x0509, B:202:0x052c, B:204:0x053a, B:203:0x0535, B:205:0x0541, B:207:0x0546, B:209:0x054a, B:210:0x0560, B:212:0x0564, B:217:0x058b, B:223:0x05b9, B:226:0x063c, B:228:0x064e, B:230:0x065a, B:227:0x0647, B:232:0x0660, B:234:0x0664, B:237:0x067e, B:238:0x0686, B:241:0x068b, B:244:0x069d), top: B:289:0x0338 }] */
    /* JADX WARN: Removed duplicated region for block: B:286:0x024e A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:93:0x0224  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void commitPackageSettings(final android.content.pm.PackageParser.Package r29, final android.content.pm.PackageParser.Package r30, com.android.server.pm.PackageSetting r31, android.os.UserHandle r32, int r33, boolean r34) {
        /*
            Method dump skipped, instructions count: 1768
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.commitPackageSettings(android.content.pm.PackageParser$Package, android.content.pm.PackageParser$Package, com.android.server.pm.PackageSetting, android.os.UserHandle, int, boolean):void");
    }

    /* JADX WARN: Code restructure failed: missing block: B:77:0x015d, code lost:
        if (r11.isLibrary() != false) goto L79;
     */
    /* JADX WARN: Code restructure failed: missing block: B:78:0x015f, code lost:
        r11.applicationInfo.primaryCpuAbi = r8[r10];
     */
    /* JADX WARN: Code restructure failed: missing block: B:80:0x016d, code lost:
        throw new com.android.server.pm.PackageManagerException(android.hardware.biometrics.fingerprint.V2_1.RequestStatus.SYS_ETIMEDOUT, "Shared library with native libs must be multiarch");
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static void derivePackageAbi(android.content.pm.PackageParser.Package r11, java.lang.String r12, boolean r13) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 427
            To view this dump add '--comments-level debug' option
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
                            if (DEBUG_ABI_SELECTION) {
                                StringBuilder sb2 = new StringBuilder();
                                sb2.append("Adjusting ABI for ");
                                sb2.append(ps2.name);
                                sb2.append(" to ");
                                sb2.append(adjustedAbi);
                                sb2.append(" (requirer=");
                                sb2.append(requirer != null ? requirer.pkg : "null");
                                sb2.append(", scannedPackage=");
                                sb2.append(scannedPackage != null ? scannedPackage : "null");
                                sb2.append(")");
                                Slog.i(TAG, sb2.toString());
                            }
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
        this.mInstantAppInstallerActivity.exported = true;
        this.mInstantAppInstallerActivity.enabled = true;
        this.mInstantAppInstallerInfo.activityInfo = this.mInstantAppInstallerActivity;
        this.mInstantAppInstallerInfo.priority = 1;
        this.mInstantAppInstallerInfo.preferredOrder = 1;
        this.mInstantAppInstallerInfo.isDefault = true;
        this.mInstantAppInstallerInfo.match = 5799936;
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
        boolean asecApp = info.isForwardLocked() || info.isExternalAsec();
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
            } else if (asecApp) {
                info.nativeLibraryRootDir = new File(codeFile.getParentFile(), "lib").getAbsolutePath();
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
        boolean has64BitLibs3;
        File codeFile = new File(pkg.codePath);
        if (PackageParser.isApkFile(codeFile)) {
            has64BitLibs2 = new File(apkRoot, new File("lib64", apkName).getPath()).exists();
            has64BitLibs3 = new File(apkRoot, new File("lib", apkName).getPath()).exists();
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
                has64BitLibs2 = has64BitLibs;
                has64BitLibs3 = has32BitLibs;
            } else {
                has64BitLibs2 = has64BitLibs;
                has64BitLibs3 = false;
            }
        }
        if (has64BitLibs2 && !has64BitLibs3) {
            pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
            pkg.applicationInfo.secondaryCpuAbi = null;
        } else if (has64BitLibs3 && !has64BitLibs2) {
            pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            pkg.applicationInfo.secondaryCpuAbi = null;
        } else if (!has64BitLibs3 || !has64BitLibs2) {
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

    /* JADX INFO: Access modifiers changed from: private */
    public void killApplication(String pkgName, int appId, String reason) {
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
            removePackageLI(ps, chatty);
        }
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
            PackageSetting ps2 = (PackageSetting) childPkg.mExtras;
            if (ps2 != null) {
                removePackageLI(ps2, chatty);
            }
        }
    }

    void removePackageLI(PackageSetting ps, boolean chatty) {
        if (DEBUG_INSTALL && chatty) {
            Log.d(TAG, "Removing package " + ps.name);
        }
        synchronized (this.mPackages) {
            this.mPackages.remove(ps.name);
            PackageParser.Package pkg = ps.pkg;
            if (pkg != null) {
                cleanPackageDataStructuresLILPw(pkg, chatty);
            }
        }
    }

    void removeInstalledPackageLI(PackageParser.Package pkg, boolean chatty) {
        if (DEBUG_INSTALL && chatty) {
            Log.d(TAG, "Removing package " + pkg.applicationInfo.packageName);
        }
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
        int N = pkg.providers.size();
        StringBuilder r = null;
        for (int i = 0; i < N; i++) {
            PackageParser.Provider p = (PackageParser.Provider) pkg.providers.get(i);
            this.mProviders.removeProvider(p);
            if (p.info.authority != null) {
                String[] names = p.info.authority.split(";");
                for (int j = 0; j < names.length; j++) {
                    if (this.mProvidersByAuthority.get(names[j]) == p) {
                        this.mProvidersByAuthority.remove(names[j]);
                        if (DEBUG_REMOVE && chatty) {
                            Log.d(TAG, "Unregistered content provider: " + names[j] + ", className = " + p.info.name + ", isSyncable = " + p.info.isSyncable);
                        }
                    }
                }
                if (DEBUG_REMOVE && chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(p.info.name);
                }
            }
        }
        if (r != null && DEBUG_REMOVE) {
            Log.d(TAG, "  Providers: " + ((Object) r));
        }
        int N2 = pkg.services.size();
        StringBuilder r2 = null;
        for (int i2 = 0; i2 < N2; i2++) {
            PackageParser.Service s = (PackageParser.Service) pkg.services.get(i2);
            this.mServices.removeService(s);
            if (chatty) {
                if (r2 == null) {
                    r2 = new StringBuilder(256);
                } else {
                    r2.append(' ');
                }
                r2.append(s.info.name);
            }
        }
        if (r2 != null && DEBUG_REMOVE) {
            Log.d(TAG, "  Services: " + ((Object) r2));
        }
        int N3 = pkg.receivers.size();
        StringBuilder r3 = null;
        for (int i3 = 0; i3 < N3; i3++) {
            PackageParser.Activity a = (PackageParser.Activity) pkg.receivers.get(i3);
            this.mReceivers.removeActivity(a, "receiver");
            if (DEBUG_REMOVE && chatty) {
                if (r3 == null) {
                    r3 = new StringBuilder(256);
                } else {
                    r3.append(' ');
                }
                r3.append(a.info.name);
            }
        }
        if (r3 != null && DEBUG_REMOVE) {
            Log.d(TAG, "  Receivers: " + ((Object) r3));
        }
        int N4 = pkg.activities.size();
        StringBuilder r4 = null;
        for (int i4 = 0; i4 < N4; i4++) {
            PackageParser.Activity a2 = (PackageParser.Activity) pkg.activities.get(i4);
            this.mActivities.removeActivity(a2, "activity");
            if (DEBUG_REMOVE && chatty) {
                if (r4 == null) {
                    r4 = new StringBuilder(256);
                } else {
                    r4.append(' ');
                }
                r4.append(a2.info.name);
            }
        }
        if (r4 != null && DEBUG_REMOVE) {
            Log.d(TAG, "  Activities: " + ((Object) r4));
        }
        this.mPermissionManager.removeAllPermissions(pkg, chatty);
        int N5 = pkg.instrumentation.size();
        StringBuilder r5 = null;
        for (int i5 = 0; i5 < N5; i5++) {
            PackageParser.Instrumentation a3 = (PackageParser.Instrumentation) pkg.instrumentation.get(i5);
            this.mInstrumentation.remove(a3.getComponentName());
            if (DEBUG_REMOVE && chatty) {
                if (r5 == null) {
                    r5 = new StringBuilder(256);
                } else {
                    r5.append(' ');
                }
                r5.append(a3.info.name);
            }
        }
        if (r5 != null && DEBUG_REMOVE) {
            Log.d(TAG, "  Instrumentation: " + ((Object) r5));
        }
        StringBuilder r6 = null;
        if ((pkg.applicationInfo.flags & 1) != 0 && pkg.libraryNames != null) {
            for (int i6 = 0; i6 < pkg.libraryNames.size(); i6++) {
                String name = (String) pkg.libraryNames.get(i6);
                if (removeSharedLibraryLPw(name, 0L) && DEBUG_REMOVE && chatty) {
                    if (r6 == null) {
                        r6 = new StringBuilder(256);
                    } else {
                        r6.append(' ');
                    }
                    r6.append(name);
                }
            }
        }
        StringBuilder r7 = null;
        if (pkg.staticSharedLibName != null && removeSharedLibraryLPw(pkg.staticSharedLibName, pkg.staticSharedLibVersion) && DEBUG_REMOVE && chatty) {
            if (0 == 0) {
                r7 = new StringBuilder(256);
            } else {
                r7.append(' ');
            }
            r7.append(pkg.staticSharedLibName);
        }
        if (r7 == null || !DEBUG_REMOVE) {
            return;
        }
        Log.d(TAG, "  Libraries: " + ((Object) r7));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ActivityIntentResolver extends IntentResolver<PackageParser.ActivityIntentInfo, ResolveInfo> {
        private final ArrayMap<ComponentName, PackageParser.Activity> mActivities = new ArrayMap<>();
        private int mFlags;

        ActivityIntentResolver() {
        }

        @Override // com.android.server.IntentResolver
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, boolean defaultOnly, int userId) {
            if (PackageManagerService.sUserManager.exists(userId)) {
                this.mFlags = defaultOnly ? 65536 : 0;
                return super.queryIntent(intent, resolvedType, defaultOnly, userId);
            }
            return null;
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags, int userId) {
            if (PackageManagerService.sUserManager.exists(userId)) {
                this.mFlags = flags;
                return super.queryIntent(intent, resolvedType, (65536 & flags) != 0, userId);
            }
            return null;
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType, int flags, ArrayList<PackageParser.Activity> packageActivities, int userId) {
            if (PackageManagerService.sUserManager.exists(userId) && packageActivities != null) {
                this.mFlags = flags;
                boolean defaultOnly = (65536 & flags) != 0;
                int N = packageActivities.size();
                ArrayList<PackageParser.ActivityIntentInfo[]> listCut = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    ArrayList<PackageParser.ActivityIntentInfo> intentFilters = packageActivities.get(i).intents;
                    if (intentFilters != null && intentFilters.size() > 0) {
                        PackageParser.ActivityIntentInfo[] array = new PackageParser.ActivityIntentInfo[intentFilters.size()];
                        intentFilters.toArray(array);
                        listCut.add(array);
                    }
                }
                return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
            }
            return null;
        }

        private PackageParser.Activity findMatchingActivity(List<PackageParser.Activity> activityList, ActivityInfo activityInfo) {
            for (PackageParser.Activity sysActivity : activityList) {
                if (sysActivity.info.name.equals(activityInfo.name)) {
                    return sysActivity;
                }
                if (sysActivity.info.name.equals(activityInfo.targetActivity)) {
                    return sysActivity;
                }
                if (sysActivity.info.targetActivity != null) {
                    if (sysActivity.info.targetActivity.equals(activityInfo.name)) {
                        return sysActivity;
                    }
                    if (sysActivity.info.targetActivity.equals(activityInfo.targetActivity)) {
                        return sysActivity;
                    }
                }
            }
            return null;
        }

        /* loaded from: classes.dex */
        public class IterGenerator<E> {
            public IterGenerator() {
            }

            public Iterator<E> generate(PackageParser.ActivityIntentInfo info) {
                return null;
            }
        }

        /* loaded from: classes.dex */
        public class ActionIterGenerator extends IterGenerator<String> {
            public ActionIterGenerator() {
                super();
            }

            @Override // com.android.server.pm.PackageManagerService.ActivityIntentResolver.IterGenerator
            public Iterator<String> generate(PackageParser.ActivityIntentInfo info) {
                return info.actionsIterator();
            }
        }

        /* loaded from: classes.dex */
        public class CategoriesIterGenerator extends IterGenerator<String> {
            public CategoriesIterGenerator() {
                super();
            }

            @Override // com.android.server.pm.PackageManagerService.ActivityIntentResolver.IterGenerator
            public Iterator<String> generate(PackageParser.ActivityIntentInfo info) {
                return info.categoriesIterator();
            }
        }

        /* loaded from: classes.dex */
        public class SchemesIterGenerator extends IterGenerator<String> {
            public SchemesIterGenerator() {
                super();
            }

            @Override // com.android.server.pm.PackageManagerService.ActivityIntentResolver.IterGenerator
            public Iterator<String> generate(PackageParser.ActivityIntentInfo info) {
                return info.schemesIterator();
            }
        }

        /* loaded from: classes.dex */
        public class AuthoritiesIterGenerator extends IterGenerator<IntentFilter.AuthorityEntry> {
            public AuthoritiesIterGenerator() {
                super();
            }

            @Override // com.android.server.pm.PackageManagerService.ActivityIntentResolver.IterGenerator
            public Iterator<IntentFilter.AuthorityEntry> generate(PackageParser.ActivityIntentInfo info) {
                return info.authoritiesIterator();
            }
        }

        private <T> void getIntentListSubset(List<PackageParser.ActivityIntentInfo> intentList, IterGenerator<T> generator, Iterator<T> searchIterator) {
            while (searchIterator.hasNext() && intentList.size() != 0) {
                T searchAction = searchIterator.next();
                Iterator<PackageParser.ActivityIntentInfo> intentIter = intentList.iterator();
                while (intentIter.hasNext()) {
                    PackageParser.ActivityIntentInfo intentInfo = intentIter.next();
                    boolean selectionFound = false;
                    Iterator<T> intentSelectionIter = generator.generate(intentInfo);
                    while (true) {
                        if (intentSelectionIter == null || !intentSelectionIter.hasNext()) {
                            break;
                        }
                        T intentSelection = intentSelectionIter.next();
                        if (intentSelection != null && intentSelection.equals(searchAction)) {
                            selectionFound = true;
                            break;
                        }
                    }
                    if (!selectionFound) {
                        intentIter.remove();
                    }
                }
            }
        }

        private boolean isProtectedAction(PackageParser.ActivityIntentInfo filter) {
            Iterator<String> actionsIter = filter.actionsIterator();
            while (actionsIter != null && actionsIter.hasNext()) {
                String filterAction = actionsIter.next();
                if (PackageManagerService.PROTECTED_ACTIONS.contains(filterAction)) {
                    return true;
                }
            }
            return false;
        }

        private void adjustPriority(List<PackageParser.Activity> systemActivities, PackageParser.ActivityIntentInfo intent) {
            if (intent.getPriority() <= 0) {
                return;
            }
            ActivityInfo activityInfo = intent.activity.info;
            ApplicationInfo applicationInfo = activityInfo.applicationInfo;
            boolean privilegedApp = (applicationInfo.privateFlags & 8) != 0;
            if (!privilegedApp) {
                if (PackageManagerService.DEBUG_FILTERS) {
                    Slog.i(PackageManagerService.TAG, "Non-privileged app; cap priority to 0; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                }
                intent.setPriority(0);
            } else if (systemActivities == null) {
                if (isProtectedAction(intent)) {
                    if (!PackageManagerService.this.mDeferProtectedFilters) {
                        if (PackageManagerService.DEBUG_FILTERS && PackageManagerService.this.mSetupWizardPackage == null) {
                            Slog.i(PackageManagerService.TAG, "No setup wizard; All protected intents capped to priority 0");
                        }
                        if (intent.activity.info.packageName.equals(PackageManagerService.this.mSetupWizardPackage)) {
                            if (PackageManagerService.DEBUG_FILTERS) {
                                Slog.i(PackageManagerService.TAG, "Found setup wizard; allow priority " + intent.getPriority() + "; package: " + intent.activity.info.packageName + " activity: " + intent.activity.className + " priority: " + intent.getPriority());
                                return;
                            }
                            return;
                        }
                        if (PackageManagerService.DEBUG_FILTERS) {
                            Slog.i(PackageManagerService.TAG, "Protected action; cap priority to 0; package: " + intent.activity.info.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                        }
                        intent.setPriority(0);
                        return;
                    }
                    PackageManagerService.this.mProtectedFilters.add(intent);
                    if (PackageManagerService.DEBUG_FILTERS) {
                        Slog.i(PackageManagerService.TAG, "Protected action; save for later; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                    }
                }
            } else {
                PackageParser.Activity foundActivity = findMatchingActivity(systemActivities, activityInfo);
                if (foundActivity == null) {
                    if (PackageManagerService.DEBUG_FILTERS) {
                        Slog.i(PackageManagerService.TAG, "New activity; cap priority to 0; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                    }
                    intent.setPriority(0);
                    return;
                }
                List<PackageParser.ActivityIntentInfo> intentListCopy = new ArrayList<>(foundActivity.intents);
                findFilters(intent);
                Iterator<String> actionsIterator = intent.actionsIterator();
                if (actionsIterator != null) {
                    getIntentListSubset(intentListCopy, new ActionIterGenerator(), actionsIterator);
                    if (intentListCopy.size() == 0) {
                        if (PackageManagerService.DEBUG_FILTERS) {
                            Slog.i(PackageManagerService.TAG, "Mismatched action; cap priority to 0; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                        }
                        intent.setPriority(0);
                        return;
                    }
                }
                Iterator<String> categoriesIterator = intent.categoriesIterator();
                if (categoriesIterator != null) {
                    getIntentListSubset(intentListCopy, new CategoriesIterGenerator(), categoriesIterator);
                    if (intentListCopy.size() == 0) {
                        if (PackageManagerService.DEBUG_FILTERS) {
                            Slog.i(PackageManagerService.TAG, "Mismatched category; cap priority to 0; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                        }
                        intent.setPriority(0);
                        return;
                    }
                }
                Iterator<String> schemesIterator = intent.schemesIterator();
                if (schemesIterator != null) {
                    getIntentListSubset(intentListCopy, new SchemesIterGenerator(), schemesIterator);
                    if (intentListCopy.size() == 0) {
                        if (PackageManagerService.DEBUG_FILTERS) {
                            Slog.i(PackageManagerService.TAG, "Mismatched scheme; cap priority to 0; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                        }
                        intent.setPriority(0);
                        return;
                    }
                }
                Iterator<IntentFilter.AuthorityEntry> authoritiesIterator = intent.authoritiesIterator();
                if (authoritiesIterator != null) {
                    getIntentListSubset(intentListCopy, new AuthoritiesIterGenerator(), authoritiesIterator);
                    if (intentListCopy.size() == 0) {
                        if (PackageManagerService.DEBUG_FILTERS) {
                            Slog.i(PackageManagerService.TAG, "Mismatched authority; cap priority to 0; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                        }
                        intent.setPriority(0);
                        return;
                    }
                }
                int cappedPriority = 0;
                int i = intentListCopy.size() - 1;
                while (true) {
                    int i2 = i;
                    if (i2 < 0) {
                        break;
                    }
                    cappedPriority = Math.max(cappedPriority, intentListCopy.get(i2).getPriority());
                    i = i2 - 1;
                }
                if (intent.getPriority() > cappedPriority) {
                    if (PackageManagerService.DEBUG_FILTERS) {
                        Slog.i(PackageManagerService.TAG, "Found matching filter(s); cap priority to " + cappedPriority + "; package: " + applicationInfo.packageName + " activity: " + intent.activity.className + " origPrio: " + intent.getPriority());
                    }
                    intent.setPriority(cappedPriority);
                }
            }
        }

        public final void addActivity(PackageParser.Activity a, String type) {
            this.mActivities.put(a.getComponentName(), a);
            if (PackageManagerService.DEBUG_SHOW_INFO) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ");
                sb.append(type);
                sb.append(" ");
                sb.append(a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel : a.info.name);
                sb.append(":");
                Log.v(PackageManagerService.TAG, sb.toString());
            }
            if (PackageManagerService.DEBUG_SHOW_INFO) {
                Log.v(PackageManagerService.TAG, "    Class=" + a.info.name);
            }
            int NI = a.intents.size();
            for (int j = 0; j < NI; j++) {
                PackageParser.ActivityIntentInfo intent = (PackageParser.ActivityIntentInfo) a.intents.get(j);
                if ("activity".equals(type)) {
                    PackageSetting ps = PackageManagerService.this.mSettings.getDisabledSystemPkgLPr(intent.activity.info.packageName);
                    List<PackageParser.Activity> systemActivities = (ps == null || ps.pkg == null) ? null : ps.pkg.activities;
                    adjustPriority(systemActivities, intent);
                }
                if (PackageManagerService.DEBUG_SHOW_INFO) {
                    Log.v(PackageManagerService.TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(2, PackageManagerService.TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(PackageManagerService.TAG, "==> For Activity " + a.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeActivity(PackageParser.Activity a, String type) {
            this.mActivities.remove(a.getComponentName());
            if (PackageManagerService.DEBUG_SHOW_INFO) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ");
                sb.append(type);
                sb.append(" ");
                sb.append(a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel : a.info.name);
                sb.append(":");
                Log.v(PackageManagerService.TAG, sb.toString());
                Log.v(PackageManagerService.TAG, "    Class=" + a.info.name);
            }
            int NI = a.intents.size();
            for (int j = 0; j < NI; j++) {
                PackageParser.ActivityIntentInfo intent = (PackageParser.ActivityIntentInfo) a.intents.get(j);
                if (PackageManagerService.DEBUG_SHOW_INFO) {
                    Log.v(PackageManagerService.TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(2, PackageManagerService.TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean allowFilterResult(PackageParser.ActivityIntentInfo filter, List<ResolveInfo> dest) {
            ActivityInfo filterAi = filter.activity.info;
            for (int i = dest.size() - 1; i >= 0; i--) {
                ActivityInfo destAi = dest.get(i).activityInfo;
                if (destAi.name == filterAi.name && destAi.packageName == filterAi.packageName) {
                    return false;
                }
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public PackageParser.ActivityIntentInfo[] newArray(int size) {
            return new PackageParser.ActivityIntentInfo[size];
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isFilterStopped(PackageParser.ActivityIntentInfo filter, int userId) {
            PackageSetting ps;
            if (PackageManagerService.sUserManager.exists(userId)) {
                PackageParser.Package p = filter.activity.owner;
                if (p == null || (ps = (PackageSetting) p.mExtras) == null) {
                    return false;
                }
                return (ps.pkgFlags & 1) == 0 && ps.getStopped(userId);
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isPackageForFilter(String packageName, PackageParser.ActivityIntentInfo info) {
            return packageName.equals(info.activity.owner.packageName);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public ResolveInfo newResult(PackageParser.ActivityIntentInfo info, int match, int userId) {
            PackageUserState userState;
            ActivityInfo ai;
            if (PackageManagerService.sUserManager.exists(userId) && PackageManagerService.this.mSettings.isEnabledAndMatchLPr(info.activity.info, this.mFlags, userId)) {
                PackageParser.Activity activity = info.activity;
                PackageSetting ps = (PackageSetting) activity.owner.mExtras;
                if (ps == null || (ai = PackageParser.generateActivityInfo(activity, this.mFlags, (userState = ps.readUserState(userId)), userId)) == null) {
                    return null;
                }
                boolean matchExplicitlyVisibleOnly = (this.mFlags & 33554432) != 0;
                boolean matchVisibleToInstantApp = (this.mFlags & 16777216) != 0;
                boolean componentVisible = matchVisibleToInstantApp && info.isVisibleToInstantApp() && (!matchExplicitlyVisibleOnly || info.isExplicitlyVisibleToInstantApp());
                boolean matchInstantApp = (this.mFlags & DumpState.DUMP_VOLUMES) != 0;
                if (!matchVisibleToInstantApp || componentVisible || userState.instantApp) {
                    if (matchInstantApp || !userState.instantApp) {
                        if (userState.instantApp && ps.isUpdateAvailable()) {
                            return null;
                        }
                        ResolveInfo res = new ResolveInfo();
                        res.activityInfo = ai;
                        if ((this.mFlags & 64) != 0) {
                            res.filter = info;
                        }
                        if (info != null) {
                            res.handleAllWebDataURI = info.handleAllWebDataURI();
                        }
                        res.priority = info.getPriority();
                        res.preferredOrder = activity.owner.mPreferredOrder;
                        res.match = match;
                        res.isDefault = info.hasDefault;
                        res.labelRes = info.labelRes;
                        res.nonLocalizedLabel = info.nonLocalizedLabel;
                        if (PackageManagerService.this.userNeedsBadging(userId)) {
                            res.noResourceId = true;
                        } else {
                            res.icon = info.icon;
                        }
                        res.iconResourceId = info.icon;
                        res.system = res.activityInfo.applicationInfo.isSystemApp();
                        res.isInstantAppAvailable = userState.instantApp;
                        return res;
                    }
                    return null;
                }
                return null;
            }
            return null;
        }

        @Override // com.android.server.IntentResolver
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, PackageManagerService.mResolvePrioritySorter);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public void dumpFilter(PrintWriter out, String prefix, PackageParser.ActivityIntentInfo filter) {
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(filter.activity)));
            out.print(' ');
            filter.activity.printComponentShortName(out);
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public Object filterToLabel(PackageParser.ActivityIntentInfo filter) {
            return filter.activity;
        }

        @Override // com.android.server.IntentResolver
        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            PackageParser.Activity activity = (PackageParser.Activity) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(activity)));
            out.print(' ');
            activity.printComponentShortName(out);
            if (count > 1) {
                out.print(" (");
                out.print(count);
                out.print(" filters)");
            }
            out.println();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ServiceIntentResolver extends IntentResolver<PackageParser.ServiceIntentInfo, ResolveInfo> {
        private int mFlags;
        private final ArrayMap<ComponentName, PackageParser.Service> mServices;

        private ServiceIntentResolver() {
            this.mServices = new ArrayMap<>();
        }

        @Override // com.android.server.IntentResolver
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, boolean defaultOnly, int userId) {
            this.mFlags = defaultOnly ? 65536 : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags, int userId) {
            if (PackageManagerService.sUserManager.exists(userId)) {
                this.mFlags = flags;
                return super.queryIntent(intent, resolvedType, (65536 & flags) != 0, userId);
            }
            return null;
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType, int flags, ArrayList<PackageParser.Service> packageServices, int userId) {
            if (PackageManagerService.sUserManager.exists(userId) && packageServices != null) {
                this.mFlags = flags;
                boolean defaultOnly = (65536 & flags) != 0;
                int N = packageServices.size();
                ArrayList<PackageParser.ServiceIntentInfo[]> listCut = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    ArrayList<PackageParser.ServiceIntentInfo> intentFilters = packageServices.get(i).intents;
                    if (intentFilters != null && intentFilters.size() > 0) {
                        PackageParser.ServiceIntentInfo[] array = new PackageParser.ServiceIntentInfo[intentFilters.size()];
                        intentFilters.toArray(array);
                        listCut.add(array);
                    }
                }
                return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
            }
            return null;
        }

        public final void addService(PackageParser.Service s) {
            this.mServices.put(s.getComponentName(), s);
            if (PackageManagerService.DEBUG_SHOW_INFO) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ");
                sb.append(s.info.nonLocalizedLabel != null ? s.info.nonLocalizedLabel : s.info.name);
                sb.append(":");
                Log.v(PackageManagerService.TAG, sb.toString());
                Log.v(PackageManagerService.TAG, "    Class=" + s.info.name);
            }
            int NI = s.intents.size();
            for (int j = 0; j < NI; j++) {
                PackageParser.ServiceIntentInfo intent = (PackageParser.ServiceIntentInfo) s.intents.get(j);
                if (PackageManagerService.DEBUG_SHOW_INFO) {
                    Log.v(PackageManagerService.TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(2, PackageManagerService.TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(PackageManagerService.TAG, "==> For Service " + s.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeService(PackageParser.Service s) {
            this.mServices.remove(s.getComponentName());
            if (PackageManagerService.DEBUG_SHOW_INFO) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ");
                sb.append(s.info.nonLocalizedLabel != null ? s.info.nonLocalizedLabel : s.info.name);
                sb.append(":");
                Log.v(PackageManagerService.TAG, sb.toString());
                Log.v(PackageManagerService.TAG, "    Class=" + s.info.name);
            }
            int NI = s.intents.size();
            for (int j = 0; j < NI; j++) {
                PackageParser.ServiceIntentInfo intent = (PackageParser.ServiceIntentInfo) s.intents.get(j);
                if (PackageManagerService.DEBUG_SHOW_INFO) {
                    Log.v(PackageManagerService.TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(2, PackageManagerService.TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean allowFilterResult(PackageParser.ServiceIntentInfo filter, List<ResolveInfo> dest) {
            ServiceInfo filterSi = filter.service.info;
            for (int i = dest.size() - 1; i >= 0; i--) {
                ServiceInfo destAi = dest.get(i).serviceInfo;
                if (destAi.name == filterSi.name && destAi.packageName == filterSi.packageName) {
                    return false;
                }
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public PackageParser.ServiceIntentInfo[] newArray(int size) {
            return new PackageParser.ServiceIntentInfo[size];
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isFilterStopped(PackageParser.ServiceIntentInfo filter, int userId) {
            PackageSetting ps;
            if (PackageManagerService.sUserManager.exists(userId)) {
                PackageParser.Package p = filter.service.owner;
                if (p == null || (ps = (PackageSetting) p.mExtras) == null) {
                    return false;
                }
                return (ps.pkgFlags & 1) == 0 && ps.getStopped(userId);
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isPackageForFilter(String packageName, PackageParser.ServiceIntentInfo info) {
            return packageName.equals(info.service.owner.packageName);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public ResolveInfo newResult(PackageParser.ServiceIntentInfo filter, int match, int userId) {
            PackageUserState userState;
            ServiceInfo si;
            if (PackageManagerService.sUserManager.exists(userId) && PackageManagerService.this.mSettings.isEnabledAndMatchLPr(filter.service.info, this.mFlags, userId)) {
                PackageParser.Service service = filter.service;
                PackageSetting ps = (PackageSetting) service.owner.mExtras;
                if (ps == null || (si = PackageParser.generateServiceInfo(service, this.mFlags, (userState = ps.readUserState(userId)), userId)) == null) {
                    return null;
                }
                boolean matchVisibleToInstantApp = (this.mFlags & 16777216) != 0;
                boolean isInstantApp = (this.mFlags & DumpState.DUMP_VOLUMES) != 0;
                if (!matchVisibleToInstantApp || filter.isVisibleToInstantApp() || userState.instantApp) {
                    if (isInstantApp || !userState.instantApp) {
                        if (userState.instantApp && ps.isUpdateAvailable()) {
                            return null;
                        }
                        ResolveInfo res = new ResolveInfo();
                        res.serviceInfo = si;
                        if ((this.mFlags & 64) != 0) {
                            res.filter = filter;
                        }
                        res.priority = filter.getPriority();
                        res.preferredOrder = service.owner.mPreferredOrder;
                        res.match = match;
                        res.isDefault = filter.hasDefault;
                        res.labelRes = filter.labelRes;
                        res.nonLocalizedLabel = filter.nonLocalizedLabel;
                        res.icon = filter.icon;
                        res.system = res.serviceInfo.applicationInfo.isSystemApp();
                        return res;
                    }
                    return null;
                }
                return null;
            }
            return null;
        }

        @Override // com.android.server.IntentResolver
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, PackageManagerService.mResolvePrioritySorter);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public void dumpFilter(PrintWriter out, String prefix, PackageParser.ServiceIntentInfo filter) {
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(filter.service)));
            out.print(' ');
            filter.service.printComponentShortName(out);
            out.print(" filter ");
            out.print(Integer.toHexString(System.identityHashCode(filter)));
            if (filter.service.info.permission != null) {
                out.print(" permission ");
                out.println(filter.service.info.permission);
                return;
            }
            out.println();
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public Object filterToLabel(PackageParser.ServiceIntentInfo filter) {
            return filter.service;
        }

        @Override // com.android.server.IntentResolver
        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            PackageParser.Service service = (PackageParser.Service) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(service)));
            out.print(' ');
            service.printComponentShortName(out);
            if (count > 1) {
                out.print(" (");
                out.print(count);
                out.print(" filters)");
            }
            out.println();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ProviderIntentResolver extends IntentResolver<PackageParser.ProviderIntentInfo, ResolveInfo> {
        private int mFlags;
        private final ArrayMap<ComponentName, PackageParser.Provider> mProviders;

        private ProviderIntentResolver() {
            this.mProviders = new ArrayMap<>();
        }

        @Override // com.android.server.IntentResolver
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, boolean defaultOnly, int userId) {
            this.mFlags = defaultOnly ? 65536 : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags, int userId) {
            if (!PackageManagerService.sUserManager.exists(userId)) {
                return null;
            }
            this.mFlags = flags;
            return super.queryIntent(intent, resolvedType, (65536 & flags) != 0, userId);
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType, int flags, ArrayList<PackageParser.Provider> packageProviders, int userId) {
            if (PackageManagerService.sUserManager.exists(userId) && packageProviders != null) {
                this.mFlags = flags;
                boolean defaultOnly = (65536 & flags) != 0;
                int N = packageProviders.size();
                ArrayList<PackageParser.ProviderIntentInfo[]> listCut = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    ArrayList<PackageParser.ProviderIntentInfo> intentFilters = packageProviders.get(i).intents;
                    if (intentFilters != null && intentFilters.size() > 0) {
                        PackageParser.ProviderIntentInfo[] array = new PackageParser.ProviderIntentInfo[intentFilters.size()];
                        intentFilters.toArray(array);
                        listCut.add(array);
                    }
                }
                return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
            }
            return null;
        }

        public final void addProvider(PackageParser.Provider p) {
            if (this.mProviders.containsKey(p.getComponentName())) {
                Slog.w(PackageManagerService.TAG, "Provider " + p.getComponentName() + " already defined; ignoring");
                return;
            }
            this.mProviders.put(p.getComponentName(), p);
            if (PackageManagerService.DEBUG_SHOW_INFO) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ");
                sb.append(p.info.nonLocalizedLabel != null ? p.info.nonLocalizedLabel : p.info.name);
                sb.append(":");
                Log.v(PackageManagerService.TAG, sb.toString());
                Log.v(PackageManagerService.TAG, "    Class=" + p.info.name);
            }
            int NI = p.intents.size();
            for (int j = 0; j < NI; j++) {
                PackageParser.ProviderIntentInfo intent = (PackageParser.ProviderIntentInfo) p.intents.get(j);
                if (PackageManagerService.DEBUG_SHOW_INFO) {
                    Log.v(PackageManagerService.TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(2, PackageManagerService.TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(PackageManagerService.TAG, "==> For Provider " + p.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeProvider(PackageParser.Provider p) {
            this.mProviders.remove(p.getComponentName());
            if (PackageManagerService.DEBUG_SHOW_INFO) {
                StringBuilder sb = new StringBuilder();
                sb.append("  ");
                sb.append(p.info.nonLocalizedLabel != null ? p.info.nonLocalizedLabel : p.info.name);
                sb.append(":");
                Log.v(PackageManagerService.TAG, sb.toString());
                Log.v(PackageManagerService.TAG, "    Class=" + p.info.name);
            }
            int NI = p.intents.size();
            for (int j = 0; j < NI; j++) {
                PackageParser.ProviderIntentInfo intent = (PackageParser.ProviderIntentInfo) p.intents.get(j);
                if (PackageManagerService.DEBUG_SHOW_INFO) {
                    Log.v(PackageManagerService.TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(2, PackageManagerService.TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean allowFilterResult(PackageParser.ProviderIntentInfo filter, List<ResolveInfo> dest) {
            ProviderInfo filterPi = filter.provider.info;
            for (int i = dest.size() - 1; i >= 0; i--) {
                ProviderInfo destPi = dest.get(i).providerInfo;
                if (destPi.name == filterPi.name && destPi.packageName == filterPi.packageName) {
                    return false;
                }
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public PackageParser.ProviderIntentInfo[] newArray(int size) {
            return new PackageParser.ProviderIntentInfo[size];
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isFilterStopped(PackageParser.ProviderIntentInfo filter, int userId) {
            PackageSetting ps;
            if (PackageManagerService.sUserManager.exists(userId)) {
                PackageParser.Package p = filter.provider.owner;
                if (p == null || (ps = (PackageSetting) p.mExtras) == null) {
                    return false;
                }
                return (ps.pkgFlags & 1) == 0 && ps.getStopped(userId);
            }
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isPackageForFilter(String packageName, PackageParser.ProviderIntentInfo info) {
            return packageName.equals(info.provider.owner.packageName);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public ResolveInfo newResult(PackageParser.ProviderIntentInfo filter, int match, int userId) {
            ProviderInfo pi;
            if (PackageManagerService.sUserManager.exists(userId) && PackageManagerService.this.mSettings.isEnabledAndMatchLPr(filter.provider.info, this.mFlags, userId)) {
                PackageParser.Provider provider = filter.provider;
                PackageSetting ps = (PackageSetting) provider.owner.mExtras;
                if (ps == null) {
                    return null;
                }
                PackageUserState userState = ps.readUserState(userId);
                boolean matchVisibleToInstantApp = (this.mFlags & 16777216) != 0;
                boolean isInstantApp = (this.mFlags & DumpState.DUMP_VOLUMES) != 0;
                if (!matchVisibleToInstantApp || filter.isVisibleToInstantApp() || userState.instantApp) {
                    if (isInstantApp || !userState.instantApp) {
                        if ((userState.instantApp && ps.isUpdateAvailable()) || (pi = PackageParser.generateProviderInfo(provider, this.mFlags, userState, userId)) == null) {
                            return null;
                        }
                        ResolveInfo res = new ResolveInfo();
                        res.providerInfo = pi;
                        if ((this.mFlags & 64) != 0) {
                            res.filter = filter;
                        }
                        res.priority = filter.getPriority();
                        res.preferredOrder = provider.owner.mPreferredOrder;
                        res.match = match;
                        res.isDefault = filter.hasDefault;
                        res.labelRes = filter.labelRes;
                        res.nonLocalizedLabel = filter.nonLocalizedLabel;
                        res.icon = filter.icon;
                        res.system = res.providerInfo.applicationInfo.isSystemApp();
                        return res;
                    }
                    return null;
                }
                return null;
            }
            return null;
        }

        @Override // com.android.server.IntentResolver
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, PackageManagerService.mResolvePrioritySorter);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public void dumpFilter(PrintWriter out, String prefix, PackageParser.ProviderIntentInfo filter) {
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(filter.provider)));
            out.print(' ');
            filter.provider.printComponentShortName(out);
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public Object filterToLabel(PackageParser.ProviderIntentInfo filter) {
            return filter.provider;
        }

        @Override // com.android.server.IntentResolver
        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            PackageParser.Provider provider = (PackageParser.Provider) label;
            out.print(prefix);
            out.print(Integer.toHexString(System.identityHashCode(provider)));
            out.print(' ');
            provider.printComponentShortName(out);
            if (count > 1) {
                out.print(" (");
                out.print(count);
                out.print(" filters)");
            }
            out.println();
        }
    }

    /* loaded from: classes.dex */
    static final class InstantAppIntentResolver extends IntentResolver<AuxiliaryResolveInfo.AuxiliaryFilter, AuxiliaryResolveInfo.AuxiliaryFilter> {
        final ArrayMap<String, Pair<Integer, InstantAppResolveInfo>> mOrderResult = new ArrayMap<>();

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public AuxiliaryResolveInfo.AuxiliaryFilter[] newArray(int size) {
            return new AuxiliaryResolveInfo.AuxiliaryFilter[size];
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isPackageForFilter(String packageName, AuxiliaryResolveInfo.AuxiliaryFilter responseObj) {
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public AuxiliaryResolveInfo.AuxiliaryFilter newResult(AuxiliaryResolveInfo.AuxiliaryFilter responseObj, int match, int userId) {
            if (PackageManagerService.sUserManager.exists(userId)) {
                String packageName = responseObj.resolveInfo.getPackageName();
                Integer order = Integer.valueOf(responseObj.getOrder());
                Pair<Integer, InstantAppResolveInfo> lastOrderResult = this.mOrderResult.get(packageName);
                if (lastOrderResult == null || ((Integer) lastOrderResult.first).intValue() < order.intValue()) {
                    InstantAppResolveInfo res = responseObj.resolveInfo;
                    if (order.intValue() > 0) {
                        this.mOrderResult.put(packageName, new Pair<>(order, res));
                    }
                    return responseObj;
                }
                return null;
            }
            return null;
        }

        @Override // com.android.server.IntentResolver
        protected void filterResults(List<AuxiliaryResolveInfo.AuxiliaryFilter> results) {
            if (this.mOrderResult.size() == 0) {
                return;
            }
            int resultSize = results.size();
            int i = 0;
            while (i < resultSize) {
                InstantAppResolveInfo info = results.get(i).resolveInfo;
                String packageName = info.getPackageName();
                Pair<Integer, InstantAppResolveInfo> savedInfo = this.mOrderResult.get(packageName);
                if (savedInfo != null) {
                    if (savedInfo.second == info) {
                        this.mOrderResult.remove(packageName);
                        if (this.mOrderResult.size() == 0) {
                            return;
                        }
                    } else {
                        results.remove(i);
                        resultSize--;
                        i--;
                    }
                }
                i++;
            }
        }
    }

    @Override // com.android.server.pm.PackageSender
    public void sendPackageBroadcast(final String action, final String pkg, final Bundle extras, final int flags, final String targetPkg, final IIntentReceiver finishedReceiver, final int[] userIds, final int[] instantUserIds) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.8
            @Override // java.lang.Runnable
            public void run() {
                int[] iArr;
                try {
                    IActivityManager am = ActivityManager.getService();
                    if (am == null) {
                        return;
                    }
                    if (userIds != null) {
                        iArr = userIds;
                    } else {
                        iArr = am.getRunningUserIds();
                    }
                    int[] resolvedUserIds = iArr;
                    PackageManagerService.this.doSendBroadcast(am, action, pkg, extras, flags, targetPkg, finishedReceiver, resolvedUserIds, false);
                    if (instantUserIds != null && instantUserIds != PackageManagerService.EMPTY_INT_ARRAY) {
                        PackageManagerService.this.doSendBroadcast(am, action, pkg, extras, flags, targetPkg, finishedReceiver, instantUserIds, true);
                    }
                } catch (RemoteException e) {
                }
            }
        });
    }

    @Override // com.android.server.pm.PackageSender
    public void notifyPackageAdded(String packageName) {
        synchronized (this.mPackages) {
            if (this.mPackageListObservers.size() == 0) {
                return;
            }
            PackageManagerInternal.PackageListObserver[] observers = (PackageManagerInternal.PackageListObserver[]) this.mPackageListObservers.toArray();
            for (int i = observers.length - 1; i >= 0; i--) {
                observers[i].onPackageAdded(packageName);
            }
        }
    }

    @Override // com.android.server.pm.PackageSender
    public void notifyPackageRemoved(String packageName) {
        synchronized (this.mPackages) {
            if (this.mPackageListObservers.size() == 0) {
                return;
            }
            PackageManagerInternal.PackageListObserver[] observers = (PackageManagerInternal.PackageListObserver[]) this.mPackageListObservers.toArray();
            for (int i = observers.length - 1; i >= 0; i--) {
                observers[i].onPackageRemoved(packageName);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doSendBroadcast(IActivityManager am, String action, String pkg, Bundle extras, int flags, String targetPkg, IIntentReceiver finishedReceiver, int[] userIds, boolean isInstantApp) throws RemoteException {
        for (int id : userIds) {
            Intent intent = new Intent(action, pkg != null ? Uri.fromParts("package", pkg, null) : null);
            String[] requiredPermissions = isInstantApp ? INSTANT_APP_BROADCAST_PERMISSION : null;
            if (extras != null) {
                intent.putExtras(extras);
            }
            if (targetPkg != null) {
                intent.setPackage(targetPkg);
            }
            int uid = intent.getIntExtra("android.intent.extra.UID", -1);
            if (uid > 0 && UserHandle.getUserId(uid) != id) {
                uid = UserHandle.getUid(id, UserHandle.getAppId(uid));
                intent.putExtra("android.intent.extra.UID", uid);
            }
            intent.putExtra("android.intent.extra.user_handle", id);
            intent.addFlags(67108864 | flags);
            if (DEBUG_BROADCASTS) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.d(TAG, "Sending to user " + id + ": " + intent.toShortString(false, true, false, false) + " " + intent.getExtras(), here);
            }
            am.broadcastIntent((IApplicationThread) null, intent, (String) null, finishedReceiver, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, finishedReceiver != null, false, id);
        }
    }

    private boolean isExternalMediaAvailable() {
        return this.mMediaMounted || Environment.isExternalStorageEmulated();
    }

    public PackageCleanItem nextPackageToClean(PackageCleanItem lastPackage) {
        if (getInstantAppPackageName(Binder.getCallingUid()) == null && isExternalMediaAvailable()) {
            synchronized (this.mPackages) {
                ArrayList<PackageCleanItem> pkgs = this.mSettings.mPackagesToBeCleaned;
                if (lastPackage != null) {
                    pkgs.remove(lastPackage);
                }
                if (pkgs.size() > 0) {
                    return pkgs.get(0);
                }
                return null;
            }
        }
        return null;
    }

    void schedulePackageCleaning(String packageName, int userId, boolean andCode) {
        Message msg = this.mHandler.obtainMessage(7, userId, andCode ? 1 : 0, packageName);
        if (this.mSystemReady) {
            msg.sendToTarget();
            return;
        }
        if (this.mPostSystemReadyMessages == null) {
            this.mPostSystemReadyMessages = new ArrayList<>();
        }
        this.mPostSystemReadyMessages.add(msg);
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:31:0x0067 -> B:32:0x0068). Please submit an issue!!! */
    void startCleaningPackages() {
        if (!isExternalMediaAvailable()) {
            return;
        }
        synchronized (this.mPackages) {
            if (this.mSettings.mPackagesToBeCleaned.isEmpty()) {
                return;
            }
            Intent intent = new Intent("android.content.pm.CLEAN_EXTERNAL_STORAGE");
            intent.setComponent(DEFAULT_CONTAINER_COMPONENT);
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                int dcsUid = -1;
                synchronized (this.mPackages) {
                    try {
                        if (!this.mDefaultContainerWhitelisted) {
                            this.mDefaultContainerWhitelisted = true;
                            PackageSetting ps = this.mSettings.mPackages.get(DEFAULT_CONTAINER_PACKAGE);
                            dcsUid = UserHandle.getUid(0, ps.appId);
                        }
                        int dcsUid2 = dcsUid;
                        try {
                            if (dcsUid2 > 0) {
                                try {
                                    am.backgroundWhitelistUid(dcsUid2);
                                } catch (RemoteException e) {
                                    return;
                                }
                            }
                            am.startService((IApplicationThread) null, intent, (String) null, false, this.mContext.getOpPackageName(), 0);
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
            }
        }
    }

    private int fixUpInstallReason(String installerPackageName, int installerUid, int installReason) {
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
    public void earlyBindToDefContainer() {
        this.mHandler.sendMessage(this.mHandler.obtainMessage(21));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void installStage(String packageName, File stagedDir, IPackageInstallObserver2 observer, PackageInstaller.SessionParams sessionParams, String installerPackageName, int installerUid, UserHandle user, PackageParser.SigningDetails signingDetails) {
        if (DEBUG_INSTANT && (sessionParams.installFlags & 2048) != 0) {
            Slog.d(TAG, "Ephemeral install of " + packageName);
        }
        VerificationInfo verificationInfo = new VerificationInfo(sessionParams.originatingUri, sessionParams.referrerUri, sessionParams.originatingUid, installerUid);
        OriginInfo origin = OriginInfo.fromStagedFile(stagedDir);
        Message msg = this.mHandler.obtainMessage(5);
        int installReason = fixUpInstallReason(installerPackageName, installerUid, sessionParams.installReason);
        InstallParams params = new InstallParams(origin, null, observer, sessionParams.installFlags, installerPackageName, sessionParams.volumeUuid, verificationInfo, user, sessionParams.abiOverride, sessionParams.grantedRuntimePermissions, signingDetails, installReason);
        params.setTraceMethod("installStage").setTraceCookie(System.identityHashCode(params));
        msg.obj = params;
        Trace.asyncTraceBegin(262144L, "installStage", System.identityHashCode(msg.obj));
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
        if (!sendBootCompleted || ArrayUtils.isEmpty(userIds)) {
            return;
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$8-IQ5_GLnR11f6LVoppcC-6hZ78
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.lambda$sendPackageAddedForNewUsers$5(PackageManagerService.this, userIds, packageName, includeStopped);
            }
        });
    }

    public static /* synthetic */ void lambda$sendPackageAddedForNewUsers$5(PackageManagerService packageManagerService, int[] userIds, String packageName, boolean includeStopped) {
        for (int userId : userIds) {
            packageManagerService.sendBootCompletedBroadcastToSystemApp(packageName, includeStopped, userId);
        }
    }

    private void sendBootCompletedBroadcastToSystemApp(String packageName, boolean includeStopped, int userId) {
        String[] requiredPermissions;
        if (!this.mUserManagerInternal.isUserRunning(userId)) {
            return;
        }
        IActivityManager am = ActivityManager.getService();
        try {
            Intent lockedBcIntent = new Intent("android.intent.action.LOCKED_BOOT_COMPLETED").setPackage(packageName);
            if (includeStopped) {
                lockedBcIntent.addFlags(32);
            }
            requiredPermissions = new String[]{"android.permission.RECEIVE_BOOT_COMPLETED"};
            try {
                am.broadcastIntent((IApplicationThread) null, lockedBcIntent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, false, false, userId);
            } catch (RemoteException e) {
                e = e;
            }
        } catch (RemoteException e2) {
            e = e2;
        }
        try {
            if (this.mUserManagerInternal.isUserUnlockingOrUnlocked(userId)) {
                Intent bcIntent = new Intent("android.intent.action.BOOT_COMPLETED").setPackage(packageName);
                if (includeStopped) {
                    bcIntent.addFlags(32);
                }
                am.broadcastIntent((IApplicationThread) null, bcIntent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, false, false, userId);
            }
        } catch (RemoteException e3) {
            e = e3;
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USERS", null);
        int callingUid = Binder.getCallingUid();
        PermissionManagerInternal permissionManagerInternal = this.mPermissionManager;
        permissionManagerInternal.enforceCrossUserPermission(callingUid, userId, true, true, "setApplicationHiddenSetting for user " + userId);
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
                        installExistingPackageAsUser(packageName, userId, 0, 3);
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

    private void sendPackagesSuspendedForUser(String[] pkgList, int userId, boolean suspended, PersistableBundle launcherExtras) {
        if (pkgList.length > 0) {
            Bundle extras = new Bundle(1);
            extras.putStringArray("android.intent.extra.changed_package_list", pkgList);
            if (launcherExtras != null) {
                extras.putBundle("android.intent.extra.LAUNCHER_EXTRAS", new Bundle(launcherExtras.deepCopy()));
            }
            sendPackageBroadcast(suspended ? "android.intent.action.PACKAGES_SUSPENDED" : "android.intent.action.PACKAGES_UNSUSPENDED", null, extras, 1073741824, null, null, new int[]{userId}, null);
        }
    }

    public boolean getApplicationHiddenSettingAsUser(String packageName, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_USERS", null);
        int callingUid = Binder.getCallingUid();
        PermissionManagerInternal permissionManagerInternal = this.mPermissionManager;
        permissionManagerInternal.enforceCrossUserPermission(callingUid, userId, true, false, "getApplicationHidden for user " + userId);
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

    public int installExistingPackageAsUser(String packageName, int userId, int installFlags, int installReason) {
        int[] userIds;
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
                                boolean installAllowed = false;
                                for (int checkUserId : sUserManager.getUserIds()) {
                                    installAllowed = !pkgSetting.getInstantApp(checkUserId);
                                    if (installAllowed) {
                                        break;
                                    }
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
                            setInstantAppForUser(pkgSetting, userId, instantApp, fullApp);
                            if (installed) {
                                if (pkgSetting.pkg != null) {
                                    synchronized (this.mInstallLock) {
                                        prepareAppDataAfterInstallLIF(pkgSetting.pkg);
                                    }
                                }
                                sendPackageAddedForUser(packageName, pkgSetting, userId);
                                synchronized (this.mPackages) {
                                    updateSequenceNumberLP(pkgSetting, new int[]{userId});
                                }
                            }
                            Binder.restoreCallingIdentity(callingId);
                            return 1;
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                } catch (Throwable th2) {
                    th = th2;
                    Binder.restoreCallingIdentity(callingId);
                    throw th;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        } catch (Throwable th4) {
            th = th4;
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

    public String[] setPackagesSuspendedAsUser(String[] packageNames, boolean suspended, PersistableBundle appExtras, PersistableBundle launcherExtras, String dialogMessage, String callingPackage, int userId) {
        long callingId;
        ArrayMap<String, PackageParser.Package> arrayMap;
        long callingId2;
        String packageName;
        String[] strArr = packageNames;
        boolean z = suspended;
        String str = callingPackage;
        this.mContext.enforceCallingOrSelfPermission("android.permission.SUSPEND_APPS", "setPackagesSuspendedAsUser");
        int callingUid = Binder.getCallingUid();
        int i = 0;
        if (callingUid != 0 && callingUid != 1000 && getPackageUid(str, 0, userId) != callingUid) {
            throw new SecurityException("Calling package " + str + " in user " + userId + " does not belong to calling uid " + callingUid);
        } else if (!PLATFORM_PACKAGE_NAME.equals(str) && this.mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(userId) != null) {
            throw new UnsupportedOperationException("Cannot suspend/unsuspend packages. User " + userId + " has an active DO or PO");
        } else if (ArrayUtils.isEmpty(packageNames)) {
            return strArr;
        } else {
            List<String> changedPackagesList = new ArrayList<>(strArr.length);
            List<String> unactionedPackages = new ArrayList<>(strArr.length);
            long callingId3 = Binder.clearCallingIdentity();
            try {
                ArrayMap<String, PackageParser.Package> arrayMap2 = this.mPackages;
                synchronized (arrayMap2) {
                    while (i < strArr.length) {
                        try {
                            try {
                                String packageName2 = strArr[i];
                                if (str.equals(packageName2)) {
                                    try {
                                        StringBuilder sb = new StringBuilder();
                                        sb.append("Calling package: ");
                                        sb.append(str);
                                        sb.append(" trying to ");
                                        sb.append(z ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "un");
                                        sb.append("suspend itself. Ignoring");
                                        Slog.w(TAG, sb.toString());
                                        unactionedPackages.add(packageName2);
                                    } catch (Throwable th) {
                                        th = th;
                                        arrayMap = arrayMap2;
                                        callingId = callingId3;
                                        while (true) {
                                            try {
                                                try {
                                                    break;
                                                } catch (Throwable th2) {
                                                    th = th2;
                                                    Binder.restoreCallingIdentity(callingId);
                                                    throw th;
                                                }
                                            } catch (Throwable th3) {
                                                th = th3;
                                            }
                                        }
                                        throw th;
                                    }
                                } else {
                                    PackageSetting pkgSetting = this.mSettings.mPackages.get(packageName2);
                                    try {
                                        if (pkgSetting == null) {
                                            packageName = packageName2;
                                            arrayMap = arrayMap2;
                                            callingId2 = callingId3;
                                        } else if (filterAppAccessLPr(pkgSetting, callingUid, userId)) {
                                            packageName = packageName2;
                                            arrayMap = arrayMap2;
                                            callingId2 = callingId3;
                                        } else if (!z || canSuspendPackageForUserLocked(packageName2, userId)) {
                                            boolean z2 = z;
                                            arrayMap = arrayMap2;
                                            callingId2 = callingId3;
                                            pkgSetting.setSuspended(z2, str, dialogMessage, appExtras, launcherExtras, userId);
                                            changedPackagesList.add(packageName2);
                                            i++;
                                            callingId3 = callingId2;
                                            arrayMap2 = arrayMap;
                                            strArr = packageNames;
                                            z = suspended;
                                            str = callingPackage;
                                        } else {
                                            unactionedPackages.add(packageName2);
                                        }
                                        Slog.w(TAG, "Could not find package setting for package: " + packageName + ". Skipping suspending/un-suspending.");
                                        unactionedPackages.add(packageName);
                                        i++;
                                        callingId3 = callingId2;
                                        arrayMap2 = arrayMap;
                                        strArr = packageNames;
                                        z = suspended;
                                        str = callingPackage;
                                    } catch (Throwable th4) {
                                        th = th4;
                                        callingId = callingId2;
                                        while (true) {
                                            break;
                                            break;
                                        }
                                        throw th;
                                    }
                                }
                                arrayMap = arrayMap2;
                                callingId2 = callingId3;
                                i++;
                                callingId3 = callingId2;
                                arrayMap2 = arrayMap;
                                strArr = packageNames;
                                z = suspended;
                                str = callingPackage;
                            } catch (Throwable th5) {
                                th = th5;
                                arrayMap = arrayMap2;
                                callingId = callingId3;
                            }
                        } catch (Throwable th6) {
                            th = th6;
                            arrayMap = arrayMap2;
                            callingId = callingId3;
                        }
                    }
                    arrayMap = arrayMap2;
                    long callingId4 = callingId3;
                    try {
                        Binder.restoreCallingIdentity(callingId4);
                        if (!changedPackagesList.isEmpty()) {
                            String[] changedPackages = (String[]) changedPackagesList.toArray(new String[changedPackagesList.size()]);
                            sendPackagesSuspendedForUser(changedPackages, userId, suspended, launcherExtras);
                            sendMyPackageSuspendedOrUnsuspended(changedPackages, suspended, appExtras, userId);
                            synchronized (this.mPackages) {
                                scheduleWritePackageRestrictionsLocked(userId);
                            }
                        }
                        return (String[]) unactionedPackages.toArray(new String[unactionedPackages.size()]);
                    } catch (Throwable th7) {
                        th = th7;
                        callingId = callingId4;
                        while (true) {
                            break;
                            break;
                        }
                        throw th;
                    }
                }
            } catch (Throwable th8) {
                th = th8;
                callingId = callingId3;
            }
        }
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
            action = "android.intent.action.MY_PACKAGE_SUSPENDED";
            if (appExtras != null) {
                Bundle bundledAppExtras = new Bundle(appExtras.deepCopy());
                intentExtras.putBundle("android.intent.extra.SUSPENDED_PACKAGE_EXTRAS", bundledAppExtras);
            }
        } else {
            action = "android.intent.action.MY_PACKAGE_UNSUSPENDED";
        }
        final String action2 = action;
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.9
            @Override // java.lang.Runnable
            public void run() {
                String[] strArr;
                try {
                    IActivityManager am = ActivityManager.getService();
                    if (am == null) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("IActivityManager null. Cannot send MY_PACKAGE_ ");
                        sb.append(suspended ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "UN");
                        sb.append("SUSPENDED broadcasts");
                        Slog.wtf(PackageManagerService.TAG, sb.toString());
                        return;
                    }
                    int[] targetUserIds = {userId};
                    for (String packageName : affectedPackages) {
                        PackageManagerService.this.doSendBroadcast(am, action2, null, intentExtras, 16777216, packageName, null, targetUserIds, false);
                    }
                } catch (RemoteException e) {
                }
            }
        });
    }

    public boolean isPackageSuspendedForUser(String packageName, int userId) {
        boolean suspended;
        int callingUid = Binder.getCallingUid();
        PermissionManagerInternal permissionManagerInternal = this.mPermissionManager;
        permissionManagerInternal.enforceCrossUserPermission(callingUid, userId, true, false, "isPackageSuspendedForUser for user " + userId);
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
            unsuspendForSuspendingPackages(new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$S4BXTl5Ly3EHhXAReFCtlz2B8eo
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean equals;
                    equals = packageName.equals((String) obj);
                    return equals;
                }
            }, userId);
        }
    }

    void unsuspendForNonSystemSuspendingPackages(ArraySet<Integer> userIds) {
        int sz = userIds.size();
        for (int i = 0; i < sz; i++) {
            unsuspendForSuspendingPackages(new Predicate() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$bnLYyNywBZdr_a6WGQKRTv8z0S4
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return PackageManagerService.lambda$unsuspendForNonSystemSuspendingPackages$6((String) obj);
                }
            }, userIds.valueAt(i).intValue());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$unsuspendForNonSystemSuspendingPackages$6(String suspendingPackage) {
        return !PLATFORM_PACKAGE_NAME.equals(suspendingPackage);
    }

    private void unsuspendForSuspendingPackages(Predicate<String> packagePredicate, int userId) {
        List<String> affectedPackages = new ArrayList<>();
        synchronized (this.mPackages) {
            for (PackageSetting ps : this.mSettings.mPackages.values()) {
                PackageUserState pus = ps.readUserState(userId);
                if (pus.suspended && packagePredicate.test(pus.suspendingPackage)) {
                    ps.setSuspended(false, null, null, null, null, userId);
                    affectedPackages.add(ps.name);
                }
            }
        }
        if (!affectedPackages.isEmpty()) {
            String[] packageArray = (String[]) affectedPackages.toArray(new String[affectedPackages.size()]);
            sendMyPackageSuspendedOrUnsuspended(packageArray, false, null, userId);
            sendPackagesSuspendedForUser(packageArray, userId, false, null);
            this.mSettings.writePackageRestrictionsLPr(userId);
        }
    }

    @GuardedBy("mPackages")
    private boolean canSuspendPackageForUserLocked(String packageName, int userId) {
        if (isPackageDeviceAdmin(packageName, userId)) {
            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": has an active device admin");
            return false;
        }
        String activeLauncherPackageName = getActiveLauncherPackageName(userId);
        if (packageName.equals(activeLauncherPackageName)) {
            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": contains the active launcher");
            return false;
        } else if (packageName.equals(this.mRequiredInstallerPackage)) {
            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": required for package installation");
            return false;
        } else if (packageName.equals(this.mRequiredUninstallerPackage)) {
            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": required for package uninstallation");
            return false;
        } else if (packageName.equals(this.mRequiredVerifierPackage)) {
            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": required for package verification");
            return false;
        } else if (packageName.equals(getDefaultDialerPackageName(userId))) {
            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": is the default dialer");
            return false;
        } else if (this.mProtectedPackages.isPackageStateProtected(userId, packageName)) {
            Slog.w(TAG, "Cannot suspend package \"" + packageName + "\": protected package");
            return false;
        } else {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if (pkg != null && pkg.applicationInfo.isStaticSharedLibrary()) {
                Slog.w(TAG, "Cannot suspend package: " + packageName + " providing static shared library: " + pkg.staticSharedLibName);
                return false;
            } else if (PLATFORM_PACKAGE_NAME.equals(packageName)) {
                Slog.w(TAG, "Cannot suspend package: " + packageName);
                return false;
            } else {
                return true;
            }
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
        String defaultDialerPackageNameLPw;
        synchronized (this.mPackages) {
            defaultDialerPackageNameLPw = this.mSettings.getDefaultDialerPackageNameLPw(userId);
        }
        return defaultDialerPackageNameLPw;
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
        To view partially-correct add '--show-bad-code' argument
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
                if (DEBUG_VERIFY) {
                    Slog.d(TAG, "Added sufficient verifier " + verifierInfo.packageName + " with the correct signature");
                }
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

    public void finishPackageInstall(int token, boolean didLaunch) {
        enforceSystemOrRoot("Only the system is allowed to finish installs");
        if (DEBUG_INSTALL) {
            Slog.v(TAG, "BM finishing package install for " + token);
        }
        Trace.asyncTraceEnd(262144L, "restore", token);
        Message msg = this.mHandler.obtainMessage(9, token, didLaunch ? 1 : 0);
        this.mHandler.sendMessage(msg);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long getVerificationTimeout() {
        return Settings.Global.getLong(this.mContext.getContentResolver(), "verifier_timeout", 10000L);
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
        } else if ((installFlags & 2048) != 0 && this.mInstantAppInstallerActivity != null && this.mInstantAppInstallerActivity.packageName.equals(this.mRequiredVerifierPackage)) {
            try {
                ((AppOpsManager) this.mContext.getSystemService(AppOpsManager.class)).checkPackage(installerUid, this.mRequiredVerifierPackage);
                if (DEBUG_VERIFY) {
                    Slog.i(TAG, "disable verification for instant app");
                }
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
        boolean result;
        this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        }
        synchronized (this.mPackages) {
            result = this.mSettings.setDefaultBrowserPackageNameLPw(packageName, userId);
            if (packageName != null) {
                this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultBrowser(packageName, userId);
            }
        }
        return result;
    }

    public String getDefaultBrowserPackageName(int userId) {
        String defaultBrowserPackageNameLPw;
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", null);
        }
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (this.mPackages) {
            defaultBrowserPackageNameLPw = this.mSettings.getDefaultBrowserPackageNameLPw(userId);
        }
        return defaultBrowserPackageNameLPw;
    }

    private int getUnknownSourcesSettings() {
        return Settings.Secure.getInt(this.mContext.getContentResolver(), "install_non_market_apps", -1);
    }

    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        PackageSetting installerPackageSetting;
        Signature[] callerSignature;
        PackageSetting setting;
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return;
        }
        synchronized (this.mPackages) {
            PackageSetting targetPackageSetting = this.mSettings.mPackages.get(targetPackage);
            if (targetPackageSetting == null || filterAppAccessLPr(targetPackageSetting, callingUid, UserHandle.getUserId(callingUid))) {
                throw new IllegalArgumentException("Unknown target package: " + targetPackage);
            }
            if (installerPackageName != null) {
                installerPackageSetting = this.mSettings.mPackages.get(installerPackageName);
                if (installerPackageSetting == null) {
                    throw new IllegalArgumentException("Unknown installer package: " + installerPackageName);
                }
            } else {
                installerPackageSetting = null;
            }
            Object obj = this.mSettings.getUserIdLPr(callingUid);
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
                if (targetPackageSetting.installerPackageName != null && (setting = this.mSettings.mPackages.get(targetPackageSetting.installerPackageName)) != null && PackageManagerServiceUtils.compareSignatures(callerSignature, setting.signatures.mSigningDetails.signatures) != 0) {
                    throw new SecurityException("Caller does not have same cert as old installer package " + targetPackageSetting.installerPackageName);
                }
                targetPackageSetting.installerPackageName = installerPackageName;
                if (installerPackageName != null) {
                    this.mSettings.mInstallerPackages.add(installerPackageName);
                }
                scheduleWriteSettingsLocked();
            } else {
                throw new SecurityException("Unknown calling UID: " + callingUid);
            }
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
    public void processPendingInstall(final InstallArgs args, final int currentStatus) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.10
            @Override // java.lang.Runnable
            public void run() {
                PackageManagerService.this.mHandler.removeCallbacks(this);
                PackageInstalledInfo res = new PackageInstalledInfo();
                res.setReturnCode(currentStatus);
                res.uid = -1;
                res.pkg = null;
                res.removedInfo = null;
                if (res.returnCode == 1) {
                    args.doPreInstall(res.returnCode);
                    synchronized (PackageManagerService.this.mInstallLock) {
                        PackageManagerService.this.installPackageTracedLI(args, res);
                    }
                    args.doPostInstall(res.returnCode, res.uid);
                }
                boolean update = (res.removedInfo == null || res.removedInfo.removedPackage == null) ? false : true;
                int flags = res.pkg == null ? 0 : res.pkg.applicationInfo.flags;
                boolean doRestore = (update || (32768 & flags) == 0) ? false : true;
                if (PackageManagerService.this.mNextInstallToken < 0) {
                    PackageManagerService.this.mNextInstallToken = 1;
                }
                PackageManagerService packageManagerService = PackageManagerService.this;
                int token = packageManagerService.mNextInstallToken;
                packageManagerService.mNextInstallToken = token + 1;
                PostInstallData data = new PostInstallData(args, res);
                PackageManagerService.this.mRunningInstalls.put(token, data);
                if (PackageManagerService.DEBUG_INSTALL) {
                    Log.v(PackageManagerService.TAG, "+ starting restore round-trip " + token);
                }
                if (res.returnCode == 1 && doRestore) {
                    IBackupManager bm = IBackupManager.Stub.asInterface(ServiceManager.getService(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD));
                    if (bm != null) {
                        if (PackageManagerService.DEBUG_INSTALL) {
                            Log.v(PackageManagerService.TAG, "token " + token + " to BM for possible restore");
                        }
                        Trace.asyncTraceBegin(262144L, "restore", token);
                        try {
                            if (bm.isBackupServiceActive(0)) {
                                bm.restoreAtInstall(res.pkg.applicationInfo.packageName, token);
                            } else {
                                doRestore = false;
                            }
                        } catch (RemoteException e) {
                        } catch (Exception e2) {
                            Slog.e(PackageManagerService.TAG, "Exception trying to enqueue restore", e2);
                            doRestore = false;
                        }
                    } else {
                        Slog.e(PackageManagerService.TAG, "Backup Manager not found!");
                        doRestore = false;
                    }
                }
                if (!doRestore) {
                    if (PackageManagerService.DEBUG_INSTALL) {
                        Log.v(PackageManagerService.TAG, "No restore - queue post-install for " + token);
                    }
                    Trace.asyncTraceBegin(262144L, "postInstall", token);
                    Message msg = PackageManagerService.this.mHandler.obtainMessage(9, token, 0);
                    PackageManagerService.this.mHandler.sendMessage(msg);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyFirstLaunch(final String packageName, final String installerPackage, final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.11
            @Override // java.lang.Runnable
            public void run() {
                for (int i = 0; i < PackageManagerService.this.mRunningInstalls.size(); i++) {
                    PostInstallData data = PackageManagerService.this.mRunningInstalls.valueAt(i);
                    if (data.res.returnCode == 1 && packageName.equals(data.res.pkg.applicationInfo.packageName)) {
                        for (int uIndex = 0; uIndex < data.res.newUsers.length; uIndex++) {
                            if (userId == data.res.newUsers[uIndex]) {
                                if (PackageManagerService.DEBUG_BACKUP) {
                                    Slog.i(PackageManagerService.TAG, "Package " + packageName + " being restored so deferring FIRST_LAUNCH");
                                    return;
                                }
                                return;
                            }
                        }
                        continue;
                    }
                }
                if (PackageManagerService.DEBUG_BACKUP) {
                    Slog.i(PackageManagerService.TAG, "Package " + packageName + " sending normal FIRST_LAUNCH");
                }
                boolean isInstantApp = PackageManagerService.this.isInstantApp(packageName, userId);
                int[] userIds = isInstantApp ? PackageManagerService.EMPTY_INT_ARRAY : new int[]{userId};
                int[] instantUserIds = isInstantApp ? new int[]{userId} : PackageManagerService.EMPTY_INT_ARRAY;
                PackageManagerService.this.sendFirstLaunchBroadcast(packageName, installerPackage, userIds, instantUserIds);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendFirstLaunchBroadcast(String pkgName, String installerPkg, int[] userIds, int[] instantUserIds) {
        sendPackageBroadcast("android.intent.action.PACKAGE_FIRST_LAUNCH", pkgName, null, 0, installerPkg, null, userIds, instantUserIds);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class HandlerParams {
        private static final int MAX_RETRIES = 4;
        private int mRetries = 0;
        private final UserHandle mUser;
        int traceCookie;
        String traceMethod;

        abstract void handleReturnCode();

        abstract void handleServiceError();

        abstract void handleStartCopy() throws RemoteException;

        HandlerParams(UserHandle user) {
            this.mUser = user;
        }

        UserHandle getUser() {
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

        final boolean startCopy() {
            int i;
            boolean res = false;
            try {
                if (PackageManagerService.DEBUG_INSTALL) {
                    Slog.i(PackageManagerService.TAG, "startCopy " + this.mUser + ": " + this);
                }
                i = this.mRetries + 1;
                this.mRetries = i;
            } catch (RemoteException e) {
                if (PackageManagerService.DEBUG_INSTALL) {
                    Slog.i(PackageManagerService.TAG, "Posting install MCS_RECONNECT");
                }
                PackageManagerService.this.mHandler.sendEmptyMessage(10);
            }
            if (i > 4) {
                Slog.w(PackageManagerService.TAG, "Failed to invoke remote methods on default container service. Giving up");
                PackageManagerService.this.mHandler.sendEmptyMessage(11);
                handleServiceError();
                return false;
            }
            handleStartCopy();
            res = true;
            handleReturnCode();
            return res;
        }

        final void serviceError() {
            if (PackageManagerService.DEBUG_INSTALL) {
                Slog.i(PackageManagerService.TAG, "serviceError");
            }
            handleServiceError();
            handleReturnCode();
        }
    }

    private static void clearDirectory(IMediaContainerService mcs, File[] paths) {
        for (File path : paths) {
            try {
                mcs.clearDirectory(path.getAbsolutePath());
            } catch (RemoteException e) {
            }
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
    public class InstallParams extends HandlerParams {
        final String[] grantedRuntimePermissions;
        int installFlags;
        final int installReason;
        final String installerPackageName;
        private InstallArgs mArgs;
        private int mRet;
        final MoveInfo move;
        final IPackageInstallObserver2 observer;
        final OriginInfo origin;
        final String packageAbiOverride;
        final PackageParser.SigningDetails signingDetails;
        final VerificationInfo verificationInfo;
        final String volumeUuid;

        InstallParams(OriginInfo origin, MoveInfo move, IPackageInstallObserver2 observer, int installFlags, String installerPackageName, String volumeUuid, VerificationInfo verificationInfo, UserHandle user, String packageAbiOverride, String[] grantedPermissions, PackageParser.SigningDetails signingDetails, int installReason) {
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
            this.signingDetails = signingDetails;
            this.installReason = installReason;
        }

        public String toString() {
            return "InstallParams{" + Integer.toHexString(System.identityHashCode(this)) + " file=" + this.origin.file + "}";
        }

        private int installLocationPolicy(PackageInfoLite pkgLite) {
            PackageSetting ps;
            String packageName = pkgLite.packageName;
            int installLocation = pkgLite.installLocation;
            boolean downgradePermitted = false;
            boolean onSd = (this.installFlags & 8) != 0;
            synchronized (PackageManagerService.this.mPackages) {
                PackageParser.Package installedPkg = PackageManagerService.this.mPackages.get(packageName);
                PackageParser.Package dataOwnerPkg = installedPkg;
                if (dataOwnerPkg == null && (ps = PackageManagerService.this.mSettings.mPackages.get(packageName)) != null) {
                    dataOwnerPkg = ps.pkg;
                }
                if (dataOwnerPkg != null) {
                    boolean downgradeRequested = (this.installFlags & 128) != 0;
                    boolean packageDebuggable = (dataOwnerPkg.applicationInfo.flags & 2) != 0;
                    if (downgradeRequested && (Build.IS_DEBUGGABLE || packageDebuggable)) {
                        downgradePermitted = true;
                    }
                    if (!downgradePermitted) {
                        try {
                            PackageManagerService.checkDowngrade(dataOwnerPkg, pkgLite);
                        } catch (PackageManagerException e) {
                            Slog.w(PackageManagerService.TAG, "Downgrade detected: " + e.getMessage());
                            return -7;
                        }
                    }
                }
                if (installedPkg != null) {
                    if ((this.installFlags & 2) != 0) {
                        if ((installedPkg.applicationInfo.flags & 1) != 0) {
                            if (onSd) {
                                Slog.w(PackageManagerService.TAG, "Cannot install update to system app on sdcard");
                                return -3;
                            }
                            return 1;
                        } else if (onSd) {
                            return 2;
                        } else {
                            if (installLocation == 1) {
                                return 1;
                            }
                            if (installLocation != 2) {
                                return PackageManagerService.isExternal(installedPkg) ? 2 : 1;
                            }
                        }
                    } else {
                        return -4;
                    }
                }
                if (onSd) {
                    return 2;
                }
                return pkgLite.recommendedInstallLocation;
            }
        }

        @Override // com.android.server.pm.PackageManagerService.HandlerParams
        public void handleStartCopy() throws RemoteException {
            int ret;
            int ret2 = 1;
            if (this.origin.staged) {
                if (this.origin.file == null) {
                    throw new IllegalStateException("Invalid stage location");
                }
                this.installFlags |= 16;
                this.installFlags &= -9;
            }
            boolean onSd = (this.installFlags & 8) != 0;
            boolean onInt = (this.installFlags & 16) != 0;
            boolean ephemeral = (this.installFlags & 2048) != 0;
            PackageInfoLite pkgLite = null;
            if (onInt && onSd) {
                Slog.w(PackageManagerService.TAG, "Conflicting flags specified for installing on both internal and external");
                ret2 = -19;
            } else if (onSd && ephemeral) {
                Slog.w(PackageManagerService.TAG, "Conflicting flags specified for installing ephemeral on external");
                ret2 = -19;
            } else {
                PackageInfoLite pkgLite2 = PackageManagerService.this.mContainerService.getMinimalPackageInfo(this.origin.resolvedPath, this.installFlags, this.packageAbiOverride);
                if (PackageManagerService.DEBUG_INSTANT && ephemeral) {
                    Slog.v(PackageManagerService.TAG, "pkgLite for install: " + pkgLite2);
                }
                if (this.origin.staged || pkgLite2.recommendedInstallLocation != -1) {
                    pkgLite = pkgLite2;
                } else {
                    StorageManager storage = StorageManager.from(PackageManagerService.this.mContext);
                    long lowThreshold = storage.getStorageLowBytes(Environment.getDataDirectory());
                    long sizeBytes = PackageManagerService.this.mContainerService.calculateInstalledSize(this.origin.resolvedPath, this.packageAbiOverride);
                    try {
                        PackageManagerService.this.mInstaller.freeCache(null, sizeBytes + lowThreshold, 0L, 0);
                        pkgLite = PackageManagerService.this.mContainerService.getMinimalPackageInfo(this.origin.resolvedPath, this.installFlags, this.packageAbiOverride);
                    } catch (Installer.InstallerException e) {
                        Slog.w(PackageManagerService.TAG, "Failed to free cache", e);
                        pkgLite = pkgLite2;
                    }
                    if (pkgLite.recommendedInstallLocation == -6) {
                        pkgLite.recommendedInstallLocation = -1;
                    }
                }
            }
            if (ret2 == 1) {
                int loc = pkgLite.recommendedInstallLocation;
                if (loc == -3) {
                    ret2 = -19;
                } else if (loc == -4) {
                    ret2 = -1;
                } else if (loc == -1) {
                    ret2 = -4;
                } else if (loc == -2) {
                    ret2 = -2;
                } else if (loc == -6) {
                    ret2 = -3;
                } else if (loc == -5) {
                    ret2 = -20;
                } else {
                    int loc2 = installLocationPolicy(pkgLite);
                    if (loc2 == -7) {
                        ret2 = -25;
                    } else if (!onSd && !onInt) {
                        if (loc2 == 2) {
                            this.installFlags |= 8;
                            this.installFlags &= -17;
                        } else if (loc2 == 3) {
                            if (PackageManagerService.DEBUG_INSTANT) {
                                Slog.v(PackageManagerService.TAG, "...setting INSTALL_EPHEMERAL install flag");
                            }
                            this.installFlags |= 2048;
                            this.installFlags &= -25;
                        } else {
                            this.installFlags |= 16;
                            this.installFlags &= -9;
                        }
                    }
                }
            }
            InstallArgs args = PackageManagerService.this.createInstallArgs(this);
            this.mArgs = args;
            if (ret2 == 1) {
                UserHandle verifierUser = getUser();
                if (verifierUser == UserHandle.ALL) {
                    verifierUser = UserHandle.SYSTEM;
                }
                int requiredUid = PackageManagerService.this.mRequiredVerifierPackage == null ? -1 : PackageManagerService.this.getPackageUid(PackageManagerService.this.mRequiredVerifierPackage, 268435456, verifierUser.getIdentifier());
                int installerUid = this.verificationInfo == null ? -1 : this.verificationInfo.installerUid;
                if (this.origin.existing || requiredUid == -1 || !PackageManagerService.this.isVerificationEnabled(verifierUser.getIdentifier(), this.installFlags, installerUid)) {
                    ret2 = args.copyApk(PackageManagerService.this.mContainerService, true);
                } else {
                    Intent verification = new Intent("android.intent.action.PACKAGE_NEEDS_VERIFICATION");
                    verification.addFlags(268435456);
                    verification.setDataAndType(Uri.fromFile(new File(this.origin.resolvedPath)), PackageManagerService.PACKAGE_MIME_TYPE);
                    verification.addFlags(1);
                    List<ResolveInfo> receivers = PackageManagerService.this.queryIntentReceiversInternal(verification, PackageManagerService.PACKAGE_MIME_TYPE, 0, verifierUser.getIdentifier(), false);
                    if (PackageManagerService.DEBUG_VERIFY) {
                        Slog.d(PackageManagerService.TAG, "Found " + receivers.size() + " verifiers for intent " + verification.toString() + " with " + pkgLite.verifiers.length + " optional verifiers");
                    }
                    final int verificationId = PackageManagerService.access$4808(PackageManagerService.this);
                    verification.putExtra("android.content.pm.extra.VERIFICATION_ID", verificationId);
                    verification.putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE", this.installerPackageName);
                    verification.putExtra("android.content.pm.extra.VERIFICATION_INSTALL_FLAGS", this.installFlags);
                    verification.putExtra("android.content.pm.extra.VERIFICATION_PACKAGE_NAME", pkgLite.packageName);
                    verification.putExtra("android.content.pm.extra.VERIFICATION_VERSION_CODE", pkgLite.versionCode);
                    verification.putExtra("android.content.pm.extra.VERIFICATION_LONG_VERSION_CODE", pkgLite.getLongVersionCode());
                    if (this.verificationInfo != null) {
                        if (this.verificationInfo.originatingUri != null) {
                            verification.putExtra("android.intent.extra.ORIGINATING_URI", this.verificationInfo.originatingUri);
                        }
                        if (this.verificationInfo.referrer != null) {
                            verification.putExtra("android.intent.extra.REFERRER", this.verificationInfo.referrer);
                        }
                        if (this.verificationInfo.originatingUid >= 0) {
                            verification.putExtra("android.intent.extra.ORIGINATING_UID", this.verificationInfo.originatingUid);
                        }
                        if (this.verificationInfo.installerUid >= 0) {
                            verification.putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_UID", this.verificationInfo.installerUid);
                        }
                    }
                    PackageVerificationState verificationState = new PackageVerificationState(requiredUid, args);
                    PackageManagerService.this.mPendingVerification.append(verificationId, verificationState);
                    List<ComponentName> sufficientVerifiers = PackageManagerService.this.matchVerifiers(pkgLite, receivers, verificationState);
                    DeviceIdleController.LocalService idleController = PackageManagerService.this.getDeviceIdleController();
                    long idleDuration = PackageManagerService.this.getVerificationTimeout();
                    if (sufficientVerifiers != null) {
                        int N = sufficientVerifiers.size();
                        if (N != 0) {
                            ret = ret2;
                            int i = 0;
                            while (true) {
                                int i2 = i;
                                if (i2 >= N) {
                                    break;
                                }
                                ComponentName verifierComponent = sufficientVerifiers.get(i2);
                                idleController.addPowerSaveTempWhitelistApp(Process.myUid(), verifierComponent.getPackageName(), idleDuration, verifierUser.getIdentifier(), false, "package verifier");
                                boolean onSd2 = onSd;
                                Intent sufficientIntent = new Intent(verification);
                                sufficientIntent.setComponent(verifierComponent);
                                PackageManagerService.this.mContext.sendBroadcastAsUser(sufficientIntent, verifierUser);
                                i = i2 + 1;
                                onSd = onSd2;
                            }
                        } else {
                            Slog.i(PackageManagerService.TAG, "Additional verifiers required, but none installed.");
                            ret2 = -22;
                            ComponentName requiredVerifierComponent = PackageManagerService.this.matchComponentForVerifier(PackageManagerService.this.mRequiredVerifierPackage, receivers);
                            if (ret2 != 1 && PackageManagerService.this.mRequiredVerifierPackage != null) {
                                Trace.asyncTraceBegin(262144L, "verification", verificationId);
                                verification.setComponent(requiredVerifierComponent);
                                idleController.addPowerSaveTempWhitelistApp(Process.myUid(), PackageManagerService.this.mRequiredVerifierPackage, idleDuration, verifierUser.getIdentifier(), false, "package verifier");
                                PackageManagerService.this.mContext.sendOrderedBroadcastAsUser(verification, verifierUser, "android.permission.PACKAGE_VERIFICATION_AGENT", new BroadcastReceiver() { // from class: com.android.server.pm.PackageManagerService.InstallParams.1
                                    @Override // android.content.BroadcastReceiver
                                    public void onReceive(Context context, Intent intent) {
                                        Message msg = PackageManagerService.this.mHandler.obtainMessage(16);
                                        msg.arg1 = verificationId;
                                        PackageManagerService.this.mHandler.sendMessageDelayed(msg, PackageManagerService.this.getVerificationTimeout());
                                    }
                                }, null, 0, null, null);
                                this.mArgs = null;
                            }
                        }
                    } else {
                        ret = ret2;
                    }
                    ret2 = ret;
                    ComponentName requiredVerifierComponent2 = PackageManagerService.this.matchComponentForVerifier(PackageManagerService.this.mRequiredVerifierPackage, receivers);
                    if (ret2 != 1) {
                    }
                }
            }
            this.mRet = ret2;
        }

        @Override // com.android.server.pm.PackageManagerService.HandlerParams
        void handleReturnCode() {
            if (this.mArgs != null) {
                PackageManagerService.this.processPendingInstall(this.mArgs, this.mRet);
            }
        }

        @Override // com.android.server.pm.PackageManagerService.HandlerParams
        void handleServiceError() {
            this.mArgs = PackageManagerService.this.createInstallArgs(this);
            this.mRet = RequestStatus.SYS_ETIMEDOUT;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public InstallArgs createInstallArgs(InstallParams params) {
        if (params.move != null) {
            return new MoveInstallArgs(params);
        }
        return new FileInstallArgs(params);
    }

    private InstallArgs createInstallArgsForExisting(int installFlags, String codePath, String resourcePath, String[] instructionSets) {
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
        final MoveInfo move;
        final IPackageInstallObserver2 observer;
        final OriginInfo origin;
        final PackageParser.SigningDetails signingDetails;
        final int traceCookie;
        final String traceMethod;
        final UserHandle user;
        final String volumeUuid;

        abstract void cleanUpResourcesLI();

        abstract int copyApk(IMediaContainerService iMediaContainerService, boolean z) throws RemoteException;

        abstract boolean doPostDeleteLI(boolean z);

        abstract int doPostInstall(int i, int i2);

        abstract int doPreInstall(int i);

        abstract boolean doRename(int i, PackageParser.Package r2, String str);

        abstract String getCodePath();

        abstract String getResourcePath();

        InstallArgs(OriginInfo origin, MoveInfo move, IPackageInstallObserver2 observer, int installFlags, String installerPackageName, String volumeUuid, UserHandle user, String[] instructionSets, String abiOverride, String[] installGrantPermissions, String traceMethod, int traceCookie, PackageParser.SigningDetails signingDetails, int installReason) {
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
            this.traceMethod = traceMethod;
            this.traceCookie = traceCookie;
            this.signingDetails = signingDetails;
            this.installReason = installReason;
        }

        int doPreCopy() {
            return 1;
        }

        int doPostCopy(int uid) {
            return 1;
        }

        protected boolean isFwdLocked() {
            return (this.installFlags & 1) != 0;
        }

        protected boolean isExternalAsec() {
            return (this.installFlags & 8) != 0;
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
            super(params.origin, params.move, params.observer, params.installFlags, params.installerPackageName, params.volumeUuid, params.getUser(), null, params.packageAbiOverride, params.grantedRuntimePermissions, params.traceMethod, params.traceCookie, params.signingDetails, params.installReason);
            if (isFwdLocked()) {
                throw new IllegalArgumentException("Forward locking only supported in ASEC");
            }
        }

        FileInstallArgs(String codePath, String resourcePath, String[] instructionSets) {
            super(OriginInfo.fromNothing(), null, null, 0, null, null, null, instructionSets, null, null, null, 0, PackageParser.SigningDetails.UNKNOWN, 0);
            this.codeFile = codePath != null ? new File(codePath) : null;
            this.resourceFile = resourcePath != null ? new File(resourcePath) : null;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException {
            Trace.traceBegin(262144L, "copyApk");
            try {
                return doCopyApk(imcs, temp);
            } finally {
                Trace.traceEnd(262144L);
            }
        }

        private int doCopyApk(IMediaContainerService imcs, boolean temp) throws RemoteException {
            int ret;
            if (this.origin.staged) {
                if (PackageManagerService.DEBUG_INSTALL) {
                    Slog.d(PackageManagerService.TAG, this.origin.file + " already staged; skipping copy");
                }
                this.codeFile = this.origin.file;
                this.resourceFile = this.origin.file;
                return 1;
            }
            try {
                boolean isEphemeral = (this.installFlags & 2048) != 0;
                File tempDir = PackageManagerService.this.mInstallerService.allocateStageDirLegacy(this.volumeUuid, isEphemeral);
                this.codeFile = tempDir;
                this.resourceFile = tempDir;
                int ret2 = imcs.copyPackage(this.origin.file.getAbsolutePath(), new IParcelFileDescriptorFactory.Stub() { // from class: com.android.server.pm.PackageManagerService.FileInstallArgs.1
                    public ParcelFileDescriptor open(String name, int mode) throws RemoteException {
                        if (!FileUtils.isValidExtFilename(name)) {
                            throw new IllegalArgumentException("Invalid filename: " + name);
                        }
                        try {
                            File file = new File(FileInstallArgs.this.codeFile, name);
                            FileDescriptor fd = Os.open(file.getAbsolutePath(), OsConstants.O_RDWR | OsConstants.O_CREAT, 420);
                            Os.chmod(file.getAbsolutePath(), 420);
                            return new ParcelFileDescriptor(fd);
                        } catch (ErrnoException e) {
                            throw new RemoteException("Failed to open: " + e.getMessage());
                        }
                    }
                });
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
                    } catch (IOException e) {
                        Slog.e(PackageManagerService.TAG, "Copying native libraries failed", e);
                        ret = RequestStatus.SYS_ETIMEDOUT;
                    }
                    return ret;
                } finally {
                    IoUtils.closeQuietly(handle);
                }
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
        boolean doRename(int status, PackageParser.Package pkg, String oldCodePath) {
            if (status != 1) {
                cleanUp();
                return false;
            }
            File targetDir = this.codeFile.getParentFile();
            File beforeCodeFile = this.codeFile;
            File afterCodeFile = PackageManagerService.this.getNextCodePath(targetDir, pkg.packageName);
            if (PackageManagerService.DEBUG_INSTALL) {
                Slog.d(PackageManagerService.TAG, "Renaming " + beforeCodeFile + " to " + afterCodeFile);
            }
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
            if (this.codeFile != null) {
                return this.codeFile.getAbsolutePath();
            }
            return null;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        String getResourcePath() {
            if (this.resourceFile != null) {
                return this.resourceFile.getAbsolutePath();
            }
            return null;
        }

        private boolean cleanUp() {
            if (this.codeFile == null || !this.codeFile.exists()) {
                return false;
            }
            PackageManagerService.this.removeCodePathLI(this.codeFile);
            if (this.resourceFile != null && !FileUtils.contains(this.codeFile, this.resourceFile)) {
                this.resourceFile.delete();
                return true;
            }
            return true;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        void cleanUpResourcesLI() {
            List<String> allCodePaths = Collections.EMPTY_LIST;
            if (this.codeFile != null && this.codeFile.exists()) {
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

    static String cidFromCodePath(String fullCodePath) {
        int eidx = fullCodePath.lastIndexOf(SliceClientPermissions.SliceAuthority.DELIMITER);
        String subStr1 = fullCodePath.substring(0, eidx);
        int sidx = subStr1.lastIndexOf(SliceClientPermissions.SliceAuthority.DELIMITER);
        return subStr1.substring(sidx + 1, eidx);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class MoveInstallArgs extends InstallArgs {
        private File codeFile;
        private File resourceFile;

        MoveInstallArgs(InstallParams params) {
            super(params.origin, params.move, params.observer, params.installFlags, params.installerPackageName, params.volumeUuid, params.getUser(), null, params.packageAbiOverride, params.grantedRuntimePermissions, params.traceMethod, params.traceCookie, params.signingDetails, params.installReason);
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        int copyApk(IMediaContainerService imcs, boolean temp) {
            if (PackageManagerService.DEBUG_INSTALL) {
                Slog.d(PackageManagerService.TAG, "Moving " + this.move.packageName + " from " + this.move.fromUuid + " to " + this.move.toUuid);
            }
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
            if (PackageManagerService.DEBUG_INSTALL) {
                Slog.d(PackageManagerService.TAG, "codeFile after move is " + this.codeFile);
                return 1;
            }
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
        boolean doRename(int status, PackageParser.Package pkg, String oldCodePath) {
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
            if (this.codeFile != null) {
                return this.codeFile.getAbsolutePath();
            }
            return null;
        }

        @Override // com.android.server.pm.PackageManagerService.InstallArgs
        String getResourcePath() {
            if (this.resourceFile != null) {
                return this.resourceFile.getAbsolutePath();
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

    static String getAsecPackageName(String packageCid) {
        int idx = packageCid.lastIndexOf(INSTALL_PACKAGE_SUFFIX);
        if (idx == -1) {
            return packageCid;
        }
        return packageCid.substring(0, idx);
    }

    private static String getNextCodePath(String oldCodePath, String prefix, String suffix) {
        String subStr;
        int idx = 1;
        if (oldCodePath != null) {
            String subStr2 = oldCodePath;
            if (suffix != null && subStr2.endsWith(suffix)) {
                subStr2 = subStr2.substring(0, subStr2.length() - suffix.length());
            }
            int sidx = subStr2.lastIndexOf(prefix);
            if (sidx != -1 && (subStr = subStr2.substring(prefix.length() + sidx)) != null) {
                if (subStr.startsWith(INSTALL_PACKAGE_SUFFIX)) {
                    subStr = subStr.substring(INSTALL_PACKAGE_SUFFIX.length());
                }
                try {
                    int idx2 = Integer.parseInt(subStr);
                    idx = idx2 <= 1 ? idx2 + 1 : idx2 - 1;
                } catch (NumberFormatException e) {
                }
            }
        }
        String idxStr = INSTALL_PACKAGE_SUFFIX + Integer.toString(idx);
        return prefix + idxStr;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public File getNextCodePath(File targetDir, String packageName) {
        File result;
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        do {
            random.nextBytes(bytes);
            String suffix = Base64.encodeToString(bytes, 10);
            result = new File(targetDir, packageName + INSTALL_PACKAGE_SUFFIX + suffix);
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
            int childCount = this.addedChildPackages != null ? this.addedChildPackages.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).setError(msg, e);
            }
            Slog.w(PackageManagerService.TAG, msg, e);
        }

        public void setError(String msg, PackageManagerException e) {
            this.returnCode = e.error;
            setReturnMessage(ExceptionUtils.getCompleteMessage(msg, e));
            int childCount = this.addedChildPackages != null ? this.addedChildPackages.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).setError(msg, e);
            }
            Slog.w(PackageManagerService.TAG, msg, e);
        }

        public void setReturnCode(int returnCode) {
            this.returnCode = returnCode;
            int childCount = this.addedChildPackages != null ? this.addedChildPackages.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).returnCode = returnCode;
            }
        }

        private void setReturnMessage(String returnMsg) {
            this.returnMsg = returnMsg;
            int childCount = this.addedChildPackages != null ? this.addedChildPackages.size() : 0;
            for (int i = 0; i < childCount; i++) {
                this.addedChildPackages.valueAt(i).returnMsg = returnMsg;
            }
        }
    }

    private void installNewPackageLIF(PackageParser.Package pkg, int parseFlags, int scanFlags, UserHandle user, String installerPackageName, String volumeUuid, PackageInstalledInfo res, int installReason) {
        Trace.traceBegin(262144L, "installNewPackage");
        String pkgName = pkg.packageName;
        if (DEBUG_INSTALL) {
            Slog.d(TAG, "installNewPackageLI: " + pkg);
        }
        synchronized (this.mPackages) {
            String renamedPackage = this.mSettings.getRenamedPackageLPr(pkgName);
            if (renamedPackage != null) {
                res.setError(-1, "Attempt to re-install " + pkgName + " without first uninstalling package running as " + renamedPackage);
            } else if (this.mPackages.containsKey(pkgName)) {
                res.setError(-1, "Attempt to re-install " + pkgName + " without first uninstalling.");
            } else {
                try {
                    PackageParser.Package newPackage = scanPackageTracedLI(pkg, parseFlags, scanFlags, System.currentTimeMillis(), user);
                    updateSettingsLI(newPackage, installerPackageName, null, res, user, installReason);
                    if (res.returnCode == 1) {
                        prepareAppDataAfterInstallLIF(newPackage);
                    } else {
                        deletePackageLIF(pkgName, UserHandle.ALL, false, null, 1, res.removedInfo, true, null);
                    }
                } catch (PackageManagerException e) {
                    res.setError("Package couldn't be installed in " + pkg.codePath, e);
                }
                Trace.traceEnd(262144L);
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

    private void replacePackageLIF(PackageParser.Package pkg, int parseFlags, int scanFlags, UserHandle user, String installerPackageName, PackageInstalledInfo res, int installReason) {
        int i;
        int childCount;
        int[] installedUsers;
        PackageSetting ps;
        boolean isInstantApp = (scanFlags & 16384) != 0;
        String pkgName = pkg.packageName;
        synchronized (this.mPackages) {
            try {
                PackageParser.Package oldPackage = this.mPackages.get(pkgName);
                if (DEBUG_INSTALL) {
                    try {
                        Slog.d(TAG, "replacePackageLI: new=" + pkg + ", old=" + oldPackage);
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
                boolean oldTargetsPreRelease = oldPackage.applicationInfo.targetSdkVersion == 10000;
                boolean newTargetsPreRelease = pkg.applicationInfo.targetSdkVersion == 10000;
                if (!oldTargetsPreRelease || newTargetsPreRelease) {
                    i = parseFlags;
                } else {
                    i = parseFlags;
                    if ((i & 128) == 0) {
                        Slog.w(TAG, "Can't install package targeting released sdk");
                        res.setReturnCode(-7);
                        return;
                    }
                }
                PackageSetting ps2 = this.mSettings.mPackages.get(pkgName);
                KeySetManagerService ksms = this.mSettings.mKeySetManagerService;
                if (ksms.shouldCheckUpgradeKeySetLocked(ps2, scanFlags)) {
                    if (!ksms.checkUpgradeKeySetLocked(ps2, pkg)) {
                        res.setError(-7, "New package not signed by keys specified by upgrade-keysets: " + pkgName);
                        return;
                    }
                } else if (!pkg.mSigningDetails.checkCapability(oldPackage.mSigningDetails, 1) && !oldPackage.mSigningDetails.checkCapability(pkg.mSigningDetails, 8)) {
                    res.setError(-7, "New package has a different signature: " + pkgName);
                    return;
                }
                if (oldPackage.restrictUpdateHash != null && oldPackage.isSystem()) {
                    try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-512");
                        try {
                            updateDigest(digest, new File(pkg.baseCodePath));
                            if (!ArrayUtils.isEmpty(pkg.splitCodePaths)) {
                                String[] strArr = pkg.splitCodePaths;
                                int i2 = 0;
                                for (int length = strArr.length; i2 < length; length = length) {
                                    try {
                                        String path = strArr[i2];
                                        updateDigest(digest, new File(path));
                                        i2++;
                                        strArr = strArr;
                                    } catch (IOException | NoSuchAlgorithmException e) {
                                        res.setError(-2, "Could not compute hash: " + pkgName);
                                        return;
                                    }
                                }
                            }
                            byte[] digestBytes = digest.digest();
                            if (!Arrays.equals(oldPackage.restrictUpdateHash, digestBytes)) {
                                res.setError(-2, "New package fails restrict-update check: " + pkgName);
                                return;
                            }
                            pkg.restrictUpdateHash = oldPackage.restrictUpdateHash;
                        } catch (IOException | NoSuchAlgorithmException e2) {
                        }
                    } catch (IOException | NoSuchAlgorithmException e3) {
                    }
                }
                String invalidPackageName = getParentOrChildPackageChangedSharedUser(oldPackage, pkg);
                if (invalidPackageName != null) {
                    res.setError(-8, "Package " + invalidPackageName + " tried to change user " + oldPackage.mSharedUserId);
                    return;
                }
                boolean oldPkgSupportMultiArch = oldPackage.applicationInfo.secondaryCpuAbi != null;
                boolean newPkgSupportMultiArch = pkg.applicationInfo.secondaryCpuAbi != null;
                if (isSystemApp(oldPackage) && oldPkgSupportMultiArch && !newPkgSupportMultiArch) {
                    res.setError(-7, "Update to package " + pkgName + " doesn't support multi arch");
                    return;
                }
                int[] allUsers = sUserManager.getUserIds();
                int[] installedUsers2 = ps2.queryInstalledUsers(allUsers, true);
                if (isInstantApp) {
                    if (user != null && user.getIdentifier() != -1) {
                        if (!ps2.getInstantApp(user.getIdentifier())) {
                            Slog.w(TAG, "Can't replace full app with instant app: " + pkgName + " for user: " + user.getIdentifier());
                            res.setReturnCode(-116);
                            return;
                        }
                    }
                    int length2 = allUsers.length;
                    int i3 = 0;
                    while (i3 < length2) {
                        int currentUser = allUsers[i3];
                        if (!ps2.getInstantApp(currentUser)) {
                            Slog.w(TAG, "Can't replace full app with instant app: " + pkgName + " for user: " + currentUser);
                            res.setReturnCode(-116);
                            return;
                        }
                        i3++;
                        ksms = ksms;
                    }
                }
                res.removedInfo = new PackageRemovedInfo(this);
                res.removedInfo.uid = oldPackage.applicationInfo.uid;
                res.removedInfo.removedPackage = oldPackage.packageName;
                res.removedInfo.installerPackageName = ps2.installerPackageName;
                res.removedInfo.isStaticSharedLib = pkg.staticSharedLibName != null;
                res.removedInfo.isUpdate = true;
                res.removedInfo.origUsers = installedUsers2;
                res.removedInfo.installReasons = new SparseArray<>(installedUsers2.length);
                for (int userId : installedUsers2) {
                    res.removedInfo.installReasons.put(userId, Integer.valueOf(ps2.getInstallReason(userId)));
                }
                int childCount2 = oldPackage.childPackages != null ? oldPackage.childPackages.size() : 0;
                int i4 = 0;
                while (true) {
                    int i5 = i4;
                    if (i5 >= childCount2) {
                        break;
                    }
                    PackageParser.Package childPkg = (PackageParser.Package) oldPackage.childPackages.get(i5);
                    boolean childPackageUpdated = false;
                    PackageSetting childPs = this.mSettings.getPackageLPr(childPkg.packageName);
                    if (res.addedChildPackages != null) {
                        childCount = childCount2;
                        PackageInstalledInfo childRes = res.addedChildPackages.get(childPkg.packageName);
                        if (childRes != null) {
                            installedUsers = installedUsers2;
                            childRes.removedInfo.uid = childPkg.applicationInfo.uid;
                            childRes.removedInfo.removedPackage = childPkg.packageName;
                            if (childPs != null) {
                                childRes.removedInfo.installerPackageName = childPs.installerPackageName;
                            }
                            childRes.removedInfo.isUpdate = true;
                            childRes.removedInfo.installReasons = res.removedInfo.installReasons;
                            childPackageUpdated = true;
                        } else {
                            installedUsers = installedUsers2;
                        }
                    } else {
                        childCount = childCount2;
                        installedUsers = installedUsers2;
                    }
                    if (childPackageUpdated) {
                        ps = ps2;
                    } else {
                        PackageRemovedInfo childRemovedRes = new PackageRemovedInfo(this);
                        childRemovedRes.removedPackage = childPkg.packageName;
                        if (childPs != null) {
                            childRemovedRes.installerPackageName = childPs.installerPackageName;
                        }
                        childRemovedRes.isUpdate = false;
                        childRemovedRes.dataRemoved = true;
                        synchronized (this.mPackages) {
                            if (childPs != null) {
                                ps = ps2;
                                try {
                                    childRemovedRes.origUsers = childPs.queryInstalledUsers(allUsers, true);
                                } finally {
                                }
                            } else {
                                ps = ps2;
                            }
                        }
                        if (res.removedInfo.removedChildPackages == null) {
                            res.removedInfo.removedChildPackages = new ArrayMap<>();
                        }
                        res.removedInfo.removedChildPackages.put(childPkg.packageName, childRemovedRes);
                    }
                    i4 = i5 + 1;
                    childCount2 = childCount;
                    installedUsers2 = installedUsers;
                    ps2 = ps;
                }
                boolean sysPkg = isSystemApp(oldPackage);
                if (!sysPkg) {
                    replaceNonSystemPackageLIF(oldPackage, pkg, parseFlags, scanFlags, user, allUsers, installerPackageName, res, installReason);
                    return;
                }
                boolean privileged = (oldPackage.applicationInfo.privateFlags & 8) != 0;
                boolean oem = (oldPackage.applicationInfo.privateFlags & 131072) != 0;
                boolean vendor = (oldPackage.applicationInfo.privateFlags & 262144) != 0;
                boolean product = (oldPackage.applicationInfo.privateFlags & 524288) != 0;
                int systemParseFlags = i;
                int systemScanFlags = scanFlags | 131072 | (privileged ? 262144 : 0) | (oem ? 524288 : 0) | (vendor ? 1048576 : 0) | (product ? 2097152 : 0);
                replaceSystemPackageLIF(oldPackage, pkg, systemParseFlags, systemScanFlags, user, allUsers, installerPackageName, res, installReason);
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:102:0x029c  */
    /* JADX WARN: Removed duplicated region for block: B:58:0x0184  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void replaceNonSystemPackageLIF(android.content.pm.PackageParser.Package r31, android.content.pm.PackageParser.Package r32, int r33, int r34, android.os.UserHandle r35, int[] r36, java.lang.String r37, com.android.server.pm.PackageManagerService.PackageInstalledInfo r38, int r39) {
        /*
            Method dump skipped, instructions count: 786
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.replaceNonSystemPackageLIF(android.content.pm.PackageParser$Package, android.content.pm.PackageParser$Package, int, int, android.os.UserHandle, int[], java.lang.String, com.android.server.pm.PackageManagerService$PackageInstalledInfo, int):void");
    }

    private void replaceSystemPackageLIF(PackageParser.Package deletedPackage, PackageParser.Package pkg, int parseFlags, int scanFlags, UserHandle user, int[] allUsers, String installerPackageName, PackageInstalledInfo res, int installReason) {
        boolean disabledSystem;
        int i;
        int newChildCount;
        PackageSetting ps;
        if (DEBUG_INSTALL) {
            Slog.d(TAG, "replaceSystemPackageLI: new=" + pkg + ", old=" + deletedPackage);
        }
        removePackageLI(deletedPackage, true);
        synchronized (this.mPackages) {
            try {
                disabledSystem = disableSystemPackageLPw(deletedPackage, pkg);
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
        int i2 = 0;
        if (disabledSystem) {
            res.removedInfo.args = null;
        } else {
            res.removedInfo.args = createInstallArgsForExisting(0, deletedPackage.applicationInfo.getCodePath(), deletedPackage.applicationInfo.getResourcePath(), InstructionSets.getAppDexInstructionSets(deletedPackage.applicationInfo));
        }
        clearAppDataLIF(pkg, -1, UsbTerminalTypes.TERMINAL_IN_PERSONAL_MIC);
        res.setReturnCode(1);
        pkg.setApplicationInfoFlags(128, 128);
        PackageParser.Package newPackage = null;
        try {
            newPackage = scanPackageTracedLI(pkg, parseFlags, scanFlags, 0L, user);
            PackageSetting deletedPkgSetting = (PackageSetting) deletedPackage.mExtras;
            setInstallAndUpdateTime(newPackage, deletedPkgSetting.firstInstallTime, System.currentTimeMillis());
            if (res.returnCode == 1) {
                int deletedChildCount = deletedPackage.childPackages != null ? deletedPackage.childPackages.size() : 0;
                int newChildCount2 = newPackage.childPackages != null ? newPackage.childPackages.size() : 0;
                int i3 = 0;
                while (true) {
                    int i4 = i3;
                    if (i4 >= deletedChildCount) {
                        break;
                    }
                    PackageParser.Package deletedChildPkg = (PackageParser.Package) deletedPackage.childPackages.get(i4);
                    boolean childPackageDeleted = true;
                    int j = i2;
                    while (true) {
                        if (j >= newChildCount2) {
                            break;
                        }
                        PackageParser.Package newChildPkg = (PackageParser.Package) newPackage.childPackages.get(j);
                        if (deletedChildPkg.packageName.equals(newChildPkg.packageName)) {
                            childPackageDeleted = false;
                            break;
                        }
                        j++;
                    }
                    if (!childPackageDeleted || (ps = this.mSettings.getDisabledSystemPkgLPr(deletedChildPkg.packageName)) == null || res.removedInfo.removedChildPackages == null) {
                        i = i4;
                        newChildCount = newChildCount2;
                    } else {
                        PackageRemovedInfo removedChildRes = res.removedInfo.removedChildPackages.get(deletedChildPkg.packageName);
                        i = i4;
                        newChildCount = newChildCount2;
                        removePackageDataLIF(ps, allUsers, removedChildRes, 0, false);
                        removedChildRes.removedForAllUsers = this.mPackages.get(ps.name) == null;
                    }
                    i3 = i + 1;
                    newChildCount2 = newChildCount;
                    i2 = 0;
                }
                updateSettingsLI(newPackage, installerPackageName, allUsers, res, user, installReason);
                prepareAppDataAfterInstallLIF(newPackage);
                this.mDexManager.notifyPackageUpdated(newPackage.packageName, newPackage.baseCodePath, newPackage.splitCodePaths);
            }
        } catch (PackageManagerException e) {
            res.setReturnCode(RequestStatus.SYS_ETIMEDOUT);
            res.setError("Package couldn't be installed in " + pkg.codePath, e);
        }
        if (res.returnCode == 1) {
            return;
        }
        if (newPackage != null) {
            removeInstalledPackageLI(newPackage, true);
        }
        try {
            scanPackageTracedLI(deletedPackage, parseFlags, 2, 0L, user);
        } catch (PackageManagerException e2) {
            Slog.e(TAG, "Failed to restore original package: " + e2.getMessage());
        }
        synchronized (this.mPackages) {
            try {
                if (disabledSystem) {
                    try {
                        enableSystemPackageLPw(deletedPackage);
                    } catch (Throwable th3) {
                        th = th3;
                        throw th;
                    }
                }
                setInstallerPackageNameLPw(deletedPackage, installerPackageName);
                this.mPermissionManager.updatePermissions(deletedPackage.packageName, deletedPackage, false, this.mPackages.values(), this.mPermissionCallback);
                this.mSettings.writeLPr();
                Slog.i(TAG, "Successfully restored package : " + deletedPackage.packageName + " after failed upgrade");
            } catch (Throwable th4) {
                th = th4;
            }
        }
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

    private void enableSystemPackageLPw(PackageParser.Package pkg) {
        this.mSettings.enableSystemPackageLPw(pkg.packageName);
        int childCount = pkg.childPackages != null ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            PackageParser.Package childPkg = (PackageParser.Package) pkg.childPackages.get(i);
            this.mSettings.enableSystemPackageLPw(childPkg.packageName);
        }
    }

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
        int i;
        int[] userIds;
        int i2;
        int[] iArr;
        int origUserId;
        Trace.traceBegin(262144L, "updateSettings");
        String pkgName = pkg.packageName;
        if (DEBUG_INSTALL) {
            Slog.d(TAG, "New package installed in " + pkg.codePath);
        }
        synchronized (this.mPackages) {
            try {
                try {
                    this.mPermissionManager.updatePermissions(pkg.packageName, pkg, true, this.mPackages.values(), this.mPermissionCallback);
                    PackageSetting ps = this.mSettings.mPackages.get(pkgName);
                    int userId = user.getIdentifier();
                    if (ps != null) {
                        if (isSystemApp(pkg)) {
                            if (DEBUG_INSTALL) {
                                Slog.d(TAG, "Implicitly enabling system package on upgrade: " + pkgName);
                            }
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
                                int length = allUsers.length;
                                int i3 = 0;
                                while (i3 < length) {
                                    int currentUserId = allUsers[i3];
                                    boolean installed = ArrayUtils.contains(installedForUsers, currentUserId);
                                    if (DEBUG_INSTALL) {
                                        StringBuilder sb = new StringBuilder();
                                        i2 = length;
                                        sb.append("    user ");
                                        sb.append(currentUserId);
                                        sb.append(" => ");
                                        sb.append(installed);
                                        Slog.d(TAG, sb.toString());
                                    } else {
                                        i2 = length;
                                    }
                                    ps.setInstalled(installed, currentUserId);
                                    i3++;
                                    length = i2;
                                }
                            }
                        }
                        if (userId != -1) {
                            ps.setInstalled(true, userId);
                            i = 0;
                            ps.setEnabled(0, userId, installerPackageName);
                        } else {
                            i = 0;
                        }
                        Set<Integer> previousUserIds = new ArraySet<>();
                        if (res.removedInfo != null && res.removedInfo.installReasons != null) {
                            int installReasonCount = res.removedInfo.installReasons.size();
                            for (int i4 = i; i4 < installReasonCount; i4++) {
                                int previousUserId = res.removedInfo.installReasons.keyAt(i4);
                                int previousInstallReason = res.removedInfo.installReasons.valueAt(i4).intValue();
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
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void installPackageTracedLI(InstallArgs args, PackageInstalledInfo res) {
        try {
            Trace.traceBegin(262144L, "installPackage");
            installPackageLI(args, res);
        } finally {
            Trace.traceEnd(262144L);
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private void installPackageLI(com.android.server.pm.PackageManagerService.InstallArgs r60, com.android.server.pm.PackageManagerService.PackageInstalledInfo r61) {
        /*
            Method dump skipped, instructions count: 3162
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.installPackageLI(com.android.server.pm.PackageManagerService$InstallArgs, com.android.server.pm.PackageManagerService$PackageInstalledInfo):void");
    }

    private void startIntentFilterVerifications(int userId, boolean replacing, PackageParser.Package pkg) {
        if (this.mIntentFilterVerifierComponent == null) {
            Slog.w(TAG, "No IntentFilter verification will not be done as there is no IntentFilterVerifier available!");
            return;
        }
        int verifierUid = getPackageUid(this.mIntentFilterVerifierComponent.getPackageName(), 268435456, userId == -1 ? 0 : userId);
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
    public void verifyIntentFiltersIfNeeded(int userId, int verifierUid, boolean replacing, PackageParser.Package pkg) {
        boolean needToVerify;
        Iterator it;
        PackageParser.Activity a;
        Iterator it2;
        int size = pkg.activities.size();
        if (size == 0) {
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(TAG, "No activity, so no need to verify any IntentFilter!");
                return;
            }
            return;
        }
        boolean hasDomainURLs = hasDomainURLs(pkg);
        if (!hasDomainURLs) {
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(TAG, "No domain URLs, so no need to verify any IntentFilter!");
                return;
            }
            return;
        }
        if (DEBUG_DOMAIN_VERIFICATION) {
            Slog.d(TAG, "Checking for userId:" + userId + " if any IntentFilter from the " + size + " Activities needs verification ...");
        }
        int count = 0;
        String packageName = pkg.packageName;
        synchronized (this.mPackages) {
            if (!replacing) {
                try {
                    IntentFilterVerificationInfo ivi = this.mSettings.getIntentFilterVerificationLPr(packageName);
                    if (ivi != null) {
                        if (DEBUG_DOMAIN_VERIFICATION) {
                            Slog.i(TAG, "Package " + packageName + " already verified: status=" + ivi.getStatusString());
                        }
                        return;
                    }
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            }
            boolean needToVerify2 = false;
            Iterator it3 = pkg.activities.iterator();
            while (it3.hasNext()) {
                Iterator it4 = ((PackageParser.Activity) it3.next()).intents.iterator();
                while (true) {
                    if (it4.hasNext()) {
                        PackageParser.ActivityIntentInfo filter = (PackageParser.ActivityIntentInfo) it4.next();
                        if (filter.needsVerification() && needsNetworkVerificationLPr(filter)) {
                            if (DEBUG_DOMAIN_VERIFICATION) {
                                Slog.d(TAG, "Intent filter needs verification, so processing all filters");
                            }
                            needToVerify2 = true;
                        }
                    }
                }
            }
            boolean z = true;
            if (needToVerify2) {
                int verificationId = this.mIntentFilterVerificationToken;
                this.mIntentFilterVerificationToken = verificationId + 1;
                Iterator it5 = pkg.activities.iterator();
                while (it5.hasNext()) {
                    PackageParser.Activity a2 = (PackageParser.Activity) it5.next();
                    Iterator it6 = a2.intents.iterator();
                    int count2 = count;
                    while (it6.hasNext()) {
                        try {
                            PackageParser.ActivityIntentInfo filter2 = (PackageParser.ActivityIntentInfo) it6.next();
                            if (filter2.handlesWebUris(z) && needsNetworkVerificationLPr(filter2)) {
                                if (DEBUG_DOMAIN_VERIFICATION) {
                                    StringBuilder sb = new StringBuilder();
                                    needToVerify = needToVerify2;
                                    sb.append("Verification needed for IntentFilter:");
                                    sb.append(filter2.toString());
                                    Slog.d(TAG, sb.toString());
                                } else {
                                    needToVerify = needToVerify2;
                                }
                                it = it6;
                                a = a2;
                                it2 = it5;
                                this.mIntentFilterVerifier.addOneIntentFilterVerification(verifierUid, userId, verificationId, filter2, packageName);
                                count2++;
                            } else {
                                needToVerify = needToVerify2;
                                it = it6;
                                a = a2;
                                it2 = it5;
                            }
                            it6 = it;
                            needToVerify2 = needToVerify;
                            a2 = a;
                            it5 = it2;
                            z = true;
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                    count = count2;
                    z = true;
                }
            }
            if (count <= 0) {
                if (DEBUG_DOMAIN_VERIFICATION) {
                    Slog.d(TAG, "No filters or not all autoVerify for " + packageName);
                    return;
                }
                return;
            }
            if (DEBUG_DOMAIN_VERIFICATION) {
                StringBuilder sb2 = new StringBuilder();
                sb2.append("Starting ");
                sb2.append(count);
                sb2.append(" IntentFilter verification");
                sb2.append(count > 1 ? "s" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                sb2.append(" for userId:");
                sb2.append(userId);
                Slog.d(TAG, sb2.toString());
            }
            this.mIntentFilterVerifier.startVerifications(userId);
        }
    }

    private boolean needsNetworkVerificationLPr(PackageParser.ActivityIntentInfo filter) {
        ComponentName cn = filter.activity.getComponentName();
        String packageName = cn.getPackageName();
        IntentFilterVerificationInfo ivi = this.mSettings.getIntentFilterVerificationLPr(packageName);
        if (ivi == null) {
            return true;
        }
        int status = ivi.getStatus();
        switch (status) {
            case 0:
            case 1:
                return true;
            default:
                return false;
        }
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

    private static boolean hasDomainURLs(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & 16) != 0;
    }

    private static boolean isSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & 1) != 0;
    }

    private static boolean isUpdatedSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & 128) != 0;
    }

    private int packageFlagsToInstallFlags(PackageSetting ps) {
        int installFlags = 0;
        if (isExternal(ps) && TextUtils.isEmpty(ps.volumeUuid)) {
            installFlags = 0 | 8;
        }
        if (ps.isForwardLocked()) {
            return installFlags | 1;
        }
        return installFlags;
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
        File[] listFiles;
        FilenameFilter filter = new FilenameFilter() { // from class: com.android.server.pm.PackageManagerService.12
            @Override // java.io.FilenameFilter
            public boolean accept(File dir, String name) {
                return name.startsWith("vmdl") && name.endsWith(".tmp");
            }
        };
        for (File file : sDrmAppPrivateInstallDir.listFiles(filter)) {
            file.delete();
        }
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
            try {
                Intent intent = new Intent("android.intent.action.UNINSTALL_PACKAGE");
                intent.setData(Uri.fromParts("package", packageName, null));
                intent.putExtra("android.content.pm.extra.CALLBACK", observer.asBinder());
                observer.onUserActionRequired(intent);
                return;
            } catch (RemoteException e) {
                return;
            }
        }
        final boolean deleteAllUsers = (deleteFlags & 2) != 0;
        final int[] users = deleteAllUsers ? sUserManager.getUserIds() : new int[]{userId};
        if (UserHandle.getUserId(uid) != userId || (deleteAllUsers && users.length > 1)) {
            Context context = this.mContext;
            context.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "deletePackage for user " + userId);
        }
        if (isUserRestricted(userId, "no_uninstall_apps")) {
            try {
                observer.onPackageDeleted(packageName, -3, (String) null);
            } catch (RemoteException e2) {
            }
        } else if (!deleteAllUsers && getBlockUninstallForUser(internalPackageName, userId)) {
            try {
                observer.onPackageDeleted(packageName, -4, (String) null);
            } catch (RemoteException e3) {
            }
        } else {
            if (DEBUG_REMOVE) {
                StringBuilder sb = new StringBuilder();
                sb.append("deletePackageAsUser: pkg=");
                sb.append(internalPackageName);
                sb.append(" user=");
                sb.append(userId);
                sb.append(" deleteAllUsers: ");
                sb.append(deleteAllUsers);
                sb.append(" version=");
                sb.append(versionCode == -1 ? "VERSION_CODE_HIGHEST" : Long.valueOf(versionCode));
                Slog.d(TAG, sb.toString());
            }
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.13
                @Override // java.lang.Runnable
                public void run() {
                    int returnCode;
                    int[] iArr;
                    int returnCode2;
                    PackageManagerService.this.mHandler.removeCallbacks(this);
                    PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(internalPackageName);
                    boolean doDeletePackage = true;
                    if (ps != null) {
                        boolean targetIsInstantApp = ps.getInstantApp(UserHandle.getUserId(callingUid));
                        doDeletePackage = !targetIsInstantApp || canViewInstantApps;
                    }
                    if (doDeletePackage) {
                        if (deleteAllUsers) {
                            int[] blockUninstallUserIds = PackageManagerService.this.getBlockUninstallForUsers(internalPackageName, users);
                            if (ArrayUtils.isEmpty(blockUninstallUserIds)) {
                                returnCode = PackageManagerService.this.deletePackageX(internalPackageName, versionCode, userId, deleteFlags);
                            } else {
                                int userFlags = deleteFlags & (-3);
                                for (int userId2 : users) {
                                    if (!ArrayUtils.contains(blockUninstallUserIds, userId2) && (returnCode2 = PackageManagerService.this.deletePackageX(internalPackageName, versionCode, userId2, userFlags)) != 1) {
                                        Slog.w(PackageManagerService.TAG, "Package delete failed for user " + userId2 + ", returnCode " + returnCode2);
                                    }
                                }
                                returnCode = -4;
                            }
                        } else {
                            returnCode = PackageManagerService.this.deletePackageX(internalPackageName, versionCode, userId, deleteFlags);
                        }
                    } else {
                        returnCode = -1;
                    }
                    try {
                        observer.onPackageDeleted(packageName, returnCode, (String) null);
                    } catch (RemoteException e4) {
                        Log.i(PackageManagerService.TAG, "Observer no longer exists.");
                    }
                }
            });
        }
    }

    private String resolveExternalPackageNameLPr(PackageParser.Package pkg) {
        if (pkg.staticSharedLibName != null) {
            return pkg.manifestPackageName;
        }
        return pkg.packageName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String resolveInternalPackageNameLPr(String packageName, long versionCode) {
        int callingAppId;
        String libName;
        String normalizedPackageName = this.mSettings.getRenamedPackageLPr(packageName);
        String packageName2 = normalizedPackageName != null ? normalizedPackageName : packageName;
        LongSparseArray<SharedLibraryEntry> versionedLib = this.mStaticLibsByDeclaringPackage.get(packageName2);
        if (versionedLib == null || versionedLib.size() <= 0) {
            return packageName2;
        }
        LongSparseLongArray versionsCallerCanSee = null;
        int callingAppId2 = UserHandle.getAppId(Binder.getCallingUid());
        if (callingAppId2 != 1000 && callingAppId2 != SHELL_UID && callingAppId2 != 0) {
            versionsCallerCanSee = new LongSparseLongArray();
            String libName2 = versionedLib.valueAt(0).info.getName();
            String[] uidPackages = getPackagesForUid(Binder.getCallingUid());
            if (uidPackages != null) {
                int length = uidPackages.length;
                int i = 0;
                while (i < length) {
                    String uidPackage = uidPackages[i];
                    PackageSetting ps = this.mSettings.getPackageLPr(uidPackage);
                    int libIdx = ArrayUtils.indexOf(ps.usesStaticLibraries, libName2);
                    if (libIdx < 0) {
                        callingAppId = callingAppId2;
                        libName = libName2;
                    } else {
                        callingAppId = callingAppId2;
                        libName = libName2;
                        long libVersion = ps.usesStaticLibrariesVersions[libIdx];
                        versionsCallerCanSee.append(libVersion, libVersion);
                    }
                    i++;
                    callingAppId2 = callingAppId;
                    libName2 = libName;
                }
            }
        }
        if (versionsCallerCanSee != null && versionsCallerCanSee.size() <= 0) {
            return packageName2;
        }
        SharedLibraryEntry highestVersion = null;
        int versionCount = versionedLib.size();
        int i2 = 0;
        while (true) {
            int i3 = i2;
            if (i3 < versionCount) {
                SharedLibraryEntry libEntry = versionedLib.valueAt(i3);
                if (versionsCallerCanSee == null || versionsCallerCanSee.indexOfKey(libEntry.info.getLongVersion()) >= 0) {
                    long libVersionCode = libEntry.info.getDeclaringPackage().getLongVersionCode();
                    if (versionCode != -1) {
                        if (libVersionCode == versionCode) {
                            return libEntry.apk;
                        }
                    } else if (highestVersion == null) {
                        highestVersion = libEntry;
                    } else if (libVersionCode > highestVersion.info.getDeclaringPackage().getLongVersionCode()) {
                        highestVersion = libEntry;
                    }
                }
                i2 = i3 + 1;
            } else if (highestVersion != null) {
                return highestVersion.apk;
            } else {
                return packageName2;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isCallerVerifier(int callingUid) {
        int callingUserId = UserHandle.getUserId(callingUid);
        return this.mRequiredVerifierPackage != null && callingUid == getPackageUid(this.mRequiredVerifierPackage, 0, callingUserId);
    }

    private boolean isCallerAllowedToSilentlyUninstall(int callingUid, String pkgName) {
        if (callingUid == SHELL_UID || callingUid == 0 || UserHandle.getAppId(callingUid) == 1000) {
            return true;
        }
        int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUid == getPackageUid(getInstallerPackageName(pkgName), 0, callingUserId)) {
            return true;
        }
        if (this.mRequiredVerifierPackage != null && callingUid == getPackageUid(this.mRequiredVerifierPackage, 0, callingUserId)) {
            return true;
        }
        if (this.mRequiredUninstallerPackage == null || callingUid != getPackageUid(this.mRequiredUninstallerPackage, 0, callingUserId)) {
            return (this.mStorageManagerPackage != null && callingUid == getPackageUid(this.mStorageManagerPackage, 0, callingUserId)) || checkUidPermission("android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS", callingUid) == 0;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int[] getBlockUninstallForUsers(String packageName, int[] userIds) {
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
            EventLog.writeEvent(1397638484, "128599183", -1, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
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
        return this.mKeepUninstalledPackages != null && this.mKeepUninstalledPackages.contains(packageName);
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    int deletePackageX(java.lang.String r26, long r27, int r29, int r30) {
        /*
            Method dump skipped, instructions count: 623
            To view this dump add '--comments-level debug' option
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
            int childCount = this.removedChildPackages != null ? this.removedChildPackages.size() : 0;
            for (int i = 0; i < childCount; i++) {
                PackageRemovedInfo childInfo = this.removedChildPackages.valueAt(i);
                childInfo.sendPackageRemovedBroadcastInternal(killApp);
            }
        }

        void sendSystemPackageUpdatedBroadcasts() {
            if (this.isRemovedPackageSystemUpdate) {
                sendSystemPackageUpdatedBroadcastsInternal();
                int childCount = this.removedChildPackages != null ? this.removedChildPackages.size() : 0;
                for (int i = 0; i < childCount; i++) {
                    PackageRemovedInfo childInfo = this.removedChildPackages.valueAt(i);
                    if (childInfo.isRemovedPackageSystemUpdate) {
                        childInfo.sendSystemPackageUpdatedBroadcastsInternal();
                    }
                }
            }
        }

        void sendSystemPackageAppearedBroadcasts() {
            int packageCount = this.appearedChildPackages != null ? this.appearedChildPackages.size() : 0;
            for (int i = 0; i < packageCount; i++) {
                PackageInstalledInfo installedInfo = this.appearedChildPackages.valueAt(i);
                this.packageSender.sendPackageAddedForNewUsers(installedInfo.name, true, false, UserHandle.getAppId(installedInfo.uid), installedInfo.newUsers, null);
            }
        }

        private void sendSystemPackageUpdatedBroadcastsInternal() {
            Bundle extras = new Bundle(2);
            extras.putInt("android.intent.extra.UID", this.removedAppId >= 0 ? this.removedAppId : this.uid);
            extras.putBoolean("android.intent.extra.REPLACING", true);
            this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", this.removedPackage, extras, 0, null, null, null, null);
            this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", this.removedPackage, extras, 0, null, null, null, null);
            this.packageSender.sendPackageBroadcast("android.intent.action.MY_PACKAGE_REPLACED", null, null, 0, this.removedPackage, null, null, null);
            if (this.installerPackageName != null) {
                this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_ADDED", this.removedPackage, extras, 0, this.installerPackageName, null, null, null);
                this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REPLACED", this.removedPackage, extras, 0, this.installerPackageName, null, null, null);
            }
        }

        private void sendPackageRemovedBroadcastInternal(boolean killApp) {
            if (this.isStaticSharedLib) {
                return;
            }
            Bundle extras = new Bundle(2);
            extras.putInt("android.intent.extra.UID", this.removedAppId >= 0 ? this.removedAppId : this.uid);
            extras.putBoolean("android.intent.extra.DATA_REMOVED", this.dataRemoved);
            extras.putBoolean("android.intent.extra.DONT_KILL_APP", !killApp);
            if (this.isUpdate || this.isRemovedPackageSystemUpdate) {
                extras.putBoolean("android.intent.extra.REPLACING", true);
            }
            extras.putBoolean("android.intent.extra.REMOVED_FOR_ALL_USERS", this.removedForAllUsers);
            if (this.removedPackage != null) {
                this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REMOVED", this.removedPackage, extras, 0, null, null, this.broadcastUsers, this.instantUserIds);
                if (this.installerPackageName != null) {
                    this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_REMOVED", this.removedPackage, extras, 0, this.installerPackageName, null, this.broadcastUsers, this.instantUserIds);
                }
                if (this.dataRemoved && !this.isRemovedPackageSystemUpdate) {
                    this.packageSender.sendPackageBroadcast("android.intent.action.PACKAGE_FULLY_REMOVED", this.removedPackage, extras, 16777216, null, null, this.broadcastUsers, this.instantUserIds);
                    this.packageSender.notifyPackageRemoved(this.removedPackage);
                }
            }
            if (this.removedAppId >= 0) {
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

    private void removePackageDataLIF(PackageSetting ps, int[] allUserHandles, PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        PackageParser.Package deletedPkg;
        final PackageSetting deletedPs;
        boolean installedStateChanged;
        int i;
        PackageParser.Package resolvedPkg;
        String packageName = ps.name;
        if (DEBUG_REMOVE) {
            Slog.d(TAG, "removePackageDataLI: " + ps);
        }
        synchronized (this.mPackages) {
            deletedPkg = this.mPackages.get(packageName);
            deletedPs = this.mSettings.mPackages.get(packageName);
            if (outInfo != null) {
                outInfo.removedPackage = packageName;
                outInfo.installerPackageName = ps.installerPackageName;
                outInfo.isStaticSharedLib = (deletedPkg == null || deletedPkg.staticSharedLibName == null) ? false : true;
                outInfo.populateUsers(deletedPs == null ? null : deletedPs.queryInstalledUsers(sUserManager.getUserIds(), true), deletedPs);
            }
        }
        removePackageLI(ps, (flags & Integer.MIN_VALUE) != 0);
        if ((flags & 1) == 0) {
            if (deletedPkg != null) {
                resolvedPkg = deletedPkg;
            } else {
                resolvedPkg = new PackageParser.Package(ps.name);
                resolvedPkg.setVolumeUuid(ps.volumeUuid);
            }
            destroyAppDataLIF(resolvedPkg, -1, 3);
            destroyAppProfilesLIF(resolvedPkg, -1);
            if (outInfo != null) {
                outInfo.dataRemoved = true;
            }
            schedulePackageCleaning(packageName, -1, true);
        }
        int removedAppId = -1;
        synchronized (this.mPackages) {
            boolean installedStateChanged2 = false;
            if (deletedPs != null) {
                if ((flags & 1) == 0) {
                    try {
                        clearIntentFilterVerificationsLPw(deletedPs.name, -1);
                        clearDefaultBrowserIfNeeded(packageName);
                        this.mSettings.mKeySetManagerService.removeAppKeySetDataLPw(packageName);
                        removedAppId = this.mSettings.removePackageLPw(packageName);
                        if (outInfo != null) {
                            outInfo.removedAppId = removedAppId;
                        }
                        this.mPermissionManager.updatePermissions(deletedPs.name, null, false, this.mPackages.values(), this.mPermissionCallback);
                        if (deletedPs.sharedUser != null) {
                            int[] userIds = UserManagerService.getInstance().getUserIds();
                            int length = userIds.length;
                            int i2 = 0;
                            while (i2 < length) {
                                int userIdToKill = this.mSettings.updateSharedUserPermsLPw(deletedPs, userIds[i2]);
                                installedStateChanged = installedStateChanged2;
                                if (userIdToKill != -1 && userIdToKill < 0) {
                                    i2++;
                                    installedStateChanged2 = installedStateChanged;
                                }
                                this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.14
                                    @Override // java.lang.Runnable
                                    public void run() {
                                        PackageManagerService.this.killApplication(deletedPs.name, deletedPs.appId, PackageManagerService.KILL_APP_REASON_GIDS_CHANGED);
                                    }
                                });
                                break;
                            }
                        }
                        installedStateChanged = installedStateChanged2;
                        clearPackagePreferredActivitiesLPw(deletedPs.name, -1);
                    } finally {
                    }
                } else {
                    installedStateChanged = false;
                }
                if (allUserHandles != null && outInfo != null && outInfo.origUsers != null) {
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "Propagating install state across downgrade");
                    }
                    int i3 = 0;
                    for (int length2 = allUserHandles.length; i3 < length2; length2 = i) {
                        int userId = allUserHandles[i3];
                        boolean installed = ArrayUtils.contains(outInfo.origUsers, userId);
                        if (DEBUG_REMOVE) {
                            StringBuilder sb = new StringBuilder();
                            i = length2;
                            sb.append("    user ");
                            sb.append(userId);
                            sb.append(" => ");
                            sb.append(installed);
                            Slog.d(TAG, sb.toString());
                        } else {
                            i = length2;
                        }
                        if (installed != ps.getInstalled(userId)) {
                            installedStateChanged = true;
                        }
                        ps.setInstalled(installed, userId);
                        i3++;
                    }
                }
            } else {
                installedStateChanged = false;
            }
            if (writeSettings) {
                this.mSettings.writeLPr();
            }
            if (installedStateChanged) {
                this.mSettings.writeKernelMappingLPr(ps);
            }
        }
        if (removedAppId != -1) {
            removeKeystoreDataIfNeeded(-1, removedAppId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean locationIsPrivileged(String path) {
        try {
            File privilegedAppDir = new File(Environment.getRootDirectory(), "priv-app");
            File privilegedVendorAppDir = new File(Environment.getVendorDirectory(), "priv-app");
            File privilegedOdmAppDir = new File(Environment.getOdmDirectory(), "priv-app");
            File privilegedProductAppDir = new File(Environment.getProductDirectory(), "priv-app");
            if (!path.startsWith(privilegedAppDir.getCanonicalPath()) && !path.startsWith(privilegedVendorAppDir.getCanonicalPath()) && !path.startsWith(privilegedOdmAppDir.getCanonicalPath())) {
                if (!path.startsWith(privilegedProductAppDir.getCanonicalPath())) {
                    return false;
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
            return path.startsWith(Environment.getOemDirectory().getCanonicalPath());
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    static boolean locationIsVendor(String path) {
        try {
            if (!path.startsWith(Environment.getVendorDirectory().getCanonicalPath())) {
                if (!path.startsWith(Environment.getOdmDirectory().getCanonicalPath())) {
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
            return path.startsWith(Environment.getProductDirectory().getCanonicalPath());
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
            return false;
        }
    }

    private boolean deleteSystemPackageLIF(PackageParser.Package deletedPkg, PackageSetting deletedPs, int[] allUserHandles, int flags, PackageRemovedInfo outInfo, boolean writeSettings) {
        PackageSetting disabledPs;
        PackageRemovedInfo childInfo;
        if (deletedPs.parentPackageName != null) {
            Slog.w(TAG, "Attempt to delete child system package " + deletedPkg.packageName);
            return false;
        }
        boolean applyUserRestrictions = (allUserHandles == null || outInfo.origUsers == null) ? false : true;
        synchronized (this.mPackages) {
            disabledPs = this.mSettings.getDisabledSystemPkgLPr(deletedPs.name);
        }
        if (DEBUG_REMOVE) {
            Slog.d(TAG, "deleteSystemPackageLI: newPs=" + deletedPkg.packageName + " disabledPs=" + disabledPs);
        }
        if (disabledPs == null) {
            Slog.w(TAG, "Attempt to delete unknown system package " + deletedPkg.packageName);
            return false;
        }
        if (DEBUG_REMOVE) {
            Slog.d(TAG, "Deleting system pkg from data partition");
        }
        if (DEBUG_REMOVE && applyUserRestrictions) {
            Slog.d(TAG, "Remembering install states:");
            for (int userId : allUserHandles) {
                boolean finstalled = ArrayUtils.contains(outInfo.origUsers, userId);
                Slog.d(TAG, "   u=" + userId + " inst=" + finstalled);
            }
        }
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
        boolean ret = deleteInstalledPackageLIF(deletedPs, true, disabledPs.versionCode < deletedPs.versionCode ? flags & (-2) : flags | 1, allUserHandles, outInfo, writeSettings, disabledPs.pkg);
        if (ret) {
            synchronized (this.mPackages) {
                enableSystemPackageLPw(disabledPs.pkg);
                removeNativeBinariesLI(deletedPs);
            }
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Re-installing system package: " + disabledPs);
            }
            try {
                try {
                    installPackageFromSystemLIF(disabledPs.codePathString, false, allUserHandles, outInfo.origUsers, deletedPs.getPermissionsState(), writeSettings);
                    if (disabledPs.pkg.isStub) {
                        this.mSettings.disableSystemPackageLPw(disabledPs.name, true);
                        return true;
                    }
                    return true;
                } catch (PackageManagerException e) {
                    Slog.w(TAG, "Failed to restore system package:" + deletedPkg.packageName + ": " + e.getMessage());
                    if (disabledPs.pkg.isStub) {
                        this.mSettings.disableSystemPackageLPw(disabledPs.name, true);
                    }
                    return false;
                }
            } catch (Throwable th) {
                if (disabledPs.pkg.isStub) {
                    this.mSettings.disableSystemPackageLPw(disabledPs.name, true);
                }
                throw th;
            }
        }
        return false;
    }

    private PackageParser.Package installPackageFromSystemLIF(String codePathString, boolean isPrivileged, int[] allUserHandles, int[] origUserHandles, PermissionsState origPermissionState, boolean writeSettings) throws PackageManagerException {
        File codePath;
        boolean applyUserRestrictions;
        int i;
        boolean installed;
        boolean z = true;
        int parseFlags = this.mDefParseFlags | 1 | 16;
        int scanFlags = 131072;
        if (isPrivileged || locationIsPrivileged(codePathString)) {
            scanFlags = 131072 | 262144;
        }
        if (locationIsOem(codePathString)) {
            scanFlags |= 524288;
        }
        if (locationIsVendor(codePathString)) {
            scanFlags |= 1048576;
        }
        if (locationIsProduct(codePathString)) {
            scanFlags |= 2097152;
        }
        File codePath2 = new File(codePathString);
        PackageParser.Package pkg = scanPackageTracedLI(codePath2, parseFlags, scanFlags, 0L, (UserHandle) null);
        try {
            updateSharedLibrariesLPr(pkg, null);
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
                boolean applyUserRestrictions2 = z;
                if (applyUserRestrictions2) {
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "Propagating install state across reinstall");
                    }
                    boolean installedStateChanged = false;
                    int i2 = 0;
                    for (int length = allUserHandles.length; i2 < length; length = i) {
                        int userId = allUserHandles[i2];
                        boolean installed2 = ArrayUtils.contains(origUserHandles, userId);
                        if (DEBUG_REMOVE) {
                            codePath = codePath2;
                            try {
                                applyUserRestrictions = applyUserRestrictions2;
                                StringBuilder sb = new StringBuilder();
                                i = length;
                                sb.append("    user ");
                                sb.append(userId);
                                sb.append(" => ");
                                installed = installed2;
                                sb.append(installed);
                                Slog.d(TAG, sb.toString());
                            } catch (Throwable th2) {
                                th = th2;
                                throw th;
                            }
                        } else {
                            codePath = codePath2;
                            applyUserRestrictions = applyUserRestrictions2;
                            i = length;
                            installed = installed2;
                        }
                        if (installed != ps.getInstalled(userId)) {
                            installedStateChanged = true;
                        }
                        ps.setInstalled(installed, userId);
                        this.mSettings.writeRuntimePermissionsForUserLPr(userId, false);
                        i2++;
                        codePath2 = codePath;
                        applyUserRestrictions2 = applyUserRestrictions;
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

    private boolean deleteInstalledPackageLIF(PackageSetting ps, boolean deleteCodeAndResources, int flags, int[] allUserHandles, PackageRemovedInfo outInfo, boolean writeSettings, PackageParser.Package replacingPackage) {
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
            int i = 0;
            if (outInfo != null && outInfo.removedChildPackages != null) {
                if (ps.childPackageNames == null) {
                    childCount = 0;
                } else {
                    childCount = ps.childPackageNames.size();
                }
                for (int i2 = 0; i2 < childCount; i2++) {
                    String childPackageName = ps.childPackageNames.get(i2);
                    PackageSetting childPs2 = this.mSettings.mPackages.get(childPackageName);
                    if (childPs2 == null) {
                        return false;
                    }
                    PackageRemovedInfo childInfo = outInfo.removedChildPackages.get(childPackageName);
                    if (childInfo != null) {
                        childInfo.uid = childPs2.appId;
                    }
                }
            }
            removePackageDataLIF(ps, allUserHandles, outInfo, flags, writeSettings);
            int childCount2 = ps.childPackageNames != null ? ps.childPackageNames.size() : 0;
            while (true) {
                int i3 = i;
                if (i3 >= childCount2) {
                    break;
                }
                synchronized (this.mPackages) {
                    childPs = this.mSettings.getPackageLPr(ps.childPackageNames.get(i3));
                }
                if (childPs != null) {
                    PackageRemovedInfo childOutInfo = (outInfo == null || outInfo.removedChildPackages == null) ? null : outInfo.removedChildPackages.get(childPs.name);
                    int deleteFlags = ((flags & 1) == 0 || replacingPackage == null || replacingPackage.hasChildPackage(childPs.name)) ? flags : flags & (-2);
                    removePackageDataLIF(childPs, allUserHandles, childOutInfo, deleteFlags, writeSettings);
                }
                i = i3 + 1;
            }
            if (ps.parentPackageName == null && deleteCodeAndResources && outInfo != null) {
                outInfo.args = createInstallArgsForExisting(packageFlagsToInstallFlags(ps), ps.codePathString, ps.resourcePathString, InstructionSets.getAppDexInstructionSets(ps));
                return true;
            }
            return true;
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

    private boolean deletePackageLIF(String packageName, UserHandle user, boolean deleteCodeAndResources, int[] allUserHandles, int flags, PackageRemovedInfo outInfo, boolean writeSettings, PackageParser.Package replacingPackage) {
        boolean ret;
        boolean ret2;
        PackageSetting updatedPs;
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        if (DEBUG_REMOVE) {
            Slog.d(TAG, "deletePackageLI: " + packageName + " user " + user);
        }
        synchronized (this.mPackages) {
            PackageSetting ps = this.mSettings.mPackages.get(packageName);
            if (ps == null) {
                Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
                return false;
            }
            if (ps.parentPackageName != null && (!isSystemApp(ps) || (flags & 4) != 0)) {
                if (DEBUG_REMOVE) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Uninstalled child package:");
                    sb.append(packageName);
                    sb.append(" for user:");
                    sb.append(user == null ? -1 : user);
                    Slog.d(TAG, sb.toString());
                }
                int removedUserId = user != null ? user.getIdentifier() : -1;
                if (!clearPackageStateForUserLIF(ps, removedUserId, outInfo)) {
                    return false;
                }
                markPackageUninstalledForUserLPw(ps, user);
                scheduleWritePackageRestrictionsLocked(user);
                return true;
            }
            int userId = user == null ? -1 : user.getIdentifier();
            if (ps.getPermissionsState().hasPermission("android.permission.SUSPEND_APPS", userId)) {
                unsuspendForSuspendingPackage(packageName, userId);
            }
            if ((!isSystemApp(ps) || (flags & 4) != 0) && user != null && user.getIdentifier() != -1) {
                markPackageUninstalledForUserLPw(ps, user);
                if (!isSystemApp(ps)) {
                    boolean keepUninstalledPackage = shouldKeepUninstalledPackageLPr(packageName);
                    if (ps.isAnyInstalled(sUserManager.getUserIds()) || keepUninstalledPackage) {
                        if (DEBUG_REMOVE) {
                            Slog.d(TAG, "Still installed by other users");
                        }
                        if (!clearPackageStateForUserLIF(ps, user.getIdentifier(), outInfo)) {
                            return false;
                        }
                        scheduleWritePackageRestrictionsLocked(user);
                        return true;
                    }
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "Not installed by other users, full delete");
                    }
                    ps.setInstalled(true, user.getIdentifier());
                    this.mSettings.writeKernelMappingLPr(ps);
                } else {
                    boolean keepUninstalledPackage2 = DEBUG_REMOVE;
                    if (keepUninstalledPackage2) {
                        Slog.d(TAG, "Deleting system app");
                    }
                    if (!clearPackageStateForUserLIF(ps, user.getIdentifier(), outInfo)) {
                        return false;
                    }
                    scheduleWritePackageRestrictionsLocked(user);
                    return true;
                }
            }
            if (ps.childPackageNames != null && outInfo != null) {
                synchronized (this.mPackages) {
                    int childCount = ps.childPackageNames.size();
                    outInfo.removedChildPackages = new ArrayMap<>(childCount);
                    for (int i = 0; i < childCount; i++) {
                        String childPackageName = ps.childPackageNames.get(i);
                        PackageRemovedInfo childInfo = new PackageRemovedInfo(this);
                        childInfo.removedPackage = childPackageName;
                        childInfo.installerPackageName = ps.installerPackageName;
                        outInfo.removedChildPackages.put(childPackageName, childInfo);
                        PackageSetting childPs = this.mSettings.getPackageLPr(childPackageName);
                        if (childPs != null) {
                            childInfo.origUsers = childPs.queryInstalledUsers(allUserHandles, true);
                        }
                    }
                }
            }
            if (isSystemApp(ps)) {
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Removing system package: " + ps.name);
                }
                boolean ret3 = deleteSystemPackageLIF(ps.pkg, ps, allUserHandles, flags, outInfo, writeSettings);
                ret2 = ret3;
                ret = true;
            } else {
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Removing non-system package: " + ps.name);
                }
                ret = true;
                ret2 = deleteInstalledPackageLIF(ps, deleteCodeAndResources, flags, allUserHandles, outInfo, writeSettings, replacingPackage);
            }
            if (outInfo != null) {
                outInfo.removedForAllUsers = this.mPackages.get(ps.name) == null ? ret : false;
                if (outInfo.removedChildPackages != null) {
                    synchronized (this.mPackages) {
                        int childCount2 = outInfo.removedChildPackages.size();
                        for (int i2 = 0; i2 < childCount2; i2++) {
                            PackageRemovedInfo childInfo2 = outInfo.removedChildPackages.valueAt(i2);
                            if (childInfo2 != null) {
                                childInfo2.removedForAllUsers = this.mPackages.get(childInfo2.removedPackage) == null ? ret : false;
                            }
                        }
                    }
                }
                if (isSystemApp(ps)) {
                    synchronized (this.mPackages) {
                        PackageSetting updatedPs2 = this.mSettings.getPackageLPr(ps.name);
                        int childCount3 = updatedPs2.childPackageNames != null ? updatedPs2.childPackageNames.size() : 0;
                        int i3 = 0;
                        while (true) {
                            int i4 = i3;
                            if (i4 >= childCount3) {
                                break;
                            }
                            String childPackageName2 = updatedPs2.childPackageNames.get(i4);
                            if (outInfo.removedChildPackages != null) {
                                if (outInfo.removedChildPackages.indexOfKey(childPackageName2) < 0) {
                                }
                                updatedPs = updatedPs2;
                                i3 = i4 + 1;
                                updatedPs2 = updatedPs;
                            }
                            PackageSetting childPs2 = this.mSettings.getPackageLPr(childPackageName2);
                            if (childPs2 == null) {
                                updatedPs = updatedPs2;
                                i3 = i4 + 1;
                                updatedPs2 = updatedPs;
                            } else {
                                PackageInstalledInfo installRes = new PackageInstalledInfo();
                                installRes.name = childPackageName2;
                                updatedPs = updatedPs2;
                                installRes.newUsers = childPs2.queryInstalledUsers(allUserHandles, ret);
                                installRes.pkg = this.mPackages.get(childPackageName2);
                                installRes.uid = childPs2.pkg.applicationInfo.uid;
                                if (outInfo.appearedChildPackages == null) {
                                    outInfo.appearedChildPackages = new ArrayMap<>();
                                }
                                outInfo.appearedChildPackages.put(childPackageName2, installRes);
                                i3 = i4 + 1;
                                updatedPs2 = updatedPs;
                            }
                        }
                    }
                }
            }
            return ret2;
        }
    }

    private void markPackageUninstalledForUserLPw(PackageSetting ps, UserHandle user) {
        PackageSetting packageSetting = ps;
        int[] userIds = (user == null || user.getIdentifier() == -1) ? sUserManager.getUserIds() : new int[]{user.getIdentifier()};
        int length = userIds.length;
        int i = 0;
        while (i < length) {
            int nextUserId = userIds[i];
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Marking package:" + packageSetting.name + " uninstalled for user:" + nextUserId);
            }
            packageSetting.setUserState(nextUserId, 0L, 0, false, true, true, false, false, null, null, null, null, false, false, null, null, null, packageSetting.readUserState(nextUserId).domainVerificationStatus, 0, 0, null);
            i++;
            length = length;
            userIds = userIds;
            packageSetting = ps;
        }
        this.mSettings.writeKernelMappingLPr(ps);
    }

    private boolean clearPackageStateForUserLIF(PackageSetting ps, int userId, PackageRemovedInfo outInfo) {
        PackageParser.Package pkg;
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(ps.name);
        }
        boolean z = false;
        int[] userIds = userId == -1 ? sUserManager.getUserIds() : new int[]{userId};
        for (int nextUserId : userIds) {
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Updating package:" + ps.name + " install state for user:" + nextUserId);
            }
            destroyAppDataLIF(pkg, userId, 3);
            destroyAppProfilesLIF(pkg, userId);
            clearDefaultBrowserIfNeededForUser(ps.name, userId);
            removeKeystoreDataIfNeeded(nextUserId, ps.appId);
            schedulePackageCleaning(ps.name, nextUserId, false);
            synchronized (this.mPackages) {
                if (clearPackagePreferredActivitiesLPw(ps.name, nextUserId)) {
                    scheduleWritePackageRestrictionsLocked(nextUserId);
                }
                resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, nextUserId);
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
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ClearStorageConnection implements ServiceConnection {
        IMediaContainerService mContainerService;

        private ClearStorageConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (this) {
                this.mContainerService = IMediaContainerService.Stub.asInterface(Binder.allowBlocking(service));
                notifyAll();
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearExternalStorageDataSync(String packageName, int userId, boolean allData) {
        boolean mounted;
        if (DEFAULT_CONTAINER_PACKAGE.equals(packageName)) {
            return;
        }
        if (Environment.isExternalStorageEmulated()) {
            mounted = true;
        } else {
            String status = Environment.getExternalStorageState();
            mounted = status.equals("mounted") || status.equals("mounted_ro");
        }
        if (!mounted) {
            return;
        }
        Intent containerIntent = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
        int[] users = userId == -1 ? sUserManager.getUserIds() : new int[]{userId};
        ClearStorageConnection conn = new ClearStorageConnection();
        if (this.mContext.bindServiceAsUser(containerIntent, conn, 1, UserHandle.SYSTEM)) {
            try {
                for (int curUser : users) {
                    long timeout = SystemClock.uptimeMillis() + 5000;
                    synchronized (conn) {
                        while (conn.mContainerService == null) {
                            long now = SystemClock.uptimeMillis();
                            if (now >= timeout) {
                                break;
                            }
                            try {
                                conn.wait(timeout - now);
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                    if (conn.mContainerService == null) {
                        return;
                    }
                    Environment.UserEnvironment userEnv = new Environment.UserEnvironment(curUser);
                    clearDirectory(conn.mContainerService, userEnv.buildExternalStorageAppCacheDirs(packageName));
                    if (allData) {
                        clearDirectory(conn.mContainerService, userEnv.buildExternalStorageAppDataDirs(packageName));
                        clearDirectory(conn.mContainerService, userEnv.buildExternalStorageAppMediaDirs(packageName));
                    }
                }
            } finally {
                this.mContext.unbindService(conn);
            }
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

    public void clearDalvikCache(String packageName) {
        PackageParser.Package pkg;
        synchronized (this.mPackages) {
            pkg = this.mPackages.get(packageName);
        }
        synchronized (this.mInstallLock) {
            try {
                removeDexFiles(pkg);
            } catch (Exception e) {
                Slog.w(TAG, "removeDexFiles: ", e);
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
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.15
            @Override // java.lang.Runnable
            public void run() {
                boolean succeeded;
                boolean succeeded2;
                PackageManagerService.this.mHandler.removeCallbacks(this);
                if (!z) {
                    PackageFreezer freezer = PackageManagerService.this.freezePackage(packageName, "clearApplicationUserData");
                    try {
                        synchronized (PackageManagerService.this.mInstallLock) {
                            succeeded2 = PackageManagerService.this.clearApplicationUserDataLIF(packageName, userId);
                        }
                        PackageManagerService.this.clearExternalStorageDataSync(packageName, userId, true);
                        synchronized (PackageManagerService.this.mPackages) {
                            PackageManagerService.this.mInstantAppRegistry.deleteInstantApplicationMetadataLPw(packageName, userId);
                        }
                        if (freezer != null) {
                            freezer.close();
                        }
                        succeeded = succeeded2;
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
                                if (th != null) {
                                    try {
                                        freezer.close();
                                    } catch (Throwable th3) {
                                        th.addSuppressed(th3);
                                    }
                                } else {
                                    freezer.close();
                                }
                            }
                            throw th2;
                        }
                    }
                } else {
                    succeeded = false;
                }
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, succeeded);
                    } catch (RemoteException e) {
                        Log.i(PackageManagerService.TAG, "Observer no longer exists.");
                    }
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean clearApplicationUserDataLIF(String packageName, int userId) {
        PackageSetting ps;
        int flags = 0;
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
            PackageParser.Package pkg2 = pkg;
            clearAppDataLIF(pkg2, userId, 3);
            int appId = UserHandle.getAppId(pkg2.applicationInfo.uid);
            removeKeystoreDataIfNeeded(userId, appId);
            UserManagerInternal umInternal = getUserManagerInternal();
            if (umInternal.isUserUnlockingOrUnlocked(userId)) {
                flags = 3;
            } else if (umInternal.isUserRunning(userId)) {
                flags = 1;
            }
            prepareAppDataContentsLIF(pkg2, userId, flags);
            return true;
        }
    }

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

    private void resetUserChangesToRuntimePermissionsAndFlagsLPw(PackageSetting ps, final int userId) {
        int userSettableMask;
        PackageSetting packageSetting = ps;
        if (packageSetting.pkg == null) {
            return;
        }
        int userSettableMask2 = 75;
        boolean writeRuntimePermissions = false;
        int permissionCount = packageSetting.pkg.requestedPermissions.size();
        boolean writeInstallPermissions = false;
        int i = 0;
        while (i < permissionCount) {
            String permName = (String) packageSetting.pkg.requestedPermissions.get(i);
            BasePermission bp = this.mPermissionManager.getPermissionTEMP(permName);
            if (bp == null) {
                userSettableMask = userSettableMask2;
            } else {
                if (packageSetting.sharedUser != null) {
                    boolean used = false;
                    int packageCount = packageSetting.sharedUser.packages.size();
                    int j = 0;
                    while (true) {
                        if (j < packageCount) {
                            PackageSetting pkg = packageSetting.sharedUser.packages.valueAt(j);
                            if (pkg.pkg == null) {
                                userSettableMask = userSettableMask2;
                            } else {
                                userSettableMask = userSettableMask2;
                                if (!pkg.pkg.packageName.equals(packageSetting.pkg.packageName) && pkg.pkg.requestedPermissions.contains(permName)) {
                                    used = true;
                                }
                            }
                            j++;
                            userSettableMask2 = userSettableMask;
                        } else {
                            userSettableMask = userSettableMask2;
                        }
                    }
                    if (used) {
                    }
                } else {
                    userSettableMask = userSettableMask2;
                }
                PermissionsState permissionsState = ps.getPermissionsState();
                int oldFlags = permissionsState.getPermissionFlags(permName, userId);
                boolean hasInstallState = permissionsState.getInstallPermissionState(permName) != null;
                int flags = 0;
                if (this.mSettings.mPermissions.mPermissionReviewRequired && packageSetting.pkg.applicationInfo.targetSdkVersion < 23) {
                    flags = 0 | 64;
                }
                if (permissionsState.updatePermissionFlags(bp, userId, 75, flags)) {
                    if (hasInstallState) {
                        writeInstallPermissions = true;
                    } else {
                        writeRuntimePermissions = true;
                    }
                }
                if (bp.isRuntime() && (oldFlags & 20) == 0) {
                    if ((oldFlags & 32) != 0) {
                        if (permissionsState.grantRuntimePermission(bp, userId) != -1) {
                            writeRuntimePermissions = true;
                        }
                    } else if ((flags & 64) == 0) {
                        int revokeResult = permissionsState.revokeRuntimePermission(bp, userId);
                        switch (revokeResult) {
                            case 0:
                            case 1:
                                writeRuntimePermissions = true;
                                final int appId = packageSetting.appId;
                                this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.16
                                    @Override // java.lang.Runnable
                                    public void run() {
                                        PackageManagerService.this.killUid(appId, userId, PackageManagerService.KILL_APP_REASON_PERMISSIONS_REVOKED);
                                    }
                                });
                                continue;
                        }
                    }
                }
            }
            i++;
            userSettableMask2 = userSettableMask;
            packageSetting = ps;
        }
        if (writeRuntimePermissions) {
            this.mSettings.writeRuntimePermissionsForUserLPr(userId, true);
        }
        if (writeInstallPermissions) {
            this.mSettings.writeLPr();
        }
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
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.17
            @Override // java.lang.Runnable
            public void run() {
                PackageSetting ps = pkg == null ? null : (PackageSetting) pkg.mExtras;
                boolean doClearData = true;
                if (ps != null) {
                    boolean targetIsInstantApp = ps.getInstantApp(UserHandle.getUserId(callingUid));
                    doClearData = !targetIsInstantApp || hasAccessInstantApps == 0;
                }
                if (doClearData) {
                    synchronized (PackageManagerService.this.mInstallLock) {
                        PackageManagerService.this.clearAppDataLIF(pkg, userId, 259);
                        PackageManagerService.this.clearAppDataLIF(pkg, userId, UsbTerminalTypes.TERMINAL_IN_PERSONAL_MIC);
                    }
                    PackageManagerService.this.clearExternalStorageDataSync(packageName, userId, false);
                }
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, true);
                    } catch (RemoteException e) {
                        Log.i(PackageManagerService.TAG, "Observer no longer exists.");
                    }
                }
            }
        });
    }

    public void getPackageSizeInfo(String packageName, int userHandle, IPackageStatsObserver observer) {
        throw new UnsupportedOperationException("Shame on you for calling the hidden API getPackageSizeInfo(). Shame!");
    }

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
    public int getUidTargetSdkVersionLockedLPr(int uid) {
        int v;
        Object obj = this.mSettings.getUserIdLPr(uid);
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
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        synchronized (this.mPackages) {
            try {
                try {
                    if (this.mContext.checkCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS") != 0) {
                        if (getUidTargetSdkVersionLockedLPr(callingUid) < 8) {
                            Slog.w(TAG, "Ignoring addPreferredActivity() from uid " + callingUid);
                            return;
                        }
                        this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
                    }
                    PreferredIntentResolver pir = this.mSettings.editPreferredActivitiesLPw(userId);
                    StringBuilder sb = new StringBuilder();
                    try {
                        sb.append(opname);
                        sb.append(" activity ");
                        sb.append(activity.flattenToShortString());
                        sb.append(" for user ");
                        sb.append(userId);
                        sb.append(":");
                        Slog.i(TAG, sb.toString());
                        filter.dump(new LogPrinter(4, TAG), "  ");
                        pir.addFilter(new PreferredActivity(filter, match, set, activity, always));
                        scheduleWritePackageRestrictionsLocked(userId);
                        postPreferredActivityChangedBroadcast(userId);
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private void postPreferredActivityChangedBroadcast(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$pG5M8N0ge8cs9_1xCnV9yYuSdCw
            @Override // java.lang.Runnable
            public final void run() {
                PackageManagerService.lambda$postPreferredActivityChangedBroadcast$7(userId);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$postPreferredActivityChangedBroadcast$7(int userId) {
        IActivityManager am = ActivityManager.getService();
        if (am == null) {
            return;
        }
        Intent intent = new Intent("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED");
        intent.putExtra("android.intent.extra.user_handle", userId);
        try {
            am.broadcastIntent((IApplicationThread) null, intent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, (String[]) null, -1, (Bundle) null, false, false, userId);
        } catch (RemoteException e) {
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:57:0x01bf A[Catch: all -> 0x0248, TryCatch #2 {all -> 0x0248, blocks: (B:45:0x0172, B:47:0x0178, B:49:0x017c, B:50:0x01af, B:57:0x01bf, B:59:0x01c3, B:61:0x01ea, B:63:0x01f0, B:65:0x01fa, B:66:0x0225, B:70:0x0233, B:71:0x0241, B:75:0x0246), top: B:86:0x0038 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void replacePreferredActivity(android.content.IntentFilter r17, int r18, android.content.ComponentName[] r19, android.content.ComponentName r20, int r21) {
        /*
            Method dump skipped, instructions count: 608
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.replacePreferredActivity(android.content.IntentFilter, int, android.content.ComponentName[], android.content.ComponentName, int):void");
    }

    public void clearPackagePreferredActivities(String packageName) {
        int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return;
        }
        synchronized (this.mPackages) {
            PackageParser.Package pkg = this.mPackages.get(packageName);
            if ((pkg == null || pkg.applicationInfo.uid != callingUid) && this.mContext.checkCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS") != 0) {
                if (getUidTargetSdkVersionLockedLPr(callingUid) < 8) {
                    Slog.w(TAG, "Ignoring clearPackagePreferredActivities() from uid " + callingUid);
                    return;
                }
                this.mContext.enforceCallingOrSelfPermission("android.permission.SET_PREFERRED_APPLICATIONS", null);
            }
            PackageSetting ps = this.mSettings.getPackageLPr(packageName);
            if (ps == null || !filterAppAccessLPr(ps, callingUid, UserHandle.getUserId(callingUid))) {
                int user = UserHandle.getCallingUserId();
                if (clearPackagePreferredActivitiesLPw(packageName, user)) {
                    scheduleWritePackageRestrictionsLocked(user);
                }
            }
        }
    }

    boolean clearPackagePreferredActivitiesLPw(String packageName, int userId) {
        boolean changed = false;
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
                    changed = true;
                }
            }
        }
        if (changed) {
            postPreferredActivityChangedBroadcast(userId);
        }
        return changed;
    }

    private void clearIntentFilterVerificationsLPw(int userId) {
        int packageCount = this.mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageParser.Package pkg = this.mPackages.valueAt(i);
            clearIntentFilterVerificationsLPw(pkg.packageName, userId);
        }
    }

    void clearIntentFilterVerificationsLPw(String packageName, int userId) {
        int[] userIds;
        if (userId == -1) {
            if (this.mSettings.removeIntentFilterVerificationLPw(packageName, sUserManager.getUserIds())) {
                for (int oneUserId : sUserManager.getUserIds()) {
                    scheduleWritePackageRestrictionsLocked(oneUserId);
                }
            }
        } else if (this.mSettings.removeIntentFilterVerificationLPw(packageName, userId)) {
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
            synchronized (this.mPackages) {
                clearPackagePreferredActivitiesLPw(null, userId);
                this.mSettings.applyDefaultPreferredAppsLPw(this, userId);
                applyFactoryDefaultBrowserLPw(userId);
                clearIntentFilterVerificationsLPw(userId);
                primeDomainVerificationsLPw(userId);
                resetUserChangesToRuntimePermissionsAndFlagsLPw(userId);
                scheduleWritePackageRestrictionsLocked(userId);
            }
            resetNetworkPolicies(userId);
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
            Slog.i(TAG, "Adding persistent preferred activity " + activity + " for user " + userId + ":");
            filter.dump(new LogPrinter(4, TAG), "  ");
            this.mSettings.editPersistentPreferredActivitiesLPw(userId).addFilter(new PersistentPreferredActivity(filter, activity));
            scheduleWritePackageRestrictionsLocked(userId);
            postPreferredActivityChangedBroadcast(userId);
        }
    }

    public void clearPackagePersistentPreferredActivities(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 1000) {
            throw new SecurityException("clearPackagePersistentPreferredActivities can only be run by the system");
        }
        boolean changed = false;
        synchronized (this.mPackages) {
            ArrayList<PersistentPreferredActivity> removed = null;
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
            if (changed) {
                scheduleWritePackageRestrictionsLocked(userId);
                postPreferredActivityChangedBroadcast(userId);
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
        if (type != 2) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Didn't find start tag during restore");
                return;
            }
            return;
        }
        Slog.v(TAG, ":: restoreFromXml() : got to tag " + parser.getName());
        if (!expectedStartTag.equals(parser.getName())) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Found unexpected tag " + parser.getName());
                return;
            }
            return;
        }
        do {
        } while (parser.next() == 4);
        Slog.v(TAG, ":: stepped forward, applying functor at tag " + parser.getName());
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
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write preferred activities for backup", e);
            }
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
            restoreFromXml(parser, userId, TAG_PREFERRED_BACKUP, new BlobXmlRestorer() { // from class: com.android.server.pm.PackageManagerService.18
                @Override // com.android.server.pm.PackageManagerService.BlobXmlRestorer
                public void apply(XmlPullParser parser2, int userId2) throws XmlPullParserException, IOException {
                    synchronized (PackageManagerService.this.mPackages) {
                        PackageManagerService.this.mSettings.readPreferredActivitiesLPw(parser2, userId2);
                    }
                }
            });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring preferred activities: " + e.getMessage());
            }
        }
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
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write default apps for backup", e);
            }
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
            restoreFromXml(parser, userId, TAG_DEFAULT_APPS, new BlobXmlRestorer() { // from class: com.android.server.pm.PackageManagerService.19
                @Override // com.android.server.pm.PackageManagerService.BlobXmlRestorer
                public void apply(XmlPullParser parser2, int userId2) throws XmlPullParserException, IOException {
                    synchronized (PackageManagerService.this.mPackages) {
                        PackageManagerService.this.mSettings.readDefaultAppsLPw(parser2, userId2);
                    }
                }
            });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring default apps: " + e.getMessage());
            }
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
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write default apps for backup", e);
            }
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
            restoreFromXml(parser, userId, TAG_INTENT_FILTER_VERIFICATION, new BlobXmlRestorer() { // from class: com.android.server.pm.PackageManagerService.20
                @Override // com.android.server.pm.PackageManagerService.BlobXmlRestorer
                public void apply(XmlPullParser parser2, int userId2) throws XmlPullParserException, IOException {
                    synchronized (PackageManagerService.this.mPackages) {
                        PackageManagerService.this.mSettings.readAllDomainVerificationsLPr(parser2, userId2);
                        PackageManagerService.this.mSettings.writeLPr();
                    }
                }
            });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring preferred activities: " + e.getMessage());
            }
        }
    }

    public byte[] getPermissionGrantBackup(int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call getPermissionGrantBackup()");
        }
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_PERMISSION_BACKUP);
            synchronized (this.mPackages) {
                serializeRuntimePermissionGrantsLPr(serializer, userId);
            }
            serializer.endTag(null, TAG_PERMISSION_BACKUP);
            serializer.endDocument();
            serializer.flush();
            return dataStream.toByteArray();
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write default apps for backup", e);
            }
            return null;
        }
    }

    public void restorePermissionGrants(byte[] backup, int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call restorePermissionGrants()");
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_PERMISSION_BACKUP, new BlobXmlRestorer() { // from class: com.android.server.pm.PackageManagerService.21
                @Override // com.android.server.pm.PackageManagerService.BlobXmlRestorer
                public void apply(XmlPullParser parser2, int userId2) throws XmlPullParserException, IOException {
                    synchronized (PackageManagerService.this.mPackages) {
                        PackageManagerService.this.processRestoredPermissionGrantsLPr(parser2, userId2);
                    }
                }
            });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring preferred activities: " + e.getMessage());
            }
        }
    }

    private void serializeRuntimePermissionGrantsLPr(XmlSerializer serializer, int userId) throws IOException {
        boolean isGranted;
        PackageManagerService packageManagerService = this;
        serializer.startTag(null, TAG_ALL_GRANTS);
        int N = packageManagerService.mSettings.mPackages.size();
        int i = 0;
        while (i < N) {
            PackageSetting ps = packageManagerService.mSettings.mPackages.valueAt(i);
            boolean pkgGrantsKnown = false;
            PermissionsState packagePerms = ps.getPermissionsState();
            for (PermissionsState.PermissionState state : packagePerms.getRuntimePermissionStates(userId)) {
                int grantFlags = state.getFlags();
                if ((grantFlags & 52) == 0 && ((isGranted = state.isGranted()) || (grantFlags & 11) != 0)) {
                    String packageName = packageManagerService.mSettings.mPackages.keyAt(i);
                    if (!pkgGrantsKnown) {
                        serializer.startTag(null, TAG_GRANT);
                        serializer.attribute(null, ATTR_PACKAGE_NAME, packageName);
                        pkgGrantsKnown = true;
                    }
                    boolean userSet = (grantFlags & 1) != 0;
                    boolean userFixed = (grantFlags & 2) != 0;
                    boolean revoke = (grantFlags & 8) != 0;
                    serializer.startTag(null, TAG_PERMISSION);
                    serializer.attribute(null, "name", state.getName());
                    if (isGranted) {
                        serializer.attribute(null, ATTR_IS_GRANTED, "true");
                    }
                    if (userSet) {
                        serializer.attribute(null, ATTR_USER_SET, "true");
                    }
                    if (userFixed) {
                        serializer.attribute(null, ATTR_USER_FIXED, "true");
                    }
                    if (revoke) {
                        serializer.attribute(null, ATTR_REVOKE_ON_UPGRADE, "true");
                    }
                    serializer.endTag(null, TAG_PERMISSION);
                }
                packageManagerService = this;
            }
            if (pkgGrantsKnown) {
                serializer.endTag(null, TAG_GRANT);
            }
            i++;
            packageManagerService = this;
        }
        serializer.endTag(null, TAG_ALL_GRANTS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void processRestoredPermissionGrantsLPr(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        String pkgName = null;
        while (true) {
            int outerDepth2 = outerDepth;
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth2)) {
                break;
            }
            if (type != 3 && type != 4) {
                String tagName = parser.getName();
                if (tagName.equals(TAG_GRANT)) {
                    pkgName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    if (DEBUG_BACKUP) {
                        Slog.v(TAG, "+++ Restoring grants for package " + pkgName);
                    }
                } else if (tagName.equals(TAG_PERMISSION)) {
                    boolean isGranted = "true".equals(parser.getAttributeValue(null, ATTR_IS_GRANTED));
                    String permName = parser.getAttributeValue(null, "name");
                    int newFlagSet = 0;
                    if ("true".equals(parser.getAttributeValue(null, ATTR_USER_SET))) {
                        newFlagSet = 0 | 1;
                    }
                    if ("true".equals(parser.getAttributeValue(null, ATTR_USER_FIXED))) {
                        newFlagSet |= 2;
                    }
                    if ("true".equals(parser.getAttributeValue(null, ATTR_REVOKE_ON_UPGRADE))) {
                        newFlagSet |= 8;
                    }
                    int newFlagSet2 = newFlagSet;
                    if (DEBUG_BACKUP) {
                        Slog.v(TAG, "  + Restoring grant: pkg=" + pkgName + " perm=" + permName + " granted=" + isGranted + " bits=0x" + Integer.toHexString(newFlagSet2));
                    }
                    PackageSetting ps = this.mSettings.mPackages.get(pkgName);
                    if (ps != null) {
                        if (DEBUG_BACKUP) {
                            Slog.v(TAG, "        + already installed; applying");
                        }
                        PermissionsState perms = ps.getPermissionsState();
                        BasePermission bp = this.mPermissionManager.getPermissionTEMP(permName);
                        if (bp != null) {
                            if (isGranted) {
                                perms.grantRuntimePermission(bp, userId);
                            }
                            if (newFlagSet2 != 0) {
                                perms.updatePermissionFlags(bp, userId, 11, newFlagSet2);
                            }
                        }
                    } else {
                        if (DEBUG_BACKUP) {
                            Slog.v(TAG, "        - not yet installed; saving for later");
                        }
                        this.mSettings.processRestoredPermissionGrantLPr(pkgName, permName, isGranted, newFlagSet2, userId);
                    }
                } else {
                    reportSettingsProblem(5, "Unknown element under <perm-grant-backup>: " + tagName);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
            outerDepth = outerDepth2;
        }
        scheduleWriteSettingsLocked();
        this.mSettings.writeRuntimePermissionsForUserLPr(userId, false);
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

    public void sendSessionCommitBroadcast(PackageInstaller.SessionInfo sessionInfo, int userId) {
        UserManagerService ums = UserManagerService.getInstance();
        if (ums != null) {
            UserInfo parent = ums.getProfileParent(userId);
            int launcherUid = parent != null ? parent.id : userId;
            ComponentName launcherComponent = getDefaultHomeActivity(launcherUid);
            if (launcherComponent != null) {
                Intent launcherIntent = new Intent("android.content.pm.action.SESSION_COMMITTED").putExtra("android.content.pm.extra.SESSION", sessionInfo).putExtra("android.intent.extra.USER", UserHandle.of(userId)).setPackage(launcherComponent.getPackageName());
                this.mContext.sendBroadcastAsUser(launcherIntent, UserHandle.of(launcherUid));
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
        Intent intent = getHomeIntent();
        List<ResolveInfo> list = queryIntentActivitiesInternal(intent, null, 128, userId);
        ResolveInfo preferred = findPreferredActivity(intent, null, 0, list, 0, true, false, false, userId);
        allHomeCandidates.clear();
        if (list != null) {
            for (ResolveInfo ri : list) {
                allHomeCandidates.add(ri);
            }
        }
        if (preferred == null || preferred.activityInfo == null) {
            return null;
        }
        return new ComponentName(preferred.activityInfo.packageName, preferred.activityInfo.name);
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
        return this.mContext.getString(17039661);
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private void setEnabledSetting(java.lang.String r40, java.lang.String r41, int r42, int r43, int r44, java.lang.String r45) {
        /*
            Method dump skipped, instructions count: 1618
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.setEnabledSetting(java.lang.String, java.lang.String, int, int, int, java.lang.String):void");
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
        if (DEBUG_INSTALL) {
            Log.v(TAG, "Sending package changed: package=" + packageName + " components=" + componentNames);
        }
        Bundle extras = new Bundle(4);
        extras.putString("android.intent.extra.changed_component_name", componentNames.get(0));
        String[] nameList = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray("android.intent.extra.changed_component_name_list", nameList);
        extras.putBoolean("android.intent.extra.DONT_KILL_APP", killFlag);
        extras.putInt("android.intent.extra.UID", packageUid);
        int flags = !componentNames.contains(packageName) ? 1073741824 : 0;
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
            return this.mSettings.getInstallerPackageNameLPr(packageName);
        }
    }

    public boolean isOrphaned(String packageName) {
        boolean isOrphaned;
        synchronized (this.mPackages) {
            isOrphaned = this.mSettings.isOrphaned(packageName);
        }
        return isOrphaned;
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

    public void systemReady() {
        int[] userIds;
        enforceSystemOrRoot("Only the system can claim the system is ready");
        boolean compatibilityModeEnabled = true;
        this.mSystemReady = true;
        final ContentResolver resolver = this.mContext.getContentResolver();
        ContentObserver co = new ContentObserver(this.mHandler) { // from class: com.android.server.pm.PackageManagerService.22
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                PackageManagerService packageManagerService = PackageManagerService.this;
                boolean z = true;
                if (Settings.Global.getInt(resolver, "enable_ephemeral_feature", 1) != 0 && Settings.Secure.getInt(resolver, "instant_apps_enabled", 1) != 0) {
                    z = false;
                }
                packageManagerService.mWebInstantAppsDisabled = z;
            }
        };
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("enable_ephemeral_feature"), false, co, 0);
        this.mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("instant_apps_enabled"), false, co, 0);
        co.onChange(true);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(this.mContext.getOpPackageName(), this, this.mContext.getContentResolver(), 0);
        disableSkuSpecificApps();
        if (Settings.Global.getInt(this.mContext.getContentResolver(), "compatibility_mode", 1) != 1) {
            compatibilityModeEnabled = false;
        }
        PackageParser.setCompatibilityModeEnabled(compatibilityModeEnabled);
        if (DEBUG_SETTINGS) {
            Log.d(TAG, "compatibility mode:" + compatibilityModeEnabled);
        }
        int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;
        synchronized (this.mPackages) {
            try {
                ArrayList<PreferredActivity> removed = new ArrayList<>();
                for (int i = 0; i < this.mSettings.mPreferredActivities.size(); i++) {
                    PreferredIntentResolver pir = this.mSettings.mPreferredActivities.valueAt(i);
                    removed.clear();
                    for (PreferredActivity pa : pir.filterSet()) {
                        if (this.mActivities.mActivities.get(pa.mPref.mComponent) == null) {
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
                }
                if (this.mPostSystemReadyMessages != null) {
                    Iterator<Message> it = this.mPostSystemReadyMessages.iterator();
                    while (it.hasNext()) {
                        Message msg = it.next();
                        msg.sendToTarget();
                    }
                    this.mPostSystemReadyMessages = null;
                }
                StorageManager storage = (StorageManager) this.mContext.getSystemService(StorageManager.class);
                storage.registerListener(this.mStorageListener);
                this.mInstallerService.systemReady();
                this.mDexManager.systemReady();
                this.mPackageDexOptimizer.systemReady();
                StorageManagerInternal StorageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
                StorageManagerInternal.addExternalStoragePolicy(new StorageManagerInternal.ExternalStorageMountPolicy() { // from class: com.android.server.pm.PackageManagerService.23
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
                    this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.pm.PackageManagerService.24
                        @Override // android.content.BroadcastReceiver
                        public void onReceive(Context context, Intent intent) {
                            PackageManagerService.this.mInstantAppResolverConnection.optimisticBind();
                            PackageManagerService.this.mContext.unregisterReceiver(this);
                        }
                    }, new IntentFilter("android.intent.action.BOOT_COMPLETED"));
                }
                xpPackageManagerService.get(this.mContext).systemReady();
                new Thread(new Runnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$gquR3vNOgXaVjcm7q7R6UKaJ_cA
                    @Override // java.lang.Runnable
                    public final void run() {
                        PackageManagerService.lambda$systemReady$8(PackageManagerService.this);
                    }
                }).start();
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public static /* synthetic */ void lambda$systemReady$8(PackageManagerService packageManagerService) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.LOCALE_CHANGED");
        packageManagerService.mContext.registerReceiver(packageManagerService.mReceiver, intentFilter);
        mXuiAppInfoInnerList = packageManagerService.napaAppInfoLoad();
        Slog.d(TAG, "when systemReady() xuiAppInfoLoad  mXuiAppInfoInnerList=" + mXuiAppInfoInnerList);
    }

    public void waitForAppDataPrepared() {
        if (this.mPrepareAppDataFuture == null) {
            return;
        }
        ConcurrentUtils.waitForFutureNoInterrupt(this.mPrepareAppDataFuture, "wait for prepareAppData");
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
        StringBuffer buf = new StringBuffer(128);
        buf.append('[');
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(array[i]);
            }
        }
        buf.append(']');
        return buf.toString();
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new PackageManagerShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* JADX WARN: Code restructure failed: missing block: B:390:0x07b3, code lost:
        if (r0.isDumping(4096) == false) goto L279;
     */
    /* JADX WARN: Code restructure failed: missing block: B:391:0x07b5, code lost:
        r2 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:392:0x07b6, code lost:
        r8 = r2;
     */
    /* JADX WARN: Code restructure failed: missing block: B:393:0x07bf, code lost:
        if (r8 >= r40.mSettings.mPreferredActivities.size()) goto L278;
     */
    /* JADX WARN: Code restructure failed: missing block: B:394:0x07c1, code lost:
        r2 = r40.mSettings.mPreferredActivities.valueAt(r8);
        r3 = r40.mSettings.mPreferredActivities.keyAt(r8);
     */
    /* JADX WARN: Code restructure failed: missing block: B:395:0x07d9, code lost:
        if (r0.getTitlePrinted() == false) goto L277;
     */
    /* JADX WARN: Code restructure failed: missing block: B:396:0x07db, code lost:
        r3 = "\nPreferred Activities User " + r3 + ":";
     */
    /* JADX WARN: Code restructure failed: missing block: B:397:0x07f2, code lost:
        r3 = "Preferred Activities User " + r3 + ":";
     */
    /* JADX WARN: Code restructure failed: missing block: B:399:0x081e, code lost:
        if (r2.dump(r42, r3, "  ", r27, true, false) == false) goto L276;
     */
    /* JADX WARN: Code restructure failed: missing block: B:400:0x0820, code lost:
        r0.setTitlePrinted(true);
     */
    /* JADX WARN: Code restructure failed: missing block: B:401:0x0824, code lost:
        r2 = r8 + 1;
     */
    /* JADX WARN: Incorrect condition in loop: B:195:0x03fd */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:232:0x049f  */
    /* JADX WARN: Removed duplicated region for block: B:359:0x0729  */
    /* JADX WARN: Removed duplicated region for block: B:360:0x072c  */
    /* JADX WARN: Removed duplicated region for block: B:363:0x0740 A[Catch: all -> 0x0745, TryCatch #6 {all -> 0x0745, blocks: (B:348:0x06f9, B:350:0x06ff, B:355:0x0719, B:357:0x0721, B:361:0x072e, B:363:0x0740, B:368:0x0750, B:370:0x0758, B:374:0x0765, B:376:0x0777, B:378:0x077d, B:380:0x0785, B:384:0x0792, B:386:0x07a4, B:389:0x07ad, B:392:0x07b6, B:394:0x07c1, B:396:0x07db, B:398:0x0808, B:400:0x0820, B:401:0x0824, B:397:0x07f2, B:403:0x0829, B:405:0x082f), top: B:669:0x06f9 }] */
    /* JADX WARN: Removed duplicated region for block: B:372:0x0760  */
    /* JADX WARN: Removed duplicated region for block: B:373:0x0763  */
    /* JADX WARN: Removed duplicated region for block: B:376:0x0777 A[Catch: all -> 0x0745, TryCatch #6 {all -> 0x0745, blocks: (B:348:0x06f9, B:350:0x06ff, B:355:0x0719, B:357:0x0721, B:361:0x072e, B:363:0x0740, B:368:0x0750, B:370:0x0758, B:374:0x0765, B:376:0x0777, B:378:0x077d, B:380:0x0785, B:384:0x0792, B:386:0x07a4, B:389:0x07ad, B:392:0x07b6, B:394:0x07c1, B:396:0x07db, B:398:0x0808, B:400:0x0820, B:401:0x0824, B:397:0x07f2, B:403:0x0829, B:405:0x082f), top: B:669:0x06f9 }] */
    /* JADX WARN: Removed duplicated region for block: B:382:0x078d  */
    /* JADX WARN: Removed duplicated region for block: B:383:0x0790  */
    /* JADX WARN: Removed duplicated region for block: B:386:0x07a4 A[Catch: all -> 0x0745, TryCatch #6 {all -> 0x0745, blocks: (B:348:0x06f9, B:350:0x06ff, B:355:0x0719, B:357:0x0721, B:361:0x072e, B:363:0x0740, B:368:0x0750, B:370:0x0758, B:374:0x0765, B:376:0x0777, B:378:0x077d, B:380:0x0785, B:384:0x0792, B:386:0x07a4, B:389:0x07ad, B:392:0x07b6, B:394:0x07c1, B:396:0x07db, B:398:0x0808, B:400:0x0820, B:401:0x0824, B:397:0x07f2, B:403:0x0829, B:405:0x082f), top: B:669:0x06f9 }] */
    /* JADX WARN: Removed duplicated region for block: B:433:0x08bd  */
    /* JADX WARN: Removed duplicated region for block: B:477:0x0a6d  */
    /* JADX WARN: Removed duplicated region for block: B:486:0x0a86  */
    /* JADX WARN: Removed duplicated region for block: B:488:0x0a8c A[Catch: all -> 0x0b5c, TryCatch #19 {all -> 0x0b5c, blocks: (B:482:0x0a79, B:488:0x0a8c, B:490:0x0a94, B:491:0x0aa3, B:493:0x0aa9, B:495:0x0ab1, B:499:0x0abe, B:501:0x0ac4, B:502:0x0ac7, B:503:0x0acd, B:504:0x0ae7, B:505:0x0af2, B:507:0x0af8, B:509:0x0b06, B:513:0x0b13, B:515:0x0b19, B:516:0x0b1c, B:517:0x0b22, B:519:0x0b45, B:521:0x0b4b, B:526:0x0b62, B:528:0x0b6a), top: B:685:0x0a79 }] */
    /* JADX WARN: Removed duplicated region for block: B:493:0x0aa9 A[Catch: all -> 0x0b5c, TryCatch #19 {all -> 0x0b5c, blocks: (B:482:0x0a79, B:488:0x0a8c, B:490:0x0a94, B:491:0x0aa3, B:493:0x0aa9, B:495:0x0ab1, B:499:0x0abe, B:501:0x0ac4, B:502:0x0ac7, B:503:0x0acd, B:504:0x0ae7, B:505:0x0af2, B:507:0x0af8, B:509:0x0b06, B:513:0x0b13, B:515:0x0b19, B:516:0x0b1c, B:517:0x0b22, B:519:0x0b45, B:521:0x0b4b, B:526:0x0b62, B:528:0x0b6a), top: B:685:0x0a79 }] */
    /* JADX WARN: Removed duplicated region for block: B:507:0x0af8 A[Catch: all -> 0x0b5c, TryCatch #19 {all -> 0x0b5c, blocks: (B:482:0x0a79, B:488:0x0a8c, B:490:0x0a94, B:491:0x0aa3, B:493:0x0aa9, B:495:0x0ab1, B:499:0x0abe, B:501:0x0ac4, B:502:0x0ac7, B:503:0x0acd, B:504:0x0ae7, B:505:0x0af2, B:507:0x0af8, B:509:0x0b06, B:513:0x0b13, B:515:0x0b19, B:516:0x0b1c, B:517:0x0b22, B:519:0x0b45, B:521:0x0b4b, B:526:0x0b62, B:528:0x0b6a), top: B:685:0x0a79 }] */
    /* JADX WARN: Removed duplicated region for block: B:526:0x0b62 A[Catch: all -> 0x0b5c, TryCatch #19 {all -> 0x0b5c, blocks: (B:482:0x0a79, B:488:0x0a8c, B:490:0x0a94, B:491:0x0aa3, B:493:0x0aa9, B:495:0x0ab1, B:499:0x0abe, B:501:0x0ac4, B:502:0x0ac7, B:503:0x0acd, B:504:0x0ae7, B:505:0x0af2, B:507:0x0af8, B:509:0x0b06, B:513:0x0b13, B:515:0x0b19, B:516:0x0b1c, B:517:0x0b22, B:519:0x0b45, B:521:0x0b4b, B:526:0x0b62, B:528:0x0b6a), top: B:685:0x0a79 }] */
    /* JADX WARN: Removed duplicated region for block: B:540:0x0b92  */
    /* JADX WARN: Removed duplicated region for block: B:544:0x0b9e A[Catch: all -> 0x0b89, TRY_ENTER, TRY_LEAVE, TryCatch #8 {all -> 0x0b89, blocks: (B:534:0x0b85, B:544:0x0b9e, B:548:0x0bb1, B:550:0x0bb7, B:551:0x0bba, B:553:0x0bd3, B:555:0x0bf4, B:558:0x0c03), top: B:671:0x0b85 }] */
    /* JADX WARN: Removed duplicated region for block: B:548:0x0bb1 A[Catch: all -> 0x0b89, TRY_ENTER, TryCatch #8 {all -> 0x0b89, blocks: (B:534:0x0b85, B:544:0x0b9e, B:548:0x0bb1, B:550:0x0bb7, B:551:0x0bba, B:553:0x0bd3, B:555:0x0bf4, B:558:0x0c03), top: B:671:0x0b85 }] */
    /* JADX WARN: Removed duplicated region for block: B:562:0x0c2f  */
    /* JADX WARN: Removed duplicated region for block: B:571:0x0c47 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:576:0x0c57 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:579:0x0c74 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:580:0x0c7a  */
    /* JADX WARN: Removed duplicated region for block: B:586:0x0c97 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:591:0x0ca7 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:594:0x0cc4 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:596:0x0ccb A[Catch: all -> 0x0dc9, LOOP:11: B:596:0x0ccb->B:598:0x0cd5, LOOP_START, PHI: r16 
      PHI: (r16v2 'i' int) = (r16v1 'i' int), (r16v3 'i' int) binds: [B:593:0x0cc2, B:598:0x0cd5] A[DONT_GENERATE, DONT_INLINE], TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:601:0x0ce8 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:606:0x0cf8 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:610:0x0d0c A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:615:0x0d35 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:619:0x0d43 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:622:0x0d4b A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:626:0x0d59 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:629:0x0d61 A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:636:0x0d86  */
    /* JADX WARN: Removed duplicated region for block: B:638:0x0d8a A[Catch: all -> 0x0dc9, TryCatch #9 {all -> 0x0dc9, blocks: (B:657:0x0dc7, B:567:0x0c3b, B:571:0x0c47, B:574:0x0c51, B:576:0x0c57, B:577:0x0c5a, B:579:0x0c74, B:584:0x0c92, B:581:0x0c7c, B:583:0x0c84, B:586:0x0c97, B:589:0x0ca1, B:591:0x0ca7, B:592:0x0caa, B:594:0x0cc4, B:599:0x0ce3, B:596:0x0ccb, B:598:0x0cd5, B:601:0x0ce8, B:604:0x0cf2, B:606:0x0cf8, B:607:0x0cfb, B:608:0x0d06, B:610:0x0d0c, B:612:0x0d1a, B:615:0x0d35, B:617:0x0d3d, B:619:0x0d43, B:620:0x0d46, B:622:0x0d4b, B:624:0x0d53, B:626:0x0d59, B:627:0x0d5c, B:629:0x0d61, B:632:0x0d6b, B:634:0x0d71, B:635:0x0d74, B:638:0x0d8a, B:640:0x0d90, B:641:0x0d96), top: B:673:0x04a8 }] */
    /* JADX WARN: Removed duplicated region for block: B:643:0x0d99  */
    /* JADX WARN: Removed duplicated region for block: B:673:0x04a8 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:676:0x0b79 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:768:? A[ADDED_TO_REGION, RETURN, SYNTHETIC] */
    /* JADX WARN: Type inference failed for: r15v3 */
    /* JADX WARN: Type inference failed for: r15v36 */
    /* JADX WARN: Type inference failed for: r15v4 */
    /* JADX WARN: Type inference failed for: r15v44 */
    /* JADX WARN: Type inference failed for: r15v45 */
    /* JADX WARN: Type inference failed for: r15v7 */
    /* JADX WARN: Type inference failed for: r15v8 */
    /* JADX WARN: Type inference failed for: r15v9 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    protected void dump(java.io.FileDescriptor r41, java.io.PrintWriter r42, java.lang.String[] r43) {
        /*
            Method dump skipped, instructions count: 3531
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
    }

    private void disableSkuSpecificApps() {
        String[] apkList = this.mContext.getResources().getStringArray(17236007);
        String[] skuArray = this.mContext.getResources().getStringArray(17236006);
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
            LongSparseArray<SharedLibraryEntry> versionedLib = this.mSharedLibraries.get(libName);
            if (versionedLib != null) {
                int versionCount = versionedLib.size();
                for (int j = 0; j < versionCount; j++) {
                    SharedLibraryEntry libEntry = versionedLib.valueAt(j);
                    long sharedLibraryToken = proto.start(2246267895811L);
                    proto.write(1138166333441L, libEntry.info.getName());
                    boolean isJar = libEntry.path != null;
                    proto.write(1133871366146L, isJar);
                    if (isJar) {
                        proto.write(1138166333443L, libEntry.path);
                    } else {
                        proto.write(1138166333444L, libEntry.apk);
                    }
                    proto.end(sharedLibraryToken);
                }
            }
        }
    }

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
                for (String host : ivi.getDomains()) {
                    result.add(host);
                }
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
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.25
            @Override // java.lang.Runnable
            public void run() {
                PackageManagerService.this.loadPrivatePackagesInner(vol);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't wrap try/catch for region: R(12:52|(2:89|90)(2:54|(2:56|57)(2:88|72))|58|59|60|118|68|69|70|71|72|50) */
    /* JADX WARN: Code restructure failed: missing block: B:66:0x012f, code lost:
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:67:0x0130, code lost:
        r20 = r1;
     */
    /* JADX WARN: Removed duplicated region for block: B:105:0x00b7 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:102:? -> B:58:0x0126). Please submit an issue!!! */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:106:? -> B:39:0x00ca). Please submit an issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void loadPrivatePackagesInner(android.os.storage.VolumeInfo r23) {
        /*
            Method dump skipped, instructions count: 483
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.loadPrivatePackagesInner(android.os.storage.VolumeInfo):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unloadPrivatePackages(final VolumeInfo vol) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.26
            @Override // java.lang.Runnable
            public void run() {
                PackageManagerService.this.unloadPrivatePackagesInner(vol);
            }
        });
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    /* JADX INFO: Access modifiers changed from: private */
    public void unloadPrivatePackagesInner(android.os.storage.VolumeInfo r27) {
        /*
            Method dump skipped, instructions count: 347
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.unloadPrivatePackagesInner(android.os.storage.VolumeInfo):void");
    }

    private void assertPackageKnown(String volumeUuid, String packageName) throws PackageManagerException {
        synchronized (this.mPackages) {
            String packageName2 = normalizePackageNameLPr(packageName);
            PackageSetting ps = this.mSettings.mPackages.get(packageName2);
            if (ps == null) {
                throw new PackageManagerException("Package " + packageName2 + " is unknown");
            } else if (!TextUtils.equals(volumeUuid, ps.volumeUuid)) {
                throw new PackageManagerException("Package " + packageName2 + " found on unknown volume " + volumeUuid + "; expected volume " + ps.volumeUuid);
            }
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
        int i = 0;
        List<File> filesToDelete = null;
        for (File file : files) {
            boolean isPackage = (PackageParser.isApkFile(file) || file.isDirectory()) && !PackageInstallerService.isStageName(file.getName());
            if (isPackage) {
                String absolutePath = file.getAbsolutePath();
                boolean pathValid = false;
                int absoluteCodePathCount = absoluteCodePaths.size();
                int i2 = 0;
                while (true) {
                    if (i2 >= absoluteCodePathCount) {
                        break;
                    }
                    String absoluteCodePath = absoluteCodePaths.get(i2);
                    if (!absolutePath.startsWith(absoluteCodePath)) {
                        i2++;
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
            while (true) {
                int i3 = i;
                if (i3 < fileToDeleteCount) {
                    File fileToDelete = filesToDelete.get(i3);
                    PackageManagerServiceUtils.logCriticalInfo(5, "Destroying orphaned" + fileToDelete);
                    synchronized (this.mInstallLock) {
                        removeCodePathLI(fileToDelete);
                    }
                    i = i3 + 1;
                } else {
                    return;
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

    private void reconcileAppsDataLI(String volumeUuid, int userId, int flags, boolean migrateAppData) {
        reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppData, false);
    }

    private List<String> reconcileAppsDataLI(String volumeUuid, int userId, int flags, boolean migrateAppData, boolean onlyCoreApps) {
        List<PackageSetting> packages;
        int i;
        File[] files;
        int i2;
        File[] files2;
        int i3;
        int i4;
        int i5;
        File ceDir;
        File ceDir2;
        Slog.v(TAG, "reconcileAppsData for " + volumeUuid + " u" + userId + " 0x" + Integer.toHexString(flags) + " migrateAppData=" + migrateAppData);
        addBootEvent("PMS: reconcileAppsDataLI");
        List<String> result = onlyCoreApps ? new ArrayList<>() : null;
        File deDir = Environment.getDataUserCeDirectory(volumeUuid, userId);
        File deDir2 = Environment.getDataUserDeDirectory(volumeUuid, userId);
        if ((flags & 2) != 0) {
            if (StorageManager.isFileEncryptedNativeOrEmulated() && !StorageManager.isUserKeyUnlocked(userId)) {
                throw new RuntimeException("Yikes, someone asked us to reconcile CE storage while " + userId + " was still locked; this would have caused massive data loss!");
            }
            File[] files3 = FileUtils.listFilesOrEmpty(deDir);
            int length = files3.length;
            int i6 = 0;
            while (i6 < length) {
                File file = files3[i6];
                String packageName = file.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName, userId);
                    i3 = i6;
                    i4 = length;
                    files2 = files3;
                    ceDir = deDir;
                    ceDir2 = deDir2;
                } catch (PackageManagerException e) {
                    files2 = files3;
                    PackageManagerServiceUtils.logCriticalInfo(5, "Destroying " + file + " due to: " + e);
                    try {
                        i3 = i6;
                        i4 = length;
                        ceDir = deDir;
                        i5 = 5;
                        ceDir2 = deDir2;
                        try {
                            this.mInstaller.destroyAppData(volumeUuid, packageName, userId, 2, 0L);
                        } catch (Installer.InstallerException e2) {
                            e2 = e2;
                            PackageManagerServiceUtils.logCriticalInfo(i5, "Failed to destroy: " + e2);
                            i6 = i3 + 1;
                            deDir2 = ceDir2;
                            files3 = files2;
                            length = i4;
                            deDir = ceDir;
                        }
                    } catch (Installer.InstallerException e3) {
                        e2 = e3;
                        i3 = i6;
                        i4 = length;
                        i5 = 5;
                        ceDir = deDir;
                        ceDir2 = deDir2;
                    }
                }
                i6 = i3 + 1;
                deDir2 = ceDir2;
                files3 = files2;
                length = i4;
                deDir = ceDir;
            }
        }
        File ceDir3 = deDir2;
        if ((flags & 1) != 0) {
            File[] files4 = FileUtils.listFilesOrEmpty(ceDir3);
            int length2 = files4.length;
            int i7 = 0;
            while (i7 < length2) {
                File file2 = files4[i7];
                String packageName2 = file2.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName2, userId);
                    i = i7;
                    files = files4;
                    i2 = length2;
                } catch (PackageManagerException e4) {
                    PackageManagerServiceUtils.logCriticalInfo(5, "Destroying " + file2 + " due to: " + e4);
                    try {
                        i = i7;
                        files = files4;
                        i2 = length2;
                        try {
                            this.mInstaller.destroyAppData(volumeUuid, packageName2, userId, 1, 0L);
                        } catch (Installer.InstallerException e5) {
                            e2 = e5;
                            PackageManagerServiceUtils.logCriticalInfo(5, "Failed to destroy: " + e2);
                            i7 = i + 1;
                            files4 = files;
                            length2 = i2;
                        }
                    } catch (Installer.InstallerException e6) {
                        e2 = e6;
                        i = i7;
                        files = files4;
                        i2 = length2;
                    }
                }
                i7 = i + 1;
                files4 = files;
                length2 = i2;
            }
        }
        synchronized (this.mPackages) {
            try {
                packages = this.mSettings.getVolumePackagesLPr(volumeUuid);
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
        UserManager um = (UserManager) this.mContext.getSystemService(UserManager.class);
        UserManagerInternal umInternal = getUserManagerInternal();
        for (UserInfo user : um.getUsers()) {
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

    /* JADX WARN: Removed duplicated region for block: B:50:0x0136  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void prepareAppDataLeafLIF(android.content.pm.PackageParser.Package r25, int r26, int r27) {
        /*
            Method dump skipped, instructions count: 329
            To view this dump add '--comments-level debug' option
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
                if (this.mCloseGuard != null) {
                    this.mCloseGuard.warnIfOpen();
                }
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
        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.27
            @Override // java.lang.Runnable
            public void run() {
                try {
                    PackageManagerService.this.movePackageInternal(packageName, volumeUuid, moveId, callingUid, user);
                } catch (PackageManagerException e) {
                    Slog.w(PackageManagerService.TAG, "Failed to move " + packageName, e);
                    PackageManagerService.this.mMoveCallbacks.notifyStatusChanged(moveId, e.error);
                }
            }
        });
        return moveId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:101:0x0224, code lost:
        if (com.android.server.pm.PackageManagerService.DEBUG_INSTALL == false) goto L96;
     */
    /* JADX WARN: Code restructure failed: missing block: B:102:0x0226, code lost:
        android.util.Slog.d(com.android.server.pm.PackageManagerService.TAG, "Measured code size " + r0.codeSize + ", data size " + r0.dataSize);
     */
    /* JADX WARN: Code restructure failed: missing block: B:103:0x0248, code lost:
        r35 = r5.getUsableSpace();
     */
    /* JADX WARN: Code restructure failed: missing block: B:104:0x024c, code lost:
        if (r34 == false) goto L109;
     */
    /* JADX WARN: Code restructure failed: missing block: B:105:0x024e, code lost:
        r0 = r0.codeSize + r0.dataSize;
     */
    /* JADX WARN: Code restructure failed: missing block: B:106:0x0254, code lost:
        r0 = r0.codeSize;
     */
    /* JADX WARN: Code restructure failed: missing block: B:107:0x0256, code lost:
        r40 = r0;
        r0 = r13.getStorageBytesUntilLow(r5);
     */
    /* JADX WARN: Code restructure failed: missing block: B:108:0x025e, code lost:
        if (r40 > r0) goto L107;
     */
    /* JADX WARN: Code restructure failed: missing block: B:109:0x0260, code lost:
        r48.mMoveCallbacks.notifyStatusChanged(r51, 10);
        r0 = new java.util.concurrent.CountDownLatch(1);
        r1 = new com.android.server.pm.PackageManagerService.AnonymousClass28(r48);
     */
    /* JADX WARN: Code restructure failed: missing block: B:110:0x0274, code lost:
        if (r34 == false) goto L106;
     */
    /* JADX WARN: Code restructure failed: missing block: B:111:0x0276, code lost:
        r44 = r13;
        r46 = r1;
        r14 = r3;
        new com.android.server.pm.PackageManagerService.AnonymousClass29(r48).start();
        r1 = r14.getName();
        r2 = new com.android.server.pm.PackageManagerService.MoveInfo(r51, r26, r50, r49, r1, r30, r3, r14);
        r13 = r2;
     */
    /* JADX WARN: Code restructure failed: missing block: B:112:0x02b3, code lost:
        r44 = r13;
        r46 = r1;
        r14 = r3;
        r13 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:113:0x02c7, code lost:
        r2 = r48.mHandler.obtainMessage(5);
        r3 = com.android.server.pm.PackageManagerService.OriginInfo.fromExistingFile(r14);
        r5 = new com.android.server.pm.PackageManagerService.InstallParams(r48, r3, r13, r46, r18 | 2, r3, r50, null, r53, r3, null, android.content.pm.PackageParser.SigningDetails.UNKNOWN, 0);
        r5.setTraceMethod("movePackage").setTraceCookie(java.lang.System.identityHashCode(r5));
        r2.obj = r5;
        android.os.Trace.asyncTraceBegin(262144, "movePackage", java.lang.System.identityHashCode(r2.obj));
        android.os.Trace.asyncTraceBegin(262144, "queueInstall", java.lang.System.identityHashCode(r2.obj));
        r48.mHandler.sendMessage(r2);
     */
    /* JADX WARN: Code restructure failed: missing block: B:114:0x0320, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:115:0x0321, code lost:
        r14.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:116:0x033f, code lost:
        throw new com.android.server.pm.PackageManagerException(-6, "Not enough free space to move");
     */
    /* JADX WARN: Code restructure failed: missing block: B:117:0x0340, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:90:0x01e9, code lost:
        r14.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:93:0x01f8, code lost:
        throw new com.android.server.pm.PackageManagerException(-6, "Failed to measure package size");
     */
    /* JADX WARN: Code restructure failed: missing block: B:94:0x01f9, code lost:
        r0 = th;
     */
    /* JADX WARN: Removed duplicated region for block: B:130:0x0391 A[Catch: all -> 0x03ec, TryCatch #7 {all -> 0x03ec, blocks: (B:140:0x03ea, B:128:0x0388, B:129:0x0390, B:130:0x0391, B:131:0x03b1, B:132:0x03b2, B:133:0x03c3, B:134:0x03c4, B:135:0x03d3, B:136:0x03d4, B:137:0x03e3), top: B:157:0x001c }] */
    /* JADX WARN: Removed duplicated region for block: B:146:0x01d2 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:36:0x00b4 A[Catch: all -> 0x03e4, TRY_LEAVE, TryCatch #2 {all -> 0x03e4, blocks: (B:4:0x001c, B:7:0x0032, B:9:0x003e, B:11:0x0046, B:19:0x0071, B:34:0x00ae, B:36:0x00b4, B:43:0x00cc, B:45:0x00d4, B:46:0x0119, B:24:0x0080, B:30:0x008d, B:32:0x00a8), top: B:148:0x001c }] */
    /* JADX WARN: Removed duplicated region for block: B:71:0x0188  */
    /* JADX WARN: Type inference failed for: r2v11, types: [com.android.server.pm.PackageManagerService$29] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void movePackageInternal(java.lang.String r49, java.lang.String r50, final int r51, int r52, android.os.UserHandle r53) throws com.android.server.pm.PackageManagerException {
        /*
            Method dump skipped, instructions count: 1006
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerService.movePackageInternal(java.lang.String, java.lang.String, int, int, android.os.UserHandle):void");
    }

    public int movePrimaryStorage(String volumeUuid) throws RemoteException {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MOVE_PACKAGE", null);
        final int realMoveId = this.mNextMoveId.getAndIncrement();
        Bundle extras = new Bundle();
        extras.putString("android.os.storage.extra.FS_UUID", volumeUuid);
        this.mMoveCallbacks.notifyCreated(realMoveId, extras);
        IPackageMoveObserver callback = new IPackageMoveObserver.Stub() { // from class: com.android.server.pm.PackageManagerService.30
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

    private void removeUnusedPackagesLPw(UserManagerService userManager, final int userHandle) {
        int[] users = userManager.getUserIds();
        for (PackageSetting ps : this.mSettings.mPackages.values()) {
            if (ps.pkg != null) {
                final String packageName = ps.pkg.packageName;
                if ((ps.pkgFlags & 1) == 0) {
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
                        this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.31
                            @Override // java.lang.Runnable
                            public void run() {
                                PackageManagerService.this.deletePackageX(packageName, -1L, userHandle, 0);
                            }
                        });
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createNewUser(int userId, String[] disallowedPackages) {
        synchronized (this.mInstallLock) {
            this.mSettings.createNewUserLI(this, this.mInstaller, userId, disallowedPackages);
        }
        synchronized (this.mPackages) {
            scheduleWritePackageRestrictionsLocked(userId);
            scheduleWritePackageListLocked(userId);
            applyFactoryDefaultBrowserLPw(userId);
            primeDomainVerificationsLPw(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNewUserCreated(int userId) {
        this.mDefaultPermissionPolicy.grantDefaultPermissions(userId);
        synchronized (this.mPackages) {
            if (this.mSettings.mPermissions.mPermissionReviewRequired) {
                this.mPermissionManager.updateAllPermissions(StorageManager.UUID_PRIVATE_INTERNAL, true, this.mPackages.values(), this.mPermissionCallback);
            }
        }
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
    public void deletePackageIfUnusedLPr(final String packageName) {
        PackageSetting ps = this.mSettings.mPackages.get(packageName);
        if (ps != null && !ps.isAnyInstalled(sUserManager.getUserIds())) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.pm.PackageManagerService.32
                @Override // java.lang.Runnable
                public void run() {
                    PackageManagerService.this.deletePackageX(packageName, -1L, 0, 2);
                }
            });
        }
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
            switch (what) {
                case 1:
                    callback.onCreated(args.argi1, (Bundle) args.arg2);
                    return;
                case 2:
                    callback.onStatusChanged(args.argi1, args.argi2, ((Long) args.arg3).longValue());
                    return;
                default:
                    return;
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

    /* loaded from: classes.dex */
    private static final class OnPermissionChangeListeners extends Handler {
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

        public String[] getNamesForUids(int[] uids) throws RemoteException {
            String[] results = PackageManagerService.this.getNamesForUids(uids);
            for (int i = results.length - 1; i >= 0; i--) {
                if (results[i] == null) {
                    results[i] = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
    }

    /* loaded from: classes.dex */
    private class PackageManagerInternalImpl extends PackageManagerInternal {
        private PackageManagerInternalImpl() {
        }

        public void updatePermissionFlagsTEMP(String permName, String packageName, int flagMask, int flagValues, int userId) {
            PackageManagerService.this.updatePermissionFlags(permName, packageName, flagMask, flagValues, userId);
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
                Object obj = PackageManagerService.this.mSettings.getUserIdLPr(appId);
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

        public PackageParser.Package getDisabledPackage(String packageName) {
            PackageParser.Package r2;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.getDisabledSystemPkgLPr(packageName);
                r2 = ps != null ? ps.pkg : null;
            }
            return r2;
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

        public void setVoiceInteractionPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setVoiceInteractionPackagesProvider(provider);
        }

        public void setSmsAppPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setSmsAppPackagesProvider(provider);
        }

        public void setDialerAppPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setDialerAppPackagesProvider(provider);
        }

        public void setSimCallManagerPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setSimCallManagerPackagesProvider(provider);
        }

        public void setUseOpenWifiAppPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setUseOpenWifiAppPackagesProvider(provider);
        }

        public void setSyncAdapterPackagesprovider(PackageManagerInternal.SyncAdapterPackagesProvider provider) {
            PackageManagerService.this.mDefaultPermissionPolicy.setSyncAdapterPackagesProvider(provider);
        }

        public void grantDefaultPermissionsToDefaultSmsApp(String packageName, int userId) {
            PackageManagerService.this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultSmsApp(packageName, userId);
        }

        public void grantDefaultPermissionsToDefaultDialerApp(String packageName, int userId) {
            synchronized (PackageManagerService.this.mPackages) {
                PackageManagerService.this.mSettings.setDefaultDialerPackageNameLPw(packageName, userId);
            }
            PackageManagerService.this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultDialerApp(packageName, userId);
        }

        public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
            PackageManagerService.this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultSimCallManager(packageName, userId);
        }

        public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
            PackageManagerService.this.mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultUseOpenWifiApp(packageName, userId);
        }

        public void setKeepUninstalledPackages(List<String> packageList) {
            Preconditions.checkNotNull(packageList);
            List<String> removedFromList = null;
            synchronized (PackageManagerService.this.mPackages) {
                try {
                    if (PackageManagerService.this.mKeepUninstalledPackages != null) {
                        int packagesCount = PackageManagerService.this.mKeepUninstalledPackages.size();
                        List<String> removedFromList2 = null;
                        for (int i = 0; i < packagesCount; i++) {
                            try {
                                String oldPackage = (String) PackageManagerService.this.mKeepUninstalledPackages.get(i);
                                if (packageList == null || !packageList.contains(oldPackage)) {
                                    if (removedFromList2 == null) {
                                        removedFromList2 = new ArrayList<>();
                                    }
                                    removedFromList2.add(oldPackage);
                                }
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        }
                        removedFromList = removedFromList2;
                    }
                    PackageManagerService.this.mKeepUninstalledPackages = new ArrayList(packageList);
                    if (removedFromList != null) {
                        int removedCount = removedFromList.size();
                        for (int i2 = 0; i2 < removedCount; i2++) {
                            PackageManagerService.this.deletePackageIfUnusedLPr(removedFromList.get(i2));
                        }
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            }
        }

        public boolean isPermissionsReviewRequired(String packageName, int userId) {
            boolean isPermissionsReviewRequired;
            synchronized (PackageManagerService.this.mPackages) {
                isPermissionsReviewRequired = PackageManagerService.this.mPermissionManager.isPermissionsReviewRequired(PackageManagerService.this.mPackages.get(packageName), userId);
            }
            return isPermissionsReviewRequired;
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

        public String getSuspendedDialogMessage(String suspendedPackage, int userId) {
            String str;
            synchronized (PackageManagerService.this.mPackages) {
                PackageSetting ps = PackageManagerService.this.mSettings.mPackages.get(suspendedPackage);
                str = ps != null ? ps.readUserState(userId).dialogMessage : null;
            }
            return str;
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

        public void grantRuntimePermission(String packageName, String permName, int userId, boolean overridePolicy) {
            PackageManagerService.this.mPermissionManager.grantRuntimePermission(permName, packageName, overridePolicy, Binder.getCallingUid(), userId, PackageManagerService.this.mPermissionCallback);
        }

        public void revokeRuntimePermission(String packageName, String permName, int userId, boolean overridePolicy) {
            PackageManagerService.this.mPermissionManager.revokeRuntimePermission(permName, packageName, overridePolicy, Binder.getCallingUid(), userId, PackageManagerService.this.mPermissionCallback);
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
                    try {
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
                    } catch (Throwable th) {
                        throw th;
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
                z = !PackageManagerService.this.filterAppAccessLPr(ps, callingUid, component, 0, userId);
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
            Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$0mnOIUh_i9NmZjdSl_ThmAKOygQ
                public final void runOrThrow() {
                    PackageManagerService.this.mDefaultPermissionPolicy.grantDefaultPermissionsToEnabledTelephonyDataServices(packageNames, userId);
                }
            });
        }
    }

    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(final String[] packageNames, final int userId) {
        enforceSystemOrPhoneCaller("revokeDefaultPermissionsFromDisabledTelephonyDataServices");
        synchronized (this.mPackages) {
            Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.pm.-$$Lambda$PackageManagerService$SqnyEUSufdap2rpMxFHMRiHeFV4
                public final void runOrThrow() {
                    PackageManagerService.this.mDefaultPermissionPolicy.revokeDefaultPermissionsFromDisabledTelephonyDataServices(packageNames, userId);
                }
            });
        }
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
        if (!ArrayUtils.contains(packagesDeclaringPermission, packageName)) {
            if (!throwIfPermNotDeclared) {
                Slog.e(TAG, "Need to declare android.permission.REQUEST_INSTALL_PACKAGES to call this api");
                return false;
            }
            throw new SecurityException("Need to declare android.permission.REQUEST_INSTALL_PACKAGES to call this api");
        } else if (sUserManager.hasUserRestriction("no_install_unknown_sources", userId) || this.mExternalSourcesPolicy == null) {
            return false;
        } else {
            int isTrusted = this.mExternalSourcesPolicy.getPackageTrustedToInstallApps(packageName, uid);
            return isTrusted == 0;
        }
    }

    public ComponentName getInstantAppResolverSettingsComponent() {
        return this.mInstantAppResolverSettingsComponent;
    }

    public ComponentName getInstantAppInstallerComponent() {
        if (getInstantAppPackageName(Binder.getCallingUid()) == null && this.mInstantAppInstallerActivity != null) {
            return this.mInstantAppInstallerActivity.getComponentName();
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
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                ArrayMap<String, PackageParser.Package> arrayMap3 = arrayMap;
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
                    int currentuserId = UserHandle.getUserId(userId);
                    ps.setNotLaunched(stop, currentuserId);
                    ps.setStopped(stop, currentuserId);
                    Slog.d(TAG, "check PKMS.setPackageStoppedStateLPw stop=" + stop + " currentuserId=" + currentuserId + " ps.getNotLaunched(currentuserId)=" + ps.getNotLaunched(currentuserId));
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
        if (mXuiAppInfoInnerList == null) {
            synchronized (xpAppInfo.class) {
                if (mXuiAppInfoInnerList == null) {
                    mXuiAppInfoInnerList = napaAppInfoLoad();
                }
            }
        } else {
            synchronized (mXuiAppInfoInnerList) {
                for (xpAppInfoInner infoInner : mXuiAppInfoInnerList) {
                    infoInner.appInfo.mXpAppIcon = xpPackageManagerService.get(this.mContext).getXpAppIcon(infoInner.appInfo.resId);
                    Slog.d(TAG, "getXpAppPackageList infoInner.appInfo.resId=" + infoInner.appInfo.resId + " infoInner.appInfo.pkgName=" + infoInner.appInfo.pkgName + " ,icon by resId infoInner.appInfo.mXpAppIcon=" + infoInner.appInfo.mXpAppIcon);
                    if (infoInner.appInfo.mXpAppIcon == null) {
                        infoInner.appInfo.mXpAppIcon = xpPackageManagerService.get(this.mContext).getXpAppIcon("default");
                    }
                }
            }
        }
        dispatchNapaAppInfo(screenId);
        Slog.d(TAG, "getXpAppPackageList screenId=" + screenId + " ,getXuiAppInfoListMapping(screenId)=" + getXuiAppInfoListMapping(screenId));
        return getXuiAppInfoListMapping(screenId);
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
        synchronized (mXuiAppInfoInnerList) {
            List<xpAppInfo> mXuiAppInfoList2 = new ArrayList<>();
            for (xpAppInfoInner infoInner : mXuiAppInfoInnerList) {
                if ((infoInner.appInfo.supportScreenId == -1 || infoInner.appInfo.supportScreenId == screenId) && infoInner.display) {
                    mXuiAppInfoList2.add(infoInner.appInfo);
                }
            }
            Slog.w(TAG, "dispatchNapaAppInfo screenId=" + screenId + " mXuiAppInfoList=" + mXuiAppInfoList2);
            mXuiAppInfoListMapping.put(Integer.valueOf(screenId), mXuiAppInfoList2);
        }
    }

    public static List<xpAppInfo> getXuiAppInfoListMapping(int screenId) {
        Slog.w(TAG, "getXuiAppInfoListMapping screenId" + screenId);
        if (mXuiAppInfoListMapping.containsKey(Integer.valueOf(screenId))) {
            return mXuiAppInfoListMapping.get(Integer.valueOf(screenId));
        }
        return mXuiAppInfoListMapping.get(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String getResourceTitleName(String nameId) {
        String name = LocaleStrings.getInstance().getString(nameId);
        return name;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class xpAppInfoInner {
        public xpAppInfo appInfo;
        public String defaultTitle;
        public boolean display;

        private xpAppInfoInner() {
        }
    }

    private xpAppInfoInner parseNapaAppInfo(String data) {
        if (data == null || data.startsWith(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR) || data.length() < 8) {
            return null;
        }
        String[] params = data.split("#%");
        if (params == null || params.length < 7) {
            Slog.w(TAG, "invalid data:" + data);
            return null;
        }
        boolean display = false;
        if (!params[0].startsWith("CFC:")) {
            if ("DIS:0".equals(params[0])) {
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
        } else {
            String suffix = params[0].split(":")[1];
            if (!"0".equals(suffix)) {
                String[] props = suffix.split(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK);
                int mPropValue = 0;
                for (String str : props) {
                    mPropValue += FeatureOption.hasFeature(str) ? 1 : 0;
                }
                Slog.i(TAG, "app info suffix=" + suffix + " props[0]=" + props[0] + "props.length=" + props.length + "mPropValue=" + mPropValue);
                boolean support = props != null && mPropValue == props.length;
                display = support;
                if (!support) {
                    Slog.w(TAG, "app info not support,data=" + data);
                }
            } else {
                display = true;
            }
        }
        xpAppInfoInner infoinner = new xpAppInfoInner();
        infoinner.display = display;
        infoinner.appInfo = new xpAppInfo();
        infoinner.appInfo.mLaunchMode = 0;
        infoinner.appInfo.resId = params[1];
        infoinner.appInfo.mXpAppTitle = getResourceTitleName(params[1]);
        infoinner.defaultTitle = params[2];
        if (infoinner.appInfo.mXpAppTitle == null) {
            infoinner.appInfo.mXpAppTitle = infoinner.defaultTitle;
            Slog.w(TAG, "app info undefined mXpAppTitle params[1]=" + params[1] + ",set default:" + infoinner.defaultTitle);
        }
        infoinner.appInfo.mXpAppId = params[3];
        infoinner.appInfo.mXpAppPage = params[4];
        if (params.length > 5) {
            if ("0".equals(params[5])) {
                infoinner.appInfo.mLaunchMode = 0;
                infoinner.appInfo.mLaunchParam = null;
            } else {
                String[] launchParams = params[5].split(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK, -1);
                infoinner.appInfo.mLaunchMode = Integer.parseInt(launchParams[0]);
                infoinner.appInfo.mLaunchParam = launchParams[1];
            }
            infoinner.appInfo.supportScreenId = Integer.valueOf(params[6]).intValue();
            Slog.w(TAG, "parseNapaAppInfo params[6] value is =" + Integer.valueOf(params[6]).intValue() + " infoinner.appInfo.supportScreenId=" + infoinner.appInfo.supportScreenId);
        }
        infoinner.appInfo.mXpAppIcon = xpPackageManagerService.get(this.mContext).getXpAppIcon(infoinner.appInfo.resId);
        return infoinner;
    }

    public Bitmap getXpAppIcon(String pkgName) {
        return xpPackageManagerService.get(this.mContext).getXpAppIcon(pkgName);
    }
}
