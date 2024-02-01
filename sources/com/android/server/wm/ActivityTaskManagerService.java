package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IActivityTaskManager;
import android.app.IApplicationThread;
import android.app.IAssistDataReceiver;
import android.app.INotificationManager;
import android.app.IRequestFinishCallback;
import android.app.ITaskStackListener;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.ProfilerInfo;
import android.app.RemoteAction;
import android.app.WaitResult;
import android.app.WindowConfiguration;
import android.app.admin.DevicePolicyCache;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.FactoryTest;
import android.os.Handler;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UpdateLock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.storage.IStorageManager;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.service.voice.VoiceInteractionManagerInternal;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.proto.ProtoOutputStream;
import android.view.IApplicationToken;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.inputmethod.InputMethodSystemProperty;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.ProcessMap;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.KeyguardDismissCallback;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AttributeCache;
import com.android.server.DeviceIdleController;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.UiThread;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.BaseErrorDialog;
import com.android.server.am.EventLogTags;
import com.android.server.am.PendingIntentController;
import com.android.server.am.PendingIntentRecord;
import com.android.server.am.UserState;
import com.android.server.appop.AppOpsService;
import com.android.server.firewall.IntentFirewall;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wm.ActivityStack;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.SharedDisplayContainer;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityInfo;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.SharedDisplayManager;
import com.xpeng.server.am.BootEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/* loaded from: classes2.dex */
public class ActivityTaskManagerService extends IActivityTaskManager.Stub {
    static final long ACTIVITY_BG_START_GRACE_PERIOD_MS = 10000;
    static final boolean ANIMATE = true;
    private static final long APP_SWITCH_DELAY_TIME = 5000;
    public static final String DUMP_ACTIVITIES_CMD = "activities";
    public static final String DUMP_ACTIVITIES_SHORT_CMD = "a";
    public static final String DUMP_CONTAINERS_CMD = "containers";
    public static final String DUMP_LASTANR_CMD = "lastanr";
    public static final String DUMP_LASTANR_TRACES_CMD = "lastanr-traces";
    public static final String DUMP_RECENTS_CMD = "recents";
    public static final String DUMP_RECENTS_SHORT_CMD = "r";
    public static final String DUMP_STARTER_CMD = "starter";
    static final int INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT_MS = 60000;
    public static final int KEY_DISPATCHING_TIMEOUT_MS = 5000;
    private static final int PENDING_ASSIST_EXTRAS_LONG_TIMEOUT = 2000;
    private static final int PENDING_ASSIST_EXTRAS_TIMEOUT = 500;
    private static final int PENDING_AUTOFILL_ASSIST_STRUCTURE_TIMEOUT = 2000;
    public static final int RELAUNCH_REASON_FREE_RESIZE = 2;
    public static final int RELAUNCH_REASON_NONE = 0;
    public static final int RELAUNCH_REASON_WINDOWING_MODE_RESIZE = 1;
    private static final int SERVICE_LAUNCH_IDLE_WHITELIST_DURATION_MS = 5000;
    private static final long START_AS_CALLER_TOKEN_EXPIRED_TIMEOUT = 1802000;
    private static final long START_AS_CALLER_TOKEN_TIMEOUT = 600000;
    private static final long START_AS_CALLER_TOKEN_TIMEOUT_IMPL = 602000;
    private static final String TAG = "ActivityTaskManager";
    private static final String TAG_CONFIGURATION = "ActivityTaskManager";
    private static final String TAG_FOCUS = "ActivityTaskManager";
    private static final String TAG_IMMERSIVE = "ActivityTaskManager";
    private static final String TAG_LOCKTASK = "ActivityTaskManager";
    private static final String TAG_STACK = "ActivityTaskManager";
    private static final String TAG_SWITCH = "ActivityTaskManager";
    private static final String TAG_VISIBILITY = "ActivityTaskManager";
    ComponentName mActiveVoiceInteractionServiceComponent;
    private ActivityStartController mActivityStartController;
    ActivityManagerInternal mAmInternal;
    private AppOpsService mAppOpsService;
    private long mAppSwitchesAllowedTime;
    private AppWarnings mAppWarnings;
    private AssistUtils mAssistUtils;
    CompatModePackages mCompatModePackages;
    private int mConfigurationSeq;
    Context mContext;
    AppTimeTracker mCurAppTimeTracker;
    private boolean mDidAppSwitch;
    private FontScaleSettingObserver mFontScaleSettingObserver;
    boolean mForceResizableActivities;
    private float mFullscreenThumbnailScale;
    H mH;
    boolean mHasHeavyWeightFeature;
    WindowProcessController mHomeProcess;
    IntentFirewall mIntentFirewall;
    KeyguardController mKeyguardController;
    String mLastANRState;
    ActivityRecord mLastResumedActivity;
    private long mLastStopAppSwitchesTime;
    private LockTaskController mLockTaskController;
    PendingIntentController mPendingIntentController;
    private PermissionPolicyInternal mPermissionPolicyInternal;
    private PackageManagerInternal mPmInternal;
    PowerManagerInternal mPowerManagerInternal;
    WindowProcessController mPreviousProcess;
    long mPreviousProcessVisibleTime;
    private RecentTasks mRecentTasks;
    RootActivityContainer mRootActivityContainer;
    IVoiceInteractionSession mRunningVoice;
    ActivityStackSupervisor mStackSupervisor;
    boolean mSupportsFreeformWindowManagement;
    boolean mSupportsMultiDisplay;
    boolean mSupportsMultiWindow;
    boolean mSupportsPictureInPicture;
    boolean mSupportsSplitScreenMultiWindow;
    boolean mSuppressResizeConfigChanges;
    private TaskChangeNotificationController mTaskChangeNotificationController;
    private int mThumbnailHeight;
    private int mThumbnailWidth;
    ComponentName mTopComponent;
    String mTopData;
    private ActivityRecord mTracedResumedActivity;
    UriGrantsManagerInternal mUgmInternal;
    UiHandler mUiHandler;
    private UsageStatsManagerInternal mUsageStatsInternal;
    private UserManagerService mUserManager;
    PowerManager.WakeLock mVoiceWakeLock;
    VrController mVrController;
    WindowManagerService mWindowManager;
    final WindowManagerGlobalLock mGlobalLock = new WindowManagerGlobalLock();
    final Object mGlobalLockWithoutBoost = this.mGlobalLock;
    private final MirrorActiveUids mActiveUids = new MirrorActiveUids();
    private final SparseArray<String> mPendingTempWhitelist = new SparseArray<>();
    final ProcessMap<WindowProcessController> mProcessNames = new ProcessMap<>();
    final WindowProcessControllerMap mProcessMap = new WindowProcessControllerMap();
    WindowProcessController mHeavyWeightProcess = null;
    private boolean mKeyguardShown = false;
    private int mViSessionId = 1000;
    final HashMap<IBinder, IBinder> mStartActivitySources = new HashMap<>();
    final ArrayList<IBinder> mExpiredStartAsCallerTokens = new ArrayList<>();
    private final ArrayList<PendingAssistExtras> mPendingAssistExtras = new ArrayList<>();
    private final Map<Integer, Set<Integer>> mCompanionAppUidsMap = new ArrayMap();
    private final UpdateConfigurationResult mTmpUpdateConfigurationResult = new UpdateConfigurationResult();
    private String[] mSupportedSystemLocales = null;
    private Configuration mTempConfig = new Configuration();
    final StringBuilder mStringBuilder = new StringBuilder(256);
    IActivityController mController = null;
    boolean mControllerIsAMonkey = false;
    String mTopAction = "android.intent.action.MAIN";
    String mProfileApp = null;
    WindowProcessController mProfileProc = null;
    ProfilerInfo mProfilerInfo = null;
    private final UpdateLock mUpdateLock = new UpdateLock("immersive");
    final SparseArray<ArrayMap<String, Integer>> mAllowAppSwitchUids = new SparseArray<>();
    final List<ActivityTaskManagerInternal.ScreenObserver> mScreenObservers = new ArrayList();
    int mVr2dDisplayId = -1;
    private boolean mSleeping = false;
    int mTopProcessState = 2;
    private boolean mShowDialogs = true;
    boolean mShuttingDown = false;
    private int mDeviceOwnerUid = -1;
    final int mFactoryTest = FactoryTest.getMode();
    final ActivityThread mSystemThread = ActivityThread.currentActivityThread();
    final Context mUiContext = this.mSystemThread.getSystemUiContext();
    private final ClientLifecycleManager mLifecycleManager = new ClientLifecycleManager();
    @VisibleForTesting
    final ActivityTaskManagerInternal mInternal = new LocalService();
    final int GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version", 0);

    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes2.dex */
    @interface HotPath {
        public static final int LRU_UPDATE = 2;
        public static final int NONE = 0;
        public static final int OOM_ADJUSTMENT = 1;
        public static final int PROCESS_CHANGE = 3;

