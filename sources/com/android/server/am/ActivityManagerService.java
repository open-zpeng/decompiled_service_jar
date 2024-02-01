package com.android.server.am;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.app.ApplicationErrorReport;
import android.app.ContentProviderHolder;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IAssistDataReceiver;
import android.app.IInstrumentationWatcher;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUidObserver;
import android.app.IUserSwitchObserver;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProcessMemoryState;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.WindowConfiguration;
import android.app.backup.IBackupManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityPresentationInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.PathPermission;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal;
import android.net.NetworkPolicyManager;
import android.net.Uri;
import android.os.Binder;
import android.os.BinderProxy;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IPermissionController;
import android.os.IProcessInfoService;
import android.os.IProgressListener;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.storage.IStorageManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.sysprop.VoldProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;
import android.view.IRecentsAnimationRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.DumpHeapActivity;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.TriFunction;
import com.android.server.AlarmManagerInternal;
import com.android.server.BatteryService;
import com.android.server.DeviceIdleController;
import com.android.server.DisplayThread;
import com.android.server.GraphicsStatsService;
import com.android.server.IntentResolver;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.NetworkManagementInternal;
import com.android.server.PackageWatchdog;
import com.android.server.RescueParty;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.ThreadPriorityBooster;
import com.android.server.UiModeManagerService;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.am.ActiveServices;
import com.android.server.am.UidRecord;
import com.android.server.appop.AppOpsService;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.display.color.DisplayTransformManager;
import com.android.server.firewall.IntentFirewall;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.job.JobSchedulerShellCommand;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageManagerService;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.uri.GrantUri;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.PriorityDump;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.ActivityTaskManagerDebugConfig;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowProcessController;
import com.xiaopeng.app.xpDialogInfo;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.policy.xpBootManagerPolicy;
import com.xiaopeng.server.xpSystemServer;
import com.xiaopeng.util.FeatureFactory;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;

@SuppressLint({"all"})
/* loaded from: classes.dex */
public class ActivityManagerService extends IActivityManager.Stub implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback {
    public static final String ACTION_TRIGGER_IDLE = "com.android.server.ACTION_TRIGGER_IDLE";
    private static final String ACTIVITY_START_PSS_DEFER_CONFIG = "activity_start_pss_defer";
    public static final String ANR_TRACE_DIR = "/data/anr";
    static final long BATTERY_STATS_TIME = 1800000;
    private static final int BINDER_PROXY_HIGH_WATERMARK = 6000;
    private static final int BINDER_PROXY_LOW_WATERMARK = 5500;
    static final int BROADCAST_BG_TIMEOUT = 60000;
    static final int BROADCAST_FG_TIMEOUT = 10000;
    static final int CHECK_EXCESSIVE_POWER_USE_MSG = 27;
    static final int CLEAR_DNS_CACHE_MSG = 28;
    static final int COLLECT_PSS_BG_MSG = 1;
    static final int CONTENT_PROVIDER_PUBLISH_TIMEOUT = 10000;
    static final int CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG = 57;
    static final int CONTENT_PROVIDER_WAIT_TIMEOUT = 20000;
    static final int DEFER_PSS_MSG = 2;
    static final int DELETE_DUMPHEAP_MSG = 51;
    static final int DISPATCH_OOM_ADJ_OBSERVER_MSG = 70;
    static final int DISPATCH_PROCESSES_CHANGED_UI_MSG = 31;
    static final int DISPATCH_PROCESS_DIED_UI_MSG = 32;
    static final int DISPATCH_UIDS_CHANGED_UI_MSG = 53;
    static final int DROPBOX_MAX_SIZE = 196608;
    static final int FIRST_BROADCAST_QUEUE_MSG = 200;
    static final int GC_BACKGROUND_PROCESSES_MSG = 5;
    static final int HANDLE_TRUST_STORAGE_UPDATE_MSG = 63;
    static final int IDLE_UIDS_MSG = 58;
    private static final String INTENT_REMOTE_BUGREPORT_FINISHED = "com.android.internal.intent.action.REMOTE_BUGREPORT_FINISHED";
    private static final int JAVA_DUMP_MINIMUM_SIZE = 100;
    static final int KILL_APPLICATION_MSG = 22;
    static final int KILL_APP_ZYGOTE_DELAY_MS = 5000;
    static final int KILL_APP_ZYGOTE_MSG = 71;
    private static final int KSM_SHARED = 0;
    private static final int KSM_SHARING = 1;
    private static final int KSM_UNSHARED = 2;
    private static final int KSM_VOLATILE = 3;
    private static final int MAX_BUGREPORT_TITLE_SIZE = 50;
    private static final int MAX_DUP_SUPPRESSED_STACKS = 5000;
    private static final int MAX_RECEIVERS_ALLOWED_PER_APP = 1000;
    private static final int MEMINFO_COMPACT_VERSION = 1;
    private static final int MINIMUM_MEMORY_GROWTH_THRESHOLD = 10000;
    static final long MONITOR_CPU_MAX_TIME = 268435455;
    static final long MONITOR_CPU_MIN_TIME = 5000;
    static final boolean MONITOR_CPU_USAGE = true;
    static final boolean MONITOR_THREAD_CPU_USAGE = false;
    private static final int NATIVE_DUMP_TIMEOUT_MS = 2000;
    private static final long NETWORK_ACCESS_TIMEOUT_DEFAULT_MS = 200;
    @VisibleForTesting
    static final int NETWORK_STATE_BLOCK = 1;
    @VisibleForTesting
    static final int NETWORK_STATE_NO_CHANGE = 0;
    @VisibleForTesting
    static final int NETWORK_STATE_UNBLOCK = 2;
    static final int NOTIFY_CLEARTEXT_NETWORK_MSG = 49;
    static final int PERSISTENT_MASK = 9;
    static final int POST_DUMP_HEAP_NOTIFICATION_MSG = 50;
    static final int PROC_START_TIMEOUT = 20000;
    static final int PROC_START_TIMEOUT_MSG = 20;
    static final int PROC_START_TIMEOUT_WITH_WRAPPER = 1200000;
    static final int PUSH_TEMP_WHITELIST_UI_MSG = 68;
    static final int REPORT_MEM_USAGE_MSG = 33;
    static final int RESERVED_BYTES_PER_LOGCAT_LINE = 100;
    static final int SERVICE_FOREGROUND_CRASH_MSG = 69;
    static final int SERVICE_FOREGROUND_TIMEOUT_MSG = 66;
    static final String SERVICE_RECORD_KEY = "servicerecord";
    static final int SERVICE_TIMEOUT_MSG = 12;
    static final int SHOW_ERROR_UI_MSG = 1;
    static final int SHOW_NOT_RESPONDING_UI_MSG = 2;
    static final int SHOW_STRICT_MODE_VIOLATION_UI_MSG = 26;
    static final int SHUTDOWN_UI_AUTOMATION_CONNECTION_MSG = 56;
    private static final int SLOW_UID_OBSERVER_THRESHOLD_MS = 20;
    public static final int STOCK_PM_FLAGS = 1024;
    static final int STOP_DEFERRING_PSS_MSG = 3;
    static final String SYSTEM_DEBUGGABLE = "ro.debuggable";
    private static final String SYSTEM_PROPERTY_DEVICE_PROVISIONED = "persist.sys.device_provisioned";
    static final String TAG = "ActivityManager";
    static final String TAG_BACKUP = "ActivityManager";
    private static final String TAG_BROADCAST = "ActivityManager";
    private static final String TAG_CLEANUP = "ActivityManager";
    private static final String TAG_CONFIGURATION = "ActivityManager";
    private static final String TAG_LOCKTASK = "ActivityManager";
    static final String TAG_LRU = "ActivityManager";
    private static final String TAG_MU = "ActivityManager_MU";
    private static final String TAG_NETWORK = "ActivityManager_Network";
    static final String TAG_OOM_ADJ = "ActivityManager";
    private static final String TAG_POWER = "ActivityManager";
    static final String TAG_PROCESSES = "ActivityManager";
    static final String TAG_PROCESS_OBSERVERS = "ActivityManager";
    private static final String TAG_PROVIDER = "ActivityManager";
    static final String TAG_PSS = "ActivityManager";
    private static final String TAG_SERVICE = "ActivityManager";
    private static final String TAG_SWITCH = "ActivityManager";
    static final String TAG_UID_OBSERVERS = "ActivityManager";
    public static final int TOP_APP_PRIORITY_BOOST = -10;
    static final boolean TRACK_PROCSTATS_ASSOCIATIONS = true;
    static final int UPDATE_HTTP_PROXY_MSG = 29;
    static final int UPDATE_TIME_PREFERENCE_MSG = 41;
    static final int UPDATE_TIME_ZONE = 13;
    static final boolean VALIDATE_UID_STATES = true;
    static final int WAIT_FOR_DEBUGGER_UI_MSG = 6;
    private static String mInstallingPackage;
    @GuardedBy({"ActivityManagerService.class"})
    private static SimpleDateFormat sAnrFileDateFormat;
    final ArrayList<ActiveInstrumentation> mActiveInstrumentation;
    ProcessChangeItem[] mActiveProcessChanges;
    UidRecord.ChangeItem[] mActiveUidChanges;
    private final ActivityMetricsLaunchObserver mActivityLaunchObserver;
    private final AtomicInteger mActivityStartingNesting;
    @VisibleForTesting
    public ActivityTaskManagerService mActivityTaskManager;
    boolean mAllowLowerMemLevel;
    ArrayMap<String, PackageAssociationInfo> mAllowedAssociations;
    private final HashSet<Integer> mAlreadyLoggedViolatedStacks;
    boolean mAlwaysFinishActivities;
    private Map<String, String> mAppAgentMap;
    ArrayMap<String, IBinder> mAppBindArgs;
    final AppErrors mAppErrors;
    final AppOpsService mAppOpsService;
    final SparseArray<ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>>> mAssociations;
    @VisibleForTesting
    public ActivityTaskManagerInternal mAtmInternal;
    final ArrayList<ProcessChangeItem> mAvailProcessChanges;
    final ArrayList<UidRecord.ChangeItem> mAvailUidChanges;
    int[] mBackgroundAppIdWhitelist;
    ArraySet<String> mBackgroundLaunchBroadcasts;
    @GuardedBy({"this"})
    final SparseArray<BackupRecord> mBackupTargets;
    final XpBatteryStatsService mBatteryStatsService;
    BroadcastQueue mBgBroadcastQueue;
    final Handler mBgHandler;
    private boolean mBinderTransactionTrackingEnabled;
    @GuardedBy({"this"})
    boolean mBootAnimationComplete;
    int mBootPhase;
    volatile boolean mBooted;
    volatile boolean mBooting;
    final BroadcastQueue[] mBroadcastQueues;
    @GuardedBy({"this"})
    boolean mCallFinishBooting;
    ActivityManagerConstants mConstants;
    ContentCaptureManagerInternal mContentCaptureService;
    final Context mContext;
    CoreSettingsObserver mCoreSettingsObserver;
    BroadcastStats mCurBroadcastStats;
    OomAdjObserver mCurOomAdjObserver;
    int mCurOomAdjUid;
    private String mCurResumedPackage;
    private int mCurResumedUid;
    String mDebugApp;
    boolean mDebugTransient;
    DevelopmentSettingsObserver mDevelopmentSettingsObserver;
    int[] mDeviceIdleExceptIdleWhitelist;
    int[] mDeviceIdleTempWhitelist;
    int[] mDeviceIdleWhitelist;
    String mDeviceOwnerName;
    boolean mEnableOffloadQueue;
    final int mFactoryTest;
    BroadcastQueue mFgBroadcastQueue;
    boolean mForceBackgroundCheck;
    final ProcessMap<ArrayList<ProcessRecord>> mForegroundPackages;
    boolean mFullPssPending;
    final MainHandler mHandler;
    @VisibleForTesting
    public final ServiceThread mHandlerThread;
    final HiddenApiSettings mHiddenApiBlacklist;
    final SparseArray<ImportanceToken> mImportantProcesses;
    private final Injector mInjector;
    private Installer mInstaller;
    final InstrumentationReporter mInstrumentationReporter;
    public final IntentFirewall mIntentFirewall;
    ArrayMap<String, IBinder> mIsolatedAppBindArgs;
    BroadcastStats mLastBroadcastStats;
    final AtomicLong mLastCpuTime;
    long mLastFullPssTime;
    long mLastIdleTime;
    long mLastMemUsageReportTime;
    int mLastMemoryLevel;
    int mLastNumProcesses;
    long mLastPowerCheckUptime;
    long mLastWriteTime;
    final ArrayList<ContentProviderRecord> mLaunchingProviders;
    private ParcelFileDescriptor[] mLifeMonitorFds;
    DeviceIdleController.LocalService mLocalDeviceIdleController;
    PowerManagerInternal mLocalPowerManager;
    final LowMemDetector mLowMemDetector;
    long mLowRamStartTime;
    long mLowRamTimeSinceLastIdle;
    String mMemWatchDumpFile;
    int mMemWatchDumpPid;
    String mMemWatchDumpProcName;
    int mMemWatchDumpUid;
    private boolean mMemWatchIsUserInitiated;
    final ProcessMap<Pair<Long, String>> mMemWatchProcesses;
    String mNativeDebuggingApp;
    BroadcastQueue mOffloadBroadcastQueue;
    volatile boolean mOnBattery;
    public OomAdjProfiler mOomAdjProfiler;
    OomAdjuster mOomAdjuster;
    String mOrigDebugApp;
    boolean mOrigWaitForDebugger;
    PackageManagerInternal mPackageManagerInt;
    final PackageWatchdog mPackageWatchdog;
    @VisibleForTesting
    public final PendingIntentController mPendingIntentController;
    final ArrayList<ProcessChangeItem> mPendingProcessChanges;
    final ArrayList<ProcessRecord> mPendingPssProcesses;
    final PendingTempWhitelists mPendingTempWhitelist;
    final ArrayList<UidRecord.ChangeItem> mPendingUidChanges;
    final ArrayList<ProcessRecord> mPersistentStartingProcesses;
    final PidMap mPidsSelfLocked;
    private final PriorityDump.PriorityDumper mPriorityDumper;
    final Handler mProcStartHandler;
    final ServiceThread mProcStartHandlerThread;
    final CountDownLatch mProcessCpuInitLatch;
    final AtomicBoolean mProcessCpuMutexFree;
    final Thread mProcessCpuThread;
    final ProcessCpuTracker mProcessCpuTracker;
    final ProcessList mProcessList;
    final RemoteCallbackList<IProcessObserver> mProcessObservers;
    private final long[] mProcessStateStatsLongs;
    final ProcessStatsService mProcessStats;
    final ArrayList<ProcessRecord> mProcessesOnHold;
    volatile boolean mProcessesReady;
    final ArrayList<ProcessRecord> mProcessesToGc;
    final ProfileData mProfileData;
    int mProfileType;
    final ProviderMap mProviderMap;
    private volatile long mPssDeferralTime;
    private final DeviceConfig.OnPropertiesChangedListener mPssDelayConfigListener;
    final IntentResolver<BroadcastFilter, BroadcastFilter> mReceiverResolver;
    final HashMap<IBinder, ReceiverList> mRegisteredReceivers;
    boolean mSafeMode;
    final ActiveServices mServices;
    final SparseArray<ArrayMap<String, ArrayList<Intent>>> mStickyBroadcasts;
    final StringBuilder mStringBuilder;
    boolean mSystemProvidersInstalled;
    volatile boolean mSystemReady;
    SystemServiceManager mSystemServiceManager;
    final ActivityThread mSystemThread;
    boolean mTestPssMode;
    String mTrackAllocationApp;
    boolean mTrackingAssociations;
    @VisibleForTesting
    public UriGrantsManagerInternal mUgmInternal;
    final Context mUiContext;
    final Handler mUiHandler;
    int mUidChangeDispatchCount;
    final RemoteCallbackList<IUidObserver> mUidObservers;
    UsageStatsManagerInternal mUsageStatsService;
    boolean mUseFifoUiScheduling;
    final UserController mUserController;
    private boolean mUserIsMonkey;
    final ActiveUids mValidateUids;
    boolean mWaitForDebugger;
    @VisibleForTesting
    long mWaitForNetworkTimeoutMs;
    int mWakefulness;
    @VisibleForTesting
    public WindowManagerService mWindowManager;
    private volatile int mWtfClusterCount;
    private volatile long mWtfClusterStart;
    public static final int MY_PID = Process.myPid();
    static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static ThreadPriorityBooster sThreadPriorityBooster = new ThreadPriorityBooster(-2, 6);
    private static final ThreadLocal<Identity> sCallerIdentity = new ThreadLocal<>();
    private static String sTheRealBuildSerial = UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
    static final HostingRecord sNullHostingRecord = new HostingRecord(null);
    private static final int[] PROCESS_STATE_STATS_FORMAT = {32, 544, 10272};
    static final long[] DUMP_MEM_BUCKETS = {5120, 7168, 10240, 15360, 20480, 30720, 40960, 81920, 122880, 163840, 204800, 256000, 307200, 358400, 409600, 512000, 614400, 819200, 1048576, 2097152, 5242880, 10485760, 20971520};
    static final int[] DUMP_MEM_OOM_ADJ = {JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE, -900, -800, -700, 0, 100, 200, 250, DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR, 400, SystemService.PHASE_SYSTEM_SERVICES_READY, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, 700, 800, 900};
    static final String[] DUMP_MEM_OOM_LABEL = {"Native", "System", "Persistent", "Persistent Service", "Foreground", "Visible", "Perceptible", "Perceptible Low", "Heavy Weight", "Backup", "A Services", "Home", "Previous", "B Services", "Cached"};
    static final String[] DUMP_MEM_OOM_COMPACT_LABEL = {"native", "sys", "pers", "persvc", "fore", "vis", "percept", "perceptl", "heavy", BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD, "servicea", "home", "prev", "serviceb", "cached"};

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface OomAdjObserver {
        void onOomAdjMessage(String str);
    }

    static /* synthetic */ void access$400(ActivityManagerService x0, ProcessRecord x1) {
        x0.processStartTimedOutLocked(x1);
    }

    static /* synthetic */ void access$500(ActivityManagerService x0, ProcessRecord x1) {
        x0.processContentProviderPublishTimedOutLocked(x1);
    }

    static /* synthetic */ boolean access$600(ActivityManagerService x0) {
        return x0.mMemWatchIsUserInitiated;
    }

    static /* synthetic */ boolean access$702(ActivityManagerService x0, boolean x1) {
        x0.mUserIsMonkey = x1;
        return x1;
    }

    BroadcastQueue broadcastQueueForIntent(Intent intent) {
        if (isOnOffloadQueue(intent.getFlags())) {
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST_BACKGROUND) {
                Slog.i("ActivityManager", "Broadcast intent " + intent + " on offload queue");
            }
            return this.mOffloadBroadcastQueue;
        }
        boolean isFg = (intent.getFlags() & 268435456) != 0;
        if (ActivityManagerDebugConfig.DEBUG_BROADCAST_BACKGROUND) {
            StringBuilder sb = new StringBuilder();
            sb.append("Broadcast intent ");
            sb.append(intent);
            sb.append(" on ");
            sb.append(isFg ? "foreground" : "background");
            sb.append(" queue");
            Slog.i("ActivityManager", sb.toString());
        }
        return isFg ? this.mFgBroadcastQueue : this.mBgBroadcastQueue;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void boostPriorityForLockedSection() {
        sThreadPriorityBooster.boost();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void resetPriorityAfterLockedSection() {
        sThreadPriorityBooster.reset();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class PackageAssociationInfo {
        private final ArraySet<String> mAllowedPackageAssociations;
        private boolean mIsDebuggable;
        private final String mSourcePackage;

        PackageAssociationInfo(String sourcePackage, ArraySet<String> allowedPackages, boolean isDebuggable) {
            this.mSourcePackage = sourcePackage;
            this.mAllowedPackageAssociations = allowedPackages;
            this.mIsDebuggable = isDebuggable;
        }

        boolean isPackageAssociationAllowed(String targetPackage) {
            return this.mIsDebuggable || this.mAllowedPackageAssociations.contains(targetPackage);
        }

        boolean isDebuggable() {
            return this.mIsDebuggable;
        }

        void setDebuggable(boolean isDebuggable) {
            this.mIsDebuggable = isDebuggable;
        }

        ArraySet<String> getAllowedPackageAssociations() {
            return this.mAllowedPackageAssociations;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class PidMap {
        private final SparseArray<ProcessRecord> mPidMap = new SparseArray<>();

        PidMap() {
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void put(ProcessRecord app) {
            synchronized (this) {
                this.mPidMap.put(app.pid, app);
            }
            ActivityManagerService.this.mAtmInternal.onProcessMapped(app.pid, app.getWindowProcessController());
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void remove(ProcessRecord app) {
            boolean removed = false;
            synchronized (this) {
                ProcessRecord existingApp = this.mPidMap.get(app.pid);
                if (existingApp != null && existingApp.startSeq == app.startSeq) {
                    this.mPidMap.remove(app.pid);
                    removed = true;
                }
            }
            if (removed) {
                ActivityManagerService.this.mAtmInternal.onProcessUnMapped(app.pid);
            }
        }

        boolean removeIfNoThread(ProcessRecord app) {
            boolean removed = false;
            synchronized (this) {
                ProcessRecord existingApp = get(app.pid);
                if (existingApp != null && existingApp.startSeq == app.startSeq && app.thread == null) {
                    this.mPidMap.remove(app.pid);
                    removed = true;
                }
            }
            if (removed) {
                ActivityManagerService.this.mAtmInternal.onProcessUnMapped(app.pid);
            }
            return removed;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public ProcessRecord get(int pid) {
            return this.mPidMap.get(pid);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public int size() {
            return this.mPidMap.size();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public ProcessRecord valueAt(int index) {
            return this.mPidMap.valueAt(index);
        }

        int keyAt(int index) {
            return this.mPidMap.keyAt(index);
        }

        int indexOfKey(int key) {
            return this.mPidMap.indexOfKey(key);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public abstract class ImportanceToken implements IBinder.DeathRecipient {
        final int pid;
        final String reason;
        final IBinder token;

        ImportanceToken(int _pid, IBinder _token, String _reason) {
            this.pid = _pid;
            this.token = _token;
            this.reason = _reason;
        }

        public String toString() {
            return "ImportanceToken { " + Integer.toHexString(System.identityHashCode(this)) + " " + this.reason + " " + this.pid + " " + this.token + " }";
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            long pToken = proto.start(fieldId);
            proto.write(1120986464257L, this.pid);
            IBinder iBinder = this.token;
            if (iBinder != null) {
                proto.write(1138166333442L, iBinder.toString());
            }
            proto.write(1138166333443L, this.reason);
            proto.end(pToken);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class Association {
        int mCount;
        long mLastStateUptime;
        int mNesting;
        final String mSourceProcess;
        final int mSourceUid;
        long mStartTime;
        final ComponentName mTargetComponent;
        final String mTargetProcess;
        final int mTargetUid;
        long mTime;
        int mLastState = 22;
        long[] mStateTimes = new long[22];

        Association(int sourceUid, String sourceProcess, int targetUid, ComponentName targetComponent, String targetProcess) {
            this.mSourceUid = sourceUid;
            this.mSourceProcess = sourceProcess;
            this.mTargetUid = targetUid;
            this.mTargetComponent = targetComponent;
            this.mTargetProcess = targetProcess;
        }
    }

    /* loaded from: classes.dex */
    private final class DevelopmentSettingsObserver extends ContentObserver {
        private final ComponentName mBugreportStorageProvider;
        private final Uri mUri;

        public DevelopmentSettingsObserver() {
            super(ActivityManagerService.this.mHandler);
            this.mUri = Settings.Global.getUriFor("development_settings_enabled");
            this.mBugreportStorageProvider = new ComponentName("com.android.shell", "com.android.shell.BugreportStorageProvider");
            ActivityManagerService.this.mContext.getContentResolver().registerContentObserver(this.mUri, false, this, -1);
            onChange();
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (this.mUri.equals(uri)) {
                onChange();
            }
        }

        public void onChange() {
            boolean enabled = Settings.Global.getInt(ActivityManagerService.this.mContext.getContentResolver(), "development_settings_enabled", Build.IS_ENG ? 1 : 0) != 0;
            ActivityManagerService.this.mContext.getPackageManager().setComponentEnabledSetting(this.mBugreportStorageProvider, enabled ? 1 : 0, 0);
        }
    }

    /* loaded from: classes.dex */
    private class Identity {
        public final int pid;
        public final IBinder token;
        public final int uid;

        Identity(IBinder _token, int _pid, int _uid) {
            this.token = _token;
            this.pid = _pid;
            this.uid = _uid;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class PendingTempWhitelist {
        final long duration;
        final String tag;
        final int targetUid;

        PendingTempWhitelist(int _targetUid, long _duration, String _tag) {
            this.targetUid = _targetUid;
            this.duration = _duration;
            this.tag = _tag;
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1120986464257L, this.targetUid);
            proto.write(1112396529666L, this.duration);
            proto.write(1138166333443L, this.tag);
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ProfileData {
        private String mProfileApp = null;
        private ProcessRecord mProfileProc = null;
        private ProfilerInfo mProfilerInfo = null;

        ProfileData() {
        }

        void setProfileApp(String profileApp) {
            this.mProfileApp = profileApp;
            if (ActivityManagerService.this.mAtmInternal != null) {
                ActivityManagerService.this.mAtmInternal.setProfileApp(profileApp);
            }
        }

        String getProfileApp() {
            return this.mProfileApp;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void setProfileProc(ProcessRecord profileProc) {
            this.mProfileProc = profileProc;
            if (ActivityManagerService.this.mAtmInternal != null) {
                ActivityManagerService.this.mAtmInternal.setProfileProc(profileProc == null ? null : profileProc.getWindowProcessController());
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public ProcessRecord getProfileProc() {
            return this.mProfileProc;
        }

        void setProfilerInfo(ProfilerInfo profilerInfo) {
            this.mProfilerInfo = profilerInfo;
            if (ActivityManagerService.this.mAtmInternal != null) {
                ActivityManagerService.this.mAtmInternal.setProfilerInfo(profilerInfo);
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public ProfilerInfo getProfilerInfo() {
            return this.mProfilerInfo;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class ProcessChangeItem {
        static final int CHANGE_ACTIVITIES = 1;
        static final int CHANGE_FOREGROUND_SERVICES = 2;
        int changes;
        boolean foregroundActivities;
        int foregroundServiceTypes;
        int pid;
        int processState;
        int uid;

        ProcessChangeItem() {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class UidObserverRegistration {
        private static int[] ORIG_ENUMS = {4, 8, 2, 1};
        private static int[] PROTO_ENUMS = {3, 4, 2, 1};
        final int cutpoint;
        final SparseIntArray lastProcStates;
        int mMaxDispatchTime;
        int mSlowDispatchCount;
        final String pkg;
        final int uid;
        final int which;

        UidObserverRegistration(int _uid, String _pkg, int _which, int _cutpoint) {
            this.uid = _uid;
            this.pkg = _pkg;
            this.which = _which;
            this.cutpoint = _cutpoint;
            if (this.cutpoint >= 0) {
                this.lastProcStates = new SparseIntArray();
            } else {
                this.lastProcStates = null;
            }
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1120986464257L, this.uid);
            proto.write(1138166333442L, this.pkg);
            ProtoUtils.writeBitWiseFlagsToProtoEnum(proto, 2259152797699L, this.which, ORIG_ENUMS, PROTO_ENUMS);
            proto.write(1120986464260L, this.cutpoint);
            SparseIntArray sparseIntArray = this.lastProcStates;
            if (sparseIntArray != null) {
                int NI = sparseIntArray.size();
                for (int i = 0; i < NI; i++) {
                    long pToken = proto.start(2246267895813L);
                    proto.write(1120986464257L, this.lastProcStates.keyAt(i));
                    proto.write(1120986464258L, this.lastProcStates.valueAt(i));
                    proto.end(pToken);
                }
            }
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AppDeathRecipient implements IBinder.DeathRecipient {
        final ProcessRecord mApp;
        final IApplicationThread mAppThread;
        final int mPid;

        AppDeathRecipient(ProcessRecord app, int pid, IApplicationThread thread) {
            if (ActivityManagerDebugConfig.DEBUG_ALL) {
                Slog.v("ActivityManager", "New death recipient " + this + " for thread " + thread.asBinder());
            }
            this.mApp = app;
            this.mPid = pid;
            this.mAppThread = thread;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (ActivityManagerDebugConfig.DEBUG_ALL) {
                Slog.v("ActivityManager", "Death received in " + this + " for thread " + this.mAppThread.asBinder());
            }
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.appDiedLocked(this.mApp, this.mPid, this.mAppThread, true);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class UiHandler extends Handler {
        public UiHandler() {
            super(UiThread.get().getLooper(), null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                ActivityManagerService.this.mAppErrors.handleShowAppErrorUi(msg);
                ActivityManagerService.this.ensureBootCompleted();
            } else if (i == 2) {
                ActivityManagerService.this.mAppErrors.handleShowAnrUi(msg);
                ActivityManagerService.this.ensureBootCompleted();
            } else if (i == 6) {
                synchronized (ActivityManagerService.this) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        ProcessRecord app = (ProcessRecord) msg.obj;
                        if (msg.arg1 != 0) {
                            if (!app.waitedForDebugger) {
                                Dialog d = new AppWaitingForDebuggerDialog(ActivityManagerService.this, ActivityManagerService.this.mUiContext, app);
                                app.waitDialog = d;
                                app.waitedForDebugger = true;
                                d.show();
                            }
                        } else if (app.waitDialog != null) {
                            app.waitDialog.dismiss();
                            app.waitDialog = null;
                        }
                    } finally {
                    }
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
            } else if (i != ActivityManagerService.SHOW_STRICT_MODE_VIOLATION_UI_MSG) {
                if (i == 53) {
                    ActivityManagerService.this.dispatchUidsChanged();
                } else if (i == 68) {
                    ActivityManagerService.this.pushTempWhitelist();
                } else if (i == 70) {
                    ActivityManagerService.this.dispatchOomAdjObserver((String) msg.obj);
                } else if (i == 31) {
                    ActivityManagerService.this.dispatchProcessesChanged();
                } else if (i == 32) {
                    int pid = msg.arg1;
                    int uid = msg.arg2;
                    ActivityManagerService.this.dispatchProcessDied(pid, uid);
                }
            } else {
                HashMap<String, Object> data = (HashMap) msg.obj;
                synchronized (ActivityManagerService.this) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        ProcessRecord proc = (ProcessRecord) data.get("app");
                        if (proc == null) {
                            Slog.e("ActivityManager", "App not found when showing strict mode dialog.");
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        } else if (proc.crashDialog != null) {
                            Slog.e("ActivityManager", "App already has strict mode dialog: " + proc);
                        } else {
                            AppErrorResult res = (AppErrorResult) data.get("result");
                            if (ActivityManagerService.this.mAtmInternal.showStrictModeViolationDialog()) {
                                Dialog d2 = new StrictModeViolationDialog(ActivityManagerService.this.mUiContext, ActivityManagerService.this, res, proc);
                                d2.show();
                                proc.crashDialog = d2;
                            } else {
                                res.set(0);
                            }
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            ActivityManagerService.this.ensureBootCompleted();
                        }
                    } finally {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper, null, true);
        }

        /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
            jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:96:0x0216
            	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
            	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
            	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
            */
        @Override // android.os.Handler
        public void handleMessage(android.os.Message r25) {
            /*
                Method dump skipped, instructions count: 1056
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.MainHandler.handleMessage(android.os.Message):void");
        }
    }

    /* renamed from: com.android.server.am.ActivityManagerService$5  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass5 extends Handler {
        AnonymousClass5(Looper x0) {
            super(x0);
        }

        /* JADX WARN: Removed duplicated region for block: B:128:0x02a0 A[Catch: all -> 0x030a, TRY_LEAVE, TryCatch #4 {all -> 0x030a, blocks: (B:126:0x0297, B:128:0x02a0, B:133:0x02bd, B:138:0x02c9), top: B:187:0x0297 }] */
        /* JADX WARN: Removed duplicated region for block: B:148:0x0300  */
        /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:206:? -> B:159:0x031a). Please submit an issue!!! */
        @Override // android.os.Handler
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void handleMessage(android.os.Message r45) {
            /*
                Method dump skipped, instructions count: 841
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.AnonymousClass5.handleMessage(android.os.Message):void");
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$handleMessage$0(ProcessCpuTracker.Stats st) {
            return st != null && st.vsize > 0 && st.uid < 10000;
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void setSystemProcess() {
        try {
            ServiceManager.addService("activity", this, true, 21);
            ServiceManager.addService("procstats", this.mProcessStats);
            ServiceManager.addService("meminfo", new MemBinder(this), false, 2);
            ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
            ServiceManager.addService("dbinfo", new DbBinder(this));
            ServiceManager.addService("cpuinfo", new CpuBinder(this), false, 1);
            ServiceManager.addService("permission", new PermissionController(this));
            ServiceManager.addService("processinfo", new ProcessInfoService(this));
            ApplicationInfo info = this.mContext.getPackageManager().getApplicationInfo(PackageManagerService.PLATFORM_PACKAGE_NAME, 1049600);
            this.mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());
            synchronized (this) {
                boostPriorityForLockedSection();
                ProcessRecord app = this.mProcessList.newProcessRecordLocked(info, info.processName, false, 0, new HostingRecord("system"));
                app.setPersistent(true);
                app.pid = MY_PID;
                app.getWindowProcessController().setPid(MY_PID);
                app.maxAdj = -900;
                app.makeActive(this.mSystemThread.getApplicationThread(), this.mProcessStats);
                this.mPidsSelfLocked.put(app);
                this.mProcessList.updateLruProcessLocked(app, false, null);
                updateOomAdjLocked("updateOomAdj_meh");
            }
            resetPriorityAfterLockedSection();
            this.mAppOpsService.startWatchingMode(HANDLE_TRUST_STORAGE_UPDATE_MSG, null, new IAppOpsCallback.Stub() { // from class: com.android.server.am.ActivityManagerService.6
                public void opChanged(int op, int uid, String packageName) {
                    if (op == ActivityManagerService.HANDLE_TRUST_STORAGE_UPDATE_MSG && packageName != null && ActivityManagerService.this.mAppOpsService.checkOperation(op, uid, packageName) != 0) {
                        ActivityManagerService.this.runInBackgroundDisabled(uid);
                    }
                }
            });
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Unable to find android system package", e);
        }
    }

    public void setWindowManager(WindowManagerService wm) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mWindowManager = wm;
                this.mActivityTaskManager.setWindowManager(wm);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setUsageStatsManager(UsageStatsManagerInternal usageStatsManager) {
        this.mUsageStatsService = usageStatsManager;
        this.mActivityTaskManager.setUsageStatsManager(usageStatsManager);
    }

    public void setContentCaptureManager(ContentCaptureManagerInternal contentCaptureManager) {
        this.mContentCaptureService = contentCaptureManager;
    }

    public void startObservingNativeCrashes() {
        NativeCrashListener ncl = new NativeCrashListener(this);
        ncl.start();
    }

    public IAppOpsService getAppOpsService() {
        return this.mAppOpsService;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class MemBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() { // from class: com.android.server.am.ActivityManagerService.MemBinder.1
            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                dump(fd, pw, new String[]{"-a"}, asProto);
            }

            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                MemBinder.this.mActivityManagerService.dumpApplicationMemoryUsage(fd, pw, "  ", args, false, null, asProto);
            }
        };

        MemBinder(ActivityManagerService activityManagerService) {
            this.mActivityManagerService = activityManagerService;
        }

        @Override // android.os.Binder
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(this.mActivityManagerService.mContext, "meminfo", pw)) {
                return;
            }
            PriorityDump.dump(this.mPriorityDumper, fd, pw, args);
        }
    }

    /* loaded from: classes.dex */
    static class GraphicsBinder extends Binder {
        ActivityManagerService mActivityManagerService;

        GraphicsBinder(ActivityManagerService activityManagerService) {
            this.mActivityManagerService = activityManagerService;
        }

        @Override // android.os.Binder
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(this.mActivityManagerService.mContext, "gfxinfo", pw)) {
                return;
            }
            this.mActivityManagerService.dumpGraphicsHardwareUsage(fd, pw, args);
        }
    }

    /* loaded from: classes.dex */
    static class DbBinder extends Binder {
        ActivityManagerService mActivityManagerService;

        DbBinder(ActivityManagerService activityManagerService) {
            this.mActivityManagerService = activityManagerService;
        }

        @Override // android.os.Binder
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(this.mActivityManagerService.mContext, "dbinfo", pw)) {
                return;
            }
            this.mActivityManagerService.dumpDbInfo(fd, pw, args);
        }
    }

    /* loaded from: classes.dex */
    static class CpuBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() { // from class: com.android.server.am.ActivityManagerService.CpuBinder.1
            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                if (asProto || !DumpUtils.checkDumpAndUsageStatsPermission(CpuBinder.this.mActivityManagerService.mContext, "cpuinfo", pw)) {
                    return;
                }
                synchronized (CpuBinder.this.mActivityManagerService.mProcessCpuTracker) {
                    pw.print(CpuBinder.this.mActivityManagerService.mProcessCpuTracker.printCurrentLoad());
                    pw.print(CpuBinder.this.mActivityManagerService.mProcessCpuTracker.printCurrentState(SystemClock.uptimeMillis()));
                }
            }
        };

        CpuBinder(ActivityManagerService activityManagerService) {
            this.mActivityManagerService = activityManagerService;
        }

        @Override // android.os.Binder
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            PriorityDump.dump(this.mPriorityDumper, fd, pw, args);
        }
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        private static ActivityTaskManagerService sAtm;
        private final ActivityManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            this.mService = new ActivityManagerService(context, sAtm);
        }

        public static ActivityManagerService startService(SystemServiceManager ssm, ActivityTaskManagerService atm) {
            sAtm = atm;
            return ((Lifecycle) ssm.startService(Lifecycle.class)).getService();
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mService.start();
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            ActivityManagerService activityManagerService = this.mService;
            activityManagerService.mBootPhase = phase;
            if (phase == 500) {
                activityManagerService.mBatteryStatsService.systemServicesReady();
                this.mService.mServices.systemServicesReady();
            } else if (phase == 550) {
                activityManagerService.startBroadcastObservers();
            } else if (phase == 600) {
                activityManagerService.mPackageWatchdog.onPackagesReady();
            }
        }

        @Override // com.android.server.SystemService
        public void onCleanupUser(int userId) {
            this.mService.mBatteryStatsService.onCleanupUser(userId);
        }

        public ActivityManagerService getService() {
            return this.mService;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class HiddenApiSettings extends ContentObserver implements DeviceConfig.OnPropertiesChangedListener {
        public static final String HIDDEN_API_ACCESS_LOG_SAMPLING_RATE = "hidden_api_access_log_sampling_rate";
        public static final String HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE = "hidden_api_access_statslog_sampling_rate";
        private boolean mBlacklistDisabled;
        private final Context mContext;
        private List<String> mExemptions;
        private String mExemptionsStr;
        private int mLogSampleRate;
        private int mPolicy;
        private int mStatslogSampleRate;

        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            int logSampleRate = properties.getInt(HIDDEN_API_ACCESS_LOG_SAMPLING_RATE, this.mLogSampleRate);
            int statslogSampleRate = properties.getInt(HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE, this.mStatslogSampleRate);
            setSampleRates(logSampleRate, statslogSampleRate);
        }

        private void setSampleRates(int logSampleRate, int statslogSampleRate) {
            if (logSampleRate >= 0 && logSampleRate <= 65536 && logSampleRate != this.mLogSampleRate) {
                this.mLogSampleRate = logSampleRate;
                Process.ZYGOTE_PROCESS.setHiddenApiAccessLogSampleRate(this.mLogSampleRate);
            }
            if (statslogSampleRate >= 0 && statslogSampleRate <= 65536 && statslogSampleRate != this.mStatslogSampleRate) {
                this.mStatslogSampleRate = statslogSampleRate;
                Process.ZYGOTE_PROCESS.setHiddenApiAccessStatslogSampleRate(this.mStatslogSampleRate);
            }
        }

        private void initializeSampleRates() {
            int logSampleRate = DeviceConfig.getInt("app_compat", HIDDEN_API_ACCESS_LOG_SAMPLING_RATE, 0);
            int statslogSampleRate = DeviceConfig.getInt("app_compat", HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE, 0);
            setSampleRates(logSampleRate, statslogSampleRate);
        }

        public HiddenApiSettings(Handler handler, Context context) {
            super(handler);
            this.mExemptions = Collections.emptyList();
            this.mLogSampleRate = -1;
            this.mStatslogSampleRate = -1;
            this.mPolicy = -1;
            this.mContext = context;
        }

        public void registerObserver() {
            this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("hidden_api_blacklist_exemptions"), false, this);
            this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("hidden_api_policy"), false, this);
            initializeSampleRates();
            DeviceConfig.addOnPropertiesChangedListener("app_compat", this.mContext.getMainExecutor(), this);
            update();
        }

        private void update() {
            List<String> asList;
            String exemptions = Settings.Global.getString(this.mContext.getContentResolver(), "hidden_api_blacklist_exemptions");
            if (!TextUtils.equals(exemptions, this.mExemptionsStr)) {
                this.mExemptionsStr = exemptions;
                if ("*".equals(exemptions)) {
                    this.mBlacklistDisabled = true;
                    this.mExemptions = Collections.emptyList();
                } else {
                    this.mBlacklistDisabled = false;
                    if (TextUtils.isEmpty(exemptions)) {
                        asList = Collections.emptyList();
                    } else {
                        asList = Arrays.asList(exemptions.split(","));
                    }
                    this.mExemptions = asList;
                }
                if (!Process.ZYGOTE_PROCESS.setApiBlacklistExemptions(this.mExemptions)) {
                    Slog.e("ActivityManager", "Failed to set API blacklist exemptions!");
                    this.mExemptions = Collections.emptyList();
                }
            }
            this.mPolicy = getValidEnforcementPolicy("hidden_api_policy");
        }

        private int getValidEnforcementPolicy(String settingsKey) {
            int policy = Settings.Global.getInt(this.mContext.getContentResolver(), settingsKey, -1);
            if (ApplicationInfo.isValidHiddenApiEnforcementPolicy(policy)) {
                return policy;
            }
            return -1;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public boolean isDisabled() {
            return this.mBlacklistDisabled;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public int getPolicy() {
            return this.mPolicy;
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            update();
        }
    }

    @VisibleForTesting
    public ActivityManagerService(Injector injector) {
        this(injector, (ServiceThread) null);
    }

    @VisibleForTesting
    public ActivityManagerService(Injector injector, ServiceThread handlerThread) {
        ActivityManagerConstants activityManagerConstants;
        IntentFirewall intentFirewall;
        PendingIntentController pendingIntentController;
        this.mInstrumentationReporter = new InstrumentationReporter();
        this.mActiveInstrumentation = new ArrayList<>();
        this.mOomAdjProfiler = new OomAdjProfiler();
        this.mUseFifoUiScheduling = false;
        this.mBroadcastQueues = new BroadcastQueue[3];
        this.mPriorityDumper = new PriorityDump.PriorityDumper() { // from class: com.android.server.am.ActivityManagerService.1
            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                if (asProto) {
                    return;
                }
                ActivityManagerService.this.doDump(fd, pw, new String[]{ActivityTaskManagerService.DUMP_ACTIVITIES_CMD}, asProto);
                ActivityManagerService.this.doDump(fd, pw, new String[]{"service", "all-platform-critical"}, asProto);
            }

            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                ActivityManagerService.this.doDump(fd, pw, new String[]{"-a", "--normal-priority"}, asProto);
            }

            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                ActivityManagerService.this.doDump(fd, pw, args, asProto);
            }
        };
        this.mProcessList = new ProcessList();
        this.mBackgroundAppIdWhitelist = new int[]{1002};
        this.mPidsSelfLocked = new PidMap();
        this.mImportantProcesses = new SparseArray<>();
        this.mProcessesOnHold = new ArrayList<>();
        this.mPersistentStartingProcesses = new ArrayList<>();
        this.mProcessesToGc = new ArrayList<>();
        this.mPendingPssProcesses = new ArrayList<>();
        this.mActivityStartingNesting = new AtomicInteger(0);
        this.mActivityLaunchObserver = new ActivityMetricsLaunchObserver() { // from class: com.android.server.am.ActivityManagerService.2
            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onActivityLaunched(byte[] activity, int temperature) {
                if (ActivityManagerService.this.mPssDeferralTime > 0) {
                    Message msg = ActivityManagerService.this.mBgHandler.obtainMessage(2);
                    ActivityManagerService.this.mBgHandler.sendMessageAtFrontOfQueue(msg);
                }
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onIntentStarted(Intent intent) {
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onIntentFailed() {
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onActivityLaunchCancelled(byte[] abortingActivity) {
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onActivityLaunchFinished(byte[] finalActivity) {
            }
        };
        this.mPssDeferralTime = 0L;
        this.mBinderTransactionTrackingEnabled = false;
        this.mLastFullPssTime = SystemClock.uptimeMillis();
        this.mFullPssPending = false;
        this.mPssDelayConfigListener = new DeviceConfig.OnPropertiesChangedListener() { // from class: com.android.server.am.ActivityManagerService.3
            public void onPropertiesChanged(DeviceConfig.Properties properties) {
                ActivityManagerService.this.mPssDeferralTime = properties.getLong(ActivityManagerService.ACTIVITY_START_PSS_DEFER_CONFIG, 0L);
                if (ActivityManagerDebugConfig.DEBUG_PSS) {
                    Slog.d("ActivityManager", "Activity-start PSS delay now " + ActivityManagerService.this.mPssDeferralTime + " ms");
                }
            }
        };
        this.mValidateUids = new ActiveUids(this, false);
        this.mAlreadyLoggedViolatedStacks = new HashSet<>();
        this.mRegisteredReceivers = new HashMap<>();
        this.mReceiverResolver = new IntentResolver<BroadcastFilter, BroadcastFilter>() { // from class: com.android.server.am.ActivityManagerService.4
            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public boolean allowFilterResult(BroadcastFilter filter, List<BroadcastFilter> dest) {
                IBinder target = filter.receiverList.receiver.asBinder();
                for (int i = dest.size() - 1; i >= 0; i--) {
                    if (dest.get(i).receiverList.receiver.asBinder() == target) {
                        return false;
                    }
                }
                return true;
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public BroadcastFilter newResult(BroadcastFilter filter, int match, int userId) {
                if (userId == -1 || filter.owningUserId == -1 || userId == filter.owningUserId) {
                    return (BroadcastFilter) super.newResult((AnonymousClass4) filter, match, userId);
                }
                return null;
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public BroadcastFilter[] newArray(int size) {
                return new BroadcastFilter[size];
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public boolean isPackageForFilter(String packageName, BroadcastFilter filter) {
                return packageName.equals(filter.packageName);
            }
        };
        this.mStickyBroadcasts = new SparseArray<>();
        this.mAssociations = new SparseArray<>();
        this.mBackupTargets = new SparseArray<>();
        this.mLaunchingProviders = new ArrayList<>();
        this.mDeviceIdleWhitelist = new int[0];
        this.mDeviceIdleExceptIdleWhitelist = new int[0];
        this.mDeviceIdleTempWhitelist = new int[0];
        this.mPendingTempWhitelist = new PendingTempWhitelists(this);
        this.mStringBuilder = new StringBuilder(256);
        this.mProcessesReady = false;
        this.mSystemReady = false;
        this.mOnBattery = false;
        this.mBooting = false;
        this.mCallFinishBooting = false;
        this.mBootAnimationComplete = false;
        this.mWakefulness = 1;
        this.mAllowLowerMemLevel = false;
        this.mLastMemoryLevel = 0;
        this.mLastIdleTime = SystemClock.uptimeMillis();
        this.mLowRamTimeSinceLastIdle = 0L;
        this.mLowRamStartTime = 0L;
        this.mCurResumedPackage = null;
        this.mCurResumedUid = -1;
        this.mForegroundPackages = new ProcessMap<>();
        this.mTestPssMode = false;
        this.mDebugApp = null;
        this.mWaitForDebugger = false;
        this.mDebugTransient = false;
        this.mOrigDebugApp = null;
        this.mOrigWaitForDebugger = false;
        this.mAlwaysFinishActivities = false;
        this.mProfileData = new ProfileData();
        this.mAppAgentMap = null;
        this.mProfileType = 0;
        this.mMemWatchProcesses = new ProcessMap<>();
        this.mTrackAllocationApp = null;
        this.mNativeDebuggingApp = null;
        this.mProcessObservers = new RemoteCallbackList<>();
        this.mActiveProcessChanges = new ProcessChangeItem[5];
        this.mPendingProcessChanges = new ArrayList<>();
        this.mAvailProcessChanges = new ArrayList<>();
        this.mUidObservers = new RemoteCallbackList<>();
        this.mActiveUidChanges = new UidRecord.ChangeItem[5];
        this.mPendingUidChanges = new ArrayList<>();
        this.mAvailUidChanges = new ArrayList<>();
        this.mProcessCpuTracker = new ProcessCpuTracker(false);
        this.mLastCpuTime = new AtomicLong(0L);
        this.mProcessCpuMutexFree = new AtomicBoolean(true);
        this.mProcessCpuInitLatch = new CountDownLatch(1);
        this.mLastWriteTime = 0L;
        this.mBooted = false;
        this.mLastMemUsageReportTime = 0L;
        this.mBgHandler = new AnonymousClass5(BackgroundThread.getHandler().getLooper());
        this.mProcessStateStatsLongs = new long[1];
        boolean hasHandlerThread = handlerThread != null;
        this.mInjector = injector;
        this.mContext = this.mInjector.getContext();
        this.mUiContext = null;
        this.mAppErrors = null;
        this.mPackageWatchdog = null;
        this.mAppOpsService = this.mInjector.getAppOpsService(null, null);
        this.mBatteryStatsService = null;
        this.mHandler = hasHandlerThread ? new MainHandler(handlerThread.getLooper()) : null;
        this.mHandlerThread = handlerThread;
        if (!hasHandlerThread) {
            activityManagerConstants = null;
        } else {
            activityManagerConstants = new ActivityManagerConstants(this.mContext, this, this.mHandler);
        }
        this.mConstants = activityManagerConstants;
        ActiveUids activeUids = new ActiveUids(this, false);
        this.mProcessList.init(this, activeUids);
        this.mLowMemDetector = null;
        this.mOomAdjuster = new OomAdjuster(this, this.mProcessList, activeUids);
        if (!hasHandlerThread) {
            intentFirewall = null;
        } else {
            intentFirewall = new IntentFirewall(new IntentFirewallInterface(), this.mHandler);
        }
        this.mIntentFirewall = intentFirewall;
        this.mProcessCpuThread = null;
        this.mProcessStats = null;
        this.mProviderMap = null;
        this.mServices = hasHandlerThread ? new ActiveServices(this) : null;
        this.mSystemThread = null;
        this.mUiHandler = injector.getUiHandler(null);
        this.mUserController = hasHandlerThread ? new UserController(this) : null;
        if (!hasHandlerThread) {
            pendingIntentController = null;
        } else {
            pendingIntentController = new PendingIntentController(handlerThread.getLooper(), this.mUserController);
        }
        this.mPendingIntentController = pendingIntentController;
        this.mProcStartHandlerThread = null;
        this.mProcStartHandler = null;
        this.mHiddenApiBlacklist = null;
        this.mFactoryTest = 0;
    }

    public ActivityManagerService(Context systemContext, ActivityTaskManagerService atm) {
        boolean isOnBattery;
        this.mInstrumentationReporter = new InstrumentationReporter();
        this.mActiveInstrumentation = new ArrayList<>();
        this.mOomAdjProfiler = new OomAdjProfiler();
        this.mUseFifoUiScheduling = false;
        this.mBroadcastQueues = new BroadcastQueue[3];
        this.mPriorityDumper = new PriorityDump.PriorityDumper() { // from class: com.android.server.am.ActivityManagerService.1
            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                if (asProto) {
                    return;
                }
                ActivityManagerService.this.doDump(fd, pw, new String[]{ActivityTaskManagerService.DUMP_ACTIVITIES_CMD}, asProto);
                ActivityManagerService.this.doDump(fd, pw, new String[]{"service", "all-platform-critical"}, asProto);
            }

            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                ActivityManagerService.this.doDump(fd, pw, new String[]{"-a", "--normal-priority"}, asProto);
            }

            @Override // com.android.server.utils.PriorityDump.PriorityDumper
            public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                ActivityManagerService.this.doDump(fd, pw, args, asProto);
            }
        };
        this.mProcessList = new ProcessList();
        this.mBackgroundAppIdWhitelist = new int[]{1002};
        this.mPidsSelfLocked = new PidMap();
        this.mImportantProcesses = new SparseArray<>();
        this.mProcessesOnHold = new ArrayList<>();
        this.mPersistentStartingProcesses = new ArrayList<>();
        this.mProcessesToGc = new ArrayList<>();
        this.mPendingPssProcesses = new ArrayList<>();
        this.mActivityStartingNesting = new AtomicInteger(0);
        this.mActivityLaunchObserver = new ActivityMetricsLaunchObserver() { // from class: com.android.server.am.ActivityManagerService.2
            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onActivityLaunched(byte[] activity, int temperature) {
                if (ActivityManagerService.this.mPssDeferralTime > 0) {
                    Message msg = ActivityManagerService.this.mBgHandler.obtainMessage(2);
                    ActivityManagerService.this.mBgHandler.sendMessageAtFrontOfQueue(msg);
                }
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onIntentStarted(Intent intent) {
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onIntentFailed() {
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onActivityLaunchCancelled(byte[] abortingActivity) {
            }

            @Override // com.android.server.wm.ActivityMetricsLaunchObserver
            public void onActivityLaunchFinished(byte[] finalActivity) {
            }
        };
        this.mPssDeferralTime = 0L;
        this.mBinderTransactionTrackingEnabled = false;
        this.mLastFullPssTime = SystemClock.uptimeMillis();
        this.mFullPssPending = false;
        this.mPssDelayConfigListener = new DeviceConfig.OnPropertiesChangedListener() { // from class: com.android.server.am.ActivityManagerService.3
            public void onPropertiesChanged(DeviceConfig.Properties properties) {
                ActivityManagerService.this.mPssDeferralTime = properties.getLong(ActivityManagerService.ACTIVITY_START_PSS_DEFER_CONFIG, 0L);
                if (ActivityManagerDebugConfig.DEBUG_PSS) {
                    Slog.d("ActivityManager", "Activity-start PSS delay now " + ActivityManagerService.this.mPssDeferralTime + " ms");
                }
            }
        };
        this.mValidateUids = new ActiveUids(this, false);
        this.mAlreadyLoggedViolatedStacks = new HashSet<>();
        this.mRegisteredReceivers = new HashMap<>();
        this.mReceiverResolver = new IntentResolver<BroadcastFilter, BroadcastFilter>() { // from class: com.android.server.am.ActivityManagerService.4
            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public boolean allowFilterResult(BroadcastFilter filter, List<BroadcastFilter> dest) {
                IBinder target = filter.receiverList.receiver.asBinder();
                for (int i = dest.size() - 1; i >= 0; i--) {
                    if (dest.get(i).receiverList.receiver.asBinder() == target) {
                        return false;
                    }
                }
                return true;
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public BroadcastFilter newResult(BroadcastFilter filter, int match, int userId) {
                if (userId == -1 || filter.owningUserId == -1 || userId == filter.owningUserId) {
                    return (BroadcastFilter) super.newResult((AnonymousClass4) filter, match, userId);
                }
                return null;
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public BroadcastFilter[] newArray(int size) {
                return new BroadcastFilter[size];
            }

            /* JADX INFO: Access modifiers changed from: protected */
            @Override // com.android.server.IntentResolver
            public boolean isPackageForFilter(String packageName, BroadcastFilter filter) {
                return packageName.equals(filter.packageName);
            }
        };
        this.mStickyBroadcasts = new SparseArray<>();
        this.mAssociations = new SparseArray<>();
        this.mBackupTargets = new SparseArray<>();
        this.mLaunchingProviders = new ArrayList<>();
        this.mDeviceIdleWhitelist = new int[0];
        this.mDeviceIdleExceptIdleWhitelist = new int[0];
        this.mDeviceIdleTempWhitelist = new int[0];
        this.mPendingTempWhitelist = new PendingTempWhitelists(this);
        this.mStringBuilder = new StringBuilder(256);
        this.mProcessesReady = false;
        this.mSystemReady = false;
        this.mOnBattery = false;
        this.mBooting = false;
        this.mCallFinishBooting = false;
        this.mBootAnimationComplete = false;
        this.mWakefulness = 1;
        this.mAllowLowerMemLevel = false;
        this.mLastMemoryLevel = 0;
        this.mLastIdleTime = SystemClock.uptimeMillis();
        this.mLowRamTimeSinceLastIdle = 0L;
        this.mLowRamStartTime = 0L;
        this.mCurResumedPackage = null;
        this.mCurResumedUid = -1;
        this.mForegroundPackages = new ProcessMap<>();
        this.mTestPssMode = false;
        this.mDebugApp = null;
        this.mWaitForDebugger = false;
        this.mDebugTransient = false;
        this.mOrigDebugApp = null;
        this.mOrigWaitForDebugger = false;
        this.mAlwaysFinishActivities = false;
        this.mProfileData = new ProfileData();
        this.mAppAgentMap = null;
        this.mProfileType = 0;
        this.mMemWatchProcesses = new ProcessMap<>();
        this.mTrackAllocationApp = null;
        this.mNativeDebuggingApp = null;
        this.mProcessObservers = new RemoteCallbackList<>();
        this.mActiveProcessChanges = new ProcessChangeItem[5];
        this.mPendingProcessChanges = new ArrayList<>();
        this.mAvailProcessChanges = new ArrayList<>();
        this.mUidObservers = new RemoteCallbackList<>();
        this.mActiveUidChanges = new UidRecord.ChangeItem[5];
        this.mPendingUidChanges = new ArrayList<>();
        this.mAvailUidChanges = new ArrayList<>();
        this.mProcessCpuTracker = new ProcessCpuTracker(false);
        this.mLastCpuTime = new AtomicLong(0L);
        this.mProcessCpuMutexFree = new AtomicBoolean(true);
        this.mProcessCpuInitLatch = new CountDownLatch(1);
        this.mLastWriteTime = 0L;
        this.mBooted = false;
        this.mLastMemUsageReportTime = 0L;
        this.mBgHandler = new AnonymousClass5(BackgroundThread.getHandler().getLooper());
        this.mProcessStateStatsLongs = new long[1];
        LockGuard.installLock(this, 6);
        this.mInjector = new Injector();
        this.mContext = systemContext;
        this.mFactoryTest = FactoryTest.getMode();
        this.mSystemThread = ActivityThread.currentActivityThread();
        this.mUiContext = this.mSystemThread.getSystemUiContext();
        Slog.i("ActivityManager", "Memory class: " + ActivityManager.staticGetMemoryClass());
        this.mHandlerThread = new ServiceThread("ActivityManager", -2, false);
        this.mHandlerThread.start();
        this.mHandler = new MainHandler(this.mHandlerThread.getLooper());
        this.mUiHandler = this.mInjector.getUiHandler(this);
        this.mProcStartHandlerThread = new ServiceThread("ActivityManager:procStart", -2, false);
        this.mProcStartHandlerThread.start();
        this.mProcStartHandler = new Handler(this.mProcStartHandlerThread.getLooper());
        this.mConstants = new ActivityManagerConstants(this.mContext, this, this.mHandler);
        ActiveUids activeUids = new ActiveUids(this, true);
        this.mProcessList.init(this, activeUids);
        this.mLowMemDetector = new LowMemDetector(this);
        this.mOomAdjuster = new OomAdjuster(this, this.mProcessList, activeUids);
        BroadcastConstants foreConstants = new BroadcastConstants("bcast_fg_constants");
        foreConstants.TIMEOUT = JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
        BroadcastConstants backConstants = new BroadcastConstants("bcast_bg_constants");
        backConstants.TIMEOUT = 60000L;
        BroadcastConstants offloadConstants = new BroadcastConstants("bcast_offload_constants");
        offloadConstants.TIMEOUT = 60000L;
        offloadConstants.SLOW_TIME = 2147483647L;
        this.mEnableOffloadQueue = SystemProperties.getBoolean("persist.device_config.activity_manager_native_boot.offload_queue_enabled", false);
        this.mFgBroadcastQueue = new BroadcastQueue(this, this.mHandler, "foreground", foreConstants, false);
        this.mBgBroadcastQueue = new BroadcastQueue(this, this.mHandler, "background", backConstants, true);
        this.mOffloadBroadcastQueue = new BroadcastQueue(this, this.mHandler, "offload", offloadConstants, true);
        BroadcastQueue[] broadcastQueueArr = this.mBroadcastQueues;
        broadcastQueueArr[0] = this.mFgBroadcastQueue;
        broadcastQueueArr[1] = this.mBgBroadcastQueue;
        broadcastQueueArr[2] = this.mOffloadBroadcastQueue;
        this.mServices = new ActiveServices(this);
        this.mProviderMap = new ProviderMap(this);
        this.mPackageWatchdog = PackageWatchdog.getInstance(this.mUiContext);
        this.mAppErrors = new AppErrors(this.mUiContext, this, this.mPackageWatchdog);
        File systemDir = SystemServiceManager.ensureSystemDir();
        BackgroundThread.get();
        this.mBatteryStatsService = new XpBatteryStatsService(systemContext, systemDir, BackgroundThread.getHandler());
        this.mBatteryStatsService.getActiveStatistics().readLocked();
        this.mBatteryStatsService.scheduleWriteToDisk();
        if (ActivityManagerDebugConfig.DEBUG_POWER) {
            isOnBattery = true;
        } else {
            isOnBattery = this.mBatteryStatsService.getActiveStatistics().getIsOnBattery();
        }
        this.mOnBattery = isOnBattery;
        this.mBatteryStatsService.getActiveStatistics().setCallback(this);
        this.mOomAdjProfiler.batteryPowerChanged(this.mOnBattery);
        this.mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));
        this.mAppOpsService = this.mInjector.getAppOpsService(new File(systemDir, "appops.xml"), this.mHandler);
        this.mUgmInternal = (UriGrantsManagerInternal) LocalServices.getService(UriGrantsManagerInternal.class);
        this.mUserController = new UserController(this);
        this.mPendingIntentController = new PendingIntentController(this.mHandlerThread.getLooper(), this.mUserController);
        if (SystemProperties.getInt("sys.use_fifo_ui", 0) != 0) {
            this.mUseFifoUiScheduling = true;
        }
        this.mTrackingAssociations = "1".equals(SystemProperties.get("debug.track-associations"));
        this.mIntentFirewall = new IntentFirewall(new IntentFirewallInterface(), this.mHandler);
        this.mActivityTaskManager = atm;
        this.mActivityTaskManager.initialize(this.mIntentFirewall, this.mPendingIntentController, DisplayThread.get().getLooper());
        this.mAtmInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
        this.mProcessCpuThread = new Thread("CpuTracker") { // from class: com.android.server.am.ActivityManagerService.7
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                synchronized (ActivityManagerService.this.mProcessCpuTracker) {
                    try {
                        ActivityManagerService.this.mProcessCpuInitLatch.countDown();
                        ActivityManagerService.this.mProcessCpuTracker.init();
                    } finally {
                        th = th;
                        while (true) {
                            try {
                                break;
                            } catch (Throwable th) {
                                th = th;
                            }
                        }
                    }
                }
                while (true) {
                    try {
                        try {
                            synchronized (this) {
                                long now = SystemClock.uptimeMillis();
                                long nextCpuDelay = (ActivityManagerService.this.mLastCpuTime.get() + ActivityManagerService.MONITOR_CPU_MAX_TIME) - now;
                                long nextWriteDelay = (ActivityManagerService.this.mLastWriteTime + 1800000) - now;
                                if (nextWriteDelay < nextCpuDelay) {
                                    nextCpuDelay = nextWriteDelay;
                                }
                                if (nextCpuDelay > 0) {
                                    ActivityManagerService.this.mProcessCpuMutexFree.set(true);
                                    wait(nextCpuDelay);
                                }
                            }
                        } catch (InterruptedException e) {
                        }
                        ActivityManagerService.this.updateCpuStatsNow();
                    } catch (Exception e2) {
                        Slog.e("ActivityManager", "Unexpected exception collecting process stats", e2);
                    }
                }
            }
        };
        this.mHiddenApiBlacklist = new HiddenApiSettings(this.mHandler, this.mContext);
        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(this.mHandler);
        updateOomAdjLocked("updateOomAdj_meh");
        try {
            Process.setThreadGroupAndCpuset(BackgroundThread.get().getThreadId(), 2);
            Process.setThreadGroupAndCpuset(this.mOomAdjuster.mAppCompact.mCompactionThread.getThreadId(), 2);
        } catch (Exception e) {
            Slog.w("ActivityManager", "Setting background thread cpuset failed");
        }
        xpSystemServer.get().init(this.mContext);
        xpActivityManagerService.get(this.mContext).onStart();
    }

    public void setSystemServiceManager(SystemServiceManager mgr) {
        this.mSystemServiceManager = mgr;
    }

    public void setInstaller(Installer installer) {
        this.mInstaller = installer;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void start() {
        Process.removeAllProcessGroups();
        this.mProcessCpuThread.start();
        this.mBatteryStatsService.publish();
        this.mAppOpsService.publish(this.mContext);
        Slog.d("AppOps", "AppOpsService published");
        LocalServices.addService(ActivityManagerInternal.class, new LocalService());
        this.mActivityTaskManager.onActivityManagerInternalAdded();
        this.mUgmInternal.onActivityManagerInternalAdded();
        this.mPendingIntentController.onActivityManagerInternalAdded();
        try {
            this.mProcessCpuInitLatch.await();
        } catch (InterruptedException e) {
            Slog.wtf("ActivityManager", "Interrupted wait during start", e);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted wait during start");
        }
    }

    public void initPowerManagement() {
        this.mActivityTaskManager.onInitPowerManagement();
        this.mBatteryStatsService.initPowerManagement();
        this.mLocalPowerManager = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
    }

    private ArraySet<String> getBackgroundLaunchBroadcasts() {
        if (this.mBackgroundLaunchBroadcasts == null) {
            this.mBackgroundLaunchBroadcasts = SystemConfig.getInstance().getAllowImplicitBroadcasts();
        }
        return this.mBackgroundLaunchBroadcasts;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requireAllowedAssociationsLocked(String packageName) {
        ensureAllowedAssociations();
        if (this.mAllowedAssociations.get(packageName) == null) {
            this.mAllowedAssociations.put(packageName, new PackageAssociationInfo(packageName, new ArraySet(), false));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean validateAssociationAllowedLocked(String pkg1, int uid1, String pkg2, int uid2) {
        ensureAllowedAssociations();
        if (uid1 == uid2 || UserHandle.getAppId(uid1) == 1000 || UserHandle.getAppId(uid2) == 1000) {
            return true;
        }
        PackageAssociationInfo pai = this.mAllowedAssociations.get(pkg1);
        if (pai != null && !pai.isPackageAssociationAllowed(pkg2)) {
            return false;
        }
        PackageAssociationInfo pai2 = this.mAllowedAssociations.get(pkg2);
        if (pai2 == null || pai2.isPackageAssociationAllowed(pkg1)) {
            return true;
        }
        return false;
    }

    private void ensureAllowedAssociations() {
        if (this.mAllowedAssociations == null) {
            ArrayMap<String, ArraySet<String>> allowedAssociations = SystemConfig.getInstance().getAllowedAssociations();
            this.mAllowedAssociations = new ArrayMap<>(allowedAssociations.size());
            getPackageManagerInternalLocked();
            for (int i = 0; i < allowedAssociations.size(); i++) {
                String pkg = allowedAssociations.keyAt(i);
                ArraySet<String> asc = allowedAssociations.valueAt(i);
                boolean isDebuggable = false;
                try {
                    ApplicationInfo ai = AppGlobals.getPackageManager().getApplicationInfo(pkg, 131072, 0);
                    if (ai != null) {
                        isDebuggable = (ai.flags & 2) != 0;
                    }
                } catch (RemoteException e) {
                }
                this.mAllowedAssociations.put(pkg, new PackageAssociationInfo(pkg, asc, isDebuggable));
            }
        }
    }

    private void updateAssociationForApp(ApplicationInfo appInfo) {
        ensureAllowedAssociations();
        PackageAssociationInfo pai = this.mAllowedAssociations.get(appInfo.packageName);
        if (pai != null) {
            pai.setDebuggable((appInfo.flags & 2) != 0);
        }
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == 1599295570) {
            ArrayList<IBinder> procs = new ArrayList<>();
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    int NP = this.mProcessList.mProcessNames.getMap().size();
                    for (int ip = 0; ip < NP; ip++) {
                        SparseArray<ProcessRecord> apps = (SparseArray) this.mProcessList.mProcessNames.getMap().valueAt(ip);
                        int NA = apps.size();
                        for (int ia = 0; ia < NA; ia++) {
                            ProcessRecord app = apps.valueAt(ia);
                            if (app.thread != null) {
                                procs.add(app.thread.asBinder());
                            }
                        }
                    }
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            int N = procs.size();
            for (int i = 0; i < N; i++) {
                Parcel data2 = Parcel.obtain();
                try {
                    procs.get(i).transact(1599295570, data2, null, 1);
                } catch (RemoteException e) {
                }
                data2.recycle();
            }
        }
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e2) {
            if (!(e2 instanceof SecurityException) && !(e2 instanceof IllegalArgumentException) && !(e2 instanceof IllegalStateException)) {
                Slog.wtf("ActivityManager", "Activity Manager Crash. UID:" + Binder.getCallingUid() + " PID:" + Binder.getCallingPid() + " TRANS:" + code, e2);
            }
            throw e2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateCpuStats() {
        long now = SystemClock.uptimeMillis();
        if (this.mLastCpuTime.get() < now - MONITOR_CPU_MIN_TIME && this.mProcessCpuMutexFree.compareAndSet(true, false)) {
            synchronized (this.mProcessCpuThread) {
                this.mProcessCpuThread.notify();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateCpuStatsNow() {
        PidMap pidMap;
        int totalUTime;
        int N;
        PidMap pidMap2;
        synchronized (this.mProcessCpuTracker) {
            this.mProcessCpuMutexFree.set(false);
            long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;
            if (this.mLastCpuTime.get() < now - MONITOR_CPU_MIN_TIME) {
                this.mLastCpuTime.set(now);
                this.mProcessCpuTracker.update();
                if (this.mProcessCpuTracker.hasGoodLastStats()) {
                    haveNewCpuStats = true;
                    if ("true".equals(SystemProperties.get("events.cpu"))) {
                        int user = this.mProcessCpuTracker.getLastUserTime();
                        int system = this.mProcessCpuTracker.getLastSystemTime();
                        int iowait = this.mProcessCpuTracker.getLastIoWaitTime();
                        int irq = this.mProcessCpuTracker.getLastIrqTime();
                        int softIrq = this.mProcessCpuTracker.getLastSoftIrqTime();
                        int idle = this.mProcessCpuTracker.getLastIdleTime();
                        int total = user + system + iowait + irq + softIrq + idle;
                        if (total == 0) {
                            total = 1;
                        }
                        EventLog.writeEvent((int) EventLogTags.CPU, Integer.valueOf((((((user + system) + iowait) + irq) + softIrq) * 100) / total), Integer.valueOf((user * 100) / total), Integer.valueOf((system * 100) / total), Integer.valueOf((iowait * 100) / total), Integer.valueOf((irq * 100) / total), Integer.valueOf((softIrq * 100) / total));
                    }
                }
            }
            boolean haveNewCpuStats2 = haveNewCpuStats;
            BatteryStatsImpl bstats = this.mBatteryStatsService.getActiveStatistics();
            synchronized (bstats) {
                PidMap pidMap3 = this.mPidsSelfLocked;
                synchronized (pidMap3) {
                    try {
                        if (!haveNewCpuStats2) {
                            pidMap = pidMap3;
                        } else {
                            try {
                                if (!bstats.startAddingCpuLocked()) {
                                    pidMap = pidMap3;
                                } else {
                                    int totalUTime2 = 0;
                                    int N2 = this.mProcessCpuTracker.countStats();
                                    int i = 0;
                                    int totalSTime = 0;
                                    while (i < N2) {
                                        ProcessCpuTracker.Stats st = this.mProcessCpuTracker.getStats(i);
                                        if (!st.working) {
                                            N = N2;
                                            pidMap2 = pidMap3;
                                        } else {
                                            ProcessRecord pr = this.mPidsSelfLocked.get(st.pid);
                                            int totalUTime3 = totalUTime2 + st.rel_utime;
                                            totalSTime += st.rel_stime;
                                            if (pr != null) {
                                                BatteryStatsImpl.Uid.Proc ps = pr.curProcBatteryStats;
                                                if (ps == null || !ps.isActive()) {
                                                    BatteryStatsImpl.Uid.Proc processStatsLocked = bstats.getProcessStatsLocked(pr.info.uid, pr.processName);
                                                    ps = processStatsLocked;
                                                    pr.curProcBatteryStats = processStatsLocked;
                                                }
                                                ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                                long j = pr.curCpuTime;
                                                int i2 = st.rel_utime;
                                                totalUTime = totalUTime3;
                                                int totalUTime4 = st.rel_stime;
                                                N = N2;
                                                pidMap2 = pidMap3;
                                                pr.curCpuTime = j + i2 + totalUTime4;
                                                if (pr.lastCpuTime == 0) {
                                                    pr.lastCpuTime = pr.curCpuTime;
                                                }
                                            } else {
                                                totalUTime = totalUTime3;
                                                N = N2;
                                                pidMap2 = pidMap3;
                                                BatteryStatsImpl.Uid.Proc ps2 = st.batteryStats;
                                                if (ps2 == null || !ps2.isActive()) {
                                                    BatteryStatsImpl.Uid.Proc processStatsLocked2 = bstats.getProcessStatsLocked(bstats.mapUid(st.uid), st.name);
                                                    ps2 = processStatsLocked2;
                                                    st.batteryStats = processStatsLocked2;
                                                }
                                                ps2.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                            }
                                            totalUTime2 = totalUTime;
                                        }
                                        i++;
                                        pidMap3 = pidMap2;
                                        N2 = N;
                                    }
                                    pidMap = pidMap3;
                                    int userTime = this.mProcessCpuTracker.getLastUserTime();
                                    int systemTime = this.mProcessCpuTracker.getLastSystemTime();
                                    int iowaitTime = this.mProcessCpuTracker.getLastIoWaitTime();
                                    int irqTime = this.mProcessCpuTracker.getLastIrqTime();
                                    int softIrqTime = this.mProcessCpuTracker.getLastSoftIrqTime();
                                    int idleTime = this.mProcessCpuTracker.getLastIdleTime();
                                    bstats.finishAddingCpuLocked(totalUTime2, totalSTime, userTime, systemTime, iowaitTime, irqTime, softIrqTime, idleTime);
                                }
                            } catch (Throwable th) {
                                th = th;
                                PidMap pidMap4 = pidMap3;
                                throw th;
                            }
                        }
                        if (this.mLastWriteTime < now - 1800000) {
                            this.mLastWriteTime = now;
                            this.mBatteryStatsService.scheduleWriteToDisk();
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
            }
        }
    }

    public void batteryNeedsCpuUpdate() {
        updateCpuStatsNow();
    }

    public void batteryPowerChanged(boolean onBattery) {
        updateCpuStatsNow();
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                synchronized (this.mPidsSelfLocked) {
                    this.mOnBattery = ActivityManagerDebugConfig.DEBUG_POWER ? true : onBattery;
                }
                this.mOomAdjProfiler.batteryPowerChanged(onBattery);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void batteryStatsReset() {
        this.mOomAdjProfiler.reset();
    }

    public void batterySendBroadcast(Intent intent) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null, -1, null, false, false, -1, 1000, Binder.getCallingUid(), Binder.getCallingPid(), -1);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    private ArrayMap<String, IBinder> getCommonServicesLocked(boolean isolated) {
        if (isolated) {
            if (this.mIsolatedAppBindArgs == null) {
                this.mIsolatedAppBindArgs = new ArrayMap<>(1);
                addServiceToMap(this.mIsolatedAppBindArgs, "package");
            }
            return this.mIsolatedAppBindArgs;
        }
        if (this.mAppBindArgs == null) {
            this.mAppBindArgs = new ArrayMap<>();
            addServiceToMap(this.mAppBindArgs, "package");
            addServiceToMap(this.mAppBindArgs, "window");
            addServiceToMap(this.mAppBindArgs, "alarm");
            addServiceToMap(this.mAppBindArgs, "display");
            addServiceToMap(this.mAppBindArgs, "network_management");
            addServiceToMap(this.mAppBindArgs, "connectivity");
            addServiceToMap(this.mAppBindArgs, "accessibility");
            addServiceToMap(this.mAppBindArgs, "input_method");
            addServiceToMap(this.mAppBindArgs, "input");
            addServiceToMap(this.mAppBindArgs, GraphicsStatsService.GRAPHICS_STATS_SERVICE);
            addServiceToMap(this.mAppBindArgs, "appops");
            addServiceToMap(this.mAppBindArgs, ActivityTaskManagerInternal.ASSIST_KEY_CONTENT);
            addServiceToMap(this.mAppBindArgs, "jobscheduler");
            addServiceToMap(this.mAppBindArgs, "notification");
            addServiceToMap(this.mAppBindArgs, "vibrator");
            addServiceToMap(this.mAppBindArgs, "account");
            addServiceToMap(this.mAppBindArgs, "power");
            addServiceToMap(this.mAppBindArgs, "user");
            addServiceToMap(this.mAppBindArgs, "mount");
        }
        return this.mAppBindArgs;
    }

    private static void addServiceToMap(ArrayMap<String, IBinder> map, String name) {
        IBinder service = ServiceManager.getService(name);
        if (service != null) {
            map.put(name, service);
        }
    }

    public void setFocusedStack(int stackId) {
        this.mActivityTaskManager.setFocusedStack(stackId);
    }

    public void registerTaskStackListener(ITaskStackListener listener) {
        this.mActivityTaskManager.registerTaskStackListener(listener);
    }

    public void unregisterTaskStackListener(ITaskStackListener listener) {
        this.mActivityTaskManager.unregisterTaskStackListener(listener);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void updateLruProcessLocked(ProcessRecord app, boolean activityChange, ProcessRecord client) {
        this.mProcessList.updateLruProcessLocked(app, activityChange, client);
    }

    final void removeLruProcessLocked(ProcessRecord app) {
        this.mProcessList.removeLruProcessLocked(app);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ProcessRecord getProcessRecordLocked(String processName, int uid, boolean keepIfLarge) {
        return this.mProcessList.getProcessRecordLocked(processName, uid, keepIfLarge);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ProcessMap<ProcessRecord> getProcessNames() {
        return this.mProcessList.mProcessNames;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyPackageUse(String packageName, int reason) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                getPackageManagerInternalLocked().notifyPackageUse(packageName, reason);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    boolean startIsolatedProcess(String entryPoint, String[] entryPointArgs, String processName, String abiOverride, int uid, Runnable crashHandler) {
        boolean z;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ApplicationInfo info = new ApplicationInfo();
                info.uid = 1000;
                info.processName = processName;
                info.className = entryPoint;
                info.packageName = PackageManagerService.PLATFORM_PACKAGE_NAME;
                info.seInfoUser = ":complete";
                info.targetSdkVersion = Build.VERSION.SDK_INT;
                ProcessRecord proc = this.mProcessList.startProcessLocked(processName, info, false, 0, sNullHostingRecord, true, true, uid, true, abiOverride, entryPoint, entryPointArgs, crashHandler);
                z = proc != null;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final ProcessRecord startProcessLocked(String processName, ApplicationInfo info, boolean knownToBeDead, int intentFlags, HostingRecord hostingRecord, boolean allowWhileBooting, boolean isolated, boolean keepIfLarge) {
        return this.mProcessList.startProcessLocked(processName, info, knownToBeDead, intentFlags, hostingRecord, allowWhileBooting, isolated, 0, keepIfLarge, null, null, null, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAllowedWhileBooting(ApplicationInfo ai) {
        return (ai.flags & 8) != 0;
    }

    void updateBatteryStats(ComponentName activity, int uid, int userId, boolean resumed) {
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityManager", "updateBatteryStats: comp=" + activity + "res=" + resumed);
        }
        BatteryStatsImpl stats = this.mBatteryStatsService.getActiveStatistics();
        StatsLog.write(42, uid, activity.getPackageName(), activity.getShortClassName(), resumed ? 1 : 0);
        synchronized (stats) {
            if (resumed) {
                stats.noteActivityResumedLocked(uid);
            } else {
                stats.noteActivityPausedLocked(uid);
            }
        }
    }

    public void updateActivityUsageStats(ComponentName activity, int userId, int event, IBinder appToken, ComponentName taskRoot) {
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityManager", "updateActivityUsageStats: comp=" + activity + " hash=" + appToken.hashCode() + " event=" + event);
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (this.mUsageStatsService != null) {
                    this.mUsageStatsService.reportEvent(activity, userId, event, appToken.hashCode(), taskRoot);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        if (this.mContentCaptureService != null) {
            if (event == 2 || event == 1 || event == 23 || event == 24) {
                this.mContentCaptureService.notifyActivityEvent(userId, activity, event);
            }
        }
    }

    public void updateActivityUsageStats(String packageName, int userId, int event) {
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityManager", "updateActivityUsageStats: package=" + packageName + " event=" + event);
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (this.mUsageStatsService != null) {
                    this.mUsageStatsService.reportEvent(packageName, userId, event);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateForegroundServiceUsageStats(ComponentName service, int userId, boolean started) {
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.d("ActivityManager", "updateForegroundServiceUsageStats: comp=" + service + " started=" + started);
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (this.mUsageStatsService != null) {
                    this.mUsageStatsService.reportEvent(service, userId, started ? 19 : 20, 0, (ComponentName) null);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public CompatibilityInfo compatibilityInfoForPackage(ApplicationInfo ai) {
        return this.mAtmInternal.compatibilityInfoForPackage(ai);
    }

    private void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    public void setPackageScreenCompatMode(String packageName, int mode) {
        this.mActivityTaskManager.setPackageScreenCompatMode(packageName, mode);
    }

    private boolean hasUsageStatsPermission(String callingPackage) {
        int mode = this.mAppOpsService.noteOperation(43, Binder.getCallingUid(), callingPackage);
        return mode == 3 ? checkCallingPermission("android.permission.PACKAGE_USAGE_STATS") == 0 : mode == 0;
    }

    public int getPackageProcessState(String packageName, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission("android.permission.PACKAGE_USAGE_STATS", "getPackageProcessState");
        }
        int procState = 21;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                for (int i = this.mProcessList.mLruProcesses.size() - 1; i >= 0; i--) {
                    ProcessRecord proc = this.mProcessList.mLruProcesses.get(i);
                    if (procState > proc.setProcState && (proc.pkgList.containsKey(packageName) || (proc.pkgDeps != null && proc.pkgDeps.contains(packageName)))) {
                        procState = proc.setProcState;
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return procState;
    }

    public boolean setProcessMemoryTrimLevel(String process, int userId, int level) throws RemoteException {
        if (!isCallerShell()) {
            EventLog.writeEvent(1397638484, 160390416, Integer.valueOf(Binder.getCallingUid()), "");
            throw new SecurityException("Only shell can call it");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ProcessRecord app = findProcessLocked(process, userId, "setProcessMemoryTrimLevel");
                if (app == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                } else if (app.thread == null) {
                    throw new IllegalArgumentException("Process has no app thread");
                } else {
                    if (app.trimMemoryLevel >= level) {
                        throw new IllegalArgumentException("Unable to set a higher trim level than current level");
                    }
                    if (level >= 20 && app.getCurProcState() <= 7) {
                        throw new IllegalArgumentException("Unable to set a background trim level on a foreground process");
                    }
                    app.thread.scheduleTrimMemory(level);
                    app.trimMemoryLevel = level;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchProcessesChanged() {
        int N;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                N = this.mPendingProcessChanges.size();
                if (this.mActiveProcessChanges.length < N) {
                    this.mActiveProcessChanges = new ProcessChangeItem[N];
                }
                this.mPendingProcessChanges.toArray(this.mActiveProcessChanges);
                this.mPendingProcessChanges.clear();
                if (ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS) {
                    Slog.i("ActivityManager", "*** Delivering " + N + " process changes");
                }
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        int i = this.mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            IProcessObserver observer = this.mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                for (int j = 0; j < N; j++) {
                    try {
                        ProcessChangeItem item = this.mActiveProcessChanges[j];
                        if ((item.changes & 1) != 0) {
                            if (ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS) {
                                Slog.i("ActivityManager", "ACTIVITIES CHANGED pid=" + item.pid + " uid=" + item.uid + ": " + item.foregroundActivities);
                            }
                            observer.onForegroundActivitiesChanged(item.pid, item.uid, item.foregroundActivities);
                        }
                        if ((item.changes & 2) != 0) {
                            if (ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS) {
                                Slog.i("ActivityManager", "FOREGROUND SERVICES CHANGED pid=" + item.pid + " uid=" + item.uid + ": " + item.foregroundServiceTypes);
                            }
                            observer.onForegroundServicesChanged(item.pid, item.uid, item.foregroundServiceTypes);
                        }
                    } catch (RemoteException e) {
                    }
                }
            }
        }
        this.mProcessObservers.finishBroadcast();
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                for (int j2 = 0; j2 < N; j2++) {
                    this.mAvailProcessChanges.add(this.mActiveProcessChanges[j2]);
                }
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public ProcessChangeItem enqueueProcessChangeItemLocked(int pid, int uid) {
        int i = this.mPendingProcessChanges.size() - 1;
        ProcessChangeItem item = null;
        while (true) {
            if (i < 0) {
                break;
            }
            ProcessChangeItem item2 = this.mPendingProcessChanges.get(i);
            item = item2;
            if (item.pid == pid) {
                if (ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS) {
                    Slog.i("ActivityManager", "Re-using existing item: " + item);
                }
            } else {
                i--;
            }
        }
        if (i < 0) {
            int NA = this.mAvailProcessChanges.size();
            if (NA > 0) {
                ProcessChangeItem item3 = this.mAvailProcessChanges.remove(NA - 1);
                item = item3;
                if (ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS) {
                    Slog.i("ActivityManager", "Retrieving available item: " + item);
                }
            } else {
                item = new ProcessChangeItem();
                if (ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS) {
                    Slog.i("ActivityManager", "Allocating new item: " + item);
                }
            }
            item.changes = 0;
            item.pid = pid;
            item.uid = uid;
            if (this.mPendingProcessChanges.size() == 0) {
                if (ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS) {
                    Slog.i("ActivityManager", "*** Enqueueing dispatch processes changed!");
                }
                this.mUiHandler.obtainMessage(31).sendToTarget();
            }
            this.mPendingProcessChanges.add(item);
        }
        return item;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchProcessDied(int pid, int uid) {
        int i = this.mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            IProcessObserver observer = this.mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    observer.onProcessDied(pid, uid);
                } catch (RemoteException e) {
                }
            }
        }
        this.mProcessObservers.finishBroadcast();
    }

    @VisibleForTesting
    void dispatchUidsChanged() {
        int N;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                N = this.mPendingUidChanges.size();
                if (this.mActiveUidChanges.length < N) {
                    this.mActiveUidChanges = new UidRecord.ChangeItem[N];
                }
                for (int i = 0; i < N; i++) {
                    UidRecord.ChangeItem change = this.mPendingUidChanges.get(i);
                    this.mActiveUidChanges[i] = change;
                    if (change.uidRecord != null) {
                        change.uidRecord.pendingChange = null;
                        change.uidRecord = null;
                    }
                }
                this.mPendingUidChanges.clear();
                if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                    Slog.i("ActivityManager", "*** Delivering " + N + " uid changes");
                }
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        this.mUidChangeDispatchCount += N;
        int i2 = this.mUidObservers.beginBroadcast();
        while (i2 > 0) {
            i2--;
            dispatchUidsChangedForObserver(this.mUidObservers.getBroadcastItem(i2), (UidObserverRegistration) this.mUidObservers.getBroadcastCookie(i2), N);
        }
        this.mUidObservers.finishBroadcast();
        if (this.mUidObservers.getRegisteredCallbackCount() > 0) {
            for (int j = 0; j < N; j++) {
                UidRecord.ChangeItem item = this.mActiveUidChanges[j];
                if ((item.change & 1) != 0) {
                    this.mValidateUids.remove(item.uid);
                } else {
                    UidRecord validateUid = this.mValidateUids.get(item.uid);
                    if (validateUid == null) {
                        validateUid = new UidRecord(item.uid);
                        this.mValidateUids.put(item.uid, validateUid);
                    }
                    if ((item.change & 2) != 0) {
                        validateUid.idle = true;
                    } else if ((item.change & 4) != 0) {
                        validateUid.idle = false;
                    }
                    int i3 = item.processState;
                    validateUid.setProcState = i3;
                    validateUid.setCurProcState(i3);
                    validateUid.lastDispatchedProcStateSeq = item.procStateSeq;
                }
            }
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                for (int j2 = 0; j2 < N; j2++) {
                    this.mAvailUidChanges.add(this.mActiveUidChanges[j2]);
                }
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
    }

    private void dispatchUidsChangedForObserver(IUidObserver observer, UidObserverRegistration reg, int changesSize) {
        if (observer == null) {
            return;
        }
        for (int j = 0; j < changesSize; j++) {
            try {
                UidRecord.ChangeItem item = this.mActiveUidChanges[j];
                int change = item.change;
                if (change != 0 || (reg.which & 1) != 0) {
                    long start = SystemClock.uptimeMillis();
                    if ((change & 2) != 0) {
                        if ((reg.which & 4) != 0) {
                            if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                                Slog.i("ActivityManager", "UID idle uid=" + item.uid);
                            }
                            observer.onUidIdle(item.uid, item.ephemeral);
                        }
                    } else if ((change & 4) != 0 && (reg.which & 8) != 0) {
                        if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                            Slog.i("ActivityManager", "UID active uid=" + item.uid);
                        }
                        observer.onUidActive(item.uid);
                    }
                    if ((reg.which & 16) != 0) {
                        if ((change & 8) != 0) {
                            if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                                Slog.i("ActivityManager", "UID cached uid=" + item.uid);
                            }
                            observer.onUidCachedChanged(item.uid, true);
                        } else if ((change & 16) != 0) {
                            if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                                Slog.i("ActivityManager", "UID active uid=" + item.uid);
                            }
                            observer.onUidCachedChanged(item.uid, false);
                        }
                    }
                    if ((change & 1) == 0) {
                        if ((reg.which & 1) != 0) {
                            if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                                Slog.i("ActivityManager", "UID CHANGED uid=" + item.uid + ": " + item.processState);
                            }
                            boolean doReport = true;
                            if (reg.cutpoint >= 0) {
                                int lastState = reg.lastProcStates.get(item.uid, -1);
                                if (lastState != -1) {
                                    boolean lastAboveCut = lastState <= reg.cutpoint;
                                    boolean newAboveCut = item.processState <= reg.cutpoint;
                                    doReport = lastAboveCut != newAboveCut;
                                } else {
                                    doReport = item.processState != 21;
                                }
                            }
                            if (doReport) {
                                if (reg.lastProcStates != null) {
                                    reg.lastProcStates.put(item.uid, item.processState);
                                }
                                observer.onUidStateChanged(item.uid, item.processState, item.procStateSeq);
                            }
                        }
                    } else {
                        if ((reg.which & 2) != 0) {
                            if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                                Slog.i("ActivityManager", "UID gone uid=" + item.uid);
                            }
                            observer.onUidGone(item.uid, item.ephemeral);
                        }
                        if (reg.lastProcStates != null) {
                            reg.lastProcStates.delete(item.uid);
                        }
                    }
                    int duration = (int) (SystemClock.uptimeMillis() - start);
                    if (reg.mMaxDispatchTime < duration) {
                        reg.mMaxDispatchTime = duration;
                    }
                    if (duration >= 20) {
                        reg.mSlowDispatchCount++;
                    }
                }
            } catch (RemoteException e) {
                return;
            }
        }
    }

    void dispatchOomAdjObserver(String msg) {
        OomAdjObserver observer;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                observer = this.mCurOomAdjObserver;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        if (observer != null) {
            observer.onOomAdjMessage(msg);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOomAdjObserver(int uid, OomAdjObserver observer) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mCurOomAdjUid = uid;
                this.mCurOomAdjObserver = observer;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearOomAdjObserver() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mCurOomAdjUid = -1;
                this.mCurOomAdjObserver = null;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportOomAdjMessageLocked(String tag, String msg) {
        Slog.d(tag, msg);
        if (this.mCurOomAdjObserver != null) {
            this.mUiHandler.obtainMessage(70, msg).sendToTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportUidInfoMessageLocked(String tag, String msg, int uid) {
        Slog.i("ActivityManager", msg);
        if (this.mCurOomAdjObserver != null && uid == this.mCurOomAdjUid) {
            this.mUiHandler.obtainMessage(70, msg).sendToTarget();
        }
    }

    public int startActivity(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions) {
        return this.mActivityTaskManager.startActivity(caller, callingPackage, intent, resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions);
    }

    public int startXpApp(String pkgName, Intent intent) {
        return 0;
    }

    public final int startActivityAsUser(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        return this.mActivityTaskManager.startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        return this.mActivityTaskManager.startActivityAndWait(caller, callingPackage, intent, resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions, userId);
    }

    public final int startActivityFromRecents(int taskId, Bundle bOptions) {
        return this.mActivityTaskManager.startActivityFromRecents(taskId, bOptions);
    }

    public void startRecentsActivity(Intent intent, IAssistDataReceiver assistDataReceiver, IRecentsAnimationRunner recentsAnimationRunner) {
        this.mActivityTaskManager.startRecentsActivity(intent, assistDataReceiver, recentsAnimationRunner);
    }

    public void cancelRecentsAnimation(boolean restoreHomeStackPosition) {
        this.mActivityTaskManager.cancelRecentsAnimation(restoreHomeStackPosition);
    }

    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData, int finishTask) {
        return this.mActivityTaskManager.finishActivity(token, resultCode, resultData, finishTask);
    }

    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        this.mActivityTaskManager.setRequestedOrientation(token, requestedOrientation);
    }

    public final void finishHeavyWeightApp() {
        if (checkCallingPermission("android.permission.FORCE_STOP_PACKAGES") != 0) {
            String msg = "Permission Denial: finishHeavyWeightApp() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.FORCE_STOP_PACKAGES";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        this.mAtmInternal.finishHeavyWeightApp();
    }

    public void crashApplication(int uid, int initialPid, String packageName, int userId, String message, boolean force) {
        if (checkCallingPermission("android.permission.FORCE_STOP_PACKAGES") != 0) {
            String msg = "Permission Denial: crashApplication() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.FORCE_STOP_PACKAGES";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mAppErrors.scheduleAppCrashLocked(uid, initialPid, packageName, userId, message, force);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final void handleAppDiedLocked(final ProcessRecord app, boolean restarting, boolean allowRestart) {
        int pid = app.pid;
        boolean kept = cleanUpApplicationRecordLocked(app, restarting, allowRestart, -1, false);
        if (!kept && !restarting) {
            removeLruProcessLocked(app);
            if (pid > 0) {
                ProcessList.remove(pid);
            }
        }
        if (this.mProfileData.getProfileProc() == app) {
            clearProfilerLocked();
        }
        this.mAtmInternal.handleAppDied(app.getWindowProcessController(), restarting, new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityManagerService$2afaFERxNQEnSdevJxY5plp1fS4
            @Override // java.lang.Runnable
            public final void run() {
                ActivityManagerService.this.lambda$handleAppDiedLocked$0$ActivityManagerService(app);
            }
        });
    }

    public /* synthetic */ void lambda$handleAppDiedLocked$0$ActivityManagerService(ProcessRecord app) {
        Slog.w("ActivityManager", "Crash of app " + app.processName + " running instrumentation " + app.getActiveInstrumentation().mClass);
        Bundle info = new Bundle();
        info.putString("shortMsg", "Process crashed.");
        finishInstrumentationLocked(app, 0, info);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProcessRecord getRecordForAppLocked(IApplicationThread thread) {
        if (thread == null) {
            return null;
        }
        ProcessRecord record = this.mProcessList.getLRURecordForAppLocked(thread);
        if (record != null) {
            return record;
        }
        IBinder threadBinder = thread.asBinder();
        ArrayMap<String, SparseArray<ProcessRecord>> pmap = this.mProcessList.mProcessNames.getMap();
        for (int i = pmap.size() - 1; i >= 0; i--) {
            SparseArray<ProcessRecord> procs = pmap.valueAt(i);
            for (int j = procs.size() - 1; j >= 0; j--) {
                ProcessRecord proc = procs.valueAt(j);
                if (proc.thread != null && proc.thread.asBinder() == threadBinder) {
                    Slog.wtf("ActivityManager", "getRecordForApp: exists in name list but not in LRU list: " + proc);
                    return proc;
                }
            }
        }
        return null;
    }

    final void doLowMemReportIfNeededLocked(ProcessRecord dyingProc) {
        ArrayList<ProcessMemInfo> memInfos;
        if (!this.mProcessList.haveBackgroundProcessLocked()) {
            boolean doReport = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (doReport) {
                long now = SystemClock.uptimeMillis();
                if (now < this.mLastMemUsageReportTime + BackupAgentTimeoutParameters.DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS) {
                    doReport = false;
                } else {
                    this.mLastMemUsageReportTime = now;
                }
            }
            if (doReport) {
                memInfos = new ArrayList<>(this.mProcessList.getLruSizeLocked());
            } else {
                memInfos = null;
            }
            EventLog.writeEvent((int) EventLogTags.AM_LOW_MEMORY, this.mProcessList.getLruSizeLocked());
            long now2 = SystemClock.uptimeMillis();
            for (int i = this.mProcessList.mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord rec = this.mProcessList.mLruProcesses.get(i);
                if (rec != dyingProc && rec.thread != null) {
                    if (doReport) {
                        memInfos.add(new ProcessMemInfo(rec.processName, rec.pid, rec.setAdj, rec.setProcState, rec.adjType, rec.makeAdjReason()));
                    }
                    if (rec.lastLowMemory + this.mConstants.GC_MIN_INTERVAL <= now2) {
                        if (rec.setAdj <= 400) {
                            rec.lastRequestedGc = 0L;
                        } else {
                            rec.lastRequestedGc = rec.lastLowMemory;
                        }
                        rec.reportLowMemory = true;
                        rec.lastLowMemory = now2;
                        this.mProcessesToGc.remove(rec);
                        addProcessToGcListLocked(rec);
                    }
                }
            }
            if (doReport) {
                Message msg = this.mHandler.obtainMessage(33, memInfos);
                this.mHandler.sendMessage(msg);
            }
            scheduleAppGcsLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final void appDiedLocked(ProcessRecord app) {
        appDiedLocked(app, app.pid, app.thread, false);
    }

    @GuardedBy({"this"})
    final void appDiedLocked(ProcessRecord app, int pid, IApplicationThread thread, boolean fromBinderDied) {
        synchronized (this.mPidsSelfLocked) {
            ProcessRecord curProc = this.mPidsSelfLocked.get(pid);
            if (curProc != app) {
                Slog.w("ActivityManager", "Spurious death for " + app + ", curProc for " + pid + ": " + curProc);
                return;
            }
            BatteryStatsImpl stats = this.mBatteryStatsService.getActiveStatistics();
            synchronized (stats) {
                stats.noteProcessDiedLocked(app.info.uid, pid);
            }
            if (!app.killed) {
                if (!fromBinderDied) {
                    Process.killProcessQuiet(pid);
                }
                Slog.i("ActivityManager", "Kill ProcessGroup for app died pid = " + pid);
                ProcessList.killProcessGroup(app.uid, pid);
                app.killed = true;
            }
            if (app.pid == pid && app.thread != null && app.thread.asBinder() == thread.asBinder()) {
                boolean doLowMem = app.getActiveInstrumentation() == null;
                boolean doOomAdj = doLowMem;
                if (!app.killedByAm) {
                    reportUidInfoMessageLocked("ActivityManager", "Process " + app.processName + " (pid " + pid + ") has died: " + ProcessList.makeOomAdjString(app.setAdj, true) + " " + ProcessList.makeProcStateString(app.setProcState), app.info.uid);
                    this.mAllowLowerMemLevel = true;
                } else {
                    this.mAllowLowerMemLevel = false;
                    doLowMem = false;
                }
                EventLog.writeEvent((int) EventLogTags.AM_PROC_DIED, Integer.valueOf(app.userId), Integer.valueOf(app.pid), app.processName, Integer.valueOf(app.setAdj), Integer.valueOf(app.setProcState));
                if (ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                    Slog.v("ActivityManager", "Dying app: " + app + ", pid: " + pid + ", thread: " + thread.asBinder());
                }
                handleAppDiedLocked(app, false, true);
                if (doOomAdj) {
                    updateOomAdjLocked("updateOomAdj_processEnd");
                }
                if (doLowMem) {
                    doLowMemReportIfNeededLocked(app);
                }
            } else if (app.pid != pid) {
                reportUidInfoMessageLocked("ActivityManager", "Process " + app.processName + " (pid " + pid + ") has died and restarted (pid " + app.pid + ").", app.info.uid);
                EventLog.writeEvent((int) EventLogTags.AM_PROC_DIED, Integer.valueOf(app.userId), Integer.valueOf(app.pid), app.processName);
            } else if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                Slog.d("ActivityManager", "Received spurious death notification for thread " + thread.asBinder());
            }
            if (!MemoryStatUtil.hasMemcg()) {
                StatsLog.write(65, SystemClock.elapsedRealtime());
            }
        }
    }

    public static File dumpStackTraces(ArrayList<Integer> firstPids, ProcessCpuTracker processCpuTracker, SparseArray<Boolean> lastPids, ArrayList<Integer> nativePids) {
        ArrayList<Integer> extraPids = null;
        Slog.i("ActivityManager", "dumpStackTraces pids=" + lastPids + " nativepids=" + nativePids);
        if (processCpuTracker != null) {
            processCpuTracker.init();
            try {
                Thread.sleep(NETWORK_ACCESS_TIMEOUT_DEFAULT_MS);
            } catch (InterruptedException e) {
            }
            processCpuTracker.update();
            int N = processCpuTracker.countWorkingStats();
            extraPids = new ArrayList<>();
            for (int i = 0; i < N && extraPids.size() < 5; i++) {
                ProcessCpuTracker.Stats stats = processCpuTracker.getWorkingStats(i);
                if (lastPids.indexOfKey(stats.pid) >= 0) {
                    extraPids.add(Integer.valueOf(stats.pid));
                } else {
                    Slog.i("ActivityManager", "Skipping next CPU consuming process, not a java proc: " + stats.pid);
                }
            }
        }
        File tracesDir = new File(ANR_TRACE_DIR);
        maybePruneOldTraces(tracesDir);
        File tracesFile = createAnrDumpFile(tracesDir);
        if (tracesFile == null) {
            return null;
        }
        dumpStackTraces(tracesFile.getAbsolutePath(), firstPids, nativePids, extraPids);
        return tracesFile;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static synchronized File createAnrDumpFile(File tracesDir) {
        synchronized (ActivityManagerService.class) {
            boostPriorityForLockedSection();
            if (sAnrFileDateFormat == null) {
                sAnrFileDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS");
            }
            String formattedDate = sAnrFileDateFormat.format(new Date());
            File anrFile = new File(tracesDir, "anr_" + formattedDate);
            try {
            } catch (IOException ioe) {
                Slog.w("ActivityManager", "Exception creating ANR dump file:", ioe);
            }
            if (anrFile.createNewFile()) {
                FileUtils.setPermissions(anrFile.getAbsolutePath(), 384, -1, -1);
                resetPriorityAfterLockedSection();
                return anrFile;
            }
            Slog.w("ActivityManager", "Unable to create ANR dump file: createNewFile failed");
            resetPriorityAfterLockedSection();
            return null;
        }
    }

    private static void maybePruneOldTraces(File tracesDir) {
        File[] files = tracesDir.listFiles();
        if (files == null) {
            return;
        }
        int max = SystemProperties.getInt("tombstoned.max_anr_count", 64);
        long now = System.currentTimeMillis();
        Arrays.sort(files, Comparator.comparingLong(new ToLongFunction() { // from class: com.android.server.am.-$$Lambda$yk1Ms9fVlF6PvprMwF2rru-dw4Q
            @Override // java.util.function.ToLongFunction
            public final long applyAsLong(Object obj) {
                return ((File) obj).lastModified();
            }
        }).reversed());
        for (int i = 0; i < files.length; i++) {
            if ((i > max || now - files[i].lastModified() > 86400000) && !files[i].delete()) {
                Slog.w("ActivityManager", "Unable to prune stale trace file: " + files[i]);
            }
        }
    }

    private static long dumpJavaTracesTombstoned(int pid, String fileName, long timeoutMs) {
        long timeStart = SystemClock.elapsedRealtime();
        boolean javaSuccess = Debug.dumpJavaBacktraceToFileTimeout(pid, fileName, (int) (timeoutMs / 1000));
        if (javaSuccess) {
            try {
                long size = new File(fileName).length();
                if (size < 100) {
                    Slog.w("ActivityManager", "Successfully created Java ANR file is empty!");
                    javaSuccess = false;
                }
            } catch (Exception e) {
                Slog.w("ActivityManager", "Unable to get ANR file size", e);
                javaSuccess = false;
            }
        }
        if (!javaSuccess) {
            Slog.w("ActivityManager", "Dumping Java threads failed, initiating native stack dump.");
            if (!Debug.dumpNativeBacktraceToFileTimeout(pid, fileName, 2)) {
                Slog.w("ActivityManager", "Native stack dump failed!");
            }
        }
        return SystemClock.elapsedRealtime() - timeStart;
    }

    public static void dumpStackTraces(String tracesFile, ArrayList<Integer> firstPids, ArrayList<Integer> nativePids, ArrayList<Integer> extraPids) {
        Slog.i("ActivityManager", "Dumping to " + tracesFile);
        long remainingTime = 20000;
        if (firstPids != null) {
            int num = firstPids.size();
            for (int i = 0; i < num; i++) {
                Slog.i("ActivityManager", "Collecting stacks for pid " + firstPids.get(i));
                long timeTaken = dumpJavaTracesTombstoned(firstPids.get(i).intValue(), tracesFile, remainingTime);
                remainingTime -= timeTaken;
                if (remainingTime <= 0) {
                    Slog.e("ActivityManager", "Aborting stack trace dump (current firstPid=" + firstPids.get(i) + "); deadline exceeded.");
                    return;
                }
            }
        }
        if (nativePids != null) {
            Iterator<Integer> it = nativePids.iterator();
            while (it.hasNext()) {
                int pid = it.next().intValue();
                Slog.i("ActivityManager", "Collecting stacks for native pid " + pid);
                long nativeDumpTimeoutMs = Math.min(2000L, remainingTime);
                long start = SystemClock.elapsedRealtime();
                Debug.dumpNativeBacktraceToFileTimeout(pid, tracesFile, (int) (nativeDumpTimeoutMs / 1000));
                long timeTaken2 = SystemClock.elapsedRealtime() - start;
                remainingTime -= timeTaken2;
                if (remainingTime <= 0) {
                    Slog.e("ActivityManager", "Aborting stack trace dump (current native pid=" + pid + "); deadline exceeded.");
                    return;
                }
            }
        }
        if (extraPids != null) {
            Iterator<Integer> it2 = extraPids.iterator();
            while (it2.hasNext()) {
                int pid2 = it2.next().intValue();
                Slog.i("ActivityManager", "Collecting stacks for extra pid " + pid2);
                long timeTaken3 = dumpJavaTracesTombstoned(pid2, tracesFile, remainingTime);
                remainingTime -= timeTaken3;
                if (remainingTime <= 0) {
                    Slog.e("ActivityManager", "Aborting stack trace dump (current extra pid=" + pid2 + "); deadline exceeded.");
                    return;
                }
            }
        }
        Slog.i("ActivityManager", "Done dumping");
    }

    public boolean clearApplicationUserData(String packageName, boolean keepState, final IPackageDataObserver observer, int userId) {
        boolean z;
        enforceNotIsolatedCaller("clearApplicationUserData");
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int resolvedUserId = this.mUserController.handleIncomingUser(pid, uid, userId, false, 2, "clearApplicationUserData", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            try {
                synchronized (this) {
                    try {
                        boostPriorityForLockedSection();
                        if (getPackageManagerInternalLocked().isPackageDataProtected(resolvedUserId, packageName)) {
                            throw new SecurityException("Cannot clear data for a protected package: " + packageName);
                        }
                        ApplicationInfo applicationInfo = null;
                        try {
                            try {
                                applicationInfo = pm.getApplicationInfo(packageName, 8192, resolvedUserId);
                            } catch (RemoteException e) {
                            }
                            final ApplicationInfo appInfo = applicationInfo;
                            boolean clearingOwnUidData = appInfo != null && appInfo.uid == uid;
                            if (!clearingOwnUidData && checkComponentPermission("android.permission.CLEAR_APP_USER_DATA", pid, uid, -1, true) != 0) {
                                throw new SecurityException("PID " + pid + " does not have permission android.permission.CLEAR_APP_USER_DATA to clear data of package " + packageName);
                            }
                            boolean hasInstantMetadata = getPackageManagerInternalLocked().hasInstantApplicationMetadata(packageName, resolvedUserId);
                            boolean isUninstalledAppWithoutInstantMetadata = appInfo == null && !hasInstantMetadata;
                            boolean isInstantApp = (appInfo != null && appInfo.isInstantApp()) || hasInstantMetadata;
                            boolean canAccessInstantApps = checkComponentPermission("android.permission.ACCESS_INSTANT_APPS", pid, uid, -1, true) == 0;
                            if (isUninstalledAppWithoutInstantMetadata) {
                                z = false;
                            } else if (!isInstantApp || canAccessInstantApps) {
                                if (appInfo != null) {
                                    forceStopPackageLocked(packageName, appInfo.uid, "clear data");
                                    this.mAtmInternal.removeRecentTasksByPackageName(packageName, resolvedUserId);
                                }
                                try {
                                    try {
                                        resetPriorityAfterLockedSection();
                                        final boolean z2 = isInstantApp;
                                        try {
                                            try {
                                                pm.clearApplicationUserData(packageName, new IPackageDataObserver.Stub() { // from class: com.android.server.am.ActivityManagerService.8
                                                    public void onRemoveCompleted(String packageName2, boolean succeeded) throws RemoteException {
                                                        if (appInfo != null) {
                                                            synchronized (ActivityManagerService.this) {
                                                                try {
                                                                    ActivityManagerService.boostPriorityForLockedSection();
                                                                    ActivityManagerService.this.finishForceStopPackageLocked(packageName2, appInfo.uid);
                                                                } catch (Throwable th) {
                                                                    ActivityManagerService.resetPriorityAfterLockedSection();
                                                                    throw th;
                                                                }
                                                            }
                                                            ActivityManagerService.resetPriorityAfterLockedSection();
                                                        }
                                                        Intent intent = new Intent("android.intent.action.PACKAGE_DATA_CLEARED", Uri.fromParts("package", packageName2, null));
                                                        intent.addFlags(16777216);
                                                        ApplicationInfo applicationInfo2 = appInfo;
                                                        intent.putExtra("android.intent.extra.UID", applicationInfo2 != null ? applicationInfo2.uid : -1);
                                                        intent.putExtra("android.intent.extra.user_handle", resolvedUserId);
                                                        if (!z2) {
                                                            ActivityManagerService.this.broadcastIntentInPackage(PackageManagerService.PLATFORM_PACKAGE_NAME, 1000, uid, pid, intent, null, null, 0, null, null, null, null, false, false, resolvedUserId, false);
                                                        } else {
                                                            intent.putExtra("android.intent.extra.PACKAGE_NAME", packageName2);
                                                            ActivityManagerService.this.broadcastIntentInPackage(PackageManagerService.PLATFORM_PACKAGE_NAME, 1000, uid, pid, intent, null, null, 0, null, null, "android.permission.ACCESS_INSTANT_APPS", null, false, false, resolvedUserId, false);
                                                        }
                                                        IPackageDataObserver iPackageDataObserver = observer;
                                                        if (iPackageDataObserver != null) {
                                                            iPackageDataObserver.onRemoveCompleted(packageName2, succeeded);
                                                        }
                                                    }
                                                }, resolvedUserId);
                                                if (appInfo != null) {
                                                    if (!keepState) {
                                                        this.mUgmInternal.removeUriPermissionsForPackage(packageName, resolvedUserId, true, false);
                                                        INotificationManager inm = NotificationManager.getService();
                                                        inm.clearData(packageName, appInfo.uid, uid == appInfo.uid);
                                                    }
                                                    JobSchedulerInternal js = (JobSchedulerInternal) LocalServices.getService(JobSchedulerInternal.class);
                                                    js.cancelJobsForUid(appInfo.uid, "clear data");
                                                    AlarmManagerInternal ami = (AlarmManagerInternal) LocalServices.getService(AlarmManagerInternal.class);
                                                    ami.removeAlarmsForUid(appInfo.uid);
                                                }
                                            } catch (RemoteException e2) {
                                            }
                                            Binder.restoreCallingIdentity(callingId);
                                            return true;
                                        } catch (Throwable th) {
                                            th = th;
                                            Binder.restoreCallingIdentity(callingId);
                                            throw th;
                                        }
                                    } catch (Throwable th2) {
                                        th = th2;
                                    }
                                } catch (Throwable th3) {
                                    th = th3;
                                    try {
                                        resetPriorityAfterLockedSection();
                                        throw th;
                                    } catch (Throwable th4) {
                                        th = th4;
                                        Binder.restoreCallingIdentity(callingId);
                                        throw th;
                                    }
                                }
                            } else {
                                z = false;
                            }
                            try {
                                Slog.w("ActivityManager", "Invalid packageName: " + packageName);
                                boolean z3 = z;
                                if (observer != null) {
                                    try {
                                        observer.onRemoveCompleted(packageName, z3);
                                    } catch (RemoteException e3) {
                                        Slog.i("ActivityManager", "Observer no longer exists.");
                                    }
                                }
                                resetPriorityAfterLockedSection();
                                Binder.restoreCallingIdentity(callingId);
                                return false;
                            } catch (Throwable th5) {
                                th = th5;
                                resetPriorityAfterLockedSection();
                                throw th;
                            }
                        } catch (Throwable th6) {
                            th = th6;
                            resetPriorityAfterLockedSection();
                            throw th;
                        }
                    } catch (Throwable th7) {
                        th = th7;
                    }
                }
            } catch (Throwable th8) {
                th = th8;
            }
        } catch (Throwable th9) {
            th = th9;
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:48:? -> B:35:0x00cd). Please submit an issue!!! */
    public void killBackgroundProcesses(String packageName, int userId) {
        int appId;
        if (checkCallingPermission("android.permission.KILL_BACKGROUND_PROCESSES") != 0 && checkCallingPermission("android.permission.RESTART_PACKAGES") != 0) {
            String msg = "Permission Denial: killBackgroundProcesses() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.KILL_BACKGROUND_PROCESSES";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        int[] userIds = this.mUserController.expandUserId(this.mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, 2, "killBackgroundProcesses", null));
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            for (int targetUserId : userIds) {
                try {
                    int appId2 = UserHandle.getAppId(pm.getPackageUid(packageName, 268435456, targetUserId));
                    appId = appId2;
                } catch (RemoteException e) {
                    appId = -1;
                }
                if (appId == -1) {
                    Slog.w("ActivityManager", "Invalid packageName: " + packageName);
                    return;
                }
                synchronized (this) {
                    try {
                        boostPriorityForLockedSection();
                        try {
                            this.mProcessList.killPackageProcessesLocked(packageName, appId, targetUserId, SystemService.PHASE_SYSTEM_SERVICES_READY, "kill background");
                        } catch (Throwable th) {
                            th = th;
                            resetPriorityAfterLockedSection();
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                        resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void killAllBackgroundProcesses() {
        if (checkCallingPermission("android.permission.KILL_BACKGROUND_PROCESSES") != 0) {
            String msg = "Permission Denial: killAllBackgroundProcesses() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.KILL_BACKGROUND_PROCESSES";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                this.mAllowLowerMemLevel = true;
                this.mProcessList.killPackageProcessesLocked(null, -1, -1, 900, "kill all background");
                doLowMemReportIfNeededLocked(null);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    void killAllBackgroundProcessesExcept(int minTargetSdk, int maxProcState) {
        if (checkCallingPermission("android.permission.KILL_BACKGROUND_PROCESSES") != 0) {
            String msg = "Permission Denial: killAllBackgroundProcessesExcept() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.KILL_BACKGROUND_PROCESSES";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                this.mProcessList.killAllBackgroundProcessesExceptLocked(minTargetSdk, maxProcState);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void forceStopPackage(String packageName, int userId) {
        boolean z;
        if (checkCallingPermission("android.permission.FORCE_STOP_PACKAGES") != 0) {
            String msg = "Permission Denial: forceStopPackage() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.FORCE_STOP_PACKAGES";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        int callingPid = Binder.getCallingPid();
        int userId2 = this.mUserController.handleIncomingUser(callingPid, Binder.getCallingUid(), userId, true, 2, "forceStopPackage", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized (this) {
                boostPriorityForLockedSection();
                int i = -1;
                boolean z2 = true;
                int[] users = userId2 == -1 ? this.mUserController.getUsers() : new int[]{userId2};
                int length = users.length;
                int i2 = 0;
                while (i2 < length) {
                    int user = users[i2];
                    if (getPackageManagerInternalLocked().isPackageStateProtected(packageName, user)) {
                        Slog.w("ActivityManager", "Ignoring request to force stop protected package " + packageName + " u" + user);
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    int pkgUid = -1;
                    try {
                        pkgUid = pm.getPackageUid(packageName, 268435456, user);
                    } catch (RemoteException e) {
                    }
                    if (pkgUid == i) {
                        Slog.w("ActivityManager", "Invalid packageName: " + packageName);
                        z = false;
                    } else {
                        try {
                            pm.setPackageStoppedState(packageName, z2, user);
                        } catch (RemoteException e2) {
                        } catch (IllegalArgumentException e3) {
                            Slog.w("ActivityManager", "Failed trying to unstop package " + packageName + ": " + e3);
                        }
                        z = false;
                        if (this.mUserController.isUserRunning(user, 0)) {
                            forceStopPackageLocked(packageName, pkgUid, "from pid " + callingPid);
                            finishForceStopPackageLocked(packageName, pkgUid);
                        }
                    }
                    i2++;
                    i = -1;
                    z2 = true;
                }
                resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void addPackageDependency(String packageName) {
        ProcessRecord proc;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                int callingPid = Binder.getCallingPid();
                if (callingPid == Process.myPid()) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                synchronized (this.mPidsSelfLocked) {
                    proc = this.mPidsSelfLocked.get(Binder.getCallingPid());
                }
                if (proc != null) {
                    if (proc.pkgDeps == null) {
                        proc.pkgDeps = new ArraySet<>(1);
                    }
                    proc.pkgDeps.add(packageName);
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void killApplication(String pkg, int appId, int userId, String reason) {
        if (pkg == null) {
            return;
        }
        if (appId < 0) {
            Slog.w("ActivityManager", "Invalid appid specified for pkg : " + pkg);
            return;
        }
        int callerUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callerUid) == 1000) {
            Message msg = this.mHandler.obtainMessage(22);
            msg.arg1 = appId;
            msg.arg2 = userId;
            Bundle bundle = new Bundle();
            bundle.putString("pkg", pkg);
            bundle.putString(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY, reason);
            msg.obj = bundle;
            this.mHandler.sendMessage(msg);
            return;
        }
        throw new SecurityException(callerUid + " cannot kill pkg: " + pkg);
    }

    public void closeSystemDialogs(String reason) {
        this.mAtmInternal.closeSystemDialogs(reason);
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:129:? -> B:89:0x022f). Please submit an issue!!! */
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        ProcessRecord proc;
        int i;
        int oomAdj;
        long now;
        int targetUserId;
        long lastNow;
        int callingPid;
        int callingUid;
        int callingUserId;
        boolean allUsers;
        int[] iArr = pids;
        enforceNotIsolatedCaller("getProcessMemoryInfo");
        long now2 = SystemClock.uptimeMillis();
        long lastNow2 = now2 - this.mConstants.MEMORY_INFO_THROTTLE_TIME;
        int callingPid2 = Binder.getCallingPid();
        int callingUid2 = Binder.getCallingUid();
        int callingUserId2 = UserHandle.getUserId(callingUid2);
        boolean allUsers2 = ActivityManager.checkUidPermission("android.permission.INTERACT_ACROSS_USERS_FULL", callingUid2) == 0;
        boolean allUids = this.mAtmInternal.isGetTasksAllowed("getProcessMemoryInfo", callingPid2, callingUid2);
        Debug.MemoryInfo[] infos = new Debug.MemoryInfo[iArr.length];
        int i2 = iArr.length - 1;
        while (i2 >= 0) {
            infos[i2] = new Debug.MemoryInfo();
            synchronized (this) {
                try {
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    boostPriorityForLockedSection();
                    synchronized (this.mPidsSelfLocked) {
                        try {
                            proc = this.mPidsSelfLocked.get(iArr[i2]);
                            if (proc == null) {
                                i = 0;
                            } else {
                                try {
                                    i = proc.setAdj;
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
                            }
                            oomAdj = i;
                        } catch (Throwable th4) {
                            th = th4;
                        }
                    }
                } catch (Throwable th5) {
                    th = th5;
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            int targetUid = proc != null ? proc.uid : -1;
            int targetUserId2 = proc != null ? UserHandle.getUserId(targetUid) : -1;
            if (callingUid2 == targetUid) {
                now = now2;
                targetUserId = targetUserId2;
            } else {
                if (!allUids) {
                    now = now2;
                    lastNow = lastNow2;
                    callingPid = callingPid2;
                    callingUid = callingUid2;
                    callingUserId = callingUserId2;
                    allUsers = allUsers2;
                } else if (allUsers2) {
                    now = now2;
                    targetUserId = targetUserId2;
                } else {
                    now = now2;
                    targetUserId = targetUserId2;
                    if (targetUserId != callingUserId2) {
                        lastNow = lastNow2;
                        callingPid = callingPid2;
                        callingUid = callingUid2;
                        callingUserId = callingUserId2;
                        allUsers = allUsers2;
                    }
                }
                i2--;
                iArr = pids;
                allUsers2 = allUsers;
                now2 = now;
                lastNow2 = lastNow;
                callingPid2 = callingPid;
                callingUid2 = callingUid;
                callingUserId2 = callingUserId;
            }
            if (proc != null && proc.lastMemInfoTime >= lastNow2 && proc.lastMemInfo != null) {
                infos[i2].set(proc.lastMemInfo);
                lastNow = lastNow2;
                callingPid = callingPid2;
                callingUid = callingUid2;
                callingUserId = callingUserId2;
                allUsers = allUsers2;
                i2--;
                iArr = pids;
                allUsers2 = allUsers;
                now2 = now;
                lastNow2 = lastNow;
                callingPid2 = callingPid;
                callingUid2 = callingUid;
                callingUserId2 = callingUserId;
            }
            long startTime = SystemClock.currentThreadTimeMillis();
            Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(iArr[i2], memInfo);
            long endTime = SystemClock.currentThreadTimeMillis();
            infos[i2].set(memInfo);
            if (proc != null) {
                synchronized (this) {
                    try {
                        boostPriorityForLockedSection();
                        proc.lastMemInfo = memInfo;
                        lastNow = lastNow2;
                        try {
                            long lastNow3 = SystemClock.uptimeMillis();
                            proc.lastMemInfoTime = lastNow3;
                            if (proc.thread == null) {
                                callingPid = callingPid2;
                                callingUid = callingUid2;
                                callingUserId = callingUserId2;
                                allUsers = allUsers2;
                            } else if (proc.setAdj == oomAdj) {
                                try {
                                    try {
                                        callingPid = callingPid2;
                                        callingUid = callingUid2;
                                        try {
                                            callingUserId = callingUserId2;
                                        } catch (Throwable th6) {
                                            th = th6;
                                        }
                                    } catch (Throwable th7) {
                                        th = th7;
                                    }
                                } catch (Throwable th8) {
                                    th = th8;
                                }
                                try {
                                    proc.baseProcessTracker.addPss(infos[i2].getTotalPss(), infos[i2].getTotalUss(), infos[i2].getTotalRss(), false, 4, endTime - startTime, proc.pkgList.mPkgList);
                                    int ipkg = proc.pkgList.size() - 1;
                                    while (ipkg >= 0) {
                                        ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                                        ProcessRecord proc2 = proc;
                                        boolean allUsers3 = allUsers2;
                                        long startTime2 = startTime;
                                        try {
                                            StatsLog.write(18, proc.info.uid, holder.state.getName(), holder.state.getPackage(), infos[i2].getTotalPss(), infos[i2].getTotalUss(), infos[i2].getTotalRss(), 4, endTime - startTime, holder.appVersion);
                                            ipkg--;
                                            allUsers2 = allUsers3;
                                            proc = proc2;
                                            startTime = startTime2;
                                        } catch (Throwable th9) {
                                            th = th9;
                                            resetPriorityAfterLockedSection();
                                            throw th;
                                        }
                                    }
                                    allUsers = allUsers2;
                                } catch (Throwable th10) {
                                    th = th10;
                                    resetPriorityAfterLockedSection();
                                    throw th;
                                }
                            } else {
                                callingPid = callingPid2;
                                callingUid = callingUid2;
                                callingUserId = callingUserId2;
                                allUsers = allUsers2;
                            }
                        } catch (Throwable th11) {
                            th = th11;
                        }
                    } catch (Throwable th12) {
                        th = th12;
                    }
                }
                resetPriorityAfterLockedSection();
            } else {
                lastNow = lastNow2;
                callingPid = callingPid2;
                callingUid = callingUid2;
                callingUserId = callingUserId2;
                allUsers = allUsers2;
            }
            i2--;
            iArr = pids;
            allUsers2 = allUsers;
            now2 = now;
            lastNow2 = lastNow;
            callingPid2 = callingPid;
            callingUid2 = callingUid;
            callingUserId2 = callingUserId;
        }
        return infos;
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:70:? -> B:49:0x00f6). Please submit an issue!!! */
    public long[] getProcessPss(int[] pids) {
        ProcessRecord proc;
        int i;
        int oomAdj;
        int callingPid;
        int[] iArr = pids;
        enforceNotIsolatedCaller("getProcessPss");
        int callingPid2 = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        int i2 = 0;
        boolean allUsers = ActivityManager.checkUidPermission("android.permission.INTERACT_ACROSS_USERS_FULL", callingUid) == 0;
        boolean allUids = this.mAtmInternal.isGetTasksAllowed("getProcessPss", callingPid2, callingUid);
        long[] pss = new long[iArr.length];
        int i3 = iArr.length - 1;
        while (i3 >= 0) {
            synchronized (this) {
                try {
                    try {
                        boostPriorityForLockedSection();
                        synchronized (this.mPidsSelfLocked) {
                            try {
                                proc = this.mPidsSelfLocked.get(iArr[i3]);
                                if (proc == null) {
                                    i = i2;
                                } else {
                                    try {
                                        i = proc.setAdj;
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
                                oomAdj = i;
                            } catch (Throwable th3) {
                                th = th3;
                            }
                        }
                    } catch (Throwable th4) {
                        th = th4;
                        resetPriorityAfterLockedSection();
                        throw th;
                    }
                } catch (Throwable th5) {
                    th = th5;
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            if (!allUids) {
                callingPid = callingPid2;
            } else if (!allUsers && UserHandle.getUserId(proc.uid) != userId) {
                callingPid = callingPid2;
            } else {
                long[] tmpUss = new long[3];
                long startTime = SystemClock.currentThreadTimeMillis();
                pss[i3] = Debug.getPss(iArr[i3], tmpUss, null);
                long endTime = SystemClock.currentThreadTimeMillis();
                if (proc == null) {
                    callingPid = callingPid2;
                } else {
                    synchronized (this) {
                        try {
                            boostPriorityForLockedSection();
                            if (proc.thread == null || proc.setAdj != oomAdj) {
                                callingPid = callingPid2;
                            } else {
                                proc.baseProcessTracker.addPss(pss[i3], tmpUss[i2], tmpUss[2], false, 3, endTime - startTime, proc.pkgList.mPkgList);
                                int ipkg = proc.pkgList.size() - 1;
                                while (ipkg >= 0) {
                                    ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                                    int callingPid3 = callingPid2;
                                    try {
                                        StatsLog.write(18, proc.info.uid, holder.state.getName(), holder.state.getPackage(), pss[i3], tmpUss[0], tmpUss[2], 3, endTime - startTime, holder.appVersion);
                                        ipkg--;
                                        callingPid2 = callingPid3;
                                    } catch (Throwable th6) {
                                        th = th6;
                                        resetPriorityAfterLockedSection();
                                        throw th;
                                    }
                                }
                                callingPid = callingPid2;
                            }
                        } catch (Throwable th7) {
                            th = th7;
                            resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    resetPriorityAfterLockedSection();
                }
            }
            i3--;
            iArr = pids;
            callingPid2 = callingPid;
            i2 = 0;
        }
        return pss;
    }

    public void killApplicationProcess(String processName, int uid) {
        if (processName == null) {
            return;
        }
        int callerUid = Binder.getCallingUid();
        if (callerUid == 1000) {
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    ProcessRecord app = getProcessRecordLocked(processName, uid, true);
                    if (app != null && app.thread != null) {
                        try {
                            app.thread.scheduleSuicide();
                        } catch (RemoteException e) {
                        }
                    } else {
                        Slog.w("ActivityManager", "Process/uid not found attempting kill of " + processName + " / " + uid);
                    }
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            return;
        }
        throw new SecurityException(callerUid + " cannot kill app process: " + processName);
    }

    @GuardedBy({"this"})
    private void forceStopPackageLocked(String packageName, int uid, String reason) {
        forceStopPackageLocked(packageName, UserHandle.getAppId(uid), false, false, true, false, false, UserHandle.getUserId(uid), reason);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"this"})
    public void finishForceStopPackageLocked(String packageName, int uid) {
        Intent intent = new Intent("android.intent.action.PACKAGE_RESTARTED", Uri.fromParts("package", packageName, null));
        if (!this.mProcessesReady) {
            intent.addFlags(1342177280);
        }
        intent.putExtra("android.intent.extra.UID", uid);
        intent.putExtra("android.intent.extra.user_handle", UserHandle.getUserId(uid));
        broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null, -1, null, false, false, MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.getUserId(uid));
    }

    private void cleanupDisabledPackageComponentsLocked(String packageName, int userId, String[] changedClasses) {
        Set<String> disabledClasses;
        int i;
        IPackageManager pm = AppGlobals.getPackageManager();
        if (changedClasses == null) {
            return;
        }
        int enabled = 0;
        int i2 = changedClasses.length - 1;
        int i3 = 0;
        Set<String> disabledClasses2 = null;
        while (true) {
            if (i2 < 0) {
                disabledClasses = disabledClasses2;
                i = i3;
                break;
            }
            String changedClass = changedClasses[i2];
            if (changedClass.equals(packageName)) {
                try {
                    enabled = pm.getApplicationEnabledSetting(packageName, userId != -1 ? userId : 0);
                    if (enabled != 1 && enabled != 0) {
                        r7 = 1;
                    }
                    i3 = r7;
                    if (i3 != 0) {
                        disabledClasses = null;
                        i = i3;
                        break;
                    }
                } catch (Exception e) {
                    return;
                }
            } else {
                try {
                    int enabled2 = pm.getComponentEnabledSetting(new ComponentName(packageName, changedClass), userId != -1 ? userId : 0);
                    if (enabled2 == 1 || enabled2 == 0) {
                        enabled = enabled2;
                    } else {
                        if (disabledClasses2 == null) {
                            disabledClasses2 = new ArraySet<>(changedClasses.length);
                        }
                        disabledClasses2.add(changedClass);
                        enabled = enabled2;
                    }
                } catch (Exception e2) {
                    return;
                }
            }
            i2--;
        }
        if (i != 0 || disabledClasses != null) {
            this.mAtmInternal.cleanupDisabledPackageComponents(packageName, disabledClasses, userId, this.mBooted);
            Set<String> set = disabledClasses;
            this.mServices.bringDownDisabledPackageServicesLocked(packageName, set, userId, false, true);
            ArrayList<ContentProviderRecord> providers = new ArrayList<>();
            this.mProviderMap.collectPackageProvidersLocked(packageName, set, true, false, userId, providers);
            for (int i4 = providers.size() - 1; i4 >= 0; i4--) {
                removeDyingProviderLocked(null, providers.get(i4), true);
            }
            for (int i5 = this.mBroadcastQueues.length - 1; i5 >= 0; i5--) {
                this.mBroadcastQueues[i5].cleanupDisabledPackageReceiversLocked(packageName, disabledClasses, userId, true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final boolean clearBroadcastQueueForUserLocked(int userId) {
        boolean didSomething = false;
        for (int i = this.mBroadcastQueues.length - 1; i >= 0; i--) {
            didSomething |= this.mBroadcastQueues[i].cleanupDisabledPackageReceiversLocked(null, null, userId, true);
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:15:0x0030  */
    /* JADX WARN: Removed duplicated region for block: B:26:0x008e  */
    /* JADX WARN: Removed duplicated region for block: B:27:0x009b  */
    /* JADX WARN: Removed duplicated region for block: B:30:0x00d9  */
    /* JADX WARN: Removed duplicated region for block: B:34:0x00e1  */
    /* JADX WARN: Removed duplicated region for block: B:36:0x00e5  */
    /* JADX WARN: Removed duplicated region for block: B:39:0x0102  */
    /* JADX WARN: Removed duplicated region for block: B:45:0x010e A[LOOP:0: B:43:0x010b->B:45:0x010e, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:48:0x0122  */
    /* JADX WARN: Removed duplicated region for block: B:55:0x0142  */
    /* JADX WARN: Type inference failed for: r10v1 */
    /* JADX WARN: Type inference failed for: r10v2, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r10v3 */
    /* JADX WARN: Type inference failed for: r2v10, types: [int, java.util.ArrayList] */
    /* JADX WARN: Type inference failed for: r2v17 */
    /* JADX WARN: Type inference failed for: r2v18 */
    /* JADX WARN: Type inference failed for: r2v6, types: [com.android.server.am.ProviderMap, java.util.ArrayList] */
    /* JADX WARN: Type inference failed for: r2v7, types: [boolean, java.util.ArrayList] */
    @com.android.internal.annotations.GuardedBy({"this"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public final boolean forceStopPackageLocked(java.lang.String r19, int r20, boolean r21, boolean r22, boolean r23, boolean r24, boolean r25, int r26, java.lang.String r27) {
        /*
            Method dump skipped, instructions count: 345
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.forceStopPackageLocked(java.lang.String, int, boolean, boolean, boolean, boolean, boolean, int, java.lang.String):boolean");
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"this"})
    public final void processContentProviderPublishTimedOutLocked(ProcessRecord app) {
        cleanupAppInLaunchingProvidersLocked(app, true);
        this.mProcessList.removeProcessLocked(app, false, true, "timeout publishing content providers");
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"this"})
    public final void processStartTimedOutLocked(final ProcessRecord app) {
        int pid = app.pid;
        boolean gone = this.mPidsSelfLocked.removeIfNoThread(app);
        if (gone) {
            Slog.w("ActivityManager", "Process " + app + " failed to attach");
            EventLog.writeEvent((int) EventLogTags.AM_PROCESS_START_TIMEOUT, Integer.valueOf(app.userId), Integer.valueOf(pid), Integer.valueOf(app.uid), app.processName);
            this.mProcessList.removeProcessNameLocked(app.processName, app.uid);
            this.mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());
            this.mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            cleanupAppInLaunchingProvidersLocked(app, true);
            this.mServices.processStartTimedOutLocked(app);
            app.kill("start timeout", true);
            if (app.isolated) {
                this.mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            removeLruProcessLocked(app);
            BackupRecord backupTarget = this.mBackupTargets.get(app.userId);
            if (backupTarget != null && backupTarget.app.pid == pid) {
                Slog.w("ActivityManager", "Unattached app died before backup, skipping");
                this.mHandler.post(new Runnable() { // from class: com.android.server.am.ActivityManagerService.9
                    @Override // java.lang.Runnable
                    public void run() {
                        try {
                            IBackupManager bm = IBackupManager.Stub.asInterface(ServiceManager.getService(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD));
                            bm.agentDisconnectedForUser(app.userId, app.info.packageName);
                        } catch (RemoteException e) {
                        }
                    }
                });
            }
            if (isPendingBroadcastProcessLocked(pid)) {
                Slog.w("ActivityManager", "Unattached app died before broadcast acknowledged, skipping");
                skipPendingBroadcastLocked(pid);
                return;
            }
            return;
        }
        Slog.w("ActivityManager", "Spurious process start timeout - pid not known for " + app);
    }

    /* JADX WARN: Can't wrap try/catch for region: R(9:307|308|(3:(8:312|313|314|315|316|317|318|319)|318|319)|324|313|314|315|316|317) */
    /* JADX WARN: Code restructure failed: missing block: B:256:0x0599, code lost:
        r0 = e;
     */
    /* JADX WARN: Code restructure failed: missing block: B:257:0x059a, code lost:
        r4 = r13;
     */
    /* JADX WARN: Code restructure failed: missing block: B:307:0x06ed, code lost:
        r10 = r59;
     */
    /* JADX WARN: Code restructure failed: missing block: B:308:0x06ef, code lost:
        if (r10 == null) goto L254;
     */
    /* JADX WARN: Code restructure failed: missing block: B:310:0x06f3, code lost:
        if (r10.app != r1) goto L254;
     */
    /* JADX WARN: Code restructure failed: missing block: B:312:0x06f7, code lost:
        if (com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKUP == false) goto L248;
     */
    /* JADX WARN: Code restructure failed: missing block: B:313:0x06f9, code lost:
        android.util.Slog.v("ActivityManager", "New app is backup target, launching agent for " + r1);
     */
    /* JADX WARN: Code restructure failed: missing block: B:314:0x070f, code lost:
        notifyPackageUse(r10.appInfo.packageName, 5);
     */
    /* JADX WARN: Code restructure failed: missing block: B:315:0x0717, code lost:
        r64.scheduleCreateBackupAgent(r10.appInfo, compatibilityInfoForPackage(r10.appInfo), r10.backupMode, r10.userId);
     */
    /* JADX WARN: Code restructure failed: missing block: B:317:0x0727, code lost:
        r0 = move-exception;
     */
    /* JADX WARN: Code restructure failed: missing block: B:318:0x0728, code lost:
        android.util.Slog.wtf("ActivityManager", "Exception thrown creating backup agent in " + r1, r0);
        r2 = true;
     */
    /* JADX WARN: Removed duplicated region for block: B:143:0x0331 A[Catch: Exception -> 0x0268, TryCatch #14 {Exception -> 0x0268, blocks: (B:83:0x0248, B:85:0x0250, B:89:0x0257, B:91:0x025f, B:98:0x0275, B:100:0x027d, B:104:0x028a, B:106:0x0294, B:108:0x029a, B:110:0x029e, B:112:0x02a2, B:121:0x02b3, B:124:0x02c1, B:126:0x02e9, B:131:0x02fe, B:133:0x030a, B:135:0x0317, B:137:0x0321, B:143:0x0331, B:145:0x033e, B:147:0x0349, B:157:0x0379, B:159:0x0381, B:161:0x0389, B:163:0x0393, B:164:0x03b2, B:166:0x03b6, B:168:0x03c6, B:170:0x03ca, B:172:0x03de, B:174:0x03e6, B:177:0x03ef, B:179:0x03f5, B:195:0x0434, B:197:0x0442, B:150:0x0356, B:152:0x035a), top: B:373:0x0248 }] */
    /* JADX WARN: Removed duplicated region for block: B:144:0x033d  */
    /* JADX WARN: Removed duplicated region for block: B:147:0x0349 A[Catch: Exception -> 0x0268, TryCatch #14 {Exception -> 0x0268, blocks: (B:83:0x0248, B:85:0x0250, B:89:0x0257, B:91:0x025f, B:98:0x0275, B:100:0x027d, B:104:0x028a, B:106:0x0294, B:108:0x029a, B:110:0x029e, B:112:0x02a2, B:121:0x02b3, B:124:0x02c1, B:126:0x02e9, B:131:0x02fe, B:133:0x030a, B:135:0x0317, B:137:0x0321, B:143:0x0331, B:145:0x033e, B:147:0x0349, B:157:0x0379, B:159:0x0381, B:161:0x0389, B:163:0x0393, B:164:0x03b2, B:166:0x03b6, B:168:0x03c6, B:170:0x03ca, B:172:0x03de, B:174:0x03e6, B:177:0x03ef, B:179:0x03f5, B:195:0x0434, B:197:0x0442, B:150:0x0356, B:152:0x035a), top: B:373:0x0248 }] */
    /* JADX WARN: Removed duplicated region for block: B:157:0x0379 A[Catch: Exception -> 0x0268, TRY_ENTER, TryCatch #14 {Exception -> 0x0268, blocks: (B:83:0x0248, B:85:0x0250, B:89:0x0257, B:91:0x025f, B:98:0x0275, B:100:0x027d, B:104:0x028a, B:106:0x0294, B:108:0x029a, B:110:0x029e, B:112:0x02a2, B:121:0x02b3, B:124:0x02c1, B:126:0x02e9, B:131:0x02fe, B:133:0x030a, B:135:0x0317, B:137:0x0321, B:143:0x0331, B:145:0x033e, B:147:0x0349, B:157:0x0379, B:159:0x0381, B:161:0x0389, B:163:0x0393, B:164:0x03b2, B:166:0x03b6, B:168:0x03c6, B:170:0x03ca, B:172:0x03de, B:174:0x03e6, B:177:0x03ef, B:179:0x03f5, B:195:0x0434, B:197:0x0442, B:150:0x0356, B:152:0x035a), top: B:373:0x0248 }] */
    /* JADX WARN: Removed duplicated region for block: B:163:0x0393 A[Catch: Exception -> 0x0268, TryCatch #14 {Exception -> 0x0268, blocks: (B:83:0x0248, B:85:0x0250, B:89:0x0257, B:91:0x025f, B:98:0x0275, B:100:0x027d, B:104:0x028a, B:106:0x0294, B:108:0x029a, B:110:0x029e, B:112:0x02a2, B:121:0x02b3, B:124:0x02c1, B:126:0x02e9, B:131:0x02fe, B:133:0x030a, B:135:0x0317, B:137:0x0321, B:143:0x0331, B:145:0x033e, B:147:0x0349, B:157:0x0379, B:159:0x0381, B:161:0x0389, B:163:0x0393, B:164:0x03b2, B:166:0x03b6, B:168:0x03c6, B:170:0x03ca, B:172:0x03de, B:174:0x03e6, B:177:0x03ef, B:179:0x03f5, B:195:0x0434, B:197:0x0442, B:150:0x0356, B:152:0x035a), top: B:373:0x0248 }] */
    /* JADX WARN: Removed duplicated region for block: B:164:0x03b2 A[Catch: Exception -> 0x0268, TryCatch #14 {Exception -> 0x0268, blocks: (B:83:0x0248, B:85:0x0250, B:89:0x0257, B:91:0x025f, B:98:0x0275, B:100:0x027d, B:104:0x028a, B:106:0x0294, B:108:0x029a, B:110:0x029e, B:112:0x02a2, B:121:0x02b3, B:124:0x02c1, B:126:0x02e9, B:131:0x02fe, B:133:0x030a, B:135:0x0317, B:137:0x0321, B:143:0x0331, B:145:0x033e, B:147:0x0349, B:157:0x0379, B:159:0x0381, B:161:0x0389, B:163:0x0393, B:164:0x03b2, B:166:0x03b6, B:168:0x03c6, B:170:0x03ca, B:172:0x03de, B:174:0x03e6, B:177:0x03ef, B:179:0x03f5, B:195:0x0434, B:197:0x0442, B:150:0x0356, B:152:0x035a), top: B:373:0x0248 }] */
    /* JADX WARN: Removed duplicated region for block: B:168:0x03c6 A[Catch: Exception -> 0x0268, TryCatch #14 {Exception -> 0x0268, blocks: (B:83:0x0248, B:85:0x0250, B:89:0x0257, B:91:0x025f, B:98:0x0275, B:100:0x027d, B:104:0x028a, B:106:0x0294, B:108:0x029a, B:110:0x029e, B:112:0x02a2, B:121:0x02b3, B:124:0x02c1, B:126:0x02e9, B:131:0x02fe, B:133:0x030a, B:135:0x0317, B:137:0x0321, B:143:0x0331, B:145:0x033e, B:147:0x0349, B:157:0x0379, B:159:0x0381, B:161:0x0389, B:163:0x0393, B:164:0x03b2, B:166:0x03b6, B:168:0x03c6, B:170:0x03ca, B:172:0x03de, B:174:0x03e6, B:177:0x03ef, B:179:0x03f5, B:195:0x0434, B:197:0x0442, B:150:0x0356, B:152:0x035a), top: B:373:0x0248 }] */
    /* JADX WARN: Removed duplicated region for block: B:177:0x03ef A[Catch: Exception -> 0x0268, TRY_ENTER, TryCatch #14 {Exception -> 0x0268, blocks: (B:83:0x0248, B:85:0x0250, B:89:0x0257, B:91:0x025f, B:98:0x0275, B:100:0x027d, B:104:0x028a, B:106:0x0294, B:108:0x029a, B:110:0x029e, B:112:0x02a2, B:121:0x02b3, B:124:0x02c1, B:126:0x02e9, B:131:0x02fe, B:133:0x030a, B:135:0x0317, B:137:0x0321, B:143:0x0331, B:145:0x033e, B:147:0x0349, B:157:0x0379, B:159:0x0381, B:161:0x0389, B:163:0x0393, B:164:0x03b2, B:166:0x03b6, B:168:0x03c6, B:170:0x03ca, B:172:0x03de, B:174:0x03e6, B:177:0x03ef, B:179:0x03f5, B:195:0x0434, B:197:0x0442, B:150:0x0356, B:152:0x035a), top: B:373:0x0248 }] */
    /* JADX WARN: Removed duplicated region for block: B:184:0x0405 A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:215:0x049b A[Catch: Exception -> 0x049f, TRY_LEAVE, TryCatch #8 {Exception -> 0x049f, blocks: (B:209:0x0481, B:203:0x0458, B:205:0x046a, B:206:0x0473, B:215:0x049b, B:223:0x04b3, B:225:0x04bd, B:234:0x04e4, B:236:0x04ee, B:241:0x051b), top: B:361:0x0458 }] */
    /* JADX WARN: Removed duplicated region for block: B:223:0x04b3 A[Catch: Exception -> 0x049f, TRY_ENTER, TryCatch #8 {Exception -> 0x049f, blocks: (B:209:0x0481, B:203:0x0458, B:205:0x046a, B:206:0x0473, B:215:0x049b, B:223:0x04b3, B:225:0x04bd, B:234:0x04e4, B:236:0x04ee, B:241:0x051b), top: B:361:0x0458 }] */
    /* JADX WARN: Removed duplicated region for block: B:228:0x04d3  */
    /* JADX WARN: Removed duplicated region for block: B:234:0x04e4 A[Catch: Exception -> 0x049f, TRY_ENTER, TryCatch #8 {Exception -> 0x049f, blocks: (B:209:0x0481, B:203:0x0458, B:205:0x046a, B:206:0x0473, B:215:0x049b, B:223:0x04b3, B:225:0x04bd, B:234:0x04e4, B:236:0x04ee, B:241:0x051b), top: B:361:0x0458 }] */
    /* JADX WARN: Removed duplicated region for block: B:241:0x051b A[Catch: Exception -> 0x049f, TRY_ENTER, TRY_LEAVE, TryCatch #8 {Exception -> 0x049f, blocks: (B:209:0x0481, B:203:0x0458, B:205:0x046a, B:206:0x0473, B:215:0x049b, B:223:0x04b3, B:225:0x04bd, B:234:0x04e4, B:236:0x04ee, B:241:0x051b), top: B:361:0x0458 }] */
    /* JADX WARN: Removed duplicated region for block: B:243:0x0530  */
    /* JADX WARN: Removed duplicated region for block: B:269:0x0606 A[Catch: Exception -> 0x060b, TRY_ENTER, TRY_LEAVE, TryCatch #4 {Exception -> 0x060b, blocks: (B:269:0x0606, B:254:0x0594), top: B:353:0x0594 }] */
    /* JADX WARN: Removed duplicated region for block: B:278:0x0640  */
    /* JADX WARN: Removed duplicated region for block: B:297:0x06b9  */
    /* JADX WARN: Removed duplicated region for block: B:299:0x06bd  */
    /* JADX WARN: Removed duplicated region for block: B:305:0x06e9 A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:321:0x0744  */
    /* JADX WARN: Removed duplicated region for block: B:323:0x074f  */
    /* JADX WARN: Removed duplicated region for block: B:357:0x0667 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:363:0x068d A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:39:0x00f8  */
    /* JADX WARN: Removed duplicated region for block: B:49:0x0131  */
    /* JADX WARN: Type inference failed for: r14v1 */
    /* JADX WARN: Type inference failed for: r14v12 */
    /* JADX WARN: Type inference failed for: r14v2, types: [boolean] */
    @com.android.internal.annotations.GuardedBy({"this"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean attachApplicationLocked(android.app.IApplicationThread r64, int r65, int r66, long r67) {
        /*
            Method dump skipped, instructions count: 2047
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.attachApplicationLocked(android.app.IApplicationThread, int, int, long):boolean");
    }

    public final void attachApplication(IApplicationThread thread, long startSeq) {
        if (thread == null) {
            throw new SecurityException("Invalid application interface");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                int callingPid = Binder.getCallingPid();
                int callingUid = Binder.getCallingUid();
                long origId = Binder.clearCallingIdentity();
                attachApplicationLocked(thread, callingPid, callingUid, startSeq);
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void showBootMessage(CharSequence msg, boolean always) {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException();
        }
        this.mWindowManager.showBootMessage(msg, always);
    }

    final void finishBooting() {
        Trace.traceBegin(64L, "FinishBooting");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (!this.mBootAnimationComplete) {
                    this.mCallFinishBooting = true;
                    return;
                }
                this.mCallFinishBooting = false;
                resetPriorityAfterLockedSection();
                IntentFilter pkgFilter = new IntentFilter();
                pkgFilter.addAction("android.intent.action.QUERY_PACKAGE_RESTART");
                pkgFilter.addDataScheme("package");
                this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.am.ActivityManagerService.10
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent) {
                        ActivityManagerService activityManagerService;
                        String[] pkgs = intent.getStringArrayExtra("android.intent.extra.PACKAGES");
                        if (pkgs != null) {
                            for (String pkg : pkgs) {
                                ActivityManagerService activityManagerService2 = ActivityManagerService.this;
                                synchronized (activityManagerService2) {
                                    try {
                                        ActivityManagerService.boostPriorityForLockedSection();
                                        activityManagerService = activityManagerService2;
                                    } catch (Throwable th) {
                                        th = th;
                                        activityManagerService = activityManagerService2;
                                    }
                                    try {
                                        if (ActivityManagerService.this.forceStopPackageLocked(pkg, -1, false, false, false, false, false, 0, "query restart")) {
                                            setResultCode(-1);
                                            ActivityManagerService.resetPriorityAfterLockedSection();
                                            return;
                                        }
                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                    } catch (Throwable th2) {
                                        th = th2;
                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                        throw th;
                                    }
                                }
                            }
                        }
                    }
                }, pkgFilter);
                IntentFilter dumpheapFilter = new IntentFilter();
                dumpheapFilter.addAction("com.android.server.am.DELETE_DUMPHEAP");
                this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.am.ActivityManagerService.11
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent) {
                        long delay = intent.getBooleanExtra("delay_delete", false) ? BackupAgentTimeoutParameters.DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS : 0L;
                        ActivityManagerService.this.mHandler.sendEmptyMessageDelayed(51, delay);
                    }
                }, dumpheapFilter);
                IntentFilter dumpNativeStackFilter = new IntentFilter();
                dumpNativeStackFilter.addAction("com.android.server.am.DUMPNATIVE");
                this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.am.ActivityManagerService.12
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent) {
                        String globalTracesPath = SystemProperties.get("dalvik.vm.stack-trace-dir", "");
                        if (globalTracesPath.isEmpty()) {
                            globalTracesPath = ActivityManagerService.ANR_TRACE_DIR;
                        }
                        File tracesDir = new File(globalTracesPath);
                        File tracesFile = ActivityManagerService.createAnrDumpFile(tracesDir);
                        if (tracesFile == null) {
                            Slog.i("ActivityManager", "DUMPNATIVE tracesFile is Null");
                            return;
                        }
                        int pid = intent.getIntExtra("pid", -1);
                        if (pid < 0) {
                            Slog.i("ActivityManager", "DUMPNATIVE bad pid");
                        } else {
                            Debug.dumpNativeBacktraceToFileTimeout(pid, tracesFile.getAbsolutePath(), 2);
                        }
                    }
                }, dumpNativeStackFilter);
                try {
                    Slog.i("ActivityManager", "About to commit checkpoint");
                    IStorageManager storageManager = PackageHelper.getStorageManager();
                    storageManager.commitChanges();
                } catch (Exception e) {
                    PowerManager pm = (PowerManager) this.mContext.getSystemService("power");
                    pm.reboot("Checkpoint commit failed");
                }
                this.mSystemServiceManager.startBootPhase(1000);
                synchronized (this) {
                    try {
                        boostPriorityForLockedSection();
                        int NP = this.mProcessesOnHold.size();
                        if (NP > 0) {
                            ArrayList<ProcessRecord> procs = new ArrayList<>(this.mProcessesOnHold);
                            for (int ip = 0; ip < NP; ip++) {
                                if (ActivityManagerDebugConfig.DEBUG_PROCESSES) {
                                    Slog.v("ActivityManager", "Starting process on hold: " + procs.get(ip));
                                }
                                this.mProcessList.startProcessLocked(procs.get(ip), new HostingRecord("on-hold"));
                            }
                        }
                        if (this.mFactoryTest == 1) {
                            return;
                        }
                        Message nmsg = this.mHandler.obtainMessage(CHECK_EXCESSIVE_POWER_USE_MSG);
                        this.mHandler.sendMessageDelayed(nmsg, this.mConstants.POWER_CHECK_INTERVAL);
                        SystemProperties.set("sys.boot_completed", "1");
                        if (!xpBootManagerPolicy.BOOT_POLICY_ENABLED) {
                            xpBootManagerPolicy.get(this.mContext).onBootCompleted();
                        }
                        if (!"trigger_restart_min_framework".equals(VoldProperties.decrypt().orElse("")) || "".equals(VoldProperties.encrypt_progress().orElse(""))) {
                            SystemProperties.set("dev.bootcomplete", "1");
                        }
                        this.mUserController.sendBootCompleted(new IIntentReceiver.Stub() { // from class: com.android.server.am.ActivityManagerService.13
                            public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                                synchronized (ActivityManagerService.this) {
                                    try {
                                        ActivityManagerService.boostPriorityForLockedSection();
                                        ActivityManagerService.this.mOomAdjuster.mAppCompact.compactAllSystem();
                                        ActivityManagerService.this.requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, false);
                                    } catch (Throwable th) {
                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                        throw th;
                                    }
                                }
                                ActivityManagerService.resetPriorityAfterLockedSection();
                            }
                        });
                        this.mUserController.scheduleStartProfiles();
                        resetPriorityAfterLockedSection();
                        Trace.traceEnd(64L);
                    } finally {
                        resetPriorityAfterLockedSection();
                    }
                }
            } finally {
                resetPriorityAfterLockedSection();
            }
        }
    }

    public void bootAnimationComplete() {
        boolean callFinishBooting;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                callFinishBooting = this.mCallFinishBooting;
                this.mBootAnimationComplete = true;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        if (callFinishBooting) {
            finishBooting();
        }
    }

    final void ensureBootCompleted() {
        boolean booting;
        boolean enableScreen;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                booting = this.mBooting;
                this.mBooting = false;
                enableScreen = this.mBooted ? false : true;
                this.mBooted = true;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        if (booting) {
            finishBooting();
        }
        if (enableScreen) {
            this.mAtmInternal.enableScreenAfterBoot(this.mBooted);
        }
    }

    public IIntentSender getIntentSender(int type, String packageName, IBinder token, String resultWho, int requestCode, Intent[] intents, String[] resolvedTypes, int flags, Bundle bOptions, int userId) {
        int userId2;
        enforceNotIsolatedCaller("getIntentSender");
        if (intents != null) {
            if (intents.length < 1) {
                throw new IllegalArgumentException("Intents array length must be >= 1");
            }
            for (int i = 0; i < intents.length; i++) {
                Intent intent = intents[i];
                if (intent != null) {
                    if (intent.hasFileDescriptors()) {
                        throw new IllegalArgumentException("File descriptors passed in Intent");
                    }
                    if (type == 1 && (intent.getFlags() & DumpState.DUMP_APEX) != 0) {
                        throw new IllegalArgumentException("Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
                    }
                    intents[i] = new Intent(intent);
                }
            }
            if (resolvedTypes != null && resolvedTypes.length != intents.length) {
                throw new IllegalArgumentException("Intent array length does not match resolvedTypes length");
            }
        }
        if (bOptions != null && bOptions.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in options");
        }
        int callingUid = Binder.getCallingUid();
        int userId3 = this.mUserController.handleIncomingUser(Binder.getCallingPid(), callingUid, userId, type == 1, 0, "getIntentSender", null);
        if (userId != -2) {
            userId2 = userId3;
        } else {
            userId2 = -2;
        }
        if (callingUid != 0 && callingUid != 1000) {
            try {
                int uid = AppGlobals.getPackageManager().getPackageUid(packageName, 268435456, UserHandle.getUserId(callingUid));
                if (!UserHandle.isSameApp(callingUid, uid)) {
                    String msg = "Permission Denial: getIntentSender() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + ", (need uid=" + uid + ") is not allowed to send as package " + packageName;
                    Slog.w("ActivityManager", msg);
                    throw new SecurityException(msg);
                }
            } catch (RemoteException e) {
                e = e;
                throw new SecurityException(e);
            }
        }
        try {
            if (type != 3) {
                return this.mPendingIntentController.getIntentSender(type, packageName, callingUid, userId2, token, resultWho, requestCode, intents, resolvedTypes, flags, bOptions);
            }
            try {
                return this.mAtmInternal.getIntentSender(type, packageName, callingUid, userId2, token, resultWho, requestCode, intents, resolvedTypes, flags, bOptions);
            } catch (RemoteException e2) {
                e = e2;
                throw new SecurityException(e);
            }
        } catch (RemoteException e3) {
            e = e3;
        }
    }

    public int sendIntentSender(IIntentSender target, IBinder whitelistToken, int code, Intent intent, String resolvedType, IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
        Intent intent2;
        if (target instanceof PendingIntentRecord) {
            return ((PendingIntentRecord) target).sendWithResult(code, intent, resolvedType, whitelistToken, finishedReceiver, requiredPermission, options);
        }
        if (intent != null) {
            intent2 = intent;
        } else {
            Slog.wtf("ActivityManager", "Can't use null intent with direct IIntentSender call");
            intent2 = new Intent("android.intent.action.MAIN");
        }
        try {
            target.send(code, intent2, resolvedType, whitelistToken, (IIntentReceiver) null, requiredPermission, options);
        } catch (RemoteException e) {
        }
        if (finishedReceiver != null) {
            try {
                finishedReceiver.performReceive(intent2, 0, (String) null, (Bundle) null, false, false, UserHandle.getCallingUserId());
                return 0;
            } catch (RemoteException e2) {
                return 0;
            }
        }
        return 0;
    }

    public void cancelIntentSender(IIntentSender sender) {
        this.mPendingIntentController.cancelIntentSender(sender);
    }

    public String getPackageForIntentSender(IIntentSender pendingResult) {
        if (pendingResult instanceof PendingIntentRecord) {
            try {
                PendingIntentRecord res = (PendingIntentRecord) pendingResult;
                return res.key.packageName;
            } catch (ClassCastException e) {
                return null;
            }
        }
        return null;
    }

    public void registerIntentSenderCancelListener(IIntentSender sender, IResultReceiver receiver) {
        this.mPendingIntentController.registerIntentSenderCancelListener(sender, receiver);
    }

    public void unregisterIntentSenderCancelListener(IIntentSender sender, IResultReceiver receiver) {
        this.mPendingIntentController.unregisterIntentSenderCancelListener(sender, receiver);
    }

    public int getUidForIntentSender(IIntentSender sender) {
        if (sender instanceof PendingIntentRecord) {
            try {
                PendingIntentRecord res = (PendingIntentRecord) sender;
                return res.uid;
            } catch (ClassCastException e) {
                return -1;
            }
        }
        return -1;
    }

    public boolean isIntentSenderTargetedToPackage(IIntentSender pendingResult) {
        if (pendingResult instanceof PendingIntentRecord) {
            try {
                PendingIntentRecord res = (PendingIntentRecord) pendingResult;
                if (res.key.allIntents == null) {
                    return false;
                }
                for (int i = 0; i < res.key.allIntents.length; i++) {
                    Intent intent = res.key.allIntents[i];
                    if (intent.getPackage() != null && intent.getComponent() != null) {
                        return false;
                    }
                }
                return true;
            } catch (ClassCastException e) {
                return false;
            }
        }
        return false;
    }

    public boolean isIntentSenderAnActivity(IIntentSender pendingResult) {
        if (pendingResult instanceof PendingIntentRecord) {
            try {
                PendingIntentRecord res = (PendingIntentRecord) pendingResult;
                return res.key.type == 2;
            } catch (ClassCastException e) {
                return false;
            }
        }
        return false;
    }

    public boolean isIntentSenderAForegroundService(IIntentSender pendingResult) {
        if (pendingResult instanceof PendingIntentRecord) {
            PendingIntentRecord res = (PendingIntentRecord) pendingResult;
            return res.key.type == 5;
        }
        return false;
    }

    public boolean isIntentSenderABroadcast(IIntentSender pendingResult) {
        if (pendingResult instanceof PendingIntentRecord) {
            PendingIntentRecord res = (PendingIntentRecord) pendingResult;
            return res.key.type == 1;
        }
        return false;
    }

    public Intent getIntentForIntentSender(IIntentSender pendingResult) {
        enforceCallingPermission("android.permission.GET_INTENT_SENDER_INTENT", "getIntentForIntentSender()");
        if (pendingResult instanceof PendingIntentRecord) {
            try {
                PendingIntentRecord res = (PendingIntentRecord) pendingResult;
                if (res.key.requestIntent != null) {
                    return new Intent(res.key.requestIntent);
                }
                return null;
            } catch (ClassCastException e) {
                return null;
            }
        }
        return null;
    }

    public String getTagForIntentSender(IIntentSender pendingResult, String prefix) {
        String tagForIntentSenderLocked;
        if (pendingResult instanceof PendingIntentRecord) {
            try {
                PendingIntentRecord res = (PendingIntentRecord) pendingResult;
                synchronized (this) {
                    boostPriorityForLockedSection();
                    tagForIntentSenderLocked = getTagForIntentSenderLocked(res, prefix);
                }
                resetPriorityAfterLockedSection();
                return tagForIntentSenderLocked;
            } catch (ClassCastException e) {
                return null;
            }
        }
        return null;
    }

    String getTagForIntentSenderLocked(PendingIntentRecord res, String prefix) {
        Intent intent = res.key.requestIntent;
        if (intent != null) {
            if (res.lastTag != null && res.lastTagPrefix == prefix && (res.lastTagPrefix == null || res.lastTagPrefix.equals(prefix))) {
                return res.lastTag;
            }
            res.lastTagPrefix = prefix;
            StringBuilder sb = new StringBuilder(128);
            if (prefix != null) {
                sb.append(prefix);
            }
            if (intent.getAction() != null) {
                sb.append(intent.getAction());
            } else if (intent.getComponent() != null) {
                intent.getComponent().appendShortString(sb);
            } else {
                sb.append("?");
            }
            String sb2 = sb.toString();
            res.lastTag = sb2;
            return sb2;
        }
        return null;
    }

    public void setProcessLimit(int max) {
        enforceCallingPermission("android.permission.SET_PROCESS_LIMIT", "setProcessLimit()");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mConstants.setOverrideMaxCachedProcesses(max);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        trimApplications("updateOomAdj_processEnd");
    }

    public int getProcessLimit() {
        int overrideMaxCachedProcesses;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                overrideMaxCachedProcesses = this.mConstants.getOverrideMaxCachedProcesses();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return overrideMaxCachedProcesses;
    }

    void importanceTokenDied(ImportanceToken token) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                synchronized (this.mPidsSelfLocked) {
                    ImportanceToken cur = this.mImportantProcesses.get(token.pid);
                    if (cur != token) {
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    this.mImportantProcesses.remove(token.pid);
                    ProcessRecord pr = this.mPidsSelfLocked.get(token.pid);
                    if (pr == null) {
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    pr.forcingToImportant = null;
                    updateProcessForegroundLocked(pr, false, 0, false);
                    updateOomAdjLocked("updateOomAdj_uiVisibility");
                    resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setProcessImportant(IBinder token, int pid, boolean isForeground, String reason) {
        enforceCallingPermission("android.permission.SET_PROCESS_LIMIT", "setProcessImportant()");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                boolean changed = false;
                synchronized (this.mPidsSelfLocked) {
                    ProcessRecord pr = this.mPidsSelfLocked.get(pid);
                    if (pr == null && isForeground) {
                        Slog.w("ActivityManager", "setProcessForeground called on unknown pid: " + pid);
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    ImportanceToken oldToken = this.mImportantProcesses.get(pid);
                    if (oldToken != null) {
                        oldToken.token.unlinkToDeath(oldToken, 0);
                        this.mImportantProcesses.remove(pid);
                        if (pr != null) {
                            pr.forcingToImportant = null;
                        }
                        changed = true;
                    }
                    if (isForeground && token != null) {
                        ImportanceToken newToken = new ImportanceToken(pid, token, reason) { // from class: com.android.server.am.ActivityManagerService.14
                            @Override // android.os.IBinder.DeathRecipient
                            public void binderDied() {
                                ActivityManagerService.this.importanceTokenDied(this);
                            }
                        };
                        try {
                            token.linkToDeath(newToken, 0);
                            this.mImportantProcesses.put(pid, newToken);
                            pr.forcingToImportant = newToken;
                            changed = true;
                        } catch (RemoteException e) {
                        }
                    }
                    if (changed) {
                        updateOomAdjLocked("updateOomAdj_uiVisibility");
                    }
                    resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isAppForeground(int uid) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                UidRecord uidRec = this.mProcessList.mActiveUids.get(uid);
                if (uidRec != null && !uidRec.idle) {
                    boolean z = uidRec.getCurProcState() <= 7;
                    resetPriorityAfterLockedSection();
                    return z;
                }
                resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isAppBad(ApplicationInfo info) {
        boolean isBadProcessLocked;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                isBadProcessLocked = this.mAppErrors.isBadProcessLocked(info);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return isBadProcessLocked;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getUidState(int uid) {
        int uidProcStateLocked;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                uidProcStateLocked = this.mProcessList.getUidProcStateLocked(uid);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return uidProcStateLocked;
    }

    /* loaded from: classes.dex */
    static class ProcessInfoService extends IProcessInfoService.Stub {
        final ActivityManagerService mActivityManagerService;

        ProcessInfoService(ActivityManagerService activityManagerService) {
            this.mActivityManagerService = activityManagerService;
        }

        public void getProcessStatesFromPids(int[] pids, int[] states) {
            this.mActivityManagerService.getProcessStatesAndOomScoresForPIDs(pids, states, null);
        }

        public void getProcessStatesAndOomScoresFromPids(int[] pids, int[] states, int[] scores) {
            this.mActivityManagerService.getProcessStatesAndOomScoresForPIDs(pids, states, scores);
        }
    }

    public void getProcessStatesAndOomScoresForPIDs(int[] pids, int[] states, int[] scores) {
        if (scores != null) {
            enforceCallingPermission("android.permission.GET_PROCESS_STATE_AND_OOM_SCORE", "getProcessStatesAndOomScoresForPIDs()");
        }
        if (pids == null) {
            throw new NullPointerException("pids");
        }
        if (states == null) {
            throw new NullPointerException("states");
        }
        if (pids.length != states.length) {
            throw new IllegalArgumentException("pids and states arrays have different lengths!");
        }
        if (scores != null && pids.length != scores.length) {
            throw new IllegalArgumentException("pids and scores arrays have different lengths!");
        }
        synchronized (this.mPidsSelfLocked) {
            for (int i = 0; i < pids.length; i++) {
                ProcessRecord pr = this.mPidsSelfLocked.get(pids[i]);
                states[i] = pr == null ? 21 : pr.getCurProcState();
                if (scores != null) {
                    scores[i] = pr == null ? -10000 : pr.curAdj;
                }
            }
        }
    }

    /* loaded from: classes.dex */
    static class PermissionController extends IPermissionController.Stub {
        ActivityManagerService mActivityManagerService;

        PermissionController(ActivityManagerService activityManagerService) {
            this.mActivityManagerService = activityManagerService;
        }

        public boolean checkPermission(String permission, int pid, int uid) {
            return this.mActivityManagerService.checkPermission(permission, pid, uid) == 0;
        }

        public int noteOp(String op, int uid, String packageName) {
            return this.mActivityManagerService.mAppOpsService.noteOperation(AppOpsManager.strOpToOp(op), uid, packageName);
        }

        public String[] getPackagesForUid(int uid) {
            return this.mActivityManagerService.mContext.getPackageManager().getPackagesForUid(uid);
        }

        public boolean isRuntimePermission(String permission) {
            try {
                PermissionInfo info = this.mActivityManagerService.mContext.getPackageManager().getPermissionInfo(permission, 0);
                return (info.protectionLevel & 15) == 1;
            } catch (PackageManager.NameNotFoundException nnfe) {
                Slog.e("ActivityManager", "No such permission: " + permission, nnfe);
                return false;
            }
        }

        public int getPackageUid(String packageName, int flags) {
            try {
                return this.mActivityManagerService.mContext.getPackageManager().getPackageUid(packageName, flags);
            } catch (PackageManager.NameNotFoundException e) {
                return -1;
            }
        }
    }

    /* loaded from: classes.dex */
    class IntentFirewallInterface implements IntentFirewall.AMSInterface {
        IntentFirewallInterface() {
        }

        @Override // com.android.server.firewall.IntentFirewall.AMSInterface
        public int checkComponentPermission(String permission, int pid, int uid, int owningUid, boolean exported) {
            ActivityManagerService activityManagerService = ActivityManagerService.this;
            return ActivityManagerService.checkComponentPermission(permission, pid, uid, owningUid, exported);
        }

        @Override // com.android.server.firewall.IntentFirewall.AMSInterface
        public Object getAMSLock() {
            return ActivityManagerService.this;
        }
    }

    public static int checkComponentPermission(String permission, int pid, int uid, int owningUid, boolean exported) {
        if (pid == MY_PID) {
            return 0;
        }
        return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
    }

    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            return -1;
        }
        return checkComponentPermission(permission, pid, uid, -1, true);
    }

    public int checkPermissionWithToken(String permission, int pid, int uid, IBinder callerToken) {
        if (permission == null) {
            return -1;
        }
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null && tlsIdentity.token == callerToken) {
            Slog.d("ActivityManager", "checkComponentPermission() adjusting {pid,uid} to {" + tlsIdentity.pid + "," + tlsIdentity.uid + "}");
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }
        return checkComponentPermission(permission, pid, uid, -1, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int checkCallingPermission(String permission) {
        return checkPermission(permission, Binder.getCallingPid(), Binder.getCallingUid());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void enforceCallingPermission(String permission, String func) {
        if (checkCallingPermission(permission) == 0) {
            return;
        }
        String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires " + permission;
        Slog.w("ActivityManager", msg);
        throw new SecurityException(msg);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void enforcePermission(String permission, int pid, int uid, String func) {
        if (checkPermission(permission, pid, uid) == 0) {
            return;
        }
        String msg = "Permission Denial: " + func + " from pid=" + pid + ", uid=" + uid + " requires " + permission;
        Slog.w("ActivityManager", msg);
        throw new SecurityException(msg);
    }

    public boolean isAppStartModeDisabled(int uid, String packageName) {
        boolean z;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                z = getAppStartModeLocked(uid, packageName, 0, -1, false, true, false) == 3;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return z;
    }

    int appRestrictedInBackgroundLocked(int uid, String packageName, int packageTargetSdk) {
        if (packageTargetSdk >= SHOW_STRICT_MODE_VIOLATION_UI_MSG) {
            if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                Slog.i("ActivityManager", "App " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + packageName + " targets O+, restricted");
            }
            return 2;
        }
        int appop = this.mAppOpsService.noteOperation(HANDLE_TRUST_STORAGE_UPDATE_MSG, uid, packageName);
        if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
            Slog.i("ActivityManager", "Legacy app " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + packageName + " bg appop " + appop);
        }
        if (appop != 0) {
            if (appop != 1) {
                return 2;
            }
            return 1;
        } else if (this.mForceBackgroundCheck && !UserHandle.isCore(uid) && !isOnDeviceIdleWhitelistLocked(uid, true)) {
            if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                Slog.i("ActivityManager", "Force background check: " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + packageName + " restricted");
            }
            return 1;
        } else {
            return 0;
        }
    }

    int appServicesRestrictedInBackgroundLocked(int uid, String packageName, int packageTargetSdk) {
        if (this.mPackageManagerInt.isPackagePersistent(packageName)) {
            if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                Slog.i("ActivityManager", "App " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + packageName + " is persistent; not restricted in background");
            }
            return 0;
        } else if (uidOnBackgroundWhitelist(uid)) {
            if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                Slog.i("ActivityManager", "App " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + packageName + " on background whitelist; not restricted in background");
            }
            return 0;
        } else if (isOnDeviceIdleWhitelistLocked(uid, false)) {
            if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                Slog.i("ActivityManager", "App " + uid + SliceClientPermissions.SliceAuthority.DELIMITER + packageName + " on idle whitelist; not restricted in background");
            }
            return 0;
        } else {
            return appRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getAppStartModeLocked(int uid, String packageName, int packageTargetSdk, int callingPid, boolean alwaysRestrict, boolean disabledOnly, boolean forcedStandby) {
        boolean ephemeral;
        int startMode;
        ProcessRecord proc;
        UidRecord uidRec = this.mProcessList.getUidRecordLocked(uid);
        if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
            StringBuilder sb = new StringBuilder();
            sb.append("checkAllowBackground: uid=");
            sb.append(uid);
            sb.append(" pkg=");
            sb.append(packageName);
            sb.append(" rec=");
            sb.append(uidRec);
            sb.append(" always=");
            sb.append(alwaysRestrict);
            sb.append(" idle=");
            sb.append(uidRec != null ? uidRec.idle : false);
            Slog.d("ActivityManager", sb.toString());
        }
        if (uidRec == null || alwaysRestrict || forcedStandby || uidRec.idle) {
            if (uidRec == null) {
                ephemeral = getPackageManagerInternalLocked().isPackageEphemeral(UserHandle.getUserId(uid), packageName);
            } else {
                ephemeral = uidRec.ephemeral;
            }
            if (ephemeral) {
                return 3;
            }
            if (disabledOnly) {
                return 0;
            }
            if (alwaysRestrict) {
                startMode = appRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk);
            } else {
                startMode = appServicesRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk);
            }
            if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                Slog.d("ActivityManager", "checkAllowBackground: uid=" + uid + " pkg=" + packageName + " startMode=" + startMode + " onwhitelist=" + isOnDeviceIdleWhitelistLocked(uid, false) + " onwhitelist(ei)=" + isOnDeviceIdleWhitelistLocked(uid, true));
            }
            if (startMode == 1 && callingPid >= 0) {
                synchronized (this.mPidsSelfLocked) {
                    proc = this.mPidsSelfLocked.get(callingPid);
                }
                if (proc != null && !ActivityManager.isProcStateBackground(proc.getCurProcState())) {
                    return 0;
                }
            }
            return startMode;
        }
        return 0;
    }

    boolean isOnDeviceIdleWhitelistLocked(int uid, boolean allowExceptIdleToo) {
        int[] whitelist;
        int appId = UserHandle.getAppId(uid);
        if (allowExceptIdleToo) {
            whitelist = this.mDeviceIdleExceptIdleWhitelist;
        } else {
            whitelist = this.mDeviceIdleWhitelist;
        }
        return Arrays.binarySearch(whitelist, appId) >= 0 || Arrays.binarySearch(this.mDeviceIdleTempWhitelist, appId) >= 0 || this.mPendingTempWhitelist.indexOfKey(uid) >= 0;
    }

    String getPendingTempWhitelistTagForUidLocked(int uid) {
        PendingTempWhitelist ptw = this.mPendingTempWhitelist.get(uid);
        if (ptw != null) {
            return ptw.tag;
        }
        return null;
    }

    private ProviderInfo getProviderInfoLocked(String authority, int userHandle, int pmFlags) {
        ContentProviderRecord cpr = this.mProviderMap.getProviderByName(authority, userHandle);
        if (cpr != null) {
            ProviderInfo pi = cpr.info;
            return pi;
        }
        try {
            ProviderInfo pi2 = AppGlobals.getPackageManager().resolveContentProvider(authority, pmFlags | 2048, userHandle);
            return pi2;
        } catch (RemoteException e) {
            return null;
        }
    }

    @VisibleForTesting
    public void grantEphemeralAccessLocked(int userId, Intent intent, int targetAppId, int ephemeralAppId) {
        getPackageManagerInternalLocked().grantEphemeralAccess(userId, intent, targetAppId, ephemeralAppId);
    }

    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, int userId, IBinder callerToken) {
        enforceNotIsolatedCaller("checkUriPermission");
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null && tlsIdentity.token == callerToken) {
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }
        return (pid == MY_PID || this.mUgmInternal.checkUriPermission(new GrantUri(userId, uri, false), uid, modeFlags)) ? 0 : -1;
    }

    public void grantUriPermission(IApplicationThread caller, String targetPkg, Uri uri, int modeFlags, int userId) {
        enforceNotIsolatedCaller("grantUriPermission");
        GrantUri grantUri = new GrantUri(userId, uri, false);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ProcessRecord r = getRecordForAppLocked(caller);
                if (r == null) {
                    throw new SecurityException("Unable to find app for caller " + caller + " when granting permission to uri " + grantUri);
                } else if (targetPkg == null) {
                    throw new IllegalArgumentException("null target");
                } else {
                    Preconditions.checkFlagsArgument(modeFlags, (int) HdmiCecKeycode.UI_SOUND_PRESENTATION_TREBLE_STEP_MINUS);
                    this.mUgmInternal.grantUriPermission(r.uid, targetPkg, grantUri, modeFlags, null, UserHandle.getUserId(r.uid));
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void revokeUriPermission(IApplicationThread caller, String targetPackage, Uri uri, int modeFlags, int userId) {
        enforceNotIsolatedCaller("revokeUriPermission");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ProcessRecord r = getRecordForAppLocked(caller);
                if (r == null) {
                    throw new SecurityException("Unable to find app for caller " + caller + " when revoking permission to uri " + uri);
                } else if (uri == null) {
                    Slog.w("ActivityManager", "revokeUriPermission: null uri");
                    resetPriorityAfterLockedSection();
                } else if (!Intent.isAccessUriMode(modeFlags)) {
                    resetPriorityAfterLockedSection();
                } else {
                    String authority = uri.getAuthority();
                    ProviderInfo pi = getProviderInfoLocked(authority, userId, 786432);
                    if (pi == null) {
                        Slog.w("ActivityManager", "No content provider found for permission revoke: " + uri.toSafeString());
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    this.mUgmInternal.revokeUriPermission(targetPackage, r.uid, new GrantUri(userId, uri, false), modeFlags);
                    resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void showWaitingForDebugger(IApplicationThread who, boolean waiting) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ProcessRecord app = who != null ? getRecordForAppLocked(who) : null;
                if (app == null) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                Message msg = Message.obtain();
                msg.what = 6;
                msg.obj = app;
                msg.arg1 = waiting ? 1 : 0;
                this.mUiHandler.sendMessage(msg);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        this.mProcessList.getMemoryInfo(outInfo);
    }

    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        return this.mActivityTaskManager.getTasks(maxNum);
    }

    public List<ActivityManager.RunningTaskInfo> getFilteredTasks(int maxNum, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode) {
        return this.mActivityTaskManager.getFilteredTasks(maxNum, ignoreActivityType, ignoreWindowingMode);
    }

    public void cancelTaskWindowTransition(int taskId) {
        this.mActivityTaskManager.cancelTaskWindowTransition(taskId);
    }

    public void setTaskResizeable(int taskId, int resizeableMode) {
        this.mActivityTaskManager.setTaskResizeable(taskId, resizeableMode);
    }

    public ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution) {
        return this.mActivityTaskManager.getTaskSnapshot(taskId, reducedResolution);
    }

    public void resizeTask(int taskId, Rect bounds, int resizeMode) {
        this.mActivityTaskManager.resizeTask(taskId, bounds, resizeMode);
    }

    public Rect getTaskBounds(int taskId) {
        return this.mActivityTaskManager.getTaskBounds(taskId);
    }

    public void removeStack(int stackId) {
        this.mActivityTaskManager.removeStack(stackId);
    }

    public boolean removeTask(int taskId) {
        return this.mActivityTaskManager.removeTask(taskId);
    }

    public void moveTaskToFront(IApplicationThread appThread, String callingPackage, int taskId, int flags, Bundle bOptions) {
        this.mActivityTaskManager.moveTaskToFront(appThread, callingPackage, taskId, flags, bOptions);
    }

    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        return this.mActivityTaskManager.moveActivityTaskToBack(token, nonRoot);
    }

    public void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        this.mActivityTaskManager.moveTaskToStack(taskId, stackId, toTop);
    }

    public void resizeStack(int stackId, Rect destBounds, boolean allowResizeInDockedMode, boolean preserveWindows, boolean animate, int animationDuration) {
        this.mActivityTaskManager.resizeStack(stackId, destBounds, allowResizeInDockedMode, preserveWindows, animate, animationDuration);
    }

    public ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags, int userId) {
        return this.mActivityTaskManager.getRecentTasks(maxNum, flags, userId);
    }

    public boolean moveTopActivityToPinnedStack(int stackId, Rect bounds) {
        return this.mActivityTaskManager.moveTopActivityToPinnedStack(stackId, bounds);
    }

    public void resizeDockedStack(Rect dockedBounds, Rect tempDockedTaskBounds, Rect tempDockedTaskInsetBounds, Rect tempOtherTaskBounds, Rect tempOtherTaskInsetBounds) {
        this.mActivityTaskManager.resizeDockedStack(dockedBounds, tempDockedTaskBounds, tempDockedTaskInsetBounds, tempOtherTaskBounds, tempOtherTaskInsetBounds);
    }

    public void positionTaskInStack(int taskId, int stackId, int position) {
        this.mActivityTaskManager.positionTaskInStack(taskId, stackId, position);
    }

    public List<ActivityManager.StackInfo> getAllStackInfos() {
        return this.mActivityTaskManager.getAllStackInfos();
    }

    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        return this.mActivityTaskManager.getTaskForActivity(token, onlyRoot);
    }

    public void updateDeviceOwner(String packageName) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != 1000) {
            throw new SecurityException("updateDeviceOwner called from non-system process");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mDeviceOwnerName = packageName;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void updateLockTaskPackages(int userId, String[] packages) {
        this.mActivityTaskManager.updateLockTaskPackages(userId, packages);
    }

    public boolean isInLockTaskMode() {
        return this.mActivityTaskManager.isInLockTaskMode();
    }

    public int getLockTaskModeState() {
        return this.mActivityTaskManager.getLockTaskModeState();
    }

    public void startSystemLockTaskMode(int taskId) throws RemoteException {
        this.mActivityTaskManager.startSystemLockTaskMode(taskId);
    }

    private final List<ProviderInfo> generateApplicationProvidersLocked(ProcessRecord app) {
        List<ProviderInfo> providers;
        ContentProviderRecord cpr;
        try {
            providers = AppGlobals.getPackageManager().queryContentProviders(app.processName, app.uid, 268438528, (String) null).getList();
        } catch (RemoteException e) {
            providers = null;
        }
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.v(TAG_MU, "generateApplicationProvidersLocked, app.info.uid = " + app.uid);
        }
        int userId = app.userId;
        if (providers != null) {
            int N = providers.size();
            app.pubProviders.ensureCapacity(app.pubProviders.size() + N);
            int N2 = N;
            int i = 0;
            while (i < N2) {
                ProviderInfo cpi = providers.get(i);
                boolean singleton = isSingleton(cpi.processName, cpi.applicationInfo, cpi.name, cpi.flags);
                if (singleton && UserHandle.getUserId(app.uid) != 0) {
                    providers.remove(i);
                    N2--;
                    i--;
                } else {
                    ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
                    ContentProviderRecord cpr2 = this.mProviderMap.getProviderByClass(comp, userId);
                    if (cpr2 != null) {
                        cpr = cpr2;
                    } else {
                        cpr = new ContentProviderRecord(this, cpi, app.info, comp, singleton);
                        this.mProviderMap.putProviderByClass(comp, cpr);
                    }
                    if (ActivityManagerDebugConfig.DEBUG_MU) {
                        Slog.v(TAG_MU, "generateApplicationProvidersLocked, cpi.uid = " + cpr.uid);
                    }
                    app.pubProviders.put(cpi.name, cpr);
                    if (!cpi.multiprocess || !PackageManagerService.PLATFORM_PACKAGE_NAME.equals(cpi.packageName)) {
                        app.addPackage(cpi.applicationInfo.packageName, cpi.applicationInfo.longVersionCode, this.mProcessStats);
                    }
                    notifyPackageUse(cpi.applicationInfo.packageName, 4);
                }
                i++;
            }
        }
        return providers;
    }

    public String checkContentProviderAccess(String authority, int userId) {
        ProcessRecord r;
        String checkContentProviderPermissionLocked;
        if (userId == -1) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "ActivityManager");
            userId = UserHandle.getCallingUserId();
        }
        ProviderInfo cpi = null;
        try {
            cpi = AppGlobals.getPackageManager().resolveContentProvider(authority, 790016, userId);
        } catch (RemoteException e) {
        }
        if (cpi == null) {
            return "Failed to find provider " + authority + " for user " + userId + "; expected to find a valid ContentProvider for this authority";
        }
        synchronized (this.mPidsSelfLocked) {
            r = this.mPidsSelfLocked.get(Binder.getCallingPid());
        }
        if (r == null) {
            return "Failed to find PID " + Binder.getCallingPid();
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                checkContentProviderPermissionLocked = checkContentProviderPermissionLocked(cpi, r, userId, true);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return checkContentProviderPermissionLocked;
    }

    private final String checkContentProviderPermissionLocked(ProviderInfo cpi, ProcessRecord r, int userId, boolean checkUser) {
        boolean checkedGrants;
        int userId2;
        String suffix;
        int callingPid = r != null ? r.pid : Binder.getCallingPid();
        int callingUid = r != null ? r.uid : Binder.getCallingUid();
        if (!checkUser) {
            checkedGrants = false;
            userId2 = userId;
        } else {
            int tmpTargetUserId = this.mUserController.unsafeConvertIncomingUser(userId);
            if (tmpTargetUserId == UserHandle.getUserId(callingUid)) {
                checkedGrants = false;
            } else if (this.mUgmInternal.checkAuthorityGrants(callingUid, cpi, tmpTargetUserId, checkUser)) {
                return null;
            } else {
                checkedGrants = true;
            }
            userId2 = this.mUserController.handleIncomingUser(callingPid, callingUid, userId, false, 0, "checkContentProviderPermissionLocked " + cpi.authority, null);
            if (userId2 != tmpTargetUserId) {
                checkedGrants = false;
            }
        }
        if (checkComponentPermission(cpi.readPermission, callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported) == 0 || checkComponentPermission(cpi.writePermission, callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported) == 0) {
            return null;
        }
        PathPermission[] pps = cpi.pathPermissions;
        if (pps != null) {
            int i = pps.length;
            while (i > 0) {
                i--;
                PathPermission pp = pps[i];
                String pprperm = pp.getReadPermission();
                if (pprperm != null && checkComponentPermission(pprperm, callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported) == 0) {
                    return null;
                }
                String ppwperm = pp.getWritePermission();
                if (ppwperm != null && checkComponentPermission(ppwperm, callingPid, callingUid, cpi.applicationInfo.uid, cpi.exported) == 0) {
                    return null;
                }
            }
        }
        if (!checkedGrants && this.mUgmInternal.checkAuthorityGrants(callingUid, cpi, userId2, checkUser)) {
            return null;
        }
        if (!cpi.exported) {
            suffix = " that is not exported from UID " + cpi.applicationInfo.uid;
        } else {
            String suffix2 = cpi.readPermission;
            if ("android.permission.MANAGE_DOCUMENTS".equals(suffix2)) {
                suffix = " requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs";
            } else {
                suffix = " requires " + cpi.readPermission + " or " + cpi.writePermission;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Permission Denial: opening provider ");
        sb.append(cpi.name);
        sb.append(" from ");
        sb.append(r != null ? r : "(null)");
        sb.append(" (pid=");
        sb.append(callingPid);
        sb.append(", uid=");
        sb.append(callingUid);
        sb.append(")");
        sb.append(suffix);
        String msg = sb.toString();
        Slog.w("ActivityManager", msg);
        return msg;
    }

    ContentProviderConnection incProviderCountLocked(ProcessRecord r, ContentProviderRecord cpr, IBinder externalProcessToken, int callingUid, String callingPackage, String callingTag, boolean stable) {
        if (r != null) {
            for (int i = 0; i < r.conProviders.size(); i++) {
                ContentProviderConnection conn = r.conProviders.get(i);
                if (conn.provider == cpr) {
                    if (ActivityManagerDebugConfig.DEBUG_PROVIDER) {
                        Slog.v("ActivityManager", "Adding provider requested by " + r.processName + " from process " + cpr.info.processName + ": " + cpr.name.flattenToShortString() + " scnt=" + conn.stableCount + " uscnt=" + conn.unstableCount);
                    }
                    if (stable) {
                        conn.stableCount++;
                        conn.numStableIncs++;
                    } else {
                        conn.unstableCount++;
                        conn.numUnstableIncs++;
                    }
                    return conn;
                }
            }
            ContentProviderConnection conn2 = new ContentProviderConnection(cpr, r, callingPackage);
            conn2.startAssociationIfNeeded();
            if (stable) {
                conn2.stableCount = 1;
                conn2.numStableIncs = 1;
            } else {
                conn2.unstableCount = 1;
                conn2.numUnstableIncs = 1;
            }
            cpr.connections.add(conn2);
            r.conProviders.add(conn2);
            startAssociationLocked(r.uid, r.processName, r.getCurProcState(), cpr.uid, cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
            return conn2;
        }
        cpr.addExternalProcessHandleLocked(externalProcessToken, callingUid, callingTag);
        return null;
    }

    boolean decProviderCountLocked(ContentProviderConnection conn, ContentProviderRecord cpr, IBinder externalProcessToken, boolean stable) {
        if (conn != null) {
            ContentProviderRecord cpr2 = conn.provider;
            if (ActivityManagerDebugConfig.DEBUG_PROVIDER) {
                Slog.v("ActivityManager", "Removing provider requested by " + conn.client.processName + " from process " + cpr2.info.processName + ": " + cpr2.name.flattenToShortString() + " scnt=" + conn.stableCount + " uscnt=" + conn.unstableCount);
            }
            if (stable) {
                conn.stableCount--;
            } else {
                conn.unstableCount--;
            }
            if (conn.stableCount != 0 || conn.unstableCount != 0) {
                return false;
            }
            conn.stopAssociation();
            cpr2.connections.remove(conn);
            conn.client.conProviders.remove(conn);
            if (conn.client.setProcState < 16 && cpr2.proc != null) {
                cpr2.proc.lastProviderTime = SystemClock.uptimeMillis();
            }
            stopAssociationLocked(conn.client.uid, conn.client.processName, cpr2.uid, cpr2.appInfo.longVersionCode, cpr2.name, cpr2.info.processName);
            return true;
        }
        cpr.removeExternalProcessHandleLocked(externalProcessToken);
        return false;
    }

    void checkTime(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if (now - startTime > 50) {
            Slog.w("ActivityManager", "Slow operation: " + (now - startTime) + "ms so far, now at " + where);
        }
    }

    private boolean isProcessAliveLocked(ProcessRecord proc) {
        if (proc.pid <= 0) {
            if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                Slog.d("ActivityManager", "Process hasn't started yet: " + proc);
            }
            return false;
        }
        if (proc.procStatFile == null) {
            proc.procStatFile = "/proc/" + proc.pid + "/stat";
        }
        this.mProcessStateStatsLongs[0] = 0;
        if (!Process.readProcFile(proc.procStatFile, PROCESS_STATE_STATS_FORMAT, null, this.mProcessStateStatsLongs, null)) {
            if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                Slog.d("ActivityManager", "UNABLE TO RETRIEVE STATE FOR " + proc.procStatFile);
            }
            return false;
        }
        long state = this.mProcessStateStatsLongs[0];
        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
            Slog.d("ActivityManager", "RETRIEVED STATE FOR " + proc.procStatFile + ": " + ((char) state));
        }
        return (state == 90 || state == 88 || state == 120 || state == 75) ? false : true;
    }

    private String checkContentProviderAssociation(ProcessRecord callingApp, int callingUid, ProviderInfo cpi) {
        if (callingApp == null) {
            if (validateAssociationAllowedLocked(cpi.packageName, cpi.applicationInfo.uid, null, callingUid)) {
                return null;
            }
            return "<null>";
        }
        for (int i = callingApp.pkgList.size() - 1; i >= 0; i--) {
            if (!validateAssociationAllowedLocked(callingApp.pkgList.keyAt(i), callingApp.uid, cpi.packageName, cpi.applicationInfo.uid)) {
                return cpi.packageName;
            }
        }
        return null;
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:335:0x06a3
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private android.app.ContentProviderHolder getContentProviderImpl(android.app.IApplicationThread r39, java.lang.String r40, android.os.IBinder r41, int r42, java.lang.String r43, java.lang.String r44, boolean r45, int r46) {
        /*
            Method dump skipped, instructions count: 2758
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.getContentProviderImpl(android.app.IApplicationThread, java.lang.String, android.os.IBinder, int, java.lang.String, java.lang.String, boolean, int):android.app.ContentProviderHolder");
    }

    /* loaded from: classes.dex */
    private static final class StartActivityRunnable implements Runnable {
        private final Context mContext;
        private final Intent mIntent;
        private final UserHandle mUserHandle;

        StartActivityRunnable(Context context, Intent intent, UserHandle userHandle) {
            this.mContext = context;
            this.mIntent = intent;
            this.mUserHandle = userHandle;
        }

        @Override // java.lang.Runnable
        public void run() {
            this.mContext.startActivityAsUser(this.mIntent, this.mUserHandle);
        }
    }

    private boolean requestTargetProviderPermissionsReviewIfNeededLocked(ProviderInfo cpi, ProcessRecord r, int userId) {
        boolean callerForeground = true;
        if (getPackageManagerInternalLocked().isPermissionsReviewRequired(cpi.packageName, userId)) {
            if (r != null && r.setSchedGroup == 0) {
                callerForeground = false;
            }
            if (!callerForeground) {
                Slog.w("ActivityManager", "u" + userId + " Instantiating a provider in package" + cpi.packageName + " requires a permissions review");
                return false;
            }
            Intent intent = new Intent("android.intent.action.REVIEW_PERMISSIONS");
            intent.addFlags(276824064);
            intent.putExtra("android.intent.extra.PACKAGE_NAME", cpi.packageName);
            if (ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW) {
                Slog.i("ActivityManager", "u" + userId + " Launching permission review for package " + cpi.packageName);
            }
            UserHandle userHandle = new UserHandle(userId);
            this.mHandler.post(new StartActivityRunnable(this.mContext, intent, userHandle));
            return false;
        }
        return true;
    }

    @VisibleForTesting
    public IPackageManager getPackageManager() {
        return AppGlobals.getPackageManager();
    }

    @VisibleForTesting
    public PackageManagerInternal getPackageManagerInternalLocked() {
        if (this.mPackageManagerInt == null) {
            this.mPackageManagerInt = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        }
        return this.mPackageManagerInt;
    }

    public final ContentProviderHolder getContentProvider(IApplicationThread caller, String callingPackage, String name, int userId, boolean stable) {
        enforceNotIsolatedCaller("getContentProvider");
        if (caller == null) {
            String msg = "null IApplicationThread when getting content provider " + name;
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        int callingUid = Binder.getCallingUid();
        if (callingPackage != null && this.mAppOpsService.checkPackage(callingUid, callingPackage) != 0) {
            throw new SecurityException("Given calling package " + callingPackage + " does not match caller's uid " + callingUid);
        }
        return getContentProviderImpl(caller, name, null, callingUid, callingPackage, null, stable, userId);
    }

    public ContentProviderHolder getContentProviderExternal(String name, int userId, IBinder token, String tag) {
        enforceCallingPermission("android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY", "Do not have permission in call getContentProviderExternal()");
        return getContentProviderExternalUnchecked(name, token, Binder.getCallingUid(), tag != null ? tag : "*external*", this.mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, 2, "getContentProvider", null));
    }

    private ContentProviderHolder getContentProviderExternalUnchecked(String name, IBinder token, int callingUid, String callingTag, int userId) {
        return getContentProviderImpl(null, name, token, callingUid, null, callingTag, true, userId);
    }

    public void removeContentProvider(IBinder connection, boolean stable) {
        enforceNotIsolatedCaller("removeContentProvider");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    ContentProviderConnection conn = (ContentProviderConnection) connection;
                    if (conn == null) {
                        throw new NullPointerException("connection is null");
                    }
                    if (decProviderCountLocked(conn, null, null, stable)) {
                        updateOomAdjLocked("updateOomAdj_removeProvider");
                    }
                } catch (ClassCastException e) {
                    String msg = "removeContentProvider: " + connection + " not a ContentProviderConnection";
                    Slog.w("ActivityManager", msg);
                    throw new IllegalArgumentException(msg);
                }
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Deprecated
    public void removeContentProviderExternal(String name, IBinder token) {
        removeContentProviderExternalAsUser(name, token, UserHandle.getCallingUserId());
    }

    public void removeContentProviderExternalAsUser(String name, IBinder token, int userId) {
        enforceCallingPermission("android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY", "Do not have permission in call removeContentProviderExternal()");
        long ident = Binder.clearCallingIdentity();
        try {
            removeContentProviderExternalUnchecked(name, token, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeContentProviderExternalUnchecked(String name, IBinder token, int userId) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ContentProviderRecord cpr = this.mProviderMap.getProviderByName(name, userId);
                if (cpr == null) {
                    if (ActivityManagerDebugConfig.DEBUG_ALL) {
                        Slog.v("ActivityManager", name + " content provider not found in providers list");
                    }
                    resetPriorityAfterLockedSection();
                    return;
                }
                ComponentName comp = new ComponentName(cpr.info.packageName, cpr.info.name);
                ContentProviderRecord localCpr = this.mProviderMap.getProviderByClass(comp, userId);
                if (localCpr.hasExternalProcessHandles()) {
                    if (localCpr.removeExternalProcessHandleLocked(token)) {
                        updateOomAdjLocked("updateOomAdj_removeProvider");
                    } else {
                        Slog.e("ActivityManager", "Attmpt to remove content provider " + localCpr + " with no external reference for token: " + token + ".");
                    }
                } else {
                    Slog.e("ActivityManager", "Attmpt to remove content provider: " + localCpr + " with no external references.");
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public final void publishContentProviders(IApplicationThread caller, List<ContentProviderHolder> providers) {
        if (providers != null) {
            enforceNotIsolatedCaller("publishContentProviders");
            synchronized (this) {
                try {
                    try {
                        boostPriorityForLockedSection();
                        ProcessRecord r = getRecordForAppLocked(caller);
                        if (ActivityManagerDebugConfig.DEBUG_MU) {
                            Slog.v(TAG_MU, "ProcessRecord uid = " + r.uid);
                        }
                        if (r == null) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Unable to find app for caller ");
                            sb.append(caller);
                            sb.append(" (pid=");
                            sb.append(Binder.getCallingPid());
                            sb.append(") when publishing content providers");
                            throw new SecurityException(sb.toString());
                        }
                        long origId = Binder.clearCallingIdentity();
                        int N = providers.size();
                        for (int i = 0; i < N; i++) {
                            ContentProviderHolder src = providers.get(i);
                            if (src != null && src.info != null && src.provider != null) {
                                ContentProviderRecord dst = r.pubProviders.get(src.info.name);
                                if (ActivityManagerDebugConfig.DEBUG_MU) {
                                    Slog.v(TAG_MU, "ContentProviderRecord uid = " + dst.uid);
                                }
                                if (dst != null) {
                                    ComponentName comp = new ComponentName(dst.info.packageName, dst.info.name);
                                    this.mProviderMap.putProviderByClass(comp, dst);
                                    String[] names = dst.info.authority.split(";");
                                    for (String str : names) {
                                        this.mProviderMap.putProviderByName(str, dst);
                                    }
                                    int launchingCount = this.mLaunchingProviders.size();
                                    int j = 0;
                                    boolean wasInLaunchingProviders = false;
                                    int launchingCount2 = launchingCount;
                                    while (j < launchingCount2) {
                                        if (this.mLaunchingProviders.get(j) == dst) {
                                            this.mLaunchingProviders.remove(j);
                                            wasInLaunchingProviders = true;
                                            j--;
                                            launchingCount2--;
                                        }
                                        j++;
                                    }
                                    if (wasInLaunchingProviders) {
                                        this.mHandler.removeMessages(CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG, r);
                                    }
                                    r.addPackage(dst.info.applicationInfo.packageName, dst.info.applicationInfo.longVersionCode, this.mProcessStats);
                                    synchronized (dst) {
                                        dst.provider = src.provider;
                                        dst.setProcess(r);
                                        dst.notifyAll();
                                    }
                                    updateOomAdjLocked(r, true, "updateOomAdj_getProvider");
                                    maybeUpdateProviderUsageStatsLocked(r, src.info.packageName, src.info.authority);
                                }
                            }
                        }
                        Binder.restoreCallingIdentity(origId);
                        resetPriorityAfterLockedSection();
                    } catch (Throwable th) {
                        th = th;
                        resetPriorityAfterLockedSection();
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }
    }

    public boolean refContentProvider(IBinder connection, int stable, int unstable) {
        boolean z;
        try {
            ContentProviderConnection conn = (ContentProviderConnection) connection;
            if (conn == null) {
                throw new NullPointerException("connection is null");
            }
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    if (stable > 0) {
                        conn.numStableIncs += stable;
                    }
                    int stable2 = stable + conn.stableCount;
                    if (stable2 < 0) {
                        throw new IllegalStateException("stableCount < 0: " + stable2);
                    }
                    if (unstable > 0) {
                        conn.numUnstableIncs += unstable;
                    }
                    int unstable2 = unstable + conn.unstableCount;
                    if (unstable2 < 0) {
                        throw new IllegalStateException("unstableCount < 0: " + unstable2);
                    } else if (stable2 + unstable2 <= 0) {
                        throw new IllegalStateException("ref counts can't go to zero here: stable=" + stable2 + " unstable=" + unstable2);
                    } else {
                        conn.stableCount = stable2;
                        conn.unstableCount = unstable2;
                        z = !conn.dead;
                    }
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            return z;
        } catch (ClassCastException e) {
            String msg = "refContentProvider: " + connection + " not a ContentProviderConnection";
            Slog.w("ActivityManager", msg);
            throw new IllegalArgumentException(msg);
        }
    }

    public void unstableProviderDied(IBinder connection) {
        IContentProvider provider;
        try {
            ContentProviderConnection conn = (ContentProviderConnection) connection;
            if (conn == null) {
                throw new NullPointerException("connection is null");
            }
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    provider = conn.provider.provider;
                } finally {
                    resetPriorityAfterLockedSection();
                }
            }
            resetPriorityAfterLockedSection();
            if (provider == null) {
                return;
            }
            if (provider.asBinder().pingBinder()) {
                synchronized (this) {
                    try {
                        boostPriorityForLockedSection();
                        Slog.w("ActivityManager", "unstableProviderDied: caller " + Binder.getCallingUid() + " says " + conn + " died, but we don't agree");
                    } finally {
                    }
                }
                resetPriorityAfterLockedSection();
                return;
            }
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    if (conn.provider.provider != provider) {
                        return;
                    }
                    ProcessRecord proc = conn.provider.proc;
                    if (proc != null && proc.thread != null) {
                        reportUidInfoMessageLocked("ActivityManager", "Process " + proc.processName + " (pid " + proc.pid + ") early provider death", proc.info.uid);
                        long ident = Binder.clearCallingIdentity();
                        appDiedLocked(proc);
                        Binder.restoreCallingIdentity(ident);
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    resetPriorityAfterLockedSection();
                } finally {
                    resetPriorityAfterLockedSection();
                }
            }
        } catch (ClassCastException e) {
            String msg = "refContentProvider: " + connection + " not a ContentProviderConnection";
            Slog.w("ActivityManager", msg);
            throw new IllegalArgumentException(msg);
        }
    }

    public void appNotRespondingViaProvider(IBinder connection) {
        enforceCallingPermission("android.permission.REMOVE_TASKS", "appNotRespondingViaProvider()");
        ContentProviderConnection conn = (ContentProviderConnection) connection;
        if (conn == null) {
            Slog.w("ActivityManager", "ContentProviderConnection is null");
            return;
        }
        final ProcessRecord host = conn.provider.proc;
        if (host == null) {
            Slog.w("ActivityManager", "Failed to find hosting ProcessRecord");
        } else {
            this.mHandler.post(new Runnable() { // from class: com.android.server.am.ActivityManagerService.15
                @Override // java.lang.Runnable
                public void run() {
                    host.appNotResponding(null, null, null, null, false, "ContentProvider not responding");
                }
            });
        }
    }

    public final void installSystemProviders() {
        List<ProviderInfo> providers;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ProcessRecord app = (ProcessRecord) this.mProcessList.mProcessNames.get("system", 1000);
                providers = generateApplicationProvidersLocked(app);
                if (providers != null) {
                    for (int i = providers.size() - 1; i >= 0; i--) {
                        ProviderInfo pi = providers.get(i);
                        if ((pi.applicationInfo.flags & 1) == 0) {
                            Slog.w("ActivityManager", "Not installing system proc provider " + pi.name + ": not system .apk");
                            providers.remove(i);
                        }
                    }
                }
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        if (providers != null) {
            this.mSystemThread.installSystemProviders(providers);
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mSystemProvidersInstalled = true;
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        this.mConstants.start(this.mContext.getContentResolver());
        this.mCoreSettingsObserver = new CoreSettingsObserver(this);
        this.mActivityTaskManager.installSystemProviders();
        this.mDevelopmentSettingsObserver = new DevelopmentSettingsObserver();
        SettingsToPropertiesMapper.start(this.mContext.getContentResolver());
        this.mOomAdjuster.initSettings();
        RescueParty.onSettingsProviderPublished(this.mContext);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startPersistentApps(int matchFlags) {
        if (this.mFactoryTest == 1) {
            return;
        }
        synchronized (this) {
            try {
                try {
                    boostPriorityForLockedSection();
                    List<ApplicationInfo> apps = AppGlobals.getPackageManager().getPersistentApplications(matchFlags | 1024).getList();
                    for (ApplicationInfo app : apps) {
                        if (!PackageManagerService.PLATFORM_PACKAGE_NAME.equals(app.packageName)) {
                            addAppLocked(app, null, false, null);
                        }
                    }
                } catch (RemoteException e) {
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:50:0x00c4 A[ADDED_TO_REGION] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void installEncryptionUnawareProviders(int r23) {
        /*
            Method dump skipped, instructions count: 393
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.installEncryptionUnawareProviders(int):void");
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:44:0x008e
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public java.lang.String getProviderMimeType(android.net.Uri r20, int r21) {
        /*
            Method dump skipped, instructions count: 268
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.getProviderMimeType(android.net.Uri, int):java.lang.String");
    }

    private boolean canClearIdentity(int callingPid, int callingUid, int userId) {
        return UserHandle.getUserId(callingUid) == userId || checkComponentPermission("android.permission.INTERACT_ACROSS_USERS", callingPid, callingUid, -1, true) == 0 || checkComponentPermission("android.permission.INTERACT_ACROSS_USERS_FULL", callingPid, callingUid, -1, true) == 0;
    }

    private boolean uidOnBackgroundWhitelist(int uid) {
        int appId = UserHandle.getAppId(uid);
        int[] whitelist = this.mBackgroundAppIdWhitelist;
        for (int i : whitelist) {
            if (appId == i) {
                return true;
            }
        }
        return false;
    }

    public boolean isBackgroundRestricted(String packageName) {
        int packageUid;
        int callingUid = Binder.getCallingUid();
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            packageUid = pm.getPackageUid(packageName, 268435456, UserHandle.getUserId(callingUid));
        } catch (RemoteException e) {
        }
        if (packageUid != callingUid) {
            throw new IllegalArgumentException("Uid " + callingUid + " cannot query restriction state for package " + packageName);
        }
        return isBackgroundRestrictedNoCheck(callingUid, packageName);
    }

    boolean isBackgroundRestrictedNoCheck(int uid, String packageName) {
        int mode = this.mAppOpsService.checkOperation(70, uid, packageName);
        return mode != 0;
    }

    public void backgroundWhitelistUid(int uid) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the OS may call backgroundWhitelistUid()");
        }
        if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
            Slog.i("ActivityManager", "Adding uid " + uid + " to bg uid whitelist");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                int N = this.mBackgroundAppIdWhitelist.length;
                int[] newList = new int[N + 1];
                System.arraycopy(this.mBackgroundAppIdWhitelist, 0, newList, 0, N);
                newList[N] = UserHandle.getAppId(uid);
                this.mBackgroundAppIdWhitelist = newList;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final ProcessRecord addAppLocked(ApplicationInfo info, String customProcess, boolean isolated, String abiOverride) {
        return addAppLocked(info, customProcess, isolated, false, false, abiOverride);
    }

    @GuardedBy({"this"})
    final ProcessRecord addAppLocked(ApplicationInfo info, String customProcess, boolean isolated, boolean disableHiddenApiChecks, boolean mountExtStorageFull, String abiOverride) {
        ProcessRecord app;
        ProcessRecord app2;
        if (!isolated) {
            app = getProcessRecordLocked(customProcess != null ? customProcess : info.processName, info.uid, true);
        } else {
            app = null;
        }
        if (app != null) {
            app2 = app;
        } else {
            ProcessRecord app3 = this.mProcessList.newProcessRecordLocked(info, customProcess, isolated, 0, new HostingRecord("added application", customProcess != null ? customProcess : info.processName));
            this.mProcessList.updateLruProcessLocked(app3, false, null);
            updateOomAdjLocked("updateOomAdj_processBegin");
            app2 = app3;
        }
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(info.packageName, false, UserHandle.getUserId(app2.uid));
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e2) {
            Slog.w("ActivityManager", "Failed trying to unstop package " + info.packageName + ": " + e2);
        }
        if ((info.flags & 9) == 9) {
            app2.setPersistent(true);
            app2.maxAdj = -800;
        }
        if (app2.thread == null && this.mPersistentStartingProcesses.indexOf(app2) < 0) {
            this.mPersistentStartingProcesses.add(app2);
            this.mProcessList.startProcessLocked(app2, new HostingRecord("added application", customProcess != null ? customProcess : app2.processName), disableHiddenApiChecks, mountExtStorageFull, abiOverride);
        }
        return app2;
    }

    public void unhandledBack() {
        this.mActivityTaskManager.unhandledBack();
    }

    public ParcelFileDescriptor openContentUri(String uriString) throws RemoteException {
        enforceNotIsolatedCaller("openContentUri");
        int userId = UserHandle.getCallingUserId();
        Uri uri = Uri.parse(uriString);
        String name = uri.getAuthority();
        ContentProviderHolder cph = getContentProviderExternalUnchecked(name, null, Binder.getCallingUid(), "*opencontent*", userId);
        if (cph != null) {
            Binder token = new Binder();
            sCallerIdentity.set(new Identity(token, Binder.getCallingPid(), Binder.getCallingUid()));
            try {
                ParcelFileDescriptor pfd = cph.provider.openFile((String) null, uri, ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD, (ICancellationSignal) null, token);
                return pfd;
            } catch (FileNotFoundException e) {
                return null;
            } finally {
                sCallerIdentity.remove();
                removeContentProviderExternalUnchecked(name, null, userId);
            }
        }
        Slog.d("ActivityManager", "Failed to get provider for authority '" + name + "'");
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportGlobalUsageEventLocked(int event) {
        this.mUsageStatsService.reportEvent(PackageManagerService.PLATFORM_PACKAGE_NAME, this.mUserController.getCurrentUserId(), event);
        int[] profiles = this.mUserController.getCurrentProfileIds();
        if (profiles != null) {
            for (int i = profiles.length - 1; i >= 0; i--) {
                this.mUsageStatsService.reportEvent((String) null, profiles[i], event);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportCurWakefulnessUsageEventLocked() {
        int i;
        if (this.mWakefulness == 1) {
            i = 15;
        } else {
            i = 16;
        }
        reportGlobalUsageEventLocked(i);
    }

    void onWakefulnessChanged(int wakefulness) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                boolean wasAwake = this.mWakefulness == 1;
                boolean isAwake = wakefulness == 1;
                this.mWakefulness = wakefulness;
                if (wasAwake != isAwake) {
                    this.mServices.updateScreenStateLocked(isAwake);
                    reportCurWakefulnessUsageEventLocked();
                    this.mActivityTaskManager.onScreenAwakeChanged(isAwake);
                    this.mOomAdjProfiler.onWakefulnessChanged(wakefulness);
                }
                updateOomAdjLocked("updateOomAdj_uiVisibility");
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void notifyCleartextNetwork(int uid, byte[] firstPacket) {
        this.mHandler.obtainMessage(49, uid, 0, firstPacket).sendToTarget();
    }

    public boolean shutdown(int timeout) {
        if (checkCallingPermission("android.permission.SHUTDOWN") != 0) {
            throw new SecurityException("Requires permission android.permission.SHUTDOWN");
        }
        boolean timedout = this.mAtmInternal.shuttingDown(this.mBooted, timeout);
        this.mAppOpsService.shutdown();
        UsageStatsManagerInternal usageStatsManagerInternal = this.mUsageStatsService;
        if (usageStatsManagerInternal != null) {
            usageStatsManagerInternal.prepareShutdown();
        }
        this.mBatteryStatsService.shutdown();
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mProcessStats.shutdownLocked();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return timedout;
    }

    public void notifyLockedProfile(int userId) {
        this.mAtmInternal.notifyLockedProfile(userId, this.mUserController.getCurrentUserId());
    }

    public void startConfirmDeviceCredentialIntent(Intent intent, Bundle options) {
        this.mAtmInternal.startConfirmDeviceCredentialIntent(intent, options);
    }

    public void stopAppSwitches() {
        this.mActivityTaskManager.stopAppSwitches();
    }

    public void resumeAppSwitches() {
        this.mActivityTaskManager.resumeAppSwitches();
    }

    public void setDebugApp(String packageName, boolean waitForDebugger, boolean persistent) {
        enforceCallingPermission("android.permission.SET_DEBUG_APP", "setDebugApp()");
        long ident = Binder.clearCallingIdentity();
        boolean z = true;
        if (persistent) {
            try {
                ContentResolver resolver = this.mContext.getContentResolver();
                Settings.Global.putString(resolver, "debug_app", packageName);
                Settings.Global.putInt(resolver, "wait_for_debugger", waitForDebugger ? 1 : 0);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        synchronized (this) {
            boostPriorityForLockedSection();
            if (!persistent) {
                this.mOrigDebugApp = this.mDebugApp;
                this.mOrigWaitForDebugger = this.mWaitForDebugger;
            }
            this.mDebugApp = packageName;
            this.mWaitForDebugger = waitForDebugger;
            if (persistent) {
                z = false;
            }
            this.mDebugTransient = z;
            if (packageName != null) {
                forceStopPackageLocked(packageName, -1, false, false, true, true, false, -1, "set debug app");
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void setAgentApp(String packageName, String agent) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") != 0) {
                    throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
                }
                if (agent == null) {
                    if (this.mAppAgentMap != null) {
                        this.mAppAgentMap.remove(packageName);
                        if (this.mAppAgentMap.isEmpty()) {
                            this.mAppAgentMap = null;
                        }
                    }
                } else {
                    if (this.mAppAgentMap == null) {
                        this.mAppAgentMap = new HashMap();
                    }
                    if (this.mAppAgentMap.size() >= 100) {
                        Slog.e("ActivityManager", "App agent map has too many entries, cannot add " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + agent);
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    this.mAppAgentMap.put(packageName, agent);
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    void setTrackAllocationApp(ApplicationInfo app, String processName) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable && (app.flags & 2) == 0) {
                    throw new SecurityException("Process not debuggable: " + app.packageName);
                }
                this.mTrackAllocationApp = processName;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    void setProfileApp(ApplicationInfo app, String processName, ProfilerInfo profilerInfo) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable && !app.isProfileableByShell()) {
                    throw new SecurityException("Process not debuggable, and not profileable by shell: " + app.packageName);
                }
                this.mProfileData.setProfileApp(processName);
                if (this.mProfileData.getProfilerInfo() != null && this.mProfileData.getProfilerInfo().profileFd != null) {
                    try {
                        this.mProfileData.getProfilerInfo().profileFd.close();
                    } catch (IOException e) {
                    }
                }
                this.mProfileData.setProfilerInfo(new ProfilerInfo(profilerInfo));
                this.mProfileType = 0;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    void setNativeDebuggingAppLocked(ApplicationInfo app, String processName) {
        boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
        if (!isDebuggable && (app.flags & 2) == 0) {
            throw new SecurityException("Process not debuggable: " + app.packageName);
        }
        this.mNativeDebuggingApp = processName;
    }

    public void setAlwaysFinish(boolean enabled) {
        enforceCallingPermission("android.permission.SET_ALWAYS_FINISH", "setAlwaysFinish()");
        long ident = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(this.mContext.getContentResolver(), "always_finish_activities", enabled ? 1 : 0);
            synchronized (this) {
                boostPriorityForLockedSection();
                this.mAlwaysFinishActivities = enabled;
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setActivityController(IActivityController controller, boolean imAMonkey) {
        this.mActivityTaskManager.setActivityController(controller, imAMonkey);
    }

    public void setUserIsMonkey(boolean userIsMonkey) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                synchronized (this.mPidsSelfLocked) {
                    int callingPid = Binder.getCallingPid();
                    ProcessRecord proc = this.mPidsSelfLocked.get(callingPid);
                    if (proc == null) {
                        throw new SecurityException("Unknown process: " + callingPid);
                    } else if (proc.getActiveInstrumentation() == null || proc.getActiveInstrumentation().mUiAutomationConnection == null) {
                        throw new SecurityException("Only an instrumentation process with a UiAutomation can call setUserIsMonkey");
                    }
                }
                this.mUserIsMonkey = userIsMonkey;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean isUserAMonkey() {
        boolean z;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                z = this.mUserIsMonkey || this.mActivityTaskManager.isControllerAMonkey();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return z;
    }

    public void requestSystemServerHeapDump() {
        ProcessRecord pr;
        if (!Build.IS_DEBUGGABLE) {
            Slog.wtf("ActivityManager", "requestSystemServerHeapDump called on a user build");
        } else if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system process is allowed to request a system heap dump");
        } else {
            synchronized (this.mPidsSelfLocked) {
                pr = this.mPidsSelfLocked.get(Process.myPid());
            }
            if (pr == null) {
                Slog.w("ActivityManager", "system process not in mPidsSelfLocked: " + Process.myPid());
                return;
            }
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    startHeapDumpLocked(pr, true);
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
        }
    }

    @Deprecated
    public void requestBugReport(int bugreportType) {
        String extraOptions;
        if (bugreportType == 0) {
            extraOptions = "bugreportfull";
        } else if (bugreportType == 1) {
            extraOptions = "bugreportplus";
        } else if (bugreportType == 2) {
            extraOptions = "bugreportremote";
        } else if (bugreportType == 3) {
            extraOptions = "bugreportwear";
        } else if (bugreportType == 4) {
            extraOptions = "bugreporttelephony";
        } else if (bugreportType == 5) {
            extraOptions = "bugreportwifi";
        } else {
            throw new IllegalArgumentException("Provided bugreport type is not correct, value: " + bugreportType);
        }
        String type = extraOptions;
        Slog.i("ActivityManager", type + " requested by UID " + Binder.getCallingUid());
        enforceCallingPermission("android.permission.DUMP", "requestBugReport");
        SystemProperties.set("dumpstate.options", extraOptions);
        SystemProperties.set("ctl.start", "bugreport");
    }

    @Deprecated
    private void requestBugReportWithDescription(String shareTitle, String shareDescription, int bugreportType) {
        if (!TextUtils.isEmpty(shareTitle)) {
            if (shareTitle.length() > 50) {
                throw new IllegalArgumentException("shareTitle should be less than 50 characters");
            }
            if (!TextUtils.isEmpty(shareDescription)) {
                try {
                    int length = shareDescription.getBytes("UTF-8").length;
                    if (length > 91) {
                        throw new IllegalArgumentException("shareTitle should be less than 91 bytes");
                    }
                    SystemProperties.set("dumpstate.options.description", shareDescription);
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalArgumentException("shareDescription: UnsupportedEncodingException");
                }
            }
            SystemProperties.set("dumpstate.options.title", shareTitle);
        }
        Slog.d("ActivityManager", "Bugreport notification title " + shareTitle + " description " + shareDescription);
        requestBugReport(bugreportType);
    }

    @Deprecated
    public void requestTelephonyBugReport(String shareTitle, String shareDescription) {
        requestBugReportWithDescription(shareTitle, shareDescription, 4);
    }

    @Deprecated
    public void requestWifiBugReport(String shareTitle, String shareDescription) {
        requestBugReportWithDescription(shareTitle, shareDescription, 5);
    }

    public void registerProcessObserver(IProcessObserver observer) {
        enforceCallingPermission("android.permission.SET_ACTIVITY_WATCHER", "registerProcessObserver()");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mProcessObservers.register(observer);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void unregisterProcessObserver(IProcessObserver observer) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mProcessObservers.unregister(observer);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isActiveInstrumentation(int uid) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                for (int i = this.mActiveInstrumentation.size() - 1; i >= 0; i--) {
                    ActiveInstrumentation instrumentation = this.mActiveInstrumentation.get(i);
                    for (int j = instrumentation.mRunningProcesses.size() - 1; j >= 0; j--) {
                        ProcessRecord process = instrumentation.mRunningProcesses.get(j);
                        if (process.uid == uid) {
                            resetPriorityAfterLockedSection();
                            return true;
                        }
                    }
                }
                resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getUidProcessState(int uid, String callingPackage) {
        int uidProcStateLocked;
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission("android.permission.PACKAGE_USAGE_STATS", "getUidProcessState");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                uidProcStateLocked = this.mProcessList.getUidProcStateLocked(uid);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return uidProcStateLocked;
    }

    public void registerUidObserver(IUidObserver observer, int which, int cutpoint, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission("android.permission.PACKAGE_USAGE_STATS", "registerUidObserver");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mUidObservers.register(observer, new UidObserverRegistration(Binder.getCallingUid(), callingPackage, which, cutpoint));
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void unregisterUidObserver(IUidObserver observer) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mUidObservers.unregister(observer);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean isUidActive(int uid, String callingPackage) {
        boolean isUidActiveLocked;
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission("android.permission.PACKAGE_USAGE_STATS", "isUidActive");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                isUidActiveLocked = isUidActiveLocked(uid);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return isUidActiveLocked;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUidActiveLocked(int uid) {
        UidRecord uidRecord = this.mProcessList.getUidRecordLocked(uid);
        return (uidRecord == null || uidRecord.setIdle) ? false : true;
    }

    public void setPersistentVrThread(int tid) {
        this.mActivityTaskManager.setPersistentVrThread(tid);
    }

    public static boolean scheduleAsRegularPriority(int tid, boolean suppressLogs) {
        try {
            Process.setThreadScheduler(tid, 0, 0);
            return true;
        } catch (IllegalArgumentException e) {
            if (!suppressLogs) {
                Slog.w("ActivityManager", "Failed to set scheduling policy, thread does not exist:\n" + e);
            }
            return false;
        } catch (SecurityException e2) {
            if (!suppressLogs) {
                Slog.w("ActivityManager", "Failed to set scheduling policy, not allowed:\n" + e2);
            }
            return false;
        }
    }

    public static boolean scheduleAsFifoPriority(int tid, boolean suppressLogs) {
        try {
            Process.setThreadScheduler(tid, WindowManagerPolicy.COLOR_FADE_LAYER, 1);
            return true;
        } catch (IllegalArgumentException e) {
            if (!suppressLogs) {
                Slog.w("ActivityManager", "Failed to set scheduling policy, thread does not exist:\n" + e);
                return false;
            }
            return false;
        } catch (SecurityException e2) {
            if (!suppressLogs) {
                Slog.w("ActivityManager", "Failed to set scheduling policy, not allowed:\n" + e2);
                return false;
            }
            return false;
        }
    }

    public void setRenderThread(int tid) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                int pid = Binder.getCallingPid();
                if (pid == Process.myPid()) {
                    demoteSystemServerRenderThread(tid);
                    resetPriorityAfterLockedSection();
                    return;
                }
                synchronized (this.mPidsSelfLocked) {
                    ProcessRecord proc = this.mPidsSelfLocked.get(pid);
                    if (proc != null && proc.renderThreadTid == 0 && tid > 0) {
                        if (!Process.isThreadInProcess(pid, tid)) {
                            throw new IllegalArgumentException("Render thread does not belong to process");
                        }
                        proc.renderThreadTid = tid;
                        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                            Slog.d("UI_FIFO", "Set RenderThread tid " + tid + " for pid " + pid);
                        }
                        if (proc.getCurrentSchedulingGroup() == 3) {
                            if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                                Slog.d("UI_FIFO", "Promoting " + tid + "out of band");
                            }
                            if (this.mUseFifoUiScheduling) {
                                Process.setThreadScheduler(proc.renderThreadTid, WindowManagerPolicy.COLOR_FADE_LAYER, 1);
                            } else {
                                Process.setThreadPriority(proc.renderThreadTid, -10);
                            }
                        }
                    } else if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                        Slog.d("UI_FIFO", "Didn't set thread from setRenderThread? PID: " + pid + ", TID: " + tid + " FIFO: " + this.mUseFifoUiScheduling);
                    }
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private void demoteSystemServerRenderThread(int tid) {
        Process.setThreadPriority(tid, 10);
    }

    public boolean isVrModePackageEnabled(ComponentName packageName) {
        this.mActivityTaskManager.enforceSystemHasVrFeature();
        VrManagerInternal vrService = (VrManagerInternal) LocalServices.getService(VrManagerInternal.class);
        return vrService.hasVrPackage(packageName, UserHandle.getCallingUserId()) == 0;
    }

    public boolean isTopActivityImmersive() {
        return this.mActivityTaskManager.isTopActivityImmersive();
    }

    public boolean isTopOfTask(IBinder token) {
        return this.mActivityTaskManager.isTopOfTask(token);
    }

    public void setHasTopUi(boolean hasTopUi) throws RemoteException {
        if (checkCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW") != 0) {
            String msg = "Permission Denial: setHasTopUi() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERNAL_SYSTEM_WINDOW";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        int pid = Binder.getCallingPid();
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                boolean changed = false;
                synchronized (this.mPidsSelfLocked) {
                    ProcessRecord pr = this.mPidsSelfLocked.get(pid);
                    if (pr == null) {
                        Slog.w("ActivityManager", "setHasTopUi called on unknown pid: " + pid);
                        resetPriorityAfterLockedSection();
                        return;
                    }
                    if (pr.hasTopUi() != hasTopUi) {
                        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                            Slog.d("ActivityManager", "Setting hasTopUi=" + hasTopUi + " for pid=" + pid);
                        }
                        pr.setHasTopUi(hasTopUi);
                        changed = true;
                    }
                    if (changed) {
                        updateOomAdjLocked(pr, true, "updateOomAdj_uiVisibility");
                    }
                    resetPriorityAfterLockedSection();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public final void enterSafeMode() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (!this.mSystemReady) {
                    try {
                        AppGlobals.getPackageManager().enterSafeMode();
                    } catch (RemoteException e) {
                    }
                }
                this.mSafeMode = true;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public final void showSafeModeOverlay() {
        View v = LayoutInflater.from(this.mContext).inflate(17367269, (ViewGroup) null);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = 2015;
        lp.width = -2;
        lp.height = -2;
        lp.gravity = 8388691;
        lp.format = v.getBackground().getOpacity();
        lp.flags = 24;
        lp.privateFlags |= 16;
        ((WindowManager) this.mContext.getSystemService("window")).addView(v, lp);
    }

    public void noteWakeupAlarm(IIntentSender sender, WorkSource workSource, int sourceUid, String sourcePkg, String tag) {
        WorkSource workSource2;
        int sourceUid2;
        int standbyBucket;
        String str;
        String str2;
        if (workSource != null && workSource.isEmpty()) {
            workSource2 = null;
        } else {
            workSource2 = workSource;
        }
        if (sourceUid <= 0 && workSource2 == null) {
            if (sender == null || !(sender instanceof PendingIntentRecord)) {
                return;
            }
            PendingIntentRecord rec = (PendingIntentRecord) sender;
            int callerUid = Binder.getCallingUid();
            sourceUid2 = rec.uid == callerUid ? 1000 : rec.uid;
        } else {
            sourceUid2 = sourceUid;
        }
        int standbyBucket2 = 0;
        this.mBatteryStatsService.noteWakupAlarm(sourcePkg, sourceUid2, workSource2, tag);
        if (workSource2 == null) {
            UsageStatsManagerInternal usageStatsManagerInternal = this.mUsageStatsService;
            if (usageStatsManagerInternal == null) {
                standbyBucket = 0;
            } else {
                standbyBucket = usageStatsManagerInternal.getAppStandbyBucket(sourcePkg, UserHandle.getUserId(sourceUid2), SystemClock.elapsedRealtime());
            }
            StatsLog.write_non_chained(35, sourceUid2, null, tag, sourcePkg, standbyBucket);
            if (ActivityManagerDebugConfig.DEBUG_POWER) {
                Slog.w("ActivityManager", "noteWakeupAlarm[ sourcePkg=" + sourcePkg + ", sourceUid=" + sourceUid2 + ", workSource=" + workSource2 + ", tag=" + tag + ", standbyBucket=" + standbyBucket + "]");
                return;
            }
            return;
        }
        String workSourcePackage = workSource2.getName(0);
        int workSourceUid = workSource2.getAttributionUid();
        if (workSourcePackage == null) {
            workSourcePackage = sourcePkg;
            workSourceUid = sourceUid2;
        }
        UsageStatsManagerInternal usageStatsManagerInternal2 = this.mUsageStatsService;
        if (usageStatsManagerInternal2 != null) {
            str = ", standbyBucket=";
            str2 = ", tag=";
            standbyBucket2 = usageStatsManagerInternal2.getAppStandbyBucket(workSourcePackage, UserHandle.getUserId(workSourceUid), SystemClock.elapsedRealtime());
        } else {
            str = ", standbyBucket=";
            str2 = ", tag=";
        }
        StatsLog.write(35, workSource2, tag, sourcePkg, standbyBucket2);
        if (ActivityManagerDebugConfig.DEBUG_POWER) {
            Slog.w("ActivityManager", "noteWakeupAlarm[ sourcePkg=" + sourcePkg + ", sourceUid=" + sourceUid2 + ", workSource=" + workSource2 + str2 + tag + str + standbyBucket2 + " wsName=" + workSourcePackage + ")]");
        }
    }

    public void noteAlarmStart(IIntentSender sender, WorkSource workSource, int sourceUid, String tag) {
        if (workSource != null && workSource.isEmpty()) {
            workSource = null;
        }
        if (sourceUid <= 0 && workSource == null) {
            if (sender == null || !(sender instanceof PendingIntentRecord)) {
                return;
            }
            PendingIntentRecord rec = (PendingIntentRecord) sender;
            int callerUid = Binder.getCallingUid();
            sourceUid = rec.uid == callerUid ? 1000 : rec.uid;
        }
        if (ActivityManagerDebugConfig.DEBUG_POWER) {
            Slog.w("ActivityManager", "noteAlarmStart[sourceUid=" + sourceUid + ", workSource=" + workSource + ", tag=" + tag + "]");
        }
        this.mBatteryStatsService.noteAlarmStart(tag, workSource, sourceUid);
    }

    public void noteAlarmFinish(IIntentSender sender, WorkSource workSource, int sourceUid, String tag) {
        if (workSource != null && workSource.isEmpty()) {
            workSource = null;
        }
        if (sourceUid <= 0 && workSource == null) {
            if (sender == null || !(sender instanceof PendingIntentRecord)) {
                return;
            }
            PendingIntentRecord rec = (PendingIntentRecord) sender;
            int callerUid = Binder.getCallingUid();
            sourceUid = rec.uid == callerUid ? 1000 : rec.uid;
        }
        if (ActivityManagerDebugConfig.DEBUG_POWER) {
            Slog.w("ActivityManager", "noteAlarmFinish[sourceUid=" + sourceUid + ", workSource=" + workSource + ", tag=" + tag + "]");
        }
        this.mBatteryStatsService.noteAlarmFinish(tag, workSource, sourceUid);
    }

    public boolean killPids(int[] pids, String pReason, boolean secure) {
        int type;
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("killPids only available to the system");
        }
        String reason = pReason == null ? "Unknown" : pReason;
        boolean killed = false;
        synchronized (this.mPidsSelfLocked) {
            int worstType = 0;
            for (int i : pids) {
                ProcessRecord proc = this.mPidsSelfLocked.get(i);
                if (proc != null && (type = proc.setAdj) > worstType) {
                    worstType = type;
                }
            }
            if (worstType < 999 && worstType > 900) {
                worstType = 900;
            }
            if (!secure && worstType < 500) {
                worstType = SystemService.PHASE_SYSTEM_SERVICES_READY;
            }
            Slog.w("ActivityManager", "Killing processes " + reason + " at adjustment " + worstType);
            for (int i2 : pids) {
                ProcessRecord proc2 = this.mPidsSelfLocked.get(i2);
                if (proc2 != null) {
                    int adj = proc2.setAdj;
                    if (adj >= worstType && !proc2.killedByAm) {
                        proc2.kill(reason, true);
                        killed = true;
                    }
                }
            }
        }
        return killed;
    }

    public void killUid(int appId, int userId, String reason) {
        enforceCallingPermission("android.permission.KILL_UID", "killUid");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long identity = Binder.clearCallingIdentity();
                this.mProcessList.killPackageProcessesLocked(null, appId, userId, -800, false, true, true, true, false, reason != null ? reason : "kill uid");
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean killProcessesBelowForeground(String reason) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("killProcessesBelowForeground() only available to system");
        }
        return killProcessesBelowAdj(0, reason);
    }

    private boolean killProcessesBelowAdj(int belowAdj, String reason) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("killProcessesBelowAdj() only available to system");
        }
        boolean killed = false;
        synchronized (this.mPidsSelfLocked) {
            int size = this.mPidsSelfLocked.size();
            for (int i = 0; i < size; i++) {
                this.mPidsSelfLocked.keyAt(i);
                ProcessRecord proc = this.mPidsSelfLocked.valueAt(i);
                if (proc != null) {
                    int adj = proc.setAdj;
                    if (adj > belowAdj && !proc.killedByAm) {
                        proc.kill(reason, true);
                        killed = true;
                    }
                }
            }
        }
        return killed;
    }

    public void hang(IBinder who, boolean allowRestart) {
        if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") != 0) {
            throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
        }
        IBinder.DeathRecipient death = new IBinder.DeathRecipient() { // from class: com.android.server.am.ActivityManagerService.16
            @Override // android.os.IBinder.DeathRecipient
            public void binderDied() {
                synchronized (this) {
                    notifyAll();
                }
            }
        };
        try {
            who.linkToDeath(death, 0);
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    Watchdog.getInstance().setAllowRestart(allowRestart);
                    Slog.i("ActivityManager", "Hanging system process at request of pid " + Binder.getCallingPid());
                    synchronized (death) {
                        while (who.isBinderAlive()) {
                            try {
                                death.wait();
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                    Watchdog.getInstance().setAllowRestart(true);
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
        } catch (RemoteException e2) {
            Slog.w("ActivityManager", "hang: given caller IBinder is already dead.");
        }
    }

    public void restart() {
        if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") != 0) {
            throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
        }
        Log.i("ActivityManager", "Sending shutdown broadcast...");
        BroadcastReceiver br = new BroadcastReceiver() { // from class: com.android.server.am.ActivityManagerService.17
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                Log.i("ActivityManager", "Shutting down activity manager...");
                ActivityManagerService.this.shutdown(10000);
                Log.i("ActivityManager", "Shutdown complete, restarting!");
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        };
        Intent intent = new Intent("android.intent.action.ACTION_SHUTDOWN");
        intent.addFlags(268435456);
        intent.putExtra("android.intent.extra.SHUTDOWN_USERSPACE_ONLY", true);
        br.onReceive(this.mContext, intent);
    }

    private long getLowRamTimeSinceIdle(long now) {
        long j = this.mLowRamTimeSinceLastIdle;
        long j2 = this.mLowRamStartTime;
        return j + (j2 > 0 ? now - j2 : 0L);
    }

    public void performIdleMaintenance() {
        int i;
        boolean z;
        if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") != 0) {
            throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long now = SystemClock.uptimeMillis();
                long timeSinceLastIdle = now - this.mLastIdleTime;
                this.mOomAdjuster.mAppCompact.compactAllSystem();
                long lowRamSinceLastIdle = getLowRamTimeSinceIdle(now);
                this.mLastIdleTime = now;
                long j = 0;
                this.mLowRamTimeSinceLastIdle = 0L;
                if (this.mLowRamStartTime != 0) {
                    this.mLowRamStartTime = now;
                }
                int i2 = 128;
                StringBuilder sb = new StringBuilder(128);
                sb.append("Idle maintenance over ");
                TimeUtils.formatDuration(timeSinceLastIdle, sb);
                sb.append(" low RAM for ");
                TimeUtils.formatDuration(lowRamSinceLastIdle, sb);
                Slog.i("ActivityManager", sb.toString());
                boolean z2 = true;
                boolean doKilling = lowRamSinceLastIdle > timeSinceLastIdle / 3;
                long totalMemoryInKb = Process.getTotalMemory() / 1000;
                long memoryGrowthThreshold = Math.max(totalMemoryInKb / 100, (long) JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
                int i3 = this.mProcessList.mLruProcesses.size() - 1;
                while (i3 >= 0) {
                    ProcessRecord proc = this.mProcessList.mLruProcesses.get(i3);
                    if (proc.notCachedSinceIdle) {
                        if (proc.setProcState < 6 || proc.setProcState > 11) {
                            i = i2;
                            z = z2;
                        } else if (!doKilling || proc.initialIdlePss == j || proc.lastPss <= (proc.initialIdlePss * 3) / 2 || proc.lastPss <= proc.initialIdlePss + memoryGrowthThreshold) {
                            i = i2;
                            z = z2;
                        } else {
                            StringBuilder sb2 = new StringBuilder(i2);
                            sb2.append("Kill");
                            sb2.append(proc.processName);
                            sb2.append(" in idle maint: pss=");
                            sb2.append(proc.lastPss);
                            sb2.append(", swapPss=");
                            sb2.append(proc.lastSwapPss);
                            sb2.append(", initialPss=");
                            sb2.append(proc.initialIdlePss);
                            sb2.append(", period=");
                            TimeUtils.formatDuration(timeSinceLastIdle, sb2);
                            sb2.append(", lowRamPeriod=");
                            TimeUtils.formatDuration(lowRamSinceLastIdle, sb2);
                            Slog.wtfQuiet("ActivityManager", sb2.toString());
                            proc.kill("idle maint (pss " + proc.lastPss + " from " + proc.initialIdlePss + ")", z2);
                            i = i2;
                            z = z2;
                        }
                    } else if (proc.setProcState >= 15 || proc.setProcState < 0) {
                        i = i2;
                        z = z2;
                    } else {
                        proc.notCachedSinceIdle = z2;
                        proc.initialIdlePss = 0L;
                        z = z2;
                        i = 128;
                        proc.nextPssTime = ProcessList.computeNextPssTime(proc.setProcState, null, this.mTestPssMode, this.mAtmInternal.isSleeping(), now);
                    }
                    i3--;
                    i2 = i;
                    z2 = z;
                    j = 0;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void sendIdleJobTrigger() {
        if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") != 0) {
            throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(ACTION_TRIGGER_IDLE).setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME).addFlags(1073741824);
            broadcastIntent(null, intent, null, null, 0, null, null, null, -1, null, false, false, -1);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void retrieveSettings() {
        ContentResolver resolver = this.mContext.getContentResolver();
        this.mActivityTaskManager.retrieveSettings(resolver);
        String debugApp = Settings.Global.getString(resolver, "debug_app");
        boolean waitForDebugger = Settings.Global.getInt(resolver, "wait_for_debugger", 0) != 0;
        boolean alwaysFinishActivities = Settings.Global.getInt(resolver, "always_finish_activities", 0) != 0;
        long waitForNetworkTimeoutMs = Settings.Global.getLong(resolver, "network_access_timeout_ms", NETWORK_ACCESS_TIMEOUT_DEFAULT_MS);
        this.mHiddenApiBlacklist.registerObserver();
        long pssDeferralMs = DeviceConfig.getLong("activity_manager", ACTIVITY_START_PSS_DEFER_CONFIG, 0L);
        DeviceConfig.addOnPropertiesChangedListener("activity_manager", ActivityThread.currentApplication().getMainExecutor(), this.mPssDelayConfigListener);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mOrigDebugApp = debugApp;
                this.mDebugApp = debugApp;
                this.mOrigWaitForDebugger = waitForDebugger;
                this.mWaitForDebugger = waitForDebugger;
                this.mAlwaysFinishActivities = alwaysFinishActivities;
                Resources res = this.mContext.getResources();
                this.mAppErrors.loadAppsNotReportingCrashesFromConfigLocked(res.getString(17039672));
                this.mUserController.mUserSwitchUiEnabled = res.getBoolean(17891396) ? false : true;
                this.mUserController.mMaxRunningUsers = res.getInteger(17694846);
                this.mUserController.mDelayUserDataLocking = res.getBoolean(17891484);
                this.mWaitForNetworkTimeoutMs = waitForNetworkTimeoutMs;
                this.mPssDeferralTime = pssDeferralMs;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX WARN: Removed duplicated region for block: B:114:0x02d8 A[Catch: all -> 0x0332, TryCatch #13 {all -> 0x0332, blocks: (B:95:0x0287, B:112:0x02d0, B:114:0x02d8, B:115:0x02de, B:116:0x02fd, B:121:0x032d, B:106:0x02a8, B:111:0x02b2, B:105:0x02a0), top: B:170:0x019a }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void systemReady(java.lang.Runnable r46, android.util.TimingsTraceLog r47) {
        /*
            Method dump skipped, instructions count: 853
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.systemReady(java.lang.Runnable, android.util.TimingsTraceLog):void");
    }

    public /* synthetic */ void lambda$systemReady$1$ActivityManagerService(PowerSaveState state) {
        updateForceBackgroundCheck(state.batterySaverEnabled);
    }

    private void watchDeviceProvisioning(final Context context) {
        if (isDeviceProvisioned(context)) {
            SystemProperties.set(SYSTEM_PROPERTY_DEVICE_PROVISIONED, "1");
        } else {
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor("device_provisioned"), false, new ContentObserver(new Handler(Looper.getMainLooper())) { // from class: com.android.server.am.ActivityManagerService.20
                @Override // android.database.ContentObserver
                public void onChange(boolean selfChange) {
                    if (ActivityManagerService.this.isDeviceProvisioned(context)) {
                        SystemProperties.set(ActivityManagerService.SYSTEM_PROPERTY_DEVICE_PROVISIONED, "1");
                        context.getContentResolver().unregisterContentObserver(this);
                    }
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isDeviceProvisioned(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), "device_provisioned", 0) != 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startBroadcastObservers() {
        BroadcastQueue[] broadcastQueueArr;
        for (BroadcastQueue queue : this.mBroadcastQueues) {
            queue.start(this.mContext.getContentResolver());
        }
    }

    private void updateForceBackgroundCheck(boolean enabled) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (this.mForceBackgroundCheck != enabled) {
                    this.mForceBackgroundCheck = enabled;
                    if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Force background check ");
                        sb.append(enabled ? "enabled" : "disabled");
                        Slog.i("ActivityManager", sb.toString());
                    }
                    if (this.mForceBackgroundCheck) {
                        this.mProcessList.doStopUidForIdleUidsLocked();
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void killAppAtUsersRequest(ProcessRecord app, Dialog fromDialog) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mAppErrors.killAppAtUserRequestLocked(app, fromDialog);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void skipCurrentReceiverLocked(ProcessRecord app) {
        BroadcastQueue[] broadcastQueueArr;
        for (BroadcastQueue queue : this.mBroadcastQueues) {
            queue.skipCurrentReceiverLocked(app);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Intent createAppErrorServiceLocked(ProcessRecord r, long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = createErrorServiceLocked(r, timeMillis, crashInfo);
        if (report == null) {
            return null;
        }
        Intent result = new Intent("android.intent.action.APP_ERROR");
        result.setPackage("com.xiaopeng.bughunter");
        if (r != null && "com.xiaopeng.napa".equals(r.processName)) {
            result.putExtra("com.xiaopeng.bughunger.extra.boolean.LOG_UPLOAD", true);
        }
        result.putExtra("android.intent.extra.BUG_REPORT", report);
        return result;
    }

    Intent createNativeErrorServiceLocked(long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = new ApplicationErrorReport();
        report.crashInfo = crashInfo;
        report.time = timeMillis;
        report.type = 1;
        Intent result = new Intent("android.intent.action.APP_ERROR");
        result.setPackage("com.xiaopeng.bughunter");
        result.putExtra("android.intent.extra.BUG_REPORT", report);
        result.putExtra("android.intent.extra.boolean.NATIVE_REPORT", true);
        return result;
    }

    private ApplicationErrorReport createErrorServiceLocked(ProcessRecord r, long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        if (!r.isCrashing() && !r.isNotResponding() && !r.forceCrashReport) {
            return null;
        }
        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & 1) != 0;
        if (r.isCrashing() || r.forceCrashReport) {
            report.type = 1;
            report.crashInfo = crashInfo;
        } else if (r.isNotResponding()) {
            report.type = 2;
            report.anrInfo = new ApplicationErrorReport.AnrInfo();
            report.anrInfo.activity = r.notRespondingReport.tag;
            report.anrInfo.cause = r.notRespondingReport.shortMsg;
            report.anrInfo.info = r.notRespondingReport.longMsg;
        }
        return report;
    }

    public void handleApplicationCrash(IBinder app, ApplicationErrorReport.ParcelableCrashInfo crashInfo) {
        String processName;
        ProcessRecord r = findAppProcess(app, "Crash");
        if (app == null) {
            processName = "system_server";
        } else {
            processName = r == null ? UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN : r.processName;
        }
        handleApplicationCrashInner("crash", r, processName, crashInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleApplicationCrashInner(String eventType, ProcessRecord r, String processName, ApplicationErrorReport.CrashInfo crashInfo) {
        int i;
        int i2;
        int processClassEnum;
        Object[] objArr = new Object[8];
        objArr[0] = Integer.valueOf(Binder.getCallingPid());
        objArr[1] = Integer.valueOf(UserHandle.getUserId(Binder.getCallingUid()));
        objArr[2] = processName;
        objArr[3] = Integer.valueOf(r == null ? -1 : r.info.flags);
        objArr[4] = crashInfo.exceptionClassName;
        objArr[5] = crashInfo.exceptionMessage;
        objArr[6] = crashInfo.throwFileName;
        objArr[7] = Integer.valueOf(crashInfo.throwLineNumber);
        EventLog.writeEvent((int) EventLogTags.AM_CRASH, objArr);
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        String str = (r == null || r.info == null) ? "" : r.info.packageName;
        if (r != null && r.info != null) {
            if (r.info.isInstantApp()) {
                i = 2;
            } else {
                i = 1;
            }
        } else {
            i = 0;
        }
        if (r == null) {
            i2 = 0;
        } else if (r.isInterestingToUserLocked()) {
            i2 = 2;
        } else {
            i2 = 1;
        }
        if (processName.equals("system_server")) {
            processClassEnum = 3;
        } else {
            processClassEnum = r != null ? r.getProcessClassEnum() : 0;
        }
        StatsLog.write(78, callingUid, eventType, processName, callingPid, str, i, i2, processClassEnum);
        int relaunchReason = r != null ? r.getWindowProcessController().computeRelaunchReason() : 0;
        String relaunchReasonString = ActivityTaskManagerService.relaunchReasonToString(relaunchReason);
        if (crashInfo.crashTag == null) {
            crashInfo.crashTag = relaunchReasonString;
        } else {
            crashInfo.crashTag += " " + relaunchReasonString;
        }
        long time = System.currentTimeMillis();
        StringBuilder builder = new StringBuilder();
        builder.append("xiaopengbughunter_");
        builder.append(processName);
        builder.append("_");
        builder.append(eventType);
        builder.append("_" + time);
        addErrorToDropBox(builder.toString(), r, processName, null, null, null, null, null, null, crashInfo);
        this.mAppErrors.crashApplication(r, crashInfo, time);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleNativeCrashInner(int pid, String eventType, ApplicationErrorReport.CrashInfo crashInfo) {
        long time = System.currentTimeMillis();
        StringBuilder builder = new StringBuilder();
        builder.append("xiaopengbughunter_");
        builder.append(pid);
        builder.append("_");
        builder.append(eventType);
        builder.append("_" + time);
        addNativeErrorToDropBox(builder.toString(), crashInfo);
        Intent nativeErrorService = createNativeErrorServiceLocked(time, crashInfo);
        if (nativeErrorService != null) {
            try {
                this.mContext.startServiceAsUser(nativeErrorService, UserHandle.SYSTEM);
            } catch (Exception e) {
                Slog.w("ActivityManager", "native report receiver dissappeared", e);
            }
        }
    }

    public void handleApplicationStrictModeViolation(IBinder app, int penaltyMask, StrictMode.ViolationInfo info) {
        ProcessRecord r = findAppProcess(app, "StrictMode");
        if ((67108864 & penaltyMask) != 0) {
            Integer stackFingerprint = Integer.valueOf(info.hashCode());
            boolean logIt = true;
            synchronized (this.mAlreadyLoggedViolatedStacks) {
                if (this.mAlreadyLoggedViolatedStacks.contains(stackFingerprint)) {
                    logIt = false;
                } else {
                    if (this.mAlreadyLoggedViolatedStacks.size() >= 5000) {
                        this.mAlreadyLoggedViolatedStacks.clear();
                    }
                    this.mAlreadyLoggedViolatedStacks.add(stackFingerprint);
                }
            }
            if (logIt) {
                logStrictModeViolationToDropBox(r, info);
            }
        }
        if ((536870912 & penaltyMask) != 0) {
            AppErrorResult result = new AppErrorResult();
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    long origId = Binder.clearCallingIdentity();
                    Message msg = Message.obtain();
                    msg.what = SHOW_STRICT_MODE_VIOLATION_UI_MSG;
                    HashMap<String, Object> data = new HashMap<>();
                    data.put("result", result);
                    data.put("app", r);
                    data.put("info", info);
                    msg.obj = data;
                    this.mUiHandler.sendMessage(msg);
                    Binder.restoreCallingIdentity(origId);
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            int res = result.get();
            Slog.w("ActivityManager", "handleApplicationStrictModeViolation; res=" + res);
        }
    }

    private void logStrictModeViolationToDropBox(ProcessRecord process, StrictMode.ViolationInfo info) {
        String[] strArr;
        if (info == null) {
            return;
        }
        boolean isSystemApp = process == null || (process.info.flags & 129) != 0;
        String processName = process == null ? UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN : process.processName;
        final DropBoxManager dbox = (DropBoxManager) this.mContext.getSystemService("dropbox");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(processClass(process) + "_");
        if (process != null) {
            stringBuilder.append(processName + "_");
        }
        stringBuilder.append(info.getViolationClass().getSimpleName() + "_strictmode");
        final String dropboxTag = stringBuilder.toString();
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) {
            return;
        }
        StringBuilder sb = new StringBuilder(1024);
        synchronized (sb) {
            appendDropBoxProcessHeaders(process, processName, sb);
            sb.append("Build: ");
            sb.append(Build.FINGERPRINT);
            sb.append("\n");
            sb.append("System-App: ");
            sb.append(isSystemApp);
            sb.append("\n");
            sb.append("Uptime-Millis: ");
            sb.append(info.violationUptimeMillis);
            sb.append("\n");
            if (info.violationNumThisLoop != 0) {
                sb.append("Loop-Violation-Number: ");
                sb.append(info.violationNumThisLoop);
                sb.append("\n");
            }
            if (info.numAnimationsRunning != 0) {
                sb.append("Animations-Running: ");
                sb.append(info.numAnimationsRunning);
                sb.append("\n");
            }
            if (info.broadcastIntentAction != null) {
                sb.append("Broadcast-Intent-Action: ");
                sb.append(info.broadcastIntentAction);
                sb.append("\n");
            }
            if (info.durationMillis != -1) {
                sb.append("Duration-Millis: ");
                sb.append(info.durationMillis);
                sb.append("\n");
            }
            if (info.numInstances != -1) {
                sb.append("Instance-Count: ");
                sb.append(info.numInstances);
                sb.append("\n");
            }
            if (info.tags != null) {
                for (String tag : info.tags) {
                    sb.append("Span-Tag: ");
                    sb.append(tag);
                    sb.append("\n");
                }
            }
            sb.append("\n");
            sb.append(info.getStackTrace());
            sb.append("\n");
            if (info.getViolationDetails() != null) {
                sb.append(info.getViolationDetails());
                sb.append("\n");
            }
        }
        final String res = sb.toString();
        IoThread.getHandler().post(new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityManagerService$30I5N5ZS7997YvRBJqVkTZMPd6M
            @Override // java.lang.Runnable
            public final void run() {
                dbox.addText(dropboxTag, res);
            }
        });
    }

    public boolean handleApplicationWtf(final IBinder app, final String tag, boolean system, final ApplicationErrorReport.ParcelableCrashInfo crashInfo) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        if (system) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.am.ActivityManagerService.21
                @Override // java.lang.Runnable
                public void run() {
                    ActivityManagerService.this.handleApplicationWtfInner(callingUid, callingPid, app, tag, crashInfo);
                }
            });
            return false;
        }
        ProcessRecord r = handleApplicationWtfInner(callingUid, callingPid, app, tag, crashInfo);
        boolean isFatal = Build.IS_ENG || Settings.Global.getInt(this.mContext.getContentResolver(), "wtf_is_fatal", 0) != 0;
        boolean isSystem = r == null || r.isPersistent();
        if (!isFatal || isSystem) {
            return false;
        }
        this.mAppErrors.crashApplication(r, crashInfo, 0L);
        return true;
    }

    ProcessRecord handleApplicationWtfInner(int callingUid, int callingPid, IBinder app, String tag, ApplicationErrorReport.CrashInfo crashInfo) {
        String str;
        ProcessRecord r = findAppProcess(app, "WTF");
        if (app == null) {
            str = "system_server";
        } else {
            str = r == null ? UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN : r.processName;
        }
        String processName = str;
        Object[] objArr = new Object[6];
        objArr[0] = Integer.valueOf(UserHandle.getUserId(callingUid));
        objArr[1] = Integer.valueOf(callingPid);
        objArr[2] = processName;
        objArr[3] = Integer.valueOf(r == null ? -1 : r.info.flags);
        objArr[4] = tag;
        objArr[5] = crashInfo.exceptionMessage;
        EventLog.writeEvent((int) EventLogTags.AM_WTF, objArr);
        StatsLog.write(80, callingUid, tag, processName, callingPid, r != null ? r.getProcessClassEnum() : 0);
        addErrorToDropBox("wtf", r, processName, null, null, null, tag, null, null, crashInfo);
        return r;
    }

    private ProcessRecord findAppProcess(IBinder app, String reason) {
        ProcessRecord findAppProcessLocked;
        if (app == null) {
            return null;
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                findAppProcessLocked = this.mProcessList.findAppProcessLocked(app, reason);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return findAppProcessLocked;
    }

    private void appendDropBoxProcessHeaders(ProcessRecord process, String processName, StringBuilder sb) {
        if (process == null) {
            sb.append("Process: ");
            sb.append(processName);
            sb.append("\n");
            return;
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                sb.append("Process: ");
                sb.append(processName);
                sb.append("\n");
                sb.append("PID: ");
                sb.append(process.pid);
                sb.append("\n");
                sb.append("UID: ");
                sb.append(process.uid);
                sb.append("\n");
                int flags = process.info.flags;
                IPackageManager pm = AppGlobals.getPackageManager();
                sb.append("Flags: 0x");
                sb.append(Integer.toHexString(flags));
                sb.append("\n");
                for (int ip = 0; ip < process.pkgList.size(); ip++) {
                    String pkg = process.pkgList.keyAt(ip);
                    sb.append("Package: ");
                    sb.append(pkg);
                    try {
                        PackageInfo pi = pm.getPackageInfo(pkg, 0, UserHandle.getCallingUserId());
                        if (pi != null) {
                            sb.append(" v");
                            sb.append(pi.getLongVersionCode());
                            if (pi.versionName != null) {
                                sb.append(" (");
                                sb.append(pi.versionName);
                                sb.append(")");
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.e("ActivityManager", "Error getting package info: " + pkg, e);
                    }
                    sb.append("\n");
                }
                if (process.info.isInstantApp()) {
                    sb.append("Instant-App: true\n");
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    private static String processClass(ProcessRecord process) {
        if (process == null || process.pid == MY_PID) {
            return "system_server";
        }
        return "app";
    }

    public void addErrorToDropBox(String eventType, ProcessRecord process, String processName, String activityShortComponentName, String parentShortComponentName, ProcessRecord parentProcess, String subject, final String report, final File dataFile, final ApplicationErrorReport.CrashInfo crashInfo) {
        if (ServiceManager.getService("dropbox") == null) {
            return;
        }
        final DropBoxManager dbox = (DropBoxManager) this.mContext.getSystemService(DropBoxManager.class);
        final String dropboxTag = processClass(process) + "_" + eventType;
        if (dbox != null && dbox.isTagEnabled(dropboxTag)) {
            long now = SystemClock.elapsedRealtime();
            if (now - this.mWtfClusterStart > JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY) {
                this.mWtfClusterStart = now;
                this.mWtfClusterCount = 1;
            } else {
                int i = this.mWtfClusterCount;
                this.mWtfClusterCount = i + 1;
                if (i >= 5) {
                    return;
                }
            }
            final StringBuilder sb = new StringBuilder(1024);
            appendDropBoxProcessHeaders(process, processName, sb);
            if (process != null) {
                sb.append("Foreground: ");
                sb.append(process.isInterestingToUserLocked() ? "Yes" : "No");
                sb.append("\n");
            }
            if (activityShortComponentName != null) {
                sb.append("Activity: ");
                sb.append(activityShortComponentName);
                sb.append("\n");
            }
            if (parentShortComponentName != null) {
                if (parentProcess != null && parentProcess.pid != process.pid) {
                    sb.append("Parent-Process: ");
                    sb.append(parentProcess.processName);
                    sb.append("\n");
                }
                if (!parentShortComponentName.equals(activityShortComponentName)) {
                    sb.append("Parent-Activity: ");
                    sb.append(parentShortComponentName);
                    sb.append("\n");
                }
            }
            if (subject != null) {
                sb.append("Subject: ");
                sb.append(subject);
                sb.append("\n");
            }
            sb.append("Build: ");
            sb.append(Build.FINGERPRINT);
            sb.append("\n");
            if (Debug.isDebuggerConnected()) {
                sb.append("Debugger: Connected\n");
            }
            if (crashInfo != null && crashInfo.crashTag != null && !crashInfo.crashTag.isEmpty()) {
                sb.append("Crash-Tag: ");
                sb.append(crashInfo.crashTag);
                sb.append("\n");
            }
            sb.append("\n");
            Thread worker = new Thread("Error dump: " + dropboxTag) { // from class: com.android.server.am.ActivityManagerService.22
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    String str = report;
                    if (str != null) {
                        sb.append(str);
                    }
                    String setting = "logcat_for_" + dropboxTag;
                    int lines = Settings.Global.getInt(ActivityManagerService.this.mContext.getContentResolver(), setting, 0);
                    int maxDataFileSize = (ActivityManagerService.DROPBOX_MAX_SIZE - sb.length()) - (lines * 100);
                    File file = dataFile;
                    if (file != null && maxDataFileSize > 0) {
                        try {
                            sb.append(FileUtils.readTextFile(file, maxDataFileSize, "\n\n[[TRUNCATED]]"));
                        } catch (IOException e) {
                            Slog.e("ActivityManager", "Error reading " + dataFile, e);
                        }
                    }
                    ApplicationErrorReport.CrashInfo crashInfo2 = crashInfo;
                    if (crashInfo2 != null && crashInfo2.stackTrace != null) {
                        sb.append(crashInfo.stackTrace);
                    }
                    if (lines > 0) {
                        sb.append("\n");
                        InputStreamReader input = null;
                        try {
                            try {
                                try {
                                    Process logcat = new ProcessBuilder("/system/bin/timeout", "-k", "15s", "10s", "/system/bin/logcat", "-v", "threadtime", "-b", xpInputManagerService.InputPolicyKey.KEY_EVENTS, "-b", "system", "-b", "main", "-b", "crash", "-t", String.valueOf(lines)).redirectErrorStream(true).start();
                                    try {
                                        logcat.getOutputStream().close();
                                    } catch (IOException e2) {
                                    }
                                    try {
                                        logcat.getErrorStream().close();
                                    } catch (IOException e3) {
                                    }
                                    input = new InputStreamReader(logcat.getInputStream());
                                    char[] buf = new char[8192];
                                    while (true) {
                                        int num = input.read(buf);
                                        if (num <= 0) {
                                            break;
                                        }
                                        sb.append(buf, 0, num);
                                    }
                                    input.close();
                                } catch (IOException e4) {
                                    Slog.e("ActivityManager", "Error running logcat", e4);
                                    if (input != null) {
                                        input.close();
                                    }
                                }
                            } catch (IOException e5) {
                            }
                        } catch (Throwable th) {
                            if (0 != 0) {
                                try {
                                    input.close();
                                } catch (IOException e6) {
                                }
                            }
                            throw th;
                        }
                    }
                    dbox.addText(dropboxTag, sb.toString());
                }
            };
            if (process == null) {
                int oldMask = StrictMode.allowThreadDiskWritesMask();
                try {
                    worker.run();
                    return;
                } finally {
                    StrictMode.setThreadPolicyMask(oldMask);
                }
            }
            worker.start();
        }
    }

    private void addNativeErrorToDropBox(String eventType, ApplicationErrorReport.CrashInfo crashInfo) {
        DropBoxManager dbox;
        if (ServiceManager.getService("dropbox") == null || (dbox = (DropBoxManager) this.mContext.getSystemService(DropBoxManager.class)) == null) {
            return;
        }
        String dropboxTag = "native_" + eventType;
        long now = SystemClock.elapsedRealtime();
        if (now - this.mWtfClusterStart > JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY) {
            this.mWtfClusterStart = now;
            this.mWtfClusterCount = 1;
        } else {
            int i = this.mWtfClusterCount;
            this.mWtfClusterCount = i + 1;
            if (i >= 5) {
                return;
            }
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Build: ");
        sb.append(Build.FINGERPRINT);
        sb.append("\n");
        if (crashInfo != null && crashInfo.crashTag != null && !crashInfo.crashTag.isEmpty()) {
            sb.append("Crash-Tag: ");
            sb.append(crashInfo.crashTag);
            sb.append("\n");
        }
        sb.append("\n");
        if (crashInfo != null && crashInfo.stackTrace != null) {
            sb.append(crashInfo.stackTrace);
        }
        sb.append("\n");
        dbox.addText(dropboxTag, sb.toString());
    }

    public List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
        enforceNotIsolatedCaller("getProcessesInErrorState");
        List<ActivityManager.ProcessErrorStateInfo> errList = null;
        boolean allUsers = ActivityManager.checkUidPermission("android.permission.INTERACT_ACROSS_USERS_FULL", Binder.getCallingUid()) == 0;
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                for (int i = this.mProcessList.mLruProcesses.size() - 1; i >= 0; i--) {
                    ProcessRecord app = this.mProcessList.mLruProcesses.get(i);
                    if (allUsers || app.userId == userId) {
                        boolean crashing = app.isCrashing();
                        boolean notResponding = app.isNotResponding();
                        if (app.thread != null && (crashing || notResponding)) {
                            ActivityManager.ProcessErrorStateInfo report = null;
                            if (crashing) {
                                report = app.crashingReport;
                            } else if (notResponding) {
                                report = app.notRespondingReport;
                            }
                            if (report != null) {
                                if (errList == null) {
                                    errList = new ArrayList<>(1);
                                }
                                errList.add(report);
                            } else {
                                Slog.w("ActivityManager", "Missing app error report, app = " + app.processName + " crashing = " + crashing + " notResponding = " + notResponding);
                            }
                        }
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return errList;
    }

    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        List<ActivityManager.RunningAppProcessInfo> runningAppProcessesLocked;
        enforceNotIsolatedCaller("getRunningAppProcesses");
        int callingUid = Binder.getCallingUid();
        int clientTargetSdk = this.mPackageManagerInt.getUidTargetSdkVersion(callingUid);
        boolean allUsers = ActivityManager.checkUidPermission("android.permission.INTERACT_ACROSS_USERS_FULL", callingUid) == 0;
        int userId = UserHandle.getUserId(callingUid);
        boolean allUids = this.mAtmInternal.isGetTasksAllowed("getRunningAppProcesses", Binder.getCallingPid(), callingUid);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                runningAppProcessesLocked = this.mProcessList.getRunningAppProcessesLocked(allUsers, userId, allUids, callingUid, clientTargetSdk);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return runningAppProcessesLocked;
    }

    public List<ApplicationInfo> getRunningExternalApplications() {
        String[] strArr;
        enforceNotIsolatedCaller("getRunningExternalApplications");
        List<ActivityManager.RunningAppProcessInfo> runningApps = getRunningAppProcesses();
        List<ApplicationInfo> retList = new ArrayList<>();
        if (runningApps != null && runningApps.size() > 0) {
            Set<String> extList = new HashSet<>();
            Iterator<ActivityManager.RunningAppProcessInfo> it = runningApps.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                ActivityManager.RunningAppProcessInfo app = it.next();
                if (app.pkgList != null) {
                    for (String pkg : app.pkgList) {
                        extList.add(pkg);
                    }
                }
            }
            IPackageManager pm = AppGlobals.getPackageManager();
            for (String pkg2 : extList) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg2, 0, UserHandle.getCallingUserId());
                    if ((info.flags & 262144) != 0) {
                        retList.add(info);
                    }
                } catch (RemoteException e) {
                }
            }
        }
        return retList;
    }

    public void getMyMemoryState(ActivityManager.RunningAppProcessInfo outState) {
        ProcessRecord proc;
        if (outState == null) {
            throw new IllegalArgumentException("outState is null");
        }
        enforceNotIsolatedCaller("getMyMemoryState");
        int callingUid = Binder.getCallingUid();
        int clientTargetSdk = this.mPackageManagerInt.getUidTargetSdkVersion(callingUid);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                synchronized (this.mPidsSelfLocked) {
                    proc = this.mPidsSelfLocked.get(Binder.getCallingPid());
                }
                if (proc != null) {
                    this.mProcessList.fillInProcMemInfoLocked(proc, outState, clientTargetSdk);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public int getMemoryTrimLevel() {
        int i;
        enforceNotIsolatedCaller("getMyMemoryState");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                i = this.mLastMemoryLevel;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return i;
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new ActivityManagerShellCommand(this, false).exec(this, in, out, err, args, callback, resultReceiver);
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        PriorityDump.dump(this.mPriorityDumper, fd, pw, args);
    }

    private void dumpEverything(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage, boolean dumpClient, boolean dumpNormalPriority, int dumpAppId) {
        ActiveServices.ServiceDumper sdumper;
        String str;
        PrintWriter printWriter;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mConstants.dump(pw);
                this.mOomAdjuster.dumpAppCompactorSettings(pw);
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                dumpAllowedAssociationsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                this.mPendingIntentController.dumpPendingIntents(pw, dumpAll, dumpPackage);
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                dumpBroadcastsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                if (dumpAll || dumpPackage != null) {
                    dumpBroadcastStatsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                    pw.println();
                    if (dumpAll) {
                        pw.println("-------------------------------------------------------------------------------");
                    }
                }
                dumpProvidersLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                dumpPermissionsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                pw.println();
                sdumper = this.mServices.newServiceDumperLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                if (!dumpClient) {
                    if (dumpAll) {
                        pw.println("-------------------------------------------------------------------------------");
                    }
                    sdumper.dumpLocked();
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
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        if (dumpClient) {
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            sdumper.dumpWithClient();
        }
        if (dumpPackage == null) {
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpBinderProxies(pw, 6000);
        }
        synchronized (this) {
            try {
                try {
                    boostPriorityForLockedSection();
                    pw.println();
                    if (dumpAll) {
                        pw.println("-------------------------------------------------------------------------------");
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
                try {
                    this.mAtmInternal.dump(ActivityTaskManagerService.DUMP_RECENTS_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
                    pw.println();
                    if (dumpAll) {
                        pw.println("-------------------------------------------------------------------------------");
                    }
                    this.mAtmInternal.dump(ActivityTaskManagerService.DUMP_LASTANR_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
                    pw.println();
                    if (dumpAll) {
                        pw.println("-------------------------------------------------------------------------------");
                    }
                    this.mAtmInternal.dump(ActivityTaskManagerService.DUMP_STARTER_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
                    pw.println();
                    if (dumpAll) {
                        pw.println("-------------------------------------------------------------------------------");
                    }
                    this.mAtmInternal.dump(ActivityTaskManagerService.DUMP_CONTAINERS_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
                    if (!dumpNormalPriority) {
                        pw.println();
                        if (dumpAll) {
                            pw.println("-------------------------------------------------------------------------------");
                        }
                        this.mAtmInternal.dump(ActivityTaskManagerService.DUMP_ACTIVITIES_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
                    }
                    if (this.mAssociations.size() <= 0) {
                        str = dumpPackage;
                        printWriter = pw;
                    } else {
                        pw.println();
                        if (dumpAll) {
                            pw.println("-------------------------------------------------------------------------------");
                        }
                        str = dumpPackage;
                        printWriter = pw;
                        dumpAssociationsLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
                    }
                    if (str == null) {
                        pw.println();
                        if (dumpAll) {
                            printWriter.println("-------------------------------------------------------------------------------");
                        }
                        this.mOomAdjProfiler.dump(printWriter);
                        pw.println();
                        if (dumpAll) {
                            printWriter.println("-------------------------------------------------------------------------------");
                        }
                        dumpLmkLocked(printWriter);
                    }
                    pw.println();
                    if (dumpAll) {
                        printWriter.println("-------------------------------------------------------------------------------");
                    }
                    dumpLruLocked(printWriter, str);
                    pw.println();
                    if (dumpAll) {
                        printWriter.println("-------------------------------------------------------------------------------");
                    }
                    dumpProcessesLocked(fd, pw, args, opti, dumpAll, dumpPackage, dumpAppId);
                    resetPriorityAfterLockedSection();
                } catch (Throwable th4) {
                    th = th4;
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            } catch (Throwable th5) {
                th = th5;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:396:0x06e4  */
    /* JADX WARN: Removed duplicated region for block: B:398:0x06e8  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void doDump(java.io.FileDescriptor r29, java.io.PrintWriter r30, java.lang.String[] r31, boolean r32) {
        /*
            Method dump skipped, instructions count: 1860
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.doDump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[], boolean):void");
    }

    void dumpAssociationsLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        ArrayMap<String, Association> sourceProcesses;
        int dumpUid;
        int N1;
        ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> targetComponents;
        ActivityManagerService activityManagerService = this;
        String str = dumpPackage;
        pw.println("ACTIVITY MANAGER ASSOCIATIONS (dumpsys activity associations)");
        int dumpUid2 = 0;
        boolean z = false;
        if (str != null) {
            IPackageManager pm = AppGlobals.getPackageManager();
            try {
                dumpUid2 = pm.getPackageUid(str, (int) DumpState.DUMP_CHANGES, 0);
            } catch (RemoteException e) {
            }
        }
        boolean printedAnything = false;
        long now = SystemClock.uptimeMillis();
        int i1 = 0;
        int N12 = activityManagerService.mAssociations.size();
        while (i1 < N12) {
            ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> targetComponents2 = activityManagerService.mAssociations.valueAt(i1);
            int i2 = 0;
            int N2 = targetComponents2.size();
            while (i2 < N2) {
                SparseArray<ArrayMap<String, Association>> sourceUids = targetComponents2.valueAt(i2);
                int i3 = 0;
                int N3 = sourceUids.size();
                while (i3 < N3) {
                    ArrayMap<String, Association> sourceProcesses2 = sourceUids.valueAt(i3);
                    boolean printedAnything2 = printedAnything;
                    int N4 = sourceProcesses2.size();
                    int i4 = 0;
                    while (i4 < N4) {
                        int N42 = N4;
                        Association ass = sourceProcesses2.valueAt(i4);
                        if (str == null) {
                            sourceProcesses = sourceProcesses2;
                        } else {
                            sourceProcesses = sourceProcesses2;
                            if (!ass.mTargetComponent.getPackageName().equals(str) && UserHandle.getAppId(ass.mSourceUid) != dumpUid2) {
                                dumpUid = dumpUid2;
                                N1 = N12;
                                targetComponents = targetComponents2;
                                i4++;
                                str = dumpPackage;
                                sourceProcesses2 = sourceProcesses;
                                N4 = N42;
                                dumpUid2 = dumpUid;
                                N12 = N1;
                                targetComponents2 = targetComponents;
                            }
                        }
                        printedAnything2 = true;
                        pw.print("  ");
                        pw.print(ass.mTargetProcess);
                        pw.print(SliceClientPermissions.SliceAuthority.DELIMITER);
                        dumpUid = dumpUid2;
                        UserHandle.formatUid(pw, ass.mTargetUid);
                        pw.print(" <- ");
                        pw.print(ass.mSourceProcess);
                        pw.print(SliceClientPermissions.SliceAuthority.DELIMITER);
                        UserHandle.formatUid(pw, ass.mSourceUid);
                        pw.println();
                        pw.print("    via ");
                        pw.print(ass.mTargetComponent.flattenToShortString());
                        pw.println();
                        pw.print("    ");
                        long dur = ass.mTime;
                        N1 = N12;
                        int N13 = ass.mNesting;
                        if (N13 <= 0) {
                            targetComponents = targetComponents2;
                        } else {
                            targetComponents = targetComponents2;
                            dur += now - ass.mStartTime;
                        }
                        TimeUtils.formatDuration(dur, pw);
                        pw.print(" (");
                        pw.print(ass.mCount);
                        pw.print(" times)");
                        pw.print("  ");
                        int i = 0;
                        while (i < ass.mStateTimes.length) {
                            long amt = ass.mStateTimes[i];
                            long dur2 = dur;
                            if (ass.mLastState - 0 == i) {
                                amt += now - ass.mLastStateUptime;
                            }
                            if (amt != 0) {
                                pw.print(" ");
                                pw.print(ProcessList.makeProcStateString(i + 0));
                                pw.print("=");
                                TimeUtils.formatDuration(amt, pw);
                                if (ass.mLastState - 0 == i) {
                                    pw.print("*");
                                }
                            }
                            i++;
                            dur = dur2;
                        }
                        pw.println();
                        if (ass.mNesting > 0) {
                            pw.print("    Currently active: ");
                            TimeUtils.formatDuration(now - ass.mStartTime, pw);
                            pw.println();
                        }
                        i4++;
                        str = dumpPackage;
                        sourceProcesses2 = sourceProcesses;
                        N4 = N42;
                        dumpUid2 = dumpUid;
                        N12 = N1;
                        targetComponents2 = targetComponents;
                    }
                    i3++;
                    str = dumpPackage;
                    z = false;
                    printedAnything = printedAnything2;
                    dumpUid2 = dumpUid2;
                }
                i2++;
                str = dumpPackage;
                dumpUid2 = dumpUid2;
            }
            i1++;
            activityManagerService = this;
            str = dumpPackage;
            dumpUid2 = dumpUid2;
        }
        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    private int getAppId(String dumpPackage) {
        if (dumpPackage != null) {
            try {
                ApplicationInfo info = this.mContext.getPackageManager().getApplicationInfo(dumpPackage, 0);
                return UserHandle.getAppId(info.uid);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                return -1;
            }
        }
        return -1;
    }

    boolean dumpUids(PrintWriter pw, String dumpPackage, int dumpAppId, ActiveUids uids, String header, boolean needSep) {
        boolean printed = false;
        for (int i = 0; i < uids.size(); i++) {
            UidRecord uidRec = uids.valueAt(i);
            if (dumpPackage == null || UserHandle.getAppId(uidRec.uid) == dumpAppId) {
                if (!printed) {
                    printed = true;
                    if (needSep) {
                        pw.println();
                    }
                    pw.print("  ");
                    pw.println(header);
                    needSep = true;
                }
                pw.print("    UID ");
                UserHandle.formatUid(pw, uidRec.uid);
                pw.print(": ");
                pw.println(uidRec);
            }
        }
        return printed;
    }

    void dumpBinderProxyInterfaceCounts(PrintWriter pw, String header) {
        BinderProxy.InterfaceCount[] proxyCounts = BinderProxy.getSortedInterfaceCounts(50);
        pw.println(header);
        for (int i = 0; i < proxyCounts.length; i++) {
            pw.println("    #" + (i + 1) + ": " + proxyCounts[i]);
        }
    }

    boolean dumpBinderProxiesCounts(PrintWriter pw, String header) {
        SparseIntArray counts = BinderInternal.nGetBinderProxyPerUidCounts();
        if (counts != null) {
            pw.println(header);
            for (int i = 0; i < counts.size(); i++) {
                int uid = counts.keyAt(i);
                int binderCount = counts.valueAt(i);
                pw.print("    UID ");
                pw.print(uid);
                pw.print(", binder count = ");
                pw.print(binderCount);
                pw.print(", package(s)= ");
                String[] pkgNames = this.mContext.getPackageManager().getPackagesForUid(uid);
                if (pkgNames != null) {
                    for (String str : pkgNames) {
                        pw.print(str);
                        pw.print("; ");
                    }
                } else {
                    pw.print("NO PACKAGE NAME FOUND");
                }
                pw.println();
            }
            return true;
        }
        return false;
    }

    void dumpBinderProxies(PrintWriter pw, int minCountToDumpInterfaces) {
        pw.println("ACTIVITY MANAGER BINDER PROXY STATE (dumpsys activity binder-proxies)");
        int proxyCount = BinderProxy.getProxyCount();
        if (proxyCount >= minCountToDumpInterfaces) {
            dumpBinderProxyInterfaceCounts(pw, "Top proxy interface names held by SYSTEM");
        } else {
            pw.print("Not dumping proxy interface counts because size (" + Integer.toString(proxyCount) + ") looks reasonable");
            pw.println();
        }
        dumpBinderProxiesCounts(pw, "  Counts of Binder Proxies held by SYSTEM");
    }

    void dumpLruEntryLocked(PrintWriter pw, int index, ProcessRecord proc) {
        pw.print("    #");
        pw.print(index);
        pw.print(": ");
        pw.print(ProcessList.makeOomAdjString(proc.setAdj, false));
        pw.print(" ");
        pw.print(ProcessList.makeProcStateString(proc.getCurProcState()));
        pw.print(" ");
        pw.print(proc.toShortString());
        pw.print(" ");
        if (proc.hasActivitiesOrRecentTasks() || proc.hasClientActivities() || proc.treatLikeActivity) {
            pw.print(" activity=");
            boolean printed = false;
            if (proc.hasActivities()) {
                pw.print(ActivityTaskManagerService.DUMP_ACTIVITIES_CMD);
                printed = true;
            }
            if (proc.hasRecentTasks()) {
                if (printed) {
                    pw.print("|");
                }
                pw.print(ActivityTaskManagerService.DUMP_RECENTS_CMD);
                printed = true;
            }
            if (proc.hasClientActivities()) {
                if (printed) {
                    pw.print("|");
                }
                pw.print("client");
                printed = true;
            }
            if (proc.treatLikeActivity) {
                if (printed) {
                    pw.print("|");
                }
                pw.print("treated");
            }
        }
        pw.println();
    }

    void dumpLruLocked(PrintWriter pw, String dumpPackage) {
        pw.println("ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)");
        int N = this.mProcessList.mLruProcesses.size();
        boolean first = true;
        int i = N - 1;
        while (i >= this.mProcessList.mLruProcessActivityStart) {
            ProcessRecord r = this.mProcessList.mLruProcesses.get(i);
            if (dumpPackage == null || r.pkgList.containsKey(dumpPackage)) {
                if (first) {
                    pw.println("  Activities:");
                    first = false;
                }
                dumpLruEntryLocked(pw, i, r);
            }
            i--;
        }
        boolean first2 = true;
        while (i >= this.mProcessList.mLruProcessServiceStart) {
            ProcessRecord r2 = this.mProcessList.mLruProcesses.get(i);
            if (dumpPackage == null || r2.pkgList.containsKey(dumpPackage)) {
                if (first2) {
                    pw.println("  Services:");
                    first2 = false;
                }
                dumpLruEntryLocked(pw, i, r2);
            }
            i--;
        }
        boolean first3 = true;
        while (i >= 0) {
            ProcessRecord r3 = this.mProcessList.mLruProcesses.get(i);
            if (dumpPackage == null || r3.pkgList.containsKey(dumpPackage)) {
                if (first3) {
                    pw.println("  Other:");
                    first3 = false;
                }
                dumpLruEntryLocked(pw, i, r3);
            }
            i--;
        }
    }

    /* JADX WARN: Can't wrap try/catch for region: R(3:(4:270|271|(3:(2:(1:280)|281)|282|283)(2:275|276)|277)|267|268) */
    /* JADX WARN: Code restructure failed: missing block: B:260:0x085d, code lost:
        r0 = th;
     */
    @com.android.internal.annotations.GuardedBy({"this"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    void dumpProcessesLocked(java.io.FileDescriptor r20, java.io.PrintWriter r21, java.lang.String[] r22, int r23, boolean r24, java.lang.String r25, int r26) {
        /*
            Method dump skipped, instructions count: 2151
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.dumpProcessesLocked(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[], int, boolean, java.lang.String, int):void");
    }

    @GuardedBy({"this"})
    void writeProcessesToProtoLocked(ProtoOutputStream proto, String dumpPackage) {
        int numPers;
        int[] iArr;
        int[] iArr2;
        int NP = this.mProcessList.mProcessNames.getMap().size();
        int numPers2 = 0;
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> procs = (SparseArray) this.mProcessList.mProcessNames.getMap().valueAt(ip);
            int NA = procs.size();
            for (int ia = 0; ia < NA; ia++) {
                ProcessRecord r = procs.valueAt(ia);
                if (dumpPackage == null || r.pkgList.containsKey(dumpPackage)) {
                    r.writeToProto(proto, 2246267895809L, this.mProcessList.mLruProcesses.indexOf(r));
                    if (r.isPersistent()) {
                        numPers2++;
                    }
                }
            }
        }
        for (int i = 0; i < this.mProcessList.mIsolatedProcesses.size(); i++) {
            ProcessRecord r2 = this.mProcessList.mIsolatedProcesses.valueAt(i);
            if (dumpPackage == null || r2.pkgList.containsKey(dumpPackage)) {
                r2.writeToProto(proto, 2246267895810L, this.mProcessList.mLruProcesses.indexOf(r2));
            }
        }
        for (int i2 = 0; i2 < this.mActiveInstrumentation.size(); i2++) {
            ActiveInstrumentation ai = this.mActiveInstrumentation.get(i2);
            if (dumpPackage == null || ai.mClass.getPackageName().equals(dumpPackage) || ai.mTargetInfo.packageName.equals(dumpPackage)) {
                ai.writeToProto(proto, 2246267895811L);
            }
        }
        int whichAppId = getAppId(dumpPackage);
        for (int i3 = 0; i3 < this.mProcessList.mActiveUids.size(); i3++) {
            UidRecord uidRec = this.mProcessList.mActiveUids.valueAt(i3);
            if (dumpPackage == null || UserHandle.getAppId(uidRec.uid) == whichAppId) {
                uidRec.writeToProto(proto, 2246267895812L);
            }
        }
        for (int i4 = 0; i4 < this.mValidateUids.size(); i4++) {
            UidRecord uidRec2 = this.mValidateUids.valueAt(i4);
            if (dumpPackage == null || UserHandle.getAppId(uidRec2.uid) == whichAppId) {
                uidRec2.writeToProto(proto, 2246267895813L);
            }
        }
        if (this.mProcessList.getLruSizeLocked() > 0) {
            long lruToken = proto.start(1146756268038L);
            int total = this.mProcessList.getLruSizeLocked();
            proto.write(1120986464257L, total);
            proto.write(1120986464258L, total - this.mProcessList.mLruProcessActivityStart);
            proto.write(1120986464259L, total - this.mProcessList.mLruProcessServiceStart);
            numPers = numPers2;
            writeProcessOomListToProto(proto, 2246267895812L, this, this.mProcessList.mLruProcesses, false, dumpPackage);
            proto.end(lruToken);
        } else {
            numPers = numPers2;
        }
        if (dumpPackage != null) {
            synchronized (this.mPidsSelfLocked) {
                for (int i5 = 0; i5 < this.mPidsSelfLocked.size(); i5++) {
                    ProcessRecord r3 = this.mPidsSelfLocked.valueAt(i5);
                    if (r3.pkgList.containsKey(dumpPackage)) {
                        r3.writeToProto(proto, 2246267895815L);
                    }
                }
            }
        }
        if (this.mImportantProcesses.size() > 0) {
            synchronized (this.mPidsSelfLocked) {
                for (int i6 = 0; i6 < this.mImportantProcesses.size(); i6++) {
                    ImportanceToken it = this.mImportantProcesses.valueAt(i6);
                    ProcessRecord r4 = this.mPidsSelfLocked.get(it.pid);
                    if (dumpPackage == null || (r4 != null && r4.pkgList.containsKey(dumpPackage))) {
                        it.writeToProto(proto, 2246267895816L);
                    }
                }
            }
        }
        for (int i7 = 0; i7 < this.mPersistentStartingProcesses.size(); i7++) {
            ProcessRecord r5 = this.mPersistentStartingProcesses.get(i7);
            if (dumpPackage == null || dumpPackage.equals(r5.info.packageName)) {
                r5.writeToProto(proto, 2246267895817L);
            }
        }
        for (int i8 = 0; i8 < this.mProcessList.mRemovedProcesses.size(); i8++) {
            ProcessRecord r6 = this.mProcessList.mRemovedProcesses.get(i8);
            if (dumpPackage == null || dumpPackage.equals(r6.info.packageName)) {
                r6.writeToProto(proto, 2246267895818L);
            }
        }
        for (int i9 = 0; i9 < this.mProcessesOnHold.size(); i9++) {
            ProcessRecord r7 = this.mProcessesOnHold.get(i9);
            if (dumpPackage == null || dumpPackage.equals(r7.info.packageName)) {
                r7.writeToProto(proto, 2246267895819L);
            }
        }
        writeProcessesToGcToProto(proto, 2246267895820L, dumpPackage);
        this.mAppErrors.writeToProto(proto, 1146756268045L, dumpPackage);
        this.mAtmInternal.writeProcessesToProto(proto, dumpPackage, this.mWakefulness, this.mTestPssMode);
        if (dumpPackage == null) {
            this.mUserController.writeToProto(proto, 1146756268046L);
        }
        int NI = this.mUidObservers.getRegisteredCallbackCount();
        for (int i10 = 0; i10 < NI; i10++) {
            UidObserverRegistration reg = (UidObserverRegistration) this.mUidObservers.getRegisteredCallbackCookie(i10);
            if (dumpPackage == null || dumpPackage.equals(reg.pkg)) {
                reg.writeToProto(proto, 2246267895831L);
            }
        }
        for (int v : this.mDeviceIdleWhitelist) {
            proto.write(2220498092056L, v);
        }
        for (int v2 : this.mDeviceIdleTempWhitelist) {
            proto.write(2220498092057L, v2);
        }
        if (this.mPendingTempWhitelist.size() > 0) {
            for (int i11 = 0; i11 < this.mPendingTempWhitelist.size(); i11++) {
                this.mPendingTempWhitelist.valueAt(i11).writeToProto(proto, 2246267895834L);
            }
        }
        long j = 1138166333441L;
        if ((this.mDebugApp != null || this.mOrigDebugApp != null || this.mDebugTransient || this.mOrigWaitForDebugger) && (dumpPackage == null || dumpPackage.equals(this.mDebugApp) || dumpPackage.equals(this.mOrigDebugApp))) {
            long debugAppToken = proto.start(1146756268062L);
            proto.write(1138166333441L, this.mDebugApp);
            proto.write(1138166333442L, this.mOrigDebugApp);
            proto.write(1133871366147L, this.mDebugTransient);
            proto.write(1133871366148L, this.mOrigWaitForDebugger);
            proto.end(debugAppToken);
        }
        if (this.mMemWatchProcesses.getMap().size() > 0) {
            long token = proto.start(1146756268064L);
            ArrayMap<String, SparseArray<Pair<Long, String>>> procs2 = this.mMemWatchProcesses.getMap();
            int i12 = 0;
            while (i12 < procs2.size()) {
                String proc = procs2.keyAt(i12);
                SparseArray<Pair<Long, String>> uids = procs2.valueAt(i12);
                long token2 = token;
                long ptoken = proto.start(2246267895809L);
                proto.write(j, proc);
                int j2 = 0;
                while (j2 < uids.size()) {
                    long utoken = proto.start(2246267895810L);
                    Pair<Long, String> val = uids.valueAt(j2);
                    proto.write(1120986464257L, uids.keyAt(j2));
                    proto.write(1138166333442L, DebugUtils.sizeValueToString(((Long) val.first).longValue(), new StringBuilder()));
                    proto.write(1138166333443L, (String) val.second);
                    proto.end(utoken);
                    j2++;
                    NI = NI;
                    proc = proc;
                }
                proto.end(ptoken);
                i12++;
                token = token2;
                j = 1138166333441L;
            }
            long dtoken = proto.start(1146756268034L);
            proto.write(1138166333441L, this.mMemWatchDumpProcName);
            proto.write(1138166333442L, this.mMemWatchDumpFile);
            proto.write(1120986464259L, this.mMemWatchDumpPid);
            proto.write(1120986464260L, this.mMemWatchDumpUid);
            proto.write(1133871366149L, this.mMemWatchIsUserInitiated);
            proto.end(dtoken);
            proto.end(token);
        }
        String str = this.mTrackAllocationApp;
        if (str != null && (dumpPackage == null || dumpPackage.equals(str))) {
            proto.write(1138166333473L, this.mTrackAllocationApp);
        }
        if ((this.mProfileData.getProfileApp() != null || this.mProfileData.getProfileProc() != null || (this.mProfileData.getProfilerInfo() != null && (this.mProfileData.getProfilerInfo().profileFile != null || this.mProfileData.getProfilerInfo().profileFd != null))) && (dumpPackage == null || dumpPackage.equals(this.mProfileData.getProfileApp()))) {
            long token3 = proto.start(1146756268066L);
            proto.write(1138166333441L, this.mProfileData.getProfileApp());
            this.mProfileData.getProfileProc().writeToProto(proto, 1146756268034L);
            if (this.mProfileData.getProfilerInfo() != null) {
                this.mProfileData.getProfilerInfo().writeToProto(proto, 1146756268035L);
                proto.write(1120986464260L, this.mProfileType);
            }
            proto.end(token3);
        }
        if (dumpPackage == null || dumpPackage.equals(this.mNativeDebuggingApp)) {
            proto.write(1138166333475L, this.mNativeDebuggingApp);
        }
        if (dumpPackage == null) {
            proto.write(1133871366180L, this.mAlwaysFinishActivities);
            proto.write(1120986464294L, numPers);
            proto.write(1133871366183L, this.mProcessesReady);
            proto.write(1133871366184L, this.mSystemReady);
            proto.write(1133871366185L, this.mBooted);
            proto.write(1120986464298L, this.mFactoryTest);
            proto.write(1133871366187L, this.mBooting);
            proto.write(1133871366188L, this.mCallFinishBooting);
            proto.write(1133871366189L, this.mBootAnimationComplete);
            proto.write(1112396529710L, this.mLastPowerCheckUptime);
            this.mOomAdjuster.dumpProcessListVariablesLocked(proto);
            proto.write(1133871366199L, this.mAllowLowerMemLevel);
            proto.write(1120986464312L, this.mLastMemoryLevel);
            proto.write(1120986464313L, this.mLastNumProcesses);
            long now = SystemClock.uptimeMillis();
            ProtoUtils.toDuration(proto, 1146756268090L, this.mLastIdleTime, now);
            proto.write(1112396529723L, getLowRamTimeSinceIdle(now));
        }
    }

    void writeProcessesToGcToProto(ProtoOutputStream proto, long fieldId, String dumpPackage) {
        if (this.mProcessesToGc.size() > 0) {
            long now = SystemClock.uptimeMillis();
            for (int i = 0; i < this.mProcessesToGc.size(); i++) {
                ProcessRecord r = this.mProcessesToGc.get(i);
                if (dumpPackage == null || dumpPackage.equals(r.info.packageName)) {
                    long token = proto.start(fieldId);
                    r.writeToProto(proto, 1146756268033L);
                    proto.write(1133871366146L, r.reportLowMemory);
                    proto.write(1112396529667L, now);
                    proto.write(1112396529668L, r.lastRequestedGc);
                    proto.write(1112396529669L, r.lastLowMemory);
                    proto.end(token);
                }
            }
        }
    }

    boolean dumpProcessesToGc(PrintWriter pw, boolean needSep, String dumpPackage) {
        if (this.mProcessesToGc.size() > 0) {
            boolean printed = false;
            long now = SystemClock.uptimeMillis();
            for (int i = 0; i < this.mProcessesToGc.size(); i++) {
                ProcessRecord proc = this.mProcessesToGc.get(i);
                if (dumpPackage == null || dumpPackage.equals(proc.info.packageName)) {
                    if (!printed) {
                        if (needSep) {
                            pw.println();
                        }
                        needSep = true;
                        pw.println("  Processes that are waiting to GC:");
                        printed = true;
                    }
                    pw.print("    Process ");
                    pw.println(proc);
                    pw.print("      lowMem=");
                    pw.print(proc.reportLowMemory);
                    pw.print(", last gced=");
                    pw.print(now - proc.lastRequestedGc);
                    pw.print(" ms ago, last lowMem=");
                    pw.print(now - proc.lastLowMemory);
                    pw.println(" ms ago");
                }
            }
        }
        return needSep;
    }

    void printOomLevel(PrintWriter pw, String name, int adj) {
        pw.print("    ");
        if (adj >= 0) {
            pw.print(' ');
            if (adj < 10) {
                pw.print(' ');
            }
        } else if (adj > -10) {
            pw.print(' ');
        }
        pw.print(adj);
        pw.print(": ");
        pw.print(name);
        pw.print(" (");
        pw.print(stringifySize(this.mProcessList.getMemLevel(adj), 1024));
        pw.println(")");
    }

    boolean dumpOomLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll) {
        boolean needSep = false;
        if (this.mProcessList.getLruSizeLocked() > 0) {
            if (0 != 0) {
                pw.println();
            }
            pw.println("  OOM levels:");
            printOomLevel(pw, "SYSTEM_ADJ", -900);
            printOomLevel(pw, "PERSISTENT_PROC_ADJ", -800);
            printOomLevel(pw, "PERSISTENT_SERVICE_ADJ", -700);
            printOomLevel(pw, "FOREGROUND_APP_ADJ", 0);
            printOomLevel(pw, "VISIBLE_APP_ADJ", 100);
            printOomLevel(pw, "PERCEPTIBLE_APP_ADJ", 200);
            printOomLevel(pw, "PERCEPTIBLE_LOW_APP_ADJ", 250);
            printOomLevel(pw, "BACKUP_APP_ADJ", DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR);
            printOomLevel(pw, "HEAVY_WEIGHT_APP_ADJ", 400);
            printOomLevel(pw, "SERVICE_ADJ", SystemService.PHASE_SYSTEM_SERVICES_READY);
            printOomLevel(pw, "HOME_APP_ADJ", SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
            printOomLevel(pw, "PREVIOUS_APP_ADJ", 700);
            printOomLevel(pw, "SERVICE_B_ADJ", 800);
            printOomLevel(pw, "CACHED_APP_MIN_ADJ", 900);
            printOomLevel(pw, "CACHED_APP_MAX_ADJ", 999);
            if (1 != 0) {
                pw.println();
            }
            pw.print("  Process OOM control (");
            pw.print(this.mProcessList.getLruSizeLocked());
            pw.print(" total, non-act at ");
            pw.print(this.mProcessList.getLruSizeLocked() - this.mProcessList.mLruProcessActivityStart);
            pw.print(", non-svc at ");
            pw.print(this.mProcessList.getLruSizeLocked() - this.mProcessList.mLruProcessServiceStart);
            pw.println("):");
            dumpProcessOomList(pw, this, this.mProcessList.mLruProcesses, "    ", "Proc", "PERS", true, null);
            needSep = true;
        }
        dumpProcessesToGc(pw, needSep, null);
        pw.println();
        this.mAtmInternal.dumpForOom(pw);
        return true;
    }

    private boolean reportLmkKillAtOrBelow(PrintWriter pw, int oom_adj) {
        Integer cnt = ProcessList.getLmkdKillCount(0, oom_adj);
        if (cnt == null) {
            return false;
        }
        pw.println("    kills at or below oom_adj " + oom_adj + ": " + cnt);
        return true;
    }

    boolean dumpLmkLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER LMK KILLS (dumpsys activity lmk)");
        Integer cnt = ProcessList.getLmkdKillCount(NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE, NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE);
        if (cnt == null) {
            return false;
        }
        pw.println("  Total number of kills: " + cnt);
        return reportLmkKillAtOrBelow(pw, 999) && reportLmkKillAtOrBelow(pw, 900) && reportLmkKillAtOrBelow(pw, 800) && reportLmkKillAtOrBelow(pw, 700) && reportLmkKillAtOrBelow(pw, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) && reportLmkKillAtOrBelow(pw, SystemService.PHASE_SYSTEM_SERVICES_READY) && reportLmkKillAtOrBelow(pw, 400) && reportLmkKillAtOrBelow(pw, DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR) && reportLmkKillAtOrBelow(pw, 250) && reportLmkKillAtOrBelow(pw, 200) && reportLmkKillAtOrBelow(pw, 100) && reportLmkKillAtOrBelow(pw, 0);
    }

    protected boolean dumpProvider(FileDescriptor fd, PrintWriter pw, String name, String[] args, int opti, boolean dumpAll) {
        return this.mProviderMap.dumpProvider(fd, pw, name, args, opti, dumpAll);
    }

    protected boolean dumpProviderProto(FileDescriptor fd, PrintWriter pw, String name, String[] args) {
        return this.mProviderMap.dumpProviderProto(fd, pw, name, args);
    }

    /* loaded from: classes.dex */
    public static class ItemMatcher {
        boolean all = true;
        ArrayList<ComponentName> components;
        ArrayList<Integer> objects;
        ArrayList<String> strings;

        public void build(String name) {
            ComponentName componentName = ComponentName.unflattenFromString(name);
            if (componentName != null) {
                if (this.components == null) {
                    this.components = new ArrayList<>();
                }
                this.components.add(componentName);
                this.all = false;
                return;
            }
            try {
                int objectId = Integer.parseInt(name, 16);
                if (this.objects == null) {
                    this.objects = new ArrayList<>();
                }
                this.objects.add(Integer.valueOf(objectId));
                this.all = false;
            } catch (RuntimeException e) {
                if (this.strings == null) {
                    this.strings = new ArrayList<>();
                }
                this.strings.add(name);
                this.all = false;
            }
        }

        public int build(String[] args, int opti) {
            while (opti < args.length) {
                String name = args[opti];
                if ("--".equals(name)) {
                    return opti + 1;
                }
                build(name);
                opti++;
            }
            return opti;
        }

        public boolean match(Object object, ComponentName comp) {
            if (this.all) {
                return true;
            }
            if (this.components != null) {
                for (int i = 0; i < this.components.size(); i++) {
                    if (this.components.get(i).equals(comp)) {
                        return true;
                    }
                }
            }
            if (this.objects != null) {
                for (int i2 = 0; i2 < this.objects.size(); i2++) {
                    if (System.identityHashCode(object) == this.objects.get(i2).intValue()) {
                        return true;
                    }
                }
            }
            if (this.strings != null) {
                String flat = comp.flattenToString();
                for (int i3 = 0; i3 < this.strings.size(); i3++) {
                    if (flat.contains(this.strings.get(i3))) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }

    void writeBroadcastsToProtoLocked(ProtoOutputStream proto) {
        BroadcastQueue[] broadcastQueueArr;
        if (this.mRegisteredReceivers.size() > 0) {
            for (ReceiverList r : this.mRegisteredReceivers.values()) {
                r.writeToProto(proto, 2246267895809L);
            }
        }
        this.mReceiverResolver.writeToProto(proto, 1146756268034L);
        for (BroadcastQueue q : this.mBroadcastQueues) {
            q.writeToProto(proto, 2246267895811L);
        }
        int user = 0;
        while (true) {
            long token = 1138166333441L;
            if (user < this.mStickyBroadcasts.size()) {
                long token2 = proto.start(2246267895812L);
                proto.write(1120986464257L, this.mStickyBroadcasts.keyAt(user));
                for (Map.Entry<String, ArrayList<Intent>> ent : this.mStickyBroadcasts.valueAt(user).entrySet()) {
                    long actionToken = proto.start(2246267895810L);
                    proto.write(token, ent.getKey());
                    Iterator<Intent> it = ent.getValue().iterator();
                    while (it.hasNext()) {
                        Intent intent = it.next();
                        intent.writeToProto(proto, 2246267895810L, false, true, true, false);
                        actionToken = actionToken;
                        token2 = token2;
                    }
                    proto.end(actionToken);
                    token = 1138166333441L;
                }
                proto.end(token2);
                user++;
            } else {
                long handlerToken = proto.start(1146756268037L);
                proto.write(1138166333441L, this.mHandler.toString());
                this.mHandler.getLooper().writeToProto(proto, 1146756268034L);
                proto.end(handlerToken);
                return;
            }
        }
    }

    void dumpAllowedAssociationsLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage) {
        pw.println("ACTIVITY MANAGER ALLOWED ASSOCIATION STATE (dumpsys activity allowed-associations)");
        boolean printed = false;
        if (this.mAllowedAssociations != null) {
            for (int i = 0; i < this.mAllowedAssociations.size(); i++) {
                String pkg = this.mAllowedAssociations.keyAt(i);
                ArraySet<String> asc = this.mAllowedAssociations.valueAt(i).getAllowedPackageAssociations();
                boolean printedHeader = false;
                for (int j = 0; j < asc.size(); j++) {
                    if (dumpPackage == null || pkg.equals(dumpPackage) || asc.valueAt(j).equals(dumpPackage)) {
                        if (!printed) {
                            pw.println("  Allowed associations (by restricted package):");
                            printed = true;
                        }
                        if (!printedHeader) {
                            pw.print("  * ");
                            pw.print(pkg);
                            pw.println(":");
                            printedHeader = true;
                        }
                        pw.print("      Allow: ");
                        pw.println(asc.valueAt(j));
                    }
                }
                if (this.mAllowedAssociations.valueAt(i).isDebuggable()) {
                    pw.println("      (debuggable)");
                }
            }
        }
        if (!printed) {
            pw.println("  (No association restrictions)");
        }
    }

    void dumpBroadcastsLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage) {
        boolean dumpAll2;
        boolean onlyHistory;
        String dumpPackage2;
        BroadcastQueue[] broadcastQueueArr;
        boolean needSep;
        String str;
        String str2;
        boolean needSep2;
        boolean printedAnything;
        boolean dumpAll3;
        boolean needSep3 = false;
        boolean printedAnything2 = false;
        if (!"history".equals(dumpPackage)) {
            dumpAll2 = dumpAll;
            onlyHistory = false;
            dumpPackage2 = dumpPackage;
        } else {
            if (opti < args.length && "-s".equals(args[opti])) {
                dumpAll3 = false;
            } else {
                dumpAll3 = dumpAll;
            }
            onlyHistory = true;
            dumpAll2 = dumpAll3;
            dumpPackage2 = null;
        }
        pw.println("ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)");
        if (!onlyHistory && dumpAll2) {
            if (this.mRegisteredReceivers.size() <= 0) {
                needSep2 = false;
                printedAnything = false;
            } else {
                boolean printed = false;
                for (ReceiverList r : this.mRegisteredReceivers.values()) {
                    if (dumpPackage2 == null || (r.app != null && dumpPackage2.equals(r.app.info.packageName))) {
                        if (!printed) {
                            pw.println("  Registered Receivers:");
                            needSep3 = true;
                            printed = true;
                            printedAnything2 = true;
                        }
                        pw.print("  * ");
                        pw.println(r);
                        r.dump(pw, "    ");
                    }
                }
                needSep2 = needSep3;
                printedAnything = printedAnything2;
            }
            if (!this.mReceiverResolver.dump(pw, needSep2 ? "\n  Receiver Resolver Table:" : "  Receiver Resolver Table:", "    ", dumpPackage2, false, false)) {
                needSep3 = needSep2;
                printedAnything2 = printedAnything;
            } else {
                needSep3 = true;
                printedAnything2 = true;
            }
        }
        BroadcastQueue[] broadcastQueueArr2 = this.mBroadcastQueues;
        int length = broadcastQueueArr2.length;
        boolean z = false;
        boolean needSep4 = needSep3;
        boolean printedAnything3 = printedAnything2;
        int i = 0;
        while (i < length) {
            BroadcastQueue q = broadcastQueueArr2[i];
            needSep4 = q.dumpLocked(fd, pw, args, opti, dumpAll2, dumpPackage2, needSep4);
            printedAnything3 |= needSep4;
            i++;
            z = z;
            length = length;
            broadcastQueueArr2 = broadcastQueueArr2;
        }
        boolean needSep5 = true;
        if (!onlyHistory && this.mStickyBroadcasts != null && dumpPackage2 == null) {
            for (int user = 0; user < this.mStickyBroadcasts.size(); user++) {
                if (needSep5) {
                    pw.println();
                }
                needSep5 = true;
                printedAnything3 = true;
                pw.print("  Sticky broadcasts for user ");
                pw.print(this.mStickyBroadcasts.keyAt(user));
                String str3 = ":";
                pw.println(":");
                StringBuilder sb = new StringBuilder(128);
                for (Map.Entry<String, ArrayList<Intent>> ent : this.mStickyBroadcasts.valueAt(user).entrySet()) {
                    pw.print("  * Sticky action ");
                    pw.print(ent.getKey());
                    if (dumpAll2) {
                        pw.println(str3);
                        ArrayList<Intent> intents = ent.getValue();
                        int N = intents.size();
                        int i2 = 0;
                        while (i2 < N) {
                            boolean needSep6 = needSep5;
                            sb.setLength(0);
                            sb.append("    Intent: ");
                            intents.get(i2).toShortString(sb, false, true, false, false);
                            pw.println(sb.toString());
                            Bundle bundle = intents.get(i2).getExtras();
                            if (bundle == null) {
                                str2 = str3;
                            } else {
                                str2 = str3;
                                pw.print("      ");
                                pw.println(bundle.toString());
                            }
                            i2++;
                            needSep5 = needSep6;
                            str3 = str2;
                        }
                        needSep = needSep5;
                        str = str3;
                    } else {
                        needSep = needSep5;
                        str = str3;
                        pw.println("");
                    }
                    needSep5 = needSep;
                    str3 = str;
                }
            }
        }
        if (!onlyHistory && dumpAll2) {
            pw.println();
            for (BroadcastQueue queue : this.mBroadcastQueues) {
                pw.println("  mBroadcastsScheduled [" + queue.mQueueName + "]=" + queue.mBroadcastsScheduled);
            }
            pw.println("  mHandler:");
            this.mHandler.dump(new PrintWriterPrinter(pw), "    ");
            printedAnything3 = true;
        }
        if (!printedAnything3) {
            pw.println("  (nothing)");
        }
    }

    void dumpBroadcastStatsLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage) {
        if (this.mCurBroadcastStats == null) {
            return;
        }
        pw.println("ACTIVITY MANAGER BROADCAST STATS STATE (dumpsys activity broadcast-stats)");
        long now = SystemClock.elapsedRealtime();
        if (this.mLastBroadcastStats != null) {
            pw.print("  Last stats (from ");
            TimeUtils.formatDuration(this.mLastBroadcastStats.mStartRealtime, now, pw);
            pw.print(" to ");
            TimeUtils.formatDuration(this.mLastBroadcastStats.mEndRealtime, now, pw);
            pw.print(", ");
            TimeUtils.formatDuration(this.mLastBroadcastStats.mEndUptime - this.mLastBroadcastStats.mStartUptime, pw);
            pw.println(" uptime):");
            if (!this.mLastBroadcastStats.dumpStats(pw, "    ", dumpPackage)) {
                pw.println("    (nothing)");
            }
            pw.println();
        }
        pw.print("  Current stats (from ");
        TimeUtils.formatDuration(this.mCurBroadcastStats.mStartRealtime, now, pw);
        pw.print(" to now, ");
        TimeUtils.formatDuration(SystemClock.uptimeMillis() - this.mCurBroadcastStats.mStartUptime, pw);
        pw.println(" uptime):");
        if (!this.mCurBroadcastStats.dumpStats(pw, "    ", dumpPackage)) {
            pw.println("    (nothing)");
        }
    }

    void dumpBroadcastStatsCheckinLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean fullCheckin, String dumpPackage) {
        if (this.mCurBroadcastStats == null) {
            return;
        }
        BroadcastStats broadcastStats = this.mLastBroadcastStats;
        if (broadcastStats != null) {
            broadcastStats.dumpCheckinStats(pw, dumpPackage);
            if (fullCheckin) {
                this.mLastBroadcastStats = null;
                return;
            }
        }
        this.mCurBroadcastStats.dumpCheckinStats(pw, dumpPackage);
        if (fullCheckin) {
            this.mCurBroadcastStats = null;
        }
    }

    void dumpProvidersLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage) {
        ItemMatcher matcher = new ItemMatcher();
        matcher.build(args, opti);
        pw.println("ACTIVITY MANAGER CONTENT PROVIDERS (dumpsys activity providers)");
        boolean needSep = this.mProviderMap.dumpProvidersLocked(pw, dumpAll, dumpPackage);
        boolean printedAnything = false | needSep;
        if (this.mLaunchingProviders.size() > 0) {
            boolean printed = false;
            for (int i = this.mLaunchingProviders.size() - 1; i >= 0; i--) {
                ContentProviderRecord r = this.mLaunchingProviders.get(i);
                if (dumpPackage == null || dumpPackage.equals(r.name.getPackageName())) {
                    if (!printed) {
                        if (needSep) {
                            pw.println();
                        }
                        needSep = true;
                        pw.println("  Launching content providers:");
                        printed = true;
                        printedAnything = true;
                    }
                    pw.print("  Launching #");
                    pw.print(i);
                    pw.print(": ");
                    pw.println(r);
                }
            }
        }
        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    @GuardedBy({"this"})
    void dumpPermissionsLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage) {
        pw.println("ACTIVITY MANAGER URI PERMISSIONS (dumpsys activity permissions)");
        this.mUgmInternal.dump(pw, dumpAll, dumpPackage);
    }

    private static final int dumpProcessList(PrintWriter pw, ActivityManagerService service, List list, String prefix, String normalLabel, String persistentLabel, String dumpPackage) {
        int numPers = 0;
        int N = list.size() - 1;
        for (int i = N; i >= 0; i--) {
            ProcessRecord r = (ProcessRecord) list.get(i);
            if (dumpPackage == null || dumpPackage.equals(r.info.packageName)) {
                Object[] objArr = new Object[4];
                objArr[0] = prefix;
                objArr[1] = r.isPersistent() ? persistentLabel : normalLabel;
                objArr[2] = Integer.valueOf(i);
                objArr[3] = r.toString();
                pw.println(String.format("%s%s #%2d: %s", objArr));
                if (r.isPersistent()) {
                    numPers++;
                }
            }
        }
        return numPers;
    }

    private static final ArrayList<Pair<ProcessRecord, Integer>> sortProcessOomList(List<ProcessRecord> origList, String dumpPackage) {
        ArrayList<Pair<ProcessRecord, Integer>> list = new ArrayList<>(origList.size());
        for (int i = 0; i < origList.size(); i++) {
            ProcessRecord r = origList.get(i);
            if (dumpPackage == null || r.pkgList.containsKey(dumpPackage)) {
                list.add(new Pair<>(origList.get(i), Integer.valueOf(i)));
            }
        }
        Comparator<Pair<ProcessRecord, Integer>> comparator = new Comparator<Pair<ProcessRecord, Integer>>() { // from class: com.android.server.am.ActivityManagerService.23
            @Override // java.util.Comparator
            public int compare(Pair<ProcessRecord, Integer> object1, Pair<ProcessRecord, Integer> object2) {
                if (((ProcessRecord) object1.first).setAdj != ((ProcessRecord) object2.first).setAdj) {
                    return ((ProcessRecord) object1.first).setAdj > ((ProcessRecord) object2.first).setAdj ? -1 : 1;
                } else if (((ProcessRecord) object1.first).setProcState != ((ProcessRecord) object2.first).setProcState) {
                    return ((ProcessRecord) object1.first).setProcState > ((ProcessRecord) object2.first).setProcState ? -1 : 1;
                } else if (((Integer) object1.second).intValue() != ((Integer) object2.second).intValue()) {
                    return ((Integer) object1.second).intValue() > ((Integer) object2.second).intValue() ? -1 : 1;
                } else {
                    return 0;
                }
            }
        };
        Collections.sort(list, comparator);
        return list;
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r7v0 */
    /* JADX WARN: Type inference failed for: r7v1, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r7v19 */
    private static final boolean writeProcessOomListToProto(ProtoOutputStream proto, long fieldId, ActivityManagerService service, List<ProcessRecord> origList, boolean inclDetails, String dumpPackage) {
        ArrayList<Pair<ProcessRecord, Integer>> list;
        long curUptime;
        ArrayList<Pair<ProcessRecord, Integer>> list2 = sortProcessOomList(origList, dumpPackage);
        if (list2.isEmpty()) {
            return false;
        }
        long curUptime2 = SystemClock.uptimeMillis();
        ?? r7 = 1;
        int i = list2.size() - 1;
        while (i >= 0) {
            ProcessRecord r = (ProcessRecord) list2.get(i).first;
            long token = proto.start(fieldId);
            String oomAdj = ProcessList.makeOomAdjString(r.setAdj, r7);
            proto.write(1133871366145L, r.isPersistent());
            proto.write(1120986464258L, (origList.size() - r7) - ((Integer) list2.get(i).second).intValue());
            proto.write(1138166333443L, oomAdj);
            int schedGroup = -1;
            int i2 = r.setSchedGroup;
            if (i2 == 0) {
                schedGroup = 0;
            } else if (i2 == 2) {
                schedGroup = 1;
            } else if (i2 == 3) {
                schedGroup = 2;
            } else if (i2 == 4) {
                schedGroup = 3;
            }
            if (schedGroup != -1) {
                proto.write(1159641169924L, schedGroup);
            }
            if (r.hasForegroundActivities()) {
                proto.write(1133871366149L, (boolean) r7);
            } else if (r.hasForegroundServices()) {
                proto.write(1133871366150L, (boolean) r7);
            }
            proto.write(1159641169927L, ProcessList.makeProcStateProtoEnum(r.getCurProcState()));
            proto.write(1120986464264L, r.trimMemoryLevel);
            r.writeToProto(proto, 1146756268041L);
            proto.write(1138166333450L, r.adjType);
            if (r.adjSource != null || r.adjTarget != null) {
                if (r.adjTarget instanceof ComponentName) {
                    ComponentName cn = (ComponentName) r.adjTarget;
                    cn.writeToProto(proto, 1146756268043L);
                } else if (r.adjTarget != null) {
                    proto.write(1138166333452L, r.adjTarget.toString());
                }
                if (r.adjSource instanceof ProcessRecord) {
                    ProcessRecord p = (ProcessRecord) r.adjSource;
                    p.writeToProto(proto, 1146756268045L);
                } else if (r.adjSource != null) {
                    proto.write(1138166333454L, r.adjSource.toString());
                }
            }
            if (inclDetails) {
                long detailToken = proto.start(1146756268047L);
                proto.write(1120986464257L, r.maxAdj);
                proto.write(1120986464258L, r.getCurRawAdj());
                proto.write(1120986464259L, r.setRawAdj);
                proto.write(1120986464260L, r.curAdj);
                proto.write(1120986464261L, r.setAdj);
                proto.write(1159641169927L, ProcessList.makeProcStateProtoEnum(r.getCurProcState()));
                proto.write(1159641169928L, ProcessList.makeProcStateProtoEnum(r.setProcState));
                proto.write(1138166333449L, DebugUtils.sizeValueToString(r.lastPss * 1024, new StringBuilder()));
                proto.write(1138166333450L, DebugUtils.sizeValueToString(r.lastSwapPss * 1024, new StringBuilder()));
                proto.write(1138166333451L, DebugUtils.sizeValueToString(r.lastCachedPss * 1024, new StringBuilder()));
                proto.write(1133871366156L, r.cached);
                proto.write(1133871366157L, r.empty);
                proto.write(1133871366158L, r.hasAboveClient);
                if (r.setProcState < 11) {
                    list = list2;
                    curUptime = curUptime2;
                } else if (r.lastCpuTime == 0) {
                    list = list2;
                    curUptime = curUptime2;
                } else {
                    long uptimeSince = curUptime2 - service.mLastPowerCheckUptime;
                    list = list2;
                    curUptime = curUptime2;
                    long timeUsed = r.curCpuTime - r.lastCpuTime;
                    long cpuTimeToken = proto.start(1146756268047L);
                    proto.write(1112396529665L, uptimeSince);
                    proto.write(1112396529666L, timeUsed);
                    proto.write(1108101562371L, (timeUsed * 100.0d) / uptimeSince);
                    proto.end(cpuTimeToken);
                }
                proto.end(detailToken);
            } else {
                list = list2;
                curUptime = curUptime2;
            }
            proto.end(token);
            i--;
            list2 = list;
            curUptime2 = curUptime;
            r7 = 1;
        }
        return true;
    }

    private static final boolean dumpProcessOomList(PrintWriter pw, ActivityManagerService service, List<ProcessRecord> origList, String prefix, String normalLabel, String persistentLabel, boolean inclDetails, String dumpPackage) {
        char schedGroup;
        char foreground;
        char c;
        long curUptime;
        ArrayList<Pair<ProcessRecord, Integer>> list = sortProcessOomList(origList, dumpPackage);
        boolean z = false;
        if (list.isEmpty()) {
            return false;
        }
        long curUptime2 = SystemClock.uptimeMillis();
        long uptimeSince = curUptime2 - service.mLastPowerCheckUptime;
        int i = 1;
        int i2 = list.size() - 1;
        while (i2 >= 0) {
            ProcessRecord r = (ProcessRecord) list.get(i2).first;
            String oomAdj = ProcessList.makeOomAdjString(r.setAdj, z);
            int i3 = r.setSchedGroup;
            if (i3 == 0) {
                schedGroup = 'B';
            } else if (i3 == i) {
                schedGroup = 'R';
            } else if (i3 == 2) {
                schedGroup = 'F';
            } else if (i3 == 3) {
                schedGroup = 'T';
            } else {
                schedGroup = '?';
            }
            if (r.hasForegroundActivities()) {
                foreground = 'A';
            } else if (r.hasForegroundServices()) {
                foreground = 'S';
            } else {
                foreground = ' ';
            }
            String procState = ProcessList.makeProcStateString(r.getCurProcState());
            pw.print(prefix);
            pw.print(r.isPersistent() ? persistentLabel : normalLabel);
            pw.print(" #");
            int num = (origList.size() - 1) - ((Integer) list.get(i2).second).intValue();
            ArrayList<Pair<ProcessRecord, Integer>> list2 = list;
            if (num < 10) {
                pw.print(' ');
            }
            pw.print(num);
            pw.print(": ");
            pw.print(oomAdj);
            pw.print(' ');
            pw.print(schedGroup);
            pw.print('/');
            pw.print(foreground);
            pw.print('/');
            pw.print(procState);
            pw.print(" trm:");
            if (r.trimMemoryLevel < 10) {
                c = ' ';
                pw.print(' ');
            } else {
                c = ' ';
            }
            pw.print(r.trimMemoryLevel);
            pw.print(c);
            pw.print(r.toShortString());
            pw.print(" (");
            pw.print(r.adjType);
            pw.println(')');
            if (r.adjSource != null || r.adjTarget != null) {
                pw.print(prefix);
                pw.print("    ");
                if (r.adjTarget instanceof ComponentName) {
                    pw.print(((ComponentName) r.adjTarget).flattenToShortString());
                } else if (r.adjTarget != null) {
                    pw.print(r.adjTarget.toString());
                } else {
                    pw.print("{null}");
                }
                pw.print("<=");
                if (r.adjSource instanceof ProcessRecord) {
                    pw.print("Proc{");
                    pw.print(((ProcessRecord) r.adjSource).toShortString());
                    pw.println("}");
                } else if (r.adjSource != null) {
                    pw.println(r.adjSource.toString());
                } else {
                    pw.println("{null}");
                }
            }
            if (!inclDetails) {
                curUptime = curUptime2;
            } else {
                pw.print(prefix);
                pw.print("    ");
                pw.print("oom: max=");
                pw.print(r.maxAdj);
                pw.print(" curRaw=");
                pw.print(r.getCurRawAdj());
                pw.print(" setRaw=");
                pw.print(r.setRawAdj);
                pw.print(" cur=");
                pw.print(r.curAdj);
                pw.print(" set=");
                pw.println(r.setAdj);
                pw.print(prefix);
                pw.print("    ");
                pw.print("state: cur=");
                pw.print(ProcessList.makeProcStateString(r.getCurProcState()));
                pw.print(" set=");
                pw.print(ProcessList.makeProcStateString(r.setProcState));
                pw.print(" lastPss=");
                DebugUtils.printSizeValue(pw, r.lastPss * 1024);
                pw.print(" lastSwapPss=");
                DebugUtils.printSizeValue(pw, r.lastSwapPss * 1024);
                pw.print(" lastCachedPss=");
                DebugUtils.printSizeValue(pw, r.lastCachedPss * 1024);
                pw.println();
                pw.print(prefix);
                pw.print("    ");
                pw.print("cached=");
                pw.print(r.cached);
                pw.print(" empty=");
                pw.print(r.empty);
                pw.print(" hasAboveClient=");
                pw.println(r.hasAboveClient);
                if (r.setProcState < 11) {
                    curUptime = curUptime2;
                } else if (r.lastCpuTime == 0) {
                    curUptime = curUptime2;
                } else {
                    curUptime = curUptime2;
                    long timeUsed = r.curCpuTime - r.lastCpuTime;
                    pw.print(prefix);
                    pw.print("    ");
                    pw.print("run cpu over ");
                    TimeUtils.formatDuration(uptimeSince, pw);
                    pw.print(" used ");
                    TimeUtils.formatDuration(timeUsed, pw);
                    pw.print(" (");
                    pw.print((100 * timeUsed) / uptimeSince);
                    pw.println("%)");
                }
            }
            i2--;
            list = list2;
            curUptime2 = curUptime;
            z = false;
            i = 1;
        }
        return true;
    }

    ArrayList<ProcessRecord> collectProcesses(PrintWriter pw, int start, boolean allPkgs, String[] args) {
        ArrayList<ProcessRecord> collectProcessesLocked;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                collectProcessesLocked = this.mProcessList.collectProcessesLocked(start, allPkgs, args);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return collectProcessesLocked;
    }

    final void dumpGraphicsHardwareUsage(FileDescriptor fd, PrintWriter pw, String[] args) {
        ArrayList<ProcessRecord> procs = collectProcesses(pw, 0, false, args);
        if (procs == null) {
            pw.println("No process found for: " + args[0]);
            return;
        }
        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();
        pw.println("Applications Graphics Acceleration Info:");
        pw.println("Uptime: " + uptime + " Realtime: " + realtime);
        for (int i = procs.size() + (-1); i >= 0; i--) {
            ProcessRecord r = procs.get(i);
            if (r.thread != null) {
                pw.println("\n** Graphics info for pid " + r.pid + " [" + r.processName + "] **");
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.thread.dumpGfxInfo(tp.getWriteFd(), args);
                        tp.go(fd);
                        tp.kill();
                    } catch (Throwable th) {
                        tp.kill();
                        throw th;
                        break;
                    }
                } catch (RemoteException e) {
                    pw.println("Got a RemoteException while dumping the app " + r);
                    pw.flush();
                } catch (IOException e2) {
                    pw.println("Failure while dumping the app: " + r);
                    pw.flush();
                }
            }
        }
    }

    final void dumpDbInfo(FileDescriptor fd, PrintWriter pw, String[] args) {
        ArrayList<ProcessRecord> procs = collectProcesses(pw, 0, false, args);
        if (procs == null) {
            pw.println("No process found for: " + args[0]);
            return;
        }
        pw.println("Applications Database Info:");
        for (int i = procs.size() - 1; i >= 0; i--) {
            ProcessRecord r = procs.get(i);
            if (r.thread != null) {
                pw.println("\n** Database info for pid " + r.pid + " [" + r.processName + "] **");
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.thread.dumpDbInfo(tp.getWriteFd(), args);
                        tp.go(fd);
                        tp.kill();
                    } catch (Throwable th) {
                        tp.kill();
                        throw th;
                        break;
                    }
                } catch (RemoteException e) {
                    pw.println("Got a RemoteException while dumping the app " + r);
                    pw.flush();
                } catch (IOException e2) {
                    pw.println("Failure while dumping the app: " + r);
                    pw.flush();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class MemItem {
        final boolean hasActivities;
        final int id;
        final boolean isProc;
        final String label;
        final long pss;
        final String shortLabel;
        ArrayList<MemItem> subitems;
        final long swapPss;

        public MemItem(String _label, String _shortLabel, long _pss, long _swapPss, int _id, boolean _hasActivities) {
            this.isProc = true;
            this.label = _label;
            this.shortLabel = _shortLabel;
            this.pss = _pss;
            this.swapPss = _swapPss;
            this.id = _id;
            this.hasActivities = _hasActivities;
        }

        public MemItem(String _label, String _shortLabel, long _pss, long _swapPss, int _id) {
            this.isProc = false;
            this.label = _label;
            this.shortLabel = _shortLabel;
            this.pss = _pss;
            this.swapPss = _swapPss;
            this.id = _id;
            this.hasActivities = false;
        }
    }

    private static void sortMemItems(List<MemItem> items) {
        Collections.sort(items, new Comparator<MemItem>() { // from class: com.android.server.am.ActivityManagerService.24
            @Override // java.util.Comparator
            public int compare(MemItem lhs, MemItem rhs) {
                if (lhs.pss < rhs.pss) {
                    return 1;
                }
                if (lhs.pss > rhs.pss) {
                    return -1;
                }
                return 0;
            }
        });
    }

    static final void dumpMemItems(PrintWriter pw, String prefix, String tag, ArrayList<MemItem> items, boolean sort, boolean isCompact, boolean dumpSwapPss) {
        if (sort && !isCompact) {
            sortMemItems(items);
        }
        for (int i = 0; i < items.size(); i++) {
            MemItem mi = items.get(i);
            if (isCompact) {
                if (mi.isProc) {
                    pw.print("proc,");
                    pw.print(tag);
                    pw.print(",");
                    pw.print(mi.shortLabel);
                    pw.print(",");
                    pw.print(mi.id);
                    pw.print(",");
                    pw.print(mi.pss);
                    pw.print(",");
                    pw.print(dumpSwapPss ? Long.valueOf(mi.swapPss) : "N/A");
                    pw.println(mi.hasActivities ? ",a" : ",e");
                } else {
                    pw.print(tag);
                    pw.print(",");
                    pw.print(mi.shortLabel);
                    pw.print(",");
                    pw.print(mi.pss);
                    pw.print(",");
                    pw.println(dumpSwapPss ? Long.valueOf(mi.swapPss) : "N/A");
                }
            } else if (dumpSwapPss) {
                pw.printf("%s%s: %-60s (%s in swap)\n", prefix, stringifyKBSize(mi.pss), mi.label, stringifyKBSize(mi.swapPss));
            } else {
                pw.printf("%s%s: %s\n", prefix, stringifyKBSize(mi.pss), mi.label);
            }
            if (mi.subitems != null) {
                dumpMemItems(pw, prefix + "    ", mi.shortLabel, mi.subitems, true, isCompact, dumpSwapPss);
            }
        }
    }

    static final void dumpMemItems(ProtoOutputStream proto, long fieldId, String tag, ArrayList<MemItem> items, boolean sort, boolean dumpSwapPss) {
        if (sort) {
            sortMemItems(items);
        }
        for (int i = 0; i < items.size(); i++) {
            MemItem mi = items.get(i);
            long token = proto.start(fieldId);
            proto.write(1138166333441L, tag);
            proto.write(1138166333442L, mi.shortLabel);
            proto.write(1133871366148L, mi.isProc);
            proto.write(1120986464259L, mi.id);
            proto.write(1133871366149L, mi.hasActivities);
            proto.write(1112396529670L, mi.pss);
            if (dumpSwapPss) {
                proto.write(1112396529671L, mi.swapPss);
            }
            if (mi.subitems != null) {
                dumpMemItems(proto, 2246267895816L, mi.shortLabel, mi.subitems, true, dumpSwapPss);
            }
            proto.end(token);
        }
    }

    static final void appendMemBucket(StringBuilder out, long memKB, String label, boolean stackLike) {
        int start = label.lastIndexOf(46);
        int start2 = start >= 0 ? start + 1 : 0;
        int end = label.length();
        int i = 0;
        while (true) {
            long[] jArr = DUMP_MEM_BUCKETS;
            if (i < jArr.length) {
                if (jArr[i] < memKB) {
                    i++;
                } else {
                    long bucket = jArr[i] / 1024;
                    out.append(bucket);
                    out.append(stackLike ? "MB." : "MB ");
                    out.append((CharSequence) label, start2, end);
                    return;
                }
            } else {
                out.append(memKB / 1024);
                out.append(stackLike ? "MB." : "MB ");
                out.append((CharSequence) label, start2, end);
                return;
            }
        }
    }

    private final void dumpApplicationMemoryUsageHeader(PrintWriter pw, long uptime, long realtime, boolean isCheckinRequest, boolean isCompact) {
        if (isCompact) {
            pw.print("version,");
            pw.println(1);
        }
        if (isCheckinRequest || isCompact) {
            pw.print("time,");
            pw.print(uptime);
            pw.print(",");
            pw.println(realtime);
            return;
        }
        pw.println("Applications Memory Usage (in Kilobytes):");
        pw.println("Uptime: " + uptime + " Realtime: " + realtime);
    }

    private final long[] getKsmInfo() {
        int[] SINGLE_LONG_FORMAT = {8224};
        Process.readProcFile("/sys/kernel/mm/ksm/pages_shared", SINGLE_LONG_FORMAT, null, longTmp, null);
        long[] longTmp = {0};
        Process.readProcFile("/sys/kernel/mm/ksm/pages_sharing", SINGLE_LONG_FORMAT, null, longTmp, null);
        longTmp[0] = 0;
        Process.readProcFile("/sys/kernel/mm/ksm/pages_unshared", SINGLE_LONG_FORMAT, null, longTmp, null);
        longTmp[0] = 0;
        Process.readProcFile("/sys/kernel/mm/ksm/pages_volatile", SINGLE_LONG_FORMAT, null, longTmp, null);
        long[] longOut = {(longTmp[0] * 4096) / 1024, (longTmp[0] * 4096) / 1024, (longTmp[0] * 4096) / 1024, (longTmp[0] * 4096) / 1024};
        return longOut;
    }

    private static String stringifySize(long size, int order) {
        Locale locale = Locale.US;
        if (order != 1) {
            if (order != 1024) {
                if (order != 1048576) {
                    if (order == 1073741824) {
                        return String.format(locale, "%,1dG", Long.valueOf(((size / 1024) / 1024) / 1024));
                    }
                    throw new IllegalArgumentException("Invalid size order");
                }
                return String.format(locale, "%,5dM", Long.valueOf((size / 1024) / 1024));
            }
            return String.format(locale, "%,9dK", Long.valueOf(size / 1024));
        }
        return String.format(locale, "%,13d", Long.valueOf(size));
    }

    private static String stringifyKBSize(long size) {
        return stringifySize(1024 * size, 1024);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class MemoryUsageDumpOptions {
        boolean dumpDalvik;
        boolean dumpDetails;
        boolean dumpFullDetails;
        boolean dumpProto;
        boolean dumpSummaryOnly;
        boolean dumpSwapPss;
        boolean dumpUnreachable;
        boolean isCheckinRequest;
        boolean isCompact;
        boolean localOnly;
        boolean oomOnly;
        boolean packages;

        private MemoryUsageDumpOptions() {
        }
    }

    final void dumpApplicationMemoryUsage(FileDescriptor fd, PrintWriter pw, String prefix, String[] args, boolean brief, PrintWriter categoryPw, boolean asProto) {
        String opt;
        MemoryUsageDumpOptions opts = new MemoryUsageDumpOptions();
        opts.dumpDetails = false;
        opts.dumpFullDetails = false;
        opts.dumpDalvik = false;
        opts.dumpSummaryOnly = false;
        opts.dumpUnreachable = false;
        opts.oomOnly = false;
        opts.isCompact = false;
        opts.localOnly = false;
        opts.packages = false;
        opts.isCheckinRequest = false;
        opts.dumpSwapPss = false;
        opts.dumpProto = asProto;
        int opti = 0;
        while (opti < args.length && (opt = args[opti]) != null && opt.length() > 0 && opt.charAt(0) == '-') {
            opti++;
            if ("-a".equals(opt)) {
                opts.dumpDetails = true;
                opts.dumpFullDetails = true;
                opts.dumpDalvik = true;
                opts.dumpSwapPss = true;
            } else if ("-d".equals(opt)) {
                opts.dumpDalvik = true;
            } else if ("-c".equals(opt)) {
                opts.isCompact = true;
            } else if ("-s".equals(opt)) {
                opts.dumpDetails = true;
                opts.dumpSummaryOnly = true;
            } else if ("-S".equals(opt)) {
                opts.dumpSwapPss = true;
            } else if ("--unreachable".equals(opt)) {
                opts.dumpUnreachable = true;
            } else if ("--oom".equals(opt)) {
                opts.oomOnly = true;
            } else if ("--local".equals(opt)) {
                opts.localOnly = true;
            } else if ("--package".equals(opt)) {
                opts.packages = true;
            } else if ("--checkin".equals(opt)) {
                opts.isCheckinRequest = true;
            } else if (PriorityDump.PROTO_ARG.equals(opt)) {
                opts.dumpProto = true;
            } else if ("-h".equals(opt)) {
                pw.println("meminfo dump options: [-a] [-d] [-c] [-s] [--oom] [process]");
                pw.println("  -a: include all available information for each process.");
                pw.println("  -d: include dalvik details.");
                pw.println("  -c: dump in a compact machine-parseable representation.");
                pw.println("  -s: dump only summary of application memory usage.");
                pw.println("  -S: dump also SwapPss.");
                pw.println("  --oom: only show processes organized by oom adj.");
                pw.println("  --local: only collect details locally, don't call process.");
                pw.println("  --package: interpret process arg as package, dumping all");
                pw.println("             processes that have loaded that package.");
                pw.println("  --checkin: dump data for a checkin");
                pw.println("  --proto: dump data to proto");
                pw.println("If [process] is specified it can be the name or ");
                pw.println("pid of a specific process to dump.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }
        String[] innerArgs = new String[args.length - opti];
        System.arraycopy(args, opti, innerArgs, 0, args.length - opti);
        ArrayList<ProcessRecord> procs = collectProcesses(pw, opti, opts.packages, args);
        if (opts.dumpProto) {
            dumpApplicationMemoryUsage(fd, opts, innerArgs, brief, procs);
        } else {
            dumpApplicationMemoryUsage(fd, pw, prefix, opts, innerArgs, brief, procs, categoryPw);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:112:0x02d0  */
    /* JADX WARN: Removed duplicated region for block: B:153:0x03be  */
    /* JADX WARN: Removed duplicated region for block: B:280:0x077b A[Catch: all -> 0x0804, TRY_LEAVE, TryCatch #22 {all -> 0x0804, blocks: (B:278:0x0778, B:280:0x077b), top: B:497:0x0778 }] */
    /* JADX WARN: Removed duplicated region for block: B:286:0x07b5 A[Catch: all -> 0x07f0, LOOP:13: B:284:0x07b1->B:286:0x07b5, LOOP_END, TryCatch #12 {all -> 0x07f0, blocks: (B:282:0x0789, B:283:0x07a2, B:286:0x07b5, B:287:0x07d0, B:289:0x07de, B:290:0x07e5), top: B:479:0x0789 }] */
    /* JADX WARN: Removed duplicated region for block: B:289:0x07de A[Catch: all -> 0x07f0, TryCatch #12 {all -> 0x07f0, blocks: (B:282:0x0789, B:283:0x07a2, B:286:0x07b5, B:287:0x07d0, B:289:0x07de, B:290:0x07e5), top: B:479:0x0789 }] */
    /* JADX WARN: Removed duplicated region for block: B:395:0x0ac4  */
    /* JADX WARN: Removed duplicated region for block: B:412:0x0ba4  */
    /* JADX WARN: Removed duplicated region for block: B:413:0x0be8  */
    /* JADX WARN: Removed duplicated region for block: B:415:0x0bf2  */
    /* JADX WARN: Removed duplicated region for block: B:446:0x0d8e  */
    /* JADX WARN: Removed duplicated region for block: B:495:0x03ec A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:550:0x07a2 A[EDGE_INSN: B:550:0x07a2->B:283:0x07a2 ?: BREAK  , SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private final void dumpApplicationMemoryUsage(java.io.FileDescriptor r93, java.io.PrintWriter r94, java.lang.String r95, com.android.server.am.ActivityManagerService.MemoryUsageDumpOptions r96, java.lang.String[] r97, boolean r98, java.util.ArrayList<com.android.server.am.ProcessRecord> r99, java.io.PrintWriter r100) {
        /*
            Method dump skipped, instructions count: 3538
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.dumpApplicationMemoryUsage(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String, com.android.server.am.ActivityManagerService$MemoryUsageDumpOptions, java.lang.String[], boolean, java.util.ArrayList, java.io.PrintWriter):void");
    }

    /* JADX WARN: Can't wrap try/catch for region: R(5:(2:366|367)(1:301)|(5:(19:(3:358|359|(22:361|362|308|309|310|311|312|313|314|315|316|317|(4:320|321|322|318)|323|324|(2:327|325)|328|329|(1:331)|332|333|334))|310|311|312|313|314|315|316|317|(1:318)|323|324|(1:325)|328|329|(0)|332|333|334)|306|307|308|309)|303|304|305) */
    /* JADX WARN: Can't wrap try/catch for region: R(7:(13:(3:358|359|(22:361|362|308|309|310|311|312|313|314|315|316|317|(4:320|321|322|318)|323|324|(2:327|325)|328|329|(1:331)|332|333|334))|316|317|(1:318)|323|324|(1:325)|328|329|(0)|332|333|334)|310|311|312|313|314|315) */
    /* JADX WARN: Code restructure failed: missing block: B:280:0x089f, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:286:0x08c9, code lost:
        r0 = th;
     */
    /* JADX WARN: Removed duplicated region for block: B:115:0x02f3  */
    /* JADX WARN: Removed duplicated region for block: B:142:0x0427  */
    /* JADX WARN: Removed duplicated region for block: B:266:0x081c A[Catch: all -> 0x088f, TRY_LEAVE, TryCatch #25 {all -> 0x088f, blocks: (B:263:0x0800, B:264:0x0819, B:266:0x081c), top: B:422:0x0800 }] */
    /* JADX WARN: Removed duplicated region for block: B:272:0x0854 A[Catch: all -> 0x089f, LOOP:12: B:270:0x0850->B:272:0x0854, LOOP_END, TryCatch #33 {all -> 0x089f, blocks: (B:261:0x07cd, B:268:0x0838, B:269:0x0841, B:272:0x0854, B:273:0x086f, B:275:0x087d, B:276:0x0884), top: B:434:0x07cd }] */
    /* JADX WARN: Removed duplicated region for block: B:275:0x087d A[Catch: all -> 0x089f, TryCatch #33 {all -> 0x089f, blocks: (B:261:0x07cd, B:268:0x0838, B:269:0x0841, B:272:0x0854, B:273:0x086f, B:275:0x087d, B:276:0x0884), top: B:434:0x07cd }] */
    /* JADX WARN: Removed duplicated region for block: B:357:0x0abf  */
    /* JADX WARN: Removed duplicated region for block: B:358:0x0afb  */
    /* JADX WARN: Removed duplicated region for block: B:361:0x0b3f  */
    /* JADX WARN: Removed duplicated region for block: B:428:0x0458 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private final void dumpApplicationMemoryUsage(java.io.FileDescriptor r108, com.android.server.am.ActivityManagerService.MemoryUsageDumpOptions r109, java.lang.String[] r110, boolean r111, java.util.ArrayList<com.android.server.am.ProcessRecord> r112) {
        /*
            Method dump skipped, instructions count: 3100
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.dumpApplicationMemoryUsage(java.io.FileDescriptor, com.android.server.am.ActivityManagerService$MemoryUsageDumpOptions, java.lang.String[], boolean, java.util.ArrayList):void");
    }

    private void appendBasicMemEntry(StringBuilder sb, int oomAdj, int procState, long pss, long memtrack, String name) {
        sb.append("  ");
        sb.append(ProcessList.makeOomAdjString(oomAdj, false));
        sb.append(' ');
        sb.append(ProcessList.makeProcStateString(procState));
        sb.append(' ');
        ProcessList.appendRamKb(sb, pss);
        sb.append(": ");
        sb.append(name);
        if (memtrack > 0) {
            sb.append(" (");
            sb.append(stringifyKBSize(memtrack));
            sb.append(" memtrack)");
        }
    }

    private void appendMemInfo(StringBuilder sb, ProcessMemInfo mi) {
        appendBasicMemEntry(sb, mi.oomAdj, mi.procState, mi.pss, mi.memtrack, mi.name);
        sb.append(" (pid ");
        sb.append(mi.pid);
        sb.append(") ");
        sb.append(mi.adjType);
        sb.append('\n');
        if (mi.adjReason != null) {
            sb.append("                      ");
            sb.append(mi.adjReason);
            sb.append('\n');
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:98:0x03bf, code lost:
        if (r38[3] == 0) goto L113;
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r2v58 */
    /* JADX WARN: Type inference failed for: r2v60 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    void reportMemUsage(java.util.ArrayList<com.android.server.am.ProcessMemInfo> r55) {
        /*
            Method dump skipped, instructions count: 1455
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.reportMemUsage(java.util.ArrayList):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$reportMemUsage$3(ProcessCpuTracker.Stats st) {
        return st.vsize > 0;
    }

    private static boolean scanArgs(String[] args, String value) {
        if (args != null) {
            for (String arg : args) {
                if (value.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private final boolean removeDyingProviderLocked(ProcessRecord proc, ContentProviderRecord cpr, boolean always) {
        boolean inLaunching = this.mLaunchingProviders.contains(cpr);
        if (!inLaunching || always) {
            synchronized (cpr) {
                cpr.launchingApp = null;
                cpr.notifyAll();
            }
            this.mProviderMap.removeProviderByClass(cpr.name, UserHandle.getUserId(cpr.uid));
            String[] names = cpr.info.authority.split(";");
            for (String str : names) {
                this.mProviderMap.removeProviderByName(str, UserHandle.getUserId(cpr.uid));
            }
        }
        for (int i = cpr.connections.size() - 1; i >= 0; i--) {
            ContentProviderConnection conn = cpr.connections.get(i);
            if (!conn.waiting || !inLaunching || always) {
                ProcessRecord capp = conn.client;
                conn.dead = true;
                if (conn.stableCount > 0) {
                    if (!capp.isPersistent() && capp.thread != null && capp.pid != 0 && capp.pid != MY_PID) {
                        if (cpr.name != null && cpr.name.getClassName() != null && cpr.name.getClassName().contains("ApiPublisherProvider")) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("depends on provider ");
                            sb.append(cpr.name.flattenToShortString());
                            sb.append(" client ");
                            sb.append(proc != null ? proc.processName : "??");
                            sb.append(" keep alive");
                            Log.d("ActivityManager", sb.toString());
                        } else {
                            StringBuilder sb2 = new StringBuilder();
                            sb2.append("depends on provider ");
                            sb2.append(cpr.name.flattenToShortString());
                            sb2.append(" in dying proc ");
                            sb2.append(proc != null ? proc.processName : "??");
                            sb2.append(" (adj ");
                            sb2.append(proc != null ? Integer.valueOf(proc.setAdj) : "??");
                            sb2.append(")");
                            capp.kill(sb2.toString(), true);
                        }
                    }
                } else if (capp.thread != null && conn.provider.provider != null) {
                    try {
                        capp.thread.unstableProviderDied(conn.provider.provider.asBinder());
                    } catch (RemoteException e) {
                    }
                    cpr.connections.remove(i);
                    if (conn.client.conProviders.remove(conn)) {
                        stopAssociationLocked(capp.uid, capp.processName, cpr.uid, cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
                    }
                }
            }
        }
        if (inLaunching && always) {
            this.mLaunchingProviders.remove(cpr);
        }
        return inLaunching;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final boolean cleanUpApplicationRecordLocked(final ProcessRecord app, boolean restarting, boolean allowRestart, int index, boolean replacingPid) {
        boolean restart;
        if (index >= 0) {
            removeLruProcessLocked(app);
            ProcessList.remove(app.pid);
        }
        this.mProcessesToGc.remove(app);
        this.mPendingPssProcesses.remove(app);
        ProcessList.abortNextPssTime(app.procStateMemTracker);
        if (app.crashDialog != null && !app.forceCrashReport) {
            app.crashDialog.dismiss();
            app.crashDialog = null;
        }
        if (app.anrDialog != null) {
            app.anrDialog.dismiss();
            app.anrDialog = null;
        }
        if (app.waitDialog != null) {
            app.waitDialog.dismiss();
            app.waitDialog = null;
        }
        app.setCrashing(false);
        app.setNotResponding(false);
        app.resetPackageList(this.mProcessStats);
        app.unlinkDeathRecipient();
        app.makeInactive(this.mProcessStats);
        app.waitingToKill = null;
        app.forcingToImportant = null;
        updateProcessForegroundLocked(app, false, 0, false);
        app.setHasForegroundActivities(false);
        app.hasShownUi = false;
        app.treatLikeActivity = false;
        app.hasAboveClient = false;
        app.setHasClientActivities(false);
        this.mServices.killServicesLocked(app, allowRestart);
        boolean restart2 = false;
        for (int i = app.pubProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = app.pubProviders.valueAt(i);
            boolean always = app.bad || !allowRestart;
            boolean inLaunching = removeDyingProviderLocked(app, cpr, always);
            if ((inLaunching || always) && cpr.hasConnectionOrHandle()) {
                restart2 = true;
            }
            cpr.provider = null;
            cpr.setProcess(null);
        }
        app.pubProviders.clear();
        if (!cleanupAppInLaunchingProvidersLocked(app, false)) {
            restart = restart2;
        } else {
            restart = true;
        }
        if (!app.conProviders.isEmpty()) {
            for (int i2 = app.conProviders.size() - 1; i2 >= 0; i2--) {
                ContentProviderConnection conn = app.conProviders.get(i2);
                conn.provider.connections.remove(conn);
                stopAssociationLocked(app.uid, app.processName, conn.provider.uid, conn.provider.appInfo.longVersionCode, conn.provider.name, conn.provider.info.processName);
            }
            app.conProviders.clear();
        }
        skipCurrentReceiverLocked(app);
        for (int i3 = app.receivers.size() - 1; i3 >= 0; i3--) {
            removeReceiverLocked(app.receivers.valueAt(i3));
        }
        app.receivers.clear();
        BackupRecord backupTarget = this.mBackupTargets.get(app.userId);
        if (backupTarget != null && app.pid == backupTarget.app.pid) {
            if (ActivityManagerDebugConfig.DEBUG_BACKUP || ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                Slog.d("ActivityManager", "App " + backupTarget.appInfo + " died during backup");
            }
            this.mHandler.post(new Runnable() { // from class: com.android.server.am.ActivityManagerService.26
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        IBackupManager bm = IBackupManager.Stub.asInterface(ServiceManager.getService(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD));
                        bm.agentDisconnectedForUser(app.userId, app.info.packageName);
                    } catch (RemoteException e) {
                    }
                }
            });
        }
        for (int i4 = this.mPendingProcessChanges.size() - 1; i4 >= 0; i4--) {
            ProcessChangeItem item = this.mPendingProcessChanges.get(i4);
            if (app.pid > 0 && item.pid == app.pid) {
                this.mPendingProcessChanges.remove(i4);
                this.mAvailProcessChanges.add(item);
            }
        }
        this.mUiHandler.obtainMessage(32, app.pid, app.info.uid, null).sendToTarget();
        if (restarting) {
            return false;
        }
        if (!app.isPersistent() || app.isolated || app.isInstalling()) {
            if (ActivityManagerDebugConfig.DEBUG_PROCESSES || ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) {
                Slog.v("ActivityManager", "Removing non-persistent process during cleanup: " + app);
            }
            if (!replacingPid) {
                this.mProcessList.removeProcessNameLocked(app.processName, app.uid, app);
            }
            this.mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());
        } else if (!app.removed && this.mPersistentStartingProcesses.indexOf(app) < 0) {
            this.mPersistentStartingProcesses.add(app);
            restart = true;
        }
        if ((ActivityManagerDebugConfig.DEBUG_PROCESSES || ActivityTaskManagerDebugConfig.DEBUG_CLEANUP) && this.mProcessesOnHold.contains(app)) {
            Slog.v("ActivityManager", "Clean-up removing on hold: " + app);
        }
        this.mProcessesOnHold.remove(app);
        this.mAtmInternal.onCleanUpApplicationRecord(app.getWindowProcessController());
        if (restart && !app.isolated && !app.isInstalling()) {
            if (index < 0) {
                ProcessList.remove(app.pid);
            }
            this.mProcessList.addProcessNameLocked(app);
            app.pendingStart = false;
            this.mProcessList.startProcessLocked(app, new HostingRecord("restart", app.processName));
            return true;
        }
        if (app.pid > 0 && app.pid != MY_PID) {
            this.mPidsSelfLocked.remove(app);
            this.mHandler.removeMessages(20, app);
            this.mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            if (app.isolated) {
                this.mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            app.setPid(0);
        }
        return false;
    }

    boolean checkAppInLaunchingProvidersLocked(ProcessRecord app) {
        for (int i = this.mLaunchingProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = this.mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                return true;
            }
        }
        return false;
    }

    boolean cleanupAppInLaunchingProvidersLocked(ProcessRecord app, boolean alwaysBad) {
        boolean restart = false;
        for (int i = this.mLaunchingProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = this.mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                if (!alwaysBad && !app.bad && cpr.hasConnectionOrHandle()) {
                    restart = true;
                } else {
                    removeDyingProviderLocked(app, cpr, true);
                }
            }
        }
        return restart;
    }

    public List<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags) {
        List<ActivityManager.RunningServiceInfo> runningServiceInfoLocked;
        enforceNotIsolatedCaller("getServices");
        int callingUid = Binder.getCallingUid();
        boolean canInteractAcrossUsers = ActivityManager.checkUidPermission("android.permission.INTERACT_ACROSS_USERS_FULL", callingUid) == 0;
        boolean allowed = this.mAtmInternal.isGetTasksAllowed("getServices", Binder.getCallingPid(), callingUid);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                runningServiceInfoLocked = this.mServices.getRunningServiceInfoLocked(maxNum, flags, callingUid, allowed, canInteractAcrossUsers);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return runningServiceInfoLocked;
    }

    public PendingIntent getRunningServiceControlPanel(ComponentName name) {
        PendingIntent runningServiceControlPanelLocked;
        enforceNotIsolatedCaller("getRunningServiceControlPanel");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                runningServiceControlPanelLocked = this.mServices.getRunningServiceControlPanelLocked(name);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return runningServiceControlPanelLocked;
    }

    public ComponentName startService(IApplicationThread caller, Intent service, String resolvedType, boolean requireForeground, String callingPackage, int userId) throws TransactionTooLargeException {
        ComponentName res;
        enforceNotIsolatedCaller("startService");
        if (service != null && service.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        if (callingPackage != null) {
            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "*** startService: " + service + " type=" + resolvedType + " fg=" + requireForeground);
            }
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    int callingPid = Binder.getCallingPid();
                    int callingUid = Binder.getCallingUid();
                    long origId = Binder.clearCallingIdentity();
                    res = this.mServices.startServiceLocked(caller, service, resolvedType, callingPid, callingUid, requireForeground, callingPackage, userId);
                    Binder.restoreCallingIdentity(origId);
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            return res;
        }
        throw new IllegalArgumentException("callingPackage cannot be null");
    }

    public int stopService(IApplicationThread caller, Intent service, String resolvedType, int userId) {
        int stopServiceLocked;
        enforceNotIsolatedCaller("stopService");
        if (service != null && service.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                stopServiceLocked = this.mServices.stopServiceLocked(caller, service, resolvedType, userId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return stopServiceLocked;
    }

    public IBinder peekService(Intent service, String resolvedType, String callingPackage) {
        IBinder peekServiceLocked;
        enforceNotIsolatedCaller("peekService");
        if (service != null && service.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        if (callingPackage == null) {
            throw new IllegalArgumentException("callingPackage cannot be null");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                peekServiceLocked = this.mServices.peekServiceLocked(service, resolvedType, callingPackage);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return peekServiceLocked;
    }

    public boolean stopServiceToken(ComponentName className, IBinder token, int startId) {
        boolean stopServiceTokenLocked;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                stopServiceTokenLocked = this.mServices.stopServiceTokenLocked(className, token, startId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return stopServiceTokenLocked;
    }

    public void setServiceForeground(ComponentName className, IBinder token, int id, Notification notification, int flags, int foregroundServiceType) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mServices.setServiceForegroundLocked(className, token, id, notification, flags, foregroundServiceType);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public int getForegroundServiceType(ComponentName className, IBinder token) {
        int foregroundServiceTypeLocked;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                foregroundServiceTypeLocked = this.mServices.getForegroundServiceTypeLocked(className, token);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return foregroundServiceTypeLocked;
    }

    public int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll, boolean requireFull, String name, String callerPackage) {
        return this.mUserController.handleIncomingUser(callingPid, callingUid, userId, allowAll, requireFull ? 2 : 0, name, callerPackage);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSingleton(String componentProcessName, ApplicationInfo aInfo, String className, int flags) {
        boolean result = false;
        if (UserHandle.getAppId(aInfo.uid) >= 10000) {
            if ((flags & 1073741824) != 0) {
                if (ActivityManager.checkUidPermission("android.permission.INTERACT_ACROSS_USERS", aInfo.uid) != 0) {
                    ComponentName comp = new ComponentName(aInfo.packageName, className);
                    String msg = "Permission Denial: Component " + comp.flattenToShortString() + " requests FLAG_SINGLE_USER, but app does not hold android.permission.INTERACT_ACROSS_USERS";
                    Slog.w("ActivityManager", msg);
                    throw new SecurityException(msg);
                }
                result = true;
            }
        } else if ("system".equals(componentProcessName)) {
            result = true;
        } else if ((flags & 1073741824) != 0) {
            result = UserHandle.isSameApp(aInfo.uid, NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE) || (aInfo.flags & 8) != 0;
        }
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.v(TAG_MU, "isSingleton(" + componentProcessName + ", " + aInfo + ", " + className + ", 0x" + Integer.toHexString(flags) + ") = " + result);
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isValidSingletonCall(int callingUid, int componentUid) {
        int componentAppId = UserHandle.getAppId(componentUid);
        return UserHandle.isSameApp(callingUid, componentUid) || componentAppId == 1000 || componentAppId == 1001 || ActivityManager.checkUidPermission("android.permission.INTERACT_ACROSS_USERS_FULL", componentUid) == 0;
    }

    public int bindService(IApplicationThread caller, IBinder token, Intent service, String resolvedType, IServiceConnection connection, int flags, String callingPackage, int userId) throws TransactionTooLargeException {
        return bindIsolatedService(caller, token, service, resolvedType, connection, flags, null, callingPackage, userId);
    }

    public int bindIsolatedService(IApplicationThread caller, IBinder token, Intent service, String resolvedType, IServiceConnection connection, int flags, String instanceName, String callingPackage, int userId) throws TransactionTooLargeException {
        int bindServiceLocked;
        enforceNotIsolatedCaller("bindService");
        if (service != null && service.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        if (callingPackage == null) {
            throw new IllegalArgumentException("callingPackage cannot be null");
        }
        if (instanceName != null) {
            for (int i = 0; i < instanceName.length(); i++) {
                char c = instanceName.charAt(i);
                if ((c < 'a' || c > 'z') && ((c < 'A' || c > 'Z') && ((c < '0' || c > CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG) && c != '_' && c != '.'))) {
                    throw new IllegalArgumentException("Illegal instanceName");
                }
            }
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                bindServiceLocked = this.mServices.bindServiceLocked(caller, token, service, resolvedType, connection, flags, instanceName, callingPackage, userId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return bindServiceLocked;
    }

    public void updateServiceGroup(IServiceConnection connection, int group, int importance) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mServices.updateServiceGroupLocked(connection, group, importance);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean unbindService(IServiceConnection connection) {
        boolean unbindServiceLocked;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                unbindServiceLocked = this.mServices.unbindServiceLocked(connection);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return unbindServiceLocked;
    }

    public void publishService(IBinder token, Intent intent, IBinder service) {
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (!(token instanceof ServiceRecord)) {
                    throw new IllegalArgumentException("Invalid service token");
                }
                this.mServices.publishServiceLocked((ServiceRecord) token, intent, service);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void unbindFinished(IBinder token, Intent intent, boolean doRebind) {
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mServices.unbindFinishedLocked((ServiceRecord) token, intent, doRebind);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void serviceDoneExecuting(IBinder token, int type, int startId, int res) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (!(token instanceof ServiceRecord)) {
                    Slog.e("ActivityManager", "serviceDoneExecuting: Invalid service token=" + token);
                    throw new IllegalArgumentException("Invalid service token");
                }
                this.mServices.serviceDoneExecutingLocked((ServiceRecord) token, type, startId, res);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean bindBackupAgent(String packageName, int backupMode, int targetUserId) {
        ApplicationInfo app;
        if (ActivityManagerDebugConfig.DEBUG_BACKUP) {
            Slog.v("ActivityManager", "bindBackupAgent: app=" + packageName + " mode=" + backupMode + " targetUserId=" + targetUserId + " callingUid = " + Binder.getCallingUid() + " uid = " + Process.myUid());
        }
        enforceCallingPermission("android.permission.CONFIRM_FULL_BACKUP", "bindBackupAgent");
        int instantiatedUserId = PackageManagerService.PLATFORM_PACKAGE_NAME.equals(packageName) ? 0 : targetUserId;
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            ApplicationInfo app2 = pm.getApplicationInfo(packageName, 1024, instantiatedUserId);
            app = app2;
        } catch (RemoteException e) {
            app = null;
        }
        if (app == null) {
            Slog.w("ActivityManager", "Unable to bind backup agent for " + packageName);
            return false;
        }
        synchronized (this) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                try {
                    boostPriorityForLockedSection();
                    AppGlobals.getPackageManager().setPackageStoppedState(app.packageName, false, UserHandle.getUserId(app.uid));
                } catch (RemoteException e2) {
                } catch (IllegalArgumentException e3) {
                    Slog.w("ActivityManager", "Failed trying to unstop package " + app.packageName + ": " + e3);
                }
                try {
                    BackupRecord r = new BackupRecord(app, backupMode, targetUserId);
                    ComponentName hostingName = backupMode == 0 ? new ComponentName(app.packageName, app.backupAgentName) : new ComponentName(PackageManagerService.PLATFORM_PACKAGE_NAME, "FullBackupAgent");
                    ApplicationInfo app3 = app;
                    try {
                        ProcessRecord proc = startProcessLocked(app.processName, app, false, 0, new HostingRecord(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD, hostingName), false, false, false);
                        if (proc == null) {
                            try {
                                Slog.e("ActivityManager", "Unable to start backup agent process " + r);
                                resetPriorityAfterLockedSection();
                                return false;
                            } catch (Throwable th2) {
                                th = th2;
                                resetPriorityAfterLockedSection();
                                throw th;
                            }
                        }
                        if (UserHandle.isApp(app3.uid) && backupMode == 1) {
                            proc.inFullBackup = true;
                        }
                        r.app = proc;
                        BackupRecord backupTarget = this.mBackupTargets.get(targetUserId);
                        int oldBackupUid = backupTarget != null ? backupTarget.appInfo.uid : -1;
                        int newBackupUid = proc.inFullBackup ? r.appInfo.uid : -1;
                        this.mBackupTargets.put(targetUserId, r);
                        updateOomAdjLocked(proc, true, "updateOomAdj_meh");
                        if (proc.thread != null) {
                            if (ActivityManagerDebugConfig.DEBUG_BACKUP) {
                                Slog.v("ActivityManager", "Agent proc already running: " + proc);
                            }
                            try {
                                proc.thread.scheduleCreateBackupAgent(app3, compatibilityInfoForPackage(app3), backupMode, targetUserId);
                            } catch (RemoteException e4) {
                            }
                        } else if (ActivityManagerDebugConfig.DEBUG_BACKUP) {
                            Slog.v("ActivityManager", "Agent proc not running, waiting for attach");
                        }
                        resetPriorityAfterLockedSection();
                        JobSchedulerInternal js = (JobSchedulerInternal) LocalServices.getService(JobSchedulerInternal.class);
                        if (oldBackupUid != -1) {
                            js.removeBackingUpUid(oldBackupUid);
                        }
                        if (newBackupUid != -1) {
                            js.addBackingUpUid(newBackupUid);
                        }
                        return true;
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } catch (Throwable th4) {
                    th = th4;
                }
            } catch (Throwable th5) {
                th = th5;
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearPendingBackup(int userId) {
        if (ActivityManagerDebugConfig.DEBUG_BACKUP) {
            Slog.v("ActivityManager", "clearPendingBackup: userId = " + userId + " callingUid = " + Binder.getCallingUid() + " uid = " + Process.myUid());
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mBackupTargets.delete(userId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        JobSchedulerInternal js = (JobSchedulerInternal) LocalServices.getService(JobSchedulerInternal.class);
        js.clearAllBackingUpUids();
    }

    public void backupAgentCreated(String agentPackageName, IBinder agent, int userId) {
        int userId2 = this.mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, 2, "backupAgentCreated", null);
        if (ActivityManagerDebugConfig.DEBUG_BACKUP) {
            Slog.v("ActivityManager", "backupAgentCreated: " + agentPackageName + " = " + agent + " callingUserId = " + UserHandle.getCallingUserId() + " userId = " + userId2 + " callingUid = " + Binder.getCallingUid() + " uid = " + Process.myUid());
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                BackupRecord backupTarget = this.mBackupTargets.get(userId2);
                String backupAppName = backupTarget == null ? null : backupTarget.appInfo.packageName;
                if (!agentPackageName.equals(backupAppName)) {
                    Slog.e("ActivityManager", "Backup agent created for " + agentPackageName + " but not requested!");
                    resetPriorityAfterLockedSection();
                    return;
                }
                resetPriorityAfterLockedSection();
                long oldIdent = Binder.clearCallingIdentity();
                try {
                    try {
                        IBackupManager bm = IBackupManager.Stub.asInterface(ServiceManager.getService(BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD));
                        bm.agentConnectedForUser(userId2, agentPackageName, agent);
                    } catch (RemoteException e) {
                    } catch (Exception e2) {
                        Slog.w("ActivityManager", "Exception trying to deliver BackupAgent binding: ");
                        e2.printStackTrace();
                    }
                } finally {
                    Binder.restoreCallingIdentity(oldIdent);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void unbindBackupAgent(ApplicationInfo appInfo) {
        if (ActivityManagerDebugConfig.DEBUG_BACKUP) {
            Slog.v("ActivityManager", "unbindBackupAgent: " + appInfo + " appInfo.uid = " + appInfo.uid + " callingUid = " + Binder.getCallingUid() + " uid = " + Process.myUid());
        }
        enforceCallingPermission("android.permission.CONFIRM_FULL_BACKUP", "unbindBackupAgent");
        if (appInfo == null) {
            Slog.w("ActivityManager", "unbind backup agent for null app");
            return;
        }
        int userId = UserHandle.getUserId(appInfo.uid);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                BackupRecord backupTarget = this.mBackupTargets.get(userId);
                String backupAppName = backupTarget == null ? null : backupTarget.appInfo.packageName;
                if (backupAppName == null) {
                    Slog.w("ActivityManager", "Unbinding backup agent with no active backup");
                    this.mBackupTargets.delete(userId);
                    resetPriorityAfterLockedSection();
                } else if (!backupAppName.equals(appInfo.packageName)) {
                    Slog.e("ActivityManager", "Unbind of " + appInfo + " but is not the current backup target");
                    this.mBackupTargets.delete(userId);
                    resetPriorityAfterLockedSection();
                } else {
                    ProcessRecord proc = backupTarget.app;
                    updateOomAdjLocked(proc, true, "updateOomAdj_meh");
                    proc.inFullBackup = false;
                    int oldBackupUid = backupTarget.appInfo.uid;
                    if (proc.thread != null) {
                        try {
                            proc.thread.scheduleDestroyBackupAgent(appInfo, compatibilityInfoForPackage(appInfo), userId);
                        } catch (Exception e) {
                            Slog.e("ActivityManager", "Exception when unbinding backup agent:");
                            e.printStackTrace();
                        }
                    }
                    this.mBackupTargets.delete(userId);
                    resetPriorityAfterLockedSection();
                    if (oldBackupUid != -1) {
                        JobSchedulerInternal js = (JobSchedulerInternal) LocalServices.getService(JobSchedulerInternal.class);
                        js.removeBackingUpUid(oldBackupUid);
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private boolean isInstantApp(ProcessRecord record, String callerPackage, int uid) {
        if (UserHandle.getAppId(uid) < 10000) {
            return false;
        }
        if (record != null) {
            return record.info.isInstantApp();
        }
        IPackageManager pm = AppGlobals.getPackageManager();
        if (callerPackage == null) {
            try {
                String[] packageNames = pm.getPackagesForUid(uid);
                if (packageNames == null || packageNames.length == 0) {
                    throw new IllegalArgumentException("Unable to determine caller package name");
                }
                callerPackage = packageNames[0];
            } catch (RemoteException e) {
                Slog.e("ActivityManager", "Error looking up if " + callerPackage + " is an instant app.", e);
                return true;
            }
        }
        this.mAppOpsService.checkPackage(uid, callerPackage);
        return pm.isInstantApp(callerPackage, UserHandle.getUserId(uid));
    }

    boolean isPendingBroadcastProcessLocked(int pid) {
        return this.mFgBroadcastQueue.isPendingBroadcastProcessLocked(pid) || this.mBgBroadcastQueue.isPendingBroadcastProcessLocked(pid) || this.mOffloadBroadcastQueue.isPendingBroadcastProcessLocked(pid);
    }

    void skipPendingBroadcastLocked(int pid) {
        BroadcastQueue[] broadcastQueueArr;
        Slog.w("ActivityManager", "Unattached app died before broadcast acknowledged, skipping");
        for (BroadcastQueue queue : this.mBroadcastQueues) {
            queue.skipPendingBroadcastLocked(pid);
        }
    }

    boolean sendPendingBroadcastsLocked(ProcessRecord app) {
        BroadcastQueue[] broadcastQueueArr;
        boolean didSomething = false;
        for (BroadcastQueue queue : this.mBroadcastQueues) {
            didSomething |= queue.sendPendingBroadcastsLocked(app);
        }
        return didSomething;
    }

    public Intent registerReceiver(IApplicationThread caller, String callerPackage, IIntentReceiver receiver, IntentFilter filter, String permission, int userId, int flags) {
        String callerPackage2;
        ProcessRecord callerApp;
        int callingUid;
        int callingPid;
        ArrayList<Intent> stickyIntents;
        ArrayList<Intent> allSticky;
        int callingUid2;
        int userId2;
        ArrayList<Intent> stickyIntents2;
        String action;
        int i;
        Iterator<String> actions;
        ArrayList<Intent> stickyIntents3;
        enforceNotIsolatedCaller("registerReceiver");
        ArrayList<Intent> stickyIntents4 = null;
        int i2 = 0;
        boolean visibleToInstantApps = (flags & 1) != 0;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (caller != null) {
                    try {
                        ProcessRecord callerApp2 = getRecordForAppLocked(caller);
                        if (callerApp2 == null) {
                            throw new SecurityException("Unable to find app for caller " + caller + " (pid=" + Binder.getCallingPid() + ") when registering receiver " + receiver);
                        }
                        if (callerApp2.info.uid != 1000 && !callerApp2.pkgList.containsKey(callerPackage) && !PackageManagerService.PLATFORM_PACKAGE_NAME.equals(callerPackage)) {
                            throw new SecurityException("Given caller package " + callerPackage + " is not running in process " + callerApp2);
                        }
                        int callingUid3 = callerApp2.info.uid;
                        callerPackage2 = callerPackage;
                        callerApp = callerApp2;
                        callingUid = callingUid3;
                        callingPid = callerApp2.pid;
                    } catch (Throwable th) {
                        th = th;
                        while (true) {
                            try {
                                break;
                            } catch (Throwable th2) {
                                th = th2;
                            }
                        }
                        resetPriorityAfterLockedSection();
                        throw th;
                    }
                } else {
                    try {
                        int callingUid4 = Binder.getCallingUid();
                        callerPackage2 = null;
                        callerApp = null;
                        callingUid = callingUid4;
                        callingPid = Binder.getCallingPid();
                    } catch (Throwable th3) {
                        th = th3;
                        while (true) {
                            break;
                            break;
                        }
                        resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                try {
                    boolean instantApp = isInstantApp(callerApp, callerPackage2, callingUid);
                    int userId3 = this.mUserController.handleIncomingUser(callingPid, callingUid, userId, true, 2, "registerReceiver", callerPackage2);
                    try {
                        Iterator<String> actions2 = filter.actionsIterator();
                        if (actions2 == null) {
                            try {
                                ArrayList<String> noAction = new ArrayList<>(1);
                                noAction.add(null);
                                actions2 = noAction.iterator();
                            } catch (Throwable th4) {
                                th = th4;
                                while (true) {
                                    break;
                                    break;
                                }
                                resetPriorityAfterLockedSection();
                                throw th;
                            }
                        }
                        int[] userIds = {-1, UserHandle.getUserId(callingUid)};
                        while (actions2.hasNext()) {
                            try {
                                String action2 = actions2.next();
                                int length = userIds.length;
                                ArrayList<Intent> stickyIntents5 = stickyIntents4;
                                int i3 = i2;
                                while (i3 < length) {
                                    try {
                                        int id = userIds[i3];
                                        ArrayMap<String, ArrayList<Intent>> stickies = this.mStickyBroadcasts.get(id);
                                        if (stickies != null) {
                                            action = action2;
                                            ArrayList<Intent> intents = stickies.get(action);
                                            i = length;
                                            if (intents != null) {
                                                if (stickyIntents5 == null) {
                                                    ArrayList<Intent> stickyIntents6 = new ArrayList<>();
                                                    actions = actions2;
                                                    stickyIntents3 = stickyIntents6;
                                                } else {
                                                    actions = actions2;
                                                    stickyIntents3 = stickyIntents5;
                                                }
                                                try {
                                                    stickyIntents3.addAll(intents);
                                                    stickyIntents5 = stickyIntents3;
                                                } catch (Throwable th5) {
                                                    th = th5;
                                                    while (true) {
                                                        break;
                                                        break;
                                                    }
                                                    resetPriorityAfterLockedSection();
                                                    throw th;
                                                }
                                            } else {
                                                actions = actions2;
                                            }
                                        } else {
                                            action = action2;
                                            i = length;
                                            actions = actions2;
                                        }
                                        i3++;
                                        actions2 = actions;
                                        action2 = action;
                                        length = i;
                                    } catch (Throwable th6) {
                                        th = th6;
                                    }
                                }
                                stickyIntents4 = stickyIntents5;
                                i2 = 0;
                            } catch (Throwable th7) {
                                th = th7;
                            }
                        }
                        resetPriorityAfterLockedSection();
                        ArrayList<Intent> allSticky2 = null;
                        if (stickyIntents4 != null) {
                            ContentResolver resolver = this.mContext.getContentResolver();
                            int i4 = 0;
                            int N = stickyIntents4.size();
                            while (i4 < N) {
                                Intent intent = stickyIntents4.get(i4);
                                if (instantApp && (intent.getFlags() & DumpState.DUMP_COMPILER_STATS) == 0) {
                                    stickyIntents2 = stickyIntents4;
                                } else {
                                    stickyIntents2 = stickyIntents4;
                                    if (filter.match(resolver, intent, true, "ActivityManager") >= 0) {
                                        if (allSticky2 == null) {
                                            allSticky2 = new ArrayList<>();
                                        }
                                        allSticky2.add(intent);
                                    }
                                }
                                i4++;
                                stickyIntents4 = stickyIntents2;
                            }
                            stickyIntents = stickyIntents4;
                            allSticky = allSticky2;
                        } else {
                            stickyIntents = stickyIntents4;
                            allSticky = null;
                        }
                        Intent sticky = allSticky != null ? allSticky.get(0) : null;
                        if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                            Slog.v("ActivityManager", "Register receiver " + filter + ": " + sticky);
                        }
                        if (receiver == null) {
                            return sticky;
                        }
                        synchronized (this) {
                            try {
                                try {
                                    boostPriorityForLockedSection();
                                    if (callerApp != null) {
                                        try {
                                            if (callerApp.thread == null || callerApp.thread.asBinder() != caller.asBinder()) {
                                                resetPriorityAfterLockedSection();
                                                return null;
                                            }
                                        } catch (Throwable th8) {
                                            e = th8;
                                            resetPriorityAfterLockedSection();
                                            throw e;
                                        }
                                    }
                                    ReceiverList rl = this.mRegisteredReceivers.get(receiver.asBinder());
                                    if (rl == null) {
                                        try {
                                            int userId4 = callingUid;
                                            callingUid2 = callingUid;
                                            try {
                                                ReceiverList rl2 = new ReceiverList(this, callerApp, callingPid, userId4, userId3, receiver);
                                                if (rl2.app != null) {
                                                    int totalReceiversForApp = rl2.app.receivers.size();
                                                    if (totalReceiversForApp >= 1000) {
                                                        throw new IllegalStateException("Too many receivers, total of " + totalReceiversForApp + ", registered for pid: " + rl2.pid + ", callerPackage: " + callerPackage2);
                                                    }
                                                    rl2.app.receivers.add(rl2);
                                                } else {
                                                    try {
                                                        receiver.asBinder().linkToDeath(rl2, 0);
                                                        rl2.linkedToDeath = true;
                                                    } catch (RemoteException e) {
                                                        resetPriorityAfterLockedSection();
                                                        return sticky;
                                                    }
                                                }
                                                this.mRegisteredReceivers.put(receiver.asBinder(), rl2);
                                                userId2 = userId3;
                                                rl = rl2;
                                            } catch (Throwable th9) {
                                                e = th9;
                                                resetPriorityAfterLockedSection();
                                                throw e;
                                            }
                                        } catch (Throwable th10) {
                                            e = th10;
                                        }
                                    } else {
                                        int callingPid2 = callingPid;
                                        callingUid2 = callingUid;
                                        try {
                                            if (rl.uid != callingUid2) {
                                                throw new IllegalArgumentException("Receiver requested to register for uid " + callingUid2 + " was previously registered for uid " + rl.uid + " callerPackage is " + callerPackage2);
                                            } else if (rl.pid != callingPid2) {
                                                throw new IllegalArgumentException("Receiver requested to register for pid " + callingPid2 + " was previously registered for pid " + rl.pid + " callerPackage is " + callerPackage2);
                                            } else {
                                                userId2 = userId3;
                                                if (rl.userId != userId2) {
                                                    throw new IllegalArgumentException("Receiver requested to register for user " + userId2 + " was previously registered for user " + rl.userId + " callerPackage is " + callerPackage2);
                                                }
                                            }
                                        } catch (Throwable th11) {
                                            e = th11;
                                            resetPriorityAfterLockedSection();
                                            throw e;
                                        }
                                    }
                                    try {
                                        ArrayList<Intent> allSticky3 = allSticky;
                                        String callerPackage3 = callerPackage2;
                                        BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage3, permission, callingUid2, userId2, instantApp, visibleToInstantApps);
                                        if (rl.containsFilter(filter)) {
                                            Slog.w("ActivityManager", "Receiver with filter " + filter + " already registered for pid " + rl.pid + ", callerPackage is " + callerPackage3);
                                        } else {
                                            rl.add(bf);
                                            if (!bf.debugCheck()) {
                                                Slog.w("ActivityManager", "==> For Dynamic broadcast");
                                            }
                                            this.mReceiverResolver.addFilter(bf);
                                        }
                                        if (allSticky3 != null) {
                                            ArrayList receivers = new ArrayList();
                                            receivers.add(bf);
                                            int stickyCount = allSticky3.size();
                                            int i5 = 0;
                                            while (i5 < stickyCount) {
                                                Intent intent2 = allSticky3.get(i5);
                                                BroadcastQueue queue = broadcastQueueForIntent(intent2);
                                                BroadcastRecord r = new BroadcastRecord(queue, intent2, null, null, -1, -1, false, null, null, -1, null, receivers, null, 0, null, null, false, true, true, -1, false, false);
                                                queue.enqueueParallelBroadcastLocked(r);
                                                queue.scheduleBroadcastsLocked();
                                                i5++;
                                                rl = rl;
                                            }
                                        }
                                        resetPriorityAfterLockedSection();
                                        return sticky;
                                    } catch (Throwable th12) {
                                        e = th12;
                                        resetPriorityAfterLockedSection();
                                        throw e;
                                    }
                                } catch (Throwable th13) {
                                    e = th13;
                                }
                            } catch (Throwable th14) {
                                e = th14;
                            }
                        }
                    } catch (Throwable th15) {
                        th = th15;
                    }
                } catch (Throwable th16) {
                    th = th16;
                }
            } catch (Throwable th17) {
                th = th17;
            }
        }
    }

    public void unregisterReceiver(IIntentReceiver receiver) {
        if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
            Slog.v("ActivityManager", "Unregister receiver: " + receiver);
        }
        long origId = Binder.clearCallingIdentity();
        boolean doTrim = false;
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                ReceiverList rl = this.mRegisteredReceivers.get(receiver.asBinder());
                if (rl != null) {
                    BroadcastRecord r = rl.curBroadcast;
                    if (r != null && r == r.queue.getMatchingOrderedReceiver(r)) {
                        boolean doNext = r.queue.finishReceiverLocked(r, r.resultCode, r.resultData, r.resultExtras, r.resultAbort, false);
                        if (doNext) {
                            doTrim = true;
                            r.queue.processNextBroadcast(false);
                        }
                    }
                    if (rl.app != null) {
                        rl.app.receivers.remove(rl);
                    }
                    removeReceiverLocked(rl);
                    if (rl.linkedToDeath) {
                        rl.linkedToDeath = false;
                        rl.receiver.asBinder().unlinkToDeath(rl, 0);
                    }
                }
            }
            resetPriorityAfterLockedSection();
            if (doTrim) {
                trimApplications("updateOomAdj_finishReceiver");
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void removeReceiverLocked(ReceiverList rl) {
        this.mRegisteredReceivers.remove(rl.receiver.asBinder());
        for (int i = rl.size() - 1; i >= 0; i--) {
            this.mReceiverResolver.removeFilter(rl.get(i));
        }
    }

    private final void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        this.mProcessList.sendPackageBroadcastLocked(cmd, packages, userId);
    }

    private List<ResolveInfo> collectReceiverComponents(Intent intent, String resolvedType, int callingUid, int[] users) {
        int pmFlags;
        int pmFlags2;
        int[] iArr = users;
        int pmFlags3 = 268436480;
        boolean scannedFirstReceivers = false;
        try {
            int length = iArr.length;
            int i = 0;
            List<ResolveInfo> receivers = null;
            HashSet<ComponentName> singleUserReceivers = null;
            int i2 = 0;
            while (i2 < length) {
                try {
                    int user = iArr[i2];
                    try {
                        try {
                            if (callingUid == NATIVE_DUMP_TIMEOUT_MS) {
                                try {
                                    if (this.mUserController.hasUserRestriction("no_debugging_features", user) && !isPermittedShellBroadcast(intent)) {
                                        pmFlags = pmFlags3;
                                        i2++;
                                        iArr = users;
                                        pmFlags3 = pmFlags;
                                        i = 0;
                                    }
                                } catch (RemoteException e) {
                                    return receivers;
                                }
                            }
                            List<ResolveInfo> newReceivers = AppGlobals.getPackageManager().queryIntentReceivers(intent, resolvedType, pmFlags3, user).getList();
                            if (user != 0 && newReceivers != null) {
                                int i3 = i;
                                while (i3 < newReceivers.size()) {
                                    try {
                                        if ((newReceivers.get(i3).activityInfo.flags & 536870912) != 0) {
                                            newReceivers.remove(i3);
                                            i3--;
                                        }
                                        i3++;
                                    } catch (RemoteException e2) {
                                        return receivers;
                                    }
                                }
                            }
                            if (newReceivers != null && newReceivers.size() == 0) {
                                newReceivers = null;
                            }
                            if (receivers == null) {
                                pmFlags = pmFlags3;
                                receivers = newReceivers;
                            } else if (newReceivers != null) {
                                if (scannedFirstReceivers) {
                                    pmFlags = pmFlags3;
                                } else {
                                    scannedFirstReceivers = true;
                                    int i4 = 0;
                                    while (i4 < receivers.size()) {
                                        ResolveInfo ri = receivers.get(i4);
                                        if ((ri.activityInfo.flags & 1073741824) != 0) {
                                            pmFlags2 = pmFlags3;
                                            try {
                                                ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                                                if (singleUserReceivers == null) {
                                                    singleUserReceivers = new HashSet<>();
                                                }
                                                singleUserReceivers.add(cn);
                                            } catch (RemoteException e3) {
                                                return receivers;
                                            }
                                        } else {
                                            pmFlags2 = pmFlags3;
                                        }
                                        i4++;
                                        pmFlags3 = pmFlags2;
                                    }
                                    pmFlags = pmFlags3;
                                }
                                for (int i5 = 0; i5 < newReceivers.size(); i5++) {
                                    ResolveInfo ri2 = newReceivers.get(i5);
                                    if ((ri2.activityInfo.flags & 1073741824) != 0) {
                                        ComponentName cn2 = new ComponentName(ri2.activityInfo.packageName, ri2.activityInfo.name);
                                        if (singleUserReceivers == null) {
                                            singleUserReceivers = new HashSet<>();
                                        }
                                        if (!singleUserReceivers.contains(cn2)) {
                                            singleUserReceivers.add(cn2);
                                            receivers.add(ri2);
                                        }
                                    } else {
                                        receivers.add(ri2);
                                    }
                                }
                            } else {
                                pmFlags = pmFlags3;
                            }
                            i2++;
                            iArr = users;
                            pmFlags3 = pmFlags;
                            i = 0;
                        } catch (RemoteException e4) {
                            return receivers;
                        }
                    } catch (RemoteException e5) {
                        return receivers;
                    }
                } catch (RemoteException e6) {
                    return receivers;
                }
            }
            return receivers;
        } catch (RemoteException e7) {
            return null;
        }
    }

    private boolean isPermittedShellBroadcast(Intent intent) {
        return INTENT_REMOTE_BUGREPORT_FINISHED.equals(intent.getAction());
    }

    private void checkBroadcastFromSystem(Intent intent, ProcessRecord callerApp, String callerPackage, int callingUid, boolean isProtectedBroadcast, List receivers) {
        if ((intent.getFlags() & DumpState.DUMP_CHANGES) != 0) {
            return;
        }
        String action = intent.getAction();
        if (isProtectedBroadcast || "android.intent.action.CLOSE_SYSTEM_DIALOGS".equals(action) || "com.android.intent.action.DISMISS_KEYBOARD_SHORTCUTS".equals(action) || "android.intent.action.MEDIA_BUTTON".equals(action) || "android.intent.action.MEDIA_SCANNER_SCAN_FILE".equals(action) || "com.android.intent.action.SHOW_KEYBOARD_SHORTCUTS".equals(action) || "android.intent.action.MASTER_CLEAR".equals(action) || "android.intent.action.FACTORY_RESET".equals(action) || "android.appwidget.action.APPWIDGET_CONFIGURE".equals(action) || "android.appwidget.action.APPWIDGET_UPDATE".equals(action) || "android.location.HIGH_POWER_REQUEST_CHANGE".equals(action) || "com.android.omadm.service.CONFIGURATION_UPDATE".equals(action) || "android.text.style.SUGGESTION_PICKED".equals(action) || "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION".equals(action) || "android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION".equals(action)) {
            return;
        }
        if (intent.getPackage() != null || intent.getComponent() != null) {
            if (receivers == null || receivers.size() == 0) {
                return;
            }
            boolean allProtected = true;
            int i = receivers.size() - 1;
            while (true) {
                if (i < 0) {
                    break;
                }
                Object target = receivers.get(i);
                if (target instanceof ResolveInfo) {
                    ResolveInfo ri = (ResolveInfo) target;
                    if (ri.activityInfo.exported && ri.activityInfo.permission == null) {
                        allProtected = false;
                        break;
                    }
                    i--;
                } else {
                    BroadcastFilter bf = (BroadcastFilter) target;
                    if (bf.requiredPermission != null) {
                        i--;
                    } else {
                        allProtected = false;
                        break;
                    }
                }
            }
            if (allProtected) {
                return;
            }
        }
        if (!PackageManagerService.PLATFORM_PACKAGE_NAME.equals(callerPackage)) {
            if (!Build.IS_USER) {
                Log.w("ActivityManager", "Sending non-protected broadcast " + action + "pkg " + callerPackage);
            }
        } else if (callerApp != null) {
            Log.d("ActivityManager", "Sending non-protected broadcast " + action + " from system " + callerApp.toShortString() + " pkg " + callerPackage, new Throwable());
        } else {
            Log.d("ActivityManager", "Sending non-protected broadcast " + action + " from system uid " + UserHandle.formatUid(callingUid) + " pkg " + callerPackage, new Throwable());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final int broadcastIntentLocked(ProcessRecord callerApp, String callerPackage, Intent intent, String resolvedType, IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered, boolean sticky, int callingPid, int callingUid, int realCallingUid, int realCallingPid, int userId) {
        return broadcastIntentLocked(callerApp, callerPackage, intent, resolvedType, resultTo, resultCode, resultData, resultExtras, requiredPermissions, appOp, bOptions, ordered, sticky, callingPid, callingUid, realCallingUid, realCallingPid, userId, false);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:263:0x0682, code lost:
        if (r4.equals("android.intent.action.PACKAGES_SUSPENDED") != false) goto L404;
     */
    /* JADX WARN: Removed duplicated region for block: B:106:0x030b  */
    /* JADX WARN: Removed duplicated region for block: B:344:0x092f  */
    /* JADX WARN: Removed duplicated region for block: B:346:0x093d  */
    /* JADX WARN: Removed duplicated region for block: B:386:0x0a5c  */
    /* JADX WARN: Removed duplicated region for block: B:388:0x0a61  */
    /* JADX WARN: Removed duplicated region for block: B:389:0x0a69  */
    /* JADX WARN: Removed duplicated region for block: B:392:0x0a79  */
    /* JADX WARN: Removed duplicated region for block: B:393:0x0a80  */
    /* JADX WARN: Removed duplicated region for block: B:396:0x0a88  */
    /* JADX WARN: Removed duplicated region for block: B:413:0x0ac3  */
    /* JADX WARN: Removed duplicated region for block: B:416:0x0acf  */
    /* JADX WARN: Removed duplicated region for block: B:417:0x0ad1  */
    /* JADX WARN: Removed duplicated region for block: B:420:0x0ad7  */
    /* JADX WARN: Removed duplicated region for block: B:422:0x0afb  */
    /* JADX WARN: Removed duplicated region for block: B:423:0x0b00  */
    /* JADX WARN: Removed duplicated region for block: B:444:0x0b97  */
    /* JADX WARN: Removed duplicated region for block: B:486:0x0c4c  */
    /* JADX WARN: Removed duplicated region for block: B:488:0x0c51  */
    /* JADX WARN: Removed duplicated region for block: B:493:0x0c6b  */
    /* JADX WARN: Removed duplicated region for block: B:86:0x0258  */
    @com.android.internal.annotations.GuardedBy({"this"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    final int broadcastIntentLocked(com.android.server.am.ProcessRecord r51, java.lang.String r52, android.content.Intent r53, java.lang.String r54, android.content.IIntentReceiver r55, int r56, java.lang.String r57, android.os.Bundle r58, java.lang.String[] r59, int r60, android.os.Bundle r61, boolean r62, boolean r63, int r64, int r65, int r66, int r67, int r68, boolean r69) {
        /*
            Method dump skipped, instructions count: 3612
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.broadcastIntentLocked(com.android.server.am.ProcessRecord, java.lang.String, android.content.Intent, java.lang.String, android.content.IIntentReceiver, int, java.lang.String, android.os.Bundle, java.lang.String[], int, android.os.Bundle, boolean, boolean, int, int, int, int, int, boolean):int");
    }

    private int getUidFromIntent(Intent intent) {
        if (intent == null) {
            return -1;
        }
        Bundle intentExtras = intent.getExtras();
        if (!intent.hasExtra("android.intent.extra.UID")) {
            return -1;
        }
        return intentExtras.getInt("android.intent.extra.UID");
    }

    final void rotateBroadcastStatsIfNeededLocked() {
        long now = SystemClock.elapsedRealtime();
        BroadcastStats broadcastStats = this.mCurBroadcastStats;
        if (broadcastStats == null || broadcastStats.mStartRealtime + 86400000 < now) {
            this.mLastBroadcastStats = this.mCurBroadcastStats;
            BroadcastStats broadcastStats2 = this.mLastBroadcastStats;
            if (broadcastStats2 != null) {
                broadcastStats2.mEndRealtime = SystemClock.elapsedRealtime();
                this.mLastBroadcastStats.mEndUptime = SystemClock.uptimeMillis();
            }
            this.mCurBroadcastStats = new BroadcastStats();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void addBroadcastStatLocked(String action, String srcPackage, int receiveCount, int skipCount, long dispatchTime) {
        rotateBroadcastStatsIfNeededLocked();
        this.mCurBroadcastStats.addBroadcast(action, srcPackage, receiveCount, skipCount, dispatchTime);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void addBackgroundCheckViolationLocked(String action, String targetPackage) {
        rotateBroadcastStatsIfNeededLocked();
        this.mCurBroadcastStats.addBackgroundCheckViolation(action, targetPackage);
    }

    final Intent verifyBroadcastLocked(Intent intent) {
        int callingUid;
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        int flags = intent.getFlags();
        if (!this.mProcessesReady && (67108864 & flags) == 0 && (1073741824 & flags) == 0) {
            Slog.e("ActivityManager", "Attempt to launch receivers of broadcast intent " + intent + " before boot completion");
            throw new IllegalStateException("Cannot broadcast before boot completed");
        } else if ((33554432 & flags) != 0) {
            throw new IllegalArgumentException("Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
        } else {
            if ((flags & DumpState.DUMP_CHANGES) != 0 && (callingUid = Binder.getCallingUid()) != 0 && callingUid != 1000 && callingUid != NATIVE_DUMP_TIMEOUT_MS) {
                Slog.w("ActivityManager", "Removing FLAG_RECEIVER_FROM_SHELL because caller is UID " + Binder.getCallingUid());
                intent.removeFlags(DumpState.DUMP_CHANGES);
            }
            return intent;
        }
    }

    public final int broadcastIntent(IApplicationThread caller, Intent intent, String resolvedType, IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions, boolean serialized, boolean sticky, int userId) {
        Intent intent2;
        String str;
        enforceNotIsolatedCaller("broadcastIntent");
        synchronized (this) {
            try {
                try {
                    boostPriorityForLockedSection();
                    intent2 = intent;
                } catch (Throwable th) {
                    th = th;
                    intent2 = intent;
                }
            } catch (Throwable th2) {
                th = th2;
            }
            try {
                Intent intent3 = verifyBroadcastLocked(intent2);
                ProcessRecord callerApp = getRecordForAppLocked(caller);
                int callingPid = Binder.getCallingPid();
                int callingUid = Binder.getCallingUid();
                long origId = Binder.clearCallingIdentity();
                if (callerApp == null) {
                    str = null;
                } else {
                    try {
                        str = callerApp.info.packageName;
                    } catch (Throwable th3) {
                        th = th3;
                        Binder.restoreCallingIdentity(origId);
                        throw th;
                    }
                }
                try {
                    int broadcastIntentLocked = broadcastIntentLocked(callerApp, str, intent3, resolvedType, resultTo, resultCode, resultData, resultExtras, requiredPermissions, appOp, bOptions, serialized, sticky, callingPid, callingUid, callingUid, callingPid, userId);
                    Binder.restoreCallingIdentity(origId);
                    resetPriorityAfterLockedSection();
                    return broadcastIntentLocked;
                } catch (Throwable th4) {
                    th = th4;
                    Binder.restoreCallingIdentity(origId);
                    throw th;
                }
            } catch (Throwable th5) {
                th = th5;
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    int broadcastIntentInPackage(String packageName, int uid, int realCallingUid, int realCallingPid, Intent intent, String resolvedType, IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras, String requiredPermission, Bundle bOptions, boolean serialized, boolean sticky, int userId, boolean allowBackgroundActivityStarts) {
        Intent intent2;
        synchronized (this) {
            try {
                try {
                    boostPriorityForLockedSection();
                    intent2 = intent;
                } catch (Throwable th) {
                    th = th;
                    intent2 = intent;
                }
            } catch (Throwable th2) {
                th = th2;
            }
            try {
                Intent intent3 = verifyBroadcastLocked(intent2);
                long origId = Binder.clearCallingIdentity();
                String[] requiredPermissions = requiredPermission == null ? null : new String[]{requiredPermission};
                int broadcastIntentLocked = broadcastIntentLocked(null, packageName, intent3, resolvedType, resultTo, resultCode, resultData, resultExtras, requiredPermissions, -1, bOptions, serialized, sticky, -1, uid, realCallingUid, realCallingPid, userId, allowBackgroundActivityStarts);
                Binder.restoreCallingIdentity(origId);
                resetPriorityAfterLockedSection();
                return broadcastIntentLocked;
            } catch (Throwable th3) {
                th = th3;
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public final void unbroadcastIntent(IApplicationThread caller, Intent intent, int userId) {
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        int userId2 = this.mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, 0, "removeStickyBroadcast", null);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (checkCallingPermission("android.permission.BROADCAST_STICKY") != 0) {
                    String msg = "Permission Denial: unbroadcastIntent() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.BROADCAST_STICKY";
                    Slog.w("ActivityManager", msg);
                    throw new SecurityException(msg);
                }
                ArrayMap<String, ArrayList<Intent>> stickies = this.mStickyBroadcasts.get(userId2);
                if (stickies != null) {
                    ArrayList<Intent> list = stickies.get(intent.getAction());
                    if (list != null) {
                        int N = list.size();
                        int i = 0;
                        while (true) {
                            if (i >= N) {
                                break;
                            } else if (!intent.filterEquals(list.get(i))) {
                                i++;
                            } else {
                                list.remove(i);
                                break;
                            }
                        }
                        if (list.size() <= 0) {
                            stickies.remove(intent.getAction());
                        }
                    }
                    int N2 = stickies.size();
                    if (N2 <= 0) {
                        this.mStickyBroadcasts.remove(userId2);
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void backgroundServicesFinishedLocked(int userId) {
        BroadcastQueue[] broadcastQueueArr;
        for (BroadcastQueue queue : this.mBroadcastQueues) {
            queue.backgroundServicesFinishedLocked(userId);
        }
    }

    public void finishReceiver(IBinder who, int resultCode, String resultData, Bundle resultExtras, boolean resultAbort, int flags) {
        BroadcastQueue queue;
        if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
            Slog.v("ActivityManager", "Finish receiver: " + who);
        }
        if (resultExtras != null && resultExtras.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }
        long origId = Binder.clearCallingIdentity();
        boolean doNext = false;
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                if (isOnOffloadQueue(flags)) {
                    queue = this.mOffloadBroadcastQueue;
                } else if ((268435456 & flags) == 0) {
                    queue = this.mBgBroadcastQueue;
                } else {
                    queue = this.mFgBroadcastQueue;
                }
                BroadcastRecord r = queue.getMatchingOrderedReceiver(who);
                if (r != null) {
                    doNext = r.queue.finishReceiverLocked(r, resultCode, resultData, resultExtras, resultAbort, true);
                }
                if (doNext) {
                    r.queue.processNextBroadcastLocked(false, true);
                }
                trimApplicationsLocked("updateOomAdj_finishReceiver");
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:105:0x01d3 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:57:0x016d  */
    /* JADX WARN: Removed duplicated region for block: B:58:0x016f  */
    /* JADX WARN: Removed duplicated region for block: B:68:0x0184 A[Catch: all -> 0x0057, TRY_ENTER, TRY_LEAVE, TryCatch #4 {all -> 0x0057, blocks: (B:12:0x003d, B:22:0x0069, B:23:0x007d, B:27:0x0084, B:28:0x009a, B:33:0x00a5, B:34:0x00bb, B:43:0x00d5, B:44:0x011a, B:48:0x012a, B:68:0x0184, B:52:0x013d), top: B:109:0x003d }] */
    /* JADX WARN: Removed duplicated region for block: B:87:0x01e8  */
    /* JADX WARN: Removed duplicated region for block: B:91:0x0211 A[Catch: all -> 0x022f, TryCatch #7 {all -> 0x022f, blocks: (B:89:0x01f8, B:91:0x0211, B:92:0x0216, B:93:0x0219, B:100:0x022a), top: B:114:0x0038 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean startInstrumentation(android.content.ComponentName r31, java.lang.String r32, int r33, android.os.Bundle r34, android.app.IInstrumentationWatcher r35, android.app.IUiAutomationConnection r36, int r37, java.lang.String r38) {
        /*
            Method dump skipped, instructions count: 561
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.startInstrumentation(android.content.ComponentName, java.lang.String, int, android.os.Bundle, android.app.IInstrumentationWatcher, android.app.IUiAutomationConnection, int, java.lang.String):boolean");
    }

    private boolean isCallerShell() {
        int callingUid = Binder.getCallingUid();
        return callingUid == NATIVE_DUMP_TIMEOUT_MS || callingUid == 0;
    }

    private void reportStartInstrumentationFailureLocked(IInstrumentationWatcher watcher, ComponentName cn, String report) {
        Slog.w("ActivityManager", report);
        if (watcher != null) {
            Bundle results = new Bundle();
            results.putString("id", "ActivityManagerService");
            results.putString("Error", report);
            this.mInstrumentationReporter.reportStatus(watcher, cn, -1, results);
        }
    }

    void addInstrumentationResultsLocked(ProcessRecord app, Bundle results) {
        ActiveInstrumentation instr = app.getActiveInstrumentation();
        if (instr == null) {
            Slog.w("ActivityManager", "finishInstrumentation called on non-instrumented: " + app);
        } else if (!instr.mFinished && results != null) {
            if (instr.mCurResults == null) {
                instr.mCurResults = new Bundle(results);
            } else {
                instr.mCurResults.putAll(results);
            }
        }
    }

    public void addInstrumentationResults(IApplicationThread target, Bundle results) {
        UserHandle.getCallingUserId();
        if (results != null && results.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ProcessRecord app = getRecordForAppLocked(target);
                if (app == null) {
                    Slog.w("ActivityManager", "addInstrumentationResults: no app for " + target);
                    resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                addInstrumentationResultsLocked(app, results);
                Binder.restoreCallingIdentity(origId);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @GuardedBy({"this"})
    void finishInstrumentationLocked(ProcessRecord app, int resultCode, Bundle results) {
        ActiveInstrumentation instr = app.getActiveInstrumentation();
        if (instr == null) {
            Slog.w("ActivityManager", "finishInstrumentation called on non-instrumented: " + app);
            return;
        }
        if (!instr.mFinished) {
            if (instr.mWatcher != null) {
                Bundle finalResults = instr.mCurResults;
                if (finalResults != null) {
                    if (instr.mCurResults != null && results != null) {
                        finalResults.putAll(results);
                    }
                } else {
                    finalResults = results;
                }
                this.mInstrumentationReporter.reportFinished(instr.mWatcher, instr.mClass, resultCode, finalResults);
            }
            if (instr.mUiAutomationConnection != null) {
                this.mAppOpsService.setAppOpsServiceDelegate(null);
                getPackageManagerInternalLocked().setCheckPermissionDelegate((PackageManagerInternal.CheckPermissionDelegate) null);
                this.mHandler.obtainMessage(56, instr.mUiAutomationConnection).sendToTarget();
            }
            instr.mFinished = true;
        }
        instr.removeProcess(app);
        app.setActiveInstrumentation(null);
        forceStopPackageLocked(app.info.packageName, -1, false, false, true, true, false, app.userId, "finished inst");
    }

    public void finishInstrumentation(IApplicationThread target, int resultCode, Bundle results) {
        UserHandle.getCallingUserId();
        if (results != null && results.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                ProcessRecord app = getRecordForAppLocked(target);
                if (app == null) {
                    Slog.w("ActivityManager", "finishInstrumentation: no app for " + target);
                    resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                finishInstrumentationLocked(app, resultCode, results);
                Binder.restoreCallingIdentity(origId);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public ActivityManager.StackInfo getFocusedStackInfo() throws RemoteException {
        return this.mActivityTaskManager.getFocusedStackInfo();
    }

    public Configuration getConfiguration() {
        return this.mActivityTaskManager.getConfiguration();
    }

    public void suppressResizeConfigChanges(boolean suppress) throws RemoteException {
        this.mActivityTaskManager.suppressResizeConfigChanges(suppress);
    }

    public void updatePersistentConfiguration(Configuration values) {
        enforceCallingPermission("android.permission.CHANGE_CONFIGURATION", "updatePersistentConfiguration()");
        enforceWriteSettingsPermission("updatePersistentConfiguration()");
        if (values == null) {
            throw new NullPointerException("Configuration must not be null");
        }
        int userId = UserHandle.getCallingUserId();
        this.mActivityTaskManager.updatePersistentConfiguration(values, userId);
    }

    private void enforceWriteSettingsPermission(String func) {
        int uid = Binder.getCallingUid();
        if (uid == 0) {
            return;
        }
        Context context = this.mContext;
        if (Settings.checkAndNoteWriteSettingsOperation(context, uid, Settings.getPackageNameForUid(context, uid), false)) {
            return;
        }
        String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid() + ", uid=" + uid + " requires android.permission.WRITE_SETTINGS";
        Slog.w("ActivityManager", msg);
        throw new SecurityException(msg);
    }

    public boolean updateConfiguration(Configuration values) {
        return this.mActivityTaskManager.updateConfiguration(values);
    }

    public int getLaunchedFromUid(IBinder activityToken) {
        return this.mActivityTaskManager.getLaunchedFromUid(activityToken);
    }

    public String getLaunchedFromPackage(IBinder activityToken) {
        return this.mActivityTaskManager.getLaunchedFromPackage(activityToken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isReceivingBroadcastLocked(ProcessRecord app, ArraySet<BroadcastQueue> receivingQueues) {
        BroadcastQueue[] broadcastQueueArr;
        int N = app.curReceivers.size();
        if (N > 0) {
            for (int i = 0; i < N; i++) {
                receivingQueues.add(app.curReceivers.valueAt(i).queue);
            }
            return true;
        }
        for (BroadcastQueue queue : this.mBroadcastQueues) {
            BroadcastRecord r = queue.mPendingBroadcast;
            if (r != null && r.curApp == app) {
                receivingQueues.add(queue);
            }
        }
        return true ^ receivingQueues.isEmpty();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Association startAssociationLocked(int sourceUid, String sourceProcess, int sourceState, int targetUid, long targetVersionCode, ComponentName targetComponent, String targetProcess) {
        ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> components;
        SparseArray<ArrayMap<String, Association>> sourceUids;
        ArrayMap<String, Association> sourceProcesses;
        if (!this.mTrackingAssociations) {
            return null;
        }
        ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> components2 = this.mAssociations.get(targetUid);
        if (components2 != null) {
            components = components2;
        } else {
            ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> components3 = new ArrayMap<>();
            this.mAssociations.put(targetUid, components3);
            components = components3;
        }
        SparseArray<ArrayMap<String, Association>> sourceUids2 = components.get(targetComponent);
        if (sourceUids2 != null) {
            sourceUids = sourceUids2;
        } else {
            SparseArray<ArrayMap<String, Association>> sourceUids3 = new SparseArray<>();
            components.put(targetComponent, sourceUids3);
            sourceUids = sourceUids3;
        }
        ArrayMap<String, Association> sourceProcesses2 = sourceUids.get(sourceUid);
        if (sourceProcesses2 != null) {
            sourceProcesses = sourceProcesses2;
        } else {
            ArrayMap<String, Association> sourceProcesses3 = new ArrayMap<>();
            sourceUids.put(sourceUid, sourceProcesses3);
            sourceProcesses = sourceProcesses3;
        }
        Association ass = sourceProcesses.get(sourceProcess);
        if (ass == null) {
            ass = new Association(sourceUid, sourceProcess, targetUid, targetComponent, targetProcess);
            sourceProcesses.put(sourceProcess, ass);
        }
        ass.mCount++;
        ass.mNesting++;
        if (ass.mNesting == 1) {
            long uptimeMillis = SystemClock.uptimeMillis();
            ass.mLastStateUptime = uptimeMillis;
            ass.mStartTime = uptimeMillis;
            ass.mLastState = sourceState;
        }
        return ass;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopAssociationLocked(int sourceUid, String sourceProcess, int targetUid, long targetVersionCode, ComponentName targetComponent, String targetProcess) {
        ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> components;
        SparseArray<ArrayMap<String, Association>> sourceUids;
        ArrayMap<String, Association> sourceProcesses;
        Association ass;
        if (this.mTrackingAssociations && (components = this.mAssociations.get(targetUid)) != null && (sourceUids = components.get(targetComponent)) != null && (sourceProcesses = sourceUids.get(sourceUid)) != null && (ass = sourceProcesses.get(sourceProcess)) != null && ass.mNesting > 0) {
            ass.mNesting--;
            if (ass.mNesting == 0) {
                long uptime = SystemClock.uptimeMillis();
                ass.mTime += uptime - ass.mStartTime;
                long[] jArr = ass.mStateTimes;
                int i = ass.mLastState + 0;
                jArr[i] = jArr[i] + (uptime - ass.mLastStateUptime);
                ass.mLastState = 23;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void noteUidProcessState(int uid, int state) {
        int N1;
        ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> targetComponents;
        ActivityManagerService activityManagerService = this;
        activityManagerService.mBatteryStatsService.noteUidProcessState(uid, state);
        activityManagerService.mAppOpsService.updateUidProcState(uid, state);
        if (activityManagerService.mTrackingAssociations) {
            int i1 = 0;
            int N12 = activityManagerService.mAssociations.size();
            while (i1 < N12) {
                ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> targetComponents2 = activityManagerService.mAssociations.valueAt(i1);
                int i2 = 0;
                int N2 = targetComponents2.size();
                while (i2 < N2) {
                    SparseArray<ArrayMap<String, Association>> sourceUids = targetComponents2.valueAt(i2);
                    ArrayMap<String, Association> sourceProcesses = sourceUids.get(uid);
                    if (sourceProcesses != null) {
                        int i4 = 0;
                        int N4 = sourceProcesses.size();
                        while (i4 < N4) {
                            Association ass = sourceProcesses.valueAt(i4);
                            if (ass.mNesting < 1) {
                                N1 = N12;
                                targetComponents = targetComponents2;
                            } else {
                                long uptime = SystemClock.uptimeMillis();
                                long[] jArr = ass.mStateTimes;
                                int i = ass.mLastState + 0;
                                N1 = N12;
                                targetComponents = targetComponents2;
                                jArr[i] = jArr[i] + (uptime - ass.mLastStateUptime);
                                ass.mLastState = state;
                                ass.mLastStateUptime = uptime;
                            }
                            i4++;
                            N12 = N1;
                            targetComponents2 = targetComponents;
                        }
                    }
                    i2++;
                    N12 = N12;
                    targetComponents2 = targetComponents2;
                }
                i1++;
                activityManagerService = this;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class RecordPssRunnable implements Runnable {
        private final File mHeapdumpFile;
        private final ProcessRecord mProc;
        private final ActivityManagerService mService;

        RecordPssRunnable(ActivityManagerService service, ProcessRecord proc, File heapdumpFile) {
            this.mService = service;
            this.mProc = proc;
            this.mHeapdumpFile = heapdumpFile;
        }

        @Override // java.lang.Runnable
        public void run() {
            this.mService.revokeUriPermission(ActivityThread.currentActivityThread().getApplicationThread(), null, DumpHeapActivity.JAVA_URI, 3, UserHandle.myUserId());
            ParcelFileDescriptor fd = null;
            try {
                try {
                    try {
                        this.mHeapdumpFile.delete();
                        fd = ParcelFileDescriptor.open(this.mHeapdumpFile, 771751936);
                        IApplicationThread thread = this.mProc.thread;
                        if (thread != null) {
                            try {
                                if (ActivityManagerDebugConfig.DEBUG_PSS) {
                                    Slog.d("ActivityManager", "Requesting dump heap from " + this.mProc + " to " + this.mHeapdumpFile);
                                }
                                thread.dumpHeap(true, false, false, this.mHeapdumpFile.toString(), fd, (RemoteCallback) null);
                            } catch (RemoteException e) {
                            }
                        }
                        if (fd != null) {
                            fd.close();
                        }
                    } catch (FileNotFoundException e2) {
                        e2.printStackTrace();
                        if (fd != null) {
                            fd.close();
                        }
                    }
                } catch (IOException e3) {
                }
            } catch (Throwable th) {
                if (0 != 0) {
                    try {
                        fd.close();
                    } catch (IOException e4) {
                    }
                }
                throw th;
            }
        }
    }

    void recordPssSampleLocked(ProcessRecord proc, int procState, long pss, long uss, long swapPss, long rss, int statType, long pssDuration, long now) {
        long j;
        EventLogTags.writeAmPss(proc.pid, proc.uid, proc.processName, pss * 1024, uss * 1024, swapPss * 1024, rss * 1024, statType, procState, pssDuration);
        proc.lastPssTime = now;
        proc.baseProcessTracker.addPss(pss, uss, rss, true, statType, pssDuration, proc.pkgList.mPkgList);
        for (int ipkg = proc.pkgList.mPkgList.size() - 1; ipkg >= 0; ipkg--) {
            ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
            StatsLog.write(18, proc.info.uid, holder.state.getName(), holder.state.getPackage(), pss, uss, rss, statType, pssDuration, holder.appVersion);
        }
        if (ActivityManagerDebugConfig.DEBUG_PSS) {
            StringBuilder sb = new StringBuilder();
            sb.append("pss of ");
            sb.append(proc.toShortString());
            sb.append(": ");
            j = pss;
            sb.append(j);
            sb.append(" lastPss=");
            sb.append(proc.lastPss);
            sb.append(" state=");
            sb.append(ProcessList.makeProcStateString(procState));
            Slog.d("ActivityManager", sb.toString());
        } else {
            j = pss;
        }
        if (proc.initialIdlePss == 0) {
            proc.initialIdlePss = j;
        }
        proc.lastPss = j;
        proc.lastSwapPss = swapPss;
        if (procState >= 15) {
            proc.lastCachedPss = j;
            proc.lastCachedSwapPss = swapPss;
        }
        SparseArray<Pair<Long, String>> watchUids = (SparseArray) this.mMemWatchProcesses.getMap().get(proc.processName);
        Long check = null;
        if (watchUids != null) {
            Pair<Long, String> val = watchUids.get(proc.uid);
            if (val == null) {
                val = watchUids.get(0);
            }
            if (val != null) {
                check = (Long) val.first;
            }
        }
        if (check != null && j * 1024 >= check.longValue() && proc.thread != null && this.mMemWatchDumpProcName == null) {
            boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (!isDebuggable && (proc.info.flags & 2) != 0) {
                isDebuggable = true;
            }
            if (isDebuggable) {
                Slog.w("ActivityManager", "Process " + proc + " exceeded pss limit " + check + "; reporting");
                startHeapDumpLocked(proc, false);
                return;
            }
            Slog.w("ActivityManager", "Process " + proc + " exceeded pss limit " + check + ", but debugging not enabled");
        }
    }

    private void startHeapDumpLocked(ProcessRecord proc, boolean isUserInitiated) {
        File heapdumpFile = DumpHeapProvider.getJavaFile();
        this.mMemWatchDumpProcName = proc.processName;
        this.mMemWatchDumpFile = heapdumpFile.toString();
        this.mMemWatchDumpPid = proc.pid;
        this.mMemWatchDumpUid = proc.uid;
        this.mMemWatchIsUserInitiated = isUserInitiated;
        BackgroundThread.getHandler().post(new RecordPssRunnable(this, proc, heapdumpFile));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean requestPssLocked(ProcessRecord proc, int procState) {
        if (this.mPendingPssProcesses.contains(proc)) {
            return false;
        }
        if (this.mPendingPssProcesses.size() == 0) {
            long deferral = (this.mPssDeferralTime <= 0 || this.mActivityStartingNesting.get() <= 0) ? 0L : this.mPssDeferralTime;
            if (ActivityManagerDebugConfig.DEBUG_PSS && deferral > 0) {
                Slog.d("ActivityManager", "requestPssLocked() deferring PSS request by " + deferral + " ms");
            }
            this.mBgHandler.sendEmptyMessageDelayed(1, deferral);
        }
        if (ActivityManagerDebugConfig.DEBUG_PSS) {
            Slog.d("ActivityManager", "Requesting pss of: " + proc);
        }
        proc.pssProcState = procState;
        proc.pssStatType = 0;
        this.mPendingPssProcesses.add(proc);
        return true;
    }

    private void deferPssIfNeededLocked() {
        if (this.mPendingPssProcesses.size() > 0) {
            this.mBgHandler.removeMessages(1);
            this.mBgHandler.sendEmptyMessageDelayed(1, this.mPssDeferralTime);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void deferPssForActivityStart() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (this.mPssDeferralTime > 0) {
                    if (ActivityManagerDebugConfig.DEBUG_PSS) {
                        Slog.d("ActivityManager", "Deferring PSS collection for activity start");
                    }
                    deferPssIfNeededLocked();
                    this.mActivityStartingNesting.getAndIncrement();
                    this.mBgHandler.sendEmptyMessageDelayed(3, this.mPssDeferralTime);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestPssAllProcsLocked(long now, boolean always, boolean memLowered) {
        if (!always) {
            if (now < this.mLastFullPssTime + (memLowered ? this.mConstants.FULL_PSS_LOWERED_INTERVAL : this.mConstants.FULL_PSS_MIN_INTERVAL)) {
                return;
            }
        }
        if (ActivityManagerDebugConfig.DEBUG_PSS) {
            Slog.d("ActivityManager", "Requesting pss of all procs!  memLowered=" + memLowered);
        }
        this.mLastFullPssTime = now;
        this.mFullPssPending = true;
        for (int i = this.mPendingPssProcesses.size() - 1; i >= 0; i--) {
            ProcessList.abortNextPssTime(this.mPendingPssProcesses.get(i).procStateMemTracker);
        }
        this.mPendingPssProcesses.ensureCapacity(this.mProcessList.getLruSizeLocked());
        this.mPendingPssProcesses.clear();
        for (int i2 = this.mProcessList.getLruSizeLocked() - 1; i2 >= 0; i2--) {
            ProcessRecord app = this.mProcessList.mLruProcesses.get(i2);
            if (app.thread != null && app.getCurProcState() != 21 && (memLowered || ((always && now > app.lastStateTime + 1000) || now > app.lastStateTime + 1200000))) {
                app.pssProcState = app.setProcState;
                app.pssStatType = always ? 2 : 1;
                app.nextPssTime = ProcessList.computeNextPssTime(app.getCurProcState(), app.procStateMemTracker, this.mTestPssMode, this.mAtmInternal.isSleeping(), now);
                this.mPendingPssProcesses.add(app);
            }
        }
        if (!this.mBgHandler.hasMessages(1)) {
            this.mBgHandler.sendEmptyMessage(1);
        }
    }

    public void setTestPssMode(boolean enabled) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mTestPssMode = enabled;
                if (enabled) {
                    requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, true);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    final void performAppGcLocked(ProcessRecord app) {
        try {
            app.lastRequestedGc = SystemClock.uptimeMillis();
            if (app.thread != null) {
                if (app.reportLowMemory) {
                    app.reportLowMemory = false;
                    app.thread.scheduleLowMemory();
                } else {
                    app.thread.processInBackground();
                }
            }
        } catch (Exception e) {
        }
    }

    private final boolean canGcNowLocked() {
        BroadcastQueue[] broadcastQueueArr;
        for (BroadcastQueue q : this.mBroadcastQueues) {
            if (!q.mParallelBroadcasts.isEmpty() || !q.mDispatcher.isEmpty()) {
                return false;
            }
        }
        return this.mAtmInternal.canGcNow();
    }

    /* JADX WARN: Removed duplicated region for block: B:9:0x0017  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    final void performAppGcsLocked() {
        /*
            r6 = this;
            java.util.ArrayList<com.android.server.am.ProcessRecord> r0 = r6.mProcessesToGc
            int r0 = r0.size()
            if (r0 > 0) goto L9
            return
        L9:
            boolean r1 = r6.canGcNowLocked()
            if (r1 == 0) goto L4a
        Lf:
            java.util.ArrayList<com.android.server.am.ProcessRecord> r1 = r6.mProcessesToGc
            int r1 = r1.size()
            if (r1 <= 0) goto L47
            java.util.ArrayList<com.android.server.am.ProcessRecord> r1 = r6.mProcessesToGc
            r2 = 0
            java.lang.Object r1 = r1.remove(r2)
            com.android.server.am.ProcessRecord r1 = (com.android.server.am.ProcessRecord) r1
            int r2 = r1.getCurRawAdj()
            r3 = 200(0xc8, float:2.8E-43)
            if (r2 > r3) goto L2e
            boolean r2 = r1.reportLowMemory
            if (r2 == 0) goto L2d
            goto L2e
        L2d:
            goto Lf
        L2e:
            long r2 = r1.lastRequestedGc
            com.android.server.am.ActivityManagerConstants r4 = r6.mConstants
            long r4 = r4.GC_MIN_INTERVAL
            long r2 = r2 + r4
            long r4 = android.os.SystemClock.uptimeMillis()
            int r2 = (r2 > r4 ? 1 : (r2 == r4 ? 0 : -1))
            if (r2 > 0) goto L44
            r6.performAppGcLocked(r1)
            r6.scheduleAppGcsLocked()
            return
        L44:
            r6.addProcessToGcListLocked(r1)
        L47:
            r6.scheduleAppGcsLocked()
        L4a:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.performAppGcsLocked():void");
    }

    final void performAppGcsIfAppropriateLocked() {
        if (canGcNowLocked()) {
            performAppGcsLocked();
        } else {
            scheduleAppGcsLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void scheduleAppGcsLocked() {
        this.mHandler.removeMessages(5);
        if (this.mProcessesToGc.size() > 0) {
            ProcessRecord proc = this.mProcessesToGc.get(0);
            Message msg = this.mHandler.obtainMessage(5);
            long when = proc.lastRequestedGc + this.mConstants.GC_MIN_INTERVAL;
            long now = SystemClock.uptimeMillis();
            if (when < this.mConstants.GC_TIMEOUT + now) {
                when = now + this.mConstants.GC_TIMEOUT;
            }
            this.mHandler.sendMessageAtTime(msg, when);
        }
    }

    final void addProcessToGcListLocked(ProcessRecord proc) {
        boolean added = false;
        int i = this.mProcessesToGc.size() - 1;
        while (true) {
            if (i < 0) {
                break;
            } else if (this.mProcessesToGc.get(i).lastRequestedGc >= proc.lastRequestedGc) {
                i--;
            } else {
                added = true;
                this.mProcessesToGc.add(i + 1, proc);
                break;
            }
        }
        if (!added) {
            this.mProcessesToGc.add(0, proc);
        }
    }

    final void scheduleAppGcLocked(ProcessRecord app) {
        long now = SystemClock.uptimeMillis();
        if (app.lastRequestedGc + this.mConstants.GC_MIN_INTERVAL <= now && !this.mProcessesToGc.contains(app)) {
            addProcessToGcListLocked(app);
            scheduleAppGcsLocked();
        }
    }

    final void checkExcessivePowerUsageLocked() {
        boolean doCpuKills;
        long j;
        boolean doCpuKills2;
        long curUptime;
        long uptimeSince;
        ProcessRecord app;
        int cpuLimit;
        int cpuLimit2;
        ActivityManagerService activityManagerService = this;
        updateCpuStatsNow();
        BatteryStatsImpl stats = activityManagerService.mBatteryStatsService.getActiveStatistics();
        long j2 = 0;
        if (activityManagerService.mLastPowerCheckUptime != 0) {
            doCpuKills = true;
        } else {
            doCpuKills = false;
        }
        long curUptime2 = SystemClock.uptimeMillis();
        long uptimeSince2 = curUptime2 - activityManagerService.mLastPowerCheckUptime;
        activityManagerService.mLastPowerCheckUptime = curUptime2;
        int i = activityManagerService.mProcessList.mLruProcesses.size();
        while (i > 0) {
            int i2 = i - 1;
            ProcessRecord app2 = activityManagerService.mProcessList.mLruProcesses.get(i2);
            if (app2.setProcState < 15) {
                j = j2;
                doCpuKills2 = doCpuKills;
                curUptime = curUptime2;
                uptimeSince = uptimeSince2;
            } else if (app2.lastCpuTime <= j2) {
                j = j2;
                doCpuKills2 = doCpuKills;
                curUptime = curUptime2;
                uptimeSince = uptimeSince2;
            } else {
                long cputimeUsed = app2.curCpuTime - app2.lastCpuTime;
                if (ActivityManagerDebugConfig.DEBUG_POWER) {
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("CPU for ");
                    app2.toShortString(sb);
                    sb.append(": over ");
                    TimeUtils.formatDuration(uptimeSince2, sb);
                    sb.append(" used ");
                    TimeUtils.formatDuration(cputimeUsed, sb);
                    sb.append(" (");
                    sb.append((cputimeUsed * 100) / uptimeSince2);
                    sb.append("%)");
                    Slog.i("ActivityManager", sb.toString());
                }
                if (doCpuKills) {
                    j = 0;
                    if (uptimeSince2 <= 0) {
                        app = app2;
                        doCpuKills2 = doCpuKills;
                        curUptime = curUptime2;
                    } else {
                        long checkDur = curUptime2 - app2.getWhenUnimportant();
                        doCpuKills2 = doCpuKills;
                        curUptime = curUptime2;
                        if (checkDur <= activityManagerService.mConstants.POWER_CHECK_INTERVAL) {
                            cpuLimit = activityManagerService.mConstants.POWER_CHECK_MAX_CPU_1;
                        } else if (checkDur <= activityManagerService.mConstants.POWER_CHECK_INTERVAL * 2 || app2.setProcState <= 15) {
                            cpuLimit = activityManagerService.mConstants.POWER_CHECK_MAX_CPU_2;
                        } else if (checkDur <= activityManagerService.mConstants.POWER_CHECK_INTERVAL * 3) {
                            cpuLimit = activityManagerService.mConstants.POWER_CHECK_MAX_CPU_3;
                        } else {
                            cpuLimit = activityManagerService.mConstants.POWER_CHECK_MAX_CPU_4;
                        }
                        if ((100 * cputimeUsed) / uptimeSince2 >= cpuLimit) {
                            synchronized (stats) {
                                try {
                                    app = app2;
                                    cpuLimit2 = cpuLimit;
                                    uptimeSince = uptimeSince2;
                                } catch (Throwable th) {
                                    th = th;
                                }
                                try {
                                    stats.reportExcessiveCpuLocked(app2.info.uid, app2.processName, uptimeSince2, cputimeUsed);
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
                            }
                            app.kill("excessive cpu " + cputimeUsed + " during " + uptimeSince + " dur=" + checkDur + " limit=" + cpuLimit2, true);
                            app.baseProcessTracker.reportExcessiveCpu(app.pkgList.mPkgList);
                            for (int ipkg = app.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                                ProcessStats.ProcessStateHolder holder = app.pkgList.valueAt(ipkg);
                                StatsLog.write(16, app.info.uid, holder.state.getName(), holder.state.getPackage(), holder.appVersion);
                            }
                        } else {
                            app = app2;
                            uptimeSince = uptimeSince2;
                        }
                        app.lastCpuTime = app.curCpuTime;
                    }
                } else {
                    app = app2;
                    doCpuKills2 = doCpuKills;
                    curUptime = curUptime2;
                    j = 0;
                }
                uptimeSince = uptimeSince2;
                app.lastCpuTime = app.curCpuTime;
            }
            activityManagerService = this;
            uptimeSince2 = uptimeSince;
            i = i2;
            j2 = j;
            doCpuKills = doCpuKills2;
            curUptime2 = curUptime;
        }
    }

    private boolean isEphemeralLocked(int uid) {
        String[] packages = this.mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1) {
            return false;
        }
        return getPackageManagerInternalLocked().isPackageEphemeral(UserHandle.getUserId(uid), packages[0]);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public final void enqueueUidChangeLocked(UidRecord uidRec, int uid, int change) {
        UidRecord.ChangeItem pendingChange;
        UidRecord.ChangeItem pendingChange2;
        if (uidRec == null || uidRec.pendingChange == null) {
            if (this.mPendingUidChanges.size() == 0) {
                if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                    Slog.i("ActivityManager", "*** Enqueueing dispatch uid changed!");
                }
                this.mUiHandler.obtainMessage(53).sendToTarget();
            }
            int NA = this.mAvailUidChanges.size();
            if (NA > 0) {
                pendingChange = this.mAvailUidChanges.remove(NA - 1);
                if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                    Slog.i("ActivityManager", "Retrieving available item: " + pendingChange);
                }
            } else {
                pendingChange = new UidRecord.ChangeItem();
                if (ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS) {
                    Slog.i("ActivityManager", "Allocating new item: " + pendingChange);
                }
            }
            if (uidRec != null) {
                uidRec.pendingChange = pendingChange;
                if ((change & 1) != 0 && !uidRec.idle) {
                    change |= 2;
                }
            } else if (uid < 0) {
                throw new IllegalArgumentException("No UidRecord or uid");
            }
            pendingChange.uidRecord = uidRec;
            pendingChange.uid = uidRec != null ? uidRec.uid : uid;
            this.mPendingUidChanges.add(pendingChange);
            pendingChange2 = pendingChange;
        } else {
            pendingChange2 = uidRec.pendingChange;
            if ((change & 6) == 0) {
                change |= pendingChange2.change & 6;
            }
            if ((change & 24) == 0) {
                change |= pendingChange2.change & 24;
            }
            if ((change & 1) != 0) {
                change &= -13;
                if (!uidRec.idle) {
                    change |= 2;
                }
            }
        }
        pendingChange2.change = change;
        pendingChange2.processState = uidRec != null ? uidRec.setProcState : 21;
        pendingChange2.ephemeral = uidRec != null ? uidRec.ephemeral : isEphemeralLocked(uid);
        pendingChange2.procStateSeq = uidRec != null ? uidRec.curProcStateSeq : 0L;
        if (uidRec != null) {
            uidRec.lastReportedChange = change;
            uidRec.updateLastDispatchedProcStateSeq(change);
        }
        PowerManagerInternal powerManagerInternal = this.mLocalPowerManager;
        if (powerManagerInternal != null) {
            if ((change & 4) != 0) {
                powerManagerInternal.uidActive(pendingChange2.uid);
            }
            if ((change & 2) != 0) {
                this.mLocalPowerManager.uidIdle(pendingChange2.uid);
            }
            if ((change & 1) != 0) {
                this.mLocalPowerManager.uidGone(pendingChange2.uid);
            } else {
                this.mLocalPowerManager.updateUidProcState(pendingChange2.uid, pendingChange2.processState);
            }
        }
    }

    private void maybeUpdateProviderUsageStatsLocked(ProcessRecord app, String providerPkgName, String authority) {
        UserState userState;
        if (app == null || app.getCurProcState() > 7 || (userState = this.mUserController.getStartedUserState(app.userId)) == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long lastReported = userState.mProviderLastReportedFg.get(authority);
        if (lastReported == null || lastReported.longValue() < now - 60000) {
            if (this.mSystemReady) {
                this.mUsageStatsService.reportContentProviderUsage(authority, providerPkgName, app.userId);
            }
            userState.mProviderLastReportedFg.put(authority, Long.valueOf(now));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void setProcessTrackerStateLocked(ProcessRecord proc, int memFactor, long now) {
        if (proc.thread != null && proc.baseProcessTracker != null) {
            proc.baseProcessTracker.setState(proc.getReportedProcState(), memFactor, now, proc.pkgList.mPkgList);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final void updateProcessForegroundLocked(ProcessRecord proc, boolean isForeground, int fgServiceTypes, boolean oomAdj) {
        if (isForeground != proc.hasForegroundServices() || proc.getForegroundServiceTypes() != fgServiceTypes) {
            proc.setHasForegroundServices(isForeground, fgServiceTypes);
            ArrayList<ProcessRecord> curProcs = (ArrayList) this.mForegroundPackages.get(proc.info.packageName, proc.info.uid);
            if (isForeground) {
                if (curProcs == null) {
                    curProcs = new ArrayList<>();
                    this.mForegroundPackages.put(proc.info.packageName, proc.info.uid, curProcs);
                }
                if (!curProcs.contains(proc)) {
                    curProcs.add(proc);
                    this.mBatteryStatsService.noteEvent(32770, proc.info.packageName, proc.info.uid);
                }
            } else if (curProcs != null && curProcs.remove(proc)) {
                this.mBatteryStatsService.noteEvent(16386, proc.info.packageName, proc.info.uid);
                if (curProcs.size() <= 0) {
                    this.mForegroundPackages.remove(proc.info.packageName, proc.info.uid);
                }
            }
            proc.setReportedForegroundServiceTypes(fgServiceTypes);
            ProcessChangeItem item = enqueueProcessChangeItemLocked(proc.pid, proc.info.uid);
            item.changes = 2;
            item.foregroundServiceTypes = fgServiceTypes;
            if (oomAdj) {
                updateOomAdjLocked("updateOomAdj_uiVisibility");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProcessRecord getTopAppLocked() {
        String pkg;
        int uid;
        String str;
        ActivityTaskManagerInternal activityTaskManagerInternal = this.mAtmInternal;
        WindowProcessController wpc = activityTaskManagerInternal != null ? activityTaskManagerInternal.getTopApp() : null;
        ProcessRecord r = wpc != null ? (ProcessRecord) wpc.mOwner : null;
        if (r != null) {
            pkg = r.processName;
            uid = r.info.uid;
        } else {
            pkg = null;
            uid = -1;
        }
        if (uid != this.mCurResumedUid || (pkg != (str = this.mCurResumedPackage) && (pkg == null || !pkg.equals(str)))) {
            long identity = Binder.clearCallingIdentity();
            try {
                if (this.mCurResumedPackage != null) {
                    this.mBatteryStatsService.noteEvent(16387, this.mCurResumedPackage, this.mCurResumedUid);
                }
                this.mCurResumedPackage = pkg;
                this.mCurResumedUid = uid;
                if (this.mCurResumedPackage != null) {
                    this.mBatteryStatsService.noteEvent(32771, this.mCurResumedPackage, this.mCurResumedUid);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return r;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final boolean updateOomAdjLocked(ProcessRecord app, boolean oomAdjAll, String oomAdjReason) {
        return this.mOomAdjuster.updateOomAdjLocked(app, oomAdjAll, oomAdjReason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class ProcStatsRunnable implements Runnable {
        private final ProcessStatsService mProcessStats;
        private final ActivityManagerService mService;

        /* JADX INFO: Access modifiers changed from: package-private */
        public ProcStatsRunnable(ActivityManagerService service, ProcessStatsService mProcessStats) {
            this.mService = service;
            this.mProcessStats = mProcessStats;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    this.mProcessStats.writeStateAsyncLocked();
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final boolean updateLowMemStateLocked(int numCached, int numEmpty, int numTrimming) {
        int memFactor;
        int i;
        int fgTrimLevel;
        int memFactor2;
        int memFactor3;
        int trackerMemFactor;
        int curLevel;
        int N = this.mProcessList.getLruSizeLocked();
        long now = SystemClock.uptimeMillis();
        LowMemDetector lowMemDetector = this.mLowMemDetector;
        if (lowMemDetector != null && lowMemDetector.isAvailable()) {
            memFactor = this.mLowMemDetector.getMemFactor();
        } else if (numCached <= this.mConstants.CUR_TRIM_CACHED_PROCESSES && numEmpty <= this.mConstants.CUR_TRIM_EMPTY_PROCESSES) {
            int numCachedAndEmpty = numCached + numEmpty;
            if (numCachedAndEmpty <= 3) {
                memFactor = 3;
            } else if (numCachedAndEmpty <= 5) {
                memFactor = 2;
            } else {
                memFactor = 1;
            }
        } else {
            memFactor = 0;
        }
        if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
            Slog.d("ActivityManager", "oom: memFactor=" + memFactor + " last=" + this.mLastMemoryLevel + " allowLow=" + this.mAllowLowerMemLevel + " numProcs=" + this.mProcessList.getLruSizeLocked() + " last=" + this.mLastNumProcesses);
        }
        if (memFactor > this.mLastMemoryLevel && (!this.mAllowLowerMemLevel || this.mProcessList.getLruSizeLocked() >= this.mLastNumProcesses)) {
            memFactor = this.mLastMemoryLevel;
            if (ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                Slog.d("ActivityManager", "Keeping last mem factor!");
            }
        }
        int trackerMemFactor2 = memFactor;
        int memFactor4 = this.mLastMemoryLevel;
        if (trackerMemFactor2 != memFactor4) {
            EventLogTags.writeAmMemFactor(trackerMemFactor2, memFactor4);
            StatsLog.write(15, trackerMemFactor2);
        }
        this.mLastMemoryLevel = trackerMemFactor2;
        this.mLastNumProcesses = this.mProcessList.getLruSizeLocked();
        ProcessStatsService processStatsService = this.mProcessStats;
        ActivityTaskManagerInternal activityTaskManagerInternal = this.mAtmInternal;
        boolean z = true;
        if (activityTaskManagerInternal != null && activityTaskManagerInternal.mSleeping) {
            z = false;
        }
        boolean allChanged = processStatsService.setMemFactorLocked(trackerMemFactor2, z, now);
        int trackerMemFactor3 = this.mProcessStats.getMemFactorLocked();
        if (trackerMemFactor2 == 0) {
            long j = this.mLowRamStartTime;
            if (j != 0) {
                this.mLowRamTimeSinceLastIdle += now - j;
                this.mLowRamStartTime = 0L;
            }
            for (int i2 = N - 1; i2 >= 0; i2--) {
                ProcessRecord app = this.mProcessList.mLruProcesses.get(i2);
                if (allChanged || app.procStateChanged) {
                    setProcessTrackerStateLocked(app, trackerMemFactor3, now);
                    app.procStateChanged = false;
                }
                if (app.getCurProcState() < 8 && !app.systemNoUi) {
                    i = 0;
                } else if (app.hasPendingUiClean()) {
                    if (app.trimMemoryLevel < 20 && app.thread != null) {
                        try {
                            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("Trimming memory of ui hidden ");
                                sb.append(app.processName);
                                sb.append(" to ");
                                sb.append(20);
                                Slog.v("ActivityManager", sb.toString());
                            }
                            try {
                                app.thread.scheduleTrimMemory(20);
                            } catch (RemoteException e) {
                            }
                        } catch (RemoteException e2) {
                        }
                    }
                    i = 0;
                    app.setPendingUiClean(false);
                } else {
                    i = 0;
                }
                app.trimMemoryLevel = i;
            }
        } else {
            int minFactor = trackerMemFactor3;
            if (this.mLowRamStartTime == 0) {
                this.mLowRamStartTime = now;
            }
            if (trackerMemFactor2 == 2) {
                fgTrimLevel = 10;
            } else if (trackerMemFactor2 == 3) {
                fgTrimLevel = 15;
            } else {
                fgTrimLevel = 5;
            }
            int factor = numTrimming / 3;
            int minFactor2 = this.mAtmInternal.getHomeProcess() != null ? 2 + 1 : 2;
            if (this.mAtmInternal.getPreviousProcess() != null) {
                minFactor2++;
            }
            if (factor < minFactor2) {
                factor = minFactor2;
            }
            int i3 = N - 1;
            int curLevel2 = 80;
            int step = 0;
            while (i3 >= 0) {
                ProcessRecord app2 = this.mProcessList.mLruProcesses.get(i3);
                if (allChanged || app2.procStateChanged) {
                    memFactor2 = trackerMemFactor2;
                    memFactor3 = minFactor;
                    setProcessTrackerStateLocked(app2, memFactor3, now);
                    trackerMemFactor = minFactor2;
                    app2.procStateChanged = false;
                } else {
                    memFactor2 = trackerMemFactor2;
                    memFactor3 = minFactor;
                    trackerMemFactor = minFactor2;
                }
                if (app2.getCurProcState() >= 15 && !app2.killedByAm) {
                    if (app2.trimMemoryLevel < curLevel2 && app2.thread != null) {
                        try {
                            if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                                Slog.v("ActivityManager", "Trimming memory of " + app2.processName + " to " + curLevel2);
                            }
                            app2.thread.scheduleTrimMemory(curLevel2);
                        } catch (RemoteException e3) {
                        }
                    }
                    app2.trimMemoryLevel = curLevel2;
                    step++;
                    if (step >= factor) {
                        if (curLevel2 == 60) {
                            curLevel2 = 40;
                        } else if (curLevel2 == 80) {
                            curLevel2 = 60;
                        }
                        step = 0;
                    }
                } else {
                    int step2 = app2.getCurProcState();
                    if (step2 == 14 && !app2.killedByAm) {
                        if (app2.trimMemoryLevel < 40 && app2.thread != null) {
                            try {
                                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                                    Slog.v("ActivityManager", "Trimming memory of heavy-weight " + app2.processName + " to 40");
                                }
                                app2.thread.scheduleTrimMemory(40);
                            } catch (RemoteException e4) {
                            }
                        }
                        app2.trimMemoryLevel = 40;
                        curLevel = curLevel2;
                    } else {
                        if (app2.getCurProcState() < 8 && !app2.systemNoUi) {
                            curLevel = curLevel2;
                        } else if (!app2.hasPendingUiClean()) {
                            curLevel = curLevel2;
                        } else {
                            curLevel = curLevel2;
                            if (app2.trimMemoryLevel < 20 && app2.thread != null) {
                                try {
                                    if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                                        Slog.v("ActivityManager", "Trimming memory of bg-ui " + app2.processName + " to 20");
                                    }
                                    app2.thread.scheduleTrimMemory(20);
                                } catch (RemoteException e5) {
                                }
                            }
                            app2.setPendingUiClean(false);
                        }
                        if (app2.trimMemoryLevel < fgTrimLevel && app2.thread != null) {
                            try {
                                if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityManagerDebugConfig.DEBUG_OOM_ADJ) {
                                    Slog.v("ActivityManager", "Trimming memory of fg " + app2.processName + " to " + fgTrimLevel);
                                }
                                app2.thread.scheduleTrimMemory(fgTrimLevel);
                            } catch (RemoteException e6) {
                            }
                        }
                        app2.trimMemoryLevel = fgTrimLevel;
                    }
                    curLevel2 = curLevel;
                }
                i3--;
                minFactor2 = trackerMemFactor;
                minFactor = memFactor3;
                trackerMemFactor2 = memFactor2;
            }
        }
        return allChanged;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final void updateOomAdjLocked(String oomAdjReason) {
        this.mOomAdjuster.updateOomAdjLocked(oomAdjReason);
    }

    public void makePackageIdle(String packageName, int userId) {
        IPackageManager pm;
        if (checkCallingPermission("android.permission.FORCE_STOP_PACKAGES") != 0) {
            String msg = "Permission Denial: makePackageIdle() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.FORCE_STOP_PACKAGES";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        int callingPid = Binder.getCallingPid();
        int userId2 = this.mUserController.handleIncomingUser(callingPid, Binder.getCallingUid(), userId, true, 2, "makePackageIdle", null);
        long callingId = Binder.clearCallingIdentity();
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                IPackageManager pm2 = AppGlobals.getPackageManager();
                int pkgUid = -1;
                try {
                    pkgUid = pm2.getPackageUid(packageName, 268443648, 0);
                } catch (RemoteException e) {
                }
                int i = -1;
                if (pkgUid == -1) {
                    throw new IllegalArgumentException("Unknown package name " + packageName);
                }
                if (this.mLocalPowerManager != null) {
                    this.mLocalPowerManager.startUidChanges();
                }
                int appId = UserHandle.getAppId(pkgUid);
                int N = this.mProcessList.mActiveUids.size();
                int i2 = N - 1;
                while (i2 >= 0) {
                    UidRecord uidRec = this.mProcessList.mActiveUids.valueAt(i2);
                    long bgTime = uidRec.lastBackgroundTime;
                    if (bgTime <= 0 || uidRec.idle) {
                        pm = pm2;
                    } else if (UserHandle.getAppId(uidRec.uid) != appId) {
                        pm = pm2;
                    } else {
                        if (userId2 != i && userId2 != UserHandle.getUserId(uidRec.uid)) {
                            pm = pm2;
                        }
                        EventLogTags.writeAmUidIdle(uidRec.uid);
                        uidRec.idle = true;
                        uidRec.setIdle = true;
                        StringBuilder sb = new StringBuilder();
                        pm = pm2;
                        sb.append("Idling uid ");
                        sb.append(UserHandle.formatUid(uidRec.uid));
                        sb.append(" from package ");
                        sb.append(packageName);
                        sb.append(" user ");
                        sb.append(userId2);
                        Slog.w("ActivityManager", sb.toString());
                        doStopUidLocked(uidRec.uid, uidRec);
                    }
                    i2--;
                    pm2 = pm;
                    i = -1;
                }
                if (this.mLocalPowerManager != null) {
                    this.mLocalPowerManager.finishUidChanges();
                }
                Binder.restoreCallingIdentity(callingId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    final void idleUids() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mOomAdjuster.idleUidsLocked();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    @VisibleForTesting
    public void incrementProcStateSeqAndNotifyAppsLocked() {
        int blockState;
        if (this.mWaitForNetworkTimeoutMs <= 0) {
            return;
        }
        ArrayList<Integer> blockingUids = null;
        for (int i = this.mProcessList.mActiveUids.size() - 1; i >= 0; i--) {
            UidRecord uidRec = this.mProcessList.mActiveUids.valueAt(i);
            if (this.mInjector.isNetworkRestrictedForUid(uidRec.uid) && UserHandle.isApp(uidRec.uid) && uidRec.hasInternetPermission && uidRec.setProcState != uidRec.getCurProcState() && (blockState = getBlockStateForUid(uidRec)) != 0) {
                synchronized (uidRec.networkStateLock) {
                    ProcessList processList = this.mProcessList;
                    long j = processList.mProcStateSeqCounter + 1;
                    processList.mProcStateSeqCounter = j;
                    uidRec.curProcStateSeq = j;
                    if (blockState == 1) {
                        if (blockingUids == null) {
                            blockingUids = new ArrayList<>();
                        }
                        blockingUids.add(Integer.valueOf(uidRec.uid));
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                            Slog.d(TAG_NETWORK, "uid going to background, notifying all blocking threads for uid: " + uidRec);
                        }
                        if (uidRec.waitingForNetwork) {
                            uidRec.networkStateLock.notifyAll();
                        }
                    }
                }
            }
        }
        if (blockingUids == null) {
            return;
        }
        for (int i2 = this.mProcessList.mLruProcesses.size() - 1; i2 >= 0; i2--) {
            ProcessRecord app = this.mProcessList.mLruProcesses.get(i2);
            if (blockingUids.contains(Integer.valueOf(app.uid)) && !app.killedByAm && app.thread != null) {
                UidRecord uidRec2 = this.mProcessList.getUidRecordLocked(app.uid);
                try {
                    if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "Informing app thread that it needs to block: " + uidRec2);
                    }
                    if (uidRec2 != null) {
                        app.thread.setNetworkBlockSeq(uidRec2.curProcStateSeq);
                    }
                } catch (RemoteException e) {
                }
            }
        }
    }

    @VisibleForTesting
    int getBlockStateForUid(UidRecord uidRec) {
        boolean isAllowed = NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.getCurProcState()) || NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground(uidRec.getCurProcState());
        boolean wasAllowed = NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.setProcState) || NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground(uidRec.setProcState);
        if (wasAllowed || !isAllowed) {
            return (!wasAllowed || isAllowed) ? 0 : 2;
        }
        return 1;
    }

    final void runInBackgroundDisabled(int uid) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                UidRecord uidRec = this.mProcessList.getUidRecordLocked(uid);
                if (uidRec != null) {
                    if (uidRec.idle) {
                        doStopUidLocked(uidRec.uid, uidRec);
                    }
                } else {
                    doStopUidLocked(uid, null);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public final void doStopUidLocked(int uid, UidRecord uidRec) {
        this.mServices.stopInBackgroundLocked(uid);
        enqueueUidChangeLocked(uidRec, uid, 2);
    }

    @GuardedBy({"this"})
    void tempWhitelistForPendingIntentLocked(int callerPid, int callerUid, int targetUid, long duration, String tag) {
        if (ActivityManagerDebugConfig.DEBUG_WHITELISTS) {
            Slog.d("ActivityManager", "tempWhitelistForPendingIntentLocked(" + callerPid + ", " + callerUid + ", " + targetUid + ", " + duration + ")");
        }
        synchronized (this.mPidsSelfLocked) {
            ProcessRecord pr = this.mPidsSelfLocked.get(callerPid);
            if (pr == null) {
                Slog.w("ActivityManager", "tempWhitelistForPendingIntentLocked() no ProcessRecord for pid " + callerPid);
            } else if (!pr.whitelistManager && checkPermission("android.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST", callerPid, callerUid) != 0) {
                if (ActivityManagerDebugConfig.DEBUG_WHITELISTS) {
                    Slog.d("ActivityManager", "tempWhitelistForPendingIntentLocked() for target " + targetUid + ": pid " + callerPid + " is not allowed");
                }
            } else {
                tempWhitelistUidLocked(targetUid, duration, tag);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"this"})
    public void tempWhitelistUidLocked(int targetUid, long duration, String tag) {
        this.mPendingTempWhitelist.put(targetUid, new PendingTempWhitelist(targetUid, duration, tag));
        setUidTempWhitelistStateLocked(targetUid, true);
        this.mUiHandler.obtainMessage(68).sendToTarget();
    }

    void pushTempWhitelist() {
        int N;
        PendingTempWhitelist[] list;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                N = this.mPendingTempWhitelist.size();
                list = new PendingTempWhitelist[N];
                for (int i = 0; i < N; i++) {
                    list[i] = this.mPendingTempWhitelist.valueAt(i);
                }
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
        for (int i2 = 0; i2 < N; i2++) {
            PendingTempWhitelist ptw = list[i2];
            this.mLocalDeviceIdleController.addPowerSaveTempWhitelistAppDirect(ptw.targetUid, ptw.duration, true, ptw.tag);
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                for (int i3 = 0; i3 < N; i3++) {
                    PendingTempWhitelist ptw2 = list[i3];
                    int index = this.mPendingTempWhitelist.indexOfKey(ptw2.targetUid);
                    if (index >= 0 && this.mPendingTempWhitelist.valueAt(index) == ptw2) {
                        this.mPendingTempWhitelist.removeAt(index);
                    }
                }
            } finally {
            }
        }
        resetPriorityAfterLockedSection();
    }

    @GuardedBy({"this"})
    final void setAppIdTempWhitelistStateLocked(int appId, boolean onWhitelist) {
        this.mOomAdjuster.setAppIdTempWhitelistStateLocked(appId, onWhitelist);
    }

    @GuardedBy({"this"})
    final void setUidTempWhitelistStateLocked(int uid, boolean onWhitelist) {
        this.mOomAdjuster.setUidTempWhitelistStateLocked(uid, onWhitelist);
    }

    final void trimApplications(String oomAdjReason) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                trimApplicationsLocked(oomAdjReason);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    @GuardedBy({"this"})
    final void trimApplicationsLocked(String oomAdjReason) {
        for (int i = this.mProcessList.mRemovedProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord app = this.mProcessList.mRemovedProcesses.get(i);
            if (!app.hasActivitiesOrRecentTasks() && app.curReceivers.isEmpty() && app.services.size() == 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("Exiting empty application process ");
                sb.append(app.toShortString());
                sb.append(" (");
                sb.append(app.thread != null ? app.thread.asBinder() : null);
                sb.append(")\n");
                Slog.i("ActivityManager", sb.toString());
                if (app.pid > 0 && app.pid != MY_PID) {
                    app.kill("empty", false);
                } else if (app.thread != null) {
                    try {
                        app.thread.scheduleExit();
                    } catch (Exception e) {
                    }
                }
                cleanUpApplicationRecordLocked(app, false, true, -1, false);
                this.mProcessList.mRemovedProcesses.remove(i);
                if (app.isPersistent()) {
                    addAppLocked(app.info, null, false, null);
                }
            }
        }
        updateOomAdjLocked(oomAdjReason);
    }

    public void signalPersistentProcesses(int sig) throws RemoteException {
        if (sig != 10) {
            throw new SecurityException("Only SIGNAL_USR1 is allowed");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (checkCallingPermission("android.permission.SIGNAL_PERSISTENT_PROCESSES") != 0) {
                    throw new SecurityException("Requires permission android.permission.SIGNAL_PERSISTENT_PROCESSES");
                }
                for (int i = this.mProcessList.mLruProcesses.size() - 1; i >= 0; i--) {
                    ProcessRecord r = this.mProcessList.mLruProcesses.get(i);
                    if (r.thread != null && r.isPersistent()) {
                        Process.sendSignal(r.pid, sig);
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    private void stopProfilerLocked(ProcessRecord proc, int profileType) {
        if (proc == null || proc == this.mProfileData.getProfileProc()) {
            proc = this.mProfileData.getProfileProc();
            profileType = this.mProfileType;
            clearProfilerLocked();
        }
        if (proc == null) {
            return;
        }
        try {
            proc.thread.profilerControl(false, (ProfilerInfo) null, profileType);
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearProfilerLocked() {
        if (this.mProfileData.getProfilerInfo() != null && this.mProfileData.getProfilerInfo().profileFd != null) {
            try {
                this.mProfileData.getProfilerInfo().profileFd.close();
            } catch (IOException e) {
            }
        }
        this.mProfileData.setProfileApp(null);
        this.mProfileData.setProfileProc(null);
        this.mProfileData.setProfilerInfo(null);
    }

    /* JADX WARN: Code restructure failed: missing block: B:23:0x004b, code lost:
        stopProfilerLocked(null, 0);
        setProfileApp(r0.info, r0.processName, r8);
        r4.mProfileData.setProfileProc(r0);
        r4.mProfileType = r9;
        r1 = r8.profileFd;
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x0064, code lost:
        r1 = r1.dup();
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x0067, code lost:
        r1 = null;
     */
    /* JADX WARN: Removed duplicated region for block: B:35:0x008b  */
    /* JADX WARN: Removed duplicated region for block: B:48:0x00a4 A[DONT_GENERATE] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean profileControl(java.lang.String r5, int r6, boolean r7, android.app.ProfilerInfo r8, int r9) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 215
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerService.profileControl(java.lang.String, int, boolean, android.app.ProfilerInfo, int):boolean");
    }

    private ProcessRecord findProcessLocked(String process, int userId, String callName) {
        int userId2 = this.mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, 2, callName, null);
        ProcessRecord proc = null;
        try {
            int pid = Integer.parseInt(process);
            synchronized (this.mPidsSelfLocked) {
                proc = this.mPidsSelfLocked.get(pid);
            }
        } catch (NumberFormatException e) {
        }
        if (proc == null) {
            ArrayMap<String, SparseArray<ProcessRecord>> all = this.mProcessList.mProcessNames.getMap();
            SparseArray<ProcessRecord> procs = all.get(process);
            if (procs != null && procs.size() > 0) {
                ProcessRecord proc2 = procs.valueAt(0);
                ProcessRecord proc3 = proc2;
                if (userId2 != -1 && proc3.userId != userId2) {
                    for (int i = 1; i < procs.size(); i++) {
                        ProcessRecord thisProc = procs.valueAt(i);
                        if (thisProc.userId == userId2) {
                            return thisProc;
                        }
                    }
                    return proc3;
                }
                return proc3;
            }
            return proc;
        }
        return proc;
    }

    /* JADX WARN: Not initialized variable reg: 3, insn: 0x00bb: MOVE  (r4 I:??[OBJECT, ARRAY]) = (r3 I:??[OBJECT, ARRAY] A[D('fd' android.os.ParcelFileDescriptor)]), block:B:54:0x00bb */
    public boolean dumpHeap(String process, int userId, boolean managed, boolean mallocInfo, boolean runGc, String path, ParcelFileDescriptor fd, RemoteCallback finishCallback) {
        ParcelFileDescriptor fd2;
        ParcelFileDescriptor fd3;
        RemoteException remoteException;
        try {
            try {
                try {
                    synchronized (this) {
                        try {
                            boostPriorityForLockedSection();
                        } catch (Throwable th) {
                            th = th;
                        }
                        try {
                            if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") == 0) {
                                if (fd != null) {
                                    ProcessRecord proc = findProcessLocked(process, userId, "dumpHeap");
                                    if (proc == null || proc.thread == null) {
                                        throw new IllegalArgumentException("Unknown process: " + process);
                                    }
                                    boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                                    if (!isDebuggable && (proc.info.flags & 2) == 0) {
                                        throw new SecurityException("Process not debuggable: " + proc);
                                    }
                                    proc.thread.dumpHeap(managed, mallocInfo, runGc, path, fd, finishCallback);
                                    ParcelFileDescriptor fd4 = null;
                                    resetPriorityAfterLockedSection();
                                    if (0 != 0) {
                                        try {
                                            fd4.close();
                                        } catch (IOException e) {
                                        }
                                    }
                                    return true;
                                }
                                throw new IllegalArgumentException("null fd");
                            }
                            throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
                        } catch (Throwable th2) {
                            th = th2;
                            try {
                                resetPriorityAfterLockedSection();
                                throw th;
                            } catch (RemoteException e2) {
                                throw new IllegalStateException("Process disappeared");
                            }
                        }
                    }
                } catch (RemoteException e3) {
                    throw new IllegalStateException("Process disappeared");
                } catch (Throwable th3) {
                    fd3 = fd;
                    remoteException = th3;
                    if (fd3 != null) {
                        try {
                            fd3.close();
                        } catch (IOException e4) {
                        }
                    }
                    throw remoteException;
                }
            } catch (Throwable th4) {
                th = th4;
            }
        } catch (Throwable e5) {
            fd3 = fd2;
            remoteException = e5;
        }
    }

    public void setDumpHeapDebugLimit(String processName, int uid, long maxMemSize, String reportPackage) {
        String processName2;
        int uid2;
        String processName3;
        int uid3;
        if (processName != null) {
            enforceCallingPermission("android.permission.SET_DEBUG_APP", "setDumpHeapDebugLimit()");
            processName3 = processName;
            uid3 = uid;
        } else {
            synchronized (this.mPidsSelfLocked) {
                ProcessRecord proc = this.mPidsSelfLocked.get(Binder.getCallingPid());
                if (proc == null) {
                    throw new SecurityException("No process found for calling pid " + Binder.getCallingPid());
                }
                if (!Build.IS_DEBUGGABLE && (proc.info.flags & 2) == 0) {
                    throw new SecurityException("Not running a debuggable build");
                }
                processName2 = proc.processName;
                uid2 = proc.uid;
                if (reportPackage != null && !proc.pkgList.containsKey(reportPackage)) {
                    throw new SecurityException("Package " + reportPackage + " is not running in " + proc);
                }
            }
            processName3 = processName2;
            uid3 = uid2;
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (maxMemSize > 0) {
                    this.mMemWatchProcesses.put(processName3, uid3, new Pair(Long.valueOf(maxMemSize), reportPackage));
                } else if (uid3 != 0) {
                    this.mMemWatchProcesses.remove(processName3, uid3);
                } else {
                    this.mMemWatchProcesses.getMap().remove(processName3);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void dumpHeapFinished(String path) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (Binder.getCallingPid() != this.mMemWatchDumpPid) {
                    Slog.w("ActivityManager", "dumpHeapFinished: Calling pid " + Binder.getCallingPid() + " does not match last pid " + this.mMemWatchDumpPid);
                    resetPriorityAfterLockedSection();
                    return;
                }
                if (this.mMemWatchDumpFile != null && this.mMemWatchDumpFile.equals(path)) {
                    if (ActivityManagerDebugConfig.DEBUG_PSS) {
                        Slog.d("ActivityManager", "Dump heap finished for " + path);
                    }
                    this.mHandler.sendEmptyMessage(50);
                    Runtime.getRuntime().gc();
                    resetPriorityAfterLockedSection();
                    return;
                }
                Slog.w("ActivityManager", "dumpHeapFinished: Calling path " + path + " does not match last path " + this.mMemWatchDumpFile);
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // com.android.server.Watchdog.Monitor
    public void monitor() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onCoreSettingsChange(Bundle settings) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mProcessList.updateCoreSettingsLocked(settings);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean startUserInBackground(int userId) {
        return startUserInBackgroundWithListener(userId, null);
    }

    public boolean startUserInBackgroundWithListener(int userId, IProgressListener unlockListener) {
        return this.mUserController.lambda$startUser$8$UserController(userId, false, unlockListener);
    }

    public boolean startUserInForegroundWithListener(int userId, IProgressListener unlockListener) {
        return this.mUserController.lambda$startUser$8$UserController(userId, true, unlockListener);
    }

    public boolean unlockUser(int userId, byte[] token, byte[] secret, IProgressListener listener) {
        return this.mUserController.unlockUser(userId, token, secret, listener);
    }

    public boolean switchUser(int targetUserId) {
        return this.mUserController.switchUser(targetUserId);
    }

    public int stopUser(int userId, boolean force, IStopUserCallback callback) {
        return this.mUserController.stopUser(userId, force, callback, null);
    }

    public UserInfo getCurrentUser() {
        return this.mUserController.getCurrentUser();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getStartedUserState(int userId) {
        UserState userState = this.mUserController.getStartedUserState(userId);
        return UserState.stateToString(userState.state);
    }

    public boolean isUserRunning(int userId, int flags) {
        if (!this.mUserController.isSameProfileGroup(userId, UserHandle.getCallingUserId()) && checkCallingPermission("android.permission.INTERACT_ACROSS_USERS") != 0) {
            String msg = "Permission Denial: isUserRunning() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        return this.mUserController.isUserRunning(userId, flags);
    }

    public int[] getRunningUserIds() {
        if (checkCallingPermission("android.permission.INTERACT_ACROSS_USERS") != 0) {
            String msg = "Permission Denial: isUserRunning() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.INTERACT_ACROSS_USERS";
            Slog.w("ActivityManager", msg);
            throw new SecurityException(msg);
        }
        return this.mUserController.getStartedUserArray();
    }

    public void registerUserSwitchObserver(IUserSwitchObserver observer, String name) {
        this.mUserController.registerUserSwitchObserver(observer, name);
    }

    public void unregisterUserSwitchObserver(IUserSwitchObserver observer) {
        this.mUserController.unregisterUserSwitchObserver(observer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ApplicationInfo getAppInfoForUser(ApplicationInfo info, int userId) {
        if (info == null) {
            return null;
        }
        ApplicationInfo newInfo = new ApplicationInfo(info);
        newInfo.initForUser(userId);
        return newInfo;
    }

    public boolean isUserStopped(int userId) {
        return this.mUserController.getStartedUserState(userId) == null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityInfo getActivityInfoForUser(ActivityInfo aInfo, int userId) {
        if (aInfo == null || (userId < 1 && aInfo.applicationInfo.uid < 100000)) {
            return aInfo;
        }
        ActivityInfo info = new ActivityInfo(aInfo);
        info.applicationInfo = getAppInfoForUser(info.applicationInfo, userId);
        return info;
    }

    private boolean processSanityChecksLocked(ProcessRecord process) {
        if (process == null || process.thread == null) {
            return false;
        }
        boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
        if (!isDebuggable && (process.info.flags & 2) == 0) {
            return false;
        }
        return true;
    }

    public boolean startBinderTracking() throws RemoteException {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mBinderTransactionTrackingEnabled = true;
                if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") != 0) {
                    throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
                }
                for (int i = 0; i < this.mProcessList.mLruProcesses.size(); i++) {
                    ProcessRecord process = this.mProcessList.mLruProcesses.get(i);
                    if (processSanityChecksLocked(process)) {
                        try {
                            process.thread.startBinderTracking();
                        } catch (RemoteException e) {
                            Log.v("ActivityManager", "Process disappared");
                        }
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return true;
    }

    public boolean stopBinderTrackingAndDump(ParcelFileDescriptor fd) throws RemoteException {
        TransferPipe tp;
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                this.mBinderTransactionTrackingEnabled = false;
                if (checkCallingPermission("android.permission.SET_ACTIVITY_WATCHER") != 0) {
                    throw new SecurityException("Requires permission android.permission.SET_ACTIVITY_WATCHER");
                }
                if (fd == null) {
                    throw new IllegalArgumentException("null fd");
                }
                FastPrintWriter fastPrintWriter = new FastPrintWriter(new FileOutputStream(fd.getFileDescriptor()));
                fastPrintWriter.println("Binder transaction traces for all processes.\n");
                Iterator<ProcessRecord> it = this.mProcessList.mLruProcesses.iterator();
                while (it.hasNext()) {
                    ProcessRecord process = it.next();
                    if (processSanityChecksLocked(process)) {
                        fastPrintWriter.println("Traces for process: " + process.processName);
                        fastPrintWriter.flush();
                        try {
                            tp = new TransferPipe();
                        } catch (RemoteException e) {
                            fastPrintWriter.println("Got a RemoteException while dumping IPC traces from " + process + ".  Exception: " + e);
                            fastPrintWriter.flush();
                        } catch (IOException e2) {
                            fastPrintWriter.println("Failure while dumping IPC traces from " + process + ".  Exception: " + e2);
                            fastPrintWriter.flush();
                        }
                        try {
                            process.thread.stopBinderTrackingAndDump(tp.getWriteFd());
                            tp.go(fd.getFileDescriptor());
                            tp.kill();
                        } catch (Throwable th) {
                            tp.kill();
                            throw th;
                            break;
                        }
                    }
                }
                fd = null;
            }
            resetPriorityAfterLockedSection();
            return true;
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException e3) {
                }
            }
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    public final class LocalService extends ActivityManagerInternal {
        public LocalService() {
        }

        public String checkContentProviderAccess(String authority, int userId) {
            return ActivityManagerService.this.checkContentProviderAccess(authority, userId);
        }

        public void onWakefulnessChanged(int wakefulness) {
            ActivityManagerService.this.onWakefulnessChanged(wakefulness);
        }

        public boolean startIsolatedProcess(String entryPoint, String[] entryPointArgs, String processName, String abiOverride, int uid, Runnable crashHandler) {
            return ActivityManagerService.this.startIsolatedProcess(entryPoint, entryPointArgs, processName, abiOverride, uid, crashHandler);
        }

        public void killForegroundAppsForUser(int userHandle) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ArrayList<ProcessRecord> procs = new ArrayList<>();
                    int NP = ActivityManagerService.this.mProcessList.mProcessNames.getMap().size();
                    for (int ip = 0; ip < NP; ip++) {
                        SparseArray<ProcessRecord> apps = (SparseArray) ActivityManagerService.this.mProcessList.mProcessNames.getMap().valueAt(ip);
                        int NA = apps.size();
                        for (int ia = 0; ia < NA; ia++) {
                            ProcessRecord app = apps.valueAt(ia);
                            if (!app.isPersistent() && (app.removed || (app.userId == userHandle && app.hasForegroundActivities()))) {
                                procs.add(app);
                            }
                        }
                    }
                    int N = procs.size();
                    for (int i = 0; i < N; i++) {
                        ActivityManagerService.this.mProcessList.removeProcessLocked(procs.get(i), false, true, "kill all fg");
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void setPendingIntentWhitelistDuration(IIntentSender target, IBinder whitelistToken, long duration) {
            ActivityManagerService.this.mPendingIntentController.setPendingIntentWhitelistDuration(target, whitelistToken, duration);
        }

        public void setPendingIntentAllowBgActivityStarts(IIntentSender target, IBinder whitelistToken, int flags) {
            if (!(target instanceof PendingIntentRecord)) {
                Slog.w("ActivityManager", "setPendingIntentAllowBgActivityStarts(): not a PendingIntentRecord: " + target);
                return;
            }
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ((PendingIntentRecord) target).setAllowBgActivityStarts(whitelistToken, flags);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void clearPendingIntentAllowBgActivityStarts(IIntentSender target, IBinder whitelistToken) {
            if (!(target instanceof PendingIntentRecord)) {
                Slog.w("ActivityManager", "clearPendingIntentAllowBgActivityStarts(): not a PendingIntentRecord: " + target);
                return;
            }
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ((PendingIntentRecord) target).clearAllowBgActivityStarts(whitelistToken);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void setDeviceIdleWhitelist(int[] allAppids, int[] exceptIdleAppids) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.mDeviceIdleWhitelist = allAppids;
                    ActivityManagerService.this.mDeviceIdleExceptIdleWhitelist = exceptIdleAppids;
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void updateDeviceIdleTempWhitelist(int[] appids, int changingAppId, boolean adding) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.mDeviceIdleTempWhitelist = appids;
                    ActivityManagerService.this.setAppIdTempWhitelistStateLocked(changingAppId, adding);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public int getUidProcessState(int uid) {
            return ActivityManagerService.this.getUidState(uid);
        }

        public boolean isSystemReady() {
            return ActivityManagerService.this.mSystemReady;
        }

        public void setHasOverlayUi(int pid, boolean hasOverlayUi) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    synchronized (ActivityManagerService.this.mPidsSelfLocked) {
                        ProcessRecord pr = ActivityManagerService.this.mPidsSelfLocked.get(pid);
                        if (pr == null) {
                            Slog.w("ActivityManager", "setHasOverlayUi called on unknown pid: " + pid);
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        } else if (pr.hasOverlayUi() == hasOverlayUi) {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        } else {
                            pr.setHasOverlayUi(hasOverlayUi);
                            ActivityManagerService.this.updateOomAdjLocked(pr, true, "updateOomAdj_uiVisibility");
                            ActivityManagerService.resetPriorityAfterLockedSection();
                        }
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        public void notifyNetworkPolicyRulesUpdated(int uid, long procStateSeq) {
            if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                Slog.d(ActivityManagerService.TAG_NETWORK, "Got update from NPMS for uid: " + uid + " seq: " + procStateSeq);
            }
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    UidRecord record = ActivityManagerService.this.mProcessList.getUidRecordLocked(uid);
                    if (record == null) {
                        if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                            Slog.d(ActivityManagerService.TAG_NETWORK, "No active uidRecord for uid: " + uid + " procStateSeq: " + procStateSeq);
                        }
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    synchronized (record.networkStateLock) {
                        if (record.lastNetworkUpdatedProcStateSeq >= procStateSeq) {
                            if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                                Slog.d(ActivityManagerService.TAG_NETWORK, "procStateSeq: " + procStateSeq + " has already been handled for uid: " + uid);
                            }
                            return;
                        }
                        record.lastNetworkUpdatedProcStateSeq = procStateSeq;
                        if (record.curProcStateSeq > procStateSeq) {
                            if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                                Slog.d(ActivityManagerService.TAG_NETWORK, "No need to handle older seq no., Uid: " + uid + ", curProcstateSeq: " + record.curProcStateSeq + ", procStateSeq: " + procStateSeq);
                            }
                            return;
                        }
                        if (record.waitingForNetwork) {
                            if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                                Slog.d(ActivityManagerService.TAG_NETWORK, "Notifying all blocking threads for uid: " + uid + ", procStateSeq: " + procStateSeq);
                            }
                            record.networkStateLock.notifyAll();
                        }
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        public boolean isRuntimeRestarted() {
            return ActivityManagerService.this.mSystemServiceManager.isRuntimeRestarted();
        }

        public boolean canStartMoreUsers() {
            return ActivityManagerService.this.mUserController.canStartMoreUsers();
        }

        public void setSwitchingFromSystemUserMessage(String switchingFromSystemUserMessage) {
            ActivityManagerService.this.mUserController.setSwitchingFromSystemUserMessage(switchingFromSystemUserMessage);
        }

        public void setSwitchingToSystemUserMessage(String switchingToSystemUserMessage) {
            ActivityManagerService.this.mUserController.setSwitchingToSystemUserMessage(switchingToSystemUserMessage);
        }

        public int getMaxRunningUsers() {
            return ActivityManagerService.this.mUserController.mMaxRunningUsers;
        }

        public boolean isUidActive(int uid) {
            boolean isUidActiveLocked;
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    isUidActiveLocked = ActivityManagerService.this.isUidActiveLocked(uid);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            return isUidActiveLocked;
        }

        public List<ProcessMemoryState> getMemoryStateForProcesses() {
            List<ProcessMemoryState> processMemoryStates = new ArrayList<>();
            synchronized (ActivityManagerService.this.mPidsSelfLocked) {
                int size = ActivityManagerService.this.mPidsSelfLocked.size();
                for (int i = 0; i < size; i++) {
                    ProcessRecord r = ActivityManagerService.this.mPidsSelfLocked.valueAt(i);
                    processMemoryStates.add(new ProcessMemoryState(r.uid, r.pid, r.processName, r.curAdj));
                }
            }
            return processMemoryStates;
        }

        public int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll, int allowMode, String name, String callerPackage) {
            return ActivityManagerService.this.mUserController.handleIncomingUser(callingPid, callingUid, userId, allowAll, allowMode, name, callerPackage);
        }

        public void enforceCallingPermission(String permission, String func) {
            ActivityManagerService.this.enforceCallingPermission(permission, func);
        }

        public int getCurrentUserId() {
            return ActivityManagerService.this.mUserController.getCurrentUserId();
        }

        public boolean isUserRunning(int userId, int flags) {
            return ActivityManagerService.this.mUserController.isUserRunning(userId, flags);
        }

        public void trimApplications() {
            ActivityManagerService.this.trimApplications("updateOomAdj_activityChange");
        }

        public void killProcessesForRemovedTask(ArrayList<Object> procsToKill) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    for (int i = 0; i < procsToKill.size(); i++) {
                        WindowProcessController wpc = (WindowProcessController) procsToKill.get(i);
                        ProcessRecord pr = (ProcessRecord) wpc.mOwner;
                        if (pr.setSchedGroup == 0 && pr.curReceivers.isEmpty()) {
                            pr.kill("remove task", true);
                        } else {
                            pr.waitingToKill = "remove task";
                        }
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void killProcess(String processName, int uid, String reason) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ProcessRecord proc = ActivityManagerService.this.getProcessRecordLocked(processName, uid, true);
                    if (proc != null) {
                        ActivityManagerService.this.mProcessList.removeProcessLocked(proc, false, true, reason);
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public boolean hasRunningActivity(int uid, String packageName) {
            if (packageName == null) {
                return false;
            }
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    for (int i = 0; i < ActivityManagerService.this.mProcessList.mLruProcesses.size(); i++) {
                        ProcessRecord pr = ActivityManagerService.this.mProcessList.mLruProcesses.get(i);
                        if (pr.uid == uid && pr.getWindowProcessController().hasRunningActivity(packageName)) {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            return true;
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return false;
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        public void updateOomAdj() {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.updateOomAdjLocked("updateOomAdj_meh");
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void updateCpuStats() {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.updateCpuStats();
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void updateBatteryStats(ComponentName activity, int uid, int userId, boolean resumed) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.updateBatteryStats(activity, uid, userId, resumed);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void updateActivityUsageStats(ComponentName activity, int userId, int event, IBinder appToken, ComponentName taskRoot) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.updateActivityUsageStats(activity, userId, event, appToken, taskRoot);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void updateForegroundTimeIfOnBattery(String packageName, int uid, long cpuTimeDiff) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    if (!ActivityManagerService.this.mBatteryStatsService.isOnBattery()) {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    BatteryStatsImpl bsi = ActivityManagerService.this.mBatteryStatsService.getActiveStatistics();
                    synchronized (bsi) {
                        BatteryStatsImpl.Uid.Proc ps = bsi.getProcessStatsLocked(uid, packageName);
                        if (ps != null) {
                            ps.addForegroundTimeLocked(cpuTimeDiff);
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        public void sendForegroundProfileChanged(int userId) {
            ActivityManagerService.this.mUserController.sendForegroundProfileChanged(userId);
        }

        public boolean shouldConfirmCredentials(int userId) {
            return ActivityManagerService.this.mUserController.shouldConfirmCredentials(userId);
        }

        public int[] getCurrentProfileIds() {
            return ActivityManagerService.this.mUserController.getCurrentProfileIds();
        }

        public UserInfo getCurrentUser() {
            return ActivityManagerService.this.mUserController.getCurrentUser();
        }

        public void ensureNotSpecialUser(int userId) {
            ActivityManagerService.this.mUserController.ensureNotSpecialUser(userId);
        }

        public boolean isCurrentProfile(int userId) {
            return ActivityManagerService.this.mUserController.isCurrentProfile(userId);
        }

        public boolean hasStartedUserState(int userId) {
            return ActivityManagerService.this.mUserController.hasStartedUserState(userId);
        }

        public void finishUserSwitch(Object uss) {
            ActivityManagerService.this.mUserController.finishUserSwitch((UserState) uss);
        }

        public void scheduleAppGcs() {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.scheduleAppGcsLocked();
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public int getTaskIdForActivity(IBinder token, boolean onlyRoot) {
            int taskForActivity;
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    taskForActivity = ActivityManagerService.this.getTaskForActivity(token, onlyRoot);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            return taskForActivity;
        }

        public ActivityPresentationInfo getActivityPresentationInfo(IBinder token) {
            int displayId = -1;
            try {
                displayId = ActivityManagerService.this.mActivityTaskManager.getActivityDisplayId(token);
            } catch (RemoteException e) {
            }
            return new ActivityPresentationInfo(ActivityManagerService.this.mActivityTaskManager.getTaskForActivity(token, false), displayId, ActivityManagerService.this.mActivityTaskManager.getActivityClassForToken(token));
        }

        public void setBooting(boolean booting) {
            ActivityManagerService.this.mBooting = booting;
        }

        public boolean isBooting() {
            return ActivityManagerService.this.mBooting;
        }

        public void setBooted(boolean booted) {
            ActivityManagerService.this.mBooted = booted;
        }

        public boolean isBooted() {
            return ActivityManagerService.this.mBooted;
        }

        public void finishBooting() {
            ActivityManagerService.this.finishBooting();
        }

        public void tempWhitelistForPendingIntent(int callerPid, int callerUid, int targetUid, long duration, String tag) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.tempWhitelistForPendingIntentLocked(callerPid, callerUid, targetUid, duration, tag);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public int broadcastIntentInPackage(String packageName, int uid, int realCallingUid, int realCallingPid, Intent intent, String resolvedType, IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras, String requiredPermission, Bundle bOptions, boolean serialized, boolean sticky, int userId, boolean allowBackgroundActivityStarts) {
            int broadcastIntentInPackage;
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    broadcastIntentInPackage = ActivityManagerService.this.broadcastIntentInPackage(packageName, uid, realCallingUid, realCallingPid, intent, resolvedType, resultTo, resultCode, resultData, resultExtras, requiredPermission, bOptions, serialized, sticky, userId, allowBackgroundActivityStarts);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            return broadcastIntentInPackage;
        }

        public ComponentName startServiceInPackage(int uid, Intent service, String resolvedType, boolean fgRequired, String callingPackage, int userId, boolean allowBackgroundActivityStarts) throws TransactionTooLargeException {
            synchronized (ActivityManagerService.this) {
                try {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("startServiceInPackage: ");
                            try {
                                sb.append(service);
                                sb.append(" type=");
                                sb.append(resolvedType);
                                Slog.v("ActivityManager", sb.toString());
                            } catch (Throwable th) {
                                th = th;
                                ActivityManagerService.resetPriorityAfterLockedSection();
                                throw th;
                            }
                        }
                        long origId = Binder.clearCallingIdentity();
                        ComponentName res = ActivityManagerService.this.mServices.startServiceLocked(null, service, resolvedType, -1, uid, fgRequired, callingPackage, userId, allowBackgroundActivityStarts);
                        Binder.restoreCallingIdentity(origId);
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        return res;
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
        }

        public void disconnectActivityFromServices(Object connectionHolder, Object conns) {
            ActivityServiceConnectionsHolder holder = (ActivityServiceConnectionsHolder) connectionHolder;
            HashSet<ConnectionRecord> toDisconnect = (HashSet) conns;
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    Iterator<ConnectionRecord> it = toDisconnect.iterator();
                    while (it.hasNext()) {
                        ConnectionRecord cr = it.next();
                        ActivityManagerService.this.mServices.removeConnectionLocked(cr, null, holder);
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void cleanUpServices(int userId, ComponentName component, Intent baseIntent) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.mServices.cleanUpServices(userId, component, baseIntent);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public ActivityInfo getActivityInfoForUser(ActivityInfo aInfo, int userId) {
            return ActivityManagerService.this.getActivityInfoForUser(aInfo, userId);
        }

        public void ensureBootCompleted() {
            ActivityManagerService.this.ensureBootCompleted();
        }

        public void updateOomLevelsForDisplay(int displayId) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    if (ActivityManagerService.this.mWindowManager != null) {
                        ActivityManagerService.this.mProcessList.applyDisplaySize(ActivityManagerService.this.mWindowManager);
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public boolean isActivityStartsLoggingEnabled() {
            return ActivityManagerService.this.mConstants.mFlagActivityStartsLoggingEnabled;
        }

        public boolean isBackgroundActivityStartsEnabled() {
            return ActivityManagerService.this.mConstants.mFlagBackgroundActivityStartsEnabled;
        }

        public void reportCurKeyguardUsageEvent(boolean keyguardShowing) {
            int i;
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService activityManagerService = ActivityManagerService.this;
                    if (keyguardShowing) {
                        i = 17;
                    } else {
                        i = 18;
                    }
                    activityManagerService.reportGlobalUsageEventLocked(i);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public long inputDispatchingTimedOut(int pid, boolean aboveSystem, String reason) {
            long inputDispatchingTimedOut;
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    inputDispatchingTimedOut = ActivityManagerService.this.inputDispatchingTimedOut(pid, aboveSystem, reason);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            return inputDispatchingTimedOut;
        }

        public boolean inputDispatchingTimedOut(Object proc, String activityShortComponentName, ApplicationInfo aInfo, String parentShortComponentName, Object parentProc, boolean aboveSystem, String reason) {
            return ActivityManagerService.this.inputDispatchingTimedOut((ProcessRecord) proc, activityShortComponentName, aInfo, parentShortComponentName, (WindowProcessController) parentProc, aboveSystem, reason);
        }

        public void broadcastGlobalConfigurationChanged(int changes, boolean initLocale) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    Intent intent = new Intent("android.intent.action.CONFIGURATION_CHANGED");
                    intent.addFlags(1881145344);
                    ActivityManagerService.this.broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), -1);
                    if ((changes & 4) != 0) {
                        Intent intent2 = new Intent("android.intent.action.LOCALE_CHANGED");
                        intent2.addFlags(287309824);
                        if (initLocale || !ActivityManagerService.this.mProcessesReady) {
                            intent2.addFlags(1073741824);
                        }
                        ActivityManagerService.this.broadcastIntentLocked(null, null, intent2, null, null, 0, null, null, null, -1, null, false, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), -1);
                    }
                    if (!initLocale && isSplitConfigurationChange(changes)) {
                        Intent intent3 = new Intent("android.intent.action.SPLIT_CONFIGURATION_CHANGED");
                        intent3.addFlags(553648128);
                        String[] permissions = {"android.permission.INSTALL_PACKAGES"};
                        ActivityManagerService.this.broadcastIntentLocked(null, null, intent3, null, null, 0, null, null, permissions, -1, null, false, false, ActivityManagerService.MY_PID, 1000, Binder.getCallingUid(), Binder.getCallingPid(), -1);
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        private boolean isSplitConfigurationChange(int configDiff) {
            return (configDiff & 4100) != 0;
        }

        public void broadcastCloseSystemDialogs(String reason) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    Intent intent = new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS");
                    intent.addFlags(1342177280);
                    if (reason != null) {
                        intent.putExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY, reason);
                    }
                    ActivityManagerService.this.broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null, -1, null, false, false, -1, 1000, Binder.getCallingUid(), Binder.getCallingPid(), -1);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void killAllBackgroundProcessesExcept(int minTargetSdk, int maxProcState) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.killAllBackgroundProcessesExcept(minTargetSdk, maxProcState);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void startProcess(String processName, ApplicationInfo info, boolean knownToBeDead, String hostingType, ComponentName hostingName) {
            try {
                if (Trace.isTagEnabled(64L)) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("startProcess:");
                    try {
                        sb.append(processName);
                        Trace.traceBegin(64L, sb.toString());
                    } catch (Throwable th) {
                        th = th;
                        Trace.traceEnd(64L);
                        throw th;
                    }
                }
            } catch (Throwable th2) {
                th = th2;
            }
            try {
                try {
                    synchronized (ActivityManagerService.this) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            ActivityManagerService.this.startProcessLocked(processName, info, knownToBeDead, 0, new HostingRecord(hostingType, hostingName), false, false, true);
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            Trace.traceEnd(64L);
                        } catch (Throwable th3) {
                            th = th3;
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                } catch (Throwable th4) {
                    th = th4;
                }
            } catch (Throwable th5) {
                th = th5;
                Trace.traceEnd(64L);
                throw th;
            }
        }

        public void setDebugFlagsForStartingActivity(ActivityInfo aInfo, int startFlags, ProfilerInfo profilerInfo, Object wmLock) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    synchronized (wmLock) {
                        if ((startFlags & 2) != 0) {
                            ActivityManagerService.this.setDebugApp(aInfo.processName, true, false);
                        }
                        if ((startFlags & 8) != 0) {
                            ActivityManagerService.this.setNativeDebuggingAppLocked(aInfo.applicationInfo, aInfo.processName);
                        }
                        if ((startFlags & 4) != 0) {
                            ActivityManagerService.this.setTrackAllocationApp(aInfo.applicationInfo, aInfo.processName);
                        }
                        if (profilerInfo != null) {
                            ActivityManagerService.this.setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo);
                        }
                        wmLock.notify();
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public int getStorageMountMode(int pid, int uid) {
            int i;
            if (uid == ActivityManagerService.NATIVE_DUMP_TIMEOUT_MS || uid == 0) {
                return 6;
            }
            synchronized (ActivityManagerService.this.mPidsSelfLocked) {
                ProcessRecord pr = ActivityManagerService.this.mPidsSelfLocked.get(pid);
                i = pr == null ? 0 : pr.mountMode;
            }
            return i;
        }

        public boolean isAppForeground(int uid) {
            return ActivityManagerService.this.isAppForeground(uid);
        }

        public boolean isAppBad(ApplicationInfo info) {
            return ActivityManagerService.this.isAppBad(info);
        }

        public void clearPendingBackup(int userId) {
            ActivityManagerService.this.clearPendingBackup(userId);
        }

        public void prepareForPossibleShutdown() {
            ActivityManagerService.this.prepareForPossibleShutdown();
        }

        public boolean hasRunningForegroundService(int uid, int foregroundServicetype) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    for (int i = 0; i < ActivityManagerService.this.mProcessList.mLruProcesses.size(); i++) {
                        ProcessRecord pr = ActivityManagerService.this.mProcessList.mLruProcesses.get(i);
                        if (pr.uid == uid && (pr.getForegroundServiceTypes() & foregroundServicetype) != 0) {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            return true;
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return false;
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        public boolean hasForegroundServiceNotification(String pkg, int userId, String channelId) {
            boolean hasForegroundServiceNotificationLocked;
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    hasForegroundServiceNotificationLocked = ActivityManagerService.this.mServices.hasForegroundServiceNotificationLocked(pkg, userId, channelId);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            return hasForegroundServiceNotificationLocked;
        }

        public void stopForegroundServicesForChannel(String pkg, int userId, String channelId) {
            synchronized (ActivityManagerService.this) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActivityManagerService.this.mServices.stopForegroundServicesForChannelLocked(pkg, userId, channelId);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        public void registerProcessObserver(IProcessObserver processObserver) {
            ActivityManagerService.this.registerProcessObserver(processObserver);
        }

        public void unregisterProcessObserver(IProcessObserver processObserver) {
            ActivityManagerService.this.unregisterProcessObserver(processObserver);
        }

        public boolean isActiveInstrumentation(int uid) {
            return ActivityManagerService.this.isActiveInstrumentation(uid);
        }
    }

    long inputDispatchingTimedOut(int pid, boolean aboveSystem, String reason) {
        ProcessRecord proc;
        long timeout;
        if (checkCallingPermission("android.permission.FILTER_EVENTS") != 0) {
            throw new SecurityException("Requires permission android.permission.FILTER_EVENTS");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                synchronized (this.mPidsSelfLocked) {
                    proc = this.mPidsSelfLocked.get(pid);
                }
                timeout = proc != null ? proc.getInputDispatchingTimeout() : MONITOR_CPU_MIN_TIME;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        if (inputDispatchingTimedOut(proc, null, null, null, null, aboveSystem, reason)) {
            return -1L;
        }
        return timeout;
    }

    boolean inputDispatchingTimedOut(final ProcessRecord proc, final String activityShortComponentName, final ApplicationInfo aInfo, final String parentShortComponentName, final WindowProcessController parentProcess, final boolean aboveSystem, String reason) {
        String annotation;
        if (checkCallingPermission("android.permission.FILTER_EVENTS") != 0) {
            throw new SecurityException("Requires permission android.permission.FILTER_EVENTS");
        }
        if (reason == null) {
            annotation = "Input dispatching timed out";
        } else {
            annotation = "Input dispatching timed out (" + reason + ")";
        }
        if (proc != null) {
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    if (proc.isDebugging()) {
                        resetPriorityAfterLockedSection();
                        return false;
                    } else if (proc.getActiveInstrumentation() != null) {
                        Bundle info = new Bundle();
                        info.putString("shortMsg", "keyDispatchingTimedOut");
                        info.putString("longMsg", annotation);
                        finishInstrumentationLocked(proc, 0, info);
                        resetPriorityAfterLockedSection();
                        return true;
                    } else {
                        resetPriorityAfterLockedSection();
                        final String str = annotation;
                        this.mHandler.post(new Runnable() { // from class: com.android.server.am.-$$Lambda$ActivityManagerService$suSj-_Gky16tELPffKx_qENL8g0
                            @Override // java.lang.Runnable
                            public final void run() {
                                ProcessRecord.this.appNotResponding(activityShortComponentName, aInfo, parentShortComponentName, parentProcess, aboveSystem, str);
                            }
                        });
                    }
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }
        return true;
    }

    public void waitForNetworkStateUpdate(long procStateSeq) {
        int callingUid = Binder.getCallingUid();
        if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
            Slog.d(TAG_NETWORK, "Called from " + callingUid + " to wait for seq: " + procStateSeq);
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                UidRecord record = this.mProcessList.getUidRecordLocked(callingUid);
                if (record == null) {
                    resetPriorityAfterLockedSection();
                    return;
                }
                resetPriorityAfterLockedSection();
                synchronized (record.networkStateLock) {
                    if (record.lastDispatchedProcStateSeq < procStateSeq) {
                        if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                            Slog.d(TAG_NETWORK, "Uid state change for seq no. " + procStateSeq + " is not dispatched to NPMS yet, so don't wait. Uid: " + callingUid + " lastProcStateSeqDispatchedToObservers: " + record.lastDispatchedProcStateSeq);
                        }
                    } else if (record.curProcStateSeq > procStateSeq) {
                        if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                            Slog.d(TAG_NETWORK, "Ignore the wait requests for older seq numbers. Uid: " + callingUid + ", curProcStateSeq: " + record.curProcStateSeq + ", procStateSeq: " + procStateSeq);
                        }
                    } else if (record.lastNetworkUpdatedProcStateSeq >= procStateSeq) {
                        if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                            Slog.d(TAG_NETWORK, "Network rules have been already updated for seq no. " + procStateSeq + ", so no need to wait. Uid: " + callingUid + ", lastProcStateSeqWithUpdatedNetworkState: " + record.lastNetworkUpdatedProcStateSeq);
                        }
                    } else {
                        try {
                            if (ActivityManagerDebugConfig.DEBUG_NETWORK) {
                                Slog.d(TAG_NETWORK, "Starting to wait for the network rules update. Uid: " + callingUid + " procStateSeq: " + procStateSeq);
                            }
                            long startTime = SystemClock.uptimeMillis();
                            record.waitingForNetwork = true;
                            record.networkStateLock.wait(this.mWaitForNetworkTimeoutMs);
                            record.waitingForNetwork = false;
                            long totalTime = SystemClock.uptimeMillis() - startTime;
                            if (totalTime >= this.mWaitForNetworkTimeoutMs || ActivityManagerDebugConfig.DEBUG_NETWORK) {
                                Slog.w(TAG_NETWORK, "Total time waited for network rules to get updated: " + totalTime + ". Uid: " + callingUid + " procStateSeq: " + procStateSeq + " UidRec: " + record + " validateUidRec: " + this.mValidateUids.get(callingUid));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void waitForBroadcastIdle(PrintWriter pw) {
        BroadcastQueue[] broadcastQueueArr;
        enforceCallingPermission("android.permission.DUMP", "waitForBroadcastIdle()");
        while (true) {
            boolean idle = true;
            synchronized (this) {
                try {
                    boostPriorityForLockedSection();
                    for (BroadcastQueue queue : this.mBroadcastQueues) {
                        if (!queue.isIdle()) {
                            String msg = "Waiting for queue " + queue + " to become idle...";
                            pw.println(msg);
                            pw.println(queue.describeState());
                            pw.flush();
                            Slog.v("ActivityManager", msg);
                            queue.cancelDeferrals();
                            idle = false;
                        }
                    }
                } catch (Throwable th) {
                    resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            resetPriorityAfterLockedSection();
            if (idle) {
                pw.println("All broadcast queues are idle!");
                pw.flush();
                Slog.v("ActivityManager", "All broadcast queues are idle!");
                return;
            }
            SystemClock.sleep(1000L);
        }
    }

    public void killPackageDependents(String packageName, int userId) {
        enforceCallingPermission("android.permission.KILL_UID", "killPackageDependents()");
        if (packageName == null) {
            throw new NullPointerException("Cannot kill the dependents of a package without its name.");
        }
        long callingId = Binder.clearCallingIdentity();
        IPackageManager pm = AppGlobals.getPackageManager();
        int pkgUid = -1;
        try {
            pkgUid = pm.getPackageUid(packageName, 268435456, userId);
        } catch (RemoteException e) {
        }
        if (userId != -1 && pkgUid == -1) {
            throw new IllegalArgumentException("Cannot kill dependents of non-existing package " + packageName);
        }
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                ProcessList processList = this.mProcessList;
                int appId = UserHandle.getAppId(pkgUid);
                processList.killPackageProcessesLocked(packageName, appId, userId, 0, "dep: " + packageName);
            }
            resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public int restartUserInBackground(int userId) {
        return this.mUserController.restartUser(userId, false);
    }

    public void scheduleApplicationInfoChanged(List<String> packageNames, int userId) {
        enforceCallingPermission("android.permission.CHANGE_CONFIGURATION", "scheduleApplicationInfoChanged()");
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                updateApplicationInfoLocked(packageNames, userId);
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void updateSystemUiContext() {
        PackageManagerInternal packageManagerInternal;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                packageManagerInternal = getPackageManagerInternalLocked();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        ApplicationInfo ai = packageManagerInternal.getApplicationInfo(PackageManagerService.PLATFORM_PACKAGE_NAME, 1024, Binder.getCallingUid(), 0);
        ActivityThread.currentActivityThread().handleSystemApplicationInfoChanged(ai);
    }

    void updateApplicationInfoLocked(List<String> packagesToUpdate, int userId) {
        boolean updateFrameworkRes = packagesToUpdate.contains(PackageManagerService.PLATFORM_PACKAGE_NAME);
        if (updateFrameworkRes) {
            PackageParser.readConfigUseRoundIcon((Resources) null);
        }
        this.mProcessList.updateApplicationInfoLocked(packagesToUpdate, userId, updateFrameworkRes);
        if (updateFrameworkRes) {
            Executor executor = ActivityThread.currentActivityThread().getExecutor();
            final DisplayManagerInternal display = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
            if (display != null) {
                Objects.requireNonNull(display);
                executor.execute(new Runnable() { // from class: com.android.server.am.-$$Lambda$gATL8uvTPRd405IfefK1RL9bNqA
                    @Override // java.lang.Runnable
                    public final void run() {
                        display.onOverlayChanged();
                    }
                });
            }
            final WindowManagerService windowManagerService = this.mWindowManager;
            if (windowManagerService != null) {
                Objects.requireNonNull(windowManagerService);
                executor.execute(new Runnable() { // from class: com.android.server.am.-$$Lambda$5hokEl5hcign5FXeGZdl53qh2zg
                    @Override // java.lang.Runnable
                    public final void run() {
                        WindowManagerService.this.onOverlayChanged();
                    }
                });
            }
        }
    }

    public void attachAgent(String process, String path) {
        try {
            synchronized (this) {
                boostPriorityForLockedSection();
                ProcessRecord proc = findProcessLocked(process, 0, "attachAgent");
                if (proc == null || proc.thread == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }
                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable && (proc.info.flags & 2) == 0) {
                    throw new SecurityException("Process not debuggable: " + proc);
                }
                proc.thread.attachAgent(path);
            }
            resetPriorityAfterLockedSection();
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        }
    }

    public void prepareForPossibleShutdown() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (this.mUsageStatsService != null) {
                    this.mUsageStatsService.prepareForPossibleShutdown();
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class Injector {
        private NetworkManagementInternal mNmi;

        public Context getContext() {
            return null;
        }

        public AppOpsService getAppOpsService(File file, Handler handler) {
            return new AppOpsService(file, handler);
        }

        public Handler getUiHandler(ActivityManagerService service) {
            Objects.requireNonNull(service);
            return new UiHandler();
        }

        public boolean isNetworkRestrictedForUid(int uid) {
            if (ensureHasNetworkManagementInternal()) {
                return this.mNmi.isNetworkRestrictedForUid(uid);
            }
            return false;
        }

        private boolean ensureHasNetworkManagementInternal() {
            if (this.mNmi == null) {
                this.mNmi = (NetworkManagementInternal) LocalServices.getService(NetworkManagementInternal.class);
            }
            return this.mNmi != null;
        }
    }

    public void startDelegateShellPermissionIdentity(int delegateUid, String[] permissions) {
        if (UserHandle.getCallingAppId() != NATIVE_DUMP_TIMEOUT_MS && UserHandle.getCallingAppId() != 0) {
            throw new SecurityException("Only the shell can delegate its permissions");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (this.mAppOpsService.getAppOpsServiceDelegate() != getPackageManagerInternalLocked().getCheckPermissionDelegate()) {
                    throw new IllegalStateException("Bad shell delegate state");
                }
                if (this.mAppOpsService.getAppOpsServiceDelegate() != null) {
                    if (!(this.mAppOpsService.getAppOpsServiceDelegate() instanceof ShellDelegate)) {
                        throw new IllegalStateException("Bad shell delegate state");
                    }
                    ShellDelegate delegate = (ShellDelegate) this.mAppOpsService.getAppOpsServiceDelegate();
                    if (delegate.getDelegateUid() != delegateUid) {
                        throw new SecurityException("Shell can delegate permissions only to one instrumentation at a time");
                    }
                    delegate.setPermissions(permissions);
                    resetPriorityAfterLockedSection();
                    return;
                }
                int instrCount = this.mActiveInstrumentation.size();
                for (int i = 0; i < instrCount; i++) {
                    ActiveInstrumentation instr = this.mActiveInstrumentation.get(i);
                    if (instr.mTargetInfo.uid == delegateUid) {
                        if (instr.mUiAutomationConnection == null) {
                            throw new SecurityException("Shell can delegate its permissions only to an instrumentation started from the shell");
                        } else {
                            ShellDelegate shellDelegate = new ShellDelegate(instr.mTargetInfo.packageName, delegateUid, permissions);
                            this.mAppOpsService.setAppOpsServiceDelegate(shellDelegate);
                            getPackageManagerInternalLocked().setCheckPermissionDelegate(shellDelegate);
                            resetPriorityAfterLockedSection();
                            return;
                        }
                    }
                }
                resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void stopDelegateShellPermissionIdentity() {
        if (UserHandle.getCallingAppId() != NATIVE_DUMP_TIMEOUT_MS && UserHandle.getCallingAppId() != 0) {
            throw new SecurityException("Only the shell can delegate its permissions");
        }
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                this.mAppOpsService.setAppOpsServiceDelegate(null);
                getPackageManagerInternalLocked().setCheckPermissionDelegate((PackageManagerInternal.CheckPermissionDelegate) null);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    /* loaded from: classes.dex */
    private class ShellDelegate implements AppOpsManagerInternal.CheckOpsDelegate, PackageManagerInternal.CheckPermissionDelegate {
        private String[] mPermissions;
        private final String mTargetPackageName;
        private final int mTargetUid;

        ShellDelegate(String targetPacakgeName, int targetUid, String[] permissions) {
            this.mTargetPackageName = targetPacakgeName;
            this.mTargetUid = targetUid;
            this.mPermissions = permissions;
        }

        int getDelegateUid() {
            return this.mTargetUid;
        }

        void setPermissions(String[] permissions) {
            this.mPermissions = permissions;
        }

        public int checkOperation(int code, int uid, String packageName, boolean raw, QuadFunction<Integer, Integer, String, Boolean, Integer> superImpl) {
            if (uid == this.mTargetUid && isTargetOp(code)) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return ((Integer) superImpl.apply(Integer.valueOf(code), Integer.valueOf((int) ActivityManagerService.NATIVE_DUMP_TIMEOUT_MS), "com.android.shell", Boolean.valueOf(raw))).intValue();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return ((Integer) superImpl.apply(Integer.valueOf(code), Integer.valueOf(uid), packageName, Boolean.valueOf(raw))).intValue();
        }

        public int checkAudioOperation(int code, int usage, int uid, String packageName, QuadFunction<Integer, Integer, Integer, String, Integer> superImpl) {
            if (uid == this.mTargetUid && isTargetOp(code)) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return ((Integer) superImpl.apply(Integer.valueOf(code), Integer.valueOf(usage), Integer.valueOf((int) ActivityManagerService.NATIVE_DUMP_TIMEOUT_MS), "com.android.shell")).intValue();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return ((Integer) superImpl.apply(Integer.valueOf(code), Integer.valueOf(usage), Integer.valueOf(uid), packageName)).intValue();
        }

        public int noteOperation(int code, int uid, String packageName, TriFunction<Integer, Integer, String, Integer> superImpl) {
            if (uid == this.mTargetUid && isTargetOp(code)) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return ActivityManagerService.this.mAppOpsService.noteProxyOperation(code, ActivityManagerService.NATIVE_DUMP_TIMEOUT_MS, "com.android.shell", uid, packageName);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return ((Integer) superImpl.apply(Integer.valueOf(code), Integer.valueOf(uid), packageName)).intValue();
        }

        public int checkPermission(String permName, String pkgName, int userId, TriFunction<String, String, Integer, Integer> superImpl) {
            if (this.mTargetPackageName.equals(pkgName) && isTargetPermission(permName)) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return ((Integer) superImpl.apply(permName, "com.android.shell", Integer.valueOf(userId))).intValue();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return ((Integer) superImpl.apply(permName, pkgName, Integer.valueOf(userId))).intValue();
        }

        public int checkUidPermission(String permName, int uid, BiFunction<String, Integer, Integer> superImpl) {
            if (uid == this.mTargetUid && isTargetPermission(permName)) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(permName, Integer.valueOf((int) ActivityManagerService.NATIVE_DUMP_TIMEOUT_MS)).intValue();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(permName, Integer.valueOf(uid)).intValue();
        }

        private boolean isTargetOp(int code) {
            String permission;
            if (this.mPermissions == null || (permission = AppOpsManager.opToPermission(code)) == null) {
                return true;
            }
            return isTargetPermission(permission);
        }

        private boolean isTargetPermission(String permission) {
            String[] strArr = this.mPermissions;
            return strArr == null || ArrayUtils.contains(strArr, permission);
        }
    }

    void maybeTriggerWatchdog() {
    }

    private boolean isOnOffloadQueue(int flags) {
        return this.mEnableOffloadQueue && (Integer.MIN_VALUE & flags) != 0;
    }

    public ParcelFileDescriptor getLifeMonitor() {
        ParcelFileDescriptor dup;
        if (!isCallerShell()) {
            throw new SecurityException("Only shell can call it");
        }
        synchronized (this) {
            try {
                try {
                    boostPriorityForLockedSection();
                    if (this.mLifeMonitorFds == null) {
                        this.mLifeMonitorFds = ParcelFileDescriptor.createPipe();
                    }
                    dup = this.mLifeMonitorFds[0].dup();
                } catch (IOException e) {
                    Slog.w("ActivityManager", "Unable to create pipe", e);
                    resetPriorityAfterLockedSection();
                    return null;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return dup;
    }

    public void setFocusedAppNoChecked(int taskId) {
        this.mActivityTaskManager.setFocusedAppNoChecked(taskId);
    }

    public void finishMiniProgram() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                try {
                    finishMiniProgramLocked();
                    Binder.restoreCallingIdentity(origId);
                } catch (Exception e) {
                    Log.d("ActivityManager", "finishMiniProgram e" + e);
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public void handleActivityChanged(Bundle bundle) {
    }

    private void finishMiniProgramLocked() {
    }

    public boolean isTopActivityFullscreen() {
        return xpActivityManagerService.get(this.mContext).isTopActivityFullscreen();
    }

    public void forceGrantFolderPermission(String path) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                try {
                    xpActivityManagerService.get(this.mContext).grantFolderPermission(path);
                    Binder.restoreCallingIdentity(origId);
                } catch (Exception e) {
                    Log.d("ActivityManager", "forceGrantFolderPermission e=" + e);
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public String getOption(String key, String defaultValue) {
        return FeatureFactory.get(key, defaultValue);
    }

    public double[] getUsageInfo() {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                try {
                } catch (Exception e) {
                    Log.d("ActivityManager", "getUsageInfo e=" + e);
                    Binder.restoreCallingIdentity(origId);
                }
                if (this.mProcessCpuTracker == null) {
                    Binder.restoreCallingIdentity(origId);
                    resetPriorityAfterLockedSection();
                    return null;
                }
                synchronized (this.mProcessCpuTracker) {
                    this.mProcessCpuTracker.update();
                }
                double cpu = this.mProcessCpuTracker.getTotalCpuPercent();
                double[] info = {cpu};
                Binder.restoreCallingIdentity(origId);
                resetPriorityAfterLockedSection();
                return info;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setHomeState(ComponentName component, int state) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                xpActivityManagerService.get(this.mContext).setHomeState(component, state);
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public List<xpDialogInfo> getDialogRecorder(boolean topOnly) {
        long origId = Binder.clearCallingIdentity();
        try {
            try {
                return xpActivityManagerService.get(this.mContext).getDialogRecorder(topOnly);
            } catch (Exception e) {
                Log.d("ActivityManager", "getDialogRecorder e=" + e);
                Binder.restoreCallingIdentity(origId);
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void setDialogRecorder(xpDialogInfo info) {
        long origId = Binder.clearCallingIdentity();
        try {
            try {
                xpActivityManagerService.get(this.mContext).setDialogRecorder(info);
            } catch (Exception e) {
                Log.d("ActivityManager", "setDialogRecorder e=" + e);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void dismissDialog(int type) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                try {
                    xpActivityManagerService.get(this.mContext).dismissDialog(type);
                    Binder.restoreCallingIdentity(origId);
                } catch (Exception e) {
                    Log.d("ActivityManager", "dismissDialog e=" + e);
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public List<String> getSpeechObserver() {
        List<String> speechObserver;
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                try {
                    speechObserver = xpActivityManagerService.get(this.mContext).getSpeechObserver();
                    Binder.restoreCallingIdentity(origId);
                } catch (Exception e) {
                    Log.d("ActivityManager", "getSpeechObserver e=" + e);
                    Binder.restoreCallingIdentity(origId);
                    resetPriorityAfterLockedSection();
                    return null;
                }
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
        return speechObserver;
    }

    public void notifyInstallPersistentPackage(String packageName) {
        Slog.i("ActivityManager", "notifyInstallPersistentPackage packageName=" + packageName + " mInstallingPackage " + mInstallingPackage);
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                mInstallingPackage = packageName;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
        resetPriorityAfterLockedSection();
    }

    public boolean isInstalling(String packageName) {
        synchronized (this) {
            try {
                boostPriorityForLockedSection();
                if (mInstallingPackage != null && mInstallingPackage.equals(packageName)) {
                    resetPriorityAfterLockedSection();
                    return true;
                }
                resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }
}