        int caller() default 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static final class UpdateConfigurationResult {
        boolean activityRelaunched;
        int changes;

        UpdateConfigurationResult() {
        }

        void reset() {
            this.changes = 0;
            this.activityRelaunched = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class FontScaleSettingObserver extends ContentObserver {
        private final Uri mFontScaleUri;
        private final Uri mHideErrorDialogsUri;

        public FontScaleSettingObserver() {
            super(ActivityTaskManagerService.this.mH);
            this.mFontScaleUri = Settings.System.getUriFor("font_scale");
            this.mHideErrorDialogsUri = Settings.Global.getUriFor("hide_error_dialogs");
            ContentResolver resolver = ActivityTaskManagerService.this.mContext.getContentResolver();
            resolver.registerContentObserver(this.mFontScaleUri, false, this, -1);
            resolver.registerContentObserver(this.mHideErrorDialogsUri, false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (this.mFontScaleUri.equals(uri)) {
                ActivityTaskManagerService.this.updateFontScaleIfNeeded(userId);
            } else if (this.mHideErrorDialogsUri.equals(uri)) {
                synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        ActivityTaskManagerService.this.updateShouldShowDialogsLocked(ActivityTaskManagerService.this.getGlobalConfiguration());
                    } catch (Throwable th) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public ActivityTaskManagerService(Context context) {
        this.mContext = context;
    }

    public void onSystemReady() {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mHasHeavyWeightFeature = this.mContext.getPackageManager().hasSystemFeature("android.software.cant_save_state");
                this.mAssistUtils = new AssistUtils(this.mContext);
                this.mVrController.onSystemReady();
                this.mRecentTasks.onSystemReadyLocked();
                this.mStackSupervisor.onSystemReady();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void onInitPowerManagement() {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.initPowerManagement();
                PowerManager pm = (PowerManager) this.mContext.getSystemService("power");
                this.mPowerManagerInternal = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
                this.mVoiceWakeLock = pm.newWakeLock(1, "*voice*");
                this.mVoiceWakeLock.setReferenceCounted(false);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void installSystemProviders() {
        this.mFontScaleSettingObserver = new FontScaleSettingObserver();
    }

    /* JADX WARN: Removed duplicated region for block: B:43:0x00e4 A[Catch: all -> 0x013b, TryCatch #0 {all -> 0x013b, blocks: (B:28:0x008b, B:40:0x00ad, B:41:0x00b7, B:43:0x00e4, B:45:0x00ff, B:47:0x011d, B:49:0x0136, B:48:0x012c, B:39:0x00a2), top: B:56:0x008b }] */
    /* JADX WARN: Removed duplicated region for block: B:44:0x00fd  */
    /* JADX WARN: Removed duplicated region for block: B:47:0x011d A[Catch: all -> 0x013b, TryCatch #0 {all -> 0x013b, blocks: (B:28:0x008b, B:40:0x00ad, B:41:0x00b7, B:43:0x00e4, B:45:0x00ff, B:47:0x011d, B:49:0x0136, B:48:0x012c, B:39:0x00a2), top: B:56:0x008b }] */
    /* JADX WARN: Removed duplicated region for block: B:48:0x012c A[Catch: all -> 0x013b, TryCatch #0 {all -> 0x013b, blocks: (B:28:0x008b, B:40:0x00ad, B:41:0x00b7, B:43:0x00e4, B:45:0x00ff, B:47:0x011d, B:49:0x0136, B:48:0x012c, B:39:0x00a2), top: B:56:0x008b }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void retrieveSettings(android.content.ContentResolver r18) {
        /*
            Method dump skipped, instructions count: 321
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityTaskManagerService.retrieveSettings(android.content.ContentResolver):void");
    }

    public WindowManagerGlobalLock getGlobalLock() {
        return this.mGlobalLock;
    }

    @VisibleForTesting
    public ActivityTaskManagerInternal getAtmInternal() {
        return this.mInternal;
    }

    public void initialize(IntentFirewall intentFirewall, PendingIntentController intentController, Looper looper) {
        this.mH = new H(looper);
        this.mUiHandler = new UiHandler();
        this.mIntentFirewall = intentFirewall;
        File systemDir = SystemServiceManager.ensureSystemDir();
        this.mAppWarnings = new AppWarnings(this, this.mUiContext, this.mH, this.mUiHandler, systemDir);
        this.mCompatModePackages = new CompatModePackages(this, systemDir, this.mH);
        this.mPendingIntentController = intentController;
        this.mTempConfig.setToDefaults();
        this.mTempConfig.setLocales(LocaleList.getDefault());
        this.mTempConfig.seq = 1;
        this.mConfigurationSeq = 1;
        this.mStackSupervisor = createStackSupervisor();
        this.mRootActivityContainer = new RootActivityContainer(this);
        this.mRootActivityContainer.onConfigurationChanged(this.mTempConfig);
        this.mTaskChangeNotificationController = new TaskChangeNotificationController(this.mGlobalLock, this.mStackSupervisor, this.mH);
        this.mLockTaskController = new LockTaskController(this.mContext, this.mStackSupervisor, this.mH);
        this.mActivityStartController = new ActivityStartController(this);
        this.mRecentTasks = createRecentTasks();
        this.mStackSupervisor.setRecentTasks(this.mRecentTasks);
        this.mVrController = new VrController(this.mGlobalLock);
        this.mKeyguardController = this.mStackSupervisor.getKeyguardController();
    }

    public void onActivityManagerInternalAdded() {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mAmInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
                this.mUgmInternal = (UriGrantsManagerInternal) LocalServices.getService(UriGrantsManagerInternal.class);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int increaseConfigurationSeqLocked() {
        int i = this.mConfigurationSeq + 1;
        this.mConfigurationSeq = i;
        this.mConfigurationSeq = Math.max(i, 1);
        return this.mConfigurationSeq;
    }

    protected ActivityStackSupervisor createStackSupervisor() {
        ActivityStackSupervisor supervisor = new ActivityStackSupervisor(this, this.mH.getLooper());
        supervisor.initialize();
        return supervisor;
    }

    public void setWindowManager(WindowManagerService wm) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mWindowManager = wm;
                this.mLockTaskController.setWindowManager(wm);
                this.mStackSupervisor.setWindowManager(wm);
                this.mRootActivityContainer.setWindowManager(wm);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void setUsageStatsManager(UsageStatsManagerInternal usageStatsManager) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mUsageStatsInternal = usageStatsManager;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserManagerService getUserManager() {
        if (this.mUserManager == null) {
            IBinder b = ServiceManager.getService("user");
            this.mUserManager = IUserManager.Stub.asInterface(b);
        }
        return this.mUserManager;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppOpsService getAppOpsService() {
        if (this.mAppOpsService == null) {
            IBinder b = ServiceManager.getService("appops");
            this.mAppOpsService = IAppOpsService.Stub.asInterface(b);
        }
        return this.mAppOpsService;
    }

    boolean hasUserRestriction(String restriction, int userId) {
        return getUserManager().hasUserRestriction(restriction, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSystemAlertWindowPermission(int callingUid, int callingPid, String callingPackage) {
        int mode = getAppOpsService().noteOperation(24, callingUid, callingPackage);
        return mode == 3 ? checkPermission("android.permission.SYSTEM_ALERT_WINDOW", callingPid, callingUid) == 0 : mode == 0;
    }

    protected RecentTasks createRecentTasks() {
        return new RecentTasks(this, this.mStackSupervisor);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecentTasks getRecentTasks() {
        return this.mRecentTasks;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ClientLifecycleManager getLifecycleManager() {
        return this.mLifecycleManager;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStartController getActivityStartController() {
        return this.mActivityStartController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskChangeNotificationController getTaskChangeNotificationController() {
        return this.mTaskChangeNotificationController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public LockTaskController getLockTaskController() {
        return this.mLockTaskController;
    }

    Configuration getGlobalConfigurationForCallingPid() {
        int pid = Binder.getCallingPid();
        return getGlobalConfigurationForPid(pid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Configuration getGlobalConfigurationForPid(int pid) {
        Configuration configuration;
        if (pid == ActivityManagerService.MY_PID || pid < 0) {
            return getGlobalConfiguration();
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                WindowProcessController app = this.mProcessMap.getProcess(pid);
                configuration = app != null ? app.getConfiguration() : getGlobalConfiguration();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return configuration;
    }

    public ConfigurationInfo getDeviceConfigurationInfo() {
        ConfigurationInfo config = new ConfigurationInfo();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                Configuration globalConfig = getGlobalConfigurationForCallingPid();
                config.reqTouchScreen = globalConfig.touchscreen;
                config.reqKeyboardType = globalConfig.keyboard;
                config.reqNavigation = globalConfig.navigation;
                if (globalConfig.navigation == 2 || globalConfig.navigation == 3) {
                    config.reqInputFeatures |= 2;
                }
                if (globalConfig.keyboard != 0 && globalConfig.keyboard != 1) {
                    config.reqInputFeatures |= 1;
                }
                config.reqGlEsVersion = this.GL_ES_VERSION;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return config;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void start() {
        LocalServices.addService(ActivityTaskManagerInternal.class, this.mInternal);
    }

    /* loaded from: classes2.dex */
    public static final class Lifecycle extends SystemService {
        private final ActivityTaskManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            this.mService = new ActivityTaskManagerService(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService("activity_task", this.mService);
            this.mService.start();
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userId) {
            synchronized (this.mService.getGlobalLock()) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    this.mService.mStackSupervisor.onUserUnlocked(userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.SystemService
        public void onCleanupUser(int userId) {
            synchronized (this.mService.getGlobalLock()) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    this.mService.mStackSupervisor.mLaunchParamsPersister.onCleanupUser(userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        public ActivityTaskManagerService getService() {
            return this.mService;
        }
    }

    public final int startActivity(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions) {
        return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions, UserHandle.getCallingUserId());
    }

    public final int startActivities(IApplicationThread caller, String callingPackage, Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle bOptions, int userId) {
        Bundle bOptions2;
        Bundle bOptions3;
        enforceNotIsolatedCaller("startActivities");
        int userId2 = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, "startActivities");
        if (intents == null) {
            bOptions2 = bOptions;
        } else {
            try {
                int length = intents.length;
                bOptions2 = bOptions;
                for (int i = 0; i < length; i++) {
                    try {
                        ResolveInfo rInfo = this.mStackSupervisor.resolveIntent(intents[i], resolvedTypes[i], userId2, 0, 0);
                        WindowProcessController callerApp = getProcessController(caller);
                        ApplicationInfo callerInfo = callerApp != null ? callerApp.mInfo : null;
                        ActivityInfo ai = rInfo != null ? rInfo.activityInfo : null;
                        xpActivityInfo xai = SharedDisplayFactory.createActivityInfo(intents[i], rInfo);
                        intents[i] = ActivityInfoManager.appendIntentExtra(this.mContext, xai);
                        int sharedId = SharedDisplayManager.getLaunchSharedId(this.mContext, ai, intents[i], callerInfo);
                        if (SharedDisplayManager.sharedValid(sharedId)) {
                            intents[i].setSharedId(sharedId);
                        }
                        xpActivityManager.ActivityRecordInfo ari = xpActivityManager.getOverrideActivityRecord(ai, intents[i], bOptions2);
                        if (ari != null) {
                            if (ari.intent != null) {
                                intents[i] = ari.intent;
                            }
                            if (ari.options != null) {
                                bOptions2 = ari.options;
                            }
                        }
                    } catch (Exception e) {
                        bOptions3 = bOptions2;
                        return getActivityStartController().startActivities(caller, -1, 0, -1, callingPackage, intents, resolvedTypes, resultTo, SafeActivityOptions.fromBundle(bOptions3), userId2, "startActivities", null, false);
                    }
                }
            } catch (Exception e2) {
                bOptions2 = bOptions;
            }
        }
        bOptions3 = bOptions2;
        return getActivityStartController().startActivities(caller, -1, 0, -1, callingPackage, intents, resolvedTypes, resultTo, SafeActivityOptions.fromBundle(bOptions3), userId2, "startActivities", null, false);
    }

    public int startActivityAsUser(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions, userId, true);
    }

    int startActivityAsUser(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId, boolean validateIncomingUser) {
        Intent intent2;
        Bundle bOptions2;
        enforceNotIsolatedCaller("startActivityAsUser");
        int userId2 = getActivityStartController().checkTargetUser(userId, validateIncomingUser, Binder.getCallingPid(), Binder.getCallingUid(), "startActivityAsUser");
        try {
            ResolveInfo rInfo = this.mStackSupervisor.resolveIntent(intent, resolvedType, userId2, 0, 0);
            WindowProcessController callerApp = getProcessController(caller);
            ApplicationInfo callerInfo = callerApp != null ? callerApp.mInfo : null;
            ActivityInfo ai = rInfo != null ? rInfo.activityInfo : null;
            intent2 = intent;
            try {
                xpActivityInfo xai = SharedDisplayFactory.createActivityInfo(intent2, rInfo);
                String str = xai.packageName;
                intent2 = ActivityInfoManager.appendIntentExtra(this.mContext, xai);
                try {
                    int sharedId = SharedDisplayManager.getLaunchSharedId(this.mContext, ai, intent2, callerInfo);
                    if (SharedDisplayManager.sharedValid(sharedId)) {
                        intent2.setSharedId(sharedId);
                    }
                    bOptions2 = bOptions;
                    try {
                        xpActivityManager.ActivityRecordInfo ari = xpActivityManager.getOverrideActivityRecord(ai, intent2, bOptions2);
                        if (ari != null) {
                            if (ari.intent != null) {
                                intent2 = ari.intent;
                            }
                            if (ari.options != null) {
                                bOptions2 = ari.options;
                            }
                        }
                    } catch (Exception e) {
                    }
                } catch (Exception e2) {
                    bOptions2 = bOptions;
                }
            } catch (Exception e3) {
                bOptions2 = bOptions;
            }
        } catch (Exception e4) {
            intent2 = intent;
            bOptions2 = bOptions;
        }
        return getActivityStartController().obtainStarter(intent2, "startActivityAsUser").setCaller(caller).setCallingPackage(callingPackage).setResolvedType(resolvedType).setResultTo(resultTo).setResultWho(resultWho).setRequestCode(requestCode).setStartFlags(startFlags).setProfilerInfo(profilerInfo).setActivityOptions(bOptions2).setMayWait(userId2).execute();
    }

    public int startActivityIntentSender(IApplicationThread caller, IIntentSender target, IBinder whitelistToken, Intent fillInIntent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flagsMask, int flagsValues, Bundle bOptions) {
        enforceNotIsolatedCaller("startActivityIntentSender");
        if (fillInIntent != null && fillInIntent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        if (!(target instanceof PendingIntentRecord)) {
            throw new IllegalArgumentException("Bad PendingIntent object");
        }
        PendingIntentRecord pir = (PendingIntentRecord) target;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = getTopDisplayFocusedStack();
                if (stack.mResumedActivity != null && stack.mResumedActivity.info.applicationInfo.uid == Binder.getCallingUid()) {
                    this.mAppSwitchesAllowedTime = 0L;
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return pir.sendInner(0, fillInIntent, resolvedType, whitelistToken, null, null, resultTo, resultWho, requestCode, flagsMask, flagsValues, bOptions);
    }

    /* JADX WARN: Code restructure failed: missing block: B:41:0x00a4, code lost:
        r12 = r12 + r8;
     */
    /* JADX WARN: Code restructure failed: missing block: B:42:0x00a5, code lost:
        if (r12 >= r11) goto L44;
     */
    /* JADX WARN: Code restructure failed: missing block: B:43:0x00a7, code lost:
        r10 = r0.get(r12).activityInfo;
     */
    /* JADX WARN: Code restructure failed: missing block: B:44:0x00b0, code lost:
        if (r9 == false) goto L51;
     */
    /* JADX WARN: Code restructure failed: missing block: B:45:0x00b2, code lost:
        android.util.Slog.v("ActivityTaskManager", "Next matching activity: found current " + r0.packageName + com.android.server.slice.SliceClientPermissions.SliceAuthority.DELIMITER + r0.info.name);
        r14 = new java.lang.StringBuilder();
        r14.append("Next matching activity: next is ");
     */
    /* JADX WARN: Code restructure failed: missing block: B:46:0x00e2, code lost:
        if (r10 != null) goto L50;
     */
    /* JADX WARN: Code restructure failed: missing block: B:47:0x00e4, code lost:
        r15 = "null";
     */
    /* JADX WARN: Code restructure failed: missing block: B:48:0x00e7, code lost:
        r15 = r10.packageName + com.android.server.slice.SliceClientPermissions.SliceAuthority.DELIMITER + r10.name;
     */
    /* JADX WARN: Code restructure failed: missing block: B:49:0x00ff, code lost:
        r14.append(r15);
        android.util.Slog.v("ActivityTaskManager", r14.toString());
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean startNextMatchingActivity(android.os.IBinder r17, android.content.Intent r18, android.os.Bundle r19) {
        /*
            Method dump skipped, instructions count: 451
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityTaskManagerService.startNextMatchingActivity(android.os.IBinder, android.content.Intent, android.os.Bundle):boolean");
    }

    public final WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        int i;
        WaitResult res = new WaitResult();
        synchronized (this.mGlobalLock) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    enforceNotIsolatedCaller("startActivityAndWait");
                    i = userId;
                } catch (Throwable th) {
                    th = th;
                    i = userId;
                }
            } catch (Throwable th2) {
                th = th2;
            }
            try {
                try {
                    try {
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } catch (Throwable th4) {
                    th = th4;
                }
                try {
                } catch (Throwable th5) {
                    th = th5;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            } catch (Throwable th6) {
                th = th6;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
            try {
            } catch (Throwable th7) {
                th = th7;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
            try {
            } catch (Throwable th8) {
                th = th8;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
            try {
                try {
                } catch (Throwable th9) {
                    th = th9;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
                try {
                } catch (Throwable th10) {
                    th = th10;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
                try {
                } catch (Throwable th11) {
                    th = th11;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
                try {
                    getActivityStartController().obtainStarter(intent, "startActivityAndWait").setCaller(caller).setCallingPackage(callingPackage).setResolvedType(resolvedType).setResultTo(resultTo).setResultWho(resultWho).setRequestCode(requestCode).setStartFlags(startFlags).setActivityOptions(bOptions).setMayWait(handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), i, "startActivityAndWait")).setProfilerInfo(profilerInfo).setWaitResult(res).execute();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return res;
                } catch (Throwable th12) {
                    th = th12;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            } catch (Throwable th13) {
                th = th13;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public final int startActivityWithConfig(IApplicationThread caller, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, Configuration config, Bundle bOptions, int userId) {
        int execute;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                enforceNotIsolatedCaller("startActivityWithConfig");
                execute = getActivityStartController().obtainStarter(intent, "startActivityWithConfig").setCaller(caller).setCallingPackage(callingPackage).setResolvedType(resolvedType).setResultTo(resultTo).setResultWho(resultWho).setRequestCode(requestCode).setStartFlags(startFlags).setGlobalConfiguration(config).setActivityOptions(bOptions).setMayWait(handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, "startActivityWithConfig")).execute();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return execute;
    }

    public IBinder requestStartActivityPermissionToken(IBinder delegatorToken) {
        int callingUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callingUid) != 1000) {
            throw new SecurityException("Only the system process can request a permission token, received request from uid: " + callingUid);
        }
        IBinder permissionToken = new Binder();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStartActivitySources.put(permissionToken, delegatorToken);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        Message expireMsg = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$3DTHgCAeEd5OOF7ACeXoCk8mmrQ
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((ActivityTaskManagerService) obj).expireStartAsCallerTokenMsg((IBinder) obj2);
            }
        }, this, permissionToken);
        this.mUiHandler.sendMessageDelayed(expireMsg, START_AS_CALLER_TOKEN_TIMEOUT_IMPL);
        Message forgetMsg = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$7ieG0s-7Zp4H2bLiWdOgB6MqhcI
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((ActivityTaskManagerService) obj).forgetStartAsCallerTokenMsg((IBinder) obj2);
            }
        }, this, permissionToken);
        this.mUiHandler.sendMessageDelayed(forgetMsg, START_AS_CALLER_TOKEN_EXPIRED_TIMEOUT);
        return permissionToken;
    }

    /* JADX WARN: Code restructure failed: missing block: B:31:0x00b5, code lost:
        if (r20.getComponent() == null) goto L38;
     */
    /* JADX WARN: Code restructure failed: missing block: B:33:0x00bb, code lost:
        if (r20.getSelector() != null) goto L36;
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00c5, code lost:
        throw new java.lang.SecurityException("Selector not allowed with ignoreTargetSecurity");
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x00cd, code lost:
        throw new java.lang.SecurityException("Component must be specified with ignoreTargetSecurity");
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public final int startActivityAsCaller(android.app.IApplicationThread r18, java.lang.String r19, android.content.Intent r20, java.lang.String r21, android.os.IBinder r22, java.lang.String r23, int r24, int r25, android.app.ProfilerInfo r26, android.os.Bundle r27, android.os.IBinder r28, boolean r29, int r30) {
        /*
            Method dump skipped, instructions count: 458
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityTaskManagerService.startActivityAsCaller(android.app.IApplicationThread, java.lang.String, android.content.Intent, java.lang.String, android.os.IBinder, java.lang.String, int, int, android.app.ProfilerInfo, android.os.Bundle, android.os.IBinder, boolean, int):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int handleIncomingUser(int callingPid, int callingUid, int userId, String name) {
        return this.mAmInternal.handleIncomingUser(callingPid, callingUid, userId, false, 2, name, (String) null);
    }

    public int startVoiceActivity(String callingPackage, int callingPid, int callingUid, Intent intent, String resolvedType, IVoiceInteractionSession session, IVoiceInteractor interactor, int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        this.mAmInternal.enforceCallingPermission("android.permission.BIND_VOICE_INTERACTION", "startVoiceActivity()");
        if (session == null || interactor == null) {
            throw new NullPointerException("null session or interactor");
        }
        return getActivityStartController().obtainStarter(intent, "startVoiceActivity").setCallingUid(callingUid).setCallingPackage(callingPackage).setResolvedType(resolvedType).setVoiceSession(session).setVoiceInteractor(interactor).setStartFlags(startFlags).setProfilerInfo(profilerInfo).setActivityOptions(bOptions).setMayWait(handleIncomingUser(callingPid, callingUid, userId, "startVoiceActivity")).setAllowBackgroundActivityStart(true).execute();
    }

    public int startAssistantActivity(String callingPackage, int callingPid, int callingUid, Intent intent, String resolvedType, Bundle bOptions, int userId) {
        this.mAmInternal.enforceCallingPermission("android.permission.BIND_VOICE_INTERACTION", "startAssistantActivity()");
        return getActivityStartController().obtainStarter(intent, "startAssistantActivity").setCallingUid(callingUid).setCallingPackage(callingPackage).setResolvedType(resolvedType).setActivityOptions(bOptions).setMayWait(handleIncomingUser(callingPid, callingUid, userId, "startAssistantActivity")).setAllowBackgroundActivityStart(true).execute();
    }

    public void startRecentsActivity(Intent intent, @Deprecated IAssistDataReceiver unused, IRecentsAnimationRunner recentsAnimationRunner) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "startRecentsActivity()");
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        long origId = Binder.clearCallingIdentity();
        try {
            try {
                synchronized (this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        ComponentName recentsComponent = this.mRecentTasks.getRecentsComponent();
                        int recentsUid = this.mRecentTasks.getRecentsComponentUid();
                        WindowProcessController caller = getProcessController(callingPid, callingUid);
                        RecentsAnimation anim = new RecentsAnimation(this, this.mStackSupervisor, getActivityStartController(), this.mWindowManager, intent, recentsComponent, recentsUid, caller);
                        if (recentsAnimationRunner == null) {
                            anim.preloadRecentsActivity();
                        } else {
                            anim.startRecentsActivity(recentsAnimationRunner);
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                    } catch (Throwable th) {
                        th = th;
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
            } catch (Throwable th2) {
                th = th2;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public final int startActivityFromRecents(int taskId, Bundle bOptions) {
        int startActivityFromRecents;
        enforceCallerIsRecentsOrHasPermission("android.permission.START_TASKS_FROM_RECENTS", "startActivityFromRecents()");
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(bOptions);
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                startActivityFromRecents = this.mStackSupervisor.startActivityFromRecents(callingPid, callingUid, taskId, safeOptions);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return startActivityFromRecents;
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public final boolean isActivityStartAllowedOnDisplay(int displayId, Intent intent, String resolvedType, int userId) {
        boolean canPlaceEntityOnDisplay;
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long origId = Binder.clearCallingIdentity();
        try {
            ActivityInfo aInfo = this.mStackSupervisor.resolveActivity(intent, resolvedType, 0, null, userId, ActivityStarter.computeResolveFilterUid(callingUid, callingUid, -10000));
            ActivityInfo aInfo2 = this.mAmInternal.getActivityInfoForUser(aInfo, userId);
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                canPlaceEntityOnDisplay = this.mStackSupervisor.canPlaceEntityOnDisplay(displayId, callingPid, callingUid, aInfo2);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return canPlaceEntityOnDisplay;
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData, int finishTask) {
        boolean res;
        if (resultData != null && resultData.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return true;
                }
                TaskRecord tr = r.getTaskRecord();
                ActivityRecord rootR = tr.getRootActivity();
                if (rootR == null) {
                    Slog.w("ActivityTaskManager", "Finishing task with all activities already finished");
                }
                if (getLockTaskController().activityBlockedFromFinish(r)) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                if (this.mController != null) {
                    try {
                        ActivityRecord next = r.getActivityStack().topRunningActivityLocked(token, 0);
                        if (next != null) {
                            boolean resumeOK = true;
                            try {
                                resumeOK = this.mController.activityResuming(next.packageName);
                            } catch (RemoteException e) {
                                this.mController = null;
                                Watchdog.getInstance().setActivityController(null);
                            }
                            if (!resumeOK) {
                                Slog.i("ActivityTaskManager", "Not finishing activity because controller resumed");
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return false;
                            }
                        }
                    } catch (Throwable th) {
                        th = th;
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                if (r.app != null) {
                    r.app.setLastActivityFinishTimeIfNeeded(SystemClock.uptimeMillis());
                }
                long origId = Binder.clearCallingIdentity();
                boolean finishWithRootActivity = finishTask == 1;
                if (finishTask == 2 || (finishWithRootActivity && r == rootR)) {
                    res = this.mStackSupervisor.removeTaskByIdLocked(tr.taskId, false, finishWithRootActivity, "finish-activity");
                    if (!res) {
                        Slog.i("ActivityTaskManager", "Removing task failed to finish activity");
                    }
                    r.mRelaunchReason = 0;
                } else {
                    res = tr.getStack().requestFinishActivityLocked(token, resultCode, resultData, "app-request", true);
                    if (!res) {
                        Slog.i("ActivityTaskManager", "Failed to finish by app-request");
                    }
                }
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
                return res;
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public boolean finishActivityAffinity(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    Binder.restoreCallingIdentity(origId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                TaskRecord task = r.getTaskRecord();
                if (getLockTaskController().activityBlockedFromFinish(r)) {
                    Binder.restoreCallingIdentity(origId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                boolean finishActivityAffinityLocked = task.getStack().finishActivityAffinityLocked(r);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
                return finishActivityAffinityLocked;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public final void activityIdle(IBinder token, Configuration config, boolean stopProfiling) {
        long origId = Binder.clearCallingIdentity();
        WindowProcessController proc = null;
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = ActivityRecord.getStackLocked(token);
                if (stack != null) {
                    ActivityRecord r = this.mStackSupervisor.activityIdleInternalLocked(token, false, false, config);
                    if (r != null) {
                        proc = r.app;
                    }
                    if (stopProfiling && proc != null) {
                        proc.clearProfilerIfNeeded();
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public final void activityResumed(IBinder token) {
        long origId = Binder.clearCallingIdentity();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord.activityResumedLocked(token);
                this.mWindowManager.notifyAppResumedFinished(token);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        Binder.restoreCallingIdentity(origId);
    }

    public final void activityTopResumedStateLost() {
        long origId = Binder.clearCallingIdentity();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.handleTopResumedStateReleased(false);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        Binder.restoreCallingIdentity(origId);
    }

    public final void activityPaused(IBinder token) {
        long origId = Binder.clearCallingIdentity();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = ActivityRecord.getStackLocked(token);
                if (stack != null) {
                    stack.activityPausedLocked(token, false);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        Binder.restoreCallingIdentity(origId);
    }

    public final void activityStopped(IBinder token, Bundle icicle, PersistableBundle persistentState, CharSequence description) {
        ActivityRecord r;
        if (ActivityTaskManagerDebugConfig.DEBUG_ALL) {
            Slog.v("ActivityTaskManager", "Activity stopped: token=" + token);
        }
        if (icicle != null && icicle.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }
        long origId = Binder.clearCallingIdentity();
        String restartingName = null;
        int restartingUid = 0;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    if (r.attachedToProcess() && r.isState(ActivityStack.ActivityState.RESTARTING_PROCESS)) {
                        restartingName = r.app.mName;
                        restartingUid = r.app.mUid;
                    }
                    r.activityStoppedLocked(icicle, persistentState, description);
                }
                handleActivityStoppedLocked(r);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (restartingName != null) {
            this.mStackSupervisor.removeRestartTimeouts(r);
            this.mAmInternal.killProcess(restartingName, restartingUid, "restartActivityProcess");
        }
        this.mAmInternal.trimApplications();
        Binder.restoreCallingIdentity(origId);
    }

    public final void activityDestroyed(IBinder token) {
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH) {
            Slog.v("ActivityTaskManager", "ACTIVITY DESTROYED: " + token);
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = ActivityRecord.getStackLocked(token);
                if (stack != null) {
                    stack.activityDestroyedLocked(token, "activityDestroyed");
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public final void activityRelaunched(IBinder token) {
        long origId = Binder.clearCallingIdentity();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.activityRelaunchedLocked(token);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        Binder.restoreCallingIdentity(origId);
    }

    public final void activitySlept(IBinder token) {
        if (ActivityTaskManagerDebugConfig.DEBUG_ALL) {
            Slog.v("ActivityTaskManager", "Activity slept: token=" + token);
        }
        long origId = Binder.clearCallingIdentity();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    this.mStackSupervisor.activitySleptLocked(r);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        Binder.restoreCallingIdentity(origId);
    }

    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                r.setRequestedOrientation(requestedOrientation);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getRequestedOrientation(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return -1;
                }
                int orientation = r.getOrientation();
                WindowManagerService.resetPriorityAfterLockedSection();
                return orientation;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setImmersive(IBinder token, boolean immersive) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    throw new IllegalArgumentException();
                }
                r.immersive = immersive;
                if (r.isResumedActivityOnDisplay()) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_IMMERSIVE) {
                        Slog.d("ActivityTaskManager", "Frontmost changed immersion: " + r);
                    }
                    applyUpdateLockStateLocked(r);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    void applyUpdateLockStateLocked(final ActivityRecord r) {
        final boolean nextState = r != null && r.immersive;
        this.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$p4I6RZJqLXjaEjdISFyNzjAe4HE
            @Override // java.lang.Runnable
            public final void run() {
                ActivityTaskManagerService.this.lambda$applyUpdateLockStateLocked$0$ActivityTaskManagerService(nextState, r);
            }
        });
    }

    public /* synthetic */ void lambda$applyUpdateLockStateLocked$0$ActivityTaskManagerService(boolean nextState, ActivityRecord r) {
        if (this.mUpdateLock.isHeld() != nextState) {
            if (ActivityTaskManagerDebugConfig.DEBUG_IMMERSIVE) {
                Slog.d("ActivityTaskManager", "Applying new update lock state '" + nextState + "' for " + r);
            }
            if (nextState) {
                this.mUpdateLock.acquire();
            } else {
                this.mUpdateLock.release();
            }
        }
    }

    public boolean isImmersive(IBinder token) {
        boolean z;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    throw new IllegalArgumentException();
                }
                z = r.immersive;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return z;
    }

    public boolean isTopActivityImmersive() {
        boolean z;
        enforceNotIsolatedCaller("isTopActivityImmersive");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = getTopDisplayFocusedStack().topRunningActivityLocked();
                z = r != null ? r.immersive : false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return z;
    }

    public void overridePendingTransition(IBinder token, String packageName, int enterAnim, int exitAnim) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord self = ActivityRecord.isInStackLocked(token);
                if (self == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                if (self.isState(ActivityStack.ActivityState.RESUMED, ActivityStack.ActivityState.PAUSING)) {
                    self.getDisplay().mDisplayContent.mAppTransition.overridePendingAppTransition(packageName, enterAnim, exitAnim, null);
                }
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getFrontActivityScreenCompatMode() {
        enforceNotIsolatedCaller("getFrontActivityScreenCompatMode");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = getTopDisplayFocusedStack().topRunningActivityLocked();
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return -3;
                }
                int computeCompatModeLocked = this.mCompatModePackages.computeCompatModeLocked(r.info.applicationInfo);
                WindowManagerService.resetPriorityAfterLockedSection();
                return computeCompatModeLocked;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setFrontActivityScreenCompatMode(int mode) {
        this.mAmInternal.enforceCallingPermission("android.permission.SET_SCREEN_COMPATIBILITY", "setFrontActivityScreenCompatMode");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = getTopDisplayFocusedStack().topRunningActivityLocked();
                if (r == null) {
                    Slog.w("ActivityTaskManager", "setFrontActivityScreenCompatMode failed: no top activity");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ApplicationInfo ai = r.info.applicationInfo;
                this.mCompatModePackages.setPackageScreenCompatModeLocked(ai, mode);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getLaunchedFromUid(IBinder activityToken) {
        ActivityRecord srec;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                srec = ActivityRecord.forTokenLocked(activityToken);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (srec == null) {
            return -1;
        }
        return srec.launchedFromUid;
    }

    public String getLaunchedFromPackage(IBinder activityToken) {
        ActivityRecord srec;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                srec = ActivityRecord.forTokenLocked(activityToken);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (srec == null) {
            return null;
        }
        return srec.launchedFromPackage;
    }

    public boolean convertFromTranslucent(IBinder token) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    boolean translucentChanged = r.changeWindowTranslucency(true);
                    if (translucentChanged) {
                        this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
                    }
                    this.mWindowManager.setAppFullscreen(token, true);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return translucentChanged;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean convertToTranslucent(IBinder token, Bundle options) {
        SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(options);
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    TaskRecord task = r.getTaskRecord();
                    int index = task.mActivities.lastIndexOf(r);
                    if (index > 0) {
                        ActivityRecord under = task.mActivities.get(index - 1);
                        under.returningOptions = safeOptions != null ? safeOptions.getOptions(r) : null;
                    }
                    boolean translucentChanged = r.changeWindowTranslucency(false);
                    if (translucentChanged) {
                        r.getActivityStack().convertActivityToTranslucent(r);
                    }
                    this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
                    this.mWindowManager.setAppFullscreen(token, false);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return translucentChanged;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void notifyActivityDrawn(IBinder token) {
        if (ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.d("ActivityTaskManager", "notifyActivityDrawn: token=" + token);
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = this.mRootActivityContainer.isInAnyStack(token);
                if (r != null) {
                    r.getActivityStack().notifyActivityDrawnLocked(r);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void reportActivityFullyDrawn(IBinder token, boolean restoredFromBundle) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                r.reportFullyDrawnLocked(restoredFromBundle);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getActivityDisplayId(IBinder activityToken) throws RemoteException {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = ActivityRecord.getStackLocked(activityToken);
                if (stack != null && stack.mDisplayId != -1) {
                    int i = stack.mDisplayId;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return i;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return 0;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public ActivityManager.StackInfo getFocusedStackInfo() throws RemoteException {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "getStackInfo()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack focusedStack = getTopDisplayFocusedStack();
                if (focusedStack != null) {
                    ActivityManager.StackInfo stackInfo = this.mRootActivityContainer.getStackInfo(focusedStack.mStackId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return stackInfo;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setFocusedStack(int stackId) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "setFocusedStack()");
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = this.mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    Slog.w("ActivityTaskManager", "setFocusedStack: No stack with id=" + stackId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ActivityRecord r = stack.topRunningActivityLocked();
                if (r != null && r.moveFocusableActivityToTop("setFocusedStack")) {
                    this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void setFocusedTask(int taskId) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "setFocusedTask()");
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
                if (task != null) {
                    ActivityRecord r = task.topRunningActivityLocked();
                    if (r != null && r.moveFocusableActivityToTop("setFocusedTask")) {
                        this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void restartActivityProcessIfVisible(IBinder activityToken) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "restartActivityProcess()");
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                if (r != null) {
                    r.restartProcessIfVisible();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public boolean removeTask(int taskId) {
        boolean removeTaskByIdLocked;
        enforceCallerIsRecentsOrHasPermission("android.permission.REMOVE_TASKS", "removeTask()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                removeTaskByIdLocked = this.mStackSupervisor.removeTaskByIdLocked(taskId, true, true, "remove-task");
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return removeTaskByIdLocked;
    }

    public void removeAllVisibleRecentTasks() {
        enforceCallerIsRecentsOrHasPermission("android.permission.REMOVE_TASKS", "removeAllVisibleRecentTasks()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                getRecentTasks().removeAllVisibleTasks(this.mAmInternal.getCurrentUserId());
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public boolean shouldUpRecreateTask(IBinder token, String destAffinity) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord srec = ActivityRecord.forTokenLocked(token);
                if (srec != null) {
                    boolean shouldUpRecreateTaskLocked = srec.getActivityStack().shouldUpRecreateTaskLocked(srec, destAffinity);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return shouldUpRecreateTaskLocked;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean navigateUpTo(IBinder token, Intent destIntent, int resultCode, Intent resultData) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r != null) {
                    boolean navigateUpToLocked = r.getActivityStack().navigateUpToLocked(r, destIntent, resultCode, resultData);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return navigateUpToLocked;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        enforceNotIsolatedCaller("moveActivityTaskToBack");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                int taskId = ActivityRecord.getTaskForActivityLocked(token, !nonRoot);
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId);
                if (task != null) {
                    boolean moveTaskToBackLocked = ActivityRecord.getStackLocked(token).moveTaskToBackLocked(taskId);
                    Binder.restoreCallingIdentity(origId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return moveTaskToBackLocked;
                }
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public Rect getTaskBounds(int taskId) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "getTaskBounds()");
        long ident = Binder.clearCallingIdentity();
        Rect rect = new Rect();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 1);
                if (task == null) {
                    Slog.w("ActivityTaskManager", "getTaskBounds: taskId=" + taskId + " not found");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return rect;
                }
                if (task.getStack() != null) {
                    task.getWindowContainerBounds(rect);
                } else if (!task.matchParentBounds()) {
                    rect.set(task.getBounds());
                } else if (task.mLastNonFullscreenBounds != null) {
                    rect.set(task.mLastNonFullscreenBounds);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return rect;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public ActivityManager.TaskDescription getTaskDescription(int id) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "getTaskDescription()");
                TaskRecord tr = this.mRootActivityContainer.anyTaskForId(id, 1);
                if (tr != null) {
                    ActivityManager.TaskDescription taskDescription = tr.lastTaskDescription;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return taskDescription;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return null;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop) {
        if (windowingMode == 3) {
            setTaskWindowingModeSplitScreenPrimary(taskId, 0, toTop, true, null, true);
            return;
        }
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "setTaskWindowingMode()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
                if (task == null) {
                    Slog.w("ActivityTaskManager", "setTaskWindowingMode: No task for id=" + taskId);
                    Binder.restoreCallingIdentity(ident);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityTaskManager", "setTaskWindowingMode: moving task=" + taskId + " to windowingMode=" + windowingMode + " toTop=" + toTop);
                }
                if (!task.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("setTaskWindowingMode: Attempt to move non-standard task " + taskId + " to windowing mode=" + windowingMode);
                }
                ActivityStack stack = task.getStack();
                if (toTop) {
                    stack.moveToFront("setTaskWindowingMode", task);
                }
                stack.setWindowingMode(windowingMode);
                Binder.restoreCallingIdentity(ident);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public String getCallingPackage(IBinder token) {
        String str;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = getCallingRecordLocked(token);
                str = r != null ? r.info.packageName : null;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return str;
    }

    public ComponentName getCallingActivity(IBinder token) {
        ComponentName component;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = getCallingRecordLocked(token);
                component = r != null ? r.intent.getComponent() : null;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return component;
    }

    private ActivityRecord getCallingRecordLocked(IBinder token) {
        ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r == null) {
            return null;
        }
        return r.resultTo;
    }

    public void unhandledBack() {
        this.mAmInternal.enforceCallingPermission("android.permission.FORCE_BACK", "unhandledBack()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                getTopDisplayFocusedStack().unhandledBackLocked();
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void onBackPressedOnTaskRoot(IBinder token, IRequestFinishCallback callback) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ActivityStack stack = r.getActivityStack();
                if (stack != null && stack.isSingleTaskInstance()) {
                    TaskRecord task = r.getTaskRecord();
                    this.mTaskChangeNotificationController.notifyBackPressedOnTaskRoot(task.getTaskInfo());
                } else {
                    try {
                        callback.requestFinish();
                    } catch (RemoteException e) {
                        Slog.e("ActivityTaskManager", "Failed to invoke request finish callback", e);
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void moveTaskToFront(IApplicationThread appThread, String callingPackage, int taskId, int flags, Bundle bOptions) {
        this.mAmInternal.enforceCallingPermission("android.permission.REORDER_TASKS", "moveTaskToFront()");
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.d("ActivityTaskManager", "moveTaskToFront: moving taskId=" + taskId);
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                moveTaskToFrontLocked(appThread, callingPackage, taskId, flags, SafeActivityOptions.fromBundle(bOptions), false);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void moveTaskToFrontLocked(IApplicationThread appThread, String callingPackage, int taskId, int flags, SafeActivityOptions options, boolean fromRecents) {
        WindowProcessController callerApp;
        ActivityOptions realOptions;
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        if (!isSameApp(callingUid, callingPackage)) {
            String msg = "Permission Denial: moveTaskToFrontLocked() from pid=" + Binder.getCallingPid() + " as package " + callingPackage;
            Slog.w("ActivityTaskManager", msg);
            throw new SecurityException(msg);
        } else if (!checkAppSwitchAllowedLocked(callingPid, callingUid, -1, -1, "Task to front")) {
            SafeActivityOptions.abort(options);
        } else {
            long origId = Binder.clearCallingIdentity();
            if (appThread != null) {
                WindowProcessController callerApp2 = getProcessController(appThread);
                callerApp = callerApp2;
            } else {
                callerApp = null;
            }
            ActivityStarter starter = getActivityStartController().obtainStarter(null, "moveTaskToFront");
            if (starter.shouldAbortBackgroundActivityStart(callingUid, callingPid, callingPackage, -1, -1, callerApp, null, false, null) && !isBackgroundActivityStartsEnabled()) {
                return;
            }
            try {
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId);
                if (task == null) {
                    Slog.d("ActivityTaskManager", "Could not find task for id: " + taskId);
                    SafeActivityOptions.abort(options);
                    Binder.restoreCallingIdentity(origId);
                } else if (getLockTaskController().isLockTaskModeViolation(task)) {
                    Slog.e("ActivityTaskManager", "moveTaskToFront: Attempt to violate Lock Task Mode");
                    SafeActivityOptions.abort(options);
                    Binder.restoreCallingIdentity(origId);
                } else {
                    if (options != null) {
                        try {
                            realOptions = options.getOptions(this.mStackSupervisor);
                        } catch (Throwable th) {
                            th = th;
                            Binder.restoreCallingIdentity(origId);
                            throw th;
                        }
                    } else {
                        realOptions = null;
                    }
                    this.mStackSupervisor.findTaskToMoveToFront(task, flags, realOptions, "moveTaskToFront", false);
                    ActivityRecord topActivity = task.getTopActivity();
                    if (topActivity != null) {
                        try {
                            topActivity.showStartingWindow(null, false, true, fromRecents);
                        } catch (Throwable th2) {
                            th = th2;
                            Binder.restoreCallingIdentity(origId);
                            throw th;
                        }
                    }
                    Binder.restoreCallingIdentity(origId);
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSameApp(int callingUid, String packageName) {
        if (callingUid != 0 && callingUid != 1000) {
            if (packageName == null) {
                return false;
            }
            try {
                int uid = AppGlobals.getPackageManager().getPackageUid(packageName, 268435456, UserHandle.getUserId(callingUid));
                return UserHandle.isSameApp(callingUid, uid);
            } catch (RemoteException e) {
                return true;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkAppSwitchAllowedLocked(int sourcePid, int sourceUid, int callingPid, int callingUid, String name) {
        if (this.mAppSwitchesAllowedTime >= SystemClock.uptimeMillis() && !getRecentTasks().isCallerRecents(sourceUid)) {
            int perm = checkComponentPermission("android.permission.STOP_APP_SWITCHES", sourcePid, sourceUid, -1, true);
            if (perm == 0 || checkAllowAppSwitchUid(sourceUid)) {
                return true;
            }
            if (callingUid != -1 && callingUid != sourceUid) {
                int perm2 = checkComponentPermission("android.permission.STOP_APP_SWITCHES", callingPid, callingUid, -1, true);
                if (perm2 == 0 || checkAllowAppSwitchUid(callingUid)) {
                    return true;
                }
            }
            Slog.w("ActivityTaskManager", name + " request from " + sourceUid + " stopped");
            return false;
        }
        return true;
    }

    private boolean checkAllowAppSwitchUid(int uid) {
        ArrayMap<String, Integer> types = this.mAllowAppSwitchUids.get(UserHandle.getUserId(uid));
        if (types != null) {
            for (int i = types.size() - 1; i >= 0; i--) {
                if (types.valueAt(i).intValue() == uid) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    public void setActivityController(IActivityController controller, boolean imAMonkey) {
        this.mAmInternal.enforceCallingPermission("android.permission.SET_ACTIVITY_WATCHER", "setActivityController()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mController = controller;
                this.mControllerIsAMonkey = imAMonkey;
                Watchdog.getInstance().setActivityController(controller);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public boolean isControllerAMonkey() {
        boolean z;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                z = this.mController != null && this.mControllerIsAMonkey;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return z;
    }

    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        int taskForActivityLocked;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                taskForActivityLocked = ActivityRecord.getTaskForActivityLocked(token, onlyRoot);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return taskForActivityLocked;
    }

    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        return getFilteredTasks(maxNum, 0, 0);
    }

    public List<ActivityManager.RunningTaskInfo> getFilteredTasks(int maxNum, @WindowConfiguration.ActivityType int ignoreActivityType, @WindowConfiguration.WindowingMode int ignoreWindowingMode) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        boolean crossUser = isCrossUserAllowed(callingPid, callingUid);
        int[] profileIds = getUserManager().getProfileIds(UserHandle.getUserId(callingUid), true);
        ArraySet<Integer> callingProfileIds = new ArraySet<>();
        for (int i : profileIds) {
            callingProfileIds.add(Integer.valueOf(i));
        }
        ArrayList<ActivityManager.RunningTaskInfo> list = new ArrayList<>();
        synchronized (this.mGlobalLock) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (ActivityTaskManagerDebugConfig.DEBUG_ALL) {
                        try {
                            StringBuilder sb = new StringBuilder();
                            sb.append("getTasks: max=");
                            sb.append(maxNum);
                            Slog.v("ActivityTaskManager", sb.toString());
                        } catch (Throwable th) {
                            th = th;
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    boolean allowed = isGetTasksAllowed("getTasks", callingPid, callingUid);
                    this.mRootActivityContainer.getRunningTasks(maxNum, list, ignoreActivityType, ignoreWindowingMode, callingUid, allowed, crossUser, callingProfileIds);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return list;
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public final void finishSubActivity(IBinder token, String resultWho, int requestCode) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    r.getActivityStack().finishSubActivityLocked(r, resultWho, requestCode);
                }
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public boolean willActivityBeVisible(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = ActivityRecord.getStackLocked(token);
                if (stack != null) {
                    boolean willActivityBeVisibleLocked = stack.willActivityBeVisibleLocked(token);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return willActivityBeVisibleLocked;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "moveTaskToStack()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId);
                if (task == null) {
                    Slog.w("ActivityTaskManager", "moveTaskToStack: No task for id=" + taskId);
                    Binder.restoreCallingIdentity(ident);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityTaskManager", "moveTaskToStack: moving task=" + taskId + " to stackId=" + stackId + " toTop=" + toTop);
                }
                ActivityStack stack = this.mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    throw new IllegalStateException("moveTaskToStack: No stack for stackId=" + stackId);
                } else if (!stack.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("moveTaskToStack: Attempt to move task " + taskId + " to stack " + stackId);
                } else {
                    if (stack.inSplitScreenPrimaryWindowingMode()) {
                        this.mWindowManager.setDockedStackCreateState(0, null);
                    }
                    task.reparent(stack, toTop, 1, true, false, "moveTaskToStack");
                    Binder.restoreCallingIdentity(ident);
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void resizeStack(int stackId, Rect destBounds, boolean allowResizeInDockedMode, boolean preserveWindows, boolean animate, int animationDuration) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "resizeStack()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (animate) {
                        try {
                            ActivityStack stack = this.mRootActivityContainer.getStack(stackId);
                            if (stack == null) {
                                Slog.w("ActivityTaskManager", "resizeStack: stackId " + stackId + " not found.");
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return;
                            } else if (stack.getWindowingMode() != 2) {
                                throw new IllegalArgumentException("Stack: " + stackId + " doesn't support animated resize.");
                            } else {
                                stack.animateResizePinnedStack(null, destBounds, animationDuration, false);
                            }
                        } catch (Throwable th) {
                            th = th;
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    } else {
                        ActivityStack stack2 = this.mRootActivityContainer.getStack(stackId);
                        if (stack2 == null) {
                            Slog.w("ActivityTaskManager", "resizeStack: stackId " + stackId + " not found.");
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                        this.mRootActivityContainer.resizeStack(stack2, destBounds, null, null, preserveWindows, allowResizeInDockedMode, false);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th2) {
                    th = th2;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void offsetPinnedStackBounds(int stackId, Rect compareBounds, int xOffset, int yOffset, int animationDuration) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "offsetPinnedStackBounds()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                if (xOffset == 0 && yOffset == 0) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ActivityStack stack = this.mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    Slog.w("ActivityTaskManager", "offsetPinnedStackBounds: stackId " + stackId + " not found.");
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (stack.getWindowingMode() != 2) {
                    throw new IllegalArgumentException("Stack: " + stackId + " doesn't support animated resize.");
                } else {
                    Rect destBounds = new Rect();
                    stack.getAnimationOrCurrentBounds(destBounds);
                    if (!destBounds.isEmpty() && destBounds.equals(compareBounds)) {
                        destBounds.offset(xOffset, yOffset);
                        stack.animateResizePinnedStack(null, destBounds, animationDuration, false);
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    Slog.w("ActivityTaskManager", "The current stack bounds does not matched! It may be obsolete.");
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean setTaskWindowingModeSplitScreenPrimary(int taskId, int createMode, boolean toTop, boolean animate, Rect initialBounds, boolean showRecents) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "setTaskWindowingModeSplitScreenPrimary()");
        synchronized (this.mGlobalLock) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    long ident = Binder.clearCallingIdentity();
                    try {
                        TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
                        if (task == null) {
                            Slog.w("ActivityTaskManager", "setTaskWindowingModeSplitScreenPrimary: No task for id=" + taskId);
                            Binder.restoreCallingIdentity(ident);
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return false;
                        }
                        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                            Slog.d("ActivityTaskManager", "setTaskWindowingModeSplitScreenPrimary: moving task=" + taskId + " to createMode=" + createMode + " toTop=" + toTop);
                        }
                        try {
                            if (!task.isActivityTypeStandardOrUndefined()) {
                                throw new IllegalArgumentException("setTaskWindowingMode: Attempt to move non-standard task " + taskId + " to split-screen windowing mode");
                            }
                            this.mWindowManager.setDockedStackCreateState(createMode, initialBounds);
                            int windowingMode = task.getWindowingMode();
                            ActivityStack stack = task.getStack();
                            if (toTop) {
                                stack.moveToFront("setTaskWindowingModeSplitScreenPrimary", task);
                            }
                            stack.setWindowingMode(3, animate, showRecents, false, false, false);
                            boolean z = windowingMode != task.getWindowingMode();
                            Binder.restoreCallingIdentity(ident);
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return z;
                        } catch (Throwable th) {
                            th = th;
                            Binder.restoreCallingIdentity(ident);
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            } catch (Throwable th4) {
                th = th4;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void removeStacksInWindowingModes(int[] windowingModes) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "removeStacksInWindowingModes()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                this.mRootActivityContainer.removeStacksInWindowingModes(windowingModes);
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void removeStacksWithActivityTypes(int[] activityTypes) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "removeStacksWithActivityTypes()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                this.mRootActivityContainer.removeStacksWithActivityTypes(activityTypes);
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags, int userId) {
        ParceledListSlice<ActivityManager.RecentTaskInfo> recentTasks;
        int callingUid = Binder.getCallingUid();
        int userId2 = handleIncomingUser(Binder.getCallingPid(), callingUid, userId, "getRecentTasks");
        boolean allowed = isGetTasksAllowed("getRecentTasks", Binder.getCallingPid(), callingUid);
        boolean detailed = checkGetTasksPermission("android.permission.GET_DETAILED_TASKS", Binder.getCallingPid(), UserHandle.getAppId(callingUid)) == 0;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                recentTasks = this.mRecentTasks.getRecentTasks(maxNum, flags, allowed, detailed, userId2, callingUid);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return recentTasks;
    }

    public List<ActivityManager.StackInfo> getAllStackInfos() {
        ArrayList<ActivityManager.StackInfo> allStackInfos;
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "getAllStackInfos()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                allStackInfos = this.mRootActivityContainer.getAllStackInfos();
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return allStackInfos;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public ActivityManager.StackInfo getStackInfo(int windowingMode, int activityType) {
        ActivityManager.StackInfo stackInfo;
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "getStackInfo()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                stackInfo = this.mRootActivityContainer.getStackInfo(windowingMode, activityType);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return stackInfo;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void cancelRecentsAnimation(boolean restoreHomeStackPosition) {
        int i;
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "cancelRecentsAnimation()");
        long callingUid = Binder.getCallingUid();
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                WindowManagerService windowManagerService = this.mWindowManager;
                if (restoreHomeStackPosition) {
                    i = 2;
                } else {
                    i = 0;
                }
                windowManagerService.cancelRecentsAnimationSynchronously(i, "cancelRecentsAnimation/uid=" + callingUid);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void startLockTaskModeByToken(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                startLockTaskModeLocked(r.getTaskRecord(), false);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void startSystemLockTaskMode(int taskId) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "startSystemLockTaskMode");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
                if (task != null) {
                    task.getStack().moveToFront("startSystemLockTaskMode");
                    startLockTaskModeLocked(task, true);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void stopLockTaskModeByToken(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                stopLockTaskModeInternal(r.getTaskRecord(), false);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void stopSystemLockTaskMode() throws RemoteException {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "stopSystemLockTaskMode");
        stopLockTaskModeInternal(null, true);
    }

    private void startLockTaskModeLocked(TaskRecord task, boolean isSystemCaller) {
        if (ActivityTaskManagerDebugConfig.DEBUG_LOCKTASK) {
            Slog.w("ActivityTaskManager", "startLockTaskModeLocked: " + task);
        }
        if (task == null || task.mLockTaskAuth == 0) {
            return;
        }
        ActivityStack stack = this.mRootActivityContainer.getTopDisplayFocusedStack();
        if (stack == null || task != stack.topTask()) {
            throw new IllegalArgumentException("Invalid task, not in foreground");
        }
        int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            this.mRootActivityContainer.removeStacksInWindowingModes(2);
            getLockTaskController().startLockTaskMode(task, isSystemCaller, callingUid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void stopLockTaskModeInternal(TaskRecord task, boolean isSystemCaller) {
        int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                getLockTaskController().stopLockTaskMode(task, isSystemCaller, callingUid);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            TelecomManager tm = (TelecomManager) this.mContext.getSystemService("telecom");
            if (tm != null) {
                tm.showInCallScreen(false);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void updateLockTaskPackages(int userId, String[] packages) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != 1000) {
            this.mAmInternal.enforceCallingPermission("android.permission.UPDATE_LOCK_TASK_PACKAGES", "updateLockTaskPackages()");
        }
        synchronized (this) {
            if (ActivityTaskManagerDebugConfig.DEBUG_LOCKTASK) {
                Slog.w("ActivityTaskManager", "Whitelisting " + userId + ":" + Arrays.toString(packages));
            }
            getLockTaskController().updateLockTaskPackages(userId, packages);
        }
    }

    public boolean isInLockTaskMode() {
        return getLockTaskModeState() != 0;
    }

    public int getLockTaskModeState() {
        int lockTaskModeState;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                lockTaskModeState = getLockTaskController().getLockTaskModeState();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return lockTaskModeState;
    }

    public void setTaskDescription(IBinder token, ActivityManager.TaskDescription td) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    r.setTaskDescription(td);
                    TaskRecord task = r.getTaskRecord();
                    task.updateTaskDescription();
                    this.mTaskChangeNotificationController.notifyTaskDescriptionChanged(task.getTaskInfo());
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public Bundle getActivityOptions(IBinder token) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                Bundle bundle = null;
                if (r != null) {
                    ActivityOptions activityOptions = r.takeOptionsLocked(true);
                    if (activityOptions != null) {
                        bundle = activityOptions.toBundle();
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return bundle;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public List<IBinder> getAppTasks(String callingPackage) {
        ArrayList<IBinder> appTasksList;
        int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                appTasksList = this.mRecentTasks.getAppTasksList(callingUid, callingPackage);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return appTasksList;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void finishVoiceTask(IVoiceInteractionSession session) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                this.mRootActivityContainer.finishVoiceTask(session);
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public boolean isTopOfTask(IBinder token) {
        boolean z;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                z = r != null && r.getTaskRecord().getTopActivity() == r;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return z;
    }

    public void notifyLaunchTaskBehindComplete(IBinder token) {
        this.mStackSupervisor.scheduleLaunchTaskBehindComplete(token);
    }

    public void notifyEnterAnimationComplete(final IBinder token) {
        this.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$BXul1K8BX6JEv_ff3NT76qpeZGQ
            @Override // java.lang.Runnable
            public final void run() {
                ActivityTaskManagerService.this.lambda$notifyEnterAnimationComplete$1$ActivityTaskManagerService(token);
            }
        });
    }

    public /* synthetic */ void lambda$notifyEnterAnimationComplete$1$ActivityTaskManagerService(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r != null && r.attachedToProcess()) {
                    try {
                        r.app.getThread().scheduleEnterAnimationComplete(r.appToken);
                    } catch (RemoteException e) {
                    }
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void reportAssistContextExtras(IBinder token, Bundle extras, AssistStructure structure, AssistContent content, Uri referrer) {
        PendingAssistExtras pae = (PendingAssistExtras) token;
        synchronized (pae) {
            pae.result = extras;
            pae.structure = structure;
            pae.content = content;
            if (referrer != null) {
                pae.extras.putParcelable("android.intent.extra.REFERRER", referrer);
            }
            if (structure != null) {
                structure.setTaskId(pae.activity.getTaskRecord().taskId);
                structure.setActivityComponent(pae.activity.mActivityComponent);
                structure.setHomeActivity(pae.isHome);
            }
            pae.haveResult = true;
            pae.notifyAll();
            if (pae.intent == null && pae.receiver == null) {
                return;
            }
            Bundle sendBundle = null;
            synchronized (this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    buildAssistBundleLocked(pae, extras);
                    boolean exists = this.mPendingAssistExtras.remove(pae);
                    this.mUiHandler.removeCallbacks(pae);
                    if (!exists) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    IAssistDataReceiver sendReceiver = pae.receiver;
                    if (sendReceiver != null) {
                        sendBundle = new Bundle();
                        sendBundle.putInt(ActivityTaskManagerInternal.ASSIST_TASK_ID, pae.activity.getTaskRecord().taskId);
                        sendBundle.putBinder(ActivityTaskManagerInternal.ASSIST_ACTIVITY_ID, pae.activity.assistToken);
                        sendBundle.putBundle("data", pae.extras);
                        sendBundle.putParcelable(ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE, pae.structure);
                        sendBundle.putParcelable(ActivityTaskManagerInternal.ASSIST_KEY_CONTENT, pae.content);
                        sendBundle.putBundle(ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS, pae.receiverExtras);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (sendReceiver != null) {
                        try {
                            sendReceiver.onHandleAssistData(sendBundle);
                            return;
                        } catch (RemoteException e) {
                            return;
                        }
                    }
                    long ident = Binder.clearCallingIdentity();
                    try {
                        if (TextUtils.equals(pae.intent.getAction(), "android.service.voice.VoiceInteractionService")) {
                            pae.intent.putExtras(pae.extras);
                            startVoiceInteractionServiceAsUser(pae.intent, pae.userHandle, "AssistContext");
                        } else {
                            pae.intent.replaceExtras(pae.extras);
                            pae.intent.setFlags(872415232);
                            this.mInternal.closeSystemDialogs(PhoneWindowManager.SYSTEM_DIALOG_REASON_ASSIST);
                            try {
                                this.mContext.startActivityAsUser(pae.intent, new UserHandle(pae.userHandle));
                            } catch (ActivityNotFoundException e2) {
                                Slog.w("ActivityTaskManager", "No activity to handle assist action.", e2);
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }
    }

    private void startVoiceInteractionServiceAsUser(Intent intent, int userHandle, String reason) {
        ResolveInfo resolveInfo = this.mContext.getPackageManager().resolveServiceAsUser(intent, 0, userHandle);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.e("ActivityTaskManager", "VoiceInteractionService intent does not resolve. Not starting.");
            return;
        }
        intent.setPackage(resolveInfo.serviceInfo.packageName);
        ((DeviceIdleController.LocalService) LocalServices.getService(DeviceIdleController.LocalService.class)).addPowerSaveTempWhitelistApp(Process.myUid(), intent.getPackage(), APP_SWITCH_DELAY_TIME, userHandle, false, reason);
        try {
            this.mContext.startServiceAsUser(intent, UserHandle.of(userHandle));
        } catch (RuntimeException e) {
            Slog.e("ActivityTaskManager", "VoiceInteractionService failed to start.", e);
        }
    }

    public int addAppTask(IBinder activityToken, Intent intent, ActivityManager.TaskDescription description, Bitmap thumbnail) throws RemoteException {
        int callingUid = Binder.getCallingUid();
        long callingIdent = Binder.clearCallingIdentity();
        try {
            try {
                synchronized (this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                        try {
                            if (r == null) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("Activity does not exist; token=");
                                sb.append(activityToken);
                                throw new IllegalArgumentException(sb.toString());
                            }
                            try {
                                ComponentName comp = intent.getComponent();
                                if (comp == null) {
                                    throw new IllegalArgumentException("Intent " + intent + " must specify explicit component");
                                }
                                if (thumbnail.getWidth() == this.mThumbnailWidth && thumbnail.getHeight() == this.mThumbnailHeight) {
                                    if (intent.getSelector() != null) {
                                        intent.setSelector(null);
                                    }
                                    if (intent.getSourceBounds() != null) {
                                        intent.setSourceBounds(null);
                                    }
                                    if ((intent.getFlags() & DumpState.DUMP_FROZEN) != 0 && (intent.getFlags() & 8192) == 0) {
                                        intent.addFlags(8192);
                                    }
                                    ActivityInfo ainfo = AppGlobals.getPackageManager().getActivityInfo(comp, 1024, UserHandle.getUserId(callingUid));
                                    if (ainfo.applicationInfo.uid != callingUid) {
                                        throw new SecurityException("Can't add task for another application: target uid=" + ainfo.applicationInfo.uid + ", calling uid=" + callingUid);
                                    }
                                    ActivityStack stack = r.getActivityStack();
                                    TaskRecord task = stack.createTaskRecord(this.mStackSupervisor.getNextTaskIdForUserLocked(r.mUserId), ainfo, intent, null, null, false);
                                    if (!this.mRecentTasks.addToBottom(task)) {
                                        stack.removeTask(task, "addAppTask", 0);
                                        WindowManagerService.resetPriorityAfterLockedSection();
                                        Binder.restoreCallingIdentity(callingIdent);
                                        return -1;
                                    }
                                    task.lastTaskDescription.copyFrom(description);
                                    int i = task.taskId;
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                    Binder.restoreCallingIdentity(callingIdent);
                                    return i;
                                }
                                throw new IllegalArgumentException("Bad thumbnail size: got " + thumbnail.getWidth() + "x" + thumbnail.getHeight() + ", require " + this.mThumbnailWidth + "x" + this.mThumbnailHeight);
                            } catch (Throwable th) {
                                th = th;
                                try {
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                    throw th;
                                } catch (Throwable th2) {
                                    th = th2;
                                    Binder.restoreCallingIdentity(callingIdent);
                                    throw th;
                                }
                            }
                        } catch (Throwable th3) {
                            th = th3;
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
        }
    }

    public Point getAppTaskThumbnailSize() {
        Point point;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                point = new Point(this.mThumbnailWidth, this.mThumbnailHeight);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return point;
    }

    public void setTaskResizeable(int taskId, int resizeableMode) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 1);
                if (task == null) {
                    Slog.w("ActivityTaskManager", "setTaskResizeable: taskId=" + taskId + " not found");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                task.setResizeMode(resizeableMode);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void resizeTask(int taskId, Rect bounds, int resizeMode) {
        ActivityStack stack;
        boolean preserveWindow;
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "resizeTask()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
                if (task == null) {
                    Slog.w("ActivityTaskManager", "resizeTask: taskId=" + taskId + " not found");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ActivityStack stack2 = task.getStack();
                if (!task.getWindowConfiguration().canResizeTask()) {
                    throw new IllegalArgumentException("resizeTask not allowed on task=" + task);
                }
                boolean z = true;
                if (bounds == null && stack2.getWindowingMode() == 5) {
                    stack = stack2.getDisplay().getOrCreateStack(1, stack2.getActivityType(), true);
                } else if (bounds != null && stack2.getWindowingMode() != 5) {
                    stack = stack2.getDisplay().getOrCreateStack(5, stack2.getActivityType(), true);
                } else {
                    stack = stack2;
                }
                if ((resizeMode & 1) == 0) {
                    z = false;
                }
                boolean preserveWindow2 = z;
                if (stack != task.getStack()) {
                    task.reparent(stack, true, 1, true, true, "resizeTask");
                    preserveWindow = false;
                } else {
                    preserveWindow = preserveWindow2;
                }
                task.resize(bounds, resizeMode, preserveWindow, false);
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean releaseActivityInstance(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    boolean safelyDestroyActivityLocked = r.getActivityStack().safelyDestroyActivityLocked(r, "app-req");
                    Binder.restoreCallingIdentity(origId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return safelyDestroyActivityLocked;
                }
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void releaseSomeActivities(IApplicationThread appInt) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                WindowProcessController app = getProcessController(appInt);
                this.mRootActivityContainer.releaseSomeActivitiesLocked(app, "low-mem");
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void setLockScreenShown(final boolean keyguardShowing, boolean aodShowing) {
        if (checkCallingPermission("android.permission.DEVICE_POWER") != 0) {
            throw new SecurityException("Requires permission android.permission.DEVICE_POWER");
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                if (this.mKeyguardShown != keyguardShowing) {
                    this.mKeyguardShown = keyguardShowing;
                    Message msg = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$zwLNi4Hz7werGBGptK8eYRpBWpw
                        @Override // java.util.function.BiConsumer
                        public final void accept(Object obj, Object obj2) {
                            ((ActivityManagerInternal) obj).reportCurKeyguardUsageEvent(((Boolean) obj2).booleanValue());
                        }
                    }, this.mAmInternal, Boolean.valueOf(keyguardShowing));
                    this.mH.sendMessage(msg);
                }
                this.mKeyguardController.setKeyguardShown(keyguardShowing, aodShowing);
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        this.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$iduseKQrjIWQYD0hJ8Q5DMmuSfE
            @Override // java.lang.Runnable
            public final void run() {
                ActivityTaskManagerService.this.lambda$setLockScreenShown$2$ActivityTaskManagerService(keyguardShowing);
            }
        });
    }

    public /* synthetic */ void lambda$setLockScreenShown$2$ActivityTaskManagerService(boolean keyguardShowing) {
        for (int i = this.mScreenObservers.size() - 1; i >= 0; i--) {
            this.mScreenObservers.get(i).onKeyguardStateChanged(keyguardShowing);
        }
    }

    public void onScreenAwakeChanged(final boolean isAwake) {
        this.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$Uli7s8UWTEj0IpBUtoST5bmgvKk
            @Override // java.lang.Runnable
            public final void run() {
                ActivityTaskManagerService.this.lambda$onScreenAwakeChanged$3$ActivityTaskManagerService(isAwake);
            }
        });
    }

    public /* synthetic */ void lambda$onScreenAwakeChanged$3$ActivityTaskManagerService(boolean isAwake) {
        for (int i = this.mScreenObservers.size() - 1; i >= 0; i--) {
            this.mScreenObservers.get(i).onAwakeStateChanged(isAwake);
        }
    }

    public Bitmap getTaskDescriptionIcon(String filePath, int userId) {
        int userId2 = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, "getTaskDescriptionIcon");
        File passedIconFile = new File(filePath);
        File legitIconFile = new File(TaskPersister.getUserImagesDir(userId2), passedIconFile.getName());
        if (!legitIconFile.getPath().equals(filePath) || !filePath.contains("_activity_icon_")) {
            throw new IllegalArgumentException("Bad file path: " + filePath + " passed for userId " + userId2);
        }
        return this.mRecentTasks.getTaskDescriptionIcon(filePath);
    }

    public void startInPlaceAnimationOnFrontMostApplication(Bundle opts) {
        ActivityOptions activityOptions;
        SafeActivityOptions safeOptions = SafeActivityOptions.fromBundle(opts);
        if (safeOptions != null) {
            activityOptions = safeOptions.getOptions(this.mStackSupervisor);
        } else {
            activityOptions = null;
        }
        if (activityOptions == null || activityOptions.getAnimationType() != 10 || activityOptions.getCustomInPlaceResId() == 0) {
            throw new IllegalArgumentException("Expected in-place ActivityOption with valid animation");
        }
        ActivityStack focusedStack = getTopDisplayFocusedStack();
        if (focusedStack != null) {
            DisplayContent dc = focusedStack.getDisplay().mDisplayContent;
            dc.prepareAppTransition(17, false);
            dc.mAppTransition.overrideInPlaceAppTransition(activityOptions.getPackageName(), activityOptions.getCustomInPlaceResId());
            dc.executeAppTransition();
        }
    }

    public void removeStack(int stackId) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "removeStack()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                ActivityStack stack = this.mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    Slog.w("ActivityTaskManager", "removeStack: No stack with id=" + stackId);
                    Binder.restoreCallingIdentity(ident);
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (!stack.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("Removing non-standard stack is not allowed.");
                } else {
                    this.mStackSupervisor.removeStack(stack);
                    Binder.restoreCallingIdentity(ident);
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void moveStackToDisplay(int stackId, int displayId) {
        this.mAmInternal.enforceCallingPermission("android.permission.INTERNAL_SYSTEM_WINDOW", "moveStackToDisplay()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityTaskManager", "moveStackToDisplay: moving stackId=" + stackId + " to displayId=" + displayId);
                }
                this.mRootActivityContainer.moveStackToDisplay(stackId, displayId, true);
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void toggleFreeformWindowingMode(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    throw new IllegalArgumentException("toggleFreeformWindowingMode: No activity record matching token=" + token);
                }
                ActivityStack stack = r.getActivityStack();
                if (stack == null) {
                    throw new IllegalStateException("toggleFreeformWindowingMode: the activity doesn't have a stack");
                }
                if (!stack.inFreeformWindowingMode() && stack.getWindowingMode() != 1) {
                    throw new IllegalStateException("toggleFreeformWindowingMode: You can only toggle between fullscreen and freeform.");
                }
                if (stack.inFreeformWindowingMode()) {
                    stack.setWindowingMode(1);
                } else if (stack.getParent().inFreeformWindowingMode()) {
                    stack.setWindowingMode(0);
                } else {
                    stack.setWindowingMode(5);
                }
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void registerTaskStackListener(ITaskStackListener listener) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "registerTaskStackListener()");
        this.mTaskChangeNotificationController.registerTaskStackListener(listener);
    }

    public void unregisterTaskStackListener(ITaskStackListener listener) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "unregisterTaskStackListener()");
        this.mTaskChangeNotificationController.unregisterTaskStackListener(listener);
    }

    public boolean requestAssistContextExtras(int requestType, IAssistDataReceiver receiver, Bundle receiverExtras, IBinder activityToken, boolean focused, boolean newSessionId) {
        return enqueueAssistContext(requestType, null, null, receiver, receiverExtras, activityToken, focused, newSessionId, UserHandle.getCallingUserId(), null, 2000L, 0) != null;
    }

    public boolean requestAutofillData(IAssistDataReceiver receiver, Bundle receiverExtras, IBinder activityToken, int flags) {
        return enqueueAssistContext(2, null, null, receiver, receiverExtras, activityToken, true, true, UserHandle.getCallingUserId(), null, 2000L, flags) != null;
    }

    public boolean launchAssistIntent(Intent intent, int requestType, String hint, int userHandle, Bundle args) {
        return enqueueAssistContext(requestType, intent, hint, null, null, null, true, true, userHandle, args, 500L, 0) != null;
    }

    public Bundle getAssistContextExtras(int requestType) {
        PendingAssistExtras pae = enqueueAssistContext(requestType, null, null, null, null, null, true, true, UserHandle.getCallingUserId(), null, 500L, 0);
        if (pae == null) {
            return null;
        }
        synchronized (pae) {
            while (!pae.haveResult) {
                try {
                    pae.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                buildAssistBundleLocked(pae, pae.result);
                this.mPendingAssistExtras.remove(pae);
                this.mUiHandler.removeCallbacks(pae);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return pae.extras;
    }

    private static int checkCallingPermission(String permission) {
        return checkPermission(permission, Binder.getCallingPid(), UserHandle.getAppId(Binder.getCallingUid()));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceCallerIsRecentsOrHasPermission(String permission, String func) {
        if (!getRecentTasks().isCallerRecents(Binder.getCallingUid())) {
            this.mAmInternal.enforceCallingPermission(permission, func);
        }
    }

    @VisibleForTesting
    int checkGetTasksPermission(String permission, int pid, int uid) {
        return checkPermission(permission, pid, uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            return -1;
        }
        return checkComponentPermission(permission, pid, uid, -1, true);
    }

    public static int checkComponentPermission(String permission, int pid, int uid, int owningUid, boolean exported) {
        return ActivityManagerService.checkComponentPermission(permission, pid, uid, owningUid, exported);
    }

    boolean isGetTasksAllowed(String caller, int callingPid, int callingUid) {
        if (getRecentTasks().isCallerRecents(callingUid)) {
            return true;
        }
        boolean allowed = checkGetTasksPermission("android.permission.REAL_GET_TASKS", callingPid, callingUid) == 0;
        if (!allowed) {
            if (checkGetTasksPermission("android.permission.GET_TASKS", callingPid, callingUid) == 0) {
                try {
                    if (AppGlobals.getPackageManager().isUidPrivileged(callingUid)) {
                        allowed = true;
                        if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                            Slog.w("ActivityTaskManager", caller + ": caller " + callingUid + " is using old GET_TASKS but privileged; allowing");
                        }
                    }
                } catch (RemoteException e) {
                }
            }
            if (ActivityTaskManagerDebugConfig.DEBUG_TASKS) {
                Slog.w("ActivityTaskManager", caller + ": caller " + callingUid + " does not hold REAL_GET_TASKS; limiting output");
            }
        }
        return allowed;
    }

    boolean isCrossUserAllowed(int pid, int uid) {
        return checkPermission("android.permission.INTERACT_ACROSS_USERS", pid, uid) == 0 || checkPermission("android.permission.INTERACT_ACROSS_USERS_FULL", pid, uid) == 0;
    }

    private PendingAssistExtras enqueueAssistContext(int requestType, Intent intent, String hint, IAssistDataReceiver receiver, Bundle receiverExtras, IBinder activityToken, boolean focused, boolean newSessionId, int userHandle, Bundle args, long timeout, int flags) {
        ActivityRecord activity;
        ActivityRecord caller;
        this.mAmInternal.enforceCallingPermission("android.permission.GET_TOP_ACTIVITY_INFO", "enqueueAssistContext()");
        synchronized (this.mGlobalLock) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityRecord activity2 = getTopDisplayFocusedStack().getTopActivity();
                    if (activity2 == null) {
                        Slog.w("ActivityTaskManager", "getAssistContextExtras failed: no top activity");
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    } else if (!activity2.attachedToProcess()) {
                        Slog.w("ActivityTaskManager", "getAssistContextExtras failed: no process for " + activity2);
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    } else {
                        if (focused) {
                            if (activityToken != null && activity2 != (caller = ActivityRecord.forTokenLocked(activityToken))) {
                                Slog.w("ActivityTaskManager", "enqueueAssistContext failed: caller " + caller + " is not current top " + activity2);
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return null;
                            }
                            activity = activity2;
                        } else {
                            ActivityRecord activity3 = ActivityRecord.forTokenLocked(activityToken);
                            if (activity3 == null) {
                                Slog.w("ActivityTaskManager", "enqueueAssistContext failed: activity for token=" + activityToken + " couldn't be found");
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return null;
                            } else if (activity3.attachedToProcess()) {
                                activity = activity3;
                            } else {
                                Slog.w("ActivityTaskManager", "enqueueAssistContext failed: no process for " + activity3);
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return null;
                            }
                        }
                        Bundle extras = new Bundle();
                        if (args != null) {
                            extras.putAll(args);
                        }
                        extras.putString("android.intent.extra.ASSIST_PACKAGE", activity.packageName);
                        extras.putInt("android.intent.extra.ASSIST_UID", activity.app.mUid);
                        PendingAssistExtras pae = new PendingAssistExtras(activity, extras, intent, hint, receiver, receiverExtras, userHandle);
                        pae.isHome = activity.isActivityTypeHome();
                        if (newSessionId) {
                            this.mViSessionId++;
                        }
                        try {
                            activity.app.getThread().requestAssistContextExtras(activity.appToken, pae, requestType, this.mViSessionId, flags);
                            this.mPendingAssistExtras.add(pae);
                            try {
                                this.mUiHandler.postDelayed(pae, timeout);
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return pae;
                            } catch (RemoteException e) {
                                Slog.w("ActivityTaskManager", "getAssistContextExtras failed: crash calling " + activity);
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return null;
                            }
                        } catch (RemoteException e2) {
                        }
                    }
                } catch (Throwable th) {
                    e = th;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw e;
                }
            } catch (Throwable th2) {
                e = th2;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw e;
            }
        }
    }

    private void buildAssistBundleLocked(PendingAssistExtras pae, Bundle result) {
        if (result != null) {
            pae.extras.putBundle("android.intent.extra.ASSIST_CONTEXT", result);
        }
        if (pae.hint != null) {
            pae.extras.putBoolean(pae.hint, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pendingAssistExtrasTimedOut(PendingAssistExtras pae) {
        IAssistDataReceiver receiver;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mPendingAssistExtras.remove(pae);
                receiver = pae.receiver;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (receiver != null) {
            Bundle sendBundle = new Bundle();
            sendBundle.putBundle(ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS, pae.receiverExtras);
            try {
                pae.receiver.onHandleAssistData(sendBundle);
            } catch (RemoteException e) {
            }
        }
    }

    /* loaded from: classes2.dex */
    public class PendingAssistExtras extends Binder implements Runnable {
        public final ActivityRecord activity;
        public final Bundle extras;
        public final String hint;
        public final Intent intent;
        public boolean isHome;
        public final IAssistDataReceiver receiver;
        public Bundle receiverExtras;
        public final int userHandle;
        public boolean haveResult = false;
        public Bundle result = null;
        public AssistStructure structure = null;
        public AssistContent content = null;

        public PendingAssistExtras(ActivityRecord _activity, Bundle _extras, Intent _intent, String _hint, IAssistDataReceiver _receiver, Bundle _receiverExtras, int _userHandle) {
            this.activity = _activity;
            this.extras = _extras;
            this.intent = _intent;
            this.hint = _hint;
            this.receiver = _receiver;
            this.receiverExtras = _receiverExtras;
            this.userHandle = _userHandle;
        }

        @Override // java.lang.Runnable
        public void run() {
            Slog.w("ActivityTaskManager", "getAssistContextExtras failed: timeout retrieving from " + this.activity);
            synchronized (this) {
                this.haveResult = true;
                notifyAll();
            }
            ActivityTaskManagerService.this.pendingAssistExtrasTimedOut(this);
        }
    }

    public boolean isAssistDataAllowedOnCurrentActivity() {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack focusedStack = getTopDisplayFocusedStack();
                if (focusedStack != null && !focusedStack.isActivityTypeAssistant()) {
                    ActivityRecord activity = focusedStack.getTopActivity();
                    if (activity == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return false;
                    }
                    int userId = activity.mUserId;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return !DevicePolicyCache.getInstance().getScreenCaptureDisabled(userId);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean showAssistFromActivity(IBinder token, Bundle args) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord caller = ActivityRecord.forTokenLocked(token);
                ActivityRecord top = getTopDisplayFocusedStack().getTopActivity();
                if (top != caller) {
                    Slog.w("ActivityTaskManager", "showAssistFromActivity failed: caller " + caller + " is not current top " + top);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } else if (top.nowVisible) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return this.mAssistUtils.showSessionForActiveService(args, 8, (IVoiceInteractionSessionShowCallback) null, token);
                } else {
                    Slog.w("ActivityTaskManager", "showAssistFromActivity failed: caller " + caller + " is not visible");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean isRootVoiceInteraction(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                boolean z = r.rootVoiceInteraction;
                WindowManagerService.resetPriorityAfterLockedSection();
                return z;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocalVoiceInteractionStartedLocked(IBinder activity, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
        ActivityRecord activityToCallback = ActivityRecord.forTokenLocked(activity);
        if (activityToCallback == null) {
            return;
        }
        activityToCallback.setVoiceSessionLocked(voiceSession);
        try {
            activityToCallback.app.getThread().scheduleLocalVoiceInteractionStarted(activity, voiceInteractor);
            long token = Binder.clearCallingIdentity();
            startRunningVoiceLocked(voiceSession, activityToCallback.appInfo.uid);
            Binder.restoreCallingIdentity(token);
        } catch (RemoteException e) {
            activityToCallback.clearVoiceSessionLocked();
        }
    }

    private void startRunningVoiceLocked(IVoiceInteractionSession session, int targetUid) {
        Slog.d("ActivityTaskManager", "<<<  startRunningVoiceLocked()");
        this.mVoiceWakeLock.setWorkSource(new WorkSource(targetUid));
        IVoiceInteractionSession iVoiceInteractionSession = this.mRunningVoice;
        if (iVoiceInteractionSession == null || iVoiceInteractionSession.asBinder() != session.asBinder()) {
            boolean wasRunningVoice = this.mRunningVoice != null;
            this.mRunningVoice = session;
            if (!wasRunningVoice) {
                this.mVoiceWakeLock.acquire();
                updateSleepIfNeededLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishRunningVoiceLocked() {
        if (this.mRunningVoice != null) {
            this.mRunningVoice = null;
            this.mVoiceWakeLock.release();
            updateSleepIfNeededLocked();
        }
    }

    public void setVoiceKeepAwake(IVoiceInteractionSession session, boolean keepAwake) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mRunningVoice != null && this.mRunningVoice.asBinder() == session.asBinder()) {
                    if (keepAwake) {
                        this.mVoiceWakeLock.acquire();
                    } else {
                        this.mVoiceWakeLock.release();
                    }
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public ComponentName getActivityClassForToken(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return null;
                }
                ComponentName component = r.intent.getComponent();
                WindowManagerService.resetPriorityAfterLockedSection();
                return component;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public String getPackageForToken(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return null;
                }
                String str = r.packageName;
                WindowManagerService.resetPriorityAfterLockedSection();
                return str;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void showLockTaskEscapeMessage(IBinder token) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                getLockTaskController().showLockTaskToast();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void keyguardGoingAway(int flags) {
        enforceNotIsolatedCaller("keyguardGoingAway");
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                this.mKeyguardController.keyguardGoingAway(flags);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void positionTaskInStack(int taskId, int stackId, int position) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "positionTaskInStack()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long ident = Binder.clearCallingIdentity();
                if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                    Slog.d("ActivityTaskManager", "positionTaskInStack: positioning task=" + taskId + " in stackId=" + stackId + " at position=" + position);
                }
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId);
                if (task == null) {
                    throw new IllegalArgumentException("positionTaskInStack: no task for id=" + taskId);
                }
                ActivityStack stack = this.mRootActivityContainer.getStack(stackId);
                if (stack == null) {
                    throw new IllegalArgumentException("positionTaskInStack: no stack for id=" + stackId);
                } else if (!stack.isActivityTypeStandardOrUndefined()) {
                    throw new IllegalArgumentException("positionTaskInStack: Attempt to change the position of task " + taskId + " in/to non-standard stack");
                } else {
                    if (task.getStack() == stack) {
                        stack.positionChildAt(task, position);
                    } else {
                        task.reparent(stack, position, 2, false, false, "positionTaskInStack");
                    }
                    Binder.restoreCallingIdentity(ident);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void reportSizeConfigurations(IBinder token, int[] horizontalSizeConfiguration, int[] verticalSizeConfigurations, int[] smallestSizeConfigurations) {
        if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
            Slog.v("ActivityTaskManager", "Report configuration: " + token + " " + horizontalSizeConfiguration + " " + verticalSizeConfigurations);
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord record = ActivityRecord.isInStackLocked(token);
                if (record == null) {
                    throw new IllegalArgumentException("reportSizeConfigurations: ActivityRecord not found for: " + token);
                }
                record.setSizeConfigurations(horizontalSizeConfiguration, verticalSizeConfigurations, smallestSizeConfigurations);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void dismissSplitScreenMode(boolean toTop) {
        ActivityStack otherStack;
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "dismissSplitScreenMode()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = this.mRootActivityContainer.getDefaultDisplay().getSplitScreenPrimaryStack();
                if (stack == null) {
                    Slog.w("ActivityTaskManager", "dismissSplitScreenMode: primary split-screen stack not found.");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (toTop) {
                    stack.moveToFront("dismissSplitScreenMode");
                } else if (this.mRootActivityContainer.isTopDisplayFocusedStack(stack) && (otherStack = stack.getDisplay().getTopStackInWindowingMode(4)) != null) {
                    otherStack.moveToFront("dismissSplitScreenMode_other");
                }
                stack.setWindowingMode(0);
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void dismissPip(boolean animate, int animationDuration) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "dismissPip()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityStack stack = this.mRootActivityContainer.getDefaultDisplay().getPinnedStack();
                if (stack == null) {
                    Slog.w("ActivityTaskManager", "dismissPip: pinned stack not found.");
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (stack.getWindowingMode() != 2) {
                    throw new IllegalArgumentException("Stack: " + stack + " doesn't support animated resize.");
                } else {
                    if (animate) {
                        stack.animateResizePinnedStack(null, null, animationDuration, false);
                    } else {
                        this.mStackSupervisor.moveTasksToFullscreenStackLocked(stack, true);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void suppressResizeConfigChanges(boolean suppress) throws RemoteException {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "suppressResizeConfigChanges()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mSuppressResizeConfigChanges = suppress;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void moveTasksToFullscreenStack(int fromStackId, boolean onTop) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "moveTasksToFullscreenStack()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                ActivityStack stack = this.mRootActivityContainer.getStack(fromStackId);
                if (stack != null) {
                    if (!stack.isActivityTypeStandardOrUndefined()) {
                        throw new IllegalArgumentException("You can't move tasks from non-standard stacks.");
                    }
                    this.mStackSupervisor.moveTasksToFullscreenStackLocked(stack, onTop);
                }
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public boolean moveTopActivityToPinnedStack(int stackId, Rect bounds) {
        boolean moveTopStackActivityToPinnedStack;
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "moveTopActivityToPinnedStack()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (!this.mSupportsPictureInPicture) {
                    throw new IllegalStateException("moveTopActivityToPinnedStack:Device doesn't support picture-in-picture mode");
                }
                long ident = Binder.clearCallingIdentity();
                moveTopStackActivityToPinnedStack = this.mRootActivityContainer.moveTopStackActivityToPinnedStack(stackId);
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return moveTopStackActivityToPinnedStack;
    }

    public boolean isInMultiWindowMode(IBinder token) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    boolean inMultiWindowMode = r.inMultiWindowMode();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return inMultiWindowMode;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean isInPictureInPictureMode(IBinder token) {
        boolean isInPictureInPictureMode;
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                isInPictureInPictureMode = isInPictureInPictureMode(ActivityRecord.forTokenLocked(token));
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return isInPictureInPictureMode;
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private boolean isInPictureInPictureMode(ActivityRecord r) {
        if (r == null || r.getActivityStack() == null || !r.inPinnedWindowingMode() || r.getActivityStack().isInStackLocked(r) == null) {
            return false;
        }
        TaskStack taskStack = r.getActivityStack().getTaskStack();
        return !taskStack.isAnimatingBoundsToFullscreen();
    }

    public boolean enterPictureInPictureMode(IBinder token, final PictureInPictureParams params) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                final ActivityRecord r = ensureValidPictureInPictureActivityParamsLocked("enterPictureInPictureMode", token, params);
                if (isInPictureInPictureMode(r)) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return true;
                } else if (!r.checkEnterPictureInPictureState("enterPictureInPictureMode", false)) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } else {
                    final Runnable enterPipRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$js0zprxhKzo_Mx9ozR8logP_1-c
                        @Override // java.lang.Runnable
                        public final void run() {
                            ActivityTaskManagerService.this.lambda$enterPictureInPictureMode$4$ActivityTaskManagerService(r, params);
                        }
                    };
                    if (isKeyguardLocked()) {
                        dismissKeyguard(token, new KeyguardDismissCallback() { // from class: com.android.server.wm.ActivityTaskManagerService.1
                            public void onDismissSucceeded() {
                                ActivityTaskManagerService.this.mH.post(enterPipRunnable);
                            }
                        }, null);
                    } else {
                        enterPipRunnable.run();
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return true;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public /* synthetic */ void lambda$enterPictureInPictureMode$4$ActivityTaskManagerService(ActivityRecord r, PictureInPictureParams params) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                r.pictureInPictureArgs.copyOnlySet(params);
                float aspectRatio = r.pictureInPictureArgs.getAspectRatio();
                List<RemoteAction> actions = r.pictureInPictureArgs.getActions();
                Rect sourceBounds = new Rect(r.pictureInPictureArgs.getSourceRectHint());
                this.mRootActivityContainer.moveActivityToPinnedStack(r, sourceBounds, aspectRatio, "enterPictureInPictureMode");
                ActivityStack stack = r.getActivityStack();
                stack.setPictureInPictureAspectRatio(aspectRatio);
                stack.setPictureInPictureActions(actions);
                MetricsLoggerWrapper.logPictureInPictureEnter(this.mContext, r.appInfo.uid, r.shortComponentName, r.supportsEnterPipOnTaskSwitch);
                logPictureInPictureArgs(params);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void setPictureInPictureParams(IBinder token, PictureInPictureParams params) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ensureValidPictureInPictureActivityParamsLocked("setPictureInPictureParams", token, params);
                r.pictureInPictureArgs.copyOnlySet(params);
                if (r.inPinnedWindowingMode()) {
                    ActivityStack stack = r.getActivityStack();
                    if (!stack.isAnimatingBoundsToFullscreen()) {
                        stack.setPictureInPictureAspectRatio(r.pictureInPictureArgs.getAspectRatio());
                        stack.setPictureInPictureActions(r.pictureInPictureArgs.getActions());
                    }
                }
                logPictureInPictureArgs(params);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public int getMaxNumPictureInPictureActions(IBinder token) {
        return 3;
    }

    private void logPictureInPictureArgs(PictureInPictureParams params) {
        if (params.hasSetActions()) {
            MetricsLogger.histogram(this.mContext, "tron_varz_picture_in_picture_actions_count", params.getActions().size());
        }
        if (params.hasSetAspectRatio()) {
            LogMaker lm = new LogMaker(824);
            lm.addTaggedData(825, Float.valueOf(params.getAspectRatio()));
            MetricsLogger.action(lm);
        }
    }

    private ActivityRecord ensureValidPictureInPictureActivityParamsLocked(String caller, IBinder token, PictureInPictureParams params) {
        if (!this.mSupportsPictureInPicture) {
            throw new IllegalStateException(caller + ": Device doesn't support picture-in-picture mode.");
        }
        ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            throw new IllegalStateException(caller + ": Can't find activity for token=" + token);
        } else if (!r.supportsPictureInPicture()) {
            throw new IllegalStateException(caller + ": Current activity does not support picture-in-picture.");
        } else if (params.hasSetAspectRatio() && !this.mWindowManager.isValidPictureInPictureAspectRatio(r.getActivityStack().mDisplayId, params.getAspectRatio())) {
            float minAspectRatio = this.mContext.getResources().getFloat(17105071);
            float maxAspectRatio = this.mContext.getResources().getFloat(17105070);
            throw new IllegalArgumentException(String.format(caller + ": Aspect ratio is too extreme (must be between %f and %f).", Float.valueOf(minAspectRatio), Float.valueOf(maxAspectRatio)));
        } else {
            params.truncateActions(getMaxNumPictureInPictureActions(token));
            return r;
        }
    }

    public IBinder getUriPermissionOwnerForActivity(IBinder activityToken) {
        Binder externalToken;
        enforceNotIsolatedCaller("getUriPermissionOwnerForActivity");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                if (r == null) {
                    throw new IllegalArgumentException("Activity does not exist; token=" + activityToken);
                }
                externalToken = r.getUriPermissionsLocked().getExternalToken();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return externalToken;
    }

    public void resizeDockedStack(Rect dockedBounds, Rect tempDockedTaskBounds, Rect tempDockedTaskInsetBounds, Rect tempOtherTaskBounds, Rect tempOtherTaskInsetBounds) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "resizeDockedStack()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.resizeDockedStackLocked(dockedBounds, tempDockedTaskBounds, tempDockedTaskInsetBounds, tempOtherTaskBounds, tempOtherTaskInsetBounds, true);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void setSplitScreenResizing(boolean resizing) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "setSplitScreenResizing()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.setSplitScreenResizing(resizing);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void enforceSystemHasVrFeature() {
        if (!this.mContext.getPackageManager().hasSystemFeature("android.hardware.vr.high_performance")) {
            throw new UnsupportedOperationException("VR mode not supported on this device!");
        }
    }

    public int setVrMode(IBinder token, boolean enabled, ComponentName packageName) {
        ActivityRecord r;
        enforceSystemHasVrFeature();
        VrManagerInternal vrService = (VrManagerInternal) LocalServices.getService(VrManagerInternal.class);
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                r = ActivityRecord.isInStackLocked(token);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (r == null) {
            throw new IllegalArgumentException();
        }
        int err = vrService.hasVrPackage(packageName, r.mUserId);
        if (err != 0) {
            return err;
        }
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                r.requestedVrComponent = enabled ? packageName : null;
                if (r.isResumedActivityOnDisplay()) {
                    applyUpdateVrModeLocked(r);
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return 0;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void startLocalVoiceInteraction(IBinder callingActivity, Bundle options) {
        Slog.i("ActivityTaskManager", "Activity tried to startLocalVoiceInteraction");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord activity = getTopDisplayFocusedStack().getTopActivity();
                if (ActivityRecord.forTokenLocked(callingActivity) != activity) {
                    throw new SecurityException("Only focused activity can call startVoiceInteraction");
                }
                if (this.mRunningVoice == null && activity.getTaskRecord().voiceSession == null && activity.voiceSession == null) {
                    if (activity.pendingVoiceInteractionStart) {
                        Slog.w("ActivityTaskManager", "Pending start of voice interaction already.");
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    activity.pendingVoiceInteractionStart = true;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    ((VoiceInteractionManagerInternal) LocalServices.getService(VoiceInteractionManagerInternal.class)).startLocalVoiceInteraction(callingActivity, options);
                    return;
                }
                Slog.w("ActivityTaskManager", "Already in a voice interaction, cannot start new voice interaction");
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void stopLocalVoiceInteraction(IBinder callingActivity) {
        ((VoiceInteractionManagerInternal) LocalServices.getService(VoiceInteractionManagerInternal.class)).stopLocalVoiceInteraction(callingActivity);
    }

    public boolean supportsLocalVoiceInteraction() {
        return ((VoiceInteractionManagerInternal) LocalServices.getService(VoiceInteractionManagerInternal.class)).supportsLocalVoiceInteraction();
    }

    public void notifyPinnedStackAnimationStarted() {
        this.mTaskChangeNotificationController.notifyPinnedStackAnimationStarted();
    }

    public void notifyPinnedStackAnimationEnded() {
        this.mTaskChangeNotificationController.notifyPinnedStackAnimationEnded();
    }

    public void resizePinnedStack(Rect pinnedBounds, Rect tempPinnedTaskBounds) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "resizePinnedStack()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                this.mStackSupervisor.resizePinnedStackLocked(pinnedBounds, tempPinnedTaskBounds);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean updateDisplayOverrideConfiguration(Configuration values, int displayId) {
        this.mAmInternal.enforceCallingPermission("android.permission.CHANGE_CONFIGURATION", "updateDisplayOverrideConfiguration()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (!this.mRootActivityContainer.isDisplayAdded(displayId)) {
                    if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                        Slog.w("ActivityTaskManager", "Trying to update display configuration for non-existing displayId=" + displayId);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                if (values == null && this.mWindowManager != null) {
                    values = this.mWindowManager.computeNewConfiguration(displayId);
                }
                if (this.mWindowManager != null) {
                    Message msg = PooledLambda.obtainMessage($$Lambda$ADNhW0r9Skcs9ezrOGURijIlyQ.INSTANCE, this.mAmInternal, Integer.valueOf(displayId));
                    this.mH.sendMessage(msg);
                }
                long origId = Binder.clearCallingIdentity();
                if (values != null) {
                    Settings.System.clearConfiguration(values);
                }
                updateDisplayOverrideConfigurationLocked(values, null, false, displayId, this.mTmpUpdateConfigurationResult);
                boolean z = this.mTmpUpdateConfigurationResult.changes != 0;
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
                return z;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public boolean updateConfiguration(Configuration values) {
        boolean z;
        this.mAmInternal.enforceCallingPermission("android.permission.CHANGE_CONFIGURATION", "updateConfiguration()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (values == null && this.mWindowManager != null) {
                    values = this.mWindowManager.computeNewConfiguration(0);
                }
                if (this.mWindowManager != null) {
                    Message msg = PooledLambda.obtainMessage($$Lambda$ADNhW0r9Skcs9ezrOGURijIlyQ.INSTANCE, this.mAmInternal, 0);
                    this.mH.sendMessage(msg);
                }
                long origId = Binder.clearCallingIdentity();
                if (values != null) {
                    Settings.System.clearConfiguration(values);
                }
                updateConfigurationLocked(values, null, false, false, -10000, false, this.mTmpUpdateConfigurationResult);
                z = this.mTmpUpdateConfigurationResult.changes != 0;
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return z;
    }

    public void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback, CharSequence message) {
        if (message != null) {
            this.mAmInternal.enforceCallingPermission("android.permission.SHOW_KEYGUARD_MESSAGE", "dismissKeyguard()");
        }
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                this.mKeyguardController.dismissKeyguard(token, callback, message);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void cancelTaskWindowTransition(int taskId) {
        enforceCallerIsRecentsOrHasPermission("android.permission.MANAGE_ACTIVITY_STACKS", "cancelTaskWindowTransition()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
                if (task == null) {
                    Slog.w("ActivityTaskManager", "cancelTaskWindowTransition: taskId=" + taskId + " not found");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                task.cancelWindowTransition();
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution) {
        enforceCallerIsRecentsOrHasPermission("android.permission.READ_FRAME_BUFFER", "getTaskSnapshot()");
        long ident = Binder.clearCallingIdentity();
        try {
            return getTaskSnapshot(taskId, reducedResolution, true);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution, boolean restoreFromDisk) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 1);
                if (task == null) {
                    Slog.w("ActivityTaskManager", "getTaskSnapshot: taskId=" + taskId + " not found");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return null;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return task.getSnapshot(reducedResolution, restoreFromDisk);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setDisablePreviewScreenshots(IBinder token, boolean disable) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    Slog.w("ActivityTaskManager", "setDisablePreviewScreenshots: Unable to find activity for token=" + token);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                r.setDisablePreviewScreenshots(disable);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getLastResumedActivityUserId() {
        this.mAmInternal.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "getLastResumedActivityUserId()");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mLastResumedActivity == null) {
                    int currentUserId = getCurrentUserId();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return currentUserId;
                }
                int i = this.mLastResumedActivity.mUserId;
                WindowManagerService.resetPriorityAfterLockedSection();
                return i;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void updateLockTaskFeatures(int userId, int flags) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != 1000) {
            this.mAmInternal.enforceCallingPermission("android.permission.UPDATE_LOCK_TASK_PACKAGES", "updateLockTaskFeatures()");
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (ActivityTaskManagerDebugConfig.DEBUG_LOCKTASK) {
                    Slog.w("ActivityTaskManager", "Allowing features " + userId + ":0x" + Integer.toHexString(flags));
                }
                getLockTaskController().updateLockTaskFeatures(userId, flags);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void setShowWhenLocked(IBinder token, boolean showWhenLocked) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                r.setShowWhenLocked(showWhenLocked);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setInheritShowWhenLocked(IBinder token, boolean inheritShowWhenLocked) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                r.setInheritShowWhenLocked(inheritShowWhenLocked);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setTurnScreenOn(IBinder token, boolean turnScreenOn) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                r.setTurnScreenOn(turnScreenOn);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void registerRemoteAnimations(IBinder token, RemoteAnimationDefinition definition) {
        this.mAmInternal.enforceCallingPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS", "registerRemoteAnimations");
        definition.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                r.registerRemoteAnimations(definition);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void registerRemoteAnimationForNextActivityStart(String packageName, RemoteAnimationAdapter adapter) {
        this.mAmInternal.enforceCallingPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS", "registerRemoteAnimationForNextActivityStart");
        adapter.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                getActivityStartController().registerRemoteAnimationForNextActivityStart(packageName, adapter);
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void registerRemoteAnimationsForDisplay(int displayId, RemoteAnimationDefinition definition) {
        this.mAmInternal.enforceCallingPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS", "registerRemoteAnimations");
        definition.setCallingPidUid(Binder.getCallingPid(), Binder.getCallingUid());
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ActivityDisplay display = this.mRootActivityContainer.getActivityDisplay(displayId);
                if (display == null) {
                    Slog.e("ActivityTaskManager", "Couldn't find display with id: " + displayId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                long origId = Binder.clearCallingIdentity();
                display.mDisplayContent.registerRemoteAnimations(definition);
                Binder.restoreCallingIdentity(origId);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void alwaysShowUnsupportedCompileSdkWarning(ComponentName activity) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                this.mAppWarnings.alwaysShowUnsupportedCompileSdkWarning(activity);
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void setVrThread(int tid) {
        enforceSystemHasVrFeature();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                int pid = Binder.getCallingPid();
                WindowProcessController wpc = this.mProcessMap.getProcess(pid);
                this.mVrController.setVrThreadLocked(tid, pid, wpc);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void setPersistentVrThread(int tid) {
        if (checkCallingPermission("android.permission.RESTRICTED_VR_ACCESS") != 0) {
            String msg = "Permission Denial: setPersistentVrThread() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires android.permission.RESTRICTED_VR_ACCESS";
            Slog.w("ActivityTaskManager", msg);
            throw new SecurityException(msg);
        }
        enforceSystemHasVrFeature();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                int pid = Binder.getCallingPid();
                WindowProcessController proc = this.mProcessMap.getProcess(pid);
                this.mVrController.setPersistentVrThreadLocked(tid, pid, proc);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void stopAppSwitches() {
        enforceCallerIsRecentsOrHasPermission("android.permission.STOP_APP_SWITCHES", "stopAppSwitches");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mAppSwitchesAllowedTime = SystemClock.uptimeMillis() + APP_SWITCH_DELAY_TIME;
                this.mLastStopAppSwitchesTime = SystemClock.uptimeMillis();
                this.mDidAppSwitch = false;
                getActivityStartController().schedulePendingActivityLaunches(APP_SWITCH_DELAY_TIME);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void resumeAppSwitches() {
        enforceCallerIsRecentsOrHasPermission("android.permission.STOP_APP_SWITCHES", "resumeAppSwitches");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mAppSwitchesAllowedTime = 0L;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getLastStopAppSwitchesTime() {
        return this.mLastStopAppSwitchesTime;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onStartActivitySetDidAppSwitch() {
        if (this.mDidAppSwitch) {
            this.mAppSwitchesAllowedTime = 0L;
        } else {
            this.mDidAppSwitch = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldDisableNonVrUiLocked() {
        return this.mVrController.shouldDisableNonVrUiLocked();
    }

    private void applyUpdateVrModeLocked(final ActivityRecord r) {
        if (r.requestedVrComponent != null && r.getDisplayId() != 0) {
            Slog.i("ActivityTaskManager", "Moving " + r.shortComponentName + " from display " + r.getDisplayId() + " to main display for VR");
            this.mRootActivityContainer.moveStackToDisplay(r.getStackId(), 0, true);
        }
        this.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$7Ia1bmRpPHHSNlbH8cuLw8dKG04
            @Override // java.lang.Runnable
            public final void run() {
                ActivityTaskManagerService.this.lambda$applyUpdateVrModeLocked$5$ActivityTaskManagerService(r);
            }
        });
    }

    public /* synthetic */ void lambda$applyUpdateVrModeLocked$5$ActivityTaskManagerService(ActivityRecord r) {
        if (!this.mVrController.onVrModeChanged(r)) {
            return;
        }
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                boolean disableNonVrUi = this.mVrController.shouldDisableNonVrUiLocked();
                this.mWindowManager.disableNonVrUi(disableNonVrUi);
                if (disableNonVrUi) {
                    this.mRootActivityContainer.removeStacksInWindowingModes(2);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public int getPackageScreenCompatMode(String packageName) {
        int packageScreenCompatModeLocked;
        enforceNotIsolatedCaller("getPackageScreenCompatMode");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                packageScreenCompatModeLocked = this.mCompatModePackages.getPackageScreenCompatModeLocked(packageName);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return packageScreenCompatModeLocked;
    }

    public void setPackageScreenCompatMode(String packageName, int mode) {
        this.mAmInternal.enforceCallingPermission("android.permission.SET_SCREEN_COMPATIBILITY", "setPackageScreenCompatMode");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mCompatModePackages.setPackageScreenCompatModeLocked(packageName, mode);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public boolean getPackageAskScreenCompat(String packageName) {
        boolean packageAskCompatModeLocked;
        enforceNotIsolatedCaller("getPackageAskScreenCompat");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                packageAskCompatModeLocked = this.mCompatModePackages.getPackageAskCompatModeLocked(packageName);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return packageAskCompatModeLocked;
    }

    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        this.mAmInternal.enforceCallingPermission("android.permission.SET_SCREEN_COMPATIBILITY", "setPackageAskScreenCompat");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mCompatModePackages.setPackageAskCompatModeLocked(packageName, ask);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public static String relaunchReasonToString(int relaunchReason) {
        if (relaunchReason != 1) {
            if (relaunchReason == 2) {
                return "free_resize";
            }
            return null;
        }
        return "window_resize";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityStack getTopDisplayFocusedStack() {
        return this.mRootActivityContainer.getTopDisplayFocusedStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskPersisterLocked(TaskRecord task, boolean flush) {
        this.mRecentTasks.notifyTaskPersisterLocked(task, flush);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isKeyguardLocked() {
        return this.mKeyguardController.isKeyguardLocked();
    }

    public void clearLaunchParamsForPackages(List<String> packageNames) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "clearLaunchParamsForPackages");
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                for (int i = 0; i < packageNames.size(); i++) {
                    this.mStackSupervisor.mLaunchParamsPersister.removeRecordForPackage(packageNames.get(i));
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void setDisplayToSingleTaskInstance(int displayId) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "setDisplayToSingleTaskInstance");
        long origId = Binder.clearCallingIdentity();
        try {
            ActivityDisplay display = this.mRootActivityContainer.getActivityDisplayOrCreate(displayId);
            if (display != null) {
                display.setDisplayToSingleTaskInstance();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void dumpLastANRLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)");
        String str = this.mLastANRState;
        if (str == null) {
            pw.println("  <no ANR has occurred since boot>");
        } else {
            pw.println(str);
        }
    }

    void dumpLastANRTracesLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER LAST ANR TRACES (dumpsys activity lastanr-traces)");
        File[] files = new File(ActivityManagerService.ANR_TRACE_DIR).listFiles();
        if (ArrayUtils.isEmpty(files)) {
            pw.println("  <no ANR has occurred since boot>");
            return;
        }
        File latest = null;
        for (File f : files) {
            if (latest == null || latest.lastModified() < f.lastModified()) {
                latest = f;
            }
        }
        pw.print("File: ");
        pw.print(latest.getName());
        pw.println();
        try {
            BufferedReader in = new BufferedReader(new FileReader(latest));
            while (true) {
                String line = in.readLine();
                if (line != null) {
                    pw.println(line);
                } else {
                    $closeResource(null, in);
                    return;
                }
            }
        } catch (IOException e) {
            pw.print("Unable to read: ");
            pw.print(e);
            pw.println();
        }
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

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage, "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)");
    }

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, boolean dumpClient, String dumpPackage, String header) {
        pw.println(header);
        boolean printedAnything = this.mRootActivityContainer.dumpActivities(fd, pw, dumpAll, dumpClient, dumpPackage);
        boolean needSep = printedAnything;
        boolean printed = ActivityStackSupervisor.printThisActivity(pw, this.mRootActivityContainer.getTopResumedActivity(), dumpPackage, needSep, "  ResumedActivity: ");
        if (printed) {
            printedAnything = true;
            needSep = false;
        }
        if (dumpPackage == null) {
            if (needSep) {
                pw.println();
            }
            printedAnything = true;
            this.mStackSupervisor.dump(pw, "  ");
        }
        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    void dumpActivityContainersLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER STARTER (dumpsys activity containers)");
        this.mRootActivityContainer.dumpChildrenNames(pw, " ");
        pw.println(" ");
    }

    void dumpActivityStarterLocked(PrintWriter pw, String dumpPackage) {
        pw.println("ACTIVITY MANAGER STARTER (dumpsys activity starter)");
        getActivityStartController().dump(pw, "", dumpPackage);
    }

    protected boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name, String[] args, int opti, boolean dumpAll, boolean dumpVisibleStacksOnly, boolean dumpFocusedStackOnly) {
        TaskRecord lastTask;
        synchronized (this.mGlobalLock) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ArrayList<ActivityRecord> activities = this.mRootActivityContainer.getDumpActivities(name, dumpVisibleStacksOnly, dumpFocusedStackOnly);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (activities.size() <= 0) {
                        return false;
                    }
                    String[] newArgs = new String[args.length - opti];
                    System.arraycopy(args, opti, newArgs, 0, args.length - opti);
                    int i = activities.size() - 1;
                    TaskRecord lastTask2 = null;
                    TaskRecord lastTask3 = null;
                    while (i >= 0) {
                        ActivityRecord r = activities.get(i);
                        if (lastTask3 != null) {
                            pw.println();
                        }
                        synchronized (this.mGlobalLock) {
                            try {
                                WindowManagerService.boostPriorityForLockedSection();
                                TaskRecord task = r.getTaskRecord();
                                if (lastTask2 == task) {
                                    lastTask = lastTask2;
                                } else {
                                    try {
                                        pw.print("TASK ");
                                        pw.print(task.affinity);
                                        pw.print(" id=");
                                        pw.print(task.taskId);
                                        pw.print(" userId=");
                                        pw.println(task.userId);
                                        if (dumpAll) {
                                            task.dump(pw, "  ");
                                        }
                                        lastTask = task;
                                    } catch (Throwable th) {
                                        th = th;
                                        while (true) {
                                            try {
                                                break;
                                            } catch (Throwable th2) {
                                                th = th2;
                                            }
                                        }
                                        WindowManagerService.resetPriorityAfterLockedSection();
                                        throw th;
                                    }
                                }
                            } catch (Throwable th3) {
                                th = th3;
                            }
                            try {
                            } catch (Throwable th4) {
                                th = th4;
                                while (true) {
                                    break;
                                    break;
                                }
                                WindowManagerService.resetPriorityAfterLockedSection();
                                throw th;
                            }
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        dumpActivity("  ", fd, pw, activities.get(i), newArgs, dumpAll);
                        i--;
                        lastTask3 = 1;
                        lastTask2 = lastTask;
                        newArgs = newArgs;
                    }
                    return true;
                } catch (Throwable th5) {
                    th = th5;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            } catch (Throwable th6) {
                th = th6;
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private void dumpActivity(String prefix, FileDescriptor fd, PrintWriter pw, ActivityRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        IApplicationThread appThread = null;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                pw.print(prefix);
                pw.print("ACTIVITY ");
                pw.print(r.shortComponentName);
                pw.print(" ");
                pw.print(Integer.toHexString(System.identityHashCode(r)));
                pw.print(" pid=");
                if (r.hasProcess()) {
                    pw.println(r.app.getPid());
                    appThread = r.app.getThread();
                } else {
                    pw.println("(not running)");
                }
                if (dumpAll) {
                    r.dump(pw, innerPrefix);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        if (appThread != null) {
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                appThread.dumpActivity(tp.getWriteFd(), r.appToken, innerPrefix, args);
                tp.go(fd);
                $closeResource(null, tp);
            } catch (RemoteException e) {
                pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
            } catch (IOException e2) {
                pw.println(innerPrefix + "Failure while dumping the activity: " + e2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeSleepStateToProto(ProtoOutputStream proto, int wakeFullness, boolean testPssMode) {
        long sleepToken = proto.start(1146756268059L);
        proto.write(1159641169921L, PowerManagerInternal.wakefulnessToProtoEnum(wakeFullness));
        Iterator<ActivityTaskManagerInternal.SleepToken> it = this.mRootActivityContainer.mSleepTokens.iterator();
        while (it.hasNext()) {
            ActivityTaskManagerInternal.SleepToken st = it.next();
            proto.write(2237677961218L, st.toString());
        }
        proto.write(1133871366147L, this.mSleeping);
        proto.write(1133871366148L, this.mShuttingDown);
        proto.write(1133871366149L, testPssMode);
        proto.end(sleepToken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getCurrentUserId() {
        return this.mAmInternal.getCurrentUserId();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    public Configuration getConfiguration() {
        Configuration ci;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ci = new Configuration(getGlobalConfigurationForCallingPid());
                ci.userSetLocale = false;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return ci;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Configuration getGlobalConfiguration() {
        return this.mRootActivityContainer.getConfiguration();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateConfigurationLocked(Configuration values, ActivityRecord starting, boolean initLocale) {
        return updateConfigurationLocked(values, starting, initLocale, false);
    }

    boolean updateConfigurationLocked(Configuration values, ActivityRecord starting, boolean initLocale, boolean deferResume) {
        return updateConfigurationLocked(values, starting, initLocale, false, -10000, deferResume);
    }

    public void updatePersistentConfiguration(Configuration values, int userId) {
        long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                updateConfigurationLocked(values, null, false, true, userId, false);
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updateConfigurationLocked(Configuration values, ActivityRecord starting, boolean initLocale, boolean persistent, int userId, boolean deferResume) {
        return updateConfigurationLocked(values, starting, initLocale, persistent, userId, deferResume, null);
    }

    boolean updateConfigurationLocked(Configuration values, ActivityRecord starting, boolean initLocale, boolean persistent, int userId, boolean deferResume, UpdateConfigurationResult result) {
        int changes = 0;
        WindowManagerService windowManagerService = this.mWindowManager;
        if (windowManagerService != null) {
            windowManagerService.deferSurfaceLayout();
        }
        if (values != null) {
            try {
                changes = updateGlobalConfigurationLocked(values, initLocale, persistent, userId, deferResume);
            } finally {
                WindowManagerService windowManagerService2 = this.mWindowManager;
                if (windowManagerService2 != null) {
                    windowManagerService2.continueSurfaceLayout();
                }
            }
        }
        boolean kept = ensureConfigAndVisibilityAfterUpdate(starting, changes);
        if (result != null) {
            result.changes = changes;
            result.activityRelaunched = !kept;
        }
        return kept;
    }

    private int updateGlobalConfigurationLocked(Configuration values, boolean initLocale, boolean persistent, int userId, boolean deferResume) {
        this.mTempConfig.setTo(getGlobalConfiguration());
        int changes = this.mTempConfig.updateFrom(values);
        if (changes == 0) {
            performDisplayOverrideConfigUpdate(values, deferResume, 0);
            return 0;
        }
        if (ActivityTaskManagerDebugConfig.DEBUG_SWITCH || ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
            Slog.i("ActivityTaskManager", "Updating global configuration to: " + values);
        }
        EventLog.writeEvent((int) EventLogTags.CONFIGURATION_CHANGED, changes);
        StatsLog.write(66, values.colorMode, values.densityDpi, values.fontScale, values.hardKeyboardHidden, values.keyboard, values.keyboardHidden, values.mcc, values.mnc, values.navigation, values.navigationHidden, values.orientation, values.screenHeightDp, values.screenLayout, values.screenWidthDp, values.smallestScreenWidthDp, values.touchscreen, values.uiMode);
        if (!initLocale && !values.getLocales().isEmpty() && values.userSetLocale) {
            LocaleList locales = values.getLocales();
            int bestLocaleIndex = 0;
            if (locales.size() > 1) {
                if (this.mSupportedSystemLocales == null) {
                    this.mSupportedSystemLocales = Resources.getSystem().getAssets().getLocales();
                }
                bestLocaleIndex = Math.max(0, locales.getFirstMatchIndex(this.mSupportedSystemLocales));
            }
            SystemProperties.set("persist.sys.locale", locales.get(bestLocaleIndex).toLanguageTag());
            LocaleList.setDefault(locales, bestLocaleIndex);
            Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$U6g1UdnOPnEF9wX1OTm9nKVXY5k
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((ActivityTaskManagerService) obj).sendLocaleToMountDaemonMsg((Locale) obj2);
                }
            }, this, locales.get(bestLocaleIndex));
            this.mH.sendMessage(m);
        }
        this.mTempConfig.seq = increaseConfigurationSeqLocked();
        this.mRootActivityContainer.onConfigurationChanged(this.mTempConfig);
        Slog.i("ActivityTaskManager", "Config changes=" + Integer.toHexString(changes) + " " + this.mTempConfig);
        this.mUsageStatsInternal.reportConfigurationChange(this.mTempConfig, this.mAmInternal.getCurrentUserId());
        updateShouldShowDialogsLocked(this.mTempConfig);
        AttributeCache ac = AttributeCache.instance();
        if (ac != null) {
            ac.updateConfiguration(this.mTempConfig);
        }
        this.mSystemThread.applyConfigurationToResources(this.mTempConfig);
        Configuration configCopy = new Configuration(this.mTempConfig);
        if (persistent && Settings.System.hasInterestingConfigurationChanges(changes)) {
            Message msg = PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$yP9TbBmrgQ4lrgcxb-8oL1pBAs4
                public final void accept(Object obj, Object obj2, Object obj3) {
                    ((ActivityTaskManagerService) obj).sendPutConfigurationForUserMsg(((Integer) obj2).intValue(), (Configuration) obj3);
                }
            }, this, Integer.valueOf(userId), configCopy);
            this.mH.sendMessage(msg);
        }
        SparseArray<WindowProcessController> pidMap = this.mProcessMap.getPidMap();
        for (int i = pidMap.size() - 1; i >= 0; i--) {
            int pid = pidMap.keyAt(i);
            WindowProcessController app = pidMap.get(pid);
            if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                Slog.v("ActivityTaskManager", "Update process config of " + app.mName + " to new config " + configCopy);
            }
            app.onConfigurationChanged(configCopy);
        }
        Message msg2 = PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.wm.-$$Lambda$swA_sUfSJdP8eC8AA9Iby3-SuOY
            public final void accept(Object obj, Object obj2, Object obj3) {
                ((ActivityManagerInternal) obj).broadcastGlobalConfigurationChanged(((Integer) obj2).intValue(), ((Boolean) obj3).booleanValue());
            }
        }, this.mAmInternal, Integer.valueOf(changes), Boolean.valueOf(initLocale));
        this.mH.sendMessage(msg2);
        performDisplayOverrideConfigUpdate(this.mRootActivityContainer.getConfiguration(), deferResume, 0);
        return changes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateDisplayOverrideConfigurationLocked(Configuration values, ActivityRecord starting, boolean deferResume, int displayId) {
        return updateDisplayOverrideConfigurationLocked(values, starting, deferResume, displayId, null);
    }

    boolean updateDisplayOverrideConfigurationLocked(Configuration values, ActivityRecord starting, boolean deferResume, int displayId, UpdateConfigurationResult result) {
        int changes = 0;
        WindowManagerService windowManagerService = this.mWindowManager;
        if (windowManagerService != null) {
            windowManagerService.deferSurfaceLayout();
        }
        try {
            if (values != null) {
                if (displayId == 0) {
                    changes = updateGlobalConfigurationLocked(values, false, false, -10000, deferResume);
                } else {
                    changes = performDisplayOverrideConfigUpdate(values, deferResume, displayId);
                }
            }
            boolean kept = ensureConfigAndVisibilityAfterUpdate(starting, changes);
            if (result != null) {
                result.changes = changes;
                result.activityRelaunched = !kept;
            }
            return kept;
        } finally {
            WindowManagerService windowManagerService2 = this.mWindowManager;
            if (windowManagerService2 != null) {
                windowManagerService2.continueSurfaceLayout();
            }
        }
    }

    private int performDisplayOverrideConfigUpdate(Configuration values, boolean deferResume, int displayId) {
        this.mTempConfig.setTo(this.mRootActivityContainer.getDisplayOverrideConfiguration(displayId));
        int changes = this.mTempConfig.updateFrom(values);
        if (changes != 0) {
            Slog.i("ActivityTaskManager", "Override config changes=" + Integer.toHexString(changes) + " " + this.mTempConfig + " for displayId=" + displayId);
            this.mRootActivityContainer.setDisplayOverrideConfiguration(this.mTempConfig, displayId);
            boolean isDensityChange = (changes & 4096) != 0;
            if (isDensityChange && displayId == 0) {
                this.mAppWarnings.onDensityChanged();
                Message msg = PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.wm.-$$Lambda$ibmQVLjaQW2x74Wk8TcE0Og2MJM
                    public final void accept(Object obj, Object obj2, Object obj3) {
                        ((ActivityManagerInternal) obj).killAllBackgroundProcessesExcept(((Integer) obj2).intValue(), ((Integer) obj3).intValue());
                    }
                }, this.mAmInternal, 24, 7);
                this.mH.sendMessage(msg);
            }
        }
        return changes;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateEventDispatchingLocked(boolean booted) {
        this.mWindowManager.setEventDispatching(booted && !this.mShuttingDown);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendPutConfigurationForUserMsg(int userId, Configuration config) {
        ContentResolver resolver = this.mContext.getContentResolver();
        Settings.System.putConfigurationForUser(resolver, config, userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendLocaleToMountDaemonMsg(Locale l) {
        try {
            IBinder service = ServiceManager.getService("mount");
            IStorageManager storageManager = IStorageManager.Stub.asInterface(service);
            Log.d("ActivityTaskManager", "Storing locale " + l.toLanguageTag() + " for decryption UI");
            storageManager.setField("SystemLocale", l.toLanguageTag());
        } catch (RemoteException e) {
            Log.e("ActivityTaskManager", "Error storing locale for decryption UI", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void expireStartAsCallerTokenMsg(IBinder permissionToken) {
        this.mStartActivitySources.remove(permissionToken);
        this.mExpiredStartAsCallerTokens.add(permissionToken);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void forgetStartAsCallerTokenMsg(IBinder permissionToken) {
        this.mExpiredStartAsCallerTokens.remove(permissionToken);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isActivityStartsLoggingEnabled() {
        return this.mAmInternal.isActivityStartsLoggingEnabled();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBackgroundActivityStartsEnabled() {
        return this.mAmInternal.isBackgroundActivityStartsEnabled();
    }

    void enableScreenAfterBoot(boolean booted) {
        EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_ENABLE_SCREEN, SystemClock.uptimeMillis());
        this.mWindowManager.enableScreenAfterBoot();
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                updateEventDispatchingLocked(booted);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static long getInputDispatchingTimeoutLocked(ActivityRecord r) {
        if (r == null || !r.hasProcess()) {
            return APP_SWITCH_DELAY_TIME;
        }
        return getInputDispatchingTimeoutLocked(r.app);
    }

    private static long getInputDispatchingTimeoutLocked(WindowProcessController r) {
        return r != null ? r.getInputDispatchingTimeout() : APP_SWITCH_DELAY_TIME;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateShouldShowDialogsLocked(Configuration config) {
        boolean z = false;
        boolean inputMethodExists = (config.keyboard == 1 && config.touchscreen == 1 && config.navigation == 1) ? false : true;
        int modeType = config.uiMode & 15;
        boolean uiModeSupportsDialogs = (modeType == 3 || (modeType == 6 && Build.IS_USER) || modeType == 4 || modeType == 7) ? false : true;
        boolean hideDialogsSet = Settings.Global.getInt(this.mContext.getContentResolver(), "hide_error_dialogs", 0) != 0;
        if (inputMethodExists && uiModeSupportsDialogs && !hideDialogsSet) {
            z = true;
        }
        this.mShowDialogs = z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateFontScaleIfNeeded(int userId) {
        float scaleFactor = Settings.System.getFloatForUser(this.mContext.getContentResolver(), "font_scale", 1.0f, userId);
        synchronized (this) {
            if (getGlobalConfiguration().fontScale == scaleFactor) {
                return;
            }
            Configuration configuration = this.mWindowManager.computeNewConfiguration(0);
            configuration.fontScale = scaleFactor;
            updatePersistentConfiguration(configuration, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSleepingOrShuttingDownLocked() {
        return isSleepingLocked() || this.mShuttingDown;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSleepingLocked() {
        return this.mSleeping;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setResumedActivityUncheckLocked(ActivityRecord r, String reason) {
        IVoiceInteractionSession session;
        TaskRecord task = r.getTaskRecord();
        if (task.isActivityTypeStandard()) {
            if (this.mCurAppTimeTracker != r.appTimeTracker) {
                AppTimeTracker appTimeTracker = this.mCurAppTimeTracker;
                if (appTimeTracker != null) {
                    appTimeTracker.stop();
                    this.mH.obtainMessage(1, this.mCurAppTimeTracker).sendToTarget();
                    this.mRootActivityContainer.clearOtherAppTimeTrackers(r.appTimeTracker);
                    this.mCurAppTimeTracker = null;
                }
                if (r.appTimeTracker != null) {
                    this.mCurAppTimeTracker = r.appTimeTracker;
                    startTimeTrackingFocusedActivityLocked();
                }
            } else {
                startTimeTrackingFocusedActivityLocked();
            }
        } else {
            r.appTimeTracker = null;
        }
        if (task.voiceInteractor != null) {
            startRunningVoiceLocked(task.voiceSession, r.info.applicationInfo.uid);
        } else {
            finishRunningVoiceLocked();
            ActivityRecord activityRecord = this.mLastResumedActivity;
            if (activityRecord != null) {
                TaskRecord lastResumedActivityTask = activityRecord.getTaskRecord();
                if (lastResumedActivityTask != null && lastResumedActivityTask.voiceSession != null) {
                    session = lastResumedActivityTask.voiceSession;
                } else {
                    session = this.mLastResumedActivity.voiceSession;
                }
                if (session != null) {
                    finishVoiceTask(session);
                }
            }
        }
        if (this.mLastResumedActivity != null && r.mUserId != this.mLastResumedActivity.mUserId) {
            this.mAmInternal.sendForegroundProfileChanged(r.mUserId);
        }
        updateResumedAppTrace(r);
        this.mLastResumedActivity = r;
        r.getDisplay().setFocusedApp(r, true);
        handleActivityResumedLocked(r);
        applyUpdateLockStateLocked(r);
        applyUpdateVrModeLocked(r);
        EventLogTags.writeAmSetResumedActivity(r.mUserId, r.shortComponentName, reason);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityTaskManagerInternal.SleepToken acquireSleepToken(String tag, int displayId) {
        ActivityTaskManagerInternal.SleepToken token;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                Slog.i("ActivityTaskManager", "acquireSleepToken, tag--->" + tag + ", displayId--->" + displayId);
                token = this.mRootActivityContainer.createSleepToken(tag, displayId);
                updateSleepIfNeededLocked();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return token;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateSleepIfNeededLocked() {
        boolean shouldSleep = !this.mRootActivityContainer.hasAwakeDisplay();
        boolean wasSleeping = this.mSleeping;
        boolean updateOomAdj = false;
        if (!shouldSleep) {
            if (wasSleeping) {
                this.mSleeping = false;
                StatsLog.write(14, 2);
                startTimeTrackingFocusedActivityLocked();
                this.mTopProcessState = 2;
                Slog.d("ActivityTaskManager", "Top Process State changed to PROCESS_STATE_TOP");
                this.mStackSupervisor.comeOutOfSleepIfNeededLocked();
            }
            this.mRootActivityContainer.applySleepTokens(true);
            if (wasSleeping) {
                updateOomAdj = true;
            }
        } else if (!this.mSleeping && shouldSleep) {
            this.mSleeping = true;
            StatsLog.write(14, 1);
            AppTimeTracker appTimeTracker = this.mCurAppTimeTracker;
            if (appTimeTracker != null) {
                appTimeTracker.stop();
            }
            this.mTopProcessState = 13;
            Slog.d("ActivityTaskManager", "Top Process State changed to PROCESS_STATE_TOP_SLEEPING");
            this.mStackSupervisor.goingToSleepLocked();
            updateResumedAppTrace(null);
            updateOomAdj = true;
        }
        ActivityTaskManagerInternal activityTaskManagerInternal = this.mInternal;
        if (activityTaskManagerInternal != null) {
            activityTaskManagerInternal.mSleeping = this.mSleeping;
        }
        if (updateOomAdj) {
            H h = this.mH;
            ActivityManagerInternal activityManagerInternal = this.mAmInternal;
            Objects.requireNonNull(activityManagerInternal);
            h.post(new $$Lambda$yIIsPVyXvnU3Rv8mcliitgIpSs(activityManagerInternal));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateOomAdj() {
        H h = this.mH;
        ActivityManagerInternal activityManagerInternal = this.mAmInternal;
        Objects.requireNonNull(activityManagerInternal);
        h.post(new $$Lambda$yIIsPVyXvnU3Rv8mcliitgIpSs(activityManagerInternal));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateCpuStats() {
        H h = this.mH;
        final ActivityManagerInternal activityManagerInternal = this.mAmInternal;
        Objects.requireNonNull(activityManagerInternal);
        h.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$LYW1ECaEajjYgarzgKdTZ4O1fi0
            @Override // java.lang.Runnable
            public final void run() {
                activityManagerInternal.updateCpuStats();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateBatteryStats(ActivityRecord component, boolean resumed) {
        Message m = PooledLambda.obtainMessage(new QuintConsumer() { // from class: com.android.server.wm.-$$Lambda$hT1kyMEAhvB1-Uxr0DFAlnuU3cQ
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5) {
                ((ActivityManagerInternal) obj).updateBatteryStats((ComponentName) obj2, ((Integer) obj3).intValue(), ((Integer) obj4).intValue(), ((Boolean) obj5).booleanValue());
            }
        }, this.mAmInternal, component.mActivityComponent, Integer.valueOf(component.app.mUid), Integer.valueOf(component.mUserId), Boolean.valueOf(resumed));
        this.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateActivityUsageStats(ActivityRecord activity, int event) {
        ActivityRecord rootActivity;
        ComponentName taskRoot = null;
        TaskRecord task = activity.getTaskRecord();
        if (task != null && (rootActivity = task.getRootActivity()) != null) {
            taskRoot = rootActivity.mActivityComponent;
        }
        Message m = PooledLambda.obtainMessage(new HexConsumer() { // from class: com.android.server.wm.-$$Lambda$UB90fpYUkajpKCLGR93ZDlgDhyw
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5, Object obj6) {
                ((ActivityManagerInternal) obj).updateActivityUsageStats((ComponentName) obj2, ((Integer) obj3).intValue(), ((Integer) obj4).intValue(), (IApplicationToken.Stub) obj5, (ComponentName) obj6);
            }
        }, this.mAmInternal, activity.mActivityComponent, Integer.valueOf(activity.mUserId), Integer.valueOf(event), activity.appToken, taskRoot);
        this.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBooting(boolean booting) {
        this.mAmInternal.setBooting(booting);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBooting() {
        return this.mAmInternal.isBooting();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBooted(boolean booted) {
        this.mAmInternal.setBooted(booted);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBooted() {
        return this.mAmInternal.isBooted();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postFinishBooting(final boolean finishBooting, final boolean enableScreen) {
        this.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$oP6xxIfnD4kb4JN7aSJU073ULR4
            @Override // java.lang.Runnable
            public final void run() {
                ActivityTaskManagerService.this.lambda$postFinishBooting$6$ActivityTaskManagerService(finishBooting, enableScreen);
            }
        });
    }

    public /* synthetic */ void lambda$postFinishBooting$6$ActivityTaskManagerService(boolean finishBooting, boolean enableScreen) {
        if (finishBooting) {
            this.mAmInternal.finishBooting();
        }
        if (enableScreen) {
            this.mInternal.enableScreenAfterBoot(isBooted());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHeavyWeightProcess(ActivityRecord root) {
        this.mHeavyWeightProcess = root.app;
        Message m = PooledLambda.obtainMessage(new QuadConsumer() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$x3j1aVkumtfulORwKd6dHysJyE0
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4) {
                ((ActivityTaskManagerService) obj).postHeavyWeightProcessNotification((WindowProcessController) obj2, (Intent) obj3, ((Integer) obj4).intValue());
            }
        }, this, root.app, root.intent, Integer.valueOf(root.mUserId));
        this.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearHeavyWeightProcessIfEquals(WindowProcessController proc) {
        WindowProcessController windowProcessController = this.mHeavyWeightProcess;
        if (windowProcessController == null || windowProcessController != proc) {
            return;
        }
        this.mHeavyWeightProcess = null;
        Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$w70cT1_hTWQQAYctmXaA0BeZuBc
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((ActivityTaskManagerService) obj).cancelHeavyWeightProcessNotification(((Integer) obj2).intValue());
            }
        }, this, Integer.valueOf(proc.mUserId));
        this.mH.sendMessage(m);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelHeavyWeightProcessNotification(int userId) {
        INotificationManager inm = NotificationManager.getService();
        if (inm == null) {
            return;
        }
        try {
            inm.cancelNotificationWithTag(PackageManagerService.PLATFORM_PACKAGE_NAME, (String) null, 11, userId);
        } catch (RemoteException e) {
        } catch (RuntimeException e2) {
            Slog.w("ActivityTaskManager", "Error canceling notification for service", e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v0, types: [java.lang.String] */
    /* JADX WARN: Type inference failed for: r0v2, types: [android.os.RemoteException] */
    /* JADX WARN: Type inference failed for: r0v3 */
    public void postHeavyWeightProcessNotification(WindowProcessController proc, Intent intent, int userId) {
        INotificationManager inm;
        String e = "ActivityTaskManager";
        if (proc == null || (inm = NotificationManager.getService()) == null) {
            return;
        }
        try {
            Context context = this.mContext.createPackageContext(proc.mInfo.packageName, 0);
            String text = this.mContext.getString(17040095, context.getApplicationInfo().loadLabel(context.getPackageManager()));
            Notification notification = new Notification.Builder(context, SystemNotificationChannels.HEAVY_WEIGHT_APP).setSmallIcon(17303526).setWhen(0L).setOngoing(true).setTicker(text).setColor(this.mContext.getColor(17170460)).setContentTitle(text).setContentText(this.mContext.getText(17040096)).setContentIntent(PendingIntent.getActivityAsUser(this.mContext, 0, intent, 268435456, null, new UserHandle(userId))).build();
            try {
                inm.enqueueNotificationWithTag(PackageManagerService.PLATFORM_PACKAGE_NAME, PackageManagerService.PLATFORM_PACKAGE_NAME, (String) null, 11, notification, userId);
            } catch (RemoteException e2) {
                e = e2;
            } catch (RuntimeException e3) {
                Slog.w("ActivityTaskManager", "Error showing notification for heavy-weight app", e3);
            }
        } catch (PackageManager.NameNotFoundException e4) {
            Slog.w(e, "Unable to create context for heavy notification", e4);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IIntentSender getIntentSenderLocked(int type, String packageName, int callingUid, int userId, IBinder token, String resultWho, int requestCode, Intent[] intents, String[] resolvedTypes, int flags, Bundle bOptions) {
        ActivityRecord activity;
        if (type == 3) {
            ActivityRecord activity2 = ActivityRecord.isInStackLocked(token);
            if (activity2 == null) {
                Slog.w("ActivityTaskManager", "Failed createPendingResult: activity " + token + " not in any stack");
                return null;
            } else if (!activity2.finishing) {
                activity = activity2;
            } else {
                Slog.w("ActivityTaskManager", "Failed createPendingResult: activity " + activity2 + " is finishing");
                return null;
            }
        } else {
            activity = null;
        }
        PendingIntentRecord rec = this.mPendingIntentController.getIntentSender(type, packageName, callingUid, userId, token, resultWho, requestCode, intents, resolvedTypes, flags, bOptions);
        boolean noCreate = (flags & 536870912) != 0;
        if (noCreate) {
            return rec;
        }
        if (type == 3) {
            if (activity.pendingResults == null) {
                activity.pendingResults = new HashSet<>();
            }
            activity.pendingResults.add(rec.ref);
        }
        return rec;
    }

    private void startTimeTrackingFocusedActivityLocked() {
        AppTimeTracker appTimeTracker;
        ActivityRecord resumedActivity = this.mRootActivityContainer.getTopResumedActivity();
        if (!this.mSleeping && (appTimeTracker = this.mCurAppTimeTracker) != null && resumedActivity != null) {
            appTimeTracker.start(resumedActivity.packageName);
        }
    }

    private void updateResumedAppTrace(ActivityRecord resumed) {
        ActivityRecord activityRecord = this.mTracedResumedActivity;
        if (activityRecord != null) {
            Trace.asyncTraceEnd(64L, constructResumedTraceName(activityRecord.packageName), 0);
        }
        if (resumed != null) {
            Trace.asyncTraceBegin(64L, constructResumedTraceName(resumed.packageName), 0);
        }
        this.mTracedResumedActivity = resumed;
    }

    private String constructResumedTraceName(String packageName) {
        return "focused app: " + packageName;
    }

    private boolean ensureConfigAndVisibilityAfterUpdate(ActivityRecord starting, int changes) {
        ActivityStack mainStack = this.mRootActivityContainer.getTopDisplayFocusedStack();
        if (mainStack == null) {
            return true;
        }
        if (changes != 0 && starting == null) {
            starting = mainStack.topRunningActivityLocked();
        }
        if (starting == null) {
            return true;
        }
        boolean kept = starting.ensureActivityConfiguration(changes, false);
        this.mRootActivityContainer.ensureActivitiesVisible(starting, changes, false);
        return kept;
    }

    public /* synthetic */ void lambda$scheduleAppGcsLocked$7$ActivityTaskManagerService() {
        this.mAmInternal.scheduleAppGcs();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleAppGcsLocked() {
        this.mH.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$-xFyZDUKMraVkermSJGXQdN3oJ4
            @Override // java.lang.Runnable
            public final void run() {
                ActivityTaskManagerService.this.lambda$scheduleAppGcsLocked$7$ActivityTaskManagerService();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        return this.mCompatModePackages.compatibilityInfoForPackageLocked(ai);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IPackageManager getPackageManager() {
        return AppGlobals.getPackageManager();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageManagerInternal getPackageManagerInternalLocked() {
        if (this.mPmInternal == null) {
            this.mPmInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        }
        return this.mPmInternal;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PermissionPolicyInternal getPermissionPolicyInternal() {
        if (this.mPermissionPolicyInternal == null) {
            this.mPermissionPolicyInternal = (PermissionPolicyInternal) LocalServices.getService(PermissionPolicyInternal.class);
        }
        return this.mPermissionPolicyInternal;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWarnings getAppWarningsLocked() {
        return this.mAppWarnings;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Intent getHomeIntent() {
        String str = this.mTopAction;
        String str2 = this.mTopData;
        Intent intent = new Intent(str, str2 != null ? Uri.parse(str2) : null);
        intent.setComponent(this.mTopComponent);
        intent.addFlags(256);
        if (this.mFactoryTest != 1) {
            intent.addCategory("android.intent.category.HOME");
        }
        return intent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Intent getSecondaryHomeIntent(String preferredPackage) {
        String str = this.mTopAction;
        String str2 = this.mTopData;
        Intent intent = new Intent(str, str2 != null ? Uri.parse(str2) : null);
        boolean useSystemProvidedLauncher = this.mContext.getResources().getBoolean(17891566);
        if (preferredPackage == null || useSystemProvidedLauncher) {
            String secondaryHomeFromProperty = FeatureOption.FO_SECONDARY_HOME_COMPONENT;
            String secondaryHomeFromResource = this.mContext.getResources().getString(17039775);
            String secondaryHomeComponent = !TextUtils.isEmpty(secondaryHomeFromProperty) ? secondaryHomeFromProperty : secondaryHomeFromResource;
            intent.setComponent(ComponentName.unflattenFromString(secondaryHomeComponent));
        } else {
            intent.setPackage(preferredPackage);
        }
        intent.addFlags(256);
        if (this.mFactoryTest != 1) {
            intent.addCategory("android.intent.category.SECONDARY_HOME");
        }
        return intent;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowProcessController getProcessController(String processName, int uid) {
        if (uid == 1000) {
            SparseArray<WindowProcessController> procs = (SparseArray) this.mProcessNames.getMap().get(processName);
            if (procs == null) {
                return null;
            }
            int procCount = procs.size();
            for (int i = 0; i < procCount; i++) {
                int procUid = procs.keyAt(i);
                if (!UserHandle.isApp(procUid) && UserHandle.isSameUser(procUid, uid)) {
                    return procs.valueAt(i);
                }
            }
        }
        return (WindowProcessController) this.mProcessNames.get(processName, uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowProcessController getProcessController(IApplicationThread thread) {
        if (thread == null) {
            return null;
        }
        IBinder threadBinder = thread.asBinder();
        ArrayMap<String, SparseArray<WindowProcessController>> pmap = this.mProcessNames.getMap();
        for (int i = pmap.size() - 1; i >= 0; i--) {
            SparseArray<WindowProcessController> procs = pmap.valueAt(i);
            for (int j = procs.size() - 1; j >= 0; j--) {
                WindowProcessController proc = procs.valueAt(j);
                if (proc.hasThread() && proc.getThread().asBinder() == threadBinder) {
                    return proc;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowProcessController getProcessController(int pid, int uid) {
        WindowProcessController proc = this.mProcessMap.getProcess(pid);
        if (proc == null || !UserHandle.isApp(uid) || proc.mUid != uid) {
            return null;
        }
        return proc;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getUidState(int uid) {
        return this.mActiveUids.getUidState(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUidForeground(int uid) {
        return this.mWindowManager.mRoot.isAnyNonToastWindowVisibleForUid(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDeviceOwner(int uid) {
        return uid >= 0 && this.mDeviceOwnerUid == uid;
    }

    void setDeviceOwnerUid(int uid) {
        this.mDeviceOwnerUid = uid;
    }

    String getPendingTempWhitelistTagForUidLocked(int uid) {
        return this.mPendingTempWhitelist.get(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void logAppTooSlow(WindowProcessController app, long startTime, String msg) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAssociatedCompanionApp(int userId, int uid) {
        Set<Integer> allUids = this.mCompanionAppUidsMap.get(Integer.valueOf(userId));
        if (allUids == null) {
            return false;
        }
        return allUids.contains(Integer.valueOf(uid));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifySingleTaskDisplayEmpty(int displayId) {
        this.mTaskChangeNotificationController.notifySingleTaskDisplayEmpty(displayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public final class H extends Handler {
        static final int FIRST_ACTIVITY_STACK_MSG = 100;
        static final int FIRST_SUPERVISOR_STACK_MSG = 200;
        static final int REPORT_TIME_TRACKER_MSG = 1;

        H(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                AppTimeTracker tracker = (AppTimeTracker) msg.obj;
                tracker.deliverResult(ActivityTaskManagerService.this.mContext);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public final class UiHandler extends Handler {
        static final int DISMISS_DIALOG_UI_MSG = 1;

        public UiHandler() {
            super(UiThread.get().getLooper(), null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                Dialog d = (Dialog) msg.obj;
                d.dismiss();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public final class LocalService extends ActivityTaskManagerInternal {
        LocalService() {
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public ActivityTaskManagerInternal.SleepToken acquireSleepToken(String tag, int displayId) {
            Preconditions.checkNotNull(tag);
            return ActivityTaskManagerService.this.acquireSleepToken(tag, displayId);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public ComponentName getHomeActivityForUser(int userId) {
            ComponentName componentName;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityRecord homeActivity = ActivityTaskManagerService.this.mRootActivityContainer.getDefaultDisplayHomeActivityForUser(userId);
                    componentName = homeActivity == null ? null : homeActivity.mActivityComponent;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return componentName;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onLocalVoiceInteractionStarted(IBinder activity, IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.onLocalVoiceInteractionStartedLocked(activity, voiceSession, voiceInteractor);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyAppTransitionStarting(SparseIntArray reasons, long timestamp) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mStackSupervisor.getActivityMetricsLogger().notifyTransitionStarting(reasons, timestamp);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifySingleTaskDisplayDrawn(int displayId) {
            ActivityTaskManagerService.this.mTaskChangeNotificationController.notifySingleTaskDisplayDrawn(displayId);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyAppTransitionFinished() {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mStackSupervisor.notifyAppTransitionDone();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyAppTransitionCancelled() {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mStackSupervisor.notifyAppTransitionDone();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public List<IBinder> getTopVisibleActivities() {
            List<IBinder> topVisibleActivities;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    topVisibleActivities = ActivityTaskManagerService.this.mRootActivityContainer.getTopVisibleActivities();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return topVisibleActivities;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyDockedStackMinimizedChanged(boolean minimized) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRootActivityContainer.setDockedStackMinimized(minimized);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public int startActivitiesAsPackage(String packageName, int userId, Intent[] intents, Bundle bOptions) {
            int packageUid;
            Preconditions.checkNotNull(intents, "intents");
            String[] resolvedTypes = new String[intents.length];
            long ident = Binder.clearCallingIdentity();
            for (int i = 0; i < intents.length; i++) {
                try {
                    resolvedTypes[i] = intents[i].resolveTypeIfNeeded(ActivityTaskManagerService.this.mContext.getContentResolver());
                } catch (RemoteException e) {
                } catch (Throwable th) {
                    th = th;
                }
            }
            try {
                packageUid = AppGlobals.getPackageManager().getPackageUid(packageName, 268435456, userId);
                Binder.restoreCallingIdentity(ident);
            } catch (RemoteException e2) {
                Binder.restoreCallingIdentity(ident);
                packageUid = 0;
                return ActivityTaskManagerService.this.getActivityStartController().startActivitiesInPackage(packageUid, packageName, intents, resolvedTypes, null, SafeActivityOptions.fromBundle(bOptions), userId, false, null, false);
            } catch (Throwable th2) {
                th = th2;
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
            return ActivityTaskManagerService.this.getActivityStartController().startActivitiesInPackage(packageUid, packageName, intents, resolvedTypes, null, SafeActivityOptions.fromBundle(bOptions), userId, false, null, false);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public int startActivitiesInPackage(int uid, int realCallingPid, int realCallingUid, String callingPackage, Intent[] intents, String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart) {
            int startActivitiesInPackage;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    startActivitiesInPackage = ActivityTaskManagerService.this.getActivityStartController().startActivitiesInPackage(uid, realCallingPid, realCallingUid, callingPackage, intents, resolvedTypes, resultTo, options, userId, validateIncomingUser, originatingPendingIntent, allowBackgroundActivityStart);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return startActivitiesInPackage;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public int startActivityInPackage(int uid, int realCallingPid, int realCallingUid, String callingPackage, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int startFlags, SafeActivityOptions options, int userId, TaskRecord inTask, String reason, boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart) {
            int startActivityInPackage;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    startActivityInPackage = ActivityTaskManagerService.this.getActivityStartController().startActivityInPackage(uid, realCallingPid, realCallingUid, callingPackage, intent, resolvedType, resultTo, resultWho, requestCode, startFlags, options, userId, inTask, reason, validateIncomingUser, originatingPendingIntent, allowBackgroundActivityStart);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return startActivityInPackage;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public int startActivityAsUser(IApplicationThread caller, String callerPacakge, Intent intent, Bundle options, int userId) {
            ActivityTaskManagerService activityTaskManagerService = ActivityTaskManagerService.this;
            return activityTaskManagerService.startActivityAsUser(caller, callerPacakge, intent, intent.resolveTypeIfNeeded(activityTaskManagerService.mContext.getContentResolver()), null, null, 0, 268435456, null, options, userId, false);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyKeyguardFlagsChanged(Runnable callback, int displayId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityDisplay activityDisplay = ActivityTaskManagerService.this.mRootActivityContainer.getActivityDisplay(displayId);
                    if (activityDisplay == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    DisplayContent dc = activityDisplay.mDisplayContent;
                    boolean wasTransitionSet = dc.mAppTransition.getAppTransition() != 0;
                    if (!wasTransitionSet) {
                        dc.prepareAppTransition(0, false);
                    }
                    ActivityTaskManagerService.this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
                    if (!wasTransitionSet) {
                        dc.executeAppTransition();
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    if (callback != null) {
                        callback.run();
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyKeyguardTrustedChanged() {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (ActivityTaskManagerService.this.mKeyguardController.isKeyguardShowing(0)) {
                        ActivityTaskManagerService.this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setVr2dDisplayId(int vr2dDisplayId) {
            if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
                Slog.d("ActivityTaskManager", "setVr2dDisplayId called for: " + vr2dDisplayId);
            }
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mVr2dDisplayId = vr2dDisplayId;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setFocusedActivity(IBinder token) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityRecord r = ActivityRecord.forTokenLocked(token);
                    if (r == null) {
                        throw new IllegalArgumentException("setFocusedActivity: No activity record matching token=" + token);
                    } else if (r.moveFocusableActivityToTop("setFocusedActivity")) {
                        ActivityTaskManagerService.this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void registerScreenObserver(ActivityTaskManagerInternal.ScreenObserver observer) {
            ActivityTaskManagerService.this.mScreenObservers.add(observer);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isCallerRecents(int callingUid) {
            return ActivityTaskManagerService.this.getRecentTasks().isCallerRecents(callingUid);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isRecentsComponentHomeActivity(int userId) {
            return ActivityTaskManagerService.this.getRecentTasks().isRecentsComponentHomeActivity(userId);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void cancelRecentsAnimation(boolean restoreHomeStackPosition) {
            ActivityTaskManagerService.this.cancelRecentsAnimation(restoreHomeStackPosition);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void enforceCallerIsRecentsOrHasPermission(String permission, String func) {
            ActivityTaskManagerService.this.enforceCallerIsRecentsOrHasPermission(permission, func);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyActiveVoiceInteractionServiceChanged(ComponentName component) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mActiveVoiceInteractionServiceComponent = component;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setAllowAppSwitches(String type, int uid, int userId) {
            if (!ActivityTaskManagerService.this.mAmInternal.isUserRunning(userId, 1)) {
                return;
            }
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ArrayMap<String, Integer> types = ActivityTaskManagerService.this.mAllowAppSwitchUids.get(userId);
                    if (types == null) {
                        if (uid < 0) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        } else {
                            types = new ArrayMap<>();
                            ActivityTaskManagerService.this.mAllowAppSwitchUids.put(userId, types);
                        }
                    }
                    if (uid < 0) {
                        types.remove(type);
                    } else {
                        types.put(type, Integer.valueOf(uid));
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onUserStopped(int userId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.getRecentTasks().unloadUserDataFromMemoryLocked(userId);
                    ActivityTaskManagerService.this.mAllowAppSwitchUids.remove(userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isGetTasksAllowed(String caller, int callingPid, int callingUid) {
            boolean isGetTasksAllowed;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    isGetTasksAllowed = ActivityTaskManagerService.this.isGetTasksAllowed(caller, callingPid, callingUid);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return isGetTasksAllowed;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onProcessAdded(WindowProcessController proc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                ActivityTaskManagerService.this.mProcessNames.put(proc.mName, proc.mUid, proc);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onProcessRemoved(String name, int uid) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                ActivityTaskManagerService.this.mProcessNames.remove(name, uid);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onCleanUpApplicationRecord(WindowProcessController proc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                if (proc == ActivityTaskManagerService.this.mHomeProcess) {
                    ActivityTaskManagerService.this.mHomeProcess = null;
                }
                if (proc == ActivityTaskManagerService.this.mPreviousProcess) {
                    ActivityTaskManagerService.this.mPreviousProcess = null;
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public int getTopProcessState() {
            int i;
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                i = ActivityTaskManagerService.this.mTopProcessState;
            }
            return i;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isHeavyWeightProcess(WindowProcessController proc) {
            boolean z;
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                z = proc == ActivityTaskManagerService.this.mHeavyWeightProcess;
            }
            return z;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void clearHeavyWeightProcessIfEquals(WindowProcessController proc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                ActivityTaskManagerService.this.clearHeavyWeightProcessIfEquals(proc);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void finishHeavyWeightApp() {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (ActivityTaskManagerService.this.mHeavyWeightProcess != null) {
                        ActivityTaskManagerService.this.mHeavyWeightProcess.finishActivities();
                    }
                    ActivityTaskManagerService.this.clearHeavyWeightProcessIfEquals(ActivityTaskManagerService.this.mHeavyWeightProcess);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isSleeping() {
            boolean isSleepingLocked;
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                isSleepingLocked = ActivityTaskManagerService.this.isSleepingLocked();
            }
            return isSleepingLocked;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isShuttingDown() {
            boolean z;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    z = ActivityTaskManagerService.this.mShuttingDown;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return z;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean shuttingDown(boolean booted, int timeout) {
            boolean shutdownLocked;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mShuttingDown = true;
                    ActivityTaskManagerService.this.mRootActivityContainer.prepareForShutdown();
                    ActivityTaskManagerService.this.updateEventDispatchingLocked(booted);
                    ActivityTaskManagerService.this.notifyTaskPersisterLocked(null, true);
                    shutdownLocked = ActivityTaskManagerService.this.mStackSupervisor.shutdownLocked(timeout);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return shutdownLocked;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void enableScreenAfterBoot(boolean booted) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    EventLog.writeEvent((int) EventLogTags.BOOT_PROGRESS_ENABLE_SCREEN, SystemClock.uptimeMillis());
                    BootEvent.addBootEvent("AMS:ENABLE_SCREEN");
                    ActivityTaskManagerService.this.mWindowManager.enableScreenAfterBoot();
                    ActivityTaskManagerService.this.updateEventDispatchingLocked(booted);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean showStrictModeViolationDialog() {
            boolean z;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    z = (!ActivityTaskManagerService.this.mShowDialogs || this.mSleeping || ActivityTaskManagerService.this.mShuttingDown) ? false : true;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return z;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void showSystemReadyErrorDialogsIfNeeded() {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (AppGlobals.getPackageManager().hasSystemUidErrors()) {
                            Slog.e("ActivityTaskManager", "UIDs on the system are inconsistent, you need to wipe your data partition or your device will be unstable.");
                            ActivityTaskManagerService.this.mUiHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$LocalService$hXNJNh8HjV10X1ZEOI6o0Yzmq8o
                                @Override // java.lang.Runnable
                                public final void run() {
                                    ActivityTaskManagerService.LocalService.this.lambda$showSystemReadyErrorDialogsIfNeeded$0$ActivityTaskManagerService$LocalService();
                                }
                            });
                        }
                    } catch (RemoteException e) {
                    }
                    if (!Build.isBuildConsistent()) {
                        Slog.e("ActivityTaskManager", "Build fingerprint is not consistent, warning user");
                        ActivityTaskManagerService.this.mUiHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$LocalService$xIfx_hFO4SXy-Nq34zoEHe3S9eU
                            @Override // java.lang.Runnable
                            public final void run() {
                                ActivityTaskManagerService.LocalService.this.lambda$showSystemReadyErrorDialogsIfNeeded$1$ActivityTaskManagerService$LocalService();
                            }
                        });
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        public /* synthetic */ void lambda$showSystemReadyErrorDialogsIfNeeded$0$ActivityTaskManagerService$LocalService() {
            if (ActivityTaskManagerService.this.mShowDialogs) {
                AlertDialog d = new BaseErrorDialog(ActivityTaskManagerService.this.mUiContext);
                d.getWindow().setType(2010);
                d.setCancelable(false);
                d.setTitle(ActivityTaskManagerService.this.mUiContext.getText(17039490));
                d.setMessage(ActivityTaskManagerService.this.mUiContext.getText(17041134));
                d.setButton(-1, ActivityTaskManagerService.this.mUiContext.getText(17039370), ActivityTaskManagerService.this.mUiHandler.obtainMessage(1, d));
                d.show();
            }
        }

        public /* synthetic */ void lambda$showSystemReadyErrorDialogsIfNeeded$1$ActivityTaskManagerService$LocalService() {
            if (ActivityTaskManagerService.this.mShowDialogs) {
                AlertDialog d = new BaseErrorDialog(ActivityTaskManagerService.this.mUiContext);
                d.getWindow().setType(2010);
                d.setCancelable(false);
                d.setTitle(ActivityTaskManagerService.this.mUiContext.getText(17039490));
                d.setMessage(ActivityTaskManagerService.this.mUiContext.getText(17041133));
                d.setButton(-1, ActivityTaskManagerService.this.mUiContext.getText(17039370), ActivityTaskManagerService.this.mUiHandler.obtainMessage(1, d));
                d.show();
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onProcessMapped(int pid, WindowProcessController proc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mProcessMap.put(pid, proc);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onProcessUnMapped(int pid) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mProcessMap.remove(pid);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onPackageDataCleared(String name) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mCompatModePackages.handlePackageDataClearedLocked(name);
                    ActivityTaskManagerService.this.mAppWarnings.onPackageDataCleared(name);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onPackageUninstalled(String name) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mAppWarnings.onPackageUninstalled(name);
                    ActivityTaskManagerService.this.mCompatModePackages.handlePackageUninstalledLocked(name);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onPackageAdded(String name, boolean replacing) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mCompatModePackages.handlePackageAddedLocked(name, replacing);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onPackageReplaced(ApplicationInfo aInfo) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRootActivityContainer.updateActivityApplicationInfo(aInfo);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public CompatibilityInfo compatibilityInfoForPackage(ApplicationInfo ai) {
            CompatibilityInfo compatibilityInfoForPackageLocked;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    compatibilityInfoForPackageLocked = ActivityTaskManagerService.this.compatibilityInfoForPackageLocked(ai);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return compatibilityInfoForPackageLocked;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onImeWindowSetOnDisplay(int pid, int displayId) {
            if (InputMethodSystemProperty.MULTI_CLIENT_IME_ENABLED) {
                return;
            }
            if (pid == ActivityManagerService.MY_PID || pid < 0) {
                if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                    Slog.w("ActivityTaskManager", "Trying to update display configuration for system/invalid process.");
                    return;
                }
                return;
            }
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityDisplay activityDisplay = ActivityTaskManagerService.this.mRootActivityContainer.getActivityDisplay(displayId);
                    if (activityDisplay == null) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.w("ActivityTaskManager", "Trying to update display configuration for non-existing displayId=" + displayId);
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    WindowProcessController process = ActivityTaskManagerService.this.mProcessMap.getProcess(pid);
                    if (process == null) {
                        if (ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION) {
                            Slog.w("ActivityTaskManager", "Trying to update display configuration for invalid process, pid=" + pid);
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    process.registerDisplayConfigurationListenerLocked(activityDisplay);
                    WindowManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void sendActivityResult(int callingUid, IBinder activityToken, String resultWho, int requestCode, int resultCode, Intent data) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                    if (r != null && r.getActivityStack() != null) {
                        r.getActivityStack().sendActivityResultLocked(callingUid, r, resultWho, requestCode, resultCode, data);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void clearPendingResultForActivity(IBinder activityToken, WeakReference<PendingIntentRecord> pir) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                    if (r != null && r.pendingResults != null) {
                        r.pendingResults.remove(pir);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public ActivityTaskManagerInternal.ActivityTokens getTopActivityForTask(int taskId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    TaskRecord taskRecord = ActivityTaskManagerService.this.mRootActivityContainer.anyTaskForId(taskId);
                    if (taskRecord == null) {
                        Slog.w("ActivityTaskManager", "getApplicationThreadForTopActivity failed: Requested task not found");
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    }
                    ActivityRecord activity = taskRecord.getTopActivity();
                    if (activity == null) {
                        Slog.w("ActivityTaskManager", "getApplicationThreadForTopActivity failed: Requested activity not found");
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    } else if (!activity.attachedToProcess()) {
                        Slog.w("ActivityTaskManager", "getApplicationThreadForTopActivity failed: No process for " + activity);
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    } else {
                        ActivityTaskManagerInternal.ActivityTokens activityTokens = new ActivityTaskManagerInternal.ActivityTokens(activity.appToken, activity.assistToken, activity.app.getThread());
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return activityTokens;
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public IIntentSender getIntentSender(int type, String packageName, int callingUid, int userId, IBinder token, String resultWho, int requestCode, Intent[] intents, String[] resolvedTypes, int flags, Bundle bOptions) {
            IIntentSender intentSenderLocked;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    intentSenderLocked = ActivityTaskManagerService.this.getIntentSenderLocked(type, packageName, callingUid, userId, token, resultWho, requestCode, intents, resolvedTypes, flags, bOptions);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return intentSenderLocked;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public ActivityServiceConnectionsHolder getServiceConnectionsHolder(IBinder token) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityRecord r = ActivityRecord.isInStackLocked(token);
                    if (r == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return null;
                    }
                    if (r.mServiceConnectionsHolder == null) {
                        r.mServiceConnectionsHolder = new ActivityServiceConnectionsHolder(ActivityTaskManagerService.this, r);
                    }
                    ActivityServiceConnectionsHolder activityServiceConnectionsHolder = r.mServiceConnectionsHolder;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return activityServiceConnectionsHolder;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public Intent getHomeIntent() {
            Intent homeIntent;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    homeIntent = ActivityTaskManagerService.this.getHomeIntent();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return homeIntent;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean startHomeActivity(int userId, String reason) {
            boolean startHomeOnDisplay;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    startHomeOnDisplay = ActivityTaskManagerService.this.mRootActivityContainer.startHomeOnDisplay(userId, reason, 0);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return startHomeOnDisplay;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean startHomeOnDisplay(int userId, String reason, int displayId, boolean allowInstrumenting, boolean fromHomeKey) {
            boolean startHomeOnDisplay;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    startHomeOnDisplay = ActivityTaskManagerService.this.mRootActivityContainer.startHomeOnDisplay(userId, reason, displayId, allowInstrumenting, fromHomeKey);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return startHomeOnDisplay;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean startHomeOnAllDisplays(int userId, String reason) {
            boolean startHomeOnAllDisplays;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    startHomeOnAllDisplays = ActivityTaskManagerService.this.mRootActivityContainer.startHomeOnAllDisplays(userId, reason);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return startHomeOnAllDisplays;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isFactoryTestProcess(WindowProcessController wpc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                boolean z = false;
                if (ActivityTaskManagerService.this.mFactoryTest == 0) {
                    return false;
                }
                if (ActivityTaskManagerService.this.mFactoryTest == 1 && ActivityTaskManagerService.this.mTopComponent != null && wpc.mName.equals(ActivityTaskManagerService.this.mTopComponent.getPackageName())) {
                    return true;
                }
                if (ActivityTaskManagerService.this.mFactoryTest == 2 && (wpc.mInfo.flags & 16) != 0) {
                    z = true;
                }
                return z;
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void updateTopComponentForFactoryTest() {
            final CharSequence errorMsg;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (ActivityTaskManagerService.this.mFactoryTest != 1) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    ResolveInfo ri = ActivityTaskManagerService.this.mContext.getPackageManager().resolveActivity(new Intent("android.intent.action.FACTORY_TEST"), 1024);
                    if (ri != null) {
                        ActivityInfo ai = ri.activityInfo;
                        ApplicationInfo app = ai.applicationInfo;
                        if ((1 & app.flags) != 0) {
                            ActivityTaskManagerService.this.mTopAction = "android.intent.action.FACTORY_TEST";
                            ActivityTaskManagerService.this.mTopData = null;
                            ActivityTaskManagerService.this.mTopComponent = new ComponentName(app.packageName, ai.name);
                            errorMsg = null;
                        } else {
                            errorMsg = ActivityTaskManagerService.this.mContext.getResources().getText(17040007);
                        }
                    } else {
                        errorMsg = ActivityTaskManagerService.this.mContext.getResources().getText(17040006);
                    }
                    if (errorMsg == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    ActivityTaskManagerService.this.mTopAction = null;
                    ActivityTaskManagerService.this.mTopData = null;
                    ActivityTaskManagerService.this.mTopComponent = null;
                    ActivityTaskManagerService.this.mUiHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$ActivityTaskManagerService$LocalService$smesvyl87CxHptMAvRA559Glc1k
                        @Override // java.lang.Runnable
                        public final void run() {
                            ActivityTaskManagerService.LocalService.this.lambda$updateTopComponentForFactoryTest$2$ActivityTaskManagerService$LocalService(errorMsg);
                        }
                    });
                    WindowManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        public /* synthetic */ void lambda$updateTopComponentForFactoryTest$2$ActivityTaskManagerService$LocalService(CharSequence errorMsg) {
            Dialog d = new FactoryErrorDialog(ActivityTaskManagerService.this.mUiContext, errorMsg);
            d.show();
            ActivityTaskManagerService.this.mAmInternal.ensureBootCompleted();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void handleAppDied(WindowProcessController wpc, boolean restarting, Runnable finishInstrumentationCallback) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                boolean hasVisibleActivities = ActivityTaskManagerService.this.mRootActivityContainer.handleAppDied(wpc);
                wpc.clearRecentTasks();
                wpc.clearActivities();
                if (wpc.isInstrumenting()) {
                    finishInstrumentationCallback.run();
                }
                if (!restarting && hasVisibleActivities) {
                    ActivityTaskManagerService.this.mWindowManager.deferSurfaceLayout();
                    if (!ActivityTaskManagerService.this.mRootActivityContainer.resumeFocusedStacksTopActivities()) {
                        ActivityTaskManagerService.this.mRootActivityContainer.ensureActivitiesVisible(null, 0, false);
                    }
                    ActivityTaskManagerService.this.mWindowManager.continueSurfaceLayout();
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void closeSystemDialogs(String reason) {
            ActivityTaskManagerService.this.enforceNotIsolatedCaller("closeSystemDialogs");
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long origId = Binder.clearCallingIdentity();
            try {
                synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (uid >= 10000) {
                        WindowProcessController proc = ActivityTaskManagerService.this.mProcessMap.getProcess(pid);
                        if (!proc.isPerceptible()) {
                            Slog.w("ActivityTaskManager", "Ignoring closeSystemDialogs " + reason + " from background process " + proc);
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                    }
                    ActivityTaskManagerService.this.mWindowManager.closeSystemDialogs(reason);
                    ActivityTaskManagerService.this.mRootActivityContainer.closeSystemDialogs();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    ActivityTaskManagerService.this.mAmInternal.broadcastCloseSystemDialogs(reason);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void cleanupDisabledPackageComponents(String packageName, Set<String> disabledClasses, int userId, boolean booted) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (ActivityTaskManagerService.this.mRootActivityContainer.finishDisabledPackageActivities(packageName, disabledClasses, true, false, userId) && booted) {
                        ActivityTaskManagerService.this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                        ActivityTaskManagerService.this.mStackSupervisor.scheduleIdleLocked();
                    }
                    ActivityTaskManagerService.this.getRecentTasks().cleanupDisabledPackageTasksLocked(packageName, disabledClasses, userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean onForceStopPackage(String packageName, boolean doit, boolean evenPersistent, int userId) {
            boolean didSomething;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    boolean didSomething2 = ActivityTaskManagerService.this.getActivityStartController().clearPendingActivityLaunches(packageName);
                    didSomething = didSomething2 | ActivityTaskManagerService.this.mRootActivityContainer.finishDisabledPackageActivities(packageName, null, doit, evenPersistent, userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return didSomething;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void resumeTopActivities(boolean scheduleIdle) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                    if (scheduleIdle) {
                        ActivityTaskManagerService.this.mStackSupervisor.scheduleIdleLocked();
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void preBindApplication(WindowProcessController wpc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                ActivityTaskManagerService.this.mStackSupervisor.getActivityMetricsLogger().notifyBindApplication(wpc.mInfo);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean attachApplication(WindowProcessController wpc) throws RemoteException {
            boolean attachApplication;
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                attachApplication = ActivityTaskManagerService.this.mRootActivityContainer.attachApplication(wpc);
            }
            return attachApplication;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void notifyLockedProfile(int userId, int currentUserId) {
            try {
                if (!AppGlobals.getPackageManager().isUidPrivileged(Binder.getCallingUid())) {
                    throw new SecurityException("Only privileged app can call notifyLockedProfile");
                }
                synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        long ident = Binder.clearCallingIdentity();
                        if (ActivityTaskManagerService.this.mAmInternal.shouldConfirmCredentials(userId)) {
                            if (ActivityTaskManagerService.this.mKeyguardController.isKeyguardLocked()) {
                                startHomeActivity(currentUserId, "notifyLockedProfile");
                            }
                            ActivityTaskManagerService.this.mRootActivityContainer.lockAllProfileTasks(userId);
                        }
                        Binder.restoreCallingIdentity(ident);
                    } catch (Throwable th) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (RemoteException ex) {
                throw new SecurityException("Fail to check is caller a privileged app", ex);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void startConfirmDeviceCredentialIntent(Intent intent, Bundle options) {
            ActivityTaskManagerService.this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "startConfirmDeviceCredentialIntent");
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    long ident = Binder.clearCallingIdentity();
                    intent.addFlags(276824064);
                    ActivityOptions activityOptions = options != null ? new ActivityOptions(options) : ActivityOptions.makeBasic();
                    ActivityTaskManagerService.this.mContext.startActivityAsUser(intent, activityOptions.toBundle(), UserHandle.CURRENT);
                    Binder.restoreCallingIdentity(ident);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void writeActivitiesToProto(ProtoOutputStream proto) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRootActivityContainer.writeToProto(proto, 1146756268033L, 0);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void saveANRState(String reason) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                    pw.println("  ANR time: " + DateFormat.getDateTimeInstance().format(new Date()));
                    if (reason != null) {
                        pw.println("  Reason: " + reason);
                    }
                    pw.println();
                    ActivityTaskManagerService.this.getActivityStartController().dump(pw, "  ", null);
                    pw.println();
                    pw.println("-------------------------------------------------------------------------------");
                    ActivityTaskManagerService.this.dumpActivitiesLocked(null, pw, null, 0, true, false, null, "");
                    pw.println();
                    pw.close();
                    ActivityTaskManagerService.this.mLastANRState = sw.toString();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void clearSavedANRState() {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mLastANRState = null;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void dump(String cmd, FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (!ActivityTaskManagerService.DUMP_ACTIVITIES_CMD.equals(cmd) && !ActivityTaskManagerService.DUMP_ACTIVITIES_SHORT_CMD.equals(cmd)) {
                            if (ActivityTaskManagerService.DUMP_LASTANR_CMD.equals(cmd)) {
                                ActivityTaskManagerService.this.dumpLastANRLocked(pw);
                            } else if (ActivityTaskManagerService.DUMP_LASTANR_TRACES_CMD.equals(cmd)) {
                                ActivityTaskManagerService.this.dumpLastANRTracesLocked(pw);
                            } else if (ActivityTaskManagerService.DUMP_STARTER_CMD.equals(cmd)) {
                                ActivityTaskManagerService.this.dumpActivityStarterLocked(pw, dumpPackage);
                            } else if (ActivityTaskManagerService.DUMP_CONTAINERS_CMD.equals(cmd)) {
                                ActivityTaskManagerService.this.dumpActivityContainersLocked(pw);
                            } else {
                                if (!ActivityTaskManagerService.DUMP_RECENTS_CMD.equals(cmd) && !ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD.equals(cmd)) {
                                }
                                if (ActivityTaskManagerService.this.getRecentTasks() != null) {
                                    ActivityTaskManagerService.this.getRecentTasks().dump(pw, dumpAll, dumpPackage);
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                        }
                        ActivityTaskManagerService.this.dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
                        WindowManagerService.resetPriorityAfterLockedSection();
                    } catch (Throwable th) {
                        th = th;
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        /* JADX WARN: Code restructure failed: missing block: B:113:0x0369, code lost:
            r0 = th;
         */
        /* JADX WARN: Code restructure failed: missing block: B:88:0x02a2, code lost:
            r15.println();
            r5 = false;
         */
        /* JADX WARN: Removed duplicated region for block: B:122:0x0191 A[EXC_TOP_SPLITTER, SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:24:0x0065 A[Catch: all -> 0x0357, TryCatch #2 {all -> 0x0357, blocks: (B:16:0x0034, B:18:0x004f, B:21:0x0057, B:24:0x0065, B:25:0x006a, B:27:0x0084, B:30:0x008c, B:32:0x0098, B:33:0x00ae, B:36:0x00b6, B:39:0x00c4, B:40:0x00c9, B:42:0x00e3, B:45:0x010a, B:46:0x0126, B:48:0x0134, B:49:0x0145, B:51:0x014b, B:53:0x0163, B:57:0x016c, B:58:0x0172, B:63:0x01c9), top: B:119:0x0034 }] */
        /* JADX WARN: Removed duplicated region for block: B:39:0x00c4 A[Catch: all -> 0x0357, TryCatch #2 {all -> 0x0357, blocks: (B:16:0x0034, B:18:0x004f, B:21:0x0057, B:24:0x0065, B:25:0x006a, B:27:0x0084, B:30:0x008c, B:32:0x0098, B:33:0x00ae, B:36:0x00b6, B:39:0x00c4, B:40:0x00c9, B:42:0x00e3, B:45:0x010a, B:46:0x0126, B:48:0x0134, B:49:0x0145, B:51:0x014b, B:53:0x0163, B:57:0x016c, B:58:0x0172, B:63:0x01c9), top: B:119:0x0034 }] */
        /* JADX WARN: Removed duplicated region for block: B:42:0x00e3 A[Catch: all -> 0x0357, TryCatch #2 {all -> 0x0357, blocks: (B:16:0x0034, B:18:0x004f, B:21:0x0057, B:24:0x0065, B:25:0x006a, B:27:0x0084, B:30:0x008c, B:32:0x0098, B:33:0x00ae, B:36:0x00b6, B:39:0x00c4, B:40:0x00c9, B:42:0x00e3, B:45:0x010a, B:46:0x0126, B:48:0x0134, B:49:0x0145, B:51:0x014b, B:53:0x0163, B:57:0x016c, B:58:0x0172, B:63:0x01c9), top: B:119:0x0034 }] */
        /* JADX WARN: Removed duplicated region for block: B:44:0x0108  */
        /* JADX WARN: Removed duplicated region for block: B:69:0x024f  */
        /* JADX WARN: Removed duplicated region for block: B:72:0x0257 A[Catch: all -> 0x0353, TryCatch #1 {all -> 0x0353, blocks: (B:66:0x0227, B:70:0x0251, B:72:0x0257, B:73:0x0261, B:76:0x026e, B:78:0x0278, B:79:0x0283, B:82:0x028b), top: B:117:0x0227 }] */
        /* JADX WARN: Removed duplicated region for block: B:75:0x026b  */
        /* JADX WARN: Removed duplicated region for block: B:95:0x02ec  */
        /* JADX WARN: Removed duplicated region for block: B:97:0x02f0 A[Catch: all -> 0x0369, TryCatch #3 {all -> 0x0369, blocks: (B:92:0x02e1, B:88:0x02a2, B:90:0x02a8, B:91:0x02ae, B:110:0x0364, B:93:0x02e4, B:97:0x02f0, B:99:0x02f6, B:100:0x031a, B:101:0x034e), top: B:121:0x02a2 }] */
        @Override // com.android.server.wm.ActivityTaskManagerInternal
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public boolean dumpForProcesses(java.io.FileDescriptor r14, java.io.PrintWriter r15, boolean r16, java.lang.String r17, int r18, boolean r19, boolean r20, int r21) {
            /*
                Method dump skipped, instructions count: 875
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.ActivityTaskManagerService.LocalService.dumpForProcesses(java.io.FileDescriptor, java.io.PrintWriter, boolean, java.lang.String, int, boolean, boolean, int):boolean");
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void writeProcessesToProto(ProtoOutputStream proto, String dumpPackage, int wakeFullness, boolean testPssMode) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (dumpPackage == null) {
                        ActivityTaskManagerService.this.getGlobalConfiguration().writeToProto(proto, 1146756268051L);
                        proto.write(1133871366165L, ActivityTaskManagerService.this.getTopDisplayFocusedStack().mConfigWillChange);
                        ActivityTaskManagerService.this.writeSleepStateToProto(proto, wakeFullness, testPssMode);
                        if (ActivityTaskManagerService.this.mRunningVoice != null) {
                            long vrToken = proto.start(1146756268060L);
                            proto.write(1138166333441L, ActivityTaskManagerService.this.mRunningVoice.toString());
                            ActivityTaskManagerService.this.mVoiceWakeLock.writeToProto(proto, 1146756268034L);
                            proto.end(vrToken);
                        }
                        ActivityTaskManagerService.this.mVrController.writeToProto(proto, 1146756268061L);
                        if (ActivityTaskManagerService.this.mController != null) {
                            long token = proto.start(1146756268069L);
                            proto.write(1146756268069L, ActivityTaskManagerService.this.mController.toString());
                            proto.write(1133871366146L, ActivityTaskManagerService.this.mControllerIsAMonkey);
                            proto.end(token);
                        }
                        ActivityTaskManagerService.this.mStackSupervisor.mGoingToSleepWakeLock.writeToProto(proto, 1146756268079L);
                        ActivityTaskManagerService.this.mStackSupervisor.mLaunchingActivityWakeLock.writeToProto(proto, 1146756268080L);
                    }
                    if (ActivityTaskManagerService.this.mHomeProcess != null && (dumpPackage == null || ActivityTaskManagerService.this.mHomeProcess.mPkgList.contains(dumpPackage))) {
                        ActivityTaskManagerService.this.mHomeProcess.writeToProto(proto, 1146756268047L);
                    }
                    if (ActivityTaskManagerService.this.mPreviousProcess != null && (dumpPackage == null || ActivityTaskManagerService.this.mPreviousProcess.mPkgList.contains(dumpPackage))) {
                        ActivityTaskManagerService.this.mPreviousProcess.writeToProto(proto, 1146756268048L);
                        proto.write(1112396529681L, ActivityTaskManagerService.this.mPreviousProcessVisibleTime);
                    }
                    if (ActivityTaskManagerService.this.mHeavyWeightProcess != null && (dumpPackage == null || ActivityTaskManagerService.this.mHeavyWeightProcess.mPkgList.contains(dumpPackage))) {
                        ActivityTaskManagerService.this.mHeavyWeightProcess.writeToProto(proto, 1146756268050L);
                    }
                    for (Map.Entry<String, Integer> entry : ActivityTaskManagerService.this.mCompatModePackages.getPackages().entrySet()) {
                        String pkg = entry.getKey();
                        int mode = entry.getValue().intValue();
                        if (dumpPackage == null || dumpPackage.equals(pkg)) {
                            long compatToken = proto.start(2246267895830L);
                            proto.write(1138166333441L, pkg);
                            proto.write(1120986464258L, mode);
                            proto.end(compatToken);
                        }
                    }
                    if (ActivityTaskManagerService.this.mCurAppTimeTracker != null) {
                        ActivityTaskManagerService.this.mCurAppTimeTracker.writeToProto(proto, 1146756268063L, true);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name, String[] args, int opti, boolean dumpAll, boolean dumpVisibleStacksOnly, boolean dumpFocusedStackOnly) {
            return ActivityTaskManagerService.this.dumpActivity(fd, pw, name, args, opti, dumpAll, dumpVisibleStacksOnly, dumpFocusedStackOnly);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void dumpForOom(PrintWriter pw) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    pw.println("  mHomeProcess: " + ActivityTaskManagerService.this.mHomeProcess);
                    pw.println("  mPreviousProcess: " + ActivityTaskManagerService.this.mPreviousProcess);
                    if (ActivityTaskManagerService.this.mHeavyWeightProcess != null) {
                        pw.println("  mHeavyWeightProcess: " + ActivityTaskManagerService.this.mHeavyWeightProcess);
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean canGcNow() {
            boolean z;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    z = isSleeping() || ActivityTaskManagerService.this.mRootActivityContainer.allResumedActivitiesIdle();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return z;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public WindowProcessController getTopApp() {
            WindowProcessController windowProcessController;
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                ActivityRecord top = ActivityTaskManagerService.this.mRootActivityContainer.getTopResumedActivity();
                windowProcessController = top != null ? top.app : null;
            }
            return windowProcessController;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void rankTaskLayersIfNeeded() {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                if (ActivityTaskManagerService.this.mRootActivityContainer != null) {
                    ActivityTaskManagerService.this.mRootActivityContainer.rankTaskLayersIfNeeded();
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void scheduleDestroyAllActivities(String reason) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRootActivityContainer.scheduleDestroyAllActivities(null, reason);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void removeUser(int userId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRootActivityContainer.removeUser(userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean switchUser(int userId, UserState userState) {
            boolean switchUser;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    switchUser = ActivityTaskManagerService.this.mRootActivityContainer.switchUser(userId, userState);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return switchUser;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onHandleAppCrash(WindowProcessController wpc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRootActivityContainer.handleAppCrash(wpc);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public int finishTopCrashedActivities(WindowProcessController crashedApp, String reason) {
            int finishTopCrashedActivities;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    finishTopCrashedActivities = ActivityTaskManagerService.this.mRootActivityContainer.finishTopCrashedActivities(crashedApp, reason);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return finishTopCrashedActivities;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onUidActive(int uid, int procState) {
            ActivityTaskManagerService.this.mActiveUids.onUidActive(uid, procState);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onUidInactive(int uid) {
            ActivityTaskManagerService.this.mActiveUids.onUidInactive(uid);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onActiveUidsCleared() {
            ActivityTaskManagerService.this.mActiveUids.onActiveUidsCleared();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onUidProcStateChanged(int uid, int procState) {
            ActivityTaskManagerService.this.mActiveUids.onUidProcStateChanged(uid, procState);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onUidAddedToPendingTempWhitelist(int uid, String tag) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                ActivityTaskManagerService.this.mPendingTempWhitelist.put(uid, tag);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onUidRemovedFromPendingTempWhitelist(int uid) {
            synchronized (ActivityTaskManagerService.this.mGlobalLockWithoutBoost) {
                ActivityTaskManagerService.this.mPendingTempWhitelist.remove(uid);
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean handleAppCrashInActivityController(String processName, int pid, String shortMsg, String longMsg, long timeMillis, String stackTrace, Runnable killCrashingAppCallback) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (ActivityTaskManagerService.this.mController == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return false;
                    }
                    try {
                        if (!ActivityTaskManagerService.this.mController.appCrashed(processName, pid, shortMsg, longMsg, timeMillis, stackTrace)) {
                            killCrashingAppCallback.run();
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return true;
                        }
                    } catch (RemoteException e) {
                        ActivityTaskManagerService.this.mController = null;
                        Watchdog.getInstance().setActivityController(null);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void removeRecentTasksByPackageName(String packageName, int userId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRecentTasks.removeTasksByPackageName(packageName, userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void cleanupRecentTasksForUser(int userId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRecentTasks.cleanupLocked(userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void loadRecentTasksForUser(int userId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRecentTasks.loadUserRecentsLocked(userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void onPackagesSuspendedChanged(String[] packages, boolean suspended, int userId) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mRecentTasks.onPackagesSuspendedChanged(packages, suspended, userId);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void flushRecentTasks() {
            ActivityTaskManagerService.this.mRecentTasks.flush();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public WindowProcessController getHomeProcess() {
            WindowProcessController windowProcessController;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    windowProcessController = ActivityTaskManagerService.this.mHomeProcess;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return windowProcessController;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public WindowProcessController getPreviousProcess() {
            WindowProcessController windowProcessController;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    windowProcessController = ActivityTaskManagerService.this.mPreviousProcess;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return windowProcessController;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void clearLockedTasks(String reason) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.getLockTaskController().clearLockedTasks(reason);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void updateUserConfiguration() {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    Configuration configuration = new Configuration(ActivityTaskManagerService.this.getGlobalConfiguration());
                    int currentUserId = ActivityTaskManagerService.this.mAmInternal.getCurrentUserId();
                    Settings.System.adjustConfigurationForUser(ActivityTaskManagerService.this.mContext.getContentResolver(), configuration, currentUserId, Settings.System.canWrite(ActivityTaskManagerService.this.mContext));
                    ActivityTaskManagerService.this.updateConfigurationLocked(configuration, null, false, false, currentUserId, false);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean canShowErrorDialogs() {
            boolean z;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    z = false;
                    if (ActivityTaskManagerService.this.mShowDialogs && !this.mSleeping && !ActivityTaskManagerService.this.mShuttingDown && !ActivityTaskManagerService.this.mKeyguardController.isKeyguardOrAodShowing(0) && !ActivityTaskManagerService.this.hasUserRestriction("no_system_error_dialogs", ActivityTaskManagerService.this.mAmInternal.getCurrentUserId()) && (!UserManager.isDeviceInDemoMode(ActivityTaskManagerService.this.mContext) || !ActivityTaskManagerService.this.mAmInternal.getCurrentUser().isDemo())) {
                        z = true;
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return z;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setProfileApp(String profileApp) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mProfileApp = profileApp;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setProfileProc(WindowProcessController wpc) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mProfileProc = wpc;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setProfilerInfo(ProfilerInfo profilerInfo) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mProfilerInfo = profilerInfo;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public ActivityMetricsLaunchObserverRegistry getLaunchObserverRegistry() {
            ActivityMetricsLaunchObserverRegistry launchObserverRegistry;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    launchObserverRegistry = ActivityTaskManagerService.this.mStackSupervisor.getActivityMetricsLogger().getLaunchObserverRegistry();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return launchObserverRegistry;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public ActivityManager.TaskSnapshot getTaskSnapshotNoRestore(int taskId, boolean reducedResolution) {
            return ActivityTaskManagerService.this.getTaskSnapshot(taskId, reducedResolution, false);
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public boolean isUidForeground(int uid) {
            boolean isUidForeground;
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    isUidForeground = ActivityTaskManagerService.this.isUidForeground(uid);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return isUidForeground;
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setDeviceOwnerUid(int uid) {
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.setDeviceOwnerUid(uid);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }

        @Override // com.android.server.wm.ActivityTaskManagerInternal
        public void setCompanionAppPackages(int userId, Set<String> companionAppPackages) {
            HashSet hashSet = new HashSet();
            for (String pkg : companionAppPackages) {
                int uid = ActivityTaskManagerService.this.getPackageManagerInternalLocked().getPackageUid(pkg, 0, userId);
                if (uid >= 0) {
                    hashSet.add(Integer.valueOf(uid));
                }
            }
            synchronized (ActivityTaskManagerService.this.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    ActivityTaskManagerService.this.mCompanionAppUidsMap.put(Integer.valueOf(userId), hashSet);
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }
    }

    private void handleActivityResumedLocked(ActivityRecord r) {
        if (r == null) {
            return;
        }
        xpActivityManagerService.xpActivityRecord xr = new xpActivityManagerService.xpActivityRecord(r.mActivityComponent, r.intent);
        xpActivityManagerService.get(this.mContext).onActivityResumed(xr);
        this.mWindowManager.mSharedDisplayContainer.onActivityResumed(r);
    }

    private void handleActivityStoppedLocked(ActivityRecord r) {
        if (r == null) {
            return;
        }
        this.mWindowManager.mSharedDisplayContainer.onActivityStopped(r);
    }

    public void setHomeState(ComponentName component, int state) {
        xpActivityManagerService.get(this.mContext).setHomeState(component, state);
    }

    public ArrayList<SharedDisplayContainer.SharedRecord> getSharedRecord() {
        ArrayList<SharedDisplayContainer.SharedRecord> sharedRecord;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                sharedRecord = SharedDisplayContainer.getSharedRecord(this.mRootActivityContainer);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return sharedRecord;
    }

    public void finishTaskActivity(int taskId) {
        TaskRecord task;
        ActivityStack.ActivityState state;
        ArrayList<ActivityRecord> list;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                ArrayList<ActivityRecord> activities = new ArrayList<>();
                try {
                    int size = this.mRootActivityContainer != null ? this.mRootActivityContainer.getChildCount() : 0;
                    for (int i = 0; i < size; i++) {
                        ActivityDisplay display = this.mRootActivityContainer.getChildAt(i);
                        int childCount = display != null ? display.getChildCount() : 0;
                        for (int j = 0; j < childCount; j++) {
                            ActivityStack stack = display.getChildAt(j);
                            if (stack != null && stack.getChildCount() != 0 && (list = stack.getHistoryActivitiesLocked()) != null && list.size() > 0) {
                                activities.addAll(list);
                            }
                        }
                    }
                    Iterator<ActivityRecord> it = activities.iterator();
                    while (it.hasNext()) {
                        ActivityRecord record = it.next();
                        if (record != null && (task = record.getTaskRecord()) != null && task.taskId == taskId && (state = record.getState()) != null && state != ActivityStack.ActivityState.DESTROYED && state != ActivityStack.ActivityState.DESTROYING) {
                            finishActivityAffinity(record.appToken.asBinder());
                        }
                    }
                } catch (Exception e) {
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void finishPackageActivity(String packageName) {
        ActivityStack.ActivityState state;
        ArrayList<ActivityRecord> list;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (TextUtils.isEmpty(packageName)) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ArrayList<ActivityRecord> activities = new ArrayList<>();
                try {
                    int size = this.mRootActivityContainer != null ? this.mRootActivityContainer.getChildCount() : 0;
                    for (int i = 0; i < size; i++) {
                        ActivityDisplay display = this.mRootActivityContainer.getChildAt(i);
                        int childCount = display != null ? display.getChildCount() : 0;
                        for (int j = 0; j < childCount; j++) {
                            ActivityStack stack = display.getChildAt(j);
                            if (stack != null && stack.getChildCount() != 0 && (list = stack.getHistoryActivitiesLocked()) != null && list.size() > 0) {
                                activities.addAll(list);
                            }
                        }
                    }
                    Iterator<ActivityRecord> it = activities.iterator();
                    while (it.hasNext()) {
                        ActivityRecord record = it.next();
                        if (record != null && packageName.equals(record.packageName) && (state = record.getState()) != null && state != ActivityStack.ActivityState.DESTROYED && state != ActivityStack.ActivityState.DESTROYING) {
                            finishActivityAffinity(record.appToken.asBinder());
                        }
                    }
                } catch (Exception e) {
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void performActivityChanged(Bundle extras) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mWindowManager.mSharedDisplayContainer.performActivityChanged(extras);
                xpActivityManagerService.get(this.mContext).performActivityChanged(extras);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void performTopActivityChanged(Bundle extras) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mWindowManager.mSharedDisplayContainer.performTopActivityChanged(extras);
                xpActivityManagerService.get(this.mContext).performTopActivityChanged(extras);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public ArrayList<ActivityRecord> getHistoryActivitiesLocked() {
        ArrayList<ActivityRecord> historyActivitiesLocked;
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                historyActivitiesLocked = SharedDisplayContainer.getHistoryActivitiesLocked(this.mRootActivityContainer);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return historyActivitiesLocked;
    }

    protected void setSharedId(String packageName, int sharedId) {
        xpLogger.i("ActivityTaskManager", "setSharedId sharedId=" + sharedId + " packageName=" + packageName);
        SharedDisplayContainer.setSharedId(packageName, sharedId);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onLaunchParamsLoaded(ArrayList<SharedDisplayContainer.LaunchParams> list) {
        this.mWindowManager.mSharedDisplayContainer.onLaunchParamsLoaded(list);
    }

    public void setFocusedAppNoChecked(int taskId) {
        this.mAmInternal.enforceCallingPermission("android.permission.MANAGE_ACTIVITY_STACKS", "setFocusedTask()");
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                TaskRecord task = this.mRootActivityContainer.anyTaskForId(taskId, 0);
                if (task != null) {
                    ActivityRecord r = task.topRunningActivityLocked();
                    if (r != null && r.moveFocusableActivityToTop("setFocusedTaskNoChecked")) {
                        this.mRootActivityContainer.resumeFocusedStacksTopActivities();
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }
}
